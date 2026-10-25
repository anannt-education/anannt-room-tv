package io.freemytv;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * The bridge to the Room TV additions (io.freemytv.room, Kotlin, only in the Gradle :tv build): the LAN server for
 * the phone companion and its two screens. Everything is reached by class name, so a plain build.sh build, which
 * has none of it, still compiles and simply shows no Room rows.
 */
final class Room {
    private Room() {}

    static final String SERVICE = "io.freemytv.room.RoomService";
    static final String PAIRING = "io.freemytv.room.PairingActivity";
    static final String PAIRED = "io.freemytv.room.PairedPhonesActivity";
    static final String NAME = "io.freemytv.room.RoomNameActivity";
    static final String REMOTE = "io.freemytv.room.RemoteAccessActivity";

    private static Boolean present;

    /** True when this APK carries the Room TV service. */
    static synchronized boolean available(Context c) {
        if (present == null) {
            try {
                present = c.getPackageManager().resolveService(new Intent().setClassName(c, SERVICE), PackageManager.MATCH_ALL) != null;
            } catch (Exception e) {
                present = false;
            }
        }
        return present;
    }

    /** Starts the LAN server as a foreground service in the main process; does nothing in a plain build. */
    static void start(Context c) {
        if (!available(c)) return;
        try {
            c.startForegroundService(new Intent().setClassName(c, SERVICE));
        } catch (Exception e) {
            AppLog.w("room service: " + e);
        }
    }

    /** Opens one of the Room screens (PAIRING, PAIRED, NAME or REMOTE). */
    static void open(Context c, String activity) {
        try {
            c.startActivity(new Intent().setClassName(c, activity).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            AppLog.w("room screen: " + e);
        }
    }
}
