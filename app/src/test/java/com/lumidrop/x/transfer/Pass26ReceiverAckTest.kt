package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ══ PASS 26 — receiver-acknowledged completion, pinned on the JVM ═══════════
 *
 * THE reported failure set: Android→iPad 4 (stock iOS 10.3.3 Safari) sends —
 * small transfers showed "Completed" on the sender while the iPad had
 * received nothing at all, large transfers sat stuck at "Waiting / 0 KB /
 * 0 KB/s", and some transfers visibly started then flipped straight back to
 * "Waiting".
 *
 * The root cause, traced end to end: the legacy iOS receive pump dispatched
 * a BLIND octet=1&handoff=1 interception iframe — a protocol built for a
 * jailbreak download manager that a stock iPad 4 Safari simply does not
 * have. WebKit silently slurped small attachment bodies into memory and
 * discarded them (the host's socket-write completion then marked the
 * transfer COMPLETED — falsely), and aborted large ones mid-stream (the
 * host's handoff re-await flipped the row ACTIVE→PENDING "Waiting for
 * Safari…" for a download-manager re-request no stock browser ever sends —
 * the stuck "Waiting"/Active→Waiting regression, exactly).
 *
 * The fix's engine half is pinned here: a download request the web client
 * flags recv=1 (the page's OWN tracked receive — the only receive a
 * browser with no download manager genuinely has) marks its transfer
 * ack-expected, and the row may then NEVER complete on socket-write
 * evidence alone — only the receiver's own finalization confirmation
 * (TransferManager.ackDownloadReceive, POSTed by the page once its load
 * genuinely finished) completes it. Attempt-scoped, peer-validated,
 * idempotent, and immune to late/stale acks. Modern-browser and
 * download-manager (octet/handoff) requests never set the flag, so their
 * completion semantics are bit-for-bit unchanged — pinned here too.
 */
class Pass26ReceiverAckTest {

    private val peerIp = "192.168.43.5"       // the iPad 4
    private val otherIp = "192.168.43.9"      // another receiving device
    private val peerName = "iPad \u2022 Safari 10.3.3"

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
        // A prober left set by an earlier test class in this JVM (e.g.
        // GoatPass7EngineTest) would send the abort path into the verdict
        // regime instead of the deterministic headless interruption —
        // mirror TransferManagerTest's own reset so the abort-shape tests
        // below resolve exactly as the headless path does.
        TransferManager.browserLivenessProber = null
    }

    /** Mirrors the real QS receive flow: /accept pre-registers the PENDING
     *  row, the pump's recv=1 GET claims it ACTIVE and marks it
     *  ack-expected, then the streamed bytes arrive. Returns the tid. */
    private fun claimedAckedReceive(fileName: String, total: Long, deliverBytes: Long = total): String {
        val qsItem = "qs-$fileName"
        TransferManager.claimOrStartPending(fileName, total, peerIp, peerName, qsItem)
        val tid = TransferManager.claimOrStartActiveQuickShare(
            qsItem, peerIp, fileName, total, peerName, null)
        assertNotNull(tid)
        TransferManager.markDownloadAckExpected(tid)
        if (deliverBytes > 0) TransferManager.addProgress(tid, deliverBytes)
        return tid
    }

    private fun statusOf(tid: String): TransferManager.Status? =
        TransferManager.activeSnapshot().firstOrNull { it.id == tid }?.status
            ?: TransferManager.terminalStateFor(tid)

    // ── 1. The false-Completed root fix: socket-write evidence alone can ───
    //    no longer complete a receiver-acked receive.

    @Test
    fun `socket-write completion does not complete an ack-expected receive`() {
        val tid = claimedAckedReceive("Photo.jpg", 10_000L)
        // The stream delivered every byte and the terminal success callback
        // fired (exactly what a stock Safari's silent slurp used to look
        // like) — the row must stay a live receive awaiting the receiver's
        // own confirmation, NOT turn COMPLETED.
        TransferManager.complete(tid)
        assertEquals(TransferManager.Status.ACTIVE, statusOf(tid))
    }

    @Test
    fun `the receiver's finalization ack completes the receive`() {
        val tid = claimedAckedReceive("Photo.jpg", 10_000L)
        val gen = TransferManager.resumeGenOf(tid)
        TransferManager.complete(tid)
        assertTrue(TransferManager.ackDownloadReceive(tid, gen, peerIp))
        assertEquals(TransferManager.Status.COMPLETED, statusOf(tid))
        // The completed row carries the real delivered size.
        val row = TransferManager.completedSnapshot().first { it.id == tid }
        assertEquals(10_000L, row.totalBytes)
        assertEquals(10_000L, row.transferredBytes)
    }

    @Test
    fun `a duplicate ack is an idempotent no-op`() {
        val tid = claimedAckedReceive("Archive.zip", 30_000_000L)
        val gen = TransferManager.resumeGenOf(tid)
        TransferManager.complete(tid)
        assertTrue(TransferManager.ackDownloadReceive(tid, gen, peerIp))
        // The page's retry of the ack POST (a duplicate completion event)
        // must neither corrupt the completed state nor double-count.
        assertFalse(TransferManager.ackDownloadReceive(tid, gen, peerIp))
        assertEquals(TransferManager.Status.COMPLETED, statusOf(tid))
    }

    // ── 2. Attempt safety: events from previous attempts cannot overwrite ───
    //    the current attempt's state.

    @Test
    fun `a stale-attempt ack cannot complete the newer attempt`() {
        val qsItem = "qs-Album.zip"
        TransferManager.claimOrStartPending("Album.zip", 5_000L, peerIp, peerName, qsItem)
        val tid = TransferManager.claimOrStartActiveQuickShare(
            qsItem, peerIp, "Album.zip", 5_000L, peerName, null)
        TransferManager.markDownloadAckExpected(tid)
        TransferManager.addProgress(tid, 2_000L)
        val oldGen = TransferManager.resumeGenOf(tid)
        // Attempt 1 dies mid-stream (the test JVM wires no liveness prober,
        // so the abort resolves interrupted immediately — the honest shape).
        TransferManager.downloadConnectionAborted(tid)
        assertEquals(TransferManager.Status.FAILED, statusOf(tid))
        // The re-dispatch re-opens THE SAME row in place (gen bumped) and
        // its fresh recv=1 request re-marks it.
        TransferManager.reopenInterruptedDownloadForRange(qsItem, "Album.zip", peerIp, 0L)
        TransferManager.markDownloadAckExpected(tid)
        assertEquals(TransferManager.Status.ACTIVE, statusOf(tid))
        // The DEAD attempt's late ack must be refused…
        assertFalse(TransferManager.ackDownloadReceive(tid, oldGen, peerIp))
        assertEquals(TransferManager.Status.ACTIVE, statusOf(tid))
        // …while the new attempt's own ack completes it.
        assertTrue(TransferManager.ackDownloadReceive(tid, TransferManager.resumeGenOf(tid), peerIp))
        assertEquals(TransferManager.Status.COMPLETED, statusOf(tid))
    }

    @Test
    fun `an ack from a different peer is refused`() {
        val tid = claimedAckedReceive("Notes.txt.b64", 1_234L)
        val gen = TransferManager.resumeGenOf(tid)
        TransferManager.complete(tid)
        // Another client's stale/confused ack must never complete this
        // client's row.
        assertFalse(TransferManager.ackDownloadReceive(tid, gen, otherIp))
        assertEquals(TransferManager.Status.ACTIVE, statusOf(tid))
        assertTrue(TransferManager.ackDownloadReceive(tid, gen, peerIp))
        assertEquals(TransferManager.Status.COMPLETED, statusOf(tid))
    }

    @Test
    fun `a host cancel outranks any late ack`() {
        val tid = claimedAckedReceive("CancelledItem.jpg", 500L)
        val gen = TransferManager.resumeGenOf(tid)
        TransferManager.requestCancel(tid)
        assertEquals(TransferManager.Status.CANCELLED, statusOf(tid))
        // The cancelled attempt's late confirmation changes nothing.
        assertFalse(TransferManager.ackDownloadReceive(tid, gen, peerIp))
        assertEquals(TransferManager.Status.CANCELLED, statusOf(tid))
    }

    // ── 3. The abort/teardown shapes must not fake completion either. ───────

    @Test
    fun `a delivered-everything abort holds for the ack instead of completing`() {
        // The exact Test-A/B/D shape on a stock iPad: every byte left the
        // socket (the terminal write succeeded or a sibling aborted after
        // full delivery) — previously the row went COMPLETED right here
        // while the iPad had nothing. Now the bytes-out evidence merely arms
        // the ack window; only the receiver's confirmation completes.
        val tid = claimedAckedReceive("SmallFolder.zip", 4_096L)
        val gen = TransferManager.resumeGenOf(tid)
        TransferManager.downloadConnectionAborted(tid)
        assertEquals(TransferManager.Status.ACTIVE, statusOf(tid))
        assertTrue(TransferManager.ackDownloadReceive(tid, gen, peerIp))
        assertEquals(TransferManager.Status.COMPLETED, statusOf(tid))
    }

    @Test
    fun `an ack that beats the terminal callback still completes and the late callback no-ops`() {
        // The client's onload (and its ack POST) can land a hair before the
        // server-side stream-close callback runs complete(). The ack is the
        // stronger signal (the page PROVES it holds every byte); the later
        // complete() must be a harmless no-op, never a crash or a re-open.
        val tid = claimedAckedReceive("TinyFile.ktx", 64L)
        val gen = TransferManager.resumeGenOf(tid)
        assertTrue(TransferManager.ackDownloadReceive(tid, gen, peerIp))
        assertEquals(TransferManager.Status.COMPLETED, statusOf(tid))
        TransferManager.complete(tid)
        assertEquals(TransferManager.Status.COMPLETED, statusOf(tid))
    }

    // ── 4. The honest deadline: an unconfirmed receive resolves interrupted, ──
    //    never completed.

    @Test(timeout = 60_000L)
    fun `an ack window that closes with no confirmation resolves interrupted, never completed`() {
        val tid = claimedAckedReceive("BigArchive.zip", 30_000_000L)
        val gen = TransferManager.resumeGenOf(tid)
        TransferManager.complete(tid)
        assertEquals(TransferManager.Status.ACTIVE, statusOf(tid))
        // The page died between receiving the last byte and confirming: the
        // stall sweep must resolve the row FAILED-interrupted (the existing
        // auto-resume regime owns the recovery), never COMPLETED. The window
        // is 20s at a 5s sweep cadence — poll up to 45s for the verdict.
        val deadline = System.currentTimeMillis() + 45_000L
        var st = statusOf(tid)
        while (st == TransferManager.Status.ACTIVE && System.currentTimeMillis() < deadline) {
            Thread.sleep(500L)
            st = statusOf(tid)
        }
        // And a VERY late ack for the now-failed attempt still changes nothing.
        assertFalse(TransferManager.ackDownloadReceive(tid, gen, peerIp))
        assertEquals(TransferManager.Status.FAILED, st)
    }

    // ── 5. Regression pins: every other completion path is unchanged. ──────

    @Test
    fun `a plain modern-browser receive still completes on socket write`() {
        // No recv flag — a modern Chrome/Safari a[download] request. Its
        // download manager's save IS the finalization; the row completes at
        // the terminal write exactly as before Pass 26.
        val qsItem = "qs-ModernFile.bin"
        TransferManager.claimOrStartPending("ModernFile.bin", 8_000L, peerIp, peerName, qsItem)
        val tid = TransferManager.claimOrStartActiveQuickShare(
            qsItem, peerIp, "ModernFile.bin", 8_000L, peerName, null)
        TransferManager.addProgress(tid, 8_000L)
        TransferManager.complete(tid)
        assertEquals(TransferManager.Status.COMPLETED, statusOf(tid))
    }

    @Test
    fun `an ack-less receive ignores the ack endpoint entirely`() {
        // The modern row never set ackExpected: even a (buggy or hostile)
        // ack POST naming it must be a plain no-op — the modern pipeline
        // cannot be completed out from under its own download manager.
        val qsItem = "qs-ModernFile2.bin"
        TransferManager.claimOrStartPending("ModernFile2.bin", 8_000L, peerIp, peerName, qsItem)
        val tid = TransferManager.claimOrStartActiveQuickShare(
            qsItem, peerIp, "ModernFile2.bin", 8_000L, peerName, null)
        TransferManager.addProgress(tid, 4_000L)
        assertFalse(TransferManager.ackDownloadReceive(tid, TransferManager.resumeGenOf(tid), peerIp))
        assertEquals(TransferManager.Status.ACTIVE, statusOf(tid))
    }
}
