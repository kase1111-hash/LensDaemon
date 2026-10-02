package com.lensdaemon.camera

/**
 * Chooses the auto-exposure frame-rate range for the capture session.
 *
 * Left to itself, auto-exposure lengthens exposures in dim light and lets the
 * frame rate sag to the bottom of a variable range such as [15, 30]. For a
 * live stream that is the wrong trade: the picture judders in OBS, a stream
 * configured for 60 fps never gets more than 30, and the encoder's rate
 * control is tuned for a frame rate it does not receive. A fixed range at the
 * stream's frame rate keeps the cadence constant and lets the camera raise
 * gain instead.
 */
object FrameRatePolicy {

    /**
     * Pick from [available] (the camera's CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
     * the range to request for a stream at [targetFps]:
     *
     *  1. the fixed range [targetFps, targetFps];
     *  2. otherwise a range topping out at [targetFps], with the highest floor;
     *  3. otherwise the nearest range above [targetFps], with the highest floor;
     *  4. otherwise (the camera cannot reach the target) its fastest range.
     *
     * Returns null when the camera lists no ranges.
     */
    fun chooseAeTargetFpsRange(available: List<IntRange>, targetFps: Int): IntRange? {
        if (available.isEmpty()) return null

        available.firstOrNull { it.first == targetFps && it.last == targetFps }?.let { return it }

        available.filter { it.last == targetFps }.maxByOrNull { it.first }?.let { return it }

        val above = available.filter { it.last > targetFps }
        if (above.isNotEmpty()) {
            val nearestTop = above.minOf { it.last }
            return above.filter { it.last == nearestTop }.maxBy { it.first }
        }

        val fastestTop = available.maxOf { it.last }
        return available.filter { it.last == fastestTop }.maxBy { it.first }
    }
}
