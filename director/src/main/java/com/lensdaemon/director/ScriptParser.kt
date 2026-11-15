package com.lensdaemon.director

import timber.log.Timber
import java.util.regex.Matcher
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
 * - [BEAT] or [HOLD: {seconds}]
 * - [TAKE: {number}]
 * - [CUT TO: shot]
 *
 * One cue per line, and the text inside a cue's brackets holds no other bracket. A scene
 * marker without a label is "Untitled". [parseScript] reports what it could not use in
 * [ParsedScript.errors]: bracketed lines that are not cues, unknown shot names (which become
 * MEDIUM) and lines with more than one cue (only the first is used). Lines of plain prose
 * are narration. Parsing takes time in proportion to the script's length.
 */
class ScriptParser(
    config: DirectorConfig = DirectorConfig.DEFAULT
) {
    companion object {
        private const val TAG = "ScriptParser"

        /** At most this many problems are listed one by one; the rest are counted. */
        private const val MAX_LISTED_PROBLEMS = 10

        // Regex patterns for cue detection. Patterns with free text in the brackets read it
        // with possessive quantifiers that stop at the next bracket, so matching a line takes
        // time in proportion to its length: an unclosed cue followed by a long run of text or
        // spaces can't make the matcher retry every way of splitting it.
        private val SCENE_PATTERN = Pattern.compile(
            """\[SCENE[:\s]++([^\[\]]*+)\]""",
            Pattern.CASE_INSENSITIVE
        )

        // Any shot name (the text up to the bracket, trimmed): unknown ones are reported and become MEDIUM
        private val SHOT_PATTERN = Pattern.compile(
            """\[SHOT[:\s]++([^\[\]\s][^\[\]]*+)\]""",
            Pattern.CASE_INSENSITIVE
        )

        private val TRANSITION_PATTERN = Pattern.compile(
            """\[TRANSITION[:\s]+(PUSH[_\s]?IN|PULL[_\s]?BACK|RACK[_\s]?FOCUS|CUT|HOLD)\](?:\s*+-?\s*+(\d+(?:\.\d+)?)\s*s?)?""",
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

        private val BEAT_PATTERN = Pattern.compile(
            """\[BEAT\]|\[HOLD[:\s]+(\d+(?:\.\d+)?)\s*s?\]""",
            Pattern.CASE_INSENSITIVE
        )

        private val TAKE_PATTERN = Pattern.compile(
            """\[TAKE[:\s]+(\d+)\]""",
            Pattern.CASE_INSENSITIVE
        )

        // Any shot name, as for SHOT
        private val CUT_TO_PATTERN = Pattern.compile(
            """\[CUT\s++TO[:\s]++([^\[\]\s][^\[\]]*+)\]""",
            Pattern.CASE_INSENSITIVE
        )

        /** The patterns of the cue types, for counting the cues on a line. */
        private val CUE_PATTERNS = listOf(
            SHOT_PATTERN, TRANSITION_PATTERN, FOCUS_PATTERN, EXPOSURE_PATTERN, BEAT_PATTERN, TAKE_PATTERN, CUT_TO_PATTERN
        )

        /** One bracketed part of a line, such as "[SHOT: WIDE]". */
        private val BRACKETED = Pattern.compile("""\[[^\[\]]*\]""")

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

    /**
     * The settings in force (default transition and hold durations). [DirectorManager]
     * replaces them when the director's configuration changes.
     */
    @Volatile
    internal var config: DirectorConfig = config

    /** The problems found by the last [parseScript]. */
    @Volatile
    private var lastErrors: List<String> = emptyList()

    /**
     * Parse a complete script into scenes and cues
     */
    fun parseScript(script: String): ParsedScript {
        val problems = ProblemList()
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
                    label = sceneMatcher.group(1)?.trim().orEmpty().ifEmpty { "Untitled" },
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
            lineProblems(trimmedLine, lineNumber, cues).forEach(problems::add)
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

        val errors = problems.toList()
        lastErrors = errors
        Timber.tag(TAG).i("Parsed script: ${scenes.size} scenes, $totalCues cues, ${errors.size} problems")

        return ParsedScript(
            rawScript = script,
            scenes = scenes,
            totalCues = totalCues,
            estimatedDurationMs = estimatedDurationMs,
            errors = errors
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
     * Parse explicit cue format [CUE_TYPE: value]: the first cue written on the line
     */
    private fun parseExplicitCue(line: String, lineNumber: Int): DirectorCue? {
        var first: Matcher? = null
        for (pattern in CUE_PATTERNS) {
            val matcher = pattern.matcher(line)
            if (matcher.find() && (first == null || matcher.start() < first.start())) {
                first = matcher
            }
        }
        val found = first ?: return null

        return when (found.pattern()) {
            SHOT_PATTERN -> DirectorCue(
                type = CueType.SHOT,
                rawText = line,
                shotType = shotTypeOrNull(normalizeShotName(found.group(1) ?: "")) ?: ShotType.MEDIUM,
                lineNumber = lineNumber,
                notes = extractDescription(line, found.end())
            )
            TRANSITION_PATTERN -> transitionCue(line, lineNumber, found)
            FOCUS_PATTERN -> DirectorCue(
                type = CueType.FOCUS,
                rawText = line,
                focusTarget = parseFocusTarget(found.group(1)?.uppercase() ?: "AUTO"),
                lineNumber = lineNumber
            )
            EXPOSURE_PATTERN -> DirectorCue(
                type = CueType.EXPOSURE,
                rawText = line,
                exposurePreset = parseExposurePreset(found.group(1)?.uppercase() ?: "AUTO"),
                lineNumber = lineNumber
            )
            BEAT_PATTERN -> {
                // [BEAT], or [HOLD: seconds]
                val holdSeconds = found.group(1)?.toFloatOrNull() ?: 0f
                DirectorCue(
                    type = CueType.BEAT,
                    rawText = line,
                    holdDurationMs = if (holdSeconds > 0) (holdSeconds * 1000).toLong() else config.defaultHoldDurationMs,
                    lineNumber = lineNumber
                )
            }
            TAKE_PATTERN -> DirectorCue(
                type = CueType.TAKE,
                rawText = line,
                takeNumber = found.group(1)?.toIntOrNull() ?: 1,
                lineNumber = lineNumber
            )
            // CUT TO
            else -> DirectorCue(
                type = CueType.CUT,
                rawText = line,
                shotType = shotTypeOrNull(normalizeShotName(found.group(1) ?: "")) ?: ShotType.MEDIUM,
                transitionType = TransitionType.CUT,
                transitionDurationMs = 0,
                lineNumber = lineNumber
            )
        }
    }

    /** A TRANSITION cue, with the duration written after it ("- 2s") or the default one. */
    private fun transitionCue(line: String, lineNumber: Int, matcher: Matcher): DirectorCue {
        val transitionStr = matcher.group(1)?.uppercase()?.replace(" ", "_") ?: ""
        val durationStr = matcher.group(2)
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
            notes = extractDescription(line, matcher.end())
        )
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
     * The problems with one script line, numbered from 1 as an editor shows them: a
     * bracketed line that is not a cue, an unknown shot name, or more than one cue.
     */
    private fun lineProblems(line: String, lineNumber: Int, cues: List<DirectorCue>): List<String> {
        val shownLine = lineNumber + 1
        val cue = cues.firstOrNull()
            ?: return if (line.startsWith("[")) listOf("Line $shownLine: not a cue: $line") else emptyList()
        val problems = mutableListOf<String>()
        unknownShotName(line, cue)?.let { problems.add("Line $shownLine: unknown shot $it, using MEDIUM") }
        if (explicitCueCount(line) > 1) {
            problems.add("Line $shownLine: only the first cue on a line is used")
        }
        return problems
    }

    /** The shot name a [SHOT] or [CUT TO] cue on [line] asked for, when the director doesn't know it. */
    private fun unknownShotName(line: String, cue: DirectorCue): String? {
        val pattern = when (cue.type) {
            CueType.SHOT -> SHOT_PATTERN
            CueType.CUT -> CUT_TO_PATTERN
            else -> return null
        }
        val matcher = pattern.matcher(line)
        // A shot found in plain words has no bracketed name
        if (!matcher.find()) return null
        val name = matcher.group(1)?.trim() ?: return null
        if (shotTypeOrNull(normalizeShotName(name)) != null) return null
        return name.ifEmpty { "(none)" }
    }

    /** How many bracketed cues [line] holds. */
    private fun explicitCueCount(line: String): Int {
        val matcher = BRACKETED.matcher(line)
        var count = 0
        while (matcher.find()) {
            val part = matcher.group()
            if (CUE_PATTERNS.any { it.matcher(part).find() }) count++
        }
        return count
    }

    /** A shot name as [shotTypeOrNull] reads it: upper case, with underscores for spaces and hyphens. */
    private fun normalizeShotName(name: String): String =
        name.trim().uppercase().replace(" ", "_").replace("-", "_")

    /**
     * Parse shot type string to enum; null for a name the director doesn't know
     */
    private fun shotTypeOrNull(str: String): ShotType? {
        return when {
            str.contains("ESTABLISHING") -> ShotType.ESTABLISHING
            str.contains("EXTREME") || str == "ECU" -> ShotType.EXTREME_CLOSE
            str.contains("MEDIUM_CLOSE") || str.contains("MCU") -> ShotType.MEDIUM_CLOSE
            str.contains("CLOSE") -> ShotType.CLOSE_UP
            str.contains("MEDIUM") -> ShotType.MEDIUM
            str.contains("FULL") -> ShotType.FULL_SHOT
            str.contains("OVER") || str == "OTS" -> ShotType.OVER_SHOULDER
            str.contains("WIDE") -> ShotType.WIDE
            else -> null
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
    fun getErrors(): List<String> = lastErrors

    /** Problems of one parse: the first [MAX_LISTED_PROBLEMS] are listed, the rest counted. */
    private class ProblemList {
        private val listed = mutableListOf<String>()
        private var unlisted = 0

        fun add(problem: String) {
            if (listed.size < MAX_LISTED_PROBLEMS) listed.add(problem) else unlisted++
        }

        fun toList(): List<String> = when (unlisted) {
            0 -> listed.toList()
            1 -> listed + "1 more problem not listed"
            else -> listed + "$unlisted more problems not listed"
        }
    }
}
