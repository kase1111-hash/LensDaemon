package com.lensdaemon.director

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lensdaemon.LensDaemonApp
import com.lensdaemon.MainActivity
import com.lensdaemon.R
import com.lensdaemon.camera.CameraService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Foreground service for AI Director functionality.
 *
 * Provides:
 * - DirectorManager lifecycle management
 * - Camera controller adapter integration
 * - Quality metrics collection
 * - Script persistence
 * - Take/recording coordination
 * - Service binding for API access
 */
class DirectorService : Service() {

    companion object {
        private const val TAG = "DirectorService"
        private const val NOTIFICATION_ID = 5001

        // Intent actions
        const val ACTION_START = "com.lensdaemon.action.START_DIRECTOR"
        const val ACTION_STOP = "com.lensdaemon.action.STOP_DIRECTOR"

        // Script storage
        private const val SCRIPTS_DIR = "director_scripts"
        private const val CURRENT_SCRIPT_FILE = "current_script.txt"

        /**
         * Create intent to start director service
         */
        fun createStartIntent(context: Context): Intent {
            return Intent(context, DirectorService::class.java).apply {
                action = ACTION_START
            }
        }

        /**
         * Create intent to stop director service
         */
        fun createStopIntent(context: Context): Intent {
            return Intent(context, DirectorService::class.java).apply {
                action = ACTION_STOP
            }
        }
    }

    // Service binder
    private val binder = DirectorBinder()

    inner class DirectorBinder : Binder() {
        fun getService(): DirectorService = this@DirectorService
    }

    // Coroutine scope
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Director components
    private var directorManager: DirectorManager? = null
    private var cameraControllerAdapter: CameraControllerAdapter? = null
    private var qualityMetricsCollector: QualityMetricsCollector? = null

    // Camera service connection
    private var cameraService: CameraService? = null
    private var cameraBound = false

    private val cameraConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as CameraService.LocalBinder
            cameraService = binder.getService()
            cameraBound = true

            // Set up camera integration
            setupCameraIntegration()

            Timber.tag(TAG).i("CameraService connected")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            cameraService = null
            cameraBound = false

            // Clean up camera integration
            cleanupCameraIntegration()

            Timber.tag(TAG).i("CameraService disconnected")
        }
    }

    // State
    private val _serviceState = MutableStateFlow(DirectorServiceState.STOPPED)
    val serviceState: StateFlow<DirectorServiceState> = _serviceState.asStateFlow()

    // Recording integration callbacks
    var onTakeStarted: ((take: RecordedTake) -> Unit)? = null
    var onTakeEnded: ((take: RecordedTake) -> Unit)? = null
    var onRecordingMarker: ((marker: TakeMarker) -> Unit)? = null

    override fun onCreate() {
        super.onCreate()
        Timber.tag(TAG).i("DirectorService created")

        // Initialize director manager
        directorManager = DirectorManager(this)

        // Subscribe to director events
        subscribeToDirectorEvents()

        // Ensure scripts directory exists
        getScriptsDirectory().mkdirs()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.tag(TAG).d("onStartCommand: ${intent?.action}")

        when (intent?.action) {
            ACTION_START -> {
                startDirectorService()
            }
            ACTION_STOP -> {
                stopDirectorService()
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Timber.tag(TAG).i("DirectorService destroyed")

        cleanupCameraIntegration()
        unbindCameraService()
        directorManager?.destroy()
        directorManager = null
        serviceScope.cancel()
    }

    /**
     * Start director service
     */
    private fun startDirectorService() {
        if (_serviceState.value == DirectorServiceState.RUNNING) {
            Timber.tag(TAG).w("Director service already running")
            return
        }

        Timber.tag(TAG).i("Starting director service")
        startForeground(NOTIFICATION_ID, createNotification("Ready"))

        // Bind to camera service
        bindCameraService()

        // Load saved script if exists
        loadSavedScript()

        _serviceState.value = DirectorServiceState.RUNNING
        updateNotification("Ready")
    }

    /**
     * Stop director service
     */
    private fun stopDirectorService() {
        Timber.tag(TAG).i("Stopping director service")

        directorManager?.stopExecution()
        cleanupCameraIntegration()
        unbindCameraService()

        _serviceState.value = DirectorServiceState.STOPPED
    }

    /**
     * Set up camera integration when camera service connects
     */
    private fun setupCameraIntegration() {
        val camera = cameraService ?: return
        val director = directorManager ?: return

        // Create camera controller adapter
        cameraControllerAdapter = CameraControllerAdapter(camera)

        // Connect director to camera controller
        director.setCameraController(cameraControllerAdapter)

        // Update shot mapper with camera capabilities
        cameraControllerAdapter?.let { adapter ->
            director.getShotMapper().updateCapabilities(adapter.getCameraCapabilities())
        }

        // Set up quality metrics collector
        qualityMetricsCollector = QualityMetricsCollector().apply {
            cameraControllerAdapter?.let { setSource(it.asMetricsSource()) }
            director.getTakeManager().let { setSink(it.asMetricsSink()) }
        }

        updateNotification(if (director.isEnabled()) "Active" else "Ready")
        Timber.tag(TAG).i("Camera integration set up")
    }

    /**
     * Clean up camera integration
     */
    private fun cleanupCameraIntegration() {
        qualityMetricsCollector?.destroy()
        qualityMetricsCollector = null

        cameraControllerAdapter?.reset()
        cameraControllerAdapter = null

        directorManager?.setCameraController(null)

        Timber.tag(TAG).i("Camera integration cleaned up")
    }

    /**
     * Subscribe to director events for recording integration
     */
    private fun subscribeToDirectorEvents() {
        val director = directorManager ?: return

        serviceScope.launch {
            director.events.collect { event ->
                when (event) {
                    is DirectorManager.DirectorEvent.TakeStarted -> {
                        onTakeStarted?.invoke(event.take)
                        onRecordingMarker?.invoke(TakeMarker(
                            type = MarkerType.TAKE_START,
                            takeNumber = event.take.takeNumber,
                            sceneId = event.take.sceneId,
                            timestampMs = event.take.startTimeMs
                        ))
                        updateNotification("Recording Take #${event.take.takeNumber}")
                    }
                    is DirectorManager.DirectorEvent.TakeEnded -> {
                        onTakeEnded?.invoke(event.take)
                        onRecordingMarker?.invoke(TakeMarker(
                            type = MarkerType.TAKE_END,
                            takeNumber = event.take.takeNumber,
                            sceneId = event.take.sceneId,
                            timestampMs = event.take.endTimeMs,
                            qualityScore = event.take.qualityScore
                        ))
                        updateNotification("Take #${event.take.takeNumber} ended")
                    }
                    is DirectorManager.DirectorEvent.StateChanged -> {
                        val state = event.state
                        val statusText = when (state) {
                            DirectorState.DISABLED -> "Disabled"
                            DirectorState.IDLE -> "Ready"
                            DirectorState.PARSING -> "Parsing..."
                            DirectorState.READY -> "Script Loaded"
                            DirectorState.RUNNING -> "Running"
                            DirectorState.PAUSED -> "Paused"
                            DirectorState.THERMAL_HOLD -> "Thermal Hold"
                        }
                        updateNotification(statusText)
                    }
                    is DirectorManager.DirectorEvent.CueExecuted -> {
                        onRecordingMarker?.invoke(TakeMarker(
                            type = MarkerType.CUE,
                            takeNumber = directorManager?.getTakeManager()?.currentTakeNumber ?: 0,
                            sceneId = directorManager?.currentSession?.value?.currentScene?.id ?: "",
                            timestampMs = System.currentTimeMillis(),
                            cueText = event.cue.rawText,
                            cueSuccess = event.success
                        ))
                    }
                    else -> {}
                }
            }
        }
    }

    /**
     * Bind to camera service
     */
    private fun bindCameraService() {
        if (!cameraBound) {
            val intent = Intent(this, CameraService::class.java)
            bindService(intent, cameraConnection, Context.BIND_AUTO_CREATE)
        }
    }

    /**
     * Unbind from camera service
     */
    private fun unbindCameraService() {
        if (cameraBound) {
            unbindService(cameraConnection)
            cameraBound = false
        }
    }

    /**
     * Create notification
     */
    private fun createNotification(status: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, LensDaemonApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AI Director")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * Update notification
     */
    private fun updateNotification(status: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, createNotification(status))
    }

    // ==================== Script Persistence ====================

    /**
     * Get scripts directory
     */
    private fun getScriptsDirectory(): File {
        return File(filesDir, SCRIPTS_DIR)
    }

    /**
     * Save current script to storage
     */
    fun saveCurrentScript(name: String = CURRENT_SCRIPT_FILE): Boolean {
        val session = directorManager?.currentSession?.value ?: return false
        val script = session.script

        return try {
            val file = File(getScriptsDirectory(), name)
            file.writeText(script.rawText)
            Timber.tag(TAG).i("Script saved: $name")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save script")
            false
        }
    }

    /**
     * Load saved script
     */
    private fun loadSavedScript() {
        val file = File(getScriptsDirectory(), CURRENT_SCRIPT_FILE)
        if (file.exists()) {
            try {
                val scriptText = file.readText()
                if (scriptText.isNotBlank()) {
                    directorManager?.loadScript(scriptText)
                    Timber.tag(TAG).i("Loaded saved script")
                }
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Failed to load saved script")
            }
        }
    }

    /**
     * List saved scripts
     */
    fun listSavedScripts(): List<ScriptFile> {
        return getScriptsDirectory().listFiles()?.mapNotNull { file ->
            if (file.isFile && file.name.endsWith(".txt")) {
                ScriptFile(
                    name = file.nameWithoutExtension,
                    fileName = file.name,
                    sizeBytes = file.length(),
                    lastModified = file.lastModified()
                )
            } else null
        }?.sortedByDescending { it.lastModified } ?: emptyList()
    }

    /**
     * Load script from file
     */
    fun loadScriptFromFile(fileName: String): Result<ParsedScript> {
        val file = File(getScriptsDirectory(), fileName)
        if (!file.exists()) {
            return Result.failure(IllegalArgumentException("Script file not found: $fileName"))
        }

        return try {
            val scriptText = file.readText()
            directorManager?.loadScript(scriptText)
                ?: Result.failure(IllegalStateException("Director not initialized"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Save script to file
     */
    fun saveScriptToFile(fileName: String, scriptText: String): Boolean {
        return try {
            val file = File(getScriptsDirectory(), if (fileName.endsWith(".txt")) fileName else "$fileName.txt")
            file.writeText(scriptText)
            Timber.tag(TAG).i("Script saved: ${file.name}")
            true
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to save script")
            false
        }
    }

    /**
     * Delete script file
     */
    fun deleteScriptFile(fileName: String): Boolean {
        val file = File(getScriptsDirectory(), fileName)
        return if (file.exists()) {
            file.delete().also { success ->
                if (success) Timber.tag(TAG).i("Script deleted: $fileName")
            }
        } else false
    }

    /**
     * Export script text
     */
    fun exportScript(fileName: String): String? {
        val file = File(getScriptsDirectory(), fileName)
        return if (file.exists()) file.readText() else null
    }

    // ==================== Recording Integration ====================

    /**
     * Start quality metrics collection (call when recording starts)
     */
    fun startQualityMetricsCollection() {
        qualityMetricsCollector?.startCollection()
    }

    /**
     * Stop quality metrics collection
     */
    fun stopQualityMetricsCollection() {
        qualityMetricsCollector?.stopCollection()
    }

    /**
     * Link take to recording file
     */
    fun linkTakeToRecording(takeNumber: Int, filePath: String) {
        directorManager?.getTakeManager()?.linkTakeToFile(takeNumber, filePath)
    }

    /**
     * Get take markers for recording metadata
     */
    fun getTakeMarkersForSession(): List<TakeMarker> {
        return takeMarkers.toList()
    }

    // Store markers for current session
    private val takeMarkers = mutableListOf<TakeMarker>()

    /**
     * Clear take markers (call when starting new recording)
     */
    fun clearTakeMarkers() {
        takeMarkers.clear()
    }

    // ==================== Public API ====================

    /**
     * Get director manager
     */
    fun getDirectorManager(): DirectorManager? = directorManager

    /**
     * Get quality metrics collector
     */
    fun getQualityMetricsCollector(): QualityMetricsCollector? = qualityMetricsCollector

    /**
     * Check if director is enabled
     */
    fun isDirectorEnabled(): Boolean = directorManager?.isEnabled() ?: false

    /**
     * Get current director state
     */
    fun getDirectorState(): DirectorState = directorManager?.state?.value ?: DirectorState.DISABLED

    /**
     * Check if camera is connected
     */
    fun isCameraConnected(): Boolean = cameraBound && cameraService != null
}

/**
 * Director service state
 */
enum class DirectorServiceState {
    STOPPED,
    RUNNING,
    ERROR
}

/**
 * Script file info
 */
data class ScriptFile(
    val name: String,
    val fileName: String,
    val sizeBytes: Long,
    val lastModified: Long
) {
    val lastModifiedFormatted: String
        get() {
            val date = java.util.Date(lastModified)
            return java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(date)
        }

    val sizeFormatted: String
        get() = when {
            sizeBytes >= 1024 * 1024 -> "${sizeBytes / (1024 * 1024)} MB"
            sizeBytes >= 1024 -> "${sizeBytes / 1024} KB"
            else -> "$sizeBytes B"
        }
}

/**
 * Take marker for recording metadata
 */
data class TakeMarker(
    val type: MarkerType,
    val takeNumber: Int,
    val sceneId: String,
    val timestampMs: Long,
    val qualityScore: Float = 0f,
    val cueText: String = "",
    val cueSuccess: Boolean = true
)

/**
 * Marker type
 */
enum class MarkerType {
    TAKE_START,
    TAKE_END,
    CUE,
    SCENE_CHANGE
}
