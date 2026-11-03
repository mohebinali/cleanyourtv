#!/usr/bin/env bash
# Puts the TV back to stock: re-enables every package listed in state/disabled.log, gives HOME, the screensaver
# and the accessibility slot back, and uninstalls the Prayer app.
# Set STOCK_HOME / STOCK_DREAM if your TV's stock launcher or screensaver isn't Google TV's.
. "$(dirname "$0")/env.sh"
STOCK_HOME="${STOCK_HOME:-com.google.android.apps.tv.launcherx/.home.HomeActivity}"
STOCK_DREAM="${STOCK_DREAM:-com.google.android.apps.tv.dreamx/.service.Backdrop}"
LOG="$ROOT/state/disabled.log"
if [ -f "$LOG" ]; then
  while read -r p; do
    [ -z "$p" ] || [ "$p" = tv.afnon.home ] && continue
    echo "$p: $(adb shell pm enable --user 0 "$p" 2>&1 | tr -d '\r')"
  done < "$LOG"
fi
adb shell cmd package set-home-activity "$STOCK_HOME"
adb shell settings put secure screensaver_components "$STOCK_DREAM"
adb shell settings delete secure enabled_accessibility_services
echo "tv.afnon.home: $(adb uninstall tv.afnon.home 2>&1 | tr -d '\r')"
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Done. Reboot the TV to finish."
