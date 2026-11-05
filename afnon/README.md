# Prayer

A small companion to the normal home (CleanYourTV's launcher, [`launcher/`](../launcher)) on a Google TV. It has no
screens of its own; its only permissions are turning Wi-Fi off and on, and, granted over adb, turning wireless
debugging back on (Privacy). Its package is still `tv.afnon.home`, its name before 0.24, so it updates in place.

## What it does

- **The prayer card.** When a prayer's time begins, the mihrab on its side slides in from the start edge of the
  screen (the left in English, the right in Arabic) over whatever is on: حَيَّ عَلَى الصَّلَاةِ, the prayer's adhan
  ("Maghrib Adhan", «أذان المغرب») and its time. It stays a minute, a gold line running out under the words, then
  slides back out. It makes no sound and never takes focus, so the programme goes on underneath. On Friday, Dhuhr is
  Jumu'ah. The words follow the TV's language, with 0-9 digits in both.
- **Prayer times** for Dammam, Umm al-Qura (as Saudi mosques keep it) with the standard Asr, worked out on the TV by
  [adhan-java](https://github.com/batoulapps/adhan-java). The place and method are in `PrayerDay.java`.
- **Home stays on the normal home.** Google TV sends Home to its own launcher whatever the default is, and that
  launcher can't be disabled safely (the setup wizard and Cast depend on it). The service opens the normal home over
  it whenever it comes up: on a Home press, and as soon as the TV comes on (`HomeTakeover.java`).
- **AirPlay.** A phone that mirrors while the TV shows another input switches the TV to the Chromecast once its
  picture comes up (HDMI-CEC One Touch Play, as the remote's Home button sends); one that mirrors under the
  screensaver ends it; with the TV off nothing happens and the TV stays off. When the phone disconnects, the
  receiver's screen closes and the TV goes back to Home (`AirPlayReturn.java`, for the
  [AirPlay receiver](https://github.com/jqssun/android-airplay-server)). Only a mirrored picture switches the TV, not
  a phone that merely connects. With the TV left on another input for 30 s, whatever plays on the Chromecast is
  paused.
- **The TV off, and back on.** Turned off, the Samsung puts the Chromecast to standby, and turned on with its own
  remote it wakes it only at the end of its own handshake, 13 s after its HDMI is up. So when the TV goes off the
  Chromecast's Wi-Fi goes off, no phone (YouTube's Cast button, AirPlay, the Google Home app) sees it while the TV is
  off, and 10 s later it is woken back up, ready: it is on the screen as soon as the TV comes back, to the home. A
  TV turned back on within the 10 s wakes it itself, the slower way it always did, and Home is then pressed: woken
  that way the Chromecast isn't HDMI-CEC's active source, and the Samsung sends its remote's buttons only to that.
  The Samsung drops its HDMI hot-plug for a second as it powers up, and selects the Chromecast as it comes up on its
  input, either of which brings Wi-Fi back (`TvPower.java`), and where the TV was showing the Chromecast, Home is
  pressed then, so the Samsung's remote works at once rather than after its own 12 s check of its HDMI devices; a
  remote's buttons bring Wi-Fi back too, in case. The AirPlay receiver leaves Wi-Fi just before it goes, through its
  own usb_only setting, so iPhones drop it from Screen Mirroring at once. Waking the
  Chromecast leaves the TV off only with its HDMI-CEC power_control_mode set to none (Install), so the Chromecast's
  own power button no longer turns the TV off or on.

All of it runs in one accessibility service, `RemoteKeys` (its old name, which the TV has turned on), because Android
keeps an enabled accessibility service running whatever is on screen.

## Privacy

No network access and no microphone. Its permissions turn Wi-Fi off while the TV is off and back on as it comes on,
which only apps built for Android 9 or before may do (hence targetSdk 28), and, granted over adb, write one secure
setting: wireless debugging, back on with Wi-Fi where it was on (Android turns it off with Wi-Fi). It sets one setting
of the AirPlay receiver, usb_only, on while the TV is off, for which the receiver asks DUMP, also granted over adb;
the app dumps nothing with it. The service reads
no app's screen except the AirPlay receiver's (for "1 connected" / "0 connected"), and which app is in front (its name
only); it notices that the focus moved, not what took it.
It reads one system setting, the TV's HDMI-CEC state as the Chromecast's vendor service reports it (whether the TV
shows the Chromecast), and hears the HDMI sound output being unplugged and plugged back in.
To tell when a phone starts mirroring under the screensaver it notices that media sound has started playing; Android
tells any app which kinds of sound are playing, not whose, and nothing is recorded. Nothing read is kept.

## Install

```sh
export TV=192.168.68.118:5555   # the TV's adb address
tools/install.sh
```

or by hand, with an APK from the Build Homes release:

```sh
adb install -r afnon.apk
adb shell cmd package set-home-activity tv.afnon.home/.MainActivity
adb shell settings put secure enabled_accessibility_services tv.afnon.home/.RemoteKeys
adb shell settings put secure accessibility_enabled 1
# Stay awake while the TV shows another input, so a phone's AirPlay can switch the TV over. Google TV's default,
# standby_now, sleeps 30 s after the TV leaves the Chromecast (the app pauses what plays then instead).
adb shell cmd hdmi_control cec_setting set power_state_change_on_active_source_lost none
# Waking the Chromecast while the TV is off leaves the TV off (no One Touch Play on wake, no <Standby> on sleep).
adb shell cmd hdmi_control cec_setting set power_control_mode none
# No picture-in-picture for the AirPlay receiver: on Home with a phone on it, it would shrink into a corner window,
# unreadable to the app, that stays over every app after the phone leaves.
adb shell appops set io.github.jqssun.airplay PICTURE_IN_PICTURE ignore
# Wireless debugging back on with Wi-Fi (optional: without it, it stays off after the TV's first time off).
adb shell pm grant tv.afnon.home android.permission.WRITE_SECURE_SETTINGS
# The AirPlay receiver off Wi-Fi while the TV is off, so iPhones drop it at once (optional: without it, Screen
# Mirroring keeps showing it a while). The receiver takes its settings only from apps holding DUMP.
adb shell pm grant tv.afnon.home android.permission.DUMP
```

The Home role is what lets the service act; this app's Home itself only passes on to the normal home.

## Test the card

```sh
adb shell am start -n tv.afnon.home/.Test --es test adhan
```

The card for the next prayer comes up 8 s later, time to open another app first. Only the adb shell can start the
`.Test` alias. `--es test wifi` turns Wi-Fi off for 25 s and back on, the app doing both, so adb over Wi-Fi comes back
(never cut it with `svc wifi disable` over adb over Wi-Fi: nothing then turns it back on); `--es test dry` has the
TV's power handled with Wi-Fi only logged until the service restarts.

## The Samsung remote does nothing on the Chromecast

Twice (2026-10-24, 2026-11-05) the Samsung has refused every HDMI-CEC broadcast while directed messages still
worked: `adb shell dumpsys hdmi_control` shows the Chromecast's `<Report Physical Address>` and `<Active Source>`
ending `(NACK, NACK)` and no broadcast from the TV, so the TV never learns which input the Chromecast is on and sends
it no keys. To check, `adb shell cmd hdmi_control vendorcommand -t 4 -d 15 -a 00 -i true` and look for `4F:A0` at the
end of that history: `(ACK)` is healthy. A standby doesn't clear it, nor does restarting the Chromecast; pulling the
Samsung's mains plug for about 20 s does (unplug the other HDMI devices first, to rule them out). Not much longer:
30 s without the TV's HDMI, Android puts an idle Chromecast to sleep. Then `adb shell cmd hdmi_control onetouchplay`
tells the TV where the Chromecast is at once. Save `adb shell dumpsys hdmi_control` before any of it: the message
history, which shows what came just before the first refused broadcast, is lost when the Chromecast restarts.

## Files

| File | What |
|---|---|
| `RemoteKeys.java` | The always-on accessibility service that hosts the four below |
| `PrayerWatch.java`, `SideArchView.java` | The prayer card and its arch |
| `PrayerDay.java`, `Text.java` | The prayer times, and the card's words in Arabic or English |
| `HomeTakeover.java`, `ProbeActivity.java`, `MainActivity.java` | Home kept on the normal home |
| `AirPlayReturn.java`, `WakeActivity.java` | AirPlay: a phone's picture brought to the TV, Home again when it leaves |
| `TvPower.java` | The Chromecast kept ready and off Wi-Fi while the TV is off |

## Licence

MIT (`LICENSE`). adhan-java is MIT; the fonts (Amiri, Alexandria, Marcellus in `app/src/main/res/font/`) are under
the SIL Open Font Licence 1.1.
