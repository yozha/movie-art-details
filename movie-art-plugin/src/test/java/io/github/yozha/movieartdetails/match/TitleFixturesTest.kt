package io.github.yozha.movieartdetails.match

import io.github.yozha.movieartdetails.model.CardKind
import io.github.yozha.movieartdetails.model.Script
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Table-driven test of title parsing and source classification: src/test/resources/match/titles.tsv. */
class TitleFixturesTest {
    private class Row(
        val line: Int,
        val raw: String,
        val type: Int?,
        val iconUri: String?,
        val kind: CardKind,
        /** null: not checked. */
        val primary: String?,
        val year: Int?,
        val season: Int?,
        val episode: Int?,
        /** null: not checked. */
        val script: Script?,
    )

    private val rows: List<Row> by lazy {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("match/titles.tsv")) { "match/titles.tsv not found" }
        stream.bufferedReader(Charsets.UTF_8).use { it.readLines() }.mapIndexedNotNull { i, line ->
            if (line.isBlank() || line.startsWith("#")) return@mapIndexedNotNull null
            val c = line.split('\t')
            require(c.size == 9) { "titles.tsv:${i + 1}: expected 9 columns, got ${c.size}" }
            Row(
                line = i + 1,
                raw = c[0],
                type = c[1].ifEmpty { null }?.toInt(),
                iconUri = c[2].ifEmpty { null },
                kind = CardKind.valueOf(c[3]),
                primary = c[4].takeIf { it != "*" },
                year = c[5].ifEmpty { null }?.toInt(),
                season = c[6].ifEmpty { null }?.toInt(),
                episode = c[7].ifEmpty { null }?.toInt(),
                script = c[8].ifEmpty { null }?.let(Script::valueOf),
            )
        }
    }

    @Test
    fun fixtureHasAtLeast40Rows() {
        assertTrue("rows: ${rows.size}", rows.size >= 40)
    }

    @Test
    fun everyRowMatchesExpectation() {
        val failures = mutableListOf<String>()
        for (r in rows) {
            val p = TitleParser.parse(r.raw)
            val d = SourceHints.classify(r.type, r.iconUri)
            fun check(field: String, expected: Any?, actual: Any?) {
                if (expected != actual) failures += "titles.tsv:${r.line} \"${r.raw}\": $field expected <$expected>, got <$actual>"
            }
            check("raw", r.raw, p.raw)
            check("kind", r.kind, d.kind)
            if (r.primary != null) check("primary", r.primary, p.primary)
            check("year", r.year, p.year)
            check("season", r.season, p.season)
            check("episode", r.episode, p.episode)
            if (r.script != null) check("script", r.script, p.script)
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun representativeTitlesArePresent() {
        val raws = rows.map { it.raw }.toSet()
        for (t in listOf("Аватар / Avatar", "Up", "Gravity Falls", "Fargo", "Солярис", "Ведмідь / The Bear", "1917")) {
            assertTrue("missing row \"$t\"", t in raws)
        }
        val vlc = rows.first { it.raw.startsWith("Alfred Hitchcock's Rear Window 1954 1080p") }
        assertEquals(4, vlc.type)
        assertEquals("Alfred Hitchcock's Rear Window", vlc.primary)
    }
}
