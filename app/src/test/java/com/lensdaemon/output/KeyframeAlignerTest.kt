package com.lensdaemon.output

import com.lensdaemon.encoder.EncodedFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [KeyframeAligner]: what one receiver joining a running
 * stream gets from the encoder's output.
 */
class KeyframeAlignerTest {

    companion object {
        private const val FLAG_KEY_FRAME = 1
        private const val FLAG_CODEC_CONFIG = 2

        private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        private val SPS = byteArrayOf(0x67, 0x42, 0x00, 0x1f, 0xe5.toByte(), 0x40)
        private val PPS = byteArrayOf(0x68, 0xce.toByte(), 0x38, 0x80.toByte())
        private val IDR = START_CODE + byteArrayOf(0x65) + ByteArray(64) { 0x11 }
        private val P = START_CODE + byteArrayOf(0x41) + ByteArray(32) { 0x22 }

        private val VPS_H265 = byteArrayOf(0x40, 0x01, 0x0c, 0x01)
        private val SPS_H265 = byteArrayOf(0x42, 0x01, 0x01, 0x01)
        private val PPS_H265 = byteArrayOf(0x44, 0x01, 0xc1.toByte(), 0x72)
        private val IDR_H265 = START_CODE + byteArrayOf(0x26, 0x01) + ByteArray(64) { 0x33 }
    }

    private fun frame(data: ByteArray, flags: Int = 0, ptsUs: Long = 0L) = EncodedFrame(data, ptsUs, flags)

    @Test
    fun `pictures before the first keyframe are held back`() {
        val aligner = KeyframeAligner(isHevc = false)
        aligner.setParameterSets(null, SPS, PPS)

        assertNull(aligner.process(frame(P)))
        assertNull(aligner.process(frame(P)))
        assertFalse(aligner.started)
        assertEquals(2, aligner.skippedFrames)

        assertArrayEquals(START_CODE + SPS + START_CODE + PPS + IDR, aligner.process(frame(IDR, FLAG_KEY_FRAME)))
        assertTrue(aligner.started)
        assertSame("pictures after the keyframe pass untouched", P, aligner.process(frame(P)))
    }

    @Test
    fun `codec-config buffers are learned, never passed on`() {
        val aligner = KeyframeAligner(isHevc = false)

        assertNull(aligner.process(frame(START_CODE + SPS + START_CODE + PPS, FLAG_CODEC_CONFIG)))
        assertArrayEquals(SPS, aligner.sps)
        assertArrayEquals(PPS, aligner.pps)
        assertArrayEquals(START_CODE + SPS + START_CODE + PPS + IDR, aligner.process(frame(IDR, FLAG_KEY_FRAME)))
    }

    @Test
    fun `keyframes carrying their parameter sets are passed unchanged and refresh the cache`() {
        val aligner = KeyframeAligner(isHevc = false)
        aligner.setParameterSets(null, SPS, PPS)
        val newSps = byteArrayOf(0x67, 0x64, 0x00, 0x28)
        val inBand = START_CODE + newSps + START_CODE + PPS + IDR

        assertSame(inBand, aligner.process(frame(inBand, FLAG_KEY_FRAME)))
        assertArrayEquals("later bare keyframes use the in-band SPS", START_CODE + newSps + START_CODE + PPS + IDR,
            aligner.process(frame(IDR, FLAG_KEY_FRAME)))
    }

    @Test
    fun `reset waits for the next keyframe again`() {
        val aligner = KeyframeAligner(isHevc = false)
        aligner.process(frame(IDR, FLAG_KEY_FRAME))
        assertSame(P, aligner.process(frame(P)))

        aligner.reset()
        assertNull("after a reset a P-frame no longer gets through", aligner.process(frame(P)))
        assertTrue(aligner.process(frame(IDR, FLAG_KEY_FRAME)) != null)
        assertSame(P, aligner.process(frame(P)))
    }

    @Test
    fun `without known parameter sets a keyframe passes as-is`() {
        val aligner = KeyframeAligner(isHevc = false)
        assertSame(IDR, aligner.process(frame(IDR, FLAG_KEY_FRAME)))
    }

    @Test
    fun `parameter sets go after an access unit delimiter the encoder wrote`() {
        val aligner = KeyframeAligner(isHevc = false)
        aligner.setParameterSets(null, SPS, PPS)
        val aud = START_CODE + byteArrayOf(0x09, 0xF0.toByte())

        assertArrayEquals(
            "the AUD must stay the first NAL unit",
            aud + START_CODE + SPS + START_CODE + PPS + IDR,
            aligner.process(frame(aud + IDR, FLAG_KEY_FRAME))
        )
    }

    @Test
    fun `H265 keyframes get VPS, SPS and PPS`() {
        val aligner = KeyframeAligner(isHevc = true)
        aligner.process(frame(START_CODE + VPS_H265 + START_CODE + SPS_H265 + START_CODE + PPS_H265, FLAG_CODEC_CONFIG))

        assertArrayEquals(
            START_CODE + VPS_H265 + START_CODE + SPS_H265 + START_CODE + PPS_H265 + IDR_H265,
            aligner.process(frame(IDR_H265, FLAG_KEY_FRAME))
        )
    }
}
