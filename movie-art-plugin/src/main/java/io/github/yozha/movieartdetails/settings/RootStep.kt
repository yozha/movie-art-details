package io.github.yozha.movieartdetails.settings

import androidx.core.content.ContextCompat
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import io.github.yozha.movieartdetails.R
import io.github.yozha.movieartdetails.core.ServiceLocator

/** Main settings menu. Labels are refreshed in onResume, after returning from a nested screen. */
class RootStep : BaseStep() {
    private val settings by lazy { AppSettings(requireContext()) }

    override fun guidance() = Guidance(
        getString(R.string.plugin_name),
        getString(R.string.settings_root_description),
        getString(R.string.settings_title),
        ContextCompat.getDrawable(requireContext(), R.drawable.ic_banner),
    )

    override fun buildActions(): List<GuidedAction> = listOf(
        item(ID_KEY, getString(R.string.key_title), null, next = true),
        item(ID_LANGUAGE, getString(R.string.language_title), languageLabel(settings.languageChoice()), next = true),
        item(ID_APPEARANCE, getString(R.string.appearance_title), getString(R.string.appearance_summary), next = true),
        item(ID_RECOGNITION, getString(R.string.recognition_title), getString(R.string.recognition_summary), next = true),
        item(ID_CACHE, getString(R.string.cache_title), getString(R.string.cache_summary), next = true),
        item(ID_DIAGNOSTICS, getString(R.string.diagnostics_title), null, next = true),
        item(ID_ABOUT, getString(R.string.about_title), getString(R.string.about_summary), next = true),
    )

    override fun onResume() {
        super.onResume()
        describe(ID_LANGUAGE, languageLabel(settings.languageChoice()))
        describe(ID_DIAGNOSTICS, getString(if (settings.diagnosticsEnabled()) R.string.state_on else R.string.state_off))
        background({ ServiceLocator.credentials.masked() }) { r ->
            describe(ID_KEY, r.getOrNull() ?: getString(R.string.key_not_set))
        }
    }

    override fun onClick(action: GuidedAction) {
        when (action.id) {
            ID_KEY -> open(KeyStep())
            ID_LANGUAGE -> open(LanguageStep())
            ID_APPEARANCE -> open(AppearanceStep())
            ID_RECOGNITION -> open(RecognitionStep())
            ID_CACHE -> open(CacheStep())
            ID_DIAGNOSTICS -> open(DiagnosticsStep())
            ID_ABOUT -> open(AboutStep())
        }
    }

    private companion object {
        const val ID_KEY = 1L
        const val ID_LANGUAGE = 2L
        const val ID_APPEARANCE = 3L
        const val ID_RECOGNITION = 4L
        const val ID_CACHE = 5L
        const val ID_DIAGNOSTICS = 6L
        const val ID_ABOUT = 7L
    }
}
