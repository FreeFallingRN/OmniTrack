package com.omnitrack.app.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.omnitrack.app.receiver.QuickActionReceiver

class ActionNotificationService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val seriesId = intent?.getStringExtra("SERIES_ID") ?: return START_NOT_STICKY
        val title = intent.getStringExtra("TITLE") ?: "Reading Session"
        val currentUnit = intent.getDoubleExtra("CURRENT_UNIT", 0.0)

        val notification = buildNotification(this, seriesId, currentUnit, title)
        startForeground(NOTIF_ID, notification)
        return START_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "omnitrack_active_session"
        private const val NOTIF_ID = 1001

        fun updateNotification(context: Context, seriesId: String, currentUnit: Double, title: String = "Reading Session") {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createChannel(manager)
            val notification = buildNotification(context, seriesId, currentUnit, title)
            manager.notify(NOTIF_ID, notification)
        }

        private fun buildNotification(context: Context, seriesId: String, currentUnit: Double, title: String): Notification {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createChannel(manager)

            val plusOneIntent = Intent(context, QuickActionReceiver::class.java).apply {
                putExtra("SERIES_ID", seriesId)
                putExtra("CURRENT_UNIT", currentUnit)
                putExtra("DELTA", 1.0)
            }
            val plusOnePending = PendingIntent.getBroadcast(
                context, 1, plusOneIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val minusOneIntent = Intent(context, QuickActionReceiver::class.java).apply {
                putExtra("SERIES_ID", seriesId)
                putExtra("CURRENT_UNIT", currentUnit)
                putExtra("DELTA", -1.0)
            }
            val minusOnePending = PendingIntent.getBroadcast(
                context, 2, minusOneIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(title)
                .setContentText("Current Progress: Ch. ${currentUnit.toInt()}")
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .addAction(android.R.drawable.ic_input_add, "+1 Ch", plusOnePending)
                .addAction(android.R.drawable.ic_delete, "-1 Ch", minusOnePending)
                .build()
        }

        private fun createChannel(manager: NotificationManager) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Active Tracking Session",
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }
    }
}