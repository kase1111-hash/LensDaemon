package com.lensdaemon.camera

import android.graphics.Rect
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import timber.log.Timber

/**
 * What a capture request needs to know about the open camera to zoom and to
 * place AF/AE regions: the size of its active pixel array (regions and crops
 * are in its coordinates, (0, 0) at its top-left), how many AF and AE regions
 * it takes (0: none), and whether it zooms with CONTROL_ZOOM_RATIO (Android 11+).
 */
internal data class LensGeometry(
    val activeWidth: Int = 0,
    val activeHeight: Int = 0,
    val maxAfRegions: Int = 0,
    val maxAeRegions: Int = 0,
    val zoomRatio: Boolean = false
) {
    companion object {
        /** Before a camera has opened, or when its characteristics can't be read: no zoom crop, no regions. */
        val UNKNOWN = LensGeometry()
    }
}

/** The [LensGeometry] of camera [cameraId], or [LensGeometry.UNKNOWN] when it can't be read. */
internal fun CameraManager.lensGeometry(cameraId: String): LensGeometry = try {
    val characteristics = getCameraCharacteristics(cameraId)
    val active = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
    val zoomRatio = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        characteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null
    } else {
        false
    }
    LensGeometry(
        activeWidth = active?.width() ?: 0,
        activeHeight = active?.height() ?: 0,
        maxAfRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0,
        maxAeRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0,
        zoomRatio = zoomRatio
    )
} catch (e: CameraAccessException) {
    Timber.w(e, "Could not read the active array of camera $cameraId")
    LensGeometry.UNKNOWN
} catch (e: IllegalArgumentException) {
    Timber.w(e, "Unknown camera $cameraId")
    LensGeometry.UNKNOWN
}

/**
 * The SCALER_CROP_REGION that zooms by [zoom] on a camera that zooms by
 * cropping (Android 10, or a camera without CONTROL_ZOOM_RATIO), centred in the
 * active array (see [MeteringRegions.centredCrop]). Null at 1x and below, with
 * zoom ratio support, or when the active array is unknown.
 */
internal fun zoomCrop(zoom: Float, lens: LensGeometry): Rect? {
    val cropsToZoom = !lens.zoomRatio && zoom > 1f
    if (!cropsToZoom || lens.activeWidth <= 0 || lens.activeHeight <= 0) return null
    return MeteringRegions.centredCrop(lens.activeWidth, lens.activeHeight, zoom).toRect()
}

/**
 * Zoom by [zoom]: CONTROL_ZOOM_RATIO where the camera supports it (Android 11+),
 * otherwise a centred SCALER_CROP_REGION, which is how Android 10 zooms.
 */
internal fun CaptureRequest.Builder.applyZoom(zoom: Float, lens: LensGeometry) {
    val crop = zoomCrop(zoom, lens)
    if (crop != null) {
        set(CaptureRequest.SCALER_CROP_REGION, crop)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (lens.zoomRatio && zoom.isFinite() && zoom != 1f) set(CaptureRequest.CONTROL_ZOOM_RATIO, zoom)
    }
}

/**
 * Point autofocus and auto-exposure at [config]'s regions (tap-to-focus, spot
 * metering), on a camera that takes them (CONTROL_MAX_REGIONS_AF/AE > 0), at
 * most as many as it takes. The regions are worked out on the whole active
 * array as the picture shows it: with CONTROL_ZOOM_RATIO the camera relates
 * them to the zoomed picture itself, while with a crop region (Android 10)
 * they are moved into the crop here.
 */
internal fun CaptureRequest.Builder.applyMeteringRegions(config: CaptureConfig, lens: LensGeometry) {
    val crop = zoomCrop(config.zoomRatio, lens)
    config.afRegions?.takeIf { it.isNotEmpty() && lens.maxAfRegions > 0 }?.let { regions ->
        set(CaptureRequest.CONTROL_AF_REGIONS, regions.take(lens.maxAfRegions).map { it.inCrop(crop, lens) }.toTypedArray())
    }
    config.aeRegions?.takeIf { it.isNotEmpty() && lens.maxAeRegions > 0 }?.let { regions ->
        set(CaptureRequest.CONTROL_AE_REGIONS, regions.take(lens.maxAeRegions).map { it.inCrop(crop, lens) }.toTypedArray())
    }
}

/** This region moved into [crop] (see [MeteringRegions.mapIntoCrop]); unchanged without a crop. */
private fun MeteringRectangle.inCrop(crop: Rect?, lens: LensGeometry): MeteringRectangle {
    crop ?: return this
    val mapped = MeteringRegions.mapIntoCrop(
        intArrayOf(x, y, x + width, y + height),
        lens.activeWidth,
        lens.activeHeight,
        intArrayOf(crop.left, crop.top, crop.right, crop.bottom)
    )
    return MeteringRectangle(mapped.toRect(), meteringWeight)
}

/** A (left, top, right, bottom) array as a [Rect]. */
internal fun IntArray.toRect(): Rect = Rect(this[0], this[1], this[2], this[3])
