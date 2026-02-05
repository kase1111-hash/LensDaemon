package com.lensdaemon.thermal

/**
 * Thermal state severity levels
 */
enum class ThermalLevel {
    NORMAL,       // All systems nominal
    ELEVATED,     // Starting to warm up, no action needed
    WARNING,      // Approaching limits, reduce bitrate
    CRITICAL,     // High temps, reduce resolution/framerate
    EMERGENCY     // Dangerously hot, pause streaming
}

/**
 * Throttle action types
 */
enum class ThrottleAction {
    NONE,
    REDUCE_BITRATE,
    REDUCE_RESOLUTION,
    REDUCE_FRAMERATE,
    PAUSE_STREAMING,
    DISABLE_CHARGING,
    ALERT_USER
}

/**
 * Thermal source types
 */
enum class ThermalSource {
    CPU,
    BATTERY,
    GPU,
    SKIN,
    UNKNOWN
}

/**
 * Individual thermal threshold configuration
 */
data class ThermalThreshold(
    val level: ThermalLevel,
    val temperatureCelsius: Float,
    val actions: List<ThrottleAction>,
    val hysteresisDegreesC: Float = 3.0f  // Temperature must drop this much to exit state
)

/**
 * CPU thermal thresholds configuration
 */
data class CpuThermalConfig(
    val normalMaxC: Float = 45f,
    val warningThresholdC: Float = 50f,
    val criticalThresholdC: Float = 55f,
    val emergencyThresholdC: Float = 60f,
    val hysteresisC: Float = 3.0f
) {
    fun getThresholds(): List<ThermalThreshold> = listOf(
        ThermalThreshold(
            level = ThermalLevel.ELEVATED,
            temperatureCelsius = normalMaxC,
            actions = emptyList(),
            hysteresisDegreesC = hysteresisC
        ),
        ThermalThreshold(
            level = ThermalLevel.WARNING,
            temperatureCelsius = warningThresholdC,
            actions = listOf(ThrottleAction.REDUCE_BITRATE),
            hysteresisDegreesC = hysteresisC
        ),
        ThermalThreshold(
            level = ThermalLevel.CRITICAL,
            temperatureCelsius = criticalThresholdC,
            actions = listOf(ThrottleAction.REDUCE_RESOLUTION, ThrottleAction.REDUCE_FRAMERATE),
            hysteresisDegreesC = hysteresisC
        ),
        ThermalThreshold(
            level = ThermalLevel.EMERGENCY,
            temperatureCelsius = emergencyThresholdC,
            actions = listOf(ThrottleAction.PAUSE_STREAMING, ThrottleAction.ALERT_USER),
            hysteresisDegreesC = hysteresisC
        )
    )
}

/**
 * Battery thermal thresholds configuration
 */
data class BatteryThermalConfig(
    val normalMaxC: Float = 38f,
    val warningThresholdC: Float = 42f,
    val criticalThresholdC: Float = 45f,
    val emergencyThresholdC: Float = 48f,
    val hysteresisC: Float = 2.0f
) {
    fun getThresholds(): List<ThermalThreshold> = listOf(
        ThermalThreshold(
            level = ThermalLevel.ELEVATED,
            temperatureCelsius = normalMaxC,
            actions = emptyList(),
            hysteresisDegreesC = hysteresisC
        ),
        ThermalThreshold(
            level = ThermalLevel.WARNING,
            temperatureCelsius = warningThresholdC,
            actions = listOf(ThrottleAction.DISABLE_CHARGING),
            hysteresisDegreesC = hysteresisC
        ),
        ThermalThreshold(
            level = ThermalLevel.CRITICAL,
            temperatureCelsius = criticalThresholdC,
            actions = listOf(ThrottleAction.DISABLE_CHARGING, ThrottleAction.ALERT_USER),
            hysteresisDegreesC = hysteresisC
        ),
        ThermalThreshold(
            level = ThermalLevel.EMERGENCY,
            temperatureCelsius = emergencyThresholdC,
            actions = listOf(ThrottleAction.PAUSE_STREAMING, ThrottleAction.ALERT_USER),
            hysteresisDegreesC = hysteresisC
        )
    )
}

/**
 * Battery bypass (charge limiting) configuration
 */
data class BatteryBypassConfig(
    val enabled: Boolean = true,
    val targetChargePercent: Int = 50,       // Stop charging at this level
    val resumeChargePercent: Int = 40,       // Resume charging below this level
    val maxBatteryTempC: Float = 42f,        // Disable charging above this temp
    val resumeChargeTempC: Float = 38f       // Resume charging below this temp
)

/**
 * Bitrate reduction configuration
 */
data class BitrateReductionConfig(
    val warningReductionPercent: Int = 20,   // Reduce by 20% at WARNING level
    val criticalReductionPercent: Int = 40,  // Reduce by 40% at CRITICAL level
    val minBitrateKbps: Int = 500            // Never go below this
)

/**
 * Resolution reduction configuration
 */
data class ResolutionReductionConfig(
    val enable4KTo1080p: Boolean = true,     // Reduce 4K to 1080p at CRITICAL
    val enable1080pTo720p: Boolean = true    // Reduce 1080p to 720p if still critical
)

/**
 * Framerate reduction configuration
 */
data class FramerateReductionConfig(
    val reduceTo24fps: Boolean = true,       // Reduce to 24fps at CRITICAL
    val reduceTo15fps: Boolean = true        // Reduce to 15fps if still critical
)

/**
 * Complete thermal configuration
 */
data class ThermalConfig(
    val cpuConfig: CpuThermalConfig = CpuThermalConfig(),
    val batteryConfig: BatteryThermalConfig = BatteryThermalConfig(),
    val batteryBypassConfig: BatteryBypassConfig = BatteryBypassConfig(),
    val bitrateReduction: BitrateReductionConfig = BitrateReductionConfig(),
    val resolutionReduction: ResolutionReductionConfig = ResolutionReductionConfig(),
    val framerateReduction: FramerateReductionConfig = FramerateReductionConfig(),
    val monitorIntervalMs: Long = 5000,      // Poll temperature every 5 seconds
    val historyRetentionHours: Int = 24,     // Keep 24 hours of history
    val historyIntervalMs: Long = 60000      // Record history every minute
) {
    companion object {
        /** Default configuration */
        val DEFAULT = ThermalConfig()

        /** Aggressive thermal management for hot environments */
        val AGGRESSIVE = ThermalConfig(
            cpuConfig = CpuThermalConfig(
                normalMaxC = 40f,
                warningThresholdC = 45f,
                criticalThresholdC = 50f,
                emergencyThresholdC = 55f
            ),
            batteryConfig = BatteryThermalConfig(
                normalMaxC = 35f,
                warningThresholdC = 38f,
                criticalThresholdC = 42f,
                emergencyThresholdC = 45f
            ),
            batteryBypassConfig = BatteryBypassConfig(
                targetChargePercent = 40,
                resumeChargePercent = 30
            )
        )

        /** Relaxed thermal management for cooled setups */
        val RELAXED = ThermalConfig(
            cpuConfig = CpuThermalConfig(
                normalMaxC = 50f,
                warningThresholdC = 55f,
                criticalThresholdC = 60f,
                emergencyThresholdC = 65f
            ),
            batteryConfig = BatteryThermalConfig(
                normalMaxC = 42f,
                warningThresholdC = 45f,
                criticalThresholdC = 48f,
                emergencyThresholdC = 52f
            ),
            batteryBypassConfig = BatteryBypassConfig(
                targetChargePercent = 60,
                resumeChargePercent = 50
            )
        )
    }
}

/**
 * Current thermal status snapshot
 */
data class ThermalStatus(
    val cpuTemperatureC: Float = 0f,
    val batteryTemperatureC: Float = 0f,
    val cpuLevel: ThermalLevel = ThermalLevel.NORMAL,
    val batteryLevel: ThermalLevel = ThermalLevel.NORMAL,
    val overallLevel: ThermalLevel = ThermalLevel.NORMAL,
    val activeActions: Set<ThrottleAction> = emptySet(),
    val batteryPercent: Int = 0,
    val isCharging: Boolean = false,
    val chargingDisabled: Boolean = false,
    val bitrateReductionPercent: Int = 0,
    val resolutionReduced: Boolean = false,
    val framerateReduced: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    /** Human-readable status */
    val statusText: String
        get() = when (overallLevel) {
            ThermalLevel.NORMAL -> "Normal"
            ThermalLevel.ELEVATED -> "Elevated"
            ThermalLevel.WARNING -> "Warning - Throttling"
            ThermalLevel.CRITICAL -> "Critical - Performance Reduced"
            ThermalLevel.EMERGENCY -> "Emergency - Streaming Paused"
        }

    /** Is any throttling active? */
    val isThrottling: Boolean
        get() = activeActions.isNotEmpty()
}

/**
 * Temperature reading data point
 */
data class TemperatureReading(
    val source: ThermalSource,
    val temperatureC: Float,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Thermal event for history tracking
 */
data class ThermalEvent(
    val timestamp: Long,
    val source: ThermalSource,
    val oldLevel: ThermalLevel,
    val newLevel: ThermalLevel,
    val temperatureC: Float,
    val actionsTriggered: List<ThrottleAction>
)

/**
 * Thermal history entry for graphing
 */
data class ThermalHistoryEntry(
    val timestamp: Long,
    val cpuTemperatureC: Float,
    val batteryTemperatureC: Float,
    val cpuLevel: ThermalLevel,
    val batteryLevel: ThermalLevel
)

/**
 * Thermal statistics summary
 */
data class ThermalStats(
    val periodStartMs: Long,
    val periodEndMs: Long,
    val cpuTempMin: Float,
    val cpuTempMax: Float,
    val cpuTempAvg: Float,
    val batteryTempMin: Float,
    val batteryTempMax: Float,
    val batteryTempAvg: Float,
    val timeInWarningMs: Long,
    val timeInCriticalMs: Long,
    val timeInEmergencyMs: Long,
    val throttleEventCount: Int
)

/**
 * Thermal governor listener interface
 */
interface ThermalGovernorListener {
    fun onThermalLevelChanged(status: ThermalStatus)
    fun onThrottleAction(action: ThrottleAction, enabled: Boolean)
    fun onEmergencyShutdown(reason: String)
}
