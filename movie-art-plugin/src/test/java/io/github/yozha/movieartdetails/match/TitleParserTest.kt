package io.github.yozha.movieartdetails.match

import io.github.yozha.movieartdetails.model.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TitleParserTest {
    @Test
    fun localizedAndOriginalAreKept() {
        val p = TitleParser.parse("Аватар / Avatar")
        assertEquals("Аватар / Avatar", p.raw)
        assertEquals("Аватар", p.primary)
        assertEquals(listOf("Avatar"), p.alternates)
        assertEquals("аватар", p.normalized)
        assertEquals(Script.CYRILLIC_AMBIGUOUS, p.script)
        assertFalse(p.looksLikeFileName)
    }

    @Test
    fun vlcFileNameIsCleanedWithoutLosingHints() {
        val raw = "Alfred Hitchcock's Rear Window 1954 1080p BluRay REMUX AVC FLAC 2.0 - SOMEGROUP"
        val p = TitleParser.parse(raw)
        assertEquals(raw, p.raw)
        assertEquals("Alfred Hitchcock's Rear Window", p.primary)
        assertEquals(1954, p.year)
        assertTrue(p.looksLikeFileName)
        // The year is the only trailing number: the variant with the number is kept (it may be part of the title).
        assertEquals(listOf("Alfred Hitchcock's Rear Window 1954"), p.alternates)
    }

    @Test
    fun idTagsAreExtracted() {
        val imdb = TitleParser.parse("Movie Name (2010) [imdbid-tt1234567]")
        assertEquals("tt1234567", imdb.imdbId)
        assertEquals("Movie Name", imdb.primary)
        assertEquals("tt7654321", TitleParser.parse("Some Film {imdb-tt7654321}").imdbId)
        val tmdb = TitleParser.parse("Матрица [tmdbid-603]")
        assertEquals(603, tmdb.tmdbId)
        assertEquals("Матрица", tmdb.primary)
        assertNull(tmdb.imdbId)
    }

    @Test
    fun numberAtEndOfPlainTitleStaysAsAlternate() {
        val p = TitleParser.parse("Wonder Woman 1984")
        assertEquals("Wonder Woman", p.primary)
        assertEquals(1984, p.year)
        assertEquals(listOf("Wonder Woman 1984"), p.alternates)
    }

    @Test
    fun numberAtEndOfFileNameStaysAsAlternate() {
        val p = TitleParser.parse("Wonder.Woman.1984.1080p.WEB-DL.mkv")
        assertEquals("Wonder Woman", p.primary)
        assertEquals(1984, p.year)
        assertEquals(listOf("Wonder Woman 1984"), p.alternates)
        assertTrue(p.looksLikeFileName)
        // With a second year candidate, the number belongs to the title and the last one is the year; no extra variant.
        val both = TitleParser.parse("Wonder.Woman.1984.2020.1080p.mkv")
        assertEquals("Wonder Woman 1984", both.primary)
        assertEquals(2020, both.year)
        assertTrue(both.alternates.isEmpty())
        // A year in brackets is unambiguous.
        assertTrue(TitleParser.parse("[YTS.MX] Solaris (1972) [1080p] [BluRay] [5.1]").alternates.isEmpty())
    }

    @Test
    fun titleNumbersAndBracketsAreNotRemoved() {
        assertEquals("1917", TitleParser.parse("1917").primary)
        assertNull(TitleParser.parse("1917").year)
        assertEquals("2001: A Space Odyssey", TitleParser.parse("2001: A Space Odyssey").primary)
        assertEquals("9 1/2 Weeks", TitleParser.parse("9 1/2 Weeks").primary)
        assertEquals("(500) Days of Summer", TitleParser.parse("(500) Days of Summer").primary)
        assertEquals("Space: 1999", TitleParser.parse("Space: 1999").primary)
        assertEquals("Charlotte's Web", TitleParser.parse("Charlotte's Web").primary)
    }

    @Test
    fun trailingBracketGivesAlternate() {
        val p = TitleParser.parse("Movie (Director's Cut)")
        assertEquals("Movie (Director's Cut)", p.primary)
        assertEquals(listOf("Movie"), p.alternates)
    }

    @Test
    fun normalizationUnifiesApostrophesDashesAndYo() {
        val p = TitleParser.parse("Ocean’s  Eleven – Spider‐Man")
        assertEquals("Ocean's Eleven - Spider-Man", p.primary)
        assertEquals("ocean's eleven - spider-man", p.normalized)
        assertEquals("звездные войны", TitleParser.parse("Звёздные войны").normalized)
        assertEquals(TitleText.compareForm("Ocean's Eleven"), TitleText.compareForm("OCEAN’S  ELEVEN"))
        assertEquals("amelie", TitleText.compareForm("Amélie"))
        // NFKC: full-width characters are folded to regular ones.
        assertEquals("abc", TitleText.normalize("ＡＢＣ"))
    }

    @Test
    fun scriptDetection() {
        assertEquals(Script.CYRILLIC_RU, TitleText.scriptOf("Чёрный лебедь"))
        assertEquals(Script.CYRILLIC_AMBIGUOUS, TitleText.scriptOf("Солярис"))
        assertEquals(Script.CYRILLIC_UK, TitleText.scriptOf("Ведмідь"))
        assertEquals(Script.CYRILLIC_UK, TitleText.scriptOf("Ґодзілла"))
        assertEquals(Script.CYRILLIC_AMBIGUOUS, TitleText.scriptOf("Аватар"))
        assertEquals(Script.CYRILLIC_AMBIGUOUS, TitleText.scriptOf("Ґодзілла / Чёрный лебедь"))
        assertEquals(Script.LATIN, TitleText.scriptOf("Up"))
        assertEquals(Script.OTHER, TitleText.scriptOf("千と千尋の神隠し"))
        assertEquals(Script.NONE, TitleText.scriptOf("1917"))
    }

    @Test
    fun emptyAndTagOnlyInputs() {
        val empty = TitleParser.parse("")
        assertEquals("", empty.primary)
        assertEquals(Script.NONE, empty.script)
        val tagsOnly = TitleParser.parse("1080p BluRay x264")
        assertEquals("", tagsOnly.primary)
        assertTrue(tagsOnly.looksLikeFileName)
    }

    @Test
    fun episodeTitleAfterMarkerIsDropped() {
        val p = TitleParser.parse("Breaking.Bad.S05E14.Ozymandias.720p.WEB-DL.mkv")
        assertEquals("Breaking Bad", p.primary)
        assertEquals(5, p.season)
        assertEquals(14, p.episode)
        assertEquals(listOf("Breaking Bad S05E14 Ozymandias"), p.alternates)
    }

    @Test
    fun episodeMarkerInOneHalfIsPartOfMovieTitle() {
        val sw = TitleParser.parse("Звёздные войны: Эпизод 4 – Новая надежда / Star Wars")
        assertEquals("Звёздные войны: Эпизод 4 - Новая надежда", sw.primary)
        assertEquals(listOf("Star Wars"), sw.alternates)
        assertNull(sw.season)
        assertNull(sw.episode)

        val os = TitleParser.parse("Сезон охоты 2 / Open Season 2")
        assertEquals("Сезон охоты 2", os.primary)
        assertEquals(listOf("Open Season 2"), os.alternates)
        assertNull(os.season)
        assertNull(os.episode)
    }

    @Test
    fun cutByMarkerKeepsUncutAlternate() {
        val p = TitleParser.parse("Звёздные войны: Эпизод 4 – Новая надежда")
        assertEquals("Звёздные войны", p.primary)
        assertEquals(4, p.episode)
        assertEquals(listOf("Звёздные войны: Эпизод 4 - Новая надежда"), p.alternates)
    }

    @Test
    fun markerWithoutLettersAroundIsTheTitle() {
        for (raw in listOf("10x10", "Episode 50")) {
            val p = TitleParser.parse(raw)
            assertEquals(raw, p.primary)
            assertNull(raw, p.season)
            assertNull(raw, p.episode)
        }
        val file = TitleParser.parse("Episode.50.2011.1080p.WEB-DL.mkv")
        assertEquals("Episode 50", file.primary)
        assertEquals(2011, file.year)
        assertNull(file.episode)
    }
}
