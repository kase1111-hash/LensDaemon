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
import com.lensdaemon.BuildConfig
import com.lensdaemon.camera.CameraService
import com.lensdaemon.director.CameraControllerAdapter
import com.lensdaemon.director.DirectorManager
import com.lensdaemon.director.QualityMetricsCollector
import com.lensdaemon.director.asMetricsSink
import com.lensdaemon.director.asMetricsSource
import com.lensdaemon.storage.UploadService
import com.lensdaemon.thermal.ThermalService
import com.lensdaemon.thermal.ThrottleAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * Foreground service for the embedded HTTP web server.
 * Serves the dashboard UI and REST API endpoints.
 *
 * It also connects the services: the thermal governor's throttling reaches the
 * camera through here (a bitrate cut, lower resolution and frame rate at
 * CRITICAL, and at EMERGENCY a pause that restarts the same outputs once the
 * phone has cooled down), and the AI Director's take scoring samples the
 * camera while a take runs.
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

    // Thermal throttling calls into the camera run here, one at a time and in the order
    // the governor made them: they may block while the encoder restarts, so never on Main
    private val thermalDispatcher: ExecutorCoroutineDispatcher =
        Executors.newSingleThreadExecutor { task -> Thread(task, "LensDaemon-thermal") }.asCoroutineDispatcher()

    // Web server components
    private var webServer: WebServer? = null
    private var apiRoutes: ApiRoutes? = null
    private var mjpegStreamer: MjpegStreamer? = null

    // AI Director components
    private var directorManager: DirectorManager? = null
    private var cameraControllerAdapter: CameraControllerAdapter? = null
    private var qualityMetricsCollector: QualityMetricsCollector? = null

    // Starts and stops the quality metrics collection with the director's takes
    private var takeWatchJob: Job? = null

    // Camera service connection; read on the thermal thread too
    @Volatile
    private var cameraService: CameraService? = null
    private var cameraBound = false

    private val cameraConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.LocalBinder
            cameraService = binder.getService()
            cameraBound = true

            // Connect camera service to API routes
            apiRoutes?.cameraService = cameraService

            // Live preview and snapshots for the dashboard: frames are only
            // converted to JPEG while the MJPEG stream has viewers or a
            // snapshot is pending.
            cameraService?.setPreviewFrameSink(
                demand = { (mjpegStreamer?.getClientCount() ?: 0) > 0 },
                sink = { jpeg -> mjpegStreamer?.pushFrame(jpeg) }
            )
            setSnapshotCallback { cameraService?.captureSnapshot() }

            // Set up AI Director integration
            setupDirectorIntegration()

            // Throttling the governor applied before the camera was connected
            pushThermalStateToCamera()

            Timber.i("$TAG: CameraService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService?.clearPreviewFrameSink()
            apiRoutes?.onSnapshotRequest = null
            cameraService = null
            cameraBound = false
            apiRoutes?.cameraService = null

            // Clean up director integration
            cleanupDirectorIntegration()

            Timber.i("$TAG: CameraService disconnected")
        }
    }

    // Upload service connection
    private var uploadService: UploadService? = null
    private var uploadBound = false

    private val uploadConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as UploadService.UploadBinder
            uploadService = binder.getService()
            uploadBound = true

            // Connect upload service to API routes
            apiRoutes?.uploadService = uploadService

            Timber.i("$TAG: UploadService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            uploadService = null
            uploadBound = false
            apiRoutes?.uploadService = null
            Timber.i("$TAG: UploadService disconnected")
        }
    }

    // Thermal service connection
    private var thermalService: ThermalService? = null
    private var thermalBound = false

    private val thermalConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as ThermalService.ThermalBinder
            val thermal = binder.getService()
            thermalService = thermal
            thermalBound = true

            // Connect thermal governor to API routes
            apiRoutes?.thermalGovernor = thermal.getGovernor()

            // Throttling from now on, and what the governor applied before it was connected
            wireThermalCallbacks(thermal)
            pushThermalStateToCamera()

            Timber.i("$TAG: ThermalService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            thermalService?.let { clearThermalCallbacks(it) }
            thermalService = null
            thermalBound = false
            apiRoutes?.thermalGovernor = null

            // Nothing watches the temperatures any more: lift every throttle, as the governor does when it stops
            launchThermal { camera ->
                camera.setThermalBitrateReduction(0)
                camera.setThermalEncoderLimits(reduceResolution = false, reduceFramerate = false)
                camera.resumeAfterThermal()
            }
            Timber.i("$TAG: ThermalService disconnected")
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

        // Wire rate limiter (60 burst, 10/sec refill)
        apiRoutes?.rateLimiter = RateLimiter()

        // Load API token from prefs (null = auth disabled for first-boot setup)
        val prefs = getSharedPreferences("lensdaemon_security", MODE_PRIVATE)
        apiRoutes?.apiToken = prefs.getString("api_token", null)

        // Kiosk mode (/api/kiosk/*, used by the setup wizard and the dashboard)
        apiRoutes?.kioskManager = (application as LensDaemonApp).kioskManager

        // Bind to camera service
        bindCameraService()

        // Bind to upload service
        bindUploadService()

        // Bind to thermal service
        bindThermalService()
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
        cleanupDirectorIntegration()
        unbindCameraService()
        unbindUploadService()
        unbindThermalService()
        serviceScope.cancel()
        thermalDispatcher.close()
        Timber.i("$TAG: Service destroyed")
    }

    // ==================== Thermal throttling ====================

    /**
     * Pass the governor's throttling on to the camera, each change on the
     * thermal thread in the order the governor made it: the bitrate cut, the
     * CRITICAL resolution and frame-rate limits, and the EMERGENCY pause,
     * which stops every output and restarts the same ones once the phone has
     * cooled down (the governor resumes only after the hysteresis, and only
     * when neither the CPU nor the battery is at EMERGENCY).
     */
    private fun wireThermalCallbacks(thermal: ThermalService) {
        thermal.onReduceBitrate = { percent -> launchThermal { it.setThermalBitrateReduction(percent) } }
        thermal.onEncoderLimits = { resolution, framerate ->
            launchThermal { it.setThermalEncoderLimits(resolution, framerate) }
        }
        thermal.onPauseStreaming = { launchThermal { it.pauseForThermal() } }
        thermal.onResumeStreaming = { launchThermal { it.resumeAfterThermal() } }
        thermal.onRestoreSettings = { Timber.i("$TAG: Thermal throttling lifted") }
    }

    private fun clearThermalCallbacks(thermal: ThermalService) {
        thermal.onReduceBitrate = null
        thermal.onEncoderLimits = null
        thermal.onPauseStreaming = null
        thermal.onResumeStreaming = null
        thermal.onRestoreSettings = null
    }

    /**
     * Bring the camera in line with the governor's current throttling: the
     * governor reports changes only as they happen, so those made before both
     * services were connected would otherwise be lost. A pause goes first and
     * a resume last, as the governor orders them, so outputs aren't restarted
     * only to be stopped and resumed ones start within the limits.
     */
    private fun pushThermalStateToCamera() {
        val thermal = thermalService ?: return
        if (cameraService == null) return
        val status = thermal.getStatus()
        val paused = ThrottleAction.PAUSE_STREAMING in status.activeActions
        launchThermal { camera ->
            if (paused) camera.pauseForThermal()
            camera.setThermalBitrateReduction(status.bitrateReductionPercent)
            camera.setThermalEncoderLimits(status.resolutionReduced, status.framerateReduced)
            if (!paused) camera.resumeAfterThermal()
        }
    }

    /**
     * Run [block] with the camera on the thermal thread, after every block
     * launched before it; skipped while no camera is connected. A failure is
     * logged rather than taking the app down.
     */
    private fun launchThermal(block: (CameraService) -> Unit) {
        serviceScope.launch(thermalDispatcher) {
            val camera = cameraService ?: return@launch
            try {
                block(camera)
            } catch (e: RuntimeException) {
                Timber.e(e, "$TAG: Thermal throttling could not be applied to the camera")
            }
        }
    }

    /**
     * Set up AI Director integration with camera service
     */
    private fun setupDirectorIntegration() {
        if (!BuildConfig.ENABLE_DIRECTOR) return
        val camera = cameraService ?: return

        // The director drives the camera through the adapter, within the camera's capabilities
        val director = DirectorManager(this)
        val adapter = CameraControllerAdapter(camera)
        director.setCameraController(adapter)
        director.getShotMapper().updateCapabilities(adapter.getCameraCapabilities())

        // Quality samples go from the camera to the take manager, which scores each take
        val takeManager = director.getTakeManager()
        val collector = QualityMetricsCollector().apply {
            setSource(adapter.asMetricsSource())
            setSink(takeManager.asMetricsSink())
        }

        directorManager = director
        cameraControllerAdapter = adapter
        qualityMetricsCollector = collector

        // Sample the camera while a take runs, so each take is scored on its own footage
        takeWatchJob = serviceScope.launch {
            takeManager.currentTake
                .map { it != null }
                .distinctUntilChanged()
                .collect { inTake ->
                    if (inTake) {
                        collector.startCollection()
                    } else if (collector.isCollecting.value) {
                        collector.stopCollection()
                    }
                }
        }

        // Connect director to API routes
        apiRoutes?.directorManager = director

        Timber.i("$TAG: AI Director integration set up")
    }

    /**
     * Clean up AI Director integration
     */
    private fun cleanupDirectorIntegration() {
        takeWatchJob?.cancel()
        takeWatchJob = null

        qualityMetricsCollector?.destroy()
        qualityMetricsCollector = null

        directorManager?.destroy()
        directorManager = null

        cameraControllerAdapter?.reset()
        cameraControllerAdapter = null

        apiRoutes?.directorManager = null

        Timber.i("$TAG: AI Director integration cleaned up")
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
     * Bind to upload service
     */
    private fun bindUploadService() {
        val intent = Intent(this, UploadService::class.java)
        bindService(intent, uploadConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Unbind from upload service
     */
    private fun unbindUploadService() {
        if (uploadBound) {
            unbindService(uploadConnection)
            uploadBound = false
        }
    }

    /**
     * Bind to thermal service
     */
    private fun bindThermalService() {
        val intent = Intent(this, ThermalService::class.java)
        bindService(intent, thermalConnection, Context.BIND_AUTO_CREATE)
    }

    /**
     * Unbind from thermal service, which may live on (started, or bound by the
     * activity): its throttling then no longer calls into this service
     */
    private fun unbindThermalService() {
        thermalService?.let { clearThermalCallbacks(it) }
        if (thermalBound) {
            unbindService(thermalConnection)
            thermalBound = false
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

    /**
     * Set or clear the API authentication token.
     * Pass null to disable authentication.
     */
    fun setApiToken(token: String?) {
        val prefs = getSharedPreferences("lensdaemon_security", MODE_PRIVATE)
        if (token.isNullOrEmpty()) {
            prefs.edit().remove("api_token").apply()
        } else {
            prefs.edit().putString("api_token", token).apply()
        }
        apiRoutes?.apiToken = token
    }

    // ==================== AI Director API ====================

    /**
     * Get director manager
     */
    fun getDirectorManager(): DirectorManager? = directorManager

    /**
     * Get quality metrics collector; it collects while a director take runs
     */
    fun getQualityMetricsCollector(): QualityMetricsCollector? = qualityMetricsCollector

    /**
     * Check if AI Director is enabled
     */
    fun isDirectorEnabled(): Boolean = directorManager?.isEnabled() ?: false
}
