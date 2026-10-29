package io.github.yozha.movieartdetails.tmdb

import io.github.yozha.movieartdetails.model.DisplayLanguage
import io.github.yozha.movieartdetails.model.DisplayLanguage.EN
import io.github.yozha.movieartdetails.model.DisplayLanguage.RU
import io.github.yozha.movieartdetails.model.DisplayLanguage.UK
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.TitleDetails
import io.github.yozha.movieartdetails.tmdb.TestData.details
import io.github.yozha.movieartdetails.tmdb.TestData.img
import io.github.yozha.movieartdetails.tmdb.TestData.tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageSelectorTest {

    // ───────────────────────── backdrop ─────────────────────────

    @Test
    fun mainBackdropKeptWhenTextlessAndWide() {
        val d = details(backdropPath = "/main.jpg", backdrops = listOf(
            img("/main.jpg", null, 1920, 1080, vote = 5.0),
            img("/better.jpg", null, 3840, 2160, vote = 6.0),
        ))
        assertEquals("/main.jpg", ImageSelector.backdrop(d))
    }

    @Test
    fun textlessWideChosenByVoteWhenMainHasText() {
        val d = details(backdropPath = "/main.jpg", backdrops = listOf(
            img("/main.jpg", "en", 3840, 2160, vote = 9.0),
            img("/low.jpg", null, 1920, 1080, vote = 5.1),
            img("/high.jpg", null, 1920, 1080, vote = 5.7),
            img("/small.jpg", null, 1280, 720, vote = 8.0),
        ))
        assertEquals("/high.jpg", ImageSelector.backdrop(d))
    }

    @Test
    fun narrowMainLosesToWideTextless() {
        val d = details(backdropPath = "/main.jpg", backdrops = listOf(
            img("/main.jpg", null, 1280, 720, vote = 9.0),
            img("/wide.jpg", null, 2560, 1440, vote = 1.0),
        ))
        assertEquals("/wide.jpg", ImageSelector.backdrop(d))
    }

    @Test
    fun largestTextlessWhenNoneIsWideEnough() {
        val d = details(backdropPath = "/main.jpg", backdrops = listOf(
            img("/main.jpg", "ru", 1920, 1080),
            img("/a.jpg", null, 1280, 720, vote = 9.0),
            img("/b.jpg", null, 1600, 900, vote = 1.0),
            img("/c.svg", null, 4000, 2250),
        ))
        assertEquals("/b.jpg", ImageSelector.backdrop(d))
    }

    @Test
    fun fallsBackToBackdropPath() {
        assertEquals("/main.jpg", ImageSelector.backdrop(details(backdropPath = "/main.jpg", backdrops = listOf(img("/main.jpg", "en", 3840, 2160)))))
        assertNull(ImageSelector.backdrop(details(backdropPath = null)))
    }

    // ───────────────────────── logo ─────────────────────────

    /**
     * Logo path for a shown title in [language], in a block in [display]. By default the block is in the shown
     * title's language, or English for a title in a language without a block (fr, ja) or an unknown one.
     */
    private fun logo(d: TitleDetails, language: String?, title: String, display: DisplayLanguage = displayFor(language)) =
        ImageSelector.logo(d, ShownTitle(title, language), display)?.path

    private fun displayFor(language: String?) = DisplayLanguage.entries.firstOrNull { it.iso639 == language } ?: EN

    @Test
    fun svgLogoDownloadedAsPngRendition() {
        val onlySvg = details(logos = listOf(img("/logo.svg", "ru", 1000, 300)))
        assertEquals(LogoPick("/logo.png", "w500"), ImageSelector.logo(onlySvg, ShownTitle("Аватар", "ru"), RU))
        // SVG and PNG compete on equal terms.
        val both = details(logos = listOf(img("/a.svg", "ru", 1000, 300, vote = 9.0), img("/b.png", "ru", 1000, 300, vote = 1.0)))
        assertEquals("/a.png", logo(both, "ru", "Аватар"))
        assertNull("other formats are skipped", logo(details(logos = listOf(img("/c.jpg", "ru", 1000, 300))), "ru", "Аватар"))
    }

    @Test
    fun displayLanguagePreferredByVoteThenWidth() {
        val d = details(logos = listOf(
            img("/en.png", "en", 1000, 300, vote = 9.0),
            img("/ru-low.png", "ru", 1000, 300, vote = 5.0),
            img("/ru-high-narrow.png", "ru", 800, 240, vote = 5.5),
            img("/ru-high-wide.png", "ru", 1200, 360, vote = 5.5),
        ))
        assertEquals("/ru-high-wide.png", logo(d, "ru", "Аватар"))
    }

    @Test
    fun displayLanguageLogoComesFirst() {
        val d = details(
            originalTitle = "Солярис", originalLanguage = "ru",
            translations = listOf(tr("en", "US", "Solaris")),
            logos = listOf(img("/uk.png", "uk", 1000, 300), img("/ru.png", "ru", 1000, 300, vote = 9.0), img("/en.png", "en", 1000, 300)),
        )
        // A uk block shows the Russian original title, but a uk logo is in the language of the rest of the block.
        assertEquals("/uk.png", logo(d, "ru", "Солярис", UK))
        assertEquals("/ru.png", logo(d, "ru", "Солярис", RU))
        assertEquals("/en.png", logo(d, "en", "Solaris", EN))
    }

    @Test
    fun logoInLanguageOfShownTitleComesNext() {
        val d = details(
            originalTitle = "Солярис", originalLanguage = "ru",
            translations = listOf(tr("en", "US", "Solaris")),
            logos = listOf(img("/ru.png", "ru", 1000, 300), img("/en.png", "en", 1000, 300, vote = 9.0)),
        )
        // No uk logo: the original title is shown, so its language's logo, despite the better-rated en one.
        assertEquals("/ru.png", logo(d, "ru", "Солярис", UK))
        // The English title is shown in a uk block: the English logo.
        assertEquals("/en.png", logo(d.copy(logos = listOf(img("/en.png", "en", 1000, 300))), "en", "Solaris", UK))
    }

    /** TMDB leaves a translation title empty when it equals the original: the text title falls back to it. */
    @Test
    fun displayLanguageLogoWinsWhenTranslationTitleIsEmpty() {
        val shogun = details(
            type = MediaType.TV, originalTitle = "Shōgun", originalLanguage = "en",
            translations = listOf(tr("en", "US", "Shōgun", "Overview."), tr("ru", "RU", null, "Описание.")),
            logos = listOf(img("/ru.png", "ru", 1000, 300, vote = 5.0), img("/en.png", "en", 1000, 300, vote = 9.0)),
        )
        assertEquals(ShownTitle("Shōgun", "en"), DisplayInfoBuilder.title(shogun, RU))
        assertEquals("/ru.png", shownLogo(shogun, RU))
        assertEquals("/en.png", shownLogo(shogun, EN))
        // No uk logo: the English one matches the shown English title.
        assertEquals("/en.png", shownLogo(shogun, UK))

        // A title without letters is shown as the original in every language.
        val digits = details(
            originalTitle = "1917", originalLanguage = "en",
            translations = listOf(tr("en", "US", "1917"), tr("ru", "RU", null, "Описание."), tr("uk", "UA", null, "Опис.")),
            logos = listOf(
                img("/en.png", "en", 1000, 300, vote = 9.0),
                img("/ru.png", "ru", 1000, 300, vote = 5.0),
                img("/uk.png", "uk", 1000, 300, vote = 4.0),
            ),
        )
        assertEquals(ShownTitle("1917", "en"), DisplayInfoBuilder.title(digits, RU))
        assertEquals(ShownTitle("1917", "en"), DisplayInfoBuilder.title(digits, UK))
        assertEquals("/ru.png", shownLogo(digits, RU))
        assertEquals("/uk.png", shownLogo(digits, UK))
        assertEquals("/en.png", shownLogo(digits, EN))
    }

    private fun shownLogo(d: TitleDetails, display: DisplayLanguage) =
        ImageSelector.logo(d, DisplayInfoBuilder.title(d, display), display)?.path

    @Test
    fun foreignLogoOnlyWhenShownTitleMatches() {
        val d = details(
            originalTitle = "Avatar", originalLanguage = "en",
            translations = listOf(tr("en", "US", "Avatar"), tr("ru", "RU", "Аватар")),
            logos = listOf(img("/en.png", "en", 1000, 300)),
        )
        assertNull(logo(d, "ru", "Аватар"))
        // A uk title spelled like the English one may use the English logo.
        assertEquals("/en.png", logo(d, "uk", "Avatar"))
        assertEquals("/en.png", logo(d, "en", "Avatar"))
        // Matching ignores case and punctuation.
        assertEquals("/en.png", logo(d, "uk", "AVATAR!"))
        assertEquals("/en.png", logo(d, null, "Avatar", UK))
    }

    @Test
    fun originalLanguageLogoMatchesOriginalTitle() {
        val d = details(originalTitle = "Amélie", originalLanguage = "fr", logos = listOf(img("/fr.png", "fr", 900, 300)))
        assertEquals("/fr.png", logo(d, "fr", "Amélie"))
        // An English block showing the same title.
        assertEquals("/fr.png", logo(d, "en", "Amélie"))
    }

    @Test
    fun emptyTranslationTitleMatchesOriginalInSameScriptOnly() {
        // TMDB leaves a translation title empty when it equals the original. A uk block, so that neither logo is in
        // the display language and only the title check applies.
        val d = details(
            originalTitle = "Amélie", originalLanguage = "fr",
            translations = listOf(tr("en", "US", null, "Overview"), tr("ru", "RU", null, "Описание")),
            logos = listOf(img("/en.png", "en", 900, 300)),
        )
        assertEquals("/en.png", logo(d, "fr", "Amélie", UK))
        // A Latin original is not the Russian title, even if the ru title is empty.
        val ru = d.copy(logos = listOf(img("/ru.png", "ru", 900, 300)))
        assertNull(logo(ru, "fr", "Amélie", UK))
        // No translation at all is not "same as the original".
        val noEn = d.copy(translations = emptyList())
        assertNull(logo(noEn, "fr", "Amélie", UK))
    }

    @Test
    fun textlessLanguageLogoIgnored() {
        val d = details(logos = listOf(img("/x.png", null, 1000, 300)))
        assertNull(logo(d, "ru", "Original"))
    }

    @Test
    fun aspectAndHeightChecks() {
        fun pick(w: Int, h: Int, path: String = "/l.png") = ImageSelector.logo(details(logos = listOf(img(path, "ru", w, h))), ShownTitle("X", "ru"), RU)
        assertNull("too tall", pick(300, 500))
        assertNull("aspect > 14", pick(1500, 100))
        assertNull("source too small", pick(200, 50))
        assertNull("wide source under 60 px, never upscaled", pick(650, 55))
        assertNull("wide SVG only comes at w500: 500 px / 13 < 60 px", pick(1300, 100, "/l.svg"))
        assertNull("no dimensions", pick(0, 0))
        assertEquals(LogoPick("/l.png", "w500"), pick(400, 400))
        assertEquals(LogoPick("/l.png", "w500"), pick(1000, 300))
    }

    @Test
    fun wideLogoAcceptedAndDownloadedAtOriginal() {
        fun pick(w: Int, h: Int) = ImageSelector.logo(details(logos = listOf(img("/l.png", "ru", w, h))), ShownTitle("X", "ru"), RU)
        // Aspect 12.4: 883 px wide in the default block → 71 px tall; w500 would give only 40 px.
        assertEquals(LogoPick("/l.png", "original"), pick(1240, 100))
        assertEquals(LogoPick("/l.png", "original"), pick(900, 105))
        assertEquals(LogoPick("/l.png", "original"), pick(1400, 100))
        // Wide, but no wider than w500: nothing to gain from the original.
        assertEquals(LogoPick("/l.png", "w500"), pick(450, 100))
    }

    /**
     * An original logo over the download size cap falls back to w500. A logo that is too small there goes after one
     * that is usable at both sizes, whatever the votes.
     */
    @Test
    fun logoUsableAtW500FallbackPreferred() {
        fun pick(vararg logos: Pair<Int, Int>) = ImageSelector.logo(
            details(logos = logos.mapIndexed { i, (w, h) -> img("/$w.png", "ru", w, h, vote = 9.0 - i) }),
            ShownTitle("X", "ru"), RU,
        )
        // 10:1 is only 50 px tall at w500: the lower-rated 3:1 logo wins.
        assertEquals(LogoPick("/900.png", "w500"), pick(2000 to 200, 900 to 300))
        // 5:1 is 100 px tall at w500: still picked first, and still at original.
        assertEquals(LogoPick("/1500.png", "original"), pick(1500 to 300, 900 to 300))
        // The boundary is 500 / 60 ≈ 8.33: 8.2:1 is 61 px tall at w500, 8.4:1 only 59.5 px.
        assertEquals(LogoPick("/820.png", "original"), pick(820 to 100, 900 to 300))
        assertEquals(LogoPick("/900.png", "w500"), pick(840 to 100, 900 to 300))
        // With nothing else in the language, the wide logo is still picked: it works whenever the original fits the cap.
        assertEquals(LogoPick("/840.png", "original"), pick(840 to 100))
    }

    @Test
    fun w500FallbackOrderStaysWithinLanguageStep() {
        val d = details(
            originalTitle = "Солярис", originalLanguage = "ru",
            translations = listOf(tr("en", "US", "Solaris")),
            logos = listOf(img("/uk-wide.png", "uk", 2000, 200, vote = 1.0), img("/ru.png", "ru", 900, 300, vote = 9.0)),
        )
        // A display-language logo still comes before one in the language of the shown title.
        assertEquals(LogoPick("/uk-wide.png", "original"), ImageSelector.logo(d, ShownTitle("Солярис", "ru"), UK))
    }
}
