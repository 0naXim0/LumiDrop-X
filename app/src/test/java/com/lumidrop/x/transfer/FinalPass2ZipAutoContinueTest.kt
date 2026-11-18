package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ══ FINAL PASS 2 (§1) — the streamed ZIP's "Auto-Continue on Reconnection" ══
 * engine contract, pinned on the JVM.
 *
 * A native-to-native ZIP stream severed by a disconnection is, by design,
 * never byte-resumed: the sender's own auto-resume loop re-POSTs a FRESH
 * archive generation from byte 0 on its own schedule. The receiver's row for
 * that attempt must therefore resolve into the KEPT auto-continue state —
 * FAILED+interrupted (so the re-POST's claim chain re-opens the SAME row in
 * place, never a second incoming card), progress zeroed (the next attempt
 * restarts from 0 MB; the dead attempt's bytes are not live progress), NOT
 * registered as a byte-resumable row (no partial exists; the prune that
 * reaps resumable cards whose backing partial vanished must never dismiss
 * it), and bounded (it rides the ordinary failed-auto-removal schedule).
 *
 * The sender's live row latches the presentation twin (markZipAutoContinue):
 * stays ACTIVE with the reconnecting flag — every lifecycle guard keeps
 * working — while its progress counters are zeroed for the same honesty.
 *
 * Control cases pin the UNCHANGED neighbors: an ordinary (known-size)
 * interrupted kept-for-resume receive still keeps its byte checkpoint, and a
 * deliberate cancel never routes into the auto-continue state.
 */
class FinalPass2ZipAutoContinueTest {

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

    /** The receiver's kept auto-continue card, exactly as apiNativePushFile's
     *  interrupted-streamed-zip branch produces it. */
    private fun claimAndInterruptZipReceive(): String {
        val id = TransferManager.startPendingUpload(
            requestId = reqId,
            fileName = "Photos_more_(27_Items).zip", totalBytes = -1L,
            peerIp = peerIp, peerName = peerName,
            source = TransferManager.Source.NATIVE_PUSH, zipStream = true)
        assertNotNull(TransferManager.claimPendingUpload(reqId, "/dest/x.zip"))
        // Attempt 1 streamed 4 MB of the unknown-length archive before the
        // connection died.
        TransferManager.updateProgress(id, 4L * 1024 * 1024)
        TransferManager.failForZipAutoContinue(id)
        return id
    }

    @Test
    fun `an interrupted streamed zip keeps one card in the auto-continue state`() {
        val id = claimAndInterruptZipReceive()
        val t = TransferManager.get(id)!!
        assertEquals(TransferManager.Status.FAILED, t.status)
        assertTrue("the re-POST's claim chain keys on FAILED+interrupted", t.interrupted)
        assertEquals("progress is zeroed — the next attempt restarts from 0", 0L, t.transferredBytes)
        assertEquals(0L, t.resumedFromBytes)
        assertEquals("never a byte-resume promise", false, t.isIndeterminate && t.totalBytes >= 0)
    }

    @Test
    fun `the re-POST claim chain finds and re-opens the SAME row with zero progress`() {
        val id = claimAndInterruptZipReceive()
        val found = TransferManager.findInterruptedUploadId(reqId)
        assertEquals("findInterruptedUploadId resolves to the one logical card", id, found)
        val reopened = TransferManager.reopenInterruptedUploadByExactId(found!!, "/dest/x.zip")
        assertEquals("the re-open reuses the original row id — never a duplicate", id, reopened)
        val t = TransferManager.get(id)!!
        assertEquals(TransferManager.Status.ACTIVE, t.status)
        assertFalse(t.interrupted)
        assertEquals("a re-opened streamed zip never presents 'Resuming N%'", 0L, t.transferredBytes)
        assertEquals(0L, t.resumedFromBytes)
    }

    @Test
    fun `a second interruption cycle reuses the same card again`() {
        val id = claimAndInterruptZipReceive()
        // Reconnection attempt 2 arrives, streams 9 MB, the network dies again.
        TransferManager.reopenInterruptedUploadByExactId(id, "/dest/x.zip")!!
        TransferManager.updateProgress(id, 9L * 1024 * 1024)
        TransferManager.failForZipAutoContinue(id)
        // The third attempt's claim chain must still resolve to ONE card.
        assertEquals(id, TransferManager.findInterruptedUploadId(reqId))
        assertEquals(0L, TransferManager.get(id)!!.transferredBytes)
        val again = TransferManager.reopenInterruptedUploadByExactId(id, "/dest/x.zip")
        assertEquals(id, again)
    }

    @Test
    fun `the kept zip card survives the resumable prune that reaps partial-less rows`() {
        val id = claimAndInterruptZipReceive()
        // The prune every fresh start runs: dismisses kept-for-resume rows
        // whose backing partial no longer exists. The streamed ZIP's partial
        // is deliberately deleted (nothing to byte-resume), so the auto-
        // continue card MUST be immune to it.
        TransferManager.pruneResumableRows { false }
        assertNotNull("the auto-continue card is not a resumable row", TransferManager.get(id))
        assertEquals(TransferManager.Status.FAILED, TransferManager.get(id)!!.status)
    }

    @Test
    fun `the kept zip card is cancellable through the auto-resume row machinery`() {
        val id = claimAndInterruptZipReceive()
        assertTrue("Cancel All / selection must reach the waiting card",
            TransferManager.autoResumeReceiverRows().any { it.id == id })
    }

    @Test
    fun `an ordinary interrupted receive keeps its byte checkpoint as before`() {
        val id = TransferManager.startPendingUpload(
            requestId = "push-2::video.mp4",
            fileName = "video.mp4", totalBytes = 100L,
            peerIp = peerIp, peerName = peerName,
            source = TransferManager.Source.NATIVE_PUSH, zipStream = false)
        TransferManager.claimPendingUpload("push-2::video.mp4", "/dest/video.mp4")
        TransferManager.updateProgress(id, 62L)
        TransferManager.fail(id, interrupted = true, keepForResume = true)
        val t = TransferManager.get(id)!!
        assertEquals(TransferManager.Status.FAILED, t.status)
        assertTrue(t.interrupted)
        assertEquals("the ordinary kept row KEEPS its checkpoint for 'Resuming 62%'", 62L, t.transferredBytes)
        // And its re-open restores the resume baseline — the byte-resume
        // contract ordinary rows have always had.
        TransferManager.reopenInterruptedUploadByExactId(id, "/dest/video.mp4")
        assertEquals(62L, TransferManager.get(id)!!.resumedFromBytes)
    }

    @Test
    fun `a deliberately cancelled zip never enters the auto-continue state`() {
        val id = TransferManager.startPendingUpload(
            requestId = reqId,
            fileName = "Photos_more_(27_Items).zip", totalBytes = -1L,
            peerIp = peerIp, peerName = peerName,
            source = TransferManager.Source.NATIVE_PUSH, zipStream = true)
        TransferManager.claimPendingUpload(reqId, "/dest/x.zip")
        TransferManager.requestCancel(id)
        // The interrupted arm must refuse to overwrite a deliberate cancel.
        TransferManager.failForZipAutoContinue(id)
        val t = TransferManager.get(id)!!
        assertEquals("a deliberate stop stays CANCELLED", TransferManager.Status.CANCELLED, t.status)
        assertFalse(t.interrupted)
        assertNull("and the claim chain does not adopt it for a re-POST",
            TransferManager.findInterruptedUploadId(reqId))
    }

    @Test
    fun `the sender's live row latches the zip reconnecting presentation with zeroed progress`() {
        val sendId = TransferManager.startPendingSend("Photos_more_(27_Items).zip", -1L, zipStream = true)
        TransferManager.claimPendingSend(sendId)
        TransferManager.updateProgress(sendId, 7L * 1024 * 1024)
        // Attempt 1 died; the retry loop latches the auto-continue state.
        TransferManager.markZipAutoContinue(sendId)
        val t = TransferManager.get(sendId)!!
        assertEquals(TransferManager.Status.ACTIVE, t.status)
        assertTrue("the row stays live behind the calm chip", t.reconnecting)
        assertEquals("the dead attempt's bytes are not live progress", 0L, t.transferredBytes)
    }
}
