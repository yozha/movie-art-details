package io.github.yozha.movieartdetails.settings

import io.github.yozha.movieartdetails.model.ManualMapping
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.RecentTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreCodecTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test
    fun mappingsRoundTripIncludingDoNotRecognize() {
        val items = listOf(
            ManualMapping("dune|movie|1984", "Dune (1984)", MediaType.MOVIE to 841),
            ManualMapping("fargo|series|", "Fargo", MediaType.TV to 60622),
            ManualMapping("4k uhd rainforest sounds|unknown|", "4K UHD Rainforest Sounds", null),
        )
        assertEquals(items, StoreCodec.decodeMappings(StoreCodec.encodeMappings(items)))
    }

    @Test
    fun corruptMappingsFileGivesEmptyListAndBadItemsAreSkipped() {
        listOf("", "{", "[]", "{\"v\":2,\"items\":[]}", "<html>").forEach { assertTrue(StoreCodec.decodeMappings(it).isEmpty()) }
        val text = """
            {"v":1,"items":[
              {"key":"","source":"x","type":"movie","id":1},
              {"key":"a|movie|","source":"A","type":"film","id":1},
              {"key":"b|movie|","source":"B","type":"movie","id":-3},
              {"key":"c|movie|","source":"C","type":"movie"},
              {"key":"d|movie|","source":"D","type":"movie","id":5,"extra":1},
              {"key":"d|movie|","source":"D2","type":"tv","id":6}
            ]}
        """.trimIndent()
        assertEquals(listOf(ManualMapping("d|movie|", "D", MediaType.MOVIE to 5)), StoreCodec.decodeMappings(text))
    }

    @Test
    fun recentKeepsAtMost50NewestWithin30Days() {
        val items = (0 until 80).map { RecentTitle("T$it", 0, null, "t$it|movie|", now - it * 1000L) } +
            RecentTitle("old", 0, null, "old|movie|", now - 31 * day)
        val pruned = StoreCodec.decodeRecent(StoreCodec.encodeRecent(items), now)
        assertEquals(StoreCodec.MAX_RECENT, pruned.size)
        assertEquals("T0", pruned.first().raw)
        assertTrue(pruned.none { it.raw == "old" })
    }

    @Test
    fun addRecentMovesExistingToFrontWithoutDuplicates() {
        val a = RecentTitle("Avatar", 0, "cdn.example.com", "avatar|movie|", now - day)
        val b = RecentTitle("Up", 0, "192.168.1.20", "up|movie|", now - 2 * day)
        val list = StoreCodec.addRecent(listOf(a, b), b.copy(lastSeenAt = now), now)
        assertEquals(listOf("Up", "Avatar"), list.map { it.raw })
        val long = StoreCodec.addRecent(list, RecentTitle("x".repeat(1000), 4, null, "x|unknown|", now), now)
        assertEquals(StoreCodec.MAX_RAW_CHARS, long.first().raw.length)
    }

    @Test
    fun futureTimestampsAreKept() {
        val t = RecentTitle("Future", 0, null, "future|movie|", now + day)
        assertEquals(listOf(t), StoreCodec.pruneRecent(listOf(t), now))
    }

    @Test
    fun sourceAppsRoundTrip() {
        val items = linkedMapOf("192.168.1.20" to "com.example.mediaclient", "i.ytimg.com" to "com.google.android.youtube.tv")
        assertEquals(items, StoreCodec.decodeSourceApps(StoreCodec.encodeSourceApps(items)))
    }

    @Test
    fun corruptSourceAppsFileGivesEmptyMapAndBadItemsAreSkipped() {
        listOf("", "{", "[]", "{\"v\":2,\"items\":[]}", "<html>").forEach { assertTrue(StoreCodec.decodeSourceApps(it).isEmpty()) }
        val text = """
            {"v":1,"items":[
              {"host":"","pkg":"com.example.a"},
              {"host":"bad host","pkg":"com.example.a"},
              {"host":"a/b","pkg":"com.example.a"},
              {"host":"cdn.example.com","pkg":"not a package"},
              {"host":"cdn.example.com","pkg":"single"},
              {"host":"cdn.example.com","pkg":"com.example.1app"},
              {"host":" CDN.Example.COM ","pkg":"com.example.app"},
              {"host":"cdn.example.com","pkg":"com.example.duplicate"},
              {"pkg":"com.example.nohost"}
            ]}
        """.trimIndent()
        assertEquals(mapOf("cdn.example.com" to "com.example.app"), StoreCodec.decodeSourceApps(text))
    }

    @Test
    fun hostAndPackageValidation() {
        assertEquals("i.ytimg.com", StoreCodec.normalizeHost(" I.YTIMG.com "))
        assertEquals("[fd00::1]", StoreCodec.normalizeHost("[FD00::1]"))
        listOf("", "  ", "a b", "a/b", "x".repeat(254)).forEach { assertEquals(null, StoreCodec.normalizeHost(it)) }
        listOf("com.example.app", "org.videolan.vlc", "a.b_c.D9").forEach { assertTrue(it, StoreCodec.isPackageName(it)) }
        listOf("", "app", "com..app", ".com.app", "com.app.", "com.1app", "com.example app").forEach {
            assertTrue(it, !StoreCodec.isPackageName(it))
        }
    }
}
