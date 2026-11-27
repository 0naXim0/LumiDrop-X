package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ══ PASS 27 — the demotion-free handoff re-await, pinned on the JVM ═════════
 *
 * THE reported Active→Waiting regression: a direct (browser download-manager
 * intercepted) download's first connection aborts at the interception, and the
 * old re-await logic DEMOTED the row to PENDING — zeroing its byte counter and
 * rendering "Waiting for Safari…" as a QUEUE state — where it read as the
 * transfer visibly leaving Active and, whenever the browser's re-request was
 * not confirmed instantly, WEDGED there at 0 KB (half the stuck-Waiting
 * reports; the other half was the same window simply never resolving).
 *
 * The fix is pinned here: the interception's cancel-then-re-request is the
 * NORMAL direct-download pattern, so the row STAYS ACTIVE through the re-await
 * window — same identity, same card, real bytes preserved — and only the
 * bounded, hard-capped re-await deadline can end it (FAILED-interrupted,
 * honestly, never COMPLETED, never a silent wedge). Regression pins: the
 * modern-browser abort semantics and the delivered-everything completion are
 * untouched.
 */
class Pass27DirectDownloadReliabilityTest {

    private val peerIp = "192.168.43.5"       // the iPad 4
    private val peerName = "iPad \u2022 Safari 10.3.3"

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
        TransferManager.browserLivenessProber = null
    }

    private fun statusOf(tid: String): TransferManager.Status? =
        TransferManager.activeSnapshot().firstOrNull { it.id == tid }?.status
            ?: TransferManager.terminalStateFor(tid)

    /** A handoff-eligible QS receive row mid-stream, exactly as the
     *  interception's first (canceled) connection leaves it. */
    private fun interceptedReceive(fileName: String, total: Long, at: Long): String {
        val qsItem = "qs-$fileName"
        TransferManager.claimOrStartPending(fileName, total, peerIp, peerName, qsItem)
        val tid = TransferManager.claimOrStartActiveQuickShare(
            qsItem, peerIp, fileName, total, peerName, null)
        assertNotNull(tid)
        TransferManager.markHandoffEligible(tid)
        if (at > 0) TransferManager.addProgress(tid, at)
        return tid
    }

    // ── 1. The Active→Waiting fix itself ─────────────────────────────────────

    @Test
    fun `the interception abort keeps the row ACTIVE - no demotion, no byte zeroing`() {
        val tid = interceptedReceive("Album.zip", 30_000_000L, 2_000_000L)
        TransferManager.downloadConnectionAborted(tid)
        // NOT demoted to PENDING — the exact old regression (Active→Waiting).
        assertEquals(TransferManager.Status.ACTIVE, statusOf(tid))
        // The bytes the canceled leg genuinely delivered are preserved — the
        // old demotion zeroed them, which read as "0 KB" forever after.
        val row = TransferManager.activeSnapshot().first { it.id == tid }
        assertEquals(2_000_000L, row.transferredBytes)
        // The re-await window is live (the beat can extend it).
        assertTrue(TransferManager.extendHandoffWindow(tid))
    }

    @Test
    fun `the download manager's re-request keeps the same identity and completes`() {
        val tid = interceptedReceive("Album.zip", 30_000_000L, 2_000_000L)
        TransferManager.downloadConnectionAborted(tid)
        assertEquals(TransferManager.Status.ACTIVE, statusOf(tid))
        // The browser's own session re-requests the SAME URL: the request
        // ATTACHES to the live row (no second identity, no ghost row).
        val tid2 = TransferManager.claimOrStartActiveQuickShare(
            "qs-Album.zip", peerIp, "Album.zip", 30_000_000L, peerName, null)
        assertEquals(tid, tid2)
        // ...delivers the remainder, and the row completes on the full body.
        TransferManager.addProgress(tid, 28_000_000L)
        TransferManager.complete(tid)
        assertEquals(TransferManager.Status.COMPLETED, statusOf(tid))
        // No re-await window survives the delivery.
        assertFalse(TransferManager.extendHandoffWindow(tid))
    }

    @Test
    fun `a re-await that never gets its re-request resolves interrupted, never wedges, never completes`() {
        val tid = interceptedReceive("BigArchive.zip", 30_000_000L, 1_000_000L)
        TransferManager.downloadConnectionAborted(tid)
        assertEquals(TransferManager.Status.ACTIVE, statusOf(tid))
        // The hard-capped window (10 s grace, 5 s sweep cadence, no beats to
        // extend it) must resolve the row FAILED-interrupted — the honest
        // auto-resume regime — never COMPLETED, never an eternal Waiting.
        val deadline = System.currentTimeMillis() + 30_000L
        var st = statusOf(tid)
        while (st == TransferManager.Status.ACTIVE && System.currentTimeMillis() < deadline) {
            Thread.sleep(500L)
            st = statusOf(tid)
        }
        assertEquals(TransferManager.Status.FAILED, st)
        // And the window is gone with the verdict.
        assertFalse(TransferManager.extendHandoffWindow(tid))
    }

    @Test
    fun `a beat extension keeps the re-await honest and still resolves it`() {
        val tid = interceptedReceive("Photo 001.jpg", 5_000L, 0L)
        TransferManager.downloadConnectionAborted(tid)
        // One save-sheet-worth of extension: the deadline moves, but the
        // resolution still lands (the extension is a delay, not an override).
        assertTrue(TransferManager.extendHandoffWindow(tid))
        val deadline = System.currentTimeMillis() + 35_000L
        var st = statusOf(tid)
        while (st == TransferManager.Status.ACTIVE && System.currentTimeMillis() < deadline) {
            Thread.sleep(500L)
            st = statusOf(tid)
        }
        assertEquals(TransferManager.Status.FAILED, st)
    }

    // ── 2. Regression pins — everything outside the fix is unchanged ─────────

    @Test
    fun `a modern (non-handoff) abort still resolves interrupted immediately - headless`() {
        val qsItem = "qs-ModernFile.bin"
        TransferManager.claimOrStartPending("ModernFile.bin", 8_000L, peerIp, peerName, qsItem)
        val tid = TransferManager.claimOrStartActiveQuickShare(
            qsItem, peerIp, "ModernFile.bin", 8_000L, peerName, null)
        TransferManager.addProgress(tid, 4_000L)
        // NO handoff flag — a plain browser download.
        TransferManager.downloadConnectionAborted(tid)
        assertEquals(TransferManager.Status.FAILED, statusOf(tid))
        val row = TransferManager.activeSnapshot().first { it.id == tid }
        assertTrue(row.interrupted)
    }

    @Test
    fun `a delivered-everything abort still completes`() {
        val qsItem = "qs-Complete.bin"
        TransferManager.claimOrStartPending("Complete.bin", 8_000L, peerIp, peerName, qsItem)
        val tid = TransferManager.claimOrStartActiveQuickShare(
            qsItem, peerIp, "Complete.bin", 8_000L, peerName, null)
        TransferManager.addProgress(tid, 8_000L)
        TransferManager.downloadConnectionAborted(tid)
        assertEquals(TransferManager.Status.COMPLETED, statusOf(tid))
    }

    @Test
    fun `a host cancel still outranks the re-await`() {
        val tid = interceptedReceive("CancelledItem.jpg", 500L, 100L)
        TransferManager.downloadConnectionAborted(tid)
        assertEquals(TransferManager.Status.ACTIVE, statusOf(tid))
        TransferManager.requestCancel(tid)
        assertEquals(TransferManager.Status.CANCELLED, statusOf(tid))
        // And a late beat extension is refused on a terminal row.
        assertFalse(TransferManager.extendHandoffWindow(tid))
    }

    @Test
    fun `a plain modern receive still completes on the full body`() {
        val qsItem = "qs-ModernFile2.bin"
        TransferManager.claimOrStartPending("ModernFile2.bin", 8_000L, peerIp, peerName, qsItem)
        val tid = TransferManager.claimOrStartActiveQuickShare(
            qsItem, peerIp, "ModernFile2.bin", 8_000L, peerName, null)
        TransferManager.addProgress(tid, 8_000L)
        TransferManager.complete(tid)
        assertEquals(TransferManager.Status.COMPLETED, statusOf(tid))
        assertNull(TransferManager.activeSnapshot().firstOrNull { it.id == tid })
    }
}
