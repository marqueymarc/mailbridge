#!/bin/zsh
set -euo pipefail

ROOT="$(cd -- "$(dirname -- "$0")/.." && pwd)"
RUNNER="$ROOT/mail-migration.sh"
GUARDED="$ROOT/run-guarded-migration.sh"

zsh -n "$RUNNER" "$GUARDED"

test_root="$(mktemp -d "${TMPDIR:-/tmp}/mail-importer-runner.XXXXXX")"
trap 'rm -rf "$test_root"' EXIT

help_output="$(MAIL_IMPORTER_ROOT="$test_root" "$RUNNER" help)"
[[ "$help_output" == *"approve-import"* ]]
[[ "$help_output" == *"guarded"* ]]

status_output="$(MAIL_IMPORTER_ROOT="$test_root" "$RUNNER" status)"
[[ "$status_output" == *"handoff=paused"* ]]

shadow_config="$(
  MAIL_IMPORTER_ROOT="$test_root" \
  MAIL_IMPORTER_USER="shadowmarq@gmail.com" \
  MAIL_IMPORTER_STATE="$test_root/state/shadowmarq" \
  MAIL_IMPORTER_CREDENTIAL_STORE="$test_root/oauth/shadowmarq" \
  "$RUNNER" config
)"
marc_config="$(
  MAIL_IMPORTER_ROOT="$test_root" \
  MAIL_IMPORTER_USER="marc.a.meyer@gmail.com" \
  MAIL_IMPORTER_STATE="$test_root/state/marc.a.meyer" \
  MAIL_IMPORTER_CREDENTIAL_STORE="$test_root/oauth/marc.a.meyer" \
  "$RUNNER" config
)"
[[ "$shadow_config" == *"user=shadowmarq@gmail.com"* ]]
[[ "$marc_config" == *"user=marc.a.meyer@gmail.com"* ]]
shadow_state="$(print -r -- "$shadow_config" | /usr/bin/awk -F= '$1 == "state" { print $2 }')"
marc_state="$(print -r -- "$marc_config" | /usr/bin/awk -F= '$1 == "state" { print $2 }')"
[[ "$shadow_state" != "$marc_state" ]]

print -r -- "runner checks passed"
