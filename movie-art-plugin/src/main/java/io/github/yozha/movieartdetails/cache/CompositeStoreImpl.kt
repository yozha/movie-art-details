package io.github.yozha.movieartdetails.cache

import io.github.yozha.movieartdetails.model.CompositeStore
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Level 2 cache: published composites in a single directory [dir],
 * which core serves to the host through FileProvider.
 *
 * File name is `<sha256(key)>-<gen>.jpg`. The generation is monotonic (≥ publish time on [clock]
 * and greater than any existing one), so every new result gets a new URI:
 * Projectivy does not re-read a URI it has already opened. File age is derived from gen,
 * not mtime, so reads don't extend it.
 *
 * Protection: the last [SERVED_RING] served files (the latest and ≥5 previous) survive both trim and clear.
 * After a process restart the [SERVED_RING] newest files count as protected: the host may still
 * hold a grant on a URI issued before the process died.
 *
 * [verify] checks the temp file before the rename (core passes a BitmapFactory check).
 */
class CompositeStoreImpl(
    override val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val verify: (File) -> Boolean = { true },
) : CompositeStore {

    private val lock = Any()

    @Volatile private var initialized = false
    // Guarded by lock.
    private var lastGen = 0L
    private val inFlight = HashSet<String>()
    private val served = ArrayDeque<String>()

    override fun find(key: String): File? {
        ensureInit()
        val prefix = AtomicFiles.sha256Hex(key) + "-"
        val newest = dir.listFiles { _, name -> name.startsWith(prefix) }
            ?.mapNotNull(::parse)?.maxByOrNull { it.gen } ?: return null
        if (newest.size <= 0 || ageOf(newest.gen) > MAX_AGE_MS) return null
        return newest.file
    }

    /**
     * Writes the composite via [write] to a temp `<name>.tmp`, fsyncs it, checks [verify]
     * and renames it atomically. On any error the temp file is deleted and the exception is rethrown.
     */
    override fun publish(key: String, write: (File) -> Unit): File {
        ensureInit()
        val name = synchronized(lock) {
            lastGen = maxOf(lastGen + 1, clock())
            "${AtomicFiles.sha256Hex(key)}-$lastGen$EXT".also { inFlight += it }
        }
        val target = File(dir, name)
        val tmp = File(dir, name + AtomicFiles.TMP_SUFFIX)
        try {
            dir.mkdirs()
            write(tmp)
            if (!tmp.isFile || tmp.length() <= 0) throw IOException("composite not written")
            RandomAccessFile(tmp, "rw").use { it.fd.sync() }
            if (!verify(tmp)) throw IOException("composite failed verification")
            if (!tmp.renameTo(target)) throw IOException("rename failed")
            return target
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        } finally {
            synchronized(lock) { inFlight -= name }
        }
    }

    override fun markServed(file: File) {
        val name = file.name
        if (parseName(name) == null) return
        ensureInit()
        synchronized(lock) {
            served.remove(name)
            served.addLast(name)
            while (served.size > SERVED_RING) served.removeFirst()
        }
    }

    /**
     * Deletes (except protected files and those published less than [GRACE_MS] ago):
     * abandoned temp files; files older than 30 days; superseded generations of the same key;
     * then the oldest by publish time while the total exceeds [maxBytes].
     */
    override fun trim(maxBytes: Long) {
        ensureInit()
        synchronized(lock) {
            val now = clock()
            val files = dir.listFiles() ?: return
            files.filter { it.isFile && it.name.endsWith(AtomicFiles.TMP_SUFFIX) && it.name.removeSuffix(AtomicFiles.TMP_SUFFIX) !in inFlight }
                .forEach { it.delete() }
            val entries = files.mapNotNull(::parse)
            val newestGen = entries.groupBy { it.hash }.mapValues { (_, list) -> list.maxOf { it.gen } }
            fun removable(e: Entry) = e.file.name !in served && ageOf(e.gen, now) > GRACE_MS
            val kept = ArrayList<Entry>()
            for (e in entries) {
                val stale = ageOf(e.gen, now) > MAX_AGE_MS || e.gen < newestGen.getValue(e.hash)
                if (stale && removable(e) && e.file.delete()) continue
                kept += e
            }
            var total = kept.sumOf { it.size }
            for (e in kept.sortedBy { it.gen }) {
                if (total <= maxBytes) break
                if (removable(e) && e.file.delete()) total -= e.size
            }
        }
    }

    override fun sizeBytes(): Long = dir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L

    /**
     * Deletes all of its own files except protected ones (served to the host) and temp files of in-flight publishes:
     * deleting the wallpaper currently on screen would make Projectivy fall back to its default after retrying.
     * Protected files go through a regular trim once they drop out of the ring. Foreign files in [dir] are left alone.
     */
    override fun clear() {
        ensureInit()
        synchronized(lock) {
            dir.listFiles()?.forEach { f ->
                val name = f.name
                val own = if (name.endsWith(AtomicFiles.TMP_SUFFIX)) {
                    val base = name.removeSuffix(AtomicFiles.TMP_SUFFIX)
                    parseName(base) != null && base !in inFlight
                } else {
                    parseName(name) != null && name !in served
                }
                if (own && f.isFile) f.delete()
            }
        }
    }

    // ───────────── internals ─────────────

    private class Entry(val file: File, val hash: String, val gen: Long, val size: Long)

    private fun ensureInit() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            dir.mkdirs()
            val entries = ArrayList<Entry>()
            dir.listFiles()?.forEach { f ->
                // Temp files found before init are leftovers from a killed process: no publishes have started yet.
                if (f.name.endsWith(AtomicFiles.TMP_SUFFIX)) {
                    if (parseName(f.name.removeSuffix(AtomicFiles.TMP_SUFFIX)) != null) f.delete()
                } else {
                    parse(f)?.let(entries::add)
                }
            }
            lastGen = entries.maxOfOrNull { it.gen } ?: 0L
            entries.sortedBy { it.gen }.takeLast(SERVED_RING).forEach { served.addLast(it.file.name) }
            initialized = true
        }
    }

    private fun parse(file: File): Entry? {
        val (hash, gen) = parseName(file.name) ?: return null
        if (!file.isFile) return null
        return Entry(file, hash, gen, file.length())
    }

    private fun parseName(name: String): Pair<String, Long>? {
        val m = NAME.matchEntire(name) ?: return null
        val gen = m.groupValues[2].toLongOrNull() ?: return null
        return m.groupValues[1] to gen
    }

    private fun ageOf(gen: Long, now: Long = clock()): Long = (now - gen).coerceAtLeast(0)

    companion object {
        /** The last served file and the 5 before it. */
        const val SERVED_RING = 6
        const val MAX_AGE_MS = 30L * 24 * 60 * 60 * 1000
        /** A freshly published file is kept: it may not have been served to the host yet. */
        const val GRACE_MS = 2L * 60 * 1000

        private const val EXT = ".jpg"
        private val NAME = Regex("^([0-9a-f]{64})-(\\d{1,19})\\.jpg$")
    }
}
