package io.github.yozha.movieartdetails.core

import android.graphics.Bitmap
import io.github.yozha.movieartdetails.model.AppBadge
import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.CompositeStore
import io.github.yozha.movieartdetails.model.Diagnostics
import io.github.yozha.movieartdetails.model.DisplayInfo
import io.github.yozha.movieartdetails.model.DisplayLanguage
import io.github.yozha.movieartdetails.model.SettingsRepository
import io.github.yozha.movieartdetails.model.TextPreset
import io.github.yozha.movieartdetails.model.TmdbError
import io.github.yozha.movieartdetails.model.WallpaperRenderer
import io.github.yozha.movieartdetails.render.BlockContent
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Titled neutral frames on fakes: when they apply, their cache keys, rendering and failures. */
class TitledCardsTest {

    @get:Rule val tmp = TemporaryFolder()

    private val settings = FakeSettings()
    private val renderer = FakeRenderer()
    private val badges = FakeBadges()
    private val diagnostics = FakeDiagnostics()
    private lateinit var store: FakeStore
    private lateinit var titled: TitledCards
    private var published = 0

    private var current = true
    private val ctx = object : JobContext {
        override fun isCurrent() = current
        override fun remainingMs() = 8_000L
    }

    @Before
    fun setUp() {
        store = FakeStore(tmp.newFolder("published"))
        titled = TitledCards(store, renderer, settings, badges, diagnostics, afterPublish = { published++ })
    }

    private val youtube = "https://i.ytimg.com/vi/x/hq.jpg"
    private fun program(title: String = "Sample Video", color: String = "#101216") = NeutralCard(title, iconUri = youtube, color = color)

    @Test
    fun `setting off or no title means a plain color`() {
        settings.titled = false
        assertEquals(Outcome.Rejected(TitledCards.OFF), titled.quick(program()))
        assertEquals(Outcome.Rejected(TitledCards.OFF), titled.prepare(program(), ctx))
        settings.titled = true
        assertTrue(titled.quick(program(title = " \n ")) is Outcome.Rejected)
        assertTrue(titled.quick(program(color = "red")) is Outcome.Rejected)
        assertTrue(renderer.titles.isEmpty())
    }

    @Test
    fun `uncached frame is pending, rendered once, then served from the store`() {
        badges.refs[youtube] = BadgeRef("com.google.android.youtube.tv", 7L)
        val pending = titled.quick(program())
        assertTrue(pending is Outcome.Pending)
        assertTrue((pending as Outcome.Pending).jobKey.startsWith("titled|"))

        val ready = titled.prepare(program(), ctx) as Outcome.Ready
        assertEquals("titled", ready.note)
        assertEquals("Sample Video", ready.composite.title)
        assertNull(ready.composite.sourceUrl)
        assertNull(ready.composite.author)
        assertEquals(listOf("Sample Video"), renderer.titles)
        assertEquals(0xFF101216.toInt(), renderer.backgrounds.single())
        assertEquals(listOf(BadgeRef("com.google.android.youtube.tv", 7L)), badges.loaded)
        assertEquals(1, published)
        assertEquals(pending.jobKey, store.keys.single())

        val cached = titled.quick(program())
        assertEquals(Outcome.Ready(ready.composite, "titled:L2"), cached)
        assertEquals(Outcome.Ready(ready.composite, "titled:L2"), titled.prepare(program(), ctx))
        assertEquals(1, renderer.titles.size)
    }

    @Test
    fun `title is collapsed and capped`() {
        titled.prepare(program(title = "  Sample\n\tVideo  "), ctx)
        assertEquals("Sample Video", renderer.titles.single())
        val long = "x".repeat(500)
        titled.prepare(program(title = long), ctx)
        assertEquals(BlockContent.MAX_TITLED_CHARS, renderer.titles.last().length)
    }

    @Test
    fun `key covers title, badge, color, appearance, renderer and size`() {
        val base = TitledCards.compositeKey("Sample", BadgeRef("com.example.app", 1L), 0xFF101216.toInt(), AppearanceSettings(), 1)
        assertTrue(base.startsWith("titled|Sample|app:com.example.app@1|"))
        assertTrue(base.endsWith("|r1|${CardPipeline.CANVAS}"))
        val variants = listOf(
            TitledCards.compositeKey("Sample 2", BadgeRef("com.example.app", 1L), 0xFF101216.toInt(), AppearanceSettings(), 1),
            TitledCards.compositeKey("Sample", BadgeRef("com.example.app", 2L), 0xFF101216.toInt(), AppearanceSettings(), 1),
            TitledCards.compositeKey("Sample", BadgeRef("com.example.other", 1L), 0xFF101216.toInt(), AppearanceSettings(), 1),
            TitledCards.compositeKey("Sample", null, 0xFF101216.toInt(), AppearanceSettings(), 1),
            TitledCards.compositeKey("Sample", BadgeRef("com.example.app", 1L), 0xFF123456.toInt(), AppearanceSettings(), 1),
            TitledCards.compositeKey("Sample", BadgeRef("com.example.app", 1L), 0xFF101216.toInt(), AppearanceSettings(textPreset = TextPreset.LARGE), 1),
            TitledCards.compositeKey("Sample", BadgeRef("com.example.app", 1L), 0xFF101216.toInt(), AppearanceSettings(), 2),
        )
        variants.forEach { assertNotEquals(base, it) }
        assertEquals(variants.size, variants.toSet().size)
    }

    @Test
    fun `app cards use their package, program cards their iconUri`() {
        badges.packages["com.example.app"] = BadgeRef("com.example.app", 3L)
        badges.refs[youtube] = BadgeRef("com.google.android.youtube.tv", 7L)
        val app = titled.quick(NeutralCard("Sample App", packageName = "com.example.app", color = "#123456")) as Outcome.Pending
        assertTrue(app.jobKey.contains("|app:com.example.app@3|"))
        assertTrue(app.jobKey.contains("|ff123456|"))
        val video = titled.quick(program()) as Outcome.Pending
        assertTrue(video.jobKey.contains("|app:com.google.android.youtube.tv@7|"))
        val shortcut = titled.quick(NeutralCard("Shortcut", color = "#123456")) as Outcome.Pending
        assertTrue(shortcut.jobKey.contains("|app:-|"))
    }

    @Test
    fun `badge is left out when the setting hides it`() {
        badges.refs[youtube] = BadgeRef("com.google.android.youtube.tv", 7L)
        settings.appearance = AppearanceSettings(showAppBadge = false)
        val pending = titled.quick(program()) as Outcome.Pending
        assertTrue(pending.jobKey.contains("|app:-|"))
        titled.prepare(program(), ctx)
        assertTrue(badges.loaded.isEmpty())
        assertEquals(0, badges.lookups)
    }

    @Test
    fun `render failure is a transient failure and publishes nothing`() {
        renderer.fail = IOException("disk full")
        assertEquals(Outcome.Failed("titled:error"), titled.prepare(program(), ctx))
        renderer.fail = OutOfMemoryError("bitmap")
        assertEquals(Outcome.Failed("titled:oom"), titled.prepare(program(), ctx))
        assertTrue(store.keys.isEmpty())
        assertEquals(0, published)
    }

    @Test
    fun `badge that fails to load is left out of the frame`() {
        badges.refs[youtube] = BadgeRef("com.google.android.youtube.tv", 7L)
        badges.failLoad = true
        assertTrue(titled.prepare(program(), ctx) is Outcome.Ready)
        assertEquals(listOf<AppBadge?>(null), renderer.badges)
    }

    @Test
    fun `badge that fails to load leaves its key free for the next focus`() {
        badges.refs[youtube] = BadgeRef("com.google.android.youtube.tv", 7L)
        badges.failLoad = true
        val pending = titled.quick(program()) as Outcome.Pending
        assertEquals("titled", titled.prepare(program(), ctx).note)
        assertTrue(store.keys.single().contains("|app:-|"))
        assertTrue(titled.quick(program()) is Outcome.Pending)
        // Still failing: the frame without the badge is reused, not rendered again.
        assertEquals("titled:L2:nobadge", titled.prepare(program(), ctx).note)
        assertEquals(1, renderer.titles.size)

        badges.failLoad = false
        val ready = titled.prepare(program(), ctx) as Outcome.Ready
        assertEquals("titled", ready.note)
        assertEquals(2, renderer.titles.size)
        assertNotNull(renderer.badges.last())
        assertTrue(pending.jobKey in store.keys)
        assertEquals(Outcome.Ready(ready.composite, "titled:L2"), titled.quick(program()))
    }

    @Test
    fun `card that lost focus is not rendered`() {
        current = false
        assertEquals(Outcome.Failed(Outcome.STALE), titled.prepare(program(), ctx))
        assertTrue(renderer.titles.isEmpty())
    }

    @Test
    fun `diagnostics carry no titles`() {
        titled.prepare(program(title = "Secret Video"), ctx)
        renderer.fail = IOException("x")
        titled.prepare(program(title = "Secret Other"), ctx)
        assertTrue(diagnostics.lines.isNotEmpty())
        assertTrue(diagnostics.lines.none { "Secret" in it })
    }

    @Test
    fun `colors parse as opaque ARGB`() {
        assertEquals(0xFF101216.toInt(), TitledCards.parseColor("#101216"))
        assertEquals(0xFF0D1B2A.toInt(), TitledCards.parseColor(" #0d1b2a "))
        assertEquals(0xFF123456.toInt(), TitledCards.parseColor("#80123456"))
        listOf("101216", "#12345", "#1234567", "#-12345", "#GGGGGG", "", "red").forEach { assertNull(it, TitledCards.parseColor(it)) }
    }

    // ───────────── fakes ─────────────

    private class FakeStore(override val dir: File) : CompositeStore {
        private val byKey = LinkedHashMap<String, File>()
        val keys: Set<String> get() = byKey.keys

        override fun find(key: String): File? = byKey[key]

        override fun publish(key: String, write: (File) -> Unit): File {
            val file = File(dir, "t${byKey.size}.jpg")
            write(file)
            byKey[key] = file
            return file
        }

        override fun markServed(file: File) = Unit
        override fun trim(maxBytes: Long) = Unit
        override fun sizeBytes() = 0L
        override fun clear() = Unit
    }

    private class FakeRenderer : WallpaperRenderer {
        override val version = 1
        var fail: Throwable? = null
        val titles = CopyOnWriteArrayList<String>()
        val backgrounds = CopyOnWriteArrayList<Int>()
        val badges = ArrayList<AppBadge?>()

        override fun render(backdrop: Bitmap?, logo: Bitmap?, info: DisplayInfo, settings: AppearanceSettings, out: File, badge: AppBadge?) =
            throw UnsupportedOperationException()

        override fun renderTitled(title: String, badge: AppBadge?, background: Int, settings: AppearanceSettings, out: File) {
            fail?.let { throw it }
            titles += title
            backgrounds += background
            badges += badge
            out.writeText("jpeg:$title")
        }
    }

    private class FakeBadges : AppBadges {
        val refs = HashMap<String, BadgeRef>()
        val packages = HashMap<String, BadgeRef>()
        val loaded = ArrayList<BadgeRef>()
        var lookups = 0
        var failLoad = false

        override fun forIconUri(iconUri: String?): BadgeRef? {
            lookups++
            return refs[iconUri]
        }

        override fun forPackage(packageName: String?): BadgeRef? {
            lookups++
            return packages[packageName]
        }

        // A loaded badge is recorded; its bitmap is allocated without running the (stub) constructor.
        override fun load(ref: BadgeRef): AppBadge? {
            if (failLoad) throw IllegalStateException("no badge")
            loaded += ref
            return AppBadge(stubBitmap, banner = true)
        }
    }

    private class FakeSettings : SettingsRepository {
        var titled = true
        var appearance = AppearanceSettings()
        override fun language() = DisplayLanguage.EN
        override fun appearance() = appearance
        override fun minVoteCount() = 10
        override fun cacheLimitBytes() = 200L * 1024 * 1024
        override fun neutralColor() = "#101216"
        override fun diagnosticsEnabled() = false
        override fun logTitlesInDiagnostics() = false
        override fun showTitleWhenUnrecognized() = titled
        override fun exportJson() = "{}"
        override fun importJson(json: String) = true
    }

    private class FakeDiagnostics : Diagnostics {
        val lines = CopyOnWriteArrayList<String>()
        override fun event(line: String) {
            lines += line
        }
        override fun lastError(error: TmdbError?) = Unit
        override fun recent(): List<String> = lines
    }
}
