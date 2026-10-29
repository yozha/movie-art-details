package io.github.yozha.movieartdetails.settings

import android.content.ContextWrapper
import io.github.yozha.movieartdetails.model.ManualMapping
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.RecentTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Stores on the JVM: Context is a stub that only provides noBackupFilesDir. */
class StoresTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun context(dir: File = tmp.root) = object : ContextWrapper(null) {
        override fun getNoBackupFilesDir(): File = dir
    }

    @Test
    fun mappingsSurviveNewInstanceAndRemove() {
        val first = MappingsStore(context())
        first.put(ManualMapping("dune|movie|1984", "Dune (1984)", MediaType.MOVIE to 841))
        first.put(ManualMapping("4k rainforest sounds|unknown|", "4K Rainforest Sounds", null))
        first.put(ManualMapping("dune|movie|1984", "Dune 1984", MediaType.MOVIE to 841)) // replaced by key

        val second = MappingsStore(context())
        assertEquals(2, second.all().size)
        assertEquals("Dune 1984", second.get("dune|movie|1984")?.sourceTitle)
        assertNull(second.get("4k rainforest sounds|unknown|")?.target)

        second.remove("dune|movie|1984")
        assertNull(MappingsStore(context()).get("dune|movie|1984"))
    }

    @Test
    fun corruptMappingsFileIsTreatedAsEmpty() {
        File(tmp.root, MappingsStore.FILE_NAME).writeText("{garbage")
        val store = MappingsStore(context())
        assertTrue(store.all().isEmpty())
        store.put(ManualMapping("a|movie|", "A", MediaType.TV to 1))
        assertEquals(1, MappingsStore(context()).all().size)
    }

    @Test
    fun recentDeduplicatesThrottlesWritesAndClears() {
        var now = 1_800_000_000_000L
        val store = RecentTitlesStore(context()) { now }
        val file = File(tmp.root, RecentTitlesStore.FILE_NAME)
        store.record(RecentTitle("Avatar", 0, "cdn.example.com", "avatar|movie|", now))
        assertTrue(file.isFile)
        val written = file.readText()

        now += 60_000 // repeated a minute later: updated in memory, not written to disk
        store.record(RecentTitle("Avatar", 0, "cdn.example.com", "avatar|movie|", now))
        assertEquals(written, file.readText())
        assertEquals(1, store.all().size)
        assertEquals(now, store.all().single().lastSeenAt)

        store.record(RecentTitle("  ", 0, null, "|movie|", now))
        assertEquals(1, store.all().size)

        store.clear()
        assertFalse(file.exists())
        assertTrue(RecentTitlesStore(context()) { now }.all().isEmpty())
    }

    @Test
    fun recentDropsEntriesOlderThan30DaysOnRead() {
        var now = 1_800_000_000_000L
        RecentTitlesStore(context()) { now }.record(RecentTitle("Up", 0, null, "up|movie|", now))
        now += 31L * 24 * 60 * 60 * 1000
        assertTrue(RecentTitlesStore(context()) { now }.all().isEmpty())
    }
}
