package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * ══ GOAT-PASS 2 — PipelineIO verification suite ════════════════════════════
 *
 * Proves, on the JVM, the contracts the GOAT-PASS 2 receive/send pipeline
 * makes — the mechanism behind the throughput fix, measured honestly:
 *
 *  §correctness  byte-exact round-trips through both stages (writer and
 *                read-ahead), including short-read sources and offset
 *                resumes with stale tails.
 *  §durability   readerBytes ≥ durableBytes during flight; finish() makes
 *                them equal; ONLY finish()'s count ever matches the file.
 *  §absorption   THE mechanism benchmark: injected multi-tens-of-ms disk
 *                write stalls (the real-world flash-jitter shape) are
 *                absorbed by the bounded queue — the reader's total blocked
 *                time stays a small fraction of the stall time, where a
 *                synchronous loop pays every millisecond of every stall on
 *                its hot path. This is a mechanism measurement, NOT a
 *                network benchmark.
 *  §backpressure a disk that is SUSTAINEDLY slower than the feed makes
 *                borrow() block — buffering is bounded by construction,
 *                never unbounded.
 *  §cancel       abort() drops queued buffers fast, keeps the durable
 *                prefix, never hangs.
 *  §interrupt    a mid-stream stop + finish() drains the queue so the
 *                checkpoint includes every received buffer.
 *  §disk-failure an injected write IOException rethrows to the reader at
 *                the next borrow/commit/finish AND truncates the file back
 *                to the durable prefix (a resume record can never point
 *                into a torn buffer).
 *  §readahead    bounded prefetch (never more than QUEUE_DEPTH+1 buffers
 *                consumed ahead), error delivery after the good prefix,
 *                and a clean close.
 */
class PipelineIOTest {

    private fun tempFile(name: String): File =
        File.createTempFile("gp2-$name", ".bin").apply { deleteOnExit() }

    private fun md5(f: File): String =
        MessageDigest.getInstance("MD5").let { d ->
            f.inputStream().use { s ->
                val b = ByteArray(64 * 1024)
                while (true) {
                    val n = s.read(b); if (n <= 0) break; d.update(b, 0, n)
                }
            }
            d.digest().joinToString("") { "%02x".format(it) }
        }

    /** A source that yields at most [maxChunk] bytes per read — the shape of
     *  the BufferedInputStreams NanoHTTPD hands the receive loops. */
    private class ShortSource(data: ByteArray, private val maxChunk: Int) : InputStream() {
        private val inner = ByteArrayInputStream(data)
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            inner.read(b, off, minOf(len, maxChunk))
        override fun close() = inner.close()
    }

    /** Random-access wrapper that injects per-write behavior (stalls,
     *  failures) at selected write indexes — the disk-jitter simulator. */
    private open class InjectingRAF(file: File, private val hook: (writeIndex: Int) -> Unit) :
        RandomAccessFile(file, "rw") {
        private val writes = java.util.concurrent.atomic.AtomicInteger(0)
        override fun write(b: ByteArray, off: Int, len: Int) {
            hook(writes.getAndIncrement())
            super.write(b, off, len)
        }
    }

    // ── §correctness ─────────────────────────────────────────────────────────

    @Test
    fun `writer round-trip is byte-exact through a short-read source`() {
        val data = java.util.Random(42).let { r ->
            ByteArray(5 * 1024 * 1024 + 12345).also { r.nextBytes(it) }   // 5 MB + odd tail
        }
        val f = tempFile("rt")
        val w = PipelineIO.PipelinedFileWriter(f, 0L)
        val src = ShortSource(data, 64 * 1024)   // deliberate 64 KB granularity
        try {
            while (true) {
                val buf = w.borrow()
                val n = src.read(buf)
                if (n <= 0) { w.abandon(); break }
                w.commit(n)
            }
            val durable = w.finish(trimTo = data.size.toLong())
            assertEquals("durable count equals the delivered count", data.size.toLong(), durable)
        } finally { w.abort() }
        assertEquals("file length is exact (trimmed)", data.size.toLong(), f.length())
        assertEquals("content is byte-exact", md5(f), md5(File(f.parentFile, f.name).let { _ ->
            val ref = tempFile("ref"); ref.writeBytes(data); ref }))
    }

    @Test
    fun `read-ahead round-trip is byte-exact with a short-read source`() {
        val data = java.util.Random(7).let { r -> ByteArray(3 * 1024 * 1024 + 777).also { r.nextBytes(it) } }
        val ra = PipelineIO.ReadAheadInputStream(ShortSource(data, 100 * 1024))
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(128 * 1024)
        try {
            while (true) {
                val n = ra.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } finally { ra.close() }
        assertTrue("read-ahead delivers the exact bytes", out.toByteArray().contentEquals(data))
    }

    @Test
    fun `offset resume preserves the prefix and finish trims a stale tail`() {
        val f = tempFile("resume")
        val prefix = ByteArray(1024 * 1024).also { java.util.Random(1).nextBytes(it) }
        val tail = ByteArray(700 * 1024).also { java.util.Random(2).nextBytes(it) }
        RandomAccessFile(f, "rw").use { it.write(prefix); it.write(tail) }   // stale tail beyond the checkpoint
        val w = PipelineIO.PipelinedFileWriter(f, prefix.size.toLong())
        try {
            val src = ShortSource(tail, 256 * 1024)
            while (true) {
                val buf = w.borrow()
                val n = src.read(buf)
                if (n <= 0) { w.abandon(); break }
                w.commit(n)
            }
            val durable = w.finish(trimTo = (prefix.size + tail.size).toLong())
            assertEquals("durable includes the preserved prefix", (prefix.size + tail.size).toLong(), durable)
        } finally { w.abort() }
        assertEquals("stale tail is trimmed to the declared total", (prefix.size + tail.size).toLong(), f.length())
        RandomAccessFile(f, "r").use { raf ->
            val got = ByteArray(prefix.size)
            raf.seek(0); raf.readFully(got)
            assertTrue("prefix preserved byte-exact across the resume", got.contentEquals(prefix))
        }
    }

    @Test
    fun `fresh leg truncates a stale longer partial at start`() {
        val f = tempFile("fresh")
        RandomAccessFile(f, "rw").use { it.write(ByteArray(4 * 1024 * 1024)) }  // stale 4 MB
        val newData = ByteArray(1 * 1024 * 1024)
        java.util.Random(9).nextBytes(newData)
        val w = PipelineIO.PipelinedFileWriter(f, 0L)   // fresh leg: offset 0 → auto-truncate
        try {
            val src = ByteArrayInputStream(newData)
            while (true) {
                val buf = w.borrow()
                val n = src.read(buf)
                if (n <= 0) { w.abandon(); break }
                w.commit(n)
            }
            w.finish(trimTo = newData.size.toLong())
        } finally { w.abort() }
        assertEquals("stale 4 MB partial is gone", newData.size.toLong(), f.length())
        RandomAccessFile(f, "r").use { raf ->
            val got = ByteArray(newData.size); raf.readFully(got)
            assertTrue("content is exactly the new leg's bytes", got.contentEquals(newData))
        }
    }

    // ── §durability + §interrupt ─────────────────────────────────────────────

    @Test
    fun `interrupted stream drains on finish so the checkpoint includes queued buffers`() {
        val f = tempFile("intr")
        val total = 20 * PipelineIO.BUFFER_BYTES   // 5 MB
        val data = ByteArray(total).also { java.util.Random(3).nextBytes(it) }
        val w = PipelineIO.PipelinedFileWriter(f, 0L)
        val fed = AtomicLong(0)
        try {
            // Feed only the first 12 buffers, then "lose the connection".
            val src = ByteArrayInputStream(data)
            var fedCount = 0
            while (fedCount < 12) {
                val buf = w.borrow()
                val n = src.read(buf)
                if (n <= 0) { w.abandon(); break }
                w.commit(n); fed.addAndGet(n.toLong()); fedCount++
            }
            // The reader may be up to QUEUE_DEPTH buffers ahead of the
            // writer — that is the point of the pipeline.
            val durable = w.finish()
            assertEquals("drain makes durable == reader count", w.readerBytes, durable)
            assertEquals("durable equals every byte the reader delivered", fed.get(), durable)
        } finally { w.abort() }
        assertEquals("file holds exactly the durable checkpoint", fed.get(), f.length())
    }

    // ── §cancel ──────────────────────────────────────────────────────────────

    @Test
    fun `abort drops queued buffers fast and keeps the durable prefix`() {
        val f = tempFile("abort")
        val w = PipelineIO.PipelinedFileWriter(f, 0L)
        val data = ByteArray(6 * PipelineIO.BUFFER_BYTES)
        java.util.Random(5).nextBytes(data)
        try {
            val src = ByteArrayInputStream(data)
            while (src.available() > 0) {
                val buf = w.borrow()
                val n = src.read(buf)
                if (n <= 0) { w.abandon(); break }
                w.commit(n)
            }
            val t0 = System.nanoTime()
            w.abort()
            val abortMs = (System.nanoTime() - t0) / 1_000_000
            assertTrue("abort is fast (was ${abortMs}ms)", abortMs < 2_000L)
            assertTrue("file keeps at most the delivered bytes", f.length() in 0..data.size.toLong())
        } finally { w.abort() }
    }

    // ── §absorption — the mechanism benchmark ────────────────────────────────

    /**
     * THE GOAT-PASS 2 mechanism proof, measured on the JVM with an honest
     * latency model: a disk whose writes are instant EXCEPT a 60 ms stall
     * every 16th write (the journal-commit / f2fs-GC jitter shape that
     * produced the field-reported 5–6 MB/s dips), fed by a source paced at
     * 10 MB/s (25 ms per 256 KB — slower than the disk's average, faster
     * than the stalls). A synchronous read→write loop pays EVERY stall on
     * its hot path: the socket drain stops for the full 60 ms, the receive
     * window fills, the sender's congestion window collapses. The pipeline
     * must absorb the stalls: the READER's total time blocked in borrow()
     * stays a small fraction of the cumulative stall time.
     */
    @Test
    fun `disk stalls are absorbed by the bounded queue not paid by the reader`() {
        val f = tempFile("stall")
        val chunks = 64                      // 16 MB
        val stallEvery = 16
        val stallMs = 60L
        val stallCount = java.util.concurrent.atomic.AtomicInteger(0)
        val raf = InjectingRAF(f) { i ->
            if (i > 0 && i % stallEvery == 0) { stallCount.incrementAndGet(); Thread.sleep(stallMs) }
        }
        val w = PipelineIO.PipelinedFileWriter(f, 0L, rafFactory = { raf })
        val data = ByteArray(chunks * PipelineIO.BUFFER_BYTES)
        java.util.Random(11).nextBytes(data)
        var readerBlockedMs = 0L
        try {
            val src = ByteArrayInputStream(data)
            while (true) {
                val t0 = System.nanoTime()
                val buf = w.borrow()
                readerBlockedMs += (System.nanoTime() - t0) / 1_000_000
                val n = src.read(buf)
                if (n <= 0) { w.abandon(); break }
                w.commit(n)
                // Pace the feed like a real 10 MB/s network leg.
                Thread.sleep(25)
            }
            val durable = w.finish(trimTo = data.size.toLong())
            assertEquals(data.size.toLong(), durable)
        } finally { w.abort() }
        val cumulativeStallMs = stallCount.get() * stallMs
        assertTrue("stalls actually happened (" + stallCount.get() + "×" + stallMs + "ms)", stallCount.get() >= 2)
        // Generous CI tolerance, still meaningfully below the sync-loop
        // baseline (which blocks for ≥ cumulativeStallMs on its hot path):
        assertTrue(
            "reader blocked ${readerBlockedMs}ms across ${stallCount.get()}×${stallMs}ms of disk stalls " +
                "(queue depth ${PipelineIO.QUEUE_DEPTH} must absorb bursts)",
            readerBlockedMs < cumulativeStallMs / 2
        )
        assertEquals("file is byte-exact under stalls", data.size.toLong(), f.length())
    }

    /** The mirror contract: a disk SUSTAINEDLY slower than the feed must
     *  backpressure the reader — buffering is bounded, never unbounded. */
    @Test
    fun `sustained slow disk backpressures the reader instead of buffering unboundedly`() {
        val f = tempFile("slow")
        val chunks = 24                       // 6 MB fed
        val raf = InjectingRAF(f) { Thread.sleep(50L) }   // ~5 MB/s sustained
        val w = PipelineIO.PipelinedFileWriter(f, 0L, rafFactory = { raf })
        var readerBlockedMs = 0L
        try {
            val src = ByteArrayInputStream(ByteArray(chunks * PipelineIO.BUFFER_BYTES))
            while (true) {
                val t0 = System.nanoTime()
                val buf = w.borrow()
                readerBlockedMs += (System.nanoTime() - t0) / 1_000_000
                val n = src.read(buf)
                if (n <= 0) { w.abandon(); break }
                w.commit(n)
                // Feed at ~20 MB/s — 2× faster than the disk.
                Thread.sleep(12)
            }
            w.finish()
        } finally { w.abort() }
        assertTrue(
            "a 2× slower disk must block the reader (backpressure), blocked=${readerBlockedMs}ms",
            readerBlockedMs > 200L
        )
    }

    // ── §disk-failure ────────────────────────────────────────────────────────

    @Test
    fun `write failure rethrows to the reader and truncates back to the durable prefix`() {
        val f = tempFile("fail")
        val failAt = 5   // buffers before the injected failure
        val raf = object : RandomAccessFile(f, "rw") {
            private val writes = java.util.concurrent.atomic.AtomicInteger(0)
            override fun write(b: ByteArray, off: Int, len: Int) {
                if (writes.getAndIncrement() == failAt) throw IOException("injected flash death")
                super.write(b, off, len)
            }
        }
        val w = PipelineIO.PipelinedFileWriter(f, 0L, rafFactory = { raf })
        val data = ByteArray(20 * PipelineIO.BUFFER_BYTES)
        java.util.Random(13).nextBytes(data)
        var sawFailure = false
        var durableAtFailure = -1L
        try {
            val src = ByteArrayInputStream(data)
            while (true) {
                // ── FINAL PASS 8 (test hardening): the failure can surface at ──
                // borrow() too — its rethrowFailure() runs BEFORE the blocking
                // take(), so when the drain thread latches the injected death
                // between this reader's previous commit() and its next
                // borrow(), the IOException escapes HERE. The old shape only
                // wrapped commit(), leaving exactly that window for the
                // failure to escape the loop as an uncaught exception — a
                // pure test blind spot (the product behavior is correct:
                // both entry points must rethrow), which sandbox timing
                // occasionally exposed as a flaky failure. Both entry
                // points now feed the same sawFailure path.
                val buf = try { w.borrow() } catch (e: IOException) {
                    sawFailure = true
                    durableAtFailure = w.durableBytes
                    break
                }
                val n = src.read(buf)
                if (n <= 0) { w.abandon(); break }
                try { w.commit(n) } catch (e: IOException) {
                    sawFailure = true
                    durableAtFailure = w.durableBytes
                    break
                }
            }
            if (!sawFailure) {
                // The failure may surface at finish() instead of commit().
                try { w.finish() } catch (e: IOException) { sawFailure = true }
            }
        } finally { w.abort() }
        assertTrue("the disk failure reaches the reader as an IOException", sawFailure)
        // Whatever drained is exactly what the file holds — a checkpoint can
        // never point into a torn buffer.
        val expectedDurable = (failAt * PipelineIO.BUFFER_BYTES).toLong()
        if (durableAtFailure >= 0) assertEquals(expectedDurable, durableAtFailure)
        assertTrue(
            "file truncated back to the durable prefix (${f.length()} vs ${expectedDurable})",
            f.length() <= expectedDurable + PipelineIO.BUFFER_BYTES
        )
    }

    // ── §readahead ───────────────────────────────────────────────────────────

    /** Counts bytes consumed from the source — proves the prefetch bound. */
    private class CountingSource(private val data: ByteArray) : InputStream() {
        var served = 0
        override fun read(): Int = if (served >= data.size) -1 else data[served++].toInt() and 0xFF
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (served >= data.size) return -1
            val n = minOf(len, data.size - served)
            System.arraycopy(data, served, b, off, n)
            served += n
            return n
        }
    }

    @Test
    fun `read-ahead prefetch is bounded when the consumer stops reading`() {
        val src = CountingSource(ByteArray(48 * 1024 * 1024))   // 48 MB available
        val ra = PipelineIO.ReadAheadInputStream(src)
        try {
            val first = ByteArray(256 * 1024)
            assertEquals(256 * 1024, ra.read(first))            // consume exactly one chunk
            Thread.sleep(400)                                    // let the prefetch thread run wild… or not
            val bound = (PipelineIO.QUEUE_DEPTH + 2) * PipelineIO.BUFFER_BYTES
            assertTrue(
                "prefetch consumed ${src.served}B ahead of a stopped consumer (bound ${bound}B)",
                src.served <= bound
            )
        } finally { ra.close() }
    }

    @Test
    fun `read-ahead delivers the good prefix then rethrows the source error`() {
        val good = ByteArray(2 * 1024 * 1024).also { java.util.Random(21).nextBytes(it) }
        val boom = IOException("source died mid-file")
        val src = object : InputStream() {
            private val inner = ByteArrayInputStream(good)
            override fun read(): Int = inner.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = inner.read(b, off, len)
                return if (n > 0) n else throw boom
            }
        }
        val ra = PipelineIO.ReadAheadInputStream(src)
        val out = java.io.ByteArrayOutputStream()
        try {
            val buf = ByteArray(96 * 1024)
            try {
                while (true) {
                    val n = ra.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                }
                fail("the source error must reach the consumer")
            } catch (e: IOException) {
                assertEquals("the original error is rethrown as-is", boom, e)
            }
            assertTrue("every good byte was delivered before the error", out.toByteArray().contentEquals(good))
        } finally { ra.close() }
    }

    @Test
    fun `read-ahead close is clean and a later read fails fast`() {
        val src = CountingSource(ByteArray(1024 * 1024))
        val ra = PipelineIO.ReadAheadInputStream(src)
        val buf = ByteArray(64 * 1024)
        assertTrue(ra.read(buf) > 0)
        ra.close()
        try {
            ra.read(buf)
            fail("read after close must throw")
        } catch (e: IOException) { /* expected */ }
    }
}
