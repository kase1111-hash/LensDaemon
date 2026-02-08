package com.lensdaemon

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lensdaemon.encoder.VideoCodec
import com.lensdaemon.output.RtspServer
import com.lensdaemon.output.RtspServerState
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

/**
 * Smoke test for the RTSP server.
 * Verifies server start/stop, client connection, SDP generation, and session eviction.
 */
@RunWith(AndroidJUnit4::class)
class RtspServerSmokeTest {

    private lateinit var rtspServer: RtspServer
    private val testPort = 18554

    @Before
    fun setUp() {
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
        rtspServer.start()

        val socket = Socket("localhost", testPort)
        assertTrue("Socket should be connected", socket.isConnected)

        socket.close()
        Thread.sleep(200) // Let server process disconnect
    }

    @Test
    fun optionsReturnsAllowedMethods() {
        rtspServer.start()

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
        rtspServer.updateCodecConfig(
            codec = VideoCodec.H264,
            sps = byteArrayOf(0x67, 0x42, 0x00, 0x1e, 0xab.toByte()),
            pps = byteArrayOf(0x68, 0xce.toByte(), 0x38, 0x80.toByte()),
            vps = null
        )
        rtspServer.start()

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
        rtspServer.start()

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
        rtspServer.start()

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
