package com.lensdaemon.thermal

import org.junit.Assert.*
import org.junit.Test

class ThermalConfigTest {

    // -------------------------------------------------------
    // 1. ThermalLevel enum ordering
    // -------------------------------------------------------

    @Test
    fun thermalLevel_ordinalOrdering() {
        assertTrue(ThermalLevel.NORMAL.ordinal < ThermalLevel.ELEVATED.ordinal)
        assertTrue(ThermalLevel.ELEVATED.ordinal < ThermalLevel.WARNING.ordinal)
        assertTrue(ThermalLevel.WARNING.ordinal < ThermalLevel.CRITICAL.ordinal)
        assertTrue(ThermalLevel.CRITICAL.ordinal < ThermalLevel.EMERGENCY.ordinal)
    }

    @Test
    fun thermalLevel_containsAllExpectedValues() {
        val values = ThermalLevel.values()
        assertEquals(5, values.size)
        assertNotNull(ThermalLevel.valueOf("NORMAL"))
        assertNotNull(ThermalLevel.valueOf("ELEVATED"))
        assertNotNull(ThermalLevel.valueOf("WARNING"))
        assertNotNull(ThermalLevel.valueOf("CRITICAL"))
        assertNotNull(ThermalLevel.valueOf("EMERGENCY"))
    }

    // -------------------------------------------------------
    // 2. CpuThermalConfig
    // -------------------------------------------------------

    @Test
    fun cpuThermalConfig_defaultValues() {
        val config = CpuThermalConfig()
        assertEquals(45f, config.normalMaxC, 0.01f)
        assertEquals(50f, config.warningThresholdC, 0.01f)
        assertEquals(55f, config.criticalThresholdC, 0.01f)
        assertEquals(60f, config.emergencyThresholdC, 0.01f)
        assertEquals(3.0f, config.hysteresisC, 0.01f)
    }

    @Test
    fun cpuThermalConfig_getThresholds_returnsFourThresholds() {
        val thresholds = CpuThermalConfig().getThresholds()
        assertEquals(4, thresholds.size)
    }

    @Test
    fun cpuThermalConfig_getThresholds_correctLevels() {
        val thresholds = CpuThermalConfig().getThresholds()
        assertEquals(ThermalLevel.ELEVATED, thresholds[0].level)
        assertEquals(ThermalLevel.WARNING, thresholds[1].level)
        assertEquals(ThermalLevel.CRITICAL, thresholds[2].level)
        assertEquals(ThermalLevel.EMERGENCY, thresholds[3].level)
    }

    @Test
    fun cpuThermalConfig_getThresholds_correctTemperatures() {
        val config = CpuThermalConfig()
        val thresholds = config.getThresholds()
        assertEquals(45f, thresholds[0].temperatureCelsius, 0.01f)
        assertEquals(50f, thresholds[1].temperatureCelsius, 0.01f)
        assertEquals(55f, thresholds[2].temperatureCelsius, 0.01f)
        assertEquals(60f, thresholds[3].temperatureCelsius, 0.01f)
    }

    @Test
    fun cpuThermalConfig_getThresholds_correctHysteresis() {
        val config = CpuThermalConfig()
        val thresholds = config.getThresholds()
        for (threshold in thresholds) {
            assertEquals(3.0f, threshold.hysteresisDegreesC, 0.01f)
        }
    }

    @Test
    fun cpuThermalConfig_getThresholds_correctActions() {
        val thresholds = CpuThermalConfig().getThresholds()

        // ELEVATED: no actions
        assertTrue(thresholds[0].actions.isEmpty())

        // WARNING: reduce bitrate
        assertEquals(listOf(ThrottleAction.REDUCE_BITRATE), thresholds[1].actions)

        // CRITICAL: reduce resolution and framerate
        assertEquals(
            listOf(ThrottleAction.REDUCE_RESOLUTION, ThrottleAction.REDUCE_FRAMERATE),
            thresholds[2].actions
        )

        // EMERGENCY: pause streaming and alert user
        assertEquals(
            listOf(ThrottleAction.PAUSE_STREAMING, ThrottleAction.ALERT_USER),
            thresholds[3].actions
        )
    }

    @Test
    fun cpuThermalConfig_customValues() {
        val config = CpuThermalConfig(
            normalMaxC = 40f,
            warningThresholdC = 48f,
            criticalThresholdC = 53f,
            emergencyThresholdC = 58f,
            hysteresisC = 5.0f
        )
        val thresholds = config.getThresholds()
        assertEquals(40f, thresholds[0].temperatureCelsius, 0.01f)
        assertEquals(48f, thresholds[1].temperatureCelsius, 0.01f)
        assertEquals(53f, thresholds[2].temperatureCelsius, 0.01f)
        assertEquals(58f, thresholds[3].temperatureCelsius, 0.01f)
        for (threshold in thresholds) {
            assertEquals(5.0f, threshold.hysteresisDegreesC, 0.01f)
        }
    }

    // -------------------------------------------------------
    // 3. BatteryThermalConfig
    // -------------------------------------------------------

    @Test
    fun batteryThermalConfig_defaultValues() {
        val config = BatteryThermalConfig()
        assertEquals(38f, config.normalMaxC, 0.01f)
        assertEquals(42f, config.warningThresholdC, 0.01f)
        assertEquals(45f, config.criticalThresholdC, 0.01f)
        assertEquals(48f, config.emergencyThresholdC, 0.01f)
        assertEquals(2.0f, config.hysteresisC, 0.01f)
    }

    @Test
    fun batteryThermalConfig_getThresholds_returnsFourThresholds() {
        val thresholds = BatteryThermalConfig().getThresholds()
        assertEquals(4, thresholds.size)
    }

    @Test
    fun batteryThermalConfig_getThresholds_correctLevels() {
        val thresholds = BatteryThermalConfig().getThresholds()
        assertEquals(ThermalLevel.ELEVATED, thresholds[0].level)
        assertEquals(ThermalLevel.WARNING, thresholds[1].level)
        assertEquals(ThermalLevel.CRITICAL, thresholds[2].level)
        assertEquals(ThermalLevel.EMERGENCY, thresholds[3].level)
    }

    @Test
    fun batteryThermalConfig_getThresholds_correctTemperatures() {
        val thresholds = BatteryThermalConfig().getThresholds()
        assertEquals(38f, thresholds[0].temperatureCelsius, 0.01f)
        assertEquals(42f, thresholds[1].temperatureCelsius, 0.01f)
        assertEquals(45f, thresholds[2].temperatureCelsius, 0.01f)
        assertEquals(48f, thresholds[3].temperatureCelsius, 0.01f)
    }

    @Test
    fun batteryThermalConfig_getThresholds_correctHysteresis() {
        val thresholds = BatteryThermalConfig().getThresholds()
        for (threshold in thresholds) {
            assertEquals(2.0f, threshold.hysteresisDegreesC, 0.01f)
        }
    }

    @Test
    fun batteryThermalConfig_getThresholds_correctActions() {
        val thresholds = BatteryThermalConfig().getThresholds()

        // ELEVATED: no actions
        assertTrue(thresholds[0].actions.isEmpty())

        // WARNING: disable charging
        assertEquals(listOf(ThrottleAction.DISABLE_CHARGING), thresholds[1].actions)

        // CRITICAL: disable charging and alert user
        assertEquals(
            listOf(ThrottleAction.DISABLE_CHARGING, ThrottleAction.ALERT_USER),
            thresholds[2].actions
        )

        // EMERGENCY: pause streaming and alert user
        assertEquals(
            listOf(ThrottleAction.PAUSE_STREAMING, ThrottleAction.ALERT_USER),
            thresholds[3].actions
        )
    }

    // -------------------------------------------------------
    // 4. ThermalConfig presets
    // -------------------------------------------------------

    @Test
    fun thermalConfig_defaultPreset_hasDefaultCpuAndBatteryConfigs() {
        val config = ThermalConfig.DEFAULT
        assertEquals(CpuThermalConfig(), config.cpuConfig)
        assertEquals(BatteryThermalConfig(), config.batteryConfig)
    }

    @Test
    fun thermalConfig_aggressivePreset_hasLowerThresholds() {
        val aggressive = ThermalConfig.AGGRESSIVE
        val default = ThermalConfig.DEFAULT

        // CPU thresholds are lower in aggressive
        assertTrue(aggressive.cpuConfig.normalMaxC < default.cpuConfig.normalMaxC)
        assertTrue(aggressive.cpuConfig.warningThresholdC < default.cpuConfig.warningThresholdC)
        assertTrue(aggressive.cpuConfig.criticalThresholdC < default.cpuConfig.criticalThresholdC)
        assertTrue(aggressive.cpuConfig.emergencyThresholdC < default.cpuConfig.emergencyThresholdC)

        // Battery thresholds are lower in aggressive
        assertTrue(aggressive.batteryConfig.normalMaxC < default.batteryConfig.normalMaxC)
        assertTrue(aggressive.batteryConfig.warningThresholdC < default.batteryConfig.warningThresholdC)
        assertTrue(aggressive.batteryConfig.criticalThresholdC < default.batteryConfig.criticalThresholdC)
        assertTrue(aggressive.batteryConfig.emergencyThresholdC < default.batteryConfig.emergencyThresholdC)
    }

    @Test
    fun thermalConfig_aggressivePreset_specificValues() {
        val aggressive = ThermalConfig.AGGRESSIVE
        assertEquals(40f, aggressive.cpuConfig.normalMaxC, 0.01f)
        assertEquals(45f, aggressive.cpuConfig.warningThresholdC, 0.01f)
        assertEquals(50f, aggressive.cpuConfig.criticalThresholdC, 0.01f)
        assertEquals(55f, aggressive.cpuConfig.emergencyThresholdC, 0.01f)

        assertEquals(35f, aggressive.batteryConfig.normalMaxC, 0.01f)
        assertEquals(38f, aggressive.batteryConfig.warningThresholdC, 0.01f)
        assertEquals(42f, aggressive.batteryConfig.criticalThresholdC, 0.01f)
        assertEquals(45f, aggressive.batteryConfig.emergencyThresholdC, 0.01f)

        assertEquals(40, aggressive.batteryBypassConfig.targetChargePercent)
        assertEquals(30, aggressive.batteryBypassConfig.resumeChargePercent)
    }

    @Test
    fun thermalConfig_relaxedPreset_hasHigherThresholds() {
        val relaxed = ThermalConfig.RELAXED
        val default = ThermalConfig.DEFAULT

        // CPU thresholds are higher in relaxed
        assertTrue(relaxed.cpuConfig.normalMaxC > default.cpuConfig.normalMaxC)
        assertTrue(relaxed.cpuConfig.warningThresholdC > default.cpuConfig.warningThresholdC)
        assertTrue(relaxed.cpuConfig.criticalThresholdC > default.cpuConfig.criticalThresholdC)
        assertTrue(relaxed.cpuConfig.emergencyThresholdC > default.cpuConfig.emergencyThresholdC)

        // Battery thresholds are higher in relaxed
        assertTrue(relaxed.batteryConfig.normalMaxC > default.batteryConfig.normalMaxC)
        assertTrue(relaxed.batteryConfig.warningThresholdC > default.batteryConfig.warningThresholdC)
        assertTrue(relaxed.batteryConfig.criticalThresholdC > default.batteryConfig.criticalThresholdC)
        assertTrue(relaxed.batteryConfig.emergencyThresholdC > default.batteryConfig.emergencyThresholdC)
    }

    @Test
    fun thermalConfig_relaxedPreset_specificValues() {
        val relaxed = ThermalConfig.RELAXED
        assertEquals(50f, relaxed.cpuConfig.normalMaxC, 0.01f)
        assertEquals(55f, relaxed.cpuConfig.warningThresholdC, 0.01f)
        assertEquals(60f, relaxed.cpuConfig.criticalThresholdC, 0.01f)
        assertEquals(65f, relaxed.cpuConfig.emergencyThresholdC, 0.01f)

        assertEquals(42f, relaxed.batteryConfig.normalMaxC, 0.01f)
        assertEquals(45f, relaxed.batteryConfig.warningThresholdC, 0.01f)
        assertEquals(48f, relaxed.batteryConfig.criticalThresholdC, 0.01f)
        assertEquals(52f, relaxed.batteryConfig.emergencyThresholdC, 0.01f)

        assertEquals(60, relaxed.batteryBypassConfig.targetChargePercent)
        assertEquals(50, relaxed.batteryBypassConfig.resumeChargePercent)
    }

    @Test
    fun thermalConfig_customConstruction() {
        val customCpu = CpuThermalConfig(normalMaxC = 42f, warningThresholdC = 47f)
        val customBattery = BatteryThermalConfig(normalMaxC = 36f)
        val config = ThermalConfig(
            cpuConfig = customCpu,
            batteryConfig = customBattery,
            monitorIntervalMs = 10000
        )
        assertEquals(42f, config.cpuConfig.normalMaxC, 0.01f)
        assertEquals(47f, config.cpuConfig.warningThresholdC, 0.01f)
        assertEquals(36f, config.batteryConfig.normalMaxC, 0.01f)
        assertEquals(10000L, config.monitorIntervalMs)
    }

    @Test
    fun thermalConfig_defaultMonitoringValues() {
        val config = ThermalConfig.DEFAULT
        assertEquals(5000L, config.monitorIntervalMs)
        assertEquals(24, config.historyRetentionHours)
        assertEquals(60000L, config.historyIntervalMs)
    }

    // -------------------------------------------------------
    // 5. ThermalStatus
    // -------------------------------------------------------

    @Test
    fun thermalStatus_statusText_normal() {
        val status = ThermalStatus(overallLevel = ThermalLevel.NORMAL)
        assertEquals("Normal", status.statusText)
    }

    @Test
    fun thermalStatus_statusText_elevated() {
        val status = ThermalStatus(overallLevel = ThermalLevel.ELEVATED)
        assertEquals("Elevated", status.statusText)
    }

    @Test
    fun thermalStatus_statusText_warning() {
        val status = ThermalStatus(overallLevel = ThermalLevel.WARNING)
        assertEquals("Warning - Throttling", status.statusText)
    }

    @Test
    fun thermalStatus_statusText_critical() {
        val status = ThermalStatus(overallLevel = ThermalLevel.CRITICAL)
        assertEquals("Critical - Performance Reduced", status.statusText)
    }

    @Test
    fun thermalStatus_statusText_emergency() {
        val status = ThermalStatus(overallLevel = ThermalLevel.EMERGENCY)
        assertEquals("Emergency - Streaming Paused", status.statusText)
    }

    @Test
    fun thermalStatus_isThrottling_falseWithEmptyActions() {
        val status = ThermalStatus(activeActions = emptySet())
        assertFalse(status.isThrottling)
    }

    @Test
    fun thermalStatus_isThrottling_trueWithActions() {
        val status = ThermalStatus(activeActions = setOf(ThrottleAction.REDUCE_BITRATE))
        assertTrue(status.isThrottling)
    }

    @Test
    fun thermalStatus_isThrottling_trueWithMultipleActions() {
        val status = ThermalStatus(
            activeActions = setOf(
                ThrottleAction.REDUCE_BITRATE,
                ThrottleAction.REDUCE_RESOLUTION,
                ThrottleAction.REDUCE_FRAMERATE
            )
        )
        assertTrue(status.isThrottling)
    }

    @Test
    fun thermalStatus_defaultValues() {
        val status = ThermalStatus()
        assertEquals(0f, status.cpuTemperatureC, 0.01f)
        assertEquals(0f, status.batteryTemperatureC, 0.01f)
        assertEquals(ThermalLevel.NORMAL, status.cpuLevel)
        assertEquals(ThermalLevel.NORMAL, status.batteryLevel)
        assertEquals(ThermalLevel.NORMAL, status.overallLevel)
        assertTrue(status.activeActions.isEmpty())
        assertEquals(0, status.batteryPercent)
        assertFalse(status.isCharging)
        assertFalse(status.chargingDisabled)
        assertEquals(0, status.bitrateReductionPercent)
        assertFalse(status.resolutionReduced)
        assertFalse(status.framerateReduced)
    }

    // -------------------------------------------------------
    // 6. BitrateReductionConfig defaults
    // -------------------------------------------------------

    @Test
    fun bitrateReductionConfig_defaultValues() {
        val config = BitrateReductionConfig()
        assertEquals(20, config.warningReductionPercent)
        assertEquals(40, config.criticalReductionPercent)
        assertEquals(500, config.minBitrateKbps)
    }

    // -------------------------------------------------------
    // 7. BatteryBypassConfig defaults
    // -------------------------------------------------------

    @Test
    fun batteryBypassConfig_defaultValues() {
        val config = BatteryBypassConfig()
        assertTrue(config.enabled)
        assertEquals(50, config.targetChargePercent)
        assertEquals(40, config.resumeChargePercent)
        assertEquals(42f, config.maxBatteryTempC, 0.01f)
        assertEquals(38f, config.resumeChargeTempC, 0.01f)
    }

    // -------------------------------------------------------
    // 8. ThermalThreshold hysteresis cooldown temperature
    // -------------------------------------------------------

    @Test
    fun thermalThreshold_hysteresisCooldownTemperature() {
        val threshold = ThermalThreshold(
            level = ThermalLevel.WARNING,
            temperatureCelsius = 50f,
            actions = listOf(ThrottleAction.REDUCE_BITRATE),
            hysteresisDegreesC = 3.0f
        )
        val cooldownTemp = threshold.temperatureCelsius - threshold.hysteresisDegreesC
        assertEquals(47f, cooldownTemp, 0.01f)
    }

    @Test
    fun thermalThreshold_hysteresisCooldown_cpuThresholds() {
        val thresholds = CpuThermalConfig().getThresholds()

        // ELEVATED: 45 - 3 = 42
        assertEquals(42f, thresholds[0].temperatureCelsius - thresholds[0].hysteresisDegreesC, 0.01f)
        // WARNING: 50 - 3 = 47
        assertEquals(47f, thresholds[1].temperatureCelsius - thresholds[1].hysteresisDegreesC, 0.01f)
        // CRITICAL: 55 - 3 = 52
        assertEquals(52f, thresholds[2].temperatureCelsius - thresholds[2].hysteresisDegreesC, 0.01f)
        // EMERGENCY: 60 - 3 = 57
        assertEquals(57f, thresholds[3].temperatureCelsius - thresholds[3].hysteresisDegreesC, 0.01f)
    }

    @Test
    fun thermalThreshold_hysteresisCooldown_batteryThresholds() {
        val thresholds = BatteryThermalConfig().getThresholds()

        // ELEVATED: 38 - 2 = 36
        assertEquals(36f, thresholds[0].temperatureCelsius - thresholds[0].hysteresisDegreesC, 0.01f)
        // WARNING: 42 - 2 = 40
        assertEquals(40f, thresholds[1].temperatureCelsius - thresholds[1].hysteresisDegreesC, 0.01f)
        // CRITICAL: 45 - 2 = 43
        assertEquals(43f, thresholds[2].temperatureCelsius - thresholds[2].hysteresisDegreesC, 0.01f)
        // EMERGENCY: 48 - 2 = 46
        assertEquals(46f, thresholds[3].temperatureCelsius - thresholds[3].hysteresisDegreesC, 0.01f)
    }

    @Test
    fun thermalThreshold_defaultHysteresis() {
        val threshold = ThermalThreshold(
            level = ThermalLevel.WARNING,
            temperatureCelsius = 50f,
            actions = emptyList()
        )
        assertEquals(3.0f, threshold.hysteresisDegreesC, 0.01f)
    }

    // -------------------------------------------------------
    // 9. ThrottleAction enum values
    // -------------------------------------------------------

    @Test
    fun throttleAction_allValuesExist() {
        assertNotNull(ThrottleAction.valueOf("NONE"))
        assertNotNull(ThrottleAction.valueOf("REDUCE_BITRATE"))
        assertNotNull(ThrottleAction.valueOf("REDUCE_RESOLUTION"))
        assertNotNull(ThrottleAction.valueOf("REDUCE_FRAMERATE"))
        assertNotNull(ThrottleAction.valueOf("PAUSE_STREAMING"))
        assertNotNull(ThrottleAction.valueOf("DISABLE_CHARGING"))
        assertNotNull(ThrottleAction.valueOf("ALERT_USER"))
    }

    @Test
    fun throttleAction_totalCount() {
        assertEquals(7, ThrottleAction.values().size)
    }

    // -------------------------------------------------------
    // 10. ThermalEvent and ThermalHistoryEntry construction
    // -------------------------------------------------------

    @Test
    fun thermalEvent_construction() {
        val event = ThermalEvent(
            timestamp = 1000L,
            source = ThermalSource.CPU,
            oldLevel = ThermalLevel.NORMAL,
            newLevel = ThermalLevel.WARNING,
            temperatureC = 51.5f,
            actionsTriggered = listOf(ThrottleAction.REDUCE_BITRATE)
        )
        assertEquals(1000L, event.timestamp)
        assertEquals(ThermalSource.CPU, event.source)
        assertEquals(ThermalLevel.NORMAL, event.oldLevel)
        assertEquals(ThermalLevel.WARNING, event.newLevel)
        assertEquals(51.5f, event.temperatureC, 0.01f)
        assertEquals(listOf(ThrottleAction.REDUCE_BITRATE), event.actionsTriggered)
    }

    @Test
    fun thermalEvent_constructionWithBatterySource() {
        val event = ThermalEvent(
            timestamp = 2000L,
            source = ThermalSource.BATTERY,
            oldLevel = ThermalLevel.WARNING,
            newLevel = ThermalLevel.CRITICAL,
            temperatureC = 45.0f,
            actionsTriggered = listOf(ThrottleAction.DISABLE_CHARGING, ThrottleAction.ALERT_USER)
        )
        assertEquals(ThermalSource.BATTERY, event.source)
        assertEquals(ThermalLevel.CRITICAL, event.newLevel)
        assertEquals(2, event.actionsTriggered.size)
    }

    @Test
    fun thermalEvent_constructionWithEmptyActions() {
        val event = ThermalEvent(
            timestamp = 3000L,
            source = ThermalSource.CPU,
            oldLevel = ThermalLevel.ELEVATED,
            newLevel = ThermalLevel.NORMAL,
            temperatureC = 40.0f,
            actionsTriggered = emptyList()
        )
        assertTrue(event.actionsTriggered.isEmpty())
    }

    @Test
    fun thermalHistoryEntry_construction() {
        val entry = ThermalHistoryEntry(
            timestamp = 5000L,
            cpuTemperatureC = 48.5f,
            batteryTemperatureC = 39.2f,
            cpuLevel = ThermalLevel.ELEVATED,
            batteryLevel = ThermalLevel.WARNING
        )
        assertEquals(5000L, entry.timestamp)
        assertEquals(48.5f, entry.cpuTemperatureC, 0.01f)
        assertEquals(39.2f, entry.batteryTemperatureC, 0.01f)
        assertEquals(ThermalLevel.ELEVATED, entry.cpuLevel)
        assertEquals(ThermalLevel.WARNING, entry.batteryLevel)
    }

    @Test
    fun thermalHistoryEntry_normalLevels() {
        val entry = ThermalHistoryEntry(
            timestamp = 6000L,
            cpuTemperatureC = 35.0f,
            batteryTemperatureC = 28.0f,
            cpuLevel = ThermalLevel.NORMAL,
            batteryLevel = ThermalLevel.NORMAL
        )
        assertEquals(ThermalLevel.NORMAL, entry.cpuLevel)
        assertEquals(ThermalLevel.NORMAL, entry.batteryLevel)
    }

    // -------------------------------------------------------
    // Additional: ThermalSource enum
    // -------------------------------------------------------

    @Test
    fun thermalSource_allValuesExist() {
        assertNotNull(ThermalSource.valueOf("CPU"))
        assertNotNull(ThermalSource.valueOf("BATTERY"))
        assertNotNull(ThermalSource.valueOf("GPU"))
        assertNotNull(ThermalSource.valueOf("SKIN"))
        assertNotNull(ThermalSource.valueOf("UNKNOWN"))
        assertEquals(5, ThermalSource.values().size)
    }

    // -------------------------------------------------------
    // Additional: Data class equality and copy
    // -------------------------------------------------------

    @Test
    fun cpuThermalConfig_dataClassEquality() {
        val config1 = CpuThermalConfig()
        val config2 = CpuThermalConfig()
        assertEquals(config1, config2)
    }

    @Test
    fun cpuThermalConfig_dataClassCopy() {
        val original = CpuThermalConfig()
        val modified = original.copy(normalMaxC = 42f)
        assertEquals(42f, modified.normalMaxC, 0.01f)
        assertEquals(original.warningThresholdC, modified.warningThresholdC, 0.01f)
    }

    @Test
    fun batteryThermalConfig_dataClassEquality() {
        val config1 = BatteryThermalConfig()
        val config2 = BatteryThermalConfig()
        assertEquals(config1, config2)
    }

    @Test
    fun thermalStatus_dataClassCopy() {
        val original = ThermalStatus()
        val modified = original.copy(
            cpuTemperatureC = 55f,
            overallLevel = ThermalLevel.CRITICAL,
            activeActions = setOf(ThrottleAction.REDUCE_RESOLUTION)
        )
        assertEquals(55f, modified.cpuTemperatureC, 0.01f)
        assertEquals(ThermalLevel.CRITICAL, modified.overallLevel)
        assertTrue(modified.isThrottling)
        assertEquals("Critical - Performance Reduced", modified.statusText)
    }
}
