package com.lensdaemon.output

import java.nio.ByteBuffer

/**
 * RTCP Sender Report (RFC 3550 section 6.4.1) with an SDES CNAME, sent as one
 * compound packet.
 *
 * A sender report ties an RTP timestamp to wall-clock (NTP) time. Players
 * need one per stream to line audio up with video: without them each stream
 * is timed from its own first packet, so audio and video drift apart by
 * however far apart their first packets were. Both streams' reports are
 * built from the same media-clock reading, so they agree on "now".
 */
object RtcpSenderReport {

    private const val VERSION_BITS = 0x80
    private const val PT_SR = 200
    private const val PT_SDES = 202
    private const val SDES_CNAME = 1
    private const val SR_LENGTH_BYTES = 28

    /** Seconds from the NTP epoch (1900) to the Unix epoch (1970). */
    private const val NTP_UNIX_OFFSET_SECONDS = 2_208_988_800L

    /**
     * Build the compound packet: SR (no reception report blocks) then SDES.
     *
     * @param wallClockMs wall-clock time of the instant [rtpTimestamp] refers to
     * @param packetCount RTP packets sent so far on this stream
     * @param octetCount RTP payload octets sent so far on this stream
     */
    fun build(
        ssrc: Long,
        wallClockMs: Long,
        rtpTimestamp: Long,
        packetCount: Long,
        octetCount: Long,
        cname: String
    ): ByteArray {
        val sdes = sdes(ssrc, cname)
        val buffer = ByteBuffer.allocate(SR_LENGTH_BYTES + sdes.size)
        buffer.put(VERSION_BITS.toByte())
        buffer.put(PT_SR.toByte())
        buffer.putShort((SR_LENGTH_BYTES / 4 - 1).toShort())
        buffer.putInt(ssrc.toInt())
        buffer.putLong(ntpTimestamp(wallClockMs))
        buffer.putInt(rtpTimestamp.toInt())
        buffer.putInt(packetCount.toInt())
        buffer.putInt(octetCount.toInt())
        buffer.put(sdes)
        return buffer.array()
    }

    /** 64-bit NTP timestamp: seconds since 1900 in the high word, binary fraction in the low. */
    fun ntpTimestamp(wallClockMs: Long): Long {
        val seconds = wallClockMs / 1000 + NTP_UNIX_OFFSET_SECONDS
        val fraction = (wallClockMs % 1000) * 0x1_0000_0000L / 1000
        return (seconds shl 32) or fraction
    }

    /** SDES packet with one chunk: the CNAME item, an END item, padded to 32 bits. */
    private fun sdes(ssrc: Long, cname: String): ByteArray {
        val text = cname.toByteArray(Charsets.US_ASCII).take(255).toByteArray()
        val chunkLength = 4 + 2 + text.size + 1 // SSRC, item header, text, END
        val paddedChunk = (chunkLength + 3) / 4 * 4
        val buffer = ByteBuffer.allocate(4 + paddedChunk)
        buffer.put((VERSION_BITS or 1).toByte()) // one chunk
        buffer.put(PT_SDES.toByte())
        buffer.putShort(((4 + paddedChunk) / 4 - 1).toShort())
        buffer.putInt(ssrc.toInt())
        buffer.put(SDES_CNAME.toByte())
        buffer.put(text.size.toByte())
        buffer.put(text)
        // END item and padding are zero bytes, already there
        return buffer.array()
    }
}
