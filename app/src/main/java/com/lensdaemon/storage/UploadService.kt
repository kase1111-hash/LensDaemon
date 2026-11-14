package com.lensdaemon.storage

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lensdaemon.LensDaemonApp
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
 * - Foreground service for reliable uploads (where Android allows starting one)
 * - Upload queue with persistence; queued uploads resume when the service starts
 * - Automatic retry: 3 attempts, 2 s and 4 s apart, then kept as failed
 * - Progress notifications
 * - Failed uploads retried when the network, or its internet access, comes back
 *   (kiosk networkRecovery.autoReconnect)
 * - One upload at a time
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

    /** The upload percentage last shown, so the notification changes only when it moves. */
    @Volatile
    private var lastNotifiedPercent = -1

    /** True from losing the network (or starting without one) until it is back. */
    @Volatile
    private var networkLost = false

    /** Whether the default network last reported working internet access (validated). */
    @Volatile
    private var networkValidated = false

    /**
     * Retries the failed uploads when the network comes back, while kiosk
     * network recovery (networkRecovery.autoReconnect) is on: when a network
     * is up again after a loss, which is enough for a share on the local
     * network (that network may have no internet access at all), and again
     * whenever the network gains working internet access. That often comes
     * later: the router is back before its internet link, or internet access
     * returns while the Wi-Fi stayed up. Called on a system thread.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            networkLost = true
            networkValidated = false
        }

        override fun onAvailable(network: Network) {
            if (!networkLost) return
            networkLost = false
            retryFailedOnReconnect("Network is back")
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (validated && !networkValidated) retryFailedOnReconnect("Internet access is back")
            networkValidated = validated
        }
    }

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

        watchNetwork()

        // Uploads still queued from before (an app restart, a reboot) carry on
        if (uploadQueue.getPendingTasks().isNotEmpty()) {
            startUploads()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag(TAG).d("onStartCommand: ${intent?.action}")

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

        try {
            getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback)
        } catch (e: IllegalArgumentException) {
            Timber.tag(TAG).w(e, "The network callback was not registered")
        }

        uploadQueue.stopProcessing()
        uploadQueue.removeListener(this)
        uploadQueue.release()

        smbClient?.disconnect()

        serviceScope.cancel()
    }

    /**
     * Watch the default network, so failed uploads are retried when it comes
     * back. Starts from the network's state now, so the report of the current
     * network that follows registration retries nothing.
     */
    private fun watchNetwork() {
        try {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            val active = connectivity.activeNetwork
            networkLost = active == null
            networkValidated = active?.let { connectivity.getNetworkCapabilities(it) }
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            connectivity.registerDefaultNetworkCallback(networkCallback)
        } catch (e: SecurityException) {
            Timber.tag(TAG).w(e, "Cannot watch the network: failed uploads are not retried when it returns")
        }
    }

    /**
     * Queue the failed uploads again because the network came back ([reason]),
     * if kiosk network recovery is on. Uploads paused through [stopUploads]
     * stay paused: their failed tasks wait in the queue for the resume.
     */
    private fun retryFailedOnReconnect(reason: String) {
        serviceScope.launch {
            if ((application as LensDaemonApp).kioskManager.getConfig().networkRecovery.autoReconnect) {
                val retried = if (_state.value == UploadServiceState.PAUSED) {
                    uploadQueue.retryFailed().also { updateStats() }
                } else {
                    retryFailed()
                }
                if (retried > 0) Timber.tag(TAG).i("$reason: retrying $retried failed uploads")
            }
        }
    }

    /**
     * Initialize storage clients from stored credentials. A backend whose saved
     * settings can't be read (for example restored from a backup without their
     * key) is left unconfigured instead of stopping the service.
     */
    private fun initializeClients() {
        // S3 client
        try {
            val s3Creds = credentialStore.getS3Credentials()
            if (s3Creds != null && s3Creds.isValid()) {
                s3Client = S3Client(s3Creds)
                Timber.tag(TAG).d("S3 client initialized: ${s3Creds.backend}")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Saved S3 settings could not be loaded; S3 uploads are off")
        }

        // SMB client
        try {
            val smbCreds = credentialStore.getSmbCredentials()
            if (smbCreds != null && smbCreds.isValid()) {
                smbClient = SmbClient(smbCreds)
                Timber.tag(TAG).d("SMB client initialized: ${smbCreds.server}")
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Saved SMB settings could not be loaded; SMB uploads are off")
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

        try {
            startForeground(NOTIFICATION_ID, createNotification("Starting uploads..."))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses it from the background: upload anyway, while
            // the web server keeps this service bound
            Timber.tag(TAG).w(e, "Uploading without a foreground notification")
        }

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
            Timber.tag(TAG).w("File not found: $filePath")
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

        Timber.tag(TAG).i("Enqueued $count recordings for upload")
        return count
    }

    /**
     * Configure S3 credentials
     */
    fun configureS3(credentials: S3Credentials) {
        credentialStore.storeS3Credentials(credentials)
        s3Client = S3Client(credentials)
        updateStats()
        Timber.tag(TAG).i("S3 configured: ${credentials.backend} - ${credentials.bucket}")
    }

    /**
     * Test S3 connection
     */
    suspend fun testS3Connection(): Result<Boolean> {
        val client = s3Client ?: return Result.failure(Exception("S3 not configured"))
        return client.testConnection()
    }

    /**
     * Test S3 settings without saving them, so they can be checked before they
     * replace the saved ones.
     */
    suspend fun testS3Connection(credentials: S3Credentials): Result<Boolean> =
        S3Client(credentials).testConnection()

    /**
     * Configure SMB credentials
     */
    fun configureSmb(credentials: SmbCredentials) {
        credentialStore.storeSmbCredentials(credentials)
        smbClient?.disconnect()
        smbClient = SmbClient(credentials)
        updateStats()
        Timber.tag(TAG).i("SMB configured: ${credentials.server}/${credentials.share}")
    }

    /**
     * Test SMB connection
     */
    suspend fun testSmbConnection(): Result<Boolean> {
        val client = smbClient ?: return Result.failure(Exception("SMB not configured"))
        return client.testConnection()
    }

    /**
     * Test SMB settings without saving them, so they can be checked before they
     * replace the saved ones.
     */
    suspend fun testSmbConnection(credentials: SmbCredentials): Result<Boolean> {
        val client = SmbClient(credentials)
        return try {
            client.testConnection()
        } finally {
            // A failed attempt can leave a connection half open
            client.disconnect()
        }
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
     * Get the uploads that used up their attempts (until retried or cancelled)
     */
    fun getFailedTasks(): List<UploadTask> = uploadQueue.getFailedTasks()

    /**
     * Cancel a pending task, or drop a failed one
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
     * Queue all failed tasks again with fresh attempts, and start uploading if
     * no upload is running.
     *
     * @return how many uploads were queued again
     */
    fun retryFailed(): Int {
        val retried = uploadQueue.retryFailed()
        if (retried > 0 && _state.value != UploadServiceState.UPLOADING) {
            startUploads()
        }
        updateStats()
        return retried
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
        lastNotifiedPercent = -1
        updateNotification("Uploading: ${task.fileName}")
        updateStats()
    }

    override fun onUploadProgress(task: UploadTask, bytesUploaded: Long, totalBytes: Long) {
        val progress = if (totalBytes > 0) ((bytesUploaded * 100) / totalBytes).toInt() else 0
        // Progress comes every few KB: update only when the whole percentage moves
        if (progress == lastNotifiedPercent) return
        lastNotifiedPercent = progress
        updateNotification("Uploading: ${task.fileName} ($progress%)")
        updateStats()
    }

    override fun onUploadCompleted(task: UploadTask) {
        updateNotification("Upload completed: ${task.fileName}")
        updateStats()
    }

    override fun onUploadFailed(task: UploadTask, error: String) {
        Timber.tag(TAG).e("Upload failed: ${task.fileName} - $error")
        updateStats()
    }

    override fun onUploadCancelled(task: UploadTask) {
        updateStats()
    }

    /**
     * Called once each time the queue runs out of work, so the notice is
     * posted once; it says how many uploads are left in the failed list.
     */
    override fun onQueueEmpty() {
        _state.value = UploadServiceState.IDLE
        val failed = uploadQueue.getStats().failedCount
        updateNotification(if (failed > 0) "Uploads finished: $failed failed" else "All uploads completed")
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
            "$status (${stats.completedCount}/${stats.totalCount})"
        } else {
            status
        }

        // Only an upload in progress stays put with a progress bar; the
        // finished and paused notices can be swiped away
        val uploading = _state.value == UploadServiceState.UPLOADING
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("LensDaemon Uploads")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(uploading)
            .setSilent(true)
        if (uploading) builder.setProgress(100, stats.currentProgress, stats.currentProgress == 0)
        return builder.build()
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
