package io.github.yozha.movieartdetails.render

import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.DisplayInfo
import io.github.yozha.movieartdetails.model.DisplayLanguage
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.TextPreset
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
    ) = BlockContent(title, logoAspect, metaParts, rating, genres, overview)

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
        val layout = LayoutEngine.layout(content(logoAspect = 4f), AppearanceSettings(bottomLimitPct = 0.52f), measurer)
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
        val settings = AppearanceSettings(bottomLimitPct = 0.40f)
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
        val settings = AppearanceSettings(bottomLimitPct = 0.33f)
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
        val settings = AppearanceSettings(bottomLimitPct = 0.34f, blockWidthPct = 0.6f)
        val layout = LayoutEngine.layout(content(logoAspect = 3f, metaParts = listOf("2009"), genres = listOf("Драма", "Боевик")), settings, measurer)
        assertInside(layout)
        assertTrue(layout.steps.contains("genres=merged"))
        assertNull(layout.genres)
        assertEquals("2009 · Драма · Боевик", layout.meta!!.main!!.text)
    }

    @Test
    fun impossibleLimitTrimsFromBottomAndNeverOverflows() {
        val settings = AppearanceSettings(topPct = 0.07f, bottomLimitPct = 0.15f)
        val layout = LayoutEngine.layout(content(logoAspect = 3f), settings, measurer)
        assertInside(layout)
        assertOrdered(layout.steps)
        assertNull(layout.overview)
        assertTrue(layout.steps.any { it.startsWith("trim:") })
    }

    @Test
    fun everyPresetAndLineCountStaysAboveLimit() {
        val titles = listOf("Дюна", "Звёздные войны. Ґодзілла. Аліса в Країні Чудес. Темний лицар. Людина-павук: Додому шляху немає.")
        for (preset in TextPreset.entries) for (lines in 3..5) for (title in titles) for (logo in listOf(null, 1f, 4f, 9f)) {
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

        val hidden = BlockContent.from(info, AppearanceSettings(showLogo = false, showRating = false, showOverview = false, showGenres = false, showYear = false), 2f)
        assertNull(hidden.logoAspect)
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
        val f = LayoutEngine.frame(AppearanceSettings(leftPct = 0.1f, blockWidthPct = 0.9f, bottomLimitPct = 0.8f))
        assertEquals(0.52f * 1080, f.limit, 0.01f)
        assertTrue(f.right <= 0.74f * 1920 - LayoutEngine.SHADOW_PAD)
        val layout = LayoutEngine.layout(content(), AppearanceSettings(leftPct = 0.1f, blockWidthPct = 0.9f, bottomLimitPct = 0.8f), measurer)
        assertInside(layout)
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
        assertFalse("aspect > 9", LayoutEngine.logoUsable(1000, 100))
        assertFalse("aspect < 0.8", LayoutEngine.logoUsable(70, 100))
        // 520×58 after fitting: below 60 px.
        assertFalse(LayoutEngine.logoUsable(900, 100))
        assertTrue(LayoutEngine.logoUsable(800, 100))
        assertFalse(LayoutEngine.logoUsable(0, 10))
    }
}
