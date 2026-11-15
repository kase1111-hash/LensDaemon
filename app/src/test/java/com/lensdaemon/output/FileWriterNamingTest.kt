package com.lensdaemon.output

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [safeDeviceName], the phone model as recording file names
 * carry it.
 */
class FileWriterNamingTest {

    @Test
    fun `punctuation in the model name becomes underscores`() {
        assertEquals("moto_g_7__power", safeDeviceName("moto g(7) power"))
    }

    @Test
    fun `spaces become underscores`() {
        assertEquals("Pixel_7", safeDeviceName("Pixel 7"))
    }

    @Test
    fun `an unknown model is called phone`() {
        assertEquals("phone", safeDeviceName(null))
    }

    @Test
    fun `letters, digits, dots, underscores and hyphens are kept`() {
        assertEquals("SM-G991B_v1.2", safeDeviceName("SM-G991B_v1.2"))
    }

    @Test
    fun `quotes, slashes and non-ASCII letters are replaced`() {
        assertEquals("a__b_c__", safeDeviceName("a\"/b\\cÉ\u0000"))
    }
}
