# Prayer

A small companion to the normal home (CleanYourTV's launcher, [`launcher/`](../launcher)) on a Google TV. It has no
screens of its own and asks for no permissions. Its package is still `tv.afnon.home`, its name before 0.24, so it
updates in place.

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
  screensaver ends it; with the Chromecast in standby (the TV off) nothing happens and the TV stays off. When the
  phone disconnects, the receiver's screen closes and the TV goes back to Home (`AirPlayReturn.java`, for the
  [AirPlay receiver](https://github.com/jqssun/android-airplay-server)). Only a mirrored picture switches the TV, not
  a phone that merely connects. A TV turned on straight to another input leaves the Chromecast in standby, which the
  app can't tell from a TV that is off: mirroring switches it again once the Chromecast's input has been shown. With
  the TV left on another input for 30 s, whatever plays on the Chromecast is paused.

All of it runs in one accessibility service, `RemoteKeys` (its old name, which the TV has turned on), because Android
keeps an enabled accessibility service running whatever is on screen.

## Privacy

No network access, no microphone, no permissions at all. The service reads no app's screen except the AirPlay
receiver's (for "1 connected" / "0 connected"), and which app is in front (its name only).
It reads one system setting, the TV's HDMI-CEC state as the Chromecast's vendor service reports it (whether the TV
shows the Chromecast).
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
```

The Home role is what lets the service act; this app's Home itself only passes on to the normal home.

## Test the card

```sh
adb shell am start -n tv.afnon.home/.Test --es test adhan
```

The card for the next prayer comes up 8 s later, time to open another app first. Only the adb shell can start the
`.Test` alias.

## Files

| File | What |
|---|---|
| `RemoteKeys.java` | The always-on accessibility service that hosts the three below |
| `PrayerWatch.java`, `SideArchView.java` | The prayer card and its arch |
| `PrayerDay.java`, `Text.java` | The prayer times, and the card's words in Arabic or English |
| `HomeTakeover.java`, `ProbeActivity.java`, `MainActivity.java` | Home kept on the normal home |
| `AirPlayReturn.java`, `WakeActivity.java` | AirPlay: a phone's picture brought to the TV, Home again when it leaves |

## Licence

MIT (`LICENSE`). adhan-java is MIT; the fonts (Amiri, Alexandria, Marcellus in `app/src/main/res/font/`) are under
the SIL Open Font Licence 1.1.
