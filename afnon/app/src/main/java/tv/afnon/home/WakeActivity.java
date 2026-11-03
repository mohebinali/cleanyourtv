package tv.afnon.home;

import android.app.Activity;

/**
 * An invisible screen that wakes the Chromecast and closes. Android wakes the screen for it because of
 * android:turnScreenOn in the manifest, the way it does for an alarm clock, so no permission is needed. AirPlayReturn
 * opens it when a phone's AirPlay comes in under the screensaver, which stays on top of every app (waking ends the
 * screensaver; the receiver's screen, which opened under it, is left in front). TvPower opens it to wake the Chromecast
 * from standby while the TV is off (with HDMI-CEC power_control_mode = none that wake leaves the TV off), and to end
 * the screensaver as the TV comes back on.
 */
public class WakeActivity extends Activity {
    @Override
    protected void onResume() {
        super.onResume();
        finish();
        overridePendingTransition(0, 0);
    }
}
