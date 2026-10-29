package io.github.yozha.movieartdetails.match

import io.github.yozha.movieartdetails.model.ParsedTitle
import io.github.yozha.movieartdetails.model.Script

/**
 * Parses a program card title. Nothing from the source is lost: year, season/episode and IDs
 * become hints, and technical file tags are stripped from [ParsedTitle.primary] only.
 *
 * Order: ID tags → extension → dots as separators → cut at the first "strong" tag
 * (1080p, BluRay, DTS…) → weak tags after the year (Rus, WEB…) → year in brackets → "Localized / Original"
 * → SxxExx in each part → trailing year in each part. A part trimmed by a marker is kept in alternates.
 * Numbers and brackets that may belong to the title ("1917", "(500) Days of Summer", "9 1/2 Weeks") are left alone.
 */
object TitleParser {
    /** Word boundaries for tags: the neighboring character is neither a letter nor a digit. */
    private const val WB = """(?<![\p{L}\p{N}])"""
    private const val WE = """(?![\p{L}\p{N}])"""

    private val IC = RegexOption.IGNORE_CASE
    private val CONTROL = Regex("""[\p{Cc}\p{Cf}]+""")
    private val IMDB_TAG = Regex("""[\[{(]\s*imdb(?:id)?\s*[-=: ]\s*(tt\d{6,10})\s*[\]})]""", IC)
    private val TMDB_TAG = Regex("""[\[{(]\s*tmdb(?:id)?\s*[-=: ]\s*(\d{1,9})\s*[\]})]""", IC)
    private val OTHER_ID_TAG = Regex("""[\[{(]\s*(?:tvdb|imdb|tmdb)(?:id)?\s*[-=: ][^\]})]*[\]})]""", IC)
    private val EXTENSION =
        Regex("""\.(?:mkv|mp4|m4v|avi|mov|wmv|ts|m2ts|mts|webm|flv|mpg|mpeg|iso|vob|3gp|ogv|divx)$""", IC)
    private val DOT_SEP = Regex("""[._]+""")

    /** Tags that practically never occur in a movie title: everything after them is technical. */
    private val STRONG = Regex(
        "$WB(?:" + listOf(
            """(?:240|360|480|576|720|1080|1440|2160|4320)[pi]""",
            """[48]k|uhd""",
            """u?hd[ .-]?rip|bd[ .-]?rip|br[ .-]?rip|bd[ .-]?remux|remux|blu[ .-]?ray|bdmv""",
            """web[ .-]?dl|web[ .-]?rip|hdtv(?:rip)?|dvd[ .-]?rip|dvd[59]|camrip|hdcam|tv[ .-]?rip|sat[ .-]?rip|hd[ .-]?dvd""",
            """[xh][ .]?26[45]|hevc|avc|xvid|divx|vc[ .-]?1|av1|vp9|mpeg[ .-]?2|1[02][ .-]?bit|hi10p""",
            """hdr(?:10(?:\+|plus)?)?|dolby[ .]?vision|dovi|hlg""",
            """dts(?:[ .-]?(?:hd|x|es))?(?:[ .-]?(?:ma|hra))?|truehd|atmos|e?ac[ .-]?3""",
            """ddp?(?:[ .]?[257][ .]?[01])?|dd\+|aac(?:[ .]?[257][ .]?[01])?|flac|lpcm""",
            """\d{2,3}[ .-]?fps""",
        ).joinToString("|") + ")$WE",
        IC,
    )

    /** Weak tags: these can be ordinary words too, so they are only stripped as a tail after the year. */
    private const val WEAK = "web|hd|sd|dv|ma|rus|eng|ukr|ua|subs?|dub|dual|multi|mvo|dvo|avo|proper|repack|rerip|" +
        "extended|unrated|uncut|remastered|imax|open[ .]matte|limited|internal|nf|amzn|dsnp|hmax|atvp|hulu|" +
        "itunes|[257][ .][01]|3d|hfr|director'?s[ .]cut|theatrical|rip"
    private val WEAK_RUN = Regex("""(?:[\s.\-_\[\](),]*$WB(?:$WEAK)$WE)+[\s.\-_\[\](),]*$""", IC)
    private val LEADING_GROUP = Regex("""^\[([^\]]*)\]\s*""")

    /** Season/episode marker; a group number of 0 means no value. */
    private class Marker(val regex: Regex, val seasonGroup: Int, val episodeGroup: Int)

    private val MARKERS = listOf(
        Marker(Regex("""${WB}s(\d{1,2})[ ._-]?e(\d{1,3})(?!\d)""", IC), 1, 2),
        Marker(Regex("""$WB(\d{1,2})x(\d{2,3})$WE""", IC), 1, 2),
        Marker(
            Regex("""$WB(?:season|сезон)[ ._-]?(\d{1,2})(?:[ ._,-]*(?:episode|серия|эпизод)[ ._-]?(\d{1,3}))?(?!\d)""", IC),
            1, 2,
        ),
        Marker(Regex("""$WB(\d{1,2})[ ._-]?(?:-?й[ ._-]?)?сезон$WE""", IC), 1, 0),
        Marker(Regex("""$WB(?:episode|серия|эпизод)[ ._-]?(\d{1,3})(?!\d)""", IC), 0, 1),
        Marker(Regex("""$WB(\d{1,3})[ ._-]?(?:серия|эпизод)$WE""", IC), 0, 1),
        Marker(Regex("""${WB}s(\d{2})$WE""", IC), 1, 0),
    )

    private val BRACKET_YEAR = Regex("""[(\[]\s*((?:19|20)\d\d)\s*[)\]]""")
    private val YEAR_NUMBER = Regex("""$WB((?:19|20)\d\d)$WE""")
    private val TRAILING_YEAR = Regex("""$WB[(\[]?((?:19|20)\d\d)[)\]]?\s*$""")
    private val TRAILING_BRACKET = Regex("""\s*[(\[][^()\[\]]*[)\]]\s*$""")
    private val EMPTY_BRACKETS = Regex("""\([\s\-_.,/]*\)|\[[\s\-_.,/]*\]|\{[\s\-_.,/]*\}""")
    private const val LEAD_JUNK = "-_,;:|/+~*"
    private const val TRAIL_JUNK = "-_,;:|/+~*([{"
    private const val YEAR_HEAD_JUNK = "-_.,|/([{"
    private const val OPEN_QUOTES = "\"«“„'"
    private const val CLOSE_QUOTES = "\"»”“'"

    /** Upper bound for a plausible year: "Blade Runner 2049" is a title, not a year. */
    private val maxYear = java.time.Year.now().value + 3

    fun parse(raw: String): ParsedTitle {
        var s = TitleText.collapse(TitleText.unify(raw).replace(CONTROL, " "))

        val imdbId = IMDB_TAG.find(s)?.groupValues?.get(1)?.lowercase()
        val tmdbId = TMDB_TAG.find(s)?.groupValues?.get(1)?.toIntOrNull()
        s = s.replace(IMDB_TAG, " ").replace(TMDB_TAG, " ").replace(OTHER_ID_TAG, " ").trim()

        var fileLike = false
        EXTENSION.find(s)?.let {
            s = s.substring(0, it.range.first)
            fileLike = true
        }
        val separators = s.count { it == '.' || it == '_' }
        if (' ' !in s && (separators >= 2 || fileLike && separators >= 1)) {
            s = s.replace(DOT_SEP, " ")
            fileLike = true
        }
        STRONG.find(s)?.let { tag ->
            fileLike = true
            val head = s.substring(0, tag.range.first)
            // A tag at the very start ("4K UHD Rainforest Sounds…"): strip the tags in place instead of truncating everything.
            s = if (head.any(Char::isLetterOrDigit)) head else s.replace(STRONG, " ")
        }
        s = TitleText.collapse(s)
        if (fileLike) s = stripLeadingGroup(stripWeakAfterYear(s))

        // A year in brackets is a hint wherever it appears (including after an episode marker).
        val bracketYears = BRACKET_YEAR.findAll(s).filter { plausibleYear(it.groupValues[1].toInt()) }.toList()
        val bracketYear: Int? = bracketYears.lastOrNull()?.groupValues?.get(1)?.toInt()
        for (m in bracketYears.asReversed()) s = s.replaceRange(m.range, " ")

        val alternates = mutableListOf<String>()
        val parts = splitTitles(TitleText.collapse(s)).toMutableList()
        var season: Int? = null
        var episode: Int? = null
        // Markers are searched in each "Localized / Original" part separately. A marker missing from any part
        // ("Open Season 2" or "Star Wars: Episode 4…" next to a title without it) is part of the movie title.
        val found = parts.map { p -> MARKERS.firstNotNullOfOrNull { mk -> mk.regex.find(p)?.let { mk to it } } }
        if (parts.indices.all { found[it] != null || !parts[it].any(Char::isLetter) }) {
            for ((i, f) in found.withIndex()) {
                val (marker, m) = f ?: continue
                val part = parts[i]
                // Everything after the marker is usually the episode title or technical tags.
                val head = part.substring(0, m.range.first)
                val cut = if (head.any(Char::isLetterOrDigit)) head else part.removeRange(m.range)
                // "10x10", "Episode 50": no letters remain without the marker, so it is a title, not an episode number.
                if (!cut.any(Char::isLetter)) continue
                if (season == null && episode == null) {
                    if (marker.seasonGroup > 0) season = m.groupValues[marker.seasonGroup].toIntOrNull()
                    if (marker.episodeGroup > 0) episode = m.groupValues[marker.episodeGroup].toIntOrNull()
                }
                // The removed text may have been part of the title, so the untrimmed part stays as an alternate.
                alternates += clean(part)
                parts[i] = cut
            }
        }

        var year: Int? = bracketYear
        val titles = mutableListOf<String>()
        for (part in parts.map { clean(it) }) {
            if (!part.any(Char::isLetterOrDigit)) continue
            val m = TRAILING_YEAR.find(part)
            val y = m?.groupValues?.get(1)?.toInt()
            val before = m?.let { part.substring(0, it.range.first).trimEnd() }.orEmpty()
            val head = before.trimEnd { it.isWhitespace() || it in YEAR_HEAD_JUNK }
            if (y == null || !plausibleYear(y) || !head.any(Char::isLetterOrDigit) || before.endsWith(':') ||
                (year != null && year != y)
            ) {
                titles += part
                continue
            }
            year = y
            // A trailing number may be part of the title: "Wonder Woman 1984", "Wonder.Woman.1984.1080p.mkv".
            // In a file name, only if there is no other year candidate ("Wonder.Woman.1984.2020…" means 2020).
            if (!fileLike || bracketYear == null && YEAR_NUMBER.findAll(head).none { plausibleYear(it.groupValues[1].toInt()) }) {
                alternates += part
            }
            titles += head
        }

        val primary = titles.firstOrNull().orEmpty()
        TRAILING_BRACKET.find(primary)?.let { alternates += clean(primary.substring(0, it.range.first)) }
        val primaryNorm = TitleText.normalize(primary)
        val alts = (titles.drop(1) + alternates)
            .filter { it.any(Char::isLetterOrDigit) && TitleText.normalize(it) != primaryNorm }
            .distinctBy(TitleText::normalize)

        return ParsedTitle(
            raw = raw,
            primary = primary,
            alternates = alts,
            year = year,
            season = season,
            episode = episode,
            imdbId = imdbId,
            tmdbId = tmdbId,
            looksLikeFileName = fileLike,
            script = if (primary.isEmpty()) Script.NONE else TitleText.scriptOf(primary),
            normalized = primaryNorm,
        )
    }

    private fun plausibleYear(y: Int) = y in 1888..maxYear

    /** "Movie 2010 Rus Eng" → "Movie 2010"; "Charlotte's Web" is left alone since no year precedes "Web". */
    private fun stripWeakAfterYear(s: String): String {
        val m = WEAK_RUN.find(s) ?: return s
        val head = s.substring(0, m.range.first).trimEnd()
        return if (TRAILING_YEAR.containsMatchIn(head)) head else s
    }

    /** "[YTS.MX] Movie (2010)" → "Movie (2010)"; a bracket holding just a year is kept. */
    private fun stripLeadingGroup(s: String): String {
        val m = LEADING_GROUP.find(s) ?: return s
        val rest = s.substring(m.range.last + 1)
        val inner = m.groupValues[1].trim()
        return if (BRACKET_YEAR.matches("[$inner]") || !rest.any(Char::isLetterOrDigit)) s else rest
    }

    /** "Localized / Original", " | "; " - " only when one side is Cyrillic and the other Latin. */
    private fun splitTitles(s: String): List<String> {
        for (sep in listOf(" / ", " | ")) {
            val parts = s.split(sep)
            if (parts.size > 1 && parts.count { p -> p.any(Char::isLetter) } >= 2) return parts
        }
        val dash = s.split(" - ")
        if (dash.size == 2) {
            val a = TitleText.scriptOf(dash[0])
            val b = TitleText.scriptOf(dash[1])
            if (a != b && (a == Script.LATIN && isCyrillic(b) || b == Script.LATIN && isCyrillic(a))) return dash
        }
        return listOf(s)
    }

    private fun isCyrillic(s: Script) =
        s == Script.CYRILLIC_RU || s == Script.CYRILLIC_UK || s == Script.CYRILLIC_AMBIGUOUS

    private fun clean(s: String): String {
        var t = TitleText.collapse(s.replace(EMPTY_BRACKETS, " "))
        t = t.trimStart { it.isWhitespace() || it in LEAD_JUNK }.trimEnd { it.isWhitespace() || it in TRAIL_JUNK }
        if (t.length >= 2 && t.first() in OPEN_QUOTES && t.last() in CLOSE_QUOTES) t = t.substring(1, t.length - 1).trim()
        return t
    }
}
