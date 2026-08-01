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

package to.lean.tools.gmail.importer.gmail;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import com.google.api.client.googleapis.batch.BatchRequest;
import com.google.api.client.googleapis.batch.json.JsonBatchCallback;
import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.AbstractInputStreamContent;
import com.google.api.client.http.HttpHeaders;
import com.google.api.services.gmail.Gmail;
import com.google.api.services.gmail.model.Label;
import com.google.api.services.gmail.model.BatchModifyMessagesRequest;
import com.google.api.services.gmail.model.ListLabelsResponse;
import com.google.api.services.gmail.model.ListMessagesResponse;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.ModifyMessageRequest;
import com.google.common.base.Verify;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Sets;
import com.google.common.io.ByteStreams;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import javax.inject.Inject;
import to.lean.tools.gmail.importer.CommandLineArguments;
import to.lean.tools.gmail.importer.local.LocalMessage;

/**
 * Exports an API for a user's mailbox. This is a wrapper around the user's Gmail mailbox as exposed
 * by the Gmail API. This is not a perfect interface as it assumes that the mailbox is static except
 * for the actions that it issues.
 */
class Mailbox {
  static final int TOO_MANY_CONCURRENT_REQUESTS_FOR_USER = 429;

  private final GmailService gmailService;
  private final User user;
  private final CommandLineArguments commandLineArguments;

  private Map<String, Label> labelsById;
  private Map<String, Label> labelsByName;

  @Inject
  Mailbox(GmailService gmailService, User user, CommandLineArguments commandLineArguments) {
    this.gmailService = gmailService;
    this.user = user;
    this.commandLineArguments = commandLineArguments;
  }

  void ensureLabels(Iterable<LocalMessage> messages) throws IOException {
    Set<String> needed = Sets.newHashSet();
    for (LocalMessage message : messages) {
      message.getFolders().stream()
          .map(this::normalizeLabelName)
          .filter(name -> !isSystemLabel(name))
          .forEach(needed::add);
    }
    for (String name : needed) {
      if (labelsByName.containsKey(name)) {
        continue;
      }
      Label created =
          gmailService
              .getServiceWithRetries()
              .users()
              .labels()
              .create(
                  user.getEmailAddress(),
                  new Label()
                      .setName(name)
                      .setLabelListVisibility("labelShow")
                      .setMessageListVisibility("show"))
              .execute();
      labelsByName.put(created.getName(), created);
      labelsById.put(created.getId(), created);
      System.err.println("Created Gmail label: " + created.getName());
    }
  }

  /** Ensures one archive label exists without interpreting local folders or message state. */
  void ensureArchiveLabel(String name) throws IOException {
    if (name == null || name.trim().isEmpty()) {
      return;
    }
    Label existing = findLabel(name);
    if (existing != null) {
      // Gmail exposes system labels using uppercase names (for example IMPORTANT), while a
      // Thunderbird/IMAP folder may be presented as Important. Reuse the existing system label.
      labelsByName.put(name, existing);
      return;
    }
    Label created =
        gmailService
            .getServiceWithRetries()
            .users()
            .labels()
            .create(
                user.getEmailAddress(),
                new Label()
                    .setName(name)
                    .setLabelListVisibility("labelShow")
                    .setMessageListVisibility("show"))
            .execute();
    labelsByName.put(created.getName(), created);
    labelsById.put(created.getId(), created);
    System.err.println("Created Gmail archive label: " + created.getName());
  }

  /** Adds the archive label only; it never removes Inbox, unread, spam, or other labels. */
  void applyArchiveLabel(String gmailMessageId, String name) throws IOException {
    Label label = findLabel(name);
    if (label == null) {
      throw new IOException("Archive label is unavailable: " + name);
    }
    // Gmail import can return a message ID before the message is available to the modify endpoint.
    // Wait up to about 90 seconds before leaving the durable `uploaded` receipt for a later resume.
    final int maximumAttempts = 9;
    for (int attempt = 1; attempt <= maximumAttempts; attempt++) {
      try {
        gmailService
            .getServiceWithRetries()
            .users()
            .messages()
            .modify(
                user.getEmailAddress(),
                gmailMessageId,
                new ModifyMessageRequest().setAddLabelIds(Collections.singletonList(label.getId())))
            .execute();
        return;
      } catch (GoogleJsonResponseException e) {
        if (e.getStatusCode() != 404 || attempt == maximumAttempts) {
          throw e;
        }
        long delay = Math.min(30_000L, 500L * (1L << (attempt - 1)));
        System.err.format(
            "Gmail has not exposed imported message %s for labeling; retrying in %d ms (%d/%d)%n",
            gmailMessageId, delay, attempt, maximumAttempts);
        try {
          Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while waiting to label imported message", interrupted);
        }
      }
    }
  }

  void connect() throws IOException {
    loadLabels();
  }

  /** Loads the user's labels and remembers them. */
  void loadLabels() throws IOException {
    ListLabelsResponse labelResponse =
        gmailService
            .getServiceWithRetries()
            .users()
            .labels()
            .list(user.getEmailAddress())
            .execute();

    Verify.verify(!labelResponse.isEmpty(), "could not get labels %s");

    List<Label> labels = labelResponse.getLabels();
    labelsByName = labels.stream().collect(toMap(Label::getName, label -> label));
    labelsById = labels.stream().collect(toMap(Label::getId, label -> label));
    System.err.format("Got labels: %s", labelsByName);
  }

  /** Reads fresh Gmail counters for a known label; it does not modify Gmail state. */
  Label getLabelStatus(String name) throws IOException {
    Label label = findLabel(name);
    if (label == null) {
      throw new IOException("Gmail label does not exist: " + name);
    }
    return gmailService
        .getServiceWithRetries()
        .users()
        .labels()
        .get(user.getEmailAddress(), label.getId())
        .execute();
  }

  int trashLabel(String name) throws IOException {
    Label label = labelsByName.get(name);
    if (label == null) {
      throw new IOException("Gmail label does not exist: " + name);
    }
    Gmail gmail = gmailService.getServiceWithRetries();
    String pageToken = null;
    List<String> messageIds = new ArrayList<>();
    do {
      ListMessagesResponse response =
          gmail
              .users()
              .messages()
              .list(user.getEmailAddress())
              .setLabelIds(Collections.singletonList(label.getId()))
              .setPageToken(pageToken)
              .setMaxResults(500L)
              .execute();
      if (response.getMessages() != null) {
        for (Message message : response.getMessages()) {
          messageIds.add(message.getId());
        }
      }
      pageToken = response.getNextPageToken();
    } while (pageToken != null && !pageToken.isEmpty());

    // Snapshot all IDs before mutating Gmail; otherwise moving messages to Trash while paging
    // can cause the same label query to return them again and create an unbounded loop.
    for (int start = 0; start < messageIds.size(); start += 1000) {
      List<String> batch = messageIds.subList(start, Math.min(start + 1000, messageIds.size()));
      gmail
          .users()
          .messages()
          .batchModify(
              user.getEmailAddress(),
              new BatchModifyMessagesRequest()
                  .setIds(batch)
                  .setAddLabelIds(Collections.singletonList("TRASH"))
                  .setRemoveLabelIds(Collections.singletonList(label.getId())))
          .execute();
    }
    return messageIds.size();
  }

  ArchiveLabelReconciliation reconcileArchiveLabel(String name, Set<String> expectedIds)
      throws IOException {
    Label label = findLabel(name);
    if (label == null) {
      throw new IOException("Gmail label does not exist: " + name);
    }
    Set<String> actualBefore = listMessageIdsForLabel(label.getId());
    Set<String> missing = new LinkedHashSet<>(expectedIds);
    missing.removeAll(actualBefore);
    Set<String> repairable = new LinkedHashSet<>();
    Set<String> missingInGmail = new LinkedHashSet<>();
    for (String gmailId : missing) {
      if (gmailMessageExists(gmailId)) {
        repairable.add(gmailId);
      } else {
        missingInGmail.add(gmailId);
      }
    }
    Gmail gmail = gmailService.getServiceWithRetries();
    List<String> repairableIds = new ArrayList<>(repairable);
    for (int start = 0; start < repairableIds.size(); start += 1000) {
      List<String> batch = repairableIds.subList(start, Math.min(start + 1000, repairableIds.size()));
      if (!batch.isEmpty()) {
        gmail
            .users()
            .messages()
            .batchModify(
                user.getEmailAddress(),
                new BatchModifyMessagesRequest()
                    .setIds(batch)
                    .setAddLabelIds(Collections.singletonList(label.getId())))
            .execute();
      }
    }
    Set<String> actualAfter = listMessageIdsForLabel(label.getId());
    Set<String> missingAfter = new LinkedHashSet<>(expectedIds);
    missingAfter.removeAll(actualAfter);
    Set<String> unexpected = new LinkedHashSet<>(actualAfter);
    unexpected.removeAll(expectedIds);
    return new ArchiveLabelReconciliation(
        expectedIds.size(),
        actualBefore.size(),
        missing.size(),
        repairable.size() - missingAfter.size(),
        missingAfter.size(),
        unexpected.size(),
        missingInGmail.size());
  }

  private Set<String> listMessageIdsForLabel(String labelId) throws IOException {
    Set<String> messageIds = new LinkedHashSet<>();
    String pageToken = null;
    do {
      ListMessagesResponse response =
          gmailService
              .getServiceWithRetries()
              .users()
              .messages()
              .list(user.getEmailAddress())
              .setLabelIds(Collections.singletonList(labelId))
              .setIncludeSpamTrash(true)
              .setPageToken(pageToken)
              .setMaxResults(500L)
              .execute();
      if (response.getMessages() != null) {
        for (Message message : response.getMessages()) {
          messageIds.add(message.getId());
        }
      }
      pageToken = response.getNextPageToken();
    } while (pageToken != null && !pageToken.isEmpty());
    return messageIds;
  }

  private Label findLabel(String name) {
    Label exact = labelsByName.get(name);
    if (exact != null) {
      return exact;
    }
    for (Map.Entry<String, Label> entry : labelsByName.entrySet()) {
      if (entry.getKey().equalsIgnoreCase(name)) {
        return entry.getValue();
      }
    }
    return null;
  }

  static final class ArchiveLabelReconciliation {
    final int journalIds;
    final int labelIdsBefore;
    final int missingBefore;
    final int added;
    final int missingAfter;
    final int unexpected;
    final int missingInGmail;

    ArchiveLabelReconciliation(
        int journalIds,
        int labelIdsBefore,
        int missingBefore,
        int added,
        int missingAfter,
        int unexpected,
        int missingInGmail) {
      this.journalIds = journalIds;
      this.labelIdsBefore = labelIdsBefore;
      this.missingBefore = missingBefore;
      this.added = added;
      this.missingAfter = missingAfter;
      this.unexpected = unexpected;
      this.missingInGmail = missingInGmail;
    }
  }

  /** Returns whether Gmail can currently retrieve this immutable message ID. */
  boolean gmailMessageExists(String gmailMessageId) throws IOException {
    try {
      gmailService
          .getServiceWithRetries()
          .users()
          .messages()
          .get(user.getEmailAddress(), gmailMessageId)
          .setFormat("minimal")
          .execute();
      return true;
    } catch (GoogleJsonResponseException e) {
      if (e.getStatusCode() == 404) {
        return false;
      }
      throw e;
    }
  }

  Multimap<LocalMessage, Message> mapMessageIds(Iterable<LocalMessage> localMessages) {
    Multimap<LocalMessage, Message> results = MultimapBuilder.hashKeys().linkedListValues().build();

    Gmail gmail = gmailService.getServiceWithRetries();
    for (LocalMessage localMessage : localMessages) {
      String messageId = localMessage.getMessageId();
      if (messageId == null || messageId.startsWith("generated:")) {
        continue;
      }
      try {
        ListMessagesResponse response =
            gmail
                .users()
                .messages()
                .list(user.getEmailAddress())
                .setQ("rfc822msgid:" + messageId)
                .setFields("messages(id)")
                .execute();
        if (response != null && !response.isEmpty()) {
          results.putAll(localMessage, response.getMessages());
          System.err.println("For " + messageId + " got:");
          response.getMessages().stream()
              .forEach(message -> System.err.println("  message id: " + message.getId()));
        }
      } catch (IOException e) {
        // The request initializer installed by GmailService applies exponential retry handling
        // to transient HTTP failures. Preserve the import's existing best-effort behavior if a
        // lookup still fails after those retries: the sync will attempt an upload instead.
        System.err.println("Could not get message: " + messageId);
        System.err.println("  because: " + e.getMessage());
      }
    }

    return results;
  }

  Message uploadMessage(LocalMessage localMessage) throws GoogleJsonResponseException {
    Gmail gmail = gmailService.getServiceWithRetries();
    byte[] bytes = localMessage.getRawContent();
    Message metadata = uploadMetadata();
    if (commandLineArguments.directInsert) {
      try {
        return uploadWithInsert(gmail, bytes, metadata);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    try {
      return uploadWithImport(gmail, bytes, metadata);
    } catch (GoogleJsonResponseException e) {
      if (e.getStatusCode() == 400) {
        System.err.println("Gmail import rejected the RFC822 message; trying direct insert fallback.");
        try {
          return uploadWithInsert(gmail, bytes, metadata);
        } catch (IOException fallback) {
          System.err.format("Direct insert fallback failed: %s%n", fallback.getMessage());
          throw new RuntimeException(fallback);
        }
      }
      throw e;
    } catch (IOException e) {
      System.err.format("Failed to upload message: \n");
      try {
        ByteStreams.copy(new ByteArrayInputStream(localMessage.getRawContent()), System.err);
      } catch (IOException e1) {
        System.err.format("Holy shit Batman! Error within an error! (%s)", e.getMessage());
      }
      throw new RuntimeException(e);
    }
  }

  /** Whether new archive uploads receive the archive label atomically with their insert. */
  boolean embedsArchiveLabelOnUpload() {
    return commandLineArguments.archiveLabel != null
        && labelsByName != null
        && labelsByName.containsKey(commandLineArguments.archiveLabel);
  }

  private Message uploadMetadata() {
    Message metadata = new Message();
    if (embedsArchiveLabelOnUpload()) {
      metadata.setLabelIds(
          Collections.singletonList(labelsByName.get(commandLineArguments.archiveLabel).getId()));
    }
    return metadata;
  }

  private Message uploadWithImport(Gmail gmail, byte[] bytes, Message metadata) throws IOException {
    Gmail.Users.Messages.GmailImport request =
        gmail
            .users()
            .messages()
            .gmailImport(user.getEmailAddress(), metadata, messageContent(bytes))
            .setInternalDateSource("dateHeader");
    reportUploadProgress(request.getMediaHttpUploader());
    Message result = request.execute();
    System.out.println(result.toPrettyString());
    return result;
  }

  private Message uploadWithInsert(Gmail gmail, byte[] bytes, Message metadata) throws IOException {
    Gmail.Users.Messages.Insert request =
        gmail
            .users()
            .messages()
            .insert(user.getEmailAddress(), metadata, messageContent(bytes))
            .setInternalDateSource("dateHeader");
    reportUploadProgress(request.getMediaHttpUploader());
    Message result = request.execute();
    System.out.println("Direct insert succeeded: " + result.getId());
    return result;
  }

  private AbstractInputStreamContent messageContent(byte[] bytes) {
    return new AbstractInputStreamContent("message/rfc822") {
      @Override
      public InputStream getInputStream() throws IOException {
        return new ByteArrayInputStream(bytes);
      }

      @Override
      public long getLength() throws IOException {
        return bytes.length;
      }

      @Override
      public boolean retrySupported() {
        return true;
      }
    };
  }

  private void reportUploadProgress(
      com.google.api.client.googleapis.media.MediaHttpUploader uploader) {
    uploader.setProgressListener(
        progress -> {
          System.out.format(
              "[%s] Progress: %2.0f        \r",
              progress.getUploadState().toString(), progress.getProgress() * 100);
          System.out.flush();
        });
    System.out.println();
  }

  Set<LocalMessage> syncLocalLabelsToGmail(Multimap<LocalMessage, Message> map)
      throws IOException {
    Deque<LabelUpdate> queue = new ArrayDeque<>();
    Map<LocalMessage, Integer> remaining = new IdentityHashMap<>();
    for (Map.Entry<LocalMessage, Message> entry : map.entries()) {
      queue.addLast(new LabelUpdate(entry.getKey(), entry.getValue()));
      remaining.merge(entry.getKey(), 1, Integer::sum);
    }
    Set<LocalMessage> synchronizedMessages =
        Collections.newSetFromMap(new IdentityHashMap<LocalMessage, Boolean>());
    if (queue.isEmpty()) {
      return synchronizedMessages;
    }

    int maximum = Math.max(1, commandLineArguments.labelMaxConcurrency);
    int initial = Math.max(1, commandLineArguments.labelInitialConcurrency);
    AdaptiveConcurrencyController controller =
        new AdaptiveConcurrencyController(initial, maximum);
    ExecutorService executor = Executors.newFixedThreadPool(maximum);
    CompletionService<LabelUpdateResult> completion = new ExecutorCompletionService<>(executor);
    Map<LabelUpdate, Integer> attempts = new IdentityHashMap<>();
    int inFlight = 0;
    int finished = 0;

    try {
      while (!queue.isEmpty() || inFlight > 0) {
        while (!queue.isEmpty() && inFlight < controller.concurrency()) {
          LabelUpdate update = queue.removeFirst();
          inFlight++;
          completion.submit(() -> applyLabelUpdate(update));
        }

        if (inFlight == 0) {
          continue;
        }
        LabelUpdateResult result = completion.take().get();
        inFlight--;

        if (result.success) {
          int left = remaining.get(result.update.localMessage) - 1;
          remaining.put(result.update.localMessage, left);
          if (left == 0) {
            synchronizedMessages.add(result.update.localMessage);
          }
          int before = controller.concurrency();
          controller.recordSuccess();
          reportLabelConcurrencyChange(before, controller.concurrency());
          finished++;
          reportLabelProgress(finished, map.size(), controller.concurrency());
          continue;
        }

        if (result.rateLimited) {
          int attempt = attempts.containsKey(result.update)
              ? attempts.get(result.update) + 1
              : 1;
          attempts.put(result.update, attempt);
          if (attempt <= AdaptiveConcurrencyController.MAX_ATTEMPTS) {
            int before = controller.concurrency();
            long delay = controller.recordRateLimit();
            reportLabelConcurrencyChange(before, controller.concurrency());
            System.err.format(
                "Gmail rate limit on label update attempt %d/%d; retrying after %d ms%n",
                attempt, AdaptiveConcurrencyController.MAX_ATTEMPTS, delay);
            Thread.sleep(delay);
            queue.addFirst(result.update);
          } else {
            finished++;
            reportLabelProgress(finished, map.size(), controller.concurrency());
            System.err.format(
                "Giving up after Gmail rate limits for labels on message %s; leaving it uncheckpointed.%n",
                result.update.message.getId());
          }
        } else {
          controller.recordFailure();
          finished++;
          reportLabelProgress(finished, map.size(), controller.concurrency());
          System.err.format(
              "Could not synchronize labels for message %s; leaving it uncheckpointed.%n",
              result.update.message.getId());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Label synchronization interrupted", e);
    } catch (ExecutionException e) {
      throw new IOException("Label synchronization worker failed", e.getCause());
    } finally {
      executor.shutdownNow();
    }
    return synchronizedMessages;
  }

  private LabelUpdateResult applyLabelUpdate(LabelUpdate update) {
    try {
      Set<String> labelNamesToAdd =
          update.localMessage.getFolders().stream()
              .map(this::normalizeLabelName)
              .collect(toSet());
      Set<String> labelNamesToRemove = Sets.newHashSet("SPAM", "TRASH");
      labelNamesToRemove.removeAll(labelNamesToAdd);

      if (update.localMessage.isStarred()) {
        labelNamesToAdd.add("STARRED");
        labelNamesToRemove.remove("STARRED");
      }
      if (update.localMessage.isUnread()) {
        labelNamesToAdd.add("UNREAD");
        labelNamesToRemove.remove("UNREAD");
      } else {
        labelNamesToRemove.add("UNREAD");
        labelNamesToAdd.remove("UNREAD");
      }

      if (!labelNamesToAdd.contains("INBOX")) {
        labelNamesToRemove.add("INBOX");
        labelNamesToAdd.remove("INBOX");
      }

      List<String> labelIdsToAdd =
          labelNamesToAdd.stream()
              .map(labelName -> labelsByName.get(labelName).getId())
              .collect(toList());
      List<String> labelIdsToRemove =
          labelNamesToRemove.stream()
              .map(labelName -> labelsByName.get(labelName).getId())
              .collect(toList());

      gmailService
          .getServiceWithRetries()
          .users()
          .messages()
          .modify(
              user.getEmailAddress(),
              update.message.getId(),
              new ModifyMessageRequest()
                  .setAddLabelIds(labelIdsToAdd)
                  .setRemoveLabelIds(labelIdsToRemove))
          .execute();
      return new LabelUpdateResult(update, true, false);
    } catch (GoogleJsonResponseException e) {
      return new LabelUpdateResult(
          update, false, e.getStatusCode() == TOO_MANY_CONCURRENT_REQUESTS_FOR_USER);
    } catch (IOException e) {
      return new LabelUpdateResult(update, false, false);
    } catch (RuntimeException e) {
      return new LabelUpdateResult(update, false, isRateLimited(e));
    }
  }

  private boolean isRateLimited(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof GoogleJsonResponseException
          && ((GoogleJsonResponseException) current).getStatusCode()
              == TOO_MANY_CONCURRENT_REQUESTS_FOR_USER) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private void reportLabelConcurrencyChange(int before, int after) {
    if (before != after) {
      System.err.format("Adaptive label concurrency: %d -> %d%n", before, after);
    }
  }

  private void reportLabelProgress(int finished, int total, int concurrency) {
    System.err.format(
        "Label progress: %d/%d updates finished (concurrency=%d)%n",
        finished, total, concurrency);
  }

  private static final class LabelUpdate {
    private final LocalMessage localMessage;
    private final Message message;

    private LabelUpdate(LocalMessage localMessage, Message message) {
      this.localMessage = localMessage;
      this.message = message;
    }
  }

  private static final class LabelUpdateResult {
    private final LabelUpdate update;
    private final boolean success;
    private final boolean rateLimited;

    private LabelUpdateResult(LabelUpdate update, boolean success, boolean rateLimited) {
      this.update = update;
      this.success = success;
      this.rateLimited = rateLimited;
    }
  }

  String normalizeLabelName(String localLabel) {
    if (localLabel.equalsIgnoreCase("INBOX")) {
      return "INBOX";
    }
    if (localLabel.equalsIgnoreCase("DRAFTS")) {
      return "Import/Drafts";
    }
    if (localLabel.equalsIgnoreCase("TRASH")) {
      return "TRASH";
    }
    if (localLabel.equalsIgnoreCase("SPAM")) {
      return "SPAM";
    }
    String prefix = commandLineArguments.labelPrefix == null ? "" : commandLineArguments.labelPrefix;
    if (prefix.isEmpty() || localLabel.startsWith(prefix)) {
      return localLabel;
    }
    return prefix + localLabel;
  }

  private boolean isSystemLabel(String label) {
    return ImmutableSet.of("INBOX", "TRASH", "SPAM", "STARRED", "UNREAD").contains(label);
  }

  private void syncLabels(
      Gmail gmailApi,
      BiMap<String, String> labelIdToNameMap,
      Multimap<LocalMessage, Message> localMessageToGmailMessages)
      throws IOException {
    BatchRequest relabelBatch = gmailApi.batch();
    for (Map.Entry<LocalMessage, Message> entry : localMessageToGmailMessages.entries()) {
      LocalMessage localMessage = entry.getKey();
      Message gmailMessage = entry.getValue();

      Set<String> gmailLabels =
          gmailMessage.getLabelIds() == null
              ? ImmutableSet.of()
              : gmailMessage.getLabelIds().stream()
                  .map(labelIdToNameMap::get)
                  .collect(Collectors.toSet());

      List<String> missingLabelIds =
          localMessage.getFolders().stream()
              .map(folder -> folder.equalsIgnoreCase("Inbox") ? "INBOX" : folder)
              .filter(folder -> !gmailLabels.contains(folder))
              .map(folder -> labelIdToNameMap.inverse().get(folder))
              .collect(Collectors.toList());

      if (localMessage.isUnread() && !gmailLabels.contains("UNREAD")) {
        missingLabelIds.add("UNREAD");
      }
      if (localMessage.isStarred() && !gmailLabels.contains("STARRED")) {
        missingLabelIds.add("STARRED");
      }

      for (String folder : localMessage.getFolders()) {
        if (!gmailLabels.contains(folder)) {
          System.out.format(
              "Trying to add labels %s to %s\n",
              missingLabelIds.stream().map(labelIdToNameMap::get).collect(Collectors.joining(", ")),
              gmailMessage.getId());
          gmailApi
              .users()
              .messages()
              .modify(
                  user.getEmailAddress(),
                  gmailMessage.getId(),
                  new ModifyMessageRequest().setAddLabelIds(missingLabelIds))
              .queue(
                  relabelBatch,
                  new JsonBatchCallback<Message>() {
                    @Override
                    public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders)
                        throws IOException {
                      System.err.format(
                          "For label ids %s, got error: %s\n", missingLabelIds, e.toPrettyString());
                    }

                    @Override
                    public void onSuccess(Message message, HttpHeaders responseHeaders)
                        throws IOException {
                      System.out.format(
                          "Successfully added labels %s to %s\n",
                          missingLabelIds.stream()
                              .map(labelIdToNameMap::get)
                              .collect(Collectors.joining(", ")),
                          message.getId());
                    }
                  });
        }
      }
      if (relabelBatch.size() > 0) {
        relabelBatch.execute();
      }
    }
  }

  private void createMissingLabels(
      Gmail gmailApi, final BiMap<String, String> labelIdToNameMap, Set<LocalMessage> localMessages)
      throws IOException {

    Set<String> missingLabels =
        localMessages.stream()
            .flatMap(localMessage -> localMessage.getFolders().stream())
            .filter(folder -> !"INBOX".equalsIgnoreCase(folder))
            .filter(folder -> !labelIdToNameMap.containsValue(folder))
            .collect(Collectors.toSet());

    if (!missingLabels.isEmpty()) {
      BatchRequest batchRequest = gmailApi.batch();
      for (String label : missingLabels) {
        System.err.format("Adding label %s\n", label);
        gmailApi
            .users()
            .labels()
            .create(
                user.getEmailAddress(),
                new Label()
                    .setName(label)
                    .setLabelListVisibility("labelHide")
                    .setMessageListVisibility("show"))
            .queue(
                batchRequest,
                new JsonBatchCallback<Label>() {
                  @Override
                  public void onFailure(GoogleJsonError e, HttpHeaders responseHeaders)
                      throws IOException {
                    System.err.format("For label %s, got error: %s\n", label, e.toPrettyString());
                  }

                  @Override
                  public void onSuccess(Label label, HttpHeaders responseHeaders)
                      throws IOException {
                    labelIdToNameMap.put(label.getId(), label.getName());
                  }
                });
      }
      batchRequest.execute();
    }
  }
}
