package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ══ FINAL PASS 4 (§2) — the browser-leg streamed-ZIP row is born marked, ══
 * ══ pinned on the JVM. ══
 *
 * FINAL PASS 3 fixed the streamed-ZIP History-selection gate for the NATIVE
 * leg only: the sender's native-push row got isZipStream at mint time
 * (startPendingSend), but a ZIP batch sent through the BROWSER leg
 * (addZipBatchToQuickShare → preRegisterQuickShareSends → claimOrStartPending)
 * minted its Waiting rows WITHOUT the marker — indistinguishable from an
 * ordinary unknown-size file, so the completed row failed the isZipStream-keyed
 * selection eligibility (isRangeSelectableIn) and Hold & Slide / Range skipped
 * it while a plain tap still worked: the same asymmetry, the other leg.
 *
 * These tests pin the engine half: claimOrStartPending/startPending accept and
 * carry zipStream onto the minted row, so the browser leg's pre-registered
 * ZIP-batch rows (and any other caller that passes the flag) are born marked
 * exactly like a native leg's.
 */
class FinalPass4ZipStreamMarkerTest {

    private val peerIp = "192.168.43.45"
    private val peerName = "Kitchen tablet"
    private val zipName = "Photos_more_(27_Items).zip"

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
    }

    @Test
    fun `claimOrStartPending carries zipStream onto the minted row`() {
        // Exactly the addZipBatchToQuickShare call shape (size -1: the
        // archive's own byte count is unknowable before it is streamed).
        val tid = TransferManager.claimOrStartPending(zipName, -1L, peerIp, peerName,
            qsItemName = zipName, zipStream = true)
        assertNotNull(tid)
        val t = TransferManager.get(tid!!)!!
        assertEquals(TransferManager.Status.PENDING, t.status)
        assertTrue("the browser-leg streamed-archive row must be born marked",
            t.isZipStream)
        assertEquals(-1L, t.totalBytes)
        assertEquals(zipName, t.qsItemName)
    }

    @Test
    fun `startPending carries zipStream onto the minted row`() {
        val tid = TransferManager.startPending(TransferManager.Direction.DOWNLOAD, zipName, -1L,
            peerIp, peerName, source = TransferManager.Source.QUICK_SHARE,
            qsItemName = zipName, zipStream = true)
        assertTrue(TransferManager.get(tid)!!.isZipStream)
    }

    @Test
    fun `ordinary rows stay unmarked by default`() {
        val tid = TransferManager.claimOrStartPending("notes.txt", 128L, peerIp, peerName,
            qsItemName = "notes.txt")
        val t = TransferManager.get(tid!!)!!
        assertEquals(false, t.isZipStream)
    }
}
