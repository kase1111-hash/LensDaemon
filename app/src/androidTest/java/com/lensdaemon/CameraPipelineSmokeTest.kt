package com.lensdaemon

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.lensdaemon.camera.CaptureConfig
import com.lensdaemon.camera.LensDaemonCameraManager
import com.lensdaemon.camera.LensType
import com.lensdaemon.camera.PreviewFrameGrabber
import android.graphics.BitmapFactory
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.encoder.VideoCodec
import com.lensdaemon.encoder.VideoEncoder
import com.lensdaemon.output.FileWriter
import com.lensdaemon.output.FileWriterConfig
import com.lensdaemon.output.SegmentDuration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * End-to-end smoke test for the capture pipeline: camera frames must reach the
 * hardware encoder through its input surface, and encoded frames must be
 * writable into a playable MP4.
 *
 * Skipped on devices without a back camera. Uses a small resolution so it
 * runs on the emulator's virtual camera.
 */
@RunWith(AndroidJUnit4::class)
class CameraPipelineSmokeTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    private lateinit var context: Context
    private lateinit var manager: LensDaemonCameraManager
    private lateinit var encoder: VideoEncoder
    private lateinit var previewReader: ImageReader
    private lateinit var consumerThread: HandlerThread

    private val size = Size(640, 480)
    private val encoderConfig = EncoderConfig(
        codec = VideoCodec.H264,
        resolution = size,
        bitrateBps = 1_000_000,
        frameRate = 30
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        manager = LensDaemonCameraManager(context)
        assumeTrue("No back camera on this device", manager.availableLenses.isNotEmpty())

        encoder = VideoEncoder(encoderConfig)
        consumerThread = HandlerThread("preview-consumer").apply { start() }
        // A PRIVATE ImageReader stands in for the on-screen preview and always
        // drains its buffers so the camera never stalls on the preview stream.
        previewReader = ImageReader.newInstance(size.width, size.height, ImageFormat.PRIVATE, 3)
        previewReader.setOnImageAvailableListener(
            { reader -> reader.acquireLatestImage()?.close() },
            Handler(consumerThread.looper)
        )
    }

    @After
    fun tearDown() {
        if (this::encoder.isInitialized) encoder.release()
        if (this::manager.isInitialized) manager.release()
        if (this::previewReader.isInitialized) previewReader.close()
        if (this::consumerThread.isInitialized) consumerThread.quitSafely()
    }

    @Test
    fun cameraFramesReachTheEncoderWhenAttachedBeforePreview() {
        val encoderSurface = encoder.initialize().getOrThrow()
        val frames = CountDownLatch(5)
        val sawKeyFrame = AtomicBoolean(false)
        encoder.setFrameCallback { frame ->
            if (frame.isKeyFrame) sawKeyFrame.set(true)
            if (!frame.isConfigFrame) frames.countDown()
        }

        runBlocking {
            assertTrue("camera should open", manager.openCamera(LensType.MAIN))
            assertTrue("encoder surface should attach", manager.addEncoderSurface(encoderSurface))
            assertTrue("preview should start", manager.startPreview(previewReader.surface, CaptureConfig(resolution = size)))
        }
        assertTrue("encoder should start", encoder.start())

        assertTrue("encoder should receive camera frames within 20 s", frames.await(20, TimeUnit.SECONDS))
        assertTrue("a keyframe should have been produced", sawKeyFrame.get())
        assertNotNull("SPS should be learned from the codec-config buffer", encoder.getSps())
        assertNotNull("PPS should be learned from the codec-config buffer", encoder.getPps())
    }

    @Test
    fun attachingTheEncoderToARunningPreviewRebuildsTheSession() {
        runBlocking {
            assertTrue("camera should open", manager.openCamera(LensType.MAIN))
            assertTrue("preview should start", manager.startPreview(previewReader.surface, CaptureConfig(resolution = size)))
        }

        // This is the app's real order: preview running first, streaming started later.
        val encoderSurface = encoder.initialize().getOrThrow()
        val frames = CountDownLatch(5)
        encoder.setFrameCallback { frame -> if (!frame.isConfigFrame) frames.countDown() }

        val attached = runBlocking { manager.addEncoderSurface(encoderSurface) }
        assertTrue("encoder surface should attach to the live session\n" + recentCameraLog(), attached)
        assertTrue("encoder should start", encoder.start())
        assertTrue("encoder should receive frames after the session was rebuilt", frames.await(20, TimeUnit.SECONDS))

        // Detaching must rebuild the session again without the encoder and leave the preview alive.
        runBlocking { assertTrue("encoder surface should detach", manager.removeEncoderSurface()) }
        val previewFrames = CountDownLatch(3)
        previewReader.setOnImageAvailableListener(
            { reader -> reader.acquireLatestImage()?.close(); previewFrames.countDown() },
            Handler(consumerThread.looper)
        )
        assertTrue("preview should keep running after the encoder was detached", previewFrames.await(10, TimeUnit.SECONDS))
    }

    /** Recent camera-related logcat lines, for failure diagnostics. */
    private fun recentCameraLog(): String {
        return try {
            val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
                .executeShellCommand("logcat -d -t 400")
            ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().readLines()
                .filter { line -> Regex("Camera|LensDaemon|Reconfig|Session|Encoder|configure").containsMatchIn(line) }
                .takeLast(60)
                .joinToString("\n")
        } catch (e: Exception) {
            "(logcat unavailable: ${e.message})"
        }
    }

    @Test
    fun recordingProducesAPlayableMp4() {
        val outputDir = File(context.cacheDir, "pipeline-test").apply { deleteRecursively(); mkdirs() }
        val writer = FileWriter(
            context,
            FileWriterConfig(outputDirectory = outputDir, encoderConfig = encoderConfig, segmentDuration = SegmentDuration.CONTINUOUS)
        )
        // The configured format carries no csd: the writer must learn SPS/PPS from
        // the codec-config buffer and open the file on the first keyframe.
        writer.setVideoFormat(encoderConfig.toMediaFormat())
        writer.onKeyFrameRequest = { encoder.requestKeyFrame() }

        val encoderSurface = encoder.initialize().getOrThrow()
        val written = AtomicInteger(0)
        val enough = CountDownLatch(30)
        encoder.setFrameCallback { frame ->
            if (writer.writeFrame(frame) && !frame.isConfigFrame) {
                written.incrementAndGet()
                enough.countDown()
            }
        }

        runBlocking {
            assertTrue("camera should open", manager.openCamera(LensType.MAIN))
            assertTrue("encoder surface should attach", manager.addEncoderSurface(encoderSurface))
            assertTrue("preview should start", manager.startPreview(previewReader.surface, CaptureConfig(resolution = size)))
        }
        assertTrue("recording should start", writer.startRecording())
        assertTrue("encoder should start", encoder.start())
        assertTrue("30 frames should be written within 30 s", enough.await(30, TimeUnit.SECONDS))

        val segments = writer.stopRecording()
        writer.release()
        assertEquals("one continuous segment expected", 1, segments.size)

        val file = File(segments[0])
        assertTrue("recording file should exist", file.exists())
        assertTrue("recording file should not be empty", file.length() > 0)

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val videoFormat = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            assertNotNull("MP4 should contain a video track", videoFormat)
            assertEquals("video/avc", videoFormat!!.getString(MediaFormat.KEY_MIME))
            assertTrue("video track should carry csd-0 (SPS)", videoFormat.containsKey("csd-0"))
            assertTrue("video track should carry csd-1 (PPS)", videoFormat.containsKey("csd-1"))
            assertEquals(size.width, videoFormat.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(size.height, videoFormat.getInteger(MediaFormat.KEY_HEIGHT))
        } finally {
            extractor.release()
        }
    }

    @Test
    fun previewFramesCanBeGrabbedAsJpegSnapshots() {
        val grabber = PreviewFrameGrabber()
        manager.onImageAvailable = grabber::onImage

        runBlocking {
            assertTrue("camera should open", manager.openCamera(LensType.MAIN))
            assertTrue("preview should start", manager.startPreview(previewReader.surface, CaptureConfig(resolution = size)))
        }

        val jpeg = grabber.captureSnapshot(10_000)
        assertNotNull("a snapshot should be captured from the live preview", jpeg)
        assertTrue("snapshot should be a JPEG", jpeg!!.size > 2 && jpeg[0] == 0xFF.toByte() && jpeg[1] == 0xD8.toByte())

        val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        assertNotNull("snapshot should decode", bitmap)
        assertEquals(size.width, bitmap!!.width)
        assertEquals(size.height, bitmap.height)

        // Stream frames flow only while there is demand, and are rate limited
        val streamed = CountDownLatch(3)
        grabber.streamSink = { streamed.countDown() }
        grabber.streamDemand = { true }
        assertTrue("stream frames should arrive while demanded", streamed.await(10, TimeUnit.SECONDS))
    }
}
