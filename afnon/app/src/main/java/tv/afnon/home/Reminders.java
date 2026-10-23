package tv.afnon.home;

import android.content.Context;

import java.util.Date;

/**
 * When the reminder and the call to prayer are due. The remote buttons service checks every few seconds and
 * shows them; Home shows its own reminder card while it is in front, and the screensaver steps aside for the call.
 */
final class Reminders {
    private Reminders() {}

    /** How long after a prayer time the call screen may still open (the check runs every 15 s). */
    static final long CALL_WINDOW_MS = 120_000;

    /** Set by Home while it is in front: the reminder becomes Home's own card instead of the banner. */
    interface HomeCard {
        void showReminder();
    }

    static HomeCard home;

    /** The prayer whose time began within the last two minutes and has its adhan on; -1 if none. */
    static int dueCall(Context c, PrayerDay day, Date now) {
        if (!Prefs.adhan(c)) return -1;
        for (int i = 0; i < 6; i++) {
            if (i == PrayerDay.SUNRISE) continue;
            long since = now.getTime() - day.times[i].getTime();
            if (since >= 0 && since < CALL_WINDOW_MS && Prefs.bell(c, i)) return i;
        }
        return -1;
    }

    /**
     * The prayer whose time began within the last two minutes with its adhan off (all of them, or that prayer's
     * bell), for the spoken «حان الآن موعد صلاة …»; -1 if none, or if that announcement is turned off.
     */
    static int dueAnnounce(Context c, PrayerDay day, Date now) {
        if (!Prefs.announce(c)) return -1;
        boolean adhan = Prefs.adhan(c);
        for (int i = 0; i < 6; i++) {
            if (i == PrayerDay.SUNRISE) continue;
            long since = now.getTime() - day.times[i].getTime();
            if (since >= 0 && since < CALL_WINDOW_MS && !(adhan && Prefs.bell(c, i))) return i;
        }
        return -1;
    }

    /** Whether the next prayer is inside the reminder window (Settings: 5 to 30 minutes before, or off). */
    static boolean dueReminder(Context c, PrayerDay day, Date now) {
        int minutes = Prefs.remindMinutes(c);
        if (minutes <= 0) return false;
        long left = Screen.secondsUntil(day.nextTime, now);
        return left > 0 && left <= minutes * 60L;
    }

    /** Minutes left, rounded up, as the reminder words it. */
    static long minutesLeft(PrayerDay day, Date now) {
        return (Screen.secondsUntil(day.nextTime, now) + 59) / 60;
    }
}
