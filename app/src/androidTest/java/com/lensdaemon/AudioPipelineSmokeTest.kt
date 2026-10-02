package com.lensdaemon

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.lensdaemon.camera.CaptureConfig
import com.lensdaemon.camera.LensDaemonCameraManager
import com.lensdaemon.camera.LensType
import com.lensdaemon.encoder.Aac
import com.lensdaemon.encoder.AudioConfig
import com.lensdaemon.encoder.AudioEncoder
import com.lensdaemon.encoder.EncodedAudioFrame
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.encoder.PcmSource
import com.lensdaemon.encoder.VideoCodec
import com.lensdaemon.encoder.VideoEncoder
import com.lensdaemon.encoder.toMuxerFormat
import com.lensdaemon.output.FileWriter
import com.lensdaemon.output.FileWriterConfig
import com.lensdaemon.output.SegmentDuration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.sin

/**
 * The audio path on a real AAC encoder: PCM in, AAC frames out with the
 * source's timestamps, and an MP4 recording that carries them as a second
 * track. PCM comes from a synthetic tone, since the CI emulator runs
 * without an audio device.
 */
@RunWith(AndroidJUnit4::class)
class AudioPipelineSmokeTest {

    @get:Rule
    val permissionRule: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    /** A 440 Hz tone, as fast as the encoder takes it, timed from [baseUs]. */
    private class TonePcmSource(
        override val sampleRate: Int = 48_000,
        override val channelCount: Int = 2,
        private val baseUs: Long = 5_000_000L
    ) : PcmSource {
        @Volatile private var open = false
        private var phase = 0.0

        override fun start(): Boolean {
            open = true
            return true
        }

        override fun read(buffer: ByteArray, size: Int): Int {
            if (!open) return -1
            val frames = size / (2 * channelCount)
            var offset = 0
            repeat(frames) {
                val sample = (sin(phase) * 8000).toInt()
                phase += 2 * PI * 440 / sampleRate
                repeat(channelCount) {
                    buffer[offset++] = sample.toByte()
                    buffer[offset++] = (sample shr 8).toByte()
                }
            }
            return offset
        }

        override fun presentationTimeUs(framePosition: Long, framesJustRead: Int): Long =
            baseUs + framePosition * 1_000_000L / sampleRate

        override fun close() {
            open = false
        }
    }

    /** Encode [count] AAC frames of a tone; returns them in order, the config frame first. */
    private fun encodeTone(count: Int): List<EncodedAudioFrame> {
        val frames = Collections.synchronizedList(mutableListOf<EncodedAudioFrame>())
        val enough = CountDownLatch(count)
        val encoder = AudioEncoder(TonePcmSource()) { frame ->
            frames.add(frame)
            enough.countDown()
        }
        assertTrue("AAC encoder should start", encoder.start())
        try {
            assertTrue("$count AAC frames should be encoded within 20 s", enough.await(20, TimeUnit.SECONDS))
        } finally {
            encoder.stop()
        }
        assertFalse("encoder should stop", encoder.isRunning)
        return synchronized(frames) { frames.toList() }
    }

    @Test
    fun aacEncoderEmitsItsConfigThenFramesOnTheSourceClock() {
        val frames = encodeTone(50)

        assertTrue("the first output should be the AudioSpecificConfig", frames.first().isConfig)
        assertArrayEquals("AAC-LC 48 kHz stereo", Aac.audioSpecificConfig(48_000, 2), frames.first().data)

        val audio = frames.drop(1)
        assertTrue("then only audio frames", audio.none { it.isConfig })
        assertTrue("frames should not be empty", audio.all { it.size > 0 })
        audio.zipWithNext().forEach { (a, b) ->
            assertTrue(
                "timestamps should increase (${a.presentationTimeUs} -> ${b.presentationTimeUs})",
                b.presentationTimeUs > a.presentationTimeUs
            )
        }
        val firstPts = audio.first().presentationTimeUs
        assertTrue("timestamps should follow the source's clock ($firstPts)", firstPts in 4_900_000L..6_000_000L)
    }

    @Test
    fun recordingCarriesAnAudioTrack() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = LensDaemonCameraManager(context)
        assumeTrue("No back camera on this device", manager.availableLenses.isNotEmpty())

        val aac = ConcurrentLinkedQueue(encodeTone(400).filter { !it.isConfig })
        val size = Size(640, 480)
        val encoderConfig = EncoderConfig(codec = VideoCodec.H264, resolution = size, bitrateBps = 1_000_000, frameRate = 30)
        val encoder = VideoEncoder(encoderConfig)
        val consumerThread = HandlerThread("preview-consumer").apply { start() }
        // GPU sampling like a real preview; see CameraPipelineSmokeTest.setUp
        val previewReader = ImageReader.newInstance(
            size.width, size.height, ImageFormat.PRIVATE, 3, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE
        )
        previewReader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(consumerThread.looper))

        val outputDir = File(context.cacheDir, "audio-recording-test").apply { deleteRecursively(); mkdirs() }
        val writer = FileWriter(
            context,
            FileWriterConfig(outputDirectory = outputDir, encoderConfig = encoderConfig, segmentDuration = SegmentDuration.CONTINUOUS)
        )
        writer.setVideoFormat(encoderConfig.toMediaFormat())
        writer.setAudioFormat(AudioConfig(48_000, 2).toMuxerFormat())
        writer.onKeyFrameRequest = { encoder.requestKeyFrame() }

        try {
            val audioWritten = AtomicInteger(0)
            val enough = CountDownLatch(60)
            val encoderSurface = encoder.initialize().getOrThrow()
            encoder.setFrameCallback { frame ->
                writer.writeFrame(frame)
                if (frame.isConfigFrame) return@setFrameCallback
                // One AAC frame per picture, stamped with the picture's time so
                // the test does not depend on how the emulator's camera clock
                // relates to the audio clock
                val next = aac.poll() ?: return@setFrameCallback
                if (writer.writeAudioFrame(EncodedAudioFrame(next.data, frame.presentationTimeUs))) {
                    audioWritten.incrementAndGet()
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
            assertTrue("60 audio frames should be recorded within 30 s", enough.await(30, TimeUnit.SECONDS))

            val segments = writer.stopRecording()
            assertEquals("one continuous segment expected", 1, segments.size)

            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(segments[0])
                val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                val mimes = formats.map { it.getString(MediaFormat.KEY_MIME) }
                assertTrue("MP4 should contain a video track: $mimes", "video/avc" in mimes)
                val audio = formats.firstOrNull { it.getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
                assertNotNull("MP4 should contain an AAC track: $mimes", audio)
                assertEquals(48_000, audio!!.getInteger(MediaFormat.KEY_SAMPLE_RATE))
                assertEquals(2, audio.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            } finally {
                extractor.release()
            }
        } finally {
            // Camera first, so it never draws into a released encoder surface,
            // then the encoder, so no frame reaches a released writer
            manager.release()
            encoder.release()
            writer.release()
            previewReader.close()
            consumerThread.quitSafely()
        }
    }
}
