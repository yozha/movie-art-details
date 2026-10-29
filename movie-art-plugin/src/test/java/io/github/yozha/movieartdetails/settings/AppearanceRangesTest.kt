package io.github.yozha.movieartdetails.settings

import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.BlockAnchor
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceRangesTest {
    private val eps = 1e-6f

    @Test
    fun defaultsAreUntouched() {
        assertEquals(AppearanceSettings(), AppearanceRanges.clamp(AppearanceSettings()))
    }

    /** The default card row keeps today's layout: the block ends at 49%. */
    @Test
    fun defaultBottomLimitIsUnchanged() {
        assertEquals(0.49f, AppearanceSettings().bottomLimitPct, eps)
        assertEquals(BlockAnchor.TOP, AppearanceSettings().blockAnchor)
    }

    @Test
    fun everyOptionComboStaysOutOfClockAndRowZones() {
        for (l in AppearanceRanges.LEFT_OPTIONS) for (t in AppearanceRanges.TOP_OPTIONS) for (w in AppearanceRanges.WIDTH_OPTIONS)
            for (r in AppearanceRanges.ROW_OPTIONS) for (g in AppearanceRanges.ROW_GAP_OPTIONS) {
                val a = AppearanceRanges.clamp(AppearanceSettings(leftPct = l, topPct = t, blockWidthPct = w, rowPositionPct = r, rowGapPct = g))
                assertSafe(a)
                assertEquals(a.toString(), r, a.rowPositionPct)
            }
    }

    /** A gap that would leave the block too short shrinks; a lower row lifts that limit. */
    @Test
    fun gapShrinksToKeepMinimumHeight() {
        val tight = AppearanceRanges.clamp(AppearanceSettings(topPct = 0.20f, rowPositionPct = 0.55f, rowGapPct = 0.15f))
        assertEquals(0.10f, tight.rowGapPct, 1e-5f)
        val roomy = AppearanceRanges.clamp(AppearanceSettings(topPct = 0.20f, rowPositionPct = 0.70f, rowGapPct = 0.15f))
        assertEquals(0.15f, roomy.rowGapPct, eps)
    }

    /** Rendered images are cached by the fingerprint, so every layout setting must change it. */
    @Test
    fun fingerprintCoversRowAnchorAndOverviewLines() {
        val d = AppearanceSettings()
        listOf(
            d.copy(rowPositionPct = 0.70f),
            d.copy(rowGapPct = 0.04f),
            d.copy(blockAnchor = BlockAnchor.ABOVE_ROW),
            d.copy(overviewLines = 8),
        ).forEach { assertNotEquals(it.toString(), d.fingerprint(), it.fingerprint()) }
        assertEquals(d.fingerprint(), AppearanceSettings().fingerprint())
    }

    @Test
    fun extremeAndNonFiniteValuesAreClamped() {
        listOf(
            AppearanceSettings(leftPct = 1f, topPct = 1f, blockWidthPct = 1f, rowPositionPct = 1f, rowGapPct = -1f, overviewLines = 99, scrimIntensity = 9f),
            AppearanceSettings(leftPct = -1f, topPct = -1f, blockWidthPct = 0f, rowPositionPct = 0f, rowGapPct = 1f, overviewLines = -1, scrimIntensity = -1f),
            AppearanceSettings(
                leftPct = Float.NaN, topPct = Float.POSITIVE_INFINITY, blockWidthPct = Float.NaN,
                rowPositionPct = Float.NaN, rowGapPct = Float.NEGATIVE_INFINITY, scrimIntensity = Float.NaN,
            ),
        ).forEach { assertSafe(AppearanceRanges.clamp(it)) }
        val nan = AppearanceRanges.clamp(AppearanceSettings(leftPct = Float.NaN, scrimIntensity = Float.NaN))
        assertEquals(AppearanceSettings().leftPct, nan.leftPct)
        assertEquals(AppearanceSettings().scrimIntensity, nan.scrimIntensity)
        val nanRow = AppearanceRanges.clamp(AppearanceSettings(rowPositionPct = Float.NaN, rowGapPct = Float.NaN))
        assertEquals(AppearanceSettings().bottomLimitPct, nanRow.bottomLimitPct, eps)
    }

    @Test
    fun optionsLieInsideRanges() {
        assertTrue(AppearanceRanges.LEFT_OPTIONS.all { it in AppearanceRanges.LEFT })
        assertTrue(AppearanceRanges.TOP_OPTIONS.all { it in AppearanceRanges.TOP })
        assertTrue(AppearanceRanges.WIDTH_OPTIONS.all { it in AppearanceRanges.WIDTH })
        assertTrue(AppearanceRanges.ROW_OPTIONS.all { it in AppearanceRanges.ROW })
        assertTrue(AppearanceRanges.ROW_GAP_OPTIONS.all { it in AppearanceRanges.ROW_GAP })
        assertTrue(AppearanceSettings().rowPositionPct in AppearanceRanges.ROW_OPTIONS)
        assertTrue(AppearanceSettings().rowGapPct in AppearanceRanges.ROW_GAP_OPTIONS)
        assertEquals(3..8, AppearanceRanges.OVERVIEW_LINES)
        assertTrue(AppearanceRanges.SCRIM_OPTIONS.all { it in AppearanceRanges.SCRIM })
        assertEquals(3, AppearanceRanges.nearest(AppearanceRanges.LEFT_OPTIONS, 0.061f))
    }

    private fun assertSafe(a: AppearanceSettings) {
        val msg = a.toString()
        assertTrue(msg, a.topPct <= 0.20f + eps)                             // the block starts in the clock band…
        assertTrue(msg, a.leftPct + a.blockWidthPct <= 0.74f - 0.02f + eps)  // …so its right edge stays left of 74% with a margin
        assertTrue(msg, a.rowPositionPct in 0.55f..0.85f)
        assertTrue(msg, a.bottomLimitPct <= a.rowPositionPct - 0.03f + eps)  // the hard gap above the card row
        assertTrue(msg, a.bottomLimitPct - a.topPct >= AppearanceRanges.MIN_HEIGHT - eps)
        assertTrue(msg, a.blockWidthPct > 0f && a.leftPct > 0f)
        assertTrue(msg, a.overviewLines in 3..8)
        assertTrue(msg, a.scrimIntensity in 0f..1f)
    }
}
