package com.gathin.tvhome;

import android.content.res.Resources;
import android.icu.util.IslamicCalendar;

import java.text.NumberFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The Hijri date under the Gregorian one: the Umm al-Qura calendar, as Saudi Arabia keeps it and the prayer app
 * shows it, with the home's month names and digits.
 */
final class Hijri {

    private Hijri() {
    }

    /** e.g. "19 Jumada al-Ula 1448 AH", or "19 جمادى الأولى 1448 هـ" in Arabic. */
    static String today(Resources res) {
        IslamicCalendar h = new IslamicCalendar();
        h.setCalculationType(IslamicCalendar.CalculationType.ISLAMIC_UMALQURA);
        h.setTime(new Date());
        Locale locale = res.getConfiguration().getLocales().get(0);
        NumberFormat digits = NumberFormat.getIntegerInstance(locale);
        digits.setGroupingUsed(false);   // the year is 1448, not 1,448
        return res.getString(R.string.hijri_date, digits.format(h.get(IslamicCalendar.DAY_OF_MONTH)),
                res.getStringArray(R.array.hijri_months)[h.get(IslamicCalendar.MONTH)],
                digits.format(h.get(IslamicCalendar.YEAR)));
    }
}
