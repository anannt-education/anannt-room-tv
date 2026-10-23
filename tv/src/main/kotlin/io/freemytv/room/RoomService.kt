package io.freemytv.room

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import io.freemytv.R

/**
 * Keeps the LAN server alive as a foreground service in the main process, so a phone can still reach the TV while
 * another app (Zoom, from M2) is in front. The notification is required by Android; TVs don't show it.
 */
class RoomService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        RoomRuntime.start(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        RoomRuntime.stop()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Room TV", NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Anannt Room TV")
            .setContentText("Phone companion ready on this network")
            .setSmallIcon(R.drawable.icon)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "room"
        private const val NOTIFICATION_ID = 8787

        fun start(c: Context) {
            runCatching { c.startForegroundService(Intent(c, RoomService::class.java)) }
        }
    }
}
