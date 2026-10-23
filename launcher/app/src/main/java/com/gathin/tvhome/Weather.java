package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The temperature and relative humidity under the clock, from Open-Meteo (no key, no account).
 *
 * The launcher is otherwise offline, so this keeps out of its way: never on the main thread, never
 * blocking a frame, and silent when there is no network — the last good reading stays on screen.
 */
final class Weather {

    /** Dammam. Change these two to move the reading to another city. */
    private static final double LAT = 26.4333, LON = 50.1000;

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

    /** Fetches when the reading has gone stale; does nothing while one is already out. */
    static void refresh(Context c, final Runnable onUpdated) {
        final Context app = c.getApplicationContext();
        if (System.currentTimeMillis() - prefs(app).getLong("at", 0L) < FRESH_MS) return;
        if (!inFlight.compareAndSet(false, true)) return;

        new Thread(() -> {
            try {
                String url = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
                        + "&current=temperature_2m,relative_humidity_2m,weather_code&timezone=auto";
                JSONObject cur = new JSONObject(get(url)).getJSONObject("current");
                prefs(app).edit()
                        .putInt("temp", (int) Math.round(cur.getDouble("temperature_2m")))
                        .putInt("humidity", cur.optInt("relative_humidity_2m", -1))
                        .putInt("code", cur.optInt("weather_code", -1))
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
