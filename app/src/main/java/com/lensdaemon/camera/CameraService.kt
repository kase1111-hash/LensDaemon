package com.lensdaemon.camera

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
 * Foreground service for camera capture operations.
 * Manages Camera2 pipeline, encoding, and streaming.
 *
 * Implementation will be completed in Phase 2-5.
 */
class CameraService : Service() {

    private val binder = LocalBinder()

    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Timber.i("CameraService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("CameraService started")
        startForeground(LensDaemonApp.NOTIFICATION_ID, createNotification())
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("CameraService destroyed")
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
            .setContentText(getString(R.string.notification_streaming))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    // ==================== Camera Control API ====================
    // These methods will be implemented in Phase 2-3

    fun startPreview() {
        Timber.i("startPreview() - Not yet implemented")
        // TODO: Phase 2 - Initialize Camera2 and start preview
    }

    fun stopPreview() {
        Timber.i("stopPreview() - Not yet implemented")
        // TODO: Phase 2 - Stop Camera2 preview
    }

    fun startStreaming() {
        Timber.i("startStreaming() - Not yet implemented")
        // TODO: Phase 4-5 - Start encoding and RTSP server
    }

    fun stopStreaming() {
        Timber.i("stopStreaming() - Not yet implemented")
        // TODO: Phase 4-5 - Stop encoding and RTSP server
    }

    fun switchLens(lens: String) {
        Timber.i("switchLens($lens) - Not yet implemented")
        // TODO: Phase 3 - Switch between wide/main/tele lenses
    }

    fun setZoom(zoomLevel: Float) {
        Timber.i("setZoom($zoomLevel) - Not yet implemented")
        // TODO: Phase 3 - Set camera zoom level
    }

    fun isStreaming(): Boolean = false // TODO: Track actual state
}
