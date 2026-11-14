package com.lensdaemon.camera

/**
 * How often the outputs may be brought back automatically after Android
 * restarted the app (a crash or a process kill): at most `maxAttempts`
 * restarts within the last `windowMinutes`, so a crash loop ends instead of
 * reopening the camera for ever.
 *
 * Works on the wall-clock times (ms) of earlier restarts, which the caller
 * keeps in preferences as text ([encode], [decode]). Pure, so it is unit-tested
 * without Android.
 */
internal object RestartBudget {

    private const val MS_PER_MINUTE = 60_000L

    /**
     * The restarts in [times] that fall inside the window ending at [now]. A
     * window shorter than a minute counts as one minute; times after [now]
     * (the clock was set back) are dropped.
     */
    fun prune(times: List<Long>, now: Long, windowMinutes: Int): List<Long> {
        val since = now - windowMinutes.coerceAtLeast(1) * MS_PER_MINUTE
        return times.filter { it in (since + 1)..now }
    }

    /**
     * Whether one more restart is allowed at [now]: fewer than [maxAttempts]
     * restarts in the window so far. [maxAttempts] of 0 or less means no limit.
     */
    fun allow(times: List<Long>, now: Long, maxAttempts: Int, windowMinutes: Int): Boolean =
        maxAttempts <= 0 || prune(times, now, windowMinutes).size < maxAttempts

    /** [times] as text for preferences, e.g. "1699887600000,1699887660000". */
    fun encode(times: List<Long>): String = times.joinToString(",")

    /** The times [encode] wrote; entries that are not numbers are skipped, and null gives none. */
    fun decode(text: String?): List<Long> =
        text.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
}
