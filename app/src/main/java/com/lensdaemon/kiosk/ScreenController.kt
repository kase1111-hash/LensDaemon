package com.lensdaemon.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import com.lensdaemon.AdminReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Screen Controller
 *
 * Manages screen state for kiosk mode:
 * - Screen off (using Device Admin)
 * - Screen dimming (brightness control)
 * - Black overlay (display on but showing black)
 * - Auto-off timeout
 * - Wake on activity
 */
class ScreenController(
    private val context: Context
) {
    companion object {
        private const val TAG = "ScreenController"
        private const val MIN_BRIGHTNESS = 0
        private const val MAX_BRIGHTNESS = 255
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val handler = Handler(Looper.getMainLooper())

    // Device policy manager for screen lock
    private val devicePolicyManager: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    private val powerManager: PowerManager by lazy {
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    private val adminComponent: ComponentName by lazy {
        ComponentName(context, AdminReceiver::class.java)
    }

    // State
    private val _currentMode = MutableStateFlow(ScreenMode.PREVIEW)
    val currentMode: StateFlow<ScreenMode> = _currentMode.asStateFlow()

    private val _isScreenOn = MutableStateFlow(true)
    val isScreenOn: StateFlow<Boolean> = _isScreenOn.asStateFlow()

    private val _currentBrightness = MutableStateFlow(MAX_BRIGHTNESS)
    val currentBrightness: StateFlow<Int> = _currentBrightness.asStateFlow()

    // Configuration
    private var config = ScreenConfig()

    // Black overlay view
    private var blackOverlay: View? = null
    private var overlayContainer: FrameLayout? = null

    // Auto-off timeout
    private var autoOffJob: Job? = null
    private var lastActivityTime = System.currentTimeMillis()

    // Original brightness for restore
    private var originalBrightness: Int = MAX_BRIGHTNESS
    private var originalBrightnessMode: Int = Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC

    // Wake lock for keeping screen on
    private var wakeLock: PowerManager.WakeLock? = null

    init {
        saveOriginalBrightness()
    }

    /**
     * Configure screen controller
     */
    fun configure(screenConfig: ScreenConfig) {
        this.config = screenConfig
        Timber.tag(TAG).d("Screen configured: mode=${screenConfig.mode}, timeout=${screenConfig.autoOffTimeoutSec}s")
    }

    /**
     * Apply screen mode
     */
    fun applyMode(activity: Activity, mode: ScreenMode) {
        Timber.tag(TAG).i("Applying screen mode: $mode")

        // Remove any existing overlay first
        removeBlackOverlay()

        when (mode) {
            ScreenMode.PREVIEW -> {
                applyPreviewMode(activity)
            }
            ScreenMode.OFF -> {
                applyOffMode(activity)
            }
            ScreenMode.DIM -> {
                applyDimMode(activity)
            }
            ScreenMode.BLACK -> {
                applyBlackMode(activity)
            }
        }

        _currentMode.value = mode

        // Start auto-off timer if configured
        if (mode != ScreenMode.OFF && config.autoOffTimeoutSec > 0) {
            startAutoOffTimer()
        }
    }

    /**
     * Apply preview mode (normal display)
     */
    private fun applyPreviewMode(activity: Activity) {
        restoreBrightness(activity)
        releaseWakeLock()

        if (config.keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        _isScreenOn.value = true
    }

    /**
     * Apply off mode (screen completely off)
     */
    private fun applyOffMode(activity: Activity) {
        // Use Device Admin to lock screen
        if (isDeviceAdmin()) {
            try {
                devicePolicyManager.lockNow()
                _isScreenOn.value = false
                Timber.tag(TAG).d("Screen locked via Device Admin")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to lock screen")
                // Fallback to black mode
                applyBlackMode(activity)
            }
        } else {
            // No admin, use black overlay + minimum brightness
            applyBlackMode(activity)
            setBrightness(activity, MIN_BRIGHTNESS)
        }
    }

    /**
     * Apply dim mode (minimum brightness)
     */
    private fun applyDimMode(activity: Activity) {
        setBrightness(activity, config.dimBrightness)

        if (config.keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        _isScreenOn.value = true
    }

    /**
     * Apply black mode (display on but showing black overlay)
     */
    private fun applyBlackMode(activity: Activity) {
        addBlackOverlay(activity)
        setBrightness(activity, config.dimBrightness)

        if (config.keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        _isScreenOn.value = true
    }

    /**
     * Add black overlay to the activity
     */
    private fun addBlackOverlay(activity: Activity) {
        handler.post {
            try {
                // Find the root layout
                val rootView = activity.window.decorView.findViewById<FrameLayout>(android.R.id.content)
                overlayContainer = rootView

                // Create black overlay
                val overlay = View(activity).apply {
                    setBackgroundColor(0xFF000000.toInt())
                    layoutParams = FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                    )
                    elevation = Float.MAX_VALUE // Ensure it's on top
                    isClickable = true // Consume touch events
                    isFocusable = true

                    // Add touch listener to detect activity
                    setOnTouchListener { _, _ ->
                        recordActivity()
                        true
                    }
                }

                rootView.addView(overlay)
                blackOverlay = overlay

                Timber.tag(TAG).d("Black overlay added")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to add black overlay")
            }
        }
    }

    /**
     * Remove black overlay
     */
    private fun removeBlackOverlay() {
        handler.post {
            blackOverlay?.let { overlay ->
                overlayContainer?.removeView(overlay)
                blackOverlay = null
                overlayContainer = null
                Timber.tag(TAG).d("Black overlay removed")
            }
        }
    }

    /**
     * Set screen brightness
     */
    private fun setBrightness(activity: Activity, brightness: Int) {
        val clampedBrightness = brightness.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)

        handler.post {
            try {
                // Set window brightness
                val params = activity.window.attributes
                params.screenBrightness = clampedBrightness / 255f
                activity.window.attributes = params

                _currentBrightness.value = clampedBrightness
                Timber.tag(TAG).d("Brightness set to $clampedBrightness")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to set brightness")
            }
        }
    }

    /**
     * Restore original brightness
     */
    private fun restoreBrightness(activity: Activity) {
        handler.post {
            try {
                val params = activity.window.attributes
                params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                activity.window.attributes = params

                _currentBrightness.value = originalBrightness
                Timber.tag(TAG).d("Brightness restored")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to restore brightness")
            }
        }
    }

    /**
     * Save original brightness settings
     */
    private fun saveOriginalBrightness() {
        try {
            originalBrightness = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                MAX_BRIGHTNESS
            )
            originalBrightnessMode = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            )
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to save original brightness")
        }
    }

    /**
     * Wake the screen
     */
    fun wakeScreen(activity: Activity) {
        if (!_isScreenOn.value || _currentMode.value == ScreenMode.OFF) {
            Timber.tag(TAG).d("Waking screen")

            // Acquire temporary wake lock
            acquireWakeLock()

            // Apply preview mode
            applyMode(activity, ScreenMode.PREVIEW)

            recordActivity()
        }
    }

    /**
     * Record user activity (resets auto-off timer)
     */
    fun recordActivity() {
        lastActivityTime = System.currentTimeMillis()

        if (config.autoOffTimeoutSec > 0 && _currentMode.value != ScreenMode.OFF) {
            startAutoOffTimer()
        }
    }

    /**
     * Start auto-off timer
     */
    private fun startAutoOffTimer() {
        autoOffJob?.cancel()

        if (config.autoOffTimeoutSec <= 0) return

        autoOffJob = scope.launch {
            while (isActive) {
                delay(1000)

                val elapsed = System.currentTimeMillis() - lastActivityTime
                val timeoutMs = config.autoOffTimeoutSec * 1000L

                if (elapsed >= timeoutMs && _currentMode.value != ScreenMode.OFF) {
                    Timber.tag(TAG).d("Auto-off timeout reached")
                    // Request screen off via event
                    // Note: Activity reference would be needed here
                    // This would typically trigger an event to KioskManager
                    break
                }
            }
        }
    }

    /**
     * Stop auto-off timer
     */
    private fun stopAutoOffTimer() {
        autoOffJob?.cancel()
        autoOffJob = null
    }

    /**
     * Acquire wake lock
     */
    private fun acquireWakeLock() {
        if (wakeLock == null) {
            @Suppress("DEPRECATION")
            wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "LensDaemon:ScreenWake"
            )
        }
        wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max
    }

    /**
     * Release wake lock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    /**
     * Check if app is device admin
     */
    private fun isDeviceAdmin(): Boolean {
        return devicePolicyManager.isAdminActive(adminComponent)
    }

    /**
     * Check if app is device owner
     */
    private fun isDeviceOwner(): Boolean {
        return devicePolicyManager.isDeviceOwnerApp(context.packageName)
    }

    /**
     * Get current screen state info
     */
    fun getScreenState(): ScreenState {
        return ScreenState(
            mode = _currentMode.value,
            isOn = _isScreenOn.value,
            brightness = _currentBrightness.value,
            autoOffTimeoutSec = config.autoOffTimeoutSec,
            secondsUntilOff = if (config.autoOffTimeoutSec > 0) {
                val elapsed = (System.currentTimeMillis() - lastActivityTime) / 1000
                maxOf(0, config.autoOffTimeoutSec - elapsed.toInt())
            } else 0
        )
    }

    /**
     * Release resources
     */
    fun release() {
        stopAutoOffTimer()
        releaseWakeLock()
        removeBlackOverlay()
        scope.cancel()
    }
}

/**
 * Screen state for status reporting
 */
data class ScreenState(
    val mode: ScreenMode,
    val isOn: Boolean,
    val brightness: Int,
    val autoOffTimeoutSec: Int,
    val secondsUntilOff: Int
)
