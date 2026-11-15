package com.lensdaemon.director

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import timber.log.Timber

/**
 * Director Manager
 *
 * Main coordinator for AI Director functionality. Orchestrates script parsing,
 * shot mapping, cue execution, and take management.
 *
 * Design principles:
 * - Completely inert when disabled (zero thermal impact)
 * - Thermal-aware: a run that gets as hot as [DirectorConfig.thermalThresholdDisable]
 *   stops in [DirectorState.THERMAL_HOLD], which clears (without resuming) once the
 *   phone is [THERMAL_RESUME_MARGIN_C] °C cooler
 * - Clean separation from camera pipeline
 * - Observable state via StateFlow
 *
 * A script runs in its own coroutine, one cue after another: [BEAT] and [HOLD] wait,
 * [TAKE] starts the next take, and the other cues become camera commands. Stop and the
 * end of the script go back to the first scene; pause and a thermal hold keep the
 * position, and resuming continues the paused take. The public methods are meant for
 * HTTP threads: control methods run one at a time, and those that stop a running
 * script wait for its coroutine to finish.
 */
class DirectorManager internal constructor(
    private val persistence: DirectorConfigPersistence,
    private val thermalMonitorIntervalMs: Long
) {
    /** A director whose settings live in [persistence]. */
    constructor(persistence: DirectorConfigPersistence) : this(persistence, THERMAL_MONITOR_INTERVAL_MS)

    /** A director whose settings live in the app's preferences. */
    constructor(context: Context) : this(DirectorConfigStore(context))

    companion object {
        private const val TAG = "DirectorManager"

        /** A thermal hold clears once the phone is this many °C below [DirectorConfig.thermalThresholdDisable]. */
        const val THERMAL_RESUME_MARGIN_C = 5

        /** Between cues, a run reads the temperature at most this often. */
        private const val THERMAL_CHECK_INTERVAL_MS = 5_000L

        /** How often the temperature is checked while a script runs or is held for heat. */
        private const val THERMAL_MONITOR_INTERVAL_MS = 10_000L

        private const val EVENT_BUFFER = 64
    }

    /**
     * Camera control interface for executing commands
     */
    interface CameraController {
        fun switchLens(lens: String)
        fun setZoom(level: Float, animated: Boolean = false, durationMs: Long = 0)
        fun setFocusMode(target: FocusTarget)

        /**
         * Apply an exposure preset. The director sends a preset other than AUTO with every
         * command while it is in force, and AUTO only when a cue goes back to it. The camera
         * sets the preset wherever its exposure differs, including on a lens that opens after
         * the call: a lens change starts the new lens at 0 EV.
         */
        fun setExposurePreset(preset: ExposurePreset)
        fun getCurrentTemperature(): Int

        /**
         * What the camera can do now (lenses, zoom, focus), for mapping shots; null when
         * unknown. Read when a script loads or starts and before each cue run by hand.
         */
        fun getCapabilities(): ShotMapper.CameraCapabilities? = null
    }

    /**
     * Director event for observers
     */
    sealed class DirectorEvent {
        data class StateChanged(val state: DirectorState) : DirectorEvent()
        data class ScriptLoaded(val script: ParsedScript) : DirectorEvent()
        data class CueExecuted(val cue: DirectorCue, val success: Boolean) : DirectorEvent()
        data class TakeStarted(val take: RecordedTake) : DirectorEvent()
        data class TakeEnded(val take: RecordedTake) : DirectorEvent()
        data class Error(val message: String) : DirectorEvent()
        data class ThermalWarning(val temperature: Int, val action: String) : DirectorEvent()
    }

    /** A place in the script: a scene, and a cue in it. */
    private data class Position(val scene: Int, val cue: Int)

    // Settings first: the components are built with them
    @Volatile
    private var config: DirectorConfig = persistence.loadConfig()

    // Components
    private val scriptParser = ScriptParser(config)
    private val shotMapper = ShotMapper(config)
    private val takeManager = TakeManager(config)

    // State
    private val _state = MutableStateFlow(DirectorState.DISABLED)
    val state: StateFlow<DirectorState> = _state.asStateFlow()

    private val _currentSession = MutableStateFlow<DirectorSession?>(null)
    val currentSession: StateFlow<DirectorSession?> = _currentSession.asStateFlow()

    // Events never wait for listeners: when one falls behind, the oldest events are dropped
    private val _events = MutableSharedFlow<DirectorEvent>(
        extraBufferCapacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<DirectorEvent> = _events.asSharedFlow()

    // Controller reference (set when camera service binds)
    @Volatile
    private var cameraController: CameraController? = null

    // Coroutine scope for director operations
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    /** Makes the control methods run one at a time; the script's coroutine never takes it. */
    private val controlLock = Any()

    /** Guards the state, the session position, [cueExecutionJob] and [runGeneration]; held briefly. */
    private val sessionLock = Any()

    /** Keeps the shot mapper and the camera calls of one cue together. */
    private val cameraLock = Any()

    /** The coroutine running the script (under [sessionLock]). */
    private var cueExecutionJob: Job? = null

    /** Counts started and stopped runs (under [sessionLock]); a run with an old number can't move the session. */
    private var runGeneration = 0

    /**
     * The exposure preset the director last sent (under [cameraLock]). AUTO goes to the camera
     * only when a cue changes the preset back to it, so a script without [EXPOSURE] cues never
     * undoes an exposure set on the dashboard. Any other preset goes with every command, and
     * the camera puts it back where it was lost: a lens change starts the new lens at 0 EV.
     */
    private var lastAppliedExposure = ExposurePreset.AUTO

    // Thermal monitoring
    @Volatile
    private var thermalCheckJob: Job? = null

    /** When the temperature was last read, on [TakeManager.monotonicNowMs]'s clock. */
    @Volatile
    private var lastThermalCheckMs = 0L

    @Volatile
    private var lastTemperatureC: Int? = null

    init {
        // A pause doesn't count in the score of the take it keeps open
        takeManager.samplingPaused = { _state.value == DirectorState.PAUSED }
        if (config.enabled) {
            _state.value = DirectorState.IDLE
            startThermalMonitoring()
        }
    }

    /**
     * Enable AI Director
     */
    fun enable() {
        synchronized(controlLock) {
            if (_state.value != DirectorState.DISABLED) return
            _state.value = DirectorState.IDLE
            applyConfig(config.copy(enabled = true))
            startThermalMonitoring()
        }
        emitEvent(DirectorEvent.StateChanged(DirectorState.IDLE))
        Timber.tag(TAG).i("AI Director enabled")
    }

    /**
     * Disable AI Director (returns to inert state): a running script stops, its take ends
     * and the script is unloaded. Saves the director as disabled.
     */
    fun disable() {
        synchronized(controlLock) {
            stopRunAndWait()
            stopThermalMonitoring()
            synchronized(sessionLock) {
                _currentSession.value = null
                _state.value = DirectorState.DISABLED
            }
            resetCameraState()
            applyConfig(config.copy(enabled = false))
        }
        emitEvent(DirectorEvent.StateChanged(DirectorState.DISABLED))
        Timber.tag(TAG).i("AI Director disabled (inert)")
    }

    /**
     * Check if director is enabled
     */
    fun isEnabled(): Boolean = _state.value != DirectorState.DISABLED

    /**
     * Set camera controller for command execution; the shot mapper takes its capabilities
     */
    fun setCameraController(controller: CameraController?) {
        cameraController = controller
        refreshCapabilities()
    }

    /**
     * Load and parse a script. Refused while a script runs or is paused, and when the text
     * holds no cues; a refused load keeps the previous script. The loaded script's errors
     * list the parser's problems, scenes without cues and shots the camera can't do.
     */
    fun loadScript(scriptText: String): Result<ParsedScript> {
        loadRefusal()?.let { return Result.failure(it) }

        // Parsed before taking the control lock: a long script doesn't hold up the other controls
        val parsed = try {
            scriptParser.parseScript(scriptText)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Script parsing failed")
            return Result.failure(IllegalArgumentException("Could not read the script: ${e.message}", e))
        }
        if (parsed.scenes.isEmpty() || parsed.totalCues == 0) {
            val reason = (listOf("Script contains no valid scenes or cues") + parsed.errors).joinToString("; ")
            return Result.failure(IllegalArgumentException(reason))
        }

        synchronized(controlLock) {
            // The director may have started, or been turned off, while the script was parsed
            loadRefusal()?.let { return Result.failure(it) }

            // Validate against what the camera can do now
            refreshCapabilities()
            val problems = parsed.errors + scriptParser.validateScript(parsed) +
                shotMapper.validateMapping(parsed, firstLineNumber = 1)
            val script = parsed.copy(errors = problems)

            resetCameraState()
            val newState = synchronized(sessionLock) {
                _currentSession.value = DirectorSession(script = script, startTimeMs = System.currentTimeMillis())
                // A thermal hold stays until the phone cools
                if (_state.value != DirectorState.THERMAL_HOLD) _state.value = DirectorState.READY
                _state.value
            }
            emitEvent(DirectorEvent.ScriptLoaded(script))
            emitEvent(DirectorEvent.StateChanged(newState))

            Timber.tag(TAG).i(
                "Script loaded: ${script.scenes.size} scenes, ${script.totalCues} cues, " +
                    "est. duration: ${script.estimatedDurationFormatted}, ${problems.size} problems"
            )
            return Result.success(script)
        }
    }

    /**
     * Start executing the loaded script, or resume a paused one, from the current cue (from
     * the next scene with cues when the current one has none). Refused without a script, in
     * other states, and while the phone is too hot, which is read now: a thermal hold clears
     * here when the phone has cooled enough.
     */
    fun startExecution(): Boolean {
        synchronized(controlLock) {
            if (_currentSession.value == null) return false
            if (_state.value == DirectorState.THERMAL_HOLD && !leaveThermalHoldIfCool()) return false
            val from = _state.value
            if (from != DirectorState.READY && from != DirectorState.PAUSED) return false
            refreshCapabilities()
            if (!checkThermalState(fresh = true)) return false

            synchronized(sessionLock) {
                val session = _currentSession.value ?: return false
                val start = runnablePosition(session)
                if (start == null || _state.value != from) return false
                _currentSession.value = session.copy(currentSceneIndex = start.scene, currentCueIndex = start.cue)
                _state.value = DirectorState.RUNNING
                emitEvent(DirectorEvent.StateChanged(DirectorState.RUNNING))
                continueOrStartTake(session.script.scenes[start.scene])
                startCueExecutionLoop()
            }
        }
        Timber.tag(TAG).i("Script execution started")
        return true
    }

    /**
     * Pause execution: the run stops where it is, and its take stays open for resume.
     * Quality samples taken while paused don't count in that take.
     */
    fun pauseExecution() {
        synchronized(controlLock) {
            if (_state.value != DirectorState.RUNNING) return
            stopRunCoroutine()
            synchronized(sessionLock) {
                // A thermal hold may have come in meanwhile
                if (_state.value != DirectorState.RUNNING) return
                _state.value = DirectorState.PAUSED
            }
        }
        emitEvent(DirectorEvent.StateChanged(DirectorState.PAUSED))
        Timber.tag(TAG).i("Execution paused")
    }

    /**
     * Resume a paused run (or one held for heat, once the phone has cooled)
     */
    fun resumeExecution(): Boolean {
        val current = _state.value
        return if (current == DirectorState.PAUSED || current == DirectorState.THERMAL_HOLD) startExecution() else false
    }

    /**
     * Stop execution: a running or paused script stops, its take ends, and the position goes
     * back to the first scene. A thermal hold stays until the phone cools.
     */
    fun stopExecution() {
        val newState = synchronized(controlLock) {
            stopRunAndWait()
            resetShotMapper()
            synchronized(sessionLock) {
                rewindLocked()
                when (_state.value) {
                    DirectorState.RUNNING, DirectorState.PAUSED, DirectorState.PARSING ->
                        _state.value = if (_currentSession.value != null) DirectorState.READY else DirectorState.IDLE
                    else -> Unit
                }
                _state.value
            }
        }
        if (newState != DirectorState.DISABLED) {
            emitEvent(DirectorEvent.StateChanged(newState))
        }
        Timber.tag(TAG).i("Execution stopped")
    }

    /**
     * Execute a single cue now, by hand: [BEAT] and [HOLD] succeed without waiting, [TAKE]
     * starts the next take, and the other cues become a camera command. A cue run by hand
     * counts in the take in progress, without timing.
     */
    fun executeCue(cue: DirectorCue): Boolean {
        if (_state.value == DirectorState.DISABLED) {
            return false
        }
        refreshCapabilities()

        val success = when (cue.type) {
            CueType.BEAT -> true
            CueType.TAKE -> {
                takeBoundary(generation = null)
                true
            }
            else -> executeCameraCue(cue)
        }
        emitEvent(DirectorEvent.CueExecuted(cue, success))
        takeManager.recordCueExecution(cue.id, expectedTimeMs = null, success = success)
        return success
    }

    /**
     * Execute a single cue from text
     */
    fun executeCueText(cueText: String): Boolean {
        val cue = scriptParser.parseSingleCue(cueText) ?: return false
        return executeCue(cue)
    }

    /**
     * Go to the next cue (the first cue of the next scene with cues, at the end of a scene).
     * While a script runs, that cue runs at once, and going past the last cue ends the run;
     * otherwise only the position moves. Returns the cue, or null when there is none.
     */
    fun nextCue(): DirectorCue? {
        synchronized(controlLock) {
            val running = _state.value == DirectorState.RUNNING
            if (running) stopRunCoroutine()
            synchronized(sessionLock) {
                val session = _currentSession.value ?: return null
                // A thermal hold may have come in while the run stopped
                val stillRunning = running && _state.value == DirectorState.RUNNING
                val next = positionAfter(session)
                if (next == null) {
                    if (stillRunning) completeRun(generation = null)
                    return null
                }
                moveToLocked(session, next, running = stillRunning)
                if (stillRunning) startCueExecutionLoop()
                return session.script.scenes[next.scene].cues[next.cue]
            }
        }
    }

    /**
     * Go to the start of scene [index]. While a script runs, a new take starts and that
     * scene's first cue (or the next scene's, when it has none) runs at once; otherwise
     * only the position moves. False when the loaded script has no such scene.
     */
    fun goToScene(index: Int): Boolean {
        synchronized(controlLock) {
            val scenes = _currentSession.value?.script?.scenes ?: return false
            if (index !in scenes.indices) return false
            val running = _state.value == DirectorState.RUNNING
            if (running) stopRunCoroutine()
            synchronized(sessionLock) {
                val session = _currentSession.value ?: return false
                if (!(running && _state.value == DirectorState.RUNNING)) {
                    moveToLocked(session, Position(index, 0), running = false, newTake = true)
                } else {
                    val scene = firstSceneWithCues(session.script, index)
                    if (scene == null) {
                        completeRun(generation = null)
                    } else {
                        moveToLocked(session, Position(scene, 0), running = true, newTake = true)
                        startCueExecutionLoop()
                    }
                }
            }
        }
        Timber.tag(TAG).d("Jumped to scene $index")
        return true
    }

    /**
     * Mark current take
     */
    fun markCurrentTake(quality: TakeQuality, notes: String = ""): Boolean {
        val takeNumber = takeManager.getCurrentTakeNumber()
        return if (takeNumber > 0) {
            takeManager.markTake(takeNumber, quality, notes)
        } else false
    }

    /**
     * Get current director status
     */
    fun getStatus(): DirectorStatus {
        val session = _currentSession.value
        return DirectorStatus(
            state = _state.value,
            enabled = isEnabled(),
            inferenceMode = config.inferenceMode,
            hasScript = session != null,
            currentScene = session?.currentScene?.label,
            currentCue = session?.currentCue?.rawText,
            nextCue = session?.nextCue?.rawText,
            takeNumber = takeManager.getCurrentTakeNumber(),
            totalCues = session?.script?.totalCues ?: 0,
            executedCues = session?.let { s ->
                var count = 0
                for (i in 0 until s.currentSceneIndex) {
                    count += s.script.scenes[i].cues.size
                }
                count + s.currentCueIndex
            } ?: 0,
            thermalProtectionActive = _state.value == DirectorState.THERMAL_HOLD
        )
    }

    /**
     * Update configuration: saved, passed on to the parser, mapper and take manager, and the
     * thermal monitor starts or stops with enabled and thermalAutoDisable
     */
    fun updateConfig(newConfig: DirectorConfig) {
        synchronized(controlLock) {
            val wasEnabled = isEnabled()
            applyConfig(newConfig)
            when {
                newConfig.enabled && !wasEnabled -> enable()
                !newConfig.enabled && wasEnabled -> disable()
                newConfig.enabled -> {
                    // Without thermal protection nothing would clear a hold
                    if (!newConfig.thermalAutoDisable) leaveThermalHold()
                    startThermalMonitoring()
                }
                else -> Unit
            }
        }
    }

    /**
     * The configuration in force
     */
    fun getConfig(): DirectorConfig = config

    /**
     * The temperature (°C) read at the last thermal check, or null before the first
     */
    fun lastTemperature(): Int? = lastTemperatureC

    /**
     * A thermal hold clears once the phone is below this temperature (°C)
     */
    fun thermalResumeTemperature(): Int = config.thermalThresholdDisable - THERMAL_RESUME_MARGIN_C

    /**
     * Get take manager for direct access
     */
    fun getTakeManager(): TakeManager = takeManager

    /**
     * Get shot mapper for direct access
     */
    fun getShotMapper(): ShotMapper = shotMapper

    /**
     * Record quality metrics (forwarded to take manager)
     */
    fun recordFocusSample(locked: Boolean) = takeManager.recordFocusSample(locked)
    fun recordExposureSample(value: Float) = takeManager.recordExposureSample(value)
    fun recordMotionSample(shakiness: Float) = takeManager.recordMotionSample(shakiness)
    fun recordAudioSample(level: Float) = takeManager.recordAudioSample(level)

    /**
     * Get session duration in seconds: the time since the script was loaded, 0 without one
     */
    fun getSessionDuration(): Long {
        val session = _currentSession.value ?: return 0
        if (session.startTimeMs == 0L) return 0
        return (System.currentTimeMillis() - session.startTimeMs) / 1000
    }

    /**
     * Unload the script: a run stops and its take ends. Recorded takes stay.
     */
    fun clearScript() {
        val newState = synchronized(controlLock) {
            stopRunAndWait()
            resetCameraState()
            synchronized(sessionLock) {
                _currentSession.value = null
                // A thermal hold stays until the phone cools
                if (_state.value != DirectorState.DISABLED && _state.value != DirectorState.THERMAL_HOLD) {
                    _state.value = DirectorState.IDLE
                }
                _state.value
            }
        }
        if (newState != DirectorState.DISABLED) {
            emitEvent(DirectorEvent.StateChanged(newState))
        }
        Timber.tag(TAG).i("Script cleared")
    }

    /**
     * Release the director when its service goes away: a run stops (its take ends) and the
     * monitoring ends. The saved settings stay as they are, enabled included.
     */
    fun destroy() {
        cancelRun()
        endCurrentTake()
        stopThermalMonitoring()
        scope.cancel()
        Timber.tag(TAG).i("Director released")
    }

    // Private implementation

    /**
     * Runs the script from the current cue in a new coroutine. Call it holding
     * [sessionLock], with the state RUNNING.
     */
    private fun startCueExecutionLoop() {
        val generation = ++runGeneration
        cueExecutionJob?.cancel()
        cueExecutionJob = scope.launch {
            // When each cue is meant to run: the start plus the waits before it, on the
            // monotonic clock the take manager times cues with
            var expectedAtMs = TakeManager.monotonicNowMs()
            while (isActive && _state.value == DirectorState.RUNNING) {
                val cue = _currentSession.value?.currentCue
                if (cue == null) {
                    completeRun(generation)
                    break
                }
                val waitMs = runCue(cue, expectedAtMs, generation)
                if (waitMs > 0) {
                    delay(waitMs)
                }
                expectedAtMs += waitMs
                // A run stopped during the cue must not move the session
                ensureActive()
                if (!checkThermalState(fresh = false)) break
                if (!advanceRun(generation)) {
                    completeRun(generation)
                    break
                }
            }
        }
    }

    /**
     * Runs one cue of a script and returns how long the script then waits, in ms. Only a
     * camera cue that could not be mapped or run counts as failed.
     */
    private fun runCue(cue: DirectorCue, expectedAtMs: Long, generation: Int): Long {
        val success = when (cue.type) {
            CueType.BEAT -> true
            CueType.TAKE -> {
                takeBoundary(generation)
                true
            }
            else -> executeCameraCue(cue)
        }
        emitEvent(DirectorEvent.CueExecuted(cue, success))
        takeManager.recordCueExecution(cue.id, expectedAtMs, success)

        return when (cue.type) {
            CueType.TRANSITION -> cue.transitionDurationMs
            CueType.BEAT -> if (cue.holdDurationMs > 0) cue.holdDurationMs else config.defaultHoldDurationMs
            else -> 0L
        }
    }

    /**
     * Maps a camera cue to a command and runs it; false when the cue can't be mapped or the
     * camera call failed
     */
    private fun executeCameraCue(cue: DirectorCue): Boolean = synchronized(cameraLock) {
        val result = shotMapper.mapCue(cue)
        val command = result.command
        if (result.success && command != null) {
            executeCommand(command)
        } else {
            Timber.tag(TAG).w("Cue not mapped: ${cue.rawText} ${result.warnings}")
            false
        }
    }

    /** Runs a camera command (holding [cameraLock]); the exposure preset as [lastAppliedExposure] says. */
    private fun executeCommand(command: ShotMapper.CameraCommand): Boolean {
        val controller = cameraController ?: run {
            Timber.tag(TAG).w("No camera controller set, command not executed")
            return false
        }

        return try {
            // Execute lens switch if needed
            controller.switchLens(command.lens)

            // Execute zoom
            val animated = command.transitionType == TransitionType.PUSH_IN ||
                command.transitionType == TransitionType.PULL_BACK
            controller.setZoom(command.zoomLevel, animated, command.transitionDurationMs)

            // Set focus mode
            controller.setFocusMode(command.focusMode)

            // Exposure: a preset other than AUTO every time, AUTO when a cue goes back to it
            val preset = command.exposurePreset
            if (preset != ExposurePreset.AUTO || lastAppliedExposure != ExposurePreset.AUTO) {
                controller.setExposurePreset(preset)
                lastAppliedExposure = preset
            }

            Timber.tag(TAG).d("Executed command: ${command.notes}")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to execute command")
            false
        }
    }

    /**
     * A [TAKE] cue: the take in progress ends and the next one starts, for the current
     * scene. A take of that scene in which no cue has run yet goes on instead, so a [TAKE]
     * written at the start of a scene doesn't leave an empty take behind. [generation] is
     * the run's (null for a cue run by hand).
     */
    private fun takeBoundary(generation: Int?) {
        synchronized(sessionLock) {
            if (generation != null && generation != runGeneration) return
            val scene = _currentSession.value?.currentScene
            if (scene != null && takeManager.isUnusedTakeOf(scene.id)) return
            endCurrentTake()
            scene?.let { startTakeFor(it) }
        }
    }

    /**
     * Moves a run to its next cue, past scenes without cues (with one take boundary).
     * False at the end of the script, or when the run was stopped meanwhile.
     */
    private fun advanceRun(generation: Int): Boolean = synchronized(sessionLock) {
        if (generation != runGeneration || _state.value != DirectorState.RUNNING) return false
        val session = _currentSession.value ?: return false
        val next = positionAfter(session) ?: return false
        moveToLocked(session, next, running = true)
        true
    }

    /**
     * Ends a run that went past its last cue: the take ends, the position goes back to the
     * first scene and the state to READY. [generation] is the run's; null when the caller
     * already stopped the run's coroutine.
     */
    private fun completeRun(generation: Int?) {
        val newState = synchronized(sessionLock) {
            if (generation != null && generation != runGeneration) return
            if (_state.value != DirectorState.RUNNING) return
            runGeneration++
            endCurrentTake()
            rewindLocked()
            resetShotMapper()
            val done = if (_currentSession.value != null) DirectorState.READY else DirectorState.IDLE
            _state.value = done
            done
        }
        emitEvent(DirectorEvent.StateChanged(newState))
        Timber.tag(TAG).i("Script execution complete")
    }

    /**
     * Sets the position (holding [sessionLock]). Entering another scene, or [newTake], ends
     * the take in progress and, when [running], starts the scene's take.
     */
    private fun moveToLocked(session: DirectorSession, target: Position, running: Boolean, newTake: Boolean = false) {
        _currentSession.value = session.copy(currentSceneIndex = target.scene, currentCueIndex = target.cue)
        if (config.autoTakeSeparation && (newTake || target.scene != session.currentSceneIndex)) {
            endCurrentTake()
            if (running) startTakeFor(session.script.scenes[target.scene])
        }
    }

    /** Back to the first cue of the first scene (holding [sessionLock]). */
    private fun rewindLocked() {
        _currentSession.value = _currentSession.value?.copy(currentSceneIndex = 0, currentCueIndex = 0)
    }

    /** The cue after the current one: the next in the scene, else the first of the next scene with cues; null at the end. */
    private fun positionAfter(session: DirectorSession): Position? {
        val scene = session.currentSceneIndex
        val cuesInScene = session.script.scenes.getOrNull(scene)?.cues?.size ?: 0
        if (session.currentCueIndex + 1 < cuesInScene) {
            return Position(scene, session.currentCueIndex + 1)
        }
        return firstSceneWithCues(session.script, scene + 1)?.let { Position(it, 0) }
    }

    /** Where a run starts: the current cue, else the next scene with cues (from the top when none is left). */
    private fun runnablePosition(session: DirectorSession): Position? {
        if (session.currentCue != null) {
            return Position(session.currentSceneIndex, session.currentCueIndex)
        }
        val scene = firstSceneWithCues(session.script, session.currentSceneIndex)
            ?: firstSceneWithCues(session.script, 0)
            ?: return null
        return Position(scene, 0)
    }

    /** The index of the first scene from [from] on that has cues. */
    private fun firstSceneWithCues(script: ParsedScript, from: Int): Int? =
        (from.coerceAtLeast(0) until script.scenes.size).firstOrNull { script.scenes[it].cues.isNotEmpty() }

    /** A run's take: the paused take of this scene continues; otherwise the next one starts. */
    private fun continueOrStartTake(scene: DirectorScene) {
        if (!config.autoTakeSeparation) return
        if (takeManager.currentTake.value?.sceneId == scene.id) return
        endCurrentTake()
        startTakeFor(scene)
    }

    private fun startTakeFor(scene: DirectorScene) {
        emitEvent(DirectorEvent.TakeStarted(takeManager.startTake(scene.id, scene.label)))
    }

    /** Ends the take in progress, if any, and announces it. */
    private fun endCurrentTake() {
        takeManager.endTake()?.let { emitEvent(DirectorEvent.TakeEnded(it)) }
    }

    /** Cancels the script's coroutine without waiting for it; that run can no longer move the session. */
    private fun cancelRun(): Job? {
        val job = synchronized(sessionLock) {
            runGeneration++
            cueExecutionJob.also { cueExecutionJob = null }
        }
        job?.cancel()
        return job
    }

    /**
     * Cancels the script's coroutine and waits until it has finished its current cue. Only
     * for callers outside that coroutine (HTTP threads), never while holding [sessionLock].
     */
    private fun stopRunCoroutine() {
        cancelRun()?.let { job -> runBlocking { job.join() } }
    }

    /** [stopRunCoroutine], then the take in progress ends. */
    private fun stopRunAndWait() {
        stopRunCoroutine()
        endCurrentTake()
    }

    private fun resetShotMapper() {
        synchronized(cameraLock) {
            shotMapper.reset()
        }
    }

    /** Forgets what the director set on the camera: the mapper starts afresh and exposure counts as AUTO. */
    private fun resetCameraState() {
        synchronized(cameraLock) {
            shotMapper.reset()
            lastAppliedExposure = ExposurePreset.AUTO
        }
    }

    /** Why a script can't be loaded in the current state, or null when it can. */
    private fun loadRefusal(): IllegalStateException? = when (_state.value) {
        DirectorState.DISABLED -> IllegalStateException("The director is off")
        DirectorState.RUNNING, DirectorState.PAUSED -> IllegalStateException("Stop the director before loading another script")
        else -> null
    }

    private fun refreshCapabilities() {
        val capabilities = try {
            cameraController?.getCapabilities()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read the camera's capabilities")
            null
        }
        capabilities?.let { shotMapper.updateCapabilities(it) }
    }

    private fun applyConfig(newConfig: DirectorConfig) {
        config = newConfig
        scriptParser.config = newConfig
        shotMapper.config = newConfig
        takeManager.config = newConfig
        persistence.saveConfig(newConfig)
    }

    /**
     * Reads the temperature (now when [fresh], else at most every few seconds) and stops a
     * run in a thermal hold when it reached the disable threshold. False while held.
     */
    private fun checkThermalState(fresh: Boolean): Boolean {
        if (!config.thermalAutoDisable) return true
        if (_state.value == DirectorState.THERMAL_HOLD) return false
        if (!fresh && TakeManager.monotonicNowMs() - lastThermalCheckMs < THERMAL_CHECK_INTERVAL_MS) return true

        val temp = readTemperature() ?: return true
        if (temp < config.thermalThresholdDisable) return true
        enterThermalHold(temp)
        return false
    }

    private fun readTemperature(): Int? {
        val controller = cameraController ?: return null
        val temp = try {
            controller.getCurrentTemperature()
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Could not read the temperature")
            return null
        }
        lastTemperatureC = temp
        lastThermalCheckMs = TakeManager.monotonicNowMs()
        return temp
    }

    /**
     * Stops in THERMAL_HOLD: a run stops where it is (its take ends) without waiting, as
     * this may be the run's own coroutine. Announced once, when the hold begins.
     */
    private fun enterThermalHold(temp: Int) {
        synchronized(sessionLock) {
            val current = _state.value
            if (current == DirectorState.THERMAL_HOLD || current == DirectorState.DISABLED) return
            _state.value = DirectorState.THERMAL_HOLD
        }
        cancelRun()
        endCurrentTake()
        Timber.tag(TAG).w("Thermal threshold reached ($temp°C): director held until below ${thermalResumeTemperature()}°C")
        emitEvent(
            DirectorEvent.ThermalWarning(temp, "Stopped until the phone cools below ${thermalResumeTemperature()}°C")
        )
        emitEvent(DirectorEvent.StateChanged(DirectorState.THERMAL_HOLD))
    }

    /** Leaves a thermal hold for READY (or IDLE without a script); nothing resumes by itself. */
    private fun leaveThermalHold() {
        val newState = synchronized(sessionLock) {
            if (_state.value != DirectorState.THERMAL_HOLD) return
            val cooled = if (_currentSession.value != null) DirectorState.READY else DirectorState.IDLE
            _state.value = cooled
            cooled
        }
        Timber.tag(TAG).i("Thermal hold cleared at ${lastTemperatureC}°C")
        emitEvent(DirectorEvent.StateChanged(newState))
    }

    /** In a thermal hold: reads the temperature now and leaves the hold when the phone has cooled. False while still too hot. */
    private fun leaveThermalHoldIfCool(): Boolean {
        val temp = readTemperature()
        val cool = !config.thermalAutoDisable || (temp != null && temp < thermalResumeTemperature())
        if (cool) leaveThermalHold()
        return cool
    }

    /** Starts (or restarts) the thermal monitor, when thermal protection is on. */
    private fun startThermalMonitoring() {
        stopThermalMonitoring()
        if (!config.thermalAutoDisable) return
        thermalCheckJob = scope.launch {
            while (isActive) {
                monitorTemperature()
                delay(thermalMonitorIntervalMs)
            }
        }
    }

    private fun stopThermalMonitoring() {
        thermalCheckJob?.cancel()
        thermalCheckJob = null
    }

    /**
     * One monitor check: a hot run stops in a thermal hold, and a hold clears once the phone
     * has cooled. A director that isn't running reads nothing.
     */
    private fun monitorTemperature() {
        when (_state.value) {
            DirectorState.RUNNING -> checkThermalState(fresh = true)
            DirectorState.THERMAL_HOLD -> {
                val temp = readTemperature() ?: return
                if (temp < thermalResumeTemperature()) leaveThermalHold()
            }
            else -> Unit
        }
    }

    private fun emitEvent(event: DirectorEvent) {
        _events.tryEmit(event)
    }
}
