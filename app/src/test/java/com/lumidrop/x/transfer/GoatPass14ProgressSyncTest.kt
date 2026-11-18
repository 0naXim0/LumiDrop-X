package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ══ GOAT-PASS 14 (Phase 1, §4) — sender/receiver progress convergence ════
 *
 * Pins the engine half of the folder-progress synchronization fix: a
 * folder's DERIVED status, progress and "N/M files" total are computed
 * over the ELIGIBLE members ([TransferManager.eligibleMembers] — every
 * child except the DECLINED ones). The receiver's per-child ✕ declines
 * arrive on the status polls as `excludedNested` and resolve the matching
 * sender rows Declined (NativePushClient's poll tick); those children are
 * outside the eligible manifest, so:
 *
 *  • a folder whose 25 eligible children completed while 5 declined rows
 *    still linger their 400 ms auto-dismissal reads COMPLETED — never a
 *    false "Declined", never stuck in Active at "25/30";
 *  • the group progress reaches 100% over the eligible bytes, not 83%;
 *  • a folder the receiver turned down ENTIRELY (every member Declined)
 *    still reads DECLINED;
 *  • CANCELLED members deliberately stay in the denominator (classic
 *    behavior — a sender-side cancel is not a receiver manifest decision).
 *
 * Also pins the §1.3/§1.6 unknown-total rows the new ZIP-batch sends mint:
 * a parked "Waiting for device" archive row (-1 bytes) survives claim →
 * complete as ONE honest row, and an unknown-size group renders
 * indeterminate while it streams.
 */
class GoatPass14ProgressSyncTest {

    private val peerIp = "192.168.43.21"
    private val peerName = "Pixel 8 \u2022 LumiDrop"

    @Before
    fun resetEngine() {
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.resetGates()
        TransferManager.browserLivenessProber = null
    }

    /** 30 pre-registered children of one folder group — the sender's rows
     *  from manifest build (startPendingSend with the folder's groupId). */
    private fun folderOf30(): Pair<String, List<String>> {
        val gid = TransferManager.startGroup("Vacation", TransferManager.Direction.SEND,
            peerIp, peerName, TransferManager.Source.NATIVE_PUSH)
        val ids = (1..30).map { i ->
            TransferManager.startPendingSend("Vacation/IMG_%02d.jpg".format(i), 1000L, gid, peerIp, peerName)
        }
        return gid to ids
    }

    // ── the brief's §4 acceptance scenario, sender half ─────────────────

    @Test
    fun `folder with five declined children completes at 25 of 25 not 25 of 30`() {
        val (gid, ids) = folderOf30()

        // The receiver excluded 5 children mid-flight; the sender's poll
        // tick resolved their rows Declined (still lingering — that is the
        // point: the auto-dismissal has NOT swept them yet).
        val declinedIds = ids.subList(0, 5)
        assertTrue("resolvePendingSends resolves the declined children in place",
            TransferManager.resolvePendingSends(declinedIds) { id -> TransferManager.decline(id) })
        declinedIds.forEach { id ->
            assertEquals("a declined child's own row reads Declined immediately",
                TransferManager.Status.DECLINED, TransferManager.get(id)!!.status)
        }

        // The 25 eligible children stream and complete.
        val eligibleIds = ids.subList(5, 30)
        eligibleIds.forEach { id ->
            assertTrue(TransferManager.claimPendingSend(id))
            TransferManager.complete(id)
        }

        val members = TransferManager.membersOf(gid)
        assertEquals("all 30 rows still exist (the declined five linger their 400 ms)", 30, members.size)

        // ── the §4 contract: derived over the ELIGIBLE manifest ──
        assertEquals("the folder reads COMPLETED the moment its eligible children finish",
            TransferManager.Status.COMPLETED, TransferManager.groupStatus(members))
        assertEquals("progress reaches 100% over the eligible bytes, never 83%",
            100, TransferManager.groupProgressPct(members))
        assertEquals("the eligible manifest is exactly the 25 kept children",
            25, TransferManager.eligibleMembers(members).size)
        assertEquals("filesDone counts the completed children",
            25, members.count { it.status == TransferManager.Status.COMPLETED })
    }

    @Test
    fun `a mid-decline while others still stream keeps the folder ACTIVE`() {
        val (gid, ids) = folderOf30()
        // 3 declined, 10 completed, 1 streaming (claimed, not finished),
        // 16 still waiting → ACTIVE over the eligible members.
        TransferManager.resolvePendingSends(ids.subList(0, 3)) { id -> TransferManager.decline(id) }
        ids.subList(3, 13).forEach { id ->
            assertTrue(TransferManager.claimPendingSend(id))
            TransferManager.complete(id)
        }
        assertTrue(TransferManager.claimPendingSend(ids[13]))
        TransferManager.updateProgress(ids[13], 0L)
        val members = TransferManager.membersOf(gid)
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.groupStatus(members))
        // 10 of the 27 eligible done; 10000 of 27000 eligible bytes → 37%.
        assertEquals(37, TransferManager.groupProgressPct(members))
    }

    @Test
    fun `a folder the receiver turned down entirely reads DECLINED`() {
        val (gid, ids) = folderOf30()
        TransferManager.resolvePendingSends(ids) { id -> TransferManager.decline(id) }
        val members = TransferManager.membersOf(gid)
        assertEquals(TransferManager.Status.DECLINED, TransferManager.groupStatus(members))
        assertTrue(TransferManager.eligibleMembers(members).isEmpty())
    }

    @Test
    fun `cancelled members stay in the denominator - classic behavior preserved`() {
        val (gid, ids) = folderOf30()
        // A sender-side cancel of one child is NOT a receiver manifest
        // decision: the row stays counted until its own dismissal.
        TransferManager.requestCancel(ids[0])
        ids.subList(1, 30).forEach { id ->
            assertTrue(TransferManager.claimPendingSend(id))
            TransferManager.complete(id)
        }
        val members = TransferManager.membersOf(gid)
        assertEquals(30, TransferManager.eligibleMembers(members).size)
        // 29 of 30 done — the classic presentation, byte-for-byte.
        assertEquals(TransferManager.Status.CANCELLED, TransferManager.groupStatus(members))
    }

    // ── §1.3/§1.6: the unknown-total ZIP rows ───────────────────────────

    @Test
    fun `a parked zip batch row waits claims and completes as one honest unknown-size row`() {
        // Auto Send with nothing connected parks the whole batch as ONE
        // row: the archive's name, total -1 (unknowable until streamed).
        val parkedId = TransferManager.startPendingSend("Trip_Notes_more__(9_Files).zip", -1L)
        val row = TransferManager.get(parkedId)
        assertNotNull(row)
        assertEquals(-1L, row!!.totalBytes)
        assertTrue(TransferManager.isPendingSendAlive(parkedId))
        assertFalse("an unknown-total row renders indeterminate",
            row.progressPct > 0 && row.totalBytes > 0)

        // The peer appears: the same row is claimed, streamed, completed.
        assertTrue(TransferManager.claimPendingSend(parkedId))
        TransferManager.updateProgress(parkedId, 1_500_000L)
        TransferManager.complete(parkedId)
        assertEquals(TransferManager.Status.COMPLETED, TransferManager.get(parkedId)!!.status)
    }

    @Test
    fun `an unknown-size streaming group renders indeterminate over eligible members`() {
        val gid = TransferManager.startGroup("Batch.zip", TransferManager.Direction.SEND,
            peerIp, peerName, TransferManager.Source.NATIVE_PUSH)
        val ids = (1..3).map {
            TransferManager.startPendingSend("Batch.zip", -1L, gid, peerIp, peerName)
        }
        ids.forEach { assertTrue(TransferManager.claimPendingSend(it)) }
        val members = TransferManager.membersOf(gid)
        assertTrue("unknown sizes + streaming = indeterminate",
            TransferManager.groupIsIndeterminate(members))
        assertEquals("count-based fallback progress while nothing has a size",
            0, TransferManager.groupProgressPct(members))
        // One child declined → indeterminacy is judged over the remaining
        // eligible members only, and one completion shows real movement.
        TransferManager.decline(ids[0])
        TransferManager.complete(ids[1])
        val after = TransferManager.membersOf(gid)
        assertTrue(TransferManager.groupIsIndeterminate(after))
        assertEquals(50, TransferManager.groupProgressPct(after))   // 1 of the 2 eligible done
    }
}
