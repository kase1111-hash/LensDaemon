package com.lensdaemon.output

/**
 * RTSP protocol constants and methods
 */
object RtspConstants {
    // Default ports
    const val DEFAULT_RTSP_PORT = 8554
    const val DEFAULT_RTP_PORT = 5004
    const val DEFAULT_RTCP_PORT = 5005

    // Stream paths
    const val STREAM_PATH = "/stream"
    const val LIVE_PATH = "/live"

    // RTSP protocol version
    const val RTSP_VERSION = "RTSP/1.0"

    // RTSP methods
    const val METHOD_OPTIONS = "OPTIONS"
    const val METHOD_DESCRIBE = "DESCRIBE"
    const val METHOD_SETUP = "SETUP"
    const val METHOD_PLAY = "PLAY"
    const val METHOD_PAUSE = "PAUSE"
    const val METHOD_TEARDOWN = "TEARDOWN"
    const val METHOD_GET_PARAMETER = "GET_PARAMETER"
    const val METHOD_SET_PARAMETER = "SET_PARAMETER"
    const val METHOD_ANNOUNCE = "ANNOUNCE"
    const val METHOD_RECORD = "RECORD"

    // RTSP headers
    const val HEADER_CSEQ = "CSeq"
    const val HEADER_SESSION = "Session"
    const val HEADER_TRANSPORT = "Transport"
    const val HEADER_CONTENT_TYPE = "Content-Type"
    const val HEADER_CONTENT_LENGTH = "Content-Length"
    const val HEADER_CONTENT_BASE = "Content-Base"
    const val HEADER_PUBLIC = "Public"
    const val HEADER_SERVER = "Server"
    const val HEADER_DATE = "Date"
    const val HEADER_RANGE = "Range"
    const val HEADER_RTP_INFO = "RTP-Info"
    const val HEADER_ACCEPT = "Accept"
    const val HEADER_USER_AGENT = "User-Agent"
    const val HEADER_AUTHORIZATION = "Authorization"
    const val HEADER_WWW_AUTHENTICATE = "WWW-Authenticate"

    // Content types
    const val CONTENT_TYPE_SDP = "application/sdp"

    // Transport modes
    const val TRANSPORT_RTP_AVP = "RTP/AVP"
    const val TRANSPORT_RTP_AVP_TCP = "RTP/AVP/TCP"
    const val TRANSPORT_RTP_AVP_UDP = "RTP/AVP/UDP"

    // Server name
    const val SERVER_NAME = "LensDaemon RTSP Server/1.0"

    // Session timeout in seconds
    const val DEFAULT_SESSION_TIMEOUT = 60

    // Supported methods
    val SUPPORTED_METHODS = listOf(
        METHOD_OPTIONS,
        METHOD_DESCRIBE,
        METHOD_SETUP,
        METHOD_PLAY,
        METHOD_PAUSE,
        METHOD_TEARDOWN,
        METHOD_GET_PARAMETER
    )
}

/**
 * RTSP response status codes
 */
enum class RtspStatusCode(val code: Int, val reason: String) {
    OK(200, "OK"),
    CREATED(201, "Created"),
    LOW_ON_STORAGE(250, "Low on Storage Space"),
    MULTIPLE_CHOICES(300, "Multiple Choices"),
    MOVED_PERMANENTLY(301, "Moved Permanently"),
    MOVED_TEMPORARILY(302, "Moved Temporarily"),
    SEE_OTHER(303, "See Other"),
    NOT_MODIFIED(304, "Not Modified"),
    USE_PROXY(305, "Use Proxy"),
    BAD_REQUEST(400, "Bad Request"),
    UNAUTHORIZED(401, "Unauthorized"),
    PAYMENT_REQUIRED(402, "Payment Required"),
    FORBIDDEN(403, "Forbidden"),
    NOT_FOUND(404, "Not Found"),
    METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
    NOT_ACCEPTABLE(406, "Not Acceptable"),
    PROXY_AUTH_REQUIRED(407, "Proxy Authentication Required"),
    REQUEST_TIMEOUT(408, "Request Timeout"),
    GONE(410, "Gone"),
    LENGTH_REQUIRED(411, "Length Required"),
    PRECONDITION_FAILED(412, "Precondition Failed"),
    ENTITY_TOO_LARGE(413, "Request Entity Too Large"),
    URI_TOO_LONG(414, "Request-URI Too Long"),
    UNSUPPORTED_MEDIA(415, "Unsupported Media Type"),
    INVALID_PARAMETER(451, "Parameter Not Understood"),
    CONFERENCE_NOT_FOUND(452, "Conference Not Found"),
    NOT_ENOUGH_BANDWIDTH(453, "Not Enough Bandwidth"),
    SESSION_NOT_FOUND(454, "Session Not Found"),
    METHOD_NOT_VALID(455, "Method Not Valid in This State"),
    HEADER_FIELD_NOT_VALID(456, "Header Field Not Valid for Resource"),
    INVALID_RANGE(457, "Invalid Range"),
    PARAMETER_READ_ONLY(458, "Parameter Is Read-Only"),
    AGGREGATE_OPERATION(459, "Aggregate Operation Not Allowed"),
    ONLY_AGGREGATE(460, "Only Aggregate Operation Allowed"),
    UNSUPPORTED_TRANSPORT(461, "Unsupported Transport"),
    DESTINATION_UNREACHABLE(462, "Destination Unreachable"),
    INTERNAL_ERROR(500, "Internal Server Error"),
    NOT_IMPLEMENTED(501, "Not Implemented"),
    BAD_GATEWAY(502, "Bad Gateway"),
    SERVICE_UNAVAILABLE(503, "Service Unavailable"),
    GATEWAY_TIMEOUT(504, "Gateway Timeout"),
    VERSION_NOT_SUPPORTED(505, "RTSP Version Not Supported"),
    OPTION_NOT_SUPPORTED(551, "Option Not Supported");

    override fun toString(): String = "$code $reason"
}

/**
 * RTSP transport mode
 */
enum class RtspTransportMode {
    UDP_UNICAST,
    UDP_MULTICAST,
    TCP_INTERLEAVED
}

/**
 * Parsed RTSP request
 */
data class RtspRequest(
    val method: String,
    val uri: String,
    val version: String,
    val headers: Map<String, String>,
    val body: String? = null
) {
    val cseq: Int
        get() = headers[RtspConstants.HEADER_CSEQ]?.toIntOrNull() ?: 0

    val sessionId: String?
        get() = headers[RtspConstants.HEADER_SESSION]?.substringBefore(";")

    val transport: String?
        get() = headers[RtspConstants.HEADER_TRANSPORT]

    val userAgent: String?
        get() = headers[RtspConstants.HEADER_USER_AGENT]

    val accept: String?
        get() = headers[RtspConstants.HEADER_ACCEPT]

    companion object {
        /**
         * Parse RTSP request from string
         */
        fun parse(data: String): RtspRequest? {
            val lines = data.split("\r\n")
            if (lines.isEmpty()) return null

            // Parse request line
            val requestLine = lines[0].split(" ")
            if (requestLine.size < 3) return null

            val method = requestLine[0]
            val uri = requestLine[1]
            val version = requestLine[2]

            // Parse headers
            val headers = mutableMapOf<String, String>()
            var bodyStartIndex = -1

            for (i in 1 until lines.size) {
                val line = lines[i]
                if (line.isEmpty()) {
                    bodyStartIndex = i + 1
                    break
                }
                val colonIndex = line.indexOf(':')
                if (colonIndex > 0) {
                    val key = line.substring(0, colonIndex).trim()
                    val value = line.substring(colonIndex + 1).trim()
                    headers[key] = value
                }
            }

            // Parse body if present
            val body = if (bodyStartIndex > 0 && bodyStartIndex < lines.size) {
                lines.subList(bodyStartIndex, lines.size).joinToString("\r\n")
            } else null

            return RtspRequest(method, uri, version, headers, body)
        }
    }
}

/**
 * RTSP response builder
 */
class RtspResponse(
    val statusCode: RtspStatusCode,
    val cseq: Int
) {
    private val headers = mutableMapOf<String, String>()
    private var body: String? = null

    init {
        headers[RtspConstants.HEADER_CSEQ] = cseq.toString()
        headers[RtspConstants.HEADER_SERVER] = RtspConstants.SERVER_NAME
    }

    fun addHeader(name: String, value: String): RtspResponse {
        headers[name] = value
        return this
    }

    fun setSession(sessionId: String, timeout: Int = RtspConstants.DEFAULT_SESSION_TIMEOUT): RtspResponse {
        headers[RtspConstants.HEADER_SESSION] = "$sessionId;timeout=$timeout"
        return this
    }

    fun setBody(content: String, contentType: String = RtspConstants.CONTENT_TYPE_SDP): RtspResponse {
        body = content
        headers[RtspConstants.HEADER_CONTENT_TYPE] = contentType
        headers[RtspConstants.HEADER_CONTENT_LENGTH] = content.length.toString()
        return this
    }

    fun build(): String {
        val sb = StringBuilder()

        // Status line
        sb.append("${RtspConstants.RTSP_VERSION} $statusCode\r\n")

        // Headers
        for ((key, value) in headers) {
            sb.append("$key: $value\r\n")
        }

        // Empty line between headers and body
        sb.append("\r\n")

        // Body
        body?.let { sb.append(it) }

        return sb.toString()
    }

    companion object {
        fun ok(cseq: Int) = RtspResponse(RtspStatusCode.OK, cseq)
        fun notFound(cseq: Int) = RtspResponse(RtspStatusCode.NOT_FOUND, cseq)
        fun badRequest(cseq: Int) = RtspResponse(RtspStatusCode.BAD_REQUEST, cseq)
        fun methodNotAllowed(cseq: Int) = RtspResponse(RtspStatusCode.METHOD_NOT_ALLOWED, cseq)
        fun sessionNotFound(cseq: Int) = RtspResponse(RtspStatusCode.SESSION_NOT_FOUND, cseq)
        fun internalError(cseq: Int) = RtspResponse(RtspStatusCode.INTERNAL_ERROR, cseq)
        fun unsupportedTransport(cseq: Int) = RtspResponse(RtspStatusCode.UNSUPPORTED_TRANSPORT, cseq)
    }
}

/**
 * Transport parameters parsed from Transport header
 */
data class TransportParams(
    val mode: RtspTransportMode,
    val clientRtpPort: Int,
    val clientRtcpPort: Int,
    val interleavedRtpChannel: Int = 0,
    val interleavedRtcpChannel: Int = 1
) {
    companion object {
        /**
         * Parse Transport header value
         * Examples:
         *   RTP/AVP;unicast;client_port=5004-5005
         *   RTP/AVP/TCP;interleaved=0-1
         */
        fun parse(transport: String): TransportParams? {
            val parts = transport.split(";").map { it.trim() }
            if (parts.isEmpty()) return null

            var mode = RtspTransportMode.UDP_UNICAST
            var clientRtpPort = RtspConstants.DEFAULT_RTP_PORT
            var clientRtcpPort = RtspConstants.DEFAULT_RTCP_PORT
            var interleavedRtp = 0
            var interleavedRtcp = 1

            for (part in parts) {
                when {
                    part.contains("TCP", ignoreCase = true) -> {
                        mode = RtspTransportMode.TCP_INTERLEAVED
                    }
                    part.contains("multicast", ignoreCase = true) -> {
                        mode = RtspTransportMode.UDP_MULTICAST
                    }
                    part.startsWith("client_port=", ignoreCase = true) -> {
                        val ports = part.substringAfter("=").split("-")
                        if (ports.isNotEmpty()) {
                            clientRtpPort = ports[0].toIntOrNull() ?: RtspConstants.DEFAULT_RTP_PORT
                            clientRtcpPort = if (ports.size > 1) {
                                ports[1].toIntOrNull() ?: (clientRtpPort + 1)
                            } else {
                                clientRtpPort + 1
                            }
                        }
                    }
                    part.startsWith("interleaved=", ignoreCase = true) -> {
                        val channels = part.substringAfter("=").split("-")
                        if (channels.isNotEmpty()) {
                            interleavedRtp = channels[0].toIntOrNull() ?: 0
                            interleavedRtcp = if (channels.size > 1) {
                                channels[1].toIntOrNull() ?: (interleavedRtp + 1)
                            } else {
                                interleavedRtp + 1
                            }
                        }
                        mode = RtspTransportMode.TCP_INTERLEAVED
                    }
                }
            }

            return TransportParams(
                mode = mode,
                clientRtpPort = clientRtpPort,
                clientRtcpPort = clientRtcpPort,
                interleavedRtpChannel = interleavedRtp,
                interleavedRtcpChannel = interleavedRtcp
            )
        }
    }

    /**
     * Build Transport header value for response
     */
    fun toResponseHeader(serverRtpPort: Int, serverRtcpPort: Int, ssrc: Long): String {
        return when (mode) {
            RtspTransportMode.UDP_UNICAST -> {
                "RTP/AVP;unicast;client_port=$clientRtpPort-$clientRtcpPort;" +
                        "server_port=$serverRtpPort-$serverRtcpPort;ssrc=${ssrc.toString(16).uppercase()}"
            }
            RtspTransportMode.TCP_INTERLEAVED -> {
                "RTP/AVP/TCP;interleaved=$interleavedRtpChannel-$interleavedRtcpChannel;" +
                        "ssrc=${ssrc.toString(16).uppercase()}"
            }
            RtspTransportMode.UDP_MULTICAST -> {
                "RTP/AVP;multicast;port=$serverRtpPort-$serverRtcpPort;" +
                        "ssrc=${ssrc.toString(16).uppercase()}"
            }
        }
    }
}
