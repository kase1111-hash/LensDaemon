package com.lensdaemon.encoder

import timber.log.Timber
import java.nio.ByteBuffer

/**
 * NAL unit types for H.264 (AVC)
 */
object H264NalType {
    const val UNSPECIFIED = 0
    const val SLICE_NON_IDR = 1      // Non-IDR slice
    const val SLICE_PART_A = 2       // Slice data partition A
    const val SLICE_PART_B = 3       // Slice data partition B
    const val SLICE_PART_C = 4       // Slice data partition C
    const val SLICE_IDR = 5          // IDR slice (keyframe)
    const val SEI = 6                // Supplemental enhancement information
    const val SPS = 7                // Sequence parameter set
    const val PPS = 8                // Picture parameter set
    const val AUD = 9                // Access unit delimiter
    const val END_SEQ = 10           // End of sequence
    const val END_STREAM = 11        // End of stream
    const val FILLER = 12            // Filler data
    const val SPS_EXT = 13           // SPS extension
    const val PREFIX = 14            // Prefix NAL unit
    const val SUBSET_SPS = 15        // Subset SPS
    const val STAP_A = 24            // STAP-A (RTP)
    const val STAP_B = 25            // STAP-B (RTP)
    const val MTAP16 = 26            // MTAP16 (RTP)
    const val MTAP24 = 27            // MTAP24 (RTP)
    const val FU_A = 28              // FU-A (RTP fragmentation)
    const val FU_B = 29              // FU-B (RTP fragmentation)

    fun isKeyFrame(type: Int): Boolean = type == SLICE_IDR
    fun isConfigData(type: Int): Boolean = type == SPS || type == PPS
    fun getName(type: Int): String = when (type) {
        SLICE_NON_IDR -> "P/B-Frame"
        SLICE_IDR -> "IDR (Keyframe)"
        SEI -> "SEI"
        SPS -> "SPS"
        PPS -> "PPS"
        AUD -> "AUD"
        else -> "NAL($type)"
    }
}

/**
 * NAL unit types for H.265 (HEVC)
 */
object H265NalType {
    const val TRAIL_N = 0
    const val TRAIL_R = 1
    const val TSA_N = 2
    const val TSA_R = 3
    const val STSA_N = 4
    const val STSA_R = 5
    const val RADL_N = 6
    const val RADL_R = 7
    const val RASL_N = 8
    const val RASL_R = 9
    const val BLA_W_LP = 16
    const val BLA_W_RADL = 17
    const val BLA_N_LP = 18
    const val IDR_W_RADL = 19        // IDR (keyframe)
    const val IDR_N_LP = 20          // IDR (keyframe)
    const val CRA_NUT = 21           // CRA (keyframe-like)
    const val VPS = 32               // Video parameter set
    const val SPS = 33               // Sequence parameter set
    const val PPS = 34               // Picture parameter set
    const val AUD = 35               // Access unit delimiter
    const val EOS = 36               // End of sequence
    const val EOB = 37               // End of bitstream
    const val FD = 38                // Filler data
    const val PREFIX_SEI = 39        // SEI prefix
    const val SUFFIX_SEI = 40        // SEI suffix
    const val AP = 48                // Aggregation packet (RTP)
    const val FU = 49                // Fragmentation unit (RTP)

    fun isKeyFrame(type: Int): Boolean = type in 16..21
    fun isConfigData(type: Int): Boolean = type in 32..34
    fun getName(type: Int): String = when (type) {
        TRAIL_N, TRAIL_R -> "Trail"
        IDR_W_RADL, IDR_N_LP -> "IDR (Keyframe)"
        CRA_NUT -> "CRA"
        VPS -> "VPS"
        SPS -> "SPS"
        PPS -> "PPS"
        AUD -> "AUD"
        else -> "NAL($type)"
    }
}

/**
 * Represents a parsed NAL unit
 */
data class NalUnit(
    /** NAL unit type */
    val type: Int,

    /** Raw data including start code */
    val data: ByteArray,

    /** Data offset (after start code) */
    val payloadOffset: Int,

    /** Payload size (excluding start code) */
    val payloadSize: Int,

    /** Start code length (3 or 4 bytes) */
    val startCodeLength: Int,

    /** Is this H.265/HEVC? */
    val isHevc: Boolean = false
) {
    /** Payload data without start code */
    val payload: ByteArray
        get() = data.copyOfRange(payloadOffset, payloadOffset + payloadSize)

    /** Full NAL unit data including start code */
    val fullData: ByteArray
        get() = data.copyOfRange(payloadOffset - startCodeLength, payloadOffset + payloadSize)

    val isKeyFrame: Boolean
        get() = if (isHevc) H265NalType.isKeyFrame(type) else H264NalType.isKeyFrame(type)

    val isConfigData: Boolean
        get() = if (isHevc) H265NalType.isConfigData(type) else H264NalType.isConfigData(type)

    val typeName: String
        get() = if (isHevc) H265NalType.getName(type) else H264NalType.getName(type)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NalUnit
        return type == other.type && data.contentEquals(other.data)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + data.contentHashCode()
        return result
    }

    override fun toString(): String = "NalUnit(type=$typeName, size=$payloadSize, keyframe=$isKeyFrame)"
}

/**
 * Parser for H.264/H.265 NAL units
 * Extracts individual NAL units from encoded video data for RTP packetization
 */
class NalUnitParser(
    private val isHevc: Boolean = false
) {
    companion object {
        private const val TAG = "NalUnitParser"

        // Start code patterns
        private val START_CODE_3 = byteArrayOf(0x00, 0x00, 0x01)
        private val START_CODE_4 = byteArrayOf(0x00, 0x00, 0x00, 0x01)

        /**
         * Find start code at position
         * Returns start code length (3 or 4) or 0 if not found
         */
        fun findStartCode(data: ByteArray, offset: Int): Int {
            if (offset + 4 <= data.size) {
                if (data[offset] == 0.toByte() &&
                    data[offset + 1] == 0.toByte() &&
                    data[offset + 2] == 0.toByte() &&
                    data[offset + 3] == 1.toByte()) {
                    return 4
                }
            }
            if (offset + 3 <= data.size) {
                if (data[offset] == 0.toByte() &&
                    data[offset + 1] == 0.toByte() &&
                    data[offset + 2] == 1.toByte()) {
                    return 3
                }
            }
            return 0
        }

        /**
         * Find next start code position after given offset
         * Returns -1 if not found
         */
        fun findNextStartCode(data: ByteArray, startOffset: Int): Int {
            var i = startOffset
            while (i < data.size - 2) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                    if (data[i + 2] == 1.toByte()) {
                        return i
                    }
                    if (i + 3 < data.size && data[i + 2] == 0.toByte() && data[i + 3] == 1.toByte()) {
                        return i
                    }
                }
                i++
            }
            return -1
        }
    }

    /** Cached SPS data */
    var sps: ByteArray? = null
        private set

    /** Cached PPS data */
    var pps: ByteArray? = null
        private set

    /** Cached VPS data (H.265 only) */
    var vps: ByteArray? = null
        private set

    /**
     * Parse NAL units from encoded frame data
     */
    fun parse(data: ByteArray): List<NalUnit> {
        val nalUnits = mutableListOf<NalUnit>()
        var offset = 0

        // Find first start code
        val firstStartCode = findStartCode(data, 0)
        if (firstStartCode == 0) {
            Timber.w("$TAG: No start code found at beginning of data")
            // Try to find start code elsewhere
            offset = findNextStartCode(data, 0)
            if (offset < 0) {
                Timber.e("$TAG: No NAL units found in data")
                return emptyList()
            }
        }

        while (offset < data.size) {
            val startCodeLen = findStartCode(data, offset)
            if (startCodeLen == 0) {
                offset++
                continue
            }

            val nalStart = offset + startCodeLen

            // Find next start code or end of data
            var nalEnd = findNextStartCode(data, nalStart)
            if (nalEnd < 0) {
                nalEnd = data.size
            }

            val nalSize = nalEnd - nalStart
            if (nalSize > 0) {
                val nalType = extractNalType(data, nalStart)
                val nalData = data.copyOfRange(offset, nalEnd)

                val nalUnit = NalUnit(
                    type = nalType,
                    data = nalData,
                    payloadOffset = startCodeLen,
                    payloadSize = nalSize,
                    startCodeLength = startCodeLen,
                    isHevc = isHevc
                )

                nalUnits.add(nalUnit)

                // Cache config data
                cacheConfigData(nalUnit)

                Timber.v("$TAG: Parsed ${nalUnit.typeName}, size=$nalSize")
            }

            offset = nalEnd
        }

        return nalUnits
    }

    /**
     * Parse NAL units from ByteBuffer
     */
    fun parse(buffer: ByteBuffer): List<NalUnit> {
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return parse(data)
    }

    /**
     * Extract NAL type from first byte after start code
     */
    private fun extractNalType(data: ByteArray, offset: Int): Int {
        if (offset >= data.size) return 0

        return if (isHevc) {
            // H.265: NAL type is bits 1-6 of first byte (shifted right 1)
            (data[offset].toInt() and 0x7E) shr 1
        } else {
            // H.264: NAL type is bits 0-4 of first byte
            data[offset].toInt() and 0x1F
        }
    }

    /**
     * Cache SPS/PPS/VPS for later use
     */
    private fun cacheConfigData(nalUnit: NalUnit) {
        when {
            isHevc -> {
                when (nalUnit.type) {
                    H265NalType.VPS -> {
                        vps = nalUnit.payload
                        Timber.d("$TAG: Cached VPS (${vps?.size} bytes)")
                    }
                    H265NalType.SPS -> {
                        sps = nalUnit.payload
                        Timber.d("$TAG: Cached SPS (${sps?.size} bytes)")
                    }
                    H265NalType.PPS -> {
                        pps = nalUnit.payload
                        Timber.d("$TAG: Cached PPS (${pps?.size} bytes)")
                    }
                }
            }
            else -> {
                when (nalUnit.type) {
                    H264NalType.SPS -> {
                        sps = nalUnit.payload
                        Timber.d("$TAG: Cached SPS (${sps?.size} bytes)")
                    }
                    H264NalType.PPS -> {
                        pps = nalUnit.payload
                        Timber.d("$TAG: Cached PPS (${pps?.size} bytes)")
                    }
                }
            }
        }
    }

    /**
     * Check if we have all required config data
     */
    fun hasConfigData(): Boolean {
        return if (isHevc) {
            vps != null && sps != null && pps != null
        } else {
            sps != null && pps != null
        }
    }

    /**
     * Get combined config data (SPS+PPS or VPS+SPS+PPS)
     * with start codes between each NAL unit
     */
    fun getConfigData(): ByteArray? {
        if (!hasConfigData()) return null

        return if (isHevc) {
            // VPS + SPS + PPS for H.265
            val vpsData = vps!!
            val spsData = sps!!
            val ppsData = pps!!

            ByteArray(START_CODE_4.size * 3 + vpsData.size + spsData.size + ppsData.size).also { result ->
                var offset = 0
                START_CODE_4.copyInto(result, offset)
                offset += START_CODE_4.size
                vpsData.copyInto(result, offset)
                offset += vpsData.size
                START_CODE_4.copyInto(result, offset)
                offset += START_CODE_4.size
                spsData.copyInto(result, offset)
                offset += spsData.size
                START_CODE_4.copyInto(result, offset)
                offset += START_CODE_4.size
                ppsData.copyInto(result, offset)
            }
        } else {
            // SPS + PPS for H.264
            val spsData = sps!!
            val ppsData = pps!!

            ByteArray(START_CODE_4.size * 2 + spsData.size + ppsData.size).also { result ->
                var offset = 0
                START_CODE_4.copyInto(result, offset)
                offset += START_CODE_4.size
                spsData.copyInto(result, offset)
                offset += spsData.size
                START_CODE_4.copyInto(result, offset)
                offset += START_CODE_4.size
                ppsData.copyInto(result, offset)
            }
        }
    }

    /**
     * Clear cached config data
     */
    fun clearCache() {
        sps = null
        pps = null
        vps = null
    }

    /**
     * Parse SPS to extract video dimensions
     * Returns Size(width, height) or null if parsing fails
     */
    fun parseSpsDimensions(): android.util.Size? {
        val spsData = sps ?: return null

        return try {
            if (isHevc) {
                parseH265SpsDimensions(spsData)
            } else {
                parseH264SpsDimensions(spsData)
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to parse SPS dimensions")
            null
        }
    }

    /**
     * Simple H.264 SPS parser for dimensions
     * Note: This is a simplified implementation
     */
    private fun parseH264SpsDimensions(sps: ByteArray): android.util.Size? {
        // This would require a full H.264 bitstream parser
        // For now, we rely on MediaFormat from encoder
        Timber.d("$TAG: H.264 SPS parsing not fully implemented")
        return null
    }

    /**
     * Simple H.265 SPS parser for dimensions
     * Note: This is a simplified implementation
     */
    private fun parseH265SpsDimensions(sps: ByteArray): android.util.Size? {
        // This would require a full H.265 bitstream parser
        // For now, we rely on MediaFormat from encoder
        Timber.d("$TAG: H.265 SPS parsing not fully implemented")
        return null
    }
}

/**
 * Utility for creating NAL units with proper start codes
 */
object NalUnitBuilder {
    private val START_CODE_4 = byteArrayOf(0x00, 0x00, 0x00, 0x01)

    /**
     * Prepend 4-byte start code to NAL payload
     */
    fun withStartCode(payload: ByteArray): ByteArray {
        return ByteArray(START_CODE_4.size + payload.size).also {
            START_CODE_4.copyInto(it, 0)
            payload.copyInto(it, START_CODE_4.size)
        }
    }

    /**
     * Create H.264 IDR frame with SPS/PPS prepended
     */
    fun createKeyFrameWithConfig(
        sps: ByteArray,
        pps: ByteArray,
        idrPayload: ByteArray
    ): ByteArray {
        val totalSize = (START_CODE_4.size * 3) + sps.size + pps.size + idrPayload.size
        return ByteArray(totalSize).also { result ->
            var offset = 0

            // SPS
            START_CODE_4.copyInto(result, offset)
            offset += START_CODE_4.size
            sps.copyInto(result, offset)
            offset += sps.size

            // PPS
            START_CODE_4.copyInto(result, offset)
            offset += START_CODE_4.size
            pps.copyInto(result, offset)
            offset += pps.size

            // IDR
            START_CODE_4.copyInto(result, offset)
            offset += START_CODE_4.size
            idrPayload.copyInto(result, offset)
        }
    }

    /**
     * Create H.265 IDR frame with VPS/SPS/PPS prepended
     */
    fun createHevcKeyFrameWithConfig(
        vps: ByteArray,
        sps: ByteArray,
        pps: ByteArray,
        idrPayload: ByteArray
    ): ByteArray {
        val totalSize = (START_CODE_4.size * 4) + vps.size + sps.size + pps.size + idrPayload.size
        return ByteArray(totalSize).also { result ->
            var offset = 0

            // VPS
            START_CODE_4.copyInto(result, offset)
            offset += START_CODE_4.size
            vps.copyInto(result, offset)
            offset += vps.size

            // SPS
            START_CODE_4.copyInto(result, offset)
            offset += START_CODE_4.size
            sps.copyInto(result, offset)
            offset += sps.size

            // PPS
            START_CODE_4.copyInto(result, offset)
            offset += START_CODE_4.size
            pps.copyInto(result, offset)
            offset += pps.size

            // IDR
            START_CODE_4.copyInto(result, offset)
            offset += START_CODE_4.size
            idrPayload.copyInto(result, offset)
        }
    }
}
