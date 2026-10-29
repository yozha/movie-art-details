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

/** Ready composite for the host reply: a TMDB frame, or a titled neutral frame with no source or author. */
data class Composite(val file: File, val title: String, val sourceUrl: String?, val author: String? = TMDB_AUTHOR) {
    companion object {
        const val TMDB_AUTHOR = "TMDB"
    }
}

/** A card shown without TMDB details: its own title on the neutral background. */
data class NeutralCard(
    /** As Projectivy sent it, trimmed. */
    val title: String,
    /** Program card: its iconUri, which identifies the source app. */
    val iconUri: String? = null,
    /** App card: its package; null for Projectivy's own shortcuts. */
    val packageName: String? = null,
    /** "#RRGGBB": the color the plain neutral reply uses (with the app card tint, if any). */
    val color: String,
)

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

/** Titled neutral frames, with no TMDB requests. Implementation: [TitledCards]. */
interface NeutralResolver {
    /**
     * On the binder thread, no rendering: Ready (a cached frame), Pending (it has to be rendered) or Rejected when the
     * setting is off or there is nothing to show: the reply is then a plain neutral color.
     */
    fun quick(card: NeutralCard): Outcome
    /** Renders on a pool thread: Ready, or Failed (a plain neutral color). */
    fun prepare(card: NeutralCard, ctx: JobContext): Outcome

    companion object {
        /** Plain neutral colors only. */
        val OFF: NeutralResolver = object : NeutralResolver {
            override fun quick(card: NeutralCard): Outcome = Outcome.Rejected(TitledCards.OFF)
            override fun prepare(card: NeutralCard, ctx: JobContext): Outcome = Outcome.Rejected(TitledCards.OFF)
        }
    }
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
 * - ProgramCardFocused: composite cached → reply at once; rejected → the titled frame (below); otherwise a pool job
 *   (one per jobKey) and a wait of at most [tBlockMs]; on timeout → neutral, and once the job is ready,
 *   if the generation is still current, one broadcast per generation. If the host doesn't fetch the frame with a
 *   TimeElapsed after it, the broadcast is repeated up to [REBROADCAST_DELAYS_MS].size times: an unanswered broadcast
 *   would leave the neutral background until the next focus change.
 * - TimeElapsed: background for the current state; the same composite → the same URI.
 * - CardFocused: new generation, the titled frame (below), else neutral COLOR (tinted with darkColor if enabled in settings).
 * - null and any other event → empty list; any internal error → neutral COLOR.
 *
 * Titled neutral frames ([NeutralResolver]): a program card that ends up not recognized (Rejected, from the quick path
 * or from preparation) and an app card with a title are shown with their own title instead of a plain color. The
 * frame is delivered the same way as a composite: from the cache at once, or rendered as a job within the T_block that
 * is left, else the neutral COLOR now and a broadcast later. A card still being matched gets the plain COLOR, as before;
 * a transient failure stays a plain COLOR too and is retried on TimeElapsed (a titled quick path that failed before it
 * had a job is asked again a bounded number of times).
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
    private val titled: NeutralResolver = NeutralResolver.OFF,
    private val tBlockMs: Long = T_BLOCK_MS,
    private val tBgMs: Long = T_BG_MS,
    /** Monotonic milliseconds. */
    private val monoMs: () -> Long = { System.nanoTime() / 1_000_000 },
    /** Runs a task after a delay, for repeating a broadcast the host didn't act on; no repeats without one. */
    private val later: (delayMs: Long, task: () -> Unit) -> Unit = { _, _ -> },
) {

    /** [color] is this generation's neutral COLOR reply. */
    private class CardState(val generation: Long, val card: FocusedCard?, val color: String) {
        // Everything below is guarded by lock.
        /** Set once the card is shown with its own title; [jobKey] and [outcome] then belong to the titled frame. */
        var neutral: NeutralCard? = null
        var jobKey: String? = null
        var outcome: Outcome? = null
        /** TimeElapsed retries of a titled quick path that failed before it had a job: bounded by [MAX_TITLE_RETRIES]. */
        var titleRetries = 0
        /** A binder call of this generation is waiting for the result: no broadcast needed, it will reply itself. */
        var waiting = false
        /** The ready composite of this generation has already been returned to the host. */
        var delivered = false
        var broadcastSent = false
    }

    /**
     * [restarts]: how many times the job was restarted after "stale" when focus returned to the card.
     * [titled]: renders a titled neutral frame rather than matching (diagnostics only).
     */
    private inner class Job(
        val key: String,
        val work: (JobContext) -> Outcome,
        val restarts: Int = 0,
        val titled: Boolean = false,
    ) : JobContext {
        val createdAt = monoMs()
        @Volatile var startedAt = 0L
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
        val state = newGeneration(card, safeNeutralColor())
        trace.generation = state.generation
        if (card.title.isEmpty()) {
            trace.note = "no-title"
            settle(state, Outcome.Rejected(trace.note))
            return neutral(state.color)
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
            is Outcome.Failed -> {
                settle(state, quick)
                neutral(state.color)
            }
            // Not recognized: the card's own title instead (or the plain color when that is off).
            is Outcome.Rejected -> reply(state, awaitTitle(state, neutralCard(state, card)), trace, prefix = quick.note)
            is Outcome.Pending -> reply(state, awaitPreparation(state, quick.jobKey) { ctx -> resolver.prepare(card, ctx) }, trace)
        }
    }

    /** App cards have no TMDB details: the titled neutral frame with the app's badge, or the (tinted) plain color. */
    private fun onAppCard(event: Event.CardFocused, trace: Trace): List<Wallpaper> {
        val tint = if (event.darkColor != 0 && tintFromCard()) colorHex(event.darkColor) else null
        val state = newGeneration(null, tint ?: safeNeutralColor())
        trace.generation = state.generation
        trace.note = "app"
        val title = event.title?.trim().orEmpty()
        if (title.isEmpty()) {
            settle(state, Outcome.Rejected(trace.note))
            return neutral(state.color)
        }
        return reply(state, awaitTitle(state, NeutralCard(title, packageName = event.packageName, color = state.color)), trace, prefix = "app")
    }

    /** The reply after a wait: a ready frame, or this generation's neutral color. */
    private fun reply(state: CardState, out: Outcome?, trace: Trace, prefix: String? = null): List<Wallpaper> {
        val result = when (out) {
            is Outcome.Ready -> {
                trace.note = "ready<T_block:${out.note}"
                listOf(host.wallpaper(out.composite))
            }
            null -> {
                trace.note = "neutral>T_block"
                neutral(state.color)
            }
            else -> {
                trace.note = out.note
                neutral(state.color)
            }
        }
        if (prefix != null) trace.note = "$prefix ${trace.note}"
        return result
    }

    private fun neutralCard(state: CardState, card: FocusedCard) = NeutralCard(card.title, iconUri = card.iconUri, color = state.color)

    private fun onTimeElapsed(trace: Trace): List<Wallpaper> {
        var restart: Job? = null
        var retitle: Pair<CardState, NeutralCard>? = null
        var color: String? = null
        val ready: Composite? = lock.withLock {
            val state = current
            trace.generation = state?.generation ?: 0
            val work = state?.let(::workOf)
            if (state == null || (work == null && state.outcome == null)) {
                trace.note = "te:no-card"
                return@withLock null
            }
            color = state.color
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
                    val key = state.jobKey
                    val titledCard = state.neutral
                    when {
                        key != null -> {
                            state.outcome = null
                            restart = work?.let { ensureJobLocked(key, it, titled = state.neutral != null) }
                        }
                        // The titled quick path failed before it had a job: ask it again.
                        titledCard != null -> if (state.titleRetries < MAX_TITLE_RETRIES) {
                            state.titleRetries++
                            state.outcome = null
                            retitle = state to titledCard
                        } else {
                            trace.note = "te:failed"
                        }
                        else -> state.outcome = null
                    }
                    null
                }
            }
        }
        restart?.let(::start)
        val shown = ready ?: retitle?.let { (state, neutral) -> retryTitle(state, neutral) }
        return if (shown != null) listOf(host.wallpaper(shown)) else neutral(color ?: safeNeutralColor())
    }

    /** [showTitle] again from TimeElapsed, without waiting: a frame that is ready by the time it returns is the reply. */
    private fun retryTitle(state: CardState, neutral: NeutralCard): Composite? {
        lock.withLock { state.waiting = true }
        try {
            showTitle(state, neutral)
        } finally {
            lock.withLock { state.waiting = false }
        }
        return lock.withLock {
            val out = state.outcome
            if (current === state && out is Outcome.Ready && !state.broadcastSent) out.composite.also { state.delivered = true } else null
        }
    }

    /** Guarded by lock. The job behind [state]'s current [CardState.jobKey]: the titled frame once there is one, else matching. */
    private fun workOf(state: CardState): ((JobContext) -> Outcome)? {
        state.neutral?.let { n -> return { ctx -> titled.prepare(n, ctx) } }
        val card = state.card ?: return null
        return { ctx -> resolver.prepare(card, ctx) }
    }

    private fun newGeneration(card: FocusedCard?, color: String): CardState = lock.withLock {
        CardState(++generation, card, color).also {
            current = it
            changed.signalAll()
        }
    }

    private fun settle(state: CardState, outcome: Outcome) = lock.withLock {
        if (current === state) state.outcome = outcome
    }

    /** Starts (or joins) a job and waits for it up to T_block. Returns null if it didn't finish in time or the generation changed. */
    private fun awaitPreparation(state: CardState, jobKey: String, work: (JobContext) -> Outcome): Outcome? {
        val job = lock.withLock {
            if (current !== state) return null
            state.jobKey = jobKey
            // Set before starting: a synchronous executor may finish the job before we begin waiting.
            state.waiting = true
            ensureJobLocked(jobKey, work)
        }
        try {
            job?.let(::start)
        } catch (t: Throwable) {
            lock.withLock { state.waiting = false }
            throw t
        }
        return awaitOutcome(state)
    }

    /** Shows the card with its own title ([showTitle]) and waits for the frame up to T_block, like [awaitPreparation]. */
    private fun awaitTitle(state: CardState, neutral: NeutralCard): Outcome? {
        lock.withLock {
            if (current !== state) return null
            state.waiting = true
        }
        try {
            showTitle(state, neutral)
        } catch (t: Throwable) {
            lock.withLock { state.waiting = false }
            throw t
        }
        return awaitOutcome(state)
    }

    /** Waits up to T_block for [state]'s outcome. Returns null if it didn't arrive in time or the generation changed. */
    private fun awaitOutcome(state: CardState): Outcome? = lock.withLock {
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

    /**
     * Moves [state] to its titled frame: a cached one settles at once, otherwise a render job starts (or is joined);
     * [NeutralResolver] saying no settles a plain neutral color, under the [reason] matching gave, if any. Called on
     * the binder thread or, after a preparation that ended Rejected, on its pool thread.
     */
    private fun showTitle(state: CardState, neutral: NeutralCard, reason: String? = null) {
        val quick = try {
            titled.quick(neutral)
        } catch (t: Throwable) {
            Outcome.Failed("titled:error:${t.javaClass.simpleName}")
        }
        var job: Job? = null
        var notify = false
        lock.withLock {
            if (current !== state) return
            state.neutral = neutral
            if (quick is Outcome.Pending) {
                state.jobKey = quick.jobKey
                job = ensureJobLocked(quick.jobKey, { ctx -> titled.prepare(neutral, ctx) }, titled = true)
            } else {
                notify = settleLocked(state, if (quick is Outcome.Rejected && reason != null) Outcome.Rejected(reason) else quick)
            }
        }
        job?.let(::start)
        if (notify) broadcast(state, quick)
    }

    /** Guarded by lock. Creates a job if none exists; null if one is already running (the current generation gets its result). */
    private fun ensureJobLocked(key: String, work: (JobContext) -> Outcome, restarts: Int = 0, titled: Boolean = false): Job? {
        if (jobs.containsKey(key)) return null
        return Job(key, work, restarts, titled).also { jobs[key] = it }
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
                job.startedAt = monoMs()
                job.deadline = job.startedAt + tBgMs
                job.work(job)
            }
        } catch (t: Throwable) {
            Outcome.Failed("error:${t.javaClass.simpleName}")
        }
        complete(job, outcome)
    }

    private fun complete(job: Job, outcome: Outcome) {
        var notify: CardState? = null
        var restart: Job? = null
        var unrecognized: Pair<CardState, NeutralCard>? = null
        var fate = "dropped"
        lock.withLock {
            if (jobs[job.key] === job) jobs.remove(job.key)
            val state = current
            // A stale result isn't published (it's already cached and will be reused if focus returns to the card).
            if (state == null || state.jobKey != job.key || state.outcome != null) return@withLock
            fate = "g=${state.generation}"
            // The job aborted as stale, but focus has already returned to the card and is waiting on the same jobKey:
            // don't store "stale" as the outcome (that would mean a neutral background until TimeElapsed); restart instead.
            if (outcome is Outcome.Failed && outcome.note == Outcome.STALE && job.restarts < MAX_STALE_RESTARTS) {
                restart = ensureJobLocked(job.key, job.work, job.restarts + 1, job.titled)
                return@withLock
            }
            // Matching ended without a title: show the card's own title (a waiting call keeps waiting for it).
            val card = state.card
            if (outcome is Outcome.Rejected && state.neutral == null && card != null) {
                unrecognized = state to neutralCard(state, card)
                return@withLock
            }
            if (settleLocked(state, outcome)) notify = state
        }
        logJob(job, outcome, fate)
        restart?.let(::start)
        unrecognized?.let { (state, neutral) -> showTitle(state, neutral, outcome.note) }
        notify?.let { broadcast(it, outcome) }
    }

    /** Queue and run times of a finished job, to tell a slow render from a lost delivery. No keys: they hold titles. */
    private fun logJob(job: Job, outcome: Outcome, fate: String) {
        val end = monoMs()
        val ran = if (job.startedAt > 0) "queued=${job.startedAt - job.createdAt}ms run=${end - job.startedAt}ms" else "not run"
        safeEvent("$fate job ${if (job.titled) "titled" else "match"} → ${outcome.note} $ran")
    }

    /** Guarded by lock. Stores the outcome and wakes the waiting call; true if the host has to be told with a broadcast. */
    private fun settleLocked(state: CardState, outcome: Outcome): Boolean {
        state.outcome = outcome
        changed.signalAll()
        if (outcome !is Outcome.Ready || state.waiting || state.delivered || state.broadcastSent) return false
        state.broadcastSent = true
        return true
    }

    private fun broadcast(state: CardState, outcome: Outcome, attempt: Int = 0) {
        val label = if (attempt == 0) "broadcast" else "rebroadcast #$attempt: not fetched"
        var sent = false
        val line = try {
            if (host.canNotify()) {
                host.notifyUpdated()
                sent = true
                "g=${state.generation} $label ${outcome.note}"
            } else {
                "g=${state.generation} $label skipped: screen off"
            }
        } catch (t: Throwable) {
            "g=${state.generation} $label error:${t.javaClass.simpleName}"
        }
        safeEvent(line)
        if (sent && attempt < REBROADCAST_DELAYS_MS.size) confirmFetched(state, outcome, attempt)
    }

    /** Repeats the broadcast if the frame is still current and ready but the host hasn't fetched it with TimeElapsed. */
    private fun confirmFetched(state: CardState, outcome: Outcome, attempt: Int) {
        try {
            later(REBROADCAST_DELAYS_MS[attempt]) {
                val again = lock.withLock { current === state && !state.delivered && state.outcome is Outcome.Ready }
                if (again) broadcast(state, outcome, attempt + 1)
            }
        } catch (_: Throwable) {
        }
    }

    private fun neutral(color: String = safeNeutralColor()): List<Wallpaper> = listOf(Wallpaper(color, WallpaperType.COLOR))

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
        /** Max TimeElapsed retries of a failed titled quick path per card. */
        private const val MAX_TITLE_RETRIES = 3
        /** Waits before each repeat of a broadcast the host didn't act on (counted from the previous one). */
        internal val REBROADCAST_DELAYS_MS = longArrayOf(800, 1_500)

        private val COLOR_RE = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")

        fun colorHex(argb: Int): String = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF)
    }
}
