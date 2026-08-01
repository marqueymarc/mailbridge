#!/bin/zsh
set -euo pipefail

ROOT="${MAIL_IMPORTER_ROOT:-$(cd -- "$(dirname -- "$0")" && pwd)}"
SOURCE_ROOT="${MAIL_SOURCE_ROOT:-/Volumes/Humboldt/marc-data/Thunderbird-ESR-Profile/Mail/Local Folders}"
STATE="${MAIL_IMPORTER_STATE:-$ROOT/state}"
JAR="${MAIL_IMPORTER_JAR:-$ROOT/target/mail-importer-0.0.0-SNAPSHOT-jar-with-dependencies.jar}"
USER_EMAIL="${MAIL_IMPORTER_USER:-shadowmarq@gmail.com}"
ARCHIVE_LABEL="${MAIL_IMPORTER_ARCHIVE_LABEL:-Local Imported}"
LABEL_PREFIX="${MAIL_IMPORTER_LABEL_PREFIX:-Legacy Mail/}"
CREDENTIAL_STORE="${MAIL_IMPORTER_CREDENTIAL_STORE:-$STATE/oauth-reauthorize-v2}"
CLIENT_SECRET="${MAIL_IMPORTER_CLIENT_SECRET:-$STATE/client_secret.json}"
JAVA_BIN="${MAIL_IMPORTER_JAVA:-$(command -v java)}"
MAVEN_BIN="${MAIL_IMPORTER_MAVEN:-$(command -v mvn)}"
JAVA_HOME_PATH=""
if [[ -x /opt/homebrew/opt/openjdk@17/bin/java ]]; then
  JAVA_BIN="${MAIL_IMPORTER_JAVA:-/opt/homebrew/opt/openjdk@17/bin/java}"
  JAVA_HOME_PATH="/opt/homebrew/opt/openjdk@17"
fi

usage() {
  cat <<'EOF'
Usage: ./mail-migration.sh COMMAND [MAILBOX]

Commands:
  build                    Build the runnable importer JAR.
  test                     Run the shell checks and Maven test suite.
  scan MAILBOX             Count and validate a local mailbox (no Gmail access).
  run MAILBOX              Resume or start a concurrent archival upload.
  verify MAILBOX           Verify a v4 journal against its local source (no Gmail access).
  reconcile MAILBOX        Reconcile only ambiguous in-flight requests (no uploads).
  guarded                  Run marc.old, pause for cleanup approval, then Import.
  status                   Show processes and durable checkpoint counts.
  approve-import           Release the guarded handoff after cleanup review.
  help                     Show this help.

Mailbox names:
  marc-old                 Legacy marc.old
  legacy-import            Legacy Import
  FOLDER                   Any direct folder under MAIL_SOURCE_ROOT

Each direct folder receives a separate v4 journal whose name is derived from
the folder name. The guarded command waits for state/cleanup-approved. Do not create that file
until the cleanup inventory has been reviewed and approved.
EOF
}

die() {
  print -u2 -- "mail-migration: $*"
  exit 2
}

require_jar() {
  [[ -f "$JAR" ]] || die "JAR not found: $JAR (run '$0 build')"
}

mailbox_name() {
  case "$1" in
    marc-old) print -r -- "Legacy marc.old" ;;
    legacy-import) print -r -- "Legacy Import" ;;
    *"/"*|*".."*) die "mailbox must be a direct source folder name" ;;
    *)
      [[ -d "$SOURCE_ROOT/$1" || -f "$SOURCE_ROOT/$1" ]] || die "source mailbox folder or file not found: $SOURCE_ROOT/$1"
      print -r -- "$1"
      ;;
  esac
}

checkpoint_stem() {
  local normalized="$(mailbox_name "$1" | /usr/bin/tr '[:upper:] ' '[:lower:]-' | /usr/bin/tr -cs 'a-z0-9._-' '-')"
  print -r -- "v4-${normalized%-}"
}

audit_mailbox() {
  local mailbox_key="$1"
  local mailbox="$(mailbox_name "$mailbox_key")"
  local stem="$(checkpoint_stem "$mailbox_key")"
  local max_args=()
  if [[ -n "${MAIL_AUDIT_MAX_MESSAGES:-}" ]]; then
    max_args=(--max_messages "$MAIL_AUDIT_MAX_MESSAGES")
  fi
  run_java \
    --mailbox "$SOURCE_ROOT/$mailbox" \
    --user "$USER_EMAIL" \
    --audit_gmail \
    "${max_args[@]}" \
    --checkpoint "$STATE/$stem-journal.tsv" \
    --label_checkpoint "$STATE/$stem-label-checkpoint.tsv" \
    --credential_store "$CREDENTIAL_STORE" \
    --client_secret "$CLIENT_SECRET"
}

run_java() {
  require_jar
  command "$JAVA_BIN" -jar "$JAR" "$@"
}

run_upload_java() {
  require_jar
  if [[ -x /usr/bin/caffeinate ]]; then
    command /usr/bin/caffeinate -dims "$JAVA_BIN" -jar "$JAR" "$@"
  else
    command "$JAVA_BIN" -jar "$JAR" "$@"
  fi
}

run_maven() {
  if [[ -n "$JAVA_HOME_PATH" ]]; then
    env JAVA_HOME="$JAVA_HOME_PATH" "$MAVEN_BIN" "$@"
  else
    command "$MAVEN_BIN" "$@"
  fi
}

run_mailbox() {
  local mailbox_key="$1"
  local mailbox="$(mailbox_name "$mailbox_key")"
  local stem="$(checkpoint_stem "$mailbox_key")"
  local max_args=()
  if [[ -n "${MAIL_MAX_MESSAGES:-}" ]]; then
    max_args=(--max_messages "$MAIL_MAX_MESSAGES")
  fi
  mkdir -p "$STATE"
  run_upload_java \
    --mailbox "$SOURCE_ROOT/$mailbox" \
    --user "$USER_EMAIL" \
    --upload_initial_concurrency "${MAIL_UPLOAD_INITIAL_CONCURRENCY:-4}" \
    --upload_max_concurrency "${MAIL_UPLOAD_MAX_CONCURRENCY:-8}" \
    --skip_labels \
    --archive_label "$ARCHIVE_LABEL" \
    --direct_insert \
    --verify_after_upload \
    "${max_args[@]}" \
    --checkpoint "$STATE/$stem-journal.tsv" \
    --lock_file "$STATE/uploader.lock" \
    ${MAIL_RETRY_INFLIGHT:+--retry_inflight} \
    --credential_store "$CREDENTIAL_STORE" \
    --client_secret "$CLIENT_SECRET"
}

scan_mailbox() {
  local mailbox_key="$1"
  local max_args=()
  if [[ -n "${MAIL_MAX_MESSAGES:-}" ]]; then
    max_args=(--max_messages "$MAIL_MAX_MESSAGES")
  fi
  run_java --mailbox "$SOURCE_ROOT/$(mailbox_name "$mailbox_key")" --scan_only "${max_args[@]}"
}

verify_mailbox() {
  local mailbox_key="$1"
  local mailbox="$(mailbox_name "$mailbox_key")"
  local stem="$(checkpoint_stem "$mailbox_key")"
  local max_args=()
  if [[ -n "${MAIL_MAX_MESSAGES:-}" ]]; then
    max_args=(--max_messages "$MAIL_MAX_MESSAGES")
  fi
  run_java \
    --mailbox "$SOURCE_ROOT/$mailbox" \
    --skip_labels \
    "${max_args[@]}" \
    --checkpoint "$STATE/$stem-journal.tsv" \
    --verify_checkpoint
}

reconcile_mailbox() {
  local mailbox_key="$1"
  local mailbox="$(mailbox_name "$mailbox_key")"
  local stem="$(checkpoint_stem "$mailbox_key")"
  local max_args=()
  if [[ -n "${MAIL_MAX_MESSAGES:-}" ]]; then
    max_args=(--max_messages "$MAIL_MAX_MESSAGES")
  fi
  run_java \
    --mailbox "$SOURCE_ROOT/$mailbox" \
    --user "$USER_EMAIL" \
    --reconcile_inflight \
    "${max_args[@]}" \
    --checkpoint "$STATE/$stem-journal.tsv" \
    --credential_store "$CREDENTIAL_STORE" \
    --client_secret "$CLIENT_SECRET"
}

repair_labels() {
  local mailbox_key="$1"
  local mailbox="$(mailbox_name "$mailbox_key")"
  local stem="$(checkpoint_stem "$mailbox_key")"
  run_java \
    --mailbox "$SOURCE_ROOT/$mailbox" \
    --user "$USER_EMAIL" \
    --label_prefix "$LABEL_PREFIX" \
    --repair_labels \
    --checkpoint "$STATE/$stem-checkpoint.tsv" \
    --label_checkpoint "$STATE/$stem-label-checkpoint.tsv" \
    --credential_store "$CREDENTIAL_STORE" \
    --client_secret "$CLIENT_SECRET"
}

status() {
  ps -axo pid,ppid,state,etime,command | rg 'run-guarded-migration|mail-importer.*jar|caffeinate' | rg -v 'rg ' || true
  local journal
  for journal in "$STATE"/v4-*-journal.tsv(N); do
    local summary=$(/usr/bin/awk -F '\t' '
      { last[$2] = $1 }
      END {
        for (key in last) {
          if (last[key] == "completed") completed++
          else if (last[key] == "uploaded") uploaded++
          else if (last[key] == "in_flight") in_flight++
        }
        printf "completed=%d uploaded_waiting_for_label=%d in_flight=%d", completed, uploaded, in_flight
      }' "$journal")
    print -r -- "$(basename "$journal") $summary"
  done
  if [[ -f "$STATE/cleanup-approved" ]]; then
    print -r -- "handoff=approved"
  else
    print -r -- "handoff=paused (waiting for $STATE/cleanup-approved)"
  fi
}

[[ $# -gt 0 ]] || { usage; exit 0; }
command_name="$1"
shift

case "$command_name" in
  build)
    run_maven -q package -Dmaven.test.skip=true
    ;;
  test)
    command "$ROOT/scripts/test-runner.sh"
    run_maven test
    ;;
  scan)
    [[ $# -eq 1 ]] || die "scan requires a mailbox name"
    scan_mailbox "$1"
    ;;
  run)
    [[ $# -eq 1 ]] || die "run requires a mailbox name"
    run_mailbox "$1"
    ;;
  verify)
    [[ $# -eq 1 ]] || die "verify requires a mailbox name"
    verify_mailbox "$1"
    ;;
  reconcile)
    [[ $# -eq 1 ]] || die "reconcile requires a mailbox name"
    reconcile_mailbox "$1"
    ;;
  audit)
    [[ $# -eq 1 ]] || die "audit requires a mailbox name"
    audit_mailbox "$1"
    ;;
  repair-labels)
    [[ $# -eq 1 ]] || die "repair-labels requires a mailbox name"
    repair_labels "$1"
    ;;
  guarded)
    exec "$ROOT/run-guarded-migration.sh"
    ;;
  status)
    status
    ;;
  approve-import)
    mkdir -p "$STATE"
    : > "$STATE/cleanup-approved"
    print -r -- "Legacy Import handoff released via $STATE/cleanup-approved"
    ;;
  help|-h|--help)
    usage
    ;;
  *)
    usage
    die "unknown command '$command_name'"
    ;;
esac
