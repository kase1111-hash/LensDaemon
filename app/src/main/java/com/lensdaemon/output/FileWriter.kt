package com.lensdaemon.output

import android.content.Context
import android.media.MediaFormat
import android.os.Build
import com.lensdaemon.encoder.EncodedFrame
import com.lensdaemon.encoder.EncoderConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Recording state
 */
enum class RecordingState {
    IDLE,
    STARTING,
    RECORDING,
    PAUSED,
    STOPPING,
    ERROR
}

/**
 * Segment duration options
 */
enum class SegmentDuration(val minutes: Int, val displayName: String) {
    ONE_MINUTE(1, "1 minute"),
    FIVE_MINUTES(5, "5 minutes"),
    FIFTEEN_MINUTES(15, "15 minutes"),
    THIRTY_MINUTES(30, "30 minutes"),
    SIXTY_MINUTES(60, "60 minutes"),
    CONTINUOUS(0, "Continuous");

    val durationMs: Long get() = if (minutes > 0) minutes * 60 * 1000L else Long.MAX_VALUE
}

/**
 * File writer configuration
 */
data class FileWriterConfig(
    /** Base directory for recordings */
    val outputDirectory: File,

    /** Device name for filename */
    val deviceName: String = Build.MODEL.replace(" ", "_"),

    /** Segment duration */
    val segmentDuration: SegmentDuration = SegmentDuration.CONTINUOUS,

    /** Encoder configuration for format info */
    val encoderConfig: EncoderConfig = EncoderConfig.PRESET_1080P,

    /** Video rotation in degrees */
    val rotationDegrees: Int = 0,

    /** File extension */
    val fileExtension: String = "mp4"
) {
    init {
        require(outputDirectory.isDirectory || !outputDirectory.exists()) {
            "Output path must be a directory"
        }
    }
}

/**
 * Recording statistics
 */
data class RecordingStats(
    /** Current recording state */
    val state: RecordingState = RecordingState.IDLE,

    /** Current segment file path */
    val currentFilePath: String? = null,

    /** Total frames recorded in current segment */
    val framesInSegment: Long = 0,

    /** Total frames recorded across all segments */
    val totalFrames: Long = 0,

    /** Total bytes recorded in current segment */
    val bytesInSegment: Long = 0,

    /** Total bytes recorded across all segments */
    val totalBytes: Long = 0,

    /** Current segment index (0-based) */
    val segmentIndex: Int = 0,

    /** Recording start time */
    val startTimeMs: Long = 0,

    /** Current segment start time */
    val segmentStartTimeMs: Long = 0,

    /** Recording duration in ms */
    val durationMs: Long = 0,

    /** List of completed segment files */
    val completedSegments: List<String> = emptyList()
) {
    /** Duration in seconds */
    val durationSec: Float get() = durationMs / 1000f

    /** Average bitrate in kbps */
    val avgBitrateKbps: Float get() = if (durationMs > 0) {
        (totalBytes * 8f) / durationMs
    } else 0f
}

/**
 * Recording event for callbacks
 */
sealed class RecordingEvent {
    data class Started(val filePath: String) : RecordingEvent()
    data class SegmentCompleted(val filePath: String, val index: Int) : RecordingEvent()
    data class NewSegmentStarted(val filePath: String, val index: Int) : RecordingEvent()
    data class Stopped(val filePaths: List<String>) : RecordingEvent()
    data class Error(val error: String) : RecordingEvent()
    data class Paused(val filePath: String) : RecordingEvent()
    data class Resumed(val filePath: String) : RecordingEvent()
}

/**
 * Listener interface for recording events
 */
interface RecordingListener {
    fun onRecordingEvent(event: RecordingEvent)
}

/**
 * Local file writer for MP4 recording with segmentation support
 *
 * Features:
 * - Automatic file segmentation at configurable intervals
 * - Filename pattern: LensDaemon_{device}_{timestamp}.mp4
 * - Recording state management (start/stop/pause/resume)
 * - Statistics tracking
 * - Event callbacks for segment completion
 */
class FileWriter(
    private val context: Context,
    private val config: FileWriterConfig
) {
    companion object {
        private const val TAG = "FileWriter"
        private const val FILENAME_PREFIX = "LensDaemon"
        private val DATE_FORMAT = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var currentMuxer: Mp4Muxer? = null
    private var videoFormat: MediaFormat? = null
    private var segmentJob: Job? = null

    private val isRecording = AtomicBoolean(false)
    private val isPaused = AtomicBoolean(false)

    private val totalFrames = AtomicLong(0)
    private val totalBytes = AtomicLong(0)
    private val segmentFrames = AtomicLong(0)
    private val segmentBytes = AtomicLong(0)

    private var startTimeMs: Long = 0
    private var segmentStartTimeMs: Long = 0
    private var segmentIndex: Int = 0
    private val completedSegments = mutableListOf<String>()

    private val listeners = mutableListOf<RecordingListener>()

    private val _state = MutableStateFlow(RecordingState.IDLE)
    val state: StateFlow<RecordingState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(RecordingStats())
    val stats: StateFlow<RecordingStats> = _stats.asStateFlow()

    private val lock = Object()

    /**
     * Set the video format from encoder output
     * Must be called with the format containing SPS/PPS before recording
     */
    fun setVideoFormat(format: MediaFormat) {
        synchronized(lock) {
            this.videoFormat = format
            Timber.tag(TAG).d("Video format set: $format")
        }
    }

    /**
     * Start recording
     */
    fun startRecording(): Boolean {
        synchronized(lock) {
            if (isRecording.get()) {
                Timber.tag(TAG).w("Already recording")
                return false
            }

            if (videoFormat == null) {
                Timber.tag(TAG).e("Cannot start: video format not set")
                _state.value = RecordingState.ERROR
                notifyListeners(RecordingEvent.Error("Video format not set"))
                return false
            }

            _state.value = RecordingState.STARTING

            // Ensure output directory exists
            if (!config.outputDirectory.exists()) {
                config.outputDirectory.mkdirs()
            }

            // Reset counters
            totalFrames.set(0)
            totalBytes.set(0)
            segmentIndex = 0
            completedSegments.clear()
            startTimeMs = System.currentTimeMillis()

            // Create first segment
            if (!createNewSegment()) {
                _state.value = RecordingState.ERROR
                return false
            }

            isRecording.set(true)
            isPaused.set(false)
            _state.value = RecordingState.RECORDING

            // Start segment timer if needed
            startSegmentTimer()

            updateStats()
            notifyListeners(RecordingEvent.Started(currentMuxer?.getOutputPath() ?: ""))

            Timber.tag(TAG).i("Recording started: ${currentMuxer?.getOutputPath()}")
            return true
        }
    }

    /**
     * Stop recording and finalize all files
     */
    fun stopRecording(): List<String> {
        synchronized(lock) {
            if (!isRecording.get()) {
                Timber.tag(TAG).w("Not recording")
                return completedSegments.toList()
            }

            _state.value = RecordingState.STOPPING

            // Cancel segment timer
            segmentJob?.cancel()
            segmentJob = null

            // Finalize current segment
            finalizeCurrentSegment()

            isRecording.set(false)
            isPaused.set(false)
            _state.value = RecordingState.IDLE

            updateStats()

            val allSegments = completedSegments.toList()
            notifyListeners(RecordingEvent.Stopped(allSegments))

            Timber.tag(TAG).i("Recording stopped: ${allSegments.size} segments")
            return allSegments
        }
    }

    /**
     * Pause recording (keeps file open)
     */
    fun pauseRecording(): Boolean {
        synchronized(lock) {
            if (!isRecording.get() || isPaused.get()) {
                return false
            }

            isPaused.set(true)
            _state.value = RecordingState.PAUSED
            updateStats()
            notifyListeners(RecordingEvent.Paused(currentMuxer?.getOutputPath() ?: ""))

            Timber.tag(TAG).d("Recording paused")
            return true
        }
    }

    /**
     * Resume recording
     */
    fun resumeRecording(): Boolean {
        synchronized(lock) {
            if (!isRecording.get() || !isPaused.get()) {
                return false
            }

            isPaused.set(false)
            _state.value = RecordingState.RECORDING
            updateStats()
            notifyListeners(RecordingEvent.Resumed(currentMuxer?.getOutputPath() ?: ""))

            Timber.tag(TAG).d("Recording resumed")
            return true
        }
    }

    /**
     * Write an encoded frame to the recording
     */
    fun writeFrame(frame: EncodedFrame): Boolean {
        if (!isRecording.get() || isPaused.get()) {
            return false
        }

        val muxer = currentMuxer ?: return false

        // Handle codec config data - update format for new segments
        if (frame.isConfigFrame) {
            return true // Skip, format is already set
        }

        val success = muxer.writeVideoFrame(frame)
        if (success) {
            totalFrames.incrementAndGet()
            totalBytes.addAndGet(frame.size.toLong())
            segmentFrames.incrementAndGet()
            segmentBytes.addAndGet(frame.size.toLong())
        }

        return success
    }

    /**
     * Add a recording event listener
     */
    fun addListener(listener: RecordingListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Remove a recording event listener
     */
    fun removeListener(listener: RecordingListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * Get current recording statistics
     */
    fun getStats(): RecordingStats {
        val durationMs = if (startTimeMs > 0) {
            System.currentTimeMillis() - startTimeMs
        } else 0L

        return RecordingStats(
            state = _state.value,
            currentFilePath = currentMuxer?.getOutputPath(),
            framesInSegment = segmentFrames.get(),
            totalFrames = totalFrames.get(),
            bytesInSegment = segmentBytes.get(),
            totalBytes = totalBytes.get(),
            segmentIndex = segmentIndex,
            startTimeMs = startTimeMs,
            segmentStartTimeMs = segmentStartTimeMs,
            durationMs = durationMs,
            completedSegments = completedSegments.toList()
        )
    }

    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean = isRecording.get()

    /**
     * Check if recording is paused
     */
    fun isPaused(): Boolean = isPaused.get()

    /**
     * Release resources
     */
    fun release() {
        synchronized(lock) {
            if (isRecording.get()) {
                stopRecording()
            }
            scope.cancel()
            listeners.clear()
        }
    }

    /**
     * Generate filename for a segment
     */
    private fun generateFilename(): String {
        val timestamp = DATE_FORMAT.format(Date())
        val segmentSuffix = if (config.segmentDuration != SegmentDuration.CONTINUOUS) {
            "_seg${String.format("%03d", segmentIndex)}"
        } else ""

        return "${FILENAME_PREFIX}_${config.deviceName}_${timestamp}${segmentSuffix}.${config.fileExtension}"
    }

    /**
     * Create a new segment file
     */
    private fun createNewSegment(): Boolean {
        val format = videoFormat ?: return false

        val filename = generateFilename()
        val outputFile = File(config.outputDirectory, filename)

        Timber.tag(TAG).d("Creating new segment: $filename")

        val muxer = Mp4MuxerBuilder()
            .outputPath(outputFile.absolutePath)
            .videoCodec(config.encoderConfig.codec)
            .resolution(config.encoderConfig.width, config.encoderConfig.height)
            .frameRate(config.encoderConfig.frameRate)
            .bitrate(config.encoderConfig.bitrateBps)
            .rotation(config.rotationDegrees)
            .build()

        if (!muxer.initialize()) {
            Timber.tag(TAG).e("Failed to initialize muxer")
            notifyListeners(RecordingEvent.Error("Failed to initialize muxer"))
            return false
        }

        if (muxer.addVideoTrack(format) < 0) {
            Timber.tag(TAG).e("Failed to add video track")
            muxer.release()
            notifyListeners(RecordingEvent.Error("Failed to add video track"))
            return false
        }

        if (!muxer.start()) {
            Timber.tag(TAG).e("Failed to start muxer")
            muxer.release()
            notifyListeners(RecordingEvent.Error("Failed to start muxer"))
            return false
        }

        currentMuxer = muxer
        segmentStartTimeMs = System.currentTimeMillis()
        segmentFrames.set(0)
        segmentBytes.set(0)

        if (segmentIndex > 0) {
            notifyListeners(RecordingEvent.NewSegmentStarted(outputFile.absolutePath, segmentIndex))
        }

        return true
    }

    /**
     * Finalize the current segment
     */
    private fun finalizeCurrentSegment() {
        val muxer = currentMuxer ?: return

        val filePath = muxer.getOutputPath()
        val stats = muxer.getStats()

        if (muxer.stop()) {
            completedSegments.add(filePath)
            notifyListeners(RecordingEvent.SegmentCompleted(filePath, segmentIndex))
            Timber.tag(TAG).d(
                "Segment completed: $filePath, " +
                "${stats.framesWritten} frames, " +
                "${stats.bytesWritten} bytes, " +
                "${stats.durationSec}s"
            )
        } else {
            Timber.tag(TAG).e("Failed to finalize segment: $filePath")
            // Try to delete incomplete file
            try {
                File(filePath).delete()
            } catch (_: Exception) {}
        }

        currentMuxer = null
    }

    /**
     * Start segment rotation timer
     */
    private fun startSegmentTimer() {
        if (config.segmentDuration == SegmentDuration.CONTINUOUS) {
            return
        }

        segmentJob = scope.launch {
            while (isActive && isRecording.get()) {
                delay(config.segmentDuration.durationMs)

                if (isRecording.get() && !isPaused.get()) {
                    rotateSegment()
                }
            }
        }
    }

    /**
     * Rotate to a new segment
     */
    private fun rotateSegment() {
        synchronized(lock) {
            if (!isRecording.get()) return

            Timber.tag(TAG).d("Rotating segment")

            // Finalize current segment
            finalizeCurrentSegment()

            // Increment segment index
            segmentIndex++

            // Create new segment
            if (!createNewSegment()) {
                Timber.tag(TAG).e("Failed to create new segment, stopping recording")
                _state.value = RecordingState.ERROR
                isRecording.set(false)
                notifyListeners(RecordingEvent.Error("Failed to create new segment"))
            }

            updateStats()
        }
    }

    /**
     * Update stats flow
     */
    private fun updateStats() {
        _stats.value = getStats()
    }

    /**
     * Notify all listeners of an event
     */
    private fun notifyListeners(event: RecordingEvent) {
        synchronized(listeners) {
            listeners.forEach { listener ->
                try {
                    listener.onRecordingEvent(event)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error notifying listener")
                }
            }
        }
    }
}

/**
 * Factory for creating FileWriter instances
 */
object FileWriterFactory {
    /**
     * Create a FileWriter with default configuration
     */
    fun create(
        context: Context,
        outputDirectory: File,
        encoderConfig: EncoderConfig = EncoderConfig.PRESET_1080P,
        segmentDuration: SegmentDuration = SegmentDuration.CONTINUOUS
    ): FileWriter {
        val config = FileWriterConfig(
            outputDirectory = outputDirectory,
            encoderConfig = encoderConfig,
            segmentDuration = segmentDuration
        )
        return FileWriter(context, config)
    }

    /**
     * Create a FileWriter for continuous recording
     */
    fun createContinuous(
        context: Context,
        outputDirectory: File,
        encoderConfig: EncoderConfig = EncoderConfig.PRESET_1080P
    ): FileWriter {
        return create(context, outputDirectory, encoderConfig, SegmentDuration.CONTINUOUS)
    }

    /**
     * Create a FileWriter with 5-minute segments
     */
    fun createSegmented(
        context: Context,
        outputDirectory: File,
        encoderConfig: EncoderConfig = EncoderConfig.PRESET_1080P,
        segmentMinutes: Int = 5
    ): FileWriter {
        val duration = when (segmentMinutes) {
            1 -> SegmentDuration.ONE_MINUTE
            5 -> SegmentDuration.FIVE_MINUTES
            15 -> SegmentDuration.FIFTEEN_MINUTES
            30 -> SegmentDuration.THIRTY_MINUTES
            60 -> SegmentDuration.SIXTY_MINUTES
            else -> SegmentDuration.FIVE_MINUTES
        }
        return create(context, outputDirectory, encoderConfig, duration)
    }
}
