package io.github.yozha.movieartdetails.tmdb

import io.github.yozha.movieartdetails.model.ImageInfo
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.SeasonInfo
import io.github.yozha.movieartdetails.model.TitleDetails
import io.github.yozha.movieartdetails.model.Translation

/** Synthetic fixtures from src/test/resources/tmdb and a details builder for tests. */
internal object TestData {

    fun text(name: String): String =
        requireNotNull(TestData::class.java.classLoader?.getResource("tmdb/$name")) { "missing fixture $name" }.readText()

    fun fixtureDetails(name: String, type: MediaType): TitleDetails =
        tmdbJson.decodeFromString(DetailsDto.serializer(), text(name)).toDetails(type)

    fun details(
        type: MediaType = MediaType.MOVIE,
        title: String = "Title",
        originalTitle: String = "Original",
        originalLanguage: String? = "en",
        overview: String? = null,
        date: String? = "2009-12-15",
        lastAirDate: String? = null,
        status: String? = null,
        runtime: Int? = null,
        seasons: Int? = null,
        genres: List<String> = emptyList(),
        voteAverage: Double = 7.5,
        voteCount: Int = 100,
        backdropPath: String? = null,
        translations: List<Translation> = emptyList(),
        logos: List<ImageInfo> = emptyList(),
        backdrops: List<ImageInfo> = emptyList(),
        genreIds: List<Int> = emptyList(),
        seasonList: List<SeasonInfo> = emptyList(),
        lastAiredSeason: Int? = null,
    ) = TitleDetails(
        mediaType = type, id = 1, title = title, originalTitle = originalTitle, originalLanguage = originalLanguage,
        overview = overview, date = date, lastAirDate = lastAirDate, status = status, runtimeMinutes = runtime,
        numberOfSeasons = seasons, genres = genres, voteAverage = voteAverage, voteCount = voteCount,
        backdropPath = backdropPath, translations = translations, logos = logos, backdrops = backdrops, imdbId = null,
        genreIds = genreIds, seasons = seasonList, lastAiredSeason = lastAiredSeason,
    )

    fun tr(lang: String, region: String, title: String?, overview: String? = null, runtime: Int? = null) =
        Translation(lang, region, title, overview, runtime)

    fun img(path: String, lang: String?, width: Int, height: Int, vote: Double = 5.0) = ImageInfo(path, lang, width, height, vote)
}
