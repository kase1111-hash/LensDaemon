package com.lensdaemon.storage

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lensdaemon.LensDaemonApp
import com.lensdaemon.MainActivity
import com.lensdaemon.R
import timber.log.Timber

/**
 * Background service for uploading recorded files to network storage.
 * Handles SMB/NFS shares and S3-compatible object storage.
 *
 * Implementation will be completed in Phase 8.
 */
class UploadService : Service() {

    private val binder = LocalBinder()
    private var isUploading = false

    inner class LocalBinder : Binder() {
        fun getService(): UploadService = this@UploadService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Timber.i("UploadService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("UploadService started")
        startForeground(LensDaemonApp.NOTIFICATION_ID + 2, createNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("UploadService destroyed")
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, LensDaemonApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText("Upload service running")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // ==================== Upload API ====================
    // These methods will be implemented in Phase 8

    fun queueUpload(filePath: String) {
        Timber.i("queueUpload($filePath) - Not yet implemented")
        // TODO: Phase 8 - Add file to upload queue
    }

    fun getQueueSize(): Int = 0 // TODO: Return actual queue size

    fun isUploading(): Boolean = isUploading
}
