package io.github.yozha.movieartdetails.settings

import io.github.yozha.movieartdetails.model.AppearanceSettings

/**
 * Allowed appearance ranges. The limits make it impossible to move the block into the clock area
 * (x ≥ 74% where y ≤ 20%) or the launcher row area (y > 52%):
 * - the block top is always ≤ 20%, so the right edge left + width ≤ [MAX_RIGHT] = 72% (with a shadow margin);
 * - bottom ≤ 52%; block height bottom − top ≥ [MIN_HEIGHT], otherwise not even the meta line fits.
 * The renderer also clips the frame to the same limits (LayoutEngine.frame); this is input validation.
 */
object AppearanceRanges {
    val LEFT = 0.02f..0.20f
    val TOP = 0.03f..0.20f
    val WIDTH = 0.30f..0.66f
    val BOTTOM = 0.40f..0.52f
    val OVERVIEW_LINES = 3..5
    val SCRIM = 0.25f..1.0f
    const val MAX_RIGHT = 0.72f
    const val MIN_HEIGHT = 0.25f

    // Values selectable with the remote (all within range).
    val LEFT_OPTIONS = listOf(0.03f, 0.04f, 0.05f, 0.06f, 0.08f, 0.10f, 0.12f, 0.15f)
    val TOP_OPTIONS = listOf(0.04f, 0.05f, 0.07f, 0.09f, 0.11f, 0.13f, 0.15f)
    val WIDTH_OPTIONS = listOf(0.36f, 0.40f, 0.43f, 0.46f, 0.50f, 0.55f, 0.60f, 0.66f)
    val BOTTOM_OPTIONS = listOf(0.44f, 0.46f, 0.48f, 0.49f, 0.50f, 0.52f)
    val SCRIM_OPTIONS = listOf(0.25f, 0.40f, 0.50f, 0.60f, 0.75f, 0.90f, 1.0f)

    /** Coerces any values (including NaN from an import) into range; order matters: left first, then width. */
    fun clamp(a: AppearanceSettings): AppearanceSettings {
        val d = AppearanceSettings()
        val left = a.leftPct.fit(LEFT, d.leftPct)
        val top = a.topPct.fit(TOP, d.topPct)
        val width = a.blockWidthPct.fit(WIDTH, d.blockWidthPct).coerceAtMost(MAX_RIGHT - left)
        val bottom = a.bottomLimitPct.fit(BOTTOM, d.bottomLimitPct).coerceAtLeast(top + MIN_HEIGHT).coerceAtMost(BOTTOM.endInclusive)
        return a.copy(
            leftPct = left,
            topPct = top,
            blockWidthPct = width,
            bottomLimitPct = bottom,
            overviewLines = a.overviewLines.coerceIn(OVERVIEW_LINES),
            scrimIntensity = a.scrimIntensity.fit(SCRIM, d.scrimIntensity),
        )
    }

    /** Index of the closest value in a list for remote selection. */
    fun nearest(options: List<Float>, value: Float): Int = options.indices.minBy { kotlin.math.abs(options[it] - value) }

    private fun Float.fit(range: ClosedFloatingPointRange<Float>, default: Float): Float =
        if (isNaN() || isInfinite()) default else coerceIn(range)
}
