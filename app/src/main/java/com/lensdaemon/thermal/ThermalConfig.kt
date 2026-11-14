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

    /**
     * Marks an event in the thermal history when a level rises to CRITICAL or EMERGENCY.
     * Only a log marker: no separate alert or notification is posted.
     */
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
 * Resolution reduction configuration.
 *
 * At CPU CRITICAL the encoder is asked for one resolution step down (2160p to 1080p,
 * 1080p to 720p, aspect ratio kept). The step is requested while either switch is on;
 * with both off the resolution is left alone.
 */
data class ResolutionReductionConfig(
    val enable4KTo1080p: Boolean = true,
    val enable1080pTo720p: Boolean = true
)

/**
 * Framerate reduction configuration.
 *
 * [reduceTo24fps]: at CPU CRITICAL the encoder frame rate is capped at 24 fps.
 * [reduceTo15fps]: not applied; there is no second step below 24 fps.
 */
data class FramerateReductionConfig(
    val reduceTo24fps: Boolean = true,
    val reduceTo15fps: Boolean = true
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
 * Thermal governor listener interface. The governor calls it on the main thread.
 */
interface ThermalGovernorListener {
    /** A CPU or battery level changed; [status] already reflects the change. */
    fun onThermalLevelChanged(status: ThermalStatus)

    /** A throttle action was switched on or off. */
    fun onThrottleAction(action: ThrottleAction, enabled: Boolean)

    /** Outputs were paused because the CPU or the battery reached EMERGENCY. */
    fun onEmergencyShutdown(reason: String)
}

/**
 * The level for a temperature, given the level its source is at now.
 *
 * Levels rise as soon as the temperature reaches their threshold. A level is left only
 * once the temperature is that level's hysteresis below its threshold, so a reading that
 * hovers around a threshold doesn't flip the level (and the throttling) on every poll.
 * A fast drop goes straight to the highest level the temperature still holds: with the
 * default CPU thresholds, 61°C then 50°C gives EMERGENCY then WARNING.
 *
 * @param tempC the temperature now
 * @param current the level before this reading
 * @param thresholds the thresholds of the source (CPU or battery)
 */
fun levelWithHysteresis(
    tempC: Float,
    current: ThermalLevel,
    thresholds: List<ThermalThreshold>
): ThermalLevel {
    val raw = thresholds
        .filter { tempC >= it.temperatureCelsius }
        .maxOfOrNull { it.level } ?: ThermalLevel.NORMAL
    if (raw >= current) return raw
    val held = thresholds
        .filter { it.level <= current && tempC >= it.temperatureCelsius - it.hysteresisDegreesC }
        .maxOfOrNull { it.level } ?: ThermalLevel.NORMAL
    return maxOf(raw, held)
}

/**
 * The throttling wanted for one CPU level and one battery level; see [throttlePlanFor].
 */
data class ThrottlePlan(
    val bitrateReductionPercent: Int = 0,
    val reduceResolution: Boolean = false,
    val reduceFramerate: Boolean = false,
    val pauseStreaming: Boolean = false,
    val holdCharging: Boolean = false
) {
    /** The throttle actions this plan puts in force. */
    fun actions(): List<ThrottleAction> = buildList {
        if (bitrateReductionPercent > 0) add(ThrottleAction.REDUCE_BITRATE)
        if (reduceResolution) add(ThrottleAction.REDUCE_RESOLUTION)
        if (reduceFramerate) add(ThrottleAction.REDUCE_FRAMERATE)
        if (pauseStreaming) add(ThrottleAction.PAUSE_STREAMING)
        if (holdCharging) add(ThrottleAction.DISABLE_CHARGING)
    }
}

/**
 * The throttling wanted while the CPU is at [cpu] and the battery at [battery].
 *
 * - Bitrate: cut by the warning percentage at CPU WARNING and by the critical percentage
 *   at CPU CRITICAL and above ([ThermalConfig.bitrateReduction]).
 * - Resolution one step down and frame rate capped at 24 fps from CPU CRITICAL, unless
 *   switched off in [ThermalConfig.resolutionReduction] / [ThermalConfig.framerateReduction].
 * - Outputs paused while the CPU or the battery is at EMERGENCY.
 * - Charging held while the battery is at WARNING or above.
 *
 * The governor derives this from both levels every cycle and applies only the differences,
 * so the CPU cooling down never lifts a measure the battery still needs, and the reverse.
 */
fun throttlePlanFor(cpu: ThermalLevel, battery: ThermalLevel, config: ThermalConfig): ThrottlePlan {
    val cpuCritical = cpu >= ThermalLevel.CRITICAL
    val resolution = config.resolutionReduction
    return ThrottlePlan(
        bitrateReductionPercent = when {
            cpuCritical -> config.bitrateReduction.criticalReductionPercent
            cpu == ThermalLevel.WARNING -> config.bitrateReduction.warningReductionPercent
            else -> 0
        },
        reduceResolution = cpuCritical && (resolution.enable4KTo1080p || resolution.enable1080pTo720p),
        reduceFramerate = cpuCritical && config.framerateReduction.reduceTo24fps,
        pauseStreaming = cpu == ThermalLevel.EMERGENCY || battery == ThermalLevel.EMERGENCY,
        holdCharging = battery >= ThermalLevel.WARNING
    )
}
