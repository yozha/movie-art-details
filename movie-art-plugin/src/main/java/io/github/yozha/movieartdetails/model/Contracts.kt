package io.github.yozha.movieartdetails.model

import android.graphics.Bitmap
import java.io.File

/*
 * Shared contract between the Movie Art Details modules.
 * Modules: match/ (title matching), tmdb/ (network and metadata), render/ (rendering),
 * cache/ (caching and publishing), settings/ (settings and storage), core/ (service and orchestration).
 * Every module depends on this file, so change it only in coordination with all of them.
 * All APIs are blocking; never call them on the main thread.
 */

// ───────────────────────────── Common types ─────────────────────────────

enum class MediaType(val path: String) { MOVIE("movie"), TV("tv") }

/** What the plugin decided to do with a card before searching. */
enum class CardKind { MOVIE, SERIES, UNKNOWN, SKIP }

/** Display language. */
enum class DisplayLanguage(val tmdb: String, val iso639: String, val region: String) {
    RU("ru-RU", "ru", "RU"), UK("uk-UA", "uk", "UA"), EN("en-US", "en", "US"),
}

/** Script of the card title; selects the search query language. */
enum class Script { CYRILLIC_RU, CYRILLIC_UK, CYRILLIC_AMBIGUOUS, LATIN, OTHER, NONE }

// ───────────────────────────── match/ ─────────────────────────────

/**
 * Parsed form of the raw card title. Nothing is lost: [raw] keeps the original,
 * and hints (year, season/episode, IDs) are stored separately.
 */
data class ParsedTitle(
    val raw: String,
    /** Main search title: without technical tags, SxxExx or ID tags. */
    val primary: String,
    /** Extra variants: halves of "Localized / Original", a variant without brackets, etc. */
    val alternates: List<String>,
    val year: Int?,
    val season: Int?,
    val episode: Int?,
    /** tt1234567 from tags like [imdbid-tt…] or {imdb-tt…}. */
    val imdbId: String?,
    /** ID from tags like [tmdbid-123]. */
    val tmdbId: Int?,
    /** true if technical file tags (1080p, Remux, DTS, release group…) were stripped from the title. */
    val looksLikeFileName: Boolean,
    val script: Script,
    /** Normalized [primary] (NFKC, lowercase, collapsed whitespace, unified apostrophes/dashes). */
    val normalized: String,
)

/** Decision based on the card type and the iconUri host. */
data class SourceDecision(val kind: CardKind, val source: String, val reason: String)

/** TMDB candidate from search or /find. Titles are in the query language plus the original. */
data class Candidate(
    val mediaType: MediaType,
    val id: Int,
    val title: String,
    val originalTitle: String,
    /** release_date / first_air_date, "YYYY-MM-DD" or null. */
    val date: String?,
    val popularity: Double,
    val voteCount: Int,
    val originalLanguage: String?,
    /** Language of the query that returned [title] (e.g. "ru-RU"). */
    val queryLanguage: String,
) {
    val year: Int? get() = date?.take(4)?.toIntOrNull()
}

data class ScoredCandidate(val candidate: Candidate, val score: Double, val explanation: String)

data class MatchResult(
    val ranked: List<ScoredCandidate>,
    /** Best candidate, if its score meets the acceptance threshold and its lead over the runner-up is large enough. */
    val accepted: Candidate?,
    val confidence: Double,
    val reason: String,
)

// ───────────────────────────── tmdb/ ─────────────────────────────

enum class TmdbErrorKind { NO_KEY, INVALID_KEY, SUSPENDED_KEY, NOT_FOUND, RATE_LIMITED, SERVER, NETWORK, BAD_REQUEST, BAD_RESPONSE, TOO_LARGE }

data class TmdbError(
    val kind: TmdbErrorKind,
    val httpCode: Int? = null,
    val statusCode: Int? = null,
    val retryAfterMs: Long? = null,
    /** Never contains the credential or the full URL. */
    val message: String = "",
)

sealed interface TmdbResult<out T> {
    data class Ok<T>(val value: T) : TmdbResult<T>
    data class Err(val error: TmdbError) : TmdbResult<Nothing>
}

data class Translation(val iso639: String, val iso3166: String, val title: String?, val overview: String?)

data class ImageInfo(
    val filePath: String,
    val iso639: String?,
    val width: Int,
    val height: Int,
    val voteAverage: Double,
)

data class TitleDetails(
    val mediaType: MediaType,
    val id: Int,
    /** title/name in the query language (TMDB may fall back to the original; don't treat that as a translation). */
    val title: String,
    val originalTitle: String,
    val originalLanguage: String?,
    val overview: String?,
    /** release_date (movie) / first_air_date (series). */
    val date: String?,
    val lastAirDate: String?,
    /** TMDB status: "Released", "Ended", "Canceled", "Returning Series", … */
    val status: String?,
    val runtimeMinutes: Int?,
    val numberOfSeasons: Int?,
    /** Genres in the query language. */
    val genres: List<String>,
    val voteAverage: Double,
    val voteCount: Int,
    val backdropPath: String?,
    val translations: List<Translation>,
    val logos: List<ImageInfo>,
    val backdrops: List<ImageInfo>,
    val imdbId: String?,
)

/**
 * TMDB v3 client (api_key auth). Implementation: tmdb/TmdbHttpClient.
 * Constructor: TmdbHttpClient(apiKey: () -> String?, http: okhttp3.OkHttpClient,
 *   apiBase: String = "https://api.themoviedb.org/3/", imageBase: String = "https://image.tmdb.org/t/p/")
 */
interface TmdbClient {
    fun validateKey(): TmdbResult<Unit>
    fun searchMovie(query: String, language: String, year: Int? = null): TmdbResult<List<Candidate>>
    fun searchTv(query: String, language: String, year: Int? = null): TmdbResult<List<Candidate>>
    fun findByImdb(imdbId: String, language: String): TmdbResult<List<Candidate>>
    /** details + append_to_response=images,translations,external_ids; include_image_language=<lang>,en,null. */
    fun details(mediaType: MediaType, id: Int, language: String): TmdbResult<TitleDetails>
    /** Downloads an image from the image CDN: size is "original", "w1280", "w500"…; response size is capped. */
    fun downloadImage(filePath: String, size: String, maxBytes: Long): TmdbResult<ByteArray>
}

/** Ready-to-draw info block strings. null means the field is hidden. */
data class DisplayInfo(
    val mediaType: MediaType,
    val tmdbId: Int,
    val language: DisplayLanguage,
    val title: String,
    /** "2009", "2011–2019". */
    val yearText: String?,
    /** "Movie" / "Series" in the display language. */
    val typeText: String,
    /** "2h 42m" / "3 seasons". */
    val durationText: String?,
    val genres: List<String>,
    /** "TMDB 7.6/10"; null if the vote count is below the threshold. */
    val ratingText: String?,
    val overview: String?,
    /** Selected logo (PNG), or null to draw the title as text. */
    val logoPath: String?,
    /** Selected backdrop, or null for a neutral background behind the text. */
    val backdropPath: String?,
    /** Fingerprint of all values above; part of the composite key. */
    val fingerprint: String,
)

// ───────────────────────────── render/ ─────────────────────────────

enum class TextPreset { SMALL, MEDIUM, LARGE }

/** Appearance settings. Values are fractions of the canvas. */
data class AppearanceSettings(
    val textPreset: TextPreset = TextPreset.MEDIUM,
    val leftPct: Float = 0.06f,
    val topPct: Float = 0.07f,
    val blockWidthPct: Float = 0.46f,
    /** Block bottom: y_bottom ≤ bottomLimitPct·H. Defaults to a 49% target; the hard limit is 0.52. */
    val bottomLimitPct: Float = 0.49f,
    val overviewLines: Int = 5,
    /** 0..1: strength of the scrim behind the text. */
    val scrimIntensity: Float = 0.75f,
    val showLogo: Boolean = true,
    val showYear: Boolean = true,
    val showType: Boolean = true,
    val showDuration: Boolean = false,
    val showGenres: Boolean = true,
    val showRating: Boolean = true,
    val showOverview: Boolean = true,
) {
    /** Version of the render-relevant fields; part of the composite key. */
    fun fingerprint(): String = toString().hashCode().toUInt().toString(16)
}

/**
 * Renders the final 1920×1080 frame. Implementation: render/InfoBlockRenderer(context).
 * The caller has already decoded backdrop and logo and downscaled them if needed;
 * the result is written as JPEG to [out] (the caller publishes it atomically).
 */
interface WallpaperRenderer {
    fun render(backdrop: Bitmap?, logo: Bitmap?, info: DisplayInfo, settings: AppearanceSettings, out: File)
    /** Render algorithm version; part of the composite key. */
    val version: Int
}

// ───────────────────────────── cache/ ─────────────────────────────

/**
 * Level 1: metadata and source images. Implementation: cache/SourceCacheImpl(dir: File, clock: () -> Long).
 * Metadata TTL is 24 h; expired entries are available via allowStale (offline). Nothing older than 6 months is kept.
 */
interface SourceCache {
    fun getDetails(type: MediaType, id: Int, language: String, allowStale: Boolean): TitleDetails?
    fun putDetails(details: TitleDetails, language: String)
    fun getImage(filePath: String, size: String): File?
    fun putImage(filePath: String, size: String, bytes: ByteArray): File
    /** Negative match result keyed by title key, TTL ~30 min. */
    fun isNegative(titleKey: String): Boolean
    fun putNegative(titleKey: String)
    /** Positive match result (title key → candidate): fresh for 24 h. */
    fun getMatch(titleKey: String): Pair<MediaType, Int>?
    /**
     * With [allowStale], expired entries (older than 24 h but not older than 6 months) are returned too; use only
     * when search is unavailable because of the network (offline). Defaults to [getMatch] without expired entries.
     */
    fun getMatch(titleKey: String, allowStale: Boolean): Pair<MediaType, Int>? = getMatch(titleKey)
    /** Rewrites the entry (write time = now): both after a search and after refreshing this title's details. */
    fun putMatch(titleKey: String, type: MediaType, id: Int)
    fun sizeBytes(): Long
    /** LRU trim down to [maxBytes] (low storage, cache limit). */
    fun trim(maxBytes: Long) {}
    fun clear()
}

/**
 * Level 2: published composites. Implementation: cache/CompositeStoreImpl(dir: File, clock: () -> Long, verify: (File) -> Boolean).
 * Files live only in internal storage, in a single directory served through FileProvider.
 * publish: writes to a temp file via [write], verifies it decodes, then renames it atomically.
 * The last served file and ≥5 previous ones are never deleted; trim is LRU within maxBytes and 30 days.
 */
interface CompositeStore {
    val dir: File
    fun find(key: String): File?
    fun publish(key: String, write: (File) -> Unit): File
    fun markServed(file: File)
    fun trim(maxBytes: Long)
    fun sizeBytes(): Long
    fun clear()
}

// ───────────────────────────── settings/ ─────────────────────────────

/** Manual mapping. target == null means "do not match this title". */
data class ManualMapping(val key: String, val sourceTitle: String, val target: Pair<MediaType, Int>?)

data class RecentTitle(val raw: String, val type: Int?, val iconHost: String?, val key: String, val lastSeenAt: Long)

/** All user settings (except the key). Implementation: settings/AppSettings(context). */
interface SettingsRepository {
    fun language(): DisplayLanguage
    fun appearance(): AppearanceSettings
    fun minVoteCount(): Int
    fun cacheLimitBytes(): Long
    fun neutralColor(): String
    fun diagnosticsEnabled(): Boolean
    fun logTitlesInDiagnostics(): Boolean
    /** Tint the neutral background with CardFocused.darkColor. */
    fun tintNeutralFromCard(): Boolean = false
    /** Export for getPreferences: allowlisted keys only, with schemaVersion, without the key or history. */
    fun exportJson(): String
    /** Import from setPreferences: no clear(); unknown/incompatible fields are ignored. */
    fun importJson(json: String): Boolean
}

/** TMDB key: AES-GCM with an Android Keystore key, file in noBackupFilesDir. Implementation: settings/CredentialStore(context). */
interface CredentialRepository {
    fun get(): String?
    fun set(key: String)
    fun clear()
    fun masked(): String?
}

/** Implementation: settings/MappingsStore(context). */
interface MappingsRepository {
    fun get(key: String): ManualMapping?
    fun put(mapping: ManualMapping)
    fun remove(key: String)
    fun all(): List<ManualMapping>
}

/** Implementation: settings/RecentTitlesStore(context): ≤50 entries, ≤30 days, excluded from backup. */
interface RecentTitlesRepository {
    fun record(title: RecentTitle)
    fun all(): List<RecentTitle>
    fun clear()
}

/** Diagnostics log. Implementation: core/DiagnosticsLog(context). Keeps the last 50 events. */
interface Diagnostics {
    fun event(line: String)
    fun lastError(error: TmdbError?)
    fun recent(): List<String>
    /** Last TMDB error for the settings screen, without the credential or URL. */
    fun lastErrorText(): String? = null
    fun clear() {}
}
