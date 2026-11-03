package tv.afnon.home;

import android.app.Activity;

/**
 * An invisible screen that wakes the TV and closes. AirPlayReturn opens it when a phone's AirPlay comes in while the
 * TV can't show it: under the screensaver, which waking ends, or in standby, where the wake switches the TV on over
 * HDMI-CEC as the remote does. Android wakes the screen for it because of android:turnScreenOn in the manifest, the
 * way it does for an alarm clock, so no permission is needed. It closes as soon as it is up, leaving the AirPlay
 * receiver's screen, which opened under it, in front.
 */
public class WakeActivity extends Activity {
    @Override
    protected void onResume() {
        super.onResume();
        finish();
        overridePendingTransition(0, 0);
    }
}
