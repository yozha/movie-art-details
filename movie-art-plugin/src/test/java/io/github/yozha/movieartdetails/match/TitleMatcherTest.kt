package io.github.yozha.movieartdetails.match

import io.github.yozha.movieartdetails.model.Candidate
import io.github.yozha.movieartdetails.model.CardKind
import io.github.yozha.movieartdetails.model.MediaType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * Matching against synthetic TMDB responses (src/test/resources/match/search_cases.synthetic.json).
 * Criteria: zero false accepts on the reject subset and at least 90% correct on the rest.
 */
class TitleMatcherTest {
    private class Case(
        val name: String,
        val raw: String,
        val type: Int?,
        val iconUri: String?,
        val expect: Pair<MediaType, Int>?,
        val candidates: List<Candidate>,
    )

    private val cases: List<Case> by lazy {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("match/search_cases.synthetic.json"))
        val root = Json.parseToJsonElement(stream.bufferedReader(Charsets.UTF_8).use { it.readText() }).jsonObject
        root.getValue("cases").jsonArray.map { e ->
            val o = e.jsonObject
            Case(
                name = o.getValue("name").jsonPrimitive.content,
                raw = o.getValue("raw").jsonPrimitive.content,
                type = o["type"]?.jsonPrimitive?.intOrNull,
                iconUri = o["iconUri"]?.jsonPrimitive?.contentOrNull,
                expect = (o["expect"] as? JsonObject)?.let { mediaType(it) to it.getValue("id").jsonPrimitive.int },
                candidates = o.getValue("responses").jsonArray.flatMap { r ->
                    val resp = r.jsonObject
                    val media = mediaType(resp)
                    val lang = resp.getValue("language").jsonPrimitive.content
                    resp.getValue("body").jsonObject.getValue("results").jsonArray.map { candidate(media, lang, it.jsonObject) }
                },
            )
        }
    }

    private fun mediaType(o: JsonObject) = if (o.getValue("media").jsonPrimitive.content == "tv") MediaType.TV else MediaType.MOVIE

    /** Parses a results item the same way tmdb/ does (title/name, release_date/first_air_date). */
    private fun candidate(media: MediaType, lang: String, o: JsonObject) = Candidate(
        mediaType = media,
        id = o.getValue("id").jsonPrimitive.int,
        title = (o["title"] ?: o["name"])!!.jsonPrimitive.content,
        originalTitle = (o["original_title"] ?: o["original_name"])!!.jsonPrimitive.content,
        date = (o["release_date"] ?: o["first_air_date"])?.jsonPrimitive?.contentOrNull?.ifEmpty { null },
        popularity = o["popularity"]?.jsonPrimitive?.double ?: 0.0,
        voteCount = o["vote_count"]?.jsonPrimitive?.int ?: 0,
        originalLanguage = o["original_language"]?.jsonPrimitive?.contentOrNull,
        queryLanguage = lang,
    )

    private fun run(c: Case) = TitleMatcher.match(TitleParser.parse(c.raw), SourceHints.classify(c.type, c.iconUri).kind, c.candidates)

    @Test
    fun fixtureCriteria() {
        val (positive, reject) = cases.partition { it.expect != null }
        assertTrue("too few reject cases: ${reject.size}", reject.size >= 10)
        val falseAccepts = reject.mapNotNull { c ->
            run(c).accepted?.let { "${c.name}: accepted ${it.mediaType}/${it.id}" }
        }
        assertTrue("false accepts:\n" + falseAccepts.joinToString("\n"), falseAccepts.isEmpty())

        val wrong = positive.mapNotNull { c ->
            val r = run(c)
            val got = r.accepted?.let { it.mediaType to it.id }
            if (got == c.expect) null else "${c.name}: expected ${c.expect}, got $got (${r.reason}; ${r.ranked.take(2).map { it.explanation }})"
        }
        val required = ceil(positive.size * 0.9).toInt()
        assertTrue("correct ${positive.size - wrong.size} of ${positive.size}:\n" + wrong.joinToString("\n"), positive.size - wrong.size >= required)
    }

    @Test
    fun everySyntheticCaseAsDesigned() {
        // Stricter than the criteria above: on synthetic data the matcher must give exactly the intended answer.
        for (c in cases) {
            val got = run(c).accepted?.let { it.mediaType to it.id }
            assertEquals(c.name, c.expect, got)
        }
    }

    // ── Individual scenarios with candidates built in code ──

    private fun movie(id: Int, title: String, date: String?, votes: Int = 100, original: String = title) =
        Candidate(MediaType.MOVIE, id, title, original, date, votes / 10.0, votes, "en", "en-US")

    private fun tv(id: Int, title: String, date: String?, votes: Int = 100, original: String = title) =
        Candidate(MediaType.TV, id, title, original, date, votes / 10.0, votes, "en", "en-US")

    @Test
    fun avatarFranchiseAndSeriesAreNotConfused() {
        val candidates = listOf(
            movie(76600, "Avatar: The Way of Water", "2022-12-14", 12000),
            tv(246, "Avatar: The Last Airbender", "2005-02-21", 4000),
            movie(19995, "Avatar", "2009-12-15", 32000),
        )
        val r = TitleMatcher.match(TitleParser.parse("Avatar"), CardKind.MOVIE, candidates)
        assertEquals(19995, r.accepted?.id)
        assertEquals(19995, r.ranked.first().candidate.id)
        assertTrue(r.confidence >= TitleMatcher.ACCEPT)

        val series = TitleMatcher.match(TitleParser.parse("Avatar: The Last Airbender"), CardKind.SERIES, candidates)
        assertEquals(MediaType.TV, series.accepted?.mediaType)
        assertEquals(246, series.accepted?.id)
    }

    @Test
    fun yearPicksBetweenSameNameMovies() {
        val dunes = listOf(movie(438631, "Dune", "2021-09-15", 12000), movie(841, "Dune", "1984-12-14", 2800))
        assertEquals(841, TitleMatcher.match(TitleParser.parse("Dune (1984)"), CardKind.MOVIE, dunes).accepted?.id)
        assertEquals(438631, TitleMatcher.match(TitleParser.parse("Dune (2021)"), CardKind.MOVIE, dunes).accepted?.id)
        // No year and no clear lead in votes: reject rather than pick at random.
        val close = listOf(movie(438631, "Dune", "2021-09-15", 5000), movie(841, "Dune", "1984-12-14", 2800))
        assertNull(TitleMatcher.match(TitleParser.parse("Dune"), CardKind.MOVIE, close).accepted)
        // Unknown type: a tie is not broken by popularity (raised threshold).
        assertNull(TitleMatcher.match(TitleParser.parse("Dune"), CardKind.UNKNOWN, dunes).accepted)
    }

    @Test
    fun youtubeLikeTitleIsRejected() {
        val parsed = TitleParser.parse("4K UHD Rainforest Sounds 🌧️ Ambient Film for Sleep (10 Hours)")
        val randoms = listOf(
            movie(1, "Rainforest", "2018-01-01", 40), movie(2, "Ambient", "2015-01-01"),
            movie(3, "Sleep", "2010-01-01"), tv(4, "Planet Earth", "2006-03-05", 3000),
        )
        for (kind in CardKind.entries) {
            val r = TitleMatcher.match(parsed, kind, randoms)
            assertNull("kind=$kind", r.accepted)
        }
        assertEquals(CardKind.SKIP, SourceHints.classify(4, "https://i.ytimg.com/vi/x/hq.jpg").kind)
    }

    @Test
    fun skipNeverAccepts() {
        val r = TitleMatcher.match(TitleParser.parse("Up"), CardKind.SKIP, listOf(movie(14160, "Up", "2009-05-28", 5000)))
        assertNull(r.accepted)
        assertEquals(1, r.ranked.size)
    }

    @Test
    fun duplicatesFromTwoQueryLanguagesDoNotKillMargin() {
        val ru = Candidate(MediaType.MOVIE, 19995, "Аватар", "Avatar", "2009-12-15", 30.0, 32000, "en", "ru-RU")
        val uk = ru.copy(queryLanguage = "uk-UA")
        val r = TitleMatcher.match(TitleParser.parse("Аватар"), CardKind.MOVIE, listOf(ru, uk))
        assertEquals(1, r.ranked.size)
        assertEquals(19995, r.accepted?.id)
    }

    @Test
    fun popularityDoesNotLiftWeakMatch() {
        val r = TitleMatcher.match(
            TitleParser.parse("Moonfall Origins"), CardKind.MOVIE,
            listOf(movie(406759, "Moonfall", "2022-02-03", 40000)),
        )
        assertNull(r.accepted)
        assertTrue(r.confidence < TitleMatcher.ACCEPT)
    }

    @Test
    fun seriesCardDoesNotTakeMovie() {
        val r = TitleMatcher.match(
            TitleParser.parse("Fargo"), CardKind.SERIES,
            listOf(movie(990062, "Fargo Diaries", "2012-05-25", 1800), movie(275, "Fargo", "1996-03-08", 500)),
        )
        assertNull(r.accepted)
    }

    @Test
    fun vlcMovieFileIsNotMatchedToOldSeriesOfSameName() {
        val parsed = TitleParser.parse("Wonder.Woman.1984.1080p.WEB-DL.mkv")
        val kind = SourceHints.classify(4, "content://org.videolan.vlc.thumbprovider/thumb/1").kind
        assertEquals(CardKind.UNKNOWN, kind)
        val series = tv(990250, "Wonder Woman", "1975-11-07", 150)
        val ww2017 = movie(297762, "Wonder Woman", "2017-05-30", 18000)
        val ww84 = movie(464052, "Wonder Woman 1984", "2020-12-16", 8000)

        val r = TitleMatcher.match(parsed, kind, listOf(ww2017, series, ww84))
        assertEquals(MediaType.MOVIE to 464052, r.accepted?.let { it.mediaType to it.id })
        // The file year is later than first_air_date: without SxxExx this earns the series no leniency, it is a plain mismatch.
        val seriesScore = r.ranked.first { it.candidate.mediaType == MediaType.TV }
        assertTrue(seriesScore.explanation, seriesScore.score < TitleMatcher.ACCEPT)

        // The right movie is not in the results: reject rather than take the old series.
        assertNull(TitleMatcher.match(parsed, kind, listOf(ww2017, series)).accepted)
    }

    @Test
    fun lenientSeriesYearOnlyForSeriesCardOrEpisode() {
        val fargo = listOf(tv(60622, "Fargo", "2014-04-15", 6000))
        assertEquals(60622, TitleMatcher.match(TitleParser.parse("Fargo (2016)"), CardKind.SERIES, fargo).accepted?.id)
        assertEquals(60622, TitleMatcher.match(TitleParser.parse("Fargo.2016.S01E02.1080p.mkv"), CardKind.UNKNOWN, fargo).accepted?.id)
        // Neither a type nor SxxExx: a year 2 off falls under the movie rule.
        assertNull(TitleMatcher.match(TitleParser.parse("Fargo (2016)"), CardKind.UNKNOWN, fargo).accepted)
    }

    @Test
    fun episodeWordInMovieTitleDoesNotPenalizeMovieCard() {
        val ru = { id: Int, title: String, original: String, date: String ->
            Candidate(MediaType.MOVIE, id, title, original, date, 10.0, 1000, "en", "ru-RU")
        }
        val starWars = listOf(
            ru(11, "Звёздные войны: Эпизод 4 – Новая надежда", "Star Wars", "1977-05-25"),
            ru(1891, "Звёздные войны: Эпизод 5 – Империя наносит ответный удар", "The Empire Strikes Back", "1980-05-20"),
        )
        // Both "… / Star Wars" and the Russian half alone (marker cut, episode=4) give a movie with type=0.
        for (raw in listOf("Звёздные войны: Эпизод 4 – Новая надежда / Star Wars", "Звёздные войны: Эпизод 4 – Новая надежда")) {
            val r = TitleMatcher.match(TitleParser.parse(raw), CardKind.MOVIE, starWars)
            assertEquals(raw, 11, r.accepted?.id)
            assertTrue(r.ranked.first().explanation, r.ranked.first().explanation.contains("kind=+0.05"))
        }
        val openSeason = listOf(
            ru(990261, "Сезон охоты 2", "Open Season 2", "2008-09-24"),
            ru(7484, "Сезон охоты", "Open Season", "2006-09-29"),
        )
        assertEquals(990261, TitleMatcher.match(TitleParser.parse("Сезон охоты 2 / Open Season 2"), CardKind.MOVIE, openSeason).accepted?.id)
    }

    @Test
    fun tmdbIdTagWins() {
        val r = TitleMatcher.match(
            TitleParser.parse("Something Else [tmdbid-603]"), CardKind.UNKNOWN,
            listOf(movie(603, "The Matrix", "1999-03-30", 25000), movie(604, "The Matrix Reloaded", "2003-05-15")),
        )
        assertEquals(603, r.accepted?.id)
        assertEquals(1.0, r.confidence, 1e-9)
    }

    @Test
    fun emptyCandidatesAndExplanations() {
        val none = TitleMatcher.match(TitleParser.parse("Up"), CardKind.MOVIE, emptyList())
        assertNull(none.accepted)
        assertEquals(0.0, none.confidence, 0.0)
        val r = TitleMatcher.match(TitleParser.parse("Up (2009)"), CardKind.MOVIE, listOf(movie(14160, "Up", "2009-05-28")))
        assertNotNull(r.accepted)
        assertTrue(r.ranked.first().explanation, r.ranked.first().explanation.startsWith("title=1.00 year=+0.10 kind=+0.05"))
    }

    @Test
    fun similarityBasics() {
        assertEquals(1.0, TitleMatcher.similarity("solaris", "solaris"), 0.0)
        assertEquals(1.0, TitleMatcher.similarity("station solaris", "solaris station"), 1e-9)
        assertTrue(TitleMatcher.similarity("gladiatir", "gladiator") > 0.9)
        assertTrue(TitleMatcher.similarity("avatar", "avatar the way of water") < 0.5)
        assertEquals(0.0, TitleMatcher.similarity("", "up"), 0.0)
    }
}
