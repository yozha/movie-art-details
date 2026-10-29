package io.github.yozha.movieartdetails.settings

import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.BlockAnchor
import io.github.yozha.movieartdetails.model.TextPreset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Values exported through getPreferences (an allowlist). The key, history and diagnostics are excluded by design. */
data class PrefsSnapshot(
    val language: LanguageChoice,
    val appearance: AppearanceSettings,
    val minVoteCount: Int,
    val cacheLimitMb: Int,
    val neutralColor: String,
    val showTitleWhenUnrecognized: Boolean = true,
)

/** Import result: only recognized, valid fields; everything else is null, meaning "leave as is". */
data class PrefsPatch(
    val language: LanguageChoice? = null,
    val minVoteCount: Int? = null,
    val cacheLimitMb: Int? = null,
    val neutralColor: String? = null,
    val showTitleWhenUnrecognized: Boolean? = null,
    /** Appearance: incoming fields applied over the current values, already coerced into range. */
    val appearance: AppearanceSettings? = null,
)

/**
 * JSON for getPreferences/setPreferences. Format:
 * `{"schemaVersion":2,"language":"system","minVoteCount":10,"cacheLimitMb":200,"neutralColor":"#101216",`
 * `"showTitleWhenUnrecognized":true,"appearance":{…}}`.
 *
 * Import is lenient: unknown fields are ignored, fields with a wrong type or format are skipped, numbers
 * are coerced into range. An unknown schema version, non-JSON and non-objects are rejected entirely (decode → null).
 * Version 1 is still read: its "bottomLimitPct" (from before the card row position existed) becomes the gap
 * above a row at [LEGACY_ROW_PCT]. Files written before "showTitleWhenUnrecognized" existed leave that setting
 * as it is. Manual mappings are not exported yet, since the key format may still change;
 * source app choices are not exported either: they name hosts on the user's network and stay on the device.
 */
object PrefsCodec {
    const val SCHEMA_VERSION = 2
    private const val SCHEMA_V1 = 1
    /** Where the card row started when the block's bottom limit was set directly (schema 1). */
    const val LEGACY_ROW_PCT = 0.55f
    /** Guard against junk from a foreign backup: settings take about 0.5 KB. */
    const val MAX_INPUT_CHARS = 64 * 1024

    val MIN_VOTES = 0..10_000
    val CACHE_MB = 50..2_000
    private val COLOR = Regex("^#[0-9a-fA-F]{6}$")

    fun isValidColor(s: String): Boolean = COLOR.matches(s)

    fun encode(s: PrefsSnapshot): String = buildJsonObject {
        put("schemaVersion", SCHEMA_VERSION)
        put("language", s.language.id)
        put("minVoteCount", s.minVoteCount)
        put("cacheLimitMb", s.cacheLimitMb)
        put("neutralColor", s.neutralColor)
        put(K_SHOW_TITLE, s.showTitleWhenUnrecognized)
        put("appearance", buildJsonObject {
            val a = s.appearance
            put(A_PRESET, a.textPreset.name)
            put(A_LEFT, a.leftPct)
            put(A_TOP, a.topPct)
            put(A_WIDTH, a.blockWidthPct)
            put(A_ROW, a.rowPositionPct)
            put(A_ROW_GAP, a.rowGapPct)
            put(A_ANCHOR, a.blockAnchor.name)
            put(A_LINES, a.overviewLines)
            put(A_SCRIM, a.scrimIntensity)
            put(A_LOGO, a.showLogo)
            put(A_YEAR, a.showYear)
            put(A_TYPE, a.showType)
            put(A_DURATION, a.showDuration)
            put(A_GENRES, a.showGenres)
            put(A_RATING, a.showRating)
            put(A_OVERVIEW, a.showOverview)
            put(A_APP_BADGE, a.showAppBadge)
        })
    }.toString()

    /** [current] is the current appearance: fields missing from the input keep their values. */
    fun decode(json: String, current: AppearanceSettings): PrefsPatch? {
        if (json.length > MAX_INPUT_CHARS) return null
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
        val version = root.int("schemaVersion")
        if (version != SCHEMA_VERSION && version != SCHEMA_V1) return null
        val appearance = root["appearance"] as? JsonObject
        return PrefsPatch(
            language = LanguageChoice.of(root.str("language")),
            minVoteCount = root.int("minVoteCount")?.coerceIn(MIN_VOTES),
            cacheLimitMb = root.int("cacheLimitMb")?.coerceIn(CACHE_MB),
            neutralColor = root.str("neutralColor")?.takeIf(::isValidColor),
            showTitleWhenUnrecognized = root.bool(K_SHOW_TITLE),
            appearance = appearance?.let { AppearanceRanges.clamp(it.applyTo(current, version == SCHEMA_V1)) },
        )
    }

    private fun JsonObject.applyTo(b: AppearanceSettings, v1: Boolean) = b.copy(
        textPreset = str(A_PRESET)?.let { p -> TextPreset.entries.firstOrNull { it.name == p } } ?: b.textPreset,
        leftPct = float(A_LEFT) ?: b.leftPct,
        topPct = float(A_TOP) ?: b.topPct,
        blockWidthPct = float(A_WIDTH) ?: b.blockWidthPct,
        rowPositionPct = (if (v1) null else float(A_ROW)) ?: b.rowPositionPct,
        rowGapPct = (if (v1) float(A_LEGACY_BOTTOM)?.let { LEGACY_ROW_PCT - it } else float(A_ROW_GAP)) ?: b.rowGapPct,
        blockAnchor = (if (v1) null else str(A_ANCHOR))?.let { n -> BlockAnchor.entries.firstOrNull { it.name == n } } ?: b.blockAnchor,
        overviewLines = int(A_LINES) ?: b.overviewLines,
        scrimIntensity = float(A_SCRIM) ?: b.scrimIntensity,
        showLogo = bool(A_LOGO) ?: b.showLogo,
        showYear = bool(A_YEAR) ?: b.showYear,
        showType = bool(A_TYPE) ?: b.showType,
        showDuration = bool(A_DURATION) ?: b.showDuration,
        showGenres = bool(A_GENRES) ?: b.showGenres,
        showRating = bool(A_RATING) ?: b.showRating,
        showOverview = bool(A_OVERVIEW) ?: b.showOverview,
        showAppBadge = bool(A_APP_BADGE) ?: b.showAppBadge,
    )

    // Primitives are read by content: both 0.5 and "0.5" are accepted; objects, arrays and null are skipped.
    private fun JsonObject.prim(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(name: String): Int? = prim(name)?.toDoubleOrNull()?.takeIf { it.isFinite() && it % 1.0 == 0.0 && kotlin.math.abs(it) <= Int.MAX_VALUE }?.toInt()
    private fun JsonObject.float(name: String): Float? = prim(name)?.toFloatOrNull()?.takeIf { it.isFinite() }
    private fun JsonObject.bool(name: String): Boolean? = prim(name)?.toBooleanStrictOrNull()

    private const val K_SHOW_TITLE = "showTitleWhenUnrecognized"
    private const val A_PRESET = "textPreset"
    private const val A_LEFT = "leftPct"
    private const val A_TOP = "topPct"
    private const val A_WIDTH = "blockWidthPct"
    private const val A_ROW = "rowPositionPct"
    private const val A_ROW_GAP = "rowGapPct"
    private const val A_ANCHOR = "blockAnchor"
    /** Schema 1 only. */
    private const val A_LEGACY_BOTTOM = "bottomLimitPct"
    private const val A_LINES = "overviewLines"
    private const val A_SCRIM = "scrimIntensity"
    private const val A_LOGO = "showLogo"
    private const val A_YEAR = "showYear"
    private const val A_TYPE = "showType"
    private const val A_DURATION = "showDuration"
    private const val A_GENRES = "showGenres"
    private const val A_RATING = "showRating"
    private const val A_OVERVIEW = "showOverview"
    private const val A_APP_BADGE = "showAppBadge"
}
