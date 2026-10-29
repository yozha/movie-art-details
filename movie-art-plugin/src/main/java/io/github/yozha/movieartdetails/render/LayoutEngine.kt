package io.github.yozha.movieartdetails.render

import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.BlockAnchor
import io.github.yozha.movieartdetails.model.DisplayInfo
import io.github.yozha.movieartdetails.model.LogoRules
import io.github.yozha.movieartdetails.model.TextPreset
import kotlin.math.floor
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
    /** Height of the source-app plate at the start of the meta line. */
    val badgeH: Float,
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
    /** Height of the logo bitmap in px: the logo is never drawn taller, so it is never upscaled. null means no cap. */
    val logoPixelHeight: Float? = null,
    /** Width/height of the source-app plate ([LayoutEngine.badgeAspect]); null means no plate. */
    val badgeAspect: Float? = null,
) {
    companion object {
        private val WHITESPACE = Regex("\\s+")
        const val MAX_GENRES = 3

        fun collapse(text: String): String = text.replace(WHITESPACE, " ").trim()

        fun from(
            info: DisplayInfo,
            settings: AppearanceSettings,
            logoAspect: Float?,
            logoPixelHeight: Float? = null,
            badgeAspect: Float? = null,
        ): BlockContent = BlockContent(
            title = collapse(info.title),
            logoAspect = logoAspect?.takeIf { settings.showLogo },
            logoPixelHeight = logoPixelHeight?.takeIf { logoAspect != null && settings.showLogo },
            metaParts = listOfNotNull(
                info.yearText.takeIf { settings.showYear },
                info.typeText.takeIf { settings.showType },
                info.durationText.takeIf { settings.showDuration },
            ).map(::collapse).filter { it.isNotEmpty() },
            rating = info.ratingText?.takeIf { settings.showRating }?.let(::collapse)?.ifEmpty { null },
            genres = if (settings.showGenres) info.genres.map(::collapse).filter { it.isNotEmpty() }.take(MAX_GENRES) else emptyList(),
            overview = info.overview?.takeIf { settings.showOverview }?.let(::collapse)?.ifEmpty { null },
            badgeAspect = badgeAspect?.takeIf { settings.showAppBadge },
        )

        /**
         * A card shown without TMDB details: its own title as the header and the source-app plate on the meta line;
         * the title is cut to [MAX_TITLED_CHARS] (the layout ellipsizes it within two lines anyway).
         */
        fun titled(title: String, settings: AppearanceSettings, badgeAspect: Float?): BlockContent = BlockContent(
            title = collapse(title).take(MAX_TITLED_CHARS),
            logoAspect = null,
            metaParts = emptyList(),
            rating = null,
            genres = emptyList(),
            overview = null,
            badgeAspect = badgeAspect?.takeIf { settings.showAppBadge },
        )

        const val MAX_TITLED_CHARS = 200
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

/** Drawn size of a logo, in px. */
data class LogoSize(val width: Float, val height: Float)

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

/** Source-app plate: [width] follows the badge's aspect ratio. */
data class BadgeBox(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * "year · type · runtime" plus the highlighted rating on the same line, after the source-app [badge] plate if there
 * is one. With a plate the line is as tall as the plate, and the text is centered on it.
 */
data class MetaLine(val main: TextLine?, val rating: TextLine?, val top: Float, val height: Float, val badge: BadgeBox? = null) {
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
    /** Lowest the launcher's card row can start; settings cannot move it further down. */
    const val MAX_ROW = 0.85f
    /** Hard gap above the card row: no text or shadow pixel closer to it than 3% (below 52% for the default 55% row). */
    const val MIN_ROW_GAP = 0.03f
    /** Upper bound of the overview line setting; the shrink steps still apply. */
    const val MAX_OVERVIEW_LINES = 8
    /** Margin for the text shadow: 5 px blur (≈3σ) plus a 2 px offset. */
    const val SHADOW_PAD = 14f
    const val TITLE_PITCH = 1.1f
    const val MAX_TITLE_LINES = 2
    private const val HEADER_STEP = 10f
    /** A trimmed overview keeps at least this many lines, otherwise it is cut with an ellipsis instead. */
    private const val MIN_SENTENCE_LINES = 2
    /** Gap between the meta line and the rating, as a fraction of the font size. */
    private const val RATING_GAP_EM = 0.9f
    /** Gap between the source-app plate and the meta text, as a fraction of the font size. */
    const val BADGE_GAP_EM = 0.6f
    /** Widest source-app plate relative to its height: wider banners are cropped to it. */
    const val MAX_BADGE_ASPECT = 2.5f
    private const val SEPARATOR = " · "
    /** Shrink step 4: font size of the text below the header. */
    private val SCALES = floatArrayOf(0.94f, 0.88f, 0.82f)

    fun sizes(preset: TextPreset): PresetSizes = when (preset) {
        TextPreset.SMALL -> PresetSizes(72f, 40f, 28f, 24f, 24f, 33f, 70f, 110f, 460f, 18f, 8f, 14f, 46f)
        TextPreset.MEDIUM -> PresetSizes(85f, 45f, 32f, 28f, 28f, 38f, 80f, 130f, 520f, 22f, 10f, 18f, 52f)
        TextPreset.LARGE -> PresetSizes(96f, 50f, 38f, 34f, 34f, 46f, 90f, 140f, 560f, 26f, 12f, 22f, 60f)
    }

    /**
     * Shape of the source-app plate for a [width]×[height] badge: a banner keeps its own aspect ratio within
     * 1..[MAX_BADGE_ASPECT] (and is cropped to it), an icon sits on a square plate.
     */
    fun badgeAspect(width: Int, height: Int, banner: Boolean): Float =
        if (!banner || width <= 0 || height <= 0) 1f else (width.toFloat() / height).coerceIn(1f, MAX_BADGE_ASPECT)

    /**
     * Aspect ratio within [LogoRules] and height ≥ [LogoRules.MIN_HEIGHT] after fitting into the logo box of [preset]
     * in [frame] without upscaling (see [logoFit]). Contrast is checked by the renderer.
     */
    fun logoUsable(width: Int, height: Int, frame: Frame = frame(AppearanceSettings()), preset: TextPreset = TextPreset.MEDIUM): Boolean {
        if (width <= 0 || height <= 0) return false
        val aspect = width.toFloat() / height
        if (aspect < LogoRules.MIN_ASPECT || aspect > LogoRules.MAX_ASPECT) return false
        return logoFit(aspect, height.toFloat(), frame, sizes(preset)).height >= LogoRules.MIN_HEIGHT
    }

    /**
     * Size of a logo with [aspect] in the header at most [boxH] tall: no wider than [logoMaxWidth] and no taller
     * than the bitmap's own [pixelHeight], so it is never upscaled. The same fit is used for the layout, the size
     * check and the renderer's contrast sample.
     */
    fun logoFit(aspect: Float, pixelHeight: Float?, frame: Frame, preset: PresetSizes, boxH: Float = preset.logoMaxH): LogoSize {
        val h = minOf(boxH, logoMaxWidth(aspect, frame, preset) / aspect, pixelHeight ?: Float.MAX_VALUE)
        return LogoSize(h * aspect, h)
    }

    /** Widest a logo may be: the preset's logo width, or the whole block width for a wide wordmark. */
    fun logoMaxWidth(aspect: Float, frame: Frame, preset: PresetSizes): Float =
        if (aspect > LogoRules.WIDE_ASPECT) frame.width else min(preset.logoMaxW, frame.width)

    /**
     * Frame with hard limits applied on top of the settings: the bottom limit stays at least [MIN_ROW_GAP] above
     * the card row (which starts no lower than [MAX_ROW]), and the right edge stays left of the clock area (with a
     * shadow margin), so settings cannot move the block into either.
     */
    fun frame(settings: AppearanceSettings, w: Int = CANVAS_W, h: Int = CANVAS_H): Frame {
        val left = (settings.leftPct.orZero().coerceIn(0f, 0.3f) * w).coerceAtLeast(SHADOW_PAD)
        val top = (settings.topPct.orZero().coerceIn(0f, 0.3f) * h).coerceAtLeast(SHADOW_PAD)
        val row = settings.rowPositionPct.orZero().coerceIn(0f, MAX_ROW)
        val limit = (row - settings.rowGapPct.orZero().coerceAtLeast(MIN_ROW_GAP)).coerceAtLeast(0f) * h
        val right = min(left + settings.blockWidthPct.orZero().coerceIn(0.1f, 1f) * w, CLOCK_X * w - SHADOW_PAD)
        return Frame(w, h, left, top, max(right, left + 1f), limit)
    }

    /**
     * Layout with shrinking: 1) overview lines from the setting (up to [MAX_OVERVIEW_LINES]) down to 3; 2) logo/title height down to its minimum;
     * 3) genres merged into the meta line (whatever does not fit is dropped); 4) font size. If that is
     * still not enough, the overview goes below 3 lines, then elements are removed whole from the bottom.
     * An overview longer than its lines ends after the last whole sentence that fits, or else with an ellipsis.
     * The block is laid out from the frame top; with [BlockAnchor.ABOVE_ROW] it is then moved down to end at the
     * bottom limit, so it never starts above the frame top. The result always satisfies [BlockLayout.fits].
     */
    fun layout(content: BlockContent, settings: AppearanceSettings, measurer: TextMeasurer, w: Int = CANVAS_W, h: Int = CANVAS_H): BlockLayout {
        val fitted = fit(content, settings, measurer, w, h)
        return when (settings.blockAnchor) {
            BlockAnchor.TOP -> fitted
            BlockAnchor.ABOVE_ROW -> aboveRow(fitted)
        }
    }

    private fun fit(content: BlockContent, settings: AppearanceSettings, measurer: TextMeasurer, w: Int, h: Int): BlockLayout {
        val preset = sizes(settings.textPreset)
        val placer = Placer(content, frame(settings, w, h), preset, measurer)
        var last: BlockLayout? = null
        for (plan in plans(settings.overviewLines.coerceIn(0, MAX_OVERVIEW_LINES), preset)) {
            val layout = placer.place(plan)
            if (layout.fits) return layout
            last = layout
        }
        return trim(last!!)
    }

    /**
     * Moves a fitted block down so that its bottom (shadow included) lands on the frame limit. The shift is whole
     * pixels, rounded down, so float error can never push an element past the limit.
     */
    private fun aboveRow(l: BlockLayout): BlockLayout {
        val dy = floor(l.frame.limit - SHADOW_PAD - l.bottom)
        if (l.isEmpty || dy <= 0f) return l
        fun TextLine.moved() = copy(top = top + dy, baseline = baseline + dy)
        return l.copy(
            header = when (val h = l.header) {
                is LogoBox -> h.copy(top = h.top + dy)
                is TitleBox -> h.copy(top = h.top + dy)
                null -> null
            },
            meta = l.meta?.let { m ->
                m.copy(main = m.main?.moved(), rating = m.rating?.moved(), top = m.top + dy, badge = m.badge?.let { it.copy(top = it.top + dy) })
            },
            genres = l.genres?.moved(),
            overview = l.overview?.let { it.copy(top = it.top + dy) },
            bottom = l.bottom + dy,
        )
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
        private val fitted = HashMap<Triple<String, TextStyle, Int>, String>()

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
                val size = logoFit(aspect, content.logoPixelHeight, frame, p, boxH)
                return LogoBox(frame.left, frame.top, size.width, size.height)
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

        /**
         * Dropped right to left: wrapped genres, then runtime and type; year, rating and the source-app plate always
         * stay. The plate's width (and a gap after it) is reserved at the start of the line.
         */
        private fun metaLine(top: Float, s: Float, merged: List<String>): MetaLine? {
            val style = TextStyle(p.meta * s, Weight.MEDIUM)
            val ratingStyle = style.copy(weight = Weight.BOLD)
            val badge = content.badgeAspect?.let { aspect ->
                val h = p.badgeH * s
                BadgeBox(frame.left, top, min(h * aspect, frame.width), h)
            }
            val textLeft = badge?.let { it.right + style.size * BADGE_GAP_EM } ?: frame.left
            val rating = content.rating
            val ratingW = rating?.let { m.width(it, ratingStyle) } ?: 0f
            val avail = frame.right - textLeft - ratingW - if (rating != null) style.size * RATING_GAP_EM else 0f
            var parts = content.metaParts + merged
            while (parts.size > 1 && m.width(parts.joinToString(SEPARATOR), style) > avail) parts = parts.dropLast(1)
            val mainText = parts.joinToString(SEPARATOR).takeIf { it.isNotEmpty() && m.width(it, style) <= avail }
            if (mainText == null && rating == null && badge == null) return null

            val ascent = max(m.ascent(style), m.ascent(ratingStyle))
            val textH = ascent + max(m.descent(style), m.descent(ratingStyle))
            val height = max(textH, badge?.height ?: 0f)
            // Centered on the plate; whole pixels keep the baseline crisp.
            val textTop = if (badge == null) top else top + floor((height - textH) / 2)
            val main = mainText?.let { TextLine(it, style, textLeft, textTop, textTop + ascent, m.width(it, style), textH) }
            val ratingLine = rating?.let {
                val left = main?.let { it.right + style.size * RATING_GAP_EM } ?: textLeft
                TextLine(it, ratingStyle, left, textTop, textTop + ascent, ratingW, textH)
            }
            return MetaLine(main, ratingLine, top, height, badge)
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
            val full = content.overview ?: return null
            if (maxLines <= 0) return null
            val style = TextStyle(p.overview * s, Weight.REGULAR)
            val text = fitted.getOrPut(Triple(full, style, maxLines)) { fitOverview(full, style, maxLines) }
            val lines = min(lineCount(text, style), maxLines)
            if (lines <= 0) return null
            val pitch = (p.overviewPitch * s).roundToInt()
            val height = (lines - 1) * pitch + m.ascent(style) + m.descent(style)
            return OverviewBox(text, style, lines, pitch, frame.left, top, frame.width, height)
        }

        /**
         * An overview longer than [maxLines] ends at the last whole sentence that fits, if that keeps at least
         * [MIN_SENTENCE_LINES] lines; otherwise it stays whole and the renderer cuts it with an ellipsis.
         */
        private fun fitOverview(text: String, style: TextStyle, maxLines: Int): String {
            if (lineCount(text, style) <= maxLines) return text
            for (end in Sentences.ends(text).asReversed()) {
                val prefix = text.substring(0, end)
                val lines = lineCount(prefix, style)
                if (lines <= maxLines) return if (lines >= min(MIN_SENTENCE_LINES, maxLines)) prefix else text
            }
            return text
        }

        private fun lineCount(text: String, style: TextStyle): Int =
            counts.getOrPut(text to style) { m.lineCount(text, style, frame.width) }
    }

    private fun Float.orZero(): Float = if (isNaN()) 0f else this
}

/**
 * Sentence boundaries in an overview, for trimming it after a whole sentence. A boundary is [.!?…] (possibly
 * repeated, then closing quotes or brackets), a space, and an uppercase letter or a digit, possibly after an opening
 * quote or a dialogue dash. A single period after an initial ("J. R. R.") or a common abbreviation
 * ([ABBREVIATIONS], e.g. "Mr.") is not a boundary.
 */
internal object Sentences {
    private const val TERMINATORS = ".!?…"
    private const val CLOSERS = "\"'»”’)]"
    private const val OPENERS = "\"'«“„‘([—–"

    /** Lowercased words of two or more letters that are written with a period without ending a sentence. */
    private val ABBREVIATIONS = setOf(
        // Russian and Ukrainian
        "гг", "вв", "им", "ул", "пл", "др", "пр", "см", "св", "ст", "тыс", "тис", "млн", "млрд", "руб", "грн", "дж",
        "проф", "акад", "ген", "полк", "кап", "лейт", "рис", "ін", "див", "напр", "англ", "франц", "нем",
        // English
        "mr", "mrs", "ms", "dr", "st", "jr", "sr", "vs", "mt", "lt", "col", "gen", "sgt", "capt", "prof", "rev", "inc", "co", "ltd", "etc",
    )

    /** Single-letter words that can end a sentence; any other single letter before a period is an initial or an abbreviation. */
    private val ONE_LETTER_WORDS = setOf("я")

    /** End indices (exclusive) of the sentences in [text], in ascending order; the end of the text is not included. */
    fun ends(text: String): List<Int> {
        val ends = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            if (text[i] !in TERMINATORS) {
                i++
                continue
            }
            val start = i
            while (i < text.length && text[i] in TERMINATORS) i++
            val singlePeriod = i - start == 1 && text[start] == '.'
            while (i < text.length && text[i] in CLOSERS) i++
            if (singlePeriod && isAbbreviation(text, start)) continue
            if (startsSentence(text, i)) ends += i
        }
        return ends
    }

    private fun isAbbreviation(text: String, period: Int): Boolean {
        var from = period
        while (from > 0 && text[from - 1].isLetter()) from--
        val word = text.substring(from, period).lowercase()
        return when (word.length) {
            0 -> false
            1 -> word !in ONE_LETTER_WORDS
            else -> word in ABBREVIATIONS
        }
    }

    /** A space at [index], then an uppercase letter or a digit after any opening quotes and dashes. */
    private fun startsSentence(text: String, index: Int): Boolean {
        if (index >= text.length || !text[index].isWhitespace()) return false
        var j = index
        while (j < text.length && (text[j] in OPENERS || text[j].isWhitespace())) j++
        return j < text.length && (text[j].isUpperCase() || text[j].isDigit())
    }
}
