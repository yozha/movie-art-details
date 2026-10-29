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

/** One page of /search results; the totals are null when unknown. */
data class SearchPage(
    val results: List<Candidate>,
    val totalPages: Int? = null,
    val totalResults: Int? = null,
)

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

data class Translation(
    val iso639: String,
    val iso3166: String,
    val title: String?,
    val overview: String?,
    /** Movie runtime in this translation; null when absent or 0. TMDB fills the top-level runtime from it. */
    val runtimeMinutes: Int? = null,
)

/** A series season from details.seasons[]. */
data class SeasonInfo(
    /** 0 is "Specials". */
    val number: Int,
    /** "YYYY-MM-DD"; null for an announced season with no date yet. */
    val airDate: String?,
    val episodeCount: Int,
)

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
    /** TMDB genre ids, index-aligned with [genres]; 0 for an unknown id, empty when unknown (e.g. an old cache entry). */
    val genreIds: List<Int> = emptyList(),
    /** Series seasons from details.seasons[]; empty for movies or when unknown. */
    val seasons: List<SeasonInfo> = emptyList(),
    /** Series: season_number of last_episode_to_air; null when unknown. */
    val lastAiredSeason: Int? = null,
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
    /** [searchMovie] or [searchTv] with TMDB's paging totals; this default reports them as unknown. */
    fun searchPage(type: MediaType, query: String, language: String, year: Int? = null): TmdbResult<SearchPage> =
        when (val r = if (type == MediaType.MOVIE) searchMovie(query, language, year) else searchTv(query, language, year)) {
            is TmdbResult.Ok -> TmdbResult.Ok(SearchPage(r.value))
            is TmdbResult.Err -> r
        }
    fun findByImdb(imdbId: String, language: String): TmdbResult<List<Candidate>>
    /** details + append_to_response=images,translations; include_image_language=<lang>,en,null. */
    fun details(mediaType: MediaType, id: Int, language: String): TmdbResult<TitleDetails>
    /** Downloads an image from the image CDN: size is "original", "w1280", "w500"…; response size is capped. */
    fun downloadImage(filePath: String, size: String, maxBytes: Long): TmdbResult<ByteArray>
    /** Opens connections to the API and image hosts ahead of the first request. Asynchronous and throttled; may do nothing. */
    fun warmUp() {}
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
    /** Selected logo as a PNG download path (an SVG logo is downloaded as its PNG rendition), or null to draw the title as text. */
    val logoPath: String?,
    /** Selected backdrop, or null for a neutral background behind the text. */
    val backdropPath: String?,
    /** Fingerprint of all values in this class; part of the composite key. */
    val fingerprint: String,
    /** TMDB image size to download [logoPath] at: "w500", or "original" for a wide logo that may span the whole block. */
    val logoSize: String = "w500",
)

// ───────────────────────────── render/ ─────────────────────────────

enum class TextPreset { SMALL, MEDIUM, LARGE }

/** Logo size rules shared by tmdb/ImageSelector (choosing a logo) and render/LayoutEngine (the final fit decision). */
object LogoRules {
    const val MIN_ASPECT = 0.8f
    const val MAX_ASPECT = 14f
    /** A logo wider than this may grow to the full block width; it is downloaded at "original". */
    const val WIDE_ASPECT = 4f
    /** Minimum logo height on the 1080p canvas after fitting. */
    const val MIN_HEIGHT = 60f
    /** Block width at the default settings (46% of 1920 px): the room for a wide logo when the frame is not known yet. */
    const val DEFAULT_BLOCK_WIDTH = 883f
}

/** Where the info block sits between [AppearanceSettings.topPct] and its bottom limit. */
enum class BlockAnchor {
    /** Starts at the top margin and grows down. */
    TOP,
    /** Ends at the bottom limit, just above the card row, and grows up; it never starts above the top margin. */
    ABOVE_ROW,
}

/** Appearance settings. Values are fractions of the canvas. */
data class AppearanceSettings(
    val textPreset: TextPreset = TextPreset.MEDIUM,
    val leftPct: Float = 0.06f,
    val topPct: Float = 0.07f,
    val blockWidthPct: Float = 0.46f,
    /**
     * Where the launcher's card row starts (the top of its header). 0.55 is Projectivy's layout with its bottom
     * info panel on; with the panel off, the row can be moved lower to make room for the block.
     */
    val rowPositionPct: Float = 0.55f,
    /** Free space between the block and the card row; see [bottomLimitPct]. */
    val rowGapPct: Float = 0.06f,
    val blockAnchor: BlockAnchor = BlockAnchor.TOP,
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
    /** Banner or icon of the app the card comes from, as a plate at the start of the meta line. */
    val showAppBadge: Boolean = true,
) {
    /** Block bottom: y_bottom ≤ bottomLimitPct·H. 0.49 by default; never closer than 3% to the row (LayoutEngine.frame). */
    val bottomLimitPct: Float get() = rowPositionPct - rowGapPct

    /** Version of the render-relevant fields (every constructor property); part of the composite key. */
    fun fingerprint(): String = toString().hashCode().toUInt().toString(16)
}

/**
 * Banner (or, without one, the icon) of the app a card comes from, drawn as a plate in the info block.
 * The bitmap is shared through a cache: renderers draw it and never recycle it.
 */
class AppBadge(val bitmap: Bitmap, val banner: Boolean)

/**
 * Renders the final 1920×1080 frame. Implementation: render/InfoBlockRenderer(context).
 * The caller has already decoded backdrop and logo and downscaled them if needed;
 * the result is written as JPEG to [out] (the caller publishes it atomically).
 */
interface WallpaperRenderer {
    /** [badge] is the source app's plate, or null for none. */
    fun render(backdrop: Bitmap?, logo: Bitmap?, info: DisplayInfo, settings: AppearanceSettings, out: File, badge: AppBadge? = null)
    /**
     * A card shown without TMDB details: a solid [background] (opaque ARGB) and a minimal block with the card's own
     * [title] and, when known, the source app's [badge].
     */
    fun renderTitled(title: String, badge: AppBadge?, background: Int, settings: AppearanceSettings, out: File)
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
    /** Cards that are not recognized, and app cards, show their title on the neutral background instead of a bare color. */
    fun showTitleWhenUnrecognized(): Boolean = true
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

/**
 * The user's choice of the app behind an iconUri host (a LAN media server, a streaming CDN), for its badge.
 * Implementation: settings/SourceAppsStore(context): local only, excluded from backup and getPreferences.
 */
interface SourceAppMappings {
    /** Package for a lowercase [host], or null when none is set. */
    fun get(host: String): String?
    fun put(host: String, packageName: String)
    fun remove(host: String)
    fun all(): Map<String, String>
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
