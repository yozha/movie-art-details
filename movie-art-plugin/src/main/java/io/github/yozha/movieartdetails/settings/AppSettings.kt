package io.github.yozha.movieartdetails.settings

import android.content.Context
import android.content.SharedPreferences
import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.DisplayLanguage
import io.github.yozha.movieartdetails.model.SettingsRepository
import io.github.yozha.movieartdetails.model.TextPreset
import java.util.Locale

/**
 * User settings on top of SharedPreferences.
 *
 * - "settings": appearance, language, vote threshold, cache limit, neutral color. Included in Auto Backup
 *   (see res/xml/backup_rules.xml): it holds no key, no history and no internal state.
 * - "settings_local": diagnostics flags. Excluded from backup and export: consent to record titles in the log
 *   must not carry over to another device through a backup.
 *
 * Values are read from SharedPreferences on every call (the platform caches them in memory), so
 * multiple instances in one process stay consistent. Everything read is coerced into range.
 */
class AppSettings(context: Context) : SettingsRepository {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val local: SharedPreferences = app.getSharedPreferences(PREFS_LOCAL, Context.MODE_PRIVATE)

    fun languageChoice(): LanguageChoice = LanguageChoice.of(prefs.getString(K_LANGUAGE, null)) ?: LanguageChoice.SYSTEM

    override fun language(): DisplayLanguage = languageChoice().resolve(Locale.getDefault().language)

    override fun appearance(): AppearanceSettings {
        val d = AppearanceSettings()
        return AppearanceRanges.clamp(
            AppearanceSettings(
                textPreset = TextPreset.entries.firstOrNull { it.name == prefs.getString(K_PRESET, null) } ?: d.textPreset,
                leftPct = prefs.getFloat(K_LEFT, d.leftPct),
                topPct = prefs.getFloat(K_TOP, d.topPct),
                blockWidthPct = prefs.getFloat(K_WIDTH, d.blockWidthPct),
                bottomLimitPct = prefs.getFloat(K_BOTTOM, d.bottomLimitPct),
                overviewLines = prefs.getInt(K_LINES, d.overviewLines),
                scrimIntensity = prefs.getFloat(K_SCRIM, d.scrimIntensity),
                showLogo = prefs.getBoolean(K_LOGO, d.showLogo),
                showYear = prefs.getBoolean(K_YEAR, d.showYear),
                showType = prefs.getBoolean(K_TYPE, d.showType),
                showDuration = prefs.getBoolean(K_DURATION, d.showDuration),
                showGenres = prefs.getBoolean(K_GENRES, d.showGenres),
                showRating = prefs.getBoolean(K_RATING, d.showRating),
                showOverview = prefs.getBoolean(K_OVERVIEW, d.showOverview),
            ),
        )
    }

    override fun minVoteCount(): Int = prefs.getInt(K_MIN_VOTES, DEFAULT_MIN_VOTES).coerceIn(PrefsCodec.MIN_VOTES)

    fun cacheLimitMb(): Int = prefs.getInt(K_CACHE_MB, DEFAULT_CACHE_MB).coerceIn(PrefsCodec.CACHE_MB)

    override fun cacheLimitBytes(): Long = cacheLimitMb() * 1024L * 1024L

    override fun neutralColor(): String = prefs.getString(K_NEUTRAL, null)?.takeIf(PrefsCodec::isValidColor) ?: DEFAULT_NEUTRAL

    /** Tint the neutral background on app cards from their darkColor; off by default. */
    override fun tintNeutralFromCard(): Boolean = prefs.getBoolean(K_TINT, false)

    override fun diagnosticsEnabled(): Boolean = local.getBoolean(K_DIAG, false)

    /** Only meaningful while diagnostics are enabled. */
    override fun logTitlesInDiagnostics(): Boolean = diagnosticsEnabled() && local.getBoolean(K_DIAG_TITLES, false)

    fun setLanguage(choice: LanguageChoice) = prefs.edit().putString(K_LANGUAGE, choice.id).apply()

    fun setAppearance(a: AppearanceSettings) = prefs.edit().putAppearance(AppearanceRanges.clamp(a)).apply()

    /** Resets appearance: only AppearanceSettings fields; the key, corrections, language and cache are untouched. */
    fun resetAppearance() = setAppearance(AppearanceSettings())

    fun setMinVoteCount(v: Int) = prefs.edit().putInt(K_MIN_VOTES, v.coerceIn(PrefsCodec.MIN_VOTES)).apply()

    fun setCacheLimitMb(mb: Int) = prefs.edit().putInt(K_CACHE_MB, mb.coerceIn(PrefsCodec.CACHE_MB)).apply()

    fun setNeutralColor(color: String) {
        if (PrefsCodec.isValidColor(color)) prefs.edit().putString(K_NEUTRAL, color).apply()
    }

    fun setTintNeutralFromCard(on: Boolean) = prefs.edit().putBoolean(K_TINT, on).apply()

    fun setDiagnosticsEnabled(on: Boolean) = local.edit().putBoolean(K_DIAG, on).apply()

    fun setLogTitlesInDiagnostics(on: Boolean) = local.edit().putBoolean(K_DIAG_TITLES, on).apply()

    override fun exportJson(): String = PrefsCodec.encode(
        PrefsSnapshot(languageChoice(), appearance(), minVoteCount(), cacheLimitMb(), neutralColor()),
    )

    /** No clear(): only recognized allowlisted fields are written; commit, so the host gets an accurate result. */
    override fun importJson(json: String): Boolean {
        val patch = PrefsCodec.decode(json, appearance()) ?: return false
        val e = prefs.edit()
        patch.language?.let { e.putString(K_LANGUAGE, it.id) }
        patch.minVoteCount?.let { e.putInt(K_MIN_VOTES, it) }
        patch.cacheLimitMb?.let { e.putInt(K_CACHE_MB, it) }
        patch.neutralColor?.let { e.putString(K_NEUTRAL, it) }
        patch.appearance?.let { e.putAppearance(it) }
        return e.commit()
    }

    private fun SharedPreferences.Editor.putAppearance(a: AppearanceSettings): SharedPreferences.Editor = this
        .putString(K_PRESET, a.textPreset.name)
        .putFloat(K_LEFT, a.leftPct)
        .putFloat(K_TOP, a.topPct)
        .putFloat(K_WIDTH, a.blockWidthPct)
        .putFloat(K_BOTTOM, a.bottomLimitPct)
        .putInt(K_LINES, a.overviewLines)
        .putFloat(K_SCRIM, a.scrimIntensity)
        .putBoolean(K_LOGO, a.showLogo)
        .putBoolean(K_YEAR, a.showYear)
        .putBoolean(K_TYPE, a.showType)
        .putBoolean(K_DURATION, a.showDuration)
        .putBoolean(K_GENRES, a.showGenres)
        .putBoolean(K_RATING, a.showRating)
        .putBoolean(K_OVERVIEW, a.showOverview)

    companion object {
        const val PREFS = "settings"
        const val PREFS_LOCAL = "settings_local"
        const val DEFAULT_MIN_VOTES = 10
        const val DEFAULT_CACHE_MB = 200
        const val DEFAULT_NEUTRAL = "#101216"

        private const val K_LANGUAGE = "language"
        private const val K_MIN_VOTES = "min_vote_count"
        private const val K_CACHE_MB = "cache_limit_mb"
        private const val K_NEUTRAL = "neutral_color"
        private const val K_TINT = "neutral_tint_from_card"
        private const val K_PRESET = "appearance_text_preset"
        private const val K_LEFT = "appearance_left_pct"
        private const val K_TOP = "appearance_top_pct"
        private const val K_WIDTH = "appearance_block_width_pct"
        private const val K_BOTTOM = "appearance_bottom_limit_pct"
        private const val K_LINES = "appearance_overview_lines"
        private const val K_SCRIM = "appearance_scrim_intensity"
        private const val K_LOGO = "appearance_show_logo"
        private const val K_YEAR = "appearance_show_year"
        private const val K_TYPE = "appearance_show_type"
        private const val K_DURATION = "appearance_show_duration"
        private const val K_GENRES = "appearance_show_genres"
        private const val K_RATING = "appearance_show_rating"
        private const val K_OVERVIEW = "appearance_show_overview"
        private const val K_DIAG = "diagnostics_enabled"
        private const val K_DIAG_TITLES = "diagnostics_log_titles"
    }
}
