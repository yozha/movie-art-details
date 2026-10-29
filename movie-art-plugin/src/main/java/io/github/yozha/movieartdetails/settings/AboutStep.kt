package io.github.yozha.movieartdetails.settings

import androidx.core.content.ContextCompat
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import androidx.leanback.widget.GuidedActionsStylist
import io.github.yozha.movieartdetails.BuildConfig
import io.github.yozha.movieartdetails.R

/**
 * About/Credits: TMDB attribution with the official logo (smaller than the plugin mark in the header),
 * the original English notice required by the API Terms of Use (a translation may accompany it but never replaces it),
 * and the link as plain text; MIT (Movie Art) and Apache-2.0 (Projectivy API, Sofadrop, Roboto, libraries) licenses.
 * Links are not clickable: the device may not have a browser.
 */
class AboutStep : BaseStep() {

    override fun guidance() = Guidance(
        getString(R.string.plugin_name),
        getString(R.string.about_version_format, BuildConfig.VERSION_NAME),
        getString(R.string.about_title),
        ContextCompat.getDrawable(requireContext(), R.drawable.ic_banner),
    )

    override fun buildActions(): List<GuidedAction> {
        val translation = getString(R.string.tmdb_notice_translation)
        val tmdb = listOf(getString(R.string.tmdb_notice), translation.takeIf { it.isNotBlank() }, getString(R.string.tmdb_url))
            .filterNotNull()
            .joinToString("\n")
        return listOf(
            item(ID_TMDB, getString(R.string.tmdb_name), tmdb, icon = ContextCompat.getDrawable(requireContext(), R.drawable.tmdb_logo)),
            item(ID_MOVIE_ART, getString(R.string.credit_movie_art_title), getString(R.string.credit_movie_art)),
            item(ID_API, getString(R.string.credit_api_title), getString(R.string.credit_api)),
            item(ID_SOFADROP, getString(R.string.credit_sofadrop_title), getString(R.string.credit_sofadrop)),
            item(ID_FONT, getString(R.string.credit_font_title), getString(R.string.credit_font)),
            item(ID_LIBS, getString(R.string.credit_libs_title), getString(R.string.credit_libs)),
            item(ID_PRIVACY, getString(R.string.about_privacy_title), getString(R.string.about_privacy)),
        )
    }

    /**
     * A leanback action icon is a 32dp square with fitCenter, which makes the TMDB logo (2.33:1) too small.
     * The width follows the drawable's aspect ratio at the same height, so the logo is not distorted and stays small.
     */
    override fun onCreateActionsStylist(): GuidedActionsStylist = object : GuidedActionsStylist() {
        override fun onBindViewHolder(vh: GuidedActionsStylist.ViewHolder, action: GuidedAction) {
            super.onBindViewHolder(vh, action)
            val view = vh.iconView ?: return
            val lp = view.layoutParams ?: return
            if (lp.height <= 0) return
            val d = action.icon
            val width = if (d != null && d.intrinsicWidth > 0 && d.intrinsicHeight > 0) lp.height * d.intrinsicWidth / d.intrinsicHeight else lp.height
            if (lp.width != width) {
                lp.width = width
                view.layoutParams = lp
            }
        }
    }

    private companion object {
        const val ID_TMDB = 1L
        const val ID_MOVIE_ART = 2L
        const val ID_API = 3L
        const val ID_SOFADROP = 4L
        const val ID_FONT = 5L
        const val ID_LIBS = 6L
        const val ID_PRIVACY = 7L
    }
}
