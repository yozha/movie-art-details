package io.github.yozha.movieartdetails.match

import io.github.yozha.movieartdetails.model.Candidate
import io.github.yozha.movieartdetails.model.CardKind
import io.github.yozha.movieartdetails.model.MatchResult
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.ParsedTitle
import io.github.yozha.movieartdetails.model.ScoredCandidate
import java.util.Locale
import kotlin.math.abs

/**
 * Scores TMDB candidates (confidence algorithm). Score in 0..1:
 *   0.80 · title similarity (best of primary/alternates against title and originalTitle;
 *          character-level Levenshtein distance + word overlap)
 *   + year: exact +0.10, ±1 +0.04, ±2 −0.15, further off −0.30; without a year, 0. For a TV candidate when the
 *          card is a series or has SxxExx, the file year may be later than first_air_date, so any year from
 *          first_air_date − 1 onward adds 0;
 *   + type: match +0.05, movie instead of series −0.30, series instead of movie −0.15 (type=0 is often a default).
 *
 * Accepted when the score ≥ [ACCEPT] ([ACCEPT_UNKNOWN] for an unknown type) and the lead over the runner-up ≥ [MARGIN].
 * popularity/voteCount never change the score: they only order equal scores and break ties between
 * same-named candidates that are already above the threshold (when the card type is known).
 * The [UNSURE]..threshold band is "unsure": neutral background, candidates go to manual selection.
 */
object TitleMatcher {
    const val ACCEPT = 0.75
    /** Higher threshold for type=null and VLC files: an exact title without a year barely passes. */
    const val ACCEPT_UNKNOWN = 0.80
    const val UNSURE = 0.50
    const val MARGIN = 0.08

    private const val W_TITLE = 0.80
    /** A tie is broken by popularity only if the leader has ≥ 3× the votes of every rival. */
    private const val TIE_VOTES_RATIO = 3
    private const val TIE_MIN_VOTES = 20

    fun match(parsed: ParsedTitle, kind: CardKind, candidates: List<Candidate>): MatchResult {
        val variants = (listOf(parsed.primary) + parsed.alternates)
            .map(TitleText::compareForm).filter { it.isNotEmpty() }.distinct()
        // The same title from ru-RU and uk-UA queries is one candidate; otherwise the margin would always be 0.
        val ranked = candidates.map { score(parsed, kind, variants, it) }
            .groupBy { it.candidate.mediaType to it.candidate.id }
            .values.map { group -> group.maxBy { it.score } }
            .sortedWith(
                compareByDescending<ScoredCandidate> { it.score }
                    .thenByDescending { it.candidate.voteCount }
                    .thenByDescending { it.candidate.popularity }
            )
        val top = ranked.firstOrNull() ?: return MatchResult(emptyList(), null, 0.0, "no candidates")
        if (kind == CardKind.SKIP) return MatchResult(ranked, null, top.score, "card not recognized")

        val threshold = if (kind == CardKind.UNKNOWN) ACCEPT_UNKNOWN else ACCEPT
        if (top.score < threshold) {
            val reason = if (top.score >= UNSURE) "unsure: %.2f < %.2f" else "no match: %.2f"
            return MatchResult(ranked, null, top.score, reason.format(Locale.ROOT, top.score, threshold))
        }
        val rivals = ranked.drop(1).filter { top.score - it.score < MARGIN }
        if (rivals.isEmpty()) {
            val margin = top.score - (ranked.getOrNull(1)?.score ?: 0.0)
            return MatchResult(ranked, top.candidate, top.score, "accepted: %.2f, margin %.2f".format(Locale.ROOT, top.score, margin))
        }
        val votes = top.candidate.voteCount
        val dominant = kind != CardKind.UNKNOWN && votes >= TIE_MIN_VOTES &&
            rivals.all { it.candidate.voteCount.toLong() * TIE_VOTES_RATIO <= votes }
        return if (dominant) {
            MatchResult(ranked, top.candidate, top.score, "accepted: %.2f, tie broken by vote count".format(Locale.ROOT, top.score))
        } else {
            MatchResult(ranked, null, top.score, "ambiguous: %d candidate(s) within %.2f".format(Locale.ROOT, rivals.size + 1, MARGIN))
        }
    }

    private fun score(parsed: ParsedTitle, kind: CardKind, variants: List<String>, c: Candidate): ScoredCandidate {
        // SxxExx in a file name means a series even if the card came as type=0 or 4. In a regular
        // movie title (type=0), "Episode 4" or "Season 2" is part of the title, so the card type decides.
        val episodic = (parsed.season != null || parsed.episode != null) && (kind != CardKind.MOVIE || parsed.looksLikeFileName)
        val kindAdj = kindAdjust(kind, episodic, c.mediaType)
        if (parsed.tmdbId == c.id && kindAdj >= 0.0) return ScoredCandidate(c, 1.0, "tmdb id from tag")

        val names = listOf(c.title, c.originalTitle).map(TitleText::compareForm).filter { it.isNotEmpty() }.distinct()
        val yearText = parsed.year?.toString()
        var best = 0.0
        var bestSim = 0.0
        var bestYear = 0.0
        val lenientTv = c.mediaType == MediaType.TV && (episodic || kind == CardKind.SERIES)
        val namesWithYear = names.filter { yearText != null && yearText in it.split(' ') }
        for (v in variants) {
            val yearInVariant = yearText != null && yearText in v.split(' ')
            for (n in names) {
                // "wonder woman 1984" vs "Wonder Woman 1984": the year is part of the title, not a hint.
                // Against "Wonder Woman" (1975) the year stays a hint, otherwise any same-named title would pass.
                val yearAdj = if (yearInVariant && n in namesWithYear) 0.0 else yearAdjust(parsed.year, c.year, lenientTv)
                val sim = similarity(v, n)
                val total = W_TITLE * sim + yearAdj
                if (total > best) {
                    best = total
                    bestSim = sim
                    bestYear = yearAdj
                }
            }
        }
        val total = (best + kindAdj).coerceIn(0.0, 1.0)
        val explanation = "title=%.2f year=%+.2f kind=%+.2f → %.2f".format(Locale.ROOT, bestSim, bestYear, kindAdj, total)
        return ScoredCandidate(c, total, explanation)
    }

    private fun kindAdjust(kind: CardKind, episodic: Boolean, type: MediaType): Double = when {
        kind == CardKind.SERIES -> if (type == MediaType.TV) 0.05 else -0.30
        episodic -> if (type == MediaType.TV) 0.05 else -0.15
        kind == CardKind.MOVIE -> if (type == MediaType.MOVIE) 0.05 else -0.15
        else -> 0.0
    }

    /** [lenientTv]: a TV candidate for a series card or SxxExx; otherwise the movie rule applies (VLC file "Wonder.Woman.1984"). */
    private fun yearAdjust(wanted: Int?, actual: Int?, lenientTv: Boolean): Double {
        if (wanted == null || actual == null) return 0.0
        val d = wanted - actual
        return when {
            d == 0 -> 0.10
            // The year in an episode file name can be later than the show's first_air_date.
            lenientTv -> if (d >= -1) 0.0 else -0.30
            abs(d) == 1 -> 0.04
            abs(d) == 2 -> -0.15
            else -> -0.30
        }
    }

    /** Similarity of two strings in compareForm, 0..1. */
    internal fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val ta = a.split(' ')
        val tb = b.split(' ')
        val chars = maxOf(ratio(a, b), ratio(ta.sorted().joinToString(" "), tb.sorted().joinToString(" ")))
        return 0.5 * chars + 0.5 * tokenDice(ta, tb)
    }

    /** Word-level Dice coefficient; words of length ≥ 4 with a typo (similarity ≥ 0.8) count as matches. */
    private fun tokenDice(a: List<String>, b: List<String>): Double {
        val rest = b.toMutableList()
        var hits = 0
        for (t in a) {
            var i = rest.indexOf(t)
            if (i < 0 && t.length >= 4) i = rest.indexOfFirst { it.length >= 4 && ratio(t, it) >= 0.8 }
            if (i >= 0) {
                hits++
                rest.removeAt(i)
            }
        }
        return 2.0 * hits / (a.size + b.size)
    }

    private fun ratio(a: String, b: String): Double = 1.0 - levenshtein(a, b).toDouble() / maxOf(a.length, b.length)

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}
