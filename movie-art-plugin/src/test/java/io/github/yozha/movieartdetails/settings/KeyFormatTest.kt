package io.github.yozha.movieartdetails.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFormatTest {
    private val key = "0123456789abcdef0123456789ABCDEF"

    @Test
    fun acceptsExactly32HexCharacters() {
        assertTrue(KeyFormat.isValidFormat(key))
        assertTrue(KeyFormat.isValidFormat("  $key \n"))
        assertTrue(CredentialStore.isValidFormat(key))
    }

    @Test
    fun rejectsEverythingElse() {
        listOf(
            "", " ", key.dropLast(1), key + "0", key.replaceFirst('0', 'g'), "0123 4567 89ab cdef 0123 4567 89ab cdef",
            "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIwMTIzIn0.c2lnbmF0dXJl", key.replaceFirst('a', 'а'), // Cyrillic "а" homoglyph
        ).forEach {
            assertFalse("input: $it", KeyFormat.isValidFormat(it))
            assertFalse("input: $it", CredentialStore.isValidFormat(it))
        }
    }

    @Test
    fun normalizeTrimsAndLowercases() {
        assertEquals(key.lowercase(), KeyFormat.normalize(" $key "))
        assertNull(KeyFormat.normalize("abc"))
        assertNull(KeyFormat.normalize(null))
        assertNull(KeyFormat.normalize(""))
    }

    @Test
    fun readAccessTokensAreRecognizedButNeverNormalizedAsKeys() {
        val token = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIwMTIzIn0.c2lnbmF0dXJl"
        assertTrue(KeyFormat.isReadAccessToken(token))
        assertTrue(KeyFormat.isReadAccessToken(" $token\n"))
        assertNull(KeyFormat.normalize(token))
        assertFalse(KeyFormat.isReadAccessToken(key))
        assertFalse(KeyFormat.isReadAccessToken("eyJ.only-two"))
    }

    @Test
    fun maskShowsOnlyLastFour() {
        val masked = KeyFormat.mask(key.lowercase())
        assertTrue(masked.endsWith("cdef"))
        assertEquals(4, masked.count { it.isLetterOrDigit() })
        assertFalse(masked.contains("0123"))
    }
}
