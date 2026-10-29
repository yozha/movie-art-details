package io.github.yozha.movieartdetails.core

import io.github.yozha.movieartdetails.match.TitleParser
import io.github.yozha.movieartdetails.model.Candidate
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.TitleDetails
import io.github.yozha.movieartdetails.model.Translation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class TagCheckTest {

    private fun found(title: String, date: String?, type: MediaType = MediaType.MOVIE, original: String = title) =
        Candidate(type, 1, title, original, date, 1.0, 10, "en", "en-US")

    private fun details(type: MediaType, title: String, date: String?, last: String? = null, translations: List<Translation> = emptyList()) =
        TitleDetails(
            mediaType = type, id = 7, title = title, originalTitle = title, originalLanguage = "en", overview = null,
            date = date, lastAirDate = last, status = null, runtimeMinutes = null, numberOfSeasons = null, genres = emptyList(),
            voteAverage = 0.0, voteCount = 0, backdropPath = null, translations = translations, logos = emptyList(),
            backdrops = emptyList(), imdbId = null,
        )

    @Test
    fun `same title fits regardless of the year`() {
        assertNotNull(TagCheck.fit(TitleParser.parse("Avatar (2009) [imdbid-tt0499549]"), found("Avatar", "2009-12-15")))
        assertNotNull(TagCheck.fit(TitleParser.parse("Avatar [imdbid-tt0499549]"), found("Avatar", null)))
    }

    @Test
    fun `different title fits only when the year agrees`() {
        val parsed = TitleParser.parse("Avatar (2009) [imdbid-tt0499549]")
        assertNotNull(TagCheck.fit(parsed, found("Аватар", "2010-01-01")))
        assertNull(TagCheck.fit(parsed, found("Unrelated Placeholder", "2022-01-01")))
        // Without a card year only the title can agree.
        assertNull(TagCheck.fit(TitleParser.parse("Avatar [imdbid-tt1234567]"), found("Unrelated Placeholder", "2009-01-01")))
    }

    @Test
    fun `a card that is only a tag cannot disagree`() {
        assertNotNull(TagCheck.fit(TitleParser.parse("[imdbid-tt1234567]"), found("Anything", "1950-01-01")))
    }

    @Test
    fun `series year may be any year the series ran`() {
        val parsed = TitleParser.parse("Synthetic Show 2015 [tmdbid-7]")
        val series = details(MediaType.TV, "Unrelated Name", "2011-04-17", last = "2019-05-19")
        assertNotNull(TagCheck.fit(parsed, series))
        assertNull(TagCheck.fit(TitleParser.parse("Synthetic Show 2021 [tmdbid-7]"), series))
    }

    @Test
    fun `details are checked against every translation and against the main title`() {
        val matrix = details(MediaType.MOVIE, "The Matrix", "1999-03-30", translations = listOf(Translation("ru", "RU", "Матрица", null)))
        assertEquals(1.0, TagCheck.fit(TitleParser.parse("Матрица [tmdbid-603]"), matrix)!!.similarity, 1e-9)
        val dune = details(MediaType.MOVIE, "Dune: Part Two", "2024-02-27")
        assertEquals(1.0, TagCheck.similarity(TitleParser.parse("Dune [tmdbid-7]"), listOf(dune.title)), 1e-9)
    }

    @Test
    fun `pick prefers the clearly better title, then the year, then the series for an episode`() {
        val movie = details(MediaType.MOVIE, "The Matrix", "1999-03-30", translations = listOf(Translation("ru", "RU", "Матрица", null)))
        val show = details(MediaType.TV, "Synthetic Talk Show", "2004-09-22")
        assertEquals(MediaType.MOVIE, TagCheck.pick(TitleParser.parse("Матрица [tmdbid-603]"), listOf(movie, show))?.mediaType)

        val sameMovie = details(MediaType.MOVIE, "Same Name", "2001-01-01")
        val sameShow = details(MediaType.TV, "Same Name", "2015-01-01")
        assertNull(TagCheck.pick(TitleParser.parse("Same Name [tmdbid-7]"), listOf(sameMovie, sameShow)))
        assertEquals(MediaType.MOVIE, TagCheck.pick(TitleParser.parse("Same Name (2001) [tmdbid-7]"), listOf(sameMovie, sameShow))?.mediaType)
        assertEquals(MediaType.TV, TagCheck.pick(TitleParser.parse("Same Name S01E02 [tmdbid-7]"), listOf(sameMovie, sameShow))?.mediaType)
        assertNull(TagCheck.pick(TitleParser.parse("Avatar [tmdbid-7]"), listOf(sameMovie, sameShow)))
    }
}
