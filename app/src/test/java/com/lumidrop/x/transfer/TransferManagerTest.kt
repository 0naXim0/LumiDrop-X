package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * Pass-8 verification suite — runs TransferManager (deliberately Android-free,
 * see its class doc) on the JVM against the exact acceptance scenarios of the
 * pass-8 brief:
 *
 *  §3  nine targeted Quick-Share sends produce exactly NINE logical transfer
 *      records through queue → accept → download → completion, with the
 *      duplicate-pending sweep resolving any second row for the same
 *      (item, client) identity, and same-named files never collapsing.
 *  §13 whole-batch cancellation: every live row resolves CANCELLED, the
 *      upload tombstone refuses a resurrected POST, and distinct push ids
 *      dedupe for propagation.
 *  §7  interrupted presentation: a connection-loss failure latches
 *      `interrupted` (and only that — deliberate cancels never do), and a
 *      byte-level resume re-opens the SAME id cleanly.
 *  §10 a 300-file batch through claim/complete with contention leaves the
 *      engine empty, the gates balanced, and history bounded.
 */
class TransferManagerTest {

    @Before
    fun resetEngine() {
        // TransferManager is a singleton; drain everything a previous test left.
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        TransferManager.dismissAllFinished()
        for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        // GOAT-PASS 1: the gates are part of the singleton's state too — rebuild
        // them to a clean TRANSFER_CONCURRENCY-permit slate so one test's
        // in-flight holders can never bleed into the next one's expectations
        // (same discipline Pass24FixesTest / ConsecutivePauseAndBatchTest
        // already apply in their own @Before).
        TransferManager.resetGates()
        // GOAT-PASS 9 (test-suite repair): the liveness prober is singleton
        // state too. GoatPass7EngineTest wires it per-test; this class never
        // reset it, so a prober left over from an earlier class turned
        // downloadConnectionAborted's immediate headless interruption into
        // the browser-verdict window (row frozen ACTIVE) and this class's
        // own §7 scenario failed purely on test ORDER. Null = the documented
        // headless behavior the scenario asserts.
        TransferManager.browserLivenessProber = null
    }

    private fun qsName(n: Int) = "photo_$n.jpg (qs)"
    private val peerIp = "192.168.43.88"
    private val peerName = "iPad \u2022 Safari"

    // ── §3: the exact "9 files, Auto Send, one iPad" acceptance scenario ─────

    @Test
    fun `nine auto-send files produce exactly nine logical transfers end to end`() {
        val n = 9
        // Host taps Send with Auto Send ON → preRegisterQuickShareSends
        // (one PENDING row per file, via claimOrStartPending).
        repeat(n) { i ->
            val id = TransferManager.claimOrStartPending("file_$i.jpg", 1000L + i, peerIp, peerName,
                qsItemName = qsName(i), filePath = "/sdcard/file_$i.jpg")
            assertNotNull("pre-registration must create a row for file $i", id)
        }
        assertEquals("after queue: exactly 9 rows", n, TransferManager.activeCount())

        // The iPad accepts (apiQuickShareAccept) — idempotent claim, no new row.
        repeat(n) { i ->
            val id = TransferManager.claimOrStartPending("file_$i.jpg", 1000L + i, peerIp, peerName,
                qsItemName = qsName(i), filePath = "/sdcard/file_$i.jpg")
            assertNull("accept must NOT mint a second row for file $i", id)
        }
        assertEquals("after accept: still exactly 9 rows", n, TransferManager.activeCount())

        // Each file's byte-request arrives (apiQSDl → claimOrStartActiveQuickShare).
        val ids = mutableListOf<String>()
        repeat(n) { i ->
            ids += TransferManager.claimOrStartActiveQuickShare(qsName(i), peerIp,
                "file_$i.jpg", 1000L + i, peerName, "/sdcard/file_$i.jpg")
        }
        assertEquals("after downloads start: still exactly 9 rows", n, TransferManager.activeCount())
        assertEquals("9 distinct ids (no id reuse across files)", n, ids.toSet().size)

        // A duplicate re-request for the same item attaches as an extra
        // connection on the SAME identity — never a duplicate row.
        repeat(n) { i ->
            val again = TransferManager.claimOrStartActiveQuickShare(qsName(i), peerIp,
                "file_$i.jpg", 1000L + i, peerName, "/sdcard/file_$i.jpg")
            assertEquals("re-request reuses the same identity", ids[i], again)
        }
        assertEquals("after re-requests: STILL exactly 9 rows", n, TransferManager.activeCount())

        // Every attached connection reports completion — the transfer as a
        // whole finalizes only when the LAST one lands (two connections were
        // attached per file above).
        ids.forEach { TransferManager.complete(it) }
        assertEquals("one connection still streaming per file", n, TransferManager.activeCount())
        ids.forEach { TransferManager.complete(it) }
        assertEquals("nothing left in flight", 0, TransferManager.activeCount())
        assertEquals("exactly 9 Sent rows — no duplicates", n,
            TransferManager.sentSnapshot().count { it.source == TransferManager.Source.QUICK_SHARE })
    }

    @Test
    fun `duplicate pending quick-share row for an already-live identity is swept`() {
        // The legitimate row (pre-registered + accepted + streaming).
        val liveId = TransferManager.claimOrStartPending("a.jpg", 10L, peerIp, peerName, qsItemName = "a.jpg")
        assertNotNull(liveId)
        TransferManager.claimOrStartActiveQuickShare("a.jpg", peerIp, "a.jpg", 10L, peerName, null)

        // A stale second PENDING row for the SAME (item, client) — the bug shape.
        val ghost = TransferManager.startPending(
            TransferManager.Direction.DOWNLOAD, "a.jpg", 10L, peerIp, peerName,
            source = TransferManager.Source.QUICK_SHARE, qsItemName = "a.jpg")

        TransferManager.sweepDuplicatePendingQuickShare()
        // The ghost resolves CANCELLED immediately (the map entry itself is
        // removed by the short auto-removal linger — the status is the
        // semantic guarantee under test).
        val ghostRow = TransferManager.get(ghost)
        assertTrue("ghost waiting row is resolved, never left Waiting",
            ghostRow == null || ghostRow.status == TransferManager.Status.CANCELLED)
        assertNotNull("live row untouched", TransferManager.get(liveId!!))
        assertEquals(TransferManager.Status.ACTIVE, TransferManager.get(liveId!!)!!.status)
    }

    @Test
    fun `two intentionally sent files with the same filename never collapse`() {
        val id1 = TransferManager.claimOrStartPending("photo.jpg", 5L, peerIp, peerName, qsItemName = "photo.jpg")
        val id2 = TransferManager.claimOrStartPending("photo (1).jpg", 7L, peerIp, peerName, qsItemName = "photo (1).jpg")
        assertNotNull(id1); assertNotNull(id2)
        TransferManager.sweepDuplicatePendingQuickShare()
        assertNotNull("same FILENAME, different identity: first survives", TransferManager.get(id1!!))
        assertNotNull("same FILENAME, different identity: second survives", TransferManager.get(id2!!))
    }

    // ── §13: receiver-side whole-batch cancellation semantics ────────────────

    @Test
    fun `cancel all resolves every live row as CANCELLED and refuses resurrected uploads`() {
        val pushId = "push-abc"
        val n = 5
        val ids = (0 until n).map { i ->
            TransferManager.startPendingUpload("$pushId::folder/f$i.bin", "f$i.bin", 100L, "10.0.0.2", "Sender",
                source = TransferManager.Source.NATIVE_PUSH)
        }
        assertEquals(n, TransferManager.activeCount())

        // The receiver's Cancel-All helper: distinct push ids for propagation.
        val pushes = TransferManager.liveUploadPushIds()
        assertEquals(setOf(pushId), pushes)

        // Cancel All: every live row resolves CANCELLED immediately.
        ids.forEach { TransferManager.requestCancel(it) }
        ids.forEach { id ->
            val t = TransferManager.get(id)!!
            assertEquals(TransferManager.Status.CANCELLED, t.status)
            assertFalse("a deliberate cancel is never 'interrupted'", t.interrupted)
        }

        // The sender's next file POST (resurrection attempt) is refused by the
        // tombstone — the pre-registered row is CANCELLED within the window.
        assertTrue("tombstone active for the cancelled batch",
            TransferManager.wasCancelledForUpload("$pushId::folder/f2.bin"))

        // ...and nothing can flip a terminal row back.
        ids.forEach { TransferManager.complete(it) }
        ids.forEach { assertEquals(TransferManager.Status.CANCELLED, TransferManager.get(it)!!.status) }
    }

    @Test
    fun `cancelAllFor is scoped to one peer`() {
        val a = TransferManager.startPendingUpload("req-a", "a.bin", 1L, "10.0.0.2", "A")
        val b = TransferManager.startPendingUpload("req-b", "b.bin", 1L, "10.0.0.3", "B")
        TransferManager.cancelAllFor("10.0.0.2")
        assertEquals(TransferManager.Status.CANCELLED, TransferManager.get(a)!!.status)
        assertEquals(TransferManager.Status.PENDING, TransferManager.get(b)!!.status)
    }

    // ── §7: interrupted presentation + byte-level resume identity ────────────

    @Test
    fun `connection loss marks interrupted, deliberate cancel does not, resume reopens same id`() {
        val id = TransferManager.start(TransferManager.Direction.UPLOAD, "video.mp4", 900L, peerIp, peerName)
        TransferManager.addProgress(id, 400L)
        TransferManager.fail(id, interrupted = true)
        val t = TransferManager.get(id)!!
        assertEquals(TransferManager.Status.FAILED, t.status)
        assertTrue("connection loss presents as interrupted", t.interrupted)

        // The sender reconnects: byte-level resume re-opens the SAME identity.
        assertTrue(TransferManager.reopenableForResume(id))
        assertTrue(TransferManager.reopenForResume(id))
        val t2 = TransferManager.get(id)!!
        assertEquals(TransferManager.Status.ACTIVE, t2.status)
        assertFalse("resumed row is no longer interrupted", t2.interrupted)
        assertEquals("progress preserved across the interruption", 400L, t2.transferredBytes)

        // A deliberate cancel of the resumed transfer is NOT an interruption.
        TransferManager.requestCancel(id)
        val t3 = TransferManager.get(id)!!
        assertEquals(TransferManager.Status.CANCELLED, t3.status)
        assertFalse(t3.interrupted)
    }

    // ── §10: 300-file batch under contention stays bounded and converges ────

    @Test
    fun `300-file batch with concurrent claim-complete drains completely and keeps gates balanced`() {
        val n = 300
        val ids = (0 until n).map { i ->
            TransferManager.startPendingSend("folder/file_$i.dat", 50L, groupId = null,
                peerIp = "10.0.0.9", peerName = "Peer", filePath = null)
        }
        assertEquals(n, TransferManager.activeCount())

        val nextIdx = AtomicInteger(0)
        val done = AtomicInteger(0)
        val threads = (0 until 4).map {
            Thread {
                while (done.get() < n) {
                    val i = nextIdx.getAndIncrement()
                    if (i >= n) break
                    val id = ids[i]
                    if (TransferManager.claimPendingSend(id)) {
                        TransferManager.updateProgress(id, 25L)
                        TransferManager.updateProgress(id, 50L)
                        TransferManager.complete(id)
                        done.incrementAndGet()
                    } else {
                        // Already terminal (cancelled by someone else?) — count it.
                        done.incrementAndGet()
                    }
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(30000) }

        assertEquals("every file resolved", n, done.get())
        assertEquals("nothing in flight at the end", 0, TransferManager.activeCount())
        assertEquals("all 300 in Sent history", n, TransferManager.sentSnapshot().size)

        // Gate discipline: both directions accept exactly one new permit.
        assertTrue("receiving gate has a free permit", TransferManager.acquireReceivingSlot("post-stress-1"))
        TransferManager.releaseReceivingSlot("post-stress-1")
        assertTrue("download gate has a free permit", TransferManager.acquireDownloadSlot("post-stress-2"))
        TransferManager.releaseDownloadSlot("post-stress-2")

        // History is pruned ON CREATION (the engine's established cadence),
        // so one more transfer creation collapses the 300 finished rows back
        // under the 60-row cap.
        TransferManager.startPendingSend("post-batch-probe.dat", 1L)
        val finished = TransferManager.snapshot().count {
            it.status != TransferManager.Status.PENDING && it.status != TransferManager.Status.ACTIVE
        }
        assertTrue("history bounded after next creation (was $finished)", finished <= 61)
    }

    @Test
    fun `concurrent duplicate accepts for one item create at most one row`() {
        // The race the atomic claim functions exist for: two accept calls in
        // parallel for the same (item, client).
        val rounds = 25
        val created = AtomicInteger(0)
        repeat(rounds) { round ->
            val latch = CountDownLatch(2)
            val roundsThreads = (0 until 2).map {
                Thread {
                    latch.countDown()
                    try { latch.await() } catch (e: InterruptedException) {}
                    val id = TransferManager.claimOrStartPending("race.jpg", 1L, peerIp, peerName, qsItemName = "race.jpg")
                    if (id != null) created.incrementAndGet()
                }
            }
            roundsThreads.forEach { it.start() }
            roundsThreads.forEach { it.join(5000) }
            // drain for the next round
            for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
            TransferManager.dismissAllFinished()
            for (t in TransferManager.activeSnapshot()) TransferManager.dismiss(t.id)
        }
        assertTrue("at most one row per racing round (was ${created.get()})", created.get() <= rounds)
    }

    // ── Pass-8 follow-up: auto-accept / auto-send synchronization semantics ──

    @Test
    fun `parked auto-send row is quietly superseded, never a stuck ghost`() {
        // Auto Send parks an item with no device around: one PENDING SEND row.
        val tid = TransferManager.startPendingSend("held-file.jpg", 500L)
        assertTrue("parked row starts PENDING (Waiting)", TransferManager.isPendingSendAlive(tid))

        // A peer appears and the drain hands the batch off; the parked row is
        // superseded by the real per-file rows (cancelPendingSend — quiet).
        TransferManager.cancelPendingSend(tid)
        assertFalse("superseded parked row is gone", TransferManager.isPendingSendAlive(tid))
        assertNull("no ghost row left in the engine", TransferManager.get(tid))
        assertEquals("nothing Waiting remains", 0, TransferManager.activeCount())
    }

    @Test
    fun `parked auto-send row for a dead item fails visibly, never silently vanishes`() {
        // The item was deleted/moved while parked: the drain's per-item
        // resolution fails the parked row instead of cancelling it — a
        // reason on screen, not a silent disappearance.
        val tid = TransferManager.startPendingSend("deleted-file.jpg", 500L)
        TransferManager.fail(tid)
        val t = TransferManager.get(tid)
        assertNotNull("failed parked row stays visible for its linger window", t)
        assertEquals(TransferManager.Status.FAILED, t!!.status)
    }

    @Test
    fun `already-delivered short-circuit completes both sides in place with no duplicate rows`() {
        // ── Receiver half: auto-accept pre-registered an UPLOAD row keyed by
        // the composite "pushId::path" request id (registerPendingTransfersFor);
        // the sender's resume probe answers "already fully delivered", and the
        // receiver completes that SAME row in place (apiNativePushFileStatus).
        val reqId = "abc123::Vacation/IMG_1.jpg"
        val pendingId = TransferManager.startPendingUpload(reqId, "Vacation/IMG_1.jpg", 2048L, peerIp, peerName)
        assertEquals("pre-registered row is Waiting (PENDING)", TransferManager.Status.PENDING,
            TransferManager.get(pendingId)!!.status)
        val claimed = TransferManager.claimPendingUpload(reqId, "/sdcard/LumiDrop X/Vacation/IMG_1.jpg")
        assertEquals("the pre-registered row itself is promoted (no duplicate)",
            pendingId, claimed)
        TransferManager.updateProgress(claimed!!, 2048L)
        TransferManager.complete(claimed)
        assertEquals("receiver row lands in Received history", 1, TransferManager.receivedSnapshot().size)
        // Idempotent: a second probe/claim finds nothing PENDING and creates
        // no second row.
        assertNull("claim after completion is a no-op", TransferManager.claimPendingUpload(reqId))
        assertEquals("still exactly one received row", 1, TransferManager.receivedSnapshot().size)

        // ── Sender half: the pre-assigned PENDING SEND row is claimed and
        // completed the moment the file short-circuits DONE.
        val sendTid = TransferManager.startPendingSend("Vacation/IMG_1.jpg", 2048L, peerIp, peerName)
        assertTrue("sender short-circuit claims its own row",
            TransferManager.claimPendingSend(sendTid))
        TransferManager.complete(sendTid)
        assertEquals("sender row lands in Sent history", 1, TransferManager.sentSnapshot().size)
        assertEquals("nothing left Waiting on either side", 0, TransferManager.activeCount())
    }

    @Test
    fun `vanished push resolves its never-delivered pre-registered rows as interrupted`() {
        // registerPendingTransfersFor-style composite rows for a push whose
        // record goes away (sender cancel, TTL sweep, dead-session sweep):
        // every still-PENDING row must resolve — nothing may sit Waiting
        // forever. Mirrors LumiDropServer.resolvePendingRowsForVanishedPush.
        val pushId = "push-vanish-test"
        val paths = listOf("Album/a.jpg", "Album/b.jpg", "Album/c.jpg")
        val tids = paths.map { p ->
            TransferManager.startPendingUpload("$pushId::$p", "Album/$p", 100L, peerIp, peerName)
        }
        assertEquals("three Waiting rows after auto-accept", 3, TransferManager.activeCount())

        // One file DID stream and complete before the sender vanished.
        val streamedTid = TransferManager.claimPendingUpload("$pushId::Album/b.jpg", "/x/b.jpg")!!
        TransferManager.updateProgress(streamedTid, 100L)
        TransferManager.complete(streamedTid)

        // The vanished-push resolution: every still-PENDING composite row of
        // the push resolves FAILED(interrupted) via findPendingUploadId+fail.
        for (p in paths) {
            val tid = TransferManager.findPendingUploadId("$pushId::$p") ?: continue
            TransferManager.fail(tid, interrupted = true)
        }
        assertEquals("no Waiting rows survive the push vanishing", 0,
            TransferManager.activeCount())
        assertEquals("the streamed file is in Received history", 1,
            TransferManager.receivedSnapshot().size)
        val survivors = TransferManager.snapshot().filter {
            it.uploadRequestId?.startsWith(pushId) == true && it.status == TransferManager.Status.FAILED
        }
        assertEquals("exactly the two interrupted rows remain for their linger window", 2, survivors.size)
        assertTrue("interrupted flag latched (Reconnect presentation)",
            survivors.all { it.interrupted })
    }

    // ── Pass-10: strictly-ordered download admission (queue-order fix) ────────

    @Test
    fun `download gate admits the oldest queued transfer first, not the earliest request`() {
        // The exact reported bug shape: File 1 streams, Files 2 and 3 are
        // visibly queued (2 before 3), but the browser fires 3's HTTP request
        // FIRST. The old fair-semaphore gate served 3 on release; the ordered
        // gate must serve 2 — the order the Transfers tab displays.
        val older = TransferManager.startPending(TransferManager.Direction.DOWNLOAD, "older.jpg", 10L,
            peerIp, peerName, source = TransferManager.Source.PLAIN)
        Thread.sleep(6)
        val newer = TransferManager.startPending(TransferManager.Direction.DOWNLOAD, "newer.jpg", 10L,
            peerIp, peerName, source = TransferManager.Source.PLAIN)
        assertNotNull(older); assertNotNull(newer)

        // Unrelated transfers hold EVERY download permit the gate has
        // (GOAT-PASS 2: the gate holds exactly 1 permit again — the
        // transfer-concurrency contract — so one holder exhausts it and the
        // waiters queue; the ordering property under test is unchanged).
        val holderKeys = (0 until TransferManager.downloadConcurrencyLimit())
            .map { "holder-key-$it" }
        holderKeys.forEach { key -> assertTrue(TransferManager.acquireDownloadSlot(key)) }
        val granted = java.util.concurrent.LinkedBlockingQueue<String>()
        // The NEWER row's request arrives FIRST (out of displayed order)…
        val tNewer = Thread { if (TransferManager.acquireDownloadSlot(newer!!)) granted.add("newer") }
        tNewer.start()
        Thread.sleep(300)   // let its waiter register in the gate
        // …then the OLDER row's request arrives.
        val tOlder = Thread { if (TransferManager.acquireDownloadSlot(older!!)) granted.add("older") }
        tOlder.start()
        Thread.sleep(300)
        assertTrue("nothing is granted while every permit is held", granted.isEmpty())

        try {
            TransferManager.releaseDownloadSlot(holderKeys[0])
            assertEquals("the OLDER queued row wins the freed permit", "older",
                granted.poll(2, java.util.concurrent.TimeUnit.SECONDS))
            // Older's streaming holds the permit; handing it back admits the
            // next row in DISPLAY order (newer), not in request-arrival order.
            TransferManager.releaseDownloadSlot(older!!)
            tOlder.join(5000)
            assertEquals("then the newer one", "newer",
                granted.poll(2, java.util.concurrent.TimeUnit.SECONDS))
            TransferManager.releaseDownloadSlot(newer!!)
            tNewer.join(5000)
        } finally {
            // Never leak a permit into the next test, whatever failed above.
            // Two rounds (with a beat between) so a waiter that grabbed the
            // permit during cleanup also hands it back; the exactly-once
            // holder-count guard makes every extra call a harmless no-op.
            holderKeys.forEach { key -> TransferManager.releaseDownloadSlot(key) }
            TransferManager.releaseDownloadSlot(older)
            tOlder.join(5000); tNewer.join(5000)
            TransferManager.releaseDownloadSlot(older)
            TransferManager.releaseDownloadSlot(newer)
            try { Thread.sleep(60) } catch (e: InterruptedException) {}
            TransferManager.releaseDownloadSlot(older)
            TransferManager.releaseDownloadSlot(newer)
            TransferManager.dismiss(older); TransferManager.dismiss(newer)
        }
    }

    // ── Pass-10: automatic resume — reconnecting presentation + row identity ──

    @Test
    fun `reconnecting latch is honest - cleared by bytes, by cancel, never by a stall`() {
        val id = TransferManager.start(TransferManager.Direction.UPLOAD, "video.mp4", 900L, peerIp, peerName)
        TransferManager.markReconnecting(id)
        assertTrue("connection lost + auto-resume running presents Reconnecting",
            TransferManager.get(id)!!.reconnecting)

        // Bytes flow again on the resumed leg — the latch clears itself.
        TransferManager.updateProgress(id, 450L)
        assertFalse("bytes flowing again clears the reconnecting latch",
            TransferManager.get(id)!!.reconnecting)

        // A second interruption re-latches; a deliberate CANCEL outranks it
        // (cancelled is cancelled — never Interrupted, never resumable).
        TransferManager.markReconnecting(id)
        TransferManager.requestCancel(id)
        val t = TransferManager.get(id)!!
        assertEquals(TransferManager.Status.CANCELLED, t.status)
        assertFalse("a deliberate cancel is never 'interrupted'", t.interrupted)
        assertFalse("a deliberate cancel ends the reconnect presentation", t.reconnecting)
    }

    @Test
    fun `interrupted upload resume leg reopens the original row with no duplicate`() {
        // The receiver half of the native-push automatic resume: the row that
        // FAILED as interrupted is looked up read-only, then re-opened under
        // its ORIGINAL id when the sender's re-POST arrives — never a second
        // row minted beside the lingering Interrupted one.
        val reqId = "push-resume::album/v.mp4"
        val id = TransferManager.startPendingUpload(reqId, "album/v.mp4", 1000L, peerIp, peerName,
            source = TransferManager.Source.NATIVE_PUSH)
        assertEquals(id, TransferManager.claimPendingUpload(reqId, "/sdcard/album/v.mp4"))
        TransferManager.updateProgress(id!!, 600L)
        TransferManager.fail(id, interrupted = true)
        assertEquals(TransferManager.Status.FAILED, TransferManager.get(id)!!.status)
        assertTrue(TransferManager.get(id)!!.interrupted)

        // The sender's automatic resume probes then re-POSTs the remainder.
        assertEquals("read-only lookup finds the interrupted row", id,
            TransferManager.findInterruptedUploadId(reqId))
        assertEquals("re-open resolves under the ORIGINAL id", id,
            TransferManager.reopenInterruptedUploadByExactId(id, "/sdcard/album/v.mp4"))
        val t = TransferManager.get(id)!!
        assertEquals(TransferManager.Status.ACTIVE, t.status)
        assertFalse(t.interrupted)
        assertFalse(t.reconnecting)
        assertEquals("progress continues from the preserved checkpoint", 600L, t.transferredBytes)
        assertEquals("still exactly ONE row for this logical transfer", 1,
            TransferManager.snapshot().count { it.uploadRequestId == reqId })

        // The resumed leg finishes — one Received row, nothing Waiting.
        TransferManager.updateProgress(id, 1000L)
        TransferManager.complete(id)
        assertEquals(TransferManager.Status.COMPLETED, TransferManager.get(id)!!.status)
    }

    // ── Pass-11: interruption must never masquerade as a cancel ──────────────

    @Test
    fun `interrupted failure does not latch the cancel signal the resume paths read`() {
        // Root cause of "reconnecting the devices does not trigger the
        // expected resumption": fail(interrupted=true) used to latch
        // cancelRequested, which isCancelled() reads — the sender's resume
        // loop then exited as Cancelled and the receiver's receive-slot gate
        // answered "Cancelled by host" for the resumed leg.
        val id = TransferManager.start(TransferManager.Direction.UPLOAD, "v.mp4", 900L, peerIp, peerName)
        TransferManager.addProgress(id, 400L)
        TransferManager.fail(id, interrupted = true)
        val t = TransferManager.get(id)!!
        assertEquals(TransferManager.Status.FAILED, t.status)
        assertTrue(t.interrupted)
        assertFalse("an interruption is NOT a deliberate cancel — the latch must stay clear",
            TransferManager.isCancelled(id))
        // The receiving-slot gate admits the interrupted row's resumed leg
        // (previously refused with \"Cancelled by host\" because the latch
        // was set).
        assertTrue("interrupted row may wait for and hold a receiving slot for its resumed leg",
            TransferManager.acquireReceivingSlot(id))
        TransferManager.releaseReceivingSlot(id)

        // A genuine (non-interrupted) failure KEEPS the unwind latch.
        val id2 = TransferManager.start(TransferManager.Direction.UPLOAD, "w.mp4", 90L, peerIp, peerName)
        TransferManager.addProgress(id2, 40L)
        TransferManager.fail(id2)
        assertTrue("a plain failure still latches the sibling-unwind signal",
            TransferManager.isCancelled(id2))
    }

    @Test
    fun `cancelled upload tombstone survives the row's own dismissal`() {
        // Root cause of the ghost transfer: the CANCELLED row auto-dismisses
        // after ~400ms, but the sender's auto-resume re-POSTs the same file
        // as much as 10 minutes later — wasCancelledForUpload consulted only
        // live rows, so the refusal died with the row and the start() fallback
        // minted a ghost. The durable tombstone outlives the dismissal.
        val reqId = "push-ghost::folder/skip.bin"
        val id = TransferManager.startPendingUpload(reqId, "skip.bin", 100L, "10.0.0.4", "Sender")
        assertEquals(id, TransferManager.claimPendingUpload(reqId, "/sdcard/album/skip.bin"))
        TransferManager.updateProgress(id!!, 50L)

        // The receiver cancels mid-stream.
        TransferManager.requestCancel(id)
        assertEquals(TransferManager.Status.CANCELLED, TransferManager.get(id)!!.status)
        assertTrue("tombstone is live while the row exists",
            TransferManager.wasCancelledForUpload(reqId))

        // The row auto-dismisses (simulated directly — the timer is what
        // fires it in production).
        TransferManager.dismiss(id)
        assertNull(TransferManager.get(id))
        // ...and the refusal STILL holds for the sender's later re-POST/probe.
        assertTrue("tombstone survives the row's dismissal for the resume window",
            TransferManager.wasCancelledForUpload(reqId))
        assertTrue("durable-only check agrees",
            TransferManager.wasCancelledForUploadDurable(reqId))

        // A cancelled session never re-opens: the interrupted-resume lookup
        // deliberately finds nothing for a deliberately cancelled row.
        assertNull("a cancelled request is never resumable",
            TransferManager.findInterruptedUploadId(reqId))
    }

    @Test
    fun `rows created in one batch keep creation order in the active snapshot`() {
        // Root cause of the Incoming-modal vs Active-tab ordering mismatch:
        // a batch's rows are minted in one tight loop, startedAt collides at
        // millisecond resolution, and the stable sort then fell back to
        // ConcurrentHashMap hash order — an arbitrary permutation of the
        // selection order. The monotonic seq is the final tiebreaker now.
        val pushId = "push-order"
        val names = (0 until 12).map { "file_$it.bin" }
        names.forEachIndexed { i, n ->
            TransferManager.startPendingUpload("$pushId::$n", n, 10L, "10.0.0.9", "Sender")
        }
        val pending = TransferManager.activeSnapshot().filter { it.uploadRequestId?.startsWith("$pushId::") == true }
        assertEquals(names.size, pending.size)
        assertEquals("Active tab lists the batch in EXACTLY the Incoming modal's (selection) order",
            names, pending.map { it.fileName })
    }

    // ── Pass-12 (cancel-finality root fix) ───────────────────────────────────

    @Test
    fun `cancelUploadRequestEverywhere cancels both the waiting and the streaming row`() {
        // Row A: still waiting its turn (PENDING under the request id).
        val reqId = "req-p12-cancel"
        val waitingId = TransferManager.startPendingUpload(reqId, "waiting.bin", 500L, peerIp, peerName)
        assertNotNull(waitingId)

        // Row B: a second file under the SAME request id that is already
        // streaming (claimed → ACTIVE).
        val streamingId = TransferManager.startPendingUpload(reqId, "streaming.bin", 900L, peerIp, peerName)
        assertNotNull(streamingId)
        TransferManager.claimPendingUpload(reqId)
        // claim binds the FIRST waiting row; make row B the ACTIVE one by
        // claiming it through the engine's own admission path instead:
        // dismiss row A's claim ambiguity by asserting B is claimable after A.
        val b = TransferManager.get(streamingId!!)
        if (b?.status == TransferManager.Status.PENDING) {
            TransferManager.claimPendingUpload(reqId)
        }

        // The sender cancels the request — the new pass-12 engine path.
        TransferManager.cancelUploadRequestEverywhere(reqId)

        for (id in listOf(waitingId!!, streamingId)) {
            val row = TransferManager.get(id)
            assertTrue("row $id must be resolved CANCELLED, never Interrupted",
                row == null || row.status == TransferManager.Status.CANCELLED)
            if (row != null) {
                assertTrue("a deliberate cancel never presents as interrupted",
                    !(row.status == TransferManager.Status.FAILED && row.interrupted))
            }
        }
        // The durable tombstone refuses any retry leg under the same request.
        assertTrue("retry legs under the cancelled request are tombstoned",
            TransferManager.wasCancelledForUpload(reqId))
    }

    @Test
    fun `a network failure arriving after a deliberate cancel cannot resurrect the row as interrupted`() {
        val reqId = "req-p12-latefail"
        val id = TransferManager.startPendingUpload(reqId, "late.bin", 400L, peerIp, peerName)
        assertNotNull(id)
        // The host/sender cancels FIRST…
        TransferManager.requestCancel(id!!)
        // …and only afterwards does the dying connection report a failure
        // (interrupted=true) — the exact ordering that used to flip a
        // cancelled row into "Interrupted".
        TransferManager.fail(id, interrupted = true)
        val row = TransferManager.get(id)
        assertTrue("the cancel wins: CANCELLED stands, never FAILED+interrupted",
            row == null || row.status == TransferManager.Status.CANCELLED)
        if (row != null) {
            assertFalse("no interrupted flag on a deliberately cancelled row", row.interrupted)
        }
    }

    // ── Pass-13: download-side cancel tombstones (the ghost-transfer fix) ────

    @Test
    fun `cancelled quick-share download stays tombstoned after its row auto-dismisses`() {
        // The reported ghost path: the host cancels a Quick-Share download,
        // the CANCELLED row auto-dismisses after 400 ms (simulated here by an
        // immediate dismiss — the scheduled task does exactly that), and a
        // LATE browser re-request (download-manager retry / parallel Range
        // leg) must still be refused by the endpoint guard. Pre-pass-13 the
        // guard only consulted LIVE rows, so the refusal evaporated together
        // with the row and claimOrStartActiveQuickShare minted a fresh ghost.
        val item = qsName(41)
        val dlId = TransferManager.claimOrStartPending("ghost.jpg", 777L, peerIp, peerName,
            qsItemName = item, filePath = "/sdcard/ghost.jpg")
        assertNotNull(dlId)
        assertEquals(dlId, TransferManager.claimOrStartActiveQuickShare(item, peerIp,
            "ghost.jpg", 777L, peerName, "/sdcard/ghost.jpg"))

        // Deliberate host cancel, then the row's own auto-dismissal.
        TransferManager.requestCancel(dlId!!)
        TransferManager.dismiss(dlId)

        // The durable tombstone — NOT the live row — answers the guard now.
        assertTrue("a late re-request for the cancelled item must be refused (Quick-Share)",
            TransferManager.wasCancelledForQuickShare(item, peerIp))
    }

    @Test
    fun `cancelled plain download stays tombstoned after its row auto-dismisses`() {
        // The plain-download twin of the Quick-Share tombstone test above —
        // apiDownload's guard reads wasCancelledForPlainDownload, which used
        // to die with the row's 400 ms auto-dismissal exactly the same way.
        val dlId = TransferManager.claimOrStartPendingPlainDownload(
            "plain.bin", 555L, peerIp, peerName, "/sdcard/plain.bin")
        assertNotNull(dlId)
        TransferManager.requestCancel(dlId!!)
        TransferManager.dismiss(dlId)
        assertTrue("a late re-request for the cancelled file must be refused (plain download)",
            TransferManager.wasCancelledForPlainDownload("plain.bin", peerIp))
    }

    @Test
    fun `cancelling one download leaves an unrelated same-peer download untouched`() {
        // The engine-level half of the individual-cancellation fix: the X on
        // one row resolves exactly that row (and its own tombstone identity);
        // a sibling for the same peer keeps flowing and is NOT refused by the
        // cancelled item's guard (guards are keyed by item identity, never by
        // peer alone).
        val itemA = qsName(50)
        val itemB = qsName(51)
        val a = TransferManager.claimOrStartPending("a.jpg", 10L, peerIp, peerName, qsItemName = itemA)
        val b = TransferManager.claimOrStartPending("b.jpg", 20L, peerIp, peerName, qsItemName = itemB)
        assertNotNull(a); assertNotNull(b)
        val aId = a!!; val bId = b!!
        assertEquals(aId, TransferManager.claimOrStartActiveQuickShare(itemA, peerIp, "a.jpg", 10L, peerName, null))
        assertEquals(bId, TransferManager.claimOrStartActiveQuickShare(itemB, peerIp, "b.jpg", 20L, peerName, null))

        TransferManager.requestCancel(aId)

        assertEquals("the cancelled row is final", TransferManager.Status.CANCELLED, TransferManager.get(aId)?.status)
        assertEquals("the sibling keeps flowing untouched", TransferManager.Status.ACTIVE, TransferManager.get(bId)?.status)
        assertFalse("the sibling's item is not refused by the cancelled item's tombstone",
            TransferManager.wasCancelledForQuickShare(itemB, peerIp))
        assertTrue("the cancelled item's own guard holds",
            TransferManager.wasCancelledForQuickShare(itemA, peerIp))
        TransferManager.complete(bId)
        assertEquals("the sibling completes normally", TransferManager.Status.COMPLETED, TransferManager.get(bId)?.status)
    }

    // ── Pass-14: resumable receives + in-place app→browser resume ────────────

    @Test
    fun `interrupted quick-share download with range re-request resumes in place from the checkpoint`() {
        // P18 root scenario: a browser's download manager re-requests with
        // "Range: bytes=N-" after a connection loss at N bytes. The OLD code
        // minted a fresh row reading 0% under a browser already at N; the
        // fix re-opens the ORIGINAL interrupted row under its SAME id with
        // its counter set to N — one card, progress a true continuation.
        val item = qsName(60)
        val id = TransferManager.claimOrStartPending("video.mp4", 1000L, peerIp, peerName, qsItemName = item)
        assertNotNull(id)
        val tid = id!!
        assertEquals(tid, TransferManager.claimOrStartActiveQuickShare(item, peerIp, "video.mp4", 1000L, peerName, null))
        TransferManager.addProgress(tid, 600L)                       // 60% received
        TransferManager.downloadConnectionAborted(tid)               // connection lost
        assertEquals(TransferManager.Status.FAILED, TransferManager.get(tid)?.status)
        assertTrue("an interruption latches the interrupted presentation flag", TransferManager.get(tid)!!.interrupted)

        // The download manager's re-request, Range: bytes=600-
        val resumed = TransferManager.claimOrStartActiveQuickShare(item, peerIp, "video.mp4", 1000L, peerName, null, rangeStart = 600L)
        assertEquals("the SAME row id is re-opened in place — never a duplicate", tid, resumed)
        val t = TransferManager.get(tid)!!
        assertEquals(TransferManager.Status.ACTIVE, t.status)
        assertEquals("the counter picks up from the client's actual partial", 600L, t.transferredBytes)
        assertEquals("the row is marked as a resumed leg (the 'Resuming' chip)", 600L, t.resumedFromBytes)
        assertEquals("exactly one row exists for this identity",
            1, TransferManager.activeSnapshot().count { it.qsItemName == item && it.peerIp == peerIp })

        // Deltas land on top of the checkpoint and the row completes.
        TransferManager.addProgress(tid, 400L)
        TransferManager.complete(tid)
        assertEquals(TransferManager.Status.COMPLETED, TransferManager.get(tid)?.status)
        assertEquals(1000L, TransferManager.get(tid)!!.transferredBytes)
    }

    @Test
    fun `re-sent native-push file adopts the existing waiting-for-reconnection card instead of minting a duplicate`() {
        // P15 requirement 4: a NEW push of a file whose interrupted partial
        // still exists re-uses the exact existing card (same id, checkpoint
        // kept, re-keyed to the new request id), so no second card appears
        // next to a stuck Waiting-for-reconnection one.
        val destPath = "/sdcard/LumiDrop X/received/movie.bin"
        val oldId = TransferManager.startPendingUpload(
            requestId = "oldpush::movie.bin", fileName = "movie.bin", totalBytes = 500L,
            peerIp = peerIp, peerName = peerName, source = TransferManager.Source.NATIVE_PUSH)
        assertEquals(oldId, TransferManager.claimPendingUpload("oldpush::movie.bin", destPath))
        TransferManager.addProgress(oldId!!, 300L)
        TransferManager.fail(oldId, interrupted = true, keepForResume = true)

        val stuck = TransferManager.get(oldId)!!
        assertEquals(TransferManager.Status.FAILED, stuck.status)
        assertTrue(stuck.interrupted)
        assertTrue("kept-for-resume rows are flagged for the discard-confirm UI", TransferManager.isKeptForResume(oldId))

        // The receiver accepts the NEW push → registerPendingTransfersFor's
        // adoption path re-keys the old row instead of startPendingUpload.
        val adopted = TransferManager.adoptResumableRowAsPending(oldId, "newpush::movie.bin", destPath, null)
        assertEquals("adoption keeps the SAME row id", oldId, adopted)
        val t = TransferManager.get(oldId)!!
        assertEquals(TransferManager.Status.PENDING, t.status)
        assertEquals("re-keyed to the new push's request id", "newpush::movie.bin", t.uploadRequestId)
        assertEquals("the byte checkpoint survives adoption", 300L, t.transferredBytes)
        assertFalse("no longer kept-for-resume once adopted", TransferManager.isKeptForResume(oldId))

        // The sender's file POST claims the adopted row and resumes from it.
        assertEquals(oldId, TransferManager.claimPendingUpload("newpush::movie.bin", destPath))
        assertEquals("a claimed adopted row is marked as a resumed leg", 300L, TransferManager.get(oldId)!!.resumedFromBytes)
        TransferManager.addProgress(oldId, 200L)
        TransferManager.complete(oldId)
        assertEquals(TransferManager.Status.COMPLETED, TransferManager.get(oldId)?.status)
        assertEquals("still exactly one card for this file across the whole cycle (never a duplicate minted)",
            1, TransferManager.snapshot().count { it.fileName == "movie.bin" })
    }

    @Test
    fun `restored resumable waiting rows are discardable and prunable with their partial`() {
        // P15 requirements 2+3: the cold-start restoration creates a
        // FAILED+interrupted kept-for-resume card; a discard (or the partial
        // vanishing) removes it cleanly, and pruning never touches a row
        // whose partial still exists.
        val kept = TransferManager.createResumableWaitingRow(
            fileName = "album.zip", totalBytes = 900L, peerIp = peerIp, peerName = peerName,
            filePath = "/sdcard/LumiDrop X/received/album.zip", transferredBytes = 450L)
        val gone = TransferManager.createResumableWaitingRow(
            fileName = "stale.zip", totalBytes = 800L, peerIp = peerIp, peerName = peerName,
            filePath = "/sdcard/LumiDrop X/received/stale.zip", transferredBytes = 100L)
        assertTrue(TransferManager.isKeptForResume(kept))
        assertTrue(TransferManager.isKeptForResume(gone))
        assertEquals(TransferManager.Status.FAILED, TransferManager.get(kept)!!.status)
        assertTrue(TransferManager.get(kept)!!.interrupted)
        assertEquals(450L, TransferManager.get(kept)!!.transferredBytes)

        // The 7-day-sweep prune: rows whose partial vanished dismiss
        // themselves; rows whose partial exists stay.
        TransferManager.pruneResumableRows { path -> path != null && !path.endsWith("stale.zip") }
        assertNotNull("a row with a live partial survives the prune", TransferManager.get(kept))
        assertNull("a row whose partial is gone dismisses itself", TransferManager.get(gone))

        // The user-confirmed discard.
        TransferManager.dismissResumable(kept)
        assertNull(TransferManager.get(kept))
        assertFalse(TransferManager.isKeptForResume(kept))
    }

    @Test
    fun `a deliberate cancel of an interrupted receive never enters the resumable state`() {
        // P2: clicking X on an in-progress item is a DECISION — the row
        // resolves CANCELLED, keeps no resume presentation, and (per the
        // receive-side cleanup) is never registered as kept-for-resume.
        val oldId = TransferManager.startPendingUpload(
            requestId = "push2::doc.pdf", fileName = "doc.pdf", totalBytes = 400L,
            peerIp = peerIp, peerName = peerName, source = TransferManager.Source.NATIVE_PUSH)
        assertEquals(oldId, TransferManager.claimPendingUpload("push2::doc.pdf", "/sdcard/doc.pdf"))
        TransferManager.addProgress(oldId!!, 150L)
        TransferManager.requestCancel(oldId)
        val t = TransferManager.get(oldId)!!
        assertEquals(TransferManager.Status.CANCELLED, t.status)
        assertFalse("a deliberate cancel is never presented as interrupted", t.interrupted)
        assertFalse("a deliberate cancel is never kept for resume", TransferManager.isKeptForResume(oldId))
    }

    // ── Pass-22 (#4/#5/#6) ────────────────────────────────────────────────────

    @Test
    fun `re-send after a COMPLETED transfer survives the duplicate sweep`() {
        // The reported bug: after a file completed for this client, sending
        // the SAME file again minted a fresh PENDING row that
        // sweepDuplicatePendingQuickShare resolved CANCELLED as a "duplicate"
        // of the old COMPLETED row — the re-send died seconds after Send.
        val first = TransferManager.claimOrStartPending("report.pdf", 900L, peerIp, peerName,
            qsItemName = "report.pdf", filePath = "/sdcard/report.pdf")
        assertNotNull(first)
        assertEquals(first, TransferManager.claimOrStartActiveQuickShare("report.pdf", peerIp,
            "report.pdf", 900L, peerName, "/sdcard/report.pdf"))
        TransferManager.complete(first!!)

        // The deliberate re-send: same file, same client, same queue name.
        val second = TransferManager.claimOrStartPending("report.pdf", 900L, peerIp, peerName,
            qsItemName = "report.pdf", filePath = "/sdcard/report.pdf")
        assertNotNull("claimOrStartPending must mint a fresh row for the re-send", second)

        // The exact moment the old code killed it:
        TransferManager.sweepDuplicatePendingQuickShare()
        val row = TransferManager.get(second!!)
        assertTrue("the re-send's fresh Waiting row SURVIVES the duplicate sweep next to the old COMPLETED row",
            row != null && row.status == TransferManager.Status.PENDING)
        // And it streams under its OWN identity, adopting nothing from the first.
        assertEquals(second, TransferManager.claimOrStartActiveQuickShare("report.pdf", peerIp,
            "report.pdf", 900L, peerName, "/sdcard/report.pdf"))
        TransferManager.complete(second)
        assertEquals("two genuine sends = two Sent rows",
            2, TransferManager.sentSnapshot().count { it.fileName == "report.pdf" })
    }

    @Test
    fun `re-send after a CANCELLED transfer works and the tombstone discrimination is answerable`() {
        // The first send is deliberately cancelled → tombstone recorded.
        val first = TransferManager.claimOrStartPending("song.mp3", 5000L, peerIp, peerName,
            qsItemName = "song.mp3")
        assertNotNull(first)
        TransferManager.requestCancel(first!!)
        assertTrue("the QS tombstone is recorded at cancel time",
            TransferManager.cancelledDownloadAt("qs", "song.mp3", peerIp) > 0L)
        assertTrue(TransferManager.wasCancelledForQuickShare("song.mp3", peerIp))

        // The deliberate re-send: the claim path clears the old tombstone (a
        // fresh registration supersedes the old verdict) and mints a new row.
        assertEquals(TransferManager.Status.CANCELLED, TransferManager.get(first)!!.status)
        val second = TransferManager.claimOrStartPending("song.mp3", 5000L, peerIp, peerName,
            qsItemName = "song.mp3")
        assertNotNull("re-send after CANCELLED must mint a fresh row", second)
        assertEquals("the stale tombstone no longer refuses the new transfer's first request",
            0L, TransferManager.cancelledDownloadAt("qs", "song.mp3", peerIp))
        assertNull("the old cancelled record is REMOVED by the fresh registration — it can no longer block or shadow the new send",
            TransferManager.get(first))
        assertEquals(TransferManager.Status.PENDING, TransferManager.get(second!!)!!.status)
    }

    @Test
    fun `orphaned native send rows resolve Failed instead of waiting forever`() {
        // #6's backstop: a PENDING SEND row whose owning session machinery is
        // gone (the rare accept-then-nothing wedge) must reach an honest
        // FAILED resolution — never an indefinite "Waiting".
        val savedOwner = TransferManager.nativeSendRowOwner
        val savedWindow = TransferManager.nativeSendOrphanMs
        try {
            val owned = TransferManager.startPendingSend("kept.txt", 10L, null, "10.0.0.7", "Peer A")
            val orphan = TransferManager.startPendingSend("lost.txt", 10L, null, "10.0.0.7", "Peer A")
            // A paused row is deliberately exempt — a parked pause is owned
            // by its own resume path, whatever the session registry says.
            val paused = TransferManager.startPendingSend("held.txt", 10L, null, "10.0.0.7", "Peer A")
            TransferManager.requestPause(paused)
            // The owner registry owns exactly `owned` (a live session) and the
            // paused row; `orphan` belongs to a session that no longer exists.
            TransferManager.nativeSendRowOwner = { tid -> tid == owned || tid == paused }
            TransferManager.nativeSendOrphanMs = 1L

            Thread.sleep(20)
            TransferManager.expireStalePending()

            assertEquals("a live session's row keeps waiting", TransferManager.Status.PENDING,
                TransferManager.get(owned)!!.status)
            val orphanRow = TransferManager.get(orphan)
            assertTrue("the orphaned send resolves Failed (visible, honest — never stuck Waiting)",
                orphanRow == null || orphanRow.status == TransferManager.Status.FAILED)
            assertTrue("the orphan is not presented as interrupted — no bytes ever moved",
                orphanRow == null || !orphanRow.interrupted)
            assertEquals("a deliberately paused row is exempt from the orphan sweep",
                TransferManager.Status.PENDING, TransferManager.get(paused)!!.status)
        } finally {
            TransferManager.nativeSendRowOwner = savedOwner
            TransferManager.nativeSendOrphanMs = savedWindow
        }
    }
}
