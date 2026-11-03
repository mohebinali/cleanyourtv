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
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Keeps the HOME key on the normal home (CleanYourTV's launcher) where the firmware ignores the default Home. Google TV
 * sends HOME to its own launcher while that launcher is enabled, even after `cmd package set-home-activity` has made
 * this app the Home role holder, and disabling the launcher is not safe there: the setup wizard's recovery screen
 * then takes Home, and the wizard has to stay, because the Cast receiver loads its device credentials from it. So when
 * the stock launcher's home screen comes up, this opens the normal home over it, as the cleanyourtv launcher's
 * stock_home_redirect does.
 *
 * A Home press opens the normal home too, because with the stock home already in front Home only brings it up again,
 * with no window event. Android announces each short press that goes home with ACTION_CLOSE_SYSTEM_DIALOGS, reason
 * "homekey", whatever remote sent it, the phone app's included; a press the stock home's window event has already
 * answered opens nothing more.
 *
 * The screen coming on is looked at too. A Chromecast that starts or wakes with the stock home in front sends no window
 * event for it when the TV comes on, so the app in front is read then (the top window's app name, nothing on it) and
 * the normal home opens over the stock home at once: as soon as the display reports it is on, which comes well before
 * Android's screen-on broadcast (that is still heard, in case). The boot probe used before stalled while the screen
 * was off, so the stock home showed for seconds when the TV came on.
 *
 * It acts only while this app holds the Home role, the normal home is installed and HOME still reaches another
 * launcher, so it does nothing where the stock home was kept on purpose. Hosted by RemoteKeys: its window events, and
 * the broadcasts, registered on it.
 */
final class HomeTakeover {

    /** The normal home, which Home is kept on. */
    static final String NORMAL_HOME = "com.gathin.tvhome";
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
     * A Home press opens the normal home this long after it, once the system has brought up the stock home, unless
     * that home's window event has opened it by then.
     */
    private static final long HOME_KEY_DELAY_MS = 250;
    /**
     * One appearance of the stock home can send two window events (shown, then focused). With nothing over the stock
     * home since the last open, its events this soon after that open count once.
     */
    private static final long SAME_PRESS_MS = 1_000;
    /** The stock home coming back this soon after each open, this many times running with no Home press in between,
     *  means the normal home isn't staying up (a crash, say): the TV is then left on the stock home for a minute,
     *  Home presses included, rather than looping. Pressing Home again and again never counts. */
    private static final long BOUNCE_MS = 3_000;
    private static final int MAX_BOUNCES = 4;
    private static final long PAUSE_MS = 60_000;
    /** How long after boot the first connection still counts as boot, for the probe. */
    private static final long BOOT_WINDOW_MS = 3 * 60 * 1000L;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable openLater = this::openIfUnanswered;
    private final Runnable lookAtFront = this::openOverStockFront;
    /** The windows come back a moment after the screen: the app in front is read at these times after it comes on. */
    private static final long[] SCREEN_ON_LOOKS_MS = {0, 300, 1000};
    /** Android's broadcast for the screen coming on. */
    private final BroadcastReceiver screenOnReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            lookAfterScreenOn();
        }
    };
    /** The display turning on, which DisplayManager reports a little before the broadcast comes. */
    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayChanged(int displayId) {
            if (displayId != Display.DEFAULT_DISPLAY) return;
            boolean on = displayOn();
            if (on && !displayWasOn) lookAfterScreenOn();
            displayWasOn = on;
        }

        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }
    };
    private boolean displayWasOn;
    /** Android's broadcast for a short Home press, whatever remote sent it. */
    private final BroadcastReceiver homeKeyReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("homekey".equals(intent.getStringExtra("reason"))) onHomePress();
        }
    };
    /** The stock launcher's HOME activities; empty without the Home role or the normal home, or with no stock home. */
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
        IntentFilter screenOn = new IntentFilter(Intent.ACTION_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(homeKeyReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            service.registerReceiver(screenOnReceiver, screenOn, Context.RECEIVER_NOT_EXPORTED);
        } else {
            service.registerReceiver(homeKeyReceiver, filter);
            service.registerReceiver(screenOnReceiver, screenOn);
        }
        DisplayManager dm = service.getSystemService(DisplayManager.class);
        if (dm != null) {
            displayWasOn = displayOn();
            dm.registerDisplayListener(displayListener, handler);
        }
        if (!stockHome.isEmpty()) atStart();
    }

    /** The screen came on: the app in front is read a few times as its windows come back. */
    private void lookAfterScreenOn() {
        handler.removeCallbacks(lookAtFront);
        for (long delay : SCREEN_ON_LOOKS_MS) handler.postDelayed(lookAtFront, delay);
    }

    private boolean displayOn() {
        DisplayManager dm = service.getSystemService(DisplayManager.class);
        Display d = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
        return d != null && d.getState() == Display.STATE_ON;
    }

    /**
     * The service has just started (boot, or a restart): the stock home may already be in front, with no window event
     * for it. With the screen on, the app in front is read and the normal home opens over the stock home; with it off,
     * the screen coming on does that. Only where the window list can't be read is the old probe used.
     */
    private void atStart() {
        PowerManager pm = service.getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) return;
        if (!openOverStockFront()) probeAtBoot();
    }

    /**
     * The normal home over the stock launcher when that is the app in front (the top full-screen application window),
     * unless it is showing voice search results or the normal home opened a moment ago. False when the window list
     * could not be read.
     */
    private boolean openOverStockFront() {
        long now = SystemClock.elapsedRealtime();
        if (now - resolvedAt >= RESOLVE_MS) resolve(now);
        if (stockHome.isEmpty() || assistantResults) return true;
        // The service hears no window-list events, so its cached list may be old: read it afresh.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) service.clearCache();
        List<AccessibilityWindowInfo> windows = service.getWindows();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || w.isInPictureInPictureMode()) continue;
            // Its app name is all that is wanted: no nodes below the root are fetched.
            AccessibilityNodeInfo root = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ? w.getRoot(0) : w.getRoot();
            if (root == null || root.getPackageName() == null) return false;
            if (root.getPackageName().toString().equals(stockPackage) && now - openedAt >= SAME_PRESS_MS) open();
            return true;
        }
        return false;
    }

    void stop() {
        handler.removeCallbacks(openLater);
        handler.removeCallbacks(lookAtFront);
        DisplayManager dm = service.getSystemService(DisplayManager.class);
        if (dm != null) dm.unregisterDisplayListener(displayListener);
        for (BroadcastReceiver r : new BroadcastReceiver[] {homeKeyReceiver, screenOnReceiver}) {
            try {
                service.unregisterReceiver(r);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
        }
    }

    /**
     * Called for every window change: the stock home screen came up, so the normal home goes over it. Any other full
     * screen covers the stock home, and the stock home coming back after one is a new appearance.
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
                // Our own home (the normal one, or this app's door to it) came up: any voice search results were left.
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
     * A short Home press, from the broadcast. When the stock home is already on screen (it came up at boot, before
     * Android bound the service, or stayed up for voice search results), Home brings it up again with no window change,
     * so the normal home opens once the system has handled the press, unless a window event has opened it by then.
     */
    private void onHomePress() {
        long now = SystemClock.elapsedRealtime();
        // Home leaves voice search results.
        assistantResults = false;
        if (now - resolvedAt >= RESOLVE_MS) resolve(now);
        if (stockHome.isEmpty()) return;
        pressedAt = now;
        handler.removeCallbacks(openLater);
        handler.postDelayed(openLater, HOME_KEY_DELAY_MS);
    }

    /** openLater: the normal home opens for a press no open has followed. Voice search results don't hold it back. */
    private void openIfUnanswered() {
        if (!stockHome.isEmpty() && pressedAt > openedAt) open();
    }

    private void open() {
        long now = SystemClock.elapsedRealtime();
        if (now < pausedUntil) return;
        // Back this soon with no Home press since the last open: the normal home didn't stay up.
        bounces = pressedAt < openedAt && now - openedAt < BOUNCE_MS ? bounces + 1 : 0;
        openedAt = now;
        if (bounces >= MAX_BOUNCES) {
            bounces = 0;
            pausedUntil = now + PAUSE_MS;
            return;
        }
        // The normal home straight, not by way of this app's Home (half a second less of the stock home). Not pinned
        // to a class: a HOME intent without one starts it as a home activity, so Back from apps returns to it.
        try {
            service.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                    .setPackage(NORMAL_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // The normal home is gone or refused: the stock home stays.
        }
    }

    /** Which launcher HOME reaches now, and its home screens when that launcher is not one to leave alone. */
    private void resolve(long now) {
        resolvedAt = now;
        stockHome.clear();
        stockPackage = null;
        if (!holdsHomeRole()) return;
        PackageManager pm = service.getPackageManager();
        // Without the normal home there is nothing to keep Home on: the stock home stays.
        Intent normal = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(NORMAL_HOME);
        if (pm.resolveActivity(normal, PackageManager.MATCH_DEFAULT_ONLY) == null) return;
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
     * The fallback where the window list can't be read. At boot the stock home is often on screen before Android binds
     * RemoteKeys, so no window event comes for it. Once per boot, open ProbeActivity: when it closes, whatever was in
     * front comes back with a window event, and onWindowChanged acts only if that is the stock home, leaving an app or
     * an HDMI input the TV resumed alone.
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
