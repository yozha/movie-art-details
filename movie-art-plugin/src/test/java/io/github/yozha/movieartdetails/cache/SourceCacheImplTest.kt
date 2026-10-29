package io.github.yozha.movieartdetails.cache

import io.github.yozha.movieartdetails.model.ImageInfo
import io.github.yozha.movieartdetails.model.MediaType
import io.github.yozha.movieartdetails.model.TitleDetails
import io.github.yozha.movieartdetails.model.Translation
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SourceCacheImplTest {

    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_800_000_000_000L
    private lateinit var root: File
    private lateinit var dir: File
    private lateinit var cache: SourceCacheImpl

    @Before
    fun setUp() {
        root = tmp.newFolder("files")
        dir = File(root, "source")
        cache = newCache()
    }

    private fun newCache() = SourceCacheImpl(dir) { now }

    private fun details(id: Int = 19995, type: MediaType = MediaType.MOVIE) = TitleDetails(
        mediaType = type, id = id, title = "Аватар", originalTitle = "Avatar", originalLanguage = "en",
        overview = "Synthetic ru-RU overview.", date = "2009-12-15", lastAirDate = null, status = "Released",
        runtimeMinutes = 162, numberOfSeasons = null, genres = listOf("фантастика", "приключения"),
        voteAverage = 7.6, voteCount = 31000, backdropPath = "/synthetic-backdrop.jpg",
        translations = listOf(Translation("ru", "RU", "Аватар", null), Translation("uk", "UA", null, "Synthetic uk-UA overview.")),
        logos = listOf(ImageInfo("/synthetic-logo.png", "ru", 500, 125, 5.3)),
        backdrops = listOf(ImageInfo("/synthetic-backdrop.jpg", null, 3840, 2160, 5.6)),
        imdbId = "tt0499549",
    )

    private fun detailsFiles() = File(dir, "details").listFiles().orEmpty().filter { it.name.endsWith(".json") }

    // ───────────── details ─────────────

    @Test
    fun detailsRoundTripAndLanguageIsPartOfKey() {
        val d = details()
        cache.putDetails(d, "ru-RU")
        assertEquals(d, cache.getDetails(MediaType.MOVIE, d.id, "ru-RU", allowStale = false))
        assertNull(cache.getDetails(MediaType.MOVIE, d.id, "en-US", allowStale = true))
        assertNull(cache.getDetails(MediaType.TV, d.id, "ru-RU", allowStale = true))
        // A new instance reads the same data from disk.
        assertEquals(d, newCache().getDetails(MediaType.MOVIE, d.id, "ru-RU", allowStale = false))
    }

    @Test
    fun detailsExpireAfter24hButStaleIsAvailable() {
        cache.putDetails(details(), "ru-RU")
        now += SourceCacheImpl.DETAILS_TTL_MS
        assertNotNull(cache.getDetails(MediaType.MOVIE, 19995, "ru-RU", allowStale = false))
        now += 1
        assertNull(cache.getDetails(MediaType.MOVIE, 19995, "ru-RU", allowStale = false))
        assertNotNull(cache.getDetails(MediaType.MOVIE, 19995, "ru-RU", allowStale = true))
        // An expired entry is not deleted: it is needed offline.
        assertEquals(1, detailsFiles().size)
    }

    @Test
    fun readingDoesNotExtendLifetime() {
        cache.putDetails(details(), "ru-RU")
        val mtime = detailsFiles().single().lastModified()
        now += SourceCacheImpl.DETAILS_TTL_MS - 1000
        assertNotNull(cache.getDetails(MediaType.MOVIE, 19995, "ru-RU", allowStale = false))
        assertEquals(mtime, detailsFiles().single().lastModified())
        now += 2000
        assertNull(cache.getDetails(MediaType.MOVIE, 19995, "ru-RU", allowStale = false))
    }

    @Test
    fun nothingOlderThanSixMonths() {
        cache.putDetails(details(), "ru-RU")
        now += SourceCacheImpl.TMDB_MAX_AGE_MS
        assertNotNull(cache.getDetails(MediaType.MOVIE, 19995, "ru-RU", allowStale = true))
        now += 1
        assertNull(cache.getDetails(MediaType.MOVIE, 19995, "ru-RU", allowStale = true))
        assertTrue(detailsFiles().isEmpty())
    }

    @Test
    fun purgeRemovesSixMonthOldDetailsWithoutReading() {
        cache.putDetails(details(1), "ru-RU")
        now += SourceCacheImpl.TMDB_MAX_AGE_MS + 1
        cache.putDetails(details(2), "ru-RU")
        cache.purgeExpired()
        assertEquals(listOf("movie_2_ru-RU.json"), detailsFiles().map { it.name })
    }

    @Test
    fun corruptedDetailsAreMissAndDeleted() {
        cache.putDetails(details(1), "ru-RU")
        cache.putDetails(details(2), "ru-RU")
        val files = detailsFiles().sortedBy { it.name }
        files[0].writeText("{\"v\":1,\"fetchedAt\":")
        files[1].writeBytes(byteArrayOf(0, -1, 7, 42))
        assertNull(cache.getDetails(MediaType.MOVIE, 1, "ru-RU", allowStale = true))
        assertNull(cache.getDetails(MediaType.MOVIE, 2, "ru-RU", allowStale = true))
        assertTrue(detailsFiles().isEmpty())
        // Writing works again after corruption.
        cache.putDetails(details(1), "ru-RU")
        assertNotNull(cache.getDetails(MediaType.MOVIE, 1, "ru-RU", allowStale = false))
    }

    @Test
    fun foreignRecordUnderOwnNameIsRejected() {
        cache.putDetails(details(1), "ru-RU")
        val file = detailsFiles().single()
        file.writeText(file.readText().replace("\"id\":1", "\"id\":2"))
        assertNull(cache.getDetails(MediaType.MOVIE, 1, "ru-RU", allowStale = true))
    }

    @Test
    fun concurrentPutAndGetOfSameDetails() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()
        repeat(8) { t ->
            pool.execute {
                start.await()
                repeat(50) {
                    try {
                        if (t % 2 == 0) cache.putDetails(details(), "ru-RU")
                        else cache.getDetails(MediaType.MOVIE, 19995, "ru-RU", allowStale = false)?.let {
                            if (it != details()) errors += AssertionError("partial read")
                        }
                    } catch (e: Throwable) {
                        errors += e
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))
        assertTrue(errors.toString(), errors.isEmpty())
        assertEquals(details(), cache.getDetails(MediaType.MOVIE, 19995, "ru-RU", allowStale = false))
        assertTrue(File(dir, "details").listFiles().orEmpty().none { it.name.endsWith(".tmp") })
    }

    // ───────────── images ─────────────

    @Test
    fun imagesKeyedByPathAndSizeAndExpireAfter30Days() {
        val bytes = byteArrayOf(1, 2, 3, 4)
        val file = cache.putImage("/synthetic-backdrop.jpg", "original", bytes)
        assertArrayEquals(bytes, cache.getImage("/synthetic-backdrop.jpg", "original")!!.readBytes())
        assertEquals(file, cache.getImage("/synthetic-backdrop.jpg", "original"))
        assertNull(cache.getImage("/synthetic-backdrop.jpg", "w1280"))
        assertNull(cache.getImage("/other.jpg", "original"))

        now += SourceCacheImpl.IMAGE_MAX_AGE_MS - 60_000
        assertNotNull(cache.getImage("/synthetic-backdrop.jpg", "original"))
        now += 120_000
        assertNull(cache.getImage("/synthetic-backdrop.jpg", "original"))
        assertFalse(file.exists())
    }

    @Test
    fun emptyImageFileIsMiss() {
        val file = cache.putImage("/a.png", "w500", byteArrayOf(9))
        file.writeBytes(ByteArray(0))
        assertNull(cache.getImage("/a.png", "w500"))
        assertFalse(file.exists())
    }

    @Test
    fun trimDeletesOldestImagesFirst() {
        val a = cache.putImage("/a.jpg", "original", ByteArray(1000))
        now += 60_000
        val b = cache.putImage("/b.jpg", "original", ByteArray(1000))
        now += 60_000
        val c = cache.putImage("/c.jpg", "original", ByteArray(1000))
        cache.trim(2500)
        assertFalse(a.exists())
        assertTrue(b.exists())
        assertTrue(c.exists())
        assertTrue(cache.sizeBytes() <= 2500)
    }

    // ───────────── matches ─────────────

    @Test
    fun negativeExpiresAfter30Minutes() {
        cache.putNegative("k")
        assertTrue(cache.isNegative("k"))
        assertFalse(cache.isNegative("other"))
        now += SourceCacheImpl.NEGATIVE_TTL_MS + 1
        assertFalse(cache.isNegative("k"))
    }

    @Test
    fun matchIsFreshFor24hAndReplacesNegative() {
        cache.putNegative("k")
        cache.putMatch("k", MediaType.TV, 1399)
        assertFalse(cache.isNegative("k"))
        assertEquals(MediaType.TV to 1399, cache.getMatch("k"))
        // Persisted on disk.
        assertEquals(MediaType.TV to 1399, newCache().getMatch("k"))
        now += SourceCacheImpl.MATCH_TTL_MS + 1
        assertNull(cache.getMatch("k"))
        assertNull(newCache().getMatch("k", allowStale = false))
    }

    @Test
    fun staleMatchIsKeptForOfflineUpTo6Months() {
        cache.putMatch("k", MediaType.MOVIE, 19995)
        now += 30L * 24 * 60 * 60 * 1000
        assertNull(cache.getMatch("k"))
        assertEquals(MediaType.MOVIE to 19995, cache.getMatch("k", allowStale = true))
        // Neither purging expired entries nor a process restart loses it.
        cache.purgeExpired()
        assertEquals(MediaType.MOVIE to 19995, newCache().getMatch("k", allowStale = true))

        // Writing it again (a details refresh) makes it fresh again.
        cache.putMatch("k", MediaType.MOVIE, 19995)
        assertEquals(MediaType.MOVIE to 19995, cache.getMatch("k"))

        now += SourceCacheImpl.MATCH_MAX_AGE_MS + 1
        assertNull(cache.getMatch("k", allowStale = true))
        cache.purgeExpired()
        assertNull(newCache().getMatch("k", allowStale = true))
    }

    @Test
    fun corruptedIndexIsEmpty() {
        cache.putMatch("k", MediaType.MOVIE, 1)
        File(dir, "matches.json").writeText("not json at all")
        val fresh = newCache()
        assertNull(fresh.getMatch("k"))
        assertFalse(fresh.isNegative("k"))
        fresh.putNegative("n")
        assertTrue(newCache().isNegative("n"))
    }

    // ───────────── size and cleanup ─────────────

    @Test
    fun clearTouchesOnlyOwnDir() {
        val foreign = File(root, "foreign.bin").apply { writeBytes(ByteArray(10)) }
        cache.putDetails(details(), "ru-RU")
        cache.putImage("/a.jpg", "original", ByteArray(100))
        cache.putMatch("k", MediaType.MOVIE, 1)
        assertTrue(cache.sizeBytes() > 100)

        cache.clear()
        assertEquals(0L, cache.sizeBytes())
        assertNull(cache.getDetails(MediaType.MOVIE, 19995, "ru-RU", allowStale = true))
        assertNull(cache.getImage("/a.jpg", "original"))
        assertNull(cache.getMatch("k"))
        assertNull(newCache().getMatch("k"))
        assertTrue(foreign.exists())
    }

    @Test
    fun leftoverTempFilesAreRemovedOnStart() {
        cache.putDetails(details(), "ru-RU")
        val leftover = File(File(dir, "details"), "movie_1_ru-RU.json.123.tmp").apply { writeText("{") }
        val fresh = newCache()
        assertNotNull(fresh.getDetails(MediaType.MOVIE, 19995, "ru-RU", allowStale = false))
        assertFalse(leftover.exists())
    }
}
