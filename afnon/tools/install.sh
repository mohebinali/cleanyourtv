#!/usr/bin/env bash
# Builds the Prayer app, installs it over the running copy and sets what it needs from the system: the Home role
# (so HomeTakeover may keep Home on the normal home), the always-on service (the prayer card, Home, AirPlay, the TV's
# power), the HDMI-CEC settings that keeps, and the one adb-only grant (wireless debugging back on with Wi-Fi).
set -e
. "$(dirname "$0")/env.sh"
(cd "$ROOT" && ./gradlew assembleDebug -q)
adb install -r "$ROOT/app/build/outputs/apk/debug/app-debug.apk" | tail -1
adb shell cmd package set-home-activity tv.afnon.home/.MainActivity >/dev/null
adb shell settings put secure enabled_accessibility_services tv.afnon.home/.RemoteKeys
adb shell settings put secure accessibility_enabled 1
# Awake while the TV shows another input, so a phone's AirPlay can switch the TV over to the Chromecast.
adb shell cmd hdmi_control cec_setting set power_state_change_on_active_source_lost none
# Waking the Chromecast while the TV is off (TvPower) leaves the TV off.
adb shell cmd hdmi_control cec_setting set power_control_mode none
# No picture-in-picture for the AirPlay receiver (AirPlayReturn can't read it, and it outlives the phone).
adb shell appops set io.github.jqssun.airplay PICTURE_IN_PICTURE ignore
adb shell pm grant tv.afnon.home android.permission.WRITE_SECURE_SETTINGS
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME >/dev/null
echo "Prayer installed. Test the card with: adb shell am start -n tv.afnon.home/.Test --es test adhan"
