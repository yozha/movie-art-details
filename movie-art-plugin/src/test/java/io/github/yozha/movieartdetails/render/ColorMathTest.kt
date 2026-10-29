package io.github.yozha.movieartdetails.render

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorMathTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test
    fun luminanceAndContrastMatchWcag() {
        assertEquals(1.0, ColorMath.luminance(white), 1e-9)
        assertEquals(0.0, ColorMath.luminance(black), 1e-9)
        assertEquals(21.0, ColorMath.contrast(1.0, 0.0), 1e-9)
        assertEquals(21.0, ColorMath.contrast(0.0, 1.0), 1e-9)
        // Gray 128: encode(luminance) returns the original channel value.
        assertEquals(128 / 255.0, ColorMath.encode(ColorMath.luminance(0xFF808080.toInt())), 1e-3)
    }

    @Test
    fun backgroundLimitGivesRequestedContrast() {
        val text = ColorMath.luminance(0xFFEDEDED.toInt())
        val bg = ColorMath.maxBackground(text, 4.5)
        assertEquals(4.5, ColorMath.contrast(text, bg), 1e-9)
    }

    @Test
    fun requiredAlpha() {
        assertEquals(0.0, ColorMath.requiredAlpha(0.3, 0.05, 0.42), 1e-9)
        assertEquals(0.58, ColorMath.requiredAlpha(1.0, 0.0, 0.42), 1e-9)
        val a = ColorMath.requiredAlpha(0.9, 0.1, 0.42)
        assertEquals(0.42, (1 - a) * 0.9 + a * 0.1, 1e-9)
    }

    @Test
    fun scrimTintIsNearBlackWithTenPercentOfAverage() {
        assertEquals(0xFF202020.toInt(), ColorMath.scrimTint(white))
        assertEquals(0xFF060606.toInt(), ColorMath.scrimTint(black))
        assertEquals(0xFF200606.toInt(), ColorMath.scrimTint(0xFFFF0000.toInt()))
    }

    @Test
    fun statsPercentileMeanAndAverage() {
        val stats = LumaStats()
        repeat(9) { stats.add(black) }
        stats.add(white)
        assertEquals(10, stats.count)
        assertEquals(0.0, stats.percentile(0.9), 1e-9)
        assertEquals(1.0, stats.percentile(1.0), 1e-9)
        assertEquals(0.1, stats.meanLuminance(), 1e-9)
        assertEquals(0xFF191919.toInt(), stats.average())
        assertEquals(0.0, LumaStats().percentile(0.9), 1e-9)
        assertEquals(black, LumaStats().average())
    }

    @Test
    fun sampleSizeIsPowerOfTwoNotBelowTarget() {
        assertEquals(1, ImageDecoder.sampleSize(1f))
        assertEquals(1, ImageDecoder.sampleSize(2f))
        assertEquals(1, ImageDecoder.sampleSize(0.6f))
        assertEquals(2, ImageDecoder.sampleSize(0.5f))
        assertEquals(2, ImageDecoder.sampleSize(0.3f))
        assertEquals(4, ImageDecoder.sampleSize(0.25f))
        assertEquals(8, ImageDecoder.sampleSize(0.1f))
        assertEquals(1, ImageDecoder.sampleSize(Float.NaN))
        assertEquals(1, ImageDecoder.sampleSize(0f))
        // 3840×2160 → exactly 1920×1080; the result is never below the target (a smaller source is not downscaled).
        for (w in listOf(1280, 1920, 2560, 3840, 7680)) {
            val sample = ImageDecoder.sampleSize(1920f / w)
            assertTrue(w / sample >= minOf(w, 1920))
        }
        assertEquals(2, ImageDecoder.sampleSize(1920f / 3840))
    }
}
