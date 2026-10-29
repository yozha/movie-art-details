package io.github.yozha.movieartdetails.core

import io.github.yozha.movieartdetails.model.TmdbError
import io.github.yozha.movieartdetails.model.TmdbErrorKind
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagnosticsLogTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun log(file: File = File(tmp.root, "diagnostics.log"), enabled: Boolean = true) =
        DiagnosticsLog(file, { enabled }, { 0L }, { it.run() })

    @Test
    fun `keeps only the last 50 events, newest first, and persists them`() {
        val file = File(tmp.root, "d.log")
        val d = log(file)
        repeat(60) { d.event("e$it") }
        val recent = d.recent()
        assertEquals(DiagnosticsLog.MAX_EVENTS, recent.size)
        assertTrue(recent.first().endsWith("e59"))
        assertTrue(recent.last().endsWith("e10"))
        assertEquals(recent, log(file).recent())
    }

    @Test
    fun `urls and keys never reach the log`() {
        val d = log()
        d.event("GET https://api.themoviedb.org/3/movie/1?api_key=0123456789abcdef0123456789abcdef failed")
        d.event("api_key=secret; raw 0123456789ABCDEF0123456789ABCDEF")
        d.event("Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIwMTIzIn0.c2lnbmF0dXJl")
        val text = d.recent().joinToString("\n")
        assertFalse("themoviedb" in text)
        assertFalse("secret" in text)
        assertFalse("0123456789" in text)
        assertFalse("eyJ" in text)
    }

    @Test
    fun `events are dropped when disabled but last error is kept`() {
        val d = log(enabled = false)
        d.event("hidden")
        d.lastError(TmdbError(TmdbErrorKind.SUSPENDED_KEY, 401, 10))
        assertTrue(d.recent().isEmpty())
        assertTrue(d.lastErrorText()!!.contains("SUSPENDED_KEY http=401 status=10"))
        d.lastError(null)
        assertNull(d.lastErrorText())
    }
}
