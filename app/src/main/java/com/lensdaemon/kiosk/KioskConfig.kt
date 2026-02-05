package com.lensdaemon.kiosk

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Kiosk mode state
 */
enum class KioskState {
    DISABLED,           // Kiosk mode off, normal device operation
    ENABLED,            // Kiosk mode active, device locked to app
    SETUP_REQUIRED,     // Device owner set, but configuration needed
    NOT_DEVICE_OWNER    // Cannot enable kiosk without device owner
}

/**
 * Screen mode when kiosk is active
 */
enum class ScreenMode {
    PREVIEW,    // Show camera preview
    OFF,        // Screen completely off
    DIM,        // Screen dimmed to minimum brightness
    BLACK       // Show black screen (display on but blank)
}

/**
 * Lock task features that can be enabled
 */
enum class LockTaskFeature {
    NONE,
    HOME,
    RECENTS,
    GLOBAL_ACTIONS,
    NOTIFICATIONS,
    KEYGUARD,
    OVERVIEW
}

/**
 * Auto-start configuration
 */
data class AutoStartConfig(
    val enabled: Boolean = true,
    val delaySeconds: Int = 5,              // Delay after boot before starting
    val startStreaming: Boolean = true,     // Auto-start streaming
    val startRtsp: Boolean = true,          // Auto-start RTSP server
    val startRecording: Boolean = false     // Auto-start recording
)

/**
 * Screen control configuration
 */
data class ScreenConfig(
    val mode: ScreenMode = ScreenMode.PREVIEW,
    val dimBrightness: Int = 10,            // Brightness level for DIM mode (0-255)
    val autoOffTimeoutSec: Int = 60,        // Auto screen off after inactivity
    val wakeOnMotion: Boolean = false,      // Wake screen on camera motion (future)
    val keepScreenOn: Boolean = true        // Keep screen on during streaming
)

/**
 * Security configuration
 */
data class SecurityConfig(
    val exitPin: String = "",                // PIN to exit kiosk mode
    val allowedExitGesture: Boolean = true,  // Allow Vol+Vol+Power gesture to exit
    val exitGestureTimeoutMs: Long = 5000,   // How long to hold gesture
    val lockStatusBar: Boolean = true,       // Prevent status bar access
    val lockNavigationBar: Boolean = true,   // Hide navigation bar
    val lockNotifications: Boolean = true,   // Block notifications
    val blockScreenshots: Boolean = false    // Prevent screenshots
)

/**
 * Network recovery configuration
 */
data class NetworkRecoveryConfig(
    val autoReconnect: Boolean = true,
    val reconnectIntervalSec: Int = 30,
    val maxReconnectAttempts: Int = 0,      // 0 = infinite
    val restartStreamOnReconnect: Boolean = true
)

/**
 * Crash recovery configuration
 */
data class CrashRecoveryConfig(
    val autoRestart: Boolean = true,
    val restartDelayMs: Long = 3000,
    val maxRestartAttempts: Int = 5,        // In a 10-minute window
    val restartWindowMinutes: Int = 10
)

/**
 * Complete kiosk configuration
 */
data class KioskConfig(
    val enabled: Boolean = false,
    val autoStart: AutoStartConfig = AutoStartConfig(),
    val screen: ScreenConfig = ScreenConfig(),
    val security: SecurityConfig = SecurityConfig(),
    val networkRecovery: NetworkRecoveryConfig = NetworkRecoveryConfig(),
    val crashRecovery: CrashRecoveryConfig = CrashRecoveryConfig()
) {
    companion object {
        val DEFAULT = KioskConfig()

        /** Configuration for 24/7 unattended operation */
        val APPLIANCE = KioskConfig(
            enabled = true,
            autoStart = AutoStartConfig(
                enabled = true,
                delaySeconds = 10,
                startStreaming = true,
                startRtsp = true,
                startRecording = false
            ),
            screen = ScreenConfig(
                mode = ScreenMode.OFF,
                autoOffTimeoutSec = 30,
                keepScreenOn = false
            ),
            security = SecurityConfig(
                allowedExitGesture = true,
                exitGestureTimeoutMs = 5000,
                lockStatusBar = true,
                lockNavigationBar = true,
                lockNotifications = true
            ),
            networkRecovery = NetworkRecoveryConfig(
                autoReconnect = true,
                reconnectIntervalSec = 30,
                maxReconnectAttempts = 0
            ),
            crashRecovery = CrashRecoveryConfig(
                autoRestart = true,
                restartDelayMs = 3000,
                maxRestartAttempts = 10
            )
        )

        /** Configuration for interactive kiosk with preview */
        val INTERACTIVE = KioskConfig(
            enabled = true,
            autoStart = AutoStartConfig(
                enabled = true,
                delaySeconds = 3,
                startStreaming = true,
                startRtsp = true
            ),
            screen = ScreenConfig(
                mode = ScreenMode.PREVIEW,
                keepScreenOn = true
            ),
            security = SecurityConfig(
                allowedExitGesture = true,
                lockStatusBar = true,
                lockNavigationBar = false
            )
        )
    }
}

/**
 * Kiosk status snapshot
 */
data class KioskStatus(
    val state: KioskState = KioskState.DISABLED,
    val isDeviceOwner: Boolean = false,
    val isLockTaskActive: Boolean = false,
    val isScreenLocked: Boolean = false,
    val config: KioskConfig = KioskConfig.DEFAULT,
    val uptimeMs: Long = 0,
    val lastRestartTime: Long = 0,
    val restartCount: Int = 0
)

/**
 * Kiosk event for logging
 */
data class KioskEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val type: KioskEventType,
    val message: String,
    val details: Map<String, Any> = emptyMap()
)

/**
 * Kiosk event types
 */
enum class KioskEventType {
    KIOSK_ENABLED,
    KIOSK_DISABLED,
    LOCK_TASK_STARTED,
    LOCK_TASK_STOPPED,
    EXIT_GESTURE_DETECTED,
    EXIT_PIN_ENTERED,
    BOOT_COMPLETED,
    AUTO_START,
    CRASH_DETECTED,
    CRASH_RECOVERY,
    NETWORK_LOST,
    NETWORK_RECOVERED,
    SCREEN_MODE_CHANGED
}

/**
 * Kiosk configuration persistence
 */
class KioskConfigStore(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "lensdaemon_kiosk_config"
        private const val KEY_CONFIG_JSON = "config_json"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Save kiosk configuration
     */
    fun saveConfig(config: KioskConfig) {
        val json = JSONObject().apply {
            put("enabled", config.enabled)

            put("autoStart", JSONObject().apply {
                put("enabled", config.autoStart.enabled)
                put("delaySeconds", config.autoStart.delaySeconds)
                put("startStreaming", config.autoStart.startStreaming)
                put("startRtsp", config.autoStart.startRtsp)
                put("startRecording", config.autoStart.startRecording)
            })

            put("screen", JSONObject().apply {
                put("mode", config.screen.mode.name)
                put("dimBrightness", config.screen.dimBrightness)
                put("autoOffTimeoutSec", config.screen.autoOffTimeoutSec)
                put("wakeOnMotion", config.screen.wakeOnMotion)
                put("keepScreenOn", config.screen.keepScreenOn)
            })

            put("security", JSONObject().apply {
                put("exitPin", config.security.exitPin)
                put("allowedExitGesture", config.security.allowedExitGesture)
                put("exitGestureTimeoutMs", config.security.exitGestureTimeoutMs)
                put("lockStatusBar", config.security.lockStatusBar)
                put("lockNavigationBar", config.security.lockNavigationBar)
                put("lockNotifications", config.security.lockNotifications)
                put("blockScreenshots", config.security.blockScreenshots)
            })

            put("networkRecovery", JSONObject().apply {
                put("autoReconnect", config.networkRecovery.autoReconnect)
                put("reconnectIntervalSec", config.networkRecovery.reconnectIntervalSec)
                put("maxReconnectAttempts", config.networkRecovery.maxReconnectAttempts)
                put("restartStreamOnReconnect", config.networkRecovery.restartStreamOnReconnect)
            })

            put("crashRecovery", JSONObject().apply {
                put("autoRestart", config.crashRecovery.autoRestart)
                put("restartDelayMs", config.crashRecovery.restartDelayMs)
                put("maxRestartAttempts", config.crashRecovery.maxRestartAttempts)
                put("restartWindowMinutes", config.crashRecovery.restartWindowMinutes)
            })
        }

        prefs.edit().putString(KEY_CONFIG_JSON, json.toString()).apply()
    }

    /**
     * Load kiosk configuration
     */
    fun loadConfig(): KioskConfig {
        val jsonStr = prefs.getString(KEY_CONFIG_JSON, null) ?: return KioskConfig.DEFAULT

        return try {
            val json = JSONObject(jsonStr)

            val autoStartJson = json.optJSONObject("autoStart")
            val screenJson = json.optJSONObject("screen")
            val securityJson = json.optJSONObject("security")
            val networkJson = json.optJSONObject("networkRecovery")
            val crashJson = json.optJSONObject("crashRecovery")

            KioskConfig(
                enabled = json.optBoolean("enabled", false),
                autoStart = AutoStartConfig(
                    enabled = autoStartJson?.optBoolean("enabled", true) ?: true,
                    delaySeconds = autoStartJson?.optInt("delaySeconds", 5) ?: 5,
                    startStreaming = autoStartJson?.optBoolean("startStreaming", true) ?: true,
                    startRtsp = autoStartJson?.optBoolean("startRtsp", true) ?: true,
                    startRecording = autoStartJson?.optBoolean("startRecording", false) ?: false
                ),
                screen = ScreenConfig(
                    mode = try {
                        ScreenMode.valueOf(screenJson?.optString("mode", "PREVIEW") ?: "PREVIEW")
                    } catch (e: Exception) {
                        ScreenMode.PREVIEW
                    },
                    dimBrightness = screenJson?.optInt("dimBrightness", 10) ?: 10,
                    autoOffTimeoutSec = screenJson?.optInt("autoOffTimeoutSec", 60) ?: 60,
                    wakeOnMotion = screenJson?.optBoolean("wakeOnMotion", false) ?: false,
                    keepScreenOn = screenJson?.optBoolean("keepScreenOn", true) ?: true
                ),
                security = SecurityConfig(
                    exitPin = securityJson?.optString("exitPin", "") ?: "",
                    allowedExitGesture = securityJson?.optBoolean("allowedExitGesture", true) ?: true,
                    exitGestureTimeoutMs = securityJson?.optLong("exitGestureTimeoutMs", 5000) ?: 5000,
                    lockStatusBar = securityJson?.optBoolean("lockStatusBar", true) ?: true,
                    lockNavigationBar = securityJson?.optBoolean("lockNavigationBar", true) ?: true,
                    lockNotifications = securityJson?.optBoolean("lockNotifications", true) ?: true,
                    blockScreenshots = securityJson?.optBoolean("blockScreenshots", false) ?: false
                ),
                networkRecovery = NetworkRecoveryConfig(
                    autoReconnect = networkJson?.optBoolean("autoReconnect", true) ?: true,
                    reconnectIntervalSec = networkJson?.optInt("reconnectIntervalSec", 30) ?: 30,
                    maxReconnectAttempts = networkJson?.optInt("maxReconnectAttempts", 0) ?: 0,
                    restartStreamOnReconnect = networkJson?.optBoolean("restartStreamOnReconnect", true) ?: true
                ),
                crashRecovery = CrashRecoveryConfig(
                    autoRestart = crashJson?.optBoolean("autoRestart", true) ?: true,
                    restartDelayMs = crashJson?.optLong("restartDelayMs", 3000) ?: 3000,
                    maxRestartAttempts = crashJson?.optInt("maxRestartAttempts", 5) ?: 5,
                    restartWindowMinutes = crashJson?.optInt("restartWindowMinutes", 10) ?: 10
                )
            )
        } catch (e: Exception) {
            KioskConfig.DEFAULT
        }
    }

    /**
     * Check if kiosk is enabled
     */
    fun isKioskEnabled(): Boolean {
        return loadConfig().enabled
    }

    /**
     * Check if auto-start is enabled
     */
    fun isAutoStartEnabled(): Boolean {
        return loadConfig().autoStart.enabled
    }
}
