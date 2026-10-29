package io.github.yozha.movieartdetails.core

import io.github.yozha.movieartdetails.model.Diagnostics
import io.github.yozha.movieartdetails.model.TmdbError
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tv.projectivy.plugin.wallpaperprovider.api.Event
import tv.projectivy.plugin.wallpaperprovider.api.Wallpaper
import tv.projectivy.plugin.wallpaperprovider.api.WallpaperType

/** Response table and deferred delivery (neutral reply, then broadcast), tested with fakes and without Android or network. */
class CoordinatorTest {

    @get:Rule val tmp = TemporaryFolder()

    private val resolver = FakeResolver()
    private val titled = FakeNeutral()
    private val host = FakeHost()
    private val manual = ManualExecutor()
    private val diagnostics = FakeDiagnostics()
    private var tint = false
    private var neutral = "#202020"
    private val pools = ArrayList<ExecutorService>()

    private lateinit var coordinator: Coordinator

    @Before
    fun setUp() {
        resolver.prepareFn = { card, _ -> Outcome.Ready(composite(card.title), "rendered") }
        titled.prepareFn = { card, _ -> Outcome.Ready(composite("titled-${card.title}"), "titled") }
        coordinator = coordinator(manual, tBlockMs = 30)
    }

    @After
    fun tearDown() {
        pools.forEach { it.shutdownNow() }
    }

    private fun coordinator(executor: Executor, tBlockMs: Long, neutralResolver: NeutralResolver = NeutralResolver.OFF) = Coordinator(
        resolver = resolver,
        host = host,
        executor = executor,
        diagnostics = diagnostics,
        neutralColor = { neutral },
        tintFromCard = { tint },
        titled = neutralResolver,
        tBlockMs = tBlockMs,
        later = { delayMs, task -> timers.addLast(delayMs to task) },
    )

    /** Delayed tasks the coordinator scheduled, run by hand. */
    private val timers = ArrayDeque<Pair<Long, () -> Unit>>()

    private fun runTimers() {
        while (timers.isNotEmpty()) timers.removeFirst().second()
    }

    private fun composite(title: String): Composite {
        val file = File(tmp.root, "$title.jpg").apply { writeText("jpeg") }
        return Composite(file, title, "https://www.themoviedb.org/movie/1")
    }

    private fun program(title: String?, type: Int? = 0, iconUri: String? = null) = Event.ProgramCardFocused(title = title, type = type, iconUri = iconUri)

    private fun List<Wallpaper>.single(): Wallpaper {
        assertEquals(1, size)
        return first()
    }

    private fun assertNeutral(result: List<Wallpaper>, color: String = neutral) {
        val w = result.single()
        assertEquals(WallpaperType.COLOR, w.type)
        assertEquals(color, w.uri)
    }

    private fun assertImage(result: List<Wallpaper>, title: String) {
        val w = result.single()
        assertEquals(WallpaperType.IMAGE, w.type)
        assertEquals("content://test/$title.jpg", w.uri)
        assertEquals(null, w.actionUri)
    }

    // ───────────── response table ─────────────

    @Test
    fun `null and unsubscribed events return empty list`() {
        assertTrue(coordinator.getWallpapers(null).isEmpty())
        assertTrue(coordinator.getWallpapers(Event.NowPlayingChanged()).isEmpty())
        assertTrue(coordinator.getWallpapers(Event.LauncherIdleModeChanged()).isEmpty())
    }

    @Test
    fun `app card returns neutral color, tinted only when enabled`() {
        val app = Event.CardFocused(darkColor = 0xFF123456.toInt(), title = "App", packageName = "x.y")
        assertNeutral(coordinator.getWallpapers(app))
        tint = true
        assertNeutral(coordinator.getWallpapers(app), "#123456")
        // Without darkColor: the regular neutral color.
        assertNeutral(coordinator.getWallpapers(Event.CardFocused(title = "App")))
    }

    @Test
    fun `broken neutral color setting falls back to default`() {
        neutral = "red"
        assertNeutral(coordinator.getWallpapers(Event.CardFocused()), Coordinator.DEFAULT_NEUTRAL)
    }

    @Test
    fun `cached composite is served immediately without a job`() {
        resolver.quickFn = { Outcome.Ready(composite(it.title), "L2") }
        assertImage(coordinator.getWallpapers(program("Avatar")), "Avatar")
        assertEquals(0, manual.size)
        assertEquals(1, host.served.size)
        assertEquals(0, host.notified)
    }

    @Test
    fun `rejected card returns neutral without a job`() {
        resolver.quickFn = { Outcome.Rejected("skip:youtube") }
        assertNeutral(coordinator.getWallpapers(program("4K HDR", type = 4)))
        assertEquals(0, manual.size)
        assertNeutral(coordinator.getWallpapers(Event.TimeElapsed))
    }

    @Test
    fun `card without title returns neutral`() {
        assertNeutral(coordinator.getWallpapers(program(null)))
        assertNeutral(coordinator.getWallpapers(program("   ")))
        assertEquals(0, resolver.quickCalls)
    }

    @Test
    fun `T_block expiry returns neutral, then one broadcast and TimeElapsed serves the result`() {
        assertNeutral(coordinator.getWallpapers(program("Avatar")))
        assertEquals(1, manual.size)
        assertEquals(0, host.notified)

        manual.runAll()
        assertEquals(1, host.notified)

        val first = coordinator.getWallpapers(Event.TimeElapsed)
        assertImage(first, "Avatar")
        // Content unchanged: same URI, permission granted again.
        assertEquals(first, coordinator.getWallpapers(Event.TimeElapsed))
        assertEquals(2, host.served.size)
        assertEquals(1, host.notified)
    }

    @Test
    fun `result ready within T_block is returned without broadcast`() {
        coordinator = coordinator(Executor { it.run() }, tBlockMs = 30)
        assertImage(coordinator.getWallpapers(program("Avatar")), "Avatar")
        assertEquals(0, host.notified)
    }

    @Test
    fun `background result within T_block on a real pool is returned without broadcast`() {
        val pool = Executors.newSingleThreadExecutor().also { pools += it }
        coordinator = coordinator(pool, tBlockMs = 5_000)
        resolver.prepareFn = { card, _ ->
            Thread.sleep(20)
            Outcome.Ready(composite(card.title), "rendered")
        }
        val started = System.nanoTime()
        assertImage(coordinator.getWallpapers(program("Avatar")), "Avatar")
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
        Thread.sleep(50)
        assertEquals(0, host.notified)
    }

    @Test
    fun `failure within T_block returns neutral`() {
        coordinator = coordinator(Executor { it.run() }, tBlockMs = 30)
        resolver.prepareFn = { _, _ -> Outcome.Rejected("low-confidence") }
        assertNeutral(coordinator.getWallpapers(program("Unknown")))
        assertEquals(0, host.notified)
    }

    // ───────────── generations ─────────────

    @Test
    fun `queued job of an old card is skipped and not delivered`() {
        coordinator.getWallpapers(program("A"))
        coordinator.getWallpapers(program("B"))
        manual.runAll()
        assertEquals(listOf("B"), resolver.prepared)
        assertEquals(1, host.notified)
        assertImage(coordinator.getWallpapers(Event.TimeElapsed), "B")
    }

    @Test
    fun `slow result of an old card does not override the newer card`() {
        resolver.prepareFn = { card, _ ->
            // While A is being prepared, focus moves to B (a rejected card).
            resolver.quickFn = { Outcome.Rejected("skip") }
            assertNeutral(coordinator.getWallpapers(program("B")))
            Outcome.Ready(composite(card.title), "rendered")
        }
        coordinator.getWallpapers(program("A"))
        manual.runAll()
        assertEquals(0, host.notified)
        assertNeutral(coordinator.getWallpapers(Event.TimeElapsed))
    }

    @Test
    fun `app card focus starts a new generation`() {
        coordinator.getWallpapers(program("A"))
        assertNeutral(coordinator.getWallpapers(Event.CardFocused(title = "App")))
        manual.runAll()
        assertEquals(0, host.notified)
        assertTrue(resolver.prepared.isEmpty())
        assertNeutral(coordinator.getWallpapers(Event.TimeElapsed))
    }

    @Test
    fun `returning to the same card joins the running job`() {
        coordinator.getWallpapers(program("A"))
        coordinator.getWallpapers(program("B"))
        coordinator.getWallpapers(program("A"))
        assertEquals(2, manual.size)
        manual.runAll()
        assertEquals(listOf("A"), resolver.prepared)
        assertEquals(1, host.notified)
        assertImage(coordinator.getWallpapers(Event.TimeElapsed), "A")
    }

    @Test
    fun `new event releases the waiting call immediately`() {
        coordinator = coordinator(manual, tBlockMs = 10_000)
        var result: List<Wallpaper> = emptyList()
        var elapsedMs = 0L
        val waiter = Thread {
            val started = System.nanoTime()
            result = coordinator.getWallpapers(program("A"))
            elapsedMs = (System.nanoTime() - started) / 1_000_000
        }
        waiter.start()
        while (manual.size == 0) Thread.sleep(5)
        Thread.sleep(50)
        resolver.quickFn = { Outcome.Rejected("skip") }
        coordinator.getWallpapers(program("B"))
        waiter.join(3_000)
        assertNeutral(result)
        assertTrue("waited $elapsedMs ms", elapsedMs < 3_000)
    }

    // ───────────── broadcast ─────────────

    @Test
    fun `broadcast is sent at most once per generation`() {
        coordinator.getWallpapers(program("A"))
        manual.runAll()
        assertEquals(1, host.notified)
        coordinator.getWallpapers(Event.TimeElapsed)

        // The composite is gone (cache cleared): TimeElapsed restarts preparation, but there is no second broadcast.
        File(tmp.root, "A.jpg").delete()
        assertNeutral(coordinator.getWallpapers(Event.TimeElapsed))
        assertEquals(1, manual.size)
        manual.runAll()
        assertEquals(1, host.notified)
        assertImage(coordinator.getWallpapers(Event.TimeElapsed), "A")

        // A new generation of the same card may get a broadcast again.
        File(tmp.root, "A.jpg").delete()
        coordinator.getWallpapers(program("A"))
        manual.runAll()
        assertEquals(2, host.notified)
    }

    @Test
    fun `no broadcast while the screen is off`() {
        host.screenOn = false
        coordinator.getWallpapers(program("A"))
        manual.runAll()
        assertEquals(0, host.notified)
        assertImage(coordinator.getWallpapers(Event.TimeElapsed), "A")
    }

    @Test
    fun `failed preparation is retried on TimeElapsed`() {
        resolver.prepareFn = { _, _ -> Outcome.Failed("tmdb:NETWORK") }
        coordinator.getWallpapers(program("A"))
        manual.runAll()
        assertEquals(0, host.notified)
        resolver.prepareFn = { card, _ -> Outcome.Ready(composite(card.title), "rendered") }
        assertNeutral(coordinator.getWallpapers(Event.TimeElapsed))
        manual.runAll()
        assertEquals(1, host.notified)
    }

    @Test
    fun `stale result of a card we returned to is re-queued, not saved`() {
        var first = true
        resolver.prepareFn = { card, _ ->
            if (first && card.title == "A") {
                first = false
                // While job A was running, focus moved to B and back to A: job A is still registered and the new generation waits for it.
                assertNeutral(coordinator.getWallpapers(program("B")))
                assertNeutral(coordinator.getWallpapers(program("A")))
                Outcome.Failed(Outcome.STALE)
            } else {
                Outcome.Ready(composite(card.title), "rendered")
            }
        }
        coordinator.getWallpapers(program("A"))
        manual.runAll()
        // B was stale by the time it ran and was skipped; A was prepared again and delivered via broadcast.
        assertEquals(listOf("A", "A"), resolver.prepared)
        assertEquals(1, host.notified)
        assertImage(coordinator.getWallpapers(Event.TimeElapsed), "A")
    }

    @Test
    fun `stale restarts are bounded`() {
        resolver.prepareFn = { _, _ -> Outcome.Failed(Outcome.STALE) }
        coordinator.getWallpapers(program("A"))
        manual.runAll()
        // The first run plus at most two restarts; then the outcome is stored and waits for TimeElapsed.
        assertEquals(3, resolver.prepared.size)
        assertEquals(0, host.notified)
    }

    // ───────────── errors ─────────────

    @Test
    fun `exception in quick path returns neutral`() {
        resolver.quickFn = { throw IllegalStateException("boom") }
        assertNeutral(coordinator.getWallpapers(program("A")))
    }

    @Test
    fun `out of memory in host bridge returns neutral`() {
        resolver.quickFn = { Outcome.Ready(composite(it.title), "L2") }
        host.fail = OutOfMemoryError("bitmap")
        assertNeutral(coordinator.getWallpapers(program("A")))
    }

    @Test
    fun `exception in background preparation is contained`() {
        resolver.prepareFn = { _, _ -> throw RuntimeException("boom") }
        assertNeutral(coordinator.getWallpapers(program("A")))
        manual.runAll()
        assertEquals(0, host.notified)
        assertNeutral(coordinator.getWallpapers(Event.TimeElapsed))
    }

    @Test
    fun `diagnostics carry no titles by default`() {
        coordinator.getWallpapers(program("Secret Title"))
        assertTrue(diagnostics.lines.isNotEmpty())
        assertTrue(diagnostics.lines.none { "Secret" in it })
        assertNotEquals(0, diagnostics.lines.size)
    }

    // ───────────── titled neutral frames ─────────────

    @Test
    fun `rejected card is shown with its own title when rendered within T_block`() {
        coordinator = coordinator(Executor { it.run() }, tBlockMs = 1_000, neutralResolver = titled)
        resolver.quickFn = { Outcome.Rejected("skip:youtube") }
        val icon = "https://i.ytimg.com/vi/x/hq.jpg"
        assertImage(coordinator.getWallpapers(program(" Sample Video ", type = 4, iconUri = icon)), "titled-Sample Video")
        assertEquals(NeutralCard("Sample Video", iconUri = icon, color = neutral), titled.prepared.single())
        assertTrue(resolver.prepared.isEmpty())
        assertEquals(0, host.notified)
        assertImage(coordinator.getWallpapers(Event.TimeElapsed), "titled-Sample Video")
    }

    @Test
    fun `cached titled frame is served at once without a job`() {
        coordinator = coordinator(manual, tBlockMs = 30, neutralResolver = titled)
        resolver.quickFn = { Outcome.Rejected("negative") }
        titled.quickFn = { Outcome.Ready(composite("titled-${it.title}"), "titled:L2") }
        assertImage(coordinator.getWallpapers(program("Unknown")), "titled-Unknown")
        assertEquals(0, manual.size)
        assertEquals(0, host.notified)
    }

    @Test
    fun `slow titled render returns neutral, then one broadcast`() {
        coordinator = coordinator(manual, tBlockMs = 30, neutralResolver = titled)
        resolver.quickFn = { Outcome.Rejected("manual-off") }
        assertNeutral(coordinator.getWallpapers(program("Unknown")))
        assertEquals(1, manual.size)
        manual.runAll()
        assertEquals(1, host.notified)
        assertImage(coordinator.getWallpapers(Event.TimeElapsed), "titled-Unknown")
        assertEquals(1, host.notified)
    }

    @Test
    fun `broadcast the host doesn't act on is repeated at most twice`() {
        coordinator = coordinator(manual, tBlockMs = 30, neutralResolver = titled)
        resolver.quickFn = { Outcome.Rejected("skip:youtube") }
        coordinator.getWallpapers(program("Video"))
        manual.runAll()
        assertEquals(1, host.notified)
        assertEquals(Coordinator.REBROADCAST_DELAYS_MS[0], timers.first().first)
        timers.removeFirst().second()
        assertEquals(2, host.notified)
        runTimers()
        assertEquals(3, host.notified)
        assertTrue(timers.isEmpty())
        assertEquals(2, diagnostics.lines.count { "rebroadcast" in it })
        assertImage(coordinator.getWallpapers(Event.TimeElapsed), "titled-Video")
    }

    @Test
    fun `broadcast is not repeated once TimeElapsed fetched the frame`() {
        coordinator = coordinator(manual, tBlockMs = 30, neutralResolver = titled)
        resolver.quickFn = { Outcome.Rejected("skip:youtube") }
        coordinator.getWallpapers(program("Video"))
        manual.runAll()
        assertImage(coordinator.getWallpapers(Event.TimeElapsed), "titled-Video")
        runTimers()
        assertEquals(1, host.notified)
    }

    @Test
    fun `broadcast is not repeated after focus moved on`() {
        coordinator = coordinator(manual, tBlockMs = 30, neutralResolver = titled)
        resolver.quickFn = { Outcome.Rejected("skip:youtube") }
        coordinator.getWallpapers(program("Video"))
        manual.runAll()
        titled.quickFn = { Outcome.Rejected(TitledCards.OFF) }
        coordinator.getWallpapers(program("Next"))
        runTimers()
        assertEquals(1, host.notified)
    }

    @Test
    fun `finished jobs log their kind, outcome and timings without keys`() {
        coordinator = coordinator(manual, tBlockMs = 30, neutralResolver = titled)
        resolver.quickFn = { Outcome.Rejected("manual-off") }
        coordinator.getWallpapers(program("Secret Video"))
        manual.runAll()
        val line = diagnostics.lines.single { " job " in it }
        assertTrue(line, Regex("""g=\d+ job titled → \S+ queued=\d+ms run=\d+ms""").containsMatchIn(line))
        assertTrue(line, "Secret" !in line)

        resolver.quickFn = { Outcome.Pending("key:${it.title}") }
        resolver.prepareFn = { _, _ -> Outcome.Failed("network") }
        coordinator.getWallpapers(program("Other"))
        coordinator.getWallpapers(program("Third"))
        manual.runAll()
        assertTrue(diagnostics.lines.any { Regex("""dropped job match → stale not run""").containsMatchIn(it) })
    }

    @Test
    fun `preparation that ends rejected switches the waiting call to the titled frame`() {
        coordinator = coordinator(Executor { it.run() }, tBlockMs = 1_000, neutralResolver = titled)
        resolver.prepareFn = { _, _ -> Outcome.Rejected("low-confidence") }
        assertImage(coordinator.getWallpapers(program("Unknown")), "titled-Unknown")
        assertEquals(listOf("Unknown"), resolver.prepared)
        assertEquals("Unknown", titled.prepared.single().title)
        assertEquals(0, host.notified)
    }

    @Test
    fun `preparation that ends rejected after T_block delivers the titled frame by broadcast`() {
        coordinator = coordinator(manual, tBlockMs = 30, neutralResolver = titled)
        resolver.prepareFn = { _, _ -> Outcome.Rejected("low-confidence") }
        // Still being matched: the plain color, as before.
        assertNeutral(coordinator.getWallpapers(program("Unknown")))
        assertTrue(titled.quickCards.isEmpty())
        manual.runAll()
        assertEquals(listOf("Unknown"), resolver.prepared)
        assertEquals(1, titled.prepared.size)
        assertEquals(1, host.notified)
        assertImage(coordinator.getWallpapers(Event.TimeElapsed), "titled-Unknown")
    }

    @Test
    fun `transient failure stays a plain color`() {
        coordinator = coordinator(Executor { it.run() }, tBlockMs = 1_000, neutralResolver = titled)
        resolver.prepareFn = { _, _ -> Outcome.Failed("tmdb:NETWORK") }
        assertNeutral(coordinator.getWallpapers(program("Avatar")))
        assertTrue(titled.quickCards.isEmpty())
    }

    @Test
    fun `titled frames turned off keep the plain color and the matching reason`() {
        titled.quickFn = { Outcome.Rejected(TitledCards.OFF) }
        coordinator = coordinator(manual, tBlockMs = 30, neutralResolver = titled)
        resolver.prepareFn = { _, _ -> Outcome.Rejected("low-confidence") }
        assertNeutral(coordinator.getWallpapers(program("Unknown")))
        manual.runAll()
        assertEquals(0, manual.size)
        assertTrue(titled.prepared.isEmpty())
        assertEquals(0, host.notified)
        assertNeutral(coordinator.getWallpapers(Event.TimeElapsed))

        resolver.quickFn = { Outcome.Rejected("skip:youtube") }
        assertNeutral(coordinator.getWallpapers(program("Video", type = 4)))
        assertTrue(diagnostics.lines.last().contains("skip:youtube"))
    }

    @Test
    fun `app card is shown with its title and package on the tinted background`() {
        coordinator = coordinator(Executor { it.run() }, tBlockMs = 1_000, neutralResolver = titled)
        tint = true
        val app = Event.CardFocused(darkColor = 0xFF123456.toInt(), title = " Sample App ", packageName = "com.example.app")
        assertImage(coordinator.getWallpapers(app), "titled-Sample App")
        assertEquals(NeutralCard("Sample App", packageName = "com.example.app", color = "#123456"), titled.prepared.single())
        assertTrue(resolver.prepared.isEmpty())
        // Projectivy's own shortcuts have no package: the title alone.
        assertImage(coordinator.getWallpapers(Event.CardFocused(title = "Shortcut")), "titled-Shortcut")
        assertEquals(NeutralCard("Shortcut", color = neutral), titled.prepared.last())
    }

    @Test
    fun `app card without a title keeps the plain tinted color`() {
        coordinator = coordinator(Executor { it.run() }, tBlockMs = 1_000, neutralResolver = titled)
        tint = true
        assertNeutral(coordinator.getWallpapers(Event.CardFocused(darkColor = 0xFF123456.toInt(), title = "  ")), "#123456")
        assertTrue(titled.quickCards.isEmpty())
        assertNeutral(coordinator.getWallpapers(Event.TimeElapsed), "#123456")
    }

    @Test
    fun `program card without a title gets no titled frame`() {
        coordinator = coordinator(Executor { it.run() }, tBlockMs = 1_000, neutralResolver = titled)
        assertNeutral(coordinator.getWallpapers(program("  ")))
        assertTrue(titled.quickCards.isEmpty())
    }

    @Test
    fun `TimeElapsed keeps the app card tint`() {
        tint = true
        assertNeutral(coordinator.getWallpapers(Event.CardFocused(darkColor = 0xFF123456.toInt(), title = "App")), "#123456")
        assertNeutral(coordinator.getWallpapers(Event.TimeElapsed), "#123456")
    }

    @Test
    fun `failed titled render is a plain color and is retried on TimeElapsed`() {
        coordinator = coordinator(manual, tBlockMs = 30, neutralResolver = titled)
        titled.prepareFn = { _, _ -> Outcome.Failed("titled:error") }
        assertNeutral(coordinator.getWallpapers(Event.CardFocused(title = "App", packageName = "com.example.app")))
        manual.runAll()
        assertEquals(0, host.notified)

        titled.prepareFn = { card, _ -> Outcome.Ready(composite("titled-${card.title}"), "titled") }
        assertNeutral(coordinator.getWallpapers(Event.TimeElapsed))
        assertEquals(1, manual.size)
        manual.runAll()
        assertEquals(2, titled.prepared.size)
        assertTrue(resolver.prepared.isEmpty())
        assertEquals(1, host.notified)
        assertImage(coordinator.getWallpapers(Event.TimeElapsed), "titled-App")
    }

    @Test
    fun `titled quick path that failed before its job is retried on TimeElapsed`() {
        coordinator = coordinator(manual, tBlockMs = 30, neutralResolver = titled)
        var fail = true
        titled.quickFn = { if (fail) throw IllegalStateException("boom") else Outcome.Pending("titled|${it.title}|${it.color}", "titled:pending") }
        assertNeutral(coordinator.getWallpapers(Event.CardFocused(title = "App", packageName = "com.example.app")))
        assertEquals(0, manual.size)

        fail = false
        assertNeutral(coordinator.getWallpapers(Event.TimeElapsed))
        assertEquals(2, titled.quickCards.size)
        assertEquals(1, manual.size)
        manual.runAll()
        assertEquals(1, host.notified)
        assertImage(coordinator.getWallpapers(Event.TimeElapsed), "titled-App")
    }

    @Test
    fun `unrecognized card whose titled quick path failed gets a cached frame on TimeElapsed`() {
        coordinator = coordinator(manual, tBlockMs = 30, neutralResolver = titled)
        resolver.quickFn = { Outcome.Rejected("skip:youtube") }
        titled.quickFn = { throw IllegalStateException("boom") }
        assertNeutral(coordinator.getWallpapers(program("Video", type = 4)))

        titled.quickFn = { Outcome.Ready(composite("titled-${it.title}"), "titled:L2") }
        assertImage(coordinator.getWallpapers(Event.TimeElapsed), "titled-Video")
        assertEquals(0, manual.size)
        assertEquals(0, host.notified)
        assertImage(coordinator.getWallpapers(Event.TimeElapsed), "titled-Video")
        assertEquals(2, titled.quickCards.size)
    }

    @Test
    fun `retries of a failing titled quick path are bounded`() {
        coordinator = coordinator(manual, tBlockMs = 30, neutralResolver = titled)
        titled.quickFn = { throw IllegalStateException("boom") }
        assertNeutral(coordinator.getWallpapers(Event.CardFocused(title = "App")))
        repeat(6) { assertNeutral(coordinator.getWallpapers(Event.TimeElapsed)) }
        // The first try plus three retries.
        assertEquals(4, titled.quickCards.size)
        assertTrue(diagnostics.lines.last().contains("te:failed"))
        assertEquals(0, manual.size)
    }

    @Test
    fun `titled render of a card that lost focus is skipped`() {
        coordinator = coordinator(manual, tBlockMs = 30, neutralResolver = titled)
        coordinator.getWallpapers(Event.CardFocused(title = "App A"))
        resolver.quickFn = { Outcome.Ready(composite(it.title), "L2") }
        assertImage(coordinator.getWallpapers(program("Avatar")), "Avatar")
        manual.runAll()
        assertTrue(titled.prepared.isEmpty())
        assertEquals(0, host.notified)
        assertImage(coordinator.getWallpapers(Event.TimeElapsed), "Avatar")
    }

    @Test
    fun `exception in the titled quick path returns neutral`() {
        coordinator = coordinator(manual, tBlockMs = 30, neutralResolver = titled)
        titled.quickFn = { throw IllegalStateException("boom") }
        tint = true
        assertNeutral(coordinator.getWallpapers(Event.CardFocused(darkColor = 0xFF123456.toInt(), title = "App")), "#123456")
        resolver.quickFn = { Outcome.Rejected("skip") }
        assertNeutral(coordinator.getWallpapers(program("Video")))
    }

    @Test
    fun `diagnostics carry no titles of titled cards`() {
        coordinator = coordinator(Executor { it.run() }, tBlockMs = 1_000, neutralResolver = titled)
        resolver.quickFn = { Outcome.Rejected("skip:youtube") }
        coordinator.getWallpapers(program("Secret Video", type = 4))
        coordinator.getWallpapers(Event.CardFocused(title = "Secret App"))
        assertTrue(diagnostics.lines.isNotEmpty())
        assertTrue(diagnostics.lines.none { "Secret" in it })
    }

    // ───────────── fakes ─────────────

    private class FakeNeutral : NeutralResolver {
        var quickFn: (NeutralCard) -> Outcome = { Outcome.Pending("titled|${it.title}|${it.color}", "titled:pending") }
        var prepareFn: (NeutralCard, JobContext) -> Outcome = { _, _ -> Outcome.Failed("unset") }
        val quickCards = CopyOnWriteArrayList<NeutralCard>()
        val prepared = CopyOnWriteArrayList<NeutralCard>()

        override fun quick(card: NeutralCard): Outcome {
            quickCards += card
            return quickFn(card)
        }

        override fun prepare(card: NeutralCard, ctx: JobContext): Outcome {
            prepared += card
            return prepareFn(card, ctx)
        }
    }

    private class FakeResolver : CardResolver {
        var quickFn: (FocusedCard) -> Outcome = { Outcome.Pending("key:${it.title}") }
        var prepareFn: (FocusedCard, JobContext) -> Outcome = { _, _ -> Outcome.Failed("unset") }
        val prepared = CopyOnWriteArrayList<String>()
        @Volatile var quickCalls = 0

        override fun quick(card: FocusedCard): Outcome {
            quickCalls++
            return quickFn(card)
        }

        override fun prepare(card: FocusedCard, ctx: JobContext): Outcome {
            prepared += card.title
            return prepareFn(card, ctx)
        }
    }

    private class FakeHost : HostBridge {
        val served = CopyOnWriteArrayList<File>()
        @Volatile var notified = 0
        @Volatile var screenOn = true
        var fail: Throwable? = null

        override fun wallpaper(composite: Composite): Wallpaper {
            fail?.let { throw it }
            served += composite.file
            return Wallpaper(
                "content://test/${composite.file.name}", WallpaperType.IMAGE,
                title = composite.title, source = composite.sourceUrl, author = "TMDB",
            )
        }

        override fun canNotify() = screenOn

        override fun notifyUpdated() {
            notified++
        }
    }

    /** Queues jobs and runs them when the test says so. */
    private class ManualExecutor : Executor {
        private val queue = ArrayDeque<Runnable>()
        val size: Int get() = synchronized(queue) { queue.size }

        override fun execute(command: Runnable) {
            synchronized(queue) { queue.addLast(command) }
        }

        fun runAll() {
            while (true) {
                val next = synchronized(queue) { queue.removeFirstOrNull() } ?: return
                next.run()
            }
        }
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
