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
 * What to tell the user on a phone where LensDaemon can't switch charging (the
 * software-only battery bypass), or "" when nothing needs doing.
 *
 * The battery counts as too warm while a thermal hold wants charging off ([heldForHeat])
 * or once it reaches [BatteryBypassConfig.maxBatteryTempC]. Too warm, the advice is to
 * unplug the charger or to let the battery cool before plugging it in: never to plug in,
 * however low the charge.
 */
internal fun chargingAdvice(
    heldForHeat: Boolean,
    isCharging: Boolean,
    percent: Int,
    tempC: Float,
    config: BatteryBypassConfig
): String {
    val tooWarm = heldForHeat || tempC >= config.maxBatteryTempC
    return when {
        tooWarm && isCharging ->
            "Unplug charger to reduce battery temperature (${tempC}°C)"
        tooWarm ->
            "Battery too warm to charge (${tempC}°C); let it cool before plugging in"
        tempC >= config.maxBatteryTempC - 3f && isCharging ->
            "Battery warming (${tempC}°C). Consider unplugging charger soon"
        percent >= config.targetChargePercent && isCharging ->
            "Battery at ${percent}%. Unplug charger to preserve battery health"
        !isCharging && percent <= config.resumeChargePercent ->
            "Battery low (${percent}%). Plug in charger"
        else -> ""
    }
}

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
 * recommendation, such as unplugging the charger) instead of stopping the charge. A
 * switch that rejects being turned off is given up on the same way. Software-only, the
 * charge-limit cycle (CHARGING, HOLDING) and manual holds don't run, and the status
 * never reports charging as blocked; THERMAL_HOLD is still reported, as what the battery
 * needs, with the advice to unplug.
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

    // Detected control path. hasHardwareControl turns false for good when the switch
    // rejects turning charging off; HTTP threads read it without the lock
    private var controlPath: String? = null
    private var isInverted: Boolean = false
    @Volatile
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
     * Manually disable charging (manual hold, kept until [enableCharging]).
     *
     * @return false when charging could not be switched off: the phone has no usable
     *   charging switch, or the switch rejected it (and is given up on, see
     *   [hasHardwareControl]). Nothing is held then.
     */
    @Synchronized
    fun disableCharging(): Boolean {
        if (!hasHardwareControl || !setChargingEnabled(false)) {
            updateStatus("Charging could not be switched off")
            return false
        }
        manualDisable = true
        _bypassState.value = BypassState.MANUAL_HOLD
        updateStatus("Charging manually disabled")
        return true
    }

    /**
     * Manually enable charging: ends the manual hold. Charging stays off while the
     * governor's thermal hold lasts.
     *
     * @return false when the charging switch rejected switching charging back on: it is
     *   still off, and the manual hold stays
     */
    @Synchronized
    fun enableCharging(): Boolean {
        if (thermalHold) {
            manualDisable = false
            _bypassState.value = BypassState.THERMAL_HOLD
            updateStatus("Manual hold released; charging stays off until the battery cools")
            return true
        }
        if (!setChargingEnabled(true)) {
            updateStatus("Charging could not be switched back on")
            return false
        }
        manualDisable = false
        if (_bypassState.value == BypassState.MANUAL_HOLD) {
            _bypassState.value = BypassState.DISABLED
        }
        updateStatus("Charging enabled")
        return true
    }

    /**
     * Disable charging due to thermal condition (the governor's thermal hold, kept until
     * [releaseThermalHold]). The state is THERMAL_HOLD even where charging can't be
     * switched off; the status then says charging isn't blocked and advises unplugging.
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
     * Whether LensDaemon can switch charging off on this phone: a writable charging switch
     * was found and hasn't rejected turning charging off. Once it has, this stays false.
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

        // Holding the charge at the target needs the charging switch; without one,
        // getStatus() only advises
        if (!hasHardwareControl) return

        // Normal bypass logic. The state changes only once the switch has done it
        when {
            currentPercent >= config.targetChargePercent -> {
                // At or above target - stop charging
                if (state != BypassState.HOLDING && setChargingEnabled(false)) {
                    _bypassState.value = BypassState.HOLDING
                    updateStatus("Holding at ${currentPercent}%")
                }
            }
            currentPercent <= config.resumeChargePercent -> {
                // Below resume threshold - start charging
                val canResume = state == BypassState.HOLDING || state == BypassState.DISABLED
                if (canResume && setChargingEnabled(true)) {
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
     * Switch charging on or off through the charging switch. Software-only, nothing is
     * written and only the wanted state is kept.
     *
     * When the switch rejects the write, the charging state stays as it was. If charging
     * is on (the switch refused to turn it off), the switch is given up on: the bypass is
     * software-only from then on and gives advice instead. If charging is off (it refused
     * to turn it back on), the switch is kept, so the status still says charging is blocked.
     *
     * @return false when the switch rejected the write
     */
    private fun setChargingEnabled(enabled: Boolean): Boolean {
        val path = controlPath
        if (hasHardwareControl && path != null) {
            try {
                val value = if (enabled xor isInverted) "1" else "0"
                File(path).writeText(value)
                Timber.tag(TAG).d("Set charging ${if (enabled) "enabled" else "disabled"} via $path")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "The charging switch $path rejected the change")
                if (_chargingEnabled.value) giveUpHardwareControl()
                return false
            }
        } else {
            // Software-only mode - track state and generate recommendations
            val action = if (enabled) "enabled" else "disabled"
            Timber.tag(TAG).d("Charging control (software-only): $action")
            if (!enabled && isCharging) {
                Timber.tag(TAG).i("Software-only: charging should be $action but device is still charging. User action required.")
            }
        }

        _chargingEnabled.value = enabled
        listeners.forEach { it.onChargingControlChanged(enabled) }
        return true
    }

    /**
     * The charging switch refused to turn charging off: treat the phone as software-only
     * from now on, so the API stops offering control and gives advice instead. The
     * charge-limit cycle needs the switch, so it stops too.
     */
    private fun giveUpHardwareControl() {
        hasHardwareControl = false
        val state = _bypassState.value
        if (state == BypassState.CHARGING || state == BypassState.HOLDING) {
            _bypassState.value = BypassState.DISABLED
        }
        Timber.tag(TAG).w("Charging switch $controlPath unusable: software-only from now on")
    }

    /**
     * Advice for software-only mode (none while LensDaemon controls charging itself).
     */
    private fun generateRecommendation(): String {
        if (hasHardwareControl) return ""

        val heldForHeat = thermalHold || _bypassState.value == BypassState.THERMAL_HOLD
        return chargingAdvice(heldForHeat, isCharging, currentPercent, currentTempC, config)
    }

    /**
     * Update status and notify listeners
     */
    private fun updateStatus(reason: String) {
        _status.value = currentStatus(reason)

        listeners.forEach { it.onBypassStateChanged(_status.value) }
    }

    /**
     * Current status, with the battery readings, the charging control and the
     * recommendation worked out now. Only [BatteryBypassStatus.reason] comes from the
     * stored [status], which changes only with the bypass state (and holds its defaults
     * until the bypass first starts).
     */
    @Synchronized
    fun getStatus(): BatteryBypassStatus = currentStatus(_status.value.reason)

    /**
     * The status as it is now. chargingBlocked is true only when the charging switch has
     * actually turned charging off: software-only, a wanted hold blocks nothing.
     */
    private fun currentStatus(reason: String) = BatteryBypassStatus(
        state = _bypassState.value,
        batteryPercent = currentPercent,
        batteryTempC = currentTempC,
        targetPercent = config.targetChargePercent,
        resumePercent = config.resumeChargePercent,
        isCharging = isCharging,
        chargingBlocked = hasHardwareControl && !_chargingEnabled.value,
        hasHardwareControl = hasHardwareControl,
        softwareOnly = !hasHardwareControl,
        recommendation = generateRecommendation(),
        reason = reason
    )

    /**
     * Update configuration (applied immediately on next evaluation cycle)
     */
    fun updateConfig(newConfig: BatteryBypassConfig) {
        Timber.tag(TAG).d("Config updated: $newConfig")
        config = newConfig
    }
}
