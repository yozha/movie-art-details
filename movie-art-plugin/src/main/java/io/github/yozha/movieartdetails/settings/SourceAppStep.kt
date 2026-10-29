package io.github.yozha.movieartdetails.settings

import android.graphics.drawable.Drawable
import android.os.Bundle
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import io.github.yozha.movieartdetails.R
import io.github.yozha.movieartdetails.core.InstalledApp
import io.github.yozha.movieartdetails.core.ServiceLocator

/** The app shown for a host: the user's choice, or else the one found automatically; [label] is null when there is none. */
data class SourceAppChoice(val label: String?, val chosen: Boolean)

internal fun BaseStep.sourceAppLabel(c: SourceAppChoice?): String = when {
    c?.label == null -> getString(R.string.source_app_unknown)
    c.chosen -> c.label
    else -> getString(R.string.source_app_auto_format, c.label)
}

/**
 * The source app of an image host, for the badge in the info block: "Automatic" (with the app it detects, if any)
 * or one of the installed apps with a launcher activity, with its icon. Projectivy does not say which app a program card comes
 * from, and a web host (a LAN media server, a streaming CDN) cannot be traced to an app automatically.
 * The choice is stored only on this device.
 */
class SourceAppStep : BaseStep() {
    private val host get() = requireArguments().getString(ARG_HOST).orEmpty()
    private var apps: List<Pair<InstalledApp, Drawable?>> = emptyList()
    private var selected: String? = null
    /** Label of the app found without the user's choice; null when there is none. */
    private var detected: String? = null
    private var loaded = false
    /** If saving finishes after onSaveInstanceState, popBackStack() would throw IllegalStateException, so the screen closes in onResume instead. */
    private var pendingClose = false

    override fun guidance() = Guidance(host, getString(R.string.source_app_description), getString(R.string.source_app), null)

    override fun buildActions(): List<GuidedAction> {
        if (!loaded) return listOf(item(ID_STATUS, getString(R.string.loading)))
        val auto = detected?.let { getString(R.string.source_app_detected_format, it) } ?: getString(R.string.source_app_automatic_hint)
        val list = mutableListOf(
            item(ID_AUTO, getString(R.string.source_app_automatic), if (selected == null) getString(R.string.source_app_selected) + "\n" + auto else auto),
        )
        if (apps.isEmpty()) list += item(ID_STATUS, getString(R.string.source_app_empty))
        apps.forEachIndexed { i, (app, icon) ->
            val note = if (app.packageName == selected) getString(R.string.source_app_selected) else null
            list += item(BASE + i, app.label, note, icon = icon)
        }
        return list
    }

    override fun onResume() {
        super.onResume()
        if (pendingClose) {
            close()
            return
        }
        val h = host
        background({
            val catalog = ServiceLocator.appCatalog
            val list = catalog.installedApps().map { it to catalog.icon(it.packageName) }
            Triple(list, ServiceLocator.sourceAppMappings.get(h), ServiceLocator.sourceApps.automaticPackage(h)?.let(catalog::label))
        }) { r ->
            r.getOrNull()?.let { (list, current, auto) ->
                apps = list
                selected = current
                detected = auto
            }
            loaded = true
            rebuild()
            focusSelected()
        }
    }

    /** Keeps the focus on the selected row: a rebuild would otherwise move it to the first one. */
    private fun focusSelected() {
        val index = selected?.let { pkg -> apps.indexOfFirst { it.first.packageName == pkg } } ?: -1
        val pos = if (index >= 0) findActionPositionById(BASE + index) else findActionPositionById(ID_AUTO)
        if (pos >= 0 && view != null) selectedActionPosition = pos
    }

    override fun onClick(action: GuidedAction) {
        val h = host
        val id = action.id
        when {
            id == ID_AUTO -> save(id) { ServiceLocator.sourceAppMappings.remove(h) }
            id >= BASE -> apps.getOrNull((id - BASE).toInt())?.let { (app, _) -> save(id) { ServiceLocator.sourceAppMappings.put(h, app.packageName) } }
        }
    }

    private fun save(id: Long, work: () -> Unit) {
        background(work) { r -> if (r.isSuccess) close() else describe(id, getString(R.string.confirm_failed)) }
    }

    private fun close() {
        if (!isAdded) return
        pendingClose = parentFragmentManager.isStateSaved
        if (!pendingClose) parentFragmentManager.popBackStack()
    }

    companion object {
        private const val ARG_HOST = "host"
        private const val ID_STATUS = 1L
        private const val ID_AUTO = 2L
        private const val BASE = 100L

        fun of(host: String) = SourceAppStep().apply { arguments = Bundle().apply { putString(ARG_HOST, host) } }

        /** The app shown for [host]. Blocking: call on a background thread. */
        fun describe(host: String): SourceAppChoice {
            val catalog = ServiceLocator.appCatalog
            ServiceLocator.sourceAppMappings.get(host)?.let(catalog::label)?.let { return SourceAppChoice(it, chosen = true) }
            return SourceAppChoice(ServiceLocator.sourceApps.automaticPackage(host)?.let(catalog::label), chosen = false)
        }
    }
}
