package io.github.yozha.movieartdetails.match

import io.github.yozha.movieartdetails.model.Candidate
import io.github.yozha.movieartdetails.model.CardKind
import io.github.yozha.movieartdetails.model.MatchResult
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.ParsedTitle
import io.github.yozha.movieartdetails.model.ScoredCandidate
import io.github.yozha.movieartdetails.model.Script
import java.util.Locale
import kotlin.math.abs

/**
 * Scores TMDB candidates (confidence algorithm). Score in 0..1:
 *   0.80 · title similarity (best of primary/alternates against title and originalTitle;
 *          character-level Levenshtein distance + word overlap, also on the [TitleText.numberForm] of both sides;
 *          a card that equals the main title of "Main: Subtitle" scores at least [MAIN_TITLE_SIM])
 *   + year: exact +0.10, ±1 +0.04 (exact title only), ±2 −0.15, further off −0.30; without a card year, 0;
 *          an undated candidate against a dated card −0.10. For a TV candidate when the card is a series or has
 *          SxxExx, the file year may be later than first_air_date, so any year from first_air_date − 1 onward adds 0;
 *   + type: match +0.05, movie instead of series −0.30, series instead of movie −0.15 (type=0 is often a default).
 *
 * Accepted when the score ≥ [ACCEPT] ([ACCEPT_UNKNOWN] for an unknown type) and the lead over the runner-up ≥ [MARGIN].
 * popularity/voteCount never change the score: they only order equal scores and pick among contenders within
 * [MARGIN] of the leader (see [mostVoted]).
 * The [UNSURE]..threshold band is "unsure": neutral background, candidates go to manual selection.
 */
object TitleMatcher {
    const val ACCEPT = 0.75
    /** Higher threshold for type=null and VLC files: an exact title without a year barely passes. */
    const val ACCEPT_UNKNOWN = 0.80
    const val UNSURE = 0.50
    const val MARGIN = 0.08

    private const val W_TITLE = 0.80
    /** Title similarity from which a match counts as the exact title. */
    private const val EXACT_SIM = 0.99
    /** Similarity of a card that names only the main title of "Main: Subtitle" / "Main. Subtitle" / "Main - Subtitle". */
    private const val MAIN_TITLE_SIM = 0.85
    private const val YEAR_EXACT = 0.10
    private const val YEAR_NEAR = 0.04
    private const val YEAR_UNDATED = -0.10

    /** Below this title similarity of the leader, a variant without a possessive credit ("Name Surname's") is tried. */
    private const val CREDIT_GATE = 0.90
    private const val CREDIT_WEIGHT = 0.95
    /** "Alfred Hitchcock's Rear Window" → "Rear Window"; a leading article is not a name ("The Devil's Advocate"). */
    private val CREDIT_PREFIX = Regex("""^(?:(?!(?:The|An?)\s)\p{Lu}[\p{L}.-]*\s+){1,2}\p{L}+'s\s+(?=\S)""")
    private val MAIN_TITLE_END = Regex(""": |\. | - """)

    /** The most-voted contender wins if it has ≥ [ratio]× the votes of every other contender and at least [minVotes]. */
    private class VoteRule(val ratio: Int, val minVotes: Int)
    private val KNOWN_TYPE_VOTES = VoteRule(3, 20)
    /** Stricter for an unknown type without SxxExx: same-named movies and series are common. */
    private val UNKNOWN_TYPE_VOTES = VoteRule(10, 50)
    /** A domestic production (original title equals the card) must still have 2× the votes of every foreign contender. */
    private const val DOMESTIC_VOTES_RATIO = 2
    private val CYRILLIC = setOf(Script.CYRILLIC_RU, Script.CYRILLIC_UK, Script.CYRILLIC_AMBIGUOUS)

    private class Form(val plain: String, val weight: Double) {
        val numeric = TitleText.numberForm(plain)
        val tokens = plain.split(' ')
    }

    private class Scored(val candidate: Candidate, val score: Double, val sim: Double, val explanation: String)

    fun match(parsed: ParsedTitle, kind: CardKind, candidates: List<Candidate>): MatchResult {
        val raws = listOf(parsed.primary) + parsed.alternates
        val variants = forms(raws, 1.0)
        val episodic = isEpisodic(parsed, kind)
        var ranked = rank(parsed, kind, episodic, variants, candidates)
        val leader = ranked.firstOrNull() ?: return MatchResult(emptyList(), null, 0.0, "no candidates")
        if (leader.sim < CREDIT_GATE) {
            // "Alfred Hitchcock's Rear Window": the credit may only re-rank, never promote a different candidate.
            val stripped = raws.mapNotNull { r -> CREDIT_PREFIX.find(r)?.let { r.substring(it.range.last + 1) } }
            if (stripped.isNotEmpty()) {
                val rescored = rank(parsed, kind, episodic, (variants + forms(stripped, CREDIT_WEIGHT)).distinctBy { it.plain }, candidates)
                if (sameTitle(rescored.first().candidate, leader.candidate)) ranked = rescored
            }
        }
        val top = ranked.first()
        val result = ranked.map { ScoredCandidate(it.candidate, it.score, it.explanation) }
        if (kind == CardKind.SKIP) return MatchResult(result, null, top.score, "card not recognized")

        val threshold = if (kind == CardKind.UNKNOWN) ACCEPT_UNKNOWN else ACCEPT
        if (top.score < threshold) {
            val reason = if (top.score >= UNSURE) "unsure: %.2f < %.2f" else "no match: %.2f"
            return MatchResult(result, null, top.score, reason.format(Locale.ROOT, top.score, threshold))
        }
        val contenders = ranked.filter { top.score - it.score < MARGIN }
        if (contenders.size == 1) {
            val margin = top.score - (ranked.getOrNull(1)?.score ?: 0.0)
            return MatchResult(result, top.candidate, top.score, "accepted: %.2f, margin %.2f".format(Locale.ROOT, top.score, margin))
        }
        val best = mostVoted(parsed, kind, episodic, variants, contenders, threshold)
        return if (best != null) {
            MatchResult(result, best.candidate, best.score, "accepted: %.2f, tie broken by vote count".format(Locale.ROOT, best.score))
        } else {
            MatchResult(result, null, top.score, "ambiguous: %d candidate(s) within %.2f".format(Locale.ROOT, contenders.size, MARGIN))
        }
    }

    /**
     * Same-named contenders within [MARGIN]: an exact year beats a year one off by less than the margin, and obscure
     * namesakes (shorts, placeholders) often date exactly. The most-voted contender is taken if it clearly dominates.
     * For a Cyrillic card, contenders whose original title equals the card (a domestic production) are tried first,
     * so a local series is not lost to a foreign one that is merely shown under the same localized title.
     */
    private fun mostVoted(
        parsed: ParsedTitle, kind: CardKind, episodic: Boolean, variants: List<Form>, contenders: List<Scored>, threshold: Double,
    ): Scored? {
        val rule = if (kind == CardKind.UNKNOWN && !episodic) UNKNOWN_TYPE_VOTES else KNOWN_TYPE_VOTES
        if (parsed.script in CYRILLIC) {
            val cardForms = variants.map { it.plain }.toSet()
            val (domestic, foreign) = contenders.partition { TitleText.compareForm(it.candidate.originalTitle) in cardForms }
            if (domestic.isNotEmpty() && foreign.isNotEmpty()) {
                val best = dominant(domestic, rule, threshold)
                if (best != null && foreign.all { it.candidate.voteCount.toLong() * DOMESTIC_VOTES_RATIO <= best.candidate.voteCount }) {
                    return best
                }
            }
        }
        return dominant(contenders, rule, threshold)
    }

    private fun dominant(group: List<Scored>, rule: VoteRule, threshold: Double): Scored? {
        val best = group.maxWith(compareBy<Scored> { it.candidate.voteCount }.thenBy { it.score })
        val votes = best.candidate.voteCount
        val ok = best.score >= threshold && votes >= rule.minVotes &&
            group.all { it === best || it.candidate.voteCount.toLong() * rule.ratio <= votes }
        return if (ok) best else null
    }

    private fun forms(raws: List<String>, weight: Double): List<Form> =
        raws.map(TitleText::compareForm).filter { it.isNotEmpty() }.distinct().map { Form(it, weight) }

    private fun sameTitle(a: Candidate, b: Candidate) = a.mediaType == b.mediaType && a.id == b.id

    /** SxxExx in a file name means a series even if the card came as type=0 or 4. In a regular
     *  movie title (type=0), "Episode 4" or "Season 2" is part of the title, so the card type decides. */
    private fun isEpisodic(parsed: ParsedTitle, kind: CardKind) =
        (parsed.season != null || parsed.episode != null) && (kind != CardKind.MOVIE || parsed.looksLikeFileName)

    private fun rank(parsed: ParsedTitle, kind: CardKind, episodic: Boolean, variants: List<Form>, candidates: List<Candidate>) =
        candidates.map { score(parsed, kind, episodic, variants, it) }
            // The same title from ru-RU and uk-UA queries is one candidate; otherwise the margin would always be 0.
            .groupBy { it.candidate.mediaType to it.candidate.id }
            .values.map { group -> group.maxBy { it.score } }
            .sortedWith(
                compareByDescending<Scored> { it.score }
                    .thenByDescending { it.candidate.voteCount }
                    .thenByDescending { it.candidate.popularity }
            )

    private fun score(parsed: ParsedTitle, kind: CardKind, episodic: Boolean, variants: List<Form>, c: Candidate): Scored {
        val kindAdj = kindAdjust(kind, episodic, c.mediaType)
        if (parsed.tmdbId == c.id && kindAdj >= 0.0) return Scored(c, 1.0, 1.0, "tmdb id from tag")

        val rawNames = listOf(c.title, c.originalTitle)
        val names = forms(rawNames, 1.0)
        val mainTitles = rawNames.mapNotNull(::mainTitle).map(TitleText::compareForm).filter { it.isNotEmpty() }.toSet()
        val yearText = parsed.year?.toString()
        var best = 0.0
        var bestSim = 0.0
        var bestYear = 0.0
        val lenientTv = c.mediaType == MediaType.TV && (episodic || kind == CardKind.SERIES)
        for (v in variants) {
            val yearInVariant = yearText != null && yearText in v.tokens
            val mainSim = if (v.plain in mainTitles) MAIN_TITLE_SIM else 0.0
            for (n in names) {
                val sim = v.weight * maxOf(similarity(v, n), mainSim)
                // "wonder woman 1984" vs "Wonder Woman 1984": the year is part of the title, not a hint.
                // Against "Wonder Woman" (1975) the year stays a hint, otherwise any same-named title would pass.
                val yearAdj = if (yearInVariant && yearText in n.tokens) 0.0 else yearAdjust(parsed.year, c.year, lenientTv, sim >= EXACT_SIM)
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
        return Scored(c, total, bestSim, explanation)
    }

    /** "Dune: Part Two" → "Dune"; null when the title has no subtitle. */
    private fun mainTitle(title: String): String? {
        val t = TitleText.unify(title)
        val end = MAIN_TITLE_END.find(t) ?: return null
        return t.substring(0, end.range.first).takeIf { head -> head.any(Char::isLetterOrDigit) }
    }

    private fun kindAdjust(kind: CardKind, episodic: Boolean, type: MediaType): Double = when {
        kind == CardKind.SERIES -> if (type == MediaType.TV) 0.05 else -0.30
        episodic -> if (type == MediaType.TV) 0.05 else -0.15
        kind == CardKind.MOVIE -> if (type == MediaType.MOVIE) 0.05 else -0.15
        else -> 0.0
    }

    /**
     * [lenientTv]: a TV candidate for a series card or SxxExx; otherwise the movie rule applies (VLC file "Wonder.Woman.1984").
     * [exactTitle]: a year one off only counts in favor of the exact title ("Family Holiday" 2015 is not "Family Holidays" 2014).
     */
    private fun yearAdjust(wanted: Int?, actual: Int?, lenientTv: Boolean, exactTitle: Boolean): Double {
        if (wanted == null) return 0.0
        // An undated candidate (unaired pilot, placeholder record) cannot confirm the year: a mild penalty, not a veto.
        if (actual == null) return YEAR_UNDATED
        val d = wanted - actual
        return when {
            d == 0 -> YEAR_EXACT
            // The year in an episode file name can be later than the show's first_air_date.
            lenientTv -> if (d >= -1) 0.0 else -0.30
            abs(d) == 1 -> if (exactTitle) YEAR_NEAR else 0.0
            abs(d) == 2 -> -0.15
            else -> -0.30
        }
    }

    /** Similarity of two forms: the better of the plain compareForm and the numberForm comparison. */
    private fun similarity(a: Form, b: Form): Double {
        val plain = similarity(a.plain, b.plain)
        if (plain >= 1.0 || a.numeric == a.plain && b.numeric == b.plain) return plain
        return maxOf(plain, similarity(a.numeric, b.numeric))
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
