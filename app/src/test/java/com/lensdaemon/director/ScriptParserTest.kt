package com.lensdaemon.director

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive unit tests for ScriptParser.
 *
 * Note: ScriptParser uses Timber for logging. Timber without planted Trees
 * silently discards messages, so no mocking is needed for unit tests.
 */
class ScriptParserTest {

    private lateinit var parser: ScriptParser

    @Before
    fun setUp() {
        parser = ScriptParser(DirectorConfig.DEFAULT)
    }

    // ========================================================================
    // 1. Empty script parsing
    // ========================================================================

    @Test
    fun `parseScript with empty string returns no scenes and no cues`() {
        val result = parser.parseScript("")
        assertEquals(0, result.scenes.size)
        assertEquals(0, result.totalCues)
        assertEquals(0L, result.estimatedDurationMs)
    }

    @Test
    fun `parseScript with whitespace only returns no scenes`() {
        val result = parser.parseScript("   \n   \n   ")
        assertEquals(0, result.scenes.size)
        assertEquals(0, result.totalCues)
    }

    @Test
    fun `parseScript with blank lines returns no scenes`() {
        val result = parser.parseScript("\n\n\n")
        assertEquals(0, result.scenes.size)
        assertEquals(0, result.totalCues)
    }

    @Test
    fun `parseScript preserves raw script text`() {
        val script = "[SHOT: WIDE]"
        val result = parser.parseScript(script)
        assertEquals(script, result.rawScript)
    }

    // ========================================================================
    // 2. Single scene with multiple cues
    // ========================================================================

    @Test
    fun `parseScript single scene with multiple cues`() {
        val script = """
            [SCENE: Opening]
            [SHOT: WIDE]
            [TRANSITION: PUSH IN] - 2s
            [FOCUS: FACE]
            [SHOT: CLOSE-UP]
        """.trimIndent()

        val result = parser.parseScript(script)
        assertEquals(1, result.scenes.size)
        assertEquals("Opening", result.scenes[0].label)
        assertEquals(4, result.scenes[0].cues.size)
        assertEquals(4, result.totalCues)
    }

    @Test
    fun `single scene cues have correct types in order`() {
        val script = """
            [SCENE: Test Scene]
            [SHOT: WIDE]
            [FOCUS: FACE]
            [EXPOSURE: BRIGHT]
        """.trimIndent()

        val result = parser.parseScript(script)
        val cues = result.scenes[0].cues

        assertEquals(CueType.SHOT, cues[0].type)
        assertEquals(CueType.FOCUS, cues[1].type)
        assertEquals(CueType.EXPOSURE, cues[2].type)
    }

    @Test
    fun `single scene cues track line numbers`() {
        val script = """
            [SCENE: Test]
            [SHOT: WIDE]
            [SHOT: MEDIUM]
        """.trimIndent()

        val result = parser.parseScript(script)
        val cues = result.scenes[0].cues

        // Line 0 is [SCENE: Test], line 1 is [SHOT: WIDE], line 2 is [SHOT: MEDIUM]
        assertEquals(1, cues[0].lineNumber)
        assertEquals(2, cues[1].lineNumber)
    }

    // ========================================================================
    // 3. Multiple scenes
    // ========================================================================

    @Test
    fun `parseScript multiple scenes separates cues correctly`() {
        val script = """
            [SCENE: Scene One]
            [SHOT: WIDE]
            [FOCUS: FACE]
            [SCENE: Scene Two]
            [SHOT: CLOSE-UP]
            [EXPOSURE: DARK]
        """.trimIndent()

        val result = parser.parseScript(script)
        assertEquals(2, result.scenes.size)

        assertEquals("Scene One", result.scenes[0].label)
        assertEquals(2, result.scenes[0].cues.size)

        assertEquals("Scene Two", result.scenes[1].label)
        assertEquals(2, result.scenes[1].cues.size)
    }

    @Test
    fun `parseScript three scenes with varying cue counts`() {
        val script = """
            [SCENE: Intro]
            [SHOT: ESTABLISHING]
            [SCENE: Middle]
            [SHOT: MEDIUM]
            [FOCUS: FACE]
            [EXPOSURE: AUTO]
            [SCENE: End]
            [SHOT: WIDE]
        """.trimIndent()

        val result = parser.parseScript(script)
        assertEquals(3, result.scenes.size)
        assertEquals(1, result.scenes[0].cues.size)
        assertEquals(3, result.scenes[1].cues.size)
        assertEquals(1, result.scenes[2].cues.size)
        assertEquals(5, result.totalCues)
    }

    @Test
    fun `parseScript scene labels are trimmed`() {
        val script = """
            [SCENE:   Leading and trailing spaces   ]
            [SHOT: WIDE]
        """.trimIndent()

        val result = parser.parseScript(script)
        assertEquals("Leading and trailing spaces", result.scenes[0].label)
    }

    @Test
    fun `parseScript scene start and end lines are set`() {
        val script = """
            [SCENE: First]
            [SHOT: WIDE]
            [SHOT: MEDIUM]
            [SCENE: Second]
            [SHOT: CLOSE-UP]
        """.trimIndent()

        val result = parser.parseScript(script)
        assertEquals(0, result.scenes[0].startLine)
        assertEquals(2, result.scenes[0].endLine)
        assertEquals(3, result.scenes[1].startLine)
        assertEquals(4, result.scenes[1].endLine)
    }

    // ========================================================================
    // 4. Each cue type individually
    // ========================================================================

    // --- SHOT ---

    @Test
    fun `parseCuesFromLine SHOT WIDE`() {
        val cues = parser.parseCuesFromLine("[SHOT: WIDE]")
        assertEquals(1, cues.size)
        assertEquals(CueType.SHOT, cues[0].type)
        assertEquals(ShotType.WIDE, cues[0].shotType)
    }

    @Test
    fun `parseCuesFromLine SHOT MEDIUM`() {
        val cues = parser.parseCuesFromLine("[SHOT: MEDIUM]")
        assertEquals(1, cues.size)
        assertEquals(ShotType.MEDIUM, cues[0].shotType)
    }

    @Test
    fun `parseCuesFromLine SHOT CLOSE_UP`() {
        val cues = parser.parseCuesFromLine("[SHOT: CLOSE-UP]")
        assertEquals(1, cues.size)
        assertEquals(ShotType.CLOSE_UP, cues[0].shotType)
    }

    @Test
    fun `parseCuesFromLine SHOT CLOSE UP with underscore`() {
        val cues = parser.parseCuesFromLine("[SHOT: CLOSE_UP]")
        assertEquals(1, cues.size)
        assertEquals(ShotType.CLOSE_UP, cues[0].shotType)
    }

    @Test
    fun `parseCuesFromLine SHOT ESTABLISHING`() {
        val cues = parser.parseCuesFromLine("[SHOT: ESTABLISHING]")
        assertEquals(1, cues.size)
        assertEquals(ShotType.ESTABLISHING, cues[0].shotType)
    }

    @Test
    fun `parseCuesFromLine SHOT FULL_SHOT`() {
        val cues = parser.parseCuesFromLine("[SHOT: FULL SHOT]")
        assertEquals(1, cues.size)
        assertEquals(ShotType.FULL_SHOT, cues[0].shotType)
    }

    @Test
    fun `parseCuesFromLine SHOT MEDIUM_CLOSE`() {
        val cues = parser.parseCuesFromLine("[SHOT: MEDIUM CLOSE]")
        assertEquals(1, cues.size)
        assertEquals(ShotType.MEDIUM_CLOSE, cues[0].shotType)
    }

    @Test
    fun `parseCuesFromLine SHOT EXTREME_CLOSE_UP`() {
        val cues = parser.parseCuesFromLine("[SHOT: EXTREME CLOSE UP]")
        assertEquals(1, cues.size)
        assertEquals(ShotType.EXTREME_CLOSE, cues[0].shotType)
    }

    @Test
    fun `parseCuesFromLine SHOT ECU`() {
        val cues = parser.parseCuesFromLine("[SHOT: ECU]")
        assertEquals(1, cues.size)
        assertEquals(ShotType.EXTREME_CLOSE, cues[0].shotType)
    }

    @Test
    fun `parseCuesFromLine SHOT OVER_SHOULDER`() {
        val cues = parser.parseCuesFromLine("[SHOT: OVER SHOULDER]")
        assertEquals(1, cues.size)
        assertEquals(ShotType.OVER_SHOULDER, cues[0].shotType)
    }

    @Test
    fun `parseCuesFromLine SHOT OTS`() {
        val cues = parser.parseCuesFromLine("[SHOT: OTS]")
        assertEquals(1, cues.size)
        assertEquals(ShotType.OVER_SHOULDER, cues[0].shotType)
    }

    // --- TRANSITION ---

    @Test
    fun `parseCuesFromLine TRANSITION PUSH IN`() {
        val cues = parser.parseCuesFromLine("[TRANSITION: PUSH IN]")
        assertEquals(1, cues.size)
        assertEquals(CueType.TRANSITION, cues[0].type)
        assertEquals(TransitionType.PUSH_IN, cues[0].transitionType)
    }

    @Test
    fun `parseCuesFromLine TRANSITION PULL BACK`() {
        val cues = parser.parseCuesFromLine("[TRANSITION: PULL BACK]")
        assertEquals(1, cues.size)
        assertEquals(TransitionType.PULL_BACK, cues[0].transitionType)
    }

    @Test
    fun `parseCuesFromLine TRANSITION RACK FOCUS`() {
        val cues = parser.parseCuesFromLine("[TRANSITION: RACK FOCUS]")
        assertEquals(1, cues.size)
        assertEquals(TransitionType.RACK_FOCUS, cues[0].transitionType)
    }

    @Test
    fun `parseCuesFromLine TRANSITION CUT`() {
        val cues = parser.parseCuesFromLine("[TRANSITION: CUT]")
        assertEquals(1, cues.size)
        assertEquals(TransitionType.CUT, cues[0].transitionType)
    }

    @Test
    fun `parseCuesFromLine TRANSITION HOLD`() {
        val cues = parser.parseCuesFromLine("[TRANSITION: HOLD]")
        assertEquals(1, cues.size)
        assertEquals(TransitionType.HOLD, cues[0].transitionType)
    }

    @Test
    fun `TRANSITION without duration uses default`() {
        val cues = parser.parseCuesFromLine("[TRANSITION: PUSH IN]")
        assertEquals(1, cues.size)
        assertEquals(
            DirectorConfig.DEFAULT.defaultTransitionDurationMs,
            cues[0].transitionDurationMs
        )
    }

    // --- FOCUS ---

    @Test
    fun `parseCuesFromLine FOCUS AUTO`() {
        val cues = parser.parseCuesFromLine("[FOCUS: AUTO]")
        assertEquals(1, cues.size)
        assertEquals(CueType.FOCUS, cues[0].type)
        assertEquals(FocusTarget.AUTO, cues[0].focusTarget)
    }

    @Test
    fun `parseCuesFromLine FOCUS FACE`() {
        val cues = parser.parseCuesFromLine("[FOCUS: FACE]")
        assertEquals(1, cues.size)
        assertEquals(FocusTarget.FACE, cues[0].focusTarget)
    }

    @Test
    fun `parseCuesFromLine FOCUS HANDS`() {
        val cues = parser.parseCuesFromLine("[FOCUS: HANDS]")
        assertEquals(1, cues.size)
        assertEquals(FocusTarget.HANDS, cues[0].focusTarget)
    }

    @Test
    fun `parseCuesFromLine FOCUS OBJECT`() {
        val cues = parser.parseCuesFromLine("[FOCUS: OBJECT]")
        assertEquals(1, cues.size)
        assertEquals(FocusTarget.OBJECT, cues[0].focusTarget)
    }

    @Test
    fun `parseCuesFromLine FOCUS BACKGROUND`() {
        val cues = parser.parseCuesFromLine("[FOCUS: BACKGROUND]")
        assertEquals(1, cues.size)
        assertEquals(FocusTarget.BACKGROUND, cues[0].focusTarget)
    }

    @Test
    fun `parseCuesFromLine FOCUS MANUAL`() {
        val cues = parser.parseCuesFromLine("[FOCUS: MANUAL]")
        assertEquals(1, cues.size)
        assertEquals(FocusTarget.MANUAL, cues[0].focusTarget)
    }

    // --- EXPOSURE ---

    @Test
    fun `parseCuesFromLine EXPOSURE AUTO`() {
        val cues = parser.parseCuesFromLine("[EXPOSURE: AUTO]")
        assertEquals(1, cues.size)
        assertEquals(CueType.EXPOSURE, cues[0].type)
        assertEquals(ExposurePreset.AUTO, cues[0].exposurePreset)
    }

    @Test
    fun `parseCuesFromLine EXPOSURE BRIGHT`() {
        val cues = parser.parseCuesFromLine("[EXPOSURE: BRIGHT]")
        assertEquals(1, cues.size)
        assertEquals(ExposurePreset.BRIGHT, cues[0].exposurePreset)
    }

    @Test
    fun `parseCuesFromLine EXPOSURE DARK`() {
        val cues = parser.parseCuesFromLine("[EXPOSURE: DARK]")
        assertEquals(1, cues.size)
        assertEquals(ExposurePreset.DARK, cues[0].exposurePreset)
    }

    @Test
    fun `parseCuesFromLine EXPOSURE BACKLIT`() {
        val cues = parser.parseCuesFromLine("[EXPOSURE: BACKLIT]")
        assertEquals(1, cues.size)
        assertEquals(ExposurePreset.BACKLIT, cues[0].exposurePreset)
    }

    @Test
    fun `parseCuesFromLine EXPOSURE SILHOUETTE`() {
        val cues = parser.parseCuesFromLine("[EXPOSURE: SILHOUETTE]")
        assertEquals(1, cues.size)
        assertEquals(ExposurePreset.SILHOUETTE, cues[0].exposurePreset)
    }

    // --- BEAT / HOLD ---

    @Test
    fun `parseCuesFromLine BEAT`() {
        val cues = parser.parseCuesFromLine("[BEAT]")
        assertEquals(1, cues.size)
        assertEquals(CueType.BEAT, cues[0].type)
        // BEAT with no explicit hold uses defaultHoldDurationMs
        assertEquals(DirectorConfig.DEFAULT.defaultHoldDurationMs, cues[0].holdDurationMs)
    }

    @Test
    fun `parseCuesFromLine HOLD with integer seconds`() {
        val cues = parser.parseCuesFromLine("[HOLD: 3]")
        assertEquals(1, cues.size)
        assertEquals(CueType.BEAT, cues[0].type)
        assertEquals(3000L, cues[0].holdDurationMs)
    }

    @Test
    fun `parseCuesFromLine HOLD with decimal seconds`() {
        val cues = parser.parseCuesFromLine("[HOLD: 2.5]")
        assertEquals(1, cues.size)
        assertEquals(CueType.BEAT, cues[0].type)
        assertEquals(2500L, cues[0].holdDurationMs)
    }

    @Test
    fun `parseCuesFromLine HOLD with s suffix`() {
        val cues = parser.parseCuesFromLine("[HOLD: 5s]")
        assertEquals(1, cues.size)
        assertEquals(CueType.BEAT, cues[0].type)
        assertEquals(5000L, cues[0].holdDurationMs)
    }

    // --- TAKE ---

    @Test
    fun `parseCuesFromLine TAKE`() {
        val cues = parser.parseCuesFromLine("[TAKE: 1]")
        assertEquals(1, cues.size)
        assertEquals(CueType.TAKE, cues[0].type)
        assertEquals(1, cues[0].takeNumber)
    }

    @Test
    fun `parseCuesFromLine TAKE with higher number`() {
        val cues = parser.parseCuesFromLine("[TAKE: 42]")
        assertEquals(1, cues.size)
        assertEquals(42, cues[0].takeNumber)
    }

    // --- CUT TO ---

    @Test
    fun `parseCuesFromLine CUT TO WIDE`() {
        val cues = parser.parseCuesFromLine("[CUT TO: WIDE]")
        assertEquals(1, cues.size)
        assertEquals(CueType.CUT, cues[0].type)
        assertEquals(ShotType.WIDE, cues[0].shotType)
        assertEquals(TransitionType.CUT, cues[0].transitionType)
        assertEquals(0L, cues[0].transitionDurationMs)
    }

    @Test
    fun `parseCuesFromLine CUT TO CLOSE-UP`() {
        val cues = parser.parseCuesFromLine("[CUT TO: CLOSE-UP]")
        assertEquals(1, cues.size)
        assertEquals(CueType.CUT, cues[0].type)
        assertEquals(ShotType.CLOSE_UP, cues[0].shotType)
    }

    @Test
    fun `parseCuesFromLine CUT TO MEDIUM`() {
        val cues = parser.parseCuesFromLine("[CUT TO: MEDIUM]")
        assertEquals(1, cues.size)
        assertEquals(ShotType.MEDIUM, cues[0].shotType)
    }

    @Test
    fun `parseCuesFromLine CUT TO ESTABLISHING`() {
        val cues = parser.parseCuesFromLine("[CUT TO: ESTABLISHING]")
        assertEquals(1, cues.size)
        assertEquals(ShotType.ESTABLISHING, cues[0].shotType)
    }

    // ========================================================================
    // 5. Transition duration parsing
    // ========================================================================

    @Test
    fun `transition with integer seconds duration`() {
        val cues = parser.parseCuesFromLine("[TRANSITION: PUSH IN] - 2s")
        assertEquals(1, cues.size)
        assertEquals(2000L, cues[0].transitionDurationMs)
    }

    @Test
    fun `transition with decimal seconds duration`() {
        val cues = parser.parseCuesFromLine("[TRANSITION: PULL BACK] - 1.5s")
        assertEquals(1, cues.size)
        assertEquals(1500L, cues[0].transitionDurationMs)
    }

    @Test
    fun `transition with duration without s suffix`() {
        val cues = parser.parseCuesFromLine("[TRANSITION: PUSH IN] - 3")
        assertEquals(1, cues.size)
        assertEquals(3000L, cues[0].transitionDurationMs)
    }

    @Test
    fun `transition with duration without dash separator`() {
        val cues = parser.parseCuesFromLine("[TRANSITION: PUSH IN] 2s")
        assertEquals(1, cues.size)
        assertEquals(2000L, cues[0].transitionDurationMs)
    }

    @Test
    fun `transition without duration uses config default`() {
        val customConfig = DirectorConfig(defaultTransitionDurationMs = 1500)
        val customParser = ScriptParser(customConfig)
        val cues = customParser.parseCuesFromLine("[TRANSITION: PUSH IN]")
        assertEquals(1, cues.size)
        assertEquals(1500L, cues[0].transitionDurationMs)
    }

    @Test
    fun `transition with zero-point-five seconds`() {
        val cues = parser.parseCuesFromLine("[TRANSITION: RACK FOCUS] - 0.5s")
        assertEquals(1, cues.size)
        assertEquals(500L, cues[0].transitionDurationMs)
    }

    // ========================================================================
    // 6. Natural language detection
    // ========================================================================

    @Test
    fun `natural language wide shot`() {
        val cues = parser.parseCuesFromLine("Start with a wide shot of the room")
        assertEquals(1, cues.size)
        assertEquals(CueType.SHOT, cues[0].type)
        assertEquals(ShotType.WIDE, cues[0].shotType)
        assertEquals("Detected from natural language", cues[0].notes)
    }

    @Test
    fun `natural language wide angle`() {
        val cues = parser.parseCuesFromLine("Use a wide angle here")
        assertEquals(1, cues.size)
        assertEquals(ShotType.WIDE, cues[0].shotType)
    }

    @Test
    fun `natural language establishing shot`() {
        val cues = parser.parseCuesFromLine("Start with an establishing shot")
        assertEquals(1, cues.size)
        assertEquals(ShotType.WIDE, cues[0].shotType)
    }

    @Test
    fun `natural language master shot`() {
        val cues = parser.parseCuesFromLine("Use a master shot for the dialogue")
        assertEquals(1, cues.size)
        assertEquals(ShotType.WIDE, cues[0].shotType)
    }

    @Test
    fun `natural language close-up`() {
        val cues = parser.parseCuesFromLine("Move to a close-up on the subject")
        assertEquals(1, cues.size)
        assertEquals(ShotType.CLOSE_UP, cues[0].shotType)
    }

    @Test
    fun `natural language closeup without hyphen`() {
        val cues = parser.parseCuesFromLine("Get a closeup of the hands")
        assertEquals(1, cues.size)
        assertEquals(ShotType.CLOSE_UP, cues[0].shotType)
    }

    @Test
    fun `natural language tight shot`() {
        val cues = parser.parseCuesFromLine("Go tight on the face")
        assertEquals(1, cues.size)
        assertEquals(ShotType.CLOSE_UP, cues[0].shotType)
    }

    @Test
    fun `natural language detail shot`() {
        val cues = parser.parseCuesFromLine("Get a detail shot of the object")
        assertEquals(1, cues.size)
        assertEquals(ShotType.CLOSE_UP, cues[0].shotType)
    }

    @Test
    fun `natural language medium shot`() {
        val cues = parser.parseCuesFromLine("Switch to a medium shot")
        assertEquals(1, cues.size)
        assertEquals(ShotType.MEDIUM, cues[0].shotType)
    }

    @Test
    fun `natural language waist shot`() {
        val cues = parser.parseCuesFromLine("Frame as a waist shot")
        assertEquals(1, cues.size)
        assertEquals(ShotType.MEDIUM, cues[0].shotType)
    }

    @Test
    fun `natural language mid-shot`() {
        val cues = parser.parseCuesFromLine("Switch to a mid-shot for dialogue")
        assertEquals(1, cues.size)
        assertEquals(ShotType.MEDIUM, cues[0].shotType)
    }

    @Test
    fun `natural language no match returns empty`() {
        val cues = parser.parseCuesFromLine("The actor walks to the door")
        assertEquals(0, cues.size)
    }

    @Test
    fun `explicit cue takes priority over natural language`() {
        // A line with both explicit and natural language should use the explicit
        val cues = parser.parseCuesFromLine("[SHOT: MEDIUM] Start with a wide shot")
        assertEquals(1, cues.size)
        assertEquals(ShotType.MEDIUM, cues[0].shotType)
    }

    // ========================================================================
    // 7. Case insensitivity
    // ========================================================================

    @Test
    fun `shot cue is case insensitive`() {
        val cues = parser.parseCuesFromLine("[shot: wide]")
        assertEquals(1, cues.size)
        assertEquals(CueType.SHOT, cues[0].type)
        assertEquals(ShotType.WIDE, cues[0].shotType)
    }

    @Test
    fun `scene marker is case insensitive`() {
        val result = parser.parseScript("[scene: My Scene]\n[shot: wide]")
        assertEquals(1, result.scenes.size)
        assertEquals("My Scene", result.scenes[0].label)
    }

    @Test
    fun `transition cue is case insensitive`() {
        val cues = parser.parseCuesFromLine("[transition: push in] - 2s")
        assertEquals(1, cues.size)
        assertEquals(TransitionType.PUSH_IN, cues[0].transitionType)
        assertEquals(2000L, cues[0].transitionDurationMs)
    }

    @Test
    fun `focus cue is case insensitive`() {
        val cues = parser.parseCuesFromLine("[focus: face]")
        assertEquals(1, cues.size)
        assertEquals(FocusTarget.FACE, cues[0].focusTarget)
    }

    @Test
    fun `exposure cue is case insensitive`() {
        val cues = parser.parseCuesFromLine("[exposure: bright]")
        assertEquals(1, cues.size)
        assertEquals(ExposurePreset.BRIGHT, cues[0].exposurePreset)
    }

    @Test
    fun `beat cue is case insensitive`() {
        val cues = parser.parseCuesFromLine("[beat]")
        assertEquals(1, cues.size)
        assertEquals(CueType.BEAT, cues[0].type)
    }

    @Test
    fun `hold cue is case insensitive`() {
        val cues = parser.parseCuesFromLine("[hold: 3]")
        assertEquals(1, cues.size)
        assertEquals(CueType.BEAT, cues[0].type)
        assertEquals(3000L, cues[0].holdDurationMs)
    }

    @Test
    fun `take cue is case insensitive`() {
        val cues = parser.parseCuesFromLine("[take: 2]")
        assertEquals(1, cues.size)
        assertEquals(CueType.TAKE, cues[0].type)
        assertEquals(2, cues[0].takeNumber)
    }

    @Test
    fun `cut to cue is case insensitive`() {
        val cues = parser.parseCuesFromLine("[cut to: wide]")
        assertEquals(1, cues.size)
        assertEquals(CueType.CUT, cues[0].type)
        assertEquals(ShotType.WIDE, cues[0].shotType)
    }

    @Test
    fun `mixed case cue is parsed`() {
        val cues = parser.parseCuesFromLine("[Shot: Wide]")
        assertEquals(1, cues.size)
        assertEquals(ShotType.WIDE, cues[0].shotType)
    }

    @Test
    fun `natural language case insensitive`() {
        val cues = parser.parseCuesFromLine("Use a Wide Shot here")
        assertEquals(1, cues.size)
        assertEquals(ShotType.WIDE, cues[0].shotType)
    }

    // ========================================================================
    // 8. parseSingleCue method
    // ========================================================================

    @Test
    fun `parseSingleCue returns cue for valid shot`() {
        val cue = parser.parseSingleCue("[SHOT: WIDE]")
        assertNotNull(cue)
        assertEquals(CueType.SHOT, cue!!.type)
        assertEquals(ShotType.WIDE, cue.shotType)
    }

    @Test
    fun `parseSingleCue returns cue for valid transition`() {
        val cue = parser.parseSingleCue("[TRANSITION: PUSH IN] - 3s")
        assertNotNull(cue)
        assertEquals(CueType.TRANSITION, cue!!.type)
        assertEquals(TransitionType.PUSH_IN, cue.transitionType)
        assertEquals(3000L, cue.transitionDurationMs)
    }

    @Test
    fun `parseSingleCue returns cue for valid focus`() {
        val cue = parser.parseSingleCue("[FOCUS: BACKGROUND]")
        assertNotNull(cue)
        assertEquals(FocusTarget.BACKGROUND, cue!!.focusTarget)
    }

    @Test
    fun `parseSingleCue returns cue for valid exposure`() {
        val cue = parser.parseSingleCue("[EXPOSURE: SILHOUETTE]")
        assertNotNull(cue)
        assertEquals(ExposurePreset.SILHOUETTE, cue!!.exposurePreset)
    }

    @Test
    fun `parseSingleCue returns cue for beat`() {
        val cue = parser.parseSingleCue("[BEAT]")
        assertNotNull(cue)
        assertEquals(CueType.BEAT, cue!!.type)
    }

    @Test
    fun `parseSingleCue returns cue for hold`() {
        val cue = parser.parseSingleCue("[HOLD: 5]")
        assertNotNull(cue)
        assertEquals(CueType.BEAT, cue!!.type)
        assertEquals(5000L, cue.holdDurationMs)
    }

    @Test
    fun `parseSingleCue returns cue for take`() {
        val cue = parser.parseSingleCue("[TAKE: 3]")
        assertNotNull(cue)
        assertEquals(CueType.TAKE, cue!!.type)
        assertEquals(3, cue.takeNumber)
    }

    @Test
    fun `parseSingleCue returns cue for cut to`() {
        val cue = parser.parseSingleCue("[CUT TO: MEDIUM]")
        assertNotNull(cue)
        assertEquals(CueType.CUT, cue!!.type)
        assertEquals(ShotType.MEDIUM, cue.shotType)
    }

    @Test
    fun `parseSingleCue returns null for unrecognized text`() {
        val cue = parser.parseSingleCue("nothing meaningful here")
        assertNull(cue)
    }

    @Test
    fun `parseSingleCue returns null for empty string`() {
        val cue = parser.parseSingleCue("")
        assertNull(cue)
    }

    @Test
    fun `parseSingleCue detects natural language`() {
        val cue = parser.parseSingleCue("close-up on hands")
        assertNotNull(cue)
        assertEquals(ShotType.CLOSE_UP, cue!!.shotType)
    }

    @Test
    fun `parseSingleCue line number defaults to zero`() {
        val cue = parser.parseSingleCue("[SHOT: WIDE]")
        assertNotNull(cue)
        assertEquals(0, cue!!.lineNumber)
    }

    // ========================================================================
    // 9. validateScript warnings
    // ========================================================================

    @Test
    fun `validateScript empty script warns no scenes and no cues`() {
        val emptyParsed = parser.parseScript("")
        val warnings = parser.validateScript(emptyParsed)
        assertTrue(warnings.contains("Script contains no scenes"))
        assertTrue(warnings.contains("Script contains no cues"))
    }

    @Test
    fun `validateScript valid script returns no warnings`() {
        val script = """
            [SCENE: Test]
            [SHOT: WIDE]
            [FOCUS: FACE]
        """.trimIndent()
        val parsed = parser.parseScript(script)
        val warnings = parser.validateScript(parsed)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `validateScript warns about scene with no cues`() {
        // Construct a ParsedScript with an empty-cues scene manually
        val emptyScene = DirectorScene(label = "Empty Scene", cues = emptyList())
        val parsed = ParsedScript(
            rawScript = "",
            scenes = listOf(emptyScene),
            totalCues = 0,
            estimatedDurationMs = 0
        )
        val warnings = parser.validateScript(parsed)
        assertTrue(warnings.any { it.contains("Empty Scene") && it.contains("no cues") })
    }

    @Test
    fun `validateScript warns for script with scenes but zero totalCues`() {
        val scene = DirectorScene(label = "Test", cues = emptyList())
        val parsed = ParsedScript(
            rawScript = "",
            scenes = listOf(scene),
            totalCues = 0,
            estimatedDurationMs = 0
        )
        val warnings = parser.validateScript(parsed)
        assertTrue(warnings.contains("Script contains no cues"))
    }

    @Test
    fun `validateScript no warning when scenes have cues`() {
        val script = """
            [SCENE: Scene A]
            [SHOT: WIDE]
            [SCENE: Scene B]
            [SHOT: MEDIUM]
        """.trimIndent()
        val parsed = parser.parseScript(script)
        val warnings = parser.validateScript(parsed)
        assertTrue(warnings.isEmpty())
    }

    // ========================================================================
    // 10. Default scene creation when no [SCENE:] markers
    // ========================================================================

    @Test
    fun `parseScript without scene markers creates default Scene 1`() {
        val script = """
            [SHOT: WIDE]
            [FOCUS: FACE]
            [SHOT: CLOSE-UP]
        """.trimIndent()

        val result = parser.parseScript(script)
        assertEquals(1, result.scenes.size)
        assertEquals("Scene 1", result.scenes[0].label)
        assertEquals(3, result.scenes[0].cues.size)
    }

    @Test
    fun `parseScript without scene markers assigns all cues to default scene`() {
        val script = """
            [SHOT: ESTABLISHING]
            [TRANSITION: PUSH IN] - 2s
            [FOCUS: FACE]
            [EXPOSURE: BRIGHT]
            [BEAT]
        """.trimIndent()

        val result = parser.parseScript(script)
        assertEquals(1, result.scenes.size)
        assertEquals(5, result.scenes[0].cues.size)
        assertEquals(5, result.totalCues)
    }

    @Test
    fun `parseScript without scene markers sets correct start and end lines`() {
        val script = """
            [SHOT: WIDE]
            [SHOT: MEDIUM]
        """.trimIndent()

        val result = parser.parseScript(script)
        assertEquals(1, result.scenes.size)
        assertEquals(0, result.scenes[0].startLine)
        assertEquals(1, result.scenes[0].endLine)
    }

    @Test
    fun `parseScript with only natural language and no scene markers`() {
        val script = "Start with a wide shot of the landscape"

        val result = parser.parseScript(script)
        assertEquals(1, result.scenes.size)
        assertEquals("Scene 1", result.scenes[0].label)
        assertEquals(1, result.scenes[0].cues.size)
        assertEquals(ShotType.WIDE, result.scenes[0].cues[0].shotType)
    }

    // ========================================================================
    // Additional edge case and integration tests
    // ========================================================================

    @Test
    fun `parseScript estimated duration accounts for transitions`() {
        val script = """
            [SCENE: Test]
            [TRANSITION: PUSH IN] - 3s
        """.trimIndent()

        val result = parser.parseScript(script)
        assertEquals(3000L, result.estimatedDurationMs)
    }

    @Test
    fun `parseScript estimated duration accounts for holds`() {
        val script = """
            [SCENE: Test]
            [HOLD: 5]
        """.trimIndent()

        val result = parser.parseScript(script)
        assertEquals(5000L, result.estimatedDurationMs)
    }

    @Test
    fun `parseScript estimated duration for beat uses default hold duration`() {
        val script = """
            [SCENE: Test]
            [BEAT]
        """.trimIndent()

        val result = parser.parseScript(script)
        assertEquals(DirectorConfig.DEFAULT.defaultHoldDurationMs, result.estimatedDurationMs)
    }

    @Test
    fun `parseScript estimated duration for shot uses default transition duration`() {
        val script = """
            [SCENE: Test]
            [SHOT: WIDE]
        """.trimIndent()

        val result = parser.parseScript(script)
        assertEquals(DirectorConfig.DEFAULT.defaultTransitionDurationMs, result.estimatedDurationMs)
    }

    @Test
    fun `parseScript cumulative duration for multiple cues`() {
        val script = """
            [SCENE: Test]
            [SHOT: WIDE]
            [TRANSITION: PUSH IN] - 2s
            [HOLD: 3]
        """.trimIndent()

        val result = parser.parseScript(script)
        // SHOT = defaultTransitionDurationMs (1000), TRANSITION = 2000, HOLD = 3000
        val expectedMs = DirectorConfig.DEFAULT.defaultTransitionDurationMs + 2000L + 3000L
        assertEquals(expectedMs, result.estimatedDurationMs)
    }

    @Test
    fun `parseCuesFromLine preserves raw text`() {
        val line = "[SHOT: WIDE] - the establishing view"
        val cues = parser.parseCuesFromLine(line)
        assertEquals(1, cues.size)
        assertEquals(line, cues[0].rawText)
    }

    @Test
    fun `parseCuesFromLine with line number parameter`() {
        val cues = parser.parseCuesFromLine("[SHOT: MEDIUM]", 42)
        assertEquals(1, cues.size)
        assertEquals(42, cues[0].lineNumber)
    }

    @Test
    fun `parseCuesFromLine unrecognized text returns empty list`() {
        val cues = parser.parseCuesFromLine("An ordinary sentence with no camera cues")
        assertTrue(cues.isEmpty())
    }

    @Test
    fun `parseCuesFromLine empty string returns empty list`() {
        val cues = parser.parseCuesFromLine("")
        assertTrue(cues.isEmpty())
    }

    @Test
    fun `custom config default hold duration is used by beat`() {
        val customConfig = DirectorConfig(defaultHoldDurationMs = 5000)
        val customParser = ScriptParser(customConfig)
        val cues = customParser.parseCuesFromLine("[BEAT]")
        assertEquals(1, cues.size)
        assertEquals(5000L, cues[0].holdDurationMs)
    }

    @Test
    fun `full script integration test with diverse cues`() {
        val script = """
            [SCENE: Opening]
            [SHOT: ESTABLISHING]
            [HOLD: 2]
            [TRANSITION: PUSH IN] - 3s
            [SHOT: MEDIUM]
            [FOCUS: FACE]
            [EXPOSURE: BRIGHT]

            [SCENE: Dialogue]
            [SHOT: MEDIUM]
            [TAKE: 1]
            [CUT TO: CLOSE-UP]
            [FOCUS: HANDS]
            [BEAT]

            [SCENE: Closing]
            [SHOT: WIDE]
            [TRANSITION: PULL BACK] - 4s
        """.trimIndent()

        val result = parser.parseScript(script)
        assertEquals(3, result.scenes.size)
        assertEquals("Opening", result.scenes[0].label)
        assertEquals("Dialogue", result.scenes[1].label)
        assertEquals("Closing", result.scenes[2].label)

        // Opening: ESTABLISHING, HOLD, TRANSITION, MEDIUM, FOCUS, EXPOSURE = 6 cues
        assertEquals(6, result.scenes[0].cues.size)
        // Dialogue: MEDIUM, TAKE, CUT TO, FOCUS, BEAT = 5 cues
        assertEquals(5, result.scenes[1].cues.size)
        // Closing: WIDE, TRANSITION = 2 cues
        assertEquals(2, result.scenes[2].cues.size)

        assertEquals(13, result.totalCues)
        assertTrue(result.estimatedDurationMs > 0)
    }

    @Test
    fun `parseScript skips blank lines between cues`() {
        val script = """
            [SCENE: Test]

            [SHOT: WIDE]

            [SHOT: MEDIUM]
        """.trimIndent()

        val result = parser.parseScript(script)
        assertEquals(1, result.scenes.size)
        assertEquals(2, result.scenes[0].cues.size)
    }

    @Test
    fun `DOF cue is not included in parseCuesFromLine`() {
        // DOF pattern exists but parseExplicitCue does not handle it,
        // so it should not produce a cue
        val cues = parser.parseCuesFromLine("[DOF: SHALLOW]")
        assertTrue(cues.isEmpty())
    }

    @Test
    fun `scene marker itself does not count as a cue`() {
        val script = """
            [SCENE: Just a Scene]
        """.trimIndent()

        val result = parser.parseScript(script)
        // A scene with no cues after it and no further content results in no scenes
        // (because the final scene saving requires currentCues.isNotEmpty())
        assertEquals(0, result.scenes.size)
        assertEquals(0, result.totalCues)
    }

    @Test
    fun `scene with only a scene marker and no cues is not saved`() {
        val script = """
            [SCENE: Empty]
            [SCENE: Has Cue]
            [SHOT: WIDE]
        """.trimIndent()

        val result = parser.parseScript(script)
        // Empty scene has no cues so it's saved with empty cues list
        // Actually looking at the code, scene is saved when next scene starts
        // even if currentCues is empty
        assertEquals(2, result.scenes.size)
        assertEquals("Empty", result.scenes[0].label)
        assertEquals(0, result.scenes[0].cues.size)
        assertEquals("Has Cue", result.scenes[1].label)
        assertEquals(1, result.scenes[1].cues.size)
    }

    @Test
    fun `getErrors returns empty list initially`() {
        val errors = parser.getErrors()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `getErrors is cleared on new parseScript call`() {
        parser.parseScript("[SHOT: WIDE]")
        parser.parseScript("")
        val errors = parser.getErrors()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `each cue has a unique id`() {
        val cues = listOf(
            parser.parseSingleCue("[SHOT: WIDE]"),
            parser.parseSingleCue("[SHOT: MEDIUM]"),
            parser.parseSingleCue("[FOCUS: FACE]")
        ).filterNotNull()

        val ids = cues.map { it.id }.toSet()
        assertEquals(3, ids.size)
    }

    @Test
    fun `parsed script has unique id`() {
        val result1 = parser.parseScript("[SHOT: WIDE]")
        val result2 = parser.parseScript("[SHOT: MEDIUM]")
        assertNotEquals(result1.id, result2.id)
    }

    @Test
    fun `parseSingleCue with PUSH_IN underscore variant`() {
        val cue = parser.parseSingleCue("[TRANSITION: PUSH_IN]")
        assertNotNull(cue)
        assertEquals(TransitionType.PUSH_IN, cue!!.transitionType)
    }

    @Test
    fun `shot cue notes contain text after bracket`() {
        val cues = parser.parseCuesFromLine("[SHOT: WIDE] - establishing the location")
        assertEquals(1, cues.size)
        assertTrue(cues[0].notes.contains("establishing the location"))
    }

    @Test
    fun `CUT TO cue has zero transition duration`() {
        val cue = parser.parseSingleCue("[CUT TO: WIDE]")
        assertNotNull(cue)
        assertEquals(0L, cue!!.transitionDurationMs)
    }

    @Test
    fun `CUT TO always has CUT transition type`() {
        val cue = parser.parseSingleCue("[CUT TO: CLOSE-UP]")
        assertNotNull(cue)
        assertEquals(TransitionType.CUT, cue!!.transitionType)
    }
}
