package com.lensdaemon.web.handlers

import com.lensdaemon.storage.RecordingFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RecordingRequests]: which names and paths reach a
 * recording, and the download header.
 */
class RecordingRequestsTest {

    private val motoName = "LensDaemon_moto_g(7)_power_20261114_090000_seg001.mp4"
    private val moto = recording("/dir/$motoName")
    private val plain = recording("/dir/x.mp4")
    private val listed = listOf(moto, plain)

    private fun recording(path: String) = RecordingFile(
        path = path,
        name = path.substringAfterLast('/'),
        sizeBytes = 1L,
        lastModifiedMs = 0L
    )

    // ==================== isPlainFileName ====================

    @Test
    fun `a name with the phone model's punctuation is a plain name`() {
        assertTrue(RecordingRequests.isPlainFileName(motoName))
    }

    @Test
    fun `a name with Arabic-Indic digits is a plain name`() {
        // What String.format wrote for the segment number in such a locale
        assertTrue(RecordingRequests.isPlainFileName("LensDaemon_Pixel_7_20261114_090000_seg٠٠١.mp4"))
    }

    @Test
    fun `empty names, paths and NUL are refused`() {
        listOf("", "a/b", "a\\b", "../x", "..", "x\u0000").forEach { name ->
            assertFalse("'$name' should be refused", RecordingRequests.isPlainFileName(name))
        }
    }

    // ==================== find ====================

    @Test
    fun `find by path compares canonical paths`() {
        assertSame(plain, RecordingRequests.find(listed, "/dir/./x.mp4", null))
        assertSame(plain, RecordingRequests.find(listed, "/dir/sub/../x.mp4", null))
        assertSame(moto, RecordingRequests.find(listed, "/dir/$motoName", null))
    }

    @Test
    fun `find refuses app files that are not listed recordings`() {
        assertNull(RecordingRequests.find(listed, "../shared_prefs/lensdaemon_kiosk_config.xml", null))
        assertNull(RecordingRequests.find(listed, "/data/user/0/com.lensdaemon/shared_prefs/x.xml", null))
        assertNull(RecordingRequests.find(listed, null, "../shared_prefs/lensdaemon_kiosk_config.xml"))
        assertNull(RecordingRequests.find(listed, "/dir/../x.mp4", null))
    }

    @Test
    fun `find by name needs the exact name of a listed recording`() {
        assertSame(moto, RecordingRequests.find(listed, null, motoName))
        assertSame(plain, RecordingRequests.find(listed, null, "x.mp4"))
        assertNull(RecordingRequests.find(listed, null, "X.MP4"))
        assertNull(RecordingRequests.find(listed, null, "x"))
        assertNull(RecordingRequests.find(listed, null, null))
    }

    @Test
    fun `a path, when given, decides over the name`() {
        assertNull(RecordingRequests.find(listed, "/elsewhere/x.mp4", "x.mp4"))
    }

    // ==================== isSameFile ====================

    @Test
    fun `isSameFile compares canonical paths and is false for a missing path`() {
        assertTrue(RecordingRequests.isSameFile("/dir/x.mp4", "/dir/./x.mp4"))
        assertFalse(RecordingRequests.isSameFile("/dir/x.mp4", "/dir/y.mp4"))
        assertFalse(RecordingRequests.isSameFile("/dir/x.mp4", null))
        assertFalse(RecordingRequests.isSameFile(null, null))
        assertFalse("a path that can't be resolved matches nothing", RecordingRequests.isSameFile("x\u0000", "x\u0000"))
    }

    // ==================== contentDisposition ====================

    @Test
    fun `contentDisposition escapes quotes and percent-encodes non-ASCII`() {
        assertEquals(
            "attachment; filename=\"clip__1__vid_o.mp4\"; filename*=UTF-8''clip%20%221%22%20vid%C3%A9o.mp4",
            RecordingRequests.contentDisposition("clip \"1\" vidéo.mp4")
        )
    }

    @Test
    fun `contentDisposition keeps the exact name in filename-star`() {
        assertEquals(
            "attachment; filename=\"LensDaemon_moto_g_7__power_20261114_090000_seg001.mp4\"; " +
                "filename*=UTF-8''LensDaemon_moto_g%287%29_power_20261114_090000_seg001.mp4",
            RecordingRequests.contentDisposition(motoName)
        )
        assertEquals("attachment; filename=\"a_b.mp4\"; filename*=UTF-8''a%2Ab.mp4", RecordingRequests.contentDisposition("a*b.mp4"))
    }
}
