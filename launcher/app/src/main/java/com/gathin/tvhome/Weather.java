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
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The temperature and relative humidity under the clock, and the week ahead under the pills (WeekView), from
 * Open-Meteo (no key, no account). One call brings both.
 *
 * The launcher is otherwise offline, so this keeps out of its way: never on the main thread, never
 * blocking a frame, and silent when there is no network — the last good reading stays on screen.
 */
final class Weather {

    /** Dammam. Change these three to move the reading to another city; CITY is the name the week shows. */
    private static final double LAT = 26.4333, LON = 50.1000;
    static final String CITY = "Dammam";

    private static final long FRESH_MS = 30L * 60L * 1000L;
    private static final long KEEP_MS = 12L * 60L * 60L * 1000L;

    private static final AtomicBoolean inFlight = new AtomicBoolean(false);
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Weather() { }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("weather", Context.MODE_PRIVATE);
    }

    /** "31° · Clear · 45%", or "" until the first reading lands. */
    static String line(Context c) {
        SharedPreferences p = prefs(c);
        long at = p.getLong("at", 0L);
        if (at == 0L || System.currentTimeMillis() - at > KEEP_MS) return "";
        int t = p.getInt("temp", Integer.MIN_VALUE);
        if (t == Integer.MIN_VALUE) return "";
        StringBuilder b = new StringBuilder();
        b.append(t).append('°');
        String sky = describe(p.getInt("code", -1));
        if (!sky.isEmpty()) b.append(" · ").append(sky);
        int h = p.getInt("humidity", -1);
        if (h >= 0) b.append(" · ").append(h).append('%');
        return b.toString();
    }

    /** One day ahead: its date, WMO code, high and low in °C, and the chance of rain in percent (-1 when not sent). */
    static final class Day {
        final LocalDate date;
        final int code, high, low, rain;

        Day(LocalDate date, int code, int high, int low, int rain) {
            this.date = date;
            this.code = code;
            this.high = high;
            this.low = low;
            this.rain = rain;
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
            JSONArray rain = d.optJSONArray("precipitation_probability_max");
            // A reading from last night still starts with yesterday; the week starts today.
            LocalDate today = LocalDate.now();
            for (int i = 0; i < time.length(); i++) {
                LocalDate date = LocalDate.parse(time.getString(i));
                double hi = high.optDouble(i), lo = low.optDouble(i);
                if (date.isBefore(today) || Double.isNaN(hi) || Double.isNaN(lo)) continue;
                days.add(new Day(date, code.optInt(i, -1), (int) Math.round(hi), (int) Math.round(lo),
                        rain == null ? -1 : rain.optInt(i, -1)));
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
        // A reading from before the week was added has no days: not fresh, so the week comes at once.
        if (p.contains("daily") && System.currentTimeMillis() - p.getLong("at", 0L) < FRESH_MS) return;
        if (!inFlight.compareAndSet(false, true)) return;

        new Thread(() -> {
            try {
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
                        + "&current=temperature_2m,relative_humidity_2m,weather_code"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                        + "&timezone=auto&forecast_days=7";
                JSONObject o = new JSONObject(get(url));
                JSONObject cur = o.getJSONObject("current");
                JSONObject daily = o.optJSONObject("daily");
                prefs(app).edit()
                        .putInt("temp", (int) Math.round(cur.getDouble("temperature_2m")))
                        .putInt("humidity", cur.optInt("relative_humidity_2m", -1))
                        .putInt("code", cur.optInt("weather_code", -1))
                        // The days as Open-Meteo sent them, read back by week(); empty clears an older week.
                        .putString("daily", daily == null ? "" : daily.toString())
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

    /** WMO codes, grouped for a glance across the room. */
    private static String describe(int code) {
        if (code < 0) return "";
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code >= 61 && code <= 67) return "Rain";
        if (code >= 71 && code <= 77) return "Snow";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 85 && code <= 86) return "Snow showers";
        if (code >= 95) return "Thunderstorm";
        return "";
    }
}
