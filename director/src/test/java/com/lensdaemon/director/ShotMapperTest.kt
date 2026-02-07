package com.lensdaemon.director

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive unit tests for ShotMapper.
 *
 * ShotMapper translates parsed DirectorCue objects into concrete CameraCommand
 * objects based on available camera hardware capabilities.
 *
 * Note: ShotMapper uses Timber for logging. Timber without planted Trees
 * silently discards messages, so no mocking is needed for unit tests.
 */
class ShotMapperTest {

    private lateinit var mapper: ShotMapper

    // Default capabilities with all features available
    private val fullCapabilities = ShotMapper.CameraCapabilities(
        hasWide = true,
        hasMain = true,
        hasTelephoto = true,
        maxDigitalZoom = 8.0f,
        supportsSmoothZoom = true,
        supportsFaceDetection = true,
        supportsManualFocus = true,
        wideFocalLength = ShotMapper.WIDE_FOCAL_LENGTH,
        mainFocalLength = ShotMapper.MAIN_FOCAL_LENGTH,
        telephotoFocalLength = ShotMapper.TELEPHOTO_FOCAL_LENGTH
    )

    // Minimal capabilities: main lens only
    private val minimalCapabilities = ShotMapper.CameraCapabilities(
        hasWide = false,
        hasMain = true,
        hasTelephoto = false,
        maxDigitalZoom = 4.0f,
        supportsSmoothZoom = false,
        supportsFaceDetection = false,
        supportsManualFocus = false
    )

    @Before
    fun setUp() {
        mapper = ShotMapper(DirectorConfig.DEFAULT)
        mapper.updateCapabilities(fullCapabilities)
    }

    // ========================================================================
    // Helper methods for creating cues
    // ========================================================================

    private fun shotCue(
        shotType: ShotType? = null,
        focusTarget: FocusTarget? = null,
        transitionType: TransitionType? = null,
        transitionDurationMs: Long = 1000
    ) = DirectorCue(
        type = CueType.SHOT,
        rawText = "[SHOT: ${shotType?.name ?: "UNKNOWN"}]",
        shotType = shotType,
        focusTarget = focusTarget,
        transitionType = transitionType,
        transitionDurationMs = transitionDurationMs
    )

    private fun transitionCue(
        transitionType: TransitionType? = null,
        transitionDurationMs: Long = 1000
    ) = DirectorCue(
        type = CueType.TRANSITION,
        rawText = "[TRANSITION: ${transitionType?.name ?: "UNKNOWN"}]",
        transitionType = transitionType,
        transitionDurationMs = transitionDurationMs
    )

    private fun focusCue(focusTarget: FocusTarget? = null) = DirectorCue(
        type = CueType.FOCUS,
        rawText = "[FOCUS: ${focusTarget?.name ?: "UNKNOWN"}]",
        focusTarget = focusTarget
    )

    private fun exposureCue(exposurePreset: ExposurePreset? = null) = DirectorCue(
        type = CueType.EXPOSURE,
        rawText = "[EXPOSURE: ${exposurePreset?.name ?: "UNKNOWN"}]",
        exposurePreset = exposurePreset
    )

    private fun cutCue(
        shotType: ShotType? = null,
        focusTarget: FocusTarget? = null
    ) = DirectorCue(
        type = CueType.CUT,
        rawText = "[CUT TO: ${shotType?.name ?: "UNKNOWN"}]",
        shotType = shotType,
        focusTarget = focusTarget
    )

    private fun beatCue() = DirectorCue(
        type = CueType.BEAT,
        rawText = "[BEAT]"
    )

    private fun takeCue(takeNumber: Int = 1) = DirectorCue(
        type = CueType.TAKE,
        rawText = "[TAKE: $takeNumber]",
        takeNumber = takeNumber
    )

    private fun sceneCue(label: String = "Test Scene") = DirectorCue(
        type = CueType.SCENE,
        rawText = "[SCENE: $label]",
        sceneLabel = label
    )

    // ========================================================================
    // 1. mapCue() with SHOT cues - lens selection
    // ========================================================================

    @Test
    fun `SHOT WIDE maps to wide lens`() {
        val result = mapper.mapCue(shotCue(ShotType.WIDE))
        assertTrue(result.success)
        assertNotNull(result.command)
        assertEquals("wide", result.command!!.lens)
    }

    @Test
    fun `SHOT ESTABLISHING maps to wide lens`() {
        val result = mapper.mapCue(shotCue(ShotType.ESTABLISHING))
        assertTrue(result.success)
        assertNotNull(result.command)
        assertEquals("wide", result.command!!.lens)
    }

    @Test
    fun `SHOT CLOSE_UP maps to telephoto lens`() {
        val result = mapper.mapCue(shotCue(ShotType.CLOSE_UP))
        assertTrue(result.success)
        assertNotNull(result.command)
        assertEquals("tele", result.command!!.lens)
    }

    @Test
    fun `SHOT EXTREME_CLOSE maps to telephoto lens`() {
        val result = mapper.mapCue(shotCue(ShotType.EXTREME_CLOSE))
        assertTrue(result.success)
        assertNotNull(result.command)
        assertEquals("tele", result.command!!.lens)
    }

    @Test
    fun `SHOT OVER_SHOULDER maps to telephoto lens`() {
        val result = mapper.mapCue(shotCue(ShotType.OVER_SHOULDER))
        assertTrue(result.success)
        assertNotNull(result.command)
        assertEquals("tele", result.command!!.lens)
    }

    @Test
    fun `SHOT MEDIUM maps to main lens`() {
        val result = mapper.mapCue(shotCue(ShotType.MEDIUM))
        assertTrue(result.success)
        assertNotNull(result.command)
        assertEquals("main", result.command!!.lens)
    }

    @Test
    fun `SHOT FULL_SHOT maps to main lens`() {
        val result = mapper.mapCue(shotCue(ShotType.FULL_SHOT))
        assertTrue(result.success)
        assertNotNull(result.command)
        assertEquals("main", result.command!!.lens)
    }

    @Test
    fun `SHOT MEDIUM_CLOSE maps to main lens`() {
        val result = mapper.mapCue(shotCue(ShotType.MEDIUM_CLOSE))
        assertTrue(result.success)
        assertNotNull(result.command)
        assertEquals("main", result.command!!.lens)
    }

    @Test
    fun `SHOT CUSTOM maps to main lens`() {
        val result = mapper.mapCue(shotCue(ShotType.CUSTOM))
        assertTrue(result.success)
        assertNotNull(result.command)
        assertEquals("main", result.command!!.lens)
    }

    @Test
    fun `SHOT cue result contains the original cue`() {
        val cue = shotCue(ShotType.WIDE)
        val result = mapper.mapCue(cue)
        assertEquals(cue, result.cue)
    }

    @Test
    fun `SHOT cue with full capabilities has no warnings`() {
        val result = mapper.mapCue(shotCue(ShotType.WIDE))
        assertTrue(result.warnings.isEmpty())
        assertFalse(result.fallbackUsed)
    }

    @Test
    fun `SHOT cue note contains shot type name`() {
        val result = mapper.mapCue(shotCue(ShotType.MEDIUM))
        assertTrue(result.command!!.notes.contains("MEDIUM"))
    }

    // ========================================================================
    // 1b. SHOT cues - zoom level ranges
    // ========================================================================

    @Test
    fun `SHOT ESTABLISHING zoom is in expected range`() {
        val result = mapper.mapCue(shotCue(ShotType.ESTABLISHING))
        val zoom = result.command!!.zoomLevel
        // ESTABLISHING range 0.5..1.0 midpoint = 0.75, coerced to 0.5..1.0
        assertTrue("Zoom $zoom should be between 0.5 and 1.0", zoom in 0.5f..1.0f)
    }

    @Test
    fun `SHOT WIDE zoom is in expected range`() {
        val result = mapper.mapCue(shotCue(ShotType.WIDE))
        val zoom = result.command!!.zoomLevel
        // WIDE range 0.6..1.2, midpoint = 0.9, coerced to 0.5..1.0
        assertTrue("Zoom $zoom should be between 0.5 and 1.0", zoom in 0.5f..1.0f)
    }

    @Test
    fun `SHOT CLOSE_UP zoom is in expected range for telephoto`() {
        val result = mapper.mapCue(shotCue(ShotType.CLOSE_UP))
        val zoom = result.command!!.zoomLevel
        // CLOSE_UP range 2.0..3.5, midpoint = 2.75, coerced to 1.0..2.0
        assertTrue("Zoom $zoom should be between 1.0 and 2.0", zoom in 1.0f..2.0f)
    }

    @Test
    fun `SHOT MEDIUM zoom is in expected range`() {
        val result = mapper.mapCue(shotCue(ShotType.MEDIUM))
        val zoom = result.command!!.zoomLevel
        // MEDIUM range 1.0..2.0, midpoint = 1.5, coerced to 1.0..2.0
        assertTrue("Zoom $zoom should be between 1.0 and 2.0", zoom in 1.0f..2.0f)
    }

    // ========================================================================
    // 1c. SHOT cues - focus mode selection
    // ========================================================================

    @Test
    fun `SHOT WIDE defaults to AUTO focus`() {
        val result = mapper.mapCue(shotCue(ShotType.WIDE))
        assertEquals(FocusTarget.AUTO, result.command!!.focusMode)
    }

    @Test
    fun `SHOT ESTABLISHING defaults to AUTO focus`() {
        val result = mapper.mapCue(shotCue(ShotType.ESTABLISHING))
        assertEquals(FocusTarget.AUTO, result.command!!.focusMode)
    }

    @Test
    fun `SHOT CLOSE_UP defaults to FACE focus when face detection available`() {
        val result = mapper.mapCue(shotCue(ShotType.CLOSE_UP))
        assertEquals(FocusTarget.FACE, result.command!!.focusMode)
    }

    @Test
    fun `SHOT EXTREME_CLOSE defaults to FACE focus when face detection available`() {
        val result = mapper.mapCue(shotCue(ShotType.EXTREME_CLOSE))
        assertEquals(FocusTarget.FACE, result.command!!.focusMode)
    }

    @Test
    fun `SHOT MEDIUM_CLOSE defaults to FACE focus when face detection available`() {
        val result = mapper.mapCue(shotCue(ShotType.MEDIUM_CLOSE))
        assertEquals(FocusTarget.FACE, result.command!!.focusMode)
    }

    @Test
    fun `SHOT OVER_SHOULDER defaults to FACE focus`() {
        val result = mapper.mapCue(shotCue(ShotType.OVER_SHOULDER))
        assertEquals(FocusTarget.FACE, result.command!!.focusMode)
    }

    @Test
    fun `SHOT CLOSE_UP defaults to AUTO focus when face detection not available`() {
        mapper.updateCapabilities(fullCapabilities.copy(supportsFaceDetection = false))
        val result = mapper.mapCue(shotCue(ShotType.CLOSE_UP))
        assertEquals(FocusTarget.AUTO, result.command!!.focusMode)
    }

    @Test
    fun `SHOT cue with explicit focusTarget overrides default`() {
        val result = mapper.mapCue(shotCue(ShotType.WIDE, focusTarget = FocusTarget.HANDS))
        assertEquals(FocusTarget.HANDS, result.command!!.focusMode)
    }

    @Test
    fun `SHOT MEDIUM defaults to AUTO focus`() {
        val result = mapper.mapCue(shotCue(ShotType.MEDIUM))
        assertEquals(FocusTarget.AUTO, result.command!!.focusMode)
    }

    // ========================================================================
    // 1d. SHOT cues - transition type and duration
    // ========================================================================

    @Test
    fun `SHOT cue without explicit transition uses CUT`() {
        val result = mapper.mapCue(shotCue(ShotType.MEDIUM))
        assertEquals(TransitionType.CUT, result.command!!.transitionType)
    }

    @Test
    fun `SHOT cue with explicit transitionType preserves it`() {
        val result = mapper.mapCue(shotCue(ShotType.MEDIUM, transitionType = TransitionType.PUSH_IN))
        assertEquals(TransitionType.PUSH_IN, result.command!!.transitionType)
    }

    @Test
    fun `SHOT cue preserves transition duration`() {
        val result = mapper.mapCue(shotCue(ShotType.MEDIUM, transitionDurationMs = 2500))
        assertEquals(2500L, result.command!!.transitionDurationMs)
    }

    // ========================================================================
    // 2. mapCue() with TRANSITION cues
    // ========================================================================

    @Test
    fun `TRANSITION PUSH_IN increases zoom by approximately 1_5x`() {
        // Set initial state with a shot
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        val initialZoom = mapper.getCurrentCommand()!!.zoomLevel

        val result = mapper.mapCue(transitionCue(TransitionType.PUSH_IN))
        assertTrue(result.success)
        assertNotNull(result.command)
        assertEquals(TransitionType.PUSH_IN, result.command!!.transitionType)

        val expectedZoom = initialZoom * 1.5f
        assertEquals(expectedZoom, result.command!!.zoomLevel, 0.01f)
    }

    @Test
    fun `TRANSITION PUSH_IN caps zoom at maxDigitalZoom`() {
        // Set capabilities with low max zoom
        mapper.updateCapabilities(fullCapabilities.copy(maxDigitalZoom = 2.0f))

        // Set initial zoom close to max
        mapper.mapCue(shotCue(ShotType.CLOSE_UP))
        val result = mapper.mapCue(transitionCue(TransitionType.PUSH_IN))

        assertTrue(result.success)
        assertTrue(
            "Zoom ${result.command!!.zoomLevel} should not exceed 2.0",
            result.command!!.zoomLevel <= 2.0f
        )
    }

    @Test
    fun `TRANSITION PULL_BACK decreases zoom by approximately 0_67x`() {
        // Set initial state
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        val initialZoom = mapper.getCurrentCommand()!!.zoomLevel

        val result = mapper.mapCue(transitionCue(TransitionType.PULL_BACK))
        assertTrue(result.success)
        assertNotNull(result.command)
        assertEquals(TransitionType.PULL_BACK, result.command!!.transitionType)

        val expectedZoom = (initialZoom * 0.67f).coerceAtLeast(0.5f)
        assertEquals(expectedZoom, result.command!!.zoomLevel, 0.01f)
    }

    @Test
    fun `TRANSITION PULL_BACK does not go below 0_5`() {
        // Start from a very low zoom by pulling back multiple times
        mapper.mapCue(shotCue(ShotType.WIDE))
        mapper.mapCue(transitionCue(TransitionType.PULL_BACK))
        mapper.mapCue(transitionCue(TransitionType.PULL_BACK))
        val result = mapper.mapCue(transitionCue(TransitionType.PULL_BACK))

        assertTrue(result.success)
        assertTrue(
            "Zoom ${result.command!!.zoomLevel} should be at least 0.5",
            result.command!!.zoomLevel >= 0.5f
        )
    }

    @Test
    fun `TRANSITION RACK_FOCUS toggles from FACE to BACKGROUND`() {
        // Set initial state with FACE focus
        mapper.mapCue(shotCue(ShotType.CLOSE_UP)) // defaults to FACE
        assertEquals(FocusTarget.FACE, mapper.getCurrentCommand()!!.focusMode)

        val result = mapper.mapCue(transitionCue(TransitionType.RACK_FOCUS))
        assertTrue(result.success)
        assertEquals(FocusTarget.BACKGROUND, result.command!!.focusMode)
        assertEquals(TransitionType.RACK_FOCUS, result.command!!.transitionType)
    }

    @Test
    fun `TRANSITION RACK_FOCUS toggles from non-FACE to FACE`() {
        // Set initial state with AUTO focus
        mapper.mapCue(shotCue(ShotType.WIDE)) // defaults to AUTO
        assertEquals(FocusTarget.AUTO, mapper.getCurrentCommand()!!.focusMode)

        val result = mapper.mapCue(transitionCue(TransitionType.RACK_FOCUS))
        assertTrue(result.success)
        assertEquals(FocusTarget.FACE, result.command!!.focusMode)
    }

    @Test
    fun `TRANSITION RACK_FOCUS toggles from BACKGROUND to FACE`() {
        // Set to BACKGROUND via focus cue
        mapper.mapCue(focusCue(FocusTarget.BACKGROUND))

        val result = mapper.mapCue(transitionCue(TransitionType.RACK_FOCUS))
        assertTrue(result.success)
        assertEquals(FocusTarget.FACE, result.command!!.focusMode)
    }

    @Test
    fun `TRANSITION HOLD keeps all current settings`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        val before = mapper.getCurrentCommand()!!

        val result = mapper.mapCue(transitionCue(TransitionType.HOLD, transitionDurationMs = 3000))
        assertTrue(result.success)
        assertEquals(before.lens, result.command!!.lens)
        assertEquals(before.zoomLevel, result.command!!.zoomLevel, 0.001f)
        assertEquals(before.focusMode, result.command!!.focusMode)
        assertEquals(TransitionType.HOLD, result.command!!.transitionType)
        assertEquals(3000L, result.command!!.transitionDurationMs)
    }

    @Test
    fun `TRANSITION CUT sets transition duration to zero`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))

        val result = mapper.mapCue(transitionCue(TransitionType.CUT, transitionDurationMs = 5000))
        assertTrue(result.success)
        assertEquals(TransitionType.CUT, result.command!!.transitionType)
        assertEquals(0L, result.command!!.transitionDurationMs)
    }

    @Test
    fun `TRANSITION preserves duration from cue`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        val result = mapper.mapCue(transitionCue(TransitionType.PUSH_IN, transitionDurationMs = 2500))
        assertEquals(2500L, result.command!!.transitionDurationMs)
    }

    @Test
    fun `TRANSITION without smooth zoom support adds warning for PUSH_IN`() {
        mapper.updateCapabilities(fullCapabilities.copy(supportsSmoothZoom = false))
        mapper.mapCue(shotCue(ShotType.MEDIUM))

        val result = mapper.mapCue(transitionCue(TransitionType.PUSH_IN))
        assertTrue(result.success)
        assertTrue(result.warnings.any { it.contains("smooth zoom") })
    }

    @Test
    fun `TRANSITION without smooth zoom support adds warning for PULL_BACK`() {
        mapper.updateCapabilities(fullCapabilities.copy(supportsSmoothZoom = false))
        mapper.mapCue(shotCue(ShotType.MEDIUM))

        val result = mapper.mapCue(transitionCue(TransitionType.PULL_BACK))
        assertTrue(result.success)
        assertTrue(result.warnings.any { it.contains("smooth zoom") })
    }

    @Test
    fun `TRANSITION HOLD does not warn about smooth zoom`() {
        mapper.updateCapabilities(fullCapabilities.copy(supportsSmoothZoom = false))
        mapper.mapCue(shotCue(ShotType.MEDIUM))

        val result = mapper.mapCue(transitionCue(TransitionType.HOLD))
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `TRANSITION RACK_FOCUS does not warn about smooth zoom`() {
        mapper.updateCapabilities(fullCapabilities.copy(supportsSmoothZoom = false))
        mapper.mapCue(shotCue(ShotType.MEDIUM))

        val result = mapper.mapCue(transitionCue(TransitionType.RACK_FOCUS))
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `TRANSITION uses default command when no prior state`() {
        // Do not set any initial state - mapper.reset() is implicit
        mapper.reset()
        val result = mapper.mapCue(transitionCue(TransitionType.PUSH_IN))
        assertTrue(result.success)
        assertNotNull(result.command)
        // Default command: MAIN lens, zoom 1.0, AUTO focus
        assertEquals("main", result.command!!.lens)
        // Push in from 1.0 -> 1.5
        assertEquals(1.5f, result.command!!.zoomLevel, 0.01f)
    }

    @Test
    fun `TRANSITION PUSH_IN note contains zoom level`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        val result = mapper.mapCue(transitionCue(TransitionType.PUSH_IN))
        assertTrue(result.command!!.notes.contains("Push in"))
    }

    @Test
    fun `TRANSITION PULL_BACK note contains zoom level`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        val result = mapper.mapCue(transitionCue(TransitionType.PULL_BACK))
        assertTrue(result.command!!.notes.contains("Pull back"))
    }

    // ========================================================================
    // 3. mapCue() with FOCUS cues
    // ========================================================================

    @Test
    fun `FOCUS AUTO maps successfully`() {
        val result = mapper.mapCue(focusCue(FocusTarget.AUTO))
        assertTrue(result.success)
        assertNotNull(result.command)
        assertEquals(FocusTarget.AUTO, result.command!!.focusMode)
    }

    @Test
    fun `FOCUS FACE maps successfully`() {
        val result = mapper.mapCue(focusCue(FocusTarget.FACE))
        assertTrue(result.success)
        assertEquals(FocusTarget.FACE, result.command!!.focusMode)
    }

    @Test
    fun `FOCUS HANDS maps successfully`() {
        val result = mapper.mapCue(focusCue(FocusTarget.HANDS))
        assertTrue(result.success)
        assertEquals(FocusTarget.HANDS, result.command!!.focusMode)
    }

    @Test
    fun `FOCUS OBJECT maps successfully`() {
        val result = mapper.mapCue(focusCue(FocusTarget.OBJECT))
        assertTrue(result.success)
        assertEquals(FocusTarget.OBJECT, result.command!!.focusMode)
    }

    @Test
    fun `FOCUS BACKGROUND maps successfully`() {
        val result = mapper.mapCue(focusCue(FocusTarget.BACKGROUND))
        assertTrue(result.success)
        assertEquals(FocusTarget.BACKGROUND, result.command!!.focusMode)
    }

    @Test
    fun `FOCUS MANUAL maps successfully`() {
        val result = mapper.mapCue(focusCue(FocusTarget.MANUAL))
        assertTrue(result.success)
        assertEquals(FocusTarget.MANUAL, result.command!!.focusMode)
    }

    @Test
    fun `FOCUS cue preserves other current command settings`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        val before = mapper.getCurrentCommand()!!

        val result = mapper.mapCue(focusCue(FocusTarget.BACKGROUND))
        assertTrue(result.success)
        assertEquals(before.lens, result.command!!.lens)
        assertEquals(before.zoomLevel, result.command!!.zoomLevel, 0.001f)
        assertEquals(before.exposurePreset, result.command!!.exposurePreset)
    }

    @Test
    fun `FOCUS cue note contains focus target name`() {
        val result = mapper.mapCue(focusCue(FocusTarget.HANDS))
        assertTrue(result.command!!.notes.contains("HANDS"))
    }

    @Test
    fun `FOCUS uses default command when no prior state`() {
        mapper.reset()
        val result = mapper.mapCue(focusCue(FocusTarget.FACE))
        assertTrue(result.success)
        assertEquals("main", result.command!!.lens)
        assertEquals(1.0f, result.command!!.zoomLevel, 0.001f)
    }

    // ========================================================================
    // 4. mapCue() with EXPOSURE cues
    // ========================================================================

    @Test
    fun `EXPOSURE AUTO maps successfully`() {
        val result = mapper.mapCue(exposureCue(ExposurePreset.AUTO))
        assertTrue(result.success)
        assertNotNull(result.command)
        assertEquals(ExposurePreset.AUTO, result.command!!.exposurePreset)
    }

    @Test
    fun `EXPOSURE BRIGHT maps successfully`() {
        val result = mapper.mapCue(exposureCue(ExposurePreset.BRIGHT))
        assertTrue(result.success)
        assertEquals(ExposurePreset.BRIGHT, result.command!!.exposurePreset)
    }

    @Test
    fun `EXPOSURE DARK maps successfully`() {
        val result = mapper.mapCue(exposureCue(ExposurePreset.DARK))
        assertTrue(result.success)
        assertEquals(ExposurePreset.DARK, result.command!!.exposurePreset)
    }

    @Test
    fun `EXPOSURE BACKLIT maps successfully`() {
        val result = mapper.mapCue(exposureCue(ExposurePreset.BACKLIT))
        assertTrue(result.success)
        assertEquals(ExposurePreset.BACKLIT, result.command!!.exposurePreset)
    }

    @Test
    fun `EXPOSURE SILHOUETTE maps successfully`() {
        val result = mapper.mapCue(exposureCue(ExposurePreset.SILHOUETTE))
        assertTrue(result.success)
        assertEquals(ExposurePreset.SILHOUETTE, result.command!!.exposurePreset)
    }

    @Test
    fun `EXPOSURE cue preserves other current command settings`() {
        mapper.mapCue(shotCue(ShotType.CLOSE_UP))
        val before = mapper.getCurrentCommand()!!

        val result = mapper.mapCue(exposureCue(ExposurePreset.DARK))
        assertTrue(result.success)
        assertEquals(before.lens, result.command!!.lens)
        assertEquals(before.zoomLevel, result.command!!.zoomLevel, 0.001f)
        assertEquals(before.focusMode, result.command!!.focusMode)
    }

    @Test
    fun `EXPOSURE cue note contains preset name`() {
        val result = mapper.mapCue(exposureCue(ExposurePreset.BACKLIT))
        assertTrue(result.command!!.notes.contains("BACKLIT"))
    }

    @Test
    fun `EXPOSURE cue has no warnings`() {
        val result = mapper.mapCue(exposureCue(ExposurePreset.BRIGHT))
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `EXPOSURE uses default command when no prior state`() {
        mapper.reset()
        val result = mapper.mapCue(exposureCue(ExposurePreset.SILHOUETTE))
        assertTrue(result.success)
        assertEquals("main", result.command!!.lens)
        assertEquals(1.0f, result.command!!.zoomLevel, 0.001f)
    }

    // ========================================================================
    // 5. mapCue() with CUT cues
    // ========================================================================

    @Test
    fun `CUT cue always uses CUT transition type`() {
        val result = mapper.mapCue(cutCue(ShotType.WIDE))
        assertTrue(result.success)
        assertEquals(TransitionType.CUT, result.command!!.transitionType)
    }

    @Test
    fun `CUT cue always has zero transition duration`() {
        val result = mapper.mapCue(cutCue(ShotType.MEDIUM))
        assertTrue(result.success)
        assertEquals(0L, result.command!!.transitionDurationMs)
    }

    @Test
    fun `CUT cue maps shot type to correct lens`() {
        val wideResult = mapper.mapCue(cutCue(ShotType.WIDE))
        assertEquals("wide", wideResult.command!!.lens)

        val closeResult = mapper.mapCue(cutCue(ShotType.CLOSE_UP))
        assertEquals("tele", closeResult.command!!.lens)

        val mediumResult = mapper.mapCue(cutCue(ShotType.MEDIUM))
        assertEquals("main", mediumResult.command!!.lens)
    }

    @Test
    fun `CUT cue without shotType defaults to MEDIUM`() {
        val result = mapper.mapCue(cutCue(shotType = null))
        assertTrue(result.success)
        assertNotNull(result.command)
        assertEquals("main", result.command!!.lens)
    }

    @Test
    fun `CUT cue note contains Cut to`() {
        val result = mapper.mapCue(cutCue(ShotType.WIDE))
        assertTrue(result.command!!.notes.contains("Cut to"))
    }

    @Test
    fun `CUT cue with explicit focusTarget uses it`() {
        val result = mapper.mapCue(cutCue(ShotType.MEDIUM, focusTarget = FocusTarget.HANDS))
        assertEquals(FocusTarget.HANDS, result.command!!.focusMode)
    }

    @Test
    fun `CUT cue without focusTarget uses default for shot type`() {
        val result = mapper.mapCue(cutCue(ShotType.WIDE))
        assertEquals(FocusTarget.AUTO, result.command!!.focusMode)
    }

    // ========================================================================
    // 6. mapCue() with non-mappable cues
    // ========================================================================

    @Test
    fun `BEAT cue returns success false`() {
        val result = mapper.mapCue(beatCue())
        assertFalse(result.success)
        assertNull(result.command)
    }

    @Test
    fun `TAKE cue returns success false`() {
        val result = mapper.mapCue(takeCue(1))
        assertFalse(result.success)
        assertNull(result.command)
    }

    @Test
    fun `SCENE cue returns success false`() {
        val result = mapper.mapCue(sceneCue("Test"))
        assertFalse(result.success)
        assertNull(result.command)
    }

    @Test
    fun `DEPTH cue returns success false`() {
        val cue = DirectorCue(type = CueType.DEPTH, rawText = "[DOF: SHALLOW]")
        val result = mapper.mapCue(cue)
        assertFalse(result.success)
        assertNull(result.command)
    }

    @Test
    fun `CUSTOM cue returns success false`() {
        val cue = DirectorCue(type = CueType.CUSTOM, rawText = "Custom action")
        val result = mapper.mapCue(cue)
        assertFalse(result.success)
        assertNull(result.command)
    }

    @Test
    fun `non-mappable cue warning contains cue type`() {
        val result = mapper.mapCue(beatCue())
        assertTrue(result.warnings.any { it.contains("BEAT") })
    }

    @Test
    fun `non-mappable cue preserves original cue in result`() {
        val cue = beatCue()
        val result = mapper.mapCue(cue)
        assertEquals(cue, result.cue)
    }

    // ========================================================================
    // 7. Missing data handling
    // ========================================================================

    @Test
    fun `SHOT cue without shotType returns success false`() {
        val result = mapper.mapCue(shotCue(shotType = null))
        assertFalse(result.success)
        assertNull(result.command)
        assertTrue(result.warnings.any { it.contains("missing shot type") })
    }

    @Test
    fun `TRANSITION cue without transitionType returns success false`() {
        val result = mapper.mapCue(transitionCue(transitionType = null))
        assertFalse(result.success)
        assertNull(result.command)
        assertTrue(result.warnings.any { it.contains("missing transition type") })
    }

    @Test
    fun `FOCUS cue without focusTarget returns success false`() {
        val result = mapper.mapCue(focusCue(focusTarget = null))
        assertFalse(result.success)
        assertNull(result.command)
        assertTrue(result.warnings.any { it.contains("missing focus target") })
    }

    @Test
    fun `EXPOSURE cue without exposurePreset returns success false`() {
        val result = mapper.mapCue(exposureCue(exposurePreset = null))
        assertFalse(result.success)
        assertNull(result.command)
        assertTrue(result.warnings.any { it.contains("missing preset") })
    }

    // ========================================================================
    // 8. Capabilities and warnings
    // ========================================================================

    @Test
    fun `SHOT WIDE warns when wide lens not available`() {
        mapper.updateCapabilities(minimalCapabilities)
        val result = mapper.mapCue(shotCue(ShotType.WIDE))
        assertTrue(result.success)
        assertTrue(result.fallbackUsed)
        assertTrue(result.warnings.any { it.contains("Wide lens not available") })
        assertEquals("main", result.command!!.lens)
    }

    @Test
    fun `SHOT ESTABLISHING warns when wide lens not available`() {
        mapper.updateCapabilities(minimalCapabilities)
        val result = mapper.mapCue(shotCue(ShotType.ESTABLISHING))
        assertTrue(result.success)
        assertTrue(result.fallbackUsed)
        assertTrue(result.warnings.any { it.contains("Wide lens not available") })
        assertEquals("main", result.command!!.lens)
    }

    @Test
    fun `SHOT WIDE fallback to main lens sets zoom to 1_0`() {
        mapper.updateCapabilities(minimalCapabilities)
        val result = mapper.mapCue(shotCue(ShotType.WIDE))
        assertEquals(1.0f, result.command!!.zoomLevel, 0.001f)
    }

    @Test
    fun `SHOT CLOSE_UP warns when telephoto not available`() {
        mapper.updateCapabilities(minimalCapabilities)
        val result = mapper.mapCue(shotCue(ShotType.CLOSE_UP))
        assertTrue(result.success)
        assertTrue(result.warnings.any { it.contains("Telephoto lens not available") })
        assertEquals("main", result.command!!.lens)
    }

    @Test
    fun `SHOT EXTREME_CLOSE warns when telephoto not available`() {
        mapper.updateCapabilities(minimalCapabilities)
        val result = mapper.mapCue(shotCue(ShotType.EXTREME_CLOSE))
        assertTrue(result.success)
        assertTrue(result.warnings.any { it.contains("Telephoto lens not available") })
        assertEquals("main", result.command!!.lens)
    }

    @Test
    fun `SHOT OVER_SHOULDER warns when telephoto not available`() {
        mapper.updateCapabilities(minimalCapabilities)
        val result = mapper.mapCue(shotCue(ShotType.OVER_SHOULDER))
        assertTrue(result.success)
        assertTrue(result.warnings.any { it.contains("Telephoto lens not available") })
    }

    @Test
    fun `SHOT CLOSE_UP without telephoto uses digital zoom on main`() {
        mapper.updateCapabilities(minimalCapabilities)
        val result = mapper.mapCue(shotCue(ShotType.CLOSE_UP))
        assertEquals("main", result.command!!.lens)
        // Zoom should be > 1.0 (digital zoom)
        assertTrue(
            "Zoom ${result.command!!.zoomLevel} should be >= 1.0",
            result.command!!.zoomLevel >= 1.0f
        )
    }

    @Test
    fun `SHOT CLOSE_UP without telephoto zoom capped at maxDigitalZoom`() {
        val lowZoomCaps = fullCapabilities.copy(hasTelephoto = false, maxDigitalZoom = 2.0f)
        mapper.updateCapabilities(lowZoomCaps)
        val result = mapper.mapCue(shotCue(ShotType.CLOSE_UP))
        assertTrue(
            "Zoom ${result.command!!.zoomLevel} should not exceed maxDigitalZoom 2.0",
            result.command!!.zoomLevel <= 2.0f
        )
    }

    @Test
    fun `FOCUS FACE warns when face detection not available`() {
        mapper.updateCapabilities(minimalCapabilities)
        val result = mapper.mapCue(focusCue(FocusTarget.FACE))
        assertTrue(result.success)
        assertTrue(result.warnings.any { it.contains("Face detection not available") })
    }

    @Test
    fun `FOCUS MANUAL warns when manual focus not available`() {
        mapper.updateCapabilities(minimalCapabilities)
        val result = mapper.mapCue(focusCue(FocusTarget.MANUAL))
        assertTrue(result.success)
        assertTrue(result.warnings.any { it.contains("Manual focus not available") })
    }

    @Test
    fun `FOCUS AUTO does not warn even with minimal capabilities`() {
        mapper.updateCapabilities(minimalCapabilities)
        val result = mapper.mapCue(focusCue(FocusTarget.AUTO))
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `FOCUS HANDS does not warn even with minimal capabilities`() {
        mapper.updateCapabilities(minimalCapabilities)
        val result = mapper.mapCue(focusCue(FocusTarget.HANDS))
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `FOCUS OBJECT does not warn even with minimal capabilities`() {
        mapper.updateCapabilities(minimalCapabilities)
        val result = mapper.mapCue(focusCue(FocusTarget.OBJECT))
        assertTrue(result.warnings.isEmpty())
    }

    @Test
    fun `FOCUS BACKGROUND does not warn even with minimal capabilities`() {
        mapper.updateCapabilities(minimalCapabilities)
        val result = mapper.mapCue(focusCue(FocusTarget.BACKGROUND))
        assertTrue(result.warnings.isEmpty())
    }

    // ========================================================================
    // 9. updateCapabilities() changes behavior
    // ========================================================================

    @Test
    fun `updateCapabilities changes lens selection for wide shots`() {
        // Initially has wide lens
        val resultWithWide = mapper.mapCue(shotCue(ShotType.WIDE))
        assertEquals("wide", resultWithWide.command!!.lens)

        // Remove wide lens
        mapper.updateCapabilities(fullCapabilities.copy(hasWide = false))

        val resultWithoutWide = mapper.mapCue(shotCue(ShotType.WIDE))
        assertEquals("main", resultWithoutWide.command!!.lens)
    }

    @Test
    fun `updateCapabilities changes lens selection for telephoto shots`() {
        val resultWithTele = mapper.mapCue(shotCue(ShotType.CLOSE_UP))
        assertEquals("tele", resultWithTele.command!!.lens)

        mapper.updateCapabilities(fullCapabilities.copy(hasTelephoto = false))

        val resultWithoutTele = mapper.mapCue(shotCue(ShotType.CLOSE_UP))
        assertEquals("main", resultWithoutTele.command!!.lens)
    }

    @Test
    fun `updateCapabilities changes face detection behavior`() {
        val resultWithFace = mapper.mapCue(focusCue(FocusTarget.FACE))
        assertTrue(resultWithFace.warnings.isEmpty())

        mapper.updateCapabilities(fullCapabilities.copy(supportsFaceDetection = false))

        val resultWithoutFace = mapper.mapCue(focusCue(FocusTarget.FACE))
        assertTrue(resultWithoutFace.warnings.any { it.contains("Face detection") })
    }

    @Test
    fun `updateCapabilities changes manual focus behavior`() {
        val resultWithManual = mapper.mapCue(focusCue(FocusTarget.MANUAL))
        assertTrue(resultWithManual.warnings.isEmpty())

        mapper.updateCapabilities(fullCapabilities.copy(supportsManualFocus = false))

        val resultWithoutManual = mapper.mapCue(focusCue(FocusTarget.MANUAL))
        assertTrue(resultWithoutManual.warnings.any { it.contains("Manual focus") })
    }

    @Test
    fun `updateCapabilities changes smooth zoom warning behavior`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        val resultSmooth = mapper.mapCue(transitionCue(TransitionType.PUSH_IN))
        assertTrue(resultSmooth.warnings.isEmpty())

        mapper.updateCapabilities(fullCapabilities.copy(supportsSmoothZoom = false))
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        val resultNoSmooth = mapper.mapCue(transitionCue(TransitionType.PUSH_IN))
        assertTrue(resultNoSmooth.warnings.any { it.contains("smooth zoom") })
    }

    // ========================================================================
    // 10. reset() clears current command state
    // ========================================================================

    @Test
    fun `reset clears current command`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        assertNotNull(mapper.getCurrentCommand())

        mapper.reset()
        assertNull(mapper.getCurrentCommand())
    }

    @Test
    fun `reset causes subsequent transition to use default command`() {
        mapper.mapCue(shotCue(ShotType.CLOSE_UP))
        mapper.reset()

        val result = mapper.mapCue(transitionCue(TransitionType.PUSH_IN))
        assertTrue(result.success)
        // Default command is MAIN lens at 1.0 zoom, push in -> 1.5
        assertEquals("main", result.command!!.lens)
        assertEquals(1.5f, result.command!!.zoomLevel, 0.01f)
    }

    @Test
    fun `getCurrentCommand is null initially`() {
        val freshMapper = ShotMapper()
        assertNull(freshMapper.getCurrentCommand())
    }

    @Test
    fun `reset allows state to be rebuilt from scratch`() {
        mapper.mapCue(shotCue(ShotType.CLOSE_UP))
        mapper.mapCue(exposureCue(ExposurePreset.SILHOUETTE))
        mapper.reset()

        mapper.mapCue(shotCue(ShotType.WIDE))
        val cmd = mapper.getCurrentCommand()!!
        assertEquals("wide", cmd.lens)
        // Exposure should be default (AUTO via shot cue default), not SILHOUETTE
        assertEquals(ExposurePreset.AUTO, cmd.exposurePreset)
    }

    // ========================================================================
    // 11. mapScene() maps all cues in sequence
    // ========================================================================

    @Test
    fun `mapScene maps all cues and returns results`() {
        val scene = DirectorScene(
            label = "Test Scene",
            cues = listOf(
                shotCue(ShotType.WIDE),
                focusCue(FocusTarget.FACE),
                exposureCue(ExposurePreset.BRIGHT)
            )
        )

        val results = mapper.mapScene(scene)
        assertEquals(3, results.size)
        assertTrue(results.all { it.success })
    }

    @Test
    fun `mapScene resets state before mapping`() {
        mapper.mapCue(shotCue(ShotType.CLOSE_UP))
        mapper.mapCue(exposureCue(ExposurePreset.DARK))

        val scene = DirectorScene(
            label = "Fresh Scene",
            cues = listOf(shotCue(ShotType.WIDE))
        )

        mapper.mapScene(scene)
        val cmd = mapper.getCurrentCommand()!!
        assertEquals("wide", cmd.lens)
    }

    @Test
    fun `mapScene cues accumulate state sequentially`() {
        val scene = DirectorScene(
            label = "Sequential",
            cues = listOf(
                shotCue(ShotType.MEDIUM),
                exposureCue(ExposurePreset.DARK),
                focusCue(FocusTarget.FACE)
            )
        )

        val results = mapper.mapScene(scene)

        // First result: MEDIUM shot with AUTO exposure and AUTO focus
        assertEquals("main", results[0].command!!.lens)
        assertEquals(ExposurePreset.AUTO, results[0].command!!.exposurePreset)

        // Second result: still MAIN lens, now DARK exposure
        assertEquals("main", results[1].command!!.lens)
        assertEquals(ExposurePreset.DARK, results[1].command!!.exposurePreset)

        // Third result: still MAIN lens, still DARK exposure, now FACE focus
        assertEquals("main", results[2].command!!.lens)
        assertEquals(ExposurePreset.DARK, results[2].command!!.exposurePreset)
        assertEquals(FocusTarget.FACE, results[2].command!!.focusMode)
    }

    @Test
    fun `mapScene with empty cues returns empty list`() {
        val scene = DirectorScene(label = "Empty", cues = emptyList())
        val results = mapper.mapScene(scene)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `mapScene includes non-mappable cues as failures`() {
        val scene = DirectorScene(
            label = "Mixed",
            cues = listOf(
                shotCue(ShotType.WIDE),
                beatCue(),
                exposureCue(ExposurePreset.BRIGHT)
            )
        )

        val results = mapper.mapScene(scene)
        assertEquals(3, results.size)
        assertTrue(results[0].success)
        assertFalse(results[1].success)
        assertTrue(results[2].success)
    }

    // ========================================================================
    // 12. validateMapping() checks hardware compatibility
    // ========================================================================

    @Test
    fun `validateMapping returns empty for compatible script`() {
        val scene = DirectorScene(
            label = "Compatible",
            cues = listOf(
                shotCue(ShotType.MEDIUM),
                focusCue(FocusTarget.AUTO)
            )
        )
        val script = ParsedScript(
            rawScript = "",
            scenes = listOf(scene),
            totalCues = 2,
            estimatedDurationMs = 2000
        )

        val issues = mapper.validateMapping(script)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `validateMapping warns about wide shot without wide lens`() {
        mapper.updateCapabilities(minimalCapabilities)

        val scene = DirectorScene(
            label = "Wide Scene",
            cues = listOf(
                DirectorCue(type = CueType.SHOT, rawText = "[SHOT: WIDE]", shotType = ShotType.WIDE, lineNumber = 5)
            )
        )
        val script = ParsedScript(
            rawScript = "",
            scenes = listOf(scene),
            totalCues = 1,
            estimatedDurationMs = 1000
        )

        val issues = mapper.validateMapping(script)
        assertTrue(issues.any { it.contains("Wide shot") && it.contains("wide lens not available") })
    }

    @Test
    fun `validateMapping warns about establishing shot without wide lens`() {
        mapper.updateCapabilities(minimalCapabilities)

        val scene = DirectorScene(
            label = "Establishing",
            cues = listOf(
                DirectorCue(type = CueType.SHOT, rawText = "[SHOT: ESTABLISHING]", shotType = ShotType.ESTABLISHING, lineNumber = 1)
            )
        )
        val script = ParsedScript(
            rawScript = "",
            scenes = listOf(scene),
            totalCues = 1,
            estimatedDurationMs = 1000
        )

        val issues = mapper.validateMapping(script)
        assertTrue(issues.any { it.contains("Wide shot") && it.contains("wide lens not available") })
    }

    @Test
    fun `validateMapping warns about close-up without telephoto and low zoom`() {
        mapper.updateCapabilities(minimalCapabilities.copy(maxDigitalZoom = 2.0f))

        val scene = DirectorScene(
            label = "Close Scene",
            cues = listOf(
                DirectorCue(type = CueType.SHOT, rawText = "[SHOT: CLOSE-UP]", shotType = ShotType.CLOSE_UP, lineNumber = 3)
            )
        )
        val script = ParsedScript(
            rawScript = "",
            scenes = listOf(scene),
            totalCues = 1,
            estimatedDurationMs = 1000
        )

        val issues = mapper.validateMapping(script)
        assertTrue(issues.any { it.contains("Close-up") && it.contains("telephoto") })
    }

    @Test
    fun `validateMapping does not warn about close-up with adequate digital zoom`() {
        mapper.updateCapabilities(minimalCapabilities.copy(maxDigitalZoom = 4.0f))

        val scene = DirectorScene(
            label = "Close Scene",
            cues = listOf(
                DirectorCue(type = CueType.SHOT, rawText = "[SHOT: CLOSE-UP]", shotType = ShotType.CLOSE_UP, lineNumber = 3)
            )
        )
        val script = ParsedScript(
            rawScript = "",
            scenes = listOf(scene),
            totalCues = 1,
            estimatedDurationMs = 1000
        )

        val issues = mapper.validateMapping(script)
        assertTrue(issues.none { it.contains("Close-up") })
    }

    @Test
    fun `validateMapping warns about extreme close-up without telephoto and low zoom`() {
        mapper.updateCapabilities(minimalCapabilities.copy(maxDigitalZoom = 2.0f))

        val scene = DirectorScene(
            label = "Detail Scene",
            cues = listOf(
                DirectorCue(type = CueType.SHOT, rawText = "[SHOT: EXTREME CLOSE]", shotType = ShotType.EXTREME_CLOSE, lineNumber = 7)
            )
        )
        val script = ParsedScript(
            rawScript = "",
            scenes = listOf(scene),
            totalCues = 1,
            estimatedDurationMs = 1000
        )

        val issues = mapper.validateMapping(script)
        assertTrue(issues.any { it.contains("Close-up") && it.contains("line 7") })
    }

    @Test
    fun `validateMapping warns about face focus without face detection`() {
        mapper.updateCapabilities(minimalCapabilities)

        val scene = DirectorScene(
            label = "Face Scene",
            cues = listOf(
                DirectorCue(type = CueType.FOCUS, rawText = "[FOCUS: FACE]", focusTarget = FocusTarget.FACE, lineNumber = 10)
            )
        )
        val script = ParsedScript(
            rawScript = "",
            scenes = listOf(scene),
            totalCues = 1,
            estimatedDurationMs = 1000
        )

        val issues = mapper.validateMapping(script)
        assertTrue(issues.any { it.contains("Face focus") && it.contains("face detection not available") })
    }

    @Test
    fun `validateMapping checks across multiple scenes`() {
        mapper.updateCapabilities(minimalCapabilities.copy(maxDigitalZoom = 2.0f))

        val scene1 = DirectorScene(
            label = "Scene 1",
            cues = listOf(
                DirectorCue(type = CueType.SHOT, rawText = "[SHOT: WIDE]", shotType = ShotType.WIDE, lineNumber = 1)
            )
        )
        val scene2 = DirectorScene(
            label = "Scene 2",
            cues = listOf(
                DirectorCue(type = CueType.SHOT, rawText = "[SHOT: CLOSE-UP]", shotType = ShotType.CLOSE_UP, lineNumber = 5)
            )
        )
        val script = ParsedScript(
            rawScript = "",
            scenes = listOf(scene1, scene2),
            totalCues = 2,
            estimatedDurationMs = 2000
        )

        val issues = mapper.validateMapping(script)
        assertTrue(issues.size >= 2)
        assertTrue(issues.any { it.contains("Wide shot") })
        assertTrue(issues.any { it.contains("Close-up") })
    }

    @Test
    fun `validateMapping ignores non-SHOT and non-FOCUS cues`() {
        mapper.updateCapabilities(minimalCapabilities)

        val scene = DirectorScene(
            label = "Safe Scene",
            cues = listOf(
                DirectorCue(type = CueType.EXPOSURE, rawText = "[EXPOSURE: BRIGHT]", exposurePreset = ExposurePreset.BRIGHT, lineNumber = 1),
                DirectorCue(type = CueType.BEAT, rawText = "[BEAT]", lineNumber = 2),
                DirectorCue(type = CueType.TAKE, rawText = "[TAKE: 1]", takeNumber = 1, lineNumber = 3)
            )
        )
        val script = ParsedScript(
            rawScript = "",
            scenes = listOf(scene),
            totalCues = 3,
            estimatedDurationMs = 3000
        )

        val issues = mapper.validateMapping(script)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `validateMapping with empty script returns empty list`() {
        val script = ParsedScript(
            rawScript = "",
            scenes = emptyList(),
            totalCues = 0,
            estimatedDurationMs = 0
        )

        val issues = mapper.validateMapping(script)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `validateMapping does not warn about MEDIUM shot regardless of capabilities`() {
        mapper.updateCapabilities(minimalCapabilities)

        val scene = DirectorScene(
            label = "Medium Scene",
            cues = listOf(
                DirectorCue(type = CueType.SHOT, rawText = "[SHOT: MEDIUM]", shotType = ShotType.MEDIUM, lineNumber = 1)
            )
        )
        val script = ParsedScript(
            rawScript = "",
            scenes = listOf(scene),
            totalCues = 1,
            estimatedDurationMs = 1000
        )

        val issues = mapper.validateMapping(script)
        assertTrue(issues.isEmpty())
    }

    @Test
    fun `validateMapping issue contains line number`() {
        mapper.updateCapabilities(minimalCapabilities)

        val scene = DirectorScene(
            label = "Test",
            cues = listOf(
                DirectorCue(type = CueType.SHOT, rawText = "[SHOT: WIDE]", shotType = ShotType.WIDE, lineNumber = 42)
            )
        )
        val script = ParsedScript(
            rawScript = "",
            scenes = listOf(scene),
            totalCues = 1,
            estimatedDurationMs = 1000
        )

        val issues = mapper.validateMapping(script)
        assertTrue(issues.any { it.contains("line 42") })
    }

    // ========================================================================
    // 13. calculateEffectiveFocalLength()
    // ========================================================================

    @Test
    fun `calculateEffectiveFocalLength for wide lens at 1x zoom`() {
        val command = ShotMapper.CameraCommand(
            lens = "wide",
            zoomLevel = 1.0f,
            focusMode = FocusTarget.AUTO
        )
        val focal = mapper.calculateEffectiveFocalLength(command)
        assertEquals(ShotMapper.WIDE_FOCAL_LENGTH, focal, 0.001f)
    }

    @Test
    fun `calculateEffectiveFocalLength for main lens at 1x zoom`() {
        val command = ShotMapper.CameraCommand(
            lens = "main",
            zoomLevel = 1.0f,
            focusMode = FocusTarget.AUTO
        )
        val focal = mapper.calculateEffectiveFocalLength(command)
        assertEquals(ShotMapper.MAIN_FOCAL_LENGTH, focal, 0.001f)
    }

    @Test
    fun `calculateEffectiveFocalLength for telephoto lens at 1x zoom`() {
        val command = ShotMapper.CameraCommand(
            lens = "tele",
            zoomLevel = 1.0f,
            focusMode = FocusTarget.AUTO
        )
        val focal = mapper.calculateEffectiveFocalLength(command)
        assertEquals(ShotMapper.TELEPHOTO_FOCAL_LENGTH, focal, 0.001f)
    }

    @Test
    fun `calculateEffectiveFocalLength multiplies by zoom level`() {
        val command = ShotMapper.CameraCommand(
            lens = "main",
            zoomLevel = 2.0f,
            focusMode = FocusTarget.AUTO
        )
        val focal = mapper.calculateEffectiveFocalLength(command)
        assertEquals(ShotMapper.MAIN_FOCAL_LENGTH * 2.0f, focal, 0.001f)
    }

    @Test
    fun `calculateEffectiveFocalLength with custom focal lengths`() {
        mapper.updateCapabilities(fullCapabilities.copy(
            wideFocalLength = 14f,
            mainFocalLength = 26f,
            telephotoFocalLength = 105f
        ))

        val wideCmd = ShotMapper.CameraCommand(
            lens = "wide", zoomLevel = 1.0f, focusMode = FocusTarget.AUTO
        )
        assertEquals(14f, mapper.calculateEffectiveFocalLength(wideCmd), 0.001f)

        val teleCmd = ShotMapper.CameraCommand(
            lens = "tele", zoomLevel = 1.5f, focusMode = FocusTarget.AUTO
        )
        assertEquals(105f * 1.5f, mapper.calculateEffectiveFocalLength(teleCmd), 0.001f)
    }

    @Test
    fun `calculateEffectiveFocalLength with fractional zoom`() {
        val command = ShotMapper.CameraCommand(
            lens = "wide",
            zoomLevel = 0.75f,
            focusMode = FocusTarget.AUTO
        )
        val focal = mapper.calculateEffectiveFocalLength(command)
        assertEquals(ShotMapper.WIDE_FOCAL_LENGTH * 0.75f, focal, 0.001f)
    }

    // ========================================================================
    // 14. getPresetForShot()
    // ========================================================================

    @Test
    fun `getPresetForShot returns preset for WIDE`() {
        val preset = mapper.getPresetForShot(ShotType.WIDE)
        assertNotNull(preset)
        assertEquals(ShotType.WIDE, preset!!.shotType)
    }

    @Test
    fun `getPresetForShot returns preset for MEDIUM`() {
        val preset = mapper.getPresetForShot(ShotType.MEDIUM)
        assertNotNull(preset)
        assertEquals(ShotType.MEDIUM, preset!!.shotType)
    }

    @Test
    fun `getPresetForShot returns preset for CLOSE_UP`() {
        val preset = mapper.getPresetForShot(ShotType.CLOSE_UP)
        assertNotNull(preset)
        assertEquals(ShotType.CLOSE_UP, preset!!.shotType)
    }

    @Test
    fun `getPresetForShot returns preset for ESTABLISHING`() {
        val preset = mapper.getPresetForShot(ShotType.ESTABLISHING)
        assertNotNull(preset)
        assertEquals(ShotType.ESTABLISHING, preset!!.shotType)
    }

    @Test
    fun `getPresetForShot returns preset for FULL_SHOT`() {
        val preset = mapper.getPresetForShot(ShotType.FULL_SHOT)
        assertNotNull(preset)
        assertEquals(ShotType.FULL_SHOT, preset!!.shotType)
    }

    @Test
    fun `getPresetForShot returns preset for MEDIUM_CLOSE`() {
        val preset = mapper.getPresetForShot(ShotType.MEDIUM_CLOSE)
        assertNotNull(preset)
        assertEquals(ShotType.MEDIUM_CLOSE, preset!!.shotType)
    }

    @Test
    fun `getPresetForShot returns preset for EXTREME_CLOSE`() {
        val preset = mapper.getPresetForShot(ShotType.EXTREME_CLOSE)
        assertNotNull(preset)
        assertEquals(ShotType.EXTREME_CLOSE, preset!!.shotType)
    }

    @Test
    fun `getPresetForShot returns preset for OVER_SHOULDER`() {
        val preset = mapper.getPresetForShot(ShotType.OVER_SHOULDER)
        assertNotNull(preset)
        assertEquals(ShotType.OVER_SHOULDER, preset!!.shotType)
    }

    @Test
    fun `getPresetForShot returns null for CUSTOM shot type`() {
        val preset = mapper.getPresetForShot(ShotType.CUSTOM)
        assertNull(preset)
    }

    @Test
    fun `getPresetForShot uses config shotPresets`() {
        val customPresets = mapOf(
            "test" to ShotPreset("test", ShotType.MEDIUM, "main", 1.5f, FocusTarget.FACE, "Custom medium")
        )
        val customConfig = DirectorConfig(shotPresets = customPresets)
        val customMapper = ShotMapper(customConfig)

        val preset = customMapper.getPresetForShot(ShotType.MEDIUM)
        assertNotNull(preset)
        assertEquals("test", preset!!.name)
        assertEquals("Custom medium", preset.description)
    }

    @Test
    fun `getPresetForShot with empty presets returns null`() {
        val customConfig = DirectorConfig(shotPresets = emptyMap())
        val customMapper = ShotMapper(customConfig)

        val preset = customMapper.getPresetForShot(ShotType.WIDE)
        assertNull(preset)
    }

    // ========================================================================
    // 15. getCurrentCommand() tracks state
    // ========================================================================

    @Test
    fun `getCurrentCommand returns null before any mapping`() {
        assertNull(ShotMapper().getCurrentCommand())
    }

    @Test
    fun `getCurrentCommand returns last mapped shot command`() {
        mapper.mapCue(shotCue(ShotType.WIDE))
        val cmd = mapper.getCurrentCommand()
        assertNotNull(cmd)
        assertEquals("wide", cmd!!.lens)
    }

    @Test
    fun `getCurrentCommand updates after each successful mapping`() {
        mapper.mapCue(shotCue(ShotType.WIDE))
        assertEquals("wide", mapper.getCurrentCommand()!!.lens)

        mapper.mapCue(shotCue(ShotType.CLOSE_UP))
        assertEquals("tele", mapper.getCurrentCommand()!!.lens)
    }

    @Test
    fun `getCurrentCommand does not change after non-mappable cue`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        val before = mapper.getCurrentCommand()

        mapper.mapCue(beatCue())
        val after = mapper.getCurrentCommand()

        assertEquals(before, after)
    }

    @Test
    fun `getCurrentCommand does not change after failed shot cue`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        val before = mapper.getCurrentCommand()

        mapper.mapCue(shotCue(shotType = null))
        val after = mapper.getCurrentCommand()

        assertEquals(before, after)
    }

    @Test
    fun `getCurrentCommand reflects focus changes`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        mapper.mapCue(focusCue(FocusTarget.BACKGROUND))
        assertEquals(FocusTarget.BACKGROUND, mapper.getCurrentCommand()!!.focusMode)
    }

    @Test
    fun `getCurrentCommand reflects exposure changes`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        mapper.mapCue(exposureCue(ExposurePreset.SILHOUETTE))
        assertEquals(ExposurePreset.SILHOUETTE, mapper.getCurrentCommand()!!.exposurePreset)
    }

    @Test
    fun `getCurrentCommand reflects transition zoom changes`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        val zoomBefore = mapper.getCurrentCommand()!!.zoomLevel

        mapper.mapCue(transitionCue(TransitionType.PUSH_IN))
        val zoomAfter = mapper.getCurrentCommand()!!.zoomLevel

        assertTrue("Zoom should increase after PUSH_IN", zoomAfter > zoomBefore)
    }

    @Test
    fun `getCurrentCommand reflects CUT cue changes`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        mapper.mapCue(cutCue(ShotType.WIDE))

        val cmd = mapper.getCurrentCommand()!!
        assertEquals("wide", cmd.lens)
        assertEquals(TransitionType.CUT, cmd.transitionType)
        assertEquals(0L, cmd.transitionDurationMs)
    }

    // ========================================================================
    // CameraCommand.differsFrom() tests
    // ========================================================================

    @Test
    fun `differsFrom returns false for identical commands`() {
        val cmd = ShotMapper.CameraCommand(
            lens = "main",
            zoomLevel = 1.0f,
            focusMode = FocusTarget.AUTO
        )
        assertFalse(cmd.differsFrom(cmd))
    }

    @Test
    fun `differsFrom returns true for different lens`() {
        val cmd1 = ShotMapper.CameraCommand(
            lens = "main", zoomLevel = 1.0f, focusMode = FocusTarget.AUTO
        )
        val cmd2 = cmd1.copy(lens = "wide")
        assertTrue(cmd1.differsFrom(cmd2))
    }

    @Test
    fun `differsFrom returns true for zoom difference greater than 0_1`() {
        val cmd1 = ShotMapper.CameraCommand(
            lens = "main", zoomLevel = 1.0f, focusMode = FocusTarget.AUTO
        )
        val cmd2 = cmd1.copy(zoomLevel = 1.2f)
        assertTrue(cmd1.differsFrom(cmd2))
    }

    @Test
    fun `differsFrom returns false for zoom difference less than 0_1`() {
        val cmd1 = ShotMapper.CameraCommand(
            lens = "main", zoomLevel = 1.0f, focusMode = FocusTarget.AUTO
        )
        val cmd2 = cmd1.copy(zoomLevel = 1.05f)
        assertFalse(cmd1.differsFrom(cmd2))
    }

    @Test
    fun `differsFrom returns true for different focus mode`() {
        val cmd1 = ShotMapper.CameraCommand(
            lens = "main", zoomLevel = 1.0f, focusMode = FocusTarget.AUTO
        )
        val cmd2 = cmd1.copy(focusMode = FocusTarget.FACE)
        assertTrue(cmd1.differsFrom(cmd2))
    }

    @Test
    fun `differsFrom ignores exposure preset difference`() {
        val cmd1 = ShotMapper.CameraCommand(
            lens = "main", zoomLevel = 1.0f, focusMode = FocusTarget.AUTO,
            exposurePreset = ExposurePreset.AUTO
        )
        val cmd2 = cmd1.copy(exposurePreset = ExposurePreset.DARK)
        assertFalse(cmd1.differsFrom(cmd2))
    }

    @Test
    fun `differsFrom ignores transition type difference`() {
        val cmd1 = ShotMapper.CameraCommand(
            lens = "main", zoomLevel = 1.0f, focusMode = FocusTarget.AUTO,
            transitionType = TransitionType.CUT
        )
        val cmd2 = cmd1.copy(transitionType = TransitionType.PUSH_IN)
        assertFalse(cmd1.differsFrom(cmd2))
    }

    // ========================================================================
    // Integration / end-to-end scenarios
    // ========================================================================

    @Test
    fun `full scene mapping sequence produces consistent state`() {
        val scene = DirectorScene(
            label = "Interview",
            cues = listOf(
                shotCue(ShotType.ESTABLISHING),
                transitionCue(TransitionType.PUSH_IN, transitionDurationMs = 2000),
                shotCue(ShotType.MEDIUM),
                focusCue(FocusTarget.FACE),
                exposureCue(ExposurePreset.BRIGHT),
                transitionCue(TransitionType.PUSH_IN, transitionDurationMs = 1500),
                shotCue(ShotType.CLOSE_UP)
            )
        )

        val results = mapper.mapScene(scene)
        assertEquals(7, results.size)

        // All shot/transition/focus/exposure cues should succeed
        assertTrue(results.all { it.success })

        // Final state should be CLOSE_UP on telephoto
        val finalCmd = mapper.getCurrentCommand()!!
        assertEquals("tele", finalCmd.lens)
    }

    @Test
    fun `mapper with default config uses default constructor`() {
        val defaultMapper = ShotMapper()
        val result = defaultMapper.mapCue(shotCue(ShotType.WIDE))
        assertTrue(result.success)
    }

    @Test
    fun `multiple consecutive PUSH_IN transitions compound zoom`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        val initialZoom = mapper.getCurrentCommand()!!.zoomLevel

        mapper.mapCue(transitionCue(TransitionType.PUSH_IN))
        val afterFirst = mapper.getCurrentCommand()!!.zoomLevel

        mapper.mapCue(transitionCue(TransitionType.PUSH_IN))
        val afterSecond = mapper.getCurrentCommand()!!.zoomLevel

        assertTrue("First push in should increase zoom", afterFirst > initialZoom)
        assertTrue("Second push in should increase zoom further", afterSecond > afterFirst)
        assertEquals(initialZoom * 1.5f * 1.5f, afterSecond, 0.1f)
    }

    @Test
    fun `multiple consecutive PULL_BACK transitions compound zoom`() {
        mapper.mapCue(shotCue(ShotType.MEDIUM))
        val initialZoom = mapper.getCurrentCommand()!!.zoomLevel

        mapper.mapCue(transitionCue(TransitionType.PULL_BACK))
        val afterFirst = mapper.getCurrentCommand()!!.zoomLevel

        mapper.mapCue(transitionCue(TransitionType.PULL_BACK))
        val afterSecond = mapper.getCurrentCommand()!!.zoomLevel

        assertTrue("First pull back should decrease zoom", afterFirst < initialZoom)
        assertTrue("Second pull back should decrease zoom further", afterSecond < afterFirst)
    }

    @Test
    fun `alternating RACK_FOCUS toggles between FACE and BACKGROUND`() {
        mapper.mapCue(focusCue(FocusTarget.FACE))

        mapper.mapCue(transitionCue(TransitionType.RACK_FOCUS))
        assertEquals(FocusTarget.BACKGROUND, mapper.getCurrentCommand()!!.focusMode)

        mapper.mapCue(transitionCue(TransitionType.RACK_FOCUS))
        assertEquals(FocusTarget.FACE, mapper.getCurrentCommand()!!.focusMode)

        mapper.mapCue(transitionCue(TransitionType.RACK_FOCUS))
        assertEquals(FocusTarget.BACKGROUND, mapper.getCurrentCommand()!!.focusMode)
    }

    @Test
    fun `CameraCapabilities default values are sensible`() {
        val defaults = ShotMapper.CameraCapabilities()
        assertTrue(defaults.hasWide)
        assertTrue(defaults.hasMain)
        assertFalse(defaults.hasTelephoto) // default is false
        assertEquals(8.0f, defaults.maxDigitalZoom, 0.001f)
        assertTrue(defaults.supportsSmoothZoom)
        assertTrue(defaults.supportsFaceDetection)
        assertTrue(defaults.supportsManualFocus)
    }

    @Test
    fun `MappingResult defaults have no warnings and no fallback`() {
        val cue = shotCue(ShotType.MEDIUM)
        val result = ShotMapper.MappingResult(
            success = true,
            command = null,
            cue = cue
        )
        assertTrue(result.warnings.isEmpty())
        assertFalse(result.fallbackUsed)
    }

    @Test
    fun `constant focal lengths have expected values`() {
        assertEquals(16f, ShotMapper.WIDE_FOCAL_LENGTH, 0.001f)
        assertEquals(24f, ShotMapper.MAIN_FOCAL_LENGTH, 0.001f)
        assertEquals(70f, ShotMapper.TELEPHOTO_FOCAL_LENGTH, 0.001f)
    }
}
