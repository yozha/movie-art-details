package io.github.yozha.movieartdetails.tmdb

import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.DisplayLanguage
import io.github.yozha.movieartdetails.model.DisplayLanguage.EN
import io.github.yozha.movieartdetails.model.DisplayLanguage.RU
import io.github.yozha.movieartdetails.model.DisplayLanguage.UK
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.SeasonInfo
import io.github.yozha.movieartdetails.model.TitleDetails
import io.github.yozha.movieartdetails.tmdb.TestData.details
import io.github.yozha.movieartdetails.tmdb.TestData.img
import io.github.yozha.movieartdetails.tmdb.TestData.tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

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
        assertEquals("w500", info.logoSize)
        assertEquals("/synthAvatarMain.jpg", info.backdropPath)
        assertEquals(19995, info.tmdbId)
        assertEquals(RU, info.language)
    }

    @Test
    fun avatarUkrainianHidesOverviewWithoutUkrainianText() {
        val info = build(avatar, UK)
        assertEquals("Аватар", info.title)
        // The uk overview is empty: hidden rather than shown in English next to a Ukrainian title.
        assertNull(info.overview)
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
        // TMDB ru names "НФ и Фэнтези" and "Боевик и Приключения" are replaced by id.
        assertEquals(listOf("Фантастика и фэнтези", "Драма", "Боевик и приключения"), ru.genres)
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
    fun returningSeriesShowsStartYearOnlyAndAiredSeasons() {
        val ru = build(bear, RU)
        assertEquals("Медведь", ru.title)
        assertEquals("2022", ru.yearText)
        // number_of_seasons is 3, but season 3 is only announced.
        assertEquals("2 сезона", ru.durationText)
        // The ru overview is empty: hidden, never the English one.
        assertNull(ru.overview)
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
        assertEquals("Synthetic overview: a young chef comes home to run the family sandwich shop.", en.overview)
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
        assertEquals(ShownTitle("Амели", "ru"), DisplayInfoBuilder.title(withRuEn, RU))
        // No uk title and a Latin original: the whole English title.
        assertEquals(ShownTitle("Amélie", "en"), DisplayInfoBuilder.title(withRuEn, UK))
        // No en title: a Latin original reads naturally in English.
        val onlyFr = base.copy(translations = listOf(tr("fr", "FR", "Original"), tr("de", "DE", "Deutsch")))
        assertEquals(ShownTitle("Original", "fr"), DisplayInfoBuilder.title(onlyFr, EN))
        // Nothing in the display language's script: the original as the last resort.
        assertEquals(ShownTitle("Original", "fr"), DisplayInfoBuilder.title(onlyFr, RU))
    }

    @Test
    fun cyrillicOriginalWinsOverEnglishForRussianAndUkrainian() {
        val russianFilm = details(title = "Солярис", originalTitle = "Солярис", originalLanguage = "ru", translations = listOf(tr("en", "US", "Solaris")))
        assertEquals(ShownTitle("Солярис", "ru"), DisplayInfoBuilder.title(russianFilm, RU))
        // No uk translation: the Cyrillic original reads naturally in Ukrainian, unlike the English title.
        assertEquals(ShownTitle("Солярис", "ru"), DisplayInfoBuilder.title(russianFilm, UK))
        assertEquals(ShownTitle("Solaris", "en"), DisplayInfoBuilder.title(russianFilm, EN))
    }

    @Test
    fun emptyTranslationTitleMeansOriginalInSameScript() {
        // TMDB leaves the en title empty when it equals the original.
        val d = details(originalTitle = "Inception", originalLanguage = "en", translations = listOf(tr("en", "US", null, "Overview")))
        assertEquals(ShownTitle("Inception", "en"), DisplayInfoBuilder.title(d, EN))
        // A title without letters fits any script.
        val digits = details(originalTitle = "1917", originalLanguage = "en", translations = listOf(tr("en", "US", "1917")))
        assertEquals(ShownTitle("1917", "en"), DisplayInfoBuilder.title(digits, UK))
    }

    @Test
    fun ukrainianBlockForRussianFilmWithoutUkrainianTranslationNeverMixesLanguages() {
        val d = details(
            title = "Сталкер", originalTitle = "Сталкер", originalLanguage = "ru",
            translations = listOf(tr("ru", "RU", "Сталкер", "Русское описание."), tr("en", "US", "Stalker", "English overview.")),
            logos = listOf(img("/en.png", "en", 1000, 300, vote = 9.0), img("/ru.png", "ru", 1000, 300, vote = 1.0)),
        )
        val uk = build(d, UK)
        assertEquals("Сталкер", uk.title)
        assertNull("no uk overview: hidden, not English", uk.overview)
        assertEquals("the logo is in the language of the shown original title", "/ru.png", uk.logoPath)

        val en = build(d, EN)
        assertEquals("Stalker", en.title)
        assertEquals("English overview.", en.overview)
        assertEquals("/en.png", en.logoPath)
    }

    @Test
    fun ukrainianTitleWithEmptyOverviewHidesOverview() {
        val d = details(
            originalTitle = "Сталкер", originalLanguage = "ru",
            translations = listOf(tr("uk", "UA", "Сталкер", null), tr("en", "US", "Stalker", "English overview.")),
        )
        assertEquals(ShownTitle("Сталкер", "uk"), DisplayInfoBuilder.title(d, UK))
        assertNull(DisplayInfoBuilder.overview(d, UK))
    }

    @Test
    fun englishTitleFallbackPrefersEnglishLogo() {
        val d = details(
            originalTitle = "千と千尋の神隠し", originalLanguage = "ja",
            translations = listOf(tr("en", "US", "Spirited Away", "English overview.")),
            logos = listOf(
                img("/ja.png", "ja", 1000, 300, vote = 9.0),
                img("/en.png", "en", 1000, 300, vote = 1.0),
            ),
        )
        // No ru title and no ru logo: the logo matches the English title, not the better-rated original one.
        val ru = build(d, RU)
        assertEquals("Spirited Away", ru.title)
        assertNull(ru.overview)
        assertEquals("/en.png", ru.logoPath)
        // A ru logo is in the language of the rest of the block, so it wins even without a ru title.
        val withRu = d.copy(logos = d.logos + img("/ru.png", "ru", 1000, 300, vote = 5.0))
        assertEquals("/ru.png", build(withRu, RU).logoPath)
        assertEquals("/en.png", build(withRu, UK).logoPath)
    }

    /** An empty translation title shows the original as text, but the display-language logo is still used. */
    @Test
    fun displayLanguageLogoUsedWhenTranslationTitleIsEmpty() {
        val d = details(
            originalTitle = "1917", originalLanguage = "en",
            translations = listOf(tr("en", "US", "1917", "English overview."), tr("ru", "RU", null, "Русское описание.")),
            logos = listOf(img("/en.png", "en", 1000, 300, vote = 9.0), img("/ru.png", "ru", 1000, 300, vote = 5.0)),
        )
        val ru = build(d, RU)
        assertEquals("1917", ru.title)
        assertEquals("/ru.png", ru.logoPath)
        assertEquals("/en.png", build(d, EN).logoPath)
    }

    @Test
    fun regionOfLocaleComesFirst() {
        val d = details(translations = listOf(
            tr("uk", "RU", "Title (uk-RU)", "Overview (uk-RU)"),
            tr("uk", "UA", "Ведмідь", null),
        ))
        assertEquals("Ведмідь", DisplayInfoBuilder.title(d, UK).text)
        // UA has no overview → any other region of the same language.
        assertEquals("Overview (uk-RU)", DisplayInfoBuilder.overview(d, UK))
    }

    @Test
    fun overviewHiddenWithoutDisplayLanguage() {
        val d = details(overview = "Fallback overview", translations = listOf(tr("de", "DE", "X", "Deutsch"), tr("en", "US", "X", "English")))
        assertNull(DisplayInfoBuilder.overview(d, RU))
        assertNull(DisplayInfoBuilder.overview(d, UK))
        assertEquals("English", DisplayInfoBuilder.overview(d, EN))
    }

    @Test
    fun responseOverviewUsedWhenTitleMadeInDisplayLanguage() {
        val d = details(originalLanguage = "ru", overview = "Описание", translations = listOf(tr("en", "US", "X", "English")))
        assertEquals("Описание", DisplayInfoBuilder.overview(d, RU))
        assertNull(DisplayInfoBuilder.overview(d, UK))
    }

    @Test
    fun withoutTranslationsArrayUsesResponseFields() {
        val d = details(title = "Аватар", originalTitle = "Avatar", overview = "  Response \n\n overview ")
        assertEquals(ShownTitle("Аватар", "ru"), DisplayInfoBuilder.title(d, RU))
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
    fun runtimeIsTheSameInEveryLanguage() {
        // TMDB's top-level runtime comes from the requested language's translation and may be wrong there.
        val d = details(
            originalLanguage = "ru", runtime = 75,
            translations = listOf(tr("uk", "UA", null, runtime = 75), tr("en", "US", null, runtime = 120), tr("ru", "RU", null, runtime = 139)),
        )
        assertEquals("2 ч 19 мин", DisplayInfoBuilder.durationText(d, RU))
        assertEquals("2 год 19 хв", DisplayInfoBuilder.durationText(d, UK))
        assertEquals("2h 19m", DisplayInfoBuilder.durationText(d, EN))
        // Original-language translation without a runtime → en-US → top-level.
        val noOriginal = d.copy(translations = listOf(tr("ru", "RU", null, runtime = null), tr("en", "GB", null, runtime = 90), tr("en", "US", null, runtime = 120)))
        assertEquals(120, DisplayInfoBuilder.runtimeMinutes(noOriginal))
        assertEquals(75, DisplayInfoBuilder.runtimeMinutes(d.copy(translations = listOf(tr("de", "DE", null, runtime = 100)))))
        assertNull(DisplayInfoBuilder.runtimeMinutes(details(runtime = null)))
    }

    @Test
    fun seasonCountSkipsSpecialsAndUnairedSeasons() {
        val today = LocalDate.of(2026, 10, 29)
        fun tv(seasons: List<SeasonInfo>, last: Int? = null, total: Int? = null) =
            DisplayInfoBuilder.durationText(details(type = MediaType.TV, seasons = total, seasonList = seasons, lastAiredSeason = last), EN, today)
        val aired = listOf(SeasonInfo(0, "2021-12-15", 1), SeasonInfo(1, "2022-02-17", 9), SeasonInfo(2, "2025-01-16", 10))
        assertEquals("2 seasons", tv(aired + SeasonInfo(3, null, 0), last = 2, total = 3))
        assertEquals("future air date", "2 seasons", tv(aired + SeasonInfo(3, "2026-11-01", 8), total = 3))
        assertEquals("no episodes yet", "2 seasons", tv(aired + SeasonInfo(3, "2026-10-01", 0), total = 3))
        assertEquals("airs today", "3 seasons", tv(aired + SeasonInfo(3, "2026-10-29", 8), total = 3))
        // Without usable seasons[]: last aired episode's season, then number_of_seasons.
        assertEquals("2 seasons", tv(listOf(SeasonInfo(1, null, 0)), last = 2, total = 3))
        assertEquals("3 seasons", tv(emptyList(), total = 3))
        assertEquals("unparsable date", "3 seasons", tv(listOf(SeasonInfo(1, "soon", 5)), total = 3))
        assertNull(tv(emptyList()))
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

    @Test
    fun russianAndUkrainianGenresInSentenceCaseKeepingAllCaps() {
        // Unknown ids (e.g. an old cache entry): sentence case only.
        val d = details(genres = listOf("Война и Политика", "НФ и Фэнтези", "телевизионный фильм"))
        assertEquals(listOf("Война и политика", "НФ и фэнтези", "Телевизионный фильм"), DisplayInfoBuilder.genres(d, RU))
        assertEquals(listOf("Екшн і пригоди"), DisplayInfoBuilder.genres(details(genres = listOf("Екшн і Пригоди")), UK))
        // en keeps TMDB's title case.
        assertEquals(listOf("Science Fiction", "Sci-Fi & Fantasy"), DisplayInfoBuilder.genres(details(genres = listOf("Science Fiction", "Sci-Fi & Fantasy")), EN))
    }

    @Test
    fun compositeTvGenresReplacedById() {
        val ru = details(genres = listOf("Боевик и Приключения", "НФ и Фэнтези", "Война и Политика", "драма"), genreIds = listOf(10759, 10765, 10768, 18))
        assertEquals(listOf("Боевик и приключения", "Фантастика и фэнтези", "Война и политика"), DisplayInfoBuilder.genres(ru, RU))
        val uk = details(genres = listOf("Науково фантастичний", "Екшн і Пригоди", "Політика та війна"), genreIds = listOf(10765, 10759, 10768))
        assertEquals(listOf("Фантастика і фентезі", "Бойовик і пригоди", "Війна і політика"), DisplayInfoBuilder.genres(uk, UK))
        val en = details(genres = listOf("Sci-Fi & Fantasy"), genreIds = listOf(10765))
        assertEquals(listOf("Sci-Fi & Fantasy"), DisplayInfoBuilder.genres(en, EN))
        // Ids that do not line up with the names are ignored.
        assertEquals(listOf("Драма"), DisplayInfoBuilder.genres(details(genres = listOf("драма"), genreIds = listOf(10765, 18)), RU))
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
