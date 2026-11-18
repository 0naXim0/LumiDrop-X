package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ══ GOAT-PASS 7 — the transfer-engine contract ═════════════════════════════
 *
 * Pins, at the ENGINE level, the two GOAT-PASS 7 §2 semantics that live in
 * TransferManager itself:
 *
 *   1. §2B — HONEST SPEED FROM THE FIRST BYTE. Every claim/re-open site arms
 *      a clean speed-sample baseline; the first progress after it only
 *      LATCHES (reports no rate), so the first COMPUTED rate measures a pure
 *      streaming window instead of dividing the first burst of bytes by the
 *      row's whole PENDING lifetime (the fabricated "KB/s" start the report
 *      described, worst on mid-batch rows whose pre-registration was oldest).
 *      The first computed sample equals the true windowed rate exactly; a
 *      rising rate converges at the fast (0.5) alpha.
 *
 *   2. §2C — THE APP→WEB RECONNECT MISVERDICT. A download connection that
 *      dies around a page re-attach (a client-side network blip on a phone
 *      hotspot host) can never be convicted CANCELLED by the liveness
 *      challenge: the re-attach stamps an immunity window inside which
 *      verdicts resolve INTERRUPTED (resumable). A with-bytes abort on a live
 *      page OUTSIDE the immunity window resolves INTERRUPTED first and only
 *      convicts CANCELLED after the re-request evidence window lapses with
 *      no re-request — and the DM's own re-request (the in-place re-open)
 *      dissolves the conviction. A zero-byte abort on a live page keeps the
 *      immediate CANCELLED verdict (unchanged semantics).
 *
 * Real-timing by necessity (the verdict fires 150 ms after the abort, the
 * evidence window is shrunk to a test-friendly 300 ms), exactly like the
 * GOAT-PASS 3/5 suites.
 */
class GoatPass7EngineTest {

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
        // Headless defaults restored: no liveness prober wired, production
        // window sizes (individual tests shrink them explicitly).
        TransferManager.browserLivenessProber = null
        TransferManager.browserCancelEvidenceMs = 5_000L
        TransferManager.browserCancelReattachImmunityMs = 12_000L
    }

    private val peerName = "Laptop • Chrome"

    // ── 1: §2B — the claim arms a clean baseline; the first byte only latches ──

    @Test
    fun `claimPendingSend latches the speed baseline at the first byte`() {
        // A SEND row pre-registered at manifest time (the PENDING wait whose
        // whole lifetime the OLD first sample used to divide by).
        val tid = TransferManager.startPending(
            TransferManager.Direction.SEND, "video.mp4", 50_000_000L,
            "192.168.43.10", "Pixel 8", source = TransferManager.Source.NATIVE_PUSH)
        Thread.sleep(600)   // the PENDING idle time the old code divided by
        assertTrue(TransferManager.claimPendingSend(tid))
        // First progress AFTER the claim = the latch moment, never a rate.
        TransferManager.updateProgress(tid, 262_144L)
        val t1 = TransferManager.get(tid)!!
        assertEquals("the first progress after a claim must latch the baseline, not report a rate",
            0.0, t1.speedBps, 0.0)
        // A pure streaming window follows; its rate is the first HONEST
        // reading — equal to the true windowed rate, not the poisoned
        // first-burst-over-idle-time figure the old code produced.
        Thread.sleep(500)
        val db = 150_000L
        TransferManager.updateProgress(tid, 262_144L + db)
        // Capture the VALUE, not the live row (Transfer is mutable — a later
        // sample would silently rewrite a held reference).
        val speed2 = TransferManager.get(tid)!!.speedBps
        assertTrue("first computed rate must reflect the pure streaming window (was $speed2 B/s)",
            speed2 >= 150_000.0 && speed2 <= 900_000.0)
        // A RISING rate converges fast (alpha 0.5): one sample at ~4x moves
        // the smoothed value well past the midpoint.
        Thread.sleep(500)
        TransferManager.updateProgress(tid, 262_144L + db + 4 * db)
        val speed3 = TransferManager.get(tid)!!.speedBps
        assertTrue("a rising rate must converge at the fast alpha (was $speed3 B/s, from $speed2 B/s)",
            speed3 > speed2 * 1.8)
    }

    @Test
    fun `claimPending arms the download-side baseline the same way`() {
        val ip = "192.168.43.11"
        val tid = TransferManager.startPending(
            TransferManager.Direction.DOWNLOAD, "photo.jpg", 8_000_000L,
            ip, peerName, source = TransferManager.Source.QUICK_SHARE, qsItemName = "photo.jpg")
        Thread.sleep(400)   // accept-to-dispatch idle time
        val claimed = TransferManager.claimPending("photo.jpg", ip)
        assertEquals(tid, claimed)
        TransferManager.addProgress(tid, 262_144L)
        assertEquals("the first progress after a QS claim must latch, not report",
            0.0, TransferManager.get(tid)!!.speedBps, 0.0)
        Thread.sleep(500)
        TransferManager.addProgress(tid, 200_000L)
        val speed = TransferManager.get(tid)!!.speedBps
        assertTrue("first computed QS download rate must be the true windowed rate (was $speed B/s)",
            speed >= 200_000.0 && speed <= 1_200_000.0)
    }

    // ── 2: §2C — the re-attach immunity verdict ────────────────────────────────

    @Test
    fun `an abort inside the re-attach immunity window resolves interrupted`() {
        val ip = "192.168.43.12"
        TransferManager.browserLivenessProber = { _ -> true }   // the page answers "alive"
        val tid = TransferManager.start(TransferManager.Direction.DOWNLOAD, "big.mkv", 900_000_000L,
            ip, peerName, source = TransferManager.Source.QUICK_SHARE, qsItemName = "big.mkv")
        TransferManager.addProgress(tid, 400_000_000L)          // 44% delivered
        // The page's SSE stream JUST re-attached (the Wi-Fi blip ended).
        TransferManager.onPageReconnected(ip)
        // The blocked write now errors -> the abort verdict fires ~150 ms later.
        TransferManager.downloadConnectionAborted(tid)
        Thread.sleep(700)
        val t = TransferManager.get(tid)!!
        assertTrue("an abort inside the re-attach window must resolve INTERRUPTED, not ${t.status}",
            t.status == TransferManager.Status.FAILED && t.interrupted)
        assertFalse(t.cancelRequested)
    }

    @Test
    fun `a with-bytes abort on a live page defers the conviction and honours no re-request`() {
        val ip = "192.168.43.13"
        TransferManager.browserLivenessProber = { _ -> true }
        TransferManager.browserCancelEvidenceMs = 300L          // test-friendly window
        val tid = TransferManager.start(TransferManager.Direction.DOWNLOAD, "doc.pdf", 10_000_000L,
            ip, peerName, source = TransferManager.Source.QUICK_SHARE, qsItemName = "doc.pdf")
        TransferManager.addProgress(tid, 5_000_000L)            // 50% delivered
        TransferManager.downloadConnectionAborted(tid)
        // Verdict at ~150 ms: interrupted (resumable), conviction deferred.
        Thread.sleep(300)
        val mid = TransferManager.get(tid)!!
        assertTrue("the with-bytes verdict must resolve interrupted first (was ${mid.status})",
            mid.status == TransferManager.Status.FAILED && mid.interrupted)
        // The evidence window lapses with NO re-request -> the deliberate
        // cancel's honest CANCELLED verdict lands (identical end state to the
        // old behavior, a few hundred ms later).
        Thread.sleep(500)
        val end = TransferManager.get(tid)
        assertTrue("the deferred conviction must land once no re-request arrives",
            end == null || end.status == TransferManager.Status.CANCELLED)
    }

    @Test
    fun `the download manager's own re-request dissolves the deferred conviction`() {
        val ip = "192.168.43.14"
        TransferManager.browserLivenessProber = { _ -> true }
        TransferManager.browserCancelEvidenceMs = 600L
        val tid = TransferManager.start(TransferManager.Direction.DOWNLOAD, "album.zip", 20_000_000L,
            ip, peerName, source = TransferManager.Source.QUICK_SHARE, qsItemName = "album.zip")
        TransferManager.addProgress(tid, 8_000_000L)
        TransferManager.downloadConnectionAborted(tid)
        Thread.sleep(350)                                        // verdict ran: interrupted
        assertEquals(TransferManager.Status.FAILED, TransferManager.get(tid)!!.status)
        // The DM's Range re-request re-opens the SAME row in place — the
        // exact path reopenInterruptedDownloadForRange drives.
        assertTrue(TransferManager.reopenForResume(tid))
        Thread.sleep(700)                                        // past the conviction deadline
        val t = TransferManager.get(tid)!!
        assertTrue("a resumed row must never be convicted cancelled by the stale evidence task (was ${t.status})",
            t.status == TransferManager.Status.ACTIVE)
    }

    @Test
    fun `a zero-byte abort on a live page keeps the immediate cancelled verdict`() {
        val ip = "192.168.43.15"
        TransferManager.browserLivenessProber = { _ -> true }
        TransferManager.browserCancelEvidenceMs = 5_000L
        val tid = TransferManager.start(TransferManager.Direction.DOWNLOAD, "note.txt", 4_000L,
            ip, peerName, source = TransferManager.Source.QUICK_SHARE, qsItemName = "note.txt")
        TransferManager.downloadConnectionAborted(tid)
        Thread.sleep(600)
        val t = TransferManager.get(tid)
        assertTrue("a zero-byte abort with a live page is a first-connection cancel (was ${t?.status})",
            t == null || t.status == TransferManager.Status.CANCELLED)
    }
}
