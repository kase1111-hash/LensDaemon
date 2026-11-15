package com.lensdaemon

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Size
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.lensdaemon.camera.CameraService
import com.lensdaemon.camera.CameraState
import com.lensdaemon.camera.FocusState
import com.lensdaemon.camera.LensType
import com.lensdaemon.databinding.ActivityMainBinding
import com.lensdaemon.kiosk.KioskActivityController
import com.lensdaemon.thermal.ThermalService
import com.lensdaemon.web.WebServerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.Locale

/**
 * Main activity for LensDaemon.
 * Displays camera preview and provides basic controls for streaming.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val STREAM_STATUS_REFRESH_MS = 1000L
    }

    private lateinit var binding: ActivityMainBinding

    /** Dashboard address and temperatures over the preview. */
    private lateinit var overlays: PhoneOverlays

    /** Pins the app to the screen while kiosk mode is on; Vol Up + Vol Down held leaves it. */
    private lateinit var kiosk: KioskActivityController

    /** Refreshes the stream overlay while the activity is visible. */
    private var streamStatusJob: Job? = null

    /** The outputs the status line last showed, so it is redrawn only when they change. */
    private var shownState: String? = null

    // Camera service connection
    private var cameraService: CameraService? = null
    private var serviceBound = false

    // Gesture detectors
    private lateinit var scaleGestureDetector: ScaleGestureDetector

    // Focus indicator handler
    private val focusHandler = Handler(Looper.getMainLooper())
    private val hideFocusIndicatorRunnable = Runnable { hideFocusIndicator() }

    /**
     * Only permissions the app actually uses and the platform knows about:
     * requesting the notification permission on Android 12 and below is
     * refused outright. Only the camera is essential; without the microphone
     * the streams carry video only.
     */
    private val requiredPermissions: Array<String> = buildList {
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val denied = permissions.filterValues { !it }.keys
        val cameraGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (cameraGranted) {
            if (denied.isNotEmpty()) {
                Timber.w("Optional permissions denied: $denied")
            }
            startCameraService()
        } else {
            Timber.w("Camera permission denied")
            showPermissionDeniedMessage()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Timber.i("CameraService connected")
            val binder = service as CameraService.LocalBinder
            cameraService = binder.getService()
            serviceBound = true
            onCameraServiceConnected()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Timber.i("CameraService disconnected")
            cameraService = null
            serviceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI()
        setupUI()
        setupGestureDetectors()
        overlays = PhoneOverlays(this, binding)
        kiosk = KioskActivityController(this, (application as LensDaemonApp).kioskManager)
        kiosk.attach()

        if (hasAllPermissions()) {
            startCameraService()
        } else {
            requestPermissions()
        }
    }

    override fun onStart() {
        super.onStart()
        startStreamStatusUpdates()
        overlays.start()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    override fun onStop() {
        streamStatusJob?.cancel()
        streamStatusJob = null
        overlays.stop()
        super.onStop()
    }

    /** While kiosk mode pins the app, the volume keys are the way out (held together). */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        kiosk.onKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onDestroy() {
        super.onDestroy()
        kiosk.release()
        focusHandler.removeCallbacks(hideFocusIndicatorRunnable)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
        // Keep-screen-on is KioskActivityController's: on, except in a kiosk
        // that lets the phone's screen timeout turn the display off
    }

    private fun setupUI() {
        binding.apply {
            btnStartStream.setOnClickListener { onStartStreamClicked() }
            btnStopStream.setOnClickListener { onStopStreamClicked() }

            // Lens selection buttons
            btnLensWide.setOnClickListener { onLensSelected(LensType.WIDE) }
            btnLensMain.setOnClickListener { onLensSelected(LensType.MAIN) }
            btnLensTele.setOnClickListener { onLensSelected(LensType.TELEPHOTO) }

            // Initially show start button, hide stop button
            btnStartStream.visibility = View.VISIBLE
            btnStopStream.visibility = View.GONE

            // Select main lens by default
            btnLensMain.isSelected = true

            // Hide focus indicator initially
            focusIndicator.visibility = View.GONE
        }

        updateStatus(StreamingState.IDLE)
    }

    private fun setupGestureDetectors() {
        // Pinch-to-zoom gesture detector. Returning true from onScale makes
        // scaleFactor the change since the last call, which zooms incrementally.
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                cameraService?.onPinchStart()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                val newZoom = cameraService?.onPinchScale(scaleFactor) ?: 1.0f
                updateZoomDisplay(newZoom)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                cameraService?.onPinchEnd()
                hideZoomDisplayDelayed()
            }
        })

        // Tap-to-focus on a real tap only: GestureDetector drops the tap when a
        // second finger comes down (a pinch) or the finger moves past the touch slop
        val tapDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                handleTapToFocus(e.x, e.y, binding.surfacePreview.width, binding.surfacePreview.height)
                return true
            }
        })

        // Touch listener for tap-to-focus and pinch-to-zoom
        binding.surfacePreview.setOnTouchListener { _, event ->
            scaleGestureDetector.onTouchEvent(event)
            tapDetector.onTouchEvent(event)
            true
        }
    }

    private fun handleTapToFocus(x: Float, y: Float, viewWidth: Int, viewHeight: Int) {
        val service = cameraService ?: return

        if (!service.isTapToFocusSupported()) {
            Timber.d("Tap-to-focus not supported")
            return
        }

        // Convert to normalized coordinates (0.0 to 1.0)
        val normalizedX = x / viewWidth
        val normalizedY = y / viewHeight

        // Trigger tap-to-focus
        service.triggerTapToFocus(normalizedX, normalizedY, Size(viewWidth, viewHeight))

        // Also trigger spot metering at same location
        service.triggerSpotMetering(normalizedX, normalizedY)

        // Show focus indicator
        showFocusIndicator(x, y)
    }

    private fun showFocusIndicator(x: Float, y: Float) {
        binding.focusIndicator.apply {
            // Position the indicator centered on tap location
            val size = resources.getDimensionPixelSize(R.dimen.focus_indicator_size)
            translationX = x - size / 2
            translationY = y - size / 2

            // Reset and animate
            alpha = 1f
            scaleX = 1.5f
            scaleY = 1.5f
            visibility = View.VISIBLE

            animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(200)
                .setListener(null)
                .start()
        }

        // Schedule hiding the indicator
        focusHandler.removeCallbacks(hideFocusIndicatorRunnable)
        focusHandler.postDelayed(hideFocusIndicatorRunnable, 2000)
    }

    private fun hideFocusIndicator() {
        binding.focusIndicator.animate()
            .alpha(0f)
            .setDuration(200)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.focusIndicator.visibility = View.GONE
                }
            })
            .start()
    }

    private fun updateZoomDisplay(zoom: Float) {
        binding.tvZoomLevel.apply {
            text = if (zoom < 2.0f) {
                String.format("%.1fx", zoom)
            } else {
                String.format("%.0fx", zoom)
            }
            visibility = View.VISIBLE
            alpha = 1f
        }
    }

    private fun hideZoomDisplayDelayed() {
        binding.tvZoomLevel.animate()
            .alpha(0f)
            .setStartDelay(1000)
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    binding.tvZoomLevel.visibility = View.GONE
                }
            })
            .start()
    }

    private fun hasAllPermissions(): Boolean {
        return requiredPermissions.all { permission ->
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        Timber.d("Requesting permissions")
        permissionLauncher.launch(requiredPermissions)
    }

    private fun startCameraService() {
        Timber.i("Starting camera service")

        // Start foreground service
        val intent = Intent(this, CameraService::class.java)
        ContextCompat.startForegroundService(this, intent)

        // Bind to service
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        // The dashboard and REST API run in their own foreground service; the
        // documented flow is "open the app, then browse to http://<ip>:8080".
        ContextCompat.startForegroundService(this, WebServerService.createStartIntent(this))

        // Thermal monitoring runs as soon as the service exists (the web server
        // binds it too); starting it here gives it its foreground notification
        ContextCompat.startForegroundService(this, ThermalService.createStartIntent(this))
    }

    private fun onCameraServiceConnected() {
        Timber.i("Camera service ready, initializing camera")

        cameraService?.let { service ->
            // Attach the preview surface
            service.attachPreviewSurface(binding.surfacePreview)

            // Start the camera with the main lens. If it is already running
            // (a stream carried on while the activity was away) it is kept.
            service.startPreview(LensType.MAIN)

            // Update UI based on available lenses
            updateLensButtons(service)

            // Observe camera state
            observeCameraState(service)

            shownState = null
            renderStreamState(service)
            if (!service.isStreaming()) binding.tvStatus.text = "Camera ready"
        }
    }

    private fun updateLensButtons(service: CameraService) {
        val lenses = service.getAvailableLenses()
        Timber.i("Available lenses: ${lenses.map { it.lensType.displayName }}")

        binding.apply {
            // Show/hide lens buttons based on availability
            val hasWide = lenses.any { it.lensType == LensType.WIDE || it.lensType == LensType.ULTRA_WIDE }
            val hasMain = lenses.any { it.lensType == LensType.MAIN }
            val hasTele = lenses.any { it.lensType == LensType.TELEPHOTO || it.lensType == LensType.SUPER_TELEPHOTO }

            btnLensWide.visibility = if (hasWide) View.VISIBLE else View.GONE
            btnLensMain.visibility = if (hasMain) View.VISIBLE else View.GONE
            btnLensTele.visibility = if (hasTele) View.VISIBLE else View.GONE

            // If only one lens, hide the selector
            if (lenses.size <= 1) {
                lensSelector.visibility = View.GONE
            }
        }
    }

    private fun observeCameraState(service: CameraService) {
        // Observe camera state changes
        lifecycleScope.launch {
            service.getCameraState().collectLatest { state ->
                Timber.d("Camera state: $state")
                when (state) {
                    CameraState.PREVIEWING -> {
                        // While outputs run the status line is renderStreamState's
                        // (Streaming or Recording): have it redrawn, e.g. after an error
                        if (service.isStreaming()) shownState = null else binding.tvStatus.text = "Preview active"
                    }
                    CameraState.STREAMING -> {
                        binding.tvStatus.text = getString(R.string.status_streaming)
                    }
                    CameraState.ERROR -> {
                        binding.tvStatus.text = getString(R.string.status_error)
                        updateStatus(StreamingState.ERROR)
                    }
                    else -> {}
                }
            }
        }

        // Observe current lens changes
        lifecycleScope.launch {
            service.getCurrentLens().collectLatest { lens ->
                lens?.let {
                    Timber.d("Current lens: ${it.lensType.displayName}")
                    updateSelectedLensButton(it.lensType)
                }
            }
        }

        // Observe focus state
        lifecycleScope.launch {
            service.focusState.collectLatest { state ->
                updateFocusIndicatorState(state)
            }
        }

        // Observe zoom level
        lifecycleScope.launch {
            service.currentZoom.collectLatest { zoom ->
                // Only update if zoom display is visible (during gesture)
                if (binding.tvZoomLevel.visibility == View.VISIBLE) {
                    updateZoomDisplay(zoom)
                }
            }
        }
    }

    private fun updateFocusIndicatorState(state: FocusState) {
        val color = when (state) {
            FocusState.SCANNING -> R.color.focus_scanning
            FocusState.FOCUSED -> R.color.focus_success
            FocusState.FAILED -> R.color.focus_failed
            FocusState.INACTIVE -> R.color.focus_inactive
        }

        binding.focusIndicator.setColorFilter(ContextCompat.getColor(this, color))
    }

    private fun updateSelectedLensButton(lensType: LensType) {
        binding.apply {
            btnLensWide.isSelected = lensType == LensType.WIDE || lensType == LensType.ULTRA_WIDE
            btnLensMain.isSelected = lensType == LensType.MAIN
            btnLensTele.isSelected = lensType == LensType.TELEPHOTO || lensType == LensType.SUPER_TELEPHOTO
        }
    }

    private fun showPermissionDeniedMessage() {
        Toast.makeText(
            this,
            "Camera permission is required for streaming",
            Toast.LENGTH_LONG
        ).show()
        binding.tvStatus.text = "Permissions required"
    }

    /**
     * Start streaming the way the README describes it: the encoder plus the
     * RTSP server, so the URL shown on screen can be opened in OBS or VLC.
     * It uses the saved stream defaults (size, frame rate, codec, bitrate and
     * port, set from the dashboard), and shares the encoder when a recording
     * or MPEG-TS stream already runs.
     */
    private fun onStartStreamClicked() {
        Timber.i("Start stream clicked")
        val service = cameraService ?: return
        binding.btnStartStream.isEnabled = false
        lifecycleScope.launch {
            // Configuring the encoder blocks briefly; keep it off the UI thread
            val started = withContext(Dispatchers.IO) { service.startRtspStreaming() }
            binding.btnStartStream.isEnabled = true
            if (!started) {
                Toast.makeText(this@MainActivity, "Could not start streaming", Toast.LENGTH_LONG).show()
                updateStatus(StreamingState.ERROR)
            }
            renderStreamState(service)
        }
    }

    private fun onStopStreamClicked() {
        Timber.i("Stop stream clicked")
        val service = cameraService ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { service.stopStreaming() }
            renderStreamState(service)
        }
    }

    /**
     * Keep the stream overlay in step with the service once a second, whether
     * the stream was started here, from the web dashboard or by auto-start.
     */
    private fun startStreamStatusUpdates() {
        streamStatusJob?.cancel()
        streamStatusJob = lifecycleScope.launch {
            while (isActive) {
                cameraService?.let { renderStreamState(it) }
                delay(STREAM_STATUS_REFRESH_MS)
            }
        }
    }

    /**
     * Show what runs, whoever started it (this screen, the dashboard, auto-start).
     * Start Stream is offered whenever RTSP is off: it shares an encoder that a
     * recording or MPEG-TS already runs. Stop ends every output, so it reads
     * "Stop all" when it would end a recording or MPEG-TS too. The status line
     * says Recording while a recording is open (running or paused), and the
     * overlay lists RTSP, MPEG-TS, the recording and the encoder settings.
     */
    private fun renderStreamState(service: CameraService) {
        val streaming = service.isStreaming()
        val rtspOn = service.isRtspServerRunning()
        val recordingLine = if (service.isRecordingPaused()) {
            "Recording paused"
        } else if (service.isRecording()) {
            "Recording"
        } else {
            null
        }
        val mpegTs = service.getMpegTsStats()?.takeIf { service.isMpegTsRunning() }
        val state = if (recordingLine != null) {
            StreamingState.RECORDING
        } else if (streaming) {
            StreamingState.STREAMING
        } else {
            StreamingState.IDLE
        }
        val shown = "$state $rtspOn ${mpegTs != null} $recordingLine"
        if (shown != shownState) {
            shownState = shown
            updateStatus(state)
        }

        val endsMore = listOfNotNull(recordingLine, mpegTs).isNotEmpty()
        binding.btnStartStream.visibility = if (rtspOn) View.GONE else View.VISIBLE
        binding.btnStopStream.visibility = if (streaming) View.VISIBLE else View.GONE
        binding.btnStopStream.setText(if (endsMore) R.string.stop_all else R.string.btn_stop_stream)
        binding.overlayStreamInfo.visibility = if (streaming) View.VISIBLE else View.GONE
        if (!streaming) return

        val rtspLine = service.getRtspUrl()?.takeIf { rtspOn }
            ?.let { "RTSP: $it · ${service.getRtspPlayingCount()} watching" } ?: "RTSP off"
        val mpegTsLine = mpegTs?.let { "MPEG-TS → ${it.remoteAddress.ifEmpty { "no receiver yet (UDP port ${it.port})" }}" }
        binding.tvRtspUrl.text = listOfNotNull(rtspLine, mpegTsLine, recordingLine).joinToString("\n")
        val audio = service.getAudioConfig()
        val audioText = if (audio == null) {
            " · no audio"
        } else {
            " · AAC ${audio.sampleRate / 1000} kHz ${if (audio.channelCount == 2) "stereo" else "mono"}"
        }
        binding.tvResolution.text = service.getEncoderConfig()
            ?.let { "${it.width}x${it.height} @ ${it.frameRate}fps ${it.codec.name}$audioText" }
            .orEmpty()
        binding.tvBitrate.text = service.getEncoderStats()
            ?.let { String.format(Locale.US, "%.1f Mbps · %.0f fps", it.currentBitrateBps / 1_000_000.0, it.currentFps) }
            .orEmpty()
    }

    private fun onLensSelected(lensType: LensType) {
        Timber.i("Lens selected: ${lensType.displayName}")
        cameraService?.switchLens(lensType)
    }

    private fun updateStatus(state: StreamingState) {
        binding.tvStatus.apply {
            text = when (state) {
                StreamingState.IDLE -> getString(R.string.status_idle)
                StreamingState.STREAMING -> getString(R.string.status_streaming)
                StreamingState.RECORDING -> getString(R.string.status_recording)
                StreamingState.ERROR -> getString(R.string.status_error)
            }
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    when (state) {
                        StreamingState.IDLE -> R.color.status_idle
                        StreamingState.STREAMING -> R.color.status_streaming
                        StreamingState.RECORDING -> R.color.status_recording
                        StreamingState.ERROR -> R.color.status_error
                    }
                )
            )
        }

        // Update status indicator
        binding.statusIndicator.setBackgroundColor(
            ContextCompat.getColor(
                this,
                when (state) {
                    StreamingState.IDLE -> R.color.status_idle
                    StreamingState.STREAMING -> R.color.status_streaming
                    StreamingState.RECORDING -> R.color.status_recording
                    StreamingState.ERROR -> R.color.status_error
                }
            )
        )
    }

    enum class StreamingState {
        IDLE,
        STREAMING,
        RECORDING,
        ERROR
    }
}
