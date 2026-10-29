package io.github.yozha.movieartdetails.core

import android.graphics.Bitmap
import io.github.yozha.movieartdetails.match.MappingKey
import io.github.yozha.movieartdetails.match.TitleParser
import io.github.yozha.movieartdetails.model.AppBadge
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
import io.github.yozha.movieartdetails.model.SearchPage
import io.github.yozha.movieartdetails.model.SettingsRepository
import io.github.yozha.movieartdetails.model.SourceCache
import io.github.yozha.movieartdetails.model.TitleDetails
import io.github.yozha.movieartdetails.model.TmdbClient
import io.github.yozha.movieartdetails.model.TmdbError
import io.github.yozha.movieartdetails.model.TmdbErrorKind
import io.github.yozha.movieartdetails.model.TmdbResult
import io.github.yozha.movieartdetails.model.Translation
import io.github.yozha.movieartdetails.model.WallpaperRenderer
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

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
    private lateinit var logos: ExecutorService
    private lateinit var pipeline: CardPipeline
    private val renderer = FakeRenderer()
    private val settings = FakeSettings()
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
        logos = Executors.newFixedThreadPool(2)
        pipeline = pipeline()
    }

    private fun pipeline(badges: AppBadges = AppBadges.NONE) = CardPipeline(
        tmdb = tmdb,
        sourceCache = cache,
        compositeStore = store,
        renderer = renderer,
        settings = settings,
        mappings = mappings,
        recent = recent,
        diagnostics = diagnostics,
        searchExecutor = search,
        logoExecutor = logos,
        background = { it.run() },
        onStorageError = { storageErrors++ },
        decodeBackdrop = { null },
        decodeLogo = { null },
        isImage = { true },
        badges = badges,
    )

    @After
    fun tearDown() {
        search.shutdownNow()
        logos.shutdownNow()
    }

    private fun key(title: String, kind: CardKind) = MappingKey.of(TitleParser.parse(title), kind)

    private fun movie(id: Int, title: String, date: String, votes: Int = 5000) =
        Candidate(MediaType.MOVIE, id, title, title, date, 50.0, votes, "en", "en-US")

    private fun show(id: Int, title: String, date: String, votes: Int = 500) =
        Candidate(MediaType.TV, id, title, title, date, 20.0, votes, "en", "en-US")

    private fun showDetails(id: Int, title: String, date: String) =
        details(id, title, date).copy(mediaType = MediaType.TV, runtimeMinutes = null, numberOfSeasons = 1, status = "Ended")

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

    // ───────────── source-app badge ─────────────

    @Test
    fun `source app badge is part of the composite key and loaded for the render`() {
        val vlc = "content://org.videolan.vlc.thumbprovider/media/1"
        val badges = FakeBadges().apply { refs[vlc] = BadgeRef("org.videolan.vlc", 5L) }
        pipeline = pipeline(badges)
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Avatar", "2009-12-15")) }
        val card = FocusedCard("Avatar", 0, vlc)

        val ready = pipeline.prepare(card, ctx) as Outcome.Ready
        assertTrue(store.keys().single().endsWith("|app:org.videolan.vlc@5"))
        assertEquals(listOf(BadgeRef("org.videolan.vlc", 5L)), badges.loaded)
        assertEquals(Outcome.Ready(ready.composite, "L2"), pipeline.quick(card))

        // The same title from another app, or after an app update, is another frame.
        assertTrue(pipeline.quick(FocusedCard("Avatar", 0, null)) is Outcome.Pending)
        badges.refs[vlc] = BadgeRef("org.videolan.vlc", 6L)
        assertTrue(pipeline.quick(card) is Outcome.Pending)
        pipeline.prepare(card, ctx)
        assertEquals(2, renderer.renders)
        assertTrue(store.keys().any { it.endsWith("|app:org.videolan.vlc@6") })
    }

    @Test
    fun `same title from two source apps gets a job per app`() {
        val plex = "content://com.plexapp.android.provider/x"
        val vlc = "content://org.videolan.vlc.thumbprovider/media/1"
        val badges = FakeBadges().apply {
            refs[plex] = BadgeRef("com.plexapp.android", 1L)
            refs[vlc] = BadgeRef("org.videolan.vlc", 5L)
        }
        pipeline = pipeline(badges)
        fun jobKey(iconUri: String?) = (pipeline.quick(FocusedCard("Dune", 0, iconUri)) as Outcome.Pending).jobKey

        assertNotEquals(jobKey(plex), jobKey(vlc))
        assertNotEquals(jobKey(plex), jobKey(null))
        assertEquals(jobKey(vlc), jobKey(vlc))
        // With the badge hidden both cards show the same frame: one job.
        settings.appearance = AppearanceSettings(showAppBadge = false)
        assertEquals(jobKey(plex), jobKey(vlc))
    }

    @Test
    fun `second app's card in the cold-load window gets a frame with its own badge`() {
        val plex = "content://com.plexapp.android.provider/x"
        val vlc = "content://org.videolan.vlc.thumbprovider/media/1"
        val badges = FakeBadges().apply {
            refs[plex] = BadgeRef("com.plexapp.android", 1L)
            refs[vlc] = BadgeRef("org.videolan.vlc", 5L)
        }
        pipeline = pipeline(badges)
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(438631, "Dune", "2021-09-15"))) }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Dune", "2021-09-15")) }
        val queue = ArrayDeque<Runnable>()
        var notified = 0
        val host = object : HostBridge {
            override fun wallpaper(composite: Composite) = Wallpaper("content://test/${composite.file.name}", WallpaperType.IMAGE)
            override fun canNotify() = true
            override fun notifyUpdated() {
                notified++
            }
        }
        val coordinator = Coordinator(pipeline, host, Executor { queue.addLast(it) }, diagnostics, neutralColor = { "#101214" }, tBlockMs = 1)

        // "Dune" in a Plex row, then "Dune" in a VLC row before the first job ran.
        coordinator.getWallpapers(Event.ProgramCardFocused(title = "Dune", type = 0, iconUri = plex))
        coordinator.getWallpapers(Event.ProgramCardFocused(title = "Dune", type = 0, iconUri = vlc))
        assertEquals(2, queue.size)
        while (queue.isNotEmpty()) queue.removeFirst().run()

        // The Plex job was skipped as stale; the VLC card got its own render, with its own badge.
        assertEquals(1, renderer.renders)
        assertEquals(listOf(BadgeRef("org.videolan.vlc", 5L)), badges.loaded)
        assertTrue(store.keys().single().endsWith("|app:org.videolan.vlc@5"))
        assertEquals(1, notified)
        val shown = coordinator.getWallpapers(Event.TimeElapsed).single()
        assertEquals("content://test/${store.find(store.keys().single())!!.name}", shown.uri)
    }

    @Test
    fun `badge that fails to load leaves its key free for the next preparation`() {
        val vlc = "content://org.videolan.vlc.thumbprovider/media/1"
        val badges = FakeBadges().apply {
            refs[vlc] = BadgeRef("org.videolan.vlc", 5L)
            failLoad = true
        }
        pipeline = pipeline(badges)
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Avatar", "2009-12-15")) }
        val card = FocusedCard("Avatar", 0, vlc)

        assertEquals("rendered", pipeline.prepare(card, ctx).note)
        assertTrue(store.keys().none { "|app:" in it })
        assertTrue(pipeline.quick(card) is Outcome.Pending)
        // Still failing: the frame without the badge is reused, not rendered again.
        assertEquals("L2:nobadge", pipeline.prepare(card, ctx).note)
        assertEquals(1, renderer.renders)

        badges.failLoad = false
        val ready = pipeline.prepare(card, ctx) as Outcome.Ready
        assertEquals("rendered", ready.note)
        assertEquals(2, renderer.renders)
        assertTrue(store.keys().any { it.endsWith("|app:org.videolan.vlc@5") })
        assertEquals(Outcome.Ready(ready.composite, "L2"), pipeline.quick(card))
    }

    @Test
    fun `hidden badge is neither looked up nor part of the key`() {
        val badges = FakeBadges().apply { refs["https://cdn.example.com/x.jpg"] = BadgeRef("com.example.app", 1L) }
        settings.appearance = AppearanceSettings(showAppBadge = false)
        pipeline = pipeline(badges)
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Avatar", "2009-12-15")) }
        assertTrue(pipeline.prepare(FocusedCard("Avatar", 0, "https://cdn.example.com/x.jpg"), ctx) is Outcome.Ready)
        assertTrue(store.keys().none { "|app:" in it })
        assertEquals(0, badges.lookups)
        assertTrue(badges.loaded.isEmpty())
    }

    @Test
    fun `composite key without a badge is unchanged`() {
        val info = DisplayInfo(MediaType.MOVIE, 1, DisplayLanguage.EN, "A", null, "Movie", null, emptyList(), null, null, null, null, "fp")
        val plain = CardPipeline.compositeKey(info, AppearanceSettings(), 1)
        assertEquals(plain, CardPipeline.compositeKey(info, AppearanceSettings(), 1, null))
        assertEquals("$plain|app:com.example.app@9", CardPipeline.compositeKey(info, AppearanceSettings(), 1, BadgeRef("com.example.app", 9L)))
        assertFalse("|app:" in plain)
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

    @Test
    fun `request deadline notes when the response headers arrived`() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("x".repeat(64)).setHeadersDelay(400, TimeUnit.MILLISECONDS))
        server.start()
        try {
            val client = OkHttpClient.Builder().addInterceptor(RequestDeadline.interceptor).build()
            RequestDeadline.within(3_000) {
                assertNull(RequestDeadline.responseAtMs())
                val started = System.nanoTime() / 1_000_000
                client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { it.body!!.string() }
                val finished = System.nanoTime() / 1_000_000
                val at = RequestDeadline.responseAtMs()
                assertNotNull(at)
                assertTrue("headers after ${at!! - started} ms", at - started >= 350)
                assertTrue(at <= finished)
            }
            assertNull(RequestDeadline.responseAtMs())
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

    // ───────────── ID tag sanity check ─────────────

    @Test
    fun `imdb tag pointing to an unrelated title falls back to search`() {
        tmdb.findFn = { _, _ -> TmdbResult.Ok(listOf(movie(990001, "Unrelated Placeholder", "2022-05-01", votes = 2))) }
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Avatar", "2009-12-15")) }

        val out = pipeline.prepare(FocusedCard("Avatar (2009) [imdbid-tt1234567]", 0, null), ctx)
        assertEquals("https://www.themoviedb.org/movie/19995", (out as Outcome.Ready).composite.sourceUrl)
        assertTrue("find" in tmdb.calls)
        assertTrue("search/movie" in tmdb.calls)
    }

    @Test
    fun `imdb tag with a differently named title is kept when the year agrees`() {
        // A tag on a localized card: /find answers in another language, but the year confirms it.
        val found = Candidate(MediaType.MOVIE, 19995, "Аватар", "Аватар", "2009-12-15", 50.0, 5000, "en", "ru-RU")
        tmdb.findFn = { _, _ -> TmdbResult.Ok(listOf(found)) }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Avatar", "2009-12-15")) }

        val out = pipeline.prepare(FocusedCard("Avatar (2009) [imdbid-tt0499549]", 0, null), ctx)
        assertEquals("https://www.themoviedb.org/movie/19995", (out as Outcome.Ready).composite.sourceUrl)
        assertTrue(tmdb.calls.none { it.startsWith("search") })
    }

    @Test
    fun `tmdbid tag that disagrees with the title and year falls back to search and is remembered`() {
        tmdb.detailsFn = { _, id, _ ->
            TmdbResult.Ok(if (id == 1) details(1, "Completely Different Story", "1999-01-01") else details(id, "Avatar", "2009-12-15"))
        }
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        val card = FocusedCard("Avatar (2009) [tmdbid-1]", 0, null)

        val out = pipeline.prepare(card, ctx)
        assertEquals("https://www.themoviedb.org/movie/19995", (out as Outcome.Ready).composite.sourceUrl)
        assertTrue("search/movie" in tmdb.calls)
        // The search result is saved under the tagged key, so the quick path serves it without asking again.
        assertEquals("L2", (pipeline.quick(card) as Outcome.Ready).note)
    }

    @Test
    fun `tmdbid tag that does not exist falls back to search`() {
        tmdb.detailsFn = { _, id, _ ->
            if (id == 1) TmdbResult.Err(TmdbError(TmdbErrorKind.NOT_FOUND, 404, 34)) else TmdbResult.Ok(details(id, "Avatar", "2009-12-15"))
        }
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }

        val out = pipeline.prepare(FocusedCard("Avatar [tmdbid-1]", 0, null), ctx)
        assertEquals("https://www.themoviedb.org/movie/19995", (out as Outcome.Ready).composite.sourceUrl)
    }

    @Test
    fun `tmdbid tag alone is taken as is`() {
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Avatar", "2009-12-15")) }
        val out = pipeline.prepare(FocusedCard("[tmdbid-19995]", 0, null), ctx)
        assertEquals("https://www.themoviedb.org/movie/19995", (out as Outcome.Ready).composite.sourceUrl)
        assertEquals(listOf("details"), tmdb.calls.toList())
    }

    @Test
    fun `tmdbid tag with an unknown type asks movie and tv and picks the one named like the card`() {
        tmdb.detailsFn = { type, id, _ ->
            TmdbResult.Ok(
                if (type == MediaType.MOVIE) {
                    details(id, "The Matrix", "1999-03-30").copy(translations = listOf(Translation("ru", "RU", "Матрица", null)))
                } else {
                    showDetails(id, "Synthetic Talk Show", "2004-09-22")
                },
            )
        }
        val out = pipeline.prepare(FocusedCard("Матрица [tmdbid-603]", null, null), ctx)
        assertEquals("https://www.themoviedb.org/movie/603", (out as Outcome.Ready).composite.sourceUrl)
        assertEquals(setOf(MediaType.MOVIE, MediaType.TV), tmdb.detailsTypes.toSet())
        assertTrue(tmdb.calls.none { it.startsWith("search") })
    }

    @Test
    fun `tmdbid tag with an unknown type searches when neither title fits`() {
        tmdb.detailsFn = { type, id, _ ->
            TmdbResult.Ok(if (type == MediaType.MOVIE) details(id, "First Unrelated", "1990-01-01") else showDetails(id, "Second Unrelated", "1991-01-01"))
        }
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(emptyList()) }
        val out = pipeline.prepare(FocusedCard("Avatar [tmdbid-603]", null, null), ctx)
        assertEquals(Outcome.Rejected("low-confidence"), out)
        assertTrue("search/movie" in tmdb.calls)
        assertTrue("search/tv" in tmdb.calls)
    }

    // ───────────── year retry ─────────────

    @Test
    fun `year filter is not retried when every first page was complete`() {
        tmdb.totalPages = { _, _, _ -> 1 }
        tmdb.movies = { q, _, _ ->
            TmdbResult.Ok(if (q == "Dune 1984") listOf(movie(841, "Dune", "1984-12-14")) else listOf(movie(438631, "Dune", "2021-09-15")))
        }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Dune", "1984-12-14")) }

        val out = pipeline.prepare(FocusedCard("Dune (1984)", 0, null), ctx)
        assertTrue(out is Outcome.Ready)
        // Instead of the year filter, the year is tried as part of the query.
        assertEquals(listOf<Int?>(null, null), tmdb.searchYears)
        assertEquals(listOf("movie|en-US|Dune|null", "movie|en-US|Dune 1984|null"), tmdb.searches)
        assertEquals(MediaType.MOVIE to 841, cache.getMatch(key("Dune (1984)", CardKind.MOVIE)))
    }

    @Test
    fun `year filter is retried only for the searches with more than one page`() {
        tmdb.totalPages = { type, _, _ -> if (type == MediaType.MOVIE) 3 else 1 }
        tmdb.movies = { _, _, year ->
            TmdbResult.Ok(if (year == 1984) listOf(movie(841, "Dune", "1984-12-14")) else listOf(movie(438631, "Dune", "2021-09-15")))
        }
        tmdb.shows = { _, _, _ -> TmdbResult.Ok(listOf(show(90228, "Dune: Prophecy", "2024-11-17"))) }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Dune", "1984-12-14")) }

        val out = pipeline.prepare(FocusedCard("Dune (1984)", null, null), ctx)
        assertEquals("https://www.themoviedb.org/movie/841", (out as Outcome.Ready).composite.sourceUrl)
        assertEquals(listOf<Int?>(null, 1984), tmdb.searchYears)
        assertEquals(1, tmdb.calls.count { it == "search/tv" })
    }

    @Test
    fun `nothing found means no year retry`() {
        tmdb.totalPages = { _, _, _ -> 1 }
        val out = pipeline.prepare(FocusedCard("Qwzxv Plorbnik (2019)", null, null), ctx)
        assertEquals(Outcome.Rejected("low-confidence"), out)
        assertEquals(2, tmdb.searches.size)
    }

    // ───────────── wider search on a miss ─────────────

    @Test
    fun `type 0 card that matches no movie is also searched as a series`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(346364, "It", "2017-09-06"))) }
        tmdb.shows = { _, _, _ -> TmdbResult.Ok(listOf(show(19614, "It", "1990-11-18"))) }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(showDetails(id, "It", "1990-11-18")) }

        val out = pipeline.prepare(FocusedCard("It (1990)", 0, null), ctx)
        assertEquals("https://www.themoviedb.org/tv/19614", (out as Outcome.Ready).composite.sourceUrl)
        assertEquals(listOf("tv|en-US|It|null"), tmdb.searches.filter { it.startsWith("tv|") })
    }

    @Test
    fun `wider pass keeps a season marker in a plain type 0 title as part of the movie title`() {
        // "Open Season 2" parses as "Open" + season 2; for a movie card the marker is part of the title.
        assertEquals(2, TitleParser.parse("Open Season 2").season)
        // The movie pass is ambiguous; the wider pass then finds a series named after the cut primary.
        tmdb.movies = { _, _, _ ->
            TmdbResult.Ok(listOf(movie(990011, "Open Season 2", "2008-09-24", votes = 900), movie(990012, "Open", "2010-01-01", votes = 400)))
        }
        tmdb.shows = { _, _, _ -> TmdbResult.Ok(listOf(show(990013, "Open", "2015-01-01", votes = 30))) }
        tmdb.detailsFn = { type, id, _ ->
            TmdbResult.Ok(if (type == MediaType.TV) showDetails(id, "Open", "2015-01-01") else details(id, "Open Season 2", "2008-09-24"))
        }

        val out = pipeline.prepare(FocusedCard("Open Season 2", 0, null), ctx)
        assertTrue("tv|en-US|Open|null" in tmdb.searches)
        // Read as an episode marker, the series would score above both movies and be accepted.
        assertEquals(Outcome.Rejected("low-confidence"), out)
        assertNull(cache.getMatch(key("Open Season 2", CardKind.MOVIE)))
    }

    @Test
    fun `alternate title in another script is searched in its own language on a miss`() {
        tmdb.movies = { q, _, _ -> TmdbResult.Ok(if (q == "Solaris") listOf(movie(593, "Solaris", "1972-02-05", votes = 900)) else emptyList()) }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Solaris", "1972-02-05")) }

        val out = pipeline.prepare(FocusedCard("Солярис / Solaris", null, null), ctx)
        assertEquals("https://www.themoviedb.org/movie/593", (out as Outcome.Ready).composite.sourceUrl)
        assertTrue("movie|en-US|Solaris|null" in tmdb.searches)
        assertTrue(tmdb.searches.filter { "Solaris" !in it }.all { "|Солярис|" in it })
    }

    @Test
    fun `an accepted match needs no wider search`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = { _, id, _ -> TmdbResult.Ok(details(id, "Avatar", "2009-12-15")) }
        assertTrue(pipeline.prepare(FocusedCard("Avatar", 0, null), ctx) is Outcome.Ready)
        assertEquals(listOf("movie|en-US|Avatar|null"), tmdb.searches)
    }

    // ───────────── images ─────────────

    private fun withImages(logo: ImageInfo) = { _: MediaType, id: Int, _: String ->
        TmdbResult.Ok(
            details(id, "Avatar", "2009-12-15").copy(
                backdropPath = "/b.jpg", backdrops = listOf(ImageInfo("/b.jpg", null, 3840, 2160, 5.0)), logos = listOf(logo),
            ),
        )
    }

    @Test
    fun `logo downloads while the backdrop is still loading`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = withImages(ImageInfo("/logo.png", "en", 1000, 250, 5.0))
        val logoRequested = CountDownLatch(1)
        var overlapped = false
        tmdb.imageFn = { path, _ ->
            if (path == "/logo.png") logoRequested.countDown() else overlapped = logoRequested.await(3, TimeUnit.SECONDS)
            TmdbResult.Ok(byteArrayOf(1, 2, 3))
        }
        val out = pipeline.prepare(FocusedCard("Avatar", 0, null), ctx)
        assertTrue("the logo request did not overlap the backdrop", overlapped)
        assertNotNull(cache.getImage("/logo.png", "w500"))
        // Bitmap cannot be decoded on the JVM, so the test stops at this step.
        assertEquals(Outcome.Failed("backdrop-decode"), out)
    }

    @Test
    fun `failed backdrop stops the logo download`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = withImages(ImageInfo("/logo.png", "en", 1000, 250, 5.0))
        val logoStarted = CountDownLatch(1)
        val logoInterrupted = CountDownLatch(1)
        tmdb.imageFn = { path, _ ->
            if (path == "/logo.png") {
                logoStarted.countDown()
                try {
                    Thread.sleep(5_000)
                    TmdbResult.Ok(byteArrayOf(1))
                } catch (e: InterruptedException) {
                    logoInterrupted.countDown()
                    TmdbResult.Err(TmdbError(TmdbErrorKind.NETWORK, message = "interrupted"))
                }
            } else {
                logoStarted.await(2, TimeUnit.SECONDS)
                TmdbResult.Err(TmdbError(TmdbErrorKind.NOT_FOUND, 404))
            }
        }
        val started = System.nanoTime()
        assertEquals(Outcome.Failed("backdrop"), pipeline.prepare(FocusedCard("Avatar", 0, null), ctx))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        assertTrue(logoInterrupted.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `canceled logo download is not reported as a network error`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        // A wide logo has a w500 fallback after original: a canceled task must not move on to it.
        tmdb.detailsFn = withImages(ImageInfo("/wide.png", "en", 2000, 200, 5.0))
        val logoStarted = CountDownLatch(1)
        tmdb.imageFn = { path, _ ->
            if (path == "/wide.png") {
                logoStarted.countDown()
                try {
                    Thread.sleep(5_000)
                    TmdbResult.Ok(byteArrayOf(1))
                } catch (e: InterruptedException) {
                    // As OkHttp does: the interrupt becomes a network error and the flag is cleared.
                    TmdbResult.Err(TmdbError(TmdbErrorKind.NETWORK, message = "image: InterruptedIOException"))
                }
            } else {
                logoStarted.await(2, TimeUnit.SECONDS)
                TmdbResult.Err(TmdbError(TmdbErrorKind.NOT_FOUND, 404))
            }
        }
        assertEquals(Outcome.Failed("backdrop"), pipeline.prepare(FocusedCard("Avatar", 0, null), ctx))
        logos.shutdown()
        assertTrue(logos.awaitTermination(3, TimeUnit.SECONDS))

        assertTrue(diagnostics.errors.toString(), diagnostics.errors.none { it.kind == TmdbErrorKind.NETWORK })
        assertEquals(listOf("original|/wide.png"), tmdb.images.filter { "/wide.png" in it }.map { it.substringBeforeLast('|') })
    }

    @Test
    fun `wide logo is downloaded at original with w500 as the fallback`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = { _, id, _ ->
            TmdbResult.Ok(details(id, "Avatar", "2009-12-15").copy(logos = listOf(ImageInfo("/wide.png", "en", 2000, 200, 5.0))))
        }
        tmdb.imageFn = { _, size ->
            if (size == "original") TmdbResult.Err(TmdbError(TmdbErrorKind.NOT_FOUND, 404)) else TmdbResult.Ok(byteArrayOf(1, 2, 3))
        }
        val out = pipeline.prepare(FocusedCard("Avatar", 0, null), ctx)
        assertEquals("rendered", (out as Outcome.Ready).note)
        assertEquals(listOf("original|/wide.png", "w500|/wide.png"), tmdb.images.map { it.substringBeforeLast('|') })
        // A logo at original gets a logo-sized limit, not the 8 MB of an original backdrop.
        assertEquals(CardPipeline.MAX_ORIGINAL_LOGO_BYTES, tmdb.images.first().substringAfterLast('|').toLong())
        assertEquals(600L * 1024, CardPipeline.MAX_ORIGINAL_LOGO_BYTES)
        assertNotNull(cache.getImage("/wide.png", "w500"))
    }

    @Test
    fun `original logo over its size cap falls back to w500 without an error`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"))) }
        tmdb.detailsFn = { _, id, _ ->
            TmdbResult.Ok(details(id, "Avatar", "2009-12-15").copy(logos = listOf(ImageInfo("/wide.png", "en", 2000, 200, 5.0))))
        }
        tmdb.imageFn = { _, size ->
            if (size == "original") {
                TmdbResult.Err(TmdbError(TmdbErrorKind.TOO_LARGE, 200, message = "image: larger than the limit"))
            } else {
                TmdbResult.Ok(byteArrayOf(1, 2, 3))
            }
        }
        val out = pipeline.prepare(FocusedCard("Avatar", 0, null), ctx)
        assertEquals("rendered", (out as Outcome.Ready).note)
        assertFalse(store.keys().single().endsWith("|nologo"))
        assertEquals(listOf("original|/wide.png", "w500|/wide.png"), tmdb.images.map { it.substringBeforeLast('|') })
        assertTrue(diagnostics.errors.isEmpty())
    }

    @Test
    fun `original backdrop is capped by the measured throughput and falls back to w1280`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"), movie(76600, "Avatar: The Way of Water", "2022-12-14"))) }
        tmdb.detailsFn = { _, id, _ ->
            TmdbResult.Ok(
                details(id, "Avatar", "2009-12-15").copy(
                    backdropPath = "/b$id.jpg", backdrops = listOf(ImageInfo("/b$id.jpg", null, 3840, 2160, 5.0)),
                ),
            )
        }
        val listedBytes = 3L * 1024 * 1024
        tmdb.imageFn = { path, size ->
            when {
                // The first card measures a slow link: 300 KB in about 800 ms.
                path == "/b19995.jpg" -> {
                    Thread.sleep(800)
                    TmdbResult.Ok(ByteArray(300 * 1024))
                }
                // Content-Length is above the limit: the client gives up right after the headers.
                size == "original" && tmdb.images.last().substringAfterLast('|').toLong() < listedBytes ->
                    TmdbResult.Err(TmdbError(TmdbErrorKind.TOO_LARGE, 200, message = "image: larger than the limit"))
                else -> TmdbResult.Ok(ByteArray(1024))
            }
        }
        pipeline.prepare(FocusedCard("Avatar", 0, null), ctx)
        val out = pipeline.prepare(FocusedCard("Avatar: The Way of Water (2022)", 0, null), ctx)

        val second = tmdb.images.filter { "/b76600.jpg" in it }
        assertEquals(listOf("original", "w1280"), second.map { it.substringBefore('|') })
        val limit = second[0].substringAfterLast('|').toLong()
        assertTrue("limit $limit", limit < listedBytes)
        // The w1280 fallback keeps its own fixed limit.
        assertEquals(4L * 1024 * 1024, second[1].substringAfterLast('|').toLong())
        assertNotNull(cache.getImage("/b76600.jpg", "w1280"))
        // An over-budget original is expected on a slow link: it is not reported as an error.
        assertTrue(diagnostics.errors.none { it.kind == TmdbErrorKind.TOO_LARGE })
        assertEquals(Outcome.Failed("backdrop-decode"), out)
    }

    @Test
    fun `logo downloads do not feed the throughput estimate`() {
        tmdb.movies = { _, _, _ -> TmdbResult.Ok(listOf(movie(19995, "Avatar", "2009-12-15"), movie(76600, "Avatar: The Way of Water", "2022-12-14"))) }
        tmdb.detailsFn = { _, id, _ ->
            TmdbResult.Ok(
                details(id, "Avatar", "2009-12-15").copy(
                    backdropPath = "/b$id.jpg", backdrops = listOf(ImageInfo("/b$id.jpg", null, 3840, 2160, 5.0)),
                    logos = listOf(ImageInfo("/l$id.png", "en", 1000, 250, 5.0)),
                ),
            )
        }
        tmdb.imageFn = { path, _ ->
            if (path.startsWith("/l")) {
                // A large, slow logo next to the backdrop: it shares the link, so it says little about the bandwidth.
                Thread.sleep(600)
                TmdbResult.Ok(ByteArray(400 * 1024))
            } else {
                TmdbResult.Ok(ByteArray(1024))
            }
        }
        pipeline.prepare(FocusedCard("Avatar", 0, null), ctx)
        pipeline.prepare(FocusedCard("Avatar: The Way of Water (2022)", 0, null), ctx)

        val original = tmdb.images.first { it.startsWith("original|/b76600.jpg") }
        assertEquals(8L * 1024 * 1024, original.substringAfterLast('|').toLong())
    }

    // ───────────── connection warm-up ─────────────

    @Test
    fun `pending card warms up the connections, a rejected one does not`() {
        pipeline.quick(FocusedCard("4K UHD Rainforest Sounds Ambient Film", 4, "https://i.ytimg.com/vi/x/hq.jpg"))
        assertEquals(0, tmdb.warmUps.get())
        assertTrue(pipeline.quick(FocusedCard("Avatar", 0, null)) is Outcome.Pending)
        assertEquals(1, tmdb.warmUps.get())
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
        /** "type|language|query|year" of every search. */
        val searches = CopyOnWriteArrayList<String>()
        val detailsTypes = CopyOnWriteArrayList<MediaType>()
        /** "size|path|maxBytes" of every image request. */
        val images = CopyOnWriteArrayList<String>()
        val warmUps = AtomicInteger()
        /** total_pages of a search page; null means unknown, as from a client without paging totals. */
        var totalPages: (MediaType, String, Int?) -> Int? = { _, _, _ -> null }
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

        override fun searchPage(type: MediaType, query: String, language: String, year: Int?): TmdbResult<SearchPage> {
            searches += "${type.path}|$language|$query|$year"
            return when (val r = if (type == MediaType.MOVIE) searchMovie(query, language, year) else searchTv(query, language, year)) {
                is TmdbResult.Ok -> TmdbResult.Ok(SearchPage(r.value, totalPages(type, query, year), r.value.size))
                is TmdbResult.Err -> r
            }
        }

        override fun warmUp() {
            warmUps.incrementAndGet()
        }

        override fun findByImdb(imdbId: String, language: String): TmdbResult<List<Candidate>> {
            calls += "find"
            return findFn(imdbId, language)
        }

        override fun details(mediaType: MediaType, id: Int, language: String): TmdbResult<TitleDetails> {
            calls += "details"
            detailsTypes += mediaType
            return detailsFn(mediaType, id, language)
        }

        override fun downloadImage(filePath: String, size: String, maxBytes: Long): TmdbResult<ByteArray> {
            calls += "image"
            images += "$size|$filePath|$maxBytes"
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
        @Volatile var renders = 0
        override fun render(backdrop: Bitmap?, logo: Bitmap?, info: DisplayInfo, settings: AppearanceSettings, out: File, badge: AppBadge?) {
            fail?.let { throw it }
            renders++
            out.writeText("jpeg:${info.tmdbId}")
        }
        override fun renderTitled(title: String, badge: AppBadge?, background: Int, settings: AppearanceSettings, out: File) =
            throw UnsupportedOperationException()
    }

    private class FakeSettings : SettingsRepository {
        var appearance = AppearanceSettings()
        override fun language() = DisplayLanguage.EN
        override fun appearance() = appearance
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

    /** A loaded badge is recorded; its bitmap is allocated without running the (stub) constructor. */
    private class FakeBadges : AppBadges {
        val refs = HashMap<String?, BadgeRef>()
        val loaded = CopyOnWriteArrayList<BadgeRef>()
        @Volatile var lookups = 0
        /** Loading fails the way [AndroidAppBadges] reports it: null. */
        @Volatile var failLoad = false
        override fun forIconUri(iconUri: String?): BadgeRef? {
            lookups++
            return refs[iconUri]
        }
        override fun forPackage(packageName: String?): BadgeRef? = null
        override fun load(ref: BadgeRef): AppBadge? {
            if (failLoad) return null
            loaded += ref
            return AppBadge(stubBitmap, banner = true)
        }
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

/** A Bitmap instance for fakes; allocated without running the (stub) constructor. */
internal val stubBitmap: Bitmap by lazy {
    val unsafeClass = Class.forName("sun.misc.Unsafe")
    val field = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }
    unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(field.get(null), Bitmap::class.java) as Bitmap
}
