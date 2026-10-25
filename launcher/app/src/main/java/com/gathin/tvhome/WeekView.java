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
 * The week ahead under the top bar's pills (Weather.week): a column a day with its name, a line symbol for the
 * weather, the high and the low, and the chance of rain on a row of its own when any day has one; the city and the
 * source along the foot. In the pills' glass, so it reads as part of the top bar. One view, redrawn only when the
 * days change; MainActivity places it and fades it out while the grid is scrolled up under it.
 */
public class WeekView extends View {
    /** A day's column and the glass's margin at the sides, in dp. */
    private static final float COLUMN = 56f, SIDE = 18f;
    /** Baselines in dp from the top: day names, high, low, chance of rain; the symbol's top and size. */
    private static final float NAME_Y = 26f, HIGH_Y = 92f, LOW_Y = 112f, RAIN_Y = 130f, SYMBOL_Y = 36f, SYMBOL = 28f;
    private static final int SUN = 0xFFFFCB66, CLOUD = 0xE6FFFFFF, RAIN = 0xFF6EC1FF;

    private final float dp, sp;
    private final Paint glass = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF box = new RectF();
    private final Path cloud = new Path(), part = new Path();
    private final Typeface regular, light, medium;
    private Weather.Day[] days = new Weather.Day[0];
    /** Some day has a chance of rain, so the row for it is shown. */
    private boolean rainRow;

    public WeekView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        sp = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 1f, getResources().getDisplayMetrics());
        regular = UiFont.get(context, R.string.font_regular);
        light = UiFont.get(context, R.string.font_light);
        medium = UiFont.get(context, R.string.font_medium);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(dp);
        edge.setColor(0x38FFFFFF);
        line.setStrokeCap(Paint.Cap.ROUND);
        line.setStrokeJoin(Paint.Join.ROUND);
    }

    /** Shows these days, today first; none leaves the view empty and zero-sized. */
    void setDays(Weather.Day[] week) {
        boolean rain = false;
        for (Weather.Day d : week) if (d.rain > 0) rain = true;
        if (week.length != days.length || rain != rainRow) requestLayout();
        days = week;
        rainRow = rain;
        invalidate();
    }

    /** Faded as a whole while the grid scrolls: drawn straight, without an offscreen layer. */
    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private float footY() {
        return (rainRow ? RAIN_Y : LOW_Y) + 25f;
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        if (days.length == 0) {
            setMeasuredDimension(0, 0);
            return;
        }
        setMeasuredDimension(Math.round((days.length * COLUMN + 2 * SIDE) * dp), Math.round((footY() + 14f) * dp));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        // The pills' glass (pill_bg): white, 18% at the top fading to 8%.
        glass.setShader(new LinearGradient(0, 0, 0, h, 0x2EFFFFFF, 0x14FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (days.length == 0) return;
        float w = getWidth(), radius = 20 * dp;
        box.set(dp / 2, dp / 2, w - dp / 2, getHeight() - dp / 2);
        c.drawRoundRect(box, radius, radius, glass);
        c.drawRoundRect(box, radius, radius, edge);

        Locale locale = getResources().getConfiguration().getLocales().get(0);
        // Spaced capitals like the date line, for scripts that have them; Arabic letters must stay joined.
        boolean caps = TextUtils.getLayoutDirectionFromLocale(locale) == LAYOUT_DIRECTION_LTR;
        text.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < days.length; i++) {
            Weather.Day d = days[i];
            float x = (SIDE + COLUMN * i + COLUMN / 2) * dp;
            String name = d.date.getDayOfWeek().getDisplayName(TextStyle.SHORT, locale);
            label(c, caps ? name.toUpperCase(locale) : name, x, NAME_Y, medium, 11, i == 0 ? 0xFFFFFFFF : 0xA6FFFFFF,
                    caps ? 0.16f : 0f);
            symbol(c, d.code, x - SYMBOL * dp / 2, SYMBOL_Y * dp, SYMBOL * dp);
            label(c, d.high + "°", x, HIGH_Y, regular, 22, 0xFFFFFFFF, 0f);
            label(c, d.low + "°", x, LOW_Y, light, 16, 0x8CFFFFFF, 0f);
            if (rainRow && d.rain > 0) label(c, d.rain + "%", x, RAIN_Y, medium, 11, RAIN, 0.04f);
        }
        text.setTextAlign(Paint.Align.LEFT);
        label(c, Weather.CITY.toUpperCase(Locale.ROOT), SIDE * dp, footY(), medium, 10, 0x73FFFFFF, 0.16f);
        text.setTextAlign(Paint.Align.RIGHT);
        label(c, "OPEN-METEO", w - SIDE * dp, footY(), medium, 10, 0x73FFFFFF, 0.16f);
    }

    /** One line of text, its baseline in dp from the top and its size in sp. */
    private void label(Canvas c, String s, float x, float baseline, Typeface face, float size, int color,
                       float spacing) {
        text.setTypeface(face);
        text.setTextSize(size * sp);
        text.setColor(color);
        text.setLetterSpacing(spacing);
        c.drawText(s, x, baseline * dp, text);
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
