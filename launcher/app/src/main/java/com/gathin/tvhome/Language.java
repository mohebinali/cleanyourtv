package com.gathin.tvhome;

import android.annotation.SuppressLint;
import android.app.LocaleManager;
import android.content.Context;
import android.content.res.Resources;
import android.os.Build;
import android.os.LocaleList;

import java.util.IllformedLocaleException;
import java.util.Locale;

/**
 * The TV's language, from the Language pill: English, the first choice, or Arabic.
 *
 * With CHANGE_CONFIGURATION and the WRITE_SETTINGS app-op, both granted over adb, this sets the whole TV's languages
 * as Settings > System > Language does, the chosen one first and the other second: every app follows, the prayer
 * app included, and Android recreates this screen in the new language (layout-ar mirrors it for Arabic). Without
 * them it changes this home's own language instead (Android 13 and later), so the pill still works here.
 *
 * Arabic goes in with Western digits (the Unicode "nu-latn" extension), so the clock, dates and every other number
 * read 0-9 as in English instead of turning into Eastern Arabic digits (٠-٩), on Home and across the TV.
 */
final class Language {
    static final String ENGLISH = "en", ARABIC = "ar";

    private Language() { }

    /** The language this home is in now: Arabic, or English for any other. */
    static String current(Context c) {
        return ARABIC.equals(c.getResources().getConfiguration().getLocales().get(0).getLanguage()) ? ARABIC : ENGLISH;
    }

    /**
     * Switches to language (ENGLISH or ARABIC). Off the main thread: a switch of the whole TV holds every app for a
     * moment, and Android brings the new language to this screen by recreating it.
     */
    @SuppressLint({"PrivateApi", "DiscouragedPrivateApi"})
    static void set(Context context, String language) {
        final Context app = context.getApplicationContext();
        new Thread(() -> {
            LocaleList system = Resources.getSystem().getConfiguration().getLocales();
            Locale chosen = variant(system, language);
            Locale other = variant(system, ENGLISH.equals(language) ? ARABIC : ENGLISH);
            try {
                // Settings' own call. Android lets apps make it (it is on the list of supported non-SDK calls) and
                // checks the two grants itself, throwing when one is missing.
                Class.forName("com.android.internal.app.LocalePicker")
                        .getMethod("updateLocales", LocaleList.class)
                        .invoke(null, new LocaleList(chosen, other));
            } catch (ReflectiveOperationException | RuntimeException e) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    app.getSystemService(LocaleManager.class).setApplicationLocales(new LocaleList(chosen));
                }
                return;
            }
            // The TV's language decides from now on: drop any language this home once set for itself.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                LocaleManager own = app.getSystemService(LocaleManager.class);
                if (!own.getApplicationLocales().isEmpty()) own.setApplicationLocales(LocaleList.getEmptyLocaleList());
            }
        }, "tvhome-language").start();
    }

    /**
     * The TV's own form of a language when it lists one (en-GB stays en-GB), else US English or plain Arabic; Arabic
     * with Western digits.
     */
    private static Locale variant(LocaleList list, String language) {
        Locale found = null;
        for (int i = 0; i < list.size() && found == null; i++) {
            if (language.equals(list.get(i).getLanguage())) found = list.get(i);
        }
        if (found == null) found = ENGLISH.equals(language) ? Locale.US : Locale.forLanguageTag(ARABIC);
        if (!ARABIC.equals(language)) return found;
        try {
            return new Locale.Builder().setLocale(found).setUnicodeLocaleKeyword("nu", "latn").build();
        } catch (IllformedLocaleException e) {
            return Locale.forLanguageTag("ar-u-nu-latn");
        }
    }
}
