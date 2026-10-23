#!/usr/bin/env bash
# Usage: tools/shot.sh [name]  — saves a TV screenshot to state/shots/<name>.png
. "$(dirname "$0")/env.sh"
mkdir -p "$ROOT/state/shots"; f="$ROOT/state/shots/${1:-shot}.png"
adb exec-out screencap -p > "$f"; echo "$f"
