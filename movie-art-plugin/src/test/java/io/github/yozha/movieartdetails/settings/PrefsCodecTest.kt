package io.github.yozha.movieartdetails.settings

import io.github.yozha.movieartdetails.model.AppearanceSettings
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
            bottomLimitPct = 0.48f, overviewLines = 3, scrimIntensity = 0.6f,
            showLogo = false, showYear = true, showType = false, showDuration = true,
            showGenres = false, showRating = true, showOverview = false,
        ),
        minVoteCount = 50,
        cacheLimitMb = 500,
        neutralColor = "#0D1B2A",
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
    }

    @Test
    fun defaultsRoundTripUnchanged() {
        val defaults = PrefsSnapshot(LanguageChoice.SYSTEM, AppearanceSettings(), 10, 200, "#101216")
        val patch = assertNotNullPatch(decode(PrefsCodec.encode(defaults), current = custom.appearance))
        assertEquals(AppearanceSettings(), patch.appearance)
        assertEquals(LanguageChoice.SYSTEM, patch.language)
    }

    @Test
    fun exportContainsOnlyAllowlistAndSchemaVersion() {
        val root = Json.parseToJsonElement(PrefsCodec.encode(custom)).jsonObject
        assertEquals(setOf("schemaVersion", "language", "minVoteCount", "cacheLimitMb", "neutralColor", "appearance"), root.keys)
        assertEquals("1", root["schemaVersion"].toString())
        val appearance = root["appearance"] as JsonObject
        assertEquals(14, appearance.size)
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
        assertNull(decode("{\"schemaVersion\":1,\"pad\":\"" + "x".repeat(PrefsCodec.MAX_INPUT_CHARS) + "\"}"))
    }

    @Test
    fun schemaMismatchIsRejected() {
        assertNull(decode("""{"language":"ru"}"""))
        assertNull(decode("""{"schemaVersion":2,"language":"ru"}"""))
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
        assertTrue(a.bottomLimitPct <= 0.52f)
        assertTrue(a.topPct >= AppearanceRanges.TOP.start)
        assertEquals(5, a.overviewLines)
        assertEquals(1.0f, a.scrimIntensity)
    }

    private fun assertNotNullPatch(p: PrefsPatch?): PrefsPatch {
        assertNotNull(p)
        return p!!
    }
}
