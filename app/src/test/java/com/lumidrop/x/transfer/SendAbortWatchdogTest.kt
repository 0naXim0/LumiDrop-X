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
import java.util.concurrent.atomic.AtomicInteger

/**
 * ══ GOAT-PASS 4 (§4) — the send-abort watchdog contract ════════════════════
 *
 * Pins the decision table that closes the two-pause queue freeze:
 *
 *   "Pause item 1 → item 2 starts. Pause item 2 → item 3 sometimes does
 *    not start."
 *
 * ROOT CAUSE (fixed in GOAT-PASS 4): NativePushClient.streamFile checks
 * pause/cancel only at the copy loop's top, so a write wedged inside the
 * TCP window (the receiver parked after mirroring the pause) never
 * returns — the dispatch permit is held forever and the next Waiting item
 * never starts. [SendAbortWatchdog] reconciles every live send connection
 * with its row's engine state and disconnects the wedged connection the
 * moment the two diverge; streamFile classifies the abort by its cause
 * (PAUSE parks the file exactly like a noticed pause).
 *
 * These tests pin the DECISION TABLE at the engine level (the full
 * NativePushClient coroutine needs Android and is verified by source
 * review — see GOAT-PASS-4.md §verification for the honest split):
 *
 *   1. a healthy ACTIVE row's connection is never touched;
 *   2. a pause younger than the grace is left alone (blips keep the
 *      in-place resume);
 *   3. a pause past the grace aborts with PAUSE — exactly once — and the
 *      row stays ACTIVE+paused (parkable, checkpoint preserved);
 *   4. a deliberate cancel aborts with CANCEL;
 *   5. a row the engine already resolved (interrupted self-heal, stall
 *      cap, completion) aborts with STATE;
 *   6. an orphaned row (dismissed underneath the leg) aborts with STATE;
 *   7. the cause is consumed exactly once;
 *   8. a retry leg that replaces a finishing one keeps its own
 *      registration (the predecessor's late close is identity-guarded);
 *   9. the full two-pause sequence: pause #1 and pause #2 each produce a
 *      PAUSE abort for their wedged legs, so BOTH dispatch permits come
 *      back and the queue keeps moving.
 */
class SendAbortWatchdogTest {

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
    }

    private val ip = "192.168.42.9"
    private val peer = "Pixel • LumiDrop"

    /** Creates a row exactly the way sendOneFile's claim chain does for a
     *  native send, and registers a counting "connection" for it. */
    private fun leg(fileName: String): Pair<String, SendAbortWatchdog.Handle> {
        val tid = TransferManager.start(
            TransferManager.Direction.SEND, fileName, 10_000L, ip, peer,
            source = TransferManager.Source.NATIVE_PUSH, filePath = "/sdcard/$fileName")
        var disconnects = 0
        val latch = CountDownLatch(1)
        val handle = SendAbortWatchdog.register(tid) { disconnects++; latch.countDown() }
        return tid to handle
    }

    /** Ages a row's pause stamp past the watchdog's grace. */
    private fun agePause(id: String, ageMs: Long) {
        val t = TransferManager.get(id)!!
        val f = t.javaClass.getDeclaredField("pauseStartedAtMs")
        f.isAccessible = true
        f.set(t, System.currentTimeMillis() - ageMs)
    }

    @Test
    fun `a healthy active row is never aborted`() {
        val (tid, handle) = leg("healthy.dat")
        SendAbortWatchdog.sweep()
        assertNull("no cause recorded for a streaming leg", handle.consumeCause())
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(tid)!!.status)
        handle.close()
    }

    @Test
    fun `a pause younger than the grace is left alone`() {
        val (tid, handle) = leg("blip.dat")
        TransferManager.requestPause(tid)   // pause just landed — sub-grace
        SendAbortWatchdog.sweep()
        assertNull("a sub-grace blip must keep the in-place resume path", handle.consumeCause())
        handle.close()
    }

    @Test
    fun `a pause past the grace aborts the wedged write with PAUSE and the row stays parkable`() {
        val (tid, handle) = leg("wedged.dat")
        TransferManager.updateProgress(tid, 4_000L)   // mid-stream
        TransferManager.requestPause(tid)
        agePause(tid, 2_000L)                          // past the 1.2s grace
        SendAbortWatchdog.sweep()
        assertEquals(SendAbortWatchdog.Cause.PAUSE, handle.consumeCause())
        // The parked-row contract: still ACTIVE, still paused, checkpoint kept.
        val t = TransferManager.get(tid)!!
        assertEquals(TransferManager.Status.ACTIVE, t.status)
        assertTrue(t.pauseRequested)
        assertEquals(4_000L, t.transferredBytes)
        handle.close()
    }

    @Test
    fun `a deliberate cancel aborts with CANCEL`() {
        val (tid, handle) = leg("cancelme.dat")
        TransferManager.requestCancel(tid)
        SendAbortWatchdog.sweep()
        assertEquals(SendAbortWatchdog.Cause.CANCEL, handle.consumeCause())
        handle.close()
    }

    @Test
    fun `a row the engine already resolved aborts with STATE`() {
        val (tid, handle) = leg("selfhealed.dat")
        // The wedged-socket self-heal / stall-cap shape: the row is FAILED
        // while this leg's write is still blocked.
        TransferManager.fail(tid, interrupted = true)
        SendAbortWatchdog.sweep()
        assertEquals(SendAbortWatchdog.Cause.STATE, handle.consumeCause())
        handle.close()
    }

    @Test
    fun `an orphaned row aborts with STATE`() {
        val (tid, handle) = leg("orphan.dat")
        TransferManager.dismiss(tid)
        SendAbortWatchdog.sweep()
        assertEquals(SendAbortWatchdog.Cause.STATE, handle.consumeCause())
        handle.close()
    }

    @Test
    fun `the abort cause is consumed exactly once`() {
        val (tid, handle) = leg("once.dat")
        TransferManager.requestPause(tid)
        agePause(tid, 2_000L)
        SendAbortWatchdog.sweep()
        assertEquals(SendAbortWatchdog.Cause.PAUSE, handle.consumeCause())
        assertNull("a stale cause must never leak into a later exception", handle.consumeCause())
        handle.close()
    }

    @Test
    fun `a retry leg keeps its registration when the predecessor closes late`() {
        val tid = TransferManager.start(
            TransferManager.Direction.SEND, "retry.dat", 10_000L, ip, peer,
            source = TransferManager.Source.NATIVE_PUSH, filePath = "/sdcard/retry.dat")
        val old = SendAbortWatchdog.register(tid) { /* finished on its own */ }
        val fresh = SendAbortWatchdog.register(tid) { /* the live retry leg */ }
        old.close()   // the predecessor's finally finally runs — must NOT drop the fresh leg
        // The fresh leg's decision must still be reachable:
        TransferManager.requestPause(tid)
        agePause(tid, 2_000L)
        SendAbortWatchdog.sweep()
        assertEquals("the retry leg was aborted by the pause, not orphaned by the predecessor's close",
            SendAbortWatchdog.Cause.PAUSE, fresh.consumeCause())
        assertNull(old.consumeCause())   // the old leg was never aborted by the sweep
        fresh.close()
    }

    @Test
    fun `the real 250ms watchdog thread fires on a wedged paused write`() {
        val (tid, handle) = leg("live-thread.dat")
        TransferManager.requestPause(tid)
        agePause(tid, 2_000L)
        // No manual sweep — the production scheduler must notice on its own
        // within a few cadences (250ms tick + scheduling slop).
        val deadline = System.currentTimeMillis() + 3_000L
        var seen: SendAbortWatchdog.Cause? = null
        while (seen == null && System.currentTimeMillis() < deadline) {
            seen = handle.consumeCause()
            if (seen == null) Thread.sleep(50)
        }
        assertEquals("the background sweep must abort a wedged paused write without any manual nudge",
            SendAbortWatchdog.Cause.PAUSE, seen)
        handle.close()
    }

    @Test
    fun `two consecutive pause freezes both get their permit back`() {
        // The exact reported shape, engine level: two files each with a live
        // send connection; the user pauses #1 then #2 (both past grace). BOTH
        // wedged writes must be aborted with PAUSE — which is what releases
        // each dispatch permit so items 2 and 3 start. (In the real client
        // only one file streams at a time; this pins the per-leg decision
        // for every pause in the sequence, whichever leg is wedged.)
        val one = leg("file1.dat")
        val two = leg("file2.dat")
        TransferManager.requestPause(one.first)
        TransferManager.requestPause(two.first)
        agePause(one.first, 2_000L)
        agePause(two.first, 2_000L)
        SendAbortWatchdog.sweep()
        assertEquals(SendAbortWatchdog.Cause.PAUSE, one.second.consumeCause())
        assertEquals(SendAbortWatchdog.Cause.PAUSE, two.second.consumeCause())
        for (t in listOf(one, two)) {
            val row = TransferManager.get(t.first)!!
            assertEquals("a parked row stays ACTIVE+paused (parkable, checkpoint preserved)",
                TransferManager.Status.ACTIVE, row.status)
            assertTrue(row.pauseRequested)
        }
        one.second.close(); two.second.close()
    }

    @Test
    fun `resume clears the pause so a later sweep leaves the streaming leg alone`() {
        val (tid, handle) = leg("resumed.dat")
        TransferManager.requestPause(tid)
        agePause(tid, 2_000L)
        SendAbortWatchdog.sweep()
        assertNotNull(handle.consumeCause())
        // The user resumes before the wedged write noticed anything: the
        // pause flag is gone, so no further sweep may abort the (healthy)
        // streaming connection.
        TransferManager.requestResume(tid)
        SendAbortWatchdog.sweep()
        assertNull(handle.consumeCause())
        handle.close()
    }
}
