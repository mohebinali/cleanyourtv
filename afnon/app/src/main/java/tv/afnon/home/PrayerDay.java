package tv.afnon.home;

import com.batoulapps.adhan.CalculationMethod;
import com.batoulapps.adhan.CalculationParameters;
import com.batoulapps.adhan.Coordinates;
import com.batoulapps.adhan.Madhab;
import com.batoulapps.adhan.PrayerTimes;
import com.batoulapps.adhan.data.DateComponents;

import java.util.Calendar;
import java.util.Date;

/**
 * One moment's view of the prayer day in Dammam, worked out on the TV with no network (adhan-java): Umm al-Qura, as
 * Saudi mosques keep it, with the standard (Shafi'i) Asr. The six times, which prayer is next, and whether it is
 * Friday, when Dhuhr is Jumu'ah.
 */
final class PrayerDay {
    static final int FAJR = 0, SUNRISE = 1, DHUHR = 2, ASR = 3, MAGHRIB = 4, ISHA = 5;
    private static final Coordinates DAMMAM = new Coordinates(26.4333, 50.1000);
    /** How long after a prayer's time its card may still come up (the watch checks every 15 s). */
    private static final long BEGAN_MS = 120_000;

    /** Today's times, except that after Isha the Fajr slot holds tomorrow's Fajr (it is the next prayer). */
    final Date[] times = new Date[6];
    /** Index of the next prayer (never Sunrise, which is a marker, not a prayer). */
    final int nextIndex;
    final Date nextTime;
    final boolean friday;

    PrayerDay(Date now) {
        CalculationParameters params = CalculationMethod.UMM_AL_QURA.getParameters();
        params.madhab = Madhab.SHAFI;
        PrayerTimes today = new PrayerTimes(DAMMAM, DateComponents.from(now), params);
        times[FAJR] = minute(today.fajr);
        times[SUNRISE] = minute(today.sunrise);
        times[DHUHR] = minute(today.dhuhr);
        times[ASR] = minute(today.asr);
        times[MAGHRIB] = minute(today.maghrib);
        times[ISHA] = minute(today.isha);

        int next = -1;
        for (int i = 0; i < 6; i++) {
            if (i != SUNRISE && times[i].after(now)) { next = i; break; }
        }
        Calendar c = Calendar.getInstance();
        c.setTime(now);
        friday = c.get(Calendar.DAY_OF_WEEK) == Calendar.FRIDAY;
        if (next == -1) {
            c.add(Calendar.DAY_OF_MONTH, 1);
            next = FAJR;
            times[FAJR] = minute(new PrayerTimes(DAMMAM, DateComponents.from(c.getTime()), params).fajr);
        }
        nextIndex = next;
        nextTime = times[next];
    }

    /** The prayer whose time began within the last two minutes; -1 if none. */
    int justBegan(Date now) {
        for (int i = 0; i < 6; i++) {
            if (i == SUNRISE) continue;
            long since = now.getTime() - times[i].getTime();
            if (since >= 0 && since < BEGAN_MS) return i;
        }
        return -1;
    }

    /**
     * The time on its whole minute. adhan-java keeps the milliseconds of the moment it was asked, so the same
     * prayer came out a little different on every check, and "already shown" never matched.
     */
    private static Date minute(Date d) {
        return new Date(d.getTime() / 60_000 * 60_000);
    }
}
