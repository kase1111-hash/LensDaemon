package com.lensdaemon.thermal

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Thermal stress test for profiling device thermal behavior.
 *
 * Runs a configurable-duration encoding stress test at max resolution/bitrate,
 * records the temperature curve and throttle events, and outputs a recommended
 * ThermalProfile for the current device.
 *
 * The stress test does not directly start encoding — it relies on the caller
 * (typically the web API) to ensure streaming is active. It monitors temperatures
 * via ThermalMonitor and records the thermal response.
 */
class ThermalStressTest(
    private val monitor: ThermalMonitor,
    private val governor: ThermalGovernor
) {

    companion object {
        private const val TAG = "ThermalStressTest"
        private const val SAMPLE_INTERVAL_MS = 5000L
        private const val MIN_DURATION_SEC = 60
        private const val MAX_DURATION_SEC = 600
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var testJob: Job? = null

    private var running = false
    private var startTimeMs = 0L
    private var durationSec = 300
    private val samples = mutableListOf<TemperatureSample>()
    private var throttleEventCount = 0
    private var lastResult: StressTestResult? = null

    /**
     * Start a stress test.
     *
     * @param durationSeconds How long to run (clamped to 60-600 seconds)
     */
    fun start(durationSeconds: Int = 300) {
        if (running) {
            Timber.tag(TAG).w("Stress test already running")
            return
        }

        durationSec = durationSeconds.coerceIn(MIN_DURATION_SEC, MAX_DURATION_SEC)
        samples.clear()
        throttleEventCount = 0
        lastResult = null
        running = true
        startTimeMs = System.currentTimeMillis()

        Timber.tag(TAG).i("Starting thermal stress test for ${durationSec}s")

        testJob = scope.launch {
            val endTimeMs = startTimeMs + (durationSec * 1000L)

            while (isActive && System.currentTimeMillis() < endTimeMs) {
                val elapsed = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
                val cpuTemp = monitor.cpuTemperature.value
                val batteryTemp = monitor.batteryTemperature.value

                samples.add(
                    TemperatureSample(
                        elapsedSec = elapsed,
                        cpuTempC = cpuTemp,
                        batteryTempC = batteryTemp
                    )
                )

                // Count throttle events from governor status
                val status = governor.status.value
                if (status.isThrottling) {
                    throttleEventCount++
                }

                delay(SAMPLE_INTERVAL_MS)
            }

            // Test complete — generate result
            finishTest()
        }
    }

    /**
     * Stop the stress test early.
     */
    fun stop() {
        if (!running) return
        Timber.tag(TAG).i("Stopping stress test early")
        testJob?.cancel()
        testJob = null
        finishTest()
    }

    /**
     * Is the stress test currently running?
     */
    fun isRunning(): Boolean = running

    /**
     * Get elapsed seconds since test start.
     */
    fun getElapsedSeconds(): Int {
        if (!running && startTimeMs == 0L) return 0
        return ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()
    }

    /**
     * Get the result of the last completed stress test, or null if none.
     */
    fun getResult(): StressTestResult? = lastResult

    /**
     * Release resources.
     */
    fun release() {
        stop()
        scope.cancel()
    }

    // ==================== Private ====================

    private fun finishTest() {
        running = false
        val actualDuration = ((System.currentTimeMillis() - startTimeMs) / 1000).toInt()

        if (samples.isEmpty()) {
            Timber.tag(TAG).w("Stress test finished with no samples")
            return
        }

        val cpuTemps = samples.map { it.cpuTempC }
        val batteryTemps = samples.map { it.batteryTempC }

        val peakCpu = cpuTemps.maxOrNull() ?: 0f
        val peakBattery = batteryTemps.maxOrNull() ?: 0f
        val avgCpu = cpuTemps.average().toFloat()
        val avgBattery = batteryTemps.average().toFloat()

        val recommendedProfile = generateRecommendedProfile(peakCpu, peakBattery, avgCpu)

        lastResult = StressTestResult(
            durationSeconds = actualDuration,
            peakCpuTempC = peakCpu,
            peakBatteryTempC = peakBattery,
            avgCpuTempC = avgCpu,
            avgBatteryTempC = avgBattery,
            throttleEvents = throttleEventCount,
            temperatureCurve = samples.toList(),
            recommendedProfile = recommendedProfile
        )

        Timber.tag(TAG).i(
            "Stress test complete: ${actualDuration}s, peak CPU=${peakCpu}°C, " +
            "peak battery=${peakBattery}°C, throttle events=$throttleEventCount"
        )
    }

    /**
     * Generate a recommended ThermalProfile based on observed temperatures.
     *
     * Strategy:
     * - Warning threshold = peak observed CPU temp - 10°C (gives headroom)
     * - Critical threshold = peak observed CPU temp - 5°C
     * - Emergency threshold = peak observed CPU temp
     * - If peak CPU was under 50°C, the device can handle relaxed thresholds
     * - If peak CPU was over 60°C, the device needs aggressive thresholds
     */
    private fun generateRecommendedProfile(
        peakCpu: Float,
        peakBattery: Float,
        avgCpu: Float
    ): ThermalProfile {
        // Set warning 10°C below peak, critical 5°C below, emergency at peak
        val cpuWarn = (peakCpu - 10f).coerceAtLeast(40f)
        val cpuCritical = (peakCpu - 5f).coerceAtLeast(45f)
        val cpuEmergency = peakCpu.coerceAtLeast(50f)

        val batteryWarn = (peakBattery - 6f).coerceAtLeast(38f)
        val batteryCritical = (peakBattery - 3f).coerceAtLeast(42f)
        val batteryEmergency = peakBattery.coerceAtLeast(45f)

        // Determine sustainable encoding settings based on thermal behavior
        val sustainableBitrate: Int
        val sustainableResolution: String
        val sustainableFps: Int

        when {
            peakCpu < 50f -> {
                sustainableBitrate = 8000
                sustainableResolution = "1080p"
                sustainableFps = 30
            }
            peakCpu < 55f -> {
                sustainableBitrate = 5000
                sustainableResolution = "1080p"
                sustainableFps = 30
            }
            peakCpu < 60f -> {
                sustainableBitrate = 4000
                sustainableResolution = "1080p"
                sustainableFps = 24
            }
            else -> {
                sustainableBitrate = 3000
                sustainableResolution = "720p"
                sustainableFps = 24
            }
        }

        return ThermalProfile(
            deviceModel = Build.MODEL,
            socModel = getSocModelSafe(),
            displayName = "${Build.MANUFACTURER} ${Build.MODEL} (auto-profiled)",
            cpuWarnC = cpuWarn,
            cpuCriticalC = cpuCritical,
            cpuEmergencyC = cpuEmergency,
            batteryWarnC = batteryWarn,
            batteryCriticalC = batteryCritical,
            batteryEmergencyC = batteryEmergency,
            sustainableBitrateKbps = sustainableBitrate,
            sustainableResolution = sustainableResolution,
            sustainableFps = sustainableFps,
            notes = "Auto-generated from ${getElapsedSeconds()}s stress test. " +
                    "Peak CPU: ${peakCpu}°C, Avg CPU: ${avgCpu}°C."
        )
    }

    @Suppress("PrivateApi")
    private fun getSocModelSafe(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                Build.SOC_MODEL
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}

/**
 * A single temperature sample recorded during a stress test
 */
data class TemperatureSample(
    val elapsedSec: Int,
    val cpuTempC: Float,
    val batteryTempC: Float
)

/**
 * Results from a completed stress test
 */
data class StressTestResult(
    val durationSeconds: Int,
    val peakCpuTempC: Float,
    val peakBatteryTempC: Float,
    val avgCpuTempC: Float,
    val avgBatteryTempC: Float,
    val throttleEvents: Int,
    val temperatureCurve: List<TemperatureSample>,
    val recommendedProfile: ThermalProfile
)
