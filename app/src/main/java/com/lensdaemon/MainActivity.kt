package com.lensdaemon

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Size
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Main activity for LensDaemon.
 * Displays camera preview and provides basic controls for streaming.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    // Camera service connection
    private var cameraService: CameraService? = null
    private var serviceBound = false

    // Gesture detectors
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    private var isScaling = false

    // Focus indicator handler
    private val focusHandler = Handler(Looper.getMainLooper())
    private val hideFocusIndicatorRunnable = Runnable { hideFocusIndicator() }

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.entries.all { it.value }
        if (allGranted) {
            Timber.i("All permissions granted")
            startCameraService()
        } else {
            Timber.w("Some permissions denied: ${permissions.filter { !it.value }.keys}")
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

        if (hasAllPermissions()) {
            startCameraService()
        } else {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    override fun onDestroy() {
        super.onDestroy()
        focusHandler.removeCallbacks(hideFocusIndicatorRunnable)
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun hideSystemUI() {
        window.insetsController?.let { controller ->
            controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Keep screen on while app is active
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
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
        // Pinch-to-zoom gesture detector
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                isScaling = true
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
                isScaling = false
                cameraService?.onPinchEnd()
                hideZoomDisplayDelayed()
            }
        })

        // Touch listener for tap-to-focus and pinch-to-zoom
        binding.surfacePreview.setOnTouchListener { view, event ->
            scaleGestureDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    if (!isScaling && event.pointerCount == 1) {
                        handleTapToFocus(event.x, event.y, view.width, view.height)
                    }
                }
            }
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
    }

    private fun onCameraServiceConnected() {
        Timber.i("Camera service ready, initializing camera")

        cameraService?.let { service ->
            // Attach the preview surface
            service.attachPreviewSurface(binding.surfacePreview)

            // Start preview with main lens
            service.startPreview(LensType.MAIN)

            // Update UI based on available lenses
            updateLensButtons(service)

            // Observe camera state
            observeCameraState(service)
        }

        updateStatus(StreamingState.IDLE)
        binding.tvStatus.text = "Camera ready"
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
                        binding.tvStatus.text = "Preview active"
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
            "Camera and audio permissions are required for streaming",
            Toast.LENGTH_LONG
        ).show()
        binding.tvStatus.text = "Permissions required"
    }

    private fun onStartStreamClicked() {
        Timber.i("Start stream clicked")
        cameraService?.startStreaming()
        updateStatus(StreamingState.STREAMING)
        binding.apply {
            btnStartStream.visibility = View.GONE
            btnStopStream.visibility = View.VISIBLE
            overlayStreamInfo.visibility = View.VISIBLE

            // Show stream info
            tvRtspUrl.text = "RTSP: rtsp://${getLocalIpAddress()}:8554/live"
            tvResolution.text = "1920x1080 @ 30fps"
            tvBitrate.text = "6.0 Mbps"
        }
    }

    private fun onStopStreamClicked() {
        Timber.i("Stop stream clicked")
        cameraService?.stopStreaming()
        updateStatus(StreamingState.IDLE)
        binding.apply {
            btnStartStream.visibility = View.VISIBLE
            btnStopStream.visibility = View.GONE
            overlayStreamInfo.visibility = View.GONE
        }
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

    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE)
                as android.net.wifi.WifiManager
            val ipAddress = wifiManager.connectionInfo.ipAddress
            return String.format(
                "%d.%d.%d.%d",
                ipAddress and 0xff,
                ipAddress shr 8 and 0xff,
                ipAddress shr 16 and 0xff,
                ipAddress shr 24 and 0xff
            )
        } catch (e: Exception) {
            Timber.w(e, "Failed to get local IP address")
            return "?.?.?.?"
        }
    }

    enum class StreamingState {
        IDLE,
        STREAMING,
        RECORDING,
        ERROR
    }
}
