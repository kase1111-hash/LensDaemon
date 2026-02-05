package com.lensdaemon.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RateLimiterTest {

    private lateinit var limiter: RateLimiter

    @Before
    fun setUp() {
        limiter = RateLimiter(
            maxTokens = 5,
            refillPerSecond = 1.0,
            cleanupIntervalMs = 60_000L
        )
    }

    @Test
    fun `first request is always allowed`() {
        assertTrue(limiter.tryAcquire("192.168.1.1"))
    }

    @Test
    fun `requests within burst capacity are allowed`() {
        val client = "192.168.1.1"
        repeat(5) {
            assertTrue("Request ${it + 1} should be allowed", limiter.tryAcquire(client))
        }
    }

    @Test
    fun `request beyond burst capacity is rejected`() {
        val client = "192.168.1.1"
        // Exhaust all tokens
        repeat(5) { limiter.tryAcquire(client) }
        // Next should be rejected
        assertFalse(limiter.tryAcquire(client))
    }

    @Test
    fun `different clients have independent limits`() {
        val client1 = "192.168.1.1"
        val client2 = "192.168.1.2"

        // Exhaust client1
        repeat(5) { limiter.tryAcquire(client1) }
        assertFalse(limiter.tryAcquire(client1))

        // Client2 should still work
        assertTrue(limiter.tryAcquire(client2))
    }

    @Test
    fun `tokens refill over time`() {
        val client = "192.168.1.1"

        // Exhaust all tokens
        repeat(5) { limiter.tryAcquire(client) }
        assertFalse(limiter.tryAcquire(client))

        // Wait for refill (1 token per second, sleep 1.1s for safety)
        Thread.sleep(1100)

        // Should have at least 1 token now
        assertTrue(limiter.tryAcquire(client))
    }

    @Test
    fun `remaining tokens reflects usage`() {
        val client = "192.168.1.1"

        // Fresh client should have max tokens
        assertEquals(5, limiter.remainingTokens(client))

        // After consuming some
        limiter.tryAcquire(client)
        limiter.tryAcquire(client)
        assertEquals(3, limiter.remainingTokens(client))
    }

    @Test
    fun `retryAfterSeconds returns 0 when tokens available`() {
        val client = "192.168.1.1"
        assertEquals(0, limiter.retryAfterSeconds(client))
    }

    @Test
    fun `retryAfterSeconds returns positive when exhausted`() {
        val client = "192.168.1.1"
        repeat(5) { limiter.tryAcquire(client) }
        assertTrue(limiter.retryAfterSeconds(client) >= 1)
    }

    @Test
    fun `trackedClients counts unique clients`() {
        assertEquals(0, limiter.trackedClients())

        limiter.tryAcquire("client1")
        assertEquals(1, limiter.trackedClients())

        limiter.tryAcquire("client2")
        assertEquals(2, limiter.trackedClients())

        // Same client again
        limiter.tryAcquire("client1")
        assertEquals(2, limiter.trackedClients())
    }

    @Test
    fun `reset clears all state`() {
        limiter.tryAcquire("client1")
        limiter.tryAcquire("client2")
        assertEquals(2, limiter.trackedClients())

        limiter.reset()
        assertEquals(0, limiter.trackedClients())
    }

    @Test
    fun `default constructor uses reasonable defaults`() {
        val defaultLimiter = RateLimiter()
        // Should allow at least 60 requests burst
        val client = "test"
        var allowed = 0
        repeat(60) {
            if (defaultLimiter.tryAcquire(client)) allowed++
        }
        assertEquals(60, allowed)
    }
}
