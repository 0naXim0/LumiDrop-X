package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ══ GOAT-PASS 6 — the complete transfer-lifecycle contract ══════════════════
 *
 * Pins, at the ENGINE level, the lifecycle semantics this pass adds:
 *
 *   1. §1 — NativeSessionPolicy: the session-hold rule that makes the
 *      reported "Pause All → resume the first item → every remaining item
 *      becomes CANCELLED" impossible. A session may only finish when its
 *      work is genuinely finished: nothing parked, nothing paused, and no
 *      file still queued, active or engine-live — paused-WAITING siblings
 *      (the majority after a Pause All) count, not just mid-stream parks.
 *      And a FINISHED session ("done"/"done_with_errors") never cancels a
 *      file its scheduler still dispatches — only a deliberately torn-down
 *      session does.
 *
 *   2. §3 — the pause-parked download connection: a settled host pause
 *      leaves the browser's connection parked; when that connection dies
 *      (surfacing only AFTER the resume clears the pause flag), the abort
 *      is the pause's own fallout — the row keeps its live state and
 *      checkpoint (never a cancel verdict, never a stuck Auto-Resume), and
 *      the download endpoints can refuse a paused item's DM retry once the
 *      pause has genuinely settled.
 *
 *   3. §3 — the re-dispatch adoption: a from-0 request attaching to a row
 *      whose legs are all gone becomes THE delivery with a clean byte
 *      count (one card, honestly from 0%), never additive on the dead
 *      leg's partial count.
 *
 *   4. §2 — pruneResumableRows only ever prunes FAILED kept-for-resume
 *      rows: a recovery card that was re-opened LIVE can never be
 *      dismissed by a validity sweep, no matter what its staging file
 *      momentarily looks like.
 *
 *   5. §4 — the native receive claim chain's ledger discipline: a permit
 *      acquired under a wait key and re-keyed onto the streaming row makes
 *      that row honestly executing, and its release frees the gate for the
 *      next item (the exact semantics apiNativePushFile now relies on).
 *
 * Real-timing by necessity (the pause-yield fires 750 ms after the pause
 * stamp), exactly like the GOAT-PASS 3/5 suites.
 */
class GoatPass6LifecycleTest {

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
        TransferManager.wireOwnsSendSlot { false }
    }

    private val ip = "192.168.43.14"
    private val peer = "Pixel • LumiDrop"

    // ── 1: the §1 session-hold policy ────────────────────────────────────────

    /**
     * The exact reported reproduction, expressed through the policy the
     * dispatcher consults: 13 files, item 1 parked mid-stream (then resumed
     * and completed — parkedCount back to 0), items 2..13 paused while still
     * WAITING. The OLD rule (parkedCount only) released the session; the new
     * rule holds it open — completing item 1 can never cancel its siblings.
     */
    @Test
    fun `paused-waiting siblings hold the session open after the parked item completes`() {
        // Item 1: resumed, streamed, completed — DONE, row terminal, not parked.
        val item1Row = TransferManager.start(TransferManager.Direction.SEND, "01.bin", 1000L,
            ip, peer, source = TransferManager.Source.NATIVE_PUSH)
        TransferManager.complete(item1Row)

        // Items 2..13: still WAITING (pre-registered PENDING rows), every one
        // deliberately paused by Pause All. Their FileProgress states are
        // QUEUED on the sender (never dispatched); their engine rows are
        // PENDING + pauseRequested.
        val waiting = (2..13).map { i ->
            val tid = TransferManager.startPendingSend("%02d.bin".format(i), 1000L)
            TransferManager.requestPause(tid)
            tid
        }

        val held = NativeSessionPolicy.sessionHeldOpen(
            fileStates = listOf(item1Row to "DONE") +
                waiting.map { it to "QUEUED" },
            parkedCount = 0,   // the one mid-stream park was resumed and completed
            rowPaused = { tid -> TransferManager.isPaused(tid) },
            rowLive = { tid ->
                TransferManager.get(tid)?.let {
                    it.status == TransferManager.Status.PENDING || it.status == TransferManager.Status.ACTIVE
                } == true
            })
        assertTrue("the session must stay open while 12 paused-waiting siblings remain", held)

        // The session may finish only once every sibling resolved: unpause and
        // resolve them all terminal first.
        waiting.forEach { tid ->
            TransferManager.requestResume(tid)
            TransferManager.cancelPendingSend(tid)   // the user's own per-item decision
        }
        val heldAfter = NativeSessionPolicy.sessionHeldOpen(
            fileStates = listOf(item1Row to "DONE") + waiting.map { it to "NOT_DONE" },
            parkedCount = 0,
            rowPaused = { tid -> TransferManager.isPaused(tid) },
            rowLive = { tid ->
                TransferManager.get(tid)?.let {
                    it.status == TransferManager.Status.PENDING || it.status == TransferManager.Status.ACTIVE
                } == true
            })
        assertFalse("a fully-resolved session finishes", heldAfter)
    }

    /** A mid-stream park alone holds the session — even when its FileProgress
     *  state has already moved past PAUSED (a completed resume's bookkeeping
     *  lag must never finish the session under a live park). */
    @Test
    fun `a mid-stream park holds the session open regardless of file state bookkeeping`() {
        val tid = TransferManager.startPendingSend("parked.bin", 1000L)
        val held = NativeSessionPolicy.sessionHeldOpen(
            fileStates = listOf(tid to "DONE"),
            parkedCount = 1,
            rowPaused = { false },
            rowLive = { false })
        assertTrue(held)
    }

    /** A sibling resumed and legitimately WAITING AT THE RECEIVING GATE
     *  (engine row ACTIVE, FileProgress already ACTIVE) holds the session —
     *  the row IS the user-visible transfer. */
    @Test
    fun `a resumed sibling waiting at the receiving gate holds the session open`() {
        val tid = TransferManager.start(TransferManager.Direction.SEND, "gate.bin", 1000L,
            ip, peer, source = TransferManager.Source.NATIVE_PUSH)
        assertTrue(TransferManager.get(tid)!!.status == TransferManager.Status.ACTIVE)
        val held = NativeSessionPolicy.sessionHeldOpen(
            fileStates = listOf(tid to "ACTIVE"),
            parkedCount = 0,
            rowPaused = { false },
            rowLive = { t -> TransferManager.get(t)?.status == TransferManager.Status.ACTIVE })
        assertTrue(held)
        TransferManager.requestCancel(tid)
    }

    /** Only a deliberately TORN-DOWN session cancels in-flight work. The
     *  FINISHED statuses ("done"/"done_with_errors") must never — that
     *  blanket match was the second half of the §1 cancellation cascade. */
    @Test
    fun `a finished session never cancels a file its scheduler still dispatches`() {
        assertFalse(NativeSessionPolicy.sessionTornDown("done"))
        assertFalse(NativeSessionPolicy.sessionTornDown("done_with_errors"))
        assertFalse(NativeSessionPolicy.sessionTornDown("sending"))
        assertFalse(NativeSessionPolicy.sessionTornDown("pending"))
        assertTrue(NativeSessionPolicy.sessionTornDown("rejected"))
        assertTrue(NativeSessionPolicy.sessionTornDown("error"))
    }

    // ── 2: the §3 pause-parked download connection ───────────────────────────

    /** Mirrors apiQSDl's admission for one QS item: a fresh ACTIVE row with
     *  exactly one live connection. */
    private fun startQsDownload(itemName: String): String =
        TransferManager.claimOrStartActiveQuickShare(itemName, ip, itemName, 1000L, peer, null)

    @Test
    fun `an abort of a pause-parked download keeps the row live with its checkpoint`() {
        val tid = startQsDownload("park-dl.bin")
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(tid)!!.status)
        TransferManager.addProgress(tid, 400L)   // a real partial: the checkpoint that must survive

        // The host pauses the item; the pause settles past the grace window —
        // the scheduled yield hands the permit back AND marks the parked
        // connection (see schedulePausePermitYield's GOAT-PASS 6 block).
        TransferManager.requestPause(tid)
        Thread.sleep(1100)
        assertTrue(TransferManager.isPaused(tid))

        // The parked connection died while parked — its death surfaces AFTER
        // the resume cleared the pause flag (the exact reported race): the
        // abort must be treated as the pause's own fallout.
        TransferManager.requestResume(tid)
        TransferManager.downloadConnectionAborted(tid)

        val row = TransferManager.get(tid)
        assertNotNull("the row must survive the parked-connection abort", row)
        assertTrue("the row keeps its live state — never CANCELLED, never FAILED",
            row!!.status == TransferManager.Status.ACTIVE || row.status == TransferManager.Status.PENDING)
        assertEquals("the checkpoint is preserved", 400L, row.transferredBytes)

        // The re-dispatch attaches to the same row with a clean count (see the
        // next test) — and the gate is free for it (the parked permit was
        // yielded at settle, the abort released nothing extra).
        TransferManager.requestCancel(tid)
    }

    @Test
    fun `a paused download abort keeps the row paused when the pause still stands`() {
        val tid = startQsDownload("park-dl2.bin")
        TransferManager.requestPause(tid)
        Thread.sleep(1100)

        // The connection dies WHILE the pause still stands (the browser gave
        // up on the stalled download while the user kept it paused).
        TransferManager.downloadConnectionAborted(tid)

        val row = TransferManager.get(tid)
        assertNotNull(row)
        assertTrue("the row stays ACTIVE+paused — a pause is a scheduled continuation",
            row!!.status == TransferManager.Status.ACTIVE && row.pauseRequested)
        TransferManager.requestCancel(tid)
    }

    @Test
    fun `pausedDownloadPastGrace answers only a settled pause for the exact item and peer`() {
        val tid = startQsDownload("grace-dl.bin")
        TransferManager.requestPause(tid)
        // Inside the toggle grace: nothing to refuse yet (a quick blip parks
        // nothing, and a DM retry inside the grace keeps working as before).
        assertNull("inside the grace window the refusal must not fire",
            TransferManager.pausedDownloadPastGrace("grace-dl.bin", "grace-dl.bin", ip))
        Thread.sleep(1100)
        assertNotNull("a settled pause answers the paused row",
            TransferManager.pausedDownloadPastGrace("grace-dl.bin", "grace-dl.bin", ip))
        // A different item / a different peer is never refused on this row's
        // behalf.
        assertNull("another item is untouched",
            TransferManager.pausedDownloadPastGrace("other.bin", "other.bin", ip))
        assertNull("another peer is untouched",
            TransferManager.pausedDownloadPastGrace("grace-dl.bin", "grace-dl.bin", "192.168.43.99"))
        // The resume lifts the refusal immediately.
        TransferManager.requestResume(tid)
        assertNull("a resumed item is never refused",
            TransferManager.pausedDownloadPastGrace("grace-dl.bin", "grace-dl.bin", ip))
        TransferManager.requestCancel(tid)
    }

    // ── 3: the §3 re-dispatch adoption (one card, honestly from 0%) ──────────

    @Test
    fun `a from-zero re-dispatch on a legless row resets the byte accounting`() {
        val tid = startQsDownload("redispatch.bin")
        TransferManager.addProgress(tid, 500L)
        // The pause-parked leg died (the §3 immunity kept the row); its
        // connection count is zero.
        TransferManager.get(tid)!!.connectionCount.set(0)

        // The pump's re-dispatch attaches to the SAME row with a from-0
        // request — the fresh leg is THE delivery now.
        val again = TransferManager.claimOrStartActiveQuickShare("redispatch.bin", ip,
            "redispatch.bin", 1000L, peer, null, rangeStart = 0L)
        assertEquals("the re-dispatch adopts the SAME row — one card, one identity", tid, again)
        val row = TransferManager.get(tid)!!
        assertEquals("the fresh leg counts from a clean slate", 1, row.connectionCount.get())
        assertEquals("honestly from 0%, never additive on the dead partial", 0L, row.transferredBytes)
        assertFalse("no hole in a fresh full delivery", row.hasHole)
        TransferManager.requestCancel(tid)
    }

    @Test
    fun `a sibling attach while a leg is live keeps the additive accounting`() {
        val tid = startQsDownload("sibling.bin")
        TransferManager.addProgress(tid, 500L)
        // A parallel Range sibling attaches while the first connection still
        // streams: the ordinary additive path — unchanged.
        val again = TransferManager.claimOrStartActiveQuickShare("sibling.bin", ip,
            "sibling.bin", 1000L, peer, null, rangeStart = 0L)
        assertEquals(tid, again)
        val row = TransferManager.get(tid)!!
        assertEquals("a live sibling attach counts a second connection", 2, row.connectionCount.get())
        assertEquals("the byte count is untouched by the attach itself", 500L, row.transferredBytes)
        TransferManager.requestCancel(tid)
    }

    // ── 4: the §2 recovery-card prune guard ──────────────────────────────────

    @Test
    fun `pruneResumableRows never dismisses a re-opened live recovery row`() {
        // A "Waiting for reconnection" card with real saved progress.
        val tid = TransferManager.createResumableWaitingRow(
            fileName = "partial.bin", totalBytes = 1000L, peerIp = ip, peerName = peer,
            filePath = "/sdcard/LumiDrop/partial.bin", transferredBytes = 620L)
        val row = TransferManager.get(tid)!!
        assertTrue(row.status == TransferManager.Status.FAILED && row.interrupted)

        // The matching file was sent again — the card's row was re-opened
        // LIVE for the resumed delivery (reopenForResume leaves the
        // resumableRowIds entry in place; the sweep must cope).
        assertTrue(TransferManager.reopenForResume(tid))
        assertTrue(TransferManager.get(tid)!!.status == TransferManager.Status.ACTIVE)

        // A validity sweep that runs mid-delivery (the staging file is
        // momentarily gone — the fresh-start window deletes and recreates it)
        // must NOT dismiss the live row.
        TransferManager.pruneResumableRows { false }
        assertNotNull("a live resumed row survives an invalid-looking sweep",
            TransferManager.get(tid))

        // Once the row genuinely FAILED again (a later interruption), the same
        // sweep dismisses the data-less card.
        TransferManager.fail(tid, interrupted = true, keepForResume = true)
        TransferManager.pruneResumableRows { false }
        assertNull("a FAILED card whose partial is gone is dismissed",
            TransferManager.get(tid))
    }

    // ── 5: the §4 native claim-chain ledger discipline ───────────────────────

    @Test
    fun `a wait-key permit re-keyed onto the streaming row keeps isExecuting truthful`() {
        // The apiNativePushFile fallback shape: nothing was pre-registered, so
        // the request waits at the receiving gate under a SYNTHETIC key, then
        // mints the row and re-keys the registration onto it.
        val waitKey = "npwait:push-777::video.mp4"
        assertTrue("the gate must grant the wait key on a free slot",
            TransferManager.acquireReceivingSlot(waitKey))

        val tid = TransferManager.start(TransferManager.Direction.UPLOAD, "video.mp4", 1000L,
            ip, peer, source = TransferManager.Source.NATIVE_PUSH, filePath = "/sdcard/LumiDrop/video.mp4")
        assertFalse("before the re-key the fresh row is not executing",
            TransferManager.isExecuting(tid))

        TransferManager.rekeyReceivingSlot(waitKey, tid)
        assertTrue("after the re-key the streaming row IS executing — the §4 chip truth",
            TransferManager.isExecuting(tid))

        // The exactly-once release under the CURRENT key frees the gate for
        // the next item; a stale release under the dead wait key is a no-op.
        TransferManager.releaseReceivingSlot(waitKey)
        assertTrue("a stale release under the dead key must not free the gate",
            TransferManager.isExecuting(tid))
        TransferManager.releaseReceivingSlot(tid)
        assertFalse("the re-keyed release frees the gate", TransferManager.isExecuting(tid))

        // The freed gate admits the next receive — the queue keeps moving.
        val next = TransferManager.startPendingUpload("gp6::next.bin", "next.bin", 1000L, ip, peer)
        assertTrue(TransferManager.acquireReceivingSlot(next))
        val claimed = TransferManager.claimPendingUpload("gp6::next.bin", "/sdcard/next.bin")
        assertEquals(next, claimed)
        assertTrue(TransferManager.isExecuting(next))
        TransferManager.releaseReceivingSlot(next)
        TransferManager.requestCancel(tid)
        TransferManager.requestCancel(next)
    }
}
