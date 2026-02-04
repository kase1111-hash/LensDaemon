package com.lensdaemon.camera

import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.util.Size

/**
 * Camera configuration data classes for the Camera2 pipeline.
 */

/**
 * Represents a physical camera lens on the device.
 */
data class CameraLens(
    val cameraId: String,
    val lensFacing: Int,  // CameraCharacteristics.LENS_FACING_*
    val lensType: LensType,
    val focalLength: Float,
    val aperture: Float,
    val hasOis: Boolean,
    val supportedResolutions: List<Size>,
    val maxZoom: Float,
    val physicalCameraId: String? = null  // For logical multi-camera
) {
    val isWide: Boolean get() = lensType == LensType.WIDE
    val isMain: Boolean get() = lensType == LensType.MAIN
    val isTelephoto: Boolean get() = lensType == LensType.TELEPHOTO

    val facingName: String get() = when (lensFacing) {
        CameraCharacteristics.LENS_FACING_BACK -> "Back"
        CameraCharacteristics.LENS_FACING_FRONT -> "Front"
        CameraCharacteristics.LENS_FACING_EXTERNAL -> "External"
        else -> "Unknown"
    }
}

/**
 * Type of camera lens based on focal length.
 */
enum class LensType(val displayName: String, val zoomFactor: String) {
    ULTRA_WIDE("Ultra Wide", "0.5x"),
    WIDE("Wide", "0.6x"),
    MAIN("Main", "1x"),
    TELEPHOTO("Telephoto", "3x"),
    SUPER_TELEPHOTO("Super Telephoto", "5x+"),
    UNKNOWN("Unknown", "?x")
}

/**
 * Current camera capture configuration.
 */
data class CaptureConfig(
    val resolution: Size = Size(1920, 1080),
    val frameRate: Int = 30,
    val focusMode: FocusMode = FocusMode.CONTINUOUS_VIDEO,
    val exposureCompensation: Int = 0,
    val whiteBalance: WhiteBalanceMode = WhiteBalanceMode.AUTO,
    val zoomRatio: Float = 1.0f,
    val stabilizationMode: StabilizationMode = StabilizationMode.OFF
)

/**
 * Focus mode for camera capture.
 */
enum class FocusMode(val camera2Mode: Int) {
    AUTO(CameraMetadata.CONTROL_AF_MODE_AUTO),
    CONTINUOUS_VIDEO(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO),
    CONTINUOUS_PICTURE(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE),
    MANUAL(CameraMetadata.CONTROL_AF_MODE_OFF),
    MACRO(CameraMetadata.CONTROL_AF_MODE_MACRO)
}

/**
 * White balance mode for camera capture.
 */
enum class WhiteBalanceMode(val camera2Mode: Int, val displayName: String) {
    AUTO(CameraMetadata.CONTROL_AWB_MODE_AUTO, "Auto"),
    INCANDESCENT(CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT, "Incandescent"),
    FLUORESCENT(CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT, "Fluorescent"),
    DAYLIGHT(CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT, "Daylight"),
    CLOUDY(CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, "Cloudy"),
    TWILIGHT(CameraMetadata.CONTROL_AWB_MODE_TWILIGHT, "Twilight"),
    SHADE(CameraMetadata.CONTROL_AWB_MODE_SHADE, "Shade")
}

/**
 * Video stabilization mode.
 */
enum class StabilizationMode(val displayName: String) {
    OFF("Off"),
    OPTICAL("OIS"),
    ELECTRONIC("EIS"),
    HYBRID("Hybrid")
}

/**
 * Camera session state.
 */
enum class CameraState {
    CLOSED,
    OPENING,
    OPENED,
    CONFIGURING,
    CONFIGURED,
    PREVIEWING,
    STREAMING,
    ERROR
}

/**
 * Represents capabilities of a camera device.
 */
data class CameraCapabilities(
    val cameraId: String,
    val hardwareLevel: Int,
    val supportedResolutions: List<Size>,
    val supportedFrameRates: List<Int>,
    val supportsManualSensor: Boolean,
    val supportsManualPostProcessing: Boolean,
    val supportsRaw: Boolean,
    val supportsLogicalMultiCamera: Boolean,
    val physicalCameraIds: List<String>,
    val maxZoom: Float,
    val exposureCompensationRange: IntRange,
    val exposureCompensationStep: Float,
    val supportedFocusModes: List<FocusMode>,
    val supportedWhiteBalanceModes: List<WhiteBalanceMode>,
    val hasFlash: Boolean,
    val hasOis: Boolean
) {
    val hardwareLevelName: String get() = when (hardwareLevel) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "Legacy"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "Limited"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "Full"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "Level 3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "External"
        else -> "Unknown"
    }

    val isFullCapable: Boolean get() = hardwareLevel >= CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL
}

/**
 * Frame data from camera capture.
 */
data class CameraFrame(
    val timestamp: Long,
    val width: Int,
    val height: Int,
    val format: Int = ImageFormat.YUV_420_888,
    val data: ByteArray? = null  // Optional raw data for processing
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as CameraFrame
        return timestamp == other.timestamp
    }

    override fun hashCode(): Int = timestamp.hashCode()
}

/**
 * Callback interface for camera events.
 */
interface CameraCallback {
    fun onCameraOpened(cameraId: String)
    fun onCameraClosed(cameraId: String)
    fun onCameraError(cameraId: String, error: CameraError)
    fun onPreviewStarted()
    fun onPreviewStopped()
    fun onFrameAvailable(frame: CameraFrame)
}

/**
 * Camera error types.
 */
enum class CameraError(val message: String) {
    CAMERA_IN_USE("Camera is already in use by another application"),
    MAX_CAMERAS_IN_USE("Maximum number of cameras already in use"),
    CAMERA_DISABLED("Camera is disabled by device policy"),
    CAMERA_DEVICE_ERROR("Camera device encountered a fatal error"),
    CAMERA_SERVICE_ERROR("Camera service encountered a fatal error"),
    PERMISSION_DENIED("Camera permission not granted"),
    CONFIGURATION_FAILED("Failed to configure camera session"),
    UNKNOWN("Unknown camera error")
}
