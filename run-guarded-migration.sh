#!/bin/zsh
set -euo pipefail

ROOT="${MAIL_IMPORTER_ROOT:-$(cd -- "$(dirname -- "$0")" && pwd)}"
STATE="${MAIL_IMPORTER_STATE:-$ROOT/state}"
LOG="$STATE/guarded-migration.log"
APPROVAL_FILE="$STATE/cleanup-approved"
RUNNER="$ROOT/mail-migration.sh"

mkdir -p "$STATE"

{
  print -r -- "[$(date -u +%FT%TZ)] START Legacy marc.old"
  "$RUNNER" run marc-old
  print -r -- "[$(date -u +%FT%TZ)] VERIFY Legacy marc.old"
  verify_output="$("$RUNNER" verify marc-old 2>&1)"
  print -r -- "$verify_output"
  missing="$(print -r -- "$verify_output" | sed -n 's/.*upload_missing=\([0-9][0-9]*\).*/\1/p' | tail -1)"
  if [[ -z "$missing" || "$missing" -ne 0 ]]; then
    print -r -- "[$(date -u +%FT%TZ)] HANDOFF_BLOCKED upload_missing=${missing:-unknown}"
    exit 2
  fi

  print -r -- "[$(date -u +%FT%TZ)] HANDOFF_PAUSED waiting_for=$APPROVAL_FILE"
  while [[ ! -f "$APPROVAL_FILE" ]]; do
    sleep 30
  done

  print -r -- "[$(date -u +%FT%TZ)] START Legacy Import"
  "$RUNNER" run legacy-import
  print -r -- "[$(date -u +%FT%TZ)] VERIFY Legacy Import"
  "$RUNNER" verify legacy-import
} >> "$LOG" 2>&1
