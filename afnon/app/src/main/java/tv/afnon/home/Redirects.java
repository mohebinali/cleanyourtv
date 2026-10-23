package tv.afnon.home;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * Opens another app whenever a given app's screen comes up. For remote buttons the firmware hard-wires to a system
 * app that can't be replaced: on a CVTE TV the ESHARE button always opens EShare, and "com.ecloud.eshare.server=
 * com.droidlogic.miracast" turns it into the Miracast button. Hosted by RemoteKeys, from its window events, so it
 * needs no key filtering and no timer.
 *
 * The "redirects" preference is a comma-separated list of from=to package pairs; empty (the default) does nothing.
 * Set it on a TV with: adb shell run-as tv.afnon.home (edit shared_prefs/afnon.xml), then restart the service.
 */
final class Redirects {
    private Redirects() {}

    /** Called for every window change; opens the target over Home when a redirected app's activity shows. */
    static void onWindowChanged(AccessibilityService s, AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName(), cls = e.getClassName();
        if (pkg == null || cls == null) return;
        String to = target(s, pkg.toString());
        if (to == null || !isActivity(s, pkg.toString(), cls.toString())) return;
        Intent open = Apps.launchIntent(s, to);
        if (open == null) return; // Target missing: leave the original app open.
        // Home underneath, so Back from the target returns Home rather than to the app it replaced.
        Intent home = new Intent(s, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            s.startActivities(new Intent[] {home, open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)});
        } catch (RuntimeException ignored) {
            // The target refuses outside launches; stay where we are.
        }
    }

    private static String target(AccessibilityService s, String from) {
        String list = Prefs.of(s).getString("redirects", "");
        for (String pair : list.split(",")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).trim().equals(from)) return pair.substring(eq + 1).trim();
        }
        return null;
    }

    /** True for the app's activities only, so its toasts and dialogs don't trigger a redirect. */
    private static boolean isActivity(AccessibilityService s, String pkg, String cls) {
        try {
            s.getPackageManager().getActivityInfo(new ComponentName(pkg, cls), 0);
            return true;
        } catch (PackageManager.NameNotFoundException notAnActivity) {
            return false;
        }
    }
}
