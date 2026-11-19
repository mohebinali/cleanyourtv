package tv.afnon.home;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A short diary of what the always-on service saw and did: the TV going off and coming on, HDMI-CEC refused, the Cast
 * receiver held, wireless debugging put back. It is kept in the app's files, so it outlasts a restart, which Android's
 * log and its HDMI-CEC history don't: what came before a fault was twice lost to the restart meant to cure it. Read
 * with: adb shell run-as tv.afnon.home cat files/events.log
 */
final class Events {
    private static final String FILE = "events.log";
    /** The diary is started afresh past this size, the one before kept beside it (events.log.1). */
    private static final long MAX_BYTES = 96 * 1024;

    private Events() {}

    static synchronized void log(Context c, String tag, String what) {
        Log.i(tag, what);
        File f = new File(c.getFilesDir(), FILE);
        if (f.length() > MAX_BYTES) {
            File old = new File(c.getFilesDir(), FILE + ".1");
            old.delete();
            f.renameTo(old);
        }
        try (FileWriter w = new FileWriter(f, true)) {
            w.write(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()) + " " + tag + ": "
                + what + "\n");
        } catch (IOException | RuntimeException e) {
            // The diary is a help in finding faults, never a reason for one.
        }
    }
}
