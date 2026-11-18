package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ══ GOAT-PASS 5 — the authoritative executing-state contract ════════════════
 *
 * Pins, at the ENGINE level, the three new scheduler semantics this pass
 * adds (§7 and §8 of the specification):
 *
 *   1. isExecuting — the ONE signal the UI's active (purple "Sending…" /
 *      "Receiving…") presentation must derive from. A row that was RESUMED
 *      but does not own an execution slot is NOT executing: it renders the
 *      neutral waiting state with its preserved progress, and flips to
 *      active only when a gate actually grants it a permit.
 *
 *   2. clearPauseSlotDebt — a receiving leg deliberately ended by a settled
 *      host pause (GOAT-PASS 5's apiUpload park exit) must not carry the
 *      pause-yield's re-acquire debt into the grave: the parked connection
 *      is gone, and a stale debt would make a LATER pause+resume cycle on
 *      the same row try to re-acquire permits it no longer owes
 *      (over-acquiring on a 1-permit gate = self-deadlock).
 *
 *   3. The pause-park queue discipline (the engine half of the
 *      fifth-pause freeze fix): a settled pause frees the receiving gate —
 *      the next request admits at ANY pause depth. Six consecutive pauses
 *      leave the queue exactly as live as the first (the reported
 *      "after pausing item 5, item 6 does not start" is impossible when
 *      the gate's arithmetic is clean).
 *
 * Real-timing by necessity (the pause-yield fires 750 ms after the pause
 * stamp), exactly like the GOAT-PASS 3 suite.
 */
class GoatPass5ExecutingStateTest {

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
        // Headless default: no sender-side holder set wired (NativePushClient
        // wires the real one at app boot; the engine must answer false for
        // SEND rows without it, never throw).
        TransferManager.wireOwnsSendSlot { false }
    }

    private val ip = "192.168.43.9"
    private val peer = "Laptop • Chrome"

    private sealed class RequestOutcome {
        object Refused : RequestOutcome()
        data class Admitted(val tid: String, val slotId: String) : RequestOutcome()
    }

    /** Mirrors apiUpload's real admission sequence for one browser upload:
     *  the PENDING row (pre-registered at approval time) waits for a
     *  receiving slot, then claims to ACTIVE — the exact chain the server
     *  endpoint drives. */
    private fun simulateUploadRequest(reqId: String, name: String, size: Long): RequestOutcome {
        val pendingId = TransferManager.findPendingUploadId(reqId)
            ?: TransferManager.startPendingUpload(reqId, name, size, ip, peer)
            ?: return RequestOutcome.Refused
        if (!TransferManager.acquireReceivingSlot(pendingId)) return RequestOutcome.Refused
        val tid = TransferManager.claimPendingUpload(reqId, "/sdcard/$name")
        if (tid == null) {
            TransferManager.releaseReceivingSlot(pendingId)
            return RequestOutcome.Refused
        }
        return RequestOutcome.Admitted(tid, pendingId)
    }

    // ── 1: isExecuting tracks real slot ownership, not resume requests ───────

    @Test
    fun `isExecuting is true only while the row holds a receiving slot`() {
        val r = simulateUploadRequest("gp5a::t1.bin", "t1.bin", 1000L)
        assertTrue("the first upload must admit", r is RequestOutcome.Admitted)
        val (tid, slotId) = (r as RequestOutcome.Admitted)
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(tid)!!.status)
        assertTrue("an admitted, streaming upload IS executing",
            TransferManager.isExecuting(tid))

        // Paused by the host: not executing the instant the pause lands.
        TransferManager.requestPause(tid)
        assertFalse("a paused row is never executing", TransferManager.isExecuting(tid))

        // The pause settles: the scheduled yield frees the gate — the row
        // STAYS Active+paused (its identity and checkpoint) but owns no
        // slot: resumed-but-queued, the exact state that must render the
        // neutral waiting presentation.
        Thread.sleep(1100)
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(tid)!!.status)
        assertTrue(TransferManager.isPaused(tid))
        assertFalse("a settled-parked row owns no execution slot", TransferManager.isExecuting(tid))

        // Cleanup under the exact id the slot was acquired with.
        TransferManager.releaseReceivingSlot(slotId)
        TransferManager.requestCancel(tid)
    }

    @Test
    fun `a resumed-but-queued row is not executing while another row streams`() {
        val rA = simulateUploadRequest("gp5b::a.bin", "a.bin", 1000L)
        assertTrue(rA is RequestOutcome.Admitted)
        val a = (rA as RequestOutcome.Admitted)
        assertTrue(TransferManager.isExecuting(a.tid))

        // Pause A; let it settle; B admits on the freed permit.
        TransferManager.requestPause(a.tid)
        Thread.sleep(1100)
        val rB = simulateUploadRequest("gp5b::b.bin", "b.bin", 1000L)
        assertTrue("the next item must admit while A stays paused", rB is RequestOutcome.Admitted)
        val b = (rB as RequestOutcome.Admitted)

        // Resume A (the host's tap clears the flag — the browser's re-POST
        // is still in flight): A is ACTIVE, unpaused, and NOT the executing
        // row. Only B wears the active presentation.
        TransferManager.requestResume(a.tid)
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(a.tid)!!.status)
        assertFalse("resumed A is queued, not executing", TransferManager.isExecuting(a.tid))
        assertTrue("B is the one genuinely executing", TransferManager.isExecuting(b.tid))

        TransferManager.releaseReceivingSlot(b.slotId)
        TransferManager.requestCancel(a.tid)
        TransferManager.requestCancel(b.tid)
    }

    @Test
    fun `isExecuting is false for pending, terminal and unknown rows`() {
        // PENDING (waiting for its turn): not executing.
        val p = TransferManager.startPendingUpload("gp5c::p.bin", "p.bin", 10L, ip, peer)
        assertNotNull(p)
        assertFalse("a PENDING row is not executing", TransferManager.isExecuting(p!!))

        // Terminal: not executing.
        val s = TransferManager.start(TransferManager.Direction.SEND, "s.bin", 10L, ip, peer)
        TransferManager.complete(s)
        assertFalse("a COMPLETED row is not executing", TransferManager.isExecuting(s))

        // Unknown: false, never throws.
        assertFalse(TransferManager.isExecuting("no-such-row"))
    }

    @Test
    fun `send rows derive executing from the wired dispatch-gate holder set`() {
        val s = TransferManager.start(TransferManager.Direction.SEND, "live.bin", 10L, ip, peer)
        // Wire the holder set to contain exactly this row's id — the shape
        // NativePushClient registers at boot (streamingSends).
        TransferManager.wireOwnsSendSlot { it == s }
        assertTrue("a SEND row inside the holder set is executing", TransferManager.isExecuting(s))
        TransferManager.wireOwnsSendSlot { false }
        assertFalse("the same row outside the holder set is not executing", TransferManager.isExecuting(s))
        // A paused row is never executing, holder set or not.
        TransferManager.requestPause(s)
        assertFalse(TransferManager.isExecuting(s))
        TransferManager.requestCancel(s)
    }

    // ── 2: the parked leg's stale debt must die with the leg ─────────────────

    @Test
    fun `clearPauseSlotDebt erases a parked leg's re-acquire ledger`() {
        val r = simulateUploadRequest("gp5d::t.bin", "t.bin", 1000L)
        assertTrue(r is RequestOutcome.Admitted)
        val (tid, slotId) = (r as RequestOutcome.Admitted)

        TransferManager.requestPause(tid)
        Thread.sleep(1100)
        assertEquals("the settled pause recorded exactly one debt unit",
            1, TransferManager.get(tid)!!.pauseYieldedSlots.get())

        // The parked leg ends (apiUpload's deliberate "Paused by host" exit)
        // — the debt dies with it, so the row's LATER pause+resume cycle
        // re-acquires exactly what THAT cycle yields, never more.
        TransferManager.clearPauseSlotDebt(tid)
        assertEquals(0, TransferManager.get(tid)!!.pauseYieldedSlots.get())
        assertFalse(TransferManager.get(tid)!!.pauseReacquireArmed)

        // Gate arithmetic after the clear: a fresh request still admits
        // (exactly one permit was freed by the yield, none leaked, none
        // double-counted).
        val r2 = simulateUploadRequest("gp5d::u.bin", "u.bin", 1000L)
        assertTrue("the queue is live after the park", r2 is RequestOutcome.Admitted)
        val u = (r2 as RequestOutcome.Admitted)

        TransferManager.releaseReceivingSlot(u.slotId)
        TransferManager.requestCancel(u.tid)
        TransferManager.releaseReceivingSlot(slotId)
        TransferManager.requestCancel(tid)
    }

    // ── 3: the pause-park queue discipline at any depth (§8's engine half) ──

    @Test
    fun `six consecutive settled pauses never wedge the receiving gate`() {
        // The reported browser-sender failure: after the FIFTH paused item,
        // item 6 does not start. At the engine level the invariant is the
        // gate's: every settled pause hands its permit back, so the Nth
        // request admits no matter how deep the pause stack is.
        val slotIds = ArrayList<String>()
        val tids = ArrayList<String>()
        for (i in 0 until 6) {
            val r = simulateUploadRequest("gp5e::f$i.bin", "f$i.bin", 1000L)
            assertTrue("request ${i + 1} must admit (the queue never wedges)",
                r is RequestOutcome.Admitted)
            val (tid, slotId) = (r as RequestOutcome.Admitted)
            assertTrue(TransferManager.isExecuting(tid))
            TransferManager.requestPause(tid)
            Thread.sleep(1100)   // settle: the yield frees the permit
            assertFalse(TransferManager.isExecuting(tid))
            slotIds.add(slotId)
            tids.add(tid)
        }
        // The SEVENTH request (the reported "item 6", one past the freeze)
        // admits immediately.
        val r7 = simulateUploadRequest("gp5e::seventh.bin", "seventh.bin", 1000L)
        assertTrue("the request after six settled pauses must admit",
            r7 is RequestOutcome.Admitted)
        val seventh = (r7 as RequestOutcome.Admitted)
        assertTrue(TransferManager.isExecuting(seventh.tid))

        // Unwind.
        TransferManager.releaseReceivingSlot(seventh.slotId)
        TransferManager.requestCancel(seventh.tid)
        for (i in tids.indices) {
            TransferManager.releaseReceivingSlot(slotIds[i])
            TransferManager.requestCancel(tids[i])
        }
    }

    // ── 3b: the CANCELLATION twin of the same discipline (§8 Test B) ─────────
    // The reported failure shape: "send 13 files; cancel items 1–5 one by one;
    // item 6 does not start." At the engine level: every deliberate cancel
    // hands its permit back SYNCHRONOUSLY (requestCancel's own release), so
    // the Nth request after any depth of cancellations admits immediately.

    @Test
    fun `six consecutive cancellations never wedge the receiving gate`() {
        for (i in 0 until 6) {
            val r = simulateUploadRequest("gp5f::c$i.bin", "c$i.bin", 1000L)
            assertTrue("request ${i + 1} must admit (cancellations never wedge the queue)",
                r is RequestOutcome.Admitted)
            val (tid, _) = (r as RequestOutcome.Admitted)
            assertTrue("item ${i + 1} is the executing row before its cancel", TransferManager.isExecuting(tid))
            TransferManager.requestCancel(tid)
            assertEquals(TransferManager.Status.CANCELLED, TransferManager.get(tid)!!.status)
            assertFalse("a cancelled row is never executing", TransferManager.isExecuting(tid))
        }
        // The SEVENTH request (the reported "item 6", one past the cancelled
        // five) admits immediately — no leaked permit, no stale registration.
        val r7 = simulateUploadRequest("gp5f::seventh.bin", "seventh.bin", 1000L)
        assertTrue("the request after six cancellations must admit",
            r7 is RequestOutcome.Admitted)
        val seventh = (r7 as RequestOutcome.Admitted)
        assertTrue(TransferManager.isExecuting(seventh.tid))
        TransferManager.releaseReceivingSlot(seventh.slotId)
        TransferManager.requestCancel(seventh.tid)
    }

    // ── 3c: mixed pause/cancel transitions (§8 Test C) ──────────────────────
    // Pause item 1, cancel item 2, pause item 3, cancel item 4, pause item 5 —
    // item 6 must still start. The two lifecycle ends free their permits
    // through different paths (the pause's scheduled yield vs the cancel's
    // synchronous release); alternating them pins that BOTH keep the gate's
    // arithmetic clean in the same queue.

    @Test
    fun `mixed pause and cancel transitions never wedge the receiving gate`() {
        val slotIds = ArrayList<String>()
        val pausedTids = ArrayList<String>()
        for (i in 0 until 5) {
            val r = simulateUploadRequest("gp5g::m$i.bin", "m$i.bin", 1000L)
            assertTrue("mixed step ${i + 1} must admit", r is RequestOutcome.Admitted)
            val (tid, slotId) = (r as RequestOutcome.Admitted)
            assertTrue(TransferManager.isExecuting(tid))
            if (i % 2 == 0) {
                TransferManager.requestPause(tid)
                Thread.sleep(1100)   // settle: the yield frees the permit
                assertFalse(TransferManager.isExecuting(tid))
                slotIds.add(slotId); pausedTids.add(tid)
            } else {
                TransferManager.requestCancel(tid)
                assertEquals(TransferManager.Status.CANCELLED, TransferManager.get(tid)!!.status)
                assertFalse(TransferManager.isExecuting(tid))
            }
        }
        // The reported "item 6" after pause, cancel, pause, cancel, pause:
        val r6 = simulateUploadRequest("gp5g::sixth.bin", "sixth.bin", 1000L)
        assertTrue("the request after the mixed pause/cancel run must admit",
            r6 is RequestOutcome.Admitted)
        val sixth = (r6 as RequestOutcome.Admitted)
        assertTrue(TransferManager.isExecuting(sixth.tid))
        TransferManager.releaseReceivingSlot(sixth.slotId)
        TransferManager.requestCancel(sixth.tid)
        for (i in pausedTids.indices) {
            TransferManager.releaseReceivingSlot(slotIds[i])
            TransferManager.requestCancel(pausedTids[i])
        }
    }

    // ── 4: the parked-row adoption lookup (§8/§9 final round) ───────────────
    // apiUpload's fresh-POST admission adopts the LIVE parked row for a
    // requestId instead of minting a duplicate — the lookup must resolve
    // exactly the ACTIVE row for THAT request, never a PENDING row, never a
    // FAILED+interrupted row (that one has its own reopen path), never
    // another request's row.

    @Test
    fun `findParkedUploadId resolves exactly the live ACTIVE row`() {
        // An admitted, settled-paused row: the parked shape.
        val r = simulateUploadRequest("gp5h::park.bin", "park.bin", 1000L)
        assertTrue(r is RequestOutcome.Admitted)
        val (tid, slotId) = (r as RequestOutcome.Admitted)
        TransferManager.requestPause(tid)
        Thread.sleep(1100)
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(tid)!!.status)
        assertEquals("the parked row is the adoption target",
            tid, TransferManager.findParkedUploadId("gp5h::park.bin"))

        // A PENDING row for another request is NOT a parked row (its own
        // claim path handles it) and never resolves for this requestId.
        val pending = TransferManager.startPendingUpload("gp5h::other.bin", "other.bin", 10L, ip, peer)
        assertNotNull(pending)
        assertTrue(TransferManager.findParkedUploadId("gp5h::other.bin") == null
            || TransferManager.get(TransferManager.findParkedUploadId("gp5h::other.bin")!!)!!.status
                == TransferManager.Status.ACTIVE)
        assertEquals("a PENDING row is not adoptable as parked",
            null, TransferManager.findParkedUploadId("gp5h::nonexistent.bin"))

        // A FAILED+interrupted row has its own reopen path — not parked.
        val f = TransferManager.start(TransferManager.Direction.UPLOAD, "failed.bin", 10L, ip, peer,
            uploadRequestId = "gp5h::failed.bin")
        TransferManager.fail(f, interrupted = true)
        assertEquals(null, TransferManager.findParkedUploadId("gp5h::failed.bin"))

        TransferManager.releaseReceivingSlot(slotId)
        TransferManager.requestCancel(tid)
        TransferManager.requestCancel(pending!!)
        TransferManager.dismiss(f)
    }

    // ── 5: the slot-ledger rekey (§7 ledger truth, final round) ──────────────
    // apiUpload's start() fallback mints a FRESH row after the permit was
    // already acquired under the swept PENDING row's id. The registration
    // must follow the row that actually streams — isExecuting answers for
    // the live row, and the leg's release (under the rekeyed id) is the one
    // that frees the gate.

    @Test
    fun `rekeyReceivingSlot moves the registration and the release follows`() {
        // The swept-PENDING shape: a pending row that vanishes before claim.
        val pendingId = TransferManager.startPendingUpload("gp5i::gone.bin", "gone.bin", 1000L, ip, peer)!!
        assertTrue(TransferManager.acquireReceivingSlot(pendingId))
        // The PENDING row is swept while the request waited at the gate —
        // apiUpload's fallback mints a fresh row for the same bytes.
        TransferManager.dismiss(pendingId)
        val fresh = TransferManager.start(TransferManager.Direction.UPLOAD, "gone.bin", 1000L, ip, peer)!!

        // Before the rekey: the fresh row streams but answers "not executing"
        // — the §7 violation this fix closes.
        assertFalse(TransferManager.isExecuting(fresh))
        TransferManager.rekeyReceivingSlot(pendingId, fresh)
        assertTrue("after the rekey the streaming row IS executing", TransferManager.isExecuting(fresh))
        assertFalse("the dead wait-key no longer reads in-flight",
            TransferManager.isReceivingInFlight(pendingId))

        // The release follows the rekeyed key: the gate comes back exactly once.
        TransferManager.complete(fresh)
        TransferManager.releaseReceivingSlot(fresh)
        val next = simulateUploadRequest("gp5i::next.bin", "next.bin", 1000L)
        assertTrue("the rekeyed release freed the permit for the next item",
            next is RequestOutcome.Admitted)
        val (tid2, slot2) = (next as RequestOutcome.Admitted)
        // The stale double-release under the dead key is a harmless no-op.
        TransferManager.releaseReceivingSlot(pendingId)
        TransferManager.releaseReceivingSlot(slot2)
        TransferManager.requestCancel(tid2)
    }
}
