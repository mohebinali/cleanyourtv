package tv.afnon.home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

/**
 * The Prayer app's Home, which is only a door to the normal home. The app holds Android's Home role (set with
 * `cmd package set-home-activity`), which HomeTakeover needs before it acts; Google TV still sends Home to its own
 * launcher, so this opens only when something asks for this app's Home, and it goes straight on to the normal home.
 * From adb, through the .Test alias: `--es test adhan` puts up the prayer card for the next prayer 8 s later;
 * `--es test wifi` turns Wi-Fi off for 25 s and back on (TvPower, this app doing both, so adb over Wi-Fi comes back);
 * `--es test dry` has TvPower only log what it would do to Wi-Fi until the service restarts, and wake the Chromecast
 * 8 s after the TV goes off rather than 1.5 s.
 */
public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent i = getIntent();
        ComponentName c = i.getComponent();
        // The test hook counts only through the .Test alias, which only the adb shell may start (manifest).
        if (c != null && c.getClassName().equals(getPackageName() + ".Test")) {
            String test = i.getStringExtra("test");
            if ("adhan".equals(test) && RemoteKeys.watch != null) RemoteKeys.watch.test(8000);
            if ("wifi".equals(test) && RemoteKeys.tvPower != null) RemoteKeys.tvPower.testWifi();
            if ("dry".equals(test) && RemoteKeys.tvPower != null) RemoteKeys.tvPower.testDry();
        }
        // A HOME intent for its package, not pinned to a class, so it starts as a home activity: Back from apps
        // returns to it.
        try {
            startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setPackage(HomeTakeover.NORMAL_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            // No normal home installed: there is nothing to go on to.
        }
        finish();
    }
}
