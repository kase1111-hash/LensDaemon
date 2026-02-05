package com.lensdaemon.output

import com.lensdaemon.encoder.H264NalType
import com.lensdaemon.encoder.H265NalType
import com.lensdaemon.encoder.NalUnit
import com.lensdaemon.encoder.NalUnitParser
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * RTP packet structure
 *
 * @param version RTP version (always 2)
 * @param padding Padding flag
 * @param extension Extension flag
 * @param csrcCount CSRC count
 * @param marker Marker bit (set on last packet of frame)
 * @param payloadType Payload type
 * @param sequenceNumber Sequence number
 * @param timestamp RTP timestamp
 * @param ssrc Synchronization source identifier
 * @param payload Packet payload
 */
data class RtpPacket(
    val version: Int = 2,
    val padding: Boolean = false,
    val extension: Boolean = false,
    val csrcCount: Int = 0,
    val marker: Boolean,
    val payloadType: Int,
    val sequenceNumber: Int,
    val timestamp: Long,
    val ssrc: Long,
    val payload: ByteArray
) {
    companion object {
        const val HEADER_SIZE = 12
        const val MAX_PACKET_SIZE = 1400 // MTU - IP/UDP headers
    }

    /**
     * Serialize RTP packet to bytes
     */
    fun toByteArray(): ByteArray {
        val packet = ByteArray(HEADER_SIZE + payload.size)

        // First byte: V=2, P, X, CC
        packet[0] = ((version shl 6) or
                (if (padding) 0x20 else 0) or
                (if (extension) 0x10 else 0) or
                csrcCount).toByte()

        // Second byte: M, PT
        packet[1] = ((if (marker) 0x80 else 0) or
                (payloadType and 0x7F)).toByte()

        // Sequence number (2 bytes, big-endian)
        packet[2] = (sequenceNumber shr 8).toByte()
        packet[3] = (sequenceNumber and 0xFF).toByte()

        // Timestamp (4 bytes, big-endian)
        packet[4] = (timestamp shr 24).toByte()
        packet[5] = (timestamp shr 16).toByte()
        packet[6] = (timestamp shr 8).toByte()
        packet[7] = (timestamp and 0xFF).toByte()

        // SSRC (4 bytes, big-endian)
        packet[8] = (ssrc shr 24).toByte()
        packet[9] = (ssrc shr 16).toByte()
        packet[10] = (ssrc shr 8).toByte()
        packet[11] = (ssrc and 0xFF).toByte()

        // Payload
        System.arraycopy(payload, 0, packet, HEADER_SIZE, payload.size)

        return packet
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as RtpPacket
        return sequenceNumber == other.sequenceNumber &&
                timestamp == other.timestamp &&
                ssrc == other.ssrc &&
                payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = sequenceNumber
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + ssrc.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }
}

/**
 * RTP packetizer for H.264/H.265 video
 * Handles NAL unit fragmentation according to RFC 6184 (H.264) and RFC 7798 (H.265)
 */
class RtpPacketizer(
    private val payloadType: Int = SdpGenerator.PAYLOAD_TYPE_H264,
    private val ssrc: Long = Random.nextLong() and 0xFFFFFFFFL,
    private val maxPacketSize: Int = RtpPacket.MAX_PACKET_SIZE,
    private val isHevc: Boolean = false
) {
    companion object {
        private const val TAG = "RtpPacketizer"

        // H.264 FU-A fragmentation
        private const val H264_FU_A_TYPE = 28

        // H.265 FU fragmentation
        private const val H265_FU_TYPE = 49

        // Clock rate for video (90kHz)
        const val CLOCK_RATE = 90000

        /**
         * Convert presentation time (microseconds) to RTP timestamp
         */
        fun toRtpTimestamp(presentationTimeUs: Long): Long {
            return (presentationTimeUs * CLOCK_RATE / 1_000_000)
        }
    }

    private val sequenceNumber = AtomicInteger(Random.nextInt(0xFFFF))
    private val nalParser = NalUnitParser(isHevc)

    /**
     * Packetize encoded frame data into RTP packets
     *
     * @param data Encoded frame data (may contain multiple NAL units)
     * @param presentationTimeUs Presentation timestamp in microseconds
     * @return List of RTP packets
     */
    fun packetize(data: ByteArray, presentationTimeUs: Long): List<RtpPacket> {
        val packets = mutableListOf<RtpPacket>()
        val rtpTimestamp = toRtpTimestamp(presentationTimeUs)

        // Parse NAL units from encoded data
        val nalUnits = nalParser.parse(data)

        for ((index, nalUnit) in nalUnits.withIndex()) {
            val isLastNal = index == nalUnits.size - 1
            packets.addAll(packetizeNalUnit(nalUnit, rtpTimestamp, isLastNal))
        }

        return packets
    }

    /**
     * Packetize a single NAL unit
     */
    private fun packetizeNalUnit(
        nalUnit: NalUnit,
        rtpTimestamp: Long,
        isLastNal: Boolean
    ): List<RtpPacket> {
        val payload = nalUnit.payload
        val maxPayloadSize = maxPacketSize - RtpPacket.HEADER_SIZE

        return if (payload.size <= maxPayloadSize) {
            // Single NAL unit packet
            listOf(createSingleNalPacket(payload, rtpTimestamp, isLastNal))
        } else {
            // Fragmentation required
            if (isHevc) {
                createH265FragmentPackets(nalUnit, rtpTimestamp, isLastNal)
            } else {
                createH264FuAPackets(nalUnit, rtpTimestamp, isLastNal)
            }
        }
    }

    /**
     * Create single NAL unit packet (no fragmentation)
     */
    private fun createSingleNalPacket(
        payload: ByteArray,
        rtpTimestamp: Long,
        marker: Boolean
    ): RtpPacket {
        return RtpPacket(
            marker = marker,
            payloadType = payloadType,
            sequenceNumber = sequenceNumber.getAndIncrement() and 0xFFFF,
            timestamp = rtpTimestamp,
            ssrc = ssrc,
            payload = payload
        )
    }

    /**
     * Create H.264 FU-A fragmentation packets (RFC 6184)
     *
     * FU-A format:
     * +---------------+---------------+---------------+
     * | FU indicator  | FU header     | FU payload    |
     * +---------------+---------------+---------------+
     *
     * FU indicator: same format as NAL header, but type=28 (FU-A)
     * FU header: S|E|R|Type (Start, End, Reserved, Original NAL type)
     */
    private fun createH264FuAPackets(
        nalUnit: NalUnit,
        rtpTimestamp: Long,
        isLastNal: Boolean
    ): List<RtpPacket> {
        val packets = mutableListOf<RtpPacket>()
        val payload = nalUnit.payload

        // NAL header is first byte
        val nalHeader = payload[0].toInt() and 0xFF
        val nalType = nalHeader and 0x1F
        val nri = nalHeader and 0x60

        // FU indicator: NRI + FU-A type (28)
        val fuIndicator = (nri or H264_FU_A_TYPE).toByte()

        // Max payload per fragment (minus FU indicator and header)
        val maxFragmentSize = maxPacketSize - RtpPacket.HEADER_SIZE - 2
        var offset = 1 // Skip NAL header byte
        var isFirst = true

        while (offset < payload.size) {
            val remaining = payload.size - offset
            val fragmentSize = minOf(remaining, maxFragmentSize)
            val isLast = (offset + fragmentSize >= payload.size)

            // FU header: S|E|R|Type
            val fuHeader = ((if (isFirst) 0x80 else 0) or
                    (if (isLast) 0x40 else 0) or
                    nalType).toByte()

            // Build packet payload: FU indicator + FU header + fragment
            val packetPayload = ByteArray(2 + fragmentSize)
            packetPayload[0] = fuIndicator
            packetPayload[1] = fuHeader
            System.arraycopy(payload, offset, packetPayload, 2, fragmentSize)

            // Marker bit set on last packet of last NAL
            val marker = isLast && isLastNal

            packets.add(RtpPacket(
                marker = marker,
                payloadType = payloadType,
                sequenceNumber = sequenceNumber.getAndIncrement() and 0xFFFF,
                timestamp = rtpTimestamp,
                ssrc = ssrc,
                payload = packetPayload
            ))

            offset += fragmentSize
            isFirst = false
        }

        return packets
    }

    /**
     * Create H.265 FU fragmentation packets (RFC 7798)
     *
     * FU format:
     * +---------------+---------------+---------------+---------------+
     * | PayloadHdr    | FU header     | FU payload    |
     * +---------------+---------------+---------------+---------------+
     *
     * PayloadHdr: 2 bytes (Type=49 for FU, LayerId, TID from original NAL)
     * FU header: S|E|Type (Start, End, Original NAL type)
     */
    private fun createH265FragmentPackets(
        nalUnit: NalUnit,
        rtpTimestamp: Long,
        isLastNal: Boolean
    ): List<RtpPacket> {
        val packets = mutableListOf<RtpPacket>()
        val payload = nalUnit.payload

        // HEVC NAL header is 2 bytes
        if (payload.size < 2) return packets

        val nalHeader0 = payload[0].toInt() and 0xFF
        val nalHeader1 = payload[1].toInt() and 0xFF
        val nalType = (nalHeader0 shr 1) and 0x3F
        val layerId = ((nalHeader0 and 0x01) shl 5) or ((nalHeader1 shr 3) and 0x1F)
        val tid = nalHeader1 and 0x07

        // FU PayloadHdr: Type=49, LayerId, TID
        val payloadHdr0 = ((H265_FU_TYPE shl 1) or (layerId shr 5)).toByte()
        val payloadHdr1 = ((layerId shl 3) or tid).toByte()

        // Max payload per fragment
        val maxFragmentSize = maxPacketSize - RtpPacket.HEADER_SIZE - 3
        var offset = 2 // Skip NAL header
        var isFirst = true

        while (offset < payload.size) {
            val remaining = payload.size - offset
            val fragmentSize = minOf(remaining, maxFragmentSize)
            val isLast = (offset + fragmentSize >= payload.size)

            // FU header: S|E|Type
            val fuHeader = ((if (isFirst) 0x80 else 0) or
                    (if (isLast) 0x40 else 0) or
                    nalType).toByte()

            // Build packet: PayloadHdr (2) + FU header (1) + fragment
            val packetPayload = ByteArray(3 + fragmentSize)
            packetPayload[0] = payloadHdr0
            packetPayload[1] = payloadHdr1
            packetPayload[2] = fuHeader
            System.arraycopy(payload, offset, packetPayload, 3, fragmentSize)

            val marker = isLast && isLastNal

            packets.add(RtpPacket(
                marker = marker,
                payloadType = payloadType,
                sequenceNumber = sequenceNumber.getAndIncrement() and 0xFFFF,
                timestamp = rtpTimestamp,
                ssrc = ssrc,
                payload = packetPayload
            ))

            offset += fragmentSize
            isFirst = false
        }

        return packets
    }

    /**
     * Get current SSRC
     */
    fun getSsrc(): Long = ssrc

    /**
     * Get current sequence number
     */
    fun getSequenceNumber(): Int = sequenceNumber.get()

    /**
     * Reset sequence number
     */
    fun resetSequenceNumber() {
        sequenceNumber.set(Random.nextInt(0xFFFF))
    }

    /**
     * Create RTP packets for SPS/PPS (codec config)
     * These are sent as single NAL units, typically before keyframes
     */
    fun packetizeConfig(sps: ByteArray, pps: ByteArray, rtpTimestamp: Long): List<RtpPacket> {
        val packets = mutableListOf<RtpPacket>()

        // SPS packet (not marker, more NALs follow)
        packets.add(createSingleNalPacket(sps, rtpTimestamp, marker = false))

        // PPS packet (not marker, IDR follows)
        packets.add(createSingleNalPacket(pps, rtpTimestamp, marker = false))

        return packets
    }

    /**
     * Create RTP packets for VPS/SPS/PPS (H.265 codec config)
     */
    fun packetizeHevcConfig(
        vps: ByteArray,
        sps: ByteArray,
        pps: ByteArray,
        rtpTimestamp: Long
    ): List<RtpPacket> {
        val packets = mutableListOf<RtpPacket>()

        packets.add(createSingleNalPacket(vps, rtpTimestamp, marker = false))
        packets.add(createSingleNalPacket(sps, rtpTimestamp, marker = false))
        packets.add(createSingleNalPacket(pps, rtpTimestamp, marker = false))

        return packets
    }
}

/**
 * Factory for creating RTP packetizers
 */
object RtpPacketizerFactory {
    /**
     * Create packetizer for H.264
     */
    fun createH264Packetizer(
        ssrc: Long = Random.nextLong() and 0xFFFFFFFFL,
        maxPacketSize: Int = RtpPacket.MAX_PACKET_SIZE
    ): RtpPacketizer {
        return RtpPacketizer(
            payloadType = SdpGenerator.PAYLOAD_TYPE_H264,
            ssrc = ssrc,
            maxPacketSize = maxPacketSize,
            isHevc = false
        )
    }

    /**
     * Create packetizer for H.265/HEVC
     */
    fun createH265Packetizer(
        ssrc: Long = Random.nextLong() and 0xFFFFFFFFL,
        maxPacketSize: Int = RtpPacket.MAX_PACKET_SIZE
    ): RtpPacketizer {
        return RtpPacketizer(
            payloadType = SdpGenerator.PAYLOAD_TYPE_H265,
            ssrc = ssrc,
            maxPacketSize = maxPacketSize,
            isHevc = true
        )
    }
}
