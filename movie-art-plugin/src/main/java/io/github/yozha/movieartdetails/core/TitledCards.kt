package io.github.yozha.movieartdetails.core

import io.github.yozha.movieartdetails.model.AppearanceSettings
import io.github.yozha.movieartdetails.model.CompositeStore
import io.github.yozha.movieartdetails.model.Diagnostics
import io.github.yozha.movieartdetails.model.SettingsRepository
import io.github.yozha.movieartdetails.model.WallpaperRenderer
import io.github.yozha.movieartdetails.render.BlockContent
import java.io.File

/**
 * Titled neutral frames: a card that gets no TMDB details, because it is skipped, excluded or not
 * recognized, or because it is an app card, is shown with the title Projectivy gave it and its source app's badge on
 * the neutral background, instead of a bare color. No TMDB requests.
 *
 * Frames are published to the level 2 store under [compositeKey]: title, badge (package and version), background
 * color, appearance fingerprint, renderer version and resolution, so a new frame always gets a new URI (a badge that
 * fails to load is left out of the key as well as the frame). A render
 * takes about 200 ms and runs one at a time with the TMDB renders ([renderLock]). Titles are never written to
 * diagnostics.
 */
class TitledCards(
    private val compositeStore: CompositeStore,
    private val renderer: WallpaperRenderer,
    private val settings: SettingsRepository,
    private val badges: AppBadges,
    private val diagnostics: Diagnostics,
    /** Shared with [CardPipeline]: one render at a time. */
    private val renderLock: Any = Any(),
    /** After a successful publish: trim caches to the limit. */
    private val afterPublish: () -> Unit = {},
) : NeutralResolver {

    private class Request(val title: String, val badge: BadgeRef?, val background: Int, val appearance: AppearanceSettings, val key: String)

    override fun quick(card: NeutralCard): Outcome {
        val r = request(card) ?: return Outcome.Rejected(reason(card))
        compositeStore.find(r.key)?.let { return Outcome.Ready(composite(it, r), "titled:L2") }
        return Outcome.Pending(r.key, "titled:pending")
    }

    override fun prepare(card: NeutralCard, ctx: JobContext): Outcome = try {
        val r = request(card)
        when {
            r == null -> Outcome.Rejected(reason(card))
            else -> compositeStore.find(r.key)?.let { Outcome.Ready(composite(it, r), "titled:L2") } ?: render(r, ctx)
        }
    } catch (e: OutOfMemoryError) {
        Outcome.Failed("titled:oom")
    } catch (e: Exception) {
        safeEvent("titled render failed: ${e.javaClass.simpleName}")
        Outcome.Failed("titled:error")
    }

    private fun render(r: Request, ctx: JobContext): Outcome {
        if (!ctx.isCurrent()) return Outcome.Failed(Outcome.STALE)
        // Decoded here, off the binder thread and outside renderLock. A badge that fails to load is left out of the frame,
        // which is then published under the key without a badge: the badge key stays free, so the next focus tries it again.
        val badge = r.badge?.let { runCatching { badges.load(it) }.getOrNull() }
        val key = if (r.badge != null && badge == null) {
            compositeKey(r.title, null, r.background, r.appearance, renderer.version).also { bare ->
                safeEvent("badge unavailable → frame without it")
                compositeStore.find(bare)?.let { return Outcome.Ready(composite(it, r), "titled:L2:nobadge") }
            }
        } else r.key
        val file = synchronized(renderLock) {
            // The card may have lost focus while another render held the lock.
            if (!ctx.isCurrent()) return Outcome.Failed(Outcome.STALE)
            compositeStore.publish(key) { out -> renderer.renderTitled(r.title, badge, r.background, r.appearance, out) }
        }
        runCatching(afterPublish)
        safeEvent("ready titled")
        return Outcome.Ready(composite(file, r), "titled")
    }

    /** null when the setting is off, there is no title or the color is not valid: a plain neutral color then. */
    private fun request(card: NeutralCard): Request? {
        if (!settings.showTitleWhenUnrecognized()) return null
        val title = BlockContent.collapse(card.title).take(BlockContent.MAX_TITLED_CHARS)
        if (title.isEmpty()) return null
        val background = parseColor(card.color) ?: return null
        val appearance = settings.appearance()
        val badge = if (!appearance.showAppBadge) {
            null
        } else {
            runCatching { if (card.packageName != null) badges.forPackage(card.packageName) else badges.forIconUri(card.iconUri) }.getOrNull()
        }
        return Request(title, badge, background, appearance, compositeKey(title, badge, background, appearance, renderer.version))
    }

    private fun reason(card: NeutralCard): String = when {
        !settings.showTitleWhenUnrecognized() -> OFF
        BlockContent.collapse(card.title).isEmpty() -> "titled:no-title"
        else -> "titled:bad-color"
    }

    private fun composite(file: File, r: Request) = Composite(file, r.title, sourceUrl = null, author = null)

    private fun safeEvent(line: String) {
        try {
            diagnostics.event(line)
        } catch (_: Throwable) {
        }
    }

    companion object {
        /** Rejected note: the setting is off, the reply is a plain neutral color. */
        const val OFF = "titled:off"

        /** Level 2 key of a titled frame. It starts with "titled|", so it never equals a TMDB composite or job key. */
        fun compositeKey(title: String, badge: BadgeRef?, background: Int, appearance: AppearanceSettings, rendererVersion: Int): String =
            "titled|$title|app:${badge?.key ?: "-"}|${Integer.toHexString(background)}|${appearance.fingerprint()}|r$rendererVersion|${CardPipeline.CANVAS}"

        /** "#RRGGBB" or "#AARRGGBB" as an opaque ARGB color; null for anything else. */
        fun parseColor(hex: String): Int? {
            val s = hex.trim()
            if (!COLOR.matches(s)) return null
            return (s.substring(1).toLong(16).toInt() and 0xFFFFFF) or OPAQUE
        }

        private const val OPAQUE = 0xFF000000.toInt()
        private val COLOR = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
    }
}
