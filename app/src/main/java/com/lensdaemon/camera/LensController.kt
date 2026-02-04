package com.lensdaemon.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.SizeF
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Controls multi-lens camera switching and zoom operations.
 * Handles smooth zoom transitions and lens selection logic.
 */
class LensController(
    private val cameraManager: CameraManager,
    private val availableLenses: List<CameraLens>
) {
    // Current lens state
    private val _currentLens = MutableStateFlow<CameraLens?>(null)
    val currentLens: StateFlow<CameraLens?> = _currentLens

    // Current zoom state
    private val _currentZoom = MutableStateFlow(1.0f)
    val currentZoom: StateFlow<Float> = _currentZoom

    // Zoom limits for current lens
    private val _zoomRange = MutableStateFlow(1.0f..1.0f)
    val zoomRange: StateFlow<ClosedFloatingPointRange<Float>> = _zoomRange

    // Smooth zoom animation state
    private var zoomAnimationTarget: Float? = null
    private var zoomAnimationStep = 0.05f

    /**
     * Set the current active lens.
     */
    fun setCurrentLens(lens: CameraLens) {
        _currentLens.value = lens
        updateZoomRange(lens)
        _currentZoom.value = 1.0f  // Reset zoom when switching lenses
        Timber.i("Lens set to: ${lens.lensType.displayName}")
    }

    /**
     * Update zoom range based on lens capabilities.
     */
    private fun updateZoomRange(lens: CameraLens) {
        _zoomRange.value = 1.0f..lens.maxZoom
    }

    /**
     * Get the best lens for a target zoom level.
     * Automatically selects appropriate lens based on zoom factor.
     */
    fun getBestLensForZoom(targetZoom: Float): CameraLens? {
        if (availableLenses.isEmpty()) return null

        // Sort lenses by focal length (zoom factor)
        val sortedLenses = availableLenses.sortedBy { it.focalLength }

        // Find the lens that can achieve the target zoom with minimal digital zoom
        for (lens in sortedLenses.reversed()) {
            val lensBaseFactor = getLensZoomFactor(lens)
            if (lensBaseFactor <= targetZoom) {
                // This lens can achieve the target with digital zoom
                val digitalZoomNeeded = targetZoom / lensBaseFactor
                if (digitalZoomNeeded <= lens.maxZoom) {
                    return lens
                }
            }
        }

        // Fallback to main lens or first available
        return availableLenses.find { it.lensType == LensType.MAIN }
            ?: availableLenses.firstOrNull()
    }

    /**
     * Get the effective zoom factor for a lens (relative to main lens).
     */
    fun getLensZoomFactor(lens: CameraLens): Float {
        val mainLens = availableLenses.find { it.lensType == LensType.MAIN }
        if (mainLens == null || mainLens.focalLength == 0f) {
            return 1.0f
        }
        return lens.focalLength / mainLens.focalLength
    }

    /**
     * Calculate the effective total zoom (optical + digital).
     */
    fun getEffectiveZoom(lens: CameraLens, digitalZoom: Float): Float {
        return getLensZoomFactor(lens) * digitalZoom
    }

    /**
     * Set zoom level with optional smooth animation.
     */
    fun setZoom(zoomLevel: Float, smooth: Boolean = false): Float {
        val lens = _currentLens.value ?: return 1.0f
        val clampedZoom = zoomLevel.coerceIn(_zoomRange.value)

        if (smooth) {
            zoomAnimationTarget = clampedZoom
        } else {
            _currentZoom.value = clampedZoom
            zoomAnimationTarget = null
        }

        return clampedZoom
    }

    /**
     * Get the next zoom step for smooth animation.
     * Call this repeatedly until it returns null.
     */
    fun getNextZoomStep(): Float? {
        val target = zoomAnimationTarget ?: return null
        val current = _currentZoom.value

        if (kotlin.math.abs(target - current) < zoomAnimationStep) {
            _currentZoom.value = target
            zoomAnimationTarget = null
            return target
        }

        val nextZoom = if (target > current) {
            (current + zoomAnimationStep).coerceAtMost(target)
        } else {
            (current - zoomAnimationStep).coerceAtLeast(target)
        }

        _currentZoom.value = nextZoom
        return nextZoom
    }

    /**
     * Check if smooth zoom animation is in progress.
     */
    fun isZoomAnimating(): Boolean = zoomAnimationTarget != null

    /**
     * Cancel any ongoing zoom animation.
     */
    fun cancelZoomAnimation() {
        zoomAnimationTarget = null
    }

    /**
     * Increment zoom by a step amount.
     */
    fun zoomIn(step: Float = 0.5f): Float {
        val newZoom = (_currentZoom.value + step).coerceIn(_zoomRange.value)
        _currentZoom.value = newZoom
        return newZoom
    }

    /**
     * Decrement zoom by a step amount.
     */
    fun zoomOut(step: Float = 0.5f): Float {
        val newZoom = (_currentZoom.value - step).coerceIn(_zoomRange.value)
        _currentZoom.value = newZoom
        return newZoom
    }

    /**
     * Reset zoom to 1x.
     */
    fun resetZoom() {
        _currentZoom.value = 1.0f
        zoomAnimationTarget = null
    }

    /**
     * Get available zoom presets based on available lenses.
     */
    fun getZoomPresets(): List<ZoomPreset> {
        val presets = mutableListOf<ZoomPreset>()

        for (lens in availableLenses.sortedBy { it.focalLength }) {
            val factor = getLensZoomFactor(lens)
            val label = when (lens.lensType) {
                LensType.ULTRA_WIDE -> "0.5x"
                LensType.WIDE -> "0.6x"
                LensType.MAIN -> "1x"
                LensType.TELEPHOTO -> "${factor.roundToInt()}x"
                LensType.SUPER_TELEPHOTO -> "${factor.roundToInt()}x"
                LensType.UNKNOWN -> "${factor}x"
            }
            presets.add(ZoomPreset(lens, factor, label))
        }

        return presets
    }

    /**
     * Get sensor physical size for a camera.
     */
    fun getSensorSize(cameraId: String): SizeF? {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        } catch (e: Exception) {
            Timber.w(e, "Failed to get sensor size for camera $cameraId")
            null
        }
    }

    /**
     * Get field of view for a camera (horizontal, in degrees).
     */
    fun getFieldOfView(cameraId: String): Float? {
        return try {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                ?: return null
            val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                ?: return null
            val focalLength = focalLengths.firstOrNull() ?: return null

            // Calculate horizontal field of view
            // FOV = 2 * arctan(sensorWidth / (2 * focalLength))
            val fovRadians = 2 * kotlin.math.atan((sensorSize.width / (2 * focalLength)).toDouble())
            Math.toDegrees(fovRadians).toFloat()
        } catch (e: Exception) {
            Timber.w(e, "Failed to calculate FOV for camera $cameraId")
            null
        }
    }

    /**
     * Check if lens has optical image stabilization.
     */
    fun hasOpticalStabilization(lens: CameraLens): Boolean = lens.hasOis

    /**
     * Get all lenses of a specific type.
     */
    fun getLensesByType(type: LensType): List<CameraLens> {
        return availableLenses.filter { it.lensType == type }
    }

    /**
     * Get the main (1x) lens.
     */
    fun getMainLens(): CameraLens? {
        return availableLenses.find { it.lensType == LensType.MAIN }
    }

    /**
     * Get the wide angle lens.
     */
    fun getWideLens(): CameraLens? {
        return availableLenses.find { it.lensType == LensType.WIDE }
            ?: availableLenses.find { it.lensType == LensType.ULTRA_WIDE }
    }

    /**
     * Get the telephoto lens.
     */
    fun getTelephotoLens(): CameraLens? {
        return availableLenses.find { it.lensType == LensType.TELEPHOTO }
            ?: availableLenses.find { it.lensType == LensType.SUPER_TELEPHOTO }
    }
}

/**
 * Represents a zoom preset corresponding to a physical lens.
 */
data class ZoomPreset(
    val lens: CameraLens,
    val zoomFactor: Float,
    val label: String
)
