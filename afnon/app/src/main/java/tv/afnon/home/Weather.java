package tv.afnon.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The weather beside the dates, for the city already chosen for prayer times.
 *
 * Open-Meteo, no key and no account. The call is the only one Afnon makes besides the radio, so it keeps the
 * app's habits: never on the main thread, never blocking a draw, and silent when there is no network — the
 * last good reading stays on screen until a newer one arrives. A reading is kept with the city it was fetched for
 * and counts only while that city is still the chosen one.
 */
final class Weather {

    /** How long a reading is still worth showing (30 minutes). */
    private static final long FRESH_MS = 30L * 60L * 1000L;
    /** How long a stale reading is still better than nothing (12 hours). */
    private static final long KEEP_MS = 12L * 60L * 60L * 1000L;

    private static final AtomicBoolean inFlight = new AtomicBoolean(false);
    private static final Handler main = new Handler(Looper.getMainLooper());

    private Weather() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("afnon", Context.MODE_PRIVATE);
    }

    /** What the home screen draws: a line like "27° · Clear · 34° / 22°", or "" when nothing is known yet. */
    static String line(Context c, Text t) {
        SharedPreferences p = prefs(c);
        long at = p.getLong("wx_at", 0L);
        if (at == 0L || System.currentTimeMillis() - at > KEEP_MS) return "";
        // Another city's weather is wrong, not just old: nothing until the new city's arrives.
        if (!Prefs.city(c).id.equals(p.getString("wx_city", ""))) return "";
        int now = p.getInt("wx_now", Integer.MIN_VALUE);
        if (now == Integer.MIN_VALUE) return "";
        int hi = p.getInt("wx_hi", Integer.MIN_VALUE);
        int lo = p.getInt("wx_lo", Integer.MIN_VALUE);
        int code = p.getInt("wx_code", -1);

        StringBuilder b = new StringBuilder();
        b.append(now).append('°');
        String sky = describe(code, t);
        if (!sky.isEmpty()) b.append(" · ").append(sky);
        if (hi != Integer.MIN_VALUE && lo != Integer.MIN_VALUE) {
            b.append(" · ").append(hi).append('°').append(" / ").append(lo).append('°');
        }
        return t.d(b.toString());
    }

    /**
     * Fetch in the background if the last reading has gone stale, then hand the new line back on the main
     * thread. Safe to call on every tick: it does nothing while a call is already out or the reading is fresh.
     * A reading for another city is never fresh, so a new city is fetched at once.
     */
    static void refresh(Context c, final Runnable onUpdated) {
        final Context app = c.getApplicationContext();
        SharedPreferences p = prefs(app);
        boolean same = Prefs.city(app).id.equals(p.getString("wx_city", ""));
        if (same && System.currentTimeMillis() - p.getLong("wx_at", 0L) < FRESH_MS) return;
        if (!inFlight.compareAndSet(false, true)) return;

        new Thread(() -> {
            try {
                Prefs.City city = Prefs.city(app);
                if (city == null) return;
                String url = "https://api.open-meteo.com/v1/forecast"
                        + "?latitude=" + city.lat + "&longitude=" + city.lng
                        + "&current=temperature_2m,weather_code"
                        + "&daily=temperature_2m_max,temperature_2m_min"
                        + "&timezone=auto&forecast_days=1";
                JSONObject o = new JSONObject(get(url));
                JSONObject cur = o.getJSONObject("current");
                JSONObject day = o.getJSONObject("daily");
                prefs(app).edit()
                        .putInt("wx_now", (int) Math.round(cur.getDouble("temperature_2m")))
                        .putInt("wx_code", cur.optInt("weather_code", -1))
                        .putInt("wx_hi", (int) Math.round(day.getJSONArray("temperature_2m_max").getDouble(0)))
                        .putInt("wx_lo", (int) Math.round(day.getJSONArray("temperature_2m_min").getDouble(0)))
                        // The city this call fetched, which may no longer be the chosen one by the time it answers.
                        .putString("wx_city", city.id)
                        .putLong("wx_at", System.currentTimeMillis())
                        .apply();
                if (onUpdated != null) main.post(onUpdated);
            } catch (Exception ignored) {
                // Offline, DNS down, a bad payload: the old reading stays and we try again on the next tick.
            } finally {
                inFlight.set(false);
            }
        }, "afnon-weather").start();
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

    /** WMO weather codes, grouped the way someone glancing at a TV across the room needs them. */
    private static String describe(int code, Text t) {
        if (code < 0) return "";
        if (code == 0) return t.ar ? "صحو" : "Clear";
        if (code <= 2) return t.ar ? "غائم جزئيًا" : "Partly cloudy";
        if (code == 3) return t.ar ? "غائم" : "Cloudy";
        if (code == 45 || code == 48) return t.ar ? "ضباب" : "Fog";
        if (code >= 51 && code <= 57) return t.ar ? "رذاذ" : "Drizzle";
        if (code >= 61 && code <= 67) return t.ar ? "مطر" : "Rain";
        if (code >= 71 && code <= 77) return t.ar ? "ثلج" : "Snow";
        if (code >= 80 && code <= 82) return t.ar ? "زخات مطر" : "Showers";
        if (code >= 85 && code <= 86) return t.ar ? "زخات ثلج" : "Snow showers";
        if (code >= 95) return t.ar ? "عاصفة رعدية" : "Thunderstorm";
        return "";
    }
}
