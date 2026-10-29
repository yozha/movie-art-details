package io.github.yozha.movieartdetails.tmdb

import io.github.yozha.movieartdetails.model.DisplayLanguage.EN
import io.github.yozha.movieartdetails.model.DisplayLanguage.RU
import io.github.yozha.movieartdetails.model.DisplayLanguage.UK
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

    @Test
    fun svgSkipped() {
        val onlySvg = details(logos = listOf(img("/logo.svg", "ru", 1000, 300)))
        assertNull(ImageSelector.logo(onlySvg, RU, "Аватар"))
        val both = details(logos = listOf(img("/logo.svg", "ru", 1000, 300, vote = 9.0), img("/logo.png", "ru", 1000, 300, vote = 1.0)))
        assertEquals("/logo.png", ImageSelector.logo(both, RU, "Аватар"))
    }

    @Test
    fun displayLanguagePreferredByVoteThenWidth() {
        val d = details(logos = listOf(
            img("/en.png", "en", 1000, 300, vote = 9.0),
            img("/ru-low.png", "ru", 1000, 300, vote = 5.0),
            img("/ru-high-narrow.png", "ru", 800, 240, vote = 5.5),
            img("/ru-high-wide.png", "ru", 1200, 360, vote = 5.5),
        ))
        assertEquals("/ru-high-wide.png", ImageSelector.logo(d, RU, "Аватар"))
    }

    @Test
    fun foreignLogoOnlyWhenDisplayedTitleMatches() {
        val d = details(
            originalTitle = "Avatar", originalLanguage = "en",
            translations = listOf(tr("en", "US", "Avatar"), tr("ru", "RU", "Аватар")),
            logos = listOf(img("/en.png", "en", 1000, 300)),
        )
        assertNull(ImageSelector.logo(d, RU, "Аватар"))
        // No translation in the display language: the English title is shown, so a Latin-script logo fits.
        assertEquals("/en.png", ImageSelector.logo(d, UK, "Avatar"))
        assertEquals("/en.png", ImageSelector.logo(d, EN, "Avatar"))
        // Matching ignores case and punctuation.
        assertEquals("/en.png", ImageSelector.logo(d, UK, "AVATAR!"))
    }

    @Test
    fun originalLanguageLogoMatchesOriginalTitle() {
        val d = details(originalTitle = "Amélie", originalLanguage = "fr", logos = listOf(img("/fr.png", "fr", 900, 300)))
        assertEquals("/fr.png", ImageSelector.logo(d, RU, "Amélie"))
    }

    @Test
    fun textlessLanguageLogoIgnored() {
        val d = details(logos = listOf(img("/x.png", null, 1000, 300)))
        assertNull(ImageSelector.logo(d, RU, "Original"))
    }

    @Test
    fun aspectAndHeightChecks() {
        fun pick(w: Int, h: Int) = ImageSelector.logo(details(logos = listOf(img("/l.png", "ru", w, h))), RU, "X")
        assertNull("too tall", pick(300, 500))
        assertNull("aspect > 9", pick(1000, 100))
        assertNull("under 60 px after fitting", pick(900, 105))
        assertNull("source too small", pick(200, 50))
        assertNull("no dimensions", pick(0, 0))
        assertEquals("/l.png", pick(400, 400))
        assertEquals("/l.png", pick(1000, 300))
    }
}
