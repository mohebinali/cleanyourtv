package tv.afnon.home;

import android.content.Context;
import android.content.Intent;

/**
 * Whether RemoteKeys should filter key events on a TV where TCL's remapping ({@link TclKeys}) is not available.
 *
 * Filtering costs something: Android re-injects every filtered key and injected keys never auto-repeat, so holding
 * Volume moves it one step. It is only worth it where nothing else answers the remote's buttons once the stock
 * launcher is gone (Google TV's launcherx answered the app buttons itself). Some firmwares answer them below the
 * launcher, in their own hotkey service: CVTE boards (Super General and other Amlogic/MediaTek sets) read
 * /vendor/menu_config_xml/hotkey.xml in com.cvte.tv.api.impl and open the app buttons, Settings and the source menu
 * themselves. There Afnon leaves the keys alone.
 *
 * The "key_filter" preference overrides the guess: "on" or "off"; anything else (the default "auto") guesses.
 * Set it on a TV with: adb shell run-as tv.afnon.home (edit shared_prefs/afnon.xml), then restart the service.
 */
final class FirmwareKeys {
    private FirmwareKeys() {}

    /** CVTE's global hotkey service (com.cvte.tv.api.impl/com.cvte.tv.api.HotkeyService). */
    static final String CVTE_HOTKEY_ACTION = "android.intent.action.HOTKEYSERVICE";

    static boolean shouldFilter(Context c) {
        String mode = Prefs.of(c).getString("key_filter", "auto");
        if ("on".equals(mode)) return true;
        if ("off".equals(mode)) return false;
        return !firmwareAnswersButtons(c);
    }

    private static boolean firmwareAnswersButtons(Context c) {
        return !c.getPackageManager().queryIntentServices(new Intent(CVTE_HOTKEY_ACTION), 0).isEmpty();
    }

    /** Firmware source (input) menus, tried in order when there is no Android TV inputs screen. */
    private static final String[][] SOURCE_MENUS = {
        // CVTE: its hotkey service leaves the Source key to the app in front, and the stock launcher opened this.
        {"com.cvte.tv.globalui", "com.cvte.tv.globalui.InputSourceActivity"},
    };

    /** The TV's own source menu, or null if this firmware has none we know. */
    static Intent sourceMenu(Context c) {
        for (String[] m : SOURCE_MENUS) {
            Intent i = new Intent().setClassName(m[0], m[1]);
            if (c.getPackageManager().resolveActivity(i, 0) != null) return i;
        }
        return null;
    }
}
