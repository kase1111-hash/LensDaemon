package com.lensdaemon.storage

import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.json.JSONObject
import java.io.File

class UploadTaskTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // -----------------------------------------------------------------------
    // 1. UploadTask construction with defaults
    // -----------------------------------------------------------------------

    @Test
    fun `constructor generates UUID for id`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )

        assertTrue("id should be a non-empty UUID string", task.id.isNotEmpty())
        // UUID format: 8-4-4-4-12 hex digits
        val uuidRegex = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        assertTrue("id should match UUID format", uuidRegex.matches(task.id))
    }

    @Test
    fun `constructor generates unique ids for different tasks`() {
        val task1 = UploadTask(
            localPath = "/tmp/video1.mp4",
            remotePath = "uploads/video1.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )
        val task2 = UploadTask(
            localPath = "/tmp/video2.mp4",
            remotePath = "uploads/video2.mp4",
            destination = UploadDestination.S3,
            fileSize = 2048L
        )

        assertNotEquals("Two tasks should have different ids", task1.id, task2.id)
    }

    @Test
    fun `constructor defaults status to PENDING`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )

        assertEquals(UploadStatus.PENDING, task.status)
    }

    @Test
    fun `constructor defaults bytesUploaded to 0`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )

        assertEquals(0L, task.bytesUploaded)
    }

    @Test
    fun `constructor defaults progress to 0`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )

        assertEquals(0, task.progress)
    }

    @Test
    fun `constructor defaults retryCount to 0`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )

        assertEquals(0, task.retryCount)
    }

    @Test
    fun `constructor defaults maxRetries to 3`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )

        assertEquals(3, task.maxRetries)
    }

    @Test
    fun `constructor sets createdAt automatically`() {
        val before = System.currentTimeMillis()
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )
        val after = System.currentTimeMillis()

        assertTrue("createdAt should be >= time before construction", task.createdAt >= before)
        assertTrue("createdAt should be <= time after construction", task.createdAt <= after)
    }

    // -----------------------------------------------------------------------
    // 2. fileExists()
    // -----------------------------------------------------------------------

    @Test
    fun `fileExists returns true when file exists`() {
        val file = tempFolder.newFile("test_video.mp4")
        val task = UploadTask(
            localPath = file.absolutePath,
            remotePath = "uploads/test_video.mp4",
            destination = UploadDestination.S3,
            fileSize = 0L
        )

        assertTrue("fileExists should return true for existing file", task.fileExists())
    }

    @Test
    fun `fileExists returns false when file does not exist`() {
        val task = UploadTask(
            localPath = "/nonexistent/path/to/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )

        assertFalse("fileExists should return false for nonexistent file", task.fileExists())
    }

    // -----------------------------------------------------------------------
    // 3. fileName property
    // -----------------------------------------------------------------------

    @Test
    fun `fileName extracts filename from path`() {
        val task = UploadTask(
            localPath = "/path/to/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )

        assertEquals("video.mp4", task.fileName)
    }

    @Test
    fun `fileName extracts filename from deeply nested path`() {
        val task = UploadTask(
            localPath = "/a/b/c/d/recording_001.mp4",
            remotePath = "uploads/recording_001.mp4",
            destination = UploadDestination.SMB,
            fileSize = 2048L
        )

        assertEquals("recording_001.mp4", task.fileName)
    }

    @Test
    fun `fileName handles filename only path`() {
        val task = UploadTask(
            localPath = "video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 512L
        )

        assertEquals("video.mp4", task.fileName)
    }

    // -----------------------------------------------------------------------
    // 4. canRetry()
    // -----------------------------------------------------------------------

    @Test
    fun `canRetry returns true when retryCount less than maxRetries and status is FAILED`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )
        task.retryCount = 1
        task.status = UploadStatus.FAILED

        assertTrue("canRetry should return true when retryCount < maxRetries and status is FAILED", task.canRetry())
    }

    @Test
    fun `canRetry returns false when retryCount equals maxRetries`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L,
            maxRetries = 3
        )
        task.retryCount = 3
        task.status = UploadStatus.FAILED

        assertFalse("canRetry should return false when retryCount >= maxRetries", task.canRetry())
    }

    @Test
    fun `canRetry returns false when retryCount exceeds maxRetries`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L,
            maxRetries = 3
        )
        task.retryCount = 5
        task.status = UploadStatus.FAILED

        assertFalse("canRetry should return false when retryCount > maxRetries", task.canRetry())
    }

    @Test
    fun `canRetry returns false when status is PENDING`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )
        task.retryCount = 0
        task.status = UploadStatus.PENDING

        assertFalse("canRetry should return false when status is PENDING", task.canRetry())
    }

    @Test
    fun `canRetry returns false when status is COMPLETED`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )
        task.retryCount = 0
        task.status = UploadStatus.COMPLETED

        assertFalse("canRetry should return false when status is COMPLETED", task.canRetry())
    }

    @Test
    fun `canRetry returns false when status is UPLOADING`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )
        task.retryCount = 0
        task.status = UploadStatus.UPLOADING

        assertFalse("canRetry should return false when status is UPLOADING", task.canRetry())
    }

    @Test
    fun `canRetry returns false when status is CANCELLED`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )
        task.retryCount = 0
        task.status = UploadStatus.CANCELLED

        assertFalse("canRetry should return false when status is CANCELLED", task.canRetry())
    }

    // -----------------------------------------------------------------------
    // 5. getRetryDelayMs()
    // -----------------------------------------------------------------------

    @Test
    fun `getRetryDelayMs returns 1000 for retryCount 0`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )
        task.retryCount = 0

        assertEquals(1000L, task.getRetryDelayMs())
    }

    @Test
    fun `getRetryDelayMs returns 2000 for retryCount 1`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )
        task.retryCount = 1

        assertEquals(2000L, task.getRetryDelayMs())
    }

    @Test
    fun `getRetryDelayMs returns 4000 for retryCount 2`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )
        task.retryCount = 2

        assertEquals(4000L, task.getRetryDelayMs())
    }

    @Test
    fun `getRetryDelayMs returns 8000 for retryCount 3`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )
        task.retryCount = 3

        assertEquals(8000L, task.getRetryDelayMs())
    }

    @Test
    fun `getRetryDelayMs coerces to max 60000 for high retryCount`() {
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )
        task.retryCount = 20

        assertEquals(60000L, task.getRetryDelayMs())
    }

    @Test
    fun `getRetryDelayMs coerces to 60000 at boundary retryCount 6`() {
        // 1 shl 6 = 64, 64 * 1000 = 64000 > 60000, should coerce to 60000
        val task = UploadTask(
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 1024L
        )
        task.retryCount = 6

        assertEquals(60000L, task.getRetryDelayMs())
    }

    // -----------------------------------------------------------------------
    // 6. toJson() and fromJson() round-trip
    // -----------------------------------------------------------------------

    @Test
    fun `toJson and fromJson round-trip preserves all fields`() {
        val original = UploadTask(
            id = "test-id-12345",
            localPath = "/storage/emulated/0/LensDaemon/video_001.mp4",
            remotePath = "camera1/2024/video_001.mp4",
            destination = UploadDestination.S3,
            status = UploadStatus.UPLOADING,
            fileSize = 104857600L,
            bytesUploaded = 52428800L,
            progress = 50,
            retryCount = 2,
            maxRetries = 5,
            lastError = "Connection timeout",
            createdAt = 1700000000000L,
            lastAttemptAt = 1700000100000L,
            completedAt = 0L,
            contentType = "video/mp4",
            deleteAfterUpload = true
        )

        val json = original.toJson()
        val restored = UploadTask.fromJson(json)

        assertEquals(original.id, restored.id)
        assertEquals(original.localPath, restored.localPath)
        assertEquals(original.remotePath, restored.remotePath)
        assertEquals(original.destination, restored.destination)
        assertEquals(original.status, restored.status)
        assertEquals(original.fileSize, restored.fileSize)
        assertEquals(original.bytesUploaded, restored.bytesUploaded)
        assertEquals(original.progress, restored.progress)
        assertEquals(original.retryCount, restored.retryCount)
        assertEquals(original.maxRetries, restored.maxRetries)
        assertEquals(original.lastError, restored.lastError)
        assertEquals(original.createdAt, restored.createdAt)
        assertEquals(original.lastAttemptAt, restored.lastAttemptAt)
        assertEquals(original.completedAt, restored.completedAt)
        assertEquals(original.contentType, restored.contentType)
        assertEquals(original.deleteAfterUpload, restored.deleteAfterUpload)
    }

    @Test
    fun `toJson and fromJson round-trip with SMB destination`() {
        val original = UploadTask(
            id = "smb-task-001",
            localPath = "/data/recordings/clip.mp4",
            remotePath = "share/backups/clip.mp4",
            destination = UploadDestination.SMB,
            fileSize = 2048L,
            createdAt = 1700000000000L
        )

        val json = original.toJson()
        val restored = UploadTask.fromJson(json)

        assertEquals(UploadDestination.SMB, restored.destination)
        assertEquals(original.id, restored.id)
        assertEquals(original.localPath, restored.localPath)
        assertEquals(original.remotePath, restored.remotePath)
    }

    @Test
    fun `fromJson uses defaults for optional fields when absent`() {
        // Build a minimal JSON with only required fields
        val json = JSONObject().apply {
            put("id", "minimal-task")
            put("localPath", "/tmp/video.mp4")
            put("remotePath", "uploads/video.mp4")
            put("destination", "S3")
            put("status", "PENDING")
            put("fileSize", 1024L)
            put("createdAt", 1700000000000L)
        }

        val task = UploadTask.fromJson(json)

        assertEquals("minimal-task", task.id)
        assertEquals("/tmp/video.mp4", task.localPath)
        assertEquals("uploads/video.mp4", task.remotePath)
        assertEquals(UploadDestination.S3, task.destination)
        assertEquals(UploadStatus.PENDING, task.status)
        assertEquals(1024L, task.fileSize)
        assertEquals(1700000000000L, task.createdAt)
        // optLong defaults
        assertEquals(0L, task.bytesUploaded)
        assertEquals(0L, task.lastAttemptAt)
        assertEquals(0L, task.completedAt)
        // optInt defaults
        assertEquals(0, task.progress)
        assertEquals(0, task.retryCount)
        assertEquals(3, task.maxRetries)
        // optString defaults
        assertNull(task.lastError)
        assertEquals("video/mp4", task.contentType)
        // optBoolean defaults
        assertFalse(task.deleteAfterUpload)
    }

    @Test
    fun `toJson produces valid JSON with all expected keys`() {
        val task = UploadTask(
            id = "json-key-test",
            localPath = "/tmp/video.mp4",
            remotePath = "uploads/video.mp4",
            destination = UploadDestination.S3,
            fileSize = 512L,
            createdAt = 1700000000000L
        )

        val json = task.toJson()

        assertTrue(json.has("id"))
        assertTrue(json.has("localPath"))
        assertTrue(json.has("remotePath"))
        assertTrue(json.has("destination"))
        assertTrue(json.has("status"))
        assertTrue(json.has("fileSize"))
        assertTrue(json.has("bytesUploaded"))
        assertTrue(json.has("progress"))
        assertTrue(json.has("retryCount"))
        assertTrue(json.has("maxRetries"))
        assertTrue(json.has("lastError"))
        assertTrue(json.has("createdAt"))
        assertTrue(json.has("lastAttemptAt"))
        assertTrue(json.has("completedAt"))
        assertTrue(json.has("contentType"))
        assertTrue(json.has("deleteAfterUpload"))
    }

    @Test
    fun `fromJson and toJson round-trip through string serialization`() {
        val original = UploadTask(
            id = "string-round-trip",
            localPath = "/tmp/recording.mp4",
            remotePath = "backups/recording.mp4",
            destination = UploadDestination.SMB,
            fileSize = 999999L,
            createdAt = 1700000000000L
        )
        original.status = UploadStatus.FAILED
        original.retryCount = 2
        original.lastError = "Network unreachable"
        original.bytesUploaded = 500000L
        original.progress = 50

        // Serialize to string and back
        val jsonString = original.toJson().toString()
        val restored = UploadTask.fromJson(JSONObject(jsonString))

        assertEquals(original.id, restored.id)
        assertEquals(original.status, restored.status)
        assertEquals(original.retryCount, restored.retryCount)
        assertEquals(original.lastError, restored.lastError)
        assertEquals(original.bytesUploaded, restored.bytesUploaded)
        assertEquals(original.progress, restored.progress)
    }

    // -----------------------------------------------------------------------
    // 7. UploadDestination enum
    // -----------------------------------------------------------------------

    @Test
    fun `UploadDestination has S3 value`() {
        val s3 = UploadDestination.valueOf("S3")
        assertEquals(UploadDestination.S3, s3)
    }

    @Test
    fun `UploadDestination has SMB value`() {
        val smb = UploadDestination.valueOf("SMB")
        assertEquals(UploadDestination.SMB, smb)
    }

    @Test
    fun `UploadDestination has exactly 2 values`() {
        val values = UploadDestination.values()
        assertEquals(2, values.size)
    }

    // -----------------------------------------------------------------------
    // 8. UploadStatus enum
    // -----------------------------------------------------------------------

    @Test
    fun `UploadStatus has PENDING value`() {
        assertEquals(UploadStatus.PENDING, UploadStatus.valueOf("PENDING"))
    }

    @Test
    fun `UploadStatus has UPLOADING value`() {
        assertEquals(UploadStatus.UPLOADING, UploadStatus.valueOf("UPLOADING"))
    }

    @Test
    fun `UploadStatus has COMPLETED value`() {
        assertEquals(UploadStatus.COMPLETED, UploadStatus.valueOf("COMPLETED"))
    }

    @Test
    fun `UploadStatus has FAILED value`() {
        assertEquals(UploadStatus.FAILED, UploadStatus.valueOf("FAILED"))
    }

    @Test
    fun `UploadStatus has CANCELLED value`() {
        assertEquals(UploadStatus.CANCELLED, UploadStatus.valueOf("CANCELLED"))
    }

    @Test
    fun `UploadStatus has exactly 5 values`() {
        val values = UploadStatus.values()
        assertEquals(5, values.size)
    }

    // -----------------------------------------------------------------------
    // 9. UploadQueueStats
    // -----------------------------------------------------------------------

    @Test
    fun `totalCount sums all count fields`() {
        val stats = UploadQueueStats(
            pendingCount = 3,
            uploadingCount = 1,
            completedCount = 10,
            failedCount = 2
        )

        assertEquals(16, stats.totalCount)
    }

    @Test
    fun `totalCount returns 0 when all counts are 0`() {
        val stats = UploadQueueStats()

        assertEquals(0, stats.totalCount)
    }

    @Test
    fun `overallProgress computes uploaded percentage`() {
        val stats = UploadQueueStats(
            totalBytes = 1000L,
            uploadedBytes = 500L
        )

        assertEquals(50, stats.overallProgress)
    }

    @Test
    fun `overallProgress returns 100 when all bytes uploaded`() {
        val stats = UploadQueueStats(
            totalBytes = 2048L,
            uploadedBytes = 2048L
        )

        assertEquals(100, stats.overallProgress)
    }

    @Test
    fun `overallProgress returns 0 when totalBytes is 0`() {
        val stats = UploadQueueStats(
            totalBytes = 0L,
            uploadedBytes = 0L
        )

        assertEquals(0, stats.overallProgress)
    }

    @Test
    fun `overallProgress handles partial progress correctly`() {
        val stats = UploadQueueStats(
            totalBytes = 300L,
            uploadedBytes = 100L
        )

        assertEquals(33, stats.overallProgress)
    }

    @Test
    fun `isActive returns true when uploadingCount greater than 0`() {
        val stats = UploadQueueStats(uploadingCount = 1)

        assertTrue("isActive should be true when uploadingCount > 0", stats.isActive)
    }

    @Test
    fun `isActive returns false when uploadingCount is 0`() {
        val stats = UploadQueueStats(uploadingCount = 0)

        assertFalse("isActive should be false when uploadingCount is 0", stats.isActive)
    }

    @Test
    fun `isActive returns true when multiple uploads in progress`() {
        val stats = UploadQueueStats(uploadingCount = 5)

        assertTrue("isActive should be true when uploadingCount > 0", stats.isActive)
    }
}
