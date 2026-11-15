package com.lensdaemon.camera

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.camera2.CameraManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import android.view.SurfaceView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
import com.lensdaemon.storage.RetentionResult
import com.lensdaemon.storage.StorageStatus
import com.lensdaemon.thermal.ThermalService
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
        /** Sent by earlier builds' boot start; handled like [ACTION_AUTO_START] with RTSP on. */
        private const val ACTION_START_RTSP_STREAMING = "com.lensdaemon.action.START_RTSP_STREAMING"
        private const val ACTION_AUTO_START = "com.lensdaemon.action.AUTO_START"
        private const val EXTRA_LENS_TYPE = "lens_type"
        private const val EXTRA_RTSP = "rtsp"
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
         * Bring the appliance up unattended (kiosk auto-start at boot): open
         * the camera and start RTSP when [rtsp] and a recording when [record],
         * each on its own, with the saved stream defaults (see
         * [CameraService.streamDefaults]). Send it with
         * ContextCompat.startForegroundService.
         */
        fun autoStartIntent(context: Context, rtsp: Boolean, record: Boolean): Intent {
            return Intent(context, CameraService::class.java).apply {
                action = ACTION_AUTO_START
                putExtra(EXTRA_RTSP, rtsp)
                putExtra(EXTRA_RECORD, record)
            }
        }

        /** [autoStartIntent] with RTSP on: serve RTSP with the saved stream defaults, recording too when [record]. */
        fun startRtspStreamingIntent(context: Context, record: Boolean = false): Intent =
            autoStartIntent(context, rtsp = true, record = record)

        private const val SNAPSHOT_TIMEOUT_MS = 3000L

        private const val PREFS_NAME = "lensdaemon_stream"
        private const val PREF_AUDIO_ENABLED = "audio_enabled"
        private const val PREF_STREAM_DEFAULTS = "stream_defaults"

        /** Times of the automatic restarts after Android stopped the app, for [RestartBudget]. */
        private const val PREF_RESTART_TIMES = "restart_times"

        /** RTSP port of the 1080p30 fallback when an unattended start fails with the saved defaults. */
        private const val DEFAULT_RTSP_PORT = 8554

        /** Tag of the wake lock and Wi-Fi lock held while the encoder runs. */
        private const val STREAM_LOCK_TAG = "LensDaemon:Streaming"

        /** The thermal bitrate cut never takes the bitrate below this (nor raises it). */
        private const val THERMAL_MIN_BITRATE_BPS = 500_000
        private const val MAX_THERMAL_BITRATE_CUT = 90

        /** Waits between attempts to reopen a camera that was lost while running. */
        private val CAMERA_RECOVERY_DELAYS_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L, 30_000L)

        /** Waits before each attempt to rebuild an encoder that failed while running. */
        private val ENCODER_RECOVERY_DELAYS_MS = listOf(1_000L, 2_000L, 5_000L, 10_000L, 30_000L)

        /** A rebuilt encoder that ran this long has recovered: a later failure starts the waits afresh. */
        private const val ENCODER_HEALTHY_MS = 60_000L

        /** Focus modes to fall back to, in order, when a newly opened lens lacks the one in use. */
        private val FOCUS_FALLBACKS = listOf(FocusMode.CONTINUOUS_VIDEO, FocusMode.AUTO, FocusMode.MANUAL)
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

    /**
     * The encoder settings asked for, before any thermal limit: by the start
     * that built the running encoder, a later Start Streaming, or a bitrate
     * change. Null while no encoder runs. Thermal limit changes, rebuilds and
     * the saved stream defaults start from it, never from the limited settings.
     */
    @Volatile private var requestedEncoderConfig: EncoderConfig? = null

    /** The thermal governor's CRITICAL limits, applied to every encoder (see [ThermalEncoderLimits]). */
    @Volatile private var thermalReduceResolution = false
    @Volatile private var thermalReduceFramerate = false

    /** The thermal bitrate cut, in percent of the configured bitrate (0 = none). */
    @Volatile private var thermalBitrateCut = 0

    /** What a thermal pause stopped, to restart once the phone has cooled down. */
    private data class PausedOutputs(
        val encoder: EncoderConfig,
        val encodingRequested: Boolean,
        val rtspPort: Int?,
        val mpegTs: MpegTsUdpConfig?,
        val recording: SegmentDuration?,
        val recordingPaused: Boolean
    ) {
        fun restartsAnything(): Boolean = encodingRequested || rtspPort != null || mpegTs != null || recording != null
    }

    /** Set while a thermal pause holds the outputs stopped; a start or stop by the user clears it ([cancelThermalPause]). */
    @Volatile private var thermalPaused: PausedOutputs? = null

    /** Rebuilds the encoder after it failed while running (see [scheduleEncoderRecovery]). */
    @Volatile private var encoderRecoveryJob: Job? = null

    /** Index of the next wait in [ENCODER_RECOVERY_DELAYS_MS]. */
    @Volatile private var encoderRecoveryStep = 0

    /** When the encoder was last rebuilt after a failure ([SystemClock.elapsedRealtime]). */
    @Volatile private var encoderRebuiltAtMs = 0L

    /** A recording a failed encoder rebuild cut off, to continue once the encoder runs again. */
    @Volatile private var recordingInterrupted = false

    /** Whether that cut-off recording was paused, so it continues paused. */
    @Volatile private var interruptedRecordingPaused = false

    /**
     * Keeps the CPU running while the encoder runs, so outputs carry on with
     * the screen off, and during a thermal pause, so the thermal monitor keeps
     * measuring and the outputs come back once the phone has cooled down.
     */
    private val streamWakeLock: PowerManager.WakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, STREAM_LOCK_TAG)
            .apply { setReferenceCounted(false) }
    }

    /**
     * Keeps Wi-Fi out of power save while the encoder runs; null on a phone
     * without Wi-Fi. From Android 14 the system turns this mode into a
     * low-latency lock, with that lock's restrictions.
     */
    @Suppress("DEPRECATION")
    private val streamWifiLock: WifiManager.WifiLock? by lazy {
        applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, STREAM_LOCK_TAG)
            ?.apply { setReferenceCounted(false) }
    }

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

    /** Set once onStartCommand has made this a foreground service (not when Android refused it). */
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
                    // A codec that fails while outputs run is rebuilt, keeping them
                    if (state == EncoderState.ERROR && isStreamingActive) scheduleEncoderRecovery()
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
        audioEnabled = streamPrefs().getBoolean(PREF_AUDIO_ENABLED, true)

        // Initialize lens controller with available lenses
        lensController = LensController(systemCameraManager, lensDaemonCameraManager.availableLenses)

        // Initialize coordinators
        recordingCoordinator = RecordingCoordinator(applicationContext, serviceScope)
        recordingCoordinator.onStateChanged = { updateNotification() }
        // A full disk stops the recording through here (on an IO thread), so an
        // encoder started only for the recording is released too
        recordingCoordinator.onStorageFull = { stopRecording() }
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

        // A null intent (not an intent without an action, which is the app's
        // own plain start): Android restarted the service after the process died
        if (intent == null) {
            handleStickyRestart()
            return START_STICKY
        }

        when (intent.action) {
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
            ACTION_AUTO_START, ACTION_START_RTSP_STREAMING -> {
                launchAutoStart(
                    rtsp = intent.getBooleanExtra(EXTRA_RTSP, true),
                    record = intent.getBooleanExtra(EXTRA_RECORD, false)
                )
            }
        }

        return START_STICKY
    }

    /**
     * Android restarted this service after the app's process died (a crash or
     * a kill). With kiosk mode, its auto-start and crashRecovery.autoRestart
     * on, and within the restart budget (maxRestartAttempts within
     * restartWindowMinutes, see [RestartBudget]), bring the appliance back: the
     * kiosk auto-start outputs with the saved stream defaults, thermal
     * monitoring, and, as device owner, the app's screen so it pins again.
     */
    private fun handleStickyRestart() {
        val kiosk = (application as LensDaemonApp).kioskManager
        val config = kiosk.getConfig()
        val recovery = config.crashRecovery
        if (!config.enabled || !config.autoStart.enabled || !recovery.autoRestart) {
            Timber.i("Restarted by Android; kiosk auto-restart is off, so no output is started")
            return
        }
        val prefs = streamPrefs()
        val now = System.currentTimeMillis()
        val recent = RestartBudget.prune(
            RestartBudget.decode(prefs.getString(PREF_RESTART_TIMES, null)), now, recovery.restartWindowMinutes
        )
        if (!RestartBudget.allow(recent, now, recovery.maxRestartAttempts, recovery.restartWindowMinutes)) {
            Timber.e(
                "Restarted ${recent.size} times within ${recovery.restartWindowMinutes} minutes; " +
                    "not starting the outputs again"
            )
            return
        }
        // commit(): a process that dies again at once must still count this restart
        if (!prefs.edit().putString(PREF_RESTART_TIMES, RestartBudget.encode(recent + now)).commit()) {
            Timber.w("Could not save the restart time")
        }
        kiosk.recordRestart()
        Timber.w("Restarted by Android after the app stopped; bringing the kiosk outputs back")

        val autoStart = config.autoStart
        if (autoStart.startRtsp || autoStart.startRecording) {
            launchAutoStart(rtsp = autoStart.startRtsp, record = autoStart.startRecording)
        }
        startThermalMonitoring()
        if (kiosk.isDeviceOwner()) reopenActivity()
    }

    /** Start the unattended outputs ([autoStartOutputs]) once the encoder service is bound, off the main thread. */
    private fun launchAutoStart(rtsp: Boolean, record: Boolean) {
        whenEncoderBound { serviceScope.launch(Dispatchers.IO) { autoStartOutputs(rtsp, record) } }
    }

    /**
     * Start RTSP when [rtsp] and a recording when [record], each on its own
     * (one failing never keeps the other off), with the saved stream defaults.
     * A start that fails with them is tried once more at 1080p30 on port 8554,
     * so a bad saved value never leaves the appliance dark; the saved defaults
     * stay as they were.
     */
    private fun autoStartOutputs(rtsp: Boolean, record: Boolean) {
        val defaults = streamDefaults()
        val config = defaults.toEncoderConfig()
        val segment = defaults.segmentDuration()
        var fellBack = false
        if (rtsp && !startRtspStreaming(config, defaults.rtspPort)) {
            Timber.w("Auto-start: RTSP did not start with the saved defaults; trying 1080p30 on port $DEFAULT_RTSP_PORT")
            fellBack = true
            if (!startRtspStreaming(EncoderConfig.PRESET_1080P, DEFAULT_RTSP_PORT)) {
                Timber.e("Auto-start: RTSP could not start")
            }
        }
        if (record && !startRecording(config, segment)) {
            Timber.w("Auto-start: recording did not start with the saved defaults; trying 1080p30")
            fellBack = true
            if (!startRecording(EncoderConfig.PRESET_1080P, segment)) {
                Timber.e("Auto-start: recording could not start")
            }
        }
        // A fallback start is no new choice: keep the saved defaults for next time
        if (fellBack) saveStreamDefaults(defaults)
    }

    /** Start thermal monitoring in the foreground, as the app and boot do; it may be refused in the background. */
    private fun startThermalMonitoring() {
        try {
            ContextCompat.startForegroundService(this, ThermalService.createStartIntent(this))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException: not allowed from the background
            Timber.w(e, "Thermal monitoring could not be started")
        } catch (e: SecurityException) {
            Timber.w(e, "Thermal monitoring could not be started")
        }
    }

    /** Open the app's screen again after a restart; as device owner it may start from the background and pins again. */
    private fun reopenActivity() {
        try {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            Timber.w(e, "Could not reopen the app's screen")
        }
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
        val started = if (withMicrophone != camera && tryStartForeground(notification, withMicrophone)) {
            foregroundHasMicrophone = true
            true
        } else {
            Timber.i("Running as a camera service without the microphone type")
            tryStartForeground(notification, camera)
        }
        if (started) {
            startedInForeground = true
        } else {
            Timber.w("Not running in the foreground: Android refused it (started from the background?)")
        }
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
        releaseStreamLocks()
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
                    val lensChanged = it.lensType != lastLensType
                    lastLensType = it.lensType
                    lensController.setCurrentLens(it)
                    initializeControllersForCamera(it.cameraId)
                    syncConfigToLens(lensChanged)
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
                val lensChanged = lens.lensType != lastLensType
                lastLensType = lens.lensType
                lensController.setCurrentLens(lens)
                initializeControllersForCamera(lens.cameraId)
                syncConfigToLens(lensChanged)
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

    /**
     * Bring the capture settings in line with the lens just opened, before its
     * session starts. A different lens starts at 1x zoom and 0 EV, in its own
     * zoom range and exposure steps; the same lens reopened (after the camera
     * was lost) keeps its zoom and exposure, clamped to the lens. The focus
     * mode falls back to one the lens supports, and tap regions, which were in
     * the old lens's coordinates, are dropped. Zoom goes through the zoom
     * controller, so the reported zoom and the applied one agree.
     */
    private fun syncConfigToLens(lensChanged: Boolean) {
        zoomController.setZoom(if (lensChanged) 1f else currentConfig.zoomRatio)
        exposureController.setExposureCompensation(if (lensChanged) 0 else currentConfig.exposureCompensation)
        val supported = focusController.getSupportedFocusModes()
        val focus = when {
            supported.isEmpty() || currentConfig.focusMode in supported -> currentConfig.focusMode
            else -> FOCUS_FALLBACKS.firstOrNull { it in supported } ?: supported.first()
        }
        if (focus in supported) focusController.setFocusMode(focus)
        currentConfig = currentConfig.copy(
            exposureCompensation = exposureController.exposureCompensation.value,
            focusMode = focus,
            afRegions = null,
            aeRegions = null
        )
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
     * Switch to a different camera lens: that type, or the nearest of its family
     * when the phone lacks it (a WIDE request takes the ultra-wide on a phone
     * without a plain wide lens). Returns false when the phone has no such lens.
     */
    fun switchLens(lensType: LensType): Boolean {
        val available = lensDaemonCameraManager.availableLenses
        val type = LensSelection.pick(lensType, available.map { it.lensType })
        val lens = type?.let { picked -> available.find { it.lensType == picked } }
        if (lens == null) {
            Timber.w("No $lensType lens on this phone")
            return false
        }
        Timber.i("Switching lens to: ${lens.lensType}")
        serviceScope.launch { openCameraAndStartPreview(lens) }
        return true
    }

    /**
     * Switch to a lens by name: "wide", "main", "tele" or a [LensType] name.
     * Returns false when the phone has no such lens or the name is unknown.
     */
    fun switchLens(lensName: String): Boolean {
        val lens = findLens(lensName) ?: run {
            Timber.w("No lens matching \"$lensName\" on this phone")
            return false
        }
        return switchLens(lens.lensType)
    }

    /** The lens a name asks for ("wide", "main", "tele" or a [LensType] name), or null when the phone has none. */
    fun findLens(lensName: String): CameraLens? {
        val available = lensDaemonCameraManager.availableLenses
        val type = LensSelection.pick(lensName, available.map { it.lensType }) ?: return null
        return available.find { it.lensType == type }
    }

    // ==================== Zoom Control (Phase 3) ====================

    /**
     * Set the zoom level immediately.
     * @return the zoom applied: [zoomLevel] clamped to the lens's zoom range
     */
    fun setZoom(zoomLevel: Float): Float = zoomController.setZoom(zoomLevel)

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
     * Trigger tap-to-focus at normalized coordinates: the focus mode and AF
     * regions the focus controller works out go into the capture request.
     * Does nothing when the lens offers no tap-to-focus.
     * @param x Normalized X (0.0 to 1.0, left to right)
     * @param y Normalized Y (0.0 to 1.0, top to bottom)
     * @param previewSize Size of the preview view
     */
    fun triggerTapToFocus(x: Float, y: Float, previewSize: Size) {
        val result = focusController.triggerTapToFocus(x, y, previewSize) ?: return
        currentConfig = currentConfig.copy(focusMode = result.mode, afRegions = result.regions?.toList())
        lensDaemonCameraManager.updateConfig(currentConfig)
        Timber.i("Tap-to-focus triggered at ($x, $y)")
    }

    /**
     * Lock focus at current position.
     */
    fun lockFocus() {
        focusController.lockFocus()
    }

    /**
     * Unlock focus and return to continuous mode, dropping any tap region.
     */
    fun unlockFocus() {
        val result = focusController.unlockFocus()
        currentConfig = currentConfig.copy(focusMode = result.mode, afRegions = null)
        lensDaemonCameraManager.updateConfig(currentConfig)
    }

    /**
     * Set focus mode, if the lens supports it; any tap region is dropped.
     */
    fun setFocusMode(mode: FocusMode) {
        if (focusController.setFocusMode(mode)) {
            currentConfig = currentConfig.copy(focusMode = mode, afRegions = null)
            lensDaemonCameraManager.updateConfig(currentConfig)
        }
    }

    /**
     * Set manual focus distance (0.0 = infinity, 1.0 = closest), if the lens
     * supports manual focus; any tap region is dropped.
     */
    fun setManualFocusDistance(distance: Float) {
        if (focusController.setManualFocusDistance(distance)) {
            currentConfig = currentConfig.copy(focusMode = FocusMode.MANUAL, afRegions = null)
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
     * Set exposure compensation, in the lens's steps.
     * @return the value applied and reported: [value] clamped to the lens's range
     */
    fun setExposureCompensation(value: Int): Int {
        if (exposureController.setExposureCompensation(value)) {
            currentConfig = currentConfig.copy(exposureCompensation = exposureController.exposureCompensation.value)
            lensDaemonCameraManager.updateConfig(currentConfig)
        }
        return currentConfig.exposureCompensation
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
     * Trigger spot metering at normalized coordinates: the AE region the
     * exposure controller works out goes into the capture request.
     */
    fun triggerSpotMetering(x: Float, y: Float) {
        val regions = exposureController.triggerSpotMetering(x, y) ?: return
        currentConfig = currentConfig.copy(aeRegions = regions.toList())
        lensDaemonCameraManager.updateConfig(currentConfig)
        Timber.i("Spot metering triggered at ($x, $y)")
    }

    /**
     * Reset metering to the camera's own (center-weighted), dropping the spot region.
     */
    fun resetMetering() {
        exposureController.resetMetering()
        currentConfig = currentConfig.copy(aeRegions = null)
        lensDaemonCameraManager.updateConfig(currentConfig)
    }

    /**
     * Get exposure compensation range.
     */
    fun getExposureCompensationRange(): android.util.Range<Int> {
        return exposureController.getExposureCompensationRange()
    }

    /** Size of one exposure compensation step, in EV (often 1/6 or 1/3). */
    fun getExposureCompensationStep(): Float = exposureController.getExposureCompensationStep()

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
     * Start the initialized encoder and mark streaming active: the CPU and
     * Wi-Fi are kept awake from here on, and an active thermal bitrate cut is
     * put on the new encoder, which starts at the configured bitrate.
     * @return false if the encoder refused to start
     */
    private fun beginEncoding(): Boolean {
        if (encoderService?.startEncoding() != true) {
            Timber.e("Encoder failed to start")
            return false
        }
        isStreamingActive = true
        acquireStreamLocks()
        startAudioIfWanted()
        updateNotification()
        if (thermalBitrateCut > 0) applyThermalBitrate()
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
     * camera is opened if needed and a fresh encoder is started with [config],
     * within the thermal limits in effect, and [config] becomes the requested
     * configuration.
     *
     * @return true if the encoder is running
     */
    @Synchronized
    private fun ensureEncoding(config: EncoderConfig): Boolean {
        // Opens the camera only if it is not running: never opened, or closed
        // or failed since. A running stream whose camera went away recovers.
        startPreview(lastLensType)

        val target = thermallyLimited(config)
        if (isStreamingActive) {
            val running = getEncoderConfig()
            if (running != null && running != target) {
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
            getEncoderConfig() == target
        if (!fresh && !initializeEncoder(target)) {
            Timber.e("Failed to initialize encoder")
            return false
        }
        if (!beginEncoding()) return false
        requestedEncoderConfig = config
        return true
    }

    /**
     * Start encoding with [config] (by default the saved stream defaults) and
     * keep encoding until [stopStreaming], whether or not an output is attached.
     *
     * If the encoder already runs with other settings it is restarted with
     * these. Attached RTSP and MPEG-TS viewers stay connected and pick the new
     * stream up at its first keyframe; a recording is split into a new
     * segment, since one MP4 track cannot change format. Under the thermal
     * governor's CRITICAL limits the encoder runs limited, and returns to
     * [config] when they clear.
     *
     * @return true if the encoder is running with [config]
     */
    @Synchronized
    fun startStreaming(config: EncoderConfig = streamDefaults().toEncoderConfig()): Boolean {
        cancelThermalPause()
        encodingRequested = true
        val target = thermallyLimited(config)
        val running = getEncoderConfig()
        val started = if (isStreamingActive && running != null && running != target) {
            requestedEncoderConfig = config
            restartEncoder(target)
        } else {
            ensureEncoding(config).also { ok -> if (ok) requestedEncoderConfig = config }
        }
        if (!started) {
            encodingRequested = false
            return false
        }
        rememberStreamDefaults()
        Timber.i("Streaming started: ${target.width}x${target.height} ${target.frameRate}fps")
        return true
    }

    /**
     * Replace the running encoder with one using [config], keeping the outputs:
     * RTSP and MPEG-TS viewers stay connected, and a recording (also one an
     * earlier failed rebuild cut off) continues in a new file, still paused if
     * it was paused.
     *
     * When the new encoder does not start, every output is stopped, so the
     * status is honest. With [keepOutputsOnFailure] (a rebuild after a runtime
     * failure, see [scheduleEncoderRecovery]) they are kept for the next
     * attempt instead, still counted as streaming with a broken encoder.
     */
    private fun restartEncoder(config: EncoderConfig, keepOutputsOnFailure: Boolean = false): Boolean {
        val previous = getEncoderConfig()
        Timber.i(
            "Restarting encoder: ${previous?.width}x${previous?.height} ${previous?.frameRate}fps -> " +
                "${config.width}x${config.height} ${config.frameRate}fps"
        )
        val wasRecording = isRecordingActive() || recordingInterrupted
        val wasPaused = recordingToContinuePaused()
        if (isRecordingActive()) {
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
            if (keepOutputsOnFailure) keepOutputsForNextRebuild(wasRecording, wasPaused) else stopOutputsAfterFailedRestart()
            return false
        }

        recordingInterrupted = false
        if (wasRecording && !startRecording()) {
            Timber.e("Recording could not continue after the encoder restart")
        } else if (wasRecording && wasPaused) {
            // New settings, thermal limits or a rebuild are no reason to resume a paused recording
            pauseRecording()
        }
        return true
    }

    /**
     * Whether the recording a restart continues is paused: the open recording's
     * own state, or else that of the one a failed rebuild cut off.
     */
    private fun recordingToContinuePaused(): Boolean =
        if (isRecordingActive()) recordingCoordinator.isPaused() else recordingInterrupted && interruptedRecordingPaused

    /** A restart failed: stop every output and release what is left of the encoder. */
    private fun stopOutputsAfterFailedRestart() {
        Timber.e("Encoder restart failed; stopping outputs")
        stopAllOutputs()
        // stopAllOutputs() found the encoder already marked stopped, so
        // detach and release whatever is left of it here
        releaseEncoderAfterDetach(encoderSurface)
        stopAudio()
    }

    /**
     * A rebuild after a runtime failure did not start: keep the outputs, and
     * a recording to continue ([wasPaused]: paused), for the next attempt. The
     * service stays streaming with a broken encoder (the wake locks stay
     * held); the dead encoder surface is taken off the camera.
     */
    private fun keepOutputsForNextRebuild(wasRecording: Boolean, wasPaused: Boolean) {
        Timber.e("Encoder rebuild failed; the outputs wait for the next attempt")
        isStreamingActive = true
        recordingInterrupted = wasRecording
        interruptedRecordingPaused = wasPaused
        val dead = encoderSurface
        encoderSurface = null
        // On the dispatcher of initializeEncoder's attach, so it runs after that
        serviceScope.launch { lensDaemonCameraManager.removeEncoderSurface(dead) }
    }

    /**
     * Stop streaming: every output (RTSP server, MPEG-TS publisher, recording)
     * and then the encoder, so nothing is left serving viewers a frozen stream.
     * A user's stop: a thermal pause no longer restarts anything afterwards.
     */
    @Synchronized
    fun stopStreaming() {
        cancelThermalPause()
        stopAllOutputs()
    }

    /**
     * Stop every output and then the encoder, and end an encoder rebuild in
     * progress. Leaves a thermal pause's record alone (see [pauseForThermal]).
     */
    private fun stopAllOutputs() {
        encoderRecoveryJob?.cancel()
        encoderRecoveryJob = null
        encoderRecoveryStep = 0
        recordingInterrupted = false
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
     * [startStreaming], an output other than [stopping], or a recording a
     * failed encoder rebuild cut off.
     */
    @Synchronized
    private fun stopEncoderIfUnused(stopping: Output) {
        val stillNeeded = encodingRequested || recordingInterrupted ||
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

    /** Stop the encoder: it is no longer requested, and the CPU and Wi-Fi may sleep again. */
    private fun stopEncoder() {
        requestedEncoderConfig = null
        releaseStreamLocks()
        if (!isStreamingActive) return

        isStreamingActive = false
        stopAudio()
        updateNotification()
        releaseEncoderAfterDetach(encoderSurface)
    }

    // ==================== Thermal throttling ====================

    /**
     * Apply the thermal governor's CRITICAL encoder limits: [reduceResolution]
     * takes the size one step down (4K -> 1080p -> 720p) and [reduceFramerate]
     * caps the frame rate at 24 (see [ThermalEncoderLimits]). A running
     * encoder is restarted with the limited settings, keeping the outputs (a
     * recording continues in a new file), and returns to the requested ones
     * once both limits clear. Encoders started meanwhile start limited.
     *
     * Thread-safe; may block while the encoder restarts, so call it off the
     * main thread, in the order the governor reports changes.
     */
    @Synchronized
    fun setThermalEncoderLimits(reduceResolution: Boolean, reduceFramerate: Boolean) {
        thermalReduceResolution = reduceResolution
        thermalReduceFramerate = reduceFramerate
        if (!isStreamingActive) return
        val target = thermallyLimited(requestedEncoderConfig ?: return)
        if (target == getEncoderConfig()) return
        Timber.i("Thermal limits (lower resolution: $reduceResolution, 24 fps: $reduceFramerate): restarting the encoder")
        restartEncoder(target)
    }

    /**
     * Cut the bitrate by [percent] (0-90) of the configured bitrate while the
     * phone is hot: now, and on every encoder started, restarted or rebuilt
     * later. 0 restores the configured bitrate. The cut never takes it below
     * 500 kbps. Thread-safe; call it off the main thread.
     */
    @Synchronized
    fun setThermalBitrateReduction(percent: Int) {
        thermalBitrateCut = percent.coerceIn(0, MAX_THERMAL_BITRATE_CUT)
        applyThermalBitrate()
    }

    /** Run the encoder at its configured bitrate less the thermal cut; never above the configured bitrate. */
    private fun applyThermalBitrate() {
        if (!isStreamingActive) return
        val configured = getEncoderConfig()?.bitrateBps ?: return
        val cut = thermalBitrateCut
        val floor = minOf(THERMAL_MIN_BITRATE_BPS, configured).toLong()
        val target = if (cut == 0) configured else (configured.toLong() * (100 - cut) / 100).coerceAtLeast(floor).toInt()
        encoderService?.updateBitrate(target)
    }

    /** [config] within the thermal limits in effect; [config] itself when they change nothing. */
    private fun thermallyLimited(config: EncoderConfig): EncoderConfig {
        val (width, height) = ThermalEncoderLimits.limitedSize(config.width, config.height, thermalReduceResolution)
        val frameRate = ThermalEncoderLimits.limitedFrameRate(config.frameRate, thermalReduceFramerate)
        if (width == config.width && height == config.height && frameRate == config.frameRate) return config
        return config.copy(resolution = Size(width, height), frameRate = frameRate)
    }

    /**
     * The phone is too hot (thermal EMERGENCY): stop every output and the
     * encoder, remembering which ran and how (the requested encoder settings,
     * Start Streaming, the RTSP port, the MPEG-TS settings, the recording's
     * segment length and whether it was paused) for [resumeAfterThermal].
     * Does nothing when nothing runs or a pause is already in effect. The CPU
     * is kept awake during the pause: the thermal monitor that calls for the
     * resume only runs while it is, and with the screen off the phone would
     * otherwise go to sleep and never bring the outputs back.
     *
     * Thread-safe; blocks while the outputs stop, so call it off the main thread.
     */
    @Synchronized
    fun pauseForThermal() {
        if (thermalPaused != null) return
        val encoder = requestedEncoderConfig ?: getEncoderConfig() ?: return
        val paused = PausedOutputs(
            encoder = encoder,
            encodingRequested = encodingRequested,
            rtspPort = rtspCoordinator.port.takeIf { rtspCoordinator.isRunning() },
            mpegTs = mpegTsCoordinator.getConfig(),
            recording = recordingCoordinator.segmentDuration.takeIf { isRecordingActive() || recordingInterrupted },
            recordingPaused = recordingToContinuePaused()
        )
        if (!paused.restartsAnything()) return
        Timber.w("Phone too hot: stopping every output until it cools down")
        stopAllOutputs()
        thermalPaused = paused
        if (!streamWakeLock.isHeld) streamWakeLock.acquire()
    }

    /**
     * The phone has cooled down: restart exactly the outputs [pauseForThermal]
     * stopped, sharing one encoder, within the thermal limits and bitrate cut
     * then in effect (a recording continues in a new file). MPEG-TS receivers
     * in listener mode have to reconnect. Does nothing without a pause, or
     * after a user's start or stop cancelled it.
     *
     * Thread-safe; blocks while the encoder starts, so call it off the main thread.
     */
    @Synchronized
    fun resumeAfterThermal() {
        val paused = thermalPaused ?: return
        thermalPaused = null
        Timber.i("Phone cooled down: restarting the outputs the thermal pause stopped")
        val encoder = paused.encoder
        if (paused.encodingRequested && !startStreaming(encoder)) {
            Timber.e("Thermal resume: the encoder did not start")
        }
        paused.rtspPort?.let { port ->
            if (!startRtspStreaming(encoder, port)) Timber.e("Thermal resume: RTSP did not start on port $port")
        }
        paused.mpegTs?.let { mpegTs ->
            if (!startMpegTsStreaming(encoder, mpegTs)) Timber.e("Thermal resume: MPEG-TS did not start")
        }
        paused.recording?.let { segment ->
            if (!startRecording(encoder, segment)) {
                Timber.e("Thermal resume: the recording did not start")
            } else if (paused.recordingPaused) {
                pauseRecording()
            }
        }
        // Nothing came back: the CPU, kept awake for the resume, may sleep again
        if (!isStreamingActive) releaseStreamLocks()
    }

    /** True while a thermal pause holds the outputs stopped, to restart them once the phone cools down. */
    fun isThermalPaused(): Boolean = thermalPaused != null

    /**
     * A start or stop by the user ends a thermal pause: nothing is restarted
     * automatically any more, and the CPU, kept awake for the resume, may
     * sleep again unless the encoder runs.
     */
    private fun cancelThermalPause() {
        if (thermalPaused == null) return
        thermalPaused = null
        if (!isStreamingActive) releaseStreamLocks()
    }

    // ==================== Encoder recovery ====================

    /**
     * The encoder failed while outputs ran (a non-transient codec error):
     * rebuild it with the same settings after 1, 2, 5, 10 and 30 s, keeping the
     * outputs, until one rebuild runs; if none does, stop the outputs so the
     * status is honest. One recovery at a time. A rebuilt encoder that fails
     * again within a minute continues with the next wait instead of starting
     * over, so a codec that keeps failing ends in a stop rather than a loop.
     * Called on the main thread by the encoder state collector.
     */
    private fun scheduleEncoderRecovery() {
        if (encoderRecoveryJob?.isActive == true) return
        if (SystemClock.elapsedRealtime() - encoderRebuiltAtMs > ENCODER_HEALTHY_MS) encoderRecoveryStep = 0
        encoderRecoveryJob = serviceScope.launch(Dispatchers.Default) {
            while (encoderRecoveryStep < ENCODER_RECOVERY_DELAYS_MS.size) {
                delay(ENCODER_RECOVERY_DELAYS_MS[encoderRecoveryStep++])
                if (recoverEncoder()) return@launch
            }
            giveUpEncoderRecovery()
        }
    }

    /**
     * One rebuild of a broken encoder, with the requested settings within the
     * thermal limits. True when nothing is left to do: the encoder runs (again)
     * or is no longer needed.
     */
    @Synchronized
    private fun recoverEncoder(): Boolean {
        if (!encoderBroken()) return true
        val config = requestedEncoderConfig?.let { thermallyLimited(it) } ?: getEncoderConfig() ?: return false
        Timber.w("Encoder failed while running; rebuilding it")
        val rebuilt = restartEncoder(config, keepOutputsOnFailure = true)
        if (rebuilt) encoderRebuiltAtMs = SystemClock.elapsedRealtime()
        return rebuilt
    }

    /** Every rebuild failed: stop the outputs, so nothing reports a stream that is not there. */
    @Synchronized
    private fun giveUpEncoderRecovery() {
        if (!encoderBroken()) return
        Timber.e("The encoder could not be rebuilt; stopping every output")
        stopAllOutputs()
    }

    /** Outputs count on the encoder, but it is not encoding: it failed, or a rebuild did not start. */
    private fun encoderBroken(): Boolean =
        isStreamingActive && encoderService?.encoderState?.value != EncoderState.ENCODING

    // ==================== Stream defaults ====================

    /**
     * The phone's stream defaults: what the phone's Start Stream, kiosk
     * auto-start and the restart after Android stopped the app use, and what
     * the start methods fall back to without settings. Built-in 1080p30 H.264
     * at 4 Mbps, RTSP port 8554 and 5-minute files until others are saved.
     */
    fun streamDefaults(): StreamDefaults = StreamDefaults.fromJson(streamPrefs().getString(PREF_STREAM_DEFAULTS, null))

    /**
     * Save [defaults] as the phone's stream defaults.
     * @return false, saving nothing, when [StreamDefaults.validationError] finds a problem
     */
    fun saveStreamDefaults(defaults: StreamDefaults): Boolean {
        val error = defaults.validationError()
        if (error != null) {
            Timber.w("Stream defaults not saved: $error")
            return false
        }
        streamPrefs().edit().putString(PREF_STREAM_DEFAULTS, defaults.toJson().toString()).apply()
        return true
    }

    /**
     * Make the start that just succeeded the stream defaults: the encoder
     * settings asked for (never the thermally limited ones the encoder may run
     * at), with [rtspPort] and [segment] when given and the saved ones otherwise.
     */
    private fun rememberStreamDefaults(rtspPort: Int? = null, segment: SegmentDuration? = null) {
        val requested = requestedEncoderConfig ?: return
        val saved = streamDefaults()
        val updated = StreamDefaults.from(requested, rtspPort ?: saved.rtspPort, segment?.minutes ?: saved.segmentMinutes)
        if (updated != saved) saveStreamDefaults(updated)
    }

    private fun streamPrefs(): SharedPreferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

    // ==================== Wake and Wi-Fi locks ====================

    /** Keep the CPU and Wi-Fi awake while the encoder runs, with the screen off or the app in the background. */
    private fun acquireStreamLocks() {
        if (!streamWakeLock.isHeld) streamWakeLock.acquire()
        streamWifiLock?.takeIf { !it.isHeld }?.acquire()
    }

    private fun releaseStreamLocks() {
        if (streamWakeLock.isHeld) streamWakeLock.release()
        streamWifiLock?.takeIf { it.isHeld }?.release()
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
        streamPrefs().edit().putBoolean(PREF_AUDIO_ENABLED, enabled).apply()
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
        requestedEncoderConfig = null
        releaseStreamLocks()
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
     * Change the encoding bitrate for now (adaptive bitrate). The configured
     * bitrate stays; see [setEncoderBitrate].
     */
    fun updateEncoderBitrate(newBitrateBps: Int) {
        encoderService?.updateBitrate(newBitrateBps)
    }

    /**
     * Make [bps] the running encoder's configured bitrate (PUT /api/config):
     * clamped to what the codec supports, applied at once and reported by
     * [getEncoderConfig] from then on. It is also the requested bitrate, so a
     * later thermal restart or rebuild keeps it, and an active thermal cut
     * applies on top of it.
     *
     * @return the bitrate applied, or null when no encoder is running
     */
    @Synchronized
    fun setEncoderBitrate(bps: Int): Int? {
        val applied = encoderService?.setConfiguredBitrate(bps) ?: return null
        requestedEncoderConfig = requestedEncoderConfig?.copy(bitrateBps = applied)
        applyThermalBitrate()
        return applied
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
     * (another output started it), otherwise starts one with [config]. Without
     * arguments (the phone's Start Stream button) the saved stream defaults are
     * used; a successful start makes its settings the new defaults.
     */
    @Synchronized
    fun startRtspStreaming(
        config: EncoderConfig = streamDefaults().toEncoderConfig(),
        rtspPort: Int = streamDefaults().rtspPort
    ): Boolean {
        cancelThermalPause()
        if (!ensureEncoding(config)) {
            return false
        }

        if (!startRtspServer(rtspPort)) {
            Timber.e("Failed to start RTSP server")
            stopEncoderIfUnused(Output.RTSP)
            return false
        }

        updateNotification()
        // The port the server listens on: a server already running keeps its own
        rememberStreamDefaults(rtspPort = rtspCoordinator.port)
        Timber.i("RTSP streaming started: ${getRtspUrl()}")
        return true
    }

    /**
     * Stop serving RTSP. The encoder keeps running if another output (or an
     * explicit Start Streaming) still needs it.
     */
    @Synchronized
    fun stopRtspStreaming() {
        cancelThermalPause()
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

    /** The running MPEG-TS publisher's settings (mode, port, target), or null when it is stopped. */
    fun getMpegTsConfig(): MpegTsUdpConfig? = mpegTsCoordinator.getConfig()

    /**
     * Publish the camera as MPEG-TS over UDP. Uses the running encoder if
     * there is one, otherwise starts one with [encoderConfig] (by default the
     * saved stream defaults); a successful start makes the encoder settings
     * the new defaults.
     */
    @Synchronized
    fun startMpegTsStreaming(
        encoderConfig: EncoderConfig = streamDefaults().toEncoderConfig(),
        mpegtsConfig: MpegTsUdpConfig = MpegTsUdpConfig()
    ): Boolean {
        cancelThermalPause()
        if (!ensureEncoding(encoderConfig)) {
            return false
        }

        if (!startMpegTsPublisher(mpegtsConfig)) {
            Timber.e("Failed to start MPEG-TS/UDP publisher")
            stopEncoderIfUnused(Output.MPEGTS)
            return false
        }

        updateNotification()
        rememberStreamDefaults()
        Timber.i("MPEG-TS/UDP streaming started on port ${mpegtsConfig.port}")
        return true
    }

    /**
     * Stop the MPEG-TS/UDP publisher. The encoder keeps running if another
     * output (or an explicit Start Streaming) still needs it.
     */
    @Synchronized
    fun stopMpegTsStreaming() {
        cancelThermalPause()
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

    /**
     * Record from the running encoder. A recording already open (running or
     * paused) is left as it is and counts as started, so a second start never
     * detaches its listeners.
     */
    @Synchronized
    fun startRecording(): Boolean {
        if (isRecordingActive()) {
            Timber.w("Already recording; the open recording is left as it is")
            return true
        }
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
     * alongside a stream), otherwise starts one with [config]. A recording
     * already open (running or paused) is left as it is, with its own segment
     * length, and counts as started: the encoder is neither started nor
     * stopped for it. A new recording's settings and segment length become the
     * stream defaults.
     */
    @Synchronized
    fun startRecording(
        config: EncoderConfig,
        segmentDuration: SegmentDuration = SegmentDuration.FIVE_MINUTES
    ): Boolean {
        if (isRecordingActive()) {
            Timber.w("Already recording; the open recording is left as it is")
            return true
        }
        cancelThermalPause()
        if (!ensureEncoding(config)) {
            return false
        }

        prepareRecorder(segmentDuration)
        val started = startRecording()
        if (started) rememberStreamDefaults(segment = segmentDuration) else stopEncoderIfUnused(Output.RECORDING)
        return started
    }

    /**
     * Stop recording. The encoder keeps running if a stream (or an explicit
     * Start Streaming) still needs it.
     */
    @Synchronized
    fun stopRecording(): List<String> {
        cancelThermalPause()
        recordingInterrupted = false
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

    /**
     * Apply the retention policy to the recordings now (never to the file
     * being recorded) and return what it removed. Runs on the calling thread
     * and does file IO: call it off the main thread.
     */
    fun enforceRetention(): RetentionResult = recordingCoordinator.enforceRetention()

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

        serviceScope.launch(Dispatchers.IO) {
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
     * Focus for faces: continuous autofocus, when the lens offers it, without
     * a tap region. The camera has no face priority yet.
     */
    fun enableFaceDetectionFocus() {
        setFocusMode(FocusMode.CONTINUOUS_VIDEO)
        Timber.d("Face focus: continuous autofocus")
    }

    /**
     * Set focus distance directly, if the lens supports manual focus (as
     * [setManualFocusDistance]); any tap region is dropped.
     * @param distance 0.0 = infinity, higher values = closer focus
     */
    fun setFocusDistance(distance: Float) {
        setManualFocusDistance(distance)
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

