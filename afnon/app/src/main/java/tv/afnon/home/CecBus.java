package tv.afnon.home;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What HDMI-CEC has said lately, from Android's own record of it: the HDMI service's dump (what `adb shell dumpsys
 * hdmi_control` prints), which ends in the history of messages received and sent. An app can't hear HDMI-CEC itself
 * (that takes a permission kept for the system), but one holding DUMP, granted over adb (README), may read that dump.
 * It tells two things nothing else does: the TV asking after its HDMI devices as it powers up, a second or two in,
 * where it selects the Chromecast only 9 to 12 s in; and the Chromecast's broadcasts being refused, which is what
 * leaves the TV's remote reaching nothing (2026-10-24, 11-05, 11-19).
 */
final class CecBus {
    private static final String TAG = "CecBus";
    private static final int MAX_DUMP_BYTES = 1 << 20;
    /** A line of the history: received or sent, "2026-11-19 22:39:22", its bytes ("04:8F"), and what follows them. */
    private static final Pattern LINE = Pattern.compile(
        "^\\s*\\[([RS])\\] time=(\\S+ \\S+) message=<[^>]*> ([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2})*)(.*)$");
    private static final int STANDBY = 0x36, REPORT_POWER_STATUS = 0x90;
    /**
     * What a TV asks or tells of its own accord, never in answer: Give Device Vendor Id, Give Device Power Status,
     * Give Physical Address, Give OSD Name, Get CEC Version, Vendor Command (With Id), Set Stream Path, Routing Change,
     * Request Active Source, User Control Pressed, Give Deck Status, Set Menu Language, Menu Request.
     */
    private static final int[] TV_ASKS =
        {0x8C, 0x8F, 0x83, 0x46, 0x9F, 0xA0, 0x89, 0x86, 0x80, 0x85, 0x44, 0x1A, 0x32, 0x8D};
    /** The latest broadcasts looked at for refusals, and how many of them make it a fact. */
    private static final int BROADCASTS = 3, REFUSED_AT_LEAST = 2;
    private static boolean warned;

    private CecBus() {}

    static final class Reading {
        /** The TV said its power is on or coming on, at or after the time asked about. */
        boolean tvSaysOn;
        /** How many things the TV asked or told of its own accord since then. */
        int tvAsked;
        /** The Chromecast's latest broadcasts were all refused, and when the first of that run went out. */
        boolean refused;
        long refusedSince;
    }

    /** The history read, for the TV's doings since a time (wall clock, ms); null where it can't be read. */
    static Reading read(Context context, long since) {
        if (context.checkSelfPermission(Manifest.permission.DUMP) != PackageManager.PERMISSION_GRANTED) return null;
        String dump = dump();
        int at = dump == null ? -1 : dump.indexOf("CEC message history:");
        if (at < 0) return null;
        Reading r = new Reading();
        List<long[]> broadcasts = new ArrayList<>();
        for (String line : dump.substring(at).split("\n")) {
            Matcher m = LINE.matcher(line);
            if (!m.matches()) continue;
            String[] hex = m.group(3).split(":");
            if (hex.length < 2) continue;
            int header = Integer.parseInt(hex[0], 16), opcode = Integer.parseInt(hex[1], 16);
            long time = time(m.group(2));
            if ("R".equals(m.group(1))) {
                if (header >> 4 != 0 || time < since || opcode == STANDBY) continue;
                if (opcode == REPORT_POWER_STATUS) {
                    // 00 on, 02 on its way on; 01 standby and 03 on its way there say it is still off.
                    int power = hex.length > 2 ? Integer.parseInt(hex[2], 16) : -1;
                    if (power == 0 || power == 2) r.tvSaysOn = true;
                } else if (asks(opcode)) {
                    r.tvAsked++;
                }
            } else if ((header & 0xF) == 0xF) {
                // A broadcast is refused where every try ended in NACK: some device on the TV's HDMI said no to it.
                // One still on its way has no result yet.
                String result = m.group(4);
                if (!result.contains("ACK")) continue;
                boolean nack = !result.replace("NACK", "").contains("ACK");
                broadcasts.add(new long[] {time, nack ? 1 : 0});
            }
        }
        int n = broadcasts.size(), refused = 0;
        for (int i = Math.max(0, n - BROADCASTS); i < n; i++) refused += (int) broadcasts.get(i)[1];
        r.refused = n >= REFUSED_AT_LEAST && refused == Math.min(n, BROADCASTS);
        if (r.refused) {
            int first = n - 1;
            while (first > 0 && broadcasts.get(first - 1)[1] == 1) first--;
            r.refusedSince = broadcasts.get(first)[0];
        }
        return r;
    }

    private static boolean asks(int opcode) {
        for (int o : TV_ASKS) if (o == opcode) return true;
        return false;
    }

    /** "2026-11-19 22:39:22" as wall-clock ms, whatever digits the TV's language writes it in; -1 if it isn't one. */
    private static long time(String s) {
        int[] f = new int[6];
        int n = 0;
        boolean inNumber = false;
        for (int i = 0; i < s.length(); i++) {
            int d = Character.digit(s.charAt(i), 10);
            if (d < 0) {
                inNumber = false;
                continue;
            }
            if (!inNumber) {
                if (++n > 6) return -1;
                inNumber = true;
            }
            f[n - 1] = f[n - 1] * 10 + d;
        }
        if (n != 6) return -1;
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(f[0], f[1] - 1, f[2], f[3], f[4], f[5]);
        return c.getTimeInMillis();
    }

    /**
     * The HDMI service's dump. android.os.ServiceManager is not in the SDK, so it is reached by name; the service
     * writes the dump into a pipe, read here to its end.
     */
    private static String dump() {
        ParcelFileDescriptor[] pipe = null;
        try {
            IBinder hdmi = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "hdmi_control");
            if (hdmi == null) return null;
            pipe = ParcelFileDescriptor.createPipe();
            hdmi.dumpAsync(pipe[1].getFileDescriptor(), new String[0]);
            pipe[1].close();
            ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
            try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0])) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0 && out.size() < MAX_DUMP_BYTES) out.write(buffer, 0, n);
            }
            return out.toString("UTF-8");
        } catch (ReflectiveOperationException | IOException | RemoteException | RuntimeException e) {
            if (!warned) Log.w(TAG, "HDMI-CEC history not readable", e);
            warned = true;
            if (pipe != null) {
                for (ParcelFileDescriptor end : pipe) {
                    try {
                        end.close();
                    } catch (IOException ignored) {
                        // Closed already.
                    }
                }
            }
            return null;
        }
    }
}
