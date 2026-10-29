package io.github.yozha.movieartdetails.core

import io.github.yozha.movieartdetails.match.SourceHints
import io.github.yozha.movieartdetails.model.Diagnostics
import java.io.File
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

/** Program card from the event: raw title (trimmed), type and iconUri. */
data class FocusedCard(val title: String, val type: Int?, val iconUri: String?)

/** Ready composite for the host reply. */
data class Composite(val file: File, val title: String, val sourceUrl: String)

/** Result of matching and preparing a card. [note] is a short diagnostics label that never contains titles. */
sealed interface Outcome {
    val note: String

    data class Ready(val composite: Composite, override val note: String) : Outcome
    /** Card is not shown: skipped, manually excluded, low confidence, key error. */
    data class Rejected(override val note: String) : Outcome
    /** Transient failure (network, T_bg, image): neutral background, no negative cache entry. */
    data class Failed(override val note: String) : Outcome
    /** Only from [CardResolver.quick]: preparation is needed; [jobKey] deduplicates identical jobs. */
    data class Pending(val jobKey: String, override val note: String = "pending") : Outcome

    companion object {
        /** [Failed] label: the job was aborted because its card lost focus. */
        const val STALE = "stale"
    }
}

/** Preparation job state exposed to the pipeline. */
interface JobContext {
    /** This job's card is still focused (retries after 429/5xx happen only for it). */
    fun isCurrent(): Boolean
    /** Time left in T_bg; ≤ 0 means stop. */
    fun remainingMs(): Long
}

/** Matching and preparation. Implementation: [CardPipeline]. */
interface CardResolver {
    /** No network, on the binder thread: Ready (composite in the level 2 cache), Rejected or Pending. */
    fun quick(card: FocusedCard): Outcome
    /** Full preparation on a pool thread: Ready, Rejected or Failed. */
    fun prepare(card: FocusedCard, ctx: JobContext): Outcome
}

/** Host bridge. Implementation: [ProjectivyHost]. */
interface HostBridge {
    /** IMAGE wallpaper with a content URI; read permission is granted to Projectivy on every call. */
    fun wallpaper(composite: Composite): Wallpaper
    /** false when the screen is off: no broadcast is sent. */
    fun canNotify(): Boolean
    /** Broadcast WALLPAPER_PROVIDER_UPDATED, reason DATA_CHANGED. */
    fun notifyUpdated()
}

/**
 * Result delivery (short blocking wait, then a broadcast) and the reply table.
 *
 * - Every focus event gets a new generation and becomes the "current card"; a waiting call from the
 *   previous generation wakes up immediately and replies with a neutral background (never hold the binder thread).
 * - ProgramCardFocused: composite cached → reply at once; rejected → neutral; otherwise a pool job
 *   (one per jobKey) and a wait of at most [tBlockMs]; on timeout → neutral, and once the job is ready,
 *   if the generation is still current, one broadcast per generation.
 * - TimeElapsed: background for the current state; the same composite → the same URI.
 * - CardFocused: new generation, neutral COLOR (tinted with darkColor if enabled in settings).
 * - null and any other event → empty list; any internal error → neutral COLOR.
 */
@Suppress("WrongConstant")
class Coordinator(
    private val resolver: CardResolver,
    private val host: HostBridge,
    /** Bounded preparation pool (2 threads); manual or synchronous in tests. */
    private val executor: Executor,
    private val diagnostics: Diagnostics,
    private val neutralColor: () -> String,
    private val tintFromCard: () -> Boolean = { false },
    private val logTitles: () -> Boolean = { false },
    private val tBlockMs: Long = T_BLOCK_MS,
    private val tBgMs: Long = T_BG_MS,
    /** Monotonic milliseconds. */
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {

    private class CardState(val generation: Long, val card: FocusedCard?) {
        // Everything below is guarded by lock.
        var jobKey: String? = null
        var outcome: Outcome? = null
        /** A binder call of this generation is waiting for the result: no broadcast needed, it will reply itself. */
        var waiting = false
        /** The ready composite of this generation has already been returned to the host. */
        var delivered = false
        var broadcastSent = false
    }

    /** [restarts]: how many times the job was restarted after "stale" when focus returned to the card. */
    private inner class Job(val key: String, val card: FocusedCard, val restarts: Int = 0) : JobContext {
        @Volatile var deadline = Long.MAX_VALUE
        override fun isCurrent() = lock.withLock { current?.jobKey == key }
        override fun remainingMs() = deadline - monoMs()
    }

    private class Trace(var generation: Long = 0, var note: String = "")

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    // Guarded by lock.
    private var generation = 0L
    private var current: CardState? = null
    private val jobs = HashMap<String, Job>()

    /** Entry point of the getWallpapers binder call. Never throws. */
    fun getWallpapers(event: Event?): List<Wallpaper> {
        if (event == null) return emptyList()
        if (event !is Event.ProgramCardFocused && event !is Event.CardFocused && event !is Event.TimeElapsed) return emptyList()
        val started = monoMs()
        val trace = Trace()
        val result = try {
            when (event) {
                is Event.ProgramCardFocused -> onProgramCard(event, trace)
                is Event.CardFocused -> onAppCard(event, trace)
                else -> onTimeElapsed(trace)
            }
        } catch (t: Throwable) {
            trace.note = "error:${t.javaClass.simpleName}"
            neutral()
        }
        log(event, trace, result, monoMs() - started)
        return result
    }

    private fun onProgramCard(event: Event.ProgramCardFocused, trace: Trace): List<Wallpaper> {
        val card = FocusedCard(event.title?.trim().orEmpty(), event.type, event.iconUri)
        val state = newGeneration(card)
        trace.generation = state.generation
        if (card.title.isEmpty()) {
            trace.note = "no-title"
            settle(state, Outcome.Rejected(trace.note))
            return neutral()
        }
        val quick = resolver.quick(card)
        trace.note = quick.note
        return when (quick) {
            is Outcome.Ready -> {
                lock.withLock {
                    if (current === state) {
                        state.outcome = quick
                        state.delivered = true
                    }
                }
                listOf(host.wallpaper(quick.composite))
            }
            is Outcome.Rejected, is Outcome.Failed -> {
                settle(state, quick)
                neutral()
            }
            is Outcome.Pending -> {
                when (val out = awaitPreparation(state, quick.jobKey)) {
                    is Outcome.Ready -> {
                        trace.note = "ready<T_block:${out.note}"
                        listOf(host.wallpaper(out.composite))
                    }
                    null -> {
                        trace.note = "neutral>T_block"
                        neutral()
                    }
                    else -> {
                        trace.note = out.note
                        neutral()
                    }
                }
            }
        }
    }

    private fun onAppCard(event: Event.CardFocused, trace: Trace): List<Wallpaper> {
        trace.generation = newGeneration(null).generation
        trace.note = "app"
        val tint = if (event.darkColor != 0 && tintFromCard()) colorHex(event.darkColor) else null
        return neutral(tint)
    }

    private fun onTimeElapsed(trace: Trace): List<Wallpaper> {
        var restart: Job? = null
        val ready: Composite? = lock.withLock {
            val state = current
            trace.generation = state?.generation ?: 0
            val card = state?.card
            if (state == null || card == null) {
                trace.note = "te:no-card"
                return@withLock null
            }
            val out = state.outcome
            when {
                out is Outcome.Ready && out.composite.file.isFile -> {
                    state.delivered = true
                    trace.note = "te:ready"
                    out.composite
                }
                out == null -> {
                    trace.note = "te:pending"
                    null
                }
                out is Outcome.Rejected -> {
                    trace.note = "te:rejected"
                    null
                }
                else -> {
                    // Transient failure or the composite was deleted (cache cleared): retry without waiting.
                    trace.note = "te:retry"
                    state.outcome = null
                    restart = state.jobKey?.let { ensureJobLocked(it, card) }
                    null
                }
            }
        }
        restart?.let(::start)
        return if (ready != null) listOf(host.wallpaper(ready)) else neutral()
    }

    private fun newGeneration(card: FocusedCard?): CardState = lock.withLock {
        CardState(++generation, card).also {
            current = it
            changed.signalAll()
        }
    }

    private fun settle(state: CardState, outcome: Outcome) = lock.withLock {
        if (current === state) state.outcome = outcome
    }

    /** Starts (or joins) a job and waits for it up to T_block. Returns null if it didn't finish in time or the generation changed. */
    private fun awaitPreparation(state: CardState, jobKey: String): Outcome? {
        val card = state.card ?: return null
        val job = lock.withLock {
            if (current !== state) return null
            state.jobKey = jobKey
            // Set before starting: a synchronous executor may finish the job before we begin waiting.
            state.waiting = true
            ensureJobLocked(jobKey, card)
        }
        try {
            job?.let(::start)
        } catch (t: Throwable) {
            lock.withLock { state.waiting = false }
            throw t
        }
        return lock.withLock {
            try {
                var left = TimeUnit.MILLISECONDS.toNanos(tBlockMs)
                while (current === state && state.outcome == null && left > 0) left = changed.awaitNanos(left)
            } finally {
                state.waiting = false
            }
            val out = state.outcome
            when {
                current !== state -> null
                out is Outcome.Ready -> out.also { state.delivered = true }
                else -> out
            }
        }
    }

    /** Guarded by lock. Creates a job if none exists; null if one is already running (the current generation gets its result). */
    private fun ensureJobLocked(key: String, card: FocusedCard, restarts: Int = 0): Job? {
        if (jobs.containsKey(key)) return null
        return Job(key, card, restarts).also { jobs[key] = it }
    }

    private fun start(job: Job) {
        try {
            executor.execute { run(job) }
        } catch (t: Throwable) {
            complete(job, Outcome.Failed("rejected-exec"))
        }
    }

    private fun run(job: Job) {
        val outcome = try {
            // A job whose card lost focus while queued is skipped, so fast scrolling doesn't clog the pool.
            if (!job.isCurrent()) {
                Outcome.Failed(Outcome.STALE)
            } else {
                job.deadline = monoMs() + tBgMs
                resolver.prepare(job.card, job)
            }
        } catch (t: Throwable) {
            Outcome.Failed("error:${t.javaClass.simpleName}")
        }
        complete(job, outcome)
    }

    private fun complete(job: Job, outcome: Outcome) {
        var notify = false
        var generationOf = 0L
        var restart: Job? = null
        lock.withLock {
            if (jobs[job.key] === job) jobs.remove(job.key)
            val state = current
            // A stale result isn't published (it's already cached and will be reused if focus returns to the card).
            if (state == null || state.jobKey != job.key || state.outcome != null) return@withLock
            // The job aborted as stale, but focus has already returned to the card and is waiting on the same jobKey:
            // don't store "stale" as the outcome (that would mean a neutral background until TimeElapsed); restart instead.
            val card = state.card
            if (outcome is Outcome.Failed && outcome.note == Outcome.STALE && card != null && job.restarts < MAX_STALE_RESTARTS) {
                restart = ensureJobLocked(job.key, card, job.restarts + 1)
                return@withLock
            }
            state.outcome = outcome
            changed.signalAll()
            if (outcome is Outcome.Ready && !state.waiting && !state.delivered && !state.broadcastSent) {
                state.broadcastSent = true
                notify = true
                generationOf = state.generation
            }
        }
        restart?.let(::start)
        if (!notify) return
        val line = try {
            if (host.canNotify()) {
                host.notifyUpdated()
                "g=$generationOf broadcast ${outcome.note}"
            } else {
                "g=$generationOf broadcast skipped: screen off"
            }
        } catch (t: Throwable) {
            "g=$generationOf broadcast error:${t.javaClass.simpleName}"
        }
        safeEvent(line)
    }

    private fun neutral(color: String? = null): List<Wallpaper> =
        listOf(Wallpaper(color ?: safeNeutralColor(), WallpaperType.COLOR))

    private fun safeNeutralColor(): String =
        runCatching(neutralColor).getOrNull()?.trim()?.takeIf { COLOR_RE.matches(it) } ?: DEFAULT_NEUTRAL

    private fun log(event: Event, trace: Trace, result: List<Wallpaper>, durationMs: Long) {
        val what = when (event) {
            is Event.ProgramCardFocused -> buildString {
                append("program type=").append(event.type)
                append(" host=").append(runCatching { SourceHints.hostOf(event.iconUri) }.getOrNull() ?: "-")
                if (runCatching(logTitles).getOrDefault(false)) append(" title=\"").append(event.title.orEmpty().take(80)).append('"')
            }
            is Event.CardFocused -> "app"
            else -> "time-elapsed"
        }
        val answer = result.firstOrNull()?.let { if (it.type == WallpaperType.COLOR) "color" else "image" } ?: "empty"
        safeEvent("g=${trace.generation} $what → $answer [${trace.note}] ${durationMs}ms")
    }

    private fun safeEvent(line: String) {
        try {
            diagnostics.event(line)
        } catch (_: Throwable) {
        }
    }

    companion object {
        /** How long a binder call waits for preparation. */
        const val T_BLOCK_MS = 300L
        /** Background preparation limit. */
        const val T_BG_MS = 8_000L
        /** Neutral background used when the setting is corrupted. */
        const val DEFAULT_NEUTRAL = "#101214"
        /** Max consecutive restarts after "stale" (guards against loops). */
        private const val MAX_STALE_RESTARTS = 2

        private val COLOR_RE = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

        fun colorHex(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)
    }
}
