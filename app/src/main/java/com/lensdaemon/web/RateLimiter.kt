package com.lensdaemon.web

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Token bucket rate limiter for API endpoints.
 *
 * Each client IP gets a bucket with configurable capacity and refill rate.
 * When a request arrives, a token is consumed. If no tokens remain, the
 * request is rejected with 429 Too Many Requests.
 *
 * Stale buckets are cleaned up periodically to prevent memory leaks, and at
 * most [maxBuckets] clients are tracked: past that, stale buckets go at once
 * and then the ones idle longest, so a client cycling through addresses
 * cannot grow the map without bound.
 */
class RateLimiter(
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val refillPerSecond: Double = DEFAULT_REFILL_PER_SECOND,
    private val cleanupIntervalMs: Long = CLEANUP_INTERVAL_MS,
    private val maxBuckets: Int = MAX_BUCKETS
) {
    companion object {
        private const val TAG = "RateLimiter"

        /** Default: 60 requests burst capacity */
        const val DEFAULT_MAX_TOKENS = 60

        /** Default: 10 requests per second sustained */
        const val DEFAULT_REFILL_PER_SECOND = 10.0

        /** Clean up stale buckets every 5 minutes */
        const val CLEANUP_INTERVAL_MS = 300_000L

        /** Remove buckets idle for more than 10 minutes */
        const val STALE_THRESHOLD_MS = 600_000L

        /** Track at most this many clients */
        const val MAX_BUCKETS = 1024
    }

    private val buckets = ConcurrentHashMap<String, TokenBucket>()
    @Volatile
    private var lastCleanup = System.currentTimeMillis()

    /**
     * Attempt to consume a token for the given client key.
     * Returns true if the request is allowed, false if rate-limited.
     */
    fun tryAcquire(clientKey: String): Boolean {
        cleanupIfNeeded()

        val bucket = buckets.getOrPut(clientKey) {
            TokenBucket(maxTokens, refillPerSecond)
        }
        if (buckets.size > maxBuckets) trimBuckets(keep = clientKey)
        return bucket.tryConsume()
    }

    /**
     * Get remaining tokens for a client (for X-RateLimit-Remaining header).
     */
    fun remainingTokens(clientKey: String): Int {
        val bucket = buckets[clientKey] ?: return maxTokens
        return bucket.availableTokens()
    }

    /**
     * Get seconds until next token is available (for Retry-After header).
     */
    fun retryAfterSeconds(clientKey: String): Int {
        val bucket = buckets[clientKey] ?: return 0
        val available = bucket.availableTokens()
        if (available > 0) return 0
        return (1.0 / refillPerSecond).toInt().coerceAtLeast(1)
    }

    /** Number of tracked clients */
    fun trackedClients(): Int = buckets.size

    /** Reset all rate limit state */
    fun reset() {
        buckets.clear()
    }

    private fun cleanupIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastCleanup < cleanupIntervalMs) return
        lastCleanup = now
        removeStale(now)
    }

    private fun removeStale(now: Long) {
        val staleThreshold = now - STALE_THRESHOLD_MS
        val removed = buckets.entries.removeAll { it.value.lastAccess < staleThreshold }
        if (removed) {
            Timber.tag(TAG).d("Cleaned up stale rate limit buckets, ${buckets.size} remaining")
        }
    }

    /**
     * Bring the map back to [maxBuckets]: stale buckets first, then the ones
     * idle longest, never [keep] (the client asking now). A forgotten client
     * starts again with a full bucket.
     */
    @Synchronized
    private fun trimBuckets(keep: String) {
        if (buckets.size <= maxBuckets) return
        removeStale(System.currentTimeMillis())
        val excess = buckets.size - maxBuckets
        if (excess <= 0) return
        // Read each last access once: buckets keep being used while this sorts
        buckets.entries
            .map { it.key to it.value.lastAccess }
            .filter { it.first != keep }
            .sortedBy { it.second }
            .take(excess)
            .forEach { buckets.remove(it.first) }
        Timber.tag(TAG).w("Tracking too many clients: forgot the $excess idle longest")
    }

    /**
     * Token bucket implementation with lazy refill.
     *
     * Tokens are refilled based on elapsed time since last access,
     * not via a background thread. This avoids timer overhead.
     */
    private class TokenBucket(
        private val maxTokens: Int,
        private val refillPerSecond: Double
    ) {
        private var tokens: Double = maxTokens.toDouble()
        private var lastRefill: Long = System.currentTimeMillis()

        // Read without the lock when the limiter trims its buckets
        @Volatile
        var lastAccess: Long = System.currentTimeMillis()
            private set

        @Synchronized
        fun tryConsume(): Boolean {
            refill()
            lastAccess = System.currentTimeMillis()

            return if (tokens >= 1.0) {
                tokens -= 1.0
                true
            } else {
                false
            }
        }

        @Synchronized
        fun availableTokens(): Int {
            refill()
            return tokens.toInt()
        }

        private fun refill() {
            val now = System.currentTimeMillis()
            val elapsedSeconds = (now - lastRefill) / 1000.0
            tokens = (tokens + elapsedSeconds * refillPerSecond).coerceAtMost(maxTokens.toDouble())
            lastRefill = now
        }
    }
}
