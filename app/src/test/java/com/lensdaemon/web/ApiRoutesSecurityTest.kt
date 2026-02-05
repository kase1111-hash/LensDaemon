package com.lensdaemon.web

import com.lensdaemon.web.handlers.ApiHandlerUtils
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Tests for security-related functionality in API route handlers.
 *
 * Tests the public sanitizeFileName and validateFileInDirectory methods
 * in ApiHandlerUtils which protect against path traversal attacks.
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
}
