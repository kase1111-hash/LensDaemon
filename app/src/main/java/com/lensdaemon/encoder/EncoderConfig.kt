package com.lensdaemon.encoder

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Size

/**
 * Video codec type for encoding
 */
enum class VideoCodec(val mimeType: String) {
    H264(MediaFormat.MIMETYPE_VIDEO_AVC),
    H265(MediaFormat.MIMETYPE_VIDEO_HEVC);

    companion object {
        fun fromMimeType(mimeType: String): VideoCodec? {
            return entries.find { it.mimeType == mimeType }
        }
    }
}

/**
 * H.264 profile levels
 */
enum class H264Profile(val value: Int) {
    BASELINE(MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline),
    MAIN(MediaCodecInfo.CodecProfileLevel.AVCProfileMain),
    HIGH(MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
}

/**
 * H.265/HEVC profile levels
 */
enum class H265Profile(val value: Int) {
    MAIN(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain),
    MAIN_10(MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10);
}

/**
 * Bitrate mode for encoding
 */
enum class BitrateMode(val value: Int) {
    /** Constant quality mode */
    CQ(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ),
    /** Variable bitrate */
    VBR(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR),
    /** Constant bitrate */
    CBR(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
}

/**
 * Encoder configuration data class
 */
data class EncoderConfig(
    /** Video codec to use */
    val codec: VideoCodec = VideoCodec.H264,

    /** Output resolution */
    val resolution: Size = Size(1920, 1080),

    /** Target bitrate in bits per second */
    val bitrateBps: Int = 4_000_000,

    /** Target frame rate */
    val frameRate: Int = 30,

    /** Keyframe interval in seconds */
    val keyframeIntervalSec: Int = 2,

    /** Bitrate mode */
    val bitrateMode: BitrateMode = BitrateMode.VBR,

    /** H.264 profile (ignored for H.265) */
    val h264Profile: H264Profile = H264Profile.HIGH,

    /** H.265 profile (ignored for H.264) */
    val h265Profile: H265Profile = H265Profile.MAIN,

    /** Maximum B-frames (0 for low latency) */
    val maxBFrames: Int = 0,

    /** Enable low latency mode for streaming */
    val lowLatency: Boolean = true,

    /** Color format (usually auto-selected) */
    val colorFormat: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
) {
    val width: Int get() = resolution.width
    val height: Int get() = resolution.height

    /** Calculated keyframe interval in frames */
    val keyframeIntervalFrames: Int get() = keyframeIntervalSec * frameRate

    /**
     * Create MediaFormat for this configuration
     */
    fun toMediaFormat(): MediaFormat {
        return MediaFormat.createVideoFormat(codec.mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, keyframeIntervalSec)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
            setInteger(MediaFormat.KEY_BITRATE_MODE, bitrateMode.value)

            // Set profile based on codec
            when (codec) {
                VideoCodec.H264 -> {
                    setInteger(MediaFormat.KEY_PROFILE, h264Profile.value)
                    // Level is typically auto-selected based on resolution/bitrate
                }
                VideoCodec.H265 -> {
                    setInteger(MediaFormat.KEY_PROFILE, h265Profile.value)
                }
            }

            // B-frames for non-streaming use cases
            if (maxBFrames > 0) {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, maxBFrames)
            }

            // Low latency mode for streaming
            if (lowLatency) {
                setInteger(MediaFormat.KEY_LATENCY, 0)
                // Prioritize realtime encoding
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
        }
    }

    companion object {
        /** 720p @ 2Mbps - Good for most streaming */
        val PRESET_720P = EncoderConfig(
            resolution = Size(1280, 720),
            bitrateBps = 2_000_000,
            frameRate = 30
        )

        /** 1080p @ 4Mbps - High quality streaming */
        val PRESET_1080P = EncoderConfig(
            resolution = Size(1920, 1080),
            bitrateBps = 4_000_000,
            frameRate = 30
        )

        /** 1080p @ 6Mbps @ 60fps - High quality/high framerate */
        val PRESET_1080P_60 = EncoderConfig(
            resolution = Size(1920, 1080),
            bitrateBps = 6_000_000,
            frameRate = 60
        )

        /** 4K @ 15Mbps - Maximum quality (device dependent) */
        val PRESET_4K = EncoderConfig(
            resolution = Size(3840, 2160),
            bitrateBps = 15_000_000,
            frameRate = 30
        )

        /** Low bandwidth preset for poor network conditions */
        val PRESET_LOW_BANDWIDTH = EncoderConfig(
            resolution = Size(640, 480),
            bitrateBps = 500_000,
            frameRate = 15
        )
    }
}

/**
 * Encoder state
 */
enum class EncoderState {
    IDLE,
    CONFIGURING,
    READY,
    ENCODING,
    STOPPING,
    ERROR
}

/**
 * Encoder error types
 */
sealed class EncoderError(val message: String) {
    data class ConfigurationError(val details: String) : EncoderError("Configuration failed: $details")
    data class CodecNotSupported(val codec: VideoCodec) : EncoderError("Codec not supported: ${codec.mimeType}")
    data class ResolutionNotSupported(val size: Size) : EncoderError("Resolution not supported: ${size.width}x${size.height}")
    data class EncodingError(val details: String) : EncoderError("Encoding error: $details")
    data class SurfaceError(val details: String) : EncoderError("Surface error: $details")
    data object Timeout : EncoderError("Encoder operation timed out")
}

/**
 * Encoded frame data with metadata
 */
data class EncodedFrame(
    /** Raw encoded data */
    val data: ByteArray,

    /** Presentation timestamp in microseconds */
    val presentationTimeUs: Long,

    /** Frame flags (keyframe, codec config, etc.) */
    val flags: Int,

    /** Frame size in bytes */
    val size: Int = data.size
) {
    /** Is this a keyframe (I-frame)? */
    val isKeyFrame: Boolean
        get() = (flags and android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0

    /** Is this codec configuration data (SPS/PPS for H.264)? */
    val isConfigFrame: Boolean
        get() = (flags and android.media.MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0

    /** Is this the end of stream? */
    val isEndOfStream: Boolean
        get() = (flags and android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EncodedFrame
        return data.contentEquals(other.data) &&
                presentationTimeUs == other.presentationTimeUs &&
                flags == other.flags
    }

    override fun hashCode(): Int {
        var result = data.contentHashCode()
        result = 31 * result + presentationTimeUs.hashCode()
        result = 31 * result + flags
        return result
    }
}

/**
 * Encoder capabilities for a specific codec
 */
data class EncoderCapabilities(
    val codec: VideoCodec,
    val codecName: String,
    val isHardwareAccelerated: Boolean,
    val supportedResolutions: List<Size>,
    val maxResolution: Size,
    val minResolution: Size,
    val supportedBitrateModes: List<BitrateMode>,
    val bitrateRange: IntRange,
    val supportedFrameRates: IntRange,
    val supportedProfiles: List<Int>
)

/**
 * Encoder statistics for monitoring
 */
data class EncoderStats(
    /** Total frames encoded */
    val framesEncoded: Long = 0,

    /** Frames dropped due to backpressure */
    val framesDropped: Long = 0,

    /** Current encoding bitrate (measured) */
    val currentBitrateBps: Long = 0,

    /** Average frame encoding time in ms */
    val avgEncodingTimeMs: Float = 0f,

    /** Total bytes encoded */
    val totalBytesEncoded: Long = 0,

    /** Encoding start time */
    val startTimeMs: Long = 0,

    /** Current frames per second (measured) */
    val currentFps: Float = 0f
) {
    /** Encoding duration in seconds */
    val durationSec: Float
        get() = if (startTimeMs > 0) (System.currentTimeMillis() - startTimeMs) / 1000f else 0f
}
