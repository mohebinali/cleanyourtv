package tv.afnon.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

/**
 * The Cast receiver kept awake. With another home in front of Google TV's own, nothing holds the Cast receiver
 * (com.google.android.apps.mediashell): it is a background app, and Android 14 freezes those. Frozen it answers no
 * phone, so YouTube on a phone finds nothing to cast to, until something happens to thaw it (2026-11-19: frozen
 * 22:09:05, 22:11:30 and 22:13:30, the last until 22:30:42; its port took a connection and never answered it). A
 * binding from this always-running service keeps it out of the background, so it is never frozen. Nothing is sent
 * over the binding.
 */
final class CastKeeper implements ServiceConnection {
    private static final String TAG = "CastKeeper";
    static final String CAST = "com.google.android.apps.mediashell";
    /** What the receiver's service answers to (its manifest). */
    private static final String BIND = "com.google.cast.action.BIND";
    private static final long REBIND_MS = 5_000;

    private final Context context;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable bind = this::bind;
    private boolean bound, told;

    CastKeeper(Context context) {
        this.context = context;
    }

    void start() {
        bind();
    }

    void stop() {
        handler.removeCallbacks(bind);
        unbind();
    }

    private void bind() {
        if (bound) return;
        Intent intent = new Intent(BIND).setPackage(CAST);
        ResolveInfo service = context.getPackageManager().resolveService(intent, 0);
        if (service == null || service.serviceInfo == null) {
            Log.i(TAG, "no Cast receiver here to keep awake");
            return;
        }
        intent.setComponent(new ComponentName(service.serviceInfo.packageName, service.serviceInfo.name));
        try {
            bound = context.bindService(intent, this, Context.BIND_AUTO_CREATE);
        } catch (SecurityException e) {
            bound = false;
        }
        if (!bound) {
            // Android wants a failed bind released too.
            bound = true;
            unbind();
            Events.log(context, TAG, "the Cast receiver can't be bound: left to Android, which may freeze it");
        }
    }

    private void unbind() {
        if (!bound) return;
        bound = false;
        try {
            context.unbindService(this);
        } catch (IllegalArgumentException ignored) {
            // Not bound after all.
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        held();
    }

    @Override
    public void onNullBinding(ComponentName name) {
        // The receiver gives nothing to talk to, which isn't wanted anyway: the binding holds all the same.
        held();
    }

    private void held() {
        if (!told) Events.log(context, TAG, "Cast receiver held awake");
        told = true;
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        // The receiver's process went; Android starts it again for the binding and says so.
    }

    @Override
    public void onBindingDied(ComponentName name) {
        unbind();
        handler.removeCallbacks(bind);
        handler.postDelayed(bind, REBIND_MS);
    }
}
