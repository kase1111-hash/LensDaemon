package com.lensdaemon.output

import com.lensdaemon.encoder.EncodedFrame
import com.lensdaemon.encoder.H264NalType
import com.lensdaemon.encoder.H265NalType
import com.lensdaemon.encoder.NalUnitParser

/**
 * Shapes the encoder's output for one receiver, which usually joins a stream
 * that is already running (OBS reconnecting, a second viewer, a restarted
 * player).
 *
 * A decoder can only start on a keyframe, and only once it has the parameter
 * sets (SPS and PPS, plus VPS for H.265) that describe it. MediaCodec hands
 * those over once, in a codec-config buffer, when encoding starts; a receiver
 * that arrives later never sees them in band, and pictures it gets before its
 * first keyframe decode as grey smears. So, per receiver, this:
 *
 *  - holds back every picture until the first keyframe after [reset],
 *  - folds codec-config buffers into its parameter-set cache instead of passing
 *    them on (they carry no picture and no usable timestamp), and
 *  - prefixes each keyframe that lacks them with the cached parameter sets.
 *
 * Keyframes that already carry parameter sets in band pass unchanged and
 * refresh the cache, so a reconfigured encoder is described correctly from its
 * next keyframe on.
 *
 * Not thread-safe: callers serialize access.
 */
class KeyframeAligner(private val isHevc: Boolean) {

    private val parser = NalUnitParser(isHevc)

    var vps: ByteArray? = null
        private set
    var sps: ByteArray? = null
        private set
    var pps: ByteArray? = null
        private set

    /** True once a keyframe has been passed since construction or the last [reset]. */
    var started = false
        private set

    /** Pictures held back while waiting for a keyframe, since construction. */
    var skippedFrames = 0L
        private set

    /** Seed or replace the cached parameter sets. Null arguments keep the cached value. */
    fun setParameterSets(vps: ByteArray?, sps: ByteArray?, pps: ByteArray?) {
        vps?.let { this.vps = it }
        sps?.let { this.sps = it }
        pps?.let { this.pps = it }
    }

    /** Hold pictures back again until the next keyframe, e.g. when a receiver (re)starts playing. */
    fun reset() {
        started = false
    }

    /**
     * The access unit to deliver for [frame], or null if this receiver must not
     * get it (a codec-config buffer, or a picture before the first keyframe).
     */
    fun process(frame: EncodedFrame): ByteArray? {
        if (frame.isConfigFrame) {
            learnParameterSets(frame.data)
            return null
        }
        if (frame.isKeyFrame) {
            started = true
            return withParameterSets(frame.data)
        }
        if (!started) {
            skippedFrames++
            return null
        }
        return frame.data
    }

    private fun withParameterSets(keyframe: ByteArray): ByteArray {
        if (learnParameterSets(keyframe)) return keyframe

        val parts = listOfNotNull(if (isHevc) vps else null, sps, pps)
        if (parts.isEmpty()) return keyframe

        // An access unit delimiter must stay the first NAL unit of the access unit
        val insertAt = leadingAudLength(keyframe)
        val out = ByteArray(parts.sumOf { START_CODE.size + it.size } + keyframe.size)
        keyframe.copyInto(out, 0, 0, insertAt)
        var offset = insertAt
        for (part in parts) {
            START_CODE.copyInto(out, offset)
            offset += START_CODE.size
            part.copyInto(out, offset)
            offset += part.size
        }
        keyframe.copyInto(out, offset, insertAt)
        return out
    }

    /** Length of the access unit delimiter [accessUnit] opens with, or 0 if it has none. */
    private fun leadingAudLength(accessUnit: ByteArray): Int {
        val startCode = NalUnitParser.findStartCode(accessUnit, 0)
        if (startCode == 0 || accessUnit.size <= startCode) return 0
        val header = accessUnit[startCode].toInt()
        val isAud = if (isHevc) ((header and 0x7E) shr 1) == H265NalType.AUD else (header and 0x1F) == H264NalType.AUD
        if (!isAud) return 0
        val next = NalUnitParser.findNextStartCode(accessUnit, startCode + 1)
        return if (next < 0) accessUnit.size else next
    }

    /**
     * Cache the parameter sets carried in [data]. Returns true if it carried a
     * complete set (SPS and PPS, and VPS for H.265), i.e. needs nothing added.
     */
    private fun learnParameterSets(data: ByteArray): Boolean {
        var hasVps = false
        var hasSps = false
        var hasPps = false
        for (unit in parser.parse(data)) {
            when {
                isHevc && unit.type == H265NalType.VPS -> { vps = unit.payload; hasVps = true }
                isHevc && unit.type == H265NalType.SPS -> { sps = unit.payload; hasSps = true }
                isHevc && unit.type == H265NalType.PPS -> { pps = unit.payload; hasPps = true }
                !isHevc && unit.type == H264NalType.SPS -> { sps = unit.payload; hasSps = true }
                !isHevc && unit.type == H264NalType.PPS -> { pps = unit.payload; hasPps = true }
            }
        }
        return hasSps && hasPps && (!isHevc || hasVps)
    }

    private companion object {
        val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
    }
}
