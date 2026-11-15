package com.lensdaemon.camera

import android.util.Size
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.encoder.VideoCodec
import com.lensdaemon.output.SegmentDuration
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber

/**
 * The phone's stream defaults: the settings used when nobody gives any, by the
 * phone's Start Stream button, kiosk auto-start at boot and the restart after
 * Android stopped the app. [CameraService] keeps them in preferences; they
 * follow the settings of each successful start and can be set through
 * PUT /api/config {"defaults": {...}}.
 *
 * Plain values, so the JSON round trip and the checks run in JVM unit tests;
 * [toEncoderConfig] and [segmentDuration] turn them into the app's own types.
 *
 * JSON: {"codec": "H264", "width": 1920, "height": 1080, "frameRate": 30,
 * "bitrate": 4000000, "rtspPort": 8554, "segmentMinutes": 5}
 */
data class StreamDefaults(
    val codec: VideoCodec = VideoCodec.H264,
    val width: Int = 1920,
    val height: Int = 1080,
    val frameRate: Int = 30,
    val bitrateBps: Int = 4_000_000,
    val rtspPort: Int = 8554,
    /** Length of each recording file in minutes; 0 records one file. */
    val segmentMinutes: Int = 5
) {
    /** These defaults as JSON, with the keys shown on the class. */
    fun toJson(): JSONObject = JSONObject()
        .put(KEY_CODEC, codec.name)
        .put(KEY_WIDTH, width)
        .put(KEY_HEIGHT, height)
        .put(KEY_FRAME_RATE, frameRate)
        .put(KEY_BITRATE, bitrateBps)
        .put(KEY_RTSP_PORT, rtspPort)
        .put(KEY_SEGMENT_MINUTES, segmentMinutes)

    /** What is wrong with these values, or null when they can be used. */
    fun validationError(): String? = when {
        !isValidWidth(width) -> "width must be an even number from $MIN_WIDTH to $MAX_WIDTH"
        !isValidHeight(height) -> "height must be an even number from $MIN_HEIGHT to $MAX_HEIGHT"
        frameRate !in FRAME_RATES -> "frameRate must be from ${FRAME_RATES.first} to ${FRAME_RATES.last}"
        bitrateBps !in BITRATE_RANGE -> "bitrate must be from ${BITRATE_RANGE.first} to ${BITRATE_RANGE.last} bits per second"
        rtspPort !in RTSP_PORTS -> "rtspPort must be from ${RTSP_PORTS.first} to ${RTSP_PORTS.last}"
        segmentMinutes !in SEGMENT_MINUTES -> "segmentMinutes must be 0 (one file), 1, 5, 15, 30 or 60"
        else -> null
    }

    /** These settings as an encoder configuration; everything else keeps its default. */
    fun toEncoderConfig(): EncoderConfig = EncoderConfig(
        codec = codec,
        resolution = Size(width, height),
        bitrateBps = bitrateBps,
        frameRate = frameRate
    )

    /** The recording segment length; 5 minutes when [segmentMinutes] is not a length the recorder offers. */
    fun segmentDuration(): SegmentDuration =
        SegmentDuration.entries.find { it.minutes == segmentMinutes } ?: SegmentDuration.FIVE_MINUTES

    companion object {
        private const val KEY_CODEC = "codec"
        private const val KEY_WIDTH = "width"
        private const val KEY_HEIGHT = "height"
        private const val KEY_FRAME_RATE = "frameRate"
        private const val KEY_BITRATE = "bitrate"
        private const val KEY_RTSP_PORT = "rtspPort"
        private const val KEY_SEGMENT_MINUTES = "segmentMinutes"

        private const val MIN_WIDTH = 160
        private const val MAX_WIDTH = 3840
        private const val MIN_HEIGHT = 120
        private const val MAX_HEIGHT = 2160
        private val FRAME_RATES = 1..60

        /**
         * Bitrates the app accepts, in bits per second: for the saved defaults,
         * PUT /api/config and /api/encoder/capabilities (one definition for all three).
         */
        val BITRATE_RANGE = 100_000..50_000_000
        private val RTSP_PORTS = 1024..65535
        private val SEGMENT_MINUTES = listOf(0, 1, 5, 15, 30, 60)

        /**
         * Defaults read back from [toJson]'s text. Each setting that is missing
         * or invalid falls back to its built-in value on its own, except width
         * and height, which fall back together so a size is never half saved and
         * half default. Null or unreadable text gives the built-in defaults.
         */
        fun fromJson(text: String?): StreamDefaults {
            if (text.isNullOrBlank()) return StreamDefaults()
            val json = try {
                JSONObject(text)
            } catch (e: JSONException) {
                Timber.w(e, "Saved stream defaults are unreadable; using the built-in ones")
                return StreamDefaults()
            }
            val builtIn = StreamDefaults()
            val width = json.intOrNull(KEY_WIDTH, ::isValidWidth)
            val height = json.intOrNull(KEY_HEIGHT, ::isValidHeight)
            val size = if (width != null && height != null) width to height else builtIn.width to builtIn.height
            return StreamDefaults(
                codec = codecNamed(json.optString(KEY_CODEC)) ?: builtIn.codec,
                width = size.first,
                height = size.second,
                frameRate = json.intOrNull(KEY_FRAME_RATE) { it in FRAME_RATES } ?: builtIn.frameRate,
                bitrateBps = json.intOrNull(KEY_BITRATE) { it in BITRATE_RANGE } ?: builtIn.bitrateBps,
                rtspPort = json.intOrNull(KEY_RTSP_PORT) { it in RTSP_PORTS } ?: builtIn.rtspPort,
                segmentMinutes = json.intOrNull(KEY_SEGMENT_MINUTES) { it in SEGMENT_MINUTES } ?: builtIn.segmentMinutes
            )
        }

        /** Defaults with [config]'s codec, size, frame rate and bitrate, plus [rtspPort] and [segmentMinutes]. */
        fun from(config: EncoderConfig, rtspPort: Int, segmentMinutes: Int): StreamDefaults = StreamDefaults(
            codec = config.codec,
            width = config.width,
            height = config.height,
            frameRate = config.frameRate,
            bitrateBps = config.bitrateBps,
            rtspPort = rtspPort,
            segmentMinutes = segmentMinutes
        )

        private fun isValidWidth(width: Int): Boolean = width in MIN_WIDTH..MAX_WIDTH && width % 2 == 0

        private fun isValidHeight(height: Int): Boolean = height in MIN_HEIGHT..MAX_HEIGHT && height % 2 == 0

        /** The codec called [name] (any letter case), or null. */
        private fun codecNamed(name: String): VideoCodec? =
            VideoCodec.entries.find { it.name.equals(name, ignoreCase = true) }

        /** The whole number under [key] when it passes [valid]; null when missing, not a whole number or invalid. */
        private fun JSONObject.intOrNull(key: String, valid: (Int) -> Boolean): Int? {
            val value = (opt(key) as? Number)?.toDouble() ?: return null
            val whole = value == Math.rint(value) && value >= Int.MIN_VALUE && value <= Int.MAX_VALUE
            return value.toInt().takeIf { whole && valid(it) }
        }
    }
}
