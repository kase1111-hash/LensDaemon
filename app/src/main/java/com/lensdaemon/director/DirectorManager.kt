package com.lensdaemon.director

import android.content.Context
import com.lensdaemon.camera.LensType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber

/**
 * Director Manager
 *
 * Main coordinator for AI Director functionality. Orchestrates script parsing,
 * shot mapping, cue execution, and take management.
 *
 * Design principles:
 * - Completely inert when disabled (zero thermal impact)
 * - Thermal-aware operation with automatic throttling
 * - Clean separation from camera pipeline
 * - Observable state via StateFlow
 */
class DirectorManager(
    private val context: Context,
    private var config: DirectorConfig = DirectorConfig.DEFAULT
) {
    companion object {
        private const val TAG = "DirectorManager"
    }

    /**
     * Camera control interface for executing commands
     */
    interface CameraController {
        fun switchLens(lens: LensType)
        fun setZoom(level: Float, animated: Boolean = false, durationMs: Long = 0)
        fun setFocusMode(target: FocusTarget)
        fun setExposurePreset(preset: ExposurePreset)
        fun getCurrentTemperature(): Int
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

    // Components
    private val configStore = DirectorConfigStore(context)
    private val scriptParser = ScriptParser(config)
    private val shotMapper = ShotMapper(config)
    private val takeManager = TakeManager(config)

    // State
    private val _state = MutableStateFlow(DirectorState.DISABLED)
    val state: StateFlow<DirectorState> = _state.asStateFlow()

    private val _currentSession = MutableStateFlow<DirectorSession?>(null)
    val currentSession: StateFlow<DirectorSession?> = _currentSession.asStateFlow()

    private val _events = MutableSharedFlow<DirectorEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<DirectorEvent> = _events.asSharedFlow()

    // Controller reference (set when camera service binds)
    private var cameraController: CameraController? = null

    // Coroutine scope for director operations
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var cueExecutionJob: Job? = null

    // Thermal monitoring
    private var thermalCheckJob: Job? = null
    private var lastThermalCheck = 0L

    init {
        // Load saved config
        config = configStore.loadConfig()
        if (config.enabled) {
            _state.value = DirectorState.IDLE
        }
    }

    /**
     * Enable AI Director
     */
    fun enable() {
        if (_state.value == DirectorState.DISABLED) {
            _state.value = DirectorState.IDLE
            config = config.copy(enabled = true)
            configStore.saveConfig(config)
            startThermalMonitoring()
            emitEvent(DirectorEvent.StateChanged(DirectorState.IDLE))
            Timber.tag(TAG).i("AI Director enabled")
        }
    }

    /**
     * Disable AI Director (returns to inert state)
     */
    fun disable() {
        stopExecution()
        stopThermalMonitoring()
        _currentSession.value = null
        _state.value = DirectorState.DISABLED
        config = config.copy(enabled = false)
        configStore.saveConfig(config)
        emitEvent(DirectorEvent.StateChanged(DirectorState.DISABLED))
        Timber.tag(TAG).i("AI Director disabled (inert)")
    }

    /**
     * Check if director is enabled
     */
    fun isEnabled(): Boolean = _state.value != DirectorState.DISABLED

    /**
     * Set camera controller for command execution
     */
    fun setCameraController(controller: CameraController?) {
        cameraController = controller
        controller?.let {
            // Update shot mapper with camera capabilities
            // (In real implementation, get capabilities from controller)
        }
    }

    /**
     * Load and parse a script
     */
    fun loadScript(scriptText: String): Result<ParsedScript> {
        if (_state.value == DirectorState.DISABLED) {
            return Result.failure(IllegalStateException("Director is disabled"))
        }

        _state.value = DirectorState.PARSING
        emitEvent(DirectorEvent.StateChanged(DirectorState.PARSING))

        return try {
            val parsedScript = scriptParser.parseScript(scriptText)
            val errors = scriptParser.getErrors()
            val warnings = scriptParser.validateScript(parsedScript)

            if (parsedScript.scenes.isEmpty() || parsedScript.totalCues == 0) {
                _state.value = DirectorState.IDLE
                return Result.failure(IllegalArgumentException("Script contains no valid scenes or cues"))
            }

            // Validate against camera capabilities
            val mappingIssues = shotMapper.validateMapping(parsedScript)
            if (mappingIssues.isNotEmpty()) {
                Timber.tag(TAG).w("Script mapping issues: $mappingIssues")
            }

            // Create new session
            _currentSession.value = DirectorSession(
                script = parsedScript,
                currentSceneIndex = 0,
                currentCueIndex = 0,
                state = DirectorState.READY
            )

            _state.value = DirectorState.READY
            emitEvent(DirectorEvent.ScriptLoaded(parsedScript))
            emitEvent(DirectorEvent.StateChanged(DirectorState.READY))

            Timber.tag(TAG).i(
                "Script loaded: ${parsedScript.scenes.size} scenes, ${parsedScript.totalCues} cues, " +
                        "est. duration: ${parsedScript.estimatedDurationFormatted}"
            )

            Result.success(parsedScript)
        } catch (e: Exception) {
            _state.value = DirectorState.IDLE
            emitEvent(DirectorEvent.Error("Failed to parse script: ${e.message}"))
            Timber.tag(TAG).e(e, "Script parsing failed")
            Result.failure(e)
        }
    }

    /**
     * Start executing the loaded script
     */
    fun startExecution(): Boolean {
        val session = _currentSession.value ?: run {
            emitEvent(DirectorEvent.Error("No script loaded"))
            return false
        }

        if (_state.value != DirectorState.READY && _state.value != DirectorState.PAUSED) {
            emitEvent(DirectorEvent.Error("Cannot start: invalid state ${_state.value}"))
            return false
        }

        // Check thermal state
        if (!checkThermalState()) {
            return false
        }

        _state.value = DirectorState.RUNNING
        emitEvent(DirectorEvent.StateChanged(DirectorState.RUNNING))

        // Start first take
        val currentScene = session.currentScene
        if (currentScene != null && config.autoTakeSeparation) {
            val take = takeManager.startTake(currentScene.id, currentScene.label)
            emitEvent(DirectorEvent.TakeStarted(take))
        }

        // Start cue execution loop
        startCueExecutionLoop()

        Timber.tag(TAG).i("Script execution started")
        return true
    }

    /**
     * Pause execution
     */
    fun pauseExecution() {
        if (_state.value == DirectorState.RUNNING) {
            cueExecutionJob?.cancel()
            _state.value = DirectorState.PAUSED
            emitEvent(DirectorEvent.StateChanged(DirectorState.PAUSED))
            Timber.tag(TAG).i("Execution paused")
        }
    }

    /**
     * Resume execution
     */
    fun resumeExecution(): Boolean {
        if (_state.value == DirectorState.PAUSED) {
            return startExecution()
        }
        return false
    }

    /**
     * Stop execution and end current take
     */
    fun stopExecution() {
        cueExecutionJob?.cancel()
        cueExecutionJob = null

        // End current take
        if (takeManager.isRecording()) {
            val take = takeManager.endTake()
            take?.let { emitEvent(DirectorEvent.TakeEnded(it)) }
        }

        if (_state.value != DirectorState.DISABLED) {
            _state.value = if (_currentSession.value != null) DirectorState.READY else DirectorState.IDLE
            emitEvent(DirectorEvent.StateChanged(_state.value))
        }

        Timber.tag(TAG).i("Execution stopped")
    }

    /**
     * Execute a single cue manually
     */
    fun executeCue(cue: DirectorCue): Boolean {
        if (_state.value == DirectorState.DISABLED) {
            return false
        }

        val result = shotMapper.mapCue(cue)
        if (!result.success || result.command == null) {
            emitEvent(DirectorEvent.CueExecuted(cue, false))
            return false
        }

        val success = executeCommand(result.command)
        emitEvent(DirectorEvent.CueExecuted(cue, success))

        if (takeManager.isRecording()) {
            takeManager.recordCueExecution(
                cueId = cue.id,
                expectedTimeMs = System.currentTimeMillis(),
                success = success
            )
        }

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
     * Advance to next cue
     */
    fun advanceCue(): DirectorCue? {
        val session = _currentSession.value ?: return null
        val currentScene = session.currentScene ?: return null

        val nextCueIndex = session.currentCueIndex + 1
        if (nextCueIndex < currentScene.cues.size) {
            _currentSession.value = session.copy(currentCueIndex = nextCueIndex)
            return currentScene.cues[nextCueIndex]
        } else {
            // Move to next scene
            return advanceScene()?.cues?.firstOrNull()
        }
    }

    /**
     * Advance to next scene
     */
    fun advanceScene(): DirectorScene? {
        val session = _currentSession.value ?: return null

        // End current take
        if (config.autoTakeSeparation && takeManager.isRecording()) {
            val take = takeManager.endTake()
            take?.let { emitEvent(DirectorEvent.TakeEnded(it)) }
        }

        val nextSceneIndex = session.currentSceneIndex + 1
        if (nextSceneIndex < session.script.scenes.size) {
            val nextScene = session.script.scenes[nextSceneIndex]
            _currentSession.value = session.copy(
                currentSceneIndex = nextSceneIndex,
                currentCueIndex = 0
            )

            // Start new take for new scene
            if (config.autoTakeSeparation && _state.value == DirectorState.RUNNING) {
                val take = takeManager.startTake(nextScene.id, nextScene.label)
                emitEvent(DirectorEvent.TakeStarted(take))
            }

            return nextScene
        }

        // No more scenes
        stopExecution()
        return null
    }

    /**
     * Jump to specific scene
     */
    fun jumpToScene(sceneIndex: Int): Boolean {
        val session = _currentSession.value ?: return false
        if (sceneIndex < 0 || sceneIndex >= session.script.scenes.size) return false

        // End current take if recording
        if (config.autoTakeSeparation && takeManager.isRecording()) {
            val take = takeManager.endTake()
            take?.let { emitEvent(DirectorEvent.TakeEnded(it)) }
        }

        _currentSession.value = session.copy(
            currentSceneIndex = sceneIndex,
            currentCueIndex = 0
        )

        // Start new take
        val scene = session.script.scenes[sceneIndex]
        if (config.autoTakeSeparation && _state.value == DirectorState.RUNNING) {
            val take = takeManager.startTake(scene.id, scene.label)
            emitEvent(DirectorEvent.TakeStarted(take))
        }

        Timber.tag(TAG).d("Jumped to scene $sceneIndex: ${scene.label}")
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
     * Update configuration
     */
    fun updateConfig(newConfig: DirectorConfig) {
        val wasEnabled = config.enabled
        config = newConfig
        configStore.saveConfig(config)

        if (!wasEnabled && config.enabled) {
            enable()
        } else if (wasEnabled && !config.enabled) {
            disable()
        }
    }

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

    // Private implementation

    private fun startCueExecutionLoop() {
        cueExecutionJob = scope.launch {
            while (isActive && _state.value == DirectorState.RUNNING) {
                val session = _currentSession.value ?: break
                val cue = session.currentCue

                if (cue != null) {
                    // Execute current cue
                    val result = shotMapper.mapCue(cue)
                    val success = if (result.success && result.command != null) {
                        executeCommand(result.command)
                    } else false

                    emitEvent(DirectorEvent.CueExecuted(cue, success))
                    takeManager.recordCueExecution(cue.id, System.currentTimeMillis(), success)

                    // Wait for cue duration
                    val waitTime = when (cue.type) {
                        CueType.TRANSITION -> cue.transitionDurationMs
                        CueType.BEAT -> if (cue.holdDurationMs > 0) cue.holdDurationMs else config.defaultHoldDurationMs
                        else -> 0L
                    }

                    if (waitTime > 0) {
                        delay(waitTime)
                    }

                    // Advance to next cue
                    advanceCue() ?: break
                } else {
                    // No more cues, try next scene
                    if (advanceScene() == null) break
                }

                // Check thermal periodically
                checkThermalState()
            }

            // Execution complete
            if (_state.value == DirectorState.RUNNING) {
                stopExecution()
                Timber.tag(TAG).i("Script execution complete")
            }
        }
    }

    private fun executeCommand(command: ShotMapper.CameraCommand): Boolean {
        val controller = cameraController ?: run {
            Timber.tag(TAG).w("No camera controller set, command not executed")
            return false
        }

        return try {
            // Execute lens switch if needed
            controller.switchLens(command.lens)

            // Execute zoom
            val animated = command.transitionType in listOf(
                TransitionType.PUSH_IN,
                TransitionType.PULL_BACK
            )
            controller.setZoom(command.zoomLevel, animated, command.transitionDurationMs)

            // Set focus mode
            controller.setFocusMode(command.focusMode)

            // Set exposure preset
            controller.setExposurePreset(command.exposurePreset)

            Timber.tag(TAG).d("Executed command: ${command.notes}")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to execute command")
            false
        }
    }

    private fun checkThermalState(): Boolean {
        if (!config.thermalAutoDisable) return true

        val now = System.currentTimeMillis()
        if (now - lastThermalCheck < 5000) return _state.value != DirectorState.THERMAL_HOLD
        lastThermalCheck = now

        val temp = cameraController?.getCurrentTemperature() ?: return true

        when {
            temp >= config.thermalThresholdDisable -> {
                Timber.tag(TAG).w("Thermal threshold exceeded ($temp°C), disabling director")
                _state.value = DirectorState.THERMAL_HOLD
                stopExecution()
                emitEvent(DirectorEvent.ThermalWarning(temp, "Director disabled due to high temperature"))
                emitEvent(DirectorEvent.StateChanged(DirectorState.THERMAL_HOLD))
                return false
            }
            temp >= config.thermalThresholdInference && config.inferenceMode == InferenceMode.REMOTE -> {
                Timber.tag(TAG).w("Thermal warning ($temp°C), disabling remote inference")
                emitEvent(DirectorEvent.ThermalWarning(temp, "Remote inference disabled due to temperature"))
            }
        }

        return true
    }

    private fun startThermalMonitoring() {
        if (config.thermalAutoDisable) {
            thermalCheckJob = scope.launch {
                while (isActive) {
                    checkThermalState()
                    delay(10_000) // Check every 10 seconds
                }
            }
        }
    }

    private fun stopThermalMonitoring() {
        thermalCheckJob?.cancel()
        thermalCheckJob = null
    }

    private fun emitEvent(event: DirectorEvent) {
        scope.launch {
            _events.emit(event)
        }
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        disable()
        scope.cancel()
    }
}
