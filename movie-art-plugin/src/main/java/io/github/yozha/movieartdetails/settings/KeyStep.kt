package io.github.yozha.movieartdetails.settings

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import androidx.leanback.widget.GuidedActionsStylist
import io.github.yozha.movieartdetails.R
import io.github.yozha.movieartdetails.core.ServiceLocator
import io.github.yozha.movieartdetails.model.TmdbResult

/**
 * TMDB key: entry with the remote (system keyboard, no suggestions or learning), a mask showing the last
 * 4 characters, validation via GET /3/authentication with a message per outcome, separate removal.
 * Entered text is not saved in fragment state (autoSaveRestore is off) and is cleared from the field immediately.
 */
class KeyStep : BaseStep() {

    override fun guidance() = Guidance(
        getString(R.string.key_title),
        getString(R.string.key_description),
        getString(R.string.plugin_name),
        null,
    )

    override fun buildActions(): List<GuidedAction> = listOf(
        item(ID_CURRENT, getString(R.string.key_current), getString(R.string.loading)),
        GuidedAction.Builder(requireContext())
            .id(ID_INPUT)
            .title(getString(R.string.key_enter))
            .description(getString(R.string.key_enter_hint))
            .editTitle("")
            .editable(true)
            .editInputType(INPUT_TYPE)
            .autoSaveRestoreEnabled(false)
            .multilineDescription(true)
            .build(),
        item(ID_VALIDATE, getString(R.string.key_validate)),
        item(ID_DELETE, getString(R.string.key_delete)),
    )

    /** IME_FLAG_NO_PERSONALIZED_LEARNING is set on the field on top of the imeOptions set by leanback. */
    override fun onCreateActionsStylist(): GuidedActionsStylist = object : GuidedActionsStylist() {
        override fun onBindViewHolder(vh: GuidedActionsStylist.ViewHolder, action: GuidedAction) {
            super.onBindViewHolder(vh, action)
            if (action.id == ID_INPUT) {
                val field: TextView? = vh.editableTitleView
                field?.let { it.imeOptions = it.imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshMask()
    }

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        if (action.id == ID_INPUT) {
            val input = action.editTitle?.toString().orEmpty()
            action.editTitle = ""
            changed(ID_INPUT)
            if (!CredentialStore.isValidFormat(input)) {
                describe(ID_INPUT, getString(R.string.key_format_error))
            } else {
                describe(ID_INPUT, getString(R.string.key_enter_hint))
                describe(ID_VALIDATE, getString(R.string.key_status_checking))
                background({
                    ServiceLocator.credentials.set(input)
                    ServiceLocator.tmdb.validateKey()
                }) { r ->
                    refreshMask()
                    describe(ID_VALIDATE, r.fold(::validationText) { getString(R.string.key_status_save_failed) })
                }
            }
        }
        return GuidedAction.ACTION_ID_CURRENT
    }

    override fun onGuidedActionEditCanceled(action: GuidedAction) {
        if (action.id == ID_INPUT) {
            action.editTitle = ""
            changed(ID_INPUT)
        }
    }

    override fun onClick(action: GuidedAction) {
        when (action.id) {
            ID_VALIDATE -> {
                describe(ID_VALIDATE, getString(R.string.key_status_checking))
                background({ ServiceLocator.tmdb.validateKey() }) { r ->
                    describe(ID_VALIDATE, r.fold(::validationText) { getString(R.string.key_status_no_connection) })
                }
            }
            ID_DELETE -> background({ ServiceLocator.credentials.clear() }) {
                refreshMask()
                describe(ID_VALIDATE, null)
                describe(ID_DELETE, getString(R.string.key_deleted))
            }
        }
    }

    private fun refreshMask() {
        background({ ServiceLocator.credentials.masked() }) { r ->
            describe(ID_CURRENT, r.getOrNull() ?: getString(R.string.key_not_set))
        }
    }

    /** 200 → valid; 401 with status 7/3/14 → invalid; 401 with status 10 → suspended; network/429/5xx → no connection. */
    private fun validationText(result: TmdbResult<Unit>): String = when (result) {
        is TmdbResult.Ok -> getString(R.string.key_status_valid)
        is TmdbResult.Err -> errorText(result.error)
    }

    private companion object {
        const val ID_CURRENT = 1L
        const val ID_INPUT = 2L
        const val ID_VALIDATE = 3L
        const val ID_DELETE = 4L
        const val INPUT_TYPE = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    }
}
