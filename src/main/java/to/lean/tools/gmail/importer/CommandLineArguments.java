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

import org.kohsuke.args4j.Option;

/** Encapsulation of all command line arguments. */
public class CommandLineArguments {

  @Option(
      name = "--mailbox",
      metaVar = "DIRECTORY",
      required = true,
      usage = "Specifies the root of the mailbox to open.")
  public String mailboxFileName;

  @Option(
      name = "--user",
      metaVar = "USER",
      usage =
          "Specifies the Gmail user name for whom to import mail. The "
              + "default value of 'me' means that the account will be taken from "
              + "the credentials provided. If any other value is specified, that "
              + "value must match the credentials. This mode allows administrators "
              + "to import mail into other accounts using their administrator "
              + "credentials.")
  public String user = "me";

  @Option(
      name = "--max_messages",
      usage =
          "The maximim number of messages to import to Gmail in this "
              + "run. This can be useful for testing an import.")
  public Integer maxMessages;

  @Option(
      name = "--upload_initial_concurrency",
      usage = "Initial number of concurrent Gmail uploads (default: 2).")
  public int uploadInitialConcurrency = 2;

  @Option(
      name = "--upload_max_concurrency",
      usage = "Maximum number of concurrent Gmail uploads (default: 4).")
  public int uploadMaxConcurrency = 4;

  @Option(
      name = "--label_initial_concurrency",
      usage = "Initial number of concurrent Gmail label updates (default: 1).")
  public int labelInitialConcurrency = 1;

  @Option(
      name = "--label_max_concurrency",
      usage = "Maximum number of concurrent Gmail label updates (default: 2).")
  public int labelMaxConcurrency = 2;

  @Option(
      name = "--checkpoint",
      metaVar = "PATH",
      usage = "Path to the durable upload checkpoint file.")
  public String checkpointPath = "state/checkpoint.tsv";

  @Option(
      name = "--lock_file",
      metaVar = "PATH",
      usage = "Exclusive lock held for the duration of a write-capable import.")
  public String lockFilePath = "state/uploader.lock";

  @Option(
      name = "--label_checkpoint",
      metaVar = "PATH",
      usage = "Path to the durable label checkpoint file.")
  public String labelCheckpointPath = "state/label-checkpoint.tsv";

  @Option(
      name = "--credential_store",
      metaVar = "DIRECTORY",
      usage = "Directory in which OAuth refresh credentials are stored.")
  public String credentialStorePath = "state/oauth";

  @Option(
      name = "--label_prefix",
      metaVar = "LABEL",
      usage = "Prefix applied to imported Gmail labels.")
  public String labelPrefix = "Legacy Mail/";

  @Option(
      name = "--client_secret",
      metaVar = "PATH",
      usage = "Path to the downloaded OAuth desktop-client JSON file.")
  public String clientSecretPath = "state/client_secret.json";

  @Option(
      name = "--scan_only",
      usage = "Read and count the local mailbox without contacting Gmail.")
  public boolean scanOnly;

  @Option(
      name = "--verify_checkpoint",
      usage = "Compare current local messages with the upload and label checkpoints without contacting Gmail.")
  public boolean verifyCheckpoint;

  @Option(
      name = "--audit_gmail",
      usage = "Look up local RFC822 Message-IDs in Gmail without uploading or changing labels.")
  public boolean auditGmail;

  @Option(
      name = "--repair_labels",
      usage = "Reapply labels to messages already recorded in the checkpoint without uploading.")
  public boolean repairLabels;

  @Option(
      name = "--skip_labels",
      usage = "Upload only; do not create, copy, or modify Gmail labels.")
  public boolean skipLabels;

  @Option(
      name = "--archive_label",
      metaVar = "LABEL",
      usage = "Add this one Gmail label to archival uploads without mirroring source folders.")
  public String archiveLabel;

  @Option(
      name = "--direct_insert",
      usage =
          "Use Gmail messages.insert instead of messages.import. Intended for preserved archives that must bypass delivery classification.")
  public boolean directInsert;

  @Option(
      name = "--verify_after_upload",
      usage =
          "Before completing a checkpoint, read back Gmail's returned message ID. An unreadable ID remains in_flight and is never retried automatically.")
  public boolean verifyAfterUpload;

  @Option(
      name = "--retry_inflight",
      usage = "Retry journaled in-flight uploads. This can create a duplicate if Gmail accepted a prior request whose response was lost.")
  public boolean retryInFlight;

  @Option(
      name = "--reconcile_inflight",
      usage = "Look up journaled in-flight Message-IDs in Gmail and record unique matches without uploading.")
  public boolean reconcileInFlight;

  @Option(
      name = "--label_status",
      metaVar = "LABEL",
      usage = "Read and report Gmail message and conversation totals for one label without modifying mail.")
  public String labelStatusName;

  @Option(
      name = "--trash_label",
      metaVar = "LABEL",
      usage = "Move every message carrying one Gmail label to Trash.")
  public String trashLabelName;

  @Option(
      name = "--verify_returned_ids",
      usage = "Read each completed checkpoint's returned Gmail message ID and report whether it still exists; no writes.")
  public boolean verifyReturnedIds;

  @Option(
      name = "--client_secret_resource_path",
      metaVar = "SECRET_RESOURCE_PATH",
      hidden = true,
      usage =
          "Path to the developer secret that developers can retrieve "
              + "from the developer console. Pre-built binaries should already "
              + "have this included.")
  public String clientSecretResourcePath = "/resources/client_secret.json";
}
