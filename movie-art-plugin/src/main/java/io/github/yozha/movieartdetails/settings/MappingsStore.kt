package io.github.yozha.movieartdetails.settings

import android.content.Context
import io.github.yozha.movieartdetails.model.ManualMapping
import io.github.yozha.movieartdetails.model.MappingsRepository
import java.io.File
import java.io.IOException

/**
 * Manual mappings: noBackupFilesDir/mappings.json, with no expiry.
 * Stores the key, the raw title and type + TMDB ID; no TMDB data. Not part of getPreferences yet.
 * Contents are kept in memory; settings screens and the service must share one instance (ServiceLocator).
 */
class MappingsStore(context: Context) : MappingsRepository {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val lock = Any()
    /** Guarded by lock; insertion order. */
    private var items: LinkedHashMap<String, ManualMapping>? = null

    override fun get(key: String): ManualMapping? = synchronized(lock) { load()[key] }

    override fun put(mapping: ManualMapping) {
        synchronized(lock) {
            val map = load()
            map.remove(mapping.key)
            map[mapping.key] = mapping
            save(map)
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            val map = load()
            if (map.remove(key) != null) save(map)
        }
    }

    override fun all(): List<ManualMapping> = synchronized(lock) { load().values.toList() }

    private fun load(): LinkedHashMap<String, ManualMapping> = items ?: LinkedHashMap<String, ManualMapping>().also { map ->
        SafeFiles.read(file, MAX_FILE_BYTES)?.let { StoreCodec.decodeMappings(it.toString(Charsets.UTF_8)) }?.forEach { map[it.key] = it }
        items = map
    }

    private fun save(map: LinkedHashMap<String, ManualMapping>) {
        while (map.size > StoreCodec.MAX_MAPPINGS) map.remove(map.keys.first())
        try {
            SafeFiles.write(file, StoreCodec.encodeMappings(map.values).toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            // Out of disk space: the mapping stays in effect until the process restarts.
        }
    }

    companion object {
        const val FILE_NAME = "mappings.json"
        private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    }
}
