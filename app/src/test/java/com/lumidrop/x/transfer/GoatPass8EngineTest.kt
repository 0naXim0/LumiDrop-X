package com.lumidrop.x.transfer

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ══ GOAT-PASS 8 (§11) — the NETWORK abort rule of the send-abort watchdog ══
 *
 * Pins the new reconciliation branch: a SEND leg whose row is latched
 * reconnecting (the stall machinery's probe-backed "peer unreachable"
 * verdict, or the LAN-transition latch) AND whose bytes have been silent
 * past [SendAbortWatchdog.networkAbortSilenceMs] gets its connection
 * aborted with Cause.NETWORK — handing recovery to the sendOneFile retry
 * loop's bounded-backoff, fresh-socket resume instead of waiting out TCP's
 * minute-scale retransmission backoff on a connection that is not coming
 * back (the reported "transfer only progressed again after 35–45 s or
 * longer after Wi-Fi returned").
 *
 * Also pins the negatives: a live-streaming row (bytes still refreshing the
 * sample clock) is never touched, and a reconnecting row whose bytes
 * RESUMED (the latch cleared) is never touched — the rule can only ever
 * fire on a provably silent, provably disconnected leg. A settled pause
 * keeps its dedicated PAUSE classification ahead of the network rule.
 *
 * Same shape as SendAbortWatchdogTest (framework-free TransferManager
 * only): rows are minted exactly the way sendOneFile's claim chain mints
 * them, the sweep is driven manually, and the cause is consumed from the
 * handle the disconnect action would have been fired for.
 */
class GoatPass8EngineTest {

    private val ip = "192.168.49.2"
    private val peer = "Pixel • LumiDrop"

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
    }

    @After
    fun cleanEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
    }

    /** Creates a row exactly the way sendOneFile's claim chain does for a
     *  native send, and registers a "connection" for it. */
    private fun leg(fileName: String): Pair<String, SendAbortWatchdog.Handle> {
        val tid = TransferManager.start(
            TransferManager.Direction.SEND, fileName, 10_000L, ip, peer,
            source = TransferManager.Source.NATIVE_PUSH, filePath = "/sdcard/$fileName")
        val handle = SendAbortWatchdog.register(tid) { }
        return tid to handle
    }

    /** Ages a row's last-sample clock (its "last real activity" signal). */
    private fun ageSilence(id: String, ageMs: Long) {
        val t = TransferManager.get(id)!!
        t.lastSampleAtMs = System.currentTimeMillis() - ageMs
    }

    @Test
    fun `network abort fires for a reconnecting silent send`() {
        val (tid, handle) = leg("silent.dat")
        TransferManager.updateProgress(tid, 4_000L)   // mid-stream
        // The peer was probed unreachable: the stall machinery latched
        // reconnecting; the write sits wedged in TCP retransmission.
        TransferManager.markReconnecting(tid)
        ageSilence(tid, SendAbortWatchdog.networkAbortSilenceMs + 1_000L)
        SendAbortWatchdog.sweep()
        assertEquals("the wedged write must be aborted onto the retry loop",
            SendAbortWatchdog.Cause.NETWORK, handle.consumeCause())
        // The row itself stays ACTIVE (presentation-only latch) — the retry
        // loop that streamFile's RETRYABLE classification runs owns the rest.
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(tid)!!.status)
        handle.close()
    }

    @Test
    fun `network abort never fires while bytes still flow`() {
        val (tid, handle) = leg("flowing.dat")
        // Reconnecting latched, but progress kept refreshing the sample
        // clock (a healthy stream that merely answered a probe late).
        TransferManager.markReconnecting(tid)
        TransferManager.updateProgress(tid, 10L)   // also clears the latch
        ageSilence(tid, 1_000L)                     // and bytes are recent
        SendAbortWatchdog.sweep()
        assertNull("a live stream must never be aborted", handle.consumeCause())
        handle.close()
    }

    @Test
    fun `network abort never fires after bytes resumed`() {
        val (tid, handle) = leg("resumed.dat")
        // The row was latched reconnecting and went silent…
        TransferManager.markReconnecting(tid)
        ageSilence(tid, SendAbortWatchdog.networkAbortSilenceMs + 500L)
        // …but then the retry landed: bytes flowed and the latch cleared —
        // only a stale silence would remain.
        TransferManager.updateProgress(tid, 50L)
        SendAbortWatchdog.sweep()
        val t = TransferManager.get(tid)!!
        assertFalse("bytes flowing clears the reconnecting latch", t.reconnecting)
        assertNull("a resumed leg must never be aborted", handle.consumeCause())
        handle.close()
    }

    @Test
    fun `a settled pause keeps its dedicated cause ahead of the network rule`() {
        val (tid, handle) = leg("parked.dat")
        TransferManager.updateProgress(tid, 2_000L)
        TransferManager.requestPause(tid)
        // Age BOTH clocks past their graces — the pause branch is evaluated
        // first and must win, never NETWORK.
        val t = TransferManager.get(tid)!!
        val f = t.javaClass.getDeclaredField("pauseStartedAtMs")
        f.isAccessible = true
        f.set(t, System.currentTimeMillis() - 2_000L)
        ageSilence(tid, SendAbortWatchdog.networkAbortSilenceMs + 2_000L)
        SendAbortWatchdog.sweep()
        assertEquals("a settled pause classifies as PAUSE, not NETWORK",
            SendAbortWatchdog.Cause.PAUSE, handle.consumeCause())
        assertTrue(t.pauseRequested)
        handle.close()
    }
}
