package tv.afnon.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

/**
 * The radio's level bars: rounded bars rising from a common baseline with the stream's loudness while it plays
 * ({@link Radio#levels}), or breathing gently if the levels can't be read. Stopped, they rest as short steps.
 * Redraws at most ~25 times a second and only while attached and playing, so it costs the weak GPU little.
 */
final class RadioBars extends View {
    private static final long FRAME_MS = 40;
    /** The resting shape: ▮▮▯ steps. */
    private static final float[] REST = {0.35f, 0.6f, 0.45f, 0.75f, 0.3f};

    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private final float[] shown = new float[Radio.BANDS];
    private final int count;
    private boolean live;

    private final Runnable frame = this::invalidate;

    RadioBars(Context c, int count) {
        super(c);
        this.count = Math.max(1, Math.min(Radio.BANDS, count));
        p.setStyle(Paint.Style.FILL);
    }

    void setColor(int color) {
        p.setColor(color);
        invalidate();
    }

    /** Playing: follow the sound. Otherwise rest. */
    void setLive(boolean on) {
        if (live == on) return;
        live = on;
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(frame);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float gap = w / (count * 3f - 1) ;
        float bar = gap * 2;
        long t = SystemClock.uptimeMillis();
        for (int i = 0; i < count; i++) {
            // With fewer bars than bands, take the voice's middle bands.
            int band = count >= Radio.BANDS ? i : Math.min(Radio.BANDS - 1, i + 1);
            float target;
            if (!live) target = REST[band];
            else if (Radio.measured) target = 0.18f + 0.82f * Radio.levels[band];
            else target = 0.45f + 0.35f * (float) Math.sin(t / (260.0 + band * 70) + band * 1.7);
            // Ease toward the target so steps never jump.
            shown[i] += (target - shown[i]) * (live ? 0.5f : 1f);
            float bh = Math.max(bar, h * shown[i]);
            float x = i * (bar + gap);
            r.set(x, h - bh, x + bar, h);
            c.drawRoundRect(r, bar / 2, bar / 2, p);
        }
        if (live && isAttachedToWindow() && getWindowVisibility() == VISIBLE) {
            removeCallbacks(frame);
            postDelayed(frame, FRAME_MS);
        }
    }
}
