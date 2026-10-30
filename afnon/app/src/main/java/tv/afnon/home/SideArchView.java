package tv.afnon.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/**
 * The mihrab on its side, for the adhan card (PrayerWatch): ArchView's arch turned a quarter, its point toward the
 * middle of the screen and its open base against the edge the card slides in from (PrayerWatch picks it). Its sides
 * run on to that edge, so the card can be any length; the edge itself has no line.
 */
final class SideArchView extends View {
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inner = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The filled shape, closed along the screen's edge; the gold line, open there; the thin line inside it. */
    private final Path shape = new Path(), outline = new Path(), innerLine = new Path();
    /** The card comes in from the left, so the point faces right. */
    private final boolean fromLeft;

    SideArchView(Context c, boolean fromLeft) {
        super(c);
        this.fromLeft = fromLeft;
        fill.setColor(Tokens.LAPIS_800);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setColor(Tokens.GOLD_400);
        stroke.setStrokeWidth(Ui.px(3));
        inner.setStyle(Paint.Style.STROKE);
        inner.setColor(Tokens.ARCH_INNER);
        inner.setStrokeWidth(Ui.px(1.5f));
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        // ArchView's 600-wide arch (design/Home.dc.html) across the card's height, inset by the stroke so none of it
        // is cut by the view's edge, with (x, y) turned to (y, x): the point at the left, middle of the height.
        float in = Ui.px(3), k = (h - 2 * in) / 600f;
        shape.reset();
        outline.reset();
        for (Path p : new Path[] {shape, outline}) {
            p.moveTo(w, in);
            p.lineTo(in + 330 * k, in);
            p.cubicTo(in + 170 * k, in, in + 70 * k, in + 140 * k, in, in + 300 * k);
            p.cubicTo(in + 70 * k, in + 460 * k, in + 170 * k, in + 600 * k, in + 330 * k, in + 600 * k);
            p.lineTo(w, in + 600 * k);
        }
        shape.close();
        innerLine.reset();
        innerLine.moveTo(w, in + 18 * k);
        innerLine.lineTo(in + 334 * k, in + 18 * k);
        innerLine.cubicTo(in + 184 * k, in + 18 * k, in + 92 * k, in + 150 * k, in + 22 * k, in + 300 * k);
        innerLine.cubicTo(in + 92 * k, in + 450 * k, in + 184 * k, in + 582 * k, in + 334 * k, in + 582 * k);
        innerLine.lineTo(w, in + 582 * k);
        if (fromLeft) {
            Matrix flip = new Matrix();
            flip.setScale(-1, 1, w / 2f, 0);
            shape.transform(flip);
            outline.transform(flip);
            innerLine.transform(flip);
        }
    }

    /** How far in from the point the arch reaches its full height, in px: the words go beyond it. */
    static float tip(int h) {
        return (h - 2 * Ui.px(3)) * 330 / 600f;
    }

    @Override
    protected void onDraw(Canvas c) {
        c.drawPath(shape, fill);
        c.drawPath(innerLine, inner);
        c.drawPath(outline, stroke);
    }
}
