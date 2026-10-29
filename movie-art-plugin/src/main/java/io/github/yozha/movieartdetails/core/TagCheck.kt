package io.github.yozha.movieartdetails.core

import io.github.yozha.movieartdetails.match.TitleMatcher
import io.github.yozha.movieartdetails.match.TitleText
import io.github.yozha.movieartdetails.model.Candidate
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.ParsedTitle
import io.github.yozha.movieartdetails.model.TitleDetails
import kotlin.math.abs

/**
 * Sanity check of an ID tag ([tmdbid-…], [imdbid-…]) against the rest of the card title, so that a stale or
 * mistyped tag does not show an unrelated title. A tag is rejected only on a clear mismatch: a low title similarity
 * and, when the card has a year, a year that disagrees too. A localized title of the right film still passes on its
 * year, and a card that is nothing but a tag cannot disagree at all.
 */
internal object TagCheck {
    /** Below this similarity (best of the card variants against every known title) the titles disagree. */
    const val MIN_SIMILARITY = 0.5
    /** Lead in similarity that decides between a movie and a series with the same ID. */
    private const val CLEAR_LEAD = 0.1
    private val MAIN_TITLE_END = Regex(""": |\. | - """)

    class Fit(val similarity: Double, val yearAgrees: Boolean)

    /** null when the tagged title clearly is not the card's title. */
    fun fit(parsed: ParsedTitle, names: Collection<String>, type: MediaType, year: Int?, lastYear: Int? = null): Fit? {
        val sim = similarity(parsed, names)
        val wanted = parsed.year
        val yearAgrees = wanted != null && year != null && when (type) {
            // A series file may carry the year of a later season.
            MediaType.TV -> wanted >= year - 1 && (lastYear == null || wanted <= lastYear + 1)
            MediaType.MOVIE -> abs(wanted - year) <= 1
        }
        val ok = parsed.primary.isBlank() || sim >= MIN_SIMILARITY || yearAgrees
        return if (ok) Fit(sim, yearAgrees) else null
    }

    /** A /find result: titles in the query language and the original. */
    fun fit(parsed: ParsedTitle, c: Candidate): Fit? = fit(parsed, listOf(c.title, c.originalTitle), c.mediaType, c.year)

    /** Details carry every translation, so a card in any language can be checked against them. */
    fun fit(parsed: ParsedTitle, d: TitleDetails): Fit? = fit(
        parsed,
        listOf(d.title, d.originalTitle) + d.translations.mapNotNull { it.title },
        d.mediaType,
        yearOf(d.date),
        yearOf(d.lastAirDate),
    )

    /**
     * The tagged title among movie/{id} and tv/{id} (the card type is unknown): the one that fits, or the clearly
     * better fit of the two. SxxExx breaks a tie in favor of the series; otherwise a tie is null (search decides).
     */
    fun pick(parsed: ParsedTitle, found: List<TitleDetails>): TitleDetails? {
        val fits = found.mapNotNull { d -> fit(parsed, d)?.let { d to it } }
            .sortedWith(compareByDescending<Pair<TitleDetails, Fit>> { it.second.similarity }.thenByDescending { it.second.yearAgrees })
        if (fits.size <= 1) return fits.singleOrNull()?.first
        val (best, top) = fits[0]
        val next = fits[1].second
        if (top.similarity - next.similarity >= CLEAR_LEAD || top.yearAgrees && !next.yearAgrees) return best
        if (parsed.season != null || parsed.episode != null) return fits.firstOrNull { it.first.mediaType == MediaType.TV }?.first
        return null
    }

    /** Best similarity of the card's variants against [names] and their main titles ("Main: Subtitle" → "Main"). */
    fun similarity(parsed: ParsedTitle, names: Collection<String>): Double {
        val cards = forms(listOf(parsed.primary) + parsed.alternates)
        val targets = forms(names.flatMap { listOfNotNull(it, mainTitle(it)) })
        var best = 0.0
        for (c in cards) {
            for (t in targets) {
                val sim = maxOf(TitleMatcher.similarity(c, t), TitleMatcher.similarity(TitleText.numberForm(c), TitleText.numberForm(t)))
                if (sim >= 1.0) return 1.0
                best = maxOf(best, sim)
            }
        }
        return best
    }

    private fun forms(raws: List<String>) = raws.map(TitleText::compareForm).filter { it.isNotEmpty() }.distinct()

    private fun mainTitle(title: String): String? {
        val end = MAIN_TITLE_END.find(title) ?: return null
        return title.substring(0, end.range.first).takeIf { head -> head.any(Char::isLetterOrDigit) }
    }

    private fun yearOf(date: String?): Int? = date?.take(4)?.toIntOrNull()
}
