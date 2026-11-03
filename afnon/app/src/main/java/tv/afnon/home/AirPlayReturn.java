package tv.afnon.home;

import android.accessibilityservice.AccessibilityService;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AirPlay on the TV. The AirPlay receiver (io.github.jqssun.airplay) opens its screen when a phone connects, and when
 * the phone goes it stays on that screen, "Waiting for connection...", while it listens in the background. It tells no
 * other app, so while its screen is in front this reads the screen every second: once it has shown a phone there
 * ("1 connected", "Press start on the sender...", or the mirrored picture), the status line going back to
 * "0 connected" means the phone has gone. The receiver's screen is then closed for good (started over as a no-history
 * screen, which the home going over it finishes) and the normal home opened straight. Closing it matters: the next
 * phone then opens it afresh, which sends a window event even under the screensaver; an old screen brought back there
 * sends none. So a receiver screen left open (by Back or Home, or opened by hand) is closed as well, out of sight,
 * when the screensaver next comes up. The receiver keeps listening throughout.
 *
 * The mirrored picture is told by the receiver's buttons for it ("Exit fullscreen" over the full-screen picture,
 * "Fullscreen" beside a smaller one), or by its size label ("1920x1080") with no "Press start" or "0 connected" beside
 * it: a receiver screen just opened shows the label for any connection, mirroring or not.
 *
 * Where the phone's picture is meant to go:
 * - The TV showing the Chromecast: nothing to do. Opened by hand there, with no phone, the screen is left alone.
 * - The screensaver up (it stays on top of every app): WakeActivity ends it. A short connection that never mirrors
 *   (an iPhone's AirPlay menu may ask a receiver for its details, which opens its screen too) ends with the
 *   screensaver put back.
 * - The TV on another input (HDMI-CEC says another source is active, as the vendor's service reports it): once the
 *   receiver shows the mirrored picture, a Home press, which on Android TV sends HDMI-CEC's One Touch Play, so the TV
 *   switches to the Chromecast; Home also puts up the home screen, so the receiver is brought back over it. Only the
 *   picture counts, so a connection that doesn't mirror never switches the TV. This needs the Chromecast awake while
 *   the TV shows another input (CEC setting power_state_change_on_active_source_lost = none, README); after 30 s
 *   there, anything left playing is paused, as the standby that setting replaced did.
 * - The Chromecast in standby: nothing; a TV that is off stays off. (A TV turned on straight to another input leaves
 *   the Chromecast asleep too, and that can't be told from a TV still off, so it is left alone as well.) A receiver
 *   screen still up when the TV next comes on is watched as one a phone was on, so it gives way to the normal home.
 * A receiver screen brought back under the screensaver sends no window event, so sound is heard for too: media sound
 * that starts under the screensaver and keeps playing a second counts as the receiver opening. Android tells any app
 * which kinds of sound are playing, not whose.
 *
 * Only the receiver's own screen is read (the service's canRetrieveWindowContent, xml/remote_keys.xml); of other
 * apps, only which one is in front. It runs on a thread of its own so nothing else waits for it, and nothing read is
 * kept.
 */
final class AirPlayReturn {
    static final String AIRPLAY = "io.github.jqssun.airplay";
    private static final String TAG = "AirPlayReturn";
    /** The first read soon after the screen comes up, to catch "1 connected" before full screen hides it. */
    private static final long FIRST_READ_MS = 250, POLL_MS = 1000;
    /** Reads in a row with another app in front before the watch ends: focus moving between windows is not leaving. */
    private static final int AWAY_POLLS = 5;
    /** The receiver sends a window event or two as it opens: one wake for them (a new screensaver allows another). */
    private static final long WAKE_AGAIN_MS = 5_000;
    /** After Home, the receiver is brought back once the home screen is up, and again if something covered it. */
    private static final long[] RESTORE_MS = {700, 1600};
    /**
     * The receiver's own window events while its screen is being closed are not a phone. A phone that connects
     * meanwhile is looked for just after, once the closed screen is gone under the home.
     */
    private static final long CLOSING_MS = 2_000, RECHECK_MS = 2_300;
    /** After the screen comes on, its windows can be read, and HDMI-CEC has settled: no switch before then. */
    private static final long AFTER_WAKE_MS = 1_500, SETTLE_MS = 3_000;
    /** The TV on another input this long with something playing: it is paused. */
    private static final long LEFT_MS = 30_000;
    /** Still playing this long after the pause key: the normal home goes over it. */
    private static final long PAUSE_MS = 2_000;
    /** Sound that starts under the screensaver counts if it is still playing this much later. */
    private static final long SOUND_MS = 1_000;
    /** A connection that began under the screensaver and never mirrored puts it back only if it was this short. */
    private static final long SHORT_MS = 10_000;
    /**
     * HDMI-CEC's active source as the vendor's service (com.droidlogic, HdmiCecActiveness) reports it for Netflix:
     * {"activeCecState":"active"} while the TV shows the Chromecast, "inactive" while it shows another input or is off.
     */
    private static final String CEC_STATE = "nrdp_video_platform_capabilities";
    /** Google TV's "go to sleep" voice action, which starts the screensaver (as the normal home's Back does). */
    private static final String START_SCREENSAVER = "com.google.android.pano.action.SLEEP";
    private static final String TV_SETTINGS = "com.android.tv.settings";
    /**
     * The receiver's words that tell a phone is on it (its strings: connected_count "%d connected", the debug
     * overlay's debug_clients "Senders: %d", waiting_for_playback), and the picture's size label ("1920x1080"). Its
     * buttons for the mirrored picture (cd_exit_fullscreen, cd_fullscreen) are there only while a phone mirrors. It
     * has no translations.
     */
    private static final Pattern CONNECTED = Pattern.compile("(\\d{1,4}) connected");
    private static final Pattern SENDERS = Pattern.compile("Senders: (\\d{1,4})");
    private static final Pattern SIZE_LABEL = Pattern.compile("\\d{2,5}x\\d{2,5}");
    private static final String WAITING_FOR_PLAYBACK = "Press start on the sender";
    private static final String EXIT_FULLSCREEN = "Exit fullscreen", FULLSCREEN = "Fullscreen";

    private final AccessibilityService service;
    private final HandlerThread thread = new HandlerThread("airplay-return");
    private final Handler handler;
    private final Runnable poll = this::poll;
    private final Runnable restore = this::restoreReceiver;
    private final Runnable recheck = this::recheck;
    private final Runnable afterWake = this::afterWake;
    private final Runnable tvLeft = this::tvLeft;
    private final Runnable stillPlaying = this::stillPlaying;
    // Everything below is touched on the thread only.
    private boolean watching;
    /** While the receiver has been in front this time, a phone has been on it (or it opened by itself for one). */
    private boolean sawSender;
    /** The mirrored picture has been on the receiver's screen this time: a phone really mirrors. */
    private boolean sawPicture;
    /** This time began under the screensaver; the TV has been switched to the Chromecast for it. */
    private boolean fromScreensaver, switched;
    /** This time began with recheck, so its end doesn't look again. */
    private boolean fromRecheck;
    /** When this time began. */
    private long begunAt;
    /** Reads in a row that found another app in front. */
    private int away;
    /** One read of the screen: the phone count (-1 without one), any words besides the size label, the signs of one. */
    private int count;
    private boolean anyText, playbackWait, sizeLabel, mirrorButton;
    /** The last read, so the log says what changed rather than every second. */
    private String lastRead = "";
    /** The screensaver is up, from Android's broadcasts (delivered on the thread). */
    private boolean dreaming;
    /** The screensaver is to go back up once the receiver's screen has closed (recheck). */
    private boolean screensaverDue;
    /**
     * The receiver's screen has opened since it was last closed here, so it may be open behind other apps. Not known
     * at the start: an earlier version, or the user, may have left it open.
     */
    private boolean receiverLeft = true;
    private long wokeAt = -WAKE_AGAIN_MS, closingUntil, screenOnAt = -SETTLE_MS;
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_ON.equals(action)) {
                screenOnAt = SystemClock.elapsedRealtime();
                handler.removeCallbacks(afterWake);
                handler.postDelayed(afterWake, AFTER_WAKE_MS);
                return;
            }
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                // Standby (the TV turned off): the wait for a TV left on another input is over. A TV turned straight
                // back on wakes the Chromecast with HDMI-CEC still saying "inactive", so that wait running on would
                // pause whatever is then put on.
                handler.removeCallbacks(tvLeft);
                handler.removeCallbacks(stillPlaying);
                return;
            }
            dreaming = Intent.ACTION_DREAMING_STARTED.equals(action);
            handler.removeCallbacks(soundCheck);
            if (dreaming) screensaverStarted();
        }
    };
    /** HDMI-CEC's active source changing: the TV leaving the Chromecast, or coming back to it. */
    private final ContentObserver cecObserver;
    private final AudioManager audio;
    /** Media sound playing: the most players heard since the screensaver came up (radio left on under it, say). */
    private int dreamPlayers;
    private final Runnable soundCheck = this::checkSound;
    private final AudioManager.AudioPlaybackCallback playback = new AudioManager.AudioPlaybackCallback() {
        @Override
        public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs) {
            if (!dreaming || mediaPlayers(configs) <= dreamPlayers) return;
            handler.removeCallbacks(soundCheck);
            handler.postDelayed(soundCheck, SOUND_MS);
        }
    };

    AirPlayReturn(AccessibilityService service) {
        this.service = service;
        thread.start();
        handler = new Handler(thread.getLooper());
        audio = service.getSystemService(AudioManager.class);
        audio.registerAudioPlaybackCallback(playback, handler);
        IntentFilter filter = new IntentFilter(Intent.ACTION_DREAMING_STARTED);
        filter.addAction(Intent.ACTION_DREAMING_STOPPED);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            service.registerReceiver(screen, filter, null, handler, Context.RECEIVER_NOT_EXPORTED);
        } else {
            service.registerReceiver(screen, filter, null, handler);
        }
        cecObserver = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange) {
                handler.removeCallbacks(tvLeft);
                if (!tvShowsUs()) handler.postDelayed(tvLeft, LEFT_MS);
            }
        };
        service.getContentResolver().registerContentObserver(Settings.Global.getUriFor(CEC_STATE), false,
            cecObserver);
    }

    /** The service has just started: the receiver may already be in front, and no window event will come for it. */
    void checkFront() {
        handler.post(() -> {
            if (receiverInFront()) begin(false, false);
        });
    }

    /** A window came up: the receiver's starts the watch, and the phone's picture is brought to the TV. */
    void onWindowChanged(AccessibilityEvent e) {
        if (e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        if (pkg == null || !AIRPLAY.contentEquals(pkg)) return;
        handler.post(this::receiverOpened);
    }

    /** The service is going: the watch and its thread end. */
    void quit() {
        audio.unregisterAudioPlaybackCallback(playback);
        service.getContentResolver().unregisterContentObserver(cecObserver);
        try {
            service.unregisterReceiver(screen);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered.
        }
        handler.removeCallbacksAndMessages(null);
        thread.quitSafely();
    }

    /** The receiver's screen came up, so it is there to be closed later, whatever happens now. */
    private void receiverOpened() {
        // Its own events while it closes; a phone's among them is found by recheck.
        if (SystemClock.elapsedRealtime() < closingUntil) return;
        receiverLeft = true;
        opened("receiver opened");
    }

    /**
     * The receiver's screen opened (or its sound started under the screensaver). In standby the TV is left off. Under
     * the screensaver (or just after a connection that only ended it, which is to put it back), or with the TV on
     * another input, the user can't have opened it: it is for a phone, or for a phone asking, and its "0 connected"
     * then closes it.
     */
    private void opened(String why) {
        if (SystemClock.elapsedRealtime() < closingUntil) return;
        if (!interactive()) {
            Log.i(TAG, why + " in standby: the TV is left off");
            return;
        }
        boolean screensaver = dreaming || screensaverDue;
        if (dreaming) wakeTv(why);
        begin(screensaver || !tvShowsUs(), screensaver);
    }

    /** soundCheck: media sound that started under the screensaver is still playing, the receiver's for a phone. */
    private void checkSound() {
        int playing = mediaPlayers(audio.getActivePlaybackConfigurations());
        if (!dreaming || playing <= dreamPlayers) return;
        dreamPlayers = playing;
        opened("sound started under the screensaver");
    }

    /**
     * The screensaver has come up, and a phone may end it again straight away. A receiver screen left open would be
     * brought back under it with no window event, and a phone mirroring then would go unseen: it is closed now, out
     * of sight, unless a phone is on it.
     */
    private void screensaverStarted() {
        dreamPlayers = mediaPlayers(audio.getActivePlaybackConfigurations());
        wokeAt = -WAKE_AGAIN_MS;
        if (!receiverLeft || (watching && sawSender)) return;
        Log.i(TAG, "screensaver up, the receiver's screen left open: closing it");
        stop();
        closeReceiver();
        goHome();
    }

    /**
     * Players of media sound, not of notifications or the system's own sounds: music and films (USAGE_MEDIA), and the
     * receiver's, which its low_latency setting (on, on this TV) plays as USAGE_GAME.
     */
    private static int mediaPlayers(List<AudioPlaybackConfiguration> configs) {
        int n = 0;
        for (AudioPlaybackConfiguration c : configs) {
            int usage = c.getAudioAttributes().getUsage();
            if (usage == AudioAttributes.USAGE_MEDIA || usage == AudioAttributes.USAGE_GAME) n++;
        }
        return n;
    }

    /**
     * Whether the TV shows the Chromecast, from HDMI-CEC as the vendor's service reports it (CEC_STATE). True where
     * that isn't known, so nothing is switched on a guess. "inactive" can be out of date: a TV that wakes the
     * Chromecast itself (turned on with its own remote, it sends the power key) shows it without making it the active
     * source, so the state stays "inactive" until the next Home press. A switch then is one Home press too many, with
     * the TV already here; taken as showing the Chromecast instead, a TV moved to another input afterwards, which
     * changes nothing in the state, would never be switched back for a phone.
     */
    private boolean tvShowsUs() {
        try {
            String caps = Settings.Global.getString(service.getContentResolver(), CEC_STATE);
            return caps == null || !"inactive".equals(new JSONObject(caps).optString("activeCecState"));
        } catch (JSONException | RuntimeException e) {
            return true;
        }
    }

    /**
     * The TV on another input and the phone's picture up: Home, which on Android TV sends HDMI-CEC's One Touch Play,
     * so the TV switches to the Chromecast. Home also brings up the home screen, so the receiver is brought back.
     */
    private void switchTv() {
        switched = true;
        Log.i(TAG, "phone mirroring, TV on another input: switching it here");
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
        handler.removeCallbacks(restore);
        for (long delay : RESTORE_MS) handler.postDelayed(restore, delay);
    }

    /** restore: the receiver back in front, over the home screen that Home put up. */
    private void restoreReceiver() {
        if (!watching || receiverInFront()) return;
        Intent open = receiverIntent();
        if (open == null) return;
        try {
            service.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not bring the receiver back", e);
        }
    }

    private Intent receiverIntent() {
        PackageManager pm = service.getPackageManager();
        Intent open = pm.getLeanbackLaunchIntentForPackage(AIRPLAY);
        return open != null ? open : pm.getLaunchIntentForPackage(AIRPLAY);
    }

    /** Under the screensaver: WakeActivity ends it. Once for a few seconds. */
    private void wakeTv(String why) {
        long now = SystemClock.elapsedRealtime();
        if (now - wokeAt < WAKE_AGAIN_MS) return;
        wokeAt = now;
        Log.i(TAG, why + " under the screensaver: ending it");
        try {
            service.startActivity(new Intent(service, WakeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not end the screensaver", e);
        }
    }

    /**
     * afterWake: the screen came on. A receiver screen in front now was left from standby (or from a session the TV
     * was turned off in): it is watched as one a phone was on, so "0 connected" gives way to the normal home.
     */
    private void afterWake() {
        if (receiverInFront()) begin(true, false);
    }

    /**
     * tvLeft: the TV has shown another input for LEFT_MS. Google TV used to go to standby then, which paused whatever
     * played; kept awake for AirPlay, it sends the pause key instead, to the app playing, as a remote's would. Only
     * with media playing, never over the receiver or the screensaver, and never in standby.
     */
    private void tvLeft() {
        if (!leftPlaying() || receiverInFront()) return;
        Log.i(TAG, "TV on another input with something playing: pausing it");
        long now = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE, 0));
        handler.removeCallbacks(stillPlaying);
        handler.postDelayed(stillPlaying, PAUSE_MS);
    }

    /** stillPlaying: the pause key didn't stop it, so the normal home goes over it, which does. */
    private void stillPlaying() {
        if (!leftPlaying() || receiverInFront()) return;
        Log.i(TAG, "still playing: normal home over it");
        goHome();
    }

    /** The TV on another input, the Chromecast awake with no screensaver and no watch, and media playing. */
    private boolean leftPlaying() {
        return !tvShowsUs() && !dreaming && interactive() && !watching
            && mediaPlayers(audio.getActivePlaybackConfigurations()) > 0;
    }

    private boolean interactive() {
        PowerManager pm = service.getSystemService(PowerManager.class);
        return pm == null || pm.isInteractive();
    }

    /**
     * The watch begins: forPhone when the receiver opened by itself for a phone (under the screensaver, with the TV
     * on another input, or left from standby); screensaver when it began under the screensaver.
     */
    private void begin(boolean forPhone, boolean screensaver) {
        if (watching) {
            if (forPhone) sawSender = true;
            return;
        }
        watching = true;
        sawSender = forPhone;
        sawPicture = false;
        fromScreensaver = screensaver;
        switched = false;
        fromRecheck = false;
        screensaverDue = false;
        begunAt = SystemClock.elapsedRealtime();
        away = 0;
        lastRead = "";
        Log.i(TAG, "receiver in front: watching" + (forPhone ? " (opened for a phone)" : ""));
        handler.postDelayed(poll, FIRST_READ_MS);
    }

    private void stop() {
        watching = false;
        handler.removeCallbacks(poll);
        handler.removeCallbacks(restore);
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
        sizeLabel = false;
        mirrorButton = false;
        read(root, 0);
        // The phone's picture: the receiver's buttons for it, or its size label with no "Press start" or
        // "0 connected" beside it (a receiver screen just opened shows the label for any connection).
        boolean picture = mirrorButton || (sizeLabel && count != 0 && !playbackWait);
        String now = "count " + count + ", words " + anyText + ", waiting for playback " + playbackWait
            + ", picture " + picture;
        if (!now.equals(lastRead)) {
            lastRead = now;
            Log.i(TAG, now + (sawSender ? " (a phone was on)" : ""));
        }
        if (count > 0 || playbackWait || picture || !anyText) {
            // A phone is on it: counted, about to play, or its picture on the screen.
            sawSender = true;
            if (picture && !sawPicture) {
                sawPicture = true;
                // The phone's picture has just come up and the TV shows another input: it is switched over. Only
                // now, so a TV moved to another input later in the session stays there; and not in the moments after
                // the screen came on, while HDMI-CEC catches up with the TV that woke it.
                if (!switched && !tvShowsUs() && SystemClock.elapsedRealtime() - screenOnAt >= SETTLE_MS) {
                    switchTv();
                }
            }
        } else if (count == 0 && sawSender) {
            endSession();
            return;
        }
        handler.postDelayed(poll, POLL_MS);
    }

    /**
     * The phone has gone: the receiver's screen closed for good and the normal home opened straight. A short
     * connection that only ended the screensaver, with no picture ever on the receiver, puts it back once the screen
     * has closed, unless a phone opened the receiver again meanwhile (recheck).
     */
    private void endSession() {
        boolean screensaver = fromScreensaver && !sawPicture
            && SystemClock.elapsedRealtime() - begunAt < SHORT_MS;
        boolean lookAgain = !fromRecheck;
        Log.i(TAG, "phone gone: normal home" + (screensaver ? ", then the screensaver" : ""));
        stop();
        closeReceiver();
        goHome();
        if (!lookAgain) {
            if (screensaver) startScreensaver();
            return;
        }
        screensaverDue = screensaver;
        handler.removeCallbacks(recheck);
        handler.postDelayed(recheck, RECHECK_MS);
    }

    /**
     * recheck, once the receiver's closed screen is gone under the home: a receiver in front now was opened afresh by
     * a phone that connected meanwhile, its window event lost among the closing screen's, and is watched as one.
     * Otherwise the screensaver goes back up if it is due.
     */
    private void recheck() {
        boolean screensaver = screensaverDue;
        screensaverDue = false;
        if (watching || !interactive()) return;
        if (receiverInFront()) {
            Log.i(TAG, "receiver opened again as its screen closed");
            receiverLeft = true;
            begin(true, screensaver);
            fromRecheck = true;
        } else if (screensaver && !dreaming) {
            startScreensaver();
        }
    }

    /**
     * The receiver's screen closed for good: started over as a no-history screen, which the home going over it then
     * finishes, so the next phone opens it afresh. Its window events while that happens are not a phone.
     */
    private void closeReceiver() {
        Intent open = receiverIntent();
        if (open == null) return;
        closingUntil = SystemClock.elapsedRealtime() + CLOSING_MS;
        try {
            service.startActivity(open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK
                | Intent.FLAG_ACTIVITY_NO_HISTORY | Intent.FLAG_ACTIVITY_NO_ANIMATION));
            receiverLeft = false;
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not close the receiver's screen", e);
        }
    }

    /**
     * The normal home, opened straight (as HomeTakeover does): a Home press would also send One Touch Play, switching
     * the TV back from another input it was moved to meanwhile, or switching it on. Home only where there is none,
     * and only where it switches nothing.
     */
    private void goHome() {
        try {
            service.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(HomeTakeover.NORMAL_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            if (tvShowsUs() && interactive() && !dreaming) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            } else {
                Log.w(TAG, "could not open the normal home", e);
            }
        }
    }

    /** The screensaver again, as the normal home's Back starts it (TV Settings' "go to sleep" voice action). */
    private void startScreensaver() {
        try {
            service.startActivity(new Intent(START_SCREENSAVER).setPackage(TV_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Log.w(TAG, "could not start the screensaver", e);
        }
    }

    /** Whether the receiver is the app in front, looked at afresh. */
    private boolean receiverInFront() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) service.clearCache();
        return isReceiver(frontApp());
    }

    private static boolean isReceiver(AccessibilityNodeInfo root) {
        return root != null && root.getPackageName() != null && AIRPLAY.contentEquals(root.getPackageName());
    }

    /**
     * The root of the app in front: the top full-screen application window (the windows come top first, and a
     * picture-in-picture one floats over the app in front), whichever window holds focus; the active window when the
     * window list is not to be had. On Android 13 and later only the root comes, not the screen under it, so another
     * app's screen is never fetched; the receiver's is read from it node by node (read).
     */
    private AccessibilityNodeInfo frontApp() {
        for (AccessibilityWindowInfo w : service.getWindows()) {
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION || w.isInPictureInPictureMode()) continue;
            AccessibilityNodeInfo root = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? w.getRoot(0) : w.getRoot();
            if (root != null) return root;
        }
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            ? service.getRootInActiveWindow(0) : service.getRootInActiveWindow();
    }

    private void read(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 16) return;
        // Before Android 13 there is no clearCache: each node is fetched afresh instead, and one gone is skipped.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && !node.refresh()) return;
        CharSequence text = node.getText();
        if (text != null && text.length() > 0) {
            String s = text.toString().trim();
            if (SIZE_LABEL.matcher(s).matches()) {
                sizeLabel = true;
            } else {
                anyText = true;
                Matcher m = CONNECTED.matcher(s);
                if (!m.matches()) m = SENDERS.matcher(s);
                if (count < 0 && m.matches()) count = Integer.parseInt(m.group(1));
                if (s.startsWith(WAITING_FOR_PLAYBACK)) playbackWait = true;
            }
        }
        CharSequence label = node.getContentDescription();
        if (label != null && (EXIT_FULLSCREEN.contentEquals(label) || FULLSCREEN.contentEquals(label))) {
            mirrorButton = true;
        }
        for (int i = 0; i < node.getChildCount(); i++) read(node.getChild(i), depth + 1);
    }
}
