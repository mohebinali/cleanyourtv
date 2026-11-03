package tv.afnon.home;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;

/**
 * The Prayer app's always-on part (its name is from when it also mapped remote buttons; the TV has it turned on by
 * this name). Android keeps an enabled accessibility service running whatever is on screen, so it hosts what has to
 * run all the time: {@link PrayerWatch}, the card when a prayer's time begins; {@link HomeTakeover}, which keeps Home
 * on the normal home where Google TV sends it to its own launcher; {@link AirPlayReturn}, which brings an AirPlay
 * phone's picture to the TV (ending the screensaver, or switching the TV over from another input) and goes Home when
 * the phone leaves; and {@link TvPower}, which keeps the Chromecast awake and off Wi-Fi while the TV is off, so it is
 * there the moment the TV comes back and no phone sees it meanwhile. It filters no keys and reads no app's screen but
 * the AirPlay receiver's (focus moving is noticed, not what has it), and the app has no network access. Turned on with:
 * settings put secure enabled_accessibility_services tv.afnon.home/.RemoteKeys
 */
public class RemoteKeys extends AccessibilityService {
    /** The running watch and TV power, for the adb test hooks; null while the service is off. */
    static PrayerWatch watch;
    static TvPower tvPower;
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
        if (tvPower != null) tvPower.quit();
        tvPower = new TvPower(this);
        if (airplay != null) airplay.quit();
        airplay = new AirPlayReturn(this, tvPower);
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
        if (tvPower != null) tvPower.quit();
        tvPower = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
            if (tvPower != null) tvPower.onFocus();
            return;
        }
        if (takeover != null) takeover.onWindowChanged(event);
        if (airplay != null) airplay.onWindowChanged(event);
    }

    @Override
    public void onInterrupt() {
    }
}
