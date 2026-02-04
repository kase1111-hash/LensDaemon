package com.lensdaemon.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.util.Range
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import kotlin.math.roundToInt

/**
 * Controls camera exposure including auto-exposure, exposure compensation,
 * AE lock, and manual exposure settings.
 */
class ExposureController {

    companion object {
        private const val DEFAULT_METERING_WEIGHT = 1000
        private const val TAP_REGION_SIZE_PERCENT = 0.15f
    }

    // Exposure compensation state
    private val _exposureCompensation = MutableStateFlow(0)
    val exposureCompensation: StateFlow<Int> = _exposureCompensation

    // AE lock state
    private val _aeLocked = MutableStateFlow(false)
    val aeLocked: StateFlow<Boolean> = _aeLocked

    // Exposure state
    private val _exposureState = MutableStateFlow(ExposureState.INACTIVE)
    val exposureState: StateFlow<ExposureState> = _exposureState

    // Current white balance mode
    private val _whiteBalance = MutableStateFlow(WhiteBalanceMode.AUTO)
    val whiteBalance: StateFlow<WhiteBalanceMode> = _whiteBalance

    // Current metering regions
    private var currentAeRegions: Array<MeteringRectangle>? = null

    // Camera capabilities
    private var sensorArraySize: Rect? = null
    private var exposureCompensationRange: Range<Int> = Range(0, 0)
    private var exposureCompensationStep: Float = 0f
    private var supportedWhiteBalanceModes: List<WhiteBalanceMode> = emptyList()

    // Manual exposure settings
    private var supportsManualExposure = false
    private var isoRange: Range<Int>? = null
    private var exposureTimeRange: Range<Long>? = null

    // Current manual settings
    private val _manualIso = MutableStateFlow<Int?>(null)
    val manualIso: StateFlow<Int?> = _manualIso

    private val _manualExposureTime = MutableStateFlow<Long?>(null)
    val manualExposureTime: StateFlow<Long?> = _manualExposureTime

    /**
     * Initialize exposure controller with camera characteristics.
     */
    fun initialize(characteristics: CameraCharacteristics) {
        sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)

        exposureCompensationRange = characteristics.get(
            CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE
        ) ?: Range(0, 0)

        exposureCompensationStep = characteristics.get(
            CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP
        )?.toFloat() ?: 0f

        val awbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)
            ?: intArrayOf()
        supportedWhiteBalanceModes = awbModes.mapNotNull { mode ->
            WhiteBalanceMode.entries.find { it.camera2Mode == mode }
        }

        // Check for manual exposure support
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: intArrayOf()
        supportsManualExposure = capabilities.contains(
            CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR
        )

        if (supportsManualExposure) {
            isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            exposureTimeRange = characteristics.get(
                CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE
            )
        }

        Timber.i("Exposure initialized - EV range: $exposureCompensationRange, " +
                "step: $exposureCompensationStep, manual: $supportsManualExposure")
    }

    /**
     * Set exposure compensation value.
     * @param ev Exposure compensation in EV steps
     */
    fun setExposureCompensation(ev: Int): Boolean {
        val clamped = ev.coerceIn(exposureCompensationRange.lower, exposureCompensationRange.upper)
        _exposureCompensation.value = clamped
        Timber.i("Exposure compensation set to: $clamped")
        return true
    }

    /**
     * Adjust exposure compensation by delta.
     */
    fun adjustExposureCompensation(delta: Int): Int {
        val newValue = (_exposureCompensation.value + delta)
            .coerceIn(exposureCompensationRange.lower, exposureCompensationRange.upper)
        _exposureCompensation.value = newValue
        return newValue
    }

    /**
     * Get exposure compensation value in EV.
     */
    fun getExposureCompensationEv(): Float {
        return _exposureCompensation.value * exposureCompensationStep
    }

    /**
     * Get exposure compensation range.
     */
    fun getExposureCompensationRange(): Range<Int> = exposureCompensationRange

    /**
     * Get exposure compensation step value.
     */
    fun getExposureCompensationStep(): Float = exposureCompensationStep

    /**
     * Lock auto-exposure at current settings.
     */
    fun lockExposure(): Boolean {
        _aeLocked.value = true
        Timber.i("Exposure locked")
        return true
    }

    /**
     * Unlock auto-exposure.
     */
    fun unlockExposure() {
        _aeLocked.value = false
        Timber.i("Exposure unlocked")
    }

    /**
     * Toggle exposure lock.
     */
    fun toggleExposureLock(): Boolean {
        val newState = !_aeLocked.value
        _aeLocked.value = newState
        return newState
    }

    /**
     * Set white balance mode.
     */
    fun setWhiteBalance(mode: WhiteBalanceMode): Boolean {
        if (mode !in supportedWhiteBalanceModes) {
            Timber.w("White balance mode $mode not supported")
            return false
        }
        _whiteBalance.value = mode
        Timber.i("White balance set to: ${mode.displayName}")
        return true
    }

    /**
     * Get supported white balance modes.
     */
    fun getSupportedWhiteBalanceModes(): List<WhiteBalanceMode> = supportedWhiteBalanceModes

    /**
     * Trigger spot metering at the specified normalized coordinates.
     * @param x Normalized X coordinate (0.0 to 1.0)
     * @param y Normalized Y coordinate (0.0 to 1.0)
     * @return The metering regions to apply
     */
    fun triggerSpotMetering(x: Float, y: Float): Array<MeteringRectangle>? {
        val sensor = sensorArraySize ?: return null

        val sensorX = (x * sensor.width()).roundToInt()
        val sensorY = (y * sensor.height()).roundToInt()

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

        currentAeRegions = arrayOf(meteringRect)
        Timber.i("Spot metering at ($x, $y) -> sensor ($sensorX, $sensorY)")

        return currentAeRegions
    }

    /**
     * Reset to center-weighted metering.
     */
    fun resetMetering() {
        currentAeRegions = null
        Timber.i("Metering reset to center-weighted")
    }

    /**
     * Update exposure state from capture result.
     */
    fun updateFromCaptureResult(result: CaptureResult) {
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: return

        val newState = when (aeState) {
            CaptureResult.CONTROL_AE_STATE_INACTIVE -> ExposureState.INACTIVE
            CaptureResult.CONTROL_AE_STATE_SEARCHING -> ExposureState.SEARCHING
            CaptureResult.CONTROL_AE_STATE_CONVERGED -> ExposureState.CONVERGED
            CaptureResult.CONTROL_AE_STATE_LOCKED -> ExposureState.LOCKED
            CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> ExposureState.FLASH_REQUIRED
            CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> ExposureState.PRECAPTURE
            else -> ExposureState.INACTIVE
        }

        if (_exposureState.value != newState) {
            _exposureState.value = newState
        }

        // Capture current ISO and exposure time for display
        result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { iso ->
            if (_manualIso.value == null) {
                // Store for reference but don't update state (auto mode)
            }
        }
    }

    /**
     * Set manual ISO (sensitivity).
     * @param iso ISO value
     */
    fun setManualIso(iso: Int): Boolean {
        if (!supportsManualExposure) {
            Timber.w("Manual exposure not supported")
            return false
        }

        val range = isoRange ?: return false
        val clamped = iso.coerceIn(range.lower, range.upper)
        _manualIso.value = clamped
        Timber.i("Manual ISO set to: $clamped")
        return true
    }

    /**
     * Set manual exposure time.
     * @param nanoseconds Exposure time in nanoseconds
     */
    fun setManualExposureTime(nanoseconds: Long): Boolean {
        if (!supportsManualExposure) {
            Timber.w("Manual exposure not supported")
            return false
        }

        val range = exposureTimeRange ?: return false
        val clamped = nanoseconds.coerceIn(range.lower, range.upper)
        _manualExposureTime.value = clamped
        Timber.i("Manual exposure time set to: $clamped ns")
        return true
    }

    /**
     * Return to auto exposure mode.
     */
    fun setAutoExposure() {
        _manualIso.value = null
        _manualExposureTime.value = null
        _aeLocked.value = false
        Timber.i("Returned to auto exposure")
    }

    /**
     * Check if manual exposure is supported.
     */
    fun isManualExposureSupported(): Boolean = supportsManualExposure

    /**
     * Get ISO range for manual mode.
     */
    fun getIsoRange(): Range<Int>? = isoRange

    /**
     * Get exposure time range for manual mode.
     */
    fun getExposureTimeRange(): Range<Long>? = exposureTimeRange

    /**
     * Get current AE regions.
     */
    fun getAeRegions(): Array<MeteringRectangle>? = currentAeRegions

    /**
     * Reset controller state.
     */
    fun reset() {
        _exposureCompensation.value = 0
        _aeLocked.value = false
        _exposureState.value = ExposureState.INACTIVE
        _whiteBalance.value = WhiteBalanceMode.AUTO
        _manualIso.value = null
        _manualExposureTime.value = null
        currentAeRegions = null
    }

    /**
     * Apply exposure settings to capture request builder.
     */
    fun applyToRequest(builder: CaptureRequest.Builder) {
        // Exposure compensation
        builder.set(
            CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
            _exposureCompensation.value
        )

        // AE lock
        builder.set(CaptureRequest.CONTROL_AE_LOCK, _aeLocked.value)

        // White balance
        builder.set(CaptureRequest.CONTROL_AWB_MODE, _whiteBalance.value.camera2Mode)

        // AE regions
        currentAeRegions?.let { regions ->
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
        }

        // Manual exposure settings
        if (supportsManualExposure) {
            val iso = _manualIso.value
            val exposureTime = _manualExposureTime.value

            if (iso != null || exposureTime != null) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                iso?.let { builder.set(CaptureRequest.SENSOR_SENSITIVITY, it) }
                exposureTime?.let { builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, it) }
            }
        }
    }
}

/**
 * Exposure state enum.
 */
enum class ExposureState {
    INACTIVE,       // AE not active
    SEARCHING,      // Adjusting exposure
    CONVERGED,      // Stable exposure found
    LOCKED,         // Exposure locked
    FLASH_REQUIRED, // Flash recommended
    PRECAPTURE      // Pre-capture metering
}
