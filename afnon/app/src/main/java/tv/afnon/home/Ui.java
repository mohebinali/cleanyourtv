package tv.afnon.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

/** Scaling from design px (1920×1080) to screen px, plus the shared motion curve and background. */
final class Ui {
    private Ui() {}

    static float scale = 1f;

    static void init(Context c) {
        scale = c.getResources().getDisplayMetrics().widthPixels / 1920f;
    }

    static float px(float designPx) { return designPx * scale; }

    static int ipx(float designPx) { return Math.round(designPx * scale); }

    /** motion.easing-standard: cubic-bezier(0.2, 0.8, 0.2, 1). */
    static final Interpolator EASE = new PathInterpolator(0.2f, 0.8f, 0.2f, 1f);

    /**
     * The eight-point star lattice on the lapis ground: one 96px tile (two squares, one turned 45°, and four
     * spokes) in gold-400 at 7%, repeated. Drawn once into a bitmap, so it costs nothing per frame.
     */
    static Drawable starLattice(Context c) {
        int s = Math.max(1, ipx(96));
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(b);
        cv.drawColor(Tokens.LAPIS_900);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(px(1.5f));
        p.setColor(Tokens.PATTERN_LINE);
        float k = s / 96f;
        cv.drawRect(28 * k, 28 * k, 68 * k, 68 * k, p);
        cv.save();
        cv.rotate(45, 48 * k, 48 * k);
        cv.drawRect(28 * k, 28 * k, 68 * k, 68 * k, p);
        cv.restore();
        cv.drawLine(0, 48 * k, 20 * k, 48 * k, p);
        cv.drawLine(76 * k, 48 * k, 96 * k, 48 * k, p);
        cv.drawLine(48 * k, 0, 48 * k, 20 * k, p);
        cv.drawLine(48 * k, 76 * k, 48 * k, 96 * k, p);
        BitmapDrawable d = new BitmapDrawable(c.getResources(), b);
        d.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        return d;
    }
}
