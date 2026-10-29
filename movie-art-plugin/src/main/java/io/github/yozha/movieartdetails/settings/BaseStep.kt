package io.github.yozha.movieartdetails.settings

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import io.github.yozha.movieartdetails.R
import io.github.yozha.movieartdetails.model.TmdbError
import io.github.yozha.movieartdetails.model.TmdbErrorKind
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Common base for settings screens: action builders, value selection via sub-actions,
 * checkboxes and background tasks (all module APIs block, so they are never called on the main thread).
 */
abstract class BaseStep : GuidedStepSupportFragment() {

    /** A value chosen from a list with the remote: sub-actions with id = parentId·[SUB] + index. */
    protected class Choice(
        val id: Long,
        val title: String,
        val labels: List<String>,
        val current: () -> Int,
        val select: (Int) -> Unit,
    )

    protected open val choices: List<Choice> get() = emptyList()

    protected abstract fun guidance(): Guidance

    protected abstract fun buildActions(): List<GuidedAction>

    /** Checkbox toggle (the state has already been changed by leanback). */
    protected open fun onToggle(id: Long, checked: Boolean) {}

    protected open fun onClick(action: GuidedAction) {}

    override fun onCreateGuidance(savedInstanceState: Bundle?): Guidance = guidance()

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        actions.addAll(buildActions())
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        if (action.checkSetId == GuidedAction.CHECKBOX_CHECK_SET_ID) onToggle(action.id, action.isChecked) else onClick(action)
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val choice = choices.firstOrNull { it.id == action.id / SUB } ?: return true
        choice.select((action.id % SUB).toInt())
        syncChoices()
        return true
    }

    /** Rebuilds the whole list (after a reset or data load). */
    protected fun rebuild() {
        setActions(buildActions())
    }

    /** Refreshes the labels and checkmarks of all choices from current values (dependent values may have shifted). */
    protected fun syncChoices() {
        for (c in choices) {
            val action = findActionById(c.id) ?: continue
            val index = c.current()
            action.description = c.labels.getOrNull(index)
            action.subActions?.forEachIndexed { i, sub -> sub.isChecked = i == index }
            changed(c.id)
        }
    }

    protected fun changed(id: Long) {
        val pos = findActionPositionById(id)
        if (pos >= 0 && view != null) notifyActionChanged(pos)
    }

    protected fun describe(id: Long, text: CharSequence?) {
        findActionById(id)?.description = text
        changed(id)
    }

    /** A plain row. Read-only rows are built this way too: infoOnly rows cannot be focused or scrolled to with the remote. */
    protected fun item(id: Long, title: CharSequence, description: CharSequence? = null, next: Boolean = false, icon: Drawable? = null): GuidedAction =
        GuidedAction.Builder(requireContext())
            .id(id)
            .title(title)
            .description(description)
            .multilineDescription(true)
            .hasNext(next)
            .icon(icon)
            .build()

    protected fun checkbox(id: Long, title: CharSequence, checked: Boolean, description: CharSequence? = null): GuidedAction =
        GuidedAction.Builder(requireContext())
            .id(id)
            .title(title)
            .description(description)
            .multilineDescription(true)
            .checkSetId(GuidedAction.CHECKBOX_CHECK_SET_ID)
            .checked(checked)
            .build()

    protected fun choiceAction(c: Choice): GuidedAction {
        val index = c.current()
        val subs = c.labels.mapIndexed { i, label ->
            GuidedAction.Builder(requireContext())
                .id(c.id * SUB + i)
                .title(label)
                .checkSetId(GuidedAction.DEFAULT_CHECK_SET_ID)
                .checked(i == index)
                .build()
        }
        return GuidedAction.Builder(requireContext())
            .id(c.id)
            .title(c.title)
            .description(c.labels.getOrNull(index))
            .subActions(subs)
            .build()
    }

    /** Opens a nested screen; does nothing after onSaveInstanceState, when a fragment transaction is no longer allowed. */
    protected fun open(step: GuidedStepSupportFragment) {
        if (isAdded && !parentFragmentManager.isStateSaved) GuidedStepSupportFragment.add(parentFragmentManager, step)
    }

    protected fun <T> background(work: () -> T, done: (Result<T>) -> Unit): Future<*> = SettingsTasks.run(this, work, done)

    protected fun languageLabel(c: LanguageChoice): String = getString(
        when (c) {
            LanguageChoice.SYSTEM -> R.string.language_system
            LanguageChoice.RU -> R.string.language_ru
            LanguageChoice.UK -> R.string.language_uk
            LanguageChoice.EN -> R.string.language_en
        },
    )

    protected fun errorText(e: TmdbError): String = getString(
        when (e.kind) {
            TmdbErrorKind.NO_KEY -> R.string.key_status_missing
            TmdbErrorKind.INVALID_KEY -> R.string.key_status_invalid
            TmdbErrorKind.SUSPENDED_KEY -> R.string.key_status_suspended
            TmdbErrorKind.NOT_FOUND -> R.string.error_not_found
            TmdbErrorKind.BAD_REQUEST -> R.string.error_bad_request
            // Timeout, DNS/TLS, 429, 5xx, non-JSON: "no connection"; the key is not reported as invalid.
            TmdbErrorKind.NETWORK, TmdbErrorKind.RATE_LIMITED, TmdbErrorKind.SERVER,
            TmdbErrorKind.BAD_RESPONSE, TmdbErrorKind.TOO_LARGE -> R.string.key_status_no_connection
        },
    )

    companion object {
        const val SUB = 1000L
    }
}

/**
 * Background tasks for settings screens.
 * - [run]: a single thread, so "save key → validate" stays ordered and the network is not flooded.
 * - [search]: TMDB candidate searches (up to 8 requests) get their own small pool, so saving, key validation
 *   and cache sizing never wait tens of seconds behind them; [requests] runs the parallel requests of one search.
 * A canceled task ([Future.cancel]) does not deliver its result.
 */
internal object SettingsTasks {
    private val main = Handler(Looper.getMainLooper())
    private val executor: ExecutorService = Executors.newSingleThreadExecutor(threads("settings-bg"))
    private val searches: ExecutorService = pool(2, "settings-search")
    /** Separate from [searches]: a search task waits for its own requests, which could deadlock in a shared pool. */
    private val requests: ExecutorService = pool(4, "settings-request")

    /** [work] runs on a background thread; [done] on the main thread, only if the task was not canceled and the fragment is still attached. */
    fun <T> run(owner: Fragment, work: () -> T, done: (Result<T>) -> Unit): Future<*> = submit(executor, owner, work, done)

    fun <T> search(owner: Fragment, work: () -> T, done: (Result<T>) -> Unit): Future<*> = submit(searches, owner, work, done)

    /** Runs [calls] in parallel and returns the results in order; interrupting the waiting thread cancels them all. */
    fun <T> all(calls: List<() -> T>): List<T> {
        val futures = calls.map { call -> requests.submit(Callable { call() }) }
        return try {
            futures.map { it.get() }
        } finally {
            futures.forEach { it.cancel(true) }
        }
    }

    private fun <T> submit(ex: ExecutorService, owner: Fragment, work: () -> T, callback: (Result<T>) -> Unit): Future<*> =
        Task(owner, work, callback).also { ex.execute(it) }

    /** FutureTask's done() is also called on cancellation; in that case (and afterwards) the result is not delivered. */
    private class Task<T>(
        private val owner: Fragment,
        work: () -> T,
        private val callback: (Result<T>) -> Unit,
    ) : FutureTask<Result<T>>(Callable { runCatching(work) }) {
        override fun done() {
            if (isCancelled) return
            val result = try { get() } catch (e: Exception) { return }
            SettingsTasks.main.post { if (!isCancelled && owner.isAdded) callback(result) }
        }
    }

    private fun threads(name: String) = ThreadFactory { r -> Thread(r, name).apply { isDaemon = true } }

    private fun pool(size: Int, name: String): ExecutorService =
        ThreadPoolExecutor(size, size, 30, TimeUnit.SECONDS, LinkedBlockingQueue(), threads(name)).apply { allowCoreThreadTimeOut(true) }
}
