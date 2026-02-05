package com.lensdaemon.director

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Quality Metrics Collector
 *
 * Collects real-time quality metrics from the camera system and forwards
 * them to the TakeManager for quality scoring. Runs in a separate coroutine
 * to avoid impacting camera performance.
 *
 * Metrics collected:
 * - Focus lock state (boolean samples)
 * - Exposure stability (normalized 0-1)
 * - Motion/shake detection (0 = stable, 1 = shaky)
 * - Audio levels (if available)
 */
class QualityMetricsCollector(
    private val sampleIntervalMs: Long = DEFAULT_SAMPLE_INTERVAL_MS
) {
    companion object {
        private const val TAG = "QualityMetricsCollector"
        private const val DEFAULT_SAMPLE_INTERVAL_MS = 100L // 10 samples per second
    }

    /**
     * Metrics source interface
     */
    interface MetricsSource {
        fun isFocusLocked(): Boolean
        fun getNormalizedExposure(): Float
        fun getMotionShakiness(): Float
        fun getAudioLevel(): Float // Returns 0 if no audio
    }

    /**
     * Metrics sink interface (typically TakeManager)
     */
    interface MetricsSink {
        fun recordFocusSample(locked: Boolean)
        fun recordExposureSample(value: Float)
        fun recordMotionSample(shakiness: Float)
        fun recordAudioSample(level: Float)
    }

    /**
     * Current metrics snapshot
     */
    data class MetricsSnapshot(
        val focusLocked: Boolean = false,
        val exposure: Float = 0.5f,
        val motion: Float = 0f,
        val audioLevel: Float = 0f,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Collector statistics
     */
    data class CollectorStats(
        val totalSamples: Long = 0,
        val focusLockSamples: Long = 0,
        val avgExposure: Float = 0f,
        val avgMotion: Float = 0f,
        val collectionStartTime: Long = 0,
        val lastSampleTime: Long = 0
    )

    private val _isCollecting = MutableStateFlow(false)
    val isCollecting: StateFlow<Boolean> = _isCollecting.asStateFlow()

    private val _currentMetrics = MutableStateFlow(MetricsSnapshot())
    val currentMetrics: StateFlow<MetricsSnapshot> = _currentMetrics.asStateFlow()

    private val _stats = MutableStateFlow(CollectorStats())
    val stats: StateFlow<CollectorStats> = _stats.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var collectionJob: Job? = null

    private var source: MetricsSource? = null
    private var sink: MetricsSink? = null

    // For stats calculation
    private var totalSamples = 0L
    private var focusLockCount = 0L
    private var exposureSum = 0f
    private var motionSum = 0f
    private var collectionStartTime = 0L

    /**
     * Set the metrics source (camera adapter)
     */
    fun setSource(source: MetricsSource) {
        this.source = source
        Timber.tag(TAG).d("Metrics source set")
    }

    /**
     * Set the metrics sink (take manager)
     */
    fun setSink(sink: MetricsSink) {
        this.sink = sink
        Timber.tag(TAG).d("Metrics sink set")
    }

    /**
     * Start collecting metrics
     */
    fun startCollection() {
        if (_isCollecting.value) {
            Timber.tag(TAG).w("Collection already in progress")
            return
        }

        if (source == null) {
            Timber.tag(TAG).e("Cannot start collection: no source set")
            return
        }

        // Reset stats
        totalSamples = 0L
        focusLockCount = 0L
        exposureSum = 0f
        motionSum = 0f
        collectionStartTime = System.currentTimeMillis()

        _isCollecting.value = true

        collectionJob = scope.launch {
            Timber.tag(TAG).i("Starting metrics collection (interval=${sampleIntervalMs}ms)")

            while (isActive && _isCollecting.value) {
                collectSample()
                delay(sampleIntervalMs)
            }

            Timber.tag(TAG).i("Metrics collection stopped")
        }
    }

    /**
     * Stop collecting metrics
     */
    fun stopCollection() {
        _isCollecting.value = false
        collectionJob?.cancel()
        collectionJob = null

        // Update final stats
        updateStats()

        Timber.tag(TAG).i("Collection stopped: $totalSamples samples collected")
    }

    /**
     * Pause collection (keeps state)
     */
    fun pauseCollection() {
        _isCollecting.value = false
        collectionJob?.cancel()
        collectionJob = null
        Timber.tag(TAG).d("Collection paused")
    }

    /**
     * Resume collection
     */
    fun resumeCollection() {
        if (!_isCollecting.value && source != null) {
            _isCollecting.value = true
            collectionJob = scope.launch {
                while (isActive && _isCollecting.value) {
                    collectSample()
                    delay(sampleIntervalMs)
                }
            }
            Timber.tag(TAG).d("Collection resumed")
        }
    }

    /**
     * Collect a single sample
     */
    private fun collectSample() {
        val src = source ?: return
        val snk = sink

        try {
            val focusLocked = src.isFocusLocked()
            val exposure = src.getNormalizedExposure()
            val motion = src.getMotionShakiness()
            val audio = src.getAudioLevel()

            // Update current metrics
            _currentMetrics.value = MetricsSnapshot(
                focusLocked = focusLocked,
                exposure = exposure,
                motion = motion,
                audioLevel = audio,
                timestamp = System.currentTimeMillis()
            )

            // Forward to sink
            snk?.apply {
                recordFocusSample(focusLocked)
                recordExposureSample(exposure)
                recordMotionSample(motion)
                if (audio > 0) {
                    recordAudioSample(audio)
                }
            }

            // Update stats
            totalSamples++
            if (focusLocked) focusLockCount++
            exposureSum += exposure
            motionSum += motion

            // Update stats periodically (every 10 samples)
            if (totalSamples % 10 == 0L) {
                updateStats()
            }

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Error collecting sample")
        }
    }

    /**
     * Update collector stats
     */
    private fun updateStats() {
        if (totalSamples == 0L) return

        _stats.value = CollectorStats(
            totalSamples = totalSamples,
            focusLockSamples = focusLockCount,
            avgExposure = exposureSum / totalSamples,
            avgMotion = motionSum / totalSamples,
            collectionStartTime = collectionStartTime,
            lastSampleTime = System.currentTimeMillis()
        )
    }

    /**
     * Get focus lock percentage
     */
    fun getFocusLockPercent(): Int {
        return if (totalSamples > 0) {
            ((focusLockCount * 100) / totalSamples).toInt()
        } else 0
    }

    /**
     * Get average exposure
     */
    fun getAverageExposure(): Float {
        return if (totalSamples > 0) exposureSum / totalSamples else 0.5f
    }

    /**
     * Get average motion/shakiness
     */
    fun getAverageMotion(): Float {
        return if (totalSamples > 0) motionSum / totalSamples else 0f
    }

    /**
     * Reset collection stats
     */
    fun resetStats() {
        totalSamples = 0L
        focusLockCount = 0L
        exposureSum = 0f
        motionSum = 0f
        collectionStartTime = System.currentTimeMillis()
        _stats.value = CollectorStats()
    }

    /**
     * Force a single sample (for manual triggering)
     */
    fun forceSample() {
        scope.launch {
            collectSample()
        }
    }

    /**
     * Clean up resources
     */
    fun destroy() {
        stopCollection()
        scope.cancel()
    }
}

/**
 * Extension to create a MetricsSource from CameraControllerAdapter
 */
fun CameraControllerAdapter.asMetricsSource(): QualityMetricsCollector.MetricsSource {
    val adapter = this
    return object : QualityMetricsCollector.MetricsSource {
        override fun isFocusLocked(): Boolean = adapter.isFocusLocked()
        override fun getNormalizedExposure(): Float = adapter.getNormalizedExposure()
        override fun getMotionShakiness(): Float = adapter.getMotionShakiness()
        override fun getAudioLevel(): Float = 0f // Audio not implemented in camera adapter
    }
}

/**
 * Extension to create a MetricsSink from TakeManager
 */
fun TakeManager.asMetricsSink(): QualityMetricsCollector.MetricsSink {
    val manager = this
    return object : QualityMetricsCollector.MetricsSink {
        override fun recordFocusSample(locked: Boolean) = manager.recordFocusSample(locked)
        override fun recordExposureSample(value: Float) = manager.recordExposureSample(value)
        override fun recordMotionSample(shakiness: Float) = manager.recordMotionSample(shakiness)
        override fun recordAudioSample(level: Float) = manager.recordAudioSample(level)
    }
}
