# Changelog

All notable changes to Afnon. Versions match `versionName` in `app/build.gradle` and the `vX.Y` git tags.

## [Unreleased]

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
