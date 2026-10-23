#!/usr/bin/env bash
# Records the 12 spoken lines in tools/announce-lines.txt (the reminder before each prayer and "its time has begun")
# into app/src/main/assets/announce/<name>.mp3 for Announce.java, with a Microsoft neural Arabic voice through
# edge-tts (pip install edge-tts; it needs the internet while it runs, the app never does). They are not committed
# (.gitignore): the voice is for your own use, not ours to redistribute. Without them the reminders still show,
# silently.
#   bash tools/fetch-announce.sh              # only the missing clips
#   FORCE=1 bash tools/fetch-announce.sh      # all of them again (after changing the voice or a line)
#   VOICE=ar-EG-ShakirNeural bash tools/fetch-announce.sh   # another voice: python -m edge_tts --list-voices | grep ^ar-
set -e
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEST="$ROOT/app/src/main/assets/announce"
VOICE="${VOICE:-ar-SA-HamedNeural}"

PY=""
for p in python3 python; do
  if command -v "$p" >/dev/null 2>&1 && "$p" -c 'import edge_tts' 2>/dev/null; then PY="$p"; break; fi
done
if [ -z "$PY" ]; then
  echo "fetch-announce: edge-tts not found (pip install edge-tts); the spoken reminders stay silent." >&2
  exit 1
fi

mkdir -p "$DEST"
echo "voice: $VOICE"
while IFS='|' read -r name text; do
  name="${name%$'\r'}"; text="${text%$'\r'}"
  [ -n "$name" ] || continue
  out="$DEST/$name.mp3"
  [ -z "$FORCE" ] && [ -s "$out" ] && continue
  "$PY" -m edge_tts --voice "$VOICE" --text "$text" --write-media "$out" 2>/dev/null
  echo "  $name.mp3 ($(( $(wc -c < "$out") / 1024 )) KB)"
done < "$ROOT/tools/announce-lines.txt"
echo "Spoken lines are in $DEST"
