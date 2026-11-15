package com.lensdaemon.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
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
import com.lensdaemon.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Kiosk Mode Manager
 *
 * Manages Device Owner APIs for single-purpose appliance mode:
 * - Lock Task Mode (prevents home/recents escape)
 * - System UI hiding (status bar, navigation bar)
 * - Package allowlist management
 * - User restrictions while kiosk mode is on (no safe boot, factory reset or
 *   USB file transfer), cleared when it is turned off
 * - Kiosk state tracking, the event log and the restart counter
 *
 * The screen modes (DIM, BLACK, keep-screen-on) belong to
 * [KioskActivityController], which applies them while the phone is pinned.
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

        /** Where [recordRestart] keeps the restart count and time, across restarts. */
        private const val RESTART_PREFS = "lensdaemon_kiosk_restarts"
        private const val KEY_RESTART_COUNT = "restart_count"
        private const val KEY_LAST_RESTART_TIME = "last_restart_time"
        private const val MAX_EVENTS = 100
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

    /**
     * Held while the on/off setting is saved together with the kiosk
     * restrictions that follow it, so they always match the last saved
     * setting: the web API's threads ([updateConfig]) and the activity's main
     * thread ([enableKiosk], [disableKiosk]) both change it.
     */
    private val enabledLock = Any()

    // Tracking (read from the web API's threads)
    @Volatile private var startTime: Long = 0
    @Volatile private var restartCount: Int = 0
    @Volatile private var lastRestartTime: Long = 0

    private val restartPrefs by lazy { context.getSharedPreferences(RESTART_PREFS, Context.MODE_PRIVATE) }

    // Event listeners
    private val listeners = mutableListOf<KioskEventListener>()

    // Event log, in memory: emptied when the app restarts
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
        restartCount = restartPrefs.getInt(KEY_RESTART_COUNT, 0)
        lastRestartTime = restartPrefs.getLong(KEY_LAST_RESTART_TIME, 0)
        updateState()
    }

    /**
     * Check if app is Device Owner
     */
    fun isDeviceOwner(): Boolean {
        return devicePolicyManager.isDeviceOwnerApp(context.packageName)
    }

    /**
     * Check if Lock Task Mode is active: LensDaemon is pinned to the screen
     */
    fun isLockTaskActive(): Boolean {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
        return activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    /**
     * Turn kiosk mode on or off from outside the activity (the web API). The
     * setting is saved, and also decides whether LensDaemon starts on boot; the
     * activity on screen applies it, pinning or unpinning itself. Turning it on
     * brings LensDaemon to the front when it is the device owner, which can
     * start activities from the background. The kiosk restrictions follow at
     * once, pinned or not (see [updateConfig]).
     */
    fun requestKioskMode(enabled: Boolean) {
        updateConfig(_config.value.copy(enabled = enabled))
        logEvent(
            if (enabled) KioskEventType.KIOSK_ENABLED else KioskEventType.KIOSK_DISABLED,
            "Kiosk mode turned ${if (enabled) "on" else "off"} remotely"
        )
        if (enabled && isDeviceOwner()) {
            try {
                context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: ActivityNotFoundException) {
                Timber.tag(TAG).w(e, "Could not bring LensDaemon to the front")
            }
        }
    }

    /**
     * Enable kiosk mode: pin [activity] (lock task mode), hide the system bars
     * and apply the kiosk restrictions. The screen mode is applied by
     * [KioskActivityController].
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

            // Update state
            startTime = System.currentTimeMillis()
            _kioskState.value = KioskState.ENABLED

            // Save enabled state, with no safe boot (which would start the phone
            // without LensDaemon), factory reset or USB file transfer
            saveEnabled(true)

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
     * Disable kiosk mode: unpin [activity], restore the system bars and the
     * screen brightness, and lift the kiosk restrictions.
     */
    fun disableKiosk(activity: Activity): Boolean {
        try {
            // Stop lock task mode
            stopLockTask(activity)

            // Restore system UI
            restoreSystemUI(activity)

            // Update state
            _kioskState.value = KioskState.DISABLED

            // Save disabled state, lifting the kiosk restrictions
            saveEnabled(false)

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

            // Notifications stay hidden whatever lockNotifications says: Android
            // allows LOCK_TASK_FEATURE_NOTIFICATIONS only together with the Home
            // button (LOCK_TASK_FEATURE_HOME), which a kiosk does not offer, and
            // setLockTaskFeatures refuses the whole set when it comes without it
            if (!security.lockNotifications) {
                Timber.tag(TAG).w("lockNotifications=false has no effect: notifications need the Home button in lock task mode")
            }

            // Show the phone's own lock screen when it wakes (without this flag
            // lock task mode hides it, even when a PIN or pattern is set)
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

        // Keep-screen-on and the screen mode are KioskActivityController's

        // Block screenshots if configured
        if (security.blockScreenshots) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }

        Timber.tag(TAG).d("System UI configured for kiosk mode")
    }

    /**
     * Restore system UI to normal: system bars, screenshots and the phone's
     * own brightness (a DIM or BLACK kiosk lowered it). Keep-screen-on is
     * [KioskActivityController]'s, which turns it back on once unpinned.
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

        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val params = activity.window.attributes
        params.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        activity.window.attributes = params

        Timber.tag(TAG).d("System UI restored")
    }

    /**
     * Update configuration. The kiosk restrictions follow the on/off setting
     * here too (the web API, or [requestKioskMode]), whether or not the phone
     * is pinned at the moment: turning kiosk mode on sets them, as device
     * owner, and turning it off lifts them. So a phone that stays pinned while
     * kiosk mode is turned off and on again (its screen off, so the activity
     * neither unpins nor pins it) has them again.
     */
    fun updateConfig(newConfig: KioskConfig) {
        synchronized(enabledLock) {
            val wasEnabled = _config.value.enabled
            _config.value = newConfig
            configStore.saveConfig(newConfig)
            if (newConfig.enabled != wasEnabled) {
                if (newConfig.enabled) setKioskRestrictions() else clearKioskRestrictions()
            }
        }
        updateState()
        Timber.tag(TAG).d("Configuration updated")
    }

    /**
     * Save kiosk mode on or off ([enableKiosk], [disableKiosk]) and set or
     * lift the kiosk restrictions to match, in one step with [updateConfig].
     */
    private fun saveEnabled(enabled: Boolean) {
        synchronized(enabledLock) {
            val newConfig = _config.value.copy(enabled = enabled)
            _config.value = newConfig
            configStore.saveConfig(newConfig)
            if (enabled) setKioskRestrictions() else clearKioskRestrictions()
        }
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
            isScreenLocked = false, // Kiosk mode never locks the screen; the screen timeout turns it off
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
        // The web API reads the log from its own threads
        synchronized(eventLog) {
            eventLog.add(event)

            // Keep the last MAX_EVENTS events
            while (eventLog.size > MAX_EVENTS) {
                eventLog.removeAt(0)
            }
        }

        // Notify listeners
        listeners.forEach { it.onKioskEvent(event) }
    }

    /**
     * Add an event to the kiosk event log (GET /api/kiosk/events) from outside
     * the manager: boot and auto-start, the exit gesture and exit PIN attempts
     * at the phone.
     */
    fun recordEvent(type: KioskEventType, message: String, details: Map<String, Any> = emptyMap()) {
        logEvent(type, message, details)
    }

    /**
     * Get event log
     */
    fun getEventLog(): List<KioskEvent> = synchronized(eventLog) { eventLog.toList() }

    /**
     * Record an automatic restart after the app stopped (CameraService, when
     * Android restarts it after a crash or kill). The count and time are saved
     * at once (commit), so GET /api/kiosk/status shows them across restarts.
     */
    fun recordRestart() {
        restartCount++
        lastRestartTime = System.currentTimeMillis()
        val saved = restartPrefs.edit()
            .putInt(KEY_RESTART_COUNT, restartCount)
            .putLong(KEY_LAST_RESTART_TIME, lastRestartTime)
            .commit()
        if (!saved) Timber.tag(TAG).w("Could not save the restart count")
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
     * Apply appliance preset. Kiosk mode stays on or off as it was (turning it
     * on pins the screen, which is for [requestKioskMode] or [enableKiosk]),
     * and the exit PIN is kept.
     */
    fun applyAppliancePreset() {
        updateConfig(_config.value.withPreset(KioskConfig.APPLIANCE))
        logEvent(KioskEventType.PRESET_APPLIED, "Appliance preset applied", mapOf("preset" to "APPLIANCE"))
        Timber.tag(TAG).i("Applied APPLIANCE preset")
    }

    /**
     * Apply interactive preset, leaving kiosk mode on or off as it was and
     * keeping the exit PIN.
     */
    fun applyInteractivePreset() {
        updateConfig(_config.value.withPreset(KioskConfig.INTERACTIVE))
        logEvent(KioskEventType.PRESET_APPLIED, "Interactive preset applied", mapOf("preset" to "INTERACTIVE"))
        Timber.tag(TAG).i("Applied INTERACTIVE preset")
    }

    /**
     * Set the user restrictions for kiosk mode, as device owner: no factory
     * reset, no safe boot (it would start the phone without LensDaemon, out of
     * kiosk mode) and no USB file transfer. Applied when kiosk mode is turned
     * on ([updateConfig]) and whenever the phone is pinned ([enableKiosk]).
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
     * Clear the kiosk user restrictions, as device owner: when kiosk mode is
     * turned off ([disableKiosk], [updateConfig]).
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
     * Get status for API, as of now: lock task mode starts and stops with the activity
     */
    fun getStatus(): KioskStatus {
        updateState()
        return _kioskStatus.value
    }

    /**
     * Release resources
     */
    fun release() {
        scope.cancel()
        listeners.clear()
    }
}
