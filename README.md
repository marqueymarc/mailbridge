# Mail Importer for Gmail — Humboldt migration fork

This working copy is maintained outside `chatmisc` at:

`/Volumes/Humboldt/marc-data/mail-importer`

It is based on Google's archived `mail-importer` project and is intended for
the preserved Thunderbird mail tree on Humboldt. The source archives and the
current Thunderbird transfer remain untouched by this project.

Do you have an old Thunderbird mail archive that you POP3ed down from AOL?

Do you want to move those old messages to Gmail so that you can use the Gmail
app on your phone and still have access to everything?

If so, maybe _Mail Importer for Gmail_ is for you!

If you are trying to bulk import mbox format files to Google Workspace, you
should probably look at
[import-mailbox-to-gmail](https://github.com/google/import-mailbox-to-gmail).

**DISCLAIMER**: This is not an official Google product.

## What does it do?

_Mail Importer for Gmail_ will upload the contents of a Thunderbird mail archive
to Gmail and do its best to preserve the read state, flagged state, and folders
of the messages. As messages are uploaded verbatim, Gmail will have an exact
copy, including all attachments and headers.

_Mail Importer_ also makes sure to only upload messages that aren't already in
Gmail. This makes it easy to re-run the import multiple times if something goes
wrong.

## How can I run it?

This fork adds Humboldt-local OAuth and checkpoint paths, a `Legacy Mail/`
label prefix, automatic creation of missing labels, and an append-only
checkpoint ledger. It remains a command-line tool and must be tested with a
bounded pilot before a full import.

### Getting a Client Secret

Each developer needs a _client secret_ to identify their version of Mail
Importer to Google using
[OAuth2](https://developers.google.com/identity/protocols/OAuth2). To get a
client secret, you have to create a project in the
[Google Developers Console](https://github.com/googleads/googleads-dotnet-lib/wiki/How-to-create-OAuth2-client-id-and-secret),
configure it correctly, then download the generated credentials. The result
should be a JSON file named something like:

```
client_secret_729820383-898athoe9t33ntohuoc.apps.googleusercontent.com.json
```

Copy the downloaded desktop OAuth JSON to
`state/client_secret.json`. The `state/` directory is ignored by Git and must
never be committed or shared.

### Multiple Gmail accounts

The same OAuth client secret may be used for more than one personal Gmail
account, but each destination account must have its own OAuth credential store
and its own state directory. The state directory contains the append-only
upload journal, so sharing it would make a second account incorrectly look
complete and could suppress uploads. The client secret is reusable; the
refresh-token stores are not.

Create the Google Cloud project and desktop OAuth client once, then authorize
each account separately. In Google Cloud Console:

1. Enable the Gmail API for the project.
2. Configure the OAuth consent screen as an external app and add each Gmail
   address as a test user if the app is still in testing.
3. Create an OAuth Client ID of type **Desktop app** and download its JSON.
4. Keep that JSON outside Git, for example at
   `/Volumes/Humboldt/marc-data/mail-importer/state/client_secret.json`.

Use explicit per-account paths. This preserves the existing `shadowmarq` state
and prepares an independent profile for the second account:

```sh
ROOT=/Volumes/Humboldt/marc-data/mail-importer
SOURCE=/Volumes/Humboldt/marc-data/Thunderbird-ESR-Profile/Mail/Local\ Folders

# Existing destination: resume only with its existing journal and token store.
MAIL_IMPORTER_USER=shadowmarq@gmail.com \
MAIL_IMPORTER_STATE="$ROOT/state" \
MAIL_IMPORTER_CREDENTIAL_STORE="$ROOT/state/oauth-reauthorize-v2" \
MAIL_IMPORTER_CLIENT_SECRET="$ROOT/state/client_secret.json" \
MAIL_SOURCE_ROOT="$SOURCE" \
  "$ROOT/mail-migration.sh" config

# New destination: do not copy the shadowmarq journal or refresh-token store.
MAIL_IMPORTER_USER=marc.a.meyer@gmail.com \
MAIL_IMPORTER_STATE="$ROOT/state/accounts/marc.a.meyer" \
MAIL_IMPORTER_CREDENTIAL_STORE="$ROOT/state/oauth/marc.a.meyer" \
MAIL_IMPORTER_CLIENT_SECRET="$ROOT/state/client_secret.json" \
MAIL_SOURCE_ROOT="$SOURCE" \
  "$ROOT/mail-migration.sh" config
```

On the first command that contacts Gmail for a new account (`scan` is local
only; use a bounded `run` or `audit`), the tool starts the OAuth flow. Sign in
as the destination account, grant the requested Gmail scopes, and allow the
localhost callback. The account named by `MAIL_IMPORTER_USER` must match the
account authorized into that credential store. If the browser cannot complete
the callback, keep the terminal running and open the printed authorization URL
on the Mac; do not reuse another account's token directory.

For the second account, authorize and test independently before a full run:

```sh
MAIL_IMPORTER_USER=marc.a.meyer@gmail.com \
MAIL_IMPORTER_STATE="$ROOT/state/accounts/marc.a.meyer" \
MAIL_IMPORTER_CREDENTIAL_STORE="$ROOT/state/oauth/marc.a.meyer" \
MAIL_IMPORTER_CLIENT_SECRET="$ROOT/state/client_secret.json" \
MAIL_SOURCE_ROOT="$SOURCE" \
MAIL_MAX_MESSAGES=100 \
MAIL_IMPORTER_ARCHIVE_LABEL="Imported Important" \
  "$ROOT/mail-migration.sh" run Important

MAIL_IMPORTER_USER=marc.a.meyer@gmail.com \
MAIL_IMPORTER_STATE="$ROOT/state/accounts/marc.a.meyer" \
MAIL_IMPORTER_CREDENTIAL_STORE="$ROOT/state/oauth/marc.a.meyer" \
MAIL_IMPORTER_CLIENT_SECRET="$ROOT/state/client_secret.json" \
MAIL_SOURCE_ROOT="$SOURCE" \
  "$ROOT/mail-migration.sh" verify Important
```

The pilot journal is under the second account's state directory and cannot
consume the `shadowmarq` journal. After review, remove `MAIL_MAX_MESSAGES` and
reuse the same account-specific variables to resume or complete the mailbox.
A single process lock applies within one state directory; separate account
directories can be run sequentially or concurrently, subject to Gmail quotas.

### Building Mail Importer

Mail Importer uses [Maven](https://maven.apache.org/). This means that it will
download all of the necessary dependencies automatically when you build it like
this:

```
mvn clean package assembly:single
```

This will produce a runnable `.jar` file in
`target/mail-importer-0.0.0-SNAPSHOT-jar-with-dependencies.jar`.

### Running Mail Importer

Once Mail Importer is built, you can run it like:

```
java -jar ./target/mail-importer-0.0.0-SNAPSHOT-jar-with-dependencies.jar \
    --mailbox DIRECTORY \
    --user shadowmarq@gmail.com
```

where `DIRECTORY` is the Thunderbird _mailbox_ to open. It may be either a
Thunderbird mailbox directory or a standalone mbox file such as
`Local Folders/Important`.

### Humboldt runner

For the Humboldt migration, use the repository runner so the mailbox names,
checkpoint files, OAuth paths, label prefix, and concurrency bounds stay in one
auditable place:

```sh
./mail-migration.sh build
  ./mail-migration.sh scan marc-old
  ./mail-migration.sh run marc-old
  ./mail-migration.sh status
```

The supported mailbox keys are `marc-old` for `Legacy marc.old` and
`legacy-import` for `Legacy Import`. Each upload and label pass is resumable
from its own append-only checkpoint ledger. The default upload bounds are 4 to
8 workers and the label bounds are 2 to 4; set the corresponding
`MAIL_*_CONCURRENCY` environment variables if a controlled test needs other
bounds.

The guarded two-mailbox workflow is:

```sh
./mail-migration.sh guarded
```

It completes `Legacy marc.old` and then pauses before scanning the handoff or
starting `Legacy Import`. After the cleanup inventory has been reviewed and
approved, release it explicitly:

```sh
./mail-migration.sh approve-import
```

The approval is a local sentinel at `state/cleanup-approved`; it is ignored by
Git and contains no credentials. Do not create it until cleanup is complete.
The guarded handoff then verifies every current source-message key against both
checkpoint ledgers before it starts `Legacy Import`; historical ledger lines
from earlier pilots do not count as current-source completion.

The Humboldt runner uses a fresh `v4-<folder>-journal.tsv` for each source
folder. The older
`marc-old-checkpoint.tsv` and `marc-old-label-checkpoint.tsv` files are retained
as historical evidence and are not reused. Version 3 captures the original
RFC822 `Message-ID` before JavaMail normalization, uses that stable identity for
upload checkpoints, and uses a separate message-plus-folder identity for label
checkpoints. This prevents JavaMail-generated IDs and folder changes from
turning a restart into a new upload pass.

When using a standalone mbox file, pass its filename as the mailbox argument;
the runner accepts both directories and regular files and derives the journal
from the file or folder name. For example:

```sh
MAIL_SOURCE_ROOT="/Volumes/Humboldt/marc-data/Thunderbird-ESR-Profile/Mail/Local Folders" \
  ./mail-migration.sh scan Important
```

### Archive upload mode

The normal runner is an archive uploader, not a Thunderbird-folder mirror. It
uploads concurrently, does not perform one Gmail lookup per source message, and
does not copy source folder labels. Each successful imported message receives
the one Gmail label `Local Imported` (override with
`MAIL_IMPORTER_ARCHIVE_LABEL`).

After an archive upload, the runner automatically reconciles the archive label
against the completed Gmail IDs in that folder's journal. It lists the current
members of the label, batch-adds the label to any journaled IDs that are
missing it, then lists the label again. This phase never uploads or deletes
messages. It reports unexpected label members and missing Gmail IDs for review;
it does not remove or re-upload them. To run the phase independently:

```sh
MAIL_IMPORTER_ARCHIVE_LABEL="Imported Important" \
  ./mail-migration.sh reconcile-label Important
```

The reconciliation result includes journal IDs, label IDs before repair,
missing IDs, IDs repaired, remaining missing IDs, unexpected label members, and
IDs no longer retrievable from Gmail. A journal ID that is no longer retrievable
is reported rather than re-uploaded, because re-uploading it could create a
duplicate after a delayed Gmail response. The membership scan includes Spam and
Trash; Gmail's label summary counters may exclude those system locations, so
the reconciliation result is the authoritative membership check.

This means a label can contain messages that Gmail currently places in Spam or
Trash. They still carry the archive label and remain in the journal, but they
may not appear in the ordinary label count or normal mailbox view. The
reconciliation pass does not move, restore, delete, or permanently purge such
messages; it only adds the requested archive label when it is missing.
The command fails if any journaled ID remains unlabeled after the repair pass.

#### Gmail conversations are not duplicate detection

Gmail groups related messages into a conversation. The number beside a sender
in the message list (for example, `Restaurant.com 6`) is the number of
individual messages in that one conversation, not six conversations. Gmail
also displays the union of labels held by messages in that conversation. Thus a
conversation can visibly show both `Legacy Mail/Legacy marc.old` and `Local
Imported` even though archive mode added only the latter to the newly imported
message; the former can belong to an older message already in the same thread.

That display does not make raw-message imports idempotent. A same-subject,
same-date source message may join an existing Gmail conversation while still
being stored as an additional individual message. Consequently, do not treat a
matching conversation or `Message-ID` search result as proof that a source
message was already uploaded, and do not start a full migration after a pilot
until its individual-message results are accepted. The safe default is to keep
ambiguous `in_flight` records unresolved rather than retrying them. A future
deduplication pass must compare the candidate Gmail message's content with the
source message, not merely compare its conversation, subject, or `Message-ID`.

Archive mode uses Gmail's direct `messages.insert` endpoint (rather than delivery-style
`messages.import`) and applies its single archive label in the same request. It immediately
reads back the returned Gmail ID before completing the journal entry. A response whose ID cannot
be read after short backoff remains `in_flight` for review; it is never silently accepted or
automatically resent.

Before the Gmail upload request, the v4 journal appends and forces an
`in_flight` record. On a Gmail response it writes `uploaded` with the returned
Gmail ID, applies `Local Imported`, then writes `completed`. Thus a restart can
finish an interrupted label application without re-uploading that message.
Completed records are skipped. An unresolved `in_flight` request is left for
review by default; `MAIL_RETRY_INFLIGHT=1` explicitly retries it and accepts a
small lost-response duplicate risk.

There is one OS-backed lock at `state/uploader.lock`, so only one writer can
use the destination and journals at a time. Folder journals remain separate:

```sh
./mail-migration.sh run marc-old
./mail-migration.sh verify marc-old
./mail-migration.sh run legacy-import
./mail-migration.sh verify legacy-import
```

For a future source folder directly beneath `MAIL_SOURCE_ROOT`, use its folder
name as the argument; the runner derives a sanitized matching journal name.
To run the checks and Java tests in one command:

```sh
./mail-migration.sh test
```

The importer sets Gmail's internal-date source to the message `Date` header,
while retaining the original raw bytes for checkpoint identity. MIME
normalization preserves the source `Message-ID` and canonicalizes an existing
source `Date` into a Gmail-safe RFC 2822 form; it never uses the upload time as
a message date. When a legacy message has no usable `Date`, the uploader
derives one from the earliest parseable `Received` timestamp. A
retry after a network failure therefore resumes from durable checkpoints
without relying on unstable local iteration order. OAuth refresh credentials,
client secrets, checkpoints, logs, and other runtime state remain outside the
revision.

Note that the Thunderbird `Mail` directory usually has several sub-directories
called `ImapMail`, `OfflineCache`, and `Mail`. Then under `Mail`, you should
find individual accounts, like `pop.mail.yahoo.com` or `pop.csi.com`, and
`Local Folders`. It is this last level of directory that contains the actual
_mailbox_.

For example, if you had old CompuServe mail on a Mac, you might run Mail
Importer like this:

```
    java -jar ./target/mail-importer-0.0.0-SNAPSHOT-jar-with-dependencies.jar \
    --mailbox /Users/me/Library/Thunderbird/my_profile/Mail/pop.csi.com
```

## Humboldt pilot

Do not run this while Thunderbird is actively uploading the same messages.
First record or stop that transfer. Then build and run a 100-message pilot:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/bin/mvn -q -DskipTests package

JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk/bin/java \
  -jar target/mail-importer-0.0.0-SNAPSHOT-jar-with-dependencies.jar \
  --mailbox "/Volumes/Humboldt/marc-data/Thunderbird-ESR-Profile/Mail/Local Folders/Legacy Import" \
  --user shadowmarq@gmail.com \
  --max_messages 100 \
  --label_prefix "Legacy Mail/" \
  --checkpoint state/import-checkpoint.tsv \
  --label_checkpoint state/import-label-checkpoint.tsv \
  --credential_store state/oauth \
  --client_secret state/client_secret.json
```

The first run opens the OAuth authorization flow. The upload checkpoint records
the source message identity and returned Gmail message ID. The label checkpoint
records successful label application. A rerun skips messages complete in both
ledgers and still performs Gmail-side Message-ID deduplication. After the pilot
is verified, repeat with `Legacy marc.old` using separate paths such as
`state/marc-old-checkpoint.tsv` and `state/marc-old-label-checkpoint.tsv`, then
remove `--max_messages` for the full run.

The importer starts with the runner's four upload workers, can increase to
eight after clean progress, and halves concurrency when Gmail returns a
rate-limit response. Override those bounds with
`MAIL_UPLOAD_INITIAL_CONCURRENCY` and `MAIL_UPLOAD_MAX_CONCURRENCY` in the
runner, or the corresponding Java flags when using the JAR directly. Label
updates start with two workers, can rise to four, and have their own
`MAIL_LABEL_INITIAL_CONCURRENCY` and `MAIL_LABEL_MAX_CONCURRENCY` bounds. The
upload checkpoint is written as soon as
the Gmail ID is known; the label checkpoint is written only after label updates
succeed.

Before resuming a migration, run the no-write Gmail audit. It performs only
RFC822 Message-ID lookups and reports which source messages are already present;
it never uploads, changes labels, or edits a checkpoint:

```sh
MAIL_AUDIT_MAX_MESSAGES=100 ./mail-migration.sh audit marc-old
```

Omit `MAIL_AUDIT_MAX_MESSAGES` only after reviewing the pilot result. A full
audit can issue one Gmail lookup per source message and is intentionally kept
separate from the upload command.

To repair labels for messages already recorded in a checkpoint, use
`--repair_labels`; this reuses the recorded Gmail IDs and does not upload
duplicates:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk/bin/java \
  -jar target/mail-importer-0.0.0-SNAPSHOT-jar-with-dependencies.jar \
  --mailbox "/Volumes/Humboldt/marc-data/Thunderbird-ESR-Profile/Mail/Local Folders/Legacy marc.old" \
  --user shadowmarq@gmail.com \
  --max_messages 200 \
  --repair_labels \
  --checkpoint state/marc-old-checkpoint.tsv \
  --label_checkpoint state/marc-old-label-checkpoint.tsv \
  --credential_store state/oauth \
  --client_secret state/client_secret.json
```

Before authorizing Gmail, the local parser can be checked without contacting
Google:

```sh
JAVA_HOME=/opt/homebrew/opt/openjdk /opt/homebrew/opt/openjdk/bin/java \
  -jar target/mail-importer-0.0.0-SNAPSHOT-jar-with-dependencies.jar \
  --mailbox "/Volumes/Humboldt/marc-data/Thunderbird-ESR-Profile/Mail/Local Folders/Legacy Import" \
  --scan_only
```

## Can I help make _Mail Importer_ better?

You bet! See the [CONTRIBUTING.md](CONTRIBUTING.md) file for more information.
