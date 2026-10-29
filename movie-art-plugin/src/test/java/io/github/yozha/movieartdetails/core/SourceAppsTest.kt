package io.github.yozha.movieartdetails.core

import io.github.yozha.movieartdetails.model.SourceAppMappings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.Executor

/** Source app resolution: user choices, content authorities and the built-in host table, on a fake catalog. */
class SourceAppsTest {

    private val catalog = FakeCatalog()
    private val mappings = FakeMappings()
    private var now = 1_000L
    /** Background reloads run in place unless [deferred]; then they wait in [queued] until [runQueued]. */
    private var deferred = false
    private val queued = ArrayList<Runnable>()
    private val apps = SourceApps(catalog, mappings, Executor { if (deferred) queued += it else it.run() }) { now }

    private fun runQueued() {
        val tasks = queued.toList()
        queued.clear()
        tasks.forEach { it.run() }
    }

    private fun install(vararg packages: String, launchable: Boolean = true) {
        packages.forEach { catalog.versions[it] = 100L + it.length }
        if (launchable) catalog.launchable += packages
    }

    @Test
    fun `content authority resolves to the provider owner`() {
        install("com.example.player")
        catalog.providers["thumbs.example"] = "com.example.player"
        assertEquals("com.example.player", apps.packageFor("content://thumbs.example/media/1"))
        assertEquals(BadgeRef("com.example.player", 118L), apps.forIconUri("content://thumbs.example/media/1"))
    }

    @Test
    fun `content authority falls back to the longest visible package prefix`() {
        install("org.videolan", "org.videolan.vlc", "org.videolan.vlcx")
        assertEquals("org.videolan.vlc", apps.packageFor("content://org.videolan.vlc.thumbprovider/media/1"))
        assertEquals("org.videolan.vlc", apps.packageFor("content://org.videolan.vlc/x"))
        // A prefix must end at a dot: "org.videolan.vlc" does not own "org.videolan.vlcplayer.thumbs".
        assertEquals("org.videolan", apps.packageFor("content://org.videolan.vlcplayer.thumbs/x"))
        assertNull(apps.packageFor("content://com.unknown.provider/x"))
    }

    @Test
    fun `a provider owner that is not installed gives no badge`() {
        catalog.providers["thumbs.example"] = "com.example.gone"
        assertNull(apps.forIconUri("content://thumbs.example/x"))
    }

    @Test
    fun `a provider owned by a system package without a launcher is not a source app`() {
        install("com.android.providers.media", "com.android.providers.tv", launchable = false)
        install("com.example.player")
        catalog.providers["media"] = "com.android.providers.media"
        catalog.providers["android.media.tv"] = "com.android.providers.tv"
        catalog.providers["thumbs.example"] = "com.example.player"
        assertNull(apps.packageFor("content://media/external/images/media/1"))
        assertNull(apps.forIconUri("content://android.media.tv/program/1"))
        assertNull(apps.automaticPackage("android.media.tv"))
        assertEquals("com.example.player", apps.packageFor("content://thumbs.example/x"))
    }

    @Test
    fun `a provider owner without a launcher falls back to the package prefix`() {
        install("com.android.providers.media", launchable = false)
        install("org.videolan.vlc")
        catalog.providers["org.videolan.vlc.thumbprovider"] = "com.android.providers.media"
        assertEquals("org.videolan.vlc", apps.packageFor("content://org.videolan.vlc.thumbprovider/x"))
    }

    @Test
    fun `authorities are matched only for content URIs`() {
        install("org.videolan.vlc")
        assertNull(apps.packageFor("https://org.videolan.vlc.cdn/x.jpg"))
    }

    @Test
    fun `youtube image hosts map to the TV app, then the phone app`() {
        assertNull(apps.packageFor("https://i.ytimg.com/vi/x/hq.jpg"))
        install("com.google.android.youtube")
        now += SourceApps.VERSION_TTL_MS
        assertEquals("com.google.android.youtube", apps.packageFor("https://i.ytimg.com/vi/x/hq.jpg"))
        install("com.google.android.youtube.tv")
        now += SourceApps.VERSION_TTL_MS
        assertEquals("com.google.android.youtube.tv", apps.packageFor("https://i.ytimg.com/vi/x/hq.jpg"))
        assertEquals("com.google.android.youtube.tv", apps.packageFor("https://ytimg.com/x.jpg"))
        assertNull(apps.packageFor("https://notytimg.com/x.jpg"))
        assertNull(apps.packageFor("https://ytimg.com.example.org/x.jpg"))
    }

    @Test
    fun `other web hosts are unknown without a user choice`() {
        install("com.example.mediaclient")
        assertNull(apps.packageFor("http://192.168.1.20:8096/Items/1/Images/Primary"))
        assertNull(apps.packageFor("https://cdn.example.com/poster.jpg"))
        assertNull(apps.packageFor(null))
        assertNull(apps.packageFor("not a uri"))
    }

    @Test
    fun `user choice for a host wins over the table and the authority`() {
        install("com.example.mediaclient", "com.google.android.youtube.tv", "org.videolan.vlc", "com.example.other")
        mappings.put("192.168.1.20", "com.example.mediaclient")
        mappings.put("i.ytimg.com", "com.example.other")
        mappings.put("org.videolan.vlc.thumbprovider", "com.example.other")
        assertEquals("com.example.mediaclient", apps.packageFor("http://192.168.1.20:8096/Items/1/Images/Primary"))
        assertEquals("com.example.other", apps.packageFor("https://i.ytimg.com/vi/x/hq.jpg"))
        assertEquals("com.example.other", apps.packageFor("content://org.videolan.vlc.thumbprovider/x"))
        // The table still applies to the other subdomains.
        assertEquals("com.google.android.youtube.tv", apps.packageFor("https://i9.ytimg.com/vi/x/hq.jpg"))
    }

    @Test
    fun `user choice of an app that was removed is ignored`() {
        install("com.google.android.youtube.tv")
        mappings.put("i.ytimg.com", "com.example.removed")
        mappings.put("192.168.1.20", "com.example.removed")
        assertEquals("com.google.android.youtube.tv", apps.packageFor("https://i.ytimg.com/vi/x/hq.jpg"))
        assertNull(apps.packageFor("http://192.168.1.20/x"))
    }

    @Test
    fun `app cards resolve by package when installed`() {
        install("com.example.app")
        assertEquals(BadgeRef("com.example.app", 115L), apps.forPackage(" com.example.app "))
        assertNull(apps.forPackage("com.example.missing"))
        assertNull(apps.forPackage(null))
        assertNull(apps.forPackage(""))
    }

    @Test
    fun `badge identity changes with the app version`() {
        install("com.example.app")
        val first = apps.forPackage("com.example.app")
        catalog.versions["com.example.app"] = 999L
        assertEquals(first, apps.forPackage("com.example.app"))
        now += SourceApps.VERSION_TTL_MS
        assertEquals(BadgeRef("com.example.app", 999L), apps.forPackage("com.example.app"))
        assertEquals("com.example.app@999", apps.forPackage("com.example.app")!!.key)
    }

    @Test
    fun `catalog lookups are memoized`() {
        install("org.videolan.vlc")
        repeat(5) { apps.forIconUri("content://org.videolan.vlc.thumbprovider/x") }
        assertEquals(1, catalog.providerCalls)
        assertEquals(1, catalog.listCalls)
        assertEquals(1, catalog.versionCalls)
        now += SourceApps.LOOKUP_TTL_MS
        apps.forIconUri("content://org.videolan.vlc.thumbprovider/x")
        assertEquals(2, catalog.providerCalls)
        assertEquals(2, catalog.listCalls)
    }

    @Test
    fun `expired lookups are served stale and reloaded in the background`() {
        install("org.videolan.vlc", "com.example.player")
        catalog.providers["thumbs.example"] = "com.example.player"
        apps.warmUp()
        assertEquals("com.example.player", apps.packageFor("content://thumbs.example/x"))
        assertEquals(1, catalog.listCalls)
        assertEquals(1, catalog.providerCalls)

        deferred = true
        catalog.providers["thumbs.example"] = "org.videolan.vlc"
        catalog.launchable.remove("com.example.player")
        now += SourceApps.LOOKUP_TTL_MS
        repeat(3) { assertEquals("com.example.player", apps.packageFor("content://thumbs.example/x")) }
        // Nothing was loaded on the calling thread, and each reload is queued once.
        assertEquals(1, catalog.listCalls)
        assertEquals(1, catalog.providerCalls)
        assertEquals(2, queued.size)

        runQueued()
        assertEquals(2, catalog.listCalls)
        assertEquals(2, catalog.providerCalls)
        assertEquals("org.videolan.vlc", apps.packageFor("content://thumbs.example/x"))
        assertEquals(0, queued.size)
    }

    @Test
    fun `card lookups never load the package list in place`() {
        install("org.videolan.vlc")
        deferred = true
        assertNull(apps.packageFor("content://org.videolan.vlc.thumbprovider/x"))
        assertEquals(0, catalog.listCalls)
        // A new authority is looked up once in place: a single provider query.
        assertEquals(1, catalog.providerCalls)
        runQueued()
        assertEquals(1, catalog.listCalls)
        assertEquals("org.videolan.vlc", apps.packageFor("content://org.videolan.vlc.thumbprovider/x"))
        assertEquals(1, catalog.providerCalls)
    }

    @Test
    fun `settings lookups reload expired answers in place`() {
        install("org.videolan.vlc")
        deferred = true
        assertEquals("org.videolan.vlc", apps.automaticPackage("org.videolan.vlc.thumbprovider"))
        install("org.videolan.vlc.beta")
        now += SourceApps.LOOKUP_TTL_MS
        assertEquals("org.videolan.vlc.beta", apps.automaticPackage("org.videolan.vlc.beta.thumbprovider"))
        assertEquals(2, catalog.listCalls)
        assertEquals(0, queued.size)
    }

    @Test
    fun `catalog errors mean no badge`() {
        catalog.fail = true
        mappings.put("i.ytimg.com", "com.example.app")
        assertNull(apps.forIconUri("content://org.videolan.vlc.thumbprovider/x"))
        assertNull(apps.forIconUri("https://i.ytimg.com/x.jpg"))
        assertNull(apps.forPackage("com.example.app"))
    }

    @Test
    fun `automatic package for the settings screen ignores the user choice`() {
        install("org.videolan.vlc", "com.google.android.youtube.tv", "com.example.other")
        mappings.put("i.ytimg.com", "com.example.other")
        assertEquals("com.google.android.youtube.tv", apps.automaticPackage("I.YTIMG.COM"))
        assertEquals("org.videolan.vlc", apps.automaticPackage("org.videolan.vlc.thumbprovider"))
        assertNull(apps.automaticPackage("192.168.1.20"))
    }

    private class FakeCatalog : AppCatalog {
        val providers = HashMap<String, String>()
        val launchable = ArrayList<String>()
        val versions = HashMap<String, Long>()
        var fail = false
        var providerCalls = 0
        var listCalls = 0
        var versionCalls = 0

        override fun providerPackage(authority: String): String? {
            providerCalls++
            if (fail) throw SecurityException("boom")
            return providers[authority]
        }

        override fun launchablePackages(): Collection<String> {
            listCalls++
            if (fail) throw SecurityException("boom")
            return launchable.toList()
        }

        override fun version(packageName: String): Long? {
            versionCalls++
            if (fail) throw SecurityException("boom")
            return versions[packageName]
        }
    }

    private class FakeMappings : SourceAppMappings {
        private val map = HashMap<String, String>()
        override fun get(host: String) = map[host]
        override fun put(host: String, packageName: String) {
            map[host] = packageName
        }
        override fun remove(host: String) {
            map.remove(host)
        }
        override fun all(): Map<String, String> = map.toMap()
    }
}
