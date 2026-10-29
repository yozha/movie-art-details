package io.github.yozha.movieartdetails.settings

/** TMDB v3 API key format: 32 hex characters; surrounding whitespace is trimmed. */
object KeyFormat {
    private val V3_KEY = Regex("^[0-9a-fA-F]{32}$")
    private val READ_TOKEN = Regex("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+$")

    fun isValidFormat(raw: String): Boolean = V3_KEY.matches(raw.trim())

    /** TMDB API Read Access Token (a JWT). Accepted only as a build-time developer credential, never from the UI. */
    fun isReadAccessToken(raw: String): Boolean = READ_TOKEN.matches(raw.trim())

    /** The key in canonical form (lowercase), or null if the format is invalid. */
    fun normalize(raw: String?): String? = raw?.trim()?.takeIf(::isValidFormat)?.lowercase()

    /** Mask for the settings screen: only the last 4 characters are visible. */
    fun mask(key: String): String = "•".repeat(8) + key.takeLast(4)
}
