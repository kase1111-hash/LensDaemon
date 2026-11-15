package com.lensdaemon.camera

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Where a tap on the picture lands on the camera sensor, and where zooming by
 * cropping moves it.
 *
 * Plain integer maths in active-array coordinates, where (0, 0) is the top-left
 * pixel of SENSOR_INFO_ACTIVE_ARRAY_SIZE, so it is unit-tested without Android.
 * Rectangles are `IntArray(left, top, right, bottom)`, right and bottom
 * exclusive, as in android.graphics.Rect.
 */
internal object MeteringRegions {

    /** The stream's shape (width / height): every encoder preset is 16:9. */
    const val OUTPUT_ASPECT = 16f / 9f

    /** Size of a tap region, as a share of the picture's width and height. */
    const val DEFAULT_SIZE_FRACTION = 0.1f

    private const val CENTRE = 0.5f

    /**
     * The sensor region for a tap at ([x], [y]), each 0..1 across the picture
     * as shown (left to right, top to bottom; out of range is clamped, not a
     * number means the centre).
     *
     * The picture is the centred part of the active array with the output's
     * aspect ([visibleBand]): on a 4:3 sensor and a 16:9 stream, x spans the
     * full width and y the middle band of height width / (16/9). The region is
     * [sizeFraction] of the picture's width and height, moved back inside the
     * picture when the tap is near an edge.
     */
    fun tapRect(
        x: Float,
        y: Float,
        activeWidth: Int,
        activeHeight: Int,
        outputAspect: Float = OUTPUT_ASPECT,
        sizeFraction: Float = DEFAULT_SIZE_FRACTION
    ): IntArray {
        val band = visibleBand(activeWidth, activeHeight, outputAspect)
        val bandWidth = band[2] - band[0]
        val bandHeight = band[3] - band[1]
        val fraction = if (sizeFraction.isFinite()) sizeFraction.coerceIn(0f, 1f) else DEFAULT_SIZE_FRACTION
        val width = (bandWidth * fraction).roundToInt().coerceIn(1, max(1, bandWidth))
        val height = (bandHeight * fraction).roundToInt().coerceIn(1, max(1, bandHeight))
        val centreX = band[0] + (unit(x) * bandWidth).roundToInt()
        val centreY = band[1] + (unit(y) * bandHeight).roundToInt()
        val left = (centreX - width / 2).coerceIn(band[0], max(band[0], band[2] - width))
        val top = (centreY - height / 2).coerceIn(band[1], max(band[1], band[3] - height))
        return intArrayOf(left, top, left + width, top + height)
    }

    /**
     * The part of an active array of [activeWidth] x [activeHeight] that a
     * picture of [outputAspect] (width / height) shows at 1x: the centred band,
     * full width on a sensor taller than the output (4:3 sensor, 16:9 stream),
     * full height on one wider. The whole array when the sizes are unknown.
     */
    fun visibleBand(activeWidth: Int, activeHeight: Int, outputAspect: Float = OUTPUT_ASPECT): IntArray {
        val width = max(0, activeWidth)
        val height = max(0, activeHeight)
        val usableAspect = outputAspect.isFinite() && outputAspect > 0f
        if (width == 0 || height == 0 || !usableAspect) {
            return intArrayOf(0, 0, width, height)
        }
        val bandHeight = min(height, (width / outputAspect).roundToInt())
        if (bandHeight < height) {
            val top = (height - bandHeight) / 2
            return intArrayOf(0, top, width, top + bandHeight)
        }
        val bandWidth = min(width, (height * outputAspect).roundToInt())
        val left = (width - bandWidth) / 2
        return intArrayOf(left, 0, left + bandWidth, height)
    }

    /**
     * The centred SCALER_CROP_REGION that zooms an active array of
     * [activeWidth] x [activeHeight] by [zoom]: width / zoom by height / zoom.
     * A zoom of 1 or less, or not a number, gives the whole array.
     */
    fun centredCrop(activeWidth: Int, activeHeight: Int, zoom: Float): IntArray {
        val factor = if (zoom.isFinite() && zoom > 1f) zoom else 1f
        val width = max(0, activeWidth)
        val height = max(0, activeHeight)
        val cropWidth = (width / factor).roundToInt().coerceIn(min(1, width), width)
        val cropHeight = (height / factor).roundToInt().coerceIn(min(1, height), height)
        val left = (width - cropWidth) / 2
        val top = (height - cropHeight) / 2
        return intArrayOf(left, top, left + cropWidth, top + cropHeight)
    }

    /**
     * [rect], worked out on the whole active array as if it were the picture,
     * moved into [crop]: where it lies when the picture shows only the crop.
     * Zoom by SCALER_CROP_REGION (Android 10) needs this, because regions are
     * absolute there; with CONTROL_ZOOM_RATIO the camera does it itself.
     * x' = crop.left + x * crop.width / activeWidth, and the same for y. The
     * result is at least one pixel wide and high.
     */
    fun mapIntoCrop(rect: IntArray, activeWidth: Int, activeHeight: Int, crop: IntArray): IntArray {
        if (activeWidth <= 0 || activeHeight <= 0) return rect.copyOf()
        val left = scale(rect[0], crop[0], crop[2] - crop[0], activeWidth)
        val top = scale(rect[1], crop[1], crop[3] - crop[1], activeHeight)
        val right = max(left + 1, scale(rect[2], crop[0], crop[2] - crop[0], activeWidth))
        val bottom = max(top + 1, scale(rect[3], crop[1], crop[3] - crop[1], activeHeight))
        return intArrayOf(left, top, right, bottom)
    }

    /** [value] on a 0..[full] axis, placed on the axis that starts at [start] and is [length] long. */
    private fun scale(value: Int, start: Int, length: Int, full: Int): Int =
        start + (value.toLong() * length / full).toInt()

    /** [value] as 0..1: clamped, with not-a-number taken as the centre. */
    private fun unit(value: Float): Float = if (value.isNaN()) CENTRE else value.coerceIn(0f, 1f)
}
