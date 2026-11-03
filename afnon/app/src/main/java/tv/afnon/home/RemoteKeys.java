package tv.afnon.home;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * The Prayer app's always-on part (its name is from when it also mapped remote buttons; the TV has it turned on by
 * this name). Android keeps an enabled accessibility service running whatever is on screen, so it hosts what has to
 * run all the time: {@link PrayerWatch}, the card when a prayer's time begins; {@link HomeTakeover}, which keeps Home
 * on the normal home where Google TV sends it to its own launcher; and {@link AirPlayReturn}, which brings an AirPlay
 * phone's picture to the TV (ending the screensaver, or switching the TV over from another input) and goes Home when
 * the phone leaves. It filters no keys and reads no app's screen but the AirPlay receiver's, and the app has no
 * network access. Turned on with:
 * settings put secure enabled_accessibility_services tv.afnon.home/.RemoteKeys
 */
public class RemoteKeys extends AccessibilityService {
    /** The running watch, for the adb test hook; null while the service is off. */
    static PrayerWatch watch;
    private HomeTakeover takeover;
    private AirPlayReturn airplay;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        if (watch != null) watch.stop();
        watch = new PrayerWatch(this);
        watch.start();
        if (takeover != null) takeover.stop();
        takeover = new HomeTakeover(this);
        takeover.start();
        if (airplay != null) airplay.quit();
        airplay = new AirPlayReturn(this);
        airplay.checkFront();
    }

    @Override
    public void onDestroy() {
        if (watch != null) watch.stop();
        watch = null;
        if (takeover != null) takeover.stop();
        takeover = null;
        if (airplay != null) airplay.quit();
        airplay = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (takeover != null) takeover.onWindowChanged(event);
        if (airplay != null) airplay.onWindowChanged(event);
    }

    @Override
    public void onInterrupt() {
    }
}
