package com.lensdaemon.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Storage location type
 */
enum class StorageLocation {
    /** Internal app storage (private) */
    INTERNAL,

    /** External app storage (private, on SD card if available) */
    EXTERNAL_APP,

    /** External public storage (accessible by other apps) */
    EXTERNAL_PUBLIC,

    /** Custom path specified by user */
    CUSTOM
}

/**
 * Storage space info
 */
data class StorageSpaceInfo(
    /** Total space in bytes */
    val totalBytes: Long,

    /** Available/free space in bytes */
    val availableBytes: Long,

    /** Used space in bytes */
    val usedBytes: Long,

    /** Storage location path */
    val path: String
) {
    /** Total space in GB */
    val totalGB: Float get() = totalBytes / (1024f * 1024f * 1024f)

    /** Available space in GB */
    val availableGB: Float get() = availableBytes / (1024f * 1024f * 1024f)

    /** Used space in GB */
    val usedGB: Float get() = usedBytes / (1024f * 1024f * 1024f)

    /** Usage percentage */
    val usagePercent: Float get() = if (totalBytes > 0) {
        (usedBytes * 100f) / totalBytes
    } else 0f

    /** Check if low on space (less than 1GB) */
    val isLowSpace: Boolean get() = availableBytes < 1L * 1024 * 1024 * 1024

    /** Check if critically low on space (less than 500MB) */
    val isCriticallyLowSpace: Boolean get() = availableBytes < 500L * 1024 * 1024
}

/**
 * Recording file info
 */
data class RecordingFile(
    /** File path */
    val path: String,

    /** File name */
    val name: String,

    /** File size in bytes */
    val sizeBytes: Long,

    /** Last modified timestamp */
    val lastModifiedMs: Long,

    /** Duration in seconds (if known) */
    val durationSec: Float? = null
) {
    /** Size in MB */
    val sizeMB: Float get() = sizeBytes / (1024f * 1024f)

    /** Formatted last modified date */
    val lastModifiedFormatted: String get() {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        return format.format(Date(lastModifiedMs))
    }

    /** Age in hours */
    val ageHours: Long get() = (System.currentTimeMillis() - lastModifiedMs) / (1000 * 60 * 60)

    companion object {
        fun fromFile(file: File): RecordingFile {
            return RecordingFile(
                path = file.absolutePath,
                name = file.name,
                sizeBytes = file.length(),
                lastModifiedMs = file.lastModified()
            )
        }
    }
}

/**
 * Low storage warning levels
 */
enum class StorageWarningLevel {
    NORMAL,
    LOW,
    CRITICAL
}

/**
 * Storage event listener
 */
interface StorageEventListener {
    fun onStorageWarning(level: StorageWarningLevel, availableBytes: Long)
    fun onRecordingDeleted(file: RecordingFile)
    fun onStorageError(error: String)
}

/**
 * Local storage manager for recordings
 *
 * Handles:
 * - Storage location management
 * - Disk space monitoring
 * - Recording file enumeration
 * - Path generation for new recordings
 * - Storage cleanup coordination
 */
class LocalStorage(
    private val context: Context,
    private val storageLocation: StorageLocation = StorageLocation.EXTERNAL_APP
) {
    companion object {
        private const val TAG = "LocalStorage"
        private const val RECORDINGS_DIR = "recordings"
        private const val SPACE_CHECK_INTERVAL_MS = 30_000L // 30 seconds
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var spaceMonitorJob: Job? = null

    private val _storageInfo = MutableStateFlow(StorageSpaceInfo(0, 0, 0, ""))
    val storageInfo: StateFlow<StorageSpaceInfo> = _storageInfo.asStateFlow()

    private val _warningLevel = MutableStateFlow(StorageWarningLevel.NORMAL)
    val warningLevel: StateFlow<StorageWarningLevel> = _warningLevel.asStateFlow()

    private val listeners = mutableListOf<StorageEventListener>()

    private var customPath: File? = null

    /**
     * Get the recordings directory
     */
    fun getRecordingsDirectory(): File {
        val baseDir = when (storageLocation) {
            StorageLocation.INTERNAL -> context.filesDir
            StorageLocation.EXTERNAL_APP -> context.getExternalFilesDir(null)
            StorageLocation.EXTERNAL_PUBLIC -> {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            }
            StorageLocation.CUSTOM -> customPath
        } ?: context.filesDir

        val recordingsDir = File(baseDir, RECORDINGS_DIR)
        if (!recordingsDir.exists()) {
            recordingsDir.mkdirs()
        }

        return recordingsDir
    }

    /**
     * Set a custom storage path
     */
    fun setCustomPath(path: File) {
        customPath = path
        if (!path.exists()) {
            path.mkdirs()
        }
    }

    /**
     * Get storage space information
     */
    fun getStorageSpace(): StorageSpaceInfo {
        val dir = getRecordingsDirectory()
        return getStorageSpaceForPath(dir)
    }

    /**
     * Get storage space for a specific path
     */
    private fun getStorageSpaceForPath(path: File): StorageSpaceInfo {
        return try {
            val stat = StatFs(path.absolutePath)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availableBlocks = stat.availableBlocksLong

            val totalBytes = totalBlocks * blockSize
            val availableBytes = availableBlocks * blockSize
            val usedBytes = totalBytes - availableBytes

            StorageSpaceInfo(
                totalBytes = totalBytes,
                availableBytes = availableBytes,
                usedBytes = usedBytes,
                path = path.absolutePath
            )
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error getting storage space")
            StorageSpaceInfo(0, 0, 0, path.absolutePath)
        }
    }

    /**
     * Start monitoring storage space
     */
    fun startSpaceMonitoring() {
        stopSpaceMonitoring()

        spaceMonitorJob = scope.launch {
            while (isActive) {
                checkStorageSpace()
                delay(SPACE_CHECK_INTERVAL_MS)
            }
        }

        Timber.tag(TAG).d("Storage space monitoring started")
    }

    /**
     * Stop monitoring storage space
     */
    fun stopSpaceMonitoring() {
        spaceMonitorJob?.cancel()
        spaceMonitorJob = null
    }

    /**
     * Check storage space and update warning level
     */
    private fun checkStorageSpace() {
        val info = getStorageSpace()
        _storageInfo.value = info

        val newLevel = when {
            info.isCriticallyLowSpace -> StorageWarningLevel.CRITICAL
            info.isLowSpace -> StorageWarningLevel.LOW
            else -> StorageWarningLevel.NORMAL
        }

        if (newLevel != _warningLevel.value) {
            _warningLevel.value = newLevel
            notifyStorageWarning(newLevel, info.availableBytes)
        }
    }

    /**
     * List all recording files
     */
    fun listRecordings(): List<RecordingFile> {
        val dir = getRecordingsDirectory()
        if (!dir.exists()) {
            return emptyList()
        }

        return dir.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() == "mp4" }
            .map { RecordingFile.fromFile(it) }
            .sortedByDescending { it.lastModifiedMs }
            .toList()
    }

    /**
     * Get total size of all recordings
     */
    fun getTotalRecordingsSize(): Long {
        return listRecordings().sumOf { it.sizeBytes }
    }

    /**
     * Get number of recording files
     */
    fun getRecordingCount(): Int {
        return listRecordings().size
    }

    /**
     * Delete a recording file
     */
    fun deleteRecording(file: RecordingFile): Boolean {
        return try {
            val f = File(file.path)
            if (f.exists() && f.delete()) {
                notifyRecordingDeleted(file)
                Timber.tag(TAG).d("Deleted recording: ${file.name}")
                true
            } else {
                Timber.tag(TAG).w("Failed to delete recording: ${file.path}")
                false
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error deleting recording: ${file.path}")
            false
        }
    }

    /**
     * Delete multiple recordings
     */
    fun deleteRecordings(files: List<RecordingFile>): Int {
        var deleted = 0
        files.forEach { file ->
            if (deleteRecording(file)) {
                deleted++
            }
        }
        return deleted
    }

    /**
     * Delete oldest recordings to free up space
     */
    fun deleteOldestToFreeSpace(bytesNeeded: Long): Int {
        val recordings = listRecordings().sortedBy { it.lastModifiedMs }
        var freedBytes = 0L
        var deleted = 0

        for (recording in recordings) {
            if (freedBytes >= bytesNeeded) {
                break
            }
            if (deleteRecording(recording)) {
                freedBytes += recording.sizeBytes
                deleted++
            }
        }

        Timber.tag(TAG).d("Deleted $deleted recordings, freed ${freedBytes / (1024 * 1024)}MB")
        return deleted
    }

    /**
     * Check if there's enough space for a recording
     */
    fun hasSpaceForRecording(estimatedBytes: Long): Boolean {
        val info = getStorageSpace()
        // Leave at least 500MB free after recording
        return info.availableBytes > (estimatedBytes + 500L * 1024 * 1024)
    }

    /**
     * Estimate recording size based on bitrate and duration
     */
    fun estimateRecordingSize(bitrateBps: Int, durationSec: Int): Long {
        // Estimate with 10% overhead for container
        return (bitrateBps.toLong() * durationSec * 110) / (8 * 100)
    }

    /**
     * Generate a path for a new recording
     */
    fun generateRecordingPath(prefix: String = "recording"): File {
        val dir = getRecordingsDirectory()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "${prefix}_${timestamp}.mp4"
        return File(dir, filename)
    }

    /**
     * Get the oldest recording file
     */
    fun getOldestRecording(): RecordingFile? {
        return listRecordings().minByOrNull { it.lastModifiedMs }
    }

    /**
     * Get the newest recording file
     */
    fun getNewestRecording(): RecordingFile? {
        return listRecordings().maxByOrNull { it.lastModifiedMs }
    }

    /**
     * Add storage event listener
     */
    fun addListener(listener: StorageEventListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Remove storage event listener
     */
    fun removeListener(listener: StorageEventListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * Clean up resources
     */
    fun release() {
        stopSpaceMonitoring()
        scope.cancel()
        listeners.clear()
    }

    private fun notifyStorageWarning(level: StorageWarningLevel, availableBytes: Long) {
        synchronized(listeners) {
            listeners.forEach { listener ->
                try {
                    listener.onStorageWarning(level, availableBytes)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error notifying listener")
                }
            }
        }
    }

    private fun notifyRecordingDeleted(file: RecordingFile) {
        synchronized(listeners) {
            listeners.forEach { listener ->
                try {
                    listener.onRecordingDeleted(file)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error notifying listener")
                }
            }
        }
    }

    private fun notifyStorageError(error: String) {
        synchronized(listeners) {
            listeners.forEach { listener ->
                try {
                    listener.onStorageError(error)
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Error notifying listener")
                }
            }
        }
    }
}

/**
 * Extension function to format bytes to human-readable string
 */
fun Long.toHumanReadableSize(): String {
    return when {
        this < 1024 -> "$this B"
        this < 1024 * 1024 -> "${this / 1024} KB"
        this < 1024 * 1024 * 1024 -> String.format("%.1f MB", this / (1024f * 1024f))
        else -> String.format("%.2f GB", this / (1024f * 1024f * 1024f))
    }
}
