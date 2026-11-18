package com.lumidrop.x.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ══ GOAT-PASS 15 (§3.4) — the streamed-zip row marker's engine contract ════
 *
 * A ZIP-batch send is ONE unknown-length archive streamed as a single
 * chunked POST. Aborting that stream mid-archive leaves a truncated zip the
 * receiver must restart from byte 0 (unknown-size entries are never
 * byte-resumed by design) — so such a row is deliberately NEVER pausable:
 * neither side's UI offers Pause for it, and the engine's own bulk Pause All
 * skips it (§3.4: "Do not allow a normal ZIP streaming request to enter an
 * unsupported paused state"). Cancel, failure, Resend and completion behave
 * exactly as for any other row.
 *
 * These tests pin the ENGINE half: the marker rides startPendingSend /
 * startPendingUpload / start (the three row factories a streamed zip can
 * flow through), and pauseAllActive refuses to pause a marked row while an
 * unmarked sibling in the very same map pauses normally.
 */
class GoatPass15ZipStreamPolicyTest {

    private val peerIp = "192.168.43.77"
    private val peerName = "Tablet \u2022 LumiDrop"

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
    }

    @Test
    fun `the three row factories carry the marker`() {
        // Sender: the pre-registered send row of the archive (zip mode's
        // one manifest entry, size -1).
        val sendId = TransferManager.startPendingSend("Photos_more_(27_Items).zip", -1L, zipStream = true)
        assertTrue(TransferManager.get(sendId)!!.isZipStream)
        // Receiver: the mirror receive row of the same unknown-size entry.
        val recvId = TransferManager.startPendingUpload(
            requestId = "push-1::Photos_more_(27_Items).zip",
            fileName = "Photos_more_(27_Items).zip", totalBytes = -1L,
            peerIp = peerIp, peerName = peerName,
            source = TransferManager.Source.NATIVE_PUSH, zipStream = true)
        assertTrue(TransferManager.get(recvId)!!.isZipStream)
        // The start() fallback (a re-send-after-discard's fresh row).
        val fallbackId = TransferManager.start(
            TransferManager.Direction.UPLOAD, "Photos_more_(27_Items).zip", -1L,
            peerIp, peerName, source = TransferManager.Source.NATIVE_PUSH,
            uploadRequestId = "push-1::x", zipStream = true)
        assertTrue(TransferManager.get(fallbackId)!!.isZipStream)
    }

    @Test
    fun `ordinary rows never carry the marker`() {
        val plainSend = TransferManager.startPendingSend("IMG_1024.jpg", 18_000_000L)
        val plainRecv = TransferManager.startPendingUpload(
            requestId = "push-2::IMG_1024.jpg", fileName = "IMG_1024.jpg",
            totalBytes = 18_000_000L, peerIp = peerIp, peerName = peerName)
        assertFalse(TransferManager.get(plainSend)!!.isZipStream)
        assertFalse(TransferManager.get(plainRecv)!!.isZipStream)
        // A real .zip FILE with a known size is an ordinary transfer — the
        // marker means "live-synthesized stream", never "name ends in .zip".
        val realZip = TransferManager.startPendingSend("archive.zip", 5_242_880L)
        assertFalse(TransferManager.get(realZip)!!.isZipStream)
    }

    @Test
    fun `pauseAllActive skips the streamed zip but pauses its ordinary sibling`() {
        val zipId = TransferManager.startPendingSend("Batch_(3_Items).zip", -1L, zipStream = true)
        val plainId = TransferManager.startPendingSend("video.mp4", 900_000_000L)
        TransferManager.claimPendingSend(zipId)
        TransferManager.claimPendingSend(plainId)

        TransferManager.pauseAllActive()

        // The streamed archive must NOT be in the unsupported paused state.
        assertFalse("a streamed ZIP must never be paused by Pause All",
            TransferManager.get(zipId)!!.pauseRequested)
        // The ordinary sibling paused exactly as it always has.
        assertTrue(TransferManager.get(plainId)!!.pauseRequested)

        // Resume All still clears any pause that IS set (an older-version
        // peer could set the flag cross-device) — the row can never strand.
        TransferManager.requestPause(zipId)
        assertTrue(TransferManager.get(zipId)!!.pauseRequested)
        TransferManager.resumeAllActive()
        assertFalse(TransferManager.get(zipId)!!.pauseRequested)
    }

    @Test
    fun `a streamed zip completes and cancels like any other row`() {
        val zipId = TransferManager.startPendingSend("Batch_(2_Items).zip", -1L, zipStream = true)
        TransferManager.claimPendingSend(zipId)
        TransferManager.updateProgress(zipId, 5_000_000L)
        TransferManager.complete(zipId)
        org.junit.Assert.assertEquals(
            TransferManager.Status.COMPLETED, TransferManager.get(zipId)!!.status)

        val zip2 = TransferManager.startPendingSend("Batch2.zip", -1L, zipStream = true)
        TransferManager.claimPendingSend(zip2)
        TransferManager.requestCancel(zip2)
        org.junit.Assert.assertEquals(
            TransferManager.Status.CANCELLED, TransferManager.get(zip2)!!.status)
    }
}
