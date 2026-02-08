package com.lensdaemon.output

import com.lensdaemon.encoder.EncodedFrame
import com.lensdaemon.encoder.VideoCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * RTSP server state
 */
enum class RtspServerState {
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    ERROR
}

/**
 * RTSP server statistics
 */
data class RtspServerStats(
    val state: RtspServerState,
    val port: Int,
    val activeConnections: Int,
    val totalConnections: Long,
    val totalPacketsSent: Long,
    val totalBytesSent: Long,
    val uptimeMs: Long,
    val avgLossPercent: Float = 0f,
    val maxJitter: Long = 0
)

/**
 * RTSP server for streaming video over RTSP protocol
 *
 * Features:
 * - Multi-client support
 * - UDP unicast and TCP interleaved modes
 * - H.264 and H.265 codec support
 * - SDP generation from encoder config
 * - RTP packetization with fragmentation
 */
class RtspServer(
    private val port: Int = RtspConstants.DEFAULT_RTSP_PORT
) {
    companion object {
        private const val TAG = "RtspServer"
        private const val DEFAULT_MAX_CLIENTS = 10
        private const val ACCEPT_TIMEOUT_MS = 1000
        private const val EVICTION_CHECK_INTERVAL_MS = 30_000L
        private const val DEFAULT_SESSION_TIMEOUT_MS = 120_000L
    }

    // Configurable limits
    var maxClients: Int = DEFAULT_MAX_CLIENTS
    var sessionTimeoutMs: Long = DEFAULT_SESSION_TIMEOUT_MS

    // Server state
    private val _state = MutableStateFlow(RtspServerState.STOPPED)
    val state: StateFlow<RtspServerState> = _state.asStateFlow()

    // Server socket
    private var serverSocket: ServerSocket? = null

    // Active sessions
    private val sessions = ConcurrentHashMap<String, RtspSession>()

    // Coroutine scope for server operations
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Control flags
    private val isRunning = AtomicBoolean(false)

    // Codec configuration
    private var codec: VideoCodec = VideoCodec.H264
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var vps: ByteArray? = null

    // Server address
    private var serverAddress: String = "0.0.0.0"

    // Statistics
    private var startTimeMs = 0L
    private var totalConnections = 0L
    private var totalPacketsSent = 0L
    private var totalBytesSent = 0L

    /**
     * Start the RTSP server
     */
    fun start(): Boolean {
        if (isRunning.get()) {
            Timber.w("$TAG: Server already running")
            return true
        }

        _state.value = RtspServerState.STARTING

        return try {
            serverSocket = ServerSocket(port)
            serverSocket?.soTimeout = ACCEPT_TIMEOUT_MS
            serverAddress = SdpGenerator().getLocalIpAddress()

            isRunning.set(true)
            startTimeMs = System.currentTimeMillis()

            // Start accept loop
            serverScope.launch {
                acceptLoop()
            }

            // Start idle session eviction loop
            serverScope.launch {
                evictionLoop()
            }

            _state.value = RtspServerState.RUNNING
            Timber.i("$TAG: RTSP server started on port $port (address: $serverAddress)")
            true

        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start RTSP server")
            _state.value = RtspServerState.ERROR
            false
        }
    }

    /**
     * Stop the RTSP server
     */
    fun stop() {
        if (!isRunning.compareAndSet(true, false)) {
            return
        }

        _state.value = RtspServerState.STOPPING
        Timber.i("$TAG: Stopping RTSP server")

        // Close all sessions
        sessions.values.forEach { session ->
            try {
                session.close()
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error closing session")
            }
        }
        sessions.clear()

        // Close server socket
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error closing server socket")
        }
        serverSocket = null

        serverScope.cancel()

        _state.value = RtspServerState.STOPPED
        Timber.i("$TAG: RTSP server stopped")
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

        // Update all active sessions
        sessions.values.forEach { session ->
            session.setCodecConfig(codec, sps, pps, vps)
        }

        Timber.d("$TAG: Codec config updated - codec=$codec, sps=${sps?.size}, pps=${pps?.size}, vps=${vps?.size}")
    }

    /**
     * Update codec parameters (called when SPS/PPS change)
     */
    fun updateCodecParams(sps: ByteArray?, pps: ByteArray?, vps: ByteArray? = null) {
        this.sps = sps
        this.pps = pps
        this.vps = vps

        sessions.values.forEach { session ->
            session.setCodecConfig(codec, sps, pps, vps)
        }
    }

    /**
     * Send encoded frame to all playing clients
     */
    fun sendFrame(frame: EncodedFrame) {
        if (!isRunning.get()) return

        val playingSessions = sessions.values.filter { it.isPlaying() }
        if (playingSessions.isEmpty()) return

        for (session in playingSessions) {
            try {
                session.sendFrame(frame)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error sending frame to session ${session.sessionId}")
            }
        }

        totalPacketsSent += playingSessions.size
        totalBytesSent += frame.size * playingSessions.size
    }

    /**
     * Accept loop for incoming connections
     */
    private suspend fun acceptLoop() {
        while (isRunning.get()) {
            try {
                val clientSocket = withContext(Dispatchers.IO) {
                    try {
                        serverSocket?.accept()
                    } catch (e: SocketException) {
                        // Timeout or socket closed
                        null
                    }
                }

                clientSocket?.let { socket ->
                    handleNewConnection(socket)
                }

            } catch (e: Exception) {
                if (isRunning.get()) {
                    Timber.e(e, "$TAG: Error accepting connection")
                }
            }
        }
    }

    /**
     * Handle new client connection
     */
    private fun handleNewConnection(socket: Socket) {
        if (sessions.size >= maxClients) {
            Timber.w("$TAG: Max clients ($maxClients) reached, rejecting connection from ${socket.inetAddress?.hostAddress}")
            socket.close()
            return
        }

        totalConnections++

        val session = RtspSession(
            socket = socket,
            serverAddress = serverAddress,
            onSessionClosed = { closedSession ->
                sessions.remove(closedSession.sessionId)
                Timber.i("$TAG: Session ${closedSession.sessionId} removed, active: ${sessions.size}")
            }
        )

        // Initialize session
        session.initialize()
        session.setCodecConfig(codec, sps, pps, vps)

        // Add to active sessions
        sessions[session.sessionId] = session

        // Start request handling
        session.startRequestLoop()

        Timber.i("$TAG: New session ${session.sessionId} from ${session.clientAddress}, active: ${sessions.size}")
    }

    /**
     * Periodically check for and evict idle sessions.
     * A session is idle if it hasn't received a command or sent a frame
     * within [sessionTimeoutMs].
     */
    private suspend fun evictionLoop() {
        while (isRunning.get()) {
            delay(EVICTION_CHECK_INTERVAL_MS)
            if (!isRunning.get()) break

            val now = System.currentTimeMillis()
            val idleSessions = sessions.values.filter { session ->
                now - session.lastActivityMs > sessionTimeoutMs
            }

            for (session in idleSessions) {
                Timber.i(
                    "$TAG: Evicting idle session ${session.sessionId} " +
                    "(idle ${(now - session.lastActivityMs) / 1000}s, timeout ${sessionTimeoutMs / 1000}s)"
                )
                try {
                    session.close()
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Error evicting session ${session.sessionId}")
                }
                sessions.remove(session.sessionId)
            }
        }
    }

    /**
     * Get number of active connections
     */
    fun getActiveConnections(): Int = sessions.size

    /**
     * Get number of playing clients
     */
    fun getPlayingClients(): Int = sessions.values.count { it.isPlaying() }

    /**
     * Get all session statistics
     */
    fun getSessionStats(): List<SessionStats> {
        return sessions.values.map { it.getStats() }
    }

    /**
     * Get server statistics
     */
    fun getStats(): RtspServerStats {
        val uptimeMs = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0
        val sessionList = sessions.values.toList()
        val avgLoss = if (sessionList.isNotEmpty()) {
            sessionList.map { it.lastFractionLost / 256f * 100f }.average().toFloat()
        } else 0f
        val maxJitter = sessionList.maxOfOrNull { it.lastJitter } ?: 0L
        return RtspServerStats(
            state = _state.value,
            port = port,
            activeConnections = sessions.size,
            totalConnections = totalConnections,
            totalPacketsSent = totalPacketsSent,
            totalBytesSent = totalBytesSent,
            uptimeMs = uptimeMs,
            avgLossPercent = avgLoss,
            maxJitter = maxJitter
        )
    }

    /**
     * Get RTSP URL for this server
     */
    fun getRtspUrl(): String {
        return "rtsp://$serverAddress:$port${RtspConstants.STREAM_PATH}"
    }

    /**
     * Check if server is running
     */
    fun isRunning(): Boolean = isRunning.get()

    /**
     * Disconnect a specific session
     */
    fun disconnectSession(sessionId: String) {
        sessions[sessionId]?.close()
    }

    /**
     * Disconnect all sessions
     */
    fun disconnectAllSessions() {
        sessions.values.toList().forEach { it.close() }
    }

    /**
     * Request keyframe from encoder (for new clients)
     */
    var onKeyframeRequest: (() -> Unit)? = null

    /**
     * Called when a new client starts playing
     * Server should request a keyframe for the new client
     */
    internal fun onClientStartsPlaying() {
        onKeyframeRequest?.invoke()
    }
}

/**
 * RTSP server builder for convenient configuration
 */
class RtspServerBuilder {
    private var port: Int = RtspConstants.DEFAULT_RTSP_PORT
    private var codec: VideoCodec = VideoCodec.H264
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var vps: ByteArray? = null
    private var maxClients: Int = 10
    private var sessionTimeoutMs: Long = 120_000L
    private var onKeyframeRequest: (() -> Unit)? = null

    fun port(port: Int) = apply { this.port = port }
    fun codec(codec: VideoCodec) = apply { this.codec = codec }
    fun sps(sps: ByteArray?) = apply { this.sps = sps }
    fun pps(pps: ByteArray?) = apply { this.pps = pps }
    fun vps(vps: ByteArray?) = apply { this.vps = vps }
    fun maxClients(max: Int) = apply { this.maxClients = max }
    fun sessionTimeoutMs(timeout: Long) = apply { this.sessionTimeoutMs = timeout }
    fun onKeyframeRequest(callback: () -> Unit) = apply { this.onKeyframeRequest = callback }

    fun build(): RtspServer {
        return RtspServer(port).also { server ->
            server.setCodecConfig(codec, sps, pps, vps)
            server.maxClients = maxClients
            server.sessionTimeoutMs = sessionTimeoutMs
            server.onKeyframeRequest = onKeyframeRequest
        }
    }
}

/**
 * Extension function for creating RTSP server
 */
fun rtspServer(block: RtspServerBuilder.() -> Unit): RtspServer {
    return RtspServerBuilder().apply(block).build()
}
