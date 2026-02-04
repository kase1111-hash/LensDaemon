package com.lensdaemon.camera

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
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
import kotlinx.coroutines.flow.MutableStateFlow
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
    private lateinit var lensDaemonCameraManager: LensDaemonCameraManager
    private val previewSurfaceProvider = PreviewSurfaceProvider()

    // Controllers (Phase 3)
    private lateinit var lensController: LensController
    private val focusController = FocusController()
    private val exposureController = ExposureController()
    private val zoomController = ZoomController()

    // Current state
    private var currentConfig = CaptureConfig()
    private var isPreviewActive = false
    private var isStreamingActive = false

    // Focus state observable
    private val _focusState = MutableStateFlow(FocusState.INACTIVE)
    val focusState: StateFlow<FocusState> = _focusState

    // Zoom state observable
    private val _currentZoom = MutableStateFlow(1.0f)
    val currentZoom: StateFlow<Float> = _currentZoom

    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    override fun onBind(intent: Intent): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Timber.i("CameraService created")

        val systemCameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        lensDaemonCameraManager = LensDaemonCameraManager(applicationContext)

        // Initialize lens controller with available lenses
        lensController = LensController(systemCameraManager, lensDaemonCameraManager.availableLenses)

        // Set up zoom change listener
        zoomController.setOnZoomChangedListener { zoom ->
            _currentZoom.value = zoom
            applyZoomToCamera(zoom)
        }

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

        // Monitor focus state
        serviceScope.launch {
            focusController.focusState.collectLatest { state ->
                _focusState.value = state
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
        lensDaemonCameraManager.release()
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

    private fun initializeControllersForCamera(cameraId: String) {
        val systemCameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        try {
            val characteristics = systemCameraManager.getCameraCharacteristics(cameraId)
            focusController.initialize(characteristics)
            exposureController.initialize(characteristics)
            zoomController.initialize(characteristics)
            Timber.i("Controllers initialized for camera: $cameraId")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize controllers for camera: $cameraId")
        }
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
    fun getAvailableLenses(): List<CameraLens> = lensDaemonCameraManager.availableLenses

    /**
     * Get the current lens being used.
     */
    fun getCurrentLens(): StateFlow<CameraLens?> = lensDaemonCameraManager.currentLens

    /**
     * Get the current camera state.
     */
    fun getCameraState(): StateFlow<CameraState> = lensDaemonCameraManager.cameraState

    /**
     * Get zoom presets for available lenses.
     */
    fun getZoomPresets(): List<ZoomPreset> = lensController.getZoomPresets()

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
            val opened = lensDaemonCameraManager.openCamera(lensType)
            if (opened) {
                val lens = lensDaemonCameraManager.currentLens.value
                lens?.let {
                    lensController.setCurrentLens(it)
                    initializeControllersForCamera(it.cameraId)
                }
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
            val opened = lensDaemonCameraManager.openCamera(lens)
            if (opened) {
                lensController.setCurrentLens(lens)
                initializeControllersForCamera(lens.cameraId)
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
            lensDaemonCameraManager.startPreview(surface, currentConfig)
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
        lensDaemonCameraManager.closeCamera()
        focusController.reset()
        exposureController.reset()
        zoomController.reset()
        updateNotification()
    }

    /**
     * Switch to a different camera lens.
     */
    fun switchLens(lensType: LensType) {
        Timber.i("Switching lens to: $lensType")
        serviceScope.launch {
            val lens = lensDaemonCameraManager.availableLenses.find { it.lensType == lensType }
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

    // ==================== Zoom Control (Phase 3) ====================

    /**
     * Set the zoom level immediately.
     */
    fun setZoom(zoomLevel: Float) {
        zoomController.setZoom(zoomLevel)
    }

    /**
     * Animate zoom to target level.
     */
    fun animateZoomTo(targetZoom: Float) {
        zoomController.animateZoomTo(targetZoom)
    }

    /**
     * Zoom in by a step.
     */
    fun zoomIn(step: Float = 0.5f): Float {
        return zoomController.zoomIn(step)
    }

    /**
     * Zoom out by a step.
     */
    fun zoomOut(step: Float = 0.5f): Float {
        return zoomController.zoomOut(step)
    }

    /**
     * Reset zoom to 1x.
     */
    fun resetZoom() {
        zoomController.resetZoom()
    }

    /**
     * Handle pinch gesture start.
     */
    fun onPinchStart() {
        zoomController.onPinchStart()
    }

    /**
     * Handle pinch scale.
     */
    fun onPinchScale(scaleFactor: Float): Float {
        return zoomController.onPinchScale(scaleFactor)
    }

    /**
     * Handle pinch gesture end.
     */
    fun onPinchEnd() {
        zoomController.onPinchEnd()
    }

    /**
     * Get zoom range for current camera.
     */
    fun getZoomRange(): ClosedFloatingPointRange<Float> = zoomController.zoomRange.value

    private fun applyZoomToCamera(zoom: Float) {
        currentConfig = currentConfig.copy(zoomRatio = zoom)
        lensDaemonCameraManager.updateConfig(currentConfig)
    }

    // ==================== Focus Control (Phase 3) ====================

    /**
     * Trigger tap-to-focus at normalized coordinates.
     * @param x Normalized X (0.0 to 1.0, left to right)
     * @param y Normalized Y (0.0 to 1.0, top to bottom)
     * @param previewSize Size of the preview view
     */
    fun triggerTapToFocus(x: Float, y: Float, previewSize: Size) {
        val result = focusController.triggerTapToFocus(x, y, previewSize)
        if (result != null) {
            currentConfig = currentConfig.copy(focusMode = result.mode)
            lensDaemonCameraManager.updateConfig(currentConfig)
            Timber.i("Tap-to-focus triggered at ($x, $y)")
        }
    }

    /**
     * Lock focus at current position.
     */
    fun lockFocus() {
        focusController.lockFocus()
    }

    /**
     * Unlock focus and return to continuous mode.
     */
    fun unlockFocus() {
        val result = focusController.unlockFocus()
        currentConfig = currentConfig.copy(focusMode = result.mode)
        lensDaemonCameraManager.updateConfig(currentConfig)
    }

    /**
     * Set focus mode.
     */
    fun setFocusMode(mode: FocusMode) {
        if (focusController.setFocusMode(mode)) {
            currentConfig = currentConfig.copy(focusMode = mode)
            lensDaemonCameraManager.updateConfig(currentConfig)
        }
    }

    /**
     * Set manual focus distance (0.0 = infinity, 1.0 = closest).
     */
    fun setManualFocusDistance(distance: Float) {
        if (focusController.setManualFocusDistance(distance)) {
            currentConfig = currentConfig.copy(focusMode = FocusMode.MANUAL)
            lensDaemonCameraManager.updateConfig(currentConfig)
        }
    }

    /**
     * Check if tap-to-focus is supported.
     */
    fun isTapToFocusSupported(): Boolean = focusController.isTapToFocusSupported()

    /**
     * Check if manual focus is supported.
     */
    fun isManualFocusSupported(): Boolean = focusController.isManualFocusSupported()

    // ==================== Exposure Control (Phase 3) ====================

    /**
     * Set exposure compensation.
     */
    fun setExposureCompensation(value: Int) {
        if (exposureController.setExposureCompensation(value)) {
            currentConfig = currentConfig.copy(exposureCompensation = value)
            lensDaemonCameraManager.updateConfig(currentConfig)
        }
    }

    /**
     * Adjust exposure compensation by delta.
     */
    fun adjustExposureCompensation(delta: Int): Int {
        val newValue = exposureController.adjustExposureCompensation(delta)
        currentConfig = currentConfig.copy(exposureCompensation = newValue)
        lensDaemonCameraManager.updateConfig(currentConfig)
        return newValue
    }

    /**
     * Lock auto-exposure.
     */
    fun lockExposure() {
        exposureController.lockExposure()
    }

    /**
     * Unlock auto-exposure.
     */
    fun unlockExposure() {
        exposureController.unlockExposure()
    }

    /**
     * Toggle exposure lock.
     */
    fun toggleExposureLock(): Boolean {
        return exposureController.toggleExposureLock()
    }

    /**
     * Get exposure lock state.
     */
    fun isExposureLocked(): StateFlow<Boolean> = exposureController.aeLocked

    /**
     * Set white balance mode.
     */
    fun setWhiteBalance(mode: WhiteBalanceMode) {
        if (exposureController.setWhiteBalance(mode)) {
            currentConfig = currentConfig.copy(whiteBalance = mode)
            lensDaemonCameraManager.updateConfig(currentConfig)
        }
    }

    /**
     * Get supported white balance modes.
     */
    fun getSupportedWhiteBalanceModes(): List<WhiteBalanceMode> {
        return exposureController.getSupportedWhiteBalanceModes()
    }

    /**
     * Trigger spot metering at normalized coordinates.
     */
    fun triggerSpotMetering(x: Float, y: Float) {
        exposureController.triggerSpotMetering(x, y)
        Timber.i("Spot metering triggered at ($x, $y)")
    }

    /**
     * Reset metering to center-weighted.
     */
    fun resetMetering() {
        exposureController.resetMetering()
    }

    /**
     * Get exposure compensation range.
     */
    fun getExposureCompensationRange(): android.util.Range<Int> {
        return exposureController.getExposureCompensationRange()
    }

    // ==================== Resolution Control ====================

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
        val lens = lensDaemonCameraManager.currentLens.value ?: return null
        return lensDaemonCameraManager.getCameraCapabilities(lens.cameraId)
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
