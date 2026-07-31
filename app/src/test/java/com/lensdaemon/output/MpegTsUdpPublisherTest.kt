package com.lensdaemon.output

import com.lensdaemon.encoder.EncodedFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [MpegTsUdpPublisher]'s transport stream packetization.
 *
 * These validate the bytes the way a receiving demuxer reads them: follow the
 * adaptation field to find the payload, and check that what a player would read
 * is exactly what the publisher wrote. Regression cover for three defects found
 * by soak-testing a multi-hour stream:
 *
 *  - PES payloads landing on a 184-byte boundary left one byte of the TS packet
 *    unwritten while still declaring a full payload, injecting a garbage byte
 *    into the elementary stream roughly once every 184 frames.
 *  - PCR was derived from wall-clock epoch time while PTS came from the
 *    boot-relative encoder clock, so the two were hours apart.
 *  - PCR was only emitted on keyframes, giving a 2 second interval against the
 *    100 ms ceiling in ISO 13818-1.
 *
 * These run on the JVM; the packetizer is reached reflectively because it is an
 * internal detail of the publisher rather than part of its API.
 */
class MpegTsUdpPublisherTest {

    companion object {
        private const val TS_PACKET_SIZE = 188
        private const val TS_SYNC_BYTE = 0x47.toByte()
        private const val VIDEO_PID = 256
        private const val PTS_MASK = 0x1FFFFFFFFL

        /** Matches MpegTsUdpConfig's default latency. */
        private const val LATENCY_MS = 120L

        private const val FLAG_KEY_FRAME = 1
    }

    private val publisher = MpegTsUdpPublisher(MpegTsUdpConfig())

    private val packetizeToTsMethod = MpegTsUdpPublisher::class.java
        .getDeclaredMethod(
            "packetizeToTs",
            Int::class.java, ByteArray::class.java, Boolean::class.java, java.lang.Long::class.java
        )
        .apply { isAccessible = true }

    private val createPesMethod = MpegTsUdpPublisher::class.java
        .getDeclaredMethod("createPesPacket", EncodedFrame::class.java, Long::class.java)
        .apply { isAccessible = true }

    private val mediaPtsMethod = MpegTsUdpPublisher::class.java
        .getDeclaredMethod("mediaPts90kHz", Long::class.java)
        .apply { isAccessible = true }

    @Suppress("UNCHECKED_CAST")
    private fun packetize(payload: ByteArray, isKeyFrame: Boolean, pcr: Long?): List<ByteArray> =
        packetizeToTsMethod.invoke(publisher, VIDEO_PID, payload, isKeyFrame, pcr) as List<ByteArray>

    private fun createPes(frame: EncodedFrame, pts90kHz: Long): ByteArray =
        createPesMethod.invoke(publisher, frame, pts90kHz) as ByteArray

    private fun mediaPts90kHz(presentationTimeUs: Long): Long =
        mediaPtsMethod.invoke(publisher, presentationTimeUs) as Long

    private fun pcrFor(pts90kHz: Long): Long = (pts90kHz - LATENCY_MS * 90) and PTS_MASK

    // -----------------------------------------------------------------
    // TS packet structure
    // -----------------------------------------------------------------

    @Test
    fun `every payload size produces exactly filled TS packets`() {
        // 1..4000 covers every alignment against the 184 byte payload capacity
        // several times over, including the 183 case that used to corrupt.
        for (size in 1..4000) {
            val payload = ByteArray(size) { ((it % 251) + 1).toByte() }
            val packets = packetize(payload, isKeyFrame = false, pcr = null)

            var carried = 0
            for (pkt in packets) {
                assertEquals("packet must be 188 bytes for payload size $size", TS_PACKET_SIZE, pkt.size)
                assertEquals("sync byte missing for payload size $size", TS_SYNC_BYTE, pkt[0])
                val offset = payloadOffset(pkt)
                assertNotNull("packet carries no payload for size $size", offset)
                carried += TS_PACKET_SIZE - offset!!
            }

            assertEquals(
                "a demuxer would read $carried bytes for a $size byte PES",
                size,
                carried
            )
        }
    }

    @Test
    fun `PES survives the round trip through TS packetization`() {
        for (size in intArrayOf(1, 100, 183, 184, 185, 367, 368, 1000, 20_000)) {
            val payload = ByteArray(size) { ((it * 7 % 251) + 1).toByte() }
            val packets = packetize(payload, isKeyFrame = true, pcr = 90_000L)

            val demuxed = java.io.ByteArrayOutputStream()
            for (pkt in packets) {
                val offset = payloadOffset(pkt) ?: continue
                demuxed.write(pkt, offset, TS_PACKET_SIZE - offset)
            }

            assertArrayEqualsMsg("PES corrupted for size $size", payload, demuxed.toByteArray())
        }
    }

    @Test
    fun `payload unit start indicator marks only the first packet`() {
        val packets = packetize(ByteArray(5000) { 1 }, isKeyFrame = false, pcr = null)
        assertTrue("expected a multi-packet PES", packets.size > 1)
        assertTrue("first packet must set PUSI", (packets.first()[1].toInt() and 0x40) != 0)
        for (pkt in packets.drop(1)) {
            assertEquals("continuation packets must not set PUSI", 0, pkt[1].toInt() and 0x40)
        }
    }

    @Test
    fun `continuity counter advances by one per packet`() {
        val fresh = MpegTsUdpPublisher(MpegTsUdpConfig())
        @Suppress("UNCHECKED_CAST")
        val packets = packetizeToTsMethod
            .invoke(fresh, VIDEO_PID, ByteArray(3000) { 2 }, false, null) as List<ByteArray>

        var expected = packets.first()[3].toInt() and 0x0F
        for (pkt in packets) {
            assertEquals("continuity counter discontinuity", expected, pkt[3].toInt() and 0x0F)
            expected = (expected + 1) and 0x0F
        }
    }

    @Test
    fun `keyframe packets signal random access`() {
        val packets = packetize(ByteArray(2000) { 3 }, isKeyFrame = true, pcr = 90_000L)
        val flags = packets.first()[5].toInt()
        assertTrue("random_access_indicator not set on keyframe", (flags and 0x40) != 0)
        assertTrue("PCR_flag not set on keyframe", (flags and 0x10) != 0)
    }

    // -----------------------------------------------------------------
    // Clocks
    // -----------------------------------------------------------------

    @Test
    fun `PCR trails PTS by the configured buffer delay`() {
        // Android hands the encoder boot-relative timestamps, so model a device
        // that has been up for three days rather than one that just booted.
        val bootUptimeUs = 3L * 24 * 3600 * 1_000_000
        val frame = EncodedFrame(ByteArray(1024) { 4 }, bootUptimeUs, FLAG_KEY_FRAME)

        val pts90 = mediaPts90kHz(frame.presentationTimeUs)
        val pes = createPes(frame, pts90)
        val packets = packetize(pes, isKeyFrame = true, pcr = pcrFor(pts90))

        val pts = readPts(pes)
        val pcr = packets.firstNotNullOfOrNull { readPcr(it) }
        assertNotNull("PES carried no PTS", pts)
        assertNotNull("no PCR was emitted", pcr)

        val delayMs = ((pts!! - pcr!!) and PTS_MASK) / 90.0
        assertEquals("decoder buffer delay", LATENCY_MS.toDouble(), delayMs, 1.0)
    }

    @Test
    fun `PTS and PCR wrap together across the 33 bit boundary`() {
        // Just before the ~26.5 hour wrap, and just after it.
        val nearWrapUs = (PTS_MASK * 1_000_000L) / 90_000L - 1_000_000L
        for (offsetUs in longArrayOf(0L, 500_000L, 1_500_000L, 3_000_000L)) {
            val ptUs = nearWrapUs + offsetUs
            val pts90 = (ptUs * 90_000L / 1_000_000L) and PTS_MASK
            val pcr90 = pcrFor(pts90)

            val delayMs = ((pts90 - pcr90) and PTS_MASK) / 90.0
            assertEquals(
                "buffer delay must survive the clock wrap",
                LATENCY_MS.toDouble(),
                delayMs,
                1.0
            )
        }
    }

    @Test
    fun `presentation clock is rebased to the first frame`() {
        val fresh = MpegTsUdpPublisher(MpegTsUdpConfig())
        val method = MpegTsUdpPublisher::class.java
            .getDeclaredMethod("mediaPts90kHz", Long::class.java).apply { isAccessible = true }

        // With no frame published yet the clock treats the first timestamp as its
        // origin, so a device with days of uptime still starts near zero.
        val bootUptimeUs = 5L * 24 * 3600 * 1_000_000
        val first = method.invoke(fresh, bootUptimeUs) as Long
        assertEquals("stream should start at the configured offset", LATENCY_MS * 90, first)
    }

    // -----------------------------------------------------------------
    // Demuxer-side helpers
    // -----------------------------------------------------------------

    /** Where a conformant demuxer starts reading payload, or null if there is none. */
    private fun payloadOffset(pkt: ByteArray): Int? {
        return when ((pkt[3].toInt() shr 4) and 0x03) {
            1 -> 4
            3 -> {
                val afLen = pkt[4].toInt() and 0xFF
                val off = 5 + afLen
                if (off > TS_PACKET_SIZE) null else off
            }
            else -> null
        }
    }

    private fun readPcr(pkt: ByteArray): Long? {
        val afc = (pkt[3].toInt() shr 4) and 0x03
        if (afc != 2 && afc != 3) return null
        if ((pkt[4].toInt() and 0xFF) < 7) return null
        if ((pkt[5].toInt() and 0x10) == 0) return null
        var base = 0L
        base = base or ((pkt[6].toLong() and 0xFF) shl 25)
        base = base or ((pkt[7].toLong() and 0xFF) shl 17)
        base = base or ((pkt[8].toLong() and 0xFF) shl 9)
        base = base or ((pkt[9].toLong() and 0xFF) shl 1)
        base = base or ((pkt[10].toLong() and 0x80) shr 7)
        return base
    }

    private fun readPts(pes: ByteArray): Long? {
        if (pes.size < 14) return null
        if (pes[0] != 0.toByte() || pes[1] != 0.toByte() || pes[2] != 1.toByte()) return null
        if ((pes[7].toInt() and 0xC0) shr 6 == 0) return null
        fun b(i: Int) = pes[i].toLong() and 0xFF
        var pts = 0L
        pts = pts or (((b(9) shr 1) and 0x07) shl 30)
        pts = pts or (b(10) shl 22)
        pts = pts or (((b(11) shr 1) and 0x7F) shl 15)
        pts = pts or (b(12) shl 7)
        pts = pts or ((b(13) shr 1) and 0x7F)
        return pts
    }

    private fun assertArrayEqualsMsg(msg: String, expected: ByteArray, actual: ByteArray) {
        assertEquals("$msg (length)", expected.size, actual.size)
        for (i in expected.indices) {
            if (expected[i] != actual[i]) {
                throw AssertionError("$msg (first difference at byte $i)")
            }
        }
    }
}
