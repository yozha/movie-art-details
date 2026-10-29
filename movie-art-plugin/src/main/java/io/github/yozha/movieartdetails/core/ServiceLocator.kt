package io.github.yozha.movieartdetails.core

import android.content.Context
import android.graphics.BitmapFactory
import io.github.yozha.movieartdetails.BuildConfig
import io.github.yozha.movieartdetails.cache.CompositeStoreImpl
import io.github.yozha.movieartdetails.cache.SourceCacheImpl
import io.github.yozha.movieartdetails.model.CompositeStore
import io.github.yozha.movieartdetails.model.CredentialRepository
import io.github.yozha.movieartdetails.model.Diagnostics
import io.github.yozha.movieartdetails.model.MappingsRepository
import io.github.yozha.movieartdetails.model.RecentTitlesRepository
import io.github.yozha.movieartdetails.model.SettingsRepository
import io.github.yozha.movieartdetails.model.SourceAppMappings
import io.github.yozha.movieartdetails.model.SourceCache
import io.github.yozha.movieartdetails.model.TmdbClient
import io.github.yozha.movieartdetails.model.WallpaperRenderer
import io.github.yozha.movieartdetails.render.InfoBlockRenderer
import io.github.yozha.movieartdetails.settings.AppSettings
import io.github.yozha.movieartdetails.settings.CredentialStore
import io.github.yozha.movieartdetails.settings.MappingsStore
import io.github.yozha.movieartdetails.settings.RecentTitlesStore
import io.github.yozha.movieartdetails.settings.SourceAppsStore
import io.github.yozha.movieartdetails.tmdb.TmdbHttpClient
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient

/**
 * Process-wide module singletons. The service and settings screens call [init] before first use.
 * Directories: filesDir/cache-v1 is the level 1 cache, filesDir/published holds composites (the only thing FileProvider exposes).
 */
object ServiceLocator {

    @Volatile private var app: Context? = null

    fun init(context: Context) {
        if (app == null) synchronized(this) { if (app == null) app = context.applicationContext }
    }

    private val context: Context get() = checkNotNull(app) { "ServiceLocator.init() not called" }

    val settings: SettingsRepository by lazy { AppSettings(context) }
    val credentials: CredentialRepository by lazy { CredentialStore(context) }
    val mappings: MappingsRepository by lazy { MappingsStore(context) }
    val recent: RecentTitlesRepository by lazy { RecentTitlesStore(context) }
    val sourceAppMappings: SourceAppMappings by lazy { SourceAppsStore(context) }
    val appCatalog: AndroidAppCatalog by lazy { AndroidAppCatalog(context) }
    val sourceApps: SourceApps by lazy { SourceApps(appCatalog, sourceAppMappings, refresher = ioPool) }
    val badges: AppBadges by lazy { AndroidAppBadges(context, sourceApps) }
    val diagnostics: Diagnostics by lazy { DiagnosticsLog(context) { settings.diagnosticsEnabled() } }

    /**
     * One client per process with explicit timeouts; no on-disk HTTP cache.
     * callTimeout of 8 s is the upper bound; during preparation a request is limited to the remaining T_bg ([RequestDeadline]).
     */
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .callTimeout(8, TimeUnit.SECONDS)
            .addInterceptor(RequestDeadline.interceptor)
            .cache(null)
            .build()
    }

    /** The user's key; the dev key from keystore.properties is used only in debug builds (the field is empty in release). */
    val tmdb: TmdbClient by lazy {
        TmdbHttpClient({ credentials.get() ?: BuildConfig.DEV_TMDB_KEY.takeIf { BuildConfig.DEBUG && it.isNotBlank() } }, http)
    }

    private val sourceCacheImpl by lazy { SourceCacheImpl(File(context.filesDir, "cache-v1")) }
    val sourceCache: SourceCache get() = sourceCacheImpl

    /** Check before rename: the file decodes (bounds only) and has frame dimensions. */
    val compositeStore: CompositeStore by lazy {
        CompositeStoreImpl(File(context.filesDir, "published"), verify = { file ->
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, o)
            o.outWidth > 0 && o.outHeight > 0
        })
    }

    val renderer: WallpaperRenderer by lazy { InfoBlockRenderer(context) }

    /** Preparation pool: 2 threads. The queue is unbounded; stale jobs are skipped when they start. */
    private val prepPool: ExecutorService by lazy { pool("mad-prep", 2) }
    /** Parallel movie/tv search requests within a single preparation. */
    private val searchPool: ExecutorService by lazy { pool("mad-search", 4) }
    /** Logo downloads next to the backdrop, one per preparation thread; separate from searches so neither waits for the other. */
    private val logoPool: ExecutorService by lazy { pool("mad-logo", 2) }
    private val ioPool: ExecutorService by lazy { pool("mad-io", 1) }
    /** Delayed one-off tasks (repeating an unanswered broadcast). */
    private val timer: ScheduledExecutorService by lazy {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mad-timer").apply { isDaemon = true } }
    }

    internal val coordinator: Coordinator by lazy {
        // TMDB frames and titled frames share it: one render at a time.
        val renderLock = Any()
        val pipeline = CardPipeline(
            tmdb = tmdb,
            sourceCache = sourceCache,
            compositeStore = compositeStore,
            renderer = renderer,
            settings = settings,
            mappings = mappings,
            recent = recent,
            diagnostics = diagnostics,
            searchExecutor = searchPool,
            logoExecutor = logoPool,
            background = { ioPool.execute(it) },
            afterPublish = ::trimCachesThrottled,
            onStorageError = ::trimCaches,
            badges = badges,
            renderLock = renderLock,
        )
        val titled = TitledCards(
            compositeStore = compositeStore,
            renderer = renderer,
            settings = settings,
            badges = badges,
            diagnostics = diagnostics,
            renderLock = renderLock,
            afterPublish = ::trimCachesThrottled,
        )
        Coordinator(
            resolver = pipeline,
            host = ProjectivyHost(context, compositeStore),
            executor = prepPool,
            diagnostics = diagnostics,
            neutralColor = { settings.neutralColor() },
            tintFromCard = { settings.tintNeutralFromCard() },
            logTitles = { settings.diagnosticsEnabled() && settings.logTitlesInDiagnostics() },
            titled = titled,
            later = { delayMs, task -> timer.schedule({ runCatching(task) }, delayMs, TimeUnit.MILLISECONDS) },
        )
    }

    @Volatile private var lastTrimAt = 0L

    /**
     * Trims both cache levels to the configured limit (200 MB by default): a quarter for composites,
     * the rest for sources. CompositeStore leaves published files recently served to the host alone.
     */
    fun trimCaches() {
        val limit = settings.cacheLimitBytes().coerceAtLeast(MIN_CACHE_BYTES)
        val composites = limit / 4
        compositeStore.trim(composites)
        sourceCacheImpl.trim(limit - composites)
        lastTrimAt = System.currentTimeMillis()
    }

    /** "Clear cache" in settings: only the plugin's own files. */
    fun clearCaches() {
        sourceCache.clear()
        compositeStore.clear()
    }

    /** Opens the TMDB connections off the main thread (reading the key may touch the Keystore); the client throttles it. */
    internal fun warmUpAsync() {
        ioPool.execute { runCatching { tmdb.warmUp() } }
        // The package list for source-app badges, so the first card does not load it on the binder thread.
        ioPool.execute { runCatching { badges.warmUp() } }
    }

    /** Background maintenance on service start. */
    internal fun maintainAsync() {
        ioPool.execute { runCatching { trimCaches() } }
    }

    private fun trimCachesThrottled() {
        if (System.currentTimeMillis() - lastTrimAt < TRIM_INTERVAL_MS) return
        lastTrimAt = System.currentTimeMillis()
        ioPool.execute { runCatching { trimCaches() } }
    }

    private fun pool(name: String, threads: Int): ExecutorService {
        val seq = AtomicInteger()
        val factory = ThreadFactory { r ->
            Thread(r, "$name-${seq.incrementAndGet()}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
        return ThreadPoolExecutor(threads, threads, 30, TimeUnit.SECONDS, LinkedBlockingQueue(), factory)
            .apply { allowCoreThreadTimeOut(true) }
    }

    private const val TRIM_INTERVAL_MS = 10 * 60_000L
    private const val MIN_CACHE_BYTES = 20L * 1024 * 1024
}
