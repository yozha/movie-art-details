package io.github.yozha.movieartdetails.tmdb

import io.github.yozha.movieartdetails.model.DisplayLanguage
import io.github.yozha.movieartdetails.model.ImageInfo
import io.github.yozha.movieartdetails.model.TitleDetails
import java.text.Normalizer
import kotlin.math.min

/**
 * Picks the backdrop and the logo from the details' images.
 * Returns file_path; the caller chooses the download size (backdrop: original, logo: w500).
 */
object ImageSelector {

    private const val MIN_BACKDROP_WIDTH = 1920
    private const val LOGO_MIN_ASPECT = 0.8
    private const val LOGO_MAX_ASPECT = 9.0
    /** The logo is downloaded at w500 and fitted into 520×130; after fitting it must be at least 60 px tall. */
    private const val LOGO_DOWNLOAD_WIDTH = 500.0
    private const val LOGO_BOX_W = 520.0
    private const val LOGO_BOX_H = 130.0
    private const val LOGO_MIN_HEIGHT = 60.0

    private val BY_VOTE_THEN_WIDTH = compareByDescending<ImageInfo> { it.voteAverage }.thenByDescending { it.width }

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
     * PNG only, with an aspect ratio of 0.8–9 and enough height after fitting.
     * A logo in the display language comes first; a logo in another language is used only if the title in that
     * language matches the shown [displayedTitle] (e.g. an en logo when the English title is shown).
     * Logos without a language are skipped: there is no telling which title they show. null → text title.
     */
    fun logo(details: TitleDetails, language: DisplayLanguage, displayedTitle: String): String? {
        val usable = details.logos.filter { isUsableLogo(it) }
        usable.filter { it.iso639.equals(language.iso639, ignoreCase = true) }
            .minWithOrNull(BY_VOTE_THEN_WIDTH)?.let { return it.filePath }

        val shown = normalize(displayedTitle)
        if (shown.isEmpty()) return null
        return usable.filter { img ->
            val lang = img.iso639 ?: return@filter false
            !lang.equals(language.iso639, ignoreCase = true) &&
                DisplayInfoBuilder.titleIn(details, lang).any { normalize(it) == shown }
        }.minWithOrNull(BY_VOTE_THEN_WIDTH)?.filePath
    }

    private fun isUsableLogo(img: ImageInfo): Boolean {
        if (!img.filePath.endsWith(".png", ignoreCase = true)) return false
        if (img.width <= 0 || img.height <= 0) return false
        val aspect = img.width.toDouble() / img.height
        if (aspect < LOGO_MIN_ASPECT || aspect > LOGO_MAX_ASPECT) return false
        val w = min(img.width.toDouble(), LOGO_DOWNLOAD_WIDTH)
        val h = w / aspect
        val scale = min(1.0, min(LOGO_BOX_W / w, LOGO_BOX_H / h))
        return h * scale >= LOGO_MIN_HEIGHT
    }

    /** Skips SVG and anything else BitmapFactory cannot decode. */
    private fun isRaster(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf("jpg", "jpeg", "png", "webp")
    }

    /** Title comparison form: NFKC, lowercase, letters and digits only. */
    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase().filter { it.isLetterOrDigit() }
}
