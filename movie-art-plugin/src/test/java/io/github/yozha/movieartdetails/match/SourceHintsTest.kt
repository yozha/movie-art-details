package io.github.yozha.movieartdetails.match

import io.github.yozha.movieartdetails.model.CardKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceHintsTest {
    private val vlc = "content://org.videolan.vlc.thumbprovider/thumb/1"

    @Test
    fun hostRulesBeatType() {
        assertEquals(CardKind.UNKNOWN, SourceHints.classify(4, vlc).kind)
        assertEquals("vlc", SourceHints.classify(4, vlc).source)
        assertEquals(CardKind.MOVIE, SourceHints.classify(0, vlc).kind)
        assertEquals(CardKind.SERIES, SourceHints.classify(3, "content://org.videolan.vlc.debug/x").kind)
        assertEquals(CardKind.SKIP, SourceHints.classify(0, "https://i.ytimg.com/vi/a/hq.jpg").kind)
        assertEquals(CardKind.SKIP, SourceHints.classify(4, "https://yt3.ggpht.com/a").kind)
        assertEquals("youtube", SourceHints.classify(4, "https://I.YTIMG.COM/vi/a").source)
        // A similar but unrelated host is not treated as YouTube.
        assertEquals(CardKind.MOVIE, SourceHints.classify(0, "https://notytimg.com/a").kind)
        // Not VLC: the scheme is http, not content.
        assertEquals(CardKind.SKIP, SourceHints.classify(4, "http://org.videolan.vlc/x").kind)
    }

    @Test
    fun typeTable() {
        assertEquals(CardKind.UNKNOWN, SourceHints.classify(null, null).kind)
        assertEquals(CardKind.MOVIE, SourceHints.classify(0, null).kind)
        for (t in 1..3) assertEquals(CardKind.SERIES, SourceHints.classify(t, null).kind)
        for (t in 4..12) assertEquals("type=$t", CardKind.SKIP, SourceHints.classify(t, null).kind)
        assertEquals(CardKind.MOVIE, SourceHints.classify(108, null).kind)
        assertEquals(CardKind.SERIES, SourceHints.classify(109, null).kind)
        assertEquals(CardKind.SERIES, SourceHints.classify(118, null).kind)
        for (t in listOf(100, 101, 106, 110, 13, 99, -1)) assertEquals("type=$t", CardKind.SKIP, SourceHints.classify(t, null).kind)
    }

    @Test
    fun knownClientsAreLabeled() {
        // A public image CDN with no host rule falls back to the type field alone.
        val cdn = SourceHints.classify(0, "https://cdn.example.com/poster/1.jpg")
        assertEquals(CardKind.MOVIE, cdn.kind)
        assertEquals("type", cdn.source)
        val wholphin = SourceHints.classify(3, "http://192.168.1.20:8096/Items/1/Images/Primary")
        assertEquals(CardKind.SERIES, wholphin.kind)
        assertEquals("lan", wholphin.source)
        assertEquals("lan", SourceHints.classify(0, "http://user:pw@192.168.1.5:32400/t").source)
        assertEquals("type", SourceHints.classify(0, "https://image.example.org/a").source)
        assertTrue(wholphin.reason.isNotEmpty())
    }

    @Test
    fun hostOf() {
        assertEquals("i.ytimg.com", SourceHints.hostOf("https://i.ytimg.com/vi/a/hq.jpg"))
        assertEquals("org.videolan.vlc.thumbprovider", SourceHints.hostOf(vlc))
        assertEquals("192.168.1.20", SourceHints.hostOf("http://192.168.1.20:8096/Items"))
        assertNull(SourceHints.hostOf(null))
        assertNull(SourceHints.hostOf("not a uri"))
        assertNull(SourceHints.hostOf("file:///sdcard/a.jpg"))
    }
}
