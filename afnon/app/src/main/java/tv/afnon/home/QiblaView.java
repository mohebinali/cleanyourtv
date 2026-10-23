package tv.afnon.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * The Qibla dial from design/PrayerDay.dc.html (360 viewBox): north at the top, a gold needle at the bearing and
 * the Kaaba as a small square at its tip. A fixed diagram, not a live compass; the TV has no magnetometer.
 */
final class QiblaView extends View {
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path tri = new Path();
    private double bearing;
    /** Where true north is on the dial, clockwise from the top; NaN when the top already is north. */
    private double north = Double.NaN;

    QiblaView(Context c) {
        super(c);
    }

    /** The needle, clockwise from the top of the dial. */
    void setBearing(double degreesFromTop) {
        bearing = degreesFromTop;
        invalidate();
    }

    /**
     * Room mode: the top of the dial is the TV (straight ahead), and a small mist marker shows true north at
     * this angle. NaN returns to north-up.
     */
    void setNorth(double degreesFromTop) {
        north = degreesFromTop;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        float k = getWidth() / 360f;
        c.save();
        c.scale(k, k);
        p.setStyle(Paint.Style.FILL);
        p.setColor(Tokens.LAPIS_900);
        c.drawCircle(180, 180, 160, p);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2);
        p.setColor(Tokens.LINE_SOFT);
        c.drawCircle(180, 180, 160, p);
        p.setStrokeWidth(1.5f);
        p.setColor(Tokens.QIBLA_RING);
        c.drawCircle(180, 180, 120, p);
        // Cardinal ticks.
        p.setStrokeWidth(3);
        p.setColor(Tokens.QIBLA_TICK);
        c.drawLine(180, 20, 180, 38, p);
        c.drawLine(180, 322, 180, 340, p);
        c.drawLine(20, 180, 38, 180, p);
        c.drawLine(322, 180, 340, 180, p);
        // North marker.
        p.setStyle(Paint.Style.FILL);
        p.setColor(Tokens.PLASTER_100);
        tri.reset();
        tri.moveTo(180, 34);
        tri.lineTo(170, 56);
        tri.lineTo(190, 56);
        tri.close();
        c.drawPath(tri, p);
        if (!Double.isNaN(north)) {
            c.save();
            c.rotate((float) north, 180, 180);
            p.setColor(Tokens.MIST_400);
            tri.reset();
            tri.moveTo(180, 66);
            tri.lineTo(173, 82);
            tri.lineTo(187, 82);
            tri.close();
            c.drawPath(tri, p);
            c.restore();
        }
        // Needle and Kaaba, rotated clockwise from the top.
        c.save();
        c.rotate((float) bearing, 180, 180);
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(6);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setColor(Tokens.GOLD_400);
        c.drawLine(180, 180, 180, 30, p);
        p.setStyle(Paint.Style.FILL);
        c.drawRoundRect(167, 14, 193, 40, 3, 3, p);
        c.restore();
        p.setColor(Tokens.PLASTER_100);
        c.drawCircle(180, 180, 10, p);
        c.restore();
    }
}
