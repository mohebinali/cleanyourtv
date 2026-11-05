# Changelog

All notable changes to the Prayer app (Afnon before 0.24). Versions match `versionName` in `app/build.gradle`.

## [Unreleased]

## [0.33] - 2026-11-05

### Fixed
- iPhones kept showing the Chromecast in Screen Mirroring with the TV off (YouTube's Cast button didn't): cut off with
  Wi-Fi, the AirPlay receiver had no goodbye to send, and iOS keeps a device it has seen. The receiver now leaves
  Wi-Fi first, by its own usb_only setting (which restarts its server, unregistering it from mDNS), and Wi-Fi goes 2 s
  later; both come back with the TV.
- The Samsung's remote reached nothing for 12 s after the TV came on: the Samsung checks its HDMI devices first and
  only then selects the Chromecast (2026-11-05 15:59:25 to 15:59:37). Where the TV was showing the Chromecast as it
  went off, Home is now pressed as soon as the TV is seen powering up, which makes the Chromecast HDMI-CEC's active
  source at once.
- The HDMI hot-plug dropping with the TV still in standby is no longer taken for it coming on: Android asks the TV its
  power status as the hot-plug comes back, and a "standby" answer has the vendor's service write the CEC state again,
  which "on" doesn't (seen at every answer that day).

## [0.32] - 2026-11-05

### Fixed
- Wi-Fi came back 30 s after the TV went off: woken 30 s into standby, the Samsung drops its HDMI hot-plug for a
  second and stays off, which was taken for it powering up, so phones saw the Chromecast again. An HDMI drop within
  6 s of the app's wake is now the wake's own (a TV powered up then is seen by the CEC state instead, where it comes
  up on the Chromecast's input).
- The Samsung's remote did nothing after the TV was turned back on within the 30 s: the TV then wakes the Chromecast
  with its power key, which doesn't make it HDMI-CEC's active source, and the Samsung sends its remote's buttons only
  to that. Home is pressed then (One Touch Play). Powered up with the Chromecast awake, the Samsung selects it itself
  (<Set Stream Path>), and its remote works.
- No warning from Android at each TV-off for cancelling a wake that wasn't set.

## [0.31] - 2026-11-05

### Changed
- The Chromecast is woken 30 s after the TV goes off instead of 1.5 s, by an alarm (a Handler's delay stops while
  the processor is suspended, as it may be by then). The wake runs Android's whole HDMI-CEC start-up, which at 1.5 s
  fell in the Samsung's own change-over to standby; two days after 0.29 the Samsung was found refusing every CEC
  broadcast, so passing no remote keys to the Chromecast, until its mains plug was pulled. Not proven the cause; the
  wake is now well clear of it. A TV turned back on within the 30 s wakes the Chromecast itself, as before 0.29.
- Woken once per TV-off: the screen going off again within 10 minutes of that wake, the TV still off, leaves the
  Chromecast asleep, so no wake and standby can go back and forth.

### Added
- README: how to tell the Samsung is refusing HDMI-CEC broadcasts (its remote then does nothing on the Chromecast),
  and how to clear it: its mains plug out for about 20 s, the CEC history saved first.

## [0.30] - 2026-11-03

### Fixed (review of 0.29)
- The TV off is remembered (prefs "tv_off") for a restarted service or a reboot: the Chromecast comes up awake with no
  screen-off to go by, and a phone's mirror would otherwise have switched the TV back on (its Home press sends One
  Touch Play whatever power_control_mode is).
- A TV turned straight back on after the Chromecast was woken is seen: its HDMI reset no longer falls in a 6 s window
  kept for the wake's own (the wake only plugs HDMI back in, and rewake forgets anything before).
- The "not kept awake again" safety engages: decided as the CEC state turns "active" within 3 s of the wake, where
  the observer used to switch Wi-Fi back on first and the later check found nothing to do.
- Wireless debugging is asked back once only: on a network Android doesn't trust it puts a prompt on the TV each
  time (AdbDebuggingManager.verifyWifiNetwork), which the 5 s retries would have stacked.
- Focus moving with a window that comes up (a window gaining focus moves it), or under the screensaver, is not taken
  for a remote's buttons.
- Wi-Fi still turning on counts as on when the TV goes off again; the note that Wi-Fi was turned off here is written
  to disk before it goes, so a run that dies in between still turns it back on.
- A receiver screen opened while the TV was off is watched when the TV comes back, as after a wake, so the TV comes on
  to the normal home rather than "Waiting for connection...".
- The screensaver is ended for a receiver that opened "just as it came up" only where this app put it back; one the
  user starts with Back is theirs.
- The 30 s wait for a TV left on another input starts only with the Chromecast awake: its own sleep turns the CEC
  state "inactive" after the screen has gone off, and a wake then sends nothing to turn it back.
- A service started under the screensaver (an update) knows it is up (the app in front is the screensaver's).

## [0.29] - 2026-11-03

### Added
- The TV off, and back on (TvPower). Turned off, the Samsung sends HDMI-CEC <Standby>; turned on with its own remote
  it woke the Chromecast only at the end of its handshake, 13 s after its HDMI was up (18:28:08 hot-plug, 18:28:16
  Samsung-only command refused, 18:28:21 power key), so the home took that long, as the user found. Now:
  - The TV's <Standby> turns the CEC state "inactive" about 0.5 s before the Chromecast's screen goes off (17:22:07;
    the Chromecast's own sleep does so 0.35 s after it, 17:55:11, and is left alone). Wi-Fi then goes off, so no
    phone sees the Chromecast while the TV is off (YouTube's Cast button, AirPlay; both stayed advertised in standby,
    and Low Power Standby, "Limit network in standby", is unsupported here). The normal home is put in front and the
    Chromecast woken straight back up: with power_control_mode = none that wake sends no One Touch Play
    (HdmiCecLocalDevicePlayback.onInitializeCecComplete), so the TV stayed off (17:55:17, its power status standby).
  - The TV powering up drops its HDMI hot-plug for about a second (17:55:30.9 to 32.1, seen with the Chromecast
    awake; in standby the Chromecast's apps hear nothing of it), reported as the HDMI sound output unplugged and back:
    Wi-Fi back on, the screensaver ended if it is up. Two focus moves (a remote's buttons), the screensaver ended by
    hand, the CEC state turning "active", or another wake do it too, in case that is missed.
  - Wi-Fi needs targetSdk 28: apps built for later Androids may not turn it off and on (no confirmation dialog on
    this TV: config_showConfirmationDialogForThirdPartyAppsEnablingWifi is false). Wireless debugging, which Android
    turns off with Wi-Fi, is turned back on once Wi-Fi has connected, where it was on and WRITE_SECURE_SETTINGS has
    been granted over adb; Android does that silently only on a trusted network.
  - A wake that switched the TV on all the same (the CEC state turning "active" within 3 s) stops it for the run.
- Test hooks `--es test wifi` (Wi-Fi off 25 s, then back on, the app doing both) and `--es test dry` (Wi-Fi only
  logged). 18:11 that day, Wi-Fi cut with `svc wifi disable` over adb over Wi-Fi stayed off: the re-enable scheduled
  on the TV died with the adb session.

### Fixed (review of 0.28)
- No picture-in-picture for the AirPlay receiver (`appops set io.github.jqssun.airplay PICTURE_IN_PICTURE ignore`,
  install): Home during a mirror shrank it into a corner window, unreadable here, that stayed over every app after the
  phone left. Home for the switch now sends it behind the home for under a second instead.
- A receiver opened just as the screensaver came up (a phone connecting as the one put back starts, or as the idle one
  does) is taken as a phone's and the screensaver ended again, rather than left under it or closed.
- receiverLeft is kept per boot (Settings.Global.BOOT_COUNT): after a reboot no receiver screen is open, so the first
  screensaver no longer opens the normal home over a paused app; a restarted service still knows of one left open.
  With no receiver to close, nothing goes home.
- A session started by the 2.3 s look looks again when it ends, once a phone has really been read on it.

## [0.28] - 2026-11-03

### Fixed
- A TV turned off and straight back on no longer has what is then put on paused and sent home. A TV that wakes the
  Chromecast itself (turned on with its own remote, it sends HDMI-CEC's power key; seen 17:22:23) shows it without
  making it the active source, so HDMI-CEC still says "inactive", and the 30 s wait for a TV left on another input,
  begun as the TV went off, ran on. Standby (ACTION_SCREEN_OFF) now ends that wait.

### Notes
- Until the next Home press after such a wake, HDMI-CEC says "inactive" with the TV on the Chromecast, so the first
  phone to mirror then gets one Home press too many (the picture goes small over the home for under a second). Taking
  "inactive" as stale instead would leave a TV moved to another input afterwards, which changes nothing in the state,
  never switched back for a phone.
- The wait itself, about 7 s from the Samsung turning on to the Chromecast's picture, is the TV's: it asks the sleeping
  Chromecast for its details, sends a Samsung-only command, which the Chromecast refuses, and sends the standard power
  key only about 5 s later (17:22:16 to 17:22:23); the picture was up 1 s after that. Nothing on the Chromecast can see
  the TV turning on before then.

## [0.27] - 2026-11-03

### Added
- A phone mirroring while the TV shows another input switches the TV to the Chromecast. HDMI-CEC's active source is
  read from the vendor's report (Settings.Global nrdp_video_platform_capabilities, activeCecState "inactive" with
  another input on). When the phone's picture first comes up on the receiver, a Home press sends One Touch Play
  (<Text View On>, <Active Source>), as the remote's Home does, and the receiver is brought back over the home screen
  Home puts up. Only the picture counts: the receiver's buttons for it ("Exit fullscreen", "Fullscreen"), or its size
  label with no "Press start on the sender..." or "0 connected" beside it, since a receiver screen just opened shows
  the label for any connection. A connection that doesn't mirror never switches the TV, and a TV moved to another
  input later in a session is left there. Seen on the TV: the user's 14:45 mirror with the TV on HDMI 2
  (<Routing Change> 3.0.0.0 to 2.0.0.0) left the TV there.
- Needs the Chromecast awake while the TV shows another input:
  `cmd hdmi_control cec_setting set power_state_change_on_active_source_lost none` (it was standby_now, standby 30 s
  after the TV left it). With the TV on another input for 30 s and something playing, the pause key goes to it
  (AudioManager.dispatchMediaKeyEvent, which needs no permission), and the normal home goes over it if it still
  plays 2 s later, as that standby paused it.

### Removed
- Mirroring with the TV off no longer switches it on, as asked: with the Chromecast in standby nothing is done. A TV
  turned on straight to another input leaves the Chromecast in standby too, which can't be told from a TV still off,
  so mirroring then doesn't switch either, until the Chromecast's input has been shown once. A receiver screen still
  up when the TV next comes on gives way to the normal home.

### Changed
- A phone leaving closes the receiver's screen for good (started over with FLAG_ACTIVITY_NO_HISTORY, which the home
  going over it finishes) and opens the normal home straight, instead of pressing Home, whose One Touch Play switched
  the TV back to the Chromecast, or switched it on, at the end of a session. The next phone then opens the receiver
  afresh, which sends a window event even under the screensaver: an old screen brought back there sent none, and a
  silent mirror plays no sound for 0.23's sound check to hear (the user's real mirrors started no audio). A receiver
  screen left open otherwise (Back, Home, opened by hand) is closed the same way, out of sight, when the screensaver
  next comes up.
- A phone that connects while the receiver's screen is closing, its window event lost among the closing screen's, is
  found by a look 2.3 s after the session ended, and watched.
- A connection under 10 s that only ended the screensaver, with no picture, puts the screensaver back once the
  receiver's screen has closed (after that look, so a phone connecting meanwhile isn't buried under it).
- A new screensaver can be ended again at once: the 5 s limit on wakes covers only one opening's window events.
- Whether the receiver is in front is told from the front window's root alone on Android 13 and later, so no other
  app's screen is fetched.

## [0.26] - 2026-11-03

### Changed
- With the screen off, any full screen of the stock launcher's own counts as its home. At boot Google TV's launcher
  puts up its dispatcher (coreservices.bootmode.DispatchActivity) while the TV is still off and hands on to its home
  as the TV comes on; the normal home now goes over it then, so the TV comes on to the normal home. Window events do
  reach the service with the screen off (checked: the stock home brought up in standby was covered 0.7 s later).
  The window list can't be read with the screen off, and after a wake it is readable only about 0.6 s later, which
  is what 0.25's looks at screen-on are bound by. Each such takeover is logged too.

## [0.25] - 2026-11-03

### Changed
- The TV coming on shows the stock home for less time. The service starting with the screen off (a boot with the TV
  off) reads the app in front then and covers the stock home before the TV comes on; after the screen comes on, the
  app in front is read at 0, 100, 200, 300, 500 and 1000 ms instead of 0, 300 and 1000. Measured on the Chromecast
  with 0.24: 0.7 s of the stock home after a wake (1.3 s with 0.22, 3.6 s before 0.21).
- HomeTakeover logs each takeover it makes from the app in front (`adb logcat -s HomeTakeover`).

## [0.24] - 2026-11-03

The Prayer app is cut down to the prayer times and the card, as asked: everything to do with sound and every screen
of its own is gone.

### Removed
- All audio: the adhan recordings (and CI's step that fetched them), the Quran radio, the spoken reminders and
  announcements, and their settings.
- The app's own screens: its home screen (the courtyard), Today & Qibla, All apps, Settings, the screen-off radio
  screen and its screensaver. It no longer appears in the app list.
- The reminder banner before each prayer, the remote button mapping (TCL and CVTE keys, app buttons), app redirects,
  its weather, and its Language pill (the normal home's Language pill switches the TV).
- Every permission: no network, no location, no settings writes. The prayer times are worked out on the TV, the card
  is an accessibility overlay, and the AirPlay wake needs none.

### Kept
- The prayer card: the mihrab on its side sliding in from the start edge for a minute when a prayer's time begins,
  silent, in the TV's language. Prayer times for Dammam, Umm al-Qura, standard Asr, fixed in `PrayerDay.java`.
- Home kept on the normal home, and AirPlay: the TV woken for a phone, Home again when it leaves.

### Changed
- Home over the stock launcher opens the normal home straight, not through this app's own Home, about half a second
  sooner; this app's Home (kept for Android's Home role) only passes on to it.
- The look at the app in front when the TV comes on starts as soon as the display reports it is on, before Android's
  screen-on broadcast arrives (about 0.7 s later on the Chromecast).
- The app and its service are named "Prayer" and "Prayer service" on the TV.

## [0.23] - 2026-11-03

### Fixed
- AirPlay under the screensaver still did nothing from the second phone session on: the receiver's screen stays open
  in the background after a session, and bringing it back under the screensaver sends no window event (only a newly
  opened screen does). The receiver starts playing as soon as a phone mirrors, so media sound that starts under the
  screensaver and is still playing a second later now wakes the TV too (AudioPlaybackCallback; Android tells which
  kinds of sound play, not whose). The receiver's sound counts whether it plays as media or, with its low_latency
  setting, as game sound; notification and system sounds don't, and sound already playing when the screensaver
  started (the radio, say) doesn't either.

## [0.22] - 2026-11-03

### Fixed
- AirPlay mirroring did nothing while the screensaver was on: the receiver opened its screen under the screensaver,
  which stays on top of every app, so the phone's picture never showed until the screensaver was left by hand. When
  the receiver's screen comes up under the screensaver, or with the Chromecast in standby, Afnon now wakes the TV
  (WakeActivity, android:turnScreenOn, as an alarm clock does): the screensaver ends, or the TV switches on over
  HDMI-CEC, and the mirrored picture is in front.

## [0.21] - 2026-11-03

### Fixed
- Turning the TV on showed Google's home for a few seconds before the normal home came up. When the Chromecast
  started with the TV off, the boot probe (ProbeActivity) waited for a screen that never drew, and closed only once
  the TV was on, so the takeover came seconds late. The takeover now reads which app is in front as the screen comes
  on (and when the service starts with it on) and opens Afnon over the stock home at once; the probe is left for a
  TV whose window list can't be read.

## [0.20] - 2026-10-30

### Fixed
- A short AirPlay session (under about 6 s) still left the receiver's screen up: full-screen mirroring opens with a
  size label ("1920x1080") for 5 s, which read as "no phone yet". The label, the "Exit fullscreen" button and the
  debug overlay's "Senders: N" now count as a phone, and the first read comes 250 ms after the screen does.
- No watch ran when the receiver was already in front as the service started (after an update or a restart): it
  now looks once at start.
- The reads run on a thread of their own, the whole screen in one request (Android 13+), so the remote's keys never
  wait for them; a picture-in-picture window no longer counts as the app in front.

## [0.19] - 2026-10-30

### Fixed
- The AirPlay watch read a stale copy of the receiver's screen: the service hears no content events, so its node
  cache never learned the screen had changed, and after the phone left it still saw the full-screen picture. Each
  read clears the cache first (AccessibilityService.clearCache, Android 13+; node refresh before).

## [0.18] - 2026-10-30

### Fixed
- Home after an AirPlay disconnect did not come on the TV: the watch read whichever window held focus and ended at
  the first read that was not the receiver's. It now reads the top app window (flagRetrieveInteractiveWindows),
  every second, ends only after five reads in a row with another app in front, and logs what it sees under the
  AirPlayReturn tag.

## [0.17] - 2026-10-30

### Added
- Home again when an AirPlay phone disconnects. The AirPlay receiver (io.github.jqssun.airplay) stays on its
  "Waiting for connection..." screen when the phone goes; while that screen is in front, the remote buttons service
  reads its status line every 1.5 s, and once a phone has been on it ("1 connected", or its picture filling the
  screen), "0 connected" sends the TV Home. Opened by hand with no phone, it is left alone; the receiver keeps
  listening in the background. This adds canRetrieveWindowContent to the service, used for that screen only.

## [0.16] - 2026-10-30

### Changed
- The adhan card comes in from the other side, the left in English and the right in Arabic, across the screen from
  the normal home's pills and the weather now under them, and is a little smaller (470 by 280) so it fits between
  the clock and the dock app's name there.

## [0.15] - 2026-10-30

### Changed
- The adhan card is the mihrab on its side, as the user drew it: its base against the edge it slides in from (the
  right, the left in Arabic) and its point toward the middle, 500 by 320, between the header and the dock and clear
  of the week's weather. The words stay upright.
- Only the adhan card comes up by itself: the reminder before each prayer is off by default, and an install that
  had it on is turned off once. Settings → Reminders turns it back on.

## [0.14] - 2026-10-30

### Changed
- The adhan card is a size smaller (the arch at half size) and sits between the header and the dock instead of
  half way down, so on Home it no longer covers the dock's last tile.

## [0.13] - 2026-10-30

### Fixed
- The adhan card cut the fatha off the top of حَيَّ عَلَى الصَّلَاةِ and drew the arch's border half as thick on its
  sides: the card clipped its own children.
- A card still up when the screen went off came back with the screen; it goes with the screen now.
- Opened from an app list (its tile), Afnon shows its own screen again, for the radio, Today & Qibla and Settings,
  instead of passing straight on to the normal home; only Home starts go there. That screen has no Theme pill.
- Settings → Radio dropped After the adhan: nothing pauses the radio for the adhan any more.
- The radio's dark screensaver no longer wakes the screen at a prayer time.

## [0.12] - 2026-10-30

### Changed
- The prayer theme is gone: with the normal home installed it is always Home, and Afnon hands every Home press to
  it. Afnon's own screen is drawn only when that home is missing, so the TV is never left without one.
- The call to prayer is a card now, not a screen: as each prayer's time begins, the mihrab slides in from the side
  (the right in English, the left in Arabic) over whatever is on, with حَيَّ عَلَى الصَّلَاةِ, the prayer's adhan
  ("Maghrib Adhan", «أذان المغرب») and its time, and slides away after a minute as a gold line under it runs out.
  It is silent and never takes the remote, so the programme carries on. It replaces the full-screen call and its
  adhan recording, so Settings → Reminders loses Close the call screen and the two adhan voice rows; Adhan and the
  per-prayer bells now turn the card on and off. The adb test is `--es test adhan` (`call` still works), and the
  watch's tests now run with the normal home as Home too.
- Digits are Latin (0-9) in Arabic too unless Settings → Display picks Arabic-Indic, and the Language pill gives
  the TV Arabic with Latin digits (the `nu-latn` locale extension), as the normal home's pill does.

### Fixed
- The key hints read in the screen's direction: in Arabic "OK" came last in «مواقيت اليوم والقبلة OK», as the
  line starts with a Latin letter.

## [0.11] - 2026-10-26

### Added
- A Language pill in the header, before Theme: a globe and the language Home is in, opening a card with English
  first and العربية second. Picking one makes it the whole TV's first language and the other its second, as
  Settings → System → Language does, so the normal home and every app follow; Afnon's own language goes back to
  following the TV. This needs `CHANGE_CONFIGURATION` and the `WRITE_SETTINGS` app-op, granted over adb by
  `tools/install.sh`; without them the pill switches Afnon alone. On Home, Up from the arch and from the first
  favourite reaches it.

### Fixed
- `tools/install.sh` stopped at granting `RECORD_AUDIO`, which Afnon no longer asks for since 0.9.

## [0.10] - 2026-10-26

### Added
- The week's weather beside the dates, under the header pills: a column a day with its name, a line symbol (sun,
  sun behind a cloud, cloud, fog, drizzle, rain, snow or a storm), the high and the low, with today's name in gold.
  As many days as fit beside the widest date line, up to seven, so a long Hijri month or city name is never drawn
  over. It comes from the weather line's Open-Meteo call, which now asks for seven days instead of one; a reading
  saved before the update is fetched again at once, so the week shows without waiting half an hour.

## [0.9] - 2026-10-25

### Removed
- The microphone permission (`RECORD_AUDIO`). Android's Visualizer needed it to read the radio's own stream for the
  level bars; Afnon now asks for no microphone access at all, and the bars breathe while the radio plays. The
  release build fails any home APK that asks for the microphone.

## [0.8] - 2026-10-25

### Fixed
- Home on Google TV works from every remote even when Google TV's own home is already on screen, as after Back from
  voice search results or at boot. The TV's own remote, working the Chromecast over HDMI-CEC (a Samsung TV's, say),
  sends keys the remote buttons service never sees, so in that case only the phone remote in the Google Home app
  brought the chosen home back. The service now also follows the notice Android sends for every short Home press,
  from any remote.
- Two quick Home presses, about half a second apart, no longer leave Google TV's home on screen: the second was
  taken for part of the first and dropped.
- Pressing Home again and again no longer leaves Google TV's home on screen with Home ignored for a minute. That
  pause is for Afnon not staying up (four bounces back to the stock home within seconds), and now only the stock
  home coming back without a Home press counts toward it.
- Voice search results stay up, also when you come back to them (Back from a video picked there) more than 15 s
  after speaking, where the chosen home used to replace them. Home still leaves them at once.
- With the normal theme, Home takes the normal home back to its first tile, as its own Home key does, instead of
  leaving focus on the tile you left from: Afnon now hands the press on as a Home press.
- A reminder that comes while the Theme menu is open now closes the menu. With both open, Back closed the menu and
  left the reminder up, and Down moved to Normal in the menu, so the next OK switched homes.
- Home follows a language or digits change made in Settings when you leave Settings with Home rather than Back. It
  kept the old layout with the new language's text, and Left and Right swapped, until something else changed.
- After picking another city, the weather line no longer shows the old city's weather for up to 30 minutes: a
  reading is kept with its city, and the new city's is fetched at once.
- When an adhan recording can't be played (one missing from the APK, say), the call screen gives the sound back to
  what was playing and puts the volume and mute back at once, instead of keeping the video paused in silence until
  it closes (10 minutes by default).

### Security
- The adb test hooks (`test`, `station`, `locale`, `digits`) work only through `tv.afnon.home/.Test`, which needs
  `WRITE_SECURE_SETTINGS`, so only the adb shell can start it. Home is open to every app on the TV, and any of them
  could use the hooks to turn the TV's sleep timer off through the radio, put up the call screen or change the
  language. The `theme` extra from the normal home's Theme menu still goes to Home, but only `prayer` or `normal`
  is taken. The README's test commands use `.Test` instead of `.MainActivity`.

## [0.7] - 2026-10-25

### Added
- Holding OK on an app opens its menu on Home too (the Favourites and More tiles), not only in All apps. After the
  rows that move it in favourites come Uninstall, only for apps you installed (never the TV's own apps or either
  home), which opens Android's own uninstall confirmation, and Details, Android's App info page for the app, where
  its permissions are. An uninstalled app is gone from Home and All apps as soon as you are back, with focus where
  it was, or on the app that took its place. Uninstall needs the new `REQUEST_DELETE_PACKAGES` permission, granted
  at install: without it Android 9 and later close the confirmation at once.

### Changed
- Holding OK in All apps is timed from the press, as Android's own long press on Home's tiles is, rather than
  waiting for the key to repeat: where the remote buttons service filters keys (every TV but TCL and CVTE boards),
  Android re-injects them and they never repeat (`FirmwareKeys.java`).
- The key hint for holding OK reads "App options" instead of "Arrange favourites", and Home's app tiles show it too.
- Home keeps focus where it was when it comes back to apps that came or went, or to favourites changed in All
  apps, instead of moving it to the arch.

### Fixed
- Home showed an app twice, among the favourites and in More, when it stood in for a favourite that is not
  installed (a default one the TV lacks, or one just uninstalled). More now leaves out all four favourites.
- Home and All apps missed one app being swapped for another while they were away (one installed, one uninstalled),
  because they compared only how many apps there were; the uninstalled one stayed as a dead tile. They now compare
  the apps themselves.
- The app menu picks a row only on the release of an OK pressed inside it, so a stray release (an OK that woke the
  screensaver, say) can no longer change a favourite on its own.

## [0.6] - 2026-10-24

### Added
- Home on Google TV with its launcher still enabled. There `cmd package set-home-activity` prints `Success` and
  the Home key keeps opening the stock launcher, and disabling that launcher is unsafe while the setup wizard is
  enabled, which casting needs (the Cast receiver loads its credentials from it). The remote buttons service now
  puts Afnon over the stock home screen when it comes up, at boot too (an invisible probe screen, once per boot),
  and on a short Home press while it is already showing. Only while Afnon holds the Home role and Home still
  reaches another launcher, never over the setup wizard, and not over voice search results. If Afnon does not stay
  up (four bounces back to the stock home within seconds), it leaves the stock home alone for a minute. Made for a
  Chromecast with Google TV 4K, Google TV 14, UTTC.250917.004.
- Theme pill: Home is either this prayer home or the cleanyourtv home, chosen from a menu on either one and
  remembered here, since Afnon is the launcher the Home key reaches.
- Weather under the date (temperature, sky and humidity for the chosen city), from Open-Meteo.

## [0.5] - 2026-10-22

### Added
- Settings → Reminders → Close the call screen: when the adhan ends, or 1, 5 or 10 minutes after it appears (10,
  as before, by default). Then it's back to what was playing. With no adhan playing (turned off, or stopped with
  the button), "when the adhan ends" closes it after a minute. The call screen's hint says which.
- Spoken reminder: «اقترب موعد صلاة …» with the reminder card or banner (Settings → Reminders, on by default).
- Announce the time: «حان الآن موعد صلاة …» when a prayer's time begins with its adhan off, either for all prayers
  or by that prayer's bell (on by default). Friday says الجمعة. Both lower whatever is playing for a moment
  instead of pausing it, and each is said once per prayer, also across a restart of the remote buttons service.
  The 12 clips are not in the repo: `tools/fetch-announce.sh` (run by `install.sh` the first time) records
  them with a Microsoft neural Arabic voice through edge-tts into `app/src/main/assets/announce/`; without them
  both stay silent. Release APKs include them. `--es test announce` tries it; `--es test banner` and
  `--es test reminder` now say the reminder too.

### Changed
- The reminder can come 5, 10, 15, 20 or 30 minutes before each prayer (or not at all). Settings → Reminders →
  Remind me before prayer is now a list, like the adhan voices; 15 is still the default.
- The Hijri date comes first: on Home it is the line under the clock (with the city) and the Gregorian date is
  the smaller line below it; on Prayer day the date line starts with it.
- The verse reference under the arch («النساء 103») follows the digits setting like every other number; it was
  always Eastern Arabic (١٠٣), even with Western digits chosen. It is the only surah:ayah reference on screen:
  Friday, Ramadan, the call screen and the screensaver show none.

## [0.4] - 2026-10-22

### Added
- 59 more cities, now 136 in 43 countries: India, Pakistan, Bangladesh, Sri Lanka, Maldives, Indonesia, Malaysia,
  Singapore, Brunei, Thailand, Japan, South Korea, Turkey, the United Kingdom, France, Germany, the Netherlands,
  Belgium, Austria, Switzerland, Italy, Spain, Bosnia and Herzegovina, Canada, Kenya, Nigeria, South Africa,
  Australia and New Zealand, plus Houston and Los Angeles. Each starts on its mosques' calculation method. Thanks,
  @mohammed-althaf-h (#4).

### Changed
- Location is picked by region first (Arab world, Asia, Europe, Americas, Africa, Oceania), then country (A to Z),
  then city, so no list is longer than 16.
- Picking a city also sets its usual Asr school: Hanafi for Pakistan, Bangladesh and most Indian cities (Kerala and
  Chennai stay Shafi'i). The Asr row can still change it.

### Fixed
- In Settings, list chooser items no longer draw over the chooser's title when scrolled (#8, thanks
  @mohammed-althaf-h in #9).

## [0.3.1] - 2026-10-22

### Changed
- Settings keys are the same on every row: the arrows only move (toward the start side goes back to the
  sections), and OK is the only key that changes anything. OK on a choice or the Hijri offset starts editing it
  (a ring shows round the control): ◀▶ move the choice, OK saves, Back cancels. OK flips a switch and opens a
  list as before. Every save shows "✓ Saved" at the end of the hint line for 2 s. Reported in #5 (thanks,
  @mohammed-althaf-h).

## [0.3] - 2026-10-22

### Added
- United States in the location list, with New York, Chicago and Dallas (ISNA method).
  The Hijri date and Ramadan mode still use Umm al-Qura, so US users who follow local moon sighting set the Hijri
  offset.
- A web preview of the launcher in `site/` (Astro, deployed to GitHub Pages by `.github/workflows/site.yml`): every
  screen in Arabic and English, driven by the arrow keys like the remote, with live prayer times for the visitor's
  chosen city. It reads the app's own strings (`Text.java`), cities, methods, stations and fonts at build time, so
  it never keeps a second copy. One page per screen and mode, with titles, descriptions, `hreflang`, a sitemap and
  structured data for search engines.
- `CODE_OF_CONDUCT.md`, `SECURITY.md`, issue templates (bug, prayer times or city, TV report, idea) and a pull
  request template. `THIRD_PARTY.md` now lists every radio station and the reciter channels.

### Changed
- `afnon-mihrab/tokens.json` is the single source of the design tokens. `Tokens.java` and `colors.xml` are now
  generated from it (`npm run tokens` in `site/`, Style Dictionary), as is the site's CSS; CI fails if they drift.
  The 15 colours that were hard-coded in the app (screensaver, menus, the reminder card, Qibla rings, the banner
  track, the Settings sidebar) are tokens now. Every value is unchanged.

## [0.2.1] - 2026-10-22

### Fixed
- A prayer's reminder and the call to prayer no longer come back after you close them. Each check computed the
  prayer's time with a few different milliseconds, so it never matched the one already shown; the reminder
  returned within 15 s and the call screen during its first two minutes. What was shown is also saved, so a
  restart of the remote buttons service (low memory, TCL's background manager) doesn't show it again.

## [0.2] - 2026-10-21

### Added
- Radio stations: Settings → Radio → Station picks broadcast Quran radios (Cairo, Nablus, Shabab FM Quran, Qatar 1
  and 2, Jordan) or one of 10 reciters' 24/7 channels from mp3quran.net. Each URL is the station's own stream;
  the same single player plays them. Home's card, the screensaver and the screen-off view show the station.
  `--es station <id>` picks one from adb.
- 57 cities, for 74 in 13 countries. Coordinates and Arabic names from Wikipedia/Wikidata.
  - Palestine, the seat of every governorate: Jerusalem, Ramallah, Nablus, Hebron, Bethlehem, Jenin, Tulkarm,
    Qalqilya, Salfit, Tubas, Jericho, Gaza, Jabalia, Deir al-Balah, Khan Yunis and Rafah.
  - Lebanon (Beirut, Tripoli, Sidon, Tyre, Baalbek), Syria (Damascus, Aleppo, Homs, Hama, Latakia, Deir ez-Zor),
    Iraq (Baghdad, Basra, Mosul, Erbil, Najaf, Karbala, Kirkuk, Sulaymaniyah), Bahrain (Manama, Muharraq),
    Oman (Muscat, Salalah, Sohar, Nizwa) and Yemen (Sanaa, Aden, Taiz, Hodeidah, Mukalla).
  - More cities where there was one: Saudi Arabia (Jeddah, Dammam, Taif, Tabuk, Abha) with Umm al-Qura,
    UAE (Abu Dhabi, Sharjah, Al Ain) with the Dubai method, Jordan (Irbid, Zarqa, Aqaba).
  - The new countries use the Muslim World League method, like Amman; the method row can still change it.
- A `redirects` preference (`from=to` package pairs) that opens another app whenever one app's screen comes up,
  for remote buttons the firmware hard-wires to an app that can't be replaced (`Redirects.java`). Empty by default.
- A `key_filter` preference (`auto`, `on`, `off`) to force key filtering on or off on a TV the guess gets wrong.

### Changed
- Location picks a country first, then one of its cities, so the remote never scrolls through all 74. A country
  with a single city is picked at once.

### Fixed
- On TVs whose firmware answers the remote's buttons itself (CVTE boards, e.g. Super General), holding Volume
  works continuously and the Source button opens the TV's own source menu again. The remote buttons service no
  longer filters keys there (`FirmwareKeys.java`, detected by CVTE's `android.intent.action.HOTKEYSERVICE`).
  CVTE's firmware leaves Source to the app in front, so Home now opens the TV's source menu itself.
- `tools/install.sh` grants location, so Home shows the Wi-Fi name on a fresh install (Android 9+ hides it otherwise).
- Where the service still filters keys, a button whose target is missing (an app that isn't installed, or no
  Android TV inputs screen) now passes through to the TV instead of doing nothing.
- Settings no longer stops answering OK after you leave it with a list open (city, method, adhan) and change
  something elsewhere, such as the room direction: coming back rebuilt the screen without the list but kept it
  marked as open.
- In the settings lists (cities, methods, adhan voices), items scrolled up no longer draw over the list's title.
- Settings → Room direction stays open. Settings acts on OK's press and the picker on its release, so the same
  press closed the picker at once and saved 0°.
- Screen off while listening uses TCL's Audio Only on Android TV 11 TCL sets too (tested on a C06_2K_GB set),
  where TCL Settings hosts it in `com.tcl.settings` instead of `com.android.tv.settings`.

## [0.1.1] - 2026-10-21

### Fixed
- Holding Volume Up or Down on the remote changes the volume continuously again, instead of one step per hold.
  The remote buttons service no longer filters keys on TCL TVs, which had stopped held keys from repeating.

### Changed
- On TCL TVs, the remote's app, Settings, Input and All apps buttons are now handed to TCL's own button
  remapping (`TclKeys.java`). Other TVs keep the previous behaviour.
- `tools/install.sh` and the other tools find adb by themselves (PATH, the Android SDK in `local.properties`,
  `ANDROID_HOME`, or the default SDK folder). `ADB_BIN` still overrides.
- `tools/restore.sh` also removes the TCL button mappings.

## [0.1] - 2026-10-21

First public release.

### Added
- Home with the next prayer and a live countdown in a mihrab arch, the day's six times, favourites and more apps.
- Today's prayers with an adhan on/off bell per prayer, the Qibla dial and room direction.
- Reminders before each prayer, and the call screen with the adhan at prayer time.
- 14 adhan voices plus a separate Fajr adhan.
- Friday (Jumu'ah, Surat al-Kahf) and Ramadan (iftar and suhoor) modes.
- Quran Radio Cairo 98.2 FM with live level bars; pauses for the adhan; screen off while listening (TCL Audio Only).
- Screensaver, on-TV settings and remote button mapping.
- Arabic (RTL) first, with English.

[Unreleased]: https://github.com/egyjs/afnon-android-tv-launcher/compare/v0.5...HEAD
[0.5]: https://github.com/egyjs/afnon-android-tv-launcher/compare/v0.4...v0.5
[0.4]: https://github.com/egyjs/afnon-android-tv-launcher/compare/v0.3.1...v0.4
[0.3.1]: https://github.com/egyjs/afnon-android-tv-launcher/compare/v0.3...v0.3.1
[0.3]: https://github.com/egyjs/afnon-android-tv-launcher/compare/v0.2.1...v0.3
[0.2.1]: https://github.com/egyjs/afnon-android-tv-launcher/compare/v0.2...v0.2.1
[0.2]: https://github.com/egyjs/afnon-android-tv-launcher/compare/v0.1.1...v0.2
[0.1.1]: https://github.com/egyjs/afnon-android-tv-launcher/compare/v0.1...v0.1.1
[0.1]: https://github.com/egyjs/afnon-android-tv-launcher/releases/tag/v0.1
