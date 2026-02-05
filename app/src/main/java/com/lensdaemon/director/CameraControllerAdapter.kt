package com.lensdaemon.director

import com.lensdaemon.camera.CameraService
import com.lensdaemon.camera.LensType
import timber.log.Timber

/**
 * Camera Controller Adapter
 *
 * Bridges the DirectorManager with CameraService, implementing the
 * CameraController interface to translate director commands to camera operations.
 *
 * This adapter isolates the director module from direct camera dependencies,
 * allowing the director to work with any camera implementation that provides
 * the necessary controls.
 */
class CameraControllerAdapter(
    private val cameraService: CameraService
) : DirectorManager.CameraController {

    companion object {
        private const val TAG = "CameraControllerAdapter"
    }

    // Track current state for change detection
    private var currentLens: LensType = LensType.MAIN
    private var currentZoom: Float = 1.0f
    private var currentFocusTarget: FocusTarget = FocusTarget.AUTO

    /**
     * Switch camera lens
     */
    override fun switchLens(lens: LensType) {
        if (lens != currentLens) {
            Timber.tag(TAG).d("Switching lens: $currentLens -> $lens")
            cameraService.switchLens(lens)
            currentLens = lens
        }
    }

    /**
     * Set zoom level with optional animation
     */
    override fun setZoom(level: Float, animated: Boolean, durationMs: Long) {
        val clampedLevel = level.coerceIn(0.5f, 10.0f)

        if (kotlin.math.abs(clampedLevel - currentZoom) > 0.01f) {
            Timber.tag(TAG).d("Setting zoom: $currentZoom -> $clampedLevel (animated=$animated, duration=${durationMs}ms)")

            if (animated && durationMs > 0) {
                cameraService.animateZoom(clampedLevel, durationMs)
            } else {
                cameraService.setZoom(clampedLevel)
            }
            currentZoom = clampedLevel
        }
    }

    /**
     * Set focus mode based on target
     */
    override fun setFocusMode(target: FocusTarget) {
        if (target != currentFocusTarget) {
            Timber.tag(TAG).d("Setting focus target: $currentFocusTarget -> $target")

            when (target) {
                FocusTarget.AUTO -> {
                    cameraService.setAutoFocus()
                }
                FocusTarget.FACE -> {
                    cameraService.enableFaceDetectionFocus()
                }
                FocusTarget.HANDS -> {
                    // Focus on lower third of frame (hands region)
                    cameraService.triggerTapToFocus(0.5f, 0.7f, android.util.Size(1920, 1080))
                }
                FocusTarget.OBJECT -> {
                    // Focus on center of frame
                    cameraService.triggerTapToFocus(0.5f, 0.5f, android.util.Size(1920, 1080))
                }
                FocusTarget.BACKGROUND -> {
                    // Focus at infinity (background)
                    cameraService.setFocusDistance(0f) // 0 = infinity
                }
                FocusTarget.MANUAL -> {
                    // Lock current focus
                    cameraService.lockFocus()
                }
            }
            currentFocusTarget = target
        }
    }

    /**
     * Set exposure preset
     */
    override fun setExposurePreset(preset: ExposurePreset) {
        Timber.tag(TAG).d("Setting exposure preset: $preset")

        val evCompensation = when (preset) {
            ExposurePreset.AUTO -> 0
            ExposurePreset.BRIGHT -> 2      // +2 EV
            ExposurePreset.DARK -> -2       // -2 EV
            ExposurePreset.BACKLIT -> 3     // +3 EV for backlight compensation
            ExposurePreset.SILHOUETTE -> -3 // -3 EV for silhouette effect
        }

        cameraService.setExposureCompensation(evCompensation)
    }

    /**
     * Get current temperature for thermal monitoring
     */
    override fun getCurrentTemperature(): Int {
        return cameraService.getCurrentCpuTemperature()
    }

    /**
     * Get camera capabilities for shot mapper
     */
    fun getCameraCapabilities(): ShotMapper.CameraCapabilities {
        val lenses = cameraService.getAvailableLenses()

        return ShotMapper.CameraCapabilities(
            hasWide = lenses.any { it.lensType == LensType.WIDE },
            hasMain = lenses.any { it.lensType == LensType.MAIN },
            hasTelephoto = lenses.any { it.lensType == LensType.TELEPHOTO },
            wideFocalLength = lenses.find { it.lensType == LensType.WIDE }?.focalLength ?: 16f,
            mainFocalLength = lenses.find { it.lensType == LensType.MAIN }?.focalLength ?: 24f,
            telephotoFocalLength = lenses.find { it.lensType == LensType.TELEPHOTO }?.focalLength ?: 70f,
            maxDigitalZoom = cameraService.getMaxZoom(),
            supportsSmoothZoom = true,
            supportsFaceDetection = cameraService.supportsFaceDetection(),
            supportsManualFocus = cameraService.supportsManualFocus()
        )
    }

    /**
     * Get current focus state for quality metrics
     */
    fun isFocusLocked(): Boolean {
        return cameraService.isFocusLocked()
    }

    /**
     * Get current exposure value (normalized 0-1)
     */
    fun getNormalizedExposure(): Float {
        return cameraService.getNormalizedExposure()
    }

    /**
     * Get current motion/shake value (0 = stable, 1 = very shaky)
     */
    fun getMotionShakiness(): Float {
        return cameraService.getMotionShakiness()
    }

    /**
     * Reset adapter state
     */
    fun reset() {
        currentLens = LensType.MAIN
        currentZoom = 1.0f
        currentFocusTarget = FocusTarget.AUTO
    }

    /**
     * Sync adapter state with actual camera state
     */
    fun syncWithCamera() {
        currentLens = cameraService.getCurrentLens().value?.lensType ?: LensType.MAIN
        currentZoom = cameraService.currentZoom.value
        Timber.tag(TAG).d("Synced with camera: lens=$currentLens, zoom=$currentZoom")
    }
}
