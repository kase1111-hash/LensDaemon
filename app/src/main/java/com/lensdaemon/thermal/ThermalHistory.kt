package com.lensdaemon.thermal

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Thermal history tracker
 *
 * Records temperature history for analysis and dashboard display:
 * - 24-hour rolling history
 * - Minute-by-minute granularity
 * - Event logging for thermal state changes
 * - Statistics calculation
 * - Persistence across app restarts
 */
class ThermalHistory(
    private val context: Context,
    private val config: ThermalConfig = ThermalConfig.DEFAULT
) {
    companion object {
        private const val TAG = "ThermalHistory"
        private const val HISTORY_FILE = "thermal_history.json"
        private const val EVENTS_FILE = "thermal_events.json"
        private const val MAX_HISTORY_ENTRIES = 1440  // 24 hours at 1 per minute
        private const val MAX_EVENT_ENTRIES = 500
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordJob: Job? = null

    // History storage
    private val history = ConcurrentLinkedDeque<ThermalHistoryEntry>()
    private val events = ConcurrentLinkedDeque<ThermalEvent>()
    private val mutex = Mutex()

    // Current thermal state for tracking state changes
    private var lastCpuLevel = ThermalLevel.NORMAL
    private var lastBatteryLevel = ThermalLevel.NORMAL

    // Statistics
    private var statsStartTime = System.currentTimeMillis()

    // Temperature monitor reference
    private var monitor: ThermalMonitor? = null

    init {
        loadHistory()
    }

    /**
     * Set the thermal monitor to read from
     */
    fun setMonitor(thermalMonitor: ThermalMonitor) {
        this.monitor = thermalMonitor
    }

    /**
     * Start recording history
     */
    fun startRecording() {
        if (recordJob?.isActive == true) {
            return
        }

        Timber.tag(TAG).i("Starting thermal history recording")
        statsStartTime = System.currentTimeMillis()

        recordJob = scope.launch {
            while (isActive) {
                try {
                    recordCurrentState()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error recording thermal history")
                }
                delay(config.historyIntervalMs)
            }
        }
    }

    /**
     * Stop recording history
     */
    fun stopRecording() {
        Timber.tag(TAG).i("Stopping thermal history recording")
        recordJob?.cancel()
        recordJob = null
        saveHistory()
    }

    /**
     * Release resources
     */
    fun release() {
        stopRecording()
        scope.cancel()
    }

    /**
     * Record current thermal state
     */
    private suspend fun recordCurrentState() {
        val mon = monitor ?: return

        val cpuTemp = mon.cpuTemperature.value
        val batteryTemp = mon.batteryTemperature.value
        val cpuLevel = calculateLevel(cpuTemp, config.cpuConfig.getThresholds())
        val batteryLevel = calculateLevel(batteryTemp, config.batteryConfig.getThresholds())

        val entry = ThermalHistoryEntry(
            timestamp = System.currentTimeMillis(),
            cpuTemperatureC = cpuTemp,
            batteryTemperatureC = batteryTemp,
            cpuLevel = cpuLevel,
            batteryLevel = batteryLevel
        )

        mutex.withLock {
            history.addLast(entry)

            // Trim to max size
            while (history.size > MAX_HISTORY_ENTRIES) {
                history.removeFirst()
            }
        }

        // Check for level changes and record events
        checkLevelChanges(cpuTemp, batteryTemp, cpuLevel, batteryLevel)

        lastCpuLevel = cpuLevel
        lastBatteryLevel = batteryLevel
    }

    /**
     * Calculate thermal level from temperature
     */
    private fun calculateLevel(tempC: Float, thresholds: List<ThermalThreshold>): ThermalLevel {
        var level = ThermalLevel.NORMAL
        for (threshold in thresholds.sortedBy { it.temperatureCelsius }) {
            if (tempC >= threshold.temperatureCelsius) {
                level = threshold.level
            }
        }
        return level
    }

    /**
     * Check for thermal level changes and record events
     */
    private suspend fun checkLevelChanges(
        cpuTemp: Float,
        batteryTemp: Float,
        cpuLevel: ThermalLevel,
        batteryLevel: ThermalLevel
    ) {
        if (cpuLevel != lastCpuLevel) {
            recordEvent(
                source = ThermalSource.CPU,
                oldLevel = lastCpuLevel,
                newLevel = cpuLevel,
                temperature = cpuTemp
            )
        }

        if (batteryLevel != lastBatteryLevel) {
            recordEvent(
                source = ThermalSource.BATTERY,
                oldLevel = lastBatteryLevel,
                newLevel = batteryLevel,
                temperature = batteryTemp
            )
        }
    }

    /**
     * Record a thermal event
     */
    suspend fun recordEvent(
        source: ThermalSource,
        oldLevel: ThermalLevel,
        newLevel: ThermalLevel,
        temperature: Float,
        actions: List<ThrottleAction> = emptyList()
    ) {
        val event = ThermalEvent(
            timestamp = System.currentTimeMillis(),
            source = source,
            oldLevel = oldLevel,
            newLevel = newLevel,
            temperatureC = temperature,
            actionsTriggered = actions
        )

        mutex.withLock {
            events.addLast(event)

            while (events.size > MAX_EVENT_ENTRIES) {
                events.removeFirst()
            }
        }

        Timber.tag(TAG).d("Thermal event: $source $oldLevel -> $newLevel @ ${temperature}°C")
    }

    /**
     * Get history entries for a time period
     */
    fun getHistory(lastHours: Int = 24): List<ThermalHistoryEntry> {
        val cutoff = System.currentTimeMillis() - (lastHours * 60 * 60 * 1000L)
        return history.filter { it.timestamp >= cutoff }
    }

    /**
     * Get recent events
     */
    fun getEvents(lastHours: Int = 24): List<ThermalEvent> {
        val cutoff = System.currentTimeMillis() - (lastHours * 60 * 60 * 1000L)
        return events.filter { it.timestamp >= cutoff }
    }

    /**
     * Get history for graphing (downsampled for performance)
     */
    fun getGraphData(points: Int = 100): List<ThermalHistoryEntry> {
        val allHistory = history.toList()
        if (allHistory.size <= points) {
            return allHistory
        }

        // Downsample to requested number of points
        val step = allHistory.size / points
        return allHistory.filterIndexed { index, _ -> index % step == 0 }
    }

    /**
     * Calculate statistics for a time period
     */
    fun getStats(lastHours: Int = 24): ThermalStats {
        val historyList = getHistory(lastHours)
        if (historyList.isEmpty()) {
            return ThermalStats(
                periodStartMs = statsStartTime,
                periodEndMs = System.currentTimeMillis(),
                cpuTempMin = 0f,
                cpuTempMax = 0f,
                cpuTempAvg = 0f,
                batteryTempMin = 0f,
                batteryTempMax = 0f,
                batteryTempAvg = 0f,
                timeInWarningMs = 0,
                timeInCriticalMs = 0,
                timeInEmergencyMs = 0,
                throttleEventCount = 0
            )
        }

        val cpuTemps = historyList.map { it.cpuTemperatureC }
        val batteryTemps = historyList.map { it.batteryTemperatureC }

        // Calculate time in each state
        var warningTime = 0L
        var criticalTime = 0L
        var emergencyTime = 0L

        for (i in 1 until historyList.size) {
            val duration = historyList[i].timestamp - historyList[i - 1].timestamp
            val level = maxOf(historyList[i].cpuLevel, historyList[i].batteryLevel)

            when (level) {
                ThermalLevel.WARNING -> warningTime += duration
                ThermalLevel.CRITICAL -> criticalTime += duration
                ThermalLevel.EMERGENCY -> emergencyTime += duration
                else -> {}
            }
        }

        val eventList = getEvents(lastHours)
        val throttleEvents = eventList.count {
            it.newLevel.ordinal > ThermalLevel.ELEVATED.ordinal
        }

        return ThermalStats(
            periodStartMs = historyList.first().timestamp,
            periodEndMs = historyList.last().timestamp,
            cpuTempMin = cpuTemps.minOrNull() ?: 0f,
            cpuTempMax = cpuTemps.maxOrNull() ?: 0f,
            cpuTempAvg = cpuTemps.average().toFloat(),
            batteryTempMin = batteryTemps.minOrNull() ?: 0f,
            batteryTempMax = batteryTemps.maxOrNull() ?: 0f,
            batteryTempAvg = batteryTemps.average().toFloat(),
            timeInWarningMs = warningTime,
            timeInCriticalMs = criticalTime,
            timeInEmergencyMs = emergencyTime,
            throttleEventCount = throttleEvents
        )
    }

    /**
     * Clear all history
     */
    suspend fun clearHistory() {
        mutex.withLock {
            history.clear()
            events.clear()
        }
        deleteHistoryFiles()
    }

    /**
     * Save history to disk
     */
    private fun saveHistory() {
        scope.launch {
            try {
                saveHistoryFile()
                saveEventsFile()
                Timber.tag(TAG).d("Thermal history saved")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error saving thermal history")
            }
        }
    }

    /**
     * Save history entries to file
     */
    private suspend fun saveHistoryFile() {
        val file = File(context.filesDir, HISTORY_FILE)
        val json = JSONArray()

        mutex.withLock {
            history.forEach { entry ->
                json.put(JSONObject().apply {
                    put("timestamp", entry.timestamp)
                    put("cpuTemp", entry.cpuTemperatureC)
                    put("batteryTemp", entry.batteryTemperatureC)
                    put("cpuLevel", entry.cpuLevel.name)
                    put("batteryLevel", entry.batteryLevel.name)
                })
            }
        }

        file.writeText(json.toString())
    }

    /**
     * Save events to file
     */
    private suspend fun saveEventsFile() {
        val file = File(context.filesDir, EVENTS_FILE)
        val json = JSONArray()

        mutex.withLock {
            events.forEach { event ->
                json.put(JSONObject().apply {
                    put("timestamp", event.timestamp)
                    put("source", event.source.name)
                    put("oldLevel", event.oldLevel.name)
                    put("newLevel", event.newLevel.name)
                    put("temperature", event.temperatureC)
                    put("actions", JSONArray().apply {
                        event.actionsTriggered.forEach { put(it.name) }
                    })
                })
            }
        }

        file.writeText(json.toString())
    }

    /**
     * Load history from disk
     */
    private fun loadHistory() {
        scope.launch {
            try {
                loadHistoryFile()
                loadEventsFile()
                Timber.tag(TAG).d("Thermal history loaded: ${history.size} entries, ${events.size} events")
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error loading thermal history")
            }
        }
    }

    /**
     * Load history entries from file
     */
    private suspend fun loadHistoryFile() {
        val file = File(context.filesDir, HISTORY_FILE)
        if (!file.exists()) return

        val json = JSONArray(file.readText())
        val cutoff = System.currentTimeMillis() - (config.historyRetentionHours * 60 * 60 * 1000L)

        mutex.withLock {
            history.clear()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val timestamp = obj.getLong("timestamp")

                if (timestamp >= cutoff) {
                    history.addLast(ThermalHistoryEntry(
                        timestamp = timestamp,
                        cpuTemperatureC = obj.getDouble("cpuTemp").toFloat(),
                        batteryTemperatureC = obj.getDouble("batteryTemp").toFloat(),
                        cpuLevel = ThermalLevel.valueOf(obj.getString("cpuLevel")),
                        batteryLevel = ThermalLevel.valueOf(obj.getString("batteryLevel"))
                    ))
                }
            }
        }
    }

    /**
     * Load events from file
     */
    private suspend fun loadEventsFile() {
        val file = File(context.filesDir, EVENTS_FILE)
        if (!file.exists()) return

        val json = JSONArray(file.readText())
        val cutoff = System.currentTimeMillis() - (config.historyRetentionHours * 60 * 60 * 1000L)

        mutex.withLock {
            events.clear()
            for (i in 0 until json.length()) {
                val obj = json.getJSONObject(i)
                val timestamp = obj.getLong("timestamp")

                if (timestamp >= cutoff) {
                    val actionsJson = obj.getJSONArray("actions")
                    val actions = (0 until actionsJson.length()).map {
                        ThrottleAction.valueOf(actionsJson.getString(it))
                    }

                    events.addLast(ThermalEvent(
                        timestamp = timestamp,
                        source = ThermalSource.valueOf(obj.getString("source")),
                        oldLevel = ThermalLevel.valueOf(obj.getString("oldLevel")),
                        newLevel = ThermalLevel.valueOf(obj.getString("newLevel")),
                        temperatureC = obj.getDouble("temperature").toFloat(),
                        actionsTriggered = actions
                    ))
                }
            }
        }
    }

    /**
     * Delete history files
     */
    private fun deleteHistoryFiles() {
        File(context.filesDir, HISTORY_FILE).delete()
        File(context.filesDir, EVENTS_FILE).delete()
    }
}
