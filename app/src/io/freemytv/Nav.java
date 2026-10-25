package io.freemytv;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * How a key from the phone reaches one of our own screens. The server is in the main process and the home screen
 * and settings are in :home, so the key travels as an ordered broadcast to this app only: a screen that is on
 * show (between onResume and onPause) has a receiver registered, plays the key into its own window as a normal
 * key press, and answers "handled". Nobody registered means another app is in front, and the sender knows.
 * The permission is a signature one, so no other app can send keys to us or listen to them (before Android 13 a
 * dynamic receiver is otherwise open to every app).
 */
public final class Nav {
    private Nav() {}

    static final String ACTION = "io.freemytv.NAV_KEY";
    static final String PERMISSION = "io.freemytv.permission.ROOM_NAV";
    private static final String KEY = "keyCode";
    private static final int HANDLED = 7;

    private static BroadcastReceiver current;
    private static Activity shown;

    /** Call from onResume of every screen that should take keys from the phone. Does nothing in a plain build. */
    public static synchronized void attach(final Activity a) {
        if (!Room.available(a)) return;
        detach(a);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                if (!isOrderedBroadcast()) return;
                int code = i.getIntExtra(KEY, KeyEvent.KEYCODE_UNKNOWN);
                if (code == KeyEvent.KEYCODE_UNKNOWN || a.isFinishing()) return;
                long now = SystemClock.uptimeMillis();
                boolean down = a.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
                boolean up = a.dispatchKeyEvent(new KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0));
                // A screen that ignores a key still counts as having received it: the phone's press reached the screen.
                setResultCode(HANDLED);
                AppLog.w("nav key " + code + (down || up ? " used" : " ignored") + " by " + a.getClass().getSimpleName());
            }
        };
        IntentFilter f = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(r, f, PERMISSION, null, Context.RECEIVER_NOT_EXPORTED);
        else a.registerReceiver(r, f, PERMISSION, null);
        current = r;
        shown = a;
    }

    /** Call from onPause. Only the screen that attached can detach, so a new screen's receiver isn't removed by the old one's pause. */
    public static synchronized void detach(Activity a) {
        if (current == null || shown != a) return;
        try { a.unregisterReceiver(current); } catch (RuntimeException ignored) {}
        current = null;
        shown = null;
    }

    /**
     * Plays a key into whichever of our screens is on show. True when one took it. Blocks the calling thread for a
     * moment (the server's), never the main thread: an app that has no screen showing answers at once.
     */
    public static boolean send(Context c, int keyCode) {
        final boolean[] handled = {false};
        final CountDownLatch done = new CountDownLatch(1);
        Intent i = new Intent(ACTION).setPackage(c.getPackageName()).putExtra(KEY, keyCode);
        c.sendOrderedBroadcast(i, PERMISSION, new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {
                handled[0] = getResultCode() == HANDLED;
                done.countDown();
            }
        }, null, 0, null, null);
        try {
            done.await(500, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return handled[0];
    }
}
