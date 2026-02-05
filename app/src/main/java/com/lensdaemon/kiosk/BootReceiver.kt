package com.lensdaemon.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.lensdaemon.MainActivity
import com.lensdaemon.camera.CameraService
import com.lensdaemon.web.WebServerService
import timber.log.Timber

/**
 * Broadcast receiver to auto-start LensDaemon on device boot.
 *
 * Handles:
 * - Boot completed detection
 * - Configurable startup delay
 * - Auto-start of camera, streaming, and RTSP services
 * - Kiosk mode reactivation
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    private val handler = Handler(Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON") {
            return
        }

        Timber.tag(TAG).i("Boot completed received")

        // Load configuration
        val configStore = KioskConfigStore(context)
        val config = configStore.loadConfig()

        // Log the boot event
        logBootEvent(context, config)

        // Check if auto-start is enabled
        if (!config.enabled || !config.autoStart.enabled) {
            Timber.tag(TAG).i("Auto-start disabled, skipping boot sequence")
            return
        }

        // Get delay before starting
        val delayMs = config.autoStart.delaySeconds * 1000L
        Timber.tag(TAG).i("Auto-start enabled, launching in ${config.autoStart.delaySeconds} seconds")

        // Schedule the startup sequence
        handler.postDelayed({
            startupSequence(context, config)
        }, delayMs)
    }

    /**
     * Execute the startup sequence
     */
    private fun startupSequence(context: Context, config: KioskConfig) {
        Timber.tag(TAG).i("Executing startup sequence")

        try {
            // 1. Start the main activity
            startMainActivity(context)

            // 2. Start web server service (needed for API access)
            startWebServerService(context)

            // 3. Start camera/streaming services if configured
            if (config.autoStart.startStreaming) {
                startCameraService(context)
            }

            // 4. Start RTSP server if configured
            if (config.autoStart.startRtsp) {
                // RTSP is started via CameraService
                Timber.tag(TAG).d("RTSP auto-start configured")
            }

            // 5. Start recording if configured
            if (config.autoStart.startRecording) {
                // Recording is started via CameraService
                Timber.tag(TAG).d("Recording auto-start configured")
            }

            Timber.tag(TAG).i("Startup sequence completed")

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error during startup sequence")
        }
    }

    /**
     * Start the main activity
     */
    private fun startMainActivity(context: Context) {
        try {
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                putExtra(EXTRA_BOOT_START, true)
            }
            context.startActivity(launchIntent)
            Timber.tag(TAG).d("Main activity started")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start main activity")
        }
    }

    /**
     * Start the camera foreground service
     */
    private fun startCameraService(context: Context) {
        try {
            val serviceIntent = Intent(context, CameraService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Timber.tag(TAG).d("Camera service started")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start camera service")
        }
    }

    /**
     * Start the web server foreground service
     */
    private fun startWebServerService(context: Context) {
        try {
            val serviceIntent = Intent(context, WebServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Timber.tag(TAG).d("Web server service started")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start web server service")
        }
    }

    /**
     * Log the boot event for tracking
     */
    private fun logBootEvent(context: Context, config: KioskConfig) {
        // Store boot timestamp for crash recovery tracking
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val lastBootTime = prefs.getLong(KEY_LAST_BOOT_TIME, 0)
        val bootCount = prefs.getInt(KEY_BOOT_COUNT, 0)

        prefs.edit()
            .putLong(KEY_LAST_BOOT_TIME, System.currentTimeMillis())
            .putInt(KEY_BOOT_COUNT, bootCount + 1)
            .apply()

        Timber.tag(TAG).i("Boot #${bootCount + 1}, last boot: $lastBootTime")
    }

    companion object {
        const val EXTRA_BOOT_START = "boot_start"
        private const val PREFS_NAME = "lensdaemon_boot"
        private const val KEY_LAST_BOOT_TIME = "last_boot_time"
        private const val KEY_BOOT_COUNT = "boot_count"
    }
}

/**
 * Crash recovery manager
 *
 * Monitors app crashes and handles automatic restart
 */
class CrashRecoveryManager(
    private val context: Context,
    private val config: CrashRecoveryConfig = CrashRecoveryConfig()
) {
    companion object {
        private const val TAG = "CrashRecoveryManager"
        private const val PREFS_NAME = "lensdaemon_crash"
        private const val KEY_CRASH_TIMES = "crash_times"
        private const val KEY_RESTART_COUNT = "restart_count"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Install crash handler
     */
    fun install() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            Timber.tag(TAG).e(throwable, "Uncaught exception on thread ${thread.name}")

            recordCrash()

            if (shouldRestart()) {
                scheduleRestart()
            }

            // Call default handler (will terminate the app)
            defaultHandler?.uncaughtException(thread, throwable)
        }

        Timber.tag(TAG).i("Crash recovery handler installed")
    }

    /**
     * Record a crash occurrence
     */
    private fun recordCrash() {
        val crashTimes = getCrashTimes().toMutableList()
        crashTimes.add(System.currentTimeMillis())

        // Keep only crashes within the window
        val windowStart = System.currentTimeMillis() - (config.restartWindowMinutes * 60 * 1000L)
        val recentCrashes = crashTimes.filter { it >= windowStart }

        prefs.edit()
            .putString(KEY_CRASH_TIMES, recentCrashes.joinToString(","))
            .apply()

        Timber.tag(TAG).d("Crash recorded, ${recentCrashes.size} crashes in window")
    }

    /**
     * Get crash times within the recovery window
     */
    private fun getCrashTimes(): List<Long> {
        val stored = prefs.getString(KEY_CRASH_TIMES, "") ?: ""
        if (stored.isEmpty()) return emptyList()

        val windowStart = System.currentTimeMillis() - (config.restartWindowMinutes * 60 * 1000L)
        return stored.split(",")
            .mapNotNull { it.toLongOrNull() }
            .filter { it >= windowStart }
    }

    /**
     * Check if we should restart after crash
     */
    private fun shouldRestart(): Boolean {
        if (!config.autoRestart) {
            return false
        }

        val crashCount = getCrashTimes().size
        if (config.maxRestartAttempts > 0 && crashCount >= config.maxRestartAttempts) {
            Timber.tag(TAG).w("Max restart attempts ($config.maxRestartAttempts) exceeded")
            return false
        }

        return true
    }

    /**
     * Schedule app restart
     */
    private fun scheduleRestart() {
        Timber.tag(TAG).i("Scheduling restart in ${config.restartDelayMs}ms")

        val restartIntent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
            putExtra(CrashRecoveryManager.EXTRA_CRASH_RESTART, true)
        }

        handler.postDelayed({
            try {
                context.startActivity(restartIntent)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to restart app")
            }
        }, config.restartDelayMs)
    }

    /**
     * Get restart count since last boot
     */
    fun getRestartCount(): Int {
        return prefs.getInt(KEY_RESTART_COUNT, 0)
    }

    /**
     * Increment restart count (call from MainActivity when recovering)
     */
    fun incrementRestartCount() {
        val count = getRestartCount() + 1
        prefs.edit().putInt(KEY_RESTART_COUNT, count).apply()
    }

    /**
     * Reset restart count (call on clean start)
     */
    fun resetRestartCount() {
        prefs.edit().putInt(KEY_RESTART_COUNT, 0).apply()
    }

    /**
     * Clear all crash data
     */
    fun clearCrashData() {
        prefs.edit()
            .remove(KEY_CRASH_TIMES)
            .remove(KEY_RESTART_COUNT)
            .apply()
    }

    companion object {
        const val EXTRA_CRASH_RESTART = "crash_restart"
    }
}

/**
 * Network recovery handler
 *
 * Monitors network connectivity and handles reconnection
 */
class NetworkRecoveryHandler(
    private val context: Context,
    private val config: NetworkRecoveryConfig = NetworkRecoveryConfig()
) {
    companion object {
        private const val TAG = "NetworkRecoveryHandler"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var reconnectAttempts = 0
    private var reconnectRunnable: Runnable? = null
    private var listener: NetworkRecoveryListener? = null
    private var isConnected = true

    /**
     * Listener interface
     */
    interface NetworkRecoveryListener {
        fun onNetworkLost()
        fun onNetworkRecovered()
        fun onReconnectAttempt(attempt: Int, maxAttempts: Int)
    }

    /**
     * Set listener
     */
    fun setListener(networkListener: NetworkRecoveryListener?) {
        this.listener = networkListener
    }

    /**
     * Called when network is lost
     */
    fun onNetworkLost() {
        if (!isConnected) return
        isConnected = false

        Timber.tag(TAG).w("Network lost")
        listener?.onNetworkLost()

        if (config.autoReconnect) {
            startReconnectLoop()
        }
    }

    /**
     * Called when network is recovered
     */
    fun onNetworkRecovered() {
        if (isConnected) return
        isConnected = true

        Timber.tag(TAG).i("Network recovered after $reconnectAttempts attempts")
        stopReconnectLoop()
        reconnectAttempts = 0
        listener?.onNetworkRecovered()
    }

    /**
     * Start the reconnection attempt loop
     */
    private fun startReconnectLoop() {
        if (reconnectRunnable != null) return

        reconnectRunnable = object : Runnable {
            override fun run() {
                if (isConnected) {
                    stopReconnectLoop()
                    return
                }

                reconnectAttempts++

                if (config.maxReconnectAttempts > 0 && reconnectAttempts > config.maxReconnectAttempts) {
                    Timber.tag(TAG).w("Max reconnect attempts exceeded")
                    stopReconnectLoop()
                    return
                }

                Timber.tag(TAG).d("Reconnect attempt $reconnectAttempts")
                listener?.onReconnectAttempt(reconnectAttempts, config.maxReconnectAttempts)

                // Schedule next attempt
                handler.postDelayed(this, config.reconnectIntervalSec * 1000L)
            }
        }

        handler.postDelayed(reconnectRunnable!!, config.reconnectIntervalSec * 1000L)
    }

    /**
     * Stop the reconnection loop
     */
    private fun stopReconnectLoop() {
        reconnectRunnable?.let { handler.removeCallbacks(it) }
        reconnectRunnable = null
    }

    /**
     * Get current reconnect attempts
     */
    fun getReconnectAttempts(): Int = reconnectAttempts

    /**
     * Reset state
     */
    fun reset() {
        stopReconnectLoop()
        reconnectAttempts = 0
        isConnected = true
    }

    /**
     * Release resources
     */
    fun release() {
        stopReconnectLoop()
        listener = null
    }
}
