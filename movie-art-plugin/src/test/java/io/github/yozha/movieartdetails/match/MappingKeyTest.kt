package io.github.yozha.movieartdetails.match

import io.github.yozha.movieartdetails.model.CardKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MappingKeyTest {
    private fun key(raw: String, kind: CardKind) = MappingKey.of(TitleParser.parse(raw), kind)

    @Test
    fun yearAndClassMakeKeysDistinct() {
        assertNotEquals(key("Dune (1984)", CardKind.MOVIE), key("Dune (2021)", CardKind.MOVIE))
        assertEquals("dune|movie|1984", key("Dune (1984)", CardKind.MOVIE))
        assertNotEquals(key("Dune", CardKind.MOVIE), key("Dune (1984)", CardKind.MOVIE))
        assertNotEquals(key("Fargo", CardKind.SERIES), key("Fargo", CardKind.MOVIE))
        assertEquals("fargo|series|", key("Fargo", CardKind.SERIES))
        assertEquals("up|unknown|", key("Up", CardKind.UNKNOWN))
        assertEquals("up|unknown|", key("Up", CardKind.SKIP))
    }

    @Test
    fun technicalTagsAndEpisodesDoNotChangeKey() {
        assertEquals(key("The Bear", CardKind.UNKNOWN), key("The.Bear.S01E02.1080p.WEB-DL.mkv", CardKind.UNKNOWN))
        assertEquals(key("The Bear", CardKind.UNKNOWN), key("The Bear S01E03", CardKind.UNKNOWN))
        assertEquals(key("Ocean's Eleven", CardKind.MOVIE), key("OCEAN’S  ELEVEN", CardKind.MOVIE))
        assertEquals(key("Звездные войны", CardKind.MOVIE), key("Звёздные войны", CardKind.MOVIE))
        assertEquals(
            "alfred hitchcock's rear window|unknown|1954",
            key("Alfred Hitchcock's Rear Window 1954 1080p BluRay REMUX AVC FLAC 2.0 - SOMEGROUP", CardKind.UNKNOWN),
        )
    }
}
