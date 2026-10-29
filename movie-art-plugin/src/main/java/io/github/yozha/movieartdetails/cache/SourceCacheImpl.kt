package io.github.yozha.movieartdetails.cache

import io.github.yozha.movieartdetails.model.ImageInfo
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.SeasonInfo
import io.github.yozha.movieartdetails.model.SourceCache
import io.github.yozha.movieartdetails.model.TitleDetails
import io.github.yozha.movieartdetails.model.Translation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Level 1 cache: parsed TMDB details, source images and match results.
 *
 * Layout of [dir]: `details/<type>_<id>_<lang>.json`, `images/<sha256(size|filePath)>.bin`, `matches.json`.
 * Age is measured from the write time on [clock]: the fetchedAt field for details, and for files the mtime
 * set on write. Reads don't extend it.
 *
 * Lifetimes:
 * - details are fresh for 24 h; expired ones are returned only with allowStale (offline), up to 6 months;
 * - images: 30 days; negative results: 30 min;
 * - a positive match is fresh for 24 h; an expired one is kept up to 6 months and returned only
 *   with allowStale, so that offline, yesterday's movie still loads from cache just like its details;
 * - nothing older than 6 months is kept (TMDB Terms of Use).
 *
 * A write time in the future (device clock not yet synced via NTP) counts as age 0.
 * Details written by an older schema lack newer fields (they read back as defaults) and count as expired:
 * online they are refetched, offline they are still available with allowStale.
 * A corrupted file is a miss and gets deleted. All writes are atomic; methods are thread-safe.
 */
class SourceCacheImpl(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
) : SourceCache {

    private val lock = Any()
    private val detailsDir = File(dir, "details")
    private val imagesDir = File(dir, "images")
    private val indexFile = File(dir, "matches.json")

    @Volatile private var initialized = false
    @Volatile private var lastPurgeAt = 0L
    /** Guarded by lock. */
    private var index: MatchIndex? = null

    override fun getDetails(type: MediaType, id: Int, language: String, allowStale: Boolean): TitleDetails? {
        ensureInit()
        val file = detailsFile(type, id, language)
        if (!file.isFile) return null
        val dto = if (file.length() > MAX_DETAILS_BYTES) null else runCatching {
            json.decodeFromString(CachedDetails.serializer(), file.readText())
        }.getOrNull()?.takeIf { it.v in 1..DETAILS_SCHEMA && it.mediaType == type.name && it.id == id && it.language == language }
        if (dto == null) {
            delete(file)
            return null
        }
        val age = ageOf(dto.fetchedAt)
        if (age > TMDB_MAX_AGE_MS) {
            delete(file)
            return null
        }
        val fresh = age <= DETAILS_TTL_MS && dto.v == DETAILS_SCHEMA
        return if (fresh || allowStale) dto.toModel(type) else null
    }

    override fun putDetails(details: TitleDetails, language: String) {
        ensureInit()
        val now = clock()
        val text = json.encodeToString(CachedDetails.serializer(), CachedDetails.of(details, language, now))
        AtomicFiles.write(detailsFile(details.mediaType, details.id, language), text.toByteArray(), now, lock)
        maybePurge(now)
    }

    override fun getImage(filePath: String, size: String): File? {
        ensureInit()
        val file = imageFile(filePath, size)
        if (!file.isFile) return null
        if (file.length() <= 0 || ageOf(file.lastModified()) > IMAGE_MAX_AGE_MS) {
            delete(file)
            return null
        }
        return file
    }

    override fun putImage(filePath: String, size: String, bytes: ByteArray): File {
        require(bytes.isNotEmpty()) { "empty image" }
        ensureInit()
        val now = clock()
        val file = AtomicFiles.write(imageFile(filePath, size), bytes, now, lock)
        maybePurge(now)
        return file
    }

    override fun isNegative(titleKey: String): Boolean {
        synchronized(lock) {
            val at = index().negatives[titleKey] ?: return false
            return ageOf(at) <= NEGATIVE_TTL_MS
        }
    }

    override fun putNegative(titleKey: String) {
        synchronized(lock) {
            val idx = index()
            idx.matches.remove(titleKey)
            // remove + put moves the entry to the end of insertion order (newest).
            idx.negatives.remove(titleKey)
            idx.negatives[titleKey] = clock()
            saveIndex(idx)
        }
    }

    override fun getMatch(titleKey: String): Pair<MediaType, Int>? = getMatch(titleKey, allowStale = false)

    override fun getMatch(titleKey: String, allowStale: Boolean): Pair<MediaType, Int>? {
        synchronized(lock) {
            val m = index().matches[titleKey] ?: return null
            val age = ageOf(m.at)
            if (age > MATCH_MAX_AGE_MS || (age > MATCH_TTL_MS && !allowStale)) return null
            val type = MediaType.entries.firstOrNull { it.name == m.type } ?: return null
            return type to m.id
        }
    }

    override fun putMatch(titleKey: String, type: MediaType, id: Int) {
        synchronized(lock) {
            val idx = index()
            idx.negatives.remove(titleKey)
            idx.matches.remove(titleKey)
            idx.matches[titleKey] = MatchEntry(type.name, id, clock())
            saveIndex(idx)
        }
    }

    override fun sizeBytes(): Long = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** Deletes only this cache's own directory contents; temp files of in-flight writes are left alone. */
    override fun clear() {
        synchronized(lock) {
            detailsDir.dataFiles().forEach { it.delete() }
            imagesDir.dataFiles().forEach { it.delete() }
            indexFile.delete()
            index = MatchIndex()
        }
    }

    /** Purges expired data: details and matches older than 6 months, images older than 30 days, negatives older than 30 min. */
    fun purgeExpired() {
        synchronized(lock) {
            val now = clock()
            lastPurgeAt = now
            detailsDir.dataFiles().filter { ageOf(it.lastModified(), now) > TMDB_MAX_AGE_MS }.forEach { it.delete() }
            imagesDir.dataFiles().filter { ageOf(it.lastModified(), now) > IMAGE_MAX_AGE_MS }.forEach { it.delete() }
            val idx = index()
            if (idx.prune(now)) saveIndex(idx)
        }
    }

    /**
     * Shrinks the cache to [maxBytes] (low storage or the size limit): expired data first,
     * then LRU by write time, images before details. The match index is small and left alone.
     */
    override fun trim(maxBytes: Long) {
        synchronized(lock) {
            purgeExpired()
            var total = sizeBytes()
            if (total <= maxBytes) return
            val victims = imagesDir.dataFiles().sortedBy { it.lastModified() } +
                detailsDir.dataFiles().sortedBy { it.lastModified() }
            for (f in victims) {
                if (total <= maxBytes) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        }
    }

    // ───────────── internals ─────────────

    private fun ensureInit() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            detailsDir.mkdirs()
            imagesDir.mkdirs()
            // Leftovers of writes interrupted by a killed process; no writes are in flight before init.
            for (d in listOf(detailsDir, imagesDir, dir)) {
                d.listFiles()?.forEach { if (it.isFile && it.name.endsWith(AtomicFiles.TMP_SUFFIX)) it.delete() }
            }
            initialized = true
        }
    }

    private fun maybePurge(now: Long) {
        if (now - lastPurgeAt >= PURGE_INTERVAL_MS || now < lastPurgeAt) purgeExpired()
    }

    private fun delete(file: File) {
        synchronized(lock) { file.delete() }
    }

    private fun ageOf(at: Long, now: Long = clock()): Long = (now - at).coerceAtLeast(0)

    private fun File.dataFiles(): List<File> =
        listFiles()?.filter { it.isFile && !it.name.endsWith(AtomicFiles.TMP_SUFFIX) }.orEmpty()

    private fun detailsFile(type: MediaType, id: Int, language: String): File {
        val lang = language.filter { it.isLetterOrDigit() || it == '-' }.ifEmpty { "x" }
        return File(detailsDir, "${type.path}_${id}_$lang.json")
    }

    private fun imageFile(filePath: String, size: String) =
        File(imagesDir, AtomicFiles.sha256Hex("$size|$filePath") + ".bin")

    /** Match index: loaded lazily once; a corrupted file yields an empty index. Guarded by lock. */
    private fun index(): MatchIndex {
        index?.let { return it }
        ensureInit()
        val loaded = if (!indexFile.isFile) null else runCatching {
            json.decodeFromString(IndexDto.serializer(), indexFile.readText())
        }.getOrNull()?.takeIf { it.v == INDEX_SCHEMA }
        if (loaded == null) indexFile.delete()
        val idx = MatchIndex(LinkedHashMap(loaded?.negatives.orEmpty()), LinkedHashMap(loaded?.matches.orEmpty()))
        idx.prune(clock())
        index = idx
        return idx
    }

    /** Guarded by lock. */
    private fun saveIndex(idx: MatchIndex) {
        val now = clock()
        idx.prune(now)
        val text = json.encodeToString(IndexDto.serializer(), IndexDto(INDEX_SCHEMA, idx.negatives, idx.matches))
        AtomicFiles.write(indexFile, text.toByteArray(), now, lock)
    }

    /** Entries in insertion order: the first one is the oldest. */
    private class MatchIndex(
        val negatives: LinkedHashMap<String, Long> = LinkedHashMap(),
        val matches: LinkedHashMap<String, MatchEntry> = LinkedHashMap(),
    ) {
        /** Drops expired and excess entries; returns true if anything changed. */
        fun prune(now: Long): Boolean {
            val before = negatives.size + matches.size
            negatives.entries.removeAll { now - it.value > NEGATIVE_TTL_MS }
            matches.entries.removeAll { now - it.value.at > MATCH_MAX_AGE_MS }
            while (negatives.size > MAX_INDEX_ENTRIES) negatives.remove(negatives.keys.first())
            while (matches.size > MAX_INDEX_ENTRIES) matches.remove(matches.keys.first())
            return negatives.size + matches.size != before
        }
    }

    // ───────────── Storage DTOs: mirror TitleDetails, independent of TMDB response DTOs ─────────────

    @Serializable
    private data class IndexDto(
        val v: Int,
        val negatives: Map<String, Long> = emptyMap(),
        val matches: Map<String, MatchEntry> = emptyMap(),
    )

    @Serializable
    private data class MatchEntry(val type: String, val id: Int, val at: Long)

    @Serializable
    private data class CachedTranslation(
        val iso639: String,
        val iso3166: String,
        val title: String? = null,
        val overview: String? = null,
        val runtimeMinutes: Int? = null,
    ) {
        fun toModel() = Translation(iso639, iso3166, title, overview, runtimeMinutes)
    }

    @Serializable
    private data class CachedSeason(
        val number: Int,
        val airDate: String? = null,
        val episodeCount: Int,
    ) {
        fun toModel() = SeasonInfo(number, airDate, episodeCount)
    }

    @Serializable
    private data class CachedImage(
        val filePath: String,
        val iso639: String? = null,
        val width: Int,
        val height: Int,
        val voteAverage: Double,
    ) {
        fun toModel() = ImageInfo(filePath, iso639, width, height, voteAverage)
    }

    @Serializable
    private data class CachedDetails(
        val v: Int,
        val fetchedAt: Long,
        val language: String,
        val mediaType: String,
        val id: Int,
        val title: String,
        val originalTitle: String,
        val originalLanguage: String? = null,
        val overview: String? = null,
        val date: String? = null,
        val lastAirDate: String? = null,
        val status: String? = null,
        val runtimeMinutes: Int? = null,
        val numberOfSeasons: Int? = null,
        val genres: List<String> = emptyList(),
        val voteAverage: Double,
        val voteCount: Int,
        val backdropPath: String? = null,
        val translations: List<CachedTranslation> = emptyList(),
        val logos: List<CachedImage> = emptyList(),
        val backdrops: List<CachedImage> = emptyList(),
        val imdbId: String? = null,
        // Added in schema 2.
        val genreIds: List<Int> = emptyList(),
        val seasons: List<CachedSeason> = emptyList(),
        val lastAiredSeason: Int? = null,
    ) {
        fun toModel(type: MediaType) = TitleDetails(
            mediaType = type, id = id, title = title, originalTitle = originalTitle, originalLanguage = originalLanguage,
            overview = overview, date = date, lastAirDate = lastAirDate, status = status, runtimeMinutes = runtimeMinutes,
            numberOfSeasons = numberOfSeasons, genres = genres, voteAverage = voteAverage, voteCount = voteCount,
            backdropPath = backdropPath,
            translations = translations.map { it.toModel() },
            logos = logos.map { it.toModel() }, backdrops = backdrops.map { it.toModel() }, imdbId = imdbId,
            genreIds = genreIds, seasons = seasons.map { it.toModel() }, lastAiredSeason = lastAiredSeason,
        )

        companion object {
            fun of(d: TitleDetails, language: String, now: Long) = CachedDetails(
                v = DETAILS_SCHEMA, fetchedAt = now, language = language, mediaType = d.mediaType.name, id = d.id,
                title = d.title, originalTitle = d.originalTitle, originalLanguage = d.originalLanguage,
                overview = d.overview, date = d.date, lastAirDate = d.lastAirDate, status = d.status,
                runtimeMinutes = d.runtimeMinutes, numberOfSeasons = d.numberOfSeasons, genres = d.genres,
                voteAverage = d.voteAverage, voteCount = d.voteCount, backdropPath = d.backdropPath,
                translations = d.translations.map { CachedTranslation(it.iso639, it.iso3166, it.title, it.overview, it.runtimeMinutes) },
                logos = d.logos.map(::image), backdrops = d.backdrops.map(::image), imdbId = d.imdbId,
                genreIds = d.genreIds, seasons = d.seasons.map { CachedSeason(it.number, it.airDate, it.episodeCount) },
                lastAiredSeason = d.lastAiredSeason,
            )

            private fun image(i: ImageInfo) = CachedImage(i.filePath, i.iso639, i.width, i.height, i.voteAverage)
        }
    }

    companion object {
        private const val MINUTE = 60_000L
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR

        const val DETAILS_TTL_MS = 24 * HOUR
        /** Match freshness: past this, search again when the network is available. */
        const val MATCH_TTL_MS = 24 * HOUR
        const val NEGATIVE_TTL_MS = 30 * MINUTE
        const val IMAGE_MAX_AGE_MS = 30 * DAY
        /** Upper bound for any TMDB data (Terms of Use): ≈6 months. */
        const val TMDB_MAX_AGE_MS = 182 * DAY
        /** Expired matches (for offline use) are kept up to the TMDB limit. */
        const val MATCH_MAX_AGE_MS = TMDB_MAX_AGE_MS

        /** Version 2 added Translation.runtimeMinutes, genreIds, seasons and lastAiredSeason. */
        private const val DETAILS_SCHEMA = 2
        private const val INDEX_SCHEMA = 1
        private const val PURGE_INTERVAL_MS = 6 * HOUR
        private const val MAX_INDEX_ENTRIES = 1000
        private const val MAX_DETAILS_BYTES = 2L * 1024 * 1024

        private val json = Json { ignoreUnknownKeys = true }
    }
}
