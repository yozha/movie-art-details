// scrimTint is adapted from rfmarcos/projectivy-plugin-sofadrop @ 0f063c19dcc9
// (app/src/main/java/com/rfmarcos/sofadrop/WallpaperRenderer.kt), Apache License 2.0:
// a dark base plus 10% of the frame's average color, per channel.
// Modified: extracted into a pure function on an ARGB Int with no Android dependencies.

package io.github.yozha.movieartdetails.render

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/*
 * Color math for the scrim and the logo checks, free of Android types.
 * Colors are ARGB Ints as in android.graphics.Color. Luminance is relative (WCAG, linear 0..1);
 * "encoded" is the same luminance in sRGB gamma, 0..1 (the space Canvas blends in).
 */
object ColorMath {
    private val LINEAR = DoubleArray(256) { c ->
        val v = c / 255.0
        if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Int): Double =
        0.2126 * LINEAR[(argb shr 16) and 0xFF] + 0.7152 * LINEAR[(argb shr 8) and 0xFF] + 0.0722 * LINEAR[argb and 0xFF]

    fun encode(linear: Double): Double =
        if (linear <= 0.0031308) linear * 12.92 else 1.055 * linear.coerceIn(0.0, 1.0).pow(1 / 2.4) - 0.055

    /** WCAG contrast ratio of two luminances: 1..21. */
    fun contrast(a: Double, b: Double): Double = (max(a, b) + 0.05) / (min(a, b) + 0.05)

    /** Highest background luminance at which text with luminance [text] still reaches contrast [ratio]. */
    fun maxBackground(text: Double, ratio: Double): Double = ((text + 0.05) / ratio - 0.05).coerceAtLeast(0.0)

    /**
     * Opacity of a fill with luminance [tint] that brings background [background] down to [target].
     * All values are encoded; blending is in sRGB, as on Canvas, and estimated by luminance only, ignoring hue.
     */
    fun requiredAlpha(background: Double, tint: Double, target: Double): Double =
        if (background <= target || background <= tint) 0.0 else ((background - target) / (background - tint)).coerceIn(0.0, 1.0)

    /** Scrim tint: near-black plus 10% of the frame's average color. */
    fun scrimTint(average: Int): Int {
        fun ch(shift: Int) = (6 + ((average shr shift) and 0xFF) * 0.1f).roundToInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** Pixel sample accumulator: average color, mean luminance and encoded-luminance percentiles. */
class LumaStats {
    private val histogram = IntArray(256)
    private var linearSum = 0.0
    private var r = 0L
    private var g = 0L
    private var b = 0L
    var count = 0
        private set

    fun add(argb: Int) {
        val l = ColorMath.luminance(argb)
        histogram[(ColorMath.encode(l) * 255).roundToInt().coerceIn(0, 255)]++
        linearSum += l
        r += (argb shr 16) and 0xFF
        g += (argb shr 8) and 0xFF
        b += argb and 0xFF
        count++
    }

    fun meanLuminance(): Double = if (count == 0) 0.0 else linearSum / count

    /** Encoded luminance below which fraction [q] of the sample lies; 0 for an empty sample. */
    fun percentile(q: Double): Double {
        if (count == 0) return 0.0
        val need = (q.coerceIn(0.0, 1.0) * count).roundToInt().coerceIn(1, count)
        var seen = 0
        for (i in histogram.indices) {
            seen += histogram[i]
            if (seen >= need) return i / 255.0
        }
        return 1.0
    }

    /** Average color, opaque; black for an empty sample. */
    fun average(): Int {
        if (count == 0) return 0xFF000000.toInt()
        return (0xFF shl 24) or ((r / count).toInt() shl 16) or ((g / count).toInt() shl 8) or (b / count).toInt()
    }
}
