package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Pass-24 suite — the engine-level regressions for this round's four fixes:
 *
 *  A. Pause→resume→re-pause INSIDE the 750ms grace window must still yield
 *     the permit (the old arming was silently consumed by the grace bail —
 *     the "second consecutive pause never starts the next item" wedge).
 *  B. A deliberate cancel hands its download permit back IMMEDIATELY (never
 *     waits on a stream-close callback that a blocked socket write can delay
 *     for minutes) — "the next item starts automatically, never a brief
 *     Failed".
 *  C. An ACTIVE row that delivered every byte but whose finalization was
 *     lost (leaked connection count) is rescued to COMPLETED by the stall
 *     watchdog once it has provably gone silent — "one stuck item at 100%
 *     must not block the entire batch" — while a PAUSED full row is never
 *     touched (a pause is the user's own state).
 *  D. A fresh registration for the same (qsItemName, peerIp) RETIRES a
 *     wedged zombie row and mints a fresh, independent PENDING row — the
 *     "re-sent 340 files, only 339 appeared" fix — while healthy live rows
 *     and deliberately paused rows are left exactly as they are.
 *  E. A completion failure caused by an aborted sibling (hasHole) resolves
 *     as an INTERRUPTION (resumable, re-openable in place), never a hard
 *     red Failed — the "briefly enters Failed during a normal cancel" fix.
 */
class Pass24FixesTest {

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
    }

    private val ip = "192.168.43.90"
    private val peer = "Windows • Edge"

    private fun simulateDownloadRequest(qsName: String, fileName: String, size: Long): RequestOutcome {
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

    private fun acquireInBackground(qsName: String, fileName: String, size: Long, timeoutMs: Long): RequestOutcome {
        val res = AtomicReference<RequestOutcome?>(null)
        val done = CountDownLatch(1)
        var worker: Thread? = null
        worker = Thread {
            try { res.set(simulateDownloadRequest(qsName, fileName, size)) }
            catch (e: Exception) { res.set(RequestOutcome.Refused) }
            finally { done.countDown() }
        }.apply { isDaemon = true }.also { it.start() }
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            // Hermetic tests: a timed-out worker would otherwise sit PARKED at
            // the gate (its own internal deadline is 60s+), and a permit freed
            // later could grant it AFTER this test ended — its unreleasable
            // hold then leaked into whichever test ran next (the classic
            // cross-class "download gate has no free permit" contamination).
            // Interrupting aborts the acquire cleanly; if the worker had
            // ALREADY been granted at the last moment, its real result is used
            // so the caller's cleanup releases the permit.
            try { worker?.interrupt() } catch (e: Exception) { /* already gone */ }
            if (done.await(2000, TimeUnit.MILLISECONDS)) return res.get() ?: RequestOutcome.Refused
            return RequestOutcome.Refused
        }
        return res.get() ?: RequestOutcome.Refused
    }

    // ── A: the pause→resume→re-pause grace hole ─────────────────────────────

    @Test
    fun `pause resume re-pause inside the grace window still yields the permit`() {
        repeat(3) { i ->
            assertNotNull(TransferManager.claimOrStartPending("g$i.bin", 1000L, ip, peer,
                qsItemName = "g$i.bin", filePath = "/sdcard/g$i.bin"))
        }
        val t0 = (simulateDownloadRequest("g0.bin", "g0.bin", 1000L) as RequestOutcome.Admitted).tid
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(t0)!!.status)

        // Pause, resume, and re-pause — ALL inside the 750ms grace window.
        // The first arming is still live when the re-pause re-stamps the
        // clock; the OLD code's fire-time grace bail consumed the arming and
        // wedged the queue behind the paused holder forever.
        TransferManager.requestPause(t0)
        TransferManager.requestResume(t0)
        TransferManager.requestPause(t0)
        assertTrue(TransferManager.get(t0)!!.pauseRequested)

        val r1 = acquireInBackground("g1.bin", "g1.bin", 1000L, timeoutMs = 6000)
        assertTrue("the re-paused holder must still hand its permit back (re-armed yield)",
            r1 is RequestOutcome.Admitted)

        // And a THIRD pause on the new holder keeps working — consecutive
        // pauses are each independently honored.
        val t1 = (r1 as RequestOutcome.Admitted).tid
        TransferManager.requestPause(t1)
        val r2 = acquireInBackground("g2.bin", "g2.bin", 1000L, timeoutMs = 6000)
        assertTrue("the next consecutive pause must also yield", r2 is RequestOutcome.Admitted)

        (r2 as RequestOutcome.Admitted).let { TransferManager.complete(it.tid); TransferManager.releaseDownloadSlot(it.tid) }
        TransferManager.complete(t1); TransferManager.releaseDownloadSlot(t1)
        TransferManager.complete(t0); TransferManager.releaseDownloadSlot(t0)
    }

    // ── B: cancel releases the permit immediately ───────────────────────────

    @Test
    fun `cancel hands the download permit back immediately`() {
        repeat(2) { i ->
            assertNotNull(TransferManager.claimOrStartPending("k$i.bin", 1000L, ip, peer,
                qsItemName = "k$i.bin", filePath = "/sdcard/k$i.bin"))
        }
        val t0 = (simulateDownloadRequest("k0.bin", "k0.bin", 1000L) as RequestOutcome.Admitted).tid
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(t0)!!.status)

        // The next item parks at the gate, then the host cancels item 0. The
        // permit must come back from requestCancel ITSELF — no stream-close
        // callback ever runs in this test (there is no stream), which is
        // exactly the point: the old code would have left the waiter parked
        // for its full 60s sliding window.
        val waiter = acquireInBackground("k1.bin", "k1.bin", 1000L, timeoutMs = 8000)
        // (the acquire races the cancel below; what matters is the end state)
        TransferManager.requestCancel(t0)
        assertEquals(TransferManager.Status.CANCELLED, TransferManager.get(t0)!!.status)

        // A fresh request must be admitted essentially instantly — the
        // cancelled row's permit was handed back synchronously.
        val t1 = acquireInBackground("k1.bin", "k1.bin", 1000L, timeoutMs = 2000)
        assertTrue("the next item must start immediately after a cancel (permit released synchronously)",
            t1 is RequestOutcome.Admitted || waiter is RequestOutcome.Admitted)
        if (t1 is RequestOutcome.Admitted) {
            TransferManager.complete(t1.tid); TransferManager.releaseDownloadSlot(t1.tid)
        }
        if (waiter is RequestOutcome.Admitted) {
            TransferManager.complete(waiter.tid); TransferManager.releaseDownloadSlot(waiter.tid)
        }
        assertEquals("gate limit stays at the GOAT-PASS 2 fixed value of 1", 1, TransferManager.downloadConcurrencyLimit())
    }

    // ── C: the delivered-everything watchdog rescue ──────────────────────────

    /** Invokes the private stall watchdog exactly the way its 5s scheduler
     *  does — no clock-aging games for the scheduler itself, only the row
     *  clocks, mirroring a real wedged finalization. */
    private fun runStallWatchdogOnce() {
        val m = TransferManager::class.java.getDeclaredMethod("sweepStalledActive")
        m.isAccessible = true
        m.invoke(TransferManager)
    }

    @Test
    fun `a wedged full row is rescued to completed and releases the queue`() {
        TransferManager.claimOrStartPending("full.bin", 4500L, ip, peer,
            qsItemName = "full.bin", filePath = "/sdcard/full.bin")
        val tid = (simulateDownloadRequest("full.bin", "full.bin", 4500L) as RequestOutcome.Admitted).tid
        TransferManager.addProgress(tid, 4500L)

        // A second "connection" attaches (the browser's retry racing the
        // teardown) and never reports in — its close callback is lost, so the
        // row's connection count leaks at 2 and the stream's own complete()
        // can never finalize it. The row sits ACTIVE at 100%.
        val tid2 = TransferManager.claimOrStartActiveQuickShare("full.bin", ip, "full.bin", 4500L, peer, "/sdcard/full.bin")
        assertEquals("the retry attaches to the same row", tid, tid2)
        TransferManager.complete(tid)   // decrements 2 -> 1: NOT finalized (the leak)
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(tid)!!.status)

        // Every byte delivered, then silence for far past the honest stall
        // window — the watchdog's delivered-everything rescue finalizes it.
        ageRow(tid, 60_000L)
        runStallWatchdogOnce()
        assertEquals("a full, silent, un-finalized row resolves Completed",
            TransferManager.Status.COMPLETED, TransferManager.get(tid)!!.status)

        // And its permit came back with it — a fresh request is admitted
        // immediately (the batch is no longer blocked behind the wedged row).
        TransferManager.claimOrStartPending("next.bin", 500L, ip, peer,
            qsItemName = "next.bin", filePath = "/sdcard/next.bin")
        val r = acquireInBackground("next.bin", "next.bin", 500L, timeoutMs = 2000)
        assertTrue("the queue advances after the rescue", r is RequestOutcome.Admitted)
        if (r is RequestOutcome.Admitted) { TransferManager.complete(r.tid); TransferManager.releaseDownloadSlot(r.tid) }
    }

    @Test
    fun `a paused full row is never rescued out from under its pause`() {
        TransferManager.claimOrStartPending("pf.bin", 4500L, ip, peer,
            qsItemName = "pf.bin", filePath = "/sdcard/pf.bin")
        val tid = (simulateDownloadRequest("pf.bin", "pf.bin", 4500L) as RequestOutcome.Admitted).tid
        TransferManager.addProgress(tid, 4500L)
        TransferManager.requestPause(tid)
        assertTrue(TransferManager.get(tid)!!.pauseRequested)

        ageRow(tid, 60_000L)
        runStallWatchdogOnce()
        val t = TransferManager.get(tid)!!
        assertEquals("a paused row keeps its honest ACTIVE state — a pause is the user's decision",
            TransferManager.Status.ACTIVE, t.status)
        assertTrue(t.pauseRequested)
    }

    // ── D: fresh registration retires wedged zombies ────────────────────────

    @Test
    fun `fresh send retires the wedged zombie and registers a fresh row`() {
        // Batch 1: the item wedges ACTIVE (leaked connection count at full
        // bytes — exactly the reported stuck item).
        TransferManager.claimOrStartPending("z0.dat", 4500L, ip, peer,
            qsItemName = "z0.dat", filePath = "/sdcard/z0.dat")
        val tid = (simulateDownloadRequest("z0.dat", "z0.dat", 4500L) as RequestOutcome.Admitted).tid
        TransferManager.addProgress(tid, 4500L)
        TransferManager.claimOrStartActiveQuickShare("z0.dat", ip, "z0.dat", 4500L, peer, "/sdcard/z0.dat")  // leaked sibling
        TransferManager.complete(tid)   // 2 -> 1, never finalizes
        ageRow(tid, 60_000L)
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(tid)!!.status)

        // Batch 2: the host re-sends the same 340 files. The fresh
        // registration must NOT be swallowed by the stuck row's identity.
        val fresh = TransferManager.claimOrStartPending("z0.dat", 4500L, ip, peer,
            qsItemName = "z0.dat", filePath = "/sdcard/z0.dat")
        assertNotNull("the re-sent item must get a fresh, independent row", fresh)
        assertNull("the wedged zombie row is retired out of the live map", TransferManager.get(tid))
        assertEquals(TransferManager.Status.PENDING, TransferManager.get(fresh!!)!!.status)

        // And the fresh row's request flows normally to completion.
        val r = simulateDownloadRequest("z0.dat", "z0.dat", 4500L) as RequestOutcome.Admitted
        assertEquals(fresh, r.tid)
        TransferManager.addProgress(r.tid, 4500L)
        TransferManager.complete(r.tid)
        TransferManager.releaseDownloadSlot(r.tid)
        assertEquals(TransferManager.Status.COMPLETED, TransferManager.get(r.tid)!!.status)
    }

    @Test
    fun `fresh send reuses a healthy live row and never touches a paused one`() {
        // Healthy: an ACTIVE row with fresh progress — the re-send reuses the
        // live entry (no duplicate row), exactly as before.
        TransferManager.claimOrStartPending("h0.dat", 4500L, ip, peer,
            qsItemName = "h0.dat", filePath = "/sdcard/h0.dat")
        val healthy = (simulateDownloadRequest("h0.dat", "h0.dat", 4500L) as RequestOutcome.Admitted).tid
        TransferManager.addProgress(healthy, 1000L)
        val reuse = TransferManager.claimOrStartPending("h0.dat", 4500L, ip, peer,
            qsItemName = "h0.dat", filePath = "/sdcard/h0.dat")
        assertNull("a healthy live row absorbs the re-send (no duplicate row)", reuse)
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(healthy)!!.status)

        // Paused: the user's own state — a re-send must not retire it and
        // must not mint a duplicate; the paused row keeps its identity,
        // progress and resume story. (h0 releases its permit first so p0's
        // own request can be admitted synchronously, exactly like the real
        // one-at-a-time pipeline.)
        TransferManager.complete(healthy); TransferManager.releaseDownloadSlot(healthy)
        TransferManager.claimOrStartPending("p0.dat", 4500L, ip, peer,
            qsItemName = "p0.dat", filePath = "/sdcard/p0.dat")
        val paused = (simulateDownloadRequest("p0.dat", "p0.dat", 4500L) as RequestOutcome.Admitted).tid
        TransferManager.addProgress(paused, 1500L)
        TransferManager.requestPause(paused)
        val pausedResend = TransferManager.claimOrStartPending("p0.dat", 4500L, ip, peer,
            qsItemName = "p0.dat", filePath = "/sdcard/p0.dat")
        assertNull("a deliberately paused row is never retired by a re-send", pausedResend)
        val t = TransferManager.get(paused)!!
        assertEquals(TransferManager.Status.ACTIVE, t.status)
        assertTrue(t.pauseRequested)
        assertEquals("paused progress preserved", 1500L, t.transferredBytes)

        TransferManager.complete(healthy); TransferManager.releaseDownloadSlot(healthy)
        TransferManager.complete(paused); TransferManager.releaseDownloadSlot(paused)
    }

    // ── E: the hasHole completion failure is an interruption ────────────────

    @Test
    fun `an aborted sibling makes the completion resolve interrupted and reopenable in place`() {
        TransferManager.claimOrStartPending("hole.bin", 1000L, ip, peer,
            qsItemName = "hole.bin", filePath = "/sdcard/hole.bin")
        val tid = (simulateDownloadRequest("hole.bin", "hole.bin", 1000L) as RequestOutcome.Admitted).tid
        // A second connection attaches (connection count 2).
        assertEquals(tid, TransferManager.claimOrStartActiveQuickShare("hole.bin", ip, "hole.bin", 1000L, peer, "/sdcard/hole.bin"))
        TransferManager.addProgress(tid, 400L)

        // The sibling connection aborts mid-span (the cancelled previous
        // item's teardown landing on the shared row): hasHole latches, and
        // the surviving connection's completion must resolve INTERRUPTED —
        // never a hard Failed — because the delivery is missing a span.
        TransferManager.downloadConnectionAborted(tid)
        TransferManager.complete(tid)
        val failed = TransferManager.get(tid)!!
        assertEquals(TransferManager.Status.FAILED, failed.status)
        assertTrue("a hole is an interruption, not a hard failure", failed.interrupted)
        assertFalse(failed.cancelRequested)

        // The download manager's Range re-request re-opens the SAME row in
        // place from the byte offset — the in-place resume the interrupted
        // presentation promises (impossible when the flag was missing).
        val reopened = TransferManager.claimOrStartActiveQuickShare("hole.bin", ip, "hole.bin", 1000L, peer, "/sdcard/hole.bin", rangeStart = 400L)
        assertEquals("the interrupted row re-opens in place", tid, reopened)
        val resumed = TransferManager.get(reopened)!!
        assertEquals(TransferManager.Status.ACTIVE, resumed.status)
        assertEquals("counter continues from the checkpoint", 400L, resumed.transferredBytes)
        TransferManager.complete(reopened); TransferManager.releaseDownloadSlot(reopened)
    }
}

/** Ages a row's clocks past a window (test-only reflection on the @Volatile
 *  backing fields — same discipline as the Pass-23 suite next door). */
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
