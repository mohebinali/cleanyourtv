package tv.afnon.home;

import android.app.Activity;

/**
 * An invisible screen that ends the screensaver and closes. AirPlayReturn opens it when a phone's AirPlay comes in
 * under the screensaver, which stays on top of every app. Android wakes the screen for it because of
 * android:turnScreenOn in the manifest, the way it does for an alarm clock, so no permission is needed; waking ends
 * the screensaver. It closes as soon as it is up, leaving the AirPlay receiver's screen, which opened under it, in
 * front. It is never opened in standby: with the TV off, AirPlay leaves it off.
 */
public class WakeActivity extends Activity {
    @Override
    protected void onResume() {
        super.onResume();
        finish();
        overridePendingTransition(0, 0);
    }
}
