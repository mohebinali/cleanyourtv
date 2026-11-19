package tv.afnon.home;

import android.Manifest;
import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The TV turning off and on, made quick to come back. Turned off, the Samsung sends HDMI-CEC <Standby> and the
 * Chromecast goes to standby; turned on with its own remote, the Samsung woke it only at the end of its own handshake,
 * 13 s after its HDMI came up (2026-11-03 18:28:08 to 18:28:21), so the home took that long to show. So the Chromecast
 * is kept awake while the TV is off, ready for the TV coming back:
 * - The TV going off: its <Standby> makes HDMI-CEC say "inactive" before the Chromecast's screen goes off (the
 *   Chromecast's own sleep, a remote's power button, does so only after, and is left asleep). No phone is to see the
 *   Chromecast while the TV is off (YouTube's Cast button, AirPlay), so the AirPlay receiver leaves Wi-Fi, with the
 *   goodbye iPhones need to drop it at once, and the network is closed to apps (hide). Wi-Fi itself stays on: turned
 *   off (0.29 to 0.34) it took wireless debugging with it and 6 to 20 s to come back, in which nothing could cast.
 *   10 s on the normal home is put in front and the Chromecast woken back up (WakeActivity). That sends no One Touch
 *   Play, so the TV stays off, with the Chromecast's HDMI-CEC power_control_mode = none (README). Were the TV to come
 *   on all the same, the CEC state turns "active", and the Chromecast is not kept awake again until the service
 *   restarts. A TV turned back on within the 10 s wakes the Chromecast itself, as it always did.
 * - The TV coming on, told from HDMI-CEC's history (CecBus, each second while the TV is off): the TV saying its power
 *   is on, which Android asks it as the Samsung drops its HDMI hot-plug for a second in powering up (17:55:30.9 to
 *   32.1; it drops it in standby too, just after this app's wake, 2026-11-05 15:47:06, and then says "standby"), or
 *   the TV asking after its HDMI devices, which it does a second or two into powering up. The network opens again,
 *   AirPlay comes back, the screensaver, if up, is ended, so the home is what the TV shows, and where the TV said it
 *   is on and was showing the Chromecast as it went off, Home makes the Chromecast HDMI-CEC's active source then and
 *   there, for the Samsung's remote (which otherwise reaches nothing until the Samsung selects the Chromecast at the
 *   end of its own check, 12 s on, 15:59:25 to 15:59:37). Without that history (no DUMP): the hot-plug coming back
 *   with no "standby" after it, a remote's buttons moving the focus, the screensaver ended by hand, the CEC state
 *   turning "active", or a wake by anything else. A TV that wakes the Chromecast itself (on again within the 10 s)
 *   doesn't make it HDMI-CEC's active source: Home is pressed, which sends One Touch Play.
 * The history also shows the Chromecast's broadcasts being refused, by some device on the TV's HDMI; it is noted
 * (Events), for the TV's remote then reaches nothing.
 */
final class TvPower {
    private static final String TAG = "TvPower";
    /**
     * HDMI-CEC's active source as the vendor's service (com.droidlogic, HdmiCecActiveness) reports it for Netflix:
     * {"activeCecState":"active"} while the TV shows the Chromecast, "inactive" while it shows another input or is off.
     */
    private static final String CEC_STATE = "nrdp_video_platform_capabilities";
    /**
     * Android's restricted networking mode (Settings.Global): on, only apps holding
     * CONNECTIVITY_USE_RESTRICTED_NETWORKS reach the network, which the system and adb do and the Cast receiver
     * doesn't. That hides the Chromecast from phones as Wi-Fi off did, and lifts in an instant.
     */
    private static final String GATE = "restricted_networking_mode";
    /**
     * HDMI-CEC's history is read this often while the TV is off, this often for a few seconds after its HDMI hot-plug
     * came back (the TV's word on its power is then on its way), and this often otherwise (for refused broadcasts).
     */
    private static final long CEC_POLL_MS = 1_000, CEC_FAST_MS = 300, CEC_FAST_FOR_MS = 4_000, CEC_WATCH_MS = 60_000;
    /** A read asked for by something on screen changing (a Home press sends broadcasts) waits this long, this often. */
    private static final long CEC_SOON_MS = 3_000, CEC_SOON_EVERY_MS = 15_000;
    /**
     * The TV asking after its HDMI devices this soon after it went off is someone turning it straight back on: Home
     * is pressed for its remote without waiting for its word on its power, which it then gives only late (asked back
     * on 20 s after going off, it dropped no hot-plug, and its remote worked 9 s on, 2026-11-19 23:05:51 to 23:06:00).
     * Later than this, a TV asking might be one looking after itself with its screen off, which Home would turn on.
     */
    private static final long BACK_SOON_MS = 2 * 60_000;
    /** So many things asked by the TV since it went off are its check of its HDMI devices: it is powering up. */
    private static final int TV_ASKS_UP = 2;
    /**
     * After the screen goes off for the TV, the Chromecast is woken this much later. The wake runs Android's whole
     * HDMI-CEC start-up (its address claimed, then broadcasts of it, its vendor and features); 1.5 s after the TV's
     * <Standby> (0.29, 0.30) that fell in the Samsung's own change-over to standby, and two days on the Samsung was
     * found refusing every broadcast, so passing no remote keys, until its mains plug was pulled (2026-11-05). Not
     * proven the cause, but by 10 s the Samsung has settled (it still broadcast 3 s in, 2026-11-03). Turned back on
     * sooner, the TV wakes the Chromecast itself, 8 s into its own start-up (16:52:02 to 16:52:10), so not longer.
     */
    private static final long REWAKE_MS = 10_000;
    /** The screen going off again this soon after this app woke the Chromecast, the TV still off: not woken again. */
    private static final long REWAKE_AGAIN_MS = 10 * 60_000;
    /**
     * The TV's <Standby> makes the CEC state "inactive" about 0.5 s before the screen goes off; the Chromecast's own
     * sleep, about 0.35 s after it (2026-11-03 17:22:07, 17:55:11). A state turned this recently is the latter's.
     */
    private static final long OWN_SLEEP_MS = 250;
    /**
     * Screen-on and HDMI changes this soon after this app woke the Chromecast are that wake's own: woken 30 s into
     * standby, the Samsung dropped its HDMI hot-plug 0.9 s later for 1.5 s and stayed off (2026-11-05 15:47:06).
     */
    private static final long OWN_WAKE_MS = 6_000;
    /**
     * Focus moves are a remote's buttons only after the home this app opened has settled, not just after a window came
     * up (a window gaining focus moves it too), and two of them in this time.
     */
    private static final long FOCUS_SETTLE_MS = 10_000, FOCUS_AFTER_WINDOW_MS = 1_000, FOCUS_PAIR_MS = 5_000;
    /** The CEC state turning "active" this soon after this app woke the Chromecast: the wake switched the TV on. */
    private static final long TV_ON_CHECK_MS = 3_000;
    /** The screensaver ending for the device going to sleep is told from one ended by hand this much later. */
    private static final long DREAM_END_MS = 700;
    /** The test hook's network closed to apps, and open again this much later. */
    private static final long TEST_GATE_MS = 25_000;
    /**
     * The HDMI hot-plug back with the TV off, the TV's answer to the Chromecast's <Give Device Power Status> is in by
     * this much later: "standby" has the vendor's service write the CEC state ("inactive") again, 0.2 to 0.8 s after it
     * (2026-11-05 15:47:07, 15:47:09, 15:59:03, 15:59:04); "on" writes nothing (15:59:26, 15:59:28, 16:05:44).
     */
    private static final long POWER_REPLY_MS = 3_000;
    /** The TV left the Chromecast this soon before the screen went off: it was showing it as it went off. */
    private static final long LEFT_AT_OFF_MS = 3_000;
    /** The AirPlay receiver's settings, taken from apps holding DUMP (a key and a value; ordered, it answers). */
    private static final ComponentName AIRPLAY_SETTINGS =
        new ComponentName(AirPlayReturn.AIRPLAY, AirPlayReturn.AIRPLAY + ".SettingsReceiver");
    private static final String AIRPLAY_SET = AirPlayReturn.AIRPLAY + ".SET_SETTING";
    /** Whether the Chromecast is hidden from phones while the TV is off: the owner's word (adb), yes unless told. */
    static final String PREF_HIDE = "tv_hide";
    /** The network closed to apps here, so it is this app's to open again. */
    private static final String PREF_GATE = "tv_gate_closed_here";
    /** The TV off, kept for a restarted service or a reboot, where the Chromecast comes up awake with no screen-off. */
    private static final String PREF_TV_OFF = "tv_off";
    /** With it, whether the TV was showing the Chromecast as it went off; and AirPlay taken off Wi-Fi here. */
    private static final String PREF_TV_OFF_HERE = "tv_off_here", PREF_AIRPLAY_OFF = "tv_airplay_off_here";

    private final AccessibilityService service;
    /** The second thread reads HDMI-CEC's history, so a read that hangs holds up nothing else. */
    private final HandlerThread thread = new HandlerThread("tv-power"), cecThread = new HandlerThread("tv-power-cec");
    private final Handler handler, cecReader;
    private final AlarmManager alarms;
    private final AlarmManager.OnAlarmListener rewake = this::rewake;
    private final Runnable dreamEnded = this::dreamEnded;
    private final Runnable poweredUp = this::poweredUp;
    private final Runnable cecPoll = this::cecPoll;
    /** What the AirPlay receiver answered to a setting ("usb_only=true", or what was wrong). */
    private final BroadcastReceiver airplaySet = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String answer = getResultData();
            Log.i(TAG, "AirPlay receiver: " + (answer != null ? answer : "no answer"));
        }
    };
    /** Told when the TV comes back on (AirPlayReturn looks for a receiver screen opened meanwhile). */
    volatile Runnable onTvOn;
    /** Told, at each reading, while HDMI-CEC broadcasts are being refused (JamNotice says what to do). */
    volatile Runnable onRefused;
    /** Covers the stock home's flash as Home is pressed; without one, Home is pressed in the open. */
    volatile HomeCover cover;
    // Everything below is touched on the thread only, but for ready, read by AirPlayReturn.
    /** The TV is off, as far as is known, and the Chromecast kept awake for it, hidden from phones. */
    private volatile boolean ready;
    /** Waking the Chromecast switched the TV on: not kept awake again while this service runs. */
    private boolean disabled;
    /** The test hook's dry run: hiding from phones is logged, not done. */
    private boolean dry;
    private boolean dreaming;
    /** rewake's alarm is set (cancelling one that isn't makes Android log a warning). */
    private boolean rewakeSet;
    /** HDMI-CEC from the TV counts for its coming on from this wall-clock time: the second after it went off. */
    private long offWall;
    /** When the TV went off, when its hot-plug last came back while it was, and when the history was last read. */
    private long offAt, replugAt = Long.MIN_VALUE / 2, readAt = Long.MIN_VALUE / 2;
    /** Counts the TV's goings off, so a reading of the history asked for one isn't taken for the next. */
    private int offCount;
    /** The Chromecast's HDMI-CEC broadcasts are being refused, as last read; and whether the history could be read. */
    private boolean refused, history;
    /** The CEC state was "active" (the TV showing the Chromecast) when last seen. */
    private boolean shown;
    /** The TV was showing the Chromecast as it went off, so it comes back to it. */
    private boolean backHere;
    /** When the CEC state last went from "active" to "inactive": the TV left the Chromecast (another input, or off). */
    private long leftAt = Long.MIN_VALUE / 2;
    private long rewokeAt = -OWN_WAKE_MS, hdmiGoneAt = -1, lastFocusAt = -FOCUS_PAIR_MS;
    /** When a window last came up (its focus moves are not a remote's). */
    private long windowAt = Long.MIN_VALUE / 2;
    /** When the CEC state last turned "inactive" (long ago where not seen). */
    private long inactiveAt = Long.MIN_VALUE / 2;
    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                screenOff();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOn();
            } else if (Intent.ACTION_DREAMING_STARTED.equals(action)) {
                dreaming = true;
            } else if (Intent.ACTION_DREAMING_STOPPED.equals(action)) {
                dreaming = false;
                handler.removeCallbacks(dreamEnded);
                handler.postDelayed(dreamEnded, DREAM_END_MS);
            } else if (AudioManager.ACTION_HDMI_AUDIO_PLUG.equals(action) && !isInitialStickyBroadcast()) {
                hdmi(intent.getIntExtra(AudioManager.EXTRA_AUDIO_PLUG_STATE, -1) == 1);
            }
        }
    };
    private final ContentObserver cecObserver;

    TvPower(AccessibilityService service) {
        this.service = service;
        thread.start();
        handler = new Handler(thread.getLooper());
        cecThread.start();
        cecReader = new Handler(cecThread.getLooper());
        alarms = service.getSystemService(AlarmManager.class);
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_DREAMING_STARTED);
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);
        filter.addAction(AudioManager.ACTION_HDMI_AUDIO_PLUG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_NOT_EXPORTED);
        } else {
            service.registerReceiver(receiver, filter, null, handler);
        }
        cecObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                boolean shows = tvShowsUs(service);
                if (!shows) {
                    inactiveAt = SystemClock.elapsedRealtime();
                    if (shown) leftAt = inactiveAt;
                } else if (ready) {
                    if (SystemClock.elapsedRealtime() - rewokeAt < TV_ON_CHECK_MS) {
                        // This app's wake sent One Touch Play (power_control_mode isn't none) and the TV came on.
                        disabled = true;
                        Log.w(TAG, "waking the Chromecast switched the TV on: not kept awake again");
                    }
                    tvOn("the TV shows the Chromecast");
                }
                shown = shows;
            }
        };
        service.getContentResolver().registerContentObserver(Settings.Global.getUriFor(CEC_STATE), false,
            cecObserver);
        handler.post(() -> {
            dreaming = dreamUp(service);
            shown = tvShowsUs(service);
            // The TV was off when the last run ended (an update, a restart, a reboot) and still seems so: awake, as
            // the Chromecast comes up, with no screen-off to go by, it stays hidden and AirPlay leaves the TV off.
            // Otherwise what was hidden here comes back, the Chromecast reachable rather than lost.
            if (prefs().getBoolean(PREF_TV_OFF, false) && interactive() && !tvShowsUs(service)) {
                Events.log(service, TAG, "service started with the TV off: kept hidden");
                ready = true;
                backHere = prefs().getBoolean(PREF_TV_OFF_HERE, false);
                rewokeAt = SystemClock.elapsedRealtime();
                tvWentOff();
                hide();
            } else {
                setReady(false);
                gate(false, true);
                show();
            }
            cecPoll();
        });
    }

    /** The TV is off, as far as is known: no phone's picture is to bring it on (AirPlayReturn). */
    boolean tvOff() {
        return ready;
    }

    /**
     * Whether the TV shows the Chromecast, from HDMI-CEC as the vendor's service reports it (CEC_STATE). True where
     * that isn't known, so nothing is switched on a guess. "inactive" can be out of date: a TV that wakes the
     * Chromecast itself (turned on with its own remote, it sends the power key) shows it without making it the active
     * source, so the state stays "inactive" until the next Home press.
     */
    static boolean tvShowsUs(Context context) {
        try {
            String caps = Settings.Global.getString(context.getContentResolver(), CEC_STATE);
            return caps == null || !"inactive".equals(new JSONObject(caps).optString("activeCecState"));
        } catch (JSONException | RuntimeException e) {
            return true;
        }
    }

    /**
     * A window came up (its focus moving with it is not a remote's buttons). A Home press brings one up and sends
     * broadcasts, so the history is read soon, for their being refused.
     */
    void onWindow() {
        handler.post(() -> {
            windowAt = SystemClock.elapsedRealtime();
            if (!ready && windowAt - readAt > CEC_SOON_EVERY_MS) {
                readAt = windowAt;
                handler.removeCallbacks(cecPoll);
                handler.postDelayed(cecPoll, CEC_SOON_MS);
            }
        });
    }

    /** A view took focus: a remote's buttons in use, so the TV is on. Under the screensaver, dreamEnded tells. */
    void onFocus() {
        handler.post(() -> {
            if (!ready || dreaming) return;
            long now = SystemClock.elapsedRealtime();
            if (now - rewokeAt < FOCUS_SETTLE_MS || now - windowAt < FOCUS_AFTER_WINDOW_MS) return;
            if (now - lastFocusAt < FOCUS_PAIR_MS) {
                tvOn("a remote's buttons");
            } else {
                lastFocusAt = now;
            }
        });
    }

    /**
     * The test hook: the network closed to apps now and open again 25 s later, by this app, so nothing is left shut
     * were adb to go with it (it shouldn't: adb is let through).
     */
    void testGate() {
        handler.post(() -> {
            Log.i(TAG, "test: network closed to apps for " + TEST_GATE_MS / 1000 + " s");
            gate(true, false);
            handler.postDelayed(() -> {
                Log.i(TAG, "test: network open again");
                gate(false, false);
            }, TEST_GATE_MS);
        });
    }

    /** The test hook: Home pressed under cover, as when the TV comes on (a few seconds on, time to look). */
    void testCover() {
        handler.postDelayed(this::home, 3_000);
    }

    /** The test hook's dry run: hiding from phones is only logged until the service restarts. */
    void testDry() {
        handler.post(() -> {
            dry = true;
            Log.i(TAG, "test: dry run, nothing hidden");
        });
    }

    /**
     * The service is going. Where it is stopping for good (turned off), what was hidden here comes back, so the
     * Chromecast isn't left off the network; where it is starting again, the new run decides.
     */
    void quit(boolean restore) {
        service.getContentResolver().unregisterContentObserver(cecObserver);
        try {
            service.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered.
        }
        if (alarms != null) alarms.cancel(rewake);
        handler.removeCallbacksAndMessages(null);
        cecReader.removeCallbacksAndMessages(null);
        if (restore) {
            handler.post(() -> {
                setReady(false);
                show();
            });
        }
        thread.quitSafely();
        cecThread.quitSafely();
    }

    /**
     * The screen went off. For the TV turning off, HDMI-CEC said "inactive" before it did (it already does with the TV
     * on another input); for the Chromecast's own sleep, a remote's power button, only after (OWN_SLEEP_MS), and that
     * is left as it is, reachable for the phone that may wake it. For the TV, the Chromecast is hidden from phones
     * and woken again 10 s later, once; where that switched the TV on earlier, it stays asleep, hidden until it wakes.
     */
    private void screenOff() {
        cancelRewake();
        long now = SystemClock.elapsedRealtime();
        if (tvShowsUs(service) || now - inactiveAt < OWN_SLEEP_MS) {
            Log.i(TAG, "Chromecast put to sleep with the TV on: left as it is");
            return;
        }
        hide();
        if (disabled) {
            Events.log(service, TAG, "TV off: hidden until the Chromecast wakes");
            return;
        }
        if (ready && now - rewokeAt < REWAKE_AGAIN_MS) {
            Events.log(service, TAG, "TV off again just after the Chromecast was woken for it: left asleep, hidden");
            return;
        }
        backHere = now - leftAt < LEFT_AT_OFF_MS;
        prefs().edit().putBoolean(PREF_TV_OFF_HERE, backHere).apply();
        tvWentOff();
        setReady(true);
        Events.log(service, TAG, "TV off" + (backHere ? ", from the Chromecast" : "") + ": hidden from phones, the "
            + "Chromecast woken again in " + REWAKE_MS / 1000 + " s to be ready");
        if (alarms != null) {
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, now + REWAKE_MS, "afnon:rewake", rewake, handler);
            rewakeSet = true;
        }
    }

    private void cancelRewake() {
        if (rewakeSet) alarms.cancel(rewake);
        rewakeSet = false;
    }

    /** The TV went off now: HDMI-CEC from it counts for its coming back from the next second on. */
    private void tvWentOff() {
        offWall = (System.currentTimeMillis() / 1000 + 1) * 1000;
        offAt = SystemClock.elapsedRealtime();
        offCount++;
    }

    /**
     * Hidden from phones for the TV off, unless the owner wants it seen (PREF_HIDE): the AirPlay receiver leaves
     * Wi-Fi, saying goodbye, and the network is closed to apps, which the Cast receiver has no other way of leaving.
     */
    private void hide() {
        if (!prefs().getBoolean(PREF_HIDE, true)) return;
        airplay(false);
        gate(true, false);
    }

    /** Back for phones to see: whatever was hidden here. */
    private void show() {
        gate(false, false);
        airplay(true);
    }

    /**
     * The network closed to apps (GATE) or open again: open again where this app closed it, and whatever this app
     * knows of it as the service starts with the TV on, for nothing else here closes it, and left closed nothing on
     * the TV reaches the network. Android lets an app write this setting (with WRITE_SECURE_SETTINGS, granted over
     * adb, README) but not read it (0.35 did, and the service fell over), so what this app did is all it goes by.
     * Without the grant the Cast receiver stays seen.
     */
    private void gate(boolean closed, boolean whatever) {
        SharedPreferences prefs = prefs();
        if (closed == prefs.getBoolean(PREF_GATE, false) && !whatever) return;
        if (dry) {
            Log.i(TAG, "dry run: the network would be " + (closed ? "closed to apps" : "open again"));
            return;
        }
        try {
            // Written first and to disk: a run that dies in between still opens it again.
            if (closed) prefs.edit().putBoolean(PREF_GATE, true).commit();
            Settings.Global.putInt(service.getContentResolver(), GATE, closed ? 1 : 0);
            if (!closed) prefs.edit().putBoolean(PREF_GATE, false).apply();
        } catch (RuntimeException e) {
            prefs.edit().putBoolean(PREF_GATE, false).apply();
            Log.w(TAG, "the network is left open to apps: the setting can't be written (README)", e);
        }
    }

    /**
     * The AirPlay receiver on Wi-Fi or off it, by its own usb_only setting. Changing that restarts its server, which
     * unregisters it from mDNS with a goodbye, so iPhones drop it at once; merely cut off it has none to send, and
     * Screen Mirroring kept showing it (2026-11-05). Put back only where this app took it off.
     */
    private void airplay(boolean on) {
        SharedPreferences prefs = prefs();
        if (on != prefs.getBoolean(PREF_AIRPLAY_OFF, false)) return;
        try {
            service.getPackageManager().getReceiverInfo(AIRPLAY_SETTINGS, 0);
        } catch (PackageManager.NameNotFoundException e) {
            prefs.edit().putBoolean(PREF_AIRPLAY_OFF, false).apply();
            return;
        }
        if (dry) {
            Log.i(TAG, "dry run: AirPlay would go " + (on ? "back on Wi-Fi" : "off Wi-Fi"));
            return;
        }
        if (!on && service.checkSelfPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "AirPlay left on Wi-Fi: the receiver takes settings only from apps holding DUMP (README)");
            return;
        }
        // Written first and to disk, as for the network: a run that dies in between still puts it back.
        prefs.edit().putBoolean(PREF_AIRPLAY_OFF, !on).commit();
        service.sendOrderedBroadcast(new Intent(AIRPLAY_SET).setComponent(AIRPLAY_SETTINGS)
            .putExtra("key", "usb_only").putExtra("value", on ? "false" : "true"), null, airplaySet, handler, 0,
            null, null);
    }

    /**
     * rewake, from an alarm (a Handler's delay stops while the processor is suspended, as it may be 10 s into
     * standby): the normal home in front, so an app left playing stays stopped, then the Chromecast woken. Android
     * keeps the processor awake while an alarm runs, so this waits (3 s at most) for the screen to come on.
     */
    private void rewake() {
        rewakeSet = false;
        if (!ready || interactive()) return;
        try {
            service.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(HomeTakeover.NORMAL_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not open the normal home", e);
        }
        rewokeAt = SystemClock.elapsedRealtime();
        hdmiGoneAt = -1;
        wake();
        for (int i = 0; i < 30 && !interactive(); i++) SystemClock.sleep(100);
        cecPoll();
    }

    /**
     * cecPoll: HDMI-CEC's history read (CecBus), on its own thread, and looked at back on this one (cecRead). Each
     * second while the TV is off and the Chromecast awake for it, each minute otherwise.
     */
    private void cecPoll() {
        handler.removeCallbacks(cecPoll);
        if (!interactive()) {
            handler.postDelayed(cecPoll, CEC_WATCH_MS);
            return;
        }
        readAt = SystemClock.elapsedRealtime();
        final boolean off = ready;
        final int count = offCount;
        final long since = off ? offWall : Long.MAX_VALUE;
        cecReader.post(() -> {
            CecBus.Reading reading = CecBus.read(service, since);
            handler.post(() -> cecRead(reading, off, count));
        });
    }

    /**
     * cecRead: what the history says. The TV saying its power is on is as good as seeing it: the home is its to show,
     * and where it was showing the Chromecast as it went off, Home makes the Chromecast its active source at once, so
     * its remote works from the start (2026-11-19: on at 23:12:40.9, its word at 23:12:43, Home at 23:12:44.4 and its
     * remote's buttons arriving that second). The TV asking after its HDMI devices is its powering up too, but a TV
     * might ask with its screen still off, and Home would turn that on: Home then only where it went off just now
     * (BACK_SOON_MS), and otherwise the Samsung selecting the Chromecast itself at the end of its check.
     */
    private void cecRead(CecBus.Reading reading, boolean off, int count) {
        history = reading != null;
        if (reading != null) {
            if (reading.refused != refused) {
                refused = reading.refused;
                Events.log(service, TAG, refused
                    ? "HDMI-CEC broadcasts are being refused (since "
                        + new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(reading.refusedSince))
                        + "): some device on the TV's HDMI says no to them, and the TV's remote reaches nothing"
                    : "HDMI-CEC broadcasts go through again");
            }
            if (refused) {
                Runnable told = onRefused;
                if (told != null) told.run();
            }
            if (off && ready && count == offCount && interactive()) {
                if (reading.tvSaysOn) {
                    boolean announce = backHere;
                    tvOn("the TV says its power is on");
                    if (announce) home();
                } else if (reading.tvAsked >= TV_ASKS_UP) {
                    boolean announce = backHere && SystemClock.elapsedRealtime() - offAt < BACK_SOON_MS;
                    tvOn("the TV is asking after its HDMI devices (powering up)");
                    if (announce) home();
                }
            }
        }
        long now = SystemClock.elapsedRealtime();
        handler.removeCallbacks(cecPoll);
        handler.postDelayed(cecPoll, reading == null || !ready ? CEC_WATCH_MS
            : now - replugAt < CEC_FAST_FOR_MS ? CEC_FAST_MS : CEC_POLL_MS);
    }

    /**
     * Home, which sends One Touch Play: the Chromecast is the TV's active source, and its remote reaches it. Under
     * cover, since Home also flashes the stock home (HomeCover).
     */
    private void home() {
        Events.log(service, TAG, "Home, so the Samsung's remote reaches the Chromecast now");
        Runnable press = () -> service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        HomeCover over = cover;
        if (over != null) over.press(press);
        else press.run();
    }

    /**
     * The screen came on: woken by something other than this app (the TV's power key, a remote), so the TV is on.
     * Woken by the TV's power key, the Chromecast isn't HDMI-CEC's active source, which the Samsung's remote needs:
     * Home makes it one (One Touch Play), the TV already on and showing it.
     */
    private void screenOn() {
        if (SystemClock.elapsedRealtime() - rewokeAt < OWN_WAKE_MS) return;
        boolean wasOff = ready;
        tvOn("woken");
        if (wasOff) home();
    }

    /**
     * The HDMI sound output unplugged and plugged back in, with the Chromecast awake: the TV powering up, or the
     * Samsung dropping its hot-plug in standby, which it does just after a wake (OWN_WAKE_MS, not counted) and maybe at
     * other times: poweredUp tells. Its going as the Chromecast falls asleep comes with the screen off, and rewake
     * forgets any going before it. A TV powered up just after the wake is seen by the CEC state instead, where it comes
     * up on the Chromecast's input.
     */
    private void hdmi(boolean plugged) {
        if (!ready || !interactive()) return;
        long now = SystemClock.elapsedRealtime();
        if (!plugged) {
            if (now - rewokeAt >= OWN_WAKE_MS) hdmiGoneAt = now;
        } else if (hdmiGoneAt >= 0) {
            hdmiGoneAt = -1;
            replugAt = now;
            handler.removeCallbacks(poweredUp);
            handler.postDelayed(poweredUp, POWER_REPLY_MS);
            cecPoll();
        }
    }

    /**
     * poweredUp: the HDMI hot-plug came back POWER_REPLY_MS ago with the TV off. Android asks the TV its power status
     * as the hot-plug comes back; a TV still in standby has said so since (the vendor's service wrote the CEC state
     * again) and is left as it is. Otherwise the TV is on. Where it was showing the Chromecast as it went off, so comes
     * back to it, Home makes the Chromecast its active source now, rather than at the end of the Samsung's own check
     * of its HDMI devices 12 s on (2026-11-05 15:59:25 to 15:59:37), until when its remote's buttons reach nothing.
     * Only where HDMI-CEC's history can't be read: there the TV's own word on its power is gone by (cecRead).
     */
    private void poweredUp() {
        if (!ready || history) return;
        if (inactiveAt >= replugAt) {
            Log.i(TAG, "HDMI came back with the TV still in standby: left off");
            return;
        }
        boolean announce = backHere;
        tvOn("HDMI came back (the TV powering up)");
        if (announce) home();
    }

    /** dreamEnded: the screensaver ended with the Chromecast still awake, so by a remote's button. */
    private void dreamEnded() {
        if (ready && !dreaming && interactive() && SystemClock.elapsedRealtime() - rewokeAt >= OWN_WAKE_MS) {
            tvOn("the screensaver ended by hand");
        }
    }

    /** The TV is on: back for phones to see, and the screensaver, if up, ended so the TV shows the home. */
    private void tvOn(String why) {
        cancelRewake();
        handler.removeCallbacks(poweredUp);
        if (!ready) {
            show();
            return;
        }
        setReady(false);
        hdmiGoneAt = -1;
        Events.log(service, TAG, why + ": TV on, back for phones to see");
        show();
        if (dreaming) wake();
        Runnable told = onTvOn;
        if (told != null) told.run();
    }

    /** ready, kept so a restarted service or a reboot with the TV still off knows it. */
    private void setReady(boolean r) {
        ready = r;
        prefs().edit().putBoolean(PREF_TV_OFF, r).apply();
    }

    /**
     * Whether the screensaver is up, for a service started under it (an update): the app in front is the screensaver's
     * (Settings.Secure screensaver_components), read by its name only.
     */
    static boolean dreamUp(AccessibilityService s) {
        PowerManager pm = s.getSystemService(PowerManager.class);
        String dreams = Settings.Secure.getString(s.getContentResolver(), "screensaver_components");
        if (pm == null || !pm.isInteractive() || dreams == null || dreams.isEmpty()) return false;
        for (AccessibilityWindowInfo w : s.getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
            AccessibilityNodeInfo root = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? w.getRoot(0) : w.getRoot();
            if (root == null || root.getPackageName() == null) return false;
            for (String c : dreams.split(",")) {
                ComponentName dream = ComponentName.unflattenFromString(c.trim());
                if (dream != null && dream.getPackageName().contentEquals(root.getPackageName())) return true;
            }
            return false;
        }
        return false;
    }

    private void wake() {
        try {
            service.startActivity(new Intent(service, WakeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not wake the Chromecast", e);
        }
    }

    private SharedPreferences prefs() {
        return Prefs.of(service);
    }

    private boolean interactive() {
        PowerManager pm = service.getSystemService(PowerManager.class);
        return pm == null || pm.isInteractive();
    }
}
