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
