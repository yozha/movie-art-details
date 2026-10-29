package io.github.yozha.movieartdetails.settings

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** Atomic writes of small store files: temp file → fsync → rename. */
internal object SafeFiles {

    fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try {
            FileOutputStream(tmp).use {
                it.write(bytes)
                it.fd.sync()
            }
            if (!tmp.renameTo(target)) throw IOException("rename failed")
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    /** File contents, or null if the file is missing, empty or larger than [maxBytes]. */
    fun read(file: File, maxBytes: Long): ByteArray? = try {
        if (!file.isFile || file.length() <= 0 || file.length() > maxBytes) null else file.readBytes()
    } catch (_: IOException) {
        null
    }
}
