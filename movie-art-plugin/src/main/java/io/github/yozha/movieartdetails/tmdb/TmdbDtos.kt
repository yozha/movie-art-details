package io.github.yozha.movieartdetails.tmdb

import io.github.yozha.movieartdetails.model.Candidate
import io.github.yozha.movieartdetails.model.ImageInfo
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.SeasonInfo
import io.github.yozha.movieartdetails.model.TitleDetails
import io.github.yozha.movieartdetails.model.Translation
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * TMDB v3 response DTOs and their mapping to contract models.
 * All fields are optional: TMDB omits and nulls them differently for movie and tv.
 * Empty strings, 0 and null all map to "no value".
 */

internal val tmdbJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    explicitNulls = false
}

@Serializable
internal data class SearchPageDto(
    val results: List<SearchItemDto> = emptyList(),
    @SerialName("total_pages") val totalPages: Int? = null,
    @SerialName("total_results") val totalResults: Int? = null,
)

@Serializable
internal data class FindDto(
    @SerialName("movie_results") val movieResults: List<SearchItemDto> = emptyList(),
    @SerialName("tv_results") val tvResults: List<SearchItemDto> = emptyList(),
)

@Serializable
internal data class SearchItemDto(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    val popularity: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("original_language") val originalLanguage: String? = null,
)

@Serializable
internal data class GenreDto(val id: Int = 0, val name: String? = null)

@Serializable
internal data class SeasonDto(
    @SerialName("season_number") val seasonNumber: Int? = null,
    @SerialName("air_date") val airDate: String? = null,
    @SerialName("episode_count") val episodeCount: Int = 0,
)

@Serializable
internal data class EpisodeDto(@SerialName("season_number") val seasonNumber: Int? = null)

@Serializable
internal data class ImageDto(
    @SerialName("file_path") val filePath: String? = null,
    @SerialName("iso_639_1") val iso639: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("vote_average") val voteAverage: Double = 0.0,
)

@Serializable
internal data class ImagesDto(
    val backdrops: List<ImageDto> = emptyList(),
    val logos: List<ImageDto> = emptyList(),
)

@Serializable
internal data class TranslationDataDto(
    val title: String? = null,
    val name: String? = null,
    val overview: String? = null,
    /** Movies only. */
    val runtime: Int? = null,
)

@Serializable
internal data class TranslationDto(
    @SerialName("iso_639_1") val iso639: String? = null,
    @SerialName("iso_3166_1") val iso3166: String? = null,
    val data: TranslationDataDto? = null,
)

@Serializable
internal data class TranslationsDto(val translations: List<TranslationDto> = emptyList())

/** No longer requested (nothing reads a series' IMDb ID); still parsed if present. */
@Serializable
internal data class ExternalIdsDto(@SerialName("imdb_id") val imdbId: String? = null)

@Serializable
internal data class DetailsDto(
    val id: Int = 0,
    val title: String? = null,
    val name: String? = null,
    @SerialName("original_title") val originalTitle: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    @SerialName("original_language") val originalLanguage: String? = null,
    val overview: String? = null,
    @SerialName("release_date") val releaseDate: String? = null,
    @SerialName("first_air_date") val firstAirDate: String? = null,
    @SerialName("last_air_date") val lastAirDate: String? = null,
    val status: String? = null,
    val runtime: Int? = null,
    @SerialName("number_of_seasons") val numberOfSeasons: Int? = null,
    val seasons: List<SeasonDto> = emptyList(),
    @SerialName("last_episode_to_air") val lastEpisodeToAir: EpisodeDto? = null,
    val genres: List<GenreDto> = emptyList(),
    @SerialName("vote_average") val voteAverage: Double = 0.0,
    @SerialName("vote_count") val voteCount: Int = 0,
    @SerialName("backdrop_path") val backdropPath: String? = null,
    @SerialName("imdb_id") val imdbId: String? = null,
    val images: ImagesDto? = null,
    val translations: TranslationsDto? = null,
    @SerialName("external_ids") val externalIds: ExternalIdsDto? = null,
)

/** /authentication response and TMDB error body. */
@Serializable
internal data class StatusDto(
    val success: Boolean? = null,
    @SerialName("status_code") val statusCode: Int? = null,
)

private fun String?.orNullIfBlank(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** Image iso_639_1: null, "" and "xx" mean "no language" (textless). */
private fun String?.imageLanguage(): String? = orNullIfBlank()?.lowercase()?.takeIf { it != "xx" }

internal fun SearchItemDto.toCandidate(type: MediaType, queryLanguage: String): Candidate? {
    if (id <= 0) return null
    val original = (originalTitle ?: originalName).orNullIfBlank()
    val shown = (title ?: name).orNullIfBlank() ?: original ?: return null
    return Candidate(
        mediaType = type,
        id = id,
        title = shown,
        originalTitle = original ?: shown,
        date = (releaseDate ?: firstAirDate).orNullIfBlank(),
        popularity = popularity,
        voteCount = voteCount,
        originalLanguage = originalLanguage.orNullIfBlank(),
        queryLanguage = queryLanguage,
    )
}

private fun ImageDto.toImage(): ImageInfo? {
    val path = filePath.orNullIfBlank() ?: return null
    return ImageInfo(path, iso639.imageLanguage(), width, height, voteAverage)
}

/** Throws IllegalArgumentException for a response without an id; the caller treats it as BAD_RESPONSE. */
internal fun DetailsDto.toDetails(type: MediaType): TitleDetails {
    require(id > 0) { "details without id" }
    val original = (originalTitle ?: originalName).orNullIfBlank()
    val shown = (title ?: name).orNullIfBlank() ?: original.orEmpty()
    val namedGenres = genres.mapNotNull { g -> g.name.orNullIfBlank()?.let { it to g.id.coerceAtLeast(0) } }
    return TitleDetails(
        mediaType = type,
        id = id,
        title = shown,
        originalTitle = original ?: shown,
        originalLanguage = originalLanguage.orNullIfBlank(),
        overview = overview.orNullIfBlank(),
        date = (if (type == MediaType.MOVIE) releaseDate else firstAirDate).orNullIfBlank(),
        lastAirDate = lastAirDate.orNullIfBlank(),
        status = status.orNullIfBlank(),
        runtimeMinutes = runtime?.takeIf { it > 0 },
        numberOfSeasons = numberOfSeasons?.takeIf { it > 0 },
        genres = namedGenres.map { it.first },
        voteAverage = voteAverage,
        voteCount = voteCount,
        backdropPath = backdropPath.orNullIfBlank(),
        translations = translations?.translations.orEmpty().mapNotNull { t ->
            val lang = t.iso639.orNullIfBlank()?.lowercase() ?: return@mapNotNull null
            Translation(
                iso639 = lang,
                iso3166 = t.iso3166.orNullIfBlank()?.uppercase().orEmpty(),
                title = (t.data?.title ?: t.data?.name).orNullIfBlank(),
                overview = t.data?.overview.orNullIfBlank(),
                runtimeMinutes = t.data?.runtime?.takeIf { it > 0 },
            )
        },
        logos = images?.logos.orEmpty().mapNotNull { it.toImage() },
        backdrops = images?.backdrops.orEmpty().mapNotNull { it.toImage() },
        imdbId = (imdbId ?: externalIds?.imdbId).orNullIfBlank(),
        genreIds = namedGenres.map { it.second },
        seasons = seasons.mapNotNull { season ->
            val number = season.seasonNumber ?: return@mapNotNull null
            SeasonInfo(number, season.airDate.orNullIfBlank(), season.episodeCount.coerceAtLeast(0))
        },
        lastAiredSeason = lastEpisodeToAir?.seasonNumber?.takeIf { it > 0 },
    )
}
