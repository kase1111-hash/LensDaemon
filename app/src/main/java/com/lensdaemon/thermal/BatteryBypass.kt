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
 * Note: Full charge control requires root or kernel-level access on most devices.
 * This implementation provides:
 * - Monitoring and recommendations
 * - Sysfs-based control on supported devices (root required)
 * - Kernel-level charging control path detection
 */
class BatteryBypass(
    private val context: Context,
    private val config: BatteryBypassConfig = BatteryBypassConfig()
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

    // Manual override
    private var manualDisable: Boolean = false

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
     * Stop battery bypass management
     */
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
        enableCharging()

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
     * Manually disable charging
     */
    fun disableCharging() {
        manualDisable = true
        setChargingEnabled(false)
        _bypassState.value = BypassState.MANUAL_HOLD
        updateStatus("Charging manually disabled")
    }

    /**
     * Manually enable charging
     */
    fun enableCharging() {
        manualDisable = false
        setChargingEnabled(true)
        if (_bypassState.value == BypassState.MANUAL_HOLD) {
            _bypassState.value = BypassState.DISABLED
        }
        updateStatus("Charging enabled")
    }

    /**
     * Disable charging due to thermal condition
     */
    fun disableChargingForThermal() {
        setChargingEnabled(false)
        _bypassState.value = BypassState.THERMAL_HOLD
        updateStatus("Charging disabled (thermal)")
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
    private fun evaluateBypassState() {
        if (manualDisable) {
            return // Don't override manual disable
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
            // Software-only mode - just track state and notify
            Timber.tag(TAG).d("Charging control (software): ${if (enabled) "enabled" else "disabled"}")
        }

        listeners.forEach { it.onChargingControlChanged(enabled) }
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
            reason = reason
        )

        listeners.forEach { it.onBypassStateChanged(_status.value) }
    }

    /**
     * Get current status
     */
    fun getStatus(): BatteryBypassStatus = _status.value

    /**
     * Update configuration
     */
    fun updateConfig(newConfig: BatteryBypassConfig) {
        // Note: Would need to restart to apply new config
        Timber.tag(TAG).d("Config update requested: $newConfig")
    }
}
