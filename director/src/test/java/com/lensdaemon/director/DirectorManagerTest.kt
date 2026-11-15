package com.lensdaemon.director

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * Unit tests for DirectorManager running real scripts on the JVM: settings in memory,
 * a fake camera with a settable temperature, short holds, and polling with timeouts.
 */
class DirectorManagerTest {

    /** Settings kept in memory. */
    private class MemoryConfig(@Volatile var saved: DirectorConfig) : DirectorConfigPersistence {
        override fun loadConfig(): DirectorConfig = saved
        override fun saveConfig(config: DirectorConfig) {
            saved = config
        }
    }

    /**
     * A camera that records what it is asked to do, at a temperature the test sets. Like the
     * phone, it opens another lens at 0 EV (AUTO), here at once. It reports the framing the
     * test sets, and none by default.
     */
    private class FakeCamera : DirectorManager.CameraController {
        val calls: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val exposures: MutableList<ExposurePreset> = Collections.synchronizedList(mutableListOf())

        /** The exposure each lens had when the camera left it for another, in order. */
        val exposuresLeft: MutableList<ExposurePreset> = Collections.synchronizedList(mutableListOf())

        @Volatile
        var temperature = 35

        @Volatile
        var lens: String? = null

        @Volatile
        var exposure = ExposurePreset.AUTO

        @Volatile
        var framing: ShotMapper.Framing? = null

        override fun switchLens(lens: String) {
            calls.add("lens $lens")
            if (lens != this.lens) {
                if (this.lens != null) exposuresLeft.add(exposure)
                this.lens = lens
                exposure = ExposurePreset.AUTO
            }
        }

        override fun setZoom(level: Float, animated: Boolean, durationMs: Long) {
            calls.add("zoom $level")
        }

        override fun setFocusMode(target: FocusTarget) {
            calls.add("focus $target")
        }

        override fun setExposurePreset(preset: ExposurePreset) {
            exposures.add(preset)
            exposure = preset
        }

        override fun getCurrentTemperature(): Int = temperature

        override fun getFraming(): ShotMapper.Framing? = framing
    }

    private lateinit var persistence: MemoryConfig
    private lateinit var camera: FakeCamera
    private lateinit var director: DirectorManager
    private val listeners = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events: MutableList<DirectorManager.DirectorEvent> = Collections.synchronizedList(mutableListOf())

    @Before
    fun setUp() {
        persistence = MemoryConfig(
            DirectorConfig(enabled = true, defaultHoldDurationMs = 20, defaultTransitionDurationMs = 20)
        )
        camera = FakeCamera()
        director = DirectorManager(persistence, thermalMonitorIntervalMs = 20)
        director.setCameraController(camera)
        listeners.launch(start = CoroutineStart.UNDISPATCHED) {
            director.events.collect { events.add(it) }
        }
    }

    @After
    fun tearDown() {
        director.destroy()
        listeners.cancel()
    }

    private fun await(what: String, timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what")
            Thread.sleep(5)
        }
    }

    private fun cuesRun(): List<DirectorManager.DirectorEvent.CueExecuted> =
        synchronized(events) { events.filterIsInstance<DirectorManager.DirectorEvent.CueExecuted>() }

    private fun countEvents(type: Class<out DirectorManager.DirectorEvent>): Int =
        synchronized(events) { events.count { type.isInstance(it) } }

    private fun cueIndex(): Int? = director.currentSession.value?.currentCueIndex

    private fun takeNumbers(): List<Int> = director.getTakeManager().recordedTakes.value.map { it.takeNumber }

    // ========================================================================
    // Thermal hold
    // ========================================================================

    @Test
    fun `overheating during a run holds the director until the phone cools`() {
        director.loadScript("[SCENE: A]\n[SHOT: WIDE]\n[HOLD: 30]\n[SHOT: MEDIUM]").getOrThrow()
        assertTrue(director.startExecution())

        camera.temperature = 60
        await("the thermal hold") { director.state.value == DirectorState.THERMAL_HOLD }
        Thread.sleep(100) // several monitor checks while still hot

        assertEquals(DirectorState.THERMAL_HOLD, director.state.value)
        assertTrue(director.getStatus().thermalProtectionActive)
        assertFalse("the take ended with the hold", director.getTakeManager().isRecording())
        assertFalse(director.startExecution())
        assertFalse(director.resumeExecution())
        assertEquals(60, director.lastTemperature())
        assertEquals(DirectorState.THERMAL_HOLD, director.state.value)
        await("the warning") { countEvents(DirectorManager.DirectorEvent.ThermalWarning::class.java) == 1 }

        // Below the threshold, but not yet 5 °C below it
        camera.temperature = 52
        Thread.sleep(100)
        assertEquals(DirectorState.THERMAL_HOLD, director.state.value)

        camera.temperature = 45
        await("the hold to clear") { director.state.value == DirectorState.READY }
        Thread.sleep(60)
        assertEquals("nothing resumes by itself", DirectorState.READY, director.state.value)
        assertEquals(1, countEvents(DirectorManager.DirectorEvent.ThermalWarning::class.java))
    }

    @Test
    fun `start reads the temperature now and refuses while too hot`() {
        director.loadScript("[SCENE: A]\n[SHOT: WIDE]").getOrThrow()
        camera.temperature = 57

        assertFalse(director.startExecution())

        assertEquals(DirectorState.THERMAL_HOLD, director.state.value)
        assertEquals(57, director.lastTemperature())
        assertEquals(50, director.thermalResumeTemperature())
    }

    @Test
    fun `start after the phone cooled clears the hold and runs`() {
        director.loadScript("[SCENE: A]\n[SHOT: WIDE]\n[HOLD: 30]").getOrThrow()
        camera.temperature = 57
        assertFalse(director.startExecution())

        camera.temperature = 40
        assertTrue(director.startExecution())
        assertEquals(DirectorState.RUNNING, director.state.value)
        director.stopExecution()
    }

    // ========================================================================
    // Run control
    // ========================================================================

    @Test
    fun `stop rewinds to the first scene`() {
        director.loadScript("[SCENE: A]\n[SHOT: WIDE]\n[SCENE: B]\n[SHOT: MEDIUM]\n[HOLD: 30]").getOrThrow()
        assertTrue(director.goToScene(1))
        assertTrue(director.startExecution())
        await("the hold in scene B") {
            director.currentSession.value?.let { it.currentSceneIndex == 1 && it.currentCueIndex == 1 } == true
        }

        director.stopExecution()

        val session = director.currentSession.value!!
        assertEquals(0, session.currentSceneIndex)
        assertEquals(0, session.currentCueIndex)
        assertEquals(DirectorState.READY, director.state.value)
        assertFalse(director.getTakeManager().isRecording())
    }

    @Test
    fun `a finished script rewinds and runs again from the start`() {
        director.loadScript("[SCENE: A]\n[SHOT: WIDE]\n[SCENE: B]\n[SHOT: CLOSE-UP]").getOrThrow()
        assertTrue(director.startExecution())
        await("the end of the script") { director.state.value == DirectorState.READY }
        assertEquals(0, director.currentSession.value!!.currentSceneIndex)

        assertTrue(director.startExecution())

        await("the second run") { cuesRun().count { it.cue.rawText == "[SHOT: WIDE]" } == 2 }
        await("the end of the second run") { director.state.value == DirectorState.READY }
    }

    @Test
    fun `nextCue while running runs the chosen cue at once`() {
        director.loadScript("[SCENE: A]\n[SHOT: WIDE]\n[HOLD: 30]\n[SHOT: CLOSE-UP]\n[HOLD: 30]").getOrThrow()
        assertTrue(director.startExecution())
        await("the first hold") { cueIndex() == 1 }

        val next = director.nextCue()

        assertEquals("[SHOT: CLOSE-UP]", next?.rawText)
        await("the close-up") { cuesRun().any { it.cue.rawText == "[SHOT: CLOSE-UP]" } }
        await("the second hold") { cueIndex() == 3 }
        assertEquals(1, cuesRun().count { it.cue.rawText == "[SHOT: CLOSE-UP]" })
        assertEquals(DirectorState.RUNNING, director.state.value)
        director.stopExecution()
    }

    @Test
    fun `nextCue when ready only moves the position`() {
        director.loadScript("[SCENE: A]\n[SHOT: WIDE]\n[SHOT: CLOSE-UP]").getOrThrow()

        assertEquals("[SHOT: CLOSE-UP]", director.nextCue()?.rawText)
        assertEquals(1, cueIndex())
        assertEquals(null, director.nextCue())
        assertEquals(DirectorState.READY, director.state.value)
        assertTrue(cuesRun().isEmpty())
    }

    @Test
    fun `goToScene while running starts that scene at once with a new take`() {
        director.loadScript("[SCENE: A]\n[SHOT: WIDE]\n[HOLD: 30]\n[SCENE: B]\n[SHOT: CLOSE-UP]\n[HOLD: 30]").getOrThrow()
        assertTrue(director.startExecution())
        await("the hold in scene A") { cueIndex() == 1 }

        assertTrue(director.goToScene(1))

        await("scene B's shot") { cuesRun().any { it.cue.rawText == "[SHOT: CLOSE-UP]" } }
        assertEquals(1, director.currentSession.value!!.currentSceneIndex)
        assertEquals(listOf(1), takeNumbers())
        assertEquals(2, director.getTakeManager().getCurrentTakeNumber())
        assertFalse(director.goToScene(5))
        director.stopExecution()
    }

    @Test
    fun `a scene without cues does not end the run`() {
        val result = director.loadScript(
            "[SCENE: A]\n[SHOT: WIDE]\n[HOLD: 0.02]\n[SCENE: B]\nThe host talks.\n[SCENE: C]\n[SHOT: CLOSE-UP]"
        ).getOrThrow()
        assertTrue(result.errors.contains("Scene 'B' has no cues"))
        assertTrue(director.startExecution())

        await("scene C's shot") { cuesRun().any { it.cue.rawText == "[SHOT: CLOSE-UP]" } }
        await("the end") { director.state.value == DirectorState.READY }

        // One take for A and one for C: B, without cues, is passed over
        val scenes = director.currentSession.value!!.script.scenes
        assertEquals(listOf(scenes[0].id, scenes[2].id), director.getTakeManager().recordedTakes.value.map { it.sceneId })
    }

    @Test
    fun `quality samples taken while paused do not count`() {
        director.loadScript("[SCENE: A]\n[SHOT: WIDE]\n[HOLD: 30]").getOrThrow()
        assertTrue(director.startExecution())
        await("the hold") { cueIndex() == 1 }
        repeat(4) { director.recordFocusSample(true) }

        director.pauseExecution()
        repeat(12) { director.recordFocusSample(false) }
        assertTrue(director.resumeExecution())
        repeat(4) { director.recordFocusSample(true) }
        director.stopExecution()

        assertEquals(100, director.getTakeManager().recordedTakes.value.single().qualityFactors.focusLockPercent)
    }

    @Test
    fun `pause and resume continue the same take`() {
        director.loadScript("[SCENE: A]\n[SHOT: WIDE]\n[HOLD: 30]\n[SHOT: MEDIUM]").getOrThrow()
        assertTrue(director.startExecution())
        await("the hold") { cueIndex() == 1 }

        director.pauseExecution()
        assertEquals(DirectorState.PAUSED, director.state.value)
        assertEquals(1, cueIndex())
        assertTrue(director.resumeExecution())

        assertEquals(DirectorState.RUNNING, director.state.value)
        assertEquals(1, director.getTakeManager().getCurrentTakeNumber())
        assertTrue(takeNumbers().isEmpty())
        director.stopExecution()
        assertEquals(listOf(1), takeNumbers())
    }

    // ========================================================================
    // Cues
    // ========================================================================

    @Test
    fun `BEAT and HOLD succeed without a camera command`() {
        director.loadScript("[SCENE: A]\n[SHOT: WIDE]\n[BEAT]\n[HOLD: 0.02]\n[SHOT: MEDIUM]").getOrThrow()
        assertTrue(director.startExecution())

        await("all four cues") { cuesRun().size == 4 }
        await("the end") { director.state.value == DirectorState.READY }

        assertTrue(cuesRun().all { it.success })
        val take = director.getTakeManager().recordedTakes.value.single()
        assertEquals(4, take.cuesExecuted)
        assertEquals(0, take.cuesFailed)
    }

    @Test
    fun `TAKE starts take 2 in the same scene`() {
        director.loadScript("[SCENE: A]\n[SHOT: WIDE]\n[TAKE: 2]\n[SHOT: MEDIUM]").getOrThrow()
        assertTrue(director.startExecution())

        await("the end") { director.state.value == DirectorState.READY && takeNumbers().size == 2 }

        val takes = director.getTakeManager().recordedTakes.value
        assertEquals(listOf(1, 2), takes.map { it.takeNumber })
        assertEquals(takes[0].sceneId, takes[1].sceneId)
        assertEquals(1, takes[0].cuesExecuted)
        assertEquals(2, takes[1].cuesExecuted)
        await("the take events") {
            countEvents(DirectorManager.DirectorEvent.TakeStarted::class.java) == 2 &&
                countEvents(DirectorManager.DirectorEvent.TakeEnded::class.java) == 2
        }
    }

    @Test
    fun `TAKE at the start of a scene keeps the take the scene just started`() {
        director.loadScript(
            "[SCENE: A]\n[TAKE: 1]\n[SHOT: WIDE]\n[HOLD: 0.02]\n[TAKE: 2]\n[SHOT: MEDIUM]\n[SCENE: B]\n[TAKE: 3]\n[SHOT: CLOSE-UP]"
        ).getOrThrow()
        assertTrue(director.startExecution())

        await("the end") { director.state.value == DirectorState.READY && takeNumbers().size == 3 }

        val takes = director.getTakeManager().recordedTakes.value
        assertEquals(listOf(1, 2, 3), takes.map { it.takeNumber })
        assertEquals(listOf(3, 2, 2), takes.map { it.cuesExecuted })
        await("the take events") {
            countEvents(DirectorManager.DirectorEvent.TakeStarted::class.java) == 3 &&
                countEvents(DirectorManager.DirectorEvent.TakeEnded::class.java) == 3
        }
    }

    @Test
    fun `a preset other than AUTO goes with every command, AUTO only when a cue goes back to it`() {
        director.loadScript(
            "[SCENE: A]\n[SHOT: WIDE]\n[SHOT: MEDIUM]\n[EXPOSURE: BACKLIT]\n[SHOT: CLOSE-UP]\n[CUT TO: WIDE]\n" +
                "[EXPOSURE: AUTO]\n[SHOT: MEDIUM]"
        ).getOrThrow()
        assertTrue(director.startExecution())

        await("all seven cues") { cuesRun().size == 7 }
        await("the end") { director.state.value == DirectorState.READY }

        // The shots before [EXPOSURE] and after the return to AUTO leave the exposure alone
        val backlit = ExposurePreset.BACKLIT
        assertEquals(listOf(backlit, backlit, backlit, ExposurePreset.AUTO), camera.exposures.toList())
    }

    @Test
    fun `an exposure preset stays on through the lens changes of later shots`() {
        director.loadScript(
            "[SCENE: Window]\n[SHOT: MEDIUM]\n[EXPOSURE: BACKLIT]\n[SHOT: WIDE]\n[HOLD: 0.02]\n[SHOT: MEDIUM]\n[HOLD: 0.02]"
        ).getOrThrow()
        assertTrue(director.startExecution())

        await("the end") { director.state.value == DirectorState.READY }

        // The wide shot and the medium shot after it each opened a lens at 0 EV, and got BACKLIT back
        assertEquals(listOf(ExposurePreset.BACKLIT, ExposurePreset.BACKLIT), camera.exposuresLeft.toList())
        assertEquals("main", camera.lens)
        assertEquals(ExposurePreset.BACKLIT, camera.exposure)
    }

    @Test
    fun `cues run by hand`() {
        assertTrue(director.executeCueText("[BEAT]"))
        assertFalse(director.executeCueText("nothing to do here"))
        assertTrue(director.executeCueText("[SHOT: WIDE]"))

        assertTrue(camera.calls.contains("lens wide"))
        assertTrue(camera.exposures.isEmpty())
    }

    @Test
    fun `an EXPOSURE AUTO cue in a new script undoes the last script's preset`() {
        director.loadScript("[SCENE: Night]\n[EXPOSURE: DARK]\n[SHOT: MEDIUM]").getOrThrow()
        assertTrue(director.startExecution())
        await("the end of the first script") { director.state.value == DirectorState.READY && cuesRun().size == 2 }
        assertEquals(ExposurePreset.DARK, camera.exposure)

        // On the same lens, so only the cue can bring the exposure back
        director.loadScript("[SCENE: Day]\n[EXPOSURE: AUTO]\n[SHOT: MEDIUM]").getOrThrow()
        assertTrue(director.startExecution())
        await("the end of the second script") { director.state.value == DirectorState.READY && cuesRun().size == 4 }

        assertEquals("main", camera.lens)
        assertEquals(ExposurePreset.AUTO, camera.exposure)
        assertEquals(listOf(ExposurePreset.DARK, ExposurePreset.DARK, ExposurePreset.AUTO), camera.exposures.toList())
    }

    @Test
    fun `an EXPOSURE AUTO cue run by hand undoes an exposure set elsewhere`() {
        // Say the EV was lowered on the dashboard, with the camera on its main lens
        camera.lens = "main"
        camera.exposure = ExposurePreset.DARK

        assertTrue(director.executeCueText("[EXPOSURE: AUTO]"))

        assertEquals(listOf(ExposurePreset.AUTO), camera.exposures.toList())
        assertEquals(ExposurePreset.AUTO, camera.exposure)
    }

    @Test
    fun `a new script leaves the exposure alone until its own EXPOSURE cue`() {
        director.loadScript("[SCENE: Night]\n[EXPOSURE: DARK]\n[SHOT: MEDIUM]").getOrThrow()
        assertTrue(director.startExecution())
        await("the end of the first script") { director.state.value == DirectorState.READY && cuesRun().size == 2 }
        // Then the EV is raised on the dashboard
        camera.exposure = ExposurePreset.BRIGHT
        camera.exposures.clear()

        director.loadScript("[SCENE: Day]\n[SHOT: MEDIUM]\n[TRANSITION: PUSH IN]").getOrThrow()
        assertTrue(director.startExecution())
        await("the end of the second script") { director.state.value == DirectorState.READY && cuesRun().size == 4 }

        assertTrue(camera.exposures.isEmpty())
        assertEquals(ExposurePreset.BRIGHT, camera.exposure)
    }

    @Test
    fun `a cue run by hand after the script keeps the framing and exposure it left`() {
        // Without a telephoto, the close-up is the main lens at 2.75x
        director.loadScript("[SCENE: A]\n[EXPOSURE: DARK]\n[SHOT: CLOSE-UP]").getOrThrow()
        assertTrue(director.startExecution())
        await("the end") { director.state.value == DirectorState.READY && cuesRun().size == 2 }
        camera.calls.clear()
        camera.exposures.clear()

        assertTrue(director.executeCueText("[FOCUS: FACE]"))

        assertEquals(listOf("lens main", "zoom 2.75", "focus FACE"), camera.calls.toList())
        assertEquals(listOf(ExposurePreset.DARK), camera.exposures.toList())
    }

    @Test
    fun `a cue run by hand after Stop keeps the framing`() {
        director.loadScript("[SCENE: A]\n[SHOT: CLOSE-UP]\n[HOLD: 30]").getOrThrow()
        assertTrue(director.startExecution())
        await("the hold") { cueIndex() == 1 }
        director.stopExecution()
        camera.calls.clear()

        assertTrue(director.executeCueText("[TRANSITION: PUSH IN]"))

        // From the close-up's 2.75x on the main lens
        assertEquals(listOf("lens main", "zoom 4.125", "focus FACE"), camera.calls.toList())
    }

    @Test
    fun `cues that keep the framing start from the camera's lens and zoom`() {
        // Put on the wide lens at 3x on the dashboard, before any shot
        camera.lens = "wide"
        camera.framing = ShotMapper.Framing("wide", 3f)

        assertTrue(director.executeCueText("[TRANSITION: PUSH IN]"))
        assertEquals(listOf("lens wide", "zoom 4.5", "focus AUTO"), camera.calls.toList())

        // Then on the tele lens at 2x: an exposure cue stays there too
        camera.calls.clear()
        camera.lens = "tele"
        camera.framing = ShotMapper.Framing("tele", 2f)
        assertTrue(director.executeCueText("[EXPOSURE: DARK]"))
        assertEquals(listOf("lens tele", "zoom 2.0", "focus AUTO"), camera.calls.toList())
        assertEquals(listOf(ExposurePreset.DARK), camera.exposures.toList())
    }

    // ========================================================================
    // Scripts and settings
    // ========================================================================

    @Test
    fun `loadScript while running fails and keeps the old script`() {
        val original = "[SCENE: A]\n[SHOT: WIDE]\n[HOLD: 30]"
        director.loadScript(original).getOrThrow()
        assertTrue(director.startExecution())

        val result = director.loadScript("[SCENE: B]\n[SHOT: MEDIUM]")

        assertTrue(result.isFailure)
        assertEquals("Stop the director before loading another script", result.exceptionOrNull()?.message)
        assertEquals(original, director.currentSession.value?.script?.rawScript)
        assertEquals(DirectorState.RUNNING, director.state.value)
        director.stopExecution()
    }

    @Test
    fun `a script without cues is refused and the previous one stays`() {
        val original = "[SCENE: A]\n[SHOT: WIDE]"
        director.loadScript(original).getOrThrow()

        val result = director.loadScript("[TRANSITON: PUSH IN]")

        val message = result.exceptionOrNull()?.message ?: ""
        assertTrue(message, message.startsWith("Script contains no valid scenes or cues"))
        assertTrue(message, message.contains("Line 1: not a cue: [TRANSITON: PUSH IN]"))
        assertEquals(original, director.currentSession.value?.script?.rawScript)
        assertEquals(DirectorState.READY, director.state.value)
    }

    @Test
    fun `configuration changes reach the parser and are saved`() {
        director.updateConfig(director.getConfig().copy(defaultHoldDurationMs = 1234))

        director.loadScript("[SCENE: A]\n[BEAT]").getOrThrow()

        assertEquals(1234L, director.currentSession.value!!.script.scenes[0].cues[0].holdDurationMs)
        assertEquals(1234L, persistence.saved.defaultHoldDurationMs)
    }

    @Test
    fun `destroy keeps the director enabled in the saved settings`() {
        director.destroy()

        assertTrue(persistence.saved.enabled)
    }

    @Test
    fun `session time counts from the script load`() {
        assertEquals(0L, director.getSessionDuration())

        director.loadScript("[SCENE: A]\n[SHOT: WIDE]").getOrThrow()

        assertTrue(director.getSessionDuration() in 0L..1L)
    }

    @Test
    fun `clearing the script keeps the takes`() {
        director.loadScript("[SCENE: A]\n[SHOT: WIDE]").getOrThrow()
        assertTrue(director.startExecution())
        await("the end") { director.state.value == DirectorState.READY && takeNumbers().size == 1 }

        director.clearScript()

        assertEquals(DirectorState.IDLE, director.state.value)
        assertEquals(null, director.currentSession.value)
        assertEquals(listOf(1), takeNumbers())
    }
}
