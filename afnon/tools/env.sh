# Shared settings for the tools. Set TV to your TV's address first, for example:
#   export TV=192.168.1.50:5555
# ADB_BIN is found on its own: the adb on your PATH, else the Android SDK's (local.properties, ANDROID_HOME,
# ANDROID_SDK_ROOT or the default SDK folder). Set it to override.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

find_adb() {
  # type -P looks for a file only, never the adb() function below.
  type -P adb && return
  local sdk dir
  sdk=$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" 2>/dev/null | tr -d '\r')
  for dir in "$sdk" "$ANDROID_HOME" "$ANDROID_SDK_ROOT" "$LOCALAPPDATA/Android/Sdk" \
             "$HOME/AppData/Local/Android/Sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    [ -n "$dir" ] || continue
    for exe in "$dir/platform-tools/adb" "$dir/platform-tools/adb.exe"; do
      [ -x "$exe" ] && { echo "$exe"; return; }
    done
  done
  return 1
}

if [ -z "$ADB_BIN" ]; then
  ADB_BIN=$(find_adb) || {
    echo "adb not found. Install Android SDK platform-tools, or set ADB_BIN=/path/to/adb." >&2
    exit 1
  }
fi
if [ -z "$TV" ]; then
  echo "Set TV=<ip>:5555 first (the TV's address, shown in its network settings)." >&2
  exit 1
fi
adb() { "$ADB_BIN" -s "$TV" "$@"; }
