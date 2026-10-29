package io.github.yozha.movieartdetails.render

import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.BlockAnchor
import io.github.yozha.movieartdetails.model.DisplayInfo
import io.github.yozha.movieartdetails.model.DisplayLanguage
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.TextPreset
import io.github.yozha.movieartdetails.settings.AppearanceRanges
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/** Monospace estimate: each character is [em] of the font size, greedy word wrap (like StaticLayout without hyphenation). */
private class FakeMeasurer(private val em: Float = 0.56f) : TextMeasurer {
    override fun width(text: String, style: TextStyle): Float = text.length * style.size * em

    override fun lineCount(text: String, style: TextStyle, maxWidth: Float): Int {
        var lines = 0
        var current = ""
        for (word in text.split(' ').filter { it.isNotEmpty() }) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (current.isEmpty() || width(candidate, style) <= maxWidth) {
                current = candidate
            } else {
                lines += ceil(width(current, style) / maxWidth).toInt().coerceAtLeast(1)
                current = word
            }
        }
        if (current.isNotEmpty()) lines += ceil(width(current, style) / maxWidth).toInt().coerceAtLeast(1)
        return lines
    }

    override fun ascent(style: TextStyle): Float = ceil(style.size * 0.93f)

    override fun descent(style: TextStyle): Float = ceil(style.size * 0.27f)
}

class LayoutEngineTest {

    private val measurer = FakeMeasurer()

    /**
     * Filler overview: Russian and Ukrainian titles of well-known international films and series joined with ". "
     * (368 characters). [FakeMeasurer] only counts characters, so the line counts below depend on the text length
     * and word breaks alone.
     */
    private val longOverview = listOf(
        "Властелин колец", "Історія іграшок", "Гладиатор", "Володар перснів", "Чёрный лебедь", "Дюна", "Интерстеллар",
        "Ґодзілла", "Зоряні війни", "Аліса в Країні Чудес", "Титаник", "Гра престолів", "Начало", "Матрица",
        "Людина-павук: Додому шляху немає", "Звёздные войны", "Игра престолов", "Ведмідь", "Форрест Гамп", "Король Лев",
        "Назад в будущее", "Темний лицар", "Аватар", "Крёстный отец", "Зелена миля", "Список Шиндлера",
    ).joinToString(". ", postfix = ".")

    private fun content(
        title: String = "Аватар",
        logoAspect: Float? = null,
        metaParts: List<String> = listOf("2009", "Фильм", "2 ч 42 мин"),
        rating: String? = "TMDB 7,6/10",
        genres: List<String> = listOf("Фантастика", "Боевик", "Приключения"),
        overview: String? = BlockContent.collapse(longOverview),
        logoPixelHeight: Float? = null,
        badgeAspect: Float? = null,
    ) = BlockContent(title, logoAspect, metaParts, rating, genres, overview, logoPixelHeight, badgeAspect)

    /** Every drawn element (with shadow padding) stays above the limit and left of the clock zone. */
    private fun assertInside(layout: BlockLayout) {
        val f = layout.frame
        val limit = f.limit - LayoutEngine.SHADOW_PAD
        val clock = LayoutEngine.CLOCK_X * f.canvasW - LayoutEngine.SHADOW_PAD
        assertTrue("fits", layout.fits)
        layout.header?.let {
            assertTrue("header bottom ${it.bottom} > $limit", it.bottom <= limit)
            assertTrue("header right", it.left + it.width <= clock)
        }
        layout.meta?.let { meta ->
            assertTrue("meta bottom", meta.bottom <= limit)
            listOfNotNull(meta.main, meta.rating).forEach { assertTrue("meta right ${it.right} > $clock", it.right <= clock) }
            meta.badge?.let {
                assertTrue("badge bottom", it.bottom <= limit)
                assertTrue("badge right", it.right <= clock)
                assertTrue("badge inside the meta line", it.top >= meta.top - 0.01f && it.bottom <= meta.bottom + 0.01f)
            }
        }
        layout.genres?.let {
            assertTrue("genres bottom", it.bottom <= limit)
            assertTrue("genres right", it.right <= clock)
        }
        layout.overview?.let {
            assertTrue("overview bottom ${it.bottom} > $limit", it.bottom <= limit)
            assertTrue("overview right", it.left + it.width <= clock)
        }
        assertTrue(layout.bottom + LayoutEngine.SHADOW_PAD <= f.limit || layout.isEmpty)
    }

    /** Shrink steps run strictly in order: overview, header, genres, font scale, emergency, trim. */
    private fun assertOrdered(steps: List<String>) {
        fun rank(step: String) = when {
            step.startsWith("overview=") -> 1
            step.startsWith("header=") -> 2
            step.startsWith("genres=") -> 3
            step.startsWith("scale=") -> 4
            step.startsWith("emergency:") -> 5
            step.startsWith("trim:") -> 6
            else -> error("unknown step $step")
        }
        val ranks = steps.map(::rank)
        assertEquals("order of $steps", ranks.sorted(), ranks)
    }

    /** By default the block targets 49%: MEDIUM with a full logo gives up one overview line. */
    @Test
    fun mediumWithLogoAtDefaultTargetDropsOneOverviewLine() {
        val layout = LayoutEngine.layout(content(logoAspect = 4f), AppearanceSettings(), measurer)
        assertEquals(listOf("overview=4"), layout.steps)
        assertEquals(4, layout.overview!!.lines)
        assertTrue(layout.bottom <= 0.49f * 1080)
    }

    @Test
    fun mediumWithLogoFitsWithoutShrinkingAtHardLimit() {
        val layout = LayoutEngine.layout(content(logoAspect = 4f), AppearanceSettings(rowGapPct = 0.03f), measurer)
        assertEquals(emptyList<String>(), layout.steps)
        val logo = layout.header as LogoBox
        assertEquals(130f, logo.height, 0.01f)
        assertEquals(520f, logo.width, 0.01f)
        assertEquals(5, layout.overview!!.lines)
        assertEquals(38, layout.overview.pitch)
        assertEquals(28f, layout.overview.style.size)
        assertEquals(32f, layout.meta!!.main!!.style.size)
        assertEquals(28f, layout.genres!!.style.size)
        assertInside(layout)
    }

    @Test
    fun worstCaseLargeNoLogoLongCyrillicTitleFiveLines() {
        val title = "Володар перснів. Хранителі персня. Історія іграшок. Чёрный лебедь. Интерстеллар. Титаник."
        val settings = AppearanceSettings(textPreset = TextPreset.LARGE, overviewLines = 5)
        val layout = LayoutEngine.layout(content(title = title), settings, measurer)

        assertInside(layout)
        val header = layout.header as TitleBox
        assertTrue(header.lines in 1..LayoutEngine.MAX_TITLE_LINES)
        assertTrue(header.style.size >= LayoutEngine.sizes(TextPreset.LARGE).titleMin)
        // LARGE guarantees at least 3 overview lines; the font size is never reduced.
        assertTrue(layout.overview!!.lines >= 3)
        assertOrdered(layout.steps)
        assertTrue(layout.steps.isNotEmpty())
        assertTrue(layout.steps.none { it.startsWith("scale=") || it.startsWith("emergency:") || it.startsWith("trim:") })
        assertEquals(34f, layout.overview.style.size)
        assertNotNull(layout.meta?.rating)
    }

    @Test
    fun shrinkOrderOverviewThenHeaderBeforeGenresAndFont() {
        val settings = AppearanceSettings(rowGapPct = 0.15f)
        val layout = LayoutEngine.layout(content(logoAspect = 3f), settings, measurer)
        assertInside(layout)
        assertOrdered(layout.steps)
        assertEquals(listOf("overview=4", "overview=3"), layout.steps.filter { it.startsWith("overview=") })
        assertTrue(layout.steps.any { it.startsWith("header=") })
        assertEquals(3, layout.overview!!.lines)
        // The font size is untouched while the earlier steps are enough.
        assertTrue(layout.steps.none { it.startsWith("scale=") })
        assertEquals(28f, layout.overview.style.size)
    }

    @Test
    fun deepShrinkReachesFontOnlyAfterGenres() {
        val settings = AppearanceSettings(rowGapPct = 0.22f)
        val layout = LayoutEngine.layout(content(logoAspect = 3f), settings, measurer)
        assertInside(layout)
        assertOrdered(layout.steps)
        assertTrue(layout.steps.contains("header=80"))
        assertTrue(layout.steps.contains("genres=merged"))
        assertNull(layout.genres)
        assertEquals(80f, layout.header!!.height, 0.01f)
        // The rating is never dropped at any step.
        assertEquals("TMDB 7,6/10", layout.meta!!.rating!!.text)
    }

    @Test
    fun genresMergeIntoMetaWhenRoomOnTheLine() {
        val settings = AppearanceSettings(rowGapPct = 0.21f, blockWidthPct = 0.6f)
        val layout = LayoutEngine.layout(content(logoAspect = 3f, metaParts = listOf("2009"), genres = listOf("Драма", "Боевик")), settings, measurer)
        assertInside(layout)
        assertTrue(layout.steps.contains("genres=merged"))
        assertNull(layout.genres)
        assertEquals("2009 · Драма · Боевик", layout.meta!!.main!!.text)
    }

    @Test
    fun impossibleLimitTrimsFromBottomAndNeverOverflows() {
        val settings = AppearanceSettings(topPct = 0.07f, rowGapPct = 0.40f)
        val layout = LayoutEngine.layout(content(logoAspect = 3f), settings, measurer)
        assertInside(layout)
        assertOrdered(layout.steps)
        assertNull(layout.overview)
        assertTrue(layout.steps.any { it.startsWith("trim:") })
    }

    @Test
    fun everyPresetAndLineCountStaysAboveLimit() {
        val titles = listOf("Дюна", "Звёздные войны. Ґодзілла. Аліса в Країні Чудес. Темний лицар. Людина-павук: Додому шляху немає.")
        for (preset in TextPreset.entries) for (lines in AppearanceRanges.OVERVIEW_LINES) for (title in titles) for (logo in listOf(null, 1f, 4f, 9f)) {
            val settings = AppearanceSettings(textPreset = preset, overviewLines = lines)
            val layout = LayoutEngine.layout(content(title = title, logoAspect = logo), settings, measurer)
            assertInside(layout)
            assertOrdered(layout.steps)
            assertTrue("$preset/$lines/$logo", layout.steps.none { it.startsWith("emergency:") || it.startsWith("trim:") })
            assertTrue(layout.overview!!.lines >= 3)
        }
    }

    @Test
    fun missingFieldsLeaveNoDanglingSeparators() {
        val onlyRating = LayoutEngine.layout(content(metaParts = emptyList()), AppearanceSettings(), measurer)
        assertNull(onlyRating.meta!!.main)
        assertEquals(onlyRating.frame.left, onlyRating.meta.rating!!.left, 0.01f)

        val onlyYear = LayoutEngine.layout(content(metaParts = listOf("2009"), rating = null, genres = emptyList()), AppearanceSettings(), measurer)
        assertEquals("2009", onlyYear.meta!!.main!!.text)
        assertNull(onlyYear.meta.rating)
        assertNull(onlyYear.genres)

        val nothing = LayoutEngine.layout(content(metaParts = emptyList(), rating = null, genres = emptyList(), overview = null), AppearanceSettings(), measurer)
        assertNull(nothing.meta)
        assertNull(nothing.overview)
        assertNotNull(nothing.header)

        val all = LayoutEngine.layout(content(), AppearanceSettings(), measurer)
        assertEquals("2009 · Фильм · 2 ч 42 мин", all.meta!!.main!!.text)
        listOfNotNull(all.meta.main.text, all.genres?.text).forEach {
            assertFalse(it.startsWith("·") || it.endsWith("·") || it.contains("·  ·"))
        }
    }

    @Test
    fun fromDisplayInfoHonorsSwitchesAndCollapsesNewlines() {
        val info = DisplayInfo(
            mediaType = MediaType.MOVIE, tmdbId = 19995, language = DisplayLanguage.RU, title = "  Аватар \n",
            yearText = "2009", typeText = "Фильм", durationText = "2 ч 42 мин", genres = listOf("Фантастика", " ", "Боевик", "Драма", "Приключения"),
            ratingText = "TMDB 7,6/10", overview = "First line.\n\nSecond\tline.", logoPath = "/l.png", backdropPath = "/b.jpg", fingerprint = "x",
        )
        val c = BlockContent.from(info, AppearanceSettings(showType = false, showDuration = false), logoAspect = 2f)
        assertEquals("Аватар", c.title)
        assertEquals(listOf("2009"), c.metaParts)
        assertEquals(listOf("Фантастика", "Боевик", "Драма"), c.genres)
        assertEquals("First line. Second line.", c.overview)
        assertEquals(2f, c.logoAspect)
        assertNull(c.logoPixelHeight)
        assertEquals(150f, BlockContent.from(info, AppearanceSettings(), 2f, 150f).logoPixelHeight)
        assertNull("no logo, no cap", BlockContent.from(info, AppearanceSettings(), null, 150f).logoPixelHeight)

        val hidden = BlockContent.from(info, AppearanceSettings(showLogo = false, showRating = false, showOverview = false, showGenres = false, showYear = false), 2f, 150f)
        assertNull(hidden.logoAspect)
        assertNull(hidden.logoPixelHeight)
        assertNull(hidden.rating)
        assertNull(hidden.overview)
        assertEquals(listOf("Фильм"), hidden.metaParts)
        assertTrue(hidden.genres.isEmpty())
    }

    @Test
    fun genresDroppedRightToLeftOnOverflow() {
        val settings = AppearanceSettings(blockWidthPct = 0.25f)
        val layout = LayoutEngine.layout(content(genres = listOf("Фантастика", "Боевик", "Приключения")), settings, measurer)
        assertEquals("Фантастика · Боевик", layout.genres!!.text)
        assertInside(layout)
    }

    @Test
    fun frameClampsToClockZoneAndHardBottom() {
        val f = LayoutEngine.frame(AppearanceSettings(leftPct = 0.1f, blockWidthPct = 0.9f, rowGapPct = -0.25f))
        assertEquals(0.52f * 1080, f.limit, 0.01f)
        assertTrue(f.right <= 0.74f * 1920 - LayoutEngine.SHADOW_PAD)
        val layout = LayoutEngine.layout(content(), AppearanceSettings(leftPct = 0.1f, blockWidthPct = 0.9f, rowGapPct = -0.25f), measurer)
        assertInside(layout)
    }

    /** The bottom limit follows the card row and always keeps the hard 3% gap; the row cannot go below 85%. */
    @Test
    fun frameLimitFollowsRowPosition() {
        fun limit(row: Float, gap: Float) = LayoutEngine.frame(AppearanceSettings(rowPositionPct = row, rowGapPct = gap)).limit
        assertEquals(0.49f * 1080, LayoutEngine.frame(AppearanceSettings()).limit, 0.01f)
        assertEquals(0.64f * 1080, limit(0.70f, 0.06f), 0.01f)
        assertEquals(0.82f * 1080, limit(0.85f, 0.03f), 0.01f)
        assertEquals("gap below the minimum", 0.82f * 1080, limit(0.85f, 0f), 0.01f)
        assertEquals("row below 85%", 0.82f * 1080, limit(1.5f, 0.03f), 0.01f)
        assertEquals("non-finite row", 0f, limit(Float.NaN, 0.06f), 0.01f)
        assertEquals("non-finite gap", 0.82f * 1080, limit(0.85f, Float.NaN), 0.01f)
        assertEquals("never negative", 0f, limit(0.55f, 2f), 0.01f)
    }

    @Test
    fun shortTitleGetsLargeSizeLongTitleWrapsToTwoLines() {
        val short = LayoutEngine.layout(content(title = "Дюна"), AppearanceSettings(), measurer).header as TitleBox
        assertEquals(85f, short.style.size)
        assertEquals(1, short.lines)

        val long = LayoutEngine.layout(content(title = "Пираты Карибского моря: Сундук мертвеца"), AppearanceSettings(), measurer).header as TitleBox
        assertEquals(2, long.lines)
        assertTrue(long.style.size in 45f..56f)
        assertTrue(long.height <= 130f)
    }

    @Test
    fun logoChecks() {
        assertTrue(LayoutEngine.logoUsable(500, 150))
        assertFalse("aspect > 14", LayoutEngine.logoUsable(1500, 100))
        assertFalse("aspect < 0.8", LayoutEngine.logoUsable(70, 100))
        assertTrue(LayoutEngine.logoUsable(800, 100))
        assertFalse(LayoutEngine.logoUsable(0, 10))
    }

    /** A wide wordmark may span the whole block, so it is usable where 520 px would leave it under 60 px tall. */
    @Test
    fun wideLogoChecksUseFrameWidthAndPreset() {
        val frame = LayoutEngine.frame(AppearanceSettings())
        assertEquals(883.2f, frame.width, 0.01f)
        // Aspect 9: 98 px tall at 883 px (it was 520×58 when capped at the logo box width).
        assertTrue(LayoutEngine.logoUsable(900, 100, frame, TextPreset.MEDIUM))
        // Aspect 12.4: 71 px tall.
        assertTrue(LayoutEngine.logoUsable(1240, 100, frame, TextPreset.MEDIUM))
        // A narrow block leaves it at 53 px.
        val narrow = LayoutEngine.frame(AppearanceSettings(blockWidthPct = 0.25f))
        assertFalse(LayoutEngine.logoUsable(900, 100, narrow, TextPreset.MEDIUM))
        assertTrue(LayoutEngine.logoUsable(900, 100, frame, TextPreset.SMALL))
    }

    /** A wide logo decoded at w500-like bounds would be upscaled to the frame width: it only counts its own pixels. */
    @Test
    fun logoUsableNeverCountsUpscaledPixels() {
        // 2100×175 decoded with inSampleSize 4: 43 px tall, which is below 60 px however wide the block is.
        assertFalse(LayoutEngine.logoUsable(525, 43))
        // The same logo decoded at block width (inSampleSize 2).
        assertTrue(LayoutEngine.logoUsable(1050, 87))
        // A small regular logo: 130 px tall in the box only if upscaled.
        assertFalse(LayoutEngine.logoUsable(150, 50))
    }

    /** blockWidthPct = 0.25 gives a 480 px frame: an aspect-10 logo is drawn 48 px tall there. */
    @Test
    fun logoUsableUsesRealFrame() {
        val narrow = LayoutEngine.frame(AppearanceSettings(blockWidthPct = 0.25f))
        assertEquals(480f, narrow.width, 0.01f)
        assertTrue(LayoutEngine.logoUsable(1500, 150))
        assertFalse(LayoutEngine.logoUsable(1500, 150, narrow, TextPreset.MEDIUM))
        // A larger preset does not help: the width is the limit.
        assertFalse(LayoutEngine.logoUsable(1500, 150, narrow, TextPreset.LARGE))
    }

    @Test
    fun logoFitNeverExceedsSourceSize() {
        val frame = LayoutEngine.frame(AppearanceSettings())
        val p = LayoutEngine.sizes(TextPreset.MEDIUM)
        // Wide: frame width / 12 = 73.6 px, capped at the 43 px source.
        val wide = LayoutEngine.logoFit(12f, 43f, frame, p)
        assertEquals(43f, wide.height, 0.01f)
        assertEquals(516f, wide.width, 0.01f)
        // Regular: the 520×130 box, capped at the 100 px source.
        val small = LayoutEngine.logoFit(2f, 100f, frame, p)
        assertEquals(LogoSize(200f, 100f), small)
        // A large source fills the box; no cap without a pixel height.
        assertEquals(LogoSize(520f, 130f), LayoutEngine.logoFit(4f, 600f, frame, p))
        assertEquals(LogoSize(520f, 130f), LayoutEngine.logoFit(4f, null, frame, p))
        assertEquals(frame.width, LayoutEngine.logoFit(12f, null, frame, p).width, 0.01f)
        // A smaller header box (shrinking) still applies.
        assertEquals(LogoSize(400f, 100f), LayoutEngine.logoFit(4f, 600f, frame, p, boxH = 100f))
    }

    @Test
    fun logoBoxCappedAtBitmapHeight() {
        val layout = LayoutEngine.layout(content(logoAspect = 4f, logoPixelHeight = 90f), AppearanceSettings(), measurer)
        val logo = layout.header as LogoBox
        assertEquals(90f, logo.height, 0.01f)
        assertEquals(360f, logo.width, 0.01f)
        assertInside(layout)
        val wide = LayoutEngine.layout(content(logoAspect = 12f, logoPixelHeight = 61f), AppearanceSettings(), measurer).header as LogoBox
        assertEquals(61f, wide.height, 0.01f)
        assertEquals(732f, wide.width, 0.01f)
    }

    @Test
    fun wideLogoSpansBlockWidth() {
        val layout = LayoutEngine.layout(content(logoAspect = 12f), AppearanceSettings(), measurer)
        val logo = layout.header as LogoBox
        assertEquals(layout.frame.width, logo.width, 0.01f)
        assertEquals(layout.frame.width / 12f, logo.height, 0.01f)
        assertInside(layout)
        // Up to aspect 4 the preset's 520 px box still applies.
        val regular = LayoutEngine.layout(content(logoAspect = 4f), AppearanceSettings(), measurer).header as LogoBox
        assertEquals(520f, regular.width, 0.01f)
    }

    // ───────────────────────── card row position and block anchor ─────────────────────────

    private fun topOf(l: BlockLayout): Float =
        listOfNotNull(l.header?.top, l.meta?.top, l.genres?.top, l.overview?.top).minOrNull() ?: l.frame.top

    /** No element whose top is in the clock band (y ≤ 20%) reaches x ≥ 74%. */
    private fun assertOutsideClock(l: BlockLayout, msg: String) {
        val clockX = LayoutEngine.CLOCK_X * l.frame.canvasW
        val clockY = LayoutEngine.CLOCK_Y * l.frame.canvasH
        val boxes = listOfNotNull(
            l.header?.let { it.top to it.left + it.width },
            l.meta?.main?.let { it.top to it.right },
            l.meta?.rating?.let { it.top to it.right },
            l.genres?.let { it.top to it.right },
            l.overview?.let { it.top to it.left + it.width },
        )
        boxes.forEach { (top, right) -> assertTrue("$msg: clock zone", top > clockY || right + LayoutEngine.SHADOW_PAD <= clockX) }
    }

    @Test
    fun everyRowPositionAnchorAndPresetStaysWithinLimits() {
        val titles = listOf("Дюна", "Звёздные войны. Ґодзілла. Аліса в Країні Чудес. Темний лицар. Людина-павук: Додому шляху немає.")
        for (row in AppearanceRanges.ROW_OPTIONS) for (anchor in BlockAnchor.entries) for (preset in TextPreset.entries)
            for (lines in listOf(3, 5, AppearanceRanges.OVERVIEW_LINES.last)) for (logo in listOf(null, 4f, 12f)) for (title in titles) {
                val msg = "row=$row/$anchor/$preset/lines=$lines/logo=$logo/${title.length}"
                val settings = AppearanceSettings(textPreset = preset, rowPositionPct = row, blockAnchor = anchor, overviewLines = lines)
                val layout = LayoutEngine.layout(content(title = title, logoAspect = logo), settings, measurer)
                assertInside(layout)
                assertOutsideClock(layout, msg)
                assertOrdered(layout.steps)
                assertTrue(msg, layout.frame.limit <= (row - LayoutEngine.MIN_ROW_GAP) * 1080 + 0.01f)
                assertTrue("$msg: starts above the top margin", topOf(layout) >= layout.frame.top - 0.01f)
                assertNotNull("$msg: meta line", layout.meta)
                assertTrue(msg, layout.steps.none { it.startsWith("trim:") || it.startsWith("emergency:") })
                assertTrue(msg, layout.overview!!.lines >= 3)
                if (anchor == BlockAnchor.ABOVE_ROW) assertEquals(msg, layout.frame.limit - LayoutEngine.SHADOW_PAD - 0.5f, layout.bottom, 0.5f)
            }
    }

    /** The same row options with every top margin and gap the settings allow still leave room for the meta line. */
    @Test
    fun everyRowAndGapOptionKeepsMetaLine() {
        for (row in AppearanceRanges.ROW_OPTIONS) for (gap in AppearanceRanges.ROW_GAP_OPTIONS) for (top in AppearanceRanges.TOP_OPTIONS)
            for (anchor in BlockAnchor.entries) for (preset in TextPreset.entries) {
                val settings = AppearanceRanges.clamp(
                    AppearanceSettings(textPreset = preset, topPct = top, rowPositionPct = row, rowGapPct = gap, blockAnchor = anchor),
                )
                val layout = LayoutEngine.layout(content(logoAspect = 4f), settings, measurer)
                assertInside(layout)
                assertOutsideClock(layout, settings.toString())
                assertNotNull(settings.toString(), layout.meta)
                assertTrue(settings.toString(), topOf(layout) >= layout.frame.top - 0.01f)
            }
    }

    @Test
    fun lowerRowGivesMoreOverviewLines() {
        val overview = BlockContent.collapse("$longOverview $longOverview")
        for (preset in TextPreset.entries) for (anchor in BlockAnchor.entries) {
            val lines = AppearanceRanges.ROW_OPTIONS.map { row ->
                val settings = AppearanceSettings(textPreset = preset, rowPositionPct = row, blockAnchor = anchor, overviewLines = 8)
                LayoutEngine.layout(content(logoAspect = 4f, overview = overview), settings, measurer).overview!!.lines
            }
            assertEquals("$preset/$anchor: $lines", lines.sorted(), lines)
            assertTrue("$preset/$anchor: $lines", lines.first() < lines.last())
            assertEquals("$preset/$anchor: $lines", 8, lines.last())
        }
    }

    @Test
    fun overviewLinesAreCappedAtEight() {
        val overview = BlockContent.collapse("$longOverview $longOverview")
        val settings = AppearanceSettings(rowPositionPct = 0.85f, overviewLines = 12)
        val layout = LayoutEngine.layout(content(logoAspect = 4f, overview = overview), settings, measurer)
        assertEquals(8, layout.overview!!.lines)
        assertEquals(emptyList<String>(), layout.steps)
    }

    /** Above the row: the same layout as at the top (same shrink steps and spacing), moved down to end at the limit. */
    @Test
    fun aboveRowAnchorMovesTheTopLayoutDown() {
        val c = content(logoAspect = 4f)
        for (row in AppearanceRanges.ROW_OPTIONS) {
            val top = LayoutEngine.layout(c, AppearanceSettings(rowPositionPct = row), measurer)
            val low = LayoutEngine.layout(c, AppearanceSettings(rowPositionPct = row, blockAnchor = BlockAnchor.ABOVE_ROW), measurer)
            val dy = low.bottom - top.bottom
            assertEquals(top.steps, low.steps)
            assertEquals(top.frame, low.frame)
            assertEquals(kotlin.math.floor(top.frame.limit - LayoutEngine.SHADOW_PAD - top.bottom), dy, 0.01f)
            assertTrue(dy >= 0f)
            assertInside(low)
            assertEquals(top.header!!.top + dy, low.header!!.top, 0.01f)
            assertEquals(top.header.height, low.header.height, 0.01f)
            assertEquals(top.meta!!.top + dy, low.meta!!.top, 0.01f)
            assertEquals(top.meta.main!!.baseline + dy, low.meta.main!!.baseline, 0.01f)
            assertEquals(top.meta.rating!!.baseline + dy, low.meta.rating!!.baseline, 0.01f)
            assertEquals(top.genres!!.baseline + dy, low.genres!!.baseline, 0.01f)
            assertEquals(top.overview!!.top + dy, low.overview!!.top, 0.01f)
            assertEquals(top.overview.text, low.overview.text)
        }
        // A short block at the default row sits right above the limit, well below the top margin.
        val short = LayoutEngine.layout(
            content(overview = "Дюна."), AppearanceSettings(blockAnchor = BlockAnchor.ABOVE_ROW), measurer,
        )
        assertTrue(topOf(short) > short.frame.top + 100f)
        assertEquals(short.frame.limit - LayoutEngine.SHADOW_PAD - 0.5f, short.bottom, 0.5f)
        assertInside(short)
        // An empty block stays empty.
        val empty = LayoutEngine.layout(
            content(title = "", metaParts = emptyList(), rating = null, genres = emptyList(), overview = null),
            AppearanceSettings(blockAnchor = BlockAnchor.ABOVE_ROW), measurer,
        )
        assertTrue(empty.isEmpty)
    }

    // ───────────────────────── source-app badge ─────────────────────────

    @Test
    fun badgeIsReservedAtTheStartOfTheMetaLine() {
        val plain = LayoutEngine.layout(content(logoAspect = 4f), AppearanceSettings(), measurer)
        val badged = LayoutEngine.layout(content(logoAspect = 4f, badgeAspect = 16f / 9f), AppearanceSettings(), measurer)
        val p = LayoutEngine.sizes(TextPreset.MEDIUM)
        val meta = badged.meta!!
        val badge = meta.badge!!
        assertEquals(badged.frame.left, badge.left, 0.01f)
        assertEquals(meta.top, badge.top, 0.01f)
        assertEquals(p.badgeH, badge.height, 0.01f)
        assertEquals(p.badgeH * 16f / 9f, badge.width, 0.01f)
        // The text starts after the plate and a gap, and is centered on it.
        val main = meta.main!!
        assertEquals(badge.right + main.style.size * LayoutEngine.BADGE_GAP_EM, main.left, 0.01f)
        assertTrue(meta.height >= badge.height)
        assertTrue(main.top >= meta.top && main.bottom <= meta.bottom)
        assertEquals(meta.top + meta.height / 2, main.top + main.height / 2, 1f)
        assertEquals(main.right + main.style.size * 0.9f, meta.rating!!.left, 0.01f)
        assertEquals(main.text, plain.meta!!.main!!.text)
        // The taller line pushes everything below it down.
        assertTrue(badged.genres!!.top > plain.genres!!.top)
        assertInside(badged)
        assertNull(plain.meta.badge)
    }

    @Test
    fun badgeTakesRoomFromMetaPartsNotFromTheRating() {
        val narrow = AppearanceSettings(blockWidthPct = 0.30f, textPreset = TextPreset.LARGE)
        val parts = listOf("2009", "Фильм", "2 ч 42 мин")
        val plain = LayoutEngine.layout(content(metaParts = parts), narrow, measurer).meta!!
        val badged = LayoutEngine.layout(content(metaParts = parts, badgeAspect = LayoutEngine.MAX_BADGE_ASPECT), narrow, measurer).meta!!
        assertTrue(badged.main!!.text.length < plain.main!!.text.length)
        assertTrue(badged.main.text.startsWith("2009"))
        assertEquals("TMDB 7,6/10", badged.rating!!.text)
    }

    @Test
    fun badgeAloneKeepsTheMetaLine() {
        val layout = LayoutEngine.layout(content(metaParts = emptyList(), rating = null, badgeAspect = 1f), AppearanceSettings(), measurer)
        val meta = layout.meta!!
        assertNull(meta.main)
        assertNull(meta.rating)
        assertEquals(meta.badge!!.height, meta.height, 0.01f)
        assertEquals(meta.badge.height, meta.badge.width, 0.01f)
        assertInside(layout)
    }

    @Test
    fun badgeShapeFollowsBannerWithinBoundsAndIconIsSquare() {
        assertEquals(16f / 9f, LayoutEngine.badgeAspect(320, 180, banner = true), 0.001f)
        assertEquals(LayoutEngine.MAX_BADGE_ASPECT, LayoutEngine.badgeAspect(600, 100, banner = true), 0.001f)
        assertEquals(1f, LayoutEngine.badgeAspect(100, 300, banner = true), 0.001f)
        assertEquals(1f, LayoutEngine.badgeAspect(320, 180, banner = false), 0.001f)
        assertEquals(1f, LayoutEngine.badgeAspect(0, 0, banner = true), 0.001f)
    }

    @Test
    fun badgeFollowsShowAppBadgeSetting() {
        val info = DisplayInfo(
            MediaType.MOVIE, 1, DisplayLanguage.EN, "Sample", "2009", "Movie", null, emptyList(), null, null, null, null, "fp",
        )
        assertEquals(1.5f, BlockContent.from(info, AppearanceSettings(), null, badgeAspect = 1.5f).badgeAspect)
        assertNull(BlockContent.from(info, AppearanceSettings(showAppBadge = false), null, badgeAspect = 1.5f).badgeAspect)
        assertNull(BlockContent.titled("Sample", AppearanceSettings(showAppBadge = false), 1.5f).badgeAspect)
    }

    /**
     * Badge plates in every row position, anchor and preset stay inside the limits. With the default margins the
     * regular shrink steps are enough; even the tightest block the settings allow keeps the plate and the meta line.
     */
    @Test
    fun everyRowAnchorAndPresetFitsTheBadge() {
        for (row in AppearanceRanges.ROW_OPTIONS) for (tight in listOf(false, true)) for (anchor in BlockAnchor.entries)
            for (preset in TextPreset.entries) for (logo in listOf(null, 4f)) {
                val base = AppearanceSettings(textPreset = preset, rowPositionPct = row, blockAnchor = anchor)
                val settings = AppearanceRanges.clamp(if (tight) base.copy(rowGapPct = 0.15f, topPct = 0.15f) else base)
                val msg = "row=$row/tight=$tight/$anchor/$preset/logo=$logo"
                val layout = LayoutEngine.layout(content(logoAspect = logo, badgeAspect = LayoutEngine.MAX_BADGE_ASPECT), settings, measurer)
                assertInside(layout)
                assertOutsideClock(layout, msg)
                assertNotNull(msg, layout.meta?.badge)
                assertOrdered(layout.steps)
                assertTrue(msg, layout.steps.none { it.startsWith("trim:") })
                if (!tight) assertTrue(msg, layout.steps.none { it.startsWith("emergency:") })
                if (anchor == BlockAnchor.ABOVE_ROW) assertEquals(msg, layout.frame.limit - LayoutEngine.SHADOW_PAD - 0.5f, layout.bottom, 0.5f)
            }
    }

    @Test
    fun aboveRowAnchorMovesTheBadgeToo() {
        val c = content(badgeAspect = 16f / 9f)
        val top = LayoutEngine.layout(c, AppearanceSettings(), measurer)
        val low = LayoutEngine.layout(c, AppearanceSettings(blockAnchor = BlockAnchor.ABOVE_ROW), measurer)
        val dy = low.bottom - top.bottom
        assertTrue(dy > 0f)
        assertEquals(top.meta!!.badge!!.top + dy, low.meta!!.badge!!.top, 0.01f)
        assertEquals(low.meta.top, low.meta.badge.top, 0.01f)
        assertInside(low)
    }

    @Test
    fun scaleStepShrinksTheBadge() {
        val settings = AppearanceSettings(rowGapPct = 0.30f)
        val layout = LayoutEngine.layout(content(logoAspect = 3f, badgeAspect = 1f), settings, measurer)
        val scale = layout.steps.lastOrNull { it.startsWith("scale=") }?.removePrefix("scale=")?.toFloat()
        assertNotNull(layout.steps.toString(), scale)
        assertEquals(LayoutEngine.sizes(TextPreset.MEDIUM).badgeH * scale!!, layout.meta!!.badge!!.height, 0.01f)
        assertInside(layout)
    }

    // ───────────────────────── titled block (card not recognized) ─────────────────────────

    @Test
    fun titledBlockHasTitleAndBadgeOnly() {
        val content = BlockContent.titled("  Тестовий   фільм\n(2024)  ", AppearanceSettings(), 16f / 9f)
        assertEquals("Тестовий фільм (2024)", content.title)
        for (anchor in BlockAnchor.entries) for (preset in TextPreset.entries) {
            val layout = LayoutEngine.layout(content, AppearanceSettings(textPreset = preset, blockAnchor = anchor), measurer)
            val header = layout.header as TitleBox
            assertEquals("Тестовий фільм (2024)", header.text)
            assertEquals(1, header.lines)
            assertNull(layout.meta!!.main)
            assertNull(layout.meta.rating)
            assertNotNull(layout.meta.badge)
            assertNull(layout.genres)
            assertNull(layout.overview)
            assertEquals(layout.header.bottom + LayoutEngine.sizes(preset).gapHeader, layout.meta.top, 0.01f)
            assertTrue(layout.steps.isEmpty())
            assertInside(layout)
        }
    }

    @Test
    fun titledBlockWithoutBadgeIsTheTitleAlone() {
        val layout = LayoutEngine.layout(BlockContent.titled("Тестовий фільм", AppearanceSettings(), null), AppearanceSettings(), measurer)
        assertNotNull(layout.header)
        assertNull(layout.meta)
        assertEquals(layout.header!!.bottom, layout.bottom, 0.01f)
    }

    @Test
    fun longTitledTitleWrapsToTwoLinesAtMost() {
        val file = "Some.Very.Long.Release.Name.2019.2160p.UHD.BluRay.REMUX.HDR.HEVC.Atmos-GROUP.mkv"
        val title = List(6) { file }.joinToString(" ")
        val content = BlockContent.titled(title, AppearanceSettings(), 1f)
        assertTrue(content.title.length <= BlockContent.MAX_TITLED_CHARS)
        for (preset in TextPreset.entries) {
            val layout = LayoutEngine.layout(content, AppearanceSettings(textPreset = preset), measurer)
            val header = layout.header as TitleBox
            assertTrue(header.lines in 1..LayoutEngine.MAX_TITLE_LINES)
            assertEquals(LayoutEngine.sizes(preset).titleMin, header.style.size, 0.01f)
            assertNotNull(layout.meta!!.badge)
            assertInside(layout)
        }
    }

    // ───────────────────────── overview trimming ─────────────────────────

    @Test
    fun longOverviewEndsAtLastWholeSentenceThatFits() {
        val full = BlockContent.collapse(longOverview)
        val layout = LayoutEngine.layout(content(logoAspect = 4f), AppearanceSettings(rowGapPct = 0.03f), measurer)
        val overview = layout.overview!!
        assertEquals(5, overview.lines)
        assertTrue(overview.text.length < full.length)
        assertTrue(full.startsWith(overview.text))
        assertTrue(overview.text.endsWith("."))
        // The trimmed text fits its lines, so the renderer draws it without an ellipsis.
        assertEquals(overview.lines, measurer.lineCount(overview.text, overview.style, overview.width))
        // The next sentence would not fit.
        val next = full.substring(0, full.indexOf('.', overview.text.length) + 1)
        assertTrue(measurer.lineCount(next, overview.style, overview.width) > overview.lines)
    }

    @Test
    fun overviewThatFitsIsKeptWhole() {
        val short = "Дюна. Интерстеллар. Титаник."
        val layout = LayoutEngine.layout(content(overview = short), AppearanceSettings(), measurer)
        assertEquals(short, layout.overview!!.text)
        assertEquals(1, layout.overview.lines)
    }

    @Test
    fun overviewFallsBackToEllipsisWithoutAUsableSentenceEnd() {
        val words = (1..120).joinToString(" ") { "слово" }
        // One endless sentence: nothing to trim at.
        val single = LayoutEngine.layout(content(overview = "$words."), AppearanceSettings(rowGapPct = 0.03f), measurer).overview!!
        assertEquals("$words.", single.text)
        assertEquals(5, single.lines)
        // The only sentence end leaves a single line: the ellipsis keeps more text.
        val text = "Дюна. ${words.replaceFirstChar { it.uppercase() }}."
        val short = LayoutEngine.layout(content(overview = text), AppearanceSettings(rowGapPct = 0.03f), measurer).overview!!
        assertEquals(text, short.text)
        assertEquals(5, short.lines)
    }

    @Test
    fun sentenceEndsSkipInitialsAndAbbreviations() {
        fun ends(text: String) = Sentences.ends(text).map { text.substring(0, it) }
        assertEquals(
            listOf("Дж. Р. Р. Толкин написал книгу."),
            ends("Дж. Р. Р. Толкин написал книгу. Она вышла в 1954 г. В Лондоне и т. д. Потом был фильм"),
        )
        assertEquals(listOf("Mr. Smith goes to Washington."), ends("Mr. Smith goes to Washington. Dr. No is here etc. and more"))
        assertEquals(listOf("Конец?!", "Конец?! «Начало»."), ends("Конец?! «Начало». — Кто там? спросил он"))
        assertEquals(listOf("Это был я."), ends("Это был я. Потом… снова"))
        assertEquals(listOf("Ждите…"), ends("Ждите… 2 дня"))
        assertEquals("no space after the period", emptyList<String>(), ends("Версия 2.5 Финал"))
    }
}
