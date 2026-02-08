package com.lensdaemon.output

import timber.log.Timber
import java.nio.ByteBuffer

/**
 * RTCP Receiver Report parser.
 *
 * Parses incoming RTCP packets (primarily Receiver Reports — RR, type 201)
 * to extract packet loss and jitter metrics from clients.
 *
 * RTCP packet format (RFC 3550):
 *   0                   1                   2                   3
 *   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |V=2|P|   RC    |     PT        |           length              |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *  |                          SSRC                                 |
 *  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 */
object RtcpParser {

    private const val TAG = "RtcpParser"

    // RTCP packet types
    private const val RTCP_SR = 200   // Sender Report
    private const val RTCP_RR = 201   // Receiver Report
    private const val RTCP_BYE = 203  // Goodbye

    // Minimum RTCP packet size: 4-byte header + 4-byte SSRC
    private const val MIN_RTCP_SIZE = 8

    // Receiver report block size: 24 bytes
    private const val REPORT_BLOCK_SIZE = 24

    /**
     * Parsed RTCP Receiver Report block.
     * One per source being reported on.
     */
    data class ReceiverReport(
        /** SSRC of the source being reported */
        val ssrc: Long,
        /** Fraction of packets lost since last RR (0-255, where 255 = 100% loss) */
        val fractionLost: Int,
        /** Cumulative number of packets lost */
        val cumulativeLost: Int,
        /** Extended highest sequence number received */
        val highestSequence: Long,
        /** Interarrival jitter estimate (in timestamp units) */
        val jitter: Long
    ) {
        /** Loss fraction as a percentage (0.0 - 100.0) */
        val lossPercent: Float get() = (fractionLost / 256f) * 100f
    }

    /**
     * Parse an RTCP packet and extract receiver reports.
     *
     * @param data raw UDP payload
     * @return list of receiver reports, empty if packet is not an RR or is malformed
     */
    fun parseReceiverReports(data: ByteArray): List<ReceiverReport> {
        if (data.size < MIN_RTCP_SIZE) return emptyList()

        val buf = ByteBuffer.wrap(data)

        // First byte: V=2 (2 bits), P (1 bit), RC (5 bits)
        val firstByte = buf.get().toInt() and 0xFF
        val version = (firstByte shr 6) and 0x03
        if (version != 2) {
            Timber.tag(TAG).v("Ignoring non-RTCP packet (version=$version)")
            return emptyList()
        }
        val reportCount = firstByte and 0x1F

        // Second byte: packet type
        val packetType = buf.get().toInt() and 0xFF
        if (packetType != RTCP_RR) {
            // Not a Receiver Report — ignore (could be SR, BYE, etc.)
            return emptyList()
        }

        // Length in 32-bit words minus one (header word)
        val length = buf.short.toInt() and 0xFFFF
        val expectedSize = (length + 1) * 4
        if (data.size < expectedSize) {
            Timber.tag(TAG).w("RTCP RR truncated: have ${data.size}, need $expectedSize")
            return emptyList()
        }

        // Reporter SSRC (who is sending this report)
        buf.int // skip reporter SSRC

        // Parse report blocks
        val reports = mutableListOf<ReceiverReport>()
        for (i in 0 until reportCount) {
            if (buf.remaining() < REPORT_BLOCK_SIZE) break

            val ssrc = buf.int.toLong() and 0xFFFFFFFFL
            val fractionAndCumulative = buf.int
            val fractionLost = (fractionAndCumulative ushr 24) and 0xFF
            // Cumulative lost is 24-bit signed
            val cumulativeLost = fractionAndCumulative and 0x00FFFFFF
            val highestSequence = buf.int.toLong() and 0xFFFFFFFFL
            val jitter = buf.int.toLong() and 0xFFFFFFFFL
            buf.int // skip last SR timestamp
            buf.int // skip delay since last SR

            reports.add(
                ReceiverReport(
                    ssrc = ssrc,
                    fractionLost = fractionLost,
                    cumulativeLost = cumulativeLost,
                    highestSequence = highestSequence,
                    jitter = jitter
                )
            )
        }

        return reports
    }
}
