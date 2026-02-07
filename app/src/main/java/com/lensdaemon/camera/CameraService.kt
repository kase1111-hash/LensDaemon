package com.lensdaemon.camera

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
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
import com.lensdaemon.encoder.EncodedFrame
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.encoder.EncoderService
import com.lensdaemon.encoder.EncoderState
import com.lensdaemon.encoder.EncoderStats
import com.lensdaemon.encoder.VideoCodec
import com.lensdaemon.output.RtspServer
import com.lensdaemon.output.RtspServerState
import com.lensdaemon.output.RtspServerStats
import com.lensdaemon.output.MpegTsUdpConfig
import com.lensdaemon.output.MpegTsUdpPublisher
import com.lensdaemon.output.MpegTsUdpStats
import com.lensdaemon.output.RecordingEvent
import com.lensdaemon.output.RecordingListener
import com.lensdaemon.output.RecordingStats
import com.lensdaemon.output.RecordingState
import com.lensdaemon.output.SegmentDuration
import com.lensdaemon.storage.RecordingFile
import com.lensdaemon.storage.StorageManager
import com.lensdaemon.storage.StorageManagerBuilder
import com.lensdaemon.storage.StorageStatus
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
        private const val DEFAULT_CPU_TEMP_FALLBACK = 40

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

    // Encoder service connection (Phase 4)
    private var encoderService: EncoderService? = null
    private var encoderBound = false
    private var encoderSurface: Surface? = null

    // Encoder state observable
    private val _encoderState = MutableStateFlow(EncoderState.IDLE)
    val encoderState: StateFlow<EncoderState> = _encoderState

    // Frame listeners (for external consumers like RTSP server)
    private val encodedFrameListeners = mutableListOf<(EncodedFrame) -> Unit>()

    // RTSP server (Phase 5)
    private var rtspServer: RtspServer? = null
    private val _rtspServerState = MutableStateFlow(RtspServerState.STOPPED)
    val rtspServerState: StateFlow<RtspServerState> = _rtspServerState

    // Storage manager (Phase 7)
    private var storageManager: StorageManager? = null
    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState

    // Recording frame listener
    private val recordingFrameListener: (EncodedFrame) -> Unit = { frame ->
        storageManager?.writeFrame(frame)
    }

    // Frame listener for RTSP server
    private val rtspFrameListener: (EncodedFrame) -> Unit = { frame ->
        rtspServer?.sendFrame(frame)
    }

    // MPEG-TS/UDP publisher
    private var mpegtsPublisher: MpegTsUdpPublisher? = null
    private val _mpegtsRunning = MutableStateFlow(false)
    val mpegtsRunning: StateFlow<Boolean> = _mpegtsRunning

    // Frame listener for MPEG-TS/UDP publisher
    private val mpegtsFrameListener: (EncodedFrame) -> Unit = { frame ->
        mpegtsPublisher?.sendFrame(frame)
    }

    private val encoderConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as EncoderService.EncoderBinder
            encoderService = binder.getService()
            encoderBound = true
            Timber.i("EncoderService connected")

            // Observe encoder state
            serviceScope.launch {
                encoderService?.encoderState?.collectLatest { state ->
                    _encoderState.value = state
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            encoderService = null
            encoderBound = false
            encoderSurface = null
            Timber.i("EncoderService disconnected")
        }
    }

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

        // Bind to encoder service
        bindEncoderService()
    }

    private fun bindEncoderService() {
        val intent = Intent(this, EncoderService::class.java)
        bindService(intent, encoderConnection, Context.BIND_AUTO_CREATE)
    }

    private fun unbindEncoderService() {
        if (encoderBound) {
            unbindService(encoderConnection)
            encoderBound = false
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
        stopStreaming()
        stopPreview()
        unbindEncoderService()
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

    // ==================== Encoding & Streaming (Phase 4-5) ====================

    /**
     * Initialize encoder with configuration.
     * @return true if initialization successful
     */
    fun initializeEncoder(config: EncoderConfig = EncoderConfig.PRESET_1080P): Boolean {
        if (!encoderBound || encoderService == null) {
            Timber.e("EncoderService not bound")
            return false
        }

        encoderSurface = encoderService?.initializeEncoder(config)
        if (encoderSurface == null) {
            Timber.e("Failed to initialize encoder")
            return false
        }

        // Set up frame listener to dispatch to registered listeners
        encoderService?.addFrameListener { frame ->
            dispatchEncodedFrame(frame)
        }

        Timber.i("Encoder initialized: ${config.width}x${config.height} @ ${config.bitrateBps}bps")
        return true
    }

    /**
     * Initialize encoder with specific parameters.
     */
    fun initializeEncoder(
        width: Int = 1920,
        height: Int = 1080,
        bitrateBps: Int = 4_000_000,
        frameRate: Int = 30,
        codec: VideoCodec = VideoCodec.H264
    ): Boolean {
        val config = EncoderConfig(
            codec = codec,
            resolution = Size(width, height),
            bitrateBps = bitrateBps,
            frameRate = frameRate
        )
        return initializeEncoder(config)
    }

    /**
     * Start video streaming (encoding + preview).
     */
    fun startStreaming() {
        if (!isPreviewActive) {
            Timber.w("Preview not active, cannot start streaming")
            return
        }

        if (encoderSurface == null) {
            // Initialize encoder with default config
            if (!initializeEncoder()) {
                Timber.e("Failed to initialize encoder for streaming")
                return
            }
        }

        // Start encoding
        encoderService?.startEncoding()
        isStreamingActive = true
        updateNotification()
        Timber.i("Streaming started")
    }

    /**
     * Start video streaming with specific encoder configuration.
     */
    fun startStreaming(config: EncoderConfig) {
        if (!isPreviewActive) {
            Timber.w("Preview not active, cannot start streaming")
            return
        }

        // Initialize encoder with provided config
        if (!initializeEncoder(config)) {
            Timber.e("Failed to initialize encoder for streaming")
            return
        }

        // Start encoding
        encoderService?.startEncoding()
        isStreamingActive = true
        updateNotification()
        Timber.i("Streaming started with config: ${config.width}x${config.height}")
    }

    /**
     * Stop video streaming.
     */
    fun stopStreaming() {
        if (!isStreamingActive) return

        encoderService?.stopEncoding()
        isStreamingActive = false
        updateNotification()
        Timber.i("Streaming stopped")
    }

    /**
     * Release encoder resources.
     */
    fun releaseEncoder() {
        encoderService?.releaseEncoder()
        encoderSurface = null
        Timber.i("Encoder released")
    }

    /**
     * Request a keyframe from encoder.
     */
    fun requestKeyFrame() {
        encoderService?.requestKeyFrame()
    }

    /**
     * Update encoding bitrate.
     */
    fun updateEncoderBitrate(newBitrateBps: Int) {
        encoderService?.updateBitrate(newBitrateBps)
    }

    /**
     * Enable adaptive bitrate control.
     */
    fun setAdaptiveBitrate(enabled: Boolean, minBps: Int = 500_000, maxBps: Int = 8_000_000) {
        encoderService?.setAdaptiveBitrate(enabled, minBps, maxBps)
    }

    /**
     * Notify encoder of network congestion.
     */
    fun onNetworkCongestion() {
        encoderService?.onNetworkCongestion()
    }

    /**
     * Notify encoder of network improvement.
     */
    fun onNetworkImproved() {
        encoderService?.onNetworkImproved()
    }

    /**
     * Get encoder surface for multi-surface capture.
     */
    fun getEncoderSurface(): Surface? = encoderSurface

    /**
     * Get SPS data for streaming setup.
     */
    fun getSps(): ByteArray? = encoderService?.getSps()

    /**
     * Get PPS data for streaming setup.
     */
    fun getPps(): ByteArray? = encoderService?.getPps()

    /**
     * Get VPS data for H.265 streaming.
     */
    fun getVps(): ByteArray? = encoderService?.getVps()

    /**
     * Check if codec config data is available.
     */
    fun hasEncoderConfigData(): Boolean = encoderService?.hasConfigData() ?: false

    /**
     * Get combined config data (SPS+PPS or VPS+SPS+PPS).
     */
    fun getEncoderConfigData(): ByteArray? = encoderService?.getConfigData()

    /**
     * Get encoder statistics.
     */
    fun getEncoderStats(): EncoderStats? = encoderService?.getStats()

    /**
     * Get current encoder configuration.
     */
    fun getEncoderConfig(): EncoderConfig? = encoderService?.getConfig()

    /**
     * Add listener for encoded frames.
     */
    fun addEncodedFrameListener(listener: (EncodedFrame) -> Unit) {
        synchronized(encodedFrameListeners) {
            encodedFrameListeners.add(listener)
        }
    }

    /**
     * Remove encoded frame listener.
     */
    fun removeEncodedFrameListener(listener: (EncodedFrame) -> Unit) {
        synchronized(encodedFrameListeners) {
            encodedFrameListeners.remove(listener)
        }
    }

    private fun dispatchEncodedFrame(frame: EncodedFrame) {
        synchronized(encodedFrameListeners) {
            encodedFrameListeners.forEach { listener ->
                try {
                    listener(frame)
                } catch (e: Exception) {
                    Timber.e(e, "Error in encoded frame listener")
                }
            }
        }
    }

    /**
     * Check if streaming is active.
     */
    fun isStreaming(): Boolean = isStreamingActive

    /**
     * Check if preview is active.
     */
    fun isPreviewActive(): Boolean = isPreviewActive

    /**
     * Check if encoder is ready.
     */
    fun isEncoderReady(): Boolean = _encoderState.value == EncoderState.READY || _encoderState.value == EncoderState.ENCODING

    // ==================== RTSP Server (Phase 5) ====================

    /**
     * Start RTSP server.
     * @param port RTSP port (default 8554)
     */
    fun startRtspServer(port: Int = 8554): Boolean {
        if (rtspServer?.isRunning() == true) {
            Timber.w("RTSP server already running")
            return true
        }

        rtspServer = RtspServer(port)

        // Set up keyframe request callback
        rtspServer?.onKeyframeRequest = {
            requestKeyFrame()
        }

        // Update codec config if available
        val codec = getEncoderConfig()?.codec ?: VideoCodec.H264
        rtspServer?.setCodecConfig(codec, getSps(), getPps(), getVps())

        // Add frame listener
        addEncodedFrameListener(rtspFrameListener)

        val success = rtspServer?.start() ?: false
        if (success) {
            _rtspServerState.value = RtspServerState.RUNNING
            Timber.i("RTSP server started: ${rtspServer?.getRtspUrl()}")
        } else {
            _rtspServerState.value = RtspServerState.ERROR
            removeEncodedFrameListener(rtspFrameListener)
        }

        return success
    }

    /**
     * Stop RTSP server.
     */
    fun stopRtspServer() {
        removeEncodedFrameListener(rtspFrameListener)
        rtspServer?.stop()
        rtspServer = null
        _rtspServerState.value = RtspServerState.STOPPED
        Timber.i("RTSP server stopped")
    }

    /**
     * Get RTSP server URL.
     */
    fun getRtspUrl(): String? = rtspServer?.getRtspUrl()

    /**
     * Get RTSP server statistics.
     */
    fun getRtspServerStats(): RtspServerStats? = rtspServer?.getStats()

    /**
     * Get number of RTSP clients connected.
     */
    fun getRtspClientCount(): Int = rtspServer?.getActiveConnections() ?: 0

    /**
     * Get number of RTSP clients currently streaming.
     */
    fun getRtspPlayingCount(): Int = rtspServer?.getPlayingClients() ?: 0

    /**
     * Check if RTSP server is running.
     */
    fun isRtspServerRunning(): Boolean = rtspServer?.isRunning() ?: false

    /**
     * Update RTSP codec config (call when SPS/PPS changes).
     */
    private fun updateRtspCodecConfig() {
        val codec = getEncoderConfig()?.codec ?: VideoCodec.H264
        rtspServer?.setCodecConfig(codec, getSps(), getPps(), getVps())
    }

    /**
     * Start streaming with RTSP server.
     * Convenience method that starts both encoding and RTSP server.
     */
    fun startRtspStreaming(
        config: EncoderConfig = EncoderConfig.PRESET_1080P,
        rtspPort: Int = 8554
    ): Boolean {
        // Start preview if not active
        if (!isPreviewActive) {
            Timber.w("Preview not active, starting with main lens")
            startPreview(LensType.MAIN)
        }

        // Initialize and start encoder
        if (!initializeEncoder(config)) {
            Timber.e("Failed to initialize encoder")
            return false
        }

        // Start encoding
        encoderService?.startEncoding()
        isStreamingActive = true

        // Start RTSP server
        val rtspStarted = startRtspServer(rtspPort)
        if (!rtspStarted) {
            Timber.e("Failed to start RTSP server")
            stopStreaming()
            return false
        }

        // Update codec config for RTSP
        serviceScope.launch {
            // Wait for SPS/PPS to be available
            delay(500)
            updateRtspCodecConfig()
        }

        updateNotification()
        Timber.i("RTSP streaming started: ${getRtspUrl()}")
        return true
    }

    /**
     * Stop RTSP streaming.
     * Convenience method that stops both RTSP server and encoding.
     */
    fun stopRtspStreaming() {
        stopRtspServer()
        stopStreaming()
        Timber.i("RTSP streaming stopped")
    }

    // ==================== MPEG-TS/UDP Publisher ====================

    /**
     * Start MPEG-TS/UDP publisher with given config.
     */
    fun startMpegTsPublisher(config: MpegTsUdpConfig = MpegTsUdpConfig()): Boolean {
        if (mpegtsPublisher?.isRunning() == true) {
            Timber.w("MPEG-TS/UDP publisher already running")
            return true
        }

        val codec = getEncoderConfig()?.codec ?: VideoCodec.H264
        mpegtsPublisher = MpegTsUdpPublisher(config).also {
            it.setCodecConfig(codec, getSps(), getPps(), getVps())
        }

        addEncodedFrameListener(mpegtsFrameListener)

        val success = mpegtsPublisher?.start() ?: false
        if (success) {
            _mpegtsRunning.value = true
            Timber.i("MPEG-TS/UDP publisher started on port ${config.port}")
        } else {
            _mpegtsRunning.value = false
            removeEncodedFrameListener(mpegtsFrameListener)
            Timber.e("Failed to start MPEG-TS/UDP publisher")
        }
        return success
    }

    /**
     * Stop MPEG-TS/UDP publisher.
     */
    fun stopMpegTsPublisher() {
        removeEncodedFrameListener(mpegtsFrameListener)
        mpegtsPublisher?.stop()
        mpegtsPublisher = null
        _mpegtsRunning.value = false
        Timber.i("MPEG-TS/UDP publisher stopped")
    }

    /**
     * Check if MPEG-TS/UDP publisher is running.
     */
    fun isMpegTsRunning(): Boolean = mpegtsPublisher?.isRunning() ?: false

    /**
     * Get MPEG-TS/UDP publisher statistics.
     */
    fun getMpegTsStats(): MpegTsUdpStats? = mpegtsPublisher?.getStats()

    /**
     * Start MPEG-TS/UDP streaming (encoder + publisher).
     */
    fun startMpegTsStreaming(
        encoderConfig: EncoderConfig = EncoderConfig.PRESET_1080P,
        mpegtsConfig: MpegTsUdpConfig = MpegTsUdpConfig()
    ): Boolean {
        if (!isPreviewActive) {
            Timber.w("Preview not active, starting with main lens")
            startPreview(LensType.MAIN)
        }

        if (!initializeEncoder(encoderConfig)) {
            Timber.e("Failed to initialize encoder for MPEG-TS/UDP")
            return false
        }

        encoderService?.startEncoding()
        isStreamingActive = true

        val started = startMpegTsPublisher(mpegtsConfig)
        if (!started) {
            Timber.e("Failed to start MPEG-TS/UDP publisher")
            stopStreaming()
            return false
        }

        updateNotification()
        Timber.i("MPEG-TS/UDP streaming started on port ${mpegtsConfig.port}")
        return true
    }

    /**
     * Stop MPEG-TS/UDP streaming.
     */
    fun stopMpegTsStreaming() {
        stopMpegTsPublisher()
        stopStreaming()
        Timber.i("MPEG-TS/UDP streaming stopped")
    }

    // ==================== Local Recording (Phase 7) ====================

    /**
     * Initialize storage manager for recording.
     * @param segmentDuration Segment duration for file splitting
     */
    fun initializeRecording(
        encoderConfig: EncoderConfig = EncoderConfig.PRESET_1080P,
        segmentDuration: SegmentDuration = SegmentDuration.FIVE_MINUTES
    ): Boolean {
        if (storageManager != null) {
            Timber.w("Storage manager already initialized")
            return true
        }

        storageManager = StorageManagerBuilder(applicationContext)
            .encoderConfig(encoderConfig)
            .segmentDuration(segmentDuration)
            .build()

        // Add recording state listener
        storageManager?.addListener(object : RecordingListener {
            override fun onRecordingEvent(event: RecordingEvent) {
                when (event) {
                    is RecordingEvent.Started -> {
                        _recordingState.value = RecordingState.RECORDING
                        updateNotification()
                    }
                    is RecordingEvent.Stopped -> {
                        _recordingState.value = RecordingState.IDLE
                        updateNotification()
                    }
                    is RecordingEvent.Paused -> {
                        _recordingState.value = RecordingState.PAUSED
                    }
                    is RecordingEvent.Resumed -> {
                        _recordingState.value = RecordingState.RECORDING
                    }
                    is RecordingEvent.Error -> {
                        _recordingState.value = RecordingState.ERROR
                        Timber.e("Recording error: ${event.error}")
                    }
                    is RecordingEvent.SegmentCompleted -> {
                        Timber.d("Recording segment completed: ${event.filePath}")
                    }
                    is RecordingEvent.NewSegmentStarted -> {
                        Timber.d("New recording segment: ${event.filePath}")
                    }
                }
            }
        })

        Timber.i("Storage manager initialized")
        return true
    }

    /**
     * Start local recording.
     * Requires encoder to be initialized and running.
     */
    fun startRecording(): Boolean {
        if (!isStreamingActive) {
            Timber.e("Cannot start recording: encoder not active")
            return false
        }

        // Initialize storage manager if needed
        if (storageManager == null) {
            val config = getEncoderConfig() ?: EncoderConfig.PRESET_1080P
            initializeRecording(config)
        }

        // Set video format from encoder
        val format = encoderService?.getOutputFormat()
        if (format != null) {
            storageManager?.setVideoFormat(format)
        } else {
            Timber.w("Encoder output format not available, will wait for first frame")
        }

        // Add frame listener for recording
        addEncodedFrameListener(recordingFrameListener)

        // Start recording
        val success = storageManager?.startRecording() == true
        if (!success) {
            removeEncodedFrameListener(recordingFrameListener)
            Timber.e("Failed to start recording")
        } else {
            Timber.i("Recording started")
        }

        return success
    }

    /**
     * Start recording with specific configuration.
     */
    fun startRecording(
        config: EncoderConfig,
        segmentDuration: SegmentDuration = SegmentDuration.FIVE_MINUTES
    ): Boolean {
        // Initialize encoder if needed
        if (!isStreamingActive) {
            if (!initializeEncoder(config)) {
                return false
            }
            encoderService?.startEncoding()
            isStreamingActive = true
        }

        // Initialize storage manager with config
        if (storageManager == null) {
            initializeRecording(config, segmentDuration)
        }

        return startRecording()
    }

    /**
     * Stop local recording.
     * @return List of recorded segment file paths
     */
    fun stopRecording(): List<String> {
        if (_recordingState.value == RecordingState.IDLE) {
            Timber.w("Not recording")
            return emptyList()
        }

        // Remove frame listener
        removeEncodedFrameListener(recordingFrameListener)

        // Stop recording
        val segments = storageManager?.stopRecording() ?: emptyList()
        Timber.i("Recording stopped: ${segments.size} segments")

        return segments
    }

    /**
     * Pause recording.
     */
    fun pauseRecording(): Boolean {
        return storageManager?.pauseRecording() == true
    }

    /**
     * Resume recording.
     */
    fun resumeRecording(): Boolean {
        return storageManager?.resumeRecording() == true
    }

    /**
     * Check if recording is active.
     */
    fun isRecording(): Boolean = _recordingState.value == RecordingState.RECORDING

    /**
     * Check if recording is paused.
     */
    fun isRecordingPaused(): Boolean = _recordingState.value == RecordingState.PAUSED

    /**
     * Get recording statistics.
     */
    fun getRecordingStats(): RecordingStats {
        return storageManager?.getRecordingStats() ?: RecordingStats()
    }

    /**
     * Get storage status.
     */
    fun getStorageStatus(): StorageStatus {
        return storageManager?.status?.value ?: StorageStatus()
    }

    /**
     * List all recorded files.
     */
    fun listRecordings(): List<RecordingFile> {
        return storageManager?.listRecordings() ?: emptyList()
    }

    /**
     * Delete a recording file.
     */
    fun deleteRecording(file: RecordingFile): Boolean {
        return storageManager?.deleteRecording(file) == true
    }

    /**
     * Get recordings directory path.
     */
    fun getRecordingsPath(): String {
        return storageManager?.getRecordingsDirectory()?.absolutePath ?: ""
    }

    /**
     * Set segment duration for recording.
     */
    fun setSegmentDuration(duration: SegmentDuration) {
        storageManager?.updateSegmentDuration(duration)
    }

    /**
     * Enforce retention policy (manual cleanup).
     */
    fun enforceRetention() {
        serviceScope.launch {
            storageManager?.enforceRetention()
        }
    }

    /**
     * Release storage manager resources.
     */
    fun releaseStorageManager() {
        removeEncodedFrameListener(recordingFrameListener)
        storageManager?.release()
        storageManager = null
        _recordingState.value = RecordingState.IDLE
    }

    /**
     * Start combined streaming and recording.
     */
    fun startStreamingAndRecording(
        config: EncoderConfig = EncoderConfig.PRESET_1080P,
        segmentDuration: SegmentDuration = SegmentDuration.FIVE_MINUTES,
        rtspPort: Int = 8554
    ): Boolean {
        // Start RTSP streaming
        if (!startRtspStreaming(config, rtspPort)) {
            return false
        }

        // Start recording
        if (storageManager == null) {
            initializeRecording(config, segmentDuration)
        }

        // Small delay to let encoder start
        serviceScope.launch {
            delay(500)
            if (!startRecording()) {
                Timber.e("Failed to start recording alongside streaming")
            }
        }

        return true
    }

    /**
     * Stop combined streaming and recording.
     */
    fun stopStreamingAndRecording(): List<String> {
        val segments = stopRecording()
        stopRtspStreaming()
        return segments
    }

    // ==================== AI Director Integration ====================

    /**
     * Animate zoom to target level with duration.
     * Used by AI Director for smooth transitions.
     */
    fun animateZoom(targetZoom: Float, durationMs: Long) {
        zoomController.animateZoomTo(targetZoom, durationMs)
    }

    /**
     * Set camera to auto focus mode.
     */
    fun setAutoFocus() {
        setFocusMode(FocusMode.CONTINUOUS_VIDEO)
    }

    /**
     * Enable face detection and auto-focus on detected faces.
     */
    fun enableFaceDetectionFocus() {
        // Enable face detection if supported
        currentConfig = currentConfig.copy(focusMode = FocusMode.CONTINUOUS_VIDEO)
        lensDaemonCameraManager.updateConfig(currentConfig)
        // Note: Actual face detection integration depends on device capabilities
        Timber.d("Face detection focus enabled")
    }

    /**
     * Set focus distance directly.
     * @param distance 0.0 = infinity, higher values = closer focus
     */
    fun setFocusDistance(distance: Float) {
        focusController.setManualFocusDistance(distance)
        currentConfig = currentConfig.copy(focusMode = FocusMode.MANUAL)
        lensDaemonCameraManager.updateConfig(currentConfig)
    }

    /**
     * Get maximum zoom ratio for current lens.
     */
    fun getMaxZoom(): Float {
        return zoomController.zoomRange.value.endInclusive
    }

    /**
     * Check if face detection is supported.
     */
    fun supportsFaceDetection(): Boolean {
        return lensDaemonCameraManager.supportsFaceDetection()
    }

    /**
     * Check if manual focus is supported.
     */
    fun supportsManualFocus(): Boolean {
        return focusController.isManualFocusSupported()
    }

    /**
     * Check if focus is currently locked.
     */
    fun isFocusLocked(): Boolean {
        return _focusState.value == FocusState.LOCKED || _focusState.value == FocusState.FOCUS_SUCCESS
    }

    /**
     * Get normalized exposure value (0-1 range).
     */
    fun getNormalizedExposure(): Float {
        val range = exposureController.exposureRange.value
        val current = currentConfig.exposureCompensation.toFloat()
        if (range.endInclusive <= range.start) return 0.5f
        return (current - range.start) / (range.endInclusive - range.start)
    }

    /**
     * Get current motion shakiness (0 = stable, 1 = very shaky).
     * Based on gyroscope/accelerometer data if available.
     */
    fun getMotionShakiness(): Float {
        // Basic implementation - could be enhanced with sensor data
        // For now, return low value when OIS is active
        return if (currentConfig.stabilizationMode == StabilizationMode.OIS ||
                   currentConfig.stabilizationMode == StabilizationMode.OIS_AND_EIS) {
            0.1f
        } else {
            0.3f // Assume some shake without OIS
        }
    }

    /**
     * Get current CPU temperature for thermal monitoring.
     * Uses device-agnostic discovery of thermal zones.
     */
    fun getCurrentCpuTemperature(): Int {
        return try {
            // Discover all thermal zones dynamically
            val thermalDir = java.io.File("/sys/class/thermal")
            if (!thermalDir.exists()) return DEFAULT_CPU_TEMP_FALLBACK

            val zones = thermalDir.listFiles()?.filter {
                it.name.startsWith("thermal_zone")
            } ?: return DEFAULT_CPU_TEMP_FALLBACK

            // Try to find a CPU-specific zone by type
            for (zone in zones) {
                val typeFile = java.io.File(zone, "type")
                val type = try { typeFile.readText().trim().lowercase() } catch (_: Exception) { continue }
                if (type.contains("cpu") || type.contains("soc") || type.contains("tsens")) {
                    readThermalZoneTemp(zone)?.let { return it }
                }
            }

            // Fallback: read first available zone
            for (zone in zones) {
                readThermalZoneTemp(zone)?.let { return it }
            }

            DEFAULT_CPU_TEMP_FALLBACK
        } catch (e: Exception) {
            DEFAULT_CPU_TEMP_FALLBACK
        }
    }

    private fun readThermalZoneTemp(zoneDir: java.io.File): Int? {
        return try {
            val tempFile = java.io.File(zoneDir, "temp")
            if (!tempFile.exists()) return null
            val raw = tempFile.readText().trim().toIntOrNull() ?: return null
            // Normalize: values > 1000 are in millidegrees
            if (raw > 1000) raw / 1000 else raw
        } catch (_: Exception) {
            null
        }
    }

}

