package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

/**
 * Uninstall in the tile menu and the App library. It only opens Android's own confirmation, so nothing goes until
 * the owner confirms there, and MainActivity's package receiver drops the tile once the app is gone. Offered only for
 * apps the owner installed: the TV's own apps can only be disabled or lose their updates, on their App info page,
 * and neither home is offered, because removing the one that is Home can leave the TV on a black screen.
 */
final class Uninstall {
    private Uninstall() {
    }

    /**
     * True for an app the owner can remove: not one of the TV's own apps, and not this home or the Prayer home.
     * FLAG_SYSTEM stays set on a system app that Play Store has updated, so those are left out too.
     */
    static boolean allowed(String pkg, int flags) {
        return pkg != null && (flags & ApplicationInfo.FLAG_SYSTEM) == 0
                && !pkg.equals("com.gathin.tvhome") && !pkg.equals(MainActivity.AFNON);
    }

    /** The same by package name (one binder call); false when the app is gone or hidden from this one. */
    static boolean allowed(Context ctx, String pkg) {
        try {
            return allowed(pkg, ctx.getPackageManager().getApplicationInfo(pkg, 0).flags);
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return false;
        }
    }

    /**
     * Opens the confirmation. It needs REQUEST_DELETE_PACKAGES in the manifest: without it Android closes the
     * confirmation straight away and the row seems to do nothing.
     */
    static void request(Context ctx, String pkg) {
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg));
        // Only a context that is not an activity needs a new task to start one.
        if (!(ctx instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }
}
