package com.lensdaemon.web

import android.content.Context
import android.os.Build
import android.util.Size
import com.lensdaemon.BuildConfig
import com.lensdaemon.camera.CameraService
import com.lensdaemon.camera.LensSelection
import com.lensdaemon.director.DirectorManager
import com.lensdaemon.kiosk.KioskManager
import com.lensdaemon.storage.UploadService
import com.lensdaemon.thermal.ThermalGovernor
import com.lensdaemon.web.handlers.ApiHandlerUtils
import com.lensdaemon.web.handlers.DirectorApiHandler
import com.lensdaemon.web.handlers.KioskApiHandler
import com.lensdaemon.web.handlers.StreamApiHandler
import com.lensdaemon.web.handlers.ThermalApiHandler
import com.lensdaemon.web.handlers.UploadApiHandler
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream

/**
 * REST API routes dispatcher for LensDaemon web interface.
 *
 * Routes are delegated to per-module handlers:
 * - [StreamApiHandler]   -> /api/stream, /api/rtsp, /api/mpegts, /api/recording, /api/recordings, /api/storage
 * - [UploadApiHandler]   -> /api/upload
 * - [ThermalApiHandler]  -> /api/thermal
 * - [KioskApiHandler]    -> /api/kiosk
 * - [DirectorApiHandler] -> /api/director
 *
 * Status, lens, camera, snapshot, config, and encoder routes remain inline.
 *
 * Every request passes, in order: body read, cross-site check ([CrossSiteGuard]),
 * then [admit]: the rate limit (per connection address) and the token check.
 */
class ApiRoutes(
    private val context: Context
) {
    companion object {
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

        private const val BEARER_PREFIX = "Bearer "

        private const val NO_ENCODER = "No encoder running: give the bitrate in a start request or save it as a default"
        private const val FOCUS_ERROR = "x and y must be finite numbers from 0 to 1"
        private const val NO_CAMERA_OPEN = "The camera is not open"
        private const val NO_TAP_FOCUS = "This lens has no tap-to-focus (it needs continuous video autofocus)"

        /**
         * The checks a request to the API or the MJPEG preview meets once its
         * body has been read, in this order: the rate limit for its connection
         * address ([limiter]; none when null), then the token ([token]; none
         * when null). Null lets the request through.
         *
         * The limit comes first so that requests with a wrong token use up
         * their client's allowance like any others: once a token is set, the
         * limit is what slows down guessing it.
         */
        internal fun admission(
            session: NanoHTTPD.IHTTPSession,
            limiter: RateLimiter?,
            token: String?
        ): NanoHTTPD.Response? = rateLimitRefusal(session, limiter) ?: tokenRefusal(session, token)

        /**
         * 429 when [limiter] has no request left for the connection's address.
         * Headers such as X-Forwarded-For are the client's to choose
         * (LensDaemon is never behind a proxy), so they would let it dodge the
         * limit or drain another client's allowance.
         */
        private fun rateLimitRefusal(session: NanoHTTPD.IHTTPSession, limiter: RateLimiter?): NanoHTTPD.Response? {
            limiter ?: return null
            val clientIp = session.remoteIpAddress ?: session.headers["remote-addr"] ?: "unknown"
            if (limiter.tryAcquire(clientIp)) return null

            val retryAfter = limiter.retryAfterSeconds(clientIp)
            val body = JSONObject().put("error", "Rate limit exceeded").put("retryAfter", retryAfter).toString()
            val response = NanoHTTPD.newFixedLengthResponse(TOO_MANY_REQUESTS, WebServer.MIME_JSON, body)
            response.addHeader("Retry-After", retryAfter.toString())
            response.addHeader("X-RateLimit-Remaining", "0")
            return response
        }

        /**
         * 401 when [token] is set and the request does not carry it, as
         * "Authorization: Bearer <token>" or, from clients that can't send
         * headers (an <img>, an EventSource), as ?token=. GET /api/status and
         * /api/device need none.
         */
        private fun tokenRefusal(session: NanoHTTPD.IHTTPSession, token: String?): NanoHTTPD.Response? {
            token ?: return null  // Auth disabled

            // Allow public read-only endpoints without auth
            if (session.uri in PUBLIC_ENDPOINTS && session.method == NanoHTTPD.Method.GET) {
                return null
            }

            val authHeader = session.headers["authorization"] ?: ""
            val providedToken = if (authHeader.startsWith(BEARER_PREFIX, ignoreCase = true)) {
                authHeader.substring(BEARER_PREFIX.length).trim()
            } else {
                session.parms?.get("token") ?: ""
            }
            if (providedToken == token) return null

            val body = JSONObject()
                .put("error", "Authentication required")
                .put("hint", "Provide Bearer token in Authorization header")
            return ApiHandlerUtils.jsonResponse(Status.UNAUTHORIZED, body.toString())
        }
    }

    // Service references (set by WebServerService on main thread, read by NanoHTTPD threads)
    @Volatile var cameraService: CameraService? = null
        set(value) {
            field = value
            streamHandler.cameraService = value
            // Uploads take only the recordings GET /api/recordings lists, and not the file
            // the recorder holds open (whatever the recording state, as for delete and download)
            uploadHandler.recordings = { value?.listRecordings().orEmpty() }
            uploadHandler.liveRecordingPath = { value?.getRecordingStats()?.currentFilePath }
        }

    @Volatile var uploadService: UploadService? = null
        set(value) {
            field = value
            uploadHandler.uploadService = value
        }

    @Volatile var thermalGovernor: ThermalGovernor? = null
        set(value) {
            field = value
            thermalHandler.thermalGovernor = value
        }

    @Volatile var kioskManager: KioskManager? = null
        set(value) {
            field = value
            kioskHandler.kioskManager = value
        }

    @Volatile var directorManager: DirectorManager? = null
        set(value) {
            field = value
            directorHandler.directorManager = value
        }

    // Snapshot callback
    @Volatile var onSnapshotRequest: (() -> ByteArray?)? = null

    // API authentication token (null = auth disabled for backwards compat)
    @Volatile var apiToken: String? = null

    // Rate limiter (null = disabled)
    @Volatile var rateLimiter: RateLimiter? = null

    // Delegated route handlers
    private val streamHandler = StreamApiHandler()
    private val uploadHandler = UploadApiHandler()
    private val thermalHandler = ThermalApiHandler()
    private val kioskHandler = KioskApiHandler()
    private val directorHandler = DirectorApiHandler(context)

    /**
     * The API token check, also used by the MJPEG preview (/mjpeg): null when
     * the request may go ahead, else 401. Off while no token is set. The token
     * comes as "Authorization: Bearer <token>" or, from clients that can't send
     * headers (an <img>, an EventSource), as ?token=. GET /api/status and
     * /api/device need none.
     */
    internal fun authorize(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val token = apiToken ?: return null  // Auth disabled

        val uri = session.uri
        // Allow public read-only endpoints without auth
        if (uri in PUBLIC_ENDPOINTS && session.method == NanoHTTPD.Method.GET) {
            return null
        }

        val authHeader = session.headers["authorization"] ?: ""
        val providedToken = if (authHeader.startsWith(BEARER_PREFIX, ignoreCase = true)) {
            authHeader.substring(BEARER_PREFIX.length).trim()
        } else {
            session.parms?.get("token") ?: ""
        }

        if (providedToken != token) {
            val body = JSONObject()
                .put("error", "Authentication required")
                .put("hint", "Provide Bearer token in Authorization header")
            return ApiHandlerUtils.jsonResponse(Status.UNAUTHORIZED, body.toString())
        }
        return null
    }

    /**
     * Check rate limit for client, keyed on the connection's address: headers
     * such as X-Forwarded-For are the client's to choose (LensDaemon is never
     * behind a proxy), so they would let it dodge the limit or drain another
     * client's bucket. Returns 429 response if rate-limited, null if allowed.
     */
    private fun checkRateLimit(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response? {
        val limiter = rateLimiter ?: return null

        val clientIp = session.remoteIpAddress ?: session.headers["remote-addr"] ?: "unknown"

        if (!limiter.tryAcquire(clientIp)) {
            val retryAfter = limiter.retryAfterSeconds(clientIp)
            val body = JSONObject().put("error", "Rate limit exceeded").put("retryAfter", retryAfter).toString()
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
        val uri = session.uri
        val method = session.method

        // Read the body of a POST or PUT before anything can answer early: NanoHTTPD
        // does not skip an unread body, which would be taken for the next request
        // on a kept-alive connection
        val body = if (method == NanoHTTPD.Method.POST || method == NanoHTTPD.Method.PUT) {
            RequestBody.readJson(session)
        } else null

        // Refuse writes sent by a page of another site (CSRF)
        CrossSiteGuard.refusal(method, session.headers)?.let { return ApiHandlerUtils.errorJson(Status.FORBIDDEN, it) }

        // Check authentication
        authorize(session)?.let { return it }

        // Check rate limit
        checkRateLimit(session)?.let { return it }

        // Delegate to per-module handlers by URI prefix
        when {
            uri.startsWith("/api/stream/") || uri.startsWith("/api/rtsp/") ||
            uri.startsWith("/api/mpegts/") || uri == "/api/audio" ||
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
            uri == "/api/device" && method == NanoHTTPD.Method.GET -> getDeviceInfo(session.remoteIpAddress)

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
            else -> ApiHandlerUtils.jsonResponse(
                Status.NOT_FOUND,
                JSONObject().put("error", "Endpoint not found").put("uri", uri).put("method", method.name).toString()
            )
        }
    }

    // ==================== Status Endpoints ====================

    private fun getStatus(): NanoHTTPD.Response {
        val camera = cameraService
        val json = JSONObject().apply {
            put("status", "ok")
            put("timestamp", System.currentTimeMillis())
            put("camera", StatusReport.camera(camera))
            put("encoder", StatusReport.encoder(camera))
            put("rtsp", StatusReport.rtsp(camera))
            put("mpegts", StatusReport.mpegTs(camera))
            camera?.let { put("audio", streamHandler.audioStatusJson(it)) }
            put("recording", StatusReport.recording(camera))
            thermalGovernor?.let { put("thermal", StatusReport.thermal(it)) }
            directorManager?.let { put("director", StatusReport.director(it)) }
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /**
     * Device details, plus the address this request came from: the dashboard
     * offers it as the default target for pushing MPEG-TS to "this computer".
     */
    private fun getDeviceInfo(clientAddress: String?): NanoHTTPD.Response {
        val json = JSONObject().apply {
            put("clientAddress", clientAddress ?: "")
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("device", Build.DEVICE)
            put("androidVersion", Build.VERSION.RELEASE)
            put("sdkVersion", Build.VERSION.SDK_INT)
            put("appVersion", BuildConfig.VERSION_NAME)
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    // ==================== Lens Control ====================

    /**
     * Switch to the lens a name asks for: "wide", "main" or "tele" (the nearest
     * lens of that family on this phone) or a lens type such as "ultra_wide".
     */
    private fun switchLens(uri: String): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val lensName = uri.substringAfterLast("/")
        if (!LensSelection.isKnownName(lensName)) {
            return ApiHandlerUtils.jsonResponse(
                Status.BAD_REQUEST,
                JSONObject().put("success", false).put("error", "Unknown lens: $lensName").toString()
            )
        }
        val lens = camera.findLens(lensName) ?: return ApiHandlerUtils.jsonResponse(
            Status.NOT_FOUND,
            JSONObject().put("success", false).put("error", "This phone has no $lensName lens").toString()
        )

        camera.switchLens(lens.lensType)

        return success(JSONObject().put("success", true).put("lens", lens.lensType.name))
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
                        put("group", LensSelection.familyOf(lens.lensType))
                        put("zoomFactor", lens.lensType.zoomFactor)
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

    /**
     * POST /api/zoom {"zoom": 2.5} ("level" is taken too): answers the zoom
     * applied, within the lens's range. 400 unless the value is a finite number.
     */
    private fun setZoom(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val zoom = ApiInputs.zoom(body) ?: return failure(Status.BAD_REQUEST, ApiInputs.ZOOM_ERROR)
        val applied = camera.setZoom(zoom.toFloat())

        return success(JSONObject().put("success", true).put("zoom", applied))
    }

    /**
     * POST /api/focus {"x": 0.5, "y": 0.4}, from the left and top edges as
     * shares of the frame (0 to 1, values beyond are clamped): focus there.
     * {"reset": true} goes back to continuous autofocus and the camera's own
     * metering. 400 unless x and y are finite numbers.
     */
    private fun triggerFocus(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        if (body?.optBoolean("reset") == true) {
            camera.unlockFocus()
            camera.resetMetering()
            return success(JSONObject().put("success", true).put("reset", true))
        }
        val x = ApiInputs.finiteNumber(body, "x")?.coerceIn(0.0, 1.0) ?: return failure(Status.BAD_REQUEST, FOCUS_ERROR)
        val y = ApiInputs.finiteNumber(body, "y")?.coerceIn(0.0, 1.0) ?: return failure(Status.BAD_REQUEST, FOCUS_ERROR)

        camera.triggerTapToFocus(x.toFloat(), y.toFloat(), Size(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT))

        return success(JSONObject().put("success", true).put("x", x).put("y", y))
    }

    /**
     * POST /api/exposure {"ev": -2}, in the lens's exposure steps: answers the
     * compensation applied, within the lens's range. 400 unless ev is a whole number.
     */
    private fun setExposure(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()

        val ev = ApiInputs.int(body, "ev") ?: return failure(Status.BAD_REQUEST, ApiInputs.EV_ERROR)
        val applied = camera.setExposureCompensation(ev)

        return success(JSONObject().put("success", true).put("ev", applied))
    }

    // ==================== Snapshot ====================

    private fun captureSnapshot(): NanoHTTPD.Response {
        val callback = onSnapshotRequest ?: return ApiHandlerUtils.errorJson(Status.SERVICE_UNAVAILABLE, "Snapshot not available")

        val jpegData = callback.invoke() ?: return ApiHandlerUtils.errorJson(Status.INTERNAL_ERROR, "Failed to capture snapshot")

        return NanoHTTPD.newFixedLengthResponse(
            Status.OK,
            WebServer.MIME_JPEG,
            ByteArrayInputStream(jpegData),
            jpegData.size.toLong()
        )
    }

    // ==================== Configuration ====================

    /**
     * GET /api/config: the running encoder's settings (empty while none runs),
     * the camera's, and "defaults", the phone's stream defaults.
     */
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
            put("defaults", camera.streamDefaults().toJson())
        }

        return NanoHTTPD.newFixedLengthResponse(Status.OK, WebServer.MIME_JSON, json.toString())
    }

    /**
     * PUT /api/config, with any of:
     * - "bitrate": the running encoder's bitrate from now on (config and status
     *   report it, and an active thermal cut applies on top); 409 while no encoder runs
     * - "zoom" and "exposureCompensation", as POST /api/zoom and /api/exposure
     * - "defaults": some or all of the phone's stream defaults, saved
     *
     * The whole body is checked before anything changes (400 with the reason),
     * and the reply carries the values applied for what was sent.
     */
    private fun updateConfig(body: JSONObject?): NanoHTTPD.Response {
        val camera = cameraService ?: return serviceUnavailable()
        body ?: return failure(Status.BAD_REQUEST, "Request body required")

        val (change, error) = ApiInputs.configChange(body, camera.streamDefaults())
        change ?: return failure(Status.BAD_REQUEST, error ?: "Invalid configuration")

        val reply = JSONObject().put("success", true).put("message", "Configuration updated")
        // First, as the one change that can be refused: a 409 leaves everything as it was
        val bitrate = change.bitrate
        if (bitrate != null) {
            val applied = camera.setEncoderBitrate(bitrate) ?: return failure(Status.CONFLICT, NO_ENCODER)
            reply.put("bitrate", applied)
        }
        change.zoom?.let { reply.put("zoom", camera.setZoom(it.toFloat())) }
        change.exposureCompensation?.let { reply.put("exposureCompensation", camera.setExposureCompensation(it)) }
        val defaults = change.defaults
        if (defaults != null) {
            if (!camera.saveStreamDefaults(defaults)) {
                return failure(Status.INTERNAL_ERROR, "The stream defaults could not be saved")
            }
            reply.put("defaults", defaults.toJson())
        }
        return success(reply)
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

    private fun serviceUnavailable(): NanoHTTPD.Response = ApiHandlerUtils.serviceUnavailable("Camera service")

    private fun success(json: JSONObject): NanoHTTPD.Response = ApiHandlerUtils.jsonResponse(Status.OK, json.toString())

    /** A refusal: {"success": false, "error": [message]}. */
    private fun failure(status: Status, message: String): NanoHTTPD.Response =
        ApiHandlerUtils.jsonResponse(status, JSONObject().put("success", false).put("error", message).toString())
}
