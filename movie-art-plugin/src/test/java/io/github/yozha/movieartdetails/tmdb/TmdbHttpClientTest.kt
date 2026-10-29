package io.github.yozha.movieartdetails.tmdb

import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.TmdbError
import io.github.yozha.movieartdetails.model.TmdbErrorKind
import io.github.yozha.movieartdetails.model.TmdbResult
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TmdbHttpClientTest {

    private lateinit var server: MockWebServer
    private var key: String? = KEY
    private var now = 1_000_000L
    private lateinit var client: TmdbHttpClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = newClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun newClient() = TmdbHttpClient(
        // OkHttp auto-retry is off: otherwise DISCONNECT_AT_START would swallow the next queued response.
        { key }, OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
        server.url("/3/").toString(), server.url("/t/p/").toString(),
        RateLimiter(permits = 1000), Random(42), { now },
    )

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json;charset=utf-8").setBody(body)

    private fun status(code: Int) = """{"success":false,"status_code":$code,"status_message":"synthetic"}"""

    private fun html(code: Int) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "text/html").setBody("<html><body>blocked</body></html>")

    private fun <T> ok(r: TmdbResult<T>): T {
        assertTrue("expected Ok: $r", r is TmdbResult.Ok)
        return (r as TmdbResult.Ok).value
    }

    private fun error(r: TmdbResult<*>): TmdbError {
        assertTrue("expected an error: $r", r is TmdbResult.Err)
        val e = (r as TmdbResult.Err).error
        assertFalse("key in message: ${e.message}", e.message.contains(KEY))
        assertFalse("URL in message: ${e.message}", e.message.contains("api_key") || e.message.contains("http"))
        return e
    }

    private fun anySearch() = client.searchMovie("Avatar", "en-US")

    // ───────────────────────── requests ─────────────────────────

    @Test
    fun searchMovieSendsOnlyAllowedParams() {
        server.enqueue(json(200, TestData.text("search_movie_avatar.synthetic.json")))
        val list = ok(client.searchMovie("  Аватар ", "ru-RU"))

        val req = server.takeRequest()
        assertEquals("/3/search/movie", req.requestUrl!!.encodedPath)
        assertFalse(req.path!!.contains("multi"))
        assertEquals(KEY, req.requestUrl!!.queryParameter("api_key"))
        assertEquals("Аватар", req.requestUrl!!.queryParameter("query"))
        assertEquals("ru-RU", req.requestUrl!!.queryParameter("language"))
        assertEquals("false", req.requestUrl!!.queryParameter("include_adult"))
        assertEquals("1", req.requestUrl!!.queryParameter("page"))
        assertNull(req.requestUrl!!.queryParameter("year"))

        // The row without an id is dropped; an empty title falls back to the original one, an empty date becomes null.
        assertEquals(listOf(19995, 76600, 900001), list.map { it.id })
        val avatar = list[0]
        assertEquals(MediaType.MOVIE, avatar.mediaType)
        assertEquals("Аватар", avatar.title)
        assertEquals("Avatar", avatar.originalTitle)
        assertEquals(2009, avatar.year)
        assertEquals("ru-RU", avatar.queryLanguage)
        assertEquals(31250, avatar.voteCount)
        assertEquals("Avatar: Synthetic Short", list[2].title)
        assertNull(list[2].date)
    }

    @Test
    fun searchMovieWithYear() {
        server.enqueue(json(200, """{"page":1,"results":[]}"""))
        assertEquals(emptyList<Any>(), ok(client.searchMovie("Avatar", "en-US", 2009)))
        assertEquals("2009", server.takeRequest().requestUrl!!.queryParameter("year"))
    }

    @Test
    fun searchTvUsesYearNotFirstAirDateYear() {
        server.enqueue(json(200, TestData.text("search_tv_bear.synthetic.json")))
        val list = ok(client.searchTv("Ведмідь", "uk-UA", 2022))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/3/search/tv", url.encodedPath)
        assertEquals("2022", url.queryParameter("year"))
        assertNull(url.queryParameter("first_air_date_year"))
        assertEquals("false", url.queryParameter("include_adult"))
        assertEquals(MediaType.TV, list.single().mediaType)
        assertEquals("The Bear", list.single().originalTitle)
        assertEquals(2022, list.single().year)
    }

    @Test
    fun blankQueryIsRejectedWithoutRequest() {
        assertEquals(TmdbErrorKind.BAD_REQUEST, error(client.searchTv("  ", "en-US")).kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun findByImdb() {
        server.enqueue(json(200, TestData.text("find_imdb_avatar.synthetic.json")))
        val list = ok(client.findByImdb("tt0499549", "en-US"))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/3/find/tt0499549", url.encodedPath)
        assertEquals("imdb_id", url.queryParameter("external_source"))
        assertEquals(MediaType.MOVIE, list.single().mediaType)
        assertEquals(19995, list.single().id)
    }

    @Test
    fun findRejectsMalformedImdbId() {
        assertEquals(TmdbErrorKind.BAD_REQUEST, error(client.findByImdb("0499549", "en-US")).kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun detailsRequestAndParsing() {
        server.enqueue(json(200, TestData.text("movie_avatar_ru.synthetic.json")))
        val d = ok(client.details(MediaType.MOVIE, 19995, "ru-RU"))

        val url = server.takeRequest().requestUrl!!
        assertEquals("/3/movie/19995", url.encodedPath)
        assertEquals("ru-RU", url.queryParameter("language"))
        assertEquals("images,translations,external_ids", url.queryParameter("append_to_response"))
        assertEquals("ru,en,null", url.queryParameter("include_image_language"))

        assertEquals("Аватар", d.title)
        assertEquals("Avatar", d.originalTitle)
        assertEquals("2009-12-15", d.date)
        assertEquals(162, d.runtimeMinutes)
        assertEquals(4, d.genres.size)
        assertEquals(31250, d.voteCount)
        assertEquals("tt0499549", d.imdbId)
        assertEquals(5, d.translations.size)
        val uk = d.translations.single { it.iso639 == "uk" }
        assertEquals("UA", uk.iso3166)
        assertNull("empty overview means no value", uk.overview)
        assertEquals(3, d.logos.size)
        assertEquals(3, d.backdrops.size)
        assertNull(d.backdrops.single { it.filePath == "/synthAvatarMain.jpg" }.iso639)
    }

    @Test
    fun detailsTvEnglishImageLanguages() {
        server.enqueue(json(200, TestData.text("tv_ended_ru.synthetic.json")))
        val d = ok(client.details(MediaType.TV, 1399, "en-US"))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/3/tv/1399", url.encodedPath)
        assertEquals("en,null", url.queryParameter("include_image_language"))
        assertEquals(MediaType.TV, d.mediaType)
        assertEquals("Game of Thrones", d.originalTitle)
        assertEquals("2011-04-17", d.date)
        assertEquals("2019-05-19", d.lastAirDate)
        assertEquals("Ended", d.status)
        assertEquals(8, d.numberOfSeasons)
        assertNull(d.runtimeMinutes)
        assertEquals("tt0944947", d.imdbId)
    }

    @Test
    fun validateKeyOk() {
        server.enqueue(json(200, """{"success":true,"status_code":1,"status_message":"Success."}"""))
        ok(client.validateKey())
        val url = server.takeRequest().requestUrl!!
        assertEquals("/3/authentication", url.encodedPath)
        assertEquals(KEY, url.queryParameter("api_key"))
    }

    // ───────────────────────── error classification ─────────────────────────

    private data class Case(val response: MockResponse, val kind: TmdbErrorKind, val statusCode: Int?)

    @Test
    fun errorMapping() {
        val cases = listOf(
            Case(json(401, status(7)), TmdbErrorKind.INVALID_KEY, 7),
            Case(json(401, status(3)), TmdbErrorKind.INVALID_KEY, 3),
            Case(json(401, status(14)), TmdbErrorKind.INVALID_KEY, 14),
            Case(json(401, status(10)), TmdbErrorKind.SUSPENDED_KEY, 10),
            Case(json(404, status(34)), TmdbErrorKind.NOT_FOUND, 34),
            Case(json(404, status(6)), TmdbErrorKind.NOT_FOUND, 6),
            Case(json(429, status(25)), TmdbErrorKind.RATE_LIMITED, 25),
            Case(json(500, status(11)), TmdbErrorKind.SERVER, 11),
            Case(json(503, status(46)), TmdbErrorKind.SERVER, 46),
            Case(json(504, status(24)), TmdbErrorKind.SERVER, 24),
            Case(html(502), TmdbErrorKind.SERVER, null),
            Case(json(400, status(22)), TmdbErrorKind.BAD_REQUEST, 22),
            Case(json(422, status(22)), TmdbErrorKind.BAD_REQUEST, 22),
            Case(html(403), TmdbErrorKind.NETWORK, null),
            Case(html(401), TmdbErrorKind.NETWORK, null),
            Case(html(200), TmdbErrorKind.NETWORK, null),
            Case(json(200, ""), TmdbErrorKind.NETWORK, null),
            Case(json(200, """{"page":1,"results":["""), TmdbErrorKind.BAD_RESPONSE, null),
            Case(json(200, """{"page":1,"results":"oops"}"""), TmdbErrorKind.BAD_RESPONSE, null),
            Case(json(200, status(7)), TmdbErrorKind.INVALID_KEY, 7),
        )
        for (c in cases) {
            client = newClient()
            server.enqueue(c.response)
            val e = error(anySearch())
            assertEquals("${c.response.status}: ${e.message}", c.kind, e.kind)
            assertEquals(c.response.status, c.statusCode, e.statusCode)
            assertTrue(e.message.isNotEmpty())
        }
    }

    @Test
    fun ioExceptionIsNetwork() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(TmdbErrorKind.NETWORK, error(anySearch()).kind)
    }

    @Test
    fun missingKeyIsNoKeyWithoutRequest() {
        key = null
        assertEquals(TmdbErrorKind.NO_KEY, error(anySearch()).kind)
        key = "   "
        assertEquals(TmdbErrorKind.NO_KEY, error(client.validateKey()).kind)
        assertEquals(TmdbErrorKind.NO_KEY, error(client.downloadImage("/a.png", "w500", 1000)).kind)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun keyIsTrimmed() {
        key = "  $KEY \n"
        server.enqueue(json(200, """{"page":1,"results":[]}"""))
        ok(anySearch())
        assertEquals(KEY, server.takeRequest().requestUrl!!.queryParameter("api_key"))
    }

    @Test
    fun invalidKeyStopsRequestsUntilKeyChanges() {
        server.enqueue(json(401, status(7)))
        assertEquals(TmdbErrorKind.INVALID_KEY, error(anySearch()).kind)

        assertEquals(TmdbErrorKind.INVALID_KEY, error(client.searchTv("x", "en-US")).kind)
        assertEquals(TmdbErrorKind.INVALID_KEY, error(client.details(MediaType.MOVIE, 1, "en-US")).kind)
        assertEquals(TmdbErrorKind.INVALID_KEY, error(client.findByImdb("tt0499549", "en-US")).kind)
        assertEquals(TmdbErrorKind.INVALID_KEY, error(client.downloadImage("/a.png", "w500", 1000)).kind)
        assertEquals(1, server.requestCount)

        key = OTHER_KEY
        server.enqueue(json(200, """{"page":1,"results":[]}"""))
        ok(anySearch())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun suspendedKeyStopsRequests() {
        server.enqueue(json(401, status(10)))
        assertEquals(TmdbErrorKind.SUSPENDED_KEY, error(anySearch()).kind)
        val again = error(anySearch())
        assertEquals(TmdbErrorKind.SUSPENDED_KEY, again.kind)
        assertEquals(10, again.statusCode)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun validateKeyBypassesBlockAndClearsItOnSuccess() {
        server.enqueue(json(401, status(7)))
        error(anySearch())
        server.enqueue(json(200, """{"success":true,"status_code":1}"""))
        ok(client.validateKey())
        server.enqueue(json(200, """{"page":1,"results":[]}"""))
        ok(anySearch())
        assertEquals(3, server.requestCount)
    }

    @Test
    fun validateKeyInvalidAndNetwork() {
        server.enqueue(json(401, status(7)))
        assertEquals(TmdbErrorKind.INVALID_KEY, error(client.validateKey()).kind)
        client = newClient()
        server.enqueue(json(200, """{"success":false}"""))
        // success=false without status_code is not a key error.
        assertFalse(error(client.validateKey()).kind == TmdbErrorKind.INVALID_KEY)
        client = newClient()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
        assertEquals(TmdbErrorKind.NETWORK, error(client.validateKey()).kind)
    }

    @Test
    fun htmlErrorDoesNotBlockKey() {
        server.enqueue(html(401))
        assertEquals(TmdbErrorKind.NETWORK, error(anySearch()).kind)
        server.enqueue(json(200, """{"page":1,"results":[]}"""))
        ok(anySearch())
        assertEquals(2, server.requestCount)
    }

    // ───────────────────────── 429 ─────────────────────────

    @Test
    fun retryAfterHeaderPausesRequests() {
        server.enqueue(json(429, status(25)).setHeader("Retry-After", "3"))
        val e = error(anySearch())
        assertEquals(TmdbErrorKind.RATE_LIMITED, e.kind)
        assertEquals(3000L, e.retryAfterMs)

        now += 1000
        val paused = error(anySearch())
        assertEquals(TmdbErrorKind.RATE_LIMITED, paused.kind)
        assertEquals(2000L, paused.retryAfterMs)
        assertEquals(1, server.requestCount)

        now += 2000
        server.enqueue(json(200, """{"page":1,"results":[]}"""))
        ok(anySearch())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun retryAfterFallbackIsExponentialWithJitter() {
        server.enqueue(json(429, status(25)).setHeader("Retry-After", "0"))
        val first = error(anySearch()).retryAfterMs!!
        assertTrue("$first", first in 2000L..2500L)

        now += first
        server.enqueue(json(429, status(25)))
        val second = error(anySearch()).retryAfterMs!!
        assertTrue("$second", second in 4000L..5000L)

        var last = second
        repeat(6) {
            now += last
            server.enqueue(json(429, status(25)))
            last = error(anySearch()).retryAfterMs!!
            assertTrue("$last", last <= 60_000L)
        }
        assertEquals(60_000L, last)

        // A success resets the counter.
        now += last
        server.enqueue(json(200, """{"page":1,"results":[]}"""))
        ok(anySearch())
        server.enqueue(json(429, status(25)))
        assertTrue(error(anySearch()).retryAfterMs!! in 2000L..2500L)
    }

    @Test
    fun parallel429sAreOneEpisode() {
        // Four requests went out before the pause; the server answers all of them with 429 and no Retry-After.
        val arrived = CountDownLatch(4)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                arrived.await(5, TimeUnit.SECONDS)
                return json(429, status(25))
            }
        }
        val pool = Executors.newFixedThreadPool(4)
        val delays = try {
            List(4) { pool.submit(Callable { error(anySearch()).retryAfterMs!! }) }.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(4, server.requestCount)
        // One episode: a pause of ≈ 2 s (jittered up to 2.5 s), not 2–4–8–16 s.
        for (d in delays) assertTrue("$delays", d in 2000L..2500L)
        val paused = error(anySearch())
        assertTrue("${paused.retryAfterMs}", paused.retryAfterMs!! in 1L..2500L)
        assertEquals(4, server.requestCount)

        // The next episode after the pause is the second step (4 s), not the fifth.
        now += 2500
        assertTrue(error(anySearch()).retryAfterMs!! in 4000L..5000L)
        assertEquals(5, server.requestCount)
    }

    @Test
    fun rateLimitIsNotKeyError() {
        server.enqueue(json(429, status(25)).setHeader("Retry-After", "1"))
        error(anySearch())
        now += 1000
        server.enqueue(json(200, """{"page":1,"results":[]}"""))
        ok(anySearch())
    }

    // ───────────────────────── images ─────────────────────────

    private fun image(bytes: ByteArray) =
        MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes))

    @Test
    fun downloadImageUsesSizeSegmentWithoutKey() {
        server.enqueue(image(ByteArray(64) { it.toByte() }))
        val bytes = ok(client.downloadImage("/synthLogo.png", "w500", 1000))
        assertEquals(64, bytes.size)
        val req: RecordedRequest = server.takeRequest()
        assertEquals("/t/p/w500/synthLogo.png", req.requestUrl!!.encodedPath)
        assertNull(req.requestUrl!!.queryParameter("api_key"))
    }

    @Test
    fun downloadImageTooLarge() {
        server.enqueue(image(ByteArray(100)))
        assertEquals(TmdbErrorKind.TOO_LARGE, error(client.downloadImage("/a.jpg", "original", 10)).kind)

        // Without Content-Length (chunked), the limit applies to the bytes actually read.
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setChunkedBody(Buffer().write(ByteArray(5000)), 512))
        assertEquals(TmdbErrorKind.TOO_LARGE, error(client.downloadImage("/a.jpg", "original", 1000)).kind)
    }

    @Test
    fun downloadImageErrors() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(TmdbErrorKind.NOT_FOUND, error(client.downloadImage("/a.jpg", "w1280", 1000)).kind)
        server.enqueue(html(200))
        assertEquals(TmdbErrorKind.NETWORK, error(client.downloadImage("/a.jpg", "w1280", 1000)).kind)
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(TmdbErrorKind.SERVER, error(client.downloadImage("/a.jpg", "w1280", 1000)).kind)
        assertEquals(3, server.requestCount)

        assertEquals(TmdbErrorKind.BAD_REQUEST, error(client.downloadImage("/../a.jpg", "w500", 1000)).kind)
        assertEquals(TmdbErrorKind.BAD_REQUEST, error(client.downloadImage("/a.jpg", "w500/x", 1000)).kind)
        assertEquals(3, server.requestCount)
    }

    private companion object {
        const val KEY = "0123456789abcdef0123456789abcdef"
        const val OTHER_KEY = "fedcba9876543210fedcba9876543210"
    }
}
