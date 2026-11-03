package tv.afnon.home;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * The little the app remembers (SharedPreferences "afnon", kept from earlier versions): the last prayer card shown,
 * so a restarted service doesn't put it up twice, and the boot the probe last ran on (HomeTakeover).
 */
final class Prefs {
    private Prefs() {}

    static SharedPreferences of(Context c) {
        return c.getSharedPreferences("afnon", Context.MODE_PRIVATE);
    }
}
