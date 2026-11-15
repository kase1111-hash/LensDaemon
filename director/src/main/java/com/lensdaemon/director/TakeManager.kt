package com.lensdaemon.director

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
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
 * The samples come from QualityMetricsCollector while a take runs; samples outside a take,
 * or while the director is paused, are ignored. Each sample is added to the take's running
 * totals as it arrives, so a take uses the same little memory however long it runs.
 *
 * On the phone the exposure and motion inputs are placeholders: the exposure is the
 * configured compensation, not a measurement, and the shake is a constant per
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

        private const val NANOS_PER_MS = 1_000_000L

        /**
         * Now, in ms on a monotonic clock, which setting the phone's time doesn't move: the
         * clock of the expected times given to [recordCueExecution].
         */
        fun monotonicNowMs(): Long = System.nanoTime() / NANOS_PER_MS
    }

    /**
     * Active take state. Its quality samples are kept as running totals ([samples]).
     */
    data class ActiveTake(
        val takeNumber: Int,
        val sceneId: String,
        val sceneLabel: String,
        val startTimeMs: Long,
        var cueTimings: MutableList<CueTiming> = mutableListOf(),
        var cuesExecuted: Int = 0,
        var cuesFailed: Int = 0
    ) {
        /** The quality samples of the take so far (under the take manager's lock). */
        internal val samples = SampleTotals()
    }

    /**
     * Quality samples summed as they arrive: how many there were of each kind, and the
     * totals the take's quality factors are worked out from when it ends.
     */
    internal class SampleTotals {
        var focus = 0L
        var focusLocked = 0L
        var exposure = 0L
        var exposureSum = 0.0
        var exposureSquareSum = 0.0
        var motion = 0L
        var motionSum = 0.0
        var audio = 0L
        var audioOutOfRange = 0L
    }

    /**
     * Cue timing data for accuracy scoring; both times are in ms on [monotonicNowMs]'s clock
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

    /**
     * True while quality samples are to be ignored although a take is open. [DirectorManager]
     * makes it "while the director is paused", so a pause doesn't count in the take's score.
     */
    @Volatile
    internal var samplingPaused: () -> Boolean = { false }

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

    /**
     * Start a new take. Callers end the take in progress first (and announce it); one still
     * open here is ended without notice.
     */
    fun startTake(sceneId: String, sceneLabel: String): RecordedTake = synchronized(lock) {
        _currentTake.value?.let { endTakeLocked(null) }

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
     * Record a focus state sample (ignored outside a take and while paused)
     */
    fun recordFocusSample(locked: Boolean) {
        addSample { totals ->
            totals.focus++
            if (locked) totals.focusLocked++
        }
    }

    /**
     * Record an exposure value sample (normalized 0-1; ignored outside a take and while paused)
     */
    fun recordExposureSample(value: Float) {
        val exposure = value.coerceIn(0f, 1f).toDouble()
        addSample { totals ->
            totals.exposure++
            totals.exposureSum += exposure
            totals.exposureSquareSum += exposure * exposure
        }
    }

    /**
     * Record a motion/stability sample (0 = perfectly stable, 1 = very shaky; ignored outside a take and while paused)
     */
    fun recordMotionSample(shakiness: Float) {
        val motion = shakiness.coerceIn(0f, 1f).toDouble()
        addSample { totals ->
            totals.motion++
            totals.motionSum += motion
        }
    }

    /**
     * Record an audio level sample (dB normalized to 0-1; ignored outside a take and while paused).
     * Levels below 0.1 or above 0.9 are out of range.
     */
    fun recordAudioSample(level: Float) {
        val audio = level.coerceIn(0f, 1f)
        addSample { totals ->
            totals.audio++
            if (audio < 0.1f || audio > 0.9f) totals.audioOutOfRange++
        }
    }

    /** Adds a sample to the running totals of the take in progress: none outside a take or while paused. */
    private inline fun addSample(add: (SampleTotals) -> Unit) {
        synchronized(lock) {
            val take = _currentTake.value ?: return
            if (samplingPaused()) return
            add(take.samples)
        }
    }

    /**
     * True when the take in progress belongs to [sceneId] and no cue has run in it yet
     */
    fun isUnusedTakeOf(sceneId: String): Boolean = synchronized(lock) {
        val take = _currentTake.value
        take != null && take.sceneId == sceneId && take.cuesExecuted + take.cuesFailed == 0
    }

    /**
     * Record a cue execution in the take in progress. [expectedTimeMs] is when a script
     * meant the cue to run, on [monotonicNowMs]'s clock; with null (a cue run by hand) only
     * the success or failure counts.
     */
    fun recordCueExecution(cueId: String, expectedTimeMs: Long?, success: Boolean) {
        synchronized(lock) {
            val take = _currentTake.value ?: return
            if (expectedTimeMs != null) {
                take.cueTimings.add(
                    CueTiming(
                        cueId = cueId,
                        expectedTimeMs = expectedTimeMs,
                        actualTimeMs = monotonicNowMs(),
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
     * Calculate quality factors from the take's sample totals
     */
    private fun calculateQualityFactors(take: ActiveTake): TakeQualityFactors {
        val samples = take.samples

        // Focus lock percentage
        val focusLockPercent = if (samples.focus > 0) {
            (samples.focusLocked * 100 / samples.focus).toInt()
        } else 0

        // Exposure stability (low variance = stable)
        val exposureStability = if (samples.exposure >= 2) {
            val mean = samples.exposureSum / samples.exposure
            val variance = (samples.exposureSquareSum / samples.exposure - mean * mean).coerceAtLeast(0.0)
            // Convert variance to stability score (lower variance = higher stability)
            max(0f, 1f - (variance * 4).toFloat())
        } else 0f

        // Motion stability (low shakiness = stable)
        val motionStability = if (samples.motion > 0) {
            // Average inverse of shakiness
            (1.0 - samples.motionSum / samples.motion).toFloat()
        } else 0f

        // Cue timing accuracy (0 when no script cue ran; the score then leaves it out)
        val cueTimingAccuracy = if (take.cueTimings.isNotEmpty()) {
            take.cueTimings.map { it.timingAccuracy }.average().toFloat()
        } else 0f

        // Audio levels OK (should be between 0.1 and 0.9)
        val audioLevelOk = if (samples.audio > 0) {
            samples.audioOutOfRange < samples.audio * 0.2 // Less than 20% out of range
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
