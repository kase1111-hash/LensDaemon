package com.lensdaemon.web.handlers

import com.lensdaemon.web.WebServer
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import java.io.File

/**
 * Shared utilities for API route handlers.
 */
object ApiHandlerUtils {

    /**
     * Validate a filename is safe (no path traversal).
     * Returns the sanitized filename or null if invalid.
     */
    fun sanitizeFileName(fileName: String): String? {
        if (fileName.isEmpty()) return null
        if (fileName.contains("/") || fileName.contains("\\") ||
            fileName.contains("..") || fileName.contains("\u0000")) {
            return null
        }
        val sanitized = fileName.trim()
        if (!sanitized.matches(Regex("^[a-zA-Z0-9._\\- ]+$"))) {
            return null
        }
        return sanitized
    }

    /**
     * Validate that a resolved file is within the expected directory.
     */
    fun validateFileInDirectory(file: File, directory: File): Boolean {
        return file.canonicalPath.startsWith(directory.canonicalPath + File.separator) ||
               file.canonicalPath == directory.canonicalPath
    }

    fun jsonResponse(status: Status, json: String): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(status, WebServer.MIME_JSON, json)
    }

    fun successJson(json: String): NanoHTTPD.Response {
        return jsonResponse(Status.OK, json)
    }

    fun errorJson(status: Status, message: String): NanoHTTPD.Response {
        return jsonResponse(status, """{"error": "$message"}""")
    }

    fun serviceUnavailable(serviceName: String): NanoHTTPD.Response {
        return errorJson(Status.SERVICE_UNAVAILABLE, "$serviceName not available")
    }

    fun bodyRequired(): NanoHTTPD.Response {
        return errorJson(Status.BAD_REQUEST, "Request body required")
    }
}
