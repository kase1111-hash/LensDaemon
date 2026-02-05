package com.lensdaemon.storage

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

class RetentionPolicyTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var recordingsDir: File

    @Before
    fun setUp() {
        recordingsDir = tempFolder.newFolder("recordings")
    }

    // ==================== RetentionConfig Tests ====================

    @Test
    fun `config presets have correct types`() {
        assertEquals(RetentionType.MAX_AGE_AND_SIZE, RetentionConfig.DEFAULT.type)
        assertEquals(RetentionType.MAX_AGE, RetentionConfig.LAST_24_HOURS.type)
        assertEquals(RetentionType.MAX_AGE, RetentionConfig.LAST_7_DAYS.type)
        assertEquals(RetentionType.MAX_AGE, RetentionConfig.LAST_30_DAYS.type)
        assertEquals(RetentionType.MAX_SIZE, RetentionConfig.MAX_5GB.type)
        assertEquals(RetentionType.MAX_SIZE, RetentionConfig.MAX_10GB.type)
        assertEquals(RetentionType.KEEP_ALL, RetentionConfig.KEEP_ALL.type)
    }

    @Test
    fun `config computed properties are correct`() {
        val config = RetentionConfig(
            maxAgeMs = TimeUnit.DAYS.toMillis(7),
            maxSizeBytes = 10L * 1024 * 1024 * 1024
        )
        assertEquals(168L, config.maxAgeHours)
        assertEquals(7L, config.maxAgeDays)
        assertEquals(10240L, config.maxSizeMB)
        assertEquals(10.0f, config.maxSizeGB, 0.01f)
    }

    @Test
    fun `24 hour preset has correct max age`() {
        assertEquals(24L, RetentionConfig.LAST_24_HOURS.maxAgeHours)
    }

    @Test
    fun `30 day preset has correct max age`() {
        assertEquals(30L, RetentionConfig.LAST_30_DAYS.maxAgeDays)
    }

    @Test
    fun `5GB preset has correct size`() {
        assertEquals(5.0f, RetentionConfig.MAX_5GB.maxSizeGB, 0.01f)
    }

    // ==================== KEEP_ALL Policy Tests ====================

    @Test
    fun `keep all policy does not delete files`() {
        createTestFile("video1.mp4", 1024)
        createTestFile("video2.mp4", 1024)

        val policy = RetentionPolicy(RetentionConfig.KEEP_ALL)
        val result = policy.enforce(recordingsDir)

        assertEquals(0, result.filesDeleted)
        assertEquals(0L, result.bytesFreed)
        assertTrue(File(recordingsDir, "video1.mp4").exists())
        assertTrue(File(recordingsDir, "video2.mp4").exists())
    }

    @Test
    fun `keep all preview returns empty list`() {
        createTestFile("video1.mp4", 1024)

        val policy = RetentionPolicy(RetentionConfig.KEEP_ALL)
        val toDelete = policy.preview(recordingsDir)

        assertTrue(toDelete.isEmpty())
    }

    // ==================== MAX_AGE Policy Tests ====================

    @Test
    fun `max age deletes old files`() {
        val oldFile = createTestFile("old.mp4", 1024)
        // Set modification time to 2 days ago
        oldFile.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(2))

        val recentFile = createTestFile("recent.mp4", 1024)
        recentFile.setLastModified(System.currentTimeMillis())

        val config = RetentionConfig(
            type = RetentionType.MAX_AGE,
            maxAgeMs = TimeUnit.DAYS.toMillis(1)
        )
        val policy = RetentionPolicy(config)
        val result = policy.enforce(recordingsDir)

        assertEquals(1, result.filesDeleted)
        assertFalse(oldFile.exists())
        assertTrue(recentFile.exists())
    }

    @Test
    fun `max age keeps all files within age limit`() {
        val file1 = createTestFile("video1.mp4", 1024)
        file1.setLastModified(System.currentTimeMillis())

        val file2 = createTestFile("video2.mp4", 1024)
        file2.setLastModified(System.currentTimeMillis())

        val config = RetentionConfig(
            type = RetentionType.MAX_AGE,
            maxAgeMs = TimeUnit.DAYS.toMillis(7)
        )
        val policy = RetentionPolicy(config)
        val result = policy.enforce(recordingsDir)

        assertEquals(0, result.filesDeleted)
        assertTrue(file1.exists())
        assertTrue(file2.exists())
    }

    @Test
    fun `max age preview shows files that would be deleted`() {
        val oldFile = createTestFile("old.mp4", 1024)
        oldFile.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10))

        val recentFile = createTestFile("recent.mp4", 1024)
        recentFile.setLastModified(System.currentTimeMillis())

        val config = RetentionConfig(
            type = RetentionType.MAX_AGE,
            maxAgeMs = TimeUnit.DAYS.toMillis(7)
        )
        val policy = RetentionPolicy(config)
        val toDelete = policy.preview(recordingsDir)

        assertEquals(1, toDelete.size)
        assertEquals(oldFile.absolutePath, toDelete[0].absolutePath)
        // Files should still exist after preview
        assertTrue(oldFile.exists())
        assertTrue(recentFile.exists())
    }

    // ==================== MAX_SIZE Policy Tests ====================

    @Test
    fun `max size deletes oldest files when over limit`() {
        // Create files totaling 3000 bytes with a limit of 2000
        val file1 = createTestFile("oldest.mp4", 1000)
        file1.setLastModified(System.currentTimeMillis() - 3000)

        val file2 = createTestFile("middle.mp4", 1000)
        file2.setLastModified(System.currentTimeMillis() - 2000)

        val file3 = createTestFile("newest.mp4", 1000)
        file3.setLastModified(System.currentTimeMillis() - 1000)

        val config = RetentionConfig(
            type = RetentionType.MAX_SIZE,
            maxSizeBytes = 2000L
        )
        val policy = RetentionPolicy(config)
        val result = policy.enforce(recordingsDir)

        assertEquals(1, result.filesDeleted)
        assertFalse(file1.exists())  // oldest should be deleted
        assertTrue(file2.exists())
        assertTrue(file3.exists())
    }

    @Test
    fun `max size keeps all files when under limit`() {
        createTestFile("video1.mp4", 500)
        createTestFile("video2.mp4", 500)

        val config = RetentionConfig(
            type = RetentionType.MAX_SIZE,
            maxSizeBytes = 2000L
        )
        val policy = RetentionPolicy(config)
        val result = policy.enforce(recordingsDir)

        assertEquals(0, result.filesDeleted)
    }

    // ==================== MAX_AGE_AND_SIZE Policy Tests ====================

    @Test
    fun `combined policy deletes by age and then by size`() {
        // Old file (should be deleted by age)
        val oldFile = createTestFile("old.mp4", 500)
        oldFile.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10))

        // Recent files exceeding size limit
        val file2 = createTestFile("file2.mp4", 800)
        file2.setLastModified(System.currentTimeMillis() - 2000)

        val file3 = createTestFile("file3.mp4", 800)
        file3.setLastModified(System.currentTimeMillis() - 1000)

        val config = RetentionConfig(
            type = RetentionType.MAX_AGE_AND_SIZE,
            maxAgeMs = TimeUnit.DAYS.toMillis(7),
            maxSizeBytes = 1000L
        )
        val policy = RetentionPolicy(config)
        val result = policy.enforce(recordingsDir)

        // Old file deleted by age, file2 deleted by size
        assertFalse(oldFile.exists())
        assertTrue(result.filesDeleted >= 2)
    }

    // ==================== File Pattern Matching Tests ====================

    @Test
    fun `only mp4 files are affected by default`() {
        val mp4File = createTestFile("video.mp4", 1024)
        mp4File.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10))

        val txtFile = createTestFile("notes.txt", 1024)
        txtFile.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10))

        val config = RetentionConfig(
            type = RetentionType.MAX_AGE,
            maxAgeMs = TimeUnit.DAYS.toMillis(1)
        )
        val policy = RetentionPolicy(config)
        val result = policy.enforce(recordingsDir)

        assertEquals(1, result.filesDeleted)
        assertFalse(mp4File.exists())
        assertTrue(txtFile.exists())  // txt not matched by *.mp4 pattern
    }

    @Test
    fun `custom include patterns are respected`() {
        val mp4File = createTestFile("video.mp4", 1024)
        mp4File.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10))

        val logFile = createTestFile("app.log", 1024)
        logFile.setLastModified(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(10))

        val config = RetentionConfig(
            type = RetentionType.MAX_AGE,
            maxAgeMs = TimeUnit.DAYS.toMillis(1),
            includePatterns = listOf("*.mp4", "*.log")
        )
        val policy = RetentionPolicy(config)
        val result = policy.enforce(recordingsDir)

        assertEquals(2, result.filesDeleted)
        assertFalse(mp4File.exists())
        assertFalse(logFile.exists())
    }

    // ==================== Empty Directory Cleanup ====================

    @Test
    fun `empty directories are deleted when configured`() {
        val subDir = File(recordingsDir, "subdir")
        subDir.mkdirs()

        val config = RetentionConfig(
            type = RetentionType.MAX_AGE,
            maxAgeMs = TimeUnit.DAYS.toMillis(1),
            deleteEmptyDirs = true
        )
        val policy = RetentionPolicy(config)
        val result = policy.enforce(recordingsDir)

        assertEquals(1, result.dirsDeleted)
        assertFalse(subDir.exists())
    }

    @Test
    fun `empty directories are kept when not configured`() {
        val subDir = File(recordingsDir, "subdir")
        subDir.mkdirs()

        val config = RetentionConfig(
            type = RetentionType.MAX_AGE,
            maxAgeMs = TimeUnit.DAYS.toMillis(1),
            deleteEmptyDirs = false
        )
        val policy = RetentionPolicy(config)
        val result = policy.enforce(recordingsDir)

        assertEquals(0, result.dirsDeleted)
        assertTrue(subDir.exists())
    }

    // ==================== Edge Cases ====================

    @Test
    fun `enforce on nonexistent directory returns empty result`() {
        val nonExistent = File(tempFolder.root, "does_not_exist")
        val policy = RetentionPolicy(RetentionConfig.DEFAULT)
        val result = policy.enforce(nonExistent)

        assertEquals(0, result.filesDeleted)
        assertEquals(0L, result.bytesFreed)
    }

    @Test
    fun `enforce on empty directory returns empty result`() {
        val policy = RetentionPolicy(RetentionConfig.DEFAULT)
        val result = policy.enforce(recordingsDir)

        assertEquals(0, result.filesDeleted)
        assertEquals(0L, result.bytesFreed)
    }

    @Test
    fun `preview on nonexistent directory returns empty list`() {
        val nonExistent = File(tempFolder.root, "does_not_exist")
        val policy = RetentionPolicy(RetentionConfig.DEFAULT)
        val toDelete = policy.preview(nonExistent)

        assertTrue(toDelete.isEmpty())
    }

    @Test
    fun `storage stats on nonexistent directory returns defaults`() {
        val nonExistent = File(tempFolder.root, "does_not_exist")
        val policy = RetentionPolicy(RetentionConfig.DEFAULT)
        val stats = policy.getStorageStats(nonExistent)

        assertEquals(0, stats.fileCount)
        assertEquals(0L, stats.totalSizeBytes)
    }

    @Test
    fun `storage stats counts matching files correctly`() {
        createTestFile("v1.mp4", 1000)
        createTestFile("v2.mp4", 2000)
        createTestFile("notes.txt", 500)

        val policy = RetentionPolicy(RetentionConfig.DEFAULT)
        val stats = policy.getStorageStats(recordingsDir)

        assertEquals(2, stats.fileCount)  // only mp4 files
        assertEquals(3000L, stats.totalSizeBytes)
    }

    // ==================== RetentionResult Tests ====================

    @Test
    fun `retention result computed properties`() {
        val result = RetentionResult(
            filesDeleted = 5,
            bytesFreed = 10L * 1024 * 1024,  // 10 MB
            dirsDeleted = 1,
            failedFiles = emptyList(),
            durationMs = 100
        )

        assertEquals(10.0f, result.bytesFreedMB, 0.01f)
        assertTrue(result.hasDeleted)
        assertTrue(result.allSucceeded)
    }

    @Test
    fun `retention result reports failures correctly`() {
        val result = RetentionResult(
            filesDeleted = 0,
            bytesFreed = 0,
            dirsDeleted = 0,
            failedFiles = listOf("/some/file.mp4"),
            durationMs = 50
        )

        assertFalse(result.hasDeleted)
        assertFalse(result.allSucceeded)
    }

    // ==================== RetentionPolicyBuilder Tests ====================

    @Test
    fun `builder creates correct policy`() {
        val policy = RetentionPolicyBuilder()
            .maxAge()
            .maxAgeDays(14)
            .includePatterns("*.mp4", "*.mkv")
            .deleteEmptyDirs(false)
            .build()

        // Verify by running against empty dir (just checks it builds without error)
        val result = policy.enforce(recordingsDir)
        assertEquals(0, result.filesDeleted)
    }

    // ==================== Helper Methods ====================

    private fun createTestFile(name: String, sizeBytes: Int): File {
        val file = File(recordingsDir, name)
        file.writeBytes(ByteArray(sizeBytes))
        return file
    }
}
