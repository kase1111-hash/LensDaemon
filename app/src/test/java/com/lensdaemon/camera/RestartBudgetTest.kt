package com.lensdaemon.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RestartBudget]: how many automatic restarts a crash loop gets.
 */
class RestartBudgetTest {

    private val minute = 60_000L
    private val now = 1_700_000_000_000L

    @Test
    fun `restarts under the maximum are allowed`() {
        assertTrue(RestartBudget.allow(emptyList(), now, maxAttempts = 5, windowMinutes = 10))
        assertTrue(RestartBudget.allow(listOf(now - minute, now - 2 * minute), now, maxAttempts = 3, windowMinutes = 10))
    }

    @Test
    fun `the maximum within the window stops further restarts`() {
        val times = (1..5).map { now - it * minute }
        assertFalse(RestartBudget.allow(times, now, maxAttempts = 5, windowMinutes = 10))
    }

    @Test
    fun `restarts older than the window are pruned and no longer count`() {
        val times = listOf(now - 20 * minute, now - 11 * minute, now - 3 * minute)
        assertEquals(listOf(now - 3 * minute), RestartBudget.prune(times, now, windowMinutes = 10))
        assertTrue(RestartBudget.allow(times, now, maxAttempts = 2, windowMinutes = 10))
        assertFalse(RestartBudget.allow(times, now, maxAttempts = 1, windowMinutes = 10))
    }

    @Test
    fun `times after now are dropped`() {
        assertEquals(emptyList<Long>(), RestartBudget.prune(listOf(now + minute), now, windowMinutes = 10))
    }

    @Test
    fun `zero or fewer attempts means no limit`() {
        val times = List(100) { now - it * 1_000L }
        assertTrue(RestartBudget.allow(times, now, maxAttempts = 0, windowMinutes = 10))
        assertTrue(RestartBudget.allow(times, now, maxAttempts = -1, windowMinutes = 10))
    }

    @Test
    fun `encode and decode round trip`() {
        val times = listOf(now - minute, now)
        assertEquals(times, RestartBudget.decode(RestartBudget.encode(times)))
        assertEquals(emptyList<Long>(), RestartBudget.decode(RestartBudget.encode(emptyList())))
    }

    @Test
    fun `decode skips what is not a time`() {
        assertEquals(emptyList<Long>(), RestartBudget.decode(null))
        assertEquals(listOf(5L, 7L), RestartBudget.decode("x,5, ,7"))
    }
}
