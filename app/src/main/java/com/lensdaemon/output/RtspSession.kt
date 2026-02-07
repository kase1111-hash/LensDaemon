package com.lensdaemon.output

import com.lensdaemon.encoder.EncodedFrame
import com.lensdaemon.encoder.VideoCodec
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * RTSP session state
 */
enum class SessionState {
    INIT,
    READY,
    PLAYING,
    PAUSED,
    TEARDOWN
}

/**
 * RTSP client session handler
 * Manages a single client connection and handles RTSP protocol commands
 */
class RtspSession(
    private val socket: Socket,
    private val serverAddress: String,
    private val onSessionClosed: (RtspSession) -> Unit
) {
    companion object {
        private const val TAG = "RtspSession"
        private const val READ_TIMEOUT_MS = 60_000
    }

    // Session identification
    val sessionId: String = UUID.randomUUID().toString().replace("-", "").substring(0, 16)

    // Session state
    @Volatile
    var state = SessionState.INIT
        private set

    // Client info
    val clientAddress: InetAddress = socket.inetAddress
    val clientPort: Int = socket.port

    // Transport
    private var transportParams: TransportParams? = null
    private var rtpPacketizer: RtpPacketizer? = null
    private var udpSocket: DatagramSocket? = null
    private var serverRtpPort: Int = 0
    private var serverRtcpPort: Int = 0

    // Codec info
    private var codec: VideoCodec = VideoCodec.H264
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var vps: ByteArray? = null

    // I/O streams
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var reader: BufferedReader? = null

    // Coroutine scope for this session
    private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Control flags
    private val isRunning = AtomicBoolean(true)
    private val isPlaying = AtomicBoolean(false)

    // Statistics
    private val packetsSent = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private var startTimeMs = 0L

    // Activity tracking for idle eviction
    @Volatile
    var lastActivityMs: Long = System.currentTimeMillis()
        private set

    /**
     * Initialize session streams
     */
    fun initialize() {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            inputStream = socket.getInputStream()
            outputStream = socket.getOutputStream()
            reader = BufferedReader(InputStreamReader(inputStream ?: return))
            Timber.i("$TAG: Session $sessionId initialized from ${clientAddress.hostAddress}:$clientPort")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize session")
            close()
        }
    }

    /**
     * Set codec configuration
     */
    fun setCodecConfig(
        codec: VideoCodec,
        sps: ByteArray?,
        pps: ByteArray?,
        vps: ByteArray? = null
    ) {
        this.codec = codec
        this.sps = sps
        this.pps = pps
        this.vps = vps

        // Create appropriate packetizer
        rtpPacketizer = when (codec) {
            VideoCodec.H264 -> RtpPacketizerFactory.createH264Packetizer()
            VideoCodec.H265 -> RtpPacketizerFactory.createH265Packetizer()
        }
    }

    /**
     * Start reading and processing RTSP requests
     */
    fun startRequestLoop() {
        sessionScope.launch {
            try {
                while (isRunning.get() && !socket.isClosed) {
                    val request = readRequest() ?: break
                    lastActivityMs = System.currentTimeMillis()
                    val response = handleRequest(request)
                    sendResponse(response)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Timber.e(e, "$TAG: Error in request loop for session $sessionId")
                }
            } finally {
                close()
            }
        }
    }

    /**
     * Read RTSP request from client
     */
    private fun readRequest(): RtspRequest? {
        val sb = StringBuilder()
        var contentLength = 0

        try {
            // Read headers
            var line = reader?.readLine() ?: return null
            while (line.isNotEmpty()) {
                sb.append(line).append("\r\n")
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                }
                line = reader?.readLine() ?: return null
            }
            sb.append("\r\n")

            // Read body if present
            if (contentLength > 0) {
                val body = CharArray(contentLength)
                reader?.read(body, 0, contentLength)
                sb.append(body)
            }

            return RtspRequest.parse(sb.toString())
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error reading request")
            return null
        }
    }

    /**
     * Handle RTSP request and generate response
     */
    private fun handleRequest(request: RtspRequest): RtspResponse {
        Timber.d("$TAG: Session $sessionId received ${request.method}")

        return when (request.method) {
            RtspConstants.METHOD_OPTIONS -> handleOptions(request)
            RtspConstants.METHOD_DESCRIBE -> handleDescribe(request)
            RtspConstants.METHOD_SETUP -> handleSetup(request)
            RtspConstants.METHOD_PLAY -> handlePlay(request)
            RtspConstants.METHOD_PAUSE -> handlePause(request)
            RtspConstants.METHOD_TEARDOWN -> handleTeardown(request)
            RtspConstants.METHOD_GET_PARAMETER -> handleGetParameter(request)
            else -> RtspResponse.methodNotAllowed(request.cseq)
        }
    }

    /**
     * Handle OPTIONS request
     */
    private fun handleOptions(request: RtspRequest): RtspResponse {
        return RtspResponse.ok(request.cseq)
            .addHeader(RtspConstants.HEADER_PUBLIC, RtspConstants.SUPPORTED_METHODS.joinToString(", "))
    }

    /**
     * Handle DESCRIBE request - return SDP
     */
    private fun handleDescribe(request: RtspRequest): RtspResponse {
        val sdpGenerator = SdpGenerator()

        val sdp = sdpGenerator.generateSdp(
            serverAddress = serverAddress,
            serverPort = serverRtpPort.takeIf { it > 0 } ?: RtspConstants.DEFAULT_RTP_PORT,
            sessionName = "LensDaemon Stream",
            config = com.lensdaemon.encoder.EncoderConfig(codec = codec),
            vps = vps,
            sps = sps,
            pps = pps,
            trackId = "trackID=0"
        )

        return RtspResponse.ok(request.cseq)
            .addHeader(RtspConstants.HEADER_CONTENT_BASE, request.uri + "/")
            .setBody(sdp, RtspConstants.CONTENT_TYPE_SDP)
    }

    /**
     * Handle SETUP request - configure transport
     */
    private fun handleSetup(request: RtspRequest): RtspResponse {
        val transport = request.transport
            ?: return RtspResponse.badRequest(request.cseq)

        val params = TransportParams.parse(transport)
            ?: return RtspResponse.unsupportedTransport(request.cseq)

        transportParams = params

        // Setup transport based on mode
        when (params.mode) {
            RtspTransportMode.UDP_UNICAST -> {
                setupUdpTransport(params)
            }
            RtspTransportMode.TCP_INTERLEAVED -> {
                // TCP uses existing connection
                serverRtpPort = 0
                serverRtcpPort = 0
            }
            RtspTransportMode.UDP_MULTICAST -> {
                return RtspResponse.unsupportedTransport(request.cseq)
            }
        }

        state = SessionState.READY

        val ssrc = rtpPacketizer?.getSsrc() ?: 0L
        val transportResponse = params.toResponseHeader(serverRtpPort, serverRtcpPort, ssrc)

        return RtspResponse.ok(request.cseq)
            .setSession(sessionId)
            .addHeader(RtspConstants.HEADER_TRANSPORT, transportResponse)
    }

    /**
     * Setup UDP transport
     */
    private fun setupUdpTransport(params: TransportParams) {
        try {
            // Create UDP socket for RTP
            udpSocket = DatagramSocket()
            serverRtpPort = udpSocket?.localPort ?: 0
            serverRtcpPort = serverRtpPort + 1

            Timber.i("$TAG: UDP transport setup - server port $serverRtpPort, client ${params.clientRtpPort}")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to setup UDP transport")
        }
    }

    /**
     * Handle PLAY request - start streaming
     */
    private fun handlePlay(request: RtspRequest): RtspResponse {
        if (state != SessionState.READY && state != SessionState.PAUSED) {
            return RtspResponse(RtspStatusCode.METHOD_NOT_VALID, request.cseq)
        }

        state = SessionState.PLAYING
        isPlaying.set(true)
        startTimeMs = System.currentTimeMillis()

        // RTP-Info header
        val seq = rtpPacketizer?.getSequenceNumber() ?: 0
        val rtpInfo = "url=${request.uri}/trackID=0;seq=$seq;rtptime=0"

        Timber.i("$TAG: Session $sessionId started playing")

        return RtspResponse.ok(request.cseq)
            .setSession(sessionId)
            .addHeader(RtspConstants.HEADER_RANGE, "npt=0.000-")
            .addHeader(RtspConstants.HEADER_RTP_INFO, rtpInfo)
    }

    /**
     * Handle PAUSE request
     */
    private fun handlePause(request: RtspRequest): RtspResponse {
        if (state != SessionState.PLAYING) {
            return RtspResponse(RtspStatusCode.METHOD_NOT_VALID, request.cseq)
        }

        state = SessionState.PAUSED
        isPlaying.set(false)

        return RtspResponse.ok(request.cseq)
            .setSession(sessionId)
    }

    /**
     * Handle TEARDOWN request
     */
    private fun handleTeardown(request: RtspRequest): RtspResponse {
        state = SessionState.TEARDOWN
        isPlaying.set(false)

        // Schedule session close
        sessionScope.launch {
            delay(100) // Allow response to be sent
            close()
        }

        return RtspResponse.ok(request.cseq)
            .setSession(sessionId)
    }

    /**
     * Handle GET_PARAMETER request (keepalive)
     */
    private fun handleGetParameter(request: RtspRequest): RtspResponse {
        return RtspResponse.ok(request.cseq)
            .setSession(sessionId)
    }

    /**
     * Send RTSP response to client
     */
    private fun sendResponse(response: RtspResponse) {
        try {
            val data = response.build()
            outputStream?.write(data.toByteArray())
            outputStream?.flush()
            Timber.v("$TAG: Sent response to $sessionId")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error sending response")
        }
    }

    /**
     * Send encoded frame to client
     */
    fun sendFrame(frame: EncodedFrame) {
        if (!isPlaying.get()) return
        val packetizer = rtpPacketizer ?: return

        try {
            val packets = packetizer.packetize(frame.data, frame.presentationTimeUs)

            for (packet in packets) {
                sendRtpPacket(packet)
            }

            packetsSent.addAndGet(packets.size.toLong())
            bytesSent.addAndGet(frame.size.toLong())
            lastActivityMs = System.currentTimeMillis()

        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error sending frame to session $sessionId")
        }
    }

    /**
     * Send RTP packet to client
     */
    private fun sendRtpPacket(packet: RtpPacket) {
        val data = packet.toByteArray()
        val params = transportParams ?: return

        when (params.mode) {
            RtspTransportMode.UDP_UNICAST -> {
                sendUdpPacket(data, params.clientRtpPort)
            }
            RtspTransportMode.TCP_INTERLEAVED -> {
                sendInterleavedPacket(data, params.interleavedRtpChannel)
            }
            else -> { /* Not supported */ }
        }
    }

    /**
     * Send UDP packet
     */
    private fun sendUdpPacket(data: ByteArray, port: Int) {
        try {
            val packet = DatagramPacket(data, data.size, clientAddress, port)
            udpSocket?.send(packet)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error sending UDP packet")
        }
    }

    /**
     * Send TCP interleaved packet
     * Format: $ + channel (1 byte) + length (2 bytes) + data
     */
    private fun sendInterleavedPacket(data: ByteArray, channel: Int) {
        try {
            val header = ByteArray(4)
            header[0] = '$'.code.toByte()
            header[1] = channel.toByte()
            header[2] = (data.size shr 8).toByte()
            header[3] = (data.size and 0xFF).toByte()

            val out = outputStream ?: return
            synchronized(out) {
                out.write(header)
                out.write(data)
                out.flush()
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error sending interleaved packet")
        }
    }

    /**
     * Check if session is playing
     */
    fun isPlaying(): Boolean = isPlaying.get()

    /**
     * Check if session is active
     */
    fun isActive(): Boolean = isRunning.get() && !socket.isClosed

    /**
     * Get session statistics
     */
    fun getStats(): SessionStats {
        val durationMs = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0
        return SessionStats(
            sessionId = sessionId,
            clientAddress = clientAddress.hostAddress ?: "unknown",
            state = state,
            packetsSent = packetsSent.get(),
            bytesSent = bytesSent.get(),
            durationMs = durationMs
        )
    }

    /**
     * Close session
     */
    fun close() {
        if (!isRunning.compareAndSet(true, false)) return

        Timber.i("$TAG: Closing session $sessionId")

        state = SessionState.TEARDOWN
        isPlaying.set(false)

        try {
            udpSocket?.close()
            inputStream?.close()
            outputStream?.close()
            socket.close()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error closing session")
        }

        sessionScope.cancel()
        onSessionClosed(this)
    }
}

/**
 * Session statistics
 */
data class SessionStats(
    val sessionId: String,
    val clientAddress: String,
    val state: SessionState,
    val packetsSent: Long,
    val bytesSent: Long,
    val durationMs: Long
) {
    val bitrateBps: Long
        get() = if (durationMs > 0) (bytesSent * 8 * 1000) / durationMs else 0
}
