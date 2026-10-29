package io.github.yozha.movieartdetails.settings

import androidx.annotation.StringRes
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import io.github.yozha.movieartdetails.R
import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.TextPreset
import kotlin.math.roundToInt

/**
 * Appearance: text preset, margins, width, bottom edge, overview lines, scrim, field visibility,
 * neutral background (and its tint from the app card), vote threshold. Each value is picked from a list
 * within the allowed range (AppearanceRanges), so the block cannot be moved into the clock or row area.
 * "Reset appearance" restores only AppearanceSettings: the key, corrections and language are untouched.
 */
class AppearanceStep : BaseStep() {
    private val settings by lazy { AppSettings(requireContext()) }

    private fun pct(v: Float) = getString(R.string.percent_format, (v * 100).roundToInt())

    /** Picks an AppearanceSettings field from a list of labeled values. */
    private fun <T> appearanceChoice(
        id: Long,
        @StringRes title: Int,
        values: List<T>,
        label: (T) -> String,
        index: (AppearanceSettings) -> Int,
        update: AppearanceSettings.(T) -> AppearanceSettings,
    ) = Choice(id, getString(title), values.map(label), { index(settings.appearance()) }) { i ->
        settings.setAppearance(settings.appearance().update(values[i]))
    }

    private fun floatChoice(
        id: Long,
        @StringRes title: Int,
        values: List<Float>,
        get: (AppearanceSettings) -> Float,
        update: AppearanceSettings.(Float) -> AppearanceSettings,
    ) = appearanceChoice(id, title, values, ::pct, { AppearanceRanges.nearest(values, get(it)) }, update)

    override val choices: List<Choice> by lazy {
        listOf(
            appearanceChoice(
                ID_PRESET, R.string.appearance_preset, TextPreset.entries.toList(),
                { getString(PRESET_LABELS.getValue(it)) }, { it.textPreset.ordinal },
            ) { copy(textPreset = it) },
            floatChoice(ID_LEFT, R.string.appearance_left, AppearanceRanges.LEFT_OPTIONS, { it.leftPct }) { copy(leftPct = it) },
            floatChoice(ID_TOP, R.string.appearance_top, AppearanceRanges.TOP_OPTIONS, { it.topPct }) { copy(topPct = it) },
            floatChoice(ID_WIDTH, R.string.appearance_width, AppearanceRanges.WIDTH_OPTIONS, { it.blockWidthPct }) { copy(blockWidthPct = it) },
            floatChoice(ID_BOTTOM, R.string.appearance_bottom, AppearanceRanges.BOTTOM_OPTIONS, { it.bottomLimitPct }) { copy(bottomLimitPct = it) },
            appearanceChoice(
                ID_LINES, R.string.appearance_overview_lines, AppearanceRanges.OVERVIEW_LINES.toList(),
                { it.toString() }, { (it.overviewLines - AppearanceRanges.OVERVIEW_LINES.first).coerceAtLeast(0) },
            ) { copy(overviewLines = it) },
            floatChoice(ID_SCRIM, R.string.appearance_scrim, AppearanceRanges.SCRIM_OPTIONS, { it.scrimIntensity }) { copy(scrimIntensity = it) },
            Choice(
                ID_NEUTRAL, getString(R.string.appearance_neutral), NEUTRAL_COLORS.map { getString(it.second) },
                { NEUTRAL_COLORS.indexOfFirst { it.first.equals(settings.neutralColor(), ignoreCase = true) }.coerceAtLeast(0) },
            ) { settings.setNeutralColor(NEUTRAL_COLORS[it].first) },
            Choice(
                ID_MIN_VOTES, getString(R.string.appearance_min_votes),
                MIN_VOTES.map { if (it == 0) getString(R.string.appearance_min_votes_any) else it.toString() },
                { MIN_VOTES.indices.minBy { i -> kotlin.math.abs(MIN_VOTES[i] - settings.minVoteCount()) } },
            ) { settings.setMinVoteCount(MIN_VOTES[it]) },
        )
    }

    override fun guidance() = Guidance(
        getString(R.string.appearance_title),
        getString(R.string.appearance_description),
        getString(R.string.plugin_name),
        null,
    )

    override fun buildActions(): List<GuidedAction> {
        val a = settings.appearance()
        return listOf(item(ID_PREVIEW, getString(R.string.preview_title), getString(R.string.preview_summary), next = true)) +
            choices.map(::choiceAction) +
            listOf(
                checkbox(ID_SHOW_LOGO, getString(R.string.field_logo), a.showLogo),
                checkbox(ID_SHOW_YEAR, getString(R.string.field_year), a.showYear),
                checkbox(ID_SHOW_TYPE, getString(R.string.field_type), a.showType),
                checkbox(ID_SHOW_DURATION, getString(R.string.field_duration), a.showDuration, getString(R.string.field_duration_hint)),
                checkbox(ID_SHOW_GENRES, getString(R.string.field_genres), a.showGenres),
                checkbox(ID_SHOW_RATING, getString(R.string.field_rating), a.showRating),
                checkbox(ID_SHOW_OVERVIEW, getString(R.string.field_overview), a.showOverview, getString(R.string.field_overview_hint)),
                checkbox(ID_TINT, getString(R.string.appearance_tint_from_card), settings.tintNeutralFromCard(), getString(R.string.appearance_tint_from_card_hint)),
                item(ID_RESET, getString(R.string.appearance_reset), getString(R.string.appearance_reset_hint)),
            )
    }

    override fun onToggle(id: Long, checked: Boolean) {
        if (id == ID_TINT) {
            settings.setTintNeutralFromCard(checked)
            return
        }
        val a = settings.appearance()
        settings.setAppearance(
            when (id) {
                ID_SHOW_LOGO -> a.copy(showLogo = checked)
                ID_SHOW_YEAR -> a.copy(showYear = checked)
                ID_SHOW_TYPE -> a.copy(showType = checked)
                ID_SHOW_DURATION -> a.copy(showDuration = checked)
                ID_SHOW_GENRES -> a.copy(showGenres = checked)
                ID_SHOW_RATING -> a.copy(showRating = checked)
                ID_SHOW_OVERVIEW -> a.copy(showOverview = checked)
                else -> return
            },
        )
    }

    override fun onClick(action: GuidedAction) {
        when (action.id) {
            ID_PREVIEW -> PreviewFragment.show(parentFragmentManager)
            ID_RESET -> {
                settings.resetAppearance()
                rebuild()
                describe(ID_RESET, getString(R.string.appearance_reset_done))
            }
        }
    }

    private companion object {
        const val ID_PREVIEW = 1L
        const val ID_PRESET = 2L
        const val ID_LEFT = 3L
        const val ID_TOP = 4L
        const val ID_WIDTH = 5L
        const val ID_BOTTOM = 6L
        const val ID_LINES = 7L
        const val ID_SCRIM = 8L
        const val ID_NEUTRAL = 9L
        const val ID_MIN_VOTES = 10L
        const val ID_SHOW_LOGO = 20L
        const val ID_SHOW_YEAR = 21L
        const val ID_SHOW_TYPE = 22L
        const val ID_SHOW_DURATION = 23L
        const val ID_SHOW_GENRES = 24L
        const val ID_SHOW_RATING = 25L
        const val ID_SHOW_OVERVIEW = 26L
        const val ID_TINT = 27L
        const val ID_RESET = 30L

        val PRESET_LABELS = mapOf(
            TextPreset.SMALL to R.string.preset_small,
            TextPreset.MEDIUM to R.string.preset_medium,
            TextPreset.LARGE to R.string.preset_large,
        )
        val NEUTRAL_COLORS = listOf(
            AppSettings.DEFAULT_NEUTRAL to R.string.neutral_graphite,
            "#000000" to R.string.neutral_black,
            "#0D1B2A" to R.string.neutral_navy,
            "#1E1E1E" to R.string.neutral_gray,
        )
        val MIN_VOTES = listOf(0, 10, 25, 50, 100, 500)
    }
}
