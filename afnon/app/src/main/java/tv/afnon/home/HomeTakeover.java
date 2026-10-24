package tv.afnon.home;

import android.accessibilityservice.AccessibilityService;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Keeps the HOME key on Afnon where the firmware ignores the default Home. Google TV sends HOME to its own launcher
 * while that launcher is enabled, even after `cmd package set-home-activity` has made Afnon the Home role holder, and
 * disabling the launcher is not safe there: the setup wizard's recovery screen then takes Home, and the wizard has to
 * stay, because the Cast receiver loads its device credentials from it. So when the stock launcher's home screen
 * comes up, this opens Afnon over it, as the cleanyourtv launcher's stock_home_redirect does.
 *
 * It acts only while Afnon holds the Home role and HOME still reaches another launcher, so it does nothing where Afnon
 * really is Home, or where the stock home was kept on purpose. Hosted by RemoteKeys: its window events, and its Home
 * key while it filters keys.
 */
final class HomeTakeover {

    /** Home screens never covered: the normal home, the resolver, the boot stand-in (FallbackHome), the wizard. */
    private static final Set<String> NEVER = new HashSet<>(Arrays.asList(
            "com.gathin.tvhome", "android", "com.android.tv.settings", "com.google.android.tungsten.setupwraith"));
    /** Google TV's Assistant shows its search results in the stock home screen, which then has to stay up. */
    private static final String ASSISTANT = "com.google.android.katniss";
    private static final long ASSISTANT_MS = 15_000;
    /** How often the launcher HOME reaches is looked up again, in case the firmware changes it. */
    private static final long RESOLVE_MS = 30_000;
    /** The system handles the Home key first, so a long press still opens Google TV's quick settings. */
    private static final long HOME_KEY_DELAY_MS = 250;
    /** One Home press can bring both a window event and the key: opens this close together count once. */
    private static final long SAME_PRESS_MS = 1_000;
    /** The stock home coming back this soon after each open, this many times running, means Afnon isn't staying up
     *  (a crash, say): the TV is then left on the stock home for a minute rather than looping. */
    private static final long BOUNCE_MS = 3_000;
    private static final int MAX_BOUNCES = 4;
    private static final long PAUSE_MS = 60_000;
    /** How long after boot the first connection still counts as boot, for the probe. */
    private static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable openLater = this::open;
    /** The stock launcher's HOME activities; empty while Afnon owns Home or doesn't hold the role. */
    private final Set<String> stockHome = new HashSet<>();
    private String stockPackage;
    private long resolvedAt = -RESOLVE_MS;
    private long assistantAt = -ASSISTANT_MS;
    private long openedAt = -BOUNCE_MS;
    private long pausedUntil;
    private int bounces;

    HomeTakeover(AccessibilityService service) {
        this.service = service;
    }

    void start() {
        resolve(SystemClock.elapsedRealtime());
        if (!stockHome.isEmpty()) probeAtBoot();
    }

    void stop() {
        handler.removeCallbacks(openLater);
    }

    /** Called for every window change: the stock home screen came up, so Afnon goes over it. */
    void onWindowChanged(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null || cls == null) return;
        long now = SystemClock.elapsedRealtime();
        if (ASSISTANT.contentEquals(pkg)) {
            assistantAt = now;
            return;
        }
        if (now - resolvedAt >= RESOLVE_MS) resolve(now);
        if (stockHome.isEmpty() || !pkg.toString().equals(stockPackage) || !stockHome.contains(cls.toString())) return;
        // Voice search: the results are in the stock home screen; leave them up.
        if (now - assistantAt < ASSISTANT_MS) return;
        open();
    }

    /**
     * The Home key, seen while RemoteKeys filters keys. When the stock home is already on screen (it came up at boot,
     * before Android bound the service), Home brings it up again with no window change, so a short press opens Afnon
     * once the system has handled the key. A long press is left alone.
     */
    void onHomeKey(KeyEvent event) {
        if (stockHome.isEmpty()) return;
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
            handler.removeCallbacks(openLater);
            handler.postDelayed(openLater, HOME_KEY_DELAY_MS);
        }
    }

    private void open() {
        long now = SystemClock.elapsedRealtime();
        if (now < pausedUntil || now - openedAt < SAME_PRESS_MS) return;
        bounces = now - openedAt < BOUNCE_MS ? bounces + 1 : 0;
        openedAt = now;
        if (bounces >= MAX_BOUNCES) {
            bounces = 0;
            pausedUntil = now + PAUSE_MS;
            return;
        }
        // Not pinned to a class: a HOME intent without one starts Afnon as a home activity, so Back from apps returns
        // to it, and MainActivity goes on to the normal home when that is the chosen theme.
        try {
            service.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(service.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Afnon's Home is gone or refused: the stock home stays.
        }
    }

    /** Which launcher HOME reaches now, and its home screens when that launcher is not one to leave alone. */
    private void resolve(long now) {
        resolvedAt = now;
        stockHome.clear();
        stockPackage = null;
        if (!holdsHomeRole()) return;
        PackageManager pm = service.getPackageManager();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        ResolveInfo winner = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY);
        if (winner == null || winner.activityInfo == null) return;
        String pkg = winner.activityInfo.packageName;
        if (pkg.equals(service.getPackageName()) || NEVER.contains(pkg)) return;
        for (ResolveInfo ri : pm.queryIntentActivities(home.setPackage(pkg), PackageManager.MATCH_DISABLED_COMPONENTS)) {
            stockHome.add(ri.activityInfo.name);
        }
        if (!stockHome.isEmpty()) stockPackage = pkg;
    }

    /** The Home role is where `set-home-activity`, or the TV's own Home picker, records the choice. */
    private boolean holdsHomeRole() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false;
        RoleManager roles = service.getSystemService(RoleManager.class);
        return roles != null && roles.isRoleHeld(RoleManager.ROLE_HOME);
    }

    /**
     * At boot the stock home is often on screen before Android binds RemoteKeys, so no window event comes for it.
     * Once per boot, open ProbeActivity: when it closes, whatever was in front comes back with a window event, and
     * onWindowChanged acts only if that is the stock home, leaving an app or an HDMI input the TV resumed alone.
     */
    private void probeAtBoot() {
        int boot = Settings.Global.getInt(service.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        SharedPreferences prefs = Prefs.of(service);
        if (boot < 0 || boot == prefs.getInt("probe_boot", -1) || SystemClock.elapsedRealtime() > BOOT_WINDOW_MS) return;
        prefs.edit().putInt("probe_boot", boot).apply();
        try {
            service.startActivity(new Intent(service, ProbeActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Then the first Home press does it.
        }
    }
}
