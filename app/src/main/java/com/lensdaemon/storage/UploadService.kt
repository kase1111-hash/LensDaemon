package com.lensdaemon.storage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lensdaemon.MainActivity
import com.lensdaemon.R
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File

/**
 * Upload service state
 */
enum class UploadServiceState {
    IDLE,
    UPLOADING,
    PAUSED,
    ERROR
}

/**
 * Upload service statistics
 */
data class UploadServiceStats(
    val state: UploadServiceState = UploadServiceState.IDLE,
    val queueStats: UploadQueueStats = UploadQueueStats(),
    val currentFileName: String? = null,
    val currentProgress: Int = 0,
    val isS3Configured: Boolean = false,
    val isSmbConfigured: Boolean = false
)

/**
 * Background upload service
 *
 * Manages file uploads to network storage backends:
 * - S3-compatible storage (AWS, Backblaze B2, MinIO, R2)
 * - SMB/CIFS network shares
 *
 * Features:
 * - Foreground service for reliable uploads
 * - Upload queue with persistence
 * - Automatic retry with exponential backoff
 * - Progress notifications
 * - Network state awareness
 * - Concurrent upload support
 */
class UploadService : Service(), UploadEventListener {

    companion object {
        private const val TAG = "UploadService"
        private const val NOTIFICATION_ID = 3001
        private const val CHANNEL_ID = "upload_service_channel"

        // Intent actions
        const val ACTION_START_UPLOADS = "com.lensdaemon.action.START_UPLOADS"
        const val ACTION_STOP_UPLOADS = "com.lensdaemon.action.STOP_UPLOADS"
        const val ACTION_ENQUEUE_FILE = "com.lensdaemon.action.ENQUEUE_FILE"

        // Intent extras
        const val EXTRA_FILE_PATH = "file_path"
        const val EXTRA_REMOTE_PATH = "remote_path"
        const val EXTRA_DESTINATION = "destination"
        const val EXTRA_DELETE_AFTER = "delete_after"

        /**
         * Create intent to start upload processing
         */
        fun createStartIntent(context: Context): Intent {
            return Intent(context, UploadService::class.java).apply {
                action = ACTION_START_UPLOADS
            }
        }

        /**
         * Create intent to stop upload processing
         */
        fun createStopIntent(context: Context): Intent {
            return Intent(context, UploadService::class.java).apply {
                action = ACTION_STOP_UPLOADS
            }
        }

        /**
         * Create intent to enqueue a file
         */
        fun createEnqueueIntent(
            context: Context,
            filePath: String,
            remotePath: String,
            destination: UploadDestination,
            deleteAfter: Boolean = false
        ): Intent {
            return Intent(context, UploadService::class.java).apply {
                action = ACTION_ENQUEUE_FILE
                putExtra(EXTRA_FILE_PATH, filePath)
                putExtra(EXTRA_REMOTE_PATH, remotePath)
                putExtra(EXTRA_DESTINATION, destination.name)
                putExtra(EXTRA_DELETE_AFTER, deleteAfter)
            }
        }
    }

    // Service binder
    private val binder = UploadBinder()

    inner class UploadBinder : Binder() {
        fun getService(): UploadService = this@UploadService
    }

    // Coroutine scope
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Components
    private lateinit var credentialStore: CredentialStore
    private lateinit var uploadQueue: UploadQueue

    private var s3Client: S3Client? = null
    private var smbClient: SmbClient? = null

    // State
    private val _state = MutableStateFlow(UploadServiceState.IDLE)
    val state: StateFlow<UploadServiceState> = _state.asStateFlow()

    private val _stats = MutableStateFlow(UploadServiceStats())
    val stats: StateFlow<UploadServiceStats> = _stats.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        Timber.tag(TAG).i("UploadService created")

        createNotificationChannel()

        // Initialize components
        credentialStore = CredentialStore(applicationContext)
        uploadQueue = UploadQueue(applicationContext)
        uploadQueue.addListener(this)

        // Set up upload handler
        uploadQueue.uploadHandler = { task, progressCallback ->
            handleUpload(task, progressCallback)
        }

        // Initialize clients if credentials available
        initializeClients()

        updateStats()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag(TAG).d("onStartCommand: \${intent?.action}")

        when (intent?.action) {
            ACTION_START_UPLOADS -> {
                startUploads()
            }
            ACTION_STOP_UPLOADS -> {
                stopUploads()
            }
            ACTION_ENQUEUE_FILE -> {
                val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
                val remotePath = intent.getStringExtra(EXTRA_REMOTE_PATH)
                val destinationStr = intent.getStringExtra(EXTRA_DESTINATION)
                val deleteAfter = intent.getBooleanExtra(EXTRA_DELETE_AFTER, false)

                if (filePath != null && remotePath != null && destinationStr != null) {
                    val destination = UploadDestination.valueOf(destinationStr)
                    enqueueFile(filePath, remotePath, destination, deleteAfter)
                }
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(TAG).i("UploadService destroyed")

        uploadQueue.stopProcessing()
        uploadQueue.removeListener(this)
        uploadQueue.release()

        smbClient?.disconnect()

        serviceScope.cancel()
    }

    /**
     * Initialize storage clients from stored credentials
     */
    private fun initializeClients() {
        // S3 client
        val s3Creds = credentialStore.getS3Credentials()
        if (s3Creds != null && s3Creds.isValid()) {
            s3Client = S3Client(s3Creds)
            Timber.tag(TAG).d("S3 client initialized: \${s3Creds.backend}")
        }

        // SMB client
        val smbCreds = credentialStore.getSmbCredentials()
        if (smbCreds != null && smbCreds.isValid()) {
            smbClient = SmbClient(smbCreds)
            Timber.tag(TAG).d("SMB client initialized: \${smbCreds.server}")
        }

        updateStats()
    }

    /**
     * Start processing uploads
     */
    fun startUploads() {
        if (_state.value == UploadServiceState.UPLOADING) {
            Timber.tag(TAG).d("Already uploading")
            return
        }

        startForeground(NOTIFICATION_ID, createNotification("Starting uploads..."))

        _state.value = UploadServiceState.UPLOADING
        uploadQueue.startProcessing()

        updateStats()
        Timber.tag(TAG).i("Upload processing started")
    }

    /**
     * Stop processing uploads
     */
    fun stopUploads() {
        uploadQueue.stopProcessing()
        _state.value = UploadServiceState.PAUSED

        updateNotification("Uploads paused")
        updateStats()
        Timber.tag(TAG).i("Upload processing stopped")
    }

    /**
     * Enqueue a file for upload
     */
    fun enqueueFile(
        filePath: String,
        remotePath: String,
        destination: UploadDestination,
        deleteAfterUpload: Boolean = false
    ): UploadTask? {
        val file = File(filePath)
        if (!file.exists()) {
            Timber.tag(TAG).w("File not found: \$filePath")
            return null
        }

        val task = uploadQueue.enqueue(file, remotePath, destination, deleteAfterUpload)

        if (task != null) {
            // Start uploads if not already running
            if (_state.value != UploadServiceState.UPLOADING) {
                startUploads()
            }
        }

        updateStats()
        return task
    }

    /**
     * Enqueue all recordings for upload
     */
    fun enqueueRecordings(
        recordings: List<RecordingFile>,
        destination: UploadDestination,
        deleteAfterUpload: Boolean = false
    ): Int {
        var count = 0
        recordings.forEach { recording ->
            val remotePath = recording.name
            if (enqueueFile(recording.path, remotePath, destination, deleteAfterUpload) != null) {
                count++
            }
        }

        Timber.tag(TAG).i("Enqueued \$count recordings for upload")
        return count
    }

    /**
     * Configure S3 credentials
     */
    fun configureS3(credentials: S3Credentials) {
        credentialStore.storeS3Credentials(credentials)
        s3Client = S3Client(credentials)
        updateStats()
        Timber.tag(TAG).i("S3 configured: \${credentials.backend} - \${credentials.bucket}")
    }

    /**
     * Test S3 connection
     */
    suspend fun testS3Connection(): Result<Boolean> {
        val client = s3Client ?: return Result.failure(Exception("S3 not configured"))
        return client.testConnection()
    }

    /**
     * Configure SMB credentials
     */
    fun configureSmb(credentials: SmbCredentials) {
        credentialStore.storeSmbCredentials(credentials)
        smbClient?.disconnect()
        smbClient = SmbClient(credentials)
        updateStats()
        Timber.tag(TAG).i("SMB configured: \${credentials.server}/\${credentials.share}")
    }

    /**
     * Test SMB connection
     */
    suspend fun testSmbConnection(): Result<Boolean> {
        val client = smbClient ?: return Result.failure(Exception("SMB not configured"))
        return client.testConnection()
    }

    /**
     * Clear S3 credentials
     */
    fun clearS3Credentials() {
        credentialStore.deleteS3Credentials()
        s3Client = null
        updateStats()
    }

    /**
     * Clear SMB credentials
     */
    fun clearSmbCredentials() {
        credentialStore.deleteSmbCredentials()
        smbClient?.disconnect()
        smbClient = null
        updateStats()
    }

    /**
     * Get pending upload tasks
     */
    fun getPendingTasks(): List<UploadTask> = uploadQueue.getPendingTasks()

    /**
     * Get completed upload tasks
     */
    fun getCompletedTasks(): List<UploadTask> = uploadQueue.getCompletedTasks()

    /**
     * Cancel a specific task
     */
    fun cancelTask(taskId: String): Boolean {
        val result = uploadQueue.cancelTask(taskId)
        updateStats()
        return result
    }

    /**
     * Clear all pending tasks
     */
    fun clearPending() {
        uploadQueue.clearPending()
        updateStats()
    }

    /**
     * Retry all failed tasks
     */
    fun retryFailed() {
        uploadQueue.retryFailed()
        updateStats()
    }

    /**
     * Get upload queue statistics
     */
    fun getQueueStats(): UploadQueueStats = uploadQueue.getStats()

    /**
     * Check if S3 is configured
     */
    fun isS3Configured(): Boolean = credentialStore.hasS3Credentials()

    /**
     * Check if SMB is configured
     */
    fun isSmbConfigured(): Boolean = credentialStore.hasSmbCredentials()

    /**
     * Get S3 credentials (for display - sensitive fields masked)
     */
    fun getS3CredentialsSafe(): S3Credentials? {
        val creds = credentialStore.getS3Credentials() ?: return null
        return creds.copy(
            accessKeyId = maskString(creds.accessKeyId),
            secretAccessKey = "********"
        )
    }

    /**
     * Get SMB credentials (for display - sensitive fields masked)
     */
    fun getSmbCredentialsSafe(): SmbCredentials? {
        val creds = credentialStore.getSmbCredentials() ?: return null
        return creds.copy(
            password = "********"
        )
    }

    /**
     * Handle upload for a task
     */
    private suspend fun handleUpload(
        task: UploadTask,
        progressCallback: ProgressCallback
    ): Result<Unit> {
        return when (task.destination) {
            UploadDestination.S3 -> handleS3Upload(task, progressCallback)
            UploadDestination.SMB -> handleSmbUpload(task, progressCallback)
        }
    }

    /**
     * Handle S3 upload
     */
    private suspend fun handleS3Upload(
        task: UploadTask,
        progressCallback: ProgressCallback
    ): Result<Unit> {
        val client = s3Client ?: return Result.failure(Exception("S3 not configured"))
        val file = File(task.localPath)

        return client.uploadFile(
            localFile = file,
            remotePath = task.remotePath,
            contentType = task.contentType,
            progressCallback = progressCallback
        ).map { Unit }
    }

    /**
     * Handle SMB upload
     */
    private suspend fun handleSmbUpload(
        task: UploadTask,
        progressCallback: ProgressCallback
    ): Result<Unit> {
        val client = smbClient ?: return Result.failure(Exception("SMB not configured"))
        val file = File(task.localPath)

        return client.uploadFile(
            localFile = file,
            remotePath = task.remotePath,
            progressCallback = progressCallback
        ).map { Unit }
    }

    // ==================== UploadEventListener ====================

    override fun onUploadStarted(task: UploadTask) {
        updateNotification("Uploading: \${task.fileName}")
        updateStats()
    }

    override fun onUploadProgress(task: UploadTask, bytesUploaded: Long, totalBytes: Long) {
        val progress = if (totalBytes > 0) ((bytesUploaded * 100) / totalBytes).toInt() else 0
        updateNotification("Uploading: \${task.fileName} (\$progress%)")
        updateStats()
    }

    override fun onUploadCompleted(task: UploadTask) {
        updateNotification("Upload completed: \${task.fileName}")
        updateStats()
    }

    override fun onUploadFailed(task: UploadTask, error: String) {
        Timber.tag(TAG).e("Upload failed: \${task.fileName} - \$error")
        updateStats()
    }

    override fun onUploadCancelled(task: UploadTask) {
        updateStats()
    }

    override fun onQueueEmpty() {
        updateNotification("All uploads completed")
        _state.value = UploadServiceState.IDLE
        updateStats()
    }

    // ==================== Notification ====================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Upload Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "File upload progress"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stats = uploadQueue.getStats()
        val contentText = if (stats.totalCount > 0) {
            "\$status (\${stats.completedCount}/\${stats.totalCount})"
        } else {
            status
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LensDaemon Uploads")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(100, stats.currentProgress, stats.currentProgress == 0)
            .build()
    }

    private fun updateNotification(status: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, createNotification(status))
    }

    private fun updateStats() {
        val queueStats = uploadQueue.getStats()
        val currentTask = uploadQueue.getCurrentTask()

        _stats.value = UploadServiceStats(
            state = _state.value,
            queueStats = queueStats,
            currentFileName = currentTask?.fileName,
            currentProgress = currentTask?.progress ?: 0,
            isS3Configured = credentialStore.hasS3Credentials(),
            isSmbConfigured = credentialStore.hasSmbCredentials()
        )
    }

    private fun maskString(input: String): String {
        return if (input.length > 4) {
            input.take(4) + "****"
        } else {
            "****"
        }
    }
}
