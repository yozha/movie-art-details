package io.github.yozha.movieartdetails.tmdb

import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.DisplayInfo
import io.github.yozha.movieartdetails.model.DisplayLanguage
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.TitleDetails
import io.github.yozha.movieartdetails.model.Translation
import java.security.MessageDigest

/**
 * Builds the info panel strings from TMDB details.
 * Fields turned off in [AppearanceSettings] (show*) come back as null right away; typeText is never cleared
 * (it is not nullable), so the renderer decides whether to show it based on showType.
 */
object DisplayInfoBuilder {

    private val ENDED_STATUSES = setOf("ended", "canceled", "cancelled")

    fun build(details: TitleDetails, language: DisplayLanguage, minVoteCount: Int, appearance: AppearanceSettings): DisplayInfo {
        val title = title(details, language)
        val year = if (appearance.showYear) yearText(details) else null
        val type = Localization.typeText(details.mediaType, language)
        val duration = if (appearance.showDuration) durationText(details, language) else null
        val genres = if (appearance.showGenres) genres(details, language) else emptyList()
        val rating = if (appearance.showRating) ratingText(details, language, minVoteCount) else null
        val overview = if (appearance.showOverview) overview(details, language) else null
        val logo = if (appearance.showLogo) ImageSelector.logo(details, language, title) else null
        val backdrop = ImageSelector.backdrop(details)
        val fingerprint = fingerprint(
            details.mediaType.name, details.id.toString(), language.name, title, year, type, duration,
            genres.joinToString("\u0002"), rating, overview, logo, backdrop,
        )
        return DisplayInfo(
            mediaType = details.mediaType,
            tmdbId = details.id,
            language = language,
            title = title,
            yearText = year,
            typeText = type,
            durationText = duration,
            genres = genres,
            ratingText = rating,
            overview = overview,
            logoPath = logo,
            backdropPath = backdrop,
            fingerprint = fingerprint,
        )
    }

    /**
     * Title: translation in the selected language → original, if the original language is the selected one →
     * English translation → original_title/original_name. Without a translations array (old cache)
     * the response title is used: either a translation or the original that TMDB fell back to.
     */
    fun title(details: TitleDetails, language: DisplayLanguage): String {
        val text = translationsFor(details.translations, language.iso639, language.region).firstNotNullOfOrNull { it.title.clean() }
            ?: details.originalTitle.clean()?.takeIf { details.originalLanguage.equals(language.iso639, ignoreCase = true) }
            ?: details.title.clean()?.takeIf { details.translations.isEmpty() }
            ?: translationsFor(details.translations, "en", "US").firstNotNullOfOrNull { it.title.clean() }
        return text ?: details.originalTitle.clean() ?: details.title.clean().orEmpty()
    }

    /** Overview: translation in the selected language (locale region first) → English (en-US first) → hidden. */
    fun overview(details: TitleDetails, language: DisplayLanguage): String? =
        translationsFor(details.translations, language.iso639, language.region).firstNotNullOfOrNull { it.overview.clean() }
            ?: details.overview.clean()?.takeIf { details.translations.isEmpty() }
            ?: translationsFor(details.translations, "en", "US").firstNotNullOfOrNull { it.overview.clean() }

    /** Titles in a specific language [iso639], used to vet a logo in a language other than the display one. */
    internal fun titleIn(details: TitleDetails, iso639: String): List<String> = buildList {
        details.translations.filter { it.iso639.equals(iso639, ignoreCase = true) }.mapNotNullTo(this) { it.title.clean() }
        if (details.originalLanguage.equals(iso639, ignoreCase = true)) details.originalTitle.clean()?.let(::add)
    }

    /** Movie: release_date year. Series: start year; a range only for Ended/Canceled with a different end year. */
    fun yearText(details: TitleDetails): String? {
        val start = yearOf(details.date) ?: return null
        if (details.mediaType == MediaType.MOVIE) return start.toString()
        val ended = details.status?.trim()?.lowercase()?.let { it in ENDED_STATUSES } == true
        val end = yearOf(details.lastAirDate)
        return if (ended && end != null && end != start) "$start–$end" else start.toString()
    }

    /** Movie: runtime > 0. Series: number of seasons > 0. */
    fun durationText(details: TitleDetails, language: DisplayLanguage): String? = when (details.mediaType) {
        MediaType.MOVIE -> details.runtimeMinutes?.takeIf { it > 0 }?.let { Localization.runtime(it, language) }
        MediaType.TV -> details.numberOfSeasons?.takeIf { it > 0 }?.let { Localization.seasons(it, language) }
    }

    /** Up to three genres, first letter capitalized. */
    fun genres(details: TitleDetails, language: DisplayLanguage): List<String> {
        val locale = Localization.locale(language)
        return details.genres.mapNotNull { it.clean() }
            .map { g -> g.replaceFirstChar { it.titlecase(locale) } }
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

    private fun yearOf(date: String?): Int? = date?.trim()?.take(4)?.toIntOrNull()?.takeIf { it > 0 }

    /** Collapses whitespace and line breaks from TMDB; an empty string means no value. */
    private fun String?.clean(): String? = this?.replace(WHITESPACE, " ")?.trim()?.takeIf { it.isNotEmpty() }

    private val WHITESPACE = Regex("\\s+")

    private fun fingerprint(vararg parts: String?): String {
        val joined = parts.joinToString("\u0001") { it ?: "\u0000" }
        val digest = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
