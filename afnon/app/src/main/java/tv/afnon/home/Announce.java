package tv.afnon.home;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.MediaPlayer;

import java.io.IOException;

/**
 * Short spoken lines in assets/announce: «اقترب موعد صلاة …» with the reminder (Settings → Reminders → Spoken
 * reminder), and «حان الآن موعد صلاة …» when a prayer's time begins with its adhan off (Announce the time). The
 * clips are recorded on the maintainer's PC by tools/fetch-announce.sh and never committed; a missing one plays
 * nothing. Whatever is playing is lowered for the three seconds, not paused.
 */
final class Announce {
    private Announce() {}

    /** Clip names by prayer index; Sunrise has none. On Friday, Dhuhr is "jumuah". */
    private static final String[] NAMES = {"fajr", null, "dhuhr", "asr", "maghrib", "isha"};

    private static MediaPlayer player;
    private static AudioFocusRequest focus;

    /** «اقترب موعد صلاة …»: before_&lt;prayer&gt;.mp3. */
    static void before(Context c, int prayer, boolean friday) {
        play(c, "before_", prayer, friday);
    }

    /** «حان الآن موعد صلاة …»: now_&lt;prayer&gt;.mp3. */
    static void now(Context c, int prayer, boolean friday) {
        play(c, "now_", prayer, friday);
    }

    private static void play(Context c, String kind, int prayer, boolean friday) {
        if (prayer < 0 || prayer >= NAMES.length || NAMES[prayer] == null) return;
        String name = friday && prayer == PrayerDay.DHUHR ? "jumuah" : NAMES[prayer];
        Context app = c.getApplicationContext();
        stop(app);
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
        MediaPlayer mp = new MediaPlayer();
        try (AssetFileDescriptor fd = app.getAssets().openFd("announce/" + kind + name + ".mp3")) {
            mp.setAudioAttributes(attrs);
            mp.setDataSource(fd.getFileDescriptor(), fd.getStartOffset(), fd.getLength());
            mp.prepare();
        } catch (IOException | RuntimeException e) {
            // Not recorded on this build (tools/fetch-announce.sh), or unreadable: say nothing.
            mp.release();
            return;
        }
        // May-duck: the video or radio underneath gets quieter for a moment instead of stopping.
        focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attrs)
            .build();
        AudioManager am = app.getSystemService(AudioManager.class);
        if (am != null) am.requestAudioFocus(focus);
        mp.setOnCompletionListener(p -> stop(app));
        player = mp;
        mp.start();
    }

    /** Ends a line that is still speaking and gives the sound back. */
    static void stop(Context c) {
        if (player != null) {
            Adhans.stop(player);
            player = null;
        }
        if (focus != null) {
            AudioManager am = c.getSystemService(AudioManager.class);
            if (am != null) am.abandonAudioFocusRequest(focus);
            focus = null;
        }
    }
}
