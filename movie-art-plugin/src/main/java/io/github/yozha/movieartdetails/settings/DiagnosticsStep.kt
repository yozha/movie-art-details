package io.github.yozha.movieartdetails.settings

import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import io.github.yozha.movieartdetails.R
import io.github.yozha.movieartdetails.core.DiagnosticsLog
import io.github.yozha.movieartdetails.core.ServiceLocator

/**
 * Diagnostics: a user-enabled log of the last 50 events, separate consent to record card titles,
 * and the last TMDB error. Lines are built by core/DiagnosticsLog and never include
 * the key or URLs.
 */
class DiagnosticsStep : BaseStep() {
    private val settings by lazy { AppSettings(requireContext()) }
    private var lines: List<String>? = null
    private var lastError: String? = null

    override fun guidance() = Guidance(
        getString(R.string.diagnostics_title),
        getString(R.string.diagnostics_description),
        getString(R.string.plugin_name),
        null,
    )

    override fun buildActions(): List<GuidedAction> {
        val head = listOf(
            checkbox(ID_ENABLED, getString(R.string.diagnostics_enable), settings.diagnosticsEnabled()),
            checkbox(ID_TITLES, getString(R.string.diagnostics_titles), settings.logTitlesInDiagnostics(), getString(R.string.diagnostics_titles_hint)),
            item(ID_LAST_ERROR, getString(R.string.diagnostics_last_error), lastError ?: getString(R.string.diagnostics_last_error_none)),
            item(ID_REFRESH, getString(R.string.diagnostics_refresh)),
            item(ID_CLEAR, getString(R.string.diagnostics_clear)),
        )
        val log = lines
        val body = when {
            log == null -> listOf(item(ID_EMPTY, getString(R.string.loading)))
            log.isEmpty() -> listOf(item(ID_EMPTY, getString(R.string.diagnostics_empty)))
            // recent() returns newest first; the whole line goes in the description (multi-line) so it is not cut off.
            else -> log.mapIndexed { i, line -> item(LINE_BASE + i, "#${i + 1}", line) }
        }
        return head + body
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    override fun onToggle(id: Long, checked: Boolean) {
        when (id) {
            ID_ENABLED -> {
                settings.setDiagnosticsEnabled(checked)
                // Turning the log off also revokes title consent: it must be granted again next time.
                if (!checked) {
                    forgetTitles()
                    settings.setLogTitlesInDiagnostics(false)
                    findActionById(ID_TITLES)?.isChecked = false
                    changed(ID_TITLES)
                }
            }
            ID_TITLES -> if (checked && !settings.diagnosticsEnabled()) {
                findActionById(ID_TITLES)?.isChecked = false
                describe(ID_TITLES, getString(R.string.diagnostics_titles_needs_log))
            } else {
                // Revoking consent also erases lines with titles that were already recorded.
                if (!checked) forgetTitles()
                settings.setLogTitlesInDiagnostics(checked)
            }
        }
    }

    private fun forgetTitles() {
        background({ (ServiceLocator.diagnostics as? DiagnosticsLog)?.clear() }) { reload() }
    }

    override fun onClick(action: GuidedAction) {
        when (action.id) {
            ID_REFRESH -> reload()
            ID_CLEAR -> background({ (ServiceLocator.diagnostics as? DiagnosticsLog)?.clear() }) { reload() }
        }
    }

    private fun reload() {
        background({
            val log = ServiceLocator.diagnostics
            log.recent().take(MAX_LINES) to (log as? DiagnosticsLog)?.lastErrorText()
        }) { r ->
            val (recent, error) = r.getOrNull() ?: (emptyList<String>() to null)
            lines = recent
            lastError = error
            rebuild()
        }
    }

    private companion object {
        const val ID_ENABLED = 1L
        const val ID_TITLES = 2L
        const val ID_REFRESH = 3L
        const val ID_EMPTY = 4L
        const val ID_LAST_ERROR = 5L
        const val ID_CLEAR = 6L
        const val LINE_BASE = 100L
        const val MAX_LINES = 50
    }
}
