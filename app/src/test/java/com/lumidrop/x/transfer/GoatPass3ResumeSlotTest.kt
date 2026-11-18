package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * ══ GOAT-PASS 3 (§6) — the slot-reacquire contract ══════════════════════════
 *
 * Reproduces, at the ENGINE level, the exact reported queue-corruption
 * scenario:
 *
 *   "Pause items 1–5 one by one (each next item correctly starts), then
 *    resume the paused items — the five paused items can all start
 *    transferring simultaneously, violating the one-active-transfer rule."
 *
 * ROOT CAUSE (fixed in GOAT-PASS 3): Pass-19's pause-yield correctly hands a
 * paused stream's execution slot back to the queue — but [TransferManager
 * .awaitUnpaused]'s return path never RE-ACQUIRED it. Every paused-then-
 * resumed stream therefore resumed flowing PERMIT-FREE: N resumed items
 * streamed simultaneously beside the current slot holder, and the gate's
 * accounting drifted with each one.
 *
 * These tests pin the fixed contract:
 *   1. the pause-yield RECORDS the re-acquire debt on the row;
 *   2. a parked worker that resumes RE-ACQUIRES the slot through the very
 *      same gate — blocking behind the current holder (one-active-per-
 *      direction preserved), taking the next free permit ahead of later
 *      queued items (older startedAt, the explicit-resume priority), and
 *      releasing it normally on completion;
 *   3. gate arithmetic end balanced through the whole dance.
 *
 * Real-timing by necessity (the yield fires 750 ms after the pause; the
 * parked worker polls at 200 ms), exactly like ConsecutivePauseAndBatchTest.
 */
class GoatPass3ResumeSlotTest {

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
    }

    private val ip = "192.168.43.9"
    private val peer = "iPad 4 • Safari"

    /** Mirrors apiQSDl's real sequence for one download request (the same
     *  shape ConsecutivePauseAndBatchTest drives). */
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

    /** Runs [body] on a worker thread with a hard timeout — a wedged gate
     *  must surface as Refused, never a hung test. */
    private fun <T> runInBackground(timeoutMs: Long, body: () -> T, onTimeout: () -> T): T {
        val res = AtomicReference<T?>(null)
        val done = CountDownLatch(1)
        Thread {
            try { res.set(body()) } catch (e: Exception) { /* surfaced by the null below */ }
            finally { done.countDown() }
        }.apply { isDaemon = true }.start()
        return if (done.await(timeoutMs, TimeUnit.MILLISECONDS)) res.get() ?: onTimeout() else onTimeout()
    }

    private fun acquireInBackground(qsName: String, fileName: String, size: Long, timeoutMs: Long): RequestOutcome =
        runInBackground(timeoutMs, { simulateDownloadRequest(qsName, fileName, size) }) { RequestOutcome.Refused }

    /** Gate occupancy probe: registers a REAL waiter under a synthetic key
     *  (non-transfer keys sort LAST in the ordered admission — it can never
     *  steal a permit from a real resumed row), releases immediately if one
     *  was actually free. Returns null when the wait outlived [timeoutMs] —
     *  i.e. the gate was FULL for the whole probe. */
    private fun probeGateFreeWithin(timeoutMs: Long): Boolean? {
        val key = "gp3probe:${java.util.UUID.randomUUID()}"
        return runInBackground(timeoutMs, {
            val ok = TransferManager.acquireDownloadSlot(key)
            if (ok) TransferManager.releaseDownloadSlot(key)
            ok
        }) { null }
    }

    // ── 1: the yield records the debt the resume must pay back ──────────────

    @Test
    fun `pause yield records the re-acquire debt and frees the queue`() {
        assertNotNull(TransferManager.claimOrStartPending("d0.bin", 1000L, ip, peer,
            qsItemName = "d0.bin", filePath = "/sdcard/d0.bin"))
        val r0 = simulateDownloadRequest("d0.bin", "d0.bin", 1000L)
        assertTrue("item 0 must admit", r0 is RequestOutcome.Admitted)
        val t0 = (r0 as RequestOutcome.Admitted).tid
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(t0)!!.status)

        TransferManager.requestPause(t0)
        // Past the 750 ms grace the yield must have fired: the debt is on the
        // row, and the queue has its permit back (item 1 admits).
        Thread.sleep(1100)
        assertEquals("the pause-yield records exactly one re-acquire debt unit",
            1, TransferManager.get(t0)!!.pauseYieldedSlots.get())

        assertNotNull(TransferManager.claimOrStartPending("d1.bin", 1000L, ip, peer,
            qsItemName = "d1.bin", filePath = "/sdcard/d1.bin"))
        val r1 = simulateDownloadRequest("d1.bin", "d1.bin", 1000L)
        assertTrue("the next item must start while item 0 stays paused", r1 is RequestOutcome.Admitted)
        val t1 = (r1 as RequestOutcome.Admitted).tid

        // Unwind: a resumed-but-never-parked row's debt dies with its terminal
        // state — complete() zeroes it, so the gate never leaks a phantom
        // obligation.
        TransferManager.complete(t1); TransferManager.releaseDownloadSlot(t1)
        TransferManager.complete(t0)
        assertEquals("a terminal row owes the gates nothing",
            0, TransferManager.get(t0)!!.pauseYieldedSlots.get())
    }

    // ── 2: the resumed parked worker re-acquires through the queue ──────────

    @Test
    fun `resumed parked worker re-acquires its slot and serializes behind the holder`() {
        repeat(3) { i ->
            assertNotNull(TransferManager.claimOrStartPending("r$i.bin", 1000L, ip, peer,
                qsItemName = "r$i.bin", filePath = "/sdcard/r$i.bin"))
        }
        // Item 0 admits (holds the permit), then the host pauses it.
        val r0 = simulateDownloadRequest("r0.bin", "r0.bin", 1000L)
        val t0 = (r0 as RequestOutcome.Admitted).tid
        TransferManager.requestPause(t0)
        Thread.sleep(1100)   // yield fires: debt=1, permit free
        assertEquals(1, TransferManager.get(t0)!!.pauseYieldedSlots.get())

        // Item 1 takes the freed permit (the queue kept moving — the whole
        // point of the pause-yield).
        val r1 = simulateDownloadRequest("r1.bin", "r1.bin", 1000L)
        assertTrue("item 1 must start while item 0 is paused", r1 is RequestOutcome.Admitted)
        val t1 = (r1 as RequestOutcome.Admitted).tid

        // A WORKER PARKS on the paused row (exactly what ProgressTracking-
        // InputStream's read loop does server-side).
        val parkedDone = CountDownLatch(1)
        val parkedRef = AtomicReference<Exception?>(null)
        Thread {
            try { TransferManager.awaitUnpaused(t0) }
            catch (e: Exception) { parkedRef.set(e) }
            finally { parkedDone.countDown() }
        }.apply { isDaemon = true }.start()
        Thread.sleep(500)
        assertTrue("the worker must stay parked while the pause stands", parkedDone.count == 1L)

        // RESUME. The parked worker wakes, sees the debt, and must go BACK
        // THROUGH THE GATE — blocking behind item 1's permit rather than
        // streaming unslotted beside it.
        TransferManager.requestResume(t0)
        // Wait for the re-acquire to land (poll cadence 200ms + grant).
        val deadline = System.currentTimeMillis() + 5000
        while (TransferManager.get(t0)!!.pauseYieldedSlots.get() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertEquals("the resumed worker consumed the debt (it re-acquired the slot)",
            0, TransferManager.get(t0)!!.pauseYieldedSlots.get())

        // The re-acquired permit is REAL: with item 1 still streaming, the
        // gate must be FULL — a probe waiter must park, not pass.
        assertEquals("with item 1 active AND item 0's resumed slot re-acquired, the gate stays full",
            null, probeGateFreeWithin(1200))

        // Item 1 finishes and releases — the permit goes to the RESUMED row
        // (older startedAt, explicitly resumed), so the gate fills AGAIN:
        // no second active transfer may exist beside the resumed one.
        TransferManager.complete(t1); TransferManager.releaseDownloadSlot(t1)
        Thread.sleep(600)
        assertEquals("the resumed row holds the permit it re-acquired — still exactly one active download",
            null, probeGateFreeWithin(1200))

        // The parked worker completed its re-acquire and returned normally.
        assertTrue("the parked worker must return normally after its re-acquire",
            parkedDone.await(2, TimeUnit.SECONDS))
        assertEquals("no exception from the parked worker", null, parkedRef.get())

        // The resumed row finishes and releases — now item 2 finally admits,
        // and the whole dance ends with a balanced gate.
        TransferManager.complete(t0); TransferManager.releaseDownloadSlot(t0)
        val r2 = acquireInBackground("r2.bin", "r2.bin", 1000L, timeoutMs = 4000)
        assertTrue("item 2 must start once the resumed row completes", r2 is RequestOutcome.Admitted)
        (r2 as RequestOutcome.Admitted).let {
            TransferManager.complete(it.tid); TransferManager.releaseDownloadSlot(it.tid)
        }
        assertEquals("no live rows remain", 0, TransferManager.activeCount())
    }
}
