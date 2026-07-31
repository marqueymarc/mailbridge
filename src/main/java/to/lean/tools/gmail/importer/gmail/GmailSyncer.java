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

import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.services.gmail.model.Message;
import com.google.api.services.gmail.model.Label;
import com.google.common.base.Preconditions;
import com.google.common.collect.Multimap;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import to.lean.tools.gmail.importer.CommandLineArguments;
import to.lean.tools.gmail.importer.CheckpointStore;
import to.lean.tools.gmail.importer.CheckpointStores;
import to.lean.tools.gmail.importer.local.LocalMessage;

/**
 * Main sync logic. After construction, instances must be initialized by calling the {@link #init()}
 * method. Messages are sync'd in batches by calling the {@link #sync(List)} method.
 */
public class GmailSyncer {
  private static final int MAX_UPLOAD_ATTEMPTS = 5;
  private final Mailbox mailbox;
  private final CheckpointStore uploadCheckpointStore;
  private final CheckpointStore labelCheckpointStore;
  private final CommandLineArguments commandLineArguments;
  private boolean initialized = false;

  GmailSyncer(Mailbox mailbox) {
    this(mailbox, null, null, new CommandLineArguments());
  }

  @Inject
  GmailSyncer(
      Mailbox mailbox,
      CheckpointStores checkpointStores,
      CommandLineArguments commandLineArguments) {
    this(
        mailbox,
        checkpointStores == null ? null : checkpointStores.upload(),
        checkpointStores == null ? null : checkpointStores.labels(),
        commandLineArguments);
  }

  GmailSyncer(
      Mailbox mailbox,
      CheckpointStore uploadCheckpointStore,
      CheckpointStore labelCheckpointStore,
      CommandLineArguments commandLineArguments) {
    this.mailbox = mailbox;
    this.uploadCheckpointStore = uploadCheckpointStore;
    this.labelCheckpointStore = labelCheckpointStore;
    this.commandLineArguments = commandLineArguments;
  }

  /**
   * Connects to Gmail and loads the base information required for the sync.
   *
   * @throws IOException if something goes wrong with the connection
   */
  public void init() throws IOException {
    mailbox.connect();
    this.initialized = true;
  }

  /** Reads the current Gmail counters for one user label without changing any messages. */
  public Label getLabelStatus(String labelName) throws IOException {
    if (!initialized) {
      throw new IOException("GmailSyncer must be initialized before reading label status");
    }
    return mailbox.getLabelStatus(labelName);
  }

  /** Read-only existence check for a Gmail message ID returned by a prior upload. */
  public boolean gmailMessageExists(String gmailMessageId) throws IOException {
    if (!initialized) {
      throw new IOException("GmailSyncer must be initialized before checking a message");
    }
    return mailbox.gmailMessageExists(gmailMessageId);
  }

  /**
   * Synchronizes the given list of messages with Gmail. When this operation completes, all of the
   * messages will appear in Gmail with the same labels (folers) that they have in the local store.
   * The message state, including read/unread, will also be synchronized, but might not match
   * exactly if the message is already in Gmail.
   *
   * <p>Note that some errors can prevent some messages from being uploaded. In this case, the
   * failure policy dictates what happens.
   *
   * @param messages the list of messages to synchronize. These messages may or may not already
   *     exist in Gmail.
   * @throws IOException if something goes wrong with the connection
   */
  public void sync(List<LocalMessage> messages) throws IOException {
    Preconditions.checkState(initialized, "GmailSyncer.init() must be called first");
    if (commandLineArguments.skipLabels) {
      syncUploadOnly(messages);
      return;
    }
    List<LocalMessage> pending =
        messages.stream()
            .filter(
                message ->
                    needsLabelSynchronization(message))
            .collect(java.util.stream.Collectors.toList());
    if (pending.isEmpty()) {
      System.err.println("Progress: 0/0 messages to process; checkpoint is already complete.");
      return;
    }

    List<LocalMessage> needsUpload =
        pending.stream()
            .filter(message -> !hasUploadCheckpoint(message))
            .collect(java.util.stream.Collectors.toList());
    Multimap<LocalMessage, Message> map =
        com.google.common.collect.MultimapBuilder.hashKeys().linkedListValues().build();
    for (LocalMessage message : pending) {
      if (hasUploadCheckpoint(message)) {
        map.put(
            message,
            new Message().setId(uploadCheckpointStore.getGmailMessageId(message.getUploadCheckpointKey())));
      }
    }
    if (!needsUpload.isEmpty()) {
      map.putAll(mailbox.mapMessageIds(needsUpload));
    }
    ProgressReporter progress = new ProgressReporter(pending.size());
    for (LocalMessage message : pending) {
      if (map.containsKey(message)) {
        progress.markProcessed(1);
      }
    }
    progress.reportCurrent();
    checkpointMappedUploads(needsUpload, map);
    uploadPending(needsUpload, map, progress);
    mailbox.ensureLabels(pending);
    java.util.Set<LocalMessage> labelsSynchronized = mailbox.syncLocalLabelsToGmail(map);

    if (labelCheckpointStore != null) {
      for (Map.Entry<LocalMessage, Message> entry : map.entries()) {
        if (labelsSynchronized.contains(entry.getKey())) {
          labelCheckpointStore.markCompleted(
              entry.getKey().getLabelCheckpointKey(), entry.getValue().getId());
        } else {
          System.err.format(
              "Leaving message uncheckpointed because labels were not synchronized: %s%n",
              entry.getValue().getId());
        }
      }
    }
  }

  /** Fast archival path: no Gmail-wide lookup or source-folder label mutations. */
  private void syncUploadOnly(List<LocalMessage> messages) throws IOException {
    Map<String, LocalMessage> unique = new LinkedHashMap<>();
    int deferred = 0;
    if (commandLineArguments.archiveLabel != null) {
      mailbox.ensureArchiveLabel(commandLineArguments.archiveLabel);
    }
    for (LocalMessage message : messages) {
      String key = message.getUploadCheckpointKey();
      if (hasUploadCheckpoint(message)) {
        continue;
      }
      String uploadedGmailId =
          uploadCheckpointStore == null ? null : uploadCheckpointStore.getUploadedGmailMessageId(key);
      if (uploadedGmailId != null && !uploadedGmailId.isEmpty()) {
        applyArchiveLabelAndComplete(message, uploadedGmailId);
        continue;
      }
      if (uploadCheckpointStore != null
          && uploadCheckpointStore.isInFlight(key)
          && !commandLineArguments.retryInFlight) {
        deferred++;
        continue;
      }
      unique.putIfAbsent(key, message);
    }
    if (deferred > 0) {
      System.err.format(
          "Deferred %d ambiguous in-flight uploads; rerun with --retry_inflight only if duplicate risk is acceptable.%n",
          deferred);
    }
    if (unique.isEmpty()) {
      System.err.println("Progress: 0/0 messages to process; upload journal is complete or awaiting review.");
      return;
    }
    ProgressReporter progress = new ProgressReporter(unique.size());
    uploadPending(
        new java.util.ArrayList<>(unique.values()),
        com.google.common.collect.MultimapBuilder.hashKeys().linkedListValues().build(),
        progress);
  }

  /** Performs Gmail-side Message-ID lookups without uploading or changing labels. */
  public AuditResult audit(List<LocalMessage> messages) {
    List<LocalMessage> withMessageIds =
        messages.stream()
            .filter(message -> message.getMessageId() != null)
            .collect(java.util.stream.Collectors.toList());
    Multimap<LocalMessage, Message> map = mailbox.mapMessageIds(withMessageIds);
    int found = 0;
    for (LocalMessage message : withMessageIds) {
      if (map.containsKey(message)) {
        found++;
      }
    }
    return new AuditResult(
        messages.size(), withMessageIds.size(), found, withMessageIds.size() - found);
  }

  /**
   * Checks uncertain request records by Message-ID without treating a match as proof: old mail can
   * have the same Message-ID. Matching records remain explicitly ambiguous and are never labeled.
   */
  public ReconciliationResult reconcileInFlight(List<LocalMessage> messages) throws IOException {
    int candidates = 0;
    int matched = 0;
    int unresolved = 0;
    for (LocalMessage message : messages) {
      if (uploadCheckpointStore == null
          || ( !uploadCheckpointStore.isInFlight(message.getUploadCheckpointKey())
              && uploadCheckpointStore.getUploadedGmailMessageId(message.getUploadCheckpointKey()) == null)) {
        continue;
      }
      candidates++;
      Multimap<LocalMessage, Message> found =
          mailbox.mapMessageIds(java.util.Collections.singletonList(message));
      if (found.get(message).size() == 1) {
        matched++;
      }
      uploadCheckpointStore.markAmbiguous(message.getUploadCheckpointKey());
      unresolved++;
    }
    return new ReconciliationResult(candidates, matched, unresolved);
  }

  public static final class ReconciliationResult {
    public final int candidates;
    public final int matched;
    public final int unresolved;

    public ReconciliationResult(int candidates, int matched, int unresolved) {
      this.candidates = candidates;
      this.matched = matched;
      this.unresolved = unresolved;
    }

    public ReconciliationResult plus(ReconciliationResult other) {
      return new ReconciliationResult(
          candidates + other.candidates, matched + other.matched, unresolved + other.unresolved);
    }
  }

  public static final class AuditResult {
    public final int sourceMessages;
    public final int messagesWithIds;
    public final int gmailMatched;
    public final int gmailMissing;

    public AuditResult(int sourceMessages, int messagesWithIds, int gmailMatched, int gmailMissing) {
      this.sourceMessages = sourceMessages;
      this.messagesWithIds = messagesWithIds;
      this.gmailMatched = gmailMatched;
      this.gmailMissing = gmailMissing;
    }

    public AuditResult plus(AuditResult other) {
      return new AuditResult(
          sourceMessages + other.sourceMessages,
          messagesWithIds + other.messagesWithIds,
          gmailMatched + other.gmailMatched,
          gmailMissing + other.gmailMissing);
    }
  }

  private boolean needsLabelSynchronization(LocalMessage message) {
    if (!hasUploadCheckpoint(message)) {
      return true;
    }
    if (commandLineArguments.repairLabels) {
      return true;
    }
    return labelCheckpointStore == null
        || !labelCheckpointStore.isCompleted(message.getLabelCheckpointKey());
  }

  private boolean hasUploadCheckpoint(LocalMessage message) {
    if (uploadCheckpointStore == null) {
      return false;
    }
    String gmailId = uploadCheckpointStore.getGmailMessageId(message.getUploadCheckpointKey());
    return gmailId != null && !gmailId.isEmpty();
  }

  private void checkpointMappedUploads(
      List<LocalMessage> needsUpload, Multimap<LocalMessage, Message> map) throws IOException {
    if (uploadCheckpointStore == null) {
      return;
    }
    for (LocalMessage message : needsUpload) {
      if (map.containsKey(message)) {
        Message gmailMessage = map.get(message).iterator().next();
        uploadCheckpointStore.markCompleted(message.getUploadCheckpointKey(), gmailMessage.getId());
      }
    }
  }

  private void uploadPending(
      List<LocalMessage> pending, Multimap<LocalMessage, Message> map, ProgressReporter progress)
      throws IOException {
    Deque<LocalMessage> queue = new ArrayDeque<>();
    for (LocalMessage message : pending) {
      if (!map.containsKey(message)) {
        queue.addLast(message);
      }
    }
    if (queue.isEmpty()) {
      return;
    }

    int maximum = Math.max(1, commandLineArguments.uploadMaxConcurrency);
    int initial = Math.max(1, commandLineArguments.uploadInitialConcurrency);
    AdaptiveConcurrencyController controller =
        new AdaptiveConcurrencyController(initial, maximum);
    ExecutorService executor = Executors.newFixedThreadPool(maximum);
    CompletionService<UploadResult> completion = new ExecutorCompletionService<>(executor);
    Map<LocalMessage, Integer> attempts = new IdentityHashMap<>();
    int inFlight = 0;

    try {
      while (!queue.isEmpty() || inFlight > 0) {
        while (!queue.isEmpty() && inFlight < controller.concurrency()) {
          LocalMessage message = queue.removeFirst();
          if (uploadCheckpointStore != null) {
            uploadCheckpointStore.markInFlight(message.getUploadCheckpointKey());
          }
          inFlight++;
          completion.submit(() -> uploadOne(message));
        }

        if (inFlight == 0) {
          continue;
        }
        UploadResult result = completion.take().get();
        inFlight--;

        if (result.message != null) {
          map.put(result.localMessage, result.message);
          if (uploadCheckpointStore != null) {
            if (commandLineArguments.archiveLabel == null) {
              uploadCheckpointStore.markCompleted(
                  result.localMessage.getUploadCheckpointKey(), result.message.getId());
            } else {
              uploadCheckpointStore.markUploaded(
                  result.localMessage.getUploadCheckpointKey(), result.message.getId());
              if (mailbox.embedsArchiveLabelOnUpload()) {
                uploadCheckpointStore.markCompleted(
                    result.localMessage.getUploadCheckpointKey(), result.message.getId());
              } else {
                applyArchiveLabelAndComplete(result.localMessage, result.message.getId());
              }
            }
          }
          int before = controller.concurrency();
          controller.recordSuccess();
          reportConcurrencyChange(before, controller.concurrency());
          progress.markProcessed(controller.concurrency());
          continue;
        }

        if (result.rateLimited) {
          int attempt = attempts.containsKey(result.localMessage)
              ? attempts.get(result.localMessage) + 1
              : 1;
          attempts.put(result.localMessage, attempt);
          if (attempt <= MAX_UPLOAD_ATTEMPTS) {
            int before = controller.concurrency();
            long delay = controller.recordRateLimit();
            reportConcurrencyChange(before, controller.concurrency());
            System.err.format(
                "Gmail rate limit on upload attempt %d/%d; retrying after %d ms%n",
                attempt, MAX_UPLOAD_ATTEMPTS, delay);
            Thread.sleep(delay);
            queue.addFirst(result.localMessage);
          } else {
            System.err.println(
                "Giving up after Gmail rate limits for one message; it remains uncheckpointed.");
            progress.markProcessed(controller.concurrency());
          }
        } else {
          controller.recordFailure();
          System.err.format(
              "Could not upload message %s; it remains uncheckpointed: %s%n",
              result.localMessage.getUploadCheckpointKey(),
              result.errorMessage == null ? "unknown upload failure" : result.errorMessage);
          progress.markProcessed(controller.concurrency());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Upload interrupted", e);
    } catch (ExecutionException e) {
      throw new IOException("Upload worker failed", e.getCause());
    } finally {
      executor.shutdownNow();
    }
  }

  private void applyArchiveLabelAndComplete(LocalMessage message, String gmailMessageId)
      throws IOException {
    if (commandLineArguments.archiveLabel != null) {
      mailbox.applyArchiveLabel(gmailMessageId, commandLineArguments.archiveLabel);
    }
    if (uploadCheckpointStore != null) {
      uploadCheckpointStore.markCompleted(message.getUploadCheckpointKey(), gmailMessageId);
    }
  }

  private UploadResult uploadOne(LocalMessage localMessage) {
    try {
      Message uploaded = mailbox.uploadMessage(localMessage);
      if (commandLineArguments.verifyAfterUpload && !verifyAcceptedUpload(uploaded.getId())) {
        return new UploadResult(
            localMessage,
            null,
            false,
            "Gmail did not make returned message ID readable after upload: " + uploaded.getId());
      }
      return new UploadResult(localMessage, uploaded, false);
    } catch (GoogleJsonResponseException e) {
      return new UploadResult(
          localMessage,
          null,
          e.getStatusCode() == 429,
          e.getDetails() == null ? e.getMessage() : e.getDetails().toString());
    } catch (RuntimeException e) {
      return new UploadResult(localMessage, null, isRateLimited(e), describeFailure(e));
    }
  }

  private boolean verifyAcceptedUpload(String gmailMessageId) {
    for (int attempt = 1; attempt <= 4; attempt++) {
      try {
        if (mailbox.gmailMessageExists(gmailMessageId)) {
          return true;
        }
      } catch (IOException e) {
        throw new RuntimeException("Unable to verify returned Gmail message ID " + gmailMessageId, e);
      }
      if (attempt < 4) {
        try {
          Thread.sleep(250L * (1L << (attempt - 1)));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return false;
        }
      }
    }
    return false;
  }

  private String describeFailure(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current.getClass().getSimpleName() + ": " + current.getMessage();
  }

  private boolean isRateLimited(Throwable throwable) {
    Throwable current = throwable;
    while (current != null) {
      if (current instanceof GoogleJsonResponseException
          && ((GoogleJsonResponseException) current).getStatusCode() == 429) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private void reportConcurrencyChange(int before, int after) {
    if (before != after) {
      System.err.format("Adaptive upload concurrency: %d -> %d%n", before, after);
    }
  }

  private static final class ProgressReporter {
    private final int total;
    private final long startedNanos = System.nanoTime();
    private int processed;

    private ProgressReporter(int total) {
      this.total = total;
    }

    private void markProcessed(int concurrency) {
      processed++;
      double elapsedSeconds = (System.nanoTime() - startedNanos) / 1_000_000_000.0;
      double throughput = elapsedSeconds <= 0 ? processed : processed / elapsedSeconds;
      System.err.format(
          "Progress: %d/%d messages processed (%.2f msg/s; concurrency=%d)%n",
          processed, total, throughput, concurrency);
    }

    private void reportCurrent() {
      System.err.format("Progress: %d/%d messages processed; looking up and uploading remaining messages%n", processed, total);
    }
  }

  private static final class UploadResult {
    private final LocalMessage localMessage;
    private final Message message;
    private final boolean rateLimited;
    private final String errorMessage;

    private UploadResult(LocalMessage localMessage, Message message, boolean rateLimited) {
      this(localMessage, message, rateLimited, null);
    }

    private UploadResult(
        LocalMessage localMessage, Message message, boolean rateLimited, String errorMessage) {
      this.localMessage = localMessage;
      this.message = message;
      this.rateLimited = rateLimited;
      this.errorMessage = errorMessage;
    }
  }
}
