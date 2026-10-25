package tv.afnon.home;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The apps Afnon shows: which exist, their names and icons, launching, which can be uninstalled, and the saved
 * favourites order.
 */
final class Apps {
    private Apps() {}

    static final int FAVOURITE_SLOTS = 4;

    private static final String[] DEFAULT_FAVOURITES = {
        "com.google.android.youtube.tv", "net.mbc.shahidTV", "dev.ctv.tv", "com.tcl.browser"};
    /** Home's "More" row picks these first (minus favourites), then fills from the rest. */
    static final String[] MORE_FIRST = {
        "com.google.android.youtube.tvmusic", "com.android.vending", "com.tcl.ui_mediaCenter",
        "com.alphainventor.filemanager", "com.sami.salaty_tv"};
    /** Never listed: this launcher, the previous one, and the TV's own input/Live TV plumbing. */
    private static final Set<String> HIDDEN = new HashSet<>(Arrays.asList(
        "tv.afnon.home", "com.gathin.tvhome", "com.tcl.suspension", "com.tcl.tv"));

    private static final Map<String, String> AR_NAMES = new HashMap<>();
    private static final Map<String, String> EN_NAMES = new HashMap<>();
    static {
        AR_NAMES.put("com.google.android.youtube.tv", "يوتيوب");
        AR_NAMES.put("net.mbc.shahidTV", "شاهد");
        AR_NAMES.put("com.google.android.youtube.tvmusic", "يوتيوب ميوزك");
        AR_NAMES.put("com.android.vending", "متجر Play");
        AR_NAMES.put("com.tcl.ui_mediaCenter", "مشغل الوسائط");
        AR_NAMES.put("com.sami.salaty_tv", "صلاتي");
        AR_NAMES.put("com.android.tv.settings", "الإعدادات");
        AR_NAMES.put("com.google.android.play.games", "ألعاب Google Play");
        EN_NAMES.put("com.google.android.youtube.tv", "YouTube");
        EN_NAMES.put("net.mbc.shahidTV", "Shahid");
        EN_NAMES.put("com.android.vending", "Play Store");
    }

    private static List<String> cached;
    private static long cachedAt;

    /**
     * Every app that has a TV or a phone-style launcher entry, one per package, not hidden. Cached for two
     * seconds: one screen build asks several times, and each query is slow on this TV.
     */
    static synchronized List<String> installed(Context c) {
        long now = android.os.SystemClock.uptimeMillis();
        if (cached != null && now - cachedAt < 2000) return new ArrayList<>(cached);
        PackageManager pm = c.getPackageManager();
        Set<String> found = new LinkedHashSet<>();
        for (String category : new String[] {Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo r : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                String p = r.activityInfo.packageName;
                if (!HIDDEN.contains(p)) found.add(p);
            }
        }
        cached = new ArrayList<>(found);
        cachedAt = now;
        return new ArrayList<>(cached);
    }

    /** Drops the cached list, so the next look sees an app that was just uninstalled. */
    static synchronized void forget() {
        cached = null;
    }

    /**
     * Changes when favourites are rearranged or apps come or go; Home and All apps rebuild when it does. The packages
     * themselves, sorted, not their count: one install and one uninstall while away would leave the count the same.
     */
    static String key(Context c) {
        return favourites(c) + "|" + new TreeSet<>(installed(c));
    }

    /** Favourites first, in their saved order; then the rest by name in the screen's language. */
    static List<String> sorted(Context c, Text t) {
        List<String> all = installed(c);
        List<String> favs = favourites(c);
        List<String> rest = new ArrayList<>();
        for (String p : all) if (!favs.contains(p)) rest.add(p);
        Collator col = Collator.getInstance(t.ar ? new Locale("ar") : Locale.ENGLISH);
        Map<String, String> names = new HashMap<>();
        for (String p : rest) names.put(p, name(c, t, p));
        rest.sort((a, b) -> col.compare(names.get(a), names.get(b)));
        List<String> out = new ArrayList<>();
        for (String p : favs) if (all.contains(p)) out.add(p);
        out.addAll(rest);
        return out;
    }

    /**
     * Home's "More" row: the preferred picks that are installed and not among favs, Home's four (so the app standing in
     * for a favourite that is not installed is not shown twice), filled up from the rest.
     */
    static List<String> more(Context c, Text t, List<String> favs, int count) {
        List<String> all = installed(c);
        List<String> out = new ArrayList<>();
        for (String p : MORE_FIRST) if (out.size() < count && all.contains(p) && !favs.contains(p)) out.add(p);
        for (String p : sorted(c, t)) if (out.size() < count && !favs.contains(p) && !out.contains(p)) out.add(p);
        return out;
    }

    // ---------------------------------------------------------------- favourites

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("afnon", Context.MODE_PRIVATE);
    }

    /** At most {@link #FAVOURITE_SLOTS}, in Home order. Uninstalled ones are kept, so a reinstall brings them back. */
    static List<String> favourites(Context c) {
        String s = prefs(c).getString("favourites", null);
        if (s == null) return new ArrayList<>(Arrays.asList(DEFAULT_FAVOURITES));
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    /** Always four for Home: the saved favourites that are installed, topped up from the rest if some went away. */
    static List<String> slots(Context c, Text t) {
        List<String> installed = installed(c);
        List<String> out = new ArrayList<>();
        for (String p : favourites(c)) if (installed.contains(p) && out.size() < FAVOURITE_SLOTS) out.add(p);
        for (String p : sorted(c, t)) if (out.size() < FAVOURITE_SLOTS && !out.contains(p)) out.add(p);
        return out;
    }

    static void saveFavourites(Context c, List<String> favs) {
        prefs(c).edit().putString("favourites", TextUtils.join(",", favs)).apply();
    }

    // ---------------------------------------------------------------- one app

    static String name(Context c, Text t, String pkg) {
        if (t.ar && AR_NAMES.containsKey(pkg)) return AR_NAMES.get(pkg);
        if (EN_NAMES.containsKey(pkg)) return EN_NAMES.get(pkg);
        try {
            PackageManager pm = c.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return pkg;
        }
    }

    static Drawable icon(Context c, String pkg) {
        try {
            return c.getPackageManager().getApplicationIcon(pkg);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Whether its menu offers Uninstall: only for apps the user installed. Not the firmware's own (FLAG_SYSTEM, which
     * their updates keep too), and never either home.
     */
    static boolean removable(Context c, String pkg) {
        if ("tv.afnon.home".equals(pkg) || "com.gathin.tvhome".equals(pkg)) return false;
        try {
            return (c.getPackageManager().getApplicationInfo(pkg, 0).flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    /** Opens the app's TV entry, or its phone-style one; back at its main screen, not wherever it was left. */
    /** The app's TV launch intent, or its phone one, or null if it isn't installed. */
    static Intent launchIntent(Context c, String pkg) {
        PackageManager pm = c.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        return i != null ? i : pm.getLaunchIntentForPackage(pkg);
    }

    static void launch(Context c, String pkg) {
        Intent i = launchIntent(c, pkg);
        if (i == null) return;
        try {
            c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // The app went away or refuses outside launches; stay where we are.
        }
    }

    static boolean isLatin(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.UnicodeBlock.of(s.charAt(i)) == Character.UnicodeBlock.ARABIC) return false;
        return true;
    }
}
