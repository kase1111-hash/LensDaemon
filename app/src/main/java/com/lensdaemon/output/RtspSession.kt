package com.lensdaemon.output

import com.lensdaemon.encoder.AudioConfig
import com.lensdaemon.encoder.EncodedAudioFrame
import com.lensdaemon.encoder.EncodedFrame
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.encoder.VideoCodec
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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
    private val onSessionClosed: (RtspSession) -> Unit,
    /** Called when the client starts (or resumes) playing, so the server can ask for a keyframe. */
    private val onStartedPlaying: (RtspSession) -> Unit = {},
    /** Now, on the clock media presentation times are stamped on, in microseconds. */
    private val mediaClockUs: () -> Long = { System.nanoTime() / 1000 }
) {
    companion object {
        private const val TAG = "RtspSession"
        private const val READ_TIMEOUT_MS = 60_000

        /**
         * Frames (video and audio) buffered per client before the backlog is
         * dropped and the client resumes at the next keyframe. Roughly two
         * seconds of 30 fps video plus 48 kHz AAC (about 47 frames a second):
         * enough to ride out a WiFi hiccup, short enough that a recovering
         * viewer catches up to live quickly.
         */
        private const val OUTBOUND_QUEUE_CAPACITY = 160

        /** How often each track sends an RTCP sender report while playing. */
        private const val SENDER_REPORT_INTERVAL_MS = 5_000L

        private const val VIDEO_CLOCK_RATE = 90_000

        /**
         * If no frame write completes within this window while frames are
         * queueing, the peer is treated as gone and the session is closed.
         * Java sockets have no write timeout, so closing the socket is the only
         * way to unblock a write that is stuck on a full send buffer.
         */
        private const val STALLED_CLIENT_TIMEOUT_MS = 10_000L

        private const val WRITER_POLL_MS = 250L

        /** First byte of an interleaved binary frame on the control connection (RFC 2326 section 10.12). */
        private const val INTERLEAVED_MAGIC = '$'.code
        private const val INTERLEAVED_HEADER_LEN = 3
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

    // Transport: one entry per track the client has SET UP
    private val tracks = ConcurrentHashMap<RtspTrack.Kind, RtspTrack>()
    @Volatile
    private var rtpPacketizer: RtpPacketizer? = null
    @Volatile
    private var aacPacketizer: AacRtpPacketizer? = null

    /** The AAC stream on offer, or null when the server has no audio. */
    @Volatile
    var audioConfig: AudioConfig? = null

    // Codec info
    private var codec: VideoCodec = VideoCodec.H264
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var vps: ByteArray? = null

    /** Encoder settings this session advertises in its SDP (frame rate, bandwidth, profile fallback). */
    @Volatile
    var streamConfig: EncoderConfig = EncoderConfig()

    /**
     * Starts this client on a self-describing keyframe. Guarded by itself:
     * frames arrive on the encoder thread, PLAY on the request thread and
     * parameter-set updates on the server's.
     */
    private var aligner = KeyframeAligner(isHevc = false)

    // I/O streams
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var input: BufferedInputStream? = null

    // Coroutine scope for this session
    private val sessionScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Control flags
    private val isRunning = AtomicBoolean(true)
    private val isPlaying = AtomicBoolean(false)

    // Statistics
    private val packetsSent = AtomicLong(0)
    private val bytesSent = AtomicLong(0)
    private val framesDropped = AtomicLong(0)
    private var startTimeMs = 0L

    /**
     * Outbound frames are handed to a per-session writer thread rather than
     * written on the caller's thread. The encoder dispatches frames to every
     * output in turn, so a blocking write here would stall RTSP delivery to all
     * other viewers, the MPEG-TS publisher and the recorder along with it.
     */
    private val outboundQueue = ArrayBlockingQueue<Outbound>(OUTBOUND_QUEUE_CAPACITY)
    private var writerThread: Thread? = null

    @Volatile
    private var lastWriteCompletedMs: Long = System.currentTimeMillis()

    // RTCP feedback
    @Volatile var lastFractionLost: Int = 0; private set
    @Volatile var lastCumulativeLost: Int = 0; private set
    @Volatile var lastJitter: Long = 0; private set

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
            input = BufferedInputStream(inputStream ?: return)
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
        val codecChanged = this.codec != codec
        this.codec = codec
        this.sps = sps
        this.pps = pps
        this.vps = vps

        // Keep a playing session's packetizer (SSRC, sequence and timestamp
        // base) when only the parameter sets change: replacing it mid-stream
        // looks like a brand new source to the player.
        if (rtpPacketizer == null || codecChanged) {
            rtpPacketizer = when (codec) {
                VideoCodec.H264 -> RtpPacketizerFactory.createH264Packetizer()
                VideoCodec.H265 -> RtpPacketizerFactory.createH265Packetizer()
            }
        }
        synchronized(this) {
            if (codecChanged) aligner = KeyframeAligner(isHevc = codec == VideoCodec.H265)
            aligner.setParameterSets(vps, sps, pps)
        }
    }

    /**
     * Start reading and processing RTSP requests
     */
    fun startRequestLoop() {
        sessionScope.launch {
            try {
                while (isRunning.get() && !socket.isClosed) {
                    val request = awaitRequest() ?: break

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
     * Block until the client sends a request. Returns null once the peer has
     * hung up or the session is closing.
     *
     * Silence is not a failure. RFC 2326 lets ongoing RTP stand in for a
     * keepalive, and plenty of players send no commands at all while playing,
     * so a read timeout simply waits again. Genuinely dead sessions are reaped
     * by the server's idle-eviction loop instead.
     */
    private fun awaitRequest(): RtspRequest? {
        while (isRunning.get() && !socket.isClosed) {
            try {
                return readRequest()
            } catch (e: SocketTimeoutException) {
                Timber.tag(TAG).v(
                    "Session $sessionId: no RTSP command for ${READ_TIMEOUT_MS / 1000}s (${e.message}); still alive"
                )
            }
        }
        return null
    }

    /**
     * Read the next RTSP request from the control connection.
     *
     * A TCP-interleaved client sends its RTCP receiver reports on this same
     * connection as binary frames ("$", channel, length, data; RFC 2326
     * section 10.12). Those are consumed here, folded into the session
     * statistics, and never reach the request parser.
     *
     * Returns null once the peer has hung up or sent something unparseable.
     * A read timeout while waiting at a message boundary propagates so the
     * caller can keep waiting; one that strikes mid-message leaves the stream
     * out of sync and is treated as a dead connection.
     */
    private fun readRequest(): RtspRequest? {
        val input = this.input ?: return null
        var atBoundary = true
        try {
            var first = input.read()
            while (first == INTERLEAVED_MAGIC) {
                atBoundary = false
                consumeInterleavedFrame(input)
                atBoundary = true
                first = input.read()
            }
            if (first < 0) return null
            atBoundary = false
            return readRtspMessage(input, first)
        } catch (e: SocketTimeoutException) {
            // Silence at a message boundary is fine: the caller keeps waiting.
            if (atBoundary) throw e
            Timber.w("$TAG: Session $sessionId stalled mid-message (${e.message}); dropping connection")
            return null
        } catch (e: IOException) {
            if (isRunning.get()) {
                Timber.w("$TAG: Session $sessionId connection ended (${e.message})")
            }
            return null
        }
    }

    /**
     * Consume one interleaved frame and apply any RTCP receiver reports it
     * carries. Anything the client sends counts as liveness.
     */
    private fun consumeInterleavedFrame(input: InputStream) {
        val header = ByteArray(INTERLEAVED_HEADER_LEN)
        readFully(input, header)
        val channel = header[0].toInt() and 0xFF
        val length = ((header[1].toInt() and 0xFF) shl 8) or (header[2].toInt() and 0xFF)
        val data = ByteArray(length)
        readFully(input, data)

        lastActivityMs = System.currentTimeMillis()
        if (tracks.values.any { it.isInterleaved && it.params.interleavedRtcpChannel == channel }) {
            applyReceiverReports(RtcpParser.parseReceiverReports(data))
        } else {
            Timber.tag(TAG).v("Session $sessionId: ignoring $length-byte interleaved frame on channel $channel")
        }
    }

    /**
     * Fold client receiver reports into the session's loss and jitter metrics.
     */
    private fun applyReceiverReports(reports: List<RtcpParser.ReceiverReport>) {
        for (report in reports) {
            lastFractionLost = report.fractionLost
            lastCumulativeLost = report.cumulativeLost
            lastJitter = report.jitter
            lastActivityMs = System.currentTimeMillis()

            if (report.lossPercent > 5f) {
                Timber.tag(TAG).w(
                    "Session $sessionId: loss=${report.lossPercent}%, jitter=${report.jitter}"
                )
            }
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

        // Describe the stream at the address this client actually reached us
        // on; the server-wide guess can be another interface (mobile data).
        val localAddress = (socket.localAddress as? Inet4Address)?.hostAddress ?: serverAddress

        val sdp = sdpGenerator.generateSdp(
            serverAddress = localAddress,
            serverPort = RtspConstants.DEFAULT_RTP_PORT,
            sessionName = "LensDaemon Stream",
            config = streamConfig.copy(codec = codec),
            vps = vps,
            sps = sps,
            pps = pps,
            trackId = "trackID=0"
        ) + (audioConfig?.let { sdpGenerator.audioMediaSection(it) } ?: "")

        return RtspResponse.ok(request.cseq)
            .addHeader(RtspConstants.HEADER_CONTENT_BASE, request.uri + "/")
            .setBody(sdp, RtspConstants.CONTENT_TYPE_SDP)
    }

    /**
     * Handle SETUP for one track: the video track (trackID=0, or the
     * aggregate URL from clients that only ever ask for one track) or the
     * audio track (trackID=1).
     */
    private fun handleSetup(request: RtspRequest): RtspResponse {
        val transport = request.transport
            ?: return RtspResponse.badRequest(request.cseq)

        val params = TransportParams.parse(transport)
            ?: return RtspResponse.unsupportedTransport(request.cseq)
        if (params.mode == RtspTransportMode.UDP_MULTICAST) {
            return RtspResponse.unsupportedTransport(request.cseq)
        }

        val kind = if (request.uri.trimEnd('/').endsWith(SdpGenerator.AUDIO_TRACK_ID)) {
            RtspTrack.Kind.AUDIO
        } else {
            RtspTrack.Kind.VIDEO
        }
        val audio = audioConfig
        if (kind == RtspTrack.Kind.AUDIO && audio == null) {
            return RtspResponse(RtspStatusCode.NOT_FOUND, request.cseq)
        }

        val track = RtspTrack(kind, params, clientAddress, outputStream, ::applyReceiverReports)
        if (!track.open(sessionScope)) {
            return RtspResponse(RtspStatusCode.INTERNAL_ERROR, request.cseq)
        }
        tracks.put(kind, track)?.close()

        val ssrc = if (kind == RtspTrack.Kind.AUDIO && audio != null) {
            val packetizer = aacPacketizer?.takeIf { it.clockRate == audio.sampleRate }
                ?: AacRtpPacketizer(audio.sampleRate).also { aacPacketizer = it }
            packetizer.getSsrc()
        } else {
            rtpPacketizer?.getSsrc() ?: 0L
        }

        state = SessionState.READY
        return RtspResponse.ok(request.cseq)
            .setSession(sessionId)
            .addHeader(
                RtspConstants.HEADER_TRANSPORT,
                params.toResponseHeader(track.serverRtpPort, track.serverRtcpPort, ssrc)
            )
    }

    /**
     * Handle PLAY request - start streaming
     */
    private fun handlePlay(request: RtspRequest): RtspResponse {
        if (state != SessionState.READY && state != SessionState.PAUSED) {
            return RtspResponse(RtspStatusCode.METHOD_NOT_VALID, request.cseq)
        }

        // Start the client on a keyframe that carries its parameter sets, and
        // ask the encoder for one now rather than leaving the player waiting
        // up to a full GOP for the next scheduled keyframe.
        synchronized(this) { aligner.reset() }
        outboundQueue.clear()
        state = SessionState.PLAYING
        isPlaying.set(true)
        startTimeMs = System.currentTimeMillis()
        startWriterLoop()
        onStartedPlaying(this)

        // RTP-Info header, one entry per track set up
        val base = request.uri.trimEnd('/')
        val rtpInfo = tracks.keys.sortedBy { it.trackId }.joinToString(",") { kind ->
            val seq = when (kind) {
                RtspTrack.Kind.AUDIO -> aacPacketizer?.getSequenceNumber()
                RtspTrack.Kind.VIDEO -> rtpPacketizer?.getSequenceNumber()
            }
            "url=$base/trackID=${kind.trackId};seq=${seq ?: 0};rtptime=0"
        }

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
            val data = response.build().toByteArray()
            val out = outputStream ?: return
            // Interleaved RTP shares this stream with the writer thread. Hold
            // the same lock so a response never lands inside an RTP frame.
            synchronized(out) {
                out.write(data)
                out.flush()
            }
            Timber.v("$TAG: Sent response to $sessionId")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error sending response")
        }
    }

    /**
     * Queue an encoded frame for delivery to this client.
     *
     * Never blocks: a client that cannot keep up loses frames rather than
     * holding up the encoder and every other output. Pictures before the
     * client's first keyframe are held back, and codec-config buffers are
     * folded into the keyframes instead of being sent on their own.
     */
    fun sendFrame(frame: EncodedFrame) {
        if (!isPlaying.get()) return
        if (rtpPacketizer == null) return

        val accessUnit = synchronized(this) { aligner.process(frame) } ?: return
        val outbound = Outbound.Video(
            if (accessUnit === frame.data) frame else frame.copy(data = accessUnit, size = accessUnit.size)
        )

        if (outboundQueue.offer(outbound)) return

        // Backlog is full. Pictures depend on the ones before them, so dropping
        // some of the backlog would leave the viewer decoding garbage until the
        // next keyframe. Drop all of it and resume cleanly at a keyframe (this
        // one, if it is one): the client sees a brief freeze and then picks up
        // at live.
        val discarded = outboundQueue.size
        outboundQueue.clear()
        if (frame.isKeyFrame) {
            outboundQueue.offer(outbound)
            framesDropped.addAndGet(discarded.toLong())
        } else {
            framesDropped.addAndGet(discarded + 1L)
            synchronized(this) { aligner.reset() }
        }

        // Frames are piling up and nothing is draining: the peer has stopped
        // reading. Close the socket, which also unblocks the stuck write.
        val stalledMs = System.currentTimeMillis() - lastWriteCompletedMs
        if (stalledMs > STALLED_CLIENT_TIMEOUT_MS) {
            Timber.tag(TAG).w(
                "Session $sessionId stalled: no frame written for ${stalledMs / 1000}s, " +
                    "${framesDropped.get()} frames dropped. Disconnecting client."
            )
            close()
        }
    }

    /**
     * Queue an AAC frame for this client, if it set up the audio track.
     *
     * Audio waits for the video's first keyframe when the client also plays
     * video, so playback starts with both. Never blocks: when the backlog is
     * full the frame is dropped and the video path deals with the stall.
     */
    fun sendAudio(frame: EncodedAudioFrame) {
        if (!isPlaying.get() || frame.isConfig) return
        if (!tracks.containsKey(RtspTrack.Kind.AUDIO) || aacPacketizer == null) return
        if (tracks.containsKey(RtspTrack.Kind.VIDEO) && !synchronized(this) { aligner.started }) return
        if (!outboundQueue.offer(Outbound.Audio(frame))) framesDropped.incrementAndGet()
    }

    /**
     * Drains the outbound queue onto the socket. Blocking writes are confined
     * to this thread, so a wedged client costs only its own session.
     */
    private fun startWriterLoop() {
        if (writerThread != null) return
        lastWriteCompletedMs = System.currentTimeMillis()

        writerThread = Thread({
            val self = Thread.currentThread()
            while (isRunning.get() && !socket.isClosed && !self.isInterrupted) {
                when (val next = pollOutboundFrame()) {
                    is Outbound.Video -> writeFrame(next.frame)
                    is Outbound.Audio -> writeAudio(next.frame)
                    null -> Unit
                }
            }
            Timber.tag(TAG).d("Session $sessionId writer loop exited")
        }, "rtsp-writer-$sessionId").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Wait briefly for the next queued frame. Returns null when nothing arrived
     * in time or the writer thread was interrupted; the interrupt flag is
     * restored so the writer loop condition sees it and exits.
     */
    private fun pollOutboundFrame(): Outbound? {
        return try {
            outboundQueue.poll(WRITER_POLL_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    /**
     * Packetize one frame and write it to the client on the writer thread.
     */
    private fun writeFrame(frame: EncodedFrame) {
        val packetizer = rtpPacketizer ?: return
        val track = tracks[RtspTrack.Kind.VIDEO] ?: return
        try {
            sendReportIfDue(track, VIDEO_CLOCK_RATE, packetizer.getSsrc())
            val packets = packetizer.packetize(frame.data, frame.presentationTimeUs)
            for (packet in packets) {
                track.sendRtp(packet)
            }
            packetsSent.addAndGet(packets.size.toLong())
            bytesSent.addAndGet(frame.size.toLong())

            val now = System.currentTimeMillis()
            lastWriteCompletedMs = now
            // Over TCP a completed write proves the peer's host is still there,
            // so it stands in for a keepalive. Over UDP it proves nothing: a
            // datagram to a vanished peer never fails, so a silent UDP viewer
            // must keep sending RTCP or RTSP keepalives or be evicted as idle.
            if (track.isInterleaved) {
                lastActivityMs = now
            }
        } catch (e: IOException) {
            if (isRunning.get()) {
                Timber.w("$TAG: Session $sessionId: write failed (${e.message}); closing")
                close()
            }
        } catch (e: Exception) {
            if (isRunning.get()) {
                Timber.e(e, "$TAG: Error sending frame to session $sessionId")
            }
        }
    }

    /** Packetize one AAC frame and write it to the client on the writer thread. */
    private fun writeAudio(frame: EncodedAudioFrame) {
        val packetizer = aacPacketizer ?: return
        val track = tracks[RtspTrack.Kind.AUDIO] ?: return
        val packet = packetizer.packetize(frame.data, frame.presentationTimeUs) ?: return
        try {
            sendReportIfDue(track, packetizer.clockRate, packetizer.getSsrc())
            track.sendRtp(packet)
            packetsSent.incrementAndGet()
            bytesSent.addAndGet(frame.size.toLong())
        } catch (e: IOException) {
            if (isRunning.get()) {
                Timber.w("$TAG: Session $sessionId: audio write failed (${e.message}); closing")
                close()
            }
        }
    }

    /**
     * Send [track] an RTCP sender report before its first packet and every
     * few seconds after. Every track maps the same media-clock instant to the
     * same wall-clock time, which is what lets players sync audio to video.
     */
    private fun sendReportIfDue(track: RtspTrack, clockRate: Int, ssrc: Long) {
        val nowMs = System.currentTimeMillis()
        if (track.lastSenderReportMs != 0L && nowMs - track.lastSenderReportMs < SENDER_REPORT_INTERVAL_MS) return
        val mediaNowUs = mediaClockUs()
        val report = RtcpSenderReport.build(
            ssrc = ssrc,
            wallClockMs = nowMs,
            rtpTimestamp = mediaNowUs * clockRate / 1_000_000,
            packetCount = track.packetsSent,
            octetCount = track.octetsSent,
            cname = "lensdaemon@$serverAddress"
        )
        track.sendRtcp(report)
        track.lastSenderReportMs = nowMs
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
            durationMs = durationMs,
            fractionLost = lastFractionLost,
            cumulativeLost = lastCumulativeLost,
            jitter = lastJitter,
            framesDropped = framesDropped.get()
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

        // Closing the socket is what unblocks a writer stuck on a full send
        // buffer, so it has to happen before waiting on that thread.
        try {
            tracks.values.forEach { it.close() }
            inputStream?.close()
            outputStream?.close()
            socket.close()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error closing session")
        }

        outboundQueue.clear()
        writerThread?.interrupt()
        writerThread = null

        sessionScope.cancel()
        onSessionClosed(this)
    }
}

/** What a session's writer thread sends next. */
private sealed interface Outbound {
    class Video(val frame: EncodedFrame) : Outbound
    class Audio(val frame: EncodedAudioFrame) : Outbound
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
    val durationMs: Long,
    val fractionLost: Int = 0,
    val cumulativeLost: Int = 0,
    val jitter: Long = 0,
    /** Frames discarded because this client could not keep up with the stream. */
    val framesDropped: Long = 0
) {
    val bitrateBps: Long
        get() = if (durationMs > 0) (bytesSent * 8 * 1000) / durationMs else 0
    val lossPercent: Float
        get() = (fractionLost / 256f) * 100f
}

/** Longest header line accepted from a client before the connection is dropped. */
private const val MAX_LINE_BYTES = 8192

/** Largest request body accepted from a client. */
private const val MAX_BODY_BYTES = 65536

/**
 * Read one RTSP message whose first byte, [firstByte], has already been
 * consumed. Returns null if the stream ends first.
 */
private fun readRtspMessage(input: InputStream, firstByte: Int): RtspRequest? {
    val sb = StringBuilder()
    var contentLength = 0

    var line = readAsciiLine(input, firstByte) ?: return null
    while (line.isNotEmpty()) {
        sb.append(line).append("\r\n")
        if (line.startsWith("Content-Length:", ignoreCase = true)) {
            contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
        }
        line = readAsciiLine(input) ?: return null
    }
    sb.append("\r\n")

    if (contentLength > MAX_BODY_BYTES) {
        throw IOException("RTSP body of $contentLength bytes exceeds $MAX_BODY_BYTES")
    }
    if (contentLength > 0) {
        val body = ByteArray(contentLength)
        readFully(input, body)
        sb.append(String(body, Charsets.ISO_8859_1))
    }
    return RtspRequest.parse(sb.toString())
}

/**
 * Read one CRLF-terminated line as ISO-8859-1 without its terminator.
 * [firstByte], when non-negative, is a byte already consumed that starts the
 * line. Returns null at end of stream.
 */
private fun readAsciiLine(input: InputStream, firstByte: Int = -1): String? {
    val bytes = ByteArrayOutputStream()
    var b = if (firstByte >= 0) firstByte else input.read()
    while (b >= 0 && b != '\n'.code) {
        bytes.write(b)
        if (bytes.size() > MAX_LINE_BYTES) {
            throw IOException("RTSP header line exceeds $MAX_LINE_BYTES bytes")
        }
        b = input.read()
    }
    if (b < 0 && bytes.size() == 0) return null
    return String(bytes.toByteArray(), Charsets.ISO_8859_1).removeSuffix("\r")
}

/** Fill [buffer] completely or throw [EOFException]. */
private fun readFully(input: InputStream, buffer: ByteArray) {
    var offset = 0
    while (offset < buffer.size) {
        val n = input.read(buffer, offset, buffer.size - offset)
        if (n < 0) throw EOFException("Connection closed after $offset of ${buffer.size} bytes")
        offset += n
    }
}
