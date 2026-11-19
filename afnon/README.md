# Prayer

A small companion to the normal home (CleanYourTV's launcher, [`launcher/`](../launcher)) on a Google TV. It has no
screens of its own and no network access; what it may do beyond an ordinary app is granted over adb (Privacy). Its
package is still `tv.afnon.home`, its name before 0.24, so it updates in place.

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
  Chromecast is hidden from phones (YouTube's Cast button, AirPlay) and 10 s later woken back up, ready: it is on
  the screen as soon as the TV comes back, to the home. Hidden, with Wi-Fi left on: the AirPlay receiver leaves
  Wi-Fi through its own usb_only setting, with the goodbye iPhones need to drop it from Screen Mirroring at once,
  and Android's restricted networking mode closes the network to apps, the Cast receiver among them (adb is let
  through); both lift in an instant when the TV comes on. The TV coming on is read from Android's own record of
  HDMI-CEC (`CecBus.java`): the TV saying its power is on, or asking after its HDMI devices, a second or two into
  powering up. Where the TV says it is on and was showing the Chromecast as it went off, Home is pressed then, so
  the Samsung's remote works at once (3.5 s after power-on, 2026-11-19) rather than after the Samsung's own 9 to 12 s
  check of its HDMI devices; turned straight back on (within 2 minutes) the TV only asks, and Home is pressed on
  that. A TV turned back on within the 10 s wakes the Chromecast itself, the slower way it always did, and Home is
  pressed then too. Home also opens Google's own home for half a second before the normal home is back over it, so
  it is pressed under a picture of the screen as it was, lifted once the normal home is in front (`HomeCover.java`).
  Waking the Chromecast leaves the TV off only with its HDMI-CEC power_control_mode set to none (Install), so the
  Chromecast's own power button no longer turns the TV off or on (`TvPower.java`).
- **Casting that answers.** With another home in front of Google TV's own, nothing holds the Cast receiver, and
  Android 14 freezes it like any background app: a phone then finds nothing to cast to until something thaws it.
  The service keeps a binding to it, so it is never frozen (`CastKeeper.java`).
- **The TV's remote reaching nothing, said on the TV.** When some other device on the TV's HDMI refuses the
  Chromecast's HDMI-CEC broadcasts (below), the TV passes it no buttons; a panel at the foot of the screen says what
  cures it: unplug the other HDMI devices and plug them back in (`JamNotice.java`).
- **Wireless debugging kept on**, where asked to (`--es adb keep`, below): Android turns it off whenever Wi-Fi drops,
  and a Chromecast at every restart (`AdbKeeper.java`).

All of it runs in one accessibility service, `RemoteKeys` (its old name, which the TV has turned on), because Android
keeps an enabled accessibility service running whatever is on screen.

## Privacy

No network access and no microphone. Granted over adb, it writes two system settings: restricted networking mode, on
while the TV is off, and wireless debugging, back on where the owner asked for that. Also granted over adb, DUMP:
with it the app sets one setting of the AirPlay receiver (usb_only, on while the TV is off; the receiver takes
settings only from apps holding DUMP) and reads one dump, the HDMI service's, for the HDMI-CEC messages between the
TV and the Chromecast (the TV powering up; broadcasts refused). It keeps a short diary of the TV going off and on
in its own files (`files/events.log`). It takes one picture of the screen as the TV comes on, shows it for a moment
over the stock home's flash and throws it away (the service's one use of taking screenshots). The service reads
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
# The network closed to apps while the TV is off, which hides the Cast receiver from phones, and wireless debugging
# turned back on where asked to (optional: without it the Cast receiver stays seen).
adb shell pm grant tv.afnon.home android.permission.WRITE_SECURE_SETTINGS
# The AirPlay receiver off Wi-Fi while the TV is off (it takes its settings only from apps holding DUMP), and
# HDMI-CEC's history read, for the TV powering up (optional: without it the TV coming on is seen later).
adb shell pm grant tv.afnon.home android.permission.DUMP
# Wireless debugging turned back on whenever Android turns it off (optional; `leave` stops it).
adb shell am start -n tv.afnon.home/.Test --es adb keep
```

The Home role is what lets the service act; this app's Home itself only passes on to the normal home.

## Test the card

```sh
adb shell am start -n tv.afnon.home/.Test --es test adhan
```

The card for the next prayer comes up 8 s later, time to open another app first. Only the adb shell can start the
`.Test` alias. `--es test gate` closes the network to apps for 25 s and opens it again, the app doing both;
`--es test dry` has the TV's power handled with the hiding only logged until the service restarts; `--es hide off`
leaves the Chromecast seen by phones while the TV is off (`on` hides it again). What the service saw and did is in
its diary: `adb shell run-as tv.afnon.home cat files/events.log`. Were the network ever left closed to apps (the
app's data cleared with the TV off, say): `adb shell settings put global restricted_networking_mode 0`.

## The Samsung remote does nothing on the Chromecast

Three times (2026-10-24, 11-05, 11-19) every HDMI-CEC broadcast was being refused while directed messages still
worked: `adb shell dumpsys hdmi_control` shows the Chromecast's `<Report Physical Address>` and `<Active Source>`
ending `(NACK, NACK)` and no broadcast from the TV, so the TV never learns which input the Chromecast is on and sends
it no keys. The service says so on the TV (`--es test jam` shows the panel) and notes it in its diary
(`files/events.log`). To check by hand,
`adb shell cmd hdmi_control vendorcommand -t 4 -d 15 -a 00 -i true` and look for `4F:A0` at the end of that history:
`(ACK)` is healthy. It was **another device on the TV's HDMI** saying no to them (2026-11-19): with the Chromecast's
HDMI plug out of the TV its broadcasts went through, back in they were refused within 3 s, and they went through for
good the moment the other HDMI devices were unplugged; plugged back in, those did not refuse again at once. So: unplug
the other HDMI devices from the TV and plug them back in. Restarting the Chromecast or the TV's standby doesn't clear
it; pulling the TV's mains plug did once (11-05), which also cuts the power of anything fed from its USB. Then
`adb shell cmd hdmi_control onetouchplay` tells the TV where the Chromecast is at once. Save
`adb shell dumpsys hdmi_control` before any of it: the message history, which shows what came just before the first
refused broadcast, is lost when the Chromecast restarts.

## Files

| File | What |
|---|---|
| `RemoteKeys.java` | The always-on accessibility service that hosts the rest |
| `PrayerWatch.java`, `SideArchView.java` | The prayer card and its arch |
| `PrayerDay.java`, `Text.java` | The prayer times, and the card's words in Arabic or English |
| `HomeTakeover.java`, `ProbeActivity.java`, `MainActivity.java` | Home kept on the normal home |
| `AirPlayReturn.java`, `WakeActivity.java` | AirPlay: a phone's picture brought to the TV, Home again when it leaves |
| `TvPower.java`, `CecBus.java` | The Chromecast kept ready and hidden from phones while the TV is off |
| `HomeCover.java` | The stock home's flash covered as Home is pressed for the TV's remote |
| `JamNotice.java` | What to do when the TV's remote reaches nothing, said on the TV |
| `CastKeeper.java` | The Cast receiver kept from being frozen |
| `AdbKeeper.java` | Wireless debugging kept on, where asked to |
| `Events.java` | The diary of what the service saw and did |

## Licence

MIT (`LICENSE`). adhan-java is MIT; the fonts (Amiri, Alexandria, Marcellus in `app/src/main/res/font/`) are under
the SIL Open Font Licence 1.1.
