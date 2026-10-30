package tv.afnon.home;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Home again when an AirPlay sender lets go. The AirPlay receiver (io.github.jqssun.airplay) brings its screen up when
 * a phone connects, and when the phone goes it stays on that screen, "Waiting for connection...", while it listens in
 * the background. It tells no other app, so while its screen is in front this reads the screen every second: once it
 * has shown a phone there ("1 connected", "Press start on the sender...", or the mirrored picture filling the screen
 * with no words at all), the status line going back to "0 connected" means the phone has gone, and this presses Home
 * as the remote's Home button would. Opened by hand with no phone on it, the screen says "0 connected" from the start
 * and is left alone. The receiver keeps listening, so the phone finds the TV next time. Only the receiver's own screen
 * is read (the service's canRetrieveWindowContent, xml/remote_keys.xml), and nothing read is kept.
 */
final class AirPlayReturn {
    static final String AIRPLAY = "io.github.jqssun.airplay";
    private static final String TAG = "AirPlayReturn";
    private static final long POLL_MS = 1000;
    /** Reads in a row with another app in front before the watch ends: focus moving between windows is not leaving. */
    private static final int AWAY_POLLS = 5;
    /**
     * The receiver's status line and its line while a phone is connected but not yet playing (its R.string
     * connected_count, "%d connected", and waiting_for_playback; it has no translations).
     */
    private static final Pattern CONNECTED = Pattern.compile("(\\d{1,4}) connected");
    private static final String WAITING_FOR_PLAYBACK = "Press start on the sender";

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = this::poll;
    private boolean watching;
    /** While the receiver has been in front this time, a phone has been on it. */
    private boolean sawSender;
    /** Reads in a row that found another app in front. */
    private int away;
    /** One read of the screen: the status line's count (-1 without one), any text at all, the playback wait. */
    private int count;
    private boolean anyText, playbackWait;
    /** The last read, so the log says what changed rather than every second. */
    private String lastRead = "";

    AirPlayReturn(AccessibilityService service) {
        this.service = service;
    }

    /** A window came to the front: the receiver's starts the watch. */
    void onWindowChanged(AccessibilityEvent e) {
        if (watching || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null || !AIRPLAY.contentEquals(pkg)) return;
        watching = true;
        sawSender = false;
        away = 0;
        lastRead = "";
        Log.i(TAG, "receiver in front: watching");
        handler.postDelayed(poll, POLL_MS);
    }

    void stop() {
        watching = false;
        handler.removeCallbacks(poll);
    }

    private void poll() {
        if (!watching) return;
        AccessibilityNodeInfo root = frontApp();
        CharSequence pkg = root == null ? null : root.getPackageName();
        if (pkg == null || !AIRPLAY.contentEquals(pkg)) {
            // Another app in front, or none readable for a moment: only several reads in a row end the watch.
            if (++away >= AWAY_POLLS) {
                Log.i(TAG, "receiver gone from the front (" + pkg + "): watch ends");
                stop();
                return;
            }
            handler.postDelayed(poll, POLL_MS);
            return;
        }
        away = 0;
        count = -1;
        anyText = false;
        playbackWait = false;
        read(root, 0);
        String now = "count " + count + ", text " + anyText + ", waiting for playback " + playbackWait;
        if (!now.equals(lastRead)) {
            lastRead = now;
            Log.i(TAG, now + (sawSender ? " (a phone was on)" : ""));
        }
        if (count > 0 || playbackWait || !anyText) {
            // A phone is on it: counted, about to play, or its picture filling the screen with no words at all.
            sawSender = true;
        } else if (count == 0 && sawSender) {
            Log.i(TAG, "phone gone: Home");
            stop();
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            return;
        }
        handler.postDelayed(poll, POLL_MS);
    }

    /**
     * The root of the app in front: the top application window (the windows come top first), whichever window holds
     * focus; the active window when the window list is not to be had.
     */
    private AccessibilityNodeInfo frontApp() {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null) return root;
            }
        }
        return service.getRootInActiveWindow();
    }

    private void read(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 16) return;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            anyText = true;
            String s = text.toString().trim();
            Matcher m = CONNECTED.matcher(s);
            if (count < 0 && m.matches()) count = Integer.parseInt(m.group(1));
            if (s.startsWith(WAITING_FOR_PLAYBACK)) playbackWait = true;
        }
        for (int i = 0; i < node.getChildCount(); i++) read(node.getChild(i), depth + 1);
    }
}
