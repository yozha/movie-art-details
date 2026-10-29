package io.github.yozha.movieartdetails.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.yozha.movieartdetails.match.MappingKey
import io.github.yozha.movieartdetails.match.SearchLanguages
import io.github.yozha.movieartdetails.match.SourceHints
import io.github.yozha.movieartdetails.match.TitleMatcher
import io.github.yozha.movieartdetails.match.TitleParser
import io.github.yozha.movieartdetails.match.TitleText
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
import io.github.yozha.movieartdetails.model.Script
import io.github.yozha.movieartdetails.model.SearchPage
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
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * Title matching and composite preparation.
 *
 * Fast path [quick] (binder thread, no network): parse → manual mapping → SKIP / negative cache →
 * saved match → fresh level 1 details → level 2 composite. A card left pending warms up the TMDB connections.
 * Full path [prepare] (pool): details by a tmdbid tag or /find by an IMDb ID tag (kept only if the card title or
 * year agrees) or movie/tv search in the script's languages → TitleMatcher (offline: an expired match up to 6 months
 * old) → details in the display language (stale cache on failure) → backdrop and logo in parallel → render → atomic
 * publish. Each TMDB request is bounded by the remaining T_bg and runs only while the card is focused.
 * ID tags in the title are part of the match and job keys. The source app's badge ([AppBadges], from the card's
 * iconUri) is drawn in the block, and its package and version are part of the job and composite keys.
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
    /**
     * Logo downloads next to the backdrop: a small pool of its own, so they never queue behind another card's searches.
     * null downloads the logo after the backdrop on the job thread.
     */
    private val logoExecutor: Executor? = null,
    /** Minor background work: recording recent titles. */
    private val background: (Runnable) -> Unit,
    /** After a successful publish: trim caches to the limit. */
    private val afterPublish: () -> Unit = {},
    /** Disk write failure (low storage): trim our own cache once. */
    private val onStorageError: () -> Unit = {},
    /** Source-app badges for the block. */
    private val badges: AppBadges = AppBadges.NONE,
    /** One render at a time: two 1920×1080 frames plus a backdrop at once would be an avoidable native heap spike on 32-bit devices. */
    private val renderLock: Any = Any(),
    private val decodeBackdrop: (File) -> Bitmap? = { ImageDecoder.decodeBackdrop(it) },
    /** null decodes for the logo box of the block being rendered (its frame width and text preset), see [ImageDecoder]. */
    private val decodeLogo: ((File) -> Bitmap?)? = null,
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
        /** The movie kind comes from type=0, which Projectivy also sends by default: a series is still possible. */
        val typeZeroMovie: Boolean,
    )

    /**
     * Title to prepare. [cachedMatch] is true when it comes from a saved match, which is refreshed together with the details.
     * [details] are already loaded (an ID tag is checked against them).
     */
    private class TitleTarget(val type: MediaType, val id: Int, val cachedMatch: Boolean, val details: TitleDetails? = null)

    private enum class ImageRole { BACKDROP, LOGO }

    /** Early exit from [prepare] with a ready outcome. */
    private class Abort(val outcome: Outcome) : RuntimeException() {
        override fun fillInStackTrace(): Throwable = this
    }

    private val throughput = ThroughputMeter()
    private val recentLock = Any()
    private val recentSeen = LinkedHashMap<String, Long>()

    override fun quick(card: FocusedCard): Outcome {
        val a = analyze(card)
        recordSeen(card, a)
        early(a)?.let { return it }
        val target = a.manual?.target ?: sourceCache.getMatch(a.matchKey) ?: return pending(a, card)
        val language = settings.language()
        val appearance = settings.appearance()
        val details = sourceCache.getDetails(target.first, target.second, language.tmdb, allowStale = false) ?: return pending(a, card)
        val info = DisplayInfoBuilder.build(details, language, settings.minVoteCount(), appearance)
        val file = compositeStore.find(compositeKey(info, appearance, renderer.version, badgeFor(card, appearance))) ?: return pending(a, card)
        return Outcome.Ready(composite(file, info), "L2")
    }

    override fun prepare(card: FocusedCard, ctx: JobContext): Outcome = try {
        prepareOrThrow(card, analyze(card), ctx)
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

    private fun prepareOrThrow(card: FocusedCard, a: Analysis, ctx: JobContext): Outcome {
        early(a)?.let { return it }
        val language = settings.language()
        val appearance = settings.appearance()
        // Manual mapping → fresh saved match (its key includes any ID tag) → ID tag or search.
        val target = a.manual?.target?.let { TitleTarget(it.first, it.second, cachedMatch = false) }
            ?: sourceCache.getMatch(a.matchKey)?.let { TitleTarget(it.first, it.second, cachedMatch = true) }
            ?: identify(a, language, ctx)
        val details = target.details ?: loadDetails(a, target, language, ctx)
        val info = DisplayInfoBuilder.build(details, language, settings.minVoteCount(), appearance)
        val badge = badgeFor(card, appearance)
        val key = compositeKey(info, appearance, renderer.version, badge)
        compositeStore.find(key)?.let { return Outcome.Ready(composite(it, info), "L2") }

        val logoPath = info.logoPath
        val logoSizes = logoSizes(info)
        val logoCached = logoPath != null && logoSizes.any { sourceCache.getImage(logoPath, it) != null }
        // Set before the logo task is canceled: the interrupt surfaces as a network error, which must not be reported.
        val logoStopped = AtomicBoolean(false)
        val logoTask = logoPath?.let { path ->
            val tail = minOf(RENDER_RESERVE_MS, ctx.remainingMs() / 4).coerceAtLeast(0)
            FutureTask { fetchImage(path, logoSizes, ImageRole.LOGO, ctx, tail, stopped = logoStopped::get) }
        }
        // Both paths are known once the details are: the logo downloads next to the backdrop, not after it.
        val parallel = logoTask != null && !logoCached && info.backdropPath != null && submitLogo(logoTask)
        // Downloaded serially, a logo that isn't cached yet needs time the backdrop must leave for it.
        val backdropTail = if (logoTask != null && !logoCached && !parallel) {
            minOf(LOGO_RESERVE_MS, ctx.remainingMs() / 4).coerceAtLeast(0)
        } else 0L
        val backdropFile = try {
            info.backdropPath?.let { path ->
                fetchImage(path, BACKDROP_SIZES, ImageRole.BACKDROP, ctx, backdropTail) ?: throw Abort(Outcome.Failed("backdrop"))
            }
        } catch (t: Throwable) {
            // The card is lost without its backdrop: stop the logo download too.
            logoStopped.set(true)
            logoTask?.cancel(true)
            throw t
        }
        // Logo failed to load (404, timeout, corrupted): draw the title as text rather than a neutral background.
        // A key error or the card losing focus still aborts preparation (exceptions from fetchImage/call).
        val logoFile = logoTask?.let { awaitLogo(it, ctx, logoStopped) }
        // The text variant gets its own key, so the next preparation tries the logo again.
        val logoSuffix = if (logoPath != null && logoFile == null) NO_LOGO else ""
        val renderKey = key + logoSuffix
        if (renderKey != key) {
            safeEvent("logo unavailable → text title")
            compositeStore.find(renderKey)?.let { return Outcome.Ready(composite(it, info), "L2:nologo") }
        }
        if (ctx.remainingMs() <= 0) return Outcome.Failed("T_bg")
        if (!ctx.isCurrent()) return Outcome.Failed(Outcome.STALE)

        // Cached and shared: not recycled here. A badge that fails to load is left out of the frame, which is then
        // published under the key without a badge: the badge key stays free, so the next preparation tries it again.
        // Loaded outside renderLock, so a frame already stored without the badge doesn't wait for another render.
        val badgeImage = badge?.let { runCatching { badges.load(it) }.getOrNull() }
        val publishKey = if (badge != null && badgeImage == null) {
            compositeKey(info, appearance, renderer.version) + logoSuffix
        } else renderKey
        if (publishKey != renderKey) {
            safeEvent("badge unavailable → frame without it")
            compositeStore.find(publishKey)?.let { return Outcome.Ready(composite(it, info), "L2:nobadge") }
        }

        val file = synchronized(renderLock) {
            // The card may have lost focus while we waited for another render: don't waste a render on it.
            if (!ctx.isCurrent()) throw Abort(Outcome.Failed(Outcome.STALE))
            var backdrop: Bitmap? = null
            var logo: Bitmap? = null
            try {
                backdrop = backdropFile?.let { decodeBackdrop(it) ?: throw Abort(Outcome.Failed("backdrop-decode")) }
                // A logo that fails to decode (SVG, broken PNG) is replaced with a text title.
                // A wide logo is decoded for the full block width, not for the regular logo box.
                val decode = decodeLogo ?: { f -> ImageDecoder.decodeLogo(file = f, settings = appearance) }
                logo = logoFile?.let(decode)
                compositeStore.publish(publishKey) { out -> renderer.render(backdrop, logo, info, appearance, out, badgeImage) }
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
        val typeZeroMovie = decision.kind == CardKind.MOVIE && card.type == 0
        return Analysis(parsed, decision, key, mappings.get(key), key + idTag(parsed), typeZeroMovie)
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

    private fun identify(a: Analysis, language: DisplayLanguage, ctx: JobContext): TitleTarget {
        a.parsed.tmdbId?.takeIf { it > 0 }?.let { id -> taggedTarget(a, id, language, ctx)?.let { return it } }
        a.parsed.imdbId?.let { imdb -> imdbTarget(a, imdb, ctx)?.let { return it } }
        if (a.parsed.primary.isBlank()) throw negative(a, "empty")
        return searchTarget(a, ctx)
    }

    /**
     * A tmdbid tag: details by ID, for an unknown card type movie/{id} and tv/{id} in parallel, picked by the card title.
     * A tag that clearly disagrees with the card's title and year, or points nowhere, falls back to search.
     */
    private fun taggedTarget(a: Analysis, id: Int, language: DisplayLanguage, ctx: JobContext): TitleTarget? {
        val types = mediaTypes(a.decision.kind)
        val results = inParallel(types.map { type -> { fetchDetails(type, id, language, ctx) } }, ctx)
        val found = results.mapNotNull { (it as? TmdbResult.Ok)?.value }.toMutableList()
        val errors = results.mapNotNull { (it as? TmdbResult.Err)?.error?.takeIf { e -> e.kind != TmdbErrorKind.NOT_FOUND } }
        if (found.isEmpty() && errors.isNotEmpty()) {
            if (errors.any { it.kind in KEY_ERRORS }) throw failure(pickError(errors))
            // Offline, 429, 5xx: the stale cache for this exact ID, as for any other title.
            types.mapNotNullTo(found) { sourceCache.getDetails(it, id, language.tmdb, allowStale = true) }
            if (found.isEmpty()) {
                staleMatch(a, errors)?.let { return it }
                throw failure(pickError(errors))
            }
            reportError(errors.first())
            safeEvent("stale details: ${errors.first().kind}")
        }
        val chosen = if (types.size == 1) found.singleOrNull()?.takeIf { TagCheck.fit(a.parsed, it) != null } else TagCheck.pick(a.parsed, found)
        if (chosen == null) {
            safeEvent(if (found.isEmpty()) "tmdb tag not found → search" else "tmdb tag does not fit the title → search")
            return null
        }
        sourceCache.putMatch(a.matchKey, chosen.mediaType, chosen.id)
        return TitleTarget(chosen.mediaType, chosen.id, cachedMatch = false, details = chosen)
    }

    /** Fresh level 1 details or a request; successful responses are cached for [loadDetails] and later cards. */
    private fun fetchDetails(type: MediaType, id: Int, language: DisplayLanguage, ctx: JobContext): TmdbResult<TitleDetails> {
        sourceCache.getDetails(type, id, language.tmdb, allowStale = false)?.let { return TmdbResult.Ok(it) }
        val r = call(ctx) { tmdb.details(type, id, language.tmdb) }
        if (r is TmdbResult.Ok) sourceCache.putDetails(r.value, language.tmdb)
        return r
    }

    /** An IMDb ID tag: /find instead of search. Empty, 404 or a clear mismatch with the card title falls back to search. */
    private fun imdbTarget(a: Analysis, imdb: String, ctx: JobContext): TitleTarget? {
        val types = mediaTypes(a.decision.kind)
        val r = call(ctx) { tmdb.findByImdb(imdb, SearchLanguages.forScript(a.parsed.script).first()) }
        if (r is TmdbResult.Err && r.error.kind != TmdbErrorKind.NOT_FOUND) {
            staleMatch(a, listOf(r.error))?.let { return it }
            throw failure(r.error)
        }
        val found = (r as? TmdbResult.Ok)?.value?.firstOrNull { it.mediaType in types } ?: return null
        if (TagCheck.fit(a.parsed, found) == null) {
            safeEvent("imdb tag does not fit the title → search")
            return null
        }
        sourceCache.putMatch(a.matchKey, found.mediaType, found.id)
        return TitleTarget(found.mediaType, found.id, cachedMatch = false)
    }

    private fun searchTarget(a: Analysis, ctx: JobContext): TitleTarget {
        val primary = a.parsed.primary
        val languages = SearchLanguages.forScript(a.parsed.script)
        val types = mediaTypes(a.decision.kind)
        val first = search(calls(primary, types, languages, null), ctx)
        val candidates = ArrayList(first.candidates)
        val errors = ArrayList(first.errors)
        var match = TitleMatcher.match(a.parsed, a.decision.kind, candidates)
        val year = a.parsed.year
        // The year is a hint, not a filter: retry only if the first page has no candidate within a year of it.
        if (match.accepted == null && year != null && candidates.none { c -> c.year?.let { abs(it - year) <= 1 } == true }) {
            val retry = yearRetry(first.pages, primary, year)
            if (retry.isNotEmpty()) {
                val second = search(retry, ctx)
                candidates += second.candidates
                errors += second.errors
                match = TitleMatcher.match(a.parsed, a.decision.kind, candidates)
            }
        }
        // A miss gets one wider pass: series for a type=0 card, and the other-script half of "Localized / Original".
        if (match.accepted == null && errors.isEmpty()) {
            val wider = widerSearch(a, types, languages)
            if (wider.isNotEmpty()) {
                val third = search(wider, ctx)
                candidates += third.candidates
                errors += third.errors
                // type=0 turned out not to be a movie: compare as an unknown type, without the movie bonus and series penalty.
                val kind = if (a.typeZeroMovie) CardKind.UNKNOWN else a.decision.kind
                match = TitleMatcher.match(widenedParsed(a), kind, candidates)
                safeEvent("search widened: ${wider.size} request(s)")
            }
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
     * The year retry. With the year filter it can only find something new where page 1 was not the whole result, so only
     * those (type, language) pairs are repeated. When every pair fit on one page, the year may instead be part of the title
     * ("Wonder Woman 1984" parsed as "Wonder Woman" + 1984): the pairs that found anything are asked for "primary year".
     */
    private fun yearRetry(pages: List<Pair<SearchCall, SearchPage>>, primary: String, year: Int): List<SearchCall> {
        val paged = pages.filter { (_, page) -> page.totalPages?.let { it > 1 } ?: true }
        if (paged.isNotEmpty()) return paged.map { (c, _) -> c.copy(year = year) }
        if (year.toString() in primary.split(' ')) return emptyList()
        return pages.filter { (_, page) -> page.results.isNotEmpty() }.map { (c, _) -> c.copy(query = "$primary $year") }
    }

    /**
     * The title as the wider pass ranks it. Under the movie kind, "Season 2" or "Episode 4" in a plain (non-file) title is
     * part of the movie title ("Open Season 2"); the unknown kind of the wider pass would read it as an episode marker and
     * favor a series named after the cut primary. So a type=0 card keeps its movie reading: no season or episode.
     */
    private fun widenedParsed(a: Analysis): ParsedTitle =
        if (a.typeZeroMovie && !a.parsed.looksLikeFileName) a.parsed.copy(season = null, episode = null) else a.parsed

    /** Requests of the wider pass: /search/tv for a type=0 card, and a cross-script alternate in its own script's languages. */
    private fun widerSearch(a: Analysis, types: List<MediaType>, languages: List<String>): List<SearchCall> = buildList {
        if (a.typeZeroMovie) addAll(calls(a.parsed.primary, listOf(MediaType.TV), languages, null))
        val alternate = crossScriptAlternate(a.parsed) ?: return@buildList
        val altTypes = if (a.typeZeroMovie) listOf(MediaType.MOVIE, MediaType.TV) else types
        addAll(calls(alternate, altTypes, SearchLanguages.forScript(TitleText.scriptOf(alternate)), null))
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

    /** One search request; [year] is TMDB's year filter. */
    private data class SearchCall(val type: MediaType, val language: String, val query: String, val year: Int?)

    private class SearchBatch(
        val candidates: List<Candidate>,
        val errors: List<TmdbError>,
        /** Successful requests with their pages, for the year retry. */
        val pages: List<Pair<SearchCall, SearchPage>>,
    )

    private fun calls(query: String, types: List<MediaType>, languages: List<String>, year: Int?): List<SearchCall> =
        types.flatMap { type -> languages.map { lang -> SearchCall(type, lang, query, year) } }

    /** movie and tv (and both languages for ambiguous Cyrillic) run in parallel under a shared T_bg limit. */
    private fun search(calls: List<SearchCall>, ctx: JobContext): SearchBatch {
        val results = inParallel(calls.map { c -> { call(ctx) { tmdb.searchPage(c.type, c.query, c.language, c.year) } } }, ctx)
        val candidates = ArrayList<Candidate>()
        val errors = ArrayList<TmdbError>()
        val pages = ArrayList<Pair<SearchCall, SearchPage>>()
        for ((c, r) in calls.zip(results)) {
            when (r) {
                is TmdbResult.Ok -> {
                    candidates += r.value.results
                    pages += c to r.value
                }
                is TmdbResult.Err -> errors += r.error
            }
        }
        return SearchBatch(candidates, errors, pages)
    }

    /** TMDB requests on [searchExecutor], awaited within T_bg; a card that lost focus aborts them all. */
    private fun <T> inParallel(requests: List<() -> TmdbResult<T>>, ctx: JobContext): List<TmdbResult<T>> {
        val futures: List<Future<TmdbResult<T>>> = requests.map { request -> searchExecutor.submit(Callable { request() }) }
        try {
            return futures.map { f ->
                try {
                    f.get(ctx.remainingMs().coerceAtLeast(1), TimeUnit.MILLISECONDS)
                } catch (e: TimeoutException) {
                    f.cancel(true)
                    TmdbResult.Err(TmdbError(TmdbErrorKind.NETWORK, message = "T_bg"))
                } catch (e: ExecutionException) {
                    // The card lost focus (Abort from call on a search thread): abort the whole preparation.
                    (e.cause as? Abort)?.let { throw it }
                    TmdbResult.Err(TmdbError(TmdbErrorKind.NETWORK, message = e.cause?.javaClass?.simpleName.orEmpty()))
                }
            }
        } finally {
            futures.forEach { if (!it.isDone) it.cancel(true) }
        }
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
     * of T_bg, and the degraded w1280 still has time to load. A backdrop size with a fallback is also capped at what
     * the measured throughput can fetch in its time slice: the CDN sends Content-Length, so an original that would not
     * arrive in time is dropped right after the headers instead of after its bytes.
     * A [stopped] download (the logo of a card that lost its backdrop) ends with null and reports nothing.
     */
    private fun fetchImage(
        path: String, sizes: List<String>, role: ImageRole, ctx: JobContext, tailMs: Long = 0, stopped: () -> Boolean = { false },
    ): File? {
        for (size in sizes) sourceCache.getImage(path, size)?.let { return it }
        for ((i, size) in sizes.withIndex()) {
            val left = ctx.remainingMs()
            if (left <= 0 || stopped()) return null
            val hasFallback = i + 1 < sizes.size
            val reserve = if (hasFallback) tailMs + minOf(FALLBACK_RESERVE_MS, (left - tailMs) / 2).coerceAtLeast(0) else tailMs
            val ceiling = maxBytes(size, role)
            val limit = if (role == ImageRole.BACKDROP && hasFallback) throughput.limit(left - reserve, ceiling) else ceiling
            val r = call(ctx, reserve) { download(path, size, limit, role) }
            if (r is TmdbResult.Err && stopped()) return null
            when (r) {
                is TmdbResult.Ok -> if (isImage(r.value)) return sourceCache.putImage(path, size, r.value)
                is TmdbResult.Err -> when {
                    r.error.kind in KEY_ERRORS -> throw failure(r.error)
                    // Expected on a slow link, not an error worth showing in settings.
                    r.error.kind == TmdbErrorKind.TOO_LARGE && limit < ceiling -> safeEvent("image $size over the time budget")
                    // An original logo above its cap: the smaller size is good enough.
                    r.error.kind == TmdbErrorKind.TOO_LARGE && role == ImageRole.LOGO && hasFallback -> safeEvent("logo $size over the size cap")
                    else -> reportError(r.error)
                }
            }
            // w1280 is a degraded mode only, and is logged to diagnostics.
            if (hasFallback) safeEvent("image degraded → ${sizes[i + 1]}")
        }
        return null
    }

    /**
     * A download; a backdrop also feeds the throughput estimate (a logo shares the link with it). The sample is timed from
     * the response headers when [RequestDeadline] saw them, so the rate limiter wait and time to first byte are left out.
     */
    private fun download(path: String, size: String, maxBytes: Long, role: ImageRole): TmdbResult<ByteArray> {
        val started = monoMs()
        val r = tmdb.downloadImage(path, size, maxBytes)
        if (r is TmdbResult.Ok && role == ImageRole.BACKDROP) {
            val from = RequestDeadline.responseAtMs()?.takeIf { it >= started } ?: started
            throughput.record(r.value.size.toLong(), monoMs() - from)
        }
        return r
    }

    /** Hands the logo download to [logoExecutor]; false when there is none (or it refuses), so it runs serially. */
    private fun submitLogo(task: Runnable): Boolean {
        val pool = logoExecutor ?: return false
        return try {
            pool.execute(task)
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    /**
     * The logo file, or null to draw a text title. Runs the task here if no pool thread has started it yet,
     * then waits for it within T_bg. Aborts (key error, lost focus) and storage errors propagate as on the job thread.
     */
    private fun awaitLogo(task: FutureTask<File?>, ctx: JobContext, stopped: AtomicBoolean): File? {
        task.run()
        return try {
            task.get(ctx.remainingMs().coerceAtLeast(1), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            stopped.set(true)
            task.cancel(true)
            null
        } catch (_: CancellationException) {
            null
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
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

    /**
     * The job key: match key, language, appearance and the source app's badge ("-" for none). The badge is part of the
     * frame, so the same title from two apps gets two jobs; the TMDB work they share is cached by the first.
     */
    private fun pending(a: Analysis, card: FocusedCard): Outcome {
        warmUp()
        val appearance = settings.appearance()
        val badge = badgeFor(card, appearance)?.key ?: "-"
        return Outcome.Pending("${a.matchKey}|${settings.language().tmdb}|${appearance.fingerprint()}|app:$badge")
    }

    /** A card is about to be prepared: open the TMDB connections while its job starts (the client throttles this). */
    private fun warmUp() {
        try {
            background(Runnable { runCatching { tmdb.warmUp() } })
        } catch (_: Throwable) {
        }
    }

    /** The source app of a program card, when the setting shows it. Binder-thread safe. */
    private fun badgeFor(card: FocusedCard, appearance: AppearanceSettings): BadgeRef? =
        if (!appearance.showAppBadge) null else runCatching { badges.forIconUri(card.iconUri) }.getOrNull()

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
        /** Logo size when DisplayInfo asks for "original" and it fails, and the only one for a regular logo. */
        private const val LOGO_SIZE = "w500"
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
        internal const val MAX_ORIGINAL_LOGO_BYTES = 600L * 1024

        /** Logical frame resolution; part of the composite key. */
        const val CANVAS = "1920x1080"

        /**
         * Level 2 key: title, language, all displayed values, appearance, renderer version, resolution, and the source
         * app's [badge] (package and version) when one is drawn.
         * If the selected logo failed to load, the composite is published under a key with the [NO_LOGO] suffix; if the
         * badge failed to load, under the key without a badge.
         */
        fun compositeKey(info: DisplayInfo, appearance: AppearanceSettings, rendererVersion: Int, badge: BadgeRef? = null): String =
            "${info.mediaType.path}/${info.tmdbId}|${info.language.tmdb}|${info.fingerprint}|${appearance.fingerprint()}|r$rendererVersion|$CANVAS" +
                (badge?.let { "|app:${it.key}" } ?: "")

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

        /**
         * Response size limit: 8 MB for an original backdrop, [MAX_ORIGINAL_LOGO_BYTES] for an original (wide) logo, which
         * falls back to w500 above it: a logo is decoded no wider than the block, so a larger file buys nothing.
         */
        private fun maxBytes(size: String, role: ImageRole): Long = when {
            size == "original" -> if (role == ImageRole.BACKDROP) 8 * MB else MAX_ORIGINAL_LOGO_BYTES
            size == "w1280" -> 4 * MB
            else -> 2 * MB
        }

        /** The size DisplayInfo picked for the logo ("original" for a wide one), then w500. */
        private fun logoSizes(info: DisplayInfo): List<String> = listOf(info.logoSize, LOGO_SIZE).distinct()

        /** The first alternate in a script other than the primary's (the Latin half of "Cyrillic / Latin"); Cyrillic variants count as one. */
        private fun crossScriptAlternate(p: ParsedTitle): String? {
            val primary = scriptFamily(p.script)
            return p.alternates.firstOrNull { alt ->
                val family = scriptFamily(TitleText.scriptOf(alt))
                family != Script.NONE && family != primary
            }
        }

        private fun scriptFamily(s: Script): Script = when (s) {
            Script.CYRILLIC_RU, Script.CYRILLIC_UK, Script.CYRILLIC_AMBIGUOUS -> Script.CYRILLIC_AMBIGUOUS
            else -> s
        }

        private fun monoMs() = System.nanoTime() / 1_000_000

        /** Decodes bounds only, no pixels. */
        private fun decodableImage(bytes: ByteArray): Boolean {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            return o.outWidth > 0 && o.outHeight > 0
        }
    }
}
