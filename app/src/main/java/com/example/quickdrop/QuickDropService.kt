package com.example.quickdrop

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.util.ArrayList

class QuickDropService : Service() {

    private var server: WebServer? = null
    private val CHANNEL_ID = "QuickDropChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification()
        startForeground(1, notification)

        // Extract data
        val sharedUris = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableArrayListExtra("SHARED_URIS", Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableArrayListExtra("SHARED_URIS")
        }

        val isPrivate = intent?.getBooleanExtra("IS_PRIVATE", true) ?: true

        startServer(sharedUris, isPrivate)

        return START_STICKY
    }

    private fun startServer(sharedUris: ArrayList<Uri>?, isPrivate: Boolean) {
        server?.stopServer()

        val rootDir = Environment.getExternalStorageDirectory()

        server = WebServer(5000, contentResolver, sharedUris, rootDir, isPrivate)
        server?.start()
    }

    override fun onDestroy() {
        server?.stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "QuickDrop File Sharing Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        // Fix: Explicitly refer to MainActivity class
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("QuickDrop is Running")
            .setContentText("File sharing active. Background service running.")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }
}