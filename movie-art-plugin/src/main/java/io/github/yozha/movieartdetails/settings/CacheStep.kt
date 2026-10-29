package io.github.yozha.movieartdetails.settings

import android.text.format.Formatter
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import io.github.yozha.movieartdetails.R
import io.github.yozha.movieartdetails.core.ServiceLocator

/** Cache: sizes of both levels, the space limit (200 MB by default), clearing only the plugin's own files. */
class CacheStep : BaseStep() {
    private val settings by lazy { AppSettings(requireContext()) }

    override val choices: List<Choice> by lazy {
        listOf(
            Choice(
                ID_LIMIT, getString(R.string.cache_limit),
                LIMITS_MB.map { Formatter.formatShortFileSize(requireContext(), it * 1024L * 1024L) },
                { LIMITS_MB.indices.minBy { i -> kotlin.math.abs(LIMITS_MB[i] - settings.cacheLimitMb()) } },
            ) {
                settings.setCacheLimitMb(LIMITS_MB[it])
                background({ ServiceLocator.trimCaches() }) { refreshSize() }
            },
        )
    }

    override fun guidance() = Guidance(
        getString(R.string.cache_title),
        getString(R.string.cache_description),
        getString(R.string.plugin_name),
        null,
    )

    override fun buildActions(): List<GuidedAction> = listOf(
        item(ID_SIZE, getString(R.string.cache_size), getString(R.string.loading)),
        choiceAction(choices.first()),
        item(ID_CLEAR, getString(R.string.cache_clear), getString(R.string.cache_clear_hint)),
    )

    override fun onResume() {
        super.onResume()
        refreshSize()
    }

    override fun onClick(action: GuidedAction) {
        if (action.id != ID_CLEAR) return
        describe(ID_CLEAR, getString(R.string.cache_clearing))
        background({ ServiceLocator.clearCaches() }) { r ->
            describe(ID_CLEAR, getString(if (r.isSuccess) R.string.cache_cleared else R.string.cache_clear_failed))
            refreshSize()
        }
    }

    private fun refreshSize() {
        background({ ServiceLocator.sourceCache.sizeBytes() to ServiceLocator.compositeStore.sizeBytes() }) { r ->
            val text = r.getOrNull()?.let { (source, composites) ->
                getString(R.string.cache_size_format, size(source), size(composites))
            }
            describe(ID_SIZE, text ?: getString(R.string.cache_size_unknown))
        }
    }

    private fun size(bytes: Long) = Formatter.formatShortFileSize(requireContext(), bytes)

    private companion object {
        const val ID_SIZE = 1L
        const val ID_LIMIT = 2L
        const val ID_CLEAR = 3L
        val LIMITS_MB = listOf(100, 200, 500, 1000)
    }
}
