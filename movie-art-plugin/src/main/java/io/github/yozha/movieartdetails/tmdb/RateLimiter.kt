package io.github.yozha.movieartdetails.tmdb

/**
 * Client-side limit on the overall request rate: at most [permits] per sliding window of [windowMs].
 * [acquire] blocks the calling thread until a slot frees up; do not call it from the main thread.
 */
class RateLimiter(
    private val permits: Int = 10,
    private val windowMs: Long = 1000,
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val stamps = ArrayDeque<Long>()

    /** @throws InterruptedException if the thread is interrupted while waiting. */
    @Synchronized
    fun acquire() {
        while (true) {
            val now = nowMs()
            while (stamps.isNotEmpty() && now - stamps.first() >= windowMs) stamps.removeFirst()
            if (stamps.size < permits) {
                stamps.addLast(now)
                return
            }
            sleep((windowMs - (now - stamps.first())).coerceAtLeast(1))
        }
    }
}
