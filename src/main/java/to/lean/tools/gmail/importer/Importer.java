/*
 * Copyright 2015 The Mail Importer Authors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package to.lean.tools.gmail.importer;

import com.google.api.client.util.Lists;
import com.google.api.services.gmail.model.Label;
import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import java.io.IOException;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import javax.inject.Provider;
import javax.mail.MessagingException;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import to.lean.tools.gmail.importer.gmail.GmailServiceModule;
import to.lean.tools.gmail.importer.gmail.GmailSyncer;
import to.lean.tools.gmail.importer.local.LocalMessage;
import to.lean.tools.gmail.importer.local.LocalStorage;
import to.lean.tools.gmail.importer.local.thunderbird.ThunderbirdModule;

/**
 * Copies messages from {@link to.lean.tools.gmail.importer.local.LocalStorage} to a {@link
 * to.lean.tools.gmail.importer.gmail.GmailSyncer} in batches. Batch iteration remains
 * single-threaded, while Gmail uploads within each batch use the syncer's adaptive worker pool.
 * When errors occur, an {@link to.lean.tools.gmail.importer.errorstrategy.ErrorStrategy} is used to
 * handle the error.
 */
public class Importer {
  private static final int BATCH_SIZE = 100;

  private final Logger logger;
  private final MailProvider<LocalStorage> storageProvider;
  private final Provider<GmailSyncer> gmailSyncerProvider;
  private final CommandLineArguments commandLineArguments;

  /**
   * Main entry point for running {@code Importer} as a stand-alone application. Commandline options
   * can be found in {@link to.lean.tools.gmail.importer.CommandLineArguments}.
   *
   * @param args the command line arguments
   * @throws MessagingException if there is a problem reading from the local store
   * @throws IOException if there is a problem with the Gmail connection
   */
  public static void main(String[] args) throws MessagingException, IOException {
    CommandLineArguments commandLineArguments = new CommandLineArguments();
    CmdLineParser commandLine = new CmdLineParser(commandLineArguments);
    try {
      commandLine.parseArgument(args);
    } catch (CmdLineException e) {
      System.err.println(e.getMessage());
      commandLine.printUsage(System.err);
      System.exit(1);
    }

    Injector injector =
        Guice.createInjector(
            new FlagsModule(commandLineArguments),
            new ThunderbirdModule(),
            new GmailServiceModule());

    Importer importer = injector.getInstance(Importer.class);
    importer.importMail();
  }

  @Inject
  Importer(
      Logger logger,
      MailProvider<LocalStorage> storageProvider,
      Provider<GmailSyncer> gmailSyncerProvider,
      CommandLineArguments commandLineArguments) {
    this.logger = logger;
    this.storageProvider = storageProvider;
    this.gmailSyncerProvider = gmailSyncerProvider;
    this.commandLineArguments = commandLineArguments;
  }

  public void importMail() throws MessagingException, IOException {
    if (commandLineArguments.labelStatusName != null) {
      labelStatus();
      return;
    }
    if (commandLineArguments.trashLabelName != null) {
      trashLabel();
      return;
    }
    LocalStorage storage = storageProvider.get();
    if (commandLineArguments.scanOnly) {
      scanLocalStorage(storage);
      return;
    }
    if (commandLineArguments.verifyCheckpoint) {
      verifyCheckpoint(storage);
      return;
    }
    if (commandLineArguments.verifyReturnedIds) {
      verifyReturnedIds(storage);
      return;
    }
    if (commandLineArguments.auditGmail) {
      auditGmail(storage);
      return;
    }
    if (commandLineArguments.reconcileInFlight) {
      reconcileInFlight(storage);
      return;
    }
    try (UploadRunLock ignored = UploadRunLock.acquire(commandLineArguments.lockFilePath)) {
      importToGmail(storage);
    }
  }

  private void verifyReturnedIds(LocalStorage storage) throws IOException {
    GmailSyncer gmailSyncer = gmailSyncerProvider.get();
    gmailSyncer.init();
    CheckpointStore checkpoint = new CheckpointStore(commandLineArguments.checkpointPath);
    int source = 0;
    int present = 0;
    int missing = 0;
    Iterator<LocalMessage> iterator = storage.iterator();
    while (iterator.hasNext() && keepImporting(source)) {
      LocalMessage message = iterator.next();
      source++;
      String key = message.getUploadCheckpointKey();
      String gmailId = checkpoint.getGmailMessageId(key);
      if (gmailId == null || gmailId.isEmpty() || !gmailSyncer.gmailMessageExists(gmailId)) {
        missing++;
        System.out.format("RETURNED_ID_MISSING gmail_id=%s source_message_id=%s%n", gmailId, message.getMessageId());
      } else {
        present++;
      }
    }
    System.out.format("RETURNED_ID_VERIFY source=%d present=%d missing=%d%n", source, present, missing);
  }

  private void labelStatus() throws IOException {
    GmailSyncer gmailSyncer = gmailSyncerProvider.get();
    gmailSyncer.init();
    Label label = gmailSyncer.getLabelStatus(commandLineArguments.labelStatusName);
    System.out.format(
        "LABEL_STATUS name=%s id=%s messages_total=%d messages_unread=%d threads_total=%d threads_unread=%d%n",
        label.getName(),
        label.getId(),
        label.getMessagesTotal() == null ? 0 : label.getMessagesTotal(),
        label.getMessagesUnread() == null ? 0 : label.getMessagesUnread(),
        label.getThreadsTotal() == null ? 0 : label.getThreadsTotal(),
        label.getThreadsUnread() == null ? 0 : label.getThreadsUnread());
  }

  private void trashLabel() throws IOException {
    GmailSyncer gmailSyncer = gmailSyncerProvider.get();
    gmailSyncer.init();
    int trashed = gmailSyncer.trashLabel(commandLineArguments.trashLabelName);
    System.out.format(
        "TRASH_LABEL name=%s messages_trashed=%d%n",
        commandLineArguments.trashLabelName, trashed);
  }

  private void importToGmail(LocalStorage storage) throws MessagingException, IOException {
    GmailSyncer gmailSyncer = gmailSyncerProvider.get();
    gmailSyncer.init();

    int messagesImported = 0;
    Iterator<LocalMessage> iterator = storage.iterator();
    while (iterator.hasNext() && keepImporting(messagesImported)) {
      List<LocalMessage> batch = Lists.newArrayListWithCapacity(BATCH_SIZE);
      for (int i = 0;
          i < BATCH_SIZE && iterator.hasNext() && keepImporting(messagesImported);
          i++) {
        LocalMessage message = iterator.next();
        logger.fine(() -> "Id: " + message.getMessageId());
        logger.fine(() -> "Folders: " + message.getFolders());
        batch.add(message);
        messagesImported++;
      }
      gmailSyncer.sync(batch);
    }
  }

  private void scanLocalStorage(LocalStorage storage) {
    int count = 0;
    int malformed = 0;
    int messageIds = 0;
    int duplicateMessageIds = 0;
    Set<String> seenMessageIds = new HashSet<>();
    Iterator<LocalMessage> iterator = storage.iterator();
    while (iterator.hasNext() && keepImporting(count)) {
      try {
        LocalMessage message = iterator.next();
        String messageId = message.getMessageId();
        if (messageId != null && !messageId.startsWith("generated:")) {
          messageIds++;
          if (!seenMessageIds.add(messageId)) {
            duplicateMessageIds++;
          }
        }
        message.getRawContent();
        count++;
      } catch (RuntimeException e) {
        malformed++;
        System.err.println("Unable to read local message " + (count + malformed) + ": " + e);
      }
    }
    System.out.format(
        "SCAN messages=%d malformed=%d message_id_present=%d message_id_duplicates=%d%n",
        count, malformed, messageIds, duplicateMessageIds);
  }

  private void auditGmail(LocalStorage storage) throws IOException {
    GmailSyncer gmailSyncer = gmailSyncerProvider.get();
    gmailSyncer.init();
    Iterator<LocalMessage> iterator = storage.iterator();
    GmailSyncer.AuditResult total = new GmailSyncer.AuditResult(0, 0, 0, 0);
    int processed = 0;
    while (iterator.hasNext() && keepImporting(processed)) {
      List<LocalMessage> batch = Lists.newArrayListWithCapacity(BATCH_SIZE);
      for (int i = 0; i < BATCH_SIZE && iterator.hasNext() && keepImporting(processed); i++) {
        batch.add(iterator.next());
        processed++;
      }
      total = total.plus(gmailSyncer.audit(batch));
      System.err.format("Audit: %d source messages examined%n", processed);
    }
    System.out.format(
        "AUDIT source=%d message_id_present=%d gmail_matched=%d gmail_missing=%d%n",
        total.sourceMessages, total.messagesWithIds, total.gmailMatched, total.gmailMissing);
  }

  private void verifyCheckpoint(LocalStorage storage) throws IOException {
    CheckpointStore upload = new CheckpointStore(commandLineArguments.checkpointPath);
    CheckpointStore labels =
        commandLineArguments.skipLabels ? null : new CheckpointStore(commandLineArguments.labelCheckpointPath);
    int messages = 0;
    int malformed = 0;
    int uploadMatched = 0;
    int labelMatched = 0;
    Iterator<LocalMessage> iterator = storage.iterator();
    while (iterator.hasNext() && keepImporting(messages)) {
      try {
        LocalMessage message = iterator.next();
        String uploadKey = message.getUploadCheckpointKey();
        messages++;
        if (upload.isCompleted(uploadKey)) {
          uploadMatched++;
        }
        if (labels == null || labels.isCompleted(message.getLabelCheckpointKey())) {
          labelMatched++;
        }
      } catch (RuntimeException e) {
        malformed++;
        System.err.println("Unable to verify local message " + (messages + malformed) + ": " + e);
      }
    }
    System.out.format(
        "VERIFY messages=%d malformed=%d upload_matched=%d label_matched=%d upload_missing=%d label_missing=%d%n",
        messages,
        malformed,
        uploadMatched,
        labelMatched,
        messages - uploadMatched,
        messages - labelMatched);
  }

  private void reconcileInFlight(LocalStorage storage) throws IOException {
    GmailSyncer gmailSyncer = gmailSyncerProvider.get();
    gmailSyncer.init();
    Iterator<LocalMessage> iterator = storage.iterator();
    GmailSyncer.ReconciliationResult total = new GmailSyncer.ReconciliationResult(0, 0, 0);
    int processed = 0;
    while (iterator.hasNext() && keepImporting(processed)) {
      List<LocalMessage> batch = Lists.newArrayListWithCapacity(BATCH_SIZE);
      for (int i = 0; i < BATCH_SIZE && iterator.hasNext() && keepImporting(processed); i++) {
        batch.add(iterator.next());
        processed++;
      }
      total = total.plus(gmailSyncer.reconcileInFlight(batch));
    }
    System.out.format(
        "RECONCILE candidates=%d matched=%d unresolved=%d%n",
        total.candidates, total.matched, total.unresolved);
  }

  private boolean keepImporting(int messagesImported) {
    return commandLineArguments.maxMessages == null
        || messagesImported < commandLineArguments.maxMessages;
  }
}
