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
 * This adapter lives in :app because it depends on CameraService and LensType.
 * The CameraController interface in :director uses Strings for lens names so the
 * module has no dependency on :app camera types.
 */
class CameraControllerAdapter(
    private val cameraService: CameraService
) : DirectorManager.CameraController {

    companion object {
        private const val TAG = "CameraControllerAdapter"
    }

    // Track current state for change detection
    private var currentLens: String = "main"
    private var currentZoom: Float = 1.0f
    private var currentFocusTarget: FocusTarget = FocusTarget.AUTO

    /**
     * Switch camera lens. Maps String → LensType at the module boundary.
     */
    override fun switchLens(lens: String) {
        if (lens != currentLens) {
            Timber.tag(TAG).d("Switching lens: $currentLens -> $lens")
            cameraService.switchLens(lens)
            currentLens = lens
        }
    }

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
                    cameraService.triggerTapToFocus(0.5f, 0.7f, android.util.Size(1920, 1080))
                }
                FocusTarget.OBJECT -> {
                    cameraService.triggerTapToFocus(0.5f, 0.5f, android.util.Size(1920, 1080))
                }
                FocusTarget.BACKGROUND -> {
                    cameraService.setFocusDistance(0f)
                }
                FocusTarget.MANUAL -> {
                    cameraService.lockFocus()
                }
            }
            currentFocusTarget = target
        }
    }

    override fun setExposurePreset(preset: ExposurePreset) {
        Timber.tag(TAG).d("Setting exposure preset: $preset")

        val evCompensation = when (preset) {
            ExposurePreset.AUTO -> 0
            ExposurePreset.BRIGHT -> 2
            ExposurePreset.DARK -> -2
            ExposurePreset.BACKLIT -> 3
            ExposurePreset.SILHOUETTE -> -3
        }

        cameraService.setExposureCompensation(evCompensation)
    }

    override fun getCurrentTemperature(): Int {
        return cameraService.getCurrentCpuTemperature()
    }

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

    fun isFocusLocked(): Boolean = cameraService.isFocusLocked()

    fun getNormalizedExposure(): Float = cameraService.getNormalizedExposure()

    fun getMotionShakiness(): Float = cameraService.getMotionShakiness()

    fun reset() {
        currentLens = "main"
        currentZoom = 1.0f
        currentFocusTarget = FocusTarget.AUTO
    }

    fun syncWithCamera() {
        currentLens = cameraService.getCurrentLens().value?.lensType?.name?.lowercase() ?: "main"
        currentZoom = cameraService.currentZoom.value
        Timber.tag(TAG).d("Synced with camera: lens=$currentLens, zoom=$currentZoom")
    }
}
