package com.lensdaemon.camera

import android.content.Context
import android.media.MediaFormat
import com.lensdaemon.encoder.EncodedFrame
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.output.RecordingEvent
import com.lensdaemon.output.RecordingListener
import com.lensdaemon.output.RecordingState
import com.lensdaemon.output.RecordingStats
import com.lensdaemon.output.SegmentDuration
import com.lensdaemon.storage.RecordingFile
import com.lensdaemon.storage.StorageManager
import com.lensdaemon.storage.StorageManagerBuilder
import com.lensdaemon.storage.StorageStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Coordinates local recording, storage management, and retention.
 *
 * Extracted from CameraService to isolate recording concerns.
 */
class RecordingCoordinator(
    private val context: Context,
    private val scope: CoroutineScope
) {
    private var storageManager: StorageManager? = null

    private val _recordingState = MutableStateFlow(RecordingState.IDLE)
    val recordingState: StateFlow<RecordingState> = _recordingState.asStateFlow()

    /** Frame listener that forwards encoded frames to the file writer */
    val frameListener: (EncodedFrame) -> Unit = { frame ->
        storageManager?.writeFrame(frame)
    }

    /** Callback for notification updates when recording state changes */
    var onStateChanged: (() -> Unit)? = null

    fun initialize(
        encoderConfig: EncoderConfig = EncoderConfig.PRESET_1080P,
        segmentDuration: SegmentDuration = SegmentDuration.FIVE_MINUTES
    ): Boolean {
        if (storageManager != null) {
            Timber.w("Storage manager already initialized")
            return true
        }

        storageManager = StorageManagerBuilder(context)
            .encoderConfig(encoderConfig)
            .segmentDuration(segmentDuration)
            .build()

        storageManager?.addListener(object : RecordingListener {
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

        Timber.i("Storage manager initialized")
        return true
    }

    fun setVideoFormat(format: MediaFormat) {
        storageManager?.setVideoFormat(format)
    }

    fun startRecording(): Boolean {
        if (storageManager == null) {
            Timber.e("Storage manager not initialized")
            return false
        }

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

    fun getStorageStatus(): StorageStatus = storageManager?.status?.value ?: StorageStatus()

    fun listRecordings(): List<RecordingFile> = storageManager?.listRecordings() ?: emptyList()

    fun deleteRecording(file: RecordingFile): Boolean = storageManager?.deleteRecording(file) == true

    fun getRecordingsPath(): String =
        storageManager?.getRecordingsDirectory()?.absolutePath ?: ""

    fun setSegmentDuration(duration: SegmentDuration) {
        storageManager?.updateSegmentDuration(duration)
    }

    fun enforceRetention() {
        scope.launch {
            storageManager?.enforceRetention()
        }
    }

    fun isInitialized(): Boolean = storageManager != null

    fun getEncoderConfig(): EncoderConfig? = null // Config comes from encoder, not storage

    fun release() {
        storageManager?.release()
        storageManager = null
        _recordingState.value = RecordingState.IDLE
    }
}
