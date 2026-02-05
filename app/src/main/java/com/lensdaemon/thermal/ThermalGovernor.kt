package com.lensdaemon.thermal

import android.content.Context
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
 * Thermal governor for automatic throttling
 *
 * Monitors temperatures and applies protective measures:
 * - CPU > 45°C → Reduce bitrate by 20%
 * - CPU > 50°C → Reduce resolution to 1080p
 * - CPU > 55°C → Reduce framerate to 24fps
 * - CPU > 60°C → Pause streaming
 * - Battery > 42°C → Disable charging
 * - Battery > 45°C → Alert user
 *
 * Features:
 * - Hysteresis to prevent oscillation
 * - Smooth transitions between states
 * - Emergency shutdown protection
 * - Event callbacks for state changes
 */
class ThermalGovernor(
    private val context: Context,
    private val config: ThermalConfig = ThermalConfig.DEFAULT
) : ThermalMonitor.ThermalMonitorListener {

    companion object {
        private const val TAG = "ThermalGovernor"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var governorJob: Job? = null

    // Monitor and history
    private lateinit var monitor: ThermalMonitor
    private lateinit var history: ThermalHistory
    private var batteryBypass: BatteryBypass? = null

    // Current state
    private val _status = MutableStateFlow(ThermalStatus())
    val status: StateFlow<ThermalStatus> = _status.asStateFlow()

    private val _cpuLevel = MutableStateFlow(ThermalLevel.NORMAL)
    val cpuLevel: StateFlow<ThermalLevel> = _cpuLevel.asStateFlow()

    private val _batteryLevel = MutableStateFlow(ThermalLevel.NORMAL)
    val batteryLevel: StateFlow<ThermalLevel> = _batteryLevel.asStateFlow()

    // Active throttling state
    private val activeActions = mutableSetOf<ThrottleAction>()
    private var currentBitrateReduction = 0
    private var isResolutionReduced = false
    private var isFramerateReduced = false
    private var isStreamingPaused = false
    private var isChargingDisabled = false

    // Listeners
    private val listeners = mutableListOf<ThermalGovernorListener>()

    // Throttle callbacks
    var onReduceBitrate: ((reductionPercent: Int) -> Unit)? = null
    var onReduceResolution: (() -> Unit)? = null
    var onReduceFramerate: (() -> Unit)? = null
    var onPauseStreaming: (() -> Unit)? = null
    var onResumeStreaming: (() -> Unit)? = null
    var onRestoreSettings: (() -> Unit)? = null

    /**
     * Initialize governor with components
     */
    fun initialize(
        thermalMonitor: ThermalMonitor,
        thermalHistory: ThermalHistory,
        batteryBypassManager: BatteryBypass? = null
    ) {
        this.monitor = thermalMonitor
        this.history = thermalHistory
        this.batteryBypass = batteryBypassManager

        monitor.addListener(this)
        history.setMonitor(monitor)
    }

    /**
     * Start the thermal governor
     */
    fun start() {
        if (governorJob?.isActive == true) {
            Timber.tag(TAG).d("Governor already running")
            return
        }

        Timber.tag(TAG).i("Starting thermal governor")

        // Start monitoring
        monitor.start()
        history.startRecording()

        // Start governor loop
        governorJob = scope.launch {
            while (isActive) {
                try {
                    evaluateThermalState()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error in governor loop")
                }
                delay(config.monitorIntervalMs)
            }
        }
    }

    /**
     * Stop the thermal governor
     */
    fun stop() {
        Timber.tag(TAG).i("Stopping thermal governor")

        governorJob?.cancel()
        governorJob = null

        monitor.stop()
        history.stopRecording()

        // Restore all settings
        restoreAllSettings()
    }

    /**
     * Release resources
     */
    fun release() {
        stop()
        monitor.removeListener(this)
        monitor.release()
        history.release()
        batteryBypass?.release()
        scope.cancel()
        listeners.clear()
    }

    /**
     * Add governor listener
     */
    fun addListener(listener: ThermalGovernorListener) {
        if (listener !in listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Remove governor listener
     */
    fun removeListener(listener: ThermalGovernorListener) {
        listeners.remove(listener)
    }

    /**
     * Get thermal history
     */
    fun getHistory(): ThermalHistory = history

    /**
     * Get current stats
     */
    fun getStats(): ThermalStats = history.getStats()

    // ==================== ThermalMonitorListener ====================

    override fun onTemperatureUpdate(cpu: Float, battery: Float, gpu: Float) {
        // State evaluation happens in governor loop
    }

    override fun onBatteryStateChanged(percent: Int, isCharging: Boolean) {
        updateStatus()
    }

    override fun onThermalStatusChanged(status: Int) {
        Timber.tag(TAG).d("Android thermal status: $status")
    }

    // ==================== Evaluation ====================

    /**
     * Evaluate current thermal state and apply actions
     */
    private suspend fun evaluateThermalState() {
        val cpuTemp = monitor.cpuTemperature.value
        val batteryTemp = monitor.batteryTemperature.value

        // Evaluate CPU thermal level
        val newCpuLevel = evaluateCpuLevel(cpuTemp)
        if (newCpuLevel != _cpuLevel.value) {
            handleCpuLevelChange(_cpuLevel.value, newCpuLevel, cpuTemp)
            _cpuLevel.value = newCpuLevel
        }

        // Evaluate battery thermal level
        val newBatteryLevel = evaluateBatteryLevel(batteryTemp)
        if (newBatteryLevel != _batteryLevel.value) {
            handleBatteryLevelChange(_batteryLevel.value, newBatteryLevel, batteryTemp)
            _batteryLevel.value = newBatteryLevel
        }

        updateStatus()
    }

    /**
     * Evaluate CPU thermal level with hysteresis
     */
    private fun evaluateCpuLevel(tempC: Float): ThermalLevel {
        val thresholds = config.cpuConfig.getThresholds()
        val currentLevel = _cpuLevel.value

        // Check for escalation (going up)
        for (threshold in thresholds.sortedByDescending { it.level.ordinal }) {
            if (tempC >= threshold.temperatureCelsius) {
                return threshold.level
            }
        }

        // Check for de-escalation (going down) - requires hysteresis
        if (currentLevel != ThermalLevel.NORMAL) {
            val currentThreshold = thresholds.find { it.level == currentLevel }
            if (currentThreshold != null) {
                val cooldownTemp = currentThreshold.temperatureCelsius - currentThreshold.hysteresisDegreesC
                if (tempC < cooldownTemp) {
                    // Find next lower level
                    return thresholds
                        .filter { it.level.ordinal < currentLevel.ordinal }
                        .maxByOrNull { it.level.ordinal }?.level ?: ThermalLevel.NORMAL
                }
                // Still in current level due to hysteresis
                return currentLevel
            }
        }

        return ThermalLevel.NORMAL
    }

    /**
     * Evaluate battery thermal level with hysteresis
     */
    private fun evaluateBatteryLevel(tempC: Float): ThermalLevel {
        val thresholds = config.batteryConfig.getThresholds()
        val currentLevel = _batteryLevel.value

        // Check for escalation
        for (threshold in thresholds.sortedByDescending { it.level.ordinal }) {
            if (tempC >= threshold.temperatureCelsius) {
                return threshold.level
            }
        }

        // Check for de-escalation with hysteresis
        if (currentLevel != ThermalLevel.NORMAL) {
            val currentThreshold = thresholds.find { it.level == currentLevel }
            if (currentThreshold != null) {
                val cooldownTemp = currentThreshold.temperatureCelsius - currentThreshold.hysteresisDegreesC
                if (tempC < cooldownTemp) {
                    return thresholds
                        .filter { it.level.ordinal < currentLevel.ordinal }
                        .maxByOrNull { it.level.ordinal }?.level ?: ThermalLevel.NORMAL
                }
                return currentLevel
            }
        }

        return ThermalLevel.NORMAL
    }

    // ==================== Action Handlers ====================

    /**
     * Handle CPU level change
     */
    private suspend fun handleCpuLevelChange(
        oldLevel: ThermalLevel,
        newLevel: ThermalLevel,
        tempC: Float
    ) {
        Timber.tag(TAG).i("CPU level: $oldLevel -> $newLevel @ ${tempC}°C")

        val actions = mutableListOf<ThrottleAction>()

        if (newLevel.ordinal > oldLevel.ordinal) {
            // Escalating - apply throttling
            when (newLevel) {
                ThermalLevel.WARNING -> {
                    applyBitrateReduction(config.bitrateReduction.warningReductionPercent)
                    actions.add(ThrottleAction.REDUCE_BITRATE)
                }
                ThermalLevel.CRITICAL -> {
                    applyBitrateReduction(config.bitrateReduction.criticalReductionPercent)
                    applyResolutionReduction()
                    applyFramerateReduction()
                    actions.add(ThrottleAction.REDUCE_BITRATE)
                    actions.add(ThrottleAction.REDUCE_RESOLUTION)
                    actions.add(ThrottleAction.REDUCE_FRAMERATE)
                }
                ThermalLevel.EMERGENCY -> {
                    pauseStreaming("CPU temperature critical: ${tempC}°C")
                    actions.add(ThrottleAction.PAUSE_STREAMING)
                    actions.add(ThrottleAction.ALERT_USER)
                }
                else -> {}
            }
        } else {
            // De-escalating - restore settings
            when (newLevel) {
                ThermalLevel.NORMAL -> {
                    restoreAllSettings()
                }
                ThermalLevel.ELEVATED -> {
                    if (isStreamingPaused) {
                        resumeStreaming()
                    }
                    restoreResolution()
                    restoreFramerate()
                    restoreBitrate()
                }
                ThermalLevel.WARNING -> {
                    if (isStreamingPaused) {
                        resumeStreaming()
                    }
                    restoreResolution()
                    restoreFramerate()
                    applyBitrateReduction(config.bitrateReduction.warningReductionPercent)
                }
                ThermalLevel.CRITICAL -> {
                    if (isStreamingPaused) {
                        resumeStreaming()
                    }
                }
                else -> {}
            }
        }

        // Record event
        history.recordEvent(ThermalSource.CPU, oldLevel, newLevel, tempC, actions)

        // Notify listeners
        val status = _status.value
        listeners.forEach { it.onThermalLevelChanged(status) }
    }

    /**
     * Handle battery level change
     */
    private suspend fun handleBatteryLevelChange(
        oldLevel: ThermalLevel,
        newLevel: ThermalLevel,
        tempC: Float
    ) {
        Timber.tag(TAG).i("Battery level: $oldLevel -> $newLevel @ ${tempC}°C")

        val actions = mutableListOf<ThrottleAction>()

        if (newLevel.ordinal > oldLevel.ordinal) {
            // Escalating
            when (newLevel) {
                ThermalLevel.WARNING -> {
                    disableCharging("Battery temperature elevated: ${tempC}°C")
                    actions.add(ThrottleAction.DISABLE_CHARGING)
                }
                ThermalLevel.CRITICAL -> {
                    disableCharging("Battery temperature high: ${tempC}°C")
                    actions.add(ThrottleAction.DISABLE_CHARGING)
                    actions.add(ThrottleAction.ALERT_USER)
                }
                ThermalLevel.EMERGENCY -> {
                    disableCharging("Battery temperature critical: ${tempC}°C")
                    pauseStreaming("Battery temperature critical: ${tempC}°C")
                    actions.add(ThrottleAction.DISABLE_CHARGING)
                    actions.add(ThrottleAction.PAUSE_STREAMING)
                    actions.add(ThrottleAction.ALERT_USER)
                }
                else -> {}
            }
        } else {
            // De-escalating
            when (newLevel) {
                ThermalLevel.NORMAL, ThermalLevel.ELEVATED -> {
                    enableCharging()
                    if (isStreamingPaused && _cpuLevel.value != ThermalLevel.EMERGENCY) {
                        resumeStreaming()
                    }
                }
                ThermalLevel.WARNING -> {
                    // Keep charging disabled
                    if (isStreamingPaused && _cpuLevel.value != ThermalLevel.EMERGENCY) {
                        resumeStreaming()
                    }
                }
                else -> {}
            }
        }

        // Record event
        history.recordEvent(ThermalSource.BATTERY, oldLevel, newLevel, tempC, actions)

        // Notify listeners
        val status = _status.value
        listeners.forEach { it.onThermalLevelChanged(status) }
    }

    // ==================== Throttle Actions ====================

    private fun applyBitrateReduction(percent: Int) {
        if (percent > currentBitrateReduction) {
            currentBitrateReduction = percent
            activeActions.add(ThrottleAction.REDUCE_BITRATE)
            onReduceBitrate?.invoke(percent)
            listeners.forEach { it.onThrottleAction(ThrottleAction.REDUCE_BITRATE, true) }
            Timber.tag(TAG).d("Applied bitrate reduction: $percent%")
        }
    }

    private fun restoreBitrate() {
        if (currentBitrateReduction > 0) {
            currentBitrateReduction = 0
            activeActions.remove(ThrottleAction.REDUCE_BITRATE)
            onReduceBitrate?.invoke(0)
            listeners.forEach { it.onThrottleAction(ThrottleAction.REDUCE_BITRATE, false) }
            Timber.tag(TAG).d("Restored bitrate")
        }
    }

    private fun applyResolutionReduction() {
        if (!isResolutionReduced) {
            isResolutionReduced = true
            activeActions.add(ThrottleAction.REDUCE_RESOLUTION)
            onReduceResolution?.invoke()
            listeners.forEach { it.onThrottleAction(ThrottleAction.REDUCE_RESOLUTION, true) }
            Timber.tag(TAG).d("Applied resolution reduction")
        }
    }

    private fun restoreResolution() {
        if (isResolutionReduced) {
            isResolutionReduced = false
            activeActions.remove(ThrottleAction.REDUCE_RESOLUTION)
            listeners.forEach { it.onThrottleAction(ThrottleAction.REDUCE_RESOLUTION, false) }
            Timber.tag(TAG).d("Restored resolution")
        }
    }

    private fun applyFramerateReduction() {
        if (!isFramerateReduced) {
            isFramerateReduced = true
            activeActions.add(ThrottleAction.REDUCE_FRAMERATE)
            onReduceFramerate?.invoke()
            listeners.forEach { it.onThrottleAction(ThrottleAction.REDUCE_FRAMERATE, true) }
            Timber.tag(TAG).d("Applied framerate reduction")
        }
    }

    private fun restoreFramerate() {
        if (isFramerateReduced) {
            isFramerateReduced = false
            activeActions.remove(ThrottleAction.REDUCE_FRAMERATE)
            listeners.forEach { it.onThrottleAction(ThrottleAction.REDUCE_FRAMERATE, false) }
            Timber.tag(TAG).d("Restored framerate")
        }
    }

    private fun pauseStreaming(reason: String) {
        if (!isStreamingPaused) {
            isStreamingPaused = true
            activeActions.add(ThrottleAction.PAUSE_STREAMING)
            onPauseStreaming?.invoke()
            listeners.forEach {
                it.onThrottleAction(ThrottleAction.PAUSE_STREAMING, true)
                it.onEmergencyShutdown(reason)
            }
            Timber.tag(TAG).w("Paused streaming: $reason")
        }
    }

    private fun resumeStreaming() {
        if (isStreamingPaused) {
            isStreamingPaused = false
            activeActions.remove(ThrottleAction.PAUSE_STREAMING)
            onResumeStreaming?.invoke()
            listeners.forEach { it.onThrottleAction(ThrottleAction.PAUSE_STREAMING, false) }
            Timber.tag(TAG).i("Resumed streaming")
        }
    }

    private fun disableCharging(reason: String) {
        if (!isChargingDisabled) {
            isChargingDisabled = true
            activeActions.add(ThrottleAction.DISABLE_CHARGING)
            batteryBypass?.disableCharging()
            listeners.forEach { it.onThrottleAction(ThrottleAction.DISABLE_CHARGING, true) }
            Timber.tag(TAG).d("Disabled charging: $reason")
        }
    }

    private fun enableCharging() {
        if (isChargingDisabled) {
            isChargingDisabled = false
            activeActions.remove(ThrottleAction.DISABLE_CHARGING)
            batteryBypass?.enableCharging()
            listeners.forEach { it.onThrottleAction(ThrottleAction.DISABLE_CHARGING, false) }
            Timber.tag(TAG).d("Enabled charging")
        }
    }

    private fun restoreAllSettings() {
        restoreBitrate()
        restoreResolution()
        restoreFramerate()
        enableCharging()
        if (isStreamingPaused) {
            resumeStreaming()
        }
        onRestoreSettings?.invoke()
        Timber.tag(TAG).i("All settings restored")
    }

    // ==================== Status ====================

    private fun updateStatus() {
        val cpuTemp = monitor.cpuTemperature.value
        val batteryTemp = monitor.batteryTemperature.value
        val batteryInfo = monitor.getBatteryInfo()

        val overallLevel = maxOf(_cpuLevel.value, _batteryLevel.value)

        _status.value = ThermalStatus(
            cpuTemperatureC = cpuTemp,
            batteryTemperatureC = batteryTemp,
            cpuLevel = _cpuLevel.value,
            batteryLevel = _batteryLevel.value,
            overallLevel = overallLevel,
            activeActions = activeActions.toSet(),
            batteryPercent = batteryInfo.percent,
            isCharging = batteryInfo.isCharging,
            chargingDisabled = isChargingDisabled,
            bitrateReductionPercent = currentBitrateReduction,
            resolutionReduced = isResolutionReduced,
            framerateReduced = isFramerateReduced
        )
    }
}
