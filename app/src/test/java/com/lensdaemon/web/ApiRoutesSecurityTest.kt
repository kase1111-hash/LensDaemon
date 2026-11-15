package com.lensdaemon.web

import com.lensdaemon.web.handlers.ApiHandlerUtils
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

/**
 * Tests for security-related functionality in API route handlers.
 *
 * Tests the public sanitizeFileName and validateFileInDirectory methods
 * in ApiHandlerUtils which protect against path traversal attacks, and the
 * order of the checks every request meets (ApiRoutes.admission): the rate
 * limit before the token, so wrong tokens count against the limit.
 */
class ApiRoutesSecurityTest {

    private fun callSanitize(fileName: String): String? {
        return ApiHandlerUtils.sanitizeFileName(fileName)
    }

    private fun validateFileInDir(file: File, directory: File): Boolean {
        return ApiHandlerUtils.validateFileInDirectory(file, directory)
    }

    // ==================== sanitizeFileName Tests ====================

    @Test
    fun `sanitize rejects empty filename`() {
        assertNull(callSanitize(""))
    }

    @Test
    fun `sanitize rejects path traversal with dot-dot`() {
        assertNull(callSanitize("../secret"))
        assertNull(callSanitize("../../etc/passwd"))
        assertNull(callSanitize(".."))
        assertNull(callSanitize("test/../secret"))
    }

    @Test
    fun `sanitize rejects forward slash`() {
        assertNull(callSanitize("path/to/file"))
        assertNull(callSanitize("/etc/passwd"))
    }

    @Test
    fun `sanitize rejects backslash`() {
        assertNull(callSanitize("path\\to\\file"))
        assertNull(callSanitize("..\\secret"))
    }

    @Test
    fun `sanitize rejects null bytes`() {
        assertNull(callSanitize("file\u0000.txt"))
    }

    @Test
    fun `sanitize rejects special characters`() {
        assertNull(callSanitize("file;rm -rf.txt"))
        assertNull(callSanitize("file\$HOME.txt"))
        assertNull(callSanitize("file`cmd`.txt"))
        assertNull(callSanitize("file|pipe.txt"))
        assertNull(callSanitize("file>redirect.txt"))
        assertNull(callSanitize("file<input.txt"))
        assertNull(callSanitize("file&background.txt"))
    }

    @Test
    fun `sanitize accepts valid filenames`() {
        assertEquals("script.txt", callSanitize("script.txt"))
        assertEquals("my-script.txt", callSanitize("my-script.txt"))
        assertEquals("my_script.txt", callSanitize("my_script.txt"))
        assertEquals("Script 1.txt", callSanitize("Script 1.txt"))
        assertEquals("test123.txt", callSanitize("test123.txt"))
    }

    @Test
    fun `sanitize trims whitespace`() {
        assertEquals("script.txt", callSanitize("  script.txt  "))
    }

    @Test
    fun `sanitize accepts filenames with dots`() {
        assertEquals("my.script.v2.txt", callSanitize("my.script.v2.txt"))
    }

    // ==================== validateFileInDirectory Tests ====================

    @Test
    fun `validate accepts file within directory`() {
        val dir = File("/tmp/scripts")
        val file = File("/tmp/scripts/test.txt")
        assertTrue(validateFileInDir(file, dir))
    }

    @Test
    fun `validate rejects file outside directory`() {
        val dir = File("/tmp/scripts")
        val file = File("/tmp/other/test.txt")
        assertFalse(validateFileInDir(file, dir))
    }

    @Test
    fun `validate rejects traversal to parent`() {
        val dir = File("/tmp/scripts")
        val file = File("/tmp/scripts/../other/test.txt")
        assertFalse(validateFileInDir(file, dir))
    }

    // ==================== Combined Attack Scenarios ====================

    @Test
    fun `traversal via URL encoded dots is blocked`() {
        // URL decoding happens before our function is called
        assertNull(callSanitize("..%2f..%2fetc%2fpasswd"))
        assertNull(callSanitize("../../../etc/passwd"))
    }

    @Test
    fun `double encoding attack is blocked`() {
        assertNull(callSanitize("..%252f..%252fetc%252fpasswd"))
    }

    @Test
    fun `unicode normalization attack is blocked`() {
        // Various unicode tricks that could bypass path validation
        assertNull(callSanitize("\u2025/etc/passwd"))  // TWO DOT LEADER
    }

    // ==================== Error bodies ====================

    @Test
    fun `errorJson stays valid JSON whatever the message holds`() {
        val message = "Invalid destination: \"S3\", \\\\server\\share\nnext line </script> café"
        val response = ApiHandlerUtils.errorJson(Status.BAD_REQUEST, message)
        assertEquals(Status.BAD_REQUEST, response.status)
        val body = JSONObject(response.data.readBytes().toString(Charsets.UTF_8))
        assertEquals(message, body.getString("error"))
        assertEquals(1, body.length())
    }

    @Test
    fun `serviceUnavailable names the service`() {
        val response = ApiHandlerUtils.serviceUnavailable("Camera service")
        assertEquals(Status.SERVICE_UNAVAILABLE, response.status)
        val body = JSONObject(response.data.readBytes().toString(Charsets.UTF_8))
        assertEquals("Camera service not available", body.getString("error"))
    }

    // ==================== Rate limit, then token ====================

    /** A request for [path] from [remoteAddress], carrying [authorization] as its Authorization header when given. */
    private class StubSession(
        private val path: String,
        private val remoteAddress: String,
        authorization: String? = null,
        private val requestMethod: NanoHTTPD.Method = NanoHTTPD.Method.GET
    ) : NanoHTTPD.IHTTPSession {
        private val requestHeaders: Map<String, String> =
            authorization?.let { mapOf("authorization" to it) } ?: emptyMap()

        override fun execute() = Unit
        override fun getCookies(): NanoHTTPD.CookieHandler? = null
        override fun getHeaders(): Map<String, String> = requestHeaders
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getMethod(): NanoHTTPD.Method = requestMethod
        @Deprecated("Deprecated in NanoHTTPD", ReplaceWith("parameters"))
        override fun getParms(): Map<String, String> = emptyMap()
        override fun getParameters(): Map<String, List<String>> = emptyMap()
        override fun getQueryParameterString(): String? = null
        override fun getUri(): String = path
        override fun parseBody(files: MutableMap<String, String>?) = Unit
        override fun getRemoteIpAddress(): String = remoteAddress
        override fun getRemoteHostName(): String = remoteAddress
    }

    private fun statusOf(response: NanoHTTPD.Response?): Int? = response?.status?.requestStatus

    private fun errorOf(response: NanoHTTPD.Response): String =
        JSONObject(response.data.readBytes().toString(Charsets.UTF_8)).getString("error")

    @Test
    fun `wrong tokens use up the rate limit, so guessing the token is slowed down`() {
        val limiter = RateLimiter(maxTokens = 3, refillPerSecond = 0.001)
        fun ask(token: String, address: String = "192.168.1.66") =
            statusOf(ApiRoutes.admission(StubSession("/api/config", address, "Bearer $token"), limiter, "secret"))

        assertEquals(listOf(401, 401, 401), (1..3).map { ask("guess-$it") })
        assertEquals("the fourth guess meets the limit", 429, ask("guess-4"))
        // The limit comes first: from that address even the right token waits now
        assertEquals(429, ask("secret"))
        // Other clients are not affected
        assertNull(ask("secret", address = "192.168.1.20"))
        assertEquals(401, ask("guess-5", address = "192.168.1.21"))
    }

    @Test
    fun `the 401 says what is missing and the 429 when to come back`() {
        val limiter = RateLimiter(maxTokens = 1, refillPerSecond = 0.001)
        val session = StubSession("/api/kiosk/disable", "10.0.0.9", requestMethod = NanoHTTPD.Method.POST)

        val unauthorized = checkNotNull(ApiRoutes.admission(session, limiter, "secret"))
        assertEquals(401, statusOf(unauthorized))
        assertEquals("Authentication required", errorOf(unauthorized))

        val limited = checkNotNull(ApiRoutes.admission(session, limiter, "secret"))
        assertEquals(429, statusOf(limited))
        val retryAfter = JSONObject(limited.data.readBytes().toString(Charsets.UTF_8)).getInt("retryAfter")
        assertTrue(retryAfter > 0)
        assertEquals(retryAfter.toString(), limited.getHeader("Retry-After"))
    }

    @Test
    fun `without a token only the limit applies, and public reads need no token`() {
        val limiter = RateLimiter(maxTokens = 2, refillPerSecond = 0.001)
        assertNull(ApiRoutes.admission(StubSession("/api/config", "10.0.0.7"), limiter, null))
        assertNull(ApiRoutes.admission(StubSession("/api/status", "10.0.0.7"), limiter, "secret"))
        assertEquals(429, statusOf(ApiRoutes.admission(StubSession("/api/status", "10.0.0.7"), limiter, "secret")))
        assertNull(ApiRoutes.admission(StubSession("/api/config", "10.0.0.7"), null, null))
    }
}
