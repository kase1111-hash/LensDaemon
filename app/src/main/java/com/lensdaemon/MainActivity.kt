package com.lensdaemon

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lensdaemon.databinding.ActivityMainBinding
import timber.log.Timber

/**
 * Main activity for LensDaemon.
 * Displays camera preview and provides basic controls for streaming.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

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
            initializeCamera()
        } else {
            Timber.w("Some permissions denied: ${permissions.filter { !it.value }.keys}")
            showPermissionDeniedMessage()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        hideSystemUI()
        setupUI()

        if (hasAllPermissions()) {
            initializeCamera()
        } else {
            requestPermissions()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
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
            btnLensWide.setOnClickListener { onLensSelected("wide") }
            btnLensMain.setOnClickListener { onLensSelected("main") }
            btnLensTele.setOnClickListener { onLensSelected("tele") }

            // Initially show start button, hide stop button
            btnStartStream.visibility = View.VISIBLE
            btnStopStream.visibility = View.GONE
        }

        updateStatus(StreamingState.IDLE)
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

    private fun initializeCamera() {
        Timber.i("Initializing camera")
        binding.tvStatus.text = "Camera ready"
        // Camera initialization will be implemented in Phase 2
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
        updateStatus(StreamingState.STREAMING)
        binding.btnStartStream.visibility = View.GONE
        binding.btnStopStream.visibility = View.VISIBLE
        // Streaming will be implemented in Phase 4-5
    }

    private fun onStopStreamClicked() {
        Timber.i("Stop stream clicked")
        updateStatus(StreamingState.IDLE)
        binding.btnStartStream.visibility = View.VISIBLE
        binding.btnStopStream.visibility = View.GONE
    }

    private fun onLensSelected(lens: String) {
        Timber.i("Lens selected: $lens")
        binding.apply {
            btnLensWide.isSelected = lens == "wide"
            btnLensMain.isSelected = lens == "main"
            btnLensTele.isSelected = lens == "tele"
        }
        // Lens switching will be implemented in Phase 3
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
    }

    enum class StreamingState {
        IDLE,
        STREAMING,
        RECORDING,
        ERROR
    }
}
