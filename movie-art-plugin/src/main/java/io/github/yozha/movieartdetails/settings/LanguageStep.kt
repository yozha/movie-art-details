package io.github.yozha.movieartdetails.settings

import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import io.github.yozha.movieartdetails.R

/** Metadata language: system default, Russian, Ukrainian, English. Picking one returns immediately. */
class LanguageStep : BaseStep() {
    private val settings by lazy { AppSettings(requireContext()) }

    override fun guidance() = Guidance(
        getString(R.string.language_title),
        getString(R.string.language_description),
        getString(R.string.plugin_name),
        null,
    )

    override fun buildActions(): List<GuidedAction> {
        val current = settings.languageChoice()
        return LanguageChoice.entries.map { c ->
            GuidedAction.Builder(requireContext())
                .id(c.ordinal.toLong() + 1)
                .title(languageLabel(c))
                .description(if (c == LanguageChoice.SYSTEM) getString(R.string.language_system_hint) else null)
                .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                .checked(c == current)
                .build()
        }
    }

    override fun onClick(action: GuidedAction) {
        val choice = LanguageChoice.entries.getOrNull((action.id - 1).toInt()) ?: return
        settings.setLanguage(choice)
        parentFragmentManager.popBackStack()
    }
}
