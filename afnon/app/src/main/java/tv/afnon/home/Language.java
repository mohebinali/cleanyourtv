package tv.afnon.home;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;

import java.util.Locale;

/**
 * The TV's language from Home's Language pill: English first and Arabic second, or the other way round.
 *
 * Sets the whole TV's languages as Settings > System > Language does, so every app follows, the normal home included,
 * and Afnon, with its language on System, rebuilds when the change reaches it (Screen.onConfigurationChanged). That
 * takes CHANGE_CONFIGURATION and the WRITE_SETTINGS app-op, both granted over adb by tools/install.sh.
 */
final class Language {
    private Language() {}

    /**
     * Makes Arabic (or English) the TV's first language and the other its second. Off the main thread, since the
     * switch holds every app for a moment; refused runs on the main thread when the TV says no.
     */
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    static void set(boolean arabic, Runnable refused) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            LocaleList system = Resources.getSystem().getConfiguration().getLocales();
            Locale en = variant(system, "en"), ar = variant(system, "ar");
            try {
                // Settings' own call. Android lets apps make it (it is on the list of supported non-SDK calls) and
                // checks the two grants itself, throwing when one is missing.
                Class.forName("com.android.internal.app.LocalePicker")
                        .getMethod("updateLocales", LocaleList.class)
                        .invoke(null, arabic ? new LocaleList(ar, en) : new LocaleList(en, ar));
            } catch (ReflectiveOperationException | RuntimeException e) {
                main.post(refused);
            }
        }, "afnon-language").start();
    }

    /** The TV's own form of a language when it lists one (en-GB stays en-GB), else US English or plain Arabic. */
    private static Locale variant(LocaleList list, String language) {
        for (int i = 0; i < list.size(); i++) {
            if (language.equals(list.get(i).getLanguage())) return list.get(i);
        }
        return "en".equals(language) ? Locale.US : Locale.forLanguageTag("ar");
    }
}
