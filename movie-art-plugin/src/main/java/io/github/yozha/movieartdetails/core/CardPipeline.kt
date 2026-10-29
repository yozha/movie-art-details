package io.github.yozha.movieartdetails.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.yozha.movieartdetails.match.MappingKey
import io.github.yozha.movieartdetails.match.SearchLanguages
import io.github.yozha.movieartdetails.match.SourceHints
import io.github.yozha.movieartdetails.match.TitleMatcher
import io.github.yozha.movieartdetails.match.TitleParser
import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.Candidate
import io.github.yozha.movieartdetails.model.CardKind
import io.github.yozha.movieartdetails.model.CompositeStore
import io.github.yozha.movieartdetails.model.Diagnostics
import io.github.yozha.movieartdetails.model.DisplayInfo
import io.github.yozha.movieartdetails.model.DisplayLanguage
import io.github.yozha.movieartdetails.model.ManualMapping
import io.github.yozha.movieartdetails.model.MappingsRepository
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.ParsedTitle
import io.github.yozha.movieartdetails.model.RecentTitle
import io.github.yozha.movieartdetails.model.RecentTitlesRepository
import io.github.yozha.movieartdetails.model.SettingsRepository
import io.github.yozha.movieartdetails.model.SourceCache
import io.github.yozha.movieartdetails.model.SourceDecision
import io.github.yozha.movieartdetails.model.TitleDetails
import io.github.yozha.movieartdetails.model.TmdbClient
import io.github.yozha.movieartdetails.model.TmdbError
import io.github.yozha.movieartdetails.model.TmdbErrorKind
import io.github.yozha.movieartdetails.model.TmdbResult
import io.github.yozha.movieartdetails.model.WallpaperRenderer
import io.github.yozha.movieartdetails.render.ImageDecoder
import io.github.yozha.movieartdetails.tmdb.DisplayInfoBuilder
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.abs

/**
 * Title matching and composite preparation.
 *
 * Fast path [quick] (binder thread, no network): parse → manual mapping → SKIP / negative cache →
 * saved match → fresh level 1 details → level 2 composite.
 * Full path [prepare] (pool): /find by IMDb ID or movie/tv search in the script's languages → TitleMatcher
 * (offline: an expired match up to 6 months old) → details in the display language (stale cache on failure) →
 * images → render → atomic publish. Each TMDB request is bounded by the remaining T_bg and runs only
 * while the card is focused. ID tags in the title are part of the match and job keys.
 *
 * Titles are never written to diagnostics: only branch labels and error codes.
 */
class CardPipeline(
    private val tmdb: TmdbClient,
    private val sourceCache: SourceCache,
    private val compositeStore: CompositeStore,
    private val renderer: WallpaperRenderer,
    private val settings: SettingsRepository,
    private val mappings: MappingsRepository,
    private val recent: RecentTitlesRepository,
    private val diagnostics: Diagnostics,
    /** Parallel search requests (movie and tv). */
    private val searchExecutor: ExecutorService,
    /** Minor background work: recording recent titles. */
    private val background: (Runnable) -> Unit,
    /** After a successful publish: trim caches to the limit. */
    private val afterPublish: () -> Unit = {},
    /** Disk write failure (low storage): trim our own cache once. */
    private val onStorageError: () -> Unit = {},
    private val decodeBackdrop: (File) -> Bitmap? = { ImageDecoder.decodeBackdrop(it) },
    private val decodeLogo: (File) -> Bitmap? = { ImageDecoder.decodeLogo(it) },
    /** Validates a downloaded image before caching it: corrupted images are not cached. */
    private val isImage: (ByteArray) -> Boolean = { decodableImage(it) },
    private val clock: () -> Long = System::currentTimeMillis,
) : CardResolver {

    private class Analysis(
        val parsed: ParsedTitle,
        val decision: SourceDecision,
        /** Key for manual mappings and title history (MappingKey). */
        val key: String,
        val manual: ManualMapping?,
        /**
         * Key for matches, the negative cache and jobs; includes the ID tag. Otherwise "Avatar [tmdbid-…]"
         * would pick up the saved match or job for plain "Avatar" instead of the explicitly specified title.
         */
        val matchKey: String,
    )

    /** Title to prepare. [cachedMatch] is true when it comes from a saved match, which is refreshed together with the details. */
    private class TitleTarget(val type: MediaType, val id: Int, val cachedMatch: Boolean)

    /** Early exit from [prepare] with a ready outcome. */
    private class Abort(val outcome: Outcome) : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    /** One render at a time: two 1920×1080 frames plus a backdrop at once would be an avoidable native heap spike on 32-bit devices. */
    private val renderLock = Any()
    private val recentLock = Any()
    private val recentSeen = LinkedHashMap<String, Long>()

    override fun quick(card: FocusedCard): Outcome {
        val a = analyze(card)
        recordSeen(card, a)
        early(a)?.let { return it }
        val target = explicitTarget(a) ?: sourceCache.getMatch(a.matchKey) ?: return pending(a)
        val language = settings.language()
        val appearance = settings.appearance()
        val details = sourceCache.getDetails(target.first, target.second, language.tmdb, allowStale = false) ?: return pending(a)
        val info = DisplayInfoBuilder.build(details, language, settings.minVoteCount(), appearance)
        val file = compositeStore.find(compositeKey(info, appearance, renderer.version)) ?: return pending(a)
        return Outcome.Ready(composite(file, info), "L2")
    }

    override fun prepare(card: FocusedCard, ctx: JobContext): Outcome = try {
        prepareOrThrow(analyze(card), ctx)
    } catch (e: Abort) {
        e.outcome
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        Outcome.Failed("interrupted")
    } catch (e: OutOfMemoryError) {
        // Bitmap pixels live in the native heap: an OOM while decoding must not crash the process.
        Outcome.Failed("oom")
    } catch (e: IOException) {
        // The TMDB client returns network errors as results, so an IOException here comes from writing the cache
        // or the composite (low storage): trim our own cache once, use a neutral background, log to diagnostics.
        safeEvent("storage error: ${e.javaClass.simpleName}")
        runCatching(onStorageError)
        Outcome.Failed("storage")
    }

    private fun prepareOrThrow(a: Analysis, ctx: JobContext): Outcome {
        early(a)?.let { return it }
        val language = settings.language()
        val appearance = settings.appearance()
        // Explicit ID (manual mapping, tmdbid tag) → fresh saved match → search.
        val target = explicitTarget(a)?.let { TitleTarget(it.first, it.second, cachedMatch = false) }
            ?: sourceCache.getMatch(a.matchKey)?.let { TitleTarget(it.first, it.second, cachedMatch = true) }
            ?: identify(a, ctx)
        val details = loadDetails(a, target, language, ctx)
        val info = DisplayInfoBuilder.build(details, language, settings.minVoteCount(), appearance)
        val key = compositeKey(info, appearance, renderer.version)
        compositeStore.find(key)?.let { return Outcome.Ready(composite(it, info), "L2") }

        val logoPath = info.logoPath
        // Logo not cached yet: the backdrop leaves time for it instead of eating the rest of T_bg.
        val logoTail = if (logoPath != null && LOGO_SIZES.none { sourceCache.getImage(logoPath, it) != null }) {
            minOf(LOGO_RESERVE_MS, ctx.remainingMs() / 4).coerceAtLeast(0)
        } else 0L
        val backdropFile = info.backdropPath?.let { path ->
            fetchImage(path, BACKDROP_SIZES, ctx, logoTail) ?: throw Abort(Outcome.Failed("backdrop"))
        }
        // Logo failed to load (404, timeout, corrupted): draw the title as text rather than a neutral background.
        // A key error or the card losing focus still aborts preparation (exceptions from fetchImage/call).
        val logoFile = logoPath?.let { path ->
            fetchImage(path, LOGO_SIZES, ctx, minOf(RENDER_RESERVE_MS, ctx.remainingMs() / 4).coerceAtLeast(0))
        }
        // The text variant gets its own key, so the next preparation tries the logo again.
        val renderKey = if (logoPath != null && logoFile == null) key + NO_LOGO else key
        if (renderKey != key) {
            safeEvent("logo unavailable → text title")
            compositeStore.find(renderKey)?.let { return Outcome.Ready(composite(it, info), "L2:nologo") }
        }
        if (ctx.remainingMs() <= 0) return Outcome.Failed("T_bg")
        if (!ctx.isCurrent()) return Outcome.Failed(Outcome.STALE)

        val file = synchronized(renderLock) {
            // The card may have lost focus while we waited for another render: don't waste a render on it.
            if (!ctx.isCurrent()) throw Abort(Outcome.Failed(Outcome.STALE))
            var backdrop: Bitmap? = null
            var logo: Bitmap? = null
            try {
                backdrop = backdropFile?.let { decodeBackdrop(it) ?: throw Abort(Outcome.Failed("backdrop-decode")) }
                // A logo that fails to decode (SVG, broken PNG) is replaced with a text title.
                logo = logoFile?.let(decodeLogo)
                compositeStore.publish(renderKey) { out -> renderer.render(backdrop, logo, info, appearance, out) }
            } finally {
                backdrop?.recycle()
                logo?.recycle()
            }
        }
        runCatching(afterPublish)
        safeEvent("ready ${info.mediaType.path} L1→L2")
        return Outcome.Ready(composite(file, info), "rendered")
    }

    // ───────────── matching ─────────────

    private fun analyze(card: FocusedCard): Analysis {
        val parsed = TitleParser.parse(card.title)
        val decision = SourceHints.classify(card.type, card.iconUri)
        val key = MappingKey.of(parsed, decision.kind)
        return Analysis(parsed, decision, key, mappings.get(key), key + idTag(parsed))
    }

    /** Offline decisions: manual exclusion, SKIP, negative cache. */
    private fun early(a: Analysis): Outcome? {
        val manual = a.manual
        if (manual != null) return if (manual.target == null) Outcome.Rejected("manual-off") else null
        if (a.decision.kind == CardKind.SKIP) return Outcome.Rejected("skip:${a.decision.source}")
        if (a.parsed.primary.isBlank() && a.parsed.imdbId == null && a.parsed.tmdbId == null) return Outcome.Rejected("empty")
        if (sourceCache.isNegative(a.matchKey)) return Outcome.Rejected("negative")
        return null
    }

    /** Explicitly specified title: manual mapping → tmdbid tag when the type is known. Takes precedence over the match cache. */
    private fun explicitTarget(a: Analysis): Pair<MediaType, Int>? {
        a.manual?.target?.let { return it }
        val tagged = a.parsed.tmdbId
        if (tagged != null && tagged > 0) {
            when (a.decision.kind) {
                CardKind.MOVIE -> return MediaType.MOVIE to tagged
                CardKind.SERIES -> return MediaType.TV to tagged
                else -> Unit
            }
        }
        return null
    }

    private fun identify(a: Analysis, ctx: JobContext): TitleTarget {
        val languages = SearchLanguages.forScript(a.parsed.script)
        val types = mediaTypes(a.decision.kind)

        val imdb = a.parsed.imdbId
        if (imdb != null) {
            // An IMDb ID tag is unambiguous: use /find instead of search; empty or 404 falls back to regular search.
            val r = call(ctx) { tmdb.findByImdb(imdb, languages.first()) }
            if (r is TmdbResult.Err && r.error.kind != TmdbErrorKind.NOT_FOUND) {
                staleMatch(a, listOf(r.error))?.let { return it }
                throw failure(r.error)
            }
            val found = if (r is TmdbResult.Ok) r.value.firstOrNull { it.mediaType in types } else null
            if (found != null) {
                sourceCache.putMatch(a.matchKey, found.mediaType, found.id)
                return TitleTarget(found.mediaType, found.id, cachedMatch = false)
            }
        }
        if (a.parsed.primary.isBlank()) throw negative(a, "empty")

        val first = search(a.parsed.primary, types, languages, null, ctx)
        var candidates = first.candidates
        var errors = first.errors
        var match = TitleMatcher.match(a.parsed, a.decision.kind, candidates)
        val year = a.parsed.year
        // The year is a hint, not a filter: retry with the year only if the first page has no matching year.
        if (match.accepted == null && year != null && candidates.none { c -> c.year?.let { abs(it - year) <= 1 } == true }) {
            val second = search(a.parsed.primary, types, languages, year, ctx)
            candidates = candidates + second.candidates
            errors = errors + second.errors
            match = TitleMatcher.match(a.parsed, a.decision.kind, candidates)
        }
        val accepted = match.accepted
        if (accepted != null) {
            sourceCache.putMatch(a.matchKey, accepted.mediaType, accepted.id)
            safeEvent("match %.2f".format(Locale.ROOT, match.confidence))
            return TitleTarget(accepted.mediaType, accepted.id, cachedMatch = false)
        }
        // Network errors are not cached as negative results.
        if (errors.isNotEmpty()) {
            staleMatch(a, errors)?.let { return it }
            throw failure(pickError(errors))
        }
        safeEvent("no match: ${match.reason}")
        throw negative(a, "low-confidence")
    }

    /**
     * Search failed purely because of the network (offline, 5xx, 429, T_bg): an expired match no older than
     * 6 months beats a neutral background; its details and images are taken from cache as well.
     * A key error or a search response without a suitable candidate does not use it.
     */
    private fun staleMatch(a: Analysis, errors: List<TmdbError>): TitleTarget? {
        if (errors.isEmpty() || errors.any { it.kind !in OFFLINE_ERRORS }) return null
        val (type, id) = sourceCache.getMatch(a.matchKey, allowStale = true) ?: return null
        reportError(errors.first())
        safeEvent("stale match: ${errors.first().kind}")
        return TitleTarget(type, id, cachedMatch = true)
    }

    private class SearchBatch(val candidates: List<Candidate>, val errors: List<TmdbError>)

    /** movie and tv (and both languages for ambiguous Cyrillic) run in parallel under a shared T_bg limit. */
    private fun search(query: String, types: List<MediaType>, languages: List<String>, year: Int?, ctx: JobContext): SearchBatch {
        val calls = types.flatMap { type -> languages.map { lang -> type to lang } }
        val futures: List<Future<TmdbResult<List<Candidate>>>> = calls.map { (type, lang) ->
            searchExecutor.submit(Callable {
                call(ctx) {
                    if (type == MediaType.MOVIE) tmdb.searchMovie(query, lang, year) else tmdb.searchTv(query, lang, year)
                }
            })
        }
        val candidates = ArrayList<Candidate>()
        val errors = ArrayList<TmdbError>()
        try {
            for (f in futures) {
                val r = try {
                    f.get(ctx.remainingMs().coerceAtLeast(1), TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    f.cancel(true)
                    TmdbResult.Err(TmdbError(TmdbErrorKind.NETWORK, message = "T_bg"))
                } catch (e: ExecutionException) {
                    // The card lost focus (Abort from call on a search thread): abort the whole preparation.
                    (e.cause as? Abort)?.let { throw it }
                    TmdbResult.Err(TmdbError(TmdbErrorKind.NETWORK, message = e.cause?.javaClass?.simpleName.orEmpty()))
                }
                when (r) {
                    is TmdbResult.Ok -> candidates += r.value
                    is TmdbResult.Err -> errors += r.error
                }
            }
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }
        return SearchBatch(candidates, errors)
    }

    // ───────────── details and images ─────────────

    private fun loadDetails(a: Analysis, target: TitleTarget, language: DisplayLanguage, ctx: JobContext): TitleDetails {
        val type = target.type
        val id = target.id
        sourceCache.getDetails(type, id, language.tmdb, allowStale = false)?.let { return it }
        val error = when (val r = call(ctx) { tmdb.details(type, id, language.tmdb) }) {
            is TmdbResult.Ok -> {
                sourceCache.putDetails(r.value, language.tmdb)
                // Fresh details confirm the match: restart its lifetime (and the 6-month offline window).
                if (target.cachedMatch) sourceCache.putMatch(a.matchKey, type, id)
                return r.value
            }
            is TmdbResult.Err -> r.error
        }
        if (error.kind == TmdbErrorKind.NOT_FOUND) {
            // Leave manual mappings alone: the contract has no way to mark one invalid yet.
            if (a.manual == null) sourceCache.putNegative(a.matchKey)
            reportError(error)
            throw Abort(Outcome.Rejected("not-found"))
        }
        // Offline, 429, 5xx, revoked key: fall back to the stale cache for this exact title.
        val stale = sourceCache.getDetails(type, id, language.tmdb, allowStale = true) ?: throw failure(error)
        reportError(error)
        safeEvent("stale details: ${error.kind}")
        return stale
    }

    /**
     * Image file from the level 1 cache or the network; sizes are in order of preference.
     * Each request is bounded by the remaining T_bg minus [tailMs], the time reserved for later steps. A size
     * with a fallback after it leaves time for that fallback too: on a slow network original doesn't eat all
     * of T_bg, and the degraded w1280 still has time to load.
     */
    private fun fetchImage(path: String, sizes: List<String>, ctx: JobContext, tailMs: Long = 0): File? {
        for (size in sizes) sourceCache.getImage(path, size)?.let { return it }
        for ((i, size) in sizes.withIndex()) {
            val left = ctx.remainingMs()
            if (left <= 0) return null
            val reserve = if (i + 1 < sizes.size) tailMs + minOf(FALLBACK_RESERVE_MS, (left - tailMs) / 2).coerceAtLeast(0) else tailMs
            when (val r = call(ctx, reserve) { tmdb.downloadImage(path, size, maxBytes(size)) }) {
                is TmdbResult.Ok -> if (isImage(r.value)) return sourceCache.putImage(path, size, r.value)
                is TmdbResult.Err -> {
                    if (r.error.kind in KEY_ERRORS) throw failure(r.error)
                    reportError(r.error)
                }
            }
            // w1280 is a degraded mode only, and is logged to diagnostics.
            if (i + 1 < sizes.size) safeEvent("image degraded → ${sizes[i + 1]}")
        }
        return null
    }

    // ───────────── errors and retries ─────────────

    /**
     * A single TMDB request with retries: on 429, wait retryAfter (the client already derives it from Retry-After
     * or a jittered 2–4–8… backoff); on 5xx, at most 2 retries. Only for the current card and within T_bg.
     *
     * Each attempt is bounded by the remaining T_bg minus [reserveMs] ([RequestDeadline] cancels the OkHttp call
     * at the deadline instead of relying on the shared 8 s callTimeout). Focus is checked before each attempt: a job
     * for a card that lost focus aborts with Failed(stale) and doesn't hold a pool thread (anything fetched stays cached).
     */
    private fun <T> call(ctx: JobContext, reserveMs: Long = 0, block: () -> TmdbResult<T>): TmdbResult<T> {
        var serverRetries = 0
        var rateRetries = 0
        while (true) {
            if (!ctx.isCurrent()) throw Abort(Outcome.Failed(Outcome.STALE))
            val budget = ctx.remainingMs() - reserveMs
            if (budget <= 0) return TmdbResult.Err(TmdbError(TmdbErrorKind.NETWORK, message = "T_bg"))
            val r = RequestDeadline.within(budget, block)
            if (r !is TmdbResult.Err) return r
            val pause = when (r.error.kind) {
                TmdbErrorKind.RATE_LIMITED -> if (rateRetries++ < MAX_RATE_RETRIES) r.error.retryAfterMs ?: RATE_PAUSE_MS else null
                TmdbErrorKind.SERVER -> if (serverRetries < MAX_SERVER_RETRIES) SERVER_PAUSE_MS * ++serverRetries else null
                else -> null
            } ?: return r
            if (!ctx.isCurrent()) throw Abort(Outcome.Failed(Outcome.STALE))
            if (pause <= 0 || pause >= ctx.remainingMs() - reserveMs) return r
            Thread.sleep(pause)
        }
    }

    private fun failure(error: TmdbError): Abort {
        reportError(error)
        // INVALID/SUSPENDED: the client stops sending requests until the key changes; the card gets a neutral background.
        val outcome = if (error.kind in KEY_ERRORS) Outcome.Rejected("key:${error.kind}") else Outcome.Failed("tmdb:${error.kind}")
        return Abort(outcome)
    }

    private fun negative(a: Analysis, note: String): Abort {
        sourceCache.putNegative(a.matchKey)
        return Abort(Outcome.Rejected(note))
    }

    /** A key error outranks a network error: its status is shown in settings. */
    private fun pickError(errors: List<TmdbError>): TmdbError = errors.firstOrNull { it.kind in KEY_ERRORS } ?: errors.first()

    private fun reportError(error: TmdbError) {
        try {
            diagnostics.lastError(error)
        } catch (_: Throwable) {
        }
    }

    private fun safeEvent(line: String) {
        try {
            diagnostics.event(line)
        } catch (_: Throwable) {
        }
    }

    // ───────────── misc ─────────────

    private fun pending(a: Analysis): Outcome =
        Outcome.Pending("${a.matchKey}|${settings.language().tmdb}|${settings.appearance().fingerprint()}")

    private fun composite(file: File, info: DisplayInfo) =
        Composite(file, info.title, "https://www.themoviedb.org/${info.mediaType.path}/${info.tmdbId}")

    /** Recent title history for manual mapping: at most once a minute per title, off the binder thread. */
    private fun recordSeen(card: FocusedCard, a: Analysis) {
        val now = clock()
        val seenKey = card.title + "\u0000" + a.key
        synchronized(recentLock) {
            val last = recentSeen[seenKey]
            if (last != null && now - last in 0 until RECENT_THROTTLE_MS) return
            recentSeen.remove(seenKey)
            recentSeen[seenKey] = now
            while (recentSeen.size > 64) recentSeen.remove(recentSeen.keys.first())
        }
        val title = RecentTitle(card.title, card.type, SourceHints.hostOf(card.iconUri), a.key, now)
        try {
            background(Runnable { runCatching { recent.record(title) } })
        } catch (_: Throwable) {
        }
    }

    companion object {
        private val BACKDROP_SIZES = listOf("original", "w1280")
        private val LOGO_SIZES = listOf("w500")
        private val KEY_ERRORS = setOf(TmdbErrorKind.NO_KEY, TmdbErrorKind.INVALID_KEY, TmdbErrorKind.SUSPENDED_KEY)
        /** Search is unavailable but the match hasn't been disproved: an expired one may be used. */
        private val OFFLINE_ERRORS = setOf(TmdbErrorKind.NETWORK, TmdbErrorKind.SERVER, TmdbErrorKind.RATE_LIMITED)
        /** Time the original size leaves for the w1280 fallback (at most half of what remains). */
        private const val FALLBACK_RESERVE_MS = 2_500L
        /** Time the backdrop leaves for a logo that isn't loaded yet (at most a quarter of what remains). */
        private const val LOGO_RESERVE_MS = 1_000L
        /** Time the logo leaves for rendering (at most a quarter of what remains). */
        private const val RENDER_RESERVE_MS = 300L
        /** Composite key suffix for a text title used in place of a logo that failed to load. */
        private const val NO_LOGO = "|nologo"
        private const val MAX_SERVER_RETRIES = 2
        private const val MAX_RATE_RETRIES = 3
        private const val SERVER_PAUSE_MS = 500L
        private const val RATE_PAUSE_MS = 2_000L
        private const val RECENT_THROTTLE_MS = 60_000L
        private const val MB = 1024L * 1024

        /** Logical frame resolution; part of the composite key. */
        const val CANVAS = "1920x1080"

        /**
         * Level 2 key: title, language, all displayed values, appearance, renderer version, resolution.
         * If the selected logo failed to load, the composite is published under a key with the [NO_LOGO] suffix.
         */
        fun compositeKey(info: DisplayInfo, appearance: AppearanceSettings, rendererVersion: Int): String =
            "${info.mediaType.path}/${info.tmdbId}|${info.language.tmdb}|${info.fingerprint}|${appearance.fingerprint()}|r$rendererVersion|$CANVAS"

        /** Title ID tags for the match key: "" when there are none (the key then equals MappingKey). */
        private fun idTag(p: ParsedTitle): String = buildString {
            p.imdbId?.let { append("|imdb:").append(it) }
            p.tmdbId?.takeIf { it > 0 }?.let { append("|tmdb:").append(it) }
        }

        internal fun mediaTypes(kind: CardKind): List<MediaType> = when (kind) {
            CardKind.MOVIE -> listOf(MediaType.MOVIE)
            CardKind.SERIES -> listOf(MediaType.TV)
            else -> listOf(MediaType.MOVIE, MediaType.TV)
        }

        /** Response size limit: 8 MB for original. */
        private fun maxBytes(size: String): Long = when (size) {
            "original" -> 8 * MB
            "w1280" -> 4 * MB
            else -> 2 * MB
        }

        /** Decodes bounds only, no pixels. */
        private fun decodableImage(bytes: ByteArray): Boolean {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            return o.outWidth > 0 && o.outHeight > 0
        }
    }
}
