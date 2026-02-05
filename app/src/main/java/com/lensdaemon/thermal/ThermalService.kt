package com.lensdaemon.thermal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lensdaemon.MainActivity
import com.lensdaemon.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber

/**
 * Foreground service for thermal management
 *
 * Provides:
 * - Continuous temperature monitoring
 * - Automatic throttling via ThermalGovernor
 * - Battery bypass/charge limiting
 * - Temperature history logging
 * - Service binding for API access
 */
class ThermalService : Service(), ThermalGovernorListener {

    companion object {
        private const val TAG = "ThermalService"
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "thermal_service_channel"

        // Intent actions
        const val ACTION_START = "com.lensdaemon.action.START_THERMAL"
        const val ACTION_STOP = "com.lensdaemon.action.STOP_THERMAL"

        /**
         * Create intent to start thermal service
         */
        fun createStartIntent(context: Context): Intent {
            return Intent(context, ThermalService::class.java).apply {
                action = ACTION_START
            }
        }

        /**
         * Create intent to stop thermal service
         */
        fun createStopIntent(context: Context): Intent {
            return Intent(context, ThermalService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }

    // Service binder
    private val binder = ThermalBinder()

    inner class ThermalBinder : Binder() {
        fun getService(): ThermalService = this@ThermalService
    }

    // Coroutine scope
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Thermal components
    private lateinit var monitor: ThermalMonitor
    private lateinit var history: ThermalHistory
    private lateinit var governor: ThermalGovernor
    private var batteryBypass: BatteryBypass? = null

    // Configuration
    private var config = ThermalConfig.DEFAULT

    // Callbacks for external systems (e.g., encoder throttling)
    var onReduceBitrate: ((reductionPercent: Int) -> Unit)? = null
    var onReduceResolution: (() -> Unit)? = null
    var onReduceFramerate: (() -> Unit)? = null
    var onPauseStreaming: (() -> Unit)? = null
    var onResumeStreaming: (() -> Unit)? = null
    var onRestoreSettings: (() -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        Timber.tag(TAG).i("ThermalService created")

        createNotificationChannel()

        // Initialize components
        monitor = ThermalMonitor(applicationContext, config)
        history = ThermalHistory(applicationContext, config)
        batteryBypass = BatteryBypass(applicationContext, config.batteryBypassConfig)
        governor = ThermalGovernor(applicationContext, config)

        // Wire up governor
        governor.initialize(monitor, history, batteryBypass)
        governor.addListener(this)

        // Wire up throttle callbacks
        governor.onReduceBitrate = { percent -> onReduceBitrate?.invoke(percent) }
        governor.onReduceResolution = { onReduceResolution?.invoke() }
        governor.onReduceFramerate = { onReduceFramerate?.invoke() }
        governor.onPauseStreaming = { onPauseStreaming?.invoke() }
        governor.onResumeStreaming = { onResumeStreaming?.invoke() }
        governor.onRestoreSettings = { onRestoreSettings?.invoke() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag(TAG).d("onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                startThermalManagement()
            }
            ACTION_STOP -> {
                stopThermalManagement()
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(TAG).i("ThermalService destroyed")

        governor.release()
        serviceScope.cancel()
    }

    /**
     * Start thermal management
     */
    fun startThermalManagement() {
        Timber.tag(TAG).i("Starting thermal management")

        startForeground(NOTIFICATION_ID, createNotification("Monitoring..."))

        governor.start()
        batteryBypass?.start()

        updateNotification()
    }

    /**
     * Stop thermal management
     */
    fun stopThermalManagement() {
        Timber.tag(TAG).i("Stopping thermal management")

        governor.stop()
        batteryBypass?.stop()
    }

    // ==================== Public API ====================

    /**
     * Get thermal governor
     */
    fun getGovernor(): ThermalGovernor = governor

    /**
     * Get thermal monitor
     */
    fun getMonitor(): ThermalMonitor = monitor

    /**
     * Get thermal history
     */
    fun getHistory(): ThermalHistory = history

    /**
     * Get battery bypass manager
     */
    fun getBatteryBypass(): BatteryBypass? = batteryBypass

    /**
     * Get current thermal status
     */
    fun getStatus(): ThermalStatus = governor.status.value

    /**
     * Get thermal status flow
     */
    fun getStatusFlow(): StateFlow<ThermalStatus> = governor.status

    /**
     * Get CPU temperature
     */
    fun getCpuTemperature(): Float = monitor.cpuTemperature.value

    /**
     * Get battery temperature
     */
    fun getBatteryTemperature(): Float = monitor.batteryTemperature.value

    /**
     * Get current CPU thermal level
     */
    fun getCpuLevel(): ThermalLevel = governor.cpuLevel.value

    /**
     * Get current battery thermal level
     */
    fun getBatteryLevel(): ThermalLevel = governor.batteryLevel.value

    /**
     * Get battery info
     */
    fun getBatteryInfo(): ThermalMonitor.BatteryInfo = monitor.getBatteryInfo()

    /**
     * Get thermal stats
     */
    fun getStats(): ThermalStats = history.getStats()

    /**
     * Check if throttling is active
     */
    fun isThrottling(): Boolean = governor.status.value.isThrottling

    /**
     * Update thermal configuration
     */
    fun updateConfig(newConfig: ThermalConfig) {
        this.config = newConfig
        // Would need to reinitialize components with new config
        Timber.tag(TAG).d("Config update requested")
    }

    // ==================== ThermalGovernorListener ====================

    override fun onThermalLevelChanged(status: ThermalStatus) {
        updateNotification()
    }

    override fun onThrottleAction(action: ThrottleAction, enabled: Boolean) {
        Timber.tag(TAG).d("Throttle action: $action = $enabled")
        updateNotification()
    }

    override fun onEmergencyShutdown(reason: String) {
        Timber.tag(TAG).w("Emergency shutdown: $reason")
        updateNotification("EMERGENCY: $reason")
    }

    // ==================== Notification ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Thermal Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Temperature monitoring and management"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String? = null): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val thermalStatus = governor.status.value
        val contentText = status ?: buildStatusText(thermalStatus)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LensDaemon Thermal")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)

        // Add color based on thermal level
        when (thermalStatus.overallLevel) {
            ThermalLevel.WARNING -> builder.setColor(0xFFFFA500.toInt()) // Orange
            ThermalLevel.CRITICAL -> builder.setColor(0xFFFF6600.toInt()) // Dark Orange
            ThermalLevel.EMERGENCY -> builder.setColor(0xFFFF0000.toInt()) // Red
            else -> {} // Default color
        }

        return builder.build()
    }

    private fun buildStatusText(status: ThermalStatus): String {
        val cpuTemp = "%.1f°C".format(status.cpuTemperatureC)
        val battTemp = "%.1f°C".format(status.batteryTemperatureC)

        return when {
            status.overallLevel == ThermalLevel.EMERGENCY ->
                "EMERGENCY - CPU: $cpuTemp, Battery: $battTemp"
            status.isThrottling ->
                "Throttling - CPU: $cpuTemp, Battery: $battTemp"
            else ->
                "CPU: $cpuTemp, Battery: $battTemp"
        }
    }

    private fun updateNotification(status: String? = null) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(status))
    }
}
