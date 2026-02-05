package com.lensdaemon.web

import android.content.Context
import android.os.Build
import com.lensdaemon.camera.CameraService
import com.lensdaemon.camera.LensType
import com.lensdaemon.director.DirectorManager
import com.lensdaemon.encoder.EncoderState
import com.lensdaemon.kiosk.KioskManager
import com.lensdaemon.storage.UploadService
import com.lensdaemon.thermal.ThermalGovernor
import com.lensdaemon.web.handlers.DirectorApiHandler
import com.lensdaemon.web.handlers.KioskApiHandler
import com.lensdaemon.web.handlers.StreamApiHandler
import com.lensdaemon.web.handlers.ThermalApiHandler
import com.lensdaemon.web.handlers.UploadApiHandler
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.ByteArrayInputStream

/**
 * REST API routes dispatcher for LensDaemon web interface.
 *
 * Routes are delegated to per-module handlers:
 * - [StreamApiHandler]   -> /api/stream/*, /api/rtsp/*, /api/recording/*, /api/recordings, /api/storage/*
 * - [UploadApiHandler]   -> /api/upload/*
 * - [ThermalApiHandler]  -> /api/thermal/*
 * - [KioskApiHandler]    -> /api/kiosk/*
 * - [DirectorApiHandler] -> /api/director/*
 *
 * Status, lens, camera, snapshot, config, and encoder routes remain inline.
 */
class ApiRoutes(
    private val context: Context
) {
    companion object {
        private const val TAG = "ApiRoutes"

        // Default preview resolution for tap-to-focus coordinate mapping
        private const val DEFAULT_PREVIEW_WIDTH = 1920
        private const val DEFAULT_PREVIEW_HEIGHT = 1080

        // HTTP 429 Too Many Requests (not in NanoHTTPD's Status enum)
        private val TOO_MANY_REQUESTS = object : NanoHTTPD.Response.IStatus {
            override fun getRequestStatus() = 429
            override fun getDescription() = "429 Too Many Requests"
        }

        // Read-only endpoints that don't require authentication
        private val PUBLIC_ENDPOINTS = setOf(
            "/api/status",
            "/api/device"
        )
    }

    // Service references (set by WebServerService)
    var cameraService: CameraService? = null
        set(value) {
            field = value
            streamHandler.cameraService = value
        }

    var uploadService: UploadService? = null
        set(value) {
            field = value
            uploadHandler.uploadService = value
        }

    var thermalGovernor: ThermalGovernor? = null
        set(value) {
            field = value
            thermalHandler.thermalGovernor = value
        }

    var kioskManager: KioskManager? = null
        set(value) {
            field = value
            kioskHandler.kioskManager = value
        }

    var directorManager: DirectorManager? = null
        set(value) {
            field = value
            directorHandler.directorManager = value
        }

    // Snapshot callback
    var onSnapshotRequest: (() -> ByteArray?)? = null

    // API authentication token (null = auth disabled for backwards compat)
    var apiToken: String? = null

    // Rate limiter (null = disabled)
    var rateLimiter: RateLimiter? = null

    // Delegated route handlers
    private val streamHandler = StreamApiHandler()
    private val uploadHandler = UploadApiHandler()
    private val thermalHandler = ThermalApiHandler()
    private val kioskHandler = KioskApiHandler()
    private val directorHandler = DirectorApiHandler(context)

    /**
     * Check API authentication.
     * Returns an error response if auth fails, null if auth passes.
     */
    private fun checkAuth(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val token = apiToken ?: return null  // Auth disabled

        val uri = session.uri
        // Allow public read-only endpoints without auth
        if (uri in PUBLIC_ENDPOINTS && session.method == NanoHTTPD.Method.GET) {
            return null
        }

        val authHeader = session.headers["authorization"] ?: ""
        val providedToken = if (authHeader.startsWith("Bearer ", ignoreCase = true)) {
            authHeader.substring(7).trim()
        } else {
            session.parms?.get("token") ?: ""
        }

        if (providedToken != token) {
            return NanoHTTPD.newFixedLengthResponse(
                Status.UNAUTHORIZED,
                WebServer.MIME_JSON,
                """{"error": "Authentication required", "hint": "Provide Bearer token in Authorization header"}"""
            )
        }
        return null
    }

    /**
     * Check rate limit for client.
     * Returns 429 response if rate-limited, null if allowed.
     */
    private fun checkRateLimit(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val limiter = rateLimiter ?: return null

        val clientIp = session.headers["x-forwarded-for"]?.split(",")?.firstOrNull()?.trim()
            ?: session.headers["remote-addr"]
            ?: "unknown"

        if (!limiter.tryAcquire(clientIp)) {
            val retryAfter = limiter.retryAfterSeconds(clientIp)
            val body = """{"error": "Rate limit exceeded", "retryAfter": $retryAfter}"""
            val response = NanoHTTPD.newFixedLengthResponse(
                TOO_MANY_REQUESTS,
                WebServer.MIME_JSON,
                body
            )
            response.addHeader("Retry-After", retryAfter.toString())
            response.addHeader("X-RateLimit-Remaining", "0")
            return response
        }

        return null
    }

    /**
     * Handle API request - main dispatcher
     */
    fun handleRequest(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        // Check authentication first
        checkAuth(session)?.let { return it }

        // Check rate limit
        checkRateLimit(session)?.let { return it }

        val uri = session.uri
        val method = session.method

        // Parse request body for POST/PUT
        val body = if (method == NanoHTTPD.Method.POST || method == NanoHTTPD.Method.PUT) {
            parseBody(session)
        } else null

        // Delegate to per-module handlers by URI prefix
        when {
            uri.startsWith("/api/stream/") || uri.startsWith("/api/rtsp/") ||
            uri.startsWith("/api/srt/") ||
            uri.startsWith("/api/recording/") || uri.startsWith("/api/recordings") ||
            uri.startsWith("/api/storage/") -> {
                streamHandler.handleRequest(uri, method, body)?.let { return it }
            }
            uri.startsWith("/api/upload/") -> {
                uploadHandler.handleRequest(uri, method, body)?.let { return it }
            }
            uri.startsWith("/api/thermal/") -> {
                thermalHandler.handleRequest(uri, method, body)?.let { return it }
            }
            uri.startsWith("/api/kiosk/") -> {
                kioskHandler.handleRequest(uri, method, body)?.let { return it }
            }
            uri.startsWith("/api/director/") -> {
                directorHandler.handleRequest(uri, method, body)?.let { return it }
            }
        }

        // Inline routes for status, lens, camera, snapshot, config, encoder
        return when {
            // Status endpoints
            uri == "/api/status" && method == NanoHTTPD.Method.GET -> getStatus()
            uri == "/api/device" && method == NanoHTTPD.Method.GET -> getDeviceInfo()

            // Lens control
            uri.startsWith("/api/lens/") && method == NanoHTTPD.Method.POST -> switchLens(uri)
            uri == "/api/lenses" && method == NanoHTTPD.Method.GET -> getLenses()

            // Camera control
            uri == "/api/zoom" && method == NanoHTTPD.Method.POST -> setZoom(body)
            uri == "/api/focus" && method == NanoHTTPD.Method.POST -> triggerFocus(body)
            uri == "/api/exposure" && method == NanoHTTPD.Method.POST -> setExposure(body)

            // Snapshot
            uri == "/api/snapshot" && (method == NanoHTTPD.Method.POST || method == NanoHTTPD.Method.GET) -> captureSnapshot()

            // Configuration
            uri == "/api/config" && method == NanoHTTPD.Method.GET -> getConfig()
            uri == "/api/config" && method == NanoHTTPD.Method.PUT -> updateConfig(body)

            // Encoder
            uri == "/api/encoder/capabilities" && method == NanoHTTPD.Method.GET -> getEncoderCapabilities()

            // Not found
            else -> NanoHTTPD.newFixedLengthResponse(
                Status.NOT_FOUND,
                WebServer.MIME_JSON,
                """{"error": "Endpoint not found", "uri": "$uri", "method": "${method.name}"}"""
            )
        }
    }

    /**
     * Parse request body
     */
    private fun parseBody(session: NanoHTTPD.IHTTPSession): JSONObject? {
        return try {
            val files = mutableMapOf<String, String>()
            session.parseBody(files)
            val postData = files["postData"] ?: return null
            JSONObject(postData)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error parsing request body")
            null
        }
    }

    // ==================== Status Endpoints ====================

    private fun getStatus(): NanoHTTPD.Response {
        val camera = cameraService
        val json = JSONObject().apply {
            put("status", "ok")
            put("timestamp", System.currentTimeMillis())

            // Camera status
            put("camera", JSONObject().apply {
                put("previewActive", camera?.isPreviewActive() ?: false)
                put("streamingActive", camera?.isStreaming() ?: false)
                val currentLens = camera?.getCurrentLens()?.value
                put("currentLens", currentLens?.lensType?.name ?: "none")
                put("zoom", camera?.currentZoom?.value ?: 1.0f)
            })

            // Encoder status
            put("encoder", JSONObject().apply {
                val state = camera?.encoderState?.value ?: EncoderState.IDLE
                put("state", state.name)
                val stats = camera?.getEncoderStats()
                if (stats != null) {
                    put("framesEncoded", stats.framesEncoded)
                    put("currentBitrate", stats.currentBitrateBps)
                    put("currentFps", stats.currentFps)
                }
            })

            // RTSP status
            put("rtsp", JSONObject().apply {
                put("running", camera?.isRtspServerRunning() ?: false)
                put("url", camera?.getRtspUrl() ?: "")
                put("clients", camera?.getRtspClientCount() ?: 0)
                put("playing", camera?.getRtspPlayingCount() ?: 0)
            })

            // Director status
            directorManager?.let { director ->
                put("director", JSONObject().apply {
                    val status = director.getStatus()
                    put("enabled", status.enabled)
                    put("state", status.state.name)
                    put("currentScene", status.currentScene ?: "")
                    put("currentCue", status.currentCue ?: "")
                    put("currentTake", status.takeNumber)
                    put("stats", JSONObject().apply {
                        val takeManager = director.getTakeManager()
                        val takes = takeManager.recordedTakes.value
                        val goodTakes = takes.count { it.qualityScore >= 7.0f }
                        val avgScore = if (takes.isNotEmpty()) {
                            takes.map { it.qualityScore }.average().toFloat()
                        } else 0f
                        put("totalTakes", takes.size)
                        put("goodTakes", goodTakes)
                        put("averageScore", avgScore)
                        put("sessionTime", director.getSessionDuration())
                    })
                })
            }
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun getDeviceInfo(): NanoHTTPD.Response {
        val json = JSONObject().apply {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("appVersion", "1.0.0")
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    // ==================== Lens Control ====================

    private fun switchLens(uri: String): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val lensName = uri.substringAfterLast("/")
        val lensType = when (lensName.lowercase()) {
            "wide", "ultrawide" -> LensType.WIDE
            "main", "primary" -> LensType.MAIN
            "tele", "telephoto", "zoom" -> LensType.TELEPHOTO
            else -> return NanoHTTPD.newFixedLengthResponse(
                Status.BAD_REQUEST,
                WebServer.MIME_JSON,
                """{"error": "Unknown lens type: $lensName"}"""
            )
        }

        camera.switchLens(lensType)

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "lens": "${lensType.name}"}"""
        )
    }

    private fun getLenses(): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val lenses = camera.getAvailableLenses()
        val currentLens = camera.getCurrentLens().value

        val json = JSONObject().apply {
            put("current", currentLens?.lensType?.name ?: "none")
            put("available", JSONArray().apply {
                lenses.forEach { lens ->
                    put(JSONObject().apply {
                        put("type", lens.lensType.name)
                        put("displayName", lens.lensType.displayName)
                        put("cameraId", lens.cameraId)
                        put("focalLength", lens.focalLength)
                        put("aperture", lens.aperture)
                    })
                }
            })
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    // ==================== Camera Control ====================

    private fun setZoom(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val zoom = body?.optDouble("zoom", 1.0) ?: 1.0
        camera.setZoom(zoom.toFloat())

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "zoom": $zoom}"""
        )
    }

    private fun triggerFocus(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val x = body?.optDouble("x", 0.5) ?: 0.5
        val y = body?.optDouble("y", 0.5) ?: 0.5

        camera.triggerTapToFocus(
            x.toFloat(),
            y.toFloat(),
            android.util.Size(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT)
        )

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "x": $x, "y": $y}"""
        )
    }

    private fun setExposure(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val ev = body?.optInt("ev", 0) ?: 0
        camera.setExposureCompensation(ev)

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "ev": $ev}"""
        )
    }

    // ==================== Snapshot ====================

    private fun captureSnapshot(): NanoHTTPD.Response {
        val callback = onSnapshotRequest ?: return NanoHTTPD.newFixedLengthResponse(
            Status.SERVICE_UNAVAILABLE,
            WebServer.MIME_JSON,
            """{"error": "Snapshot not available"}"""
        )

        val jpegData = callback.invoke() ?: return NanoHTTPD.newFixedLengthResponse(
            Status.INTERNAL_ERROR,
            WebServer.MIME_JSON,
            """{"error": "Failed to capture snapshot"}"""
        )

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JPEG,
            ByteArrayInputStream(jpegData),
            jpegData.size.toLong()
        )
    }

    // ==================== Configuration ====================

    private fun getConfig(): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val encoderConfig = camera.getEncoderConfig()
        val captureConfig = camera.getConfig()

        val json = JSONObject().apply {
            put("encoder", JSONObject().apply {
                if (encoderConfig != null) {
                    put("width", encoderConfig.width)
                    put("height", encoderConfig.height)
                    put("bitrate", encoderConfig.bitrateBps)
                    put("frameRate", encoderConfig.frameRate)
                    put("codec", encoderConfig.codec.name)
                    put("keyframeInterval", encoderConfig.keyframeIntervalSec)
                }
            })
            put("camera", JSONObject().apply {
                put("resolution", "${captureConfig.resolution.width}x${captureConfig.resolution.height}")
                put("focusMode", captureConfig.focusMode.name)
                put("whiteBalance", captureConfig.whiteBalance.name)
                put("exposureCompensation", captureConfig.exposureCompensation)
                put("zoom", captureConfig.zoomRatio)
            })
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    private fun updateConfig(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        body ?: return NanoHTTPD.newFixedLengthResponse(
            Status.BAD_REQUEST,
            WebServer.MIME_JSON,
            """{"error": "Request body required"}"""
        )

        if (body.has("bitrate")) {
            camera.updateEncoderBitrate(body.optInt("bitrate", 0))
        }
        if (body.has("zoom")) {
            camera.setZoom(body.optDouble("zoom", 1.0).toFloat())
        }
        if (body.has("exposureCompensation")) {
            camera.setExposureCompensation(body.optInt("exposureCompensation", 0))
        }

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK, WebServer.MIME_JSON,
            """{"success": true, "message": "Configuration updated"}"""
        )
    }

    // ==================== Encoder ====================

    private fun getEncoderCapabilities(): NanoHTTPD.Response {
        val json = JSONObject().apply {
            put("codecs", JSONArray().apply {
                put("H264")
                put("H265")
            })
            put("resolutions", JSONArray().apply {
                put(JSONObject().apply { put("name", "720p"); put("width", 1280); put("height", 720) })
                put(JSONObject().apply { put("name", "1080p"); put("width", 1920); put("height", 1080) })
                put(JSONObject().apply { put("name", "1080p60"); put("width", 1920); put("height", 1080) })
                put(JSONObject().apply { put("name", "4K"); put("width", 3840); put("height", 2160) })
            })
            put("frameRates", JSONArray().apply { put(15); put(24); put(30); put(60) })
            put("bitrateRange", JSONObject().apply {
                put("min", 500000)
                put("max", 20000000)
            })
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    // ==================== Helpers ====================

    private fun serviceUnavailable(): NanoHTTPD.Response {
        return NanoHTTPD.newFixedLengthResponse(
            Status.SERVICE_UNAVAILABLE,
            WebServer.MIME_JSON,
            """{"error": "Camera service not available"}"""
        )
    }
}
