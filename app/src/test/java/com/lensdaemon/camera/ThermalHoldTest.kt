package com.lensdaemon.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ThermalHold]: unattended starts wait for a thermal EMERGENCY
 * to end, whether or not anything ran when it began.
 */
class ThermalHoldTest {

    private val rtspOnly = ThermalHold.AutoStart(rtsp = true, record = false)
    private val recordOnly = ThermalHold.AutoStart(rtsp = false, record = true)

    @Test
    fun `without an emergency a start goes ahead and nothing waits`() {
        val hold = ThermalHold()
        assertFalse(hold.defer(rtspOnly))
        assertNull(hold.waiting)
        assertNull(hold.end())
    }

    @Test
    fun `during an emergency a start waits and comes back at the end`() {
        val hold = ThermalHold()
        hold.begin()
        assertTrue(hold.emergency)
        assertTrue(hold.defer(rtspOnly))
        assertEquals(rtspOnly, hold.waiting)

        assertEquals(rtspOnly, hold.end())
        assertFalse(hold.emergency)
        assertNull(hold.waiting)
        // Over: the next start goes ahead
        assertFalse(hold.defer(recordOnly))
    }

    @Test
    fun `starts that wait together are merged`() {
        val hold = ThermalHold()
        hold.begin()
        hold.defer(rtspOnly)
        hold.defer(recordOnly)
        assertEquals(ThermalHold.AutoStart(rtsp = true, record = true), hold.end())
    }

    @Test
    fun `a start of nothing is held back but leaves nothing waiting`() {
        val hold = ThermalHold()
        hold.begin()
        assertTrue(hold.defer(ThermalHold.AutoStart(rtsp = false, record = false)))
        assertNull(hold.waiting)
    }

    @Test
    fun `a user's start or stop drops the waiting start but not the emergency`() {
        val hold = ThermalHold()
        hold.begin()
        hold.defer(rtspOnly)
        assertTrue(hold.dropWaiting())
        assertNull(hold.waiting)
        assertFalse(hold.dropWaiting())
        // Still too hot: a later unattended start waits again
        assertTrue(hold.emergency)
        assertTrue(hold.defer(recordOnly))
        assertEquals(recordOnly, hold.end())
    }

    @Test
    fun `a repeated pause keeps what waits`() {
        val hold = ThermalHold()
        hold.begin()
        hold.defer(rtspOnly)
        hold.begin()
        assertEquals(rtspOnly, hold.waiting)
    }
}
