package com.lensdaemon.encoder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the AAC bitstream helpers in [Aac].
 */
class AacTest {

    @Test
    fun `AudioSpecificConfig matches what encoders emit`() {
        // AAC-LC, 48 kHz (index 3), stereo: the csd-0 MediaCodec produces
        assertArrayEquals(byteArrayOf(0x11, 0x90.toByte()), Aac.audioSpecificConfig(48_000, 2))
        // AAC-LC, 44.1 kHz (index 4), mono
        assertArrayEquals(byteArrayOf(0x12, 0x08), Aac.audioSpecificConfig(44_100, 1))
    }

    @Test
    fun `ADTS header fields decode back to the frame they describe`() {
        val payload = 371
        val header = Aac.adtsHeader(payload, 48_000, 2)
        assertEquals(Aac.ADTS_HEADER_LENGTH, header.size)

        fun bits(offset: Int, count: Int): Int {
            var value = 0
            for (i in offset until offset + count) {
                val bit = (header[i / 8].toInt() shr (7 - i % 8)) and 1
                value = (value shl 1) or bit
            }
            return value
        }

        assertEquals("syncword", 0xFFF, bits(0, 12))
        assertEquals("MPEG-4", 0, bits(12, 1))
        assertEquals("layer", 0, bits(13, 2))
        assertEquals("no CRC", 1, bits(15, 1))
        assertEquals("profile AAC-LC", 1, bits(16, 2))
        assertEquals("48 kHz", 3, bits(18, 4))
        assertEquals("stereo", 2, bits(23, 3))
        assertEquals("frame length includes the header", payload + 7, bits(30, 13))
        assertEquals("VBR buffer fullness", 0x7FF, bits(43, 11))
        assertEquals("one raw data block", 0, bits(54, 2))
    }

    @Test
    fun `only AAC sampling frequencies are accepted`() {
        assertTrue(Aac.isSupportedSampleRate(48_000))
        assertTrue(Aac.isSupportedSampleRate(44_100))
        assertFalse(Aac.isSupportedSampleRate(47_000))
    }

    @Test
    fun `audio frames compare by content`() {
        val a = EncodedAudioFrame(byteArrayOf(1, 2, 3), 10L)
        val b = EncodedAudioFrame(byteArrayOf(1, 2, 3), 10L)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == EncodedAudioFrame(byteArrayOf(1, 2, 3), 10L, isConfig = true))
    }
}
