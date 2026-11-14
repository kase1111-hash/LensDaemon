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
import kotlinx.coroutines.runBlocking
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
 * - Minute-by-minute granularity, with the levels the governor uses ([levelSource])
 * - Event log of the governor's level changes ([recordEvent] is called by the governor only)
 * - Statistics calculation
 * - Persistence across app restarts: saved every 10 entries and when released
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
        private const val SAVE_EVERY_ENTRIES = 10  // About every 10 minutes
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordJob: Job? = null

    // History storage
    private val history = ConcurrentLinkedDeque<ThermalHistoryEntry>()
    private val events = ConcurrentLinkedDeque<ThermalEvent>()
    private val mutex = Mutex()

    // Serialises file writes (the periodic save, stopRecording and release can overlap)
    private val fileLock = Any()

    // Statistics
    private var statsStartTime = System.currentTimeMillis()

    // Temperature monitor reference
    private var monitor: ThermalMonitor? = null

    /**
     * The governor's current CPU and battery levels, recorded with each entry so the
     * history matches the throttling. Without it, levels are worked out from [config]
     * without hysteresis.
     */
    @Volatile
    var levelSource: (() -> Pair<ThermalLevel, ThermalLevel>)? = null

    // Loads the saved history; recording and saving wait for it, so a late load can't
    // replace new entries and a save can't write a half-loaded history
    private val loadJob: Job = loadHistory()

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
            loadJob.join()
            var sinceSave = 0
            while (isActive) {
                try {
                    if (recordCurrentState()) sinceSave++
                    if (sinceSave >= SAVE_EVERY_ENTRIES) {
                        sinceSave = 0
                        saveFiles()
                    }
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
     * Release resources. Saves the history and events before returning: this blocks the
     * caller for two small file writes. Nothing is saved while the saved history is still
     * loading, since there is nothing new to keep yet and a save would cut the file short.
     */
    fun release() {
        recordJob?.cancel()
        recordJob = null
        if (loadJob.isCompleted) {
            try {
                runBlocking(Dispatchers.IO) { saveFiles() }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error saving thermal history on release")
            }
        }
        scope.cancel()
    }

    /**
     * Record current thermal state
     *
     * @return true when an entry was added
     */
    private suspend fun recordCurrentState(): Boolean {
        val mon = monitor ?: return false

        val cpuTemp = mon.cpuTemperature.value
        val batteryTemp = mon.batteryTemperature.value
        val (cpuLevel, batteryLevel) = levelSource?.invoke()
            ?: (calculateLevel(cpuTemp, config.cpuConfig.getThresholds()) to
                calculateLevel(batteryTemp, config.batteryConfig.getThresholds()))

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
        return true
    }

    /**
     * Calculate thermal level from temperature, without hysteresis (fallback when no
     * [levelSource] is set)
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
     * Record a thermal event (the governor calls this for each level change)
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
     * Save history to disk in the background (once the saved history has been loaded)
     */
    private fun saveHistory() {
        scope.launch {
            loadJob.join()
            try {
                saveFiles()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error saving thermal history")
            }
        }
    }

    /**
     * Write the history and events files. Works on snapshots of the concurrent deques, so
     * it needs no coroutine lock and can run from [release] while the caller is blocked.
     */
    private fun saveFiles() {
        synchronized(fileLock) {
            saveHistoryFile()
            saveEventsFile()
        }
        Timber.tag(TAG).d("Thermal history saved")
    }

    /**
     * Save history entries to file
     */
    private fun saveHistoryFile() {
        val file = File(context.filesDir, HISTORY_FILE)
        val json = JSONArray()

        history.toList().forEach { entry ->
            json.put(JSONObject().apply {
                put("timestamp", entry.timestamp)
                put("cpuTemp", entry.cpuTemperatureC)
                put("batteryTemp", entry.batteryTemperatureC)
                put("cpuLevel", entry.cpuLevel.name)
                put("batteryLevel", entry.batteryLevel.name)
            })
        }

        file.writeText(json.toString())
    }

    /**
     * Save events to file
     */
    private fun saveEventsFile() {
        val file = File(context.filesDir, EVENTS_FILE)
        val json = JSONArray()

        events.toList().forEach { event ->
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

        file.writeText(json.toString())
    }

    /**
     * Load history from disk
     */
    private fun loadHistory(): Job = scope.launch {
        try {
            loadHistoryFile()
            loadEventsFile()
            Timber.tag(TAG).d("Thermal history loaded: ${history.size} entries, ${events.size} events")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error loading thermal history")
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
     * Load events from file. Events the governor logged while the file was loading are
     * kept, after the saved ones.
     */
    private suspend fun loadEventsFile() {
        val file = File(context.filesDir, EVENTS_FILE)
        if (!file.exists()) return

        val json = JSONArray(file.readText())
        val cutoff = System.currentTimeMillis() - (config.historyRetentionHours * 60 * 60 * 1000L)
        val loaded = mutableListOf<ThermalEvent>()

        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            val timestamp = obj.getLong("timestamp")

            if (timestamp >= cutoff) {
                val actionsJson = obj.getJSONArray("actions")
                val actions = (0 until actionsJson.length()).map {
                    ThrottleAction.valueOf(actionsJson.getString(it))
                }

                loaded.add(ThermalEvent(
                    timestamp = timestamp,
                    source = ThermalSource.valueOf(obj.getString("source")),
                    oldLevel = ThermalLevel.valueOf(obj.getString("oldLevel")),
                    newLevel = ThermalLevel.valueOf(obj.getString("newLevel")),
                    temperatureC = obj.getDouble("temperature").toFloat(),
                    actionsTriggered = actions
                ))
            }
        }

        mutex.withLock {
            loaded.asReversed().forEach { events.addFirst(it) }
            while (events.size > MAX_EVENT_ENTRIES) {
                events.removeFirst()
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
