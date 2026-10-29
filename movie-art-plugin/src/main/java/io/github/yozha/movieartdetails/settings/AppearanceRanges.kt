package io.github.yozha.movieartdetails.settings

import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.render.LayoutEngine

/**
 * Allowed appearance ranges. The limits make it impossible to move the block into the clock area
 * (x ≥ 74% where y ≤ 20%) or the launcher's card row (y ≥ [ROW] position):
 * - the block top is always ≤ 20%, so the right edge left + width ≤ [MAX_RIGHT] = 72% (with a shadow margin);
 * - the bottom limit is the row position minus a gap of at least 3% ([ROW_GAP]); block height
 *   bottom − top ≥ [MIN_HEIGHT], otherwise not even the meta line fits.
 * The renderer also clips the frame to the same limits (LayoutEngine.frame); this is input validation.
 */
object AppearanceRanges {
    val LEFT = 0.02f..0.20f
    val TOP = 0.03f..0.20f
    val WIDTH = 0.30f..0.66f
    /** 55% is Projectivy's layout with its bottom info panel on. */
    val ROW = 0.55f..LayoutEngine.MAX_ROW
    val ROW_GAP = LayoutEngine.MIN_ROW_GAP..0.15f
    val OVERVIEW_LINES = 3..LayoutEngine.MAX_OVERVIEW_LINES
    val SCRIM = 0.25f..1.0f
    const val MAX_RIGHT = 0.72f
    const val MIN_HEIGHT = 0.25f

    // Values selectable with the remote (all within range).
    val LEFT_OPTIONS = listOf(0.03f, 0.04f, 0.05f, 0.06f, 0.08f, 0.10f, 0.12f, 0.15f)
    val TOP_OPTIONS = listOf(0.04f, 0.05f, 0.07f, 0.09f, 0.11f, 0.13f, 0.15f)
    val WIDTH_OPTIONS = listOf(0.36f, 0.40f, 0.43f, 0.46f, 0.50f, 0.55f, 0.60f, 0.66f)
    val ROW_OPTIONS = listOf(0.55f, 0.60f, 0.65f, 0.70f, 0.75f, 0.80f, 0.85f)
    val ROW_GAP_OPTIONS = listOf(0.03f, 0.04f, 0.05f, 0.06f, 0.08f, 0.10f, 0.12f, 0.15f)
    val SCRIM_OPTIONS = listOf(0.25f, 0.40f, 0.50f, 0.60f, 0.75f, 0.90f, 1.0f)

    /**
     * Coerces any values (including NaN from an import) into range. Order matters: left before width, top and
     * row before the gap, which shrinks so that the block keeps at least [MIN_HEIGHT].
     */
    fun clamp(a: AppearanceSettings): AppearanceSettings {
        val d = AppearanceSettings()
        val left = a.leftPct.fit(LEFT, d.leftPct)
        val top = a.topPct.fit(TOP, d.topPct)
        val width = a.blockWidthPct.fit(WIDTH, d.blockWidthPct).coerceAtMost(MAX_RIGHT - left)
        val row = a.rowPositionPct.fit(ROW, d.rowPositionPct)
        val gap = a.rowGapPct.fit(ROW_GAP, d.rowGapPct).coerceAtMost(row - top - MIN_HEIGHT)
        return a.copy(
            leftPct = left,
            topPct = top,
            blockWidthPct = width,
            rowPositionPct = row,
            rowGapPct = gap,
            overviewLines = a.overviewLines.coerceIn(OVERVIEW_LINES),
            scrimIntensity = a.scrimIntensity.fit(SCRIM, d.scrimIntensity),
        )
    }

    /** Index of the closest value in a list for remote selection. */
    fun nearest(options: List<Float>, value: Float): Int = options.indices.minBy { kotlin.math.abs(options[it] - value) }

    private fun Float.fit(range: ClosedFloatingPointRange<Float>, default: Float): Float =
        if (isNaN() || isInfinite()) default else coerceIn(range)
}
