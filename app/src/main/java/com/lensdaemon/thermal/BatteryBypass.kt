package com.lensdaemon.thermal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
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
import java.io.File

/**
 * Battery bypass / charge limiting controller
 *
 * Manages battery charging to prevent degradation during long-term operation:
 * - Charge to target level (e.g., 50%), then hold
 * - Disable charging when battery temperature is high
 * - Resume charging when level drops below threshold
 *
 * Charging can only be switched off on phones whose kernel exposes a charging switch
 * (a sysfs node) that the app itself may write; there is no root/su path. Most phones
 * have none: the bypass then runs software-only and gives advice ([getStatus]'s
 * recommendation, such as unplugging the charger) instead of stopping the charge.
 *
 * Two holds can block charging at once: a manual hold ([disableCharging], from the API)
 * and the governor's thermal hold ([disableChargingForThermal]). Each is lifted only by
 * its own release, so a cool-down never ends a manual hold and a manual enable never
 * ends a thermal hold. All state changes are synchronized: the HTTP threads, the main
 * thread and the bypass loop all call in.
 */
class BatteryBypass(
    private val context: Context,
    private var config: BatteryBypassConfig = BatteryBypassConfig()
) {
    companion object {
        private const val TAG = "BatteryBypass"

        // Common sysfs paths for charging control (device-specific)
        private val CHARGING_CONTROL_PATHS = listOf(
            "/sys/class/power_supply/battery/charging_enabled",
            "/sys/class/power_supply/battery/input_suspend",
            "/sys/class/power_supply/battery/charge_disable",
            "/sys/class/power_supply/battery/battery_charging_enabled",
            "/sys/class/power_supply/battery/batt_slate_mode",
            "/sys/class/power_supply/usb/usb_otg",
            "/sys/devices/platform/battery/power_supply/battery/charging_enabled"
        )

        // Some devices use opposite logic (1 = disabled)
        private val INVERTED_CONTROL_PATHS = listOf(
            "/sys/class/power_supply/battery/input_suspend",
            "/sys/class/power_supply/battery/charge_disable",
            "/sys/class/power_supply/battery/batt_slate_mode"
        )
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var bypassJob: Job? = null

    // State
    private val _bypassState = MutableStateFlow(BypassState.DISABLED)
    val bypassState: StateFlow<BypassState> = _bypassState.asStateFlow()

    private val _chargingEnabled = MutableStateFlow(true)
    val chargingEnabled: StateFlow<Boolean> = _chargingEnabled.asStateFlow()

    private val _status = MutableStateFlow(BatteryBypassStatus())
    val status: StateFlow<BatteryBypassStatus> = _status.asStateFlow()

    // Detected control path
    private var controlPath: String? = null
    private var isInverted: Boolean = false
    private var hasHardwareControl: Boolean = false

    // Battery state
    private var currentPercent: Int = 0
    private var currentTempC: Float = 0f
    private var isCharging: Boolean = false

    // Manual override (API) and the governor's thermal hold
    private var manualDisable: Boolean = false
    private var thermalHold: Boolean = false

    // Listeners
    private val listeners = mutableListOf<BatteryBypassListener>()

    // Battery receiver
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let { updateBatteryState(it) }
        }
    }

    init {
        detectHardwareControl()
    }

    /**
     * Bypass state
     */
    enum class BypassState {
        DISABLED,       // Bypass not active, normal charging
        CHARGING,       // Actively charging to target
        HOLDING,        // At target level, not charging
        THERMAL_HOLD,   // Charging disabled due to temperature
        MANUAL_HOLD     // Manually disabled
    }

    /**
     * Battery bypass status
     */
    data class BatteryBypassStatus(
        val state: BypassState = BypassState.DISABLED,
        val batteryPercent: Int = 0,
        val batteryTempC: Float = 0f,
        val targetPercent: Int = 50,
        val resumePercent: Int = 40,
        val isCharging: Boolean = false,
        val chargingBlocked: Boolean = false,
        val hasHardwareControl: Boolean = false,
        val softwareOnly: Boolean = false,
        val recommendation: String = "",
        val reason: String = ""
    )

    /**
     * Listener interface
     */
    interface BatteryBypassListener {
        fun onBypassStateChanged(status: BatteryBypassStatus)
        fun onChargingControlChanged(enabled: Boolean)
    }

    /**
     * Detect if hardware charging control is available
     */
    private fun detectHardwareControl() {
        for (path in CHARGING_CONTROL_PATHS) {
            val file = File(path)
            if (file.exists() && file.canWrite()) {
                controlPath = path
                isInverted = path in INVERTED_CONTROL_PATHS
                hasHardwareControl = true
                Timber.tag(TAG).i("Found charging control: $path (inverted: $isInverted)")
                return
            } else if (file.exists() && file.canRead()) {
                // Can read but not write - need root
                Timber.tag(TAG).d("Charging control exists but not writable: $path")
            }
        }

        Timber.tag(TAG).i("No hardware charging control available")
        hasHardwareControl = false
    }

    /**
     * Start battery bypass management
     */
    fun start() {
        if (bypassJob?.isActive == true) {
            return
        }

        if (!config.enabled) {
            Timber.tag(TAG).d("Battery bypass disabled in config")
            return
        }

        Timber.tag(TAG).i("Starting battery bypass manager")

        // Register battery receiver
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = context.registerReceiver(batteryReceiver, filter)
        intent?.let { updateBatteryState(it) }

        // Start bypass logic
        bypassJob = scope.launch {
            while (isActive) {
                try {
                    evaluateBypassState()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error in bypass loop")
                }
                delay(5000) // Check every 5 seconds
            }
        }

        updateStatus("Bypass manager started")
    }

    /**
     * Stop battery bypass management. Charging is switched back on, whatever held it.
     */
    @Synchronized
    fun stop() {
        Timber.tag(TAG).i("Stopping battery bypass manager")

        bypassJob?.cancel()
        bypassJob = null

        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Ignore if not registered
        }

        // Ensure charging is enabled when stopping
        manualDisable = false
        thermalHold = false
        setChargingEnabled(true)

        _bypassState.value = BypassState.DISABLED
        updateStatus("Bypass manager stopped")
    }

    /**
     * Release resources
     */
    fun release() {
        stop()
        scope.cancel()
        listeners.clear()
    }

    /**
     * Add listener
     */
    fun addListener(listener: BatteryBypassListener) {
        if (listener !in listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Remove listener
     */
    fun removeListener(listener: BatteryBypassListener) {
        listeners.remove(listener)
    }

    /**
     * Manually disable charging (manual hold, kept until [enableCharging])
     */
    @Synchronized
    fun disableCharging() {
        manualDisable = true
        setChargingEnabled(false)
        _bypassState.value = BypassState.MANUAL_HOLD
        updateStatus("Charging manually disabled")
    }

    /**
     * Manually enable charging: ends the manual hold. Charging stays off while the
     * governor's thermal hold lasts.
     */
    @Synchronized
    fun enableCharging() {
        manualDisable = false
        if (thermalHold) {
            _bypassState.value = BypassState.THERMAL_HOLD
            updateStatus("Manual hold released; charging stays off until the battery cools")
            return
        }
        setChargingEnabled(true)
        if (_bypassState.value == BypassState.MANUAL_HOLD) {
            _bypassState.value = BypassState.DISABLED
        }
        updateStatus("Charging enabled")
    }

    /**
     * Disable charging due to thermal condition (the governor's thermal hold, kept until
     * [releaseThermalHold])
     */
    @Synchronized
    fun disableChargingForThermal() {
        thermalHold = true
        setChargingEnabled(false)
        _bypassState.value = BypassState.THERMAL_HOLD
        updateStatus("Charging disabled (thermal)")
    }

    /**
     * End the governor's thermal hold. A manual hold set through the API stays in force.
     */
    @Synchronized
    fun releaseThermalHold() {
        if (!thermalHold) return
        thermalHold = false
        if (manualDisable) {
            _bypassState.value = BypassState.MANUAL_HOLD
            updateStatus("Thermal hold released; manual hold stays")
            return
        }
        setChargingEnabled(true)
        _bypassState.value = BypassState.DISABLED
        updateStatus("Thermal hold released")
    }

    /**
     * Check if bypass is holding charge
     */
    fun isHolding(): Boolean {
        return _bypassState.value in listOf(
            BypassState.HOLDING,
            BypassState.THERMAL_HOLD,
            BypassState.MANUAL_HOLD
        )
    }

    /**
     * Check if hardware control is available
     */
    fun hasHardwareControl(): Boolean = hasHardwareControl

    /**
     * Update battery state from intent
     */
    @Synchronized
    private fun updateBatteryState(intent: Intent) {
        // Temperature
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        currentTempC = tempTenths / 10f

        // Level
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        currentPercent = (level * 100) / scale

        // Charging status
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * Evaluate bypass state and take action
     */
    @Synchronized
    private fun evaluateBypassState() {
        if (manualDisable || thermalHold) {
            return // Don't override a manual or thermal hold
        }

        val state = _bypassState.value

        // Check for thermal override
        if (currentTempC >= config.maxBatteryTempC) {
            if (state != BypassState.THERMAL_HOLD) {
                setChargingEnabled(false)
                _bypassState.value = BypassState.THERMAL_HOLD
                updateStatus("Charging disabled (battery temp: ${currentTempC}°C)")
            }
            return
        }

        // If in thermal hold, check if we can resume
        if (state == BypassState.THERMAL_HOLD && currentTempC < config.resumeChargeTempC) {
            _bypassState.value = BypassState.DISABLED
            // Fall through to normal evaluation
        }

        // Normal bypass logic
        when {
            currentPercent >= config.targetChargePercent -> {
                // At or above target - stop charging
                if (state != BypassState.HOLDING) {
                    setChargingEnabled(false)
                    _bypassState.value = BypassState.HOLDING
                    updateStatus("Holding at ${currentPercent}%")
                }
            }
            currentPercent <= config.resumeChargePercent -> {
                // Below resume threshold - start charging
                if (state == BypassState.HOLDING || state == BypassState.DISABLED) {
                    setChargingEnabled(true)
                    _bypassState.value = BypassState.CHARGING
                    updateStatus("Charging from ${currentPercent}%")
                }
            }
            state == BypassState.CHARGING && currentPercent < config.targetChargePercent -> {
                // Still charging to target
                updateStatus("Charging: ${currentPercent}%")
            }
            state == BypassState.HOLDING -> {
                // In holding range, stay holding
                updateStatus("Holding at ${currentPercent}%")
            }
        }
    }

    /**
     * Set charging enabled/disabled
     */
    private fun setChargingEnabled(enabled: Boolean) {
        _chargingEnabled.value = enabled

        if (hasHardwareControl && controlPath != null) {
            try {
                val value = if (enabled xor isInverted) "1" else "0"
                File(controlPath!!).writeText(value)
                Timber.tag(TAG).d("Set charging ${if (enabled) "enabled" else "disabled"} via $controlPath")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to control charging via sysfs")
            }
        } else {
            // Software-only mode - track state and generate recommendations
            val action = if (enabled) "enabled" else "disabled"
            Timber.tag(TAG).d("Charging control (software-only): $action")
            if (!enabled && isCharging) {
                Timber.tag(TAG).i("Software-only: charging should be $action but device is still charging. User action required.")
            }
        }

        listeners.forEach { it.onChargingControlChanged(enabled) }
    }

    /**
     * Generate recommendation based on current state for software-only mode
     */
    private fun generateRecommendation(): String {
        if (hasHardwareControl) return ""

        val state = _bypassState.value
        return when {
            state == BypassState.THERMAL_HOLD && isCharging ->
                "Unplug charger to reduce battery temperature (${currentTempC}°C)"
            state == BypassState.HOLDING && isCharging ->
                "Unplug charger — battery at ${currentPercent}% (target: ${config.targetChargePercent}%)"
            currentTempC >= config.maxBatteryTempC - 3f && isCharging ->
                "Battery warming (${currentTempC}°C). Consider unplugging charger soon"
            currentPercent >= config.targetChargePercent && isCharging ->
                "Battery at ${currentPercent}%. Unplug charger to preserve battery health"
            !isCharging && currentPercent <= config.resumeChargePercent ->
                "Battery low (${currentPercent}%). Plug in charger"
            else -> ""
        }
    }

    /**
     * Update status and notify listeners
     */
    private fun updateStatus(reason: String) {
        _status.value = BatteryBypassStatus(
            state = _bypassState.value,
            batteryPercent = currentPercent,
            batteryTempC = currentTempC,
            targetPercent = config.targetChargePercent,
            resumePercent = config.resumeChargePercent,
            isCharging = isCharging,
            chargingBlocked = !_chargingEnabled.value,
            hasHardwareControl = hasHardwareControl,
            softwareOnly = !hasHardwareControl,
            recommendation = generateRecommendation(),
            reason = reason
        )

        listeners.forEach { it.onBypassStateChanged(_status.value) }
    }

    /**
     * Current status, with the battery readings and the recommendation worked out now
     * (the stored status changes only with the bypass state)
     */
    @Synchronized
    fun getStatus(): BatteryBypassStatus = _status.value.copy(
        state = _bypassState.value,
        batteryPercent = currentPercent,
        batteryTempC = currentTempC,
        targetPercent = config.targetChargePercent,
        resumePercent = config.resumeChargePercent,
        isCharging = isCharging,
        chargingBlocked = !_chargingEnabled.value,
        recommendation = generateRecommendation()
    )

    /**
     * Update configuration (applied immediately on next evaluation cycle)
     */
    fun updateConfig(newConfig: BatteryBypassConfig) {
        Timber.tag(TAG).d("Config updated: $newConfig")
        config = newConfig
    }
}
