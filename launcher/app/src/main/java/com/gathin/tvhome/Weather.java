package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The week ahead in the middle of Home (WeekView): each day's sky, high, low and humidity, from Open-Meteo (no key,
 * no account).
 *
 * The launcher is otherwise offline, so this keeps out of its way: never on the main thread, never
 * blocking a frame, and silent when there is no network — the last good reading stays on screen.
 */
final class Weather {

    /** Dammam. To move the reading to another city, change these two and weather_city in the strings. */
    private static final double LAT = 26.4333, LON = 50.1000;
    /** Open-Meteo's daily mean relative humidity, which each day of the week shows. */
    private static final String HUMIDITY = "relative_humidity_2m_mean";

    private static final long FRESH_MS = 30L * 60L * 1000L;
    private static final long KEEP_MS = 12L * 60L * 60L * 1000L;

    private static final AtomicBoolean inFlight = new AtomicBoolean(false);
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Weather() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    /**
     * A temperature in the locale's digits: "31°". The Language pill gives Arabic Western digits (Language), so it
     * reads "31°" there too.
     */
    static String degrees(Locale locale, int celsius) {
        return NumberFormat.getIntegerInstance(locale).format(celsius) + "°";
    }

    /** A percentage in the locale's digits and sign: "45%". */
    static String percent(Locale locale, int value) {
        return NumberFormat.getPercentInstance(locale).format(value / 100.0);
    }

    /** One day ahead: its date, WMO code, high and low in °C, and mean relative humidity in percent (-1 when not sent). */
    static final class Day {
        final LocalDate date;
        final int code, high, low, humidity;

        Day(LocalDate date, int code, int high, int low, int humidity) {
            this.date = date;
            this.code = code;
            this.high = high;
            this.low = low;
            this.humidity = humidity;
        }
    }

    /** The week ahead from the last reading, today first; none until a reading lands, or once it is 12 hours old. */
    static Day[] week(Context c) {
        SharedPreferences p = prefs(c);
        if (System.currentTimeMillis() - p.getLong("at", 0L) > KEEP_MS) return new Day[0];
        List<Day> days = new ArrayList<>();
        try {
            JSONObject d = new JSONObject(p.getString("daily", "{}"));
            JSONArray time = d.getJSONArray("time"), code = d.getJSONArray("weather_code");
            JSONArray high = d.getJSONArray("temperature_2m_max"), low = d.getJSONArray("temperature_2m_min");
            JSONArray humidity = d.optJSONArray(HUMIDITY);
            // A reading from last night still starts with yesterday; the week starts today.
            LocalDate today = LocalDate.now();
            for (int i = 0; i < time.length(); i++) {
                LocalDate date = LocalDate.parse(time.getString(i));
                double hi = high.optDouble(i), lo = low.optDouble(i);
                if (date.isBefore(today) || Double.isNaN(hi) || Double.isNaN(lo)) continue;
                days.add(new Day(date, code.optInt(i, -1), (int) Math.round(hi), (int) Math.round(lo),
                        humidity == null ? -1 : humidity.optInt(i, -1)));
            }
        } catch (JSONException | DateTimeParseException e) {
            // No days in this reading (one saved before the week was added): none rather than half a week.
            return new Day[0];
        }
        return days.toArray(new Day[0]);
    }

    /** Fetches when the reading has gone stale; does nothing while one is already out. */
    static void refresh(Context c, final Runnable onUpdated) {
        final Context app = c.getApplicationContext();
        SharedPreferences p = prefs(app);
        // A reading from before the days' humidity was added lacks it: not fresh, so the humidity comes at once.
        if (p.getString("daily", "").contains(HUMIDITY)
                && System.currentTimeMillis() - p.getLong("at", 0L) < FRESH_MS) return;
        if (!inFlight.compareAndSet(false, true)) return;

        new Thread(() -> {
            try {
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min," + HUMIDITY
                        + "&timezone=auto&forecast_days=7";
                JSONObject daily = new JSONObject(get(url)).getJSONObject("daily");
                prefs(app).edit()
                        // The days as Open-Meteo sent them, read back by week().
                        .putString("daily", daily.toString())
                        .putLong("at", System.currentTimeMillis())
                        .apply();
                if (onUpdated != null) main.post(onUpdated);
            } catch (Exception ignored) {
                // Offline or a bad payload: keep the old reading and try again on the next tick.
            } finally {
                inFlight.set(false);
            }
        }, "tvhome-weather").start();
    }

    private static String get(String url) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        try {
            h.setConnectTimeout(8000);
            h.setReadTimeout(8000);
            h.setRequestProperty("Accept", "application/json");
            if (h.getResponseCode() != 200) throw new IllegalStateException("http " + h.getResponseCode());
            InputStream in = h.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } finally {
            h.disconnect();
        }
    }
}
