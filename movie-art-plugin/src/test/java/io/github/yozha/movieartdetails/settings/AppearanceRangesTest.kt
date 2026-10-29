package io.github.yozha.movieartdetails.settings

import io.github.yozha.movieartdetails.model.AppearanceSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceRangesTest {
    private val eps = 1e-6f

    @Test
    fun defaultsAreUntouched() {
        assertEquals(AppearanceSettings(), AppearanceRanges.clamp(AppearanceSettings()))
    }

    @Test
    fun everyOptionComboStaysOutOfClockAndRowZones() {
        for (l in AppearanceRanges.LEFT_OPTIONS) for (t in AppearanceRanges.TOP_OPTIONS)
            for (w in AppearanceRanges.WIDTH_OPTIONS) for (b in AppearanceRanges.BOTTOM_OPTIONS) {
                val a = AppearanceRanges.clamp(AppearanceSettings(leftPct = l, topPct = t, blockWidthPct = w, bottomLimitPct = b))
                assertSafe(a)
            }
    }

    @Test
    fun extremeAndNonFiniteValuesAreClamped() {
        listOf(
            AppearanceSettings(leftPct = 1f, topPct = 1f, blockWidthPct = 1f, bottomLimitPct = 1f, overviewLines = 99, scrimIntensity = 9f),
            AppearanceSettings(leftPct = -1f, topPct = -1f, blockWidthPct = 0f, bottomLimitPct = 0f, overviewLines = -1, scrimIntensity = -1f),
            AppearanceSettings(leftPct = Float.NaN, topPct = Float.POSITIVE_INFINITY, blockWidthPct = Float.NaN, bottomLimitPct = Float.NaN, scrimIntensity = Float.NaN),
        ).forEach { assertSafe(AppearanceRanges.clamp(it)) }
        val nan = AppearanceRanges.clamp(AppearanceSettings(leftPct = Float.NaN, scrimIntensity = Float.NaN))
        assertEquals(AppearanceSettings().leftPct, nan.leftPct)
        assertEquals(AppearanceSettings().scrimIntensity, nan.scrimIntensity)
    }

    @Test
    fun optionsLieInsideRanges() {
        assertTrue(AppearanceRanges.LEFT_OPTIONS.all { it in AppearanceRanges.LEFT })
        assertTrue(AppearanceRanges.TOP_OPTIONS.all { it in AppearanceRanges.TOP })
        assertTrue(AppearanceRanges.WIDTH_OPTIONS.all { it in AppearanceRanges.WIDTH })
        assertTrue(AppearanceRanges.BOTTOM_OPTIONS.all { it in AppearanceRanges.BOTTOM })
        assertTrue(AppearanceRanges.SCRIM_OPTIONS.all { it in AppearanceRanges.SCRIM })
        assertEquals(3, AppearanceRanges.nearest(AppearanceRanges.LEFT_OPTIONS, 0.061f))
    }

    private fun assertSafe(a: AppearanceSettings) {
        val msg = a.toString()
        assertTrue(msg, a.topPct <= 0.20f + eps)                             // the block starts in the clock band…
        assertTrue(msg, a.leftPct + a.blockWidthPct <= 0.74f - 0.02f + eps)  // …so its right edge stays left of 74% with a margin
        assertTrue(msg, a.bottomLimitPct <= 0.52f + eps)
        assertTrue(msg, a.bottomLimitPct - a.topPct >= AppearanceRanges.MIN_HEIGHT - eps)
        assertTrue(msg, a.blockWidthPct > 0f && a.leftPct > 0f)
        assertTrue(msg, a.overviewLines in 3..5)
        assertTrue(msg, a.scrimIntensity in 0f..1f)
    }
}
