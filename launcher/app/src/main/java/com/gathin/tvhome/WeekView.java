package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import java.time.format.TextStyle;
import java.util.Locale;

/**
 * The next four days under the top bar's pills, as wide as their row (Weather.week): a column a day with its name,
 * a line symbol for the weather, the high and the low, and the day's humidity under the humidity sign; the city and
 * the source along the foot. In the pills' glass, so it reads as part of the top bar, and in the home's language and
 * digits: in Arabic today is on the right, as the dock starts there. One view, redrawn only when the days change;
 * MainActivity places it, gives it the pill row's width, and fades it out while the grid is scrolled up under it.
 */
public class WeekView extends View {
    /** Today and the three days after it. */
    static final int DAYS = 4;
    /** A day's narrowest column and the glass's margin at the sides, in dp. */
    private static final float COLUMN = 56f, SIDE = 18f;
    /** Baselines in dp from the top: day names, high, low, humidity; the symbol's top and size. */
    private static final float NAME_Y = 21f, HIGH_Y = 72f, LOW_Y = 89f, HUMID_Y = 106f, SYMBOL_Y = 27f, SYMBOL = 24f;
    private static final int SUN = 0xFFFFCB66, CLOUD = 0xE6FFFFFF, RAIN = 0xFF6EC1FF;

    private final float dp, sp;
    /** Right to left (Arabic, Hebrew): the days run from the right, like the dock. */
    private final boolean mirrored;
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path cloud = new Path(), part = new Path();
    private final Typeface regular, light, medium;
    private Weather.Day[] days = new Weather.Day[0];
    /** Each day's lines as drawn, made by setDays in the home's language and digits; humidity "" when not sent. */
    private String[] names = new String[0], highs = new String[0], lows = new String[0], humids = new String[0];
    private String city = "";
    /** Spaced capitals, as on the date line, for scripts that have them; Arabic letters must stay joined. */
    private boolean caps = true;
    /** The reading has the days' humidity, so its row is shown (one saved before it was added has none). */
    private boolean humidRow;
    /** The width it is given, the pill row's (setSpan); 0 for its own. */
    private int span;

    public WeekView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, getResources().getDisplayMetrics());
        mirrored = getResources().getBoolean(R.bool.rtl_app_grid);
        regular = UiFont.get(context, R.string.font_regular);
        light = UiFont.get(context, R.string.font_light);
        medium = UiFont.get(context, R.string.font_medium);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(dp);
        edge.setColor(0x38FFFFFF);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Shows the first DAYS of these, today first; none leaves the view empty and zero-sized. */
    void setDays(Weather.Day[] all) {
        Weather.Day[] week = java.util.Arrays.copyOf(all, Math.min(DAYS, all.length));
        Locale locale = getResources().getConfiguration().getLocales().get(0);
        caps = TextUtils.getLayoutDirectionFromLocale(locale) == LAYOUT_DIRECTION_LTR;
        String[] name = new String[week.length], high = new String[week.length];
        String[] low = new String[week.length], humid = new String[week.length];
        boolean anyHumid = false;
        for (int i = 0; i < week.length; i++) {
            Weather.Day d = week[i];
            String day = d.date.getDayOfWeek().getDisplayName(TextStyle.SHORT, locale);
            name[i] = caps ? day.toUpperCase(locale) : day;
            high[i] = Weather.degrees(locale, d.high);
            low[i] = Weather.degrees(locale, d.low);
            humid[i] = d.humidity >= 0 ? Weather.percent(locale, d.humidity) : "";
            if (d.humidity >= 0) anyHumid = true;
        }
        String place = getResources().getString(R.string.weather_city);
        city = caps ? place.toUpperCase(locale) : place;
        if (week.length != days.length || anyHumid != humidRow) requestLayout();
        days = week;
        names = name;
        highs = high;
        lows = low;
        humids = humid;
        humidRow = anyHumid;
        invalidate();
    }

    /** Faded as a whole while the grid scrolls: drawn straight, without an offscreen layer. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private float footY() {
        return (humidRow ? HUMID_Y : LOW_Y) + 16f;
    }

    /** Lays the days out across this many px, the columns sharing what the sides leave; never narrower than its own. */
    void setSpan(int px) {
        if (px == span) return;
        span = px;
        requestLayout();
    }

    /** The width it takes for a span: the span, or its own if that is wider (a pill row folded into one gear). */
    int widthFor(int px) {
        return Math.max(px, Math.round((Math.max(1, days.length) * COLUMN + 2 * SIDE) * dp));
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (days.length == 0) {
            setMeasuredDimension(0, 0);
            return;
        }
        setMeasuredDimension(widthFor(span), Math.round((footY() + 10f) * dp));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // The pills' glass (pill_bg): white, 18% at the top fading to 8%.
        glass.setShader(new LinearGradient(0, 0, 0, h, 0x2EFFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (days.length == 0) return;
        float w = getWidth(), radius = 20 * dp, spacing = caps ? 0.16f : 0f;
        box.set(dp / 2, dp / 2, w - dp / 2, getHeight() - dp / 2);
        c.drawRoundRect(box, radius, radius, glass);
        c.drawRoundRect(box, radius, radius, edge);

        text.setTextAlign(Paint.Align.CENTER);
        float column = (w - 2 * SIDE * dp) / days.length;
        for (int i = 0; i < days.length; i++) {
            float mid = SIDE * dp + column * (i + 0.5f);
            float x = mirrored ? w - mid : mid;
            // Arabic day names run long ("الثلاثاء"): each keeps 6dp from the next column.
            label(c, names[i], x, NAME_Y, medium, 11, i == 0 ? 0xFFFFFFFF : 0xA6FFFFFF, spacing, column - 6 * dp);
            symbol(c, days[i].code, x - SYMBOL * dp / 2, SYMBOL_Y * dp, SYMBOL * dp);
            label(c, highs[i], x, HIGH_Y, regular, 20, 0xFFFFFFFF, 0f, 0);
            label(c, lows[i], x, LOW_Y, light, 15, 0x8CFFFFFF, 0f, 0);
            if (humidRow && !humids[i].isEmpty()) humidity(c, humids[i], x);
        }
        // The city on today's side, the source at the other end.
        text.setTextAlign(mirrored ? Paint.Align.RIGHT : Paint.Align.LEFT);
        label(c, city, mirrored ? w - SIDE * dp : SIDE * dp, footY(), medium, 10, 0x73FFFFFF, spacing, 0);
        text.setTextAlign(mirrored ? Paint.Align.LEFT : Paint.Align.RIGHT);
        label(c, "OPEN-METEO", mirrored ? SIDE * dp : w - SIDE * dp, footY(), medium, 10, 0x73FFFFFF, 0.16f, 0);
    }

    /** One line of text, its baseline in dp from the top and its size in sp; shrunk to maxWidth px when set. */
    private void label(Canvas c, String s, float x, float baseline, Typeface face, float size, int color,
                       float spacing, float maxWidth) {
        text.setTypeface(face);
        text.setTextSize(size * sp);
        text.setColor(color);
        text.setLetterSpacing(spacing);
        if (maxWidth > 0) {
            float wide = text.measureText(s);
            if (wide > maxWidth) text.setTextSize(size * sp * maxWidth / wide);
        }
        c.drawText(s, x, baseline * dp, text);
    }

    /**
     * A day's humidity: the humidity sign and the percentage, centred together on the column, the sign first (on the
     * right in Arabic), both in the label's colour as Apple's weather draws them.
     */
    private void humidity(Canvas c, String s, float x) {
        int color = 0xA6FFFFFF;
        text.setTypeface(medium);
        text.setTextSize(11 * sp);
        text.setColor(color);
        text.setLetterSpacing(0.04f);
        float u = 0.7f * dp, sign = HUMID_W * u, gap = 3f * dp, wide = text.measureText(s);
        float start = x - (sign + gap + wide) / 2;
        // Its foot a little under the baseline, as the drop's round body sits lower than the digits.
        humiditySign(c, mirrored ? start + wide + gap : start, HUMID_Y * dp + 0.5f * dp, u, color);
        text.setTextAlign(Paint.Align.LEFT);
        c.drawText(s, mirrored ? start : start + sign + gap, HUMID_Y * dp, text);
        text.setTextAlign(Paint.Align.CENTER);
    }

    /** The humidity sign's width and height in its grid's units. */
    private static final float HUMID_W = 15f, HUMID_H = 13f;

    /**
     * The humidity sign as Apple's weather shows it: three waves of air, the lowest cut short, and a drop at their
     * foot, on a 15×13 grid, u px to a unit, its bottom left at (left, bottom). The same both ways round: not mirrored.
     */
    private void humiditySign(Canvas c, float left, float bottom, float u, int color) {
        float top = bottom - HUMID_H * u, amplitude = 1.25f * u;
        line.setColor(color);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.5f * u);
        // Each wave a run of half waves about 4.4 units long, up first, from x 0.8 to its end.
        // The top wave runs the width; the lower two stop short of the drop, which takes their ends' place.
        float[] ends = {14f, 10.4f, 8.2f};
        for (int i = 0; i < ends.length; i++) {
            float y = top + (2.2f + 3.6f * i) * u, from = left + 0.8f * u, length = (ends[i] - 0.8f) * u;
            int halves = Math.max(1, Math.round(length / (4.4f * u)));
            float half = length / halves;
            part.reset();
            part.moveTo(from, y);
            for (int k = 0; k < halves; k++) {
                float x0 = from + k * half, peak = k % 2 == 0 ? y - amplitude : y + amplitude;
                part.cubicTo(x0 + half / 3, peak, x0 + 2 * half / 3, peak, x0 + half, y);
            }
            c.drawPath(part, line);
        }
        drop(c, left + 12.3f * u, bottom, 7f * u, color);
    }

    /** A drop of water h px tall standing on bottom, centred on cx: a round body under a point. */
    private void drop(Canvas c, float cx, float bottom, float h, int color) {
        float r = h * 0.38f, cy = bottom - r;
        part.reset();
        part.moveTo(cx, bottom - h);
        part.cubicTo(cx + r * 0.25f, bottom - h + r * 0.8f, cx + r, cy - r * 0.6f, cx + r, cy);
        part.arcTo(cx - r, cy - r, cx + r, cy + r, 0f, 180f, false);
        part.cubicTo(cx - r, cy - r * 0.6f, cx - r * 0.25f, bottom - h + r * 0.8f, cx, bottom - h);
        part.close();
        line.setStyle(Paint.Style.FILL);
        line.setColor(color);
        c.drawPath(part, line);
    }

    /**
     * A WMO weather code as a line symbol drawn on a 24-unit grid, size px square with its corner at (left, top):
     * sun, sun behind a cloud, cloud, and below a raised cloud fog, drizzle, rain, snow or a storm's bolt.
     */
    private void symbol(Canvas c, int code, float left, float top, float size) {
        if (code < 0) return;
        float k = size / 24f;
        c.save();
        c.translate(left, top);
        line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(1.7f * k);
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
                line.setColor(CLOUD);
                c.drawLine(5f * k, 18.5f * k, 19f * k, 18.5f * k, line);
                c.drawLine(8f * k, 21.5f * k, 16f * k, 21.5f * k, line);
            } else if ((code >= 71 && code <= 77) || code == 85 || code == 86) {
                line.setColor(CLOUD);
                line.setStyle(Paint.Style.FILL);
                c.drawCircle(8f * k, 18.5f * k, 1.2f * k, line);
                c.drawCircle(12f * k, 21f * k, 1.2f * k, line);
                c.drawCircle(16f * k, 18.5f * k, 1.2f * k, line);
            } else if (code >= 95) {
                line.setColor(SUN);
                part.reset();
                part.moveTo(13f * k, 15.5f * k);
                part.lineTo(10.5f * k, 19f * k);
                part.lineTo(13.5f * k, 19f * k);
                part.lineTo(11f * k, 22.5f * k);
                c.drawPath(part, line);
            } else {
                // Drizzle (51-57) falls in short strokes, rain and showers in longer ones.
                float fall = code >= 51 && code <= 57 ? 2f : 3.5f;
                line.setColor(RAIN);
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
