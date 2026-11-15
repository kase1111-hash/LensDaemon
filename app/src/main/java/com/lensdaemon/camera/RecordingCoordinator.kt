package com.lensdaemon.camera

import android.content.Context
import android.media.MediaFormat
import com.lensdaemon.encoder.EncodedAudioFrame
import com.lensdaemon.encoder.EncodedFrame
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.output.RecordingEvent
import com.lensdaemon.output.RecordingListener
import com.lensdaemon.output.RecordingState
import com.lensdaemon.output.RecordingStats
import com.lensdaemon.output.SegmentDuration
import com.lensdaemon.storage.LocalStorage
import com.lensdaemon.storage.RecordingFile
import com.lensdaemon.storage.RetentionConfig
import com.lensdaemon.storage.RetentionPolicy
import com.lensdaemon.storage.RetentionResult
import com.lensdaemon.storage.StorageManager
import com.lensdaemon.storage.StorageManagerBuilder
import com.lensdaemon.storage.StorageManagerState
import com.lensdaemon.storage.StorageStatus
import com.lensdaemon.storage.StorageWarningLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * Coordinates local recording, storage management, and retention.
 *
 * Extracted from CameraService to isolate recording concerns. The recordings
 * folder (listing, deleting, space, clean-up) is available from creation; the
 * recorder itself is set up by [initialize] for the running encoder.
 */
class RecordingCoordinator(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var storageManager: StorageManager? = null

    /**
     * The recordings folder, usable with no recorder: the same folder
     * (EXTERNAL_APP) the recorder writes to. Its space monitor is never
     * started; the recorder's own storage watches the space while recording.
     */
    private val localStorage = LocalStorage(context)

    /** The result of the last clean-up run through [enforceRetention]. */
    @Volatile
    private var lastRetentionResult: RetentionResult? = null

    /** The encoder settings the file writer was built for (codec, size). */
    private var encoderConfig: EncoderConfig? = null

    /** The segment length in use, kept across a re-initialization. */
    var segmentDuration: SegmentDuration = SegmentDuration.FIVE_MINUTES
        private set

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    /**
     * The last error the recorder reported since the last recording start (a
     * file that would not open, "Storage full - recording stopped"), or null.
     * Kept after the stop that follows it and across [release], so a recording
     * that ended on an error still says why; cleared when a recording starts.
     */
    @Volatile
    var lastError: String? = null
        private set

    /** Frame listener that forwards encoded frames to the file writer */
    val frameListener: (EncodedFrame) -> Unit = { frame ->
        storageManager?.writeFrame(frame)
    }

    /** Forwards encoded AAC frames to the file writer */
    val audioListener: (EncodedAudioFrame) -> Unit = { frame ->
        storageManager?.audioFrameWriter?.invoke(frame)
    }

    /** The audio track format for new segments, kept across re-initialization. */
    private var audioFormat: MediaFormat? = null

    /** Callback for notification updates when recording state changes */
    var onStateChanged: (() -> Unit)? = null

    /** Invoked whenever a recording segment needs a keyframe to open. */
    var onKeyFrameRequest: (() -> Unit)? = null
        set(value) {
            field = value
            storageManager?.onKeyFrameRequest = value
        }

    /**
     * Stops the recording when the disk stays critically full after the
     * retention clean-up, so the owner can release what it holds for the
     * recording too. Invoked on an IO thread; without it the recorder stops
     * on its own.
     */
    @Volatile
    var onStorageFull: (() -> Unit)? = null

    fun initialize(
        encoderConfig: EncoderConfig = EncoderConfig.PRESET_1080P,
        segmentDuration: SegmentDuration = SegmentDuration.FIVE_MINUTES
    ): Boolean {
        if (storageManager != null) {
            Timber.w("Storage manager already initialized")
            return true
        }

        this.encoderConfig = encoderConfig
        this.segmentDuration = segmentDuration
        val manager = StorageManagerBuilder(context)
            .encoderConfig(encoderConfig)
            .segmentDuration(segmentDuration)
            .build()

        manager.addListener(object : RecordingListener {
            override fun onRecordingEvent(event: RecordingEvent) {
                when (event) {
                    is RecordingEvent.Started -> {
                        _recordingState.value = RecordingState.RECORDING
                        onStateChanged?.invoke()
                    }
                    is RecordingEvent.Stopped -> {
                        _recordingState.value = RecordingState.IDLE
                        onStateChanged?.invoke()
                    }
                    is RecordingEvent.Paused -> {
                        _recordingState.value = RecordingState.PAUSED
                    }
                    is RecordingEvent.Resumed -> {
                        _recordingState.value = RecordingState.RECORDING
                    }
                    is RecordingEvent.Error -> {
                        lastError = event.error
                        _recordingState.value = RecordingState.ERROR
                        Timber.e("Recording error: ${event.error}")
                    }
                    is RecordingEvent.SegmentCompleted -> {
                        Timber.d("Recording segment completed: ${event.filePath}")
                    }
                    is RecordingEvent.NewSegmentStarted -> {
                        Timber.d("New recording segment: ${event.filePath}")
                    }
                }
            }
        })

        // A full disk stops the recording through the owner (off the
        // recorder's thread), or right here when nobody asked to handle it
        manager.onStorageFull = {
            val stop = onStorageFull
            if (stop != null) {
                scope.launch(Dispatchers.IO) { stop() }
            } else {
                manager.stopRecording()
            }
        }
        manager.onKeyFrameRequest = onKeyFrameRequest
        manager.audioFormat = audioFormat
        storageManager = manager
        Timber.i("Storage manager initialized")
        return true
    }

    /**
     * Set the encoder's format plus SPS/PPS(/VPS) so a segment can open before
     * the stream has carried them.
     */
    fun setVideoFormat(format: MediaFormat, sps: ByteArray? = null, pps: ByteArray? = null, vps: ByteArray? = null) {
        storageManager?.setVideoFormat(format, sps, pps, vps)
    }

    /** Record audio described by [format] from the next segment, or video only when null. */
    fun setAudioFormat(format: MediaFormat?) {
        audioFormat = format
        storageManager?.audioFormat = format
    }

    fun startRecording(): Boolean {
        if (storageManager == null) {
            Timber.e("Storage manager not initialized")
            return false
        }

        // A new start: an error from an earlier recording no longer applies
        lastError = null
        val success = storageManager?.startRecording() == true
        if (!success) {
            Timber.e("Failed to start recording")
        } else {
            Timber.i("Recording started")
        }
        return success
    }

    fun stopRecording(): List<String> {
        if (_recordingState.value == RecordingState.IDLE) {
            Timber.w("Not recording")
            return emptyList()
        }

        val segments = storageManager?.stopRecording() ?: emptyList()
        Timber.i("Recording stopped: ${segments.size} segments")
        return segments
    }

    fun pauseRecording(): Boolean = storageManager?.pauseRecording() == true

    fun resumeRecording(): Boolean = storageManager?.resumeRecording() == true

    fun isRecording(): Boolean = _recordingState.value == RecordingState.RECORDING

    fun isPaused(): Boolean = _recordingState.value == RecordingState.PAUSED

    fun getStats(): RecordingStats = storageManager?.getRecordingStats() ?: RecordingStats()

    /**
     * Storage status, read now: space and warning level, the recordings on
     * disk, the recorder's state and the last clean-up. Works with no recorder.
     */
    fun getStorageStatus(): StorageStatus {
        val files = localStorage.listRecordings()
        val space = localStorage.getStorageSpace()
        return StorageStatus(
            state = storageManager?.state?.value ?: StorageManagerState.IDLE,
            recordingStats = getStats(),
            storageInfo = space,
            warningLevel = when {
                space.isCriticallyLowSpace -> StorageWarningLevel.CRITICAL
                space.isLowSpace -> StorageWarningLevel.LOW
                else -> StorageWarningLevel.NORMAL
            },
            totalRecordings = files.size,
            totalRecordingsSizeBytes = files.sumOf { it.sizeBytes },
            lastRetentionResult = lastRetentionResult
        )
    }

    /** The recordings on disk, newest first, including the segment being written. */
    fun listRecordings(): List<RecordingFile> = localStorage.listRecordings()

    /** Delete [file]; refused (false) for the segment being written. */
    fun deleteRecording(file: RecordingFile): Boolean {
        if (isOpenSegment(file)) {
            Timber.w("Not deleting ${file.name}: it is being recorded")
            return false
        }
        return localStorage.deleteRecording(file)
    }

    fun getRecordingsPath(): String = localStorage.getRecordingsDirectory().absolutePath

    fun setSegmentDuration(duration: SegmentDuration) {
        segmentDuration = duration
        storageManager?.updateSegmentDuration(duration)
    }

    /**
     * Apply the retention policy now (7 days, 10 GB, keep 1 GB free; never the
     * segment being written) and return what it removed. Runs on the calling
     * thread and does file IO: call it off the main thread.
     */
    fun enforceRetention(): RetentionResult {
        val openSegment = setOfNotNull(getStats().currentFilePath)
        val result = storageManager?.enforceRetention()
            ?: RetentionPolicy(RetentionConfig.DEFAULT).enforce(localStorage.getRecordingsDirectory(), openSegment)
        lastRetentionResult = result
        return result
    }

    fun isInitialized(): Boolean = storageManager != null

    /** The encoder settings the recorder was initialized for, or null before [initialize]. */
    fun getEncoderConfig(): EncoderConfig? = encoderConfig

    fun release() {
        storageManager?.release()
        storageManager = null
        encoderConfig = null
        _recordingState.value = RecordingState.IDLE
    }

    /** True if [file] is the segment the recorder holds open, compared by canonical path. */
    private fun isOpenSegment(file: RecordingFile): Boolean {
        val open = getStats().currentFilePath ?: return false
        return try {
            File(open).canonicalPath == File(file.path).canonicalPath
        } catch (e: IOException) {
            // Can't tell them apart: keep the file
            Timber.w(e, "Cannot resolve ${file.path}")
            true
        }
    }
}
