package com.lensdaemon.director

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * Transition Animator
 *
 * Handles smooth camera transitions for the AI Director including:
 * - Zoom animations (push in, pull back)
 * - Focus rack animations
 * - Exposure fade transitions
 * - Combined multi-property animations
 *
 * Uses Android's ValueAnimator for smooth, GPU-accelerated transitions
 * with configurable easing curves.
 */
class TransitionAnimator {

    companion object {
        private const val TAG = "TransitionAnimator"

        // Default durations
        const val DEFAULT_ZOOM_DURATION_MS = 1000L
        const val DEFAULT_FOCUS_DURATION_MS = 500L
        const val DEFAULT_EXPOSURE_DURATION_MS = 300L

        // Easing curves
        const val EASE_LINEAR = 0
        const val EASE_DECELERATE = 1
        const val EASE_ACCELERATE_DECELERATE = 2
    }

    /**
     * Transition state
     */
    enum class TransitionState {
        IDLE,
        ANIMATING,
        PAUSED,
        CANCELLED
    }

    /**
     * Active transition information
     */
    data class ActiveTransition(
        val type: TransitionType,
        val startValue: Float,
        val endValue: Float,
        val durationMs: Long,
        val progress: Float,
        val startTimeMs: Long
    )

    /**
     * Transition callback interface
     */
    interface TransitionCallback {
        fun onZoomUpdate(zoom: Float)
        fun onFocusDistanceUpdate(distance: Float)
        fun onExposureUpdate(ev: Float)
        fun onTransitionComplete(type: TransitionType)
        fun onTransitionCancelled(type: TransitionType)
    }

    private val _state = MutableStateFlow(TransitionState.IDLE)
    val state: StateFlow<TransitionState> = _state.asStateFlow()

    private val _activeTransition = MutableStateFlow<ActiveTransition?>(null)
    val activeTransition: StateFlow<ActiveTransition?> = _activeTransition.asStateFlow()

    private var callback: TransitionCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Active animators
    private var zoomAnimator: ValueAnimator? = null
    private var focusAnimator: ValueAnimator? = null
    private var exposureAnimator: ValueAnimator? = null

    /**
     * Set transition callback
     */
    fun setCallback(callback: TransitionCallback) {
        this.callback = callback
    }

    /**
     * Animate zoom from current to target level
     */
    fun animateZoom(
        fromZoom: Float,
        toZoom: Float,
        durationMs: Long = DEFAULT_ZOOM_DURATION_MS,
        easing: Int = EASE_ACCELERATE_DECELERATE,
        onComplete: (() -> Unit)? = null
    ) {
        cancelZoom()

        Timber.tag(TAG).d("Animating zoom: $fromZoom -> $toZoom over ${durationMs}ms")

        _state.value = TransitionState.ANIMATING
        _activeTransition.value = ActiveTransition(
            type = if (toZoom > fromZoom) TransitionType.PUSH_IN else TransitionType.PULL_BACK,
            startValue = fromZoom,
            endValue = toZoom,
            durationMs = durationMs,
            progress = 0f,
            startTimeMs = System.currentTimeMillis()
        )

        zoomAnimator = ValueAnimator.ofFloat(fromZoom, toZoom).apply {
            duration = durationMs
            interpolator = getInterpolator(easing)

            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                val progress = animator.animatedFraction
                callback?.onZoomUpdate(value)

                _activeTransition.value = _activeTransition.value?.copy(progress = progress)
            }

            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    _state.value = TransitionState.IDLE
                    _activeTransition.value = null
                    callback?.onTransitionComplete(
                        if (toZoom > fromZoom) TransitionType.PUSH_IN else TransitionType.PULL_BACK
                    )
                    onComplete?.invoke()
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    _state.value = TransitionState.CANCELLED
                    _activeTransition.value = null
                    callback?.onTransitionCancelled(
                        if (toZoom > fromZoom) TransitionType.PUSH_IN else TransitionType.PULL_BACK
                    )
                }
            })

            start()
        }
    }

    /**
     * Animate focus distance (rack focus)
     */
    fun animateFocusDistance(
        fromDistance: Float,
        toDistance: Float,
        durationMs: Long = DEFAULT_FOCUS_DURATION_MS,
        easing: Int = EASE_DECELERATE,
        onComplete: (() -> Unit)? = null
    ) {
        cancelFocus()

        Timber.tag(TAG).d("Animating focus: $fromDistance -> $toDistance over ${durationMs}ms")

        _state.value = TransitionState.ANIMATING
        _activeTransition.value = ActiveTransition(
            type = TransitionType.RACK_FOCUS,
            startValue = fromDistance,
            endValue = toDistance,
            durationMs = durationMs,
            progress = 0f,
            startTimeMs = System.currentTimeMillis()
        )

        focusAnimator = ValueAnimator.ofFloat(fromDistance, toDistance).apply {
            duration = durationMs
            interpolator = getInterpolator(easing)

            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                val progress = animator.animatedFraction
                callback?.onFocusDistanceUpdate(value)

                _activeTransition.value = _activeTransition.value?.copy(progress = progress)
            }

            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    _state.value = TransitionState.IDLE
                    _activeTransition.value = null
                    callback?.onTransitionComplete(TransitionType.RACK_FOCUS)
                    onComplete?.invoke()
                }

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    _state.value = TransitionState.CANCELLED
                    _activeTransition.value = null
                    callback?.onTransitionCancelled(TransitionType.RACK_FOCUS)
                }
            })

            start()
        }
    }

    /**
     * Animate exposure compensation
     */
    fun animateExposure(
        fromEv: Float,
        toEv: Float,
        durationMs: Long = DEFAULT_EXPOSURE_DURATION_MS,
        easing: Int = EASE_LINEAR,
        onComplete: (() -> Unit)? = null
    ) {
        cancelExposure()

        Timber.tag(TAG).d("Animating exposure: $fromEv -> $toEv over ${durationMs}ms")

        exposureAnimator = ValueAnimator.ofFloat(fromEv, toEv).apply {
            duration = durationMs
            interpolator = getInterpolator(easing)

            addUpdateListener { animator ->
                val value = animator.animatedValue as Float
                callback?.onExposureUpdate(value)
            }

            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    onComplete?.invoke()
                }
            })

            start()
        }
    }

    /**
     * Execute a push-in transition (zoom in)
     */
    fun pushIn(
        currentZoom: Float,
        targetZoom: Float? = null,
        durationMs: Long = DEFAULT_ZOOM_DURATION_MS,
        onComplete: (() -> Unit)? = null
    ) {
        val target = targetZoom ?: (currentZoom * 1.5f).coerceAtMost(10f)
        animateZoom(currentZoom, target, durationMs, EASE_ACCELERATE_DECELERATE, onComplete)
    }

    /**
     * Execute a pull-back transition (zoom out)
     */
    fun pullBack(
        currentZoom: Float,
        targetZoom: Float? = null,
        durationMs: Long = DEFAULT_ZOOM_DURATION_MS,
        onComplete: (() -> Unit)? = null
    ) {
        val target = targetZoom ?: (currentZoom * 0.67f).coerceAtLeast(0.5f)
        animateZoom(currentZoom, target, durationMs, EASE_ACCELERATE_DECELERATE, onComplete)
    }

    /**
     * Execute a rack focus transition
     */
    fun rackFocus(
        fromDistance: Float,
        toDistance: Float,
        durationMs: Long = DEFAULT_FOCUS_DURATION_MS,
        onComplete: (() -> Unit)? = null
    ) {
        animateFocusDistance(fromDistance, toDistance, durationMs, EASE_DECELERATE, onComplete)
    }

    /**
     * Execute a combined zoom and focus transition
     */
    fun animateZoomAndFocus(
        fromZoom: Float,
        toZoom: Float,
        fromFocus: Float,
        toFocus: Float,
        durationMs: Long,
        onComplete: (() -> Unit)? = null
    ) {
        var zoomComplete = false
        var focusComplete = false

        val checkComplete = {
            if (zoomComplete && focusComplete) {
                onComplete?.invoke()
            }
        }

        animateZoom(fromZoom, toZoom, durationMs) {
            zoomComplete = true
            checkComplete()
        }

        animateFocusDistance(fromFocus, toFocus, durationMs) {
            focusComplete = true
            checkComplete()
        }
    }

    /**
     * Execute a hold transition (maintain state for duration)
     */
    fun hold(durationMs: Long, onComplete: (() -> Unit)? = null) {
        Timber.tag(TAG).d("Holding for ${durationMs}ms")

        _state.value = TransitionState.ANIMATING
        _activeTransition.value = ActiveTransition(
            type = TransitionType.HOLD,
            startValue = 0f,
            endValue = 1f,
            durationMs = durationMs,
            progress = 0f,
            startTimeMs = System.currentTimeMillis()
        )

        scope.launch {
            val steps = 10
            val stepDuration = durationMs / steps
            for (i in 1..steps) {
                delay(stepDuration)
                _activeTransition.value = _activeTransition.value?.copy(
                    progress = i.toFloat() / steps
                )
            }
            _state.value = TransitionState.IDLE
            _activeTransition.value = null
            callback?.onTransitionComplete(TransitionType.HOLD)
            onComplete?.invoke()
        }
    }

    /**
     * Pause current animation
     */
    fun pause() {
        zoomAnimator?.pause()
        focusAnimator?.pause()
        exposureAnimator?.pause()
        _state.value = TransitionState.PAUSED
    }

    /**
     * Resume paused animation
     */
    fun resume() {
        zoomAnimator?.resume()
        focusAnimator?.resume()
        exposureAnimator?.resume()
        if (_state.value == TransitionState.PAUSED) {
            _state.value = TransitionState.ANIMATING
        }
    }

    /**
     * Cancel zoom animation
     */
    fun cancelZoom() {
        zoomAnimator?.cancel()
        zoomAnimator = null
    }

    /**
     * Cancel focus animation
     */
    fun cancelFocus() {
        focusAnimator?.cancel()
        focusAnimator = null
    }

    /**
     * Cancel exposure animation
     */
    fun cancelExposure() {
        exposureAnimator?.cancel()
        exposureAnimator = null
    }

    /**
     * Cancel all animations
     */
    fun cancelAll() {
        cancelZoom()
        cancelFocus()
        cancelExposure()
        scope.coroutineContext.cancelChildren()
        _state.value = TransitionState.IDLE
        _activeTransition.value = null
    }

    /**
     * Check if any animation is running
     */
    fun isAnimating(): Boolean {
        return _state.value == TransitionState.ANIMATING
    }

    /**
     * Get remaining time for current transition
     */
    fun getRemainingTimeMs(): Long {
        val transition = _activeTransition.value ?: return 0
        val elapsed = System.currentTimeMillis() - transition.startTimeMs
        return (transition.durationMs - elapsed).coerceAtLeast(0)
    }

    /**
     * Get interpolator for easing type
     */
    private fun getInterpolator(easing: Int) = when (easing) {
        EASE_LINEAR -> LinearInterpolator()
        EASE_DECELERATE -> DecelerateInterpolator()
        EASE_ACCELERATE_DECELERATE -> AccelerateDecelerateInterpolator()
        else -> AccelerateDecelerateInterpolator()
    }

    /**
     * Cleanup resources
     */
    fun destroy() {
        cancelAll()
        scope.cancel()
    }
}
