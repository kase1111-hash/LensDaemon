package com.lensdaemon.camera

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Size
import android.view.Surface
import android.view.SurfaceView
import androidx.core.app.NotificationCompat
import com.lensdaemon.LensDaemonApp
import com.lensdaemon.MainActivity
import com.lensdaemon.R
import com.lensdaemon.encoder.AudioConfig
import com.lensdaemon.encoder.AudioEncoder
import com.lensdaemon.encoder.EncodedAudioFrame
import com.lensdaemon.encoder.EncodedFrame
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.encoder.EncoderService
import com.lensdaemon.encoder.EncoderState
import com.lensdaemon.encoder.EncoderStats
import com.lensdaemon.encoder.MicrophonePcmSource
import com.lensdaemon.encoder.VideoCodec
import com.lensdaemon.encoder.toMuxerFormat
import com.lensdaemon.output.MpegTsUdpConfig
import com.lensdaemon.output.MpegTsUdpStats
import com.lensdaemon.output.RecordingStats
import com.lensdaemon.output.RecordingState
import com.lensdaemon.output.RtspServerState
import com.lensdaemon.output.RtspServerStats
import com.lensdaemon.output.SegmentDuration
import com.lensdaemon.storage.RecordingFile
import com.lensdaemon.storage.StorageStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

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
        private const val ACTION_START_RTSP_STREAMING = "com.lensdaemon.action.START_RTSP_STREAMING"
        private const val EXTRA_LENS_TYPE = "lens_type"
        private const val EXTRA_RECORD = "record"
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

        /**
         * Open the camera and serve RTSP with the default encoder settings,
         * optionally recording too. Used to bring the appliance up unattended.
         */
        fun startRtspStreamingIntent(context: Context, record: Boolean = false): Intent {
            return Intent(context, CameraService::class.java).apply {
                action = ACTION_START_RTSP_STREAMING
                putExtra(EXTRA_RECORD, record)
            }
        }

        private const val SNAPSHOT_TIMEOUT_MS = 3000L

        private const val PREFS_NAME = "lensdaemon_stream"
        private const val PREF_AUDIO_ENABLED = "audio_enabled"

        /** Waits between attempts to reopen a camera that was lost while running. */
        private val CAMERA_RECOVERY_DELAYS_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 30_000L)
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

    // Current state (volatile: read from NanoHTTPD threads via isPreviewActive()/isStreaming())
    private var currentConfig = CaptureConfig()
    @Volatile private var isPreviewActive = false
    @Volatile private var isStreamingActive = false

    /** A camera open has been launched and has not finished yet. */
    private val cameraOpenPending = AtomicBoolean(false)

    /** The lens last opened, to reopen after the camera is lost. */
    @Volatile private var lastLensType = LensType.MAIN

    /** Reopens the camera after it was lost while running. */
    private var cameraRecoveryJob: Job? = null

    /**
     * Encoding was asked for in its own right (Start Streaming), so it keeps
     * running when the last output stops. Outputs started on their own (RTSP,
     * MPEG-TS, recording) release the encoder when the last of them stops.
     */
    @Volatile private var encodingRequested = false

    /** Work that needs the encoder service, waiting for it to bind. */
    private val pendingEncoderWork = mutableListOf<() -> Unit>()

    // Focus state observable
    private val _focusState = MutableStateFlow(FocusState.INACTIVE)
    val focusState: StateFlow<FocusState> = _focusState

    // Zoom state observable
    private val _currentZoom = MutableStateFlow(1.0f)
    val currentZoom: StateFlow<Float> = _currentZoom

    // Encoder service connection (Phase 4, volatile: read from HTTP threads)
    @Volatile private var encoderService: EncoderService? = null
    @Volatile private var encoderBound = false
    private var encoderSurface: Surface? = null

    /**
     * The one dispatch hook registered on the encoder service. It is
     * re-registered (remove, then add) on every encoder initialization so
     * start/stop cycles never stack duplicate listeners that would deliver
     * each frame several times.
     */
    private val encoderFrameListener: (EncodedFrame) -> Unit = { frame -> dispatchEncodedFrame(frame) }

    // Preview frames for the dashboard (MJPEG stream and snapshots)
    private val previewFrameGrabber = PreviewFrameGrabber()

    // Encoder state observable
    private val _encoderState = MutableStateFlow(EncoderState.IDLE)
    val encoderState: StateFlow<EncoderState> = _encoderState

    // Frame distribution to all consumers (RTSP, recording, MPEG-TS, etc.)
    private val frameDistributor = FrameDistributor<EncodedFrame>()

    // Phone microphone: AAC frames to the same outputs, on the camera's clock
    private val audioDistributor = FrameDistributor<EncodedAudioFrame>()
    @Volatile private var audioEncoder: AudioEncoder? = null

    /** Whether streams carry the phone's microphone (when RECORD_AUDIO is granted). Remembered across restarts. */
    @Volatile var audioEnabled = true
        private set

    /** Set once onStartCommand has made this a foreground service. */
    @Volatile private var startedInForeground = false

    /** Whether the foreground service currently holds the microphone type. */
    @Volatile private var foregroundHasMicrophone = false

    // Protocol coordinators (isolate transport concerns from camera service)
    private val rtspCoordinator = RtspCoordinator()
    private lateinit var recordingCoordinator: RecordingCoordinator
    private val mpegTsCoordinator = MpegTsCoordinator()

    // Observable state delegated from coordinators
    val rtspServerState: StateFlow<RtspServerState> get() = rtspCoordinator.serverState
    val recordingState: StateFlow<RecordingState> get() = recordingCoordinator.recordingState
    val mpegtsRunning: StateFlow<Boolean> get() = mpegTsCoordinator.running

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

            val pending = pendingEncoderWork.toList()
            pendingEncoderWork.clear()
            pending.forEach { it() }
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
        lensDaemonCameraManager.onImageAvailable = previewFrameGrabber::onImage
        lensDaemonCameraManager.onCameraLost = { scheduleCameraRecovery() }
        audioEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_AUDIO_ENABLED, true)

        // Initialize lens controller with available lenses
        lensController = LensController(systemCameraManager, lensDaemonCameraManager.availableLenses)

        // Initialize coordinators
        recordingCoordinator = RecordingCoordinator(applicationContext, serviceScope)
        recordingCoordinator.onStateChanged = { updateNotification() }
        rtspCoordinator.onKeyframeRequest = { requestKeyFrame() }
        mpegTsCoordinator.onKeyframeRequest = { requestKeyFrame() }

        // Set up zoom change listener
        zoomController.setOnZoomChangedListener { zoom ->
            _currentZoom.value = zoom
            applyZoomToCamera(zoom)
        }

        // Follow the on-screen preview surface. It comes and goes with the
        // activity (screen off, app in the background); the capture session
        // keeps running without it so streaming and recording carry on.
        // collect, not collectLatest: a surface change must never be cancelled
        // halfway through rebuilding the session.
        serviceScope.launch {
            previewSurfaceProvider.surfaceState.collect { state ->
                when (state) {
                    is SurfaceState.Available -> {
                        Timber.d("Preview surface available: ${state.width}x${state.height}")
                        if (isPreviewActive) lensDaemonCameraManager.setPreviewSurface(state.surface)
                    }
                    is SurfaceState.Unavailable -> {
                        Timber.d("Preview surface gone; capture continues without it")
                        if (isPreviewActive) lensDaemonCameraManager.setPreviewSurface(null)
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

    /**
     * Run [work] now if the encoder service is bound, otherwise as soon as it
     * binds. A start intent can arrive right after onCreate, before binding
     * completes.
     */
    private fun whenEncoderBound(work: () -> Unit) {
        if (encoderBound && encoderService != null) work() else pendingEncoderWork.add(work)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("CameraService onStartCommand: ${intent?.action}")

        // Start as foreground service
        startForegroundWithTypes()

        when (intent?.action) {
            ACTION_START_PREVIEW -> {
                val lensTypeName = intent.getStringExtra(EXTRA_LENS_TYPE) ?: LensType.MAIN.name
                startPreview(LensType.valueOf(lensTypeName))
            }
            ACTION_STOP_PREVIEW -> {
                stopPreview()
            }
            ACTION_START_STREAMING -> {
                whenEncoderBound { startStreaming() }
            }
            ACTION_STOP_STREAMING -> {
                stopStreaming()
            }
            ACTION_START_RTSP_STREAMING -> {
                val record = intent.getBooleanExtra(EXTRA_RECORD, false)
                whenEncoderBound {
                    if (startRtspStreaming() && record && !startRecording()) {
                        Timber.e("Auto-start: RTSP is up but recording failed to start")
                    }
                }
            }
        }

        return START_STICKY
    }

    /**
     * Run in the foreground as a camera service, and as a microphone service
     * too when RECORD_AUDIO is granted: from Android 11 a background app
     * only hears silence from the microphone otherwise. The microphone type
     * can be refused (e.g. when starting from the background), in which case
     * the camera keeps running and audio may be silent.
     */
    private fun startForegroundWithTypes() {
        val notification = createNotification()
        val camera = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        val withMicrophone = if (hasMicrophonePermission() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            camera or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            camera
        }
        if (withMicrophone != camera && tryStartForeground(notification, withMicrophone)) {
            foregroundHasMicrophone = true
        } else {
            Timber.i("Running as a camera service without the microphone type")
            tryStartForeground(notification, camera)
        }
        startedInForeground = true
    }

    /** startForeground with [types]; false if the system refused them. */
    private fun tryStartForeground(notification: Notification, types: Int): Boolean = try {
        startForeground(LensDaemonApp.NOTIFICATION_ID, notification, types)
        true
    } catch (e: SecurityException) {
        Timber.w(e, "Foreground service types $types refused")
        false
    } catch (e: IllegalStateException) {
        // ForegroundServiceStartNotAllowedException: not allowed from the background
        Timber.w(e, "Foreground service types $types refused")
        false
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("CameraService destroyed")
        stopStreaming()
        recordingCoordinator.release()
        frameDistributor.removeAll()
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
     * Open the camera with [lensType] and start capturing, unless it is already
     * running (or opening). The activity calls this every time it binds and
     * outputs call it when they start, and neither may reopen the camera under
     * a running stream; use [switchLens] to change lenses.
     */
    fun startPreview(lensType: LensType = LensType.MAIN) {
        val state = lensDaemonCameraManager.cameraState.value
        if (isPreviewActive && state != CameraState.CLOSED && state != CameraState.ERROR) {
            Timber.d("Camera already running ($state); keeping it")
            return
        }
        if (!cameraOpenPending.compareAndSet(false, true)) return
        serviceScope.launch {
            try {
                openCameraAndStartPreview(lensType)
            } finally {
                cameraOpenPending.set(false)
            }
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
                    lastLensType = it.lensType
                    lensController.setCurrentLens(it)
                    initializeControllersForCamera(it.cameraId)
                }
                isPreviewActive = true
                startCameraPreview(currentPreviewSurface())
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
                lastLensType = lens.lensType
                lensController.setCurrentLens(lens)
                initializeControllersForCamera(lens.cameraId)
                isPreviewActive = true
                startCameraPreview(currentPreviewSurface())
                updateNotification()
            } else {
                Timber.e("Failed to open camera")
            }
        } catch (e: Exception) {
            Timber.e(e, "Error opening camera")
        }
    }

    /** The on-screen preview surface, or null while the activity is not showing. */
    private fun currentPreviewSurface(): Surface? =
        (previewSurfaceProvider.surfaceState.value as? SurfaceState.Available)?.surface

    /**
     * Start the capture session. [surface] is the on-screen preview, if the
     * activity is showing; without it the camera still feeds the encoder and
     * the dashboard preview.
     */
    private suspend fun startCameraPreview(surface: Surface?) {
        try {
            Timber.i("Starting camera capture (on-screen preview: ${surface != null})")
            lensDaemonCameraManager.startPreview(surface, currentConfig)
        } catch (e: Exception) {
            Timber.e(e, "Error starting camera preview")
        }
    }

    /**
     * The camera was taken away while running: another app opened it, the
     * camera service restarted, or the device reported an error. Reopen it,
     * backing off between attempts, so a stream recovers without anyone
     * pressing Start again; the encoder surface is still attached and the new
     * capture session feeds it.
     */
    private fun scheduleCameraRecovery() {
        if (!isPreviewActive) return
        serviceScope.launch {
            if (cameraRecoveryJob?.isActive == true) return@launch
            cameraRecoveryJob = launch recovery@{
                for (waitMs in CAMERA_RECOVERY_DELAYS_MS) {
                    delay(waitMs)
                    if (!isPreviewActive) return@recovery
                    Timber.w("Camera lost; reopening the $lastLensType lens")
                    openCameraAndStartPreview(lastLensType)
                    if (lensDaemonCameraManager.cameraState.value == CameraState.PREVIEWING) {
                        Timber.i("Camera recovered")
                        return@recovery
                    }
                }
                Timber.e("Camera could not be reopened; the next start will try again")
            }
        }
    }

    /**
     * Stop camera preview.
     */
    fun stopPreview() {
        Timber.i("Stopping preview")
        cameraRecoveryJob?.cancel()
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

    /** The outputs that share the encoder. */
    private enum class Output { RTSP, MPEGTS, RECORDING }

    /**
     * Initialize encoder with configuration.
     * @return true if initialization successful
     */
    fun initializeEncoder(config: EncoderConfig = EncoderConfig.PRESET_1080P): Boolean {
        if (!encoderBound || encoderService == null) {
            Timber.e("EncoderService not bound")
            return false
        }

        val surface = encoderService?.initializeEncoder(config)
        if (surface == null) {
            Timber.e("Failed to initialize encoder")
            return false
        }
        encoderSurface = surface

        // Dispatch encoded frames to the registered outputs, exactly once per frame
        encoderService?.removeFrameListener(encoderFrameListener)
        encoderService?.addFrameListener(encoderFrameListener)

        // Hand the encoder's input surface to the camera, and have the camera
        // deliver frames at the encoder's rate. The capture session is rebuilt
        // with the surface as a target; until that completes the codec simply
        // waits for its first frame.
        currentConfig = currentConfig.copy(frameRate = config.frameRate)
        val captureConfig = currentConfig
        serviceScope.launch {
            if (!lensDaemonCameraManager.addEncoderSurface(surface, captureConfig)) {
                Timber.e("Camera could not attach the encoder surface; nothing will be encoded")
            }
        }

        Timber.i("Encoder initialized: ${config.width}x${config.height} @ ${config.bitrateBps}bps")
        return true
    }

    /**
     * Start the initialized encoder and mark streaming active.
     * @return false if the encoder refused to start
     */
    private fun beginEncoding(): Boolean {
        if (encoderService?.startEncoding() != true) {
            Timber.e("Encoder failed to start")
            return false
        }
        isStreamingActive = true
        startAudioIfWanted()
        updateNotification()
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
     * Make sure the encoder is running for an output that is starting.
     *
     * A running encoder is shared as it is: restarting it for the new output
     * would cut every other output's viewers off mid-stream. Otherwise the
     * camera is opened if needed and a fresh encoder is started with [config].
     *
     * @return true if the encoder is running
     */
    @Synchronized
    private fun ensureEncoding(config: EncoderConfig): Boolean {
        // Opens the camera only if it is not running: never opened, or closed
        // or failed since. A running stream whose camera went away recovers.
        startPreview(lastLensType)

        if (isStreamingActive) {
            val running = getEncoderConfig()
            if (running != null && running != config) {
                Timber.i(
                    "Encoder already running at ${running.width}x${running.height} " +
                        "${running.frameRate}fps ${running.codec}; the new output shares it"
                )
            }
            return true
        }

        // A stopped MediaCodec cannot be restarted, so anything but a freshly
        // initialized encoder with this configuration is replaced.
        val fresh = encoderSurface != null &&
            encoderService?.encoderState?.value == EncoderState.READY &&
            getEncoderConfig() == config
        if (!fresh && !initializeEncoder(config)) {
            Timber.e("Failed to initialize encoder")
            return false
        }
        return beginEncoding()
    }

    /**
     * Start encoding with [config] and keep encoding until [stopStreaming],
     * whether or not an output is attached.
     *
     * If the encoder already runs with other settings it is restarted with
     * these. Attached RTSP and MPEG-TS viewers stay connected and pick the new
     * stream up at its first keyframe; a recording is split into a new
     * segment, since one MP4 track cannot change format.
     *
     * @return true if the encoder is running with [config]
     */
    @Synchronized
    fun startStreaming(config: EncoderConfig = EncoderConfig.PRESET_1080P): Boolean {
        encodingRequested = true
        val running = getEncoderConfig()
        if (isStreamingActive && running != null && running != config) {
            return restartEncoder(config)
        }
        if (!ensureEncoding(config)) {
            encodingRequested = false
            return false
        }
        Timber.i("Streaming started: ${config.width}x${config.height} ${config.frameRate}fps")
        return true
    }

    /**
     * Replace the running encoder with one using [config], keeping the outputs.
     */
    private fun restartEncoder(config: EncoderConfig): Boolean {
        val previous = getEncoderConfig()
        Timber.i(
            "Restarting encoder: ${previous?.width}x${previous?.height} ${previous?.frameRate}fps -> " +
                "${config.width}x${config.height} ${config.frameRate}fps"
        )
        val wasRecording = isRecordingActive()
        if (wasRecording) {
            detachRecorder()
            recordingCoordinator.stopRecording()
        }

        isStreamingActive = false
        encoderService?.stopEncoding()

        // Tell the outputs before the new encoder's first (codec-config)
        // buffer reaches them, so they parse it as the right codec.
        if (previous?.codec != config.codec) {
            rtspCoordinator.updateCodecConfig(config.codec, null, null, null)
            rtspCoordinator.disconnectViewers()
            mpegTsCoordinator.updateCodec(config.codec)
        }
        rtspCoordinator.setStreamConfig(config)

        if (!initializeEncoder(config) || !beginEncoding()) {
            Timber.e("Encoder restart failed; stopping outputs")
            stopStreaming()
            // stopStreaming() found the encoder already marked stopped, so
            // detach and release whatever is left of it here
            releaseEncoderAfterDetach(encoderSurface)
            stopAudio()
            return false
        }

        if (wasRecording && !startRecording()) {
            Timber.e("Recording could not continue after the encoder restart")
        }
        return true
    }

    /**
     * Stop streaming: every output (RTSP server, MPEG-TS publisher, recording)
     * and then the encoder, so nothing is left serving viewers a frozen stream.
     */
    @Synchronized
    fun stopStreaming() {
        encodingRequested = false
        if (isRecordingActive()) {
            detachRecorder()
            recordingCoordinator.stopRecording()
        }
        stopRtspServer()
        stopMpegTsPublisher()
        stopEncoder()
        stopAudio()
    }

    /**
     * Stop the encoder unless something still needs it: an explicit
     * [startStreaming], or an output other than [stopping].
     */
    @Synchronized
    private fun stopEncoderIfUnused(stopping: Output) {
        val stillNeeded = encodingRequested ||
            (stopping != Output.RTSP && rtspCoordinator.isRunning()) ||
            (stopping != Output.MPEGTS && mpegTsCoordinator.isRunning()) ||
            (stopping != Output.RECORDING && isRecordingActive())
        if (stillNeeded) {
            Timber.i("Encoder kept running for the remaining outputs")
            return
        }
        stopEncoder()
    }

    private fun isRecordingActive(): Boolean =
        recordingCoordinator.isRecording() || recordingCoordinator.isPaused()

    private fun stopEncoder() {
        if (!isStreamingActive) return

        isStreamingActive = false
        stopAudio()
        updateNotification()
        releaseEncoderAfterDetach(encoderSurface)
    }

    // ==================== Audio (phone microphone) ====================

    /**
     * Turn the phone's microphone on or off for every output. Takes effect
     * at once while streaming: RTSP viewers that connect afterwards are
     * offered (or not offered) an audio track, MPEG-TS re-announces its
     * streams, and recordings change from their next segment.
     */
    @Synchronized
    fun setAudioEnabled(enabled: Boolean) {
        audioEnabled = enabled
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_AUDIO_ENABLED, enabled).apply()
        if (!enabled) stopAudio() else if (isStreamingActive) startAudioIfWanted()
    }

    fun hasMicrophonePermission(): Boolean =
        checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** True while the microphone is being captured and encoded. */
    fun isAudioActive(): Boolean = audioEncoder?.isRunning == true

    /** The audio settings in use, or null when no audio is being captured. */
    fun getAudioConfig(): AudioConfig? = audioEncoder?.takeIf { it.isRunning }?.config

    fun getAudioFramesEncoded(): Long = audioEncoder?.getFramesEncoded() ?: 0

    /**
     * Start capturing the microphone alongside the running video, unless
     * audio is switched off, the permission is missing or there is no
     * microphone; streams then simply carry video only.
     */
    private fun startAudioIfWanted() {
        if (!audioEnabled || audioEncoder?.isRunning == true) return
        if (!hasMicrophonePermission()) {
            Timber.i("Microphone permission not granted; streaming video only")
            return
        }
        // An encoder whose thread died on an error: clear it before starting afresh
        if (audioEncoder != null) stopAudio()
        // A service started before the permission was granted needs the microphone type now
        if (startedInForeground && !foregroundHasMicrophone) startForegroundWithTypes()

        val source = MicrophonePcmSource(AudioConfig(), lensDaemonCameraManager.mediaClock)
        val encoder = AudioEncoder(source, onFrame = audioDistributor::dispatch)
        if (!encoder.start()) {
            Timber.w("Microphone unavailable; streaming video only")
            return
        }
        audioEncoder = encoder
        publishAudioConfig(encoder.config)
    }

    private fun stopAudio() {
        val encoder = audioEncoder ?: return
        audioEncoder = null
        publishAudioConfig(null)
        encoder.stop()
    }

    /** Tell every output what audio there is (or that there is none). */
    private fun publishAudioConfig(config: AudioConfig?) {
        rtspCoordinator.setAudioConfig(config)
        mpegTsCoordinator.setAudioConfig(config)
        recordingCoordinator.setAudioFormat(config?.toMuxerFormat())
    }

    /**
     * Detach [surface] from the camera first so it never renders into a dead
     * surface, then drop its encoder: a stopped MediaCodec cannot be
     * restarted, so the next start builds a fresh one.
     *
     * Runs off the main thread (codec teardown and the service lock can both
     * take a while) and only touches [surface]'s own encoder: if a start has
     * built a new encoder in the meantime, that one is left alone.
     */
    private fun releaseEncoderAfterDetach(surface: Surface?) {
        serviceScope.launch(Dispatchers.Default) {
            lensDaemonCameraManager.removeEncoderSurface(surface)
            synchronized(this@CameraService) {
                if (!isStreamingActive && encoderSurface === surface) {
                    encoderService?.stopEncoding()
                    encoderService?.releaseEncoder()
                    encoderSurface = null
                }
            }
            Timber.i("Streaming stopped")
        }
    }

    /**
     * Release encoder resources.
     */
    fun releaseEncoder() {
        isStreamingActive = false
        serviceScope.launch {
            lensDaemonCameraManager.removeEncoderSurface()
            encoderService?.releaseEncoder()
            encoderSurface = null
            Timber.i("Encoder released")
        }
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

    // ==================== Preview frames (MJPEG / snapshot) ====================

    /**
     * Route JPEG preview frames to [sink] while [demand] reports that someone
     * is watching (for example the MJPEG stream has clients).
     */
    fun setPreviewFrameSink(demand: () -> Boolean, sink: (ByteArray) -> Unit) {
        previewFrameGrabber.streamDemand = demand
        previewFrameGrabber.streamSink = sink
    }

    fun clearPreviewFrameSink() {
        previewFrameGrabber.streamSink = null
        previewFrameGrabber.streamDemand = { false }
    }

    /**
     * Capture the next preview frame as a JPEG. Blocks the calling thread
     * (never the main thread) and returns null if the camera delivers nothing
     * within [timeoutMs].
     */
    fun captureSnapshot(timeoutMs: Long = SNAPSHOT_TIMEOUT_MS): ByteArray? =
        previewFrameGrabber.captureSnapshot(timeoutMs)

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
        frameDistributor.addListener(listener)
    }

    /**
     * Remove encoded frame listener.
     */
    fun removeEncodedFrameListener(listener: (EncodedFrame) -> Unit) {
        frameDistributor.removeListener(listener)
    }

    private fun dispatchEncodedFrame(frame: EncodedFrame) {
        frameDistributor.dispatch(frame)
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
     * Start the RTSP server on [port] (or keep the running one) and feed it
     * the encoder's frames. Starting it twice never delivers a frame twice.
     */
    fun startRtspServer(port: Int = 8554): Boolean {
        frameDistributor.addListener(rtspCoordinator.frameListener)
        audioDistributor.addListener(rtspCoordinator.audioListener)
        val success = rtspCoordinator.start(port)
        if (!success) {
            frameDistributor.removeListener(rtspCoordinator.frameListener)
            audioDistributor.removeListener(rtspCoordinator.audioListener)
            return false
        }
        rtspCoordinator.setMediaClock { lensDaemonCameraManager.mediaClock.nowUs() }
        rtspCoordinator.setAudioConfig(getAudioConfig())
        // Configure after start: before it there is no server to configure.
        // Parameter sets the encoder has not produced yet are learned from
        // its codec-config buffer as it goes out.
        val config = getEncoderConfig() ?: EncoderConfig.PRESET_1080P
        rtspCoordinator.updateCodecConfig(config.codec, getSps(), getPps(), getVps())
        rtspCoordinator.setStreamConfig(config)
        return true
    }

    fun stopRtspServer() {
        frameDistributor.removeListener(rtspCoordinator.frameListener)
        audioDistributor.removeListener(rtspCoordinator.audioListener)
        rtspCoordinator.stop()
    }

    fun getRtspUrl(): String? = rtspCoordinator.getRtspUrl()

    fun getRtspServerStats(): RtspServerStats? = rtspCoordinator.getStats()

    fun getRtspClientCount(): Int = rtspCoordinator.getActiveConnections()

    fun getRtspPlayingCount(): Int = rtspCoordinator.getPlayingClients()

    fun isRtspServerRunning(): Boolean = rtspCoordinator.isRunning()

    /**
     * Serve the camera over RTSP. Uses the running encoder if there is one
     * (another output started it), otherwise starts one with [config].
     */
    @Synchronized
    fun startRtspStreaming(
        config: EncoderConfig = EncoderConfig.PRESET_1080P,
        rtspPort: Int = 8554
    ): Boolean {
        if (!ensureEncoding(config)) {
            return false
        }

        if (!startRtspServer(rtspPort)) {
            Timber.e("Failed to start RTSP server")
            stopEncoderIfUnused(Output.RTSP)
            return false
        }

        updateNotification()
        Timber.i("RTSP streaming started: ${getRtspUrl()}")
        return true
    }

    /**
     * Stop serving RTSP. The encoder keeps running if another output (or an
     * explicit Start Streaming) still needs it.
     */
    @Synchronized
    fun stopRtspStreaming() {
        stopRtspServer()
        stopEncoderIfUnused(Output.RTSP)
        Timber.i("RTSP streaming stopped")
    }

    // ==================== MPEG-TS/UDP Publisher ====================

    /**
     * Start the MPEG-TS/UDP publisher (restarting it if [config] differs from
     * the running one) and feed it the encoder's frames.
     */
    fun startMpegTsPublisher(config: MpegTsUdpConfig = MpegTsUdpConfig()): Boolean {
        val codec = getEncoderConfig()?.codec ?: VideoCodec.H264
        frameDistributor.addListener(mpegTsCoordinator.frameListener)
        audioDistributor.addListener(mpegTsCoordinator.audioListener)

        mpegTsCoordinator.setAudioConfig(getAudioConfig())
        val success = mpegTsCoordinator.start(config, codec, getSps(), getPps(), getVps())
        if (!success) {
            frameDistributor.removeListener(mpegTsCoordinator.frameListener)
            audioDistributor.removeListener(mpegTsCoordinator.audioListener)
        }
        return success
    }

    fun stopMpegTsPublisher() {
        frameDistributor.removeListener(mpegTsCoordinator.frameListener)
        audioDistributor.removeListener(mpegTsCoordinator.audioListener)
        mpegTsCoordinator.stop()
    }

    fun isMpegTsRunning(): Boolean = mpegTsCoordinator.isRunning()

    fun getMpegTsStats(): MpegTsUdpStats? = mpegTsCoordinator.getStats()

    /**
     * Publish the camera as MPEG-TS over UDP. Uses the running encoder if
     * there is one, otherwise starts one with [encoderConfig].
     */
    @Synchronized
    fun startMpegTsStreaming(
        encoderConfig: EncoderConfig = EncoderConfig.PRESET_1080P,
        mpegtsConfig: MpegTsUdpConfig = MpegTsUdpConfig()
    ): Boolean {
        if (!ensureEncoding(encoderConfig)) {
            return false
        }

        if (!startMpegTsPublisher(mpegtsConfig)) {
            Timber.e("Failed to start MPEG-TS/UDP publisher")
            stopEncoderIfUnused(Output.MPEGTS)
            return false
        }

        updateNotification()
        Timber.i("MPEG-TS/UDP streaming started on port ${mpegtsConfig.port}")
        return true
    }

    /**
     * Stop the MPEG-TS/UDP publisher. The encoder keeps running if another
     * output (or an explicit Start Streaming) still needs it.
     */
    @Synchronized
    fun stopMpegTsStreaming() {
        stopMpegTsPublisher()
        stopEncoderIfUnused(Output.MPEGTS)
        Timber.i("MPEG-TS/UDP streaming stopped")
    }

    // ==================== Local Recording (Phase 7) ====================

    fun initializeRecording(
        encoderConfig: EncoderConfig = EncoderConfig.PRESET_1080P,
        segmentDuration: SegmentDuration = SegmentDuration.FIVE_MINUTES
    ): Boolean {
        return recordingCoordinator.initialize(encoderConfig, segmentDuration)
    }

    fun startRecording(): Boolean {
        if (!isStreamingActive) {
            Timber.e("Cannot start recording: encoder not active")
            return false
        }

        prepareRecorder(recordingCoordinator.segmentDuration)

        val format = encoderService?.getOutputFormat()
        if (format != null) {
            // The format lacks csd until the encoder has produced output; the
            // cached parameter sets (or the stream itself) complete it.
            recordingCoordinator.setVideoFormat(format, getSps(), getPps(), getVps())
        }
        // Every segment opens on a requested keyframe
        recordingCoordinator.onKeyFrameRequest = { encoderService?.requestKeyFrame() }

        frameDistributor.addListener(recordingCoordinator.frameListener)
        audioDistributor.addListener(recordingCoordinator.audioListener)

        val success = recordingCoordinator.startRecording()
        if (!success) {
            detachRecorder()
        }
        return success
    }

    /**
     * Record locally. Uses the running encoder if there is one (recording
     * alongside a stream), otherwise starts one with [config].
     */
    @Synchronized
    fun startRecording(
        config: EncoderConfig,
        segmentDuration: SegmentDuration = SegmentDuration.FIVE_MINUTES
    ): Boolean {
        if (!ensureEncoding(config)) {
            return false
        }

        prepareRecorder(segmentDuration)
        val started = startRecording()
        if (!started) stopEncoderIfUnused(Output.RECORDING)
        return started
    }

    /**
     * Stop recording. The encoder keeps running if a stream (or an explicit
     * Start Streaming) still needs it.
     */
    @Synchronized
    fun stopRecording(): List<String> {
        detachRecorder()
        val segments = recordingCoordinator.stopRecording()
        stopEncoderIfUnused(Output.RECORDING)
        return segments
    }

    /** Stop feeding the recorder video and audio. */
    private fun detachRecorder() {
        frameDistributor.removeListener(recordingCoordinator.frameListener)
        audioDistributor.removeListener(recordingCoordinator.audioListener)
    }

    /**
     * Set the recorder up for the running encoder. The file writer takes its
     * MIME type, NAL parser and track size from these settings, so a recorder
     * built for other ones (an earlier stream, or settings that a shared
     * encoder did not adopt) would never find a keyframe it can parse.
     */
    private fun prepareRecorder(segmentDuration: SegmentDuration) {
        val running = getEncoderConfig() ?: return
        val prepared = recordingCoordinator.getEncoderConfig()
        if (prepared != null && prepared.codec == running.codec && prepared.resolution == running.resolution) {
            if (segmentDuration != recordingCoordinator.segmentDuration) {
                recordingCoordinator.setSegmentDuration(segmentDuration)
            }
            return
        }
        if (prepared != null) {
            Timber.i("Re-initializing the recorder for ${running.width}x${running.height} ${running.codec}")
            recordingCoordinator.release()
        }
        recordingCoordinator.initialize(running, segmentDuration)
    }

    fun pauseRecording(): Boolean = recordingCoordinator.pauseRecording()

    fun resumeRecording(): Boolean = recordingCoordinator.resumeRecording()

    fun isRecording(): Boolean = recordingCoordinator.isRecording()

    fun isRecordingPaused(): Boolean = recordingCoordinator.isPaused()

    fun getRecordingStats(): RecordingStats = recordingCoordinator.getStats()

    fun getStorageStatus(): StorageStatus = recordingCoordinator.getStorageStatus()

    fun listRecordings(): List<RecordingFile> = recordingCoordinator.listRecordings()

    fun deleteRecording(file: RecordingFile): Boolean = recordingCoordinator.deleteRecording(file)

    fun getRecordingsPath(): String = recordingCoordinator.getRecordingsPath()

    fun setSegmentDuration(duration: SegmentDuration) {
        recordingCoordinator.setSegmentDuration(duration)
    }

    fun enforceRetention() {
        recordingCoordinator.enforceRetention()
    }

    fun releaseStorageManager() {
        detachRecorder()
        recordingCoordinator.release()
    }

    fun startStreamingAndRecording(
        config: EncoderConfig = EncoderConfig.PRESET_1080P,
        segmentDuration: SegmentDuration = SegmentDuration.FIVE_MINUTES,
        rtspPort: Int = 8554
    ): Boolean {
        if (!startRtspStreaming(config, rtspPort)) {
            return false
        }
        recordingCoordinator.setSegmentDuration(segmentDuration)

        serviceScope.launch {
            delay(500)
            if (!startRecording()) {
                Timber.e("Failed to start recording alongside streaming")
            }
        }

        return true
    }

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
        return _focusState.value == FocusState.FOCUSED
    }

    /**
     * Get normalized exposure value (0-1 range).
     */
    fun getNormalizedExposure(): Float {
        val range = exposureController.getExposureCompensationRange()
        val current = currentConfig.exposureCompensation.toFloat()
        if (range.upper <= range.lower) return 0.5f
        return (current - range.lower) / (range.upper - range.lower)
    }

    /**
     * Get current motion shakiness (0 = stable, 1 = very shaky).
     * Based on gyroscope/accelerometer data if available.
     */
    fun getMotionShakiness(): Float {
        // Basic implementation - could be enhanced with sensor data
        // For now, return low value when OIS is active
        return if (currentConfig.stabilizationMode == StabilizationMode.OPTICAL ||
                   currentConfig.stabilizationMode == StabilizationMode.HYBRID) {
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

