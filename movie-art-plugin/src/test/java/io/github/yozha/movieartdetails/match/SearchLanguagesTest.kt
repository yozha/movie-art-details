package io.github.yozha.movieartdetails.match

import io.github.yozha.movieartdetails.model.Script
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchLanguagesTest {
    @Test
    fun languagesByScript() {
        assertEquals(listOf("ru-RU"), SearchLanguages.forScript(Script.CYRILLIC_RU))
        assertEquals(listOf("uk-UA"), SearchLanguages.forScript(Script.CYRILLIC_UK))
        assertEquals(listOf("ru-RU", "uk-UA"), SearchLanguages.forScript(Script.CYRILLIC_AMBIGUOUS))
        assertEquals(listOf("en-US"), SearchLanguages.forScript(Script.LATIN))
        assertEquals(listOf("en-US"), SearchLanguages.forScript(Script.OTHER))
        assertEquals(listOf("en-US"), SearchLanguages.forScript(Script.NONE))
        assertEquals(listOf("uk-UA"), SearchLanguages.forScript(TitleParser.parse("Ведмідь / The Bear").script))
    }
}
