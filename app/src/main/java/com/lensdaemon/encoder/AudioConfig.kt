package com.lensdaemon.encoder

/**
 * Audio stream settings: AAC-LC from the phone's microphone.
 */
data class AudioConfig(
    /** Sample rate in Hz; one of the AAC sampling frequencies. */
    val sampleRate: Int = 48_000,
    /** 1 (mono) or 2 (stereo). */
    val channelCount: Int = 2,
    /** AAC bitrate in bits per second. */
    val bitrateBps: Int = 128_000
)

/**
 * One encoded AAC access unit (a raw frame, no ADTS header), or the
 * encoder's AudioSpecificConfig when [isConfig].
 */
class EncodedAudioFrame(
    val data: ByteArray,
    /** Presentation time on the same clock as the video frames, in microseconds. */
    val presentationTimeUs: Long,
    val isConfig: Boolean = false
) {
    val size: Int get() = data.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncodedAudioFrame) return false
        return data.contentEquals(other.data) &&
            presentationTimeUs == other.presentationTimeUs &&
            isConfig == other.isConfig
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + presentationTimeUs.hashCode()
        result = 31 * result + isConfig.hashCode()
        return result
    }
}

/**
 * AAC-LC bitstream helpers (ISO/IEC 14496-3).
 */
object Aac {
    /** PCM samples per channel in one AAC-LC frame. */
    const val SAMPLES_PER_FRAME = 1024

    /** Audio object type for AAC Low Complexity. */
    private const val OBJECT_TYPE_LC = 2

    /** Length of an ADTS header without CRC. */
    const val ADTS_HEADER_LENGTH = 7

    /** The AAC sampling frequency table; a rate's index is what the bitstream carries. */
    private val SAMPLING_FREQUENCIES = intArrayOf(
        96_000, 88_200, 64_000, 48_000, 44_100, 32_000, 24_000, 22_050, 16_000, 12_000, 11_025, 8_000, 7_350
    )

    fun isSupportedSampleRate(sampleRate: Int): Boolean = sampleRate in SAMPLING_FREQUENCIES

    fun samplingFrequencyIndex(sampleRate: Int): Int {
        val index = SAMPLING_FREQUENCIES.indexOf(sampleRate)
        require(index >= 0) { "$sampleRate Hz is not an AAC sampling frequency" }
        return index
    }

    /**
     * The two-byte AudioSpecificConfig for AAC-LC: object type, sampling
     * frequency index, channel configuration, then three zero bits. This is
     * what MediaCodec emits as its codec-config buffer, what SDP carries as
     * `config=` and what MP4 stores as csd-0.
     */
    fun audioSpecificConfig(sampleRate: Int, channelCount: Int): ByteArray {
        val bits = (OBJECT_TYPE_LC shl 11) or (samplingFrequencyIndex(sampleRate) shl 7) or (channelCount shl 3)
        return byteArrayOf((bits shr 8).toByte(), bits.toByte())
    }

    /**
     * A 7-byte ADTS header (MPEG-4, no CRC, one raw data block) for an AAC-LC
     * frame of [payloadLength] bytes. MPEG transport streams carry AAC this way.
     */
    fun adtsHeader(payloadLength: Int, sampleRate: Int, channelCount: Int): ByteArray {
        val frameLength = payloadLength + ADTS_HEADER_LENGTH
        val frequency = samplingFrequencyIndex(sampleRate)
        val profile = OBJECT_TYPE_LC - 1
        return byteArrayOf(
            0xFF.toByte(),                                                       // syncword
            0xF1.toByte(),                                                       // syncword, MPEG-4, layer 0, no CRC
            ((profile shl 6) or (frequency shl 2) or (channelCount shr 2)).toByte(),
            (((channelCount and 0x3) shl 6) or (frameLength shr 11)).toByte(),
            ((frameLength shr 3) and 0xFF).toByte(),
            (((frameLength and 0x7) shl 5) or 0x1F).toByte(),                    // + buffer fullness 0x7FF (VBR)
            0xFC.toByte()                                                        // buffer fullness, one raw data block
        )
    }
}
