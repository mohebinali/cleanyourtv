#!/usr/bin/env bash
# Builds the Prayer app, installs it over the running copy and sets what it needs from the system: the Home role
# (so HomeTakeover may keep Home on the normal home) and the always-on service (the prayer card, Home, AirPlay).
# It asks for no permissions.
set -e
. "$(dirname "$0")/env.sh"
(cd "$ROOT" && ./gradlew assembleDebug -q)
adb install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" | tail -1
adb shell cmd package set-home-activity tv.afnon.home/.MainActivity >/dev/null
adb shell settings put secure enabled_accessibility_services tv.afnon.home/.RemoteKeys
adb shell settings put secure accessibility_enabled 1
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Prayer installed. Test the card with: adb shell am start -n tv.afnon.home/.Test --es test adhan"
