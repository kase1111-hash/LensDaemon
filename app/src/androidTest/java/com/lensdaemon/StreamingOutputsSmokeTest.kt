package com.lensdaemon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import androidx.test.rule.ServiceTestRule
import com.lensdaemon.camera.CameraService
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.encoder.VideoCodec
import com.lensdaemon.output.MpegTsMode
import com.lensdaemon.output.MpegTsUdpConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket

/**
 * The outputs (RTSP, MPEG-TS, recording) share one encoder through
 * [CameraService]: an output joins a running encoder instead of restarting it,
 * and stopping one output leaves the others streaming.
 *
 * Runs the real camera and encoder at a small size so it works on the
 * emulator's virtual camera. Skipped on devices without a back camera.
 */
@RunWith(AndroidJUnit4::class)
class StreamingOutputsSmokeTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule
    val serviceRule = ServiceTestRule()

    private lateinit var service: CameraService

    private val config = EncoderConfig(
        codec = VideoCodec.H264,
        resolution = Size(640, 480),
        bitrateBps = 1_000_000,
        frameRate = 30
    )

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val binder = serviceRule.bindService(Intent(context, CameraService::class.java))
        service = (binder as CameraService.LocalBinder).getService()
        assumeTrue("No back camera on this device", service.getAvailableLenses().isNotEmpty())
        // The emulator's virtual camera does not offer the default 1080p frame-access size
        service.setResolution(640, 480)
    }

    @After
    fun tearDown() {
        if (this::service.isInitialized) service.stopStreaming()
    }

    @Test
    fun stoppingOneOutputLeavesTheOtherStreaming() {
        val rtspPort = ServerSocket(0).use { it.localPort }

        // The encoder service binds asynchronously after the camera service starts
        assertTrue("RTSP streaming should start", retryFor(10_000) { service.startRtspStreaming(config, rtspPort) })
        assertTrue("encoder should produce frames", waitUntil(20_000) { (service.getEncoderStats()?.framesEncoded ?: 0) > 5 })

        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            val mpegts = MpegTsUdpConfig(mode = MpegTsMode.CALLER, targetHost = "127.0.0.1", targetPort = receiver.localPort)
            val otherSettings = config.copy(resolution = Size(1280, 720))
            assertTrue("MPEG-TS should start", service.startMpegTsStreaming(otherSettings, mpegts))
            assertEquals("MPEG-TS should join the running encoder, not restart it", 640, service.getEncoderConfig()?.width)

            service.stopRtspStreaming()
            assertFalse("RTSP server should be stopped", service.isRtspServerRunning())
            assertTrue("encoder should keep running for MPEG-TS", service.isStreaming())

            val sentBefore = service.getMpegTsStats()?.framesSent ?: 0
            assertTrue(
                "MPEG-TS should keep sending after RTSP stopped",
                waitUntil(10_000) { (service.getMpegTsStats()?.framesSent ?: 0) > sentBefore + 10 }
            )
            receiver.soTimeout = 5_000
            val buffer = ByteArray(2048)
            receiver.receive(DatagramPacket(buffer, buffer.size))

            service.stopMpegTsStreaming()
            assertFalse("encoder should stop once no output needs it", service.isStreaming())
        }
    }

    @Test
    fun startingAnOutputTwiceDoesNotDuplicateFrames() {
        val rtspPort = ServerSocket(0).use { it.localPort }
        assertTrue("RTSP streaming should start", retryFor(10_000) { service.startRtspStreaming(config, rtspPort) })

        DatagramSocket(0, InetAddress.getLoopbackAddress()).use { receiver ->
            val mpegts = MpegTsUdpConfig(mode = MpegTsMode.CALLER, targetHost = "127.0.0.1", targetPort = receiver.localPort)
            assertTrue(service.startMpegTsStreaming(config, mpegts))
            assertTrue("starting again with the same settings should be accepted", service.startMpegTsStreaming(config, mpegts))
            assertTrue("frames should be published", waitUntil(30_000) { (service.getMpegTsStats()?.framesSent ?: 0) > 90 })

            // Encoder stats refresh every 500 ms, so they may trail by a few
            // frames; a doubled listener would send twice as many as encoded.
            val sent = service.getMpegTsStats()?.framesSent ?: 0
            val encoded = service.getEncoderStats()?.framesEncoded ?: 0
            assertTrue(
                "every encoded picture should be sent once, not twice (encoded=$encoded, sent=$sent)",
                sent <= encoded + 20
            )
        }
    }

    private fun retryFor(timeoutMs: Long, attempt: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (attempt()) return true
            Thread.sleep(250)
        }
        return false
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        return condition()
    }
}
