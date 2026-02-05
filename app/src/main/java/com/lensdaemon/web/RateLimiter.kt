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
 * Stale buckets are cleaned up periodically to prevent memory leaks.
 */
class RateLimiter(
    private val maxTokens: Int = DEFAULT_MAX_TOKENS,
    private val refillPerSecond: Double = DEFAULT_REFILL_PER_SECOND,
    private val cleanupIntervalMs: Long = CLEANUP_INTERVAL_MS
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
    }

    private val buckets = ConcurrentHashMap<String, TokenBucket>()
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

        val staleThreshold = now - STALE_THRESHOLD_MS
        val removed = buckets.entries.removeAll { it.value.lastAccess < staleThreshold }
        if (removed) {
            Timber.tag(TAG).d("Cleaned up stale rate limit buckets, ${buckets.size} remaining")
        }
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
