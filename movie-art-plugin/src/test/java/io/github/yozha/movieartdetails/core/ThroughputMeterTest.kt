package io.github.yozha.movieartdetails.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThroughputMeterTest {

    private val kb = 1024L
    private val mb = 1024L * 1024

    @Test
    fun `without samples the ceiling applies`() {
        val meter = ThroughputMeter()
        assertNull(meter.bytesPerMs())
        assertEquals(8 * mb, meter.limit(5_000, 8 * mb))
    }

    @Test
    fun `small downloads measure latency and are ignored`() {
        val meter = ThroughputMeter()
        meter.record(30 * kb, 300)
        meter.record(200 * kb, 400)
        assertNull(meter.bytesPerMs())
        meter.record(ThroughputMeter.MIN_SAMPLE_BYTES, 500)
        assertEquals(ThroughputMeter.MIN_SAMPLE_BYTES / 500.0, meter.bytesPerMs()!!, 1e-9)
    }

    @Test
    fun `estimate is an exponentially weighted average`() {
        val meter = ThroughputMeter(alpha = 0.5)
        meter.record(2 * mb, 2048) // 1024 bytes/ms
        assertEquals(1024.0, meter.bytesPerMs()!!, 1e-9)
        meter.record(2 * mb, 4096) // 512 bytes/ms
        assertEquals(768.0, meter.bytesPerMs()!!, 1e-9)
    }

    @Test
    fun `small samples do not lower the limit a large download established`() {
        val meter = ThroughputMeter()
        meter.record(4 * mb, 2_000) // A 4 MB original in 2 s.
        val established = meter.limit(4_500, 8 * mb)
        // Latency-dominated w1280 downloads, slower per byte than the original.
        repeat(10) { meter.record(400 * kb, 1_000) }
        assertEquals(established, meter.limit(4_500, 8 * mb))
    }

    @Test
    fun `a faster small sample raises the estimate`() {
        val meter = ThroughputMeter(alpha = 0.5)
        meter.record(2 * mb, 4096) // 512 bytes/ms
        meter.record(512 * kb, 512) // 1024 bytes/ms
        assertEquals(768.0, meter.bytesPerMs()!!, 1e-9)
    }

    @Test
    fun `a slower large sample lowers the estimate`() {
        val meter = ThroughputMeter()
        meter.record(4 * mb, 2_000)
        val before = meter.bytesPerMs()!!
        meter.record(3 * mb, 6_000)
        assertTrue(meter.bytesPerMs()!! < before)
    }

    @Test
    fun `limit is what fits in the budget, between the floor and the ceiling`() {
        val meter = ThroughputMeter()
        meter.record(500 * kb, 1000) // 512 bytes/ms
        assertEquals(512L * 4000, meter.limit(4_000, 8 * mb))
        // A fast link is capped at the ceiling.
        assertEquals(mb, meter.limit(10_000, mb))
        // A tiny budget still allows a small image.
        assertEquals(ThroughputMeter.MIN_LIMIT_BYTES, meter.limit(10, 8 * mb))
        assertEquals(ThroughputMeter.MIN_LIMIT_BYTES, meter.limit(-5, 8 * mb))
    }

    @Test
    fun `zero elapsed time does not divide by zero`() {
        val meter = ThroughputMeter()
        meter.record(300 * kb, 0)
        assertEquals(300.0 * kb, meter.bytesPerMs()!!, 1e-9)
    }
}
