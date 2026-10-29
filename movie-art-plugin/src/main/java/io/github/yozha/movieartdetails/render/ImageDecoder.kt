package io.github.yozha.movieartdetails.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.LogoRules
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes sources from the level 1 cache, downsampling on read: bounds first, then inSampleSize as the
 * largest power of two that keeps the image no smaller than needed.
 * Always ARGB_8888: the logo needs alpha, and RGB_565 causes banding in backdrop gradients.
 * Errors, corrupt files (SVG included) and out-of-memory → null.
 */
object ImageDecoder {

    /** No smaller than needed to center-crop into [targetW]×[targetH]. */
    fun decodeBackdrop(file: File, targetW: Int = 1920, targetH: Int = 1080): Bitmap? =
        decode(file) { w, h -> max(targetW.toFloat() / w, targetH.toFloat() / h) }

    /**
     * No smaller than needed to fit into [maxW]×[maxH], or into [wideMaxW]×[maxH] for a wide wordmark
     * (aspect ratio above [LogoRules.WIDE_ASPECT]), which may span the whole block. The defaults match the
     * MEDIUM preset in the default block.
     */
    fun decodeLogo(
        file: File,
        maxW: Int = DEFAULT_LOGO_W,
        maxH: Int = DEFAULT_LOGO_H,
        wideMaxW: Int = LogoRules.DEFAULT_BLOCK_WIDTH.roundToInt(),
    ): Bitmap? = decode(file) { w, h -> logoScale(w, h, maxW, maxH, wideMaxW) }

    /** [decodeLogo] into the logo box of the block that [settings] lay out (its frame width and text preset). */
    fun decodeLogo(file: File, settings: AppearanceSettings): Bitmap? {
        val b = logoBounds(settings)
        return decodeLogo(file, b.maxW, b.maxH, b.wideMaxW)
    }

    /** Logo decode bounds, in px. */
    data class LogoBounds(val maxW: Int, val maxH: Int, val wideMaxW: Int)

    /** The largest logo box that [LayoutEngine] can give a logo in the block of [settings]. */
    fun logoBounds(settings: AppearanceSettings): LogoBounds {
        val frame = LayoutEngine.frame(settings)
        val preset = LayoutEngine.sizes(settings.textPreset)
        return LogoBounds(
            maxW = min(preset.logoMaxW, frame.width).roundToInt(),
            maxH = preset.logoMaxH.roundToInt(),
            wideMaxW = frame.width.roundToInt(),
        )
    }

    /** Scale that fits a [w]×[h] logo into its decode box (see [decodeLogo]). */
    fun logoScale(w: Int, h: Int, maxW: Int, maxH: Int, wideMaxW: Int): Float {
        val boxW = if (w.toFloat() / h > LogoRules.WIDE_ASPECT) max(maxW, wideMaxW) else maxW
        return min(boxW.toFloat() / w, maxH.toFloat() / h)
    }

    /** Largest power of two s for which the scale [scale] relative to the source does not exceed 1/s. */
    fun sampleSize(scale: Float): Int {
        if (scale.isNaN() || scale <= 0f) return 1
        var s = 1
        while (s < 1 shl 10 && scale * s * 2 <= 1f) s *= 2
        return s
    }

    private const val DEFAULT_LOGO_W = 520
    private const val DEFAULT_LOGO_H = 130

    private fun decode(file: File, scale: (Int, Int) -> Float): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) {
            null
        } else {
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(scale(w, h))
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(file.path, options)
        }
    } catch (e: OutOfMemoryError) {
        null
    } catch (e: Exception) {
        null
    }
}
