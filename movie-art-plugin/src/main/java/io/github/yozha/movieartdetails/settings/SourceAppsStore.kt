package io.github.yozha.movieartdetails.settings

import android.content.Context
import io.github.yozha.movieartdetails.model.SourceAppMappings
import java.io.File
import java.io.IOException

/**
 * The user's source app choices: noBackupFilesDir/source_apps.json, host → package, with no expiry.
 * Local only: excluded from Auto Backup and getPreferences, like the manual corrections.
 * Contents are kept in memory; settings screens and the service share one instance (ServiceLocator).
 */
class SourceAppsStore(context: Context) : SourceAppMappings {
    private val file = File(context.noBackupFilesDir, FILE_NAME)
    private val lock = Any()
    /** Guarded by lock. */
    private var items: LinkedHashMap<String, String>? = null

    override fun get(host: String): String? = StoreCodec.normalizeHost(host)?.let { h -> synchronized(lock) { load()[h] } }

    override fun put(host: String, packageName: String) {
        val h = StoreCodec.normalizeHost(host) ?: return
        if (!StoreCodec.isPackageName(packageName)) return
        synchronized(lock) {
            val map = load()
            map.remove(h)
            map[h] = packageName
            while (map.size > StoreCodec.MAX_SOURCE_APPS) map.remove(map.keys.first())
            save(map)
        }
    }

    override fun remove(host: String) {
        val h = StoreCodec.normalizeHost(host) ?: return
        synchronized(lock) {
            val map = load()
            if (map.remove(h) != null) save(map)
        }
    }

    override fun all(): Map<String, String> = synchronized(lock) { LinkedHashMap(load()) }

    private fun load(): LinkedHashMap<String, String> = items ?: LinkedHashMap<String, String>().also { map ->
        SafeFiles.read(file, MAX_FILE_BYTES)?.let { map.putAll(StoreCodec.decodeSourceApps(it.toString(Charsets.UTF_8))) }
        items = map
    }

    private fun save(map: Map<String, String>) {
        try {
            SafeFiles.write(file, StoreCodec.encodeSourceApps(map).toByteArray(Charsets.UTF_8))
        } catch (_: IOException) {
            // Out of disk space: the choice stays in effect until the process restarts.
        }
    }

    companion object {
        const val FILE_NAME = "source_apps.json"
        private const val MAX_FILE_BYTES = 256L * 1024
    }
}
