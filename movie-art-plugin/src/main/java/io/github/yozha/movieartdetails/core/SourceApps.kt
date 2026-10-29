package io.github.yozha.movieartdetails.core

import io.github.yozha.movieartdetails.match.SourceHints
import io.github.yozha.movieartdetails.model.SourceAppMappings
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/** The app a card comes from, with the time it was last updated: part of composite keys, so an app update redraws its badge. */
data class BadgeRef(val packageName: String, val version: Long) {
    val key: String get() = "$packageName@$version"
}

/** Installed-app queries. Android implementation: [AndroidAppCatalog]; what is visible follows the manifest's `<queries>`. */
interface AppCatalog {
    /** Package of the content provider with [authority], or null when it is unknown or not visible. */
    fun providerPackage(authority: String): String?
    /** Visible packages with a launcher activity (TV or phone). */
    fun launchablePackages(): Collection<String>
    /** lastUpdateTime of an installed, visible package; null otherwise. */
    fun version(packageName: String): Long?
}

/**
 * Finds the source app of a card for its badge. ProgramCardFocused carries no package, only an iconUri, so the
 * package is inferred from the URI, in this order:
 * 1. the user's choice for the URI host ([SourceAppMappings]), while that app is installed;
 * 2. `content://` URIs: the launchable app that owns the provider authority, or else the longest launchable package
 *    name the authority starts with (`org.videolan.vlc.thumbprovider` → `org.videolan.vlc`). A provider owned by
 *    a system package without a launcher activity (the media or TV providers) is not a source app;
 * 3. a built-in table of well-known public image hosts ([HOST_TABLE]); any other web host is left to the user.
 *
 * Safe on the binder thread: catalog answers are memoized (provider owners and the package list for [LOOKUP_TTL_MS],
 * versions for [VERSION_TTL_MS]); the user mapping lookup is an in-memory read. Card lookups never load the package
 * list: it comes from [warmUp], and expired owners and lists are served as they are while [refresher] reloads them.
 * Only an authority seen for the first time is looked up in place (a single provider query). Never throws.
 */
class SourceApps(
    private val catalog: AppCatalog,
    private val mappings: SourceAppMappings,
    /** Runs background reloads of expired catalog answers. */
    private val refresher: Executor,
    /** Monotonic milliseconds. */
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private class Timed<T>(val value: T, val at: Long)

    /** Raw provider owners by authority, launchable or not; null when there is no such provider. */
    private val providers = ConcurrentHashMap<String, Timed<String?>>()
    private val versions = ConcurrentHashMap<String, Timed<Long?>>()
    @Volatile private var launchable: Timed<Set<String>>? = null
    /** Reloads queued on [refresher], so an expired entry is reloaded once however many cards ask for it. */
    private val pending = ConcurrentHashMap.newKeySet<String>()

    /** Badge identity for a program card's iconUri; null when its app is unknown. */
    fun forIconUri(iconUri: String?): BadgeRef? = packageFor(iconUri)?.let(::forPackage)

    /** Badge identity for an app card's package; null when it is missing or not installed. */
    fun forPackage(packageName: String?): BadgeRef? {
        val pkg = packageName?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return versionOf(pkg)?.let { BadgeRef(pkg, it) }
    }

    /** The installed package for [iconUri] (see the class description), or null. */
    fun packageFor(iconUri: String?): String? = try {
        val host = SourceHints.hostOf(iconUri)
        if (host == null) null else resolve(host, content = SourceHints.schemeOf(iconUri) == "content")
    } catch (_: Exception) {
        null
    }

    /**
     * The package found without the user's choice, for the settings screen. It knows only the host, not the scheme,
     * so a host is tried as a provider authority too; no web host is a prefix of a package name in practice.
     * Blocking: expired catalog answers are reloaded in place, so call it on a background thread.
     */
    fun automaticPackage(host: String): String? = try {
        val h = host.trim().lowercase()
        tablePackage(h) ?: authorityPackage(h, blocking = true)
    } catch (_: Exception) {
        null
    }

    private fun resolve(host: String, content: Boolean): String? {
        // A choice of an app that has since been removed is ignored, not shown as a missing badge.
        mappings.get(host)?.takeIf { versionOf(it) != null }?.let { return it }
        return if (content) authorityPackage(host, blocking = false) else tablePackage(host)
    }

    /** [blocking] reloads expired answers in place; otherwise they are reloaded on [refresher] (see the class description). */
    private fun authorityPackage(authority: String, blocking: Boolean): String? {
        val now = clock()
        val packages = launchablePackages(now, blocking)
        val owner = providerOwner(authority, now, blocking)?.takeIf { it in packages }
            ?: packages.filter { authority == it || authority.startsWith("$it.") }.maxByOrNull { it.length }
        return owner?.takeIf { versionOf(it) != null }
    }

    private fun providerOwner(authority: String, now: Long, blocking: Boolean): String? {
        val cached = providers[authority]
        if (cached == null || blocking && now - cached.at >= LOOKUP_TTL_MS) return loadProvider(authority, now)
        if (now - cached.at >= LOOKUP_TTL_MS) refreshLater("provider:$authority") {
            if (providers[authority]?.let { clock() - it.at < LOOKUP_TTL_MS } != true) loadProvider(authority, clock())
        }
        return cached.value
    }

    private fun loadProvider(authority: String, now: Long): String? {
        val owner = runCatching { catalog.providerPackage(authority) }.getOrNull()
        providers[authority] = Timed(owner, now)
        return owner
    }

    private fun tablePackage(host: String): String? =
        HOST_TABLE.firstOrNull { (domain, _) -> host == domain || host.endsWith(".$domain") }
            ?.second?.firstOrNull { versionOf(it) != null }

    /** Without [blocking], an empty set until the first load (normally [warmUp]) has finished. */
    private fun launchablePackages(now: Long, blocking: Boolean): Set<String> {
        val cached = launchable
        if (cached != null && now - cached.at < LOOKUP_TTL_MS) return cached.value
        if (blocking) return loadLaunchable(now)
        // Skipped when a warm-up still in progress at the time of the lookup has loaded it meanwhile.
        refreshLater("launchable") { if (launchable?.let { clock() - it.at < LOOKUP_TTL_MS } != true) loadLaunchable(clock()) }
        // Read again: a refresher that runs tasks in place has already stored the new list.
        return launchable?.value.orEmpty()
    }

    private fun loadLaunchable(now: Long): Set<String> {
        val set = runCatching { catalog.launchablePackages().toHashSet() }.getOrDefault(emptySet())
        launchable = Timed(set, now)
        return set
    }

    private fun refreshLater(key: String, load: () -> Unit) {
        if (!pending.add(key)) return
        try {
            // The loaders catch catalog errors themselves.
            refresher.execute {
                try {
                    load()
                } finally {
                    pending.remove(key)
                }
            }
        } catch (_: Exception) {
            // Rejected (the executor is shut down): the entry stays expired and the next lookup tries again.
            pending.remove(key)
        }
    }

    private fun versionOf(pkg: String): Long? {
        val now = clock()
        versions[pkg]?.takeIf { now - it.at < VERSION_TTL_MS }?.let { return it.value }
        val v = runCatching { catalog.version(pkg) }.getOrNull()
        versions[pkg] = Timed(v, now)
        return v
    }

    /** Loads the package list ahead of the first card (on a background thread). */
    fun warmUp() {
        launchablePackages(clock(), blocking = true)
    }

    companion object {
        /** How long provider owners and the package list are reused. */
        const val LOOKUP_TTL_MS = 10 * 60_000L
        /** How long an install check and its version are reused: an app update shows up within a minute. */
        const val VERSION_TTL_MS = 60_000L

        /**
         * Well-known public image hosts (a domain and its subdomains) and their apps, in order of preference.
         * Only hosts that unambiguously belong to one app's service; anything else is set by the user.
         */
        val HOST_TABLE: List<Pair<String, List<String>>> = listOf(
            "ytimg.com" to listOf("com.google.android.youtube.tv", "com.google.android.youtube"),
        )
    }
}
