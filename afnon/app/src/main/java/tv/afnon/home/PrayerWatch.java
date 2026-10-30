package tv.afnon.home;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;

/**
 * Watches the clock from the remote buttons service, which the system keeps running whatever app is in front:
 * the reminder before each prayer (Home's own card, or a banner over other apps) and, when the time begins, the
 * adhan card: the arch sliding in from the side over whatever is on, "Maghrib Adhan", for a minute. Both are
 * accessibility overlays, so they need no overlay permission; they never take focus, touches or the sound, so
 * the programme goes on underneath. The banner hides after 12 s (design/ApproachVideo.dc.html).
 */
final class PrayerWatch {
    private static final long CHECK_MS = 15_000;
    private static final int BANNER_SECONDS = 12;
    /** How long the adhan card stays, and its slide in and out. */
    private static final long ADHAN_MS = 60_000, SLIDE_IN_MS = 700, SLIDE_OUT_MS = 450;
    /** The adhan card: the arch's 600×900 at a little over half size, and its gap to the screen's edge. */
    private static final int ADHAN_W = 330, ADHAN_H = 495, ADHAN_EDGE = 64;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /**
     * "prayer index:time" of the last reminder, call and spoken announcement, so each happens once. Saved, because
     * the system restarts this service (low memory, TCL's background manager) and a fresh one would repeat them.
     */
    private String reminded, called, announced;
    private View banner;
    /** The adhan card's window while it shows, and the card in it that slides. */
    private View adhanWindow, adhanCard;
    private final Runnable slideAdhanOut = this::slideAdhanOut;

    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            check();
            handler.postDelayed(this, CHECK_MS);
        }
    };

    PrayerWatch(AccessibilityService service) {
        this.service = service;
        reminded = Prefs.of(service).getString("reminded", "");
        called = Prefs.of(service).getString("called", "");
        announced = Prefs.of(service).getString("announced", "");
    }

    void start() {
        handler.removeCallbacks(check);
        handler.post(check);
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        hideBanner();
        hideAdhan();
    }

    private void check() {
        PowerManager pm = service.getSystemService(PowerManager.class);
        Date now = new Date();
        boolean asleep = pm != null && !pm.isInteractive();
        // Asleep: nothing to show, unless the radio plays with the screen off (TCL Audio Only), for the spoken
        // announcement. The adhan card is not worth waking the screen for: it is let go, as its minute would be.
        if (asleep && !Radio.on()) return;
        PrayerDay day = new PrayerDay(service, now);

        int call = Reminders.dueCall(service, day, now);
        if (call >= 0) {
            String key = call + ":" + day.times[call].getTime();
            // While Afnon's screensaver runs it wakes itself for the call; show the card once it has gone.
            if (!key.equals(called) && !AfnonDream.running) {
                called = key;
                Prefs.of(service).edit().putString("called", key).apply();
                if (!asleep) showAdhan(call, day.times[call], day.friday);
            }
            return;
        }
        // No call for a prayer whose adhan is off: just say that its time has begun (also with the screen off).
        int quiet = Reminders.dueAnnounce(service, day, now);
        if (quiet >= 0) {
            String key = quiet + ":" + day.times[quiet].getTime();
            if (!key.equals(announced)) {
                announced = key;
                Prefs.of(service).edit().putString("announced", key).apply();
                Announce.now(service, quiet, day.friday);
            }
        }
        if (asleep) return;
        if (Reminders.dueReminder(service, day, now)) {
            String key = day.nextIndex + ":" + day.nextTime.getTime();
            if (key.equals(reminded) || AfnonDream.running) return;
            reminded = key;
            Prefs.of(service).edit().putString("reminded", key).apply();
            if (Reminders.home != null) Reminders.home.showReminder();
            else showBanner(day, now);
            if (Prefs.remindVoice(service)) Announce.before(service, day.nextIndex, day.friday);
        }
    }

    // ---------------------------------------------------------------- adhan card

    /**
     * The adhan card: the courtyard's arch with حَيَّ عَلَى الصَّلَاةِ, the prayer's adhan ("Maghrib Adhan", «أذان
     * المغرب») and its time, sliding in from the end side (the right, the left in Arabic) over whatever is on. It
     * stays a minute, a gold line under the words running out with it, then slides back out.
     */
    void showAdhan(int prayer, Date time, boolean friday) {
        hideBanner();
        hideAdhan();
        Ui.init(service);
        Text t = Screen.loadText(service);
        boolean h24 = Prefs.clock24(service);
        Typeface ui = service.getResources().getFont(R.font.alexandria_regular);
        Typeface sacred = service.getResources().getFont(R.font.amiri_regular);
        Typeface display = service.getResources().getFont(t.ar ? R.font.amiri_bold : R.font.marcellus_regular);
        String[] names = t.prayerNames();
        if (friday && prayer == PrayerDay.DHUHR) names[PrayerDay.DHUHR] = t.jumuah();
        int w = Ui.ipx(ADHAN_W), h = Ui.ipx(ADHAN_H), edge = Ui.ipx(ADHAN_EDGE), inset = Ui.ipx(4);

        FrameLayout card = new FrameLayout(service);
        card.setLayoutDirection(t.ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        // Inset by the stroke, which the view's edge would otherwise cut in half.
        FrameLayout.LayoutParams alp = new FrameLayout.LayoutParams(w - 2 * inset, h - 2 * inset);
        alp.setMargins(inset, inset, inset, inset);
        card.addView(new ArchView(service), alp);

        LinearLayout col = new LinearLayout(service);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView verse = text(sacred, 30, Tokens.GOLD_400, "حَيَّ عَلَى الصَّلَاةِ");
        verse.setTextDirection(View.TEXT_DIRECTION_RTL);
        // "Maghrib" over "Adhan" in English, «أذان» over «المغرب» in Arabic: read down, the phrase either way.
        TextView name = text(display, t.ar ? 64 : 56, Tokens.PLASTER_100, names[prayer]);
        TextView word = text(display, 32, Tokens.GOLD_400, t.adhanWord());
        TextView when = text(ui, 21, Tokens.MIST_400, Screen.clock(t, time, h24, 1f) + t.sep() + t.city());
        when.setTextDirection(t.ar ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        TextView first = t.ar ? word : name, second = t.ar ? name : word;
        for (TextView v : new TextView[] {verse, first, second, when}) {
            v.setGravity(Gravity.CENTER_HORIZONTAL);
            v.setMaxLines(1);
            v.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(v, new LinearLayout.LayoutParams(-1, -2));
        }
        Screen.lineHeight(verse, 1.4f);
        Screen.lineHeight(name, 1.15f);
        ((LinearLayout.LayoutParams) first.getLayoutParams()).topMargin = Ui.ipx(14);
        ((LinearLayout.LayoutParams) when.getLayoutParams()).topMargin = Ui.ipx(12);
        // Under the point of the arch, where it is wide enough for the words.
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(w - 2 * Ui.ipx(34), -2,
            Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        clp.topMargin = Ui.ipx(166);
        card.addView(col, clp);

        // The minute left, emptying toward the start side as the banner's does.
        View fill = new View(service);
        fill.setBackgroundColor(Tokens.GOLD_400);
        FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(w - 2 * Ui.ipx(56), Ui.ipx(3),
            Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        flp.bottomMargin = Ui.ipx(44);
        card.addView(fill, flp);

        // The window reaches the screen's edge, so the card slides in from beyond it rather than appearing.
        FrameLayout window = new FrameLayout(service);
        window.setLayoutDirection(card.getLayoutDirection());
        window.addView(card, new FrameLayout.LayoutParams(w, h, Gravity.START | Gravity.CENTER_VERTICAL));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            w + edge, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        // The end side, half way down: the left in Arabic, the right in English.
        lp.gravity = Gravity.CENTER_VERTICAL | (t.ar ? Gravity.LEFT : Gravity.RIGHT);
        float away = (t.ar ? -1 : 1) * (w + edge);
        card.setTranslationX(away);
        card.setAlpha(0f);
        try {
            service.getSystemService(WindowManager.class).addView(window, lp);
        } catch (RuntimeException e) {
            return;
        }
        adhanWindow = window;
        adhanCard = card;
        card.animate().translationX(0f).alpha(1f).setDuration(SLIDE_IN_MS)
            .setInterpolator(new DecelerateInterpolator(2f)).start();
        fill.post(() -> {
            fill.setPivotX(t.ar ? fill.getWidth() : 0);
            fill.animate().scaleX(0f).setStartDelay(SLIDE_IN_MS).setDuration(ADHAN_MS - SLIDE_IN_MS)
                .setInterpolator(null).start();
        });
        handler.postDelayed(slideAdhanOut, ADHAN_MS);
    }

    /** The minute is up: back out to the side it came from, then gone. */
    private void slideAdhanOut() {
        View card = adhanCard;
        if (card == null) return;
        float away = (card.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -1 : 1) * (card.getWidth() + Ui.ipx(ADHAN_EDGE));
        card.animate().translationX(away).alpha(0f).setDuration(SLIDE_OUT_MS)
            .setInterpolator(new AccelerateInterpolator(2f)).withEndAction(this::hideAdhan).start();
    }

    void hideAdhan() {
        handler.removeCallbacks(slideAdhanOut);
        if (adhanWindow == null) return;
        // A slide still running is dropped with its end action, so it can't take a newer card with it.
        if (adhanCard != null) adhanCard.animate().cancel();
        try {
            service.getSystemService(WindowManager.class).removeView(adhanWindow);
        } catch (RuntimeException ignored) {
            // Already gone with the service.
        }
        adhanWindow = null;
        adhanCard = null;
    }

    // ---------------------------------------------------------------- banner

    void showBanner(PrayerDay day, Date now) {
        hideBanner();
        Ui.init(service);
        Text t = Screen.loadText(service);
        boolean h24 = Prefs.clock24(service);
        Typeface ui = service.getResources().getFont(R.font.alexandria_regular);
        Typeface display = service.getResources().getFont(t.ar ? R.font.amiri_bold : R.font.marcellus_regular);

        LinearLayout card = new LinearLayout(service);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(t.ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        card.setPadding(Ui.ipx(28), Ui.ipx(24), Ui.ipx(28), 0);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Tokens.LAPIS_900);
        bg.setCornerRadius(Ui.px(24));
        bg.setStroke(Ui.ipx(2), Tokens.GOLD_400);
        card.setBackground(bg);
        card.setClipToOutline(true);
        card.setClipToPadding(false);

        LinearLayout row = new LinearLayout(service);
        row.setGravity(Gravity.CENTER_VERTICAL);
        Icon mihrab = new Icon(service, Icon.MIHRAB, 1.2f);
        mihrab.setColor(Tokens.GOLD_400);
        row.addView(mihrab, new LinearLayout.LayoutParams(Ui.ipx(56), Ui.ipx(72)));
        LinearLayout words = new LinearLayout(service);
        words.setOrientation(LinearLayout.VERTICAL);
        String[] names = t.prayerNames();
        if (day.friday) names[PrayerDay.DHUHR] = t.jumuah();
        long minutes = Reminders.minutesLeft(day, now);
        TextView label = text(ui, 20, Tokens.MIST_400, t.apLabel());
        if (!t.ar) label.setLetterSpacing(0.2f);
        TextView title = text(display, 44, Tokens.PLASTER_100,
            t.apTitle(names[day.nextIndex], minutes, Screen.duration(Screen.secondsUntil(day.nextTime, now), false)));
        TextView sub = text(ui, 22, Tokens.MIST_400, t.bannerSub(Screen.clock(t, day.nextTime, h24, 1f), BANNER_SECONDS));
        words.addView(label);
        words.addView(title);
        words.addView(sub);
        Screen.lineHeight(title, 1.1f);
        ((LinearLayout.LayoutParams) sub.getLayoutParams()).topMargin = Ui.ipx(4);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(0, -2, 1);
        wlp.setMarginStart(Ui.ipx(20));
        row.addView(words, wlp);
        card.addView(row);

        // The time left before it hides, emptying toward the start side.
        FrameLayout track = new FrameLayout(service);
        track.setBackgroundColor(Tokens.BANNER_TRACK);
        View fill = new View(service);
        fill.setBackgroundColor(Tokens.GOLD_400);
        track.addView(fill, new FrameLayout.LayoutParams(-1, -1));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, Ui.ipx(6));
        tlp.topMargin = Ui.ipx(18);
        tlp.setMarginStart(-Ui.ipx(28));
        tlp.setMarginEnd(-Ui.ipx(28));
        card.addView(track, tlp);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            Ui.ipx(640), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        // Top end: the left in Arabic, the right in English.
        lp.gravity = Gravity.TOP | (t.ar ? Gravity.LEFT : Gravity.RIGHT);
        lp.x = Ui.ipx(96);
        lp.y = Ui.ipx(56);
        WindowManager wm = service.getSystemService(WindowManager.class);
        try {
            wm.addView(card, lp);
        } catch (RuntimeException e) {
            return;
        }
        banner = card;
        fill.post(() -> {
            fill.setPivotX(t.ar ? fill.getWidth() : 0);
            fill.animate().scaleX(0f).setDuration(BANNER_SECONDS * 1000L).setInterpolator(null).start();
        });
        handler.postDelayed(this::hideBanner, BANNER_SECONDS * 1000L);
    }

    void hideBanner() {
        if (banner == null) return;
        try {
            service.getSystemService(WindowManager.class).removeView(banner);
        } catch (RuntimeException ignored) {
            // Already gone with the service.
        }
        banner = null;
    }

    private TextView text(Typeface tf, float size, int color, CharSequence s) {
        TextView v = new TextView(service);
        v.setTypeface(tf);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(size));
        v.setTextColor(color);
        v.setText(s);
        return v;
    }

    // ---------------------------------------------------------------- testing

    /**
     * For checking on the TV without waiting for a prayer: the banner (with the spoken reminder, if on), the adhan
     * card, or the spoken «حان الآن موعد صلاة …» for the next prayer, after a delay to switch to another app first.
     * From adb: `am start -n tv.afnon.home/.Test --es test banner|adhan|announce` ("call" is the adhan card too).
     */
    void test(String kind, long delayMs) {
        handler.postDelayed(() -> {
            Date now = new Date();
            PrayerDay day = new PrayerDay(service, now);
            if ("adhan".equals(kind) || "call".equals(kind)) {
                showAdhan(day.nextIndex, day.nextTime, day.friday);
            } else if ("announce".equals(kind)) {
                Announce.now(service, day.nextIndex, day.friday);
            } else {
                showBanner(day, now);
                if (Prefs.remindVoice(service)) Announce.before(service, day.nextIndex, day.friday);
            }
        }, delayMs);
    }
}
