package com.lensdaemon.storage

import timber.log.Timber
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Retention policy type
 */
enum class RetentionType {
    /** Keep all files, no automatic deletion */
    KEEP_ALL,

    /** Delete files older than max age */
    MAX_AGE,

    /** Delete oldest files when exceeding max size */
    MAX_SIZE,

    /** Delete files based on both age and size */
    MAX_AGE_AND_SIZE
}

/**
 * Retention policy configuration
 */
data class RetentionConfig(
    /** Policy type */
    val type: RetentionType = RetentionType.KEEP_ALL,

    /** Maximum age for files in milliseconds */
    val maxAgeMs: Long = TimeUnit.DAYS.toMillis(7),

    /** Maximum total size in bytes */
    val maxSizeBytes: Long = 10L * 1024 * 1024 * 1024, // 10 GB

    /** Minimum free space to maintain in bytes */
    val minFreeSpaceBytes: Long = 1L * 1024 * 1024 * 1024, // 1 GB

    /** File patterns to include (e.g., "*.mp4") */
    val includePatterns: List<String> = listOf("*.mp4"),

    /** Whether to delete empty directories */
    val deleteEmptyDirs: Boolean = true
) {
    companion object {
        /** Default: keep last 7 days or 10GB, whichever comes first */
        val DEFAULT = RetentionConfig(
            type = RetentionType.MAX_AGE_AND_SIZE
        )

        /** Keep last 24 hours */
        val LAST_24_HOURS = RetentionConfig(
            type = RetentionType.MAX_AGE,
            maxAgeMs = TimeUnit.HOURS.toMillis(24)
        )

        /** Keep last 7 days */
        val LAST_7_DAYS = RetentionConfig(
            type = RetentionType.MAX_AGE,
            maxAgeMs = TimeUnit.DAYS.toMillis(7)
        )

        /** Keep last 30 days */
        val LAST_30_DAYS = RetentionConfig(
            type = RetentionType.MAX_AGE,
            maxAgeMs = TimeUnit.DAYS.toMillis(30)
        )

        /** Keep max 5GB */
        val MAX_5GB = RetentionConfig(
            type = RetentionType.MAX_SIZE,
            maxSizeBytes = 5L * 1024 * 1024 * 1024
        )

        /** Keep max 10GB */
        val MAX_10GB = RetentionConfig(
            type = RetentionType.MAX_SIZE,
            maxSizeBytes = 10L * 1024 * 1024 * 1024
        )

        /** Keep all recordings */
        val KEEP_ALL = RetentionConfig(
            type = RetentionType.KEEP_ALL
        )
    }

    /** Max age in hours */
    val maxAgeHours: Long get() = TimeUnit.MILLISECONDS.toHours(maxAgeMs)

    /** Max age in days */
    val maxAgeDays: Long get() = TimeUnit.MILLISECONDS.toDays(maxAgeMs)

    /** Max size in MB */
    val maxSizeMB: Long get() = maxSizeBytes / (1024 * 1024)

    /** Max size in GB */
    val maxSizeGB: Float get() = maxSizeBytes / (1024f * 1024f * 1024f)
}

/**
 * Result of retention policy enforcement
 */
data class RetentionResult(
    /** Number of files deleted */
    val filesDeleted: Int,

    /** Total bytes freed */
    val bytesFreed: Long,

    /** Number of directories deleted */
    val dirsDeleted: Int,

    /** Files that failed to delete */
    val failedFiles: List<String>,

    /** Total time taken in ms */
    val durationMs: Long
) {
    /** Bytes freed in MB */
    val bytesFreedMB: Float get() = bytesFreed / (1024f * 1024f)

    /** Bytes freed in GB */
    val bytesFreedGB: Float get() = bytesFreed / (1024f * 1024f * 1024f)

    /** Whether any files were deleted */
    val hasDeleted: Boolean get() = filesDeleted > 0 || dirsDeleted > 0

    /** Whether all deletions succeeded */
    val allSucceeded: Boolean get() = failedFiles.isEmpty()
}

/**
 * File info for sorting during retention enforcement
 */
private data class RecordingFileInfo(
    val file: File,
    val lastModified: Long,
    val size: Long
)

/**
 * Retention policy enforcer
 *
 * Manages automatic cleanup of old recordings based on configured policies.
 * Supports:
 * - Maximum age-based deletion
 * - Maximum size-based deletion
 * - Combined age+size policies
 * - Minimum free space maintenance
 */
class RetentionPolicy(
    private val config: RetentionConfig
) {
    companion object {
        private const val TAG = "RetentionPolicy"
    }

    /**
     * Enforce the retention policy on a directory
     *
     * @param directory The directory to enforce policy on
     * @return Result of the enforcement
     */
    fun enforce(directory: File): RetentionResult {
        val startTime = System.currentTimeMillis()

        if (!directory.exists() || !directory.isDirectory) {
            return RetentionResult(0, 0, 0, emptyList(), 0)
        }

        if (config.type == RetentionType.KEEP_ALL) {
            return RetentionResult(0, 0, 0, emptyList(), 0)
        }

        Timber.tag(TAG).d("Enforcing retention policy on ${directory.absolutePath}")

        // Get all matching files
        val files = getMatchingFiles(directory)
        if (files.isEmpty()) {
            return RetentionResult(0, 0, 0, emptyList(), 0)
        }

        // Sort by last modified (oldest first)
        val sortedFiles = files.sortedBy { it.lastModified }

        val filesToDelete = mutableListOf<File>()

        when (config.type) {
            RetentionType.MAX_AGE -> {
                filesToDelete.addAll(getFilesExceedingAge(sortedFiles))
            }
            RetentionType.MAX_SIZE -> {
                filesToDelete.addAll(getFilesExceedingSize(sortedFiles))
            }
            RetentionType.MAX_AGE_AND_SIZE -> {
                // First, delete files exceeding age
                filesToDelete.addAll(getFilesExceedingAge(sortedFiles))

                // Then check size with remaining files
                val remainingFiles = sortedFiles.filter { it.file !in filesToDelete }
                filesToDelete.addAll(getFilesExceedingSize(remainingFiles))
            }
            RetentionType.KEEP_ALL -> {
                // No-op
            }
        }

        // Also check minimum free space
        filesToDelete.addAll(getFilesForFreeSpace(directory, sortedFiles, filesToDelete))

        // Perform deletions
        var filesDeleted = 0
        var bytesFreed = 0L
        val failedFiles = mutableListOf<String>()

        for (fileInfo in filesToDelete.distinctBy { it.absolutePath }) {
            try {
                val size = fileInfo.length()
                if (fileInfo.delete()) {
                    filesDeleted++
                    bytesFreed += size
                    Timber.tag(TAG).d("Deleted: ${fileInfo.name} (${size / 1024}KB)")
                } else {
                    failedFiles.add(fileInfo.absolutePath)
                    Timber.tag(TAG).w("Failed to delete: ${fileInfo.absolutePath}")
                }
            } catch (e: Exception) {
                failedFiles.add(fileInfo.absolutePath)
                Timber.tag(TAG).e(e, "Error deleting: ${fileInfo.absolutePath}")
            }
        }

        // Delete empty directories if configured
        var dirsDeleted = 0
        if (config.deleteEmptyDirs) {
            dirsDeleted = deleteEmptyDirectories(directory)
        }

        val duration = System.currentTimeMillis() - startTime

        val result = RetentionResult(
            filesDeleted = filesDeleted,
            bytesFreed = bytesFreed,
            dirsDeleted = dirsDeleted,
            failedFiles = failedFiles,
            durationMs = duration
        )

        Timber.tag(TAG).i(
            "Retention enforcement complete: " +
            "$filesDeleted files deleted, " +
            "${result.bytesFreedMB}MB freed, " +
            "$dirsDeleted dirs deleted"
        )

        return result
    }

    /**
     * Preview what would be deleted without actually deleting
     */
    fun preview(directory: File): List<File> {
        if (!directory.exists() || !directory.isDirectory) {
            return emptyList()
        }

        if (config.type == RetentionType.KEEP_ALL) {
            return emptyList()
        }

        val files = getMatchingFiles(directory)
        if (files.isEmpty()) {
            return emptyList()
        }

        val sortedFiles = files.sortedBy { it.lastModified }
        val filesToDelete = mutableListOf<File>()

        when (config.type) {
            RetentionType.MAX_AGE -> {
                filesToDelete.addAll(getFilesExceedingAge(sortedFiles))
            }
            RetentionType.MAX_SIZE -> {
                filesToDelete.addAll(getFilesExceedingSize(sortedFiles))
            }
            RetentionType.MAX_AGE_AND_SIZE -> {
                filesToDelete.addAll(getFilesExceedingAge(sortedFiles))
                val remainingFiles = sortedFiles.filter { it.file !in filesToDelete }
                filesToDelete.addAll(getFilesExceedingSize(remainingFiles))
            }
            RetentionType.KEEP_ALL -> {
                // No-op
            }
        }

        filesToDelete.addAll(getFilesForFreeSpace(directory, sortedFiles, filesToDelete))

        return filesToDelete.distinctBy { it.absolutePath }
    }

    /**
     * Get current storage stats for a directory
     */
    fun getStorageStats(directory: File): StorageStats {
        if (!directory.exists()) {
            return StorageStats()
        }

        val files = getMatchingFiles(directory)
        val totalSize = files.sumOf { it.size }
        val oldestFile = files.minByOrNull { it.lastModified }
        val newestFile = files.maxByOrNull { it.lastModified }

        return StorageStats(
            fileCount = files.size,
            totalSizeBytes = totalSize,
            oldestFileMs = oldestFile?.lastModified ?: 0,
            newestFileMs = newestFile?.lastModified ?: 0,
            freeSpaceBytes = directory.freeSpace,
            totalSpaceBytes = directory.totalSpace
        )
    }

    /**
     * Get all files matching the include patterns
     */
    private fun getMatchingFiles(directory: File): List<RecordingFileInfo> {
        val files = mutableListOf<RecordingFileInfo>()

        directory.walkTopDown()
            .filter { it.isFile }
            .filter { file -> config.includePatterns.any { pattern -> matchesPattern(file.name, pattern) } }
            .forEach { file ->
                files.add(RecordingFileInfo(
                    file = file,
                    lastModified = file.lastModified(),
                    size = file.length()
                ))
            }

        return files
    }

    /**
     * Get files that exceed the maximum age
     */
    private fun getFilesExceedingAge(files: List<RecordingFileInfo>): List<File> {
        val cutoffTime = System.currentTimeMillis() - config.maxAgeMs
        return files
            .filter { it.lastModified < cutoffTime }
            .map { it.file }
    }

    /**
     * Get oldest files that exceed the maximum total size
     */
    private fun getFilesExceedingSize(files: List<RecordingFileInfo>): List<File> {
        var totalSize = files.sumOf { it.size }
        val toDelete = mutableListOf<File>()

        // Delete oldest files until under max size
        for (fileInfo in files) {
            if (totalSize <= config.maxSizeBytes) {
                break
            }
            toDelete.add(fileInfo.file)
            totalSize -= fileInfo.size
        }

        return toDelete
    }

    /**
     * Get files to delete to maintain minimum free space
     */
    private fun getFilesForFreeSpace(
        directory: File,
        files: List<RecordingFileInfo>,
        alreadyMarked: List<File>
    ): List<File> {
        val freeSpace = directory.freeSpace
        if (freeSpace >= config.minFreeSpaceBytes) {
            return emptyList()
        }

        val bytesNeeded = config.minFreeSpaceBytes - freeSpace
        var bytesFreed = alreadyMarked.sumOf { it.length() }
        val toDelete = mutableListOf<File>()

        for (fileInfo in files) {
            if (bytesFreed >= bytesNeeded) {
                break
            }
            if (fileInfo.file !in alreadyMarked) {
                toDelete.add(fileInfo.file)
                bytesFreed += fileInfo.size
            }
        }

        return toDelete
    }

    /**
     * Delete empty directories recursively
     */
    private fun deleteEmptyDirectories(directory: File): Int {
        var deleted = 0

        directory.listFiles()?.forEach { file ->
            if (file.isDirectory) {
                deleted += deleteEmptyDirectories(file)
                if (file.listFiles()?.isEmpty() == true) {
                    if (file.delete()) {
                        deleted++
                    }
                }
            }
        }

        return deleted
    }

    /**
     * Check if filename matches a glob pattern
     */
    private fun matchesPattern(filename: String, pattern: String): Boolean {
        val regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return filename.matches(Regex(regex, RegexOption.IGNORE_CASE))
    }
}

/**
 * Storage statistics
 */
data class StorageStats(
    /** Number of recording files */
    val fileCount: Int = 0,

    /** Total size of recordings in bytes */
    val totalSizeBytes: Long = 0,

    /** Oldest file timestamp */
    val oldestFileMs: Long = 0,

    /** Newest file timestamp */
    val newestFileMs: Long = 0,

    /** Free space in bytes */
    val freeSpaceBytes: Long = 0,

    /** Total space in bytes */
    val totalSpaceBytes: Long = 0
) {
    /** Total size in MB */
    val totalSizeMB: Float get() = totalSizeBytes / (1024f * 1024f)

    /** Total size in GB */
    val totalSizeGB: Float get() = totalSizeBytes / (1024f * 1024f * 1024f)

    /** Free space in GB */
    val freeSpaceGB: Float get() = freeSpaceBytes / (1024f * 1024f * 1024f)

    /** Used percentage of total space */
    val usedPercentage: Float get() = if (totalSpaceBytes > 0) {
        ((totalSpaceBytes - freeSpaceBytes) * 100f) / totalSpaceBytes
    } else 0f

    /** Age of oldest file in hours */
    val oldestFileAgeHours: Long get() = if (oldestFileMs > 0) {
        TimeUnit.MILLISECONDS.toHours(System.currentTimeMillis() - oldestFileMs)
    } else 0

    /** Age of oldest file in days */
    val oldestFileAgeDays: Long get() = if (oldestFileMs > 0) {
        TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis() - oldestFileMs)
    } else 0
}

/**
 * Builder for RetentionPolicy configuration
 */
class RetentionPolicyBuilder {
    private var type: RetentionType = RetentionType.KEEP_ALL
    private var maxAgeMs: Long = TimeUnit.DAYS.toMillis(7)
    private var maxSizeBytes: Long = 10L * 1024 * 1024 * 1024
    private var minFreeSpaceBytes: Long = 1L * 1024 * 1024 * 1024
    private var includePatterns: List<String> = listOf("*.mp4")
    private var deleteEmptyDirs: Boolean = true

    fun keepAll() = apply { this.type = RetentionType.KEEP_ALL }
    fun maxAge() = apply { this.type = RetentionType.MAX_AGE }
    fun maxSize() = apply { this.type = RetentionType.MAX_SIZE }
    fun maxAgeAndSize() = apply { this.type = RetentionType.MAX_AGE_AND_SIZE }

    fun maxAgeDays(days: Int) = apply { this.maxAgeMs = TimeUnit.DAYS.toMillis(days.toLong()) }
    fun maxAgeHours(hours: Int) = apply { this.maxAgeMs = TimeUnit.HOURS.toMillis(hours.toLong()) }

    fun maxSizeGB(gb: Int) = apply { this.maxSizeBytes = gb.toLong() * 1024 * 1024 * 1024 }
    fun maxSizeMB(mb: Int) = apply { this.maxSizeBytes = mb.toLong() * 1024 * 1024 }

    fun minFreeSpaceGB(gb: Int) = apply { this.minFreeSpaceBytes = gb.toLong() * 1024 * 1024 * 1024 }

    fun includePatterns(vararg patterns: String) = apply { this.includePatterns = patterns.toList() }
    fun deleteEmptyDirs(delete: Boolean) = apply { this.deleteEmptyDirs = delete }

    fun build(): RetentionPolicy {
        val config = RetentionConfig(
            type = type,
            maxAgeMs = maxAgeMs,
            maxSizeBytes = maxSizeBytes,
            minFreeSpaceBytes = minFreeSpaceBytes,
            includePatterns = includePatterns,
            deleteEmptyDirs = deleteEmptyDirs
        )
        return RetentionPolicy(config)
    }
}
