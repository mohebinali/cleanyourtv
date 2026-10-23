#!/usr/bin/env bash
# Usage: tools/enable.sh pkg [pkg...]  — re-enables packages and drops them from state/disabled.log
. "$(dirname "$0")/env.sh"
for p in "$@"; do
  echo "$p: $(adb shell pm enable --user 0 "$p" 2>&1 | tr -d '\r')"
  [ -f "$ROOT/state/disabled.log" ] && sed -i "/^${p//./\.}\$/d" "$ROOT/state/disabled.log"
done
