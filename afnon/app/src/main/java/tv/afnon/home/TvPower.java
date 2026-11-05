package tv.afnon.home;

import android.accessibilityservice.AccessibilityService;
import android.app.AlarmManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
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

/**
 * The TV turning off and on, made quick to come back. Turned off, the Samsung sends HDMI-CEC <Standby> and the
 * Chromecast goes to standby; turned on with its own remote, the Samsung woke it only at the end of its own handshake,
 * 13 s after its HDMI came up (2026-11-03 18:28:08 to 18:28:21), so the home took that long to show. So the Chromecast
 * is kept awake while the TV is off, ready for the TV coming back:
 * - The TV going off: its <Standby> makes HDMI-CEC say "inactive" before the Chromecast's screen goes off (the
 *   Chromecast's own sleep, a remote's power button, does so only after, and is left asleep). Wi-Fi goes off, so no
 *   phone sees the Chromecast while the TV is off (YouTube's Cast button, AirPlay, the Google Home app), and 30 s on
 *   the normal home is put in front and the Chromecast woken back up (WakeActivity). That sends no One Touch Play, so
 *   the TV stays off, with the Chromecast's HDMI-CEC power_control_mode = none (README). Were the TV to come on all
 *   the same, the CEC state turns "active", and the Chromecast is not kept awake again until the service restarts.
 *   A TV turned back on within the 30 s wakes the Chromecast itself, as it always did.
 * - The TV coming on: the Samsung drops its HDMI hot-plug for about a second as it powers up (17:55:30.9 to 32.1),
 *   which Android reports as the HDMI sound output unplugged and plugged back in (it does so too just after this
 *   app's wake, staying in standby, 2026-11-05 15:47:06, which isn't counted). Wi-Fi goes back on, and the
 *   screensaver, if it is up, is ended, so the home is what the TV shows. In case that is missed: a remote's buttons
 *   moving the focus, the screensaver ended by hand, the CEC state turning "active" (powered up on the Chromecast's
 *   input, the Samsung selects it, <Set Stream Path>, 15:47:21), or a wake by anything else. A TV that wakes the
 *   Chromecast itself (on again within the 30 s) doesn't make it HDMI-CEC's active source, and the Samsung's remote
 *   then reaches the Chromecast not at all (15:34:46 to 15:38:46): Home is pressed, which sends One Touch Play.
 * Wi-Fi is only turned back on here if it was turned off here. Android turns wireless debugging off with Wi-Fi; it is
 * turned back on once Wi-Fi has connected, where it was on and this app may (WRITE_SECURE_SETTINGS, granted over
 * adb), which Android does silently only on a network it already trusts. Only apps built for Android 9 or before may
 * turn Wi-Fi on and off, hence the app's targetSdk.
 */
final class TvPower {
    private static final String TAG = "TvPower";
    /**
     * HDMI-CEC's active source as the vendor's service (com.droidlogic, HdmiCecActiveness) reports it for Netflix:
     * {"activeCecState":"active"} while the TV shows the Chromecast, "inactive" while it shows another input or is off.
     */
    private static final String CEC_STATE = "nrdp_video_platform_capabilities";
    /** Wireless debugging (Settings.Global.ADB_WIFI_ENABLED). */
    private static final String ADB_WIFI = "adb_wifi_enabled";
    /**
     * After the screen goes off for the TV, the Chromecast is woken this much later. The wake runs Android's whole
     * HDMI-CEC start-up (its address claimed, then broadcasts of it, its vendor and features); 1.5 s after the TV's
     * <Standby> (0.29, 0.30) that fell in the Samsung's own change-over to standby, and two days on the Samsung was
     * found refusing every broadcast, so passing no remote keys, until its mains plug was pulled (2026-11-05). Not
     * proven the cause, but by 30 s the Samsung has long settled.
     */
    private static final long REWAKE_MS = 30_000;
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
    /** Wireless debugging is turned back on once Wi-Fi has connected: looked at this often, so many times. */
    private static final long ADB_POLL_MS = 5_000;
    private static final int ADB_POLLS = 24;
    /** The screensaver ending for the device going to sleep is told from one ended by hand this much later. */
    private static final long DREAM_END_MS = 700;
    /** The test hook's Wi-Fi off, and back on this much later. */
    private static final long TEST_WIFI_MS = 25_000;
    private static final String PREF_WIFI_OFF = "tv_wifi_off_here", PREF_ADB_WAS_ON = "tv_adb_was_on";
    /** The TV off, kept for a restarted service or a reboot, where the Chromecast comes up awake with no screen-off. */
    private static final String PREF_TV_OFF = "tv_off";

    private final AccessibilityService service;
    private final HandlerThread thread = new HandlerThread("tv-power");
    private final Handler handler;
    private final AlarmManager alarms;
    private final AlarmManager.OnAlarmListener rewake = this::rewake;
    private final Runnable adbBack = this::adbBack;
    private final Runnable dreamEnded = this::dreamEnded;
    /** Told when the TV comes back on (AirPlayReturn looks for a receiver screen opened meanwhile). */
    volatile Runnable onTvOn;
    // Everything below is touched on the thread only, but for ready, read by AirPlayReturn.
    /** The TV is off, as far as is known, and the Chromecast kept awake for it with Wi-Fi off. */
    private volatile boolean ready;
    /** Waking the Chromecast switched the TV on: not kept awake again while this service runs. */
    private boolean disabled;
    /** The test hook's dry run: Wi-Fi is logged, not turned off or on. */
    private boolean dry;
    private boolean dreaming;
    /** rewake's alarm is set (cancelling one that isn't makes Android log a warning). */
    private boolean rewakeSet;
    private long rewokeAt = -OWN_WAKE_MS, hdmiGoneAt = -1, lastFocusAt = -FOCUS_PAIR_MS;
    /** When a window last came up (its focus moves are not a remote's). */
    private long windowAt = Long.MIN_VALUE / 2;
    /** When the CEC state last turned "inactive" (long ago where not seen). */
    private long inactiveAt = Long.MIN_VALUE / 2;
    private int adbPolls;
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
                if (!tvShowsUs(service)) {
                    inactiveAt = SystemClock.elapsedRealtime();
                } else if (ready) {
                    if (SystemClock.elapsedRealtime() - rewokeAt < TV_ON_CHECK_MS) {
                        // This app's wake sent One Touch Play (power_control_mode isn't none) and the TV came on.
                        disabled = true;
                        Log.w(TAG, "waking the Chromecast switched the TV on: not kept awake again");
                    }
                    tvOn("the TV shows the Chromecast");
                }
            }
        };
        service.getContentResolver().registerContentObserver(Settings.Global.getUriFor(CEC_STATE), false,
            cecObserver);
        handler.post(() -> {
            dreaming = dreamUp(service);
            // The TV was off when the last run ended (an update, a restart, a reboot) and still seems so: awake, as
            // the Chromecast comes up, with no screen-off to go by, it is kept off Wi-Fi and AirPlay leaves the TV
            // off. Otherwise Wi-Fi turned off here comes back, the Chromecast reachable rather than lost.
            if (prefs().getBoolean(PREF_TV_OFF, false) && interactive() && !tvShowsUs(service)) {
                Log.i(TAG, "service started with the TV off: kept off Wi-Fi");
                ready = true;
                rewokeAt = SystemClock.elapsedRealtime();
                wifi(false);
            } else {
                setReady(false);
                if (prefs().getBoolean(PREF_WIFI_OFF, false)) {
                    Log.i(TAG, "service started with Wi-Fi turned off here: back on");
                    wifi(true);
                }
            }
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

    /** A window came up (its focus moving with it is not a remote's buttons). */
    void onWindow() {
        handler.post(() -> windowAt = SystemClock.elapsedRealtime());
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

    /** The test hook: Wi-Fi off now and back on 25 s later, by this app, so an adb over Wi-Fi survives the test. */
    void testWifi() {
        handler.post(() -> {
            Log.i(TAG, "test: Wi-Fi off for " + TEST_WIFI_MS / 1000 + " s");
            wifi(false);
            handler.postDelayed(() -> {
                Log.i(TAG, "test: Wi-Fi back on");
                wifi(true);
            }, TEST_WIFI_MS);
        });
    }

    /** The test hook's dry run: Wi-Fi is only logged until the service restarts. */
    void testDry() {
        handler.post(() -> {
            dry = true;
            Log.i(TAG, "test: dry run, Wi-Fi left alone");
        });
    }

    /**
     * The service is going. Where it is stopping for good (turned off), Wi-Fi turned off here comes back, so the
     * Chromecast isn't left off the network; where it is starting again, the new run decides.
     */
    void quit(boolean restoreWifi) {
        service.getContentResolver().unregisterContentObserver(cecObserver);
        try {
            service.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered.
        }
        if (alarms != null) alarms.cancel(rewake);
        handler.removeCallbacksAndMessages(null);
        if (restoreWifi) {
            handler.post(() -> {
                setReady(false);
                if (prefs().getBoolean(PREF_WIFI_OFF, false)) wifi(true);
            });
        }
        thread.quitSafely();
    }

    /**
     * The screen went off. For the TV turning off, HDMI-CEC said "inactive" before it did (it already does with the TV
     * on another input); for the Chromecast's own sleep, a remote's power button, only after (OWN_SLEEP_MS), and that
     * is left as it is, reachable for the phone that may wake it. For the TV, Wi-Fi goes off and the Chromecast is
     * woken again 30 s later, once; where that switched the TV on earlier, it stays asleep, Wi-Fi off until it wakes.
     */
    private void screenOff() {
        cancelRewake();
        if (tvShowsUs(service) || SystemClock.elapsedRealtime() - inactiveAt < OWN_SLEEP_MS) {
            Log.i(TAG, "Chromecast put to sleep with the TV on: left as it is");
            return;
        }
        wifi(false);
        if (disabled) {
            Log.i(TAG, "TV off: Wi-Fi off until the Chromecast wakes");
            return;
        }
        if (ready && SystemClock.elapsedRealtime() - rewokeAt < REWAKE_AGAIN_MS) {
            Log.w(TAG, "TV off again just after the Chromecast was woken for it: left asleep, Wi-Fi off");
            return;
        }
        setReady(true);
        Log.i(TAG, "TV off: Wi-Fi off, the Chromecast woken again in " + REWAKE_MS / 1000 + " s to be ready");
        if (alarms != null) {
            alarms.setExact(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + REWAKE_MS,
                "afnon:rewake", rewake, handler);
            rewakeSet = true;
        }
    }

    private void cancelRewake() {
        if (rewakeSet) alarms.cancel(rewake);
        rewakeSet = false;
    }

    /**
     * rewake, from an alarm (a Handler's delay stops while the processor is suspended, as it may be 30 s into
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
        if (wasOff) {
            Log.i(TAG, "Home, so the Samsung's remote reaches the Chromecast");
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        }
    }

    /**
     * The HDMI sound output unplugged and plugged back in, with the Chromecast awake: the TV powering up. Its going as
     * the Chromecast falls asleep comes with the screen off, and rewake forgets any going before it; the Samsung, in
     * standby, drops it too just after a wake (OWN_WAKE_MS). A TV powered up in those seconds is seen by the CEC
     * state instead, where it comes up on the Chromecast's input.
     */
    private void hdmi(boolean plugged) {
        if (!ready || !interactive()) return;
        if (!plugged) {
            if (SystemClock.elapsedRealtime() - rewokeAt >= OWN_WAKE_MS) hdmiGoneAt = SystemClock.elapsedRealtime();
        } else if (hdmiGoneAt >= 0) {
            tvOn("HDMI came back (the TV powering up)");
        }
    }

    /** dreamEnded: the screensaver ended with the Chromecast still awake, so by a remote's button. */
    private void dreamEnded() {
        if (ready && !dreaming && interactive() && SystemClock.elapsedRealtime() - rewokeAt >= OWN_WAKE_MS) {
            tvOn("the screensaver ended by hand");
        }
    }

    /** The TV is on: Wi-Fi back on, and the screensaver, if up, ended so the TV shows the home. */
    private void tvOn(String why) {
        cancelRewake();
        if (!ready) {
            if (prefs().getBoolean(PREF_WIFI_OFF, false)) {
                Log.i(TAG, why + ": Wi-Fi back on");
                wifi(true);
            }
            return;
        }
        setReady(false);
        hdmiGoneAt = -1;
        Log.i(TAG, why + ": TV on, Wi-Fi back on");
        wifi(true);
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

    /**
     * Wi-Fi off, unless it is off already (then it isn't this app's to turn back on), or back on where this app turned
     * it off; wireless debugging is then looked after (adbBack).
     */
    @SuppressWarnings("deprecation")
    private void wifi(boolean on) {
        WifiManager wm = service.getApplicationContext().getSystemService(WifiManager.class);
        if (wm == null) return;
        SharedPreferences prefs = prefs();
        if (dry) {
            Log.i(TAG, "dry run: Wi-Fi would go " + (on ? "on" : "off"));
            return;
        }
        if (!on) {
            // Still turning on counts as on (isWifiEnabled says no until it is up).
            int state = wm.getWifiState();
            if (state != WifiManager.WIFI_STATE_ENABLED && state != WifiManager.WIFI_STATE_ENABLING) return;
            boolean adbOn = Settings.Global.getInt(service.getContentResolver(), ADB_WIFI, 0) == 1;
            // Written before Wi-Fi goes, and to disk now: were the app to die in between, the next run must still
            // know to turn it back on.
            prefs.edit().putBoolean(PREF_WIFI_OFF, true).putBoolean(PREF_ADB_WAS_ON, adbOn).commit();
            if (!wm.setWifiEnabled(false)) {
                prefs.edit().putBoolean(PREF_WIFI_OFF, false).apply();
                Log.w(TAG, "Wi-Fi could not be turned off");
            }
            return;
        }
        if (!prefs.getBoolean(PREF_WIFI_OFF, false)) return;
        if (!wm.setWifiEnabled(true)) {
            Log.w(TAG, "Wi-Fi could not be turned back on");
            return;
        }
        prefs.edit().putBoolean(PREF_WIFI_OFF, false).apply();
        if (prefs.getBoolean(PREF_ADB_WAS_ON, false)) {
            adbPolls = 0;
            handler.removeCallbacks(adbBack);
            handler.postDelayed(adbBack, ADB_POLL_MS);
        }
    }

    /**
     * adbBack: wireless debugging on again once Wi-Fi has connected, as it was before Wi-Fi went off. Once only: on a
     * network Android doesn't trust it asks on the TV, every time it is asked to turn it on.
     */
    @SuppressWarnings("deprecation")
    private void adbBack() {
        if (Settings.Global.getInt(service.getContentResolver(), ADB_WIFI, 0) == 1 || ++adbPolls > ADB_POLLS) {
            prefs().edit().putBoolean(PREF_ADB_WAS_ON, false).apply();
            return;
        }
        WifiManager wm = service.getApplicationContext().getSystemService(WifiManager.class);
        WifiInfo info = wm == null ? null : wm.getConnectionInfo();
        if (info == null || info.getSupplicantState() != SupplicantState.COMPLETED || info.getIpAddress() == 0) {
            handler.postDelayed(adbBack, ADB_POLL_MS);
            return;
        }
        prefs().edit().putBoolean(PREF_ADB_WAS_ON, false).apply();
        try {
            Settings.Global.putInt(service.getContentResolver(), ADB_WIFI, 1);
        } catch (SecurityException e) {
            Log.i(TAG, "wireless debugging not turned back on: no WRITE_SECURE_SETTINGS");
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
