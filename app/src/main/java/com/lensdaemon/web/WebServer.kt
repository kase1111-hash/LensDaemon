package com.lensdaemon.web

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.IOException
import java.io.InputStream

/**
 * Web server state
 */
enum class WebServerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

/**
 * HTTP-based web server for LensDaemon control interface
 * Provides REST API and serves the web dashboard
 */
class WebServer(
    private val context: Context,
    private val port: Int = DEFAULT_PORT
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "WebServer"
        const val DEFAULT_PORT = 8080

        // MIME types
        const val MIME_JSON = "application/json"
        const val MIME_HTML = "text/html"
        const val MIME_CSS = "text/css"
        const val MIME_JS = "application/javascript"
        const val MIME_JPEG = "image/jpeg"
        const val MIME_PNG = "image/png"
        const val MIME_MULTIPART = "multipart/x-mixed-replace; boundary=frame"
    }

    // Server state
    private val _state = MutableStateFlow(WebServerState.STOPPED)
    val state: StateFlow<WebServerState> = _state.asStateFlow()

    // API routes handler
    private var apiRoutes: ApiRoutes? = null

    // MJPEG streamer
    private var mjpegStreamer: MjpegStreamer? = null

    // Statistics
    private var requestCount = 0L
    private var startTimeMs = 0L

    /**
     * Set API routes handler
     */
    fun setApiRoutes(routes: ApiRoutes) {
        this.apiRoutes = routes
    }

    /**
     * Set MJPEG streamer
     */
    fun setMjpegStreamer(streamer: MjpegStreamer) {
        this.mjpegStreamer = streamer
    }

    /**
     * Start the web server
     */
    fun startServer(): Boolean {
        if (_state.value == WebServerState.RUNNING) {
            Timber.w("$TAG: Server already running")
            return true
        }

        _state.value = WebServerState.STARTING

        return try {
            start(SOCKET_READ_TIMEOUT, false)
            startTimeMs = System.currentTimeMillis()
            _state.value = WebServerState.RUNNING
            Timber.i("$TAG: Web server started on port $port")
            true
        } catch (e: IOException) {
            Timber.e(e, "$TAG: Failed to start web server")
            _state.value = WebServerState.ERROR
            false
        }
    }

    /**
     * Stop the web server
     */
    fun stopServer() {
        if (_state.value != WebServerState.RUNNING) {
            return
        }

        _state.value = WebServerState.STOPPING
        stop()
        _state.value = WebServerState.STOPPED
        Timber.i("$TAG: Web server stopped")
    }

    /**
     * Handle incoming HTTP request
     */
    override fun serve(session: IHTTPSession): Response {
        requestCount++
        val uri = session.uri
        val method = session.method

        Timber.d("$TAG: ${method.name} $uri")

        return try {
            when {
                // API endpoints
                uri.startsWith("/api/") -> handleApiRequest(session)

                // MJPEG stream
                uri == "/mjpeg" || uri == "/stream.mjpeg" -> handleMjpegStream(session)

                // Static assets
                uri == "/" || uri == "/index.html" -> serveAsset("web/index.html", MIME_HTML)
                uri == "/dashboard.js" -> serveAsset("web/dashboard.js", MIME_JS)
                uri == "/styles.css" -> serveAsset("web/styles.css", MIME_CSS)
                uri.endsWith(".png") -> serveAsset("web${uri}", MIME_PNG)
                uri.endsWith(".jpg") || uri.endsWith(".jpeg") -> serveAsset("web${uri}", MIME_JPEG)
                uri.endsWith(".js") -> serveAsset("web${uri}", MIME_JS)
                uri.endsWith(".css") -> serveAsset("web${uri}", MIME_CSS)
                uri.endsWith(".html") -> serveAsset("web${uri}", MIME_HTML)

                // Default: try to serve as asset
                else -> serveAsset("web${uri}", getMimeType(uri))
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error handling request: $uri")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                MIME_JSON,
                """{"error": "Internal server error"}"""
            )
        }
    }

    /**
     * Handle API request
     */
    private fun handleApiRequest(session: IHTTPSession): Response {
        val routes = apiRoutes ?: return newFixedLengthResponse(
            Response.Status.SERVICE_UNAVAILABLE,
            MIME_JSON,
            """{"error": "API not available"}"""
        )

        return routes.handleRequest(session)
    }

    /**
     * Handle MJPEG stream request
     */
    private fun handleMjpegStream(session: IHTTPSession): Response {
        val streamer = mjpegStreamer ?: return newFixedLengthResponse(
            Response.Status.SERVICE_UNAVAILABLE,
            MIME_JSON,
            """{"error": "MJPEG stream not available"}"""
        )

        return streamer.createStreamResponse()
    }

    /**
     * Serve static asset from assets folder
     */
    private fun serveAsset(path: String, mimeType: String): Response {
        return try {
            val inputStream = context.assets.open(path)
            newChunkedResponse(Response.Status.OK, mimeType, inputStream)
        } catch (e: IOException) {
            Timber.w("$TAG: Asset not found: $path")
            newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                MIME_JSON,
                """{"error": "Not found", "path": "$path"}"""
            )
        }
    }

    /**
     * Get MIME type from file extension
     */
    private fun getMimeType(uri: String): String {
        return when {
            uri.endsWith(".html") -> MIME_HTML
            uri.endsWith(".css") -> MIME_CSS
            uri.endsWith(".js") -> MIME_JS
            uri.endsWith(".json") -> MIME_JSON
            uri.endsWith(".png") -> MIME_PNG
            uri.endsWith(".jpg") || uri.endsWith(".jpeg") -> MIME_JPEG
            uri.endsWith(".svg") -> "image/svg+xml"
            uri.endsWith(".ico") -> "image/x-icon"
            uri.endsWith(".woff") -> "font/woff"
            uri.endsWith(".woff2") -> "font/woff2"
            else -> "application/octet-stream"
        }
    }

    /**
     * Get server statistics
     */
    fun getStats(): WebServerStats {
        val uptimeMs = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0
        return WebServerStats(
            state = _state.value,
            port = port,
            requestCount = requestCount,
            uptimeMs = uptimeMs
        )
    }

    /**
     * Check if server is running
     */
    fun isRunning(): Boolean = _state.value == WebServerState.RUNNING

    /**
     * Get server URL
     */
    fun getServerUrl(): String {
        val ip = getLocalIpAddress()
        return "http://$ip:$port"
    }

    /**
     * Get local IP address
     */
    private fun getLocalIpAddress(): String {
        return try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val netInterface = interfaces.nextElement()
                val addresses = netInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "0.0.0.0"
                    }
                }
            }
            "0.0.0.0"
        } catch (e: Exception) {
            "0.0.0.0"
        }
    }
}

/**
 * Web server statistics
 */
data class WebServerStats(
    val state: WebServerState,
    val port: Int,
    val requestCount: Long,
    val uptimeMs: Long
)

/**
 * Extension for creating JSON response
 */
fun NanoHTTPD.jsonResponse(
    status: NanoHTTPD.Response.Status,
    json: String
): NanoHTTPD.Response {
    return newFixedLengthResponse(status, WebServer.MIME_JSON, json)
}

/**
 * Extension for creating success JSON response
 */
fun NanoHTTPD.successResponse(json: String): NanoHTTPD.Response {
    return jsonResponse(NanoHTTPD.Response.Status.OK, json)
}

/**
 * Extension for creating error JSON response
 */
fun NanoHTTPD.errorResponse(
    status: NanoHTTPD.Response.Status,
    message: String
): NanoHTTPD.Response {
    return jsonResponse(status, """{"error": "$message"}""")
}
