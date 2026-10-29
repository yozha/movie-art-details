package io.github.yozha.movieartdetails.settings

import io.github.yozha.movieartdetails.model.DisplayLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageChoiceTest {
    @Test
    fun systemMapsToRuUkEnElseEnglish() {
        assertEquals(DisplayLanguage.RU, LanguageChoice.SYSTEM.resolve("ru"))
        assertEquals(DisplayLanguage.RU, LanguageChoice.SYSTEM.resolve("ru-UA"))
        assertEquals(DisplayLanguage.UK, LanguageChoice.SYSTEM.resolve("uk"))
        assertEquals(DisplayLanguage.UK, LanguageChoice.SYSTEM.resolve("uk_UA"))
        assertEquals(DisplayLanguage.EN, LanguageChoice.SYSTEM.resolve("en"))
        assertEquals(DisplayLanguage.EN, LanguageChoice.SYSTEM.resolve("de"))
        assertEquals(DisplayLanguage.EN, LanguageChoice.SYSTEM.resolve(""))
    }

    @Test
    fun explicitChoiceIgnoresSystem() {
        assertEquals(DisplayLanguage.UK, LanguageChoice.UK.resolve("ru"))
        assertEquals(DisplayLanguage.EN, LanguageChoice.EN.resolve("uk"))
        assertEquals(DisplayLanguage.RU, LanguageChoice.RU.resolve("en"))
    }

    @Test
    fun idsRoundTrip() {
        LanguageChoice.entries.forEach { assertEquals(it, LanguageChoice.of(it.id)) }
        assertNull(LanguageChoice.of("de"))
        assertNull(LanguageChoice.of(null))
    }
}
