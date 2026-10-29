package io.github.yozha.movieartdetails.tmdb

import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.DisplayInfo
import io.github.yozha.movieartdetails.model.DisplayLanguage
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.TitleDetails
import io.github.yozha.movieartdetails.model.Translation
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale

/** The title to show and the language its text is in (null when unknown). */
data class ShownTitle(val text: String, val language: String?)

/**
 * Builds the info panel strings from TMDB details.
 * Fields turned off in [AppearanceSettings] (show*) come back as null right away; typeText is never cleared
 * (it is not nullable), so the renderer decides whether to show it based on showType.
 *
 * Languages are never mixed inside a text: a missing translation falls back to a whole title in another
 * language (see [title]), while a missing overview is hidden rather than shown in another language.
 */
object DisplayInfoBuilder {

    private val ENDED_STATUSES = setOf("ended", "canceled", "cancelled")

    fun build(
        details: TitleDetails,
        language: DisplayLanguage,
        minVoteCount: Int,
        appearance: AppearanceSettings,
        today: LocalDate = LocalDate.now(),
    ): DisplayInfo {
        val shown = title(details, language)
        val year = if (appearance.showYear) yearText(details) else null
        val type = Localization.typeText(details.mediaType, language)
        val duration = if (appearance.showDuration) durationText(details, language, today) else null
        val genres = if (appearance.showGenres) genres(details, language) else emptyList()
        val rating = if (appearance.showRating) ratingText(details, language, minVoteCount) else null
        val overview = if (appearance.showOverview) overview(details, language) else null
        val logo = if (appearance.showLogo) ImageSelector.logo(details, shown, language) else null
        val backdrop = ImageSelector.backdrop(details)
        val fingerprint = fingerprint(
            details.mediaType.name, details.id.toString(), language.name, shown.text, year, type, duration,
            genres.joinToString("\u0002"), rating, overview, logo?.path, logo?.size, backdrop,
        )
        return DisplayInfo(
            mediaType = details.mediaType,
            tmdbId = details.id,
            language = language,
            title = shown.text,
            yearText = year,
            typeText = type,
            durationText = duration,
            genres = genres,
            ratingText = rating,
            overview = overview,
            logoPath = logo?.path,
            backdropPath = backdrop,
            fingerprint = fingerprint,
            logoSize = logo?.size ?: ImageSelector.LOGO_SIZE,
        )
    }

    /**
     * Title: translation in the selected language → the original title, if it is in the selected language or
     * written in its script (a Cyrillic original for ru/uk, a Latin one for en) → English translation →
     * original_title/original_name. Without a translations array (old cache) the response title is used:
     * either a translation or the original that TMDB fell back to.
     * The returned language lets the logo match the language of the shown title.
     */
    fun title(details: TitleDetails, language: DisplayLanguage): ShownTitle {
        translationsFor(details.translations, language.iso639, language.region).firstNotNullOfOrNull { it.title.clean() }
            ?.let { return ShownTitle(it, language.iso639) }
        details.originalTitle.clean()
            ?.takeIf { details.originalLanguage.equals(language.iso639, ignoreCase = true) || isWrittenIn(it, language.iso639) }
            ?.let { return ShownTitle(it, details.originalLanguage?.lowercase() ?: language.iso639) }
        if (details.translations.isEmpty()) {
            details.title.clean()?.let { return ShownTitle(it, responseTitleLanguage(details, it, language)) }
        }
        translationsFor(details.translations, "en", "US").firstNotNullOfOrNull { it.title.clean() }
            ?.let { return ShownTitle(it, "en") }
        details.originalTitle.clean()?.let { return ShownTitle(it, details.originalLanguage?.lowercase()) }
        return ShownTitle(details.title.clean().orEmpty(), null)
    }

    /**
     * Overview in the selected language only: its translation (locale region first), or the response overview when
     * the title was made in that language. Hidden otherwise; never shown in another language.
     * Without a translations array (old cache) the response overview is used.
     */
    fun overview(details: TitleDetails, language: DisplayLanguage): String? =
        translationsFor(details.translations, language.iso639, language.region).firstNotNullOfOrNull { it.overview.clean() }
            ?: details.overview.clean()?.takeIf {
                details.translations.isEmpty() || details.originalLanguage.equals(language.iso639, ignoreCase = true)
            }

    /**
     * Titles in a specific language [iso639], used to vet a logo in a language other than that of the shown title.
     * The original title counts if it is in that language, or if that language's translation has an empty title
     * (TMDB's "same as the original") and the original is written in the language's script.
     */
    internal fun titleIn(details: TitleDetails, iso639: String): List<String> = buildList {
        val translations = details.translations.filter { it.iso639.equals(iso639, ignoreCase = true) }
        val titles = translations.mapNotNull { it.title.clean() }
        addAll(titles)
        val original = details.originalTitle.clean() ?: return@buildList
        val sameAsOriginal = translations.isNotEmpty() && titles.isEmpty() && isWrittenIn(original, iso639)
        if (details.originalLanguage.equals(iso639, ignoreCase = true) || sameAsOriginal) add(original)
    }

    /** Movie: release_date year. Series: start year; a range only for Ended/Canceled with a different end year. */
    fun yearText(details: TitleDetails): String? {
        val start = yearOf(details.date) ?: return null
        if (details.mediaType == MediaType.MOVIE) return start.toString()
        val ended = details.status?.trim()?.lowercase()?.let { it in ENDED_STATUSES } == true
        val end = yearOf(details.lastAirDate)
        return if (ended && end != null && end != start) "$start–$end" else start.toString()
    }

    /** Movie: [runtimeMinutes] > 0. Series: [airedSeasons] > 0. */
    fun durationText(details: TitleDetails, language: DisplayLanguage, today: LocalDate = LocalDate.now()): String? =
        when (details.mediaType) {
            MediaType.MOVIE -> runtimeMinutes(details)?.let { Localization.runtime(it, language) }
            MediaType.TV -> airedSeasons(details, today)?.let { Localization.seasons(it, language) }
        }

    /**
     * The same runtime in every display language. TMDB fills the top-level runtime from the requested language's
     * translation, and some translations are wrong, so: the original language's translation → en-US → top-level.
     */
    internal fun runtimeMinutes(details: TitleDetails): Int? {
        fun runtimeOf(match: (Translation) -> Boolean): Int? =
            details.translations.firstNotNullOfOrNull { t -> t.runtimeMinutes?.takeIf { it > 0 && match(t) } }
        val original = details.originalLanguage
        return original?.let { lang -> runtimeOf { it.iso639.equals(lang, ignoreCase = true) } }
            ?: runtimeOf { it.iso639.equals("en", ignoreCase = true) && it.iso3166.equals("US", ignoreCase = true) }
            ?: details.runtimeMinutes?.takeIf { it > 0 }
    }

    /**
     * Seasons that have started airing: number > 0 (0 is Specials), an air date no later than [today] and at least
     * one episode. number_of_seasons also counts announced seasons, so it is only the last fallback, after the
     * season of the last aired episode.
     */
    internal fun airedSeasons(details: TitleDetails, today: LocalDate): Int? {
        val aired = details.seasons.count { s ->
            s.number > 0 && s.episodeCount > 0 && dateOf(s.airDate)?.let { !it.isAfter(today) } == true
        }
        return aired.takeIf { it > 0 }
            ?: details.lastAiredSeason?.takeIf { it > 0 }
            ?: details.numberOfSeasons?.takeIf { it > 0 }
    }

    /**
     * Up to three genres. ru/uk: sentence case, keeping all-caps abbreviations, with better names for the composite
     * TV genres (by TMDB id); TMDB's own ru names mix lowercase and title case. en: first letter capitalized.
     */
    fun genres(details: TitleDetails, language: DisplayLanguage): List<String> {
        val locale = Localization.locale(language)
        val ids = details.genreIds.takeIf { it.size == details.genres.size }
        return details.genres.mapIndexedNotNull { i, name ->
            val clean = name.clean() ?: return@mapIndexedNotNull null
            Localization.genreName(ids?.get(i) ?: 0, language)
                ?: if (language == DisplayLanguage.EN) clean.replaceFirstChar { it.titlecase(locale) } else sentenceCase(clean, locale)
        }
            .distinct()
            .take(3)
    }

    /** null if there are no votes, fewer than the threshold, or no average rating. */
    fun ratingText(details: TitleDetails, language: DisplayLanguage, minVoteCount: Int): String? {
        if (details.voteCount <= 0 || details.voteCount < minVoteCount) return null
        if (details.voteAverage.isNaN() || details.voteAverage <= 0.0) return null
        return Localization.rating(details.voteAverage, language)
    }

    /** Translations for [iso639]: those for [region] first, then the rest in TMDB order. */
    private fun translationsFor(all: List<Translation>, iso639: String, region: String): List<Translation> =
        all.filter { it.iso639.equals(iso639, ignoreCase = true) }
            .sortedBy { if (it.iso3166.equals(region, ignoreCase = true)) 0 else 1 }

    /** The response title is the original when TMDB fell back to it, otherwise the requested language. */
    private fun responseTitleLanguage(details: TitleDetails, text: String, language: DisplayLanguage): String? =
        if (text == details.originalTitle.clean()) details.originalLanguage?.lowercase() else language.iso639

    /** The first word capitalized and the rest lowercase; all-caps abbreviations are kept as they are. */
    private fun sentenceCase(text: String, locale: Locale): String =
        text.split(' ').mapIndexed { i, word ->
            when {
                isAllCaps(word) -> word
                i == 0 -> word.lowercase(locale).replaceFirstChar { it.titlecase(locale) }
                else -> word.lowercase(locale)
            }
        }.joinToString(" ")

    private fun isAllCaps(word: String): Boolean {
        val letters = word.filter { it.isLetter() }
        return letters.length >= 2 && letters.all { it.isUpperCase() }
    }

    /** true if every letter of [text] is in the script of [iso639]; a title without letters ("1917") fits any script. */
    private fun isWrittenIn(text: String, iso639: String): Boolean {
        val script = scriptOf(iso639) ?: return false
        return text.codePoints().filter { Character.isLetter(it) }.allMatch { Character.UnicodeScript.of(it) == script }
    }

    private fun scriptOf(iso639: String): Character.UnicodeScript? = when (iso639.lowercase()) {
        in CYRILLIC_LANGUAGES -> Character.UnicodeScript.CYRILLIC
        in LATIN_LANGUAGES -> Character.UnicodeScript.LATIN
        else -> null
    }

    private val CYRILLIC_LANGUAGES = setOf("ru", "uk", "be", "bg", "kk", "ky", "mk", "mn", "sr", "tg")
    private val LATIN_LANGUAGES = setOf(
        "en", "fr", "de", "es", "it", "pt", "nl", "sv", "no", "nb", "nn", "da", "fi", "is", "pl", "cs", "sk", "sl", "hr",
        "bs", "hu", "ro", "tr", "et", "lv", "lt", "id", "ms", "vi", "ca", "eu", "gl", "ga", "cy", "sq", "af", "sw", "tl",
    )

    private fun yearOf(date: String?): Int? = date?.trim()?.take(4)?.toIntOrNull()?.takeIf { it > 0 }

    private fun dateOf(date: String?): LocalDate? = try {
        date?.trim()?.takeIf { it.isNotEmpty() }?.let(LocalDate::parse)
    } catch (e: DateTimeParseException) {
        null
    }

    /** Collapses whitespace and line breaks from TMDB; an empty string means no value. */
    private fun String?.clean(): String? = this?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")

    private fun fingerprint(vararg parts: String?): String {
        val joined = parts.joinToString("\u0001") { it ?: "\u0000" }
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
