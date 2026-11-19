package tv.afnon.home;

import android.content.Context;
import android.text.format.DateFormat;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * The words put on the TV, the prayer card's and JamNotice's, in the TV's language: Arabic when the TV is in Arabic,
 * English otherwise; 0-9 in both.
 */
final class Text {
    final boolean ar;
    /** The TV's own 12- or 24-hour clock (Settings → Date & time). */
    private final boolean h24;

    Text(Context c) {
        ar = "ar".equals(c.getResources().getConfiguration().getLocales().get(0).getLanguage());
        h24 = DateFormat.is24HourFormat(c);
    }

    /** Fajr, Sunrise, Dhuhr, Asr, Maghrib, Isha. */
    String[] prayerNames() {
        return ar ? new String[] {"الفجر", "الشروق", "الظهر", "العصر", "المغرب", "العشاء"}
                  : new String[] {"Fajr", "Sunrise", "Dhuhr", "Asr", "Maghrib", "Isha"};
    }

    /** Dhuhr's name on a Friday. */
    String jumuah() { return ar ? "الجمعة" : "Jumu'ah"; }

    /** JamNotice's heading, and under it what cures it. */
    String jamTitle() {
        return ar ? "ريموت التلفزيون لا يصل إلى الكروم كاست" : "The TV's remote isn't reaching the Chromecast";
    }

    String jamWhatToDo() {
        return ar ? "افصل أجهزة HDMI الأخرى عن التلفزيون، انتظر 10 ثوانٍ، ثم أعد توصيلها."
                  : "Unplug the other HDMI devices from the TV, wait 10 seconds, then plug them back in.";
    }

    /** The word with the prayer's name: "Maghrib Adhan", «أذان المغرب». */
    String adhanWord() { return ar ? "أذان" : "Adhan"; }

    /** The prayer's time as the TV shows the clock, and the city: "4:59 PM · Dammam", «4:59 م · الدمام». */
    String when(Date d) {
        String s = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm", Locale.US).format(d);
        if (!h24) {
            Calendar c = Calendar.getInstance();
            c.setTime(d);
            boolean am = c.get(Calendar.AM_PM) == Calendar.AM;
            s += " " + (ar ? (am ? "ص" : "م") : (am ? "AM" : "PM"));
        }
        return s + " · " + (ar ? "الدمام" : "Dammam");
    }
}
