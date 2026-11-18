package com.lumidrop.x.transfer

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * ══ GOAT-PASS 2 — the bounded transfer pipeline ══════════════════════════════
 *
 * THE ROOT CAUSE THIS FILE FIXES
 * ------------------------------
 * Every byte-movement path in this engine used to run as ONE synchronous
 * loop: read a buffer from the socket, write that buffer to the file (or
 * read from the file, write to the socket), repeat. That serialization is
 * the mechanism behind the project's two long-standing throughput
 * complaints:
 *
 *   1. The 23–28 MB/s single-stream ceiling — while the loop is busy
 *      writing 256 KB to flash (or waiting out a read-latency spike on the
 *      send side), the TCP stream is idle. The link's airtime is only
 *      partially used: throughput ≈ 1 / (t_net + t_disk) instead of the
 *      min(bw_net, bw_disk) a pipeline achieves.
 *
 *   2. The SUDDEN drops to 5–6 MB/s — Android flash write latency is not
 *      smooth: journal commits, erase-block management and f2fs GC produce
 *      multi-tens-of-milliseconds stalls. During a 40 ms stall at 30 MB/s,
 *      ~1.2 MB queues in the kernel; the receive window (frequently capped
 *      near 512 KB by net.core.rmem_max on mid-range devices) fills, TCP
 *      advertises a zero window, the sender's congestion window collapses,
 *      and the stream spends the next seconds re-climbing through
 *      congestion avoidance — visible as a deep, sudden dip. Repeat every
 *      time storage hiccups.
 *
 * THE FIX, WITHIN THE STRICT CONCURRENCY CONTRACT
 * -----------------------------------------------
 * GOAT-PASS 2 pins transfer concurrency at EXACTLY 1 — one transfer, one
 * TCP stream, no parallel files, no parallel chunks. What is allowed (and
 * what this file is) is decoupling the STAGES of that one transfer: a
 * reader stage that owns the socket and never waits for storage, and a
 * writer stage that owns the file and never waits for the network,
 * connected by a BOUNDED queue of reusable buffers. Disk-latency bursts
 * are absorbed by the queue (the socket keeps draining; the window stays
 * open); sustained disk slowness degenerates gracefully into ordinary
 * TCP backpressure — the queue fills, the reader blocks, the sender
 * paces down. No unbounded buffering is possible by construction: the
 * queue length × buffer size is the hard, constant memory ceiling.
 *
 * [PipelinedFileWriter] is the receive side (socket → disk).
 * [ReadAheadInputStream] is the send side (disk → socket).
 *
 * INTEGRITY MODEL (the four levels the spec demands, kept separate)
 * ------------------------------------------------------------------
 *   1. Fast in-memory progress   — the READER's byte count ([readerBytes],
 *      what the caller feeds updateProgress with): smooth, immediate, and
 *      by design possibly a few buffers AHEAD of durability.
 *   2. Confirmed data reception  — bytes handed to the OS write() call
 *      (the writer thread's durable counter, only readable by the reader
 *      after it joins or drains the writer via finish()).
 *   3. Durable resume checkpoint — the staging file's on-disk length
 *      after the writer drains ([finish]'s return value, optionally
 *      trimmed to the declared total). ONLY this number ever backs a
 *      resume record.
 *   4. Final destination verification — unchanged, in the callers: the
 *      existing staging-length check + rename + registry flow.
 *
 * A crash between levels 1 and 3 simply means the resume offset is a few
 * buffers behind the displayed progress — the sender re-sends those
 * buffers, nothing is lost, nothing is corrupted, and completion is only
 * ever declared from level 3+ (finish() flushes every queued buffer
 * before returning). A disk write failure truncates the staging file back
 * to the durable prefix before rethrowing, so a resume record can never
 * point into a torn buffer.
 *
 * THREAD SAFETY
 * -------------
 * Exactly two threads touch an instance: the caller's reader thread
 * (borrow/commit/abandon/finish/abort) and the internal writer thread.
 * The queues provide the happens-before edges; the volatile/atomic fields
 * cover the terminal handshakes. Instances are NOT reusable after
 * finish()/abort().
 */
object PipelineIO {

    /** Buffer size for every pipeline stage — matches the engine's
     *  long-proven 256 KB chunk. One buffer = one socket read = one disk
     *  write; no partial sloshing between stages. */
    const val BUFFER_BYTES = 256 * 1024

    /** How many buffers may sit between the reader and the writer.
     *  8 × 256 KB = 2 MB of in-flight data per active transfer:
     *   - enough to absorb a ~60 ms flash stall at 40 MB/s without
     *     closing the TCP window;
     *   - small enough to be irrelevant on a 2013-era 512 MB-RAM device;
     *   - deliberately FIXED, not adaptive: the GOAT-PASS 2 spec forbids
     *     tuning that "constantly changes settings", and a queue depth
     *     that oscillated with measured latency would add exactly the
     *     kind of self-inflicted variance this pass removes. The same
     *     constant serves both directions and every route — the queue is
     *  per-active-transfer and there is exactly one active transfer. */
    const val QUEUE_DEPTH = 8

    /** Longest [PipelinedFileWriter.finish] will wait for the writer
     *  thread to drain after EOF. Generous by design: a full queue (2 MB)
     *  onto a slow-but-alive SD card can legitimately take a few seconds.
     *  If even this expires the writer is declared stalled and the caller
     *  gets an honest failure instead of a fabricated completion. */
    const val DRAIN_TIMEOUT_MS = 30_000L

    // Control markers sent through the data queue (never valid Slot / Chunk
    // payloads — they are their own types).
    private class EofMarker(val trimTo: Long)
    private object AbortMarker

    /** A pooled 256 KB buffer plus its valid byte count. */
    private class Slot(val buf: ByteArray) { var len: Int = 0 }

    // ═══════════════════════════════════════════════════════════════════
    //  RECEIVE SIDE — socket reader → disk writer
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Bounded, crash-honest pipelined file writer for one receive.
     *
     * The caller (the HTTP worker thread that owns the socket) runs:
     *
     * ```
     * val w = PipelinedFileWriter(staging, baseOffset) { threadTuning() }
     * try {
     *     while (remaining != 0L) {
     *         ...pause / cancel / generation checks (unchanged)...
     *         val buf = w.borrow()                  // backpressure point
     *         val n = input.read(buf, 0, toRead)
     *         if (n <= 0) { w.abandon(); break }
     *         w.commit(n)
     *         TransferManager.updateProgress(tid, baseOffset + w.readerBytes)
     *     }
     *     val durable = w.finish(trimTo = total)    // drains, verifies
     * } finally {
     *     w.abort()   // no-op after a successful finish()
     * }
     * ```
     *
     * Failure semantics: a disk write exception is captured by the writer
     * thread and rethrown to the reader at the NEXT [borrow]/[commit]/
     * [finish] — so the receive loop's existing catch paths (interrupted
     * vs cancelled vs failed) behave exactly as they did with the
     * synchronous loop, while the socket drain itself never waits on
     * storage.
     *
     * @param file     destination (the STAGING file — never a final name)
     * @param offset   starting write position; any existing prefix is
     *                 preserved and written over from exactly here
     *                 (RandomAccessFile seek — strictly safer than append
     *                 mode, which can never leave a gap behind a stale
     *                 longer tail).
     * @param onWriterThreadStart optional hook invoked INSIDE the writer
     *                 thread before its first take() — the Android call
     *                 site uses it to raise that thread's scheduling
     *                 priority for the duration of the transfer.
     */
    class PipelinedFileWriter(
        file: File,
        offset: Long,
        onWriterThreadStart: (() -> Unit)? = null,
        /** Test seam ONLY — production always passes null and the pipeline
         *  opens its own plain RandomAccessFile. The JVM suite injects a
         *  subclass that stalls/fails selected writes to prove the
         *  latency-absorption and failure-isolation contracts without
         *  needing a real broken disk. */
        rafFactory: ((File) -> RandomAccessFile)? = null,
    ) {
        private val startOffset: Long = offset
        private val free = LinkedBlockingDeque<Slot>()
        private val queued = ArrayBlockingQueue<Any>(QUEUE_DEPTH)
        private val writerThread: Thread
        private val raf: RandomAccessFile

        /** Durable bytes actually handed to write() — the ONLY number a
         *  resume checkpoint may trust. */
        private val durable = AtomicLong(0L)

        /** Bytes the READER accepted into the pipeline (in-memory
         *  progress level). Always ≥ [durableBytes]. */
        @Volatile var readerBytes: Long = offset
            private set

        @Volatile private var failure: IOException? = null
        @Volatile private var finished = false
        @Volatile private var aborted = false

        /** The slot currently lent out to the reader (reader-thread
         *  confined by the borrow→commit/abandon calling convention). */
        private var lent: Slot? = null

        init {
            repeat(QUEUE_DEPTH) { free.add(Slot(ByteArray(BUFFER_BYTES))) }
            raf = rafFactory?.invoke(file) ?: RandomAccessFile(file, "rw")
            // A fresh receive (offset 0) must start from a genuinely empty
            // file: a staging partial can survive a hard app crash with no
            // resume record pointing at it, and merely overwriting from 0
            // would leave its stale tail beyond the new data — poisoning
            // the next resume validation (staging.length() vs recorded
            // bytes) and any final length check. A real resume always
            // passes offset > 0 (both call sites validate that), so
            // offset == 0 unambiguously means "nothing durable is wanted
            // from whatever is there". One setLength(0), one syscall.
            if (offset == 0L) raf.setLength(0L)
            raf.seek(offset)
            writerThread = Thread({
                try { onWriterThreadStart?.invoke() } catch (e: Exception) { /* priority hint only */ }
                try {
                    drainLoop()
                } catch (t: Throwable) {
                    if (failure == null) failure = IOException("pipeline writer died: $t")
                } finally {
                    // A failed writer truncates the staging file back to
                    // the durable prefix so a resume record can never
                    // point into a half-written buffer.
                    if (failure != null) {
                        try { raf.setLength(startOffset + durable.get()) } catch (ignored: Exception) {}
                    }
                    try { raf.close() } catch (ignored: Exception) {}
                    // Catastrophe hardening (Error-class writer death): every
                    // queued slot is recycled so a reader blocked in borrow()
                    // wakes, gets a buffer, and has its next commit()/borrow()
                    // rethrow the latched failure — a reader can never be
                    // stranded on a dead producer. After this thread exits,
                    // nothing else touches the queues, so the drain is
                    // race-free.
                    while (true) {
                        val m = queued.poll() ?: break
                        if (m is Slot) { m.len = 0; free.addLast(m) }
                    }
                }
            }, "ldx-pipe-writer")
            writerThread.isDaemon = true
            writerThread.start()
        }

        /**
         * The writer's whole life: take a slot, write it, recycle it.
         * After the FIRST write failure the loop keeps DRAINING (nothing
         * more is written) so the reader can never deadlock against a
         * full queue — its next borrow()/commit() rethrows the failure.
         */
        private fun drainLoop() {
            val trimTo: Long
            while (true) {
                val m = queued.take()
                if (m is EofMarker) { trimTo = m.trimTo; break }
                if (m === AbortMarker) return
                m as Slot
                val n = m.len
                m.len = 0
                if (n > 0 && failure == null) {
                    try {
                        raf.write(m.buf, 0, n)
                        durable.addAndGet(n.toLong())
                    } catch (e: Exception) {
                        if (failure == null) failure = (e as? IOException) ?: IOException(e)
                    }
                }
                free.addLast(m)
            }
            // Clean end-of-stream: the caller may declare an exact final
            // length (the transfer's declared total) — trim any stale tail a
            // pre-existing longer partial could have left, so the finished
            // file is EXACTLY the bytes this transfer delivered.
            if (trimTo >= 0L && failure == null) {
                try { raf.setLength(trimTo) } catch (e: Exception) {
                    if (failure == null) failure = (e as? IOException) ?: IOException(e)
                }
            }
        }

        /** The durable byte count, including the preserved prefix. */
        val durableBytes: Long get() = startOffset + durable.get()

        /** Borrows a buffer to read the NEXT socket chunk into. Blocks
         *  only when all [QUEUE_DEPTH] buffers are in flight — the
         *  pipeline's backpressure point. Throws the captured disk
         *  failure (if any) instead of handing out a buffer the writer
         *  could not keep up with. */
        fun borrow(): ByteArray {
            rethrowFailure()
            check(lent == null) { "borrow() without commit()/abandon() of the previous buffer" }
            lent = free.takeFirst()   // blocks under backpressure
            return lent!!.buf
        }

        /** Hands the buffer just filled with [n] bytes to the writer
         *  thread. Never blocks: the slot was already reserved by
         *  [borrow] leaving the free pool. */
        fun commit(n: Int) {
            rethrowFailure()
            val s = lent ?: throw IllegalStateException("commit() without borrow()")
            require(n in 1..s.buf.size) { "commit length out of range: $n" }
            lent = null
            s.len = n
            readerBytes += n
            queued.put(s)
        }

        /** Returns a borrowed buffer without queuing it (a short/failed
         *  socket read). No bytes are counted. */
        fun abandon() {
            val s = lent ?: return
            lent = null
            s.len = 0
            free.addFirst(s)
        }

        /**
         * Normal end-of-stream: queues EOF, waits (bounded) for the writer
         * to drain every committed buffer, optionally trims the file to
         * exactly [trimTo] bytes (the declared total — kills any stale
         * tail a pre-existing longer partial might have left), and returns
         * the DURABLE byte count — the number the caller's
         * resume-registration and completion-verification logic must use.
         *
         * Throws the captured disk failure if the tail never made it to
         * storage, and an IOException if the writer failed to drain within
         * [DRAIN_TIMEOUT_MS] (the caller then treats the receive as a
         * server-side fault — never a fabricated completion, never a
         * resume record that could race a still-running writer).
         */
        fun finish(trimTo: Long = -1L): Long {
            if (finished) return durableBytes
            finished = true
            offerControlMarker(EofMarker(trimTo))
            writerThread.join(DRAIN_TIMEOUT_MS)
            if (writerThread.isAlive) {
                // Genuinely wedged disk writer. Interrupt (wakes a blocked
                // take, not a blocked write), give it one more bounded
                // second, then report the honest failure.
                writerThread.interrupt()
                writerThread.join(1_000L)
                if (writerThread.isAlive)
                    throw IOException("disk writer stalled without draining")
            }
            rethrowFailure()
            return durableBytes
        }

        /**
         * Abandonment (host cancel / superseded leg / any error the caller
         * does not want a checkpoint from): tells the writer to DROP every
         * queued-but-unwritten buffer and exit promptly. The staging file
         * keeps exactly its durable prefix. Never throws — cancellation
         * must not be blocked by a wedged writer (and the cancel paths
         * delete the staging file anyway).
         */
        fun abort() {
            if (finished || aborted) return
            aborted = true
            queued.clear()
            offerControlMarker(AbortMarker)
            writerThread.interrupt()
            try { writerThread.join(2_000L) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        /** Puts a control marker without the possibility of an unbounded
         *  block: a dead writer (Error path) leaves nobody to drain the
         *  queue, so a plain put() could hang finish()/abort() forever. */
        private fun offerControlMarker(marker: Any) {
            while (!queued.offer(marker, 200, TimeUnit.MILLISECONDS)) {
                if (!writerThread.isAlive) return
            }
        }

        private fun rethrowFailure() { failure?.let { throw it } }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  SEND SIDE — disk reader → socket writer
    // ═══════════════════════════════════════════════════════════════════

    /** Clean-EOF / error marker pushed through the full-chunk queue. */
    private object EofSentinel

    /**
     * Bounded read-ahead wrapper for a send-side source stream. One
     * dedicated prefetch thread keeps up to [QUEUE_DEPTH] buffers filled
     * ahead of the consumer, so the consumer (the socket-write loop)
     * never waits on a storage-latency spike — the TCP pipe stays
     * continuously fed, which is precisely what keeps the RECEIVER's
     * window open and prevents the congestion-collapse dips.
     *
     * Semantics preserved for every existing consumer:
     *  - `read()` blocks until data/EOF — identical contract to any
     *    blocking stream;
     *  - a source error is rethrown to the consumer at the position it
     *    happened (clean EOF still delivers every byte read before it);
     *  - `close()` stops the prefetch thread (bounded join, interrupt),
     *    closes the source, and discards prefetch state — SafeResponse's
     *    guaranteed close already funnels every abort path here;
     *  - `available()` reports only bytes in the chunk being served —
     *    never a stall, never a lie.
     *
     * @param onPrefetchThreadStart optional hook invoked inside the
     *        prefetch thread before its first read — the Android call
     *        site uses it to raise that thread's scheduling priority.
     */
    class ReadAheadInputStream(
        private val src: InputStream,
        onPrefetchThreadStart: (() -> Unit)? = null,
    ) : InputStream() {

        private class Chunk(val buf: ByteArray) { var len = 0 }

        private val free = LinkedBlockingDeque<Chunk>()
        private val full = ArrayBlockingQueue<Any>(QUEUE_DEPTH)
        private val prefetchThread: Thread

        @Volatile private var srcFailure: IOException? = null
        @Volatile private var sawEof = false
        @Volatile private var closing = false
        @Volatile private var closed = false

        /** Head chunk currently being served to the consumer
         *  (consumer-thread confined). */
        private var head: Chunk? = null
        private var headPos = 0

        init {
            repeat(QUEUE_DEPTH) { free.add(Chunk(ByteArray(BUFFER_BYTES))) }
            prefetchThread = Thread({
                try { onPrefetchThreadStart?.invoke() } catch (e: Exception) { /* priority hint only */ }
                try {
                    while (!closing) {
                        val c = free.pollFirst(50, TimeUnit.MILLISECONDS) ?: continue
                        try {
                            val n = src.read(c.buf)
                            if (n < 0) {
                                free.addFirst(c)
                                full.put(EofSentinel)
                                return@Thread
                            }
                            c.len = n
                            full.put(c)
                        } catch (e: IOException) {
                            if (closing) return@Thread
                            srcFailure = e
                            free.addFirst(c)
                            full.put(EofSentinel)
                            return@Thread
                        }
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                } catch (t: Throwable) {
                    // Catastrophe hardening (Error-class producer death): the
                    // consumer must never be left blocked on an empty queue
                    // with no producer. Latch a failure and push the sentinel
                    // (retry-offering while the consumer drains; exit on
                    // close).
                    if (!closing) {
                        srcFailure = srcFailure ?: IOException("read-ahead producer died: $t")
                        try {
                            while (!full.offer(EofSentinel, 50, TimeUnit.MILLISECONDS)) {
                                if (closing) break
                            }
                        } catch (ie: InterruptedException) {
                            Thread.currentThread().interrupt()
                        }
                    }
                }
            }, "ldx-pipe-prefetch")
            prefetchThread.isDaemon = true
            prefetchThread.start()
        }

        override fun read(): Int {
            if (closed) throw IOException("stream closed")
            val one = ByteArray(1)
            return if (read(one, 0, 1) == -1) -1 else (one[0].toInt() and 0xFF)
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (closed) throw IOException("stream closed")
            if (len == 0) return 0
            var h = head
            if (h == null || headPos >= h.len) {
                if (h != null) { h.len = 0; free.addFirst(h); head = null }
                h = nextChunk() ?: return -1
                head = h
                headPos = 0
            }
            val n = minOf(len, h.len - headPos)
            System.arraycopy(h.buf, headPos, b, off, n)
            headPos += n
            return n
        }

        /** Blocks for the next prefetched chunk; applies EOF/error
         *  semantics; returns null at clean end of stream. A closed stream
         *  never reaches here (read() refuses first) — after close the
         *  prefetch thread is dead and nothing will ever arrive, so this
         *  take() would otherwise hang a misbehaving consumer forever. */
        private fun nextChunk(): Chunk? {
            if (sawEof || closed) return null
            val m = full.take()
            if (m === EofSentinel) {
                sawEof = true
                srcFailure?.let { throw it }
                return null
            }
            return m as Chunk
        }

        override fun available(): Int {
            if (closed) return 0
            val h = head
            return if (h != null) (h.len - headPos).coerceAtLeast(0) else 0
        }

        override fun close() {
            if (closed) return
            closed = true
            closing = true
            prefetchThread.interrupt()
            try { prefetchThread.join(500L) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            try { src.close() } catch (e: IOException) { /* best-effort, as everywhere */ }
        }
    }
}
