package io.github.yozha.movieartdetails.core

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Interceptor

/**
 * Deadline for a single TMDB request within the remaining T_bg.
 *
 * The shared OkHttpClient's callTimeout (8 s) is armed before interceptors run and can't be shortened for an
 * individual call; a separate client per job would lose TmdbHttpClient's shared state (10 requests/s limit,
 * backoff after 429, blocked key). So [within] opens a deadline scope on the current thread,
 * [interceptor] (installed on the shared client in ServiceLocator) registers every OkHttp call in it,
 * and a watchdog timer cancels them when the deadline passes; the client gets an IOException and reports NETWORK.
 * The response body is read inside [within], so the deadline covers that too.
 * The interceptor also notes when the response headers arrived ([responseAtMs]), so a download can be timed without
 * the rate limiter wait, connection setup and time to first byte.
 */
internal object RequestDeadline {

    private class Scope(val deadlineMs: Long) {
        private val calls = ArrayList<Call>()
        private var expired = false
        /** Monotonic ms at which the last call in this scope received its response headers. */
        @Volatile var responseAtMs: Long? = null

        @Synchronized
        fun attach(call: Call) {
            if (expired) call.cancel() else calls += call
        }

        @Synchronized
        fun expire() {
            expired = true
            calls.forEach { it.cancel() }
            calls.clear()
        }
    }

    private val current = ThreadLocal<Scope?>()

    private val timer: ScheduledExecutorService by lazy {
        ScheduledThreadPoolExecutor(1, ThreadFactory { r -> Thread(r, "mad-deadline").apply { isDaemon = true } })
            .apply { removeOnCancelPolicy = true }
    }

    /** Installed on OkHttpClient via addInterceptor; a no-op outside [within]. */
    val interceptor = Interceptor { chain ->
        val scope = current.get()
        scope?.attach(chain.call())
        chain.proceed(chain.request()).also { scope?.responseAtMs = nowMs() }
    }

    /** Time left in the current thread's scope, in ms; null outside [within]. */
    fun remainingMs(): Long? = current.get()?.let { it.deadlineMs - nowMs() }

    /** Monotonic ms (System.nanoTime based) at which the current scope's last call received its response headers; null if none did. */
    fun responseAtMs(): Long? = current.get()?.responseAtMs

    /** Runs [block]; OkHttp calls inside it are canceled after [timeoutMs] (a nested scope never outlives the outer one). */
    fun <T> within(timeoutMs: Long, block: () -> T): T {
        val outer = current.get()
        val limit = outer?.let { minOf(timeoutMs, it.deadlineMs - nowMs()) } ?: timeoutMs
        val scope = Scope(nowMs() + limit)
        if (limit <= 0) scope.expire()
        current.set(scope)
        val alarm = if (limit > 0) timer.schedule(Runnable { scope.expire() }, limit, TimeUnit.MILLISECONDS) else null
        try {
            return block()
        } finally {
            alarm?.cancel(false)
            if (outer == null) current.remove() else current.set(outer)
        }
    }

    private fun nowMs() = System.nanoTime() / 1_000_000
}
