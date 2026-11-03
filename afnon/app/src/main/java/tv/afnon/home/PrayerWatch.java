package tv.afnon.home;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Date;

/**
 * The prayer card. Watches the clock from the always-on service, and when a prayer's time begins slides in the
 * mihrab on its side from the edge of the screen, over whatever is on, with حَيَّ عَلَى الصَّلَاةِ, the prayer's adhan
 * ("Maghrib Adhan", «أذان المغرب») and its time, for a minute, then slides it back out. It makes no sound. It is an
 * accessibility overlay, so it needs no overlay permission, and it never takes focus or touches, so the programme
 * goes on underneath.
 */
final class PrayerWatch {
    private static final long CHECK_MS = 15_000;
    /** How long the card stays, and its slide in and out. */
    private static final long ADHAN_MS = 60_000, SLIDE_IN_MS = 700, SLIDE_OUT_MS = 450;
    /**
     * The card: the arch on its side, 470 long and 280 high, its base on the screen's start edge (the left, the right
     * in Arabic) and its top at 228. On Home that keeps it between the clock (it ends above 222) and the name of the
     * dock app (from 522), across the screen from the pills and the week's weather under them, in either language.
     */
    private static final int ADHAN_W = 470, ADHAN_H = 280, ADHAN_TOP = 228;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    /**
     * "prayer index:time" of the last card, so each prayer gets one. Saved, because the system restarts this service
     * now and then, and a fresh one would show it again.
     */
    private String called;
    /** The card's window while it shows, the card in it that slides, and the edge it came in from. */
    private View adhanWindow, adhanCard;
    private boolean adhanFromLeft;
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
        called = Prefs.of(service).getString("called", "");
    }

    void start() {
        handler.removeCallbacks(check);
        handler.post(check);
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        hideAdhan();
    }

    private void check() {
        // Asleep: nothing to show, and a card left up as the screen went off would come back with the screen, so it
        // goes. The card isn't worth waking the TV for; switched on within the prayer's first two minutes, it shows.
        PowerManager pm = service.getSystemService(PowerManager.class);
        if (pm != null && !pm.isInteractive()) {
            hideAdhan();
            return;
        }
        Date now = new Date();
        PrayerDay day = new PrayerDay(now);
        int prayer = day.justBegan(now);
        if (prayer < 0) return;
        String key = prayer + ":" + day.times[prayer].getTime();
        if (key.equals(called)) return;
        called = key;
        Prefs.of(service).edit().putString("called", key).apply();
        showAdhan(prayer, day.times[prayer], day.friday);
    }

    /**
     * The card: the mihrab on its side (SideArchView), its base on the start edge of the screen (the left, the right
     * in Arabic) and its point toward the middle, sliding in over whatever is on with حَيَّ عَلَى الصَّلَاةِ, the prayer's
     * adhan and its time. It stays a minute, a gold line under the words running out with it, then slides back out.
     */
    void showAdhan(int prayer, Date time, boolean friday) {
        hideAdhan();
        Ui.init(service);
        Text t = new Text(service);
        Typeface ui = service.getResources().getFont(R.font.alexandria_regular);
        Typeface sacred = service.getResources().getFont(R.font.amiri_regular);
        Typeface display = service.getResources().getFont(t.ar ? R.font.amiri_bold : R.font.marcellus_regular);
        String[] names = t.prayerNames();
        if (friday && prayer == PrayerDay.DHUHR) names[PrayerDay.DHUHR] = t.jumuah();
        int w = Ui.ipx(ADHAN_W), h = Ui.ipx(ADHAN_H);
        // The start edge, across the screen from Home's pills and the weather under them.
        boolean fromLeft = !t.ar;

        FrameLayout card = new FrameLayout(service);
        // The verse's upper marks reach past its view's edge; the window still clips the slide.
        card.setClipChildren(false);
        card.setLayoutDirection(t.ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        card.addView(new SideArchView(service, fromLeft), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(service);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setGravity(Gravity.CENTER_HORIZONTAL);
        col.setClipChildren(false);
        TextView verse = text(sacred, 23, Tokens.GOLD_400, "حَيَّ عَلَى الصَّلَاةِ");
        verse.setTextDirection(View.TEXT_DIRECTION_RTL);
        // "Maghrib" over "Adhan" in English, «أذان» over «المغرب» in Arabic: read down, the phrase either way.
        TextView name = text(display, t.ar ? 50 : 44, Tokens.PLASTER_100, names[prayer]);
        TextView word = text(display, 25, Tokens.GOLD_400, t.adhanWord());
        TextView when = text(ui, 17, Tokens.MIST_400, t.when(time));
        when.setTextDirection(t.ar ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        TextView first = t.ar ? word : name, second = t.ar ? name : word;
        for (TextView v : new TextView[] {verse, first, second, when}) {
            v.setGravity(Gravity.CENTER_HORIZONTAL);
            v.setMaxLines(1);
            v.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(v, new LinearLayout.LayoutParams(-1, -2));
        }
        lineHeight(verse, 1.4f);
        lineHeight(name, 1.15f);
        ((LinearLayout.LayoutParams) first.getLayoutParams()).topMargin = Ui.ipx(4);
        ((LinearLayout.LayoutParams) when.getLayoutParams()).topMargin = Ui.ipx(6);
        // The minute left, emptying toward the start side.
        View fill = new View(service);
        fill.setBackgroundColor(Tokens.GOLD_400);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(Ui.ipx(150), Ui.ipx(3));
        flp.topMargin = Ui.ipx(12);
        col.addView(fill, flp);
        // The words in the body of the arch, past most of the curve of its point, and off the screen's edge.
        int point = Math.round(SideArchView.tip(h) * 0.7f), edge = Ui.ipx(26);
        FrameLayout.LayoutParams clp = new FrameLayout.LayoutParams(w - point - edge, -2,
            Gravity.CENTER_VERTICAL | (fromLeft ? Gravity.LEFT : Gravity.RIGHT));
        if (fromLeft) clp.leftMargin = edge;
        else clp.rightMargin = edge;
        card.addView(col, clp);

        // The window is the card at the screen's edge, so the card slides in from beyond it rather than appearing.
        FrameLayout window = new FrameLayout(service);
        window.addView(card, new FrameLayout.LayoutParams(w, h));
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        // The start edge, between the clock and the dock: the left in English, the right in Arabic.
        lp.gravity = Gravity.TOP | (fromLeft ? Gravity.LEFT : Gravity.RIGHT);
        lp.y = Ui.ipx(ADHAN_TOP);
        card.setTranslationX((fromLeft ? -1 : 1) * w);
        card.setAlpha(0f);
        try {
            service.getSystemService(WindowManager.class).addView(window, lp);
        } catch (RuntimeException e) {
            return;
        }
        adhanWindow = window;
        adhanCard = card;
        adhanFromLeft = fromLeft;
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
        float away = (adhanFromLeft ? -1 : 1) * card.getWidth();
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

    private TextView text(Typeface tf, float size, int color, CharSequence s) {
        TextView v = new TextView(service);
        v.setTypeface(tf);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(size));
        v.setTextColor(color);
        v.setText(s);
        return v;
    }

    /** The design's line height, a multiple of the text size: the extra space shared above and below the line. */
    private static void lineHeight(TextView v, float lh) {
        v.setIncludeFontPadding(false);
        Paint.FontMetricsInt fm = v.getPaint().getFontMetricsInt();
        int half = Math.round((v.getTextSize() * lh - (fm.descent - fm.ascent)) / 2);
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        lp.topMargin += half;
        lp.bottomMargin += half;
        v.setLayoutParams(lp);
    }

    /**
     * For checking on the TV without waiting for a prayer: the card for the next prayer, delayMs from now. From adb:
     * `am start -n tv.afnon.home/.Test --es test adhan` (8 s, time to open another app first).
     */
    void test(long delayMs) {
        handler.postDelayed(() -> {
            PrayerDay day = new PrayerDay(new Date());
            showAdhan(day.nextIndex, day.nextTime, day.friday);
        }, delayMs);
    }
}
