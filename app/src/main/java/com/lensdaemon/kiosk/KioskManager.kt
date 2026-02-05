package com.lensdaemon.kiosk

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import com.lensdaemon.AdminReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Kiosk Mode Manager
 *
 * Manages Device Owner APIs for single-purpose appliance mode:
 * - Lock Task Mode (prevents home/recents escape)
 * - System UI hiding (status bar, navigation bar)
 * - Package allowlist management
 * - Kiosk state tracking
 *
 * Requirements:
 * - Device must be set as Device Owner via:
 *   adb shell dpm set-device-owner com.lensdaemon/.AdminReceiver
 */
class KioskManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "KioskManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Device policy manager
    private val devicePolicyManager: DevicePolicyManager by lazy {
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    }

    // Admin component
    private val adminComponent: ComponentName by lazy {
        ComponentName(context, AdminReceiver::class.java)
    }

    // Configuration store
    private val configStore: KioskConfigStore by lazy {
        KioskConfigStore(context)
    }

    // State
    private val _kioskState = MutableStateFlow(KioskState.DISABLED)
    val kioskState: StateFlow<KioskState> = _kioskState.asStateFlow()

    private val _kioskStatus = MutableStateFlow(KioskStatus())
    val kioskStatus: StateFlow<KioskStatus> = _kioskStatus.asStateFlow()

    private val _config = MutableStateFlow(KioskConfig.DEFAULT)
    val config: StateFlow<KioskConfig> = _config.asStateFlow()

    // Tracking
    private var startTime: Long = 0
    private var restartCount: Int = 0
    private var lastRestartTime: Long = 0

    // Event listeners
    private val listeners = mutableListOf<KioskEventListener>()

    // Event log
    private val eventLog = mutableListOf<KioskEvent>()

    /**
     * Listener interface for kiosk events
     */
    interface KioskEventListener {
        fun onKioskStateChanged(state: KioskState)
        fun onKioskEvent(event: KioskEvent)
    }

    init {
        loadConfig()
        updateState()
    }

    /**
     * Check if app is Device Owner
     */
    fun isDeviceOwner(): Boolean {
        return devicePolicyManager.isDeviceOwnerApp(context.packageName)
    }

    /**
     * Check if Lock Task Mode is active
     */
    fun isLockTaskActive(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            devicePolicyManager.isLockTaskPermitted(context.packageName)
        } else {
            false
        }
    }

    /**
     * Enable kiosk mode
     */
    fun enableKiosk(activity: Activity): Boolean {
        if (!isDeviceOwner()) {
            Timber.tag(TAG).w("Cannot enable kiosk: not device owner")
            _kioskState.value = KioskState.NOT_DEVICE_OWNER
            logEvent(KioskEventType.KIOSK_DISABLED, "Not device owner")
            return false
        }

        try {
            // Set this package as allowed for lock task
            setLockTaskPackages()

            // Start lock task mode
            startLockTask(activity)

            // Configure system UI
            configureSystemUI(activity)

            // Apply screen configuration
            applyScreenConfig(activity)

            // Update state
            startTime = System.currentTimeMillis()
            _kioskState.value = KioskState.ENABLED

            // Save enabled state
            val newConfig = _config.value.copy(enabled = true)
            _config.value = newConfig
            configStore.saveConfig(newConfig)

            updateStatus()
            logEvent(KioskEventType.KIOSK_ENABLED, "Kiosk mode enabled")

            Timber.tag(TAG).i("Kiosk mode enabled")
            return true

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to enable kiosk mode")
            logEvent(KioskEventType.KIOSK_DISABLED, "Enable failed: ${e.message}")
            return false
        }
    }

    /**
     * Disable kiosk mode
     */
    fun disableKiosk(activity: Activity): Boolean {
        try {
            // Stop lock task mode
            stopLockTask(activity)

            // Restore system UI
            restoreSystemUI(activity)

            // Update state
            _kioskState.value = KioskState.DISABLED

            // Save disabled state
            val newConfig = _config.value.copy(enabled = false)
            _config.value = newConfig
            configStore.saveConfig(newConfig)

            updateStatus()
            logEvent(KioskEventType.KIOSK_DISABLED, "Kiosk mode disabled")

            Timber.tag(TAG).i("Kiosk mode disabled")
            return true

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to disable kiosk mode")
            return false
        }
    }

    /**
     * Set packages allowed in lock task mode
     */
    private fun setLockTaskPackages() {
        if (!isDeviceOwner()) return

        val packages = arrayOf(
            context.packageName,
            "com.android.settings" // Allow settings for WiFi config if needed
        )

        try {
            devicePolicyManager.setLockTaskPackages(adminComponent, packages)
            Timber.tag(TAG).d("Lock task packages set: ${packages.joinToString()}")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set lock task packages")
        }
    }

    /**
     * Start lock task mode
     */
    private fun startLockTask(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            // Configure lock task features
            val features = configureLockTaskFeatures()
            try {
                devicePolicyManager.setLockTaskFeatures(adminComponent, features)
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to set lock task features")
            }
        }

        // Start lock task
        activity.startLockTask()
        logEvent(KioskEventType.LOCK_TASK_STARTED, "Lock task mode started")
        Timber.tag(TAG).d("Lock task started")
    }

    /**
     * Stop lock task mode
     */
    private fun stopLockTask(activity: Activity) {
        try {
            activity.stopLockTask()
            logEvent(KioskEventType.LOCK_TASK_STOPPED, "Lock task mode stopped")
            Timber.tag(TAG).d("Lock task stopped")
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Failed to stop lock task")
        }
    }

    /**
     * Configure which features are available in lock task mode
     */
    private fun configureLockTaskFeatures(): Int {
        val security = _config.value.security
        var features = DevicePolicyManager.LOCK_TASK_FEATURE_NONE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Allow global actions (power menu) for emergency shutdown
            if (security.allowedExitGesture) {
                features = features or DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS
            }

            // Block notifications if configured
            if (!security.lockNotifications) {
                features = features or DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
            }

            // Block keyguard
            features = features or DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD
        }

        return features
    }

    /**
     * Configure system UI for kiosk mode
     */
    private fun configureSystemUI(activity: Activity) {
        val security = _config.value.security

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+ approach
            activity.window.insetsController?.let { controller ->
                if (security.lockStatusBar) {
                    controller.hide(WindowInsets.Type.statusBars())
                }
                if (security.lockNavigationBar) {
                    controller.hide(WindowInsets.Type.navigationBars())
                }
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // Legacy approach
            @Suppress("DEPRECATION")
            var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

            if (security.lockStatusBar) {
                @Suppress("DEPRECATION")
                flags = flags or View.SYSTEM_UI_FLAG_FULLSCREEN
            }
            if (security.lockNavigationBar) {
                @Suppress("DEPRECATION")
                flags = flags or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            }

            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = flags
        }

        // Keep screen on during kiosk mode
        if (_config.value.screen.keepScreenOn) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Block screenshots if configured
        if (security.blockScreenshots) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        Timber.tag(TAG).d("System UI configured for kiosk mode")
    }

    /**
     * Restore system UI to normal
     */
    private fun restoreSystemUI(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.window.insetsController?.let { controller ->
                controller.show(WindowInsets.Type.statusBars())
                controller.show(WindowInsets.Type.navigationBars())
            }
        } else {
            @Suppress("DEPRECATION")
            activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }

        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)

        Timber.tag(TAG).d("System UI restored")
    }

    /**
     * Apply screen configuration
     */
    private fun applyScreenConfig(activity: Activity) {
        val screenConfig = _config.value.screen

        when (screenConfig.mode) {
            ScreenMode.OFF -> {
                // Turn off screen - requires additional permissions
                Timber.tag(TAG).d("Screen mode: OFF (requires ScreenController)")
            }
            ScreenMode.DIM -> {
                // Set minimum brightness
                val params = activity.window.attributes
                params.screenBrightness = screenConfig.dimBrightness / 255f
                activity.window.attributes = params
                Timber.tag(TAG).d("Screen mode: DIM (${screenConfig.dimBrightness}/255)")
            }
            ScreenMode.BLACK -> {
                // Black overlay - handled by ScreenController
                Timber.tag(TAG).d("Screen mode: BLACK (requires ScreenController)")
            }
            ScreenMode.PREVIEW -> {
                // Normal preview mode
                val params = activity.window.attributes
                params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
                activity.window.attributes = params
                Timber.tag(TAG).d("Screen mode: PREVIEW")
            }
        }
    }

    /**
     * Update configuration
     */
    fun updateConfig(newConfig: KioskConfig) {
        _config.value = newConfig
        configStore.saveConfig(newConfig)
        Timber.tag(TAG).d("Configuration updated")
    }

    /**
     * Get current configuration
     */
    fun getConfig(): KioskConfig = _config.value

    /**
     * Load configuration from store
     */
    private fun loadConfig() {
        _config.value = configStore.loadConfig()
    }

    /**
     * Update current state based on device status
     */
    private fun updateState() {
        val state = when {
            !isDeviceOwner() -> KioskState.NOT_DEVICE_OWNER
            _config.value.enabled && isLockTaskActive() -> KioskState.ENABLED
            isDeviceOwner() && !_config.value.enabled -> KioskState.DISABLED
            else -> KioskState.SETUP_REQUIRED
        }
        _kioskState.value = state
        updateStatus()
    }

    /**
     * Update status snapshot
     */
    private fun updateStatus() {
        _kioskStatus.value = KioskStatus(
            state = _kioskState.value,
            isDeviceOwner = isDeviceOwner(),
            isLockTaskActive = isLockTaskActive(),
            isScreenLocked = false, // Updated by ScreenController
            config = _config.value,
            uptimeMs = if (startTime > 0) System.currentTimeMillis() - startTime else 0,
            lastRestartTime = lastRestartTime,
            restartCount = restartCount
        )

        // Notify listeners
        listeners.forEach { it.onKioskStateChanged(_kioskState.value) }
    }

    /**
     * Log a kiosk event
     */
    private fun logEvent(type: KioskEventType, message: String, details: Map<String, Any> = emptyMap()) {
        val event = KioskEvent(
            timestamp = System.currentTimeMillis(),
            type = type,
            message = message,
            details = details
        )
        eventLog.add(event)

        // Keep last 100 events
        while (eventLog.size > 100) {
            eventLog.removeAt(0)
        }

        // Notify listeners
        listeners.forEach { it.onKioskEvent(event) }
    }

    /**
     * Get event log
     */
    fun getEventLog(): List<KioskEvent> = eventLog.toList()

    /**
     * Record a restart (called by crash recovery)
     */
    fun recordRestart() {
        restartCount++
        lastRestartTime = System.currentTimeMillis()
        logEvent(KioskEventType.CRASH_RECOVERY, "App restarted (count: $restartCount)")
        updateStatus()
    }

    /**
     * Add event listener
     */
    fun addListener(listener: KioskEventListener) {
        if (listener !in listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Remove event listener
     */
    fun removeListener(listener: KioskEventListener) {
        listeners.remove(listener)
    }

    /**
     * Check if auto-start should happen on boot
     */
    fun shouldAutoStart(): Boolean {
        val config = _config.value
        return config.enabled && config.autoStart.enabled
    }

    /**
     * Get auto-start delay in seconds
     */
    fun getAutoStartDelay(): Int {
        return _config.value.autoStart.delaySeconds
    }

    /**
     * Check if should auto-start streaming
     */
    fun shouldAutoStartStreaming(): Boolean {
        return _config.value.autoStart.startStreaming
    }

    /**
     * Check if should auto-start RTSP
     */
    fun shouldAutoStartRtsp(): Boolean {
        return _config.value.autoStart.startRtsp
    }

    /**
     * Apply appliance preset
     */
    fun applyAppliancePreset() {
        updateConfig(KioskConfig.APPLIANCE)
        Timber.tag(TAG).i("Applied APPLIANCE preset")
    }

    /**
     * Apply interactive preset
     */
    fun applyInteractivePreset() {
        updateConfig(KioskConfig.INTERACTIVE)
        Timber.tag(TAG).i("Applied INTERACTIVE preset")
    }

    /**
     * Set user restrictions for kiosk mode
     */
    fun setKioskRestrictions() {
        if (!isDeviceOwner()) return

        try {
            // Disable factory reset
            devicePolicyManager.addUserRestriction(
                adminComponent,
                UserManager.DISALLOW_FACTORY_RESET
            )

            // Disable safe boot
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                devicePolicyManager.addUserRestriction(
                    adminComponent,
                    UserManager.DISALLOW_SAFE_BOOT
                )
            }

            // Disable USB file transfer
            devicePolicyManager.addUserRestriction(
                adminComponent,
                UserManager.DISALLOW_USB_FILE_TRANSFER
            )

            Timber.tag(TAG).i("Kiosk restrictions applied")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to set kiosk restrictions")
        }
    }

    /**
     * Clear user restrictions
     */
    fun clearKioskRestrictions() {
        if (!isDeviceOwner()) return

        try {
            devicePolicyManager.clearUserRestriction(
                adminComponent,
                UserManager.DISALLOW_FACTORY_RESET
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                devicePolicyManager.clearUserRestriction(
                    adminComponent,
                    UserManager.DISALLOW_SAFE_BOOT
                )
            }

            devicePolicyManager.clearUserRestriction(
                adminComponent,
                UserManager.DISALLOW_USB_FILE_TRANSFER
            )

            Timber.tag(TAG).i("Kiosk restrictions cleared")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to clear kiosk restrictions")
        }
    }

    /**
     * Get status for API
     */
    fun getStatus(): KioskStatus = _kioskStatus.value

    /**
     * Release resources
     */
    fun release() {
        scope.cancel()
        listeners.clear()
    }
}
