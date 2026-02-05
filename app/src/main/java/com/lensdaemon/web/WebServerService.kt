package com.lensdaemon.web

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lensdaemon.LensDaemonApp
import com.lensdaemon.MainActivity
import com.lensdaemon.R
import com.lensdaemon.camera.CameraService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Foreground service for the embedded HTTP web server.
 * Serves the dashboard UI and REST API endpoints.
 */
class WebServerService : Service() {

    companion object {
        private const val TAG = "WebServerService"
        const val DEFAULT_PORT = 8080
        private const val NOTIFICATION_ID = 3001

        // Intent extras
        const val EXTRA_PORT = "port"

        /**
         * Create intent to start web server
         */
        fun createStartIntent(context: Context, port: Int = DEFAULT_PORT): Intent {
            return Intent(context, WebServerService::class.java).apply {
                putExtra(EXTRA_PORT, port)
            }
        }
    }

    private val binder = WebServerBinder()

    inner class WebServerBinder : Binder() {
        fun getService(): WebServerService = this@WebServerService
    }

    // Coroutine scope
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Web server components
    private var webServer: WebServer? = null
    private var apiRoutes: ApiRoutes? = null
    private var mjpegStreamer: MjpegStreamer? = null

    // Camera service connection
    private var cameraService: CameraService? = null
    private var cameraBound = false

    private val cameraConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.LocalBinder
            cameraService = binder.getService()
            cameraBound = true

            // Connect camera service to API routes
            apiRoutes?.cameraService = cameraService

            Timber.i("$TAG: CameraService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            cameraBound = false
            apiRoutes?.cameraService = null
            Timber.i("$TAG: CameraService disconnected")
        }
    }

    // State
    private val _serverState = MutableStateFlow(WebServerState.STOPPED)
    val serverState: StateFlow<WebServerState> = _serverState.asStateFlow()

    private var port = DEFAULT_PORT

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Timber.i("$TAG: Service created")

        // Initialize components
        apiRoutes = ApiRoutes(this)
        mjpegStreamer = MjpegStreamer()

        // Bind to camera service
        bindCameraService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        port = intent?.getIntExtra(EXTRA_PORT, DEFAULT_PORT) ?: DEFAULT_PORT

        Timber.i("$TAG: Starting service on port $port")
        startForeground(NOTIFICATION_ID, createNotification("Starting..."))

        startServer()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        stopServer()
        unbindCameraService()
        serviceScope.cancel()
        Timber.i("$TAG: Service destroyed")
    }

    /**
     * Start the web server
     */
    private fun startServer() {
        if (webServer?.isRunning() == true) {
            Timber.w("$TAG: Server already running")
            return
        }

        try {
            webServer = WebServer(this, port)
            webServer?.setApiRoutes(apiRoutes!!)
            webServer?.setMjpegStreamer(mjpegStreamer!!)

            val success = webServer?.startServer() ?: false

            if (success) {
                _serverState.value = WebServerState.RUNNING
                updateNotification("Running on port $port")
                Timber.i("$TAG: Web server started - ${webServer?.getServerUrl()}")
            } else {
                _serverState.value = WebServerState.ERROR
                updateNotification("Failed to start")
                Timber.e("$TAG: Failed to start web server")
            }

        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error starting web server")
            _serverState.value = WebServerState.ERROR
            updateNotification("Error: ${e.message}")
        }
    }

    /**
     * Stop the web server
     */
    private fun stopServer() {
        webServer?.stopServer()
        mjpegStreamer?.stop()
        webServer = null
        _serverState.value = WebServerState.STOPPED
        Timber.i("$TAG: Web server stopped")
    }

    /**
     * Bind to camera service
     */
    private fun bindCameraService() {
        val intent = Intent(this, CameraService::class.java)
        bindService(intent, cameraConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Unbind from camera service
     */
    private fun unbindCameraService() {
        if (cameraBound) {
            unbindService(cameraConnection)
            cameraBound = false
        }
    }

    /**
     * Create notification
     */
    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val url = webServer?.getServerUrl() ?: "http://localhost:$port"

        return NotificationCompat.Builder(this, LensDaemonApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("LensDaemon Web Server")
            .setContentText("$status - $url")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * Update notification
     */
    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }

    // ==================== Public API ====================

    /**
     * Get server port
     */
    fun getPort(): Int = port

    /**
     * Get server URL
     */
    fun getServerUrl(): String? = webServer?.getServerUrl()

    /**
     * Check if server is running
     */
    fun isRunning(): Boolean = webServer?.isRunning() ?: false

    /**
     * Get server statistics
     */
    fun getStats(): WebServerStats? = webServer?.getStats()

    /**
     * Get MJPEG streamer for pushing frames
     */
    fun getMjpegStreamer(): MjpegStreamer? = mjpegStreamer

    /**
     * Push JPEG frame to MJPEG stream
     */
    fun pushMjpegFrame(jpegData: ByteArray) {
        mjpegStreamer?.pushFrame(jpegData)
    }

    /**
     * Get number of MJPEG clients
     */
    fun getMjpegClientCount(): Int = mjpegStreamer?.getClientCount() ?: 0

    /**
     * Set snapshot callback
     */
    fun setSnapshotCallback(callback: () -> ByteArray?) {
        apiRoutes?.onSnapshotRequest = callback
    }
}
