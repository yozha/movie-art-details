package io.github.yozha.movieartdetails.match

import io.github.yozha.movieartdetails.model.CardKind
import io.github.yozha.movieartdetails.model.SourceDecision

/**
 * Decides whether to search, and for what, from `ProgramCardFocused.type` and the `iconUri` host.
 * Host rules take precedence over the type field; an unknown host falls back to the type alone.
 * Supporting a new client means adding a line to [RULES]; the logic stays the same.
 */
object SourceHints {
    private enum class Action {
        /** Known host; the type decides. */
        BY_TYPE,
        /** Search for a movie/series even with type=4/5 (VLC marks files as CLIP). */
        SEARCH,
        SKIP,
    }

    private class HostRule(val source: String, val action: Action, val reason: String, val test: (scheme: String, host: String) -> Boolean)

    private val RULES = listOf(
        HostRule("vlc", Action.SEARCH, "VLC: file name") { scheme, host ->
            scheme == "content" && host.startsWith("org.videolan.vlc")
        },
        HostRule("youtube", Action.SKIP, "YouTube: video") { _, host ->
            host.isDomainOf("ytimg.com") || host.isDomainOf("ggpht.com")
        },
        HostRule("lan", Action.BY_TYPE, "LAN media server") { _, host -> isLanHost(host) },
    )

    private val URI_HEAD = Regex("""^([a-zA-Z][a-zA-Z0-9+.\-]*)://([^/?#]*)""")
    private val IPV4 = Regex("""^(\d{1,3})\.(\d{1,3})\.\d{1,3}\.\d{1,3}$""")

    fun classify(type: Int?, iconUri: String?): SourceDecision {
        val (scheme, host) = schemeAndHost(iconUri)
        val (kind, typeReason) = byType(type)
        val rule = RULES.firstOrNull { it.test(scheme, host) }
            ?: return SourceDecision(kind, "type", typeReason)
        return when (rule.action) {
            Action.SKIP -> SourceDecision(CardKind.SKIP, rule.source, rule.reason)
            Action.BY_TYPE -> SourceDecision(kind, rule.source, "${rule.reason}; $typeReason")
            Action.SEARCH -> {
                // A VLC Now Playing clip/event/"video" is a file: a movie or a series, type unknown.
                val searchKind = if (type == null || type == 4 || type == 5 || type == 106) CardKind.UNKNOWN else kind
                SourceDecision(searchKind, rule.source, "${rule.reason}; $typeReason")
            }
        }
    }

    /** Lowercase iconUri host (without port or userinfo), for diagnostics and RecentTitle.iconHost. */
    fun hostOf(iconUri: String?): String? = schemeAndHost(iconUri).second.ifEmpty { null }

    private fun byType(type: Int?): Pair<CardKind, String> {
        if (type == null) return CardKind.UNKNOWN to "type=null: movie or series"
        return when (type) {
            0 -> CardKind.MOVIE to "type=0: movie (low confidence)"
            1, 2, 3 -> CardKind.SERIES to "type=$type: series"
            4, 5 -> CardKind.SKIP to "type=$type: clip/event, no hints"
            in 6..12 -> CardKind.SKIP to "type=$type: channel/music/game"
            108 -> CardKind.MOVIE to "Now Playing: movie"
            109, 118 -> CardKind.SERIES to "Now Playing: series"
            else -> CardKind.SKIP to if (type >= 100) "Now Playing: neither movie nor series" else "type=$type: unknown"
        }
    }

    private fun schemeAndHost(uri: String?): Pair<String, String> {
        val m = uri?.trim()?.let { URI_HEAD.find(it) } ?: return "" to ""
        val authority = m.groupValues[2].substringAfterLast('@')
        val host = if (authority.startsWith('[')) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return m.groupValues[1].lowercase() to host.lowercase().trimEnd('.')
    }

    private fun String.isDomainOf(domain: String) = this == domain || endsWith(".$domain")

    private fun isLanHost(host: String): Boolean {
        if (host == "localhost" || listOf(".local", ".lan", ".home", ".internal", ".home.arpa").any { host.endsWith(it) }) return true
        if (host.startsWith("[")) return host.startsWith("[fd") || host.startsWith("[fe80") || host == "[::1]"
        val m = IPV4.find(host) ?: return false
        val a = m.groupValues[1].toInt()
        val b = m.groupValues[2].toInt()
        return a == 10 || a == 127 || a == 192 && b == 168 || a == 172 && b in 16..31 || a == 169 && b == 254
    }
}
