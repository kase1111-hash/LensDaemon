package com.lensdaemon.camera

import android.graphics.PointF
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.util.Size
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Controls camera focus including tap-to-focus, continuous autofocus,
 * and manual focus distance.
 */
class FocusController {

    companion object {
        // Metering region size as percentage of sensor area
        private const val DEFAULT_METERING_WEIGHT = 1000
        private const val TAP_REGION_SIZE_PERCENT = 0.1f
        private const val FOCUS_LOCK_TIMEOUT_MS = 3000L
    }

    // Focus state
    private val _focusState = MutableStateFlow(FocusState.INACTIVE)
    val focusState: StateFlow<FocusState> = _focusState

    // Current focus mode
    private val _focusMode = MutableStateFlow(FocusMode.CONTINUOUS_VIDEO)
    val focusMode: StateFlow<FocusMode> = _focusMode

    // Focus locked state
    private val _focusLocked = MutableStateFlow(false)
    val focusLocked: StateFlow<Boolean> = _focusLocked

    // Manual focus distance (0.0 = infinity, 1.0 = closest)
    private val _focusDistance = MutableStateFlow(0f)
    val focusDistance: StateFlow<Float> = _focusDistance

    // Current metering regions
    private var currentAfRegions: Array<MeteringRectangle>? = null

    // Sensor array size for coordinate conversion
    private var sensorArraySize: Rect? = null

    // Supported focus modes for current camera
    private var supportedFocusModes: List<FocusMode> = emptyList()

    // Minimum focus distance (diopters) - 0 means infinity only
    private var minimumFocusDistance: Float = 0f

    /**
     * Initialize focus controller with camera characteristics.
     */
    fun initialize(characteristics: CameraCharacteristics) {
        sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        val afModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
            ?: intArrayOf()
        supportedFocusModes = afModes.mapNotNull { mode ->
            FocusMode.entries.find { it.camera2Mode == mode }
        }

        minimumFocusDistance = characteristics.get(
            CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
        ) ?: 0f

        Timber.i("Focus initialized - modes: $supportedFocusModes, minDist: $minimumFocusDistance")
    }

    /**
     * Set the focus mode.
     */
    fun setFocusMode(mode: FocusMode): Boolean {
        if (mode !in supportedFocusModes) {
            Timber.w("Focus mode $mode not supported")
            return false
        }
        _focusMode.value = mode
        _focusLocked.value = false
        currentAfRegions = null
        Timber.i("Focus mode set to: $mode")
        return true
    }

    /**
     * Trigger tap-to-focus at the specified normalized coordinates.
     * @param x Normalized X coordinate (0.0 to 1.0, left to right)
     * @param y Normalized Y coordinate (0.0 to 1.0, top to bottom)
     * @param previewSize The size of the preview for coordinate conversion
     * @return The focus mode to apply, or null if not supported
     */
    fun triggerTapToFocus(x: Float, y: Float, previewSize: Size): FocusTriggerResult? {
        val sensor = sensorArraySize ?: run {
            Timber.w("Sensor array size not initialized")
            return null
        }

        if (FocusMode.AUTO !in supportedFocusModes) {
            Timber.w("Auto focus not supported")
            return null
        }

        // Convert normalized coordinates to sensor coordinates
        val sensorX = (x * sensor.width()).roundToInt()
        val sensorY = (y * sensor.height()).roundToInt()

        // Calculate metering region
        val regionWidth = (sensor.width() * TAP_REGION_SIZE_PERCENT).roundToInt()
        val regionHeight = (sensor.height() * TAP_REGION_SIZE_PERCENT).roundToInt()

        val left = (sensorX - regionWidth / 2).coerceIn(0, sensor.width() - regionWidth)
        val top = (sensorY - regionHeight / 2).coerceIn(0, sensor.height() - regionHeight)
        val right = left + regionWidth
        val bottom = top + regionHeight

        val meteringRect = MeteringRectangle(
            Rect(left, top, right, bottom),
            DEFAULT_METERING_WEIGHT
        )

        currentAfRegions = arrayOf(meteringRect)
        _focusState.value = FocusState.SCANNING
        _focusMode.value = FocusMode.AUTO

        Timber.i("Tap-to-focus at ($x, $y) -> sensor ($sensorX, $sensorY)")

        return FocusTriggerResult(
            mode = FocusMode.AUTO,
            regions = currentAfRegions!!,
            triggerAction = CaptureRequest.CONTROL_AF_TRIGGER_START
        )
    }

    /**
     * Lock focus at current position.
     */
    fun lockFocus(): FocusTriggerResult? {
        if (FocusMode.AUTO !in supportedFocusModes) {
            return null
        }

        _focusLocked.value = true
        _focusState.value = FocusState.SCANNING

        return FocusTriggerResult(
            mode = FocusMode.AUTO,
            regions = currentAfRegions,
            triggerAction = CaptureRequest.CONTROL_AF_TRIGGER_START
        )
    }

    /**
     * Unlock focus and return to continuous mode.
     */
    fun unlockFocus(): FocusTriggerResult {
        _focusLocked.value = false
        currentAfRegions = null
        _focusState.value = FocusState.INACTIVE

        val mode = if (FocusMode.CONTINUOUS_VIDEO in supportedFocusModes) {
            FocusMode.CONTINUOUS_VIDEO
        } else {
            FocusMode.AUTO
        }
        _focusMode.value = mode

        return FocusTriggerResult(
            mode = mode,
            regions = null,
            triggerAction = CaptureRequest.CONTROL_AF_TRIGGER_CANCEL
        )
    }

    /**
     * Set manual focus distance.
     * @param distance Focus distance from 0.0 (infinity) to 1.0 (closest)
     */
    fun setManualFocusDistance(distance: Float): Boolean {
        if (FocusMode.MANUAL !in supportedFocusModes) {
            Timber.w("Manual focus not supported")
            return false
        }

        if (minimumFocusDistance == 0f) {
            Timber.w("Camera only supports infinity focus")
            return false
        }

        _focusMode.value = FocusMode.MANUAL
        _focusDistance.value = distance.coerceIn(0f, 1f)
        _focusLocked.value = false
        currentAfRegions = null

        Timber.i("Manual focus distance set to: $distance")
        return true
    }

    /**
     * Convert focus distance (0-1) to diopters for CaptureRequest.
     */
    fun getFocusDistanceInDiopters(): Float {
        return _focusDistance.value * minimumFocusDistance
    }

    /**
     * Update focus state from capture result.
     */
    fun updateFromCaptureResult(result: CaptureResult) {
        val afState = result.get(CaptureResult.CONTROL_AF_STATE) ?: return

        val newState = when (afState) {
            CaptureResult.CONTROL_AF_STATE_INACTIVE -> FocusState.INACTIVE
            CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN,
            CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN -> FocusState.SCANNING
            CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED -> FocusState.FOCUSED
            CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED,
            CaptureResult.CONTROL_AF_STATE_PASSIVE_UNFOCUSED -> FocusState.FAILED
            else -> FocusState.INACTIVE
        }

        if (_focusState.value != newState) {
            _focusState.value = newState

            // Update locked state for locked results
            if (afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                _focusLocked.value = true
            }
        }
    }

    /**
     * Get the current AF regions for capture request.
     */
    fun getAfRegions(): Array<MeteringRectangle>? = currentAfRegions

    /**
     * Check if tap-to-focus is supported.
     */
    fun isTapToFocusSupported(): Boolean = FocusMode.AUTO in supportedFocusModes

    /**
     * Check if manual focus is supported.
     */
    fun isManualFocusSupported(): Boolean =
        FocusMode.MANUAL in supportedFocusModes && minimumFocusDistance > 0f

    /**
     * Get supported focus modes.
     */
    fun getSupportedFocusModes(): List<FocusMode> = supportedFocusModes

    /**
     * Reset focus controller state.
     */
    fun reset() {
        _focusState.value = FocusState.INACTIVE
        _focusMode.value = FocusMode.CONTINUOUS_VIDEO
        _focusLocked.value = false
        _focusDistance.value = 0f
        currentAfRegions = null
    }

    /**
     * Apply focus settings to capture request builder.
     */
    fun applyToRequest(builder: CaptureRequest.Builder) {
        val mode = _focusMode.value

        builder.set(CaptureRequest.CONTROL_AF_MODE, mode.camera2Mode)

        currentAfRegions?.let { regions ->
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
        }

        if (mode == FocusMode.MANUAL && minimumFocusDistance > 0f) {
            builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, getFocusDistanceInDiopters())
        }
    }
}

/**
 * Focus state enum.
 */
enum class FocusState {
    INACTIVE,   // Focus not active
    SCANNING,   // Searching for focus
    FOCUSED,    // Successfully focused
    FAILED      // Failed to focus
}

/**
 * Result of triggering focus operation.
 */
data class FocusTriggerResult(
    val mode: FocusMode,
    val regions: Array<MeteringRectangle>?,
    val triggerAction: Int
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as FocusTriggerResult
        return mode == other.mode &&
               regions.contentEquals(other.regions) &&
               triggerAction == other.triggerAction
    }

    override fun hashCode(): Int {
        var result = mode.hashCode()
        result = 31 * result + (regions?.contentHashCode() ?: 0)
        result = 31 * result + triggerAction
        return result
    }
}
