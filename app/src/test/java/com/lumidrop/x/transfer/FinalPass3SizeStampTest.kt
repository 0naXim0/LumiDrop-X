package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ══ FINAL PASS 3 (§1) — the real final size of a completed ZIP-streamed ══
 * ══ transfer, pinned on the JVM. ══
 *
 * An unknown-length, live-synthesized archive row carries totalBytes = -1
 * for its whole life (the honest live "35.2 MB / ?" presentation). The
 * moment such a transfer genuinely completes, the completion path stamps
 * the REAL final byte count onto the row via TransferManager.stampFinalSize
 * — from the received file's own length on the receiver, or from the exact
 * streamed byte count on the sender — BEFORE complete(), so
 * finalizeCompletedBody and the HistoryStore hook (onTerminalRecord) both
 * see the actual figure. The Received tab's live row, the History tab's
 * merged rows and the persisted history entry therefore render the true
 * size instead of "?".
 *
 * These tests pin the engine half of that contract:
 *  • stampFinalSize replaces a -1 total with the real figure and syncs the
 *    byte counter, so a completed card reads size/size;
 *  • it NEVER rewrites a known declared total (a -1 stamp must not be able
 *    to corrupt an ordinary transfer's exact size);
 *  • it no-ops on a non-positive figure (nothing truthful to say → the
 *    honest "?" stands);
 *  • the full receive flow (claim → stream → stamp → complete) lands one
 *    COMPLETED row whose total is the stamped size, and the terminal
 *    record hook — the exact moment HistoryStore persists its entry —
 *    observes the stamped figure;
 *  • the re-opened auto-continue card (a severed zip re-POSTed from 0)
 *    completes with its OWN attempt's real size — never the dead
 *    attempt's, never "?";
 *  • a -1 row completed WITHOUT a stamp (a legacy path) keeps its honest
 *    "?" — nothing about the existing un-stamped behavior changes.
 */
class FinalPass3SizeStampTest {

    private val peerIp = "192.168.43.77"
    private val peerName = "Tablet \u2022 LumiDrop"
    private val reqId = "push-zip::Photos_more_(27_Items).zip"

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
    }

    /** A claimed streamed-zip receive row, exactly as apiNativePushFile's
     *  unknown-length path produces it (totalBytes = -1, zipStream = true). */
    private fun claimZipReceive(): String {
        val id = TransferManager.startPendingUpload(
            requestId = reqId,
            fileName = "Photos_more_(27_Items).zip", totalBytes = -1L,
            peerIp = peerIp, peerName = peerName,
            source = TransferManager.Source.NATIVE_PUSH, zipStream = true)
        assertNotNull(TransferManager.claimPendingUpload(reqId, "/dest/x.zip"))
        return id
    }

    @Test
    fun `stampFinalSize replaces the unknown total with the real final size`() {
        val id = claimZipReceive()
        TransferManager.updateProgress(id, 4L * 1024 * 1024)
        TransferManager.stampFinalSize(id, 9_437_184L)
        val t = TransferManager.get(id)!!
        assertEquals("the real final size replaces the -1", 9_437_184L, t.totalBytes)
        assertEquals("the byte counter is synced to the same figure", 9_437_184L, t.transferredBytes)
    }

    @Test
    fun `stampFinalSize never rewrites a known declared total`() {
        val id = TransferManager.startPendingUpload(
            requestId = "push::movie.mp4", fileName = "movie.mp4", totalBytes = 70_000_000L,
            peerIp = peerIp, peerName = peerName, source = TransferManager.Source.NATIVE_PUSH)
        assertNotNull(TransferManager.claimPendingUpload("push::movie.mp4", "/dest/movie.mp4"))
        TransferManager.updateProgress(id, 69_999_000L)
        // Even a racing/late stamp with a different figure must be a no-op:
        // a declared total is already exact.
        TransferManager.stampFinalSize(id, 1L)
        val t = TransferManager.get(id)!!
        assertEquals(70_000_000L, t.totalBytes)
    }

    @Test
    fun `stampFinalSize ignores a non-positive figure`() {
        val id = claimZipReceive()
        TransferManager.updateProgress(id, 1024L)
        TransferManager.stampFinalSize(id, 0L)
        TransferManager.stampFinalSize(id, -5L)
        val t = TransferManager.get(id)!!
        assertEquals("nothing truthful to stamp — the honest unknown stands", -1L, t.totalBytes)
    }

    @Test
    fun `the full receive flow completes with the stamped size and records it once`() {
        val id = claimZipReceive()
        TransferManager.updateProgress(id, 5_000_000L)
        // The completion-path stamp (the received file's own length), then
        // the completion itself — exactly apiNativePushFile's order.
        var hookSawTotal: Long = Long.MIN_VALUE
        var hookCalls = 0
        TransferManager.onTerminalRecord = { t ->
            hookCalls++
            hookSawTotal = t.totalBytes
        }
        try {
            TransferManager.stampFinalSize(id, 9_437_184L)
            TransferManager.complete(id)
        } finally {
            TransferManager.onTerminalRecord = null
        }
        val t = TransferManager.get(id)!!
        assertEquals(TransferManager.Status.COMPLETED, t.status)
        assertEquals("the completed card's total is the real size", 9_437_184L, t.totalBytes)
        assertEquals("…and its counter reads size/size, never size/?", 9_437_184L, t.transferredBytes)
        assertEquals("the terminal record hook fired exactly once", 1, hookCalls)
        assertEquals("HistoryStore's entry would persist the real figure (it reads t.totalBytes)",
            9_437_184L, hookSawTotal)
    }

    @Test
    fun `a re-opened auto-continue zip completes with its own attempt's size`() {
        // Attempt 1: severed at 4 MB, resolved into the kept auto-continue card.
        val id = claimZipReceive()
        TransferManager.updateProgress(id, 4L * 1024 * 1024)
        TransferManager.failForZipAutoContinue(id)
        // Attempt 2: the re-POST re-opens the SAME row from 0 and streams the
        // full archive; the completion stamps the real figure.
        assertNotNull(TransferManager.reopenInterruptedUploadByExactId(id, "/dest/x.zip"))
        TransferManager.updateProgress(id, 9_437_184L)
        TransferManager.stampFinalSize(id, 9_437_184L)
        TransferManager.complete(id)
        val t = TransferManager.get(id)!!
        assertEquals(TransferManager.Status.COMPLETED, t.status)
        assertEquals(9_437_184L, t.totalBytes)
        assertEquals(9_437_184L, t.transferredBytes)
    }

    @Test
    fun `an un-stamped -1 row still completes with its honest unknown total`() {
        // The legacy shape (a path that never stamps, or a stamp that no-ops
        // because no truthful figure existed): nothing changes for it.
        val id = claimZipReceive()
        TransferManager.updateProgress(id, 123L)
        TransferManager.complete(id)
        val t = TransferManager.get(id)!!
        assertEquals(TransferManager.Status.COMPLETED, t.status)
        assertEquals(-1L, t.totalBytes)
    }
}
