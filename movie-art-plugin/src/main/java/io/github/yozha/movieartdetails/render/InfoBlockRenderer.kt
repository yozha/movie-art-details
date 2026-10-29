// Portions adapted from rfmarcos/projectivy-plugin-sofadrop @ 0f063c19dcc9
// (app/src/main/java/com/rfmarcos/sofadrop/WallpaperRenderer.kt), Apache License 2.0:
// backdrop center-crop (drawCover), gradients drawn only over their non-transparent band
// (drawGradient), smoothstep, the scrim tint from the frame's average color, and trimming of
// transparent logo padding (trimTransparent).
// Modified: the left gradient's shape and strength follow the block frame and are raised as needed
// to meet the overview contrast target; the top scrim is weaker and scaled by a setting; the average
// color comes from a pixel sample instead of a 1x1 downscale; there is no bottom gradient; drawCover
// clamps the source rect to the image; trimTransparent returns null for a fully transparent logo.

package io.github.yozha.movieartdetails.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import io.github.yozha.movieartdetails.R
import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.DisplayInfo
import io.github.yozha.movieartdetails.model.WallpaperRenderer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Final 1920×1080 frame: center-cropped backdrop or a dark gradient, a scrim under the text, the info
 * block laid out by [LayoutEngine], encoded as JPEG q90 into out. Nothing is drawn below the bottom limit
 * or in the clock area: the layout guarantees it, and a clip enforces it too. Input bitmaps are not recycled.
 * Thread-safe: measurement state is created per call.
 */
class InfoBlockRenderer(context: Context) : WallpaperRenderer {

    override val version: Int = 1

    private val app = context.applicationContext

    /** Bundled Roboto (full Cyrillic coverage, including Ukrainian letters); falls back to the system font. */
    private val fonts: Map<Weight, Typeface> by lazy {
        mapOf(
            Weight.REGULAR to font(R.font.roboto_regular, Typeface.DEFAULT),
            Weight.MEDIUM to font(R.font.roboto_medium, Typeface.create("sans-serif-medium", Typeface.NORMAL)),
            Weight.BOLD to font(R.font.roboto_bold, Typeface.DEFAULT_BOLD),
        )
    }

    private class Logo(val bitmap: Bitmap, val plate: Boolean) {
        val aspect: Float get() = bitmap.width.toFloat() / bitmap.height
    }

    override fun render(backdrop: Bitmap?, logo: Bitmap?, info: DisplayInfo, settings: AppearanceSettings, out: File) {
        val w = LayoutEngine.CANVAS_W
        val h = LayoutEngine.CANVAS_H
        val frame = LayoutEngine.frame(settings, w, h)
        val output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(output)
            if (backdrop != null && backdrop.width > 0 && backdrop.height > 0) drawCover(canvas, backdrop, w, h) else drawNeutral(canvas, w, h)
            val tint = drawScrim(canvas, output, frame, settings.scrimIntensity)

            val measurer = PaintTextMeasurer { fonts.getValue(it) }
            val prepared = logo?.takeIf { settings.showLogo }?.let { prepareLogo(it, output, frame) }
            try {
                val layout = LayoutEngine.layout(BlockContent.from(info, settings, prepared?.aspect), settings, measurer, w, h)
                drawBlock(canvas, layout, prepared, measurer, tint)
            } finally {
                prepared?.bitmap?.let { if (it !== logo) it.recycle() }
            }
            FileOutputStream(out).use { stream ->
                if (!output.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)) throw IOException("JPEG encode failed")
            }
        } finally {
            output.recycle()
        }
    }

    // ───────────── Background ─────────────

    private fun drawCover(canvas: Canvas, image: Bitmap, width: Int, height: Int) {
        val ratio = max(width / image.width.toFloat(), height / image.height.toFloat())
        val srcWidth = (width / ratio).roundToInt().coerceIn(1, image.width)
        val srcHeight = (height / ratio).roundToInt().coerceIn(1, image.height)
        val left = (image.width - srcWidth) / 2
        val top = (image.height - srcHeight) / 2
        canvas.drawBitmap(
            image,
            Rect(left, top, left + srcWidth, top + srcHeight),
            Rect(0, 0, width, height),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
    }

    /** No backdrop: a neutral dark diagonal gradient. */
    private fun drawNeutral(canvas: Canvas, width: Int, height: Int) {
        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), NEUTRAL_TOP, NEUTRAL_BOTTOM, Shader.TileMode.CLAMP)
            isDither = true
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    /**
     * A full-height horizontal gradient on the left and a light scrim at the top, tinted from the frame's
     * average color. The left gradient peaks at the configured intensity, but never below what it takes
     * for the 90th percentile of the background under the block to give the overview 4.5:1 contrast, even at the block's right edge.
     * Returns the tint (also the logo plate color).
     */
    private fun drawScrim(canvas: Canvas, output: Bitmap, frame: Frame, intensity: Float): Int {
        val w = output.width
        val h = output.height
        val i = if (intensity.isNaN()) 0f else intensity.coerceIn(0f, 1f)
        val tint = ColorMath.scrimTint(sample(output, 0, 0, w, h, step = 16).average())
        val under = sample(output, frame.left.toInt(), frame.top.toInt(), frame.right.toInt(), frame.limit.toInt(), step = 6)

        val r = frame.right / w
        val shape = { t: Float -> 1f - smoothstep((t - 0.75f * r) / (0.25f * r + 0.30f)) }
        val target = ColorMath.encode(ColorMath.maxBackground(ColorMath.luminance(OVERVIEW_COLOR), OVERVIEW_CONTRAST))
        val need = ColorMath.requiredAlpha(under.percentile(0.9), ColorMath.encode(ColorMath.luminance(tint)), target) / shape(r)
        val peak = max(i, need.toFloat()).coerceAtMost(MAX_SCRIM)

        drawGradient(canvas, w, h, tint, horizontal = true) { t -> peak * shape(t) }
        drawGradient(canvas, w, h, tint, horizontal = false) { t -> TOP_SCRIM * i * (1 - smoothstep(t / 0.3f)) }
        return tint
    }

    private fun drawGradient(canvas: Canvas, width: Int, height: Int, tint: Int, horizontal: Boolean, alphaAt: (Float) -> Float) {
        val stops = 33
        val positions = FloatArray(stops) { it / (stops - 1f) }
        val colors = IntArray(stops) {
            Color.argb((alphaAt(positions[it]).coerceIn(0f, 1f) * 255).roundToInt(), Color.red(tint), Color.green(tint), Color.blue(tint))
        }
        val shader = if (horizontal) {
            LinearGradient(0f, 0f, width.toFloat(), 0f, colors, positions, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, 0f, height.toFloat(), colors, positions, Shader.TileMode.CLAMP)
        }
        // Only the band with non-zero alpha is drawn: this is the slowest step on TV hardware.
        val first = colors.indexOfFirst { Color.alpha(it) > 0 }.takeIf { it >= 0 } ?: return
        val last = colors.indexOfLast { Color.alpha(it) > 0 }
        val length = if (horizontal) width else height
        val start = floor(positions[max(first - 1, 0)] * length)
        val end = ceil(positions[min(last + 1, stops - 1)] * length)
        val paint = Paint().apply {
            this.shader = shader
            isDither = true
        }
        if (horizontal) canvas.drawRect(start, 0f, end, height.toFloat(), paint)
        else canvas.drawRect(0f, start, width.toFloat(), end, paint)
    }

    // ───────────── Logo ─────────────

    /**
     * Logo checks: size first ([LayoutEngine.logoUsable]), then the contrast between the mean luminance of
     * its opaque pixels and the already darkened background behind it. A light logo with too little contrast
     * gets a plate; a dark logo on a dark background is replaced with the text title (null).
     */
    private fun prepareLogo(source: Bitmap, output: Bitmap, frame: Frame): Logo? {
        if (source.width <= 0 || source.height <= 0) return null
        val trimmed = trimTransparent(source) ?: return null
        fun reject(): Logo? {
            if (trimmed !== source) trimmed.recycle()
            return null
        }
        if (!LayoutEngine.logoUsable(trimmed.width, trimmed.height)) return reject()

        val own = sample(trimmed, 0, 0, trimmed.width, trimmed.height, step = 2, minAlpha = 128)
        if (own.count == 0) return reject()
        val k = min(LayoutEngine.LOGO_CHECK_W / trimmed.width, LayoutEngine.LOGO_CHECK_H / trimmed.height)
        val left = frame.left.toInt()
        val top = frame.top.toInt()
        val behind = sample(output, left, top, left + (trimmed.width * k).roundToInt(), top + (trimmed.height * k).roundToInt(), step = 4)
        val logoL = own.meanLuminance()
        val backL = behind.meanLuminance()
        return when {
            ColorMath.contrast(logoL, backL) >= LOGO_CONTRAST -> Logo(trimmed, plate = false)
            logoL > backL -> Logo(trimmed, plate = true)
            else -> reject()
        }
    }

    /** Trims transparent padding; returns the same object if there is nothing to trim, null if the logo is empty. */
    private fun trimTransparent(bitmap: Bitmap): Bitmap? {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) for (x in 0 until w) {
            if (Color.alpha(pixels[y * w + x]) > 10) {
                if (x < left) left = x
                if (x > right) right = x
                if (y < top) top = y
                if (y > bottom) bottom = y
            }
        }
        if (right < left || bottom < top) return null
        if (left == 0 && top == 0 && right == w - 1 && bottom == h - 1) return bitmap
        return Bitmap.createBitmap(bitmap, left, top, right - left + 1, bottom - top + 1)
    }

    // ───────────── Block ─────────────

    private fun drawBlock(canvas: Canvas, layout: BlockLayout, logo: Logo?, measurer: PaintTextMeasurer, tint: Int) {
        val f = layout.frame
        canvas.save()
        // Safety net on top of the layout: no pixel (shadows included) below the limit or in the clock area.
        canvas.clipRect(0f, 0f, f.canvasW.toFloat(), f.limit)
        canvas.clipOutRect(LayoutEngine.CLOCK_X * f.canvasW, 0f, f.canvasW.toFloat(), LayoutEngine.CLOCK_Y * f.canvasH)

        when (val header = layout.header) {
            is LogoBox -> if (logo != null) {
                val box = RectF(header.left, header.top, header.left + header.width, header.top + header.height)
                if (logo.plate) {
                    val plate = RectF(box.left - PLATE_PAD, box.top - PLATE_PAD, box.right + PLATE_PAD, box.bottom + PLATE_PAD)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (tint and 0x00FFFFFF) or (PLATE_ALPHA shl 24) }
                    canvas.drawRoundRect(plate, PLATE_RADIUS, PLATE_RADIUS, paint)
                }
                canvas.drawBitmap(logo.bitmap, null, box, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            }
            is TitleBox -> drawLayout(canvas, header.text, textPaint(measurer, header.style, TITLE_COLOR), header.left, header.top, header.width, header.lines, header.pitch)
            null -> Unit
        }
        layout.meta?.let { meta ->
            meta.main?.let { drawLine(canvas, it, textPaint(measurer, it.style, META_COLOR)) }
            meta.rating?.let { drawLine(canvas, it, textPaint(measurer, it.style, RATING_COLOR)) }
        }
        layout.genres?.let { drawLine(canvas, it, textPaint(measurer, it.style, GENRES_COLOR)) }
        layout.overview?.let {
            drawLayout(canvas, it.text, textPaint(measurer, it.style, OVERVIEW_COLOR), it.left, it.top, it.width, it.lines, it.pitch)
        }
        canvas.restore()
    }

    private fun drawLine(canvas: Canvas, line: TextLine, paint: TextPaint) = canvas.drawText(line.text, line.left, line.baseline, paint)

    /** The top of the first StaticLayout line (includePad = false) matches the top of the layout element. */
    private fun drawLayout(canvas: Canvas, text: String, paint: TextPaint, left: Float, top: Float, width: Float, lines: Int, pitch: Int) {
        val layout = PaintTextMeasurer.layout(text, paint, width, lines, pitch)
        canvas.save()
        canvas.translate(left, top)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun textPaint(measurer: PaintTextMeasurer, style: TextStyle, color: Int) = TextPaint(measurer.paint(style)).apply {
        this.color = color
        setShadowLayer(SHADOW_RADIUS, 0f, SHADOW_DY, SHADOW_COLOR)
    }

    // ───────────── Pixel sampling ─────────────

    /** Every [step]-th pixel of every [step]-th row of the rectangle (clipped to the bitmap); pixels with alpha below [minAlpha] are skipped. */
    private fun sample(bitmap: Bitmap, x0: Int, y0: Int, x1: Int, y1: Int, step: Int, minAlpha: Int = 0): LumaStats {
        val stats = LumaStats()
        val left = x0.coerceIn(0, bitmap.width)
        val right = x1.coerceIn(left, bitmap.width)
        val top = y0.coerceIn(0, bitmap.height)
        val bottom = y1.coerceIn(top, bitmap.height)
        val width = right - left
        if (width == 0 || bottom == top) return stats
        val row = IntArray(width)
        var y = top
        while (y < bottom) {
            bitmap.getPixels(row, 0, width, left, y, width, 1)
            var x = 0
            while (x < width) {
                val pixel = row[x]
                if (minAlpha == 0 || (pixel ushr 24) >= minAlpha) stats.add(pixel)
                x += step
            }
            y += step
        }
        return stats
    }

    private fun font(id: Int, fallback: Typeface): Typeface =
        runCatching { ResourcesCompat.getFont(app, id) }.getOrNull() ?: fallback

    private companion object {
        const val JPEG_QUALITY = 90
        const val TITLE_COLOR = 0xFFFFFFFF.toInt()
        const val META_COLOR = 0xFFE8E8E8.toInt()
        const val GENRES_COLOR = 0xFFD2D2D2.toInt()
        const val OVERVIEW_COLOR = 0xFFEDEDED.toInt()
        /** The rating stands out by color and weight; light green, contrast ≥ 3:1 against the background under the overview. */
        const val RATING_COLOR = 0xFF9EE6B8.toInt()
        const val NEUTRAL_TOP = 0xFF1E2128.toInt()
        const val NEUTRAL_BOTTOM = 0xFF0B0C0F.toInt()
        const val SHADOW_COLOR = 0xAA000000.toInt()
        const val SHADOW_RADIUS = 5f
        const val SHADOW_DY = 2f
        const val OVERVIEW_CONTRAST = 4.5
        const val LOGO_CONTRAST = 3.0
        const val MAX_SCRIM = 0.96f
        const val TOP_SCRIM = 0.45f
        const val PLATE_PAD = 12f
        const val PLATE_RADIUS = 14f
        const val PLATE_ALPHA = 0x99

        fun smoothstep(value: Float): Float {
            val t = value.coerceIn(0f, 1f)
            return t * t * (3 - 2 * t)
        }
    }
}
