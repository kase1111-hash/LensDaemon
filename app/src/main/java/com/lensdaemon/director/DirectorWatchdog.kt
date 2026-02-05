package com.lensdaemon.director

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Watchdog configuration for autonomous operation
 */
data class WatchdogConfig(
    val enabled: Boolean = true,
    val stallTimeoutMs: Long = 60_000,
    val maxConsecutiveErrors: Int = 5,
    val llmHealthCheckIntervalMs: Long = 300_000, // 5 minutes
    val autoResumeThermalHold: Boolean = true,
    val scriptQueueEnabled: Boolean = true
)

/**
 * Watchdog runtime statistics
 */
data class WatchdogStats(
    val stallsDetected: Int = 0,
    val errorsRecovered: Int = 0,
    val llmDowngrades: Int = 0,
    val llmUpgrades: Int = 0,
    val thermalHolds: Int = 0,
    val totalThermalHoldMs: Long = 0,
    val scriptsCompleted: Int = 0,
    val queueSize: Int = 0
)

/**
 * Events emitted by the watchdog for external observers
 */
sealed class WatchdogEvent {
    data class StallDetected(val lastCueMs: Long) : WatchdogEvent()
    data class ErrorRecovered(val error: String, val consecutiveErrors: Int) : WatchdogEvent()
    data class LlmDowngraded(val reason: String) : WatchdogEvent()
    data class LlmUpgraded(val endpoint: String) : WatchdogEvent()
    data class ThermalResumed(val holdDurationMs: Long) : WatchdogEvent()
    data class ScriptQueueAdvanced(val remainingScripts: Int) : WatchdogEvent()
    data class MaxErrorsReached(val count: Int) : WatchdogEvent()
}

/**
 * Director Watchdog
 *
 * Auto-recovery and health monitoring system for autonomous AI Director operation.
 * Handles stall detection, error recovery, LLM endpoint health checks,
 * thermal hold resumption, and sequential script queue execution.
 *
 * Designed for "minimal-interaction" deployments where the director should
 * keep running without human intervention.
 */
class DirectorWatchdog(
    private val directorManager: DirectorManager,
    private val llmClient: RemoteLlmClient? = null,
    private var config: WatchdogConfig = WatchdogConfig()
) {
    companion object {
        private const val TAG = "DirectorWatchdog"
        private const val STALL_CHECK_INTERVAL_MS = 5_000L
        private const val THERMAL_CHECK_INTERVAL_MS = 10_000L
        private const val SCRIPT_COMPLETE_CHECK_INTERVAL_MS = 2_000L
    }

    // Coroutine scope with SupervisorJob so child failures don't cancel siblings
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Event emission
    private val _events = MutableSharedFlow<WatchdogEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<WatchdogEvent> = _events.asSharedFlow()

    // Monitoring jobs
    private var stallMonitorJob: Job? = null
    private var llmHealthJob: Job? = null
    private var thermalResumeJob: Job? = null
    private var scriptQueueJob: Job? = null
    private var stateObserverJob: Job? = null

    // Stall detection
    private val lastCueExecutedMs = AtomicLong(0L)

    // Error tracking
    private val consecutiveErrors = AtomicInteger(0)
    private var errorsPaused = false

    // LLM health
    private var llmHealthy = true
    private var originalInferenceMode: InferenceMode? = null

    // Thermal hold tracking
    private var thermalHoldStartMs = 0L
    private var totalThermalHoldMs = AtomicLong(0L)

    // Stats
    private val stallsDetected = AtomicInteger(0)
    private val errorsRecovered = AtomicInteger(0)
    private val llmDowngrades = AtomicInteger(0)
    private val llmUpgrades = AtomicInteger(0)
    private val thermalHolds = AtomicInteger(0)
    private val scriptsCompleted = AtomicInteger(0)

    // Script queue
    private val scriptQueue = ConcurrentLinkedQueue<String>()

    /**
     * Start watchdog monitoring.
     * Launches all monitoring loops based on configuration.
     */
    fun start() {
        if (!config.enabled) {
            Timber.tag(TAG).d("Watchdog disabled by configuration")
            return
        }

        stop() // Clean up any existing jobs

        Timber.tag(TAG).i("Starting watchdog monitoring")

        // Observe director events for cue execution tracking and error recovery
        stateObserverJob = scope.launch {
            directorManager.events.collect { event ->
                when (event) {
                    is DirectorManager.DirectorEvent.CueExecuted -> {
                        if (event.success) {
                            lastCueExecutedMs.set(System.currentTimeMillis())
                            consecutiveErrors.set(0)
                            errorsPaused = false
                        } else {
                            handleCueError("Cue execution failed: ${event.cue.rawText}")
                        }
                    }
                    is DirectorManager.DirectorEvent.Error -> {
                        handleCueError(event.message)
                    }
                    is DirectorManager.DirectorEvent.StateChanged -> {
                        onStateChanged(event.state)
                    }
                    else -> { /* no-op */ }
                }
            }
        }

        // Stall detection loop
        stallMonitorJob = scope.launch {
            while (isActive) {
                delay(STALL_CHECK_INTERVAL_MS)
                checkForStall()
            }
        }

        // LLM health check loop (only if remote inference is configured)
        if (llmClient != null) {
            llmHealthJob = scope.launch {
                while (isActive) {
                    delay(config.llmHealthCheckIntervalMs)
                    checkLlmHealth()
                }
            }
        }

        // Thermal resume loop
        if (config.autoResumeThermalHold) {
            thermalResumeJob = scope.launch {
                while (isActive) {
                    delay(THERMAL_CHECK_INTERVAL_MS)
                    checkThermalResume()
                }
            }
        }

        // Script queue processing loop
        if (config.scriptQueueEnabled) {
            scriptQueueJob = scope.launch {
                while (isActive) {
                    delay(SCRIPT_COMPLETE_CHECK_INTERVAL_MS)
                    checkScriptQueueAdvance()
                }
            }
        }
    }

    /**
     * Stop all watchdog monitoring.
     */
    fun stop() {
        stateObserverJob?.cancel()
        stallMonitorJob?.cancel()
        llmHealthJob?.cancel()
        thermalResumeJob?.cancel()
        scriptQueueJob?.cancel()

        stateObserverJob = null
        stallMonitorJob = null
        llmHealthJob = null
        thermalResumeJob = null
        scriptQueueJob = null

        Timber.tag(TAG).i("Watchdog monitoring stopped")
    }

    /**
     * Update watchdog configuration. Restarts monitoring if already active.
     */
    fun updateConfig(newConfig: WatchdogConfig) {
        val wasRunning = stallMonitorJob?.isActive == true
        config = newConfig
        if (wasRunning) {
            start()
        }
    }

    // ---- Stall Detection ----

    private fun checkForStall() {
        val status = directorManager.getStatus()
        if (status.state != DirectorState.RUNNING) return

        val lastCue = lastCueExecutedMs.get()
        if (lastCue == 0L) return // No cue has been executed yet

        val elapsed = System.currentTimeMillis() - lastCue
        if (elapsed >= config.stallTimeoutMs) {
            stallsDetected.incrementAndGet()
            Timber.tag(TAG).w("Stall detected: no cue executed for ${elapsed}ms, auto-advancing")

            emitEvent(WatchdogEvent.StallDetected(lastCue))

            // Auto-advance to next cue
            val nextCue = directorManager.advanceCue()
            if (nextCue != null) {
                lastCueExecutedMs.set(System.currentTimeMillis())
                Timber.tag(TAG).i("Auto-advanced to cue: ${nextCue.rawText}")
            } else {
                Timber.tag(TAG).i("No more cues to advance to, script may be complete")
            }
        }
    }

    // ---- Error Recovery ----

    private fun handleCueError(error: String) {
        val count = consecutiveErrors.incrementAndGet()
        errorsRecovered.incrementAndGet()

        Timber.tag(TAG).w("Cue error ($count/${config.maxConsecutiveErrors}): $error")
        emitEvent(WatchdogEvent.ErrorRecovered(error, count))

        if (count >= config.maxConsecutiveErrors) {
            Timber.tag(TAG).e("Max consecutive errors reached ($count), pausing execution")
            errorsPaused = true
            directorManager.pauseExecution()
            emitEvent(WatchdogEvent.MaxErrorsReached(count))
        } else {
            // Skip the failed cue and continue with next
            val status = directorManager.getStatus()
            if (status.state == DirectorState.RUNNING) {
                val nextCue = directorManager.advanceCue()
                if (nextCue != null) {
                    lastCueExecutedMs.set(System.currentTimeMillis())
                    Timber.tag(TAG).i("Skipped failed cue, advanced to: ${nextCue.rawText}")
                }
            }
        }
    }

    // ---- LLM Health Check ----

    private suspend fun checkLlmHealth() {
        val client = llmClient ?: return
        if (!client.isConfigured()) return

        val status = directorManager.getStatus()
        // Only relevant when director is enabled
        if (!status.enabled) return

        try {
            val result = client.testConnection()
            if (result.isSuccess) {
                if (!llmHealthy) {
                    // Endpoint recovered - upgrade back to REMOTE if we downgraded
                    llmHealthy = true
                    val original = originalInferenceMode
                    if (original == InferenceMode.REMOTE) {
                        llmUpgrades.incrementAndGet()
                        val currentConfig = DirectorConfig(
                            inferenceMode = InferenceMode.REMOTE
                        )
                        directorManager.updateConfig(currentConfig)
                        Timber.tag(TAG).i("LLM endpoint recovered, upgraded back to REMOTE mode")
                        emitEvent(WatchdogEvent.LlmUpgraded(client.toString()))
                    }
                    originalInferenceMode = null
                }
            } else {
                handleLlmUnreachable("Health check failed: ${result.exceptionOrNull()?.message}")
            }
        } catch (e: Exception) {
            handleLlmUnreachable("Health check exception: ${e.message}")
        }
    }

    private fun handleLlmUnreachable(reason: String) {
        if (llmHealthy) {
            llmHealthy = false
            val status = directorManager.getStatus()
            if (status.inferenceMode == InferenceMode.REMOTE) {
                originalInferenceMode = InferenceMode.REMOTE
                llmDowngrades.incrementAndGet()
                val currentConfig = DirectorConfig(
                    inferenceMode = InferenceMode.PRE_PARSED
                )
                directorManager.updateConfig(currentConfig)
                Timber.tag(TAG).w("LLM endpoint unreachable, downgraded to PRE_PARSED mode: $reason")
                emitEvent(WatchdogEvent.LlmDowngraded(reason))
            }
        }
    }

    // ---- Thermal Hold Resumption ----

    private fun onStateChanged(state: DirectorState) {
        when (state) {
            DirectorState.THERMAL_HOLD -> {
                thermalHoldStartMs = System.currentTimeMillis()
                thermalHolds.incrementAndGet()
                Timber.tag(TAG).i("Director entered thermal hold")
            }
            DirectorState.RUNNING -> {
                // Reset stall timer when execution starts/resumes
                lastCueExecutedMs.set(System.currentTimeMillis())
            }
            else -> { /* no-op */ }
        }
    }

    private fun checkThermalResume() {
        if (!config.autoResumeThermalHold) return

        val status = directorManager.getStatus()
        if (status.state != DirectorState.THERMAL_HOLD) return

        // Check if temperature has dropped below threshold by attempting to resume.
        // DirectorManager.startExecution() internally checks thermal state and will
        // only succeed if temperature is acceptable.
        val resumed = directorManager.startExecution()
        if (resumed) {
            val holdDuration = System.currentTimeMillis() - thermalHoldStartMs
            totalThermalHoldMs.addAndGet(holdDuration)
            Timber.tag(TAG).i("Resumed from thermal hold after ${holdDuration}ms")
            emitEvent(WatchdogEvent.ThermalResumed(holdDuration))
        }
    }

    // ---- Script Queue ----

    /**
     * Enqueue a script for sequential execution after the current script completes.
     */
    fun enqueueScript(script: String) {
        scriptQueue.add(script)
        Timber.tag(TAG).i("Script enqueued, queue size: ${scriptQueue.size}")
    }

    /**
     * Clear all queued scripts.
     */
    fun clearQueue() {
        scriptQueue.clear()
        Timber.tag(TAG).i("Script queue cleared")
    }

    /**
     * Get the number of scripts waiting in the queue.
     */
    fun getQueueSize(): Int = scriptQueue.size

    private fun checkScriptQueueAdvance() {
        if (!config.scriptQueueEnabled) return
        if (scriptQueue.isEmpty()) return

        val status = directorManager.getStatus()
        // Advance queue when director is IDLE (script finished) or READY (stopped)
        // but not when RUNNING, PAUSED, THERMAL_HOLD, PARSING, or DISABLED
        if (status.state != DirectorState.IDLE && status.state != DirectorState.READY) return

        // Don't advance if there's an active script that hasn't been started
        if (status.state == DirectorState.READY && status.hasScript) return

        val nextScript = scriptQueue.poll() ?: return
        val remaining = scriptQueue.size
        scriptsCompleted.incrementAndGet()

        Timber.tag(TAG).i("Advancing script queue, $remaining scripts remaining")

        val result = directorManager.loadScript(nextScript)
        if (result.isSuccess) {
            directorManager.startExecution()
            lastCueExecutedMs.set(System.currentTimeMillis())
            emitEvent(WatchdogEvent.ScriptQueueAdvanced(remaining))
        } else {
            Timber.tag(TAG).e("Failed to load queued script: ${result.exceptionOrNull()?.message}")
            // Try next script in queue on failure
            if (scriptQueue.isNotEmpty()) {
                Timber.tag(TAG).i("Skipping failed script, trying next in queue")
            }
        }
    }

    // ---- Stats & Utilities ----

    /**
     * Get current watchdog statistics.
     */
    fun getStats(): WatchdogStats = WatchdogStats(
        stallsDetected = stallsDetected.get(),
        errorsRecovered = errorsRecovered.get(),
        llmDowngrades = llmDowngrades.get(),
        llmUpgrades = llmUpgrades.get(),
        thermalHolds = thermalHolds.get(),
        totalThermalHoldMs = totalThermalHoldMs.get(),
        scriptsCompleted = scriptsCompleted.get(),
        queueSize = scriptQueue.size
    )

    /**
     * Reset all statistics counters.
     */
    fun resetStats() {
        stallsDetected.set(0)
        errorsRecovered.set(0)
        llmDowngrades.set(0)
        llmUpgrades.set(0)
        thermalHolds.set(0)
        totalThermalHoldMs.set(0)
        scriptsCompleted.set(0)
        consecutiveErrors.set(0)
        errorsPaused = false
    }

    /**
     * Check if the watchdog has paused execution due to max errors.
     */
    fun isErrorPaused(): Boolean = errorsPaused

    /**
     * Acknowledge errors and allow resumption after max errors pause.
     * Resets the consecutive error counter.
     */
    fun acknowledgeErrors() {
        consecutiveErrors.set(0)
        errorsPaused = false
        Timber.tag(TAG).i("Errors acknowledged, consecutive error count reset")
    }

    /**
     * Check if the LLM endpoint is currently healthy.
     */
    fun isLlmHealthy(): Boolean = llmHealthy

    private fun emitEvent(event: WatchdogEvent) {
        scope.launch {
            _events.emit(event)
        }
    }

    /**
     * Clean up all resources and cancel coroutines.
     */
    fun destroy() {
        stop()
        scriptQueue.clear()
        scope.cancel()
        Timber.tag(TAG).i("Watchdog destroyed")
    }
}
