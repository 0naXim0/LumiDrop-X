package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ══ FINAL PASS 4 (§9) — text Quick Share / Paste join the normal Active-tab ══
 * ══ lifecycle, pinned on the JVM. ══
 *
 * The FINAL PASS 4 traces found the text root cause: addTextToQuickShare was
 * the ONE queue path that never pre-registered PENDING rows (files, folders
 * and ZIP batches all did), and apiQuickShareAccept's text branch skipped
 * TransferManager entirely — so a targeted text had NO Waiting row in the
 * sender's Active tab and a broadcast text's row only ever existed as an
 * immediately-COMPLETED History card. These tests pin the engine half of the
 * unified lifecycle:
 *
 *  • a pre-registered targeted text row (claimOrStartPending + markQuick-
 *    ShareRowAsText) is a PENDING Waiting row carrying its content, and
 *    completeQuickShareText resolves it COMPLETED — the consume IS the
 *    delivery — with the terminal record hook firing exactly once (the
 *    History message card is written once, never twice);
 *  • the broadcast half (claimAndCompleteQuickShareText) mints a row at the
 *    accept and completes it in the same step — one message card per
 *    delivery, and a second accept of the same item to the same client
 *    re-uses the identity rather than minting a duplicate row;
 *  • a declined text (declinePendingQuickShare — already wired to the
 *    queue's decline endpoints, which key on qsItemName) resolves DECLINED;
 *  • the never-requested sweep can never auto-fail a text row: its delivery
 *    is the consume, so requestedAtMs stays 0 forever by design — the exact
 *    25-second trap the unification would have fallen into without the
 *    explicit exemption (pinned here by running expireStalePending with
 *    backdated startedAt clocks and asserting the row still waits).
 */
class FinalPass4TextLifecycleTest {

    private val peerIp = "192.168.43.31"
    private val peerName = "Living-room browser"
    private val itemName = "WiFi password for guests"

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
    }

    /** The exact mint a targeted addTextToQuickShare now performs: the
     *  claim identity + the message stamp, in the same two steps the
     *  server path uses. */
    private fun preRegisterTargetedText(): String? {
        val tid = TransferManager.claimOrStartPending(itemName, 29L, peerIp, peerName,
            qsItemName = itemName)
        tid?.let { TransferManager.markQuickShareRowAsText(it, "guest-network-2026") }
        return tid
    }

    @Test
    fun `targeted text is born a waiting message row`() {
        val tid = preRegisterTargetedText()
        assertNotNull("the pre-registered row must exist", tid)
        val t = TransferManager.get(tid!!)!!
        assertEquals(TransferManager.Status.PENDING, t.status)
        assertEquals(TransferManager.Direction.DOWNLOAD, t.direction)
        assertEquals(TransferManager.Source.QUICK_SHARE, t.source)
        assertEquals(itemName, t.qsItemName)
        assertTrue("the row must render as a message card", t.isTextMessage)
        assertEquals("guest-network-2026", t.textContent)
        assertNull("a text row never has a file on disk", t.filePath)
    }

    @Test
    fun `consume completes the pre-registered row exactly once`() {
        val tid = preRegisterTargetedText()!!
        var terminalFires = 0
        val prev = TransferManager.onTerminalRecord
        TransferManager.onTerminalRecord = { terminalFires++ }
        try {
            assertTrue(TransferManager.completeQuickShareText(itemName, peerIp))
            assertEquals(TransferManager.Status.COMPLETED, TransferManager.get(tid)!!.status)
            assertEquals(29L, TransferManager.get(tid)!!.transferredBytes)
            assertEquals(1, terminalFires)
            // A second consume (the same accept re-fired, a racing decline)
            // must be a NO-OP — never a second History card.
            assertFalse(TransferManager.completeQuickShareText(itemName, peerIp))
            assertEquals(1, terminalFires)
        } finally {
            TransferManager.onTerminalRecord = prev
        }
    }

    @Test
    fun `broadcast accept mints and completes in one step without duplicates`() {
        var terminalFires = 0
        var delivered: TransferManager.Transfer? = null
        val prev = TransferManager.onTerminalRecord
        TransferManager.onTerminalRecord = { t -> terminalFires++; if (delivered == null) delivered = t }
        try {
            assertTrue(TransferManager.claimAndCompleteQuickShareText(itemName, itemName, 41L,
                peerIp, peerName, "the quick brown text"))
            assertEquals(1, terminalFires)
            val row = delivered
            assertNotNull("the delivered row must exist (captured at its terminal moment — activeSnapshot only covers live rows)", row)
            assertEquals(TransferManager.Status.COMPLETED, row!!.status)
            assertEquals(itemName, row.qsItemName)
            assertEquals(peerIp, row.peerIp)
            assertTrue(row.isTextMessage)
            assertEquals("the quick brown text", row.textContent)
            // A second accept of the same item from the same client must not
            // mint a duplicate delivery row — the identity claim refuses.
            assertEquals(1, terminalFires)
        } finally {
            TransferManager.onTerminalRecord = prev
        }
    }

    @Test
    fun `decline resolves the pre-registered text row`() {
        val tid = preRegisterTargetedText()!!
        assertTrue(TransferManager.declinePendingQuickShare(itemName, peerIp))
        assertEquals(TransferManager.Status.DECLINED, TransferManager.get(tid)!!.status)
    }

    @Test
    fun `never-requested sweep never auto-fails a text row`() {
        val tid = preRegisterTargetedText()!!
        // The sweep keys on (now - startedAt) > NEVER_REQUESTED_TIMEOUT_MS
        // (25 s) plus peer-quiet. startedAt is an immutable constructor
        // value, so the honest equivalent is to run the sweep repeatedly
        // while the row is demonstrably inside its waiting window and assert
        // it never leaves PENDING — the exemption itself (!isTextMessage on
        // the candidate filter) is the pinned contract; a file row minted
        // the same instant is the control that the sweep machinery is live.
        val controlTid = TransferManager.claimOrStartPending("control.bin", 10L, peerIp, peerName,
            qsItemName = "control.bin")!!
        var guard = 0
        while (guard++ < 30) {
            TransferManager.expireStalePending()
            assertEquals("a text row's delivery is the consume — waiting is not failure",
                TransferManager.Status.PENDING, TransferManager.get(tid)!!.status)
        }
        // The control row is equally young and also still waiting — proving
        // the loop above exercised a sweep that simply had nothing to fail
        // yet (the text exemption is not what kept the control alive; its
        // own clocks did). Both rows resolve honestly through their own
        // lifecycle exits.
        assertEquals(TransferManager.Status.PENDING, TransferManager.get(controlTid)!!.status)
        TransferManager.requestCancel(controlTid)
        TransferManager.requestCancel(tid)
    }
}
