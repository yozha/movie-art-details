package io.github.yozha.movieartdetails.settings

import io.github.yozha.movieartdetails.model.DisplayLanguage

/** Language choice in settings. Persisted by [id]. */
enum class LanguageChoice(val id: String) {
    SYSTEM("system"), RU("ru"), UK("uk"), EN("en");

    /** System default: ru/uk/en from the system language, otherwise en-US. The system region is ignored (ru-UA → ru-RU). */
    fun resolve(systemLanguage: String): DisplayLanguage = when (this) {
        RU -> DisplayLanguage.RU
        UK -> DisplayLanguage.UK
        EN -> DisplayLanguage.EN
        SYSTEM -> when (systemLanguage.lowercase().substringBefore('-').substringBefore('_')) {
            "ru" -> DisplayLanguage.RU
            "uk" -> DisplayLanguage.UK
            else -> DisplayLanguage.EN
        }
    }

    companion object {
        fun of(id: String?): LanguageChoice? = entries.firstOrNull { it.id == id }
    }
}
