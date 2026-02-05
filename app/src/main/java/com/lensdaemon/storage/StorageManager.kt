package com.lensdaemon.storage

import android.content.Context
import android.media.MediaFormat
import com.lensdaemon.encoder.EncodedFrame
import com.lensdaemon.encoder.EncoderConfig
import com.lensdaemon.output.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Storage manager state
 */
enum class StorageManagerState {
    IDLE,
    RECORDING,
    PAUSED,
    ERROR
}

/**
 * Combined storage statistics
 */
data class StorageStatus(
    /** Current state */
    val state: StorageManagerState = StorageManagerState.IDLE,

    /** Recording statistics */
    val recordingStats: RecordingStats = RecordingStats(),

    /** Storage space info */
    val storageInfo: StorageSpaceInfo = StorageSpaceInfo(0, 0, 0, ""),

    /** Storage warning level */
    val warningLevel: StorageWarningLevel = StorageWarningLevel.NORMAL,

    /** Total recording files on disk */
    val totalRecordings: Int = 0,

    /** Total size of recordings on disk */
    val totalRecordingsSizeBytes: Long = 0,

    /** Last retention cleanup result */
    val lastRetentionResult: RetentionResult? = null
)

/**
 * Storage manager configuration
 */
data class StorageManagerConfig(
    /** Storage location */
    val storageLocation: StorageLocation = StorageLocation.EXTERNAL_APP,

    /** Encoder configuration */
    val encoderConfig: EncoderConfig = EncoderConfig.PRESET_1080P,

    /** Segment duration */
    val segmentDuration: SegmentDuration = SegmentDuration.FIVE_MINUTES,

    /** Retention policy configuration */
    val retentionConfig: RetentionConfig = RetentionConfig.DEFAULT,

    /** Video rotation in degrees */
    val rotationDegrees: Int = 0,

    /** Enable automatic retention enforcement */
    val autoEnforceRetention: Boolean = true,

    /** Interval for retention checks (ms) */
    val retentionCheckIntervalMs: Long = TimeUnit.HOURS.toMillis(1)
)

/**
 * Storage manager - coordinates recording, storage, and retention
 *
 * This is the main entry point for recording functionality, coordinating:
 * - FileWriter for MP4 recording with segmentation
 * - LocalStorage for disk space management
 * - RetentionPolicy for automatic cleanup
 *
 * Features:
 * - Start/stop/pause recording
 * - Automatic file segmentation
 * - Disk space monitoring
 * - Automatic retention policy enforcement
 * - Recording and storage statistics
 */
class StorageManager(
    private val context: Context,
    private val config: StorageManagerConfig = StorageManagerConfig()
) : RecordingListener, StorageEventListener {

    companion object {
        private const val TAG = "StorageManager"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Components
    private val localStorage = LocalStorage(context, config.storageLocation)
    private val retentionPolicy = RetentionPolicy(config.retentionConfig)
    private var fileWriter: FileWriter? = null

    // State
    private val _state = MutableStateFlow(StorageManagerState.IDLE)
    val state: StateFlow<StorageManagerState> = _state.asStateFlow()

    private val _status = MutableStateFlow(StorageStatus())
    val status: StateFlow<StorageStatus> = _status.asStateFlow()

    private var retentionJob: Job? = null
    private var videoFormat: MediaFormat? = null

    private val listeners = mutableListOf<RecordingListener>()

    init {
        // Set up listeners
        localStorage.addListener(this)

        // Start storage monitoring
        localStorage.startSpaceMonitoring()
    }

    /**
     * Set the video format from encoder output
     * Must be called before starting recording
     */
    fun setVideoFormat(format: MediaFormat) {
        this.videoFormat = format
        fileWriter?.setVideoFormat(format)
        Timber.tag(TAG).d("Video format set")
    }

    /**
     * Start recording
     */
    fun startRecording(): Boolean {
        if (_state.value == StorageManagerState.RECORDING) {
            Timber.tag(TAG).w("Already recording")
            return false
        }

        val format = videoFormat
        if (format == null) {
            Timber.tag(TAG).e("Cannot start recording: video format not set")
            return false
        }

        // Check available space
        val storageInfo = localStorage.getStorageSpace()
        if (storageInfo.isCriticallyLowSpace) {
            Timber.tag(TAG).e("Cannot start recording: critically low storage space")
            onRecordingEvent(RecordingEvent.Error("Critically low storage space"))
            return false
        }

        // Enforce retention before starting if needed
        if (config.autoEnforceRetention) {
            enforceRetentionSync()
        }

        // Create file writer
        val writerConfig = FileWriterConfig(
            outputDirectory = localStorage.getRecordingsDirectory(),
            encoderConfig = config.encoderConfig,
            segmentDuration = config.segmentDuration,
            rotationDegrees = config.rotationDegrees
        )

        val writer = FileWriter(context, writerConfig)
        writer.addListener(this)
        writer.setVideoFormat(format)

        if (!writer.startRecording()) {
            Timber.tag(TAG).e("Failed to start file writer")
            writer.release()
            return false
        }

        fileWriter = writer
        _state.value = StorageManagerState.RECORDING

        // Start retention enforcement schedule
        startRetentionSchedule()

        updateStatus()
        Timber.tag(TAG).i("Recording started")
        return true
    }

    /**
     * Stop recording
     */
    fun stopRecording(): List<String> {
        val writer = fileWriter
        if (writer == null || _state.value == StorageManagerState.IDLE) {
            Timber.tag(TAG).w("Not recording")
            return emptyList()
        }

        val segments = writer.stopRecording()
        writer.removeListener(this)
        writer.release()
        fileWriter = null

        _state.value = StorageManagerState.IDLE

        // Stop retention schedule
        stopRetentionSchedule()

        updateStatus()
        Timber.tag(TAG).i("Recording stopped: ${segments.size} segments")
        return segments
    }

    /**
     * Pause recording
     */
    fun pauseRecording(): Boolean {
        val writer = fileWriter ?: return false

        if (writer.pauseRecording()) {
            _state.value = StorageManagerState.PAUSED
            updateStatus()
            return true
        }
        return false
    }

    /**
     * Resume recording
     */
    fun resumeRecording(): Boolean {
        val writer = fileWriter ?: return false

        if (writer.resumeRecording()) {
            _state.value = StorageManagerState.RECORDING
            updateStatus()
            return true
        }
        return false
    }

    /**
     * Write an encoded frame
     */
    fun writeFrame(frame: EncodedFrame): Boolean {
        return fileWriter?.writeFrame(frame) == true
    }

    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean = _state.value == StorageManagerState.RECORDING

    /**
     * Check if recording is paused
     */
    fun isPaused(): Boolean = _state.value == StorageManagerState.PAUSED

    /**
     * Get recording stats
     */
    fun getRecordingStats(): RecordingStats {
        return fileWriter?.getStats() ?: RecordingStats()
    }

    /**
     * Get storage space info
     */
    fun getStorageInfo(): StorageSpaceInfo {
        return localStorage.getStorageSpace()
    }

    /**
     * Get storage statistics from retention policy
     */
    fun getStorageStats(): StorageStats {
        return retentionPolicy.getStorageStats(localStorage.getRecordingsDirectory())
    }

    /**
     * List all recordings
     */
    fun listRecordings(): List<RecordingFile> {
        return localStorage.listRecordings()
    }

    /**
     * Delete a recording
     */
    fun deleteRecording(file: RecordingFile): Boolean {
        return localStorage.deleteRecording(file)
    }

    /**
     * Manually enforce retention policy
     */
    fun enforceRetention(): RetentionResult {
        val result = retentionPolicy.enforce(localStorage.getRecordingsDirectory())
        updateStatus()
        return result
    }

    /**
     * Synchronous retention enforcement (for use before recording)
     */
    private fun enforceRetentionSync() {
        try {
            val result = retentionPolicy.enforce(localStorage.getRecordingsDirectory())
            if (result.hasDeleted) {
                Timber.tag(TAG).i(
                    "Retention enforced: ${result.filesDeleted} files deleted, " +
                    "${result.bytesFreedMB}MB freed"
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error enforcing retention")
        }
    }

    /**
     * Preview what would be deleted by retention policy
     */
    fun previewRetention(): List<File> {
        return retentionPolicy.preview(localStorage.getRecordingsDirectory())
    }

    /**
     * Get recordings directory
     */
    fun getRecordingsDirectory(): File {
        return localStorage.getRecordingsDirectory()
    }

    /**
     * Add recording event listener
     */
    fun addListener(listener: RecordingListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Remove recording event listener
     */
    fun removeListener(listener: RecordingListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * Update encoder config (for new recordings)
     */
    fun updateEncoderConfig(config: EncoderConfig) {
        // Config will be used for next recording
        Timber.tag(TAG).d("Encoder config updated: ${config.width}x${config.height} @ ${config.bitrateBps / 1000}kbps")
    }

    /**
     * Update segment duration (for new recordings)
     */
    fun updateSegmentDuration(duration: SegmentDuration) {
        Timber.tag(TAG).d("Segment duration updated: ${duration.displayName}")
    }

    /**
     * Release all resources
     */
    fun release() {
        if (isRecording() || isPaused()) {
            stopRecording()
        }

        stopRetentionSchedule()
        localStorage.removeListener(this)
        localStorage.release()
        scope.cancel()
        listeners.clear()

        Timber.tag(TAG).d("StorageManager released")
    }

    // RecordingListener implementation
    override fun onRecordingEvent(event: RecordingEvent) {
        when (event) {
            is RecordingEvent.Error -> {
                _state.value = StorageManagerState.ERROR
            }
            else -> {}
        }

        // Forward to listeners
        synchronized(listeners) {
            listeners.forEach { listener ->
                try {
                    listener.onRecordingEvent(event)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error forwarding event to listener")
                }
            }
        }

        updateStatus()
    }

    // StorageEventListener implementation
    override fun onStorageWarning(level: StorageWarningLevel, availableBytes: Long) {
        Timber.tag(TAG).w("Storage warning: $level (${availableBytes / (1024 * 1024)}MB available)")

        when (level) {
            StorageWarningLevel.CRITICAL -> {
                // If critically low during recording, enforce retention
                if (isRecording() && config.autoEnforceRetention) {
                    scope.launch {
                        val result = enforceRetention()
                        if (result.bytesFreed < 500L * 1024 * 1024) {
                            // Still not enough space, stop recording
                            Timber.tag(TAG).e("Storage critically low, stopping recording")
                            stopRecording()
                            onRecordingEvent(RecordingEvent.Error("Storage full - recording stopped"))
                        }
                    }
                }
            }
            StorageWarningLevel.LOW -> {
                // Low warning - enforce retention if enabled
                if (config.autoEnforceRetention) {
                    scope.launch { enforceRetention() }
                }
            }
            StorageWarningLevel.NORMAL -> {
                // All good
            }
        }

        updateStatus()
    }

    override fun onRecordingDeleted(file: RecordingFile) {
        updateStatus()
    }

    override fun onStorageError(error: String) {
        Timber.tag(TAG).e("Storage error: $error")
    }

    /**
     * Start periodic retention enforcement
     */
    private fun startRetentionSchedule() {
        if (!config.autoEnforceRetention) return

        stopRetentionSchedule()

        retentionJob = scope.launch {
            while (isActive) {
                delay(config.retentionCheckIntervalMs)
                try {
                    enforceRetention()
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error in scheduled retention enforcement")
                }
            }
        }
    }

    /**
     * Stop periodic retention enforcement
     */
    private fun stopRetentionSchedule() {
        retentionJob?.cancel()
        retentionJob = null
    }

    /**
     * Update status flow
     */
    private fun updateStatus() {
        _status.value = StorageStatus(
            state = _state.value,
            recordingStats = getRecordingStats(),
            storageInfo = localStorage.getStorageSpace(),
            warningLevel = localStorage.warningLevel.value,
            totalRecordings = localStorage.getRecordingCount(),
            totalRecordingsSizeBytes = localStorage.getTotalRecordingsSize()
        )
    }
}

/**
 * Builder for StorageManager configuration
 */
class StorageManagerBuilder(private val context: Context) {
    private var storageLocation: StorageLocation = StorageLocation.EXTERNAL_APP
    private var encoderConfig: EncoderConfig = EncoderConfig.PRESET_1080P
    private var segmentDuration: SegmentDuration = SegmentDuration.FIVE_MINUTES
    private var retentionConfig: RetentionConfig = RetentionConfig.DEFAULT
    private var rotationDegrees: Int = 0
    private var autoEnforceRetention: Boolean = true

    fun storageLocation(location: StorageLocation) = apply { this.storageLocation = location }
    fun encoderConfig(config: EncoderConfig) = apply { this.encoderConfig = config }
    fun segmentDuration(duration: SegmentDuration) = apply { this.segmentDuration = duration }
    fun retentionConfig(config: RetentionConfig) = apply { this.retentionConfig = config }
    fun rotation(degrees: Int) = apply { this.rotationDegrees = degrees }
    fun autoEnforceRetention(enable: Boolean) = apply { this.autoEnforceRetention = enable }

    // Convenience methods
    fun continuousRecording() = apply { this.segmentDuration = SegmentDuration.CONTINUOUS }
    fun segmentEvery5Minutes() = apply { this.segmentDuration = SegmentDuration.FIVE_MINUTES }
    fun segmentEvery15Minutes() = apply { this.segmentDuration = SegmentDuration.FIFTEEN_MINUTES }

    fun keepLastDays(days: Int) = apply {
        this.retentionConfig = RetentionConfig(
            type = RetentionType.MAX_AGE,
            maxAgeMs = java.util.concurrent.TimeUnit.DAYS.toMillis(days.toLong())
        )
    }

    fun maxStorageGB(gb: Int) = apply {
        this.retentionConfig = RetentionConfig(
            type = RetentionType.MAX_SIZE,
            maxSizeBytes = gb.toLong() * 1024 * 1024 * 1024
        )
    }

    fun build(): StorageManager {
        val config = StorageManagerConfig(
            storageLocation = storageLocation,
            encoderConfig = encoderConfig,
            segmentDuration = segmentDuration,
            retentionConfig = retentionConfig,
            rotationDegrees = rotationDegrees,
            autoEnforceRetention = autoEnforceRetention
        )
        return StorageManager(context, config)
    }
}
