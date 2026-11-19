package tv.afnon.home;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

/**
 * The stock home's flash, covered. Pressing Home is the one way this app has of making the Chromecast HDMI-CEC's
 * active source, which the TV's remote needs as the TV comes on (TvPower), and on Google TV Home opens Google's own
 * home screen before HomeTakeover can put the normal home back over it: about half a second of the wrong home
 * (2026-11-19 23:22:07.37 to 23:22:07.8). So a picture is taken of the screen as it is, shown over everything, Home
 * is pressed under it, and it is lifted once the normal home is back in front, or after 2.5 s. The picture is the
 * service's one use of taking screenshots; it is held for that moment and thrown away. Where it can't be taken
 * (before Android 11, or protected video in front), the cover is black. Like the prayer card it is an accessibility
 * overlay, taking no focus and no touches.
 */
final class HomeCover {
    /** The cover goes this long after the normal home is back in front (time for it to draw), fading this long. */
    private static final long LIFT_AFTER_HOME_MS = 300, FADE_MS = 160;
    /** At the longest, it stays this long. And Home waits this long for it to be on screen. */
    private static final long MAX_MS = 2_500, SHOWN_MS = 60;

    private final AccessibilityService service;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable lift = this::lift;
    private ImageView cover;
    private Bitmap picture;
    /** Home has been pressed under the cover: the normal home coming up from now is the one that ends it. */
    private boolean pressed;

    HomeCover(AccessibilityService service) {
        this.service = service;
    }

    /** Home pressed under cover: press is run once the cover is up. From any thread. */
    void press(Runnable press) {
        handler.post(() -> {
            if (cover != null) {
                // Covered already (two reasons for Home at once): the press goes under the cover that is up.
                press.run();
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                show(null, press);
                return;
            }
            service.takeScreenshot(Display.DEFAULT_DISPLAY, service.getMainExecutor(),
                new AccessibilityService.TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(AccessibilityService.ScreenshotResult result) {
                        HardwareBuffer buffer = result.getHardwareBuffer();
                        Bitmap taken = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        buffer.close();
                        show(taken, press);
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        show(null, press);
                    }
                });
        });
    }

    /** The normal home came up in front (RemoteKeys hears it): after a press, that is the end of what is covered. */
    void homeIsBack() {
        handler.post(() -> {
            if (cover == null || !pressed) return;
            handler.removeCallbacks(lift);
            handler.postDelayed(lift, LIFT_AFTER_HOME_MS);
        });
    }

    void stop() {
        handler.removeCallbacksAndMessages(null);
        remove();
    }

    private void show(Bitmap taken, Runnable press) {
        if (cover != null) {
            press.run();
            return;
        }
        ImageView view = new ImageView(service);
        view.setBackgroundColor(Color.BLACK);
        view.setScaleType(ImageView.ScaleType.FIT_XY);
        if (taken != null) view.setImageBitmap(taken);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE);
        try {
            service.getSystemService(WindowManager.class).addView(view, lp);
        } catch (RuntimeException e) {
            // No cover to be had: Home all the same, for the remote.
            press.run();
            return;
        }
        cover = view;
        picture = taken;
        pressed = false;
        handler.postDelayed(() -> {
            pressed = true;
            press.run();
        }, SHOWN_MS);
        handler.postDelayed(lift, MAX_MS);
    }

    /** lift: the cover fades off what is now under it, and goes. */
    private void lift() {
        handler.removeCallbacks(lift);
        ImageView view = cover;
        if (view == null) return;
        view.animate().alpha(0f).setDuration(FADE_MS).withEndAction(this::remove).start();
    }

    private void remove() {
        if (cover == null) return;
        cover.animate().cancel();
        try {
            service.getSystemService(WindowManager.class).removeView(cover);
        } catch (RuntimeException ignored) {
            // Already gone with the service.
        }
        cover = null;
        if (picture != null) picture.recycle();
        picture = null;
        pressed = false;
    }
}
