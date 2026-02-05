package com.lensdaemon.output

import com.lensdaemon.encoder.EncodedFrame
import com.lensdaemon.encoder.VideoCodec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.net.*
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

enum class SrtMode { CALLER, LISTENER }

data class SrtConfig(
    val port: Int = 9000,
    val mode: SrtMode = SrtMode.LISTENER,
    val targetHost: String = "",
    val targetPort: Int = 9000,
    val latencyMs: Int = 120,
    val maxBandwidthBps: Long = 0
)

data class SrtStats(
    val isConnected: Boolean = false,
    val bytesSent: Long = 0,
    val packetsSent: Long = 0,
    val framesSent: Long = 0,
    val uptimeMs: Long = 0,
    val mode: SrtMode = SrtMode.LISTENER,
    val port: Int = 9000,
    val remoteAddress: String = ""
)

/**
 * SRT publisher that sends H.264/H.265 encoded video over UDP with MPEG-TS wrapping.
 *
 * Implements the transport layer with MPEG-TS packaging over UDP. A full SRT
 * implementation requires native libsrt; this provides the framing layer that
 * can later be upgraded with native SRT bindings.
 *
 * Features:
 * - Caller mode (push to remote) and Listener mode (accept incoming pulls)
 * - MPEG-TS packetization with periodic PAT/PMT tables
 * - H.264 and H.265 stream type support
 * - Coroutine-based async architecture with SupervisorJob
 * - Real-time statistics via StateFlow
 */
class SrtPublisher(private val config: SrtConfig = SrtConfig()) {

    companion object {
        private const val TAG = "SrtPublisher"
        private const val TS_PACKET_SIZE = 188
        private const val TS_SYNC_BYTE = 0x47.toByte()
        private const val PAT_PID = 0
        private const val PMT_PID = 4096
        private const val VIDEO_PID = 256
        private const val H264_STREAM_TYPE = 0x1B
        private const val H265_STREAM_TYPE = 0x24
        private const val PAT_PMT_INTERVAL_MS = 500L
        private const val TS_PACKETS_PER_DATAGRAM = 7
    }

    private val isRunning = AtomicBoolean(false)
    private val _stats = MutableStateFlow(SrtStats(mode = config.mode, port = config.port))
    val stats: StateFlow<SrtStats> = _stats.asStateFlow()

    private var scope: CoroutineScope? = null
    private var socket: DatagramSocket? = null
    private var remoteAddress: InetSocketAddress? = null

    private var codec: VideoCodec = VideoCodec.H264
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null
    private var vps: ByteArray? = null

    private var continuityCounters = IntArray(8192)
    private var lastPatPmtTime = 0L
    private var startTimeMs = 0L
    private var bytesSent = 0L
    private var packetsSent = 0L
    private var framesSent = 0L

    fun setCodecConfig(codec: VideoCodec, sps: ByteArray?, pps: ByteArray?, vps: ByteArray? = null) {
        this.codec = codec
        this.sps = sps
        this.pps = pps
        this.vps = vps
        Timber.d("$TAG: Codec config set - codec=$codec, sps=${sps?.size}, pps=${pps?.size}, vps=${vps?.size}")
    }

    fun start(): Boolean {
        if (isRunning.get()) {
            Timber.w("$TAG: Publisher already running")
            return true
        }
        return try {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            socket = DatagramSocket(if (config.mode == SrtMode.LISTENER) config.port else null)

            when (config.mode) {
                SrtMode.CALLER -> {
                    if (config.targetHost.isBlank()) {
                        Timber.e("$TAG: Caller mode requires a target host")
                        cleanup()
                        return false
                    }
                    remoteAddress = InetSocketAddress(InetAddress.getByName(config.targetHost), config.targetPort)
                    Timber.i("$TAG: Caller mode targeting ${config.targetHost}:${config.targetPort}")
                }
                SrtMode.LISTENER -> {
                    scope?.launch { listenerLoop() }
                    Timber.i("$TAG: Listener mode on port ${config.port}")
                }
            }

            isRunning.set(true)
            startTimeMs = System.currentTimeMillis()
            continuityCounters = IntArray(8192)
            lastPatPmtTime = 0L
            bytesSent = 0L
            packetsSent = 0L
            framesSent = 0L
            updateStats()
            Timber.i("$TAG: SRT publisher started (mode=${config.mode}, port=${config.port})")
            true
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start SRT publisher")
            cleanup()
            false
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        Timber.i("$TAG: Stopping SRT publisher")
        cleanup()
        _stats.value = SrtStats(mode = config.mode, port = config.port)
        Timber.i("$TAG: SRT publisher stopped")
    }

    fun sendFrame(frame: EncodedFrame) {
        if (!isRunning.get()) return
        val target = remoteAddress ?: return
        if (frame.isConfigFrame) return

        try {
            val now = System.currentTimeMillis()
            if (now - lastPatPmtTime >= PAT_PMT_INTERVAL_MS) {
                sendPatPmt(target)
                lastPatPmtTime = now
            }

            val pesPacket = createPesPacket(frame)
            val tsPackets = packetizeToTs(VIDEO_PID, pesPacket, frame.isKeyFrame)
            sendTsPackets(tsPackets, target)

            framesSent++
            updateStats()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error sending frame")
        }
    }

    fun isRunning(): Boolean = isRunning.get()

    fun isConnected(): Boolean = isRunning.get() && remoteAddress != null

    // --- Listener mode: wait for incoming UDP to discover peer ---

    private suspend fun listenerLoop() {
        val buf = ByteArray(TS_PACKET_SIZE)
        val packet = DatagramPacket(buf, buf.size)
        Timber.d("$TAG: Waiting for incoming connection on port ${config.port}")

        while (isRunning.get()) {
            try {
                withContext(Dispatchers.IO) {
                    socket?.soTimeout = 1000
                    socket?.receive(packet)
                }
                val peer = InetSocketAddress(packet.address, packet.port)
                if (remoteAddress == null || remoteAddress != peer) {
                    remoteAddress = peer
                    Timber.i("$TAG: Peer connected from ${peer.address.hostAddress}:${peer.port}")
                    updateStats()
                }
            } catch (_: SocketTimeoutException) {
                // Normal timeout, continue
            } catch (e: SocketException) {
                if (isRunning.get()) Timber.e(e, "$TAG: Socket error in listener loop")
                break
            } catch (e: Exception) {
                if (isRunning.get()) Timber.e(e, "$TAG: Error in listener loop")
            }
        }
    }

    // --- MPEG-TS table generation ---

    private fun sendPatPmt(target: InetSocketAddress) {
        val combined = ByteArray(TS_PACKET_SIZE * 2)
        System.arraycopy(buildPatPacket(), 0, combined, 0, TS_PACKET_SIZE)
        System.arraycopy(buildPmtPacket(), 0, combined, TS_PACKET_SIZE, TS_PACKET_SIZE)
        socket?.send(DatagramPacket(combined, combined.size, target))
        packetsSent += 2
        bytesSent += combined.size
    }

    private fun buildPatPacket(): ByteArray {
        val pkt = ByteArray(TS_PACKET_SIZE).apply { fill(0xFF.toByte()) }
        val cc = nextContinuityCounter(PAT_PID)

        pkt[0] = TS_SYNC_BYTE
        pkt[1] = 0x40; pkt[2] = 0x00 // PUSI=1, PID=0
        pkt[3] = (0x10 or (cc and 0x0F)).toByte()
        pkt[4] = 0x00 // pointer field

        val t = 5 // table start
        pkt[t] = 0x00                                                    // table_id (PAT)
        pkt[t + 1] = 0xB0.toByte(); pkt[t + 2] = 0x0D                   // section_syntax + length=13
        pkt[t + 3] = 0x00; pkt[t + 4] = 0x01                            // transport_stream_id=1
        pkt[t + 5] = 0xC1.toByte()                                      // version=0, current_next=1
        pkt[t + 6] = 0x00; pkt[t + 7] = 0x00                            // section/last section
        pkt[t + 8] = 0x00; pkt[t + 9] = 0x01                            // program_number=1
        pkt[t + 10] = (0xE0 or ((PMT_PID shr 8) and 0x1F)).toByte()     // PMT PID high
        pkt[t + 11] = (PMT_PID and 0xFF).toByte()                       // PMT PID low

        val crc = calculateCrc32(pkt, t, 12)
        pkt[t + 12] = ((crc shr 24) and 0xFF).toByte()
        pkt[t + 13] = ((crc shr 16) and 0xFF).toByte()
        pkt[t + 14] = ((crc shr 8) and 0xFF).toByte()
        pkt[t + 15] = (crc and 0xFF).toByte()
        return pkt
    }

    private fun buildPmtPacket(): ByteArray {
        val pkt = ByteArray(TS_PACKET_SIZE).apply { fill(0xFF.toByte()) }
        val cc = nextContinuityCounter(PMT_PID)
        val streamType = if (codec == VideoCodec.H264) H264_STREAM_TYPE else H265_STREAM_TYPE

        pkt[0] = TS_SYNC_BYTE
        pkt[1] = (0x40 or ((PMT_PID shr 8) and 0x1F)).toByte()
        pkt[2] = (PMT_PID and 0xFF).toByte()
        pkt[3] = (0x10 or (cc and 0x0F)).toByte()
        pkt[4] = 0x00 // pointer field

        val t = 5
        pkt[t] = 0x02                                                    // table_id (PMT)
        pkt[t + 1] = 0xB0.toByte(); pkt[t + 2] = 0x12                   // section_syntax + length=18
        pkt[t + 3] = 0x00; pkt[t + 4] = 0x01                            // program_number=1
        pkt[t + 5] = 0xC1.toByte()                                      // version=0, current_next=1
        pkt[t + 6] = 0x00; pkt[t + 7] = 0x00                            // section/last section
        pkt[t + 8] = (0xE0 or ((VIDEO_PID shr 8) and 0x1F)).toByte()    // PCR PID high
        pkt[t + 9] = (VIDEO_PID and 0xFF).toByte()                      // PCR PID low
        pkt[t + 10] = 0xF0.toByte(); pkt[t + 11] = 0x00                 // program info length=0
        pkt[t + 12] = streamType.toByte()                                // stream type
        pkt[t + 13] = (0xE0 or ((VIDEO_PID shr 8) and 0x1F)).toByte()   // elementary PID high
        pkt[t + 14] = (VIDEO_PID and 0xFF).toByte()                     // elementary PID low
        pkt[t + 15] = 0xF0.toByte(); pkt[t + 16] = 0x00                 // ES info length=0

        val crc = calculateCrc32(pkt, t, 17)
        pkt[t + 17] = ((crc shr 24) and 0xFF).toByte()
        pkt[t + 18] = ((crc shr 16) and 0xFF).toByte()
        pkt[t + 19] = ((crc shr 8) and 0xFF).toByte()
        pkt[t + 20] = (crc and 0xFF).toByte()
        return pkt
    }

    // --- PES and TS packetization ---

    private fun createPesPacket(frame: EncodedFrame): ByteArray {
        val accessUnit = if (frame.isKeyFrame) buildKeyframeAU(frame.data) else frame.data
        val pts90kHz = frame.presentationTimeUs * 90 / 1000
        val pesPayloadLen = 3 + 5 + accessUnit.size  // flags(2) + hdrLen(1) + PTS(5) + data
        val lengthField = if (pesPayloadLen > 0xFFFF) 0 else pesPayloadLen

        val buf = ByteBuffer.allocate(14 + accessUnit.size)
        buf.put(0x00); buf.put(0x00); buf.put(0x01) // start code
        buf.put(0xE0.toByte())                       // stream_id = video 0
        buf.put(((lengthField shr 8) and 0xFF).toByte())
        buf.put((lengthField and 0xFF).toByte())
        buf.put(0x80.toByte())                       // marker bits
        buf.put(0x80.toByte())                       // PTS only
        buf.put(0x05)                                // PTS header data length
        writePts(buf, pts90kHz)
        buf.put(accessUnit)
        return buf.array()
    }

    private fun buildKeyframeAU(frameData: ByteArray): ByteArray {
        val startCode = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        val parts = mutableListOf<ByteArray>()
        if (codec == VideoCodec.H265) vps?.let { parts.add(startCode); parts.add(it) }
        sps?.let { parts.add(startCode); parts.add(it) }
        pps?.let { parts.add(startCode); parts.add(it) }
        parts.add(frameData)

        val result = ByteArray(parts.sumOf { it.size })
        var off = 0
        for (part in parts) { System.arraycopy(part, 0, result, off, part.size); off += part.size }
        return result
    }

    private fun writePts(buf: ByteBuffer, pts: Long) {
        buf.put((0x21 or (((pts shr 30) and 0x07).toInt() shl 1)).toByte())
        buf.put(((pts shr 22) and 0xFF).toByte())
        buf.put((0x01 or (((pts shr 15) and 0x7F).toInt() shl 1)).toByte())
        buf.put(((pts shr 7) and 0xFF).toByte())
        buf.put((0x01 or ((pts and 0x7F).toInt() shl 1)).toByte())
    }

    private fun packetizeToTs(pid: Int, payload: ByteArray, isKeyFrame: Boolean): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        var offset = 0
        var first = true

        while (offset < payload.size) {
            val pkt = ByteArray(TS_PACKET_SIZE)
            val cc = nextContinuityCounter(pid)
            val remaining = payload.size - offset

            // TS header
            pkt[0] = TS_SYNC_BYTE
            pkt[1] = ((if (first) 0x40 else 0x00) or ((pid shr 8) and 0x1F)).toByte()
            pkt[2] = (pid and 0xFF).toByte()

            var headerSize = 4

            if (first && isKeyFrame) {
                // Adaptation field with random access indicator + PCR
                pkt[3] = (0x30 or (cc and 0x0F)).toByte()
                pkt[4] = 0x07  // adaptation field length
                pkt[5] = 0x50  // random_access=1, PCR_flag=1
                val pcr = System.currentTimeMillis() * 90
                pkt[6] = ((pcr shr 25) and 0xFF).toByte()
                pkt[7] = ((pcr shr 17) and 0xFF).toByte()
                pkt[8] = ((pcr shr 9) and 0xFF).toByte()
                pkt[9] = ((pcr shr 1) and 0xFF).toByte()
                pkt[10] = (((pcr and 0x01).toInt() shl 7) or 0x7E).toByte()
                pkt[11] = 0x00
                headerSize = 12
            } else if (remaining < TS_PACKET_SIZE - 4) {
                // Last packet needs stuffing via adaptation field
                val stuffing = TS_PACKET_SIZE - 4 - remaining - 2 // -2 for adapt_len + flags
                if (stuffing >= 0) {
                    pkt[3] = (0x30 or (cc and 0x0F)).toByte()
                    pkt[4] = (stuffing + 1).toByte()
                    pkt[5] = 0x00
                    for (i in 0 until stuffing) pkt[6 + i] = 0xFF.toByte()
                    headerSize = 6 + stuffing
                } else {
                    pkt[3] = (0x10 or (cc and 0x0F)).toByte()
                }
            } else {
                pkt[3] = (0x10 or (cc and 0x0F)).toByte()
            }

            val toCopy = remaining.coerceAtMost(TS_PACKET_SIZE - headerSize)
            System.arraycopy(payload, offset, pkt, headerSize, toCopy)
            offset += toCopy
            packets.add(pkt)
            first = false
        }
        return packets
    }

    private fun sendTsPackets(tsPackets: List<ByteArray>, target: InetSocketAddress) {
        var i = 0
        while (i < tsPackets.size) {
            val batch = (i + TS_PACKETS_PER_DATAGRAM).coerceAtMost(tsPackets.size)
            val count = batch - i
            val data = ByteArray(count * TS_PACKET_SIZE)
            for (j in 0 until count) {
                System.arraycopy(tsPackets[i + j], 0, data, j * TS_PACKET_SIZE, TS_PACKET_SIZE)
            }
            socket?.send(DatagramPacket(data, data.size, target))
            packetsSent += count
            bytesSent += data.size
            i = batch
        }
    }

    // --- Utility ---

    private fun nextContinuityCounter(pid: Int): Int {
        val cc = continuityCounters[pid]
        continuityCounters[pid] = (cc + 1) and 0x0F
        return cc
    }

    private fun calculateCrc32(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0xFFFFFFFF.toInt()
        for (i in offset until offset + length) {
            val b = data[i].toInt() and 0xFF
            for (bit in 0 until 8) {
                crc = if ((crc xor (b shl (24 + bit))) and 0x80000000.toInt() != 0) {
                    (crc shl 1) xor 0x04C11DB7
                } else {
                    crc shl 1
                }
            }
        }
        return crc
    }

    private fun updateStats() {
        val uptimeMs = if (startTimeMs > 0) System.currentTimeMillis() - startTimeMs else 0
        val remote = remoteAddress
        _stats.value = SrtStats(
            isConnected = isRunning.get() && remote != null,
            bytesSent = bytesSent,
            packetsSent = packetsSent,
            framesSent = framesSent,
            uptimeMs = uptimeMs,
            mode = config.mode,
            port = config.port,
            remoteAddress = remote?.let { "${it.address.hostAddress}:${it.port}" } ?: ""
        )
    }

    private fun cleanup() {
        scope?.cancel()
        scope = null
        try { socket?.close() } catch (e: Exception) { Timber.e(e, "$TAG: Error closing socket") }
        socket = null
        remoteAddress = null
    }
}
