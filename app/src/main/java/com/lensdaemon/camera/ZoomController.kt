package com.lensdaemon.camera

import android.animation.ValueAnimator
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.view.animation.DecelerateInterpolator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Controls camera zoom with smooth animations and pinch-to-zoom support.
 * Supports both CONTROL_ZOOM_RATIO (Android 11+) and SCALER_CROP_REGION (legacy).
 */
class ZoomController {

    companion object {
        private const val ZOOM_ANIMATION_DURATION_MS = 200L
        private const val MIN_ZOOM = 1.0f
    }

    // Zoom state
    private val _currentZoom = MutableStateFlow(1.0f)
    val currentZoom: StateFlow<Float> = _currentZoom

    private val _zoomRange = MutableStateFlow(MIN_ZOOM..MIN_ZOOM)
    val zoomRange: StateFlow<ClosedFloatingPointRange<Float>> = _zoomRange

    // Camera characteristics
    private var sensorArraySize: Rect? = null
    private var maxZoom: Float = 1.0f
    private var supportsZoomRatio = false

    // Animation
    private var zoomAnimator: ValueAnimator? = null
    private var onZoomChanged: ((Float) -> Unit)? = null

    // Pinch-to-zoom state
    private var baseZoomForPinch: Float = 1.0f

    /**
     * Initialize zoom controller with camera characteristics.
     */
    fun initialize(characteristics: CameraCharacteristics) {
        sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

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
     * Set zoom change callback for real-time updates.
     */
    fun setOnZoomChangedListener(listener: (Float) -> Unit) {
        onZoomChanged = listener
    }

    /**
     * Set zoom level immediately (no animation).
     */
    fun setZoom(zoomLevel: Float): Float {
        cancelZoomAnimation()
        val clamped = zoomLevel.coerceIn(_zoomRange.value)
        _currentZoom.value = clamped
        onZoomChanged?.invoke(clamped)
        return clamped
    }

    /**
     * Animate zoom to target level.
     */
    fun animateZoomTo(targetZoom: Float, durationMs: Long = ZOOM_ANIMATION_DURATION_MS) {
        cancelZoomAnimation()

        val startZoom = _currentZoom.value
        val endZoom = targetZoom.coerceIn(_zoomRange.value)

        if (startZoom == endZoom) return

        zoomAnimator = ValueAnimator.ofFloat(startZoom, endZoom).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val zoom = animator.animatedValue as Float
                _currentZoom.value = zoom
                onZoomChanged?.invoke(zoom)
            }
            start()
        }

        Timber.d("Animating zoom from $startZoom to $endZoom")
    }

    /**
     * Zoom in by a step with animation.
     */
    fun zoomIn(step: Float = 0.5f, animated: Boolean = true): Float {
        val targetZoom = (_currentZoom.value + step).coerceIn(_zoomRange.value)
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
     * Cancel any ongoing zoom animation.
     */
    fun cancelZoomAnimation() {
        zoomAnimator?.cancel()
        zoomAnimator = null
    }

    /**
     * Check if zoom animation is in progress.
     */
    fun isAnimating(): Boolean = zoomAnimator?.isRunning == true

    // ==================== Pinch-to-zoom support ====================

    /**
     * Called when pinch gesture starts.
     */
    fun onPinchStart() {
        cancelZoomAnimation()
        baseZoomForPinch = _currentZoom.value
    }

    /**
     * Called during pinch gesture with scale factor.
     * @param scaleFactor The scale factor from pinch gesture (1.0 = no change)
     */
    fun onPinchScale(scaleFactor: Float): Float {
        val newZoom = (baseZoomForPinch * scaleFactor).coerceIn(_zoomRange.value)
        _currentZoom.value = newZoom
        onZoomChanged?.invoke(newZoom)
        return newZoom
    }

    /**
     * Called when pinch gesture ends.
     */
    fun onPinchEnd() {
        // Optional: snap to preset zoom levels
    }

    // ==================== Apply to capture request ====================

    /**
     * Apply zoom to capture request builder.
     */
    fun applyToRequest(builder: CaptureRequest.Builder) {
        val zoom = _currentZoom.value

        if (supportsZoomRatio && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Use CONTROL_ZOOM_RATIO on Android 11+
            builder.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
        } else {
            // Use SCALER_CROP_REGION on older devices
            applyCropRegion(builder, zoom)
        }
    }

    /**
     * Apply crop region for legacy zoom implementation.
     */
    private fun applyCropRegion(builder: CaptureRequest.Builder, zoomLevel: Float) {
        val sensor = sensorArraySize ?: return

        if (zoomLevel <= 1.0f) {
            builder.set(CaptureRequest.SCALER_CROP_REGION, sensor)
            return
        }

        val centerX = sensor.width() / 2
        val centerY = sensor.height() / 2

        val cropWidth = (sensor.width() / zoomLevel).roundToInt()
        val cropHeight = (sensor.height() / zoomLevel).roundToInt()

        val cropLeft = centerX - cropWidth / 2
        val cropTop = centerY - cropHeight / 2
        val cropRight = cropLeft + cropWidth
        val cropBottom = cropTop + cropHeight

        val cropRegion = Rect(cropLeft, cropTop, cropRight, cropBottom)
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion)
    }

    /**
     * Get the current crop region for the zoom level (legacy devices).
     */
    fun getCropRegion(): Rect? {
        val sensor = sensorArraySize ?: return null
        val zoom = _currentZoom.value

        if (zoom <= 1.0f) return sensor

        val centerX = sensor.width() / 2
        val centerY = sensor.height() / 2
        val cropWidth = (sensor.width() / zoom).roundToInt()
        val cropHeight = (sensor.height() / zoom).roundToInt()

        return Rect(
            centerX - cropWidth / 2,
            centerY - cropHeight / 2,
            centerX + cropWidth / 2,
            centerY + cropHeight / 2
        )
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
        cancelZoomAnimation()
        _currentZoom.value = MIN_ZOOM
        baseZoomForPinch = MIN_ZOOM
    }

    /**
     * Check if camera supports zoom beyond 1x.
     */
    fun isZoomSupported(): Boolean = maxZoom > 1.0f
}
