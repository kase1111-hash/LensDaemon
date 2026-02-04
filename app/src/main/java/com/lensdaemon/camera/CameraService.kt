package com.lensdaemon.camera

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Size
import android.view.Surface
import android.view.SurfaceView
import androidx.core.app.NotificationCompat
import com.lensdaemon.LensDaemonApp
import com.lensdaemon.MainActivity
import com.lensdaemon.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber

/**
 * Foreground service for camera capture operations.
 * Manages Camera2 pipeline, encoding, and streaming.
 */
class CameraService : Service() {

    companion object {
        private const val ACTION_START_PREVIEW = "com.lensdaemon.action.START_PREVIEW"
        private const val ACTION_STOP_PREVIEW = "com.lensdaemon.action.STOP_PREVIEW"
        private const val ACTION_START_STREAMING = "com.lensdaemon.action.START_STREAMING"
        private const val ACTION_STOP_STREAMING = "com.lensdaemon.action.STOP_STREAMING"
        private const val EXTRA_LENS_TYPE = "lens_type"

        fun startPreviewIntent(context: Context, lensType: LensType = LensType.MAIN): Intent {
            return Intent(context, CameraService::class.java).apply {
                action = ACTION_START_PREVIEW
                putExtra(EXTRA_LENS_TYPE, lensType.name)
            }
        }

        fun stopPreviewIntent(context: Context): Intent {
            return Intent(context, CameraService::class.java).apply {
                action = ACTION_STOP_PREVIEW
            }
        }
    }

    private val binder = LocalBinder()
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Camera components
    private lateinit var cameraManager: LensDaemonCameraManager
    private val previewSurfaceProvider = PreviewSurfaceProvider()

    // Current state
    private var currentConfig = CaptureConfig()
    private var isPreviewActive = false
    private var isStreamingActive = false

    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Timber.i("CameraService created")
        cameraManager = LensDaemonCameraManager(applicationContext)

        // Monitor surface state
        serviceScope.launch {
            previewSurfaceProvider.surfaceState.collectLatest { state ->
                when (state) {
                    is SurfaceState.Available -> {
                        Timber.d("Preview surface available: ${state.width}x${state.height}")
                        if (isPreviewActive) {
                            startCameraPreview(state.surface)
                        }
                    }
                    is SurfaceState.Unavailable -> {
                        Timber.d("Preview surface unavailable")
                    }
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("CameraService onStartCommand: ${intent?.action}")

        // Start as foreground service
        startForeground(LensDaemonApp.NOTIFICATION_ID, createNotification())

        when (intent?.action) {
            ACTION_START_PREVIEW -> {
                val lensTypeName = intent.getStringExtra(EXTRA_LENS_TYPE) ?: LensType.MAIN.name
                val lensType = LensType.valueOf(lensTypeName)
                serviceScope.launch {
                    openCameraAndStartPreview(lensType)
                }
            }
            ACTION_STOP_PREVIEW -> {
                stopPreview()
            }
            ACTION_START_STREAMING -> {
                startStreaming()
            }
            ACTION_STOP_STREAMING -> {
                stopStreaming()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("CameraService destroyed")
        stopPreview()
        cameraManager.release()
        previewSurfaceProvider.detach()
        serviceScope.cancel()
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val statusText = when {
            isStreamingActive -> getString(R.string.notification_streaming)
            isPreviewActive -> "Preview active"
            else -> "Ready"
        }

        return NotificationCompat.Builder(this, LensDaemonApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(statusText)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification() {
        val notification = createNotification()
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE)
            as android.app.NotificationManager
        notificationManager.notify(LensDaemonApp.NOTIFICATION_ID, notification)
    }

    // ==================== Public API ====================

    /**
     * Attach a SurfaceView for camera preview.
     */
    fun attachPreviewSurface(surfaceView: SurfaceView) {
        Timber.i("Attaching preview surface")
        previewSurfaceProvider.attachSurfaceView(surfaceView)
    }

    /**
     * Get available camera lenses.
     */
    fun getAvailableLenses(): List<CameraLens> = cameraManager.availableLenses

    /**
     * Get the current lens being used.
     */
    fun getCurrentLens(): StateFlow<CameraLens?> = cameraManager.currentLens

    /**
     * Get the current camera state.
     */
    fun getCameraState(): StateFlow<CameraState> = cameraManager.cameraState

    /**
     * Start camera preview with the specified lens.
     */
    fun startPreview(lensType: LensType = LensType.MAIN) {
        serviceScope.launch {
            openCameraAndStartPreview(lensType)
        }
    }

    /**
     * Start camera preview with specific lens.
     */
    fun startPreview(lens: CameraLens) {
        serviceScope.launch {
            openCameraAndStartPreview(lens)
        }
    }

    private suspend fun openCameraAndStartPreview(lensType: LensType) {
        try {
            Timber.i("Opening camera with lens type: $lensType")
            val opened = cameraManager.openCamera(lensType)
            if (opened) {
                isPreviewActive = true
                // Wait for surface to be available
                val surfaceState = previewSurfaceProvider.surfaceState.value
                if (surfaceState is SurfaceState.Available) {
                    startCameraPreview(surfaceState.surface)
                }
                updateNotification()
            } else {
                Timber.e("Failed to open camera")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error opening camera")
        }
    }

    private suspend fun openCameraAndStartPreview(lens: CameraLens) {
        try {
            Timber.i("Opening camera with lens: ${lens.lensType.displayName}")
            val opened = cameraManager.openCamera(lens)
            if (opened) {
                isPreviewActive = true
                val surfaceState = previewSurfaceProvider.surfaceState.value
                if (surfaceState is SurfaceState.Available) {
                    startCameraPreview(surfaceState.surface)
                }
                updateNotification()
            } else {
                Timber.e("Failed to open camera")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error opening camera")
        }
    }

    private suspend fun startCameraPreview(surface: Surface) {
        try {
            Timber.i("Starting camera preview")
            cameraManager.startPreview(surface, currentConfig)
        } catch (e: Exception) {
            Timber.e(e, "Error starting camera preview")
        }
    }

    /**
     * Stop camera preview.
     */
    fun stopPreview() {
        Timber.i("Stopping preview")
        isPreviewActive = false
        cameraManager.closeCamera()
        updateNotification()
    }

    /**
     * Switch to a different camera lens.
     */
    fun switchLens(lensType: LensType) {
        Timber.i("Switching lens to: $lensType")
        serviceScope.launch {
            val lens = cameraManager.availableLenses.find { it.lensType == lensType }
            if (lens != null) {
                openCameraAndStartPreview(lens)
            } else {
                Timber.w("Lens type $lensType not available")
            }
        }
    }

    /**
     * Switch to a specific lens by name (wide, main, tele).
     */
    fun switchLens(lensName: String) {
        val lensType = when (lensName.lowercase()) {
            "wide", "ultrawide", "ultra_wide" -> LensType.WIDE
            "main", "primary", "default" -> LensType.MAIN
            "tele", "telephoto", "zoom" -> LensType.TELEPHOTO
            else -> {
                Timber.w("Unknown lens name: $lensName")
                return
            }
        }
        switchLens(lensType)
    }

    /**
     * Set the zoom level.
     */
    fun setZoom(zoomLevel: Float) {
        Timber.i("Setting zoom to: $zoomLevel")
        currentConfig = currentConfig.copy(zoomRatio = zoomLevel)
        cameraManager.updateConfig(currentConfig)
    }

    /**
     * Set focus mode.
     */
    fun setFocusMode(mode: FocusMode) {
        Timber.i("Setting focus mode to: $mode")
        currentConfig = currentConfig.copy(focusMode = mode)
        cameraManager.updateConfig(currentConfig)
    }

    /**
     * Set exposure compensation.
     */
    fun setExposureCompensation(value: Int) {
        Timber.i("Setting exposure compensation to: $value")
        currentConfig = currentConfig.copy(exposureCompensation = value)
        cameraManager.updateConfig(currentConfig)
    }

    /**
     * Set white balance mode.
     */
    fun setWhiteBalance(mode: WhiteBalanceMode) {
        Timber.i("Setting white balance to: $mode")
        currentConfig = currentConfig.copy(whiteBalance = mode)
        cameraManager.updateConfig(currentConfig)
    }

    /**
     * Update capture resolution.
     */
    fun setResolution(width: Int, height: Int) {
        Timber.i("Setting resolution to: ${width}x${height}")
        currentConfig = currentConfig.copy(resolution = Size(width, height))
        previewSurfaceProvider.setTargetSize(Size(width, height))
        // Would need to restart preview to apply new resolution
    }

    /**
     * Get current capture configuration.
     */
    fun getConfig(): CaptureConfig = currentConfig

    /**
     * Get camera capabilities for current lens.
     */
    fun getCurrentCameraCapabilities(): CameraCapabilities? {
        val lens = cameraManager.currentLens.value ?: return null
        return cameraManager.getCameraCapabilities(lens.cameraId)
    }

    // ==================== Streaming (Phase 4-5) ====================

    /**
     * Start video streaming.
     */
    fun startStreaming() {
        Timber.i("startStreaming() - Will be implemented in Phase 4-5")
        isStreamingActive = true
        updateNotification()
        // TODO: Phase 4-5 - Initialize encoder and RTSP server
    }

    /**
     * Stop video streaming.
     */
    fun stopStreaming() {
        Timber.i("stopStreaming() - Will be implemented in Phase 4-5")
        isStreamingActive = false
        updateNotification()
        // TODO: Phase 4-5 - Stop encoder and RTSP server
    }

    /**
     * Check if streaming is active.
     */
    fun isStreaming(): Boolean = isStreamingActive

    /**
     * Check if preview is active.
     */
    fun isPreviewActive(): Boolean = isPreviewActive
}
