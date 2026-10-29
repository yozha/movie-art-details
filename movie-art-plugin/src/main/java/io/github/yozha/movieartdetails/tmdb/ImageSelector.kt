package io.github.yozha.movieartdetails.tmdb

import io.github.yozha.movieartdetails.model.DisplayLanguage
import io.github.yozha.movieartdetails.model.ImageInfo
import io.github.yozha.movieartdetails.model.LogoRules
import io.github.yozha.movieartdetails.model.TitleDetails
import java.text.Normalizer
import kotlin.math.min

/** A selected logo: the PNG [path] to download and the TMDB image [size] to download it at. */
data class LogoPick(val path: String, val size: String)

/**
 * Picks the backdrop and the logo from the details' images.
 * The backdrop comes back as file_path (the caller downloads it at original); the logo as a [LogoPick].
 */
object ImageSelector {

    /** Default logo download size; a wide logo is downloaded at [LOGO_SIZE_WIDE]. */
    const val LOGO_SIZE = "w500"
    const val LOGO_SIZE_WIDE = "original"

    private const val MIN_BACKDROP_WIDTH = 1920
    /** A w500 logo is at most this wide. */
    private const val LOGO_W500_WIDTH = 500.0
    /** Logo box at the default text preset; a wide logo may span the default block width instead. */
    private const val LOGO_BOX_W = 520.0
    private const val LOGO_BOX_H = 130.0

    private val BY_VOTE_THEN_WIDTH = compareByDescending<ImageInfo> { it.voteAverage }.thenByDescending { it.width }

    /** A logo that stays usable at the w500 fallback comes before a better-rated one that needs the original. */
    private val LOGO_ORDER = compareByDescending<ImageInfo> { fitsAt(it, LOGO_W500_WIDTH) }.then(BY_VOTE_THEN_WIDTH)

    /**
     * backdrop_path if it is textless and at least 1920 wide; otherwise the textless ≥ 1920 one with the highest
     * vote_average; otherwise the largest textless one; otherwise backdrop_path as is.
     */
    fun backdrop(details: TitleDetails): String? {
        val textless = details.backdrops.filter { it.iso639 == null && isRaster(it.filePath) }
        details.backdropPath?.let { main ->
            if (textless.any { it.filePath == main && it.width >= MIN_BACKDROP_WIDTH }) return main
        }
        textless.filter { it.width >= MIN_BACKDROP_WIDTH }.minWithOrNull(BY_VOTE_THEN_WIDTH)?.let { return it.filePath }
        textless.maxWithOrNull(compareBy<ImageInfo> { it.width }.thenBy { it.voteAverage })?.let { return it.filePath }
        return details.backdropPath
    }

    /**
     * A raster logo with alpha: a .png path (the CDN may serve it as WebP, which decodes the same way), or an SVG
     * logo, which TMDB also serves as a PNG at the same path with a .png extension. It needs an aspect ratio of 0.8–14
     * and enough height after fitting without upscaling the downloaded image.
     * Order: a logo in the [display] language; then one in the language of the shown [title] when that differs
     * (the original language when the original title is shown, or English when the English title is); then a logo
     * in any other language whose title in that language matches the shown title. A display-language logo comes
     * first even when the shown text title is in another language (TMDB leaves a translation title empty when it
     * equals the original, as with "1917" or "Shōgun"): it shows the localized title, in the language of the rest
     * of the block.
     * Within each step, a wide logo that is only usable at original goes after the ones that are also usable at w500:
     * an original logo over the download size cap falls back to w500, where it would be too small and turn into a
     * text title. It is still picked when nothing else in that step is usable.
     * Logos without a language are skipped: there is no telling which title they show. null → text title.
     * The renderer makes the final fit decision with the real frame.
     */
    fun logo(details: TitleDetails, title: ShownTitle, display: DisplayLanguage): LogoPick? {
        val usable = details.logos.filter { isUsableLogo(it) }
        fun bestIn(lang: String): ImageInfo? =
            usable.filter { it.iso639.equals(lang, ignoreCase = true) }.minWithOrNull(LOGO_ORDER)
        bestIn(display.iso639)?.let { return pick(it) }
        title.language?.takeUnless { it.equals(display.iso639, ignoreCase = true) }
            ?.let(::bestIn)?.let { return pick(it) }

        val shown = normalize(title.text)
        if (shown.isEmpty()) return null
        return usable.filter { img ->
            val lang = img.iso639 ?: return@filter false
            !lang.equals(display.iso639, ignoreCase = true) && !lang.equals(title.language, ignoreCase = true) &&
                DisplayInfoBuilder.titleIn(details, lang).any { normalize(it) == shown }
        }.minWithOrNull(LOGO_ORDER)?.let(::pick)
    }

    private fun pick(img: ImageInfo) = LogoPick(downloadPath(img.filePath), downloadSize(img))

    private fun isUsableLogo(img: ImageInfo): Boolean {
        if (!isPng(img.filePath) && !isSvg(img.filePath)) return false
        if (img.width <= 0 || img.height <= 0) return false
        val aspect = img.width.toDouble() / img.height
        if (aspect < LogoRules.MIN_ASPECT || aspect > LogoRules.MAX_ASPECT) return false
        return fitsAt(img, if (downloadSize(img) == LOGO_SIZE_WIDE) img.width.toDouble() else LOGO_W500_WIDTH)
    }

    /** Height ≥ [LogoRules.MIN_HEIGHT] when downloaded at most [maxWidth] wide and fitted without upscaling. */
    private fun fitsAt(img: ImageInfo, maxWidth: Double): Boolean {
        if (img.width <= 0 || img.height <= 0) return false
        val aspect = img.width.toDouble() / img.height
        val w = min(img.width.toDouble(), maxWidth)
        val h = w / aspect
        val boxW = if (aspect > LogoRules.WIDE_ASPECT) LogoRules.DEFAULT_BLOCK_WIDTH.toDouble() else LOGO_BOX_W
        val scale = min(1.0, min(boxW / w, LOGO_BOX_H / h))
        return h * scale >= LogoRules.MIN_HEIGHT
    }

    /**
     * A wide PNG logo may span the whole block, wider than w500, so it is downloaded at original (logo files are small).
     * An SVG logo is rendered by TMDB as a PNG at a fixed size, so it stays at w500.
     */
    private fun downloadSize(img: ImageInfo): String {
        val wide = img.width.toDouble() / img.height > LogoRules.WIDE_ASPECT && img.width > LOGO_W500_WIDTH
        return if (wide && isPng(img.filePath)) LOGO_SIZE_WIDE else LOGO_SIZE
    }

    /** An SVG logo is downloaded as its PNG rendition at the same path. */
    private fun downloadPath(path: String): String =
        if (isSvg(path)) path.substring(0, path.length - SVG.length) + PNG else path

    private fun isPng(path: String) = path.endsWith(PNG, ignoreCase = true)

    private fun isSvg(path: String) = path.endsWith(SVG, ignoreCase = true)

    private const val PNG = ".png"
    private const val SVG = ".svg"

    /** Skips SVG and anything else BitmapFactory cannot decode. */
    private fun isRaster(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp")
    }

    /** Title comparison form: NFKC, lowercase, letters and digits only. */
    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }
}
