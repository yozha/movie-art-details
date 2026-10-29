package io.github.yozha.movieartdetails.tmdb

import io.github.yozha.movieartdetails.model.DisplayLanguage
import io.github.yozha.movieartdetails.model.MediaType
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

/** Info panel strings in the display language. No Android resources, so they can be checked in JVM tests. */
internal object Localization {

    fun locale(lang: DisplayLanguage): Locale = Locale.forLanguageTag(lang.tmdb)

    fun typeText(type: MediaType, lang: DisplayLanguage): String = when (type) {
        MediaType.MOVIE -> when (lang) { DisplayLanguage.RU -> "Фильм"; DisplayLanguage.UK -> "Фільм"; DisplayLanguage.EN -> "Movie" }
        MediaType.TV -> when (lang) { DisplayLanguage.RU -> "Сериал"; DisplayLanguage.UK -> "Серіал"; DisplayLanguage.EN -> "Series" }
    }

    /** Hours and minutes, e.g. "2h 19m"; zero parts are omitted. */
    fun runtime(minutes: Int, lang: DisplayLanguage): String {
        val h = minutes / 60
        val m = minutes % 60
        val (hu, mu, sep) = when (lang) {
            DisplayLanguage.RU -> Triple("ч", "мин", " ")
            DisplayLanguage.UK -> Triple("год", "хв", " ")
            DisplayLanguage.EN -> Triple("h", "m", "")
        }
        return listOfNotNull(
            if (h > 0) "$h$sep$hu" else null,
            if (m > 0 || h == 0) "$m$sep$mu" else null,
        ).joinToString(" ")
    }

    /** Season count, e.g. "3 seasons", with the correct plural form. */
    fun seasons(n: Int, lang: DisplayLanguage): String = when (lang) {
        DisplayLanguage.RU -> "$n ${slavic(n, "сезон", "сезона", "сезонов")}"
        DisplayLanguage.UK -> "$n ${slavic(n, "сезон", "сезони", "сезонів")}"
        DisplayLanguage.EN -> if (n == 1) "1 season" else "$n seasons"
    }

    /** "TMDB 7.6/10": HALF_UP to one decimal place; ru/uk use a decimal comma, en a dot. */
    fun rating(voteAverage: Double, lang: DisplayLanguage): String {
        val value = BigDecimal.valueOf(voteAverage.coerceIn(0.0, 10.0)).setScale(1, RoundingMode.HALF_UP).toPlainString()
        val shown = if (lang == DisplayLanguage.EN) value else value.replace('.', ',')
        return "TMDB $shown/10"
    }

    /** ru/uk plural forms: 1, 21 → one; 2–4, 22–24 → few; 0, 5–20, 11–14 → many. */
    private fun slavic(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }
}
