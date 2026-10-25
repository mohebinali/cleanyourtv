package tv.afnon.home;

import android.accessibilityservice.AccessibilityService;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
 * A Home press opens Afnon too, because with the stock home already in front Home only brings it up again, with no
 * window event. Android announces each short press that goes home with ACTION_CLOSE_SYSTEM_DIALOGS, reason "homekey",
 * whatever remote sent it: the only sign of a press from a TV remote over HDMI-CEC, whose keys are injected and never
 * reach an accessibility service. The phone app's Home also arrives as a key; the two count as one press, and a press
 * the stock home's window event has already answered opens nothing more.
 *
 * It acts only while Afnon holds the Home role and HOME still reaches another launcher, so it does nothing where Afnon
 * really is Home, or where the stock home was kept on purpose. Hosted by RemoteKeys: its window events, its Home key
 * while it filters keys, and the Home broadcast, registered on it.
 */
final class HomeTakeover {

    /** The normal home, which Afnon hands Home on to when that is the chosen theme. */
    private static final String NORMAL_HOME = "com.gathin.tvhome";
    /** Home screens never covered: the normal home, the resolver, the boot stand-in (FallbackHome), the wizard. */
    private static final Set<String> NEVER = new HashSet<>(Arrays.asList(
            NORMAL_HOME, "android", "com.android.tv.settings", "com.google.android.tungsten.setupwraith"));
    /**
     * Google TV's Assistant shows its search results in the stock home screen, which then has to stay up. The stock
     * home coming up this soon after the Assistant is taken for its results, and left up until Home is pressed or
     * our own home comes up, so that Back from a video picked there still finds them.
     */
    private static final String ASSISTANT = "com.google.android.katniss";
    private static final long ASSISTANT_MS = 15_000;
    /** How often the launcher HOME reaches is looked up again, in case the firmware changes it. */
    private static final long RESOLVE_MS = 30_000;
    /**
     * A Home press opens Afnon this long after it, once the system has brought up the stock home, unless that home's
     * window event has opened Afnon by then.
     */
    private static final long HOME_KEY_DELAY_MS = 250;
    /**
     * One appearance of the stock home can send two window events (shown, then focused). With nothing over the stock
     * home since the last open, its events this soon after that open count once.
     */
    private static final long SAME_PRESS_MS = 1_000;
    /** The stock home coming back this soon after each open, this many times running with no Home press in between,
     *  means Afnon isn't staying up (a crash, say): the TV is then left on the stock home for a minute, Home presses
     *  included, rather than looping. Pressing Home again and again never counts. */
    private static final long BOUNCE_MS = 3_000;
    private static final int MAX_BOUNCES = 4;
    private static final long PAUSE_MS = 60_000;
    /** How long after boot the first connection still counts as boot, for the probe. */
    private static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable openLater = this::openIfUnanswered;
    /** Android's broadcast for a short Home press, whatever remote sent it. */
    private final BroadcastReceiver homeKeyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("homekey".equals(intent.getStringExtra("reason"))) onHomePress();
        }
    };
    /** The stock launcher's HOME activities; empty while Afnon owns Home or doesn't hold the role. */
    private final Set<String> stockHome = new HashSet<>();
    private String stockPackage;
    private long resolvedAt = -RESOLVE_MS;
    private long assistantAt = -ASSISTANT_MS;
    /** The stock home is showing voice search results, so it stays up (see ASSISTANT). */
    private boolean assistantResults;
    private long openedAt = -BOUNCE_MS;
    /** The last Home press, and the last time a full screen other than the stock launcher's covered its home. */
    private long pressedAt = Long.MIN_VALUE, coveredAt = Long.MIN_VALUE;
    private long pausedUntil;
    private int bounces;

    HomeTakeover(AccessibilityService service) {
        this.service = service;
    }

    void start() {
        resolve(SystemClock.elapsedRealtime());
        IntentFilter filter = new IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(homeKeyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            service.registerReceiver(homeKeyReceiver, filter);
        }
        if (!stockHome.isEmpty()) probeAtBoot();
    }

    void stop() {
        handler.removeCallbacks(openLater);
        try {
            service.unregisterReceiver(homeKeyReceiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered.
        }
    }

    /**
     * Called for every window change: the stock home screen came up, so Afnon goes over it. Any other full screen
     * covers the stock home, and the stock home coming back after one is a new appearance.
     */
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
        boolean stockApp = pkg.toString().equals(stockPackage);
        if (!stockApp || !stockHome.contains(cls.toString())) {
            // Not the banner or a dialog, nor another screen of the stock launcher's own.
            if (e.isFullScreen() && !stockApp) {
                coveredAt = now;
                // Our own home (the normal one, or Afnon's) came up: any voice search results were left.
                if (NORMAL_HOME.contentEquals(pkg) || MainActivity.class.getName().contentEquals(cls)) {
                    assistantResults = false;
                }
            }
            return;
        }
        // Voice search: the results are in the stock home screen; leave them up, also when they come back later. A
        // Home press still leaves them: openLater doesn't look at this.
        if (assistantResults || now - assistantAt < ASSISTANT_MS) {
            assistantResults = true;
            return;
        }
        if (coveredAt < openedAt && now - openedAt < SAME_PRESS_MS) return;
        open();
    }

    /**
     * The Home key, seen while RemoteKeys filters keys (from the phone app, say; a TV remote's keys over HDMI-CEC
     * never come here). A short press is a Home press, which its broadcast then joins. A long press is left alone.
     */
    void onHomeKey(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()
                && event.getEventTime() - event.getDownTime() < ViewConfiguration.getLongPressTimeout()) {
            onHomePress();
        }
    }

    /**
     * A short Home press, from the broadcast or the key. When the stock home is already on screen (it came up at boot,
     * before Android bound the service, or stayed up for voice search results), Home brings it up again with no window
     * change, so Afnon opens once the system has handled the press, unless a window event has opened it by then.
     */
    private void onHomePress() {
        long now = SystemClock.elapsedRealtime();
        // Home leaves voice search results.
        assistantResults = false;
        if (now - resolvedAt >= RESOLVE_MS) resolve(now);
        if (stockHome.isEmpty()) return;
        pressedAt = now;
        // The key and its broadcast are one press: one open, after the later of the two.
        handler.removeCallbacks(openLater);
        handler.postDelayed(openLater, HOME_KEY_DELAY_MS);
    }

    /** openLater: Afnon opens for a press that no open has followed. Voice search results don't hold a press back. */
    private void openIfUnanswered() {
        if (!stockHome.isEmpty() && pressedAt > openedAt) open();
    }

    private void open() {
        long now = SystemClock.elapsedRealtime();
        if (now < pausedUntil) return;
        // Back this soon with no Home press since the last open: Afnon didn't stay up.
        bounces = pressedAt < openedAt && now - openedAt < BOUNCE_MS ? bounces + 1 : 0;
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
