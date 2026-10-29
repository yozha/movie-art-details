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

    // ── Same-named contenders within the margin ──

    @Test
    fun popularFilmOneYearOffBeatsObscureExactYear() {
        // Festival vs release dating: a 3-vote short dated exactly loses to the well-known film dated a year later.
        val crash = listOf(movie(990301, "Crash", "2004-06-01", 3), movie(1640, "Crash", "2005-05-06", 4000))
        for (kind in listOf(CardKind.MOVIE, CardKind.UNKNOWN)) {
            val r = TitleMatcher.match(TitleParser.parse("Crash (2004)"), kind, crash)
            assertEquals("kind=$kind", 1640, r.accepted?.id)
            // The scores are unchanged: the obscure film still leads the ranking.
            assertEquals(990301, r.ranked.first().candidate.id)
            assertTrue(r.reason, r.reason.contains("vote count"))
        }
        // Without a clear lead in votes the card stays unsure.
        val close = listOf(movie(990301, "Crash", "2004-06-01", 1500), movie(1640, "Crash", "2005-05-06", 4000))
        assertNull(TitleMatcher.match(TitleParser.parse("Crash (2004)"), CardKind.MOVIE, close).accepted)
    }

    @Test
    fun unknownTypeTieIsBrokenOnlyByLargeVoteLead() {
        val inception = listOf(movie(27205, "Inception", "2010-07-15", 36000), movie(990302, "Inception", "1980-01-01", 0))
        assertEquals(27205, TitleMatcher.match(TitleParser.parse("Inception"), CardKind.UNKNOWN, inception).accepted?.id)

        val dune = listOf(movie(438631, "Dune", "2021-09-15", 15000), movie(990303, "Dűne", "2020-01-01", 37))
        val file = TitleMatcher.match(TitleParser.parse("Dune.2021.2160p.WEB-DL.mkv"), CardKind.UNKNOWN, dune)
        assertEquals(438631, file.accepted?.id)

        // A movie and a series of the same name: an unknown type needs a 10× lead.
        val clash = { seriesVotes: Int -> listOf(movie(990304, "Synthetic Title", "2015-01-01", 5000), tv(990305, "Synthetic Title", "2016-01-01", seriesVotes)) }
        assertNull(TitleMatcher.match(TitleParser.parse("Synthetic Title"), CardKind.UNKNOWN, clash(600)).accepted)
        assertEquals(990304, TitleMatcher.match(TitleParser.parse("Synthetic Title"), CardKind.UNKNOWN, clash(400)).accepted?.id)
        // A known type keeps the 3× rule.
        val movies = listOf(movie(990304, "Synthetic Title", "2015-01-01", 5000), movie(990306, "Synthetic Title", "2016-01-01", 1500))
        assertEquals(990304, TitleMatcher.match(TitleParser.parse("Synthetic Title"), CardKind.MOVIE, movies).accepted?.id)
    }

    @Test
    fun episodeFileWithUnknownTypeUsesSeriesVoteRule() {
        val series = listOf(
            tv(246, "Avatar: The Last Airbender", "2005-02-21", 5200),
            tv(82452, "Avatar: The Last Airbender", "2024-02-22", 1400),
        )
        val parsed = TitleParser.parse("Avatar.The.Last.Airbender.S01E01.The.Boy.in.the.Iceberg.1080p.mkv")
        assertEquals(246, TitleMatcher.match(parsed, CardKind.UNKNOWN, series).accepted?.id)
    }

    @Test
    fun sameNamedFilmsArePickedByYearOrVotes() {
        val film = { id: Int, date: String, votes: Int ->
            Candidate(MediaType.MOVIE, id, "Тестовий фільм", "Тестовий фільм", date, votes / 10.0, votes, "uk", "uk-UA")
        }
        val films = listOf(film(990311, "1972-01-01", 14), film(990312, "2019-10-10", 154))
        assertEquals(990312, TitleMatcher.match(TitleParser.parse("Тестовий фільм"), CardKind.MOVIE, films).accepted?.id)
        assertEquals(990312, TitleMatcher.match(TitleParser.parse("Тестовий фільм"), CardKind.UNKNOWN, films).accepted?.id)
        assertEquals(990311, TitleMatcher.match(TitleParser.parse("Тестовий фільм (1972)"), CardKind.MOVIE, films).accepted?.id)
    }

    @Test
    fun domesticSeriesWinsOverForeignShownUnderSameName() {
        val series = { id: Int, original: String, date: String, votes: Int ->
            Candidate(MediaType.TV, id, "Тестовый сериал", original, date, votes / 10.0, votes, null, "ru-RU")
        }
        val domestic = series(990401, "Тестовый сериал", "2012-10-22", 113)
        val rest = listOf(series(990403, "Serial Testowy", "2021-09-04", 5), series(990404, "Тестовый сериал", "2017-01-01", 0))
        // 113 votes is not 3× the foreign 47, but it is 2× and the only original-title match with votes.
        val r = TitleMatcher.match(TitleParser.parse("Тестовый сериал"), CardKind.SERIES, listOf(series(990402, "Test Series", "2010-09-28", 47), domestic) + rest)
        assertEquals(990401, r.accepted?.id)
        // A foreign contender with more than half the votes keeps it ambiguous.
        assertNull(TitleMatcher.match(TitleParser.parse("Тестовый сериал"), CardKind.SERIES, listOf(series(990402, "Test Series", "2010-09-28", 60), domestic) + rest).accepted)
        // A Latin card gets no domestic preference.
        val latin = listOf(tv(990405, "Test Series", "2012-10-22", 113), tv(990406, "Test Series", "2010-09-28", 47, original = "Prueba"))
        assertNull(TitleMatcher.match(TitleParser.parse("Test Series"), CardKind.SERIES, latin).accepted)
    }

    // ── Year and title details ──

    @Test
    fun undatedCandidateIsPenalizedAgainstDatedCard() {
        val pilot = tv(990250, "Wonder Woman", null, 32)
        val ww84 = movie(464052, "Wonder Woman 1984", "2020-12-16", 8000)
        val r = TitleMatcher.match(TitleParser.parse("Wonder.Woman.1984.1080p.WEB-DL.mkv"), CardKind.UNKNOWN, listOf(pilot, ww84))
        assertEquals(464052, r.accepted?.id)
        val pilotScore = r.ranked.first { it.candidate.id == 990250 }
        assertTrue(pilotScore.explanation, pilotScore.explanation.contains("year=-0.10"))
        // A lone undated exact title with a known type still passes.
        assertEquals(990501, TitleMatcher.match(TitleParser.parse("Up (2009)"), CardKind.MOVIE, listOf(movie(990501, "Up", null))).accepted?.id)
    }

    @Test
    fun yearOneOffCountsOnlyForExactTitle() {
        val r = TitleMatcher.match(
            TitleParser.parse("Family.Holiday.2015.1080p.mkv"), CardKind.UNKNOWN,
            listOf(movie(990502, "Family Holidays", "2014-09-03", 9, original = "Vacaciones en familia")),
        )
        assertNull(r.accepted)
        assertTrue(r.ranked.first().explanation, r.ranked.first().explanation.contains("year=+0.00"))
        // A fuzzy title with the exact year is unaffected.
        val seven = TitleMatcher.match(TitleParser.parse("Seven (1995)"), CardKind.MOVIE, listOf(movie(807, "Se7en", "1995-09-22", 20000)))
        assertEquals(807, seven.accepted?.id)
        assertTrue(seven.ranked.first().explanation, seven.ranked.first().explanation.startsWith("title=0.90 year=+0.10"))
    }

    @Test
    fun numeralsAndSequelWordingMatch() {
        val dune2 = Candidate(MediaType.MOVIE, 693134, "Дюна: Часть вторая", "Dune: Part Two", "2024-02-27", 60.0, 6000, "en", "ru-RU")
        val r = TitleMatcher.match(TitleParser.parse("Дюна 2"), CardKind.MOVIE, listOf(dune2))
        assertEquals(693134, r.accepted?.id)
        assertTrue(r.ranked.first().explanation, r.ranked.first().explanation.startsWith("title=1.00"))

        val weeks = listOf(movie(18167, "Another 9 1/2 Weeks", "1997-01-01", 100), movie(10068, "Nine 1/2 Weeks", "1986-02-14", 1000))
        assertEquals(10068, TitleMatcher.match(TitleParser.parse("9 1/2 Weeks"), CardKind.MOVIE, weeks).accepted?.id)
        // A number in the candidate only is not the same film.
        assertNull(TitleMatcher.match(TitleParser.parse("Avatar 3"), CardKind.MOVIE, listOf(movie(19995, "Avatar", "2009-12-15", 32000))).accepted)
    }

    @Test
    fun mainTitleOfSubtitledCandidate() {
        val cosmos = listOf(
            tv(990601, "Cosmos: A Spacetime Odyssey", "2014-03-09", 1500),
            tv(990602, "Cosmos: A Personal Voyage", "1980-09-28", 600),
        )
        val r = TitleMatcher.match(TitleParser.parse("Cosmos.2014.S01E01.1080p.mkv"), CardKind.UNKNOWN, cosmos)
        assertEquals(990601, r.accepted?.id)

        val cyrillic = tv(990603, "Тестовый сериал. Подзаголовок", "2023-11-09", 300)
        assertEquals(990603, TitleMatcher.match(TitleParser.parse("Тестовый сериал (2023) 1 сезон 5 серия"), CardKind.UNKNOWN, listOf(cyrillic)).accepted?.id)

        // A franchise name alone is not enough for a series card.
        val airbender = TitleMatcher.match(TitleParser.parse("Avatar"), CardKind.SERIES, listOf(tv(246, "Avatar: The Last Airbender", "2005-02-21", 4000)))
        assertNull(airbender.accepted)
        assertTrue(airbender.confidence >= TitleMatcher.UNSURE && airbender.confidence < TitleMatcher.ACCEPT)
        // The exact title keeps a clear lead over a sequel that shares the main title.
        val dunes = listOf(movie(693134, "Dune: Part Two", "2024-02-27", 20000), movie(438631, "Dune", "2021-09-15", 12000))
        assertEquals(438631, TitleMatcher.match(TitleParser.parse("Dune"), CardKind.MOVIE, dunes).accepted?.id)
    }

    @Test
    fun russianEpisodeWordingFindsSeries() {
        val got = Candidate(MediaType.TV, 1399, "Игра престолов", "Game of Thrones", "2011-04-17", 300.0, 25000, "en", "ru-RU")
        for (raw in listOf("Игра престолов 3 сезон 12 серия", "Игра престолов. 3 сезон 12 серия")) {
            assertEquals(raw, 1399, TitleMatcher.match(TitleParser.parse(raw), CardKind.UNKNOWN, listOf(got)).accepted?.id)
        }
    }

    // ── Possessive credit prefix ──

    @Test
    fun creditPrefixIsIgnoredForTheLeader() {
        val raw = "Alfred Hitchcock's Rear Window 1954 1080p BluRay REMUX AVC FLAC 2.0 - SOMEGROUP"
        val windows = listOf(movie(567, "Rear Window", "1954-08-01", 10000), movie(990701, "Rear Window", "1998-11-22", 300))
        val r = TitleMatcher.match(TitleParser.parse(raw), CardKind.UNKNOWN, windows)
        assertEquals(567, r.accepted?.id)
        assertTrue(r.ranked.first().explanation, r.ranked.first().explanation.startsWith("title=0.95 year=+0.10"))
    }

    @Test
    fun creditPrefixDoesNotPromoteAnotherCandidate() {
        val r = TitleMatcher.match(
            TitleParser.parse("Alan Smithee's Vampires"), CardKind.MOVIE,
            listOf(movie(990702, "Alan Smithee's Fog", "2001-01-01"), movie(990703, "Vampires", "1998-10-30", 900)),
        )
        assertNull(r.accepted)
        assertEquals(990702, r.ranked.first().candidate.id)
    }

    @Test
    fun leadingArticleIsNotACredit() {
        val advocate = listOf(movie(990704, "Advocate", "1997-10-17", 500))
        assertNull(TitleMatcher.match(TitleParser.parse("The Devil's Advocate"), CardKind.MOVIE, advocate).accepted)
        // A possessive that is part of the title matches as a whole.
        val ferris = listOf(movie(9377, "Ferris Bueller's Day Off", "1986-06-11", 5000), movie(990705, "Day Off", "2010-01-01", 900))
        assertEquals(9377, TitleMatcher.match(TitleParser.parse("Ferris Bueller's Day Off"), CardKind.MOVIE, ferris).accepted?.id)
    }
}
