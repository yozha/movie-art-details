package io.github.yozha.movieartdetails.render

import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.DisplayInfo
import io.github.yozha.movieartdetails.model.TextPreset
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * Info block layout, free of Android types: sizes, the shrink order and hard limits are decided here
 * and tested on the JVM. InfoBlockRenderer only draws the resulting rectangles.
 * All coordinates are canvas px (1920×1080), with y growing downward.
 */

enum class Weight { REGULAR, MEDIUM, BOLD }

data class TextStyle(val size: Float, val weight: Weight)

/** Text measurement. The Android implementation (PaintTextMeasurer) wraps lines exactly as drawing does. */
interface TextMeasurer {
    fun width(text: String, style: TextStyle): Float
    /** Line count when word-wrapped to [maxWidth], with no line limit. */
    fun lineCount(text: String, style: TextStyle, maxWidth: Float): Int
    /** From the top of the line to the baseline (−FontMetricsInt.ascent). */
    fun ascent(style: TextStyle): Float
    /** From the baseline to the bottom of the lowest glyphs (max(descent, bottom)). */
    fun descent(style: TextStyle): Float
}

/** Starting sizes of a preset at 1080p, in px. */
data class PresetSizes(
    /** Text title size: searched from [titleMax] down to [titleMin]; two lines fit in logoMaxH at ≈56 px. */
    val titleMax: Float,
    val titleMin: Float,
    val meta: Float,
    val genres: Float,
    val overview: Float,
    val overviewPitch: Float,
    val logoMinH: Float,
    val logoMaxH: Float,
    val logoMaxW: Float,
    /** Gaps: after the logo/title, between the meta line and genres, before the overview. */
    val gapHeader: Float,
    val gapMeta: Float,
    val gapOverview: Float,
)

/** Block fields after applying the show* flags and whitespace cleanup. No blank strings here. */
data class BlockContent(
    val title: String,
    /** Width/height of a logo that passed the logo checks; null means a text title. */
    val logoAspect: Float?,
    /** Year, type, runtime: only those present, with no empty separators. */
    val metaParts: List<String>,
    val rating: String?,
    val genres: List<String>,
    /** Line breaks are already collapsed. */
    val overview: String?,
) {
    companion object {
        private val WHITESPACE = Regex("\\s+")
        const val MAX_GENRES = 3

        fun collapse(text: String): String = text.replace(WHITESPACE, " ").trim()

        fun from(info: DisplayInfo, settings: AppearanceSettings, logoAspect: Float?): BlockContent = BlockContent(
            title = collapse(info.title),
            logoAspect = logoAspect?.takeIf { settings.showLogo },
            metaParts = listOfNotNull(
                info.yearText.takeIf { settings.showYear },
                info.typeText.takeIf { settings.showType },
                info.durationText.takeIf { settings.showDuration },
            ).map(::collapse).filter { it.isNotEmpty() },
            rating = info.ratingText?.takeIf { settings.showRating }?.let(::collapse)?.ifEmpty { null },
            genres = if (settings.showGenres) info.genres.map(::collapse).filter { it.isNotEmpty() }.take(MAX_GENRES) else emptyList(),
            overview = info.overview?.takeIf { settings.showOverview }?.let(::collapse)?.ifEmpty { null },
        )
    }
}

/** Block frame after all settings constraints: top-left corner, right edge and bottom limit. */
data class Frame(val canvasW: Int, val canvasH: Int, val left: Float, val top: Float, val right: Float, val limit: Float) {
    val width: Float get() = right - left
}

sealed interface Header {
    val left: Float
    val top: Float
    val width: Float
    val height: Float
    val bottom: Float get() = top + height
}

data class LogoBox(override val left: Float, override val top: Float, override val width: Float, override val height: Float) : Header

/** [width] is the layout width (StaticLayout), [pitch] the line pitch. */
data class TitleBox(
    val text: String,
    val style: TextStyle,
    val lines: Int,
    val pitch: Int,
    override val left: Float,
    override val top: Float,
    override val width: Float,
    override val height: Float,
) : Header

/** A single line: [width] is the measured text width. */
data class TextLine(val text: String, val style: TextStyle, val left: Float, val top: Float, val baseline: Float, val width: Float, val height: Float) {
    val bottom: Float get() = top + height
    val right: Float get() = left + width
}

/** "year · type · runtime" plus the highlighted rating on the same line. */
data class MetaLine(val main: TextLine?, val rating: TextLine?, val top: Float, val height: Float) {
    val bottom: Float get() = top + height
}

data class OverviewBox(val text: String, val style: TextStyle, val lines: Int, val pitch: Int, val left: Float, val top: Float, val width: Float, val height: Float) {
    val bottom: Float get() = top + height
}

data class BlockLayout(
    val frame: Frame,
    val header: Header?,
    val meta: MetaLine?,
    val genres: TextLine?,
    val overview: OverviewBox?,
    /** Bottom of the lowest element (shadow excluded). */
    val bottom: Float,
    /** Shrink steps applied, in order: "overview=4", "header=120", "genres=merged", "scale=0.94", "emergency:…", "trim:…". */
    val steps: List<String>,
) {
    val isEmpty: Boolean get() = header == null && meta == null && genres == null && overview == null
    /** Fits above the limit, shadow included. */
    val fits: Boolean get() = isEmpty || bottom + LayoutEngine.SHADOW_PAD <= frame.limit
}

object LayoutEngine {
    const val CANVAS_W = 1920
    const val CANVAS_H = 1080
    /** Clock area: x ≥ 74% where y ≤ 20%. */
    const val CLOCK_X = 0.74f
    const val CLOCK_Y = 0.20f
    /** Hard bottom limit: no text or shadow pixel below 52%. */
    const val MAX_BOTTOM = 0.52f
    /** Margin for the text shadow: 5 px blur (≈3σ) plus a 2 px offset. */
    const val SHADOW_PAD = 14f
    const val TITLE_PITCH = 1.1f
    const val MAX_TITLE_LINES = 2
    /** Logo checks: aspect ratio and height after fitting into 520×130. */
    const val LOGO_CHECK_W = 520f
    const val LOGO_CHECK_H = 130f
    private const val LOGO_MIN_ASPECT = 0.8f
    private const val LOGO_MAX_ASPECT = 9f
    private const val LOGO_MIN_FIT_H = 60f
    private const val HEADER_STEP = 10f
    /** Gap between the meta line and the rating, as a fraction of the font size. */
    private const val RATING_GAP_EM = 0.9f
    private const val SEPARATOR = " · "
    /** Shrink step 4: font size of the text below the header. */
    private val SCALES = floatArrayOf(0.94f, 0.88f, 0.82f)

    fun sizes(preset: TextPreset): PresetSizes = when (preset) {
        TextPreset.SMALL -> PresetSizes(72f, 40f, 28f, 24f, 24f, 33f, 70f, 110f, 460f, 18f, 8f, 14f)
        TextPreset.MEDIUM -> PresetSizes(85f, 45f, 32f, 28f, 28f, 38f, 80f, 130f, 520f, 22f, 10f, 18f)
        TextPreset.LARGE -> PresetSizes(96f, 50f, 38f, 34f, 34f, 46f, 90f, 140f, 560f, 26f, 12f, 22f)
    }

    /** Aspect ratio 0.8–9 and height ≥ 60 px after fitting into 520×130. Contrast is checked by the renderer. */
    fun logoUsable(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0) return false
        val aspect = width.toFloat() / height
        if (aspect < LOGO_MIN_ASPECT || aspect > LOGO_MAX_ASPECT) return false
        return height * min(LOGO_CHECK_W / width, LOGO_CHECK_H / height) >= LOGO_MIN_FIT_H
    }

    /**
     * Frame with hard limits applied on top of the settings: bottom limit ≤ 52%, right edge left of the
     * clock area (with a shadow margin), so settings cannot move the block there.
     */
    fun frame(settings: AppearanceSettings, w: Int = CANVAS_W, h: Int = CANVAS_H): Frame {
        val left = (settings.leftPct.orZero().coerceIn(0f, 0.3f) * w).coerceAtLeast(SHADOW_PAD)
        val top = (settings.topPct.orZero().coerceIn(0f, 0.3f) * h).coerceAtLeast(SHADOW_PAD)
        val limit = settings.bottomLimitPct.orZero().coerceIn(0f, MAX_BOTTOM) * h
        val right = min(left + settings.blockWidthPct.orZero().coerceIn(0.1f, 1f) * w, CLOCK_X * w - SHADOW_PAD)
        return Frame(w, h, left, top, max(right, left + 1f), limit)
    }

    /**
     * Layout with shrinking: 1) overview lines 5→4→3; 2) logo/title height down to its minimum;
     * 3) genres merged into the meta line (whatever does not fit is dropped); 4) font size. If that is
     * still not enough, the overview goes below 3 lines, then elements are removed whole from the bottom.
     * The result always satisfies [BlockLayout.fits].
     */
    fun layout(content: BlockContent, settings: AppearanceSettings, measurer: TextMeasurer, w: Int = CANVAS_W, h: Int = CANVAS_H): BlockLayout {
        val preset = sizes(settings.textPreset)
        val placer = Placer(content, frame(settings, w, h), preset, measurer)
        var last: BlockLayout? = null
        for (plan in plans(settings.overviewLines.coerceIn(0, 5), preset)) {
            val layout = placer.place(plan)
            if (layout.fits) return layout
            last = layout
        }
        return trim(last!!)
    }

    private data class Plan(val lines: Int, val headerH: Float, val mergeGenres: Boolean, val scale: Float, val steps: List<String>)

    private fun plans(userLines: Int, p: PresetSizes): Sequence<Plan> = sequence {
        var plan = Plan(userLines, p.logoMaxH, mergeGenres = false, scale = 1f, steps = emptyList())
        yield(plan)
        while (plan.lines > min(userLines, 3)) {
            plan = plan.copy(lines = plan.lines - 1, steps = plan.steps + "overview=${plan.lines - 1}")
            yield(plan)
        }
        while (plan.headerH > p.logoMinH) {
            val h = max(plan.headerH - HEADER_STEP, p.logoMinH)
            plan = plan.copy(headerH = h, steps = plan.steps + "header=${h.roundToInt()}")
            yield(plan)
        }
        plan = plan.copy(mergeGenres = true, steps = plan.steps + "genres=merged")
        yield(plan)
        for (s in SCALES) {
            plan = plan.copy(scale = s, steps = plan.steps + "scale=$s")
            yield(plan)
        }
        while (plan.lines > 0) {
            plan = plan.copy(lines = plan.lines - 1, steps = plan.steps + "emergency:overview=${plan.lines - 1}")
            yield(plan)
        }
    }

    /** Last resort: remove elements bottom-up until the block fits above the limit. */
    private fun trim(layout: BlockLayout): BlockLayout {
        var l = layout
        fun drop(step: String, present: Boolean, without: (BlockLayout) -> BlockLayout) {
            if (l.fits || !present) return
            val next = without(l)
            l = next.copy(bottom = lowest(next), steps = next.steps + step)
        }
        drop("trim:overview", l.overview != null) { it.copy(overview = null) }
        drop("trim:genres", l.genres != null) { it.copy(genres = null) }
        drop("trim:meta", l.meta != null) { it.copy(meta = null) }
        drop("trim:header", l.header != null) { it.copy(header = null) }
        return l
    }

    private fun lowest(l: BlockLayout): Float =
        listOfNotNull(l.header?.bottom, l.meta?.bottom, l.genres?.bottom, l.overview?.bottom).maxOrNull() ?: l.frame.top

    private enum class Slot { HEADER, META, GENRES, OVERVIEW }

    private class Placer(val content: BlockContent, val frame: Frame, val p: PresetSizes, val m: TextMeasurer) {
        private val titles = HashMap<Float, TitleBox>()
        private val counts = HashMap<Pair<String, TextStyle>, Int>()

        fun place(plan: Plan): BlockLayout {
            val s = plan.scale
            var bottom = frame.top
            var prev: Slot? = null
            fun topFor(slot: Slot): Float = when (prev) {
                null -> frame.top
                Slot.HEADER -> bottom + p.gapHeader * s
                else -> bottom + (if (slot == Slot.GENRES) p.gapMeta else p.gapOverview) * s
            }

            val head = header(plan.headerH)?.also { bottom = it.bottom; prev = Slot.HEADER }
            val meta = metaLine(topFor(Slot.META), s, if (plan.mergeGenres) content.genres else emptyList())
                ?.also { bottom = it.bottom; prev = Slot.META }
            val genres = if (plan.mergeGenres) null else genresLine(topFor(Slot.GENRES), s)
                ?.also { bottom = it.bottom; prev = Slot.GENRES }
            val overview = overviewBox(topFor(Slot.OVERVIEW), s, plan.lines)
                ?.also { bottom = it.bottom; prev = Slot.OVERVIEW }
            return BlockLayout(frame, head, meta, genres, overview, bottom, plan.steps)
        }

        private fun header(boxH: Float): Header? {
            content.logoAspect?.let { aspect ->
                val h = min(boxH, min(p.logoMaxW, frame.width) / aspect)
                return LogoBox(frame.left, frame.top, h * aspect, h)
            }
            if (content.title.isEmpty()) return null
            return titles.getOrPut(boxH) { fitTitle(boxH) }
        }

        /** Largest font size at which the title takes at most 2 lines, with no broken words, within [boxH]; otherwise the minimum and "…". */
        private fun fitTitle(boxH: Float): TitleBox {
            val words = content.title.split(' ')
            var size = p.titleMax.roundToInt()
            while (size >= p.titleMin.roundToInt()) {
                val style = TextStyle(size.toFloat(), Weight.BOLD)
                if (titleHeight(style, 1) <= boxH && words.all { m.width(it, style) <= frame.width }) {
                    val n = m.lineCount(content.title, style, frame.width)
                    if (n <= MAX_TITLE_LINES && titleHeight(style, n) <= boxH) return titleBox(style, n)
                }
                size--
            }
            val style = TextStyle(p.titleMin, Weight.BOLD)
            return titleBox(style, m.lineCount(content.title, style, frame.width).coerceIn(1, MAX_TITLE_LINES))
        }

        private fun titlePitch(style: TextStyle) = (style.size * TITLE_PITCH).roundToInt()

        private fun titleHeight(style: TextStyle, lines: Int) = (lines - 1) * titlePitch(style) + m.ascent(style) + m.descent(style)

        private fun titleBox(style: TextStyle, lines: Int) =
            TitleBox(content.title, style, lines, titlePitch(style), frame.left, frame.top, frame.width, titleHeight(style, lines))

        /** Dropped right to left: wrapped genres, then runtime and type; year and rating always stay. */
        private fun metaLine(top: Float, s: Float, merged: List<String>): MetaLine? {
            val style = TextStyle(p.meta * s, Weight.MEDIUM)
            val ratingStyle = style.copy(weight = Weight.BOLD)
            val rating = content.rating
            val ratingW = rating?.let { m.width(it, ratingStyle) } ?: 0f
            val avail = frame.width - ratingW - if (rating != null) style.size * RATING_GAP_EM else 0f
            var parts = content.metaParts + merged
            while (parts.size > 1 && m.width(parts.joinToString(SEPARATOR), style) > avail) parts = parts.dropLast(1)
            val mainText = parts.joinToString(SEPARATOR).takeIf { it.isNotEmpty() && m.width(it, style) <= avail }
            if (mainText == null && rating == null) return null

            val ascent = max(m.ascent(style), m.ascent(ratingStyle))
            val height = ascent + max(m.descent(style), m.descent(ratingStyle))
            val main = mainText?.let { TextLine(it, style, frame.left, top, top + ascent, m.width(it, style), height) }
            val ratingLine = rating?.let {
                val left = main?.let { it.right + style.size * RATING_GAP_EM } ?: frame.left
                TextLine(it, ratingStyle, left, top, top + ascent, ratingW, height)
            }
            return MetaLine(main, ratingLine, top, height)
        }

        /** On overflow, genres are dropped right to left. */
        private fun genresLine(top: Float, s: Float): TextLine? {
            val style = TextStyle(p.genres * s, Weight.REGULAR)
            var genres = content.genres
            while (genres.isNotEmpty() && m.width(genres.joinToString(SEPARATOR), style) > frame.width) genres = genres.dropLast(1)
            if (genres.isEmpty()) return null
            val text = genres.joinToString(SEPARATOR)
            val ascent = m.ascent(style)
            return TextLine(text, style, frame.left, top, top + ascent, m.width(text, style), ascent + m.descent(style))
        }

        private fun overviewBox(top: Float, s: Float, maxLines: Int): OverviewBox? {
            val text = content.overview ?: return null
            if (maxLines <= 0) return null
            val style = TextStyle(p.overview * s, Weight.REGULAR)
            val lines = min(counts.getOrPut(text to style) { m.lineCount(text, style, frame.width) }, maxLines)
            if (lines <= 0) return null
            val pitch = (p.overviewPitch * s).roundToInt()
            val height = (lines - 1) * pitch + m.ascent(style) + m.descent(style)
            return OverviewBox(text, style, lines, pitch, frame.left, top, frame.width, height)
        }
    }

    private fun Float.orZero(): Float = if (isNaN()) 0f else this
}
