package com.lensdaemon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamControlsTest {

    @Test
    fun idlePhoneOffersStartOnly() {
        val controls = StreamControls.of(streaming = false, rtspOn = false, thermalPaused = false, otherOutputs = false)
        assertEquals(StreamControls(false, startVisible = true, stopVisible = false, stopEndsAll = false, infoVisible = false), controls)
    }

    @Test
    fun rtspStreamOffersStopStream() {
        val controls = StreamControls.of(streaming = true, rtspOn = true, thermalPaused = false, otherOutputs = false)
        assertFalse(controls.startVisible)
        assertTrue(controls.stopVisible)
        assertFalse(controls.stopEndsAll)
        assertTrue(controls.infoVisible)
    }

    @Test
    fun otherOutputsMakeItStopAll() {
        val controls = StreamControls.of(streaming = true, rtspOn = false, thermalPaused = false, otherOutputs = true)
        assertTrue("Start RTSP stays offered while only MPEG-TS or a recording runs", controls.startVisible)
        assertTrue(controls.stopEndsAll)
    }

    @Test
    fun thermalPauseHidesStartAndKeepsStopAll() {
        val controls = StreamControls.of(streaming = false, rtspOn = false, thermalPaused = true, otherOutputs = false)
        assertTrue(controls.thermalPaused)
        assertFalse("a start would cancel the pause", controls.startVisible)
        assertTrue("Stop cancels the automatic restart", controls.stopVisible)
        assertTrue(controls.stopEndsAll)
        assertTrue("the pause message is shown in the stream info", controls.infoVisible)
    }

    @Test
    fun pauseFlagIsIgnoredWhileStreaming() {
        // The pause ends as soon as something streams again
        val controls = StreamControls.of(streaming = true, rtspOn = true, thermalPaused = true, otherOutputs = false)
        assertFalse(controls.thermalPaused)
        assertFalse(controls.stopEndsAll)
    }
}
