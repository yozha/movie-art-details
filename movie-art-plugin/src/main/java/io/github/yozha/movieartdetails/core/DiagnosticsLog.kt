package io.github.yozha.movieartdetails.core

import android.content.Context
import io.github.yozha.movieartdetails.model.Diagnostics
import io.github.yozha.movieartdetails.model.TmdbError
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Diagnostics log: the last [MAX_EVENTS] events in memory and in a small file,
 * noBackupFilesDir/diagnostics.log (excluded from Auto Backup). Events are recorded only when the mode is enabled
 * ([enabled]); the last TMDB error is always kept, since settings display its status.
 *
 * Every line goes through [sanitize]: URLs, api_key values, 32-char hex sequences and JWT-shaped tokens are stripped
 * even if the caller slips up. Only Coordinator passes card titles here, and only when allowed to.
 */
class DiagnosticsLog internal constructor(
    private val file: File,
    private val enabled: () -> Boolean,
    private val clock: () -> Long,
    private val io: Executor,
) : Diagnostics {

    constructor(context: Context, enabled: () -> Boolean = { true }) : this(
        File(context.applicationContext.noBackupFilesDir, FILE_NAME),
        enabled,
        System::currentTimeMillis,
        Executors.newSingleThreadExecutor { r -> Thread(r, "mad-diag").apply { isDaemon = true } },
    )

    private val lock = Any()
    // Guarded by lock.
    private var ring: ArrayDeque<String>? = null
    @Volatile private var lastErrorLine: String? = null
    private val savePending = AtomicBoolean(false)

    override fun event(line: String) {
        if (!runCatching(enabled).getOrDefault(false)) return
        add(line)
    }

    override fun lastError(error: TmdbError?) {
        val text = error?.let(::describe)
        lastErrorLine = text?.let { "${stamp()} $it" }
        if (text != null) event("error $text")
    }

    /** Newest first. */
    override fun recent(): List<String> = synchronized(lock) { loaded().reversed() }

    /** Last TMDB error for the status screen: time, kind, HTTP and status_code; never the URL or key. */
    override fun lastErrorText(): String? = lastErrorLine

    override fun clear() {
        synchronized(lock) { ring = ArrayDeque() }
        lastErrorLine = null
        scheduleSave()
    }

    private fun add(line: String) {
        val entry = "${stamp()} ${sanitize(line)}"
        synchronized(lock) {
            val r = loaded()
            r.addLast(entry)
            while (r.size > MAX_EVENTS) r.removeFirst()
        }
        scheduleSave()
    }

    /** Guarded by lock. The file is read once, lazily. */
    private fun loaded(): ArrayDeque<String> = ring ?: ArrayDeque<String>().also { r ->
        runCatching { if (file.isFile) file.readLines().takeLast(MAX_EVENTS).forEach { r.addLast(it) } }
        ring = r
    }

    /** One background write per batch of events, so the binder thread never waits on disk. */
    private fun scheduleSave() {
        if (!savePending.compareAndSet(false, true)) return
        try {
            io.execute {
                savePending.set(false)
                val text = synchronized(lock) { loaded().joinToString("\n") }
                runCatching {
                    file.parentFile?.mkdirs()
                    val tmp = File(file.path + ".tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(file)) tmp.delete()
                }
            }
        } catch (_: Throwable) {
            savePending.set(false)
        }
    }

    private fun stamp(): String = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(Date(clock()))

    companion object {
        const val MAX_EVENTS = 50
        private const val FILE_NAME = "diagnostics.log"
        private const val MAX_LINE = 240

        private val URL = Regex("""(?i)\b(?:https?|content|file)://\S+""")
        private val API_KEY = Regex("""(?i)api_key=[^&\s]*""")
        private val HEX32 = Regex("""(?i)\b[0-9a-f]{32,}\b""")
        private val JWT = Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
        private val WS = Regex("""\s+""")

        internal fun sanitize(line: String): String = line
            .replace(URL, "<uri>")
            .replace(API_KEY, "api_key=***")
            .replace(HEX32, "***")
            .replace(JWT, "***")
            .replace(WS, " ")
            .trim()
            .take(MAX_LINE)

        internal fun describe(e: TmdbError): String = buildString {
            append(e.kind.name)
            e.httpCode?.let { append(" http=").append(it) }
            e.statusCode?.let { append(" status=").append(it) }
            e.retryAfterMs?.let { append(" retry=").append(it).append("ms") }
            if (e.message.isNotBlank()) append(" ").append(sanitize(e.message).take(80))
        }
    }
}
