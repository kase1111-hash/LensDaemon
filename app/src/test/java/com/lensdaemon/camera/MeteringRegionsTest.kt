package com.lensdaemon.camera

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MeteringRegions]: where a tap on the 16:9 picture lands on
 * the sensor, and how a crop (zoom on Android 10) moves it.
 */
class MeteringRegionsTest {

    /** A 4:3 sensor: a 16:9 picture shows its middle 2250 rows. */
    private val width = 4000
    private val height = 3000
    private val bandTop = (3000 - 2250) / 2
    private val bandBottom = 3000 - (3000 - 2250) / 2

    private fun assertInside(rect: IntArray, left: Int, top: Int, right: Int, bottom: Int) {
        val text = rect.joinToString()
        assertTrue("left of [$text]", rect[0] >= left)
        assertTrue("top of [$text]", rect[1] >= top)
        assertTrue("right of [$text]", rect[2] <= right)
        assertTrue("bottom of [$text]", rect[3] <= bottom)
        assertTrue("empty [$text]", rect[2] > rect[0] && rect[3] > rect[1])
    }

    @Test
    fun `a centre tap on a 4 by 3 sensor lands in the 16 by 9 band`() {
        val rect = MeteringRegions.tapRect(0.5f, 0.5f, width, height)
        assertInside(rect, 0, bandTop, width, bandBottom)
        assertEquals(width / 2, (rect[0] + rect[2]) / 2)
        assertEquals(height / 2, (rect[1] + rect[3]) / 2)
    }

    @Test
    fun `y maps into the band and x across the full width`() {
        val top = MeteringRegions.tapRect(0.5f, 0f, width, height)
        assertEquals(bandTop, top[1])
        val bottomRight = MeteringRegions.tapRect(1f, 1f, width, height)
        assertEquals(width, bottomRight[2])
        assertEquals(bandBottom, bottomRight[3])
    }

    @Test
    fun `a corner tap is clamped inside the array`() {
        for ((x, y) in listOf(0f to 0f, 1f to 0f, 0f to 1f, 1f to 1f, -3f to 7f)) {
            assertInside(MeteringRegions.tapRect(x, y, width, height), 0, 0, width, height)
        }
        assertArrayEquals(intArrayOf(0, bandTop, 400, bandTop + 225), MeteringRegions.tapRect(0f, 0f, width, height))
    }

    @Test
    fun `on a 16 by 9 array the band is the full height`() {
        assertArrayEquals(intArrayOf(0, 0, 3840, 2160), MeteringRegions.visibleBand(3840, 2160))
        val rect = MeteringRegions.tapRect(0.5f, 1f, 3840, 2160)
        assertEquals(2160, rect[3])
    }

    @Test
    fun `an array wider than the output shows a centred band of its width`() {
        assertArrayEquals(intArrayOf(500, 0, 3700, 1800), MeteringRegions.visibleBand(4200, 1800))
        val left = MeteringRegions.tapRect(0f, 0.5f, 4200, 1800)
        assertEquals(500, left[0])
    }

    @Test
    fun `sizeFraction 0_1 gives a 400 wide region on 4000`() {
        val rect = MeteringRegions.tapRect(0.5f, 0.5f, width, height, sizeFraction = 0.1f)
        assertEquals(400, rect[2] - rect[0])
        assertEquals(225, rect[3] - rect[1])
        val wider = MeteringRegions.tapRect(0.5f, 0.5f, width, height, sizeFraction = 0.15f)
        assertEquals(600, wider[2] - wider[0])
    }

    @Test
    fun `a tap that is not a number lands in the centre`() {
        assertArrayEquals(
            MeteringRegions.tapRect(0.5f, 0.5f, width, height),
            MeteringRegions.tapRect(Float.NaN, Float.NaN, width, height)
        )
    }

    @Test
    fun `the crop for a zoom is centred and zoom times smaller`() {
        assertArrayEquals(intArrayOf(1000, 750, 3000, 2250), MeteringRegions.centredCrop(width, height, 2f))
        assertArrayEquals(intArrayOf(0, 0, width, height), MeteringRegions.centredCrop(width, height, 1f))
        assertArrayEquals(intArrayOf(0, 0, width, height), MeteringRegions.centredCrop(width, height, 0.5f))
        assertArrayEquals(intArrayOf(0, 0, width, height), MeteringRegions.centredCrop(width, height, Float.NaN))
    }

    @Test
    fun `a region moves into the crop in proportion`() {
        val crop = MeteringRegions.centredCrop(width, height, 2f)
        // The whole array maps onto the whole crop, the centre stays the centre
        assertArrayEquals(crop, MeteringRegions.mapIntoCrop(intArrayOf(0, 0, width, height), width, height, crop))
        val centre = MeteringRegions.tapRect(0.5f, 0.5f, width, height)
        val mapped = MeteringRegions.mapIntoCrop(centre, width, height, crop)
        assertEquals(width / 2, (mapped[0] + mapped[2]) / 2)
        assertEquals(height / 2, (mapped[1] + mapped[3]) / 2)
        assertEquals((centre[2] - centre[0]) / 2, mapped[2] - mapped[0])
        assertInside(mapped, crop[0], crop[1], crop[2], crop[3])
    }
}
