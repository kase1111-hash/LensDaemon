package com.lensdaemon.director

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.max

/**
 * Take Manager
 *
 * Manages take recording, automatic segmentation, quality scoring,
 * and best take suggestions for post-production efficiency.
 *
 * Takes score 0-10: at least [EXCELLENT_SCORE] is excellent, [GOOD_SCORE] good, and the
 * best take of a scene is suggested when it scores at least [SUGGESTED_MIN_SCORE].
 * Quality scoring considers:
 * - Focus lock percentage (was subject in focus?)
 * - Exposure stability (was lighting consistent?)
 * - Motion stability (was the shot steady?)
 * - Cue timing accuracy (did script cues hit their marks? left out when no script cue ran)
 * - Audio levels (was audio in acceptable range?)
 *
 * The samples come from QualityMetricsCollector while a take runs; samples outside a take
 * are ignored. On the phone the exposure and motion inputs are placeholders: the exposure
 * is the configured compensation, not a measurement, and the shake is a constant per
 * stabilisation mode, so those two factors say little until they come from capture results
 * or motion sensors. Audio isn't sampled, which leaves the audio factor at "OK".
 */
class TakeManager(
    config: DirectorConfig = DirectorConfig.DEFAULT
) {
    companion object {
        private const val TAG = "TakeManager"

        // Quality thresholds
        const val FOCUS_LOCK_GOOD = 80       // 80% focus lock = good
        const val EXPOSURE_STABILITY_GOOD = 0.8f
        const val MOTION_STABILITY_GOOD = 0.7f
        const val CUE_TIMING_GOOD = 0.85f

        // Quality weights for scoring
        const val WEIGHT_FOCUS = 0.30f
        const val WEIGHT_EXPOSURE = 0.20f
        const val WEIGHT_MOTION = 0.25f
        const val WEIGHT_CUE_TIMING = 0.15f
        const val WEIGHT_AUDIO = 0.10f

        /** Scores run from 0 to this. */
        const val MAX_SCORE = 10f

        /** A take scoring at least this is excellent. */
        const val EXCELLENT_SCORE = 8f

        /** A take scoring at least this is good. */
        const val GOOD_SCORE = 6f

        /** The best take of a scene is suggested (and listed as a best take) from this score. */
        const val SUGGESTED_MIN_SCORE = 5f
    }

    /**
     * Active take state
     */
    data class ActiveTake(
        val takeNumber: Int,
        val sceneId: String,
        val sceneLabel: String,
        val startTimeMs: Long,
        var focusSamples: MutableList<Boolean> = mutableListOf(),
        var exposureSamples: MutableList<Float> = mutableListOf(),
        var motionSamples: MutableList<Float> = mutableListOf(),
        var cueTimings: MutableList<CueTiming> = mutableListOf(),
        var audioLevelSamples: MutableList<Float> = mutableListOf(),
        var cuesExecuted: Int = 0,
        var cuesFailed: Int = 0
    )

    /**
     * Cue timing data for accuracy scoring
     */
    data class CueTiming(
        val cueId: String,
        val expectedTimeMs: Long,
        val actualTimeMs: Long,
        val success: Boolean
    ) {
        val timingErrorMs: Long get() = kotlin.math.abs(actualTimeMs - expectedTimeMs)
        val timingAccuracy: Float get() {
            // Convert error to accuracy (1.0 = perfect, 0.0 = 1+ second off)
            return max(0f, 1f - (timingErrorMs / 1000f))
        }
    }

    /**
     * Take comparison result
     */
    data class TakeComparison(
        val takes: List<RecordedTake>,
        val bestTake: RecordedTake?,
        val rankings: List<Pair<RecordedTake, Int>>,
        val recommendation: String
    )

    /** The settings in force (quality scoring on or off); [DirectorManager] replaces them when they change. */
    @Volatile
    internal var config: DirectorConfig = config

    private val _currentTake = MutableStateFlow<ActiveTake?>(null)

    /** The take in progress, or null between takes: quality samples count only while it is set. */
    val currentTake: StateFlow<ActiveTake?> = _currentTake.asStateFlow()

    private val _recordedTakes = MutableStateFlow<List<RecordedTake>>(emptyList())
    val recordedTakes: StateFlow<List<RecordedTake>> = _recordedTakes.asStateFlow()

    private val _bestTakeSuggestion = MutableStateFlow<RecordedTake?>(null)
    val bestTakeSuggestion: StateFlow<RecordedTake?> = _bestTakeSuggestion.asStateFlow()

    /** Guards the take in progress and the recorded takes (script, HTTP and sampling threads). */
    private val lock = Any()
    private var takeCounter = 0
    private val qualityMetricsQueue = ConcurrentLinkedQueue<QualityMetricSample>()

    /**
     * Quality metric sample for async processing
     */
    private data class QualityMetricSample(
        val timestamp: Long,
        val focusLocked: Boolean? = null,
        val exposureValue: Float? = null,
        val motionValue: Float? = null,
        val audioLevel: Float? = null
    )

    /**
     * Start a new take. Callers end the take in progress first (and announce it); one still
     * open here is ended without notice.
     */
    fun startTake(sceneId: String, sceneLabel: String): RecordedTake = synchronized(lock) {
        _currentTake.value?.let { endTakeLocked(null) }
        // Samples queued before this take belong to no take
        qualityMetricsQueue.clear()

        takeCounter++
        val take = ActiveTake(
            takeNumber = takeCounter,
            sceneId = sceneId,
            sceneLabel = sceneLabel,
            startTimeMs = System.currentTimeMillis()
        )
        _currentTake.value = take

        Timber.tag(TAG).i("Started take #$takeCounter for scene: $sceneLabel")

        RecordedTake(
            takeNumber = takeCounter,
            sceneId = sceneId,
            sceneLabel = sceneLabel,
            startTimeMs = take.startTimeMs
        )
    }

    /**
     * End current take and calculate quality score
     */
    fun endTake(filePath: String? = null): RecordedTake? = synchronized(lock) { endTakeLocked(filePath) }

    private fun endTakeLocked(filePath: String?): RecordedTake? {
        val active = _currentTake.value ?: return null
        val endTimeMs = System.currentTimeMillis()

        // Process any pending quality metrics
        processQueuedMetrics(active)

        // Calculate quality factors
        val qualityFactors = calculateQualityFactors(active)
        val qualityScore = calculateOverallScore(qualityFactors, hasCueTimings = active.cueTimings.isNotEmpty())

        val recordedTake = RecordedTake(
            takeNumber = active.takeNumber,
            sceneId = active.sceneId,
            sceneLabel = active.sceneLabel,
            startTimeMs = active.startTimeMs,
            endTimeMs = endTimeMs,
            filePath = filePath,
            qualityScore = qualityScore,
            qualityFactors = qualityFactors,
            cuesExecuted = active.cuesExecuted,
            cuesFailed = active.cuesFailed,
            suggested = false
        )

        // Add to recorded takes
        _recordedTakes.value = _recordedTakes.value + recordedTake

        // Update best take suggestion for this scene
        updateBestTakeSuggestion(active.sceneId)

        _currentTake.value = null

        Timber.tag(TAG).i(
            "Ended take #${active.takeNumber}: score=%.2f, focus=%d%%, duration=%dms",
            qualityScore,
            qualityFactors.focusLockPercent,
            recordedTake.durationMs
        )

        return _recordedTakes.value.lastOrNull { it.takeNumber == active.takeNumber } ?: recordedTake
    }

    /**
     * Record a focus state sample (ignored outside a take)
     */
    fun recordFocusSample(locked: Boolean) {
        if (_currentTake.value == null) return
        qualityMetricsQueue.add(
            QualityMetricSample(
                timestamp = System.currentTimeMillis(),
                focusLocked = locked
            )
        )
    }

    /**
     * Record an exposure value sample (normalized 0-1; ignored outside a take)
     */
    fun recordExposureSample(value: Float) {
        if (_currentTake.value == null) return
        qualityMetricsQueue.add(
            QualityMetricSample(
                timestamp = System.currentTimeMillis(),
                exposureValue = value.coerceIn(0f, 1f)
            )
        )
    }

    /**
     * Record a motion/stability sample (0 = perfectly stable, 1 = very shaky; ignored outside a take)
     */
    fun recordMotionSample(shakiness: Float) {
        if (_currentTake.value == null) return
        qualityMetricsQueue.add(
            QualityMetricSample(
                timestamp = System.currentTimeMillis(),
                motionValue = shakiness.coerceIn(0f, 1f)
            )
        )
    }

    /**
     * Record an audio level sample (dB normalized to 0-1; ignored outside a take)
     */
    fun recordAudioSample(level: Float) {
        if (_currentTake.value == null) return
        qualityMetricsQueue.add(
            QualityMetricSample(
                timestamp = System.currentTimeMillis(),
                audioLevel = level.coerceIn(0f, 1f)
            )
        )
    }

    /**
     * Record a cue execution in the take in progress. [expectedTimeMs] is when a script
     * meant the cue to run; with null (a cue run by hand) only the success or failure counts.
     */
    fun recordCueExecution(cueId: String, expectedTimeMs: Long?, success: Boolean) {
        synchronized(lock) {
            val take = _currentTake.value ?: return
            if (expectedTimeMs != null) {
                take.cueTimings.add(
                    CueTiming(
                        cueId = cueId,
                        expectedTimeMs = expectedTimeMs,
                        actualTimeMs = System.currentTimeMillis(),
                        success = success
                    )
                )
            }
            if (success) {
                take.cuesExecuted++
            } else {
                take.cuesFailed++
            }
        }
    }

    /**
     * Process queued quality metrics into [take]
     */
    private fun processQueuedMetrics(take: ActiveTake) {
        while (qualityMetricsQueue.isNotEmpty()) {
            val sample = qualityMetricsQueue.poll() ?: break

            sample.focusLocked?.let { take.focusSamples.add(it) }
            sample.exposureValue?.let { take.exposureSamples.add(it) }
            sample.motionValue?.let { take.motionSamples.add(it) }
            sample.audioLevel?.let { take.audioLevelSamples.add(it) }
        }
    }

    /**
     * Calculate quality factors from collected samples
     */
    private fun calculateQualityFactors(take: ActiveTake): TakeQualityFactors {
        // Focus lock percentage
        val focusLockPercent = if (take.focusSamples.isNotEmpty()) {
            (take.focusSamples.count { it } * 100 / take.focusSamples.size)
        } else 0

        // Exposure stability (low variance = stable)
        val exposureStability = if (take.exposureSamples.size >= 2) {
            val mean = take.exposureSamples.average().toFloat()
            val variance = take.exposureSamples.map { (it - mean) * (it - mean) }.average().toFloat()
            // Convert variance to stability score (lower variance = higher stability)
            max(0f, 1f - (variance * 4))
        } else 0f

        // Motion stability (low shakiness = stable)
        val motionStability = if (take.motionSamples.isNotEmpty()) {
            // Average inverse of shakiness
            1f - take.motionSamples.average().toFloat()
        } else 0f

        // Cue timing accuracy (0 when no script cue ran; the score then leaves it out)
        val cueTimingAccuracy = if (take.cueTimings.isNotEmpty()) {
            take.cueTimings.map { it.timingAccuracy }.average().toFloat()
        } else 0f

        // Audio levels OK (should be between 0.1 and 0.9)
        val audioLevelOk = if (take.audioLevelSamples.isNotEmpty()) {
            val outOfRange = take.audioLevelSamples.count { it < 0.1f || it > 0.9f }
            outOfRange < take.audioLevelSamples.size * 0.2 // Less than 20% out of range
        } else true

        return TakeQualityFactors(
            focusLockPercent = focusLockPercent,
            exposureStability = exposureStability,
            motionStability = motionStability,
            audioLevelOk = audioLevelOk,
            cueTimingAccuracy = cueTimingAccuracy
        )
    }

    /**
     * The overall score, 0-10: the weighted factors times [MAX_SCORE]. Without cue timings
     * (no script cue ran) the other weights are scaled up to fill the timing's share.
     */
    private fun calculateOverallScore(factors: TakeQualityFactors, hasCueTimings: Boolean): Float {
        if (!config.qualityScoring) return 0f

        val focusScore = factors.focusLockPercent / 100f
        val audioScore = if (factors.audioLevelOk) 1f else 0.5f

        var weighted = focusScore * WEIGHT_FOCUS +
                factors.exposureStability * WEIGHT_EXPOSURE +
                factors.motionStability * WEIGHT_MOTION +
                audioScore * WEIGHT_AUDIO
        var weights = WEIGHT_FOCUS + WEIGHT_EXPOSURE + WEIGHT_MOTION + WEIGHT_AUDIO
        if (hasCueTimings) {
            weighted += factors.cueTimingAccuracy * WEIGHT_CUE_TIMING
            weights += WEIGHT_CUE_TIMING
        }
        return (weighted / weights * MAX_SCORE).coerceIn(0f, MAX_SCORE)
    }

    /**
     * Marks the best take of a scene as suggested (when it scores at least
     * [SUGGESTED_MIN_SCORE]) and the scene's other takes as not suggested.
     */
    private fun updateBestTakeSuggestion(sceneId: String) {
        val takes = _recordedTakes.value
        val best = takes.filter { it.sceneId == sceneId }
            .maxByOrNull { it.qualityScore }
            ?.takeIf { it.qualityScore >= SUGGESTED_MIN_SCORE }

        _recordedTakes.value = takes.map { take ->
            if (take.sceneId == sceneId) take.copy(suggested = take.takeNumber == best?.takeNumber) else take
        }

        if (best != null) {
            _bestTakeSuggestion.value = best.copy(suggested = true)
            Timber.tag(TAG).d("Best take for scene $sceneId: #${best.takeNumber} (score: %.2f)", best.qualityScore)
        }
    }

    /**
     * Manually mark a take quality
     */
    fun markTake(takeNumber: Int, quality: TakeQuality, notes: String = ""): Boolean = synchronized(lock) {
        val takes = _recordedTakes.value.toMutableList()
        val index = takes.indexOfFirst { it.takeNumber == takeNumber }

        if (index >= 0) {
            takes[index] = takes[index].copy(manualMark = quality, notes = notes)
            _recordedTakes.value = takes
            Timber.tag(TAG).d("Marked take #$takeNumber as $quality")
            true
        } else {
            false
        }
    }

    /**
     * Get takes for a specific scene
     */
    fun getTakesForScene(sceneId: String): List<RecordedTake> {
        return _recordedTakes.value.filter { it.sceneId == sceneId }
    }

    /**
     * Compare takes for a scene and get recommendations
     */
    fun compareTakes(sceneId: String): TakeComparison {
        val sceneTakes = getTakesForScene(sceneId)
        if (sceneTakes.isEmpty()) {
            return TakeComparison(
                takes = emptyList(),
                bestTake = null,
                rankings = emptyList(),
                recommendation = "No takes recorded for this scene"
            )
        }

        // Sort by quality score
        val ranked = sceneTakes.sortedByDescending { it.qualityScore }
            .mapIndexed { index, take -> Pair(take, index + 1) }

        val best = ranked.firstOrNull()?.first

        // Generate recommendation
        val recommendation = buildString {
            if (best != null) {
                append("Take #${best.takeNumber} recommended")
                if (best.qualityScore >= EXCELLENT_SCORE) {
                    append(" (excellent quality)")
                } else if (best.qualityScore >= GOOD_SCORE) {
                    append(" (good quality)")
                } else {
                    append(" (acceptable, consider re-shooting)")
                }

                // Note any issues with best take
                val factors = best.qualityFactors
                val issues = mutableListOf<String>()
                if (factors.focusLockPercent < FOCUS_LOCK_GOOD) issues.add("focus issues")
                if (factors.motionStability < MOTION_STABILITY_GOOD) issues.add("camera shake")
                if (!factors.audioLevelOk) issues.add("audio levels")

                if (issues.isNotEmpty()) {
                    append(". Note: ${issues.joinToString(", ")}")
                }
            }
        }

        return TakeComparison(
            takes = sceneTakes,
            bestTake = best,
            rankings = ranked,
            recommendation = recommendation
        )
    }

    /**
     * The best take of each scene, keyed by scene id, for scenes whose best take scores at
     * least [SUGGESTED_MIN_SCORE]
     */
    fun getAllBestTakes(): Map<String, RecordedTake> {
        return _recordedTakes.value
            .groupBy { it.sceneId }
            .mapValues { (_, takes) -> takes.maxByOrNull { it.qualityScore }!! }
            .filter { it.value.qualityScore >= SUGGESTED_MIN_SCORE }
    }

    /**
     * Get statistics for current session
     */
    fun getSessionStats(): SessionStats {
        val takes = _recordedTakes.value
        val bestTakes = getAllBestTakes()

        return SessionStats(
            totalTakes = takes.size,
            uniqueScenes = takes.map { it.sceneId }.distinct().size,
            avgQualityScore = if (takes.isNotEmpty()) takes.map { it.qualityScore }.average().toFloat() else 0f,
            bestTakeCount = bestTakes.size,
            circledTakes = takes.count { it.manualMark == TakeQuality.CIRCLE },
            totalDurationMs = takes.sumOf { it.durationMs },
            totalCuesExecuted = takes.sumOf { it.cuesExecuted },
            totalCuesFailed = takes.sumOf { it.cuesFailed }
        )
    }

    /**
     * Session statistics
     */
    data class SessionStats(
        val totalTakes: Int,
        val uniqueScenes: Int,
        val avgQualityScore: Float,
        val bestTakeCount: Int,
        val circledTakes: Int,
        val totalDurationMs: Long,
        val totalCuesExecuted: Int,
        val totalCuesFailed: Int
    ) {
        val totalDurationFormatted: String get() {
            val totalSeconds = totalDurationMs / 1000
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%d:%02d", minutes, seconds)
            }
        }

        val cueSuccessRate: Float get() {
            val total = totalCuesExecuted + totalCuesFailed
            return if (total > 0) totalCuesExecuted.toFloat() / total else 0f
        }
    }

    /**
     * Reset take counter for a new session
     */
    fun resetSession() {
        synchronized(lock) {
            takeCounter = 0
            _currentTake.value = null
            _recordedTakes.value = emptyList()
            _bestTakeSuggestion.value = null
            qualityMetricsQueue.clear()
        }
        Timber.tag(TAG).i("Take manager session reset")
    }

    /**
     * Check if currently recording a take
     */
    fun isRecording(): Boolean = _currentTake.value != null

    /**
     * Get current take number
     */
    fun getCurrentTakeNumber(): Int = _currentTake.value?.takeNumber ?: 0

    /**
     * Link a take to its recording file. False when there is no such take.
     */
    fun linkTakeToFile(takeNumber: Int, filePath: String): Boolean = synchronized(lock) {
        val takes = _recordedTakes.value.toMutableList()
        val index = takes.indexOfFirst { it.takeNumber == takeNumber }

        if (index >= 0) {
            val take = takes[index]
            takes[index] = take.copy(filePath = filePath)
            _recordedTakes.value = takes
            Timber.tag(TAG).i("Linked take #$takeNumber to file: $filePath")
            true
        } else {
            Timber.tag(TAG).w("Cannot link file - take #$takeNumber not found")
            false
        }
    }

    /**
     * Get takes with recording files
     */
    fun getTakesWithFiles(): List<RecordedTake> {
        return _recordedTakes.value.filter { it.filePath != null }
    }
}
