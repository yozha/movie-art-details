package io.github.yozha.movieartdetails.tmdb

import io.github.yozha.movieartdetails.model.Candidate
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.TitleDetails
import io.github.yozha.movieartdetails.model.TmdbClient
import io.github.yozha.movieartdetails.model.TmdbError
import io.github.yozha.movieartdetails.model.TmdbErrorKind
import io.github.yozha.movieartdetails.model.TmdbResult
import java.io.IOException
import kotlin.random.Random
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import okio.Buffer

/**
 * TMDB v3 client authenticated with an api_key.
 *
 * - The key is sent only as a query parameter; the URL is never logged and never ends up in [TmdbError.message].
 * - OkHttp's disk HTTP cache is disabled for every request made by this client.
 * - After INVALID_KEY / SUSPENDED_KEY no requests are made until the key value changes
 *   ([validateKey], an explicit check by the user, always runs and lifts the block on success).
 * - After a 429, every API request returns RATE_LIMITED immediately, without network, until the pause ends.
 * - Retries (5xx, 429) are up to the caller: the client only classifies the error and supplies the pause.
 */
class TmdbHttpClient internal constructor(
    private val apiKey: () -> String?,
    http: OkHttpClient,
    apiBase: String,
    imageBase: String,
    private val limiter: RateLimiter,
    private val random: Random,
    /** Monotonic milliseconds, for the pause after a 429. */
    private val nowMs: () -> Long,
) : TmdbClient {

    constructor(
        apiKey: () -> String?,
        http: OkHttpClient,
        apiBase: String = "https://api.themoviedb.org/3/",
        imageBase: String = "https://image.tmdb.org/t/p/",
    ) : this(apiKey, http, apiBase, imageBase, RateLimiter(), Random.Default, { System.nanoTime() / 1_000_000 })

    private val http: OkHttpClient = http.newBuilder().cache(null).build()
    private val apiBase: HttpUrl = apiBase.toHttpUrl()
    private val imageBase: HttpUrl = imageBase.toHttpUrl()

    /** A key rejected by TMDB and the error returned in place of requests made with it. */
    private class Blocked(val key: String, val error: TmdbError)

    @Volatile private var blocked: Blocked? = null
    @Volatile private var pausedUntilMs: Long = 0
    /** Guards writes to [pausedUntilMs], as well as [consecutive429] and [random]. */
    private val pauseLock = Any()
    /** Number of consecutive 429 episodes (not responses): parallel 429s within one episode count once. */
    private var consecutive429 = 0

    override fun validateKey(): TmdbResult<Unit> {
        val result = api("authentication", "authentication", emptyList(), ignoreBlock = true) { el ->
            val status = tmdbJson.decodeFromJsonElement(StatusDto.serializer(), el)
            require(status.success == true) { "authentication did not return success" }
        }
        if (result is TmdbResult.Ok && blocked?.key == currentKey()) blocked = null
        return result
    }

    override fun searchMovie(query: String, language: String, year: Int?) = search(MediaType.MOVIE, query, language, year)

    override fun searchTv(query: String, language: String, year: Int?) = search(MediaType.TV, query, language, year)

    /** Only /search/movie and /search/tv, include_adult=false, page=1; a series year goes in the year parameter too. */
    private fun search(type: MediaType, query: String, language: String, year: Int?): TmdbResult<List<Candidate>> {
        val label = "search/${type.path}"
        if (query.isBlank()) return err(TmdbErrorKind.BAD_REQUEST, label, "empty query")
        val params = buildList {
            add("query" to query.trim())
            add("language" to language)
            add("include_adult" to "false")
            add("page" to "1")
            if (year != null && year > 0) add("year" to year.toString())
        }
        return api(label, label, params) { el ->
            tmdbJson.decodeFromJsonElement(SearchPageDto.serializer(), el).results.mapNotNull { it.toCandidate(type, language) }
        }
    }

    override fun findByImdb(imdbId: String, language: String): TmdbResult<List<Candidate>> {
        val id = imdbId.trim()
        if (!IMDB_ID.matches(id)) return err(TmdbErrorKind.BAD_REQUEST, "find", "invalid IMDb ID")
        val params = listOf("external_source" to "imdb_id", "language" to language)
        return api("find", "find/$id", params) { el ->
            val dto = tmdbJson.decodeFromJsonElement(FindDto.serializer(), el)
            dto.movieResults.mapNotNull { it.toCandidate(MediaType.MOVIE, language) } +
                dto.tvResults.mapNotNull { it.toCandidate(MediaType.TV, language) }
        }
    }

    override fun details(mediaType: MediaType, id: Int, language: String): TmdbResult<TitleDetails> {
        val label = "details/${mediaType.path}"
        if (id <= 0) return err(TmdbErrorKind.BAD_REQUEST, label, "invalid ID")
        // include_image_language needs "null" to return textless backdrops.
        val iso = language.substringBefore('-').lowercase().takeIf { ISO639.matches(it) }
        val imageLanguages = listOfNotNull(iso, "en", "null").distinct().joinToString(",")
        val params = listOf(
            "language" to language,
            "append_to_response" to "images,translations,external_ids",
            "include_image_language" to imageLanguages,
        )
        return api(label, "${mediaType.path}/$id", params) { el ->
            tmdbJson.decodeFromJsonElement(DetailsDto.serializer(), el).toDetails(mediaType)
        }
    }

    override fun downloadImage(filePath: String, size: String, maxBytes: Long): TmdbResult<ByteArray> {
        val label = "image"
        if (!IMAGE_SIZE.matches(size) || !IMAGE_PATH.matches(filePath) || maxBytes <= 0) {
            return err(TmdbErrorKind.BAD_REQUEST, label, "invalid image path or size")
        }
        // Images need no key, but once the key is removed or rejected, TMDB is not contacted at all.
        val key = currentKey() ?: return err(TmdbErrorKind.NO_KEY, label, NO_KEY_MESSAGE)
        blockedError(label, key)?.let { return it }
        val url = imageBase.newBuilder().addPathSegment(size).addPathSegment(filePath.removePrefix("/")).build()
        val request = Request.Builder().url(url).build()
        return execute(label) {
            http.newCall(request).execute().use { r ->
                val type = r.body?.contentType()
                when {
                    r.code == 404 || r.code == 410 -> err(TmdbErrorKind.NOT_FOUND, label, "HTTP ${r.code}", r.code)
                    r.code == 429 -> err(TmdbErrorKind.RATE_LIMITED, label, "HTTP 429", 429, retryAfter = parseRetryAfter(r.headers))
                    r.code in 500..599 -> err(TmdbErrorKind.SERVER, label, "HTTP ${r.code}", r.code)
                    !r.isSuccessful -> err(TmdbErrorKind.NETWORK, label, "HTTP ${r.code}", r.code)
                    // HTML instead of an image: a CDN/WAF page or a captive portal.
                    type != null && (type.type == "text" || "html" in type.subtype || "json" in type.subtype) ->
                        err(TmdbErrorKind.NETWORK, label, "not an image: ${type.type}/${type.subtype}", r.code)
                    else -> {
                        val bytes = r.body?.let { readLimited(it, maxBytes) }
                        when {
                            bytes == null -> err(TmdbErrorKind.TOO_LARGE, label, "larger than $maxBytes bytes", r.code)
                            bytes.isEmpty() -> err(TmdbErrorKind.BAD_RESPONSE, label, "empty response", r.code)
                            else -> TmdbResult.Ok(bytes)
                        }
                    }
                }
            }
        }
    }

    // ───────────────────────── internals ─────────────────────────

    private fun currentKey(): String? = apiKey()?.trim()?.takeIf { it.isNotEmpty() }

    /** The error to return instead of a request if TMDB has already rejected [key]; null means the request may go ahead. */
    private fun blockedError(label: String, key: String): TmdbResult.Err? {
        val b = blocked ?: return null
        if (b.key != key) return null
        return TmdbResult.Err(b.error.copy(message = "$label: key rejected by TMDB, requests stopped until the key changes"))
    }

    private fun <T> api(
        label: String,
        path: String,
        params: List<Pair<String, String>>,
        ignoreBlock: Boolean = false,
        parse: (JsonElement) -> T,
    ): TmdbResult<T> {
        val key = currentKey() ?: return err(TmdbErrorKind.NO_KEY, label, NO_KEY_MESSAGE)
        if (!ignoreBlock) blockedError(label, key)?.let { return it }
        val now = nowMs()
        val until = pausedUntilMs
        if (now < until) return err(TmdbErrorKind.RATE_LIMITED, label, "paused after 429", retryAfter = until - now)

        val url = apiBase.newBuilder().addPathSegments(path).apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("api_key", key)
        }.build()
        val request = Request.Builder().url(url).header("Accept", "application/json").build()

        return execute(label) {
            http.newCall(request).execute().use { r ->
                val bytes = r.body?.let { readLimited(it, MAX_JSON_BYTES) }
                    ?: return@use err(TmdbErrorKind.TOO_LARGE, label, "response larger than $MAX_JSON_BYTES bytes", r.code)
                handle(label, key, r.code, r.headers, bytes.toString(Charsets.UTF_8), parse)
            }
        }
    }

    /** Classifies an API response by HTTP code and status_code. */
    private fun <T> handle(
        label: String,
        key: String,
        code: Int,
        headers: Headers,
        text: String,
        parse: (JsonElement) -> T,
    ): TmdbResult<T> {
        val trimmed = text.trimStart()
        val looksJson = trimmed.startsWith("{") || trimmed.startsWith("[")
        val element = if (looksJson) runCatching { tmdbJson.parseToJsonElement(trimmed) }.getOrNull() else null
        val status = (element as? JsonObject)?.let { runCatching { tmdbJson.decodeFromJsonElement(StatusDto.serializer(), it) }.getOrNull() }
        val statusCode = status?.statusCode

        if (code in 200..299 && element != null && status?.success != false) {
            return try {
                val value = parse(element)
                synchronized(pauseLock) { consecutive429 = 0 }
                TmdbResult.Ok(value)
            } catch (e: IllegalArgumentException) {
                // SerializationException is a subclass of IllegalArgumentException.
                err(TmdbErrorKind.BAD_RESPONSE, label, "unexpected response structure", code, statusCode)
            }
        }

        val where = "HTTP $code" + (statusCode?.let { ", status_code $it" } ?: "")
        return when {
            code in 500..599 -> err(TmdbErrorKind.SERVER, label, where, code, statusCode)
            code == 429 || statusCode == 25 -> rateLimited(label, where, code, statusCode, headers)
            // An empty response or HTML from a CDN/WAF/captive portal is a network error, not a key error.
            !looksJson -> err(TmdbErrorKind.NETWORK, label, "$where, response is not JSON", code)
            element == null -> err(TmdbErrorKind.BAD_RESPONSE, label, "$where, malformed JSON", code)
            statusCode == 10 -> block(key, err(TmdbErrorKind.SUSPENDED_KEY, label, where, code, statusCode))
            (statusCode != null && statusCode in INVALID_KEY_CODES) || code == 401 -> block(key, err(TmdbErrorKind.INVALID_KEY, label, where, code, statusCode))
            code == 404 || statusCode == 34 || statusCode == 6 -> err(TmdbErrorKind.NOT_FOUND, label, where, code, statusCode)
            code in 400..499 -> err(TmdbErrorKind.BAD_REQUEST, label, where, code, statusCode)
            else -> err(TmdbErrorKind.BAD_RESPONSE, label, where, code, statusCode)
        }
    }

    private fun block(key: String, e: TmdbResult.Err): TmdbResult.Err {
        blocked = Blocked(key, e.error)
        return e
    }

    /**
     * Retry-After if present and greater than 0; otherwise 2–4–8… s, up to 60 s, with random jitter.
     * The step grows only with a new episode (a response arriving when no pause is active): 429 replies to requests
     * sent in parallel before the pause began do not each double it. The pause is only ever extended.
     */
    private fun rateLimited(label: String, where: String, code: Int, statusCode: Int?, headers: Headers): TmdbResult.Err {
        val retryAfter = parseRetryAfter(headers)
        val delay = synchronized(pauseLock) {
            val now = nowMs()
            if (now >= pausedUntilMs) consecutive429++
            val d = retryAfter ?: run {
                val base = (BACKOFF_START_MS shl (consecutive429 - 1).coerceIn(0, 5)).coerceAtMost(BACKOFF_MAX_MS)
                (base + random.nextLong(base / 4 + 1)).coerceAtMost(BACKOFF_MAX_MS)
            }
            pausedUntilMs = maxOf(pausedUntilMs, now + d)
            pausedUntilMs - now
        }
        return err(TmdbErrorKind.RATE_LIMITED, label, where, code, statusCode, delay)
    }

    private inline fun <T> execute(label: String, block: () -> TmdbResult<T>): TmdbResult<T> = try {
        limiter.acquire()
        block()
    } catch (e: IOException) {
        // Exception class only: OkHttp messages may contain the request URL.
        err(TmdbErrorKind.NETWORK, label, e.javaClass.simpleName)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        err(TmdbErrorKind.NETWORK, label, "interrupted")
    }

    private fun err(
        kind: TmdbErrorKind,
        label: String,
        detail: String,
        httpCode: Int? = null,
        statusCode: Int? = null,
        retryAfter: Long? = null,
    ) = TmdbResult.Err(TmdbError(kind, httpCode, statusCode, retryAfter, "$label: $detail"))

    internal companion object {
        const val NO_KEY_MESSAGE = "TMDB key not set"
        const val MAX_JSON_BYTES = 4L * 1024 * 1024
        const val BACKOFF_START_MS = 2_000L
        const val BACKOFF_MAX_MS = 60_000L
        /** Guards against an absurd Retry-After: the pause is capped at 10 min. */
        const val RETRY_AFTER_MAX_MS = 600_000L
        val INVALID_KEY_CODES = setOf(3, 7, 14)
        val IMDB_ID = Regex("^tt\\d{5,10}$")
        val ISO639 = Regex("^[a-z]{2,3}$")
        val IMAGE_SIZE = Regex("^(original|[wh]\\d{2,4})$")
        val IMAGE_PATH = Regex("^/?[A-Za-z0-9_-]+\\.[A-Za-z0-9]{2,5}$")

        /** Retry-After in seconds or as an HTTP date; null if the header is missing or the value is ≤ 0. */
        fun parseRetryAfter(headers: Headers): Long? {
            val raw = headers["Retry-After"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            val ms = raw.toDoubleOrNull()?.let { (it * 1000).toLong() }
                ?: headers.getDate("Retry-After")?.let { it.time - System.currentTimeMillis() }
                ?: return null
            return ms.takeIf { it > 0 }?.coerceAtMost(RETRY_AFTER_MAX_MS)
        }

        /** Reads at most [max] bytes of the body; null if the limit is exceeded. */
        fun readLimited(body: ResponseBody, max: Long): ByteArray? {
            if (body.contentLength() > max) return null
            val source = body.source()
            val buffer = Buffer()
            while (source.read(buffer, 8192) != -1L) {
                if (buffer.size > max) return null
            }
            return buffer.readByteArray()
        }
    }
}
