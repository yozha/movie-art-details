package io.github.yozha.movieartdetails.settings

import io.github.yozha.movieartdetails.model.AppearanceSettings
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
)

/** Import result: only recognized, valid fields; everything else is null, meaning "leave as is". */
data class PrefsPatch(
    val language: LanguageChoice? = null,
    val minVoteCount: Int? = null,
    val cacheLimitMb: Int? = null,
    val neutralColor: String? = null,
    /** Appearance: incoming fields applied over the current values, already coerced into range. */
    val appearance: AppearanceSettings? = null,
)

/**
 * JSON for getPreferences/setPreferences. Format:
 * `{"schemaVersion":1,"language":"system","minVoteCount":10,"cacheLimitMb":200,"neutralColor":"#101216","appearance":{…}}`.
 *
 * Import is lenient: unknown fields are ignored, fields with a wrong type or format are skipped, numbers
 * are coerced into range. A different schema version, non-JSON and non-objects are rejected entirely (decode → null).
 * Manual mappings are not exported yet, since the key format may still change.
 */
object PrefsCodec {
    const val SCHEMA_VERSION = 1
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
        put("appearance", buildJsonObject {
            val a = s.appearance
            put(A_PRESET, a.textPreset.name)
            put(A_LEFT, a.leftPct)
            put(A_TOP, a.topPct)
            put(A_WIDTH, a.blockWidthPct)
            put(A_BOTTOM, a.bottomLimitPct)
            put(A_LINES, a.overviewLines)
            put(A_SCRIM, a.scrimIntensity)
            put(A_LOGO, a.showLogo)
            put(A_YEAR, a.showYear)
            put(A_TYPE, a.showType)
            put(A_DURATION, a.showDuration)
            put(A_GENRES, a.showGenres)
            put(A_RATING, a.showRating)
            put(A_OVERVIEW, a.showOverview)
        })
    }.toString()

    /** [current] is the current appearance: fields missing from the input keep their values. */
    fun decode(json: String, current: AppearanceSettings): PrefsPatch? {
        if (json.length > MAX_INPUT_CHARS) return null
        val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
        if (root.int("schemaVersion") != SCHEMA_VERSION) return null
        val appearance = root["appearance"] as? JsonObject
        return PrefsPatch(
            language = LanguageChoice.of(root.str("language")),
            minVoteCount = root.int("minVoteCount")?.coerceIn(MIN_VOTES),
            cacheLimitMb = root.int("cacheLimitMb")?.coerceIn(CACHE_MB),
            neutralColor = root.str("neutralColor")?.takeIf(::isValidColor),
            appearance = appearance?.let { AppearanceRanges.clamp(it.applyTo(current)) },
        )
    }

    private fun JsonObject.applyTo(b: AppearanceSettings) = b.copy(
        textPreset = str(A_PRESET)?.let { p -> TextPreset.entries.firstOrNull { it.name == p } } ?: b.textPreset,
        leftPct = float(A_LEFT) ?: b.leftPct,
        topPct = float(A_TOP) ?: b.topPct,
        blockWidthPct = float(A_WIDTH) ?: b.blockWidthPct,
        bottomLimitPct = float(A_BOTTOM) ?: b.bottomLimitPct,
        overviewLines = int(A_LINES) ?: b.overviewLines,
        scrimIntensity = float(A_SCRIM) ?: b.scrimIntensity,
        showLogo = bool(A_LOGO) ?: b.showLogo,
        showYear = bool(A_YEAR) ?: b.showYear,
        showType = bool(A_TYPE) ?: b.showType,
        showDuration = bool(A_DURATION) ?: b.showDuration,
        showGenres = bool(A_GENRES) ?: b.showGenres,
        showRating = bool(A_RATING) ?: b.showRating,
        showOverview = bool(A_OVERVIEW) ?: b.showOverview,
    )

    // Primitives are read by content: both 0.5 and "0.5" are accepted; objects, arrays and null are skipped.
    private fun JsonObject.prim(name: String): String? = (this[name] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content
    private fun JsonObject.str(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.int(name: String): Int? = prim(name)?.toDoubleOrNull()?.takeIf { it.isFinite() && it % 1.0 == 0.0 && kotlin.math.abs(it) <= Int.MAX_VALUE }?.toInt()
    private fun JsonObject.float(name: String): Float? = prim(name)?.toFloatOrNull()?.takeIf { it.isFinite() }
    private fun JsonObject.bool(name: String): Boolean? = prim(name)?.toBooleanStrictOrNull()

    private const val A_PRESET = "textPreset"
    private const val A_LEFT = "leftPct"
    private const val A_TOP = "topPct"
    private const val A_WIDTH = "blockWidthPct"
    private const val A_BOTTOM = "bottomLimitPct"
    private const val A_LINES = "overviewLines"
    private const val A_SCRIM = "scrimIntensity"
    private const val A_LOGO = "showLogo"
    private const val A_YEAR = "showYear"
    private const val A_TYPE = "showType"
    private const val A_DURATION = "showDuration"
    private const val A_GENRES = "showGenres"
    private const val A_RATING = "showRating"
    private const val A_OVERVIEW = "showOverview"
}
