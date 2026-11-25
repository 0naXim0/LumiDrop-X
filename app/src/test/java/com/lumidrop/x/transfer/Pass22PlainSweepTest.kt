package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * ══ PASS 22 §1 — the plain-download never-requested liveness ══════════════
 *
 * The audited defect: the never-requested sweep inside expireStalePending
 * filtered `source == QUICK_SHARE` only, so a PLAIN row minted by
 * /api/download/register (every media-tab multi-select download) whose
 * <a download> dispatch the browser silently dropped sat PENDING forever —
 * the server half of the reported "videos permanently stuck in Waiting".
 *
 * Isolation notes (the sweep derives its per-peer activity map from the
 * rows' OWN clocks on every call — there is no activity field to reflect
 * into): each test uses its OWN peer IP so leftover rows from other tests
 * can never keep a peer "busy", and quiescence is produced by backdating
 * the rows' own clocks (ageRow), exactly the way the Pass-23/24 suites do.
 */
class Pass22PlainSweepTest {

    private fun runSweepOnce() {
        val m = TransferManager::class.java.getDeclaredMethod("expireStalePending")
        m.isAccessible = true
        m.invoke(TransferManager)
    }

    /** Backdates a row's clocks past every window (the sweep derives the
     *  peer's last-activity from maxOf(lastSampleAtMs, requestedAtMs,
     *  finishedAt) on each pass — aging the row ages the peer). */
    private fun ageRow(id: String, ageMs: Long) {
        val t = TransferManager.get(id) ?: return
        val past = System.currentTimeMillis() - ageMs
        for (name in listOf("lastSampleAtMs", "startedAt", "finishedAt")) {
            try {
                val f = t.javaClass.getDeclaredField(name)
                f.isAccessible = true
                f.set(t, past)
            } catch (e: Exception) { }
        }
    }

    @Test
    fun `a never-requested PLAIN row is failed once the peer goes quiet`() {
        val ip = "10.22.0.1"
        System.setProperty("ldx.test.plainNeverRequestedMs", "1")
        try {
            val tid = TransferManager.claimOrStartPendingPlainDownload(
                "p22_plain_never.mp4", 900L, ip, "iPad-4", "/v/p22_plain_never.mp4")
            assertNotNull("registration mints a PENDING plain row", tid)
            assertEquals(TransferManager.Status.PENDING, TransferManager.get(tid!!)!!.status)
            assertEquals(TransferManager.Source.PLAIN, TransferManager.get(tid)!!.source)

            // Fresh row → exempt (the dispatch may simply not have fired yet).
            runSweepOnce()
            assertEquals("a fresh row stays Waiting",
                TransferManager.Status.PENDING, TransferManager.get(tid)!!.status)

            // The peer goes quiet and the row ages past the (1ms test) window:
            ageRow(tid, 9_000L)
            runSweepOnce()
            assertEquals("a dropped dispatch resolves FAILED — never eternal Waiting",
                TransferManager.Status.FAILED, TransferManager.get(tid)!!.status)
        } finally { System.clearProperty("ldx.test.plainNeverRequestedMs") }
    }

    @Test
    fun `a streaming sibling keeps the queued PLAIN tail exempt until quiet`() {
        val ip = "10.22.0.2"
        System.setProperty("ldx.test.plainNeverRequestedMs", "1")
        try {
            val head = TransferManager.claimOrStartPendingPlainDownload(
                "p22_head.mp4", 900L, ip, "iPad-4", "/v/p22_head.mp4")!!
            val tail = TransferManager.claimOrStartPendingPlainDownload(
                "p22_tail.mp4", 900L, ip, "iPad-4", "/v/p22_tail.mp4")!!

            // The head claims (its request arrived) and streams bytes:
            val admitted = TransferManager.claimOrStartActivePlainDownload(
                "p22_head.mp4", 900L, ip, "iPad-4", "/v/p22_head.mp4")
            assertEquals(head, admitted)
            TransferManager.addProgress(admitted, 100L)

            // The tail: never requested, ANCIENT clocks, but the peer is
            // visibly active — the sweep must NOT touch it.
            ageRow(tail, 60_000L)
            runSweepOnce()
            assertEquals("a queued tail behind live work stays Waiting",
                TransferManager.Status.PENDING, TransferManager.get(tail)!!.status)

            // The head completes; the batch falls silent; NOW the tail fails.
            TransferManager.complete(admitted)
            TransferManager.releaseDownloadSlot(admitted)
            ageRow(head, 9_000L)   // the completed row's finishedAt goes quiet too
            runSweepOnce()
            assertEquals("once the batch goes silent the tail honestly fails",
                TransferManager.Status.FAILED, TransferManager.get(tail)!!.status)
        } finally { System.clearProperty("ldx.test.plainNeverRequestedMs") }
    }

    @Test
    fun `oldest-only — one plain row fails per pass`() {
        val ip = "10.22.0.3"
        System.setProperty("ldx.test.plainNeverRequestedMs", "1")
        try {
            val a = TransferManager.claimOrStartPendingPlainDownload(
                "p22_old_a.mp4", 900L, ip, "iPad-4", "/v/p22_old_a.mp4")!!
            Thread.sleep(3)
            val b = TransferManager.claimOrStartPendingPlainDownload(
                "p22_old_b.mp4", 900L, ip, "iPad-4", "/v/p22_old_b.mp4")!!
            ageRow(a, 20_000L); ageRow(b, 10_000L)
            runSweepOnce()
            assertEquals("exactly the OLDEST row fails",
                TransferManager.Status.FAILED, TransferManager.get(a)!!.status)
            assertEquals("the younger row keeps its turn",
                TransferManager.Status.PENDING, TransferManager.get(b)!!.status)
            // next quiet pass takes the remaining one (re-age the failed row:
            // its fresh finishedAt is the peer's newest activity stamp)
            ageRow(a, 20_000L)
            runSweepOnce()
            assertEquals(TransferManager.Status.FAILED, TransferManager.get(b)!!.status)
        } finally { System.clearProperty("ldx.test.plainNeverRequestedMs") }
    }
}
