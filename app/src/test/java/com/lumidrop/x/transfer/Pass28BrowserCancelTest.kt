package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ══ PASS 28 — browser-native download cancellation, pinned on the JVM ═════════
 *
 * THE reported bug (iPad 4, iOS 10.3.3, the DM-equipped Safari the pump serves):
 * cancelling an active download from the browser's own Downloads section aborted
 * the download manager's CONFIRMED delivery connection — and the Pass-27 handoff
 * branch treated EVERY handoff abort as the interception pattern, so the row sat
 * in the re-await ("Waiting for Safari…") for up to the 60 s cap and then failed
 * as an interruption: "the item didn't get cancelled, it was still there in the
 * sender app Active tab shown as waiting."
 *
 * The routing fix, pinned here: the row's own reAwaitedOnce latch discriminates —
 *   Case A (first interception abort, latch CLEAR) keeps the Pass-27 re-await;
 *   Case B (the confirmed delivery itself stopped, latch SET) enters the SAME
 * Pass-17 verdict regime every plain browser download already uses: the page
 * answers the liveness challenge → deliberate browser cancel (CANCELLED —
 * immediate for zero-byte stops, deferred by the evidence window for with-byte
 * stops so a download manager's own auto-resume still dissolves it); the page
 * gone → the honest interruption the auto-resume regime owns. Plus the
 * zip-stream delivery gate (ZipStreamDelivery — tested in the server package)
 * that keeps an abandoned archive reader from ever completing its row.
 */
class Pass28BrowserCancelTest {

    private val peerIp = "192.168.43.5"       // the iPad 4
    private val peerName = "iPad \u2022 Safari 10.3.3"

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
        TransferManager.browserLivenessProber = null
        TransferManager.browserCancelEvidenceMs = 5_000L
    }

    private fun statusOf(tid: String): TransferManager.Status? =
        TransferManager.activeSnapshot().firstOrNull { it.id == tid }?.status
            ?: TransferManager.terminalStateFor(tid)

    private fun row(tid: String) = TransferManager.activeSnapshot().first { it.id == tid }

    /** A handoff-eligible QS receive whose FIRST (interception) connection is
     *  mid-settle — the state the pump's iframe dispatch leaves behind. */
    private fun dispatchedHandoff(fileName: String, total: Long): String {
        val qsItem = "qs-$fileName"
        TransferManager.claimOrStartPending(fileName, total, peerIp, peerName, qsItem)
        val tid = TransferManager.claimOrStartActiveQuickShare(
            qsItem, peerIp, fileName, total, peerName, null)
        assertNotNull(tid)
        TransferManager.markHandoffEligible(tid)
        return tid
    }

    /** The full DM pattern through the interception: first connection aborted
     *  (Case A → re-await), the confirmed re-request attaches and streams
     *  [bytes] — the state a browser's Downloads section shows as an active
     *  download, ready for a Case-B stop. */
    private fun confirmedStreaming(fileName: String, total: Long, bytes: Long): String {
        val tid = dispatchedHandoff(fileName, total)
        TransferManager.downloadConnectionAborted(tid)              // interception cancel → re-await
        val tid2 = TransferManager.claimOrStartActiveQuickShare(    // the DM's confirmed re-request
            "qs-$fileName", peerIp, fileName, total, peerName, null)
        assertEquals(tid, tid2)
        if (bytes > 0) TransferManager.addProgress(tid, bytes)
        return tid
    }

    // ── 1. The routing itself ────────────────────────────────────────────────

    @Test
    fun `Case A - the FIRST interception abort still re-awaits (Pass 27 unchanged)`() {
        val tid = dispatchedHandoff("A.jpg", 1_000L)
        TransferManager.downloadConnectionAborted(tid)
        // The Pass-27 demotion-free re-await: ACTIVE, bytes preserved, window live.
        assertEquals(TransferManager.Status.ACTIVE, statusOf(tid))
        assertTrue(TransferManager.extendHandoffWindow(tid))
    }

    @Test
    fun `Case B - a stop of the CONFIRMED delivery never re-awaits (headless - interrupted)`() {
        val tid = confirmedStreaming("B.bin", 10_000_000L, 2_000_000L)
        TransferManager.downloadConnectionAborted(tid)
        // No prober wired (headless) → the verdict resolves the honest
        // interruption immediately — NEVER the "Waiting for Safari…" re-await
        // the reported bug sat in for up to a minute.
        assertEquals(TransferManager.Status.FAILED, statusOf(tid))
        assertTrue(row(tid).interrupted)
        assertFalse(TransferManager.extendHandoffWindow(tid))   // no re-await window exists
    }

    @Test
    fun `Case B with a live page and ZERO delivered bytes - the deliberate browser cancel is immediate`() {
        TransferManager.browserLivenessProber = { _ -> true }   // the page answers the challenge
        val tid = confirmedStreaming("C.jpg", 5_000L, 0L)       // cancelled at the very start
        TransferManager.downloadConnectionAborted(tid)
        Thread.sleep(450L)   // the 150 ms verdict window resolves
        assertEquals(TransferManager.Status.CANCELLED, statusOf(tid))
    }

    @Test
    fun `Case B with a live page and delivered bytes - the evidence window convicts the deliberate cancel`() {
        TransferManager.browserLivenessProber = { _ -> true }
        TransferManager.browserCancelEvidenceMs = 150L
        val tid = confirmedStreaming("D.bin", 10_000_000L, 3_000_000L)
        TransferManager.downloadConnectionAborted(tid)
        // Within the window: the honest, resumable interruption (a download
        // manager's own auto-resume still lands and dissolves the conviction).
        Thread.sleep(260L)   // verdict at ~150 ms; conviction not until ~300 ms
        assertEquals(TransferManager.Status.FAILED, statusOf(tid))
        assertTrue(row(tid).interrupted)
        // Past the window: no resume arrived — the deliberate browser cancel's
        // CANCELLED verdict lands with byte-for-byte the same end state.
        Thread.sleep(600L)
        assertEquals(TransferManager.Status.CANCELLED, statusOf(tid))
    }

    @Test
    fun `Case B with the page GONE - the honest interruption the auto-resume regime owns`() {
        TransferManager.browserLivenessProber = { _ -> false }  // network death — page included
        TransferManager.browserCancelEvidenceMs = 150L
        val tid = confirmedStreaming("E.bin", 10_000_000L, 3_000_000L)
        TransferManager.downloadConnectionAborted(tid)
        Thread.sleep(900L)
        // NEVER a phantom CANCELLED for a network drop — the resumable
        // interruption survives the (shortened) evidence window untouched.
        assertEquals(TransferManager.Status.FAILED, statusOf(tid))
        assertTrue(row(tid).interrupted)
    }

    // ── 2. The verdict's existing hardening, now covering handoff rows ───────

    @Test
    fun `Case B on a row already latched reconnecting skips the challenge - interrupted`() {
        TransferManager.browserLivenessProber = { _ -> true }
        val tid = confirmedStreaming("F.bin", 100_000L, 50_000L)
        TransferManager.markAllActiveReconnecting()
        TransferManager.downloadConnectionAborted(tid)
        Thread.sleep(450L)
        // A network-level outage already concluded — a live-page answer must
        // not turn it into a deliberate cancel (the Pass-18 pin, on handoff).
        assertEquals(TransferManager.Status.FAILED, statusOf(tid))
        assertTrue(row(tid).interrupted)
    }

    @Test
    fun `Case B under a host pause is verdict-immune - the pause owns the row`() {
        TransferManager.browserLivenessProber = { _ -> true }
        val tid = confirmedStreaming("G.bin", 100_000L, 50_000L)
        TransferManager.requestPause(tid)
        TransferManager.downloadConnectionAborted(tid)
        Thread.sleep(450L)
        // The pause-parked fallout: the row stays live and paused for its
        // resume re-dispatch — never cancelled, never interrupted.
        assertEquals(TransferManager.Status.ACTIVE, statusOf(tid))
        assertTrue(row(tid).pauseRequested)
    }

    @Test
    fun `a host cancel outranks any verdict - CANCELLED, immediately`() {
        TransferManager.browserLivenessProber = { _ -> false }
        val tid = confirmedStreaming("H.bin", 100_000L, 50_000L)
        TransferManager.requestCancel(tid)
        TransferManager.downloadConnectionAborted(tid)
        assertEquals(TransferManager.Status.CANCELLED, statusOf(tid))
    }

    @Test
    fun `full delivery evidence outranks a same-moment stop - COMPLETED, no cancel`() {
        TransferManager.browserLivenessProber = { _ -> true }
        val tid = confirmedStreaming("I.bin", 8_000L, 8_000L)
        // Abort AFTER every byte of the body genuinely left the socket:
        // completion is real evidence; a cancel that landed after the file
        // was fully delivered must not retract it.
        TransferManager.downloadConnectionAborted(tid)
        assertEquals(TransferManager.Status.COMPLETED, statusOf(tid))
    }

    // ── 3. Idempotence, stale events, reactivation ───────────────────────────

    @Test
    fun `duplicate aborts on the terminal row are safe no-ops`() {
        TransferManager.browserLivenessProber = { _ -> true }
        val tid = confirmedStreaming("J.jpg", 5_000L, 0L)
        TransferManager.downloadConnectionAborted(tid)
        Thread.sleep(450L)
        assertEquals(TransferManager.Status.CANCELLED, statusOf(tid))
        // The connection's close callback can legitimately fire more than
        // once (teardown double-fire); a terminal row ignores them all.
        TransferManager.downloadConnectionAborted(tid)
        TransferManager.downloadConnectionAborted(tid)
        assertEquals(TransferManager.Status.CANCELLED, statusOf(tid))
    }

    @Test
    fun `delayed progress events cannot reactivate a cancelled row`() {
        TransferManager.browserLivenessProber = { _ -> true }
        val tid = confirmedStreaming("K.jpg", 5_000L, 0L)
        TransferManager.downloadConnectionAborted(tid)
        Thread.sleep(450L)
        assertEquals(TransferManager.Status.CANCELLED, statusOf(tid))
        TransferManager.addProgress(tid, 4_000L)
        assertEquals(TransferManager.Status.CANCELLED, statusOf(tid))
    }

    @Test
    fun `the verdict dissolves when the download manager's own re-request lands`() {
        TransferManager.browserLivenessProber = { _ -> true }
        val tid = confirmedStreaming("L.bin", 10_000_000L, 3_000_000L)
        TransferManager.downloadConnectionAborted(tid)   // verdict window opens…
        // …but the DM's own auto-resume re-request lands inside it
        val tid2 = TransferManager.claimOrStartActiveQuickShare(
            "qs-L.bin", peerIp, "L.bin", 10_000_000L, peerName, null)
        assertEquals(tid, tid2)
        TransferManager.addProgress(tid, 7_000_000L)
        Thread.sleep(450L)   // the scheduled verdict must find nothing to decide
        assertEquals(TransferManager.Status.ACTIVE, statusOf(tid))
        TransferManager.complete(tid)
        assertEquals(TransferManager.Status.COMPLETED, statusOf(tid))
    }

    @Test
    fun `a re-dispatched delivery re-arms the interception discrimination - Case A again`() {
        TransferManager.browserLivenessProber = { _ -> true }
        val tid = confirmedStreaming("M.jpg", 5_000L, 5_000L)
        TransferManager.complete(tid)
        assertEquals(TransferManager.Status.COMPLETED, statusOf(tid))
        // The user re-requests the same file (re-tap Get / Resend): the QS
        // flow mints its fresh delivery identity (or re-opens the row —
        // either way it is a NEW logical attempt), and whatever identity it
        // uses, the NEXT interception abort must be Case A — the re-await —
        // exactly like the first delivery: the fresh attempt's reAwaitedOnce
        // latch starts clear, never inheriting the previous attempt's.
        val tid2 = TransferManager.claimOrStartActiveQuickShare(
            "qs-M.jpg", peerIp, "M.jpg", 5_000L, peerName, null)
        assertNotNull(tid2)
        TransferManager.markHandoffEligible(tid2)
        TransferManager.downloadConnectionAborted(tid2)
        assertEquals(TransferManager.Status.ACTIVE, statusOf(tid2))
        assertTrue(TransferManager.extendHandoffWindow(tid2))
    }

    // ── 4. The plain-browser (non-handoff) path is untouched ─────────────────

    @Test
    fun `a plain modern-browser stop keeps its existing verdict semantics`() {
        TransferManager.browserLivenessProber = { _ -> true }
        val qsItem = "qs-N.bin"
        TransferManager.claimOrStartPending("N.bin", 8_000L, peerIp, peerName, qsItem)
        val tid = TransferManager.claimOrStartActiveQuickShare(
            qsItem, peerIp, "N.bin", 8_000L, peerName, null)
        TransferManager.addProgress(tid, 8_000L)
        TransferManager.downloadConnectionAborted(tid)
        // Delivered-everything on a live page: the existing plain-path
        // outcome (Branch 1's own completion check) — unchanged.
        assertEquals(TransferManager.Status.COMPLETED, statusOf(tid))
        assertNull(TransferManager.activeSnapshot().firstOrNull { it.id == tid })
    }
}
