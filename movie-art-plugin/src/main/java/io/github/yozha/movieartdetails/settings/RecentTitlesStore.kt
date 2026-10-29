package io.github.yozha.movieartdetails.settings

import android.content.Context
import io.github.yozha.movieartdetails.model.RecentTitle
import io.github.yozha.movieartdetails.model.RecentTitlesRepository
import java.io.File
import java.io.IOException

/**
 * Recent title history for manual correction: noBackupFilesDir/recent_titles.json,
 * ≤ 50 entries, ≤ 30 days, local only: excluded from Auto Backup, getPreferences and logs.
 *
 * record() is called on every focus event, so only a new entry, or a repeat seen more than
 * [WRITE_INTERVAL_MS] after the previous one, is written to disk; the rest stays in memory until the next write.
 */
class RecentTitlesStore(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
) : RecentTitlesRepository {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val lock = Any()
    /** Guarded by lock. */
    private var items: List<RecentTitle>? = null

    override fun record(title: RecentTitle) {
        if (title.raw.isBlank()) return
        synchronized(lock) {
            val now = clock()
            val before = load(now)
            val previous = before.firstOrNull { it.raw == title.raw.take(StoreCodec.MAX_RAW_CHARS) && it.key == title.key }
            val after = StoreCodec.addRecent(before, title, now)
            items = after
            val stale = previous == null || title.lastSeenAt - previous.lastSeenAt > WRITE_INTERVAL_MS
            if (stale || after.size < before.size) save(after)
        }
    }

    override fun all(): List<RecentTitle> = synchronized(lock) {
        val now = clock()
        StoreCodec.pruneRecent(load(now), now).also { items = it }
    }

    override fun clear() {
        synchronized(lock) {
            items = emptyList()
            file.delete()
        }
    }

    private fun load(now: Long): List<RecentTitle> = items ?: (
        SafeFiles.read(file, MAX_FILE_BYTES)?.let { StoreCodec.decodeRecent(it.toString(Charsets.UTF_8), now) } ?: emptyList()
        ).also { items = it }

    private fun save(list: List<RecentTitle>) {
        try {
            SafeFiles.write(file, StoreCodec.encodeRecent(list).toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            // Out of disk space: the history is not critical.
        }
    }

    companion object {
        const val FILE_NAME = "recent_titles.json"
        private const val MAX_FILE_BYTES = 256L * 1024
        private const val WRITE_INTERVAL_MS = 10L * 60 * 1000
    }
}
