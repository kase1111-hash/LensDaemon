package com.lensdaemon.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensSelectionTest {

    // ==================== Classification ====================

    @Test
    fun `a phone's 24mm main camera is MAIN`() {
        // Pixel 7: 6.81mm lens on a 1/1.31" sensor, about 24.5mm equivalent
        assertEquals(LensType.MAIN, LensSelection.typeForEquivalentFocalLength(24.5f))
        assertEquals(LensType.MAIN, LensSelection.typeForEquivalentFocalLength(26f))
        assertEquals(LensType.MAIN, LensSelection.typeForEquivalentFocalLength(23f))
    }

    @Test
    fun `ultra-wide, telephoto and periscope lenses keep their own types`() {
        assertEquals(LensType.ULTRA_WIDE, LensSelection.typeForEquivalentFocalLength(13f))
        assertEquals(LensType.WIDE, LensSelection.typeForEquivalentFocalLength(18f))
        assertEquals(LensType.TELEPHOTO, LensSelection.typeForEquivalentFocalLength(52f))
        assertEquals(LensType.TELEPHOTO, LensSelection.typeForEquivalentFocalLength(77f))
        assertEquals(LensType.SUPER_TELEPHOTO, LensSelection.typeForEquivalentFocalLength(120f))
        assertEquals(LensType.SUPER_TELEPHOTO, LensSelection.typeForEquivalentFocalLength(230f))
    }

    @Test
    fun `a missing focal length is UNKNOWN`() {
        assertEquals(LensType.UNKNOWN, LensSelection.typeForEquivalentFocalLength(0f))
        assertEquals(LensType.UNKNOWN, LensSelection.typeForEquivalentFocalLength(Float.NaN))
    }

    // ==================== Picking a lens ====================

    private val pixel7Pro = listOf(LensType.ULTRA_WIDE, LensType.MAIN, LensType.SUPER_TELEPHOTO)

    @Test
    fun `wide takes the ultra-wide on a phone without a plain wide lens`() {
        assertEquals(LensType.ULTRA_WIDE, LensSelection.pick("wide", pixel7Pro))
    }

    @Test
    fun `tele takes the 5x on a phone without a 3x`() {
        assertEquals(LensType.SUPER_TELEPHOTO, LensSelection.pick("tele", pixel7Pro))
        assertEquals(LensType.SUPER_TELEPHOTO, LensSelection.pick(LensType.TELEPHOTO, pixel7Pro))
    }

    @Test
    fun `an exact type wins over the rest of its family`() {
        val galaxyUltra = listOf(LensType.ULTRA_WIDE, LensType.MAIN, LensType.TELEPHOTO, LensType.SUPER_TELEPHOTO)
        assertEquals(LensType.TELEPHOTO, LensSelection.pick("tele", galaxyUltra))
        assertEquals(LensType.SUPER_TELEPHOTO, LensSelection.pick("super_telephoto", galaxyUltra))
        assertEquals(LensType.SUPER_TELEPHOTO, LensSelection.pick("SUPER_TELEPHOTO", galaxyUltra))
        assertEquals(LensType.MAIN, LensSelection.pick("Main", galaxyUltra))
    }

    @Test
    fun `a lens the phone does not have is null`() {
        assertNull(LensSelection.pick("tele", listOf(LensType.ULTRA_WIDE, LensType.MAIN)))
        assertNull(LensSelection.pick("main", listOf(LensType.TELEPHOTO)))
        assertNull(LensSelection.pick(LensType.MAIN, emptyList()))
    }

    @Test
    fun `unknown names are rejected`() {
        assertNull(LensSelection.pick("fisheye", pixel7Pro))
        assertFalse(LensSelection.isKnownName("fisheye"))
        assertTrue(LensSelection.isKnownName("ultra-wide"))
        assertTrue(LensSelection.isKnownName("tele"))
    }

    @Test
    fun `lenses group under the wide, main and tele buttons`() {
        assertEquals("wide", LensSelection.familyOf(LensType.ULTRA_WIDE))
        assertEquals("wide", LensSelection.familyOf(LensType.WIDE))
        assertEquals("main", LensSelection.familyOf(LensType.MAIN))
        assertEquals("tele", LensSelection.familyOf(LensType.TELEPHOTO))
        assertEquals("tele", LensSelection.familyOf(LensType.SUPER_TELEPHOTO))
        assertEquals("other", LensSelection.familyOf(LensType.UNKNOWN))
    }
}
