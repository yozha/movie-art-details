package io.github.yozha.movieartdetails.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CompositeStoreImplTest {

    @get:Rule val tmp = TemporaryFolder()

    /** Slack for age checks: generations within one clock instant are consecutive (gen, gen+1, …). */
    private val slack = 1000L

    private var now = 1_800_000_000_000L
    private lateinit var dir: File
    private lateinit var store: CompositeStoreImpl

    /** A "decodable" file in tests: starts with the JPEG SOI marker and ends with EOI. */
    private val validJpeg: (File) -> Boolean = { f ->
        val b = f.readBytes()
        b.size >= 4 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() &&
            b[b.size - 2] == 0xFF.toByte() && b[b.size - 1] == 0xD9.toByte()
    }

    @Before
    fun setUp() {
        dir = File(tmp.newFolder("files"), "wallpapers")
        store = newStore()
    }

    private fun newStore() = CompositeStoreImpl(dir, { now }, validJpeg)

    private fun jpeg(payload: Int, size: Int = 16): ByteArray =
        ByteArray(size).also {
            it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte()
            for (i in 2 until size - 2) it[i] = payload.toByte()
            it[size - 2] = 0xFF.toByte(); it[size - 1] = 0xD9.toByte()
        }

    private fun publish(key: String, payload: Int = 1, size: Int = 16): File =
        store.publish(key) { it.writeBytes(jpeg(payload, size)) }

    private fun names() = dir.listFiles().orEmpty().map { it.name }.toSet()

    @Test
    fun newResultAlwaysGetsNewNameAndFindReturnsNewest() {
        assertNull(store.find("k"))
        val a = publish("k", 1)
        val b = publish("k", 2)
        assertNotEquals(a.name, b.name)
        assertTrue(a.exists() && b.exists())
        assertEquals(b, store.find("k"))
        assertNull(store.find("other"))
        assertTrue(a.name.matches(Regex("[0-9a-f]{64}-\\d+\\.jpg")))
    }

    @Test
    fun namesStayUniqueAfterRestartEvenIfClockWentBack() {
        val a = publish("k")
        now -= 86_400_000
        val b = newStore().publish("k") { it.writeBytes(jpeg(2)) }
        assertNotEquals(a.name, b.name)
        assertEquals(b, newStore().find("k"))
    }

    @Test
    fun failedWriteOrVerificationLeavesNothing() {
        try {
            store.publish("k") { it.writeBytes(byteArrayOf(1, 2, 3)); throw IllegalStateException("render failed") }
            fail()
        } catch (e: IllegalStateException) {
            // expected
        }
        try {
            store.publish("k") { it.writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
            fail()
        } catch (e: IOException) {
            // failed the decodability check
        }
        try {
            store.publish("k") { }
            fail()
        } catch (e: IOException) {
            // empty output
        }
        assertTrue(names().isEmpty())
        assertNull(store.find("k"))
    }

    @Test
    fun concurrentPublishOfSameKey() {
        val threads = 8
        val perThread = 10
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val results = ConcurrentLinkedQueue<File>()
        val errors = ConcurrentLinkedQueue<Throwable>()
        repeat(threads) { t ->
            pool.execute {
                start.await()
                repeat(perThread) { i ->
                    try {
                        results += store.publish("same") { it.writeBytes(jpeg(t * perThread + i, 4096)) }
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
        assertEquals(threads * perThread, results.map { it.name }.toSet().size)
        assertTrue(results.all { it.isFile && it.length() == 4096L && validJpeg(it) })
        assertTrue(names().none { it.endsWith(".tmp") })
        assertEquals(results.maxByOrNull { it.name.substringAfter('-').removeSuffix(".jpg").toLong() }, store.find("same"))
    }

    @Test
    fun trimNeverDeletesServedFiles() {
        val files = (0 until 10).map { publish("k$it", it, 1000).also { now += 1000 } }
        files.take(6).forEach(store::markServed)
        now += CompositeStoreImpl.GRACE_MS + slack
        store.trim(0)
        assertEquals(files.take(6).map { it.name }.toSet(), names())
    }

    @Test
    fun servedRingKeepsOnlyLastSix() {
        val files = (0 until 8).map { publish("k$it", it, 1000) }
        files.forEach(store::markServed)
        now += CompositeStoreImpl.GRACE_MS + slack
        store.trim(0)
        assertEquals(files.takeLast(CompositeStoreImpl.SERVED_RING).map { it.name }.toSet(), names())
    }

    @Test
    fun trimIsLruByPublishTimeWithinLimit() {
        val a = publish("a", size = 1000); now += 1000
        val b = publish("b", size = 1000); now += 1000
        val c = publish("c", size = 1000)
        // Reads do not extend lifetime: find(a) does not save a.
        store.find("a")
        store.markServed(c)
        now += CompositeStoreImpl.GRACE_MS + slack
        store.trim(2000)
        assertFalse(a.exists())
        assertTrue(b.exists())
        assertTrue(c.exists())
    }

    @Test
    fun freshFilesSurviveTrimWithinGrace() {
        val a = publish("a", size = 1000)
        store.trim(0)
        assertTrue(a.exists())
    }

    @Test
    fun expiredAndSupersededAreRemovedButNotProtected() {
        val old = publish("old")
        val v1 = publish("k", 1)
        val v2 = publish("k", 2)
        val served = publish("served")
        store.markServed(served)
        now += CompositeStoreImpl.MAX_AGE_MS + slack
        assertNull(store.find("old"))
        assertNull(store.find("served"))
        val fresh = publish("fresh")
        store.trim(Long.MAX_VALUE)
        assertFalse(old.exists())
        assertFalse(v1.exists())
        assertFalse(v2.exists())
        assertTrue(served.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun supersededGenerationIsRemovedAfterGrace() {
        val v1 = publish("k", 1)
        val v2 = publish("k", 2)
        now += CompositeStoreImpl.GRACE_MS + slack
        store.trim(Long.MAX_VALUE)
        assertFalse(v1.exists())
        assertTrue(v2.exists())
        assertEquals(v2, store.find("k"))
    }

    @Test
    fun newestFilesAreProtectedAfterRestart() {
        val files = (0 until 8).map { publish("k$it", it, 1000).also { now += 1000 } }
        now += CompositeStoreImpl.GRACE_MS + slack
        val restarted = newStore()
        restarted.trim(0)
        assertEquals(files.takeLast(CompositeStoreImpl.SERVED_RING).map { it.name }.toSet(), names())
    }

    @Test
    fun clearKeepsServedAndForeignFiles() {
        val a = publish("a")
        val b = publish("b")
        store.markServed(b)
        val foreign = File(dir, "readme.txt").apply { writeText("x") }
        val leftover = File(dir, a.name.replace("-", "-9") + ".tmp").apply { writeText("x") }
        assertTrue(store.sizeBytes() > 0)
        store.clear()
        assertFalse(a.exists())
        assertTrue(b.exists())
        assertTrue(foreign.exists())
        assertFalse(leftover.exists())
        assertNull(store.find("a"))
        assertEquals(b, store.find("b"))
        assertEquals(b.length() + foreign.length(), store.sizeBytes())
    }

    @Test
    fun markServedIgnoresForeignFiles() {
        val files = (0 until 6).map { publish("k$it", it, 1000) }
        files.forEach(store::markServed)
        store.markServed(File(dir, "readme.txt"))
        now += CompositeStoreImpl.GRACE_MS + slack
        store.trim(0)
        assertEquals(files.map { it.name }.toSet(), names())
    }
}
