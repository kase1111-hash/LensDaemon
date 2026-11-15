package com.lensdaemon.camera

import com.lensdaemon.encoder.VideoCodec
import com.lensdaemon.output.SegmentDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [StreamDefaults]: the JSON kept in preferences and the checks
 * PUT /api/config relies on. Uses no Android class (no EncoderConfig or Size).
 */
class StreamDefaultsTest {

    @Test
    fun `JSON round trip keeps every setting`() {
        val defaults = StreamDefaults(VideoCodec.H265, 1280, 720, 24, 2_500_000, 9554, 15)
        assertEquals(defaults, StreamDefaults.fromJson(defaults.toJson().toString()))
    }

    @Test
    fun `JSON uses the documented keys`() {
        val json = StreamDefaults().toJson()
        assertEquals("H264", json.getString("codec"))
        assertEquals(1920, json.getInt("width"))
        assertEquals(1080, json.getInt("height"))
        assertEquals(30, json.getInt("frameRate"))
        assertEquals(4_000_000, json.getInt("bitrate"))
        assertEquals(8554, json.getInt("rtspPort"))
        assertEquals(5, json.getInt("segmentMinutes"))
    }

    @Test
    fun `nothing saved or unreadable text gives the built-in defaults`() {
        assertEquals(StreamDefaults(), StreamDefaults.fromJson(null))
        assertEquals(StreamDefaults(), StreamDefaults.fromJson(""))
        assertEquals(StreamDefaults(), StreamDefaults.fromJson("garbage"))
        assertEquals(StreamDefaults(), StreamDefaults.fromJson("[1, 2]"))
    }

    @Test
    fun `one bad setting falls back on its own and keeps the others`() {
        val text = """{"codec":"h265","width":1280,"height":720,"frameRate":"fast",""" +
            """"bitrate":2000000,"rtspPort":9554,"segmentMinutes":15}"""
        assertEquals(StreamDefaults(VideoCodec.H265, 1280, 720, 30, 2_000_000, 9554, 15), StreamDefaults.fromJson(text))
    }

    @Test
    fun `out of range values fall back too`() {
        val text = """{"codec":"VP9","frameRate":0,"bitrate":10,"rtspPort":80,"segmentMinutes":7}"""
        assertEquals(StreamDefaults(), StreamDefaults.fromJson(text))
    }

    @Test
    fun `an invalid width resets the whole size, never half of it`() {
        val defaults = StreamDefaults.fromJson("""{"width":1281,"height":720,"frameRate":24}""")
        assertEquals(1920, defaults.width)
        assertEquals(1080, defaults.height)
        assertEquals(24, defaults.frameRate)
    }

    @Test
    fun `valid defaults pass validation`() {
        assertNull(StreamDefaults().validationError())
        assertNull(StreamDefaults(VideoCodec.H265, 3840, 2160, 60, 50_000_000, 65535, 0).validationError())
        assertNull(StreamDefaults(VideoCodec.H264, 160, 120, 1, 100_000, 1024, 60).validationError())
    }

    @Test
    fun `validation names the setting that is wrong`() {
        assertErrorMentions("width", StreamDefaults(width = 1281))
        assertErrorMentions("width", StreamDefaults(width = 4096))
        assertErrorMentions("height", StreamDefaults(height = 2161))
        assertErrorMentions("frameRate", StreamDefaults(frameRate = 0))
        assertErrorMentions("bitrate", StreamDefaults(bitrateBps = 10))
        assertErrorMentions("rtspPort", StreamDefaults(rtspPort = 80))
        assertErrorMentions("segmentMinutes", StreamDefaults(segmentMinutes = 7))
    }

    @Test
    fun `segment minutes map to the recorder's segment lengths`() {
        assertEquals(SegmentDuration.CONTINUOUS, StreamDefaults(segmentMinutes = 0).segmentDuration())
        assertEquals(SegmentDuration.FIFTEEN_MINUTES, StreamDefaults(segmentMinutes = 15).segmentDuration())
        assertEquals(SegmentDuration.FIVE_MINUTES, StreamDefaults(segmentMinutes = 7).segmentDuration())
    }

    private fun assertErrorMentions(setting: String, defaults: StreamDefaults) {
        val error = defaults.validationError()
        assertTrue("expected an error about $setting, got $error", error?.startsWith(setting) == true)
    }
}
