package com.lensdaemon.output

import com.lensdaemon.encoder.AudioConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/**
 * Unit tests for the pieces that carry audio over RTSP: the RFC 3640 AAC
 * packetizer, RTCP sender reports and the SDP audio section.
 */
class RtspAudioTest {

    @Test
    fun `AAC packets carry one AU header describing the frame`() {
        val packetizer = AacRtpPacketizer(clockRate = 48_000, ssrc = 0x1234_5678L)
        val frame = ByteArray(371) { (it % 251).toByte() }

        val packet = packetizer.packetize(frame, presentationTimeUs = 2_000_000L)!!

        assertEquals(SdpGenerator.PAYLOAD_TYPE_AAC, packet.payloadType)
        assertTrue("every packet completes an access unit", packet.marker)
        assertEquals("RTP clock is the sample rate", 96_000L, packet.timestamp)
        val payload = packet.payload
        assertEquals("AU-headers-length is 16 bits", 16, ((payload[0].toInt() and 0xFF) shl 8) or (payload[1].toInt() and 0xFF))
        val auHeader = ((payload[2].toInt() and 0xFF) shl 8) or (payload[3].toInt() and 0xFF)
        assertEquals("AU-size", 371, auHeader shr 3)
        assertEquals("AU-index", 0, auHeader and 0x7)
        assertArrayEquals(frame, payload.copyOfRange(4, payload.size))
    }

    @Test
    fun `AAC sequence numbers advance by one`() {
        val packetizer = AacRtpPacketizer(clockRate = 48_000)
        val first = packetizer.packetize(ByteArray(10), 0L)!!.sequenceNumber
        val second = packetizer.packetize(ByteArray(10), 21_333L)!!.sequenceNumber
        assertEquals((first + 1) and 0xFFFF, second)
    }

    @Test
    fun `a frame too big for a 13-bit AU size is refused`() {
        assertNull(AacRtpPacketizer(48_000).packetize(ByteArray(AacRtpPacketizer.MAX_AU_SIZE + 1), 0L))
    }

    @Test
    fun `sender report ties the RTP timestamp to NTP time`() {
        val wallClockMs = 1_700_000_000_123L
        val report = RtcpSenderReport.build(
            ssrc = 0xCAFEBABEL,
            wallClockMs = wallClockMs,
            rtpTimestamp = 0x1_2345_6789L, // wraps to 32 bits on the wire
            packetCount = 42,
            octetCount = 9000,
            cname = "lensdaemon@192.168.1.50"
        )
        val buffer = ByteBuffer.wrap(report)

        assertEquals("V=2, no padding, no report blocks", 0x80, buffer.get().toInt() and 0xFF)
        assertEquals("PT=SR", 200, buffer.get().toInt() and 0xFF)
        assertEquals("length in 32-bit words minus one", 6, buffer.short.toInt())
        assertEquals(0xCAFEBABEL, buffer.int.toLong() and 0xFFFFFFFFL)
        val ntpSeconds = buffer.int.toLong() and 0xFFFFFFFFL
        val ntpFraction = buffer.int.toLong() and 0xFFFFFFFFL
        assertEquals("NTP seconds count from 1900", wallClockMs / 1000 + 2_208_988_800L, ntpSeconds)
        assertEquals("NTP fraction", 123.0, ntpFraction * 1000.0 / 0x1_0000_0000L, 0.01)
        assertEquals(0x2345_6789L, buffer.int.toLong() and 0xFFFFFFFFL)
        assertEquals(42, buffer.int)
        assertEquals(9000, buffer.int)

        // SDES follows in the same compound packet
        val sdesStart = buffer.position()
        assertEquals("SDES with one chunk", 0x81, buffer.get().toInt() and 0xFF)
        assertEquals("PT=SDES", 202, buffer.get().toInt() and 0xFF)
        val sdesWords = buffer.short.toInt() + 1
        assertEquals("SDES fills the rest of the packet", report.size - sdesStart, sdesWords * 4)
        assertEquals(0xCAFEBABEL, buffer.int.toLong() and 0xFFFFFFFFL)
        assertEquals("CNAME item", 1, buffer.get().toInt())
        val length = buffer.get().toInt()
        val text = ByteArray(length).also { buffer.get(it) }
        assertEquals("lensdaemon@192.168.1.50", String(text, Charsets.US_ASCII))
        assertEquals("END item", 0, buffer.get().toInt())
    }

    @Test
    fun `SDP audio section describes AAC-hbr with the AudioSpecificConfig`() {
        val section = SdpGenerator().audioMediaSection(AudioConfig(sampleRate = 48_000, channelCount = 2, bitrateBps = 128_000))

        assertTrue(section, section.contains("m=audio 0 RTP/AVP 98"))
        assertTrue(section, section.contains("a=rtpmap:98 MPEG4-GENERIC/48000/2"))
        assertTrue(section, section.contains("mode=AAC-hbr"))
        assertTrue(section, section.contains("config=1190"))
        assertTrue(section, section.contains("sizelength=13; indexlength=3; indexdeltalength=3"))
        assertTrue(section, section.contains("a=control:trackID=1"))
        assertTrue(section, section.contains("b=AS:128"))
    }
}
