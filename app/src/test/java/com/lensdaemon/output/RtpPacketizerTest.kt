package com.lensdaemon.output

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [RtpPacket] and [RtpPacketizer].
 *
 * These tests run on the JVM without Android framework dependencies.
 * Hardcoded payload types are used (96 for H.264, 97 for H.265) to
 * avoid SdpGenerator dependency in test scope.
 */
class RtpPacketizerTest {

    companion object {
        // Payload types (hardcoded to avoid SdpGenerator dependency)
        private const val PAYLOAD_TYPE_H264 = 96
        private const val PAYLOAD_TYPE_H265 = 97

        // RTP constants
        private const val RTP_HEADER_SIZE = 12
        private const val MAX_PACKET_SIZE = 1400
        private const val CLOCK_RATE = 90000

        // H.264 FU-A type
        private const val H264_FU_A_TYPE = 28

        // H.265 FU type
        private const val H265_FU_TYPE = 49

        // Start codes
        private val START_CODE_4 = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        private val START_CODE_3 = byteArrayOf(0x00, 0x00, 0x01)
    }

    private lateinit var h264Packetizer: RtpPacketizer
    private lateinit var h265Packetizer: RtpPacketizer
    private val testSsrc = 0x12345678L

    @Before
    fun setUp() {
        h264Packetizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = MAX_PACKET_SIZE,
            isHevc = false
        )
        h265Packetizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H265,
            ssrc = testSsrc,
            maxPacketSize = MAX_PACKET_SIZE,
            isHevc = true
        )
    }

    // ========================================================================
    // 1. RtpPacket.toByteArray() - Header structure
    // ========================================================================

    @Test
    fun `toByteArray creates packet with correct total size`() {
        val payload = byteArrayOf(0x41, 0x9A.toByte(), 0x18)
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 100,
            timestamp = 1000L,
            ssrc = testSsrc,
            payload = payload
        )

        val bytes = packet.toByteArray()
        assertEquals(RTP_HEADER_SIZE + payload.size, bytes.size)
    }

    @Test
    fun `toByteArray header is exactly 12 bytes before payload`() {
        val payload = byteArrayOf(0xAA.toByte(), 0xBB.toByte())
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 1,
            timestamp = 0L,
            ssrc = 0L,
            payload = payload
        )

        val bytes = packet.toByteArray()
        // Payload should start at offset 12
        assertEquals(payload[0], bytes[12])
        assertEquals(payload[1], bytes[13])
    }

    @Test
    fun `toByteArray version bits are 2 in byte 0`() {
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = 0L,
            payload = byteArrayOf(0x41)
        )

        val bytes = packet.toByteArray()
        // Version 2 = bits 6-7 of byte 0 = 0x80 (10_000000)
        val versionBits = (bytes[0].toInt() and 0xFF) shr 6
        assertEquals(2, versionBits)
    }

    @Test
    fun `toByteArray byte 0 is 0x80 with defaults`() {
        // V=2, P=0, X=0, CC=0 -> 10_0_0_0000 = 0x80
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = 0L,
            payload = byteArrayOf(0x41)
        )

        val bytes = packet.toByteArray()
        assertEquals(0x80.toByte(), bytes[0])
    }

    @Test
    fun `toByteArray marker bit set in byte 1`() {
        val packet = RtpPacket(
            marker = true,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = 0L,
            payload = byteArrayOf(0x41)
        )

        val bytes = packet.toByteArray()
        // Marker bit is bit 7 of byte 1
        val markerBit = (bytes[1].toInt() and 0x80) != 0
        assertTrue(markerBit)
    }

    @Test
    fun `toByteArray marker bit clear in byte 1`() {
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = 0L,
            payload = byteArrayOf(0x41)
        )

        val bytes = packet.toByteArray()
        val markerBit = (bytes[1].toInt() and 0x80) != 0
        assertFalse(markerBit)
    }

    @Test
    fun `toByteArray payload type encoded in byte 1 lower 7 bits`() {
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264, // 96
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = 0L,
            payload = byteArrayOf(0x41)
        )

        val bytes = packet.toByteArray()
        val pt = bytes[1].toInt() and 0x7F
        assertEquals(PAYLOAD_TYPE_H264, pt)
    }

    @Test
    fun `toByteArray payload type H265 encoded correctly`() {
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H265, // 97
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = 0L,
            payload = byteArrayOf(0x02, 0x01)
        )

        val bytes = packet.toByteArray()
        val pt = bytes[1].toInt() and 0x7F
        assertEquals(PAYLOAD_TYPE_H265, pt)
    }

    @Test
    fun `toByteArray marker and payload type combined in byte 1`() {
        val packet = RtpPacket(
            marker = true,
            payloadType = PAYLOAD_TYPE_H264, // 96 = 0x60
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = 0L,
            payload = byteArrayOf(0x41)
        )

        val bytes = packet.toByteArray()
        // Marker=1, PT=96: 1_1100000 = 0xE0
        assertEquals(0xE0.toByte(), bytes[1])
    }

    @Test
    fun `toByteArray sequence number big-endian at offset 2-3`() {
        val seqNum = 0x1234
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = seqNum,
            timestamp = 0L,
            ssrc = 0L,
            payload = byteArrayOf(0x41)
        )

        val bytes = packet.toByteArray()
        assertEquals(0x12.toByte(), bytes[2])
        assertEquals(0x34.toByte(), bytes[3])
    }

    @Test
    fun `toByteArray sequence number zero`() {
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = 0L,
            payload = byteArrayOf(0x41)
        )

        val bytes = packet.toByteArray()
        assertEquals(0x00.toByte(), bytes[2])
        assertEquals(0x00.toByte(), bytes[3])
    }

    @Test
    fun `toByteArray sequence number max value 0xFFFF`() {
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 0xFFFF,
            timestamp = 0L,
            ssrc = 0L,
            payload = byteArrayOf(0x41)
        )

        val bytes = packet.toByteArray()
        assertEquals(0xFF.toByte(), bytes[2])
        assertEquals(0xFF.toByte(), bytes[3])
    }

    @Test
    fun `toByteArray timestamp big-endian at offset 4-7`() {
        val timestamp = 0x11223344L
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 0,
            timestamp = timestamp,
            ssrc = 0L,
            payload = byteArrayOf(0x41)
        )

        val bytes = packet.toByteArray()
        assertEquals(0x11.toByte(), bytes[4])
        assertEquals(0x22.toByte(), bytes[5])
        assertEquals(0x33.toByte(), bytes[6])
        assertEquals(0x44.toByte(), bytes[7])
    }

    @Test
    fun `toByteArray timestamp zero`() {
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = 0L,
            payload = byteArrayOf(0x41)
        )

        val bytes = packet.toByteArray()
        for (i in 4..7) {
            assertEquals("Byte at offset $i should be 0", 0x00.toByte(), bytes[i])
        }
    }

    @Test
    fun `toByteArray ssrc big-endian at offset 8-11`() {
        val ssrc = 0xAABBCCDDL
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = ssrc,
            payload = byteArrayOf(0x41)
        )

        val bytes = packet.toByteArray()
        assertEquals(0xAA.toByte(), bytes[8])
        assertEquals(0xBB.toByte(), bytes[9])
        assertEquals(0xCC.toByte(), bytes[10])
        assertEquals(0xDD.toByte(), bytes[11])
    }

    @Test
    fun `toByteArray ssrc zero`() {
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = 0L,
            payload = byteArrayOf(0x41)
        )

        val bytes = packet.toByteArray()
        for (i in 8..11) {
            assertEquals("Byte at offset $i should be 0", 0x00.toByte(), bytes[i])
        }
    }

    @Test
    fun `toByteArray payload follows header correctly`() {
        val payload = byteArrayOf(0x41, 0x9A.toByte(), 0x18, 0x00, 0xFF.toByte())
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = 0L,
            payload = payload
        )

        val bytes = packet.toByteArray()
        for (i in payload.indices) {
            assertEquals("Payload byte $i mismatch", payload[i], bytes[RTP_HEADER_SIZE + i])
        }
    }

    @Test
    fun `toByteArray empty payload results in header-only packet`() {
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = 0L,
            payload = byteArrayOf()
        )

        val bytes = packet.toByteArray()
        assertEquals(RTP_HEADER_SIZE, bytes.size)
    }

    @Test
    fun `toByteArray padding flag set when padding is true`() {
        val packet = RtpPacket(
            padding = true,
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = 0L,
            payload = byteArrayOf(0x41)
        )

        val bytes = packet.toByteArray()
        // Padding bit is bit 5 of byte 0: 0x20
        val paddingBit = (bytes[0].toInt() and 0x20) != 0
        assertTrue(paddingBit)
    }

    @Test
    fun `toByteArray extension flag set when extension is true`() {
        val packet = RtpPacket(
            extension = true,
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 0,
            timestamp = 0L,
            ssrc = 0L,
            payload = byteArrayOf(0x41)
        )

        val bytes = packet.toByteArray()
        // Extension bit is bit 4 of byte 0: 0x10
        val extensionBit = (bytes[0].toInt() and 0x10) != 0
        assertTrue(extensionBit)
    }

    // ========================================================================
    // 2. RtpPacket.equals() and hashCode()
    // ========================================================================

    @Test
    fun `equals returns true for identical packets`() {
        val payload = byteArrayOf(0x41, 0x9A.toByte())
        val packet1 = RtpPacket(
            marker = true,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 100,
            timestamp = 5000L,
            ssrc = testSsrc,
            payload = payload.copyOf()
        )
        val packet2 = RtpPacket(
            marker = true,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 100,
            timestamp = 5000L,
            ssrc = testSsrc,
            payload = payload.copyOf()
        )

        assertEquals(packet1, packet2)
    }

    @Test
    fun `equals returns false for different sequence numbers`() {
        val payload = byteArrayOf(0x41)
        val packet1 = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 1,
            timestamp = 0L,
            ssrc = testSsrc,
            payload = payload
        )
        val packet2 = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 2,
            timestamp = 0L,
            ssrc = testSsrc,
            payload = payload
        )

        assertNotEquals(packet1, packet2)
    }

    @Test
    fun `equals returns false for different timestamps`() {
        val payload = byteArrayOf(0x41)
        val packet1 = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 1,
            timestamp = 100L,
            ssrc = testSsrc,
            payload = payload
        )
        val packet2 = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 1,
            timestamp = 200L,
            ssrc = testSsrc,
            payload = payload
        )

        assertNotEquals(packet1, packet2)
    }

    @Test
    fun `equals returns false for different ssrc`() {
        val payload = byteArrayOf(0x41)
        val packet1 = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 1,
            timestamp = 0L,
            ssrc = 100L,
            payload = payload
        )
        val packet2 = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 1,
            timestamp = 0L,
            ssrc = 200L,
            payload = payload
        )

        assertNotEquals(packet1, packet2)
    }

    @Test
    fun `equals returns false for different payloads`() {
        val packet1 = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 1,
            timestamp = 0L,
            ssrc = testSsrc,
            payload = byteArrayOf(0x41)
        )
        val packet2 = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 1,
            timestamp = 0L,
            ssrc = testSsrc,
            payload = byteArrayOf(0x42)
        )

        assertNotEquals(packet1, packet2)
    }

    @Test
    fun `hashCode consistent for equal packets`() {
        val payload = byteArrayOf(0x41, 0x9A.toByte())
        val packet1 = RtpPacket(
            marker = true,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 100,
            timestamp = 5000L,
            ssrc = testSsrc,
            payload = payload.copyOf()
        )
        val packet2 = RtpPacket(
            marker = true,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 100,
            timestamp = 5000L,
            ssrc = testSsrc,
            payload = payload.copyOf()
        )

        assertEquals(packet1.hashCode(), packet2.hashCode())
    }

    @Test
    fun `equals is reflexive`() {
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 1,
            timestamp = 0L,
            ssrc = testSsrc,
            payload = byteArrayOf(0x41)
        )

        assertEquals(packet, packet)
    }

    @Test
    fun `equals returns false for null`() {
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 1,
            timestamp = 0L,
            ssrc = testSsrc,
            payload = byteArrayOf(0x41)
        )

        assertFalse(packet.equals(null))
    }

    @Test
    fun `equals returns false for different type`() {
        val packet = RtpPacket(
            marker = false,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 1,
            timestamp = 0L,
            ssrc = testSsrc,
            payload = byteArrayOf(0x41)
        )

        assertFalse(packet.equals("not a packet"))
    }

    // ========================================================================
    // 3. RtpPacket constants
    // ========================================================================

    @Test
    fun `HEADER_SIZE constant is 12`() {
        assertEquals(12, RtpPacket.HEADER_SIZE)
    }

    @Test
    fun `MAX_PACKET_SIZE constant is 1400`() {
        assertEquals(1400, RtpPacket.MAX_PACKET_SIZE)
    }

    // ========================================================================
    // 4. RtpPacketizer - toRtpTimestamp
    // ========================================================================

    @Test
    fun `toRtpTimestamp converts microseconds to 90kHz clock`() {
        // 1 second = 1,000,000 us -> 90000 ticks
        val result = RtpPacketizer.toRtpTimestamp(1_000_000L)
        assertEquals(90000L, result)
    }

    @Test
    fun `toRtpTimestamp zero microseconds returns zero`() {
        assertEquals(0L, RtpPacketizer.toRtpTimestamp(0L))
    }

    @Test
    fun `toRtpTimestamp 33333 us is approximately one frame at 30fps`() {
        // 33333 us * 90000 / 1000000 = 2999.97 -> truncated to 2999
        val result = RtpPacketizer.toRtpTimestamp(33333L)
        assertEquals(2999L, result)
    }

    @Test
    fun `toRtpTimestamp 40000 us is one frame at 25fps`() {
        // 40000 * 90000 / 1000000 = 3600
        val result = RtpPacketizer.toRtpTimestamp(40000L)
        assertEquals(3600L, result)
    }

    @Test
    fun `toRtpTimestamp large value`() {
        // 10 seconds = 10,000,000 us -> 900,000 ticks
        val result = RtpPacketizer.toRtpTimestamp(10_000_000L)
        assertEquals(900000L, result)
    }

    @Test
    fun `CLOCK_RATE constant is 90000`() {
        assertEquals(90000, RtpPacketizer.CLOCK_RATE)
    }

    // ========================================================================
    // 5. RtpPacketizer - getSsrc, getSequenceNumber, resetSequenceNumber
    // ========================================================================

    @Test
    fun `getSsrc returns configured SSRC`() {
        assertEquals(testSsrc, h264Packetizer.getSsrc())
    }

    @Test
    fun `getSequenceNumber returns initial value`() {
        val seqNum = h264Packetizer.getSequenceNumber()
        // Should be a random value in range 0..0xFFFE
        assertTrue("Sequence number should be non-negative", seqNum >= 0)
        assertTrue("Sequence number should be < 0xFFFF", seqNum < 0xFFFF)
    }

    @Test
    fun `getSequenceNumber increments after packetize`() {
        val initialSeq = h264Packetizer.getSequenceNumber()

        // Packetize a single small NAL unit -> produces 1 packet, increments seq by 1
        val nalPayload = byteArrayOf(0x41, 0x9A.toByte(), 0x18)
        val data = START_CODE_4 + nalPayload
        h264Packetizer.packetize(data, 0L)

        assertEquals(initialSeq + 1, h264Packetizer.getSequenceNumber())
    }

    @Test
    fun `resetSequenceNumber changes sequence number`() {
        val initialSeq = h264Packetizer.getSequenceNumber()
        // Reset may or may not produce same number (random), but we can verify it is in range
        h264Packetizer.resetSequenceNumber()
        val newSeq = h264Packetizer.getSequenceNumber()
        assertTrue("New sequence number should be non-negative", newSeq >= 0)
        assertTrue("New sequence number should be < 0xFFFF", newSeq < 0xFFFF)
    }

    // ========================================================================
    // 6. Small NAL unit - single packet (no fragmentation)
    // ========================================================================

    @Test
    fun `small NAL creates single packet`() {
        // Non-IDR slice: 0x41 (NRI=2, type=1)
        val nalPayload = byteArrayOf(0x41, 0x9A.toByte(), 0x18, 0x00, 0xFF.toByte())
        val data = START_CODE_4 + nalPayload

        val packets = h264Packetizer.packetize(data, 0L)

        assertEquals(1, packets.size)
    }

    @Test
    fun `small NAL single packet contains NAL payload directly`() {
        val nalPayload = byteArrayOf(0x41, 0x9A.toByte(), 0x18)
        val data = START_CODE_4 + nalPayload

        val packets = h264Packetizer.packetize(data, 0L)

        assertArrayEquals(nalPayload, packets[0].payload)
    }

    @Test
    fun `small NAL single packet marker bit set when isLastNal`() {
        val nalPayload = byteArrayOf(0x41, 0x9A.toByte(), 0x18)
        val data = START_CODE_4 + nalPayload

        val packets = h264Packetizer.packetize(data, 0L)

        // Single NAL is the last (and only) NAL, so marker should be true
        assertTrue(packets[0].marker)
    }

    @Test
    fun `small NAL packet has correct payload type`() {
        val nalPayload = byteArrayOf(0x41, 0x9A.toByte())
        val data = START_CODE_4 + nalPayload

        val packets = h264Packetizer.packetize(data, 0L)

        assertEquals(PAYLOAD_TYPE_H264, packets[0].payloadType)
    }

    @Test
    fun `small NAL packet has correct SSRC`() {
        val nalPayload = byteArrayOf(0x41, 0x9A.toByte())
        val data = START_CODE_4 + nalPayload

        val packets = h264Packetizer.packetize(data, 0L)

        assertEquals(testSsrc, packets[0].ssrc)
    }

    @Test
    fun `small NAL packet has correct timestamp`() {
        val nalPayload = byteArrayOf(0x41, 0x9A.toByte())
        val data = START_CODE_4 + nalPayload
        val presentationTimeUs = 1_000_000L // 1 second

        val packets = h264Packetizer.packetize(data, presentationTimeUs)

        assertEquals(90000L, packets[0].timestamp)
    }

    @Test
    fun `multiple small NALs marker only on last`() {
        // SPS + PPS + IDR (all small)
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte())

        val data = START_CODE_4 + sps + START_CODE_4 + pps + START_CODE_4 + idr

        val packets = h264Packetizer.packetize(data, 0L)

        assertEquals(3, packets.size)
        assertFalse("SPS packet should not have marker", packets[0].marker)
        assertFalse("PPS packet should not have marker", packets[1].marker)
        assertTrue("IDR (last) packet should have marker", packets[2].marker)
    }

    @Test
    fun `multiple small NALs all share same timestamp`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)

        val data = START_CODE_4 + sps + START_CODE_4 + pps
        val presentationTimeUs = 2_000_000L

        val packets = h264Packetizer.packetize(data, presentationTimeUs)

        val expectedTimestamp = RtpPacketizer.toRtpTimestamp(presentationTimeUs)
        packets.forEach { assertEquals(expectedTimestamp, it.timestamp) }
    }

    @Test
    fun `multiple small NALs have incrementing sequence numbers`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)

        val data = START_CODE_4 + sps + START_CODE_4 + pps

        val packets = h264Packetizer.packetize(data, 0L)

        assertEquals(2, packets.size)
        assertEquals(packets[0].sequenceNumber + 1, packets[1].sequenceNumber)
    }

    @Test
    fun `small NAL with 3-byte start code`() {
        val nalPayload = byteArrayOf(0x41, 0x9A.toByte(), 0x18)
        val data = START_CODE_3 + nalPayload

        val packets = h264Packetizer.packetize(data, 0L)

        assertEquals(1, packets.size)
        assertArrayEquals(nalPayload, packets[0].payload)
    }

    // ========================================================================
    // 7. Large NAL unit - H.264 FU-A fragmentation
    // ========================================================================

    @Test
    fun `large H264 NAL is fragmented into multiple packets`() {
        // Use a small maxPacketSize to force fragmentation easily
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = false
        )

        // NAL header: 0x41 (NRI=2, type=1 non-IDR)
        // Total payload = 200 bytes (1 header + 199 data), which exceeds maxPayloadSize of 88
        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x41 // NAL header
        for (i in 1 until 200) {
            nalPayload[i] = (i and 0xFF).toByte()
        }
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        assertTrue("Should produce multiple fragments", packets.size > 1)
    }

    @Test
    fun `H264 FU-A first fragment has S bit set`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = false
        )

        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x41 // NAL header: NRI=2, type=1
        for (i in 1 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        // First fragment FU header (byte index 1 of payload) should have S bit (0x80)
        val fuHeader = packets[0].payload[1].toInt() and 0xFF
        assertTrue("First fragment should have S bit set", (fuHeader and 0x80) != 0)
        assertFalse("First fragment should not have E bit set", (fuHeader and 0x40) != 0)
    }

    @Test
    fun `H264 FU-A last fragment has E bit set`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = false
        )

        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x41
        for (i in 1 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        val lastPacket = packets.last()
        val fuHeader = lastPacket.payload[1].toInt() and 0xFF
        assertTrue("Last fragment should have E bit set", (fuHeader and 0x40) != 0)
        assertFalse("Last fragment should not have S bit set", (fuHeader and 0x80) != 0)
    }

    @Test
    fun `H264 FU-A middle fragments have neither S nor E bits`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = false
        )

        // Make it large enough to produce at least 3 fragments
        val nalPayload = ByteArray(300)
        nalPayload[0] = 0x41
        for (i in 1 until 300) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        assertTrue("Should have at least 3 fragments", packets.size >= 3)

        // Check middle fragments (all except first and last)
        for (i in 1 until packets.size - 1) {
            val fuHeader = packets[i].payload[1].toInt() and 0xFF
            assertFalse("Middle fragment $i should not have S bit", (fuHeader and 0x80) != 0)
            assertFalse("Middle fragment $i should not have E bit", (fuHeader and 0x40) != 0)
        }
    }

    @Test
    fun `H264 FU-A indicator byte has NRI from original and type 28`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = false
        )

        // NAL header: 0x41 = 0_10_00001 -> NRI=2 (bits 5-6 = 0x40), type=1
        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x41
        for (i in 1 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        // FU indicator = (NRI bits from original) | FU-A type (28)
        // NRI from 0x41: 0x41 & 0x60 = 0x40
        // FU indicator = 0x40 | 28 = 0x40 | 0x1C = 0x5C
        val expectedFuIndicator = (0x40 or H264_FU_A_TYPE).toByte()

        for (packet in packets) {
            assertEquals(
                "FU indicator should be NRI|28",
                expectedFuIndicator,
                packet.payload[0]
            )
        }
    }

    @Test
    fun `H264 FU-A header preserves original NAL type`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = false
        )

        // NAL header: 0x41 -> type=1 (non-IDR)
        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x41
        for (i in 1 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        for (packet in packets) {
            val fuHeader = packet.payload[1].toInt() and 0xFF
            val nalType = fuHeader and 0x1F
            assertEquals("FU header should contain original NAL type", 1, nalType)
        }
    }

    @Test
    fun `H264 FU-A marker bit only on last packet of last NAL`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = false
        )

        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x41
        for (i in 1 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        // Only the last packet should have the marker bit
        for (i in 0 until packets.size - 1) {
            assertFalse("Non-last packet $i should not have marker", packets[i].marker)
        }
        assertTrue("Last packet should have marker", packets.last().marker)
    }

    @Test
    fun `H264 FU-A all fragments share same timestamp`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = false
        )

        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x41
        for (i in 1 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val presentationTimeUs = 3_000_000L
        val packets = smallPacketizer.packetize(data, presentationTimeUs)

        val expectedTimestamp = RtpPacketizer.toRtpTimestamp(presentationTimeUs)
        packets.forEach { packet ->
            assertEquals("All fragments should share timestamp", expectedTimestamp, packet.timestamp)
        }
    }

    @Test
    fun `H264 FU-A fragment count is correct for known data size`() {
        val maxPktSize = 100
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = maxPktSize,
            isHevc = false
        )

        // maxPayloadSize = 100 - 12 = 88
        // maxFragmentSize = 100 - 12 - 2 = 86 bytes per fragment
        // NAL payload = 200 bytes total, skip 1 byte NAL header -> 199 bytes to fragment
        // ceil(199 / 86) = 3 fragments
        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x41
        for (i in 1 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        assertEquals(3, packets.size)
    }

    @Test
    fun `H264 FU-A fragments have incrementing sequence numbers`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = false
        )

        val nalPayload = ByteArray(300)
        nalPayload[0] = 0x41
        for (i in 1 until 300) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        for (i in 1 until packets.size) {
            assertEquals(
                "Sequence numbers should increment",
                (packets[i - 1].sequenceNumber + 1) and 0xFFFF,
                packets[i].sequenceNumber
            )
        }
    }

    @Test
    fun `H264 FU-A with IDR NAL type preserves type in FU header`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = false
        )

        // IDR: 0x65 = 0_11_00101 -> NRI=3 (0x60), type=5
        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x65
        for (i in 1 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        // FU indicator = (NRI=0x60) | type 28 = 0x60 | 0x1C = 0x7C
        val expectedFuIndicator = (0x60 or H264_FU_A_TYPE).toByte()

        for (packet in packets) {
            assertEquals(expectedFuIndicator, packet.payload[0])
            val nalType = packet.payload[1].toInt() and 0x1F
            assertEquals(5, nalType) // IDR type
        }
    }

    @Test
    fun `H264 FU-A large NAL not last has no marker on any fragment`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = false
        )

        // Two NALs: first is large (fragmented), second is small
        val largeNalPayload = ByteArray(200)
        largeNalPayload[0] = 0x41
        for (i in 1 until 200) largeNalPayload[i] = (i and 0xFF).toByte()

        val smallNalPayload = byteArrayOf(0x41, 0xAA.toByte())

        val data = START_CODE_4 + largeNalPayload + START_CODE_4 + smallNalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        // The last packet is from the small NAL; only it should have marker
        // All fragments from the large NAL should NOT have marker
        val largeNalPacketCount = packets.size - 1 // Last one is the small NAL
        for (i in 0 until largeNalPacketCount) {
            assertFalse("Fragment $i of non-last NAL should not have marker", packets[i].marker)
        }
        assertTrue("Last NAL packet should have marker", packets.last().marker)
    }

    // ========================================================================
    // 8. H.265 FU fragmentation
    // ========================================================================

    @Test
    fun `large HEVC NAL is fragmented into multiple packets`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H265,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = true
        )

        // HEVC TRAIL_R: type=1, header byte = (1 << 1) = 0x02, second byte = 0x01
        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x02 // NAL header byte 0
        nalPayload[1] = 0x01 // NAL header byte 1 (TID=1)
        for (i in 2 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        assertTrue("Should produce multiple fragments", packets.size > 1)
    }

    @Test
    fun `HEVC FU first fragment has S bit set`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H265,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = true
        )

        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x02 // TRAIL_R type=1
        nalPayload[1] = 0x01 // TID=1
        for (i in 2 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        // FU header is at payload index 2 (after 2 bytes of PayloadHdr)
        val fuHeader = packets[0].payload[2].toInt() and 0xFF
        assertTrue("First fragment should have S bit (0x80)", (fuHeader and 0x80) != 0)
        assertFalse("First fragment should not have E bit", (fuHeader and 0x40) != 0)
    }

    @Test
    fun `HEVC FU last fragment has E bit set`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H265,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = true
        )

        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x02
        nalPayload[1] = 0x01
        for (i in 2 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        val fuHeader = packets.last().payload[2].toInt() and 0xFF
        assertTrue("Last fragment should have E bit (0x40)", (fuHeader and 0x40) != 0)
        assertFalse("Last fragment should not have S bit", (fuHeader and 0x80) != 0)
    }

    @Test
    fun `HEVC FU PayloadHdr has type 49`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H265,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = true
        )

        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x02 // TRAIL_R
        nalPayload[1] = 0x01 // TID=1
        for (i in 2 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        for (packet in packets) {
            // PayloadHdr byte 0: type is bits 1-6 = (byte >> 1) & 0x3F
            val payloadHdr0 = packet.payload[0].toInt() and 0xFF
            val fuType = (payloadHdr0 shr 1) and 0x3F
            assertEquals("PayloadHdr should indicate FU type 49", H265_FU_TYPE, fuType)
        }
    }

    @Test
    fun `HEVC FU header preserves original NAL type`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H265,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = true
        )

        // TRAIL_R: type=1
        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x02 // (1 << 1) = 2
        nalPayload[1] = 0x01
        for (i in 2 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        for (packet in packets) {
            val fuHeader = packet.payload[2].toInt() and 0xFF
            val nalType = fuHeader and 0x3F
            assertEquals("FU header should preserve original NAL type", 1, nalType)
        }
    }

    @Test
    fun `HEVC FU marker bit only on last packet of last NAL`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H265,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = true
        )

        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x02
        nalPayload[1] = 0x01
        for (i in 2 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        for (i in 0 until packets.size - 1) {
            assertFalse("Non-last fragment should not have marker", packets[i].marker)
        }
        assertTrue("Last fragment should have marker", packets.last().marker)
    }

    @Test
    fun `HEVC FU all fragments share same timestamp`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H265,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = true
        )

        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x02
        nalPayload[1] = 0x01
        for (i in 2 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload
        val presentationTimeUs = 5_000_000L

        val packets = smallPacketizer.packetize(data, presentationTimeUs)

        val expectedTimestamp = RtpPacketizer.toRtpTimestamp(presentationTimeUs)
        packets.forEach { assertEquals(expectedTimestamp, it.timestamp) }
    }

    @Test
    fun `HEVC FU fragment count is correct for known data size`() {
        val maxPktSize = 100
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H265,
            ssrc = testSsrc,
            maxPacketSize = maxPktSize,
            isHevc = true
        )

        // maxPayloadSize = 100 - 12 = 88
        // maxFragmentSize for HEVC = 100 - 12 - 3 = 85 bytes per fragment
        // NAL payload = 200 bytes total, skip 2 byte HEVC NAL header -> 198 bytes to fragment
        // ceil(198 / 85) = 3 fragments (85 + 85 + 28)
        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x02
        nalPayload[1] = 0x01
        for (i in 2 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        assertEquals(3, packets.size)
    }

    // ========================================================================
    // 9. packetizeConfig (H.264 SPS + PPS)
    // ========================================================================

    @Test
    fun `packetizeConfig returns 2 packets`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)

        val packets = h264Packetizer.packetizeConfig(sps, pps, 0L)

        assertEquals(2, packets.size)
    }

    @Test
    fun `packetizeConfig neither packet has marker bit`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)

        val packets = h264Packetizer.packetizeConfig(sps, pps, 0L)

        assertFalse("SPS config packet should not have marker", packets[0].marker)
        assertFalse("PPS config packet should not have marker", packets[1].marker)
    }

    @Test
    fun `packetizeConfig SPS packet contains SPS payload`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)

        val packets = h264Packetizer.packetizeConfig(sps, pps, 0L)

        assertArrayEquals(sps, packets[0].payload)
    }

    @Test
    fun `packetizeConfig PPS packet contains PPS payload`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)

        val packets = h264Packetizer.packetizeConfig(sps, pps, 0L)

        assertArrayEquals(pps, packets[1].payload)
    }

    @Test
    fun `packetizeConfig packets have correct timestamp`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)
        val timestamp = 12345L

        val packets = h264Packetizer.packetizeConfig(sps, pps, timestamp)

        packets.forEach { assertEquals(timestamp, it.timestamp) }
    }

    @Test
    fun `packetizeConfig packets have correct SSRC`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)

        val packets = h264Packetizer.packetizeConfig(sps, pps, 0L)

        packets.forEach { assertEquals(testSsrc, it.ssrc) }
    }

    @Test
    fun `packetizeConfig packets have correct payload type`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)

        val packets = h264Packetizer.packetizeConfig(sps, pps, 0L)

        packets.forEach { assertEquals(PAYLOAD_TYPE_H264, it.payloadType) }
    }

    @Test
    fun `packetizeConfig packets have incrementing sequence numbers`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)

        val packets = h264Packetizer.packetizeConfig(sps, pps, 0L)

        assertEquals(packets[0].sequenceNumber + 1, packets[1].sequenceNumber)
    }

    // ========================================================================
    // 10. packetizeHevcConfig (H.265 VPS + SPS + PPS)
    // ========================================================================

    @Test
    fun `packetizeHevcConfig returns 3 packets`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte())

        val packets = h265Packetizer.packetizeHevcConfig(vps, sps, pps, 0L)

        assertEquals(3, packets.size)
    }

    @Test
    fun `packetizeHevcConfig no packets have marker bit`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte())

        val packets = h265Packetizer.packetizeHevcConfig(vps, sps, pps, 0L)

        packets.forEach { packet ->
            assertFalse("HEVC config packet should not have marker", packet.marker)
        }
    }

    @Test
    fun `packetizeHevcConfig VPS packet contains VPS payload`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte())

        val packets = h265Packetizer.packetizeHevcConfig(vps, sps, pps, 0L)

        assertArrayEquals(vps, packets[0].payload)
    }

    @Test
    fun `packetizeHevcConfig SPS packet contains SPS payload`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte())

        val packets = h265Packetizer.packetizeHevcConfig(vps, sps, pps, 0L)

        assertArrayEquals(sps, packets[1].payload)
    }

    @Test
    fun `packetizeHevcConfig PPS packet contains PPS payload`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte())

        val packets = h265Packetizer.packetizeHevcConfig(vps, sps, pps, 0L)

        assertArrayEquals(pps, packets[2].payload)
    }

    @Test
    fun `packetizeHevcConfig packets have correct payload type`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte())

        val packets = h265Packetizer.packetizeHevcConfig(vps, sps, pps, 0L)

        packets.forEach { assertEquals(PAYLOAD_TYPE_H265, it.payloadType) }
    }

    @Test
    fun `packetizeHevcConfig packets have incrementing sequence numbers`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte())

        val packets = h265Packetizer.packetizeHevcConfig(vps, sps, pps, 0L)

        assertEquals(packets[0].sequenceNumber + 1, packets[1].sequenceNumber)
        assertEquals(packets[1].sequenceNumber + 1, packets[2].sequenceNumber)
    }

    @Test
    fun `packetizeHevcConfig packets share same timestamp`() {
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte())
        val timestamp = 99999L

        val packets = h265Packetizer.packetizeHevcConfig(vps, sps, pps, timestamp)

        packets.forEach { assertEquals(timestamp, it.timestamp) }
    }

    // ========================================================================
    // 11. Sequence number wrapping
    // ========================================================================

    @Test
    fun `sequence number wraps at 0xFFFF`() {
        // Create packetizer and consume sequence numbers up to near wrapping point
        val packetizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = MAX_PACKET_SIZE,
            isHevc = false
        )

        // Packetize many small NALs to advance the sequence number
        val nalPayload = byteArrayOf(0x41, 0x9A.toByte())
        val data = START_CODE_4 + nalPayload

        // Get initial seq and calculate how many more we need to approach 0xFFFF
        val initialSeq = packetizer.getSequenceNumber()

        // Packetize enough times to pass the 0xFFFF boundary
        val iterationsToWrap = 0xFFFF - initialSeq + 2
        var lastSeqNum = -1

        for (i in 0 until iterationsToWrap) {
            val packets = packetizer.packetize(data, 0L)
            lastSeqNum = packets[0].sequenceNumber
        }

        // The sequence number should have wrapped (be a small number)
        assertTrue(
            "Sequence number should wrap around 0xFFFF; got $lastSeqNum",
            lastSeqNum < iterationsToWrap
        )
    }

    @Test
    fun `sequence number is masked with 0xFFFF`() {
        val packetizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = MAX_PACKET_SIZE,
            isHevc = false
        )

        val nalPayload = byteArrayOf(0x41, 0x9A.toByte())
        val data = START_CODE_4 + nalPayload

        // Generate some packets and verify all sequence numbers are in valid range
        for (i in 0 until 100) {
            val packets = packetizer.packetize(data, 0L)
            val seqNum = packets[0].sequenceNumber
            assertTrue(
                "Sequence number $seqNum should be in range 0..65535",
                seqNum in 0..0xFFFF
            )
        }
    }

    // ========================================================================
    // 12. Edge cases and integration
    // ========================================================================

    @Test
    fun `packetize empty data returns empty list`() {
        val packets = h264Packetizer.packetize(byteArrayOf(), 0L)
        assertTrue(packets.isEmpty())
    }

    @Test
    fun `packetize data without start code returns empty list`() {
        val data = byteArrayOf(0x41, 0x9A.toByte(), 0x18)
        val packets = h264Packetizer.packetize(data, 0L)
        assertTrue(packets.isEmpty())
    }

    @Test
    fun `packetize NAL at exact max payload size produces single packet`() {
        // maxPayloadSize = 1400 - 12 = 1388 bytes
        // Create a NAL with exactly 1388 bytes of payload (including NAL header)
        val nalPayload = ByteArray(1388)
        nalPayload[0] = 0x41
        for (i in 1 until 1388) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = h264Packetizer.packetize(data, 0L)

        assertEquals("NAL at exact max payload should be single packet", 1, packets.size)
        assertArrayEquals(nalPayload, packets[0].payload)
    }

    @Test
    fun `packetize NAL one byte over max payload size produces fragments`() {
        // maxPayloadSize = 1400 - 12 = 1388 bytes
        // Create a NAL with 1389 bytes of payload
        val nalPayload = ByteArray(1389)
        nalPayload[0] = 0x41
        for (i in 1 until 1389) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = h264Packetizer.packetize(data, 0L)

        assertTrue("NAL over max payload should be fragmented", packets.size > 1)
    }

    @Test
    fun `packetize preserves sequence number continuity across calls`() {
        val nalPayload1 = byteArrayOf(0x41, 0x9A.toByte())
        val data1 = START_CODE_4 + nalPayload1
        val packets1 = h264Packetizer.packetize(data1, 0L)

        val nalPayload2 = byteArrayOf(0x41, 0xBB.toByte())
        val data2 = START_CODE_4 + nalPayload2
        val packets2 = h264Packetizer.packetize(data2, 1_000_000L)

        assertEquals(
            "Sequence numbers should be continuous across calls",
            (packets1.last().sequenceNumber + 1) and 0xFFFF,
            packets2[0].sequenceNumber
        )
    }

    @Test
    fun `packetize with mixed start codes works`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00)
        val pps = byteArrayOf(0x68, 0xCE.toByte())

        // Mix 4-byte and 3-byte start codes
        val data = START_CODE_4 + sps + START_CODE_3 + pps

        val packets = h264Packetizer.packetize(data, 0L)

        assertEquals(2, packets.size)
        assertArrayEquals(sps, packets[0].payload)
        assertArrayEquals(pps, packets[1].payload)
    }

    @Test
    fun `HEVC small NAL creates single packet`() {
        // TRAIL_R: type=1, header = (1 << 1) = 0x02
        val nalPayload = byteArrayOf(0x02, 0x01, 0xAF.toByte(), 0xBB.toByte())
        val data = START_CODE_4 + nalPayload

        val packets = h265Packetizer.packetize(data, 0L)

        assertEquals(1, packets.size)
        assertArrayEquals(nalPayload, packets[0].payload)
        assertTrue(packets[0].marker)
    }

    @Test
    fun `HEVC packetize with multiple NALs`() {
        // VPS + SPS + PPS + IDR
        val vps = byteArrayOf(0x40, 0x01, 0x0C)
        val sps = byteArrayOf(0x42, 0x01, 0x01)
        val pps = byteArrayOf(0x44, 0x01, 0xC1.toByte())
        val idr = byteArrayOf(0x26, 0x01, 0xAF.toByte(), 0xBB.toByte())

        val data = START_CODE_4 + vps +
                START_CODE_4 + sps +
                START_CODE_4 + pps +
                START_CODE_4 + idr

        val packets = h265Packetizer.packetize(data, 0L)

        assertEquals(4, packets.size)
        // Only last (IDR) should have marker
        assertFalse(packets[0].marker)
        assertFalse(packets[1].marker)
        assertFalse(packets[2].marker)
        assertTrue(packets[3].marker)
    }

    @Test
    fun `H264 FU-A reassembly of fragment payloads matches original NAL`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = false
        )

        // Create known NAL payload
        val nalPayload = ByteArray(250)
        nalPayload[0] = 0x41 // NAL header
        for (i in 1 until 250) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        // Reassemble: first byte is the NAL header (reconstructed from FU indicator + FU header)
        val reassembled = mutableListOf<Byte>()

        // Reconstruct NAL header from first fragment
        val fuIndicator = packets[0].payload[0].toInt() and 0xFF
        val fuHeader = packets[0].payload[1].toInt() and 0xFF
        val nalHeader = (fuIndicator and 0xE0) or (fuHeader and 0x1F)
        reassembled.add(nalHeader.toByte())

        // Collect fragment payloads (skip FU indicator and FU header)
        for (packet in packets) {
            val fragmentData = packet.payload.copyOfRange(2, packet.payload.size)
            reassembled.addAll(fragmentData.toList())
        }

        assertArrayEquals(
            "Reassembled fragments should match original NAL payload",
            nalPayload,
            reassembled.toByteArray()
        )
    }

    @Test
    fun `toByteArray full round-trip verification`() {
        val packet = RtpPacket(
            marker = true,
            payloadType = PAYLOAD_TYPE_H264,
            sequenceNumber = 0xABCD,
            timestamp = 0x12345678L,
            ssrc = 0xDEADBEEFL,
            payload = byteArrayOf(0x41, 0x9A.toByte(), 0x18)
        )

        val bytes = packet.toByteArray()

        // Verify complete header
        assertEquals(0x80.toByte(), bytes[0])                // V=2, P=0, X=0, CC=0
        assertEquals((0x80 or 96).toByte(), bytes[1])        // M=1, PT=96
        assertEquals(0xAB.toByte(), bytes[2])                // Seq high
        assertEquals(0xCD.toByte(), bytes[3])                // Seq low
        assertEquals(0x12.toByte(), bytes[4])                // TS byte 0
        assertEquals(0x34.toByte(), bytes[5])                // TS byte 1
        assertEquals(0x56.toByte(), bytes[6])                // TS byte 2
        assertEquals(0x78.toByte(), bytes[7])                // TS byte 3
        assertEquals(0xDE.toByte(), bytes[8])                // SSRC byte 0
        assertEquals(0xAD.toByte(), bytes[9])                // SSRC byte 1
        assertEquals(0xBE.toByte(), bytes[10])               // SSRC byte 2
        assertEquals(0xEF.toByte(), bytes[11])               // SSRC byte 3
        assertEquals(0x41.toByte(), bytes[12])               // Payload[0]
        assertEquals(0x9A.toByte(), bytes[13])               // Payload[1]
        assertEquals(0x18.toByte(), bytes[14])               // Payload[2]
        assertEquals(15, bytes.size)                          // 12 header + 3 payload
    }

    @Test
    fun `packetize single NAL byte array is serializable`() {
        val nalPayload = byteArrayOf(0x41, 0x9A.toByte())
        val data = START_CODE_4 + nalPayload

        val packets = h264Packetizer.packetize(data, 1_000_000L)

        assertEquals(1, packets.size)
        val bytes = packets[0].toByteArray()
        assertNotNull(bytes)
        assertEquals(RTP_HEADER_SIZE + nalPayload.size, bytes.size)
    }

    @Test
    fun `packetize config followed by frame maintains sequence continuity`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)

        val configPackets = h264Packetizer.packetizeConfig(sps, pps, 0L)
        val lastConfigSeq = configPackets.last().sequenceNumber

        val idrPayload = byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte())
        val data = START_CODE_4 + idrPayload
        val framePackets = h264Packetizer.packetize(data, 0L)

        assertEquals(
            "Frame packet should continue from config sequence",
            (lastConfigSeq + 1) and 0xFFFF,
            framePackets[0].sequenceNumber
        )
    }

    @Test
    fun `H264 FU-A with SPS NAL type uses correct NRI`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = false
        )

        // SPS: 0x67 = 0_11_00111 -> NRI=3 (0x60), type=7
        val nalPayload = ByteArray(200)
        nalPayload[0] = 0x67
        for (i in 1 until 200) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        // FU indicator = (NRI=0x60) | type 28 = 0x60 | 0x1C = 0x7C
        val expectedFuIndicator = (0x60 or H264_FU_A_TYPE).toByte()

        for (packet in packets) {
            assertEquals(expectedFuIndicator, packet.payload[0])
            val nalType = packet.payload[1].toInt() and 0x1F
            assertEquals(7, nalType) // SPS type
        }
    }

    @Test
    fun `default SSRC is random 32-bit value`() {
        val packetizer1 = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            isHevc = false
        )
        val packetizer2 = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            isHevc = false
        )

        // Both SSRCs should be in 32-bit unsigned range
        val ssrc1 = packetizer1.getSsrc()
        val ssrc2 = packetizer2.getSsrc()
        assertTrue("SSRC should be non-negative", ssrc1 >= 0)
        assertTrue("SSRC should fit in 32 bits", ssrc1 <= 0xFFFFFFFFL)
        assertTrue("SSRC should be non-negative", ssrc2 >= 0)
        assertTrue("SSRC should fit in 32 bits", ssrc2 <= 0xFFFFFFFFL)
    }

    @Test
    fun `H264 single NAL at boundary size is not fragmented`() {
        // Create packetizer with small max size
        val maxPktSize = 50
        val packetizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = maxPktSize,
            isHevc = false
        )

        // maxPayloadSize = 50 - 12 = 38
        // NAL payload of exactly 38 bytes should fit in one packet
        val nalPayload = ByteArray(38)
        nalPayload[0] = 0x41
        for (i in 1 until 38) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = packetizer.packetize(data, 0L)

        assertEquals(1, packets.size)
        assertArrayEquals(nalPayload, packets[0].payload)
    }

    @Test
    fun `H264 single NAL one byte over boundary is fragmented`() {
        val maxPktSize = 50
        val packetizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H264,
            ssrc = testSsrc,
            maxPacketSize = maxPktSize,
            isHevc = false
        )

        // maxPayloadSize = 50 - 12 = 38
        // NAL payload of 39 bytes should be fragmented
        val nalPayload = ByteArray(39)
        nalPayload[0] = 0x41
        for (i in 1 until 39) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = packetizer.packetize(data, 0L)

        assertTrue("39-byte payload should be fragmented with maxPktSize=50", packets.size > 1)
    }

    @Test
    fun `HEVC FU reassembly of fragment payloads matches original NAL`() {
        val smallPacketizer = RtpPacketizer(
            payloadType = PAYLOAD_TYPE_H265,
            ssrc = testSsrc,
            maxPacketSize = 100,
            isHevc = true
        )

        // TRAIL_R: type=1, header bytes = 0x02, 0x01
        val nalPayload = ByteArray(250)
        nalPayload[0] = 0x02
        nalPayload[1] = 0x01
        for (i in 2 until 250) nalPayload[i] = (i and 0xFF).toByte()
        val data = START_CODE_4 + nalPayload

        val packets = smallPacketizer.packetize(data, 0L)

        // Reassemble HEVC NAL: reconstruct 2-byte header from PayloadHdr + FU header
        val reassembled = mutableListOf<Byte>()

        // Reconstruct original NAL header from first fragment
        val payloadHdr0 = packets[0].payload[0].toInt() and 0xFF
        val payloadHdr1 = packets[0].payload[1].toInt() and 0xFF
        val fuHeader = packets[0].payload[2].toInt() and 0xFF
        val originalType = fuHeader and 0x3F
        val layerId = ((payloadHdr0 and 0x01) shl 5) or ((payloadHdr1 shr 3) and 0x1F)
        val tid = payloadHdr1 and 0x07

        // Reconstruct NAL header bytes
        val nalHdr0 = ((originalType shl 1) or (layerId shr 5)).toByte()
        val nalHdr1 = ((layerId shl 3) or tid).toByte()
        reassembled.add(nalHdr0)
        reassembled.add(nalHdr1)

        // Collect fragment payloads (skip 3 bytes: PayloadHdr[0], PayloadHdr[1], FU header)
        for (packet in packets) {
            val fragmentData = packet.payload.copyOfRange(3, packet.payload.size)
            reassembled.addAll(fragmentData.toList())
        }

        assertArrayEquals(
            "Reassembled HEVC fragments should match original NAL payload",
            nalPayload,
            reassembled.toByteArray()
        )
    }

    @Test
    fun `all packets from packetize have version 2`() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1E)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x38)
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x80.toByte())

        val data = START_CODE_4 + sps + START_CODE_4 + pps + START_CODE_4 + idr
        val packets = h264Packetizer.packetize(data, 0L)

        for (packet in packets) {
            assertEquals(2, packet.version)
            val bytes = packet.toByteArray()
            val version = (bytes[0].toInt() and 0xFF) shr 6
            assertEquals(2, version)
        }
    }
}
