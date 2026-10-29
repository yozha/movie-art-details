package io.github.yozha.movieartdetails.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import kotlin.math.max
import kotlin.math.min

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

    /** No smaller than needed to fit into [maxW]×[maxH]. */
    fun decodeLogo(file: File, maxW: Int = 520, maxH: Int = 130): Bitmap? =
        decode(file) { w, h -> min(maxW.toFloat() / w, maxH.toFloat() / h) }

    /** Largest power of two s for which the scale [scale] relative to the source does not exceed 1/s. */
    fun sampleSize(scale: Float): Int {
        if (scale.isNaN() || scale <= 0f) return 1
        var s = 1
        while (s < 1 shl 10 && scale * s * 2 <= 1f) s *= 2
        return s
    }

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
