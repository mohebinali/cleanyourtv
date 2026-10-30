package tv.afnon.home;

import android.accessibilityservice.AccessibilityService;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Home again when an AirPlay sender lets go. The AirPlay receiver (io.github.jqssun.airplay) brings its screen up when
 * a phone connects, and when the phone goes it stays on that screen, "Waiting for connection...", while it listens in
 * the background. It tells no other app, so while its screen is in front this reads the screen every 1.5 s: once it
 * has shown a phone there ("1 connected", "Press start on the sender...", or the mirrored picture filling the screen), the
 * status line going back to "0 connected" means the phone has gone, and this presses Home as the remote's Home button
 * would. Opened by hand with no phone on it, the screen says "0 connected" from the start and is left alone. The
 * receiver keeps listening, so the phone finds the TV next time. Only the receiver's own screen is read (the service's
 * canRetrieveWindowContent, xml/remote_keys.xml), and nothing read is kept.
 */
final class AirPlayReturn {
    static final String AIRPLAY = "io.github.jqssun.airplay";
    private static final long POLL_MS = 1500;
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
    /** One read of the screen: the status line's count (-1 without one), any text at all, the playback wait. */
    private int count;
    private boolean anyText, playbackWait;

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
        handler.postDelayed(poll, POLL_MS);
    }

    void stop() {
        watching = false;
        handler.removeCallbacks(poll);
    }

    private void poll() {
        if (!watching) return;
        AccessibilityNodeInfo root = service.getRootInActiveWindow();
        // Something else in front (another app, Home, the screensaver): the watch ends until the receiver comes back.
        if (root == null || root.getPackageName() == null || !AIRPLAY.contentEquals(root.getPackageName())) {
            stop();
            return;
        }
        count = -1;
        anyText = false;
        playbackWait = false;
        read(root, 0);
        if (count > 0 || playbackWait || !anyText) {
            // A phone is on it: counted, about to play, or its picture filling the screen with no words at all.
            sawSender = true;
        } else if (count == 0 && sawSender) {
            stop();
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            return;
        }
        handler.postDelayed(poll, POLL_MS);
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
