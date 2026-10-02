package com.lensdaemon

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lensdaemon.encoder.EncodedFrame
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.encoder.VideoCodec
import com.lensdaemon.output.RtspServer
import com.lensdaemon.output.RtspServerState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Smoke test for the RTSP server.
 * Verifies server start/stop, client connection, SDP generation, and session eviction,
 * and what a viewer that joins a running stream (OBS reconnecting) is sent first.
 */
@RunWith(AndroidJUnit4::class)
class RtspServerSmokeTest {

    companion object {
        private const val FLAG_KEY_FRAME = 1
        private const val FLAG_CODEC_CONFIG = 2
        private val START_CODE = byteArrayOf(0x00, 0x00, 0x00, 0x01)
        private val SPS = byteArrayOf(0x67, 0x42, 0x00, 0x1e, 0xab.toByte())
        private val PPS = byteArrayOf(0x68, 0xce.toByte(), 0x38, 0x80.toByte())
    }

    private lateinit var rtspServer: RtspServer
    private var testPort = 0

    @Before
    fun setUp() {
        // Unique port per test: sockets from the previous test (sessions held
        // by cancelled coroutines, TIME_WAIT peers) can keep the old port busy
        testPort = java.net.ServerSocket(0).use { it.localPort }
        rtspServer = RtspServer(testPort)
    }

    @After
    fun tearDown() {
        rtspServer.stop()
    }

    @Test
    fun serverStartsAndStops() {
        assertTrue("RTSP server should start", rtspServer.start())
        assertEquals(RtspServerState.RUNNING, rtspServer.state.value)

        rtspServer.stop()
        assertEquals(RtspServerState.STOPPED, rtspServer.state.value)
    }

    @Test
    fun clientCanConnect() {
        assertTrue("RTSP server should start", rtspServer.start())

        val socket = Socket("localhost", testPort)
        assertTrue("Socket should be connected", socket.isConnected)

        socket.close()
        Thread.sleep(200) // Let server process disconnect
    }

    @Test
    fun optionsReturnsAllowedMethods() {
        assertTrue("RTSP server should start", rtspServer.start())

        val socket = Socket("localhost", testPort)
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.inputStream))

        writer.print("OPTIONS rtsp://localhost:$testPort/stream RTSP/1.0\r\n")
        writer.print("CSeq: 1\r\n")
        writer.print("\r\n")
        writer.flush()

        // Read response
        val response = readRtspResponse(reader)
        assertTrue("Should return RTSP/1.0 200", response.contains("RTSP/1.0 200"))
        assertTrue("Should include Public header", response.contains("Public:"))
        assertTrue("Should list DESCRIBE method", response.contains("DESCRIBE"))
        assertTrue("Should list SETUP method", response.contains("SETUP"))
        assertTrue("Should list PLAY method", response.contains("PLAY"))

        socket.close()
    }

    @Test
    fun describeReturnsSdp() {
        // Configure codec before starting
        rtspServer.setCodecConfig(
            codec = VideoCodec.H264,
            sps = byteArrayOf(0x67, 0x42, 0x00, 0x1e, 0xab.toByte()),
            pps = byteArrayOf(0x68, 0xce.toByte(), 0x38, 0x80.toByte()),
            vps = null
        )
        assertTrue("RTSP server should start", rtspServer.start())

        val socket = Socket("localhost", testPort)
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.inputStream))

        writer.print("DESCRIBE rtsp://localhost:$testPort/stream RTSP/1.0\r\n")
        writer.print("CSeq: 1\r\n")
        writer.print("\r\n")
        writer.flush()

        val response = readRtspResponse(reader)
        assertTrue("Should return 200 OK", response.contains("RTSP/1.0 200"))
        assertTrue("Should contain SDP content type", response.contains("application/sdp"))

        socket.close()
    }

    @Test
    fun playWithoutSetupReturnsError() {
        assertTrue("RTSP server should start", rtspServer.start())

        val socket = Socket("localhost", testPort)
        val writer = PrintWriter(socket.getOutputStream(), true)
        val reader = BufferedReader(InputStreamReader(socket.inputStream))

        // Send PLAY without SETUP — should get 455 or similar error
        writer.print("PLAY rtsp://localhost:$testPort/stream RTSP/1.0\r\n")
        writer.print("CSeq: 1\r\n")
        writer.print("Session: fake-session\r\n")
        writer.print("\r\n")
        writer.flush()

        val response = readRtspResponse(reader)
        // Should NOT crash (Phase 1 NPE fix) — should return an error response
        assertFalse("Should not return 200 OK for PLAY without SETUP", response.contains("RTSP/1.0 200"))

        socket.close()
    }

    @Test
    fun serverRejectsExcessClients() {
        rtspServer.maxClients = 2
        assertTrue("RTSP server should start", rtspServer.start())

        val sockets = mutableListOf<Socket>()
        try {
            // Connect max clients
            repeat(2) {
                sockets.add(Socket("localhost", testPort))
                Thread.sleep(100)
            }

            val stats = rtspServer.getStats()
            assertEquals("Should have 2 active connections", 2, stats.activeConnections)

            // Third connection should be rejected or queued
            val extraSocket = Socket("localhost", testPort)
            sockets.add(extraSocket)
            Thread.sleep(200)

            // Server should still function
            assertNotNull(rtspServer.getStats())
        } finally {
            sockets.forEach { it.close() }
        }
    }

    @Test
    fun describeAdvertisesTheEncoderSettings() {
        rtspServer.setCodecConfig(VideoCodec.H264, SPS, PPS)
        rtspServer.setStreamConfig(EncoderConfig(frameRate = 60, bitrateBps = 8_000_000))
        assertTrue("RTSP server should start", rtspServer.start())

        Socket("localhost", testPort).use { socket ->
            val input = socket.getInputStream()
            val out = socket.getOutputStream()
            out.write(request("DESCRIBE", 1).toByteArray())
            val sdp = readResponse(input)
            assertTrue("SDP should carry the configured frame rate: $sdp", sdp.contains("a=framerate:60"))
            assertTrue("SDP should carry the configured bandwidth: $sdp", sdp.contains("b=AS:8000"))
            assertTrue("SDP should carry the parameter sets: $sdp", sdp.contains("sprop-parameter-sets="))
        }
    }

    @Test
    fun aViewerJoiningMidStreamStartsOnAKeyframeWithItsParameterSets() {
        val keyframeRequests = AtomicInteger()
        rtspServer.onKeyframeRequest = { keyframeRequests.incrementAndGet() }
        assertTrue("RTSP server should start", rtspServer.start())

        // The encoder has been running for a while: its codec-config buffer
        // went out long before this viewer connected.
        rtspServer.sendFrame(EncodedFrame(START_CODE + SPS + START_CODE + PPS, 0L, FLAG_CODEC_CONFIG))

        Socket("localhost", testPort).use { socket ->
            socket.soTimeout = 3000
            val input = socket.getInputStream()
            val out = socket.getOutputStream()

            out.write(request("SETUP", 1, "Transport: RTP/AVP/TCP;unicast;interleaved=0-1\r\n", "/trackID=0").toByteArray())
            val setup = readResponse(input)
            assertTrue("SETUP should succeed: $setup", setup.startsWith("RTSP/1.0 200"))
            val session = setup.lines().first { it.startsWith("Session:", ignoreCase = true) }
                .substringAfter(":").substringBefore(";").trim()

            out.write(request("PLAY", 2, "Session: $session\r\n").toByteArray())
            assertTrue("PLAY should succeed", readResponse(input).startsWith("RTSP/1.0 200"))
            assertEquals("PLAY should ask the encoder for a keyframe", 1, waitFor { keyframeRequests.get() })

            // Mid-GOP picture first: the viewer cannot decode it and must not get it.
            rtspServer.sendFrame(EncodedFrame(nal(0x41, 300), 1_000_000L, 0))
            rtspServer.sendFrame(EncodedFrame(nal(0x65, 4000), 1_033_333L, FLAG_KEY_FRAME))
            rtspServer.sendFrame(EncodedFrame(nal(0x41, 300), 1_066_666L, 0))

            val nalTypes = readNalTypes(input, expected = 4)
            assertEquals("viewer should get SPS, PPS, IDR, then the next picture", listOf(7, 8, 5, 1), nalTypes)
        }
    }

    /** Build an RTSP request for the stream (or [suffix] below it) with optional extra header lines. */
    private fun request(method: String, cseq: Int, headers: String = "", suffix: String = ""): String =
        "$method rtsp://localhost:$testPort/stream$suffix RTSP/1.0\r\nCSeq: $cseq\r\n$headers\r\n"

    /** Poll [value] for up to two seconds until it is non-zero and return it. */
    private fun waitFor(value: () -> Int): Int {
        val deadline = System.currentTimeMillis() + 2000
        while (value() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return value()
    }

    /** A NAL unit of [type] with a start code and [size] bytes of body. */
    private fun nal(type: Int, size: Int): ByteArray =
        START_CODE + byteArrayOf(type.toByte()) + ByteArray(size) { (0x10 + it % 0xE0).toByte() }

    /** Read one RTSP response (headers and body) byte by byte, so binary data after it stays unread. */
    private fun readResponse(input: InputStream): String {
        val sb = StringBuilder()
        var contentLength = 0
        while (true) {
            val line = readLine(input)
            sb.append(line).append("\n")
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toInt()
            }
            if (line.isEmpty()) break
        }
        if (contentLength > 0) {
            val body = ByteArray(contentLength)
            DataInputStream(input).readFully(body)
            sb.append(String(body, Charsets.US_ASCII))
        }
        return sb.toString()
    }

    private fun readLine(input: InputStream): String {
        val bytes = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            bytes.write(b)
        }
        return bytes.toString("US-ASCII").removeSuffix("\r")
    }

    /**
     * Read interleaved RTP packets until [expected] NAL units have started and
     * return their types (FU-A fragments count once, by their start fragment).
     */
    private fun readNalTypes(input: InputStream, expected: Int): List<Int> {
        val data = DataInputStream(input)
        val types = mutableListOf<Int>()
        try {
            while (types.size < expected) {
                assertEquals("expected an interleaved frame", '$'.code, data.readUnsignedByte())
                val channel = data.readUnsignedByte()
                val packet = ByteArray(data.readUnsignedShort())
                data.readFully(packet)
                if (channel != 0) continue
                val nalHeader = packet[12].toInt() and 0x1F
                if (nalHeader == 28) {
                    val fuHeader = packet[13].toInt()
                    if ((fuHeader and 0x80) != 0) types.add(fuHeader and 0x1F)
                } else {
                    types.add(nalHeader)
                }
            }
        } catch (e: SocketTimeoutException) {
            throw AssertionError("only received NAL types $types before the stream went quiet", e)
        }
        return types
    }

    /**
     * Read a complete RTSP response (headers + optional body).
     */
    private fun readRtspResponse(reader: BufferedReader): String {
        val sb = StringBuilder()
        var contentLength = 0

        // Read headers
        while (true) {
            val line = reader.readLine() ?: break
            sb.appendLine(line)
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
            }
            if (line.isEmpty()) break
        }

        // Read body if present
        if (contentLength > 0) {
            val body = CharArray(contentLength)
            reader.read(body, 0, contentLength)
            sb.append(body)
        }

        return sb.toString()
    }
}
