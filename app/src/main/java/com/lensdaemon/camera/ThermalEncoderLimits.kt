package com.lensdaemon.camera

/**
 * The encoder settings the thermal governor's CRITICAL limits allow, worked
 * out on plain numbers so they can be unit-tested without Android.
 *
 * - Resolution: one step down the ladder 4K (and anything above 1080p) ->
 *   1080p -> 720p, measured on the short side so portrait sizes step the same
 *   way. The aspect ratio is kept and both sides stay even. 720p and smaller
 *   are left alone.
 * - Frame rate: at most [MAX_THROTTLED_FPS].
 */
internal object ThermalEncoderLimits {

    /** Highest frame rate while the frame-rate limit is on. */
    const val MAX_THROTTLED_FPS = 24

    private const val FULL_HD = 1080
    private const val HD = 720

    /**
     * [width] x [height] one resolution step down when [reduce] is set, else
     * unchanged: a short side above 1080 becomes 1080, one above 720 becomes
     * 720, and the other side is scaled by the same ratio, rounded down to an
     * even number.
     */
    fun limitedSize(width: Int, height: Int, reduce: Boolean): Pair<Int, Int> {
        val shortSide = minOf(width, height)
        val target = when {
            !reduce -> shortSide
            shortSide > FULL_HD -> FULL_HD
            shortSide > HD -> HD
            else -> shortSide
        }
        if (target == shortSide) return width to height
        return scaled(width, target, shortSide) to scaled(height, target, shortSide)
    }

    /** [fps], capped at [MAX_THROTTLED_FPS] when [reduce] is set. */
    fun limitedFrameRate(fps: Int, reduce: Boolean): Int = if (reduce) minOf(fps, MAX_THROTTLED_FPS) else fps

    /** [side] scaled by [target] / [shortSide], rounded down to an even number (at least 2). */
    private fun scaled(side: Int, target: Int, shortSide: Int): Int {
        val exact = side.toLong() * target / shortSide
        return (exact.toInt() and 1.inv()).coerceAtLeast(2)
    }
}
