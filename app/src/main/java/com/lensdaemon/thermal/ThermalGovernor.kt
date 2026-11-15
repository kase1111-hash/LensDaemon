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
import java.util.Locale

/**
 * Thermal governor: turns CPU and battery temperatures into throttling.
 *
 * Every [ThermalConfig.monitorIntervalMs] it works out a level for the CPU and one for
 * the battery, derives the wanted throttling from both ([throttlePlanFor]) and applies
 * only what changed. With the default (generic profile) thresholds:
 * - CPU >= 45°C, ELEVATED: no action
 * - CPU >= 50°C, WARNING: bitrate -20%
 * - CPU >= 55°C, CRITICAL: bitrate -40%, resolution one step down, frame rate at most 24 fps
 * - CPU >= 60°C, EMERGENCY: outputs paused until it cools
 * - Battery >= 42°C, WARNING (and 45°C, CRITICAL): charging held, where the phone lets
 *   the app switch charging off (elsewhere the battery bypass only advises unplugging)
 * - Battery >= 48°C, EMERGENCY: charging held and outputs paused until it cools
 *
 * The thresholds come from the device's thermal profile, or a saved override, applied in
 * [initialize]. A level is left only once the temperature is that level's hysteresis below
 * its threshold (3°C for the CPU, 2°C for the battery by default), so readings around a
 * threshold don't flip the throttling on every poll. ALERT_USER only marks events in the
 * history log when a level rises to CRITICAL or EMERGENCY; no separate alert is posted.
 *
 * Callbacks and listeners are called on the main thread.
 */
class ThermalGovernor(
    private val context: Context,
    initialConfig: ThermalConfig = ThermalConfig.DEFAULT
) : ThermalMonitor.ThermalMonitorListener {

    companion object {
        private const val TAG = "ThermalGovernor"
    }

    // Written by PUT/DELETE /api/thermal/profile on HTTP threads, read by the main-thread loop
    @Volatile
    private var config: ThermalConfig = initialConfig

    // Profile manager for device-specific tuning
    val profileManager = ThermalProfileManager(context)

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

    // Throttling in force (main thread only)
    private val activeActions = mutableSetOf<ThrottleAction>()
    private var currentBitrateReduction = 0
    private var isResolutionReduced = false
    private var isFramerateReduced = false
    private var isStreamingPaused = false
    private var isChargingDisabled = false

    // Listeners
    private val listeners = mutableListOf<ThermalGovernorListener>()

    /** The wanted bitrate cut in percent, whenever it changes (0 when cooled down). */
    var onReduceBitrate: ((reductionPercent: Int) -> Unit)? = null

    /** The wanted encoder limits from CPU CRITICAL, on every change in either direction. */
    var onEncoderLimits: ((reduceResolution: Boolean, reduceFramerate: Boolean) -> Unit)? = null

    /** The CPU or the battery reached EMERGENCY: stop the outputs. */
    var onPauseStreaming: (() -> Unit)? = null

    /** Neither the CPU nor the battery is at EMERGENCY any more: restart the paused outputs. */
    var onResumeStreaming: (() -> Unit)? = null

    /** Called by [stop] once every throttle has been lifted. */
    var onRestoreSettings: (() -> Unit)? = null

    /**
     * Initialize governor with components, and apply the device's thermal profile
     * (or the saved override) so its thresholds are in force from the start.
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
        // History records the governor's levels, so its entries match the throttling
        history.levelSource = { _cpuLevel.value to _batteryLevel.value }

        applyActiveProfile()
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
     * Stop the thermal governor and lift every throttle
     */
    fun stop() {
        Timber.tag(TAG).i("Stopping thermal governor")

        governorJob?.cancel()
        governorJob = null

        monitor.stop()
        history.stopRecording()

        applyPlan(ThrottlePlan())
        onRestoreSettings?.invoke()
        updateStatus()
        Timber.tag(TAG).i("All settings restored")
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
     * Apply the active thermal profile to the governor config. Runs at the end of
     * [initialize] and whenever the profile changes (PUT/DELETE /api/thermal/profile).
     */
    @Synchronized
    fun applyActiveProfile() {
        val profile = profileManager.getActiveProfile()
        config = profile.toThermalConfig(config)
        Timber.tag(TAG).i("Applied thermal profile: ${profile.displayName}")
    }

    /**
     * Get the current config (useful for API responses)
     */
    fun getConfig(): ThermalConfig = config

    /**
     * The battery bypass (charge limiting), or null when the service runs without one.
     */
    fun getBatteryBypass(): BatteryBypass? = batteryBypass

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
     * Work out both levels, apply the throttling they call for, publish the status,
     * then log the level changes and tell the listeners (who see the new status).
     */
    private suspend fun evaluateThermalState() {
        val cfg = config
        val cpuTemp = monitor.cpuTemperature.value
        val batteryTemp = monitor.batteryTemperature.value

        val oldCpu = _cpuLevel.value
        val oldBattery = _batteryLevel.value
        val newCpu = levelWithHysteresis(cpuTemp, oldCpu, cfg.cpuConfig.getThresholds())
        val newBattery = levelWithHysteresis(batteryTemp, oldBattery, cfg.batteryConfig.getThresholds())
        _cpuLevel.value = newCpu
        _batteryLevel.value = newBattery

        val plan = throttlePlanFor(newCpu, newBattery, cfg)
        applyPlan(plan)
        updateStatus()

        if (newCpu != oldCpu) {
            recordLevelChange(ThermalSource.CPU, oldCpu, newCpu, cpuTemp, plan)
        }
        if (newBattery != oldBattery) {
            recordLevelChange(ThermalSource.BATTERY, oldBattery, newBattery, batteryTemp, plan)
        }
        if (newCpu != oldCpu || newBattery != oldBattery) {
            val status = _status.value
            listeners.forEach { it.onThermalLevelChanged(status) }
        }
    }

    /**
     * Log a level change in the event history, with the actions in force after it and
     * ALERT_USER when the level rose to CRITICAL or EMERGENCY.
     */
    private suspend fun recordLevelChange(
        source: ThermalSource,
        oldLevel: ThermalLevel,
        newLevel: ThermalLevel,
        tempC: Float,
        plan: ThrottlePlan
    ) {
        Timber.tag(TAG).i("$source level: $oldLevel -> $newLevel @ ${tempC}°C")
        val actions = plan.actions().toMutableList()
        if (newLevel > oldLevel && newLevel >= ThermalLevel.CRITICAL) {
            actions.add(ThrottleAction.ALERT_USER)
        }
        history.recordEvent(source, oldLevel, newLevel, tempC, actions)
    }

    // ==================== Throttle Actions ====================

    /**
     * Bring the throttling in line with [plan], acting only on what changed. A pause comes
     * before the encoder changes and a resume after them, so outputs aren't restarted just
     * to be stopped, and resumed outputs start with the limits already in place.
     */
    private fun applyPlan(plan: ThrottlePlan) {
        if (plan.pauseStreaming) setStreamingPaused(true)
        setBitrateReduction(plan.bitrateReductionPercent)
        setEncoderLimits(plan.reduceResolution, plan.reduceFramerate)
        setChargingHeld(plan.holdCharging)
        if (!plan.pauseStreaming) setStreamingPaused(false)
    }

    private fun setBitrateReduction(percent: Int) {
        if (percent == currentBitrateReduction) return
        currentBitrateReduction = percent
        setActionActive(ThrottleAction.REDUCE_BITRATE, percent > 0)
        onReduceBitrate?.invoke(percent)
        Timber.tag(TAG).d("Bitrate reduction: $percent%")
    }

    private fun setEncoderLimits(reduceResolution: Boolean, reduceFramerate: Boolean) {
        if (reduceResolution == isResolutionReduced && reduceFramerate == isFramerateReduced) return
        isResolutionReduced = reduceResolution
        isFramerateReduced = reduceFramerate
        setActionActive(ThrottleAction.REDUCE_RESOLUTION, reduceResolution)
        setActionActive(ThrottleAction.REDUCE_FRAMERATE, reduceFramerate)
        onEncoderLimits?.invoke(reduceResolution, reduceFramerate)
        Timber.tag(TAG).d("Encoder limits: lower resolution $reduceResolution, frame rate cap $reduceFramerate")
    }

    private fun setStreamingPaused(paused: Boolean) {
        if (paused == isStreamingPaused) return
        isStreamingPaused = paused
        setActionActive(ThrottleAction.PAUSE_STREAMING, paused)
        if (paused) {
            val reason = pauseReason()
            onPauseStreaming?.invoke()
            listeners.forEach { it.onEmergencyShutdown(reason) }
            Timber.tag(TAG).w("Paused streaming: $reason")
        } else {
            onResumeStreaming?.invoke()
            Timber.tag(TAG).i("Resumed streaming")
        }
    }

    /**
     * Hold or release charging through the bypass's thermal hold. Releasing it never
     * cancels a manual hold set through the API.
     */
    private fun setChargingHeld(held: Boolean) {
        if (held == isChargingDisabled) return
        isChargingDisabled = held
        setActionActive(ThrottleAction.DISABLE_CHARGING, held)
        if (held) {
            batteryBypass?.disableChargingForThermal()
            Timber.tag(TAG).d("Charging held: battery too warm")
        } else {
            batteryBypass?.releaseThermalHold()
            Timber.tag(TAG).d("Charging hold released")
        }
    }

    /** Keep [activeActions] in step and tell the listeners when an action turns on or off. */
    private fun setActionActive(action: ThrottleAction, active: Boolean) {
        val changed = if (active) activeActions.add(action) else activeActions.remove(action)
        if (changed) {
            listeners.forEach { it.onThrottleAction(action, active) }
        }
    }

    private fun pauseReason(): String {
        val cpuHot = _cpuLevel.value == ThermalLevel.EMERGENCY
        val tempC = if (cpuHot) monitor.cpuTemperature.value else monitor.batteryTemperature.value
        return String.format(Locale.US, "%s temperature critical: %.1f°C", if (cpuHot) "CPU" else "Battery", tempC)
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
