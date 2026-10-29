package io.github.yozha.movieartdetails.match

import io.github.yozha.movieartdetails.model.DisplayLanguage
import io.github.yozha.movieartdetails.model.Script

/** Search query language, chosen by the script of the card title rather than by the display language. */
object SearchLanguages {
    fun forScript(script: Script): List<String> = when (script) {
        Script.CYRILLIC_RU -> listOf(DisplayLanguage.RU.tmdb)
        Script.CYRILLIC_UK -> listOf(DisplayLanguage.UK.tmdb)
        // Ambiguous Cyrillic is compared against both the Russian and the Ukrainian candidate titles.
        Script.CYRILLIC_AMBIGUOUS -> listOf(DisplayLanguage.RU.tmdb, DisplayLanguage.UK.tmdb)
        Script.LATIN, Script.OTHER, Script.NONE -> listOf(DisplayLanguage.EN.tmdb)
    }
}
