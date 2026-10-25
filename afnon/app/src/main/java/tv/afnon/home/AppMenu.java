package tv.afnon.home;

import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.provider.Settings;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * What holding OK on an app opens, in All apps and on Home: a card over the screen with the app's name, its place in
 * favourites (a favourite moves earlier or later; any other app can take a favourite's place, since Home always has
 * four), Uninstall for apps the user installed, Details and Cancel. Uninstall and Details open Android's own screens:
 * its uninstall confirmation, and App info, which lists the app's permissions (no public intent goes straight to
 * them). Up and Down move, OK picks, Back cancels.
 */
final class AppMenu {

    private final Screen s;
    private final String pkg;
    /** Home's four, in order. */
    private final List<String> favs;
    /** After a new favourites order is saved: the screen rebuilds. */
    private final Runnable arranged;
    /** Whenever the menu goes, before the chosen action runs: the screen focuses the tile again. */
    private final Runnable closed;
    private final FrameLayout view;
    private final List<FocusTile> items = new ArrayList<>();
    private final List<Runnable> actions = new ArrayList<>();
    /** An OK press that began inside the menu: only its release picks an item, never the release of the long press
     *  that opened the menu, nor a stray release whose press went elsewhere (to the screensaver, say). */
    private boolean okPressed;

    AppMenu(Screen s, String pkg, List<String> favs, Runnable arranged, Runnable closed) {
        this.s = s;
        this.pkg = pkg;
        this.favs = favs;
        this.arranged = arranged;
        this.closed = closed;
        Text t = s.t;
        List<String> labels = new ArrayList<>();
        int at = favs.indexOf(pkg);
        if (at >= 0) {
            if (at > 0) { labels.add(t.moveEarlier()); actions.add(() -> swapFav(at, at - 1)); }
            if (at < favs.size() - 1) { labels.add(t.moveLater()); actions.add(() -> swapFav(at, at + 1)); }
        } else {
            for (int j = 0; j < favs.size(); j++) {
                final int slot = j;
                labels.add(t.putInPlaceOf(Apps.name(s, t, favs.get(j))));
                actions.add(() -> replaceFav(slot));
            }
        }
        if (Apps.removable(s, pkg)) {
            labels.add(t.uninstall());
            actions.add(() -> {
                // Read the apps again on the way back, however quickly the uninstall goes.
                Apps.forget();
                s.startSafely(new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + pkg)));
            });
        }
        labels.add(t.details());
        actions.add(() -> s.startSafely(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))));
        labels.add(t.cancel());
        actions.add(null);

        view = new FrameLayout(s);
        view.setBackgroundColor(Tokens.SCRIM_MENU);
        view.setClickable(true);
        LinearLayout card = new LinearLayout(s);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setClipChildren(false);
        // The focused item's ring reaches past the card's padding.
        card.setClipToPadding(false);
        card.setPadding(Ui.ipx(48), Ui.ipx(40), Ui.ipx(48), Ui.ipx(40));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Tokens.LAPIS_800);
        bg.setCornerRadius(Ui.px(24));
        bg.setStroke(Ui.ipx(2), Tokens.GOLD_400_60);
        card.setBackground(bg);
        TextView title = s.text(s.displayBold, 48, Tokens.GOLD_400);
        String name = Apps.name(s, t, pkg);
        title.setText(name);
        title.setTextDirection(Apps.isLatin(name) ? View.TEXT_DIRECTION_LTR : View.TEXT_DIRECTION_RTL);
        card.addView(title);
        Screen.lineHeight(title, 1.2f);
        for (int i = 0; i < labels.size(); i++) {
            FocusTile item = new FocusTile(s, 36);
            item.setFocusScale(1.04f);
            item.setRest(Tokens.LAPIS_700, 0, 0);
            TextView l = s.text(s.ui400, 26, Tokens.PLASTER_100);
            l.setText(labels.get(i));
            l.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
            l.setPadding(Ui.ipx(32), 0, Ui.ipx(32), 0);
            item.addView(l, new FrameLayout.LayoutParams(-1, -1));
            item.setLook(f -> l.setTextColor(f ? Tokens.LAPIS_900 : Tokens.PLASTER_100));
            float r = FocusTile.REACH;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.ipx(72 + 2 * r));
            lp.topMargin = Ui.ipx(i == 0 ? 24 - r : 12 - 2 * r);
            card.addView(item, lp);
            items.add(item);
        }
        view.addView(card, new FrameLayout.LayoutParams(Ui.ipx(820), -2, Gravity.CENTER));
        s.root.addView(view, new FrameLayout.LayoutParams(-1, -1));
        items.get(0).requestFocus();
    }

    /** Every key while the menu is open; none reaches the screen behind it. */
    boolean key(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (k == KeyEvent.KEYCODE_BACK) {
            if (!down) close(-1);
            return true;
        }
        if (k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (down) {
                // Repeats of the long press's own key (boards that repeat it) must not arm the menu.
                if (e.getRepeatCount() == 0) okPressed = true;
                return true;
            }
            if (!okPressed) return true;
            close(items.indexOf(s.getCurrentFocus()));
            return true;
        }
        if (down && (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN)) {
            int i = items.indexOf(s.getCurrentFocus());
            int to = Math.max(0, Math.min(items.size() - 1, i + (k == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1)));
            items.get(to).requestFocus();
        }
        return true;
    }

    /** Takes the menu away, then runs the chosen action (the last item is Cancel); -1 is Back. */
    void close(int choice) {
        s.root.removeView(view);
        closed.run();
        if (choice >= 0 && choice < actions.size() - 1) actions.get(choice).run();
    }

    private void swapFav(int a, int b) {
        List<String> f = new ArrayList<>(favs);
        String x = f.get(a);
        f.set(a, f.get(b));
        f.set(b, x);
        Apps.saveFavourites(s, f);
        arranged.run();
    }

    private void replaceFav(int slot) {
        List<String> f = new ArrayList<>(favs);
        f.set(slot, pkg);
        Apps.saveFavourites(s, f);
        arranged.run();
    }
}
