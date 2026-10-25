package tv.afnon.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/**
 * The week's weather in the corner the dates leave free under the header pills: a column a day with its name, a
 * line symbol, the high and the low, on lapis with the pills' soft border; today's name in gold. As many days as
 * fit beside the widest date line ({@link #setRoom}), up to the seven Open-Meteo sends; with room for fewer than
 * three it shows none. Drawn only when the days or the room change.
 */
final class WeekView extends View {
    /** Design px: the panel's height and the gap it keeps from the widest date line. */
    static final float HEIGHT = 128, GAP = 40;
    /** Design px: the margin at the sides, and a day's column in English and in Arabic (longer day names). */
    private static final float SIDE = 14, COLUMN_EN = 72, COLUMN_AR = 82;
    private static final int SUN = Tokens.GOLD_400, CLOUD = Tokens.PLASTER_100, FALL = Tokens.MIST_400,
            BOLT = Tokens.GOLD_200;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path cloud = new Path(), part = new Path();
    private final Typeface regular, medium;
    private Weather.Day[] days = new Weather.Day[0];
    private Text t;
    /** Design px free beside the dates; none until they are laid out, so a long date is never drawn over. */
    private float room;
    /** Days drawn: as many as fit, or 0 when fewer than three do. */
    private int shown;

    WeekView(Context c, Typeface regular, Typeface medium) {
        super(c);
        this.regular = regular;
        this.medium = medium;
        fill.setColor(Tokens.LAPIS_800);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(Ui.px(Tokens.BORDER_CHIP_WIDTH));
        edge.setColor(Tokens.BORDER_CHIP_COLOR);
        text.setTextAlign(Paint.Align.CENTER);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    /** The days to show, today first, in this screen's language and digits. */
    void setDays(Weather.Day[] week, Text words) {
        days = week;
        t = words;
        fit();
        invalidate();
    }

    /** The design px left beside the dates; called from their layout listener whenever their width changes. */
    void setRoom(float designPx) {
        if (designPx == room) return;
        room = designPx;
        fit();
    }

    private float column() {
        return t != null && t.ar ? COLUMN_AR : COLUMN_EN;
    }

    private void fit() {
        int n = Math.min(days.length, (int) ((room - 2 * SIDE) / column()));
        if (n < 3) n = 0;
        if (n == shown) return;
        shown = n;
        // setRoom runs inside a layout pass: ask for the new size after it, not during it.
        post(this::requestLayout);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (shown == 0) setMeasuredDimension(0, 0);
        else setMeasuredDimension(Ui.ipx(shown * column() + 2 * SIDE), Ui.ipx(HEIGHT));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (shown == 0 || t == null) return;
        float w = getWidth(), inset = Ui.px(Tokens.BORDER_CHIP_WIDTH) / 2, radius = Ui.px(Tokens.RADIUS_PANEL);
        box.set(inset, inset, w - inset, getHeight() - inset);
        c.drawRoundRect(box, radius, radius, fill);
        c.drawRoundRect(box, radius, radius, edge);
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float col = column();
        for (int i = 0; i < shown; i++) {
            Weather.Day d = days[i];
            // Today on the start side, where the dates begin: the left in English, the right in Arabic.
            float mid = Ui.px(SIDE + col * i + col / 2);
            float x = rtl ? w - mid : mid;
            label(c, t.weekday(d.date.getDayOfWeek().getValue()), x, 28, regular, Tokens.FONT_SIZE_CAPTION,
                    i == 0 ? Tokens.GOLD_400 : Tokens.MIST_400, t.ar ? 0f : Tokens.FONT_LETTERSPACING_CAPS_EN, col - 8);
            symbol(c, d.code, x - Ui.px(15), Ui.px(36), Ui.px(30));
            label(c, t.d(d.high + "°"), x, 91, medium, Tokens.FONT_SIZE_BODY, Tokens.PLASTER_100, 0f, 0);
            label(c, t.d(d.low + "°"), x, 114, regular, Tokens.FONT_SIZE_LABEL, Tokens.MIST_400, 0f, 0);
        }
    }

    /** Centred text on a baseline in design px, shrunk to maxWidth design px when it is wider and that is set. */
    private void label(Canvas c, String s, float x, float baseline, Typeface face, float size, int color, float spacing,
                       float maxWidth) {
        text.setTypeface(face);
        text.setTextSize(Ui.px(size));
        text.setColor(color);
        text.setLetterSpacing(spacing);
        if (maxWidth > 0) {
            float wide = text.measureText(s);
            if (wide > Ui.px(maxWidth)) text.setTextSize(Ui.px(size) * Ui.px(maxWidth) / wide);
        }
        c.drawText(s, x, Ui.px(baseline), text);
    }

    /**
     * A WMO weather code as a line symbol on the 24px icon grid (README: Iconography), size px square with its
     * corner at (left, top): sun, sun behind a cloud, cloud, and below a raised cloud fog, drizzle, rain, snow or a
     * storm's bolt.
     */
    private void symbol(Canvas c, int code, float left, float top, float size) {
        if (code < 0) return;
        float k = size / 24f;
        c.save();
        c.translate(left, top);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.8f * k);
        if (code <= 1) {
            sun(c, k, 12f, 12f, 4.2f, 7f, 9.5f);
        } else if (code == 2) {
            cloud(k, 2.5f, 3f);
            // The sun peeks out from behind the cloud: none of it is drawn inside the cloud's outline.
            c.save();
            c.clipOutPath(cloud);
            sun(c, k, 8.5f, 8.5f, 3.2f, 5.4f, 7.2f);
            c.restore();
            stroke(c, CLOUD);
        } else if (code == 3) {
            cloud(k, 0f, -0.5f);
            stroke(c, CLOUD);
        } else {
            cloud(k, 0f, -3.5f);
            stroke(c, CLOUD);
            if (code == 45 || code == 48) {
                line.setColor(FALL);
                c.drawLine(5f * k, 18.5f * k, 19f * k, 18.5f * k, line);
                c.drawLine(8f * k, 21.5f * k, 16f * k, 21.5f * k, line);
            } else if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
                line.setColor(CLOUD);
                line.setStyle(Paint.Style.FILL);
                c.drawCircle(8f * k, 18.5f * k, 1.2f * k, line);
                c.drawCircle(12f * k, 21f * k, 1.2f * k, line);
                c.drawCircle(16f * k, 18.5f * k, 1.2f * k, line);
            } else if (code >= 95) {
                line.setColor(BOLT);
                part.reset();
                part.moveTo(13f * k, 15.5f * k);
                part.lineTo(10.5f * k, 19f * k);
                part.lineTo(13.5f * k, 19f * k);
                part.lineTo(11f * k, 22.5f * k);
                c.drawPath(part, line);
            } else {
                // Drizzle (51-57) falls in short strokes, rain and showers in longer ones.
                float fall = code >= 51 && code <= 57 ? 2f : 3.5f;
                line.setColor(FALL);
                for (int i = 0; i < 3; i++) {
                    float x = 8.5f + 4f * i;
                    c.drawLine(x * k, 18f * k, (x - fall * 0.4f) * k, (18f + fall) * k, line);
                }
            }
        }
        c.restore();
    }

    /** A sun: a ring of radius core and eight rays from one radius to another, all in grid units. */
    private void sun(Canvas c, float k, float cx, float cy, float core, float from, float to) {
        line.setColor(SUN);
        c.drawCircle(cx * k, cy * k, core * k, line);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            float dx = (float) Math.cos(a), dy = (float) Math.sin(a);
            c.drawLine((cx + from * dx) * k, (cy + from * dy) * k, (cx + to * dx) * k, (cy + to * dy) * k, line);
        }
    }

    /** The cloud's outline into {@link #cloud}: three round puffs on a flat base, moved by (dx, dy) on the grid. */
    private void cloud(float k, float dx, float dy) {
        cloud.reset();
        cloud.addCircle((7.5f + dx) * k, (14.5f + dy) * k, 3.5f * k, Path.Direction.CW);
        part.reset();
        part.addCircle((12.5f + dx) * k, (11f + dy) * k, 4.5f * k, Path.Direction.CW);
        cloud.op(part, Path.Op.UNION);
        part.reset();
        part.addCircle((16.5f + dx) * k, (14.5f + dy) * k, 3.5f * k, Path.Direction.CW);
        cloud.op(part, Path.Op.UNION);
        part.reset();
        part.addRect((7.5f + dx) * k, (14.5f + dy) * k, (16.5f + dx) * k, (18f + dy) * k, Path.Direction.CW);
        cloud.op(part, Path.Op.UNION);
    }

    private void stroke(Canvas c, int color) {
        line.setColor(color);
        c.drawPath(cloud, line);
    }
}
