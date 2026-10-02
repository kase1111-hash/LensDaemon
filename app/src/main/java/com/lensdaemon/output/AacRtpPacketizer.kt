package com.lensdaemon.output

import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * RTP packetizer for AAC (RFC 3640, mpeg4-generic, AAC-hbr mode).
 *
 * Each packet carries one access unit behind a single AU header: a 16-bit
 * AU-headers-length (16 bits of headers follow), then the AU-size in 13 bits
 * and a 3-bit AU-index of 0. This matches the SDP fmtp
 * `sizelength=13; indexlength=3; indexdeltalength=3`. The RTP clock is the
 * sample rate.
 */
class AacRtpPacketizer(
    val clockRate: Int,
    private val payloadType: Int = SdpGenerator.PAYLOAD_TYPE_AAC,
    private val ssrc: Long = Random.nextLong() and 0xFFFFFFFFL
) {
    companion object {
        /** Bytes of AU-headers-length plus one AU header. */
        const val AU_HEADER_SECTION_LENGTH = 4

        /** Largest access unit a 13-bit AU-size can describe. */
        const val MAX_AU_SIZE = 0x1FFF
    }

    private val sequenceNumber = AtomicInteger(Random.nextInt(0xFFFF))

    fun getSsrc(): Long = ssrc

    fun getSequenceNumber(): Int = sequenceNumber.get() and 0xFFFF

    /** Media time in microseconds to this stream's RTP clock (truncated to 32 bits on the wire). */
    fun toRtpTimestamp(presentationTimeUs: Long): Long = presentationTimeUs * clockRate / 1_000_000

    /**
     * Wrap one raw AAC frame. Returns null for a frame too large for the AU
     * header (never produced by an AAC-LC encoder at streaming bitrates).
     */
    fun packetize(accessUnit: ByteArray, presentationTimeUs: Long): RtpPacket? {
        val size = accessUnit.size
        if (size > MAX_AU_SIZE) return null

        val payload = ByteArray(AU_HEADER_SECTION_LENGTH + size)
        payload[0] = 0x00
        payload[1] = 0x10 // AU-headers-length: 16 bits
        payload[2] = (size shr 5).toByte()
        payload[3] = ((size and 0x1F) shl 3).toByte() // AU-index 0
        accessUnit.copyInto(payload, AU_HEADER_SECTION_LENGTH)

        return RtpPacket(
            marker = true, // every packet completes an access unit
            payloadType = payloadType,
            sequenceNumber = sequenceNumber.getAndIncrement() and 0xFFFF,
            timestamp = toRtpTimestamp(presentationTimeUs),
            ssrc = ssrc,
            payload = payload
        )
    }
}
