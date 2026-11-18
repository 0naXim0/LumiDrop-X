package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Pass-23 investigation suite — reproduces, at the ENGINE level, the exact
 * browser-transfer scenarios reported:
 *
 *  A. Five Quick-Share items, host pauses the in-flight item twice in a row:
 *     after each pause the next waiting item must start (the download gate
 *     must not stay wedged by the paused holder's permit).
 *  B. A 340-file batch driven exactly the way the real pipeline drives it
 *     (one-at-a-time: pre-register -> request -> gate -> claim -> stream ->
 *     complete -> release), including the never-requested sweep running
 *     opportunistically mid-batch: every file must complete, the gate must
 *     end balanced, and no row may be swept while legitimately queued.
 *  C. Interrupt + reconnect: an ACTIVE download whose page went away must
 *     latch reconnecting, resolve interrupted on page re-attach, and the
 *     re-request must reopen the SAME row at the byte offset.
 */
class ConsecutivePauseAndBatchTest {

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
    }

    private val ip = "192.168.43.77"
    private val peer = "Windows • Chrome"

    /** Mirrors apiQSDl's real sequence for one download request. */
    private fun simulateDownloadRequest(qsName: String, fileName: String, size: Long): RequestOutcome {
        // expireInFlightQS / item lookup omitted (server-side state, not engine)
        val pendingTid = TransferManager.findPendingQuickShareId(qsName, ip)
        if (pendingTid != null) TransferManager.markPendingRequested(pendingTid)
        val gateKey =
            if (TransferManager.hasActiveDownloadFor(qsName, ip)) null
            else pendingTid ?: "dlq:${java.util.UUID.randomUUID()}"
        if (gateKey != null && !TransferManager.acquireDownloadSlot(gateKey)) return RequestOutcome.Refused
        if (gateKey != null && TransferManager.isCancelAuthoritative(gateKey)) {
            TransferManager.releaseDownloadSlot(gateKey)
            return RequestOutcome.Refused
        }
        val tid = TransferManager.claimOrStartActiveQuickShare(qsName, ip, fileName, size, peer, "/sdcard/$fileName")
        if (gateKey != null) TransferManager.moveDownloadSlot(gateKey, tid)
        return RequestOutcome.Admitted(tid)
    }

    private sealed class RequestOutcome {
        object Refused : RequestOutcome()
        data class Admitted(val tid: String) : RequestOutcome()
    }

    // ── D: Pass-23 sweep semantics — peer-quiet + oldest-only ───────────────

    @Test
    fun `silent batch fails exactly the OLDEST never-requested row per sweep pass`() {
        val n = 30
        repeat(n) { i ->
            TransferManager.claimOrStartPending("w$i.dat", 500L, ip, peer,
                qsItemName = "w$i.dat", filePath = "/sdcard/w$i.dat")
        }
        // Age every row past the 25s never-requested window AND make the
        // peer quiet (no bytes/requests/completions within the window).
        for (i in 0 until n) ageRow("w$i.dat".let { TransferManager.findPendingQuickShareId(it, ip) ?: "" }, 30_000L)
        TransferManager.expireStalePending()
        val failed = TransferManager.activeSnapshot().count { it.status == TransferManager.Status.FAILED }
        assertEquals("a silent batch fails exactly ONE row per pass (the oldest — its dispatch turn came and was dropped)",
            1, failed)
        assertEquals("the rest of the tail keeps waiting its turn", n - 1,
            TransferManager.activeSnapshot().count { it.status == TransferManager.Status.PENDING })
        // A second pass ages out the next oldest, never a cluster. (The first
        // pass's own failure stamps finishedAt — peer activity — so a REAL
        // second pass waits out its own 25s window; aging every row's clocks
        // models that window having elapsed.)
        val firstFailedIds = TransferManager.activeSnapshot().filter { it.status == TransferManager.Status.FAILED }.map { it.id }.toSet()
        assertEquals(setOf(1), setOf(firstFailedIds.size))
        Thread.sleep(50)
        for (t in TransferManager.activeSnapshot()) ageRow(t.id, 30_000L)
        TransferManager.expireStalePending()
        val failedIds2 = TransferManager.activeSnapshot().filter { it.status == TransferManager.Status.FAILED }.map { it.id }.toSet()
        assertEquals("second pass: exactly one MORE row failed", 2, failedIds2.size)
        assertEquals("the first pass's row is still the only overlap", firstFailedIds, failedIds2.intersect(firstFailedIds))
    }

    @Test
    fun `clearPendingRequested makes a refused gate wait sweep-eligible again`() {
        val id = TransferManager.claimOrStartPending("c0.dat", 500L, ip, peer,
            qsItemName = "c0.dat", filePath = "/sdcard/c0.dat")!!
        TransferManager.markPendingRequested(id)
        // The stamped row is live work — the sweep leaves it alone even when
        // everything is quiet and old.
        ageRow(id, 60_000L)
        TransferManager.expireStalePending()
        assertEquals(TransferManager.Status.PENDING, TransferManager.get(id)!!.status)
        // The gate refused the request (Pass-23): the stamp clears and the
        // honest never-requested clock owns the row again.
        TransferManager.clearPendingRequested(id)
        TransferManager.expireStalePending()
        assertEquals(TransferManager.Status.FAILED, TransferManager.get(id)!!.status)
    }

    // ── A: consecutive pauses must not wedge the queue ──────────────────────

    @Test
    fun `consecutive pauses each start the next waiting item`() {
        val n = 5
        // Accept pre-registers 5 PENDING rows (claimOrStartPending).
        repeat(n) { i ->
            assertNotNull(TransferManager.claimOrStartPending("f$i.bin", 1000L, ip, peer,
                qsItemName = "f$i.bin", filePath = "/sdcard/f$i.bin"))
        }
        assertEquals(n, TransferManager.activeCount())

        // Item 0: request -> gate admit -> claim -> ACTIVE.
        val r0 = simulateDownloadRequest("f0.bin", "f0.bin", 1000L)
        val t0 = (r0 as RequestOutcome.Admitted).tid
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(t0)!!.status)

        // ── REAL TIMING: the page dispatches the next item the moment the pd
        // snapshot lands (~250ms), i.e. BEFORE the 750ms pause-yield fires —
        // the request PARKS in the gate and must be woken by the yield.
        // Pause item 0 from the host, then immediately park item 1's request.
        TransferManager.requestPause(t0)
        val r1 = acquireInBackground("f1.bin", "f1.bin", 1000L, timeoutMs = 6000)
        assertTrue("item 1 must start after pausing item 0 (yield must hand back the permit)", r1 is RequestOutcome.Admitted)
        val t1 = (r1 as RequestOutcome.Admitted).tid
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(t1)!!.status)
        assertEquals("paused row 0 stays ACTIVE+paused (in-place resume)",
            TransferManager.Status.ACTIVE, TransferManager.get(t0)!!.status)
        assertTrue(TransferManager.get(t0)!!.pauseRequested)

        // SECOND consecutive pause — the reported wedge. Same real timing.
        TransferManager.requestPause(t1)
        val r2 = acquireInBackground("f2.bin", "f2.bin", 1000L, timeoutMs = 6000)
        assertTrue("item 2 must start after pausing item 1 — consecutive pauses must each yield the permit",
            r2 is RequestOutcome.Admitted)

        // THIRD consecutive pause.
        val t2 = (r2 as RequestOutcome.Admitted).tid
        TransferManager.requestPause(t2)
        val r3 = acquireInBackground("f3.bin", "f3.bin", 1000L, timeoutMs = 6000)
        assertTrue("item 3 must start after pausing item 2", r3 is RequestOutcome.Admitted)

        // Gate must end balanced after everything unwinds.
        (r3 as RequestOutcome.Admitted).let { TransferManager.complete(it.tid); TransferManager.releaseDownloadSlot(it.tid) }
        TransferManager.complete(t2); TransferManager.releaseDownloadSlot(t2)
        TransferManager.complete(t1); TransferManager.releaseDownloadSlot(t1)
        TransferManager.complete(t0); TransferManager.releaseDownloadSlot(t0)
    }

    /** Runs simulateDownloadRequest on a worker thread with a hard timeout —
     *  a wedged gate must surface as a Refused/timeout, never a hung test. */
    private fun acquireInBackground(qsName: String, fileName: String, size: Long, timeoutMs: Long): RequestOutcome {
        val res = AtomicReference<RequestOutcome?>(null)
        val done = CountDownLatch(1)
        Thread {
            try {
                res.set(simulateDownloadRequest(qsName, fileName, size))
            } catch (e: Exception) {
                res.set(RequestOutcome.Refused)
            } finally { done.countDown() }
        }.apply { isDaemon = true }.start()
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) return RequestOutcome.Refused
        return res.get() ?: RequestOutcome.Refused
    }

    // ── B: 340-file batch, exactly the real one-at-a-time pipeline ──────────

    @Test
    fun `340 small files all complete with a balanced gate`() {
        val n = 340
        repeat(n) { i ->
            assertNotNull(TransferManager.claimOrStartPending("s$i.dat", 4500L, ip, peer,
                qsItemName = "s$i.dat", filePath = "/sdcard/s$i.dat"))
        }
        assertEquals(n, TransferManager.activeCount())

        var completed = 0
        var refused = 0
        for (i in 0 until n) {
            // The opportunistic sweeps run at row creation on the server.
            TransferManager.expireStalePending()
            val r = simulateDownloadRequest("s$i.dat", "s$i.dat", 4500L)
            if (r !is RequestOutcome.Admitted) { refused++; continue }
            TransferManager.addProgress(r.tid, 4500L)
            TransferManager.complete(r.tid)
            TransferManager.releaseDownloadSlot(r.tid)
            completed++
        }
        assertEquals("no byte request may be refused by the gate on a healthy batch", 0, refused)
        assertEquals("all 340 files complete", n, completed)
        assertEquals("no live rows remain", 0, TransferManager.activeCount())
        assertEquals("gate limit stays at the GOAT-PASS 2 fixed value of 1", 1, TransferManager.downloadConcurrencyLimit())
    }

    @Test
    fun `never-requested sweep does not fail legitimately queued rows while the batch moves`() {
        val n = 60
        repeat(n) { i ->
            TransferManager.claimOrStartPending("q$i.dat", 500L, ip, peer,
                qsItemName = "q$i.dat", filePath = "/sdcard/q$i.dat")
        }
        // Complete 3 files one at a time, running the sweep between each like
        // the server does at every row creation.
        for (i in 0 until 3) {
            TransferManager.expireStalePending()
            val r = simulateDownloadRequest("q$i.dat", "q$i.dat", 500L) as RequestOutcome.Admitted
            TransferManager.addProgress(r.tid, 500L)
            TransferManager.complete(r.tid)
            TransferManager.releaseDownloadSlot(r.tid)
        }
        // All remaining PENDING rows must still exist — a moving batch is live
        // work and never "never-requested" fodder.
        val pendingLeft = TransferManager.activeSnapshot().count { it.status == TransferManager.Status.PENDING }
        assertEquals("queued rows survive while the batch moves", n - 3, pendingLeft)
        // And the very next item's request is still admitted.
        val r3 = acquireInBackground("q3.dat", "q3.dat", 500L, timeoutMs = 3000)
        assertTrue(r3 is RequestOutcome.Admitted)
        // Pass-24 (test hygiene): release what the probe acquired — an
        // admitted probe left holding its permit was a stray that leaked into
        // whichever test class ran next once its worker thread outlived this
        // method (the cross-class "download gate has no free permit" flake).
        if (r3 is RequestOutcome.Admitted) {
            TransferManager.complete(r3.tid); TransferManager.releaseDownloadSlot(r3.tid)
        }
    }

    // ── C: interrupt + reconnect at the engine level ────────────────────────

    @Test
    fun `interrupted download latches reconnecting then resolves and reopens in place at offset`() {
        TransferManager.claimOrStartPending("big.bin", 1_000_000L, ip, peer,
            qsItemName = "big.bin", filePath = "/sdcard/big.bin")
        val r = simulateDownloadRequest("big.bin", "big.bin", 1_000_000L) as RequestOutcome.Admitted
        TransferManager.addProgress(r.tid, 300_000L)

        // Receiver's Wi-Fi drops: the stall watchdog probes and the page is
        // dead -> latch reconnecting (presentation) + free the permit.
        TransferManager.markReconnecting(r.tid)
        TransferManager.forceReleaseDownloadSlotForTest(r.tid)
        assertTrue(TransferManager.get(r.tid)!!.reconnecting)
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(r.tid)!!.status)

        // Age the row past the stall bound the way a real outage does (no
        // progress sample for >15s) — onPageReconnected only resolves rows
        // that are genuinely stalled, never one whose bytes are flowing.
        agePastStall(r.tid)

        // Page re-attaches: onPageReconnected resolves it interrupted NOW.
        TransferManager.onPageReconnected(ip)
        val failed = TransferManager.get(r.tid)!!
        assertEquals(TransferManager.Status.FAILED, failed.status)
        assertTrue("a network drop is interrupted, never cancelled", failed.interrupted)
        assertEquals("progress preserved for the resume", 300_000L, failed.transferredBytes)

        // The DM re-requests with Range: bytes=300000- — the ORIGINAL row
        // reopens in place, seeded at the offset.
        val r2 = TransferManager.claimOrStartActiveQuickShare("big.bin", ip, "big.bin", 1_000_000L, peer, "/sdcard/big.bin", rangeStart = 300_000L)
        assertEquals("resume re-opens the SAME row id", r.tid, r2)
        val resumed = TransferManager.get(r2)!!
        assertEquals(TransferManager.Status.ACTIVE, resumed.status)
        assertEquals("counter continues from the checkpoint", 300_000L, resumed.transferredBytes)
        assertTrue(resumed.resumedFromBytes > 0)
        assertFalse("reconnecting cleared on reopen", resumed.reconnecting)
    }
}

/** Test-only escape hatch so the watchdog path can be simulated without
 *  reaching into private state from the test body. */
fun TransferManager.forceReleaseDownloadSlotForTest(id: String) {
    // The real call site (sweepStalledActive) uses the private forceRelease;
    // the public releaseDownloadSlot is exactly-once per holder entry, which
    // is what the watchdog's force-release reduces to for a single hold.
    TransferManager.releaseDownloadSlot(id)
}

/** Ages a transfer's progress sample past the stall bound the way a real
 *  network outage does (bytes frozen >15s) — test-only, reflection on the
 *  @Volatile backing field. */
private fun agePastStall(id: String) {
    val t = TransferManager.get(id) ?: return
    val f = t.javaClass.getDeclaredField("lastSampleAtMs")
    f.isAccessible = true
    f.set(t, System.currentTimeMillis() - 60_000L)
}

/** Ages a row's creation clock, its sample clock AND its finish clock past
 *  the never-requested window while keeping the peer quiet — the state a
 *  real silent batch is in once its dispatch was swallowed (and the sweep's
 *  own 25s self-protection window has elapsed). */
private fun ageRow(id: String, ageMs: Long) {
    val t = TransferManager.get(id) ?: return
    val past = System.currentTimeMillis() - ageMs
    for (name in listOf("lastSampleAtMs", "startedAt", "finishedAt")) {
        try {
            val f = t.javaClass.getDeclaredField(name)
            f.isAccessible = true
            f.set(t, past)
        } catch (e: Exception) { /* field shape changed — fail loudly in the test body */ }
    }
}
