package tv.afnon.home;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;

/**
 * Quran radio, streamed live: Cairo's إذاعة القرآن الكريم (98.2 FM) by default, or another station from
 * {@link #STATIONS} (Settings → Radio). One player for the whole app: Home's pill and card start and stop it, the
 * screensaver shows it, and it keeps playing while Afnon is in the background.
 *
 * <p>Sound: it takes the audio focus, so another app playing sound stops it; the call to prayer takes the focus
 * for a while, which pauses it, and it comes back afterwards (Settings → Radio). A dropped stream reconnects.
 *
 * <p>Standby: while it plays, the TV's "put to sleep" timer is turned off, so the screensaver can run (and dim
 * the screen) without the TV switching itself off mid-recitation; the timer is put back when the radio stops.
 *
 * <p>It never reads the sound it plays: Android's Visualizer would need the microphone permission, which Afnon does
 * not ask for, so the level bars breathe instead of following the voice.
 */
final class Radio {
    static final class Station {
        final String id, ar, en, whereAr, whereEn, url;
        /** A reciter's around-the-clock channel rather than a broadcast station. */
        final boolean reciter;

        Station(String id, String ar, String en, String whereAr, String whereEn, String url, boolean reciter) {
            this.id = id;
            this.ar = ar;
            this.en = en;
            this.whereAr = whereAr;
            this.whereEn = whereEn;
            this.url = url;
            this.reciter = reciter;
        }

        static Station reciter(String id, String ar, String en) {
            return new Station(id, ar, en, "تلاوة على مدار الساعة", "Recitation, around the clock",
                "https://backup.qurango.net/radio/" + id, true);
        }
    }

    private static final String QURAN_AR = "إذاعة القرآن الكريم", QURAN_EN = "Holy Quran Radio";

    /**
     * Each station's own stream, the one its website or official player uses. All https except radiojar, which
     * redirects to plain http (allowed in network_security.xml). Two are HLS (Qatar, Jordan); the rest MP3 or AAC.
     * Reciters are mp3quran.net's 24/7 channels (its API: mp3quran.net/api/v3/radios), MP3 at 128 kbps.
     */
    static final Station[] STATIONS = {
        new Station("cairo", QURAN_AR, QURAN_EN, "القاهرة · 98٫2 إف إم", "Cairo · 98.2 FM",
            "https://stream.radiojar.com/8s5u5tpdtwzuv", false),
        new Station("nablus", QURAN_AR, QURAN_EN, "نابلس", "Nablus", "https://quran-radio.org:8899/;", false),
        new Station("shabab", "شباب إف إم · قرآن", "Shabab FM Quran", "نابلس", "Nablus",
            "https://streaming.zaytonatube.com/ShababFM.aac", false),
        new Station("qatar", QURAN_AR, QURAN_EN, "قطر", "Qatar",
            "https://live.kwikmotion.com/qmcquranradiolive/quranradio/playlist.m3u8", false),
        new Station("qatar2", QURAN_AR + " 2", QURAN_EN + " 2", "قطر", "Qatar",
            "https://live.kwikmotion.com/qmcquranradio2live/quranradio2/playlist.m3u8", false),
        new Station("jordan", QURAN_AR, QURAN_EN, "الأردن", "Jordan",
            "https://jrtv-live.ercdn.net/jrradio/quranradio.m3u8", false),
        // Not Sharjah: its audio stream is raw ADTS AAC, which MediaPlayer's AACExtractor scans to the end before
        // playing, so a live one never starts; its official HLS is 1080p video.
        Station.reciter("mishary_alafasi", "مشاري العفاسي", "Mishary Alafasy"),
        Station.reciter("abdulbasit_abdulsamad", "عبد الباسط عبد الصمد", "Abdul Basit Abdul Samad"),
        Station.reciter("mohammed_siddiq_alminshawi", "محمد صديق المنشاوي", "Mohamed Siddiq Al-Minshawi"),
        Station.reciter("mahmoud_khalil_alhussary", "محمود خليل الحصري", "Mahmoud Khalil Al-Husary"),
        Station.reciter("maher", "ماهر المعيقلي", "Maher Al-Muaiqly"),
        Station.reciter("saad_alghamdi", "سعد الغامدي", "Saad Al-Ghamdi"),
        Station.reciter("abdulrahman_alsudaes", "عبد الرحمن السديس", "Abdul Rahman Al-Sudais"),
        Station.reciter("yasser_aldosari", "ياسر الدوسري", "Yasser Al-Dosari"),
        Station.reciter("nasser_alqatami", "ناصر القطامي", "Nasser Al-Qatami"),
        Station.reciter("mohammad_rashad_alshareef", "محمد رشاد الشريف", "Mohammad Rashad Al-Shareef"),
    };

    static int stationIndex(Context c) {
        String id = Prefs.of(c).getString("station", "cairo");
        for (int i = 0; i < STATIONS.length; i++) if (STATIONS[i].id.equals(id)) return i;
        return 0;
    }

    static Station station(Context c) {
        return STATIONS[stationIndex(c)];
    }

    /** Picks another station; if the radio is on, it moves there at once (after the adhan, if paused for it). */
    static void switchTo(Context c, int index) {
        Prefs.of(c).edit().putString("station", STATIONS[index].id).apply();
        if (wanted && state != PAUSED) {
            failures = 0;
            connect();
        }
    }

    static final int OFF = 0, CONNECTING = 1, PLAYING = 2, RETRYING = 3, PAUSED = 4;
    static final int BANDS = 5;

    interface Listener {
        void onRadio(int state);
    }

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners = new ArrayList<>();
    private static Context app;
    private static MediaPlayer player;
    private static AudioFocusRequest focus;
    private static WifiManager.WifiLock wifiLock;
    private static int state = OFF, failures;
    /** The listener asked for it; stays true through reconnects and the adhan's pause. */
    private static boolean wanted;

    private static final Runnable reconnect = Radio::connect;

    private Radio() {}

    static int state() {
        return state;
    }

    /** On as far as the listener is concerned: playing, connecting, retrying or paused for the adhan. */
    static boolean on() {
        return wanted;
    }

    static void toggle(Context c) {
        if (wanted) stop();
        else start(c);
    }

    static void start(Context c) {
        app = c.getApplicationContext();
        if (wanted) return;
        wanted = true;
        failures = 0;
        keepAwake(true);
        AudioManager am = app.getSystemService(AudioManager.class);
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes())
            .setOnAudioFocusChangeListener(Radio::onFocus, handler)
            .build();
        if (am != null) am.requestAudioFocus(focus);
        connect();
    }

    static void stop() {
        wanted = false;
        release();
        if (app != null) {
            AudioManager am = app.getSystemService(AudioManager.class);
            if (am != null && focus != null) am.abandonAudioFocusRequest(focus);
            keepAwake(false);
        }
        focus = null;
        set(OFF);
    }

    static void listen(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    static void unlisten(Listener l) {
        listeners.remove(l);
    }

    // ---------------------------------------------------------------- playing

    private static void connect() {
        handler.removeCallbacks(reconnect);
        if (!wanted || app == null) return;
        release();
        set(failures == 0 ? CONNECTING : RETRYING);
        MediaPlayer mp = new MediaPlayer();
        player = mp;
        mp.setAudioAttributes(attributes());
        mp.setWakeMode(app, PowerManager.PARTIAL_WAKE_LOCK);
        mp.setOnPreparedListener(p -> {
            if (p != player) return;
            failures = 0;
            p.start();
            set(PLAYING);
        });
        mp.setOnErrorListener((p, what, extra) -> {
            if (p == player) retry();
            return true;
        });
        // A live stream never completes; if it does, the connection dropped.
        mp.setOnCompletionListener(p -> { if (p == player) retry(); });
        try {
            mp.setDataSource(station(app).url);
            mp.prepareAsync();
        } catch (Exception e) {
            retry();
        }
    }

    /** Waits a little longer each time (3 s, 6 s … up to 30 s) and tries again, for as long as it is wanted. */
    private static void retry() {
        release();
        if (!wanted) return;
        failures++;
        set(RETRYING);
        handler.postDelayed(reconnect, Math.min(30_000L, 3_000L * failures));
    }

    private static void release() {
        handler.removeCallbacks(reconnect);
        if (player != null) {
            MediaPlayer p = player;
            player = null;
            // reset() waits for a stream that is still connecting, up to several seconds, so it runs off the main
            // thread. Its callbacks are ignored: they check p == player.
            new Thread(() -> {
                try {
                    p.reset();
                    p.release();
                } catch (RuntimeException ignored) {
                    // Already gone.
                }
            }, "radio-release").start();
        }
    }

    private static AudioAttributes attributes() {
        return new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    }

    private static void onFocus(int change) {
        switch (change) {
            case AudioManager.AUDIOFOCUS_LOSS:
                // Another app is playing sound now (a video, music): the radio steps aside for good.
                stop();
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                // The call to prayer. A live stream can't pause, so drop it and reconnect afterwards.
                if (wanted) {
                    release();
                    set(PAUSED);
                }
                break;
            case AudioManager.AUDIOFOCUS_GAIN:
                if (state == PAUSED) {
                    if (Prefs.radioResume(app)) {
                        failures = 0;
                        connect();
                    } else {
                        stop();
                    }
                }
                break;
            default:
                break;
        }
    }

    private static void set(int s) {
        state = s;
        for (Listener l : new ArrayList<>(listeners)) l.onRadio(s);
    }

    // ---------------------------------------------------------------- keeping the TV on

    /**
     * While the radio plays, the TV must not switch itself off: the "put to sleep" timer (secure sleep_timeout)
     * is set to never, and put back afterwards. The screensaver still starts as usual. Also keeps Wi-Fi awake.
     */
    private static void keepAwake(boolean on) {
        if (app == null) return;
        try {
            if (on) {
                int now = Settings.Secure.getInt(app.getContentResolver(), "sleep_timeout", -1);
                if (now != -1) {
                    Prefs.of(app).edit().putInt("sleep_saved", now).apply();
                    Settings.Secure.putInt(app.getContentResolver(), "sleep_timeout", -1);
                }
            } else {
                restoreSleep(app);
            }
        } catch (SecurityException ignored) {
            // No WRITE_SECURE_SETTINGS grant: the TV's own timer stays, and may stop a long session.
        }
        WifiManager wm = app.getSystemService(WifiManager.class);
        if (on && wm != null && wifiLock == null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "afnon:radio");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
        } else if (!on && wifiLock != null) {
            wifiLock.release();
            wifiLock = null;
        }
    }

    /** Puts the TV's sleep timer back if the radio left it off (also on start, after a crash or reboot). */
    static void restoreSleep(Context c) {
        if (wanted) return;
        int saved = Prefs.of(c).getInt("sleep_saved", Integer.MIN_VALUE);
        if (saved == Integer.MIN_VALUE) return;
        try {
            Settings.Secure.putInt(c.getContentResolver(), "sleep_timeout", saved);
            Prefs.of(c).edit().remove("sleep_saved").apply();
        } catch (SecurityException ignored) {
            // No grant; nothing was changed either.
        }
    }
}
