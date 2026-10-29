package io.github.yozha.movieartdetails.core

/**
 * Exponentially weighted image download throughput, used to size an "original" backdrop to its time slice.
 *
 * The caller samples backdrop downloads only, timed from the response headers when the client exposes them: a logo
 * runs next to the backdrop and shares its bandwidth. Downloads below [MIN_SAMPLE_BYTES] are ignored. A sample below
 * [LARGE_SAMPLE_BYTES] still carries TCP slow start (and the request itself when the headers are not timed), so it
 * may raise the estimate but never lower it: only a large download shows that the link is slower than measured.
 */
internal class ThroughputMeter(private val alpha: Double = 0.3) {

    /** Guarded by this; null until the first sample. */
    private var bytesPerMs: Double? = null

    @Synchronized
    fun record(bytes: Long, elapsedMs: Long) {
        if (bytes < MIN_SAMPLE_BYTES) return
        val sample = bytes.toDouble() / elapsedMs.coerceAtLeast(1)
        val current = bytesPerMs
        bytesPerMs = when {
            current == null -> sample
            sample < current && bytes < LARGE_SAMPLE_BYTES -> current
            else -> current + alpha * (sample - current)
        }
    }

    @Synchronized
    fun bytesPerMs(): Double? = bytesPerMs

    /**
     * The largest response expected to arrive within [budgetMs], capped at [ceiling]; [ceiling] when nothing has been
     * measured yet. Never below [MIN_LIMIT_BYTES], so a single slow sample cannot rule out every image.
     */
    fun limit(budgetMs: Long, ceiling: Long): Long {
        val rate = bytesPerMs() ?: return ceiling
        val bytes = rate * budgetMs.coerceAtLeast(0)
        return if (bytes >= ceiling) ceiling else bytes.toLong().coerceIn(minOf(MIN_LIMIT_BYTES, ceiling), ceiling)
    }

    companion object {
        const val MIN_SAMPLE_BYTES = 256L * 1024
        /** From this size on, a sample may also lower the estimate. */
        const val LARGE_SAMPLE_BYTES = 1024L * 1024
        const val MIN_LIMIT_BYTES = 256L * 1024
    }
}
