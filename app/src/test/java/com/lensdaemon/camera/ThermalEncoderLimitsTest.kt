package com.lensdaemon.camera

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [ThermalEncoderLimits]: the size and frame rate the encoder
 * runs at while the thermal governor's CRITICAL limits are on.
 */
class ThermalEncoderLimitsTest {

    @Test
    fun `4K steps down to 1080p`() {
        assertEquals(1920 to 1080, ThermalEncoderLimits.limitedSize(3840, 2160, reduce = true))
    }

    @Test
    fun `1080p steps down to 720p`() {
        assertEquals(1280 to 720, ThermalEncoderLimits.limitedSize(1920, 1080, reduce = true))
    }

    @Test
    fun `720p and smaller are left alone`() {
        assertEquals(1280 to 720, ThermalEncoderLimits.limitedSize(1280, 720, reduce = true))
        assertEquals(640 to 480, ThermalEncoderLimits.limitedSize(640, 480, reduce = true))
    }

    @Test
    fun `1440p takes one step, to 1080p`() {
        assertEquals(1920 to 1080, ThermalEncoderLimits.limitedSize(2560, 1440, reduce = true))
    }

    @Test
    fun `without the limit the size is unchanged`() {
        assertEquals(3840 to 2160, ThermalEncoderLimits.limitedSize(3840, 2160, reduce = false))
        assertEquals(1920 to 1080, ThermalEncoderLimits.limitedSize(1920, 1080, reduce = false))
    }

    @Test
    fun `portrait sizes step down on their short side`() {
        assertEquals(720 to 1280, ThermalEncoderLimits.limitedSize(1080, 1920, reduce = true))
    }

    @Test
    fun `the scaled side keeps the aspect ratio and is rounded down to an even number`() {
        // 4:3 at 1080 lines
        assertEquals(960 to 720, ThermalEncoderLimits.limitedSize(1440, 1080, reduce = true))
        // 1922 * 720 / 1080 = 1281.3, which would be odd
        assertEquals(1280 to 720, ThermalEncoderLimits.limitedSize(1922, 1080, reduce = true))
    }

    @Test
    fun `frame rate is capped at 24 while limited`() {
        assertEquals(24, ThermalEncoderLimits.limitedFrameRate(60, reduce = true))
        assertEquals(24, ThermalEncoderLimits.limitedFrameRate(30, reduce = true))
        assertEquals(15, ThermalEncoderLimits.limitedFrameRate(15, reduce = true))
        assertEquals(60, ThermalEncoderLimits.limitedFrameRate(60, reduce = false))
    }
}
