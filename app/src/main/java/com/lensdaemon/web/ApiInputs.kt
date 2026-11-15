package com.lensdaemon.web

import com.lensdaemon.camera.StreamDefaults
import com.lensdaemon.encoder.VideoCodec
import org.json.JSONObject

/**
 * A PUT /api/config request after its checks: what to change, with null for
 * what the request did not send.
 */
internal data class ConfigChange(
    /** The running encoder's bitrate from now on, in bits per second. */
    val bitrate: Int? = null,
    val zoom: Double? = null,
    /** In the lens's exposure steps. */
    val exposureCompensation: Int? = null,
    /** The phone's stream defaults with the request's settings applied. */
    val defaults: StreamDefaults? = null
)

/**
 * Reads the values in API request bodies strictly: a number has to be one, and
 * finite, so "NaN", "Infinity" or "abc" is answered with 400 instead of reaching
 * the camera (a NaN zoom once broke GET /api/status for every dashboard).
 * Numbers may also come as numeric text. Plain org.json, so it runs in JVM tests.
 */
internal object ApiInputs {

    /** Bitrates the encoder may be given, in bits per second (PUT /api/config, stream defaults). */
    val BITRATE_RANGE = 100_000..50_000_000

    const val ZOOM_ERROR = "zoom must be a finite number"
    const val EV_ERROR = "ev must be a whole number of the camera's exposure steps"
    const val EXPOSURE_ERROR = "exposureCompensation must be a whole number of the camera's exposure steps"
    val BITRATE_ERROR = "bitrate must be a whole number of bits per second between ${BITRATE_RANGE.first} and ${BITRATE_RANGE.last}"

    /** The whole-number stream defaults and how each is set. */
    private val INT_DEFAULTS: List<Pair<String, (StreamDefaults, Int) -> StreamDefaults>> = listOf(
        "width" to { d, v -> d.copy(width = v) },
        "height" to { d, v -> d.copy(height = v) },
        "frameRate" to { d, v -> d.copy(frameRate = v) },
        "bitrate" to { d, v -> d.copy(bitrateBps = v) },
        "rtspPort" to { d, v -> d.copy(rtspPort = v) },
        "segmentMinutes" to { d, v -> d.copy(segmentMinutes = v) }
    )

    /**
     * The number under [key]: a JSON number or numeric text. Null when [body]
     * or the key is missing, the value is JSON null or not a number, or it is
     * not finite.
     */
    fun finiteNumber(body: JSONObject?, key: String): Double? {
        val number = when (val value = body?.opt(key)) {
            is Number -> value.toDouble()
            is String -> value.trim().toDoubleOrNull()
            else -> null
        }
        return number?.takeIf { it.isFinite() }
    }

    /** The whole number under [key] (2 and 2.0 alike), or null as for [finiteNumber] and for fractions or values beyond Int. */
    fun int(body: JSONObject?, key: String): Int? {
        val number = finiteNumber(body, key) ?: return null
        val whole = number == Math.rint(number) && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE
        return if (whole) number.toInt() else null
    }

    /** The whole number under [key] when it is in [range], else null. */
    fun intInRange(body: JSONObject?, key: String, range: IntRange): Int? = int(body, key)?.takeIf { it in range }

    /**
     * The zoom a request asks for: "zoom", or "level" (as older docs had it)
     * when the body has no "zoom". Null when it is missing or not finite.
     */
    fun zoom(body: JSONObject?): Double? {
        val key = if (body != null && !body.has("zoom") && body.has("level")) "level" else "zoom"
        return finiteNumber(body, key)
    }

    /**
     * [current] with the settings in [json] applied: any of "codec" (H264 or
     * H265, any letter case), "width", "height", "frameRate", "bitrate",
     * "rtspPort" and "segmentMinutes"; other keys are ignored. The result is
     * checked as a whole ([StreamDefaults.validationError]).
     *
     * @param json the request's "defaults"; null when it is not a JSON object
     * @return the merged defaults and null, or null and why they are refused
     */
    fun mergeStreamDefaults(current: StreamDefaults, json: JSONObject?): Pair<StreamDefaults?, String?> {
        if (json == null) return null to "defaults must be a JSON object"
        var merged = current
        if (json.has("codec")) {
            val name = json.opt("codec") as? String
            val codec = VideoCodec.entries.find { it.name.equals(name?.trim(), ignoreCase = true) }
                ?: return null to "defaults.codec must be ${VideoCodec.entries.joinToString(" or ") { it.name }}"
            merged = merged.copy(codec = codec)
        }
        for ((key, set) in INT_DEFAULTS) {
            if (json.has(key)) {
                val value = int(json, key) ?: return null to "defaults.$key must be a whole number"
                merged = set(merged, value)
            }
        }
        val error = merged.validationError() ?: return merged to null
        return null to "defaults.$error"
    }

    /**
     * A PUT /api/config body, checked as a whole before anything is applied:
     * "bitrate" (a whole number in [BITRATE_RANGE]), "zoom" (finite),
     * "exposureCompensation" (whole) and "defaults" (merged into
     * [currentDefaults], see [mergeStreamDefaults]).
     *
     * @return the change and null, or null and why the request is refused
     */
    fun configChange(body: JSONObject, currentDefaults: StreamDefaults): Pair<ConfigChange?, String?> {
        val bitrate = if (body.has("bitrate")) {
            intInRange(body, "bitrate", BITRATE_RANGE) ?: return null to BITRATE_ERROR
        } else {
            null
        }
        val zoom = if (body.has("zoom")) {
            finiteNumber(body, "zoom") ?: return null to ZOOM_ERROR
        } else {
            null
        }
        val exposure = if (body.has("exposureCompensation")) {
            int(body, "exposureCompensation") ?: return null to EXPOSURE_ERROR
        } else {
            null
        }
        val defaults = if (body.has("defaults")) {
            val (merged, error) = mergeStreamDefaults(currentDefaults, body.optJSONObject("defaults"))
            merged ?: return null to error
        } else {
            null
        }
        return ConfigChange(bitrate, zoom, exposure, defaults) to null
    }
}
