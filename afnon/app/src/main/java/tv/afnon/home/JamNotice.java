package tv.afnon.home;

import android.accessibilityservice.AccessibilityService;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * What to do when the TV's remote stops reaching the Chromecast, said on the TV. Three times in four weeks some other
 * device on the TV's HDMI took to refusing every HDMI-CEC broadcast (README); the TV then never learns which input the
 * Chromecast is on and passes it no buttons, and nothing on screen says why. Unplugging the other HDMI devices and
 * plugging them back in cures it in seconds (2026-11-19). TvPower sees the refusals in HDMI-CEC's history, and this
 * says so: a panel at the foot of the screen for 45 s, again every ten minutes while it lasts. Like the prayer card it
 * is an accessibility overlay, takes no focus and no touches, and is not put up while the Chromecast sleeps.
 */
final class JamNotice {
    private static final long SHOW_MS = 45_000, AGAIN_MS = 10 * 60_000, FADE_MS = 400;
    /** The panel: this wide, this far above the foot of the screen (design px, 1920 across). */
    private static final int WIDTH = 1080, BOTTOM = 84;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hide = this::hide;
    private View window;
    private long shownAt = -AGAIN_MS;

    JamNotice(AccessibilityService service) {
        this.service = service;
    }

    /** The broadcasts are being refused: said now, unless it was said in the last ten minutes. */
    void show() {
        handler.post(() -> show(false));
    }

    /** For checking its look on the TV, from adb: `am start -n tv.afnon.home/.Test --es test jam`. */
    void test() {
        handler.post(() -> show(true));
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        hide();
    }

    private void show(boolean anyway) {
        long now = SystemClock.elapsedRealtime();
        PowerManager pm = service.getSystemService(PowerManager.class);
        if (!anyway && (now - shownAt < AGAIN_MS || (pm != null && !pm.isInteractive()))) return;
        hide();
        shownAt = now;
        Ui.init(service);
        Text t = new Text(service);
        Typeface ui = service.getResources().getFont(R.font.alexandria_regular);

        LinearLayout panel = new LinearLayout(service);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutDirection(t.ar ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setPadding(Ui.ipx(40), Ui.ipx(26), Ui.ipx(40), Ui.ipx(28));
        GradientDrawable ground = new GradientDrawable();
        ground.setColor(Tokens.LAPIS_800);
        ground.setCornerRadius(Ui.px(22));
        ground.setStroke(Math.max(1, Ui.ipx(2)), Tokens.GOLD_400);
        panel.setBackground(ground);
        TextView title = text(ui, 30, Tokens.PLASTER_100, t.jamTitle());
        TextView what = text(ui, 23, Tokens.MIST_400, t.jamWhatToDo());
        panel.addView(title, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(-1, -2);
        below.topMargin = Ui.ipx(8);
        panel.addView(what, below);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            Ui.ipx(WIDTH), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.y = Ui.ipx(BOTTOM);
        panel.setAlpha(0f);
        try {
            service.getSystemService(WindowManager.class).addView(panel, lp);
        } catch (RuntimeException e) {
            return;
        }
        window = panel;
        panel.animate().alpha(1f).setDuration(FADE_MS).start();
        handler.postDelayed(hide, SHOW_MS);
    }

    private void hide() {
        handler.removeCallbacks(hide);
        if (window == null) return;
        window.animate().cancel();
        try {
            service.getSystemService(WindowManager.class).removeView(window);
        } catch (RuntimeException ignored) {
            // Already gone with the service.
        }
        window = null;
    }

    private TextView text(Typeface tf, float size, int color, CharSequence s) {
        TextView v = new TextView(service);
        v.setTypeface(tf);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, Ui.px(size));
        v.setTextColor(color);
        v.setText(s);
        v.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        return v;
    }
}
