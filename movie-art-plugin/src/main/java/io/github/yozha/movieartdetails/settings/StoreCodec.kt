package io.github.yozha.movieartdetails.settings

import io.github.yozha.movieartdetails.model.ManualMapping
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.RecentTitle
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Format of the MappingsStore and RecentTitlesStore local files (noBackupFilesDir). A corrupted file
 * yields an empty list; individual invalid entries are skipped. Pure functions, tested on the JVM.
 */
object StoreCodec {
    const val VERSION = 1
    const val MAX_MAPPINGS = 2_000
    const val MAX_RECENT = 50
    const val RECENT_MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
    /** Raw titles longer than this are truncated to keep junk out of the history file. */
    const val MAX_RAW_CHARS = 300

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    @Serializable
    private class MappingsFile(val v: Int = 0, val items: List<MappingDto> = emptyList())

    /** type/id == null means "do not recognize this title". */
    @Serializable
    private class MappingDto(val key: String = "", val source: String = "", val type: String? = null, val id: Int? = null)

    @Serializable
    private class RecentFile(val v: Int = 0, val items: List<RecentDto> = emptyList())

    @Serializable
    private class RecentDto(val raw: String = "", val type: Int? = null, val host: String? = null, val key: String = "", val at: Long = 0)

    fun encodeMappings(items: Collection<ManualMapping>): String = json.encodeToString(
        MappingsFile.serializer(),
        MappingsFile(VERSION, items.map { MappingDto(it.key, it.sourceTitle, it.target?.first?.path, it.target?.second) }),
    )

    fun decodeMappings(text: String): List<ManualMapping> {
        val file = runCatching { json.decodeFromString(MappingsFile.serializer(), text) }.getOrNull() ?: return emptyList()
        if (file.v != VERSION) return emptyList()
        return file.items.mapNotNull { d ->
            if (d.key.isBlank()) return@mapNotNull null
            val target = when {
                d.type == null && d.id == null -> null
                else -> {
                    val type = MediaType.entries.firstOrNull { it.path == d.type } ?: return@mapNotNull null
                    val id = d.id?.takeIf { it > 0 } ?: return@mapNotNull null
                    type to id
                }
            }
            ManualMapping(d.key, d.source, target)
        }.distinctBy { it.key }.take(MAX_MAPPINGS)
    }

    fun encodeRecent(items: List<RecentTitle>): String = json.encodeToString(
        RecentFile.serializer(),
        RecentFile(VERSION, items.map { RecentDto(it.raw, it.type, it.iconHost, it.key, it.lastSeenAt) }),
    )

    fun decodeRecent(text: String, now: Long): List<RecentTitle> {
        val file = runCatching { json.decodeFromString(RecentFile.serializer(), text) }.getOrNull() ?: return emptyList()
        if (file.v != VERSION) return emptyList()
        return pruneRecent(file.items.filter { it.raw.isNotBlank() }.map { RecentTitle(it.raw, it.type, it.host, it.key, it.at) }, now)
    }

    /**
     * Adds or refreshes an entry: the same (raw, key) pair is replaced by the new one (with a new time and source).
     * Newest first; at most [MAX_RECENT], none older than 30 days.
     */
    fun addRecent(items: List<RecentTitle>, title: RecentTitle, now: Long): List<RecentTitle> {
        val t = title.copy(raw = title.raw.take(MAX_RAW_CHARS))
        return pruneRecent(listOf(t) + items.filterNot { it.raw == t.raw && it.key == t.key }, now)
    }

    /** Timestamps in the future (device clock not yet synced via NTP) count as fresh. */
    fun pruneRecent(items: List<RecentTitle>, now: Long): List<RecentTitle> = items
        .filter { now - it.lastSeenAt <= RECENT_MAX_AGE_MS }
        .sortedByDescending { it.lastSeenAt }
        .distinctBy { it.raw to it.key }
        .take(MAX_RECENT)
}
