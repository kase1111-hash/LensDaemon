package com.lensdaemon.director

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for TakeManager: the 0-10 score, sample totals, samples outside takes or
 * while paused, cue timing, suggested takes and linking takes to files.
 */
class TakeManagerTest {

    private lateinit var takes: TakeManager

    @Before
    fun setUp() {
        takes = TakeManager(DirectorConfig.DEFAULT)
    }

    /** Samples of a steady, sharp take: focus locked, exposure constant, no shake. */
    private fun recordGoodSamples(count: Int = 10) {
        repeat(count) {
            takes.recordFocusSample(true)
            takes.recordExposureSample(0.5f)
            takes.recordMotionSample(0f)
        }
    }

    /** Samples of a poor take: focus hunting, exposure jumping, heavy shake. */
    private fun recordPoorSamples(count: Int = 10) {
        repeat(count) { i ->
            takes.recordFocusSample(false)
            takes.recordExposureSample(if (i % 2 == 0) 0f else 1f)
            takes.recordMotionSample(1f)
        }
    }

    @Test
    fun `steady take with focus locked scores close to 10`() {
        takes.startTake("scene-1", "Opening")
        recordGoodSamples()

        val take = takes.endTake()!!

        assertTrue("score ${take.qualityScore} is on the 0-10 scale", take.qualityScore in 0f..TakeManager.MAX_SCORE)
        assertEquals(10f, take.qualityScore, 0.01f)
        assertEquals(100, take.qualityFactors.focusLockPercent)
    }

    @Test
    fun `poor take scores low but within 0 to 10`() {
        takes.startTake("scene-1", "Opening")
        recordPoorSamples()

        val take = takes.endTake()!!

        assertTrue(take.qualityScore >= 0f)
        assertTrue("score ${take.qualityScore} is low", take.qualityScore < TakeManager.SUGGESTED_MIN_SCORE)
    }

    @Test
    fun `quality factors are worked out from the sample totals`() {
        takes.startTake("scene-1", "Opening")
        listOf(true, true, true, false).forEach { takes.recordFocusSample(it) }
        listOf(0.2f, 0.4f, 0.6f, 0.8f).forEach { takes.recordExposureSample(it) }
        listOf(0.1f, 0.3f).forEach { takes.recordMotionSample(it) }
        listOf(0.5f, 0.5f, 0.5f, 0.95f).forEach { takes.recordAudioSample(it) }

        val factors = takes.endTake()!!.qualityFactors

        assertEquals(75, factors.focusLockPercent)
        // Exposure variance 0.05, so 1 - 4 x 0.05
        assertEquals(0.8f, factors.exposureStability, 0.0001f)
        assertEquals(0.8f, factors.motionStability, 0.0001f)
        // A quarter of the audio samples out of range: more than the fifth allowed
        assertFalse(factors.audioLevelOk)
    }

    @Test
    fun `a long take is scored from all of its samples`() {
        takes.startTake("scene-1", "Opening")
        repeat(400_000) { i -> takes.recordFocusSample(i % 4 != 0) }

        assertEquals(75, takes.endTake()!!.qualityFactors.focusLockPercent)
    }

    @Test
    fun `samples are ignored while sampling is paused`() {
        var paused = false
        takes.samplingPaused = { paused }
        takes.startTake("scene-1", "Opening")
        recordGoodSamples()

        paused = true
        recordPoorSamples()
        paused = false

        assertEquals(10f, takes.endTake()!!.qualityScore, 0.01f)
    }

    @Test
    fun `samples recorded before startTake are ignored`() {
        // Out of focus before the take starts: these must not count
        repeat(20) { takes.recordFocusSample(false) }

        takes.startTake("scene-1", "Opening")
        recordGoodSamples()
        val take = takes.endTake()!!

        assertEquals(100, take.qualityFactors.focusLockPercent)
    }

    @Test
    fun `samples recorded between takes are ignored`() {
        takes.startTake("scene-1", "Opening")
        recordGoodSamples()
        takes.endTake()

        repeat(20) { takes.recordFocusSample(false) }

        takes.startTake("scene-1", "Opening")
        recordGoodSamples()
        val second = takes.endTake()!!

        assertEquals(100, second.qualityFactors.focusLockPercent)
    }

    @Test
    fun `best take of a scene scoring at least 5 is suggested, the others not`() {
        takes.startTake("scene-1", "Opening")
        recordPoorSamples()
        takes.endTake()

        takes.startTake("scene-1", "Opening")
        recordGoodSamples()
        takes.endTake()

        takes.startTake("scene-1", "Opening")
        recordPoorSamples()
        takes.endTake()

        val recorded = takes.recordedTakes.value
        assertEquals(listOf(false, true, false), recorded.map { it.suggested })
        assertEquals(2, takes.bestTakeSuggestion.value?.takeNumber)
    }

    @Test
    fun `no take is suggested when the best of a scene scores below 5`() {
        takes.startTake("scene-1", "Opening")
        recordPoorSamples()
        takes.endTake()

        assertFalse(takes.recordedTakes.value.single().suggested)
        assertTrue(takes.getAllBestTakes().isEmpty())
    }

    @Test
    fun `suggestions are kept per scene`() {
        takes.startTake("scene-1", "Opening")
        recordGoodSamples()
        takes.endTake()

        takes.startTake("scene-2", "Closing")
        recordGoodSamples()
        takes.endTake()

        assertEquals(listOf(true, true), takes.recordedTakes.value.map { it.suggested })
        assertEquals(setOf("scene-1", "scene-2"), takes.getAllBestTakes().keys)
    }

    @Test
    fun `endTake returns the take with its suggested flag`() {
        takes.startTake("scene-1", "Opening")
        recordGoodSamples()

        assertTrue(takes.endTake()!!.suggested)
    }

    @Test
    fun `linkTakeToFile returns false for an unknown take`() {
        takes.startTake("scene-1", "Opening")
        takes.endTake()

        assertFalse(takes.linkTakeToFile(9, "/sdcard/take9.mp4"))
        assertTrue(takes.linkTakeToFile(1, "/sdcard/take1.mp4"))
        assertEquals("/sdcard/take1.mp4", takes.recordedTakes.value.single().filePath)
    }

    @Test
    fun `recordCueExecution with no expected time adds no timing but counts the cue`() {
        takes.startTake("scene-1", "Opening")

        takes.recordCueExecution("cue-1", expectedTimeMs = null, success = true)
        takes.recordCueExecution("cue-2", expectedTimeMs = null, success = false)

        val active = takes.currentTake.value
        assertNotNull(active)
        assertTrue(active!!.cueTimings.isEmpty())
        val take = takes.endTake()!!
        assertEquals(1, take.cuesExecuted)
        assertEquals(1, take.cuesFailed)
        assertEquals(0f, take.qualityFactors.cueTimingAccuracy, 0.0001f)
    }

    @Test
    fun `take without cue timings is scored on the other factors`() {
        takes.startTake("scene-1", "Opening")
        recordGoodSamples()
        takes.recordCueExecution("cue-1", expectedTimeMs = null, success = true)

        assertEquals(10f, takes.endTake()!!.qualityScore, 0.01f)
    }

    @Test
    fun `cue timing on time keeps a full score`() {
        takes.startTake("scene-1", "Opening")
        recordGoodSamples()
        takes.recordCueExecution("cue-1", expectedTimeMs = TakeManager.monotonicNowMs(), success = true)

        val take = takes.endTake()!!

        assertTrue(take.qualityFactors.cueTimingAccuracy > 0.9f)
        assertTrue(take.qualityScore > 9.5f)
    }

    @Test
    fun `cue far behind its time lowers the score`() {
        takes.startTake("scene-1", "Opening")
        recordGoodSamples()
        takes.recordCueExecution("cue-1", expectedTimeMs = TakeManager.monotonicNowMs() - 5_000, success = true)

        val take = takes.endTake()!!

        assertEquals(0f, take.qualityFactors.cueTimingAccuracy, 0.0001f)
        assertEquals(8.5f, take.qualityScore, 0.05f)
    }

    @Test
    fun `cues outside a take are not counted`() {
        takes.recordCueExecution("cue-1", expectedTimeMs = null, success = false)
        takes.startTake("scene-1", "Opening")

        assertEquals(0, takes.endTake()!!.cuesFailed)
    }

    @Test
    fun `quality scoring off scores 0`() {
        takes.config = DirectorConfig(qualityScoring = false)
        takes.startTake("scene-1", "Opening")
        recordGoodSamples()

        assertEquals(0f, takes.endTake()!!.qualityScore, 0.0001f)
    }

    @Test
    fun `compareTakes calls a score of at least 8 excellent`() {
        takes.startTake("scene-1", "Opening")
        recordGoodSamples()
        takes.endTake()

        val comparison = takes.compareTakes("scene-1")

        assertEquals(1, comparison.bestTake?.takeNumber)
        assertTrue(comparison.recommendation, comparison.recommendation.contains("excellent"))
    }

    @Test
    fun `a take is unused until a cue runs in it`() {
        assertFalse(takes.isUnusedTakeOf("scene-1"))
        takes.startTake("scene-1", "Opening")
        assertTrue(takes.isUnusedTakeOf("scene-1"))
        assertFalse(takes.isUnusedTakeOf("scene-2"))

        takes.recordCueExecution("cue-1", expectedTimeMs = null, success = false)

        assertFalse(takes.isUnusedTakeOf("scene-1"))
    }

    @Test
    fun `starting a take ends the one in progress`() {
        takes.startTake("scene-1", "Opening")
        val second = takes.startTake("scene-2", "Closing")

        assertEquals(2, second.takeNumber)
        assertEquals(listOf(1), takes.recordedTakes.value.map { it.takeNumber })
        assertEquals(2, takes.getCurrentTakeNumber())
    }
}
