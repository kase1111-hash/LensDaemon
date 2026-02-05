package com.lensdaemon.director

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * Director state
 */
enum class DirectorState {
    DISABLED,       // AI Director completely off (inert, zero thermal impact)
    IDLE,           // Enabled but not running a script
    PARSING,        // Parsing a new script
    READY,          // Script parsed, ready to run
    RUNNING,        // Actively executing cues
    PAUSED,         // Paused mid-script
    THERMAL_HOLD    // Disabled due to thermal protection
}

/**
 * Inference mode for script interpretation
 */
enum class InferenceMode {
    OFF,            // No AI processing, manual cues only
    PRE_PARSED,     // Cues parsed once at script load, no runtime inference
    REMOTE          // External LLM API for dynamic interpretation
}

/**
 * Shot types that can be cued
 */
enum class ShotType {
    ESTABLISHING,   // Wide angle, full scene
    WIDE,           // Wide shot
    FULL_SHOT,      // Subject head-to-toe
    MEDIUM,         // Waist-up
    MEDIUM_CLOSE,   // Chest-up
    CLOSE_UP,       // Head and shoulders
    EXTREME_CLOSE,  // Detail shot
    OVER_SHOULDER,  // POV perspective
    CUSTOM          // User-defined preset
}

/**
 * Transition types for animated camera moves
 */
enum class TransitionType {
    CUT,            // Instant change
    PUSH_IN,        // Zoom in smoothly
    PULL_BACK,      // Zoom out smoothly
    RACK_FOCUS,     // Shift focus between subjects
    HOLD            // Maintain current state
}

/**
 * Focus targets for intelligent focusing
 */
enum class FocusTarget {
    AUTO,           // Camera auto-focus
    FACE,           // Face detection priority
    HANDS,          // Lower frame focus
    OBJECT,         // Specific object (requires tap coordinates)
    BACKGROUND,     // Distant focus
    MANUAL          // Fixed focus distance
}

/**
 * Exposure presets
 */
enum class ExposurePreset {
    AUTO,           // Automatic exposure
    BRIGHT,         // +1 EV compensation
    DARK,           // -1 EV compensation
    BACKLIT,        // +2 EV for backlight compensation
    SILHOUETTE      // -2 EV for silhouette effect
}

/**
 * Cue type enumeration
 */
enum class CueType {
    SCENE,          // Scene marker for organization
    SHOT,           // Shot type change
    TRANSITION,     // Animated camera move
    FOCUS,          // Focus target change
    EXPOSURE,       // Exposure preset
    DEPTH,          // Depth of field hint
    BEAT,           // Timing marker / hold
    TAKE,           // Take boundary marker
    CUT,            // Hard cut to new shot
    CUSTOM          // User-defined cue
}

/**
 * Take quality for manual marking
 */
enum class TakeQuality {
    UNMARKED,       // Not yet reviewed
    GOOD,           // Usable take
    BAD,            // Unusable take
    CIRCLE,         // Best take (print/circle)
    HOLD            // Review later
}

/**
 * A single parsed cue from the script
 */
data class DirectorCue(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: CueType,
    val rawText: String,                    // Original cue text from script
    val shotType: ShotType? = null,
    val transitionType: TransitionType? = null,
    val transitionDurationMs: Long = 1000,
    val focusTarget: FocusTarget? = null,
    val exposurePreset: ExposurePreset? = null,
    val holdDurationMs: Long = 0,           // For BEAT/HOLD cues
    val sceneLabel: String? = null,         // For SCENE cues
    val takeNumber: Int? = null,            // For TAKE cues
    val notes: String = "",                 // Additional context
    val lineNumber: Int = 0,                // Position in script
    val timestamp: Long = 0                 // Execution timestamp (0 if not executed)
)

/**
 * Shot preset mapping shot descriptions to camera settings
 */
data class ShotPreset(
    val name: String,
    val shotType: ShotType,
    val lens: String,           // "wide", "main", "telephoto"
    val zoom: Float,            // Zoom level (1.0 = no zoom)
    val focusMode: FocusTarget = FocusTarget.AUTO,
    val description: String = ""
) {
    companion object {
        val DEFAULTS = listOf(
            ShotPreset("establishing", ShotType.ESTABLISHING, "wide", 1.0f, FocusTarget.AUTO, "Maximum coverage"),
            ShotPreset("wide", ShotType.WIDE, "wide", 1.0f, FocusTarget.AUTO, "Wide angle"),
            ShotPreset("full", ShotType.FULL_SHOT, "wide", 1.2f, FocusTarget.AUTO, "Subject head-to-toe"),
            ShotPreset("medium", ShotType.MEDIUM, "main", 1.0f, FocusTarget.AUTO, "Standard conversation"),
            ShotPreset("medium_close", ShotType.MEDIUM_CLOSE, "main", 1.5f, FocusTarget.FACE, "Chest-up framing"),
            ShotPreset("closeup", ShotType.CLOSE_UP, "main", 2.0f, FocusTarget.FACE, "Head and shoulders"),
            ShotPreset("extreme_closeup", ShotType.EXTREME_CLOSE, "telephoto", 1.0f, FocusTarget.MANUAL, "Detail shots"),
            ShotPreset("over_shoulder", ShotType.OVER_SHOULDER, "telephoto", 1.2f, FocusTarget.AUTO, "Perspective shots")
        )

        fun forShotType(shotType: ShotType): ShotPreset? {
            return DEFAULTS.find { it.shotType == shotType }
        }
    }
}

/**
 * Scene parsed from script
 */
data class DirectorScene(
    val id: String = java.util.UUID.randomUUID().toString(),
    val label: String,
    val description: String = "",
    val cues: List<DirectorCue> = emptyList(),
    val startLine: Int = 0,
    val endLine: Int = 0
)

/**
 * A recorded take with quality metrics
 */
data class RecordedTake(
    val takeNumber: Int,
    val sceneId: String,
    val sceneLabel: String,
    val startTimeMs: Long,
    val endTimeMs: Long = 0,
    val filePath: String? = null,
    val qualityScore: Float = 0f,
    val qualityFactors: TakeQualityFactors = TakeQualityFactors(),
    val cuesExecuted: Int = 0,
    val cuesFailed: Int = 0,
    val manualMark: TakeQuality = TakeQuality.UNMARKED,
    val suggested: Boolean = false,
    val notes: String = ""
) {
    val durationMs: Long get() = if (endTimeMs > 0) endTimeMs - startTimeMs else 0
    val durationFormatted: String get() {
        val totalSeconds = durationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val millis = durationMs % 1000
        return String.format("%02d:%02d.%03d", minutes, seconds, millis)
    }
}

/**
 * Quality factors for a take
 */
data class TakeQualityFactors(
    val focusLockPercent: Int = 0,      // % of time focus was locked
    val exposureStability: Float = 0f,   // 0-1, how stable exposure was
    val motionStability: Float = 0f,     // 0-1, how stable the image was
    val audioLevelOk: Boolean = true,    // Audio levels within range
    val cueTimingAccuracy: Float = 0f    // How well cues hit their marks
)

/**
 * Parsed script with scenes and cues
 */
data class ParsedScript(
    val id: String = java.util.UUID.randomUUID().toString(),
    val rawScript: String,
    val scenes: List<DirectorScene>,
    val totalCues: Int,
    val estimatedDurationMs: Long,
    val parseTimestamp: Long = System.currentTimeMillis(),
    val errors: List<String> = emptyList()
) {
    val estimatedDurationFormatted: String get() {
        val totalSeconds = estimatedDurationMs / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%d:%02d", minutes, seconds)
    }
}

/**
 * Director session state
 */
data class DirectorSession(
    val script: ParsedScript,
    val currentSceneIndex: Int = 0,
    val currentCueIndex: Int = 0,
    val takes: List<RecordedTake> = emptyList(),
    val startTimeMs: Long = 0,
    val state: DirectorState = DirectorState.READY
) {
    val currentScene: DirectorScene? get() = script.scenes.getOrNull(currentSceneIndex)
    val currentCue: DirectorCue? get() = currentScene?.cues?.getOrNull(currentCueIndex)
    val nextCue: DirectorCue? get() = currentScene?.cues?.getOrNull(currentCueIndex + 1)
    val currentTake: RecordedTake? get() = takes.lastOrNull()
    val bestTake: RecordedTake? get() = takes.filter { it.qualityScore > 0 }.maxByOrNull { it.qualityScore }
}

/**
 * Director status for API responses
 */
data class DirectorStatus(
    val state: DirectorState = DirectorState.DISABLED,
    val enabled: Boolean = false,
    val inferenceMode: InferenceMode = InferenceMode.OFF,
    val hasScript: Boolean = false,
    val currentScene: String? = null,
    val currentCue: String? = null,
    val nextCue: String? = null,
    val takeNumber: Int = 0,
    val totalCues: Int = 0,
    val executedCues: Int = 0,
    val thermalProtectionActive: Boolean = false
)

/**
 * Remote LLM configuration
 */
data class LlmConfig(
    val endpoint: String = "",
    val apiKey: String = "",
    val model: String = "gpt-4",
    val maxTokens: Int = 1000,
    val temperature: Float = 0.3f,
    val systemPrompt: String = DEFAULT_SYSTEM_PROMPT
) {
    companion object {
        const val DEFAULT_SYSTEM_PROMPT = """You are a camera direction assistant for LensDaemon.
Parse the user's script or scene description and output camera cues in the following format:
[SHOT: WIDE|MEDIUM|CLOSE-UP|etc]
[TRANSITION: PUSH IN|PULL BACK] - {duration in seconds}
[FOCUS: FACE|HANDS|OBJECT|BACKGROUND]
[EXPOSURE: AUTO|BRIGHT|DARK|BACKLIT]
[BEAT] or [HOLD: {seconds}]
[SCENE: {label}]

Be concise. Output only the cues, one per line."""
    }
}

/**
 * Complete director configuration
 */
data class DirectorConfig(
    val enabled: Boolean = false,
    val inferenceMode: InferenceMode = InferenceMode.PRE_PARSED,
    val llmConfig: LlmConfig = LlmConfig(),
    val autoTakeSeparation: Boolean = true,
    val qualityScoring: Boolean = true,
    val thermalAutoDisable: Boolean = true,
    val thermalThresholdInference: Int = 50,    // °C - disable inference above this
    val thermalThresholdDisable: Int = 55,      // °C - disable director entirely above this
    val defaultTransitionDurationMs: Long = 1000,
    val defaultHoldDurationMs: Long = 2000,
    val shotPresets: Map<String, ShotPreset> = ShotPreset.DEFAULTS.associateBy { it.name }
) {
    companion object {
        val DEFAULT = DirectorConfig()

        /** Configuration for thermal-safe operation with pre-parsed scripts */
        val THERMAL_SAFE = DirectorConfig(
            enabled = true,
            inferenceMode = InferenceMode.PRE_PARSED,
            thermalAutoDisable = true,
            thermalThresholdInference = 45,
            thermalThresholdDisable = 50
        )

        /** Configuration with remote LLM for dynamic interpretation */
        fun withRemoteLlm(endpoint: String, apiKey: String, model: String = "gpt-4") = DirectorConfig(
            enabled = true,
            inferenceMode = InferenceMode.REMOTE,
            llmConfig = LlmConfig(endpoint = endpoint, apiKey = apiKey, model = model)
        )
    }
}

/**
 * Director configuration persistence
 */
class DirectorConfigStore(private val context: Context) {
    companion object {
        private const val PREFS_NAME = "lensdaemon_director_config"
        private const val KEY_CONFIG_JSON = "config_json"
    }

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Save director configuration
     */
    fun saveConfig(config: DirectorConfig) {
        val json = JSONObject().apply {
            put("enabled", config.enabled)
            put("inferenceMode", config.inferenceMode.name)

            put("llmConfig", JSONObject().apply {
                put("endpoint", config.llmConfig.endpoint)
                put("apiKey", config.llmConfig.apiKey)
                put("model", config.llmConfig.model)
                put("maxTokens", config.llmConfig.maxTokens)
                put("temperature", config.llmConfig.temperature)
            })

            put("autoTakeSeparation", config.autoTakeSeparation)
            put("qualityScoring", config.qualityScoring)
            put("thermalAutoDisable", config.thermalAutoDisable)
            put("thermalThresholdInference", config.thermalThresholdInference)
            put("thermalThresholdDisable", config.thermalThresholdDisable)
            put("defaultTransitionDurationMs", config.defaultTransitionDurationMs)
            put("defaultHoldDurationMs", config.defaultHoldDurationMs)
        }

        prefs.edit().putString(KEY_CONFIG_JSON, json.toString()).apply()
    }

    /**
     * Load director configuration
     */
    fun loadConfig(): DirectorConfig {
        val jsonStr = prefs.getString(KEY_CONFIG_JSON, null) ?: return DirectorConfig.DEFAULT

        return try {
            val json = JSONObject(jsonStr)
            val llmJson = json.optJSONObject("llmConfig")

            DirectorConfig(
                enabled = json.optBoolean("enabled", false),
                inferenceMode = try {
                    InferenceMode.valueOf(json.optString("inferenceMode", "PRE_PARSED"))
                } catch (e: Exception) {
                    InferenceMode.PRE_PARSED
                },
                llmConfig = if (llmJson != null) {
                    LlmConfig(
                        endpoint = llmJson.optString("endpoint", ""),
                        apiKey = llmJson.optString("apiKey", ""),
                        model = llmJson.optString("model", "gpt-4"),
                        maxTokens = llmJson.optInt("maxTokens", 1000),
                        temperature = llmJson.optDouble("temperature", 0.3).toFloat()
                    )
                } else LlmConfig(),
                autoTakeSeparation = json.optBoolean("autoTakeSeparation", true),
                qualityScoring = json.optBoolean("qualityScoring", true),
                thermalAutoDisable = json.optBoolean("thermalAutoDisable", true),
                thermalThresholdInference = json.optInt("thermalThresholdInference", 50),
                thermalThresholdDisable = json.optInt("thermalThresholdDisable", 55),
                defaultTransitionDurationMs = json.optLong("defaultTransitionDurationMs", 1000),
                defaultHoldDurationMs = json.optLong("defaultHoldDurationMs", 2000)
            )
        } catch (e: Exception) {
            DirectorConfig.DEFAULT
        }
    }

    /**
     * Check if director is enabled
     */
    fun isEnabled(): Boolean {
        return loadConfig().enabled
    }
}
