package com.lensdaemon.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [FrameRatePolicy.chooseAeTargetFpsRange].
 */
class FrameRatePolicyTest {

    /** A typical rear camera: variable and fixed ranges up to 30, plus 60. */
    private val typical = listOf(15..15, 15..30, 20..20, 24..24, 7..30, 30..30, 60..60)

    @Test
    fun `a fixed range at the target wins over variable ones`() {
        assertEquals(30..30, FrameRatePolicy.chooseAeTargetFpsRange(typical, 30))
        assertEquals(24..24, FrameRatePolicy.chooseAeTargetFpsRange(typical, 24))
        assertEquals(60..60, FrameRatePolicy.chooseAeTargetFpsRange(typical, 60))
    }

    @Test
    fun `without a fixed range the one with the highest floor is used`() {
        val ranges = listOf(7..30, 15..30, 8..15)
        assertEquals(15..30, FrameRatePolicy.chooseAeTargetFpsRange(ranges, 30))
    }

    @Test
    fun `a target the camera cannot hit exactly takes the nearest faster range`() {
        val ranges = listOf(15..30, 30..30, 60..60, 30..60)
        assertEquals("25 fps should run on the 30 fps range", 30..30, FrameRatePolicy.chooseAeTargetFpsRange(ranges, 25))
    }

    @Test
    fun `a target above the camera's maximum takes its fastest range`() {
        val ranges = listOf(15..30, 30..30, 7..30)
        assertEquals("60 fps on a 30 fps camera should lock at 30", 30..30, FrameRatePolicy.chooseAeTargetFpsRange(ranges, 60))
    }

    @Test
    fun `no ranges means no request`() {
        assertNull(FrameRatePolicy.chooseAeTargetFpsRange(emptyList(), 30))
    }
}
