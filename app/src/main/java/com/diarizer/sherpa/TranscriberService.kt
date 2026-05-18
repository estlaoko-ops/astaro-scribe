package com.diarizer.sherpa

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

class TranscriberService : Service() {

    private val TAG = "TranscriberSvc"
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "transcriber_processing"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PROCESSING = "com.diarizer.sherpa.PROCESSING"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Acquire wake lock so CPU stays on during processing
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TranscriberService:ProcessingLock"
        )
        wakeLock?.acquire(30 * 60 * 1000L) // max 30 min
        Log.i(TAG, "Service created, wake lock acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("Обработка аудио...")
        startForeground(NOTIFICATION_ID, notification)
        Log.i(TAG, "Foreground service started")
        // Don't restart if killed - ProcessingService handles lifecycle
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.i(TAG, "Wake lock released")
            }
        }
        Log.i(TAG, "Service destroyed")
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Транскрибация в фоне",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Уведомление о фоновой обработке аудио"
            setShowBadge(false)
        }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Transcriber")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setProgress(0, 0, true) // indeterminate spinner
            .build()
    }

    fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }
}
