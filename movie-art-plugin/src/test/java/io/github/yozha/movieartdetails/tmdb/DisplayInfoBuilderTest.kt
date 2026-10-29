package io.github.yozha.movieartdetails.tmdb

import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.DisplayLanguage
import io.github.yozha.movieartdetails.model.DisplayLanguage.EN
import io.github.yozha.movieartdetails.model.DisplayLanguage.RU
import io.github.yozha.movieartdetails.model.DisplayLanguage.UK
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.TitleDetails
import io.github.yozha.movieartdetails.tmdb.TestData.details
import io.github.yozha.movieartdetails.tmdb.TestData.tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayInfoBuilderTest {

    private val all = AppearanceSettings(showDuration = true)
    private val avatar = TestData.fixtureDetails("movie_avatar_ru.synthetic.json", MediaType.MOVIE)
    private val got = TestData.fixtureDetails("tv_ended_ru.synthetic.json", MediaType.TV)
    private val bear = TestData.fixtureDetails("tv_returning_ru.synthetic.json", MediaType.TV)

    private fun build(d: TitleDetails, lang: DisplayLanguage, minVotes: Int = 10) =
        DisplayInfoBuilder.build(d, lang, minVotes, all)

    // ───────────────────────── fixtures ─────────────────────────

    @Test
    fun avatarRussian() {
        val info = build(avatar, RU)
        assertEquals("Аватар", info.title)
        assertEquals("2009", info.yearText)
        assertEquals("Фильм", info.typeText)
        assertEquals("2 ч 42 мин", info.durationText)
        assertEquals(listOf("Боевик", "Приключения", "Фантастика"), info.genres)
        assertEquals("TMDB 7,6/10", info.ratingText)
        assertEquals(
            "Synthetic ru-RU overview for Avatar, split across two lines.",
            info.overview,
        )
        assertEquals("/synthAvatarLogoRu.png", info.logoPath)
        assertEquals("/synthAvatarMain.jpg", info.backdropPath)
        assertEquals(19995, info.tmdbId)
        assertEquals(RU, info.language)
    }

    @Test
    fun avatarUkrainianFallsBackToEnglishOverview() {
        val info = build(avatar, UK)
        assertEquals("Аватар", info.title)
        // uk overview is empty → en-US (not en-GB).
        assertEquals("Synthetic overview: a former marine reaches a distant moon and ends up between two worlds.", info.overview)
        assertEquals("Фільм", info.typeText)
        assertEquals("2 год 42 хв", info.durationText)
        assertEquals("TMDB 7,6/10", info.ratingText)
        // No uk logo; the ru logo shows the same title, so it is acceptable.
        assertEquals("/synthAvatarLogoRu.png", info.logoPath)
    }

    @Test
    fun avatarEnglish() {
        val info = build(avatar, EN)
        assertEquals("Avatar", info.title)
        assertEquals("Movie", info.typeText)
        assertEquals("2h 42m", info.durationText)
        assertEquals("TMDB 7.6/10", info.ratingText)
        assertEquals("/synthAvatarLogoEn.png", info.logoPath)
        assertTrue(info.overview!!.startsWith("Synthetic overview"))
    }

    @Test
    fun endedSeriesShowsRangeAndSeasons() {
        val ru = build(got, RU)
        assertEquals("Игра престолов", ru.title)
        assertEquals("2011–2019", ru.yearText)
        assertEquals("Сериал", ru.typeText)
        assertEquals("8 сезонов", ru.durationText)
        assertEquals(listOf("Sci-Fi & Fantasy", "Драма", "Боевик и Приключения"), ru.genres)
        assertEquals("TMDB 8,5/10", ru.ratingText)
        assertEquals("/synthGotHigh.jpg", ru.backdropPath)
        // Only an en logo while the ru title is displayed → text.
        assertNull(ru.logoPath)

        val uk = build(got, UK)
        assertEquals("Гра престолів", uk.title)
        assertEquals("Серіал", uk.typeText)
        assertEquals("8 сезонів", uk.durationText)
        assertEquals("Synthetic uk-UA overview for Game of Thrones.", uk.overview)

        val en = build(got, EN)
        assertEquals("Game of Thrones", en.title)
        assertEquals("Series", en.typeText)
        assertEquals("8 seasons", en.durationText)
        assertEquals("/synthGotLogoEn.png", en.logoPath)
    }

    @Test
    fun returningSeriesShowsStartYearOnly() {
        val ru = build(bear, RU)
        assertEquals("Медведь", ru.title)
        assertEquals("2022", ru.yearText)
        assertEquals("2 сезона", ru.durationText)
        assertEquals("Synthetic overview: a young chef comes home to run the family sandwich shop.", ru.overview)
        assertNull("9 votes < threshold of 10", ru.ratingText)
        assertNull(ru.backdropPath)
        assertNull(ru.logoPath)

        assertEquals("TMDB 8,3/10", build(bear, RU, minVotes = 5).ratingText)

        val uk = build(bear, UK)
        assertEquals("Ведмідь", uk.title)
        assertEquals("2 сезони", uk.durationText)
        assertEquals("Synthetic uk-UA overview for The Bear.", uk.overview)

        val en = build(bear, EN)
        assertEquals("The Bear", en.title)
        assertEquals("2 seasons", en.durationText)
        assertEquals("/synthBearLogoEn.png", en.logoPath)
    }

    @Test
    fun appearanceFlagsHideFields() {
        val info = DisplayInfoBuilder.build(
            avatar, RU, 10,
            AppearanceSettings(showLogo = false, showYear = false, showGenres = false, showRating = false, showOverview = false),
        )
        assertNull(info.durationText) // showDuration is off by default
        assertNull(info.logoPath)
        assertNull(info.yearText)
        assertNull(info.ratingText)
        assertNull(info.overview)
        assertTrue(info.genres.isEmpty())
        assertEquals("Фильм", info.typeText)
        assertEquals("/synthAvatarMain.jpg", info.backdropPath)
    }

    // ───────────────────────── language fallback chain ─────────────────────────

    @Test
    fun titleChain() {
        val base = details(title = "Original", originalTitle = "Original", originalLanguage = "fr")
        val withRuEn = base.copy(translations = listOf(tr("en", "US", "Amélie"), tr("ru", "RU", "Амели")))
        assertEquals("Амели", DisplayInfoBuilder.title(withRuEn, RU))
        assertEquals("Amélie", DisplayInfoBuilder.title(withRuEn, UK))
        val onlyFr = base.copy(translations = listOf(tr("fr", "FR", "Original"), tr("de", "DE", "Deutsch")))
        assertEquals("Original", DisplayInfoBuilder.title(onlyFr, RU))
        // An original title in the display language wins over the English translation.
        val russianFilm = details(title = "Солярис", originalTitle = "Солярис", originalLanguage = "ru", translations = listOf(tr("en", "US", "Solaris")))
        assertEquals("Солярис", DisplayInfoBuilder.title(russianFilm, RU))
        assertEquals("Solaris", DisplayInfoBuilder.title(russianFilm, UK))
    }

    @Test
    fun regionOfLocaleComesFirst() {
        val d = details(translations = listOf(
            tr("uk", "RU", "Title (uk-RU)", "Overview (uk-RU)"),
            tr("uk", "UA", "Ведмідь", null),
        ))
        assertEquals("Ведмідь", DisplayInfoBuilder.title(d, UK))
        // UA has no overview → any other region of the same language comes before English.
        assertEquals("Overview (uk-RU)", DisplayInfoBuilder.overview(d, UK))
    }

    @Test
    fun overviewHiddenWithoutSelectedOrEnglish() {
        val d = details(overview = "Fallback overview", translations = listOf(tr("de", "DE", "X", "Deutsch")))
        assertNull(DisplayInfoBuilder.overview(d, RU))
    }

    @Test
    fun withoutTranslationsArrayUsesResponseFields() {
        val d = details(title = "Аватар", originalTitle = "Avatar", overview = "  Response \n\n overview ")
        assertEquals("Аватар", DisplayInfoBuilder.title(d, RU))
        assertEquals("Response overview", DisplayInfoBuilder.overview(d, RU))
    }

    // ───────────────────────── year, duration, genres ─────────────────────────

    @Test
    fun yearRules() {
        val tv = details(type = MediaType.TV, date = "2011-04-17", lastAirDate = "2019-05-19")
        assertEquals("2011–2019", DisplayInfoBuilder.yearText(tv.copy(status = "Ended")))
        assertEquals("2011–2019", DisplayInfoBuilder.yearText(tv.copy(status = "Canceled")))
        assertEquals("2011", DisplayInfoBuilder.yearText(tv.copy(status = "Returning Series")))
        assertEquals("2011", DisplayInfoBuilder.yearText(tv.copy(status = "In Production")))
        assertEquals("2011", DisplayInfoBuilder.yearText(tv.copy(status = "Ended", lastAirDate = "2011-12-01")))
        assertEquals("2011", DisplayInfoBuilder.yearText(tv.copy(status = "Ended", lastAirDate = null)))
        assertEquals("2009", DisplayInfoBuilder.yearText(details(date = "2009-12-15", lastAirDate = "2012-01-01", status = "Ended")))
        assertNull(DisplayInfoBuilder.yearText(details(date = null)))
        assertNull(DisplayInfoBuilder.yearText(details(date = "")))
    }

    @Test
    fun runtimeFormats() {
        val d = details(runtime = 139)
        assertEquals("2 ч 19 мин", DisplayInfoBuilder.durationText(d, RU))
        assertEquals("2 год 19 хв", DisplayInfoBuilder.durationText(d, UK))
        assertEquals("2h 19m", DisplayInfoBuilder.durationText(d, EN))
        assertEquals("2 ч", DisplayInfoBuilder.durationText(details(runtime = 120), RU))
        assertEquals("45 мин", DisplayInfoBuilder.durationText(details(runtime = 45), RU))
        assertEquals("45m", DisplayInfoBuilder.durationText(details(runtime = 45), EN))
        assertNull(DisplayInfoBuilder.durationText(details(runtime = null), RU))
        assertNull(DisplayInfoBuilder.durationText(details(runtime = 0), RU))
        // Series: seasons only, runtime is ignored.
        assertNull(DisplayInfoBuilder.durationText(details(type = MediaType.TV, runtime = 50, seasons = null), RU))
    }

    @Test
    fun seasonPlurals() {
        fun s(n: Int, l: DisplayLanguage) = DisplayInfoBuilder.durationText(details(type = MediaType.TV, seasons = n), l)
        assertEquals("1 сезон", s(1, RU))
        assertEquals("3 сезона", s(3, RU))
        assertEquals("5 сезонов", s(5, RU))
        assertEquals("11 сезонов", s(11, RU))
        assertEquals("12 сезонов", s(12, RU))
        assertEquals("21 сезон", s(21, RU))
        assertEquals("22 сезона", s(22, RU))
        assertEquals("1 сезон", s(1, UK))
        assertEquals("3 сезони", s(3, UK))
        assertEquals("5 сезонів", s(5, UK))
        assertEquals("14 сезонів", s(14, UK))
        assertEquals("1 season", s(1, EN))
        assertEquals("3 seasons", s(3, EN))
        assertNull(s(0, RU))
    }

    @Test
    fun genresCapitalizedAtMostThree() {
        val d = details(genres = listOf("драма", " ", "драма", "комедия", "мелодрама", "триллер"))
        assertEquals(listOf("Драма", "Комедия", "Мелодрама"), DisplayInfoBuilder.genres(d, RU))
    }

    // ───────────────────────── rating ─────────────────────────

    @Test
    fun ratingFormatting() {
        fun r(avg: Double, votes: Int, l: DisplayLanguage = RU, min: Int = 10) =
            DisplayInfoBuilder.ratingText(details(voteAverage = avg, voteCount = votes), l, min)
        assertNull("0 votes", r(7.5, 0, min = 0))
        assertNull("below threshold", r(7.5, 9))
        assertEquals("TMDB 7,5/10", r(7.5, 10))
        assertEquals("TMDB 7,3/10", r(7.25, 100))
        assertEquals("TMDB 7.3/10", r(7.25, 100, EN))
        assertEquals("TMDB 7,0/10", r(7.04, 100))
        assertEquals("TMDB 7,6/10", r(7.588, 100, UK))
        assertEquals("TMDB 10,0/10", r(10.0, 100))
        assertEquals("TMDB 10.0/10", r(10.0, 100, EN))
        assertEquals("TMDB 10,0/10", r(9.96, 100))
        assertNull("no rating", r(0.0, 100))
    }

    // ───────────────────────── fingerprint ─────────────────────────

    @Test
    fun fingerprintIsStableAndSensitive() {
        val a = build(avatar, RU)
        assertEquals(a.fingerprint, build(avatar, RU).fingerprint)
        assertEquals(32, a.fingerprint.length)
        assertNotEquals(a.fingerprint, build(avatar.copy(voteAverage = 7.9), RU).fingerprint)
        assertNotEquals(a.fingerprint, build(avatar, UK).fingerprint)
        assertNotEquals(a.fingerprint, build(avatar.copy(backdropPath = "/other.jpg", backdrops = emptyList()), RU).fingerprint)
    }
}
