package tv.afnon.home;

import android.app.Activity;
import android.os.Bundle;

/**
 * An invisible screen that closes right away. HomeTakeover opens it once at boot: whatever was on screen comes back
 * to the front when it closes and sends a window event, so the stock home can be told from an app or an HDMI input
 * without reading the screen. The same trick as the cleanyourtv launcher's ProbeActivity.
 */
public class ProbeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().post(this::close);
    }

    private void close() {
        finish();
        overridePendingTransition(0, 0);
    }
}
