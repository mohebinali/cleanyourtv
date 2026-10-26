#!/usr/bin/env bash
# Builds Afnon, installs it over the running copy and brings it straight back as the home screen. Also sets
# what Afnon needs from the system: HOME, the remote buttons service, the screensaver, TCL's auto-start
# grants, the radio's sleep-timer permission, location for the Wi-Fi name, and the two grants the Language pill
# needs to switch the whole TV between English and Arabic.
set -e
. "$(dirname "$0")/env.sh"
[ -n "$(ls "$ROOT/app/src/main/assets/adhan/"*.mp3 2>/dev/null)" ] || bash "$ROOT/tools/fetch-adhans.sh"
[ -n "$(ls "$ROOT/app/src/main/assets/announce/"*.mp3 2>/dev/null)" ] || bash "$ROOT/tools/fetch-announce.sh" || true
(cd "$ROOT" && ./gradlew assembleDebug -q)
adb install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" | tail -1
adb shell cmd package set-home-activity tv.afnon.home/.MainActivity >/dev/null
adb shell settings put secure enabled_accessibility_services tv.afnon.home/.RemoteKeys
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure screensaver_components tv.afnon.home/.AfnonDream
# TCL blocks auto-start for sideloaded apps; harmless on other brands.
adb shell appops set tv.afnon.home APP_AUTO_START allow 2>/dev/null || true
adb shell appops set tv.afnon.home APP_ASSOC_START allow 2>/dev/null || true
# Radio: the TV's sleep timer while listening. (No RECORD_AUDIO: Afnon asks for no microphone since 0.9.)
adb shell pm grant tv.afnon.home android.permission.WRITE_SECURE_SETTINGS
# Home's Wi-Fi name: Android 9+ hides the network name from apps without location.
adb shell pm grant tv.afnon.home android.permission.ACCESS_FINE_LOCATION
# Language pill: the TV's own language, as Settings > System > Language sets it.
adb shell pm grant tv.afnon.home android.permission.CHANGE_CONFIGURATION
adb shell appops set tv.afnon.home WRITE_SETTINGS allow
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Afnon installed and on screen."
