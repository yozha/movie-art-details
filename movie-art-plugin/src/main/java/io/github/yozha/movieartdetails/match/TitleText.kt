package io.github.yozha.movieartdetails.match

import io.github.yozha.movieartdetails.model.Script
import java.text.Normalizer
import java.util.Locale

/** Title normalization shared by parsing, comparison and the mapping key. */
internal object TitleText {
    private val APOSTROPHES = Regex("[’‘ʼʻ`´′＇]")
    private val DASHES = Regex("[‐‑‒–—―−﹘﹣－]")
    private val SPACES = Regex("\\s+")
    private val COMBINING = Regex("\\p{Mn}+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

    /** NFKC with unified apostrophes/dashes; case and letters are unchanged. */
    fun unify(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).replace(APOSTROPHES, "'").replace(DASHES, "-")

    fun collapse(s: String): String = s.replace(SPACES, " ").trim()

    /** Form for ParsedTitle.normalized and the mapping key: lowercase, Cyrillic yo folded to ye, collapsed whitespace. */
    fun normalize(s: String): String = collapse(unify(s).lowercase(Locale.ROOT).replace('ё', 'е'))

    /** Comparison-only form: no diacritics, apostrophes or punctuation, "&" → "and". */
    fun compareForm(s: String): String {
        val n = normalize(s).replace("&", " and ").replace("'", "")
        return collapse(Normalizer.normalize(n, Normalizer.Form.NFD).replace(COMBINING, "").replace(NON_WORD, " "))
    }

    private val NUMBER_WORDS = listOf(
        "one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten",
        "eleven", "twelve", "thirteen", "fourteen", "fifteen", "sixteen", "seventeen", "eighteen", "nineteen", "twenty",
    ).withIndex().associate { (i, w) -> w to (i + 1).toString() }
    private val ROMAN = listOf("i", "ii", "iii", "iv", "v", "vi", "vii", "viii", "ix", "x")
        .withIndex().associate { (i, r) -> r to (i + 1).toString() }
    /** Russian and Ukrainian feminine ordinals that follow the word for "part", in compareForm spelling (yo folded, apostrophes dropped). */
    private val ORDINALS = listOf(
        listOf("первая", "вторая", "третья", "четвертая", "пятая", "шестая", "седьмая", "восьмая", "девятая", "десятая"),
        listOf("перша", "друга", "третя", "четверта", "пята", "шоста", "сьома", "восьма", "девята", "десята"),
    ).flatMap { words -> words.withIndex().map { (i, w) -> w to (i + 1).toString() } }.toMap()
    private val SEQUEL_WORDS = setOf("part", "chapter", "часть", "частина", "глава", "розділ")

    /**
     * Extra comparison form of a [compareForm] string with numerals spelled as digits: English number words
     * one..twenty, sequel wording ("part two", "chapter II" and their Russian/Ukrainian forms) and a final Roman numeral II..X.
     * "dune part two" → "dune 2", "nine 1 2 weeks" → "9 1 2 weeks", "rocky iv" → "rocky 4".
     * It complements compareForm rather than replacing it: "seven" → "7" no longer resembles "se7en".
     */
    fun numberForm(s: String): String {
        if (s.isEmpty()) return s
        val tokens = s.split(' ')
        val out = ArrayList<String>(tokens.size)
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val sequel = if (t in SEQUEL_WORDS) tokens.getOrNull(i + 1)?.let(::numeral) else null
            if (sequel != null) {
                out += sequel
                i += 2
            } else {
                out += NUMBER_WORDS[t] ?: t
                i++
            }
        }
        // A lone "X" or "V" is a title, not a number; "I" at the end is usually a word or a first part.
        if (out.size >= 2) ROMAN[out.last()]?.takeIf { it != "1" }?.let { out[out.size - 1] = it }
        return out.joinToString(" ")
    }

    private fun numeral(t: String): String? =
        if (t.all(Char::isDigit)) t else NUMBER_WORDS[t] ?: ORDINALS[t] ?: ROMAN[t]

    /** Russian-only letters → CYRILLIC_RU, Ukrainian-only letters → CYRILLIC_UK, other Cyrillic (or both sets) → AMBIGUOUS. */
    fun scriptOf(s: String): Script {
        var cyrillic = false
        var latin = false
        var other = false
        var ru = false
        var uk = false
        for (ch in s) {
            if (!ch.isLetter()) continue
            when (Character.UnicodeScript.of(ch.code)) {
                Character.UnicodeScript.CYRILLIC -> {
                    cyrillic = true
                    when (ch.lowercaseChar()) {
                        'ы', 'э', 'ё', 'ъ' -> ru = true
                        'і', 'ї', 'є', 'ґ' -> uk = true
                    }
                }
                Character.UnicodeScript.LATIN -> latin = true
                else -> other = true
            }
        }
        return when {
            cyrillic -> when {
                ru && !uk -> Script.CYRILLIC_RU
                uk && !ru -> Script.CYRILLIC_UK
                else -> Script.CYRILLIC_AMBIGUOUS
            }
            latin -> Script.LATIN
            other -> Script.OTHER
            else -> Script.NONE
        }
    }
}
