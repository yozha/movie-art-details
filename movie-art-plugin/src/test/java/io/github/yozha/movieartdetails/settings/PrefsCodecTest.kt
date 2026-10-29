package io.github.yozha.movieartdetails.settings

import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.BlockAnchor
import io.github.yozha.movieartdetails.model.TextPreset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsCodecTest {
    private val custom = PrefsSnapshot(
        language = LanguageChoice.UK,
        appearance = AppearanceSettings(
            textPreset = TextPreset.LARGE, leftPct = 0.08f, topPct = 0.09f, blockWidthPct = 0.50f,
            rowPositionPct = 0.70f, rowGapPct = 0.04f, blockAnchor = BlockAnchor.ABOVE_ROW, overviewLines = 7, scrimIntensity = 0.6f,
            showLogo = false, showYear = true, showType = false, showDuration = true,
            showGenres = false, showRating = true, showOverview = false, showAppBadge = false,
        ),
        minVoteCount = 50,
        cacheLimitMb = 500,
        neutralColor = "#0D1B2A",
        showTitleWhenUnrecognized = false,
    )

    private fun decode(json: String, current: AppearanceSettings = AppearanceSettings()) = PrefsCodec.decode(json, current)

    @Test
    fun roundTripKeepsEveryAllowlistedValue() {
        val patch = assertNotNullPatch(decode(PrefsCodec.encode(custom)))
        assertEquals(custom.language, patch.language)
        assertEquals(custom.appearance, patch.appearance)
        assertEquals(custom.minVoteCount, patch.minVoteCount)
        assertEquals(custom.cacheLimitMb, patch.cacheLimitMb)
        assertEquals(custom.neutralColor, patch.neutralColor)
        assertEquals(false, patch.showTitleWhenUnrecognized)
    }

    @Test
    fun defaultsRoundTripUnchanged() {
        val defaults = PrefsSnapshot(LanguageChoice.SYSTEM, AppearanceSettings(), 10, 200, "#101216")
        val patch = assertNotNullPatch(decode(PrefsCodec.encode(defaults), current = custom.appearance))
        assertEquals(AppearanceSettings(), patch.appearance)
        assertEquals(LanguageChoice.SYSTEM, patch.language)
        assertEquals(true, patch.showTitleWhenUnrecognized)
    }

    @Test
    fun exportContainsOnlyAllowlistAndSchemaVersion() {
        val root = Json.parseToJsonElement(PrefsCodec.encode(custom)).jsonObject
        assertEquals(setOf("schemaVersion", "language", "minVoteCount", "cacheLimitMb", "neutralColor", "showTitleWhenUnrecognized", "appearance"), root.keys)
        assertEquals("2", root["schemaVersion"].toString())
        assertEquals("false", root["showTitleWhenUnrecognized"].toString())
        val appearance = root["appearance"] as JsonObject
        assertEquals(17, appearance.size)
        assertEquals("false", appearance["showAppBadge"].toString())
        assertFalse("replaced by the row position and gap", "bottomLimitPct" in appearance)
    }

    @Test
    fun fileWithoutAppBadgeKeepsCurrentValue() {
        val off = AppearanceSettings(showAppBadge = false)
        val patch = assertNotNullPatch(decode("""{"schemaVersion":2,"appearance":{"showYear":false}}""", current = off))
        assertEquals(off.copy(showYear = false), patch.appearance)
        val on = assertNotNullPatch(decode("""{"schemaVersion":2,"appearance":{"showAppBadge":true}}""", current = off))
        assertTrue(on.appearance!!.showAppBadge)
    }

    @Test
    fun fileWithoutShowTitleKeepsCurrentValue() {
        val old = assertNotNullPatch(decode("""{"schemaVersion":2,"neutralColor":"#000000","appearance":{"showYear":false}}"""))
        assertNull(old.showTitleWhenUnrecognized)
        assertEquals("#000000", old.neutralColor)
        val off = assertNotNullPatch(decode("""{"schemaVersion":2,"showTitleWhenUnrecognized":false}"""))
        assertEquals(false, off.showTitleWhenUnrecognized)
        assertNull(assertNotNullPatch(decode("""{"schemaVersion":2,"showTitleWhenUnrecognized":"maybe"}""")).showTitleWhenUnrecognized)
    }

    @Test
    fun keyAndHistoryAreNeverExportedEvenAfterHostileImport() {
        val secret = "0123456789abcdef0123456789abcdef"
        val hostile = """
            {"schemaVersion":1,"apiKey":"$secret","tmdbKey":"$secret","credential":"$secret",
             "recent":[{"raw":"Avatar"}],"mappings":[{"key":"dune|movie|"}],"diagnosticsEnabled":true,
             "language":"ru"}
        """.trimIndent()
        val patch = assertNotNullPatch(decode(hostile))
        assertEquals(LanguageChoice.RU, patch.language)
        val exported = PrefsCodec.encode(custom.copy(language = patch.language!!))
        assertFalse(exported.contains(secret))
        listOf("key", "api", "credential", "recent", "mapping", "history", "diagnostic").forEach {
            assertFalse("export must not mention '$it'", exported.lowercase().contains("\"$it"))
        }
    }

    @Test
    fun garbageIsRejectedWithoutThrowing() {
        listOf("", " ", "null", "[]", "42", "\"str\"", "{", "not json", "{\"schemaVersion\":", "<html>403</html>")
            .forEach { assertNull("input: $it", decode(it)) }
        assertNull(decode("{\"schemaVersion\":2,\"pad\":\"" + "x".repeat(PrefsCodec.MAX_INPUT_CHARS) + "\"}"))
    }

    @Test
    fun schemaMismatchIsRejected() {
        assertNull(decode("""{"language":"ru"}"""))
        assertNull(decode("""{"schemaVersion":3,"language":"ru"}"""))
        assertNull(decode("""{"schemaVersion":0,"language":"ru"}"""))
        assertNull(decode("""{"schemaVersion":1.5,"language":"ru"}"""))
        assertNull(decode("""{"schemaVersion":null,"language":"ru"}"""))
        assertNull(decode("""{"schemaVersion":{"v":1},"language":"ru"}"""))
    }

    @Test
    fun unknownAndInvalidFieldsAreSkippedOthersApplied() {
        val patch = assertNotNullPatch(
            decode(
                """
                {"schemaVersion":1,"future":{"x":1},"language":"de","minVoteCount":"many","cacheLimitMb":300,
                 "neutralColor":"red","appearance":{"textPreset":"HUGE","leftPct":"0.1","overviewLines":4,
                 "showRating":"maybe","showLogo":false,"scrimIntensity":"NaN","extra":true}}
                """.trimIndent(),
                current = custom.appearance,
            ),
        )
        assertNull(patch.language)
        assertNull(patch.minVoteCount)
        assertEquals(300, patch.cacheLimitMb)
        assertNull(patch.neutralColor)
        val a = patch.appearance!!
        assertEquals(TextPreset.LARGE, a.textPreset) // unknown preset keeps the current value
        assertEquals(0.1f, a.leftPct)                 // a number as a string is accepted
        assertEquals(4, a.overviewLines)
        assertEquals(custom.appearance.showRating, a.showRating)
        assertFalse(a.showLogo)
        assertEquals(custom.appearance.scrimIntensity, a.scrimIntensity)
        assertEquals(custom.appearance.topPct, a.topPct) // a missing field keeps the current value
    }

    @Test
    fun appearanceAsNonObjectIsIgnored() {
        val patch = assertNotNullPatch(decode("""{"schemaVersion":1,"appearance":"oops","minVoteCount":25}"""))
        assertNull(patch.appearance)
        assertEquals(25, patch.minVoteCount)
    }

    @Test
    fun importedValuesAreClampedToSafeRanges() {
        val patch = assertNotNullPatch(
            decode(
                """
                {"schemaVersion":1,"minVoteCount":-5,"cacheLimitMb":999999,
                 "appearance":{"leftPct":0.9,"topPct":-1,"blockWidthPct":2,"bottomLimitPct":0.95,
                 "overviewLines":12,"scrimIntensity":7}}
                """.trimIndent(),
            ),
        )
        assertEquals(0, patch.minVoteCount)
        assertEquals(PrefsCodec.CACHE_MB.last, patch.cacheLimitMb)
        val a = patch.appearance!!
        assertTrue(a.leftPct + a.blockWidthPct <= AppearanceRanges.MAX_RIGHT + 1e-6f)
        assertTrue(a.bottomLimitPct <= 0.52f + 1e-6f)
        assertTrue(a.topPct >= AppearanceRanges.TOP.start)
        assertEquals(AppearanceRanges.OVERVIEW_LINES.last, a.overviewLines)
        assertEquals(1.0f, a.scrimIntensity)

        val v2 = assertNotNullPatch(
            decode("""{"schemaVersion":2,"appearance":{"rowPositionPct":2,"rowGapPct":-1,"blockAnchor":"MIDDLE"}}"""),
        ).appearance!!
        assertEquals(0.85f, v2.rowPositionPct)
        assertEquals(0.03f, v2.rowGapPct)
        assertEquals(BlockAnchor.TOP, v2.blockAnchor) // unknown anchor keeps the current value
    }

    /** Schema 1 stored the bottom limit directly, with the card row at 55%: it becomes the gap above the row. */
    @Test
    fun schemaOneBottomLimitBecomesRowGap() {
        val patch = assertNotNullPatch(
            decode(
                """{"schemaVersion":1,"language":"uk","appearance":{"bottomLimitPct":0.46,"overviewLines":4,
                 "rowPositionPct":0.8,"rowGapPct":0.1,"blockAnchor":"ABOVE_ROW"}}""",
                current = AppearanceSettings(rowPositionPct = 0.65f),
            ),
        )
        assertEquals(LanguageChoice.UK, patch.language)
        val a = patch.appearance!!
        assertEquals(0.09f, a.rowGapPct, 1e-5f)
        // Schema 1 had no row position or anchor: whatever such fields say is ignored and the current values stay.
        assertEquals(0.65f, a.rowPositionPct)
        assertEquals(BlockAnchor.TOP, a.blockAnchor)
        assertEquals(4, a.overviewLines)
        // Without a bottom limit the current gap stays.
        val noBottom = assertNotNullPatch(decode("""{"schemaVersion":1,"appearance":{"overviewLines":4}}""", current = custom.appearance))
        assertEquals(custom.appearance.rowGapPct, noBottom.appearance!!.rowGapPct)
    }

    private fun assertNotNullPatch(p: PrefsPatch?): PrefsPatch {
        assertNotNull(p)
        return p!!
    }
}
