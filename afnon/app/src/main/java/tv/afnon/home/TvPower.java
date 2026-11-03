package tv.afnon.home;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
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

import org.json.JSONException;
import org.json.JSONObject;

/**
 * The TV turning off and on, made quick to come back. Turned off, the Samsung sends HDMI-CEC <Standby> and the
 * Chromecast goes to standby; turned on with its own remote, the Samsung woke it only at the end of its own handshake,
 * 13 s after its HDMI came up (2026-11-03 18:28:08 to 18:28:21), so the home took that long to show. So the Chromecast
 * is kept awake while the TV is off, ready for the TV coming back:
 * - The TV going off: its <Standby> makes HDMI-CEC say "inactive" before the Chromecast's screen goes off (the
 *   Chromecast's own sleep, a remote's power button, does so only after, and is left asleep). Wi-Fi goes off, so no
 *   phone sees the Chromecast while the TV is off (YouTube's Cast button, AirPlay, the Google Home app), the normal
 *   home is put in front, and the Chromecast is woken straight back up (WakeActivity). That sends no One Touch Play, so
 *   the TV stays off, with the Chromecast's HDMI-CEC power_control_mode = none (README). Were the TV to come on all
 *   the same, the CEC state turns "active", and the Chromecast is not kept awake again until the service restarts.
 * - The TV coming on: the Samsung drops its HDMI hot-plug for about a second as it powers up (17:55:30.9 to 32.1),
 *   which Android reports as the HDMI sound output unplugged and plugged back in. Wi-Fi goes back on, and the
 *   screensaver, if it is up, is ended, so the home is what the TV shows. In case that is missed: a remote's buttons
 *   moving the focus, the screensaver ended by hand, the CEC state turning "active", or a wake by anything else.
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
    /** After the screen goes off for the TV, the Chromecast is woken this much later, once standby has settled. */
    private static final long REWAKE_MS = 1_500, DRY_REWAKE_MS = 8_000;
    /**
     * The TV's <Standby> makes the CEC state "inactive" about 0.5 s before the screen goes off; the Chromecast's own
     * sleep, about 0.35 s after it (2026-11-03 17:22:07, 17:55:11). A state turned this recently is the latter's.
     */
    private static final long OWN_SLEEP_MS = 250;
    /** Screen-on and HDMI changes this soon after this app woke the Chromecast are that wake's own. */
    private static final long OWN_WAKE_MS = 6_000;
    /** Focus moves are a remote's buttons only after the home this app opened has settled; two in this time. */
    private static final long FOCUS_SETTLE_MS = 10_000, FOCUS_PAIR_MS = 5_000;
    /** A wake that brings the TV on has made the Chromecast the active source by this much later. */
    private static final long TV_ON_CHECK_MS = 3_000;
    /** Wireless debugging is turned back on once Wi-Fi has connected: looked at this often, so many times. */
    private static final long ADB_POLL_MS = 5_000;
    private static final int ADB_POLLS = 24;
    /** The screensaver ending for the device going to sleep is told from one ended by hand this much later. */
    private static final long DREAM_END_MS = 700;
    /** The test hook's Wi-Fi off, and back on this much later. */
    private static final long TEST_WIFI_MS = 25_000;
    private static final String PREF_WIFI_OFF = "tv_wifi_off_here", PREF_ADB_WAS_ON = "tv_adb_was_on";

    private final AccessibilityService service;
    private final HandlerThread thread = new HandlerThread("tv-power");
    private final Handler handler;
    private final Runnable rewake = this::rewake;
    private final Runnable tvOnCheck = this::tvOnCheck;
    private final Runnable adbBack = this::adbBack;
    private final Runnable dreamEnded = this::dreamEnded;
    // Everything below is touched on the thread only, but for ready, read by AirPlayReturn.
    /** The TV is off, as far as is known, and the Chromecast kept awake for it with Wi-Fi off. */
    private volatile boolean ready;
    /** Waking the Chromecast switched the TV on: not kept awake again while this service runs. */
    private boolean disabled;
    /** The test hook's dry run: Wi-Fi is logged, not turned off or on. */
    private boolean dry;
    private boolean dreaming;
    private long rewokeAt = -OWN_WAKE_MS, hdmiGoneAt = -1, lastFocusAt = -FOCUS_PAIR_MS;
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
                    tvOn("the TV shows the Chromecast");
                }
            }
        };
        service.getContentResolver().registerContentObserver(Settings.Global.getUriFor(CEC_STATE), false,
            cecObserver);
        // Turned off here by a run that has ended (an update, a restart): what the TV is doing isn't known now, so
        // Wi-Fi comes back, the Chromecast reachable rather than lost.
        handler.post(() -> {
            if (prefs().getBoolean(PREF_WIFI_OFF, false)) {
                Log.i(TAG, "service started with Wi-Fi turned off here: back on");
                wifi(true);
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

    /** A view took focus: a remote's buttons in use, so the TV is on. */
    void onFocus() {
        handler.post(() -> {
            if (!ready) return;
            long now = SystemClock.elapsedRealtime();
            if (now - rewokeAt < FOCUS_SETTLE_MS) return;
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

    /** The test hook's dry run: Wi-Fi is only logged until the service restarts, and the Chromecast woken later. */
    void testDry() {
        handler.post(() -> {
            dry = true;
            Log.i(TAG, "test: dry run, Wi-Fi left alone");
        });
    }

    /** The service is going: Wi-Fi turned off here comes back, so the Chromecast isn't left off the network. */
    void quit() {
        service.getContentResolver().unregisterContentObserver(cecObserver);
        try {
            service.unregisterReceiver(receiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered.
        }
        handler.removeCallbacksAndMessages(null);
        handler.post(() -> {
            if (prefs().getBoolean(PREF_WIFI_OFF, false)) wifi(true);
        });
        thread.quitSafely();
    }

    /**
     * The screen went off. For the TV turning off, HDMI-CEC said "inactive" before it did (it already does with the TV
     * on another input); for the Chromecast's own sleep, a remote's power button, only after (OWN_SLEEP_MS), and that
     * is left as it is, reachable for the phone that may wake it. For the TV, Wi-Fi goes off and the Chromecast is
     * woken again; where that switched the TV on earlier, it stays asleep, Wi-Fi off until it wakes.
     */
    private void screenOff() {
        handler.removeCallbacks(rewake);
        handler.removeCallbacks(tvOnCheck);
        if (tvShowsUs(service) || SystemClock.elapsedRealtime() - inactiveAt < OWN_SLEEP_MS) {
            Log.i(TAG, "Chromecast put to sleep with the TV on: left as it is");
            return;
        }
        wifi(false);
        if (disabled) {
            Log.i(TAG, "TV off: Wi-Fi off until the Chromecast wakes");
            return;
        }
        ready = true;
        Log.i(TAG, "TV off: Wi-Fi off, the Chromecast woken again to be ready");
        handler.postDelayed(rewake, dry ? DRY_REWAKE_MS : REWAKE_MS);
    }

    /** rewake: the normal home in front, so an app left playing stays stopped, then the Chromecast woken. */
    private void rewake() {
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
        handler.postDelayed(tvOnCheck, TV_ON_CHECK_MS);
    }

    /** tvOnCheck: the wake made the Chromecast the active source, so it sent One Touch Play and the TV came on. */
    private void tvOnCheck() {
        if (!ready || !tvShowsUs(service)) return;
        disabled = true;
        Log.w(TAG, "waking the Chromecast switched the TV on (power_control_mode isn't none): not kept awake again");
        tvOn("the TV came on with the wake");
    }

    /** The screen came on: woken by something other than this app (the TV's power key, a remote), so the TV is on. */
    private void screenOn() {
        if (SystemClock.elapsedRealtime() - rewokeAt < OWN_WAKE_MS) return;
        tvOn("woken");
    }

    /**
     * The HDMI sound output unplugged and plugged back in, with the Chromecast awake: the TV powering up. Its going as
     * the Chromecast falls asleep, and coming back as it is woken, are not that.
     */
    private void hdmi(boolean plugged) {
        if (!ready || !interactive() || SystemClock.elapsedRealtime() - rewokeAt < OWN_WAKE_MS) return;
        if (!plugged) {
            hdmiGoneAt = SystemClock.elapsedRealtime();
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
        handler.removeCallbacks(rewake);
        if (!ready) {
            if (prefs().getBoolean(PREF_WIFI_OFF, false)) {
                Log.i(TAG, why + ": Wi-Fi back on");
                wifi(true);
            }
            return;
        }
        ready = false;
        hdmiGoneAt = -1;
        Log.i(TAG, why + ": TV on, Wi-Fi back on");
        wifi(true);
        if (dreaming) wake();
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
            if (!wm.isWifiEnabled()) return;
            boolean adbOn = Settings.Global.getInt(service.getContentResolver(), ADB_WIFI, 0) == 1;
            if (wm.setWifiEnabled(false)) {
                prefs.edit().putBoolean(PREF_WIFI_OFF, true).putBoolean(PREF_ADB_WAS_ON, adbOn).apply();
            } else {
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

    /** adbBack: wireless debugging on again once Wi-Fi has connected, as it was before Wi-Fi went off. */
    @SuppressWarnings("deprecation")
    private void adbBack() {
        if (Settings.Global.getInt(service.getContentResolver(), ADB_WIFI, 0) == 1 || ++adbPolls > ADB_POLLS) {
            prefs().edit().putBoolean(PREF_ADB_WAS_ON, false).apply();
            return;
        }
        WifiManager wm = service.getApplicationContext().getSystemService(WifiManager.class);
        WifiInfo info = wm == null ? null : wm.getConnectionInfo();
        if (info != null && info.getSupplicantState() == SupplicantState.COMPLETED && info.getIpAddress() != 0) {
            try {
                Settings.Global.putInt(service.getContentResolver(), ADB_WIFI, 1);
            } catch (SecurityException e) {
                Log.i(TAG, "wireless debugging not turned back on: no WRITE_SECURE_SETTINGS");
                prefs().edit().putBoolean(PREF_ADB_WAS_ON, false).apply();
                return;
            }
        }
        handler.postDelayed(adbBack, ADB_POLL_MS);
    }

    private SharedPreferences prefs() {
        return Prefs.of(service);
    }

    private boolean interactive() {
        PowerManager pm = service.getSystemService(PowerManager.class);
        return pm == null || pm.isInteractive();
    }
}
