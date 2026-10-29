package io.github.yozha.movieartdetails.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RateLimiterTest {

    private var t = 0L
    private val sleeps = mutableListOf<Long>()
    private val limiter = RateLimiter(10, 1000, { t }, { sleeps += it; t += it })

    @Test
    fun tenRequestsPassEleventhWaitsForWindow() {
        repeat(10) { limiter.acquire() }
        assertTrue(sleeps.isEmpty())
        limiter.acquire()
        assertEquals(listOf(1000L), sleeps)
    }

    @Test
    fun slidingWindowWaitsOnlyForOldestSlot() {
        repeat(10) { limiter.acquire(); t += 100 }
        // t = 1000: the oldest slot (0) is already outside the window.
        limiter.acquire()
        assertTrue(sleeps.isEmpty())
        t = 1050
        limiter.acquire()
        assertEquals(listOf(50L), sleeps)
    }
}
