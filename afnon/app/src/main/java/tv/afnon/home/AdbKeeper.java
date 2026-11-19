package tv.afnon.home;

import android.content.Context;
import android.database.ContentObserver;
import android.net.wifi.SupplicantState;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Wireless debugging kept on, where the TV's owner asked for that (from adb, `--es adb keep`, MainActivity). Android
 * turns it off whenever Wi-Fi drops, and this Chromecast at every restart, after which whoever looks after the TV over
 * adb is locked out until someone walks the TV's menus (2026-11-19). Once Wi-Fi is connected it is turned back on,
 * which takes WRITE_SECURE_SETTINGS, granted over adb (README). Android does that silently on a network it was told
 * to always allow and asks on the TV on any other, every time it is asked: so once only each time it goes off.
 */
final class AdbKeeper {
    private static final String TAG = "AdbKeeper";
    /** Wireless debugging (Settings.Global.ADB_WIFI_ENABLED). */
    private static final String ADB_WIFI = "adb_wifi_enabled";
    static final String PREF_KEEP = "keep_adb";
    /** Wi-Fi is looked at this often for its connection, so many times (5 minutes). */
    private static final long POLL_MS = 5_000;
    private static final int POLLS = 60;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::poll;
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override
        public void onChange(boolean selfChange) {
            check();
        }
    };
    /** It was asked back on since it last went off: not asked again, were the TV to be asking its owner. */
    private boolean asked;
    private int polls;

    AdbKeeper(Context context) {
        this.context = context;
    }

    void start() {
        context.getContentResolver().registerContentObserver(Settings.Global.getUriFor(ADB_WIFI), false, observer);
        check();
    }

    void stop() {
        context.getContentResolver().unregisterContentObserver(observer);
        handler.removeCallbacks(poll);
    }

    /** Wireless debugging looked at: on, or to be put back once Wi-Fi is there. Also after the owner's word changed. */
    void check() {
        handler.removeCallbacks(poll);
        if (on()) {
            asked = false;
            return;
        }
        if (asked || !Prefs.of(context).getBoolean(PREF_KEEP, false)) return;
        polls = 0;
        handler.postDelayed(poll, POLL_MS);
    }

    private boolean on() {
        return Settings.Global.getInt(context.getContentResolver(), ADB_WIFI, 0) == 1;
    }

    @SuppressWarnings("deprecation")
    private void poll() {
        if (on() || asked || ++polls > POLLS) return;
        WifiManager wm = context.getApplicationContext().getSystemService(WifiManager.class);
        WifiInfo info = wm == null ? null : wm.getConnectionInfo();
        if (info == null || info.getSupplicantState() != SupplicantState.COMPLETED || info.getIpAddress() == 0) {
            handler.postDelayed(poll, POLL_MS);
            return;
        }
        asked = true;
        try {
            Settings.Global.putInt(context.getContentResolver(), ADB_WIFI, 1);
            Events.log(context, TAG, "wireless debugging was off: turned back on");
        } catch (SecurityException e) {
            Log.i(TAG, "wireless debugging not turned back on: no WRITE_SECURE_SETTINGS");
        }
    }
}
