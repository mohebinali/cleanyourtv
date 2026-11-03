package tv.afnon.home;

import android.content.Context;

/** Scaling from design px (1920×1080) to screen px. */
final class Ui {
    private Ui() {}

    static float scale = 1f;

    static void init(Context c) {
        scale = c.getResources().getDisplayMetrics().widthPixels / 1920f;
    }

    static float px(float designPx) { return designPx * scale; }

    static int ipx(float designPx) { return Math.round(designPx * scale); }
}
