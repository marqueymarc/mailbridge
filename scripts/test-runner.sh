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

print -r -- "runner checks passed"
