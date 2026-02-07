package com.lensdaemon.director

import timber.log.Timber
import java.util.regex.Pattern

/**
 * Script Parser
 *
 * Parses script/scene descriptions and extracts camera cues.
 * Supports the LensDaemon cue format:
 * - [SCENE: label]
 * - [SHOT: WIDE|MEDIUM|CLOSE-UP|etc]
 * - [TRANSITION: PUSH IN|PULL BACK] - {duration}
 * - [FOCUS: FACE|HANDS|OBJECT|BACKGROUND]
 * - [EXPOSURE: AUTO|BRIGHT|DARK|BACKLIT]
 * - [DOF: SHALLOW|DEEP|AUTO]
 * - [BEAT] or [HOLD: {seconds}]
 * - [TAKE: {number}]
 * - [CUT TO: shot]
 */
class ScriptParser(
    private val config: DirectorConfig = DirectorConfig.DEFAULT
) {
    companion object {
        private const val TAG = "ScriptParser"

        // Regex patterns for cue detection
        private val SCENE_PATTERN = Pattern.compile(
            """\[SCENE[:\s]+(.+?)\]""",
            Pattern.CASE_INSENSITIVE
        )

        private val SHOT_PATTERN = Pattern.compile(
            """\[SHOT[:\s]+(ESTABLISHING|WIDE|FULL[_\s]?SHOT|MEDIUM[_\s]?CLOSE|MEDIUM|CLOSE[_\s]?UP|EXTREME[_\s]?CLOSE[_\s]?UP|ECU|OVER[_\s]?SHOULDER|OTS)\]""",
            Pattern.CASE_INSENSITIVE
        )

        private val TRANSITION_PATTERN = Pattern.compile(
            """\[TRANSITION[:\s]+(PUSH[_\s]?IN|PULL[_\s]?BACK|RACK[_\s]?FOCUS|CUT|HOLD)\](?:\s*-?\s*(\d+(?:\.\d+)?)\s*s?)?""",
            Pattern.CASE_INSENSITIVE
        )

        private val FOCUS_PATTERN = Pattern.compile(
            """\[FOCUS[:\s]+(AUTO|FACE|HANDS|OBJECT|BACKGROUND|MANUAL)\]""",
            Pattern.CASE_INSENSITIVE
        )

        private val EXPOSURE_PATTERN = Pattern.compile(
            """\[EXPOSURE[:\s]+(AUTO|BRIGHT|DARK|BACKLIT|SILHOUETTE)\]""",
            Pattern.CASE_INSENSITIVE
        )

        private val DOF_PATTERN = Pattern.compile(
            """\[DOF[:\s]+(SHALLOW|DEEP|AUTO)\]""",
            Pattern.CASE_INSENSITIVE
        )

        private val BEAT_PATTERN = Pattern.compile(
            """\[BEAT\]|\[HOLD[:\s]+(\d+(?:\.\d+)?)\s*s?\]""",
            Pattern.CASE_INSENSITIVE
        )

        private val TAKE_PATTERN = Pattern.compile(
            """\[TAKE[:\s]+(\d+)\]""",
            Pattern.CASE_INSENSITIVE
        )

        private val CUT_TO_PATTERN = Pattern.compile(
            """\[CUT\s+TO[:\s]+(WIDE|MEDIUM|CLOSE[_\s]?UP|ESTABLISHING|.+?)\]""",
            Pattern.CASE_INSENSITIVE
        )

        // Shorthand patterns for natural language
        private val WIDE_SHOT_NATURAL = Pattern.compile(
            """(?:^|\s)(wide\s+(?:shot|angle)|establishing\s+shot|master\s+shot)""",
            Pattern.CASE_INSENSITIVE
        )

        private val CLOSE_UP_NATURAL = Pattern.compile(
            """(?:^|\s)(close[_\s-]?up|closeup|tight\s+(?:shot|on)|detail\s+shot)""",
            Pattern.CASE_INSENSITIVE
        )

        private val MEDIUM_SHOT_NATURAL = Pattern.compile(
            """(?:^|\s)(medium\s+shot|waist\s+shot|mid[_\s-]?shot)""",
            Pattern.CASE_INSENSITIVE
        )
    }

    private val errors = mutableListOf<String>()

    /**
     * Parse a complete script into scenes and cues
     */
    fun parseScript(script: String): ParsedScript {
        errors.clear()

        val lines = script.lines()
        val scenes = mutableListOf<DirectorScene>()
        var currentScene: DirectorScene? = null
        val currentCues = mutableListOf<DirectorCue>()
        var totalCues = 0
        var estimatedDurationMs = 0L

        lines.forEachIndexed { lineNumber, line ->
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) return@forEachIndexed

            // Check for scene marker
            val sceneMatcher = SCENE_PATTERN.matcher(trimmedLine)
            if (sceneMatcher.find()) {
                // Save previous scene
                if (currentScene != null) {
                    scenes.add(currentScene!!.copy(
                        cues = currentCues.toList(),
                        endLine = lineNumber - 1
                    ))
                    currentCues.clear()
                }

                // Start new scene
                currentScene = DirectorScene(
                    label = sceneMatcher.group(1)?.trim() ?: "Untitled",
                    description = extractDescription(trimmedLine, sceneMatcher.end()),
                    startLine = lineNumber
                )
                Timber.tag(TAG).d("Parsed scene: ${currentScene!!.label}")
                return@forEachIndexed
            }

            // If no scene started yet, create a default one
            if (currentScene == null) {
                currentScene = DirectorScene(
                    label = "Scene 1",
                    startLine = lineNumber
                )
            }

            // Parse cues from the line
            val cues = parseCuesFromLine(trimmedLine, lineNumber)
            currentCues.addAll(cues)
            totalCues += cues.size

            // Estimate duration
            cues.forEach { cue ->
                estimatedDurationMs += when (cue.type) {
                    CueType.TRANSITION -> cue.transitionDurationMs
                    CueType.BEAT -> if (cue.holdDurationMs > 0) cue.holdDurationMs else config.defaultHoldDurationMs
                    CueType.SHOT -> config.defaultTransitionDurationMs
                    else -> 0L
                }
            }
        }

        // Save final scene
        if (currentScene != null && currentCues.isNotEmpty()) {
            scenes.add(currentScene!!.copy(
                cues = currentCues.toList(),
                endLine = lines.size - 1
            ))
        }

        // If no scenes were created but we have cues, wrap in default scene
        if (scenes.isEmpty() && currentCues.isNotEmpty()) {
            scenes.add(DirectorScene(
                label = "Default Scene",
                cues = currentCues.toList(),
                startLine = 0,
                endLine = lines.size - 1
            ))
        }

        Timber.tag(TAG).i("Parsed script: ${scenes.size} scenes, $totalCues cues")

        return ParsedScript(
            rawScript = script,
            scenes = scenes,
            totalCues = totalCues,
            estimatedDurationMs = estimatedDurationMs,
            errors = errors.toList()
        )
    }

    /**
     * Parse a single line for cues
     */
    fun parseCuesFromLine(line: String, lineNumber: Int = 0): List<DirectorCue> {
        val cues = mutableListOf<DirectorCue>()

        // Check for explicit cue patterns first
        parseExplicitCue(line, lineNumber)?.let { cues.add(it) }

        // If no explicit cue found, try natural language detection
        if (cues.isEmpty()) {
            parseNaturalLanguage(line, lineNumber)?.let { cues.add(it) }
        }

        return cues
    }

    /**
     * Parse explicit cue format [CUE_TYPE: value]
     */
    private fun parseExplicitCue(line: String, lineNumber: Int): DirectorCue? {
        // SHOT cue
        val shotMatcher = SHOT_PATTERN.matcher(line)
        if (shotMatcher.find()) {
            val shotTypeStr = shotMatcher.group(1)?.uppercase()?.replace(" ", "_")?.replace("-", "_") ?: ""
            val shotType = parseShotType(shotTypeStr)
            return DirectorCue(
                type = CueType.SHOT,
                rawText = line,
                shotType = shotType,
                lineNumber = lineNumber,
                notes = extractDescription(line, shotMatcher.end())
            )
        }

        // TRANSITION cue
        val transitionMatcher = TRANSITION_PATTERN.matcher(line)
        if (transitionMatcher.find()) {
            val transitionStr = transitionMatcher.group(1)?.uppercase()?.replace(" ", "_") ?: ""
            val durationStr = transitionMatcher.group(2)
            val durationMs = if (durationStr != null) {
                (durationStr.toFloatOrNull() ?: 1f) * 1000
            } else {
                config.defaultTransitionDurationMs.toFloat()
            }

            return DirectorCue(
                type = CueType.TRANSITION,
                rawText = line,
                transitionType = parseTransitionType(transitionStr),
                transitionDurationMs = durationMs.toLong(),
                lineNumber = lineNumber,
                notes = extractDescription(line, transitionMatcher.end())
            )
        }

        // FOCUS cue
        val focusMatcher = FOCUS_PATTERN.matcher(line)
        if (focusMatcher.find()) {
            val focusStr = focusMatcher.group(1)?.uppercase() ?: "AUTO"
            return DirectorCue(
                type = CueType.FOCUS,
                rawText = line,
                focusTarget = parseFocusTarget(focusStr),
                lineNumber = lineNumber
            )
        }

        // EXPOSURE cue
        val exposureMatcher = EXPOSURE_PATTERN.matcher(line)
        if (exposureMatcher.find()) {
            val exposureStr = exposureMatcher.group(1)?.uppercase() ?: "AUTO"
            return DirectorCue(
                type = CueType.EXPOSURE,
                rawText = line,
                exposurePreset = parseExposurePreset(exposureStr),
                lineNumber = lineNumber
            )
        }

        // BEAT/HOLD cue
        val beatMatcher = BEAT_PATTERN.matcher(line)
        if (beatMatcher.find()) {
            val holdSeconds = beatMatcher.group(1)?.toFloatOrNull() ?: 0f
            return DirectorCue(
                type = CueType.BEAT,
                rawText = line,
                holdDurationMs = if (holdSeconds > 0) (holdSeconds * 1000).toLong() else config.defaultHoldDurationMs,
                lineNumber = lineNumber
            )
        }

        // TAKE cue
        val takeMatcher = TAKE_PATTERN.matcher(line)
        if (takeMatcher.find()) {
            val takeNum = takeMatcher.group(1)?.toIntOrNull() ?: 1
            return DirectorCue(
                type = CueType.TAKE,
                rawText = line,
                takeNumber = takeNum,
                lineNumber = lineNumber
            )
        }

        // CUT TO cue
        val cutMatcher = CUT_TO_PATTERN.matcher(line)
        if (cutMatcher.find()) {
            val targetShot = cutMatcher.group(1)?.uppercase()?.replace(" ", "_") ?: "WIDE"
            val shotType = parseShotType(targetShot)
            return DirectorCue(
                type = CueType.CUT,
                rawText = line,
                shotType = shotType,
                transitionType = TransitionType.CUT,
                transitionDurationMs = 0,
                lineNumber = lineNumber
            )
        }

        return null
    }

    /**
     * Parse natural language descriptions for implicit cues
     */
    private fun parseNaturalLanguage(line: String, lineNumber: Int): DirectorCue? {
        // Wide shot natural language
        if (WIDE_SHOT_NATURAL.matcher(line).find()) {
            return DirectorCue(
                type = CueType.SHOT,
                rawText = line,
                shotType = ShotType.WIDE,
                lineNumber = lineNumber,
                notes = "Detected from natural language"
            )
        }

        // Close-up natural language
        if (CLOSE_UP_NATURAL.matcher(line).find()) {
            return DirectorCue(
                type = CueType.SHOT,
                rawText = line,
                shotType = ShotType.CLOSE_UP,
                lineNumber = lineNumber,
                notes = "Detected from natural language"
            )
        }

        // Medium shot natural language
        if (MEDIUM_SHOT_NATURAL.matcher(line).find()) {
            return DirectorCue(
                type = CueType.SHOT,
                rawText = line,
                shotType = ShotType.MEDIUM,
                lineNumber = lineNumber,
                notes = "Detected from natural language"
            )
        }

        return null
    }

    /**
     * Parse shot type string to enum
     */
    private fun parseShotType(str: String): ShotType {
        return when {
            str.contains("ESTABLISHING") -> ShotType.ESTABLISHING
            str.contains("EXTREME") || str == "ECU" -> ShotType.EXTREME_CLOSE
            str.contains("CLOSE") -> ShotType.CLOSE_UP
            str.contains("MEDIUM_CLOSE") || str.contains("MCU") -> ShotType.MEDIUM_CLOSE
            str.contains("MEDIUM") -> ShotType.MEDIUM
            str.contains("FULL") -> ShotType.FULL_SHOT
            str.contains("OVER") || str == "OTS" -> ShotType.OVER_SHOULDER
            str.contains("WIDE") -> ShotType.WIDE
            else -> ShotType.MEDIUM
        }
    }

    /**
     * Parse transition type string to enum
     */
    private fun parseTransitionType(str: String): TransitionType {
        return when {
            str.contains("PUSH") -> TransitionType.PUSH_IN
            str.contains("PULL") -> TransitionType.PULL_BACK
            str.contains("RACK") -> TransitionType.RACK_FOCUS
            str.contains("CUT") -> TransitionType.CUT
            str.contains("HOLD") -> TransitionType.HOLD
            else -> TransitionType.CUT
        }
    }

    /**
     * Parse focus target string to enum
     */
    private fun parseFocusTarget(str: String): FocusTarget {
        return when (str) {
            "AUTO" -> FocusTarget.AUTO
            "FACE" -> FocusTarget.FACE
            "HANDS" -> FocusTarget.HANDS
            "OBJECT" -> FocusTarget.OBJECT
            "BACKGROUND" -> FocusTarget.BACKGROUND
            "MANUAL" -> FocusTarget.MANUAL
            else -> FocusTarget.AUTO
        }
    }

    /**
     * Parse exposure preset string to enum
     */
    private fun parseExposurePreset(str: String): ExposurePreset {
        return when (str) {
            "AUTO" -> ExposurePreset.AUTO
            "BRIGHT" -> ExposurePreset.BRIGHT
            "DARK" -> ExposurePreset.DARK
            "BACKLIT" -> ExposurePreset.BACKLIT
            "SILHOUETTE" -> ExposurePreset.SILHOUETTE
            else -> ExposurePreset.AUTO
        }
    }

    /**
     * Extract description text after a cue marker
     */
    private fun extractDescription(line: String, afterIndex: Int): String {
        return if (afterIndex < line.length) {
            line.substring(afterIndex).trim().trimStart('-', ':').trim()
        } else ""
    }

    /**
     * Parse a single cue string (for ad-hoc execution)
     */
    fun parseSingleCue(cueText: String): DirectorCue? {
        return parseCuesFromLine(cueText, 0).firstOrNull()
    }

    /**
     * Validate a parsed script
     */
    fun validateScript(script: ParsedScript): List<String> {
        val warnings = mutableListOf<String>()

        if (script.scenes.isEmpty()) {
            warnings.add("Script contains no scenes")
        }

        if (script.totalCues == 0) {
            warnings.add("Script contains no cues")
        }

        script.scenes.forEach { scene ->
            if (scene.cues.isEmpty()) {
                warnings.add("Scene '${scene.label}' has no cues")
            }
        }

        return warnings
    }

    /**
     * Get parsing errors from last parse operation
     */
    fun getErrors(): List<String> = errors.toList()
}
