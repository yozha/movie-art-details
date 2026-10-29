package io.github.yozha.movieartdetails.settings

import android.os.Bundle
import android.text.InputType
import androidx.leanback.widget.GuidanceStylist.Guidance
import androidx.leanback.widget.GuidedAction
import io.github.yozha.movieartdetails.R
import io.github.yozha.movieartdetails.core.ServiceLocator
import io.github.yozha.movieartdetails.match.SearchLanguages
import io.github.yozha.movieartdetails.match.SourceHints
import io.github.yozha.movieartdetails.match.TitleMatcher
import io.github.yozha.movieartdetails.match.TitleParser
import io.github.yozha.movieartdetails.model.Candidate
import io.github.yozha.movieartdetails.model.CardKind
import io.github.yozha.movieartdetails.model.ManualMapping
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.RecentTitle
import io.github.yozha.movieartdetails.model.ScoredCandidate
import io.github.yozha.movieartdetails.model.TmdbError
import io.github.yozha.movieartdetails.model.TmdbResult
import java.util.concurrent.Future
import kotlin.math.roundToInt

/*
 * Manual correction: recent titles → TMDB candidates (title, year, type) → confirmation;
 * entering a TMDB ID (with its type) or an IMDb ID; "Do not recognize this title"; listing and removing corrections;
 * clearing title history; the source app of the title's image host (SourceAppStep).
 * Titles are only shown on screen and never written to the log.
 */

private fun BaseStep.typeLabel(type: MediaType): String =
    getString(if (type == MediaType.MOVIE) R.string.type_movie else R.string.type_tv)

private fun BaseStep.targetLabel(m: ManualMapping): String =
    m.target?.let { (type, id) -> getString(R.string.mapping_target_format, typeLabel(type), id) }
        ?: getString(R.string.mapping_ignored)

/** "Recognition" section. */
class RecognitionStep : BaseStep() {

    override fun guidance() = Guidance(
        getString(R.string.recognition_title),
        getString(R.string.recognition_description),
        getString(R.string.plugin_name),
        null,
    )

    override fun buildActions(): List<GuidedAction> = listOf(
        item(ID_RECENT, getString(R.string.recent_title), getString(R.string.loading), next = true),
        item(ID_MAPPINGS, getString(R.string.mappings_title), getString(R.string.loading), next = true),
        item(ID_CLEAR_HISTORY, getString(R.string.recent_clear), getString(R.string.recent_clear_hint)),
    )

    override fun onResume() {
        super.onResume()
        background({ ServiceLocator.recent.all().size to ServiceLocator.mappings.all().size }) { r ->
            val (recent, mappings) = r.getOrNull() ?: (0 to 0)
            describe(ID_RECENT, getString(R.string.count_format, recent))
            describe(ID_MAPPINGS, getString(R.string.count_format, mappings))
        }
    }

    override fun onClick(action: GuidedAction) {
        when (action.id) {
            ID_RECENT -> open(RecentStep())
            ID_MAPPINGS -> open(MappingsStep())
            // Title history is also kept in the diagnostics log: clear both.
            ID_CLEAR_HISTORY -> background({ ServiceLocator.recent.clear(); ServiceLocator.diagnostics.clear() }) {
                describe(ID_RECENT, getString(R.string.count_format, 0))
                describe(ID_CLEAR_HISTORY, getString(R.string.recent_cleared))
            }
        }
    }

    private companion object {
        const val ID_RECENT = 1L
        const val ID_MAPPINGS = 2L
        const val ID_CLEAR_HISTORY = 3L
    }
}

/** Recent titles (≤ 50, ≤ 30 days), newest first; those that already have a correction are marked. */
class RecentStep : BaseStep() {
    private var items: List<RecentTitle> = emptyList()
    private var mapped: Set<String> = emptySet()
    private var loaded = false

    override fun guidance() = Guidance(
        getString(R.string.recent_title),
        getString(R.string.recent_description),
        getString(R.string.recognition_title),
        null,
    )

    override fun buildActions(): List<GuidedAction> = when {
        !loaded -> listOf(item(ID_STATUS, getString(R.string.loading)))
        items.isEmpty() -> listOf(item(ID_STATUS, getString(R.string.recent_empty), getString(R.string.recent_empty_hint)))
        else -> items.mapIndexed { i, t ->
            val note = if (t.key in mapped) getString(R.string.recent_has_mapping) else null
            item(BASE + i, t.raw, listOfNotNull(t.iconHost, note).joinToString(" · ").ifEmpty { null }, next = true)
        }
    }

    override fun onResume() {
        super.onResume()
        background({ ServiceLocator.recent.all() to ServiceLocator.mappings.all().map { it.key }.toSet() }) { r ->
            r.getOrNull()?.let { (list, keys) ->
                items = list
                mapped = keys
            }
            loaded = true
            rebuild()
        }
    }

    override fun onClick(action: GuidedAction) {
        items.getOrNull((action.id - BASE).toInt())?.let { open(CandidatesStep.of(it)) }
    }

    private companion object {
        const val ID_STATUS = 1L
        const val BASE = 100L
    }
}

/**
 * Correction of a single title: the raw title, its key, other recent titles with the same key,
 * the current correction, TMDB search candidates (movie and tv in the language of the title's script), ID entry,
 * and the source app of its image host, for the badge.
 */
class CandidatesStep : BaseStep() {
    private val raw get() = requireArguments().getString(ARG_RAW).orEmpty()
    private val key get() = requireArguments().getString(ARG_KEY).orEmpty()
    private val cardType get() = requireArguments().getInt(ARG_TYPE, -1).takeIf { it >= 0 }
    private val host get() = requireArguments().getString(ARG_HOST)

    private var candidates: List<Candidate> = emptyList()
    private var scores: Map<Pair<MediaType, Int>, Double> = emptyMap()
    private var status: String? = null
    private var searchTask: Future<*>? = null

    override fun guidance() = Guidance(raw, getString(R.string.candidates_description), getString(R.string.recent_title), null)

    override fun buildActions(): List<GuidedAction> {
        val list = mutableListOf(
            item(ID_KEY, getString(R.string.candidates_key), key),
            item(ID_SAME_KEY, getString(R.string.candidates_same_key), getString(R.string.loading)),
            item(ID_CURRENT, getString(R.string.candidates_current), getString(R.string.loading)),
        )
        if (host != null) list += item(ID_SOURCE_APP, getString(R.string.source_app), getString(R.string.loading), next = true)
        if (candidates.isEmpty()) {
            list += item(ID_STATUS, status ?: getString(R.string.candidates_searching))
        } else {
            candidates.forEachIndexed { i, c -> list += item(BASE + i, candidateTitle(c), candidateDescription(c), next = true) }
        }
        list += edit(ID_TMDB_MOVIE, R.string.candidates_tmdb_movie, R.string.candidates_tmdb_hint, InputType.TYPE_CLASS_NUMBER)
        list += edit(ID_TMDB_TV, R.string.candidates_tmdb_tv, R.string.candidates_tmdb_hint, InputType.TYPE_CLASS_NUMBER)
        list += edit(ID_IMDB, R.string.candidates_imdb, R.string.candidates_imdb_hint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        list += item(ID_IGNORE, getString(R.string.candidates_ignore), getString(R.string.candidates_ignore_hint), next = true)
        list += item(ID_RESET, getString(R.string.candidates_reset))
        return list
    }

    private fun edit(id: Long, title: Int, hint: Int, inputType: Int): GuidedAction = GuidedAction.Builder(requireContext())
        .id(id)
        .title(getString(title))
        .description(getString(hint))
        .editTitle("")
        .editable(true)
        .editInputType(inputType)
        .multilineDescription(true)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        search()
    }

    /**
     * The search is canceled only together with the fragment: while "Confirm" is on top, this screen's view is destroyed,
     * but the result must still reach the list (rebuild/describe are safe without a view).
     */
    override fun onDestroy() {
        searchTask?.cancel(true)
        searchTask = null
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        refreshInfo()
    }

    private fun refreshInfo() {
        val k = key
        val title = raw
        background({
            val same = ServiceLocator.recent.all().filter { it.key == k && it.raw != title }.map { it.raw }.distinct()
            same to ServiceLocator.mappings.get(k)
        }) { r ->
            val (same, mapping) = r.getOrNull() ?: (emptyList<String>() to null)
            describe(ID_SAME_KEY, same.joinToString("\n").ifEmpty { getString(R.string.candidates_same_key_none) })
            describe(ID_CURRENT, mapping?.let { targetLabel(it) } ?: getString(R.string.candidates_current_none))
        }
        host?.let { h -> background({ SourceAppStep.describe(h) }) { r -> describe(ID_SOURCE_APP, sourceAppLabel(r.getOrNull())) } }
    }

    /**
     * Up to 2 languages × movie/tv; with the year from the title, then without it if nothing is found. Requests within
     * one pass run in parallel (result order is preserved); search runs in its own pool so it does not delay saving.
     */
    private fun search() {
        val title = raw
        val type = cardType
        searchTask = SettingsTasks.search(this, {
            val parsed = TitleParser.parse(title)
            val kind = SourceHints.classify(type, null).kind.let { if (it == CardKind.SKIP) CardKind.UNKNOWN else it }
            val tmdb = ServiceLocator.tmdb
            val found = mutableListOf<Candidate>()
            var error: TmdbError? = null
            if (parsed.primary.isNotBlank()) {
                for (year in if (parsed.year != null) listOf(parsed.year, null) else listOf(null)) {
                    val calls = SearchLanguages.forScript(parsed.script).flatMap { lang ->
                        listOf({ tmdb.searchMovie(parsed.primary, lang, year) }, { tmdb.searchTv(parsed.primary, lang, year) })
                    }
                    for (r in SettingsTasks.all(calls)) {
                        when (r) {
                            is TmdbResult.Ok -> found += r.value
                            is TmdbResult.Err -> error = error ?: r.error
                        }
                    }
                    if (found.isNotEmpty()) break
                }
            }
            TitleMatcher.match(parsed, kind, found).ranked.take(MAX_CANDIDATES) to error
        }) { r ->
            val (ranked, error) = r.getOrNull() ?: (emptyList<ScoredCandidate>() to null)
            status = when {
                ranked.isNotEmpty() -> null
                r.isFailure -> getString(R.string.candidates_error)
                error != null -> errorText(error)
                else -> getString(R.string.candidates_none)
            }
            showCandidates(ranked.map { it.candidate }, ranked.associate { (it.candidate.mediaType to it.candidate.id) to it.score })
        }
    }

    private fun showCandidates(list: List<Candidate>, scores: Map<Pair<MediaType, Int>, Double> = emptyMap()) {
        candidates = list.distinctBy { it.mediaType to it.id }
        this.scores = scores
        rebuild()
        refreshInfo()
    }

    private fun candidateTitle(c: Candidate) = c.year?.let { "${c.title} ($it)" } ?: c.title

    private fun candidateDescription(c: Candidate): String = listOfNotNull(
        typeLabel(c.mediaType),
        c.originalTitle.takeIf { it.isNotBlank() && it != c.title },
        scores[c.mediaType to c.id]?.let { getString(R.string.candidates_score_format, (it * 100).roundToInt()) },
        "TMDB ${c.id}",
    ).joinToString(" · ")

    override fun onGuidedActionEditedAndProceed(action: GuidedAction): Long {
        val input = action.editTitle?.toString()?.trim().orEmpty()
        action.editTitle = ""
        changed(action.id)
        when (action.id) {
            ID_TMDB_MOVIE, ID_TMDB_TV -> {
                val type = if (action.id == ID_TMDB_MOVIE) MediaType.MOVIE else MediaType.TV
                val id = input.toIntOrNull()?.takeIf { it > 0 }
                if (id == null) {
                    describe(action.id, getString(R.string.candidates_tmdb_invalid))
                } else {
                    describe(action.id, getString(R.string.candidates_checking))
                    val language = AppSettings(requireContext()).language().tmdb
                    SettingsTasks.search(this, { ServiceLocator.tmdb.details(type, id, language) }) { r ->
                        when (val d = r.getOrNull()) {
                            is TmdbResult.Ok -> {
                                describe(action.id, getString(R.string.candidates_tmdb_hint))
                                val year = d.value.date?.take(4)
                                val label = if (year.isNullOrBlank()) d.value.title else "${d.value.title} ($year)"
                                confirm(type to id, "$label · ${typeLabel(type)}")
                            }
                            is TmdbResult.Err -> describe(action.id, errorText(d.error))
                            null -> describe(action.id, getString(R.string.candidates_error))
                        }
                    }
                }
            }
            ID_IMDB -> {
                val imdb = input.lowercase().takeIf { IMDB_ID.matches(it) }
                if (imdb == null) {
                    describe(ID_IMDB, getString(R.string.candidates_imdb_invalid))
                } else {
                    describe(ID_IMDB, getString(R.string.candidates_checking))
                    val language = AppSettings(requireContext()).language().tmdb
                    SettingsTasks.search(this, { ServiceLocator.tmdb.findByImdb(imdb, language) }) { r ->
                        when (val f = r.getOrNull()) {
                            is TmdbResult.Ok -> {
                                describe(ID_IMDB, getString(R.string.candidates_imdb_hint))
                                if (f.value.isEmpty()) {
                                    describe(ID_IMDB, getString(R.string.error_not_found))
                                } else {
                                    showCandidates(f.value)
                                }
                            }
                            is TmdbResult.Err -> describe(ID_IMDB, errorText(f.error))
                            null -> describe(ID_IMDB, getString(R.string.candidates_error))
                        }
                    }
                }
            }
        }
        return GuidedAction.ACTION_ID_CURRENT
    }

    override fun onGuidedActionEditCanceled(action: GuidedAction) {
        action.editTitle = ""
        changed(action.id)
    }

    override fun onClick(action: GuidedAction) {
        when (action.id) {
            ID_SOURCE_APP -> host?.let { open(SourceAppStep.of(it)) }
            ID_IGNORE -> confirm(null, getString(R.string.mapping_ignored))
            ID_RESET -> {
                val k = key
                background({ ServiceLocator.mappings.remove(k) }) {
                    describe(ID_RESET, getString(R.string.candidates_reset_done))
                    refreshInfo()
                }
            }
            else -> candidates.getOrNull((action.id - BASE).toInt())?.let { c ->
                confirm(c.mediaType to c.id, "${candidateTitle(c)} · ${typeLabel(c.mediaType)}")
            }
        }
    }

    private fun confirm(target: Pair<MediaType, Int>?, label: String) {
        open(ConfirmMappingStep.of(ManualMapping(key, raw, target), label))
    }

    companion object {
        private const val ARG_RAW = "raw"
        private const val ARG_KEY = "key"
        private const val ARG_TYPE = "type"
        private const val ARG_HOST = "host"
        private const val ID_SOURCE_APP = 10L
        private const val ID_KEY = 1L
        private const val ID_SAME_KEY = 2L
        private const val ID_CURRENT = 3L
        private const val ID_STATUS = 4L
        private const val ID_TMDB_MOVIE = 5L
        private const val ID_TMDB_TV = 6L
        private const val ID_IMDB = 7L
        private const val ID_IGNORE = 8L
        private const val ID_RESET = 9L
        private const val BASE = 100L
        private const val MAX_CANDIDATES = 15
        private val IMDB_ID = Regex("^tt\\d{5,10}$")

        fun of(t: RecentTitle) = CandidatesStep().apply {
            arguments = Bundle().apply {
                putString(ARG_RAW, t.raw)
                putString(ARG_KEY, t.key)
                putInt(ARG_TYPE, t.type ?: -1)
                putString(ARG_HOST, t.iconHost)
            }
        }
    }
}

/** Correction confirmation: "Save" writes the mapping and returns to the title screen. */
class ConfirmMappingStep : BaseStep() {
    private val args get() = requireArguments()
    /** If saving finishes after onSaveInstanceState, popBackStack() would throw IllegalStateException, so the screen closes in onResume instead. */
    private var pendingClose = false

    private fun mapping(): ManualMapping {
        val type = MediaType.entries.firstOrNull { it.path == args.getString(ARG_TYPE) }
        val id = args.getInt(ARG_ID, 0)
        return ManualMapping(args.getString(ARG_KEY).orEmpty(), args.getString(ARG_SOURCE).orEmpty(), type?.let { it to id }?.takeIf { id > 0 })
    }

    override fun guidance() = Guidance(
        getString(R.string.confirm_title),
        getString(R.string.confirm_description, args.getString(ARG_SOURCE).orEmpty(), args.getString(ARG_LABEL).orEmpty()),
        args.getString(ARG_KEY).orEmpty(),
        null,
    )

    override fun buildActions(): List<GuidedAction> = listOf(
        item(ID_SAVE, getString(R.string.confirm_save)),
        item(ID_CANCEL, getString(R.string.confirm_cancel)),
    )

    override fun onResume() {
        super.onResume()
        if (pendingClose) close()
    }

    override fun onClick(action: GuidedAction) {
        when (action.id) {
            ID_SAVE -> {
                val m = mapping()
                background({ ServiceLocator.mappings.put(m) }) { r ->
                    if (r.isSuccess) close() else describe(ID_SAVE, getString(R.string.confirm_failed))
                }
            }
            ID_CANCEL -> close()
        }
    }

    private fun close() {
        if (!isAdded) return
        pendingClose = parentFragmentManager.isStateSaved
        if (!pendingClose) parentFragmentManager.popBackStack()
    }

    companion object {
        private const val ARG_KEY = "key"
        private const val ARG_SOURCE = "source"
        private const val ARG_TYPE = "type"
        private const val ARG_ID = "id"
        private const val ARG_LABEL = "label"
        private const val ID_SAVE = 1L
        private const val ID_CANCEL = 2L

        fun of(m: ManualMapping, label: String) = ConfirmMappingStep().apply {
            arguments = Bundle().apply {
                putString(ARG_KEY, m.key)
                putString(ARG_SOURCE, m.sourceTitle)
                putString(ARG_TYPE, m.target?.first?.path)
                putInt(ARG_ID, m.target?.second ?: 0)
                putString(ARG_LABEL, label)
            }
        }
    }
}

/** List of corrections; each has a "Delete" sub-action. */
class MappingsStep : BaseStep() {
    private var items: List<ManualMapping> = emptyList()
    private var loaded = false

    override fun guidance() = Guidance(
        getString(R.string.mappings_title),
        getString(R.string.mappings_description),
        getString(R.string.recognition_title),
        null,
    )

    override fun buildActions(): List<GuidedAction> = when {
        !loaded -> listOf(item(ID_STATUS, getString(R.string.loading)))
        items.isEmpty() -> listOf(item(ID_STATUS, getString(R.string.mappings_empty)))
        else -> items.mapIndexed { i, m ->
            GuidedAction.Builder(requireContext())
                .id(BASE + i)
                .title(m.sourceTitle.ifBlank { m.key })
                .description("${m.key} → ${targetLabel(m)}")
                .multilineDescription(true)
                .subActions(listOf(GuidedAction.Builder(requireContext()).id(DELETE_BASE + i).title(getString(R.string.mappings_delete)).build()))
                .build()
        }
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun reload() {
        background({ ServiceLocator.mappings.all() }) { r ->
            items = r.getOrDefault(emptyList())
            loaded = true
            rebuild()
        }
    }

    override fun onSubGuidedActionClicked(action: GuidedAction): Boolean {
        val m = items.getOrNull((action.id - DELETE_BASE).toInt()) ?: return true
        background({ ServiceLocator.mappings.remove(m.key) }) { reload() }
        return true
    }

    private companion object {
        const val ID_STATUS = 1L
        const val BASE = 100L
        const val DELETE_BASE = 100_000L
    }
}
