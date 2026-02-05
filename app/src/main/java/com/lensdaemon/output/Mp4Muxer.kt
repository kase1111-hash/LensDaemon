package com.lensdaemon.output

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import com.lensdaemon.encoder.EncodedFrame
import com.lensdaemon.encoder.VideoCodec
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * MP4 muxer state
 */
enum class MuxerState {
    IDLE,
    INITIALIZED,
    STARTED,
    STOPPED,
    ERROR
}

/**
 * MP4 muxer configuration
 */
data class MuxerConfig(
    /** Output file path */
    val outputPath: String,

    /** Video codec being used */
    val videoCodec: VideoCodec = VideoCodec.H264,

    /** Video width */
    val width: Int = 1920,

    /** Video height */
    val height: Int = 1080,

    /** Frame rate */
    val frameRate: Int = 30,

    /** Video bitrate */
    val bitrateBps: Int = 4_000_000,

    /** Rotation degrees (0, 90, 180, 270) */
    val rotationDegrees: Int = 0
) {
    val outputFile: File get() = File(outputPath)
}

/**
 * MP4 muxer statistics
 */
data class MuxerStats(
    /** Total frames written */
    val framesWritten: Long = 0,

    /** Total bytes written */
    val bytesWritten: Long = 0,

    /** Recording start time in ms */
    val startTimeMs: Long = 0,

    /** Recording duration in ms */
    val durationMs: Long = 0,

    /** Output file size in bytes */
    val fileSizeBytes: Long = 0
) {
    /** Duration in seconds */
    val durationSec: Float get() = durationMs / 1000f

    /** Average bitrate in bps */
    val avgBitrateBps: Long get() = if (durationMs > 0) (bytesWritten * 8 * 1000) / durationMs else 0
}

/**
 * MP4 muxer error types
 */
sealed class MuxerError(val message: String) {
    data class FileError(val details: String) : MuxerError("File error: $details")
    data class FormatError(val details: String) : MuxerError("Format error: $details")
    data class WriteError(val details: String) : MuxerError("Write error: $details")
    data class StateError(val details: String) : MuxerError("State error: $details")
}

/**
 * MediaMuxer wrapper for MP4 file recording
 *
 * Handles:
 * - MP4 container creation and management
 * - Track configuration from codec output format
 * - Frame writing with proper timestamps
 * - File finalization and cleanup
 */
class Mp4Muxer(
    private val config: MuxerConfig
) {
    companion object {
        private const val TAG = "Mp4Muxer"
    }

    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex: Int = -1
    private var audioTrackIndex: Int = -1

    private val isStarted = AtomicBoolean(false)
    private val framesWritten = AtomicLong(0)
    private val bytesWritten = AtomicLong(0)

    private var startTimeMs: Long = 0
    private var firstFrameTimeUs: Long = -1
    private var lastFrameTimeUs: Long = 0

    @Volatile
    var state: MuxerState = MuxerState.IDLE
        private set

    @Volatile
    var lastError: MuxerError? = null
        private set

    private val lock = Object()

    /**
     * Initialize the muxer and create the output file
     */
    fun initialize(): Boolean {
        synchronized(lock) {
            if (state != MuxerState.IDLE) {
                Timber.tag(TAG).w("Cannot initialize: already in state $state")
                return false
            }

            return try {
                // Ensure parent directory exists
                config.outputFile.parentFile?.mkdirs()

                // Create MediaMuxer
                mediaMuxer = MediaMuxer(
                    config.outputPath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                ).apply {
                    // Set rotation if specified
                    if (config.rotationDegrees != 0) {
                        setOrientationHint(config.rotationDegrees)
                    }
                }

                state = MuxerState.INITIALIZED
                Timber.tag(TAG).d("Muxer initialized: ${config.outputPath}")
                true
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to initialize muxer")
                lastError = MuxerError.FileError(e.message ?: "Unknown error")
                state = MuxerState.ERROR
                false
            }
        }
    }

    /**
     * Add video track from encoder output format
     * Must be called after receiving codec config data (SPS/PPS)
     */
    fun addVideoTrack(format: MediaFormat): Int {
        synchronized(lock) {
            if (state != MuxerState.INITIALIZED) {
                Timber.tag(TAG).w("Cannot add track: state is $state")
                return -1
            }

            val muxer = mediaMuxer ?: return -1

            return try {
                videoTrackIndex = muxer.addTrack(format)
                Timber.tag(TAG).d("Video track added: index=$videoTrackIndex, format=$format")
                videoTrackIndex
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to add video track")
                lastError = MuxerError.FormatError(e.message ?: "Unknown error")
                -1
            }
        }
    }

    /**
     * Add audio track (for future audio support)
     */
    fun addAudioTrack(format: MediaFormat): Int {
        synchronized(lock) {
            if (state != MuxerState.INITIALIZED) {
                Timber.tag(TAG).w("Cannot add track: state is $state")
                return -1
            }

            val muxer = mediaMuxer ?: return -1

            return try {
                audioTrackIndex = muxer.addTrack(format)
                Timber.tag(TAG).d("Audio track added: index=$audioTrackIndex")
                audioTrackIndex
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to add audio track")
                lastError = MuxerError.FormatError(e.message ?: "Unknown error")
                -1
            }
        }
    }

    /**
     * Start muxing - must be called after all tracks are added
     */
    fun start(): Boolean {
        synchronized(lock) {
            if (state != MuxerState.INITIALIZED) {
                Timber.tag(TAG).w("Cannot start: state is $state")
                return false
            }

            if (videoTrackIndex < 0) {
                Timber.tag(TAG).e("Cannot start: no video track added")
                lastError = MuxerError.StateError("No video track")
                return false
            }

            val muxer = mediaMuxer ?: return false

            return try {
                muxer.start()
                isStarted.set(true)
                startTimeMs = System.currentTimeMillis()
                firstFrameTimeUs = -1
                state = MuxerState.STARTED
                Timber.tag(TAG).d("Muxer started")
                true
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to start muxer")
                lastError = MuxerError.StateError(e.message ?: "Unknown error")
                state = MuxerState.ERROR
                false
            }
        }
    }

    /**
     * Write an encoded video frame
     */
    fun writeVideoFrame(frame: EncodedFrame): Boolean {
        if (!isStarted.get() || videoTrackIndex < 0) {
            return false
        }

        // Skip codec config frames - they're already in the track format
        if (frame.isConfigFrame) {
            return true
        }

        return writeFrame(videoTrackIndex, frame)
    }

    /**
     * Write a frame to the specified track
     */
    private fun writeFrame(trackIndex: Int, frame: EncodedFrame): Boolean {
        val muxer = mediaMuxer ?: return false

        return try {
            val buffer = ByteBuffer.wrap(frame.data)
            val bufferInfo = MediaCodec.BufferInfo().apply {
                offset = 0
                size = frame.size
                presentationTimeUs = frame.presentationTimeUs
                flags = frame.flags
            }

            // Track first frame time for duration calculation
            if (firstFrameTimeUs < 0) {
                firstFrameTimeUs = frame.presentationTimeUs
            }
            lastFrameTimeUs = frame.presentationTimeUs

            synchronized(lock) {
                if (state == MuxerState.STARTED) {
                    muxer.writeSampleData(trackIndex, buffer, bufferInfo)
                    framesWritten.incrementAndGet()
                    bytesWritten.addAndGet(frame.size.toLong())
                }
            }
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to write frame")
            lastError = MuxerError.WriteError(e.message ?: "Unknown error")
            false
        }
    }

    /**
     * Stop muxing and finalize the file
     */
    fun stop(): Boolean {
        synchronized(lock) {
            if (state != MuxerState.STARTED) {
                Timber.tag(TAG).w("Cannot stop: state is $state")
                return state == MuxerState.STOPPED
            }

            isStarted.set(false)

            return try {
                mediaMuxer?.stop()
                mediaMuxer?.release()
                mediaMuxer = null

                state = MuxerState.STOPPED
                Timber.tag(TAG).d("Muxer stopped: ${framesWritten.get()} frames, ${bytesWritten.get()} bytes")
                true
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error stopping muxer")
                // Even on error, try to release
                try {
                    mediaMuxer?.release()
                } catch (_: Exception) {}
                mediaMuxer = null
                lastError = MuxerError.WriteError(e.message ?: "Unknown error")
                state = MuxerState.ERROR
                false
            }
        }
    }

    /**
     * Release resources without finalizing the file (for error cases)
     */
    fun release() {
        synchronized(lock) {
            isStarted.set(false)
            try {
                if (state == MuxerState.STARTED) {
                    mediaMuxer?.stop()
                }
                mediaMuxer?.release()
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error releasing muxer")
            }
            mediaMuxer = null
            state = MuxerState.IDLE
        }
    }

    /**
     * Get current muxer statistics
     */
    fun getStats(): MuxerStats {
        val durationMs = if (firstFrameTimeUs >= 0 && lastFrameTimeUs > firstFrameTimeUs) {
            (lastFrameTimeUs - firstFrameTimeUs) / 1000
        } else if (startTimeMs > 0) {
            System.currentTimeMillis() - startTimeMs
        } else {
            0L
        }

        val fileSize = try {
            if (config.outputFile.exists()) config.outputFile.length() else bytesWritten.get()
        } catch (_: Exception) {
            bytesWritten.get()
        }

        return MuxerStats(
            framesWritten = framesWritten.get(),
            bytesWritten = bytesWritten.get(),
            startTimeMs = startTimeMs,
            durationMs = durationMs,
            fileSizeBytes = fileSize
        )
    }

    /**
     * Check if muxer is currently recording
     */
    fun isRecording(): Boolean = isStarted.get() && state == MuxerState.STARTED

    /**
     * Get the output file path
     */
    fun getOutputPath(): String = config.outputPath
}

/**
 * Builder for Mp4Muxer configuration
 */
class Mp4MuxerBuilder {
    private var outputPath: String = ""
    private var videoCodec: VideoCodec = VideoCodec.H264
    private var width: Int = 1920
    private var height: Int = 1080
    private var frameRate: Int = 30
    private var bitrateBps: Int = 4_000_000
    private var rotationDegrees: Int = 0

    fun outputPath(path: String) = apply { this.outputPath = path }
    fun videoCodec(codec: VideoCodec) = apply { this.videoCodec = codec }
    fun resolution(width: Int, height: Int) = apply { this.width = width; this.height = height }
    fun frameRate(fps: Int) = apply { this.frameRate = fps }
    fun bitrate(bps: Int) = apply { this.bitrateBps = bps }
    fun rotation(degrees: Int) = apply { this.rotationDegrees = degrees }

    fun build(): Mp4Muxer {
        require(outputPath.isNotEmpty()) { "Output path must be specified" }

        val config = MuxerConfig(
            outputPath = outputPath,
            videoCodec = videoCodec,
            width = width,
            height = height,
            frameRate = frameRate,
            bitrateBps = bitrateBps,
            rotationDegrees = rotationDegrees
        )

        return Mp4Muxer(config)
    }
}
