package com.lensdaemon.encoder

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [NalUnitParser], [NalUnit], [NalUnitBuilder],
 * [H264NalType], and [H265NalType].
 *
 * These tests run on the JVM without Android framework dependencies.
 * The parseSpsDimensions() method is intentionally not tested because
 * it returns android.util.Size which is unavailable in JVM tests.
 */
class NalUnitParserTest {

    private lateinit var h264Parser: NalUnitParser
    private lateinit var hevcParser: NalUnitParser

    // Common start codes
    private val startCode3 = byteArrayOf(0x00, 0x00, 0x01)
    private val startCode4 = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    @Before
    fun setUp() {
        h264Parser = NalUnitParser(isHevc = false)
        hevcParser = NalUnitParser(isHevc = true)
    }

    // ========================================================================
    // 1. Start code detection (findStartCode)
    // ========================================================================

    @Test
    fun `findStartCode detects 4-byte start code`() {
        val data = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x65)
        val result = NalUnitParser.findStartCode(data, 0)
        assertEquals(4, result)
    }

    @Test
    fun `findStartCode detects 3-byte start code`() {
        val data = byteArrayOf(0x00, 0x00, 0x01, 0x65)
        val result = NalUnitParser.findStartCode(data, 0)
        assertEquals(3, result)
    }

    @Test
    fun `findStartCode prefers 4-byte over 3-byte when both match`() {
        // 0x00 0x00 0x00 0x01 -- this is both a 4-byte start code at offset 0
        // and could be interpreted as 3-byte at offset 1, but at offset 0 the
        // 4-byte check runs first
        val data = byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67)
        val result = NalUnitParser.findStartCode(data, 0)
        assertEquals(4, result)
    }

    @Test
    fun `findStartCode returns 0 for no start code`() {
        val data = byteArrayOf(0x00, 0x00, 0x02, 0x65)
        val result = NalUnitParser.findStartCode(data, 0)
        assertEquals(0, result)
    }

    @Test
    fun `findStartCode returns 0 when data too short for 3-byte`() {
        val data = byteArrayOf(0x00, 0x00)
        val result = NalUnitParser.findStartCode(data, 0)
        assertEquals(0, result)
    }

    @Test
    fun `findStartCode with offset detects start code at non-zero position`() {
        val data = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00, 0x01, 0x65)
        assertEquals(0, NalUnitParser.findStartCode(data, 0))
        assertEquals(4, NalUnitParser.findStartCode(data, 2))
    }

    @Test
    fun `findStartCode detects 3-byte start code at offset`() {
        val data = byteArrayOf(0xFF.toByte(), 0x00, 0x00, 0x01, 0x65)
        assertEquals(3, NalUnitParser.findStartCode(data, 1))
    }

    @Test
    fun `findStartCode returns 0 for empty data`() {
        val data = byteArrayOf()
        assertEquals(0, NalUnitParser.findStartCode(data, 0))
    }

    @Test
    fun `findStartCode at end of data boundary`() {
        // Exactly 3 bytes: just enough for 3-byte start code
        val data = byteArrayOf(0x00, 0x00, 0x01)
        assertEquals(3, NalUnitParser.findStartCode(data, 0))
    }

    @Test
    fun `findStartCode at end of data not enough bytes`() {
        // offset=1 with 3 bytes total means only 2 bytes remain -- not enough
        val data = byteArrayOf(0x00, 0x00, 0x01)
        assertEquals(0, NalUnitParser.findStartCode(data, 1))
    }

    // ========================================================================
    // 2. findNextStartCode
    // ========================================================================

    @Test
    fun `findNextStartCode finds 3-byte start code`() {
        val data = byteArrayOf(0x65, 0xAA.toByte(), 0x00, 0x00, 0x01, 0x67)
        val pos = NalUnitParser.findNextStartCode(data, 0)
        assertEquals(2, pos)
    }

    @Test
    fun `findNextStartCode finds 4-byte start code`() {
        val data = byteArrayOf(0x65, 0xAA.toByte(), 0x00, 0x00, 0x00, 0x01, 0x67)
        val pos = NalUnitParser.findNextStartCode(data, 0)
        assertEquals(2, pos)
    }

    @Test
    fun `findNextStartCode returns -1 when not found`() {
        val data = byteArrayOf(0x65, 0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte())
        val pos = NalUnitParser.findNextStartCode(data, 0)
        assertEquals(-1, pos)
    }

    @Test
    fun `findNextStartCode skips start code at startOffset`() {
        // Start code at position 0; searching from offset 1 should find next at pos 5
        val data = byteArrayOf(0x00, 0x00, 0x01, 0x67, 0x42, 0x00, 0x00, 0x01, 0x68)
        val pos = NalUnitParser.findNextStartCode(data, 3)
        assertEquals(5, pos)
    }

    @Test
    fun `findNextStartCode returns -1 for empty data`() {
        assertEquals(-1, NalUnitParser.findNextStartCode(byteArrayOf(), 0))
    }

    @Test
    fun `findNextStartCode returns -1 for data shorter than 3 bytes`() {
        assertEquals(-1, NalUnitParser.findNextStartCode(byteArrayOf(0x00, 0x00), 0))
    }

    @Test
    fun `findNextStartCode finds start code at beginning`() {
        val data = byteArrayOf(0x00, 0x00, 0x01, 0x67, 0x42)
        val pos = NalUnitParser.findNextStartCode(data, 0)
        assertEquals(0, pos)
    }

    // ========================================================================
    // 3. Parsing single H.264 NAL units
    // ========================================================================

    @Test
    fun `parse single H264 SPS with 4-byte start code`() {
        // H.264 SPS: NAL type = 0x67 (0x67 & 0x1F = 7)
        val spsPayload = byteArrayOf(0x67, 0x42, 0x00, 0x1E, 0xAB.toByte())
        val data = startCode4 + spsPayload

        val nalUnits = h264Parser.parse(data)

        assertEquals(1, nalUnits.size)
        val nal = nalUnits[0]
        assertEquals(H264NalType.SPS, nal.type)
        assertEquals(4, nal.startCodeLength)
        assertEquals(spsPayload.size, nal.payloadSize)
        assertFalse(nal.isHevc)
    }

    @Test
    fun `parse single H264 NAL with 3-byte start code`() {
        // H.264 PPS: NAL type = 0x68 (0x68 & 0x1F = 8)
        val ppsPayload = byteArrayOf(0x68, 0xCE.toByte(), 0x38, 0x80.toByte())
        val data = startCode3 + ppsPayload

        val nalUnits = h264Parser.parse(data)

        assertEquals(1, nalUnits.size)
        val nal = nalUnits[0]
        assertEquals(H264NalType.PPS, nal.type)
        assertEquals(3, nal.startCodeLength)
        assertEquals(ppsPayload.size, nal.payloadSize)
    }

    @Test
    fun `parse returns payload correctly`() {
        val spsPayload = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val data = startCode4 + spsPayload

        val nal = h264Parser.parse(data)[0]
        assertArrayEquals(spsPayload, nal.payload)
    }

    @Test
    fun `parse returns fullData including start code`() {
        val spsPayload = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val data = startCode4 + spsPayload

        val nal = h264Parser.parse(data)[0]
        assertArrayEquals(data, nal.fullData)
    }

    // ========================================================================
    // 4. Parsing multiple H.264 NAL units in a stream
    // ========================================================================

    @Test
    fun `parse multiple H264 NALs in single stream`() {
        // SPS + PPS + IDR
        val spsPayload = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val ppsPayload = byteArrayOf(0x68, 0xCE.toByte(), 0x38)
        val idrPayload = byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte(), 0x40, 0x00)

        val data = startCode4 + spsPayload +
                startCode4 + ppsPayload +
                startCode4 + idrPayload

        val nalUnits = h264Parser.parse(data)

        assertEquals(3, nalUnits.size)
        assertEquals(H264NalType.SPS, nalUnits[0].type)
        assertEquals(H264NalType.PPS, nalUnits[1].type)
        assertEquals(H264NalType.SLICE_IDR, nalUnits[2].type)
    }

    @Test
    fun `parse mixed 3-byte and 4-byte start codes`() {
        val spsPayload = byteArrayOf(0x67, 0x42)
        val ppsPayload = byteArrayOf(0x68, 0xCE.toByte())

        val data = startCode4 + spsPayload + startCode3 + ppsPayload

        val nalUnits = h264Parser.parse(data)

        assertEquals(2, nalUnits.size)
        assertEquals(H264NalType.SPS, nalUnits[0].type)
        assertEquals(4, nalUnits[0].startCodeLength)
        assertEquals(H264NalType.PPS, nalUnits[1].type)
        assertEquals(3, nalUnits[1].startCodeLength)
    }

    @Test
    fun `parse stream with SPS PPS and non-IDR`() {
        val spsPayload = byteArrayOf(0x67, 0x42)
        // Non-IDR slice: type = 0x41 & 0x1F = 1
        val nonIdrPayload = byteArrayOf(0x41, 0x9A.toByte(), 0x18)

        val data = startCode4 + spsPayload + startCode4 + nonIdrPayload

        val nalUnits = h264Parser.parse(data)

        assertEquals(2, nalUnits.size)
        assertEquals(H264NalType.SPS, nalUnits[0].type)
        assertEquals(H264NalType.SLICE_NON_IDR, nalUnits[1].type)
    }

    // ========================================================================
    // 5. H.264 NAL type identification
    // ========================================================================

    @Test
    fun `H264 SPS type identified correctly`() {
        val data = startCode4 + byteArrayOf(0x67, 0x42, 0x00)
        val nal = h264Parser.parse(data)[0]
        assertEquals(H264NalType.SPS, nal.type)
        assertEquals("SPS", nal.typeName)
        assertTrue(nal.isConfigData)
        assertFalse(nal.isKeyFrame)
    }

    @Test
    fun `H264 PPS type identified correctly`() {
        val data = startCode4 + byteArrayOf(0x68, 0xCE.toByte())
        val nal = h264Parser.parse(data)[0]
        assertEquals(H264NalType.PPS, nal.type)
        assertEquals("PPS", nal.typeName)
        assertTrue(nal.isConfigData)
        assertFalse(nal.isKeyFrame)
    }

    @Test
    fun `H264 IDR type identified correctly`() {
        val data = startCode4 + byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte())
        val nal = h264Parser.parse(data)[0]
        assertEquals(H264NalType.SLICE_IDR, nal.type)
        assertEquals("IDR (Keyframe)", nal.typeName)
        assertTrue(nal.isKeyFrame)
        assertFalse(nal.isConfigData)
    }

    @Test
    fun `H264 non-IDR type identified correctly`() {
        // NAL type 1: 0x41 & 0x1F = 1
        val data = startCode4 + byteArrayOf(0x41, 0x9A.toByte())
        val nal = h264Parser.parse(data)[0]
        assertEquals(H264NalType.SLICE_NON_IDR, nal.type)
        assertEquals("P/B-Frame", nal.typeName)
        assertFalse(nal.isKeyFrame)
        assertFalse(nal.isConfigData)
    }

    @Test
    fun `H264 SEI type identified correctly`() {
        // NAL type 6: 0x06 & 0x1F = 6
        val data = startCode4 + byteArrayOf(0x06, 0x05, 0x04)
        val nal = h264Parser.parse(data)[0]
        assertEquals(H264NalType.SEI, nal.type)
        assertEquals("SEI", nal.typeName)
        assertFalse(nal.isKeyFrame)
        assertFalse(nal.isConfigData)
    }

    @Test
    fun `H264 AUD type identified correctly`() {
        // NAL type 9: 0x09 & 0x1F = 9
        val data = startCode4 + byteArrayOf(0x09, 0xF0.toByte())
        val nal = h264Parser.parse(data)[0]
        assertEquals(H264NalType.AUD, nal.type)
        assertEquals("AUD", nal.typeName)
    }

    @Test
    fun `H264 NAL type extracts only lower 5 bits`() {
        // 0xE7 = 1110_0111 -- lower 5 bits = 0x07 = 7 (SPS)
        // nal_ref_idc = 3, nal_unit_type = 7
        val data = startCode4 + byteArrayOf(0xE7.toByte(), 0x42)
        val nal = h264Parser.parse(data)[0]
        assertEquals(H264NalType.SPS, nal.type)
    }

    // ========================================================================
    // 6. H.265/HEVC NAL type identification
    // ========================================================================

    @Test
    fun `HEVC VPS type identified correctly`() {
        // H.265 VPS: type=32, NAL header byte = (32 << 1) = 0x40
        val data = startCode4 + byteArrayOf(0x40, 0x01, 0x0C)
        val nal = hevcParser.parse(data)[0]
        assertEquals(H265NalType.VPS, nal.type)
        assertEquals("VPS", nal.typeName)
        assertTrue(nal.isConfigData)
        assertFalse(nal.isKeyFrame)
    }

    @Test
    fun `HEVC SPS type identified correctly`() {
        // H.265 SPS: type=33, NAL header byte = (33 << 1) = 0x42
        val data = startCode4 + byteArrayOf(0x42, 0x01, 0x01)
        val nal = hevcParser.parse(data)[0]
        assertEquals(H265NalType.SPS, nal.type)
        assertEquals("SPS", nal.typeName)
        assertTrue(nal.isConfigData)
        assertFalse(nal.isKeyFrame)
    }

    @Test
    fun `HEVC PPS type identified correctly`() {
        // H.265 PPS: type=34, NAL header byte = (34 << 1) = 0x44
        val data = startCode4 + byteArrayOf(0x44, 0x01, 0xC1.toByte())
        val nal = hevcParser.parse(data)[0]
        assertEquals(H265NalType.PPS, nal.type)
        assertEquals("PPS", nal.typeName)
        assertTrue(nal.isConfigData)
        assertFalse(nal.isKeyFrame)
    }

    @Test
    fun `HEVC IDR_W_RADL type identified correctly`() {
        // H.265 IDR_W_RADL: type=19, NAL header byte = (19 << 1) = 0x26
        val data = startCode4 + byteArrayOf(0x26, 0x01, 0xAF.toByte())
        val nal = hevcParser.parse(data)[0]
        assertEquals(H265NalType.IDR_W_RADL, nal.type)
        assertEquals("IDR (Keyframe)", nal.typeName)
        assertTrue(nal.isKeyFrame)
        assertFalse(nal.isConfigData)
    }

    @Test
    fun `HEVC IDR_N_LP type identified correctly`() {
        // H.265 IDR_N_LP: type=20, NAL header byte = (20 << 1) = 0x28
        val data = startCode4 + byteArrayOf(0x28, 0x01, 0xAF.toByte())
        val nal = hevcParser.parse(data)[0]
        assertEquals(H265NalType.IDR_N_LP, nal.type)
        assertEquals("IDR (Keyframe)", nal.typeName)
        assertTrue(nal.isKeyFrame)
    }

    @Test
    fun `HEVC CRA type is keyframe`() {
        // H.265 CRA_NUT: type=21, NAL header byte = (21 << 1) = 0x2A
        val data = startCode4 + byteArrayOf(0x2A, 0x01, 0xAF.toByte())
        val nal = hevcParser.parse(data)[0]
        assertEquals(H265NalType.CRA_NUT, nal.type)
        assertEquals("CRA", nal.typeName)
        assertTrue(nal.isKeyFrame)
    }

    @Test
    fun `HEVC TRAIL_R type identified correctly`() {
        // H.265 TRAIL_R: type=1, NAL header byte = (1 << 1) = 0x02
        val data = startCode4 + byteArrayOf(0x02, 0x01, 0xAF.toByte())
        val nal = hevcParser.parse(data)[0]
        assertEquals(H265NalType.TRAIL_R, nal.type)
        assertEquals("Trail", nal.typeName)
        assertFalse(nal.isKeyFrame)
        assertFalse(nal.isConfigData)
    }

    @Test
    fun `HEVC BLA types are keyframes`() {
        // BLA_W_LP: type=16, NAL header byte = (16 << 1) = 0x20
        val data = startCode4 + byteArrayOf(0x20, 0x01, 0xAF.toByte())
        val nal = hevcParser.parse(data)[0]
        assertEquals(H265NalType.BLA_W_LP, nal.type)
        assertTrue(nal.isKeyFrame)
    }

    @Test
    fun `HEVC parses multiple NALs with VPS SPS PPS IDR`() {
        // VPS (type=32, byte=0x40) + SPS (type=33, byte=0x42)
        // + PPS (type=34, byte=0x44) + IDR (type=19, byte=0x26)
        val data = startCode4 + byteArrayOf(0x40, 0x01, 0x0C) +
                startCode4 + byteArrayOf(0x42, 0x01, 0x01) +
                startCode4 + byteArrayOf(0x44, 0x01, 0xC1.toByte()) +
                startCode4 + byteArrayOf(0x26, 0x01, 0xAF.toByte(), 0xBB.toByte())

        val nalUnits = hevcParser.parse(data)

        assertEquals(4, nalUnits.size)
        assertEquals(H265NalType.VPS, nalUnits[0].type)
        assertEquals(H265NalType.SPS, nalUnits[1].type)
        assertEquals(H265NalType.PPS, nalUnits[2].type)
        assertEquals(H265NalType.IDR_W_RADL, nalUnits[3].type)

        // All should be marked as HEVC
        nalUnits.forEach { assertTrue(it.isHevc) }
    }

    @Test
    fun `HEVC NAL type extraction uses bits 1-6`() {
        // Byte 0x7E = 0111_1110 -> (0x7E & 0x7E) >> 1 = 0x3F = 63
        // But the implementation uses (byte & 0x7E) >> 1
        // Let's verify with a known type: VPS=32 -> byte = (32 << 1) | forbidden_bit=0 = 0x40
        // (0x40 & 0x7E) >> 1 = (0x40) >> 1 = 32
        val data = startCode4 + byteArrayOf(0x40, 0x01)
        val nal = hevcParser.parse(data)[0]
        assertEquals(32, nal.type)
    }

    // ========================================================================
    // 7. SPS/PPS caching during parse
    // ========================================================================

    @Test
    fun `H264 SPS is cached after parsing`() {
        val spsPayload = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val data = startCode4 + spsPayload

        assertNull(h264Parser.sps)
        h264Parser.parse(data)
        assertNotNull(h264Parser.sps)
        assertArrayEquals(spsPayload, h264Parser.sps)
    }

    @Test
    fun `H264 PPS is cached after parsing`() {
        val ppsPayload = byteArrayOf(0x68, 0xCE.toByte(), 0x38)
        val data = startCode4 + ppsPayload

        assertNull(h264Parser.pps)
        h264Parser.parse(data)
        assertNotNull(h264Parser.pps)
        assertArrayEquals(ppsPayload, h264Parser.pps)
    }

    @Test
    fun `H264 SPS and PPS cached from multi-NAL stream`() {
        val spsPayload = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val ppsPayload = byteArrayOf(0x68, 0xCE.toByte(), 0x38)
        val idrPayload = byteArrayOf(0x65, 0x88.toByte())

        val data = startCode4 + spsPayload +
                startCode4 + ppsPayload +
                startCode4 + idrPayload

        h264Parser.parse(data)

        assertNotNull(h264Parser.sps)
        assertNotNull(h264Parser.pps)
        assertArrayEquals(spsPayload, h264Parser.sps)
        assertArrayEquals(ppsPayload, h264Parser.pps)
    }

    @Test
    fun `H264 VPS is not cached`() {
        // H.264 has no VPS
        val spsPayload = byteArrayOf(0x67, 0x42)
        val data = startCode4 + spsPayload

        h264Parser.parse(data)
        assertNull(h264Parser.vps)
    }

    @Test
    fun `HEVC VPS is cached after parsing`() {
        // VPS: type=32, header byte = 0x40
        val vpsPayload = byteArrayOf(0x40, 0x01, 0x0C)
        val data = startCode4 + vpsPayload

        assertNull(hevcParser.vps)
        hevcParser.parse(data)
        assertNotNull(hevcParser.vps)
        assertArrayEquals(vpsPayload, hevcParser.vps)
    }

    @Test
    fun `HEVC SPS is cached after parsing`() {
        val spsPayload = byteArrayOf(0x42, 0x01, 0x01)
        val data = startCode4 + spsPayload

        hevcParser.parse(data)
        assertNotNull(hevcParser.sps)
        assertArrayEquals(spsPayload, hevcParser.sps)
    }

    @Test
    fun `HEVC PPS is cached after parsing`() {
        val ppsPayload = byteArrayOf(0x44, 0x01, 0xC1.toByte())
        val data = startCode4 + ppsPayload

        hevcParser.parse(data)
        assertNotNull(hevcParser.pps)
        assertArrayEquals(ppsPayload, hevcParser.pps)
    }

    @Test
    fun `HEVC VPS SPS PPS all cached from multi-NAL stream`() {
        val vpsPayload = byteArrayOf(0x40, 0x01, 0x0C)
        val spsPayload = byteArrayOf(0x42, 0x01, 0x01)
        val ppsPayload = byteArrayOf(0x44, 0x01, 0xC1.toByte())

        val data = startCode4 + vpsPayload +
                startCode4 + spsPayload +
                startCode4 + ppsPayload

        hevcParser.parse(data)

        assertNotNull(hevcParser.vps)
        assertNotNull(hevcParser.sps)
        assertNotNull(hevcParser.pps)
        assertArrayEquals(vpsPayload, hevcParser.vps)
        assertArrayEquals(spsPayload, hevcParser.sps)
        assertArrayEquals(ppsPayload, hevcParser.pps)
    }

    @Test
    fun `cached config data updated on subsequent parse calls`() {
        val spsPayload1 = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val spsPayload2 = byteArrayOf(0x67, 0x64, 0x00, 0x28)

        h264Parser.parse(startCode4 + spsPayload1)
        assertArrayEquals(spsPayload1, h264Parser.sps)

        h264Parser.parse(startCode4 + spsPayload2)
        assertArrayEquals(spsPayload2, h264Parser.sps)
    }

    // ========================================================================
    // 8. hasConfigData and getConfigData
    // ========================================================================

    @Test
    fun `H264 hasConfigData returns false when no config cached`() {
        assertFalse(h264Parser.hasConfigData())
    }

    @Test
    fun `H264 hasConfigData returns false when only SPS cached`() {
        val spsPayload = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        h264Parser.parse(startCode4 + spsPayload)
        assertFalse(h264Parser.hasConfigData())
    }

    @Test
    fun `H264 hasConfigData returns false when only PPS cached`() {
        val ppsPayload = byteArrayOf(0x68, 0xCE.toByte())
        h264Parser.parse(startCode4 + ppsPayload)
        assertFalse(h264Parser.hasConfigData())
    }

    @Test
    fun `H264 hasConfigData returns true when SPS and PPS both cached`() {
        val spsPayload = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val ppsPayload = byteArrayOf(0x68, 0xCE.toByte(), 0x38)

        h264Parser.parse(startCode4 + spsPayload + startCode4 + ppsPayload)
        assertTrue(h264Parser.hasConfigData())
    }

    @Test
    fun `H264 getConfigData returns null when config incomplete`() {
        assertNull(h264Parser.getConfigData())
    }

    @Test
    fun `H264 getConfigData returns SPS and PPS with start codes`() {
        val spsPayload = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val ppsPayload = byteArrayOf(0x68, 0xCE.toByte(), 0x38)

        h264Parser.parse(startCode4 + spsPayload + startCode4 + ppsPayload)

        val configData = h264Parser.getConfigData()
        assertNotNull(configData)

        // Expected: start_code_4 + SPS + start_code_4 + PPS
        val expected = startCode4 + spsPayload + startCode4 + ppsPayload
        assertArrayEquals(expected, configData)
    }

    @Test
    fun `HEVC hasConfigData returns false when missing VPS`() {
        val spsPayload = byteArrayOf(0x42, 0x01, 0x01)
        val ppsPayload = byteArrayOf(0x44, 0x01, 0xC1.toByte())

        hevcParser.parse(startCode4 + spsPayload + startCode4 + ppsPayload)
        assertFalse(hevcParser.hasConfigData())
    }

    @Test
    fun `HEVC hasConfigData returns false when missing SPS`() {
        val vpsPayload = byteArrayOf(0x40, 0x01, 0x0C)
        val ppsPayload = byteArrayOf(0x44, 0x01, 0xC1.toByte())

        hevcParser.parse(startCode4 + vpsPayload + startCode4 + ppsPayload)
        assertFalse(hevcParser.hasConfigData())
    }

    @Test
    fun `HEVC hasConfigData returns false when missing PPS`() {
        val vpsPayload = byteArrayOf(0x40, 0x01, 0x0C)
        val spsPayload = byteArrayOf(0x42, 0x01, 0x01)

        hevcParser.parse(startCode4 + vpsPayload + startCode4 + spsPayload)
        assertFalse(hevcParser.hasConfigData())
    }

    @Test
    fun `HEVC hasConfigData returns true when VPS SPS PPS all cached`() {
        val vpsPayload = byteArrayOf(0x40, 0x01, 0x0C)
        val spsPayload = byteArrayOf(0x42, 0x01, 0x01)
        val ppsPayload = byteArrayOf(0x44, 0x01, 0xC1.toByte())

        hevcParser.parse(startCode4 + vpsPayload + startCode4 + spsPayload + startCode4 + ppsPayload)
        assertTrue(hevcParser.hasConfigData())
    }

    @Test
    fun `HEVC getConfigData returns VPS SPS PPS with start codes`() {
        val vpsPayload = byteArrayOf(0x40, 0x01, 0x0C)
        val spsPayload = byteArrayOf(0x42, 0x01, 0x01)
        val ppsPayload = byteArrayOf(0x44, 0x01, 0xC1.toByte())

        hevcParser.parse(startCode4 + vpsPayload + startCode4 + spsPayload + startCode4 + ppsPayload)

        val configData = hevcParser.getConfigData()
        assertNotNull(configData)

        // Expected: start_code_4 + VPS + start_code_4 + SPS + start_code_4 + PPS
        val expected = startCode4 + vpsPayload + startCode4 + spsPayload + startCode4 + ppsPayload
        assertArrayEquals(expected, configData)
    }

    @Test
    fun `getConfigData returns correct size for H264`() {
        val spsPayload = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val ppsPayload = byteArrayOf(0x68, 0xCE.toByte(), 0x38)

        h264Parser.parse(startCode4 + spsPayload + startCode4 + ppsPayload)

        val configData = h264Parser.getConfigData()!!
        // 4 + 4 + 4 + 3 = 15
        assertEquals(startCode4.size * 2 + spsPayload.size + ppsPayload.size, configData.size)
    }

    @Test
    fun `getConfigData returns correct size for HEVC`() {
        val vpsPayload = byteArrayOf(0x40, 0x01, 0x0C)
        val spsPayload = byteArrayOf(0x42, 0x01, 0x01)
        val ppsPayload = byteArrayOf(0x44, 0x01, 0xC1.toByte())

        hevcParser.parse(startCode4 + vpsPayload + startCode4 + spsPayload + startCode4 + ppsPayload)

        val configData = hevcParser.getConfigData()!!
        assertEquals(startCode4.size * 3 + vpsPayload.size + spsPayload.size + ppsPayload.size, configData.size)
    }

    // ========================================================================
    // 9. clearCache
    // ========================================================================

    @Test
    fun `clearCache clears H264 SPS and PPS`() {
        val spsPayload = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val ppsPayload = byteArrayOf(0x68, 0xCE.toByte(), 0x38)

        h264Parser.parse(startCode4 + spsPayload + startCode4 + ppsPayload)
        assertTrue(h264Parser.hasConfigData())

        h264Parser.clearCache()
        assertNull(h264Parser.sps)
        assertNull(h264Parser.pps)
        assertNull(h264Parser.vps)
        assertFalse(h264Parser.hasConfigData())
    }

    @Test
    fun `clearCache clears HEVC VPS SPS and PPS`() {
        val vpsPayload = byteArrayOf(0x40, 0x01, 0x0C)
        val spsPayload = byteArrayOf(0x42, 0x01, 0x01)
        val ppsPayload = byteArrayOf(0x44, 0x01, 0xC1.toByte())

        hevcParser.parse(startCode4 + vpsPayload + startCode4 + spsPayload + startCode4 + ppsPayload)
        assertTrue(hevcParser.hasConfigData())

        hevcParser.clearCache()
        assertNull(hevcParser.vps)
        assertNull(hevcParser.sps)
        assertNull(hevcParser.pps)
        assertFalse(hevcParser.hasConfigData())
    }

    @Test
    fun `clearCache allows re-caching on next parse`() {
        val spsPayload1 = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val ppsPayload1 = byteArrayOf(0x68, 0xCE.toByte(), 0x38)

        h264Parser.parse(startCode4 + spsPayload1 + startCode4 + ppsPayload1)
        h264Parser.clearCache()

        val spsPayload2 = byteArrayOf(0x67, 0x64, 0x00, 0x28)
        val ppsPayload2 = byteArrayOf(0x68, 0xEE.toByte(), 0x3C)

        h264Parser.parse(startCode4 + spsPayload2 + startCode4 + ppsPayload2)
        assertTrue(h264Parser.hasConfigData())
        assertArrayEquals(spsPayload2, h264Parser.sps)
        assertArrayEquals(ppsPayload2, h264Parser.pps)
    }

    @Test
    fun `clearCache on fresh parser does not throw`() {
        h264Parser.clearCache()
        hevcParser.clearCache()
        // No exception expected
        assertNull(h264Parser.sps)
        assertNull(hevcParser.vps)
    }

    // ========================================================================
    // 10. NalUnitBuilder.withStartCode
    // ========================================================================

    @Test
    fun `withStartCode prepends 4-byte start code`() {
        val payload = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val result = NalUnitBuilder.withStartCode(payload)

        assertEquals(startCode4.size + payload.size, result.size)
        // Verify start code
        assertEquals(0x00.toByte(), result[0])
        assertEquals(0x00.toByte(), result[1])
        assertEquals(0x00.toByte(), result[2])
        assertEquals(0x01.toByte(), result[3])
        // Verify payload
        for (i in payload.indices) {
            assertEquals(payload[i], result[4 + i])
        }
    }

    @Test
    fun `withStartCode on empty payload returns just start code`() {
        val result = NalUnitBuilder.withStartCode(byteArrayOf())
        assertEquals(4, result.size)
        assertArrayEquals(startCode4, result)
    }

    @Test
    fun `withStartCode result is parseable`() {
        val spsPayload = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val data = NalUnitBuilder.withStartCode(spsPayload)

        val nalUnits = h264Parser.parse(data)
        assertEquals(1, nalUnits.size)
        assertEquals(H264NalType.SPS, nalUnits[0].type)
        assertArrayEquals(spsPayload, nalUnits[0].payload)
    }

    @Test
    fun `withStartCode on single byte payload`() {
        val payload = byteArrayOf(0x65)
        val result = NalUnitBuilder.withStartCode(payload)
        assertEquals(5, result.size)
        assertEquals(0x65.toByte(), result[4])
    }

    // ========================================================================
    // 11. NalUnitBuilder.createKeyFrameWithConfig
    // ========================================================================

    @Test
    fun `createKeyFrameWithConfig produces correct H264 stream`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte())

        val result = NalUnitBuilder.createKeyFrameWithConfig(sps, pps, idr)

        // Total size: 3 start codes (4 bytes each) + sps + pps + idr
        val expectedSize = 4 * 3 + sps.size + pps.size + idr.size
        assertEquals(expectedSize, result.size)

        // Parse and verify
        val nalUnits = h264Parser.parse(result)
        assertEquals(3, nalUnits.size)
        assertEquals(H264NalType.SPS, nalUnits[0].type)
        assertEquals(H264NalType.PPS, nalUnits[1].type)
        assertEquals(H264NalType.SLICE_IDR, nalUnits[2].type)
    }

    @Test
    fun `createKeyFrameWithConfig payloads are preserved`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte(), 0x40, 0x00)

        val result = NalUnitBuilder.createKeyFrameWithConfig(sps, pps, idr)
        val nalUnits = h264Parser.parse(result)

        assertArrayEquals(sps, nalUnits[0].payload)
        assertArrayEquals(pps, nalUnits[1].payload)
        assertArrayEquals(idr, nalUnits[2].payload)
    }

    @Test
    fun `createKeyFrameWithConfig caches SPS and PPS`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)
        val idr = byteArrayOf(0x65, 0x88.toByte())

        val result = NalUnitBuilder.createKeyFrameWithConfig(sps, pps, idr)
        h264Parser.parse(result)

        assertTrue(h264Parser.hasConfigData())
        assertArrayEquals(sps, h264Parser.sps)
        assertArrayEquals(pps, h264Parser.pps)
    }

    @Test
    fun `createHevcKeyFrameWithConfig produces correct HEVC stream`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte())
        val idr = byteArrayOf(0x26, 0x01, 0xAF.toByte(), 0xBB.toByte())

        val result = NalUnitBuilder.createHevcKeyFrameWithConfig(vps, sps, pps, idr)

        // Total size: 4 start codes (4 bytes each) + vps + sps + pps + idr
        val expectedSize = 4 * 4 + vps.size + sps.size + pps.size + idr.size
        assertEquals(expectedSize, result.size)

        // Parse and verify
        val nalUnits = hevcParser.parse(result)
        assertEquals(4, nalUnits.size)
        assertEquals(H265NalType.VPS, nalUnits[0].type)
        assertEquals(H265NalType.SPS, nalUnits[1].type)
        assertEquals(H265NalType.PPS, nalUnits[2].type)
        assertEquals(H265NalType.IDR_W_RADL, nalUnits[3].type)
    }

    @Test
    fun `createHevcKeyFrameWithConfig caches VPS SPS PPS`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte())
        val idr = byteArrayOf(0x26, 0x01, 0xAF.toByte())

        val result = NalUnitBuilder.createHevcKeyFrameWithConfig(vps, sps, pps, idr)
        hevcParser.parse(result)

        assertTrue(hevcParser.hasConfigData())
        assertArrayEquals(vps, hevcParser.vps)
        assertArrayEquals(sps, hevcParser.sps)
        assertArrayEquals(pps, hevcParser.pps)
    }

    @Test
    fun `createHevcKeyFrameWithConfig payloads are preserved`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01, 0x60)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte(), 0x72)
        val idr = byteArrayOf(0x26, 0x01, 0xAF.toByte(), 0xBB.toByte(), 0xCC.toByte())

        val result = NalUnitBuilder.createHevcKeyFrameWithConfig(vps, sps, pps, idr)
        val nalUnits = hevcParser.parse(result)

        assertArrayEquals(vps, nalUnits[0].payload)
        assertArrayEquals(sps, nalUnits[1].payload)
        assertArrayEquals(pps, nalUnits[2].payload)
        assertArrayEquals(idr, nalUnits[3].payload)
    }

    // ========================================================================
    // 12. Empty and edge-case data handling
    // ========================================================================

    @Test
    fun `parse empty data returns empty list`() {
        val result = h264Parser.parse(byteArrayOf())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse data with no start code returns empty list`() {
        val data = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val result = h264Parser.parse(data)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse data with only start code and no payload returns empty list`() {
        // 4-byte start code only, no NAL byte after
        val result = h264Parser.parse(startCode4)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `parse single byte payload after start code`() {
        val data = startCode4 + byteArrayOf(0x67)
        val nalUnits = h264Parser.parse(data)
        assertEquals(1, nalUnits.size)
        assertEquals(H264NalType.SPS, nalUnits[0].type)
        assertEquals(1, nalUnits[0].payloadSize)
    }

    @Test
    fun `parse data starting with garbage before start code`() {
        // Garbage bytes then valid start code + NAL
        val data = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0xFD.toByte()) +
                startCode4 + byteArrayOf(0x67, 0x42, 0x00)

        val nalUnits = h264Parser.parse(data)
        assertEquals(1, nalUnits.size)
        assertEquals(H264NalType.SPS, nalUnits[0].type)
    }

    @Test
    fun `parse preserves payloadOffset`() {
        val data = startCode4 + byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val nal = h264Parser.parse(data)[0]
        assertEquals(4, nal.payloadOffset) // After 4-byte start code
    }

    @Test
    fun `parse with 3-byte start code preserves payloadOffset`() {
        val data = startCode3 + byteArrayOf(0x67, 0x42)
        val nal = h264Parser.parse(data)[0]
        assertEquals(3, nal.payloadOffset) // After 3-byte start code
    }

    @Test
    fun `parse large number of NALs`() {
        // Create 20 non-IDR NALs
        var data = byteArrayOf()
        for (i in 0 until 20) {
            data += startCode4 + byteArrayOf(0x41, i.toByte())
        }

        val nalUnits = h264Parser.parse(data)
        assertEquals(20, nalUnits.size)
        nalUnits.forEach { assertEquals(H264NalType.SLICE_NON_IDR, it.type) }
    }

    // ========================================================================
    // 13. isKeyFrame and isConfigData computed properties
    // ========================================================================

    @Test
    fun `H264 NalUnit isKeyFrame true for IDR`() {
        val data = startCode4 + byteArrayOf(0x65, 0x88.toByte())
        val nal = h264Parser.parse(data)[0]
        assertTrue(nal.isKeyFrame)
    }

    @Test
    fun `H264 NalUnit isKeyFrame false for non-IDR slice`() {
        val data = startCode4 + byteArrayOf(0x41, 0x9A.toByte())
        val nal = h264Parser.parse(data)[0]
        assertFalse(nal.isKeyFrame)
    }

    @Test
    fun `H264 NalUnit isKeyFrame false for SPS`() {
        val data = startCode4 + byteArrayOf(0x67, 0x42)
        val nal = h264Parser.parse(data)[0]
        assertFalse(nal.isKeyFrame)
    }

    @Test
    fun `H264 NalUnit isConfigData true for SPS`() {
        val data = startCode4 + byteArrayOf(0x67, 0x42)
        val nal = h264Parser.parse(data)[0]
        assertTrue(nal.isConfigData)
    }

    @Test
    fun `H264 NalUnit isConfigData true for PPS`() {
        val data = startCode4 + byteArrayOf(0x68, 0xCE.toByte())
        val nal = h264Parser.parse(data)[0]
        assertTrue(nal.isConfigData)
    }

    @Test
    fun `H264 NalUnit isConfigData false for IDR`() {
        val data = startCode4 + byteArrayOf(0x65, 0x88.toByte())
        val nal = h264Parser.parse(data)[0]
        assertFalse(nal.isConfigData)
    }

    @Test
    fun `H264 NalUnit isConfigData false for SEI`() {
        val data = startCode4 + byteArrayOf(0x06, 0x05)
        val nal = h264Parser.parse(data)[0]
        assertFalse(nal.isConfigData)
    }

    @Test
    fun `HEVC NalUnit isKeyFrame true for IDR_W_RADL`() {
        val data = startCode4 + byteArrayOf(0x26, 0x01)
        val nal = hevcParser.parse(data)[0]
        assertTrue(nal.isKeyFrame)
    }

    @Test
    fun `HEVC NalUnit isKeyFrame true for IDR_N_LP`() {
        val data = startCode4 + byteArrayOf(0x28, 0x01)
        val nal = hevcParser.parse(data)[0]
        assertTrue(nal.isKeyFrame)
    }

    @Test
    fun `HEVC NalUnit isKeyFrame false for TRAIL`() {
        val data = startCode4 + byteArrayOf(0x02, 0x01)
        val nal = hevcParser.parse(data)[0]
        assertFalse(nal.isKeyFrame)
    }

    @Test
    fun `HEVC NalUnit isConfigData true for VPS`() {
        val data = startCode4 + byteArrayOf(0x40, 0x01)
        val nal = hevcParser.parse(data)[0]
        assertTrue(nal.isConfigData)
    }

    @Test
    fun `HEVC NalUnit isConfigData true for SPS`() {
        val data = startCode4 + byteArrayOf(0x42, 0x01)
        val nal = hevcParser.parse(data)[0]
        assertTrue(nal.isConfigData)
    }

    @Test
    fun `HEVC NalUnit isConfigData true for PPS`() {
        val data = startCode4 + byteArrayOf(0x44, 0x01)
        val nal = hevcParser.parse(data)[0]
        assertTrue(nal.isConfigData)
    }

    @Test
    fun `HEVC NalUnit isConfigData false for IDR`() {
        val data = startCode4 + byteArrayOf(0x26, 0x01)
        val nal = hevcParser.parse(data)[0]
        assertFalse(nal.isConfigData)
    }

    // ========================================================================
    // Additional: H264NalType and H265NalType static helpers
    // ========================================================================

    @Test
    fun `H264NalType isKeyFrame only for SLICE_IDR`() {
        assertTrue(H264NalType.isKeyFrame(H264NalType.SLICE_IDR))
        assertFalse(H264NalType.isKeyFrame(H264NalType.SLICE_NON_IDR))
        assertFalse(H264NalType.isKeyFrame(H264NalType.SPS))
        assertFalse(H264NalType.isKeyFrame(H264NalType.PPS))
        assertFalse(H264NalType.isKeyFrame(H264NalType.SEI))
        assertFalse(H264NalType.isKeyFrame(H264NalType.AUD))
    }

    @Test
    fun `H264NalType isConfigData for SPS and PPS only`() {
        assertTrue(H264NalType.isConfigData(H264NalType.SPS))
        assertTrue(H264NalType.isConfigData(H264NalType.PPS))
        assertFalse(H264NalType.isConfigData(H264NalType.SLICE_IDR))
        assertFalse(H264NalType.isConfigData(H264NalType.SLICE_NON_IDR))
        assertFalse(H264NalType.isConfigData(H264NalType.SEI))
    }

    @Test
    fun `H264NalType getName returns expected strings`() {
        assertEquals("SPS", H264NalType.getName(H264NalType.SPS))
        assertEquals("PPS", H264NalType.getName(H264NalType.PPS))
        assertEquals("IDR (Keyframe)", H264NalType.getName(H264NalType.SLICE_IDR))
        assertEquals("P/B-Frame", H264NalType.getName(H264NalType.SLICE_NON_IDR))
        assertEquals("SEI", H264NalType.getName(H264NalType.SEI))
        assertEquals("AUD", H264NalType.getName(H264NalType.AUD))
        assertEquals("NAL(12)", H264NalType.getName(H264NalType.FILLER))
    }

    @Test
    fun `H265NalType isKeyFrame for types 16 through 21`() {
        assertTrue(H265NalType.isKeyFrame(H265NalType.BLA_W_LP))    // 16
        assertTrue(H265NalType.isKeyFrame(H265NalType.BLA_W_RADL))  // 17
        assertTrue(H265NalType.isKeyFrame(H265NalType.BLA_N_LP))    // 18
        assertTrue(H265NalType.isKeyFrame(H265NalType.IDR_W_RADL))  // 19
        assertTrue(H265NalType.isKeyFrame(H265NalType.IDR_N_LP))    // 20
        assertTrue(H265NalType.isKeyFrame(H265NalType.CRA_NUT))     // 21
        assertFalse(H265NalType.isKeyFrame(15))
        assertFalse(H265NalType.isKeyFrame(22))
        assertFalse(H265NalType.isKeyFrame(H265NalType.TRAIL_R))
        assertFalse(H265NalType.isKeyFrame(H265NalType.VPS))
    }

    @Test
    fun `H265NalType isConfigData for types 32 through 34`() {
        assertTrue(H265NalType.isConfigData(H265NalType.VPS))  // 32
        assertTrue(H265NalType.isConfigData(H265NalType.SPS))  // 33
        assertTrue(H265NalType.isConfigData(H265NalType.PPS))  // 34
        assertFalse(H265NalType.isConfigData(31))
        assertFalse(H265NalType.isConfigData(H265NalType.AUD)) // 35
        assertFalse(H265NalType.isConfigData(H265NalType.IDR_W_RADL))
    }

    @Test
    fun `H265NalType getName returns expected strings`() {
        assertEquals("VPS", H265NalType.getName(H265NalType.VPS))
        assertEquals("SPS", H265NalType.getName(H265NalType.SPS))
        assertEquals("PPS", H265NalType.getName(H265NalType.PPS))
        assertEquals("IDR (Keyframe)", H265NalType.getName(H265NalType.IDR_W_RADL))
        assertEquals("IDR (Keyframe)", H265NalType.getName(H265NalType.IDR_N_LP))
        assertEquals("CRA", H265NalType.getName(H265NalType.CRA_NUT))
        assertEquals("Trail", H265NalType.getName(H265NalType.TRAIL_N))
        assertEquals("Trail", H265NalType.getName(H265NalType.TRAIL_R))
        assertEquals("AUD", H265NalType.getName(H265NalType.AUD))
        assertEquals("NAL(38)", H265NalType.getName(H265NalType.FD))
    }

    // ========================================================================
    // Additional: NalUnit data class properties
    // ========================================================================

    @Test
    fun `NalUnit toString includes type name and size`() {
        val data = startCode4 + byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val nal = h264Parser.parse(data)[0]
        val str = nal.toString()
        assertTrue(str.contains("SPS"))
        assertTrue(str.contains("size=4"))
        assertTrue(str.contains("keyframe=false"))
    }

    @Test
    fun `NalUnit toString for keyframe`() {
        val data = startCode4 + byteArrayOf(0x65, 0x88.toByte())
        val nal = h264Parser.parse(data)[0]
        val str = nal.toString()
        assertTrue(str.contains("IDR"))
        assertTrue(str.contains("keyframe=true"))
    }

    @Test
    fun `NalUnit equals compares type and data`() {
        val data1 = startCode4 + byteArrayOf(0x67, 0x42, 0x00)
        val data2 = startCode4 + byteArrayOf(0x67, 0x42, 0x00)
        val nal1 = h264Parser.parse(data1)[0]
        val nal2 = h264Parser.parse(data2)[0]
        assertEquals(nal1, nal2)
    }

    @Test
    fun `NalUnit not equal when different type`() {
        val data1 = startCode4 + byteArrayOf(0x67, 0x42)
        val data2 = startCode4 + byteArrayOf(0x68, 0x42)

        val parser1 = NalUnitParser(isHevc = false)
        val parser2 = NalUnitParser(isHevc = false)

        val nal1 = parser1.parse(data1)[0]
        val nal2 = parser2.parse(data2)[0]
        assertFalse(nal1 == nal2)
    }

    @Test
    fun `NalUnit hashCode consistent with equals`() {
        val data1 = startCode4 + byteArrayOf(0x67, 0x42, 0x00)
        val data2 = startCode4 + byteArrayOf(0x67, 0x42, 0x00)
        val nal1 = NalUnitParser(isHevc = false).parse(data1)[0]
        val nal2 = NalUnitParser(isHevc = false).parse(data2)[0]
        assertEquals(nal1.hashCode(), nal2.hashCode())
    }

    // ========================================================================
    // Additional: Realistic data scenarios
    // ========================================================================

    @Test
    fun `parse realistic H264 keyframe with config`() {
        // Simulate a typical H.264 keyframe access unit:
        // AUD + SPS + PPS + IDR
        val audPayload = byteArrayOf(0x09, 0xF0.toByte())
        val spsPayload = byteArrayOf(0x67, 0x64, 0x00, 0x28, 0xAC.toByte(), 0xD9.toByte(),
            0x40, 0x78, 0x02, 0x27, 0xE5.toByte(), 0xC0.toByte())
        val ppsPayload = byteArrayOf(0x68, 0xEB.toByte(), 0xE3.toByte(), 0xCB.toByte(), 0x22, 0xC0.toByte())
        val idrPayload = byteArrayOf(0x65, 0xB8.toByte(), 0x00, 0x04, 0x00, 0x00, 0x11, 0xFF.toByte(),
            0xE0.toByte(), 0x40)

        val data = startCode4 + audPayload +
                startCode4 + spsPayload +
                startCode4 + ppsPayload +
                startCode4 + idrPayload

        val nalUnits = h264Parser.parse(data)

        assertEquals(4, nalUnits.size)
        assertEquals(H264NalType.AUD, nalUnits[0].type)
        assertEquals(H264NalType.SPS, nalUnits[1].type)
        assertEquals(H264NalType.PPS, nalUnits[2].type)
        assertEquals(H264NalType.SLICE_IDR, nalUnits[3].type)

        assertTrue(h264Parser.hasConfigData())

        // Verify SPS and PPS payloads match
        assertArrayEquals(spsPayload, h264Parser.sps)
        assertArrayEquals(ppsPayload, h264Parser.pps)
    }

    @Test
    fun `parse realistic HEVC keyframe with config`() {
        // Simulate a typical H.265 keyframe access unit:
        // AUD + VPS + SPS + PPS + IDR_W_RADL
        // AUD: type=35, header byte = (35 << 1) = 0x46
        val audPayload = byteArrayOf(0x46, 0x01, 0x50)
        val vpsPayload = byteArrayOf(0x40, 0x01, 0x0C, 0x01, 0xFF.toByte(), 0xFF.toByte())
        val spsPayload = byteArrayOf(0x42, 0x01, 0x01, 0x01, 0x60, 0x00, 0x00, 0x03)
        val ppsPayload = byteArrayOf(0x44, 0x01, 0xC1.toByte(), 0x72, 0xB4.toByte())
        val idrPayload = byteArrayOf(0x26, 0x01, 0xAF.toByte(), 0x08, 0x44, 0x00, 0x00, 0x03)

        val data = startCode4 + audPayload +
                startCode4 + vpsPayload +
                startCode4 + spsPayload +
                startCode4 + ppsPayload +
                startCode4 + idrPayload

        val nalUnits = hevcParser.parse(data)

        assertEquals(5, nalUnits.size)
        assertEquals(H265NalType.AUD, nalUnits[0].type)
        assertEquals(H265NalType.VPS, nalUnits[1].type)
        assertEquals(H265NalType.SPS, nalUnits[2].type)
        assertEquals(H265NalType.PPS, nalUnits[3].type)
        assertEquals(H265NalType.IDR_W_RADL, nalUnits[4].type)

        assertTrue(hevcParser.hasConfigData())
        assertNotNull(hevcParser.getConfigData())
    }

    @Test
    fun `round trip builder then parse preserves data integrity`() {
        val sps = byteArrayOf(0x67, 0x64, 0x00, 0x28, 0xAC.toByte())
        val pps = byteArrayOf(0x68, 0xEB.toByte(), 0xE3.toByte())
        val idr = byteArrayOf(0x65, 0xB8.toByte(), 0x00, 0x04)

        val built = NalUnitBuilder.createKeyFrameWithConfig(sps, pps, idr)
        val parsed = h264Parser.parse(built)

        assertEquals(3, parsed.size)
        assertArrayEquals(sps, parsed[0].payload)
        assertArrayEquals(pps, parsed[1].payload)
        assertArrayEquals(idr, parsed[2].payload)

        // Verify config data round trips
        val configData = h264Parser.getConfigData()!!
        val configParser = NalUnitParser(isHevc = false)
        val configNals = configParser.parse(configData)
        assertEquals(2, configNals.size)
        assertArrayEquals(sps, configNals[0].payload)
        assertArrayEquals(pps, configNals[1].payload)
    }

    @Test
    fun `HEVC round trip builder then parse preserves data integrity`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01, 0x60)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte())
        val idr = byteArrayOf(0x26, 0x01, 0xAF.toByte())

        val built = NalUnitBuilder.createHevcKeyFrameWithConfig(vps, sps, pps, idr)
        val parsed = hevcParser.parse(built)

        assertEquals(4, parsed.size)
        assertArrayEquals(vps, parsed[0].payload)
        assertArrayEquals(sps, parsed[1].payload)
        assertArrayEquals(pps, parsed[2].payload)
        assertArrayEquals(idr, parsed[3].payload)

        // Verify config data round trips
        val configData = hevcParser.getConfigData()!!
        val configParser = NalUnitParser(isHevc = true)
        val configNals = configParser.parse(configData)
        assertEquals(3, configNals.size)
        assertArrayEquals(vps, configNals[0].payload)
        assertArrayEquals(sps, configNals[1].payload)
        assertArrayEquals(pps, configNals[2].payload)
    }

    // ========================================================================
    // Additional: Default constructor
    // ========================================================================

    @Test
    fun `default constructor creates H264 parser`() {
        val parser = NalUnitParser()
        val data = startCode4 + byteArrayOf(0x67, 0x42) // SPS in H.264
        val nal = parser.parse(data)[0]
        assertEquals(H264NalType.SPS, nal.type)
        assertFalse(nal.isHevc)
    }

    @Test
    fun `HEVC parser marks NAL units as HEVC`() {
        val data = startCode4 + byteArrayOf(0x40, 0x01) // VPS in HEVC
        val nal = hevcParser.parse(data)[0]
        assertTrue(nal.isHevc)
    }
}
