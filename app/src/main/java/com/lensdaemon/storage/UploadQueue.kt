package com.lensdaemon.storage

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Upload destination type
 */
enum class UploadDestination {
    S3,
    SMB
}

/**
 * Upload status
 */
enum class UploadStatus {
    PENDING,
    UPLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Upload task representing a file to upload
 */
data class UploadTask(
    /** Unique task ID */
    val id: String = UUID.randomUUID().toString(),

    /** Local file path */
    val localPath: String,

    /** Remote file path/key */
    val remotePath: String,

    /** Upload destination */
    val destination: UploadDestination,

    /** Current status */
    var status: UploadStatus = UploadStatus.PENDING,

    /** File size in bytes */
    val fileSize: Long,

    /** Bytes uploaded */
    var bytesUploaded: Long = 0,

    /** Upload progress (0-100) */
    var progress: Int = 0,

    /** Number of retry attempts */
    var retryCount: Int = 0,

    /** Maximum retries allowed */
    val maxRetries: Int = 3,

    /** Last error message */
    var lastError: String? = null,

    /** Task creation time */
    val createdAt: Long = System.currentTimeMillis(),

    /** Last attempt time */
    var lastAttemptAt: Long = 0,

    /** Completion time */
    var completedAt: Long = 0,

    /** Content type */
    val contentType: String = "video/mp4",

    /** Delete local file after successful upload */
    val deleteAfterUpload: Boolean = false
) {
    /** Check if file exists */
    fun fileExists(): Boolean = File(localPath).exists()

    /** Get filename from path */
    val fileName: String get() = File(localPath).name

    /** Check if task can be retried */
    fun canRetry(): Boolean = retryCount < maxRetries && status == UploadStatus.FAILED

    /** Calculate retry delay (exponential backoff) */
    fun getRetryDelayMs(): Long = (1000L * (1 shl retryCount)).coerceAtMost(60_000L)

    /** Convert to JSON for persistence */
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("localPath", localPath)
            put("remotePath", remotePath)
            put("destination", destination.name)
            put("status", status.name)
            put("fileSize", fileSize)
            put("bytesUploaded", bytesUploaded)
            put("progress", progress)
            put("retryCount", retryCount)
            put("maxRetries", maxRetries)
            put("lastError", lastError ?: JSONObject.NULL)
            put("createdAt", createdAt)
            put("lastAttemptAt", lastAttemptAt)
            put("completedAt", completedAt)
            put("contentType", contentType)
            put("deleteAfterUpload", deleteAfterUpload)
        }
    }

    companion object {
        /** Create from JSON */
        fun fromJson(json: JSONObject): UploadTask {
            return UploadTask(
                id = json.getString("id"),
                localPath = json.getString("localPath"),
                remotePath = json.getString("remotePath"),
                destination = UploadDestination.valueOf(json.getString("destination")),
                status = UploadStatus.valueOf(json.getString("status")),
                fileSize = json.getLong("fileSize"),
                bytesUploaded = json.optLong("bytesUploaded", 0),
                progress = json.optInt("progress", 0),
                retryCount = json.optInt("retryCount", 0),
                maxRetries = json.optInt("maxRetries", 3),
                lastError = if (json.isNull("lastError")) null else json.optString("lastError"),
                createdAt = json.getLong("createdAt"),
                lastAttemptAt = json.optLong("lastAttemptAt", 0),
                completedAt = json.optLong("completedAt", 0),
                contentType = json.optString("contentType", "video/mp4"),
                deleteAfterUpload = json.optBoolean("deleteAfterUpload", false)
            )
        }
    }
}

/**
 * Upload queue statistics
 */
data class UploadQueueStats(
    val pendingCount: Int = 0,
    val uploadingCount: Int = 0,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val totalBytes: Long = 0,
    val uploadedBytes: Long = 0,
    val currentTaskId: String? = null,
    val currentProgress: Int = 0
) {
    val totalCount: Int get() = pendingCount + uploadingCount + completedCount + failedCount
    val overallProgress: Int get() = if (totalBytes > 0) ((uploadedBytes * 100) / totalBytes).toInt() else 0
    val isActive: Boolean get() = uploadingCount > 0
}

/**
 * Upload event listener
 */
interface UploadEventListener {
    fun onUploadStarted(task: UploadTask)
    fun onUploadProgress(task: UploadTask, bytesUploaded: Long, totalBytes: Long)
    fun onUploadCompleted(task: UploadTask)
    fun onUploadFailed(task: UploadTask, error: String)
    fun onUploadCancelled(task: UploadTask)
    fun onQueueEmpty()
}

/**
 * Upload progress callback
 */
typealias ProgressCallback = (bytesUploaded: Long, totalBytes: Long) -> Unit

/**
 * Upload queue manager
 *
 * Features:
 * - One upload at a time, by a single processing loop
 * - Persistent queue storage: the upload in progress, pending and failed
 *   uploads survive a restart
 * - Retry with backoff: 3 attempts, 2 s and 4 s apart; uploads that use them
 *   all up are kept in a failed list until retried or cancelled
 * - Progress tracking
 * - Event callbacks
 * - Queue statistics
 */
class UploadQueue(
    private val context: Context
) {
    companion object {
        private const val TAG = "UploadQueue"
        private const val PREFS_NAME = "upload_queue"
        private const val KEY_QUEUE = "queue_tasks"
        private const val KEY_COMPLETED = "completed_tasks"
        private const val KEY_FAILED = "failed_tasks"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // In-memory queue
    private val pendingQueue = ConcurrentLinkedQueue<UploadTask>()

    /** Finished uploads; read by status requests while the processing loop adds to it. */
    private val completedTasks = CopyOnWriteArrayList<UploadTask>()

    /** Uploads that used up their attempts; guarded by synchronized(failedTasks). */
    private val failedTasks = mutableListOf<UploadTask>()

    @Volatile
    private var currentTask: UploadTask? = null

    /** The processing loop; at most one runs ([startProcessing] and [keepProcessing] hand it over). */
    private var processingJob: Job? = null

    /** Whether listeners have heard that the queue is empty since it last had work. */
    @Volatile
    private var queueEmptyNotified = false

    // State
    private val _stats = MutableStateFlow(UploadQueueStats())
    val stats: StateFlow<UploadQueueStats> = _stats.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    // Listeners
    private val listeners = mutableListOf<UploadEventListener>()

    // Upload handler callback (set by UploadService)
    var uploadHandler: (suspend (UploadTask, ProgressCallback) -> Result<Unit>)? = null

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    init {
        loadQueue()
    }

    /**
     * Add a task to the upload queue
     */
    fun enqueue(task: UploadTask): Boolean {
        if (!task.fileExists()) {
            Timber.tag(TAG).w("File does not exist: ${task.localPath}")
            return false
        }

        pendingQueue.offer(task)
        saveQueue()
        updateStats()

        Timber.tag(TAG).d("Task enqueued: ${task.fileName} -> ${task.destination}")
        return true
    }

    /**
     * Add a file to upload queue
     */
    fun enqueue(
        localFile: File,
        remotePath: String,
        destination: UploadDestination,
        deleteAfterUpload: Boolean = false
    ): UploadTask? {
        if (!localFile.exists()) {
            Timber.tag(TAG).w("File does not exist: ${localFile.absolutePath}")
            return null
        }

        val task = UploadTask(
            localPath = localFile.absolutePath,
            remotePath = remotePath,
            destination = destination,
            fileSize = localFile.length(),
            deleteAfterUpload = deleteAfterUpload
        )

        return if (enqueue(task)) task else null
    }

    /**
     * Start processing the queue. Only one processing loop ever runs: a start
     * while one runs (even one finishing its last upload after a stop) just
     * keeps it going.
     */
    @Synchronized
    fun startProcessing() {
        _isProcessing.value = true
        // A new start reports an empty queue once more, so its state settles
        queueEmptyNotified = false

        if (processingJob?.isActive == true) {
            Timber.tag(TAG).d("Already processing")
            return
        }
        processingJob = scope.launch {
            processQueue()
        }
    }

    /**
     * Stop processing the queue. The upload in progress finishes first; no
     * new one starts.
     */
    fun stopProcessing() {
        _isProcessing.value = false
        Timber.tag(TAG).d("Processing stopped")
    }

    /**
     * Whether the processing loop should take another task. When it stops, it
     * gives up its job under the same lock as [startProcessing], so a start
     * that comes just then launches a new loop instead of relying on this one.
     */
    @Synchronized
    private fun keepProcessing(): Boolean {
        if (!_isProcessing.value) processingJob = null
        return _isProcessing.value
    }

    /**
     * Process tasks in the queue
     */
    private suspend fun processQueue() {
        Timber.tag(TAG).d("Starting queue processing")

        while (keepProcessing()) {
            val task = pendingQueue.poll()

            if (task == null) {
                // Queue is empty: tell the listeners once, then wait for work
                if (!queueEmptyNotified) {
                    queueEmptyNotified = true
                    notifyQueueEmpty()
                }
                delay(1000) // Wait before checking again
                continue
            }
            queueEmptyNotified = false

            // Check if file still exists
            if (!task.fileExists()) {
                Timber.tag(TAG).w("File no longer exists: ${task.localPath}")
                task.status = UploadStatus.FAILED
                task.lastError = "File not found"
                synchronized(failedTasks) { failedTasks.add(task) }
                saveQueue()
                updateStats()
                notifyUploadFailed(task, "File not found")
                continue
            }

            // Process the task
            processTask(task)
        }

        Timber.tag(TAG).d("Queue processing stopped")
    }

    /**
     * Process a single upload task
     */
    private suspend fun processTask(task: UploadTask) {
        currentTask = task
        task.status = UploadStatus.UPLOADING
        task.lastAttemptAt = System.currentTimeMillis()
        // Every attempt uploads the whole file again
        task.bytesUploaded = 0
        task.progress = 0
        updateStats()

        notifyUploadStarted(task)

        val progressCallback: ProgressCallback = { bytesUploaded, totalBytes ->
            task.bytesUploaded = bytesUploaded
            val percent = if (totalBytes > 0) ((bytesUploaded * 100) / totalBytes).toInt() else 0
            // Progress comes every few KB: refresh the stats when the whole percentage moves
            if (percent != task.progress) {
                task.progress = percent
                updateStats()
            }
            notifyUploadProgress(task, bytesUploaded, totalBytes)
        }

        val handler = uploadHandler
        val result = try {
            handler?.invoke(task, progressCallback) ?: Result.failure(IllegalStateException("No upload handler"))
        } catch (e: CancellationException) {
            // Released (the service stopped) mid-upload: end here, not as a failed
            // attempt, so the task stays pending as release() saved it
            if (!currentCoroutineContext().isActive) throw e
            Timber.tag(TAG).e(e, "Upload cancelled: ${task.fileName}")
            Result.failure(e)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Upload failed: ${task.fileName}")
            Result.failure(e)
        }

        if (result.isSuccess) {
            task.status = UploadStatus.COMPLETED
            task.completedAt = System.currentTimeMillis()
            task.progress = 100
            task.bytesUploaded = task.fileSize

            completedTasks.add(task)
            notifyUploadCompleted(task)

            // Delete local file if requested
            if (task.deleteAfterUpload) {
                try {
                    File(task.localPath).delete()
                    Timber.tag(TAG).d("Deleted local file: ${task.localPath}")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to delete local file: ${task.localPath}")
                }
            }

            Timber.tag(TAG).i("Upload completed: ${task.fileName}")
        } else {
            task.lastError = result.exceptionOrNull()?.message ?: "Unknown error"
            // FAILED first: only a failed task may be retried (UploadTask.canRetry)
            task.status = UploadStatus.FAILED
            handleFailedTask(task)
        }

        currentTask = null
        saveQueue()
        updateStats()
    }

    /**
     * Handle a failed attempt: try again after a backoff (2 s, then 4 s) or,
     * after the last of its attempts, keep the task in the failed list.
     */
    private suspend fun handleFailedTask(task: UploadTask) {
        task.retryCount++

        if (task.canRetry()) {
            task.status = UploadStatus.PENDING
            val delayMs = task.getRetryDelayMs()
            Timber.tag(TAG).d("Retrying in ${delayMs}ms: ${task.fileName} (attempt ${task.retryCount + 1})")

            delay(delayMs)
            pendingQueue.offer(task)
        } else {
            task.status = UploadStatus.FAILED
            synchronized(failedTasks) { failedTasks.add(task) }
            notifyUploadFailed(task, task.lastError ?: "Unknown error")
            Timber.tag(TAG).e("Upload failed permanently: ${task.fileName}")
        }
    }

    /**
     * Cancel a pending task, or drop a failed one
     */
    fun cancelTask(taskId: String): Boolean {
        val task = pendingQueue.find { it.id == taskId }
            ?: synchronized(failedTasks) { failedTasks.find { it.id == taskId }?.also { failedTasks.remove(it) } }
            ?: return false

        pendingQueue.remove(task)
        task.status = UploadStatus.CANCELLED
        notifyUploadCancelled(task)
        saveQueue()
        updateStats()
        Timber.tag(TAG).d("Task cancelled: ${task.fileName}")
        return true
    }

    /**
     * Clear all pending tasks
     */
    fun clearPending() {
        pendingQueue.forEach { it.status = UploadStatus.CANCELLED }
        pendingQueue.clear()
        saveQueue()
        updateStats()
        Timber.tag(TAG).d("Pending tasks cleared")
    }

    /**
     * Clear completed tasks history
     */
    fun clearCompleted() {
        completedTasks.clear()
        saveQueue()
        updateStats()
    }

    /**
     * Queue every failed upload again, with a fresh set of attempts.
     * The caller starts processing if it is not running.
     *
     * @return how many uploads were queued again
     */
    fun retryFailed(): Int {
        val retried = synchronized(failedTasks) {
            failedTasks.toList().also { failedTasks.clear() }
        }
        retried.forEach { task ->
            task.status = UploadStatus.PENDING
            task.retryCount = 0
            task.lastError = null
            task.bytesUploaded = 0
            task.progress = 0
            pendingQueue.offer(task)
        }
        saveQueue()
        updateStats()
        Timber.tag(TAG).d("Retrying ${retried.size} failed tasks")
        return retried.size
    }

    /**
     * Get all pending tasks
     */
    fun getPendingTasks(): List<UploadTask> = pendingQueue.toList()

    /**
     * Get completed tasks history
     */
    fun getCompletedTasks(): List<UploadTask> = completedTasks.toList()

    /**
     * Get the uploads that used up their attempts, oldest first
     */
    fun getFailedTasks(): List<UploadTask> = synchronized(failedTasks) { failedTasks.toList() }

    /**
     * Get current upload task
     */
    fun getCurrentTask(): UploadTask? = currentTask

    /**
     * Get queue statistics
     */
    fun getStats(): UploadQueueStats {
        val current = currentTask
        val pending = pendingQueue.filter { it.status == UploadStatus.PENDING }
        val uploading = if (current?.status == UploadStatus.UPLOADING) 1 else 0
        val completed = completedTasks.filter { it.status == UploadStatus.COMPLETED }
        val failedCount = synchronized(failedTasks) { failedTasks.size }

        val totalBytes = pendingQueue.sumOf { it.fileSize } + (current?.fileSize ?: 0)
        val uploadedBytes = completedTasks.sumOf { it.fileSize } + (current?.bytesUploaded ?: 0)

        return UploadQueueStats(
            pendingCount = pending.size,
            uploadingCount = uploading,
            completedCount = completed.size,
            failedCount = failedCount,
            totalBytes = totalBytes,
            uploadedBytes = uploadedBytes,
            currentTaskId = current?.id,
            currentProgress = current?.progress ?: 0
        )
    }

    /**
     * Add event listener
     */
    fun addListener(listener: UploadEventListener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    /**
     * Remove event listener
     */
    fun removeListener(listener: UploadEventListener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    /**
     * Release resources
     */
    fun release() {
        stopProcessing()
        saveQueue()
        scope.cancel()
        listeners.clear()
    }

    /**
     * Save queue to persistent storage: the upload in progress (first, as
     * pending, so it starts over after a restart), the pending uploads, the
     * failed ones and the last 100 completed.
     */
    private fun saveQueue() {
        try {
            // Not when it has just completed or failed for good, or is back in the queue
            val inFlight = currentTask?.takeIf {
                (it.status == UploadStatus.UPLOADING || it.status == UploadStatus.PENDING) && it !in pendingQueue
            }
            val queueJson = JSONArray().apply {
                inFlight?.let { put(it.toJson().put("status", UploadStatus.PENDING.name)) }
                pendingQueue.forEach { put(it.toJson()) }
            }
            val failedJson = JSONArray().apply {
                synchronized(failedTasks) { failedTasks.forEach { put(it.toJson()) } }
            }
            val completedJson = JSONArray().apply {
                completedTasks.toList().takeLast(100).forEach { put(it.toJson()) } // Keep last 100
            }

            prefs.edit()
                .putString(KEY_QUEUE, queueJson.toString())
                .putString(KEY_FAILED, failedJson.toString())
                .putString(KEY_COMPLETED, completedJson.toString())
                .apply()
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save queue")
        }
    }

    /**
     * Load queue from persistent storage. An upload that was in progress is
     * pending again, listed once even if a save caught it on its way back into
     * the queue; failed uploads go back to the failed list.
     */
    private fun loadQueue() {
        try {
            uploadTasksFrom(prefs.getString(KEY_QUEUE, null)).distinctBy { it.id }.forEach { task ->
                if (task.status == UploadStatus.FAILED) {
                    synchronized(failedTasks) { failedTasks.add(task) }
                } else {
                    // Reset uploading tasks to pending
                    task.status = UploadStatus.PENDING
                    pendingQueue.offer(task)
                }
            }
            val failed = uploadTasksFrom(prefs.getString(KEY_FAILED, null))
            synchronized(failedTasks) { failedTasks.addAll(failed) }
            completedTasks.addAll(uploadTasksFrom(prefs.getString(KEY_COMPLETED, null)))

            Timber.tag(TAG).d("Loaded ${pendingQueue.size} pending, ${failed.size} failed, ${completedTasks.size} completed tasks")
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load queue")
        }

        updateStats()
    }

    /**
     * Update statistics flow
     */
    private fun updateStats() {
        _stats.value = getStats()
    }

    // Notification helpers
    private fun notifyUploadStarted(task: UploadTask) {
        synchronized(listeners) {
            listeners.forEach { it.onUploadStarted(task) }
        }
    }

    private fun notifyUploadProgress(task: UploadTask, bytesUploaded: Long, totalBytes: Long) {
        synchronized(listeners) {
            listeners.forEach { it.onUploadProgress(task, bytesUploaded, totalBytes) }
        }
    }

    private fun notifyUploadCompleted(task: UploadTask) {
        synchronized(listeners) {
            listeners.forEach { it.onUploadCompleted(task) }
        }
    }

    private fun notifyUploadFailed(task: UploadTask, error: String) {
        synchronized(listeners) {
            listeners.forEach { it.onUploadFailed(task, error) }
        }
    }

    private fun notifyUploadCancelled(task: UploadTask) {
        synchronized(listeners) {
            listeners.forEach { it.onUploadCancelled(task) }
        }
    }

    private fun notifyQueueEmpty() {
        synchronized(listeners) {
            listeners.forEach { it.onQueueEmpty() }
        }
    }
}

/** The upload tasks in [json], a JSON array saved by [UploadQueue]; none when there is nothing saved. */
private fun uploadTasksFrom(json: String?): List<UploadTask> {
    if (json == null) return emptyList()
    val array = JSONArray(json)
    return (0 until array.length()).map { UploadTask.fromJson(array.getJSONObject(it)) }
}
