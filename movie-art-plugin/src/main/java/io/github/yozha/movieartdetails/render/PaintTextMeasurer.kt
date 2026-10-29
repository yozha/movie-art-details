package io.github.yozha.movieartdetails.render

import android.annotation.SuppressLint
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.max

/**
 * [TextMeasurer] backed by TextPaint. Lines are wrapped by the same [layout] the renderer draws with,
 * so the line count in the layout matches what is drawn. Not thread-safe: one per render.
 */
internal class PaintTextMeasurer(private val fonts: (Weight) -> Typeface) : TextMeasurer {
    private val paints = HashMap<TextStyle, TextPaint>()

    /** Cached paint for measuring; drawing uses a copy. */
    fun paint(style: TextStyle): TextPaint = paints.getOrPut(style) {
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = fonts(style.weight)
            textSize = style.size
        }
    }

    override fun width(text: String, style: TextStyle): Float = paint(style).measureText(text)

    override fun lineCount(text: String, style: TextStyle, maxWidth: Float): Int =
        layout(text, paint(style), maxWidth, Int.MAX_VALUE, null).lineCount

    override fun ascent(style: TextStyle): Float = -paint(style).fontMetricsInt.ascent.toFloat()

    override fun descent(style: TextStyle): Float = paint(style).fontMetricsInt.let { max(it.descent, it.bottom) }.toFloat()

    companion object {
        /**
         * Word wrap that never breaks inside a word; with [maxLines], the last line ends with an ellipsis.
         * [pitch] is the line pitch in px: baseline of line i = i·pitch − ascent.
         */
        // Layout.BREAK_STRATEGY_* equal LineBreaker.BREAK_STRATEGY_*; LineBreaker itself needs API 29 (minSdk is 26).
        @SuppressLint("WrongConstant")
        fun layout(text: CharSequence, paint: TextPaint, width: Float, maxLines: Int, pitch: Int?): StaticLayout {
            val builder = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt().coerceAtLeast(1))
                .setIncludePad(false)
                .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
            if (pitch != null) {
                val fm = paint.fontMetricsInt
                builder.setLineSpacing((pitch - (fm.descent - fm.ascent)).toFloat(), 1f)
            }
            if (maxLines != Int.MAX_VALUE) builder.setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END)
            return builder.build()
        }
    }
}
