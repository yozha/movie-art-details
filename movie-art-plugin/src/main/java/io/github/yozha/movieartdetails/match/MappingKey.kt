package io.github.yozha.movieartdetails.match

import io.github.yozha.movieartdetails.model.CardKind
import io.github.yozha.movieartdetails.model.ParsedTitle

/**
 * Key for a manual title mapping: the normalized title without technical tags or SxxExx,
 * the type class (movie / series / unknown), and the year only if the source title states it explicitly.
 * Examples: "dune|movie|1984", "fargo|series|".
 */
object MappingKey {
    fun of(parsed: ParsedTitle, kind: CardKind): String {
        val cls = when (kind) {
            CardKind.MOVIE -> "movie"
            CardKind.SERIES -> "series"
            CardKind.UNKNOWN, CardKind.SKIP -> "unknown"
        }
        return "${parsed.normalized}|$cls|${parsed.year?.toString().orEmpty()}"
    }
}
