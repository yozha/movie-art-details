package io.github.yozha.movieartdetails.core

import android.graphics.Bitmap
import io.github.yozha.movieartdetails.match.MappingKey
import io.github.yozha.movieartdetails.match.TitleParser
import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.Candidate
import io.github.yozha.movieartdetails.model.CardKind
import io.github.yozha.movieartdetails.model.CompositeStore
import io.github.yozha.movieartdetails.model.Diagnostics
import io.github.yozha.movieartdetails.model.DisplayInfo
import io.github.yozha.movieartdetails.model.DisplayLanguage
import io.github.yozha.movieartdetails.model.ImageInfo
import io.github.yozha.movieartdetails.model.ManualMapping
import io.github.yozha.movieartdetails.model.MappingsRepository
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.RecentTitle
import io.github.yozha.movieartdetails.model.RecentTitlesRepository
import io.github.yozha.movieartdetails.model.SettingsRepository
import io.github.yozha.movieartdetails.model.SourceCache
import io.github.yozha.movieartdetails.model.TitleDetails
import io.github.yozha.movieartdetails.model.TmdbClient
import io.github.yozha.movieartdetails.model.TmdbError
import io.github.yozha.movieartdetails.model.TmdbErrorKind
import io.github.yozha.movieartdetails.model.TmdbResult
import io.github.yozha.movieartdetails.model.WallpaperRenderer
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Pipeline on fakes: matching branches, negative cache, TMDB errors and publishing.
 * Synthetic details have no images, so Bitmap and BitmapFactory are never touched.
 */
class CardPipelineTest {

    @get:Rule val tmp = TemporaryFolder()

    private val tmdb = FakeTmdb()
    private lateinit var cache: FakeSourceCache
    private val mappings = FakeMappings()
    private val recent = FakeRecent()
    private val diagnostics = FakeDiagnostics()
    private lateinit var store: FakeCompositeStore
    private lateinit var search: ExecutorService
    private lateinit var pipeline: CardPipeline
    private val renderer = FakeRenderer()
    private var storageErrors = 0

    private val ctx = object : JobContext {
        override fun isCurrent() = true
        override fun remainingMs() = 8_000L
    }

    @Before
    fun setUp() {
        store = FakeCompositeStore(tmp.newFolder("published"))
        cache = FakeSourceCache(tmp.newFolder("l1"))
        search = Executors.newFixedThreadPool(4)
        pipeline = CardPipeline(
            tmdb = tmdb,
            sourceCache = cache,
            compositeStore = store,
            renderer = renderer,
            settings = FakeSettings(),
            mappings = mappings,
            recent = recent,
            diagnostics = diagnostics,
            searchExecutor = search,
            background = { it.run() },
            onStorageError = { storageErrors++ },
            decodeBackdrop = { null },
            decodeLogo = { null },
            isImage = { true },
        )
    }

    @After
    fun tearDown() {
        search.shutdownNow()
    }

    private fun key(title: String, kind: CardKind) = MappingKey.of(TitleParser.parse(title), kind)

    private fun movie(id: Int, title: String, date: String, votes: Int = 5000) =
        Candidate(MediaType.MOVIE, id, title, title, date, 50.0, votes, "en", "en-US")

    private fun details(id: Int, title: String, date: String) = TitleDetails(
        mediaType = MediaType.MOVIE, id = id, title = title, originalTitle = title, originalLanguage = "en",
        overview = "Synthetic overview.", date = date, lastAirDate = null, status = "Released", runtimeMinutes = 120,
        numberOfSeasons = null, genres = listOf("Drama"), voteAverage = 7.5, voteCount = 1000, backdropPath = null,
        translations = emptyList(), logos = emptyList(), backdrops = emptyList(), imdbId = null,
    )

    // ───────────── quick path ─────────────

    @Test
    fun `youtube card is rejected without network and still recorded`() {
        val out = pipeline.quick(FocusedCard("4K UHD Rainforest Sounds Ambient Film", 4, "https://i.ytimg.com/vi/x/hq.jpg"))
        assertTrue(out is Outcome.Rejected)
        assertTrue(out.note.startsWith("skip"))
        assertTrue(tmdb.calls.isEmpty())
        assertEquals(1, recent.items.size)
        assertEquals("i.ytimg.com", recent.items.first().iconHost)
    }

    @Test
    fun `manual exclusion and negative cache reject in the quick path`() {
        mappings.put(ManualMapping(key("Avatar", CardKind.MOVIE), "Avatar", null))
        assertEquals(Outcome.Rejected("manual-off"), pipeline.quick(FocusedCard("Avatar", 0, null)))

        cache.putNegative(key("Up", CardKind.MOVIE))
        assertEquals(Outcome.Rejected("negative"), pipeline.quick(FocusedCard("Up", 0, null)))
    }

    @Test
    fun `unknown card is pending with a job key per title, language and appearance`() {
        val out = pipeline.quick(FocusedCard("Avatar", 0, null))
        assertTrue(out is Outcome.Pending)
        assertTrue((out as Outcome.Pending).jobKey.startsWith(key("Avatar", CardKind.MOVIE) + "|en-US|"))
    }

    // ───────────── full path ─────────────

    @Test
    fun `accepted match is rendered, published and then served from level 2`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Avatar", "2009-12-15")) }

        val out = pipeline.prepare(FocusedCard("Avatar", 0, null), ctx)
        assertTrue(out is Outcome.Ready)
        val ready = out as Outcome.Ready
        assertEquals("https://www.themoviedb.org/movie/19995", ready.composite.sourceUrl)
        assertEquals("Avatar", ready.composite.title)
        assertEquals(MediaType.MOVIE to 19995, cache.getMatch(key("Avatar", CardKind.MOVIE)))

        val again = pipeline.quick(FocusedCard("Avatar", 0, null))
        assertEquals(Outcome.Ready(ready.composite, "L2"), again)
    }

    @Test
    fun `no confident match is negative-cached`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(1, "Completely Different Story", "1999-01-01"))) }
        val out = pipeline.prepare(FocusedCard("Avatar", 0, null), ctx)
        assertTrue(out is Outcome.Rejected)
        assertTrue(cache.isNegative(key("Avatar", CardKind.MOVIE)))
    }

    @Test
    fun `network error is not negative-cached`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Err(TmdbError(TmdbErrorKind.NETWORK)) }
        val out = pipeline.prepare(FocusedCard("Avatar", 0, null), ctx)
        assertEquals(Outcome.Failed("tmdb:NETWORK"), out)
        assertFalse(cache.isNegative(key("Avatar", CardKind.MOVIE)))
    }

    @Test
    fun `invalid key rejects without negative cache and records the error`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Err(TmdbError(TmdbErrorKind.INVALID_KEY, 401, 7)) }
        val out = pipeline.prepare(FocusedCard("Avatar", 0, null), ctx)
        assertEquals(Outcome.Rejected("key:INVALID_KEY"), out)
        assertFalse(cache.isNegative(key("Avatar", CardKind.MOVIE)))
        assertEquals(TmdbErrorKind.INVALID_KEY, diagnostics.errors.single().kind)
    }

    @Test
    fun `search is repeated with the year when the first page has no matching year`() {
        tmdb.movies = { _, _, year ->
            TmdbResult.Ok(if (year == 1984) listOf(movie(841, "Dune", "1984-12-14")) else listOf(movie(438631, "Dune", "2021-09-15")))
        }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Dune", "1984-12-14")) }

        val out = pipeline.prepare(FocusedCard("Dune (1984)", 0, null), ctx)
        assertTrue(out is Outcome.Ready)
        assertEquals(listOf<Int?>(null, 1984), tmdb.searchYears)
        assertEquals(MediaType.MOVIE to 841, cache.getMatch(key("Dune (1984)", CardKind.MOVIE)))
    }

    @Test
    fun `stale details are used when TMDB is unreachable`() {
        val k = key("Avatar", CardKind.MOVIE)
        cache.putMatch(k, MediaType.MOVIE, 19995)
        cache.putStaleDetails(details(19995, "Avatar", "2009-12-15"), DisplayLanguage.EN.tmdb)
        tmdb.detailsFn = { _, _, _ -> TmdbResult.Err(TmdbError(TmdbErrorKind.NETWORK)) }

        assertTrue(pipeline.quick(FocusedCard("Avatar", 0, null)) is Outcome.Pending)
        val out = pipeline.prepare(FocusedCard("Avatar", 0, null), ctx)
        assertTrue(out is Outcome.Ready)
    }

    @Test
    fun `rate limit is retried after the pause`() {
        var attempts = 0
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = { _, id, _ ->
            if (++attempts == 1) TmdbResult.Err(TmdbError(TmdbErrorKind.RATE_LIMITED, 429, 25, retryAfterMs = 10))
            else TmdbResult.Ok(details(id, "Avatar", "2009-12-15"))
        }
        assertTrue(pipeline.prepare(FocusedCard("Avatar", 0, null), ctx) is Outcome.Ready)
        assertEquals(2, attempts)
    }

    @Test
    fun `manual mapping skips search`() {
        mappings.put(ManualMapping(key("Солярис", CardKind.UNKNOWN), "Солярис", MediaType.MOVIE to 593))
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Solaris", "1972-02-05")) }
        // VLC marks files as CLIP (type=4): the host decides, and the type is unknown.
        val out = pipeline.prepare(FocusedCard("Солярис", 4, "content://org.videolan.vlc.thumbprovider/1"), ctx)
        assertTrue(out is Outcome.Ready)
        assertTrue(tmdb.calls.none { it.startsWith("search") })
        assertNotNull(store.published.single())
    }

    // ───────────── offline: stale match ─────────────

    @Test
    fun `stale match is used when search fails only because of the network`() {
        val k = key("Avatar", CardKind.MOVIE)
        cache.putStaleMatch(k, MediaType.MOVIE, 19995)
        cache.putStaleDetails(details(19995, "Avatar", "2009-12-15"), DisplayLanguage.EN.tmdb)
        tmdb.movies = { _, _, _ -> TmdbResult.Err(TmdbError(TmdbErrorKind.NETWORK)) }
        tmdb.detailsFn = { _, _, _ -> TmdbResult.Err(TmdbError(TmdbErrorKind.NETWORK)) }

        assertTrue(pipeline.quick(FocusedCard("Avatar", 0, null)) is Outcome.Pending)
        val out = pipeline.prepare(FocusedCard("Avatar", 0, null), ctx)
        assertTrue(out is Outcome.Ready)
        assertEquals("https://www.themoviedb.org/movie/19995", (out as Outcome.Ready).composite.sourceUrl)
        // A real search attempt comes first; nothing is negative-cached.
        assertTrue("search/movie" in tmdb.calls)
        assertFalse(cache.isNegative(k))
    }

    @Test
    fun `stale match is not used on key errors`() {
        cache.putStaleMatch(key("Avatar", CardKind.MOVIE), MediaType.MOVIE, 19995)
        cache.putStaleDetails(details(19995, "Avatar", "2009-12-15"), DisplayLanguage.EN.tmdb)
        tmdb.movies = { _, _, _ -> TmdbResult.Err(TmdbError(TmdbErrorKind.INVALID_KEY, 401, 7)) }
        assertEquals(Outcome.Rejected("key:INVALID_KEY"), pipeline.prepare(FocusedCard("Avatar", 0, null), ctx))
    }

    @Test
    fun `refreshing details refreshes the cached match`() {
        val k = key("Avatar", CardKind.MOVIE)
        cache.putMatch(k, MediaType.MOVIE, 19995)
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Avatar", "2009-12-15")) }
        assertTrue(pipeline.prepare(FocusedCard("Avatar", 0, null), ctx) is Outcome.Ready)
        assertTrue(tmdb.calls.none { it.startsWith("search") })
        assertEquals(listOf(k, k), cache.matchWrites)
    }

    // ───────────── T_bg per request ─────────────

    @Test
    fun `slow original leaves time for the w1280 fallback`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = { _, id, _ ->
            TmdbResult.Ok(
                details(id, "Avatar", "2009-12-15").copy(
                    backdropPath = "/b.jpg", backdrops = listOf(ImageInfo("/b.jpg", null, 3840, 2160, 5.0)),
                ),
            )
        }
        val budgets = CopyOnWriteArrayList<Pair<String, Long>>()
        tmdb.imageFn = { _, size ->
            val budget = RequestDeadline.remainingMs() ?: -1L
            budgets += size to budget
            if (size == "original") {
                // Slow network: original misses its deadline and is canceled.
                Thread.sleep(budget.coerceAtLeast(0))
                TmdbResult.Err(TmdbError(TmdbErrorKind.NETWORK, message = "Canceled"))
            } else {
                TmdbResult.Ok(byteArrayOf(1, 2, 3))
            }
        }
        val out = pipeline.prepare(FocusedCard("Avatar", 0, null), deadlineCtx(1_500))

        assertEquals(listOf("original", "w1280"), budgets.map { it.first })
        val original = budgets[0].second
        assertTrue("original: $original", original in 1..800)
        assertTrue("w1280: ${budgets[1].second}", budgets[1].second >= 300)
        assertNotNull(cache.getImage("/b.jpg", "w1280"))
        // Bitmap cannot be decoded on the JVM, so the test stops at this step.
        assertEquals(Outcome.Failed("backdrop-decode"), out)
    }

    @Test
    fun `request deadline cancels a slow OkHttp call`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("x".repeat(64)).setBodyDelay(5, TimeUnit.SECONDS))
        server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor(RequestDeadline.interceptor).build()
            val started = System.nanoTime()
            val error = runCatching {
                RequestDeadline.within(300) {
                    client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body!!.string() }
                }
            }.exceptionOrNull()
            val tookMs = (System.nanoTime() - started) / 1_000_000
            assertTrue("error: $error", error is IOException)
            assertTrue("took $tookMs ms", tookMs < 3_000)
            assertNull(RequestDeadline.remainingMs())
        } finally {
            server.shutdown()
        }
    }

    // ───────────── stale job ─────────────

    @Test
    fun `job of a card that lost focus stops before the next request`() {
        val current = AtomicBoolean(true)
        tmdb.movies = { _, _, _ ->
            current.set(false)
            TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15")))
        }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Avatar", "2009-12-15")) }
        val ctx = object : JobContext {
            override fun isCurrent() = current.get()
            override fun remainingMs() = 8_000L
        }
        assertEquals(Outcome.Failed(Outcome.STALE), pipeline.prepare(FocusedCard("Avatar", 0, null), ctx))
        assertTrue("details" !in tmdb.calls)
        // Whatever was already fetched is kept.
        assertEquals(MediaType.MOVIE to 19995, cache.getMatch(key("Avatar", CardKind.MOVIE)))
        assertTrue(store.published.isEmpty())
    }

    // ───────────── ID tags ─────────────

    @Test
    fun `tmdbid tag wins over the cached match of the plain title and gets its own job`() {
        cache.putMatch(key("Avatar", CardKind.MOVIE), MediaType.MOVIE, 1)
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Avatar", "2009-12-15")) }
        val plain = FocusedCard("Avatar", 0, null)
        val tagged = FocusedCard("Avatar [tmdbid-19995]", 0, null)

        val p1 = pipeline.quick(plain) as Outcome.Pending
        val p2 = pipeline.quick(tagged) as Outcome.Pending
        assertNotEquals(p1.jobKey, p2.jobKey)

        val out = pipeline.prepare(tagged, ctx)
        assertEquals("https://www.themoviedb.org/movie/19995", (out as Outcome.Ready).composite.sourceUrl)
    }

    @Test
    fun `imdb tag is resolved by find, not by the cached match of the plain title`() {
        cache.putMatch(key("Avatar", CardKind.MOVIE), MediaType.MOVIE, 1)
        tmdb.findFn = { _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Avatar", "2009-12-15")) }

        val out = pipeline.prepare(FocusedCard("Avatar [imdbid-tt0499549]", 0, null), ctx)
        assertEquals("https://www.themoviedb.org/movie/19995", (out as Outcome.Ready).composite.sourceUrl)
        assertTrue("find" in tmdb.calls)
        assertEquals(MediaType.MOVIE to 1, cache.getMatch(key("Avatar", CardKind.MOVIE)))
    }

    // ───────────── logo and disk ─────────────

    @Test
    fun `failed logo falls back to the text title under its own key and is retried`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = { _, id, _ ->
            TmdbResult.Ok(details(id, "Avatar", "2009-12-15").copy(logos = listOf(ImageInfo("/logo.png", "en", 1000, 250, 5.0))))
        }
        tmdb.imageFn = { _, _ -> TmdbResult.Err(TmdbError(TmdbErrorKind.NOT_FOUND, 404)) }

        val out = pipeline.prepare(FocusedCard("Avatar", 0, null), ctx)
        assertEquals("rendered", (out as Outcome.Ready).note)
        assertTrue(store.keys().single().endsWith("|nologo"))

        // The next preparation tries the logo again and, without it, reuses the ready text-title variant.
        val again = pipeline.prepare(FocusedCard("Avatar", 0, null), ctx)
        assertEquals("L2:nologo", (again as Outcome.Ready).note)
        assertEquals(2, tmdb.calls.count { it == "image" })
        assertEquals(1, store.published.size)
    }

    @Test
    fun `logo key error still rejects the card`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = { _, id, _ ->
            TmdbResult.Ok(details(id, "Avatar", "2009-12-15").copy(logos = listOf(ImageInfo("/logo.png", "en", 1000, 250, 5.0))))
        }
        tmdb.imageFn = { _, _ -> TmdbResult.Err(TmdbError(TmdbErrorKind.SUSPENDED_KEY, 401, 10)) }
        assertEquals(Outcome.Rejected("key:SUSPENDED_KEY"), pipeline.prepare(FocusedCard("Avatar", 0, null), ctx))
    }

    @Test
    fun `disk write failure trims the cache once and fails the card`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Avatar", "2009-12-15")) }
        renderer.fail = IOException("ENOSPC")
        assertEquals(Outcome.Failed("storage"), pipeline.prepare(FocusedCard("Avatar", 0, null), ctx))
        assertEquals(1, storageErrors)
    }

    private fun deadlineCtx(budgetMs: Long): JobContext {
        val deadline = System.nanoTime() / 1_000_000 + budgetMs
        return object : JobContext {
            override fun isCurrent() = true
            override fun remainingMs() = deadline - System.nanoTime() / 1_000_000
        }
    }

    // ───────────── fakes ─────────────

    private class FakeTmdb : TmdbClient {
        val calls = CopyOnWriteArrayList<String>()
        val searchYears = CopyOnWriteArrayList<Int?>()
        var movies: (String, String, Int?) -> TmdbResult<List<Candidate>> = { _, _, _ -> TmdbResult.Ok(emptyList()) }
        var shows: (String, String, Int?) -> TmdbResult<List<Candidate>> = { _, _, _ -> TmdbResult.Ok(emptyList()) }
        var detailsFn: (MediaType, Int, String) -> TmdbResult<TitleDetails> = { _, _, _ -> TmdbResult.Err(TmdbError(TmdbErrorKind.NOT_FOUND)) }
        var findFn: (String, String) -> TmdbResult<List<Candidate>> = { _, _ -> TmdbResult.Ok(emptyList()) }
        var imageFn: (String, String) -> TmdbResult<ByteArray> = { _, _ -> TmdbResult.Err(TmdbError(TmdbErrorKind.NETWORK)) }

        override fun validateKey(): TmdbResult<Unit> = TmdbResult.Ok(Unit)

        override fun searchMovie(query: String, language: String, year: Int?): TmdbResult<List<Candidate>> {
            calls += "search/movie"
            searchYears += year
            return movies(query, language, year)
        }

        override fun searchTv(query: String, language: String, year: Int?): TmdbResult<List<Candidate>> {
            calls += "search/tv"
            return shows(query, language, year)
        }

        override fun findByImdb(imdbId: String, language: String): TmdbResult<List<Candidate>> {
            calls += "find"
            return findFn(imdbId, language)
        }

        override fun details(mediaType: MediaType, id: Int, language: String): TmdbResult<TitleDetails> {
            calls += "details"
            return detailsFn(mediaType, id, language)
        }

        override fun downloadImage(filePath: String, size: String, maxBytes: Long): TmdbResult<ByteArray> {
            calls += "image"
            return imageFn(filePath, size)
        }
    }

    private class FakeSourceCache(private val dir: File) : SourceCache {
        private val details = HashMap<String, Pair<TitleDetails, Boolean>>()
        private val negatives = HashSet<String>()
        /** Value: (type, id) and a "stale" flag. */
        private val matches = HashMap<String, Pair<Pair<MediaType, Int>, Boolean>>()
        private val images = HashMap<String, File>()
        val matchWrites = CopyOnWriteArrayList<String>()

        fun putStaleMatch(key: String, type: MediaType, id: Int) {
            matches[key] = (type to id) to true
        }

        fun putStaleDetails(d: TitleDetails, language: String) {
            details["${d.mediaType}/${d.id}/$language"] = d to true
        }

        override fun getDetails(type: MediaType, id: Int, language: String, allowStale: Boolean): TitleDetails? =
            details["$type/$id/$language"]?.takeIf { !it.second || allowStale }?.first

        override fun putDetails(details: TitleDetails, language: String) {
            this.details["${details.mediaType}/${details.id}/$language"] = details to false
        }

        @Synchronized
        override fun getImage(filePath: String, size: String): File? = images["$size|$filePath"]

        @Synchronized
        override fun putImage(filePath: String, size: String, bytes: ByteArray): File =
            File(dir, "img${images.size}.bin").apply { writeBytes(bytes) }.also { images["$size|$filePath"] = it }

        override fun isNegative(titleKey: String) = titleKey in negatives
        override fun putNegative(titleKey: String) {
            negatives += titleKey
        }
        override fun getMatch(titleKey: String): Pair<MediaType, Int>? = getMatch(titleKey, allowStale = false)
        override fun getMatch(titleKey: String, allowStale: Boolean): Pair<MediaType, Int>? =
            matches[titleKey]?.takeIf { !it.second || allowStale }?.first
        override fun putMatch(titleKey: String, type: MediaType, id: Int) {
            matches[titleKey] = (type to id) to false
            matchWrites += titleKey
        }
        override fun sizeBytes() = 0L
        override fun clear() = Unit
    }

    private class FakeCompositeStore(override val dir: File) : CompositeStore {
        private val byKey = HashMap<String, File>()
        val published = ArrayList<File>()

        override fun find(key: String): File? = byKey[key]

        fun keys(): Set<String> = byKey.keys

        override fun publish(key: String, write: (File) -> Unit): File {
            val file = File(dir, "c${published.size}.jpg")
            write(file)
            byKey[key] = file
            published += file
            return file
        }

        override fun markServed(file: File) = Unit
        override fun trim(maxBytes: Long) = Unit
        override fun sizeBytes() = 0L
        override fun clear() = Unit
    }

    private class FakeRenderer : WallpaperRenderer {
        override val version = 1
        var fail: Throwable? = null
        override fun render(backdrop: Bitmap?, logo: Bitmap?, info: DisplayInfo, settings: AppearanceSettings, out: File) {
            fail?.let { throw it }
            out.writeText("jpeg:${info.tmdbId}")
        }
    }

    private class FakeSettings : SettingsRepository {
        override fun language() = DisplayLanguage.EN
        override fun appearance() = AppearanceSettings()
        override fun minVoteCount() = 10
        override fun cacheLimitBytes() = 200L * 1024 * 1024
        override fun neutralColor() = "#101214"
        override fun diagnosticsEnabled() = false
        override fun logTitlesInDiagnostics() = false
        override fun exportJson() = "{}"
        override fun importJson(json: String) = true
    }

    private class FakeMappings : MappingsRepository {
        private val map = HashMap<String, ManualMapping>()
        override fun get(key: String) = map[key]
        override fun put(mapping: ManualMapping) {
            map[mapping.key] = mapping
        }
        override fun remove(key: String) {
            map.remove(key)
        }
        override fun all() = map.values.toList()
    }

    private class FakeRecent : RecentTitlesRepository {
        val items = CopyOnWriteArrayList<RecentTitle>()
        override fun record(title: RecentTitle) {
            items += title
        }
        override fun all(): List<RecentTitle> = items
        override fun clear() = items.clear()
    }

    private class FakeDiagnostics : Diagnostics {
        val errors = CopyOnWriteArrayList<TmdbError>()
        override fun event(line: String) = Unit
        override fun lastError(error: TmdbError?) {
            if (error != null) errors += error
        }
        override fun recent(): List<String> = emptyList()
    }
}
