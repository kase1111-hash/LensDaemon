package com.lensdaemon.camera

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Controls camera zoom with smooth animations and pinch-to-zoom support.
 *
 * The zoom reaches the camera through the zoom-changed listener, which
 * CameraService turns into the capture request: CONTROL_ZOOM_RATIO on Android
 * 11+, a centred SCALER_CROP_REGION on Android 10 (see CaptureRequestExtras.kt).
 *
 * Every method may be called from any thread: the web API's request threads,
 * the director's worker threads, the phone's gestures. Animations run as a
 * coroutine on [scope] (the main thread by default), and a later zoom change,
 * whether set, animated or pinched, always replaces an earlier animation: a
 * generation counter keeps a late animation step from overwriting it. Values
 * that are not finite numbers are ignored.
 */
class ZoomController(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
) {

    companion object {
        private const val ZOOM_ANIMATION_DURATION_MS = 200L
        private const val MIN_ZOOM = 1.0f

        /** Time between animation steps, about one display frame. */
        private const val FRAME_MS = 16L
    }

    // Zoom state
    private val _currentZoom = MutableStateFlow(1.0f)
    val currentZoom: StateFlow<Float> = _currentZoom

    private val _zoomRange = MutableStateFlow(MIN_ZOOM..MIN_ZOOM)
    val zoomRange: StateFlow<ClosedFloatingPointRange<Float>> = _zoomRange

    // Camera characteristics
    private var maxZoom: Float = 1.0f
    private var supportsZoomRatio = false

    /** Makes each zoom change and its listener call one step, against changes from other threads. */
    private val lock = Any()

    // Animation
    @Volatile private var animationJob: Job? = null

    /** Bumped by every change that replaces an animation; a step of an older generation is dropped. */
    private val generation = AtomicInteger()

    @Volatile private var onZoomChanged: ((Float) -> Unit)? = null

    /**
     * Initialize zoom controller with camera characteristics.
     */
    fun initialize(characteristics: CameraCharacteristics) {
        // An animation for the previous lens must not run on into this one
        cancelZoomAnimation()

        maxZoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            ?: 1.0f

        // Check if zoom ratio is supported (Android 11+)
        supportsZoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val zoomRatioRange = characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            if (zoomRatioRange != null) {
                maxZoom = zoomRatioRange.upper
                true
            } else {
                false
            }
        } else {
            false
        }

        _zoomRange.value = MIN_ZOOM..maxZoom
        _currentZoom.value = MIN_ZOOM

        Timber.i("Zoom initialized - max: $maxZoom, supportsZoomRatio: $supportsZoomRatio")
    }

    /**
     * Set zoom change callback for real-time updates. It is called on the
     * thread that made the change (the main thread for animation steps).
     */
    fun setOnZoomChangedListener(listener: (Float) -> Unit) {
        onZoomChanged = listener
    }

    /**
     * Set zoom level immediately (no animation), stopping any animation.
     * @return the zoom applied: [zoomLevel] clamped to the zoom range, or the
     *   current zoom, unchanged, when [zoomLevel] is not a finite number
     */
    fun setZoom(zoomLevel: Float): Float {
        if (!zoomLevel.isFinite()) {
            Timber.w("Ignoring zoom $zoomLevel")
            return _currentZoom.value
        }
        return synchronized(lock) {
            cancelZoomAnimation()
            val clamped = zoomLevel.coerceIn(_zoomRange.value)
            _currentZoom.value = clamped
            onZoomChanged?.invoke(clamped)
            clamped
        }
    }

    /**
     * Animate zoom to target level over [durationMs], easing into it. Callable
     * from any thread: the steps run on [scope]. A target that is not a finite
     * number is ignored; a duration of 0 or less sets the zoom at once.
     */
    fun animateZoomTo(targetZoom: Float, durationMs: Long = ZOOM_ANIMATION_DURATION_MS) {
        if (!targetZoom.isFinite()) {
            Timber.w("Ignoring a zoom animation to $targetZoom")
            return
        }
        if (durationMs <= 0) {
            setZoom(targetZoom)
            return
        }
        synchronized(lock) {
            cancelZoomAnimation()

            val startZoom = _currentZoom.value
            val endZoom = targetZoom.coerceIn(_zoomRange.value)
            if (startZoom == endZoom) return

            val gen = generation.incrementAndGet()
            animationJob = scope.launch {
                val startedAt = SystemClock.uptimeMillis()
                var running = true
                while (running) {
                    val t = ((SystemClock.uptimeMillis() - startedAt) / durationMs.toFloat()).coerceIn(0f, 1f)
                    // Decelerate: quick at first, easing into the target
                    val zoom = startZoom + (endZoom - startZoom) * (1f - (1f - t) * (1f - t))
                    running = publishStep(gen, zoom) && t < 1f
                    if (running) delay(FRAME_MS)
                }
            }
            Timber.d("Animating zoom from $startZoom to $endZoom")
        }
    }

    /** Apply one animation step, unless a later change has replaced the animation. */
    private fun publishStep(stepGeneration: Int, zoom: Float): Boolean = synchronized(lock) {
        val current = generation.get() == stepGeneration
        if (current) {
            _currentZoom.value = zoom
            onZoomChanged?.invoke(zoom)
        }
        current
    }

    /**
     * Zoom in by a step with animation.
     */
    fun zoomIn(step: Float = 0.5f, animated: Boolean = true): Float {
        val targetZoom = (_currentZoom.value + step).coerceIn(_zoomRange.value)
        if (!targetZoom.isFinite()) return _currentZoom.value
        if (animated) {
            animateZoomTo(targetZoom)
        } else {
            setZoom(targetZoom)
        }
        return targetZoom
    }

    /**
     * Zoom out by a step with animation.
     */
    fun zoomOut(step: Float = 0.5f, animated: Boolean = true): Float {
        val targetZoom = (_currentZoom.value - step).coerceIn(_zoomRange.value)
        if (!targetZoom.isFinite()) return _currentZoom.value
        if (animated) {
            animateZoomTo(targetZoom)
        } else {
            setZoom(targetZoom)
        }
        return targetZoom
    }

    /**
     * Reset zoom to 1x with animation.
     */
    fun resetZoom(animated: Boolean = true) {
        if (animated) {
            animateZoomTo(MIN_ZOOM)
        } else {
            setZoom(MIN_ZOOM)
        }
    }

    /**
     * Cancel any ongoing zoom animation, where it is. Callable from any thread.
     */
    fun cancelZoomAnimation() {
        generation.incrementAndGet()
        animationJob?.cancel()
        animationJob = null
    }

    /**
     * Check if zoom animation is in progress.
     */
    fun isAnimating(): Boolean = animationJob?.isActive == true

    // ==================== Pinch-to-zoom support ====================

    /**
     * Called when pinch gesture starts: an animation in progress stops.
     */
    fun onPinchStart() {
        cancelZoomAnimation()
    }

    /**
     * Called during pinch gesture with scale factor.
     * @param scaleFactor the change since the previous call (ScaleGestureDetector's
     *   scaleFactor when onScale returns true; 1.0 = no change), applied to the
     *   current zoom. One that is not a finite number is ignored.
     * @return the zoom applied
     */
    fun onPinchScale(scaleFactor: Float): Float {
        if (!scaleFactor.isFinite()) return _currentZoom.value
        return synchronized(lock) { setZoom(_currentZoom.value * scaleFactor) }
    }

    /**
     * Called when pinch gesture ends.
     */
    fun onPinchEnd() {
        // Optional: snap to preset zoom levels
    }

    /**
     * Get zoom level display string.
     */
    fun getZoomDisplayString(): String {
        val zoom = _currentZoom.value
        return if (zoom < 2.0f) {
            String.format("%.1fx", zoom)
        } else {
            String.format("%.0fx", zoom)
        }
    }

    /**
     * Get zoom percentage (0-100).
     */
    fun getZoomPercentage(): Int {
        val range = _zoomRange.value
        val normalized = (_currentZoom.value - range.start) / (range.endInclusive - range.start)
        return (normalized * 100).roundToInt()
    }

    /**
     * Set zoom from percentage (0-100).
     */
    fun setZoomFromPercentage(percentage: Int, animated: Boolean = false): Float {
        val range = _zoomRange.value
        val targetZoom = range.start + (percentage / 100f) * (range.endInclusive - range.start)
        return if (animated) {
            animateZoomTo(targetZoom)
            targetZoom
        } else {
            setZoom(targetZoom)
        }
    }

    /**
     * Reset controller state.
     */
    fun reset() {
        synchronized(lock) {
            cancelZoomAnimation()
            _currentZoom.value = MIN_ZOOM
        }
    }

    /**
     * Check if camera supports zoom beyond 1x.
     */
    fun isZoomSupported(): Boolean = maxZoom > 1.0f
}
