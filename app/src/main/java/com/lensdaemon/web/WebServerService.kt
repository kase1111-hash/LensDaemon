package com.lensdaemon.web

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
 * Foreground service for the embedded HTTP web server.
 * Serves the dashboard UI and REST API endpoints.
 *
 * Implementation will be completed in Phase 6.
 */
class WebServerService : Service() {

    companion object {
        const val DEFAULT_PORT = 8080
    }

    private val binder = LocalBinder()
    private var isRunning = false
    private var port = DEFAULT_PORT

    inner class LocalBinder : Binder() {
        fun getService(): WebServerService = this@WebServerService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Timber.i("WebServerService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("WebServerService started")
        startForeground(LensDaemonApp.NOTIFICATION_ID + 1, createNotification())
        startServer()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        Timber.i("WebServerService destroyed")
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
            .setContentText("Web server running on port $port")
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun startServer() {
        if (isRunning) return
        Timber.i("Starting web server on port $port")
        // TODO: Phase 6 - Initialize NanoHTTPD server
        isRunning = true
    }

    private fun stopServer() {
        if (!isRunning) return
        Timber.i("Stopping web server")
        // TODO: Phase 6 - Stop NanoHTTPD server
        isRunning = false
    }

    fun getPort(): Int = port
    fun isRunning(): Boolean = isRunning
}
