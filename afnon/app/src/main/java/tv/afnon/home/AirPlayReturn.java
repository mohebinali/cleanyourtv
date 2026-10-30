package tv.afnon.home;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
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
 * has shown a phone there ("1 connected", "Press start on the sender...", or the mirrored picture: its size label,
 * its "Exit fullscreen" button, or no words at all), the status line going back to "0 connected" means the phone has
 * gone, and this presses Home as the remote's Home button would. Opened by hand with no phone on it, the screen says
 * "0 connected" from the start and is left alone. The receiver keeps listening, so the phone finds the TV next time.
 * Only the receiver's own screen is read (the service's canRetrieveWindowContent, xml/remote_keys.xml), on a thread of
 * its own so the remote's keys never wait for it, and nothing read is kept.
 */
final class AirPlayReturn {
    static final String AIRPLAY = "io.github.jqssun.airplay";
    private static final String TAG = "AirPlayReturn";
    /** The first read soon after the screen comes up, to catch "1 connected" before full screen hides it. */
    private static final long FIRST_READ_MS = 250, POLL_MS = 1000;
    /** Reads in a row with another app in front before the watch ends: focus moving between windows is not leaving. */
    private static final int AWAY_POLLS = 5;
    /**
     * The receiver's words that tell a phone is on it (its strings: connected_count "%d connected", the debug
     * overlay's debug_clients "Senders: %d", waiting_for_playback, cd_exit_fullscreen), and the mirrored picture's
     * size label ("1920x1080"), shown only while a phone is connected. It has no translations.
     */
    private static final Pattern CONNECTED = Pattern.compile("(\\d{1,4}) connected");
    private static final Pattern SENDERS = Pattern.compile("Senders: (\\d{1,4})");
    private static final Pattern SIZE_LABEL = Pattern.compile("\\d{2,5}x\\d{2,5}");
    private static final String WAITING_FOR_PLAYBACK = "Press start on the sender", EXIT_FULLSCREEN = "Exit fullscreen";

    private final AccessibilityService service;
    private final HandlerThread thread = new HandlerThread("airplay-return");
    private final Handler handler;
    private final Runnable poll = this::poll;
    // Everything below is touched on the thread only.
    private boolean watching;
    /** While the receiver has been in front this time, a phone has been on it. */
    private boolean sawSender;
    /** Reads in a row that found another app in front. */
    private int away;
    /** One read of the screen: the phone count (-1 without one), any words besides the size label, the signs of one. */
    private int count;
    private boolean anyText, playbackWait, mirror;
    /** The last read, so the log says what changed rather than every second. */
    private String lastRead = "";

    AirPlayReturn(AccessibilityService service) {
        this.service = service;
        thread.start();
        handler = new Handler(thread.getLooper());
    }

    /** The service has just started: the receiver may already be in front, and no window event will come for it. */
    void checkFront() {
        handler.post(() -> {
            if (isReceiver(frontApp())) begin();
        });
    }

    /** A window came to the front: the receiver's starts the watch. */
    void onWindowChanged(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null || !AIRPLAY.contentEquals(pkg)) return;
        handler.post(this::begin);
    }

    /** The service is going: the watch and its thread end. */
    void quit() {
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    private void begin() {
        if (watching) return;
        watching = true;
        sawSender = false;
        away = 0;
        lastRead = "";
        Log.i(TAG, "receiver in front: watching");
        handler.postDelayed(poll, FIRST_READ_MS);
    }

    private void stop() {
        watching = false;
        handler.removeCallbacks(poll);
    }

    private void poll() {
        if (!watching) return;
        // The service hears no content events, so its copy of the screen never learns that "1 connected" became
        // "0 connected" (0.17 and 0.18 kept reading their first copy): read the screen afresh.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) service.clearCache();
        AccessibilityNodeInfo root = frontApp();
        if (!isReceiver(root)) {
            // Another app in front, or none readable for a moment: only several reads in a row end the watch.
            if (++away >= AWAY_POLLS) {
                Log.i(TAG, "receiver gone from the front (" + (root == null ? null : root.getPackageName())
                    + "): watch ends");
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
        mirror = false;
        read(root, 0);
        String now = "count " + count + ", words " + anyText + ", waiting for playback " + playbackWait
            + ", picture " + mirror;
        if (!now.equals(lastRead)) {
            lastRead = now;
            Log.i(TAG, now + (sawSender ? " (a phone was on)" : ""));
        }
        if (count > 0 || playbackWait || mirror || !anyText) {
            // A phone is on it: counted, about to play, or its picture on the screen.
            sawSender = true;
        } else if (count == 0 && sawSender) {
            Log.i(TAG, "phone gone: Home");
            stop();
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            return;
        }
        handler.postDelayed(poll, POLL_MS);
    }

    private static boolean isReceiver(AccessibilityNodeInfo root) {
        return root != null && root.getPackageName() != null && AIRPLAY.contentEquals(root.getPackageName());
    }

    /**
     * The root of the app in front: the top full-screen application window (the windows come top first, and a
     * picture-in-picture one floats over the app in front), whichever window holds focus; the active window when the
     * window list is not to be had. On Android 13 and later the whole screen comes in one request.
     */
    private AccessibilityNodeInfo frontApp() {
        List<AccessibilityWindowInfo> windows = service.getWindows();
        for (AccessibilityWindowInfo w : windows) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || w.isInPictureInPictureMode()) continue;
            AccessibilityNodeInfo root = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? w.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID) : w.getRoot();
            if (root != null) return root;
        }
        return service.getRootInActiveWindow();
    }

    private void read(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 16) return;
        // Before Android 13 there is no clearCache: each node is fetched afresh instead, and one gone is skipped.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !node.refresh()) return;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            String s = text.toString().trim();
            if (SIZE_LABEL.matcher(s).matches()) {
                mirror = true;
            } else {
                anyText = true;
                Matcher m = CONNECTED.matcher(s);
                if (!m.matches()) m = SENDERS.matcher(s);
                if (count < 0 && m.matches()) count = Integer.parseInt(m.group(1));
                if (s.startsWith(WAITING_FOR_PLAYBACK)) playbackWait = true;
            }
        }
        CharSequence label = node.getContentDescription();
        if (label != null && EXIT_FULLSCREEN.contentEquals(label)) mirror = true;
        for (int i = 0; i < node.getChildCount(); i++) read(node.getChild(i), depth + 1);
    }
}
