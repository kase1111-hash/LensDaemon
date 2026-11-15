package com.lensdaemon.web

import fi.iki.elonen.NanoHTTPD.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrossSiteGuardTest {

    private fun refusal(method: Method, origin: String?, host: String?): String? {
        val headers = buildMap {
            origin?.let { put("origin", it) }
            host?.let { put("host", it) }
        }
        return CrossSiteGuard.refusal(method, headers)
    }

    @Test
    fun `a page of the phone itself may write`() {
        assertNull(refusal(Method.POST, "http://192.168.1.50:8080", "192.168.1.50:8080"))
        assertNull(refusal(Method.PUT, "http://192.168.1.50:8080", "192.168.1.50:8080"))
        assertNull(refusal(Method.DELETE, "http://192.168.1.50:8080", "192.168.1.50:8080"))
    }

    @Test
    fun `a page of another site may not`() {
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.POST, "http://evil.example", "192.168.1.50:8080"))
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.PUT, "http://evil.example", "192.168.1.50:8080"))
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.DELETE, "http://evil.example", "192.168.1.50:8080"))
        assertEquals("Cross-site request refused", CrossSiteGuard.REFUSAL)
    }

    @Test
    fun `the opaque origin null is refused`() {
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.POST, "null", "192.168.1.50:8080"))
    }

    @Test
    fun `a request without Origin passes, as from curl or a script`() {
        assertNull(refusal(Method.POST, null, "192.168.1.50:8080"))
        assertNull(refusal(Method.POST, null, null))
    }

    @Test
    fun `reads are never refused`() {
        assertNull(refusal(Method.GET, "http://evil.example", "192.168.1.50:8080"))
        assertNull(refusal(Method.HEAD, "http://evil.example", "192.168.1.50:8080"))
        assertNull(refusal(Method.OPTIONS, "null", "192.168.1.50:8080"))
    }

    @Test
    fun `the same host on another port is another origin`() {
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.POST, "http://192.168.1.50:9000", "192.168.1.50:8080"))
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.POST, "http://192.168.1.50:8080", "192.168.1.50"))
    }

    @Test
    fun `IPv6 literals keep their brackets`() {
        assertNull(refusal(Method.POST, "http://[fe80::1]:8080", "[fe80::1]:8080"))
        assertNull(refusal(Method.POST, "http://[fe80::1]", "[fe80::1]"))
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.POST, "http://[fe80::2]:8080", "[fe80::1]:8080"))
        // Without brackets an IPv6 Host header can't be read
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.POST, "http://[fe80::1]", "fe80::1"))
    }

    @Test
    fun `a missing port is the scheme's default port`() {
        assertNull(refusal(Method.POST, "http://phone", "phone:80"))
        assertNull(refusal(Method.POST, "http://phone:80", "phone"))
        assertNull(refusal(Method.POST, "http://phone", "phone"))
        assertNull(refusal(Method.POST, "https://phone", "phone"))
        assertNull(refusal(Method.POST, "https://phone", "phone:443"))
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.POST, "https://phone", "phone:80"))
    }

    @Test
    fun `host names compare without regard to case`() {
        assertNull(refusal(Method.POST, "HTTP://LensDaemon.local:8080", "lensdaemon.LOCAL:8080"))
    }

    @Test
    fun `odd origins and hosts are refused`() {
        val host = "192.168.1.50:8080"
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.POST, "file://", host))
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.POST, "chrome-extension://abcdef", host))
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.POST, "http://", host))
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.POST, "http://user@192.168.1.50:8080", host))
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.POST, "http://192.168.1.50:80800", host))
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.POST, "http://192.168.1.50:8080", null))
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.POST, "http://192.168.1.50:8080", ""))
        assertEquals(CrossSiteGuard.REFUSAL, refusal(Method.POST, "http://192.168.1.50:8080", ":8080"))
    }

    @Test
    fun `a path after the origin is ignored`() {
        assertTrue(CrossSiteGuard.sameOrigin("http://phone:8080/", "phone:8080"))
        assertFalse(CrossSiteGuard.sameOrigin("http://phone:8080/", "other:8080"))
    }
}
