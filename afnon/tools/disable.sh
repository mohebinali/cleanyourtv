#!/usr/bin/env bash
# Usage: tools/disable.sh pkg [pkg...]
# Disables for user 0 (reversible, nothing is uninstalled), one pm call per package, and logs each one to
# state/disabled.log so restore.sh can bring it back.
. "$(dirname "$0")/env.sh"
mkdir -p "$ROOT/state"
for p in "$@"; do
  out=$(adb shell pm disable-user --user 0 "$p" 2>&1 | tr -d '\r')
  echo "$p: $out"
  case "$out" in *"disabled-user"*) grep -qxF "$p" "$ROOT/state/disabled.log" 2>/dev/null || echo "$p" >> "$ROOT/state/disabled.log";; esac
done
