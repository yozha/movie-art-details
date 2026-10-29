package io.github.yozha.movieartdetails.cache

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/** Shared cache primitives: atomic writes (temp file → fsync → rename) and key hashing. */
internal object AtomicFiles {

    const val TMP_SUFFIX = ".tmp"

    private val seq = AtomicLong(System.nanoTime())

    fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    /** Process-unique temp file next to [target], so concurrent writes of the same key don't collide. */
    fun tempFor(target: File): File = File(target.parentFile, "${target.name}.${seq.incrementAndGet()}$TMP_SUFFIX")

    /**
     * Writes [bytes] to [target] atomically. [mtime] is the write time on the cache clock (age is computed
     * from it; reading the file doesn't change it). The rename runs under [lock] so it can't race a trim.
     */
    fun write(target: File, bytes: ByteArray, mtime: Long, lock: Any): File {
        target.parentFile?.mkdirs()
        val tmp = tempFor(target)
        try {
            FileOutputStream(tmp).use {
                it.write(bytes)
                it.fd.sync()
            }
            tmp.setLastModified(mtime.coerceAtLeast(0))
            synchronized(lock) {
                if (!tmp.renameTo(target)) throw IOException("rename failed")
            }
            return target
        } catch (e: Throwable) {
            tmp.delete()
            throw e
        }
    }
}
