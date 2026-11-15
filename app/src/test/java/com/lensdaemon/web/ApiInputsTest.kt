package com.lensdaemon.web

import com.lensdaemon.camera.StreamDefaults
import com.lensdaemon.encoder.VideoCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiInputsTest {

    private fun json(text: String) = JSONObject(text)

    // ==================== finiteNumber ====================

    @Test
    fun `finiteNumber takes numbers and numeric text`() {
        assertEquals(2.5, ApiInputs.finiteNumber(json("""{"zoom": 2.5}"""), "zoom")!!, 0.0)
        assertEquals(2.5, ApiInputs.finiteNumber(json("""{"zoom": "2.5"}"""), "zoom")!!, 0.0)
        assertEquals(3.0, ApiInputs.finiteNumber(json("""{"zoom": 3}"""), "zoom")!!, 0.0)
        assertEquals(-1.0, ApiInputs.finiteNumber(json("""{"zoom": " -1 "}"""), "zoom")!!, 0.0)
    }

    @Test
    fun `finiteNumber refuses what is not a finite number`() {
        assertNull(ApiInputs.finiteNumber(json("""{"zoom": "NaN"}"""), "zoom"))
        assertNull(ApiInputs.finiteNumber(json("""{"zoom": "Infinity"}"""), "zoom"))
        assertNull(ApiInputs.finiteNumber(json("""{"zoom": "-Infinity"}"""), "zoom"))
        assertNull(ApiInputs.finiteNumber(json("""{"zoom": "1e400"}"""), "zoom"))
        assertNull(ApiInputs.finiteNumber(json("""{"zoom": "abc"}"""), "zoom"))
        assertNull(ApiInputs.finiteNumber(json("""{"zoom": ""}"""), "zoom"))
        assertNull(ApiInputs.finiteNumber(json("""{"zoom": null}"""), "zoom"))
        assertNull(ApiInputs.finiteNumber(json("""{"zoom": true}"""), "zoom"))
        assertNull(ApiInputs.finiteNumber(json("""{"zoom": [2]}"""), "zoom"))
        assertNull(ApiInputs.finiteNumber(json("""{}"""), "zoom"))
        assertNull(ApiInputs.finiteNumber(null, "zoom"))
    }

    // ==================== int / intInRange ====================

    @Test
    fun `int takes whole numbers only`() {
        assertEquals(-2, ApiInputs.int(json("""{"ev": -2}"""), "ev"))
        assertEquals(3, ApiInputs.int(json("""{"ev": 3.0}"""), "ev"))
        assertEquals(4, ApiInputs.int(json("""{"ev": "4"}"""), "ev"))
        assertNull(ApiInputs.int(json("""{"ev": 1.5}"""), "ev"))
        assertNull(ApiInputs.int(json("""{"ev": "NaN"}"""), "ev"))
        assertNull(ApiInputs.int(json("""{"ev": 3000000000}"""), "ev"))
        assertNull(ApiInputs.int(json("""{}"""), "ev"))
    }

    @Test
    fun `intInRange keeps to its bounds, both included`() {
        val range = ApiInputs.BITRATE_RANGE
        assertEquals(100_000, ApiInputs.intInRange(json("""{"bitrate": 100000}"""), "bitrate", range))
        assertEquals(50_000_000, ApiInputs.intInRange(json("""{"bitrate": 50000000}"""), "bitrate", range))
        assertNull(ApiInputs.intInRange(json("""{"bitrate": 99999}"""), "bitrate", range))
        assertNull(ApiInputs.intInRange(json("""{"bitrate": 50000001}"""), "bitrate", range))
        assertNull(ApiInputs.intInRange(json("""{"bitrate": 0}"""), "bitrate", range))
        assertNull(ApiInputs.intInRange(json("""{"bitrate": "fast"}"""), "bitrate", range))
    }

    // ==================== zoom ====================

    @Test
    fun `zoom takes zoom, or level when there is no zoom`() {
        assertEquals(2.5, ApiInputs.zoom(json("""{"zoom": 2.5}"""))!!, 0.0)
        assertEquals(2.5, ApiInputs.zoom(json("""{"level": 2.5}"""))!!, 0.0)
        assertEquals(3.0, ApiInputs.zoom(json("""{"zoom": 3, "level": 2}"""))!!, 0.0)
        // A bad zoom is not covered for by a level
        assertNull(ApiInputs.zoom(json("""{"zoom": "abc", "level": 2}""")))
        assertNull(ApiInputs.zoom(json("""{"level": "NaN"}""")))
        assertNull(ApiInputs.zoom(json("""{}""")))
        assertNull(ApiInputs.zoom(null))
    }

    // ==================== mergeStreamDefaults ====================

    @Test
    fun `mergeStreamDefaults applies only the settings given`() {
        val current = StreamDefaults()
        val (merged, error) = ApiInputs.mergeStreamDefaults(current, json("""{"width": 1280, "height": 720, "codec": "h265"}"""))
        assertNull(error)
        assertEquals(current.copy(width = 1280, height = 720, codec = VideoCodec.H265), merged)

        val (more, moreError) = ApiInputs.mergeStreamDefaults(
            merged!!,
            json("""{"frameRate": 24, "bitrate": "2000000", "rtspPort": 8555, "segmentMinutes": 0, "unknown": 1}""")
        )
        assertNull(moreError)
        assertEquals(merged.copy(frameRate = 24, bitrateBps = 2_000_000, rtspPort = 8555, segmentMinutes = 0), more)
    }

    @Test
    fun `mergeStreamDefaults with nothing given keeps the defaults`() {
        val current = StreamDefaults(width = 3840, height = 2160)
        assertEquals(current to null, ApiInputs.mergeStreamDefaults(current, json("{}")))
    }

    @Test
    fun `mergeStreamDefaults refuses bad settings, naming them`() {
        val current = StreamDefaults()
        assertRefused("defaults.codec", ApiInputs.mergeStreamDefaults(current, json("""{"codec": "VP9"}""")))
        assertRefused("defaults.codec", ApiInputs.mergeStreamDefaults(current, json("""{"codec": 264}""")))
        assertRefused("defaults.width", ApiInputs.mergeStreamDefaults(current, json("""{"width": 1281}""")))
        assertRefused("defaults.width", ApiInputs.mergeStreamDefaults(current, json("""{"width": "wide"}""")))
        assertRefused("defaults.height", ApiInputs.mergeStreamDefaults(current, json("""{"height": 4320}""")))
        assertRefused("defaults.frameRate", ApiInputs.mergeStreamDefaults(current, json("""{"frameRate": 120}""")))
        assertRefused("defaults.bitrate", ApiInputs.mergeStreamDefaults(current, json("""{"bitrate": 1000}""")))
        assertRefused("defaults.rtspPort", ApiInputs.mergeStreamDefaults(current, json("""{"rtspPort": 80}""")))
        assertRefused("defaults.segmentMinutes", ApiInputs.mergeStreamDefaults(current, json("""{"segmentMinutes": 7}""")))
        assertRefused("defaults", ApiInputs.mergeStreamDefaults(current, null))
    }

    // ==================== configChange ====================

    @Test
    fun `configChange reads every setting a PUT config can carry`() {
        val body = json("""{"bitrate": 6000000, "zoom": 2.0, "exposureCompensation": -2, "defaults": {"frameRate": 25}}""")
        val (change, error) = ApiInputs.configChange(body, StreamDefaults())
        assertNull(error)
        assertEquals(ConfigChange(6_000_000, 2.0, -2, StreamDefaults(frameRate = 25)), change)
    }

    @Test
    fun `configChange leaves out what was not sent`() {
        val (change, error) = ApiInputs.configChange(json("""{"zoom": "1.5"}"""), StreamDefaults())
        assertNull(error)
        assertEquals(ConfigChange(zoom = 1.5), change)
    }

    @Test
    fun `configChange refuses the whole request for one bad setting`() {
        val defaults = StreamDefaults()
        val badBitrate = ApiInputs.configChange(json("""{"bitrate": 12.5, "zoom": 2}"""), defaults)
        assertEquals(null to ApiInputs.BITRATE_ERROR, badBitrate)
        assertEquals(
            "bitrate must be a whole number of bits per second between 100000 and 50000000",
            ApiInputs.BITRATE_ERROR
        )
        assertEquals(null to ApiInputs.ZOOM_ERROR, ApiInputs.configChange(json("""{"zoom": "NaN"}"""), defaults))
        assertEquals(
            null to ApiInputs.EXPOSURE_ERROR,
            ApiInputs.configChange(json("""{"exposureCompensation": 0.5}"""), defaults)
        )
        assertRefused("defaults.width", ApiInputs.configChange(json("""{"zoom": 2, "defaults": {"width": 7}}"""), defaults))
        assertRefused("defaults", ApiInputs.configChange(json("""{"defaults": "1080p"}"""), defaults))
    }

    private fun assertRefused(subject: String, result: Pair<Any?, String?>) {
        assertNull("refused: $result", result.first)
        val error = result.second
        assertNotNull(error)
        assertTrue("'$error' should start with '$subject'", error!!.startsWith(subject))
    }
}
