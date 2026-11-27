package com.lumidrop.x.transfer

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory, in-process registry of every upload/download transfer the host
 * has handled since the server last started. Single source of truth for the
 * Transfers tab, its badge counter, and the floating popup.
 *
 * Lives entirely in memory — nothing here is persisted across app restarts,
 * which is the right call for a live transfer monitor (history from a
 * previous server session isn't meaningful to show as "active" or
 * actionable once the process restarts).
 *
 * Thread-safety: LumiDropServer's NanoHTTPD worker threads (one per
 * in-flight HTTP request) call into this object concurrently from many
 * different threads. Per-chunk progress reporting ([updateProgress] /
 * [addProgress]) stays lock-free — a [ConcurrentHashMap] plus `@Volatile`
 * fields (and an internal `AtomicLong` backing `transferredBytes`) on
 * [Transfer] keep that safe and fast even at hot-path call rates. The
 * lower-frequency "does a transfer for this identity already exist, or do I
 * need to create one" operations — [claimPending], [claimPendingUpload],
 * [claimOrStartPending], [claimOrStartActiveQuickShare],
 * [claimOrStartActivePlainDownload], [complete], [fail] — are
 * `@Synchronized`: those are check-then-act sequences across [transfers]
 * that a plain ConcurrentHashMap does not make atomic on its own, and
 * getting one of them wrong is exactly what produces a duplicate
 * Transfers-tab entry for one download that happened to open several
 * parallel connections (a browser's own segmented-Range fetch of one large
 * file, most visibly). The UI (main) thread only ever reads via [snapshot]
 * or flips a control flag ([requestCancel]/[requestPause]/[requestResume])
 * — a plain volatile write, safely visible to whichever worker thread is
 * mid-transfer.
 */
object TransferManager {

    /** Cancelled/failed transfers clear themselves out instead of sitting in
     *  the Active list until manually dismissed. Cancelled barely lingers —
     *  just long enough to not look like it vanished instantly, which reads
     *  as a glitch rather than a confirmation; failed stays for about a
     *  second, long enough to actually register as "this one didn't make
     *  it" before it clears. A plain java.util.Timer rather than a Handler:
     *  this object has no Android framework dependency anywhere else
     *  (ConcurrentHashMap/Atomic* only), and a bare Handler would be the
     *  only thing pulling one in. */
    private val autoRemovalTimer = java.util.Timer("transfer-auto-removal", true)
    // v1.0 (§hotspot-off resume): pending auto-removal tasks, tracked by id so
    // a FAILED transfer re-opened for byte-level resume can cancel its own
    // scheduled dismissal before it fires — otherwise a reconnected upload
    // could be swept out from under its own second attempt mid-stream.
    private val pendingRemovals = java.util.concurrent.ConcurrentHashMap<String, java.util.TimerTask>()
    private const val CANCELLED_LINGER_MS = 400L
    private const val FAILED_LINGER_MS = 1000L
    // ── FINAL PASS 2 (§1 — the receiver's kept ZIP auto-continue card) ───────
    // A streamed ZIP whose attempt was severed by a disconnection is KEPT
    // on the receiver as "Auto-Continue on Reconnection" for exactly as
    // long as the sender's own auto-resume window can still deliver a
    // fresh attempt (AUTO_RESUME_WINDOW_MS, 10 min in NativePushClient)
    // plus the same margin AUTO_RESUME_SEND_STALL_MS adds on the sender.
    // After that the recovery process has provably stopped (the sender
    // resolved its own row FAILED) and the kept card sweeps itself out —
    // the state is never left indefinitely (§1.3).
    private const val AUTO_CONTINUE_ZIP_LINGER_MS = 11 * 60_000L

    // ── Pass-11 (ghost-transfer root fix): durable cancel tombstones ──────
    // A CANCELLED upload row is auto-dismissed from `transfers` after only
    // CANCELLED_LINGER_MS (400 ms) — but the sender's auto-resume loop may
    // re-POST the same file as much as 10 minutes later (bounded backoff),
    // and the 15 s `wasCancelledForUpload` window previously consulted only
    // LIVE rows. The tombstone below records every deliberately-cancelled
    // upload REQUEST id at cancel time, so the refusal outlives the row's
    // dismissal for the same window the sender may still be retrying — the
    // re-POST is answered "Cancelled by host" (which the sender's retry
    // loop treats as a final decision) instead of minting a ghost row via
    // the start() fallback. A tombstone is only ever written for a genuine
    // user/host cancel — an interrupted failure never records one, so real
    // auto-resume is never blocked by it.
    private val cancelledUploadRequests = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val CANCELLED_UPLOAD_TOMBSTONE_MS = 10 * 60_000L
    // ── GOAT-PASS 11 (§7 — whole-folder cancellation must tombstone EVERY ──
    // child): a folder the receiver cancels resolves one row per manifest
    // entry — 694 files wrote 694 tombstones into a 128-slot map, and the
    // cap's eviction (oldest-first, and every entry is milliseconds old)
    // silently dropped 566 of them on the floor mid-cancel. A still-probing
    // sender's re-POST of any evicted path then sailed past this guard into
    // the start() fallback, minting a fresh row under the folder's group —
    // the cancelled folder "reappearing, one ghost child at a time" with a
    // growing total. The cap now comfortably exceeds any single folder's
    // file count; the per-push latch on LumiDropServer backs it up for the
    // paths this map can no longer hold.
    private const val CANCELLED_UPLOAD_TOMBSTONE_CAP = 4096

    // ── Pass-13 (ghost-transfer root fix, download half) ───────────────────
    // The DOWNLOAD twin of the upload tombstone above. A cancelled
    // DOWNLOAD row auto-dismisses after CANCELLED_LINGER_MS (400 ms), but
    // wasCancelledForQuickShare/wasCancelledForPlainDownload below used to
    // consult ONLY the live `transfers` map — so the documented 15-second
    // anti-retry window was effectively a 400 ms window. Any late re-request
    // (a browser download manager's automatic retry, a parallel Range
    // connection landing after the cancel, the page's own re-dispatch) then
    // sailed past the endpoint guard into claimOrStartActiveQuickShare's
    // start() fallback, which minted a FRESH ACTIVE row for a transfer the
    // user had explicitly cancelled — the reported Active-tab "ghost file"
    // that streams to completion and even lands in history. Exactly the
    // resurrection hole pass-11's durable tombstone closed for uploads, now
    // closed for downloads too: requestCancel records a tombstone keyed by
    // (kind, item name, peer IP) and the guard reads it for the full
    // CANCEL_TOMBSTONE_WINDOW_MS, independent of the row's own dismissal.
    private val cancelledDownloadRequests = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val CANCELLED_DOWNLOAD_TOMBSTONE_CAP = 256
    private fun downloadTombstoneKey(kind: String, itemName: String, peerIp: String) =
        kind + "\u0000" + itemName + "\u0000" + peerIp

    // ── Pass-15 (ghost-item root fix, SENDER half): durable tid cancel latch ──
    // requestCancel() finalizes a row to CANCELLED and it auto-dismisses from
    // the live map after only CANCELLED_LINGER_MS (400 ms). Every later reader
    // of "was this deliberately cancelled?" — most critically the sender's
    // own sendOneFile retry loop, whose loop-top check used to be a bare
    // isCancelled(tid) — then saw a row that no longer exists and answered
    // "not cancelled". The sender's CANCELLED row had already vanished while
    // its invisible retry loop kept re-POSTing the file: on the receiver each
    // re-POST minted a fresh ACTIVE row (the pre-registered row was gone, so
    // the start() fallback ran) whose progress "continued" from the preserved
    // checkpoint — the reported ghost item that reappears seconds after every
    // cancel, with NOTHING visible on the sender. The latch below keeps the
    // deliberate-cancel verdict answerable by tid for the full sender-side
    // auto-resume window, exactly like the upload/download tombstones already
    // do for the request-id-keyed questions. A latch is only ever written for
    // a genuine requestCancel — an interrupted failure never records one, so
    // real automatic resume is never blocked by it.
    private val cancelledTidLatches = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val CANCELLED_TID_LATCH_MS = 10 * 60_000L   // == NativePushClient.AUTO_RESUME_WINDOW_MS
    // ── GOAT-PASS 11 (§7): same reasoning as CANCELLED_UPLOAD_TOMBSTONE_CAP —
    // a 694-file folder's whole-push cancel wrote 694 latches into a 256-
    // slot map; every entry beyond the cap was evicted instantly, and the
    // sender's per-file loop-top abandon check went blind for exactly the
    // children a whole-folder cancel most needs to cover.
    private const val CANCELLED_TID_LATCH_CAP = 4096

    /** Pass-15: records the durable by-tid cancel latch — called from
     *  [requestCancel] itself so every deliberate cancel path funnels through
     *  exactly one writer. */
    private fun rememberCancelledTid(id: String) {
        if (id.isEmpty()) return
        val now = System.currentTimeMillis()
        cancelledTidLatches[id] = now
        if (cancelledTidLatches.size > CANCELLED_TID_LATCH_CAP) {
            val cutoff = now - CANCELLED_TID_LATCH_MS
            val iter = cancelledTidLatches.entries.iterator()
            while (iter.hasNext()) if (iter.next().value < cutoff) iter.remove()
            while (cancelledTidLatches.size > CANCELLED_TID_LATCH_CAP) {
                var oldestKey: String? = null; var oldestAt = Long.MAX_VALUE
                for ((k, v) in cancelledTidLatches) if (v < oldestAt) { oldestAt = v; oldestKey = k }
                if (oldestKey == null) break
                cancelledTidLatches.remove(oldestKey)
            }
        }
    }

    /** Pass-15: true when [id] was deliberately cancelled within the latch
     *  window — INCLUDING after the CANCELLED row itself has auto-dismissed
     *  from the live map. */
    fun wasCancelledTid(id: String): Boolean {
        val at = cancelledTidLatches[id] ?: return false
        return System.currentTimeMillis() - at < CANCELLED_TID_LATCH_MS
    }

    /** Pass-15: the ONE deliberate-cancel predicate for anything that owns a
     *  row's lifecycle across the row's own dismissal — the live-row flag OR
     *  the durable latch. The sender's retry loop, the receive stream loops
     *  and the sweeps all read this instead of a bare isCancelled(). */
    fun isCancelAuthoritative(id: String): Boolean = isCancelled(id) || wasCancelledTid(id)

    /** Pass-13: records a durable download-cancel tombstone — called from
     *  [requestCancel] itself, so every cancel path (row X button, Cancel
     *  All, kick-device, sweep) funnels through exactly one writer. A
     *  tombstone is only ever written for a deliberate cancel; an
     *  interrupted failure never records one, so genuine automatic resume /
     *  retry behavior is never blocked. */
    private fun rememberCancelledDownload(kind: String, itemName: String, peerIp: String) {
        if (itemName.isEmpty() || peerIp.isEmpty()) return
        val now = System.currentTimeMillis()
        cancelledDownloadRequests[downloadTombstoneKey(kind, itemName, peerIp)] = now
        if (cancelledDownloadRequests.size > CANCELLED_DOWNLOAD_TOMBSTONE_CAP) {
            val cutoff = now - CANCEL_TOMBSTONE_WINDOW_MS
            val iter = cancelledDownloadRequests.entries.iterator()
            while (iter.hasNext()) if (iter.next().value < cutoff) iter.remove()
            while (cancelledDownloadRequests.size > CANCELLED_DOWNLOAD_TOMBSTONE_CAP) {
                var oldestKey: String? = null; var oldestAt = Long.MAX_VALUE
                for ((k, v) in cancelledDownloadRequests) if (v < oldestAt) { oldestAt = v; oldestKey = k }
                if (oldestKey == null) break
                cancelledDownloadRequests.remove(oldestKey)
            }
        }
    }

    private fun wasCancelledDownloadTombstoned(kind: String, itemName: String, peerIp: String): Boolean {
        val at = cancelledDownloadRequests[downloadTombstoneKey(kind, itemName, peerIp)] ?: return false
        return System.currentTimeMillis() - at < CANCEL_TOMBSTONE_WINDOW_MS
    }

    /** Pass-17 (a genuine re-send must never be refused): forgets the
     *  durable deliberate-cancel tombstone for exactly this (kind, item,
     *  peer). The tombstone exists to stop a download manager's own
     *  AUTOMATIC retry from silently undoing a cancel — but once the host
     *  genuinely starts a NEW send of the same file to the same client
     *  (a fresh queue item + pre-registered row, or a fresh accept/
     *  registration), that decision has been superseded and the old
     *  tombstone must not answer the new transfer's very first byte request
     *  with "Cancelled by host" — the exact report of a re-send sitting
     *  stuck on "Waiting" with the browser's download manager showing
     *  "Failed" until an app restart (which clears the in-memory
     *  tombstones) made it work again. */
    fun clearCancelledDownloadTombstone(kind: String, itemName: String, peerIp: String) {
        if (itemName.isEmpty() || peerIp.isEmpty()) return
        cancelledDownloadRequests.remove(downloadTombstoneKey(kind, itemName, peerIp))
    }

    /** ── Pass-22 (#4/#5 — fresh sends vs. resurrected cancels): WHEN the ──────
     *  live deliberate-cancel tombstone for this (kind, item, peer) was
     *  recorded — 0L when none applies. The QS endpoints compare this to a
     *  queue item's own creation time so a genuinely NEW send of the same
     *  name sails through while the re-armed chip of a cancelled item can
     *  never be auto-accepted back to life. */
    fun cancelledDownloadAt(kind: String, itemName: String, peerIp: String): Long =
        cancelledDownloadRequests[downloadTombstoneKey(kind, itemName, peerIp)] ?: 0L

    /** Pass-11: records a durable tombstone for a deliberately-cancelled
     *  upload request — consult [wasCancelledForUpload]. Called from
     *  [requestCancel] itself, so every cancel path (receiver row tap,
     *  Cancel All, whole-push/group cancel, sender-propagated control)
     *  records exactly once per request id. */
    fun rememberCancelledUpload(requestId: String?) {
        if (requestId == null || requestId.isEmpty()) return
        cancelledUploadRequests[requestId] = System.currentTimeMillis()
        if (cancelledUploadRequests.size > CANCELLED_UPLOAD_TOMBSTONE_CAP) {
            val cutoff = System.currentTimeMillis() - CANCELLED_UPLOAD_TOMBSTONE_MS
            val iter = cancelledUploadRequests.entries.iterator()
            while (iter.hasNext()) if (iter.next().value < cutoff) iter.remove()
            while (cancelledUploadRequests.size > CANCELLED_UPLOAD_TOMBSTONE_CAP) {
                var oldestKey: String? = null; var oldestAt = Long.MAX_VALUE
                for ((k, v) in cancelledUploadRequests) if (v < oldestAt) { oldestAt = v; oldestKey = k }
                if (oldestKey == null) break
                cancelledUploadRequests.remove(oldestKey)
            }
        }
    }
    // Reliability pass §2 (Reconnect): a FAILED download (an interrupted
    // RECEIVE — this device's client lost the connection mid-file) stays in
    // the engine for ten minutes instead of ~1s, because its row is the
    // user's handle for reconnecting: the web client's active-transfers list
    // renders a Reconnect button that resolves this very record
    // (apiTransferReconnect needs id → filePath/qsItemName to answer), and
    // the native Active tab keeps showing it with its own actions for the
    // same window. Failed SEND/UPLOAD transfers keep the ~1s flash — their
    // retry lives on the sender's side (web Retry is client-authoritative;
    // native pushes retry through NativePushClient), so an engine-side
    // record buys nothing there.
    private const val FAILED_DOWNLOAD_LINGER_MS = 10 * 60_000L
    // §2 (duplicate history): how long after completion a re-request for the
    // exact same file+client is treated as the SAME logical download rather
    // than a new one — sized for browser-retry / download-manager re-request
    // timing, far below any deliberate "download it again" interval.
    private const val SAME_LOGICAL_DOWNLOAD_REOPEN_MS = 30_000L

    private fun scheduleAutoRemoval(id: String, delayMs: Long) {
        val task = object : java.util.TimerTask() {
            override fun run() { dismiss(id) }
        }
        pendingRemovals[id]?.cancel()
        pendingRemovals[id] = task
        autoRemovalTimer.schedule(task, delayMs)
    }

    // ── Pass-14 (resumable receives): rows that may NOT auto-dismiss ────────
    // An interrupted native-push RECEIVE whose staging partial still exists
    // is a resumable transfer: its card must STAY in the Active tab (in the
    // "Waiting for reconnection" state) for as long as the partial data
    // does — across the sender's window, across this app's own restart —
    // until the transfer resumes on the same card, the user discards it, or
    // the partial itself disappears. fail(..., keepForResume = true) is the
    // one writer of that state; the dismissal paths below all bypass the
    // auto-removal timer for such rows.
    // GOAT-PASS 9 (Android 5–7): Collections.newSetFromMap(ConcurrentHashMap)
    // — the API-1-safe shape NativePushClient's own sets already use — instead
    // of ConcurrentHashMap.newKeySet(), a Java-8 STATIC factory the platform
    // only carries from API 24 (never desugared: it crashed with
    // NoSuchMethodError at this object's class-init on Android 5/6, taking
    // the whole transfer engine down with it).
    private val resumableRowIds: MutableSet<String> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** True when [id] is a kept-for-resume interrupted receive row (the
     *  receiver-side "Waiting for reconnection" card backed by real partial
     *  data). Used by the Active tab to route its X through the discard
     *  confirmation instead of a plain dismiss. */
    fun isKeptForResume(id: String): Boolean = resumableRowIds.contains(id)

    /** Marks a kept-for-resume row as no longer resumable (its data was
     *  consumed by a completed resume, deleted by a discard, or vanished)
     *  and dismisses it from the live map. Safe on unknown/foreign ids. */
    fun dismissResumable(id: String) {
        resumableRowIds.remove(id)
        dismiss(id)
    }

    /** Prunes kept-for-resume rows whose backing partial no longer passes
     *  [stillValid] (called with the row's filePath). The 7-day staging
     *  sweep, a user deleting files behind the app's back, or any other
     *  partial-vanishing path lands here — a "Waiting for reconnection"
     *  card with nothing left to reconnect to must not linger.
     *
     *  GOAT-PASS 6 (§2): only FAILED rows are prunable now. A resumable id
     *  whose row was re-opened LIVE (a resumed leg streaming again) must
     *  never be dismissed mid-flight — a prune that raced the fresh-start
     *  window (staging deleted, then recreated by the pipelined writer)
     *  could otherwise kill a live receive's card. Adoption/leg transitions
     *  remove the id themselves; this guard is the belt-and-braces. */
    fun pruneResumableRows(stillValid: (String?) -> Boolean) {
        val toDrop = resumableRowIds.filter { id ->
            val t = transfers[id]
            t == null || (t.status == Status.FAILED && !stillValid(t.filePath))
        }
        for (id in toDrop) {
            resumableRowIds.remove(id)
            if (transfers[id] != null) dismiss(id)
        }
    }

    /** v1.0 (§hotspot-off resume): re-opens a FAILED transfer under its
     * ORIGINAL id for a byte-level resumed continuation (the web client's
     * Reconnect re-sends the remaining bytes of the very same upload). The
     // scheduled auto-removal is cancelled first — the row is live again,
     * not a 10-minute tombstone. Only FAILED rows qualify: CANCELLED was a
     * deliberate host/user stop (its partials are already deleted) and
     * anything else has nothing to resume. Returns false when the id is
     * unknown or not in a resumable state. */
    @Synchronized
    fun reopenForResume(id: String): Boolean {
        val t = transfers[id] ?: return false
        if (t.status != Status.FAILED) return false
        pendingRemovals.remove(id)?.cancel()
        bumpResumeGen(t)   // Pass-17: a NEWER leg owns this row now
        t.status = Status.ACTIVE
        t.cancelRequested = false
        t.pauseRequested = false
        t.pauseStartedAtMs = 0L   // Pass-18
        // ── GOAT-PASS 3 (§6): the interrupted leg that owed this debt is
        // gone; the new leg acquires its slot through the endpoint's own
        // claim chain before its first byte.
        t.pauseYieldedSlots.set(0)
        t.pauseReacquireArmed = false
        t.finishedAt = 0L
        t.speedBps = 0.0
        t.interrupted = false   // Pass-8 §7: a resumed transfer is live again, not interrupted
        t.reconnecting = false
        // ── GOAT-PASS 7 (§2B): a re-opened row's stale speed sample baseline ──
        // (latched by the leg that just died — possibly minutes ago) must not
        // poison the resumed leg's first reading. Arm a fresh baseline: the
        // next progress update latches at the resume's true first byte.
        armSpeedBaseline(t)
        // Pass-14 (resume wording): a resume with a real checkpoint reads as
        // one — "Resuming X%" — for as long as this leg lives.
        if (t.transferredBytes > 0L) t.resumedFromBytes = t.transferredBytes
        t.connectionCount.set(1)
        touchT(t)
        return true
    }

    /** v1.0 (§hotspot-off): read-only companion of [reopenForResume] — is this
     *  id present and still FAILED (i.e. inside its reconnect window)? Used by
     *  the resume-offset endpoint to answer honestly before the client spends
     *  a single byte re-sending. */
    fun reopenableForResume(id: String): Boolean {
        val t = transfers[id] ?: return false
        return t.status == Status.FAILED
    }

    /** Pass-10 (automatic resume): read-only lookup — the id of this
     *  request's FAILED-because-interrupted UPLOAD row, if one exists.
     *  Used by the native-push receive endpoint as the receiving-slot wait
     *  key for a resumed leg, BEFORE deciding to re-open it. */
    fun findInterruptedUploadId(requestId: String): String? =
        transfers.values.firstOrNull {
            it.status == Status.FAILED && it.interrupted && it.direction == Direction.UPLOAD &&
                it.uploadRequestId == requestId
        }?.id

    // ── Pass-17 (no duplicate upload rows/files) ────────────────────────────
    /** The COMPLETED UPLOAD row for [requestId], when this exact logical
     *  upload finished recently — a retried POST whose first leg already
     *  delivered every byte (the browser lost the success response to a
     *  connection blip, its auto-reconnect then re-POSTed) must be
     *  IDEMPOTENT: answered with the original success, never streamed a
     *  second time into a "(1)"-suffixed duplicate file under a fresh
     *  row (the reported "the image appeared twice in Received, once From
     *  iPad · Safari and once From <raw IP>"). Window: the same 10-minute
     *  reconnect horizon the client's own retry engine lives inside. */
    fun findRecentlyCompletedUpload(requestId: String): Transfer? {
        if (requestId.isEmpty()) return null
        val now = System.currentTimeMillis()
        return transfers.values.firstOrNull {
            it.status == Status.COMPLETED && it.direction == Direction.UPLOAD &&
                it.uploadRequestId == requestId && it.finishedAt > 0L &&
                now - it.finishedAt < 10 * 60_000L
        }
    }

    // ── Pass-17 (one card per transfer, receiver half) ──────────────────────
    /** A resume re-POST whose predecessor row is still NOMINALLY ACTIVE —
     *  its connection died silently, the read is blocked, and the 15-second
     *  stall watchdog has not fired yet — must ADOPT that row, not mint a
     *  second one beside it (the reported "after reconnection, one card says
     *  Waiting-for-reconnection while another continues the same file").
     *  A sender never re-POSTs while its own leg is healthy, so a second
     *  POST for this request id proves the old leg is dead; this resolves
     *  the stale row in place as interrupted (kept-for-resume when a real
     *  partial exists) so the ordinary claim chain then re-opens the SAME
     *  row for the new leg. A row with bytes flowed within the last 2.5 s
     *  is treated as genuinely still streaming: the retirement is skipped
     *  and the caller refuses the POST ("already in progress") so the two
     *  writers can never interleave on one staging file. Returns true when
     *  the row was retired in place; false when nothing stale was found;
     *  null when the old leg is FRESHLY streaming and the caller must
     *  refuse. */
    @Synchronized
    fun retireStaleActiveUploadForRequest(requestId: String, hasPartial: Boolean): Boolean? {
        if (requestId.isEmpty()) return false
        val fresh = transfers.values.firstOrNull {
            it.status == Status.ACTIVE && it.direction == Direction.UPLOAD && it.uploadRequestId == requestId
        } ?: return false
        // ── Pass-20 (paused receive rows are parked, not stale) ──────────────
        // A row deliberately PAUSED (the sender aborted its POST on purpose
        // and the receiver keeps the row alive, ACTIVE+paused, with its
        // byte checkpoint and its exact list position) is doing exactly what
        // it was told — waiting out a user pause. Retiring it as interrupted
        // here (a) flashed "Waiting for reconnection" on a healthy, merely
        // paused transfer and (b) shoved it into the FAILED bucket so its
        // Active-tab POSITION moved on the pause — the reported "receiver's
        // paused item loses its progress display and jumps position". The
        // claim chain's explicit paused-row adoption (findPausedActive-
        // UploadId) handles it instead.
        if (fresh.pauseRequested) {
            return false
        }
        if (System.currentTimeMillis() - fresh.lastSampleAtMs <= 2500L) return null   // genuinely streaming
        // ── FINAL PASS 2 (§1 — a streamed ZIP retires into AUTO-CONTINUE) ─────
        // The old shape (fail interrupted + keepForResume=hasPartial) put the
        // row into resumableRowIds and preserved a byte checkpoint an
        // unknown-length archive can never actually resume against — and its
        // "Auto-Resume on Reconnection 62%" chip promised byte-resume. A
        // streamed ZIP retires into the dedicated auto-continue state instead:
        // same row, interrupted marker (so the re-POST's claim chain re-opens
        // THIS row in place), zeroed progress, no resumable registration, and
        // a bounded life of AUTO_CONTINUE_ZIP_LINGER_MS.
        if (fresh.isZipStream) {
            failForZipAutoContinue(fresh.id)
            return true
        }
        fail(fresh.id, interrupted = true, keepForResume = hasPartial)
        return true
    }

    /** Pass-20 (receiver pause keeps progress AND position): the
     *  ACTIVE-and-deliberately-paused UPLOAD row for [requestId], when one
     *  exists — the parked receive of a file whose sender yielded its POST
     *  on purpose (see LumiDropServer's paused-abort branch, which now
     *  KEEPS the row ACTIVE instead of demoting it to PENDING). The
     *  sender's resumed POST resolves THIS row so the continuation lands
     *  on the very same card — progress bar intact at its checkpoint,
     *  position in the Active tab unchanged — never a fresh row, never a
     *  bucket jump. Null when no such parked row exists. */
    @Synchronized
    fun findPausedActiveUploadId(requestId: String): String? {
        if (requestId.isEmpty()) return null
        return transfers.values.firstOrNull {
            it.status == Status.ACTIVE && it.pauseRequested &&
                it.direction == Direction.UPLOAD && it.uploadRequestId == requestId
        }?.id
    }

    /** Pass-10 (automatic resume): re-opens a known interrupted UPLOAD row
     *  (found via [findInterruptedUploadId]) under its ORIGINAL id for a
     *  resumed continuation — the sender's automatic resume re-POSTs the
     *  remainder of the very same file, so the receiver must keep ONE row
     *  (back to Active, progress continuing from the preserved checkpoint),
     *  never a second one minted next to the lingering Interrupted one.
     *  Returns the id on success, null when the row vanished or is no
     *  longer in the re-openable state. */
    @Synchronized
    fun reopenInterruptedUploadByExactId(id: String, filePath: String?): String? {
        val t = transfers[id] ?: return null
        if (t.status != Status.FAILED || !t.interrupted || t.direction != Direction.UPLOAD) return null
        resumableRowIds.remove(id)   // Pass-14: the kept-for-resume card is being consumed by its own resume
        if (!reopenForResume(t.id)) return null
        if (filePath != null) t.filePath = filePath
        // ── FINAL PASS 2 (§1 — a re-opened streamed ZIP restarts from 0 MB): ──
        // an unknown-length archive has no per-offset identity; the fresh
        // attempt streams from byte 0. Zero both counters so the row renders
        // an honest 0% (never "Resuming N%" over data the receiver deleted)
        // and the new attempt's first progress tick owns the bar from zero.
        if (t.isZipStream) {
            t.transferredBytes = 0L
            t.resumedFromBytes = 0L
            touchT(t)
        }
        return t.id
    }

    // ── Pass-14 (resumable receives): cross-session reuse + cold restore ─────

    /** Finds the receiver-side "Waiting for reconnection" row whose
     *  destination path is exactly [destPath] — the identity a NEW send of
     *  the same file must adopt instead of minting a duplicate card next to
     *  it. Interrupted, kept-for-resume UPLOAD rows only; most recent
     *  first, so two stale partials for one path never both adopt. */
    fun findResumableUploadByDestPath(destPath: String): Transfer? =
        transfers.values
            .filter { it.status == Status.FAILED && it.interrupted && it.direction == Direction.UPLOAD &&
                it.filePath == destPath }
            .maxByOrNull { it.seq }

    /** Pass-14 (resumable receives, requirement 4 — reuse the existing
     *  card): re-opens an interrupted receive row into PENDING under the
     *  request id of the NEW push that is re-sending the same file, keeping
     *  the SAME row id, byte checkpoint, and history. The ordinary claim
     *  chain then promotes it to ACTIVE exactly as if it had been
     *  pre-registered by this push — one card per logical file, its state
     *  changing Waiting-for-reconnection → Waiting → Resuming 62% → Done.
     *  Returns the adopted id, or null when [oldId] is not adoptable (not
     *  FAILED/interrupted, wrong direction, or already gone). */
    @Synchronized
    fun adoptResumableRowAsPending(oldId: String, newRequestId: String, filePath: String?, groupId: String?): String? {
        val t = transfers[oldId] ?: return null
        if (t.status != Status.FAILED || !t.interrupted || t.direction != Direction.UPLOAD) return null
        pendingRemovals.remove(oldId)?.cancel()
        resumableRowIds.remove(oldId)
        t.status = Status.PENDING
        t.cancelRequested = false
        t.pauseRequested = false
        t.pauseStartedAtMs = 0L   // Pass-18
        // ── GOAT-PASS 3 (§6): adopted by a NEW push's fresh leg — clear the
        // dead leg's slot debt (same reasoning as reopenForResume).
        t.pauseYieldedSlots.set(0)
        t.pauseReacquireArmed = false
        t.finishedAt = 0L
        t.speedBps = 0.0
        t.reconnecting = false
        // The checkpoint stays: when this row is claimed and the sender's
        // resume offset lands, progress continues from the partial rather
        // than restarting at 0 — claimPendingUpload stamps resumedFromBytes
        // from it below.
        t.uploadRequestId = newRequestId
        if (filePath != null) t.filePath = filePath
        if (groupId != null) t.groupId = groupId
        t.lastSampleAtMs = System.currentTimeMillis()
        t.lastSampleBytes = t.transferredBytes
        touchT(t)
        return t.id
    }

    /** Pass-14 (resumable receives, requirement 2 — persist across receiver
     *  restarts): re-creates a "Waiting for reconnection" card at cold
     *  start for a partial whose interrupted session never came back. Built
     *  directly in the FAILED+interrupted presentation state (the chip
     *  renders "Waiting for reconnection"), registered as kept-for-resume
     *  so nothing auto-dismisses it, and deliberately NOT routed through
     *  fail()/rememberTerminal — this is a presentation restoration, not a
     *  new terminal event, so it must not mint a second history entry. */
    fun createResumableWaitingRow(
        fileName: String, totalBytes: Long, peerIp: String, peerName: String, filePath: String?,
        transferredBytes: Long, source: Source = Source.NATIVE_PUSH,
    ): String {
        val id = UUID.randomUUID().toString()
        val t = Transfer(id, Direction.UPLOAD, fileName, totalBytes, peerIp, peerName,
            source = source, filePath = filePath)
        t.status = Status.FAILED
        t.interrupted = true
        t.transferredBytes = transferredBytes.coerceAtLeast(0L)
        t.lastSampleBytes = t.transferredBytes
        resumableRowIds.add(id)
        transfers[id] = t
        pruneFinishedHistory()
        touch(peerIp)
        return id
    }

    /** Pass-14 (P18, app→browser resume): re-opens THIS host's interrupted
     *  DOWNLOAD row (the browser's receive, tracked on the sender) in place
     *  for a reconnecting request, instead of minting a fresh 0% row beside
     *  the lingering "Waiting for reconnection" one. [rangeStart] is what
     *  the client's re-request actually asked for (its own partial size, 0
     *  when it restarted from scratch): the row's counter is set to it so
     *  progress reads as a true continuation — never 0% under a 60%-done
     *  browser download, and never 60% under a from-scratch restart. */
    @Synchronized
    fun reopenInterruptedDownloadForRange(
        qsItemName: String?, fileName: String, peerIp: String, rangeStart: Long,
    ): String? {
        val cand = transfers.values
            .filter { it.status == Status.FAILED && it.interrupted && it.direction == Direction.DOWNLOAD &&
                it.peerIp == peerIp &&
                (if (qsItemName != null) it.qsItemName == qsItemName else it.fileName == fileName) }
            .maxByOrNull { it.seq } ?: return null
        pendingRemovals.remove(cand.id)?.cancel()
        cand.status = Status.ACTIVE
        cand.cancelRequested = false
        cand.pauseRequested = false
        cand.pauseStartedAtMs = 0L   // Pass-18
        // ── GOAT-PASS 3 (§6): fresh leg, fresh slot accounting — the dead
        // leg's pause debt must not reach this one (see reopenForResume).
        cand.pauseYieldedSlots.set(0)
        cand.pauseReacquireArmed = false
        cand.finishedAt = 0L
        cand.speedBps = 0.0
        cand.interrupted = false
        cand.reconnecting = false
        cand.hasHole = false
        cand.reAwaitUntilMs = 0L
        cand.connectionCount.set(1)
        // ── GOAT-PASS 7 (§2B): the resumed leg's first byte re-latches the ──
        // sample baseline, so its first computed rate is pure streaming.
        armSpeedBaseline(cand)
        // ── Pass-17: the interrupted row is being re-opened in place for the
        // download manager's Range re-request — a NEWER leg owns it now, and
        // any still-blocked predecessor connection must not be able to fail
        // it out from under the resumed delivery.
        bumpResumeGen(cand)
        if (rangeStart > 0L) {
            cand.transferredBytes = rangeStart
            cand.resumedFromBytes = rangeStart
        } else {
            cand.transferredBytes = 0L
            cand.resumedFromBytes = 0L
        }
        cand.lastSampleBytes = cand.transferredBytes
        touchT(cand)
        return cand.id
    }

    /**
     * UPLOAD / DOWNLOAD are both defined from the perspective of THIS
     * device's embedded server: someone else's client is pushing bytes in
     * (UPLOAD) or pulling bytes out (DOWNLOAD) over HTTP.
     *
     * SEND (Phase 14 — native device-to-device push) is different in kind:
     * it's this device's OWN app actively pushing a file out to a peer's
     * server, with NO browser or HTTP client on this end at all — see
     * NativePushClient. There is deliberately no matching "native receive"
     * direction: when a peer pushes files TO this device, this device's
     * embedded server is on the receiving end of an ordinary POST, which is
     * indistinguishable from — and reuses — UPLOAD. So a native push shows
     * up as Direction.SEND on the sender's Transfers tab and Direction.UPLOAD
     * on the receiver's, each rendered with their existing, already-correct
     * semantics (no UI branch had to special-case "peer" vs "browser").
     */
    enum class Direction { UPLOAD, DOWNLOAD, SEND }

    /**
     * PENDING: accepted by the client but no bytes have moved yet — created the
     *          instant a Quick-Share item is accepted, before the client's own
     *          download-manager concurrency limit lets the real HTTP request fire.
     * ACTIVE:  bytes are actually flowing right now (may also be [Transfer.pauseRequested]).
     * COMPLETED / FAILED / CANCELLED / DECLINED: terminal states.
     *
     * DECLINED (v1.0): the receiving side explicitly turned this transfer
     * down before/while it ran. It is deliberately NOT FAILED — a decline is
     * a successful protocol outcome, not an error — and NOT CANCELLED —
     * nobody aborted anything mid-flight; the receiver answered "no". The
     * sender's row reads "Declined" exactly like the web upload flow's
     * "Declined by host" always has, which is what makes accept/decline
     * feel synchronous and honest from both ends.
     */
    enum class Status { PENDING, ACTIVE, COMPLETED, FAILED, CANCELLED, DECLINED }

    /**
     * Distinguishes a plain file/APK/zip download from a Quick-Share queue
     * item, or (Phase 14) a native-peer push. Each of the non-PLAIN sources
     * carries enough host/sender-side context for a meaningful Retry action:
     * QUICK_SHARE re-arms the queue item for that specific client;
     * NATIVE_PUSH re-sends just that one manifest entry via NativePushClient.
     * A plain download is a client-initiated pull the host has no mechanism
     * to re-trigger on its own, so Retry is not offered for it.
     */
    enum class Source { PLAIN, QUICK_SHARE, NATIVE_PUSH }

    class Transfer(
        val id: String,
        val direction: Direction,
        val fileName: String,
        // -1 when genuinely unknown ahead of time (zip streams). Mutable for
        // exactly ONE purpose — FINAL PASS 3 (§1): the moment an unknown-
        // length stream successfully completes, stampFinalSize() replaces
        // the -1 with the REAL final byte count (the received file's own
        // length, or the exact streamed byte count) so the Received/History
        // cards and the persisted history entry carry the actual size
        // instead of "?". It is only ever written when it still holds -1
        // (a known declared total is already exact and is never touched),
        // and only at the completion instant — never mid-stream, so live
        // progress math (which divides by -1-totals as "indeterminate") is
        // untouched.
        @Volatile var totalBytes: Long,
        val peerIp: String,
        val peerName: String,
        val source: Source = Source.PLAIN,
        val qsItemName: String? = null,
        // Absolute filesystem path on the HOST's own storage, when known —
        // powers the Completed tab's tap-to-open ("Open With" / APK install)
        // action. Left null for anything that isn't a single real file on
        // disk (a multi-file zip stream is synthesized on the fly and never
        // written to the host's storage, so there's nothing to open).
        // Mutable: for an upload, the final destination path is only known
        // once the real transfer starts (after upload-folder + collision
        // resolution), so claimPendingUpload() fills it in at claim time —
        // the PENDING entry created at accept-time can't know it up front.
        @Volatile var filePath: String? = null,
        // Correlates a PENDING upload-approval entry with the later real
        // /api/upload POST that fulfills it. Null for everything else
        // (downloads correlate via qsItemName+peerIp instead).
        // Pass-14 (resumable receives): mutable — when a NEW send of the same
        // file adopts an existing "Waiting for reconnection" row (see
        // [adoptResumableRowAsPending]), the row must answer to the NEW
        // push's "pushId::relPath" request id so the ordinary claim chain
        // (findPendingUploadId → claimPendingUpload) resolves it. Only that
        // adoption path ever rewrites it, always while the row is FAILED.
        @Volatile var uploadRequestId: String? = null,
        // GOAT (Part 2 — folder as a single transfer item): which
        // TransferGroupInfo (if any) this file belongs to. Null for a
        // standalone file transfer.
        // Pass-14 (resumable receives): mutable for exactly one path — the
        // same adoption above re-parents a resumed file into the NEW push's
        // folder group, so the resumed file renders inside the new folder
        // card rather than a stale one from the interrupted session. A file
        // still never switches groups while any leg is live; adoption only
        // ever happens while the row is FAILED.
        @Volatile var groupId: String? = null,
        // ── Pass-16 (text messages are transfers, not files) ────────────
        // A text message — app-to-app, web-to-app, or app-to-browser — is
        // recorded as a COMPLETED (or DECLINED) row whose content lives HERE
        // and never on disk. isTextMessage=true marks the row so the UI
        // renders it as a message card (snippet + Copy button) in the
        // HISTORY tab, and its null filePath means the Received tab's
        // disk-reconcile never shows it — text is a conversation artifact,
        // not a received file (the exact pass-16 feedback: "text messages
        // appear in the History tab with a Copy button, not the Received
        // tab").
        @Volatile var isTextMessage: Boolean = false,
        @Volatile var textContent: String? = null,
        // ── GOAT-PASS 15 (§3.4/§9.5 — the streamed-zip row marker) ───────────
        // True for the ONE row of an app-side ZIP-batch send (sender side)
        // and its mirror receive row (receiver side): an unknown-length,
        // live-synthesized archive streamed as a single chunked POST. Such
        // a transfer is deliberately NOT pausable — aborting its stream
        // mid-archive leaves a truncated zip the receiver must restart from
        // byte 0 (unknown-size entries are never byte-resumed by design),
        // so neither side's UI offers Pause/Resume for it, and the bulk
        // Pause/Resume actions skip it. Cancel, failure, Resend and every
        // other lifecycle behave exactly as for any other row. Both sides
        // set the flag from their own knowledge of the transfer (the sender
        // from its manifest build, the receiver from the manifest entry's
        // negative declared size), so it needs no wire protocol change.
        @Volatile var isZipStream: Boolean = false,
    ) {
        // ── Pass-11 (queue-ordering root fix): creation sequence number ────
        // Rows for a multi-file batch are minted in one tight loop on both
        // sides (sender pre-registration, receiver registerPendingTrans-
        // fersFor), so `startedAt` collides at millisecond resolution for
        // every file in the batch and the previous stable sort then fell
        // back to ConcurrentHashMap hash order — an arbitrary permutation
        // of the selection order. This monotonic sequence captures the
        // ACTUAL creation order (== manifest == selection order) and is the
        // final tiebreaker in snapshot()/activeSnapshot(), making the
        // Active tab's order identical to the Incoming Files modal's and
        // the engine's real transfer order.
        val seq: Long = seqCounter.incrementAndGet()
        // Backs transferredBytes with an AtomicLong rather than a plain
        // @Volatile Long specifically so addTransferredBytes() below can do
        // a lock-free read-modify-write. That matters once more than one
        // connection is contributing to the same transfer (see
        // claimOrStartActiveQuickShare / claimOrStartActivePlainDownload):
        // each connection reports the bytes IT just read as a delta, and
        // those deltas need to add up correctly no matter how they
        // interleave, not clobber one another the way two racing plain
        // writes of an absolute total would.
        private val _transferredBytes = AtomicLong(0)
        var transferredBytes: Long
            get() = _transferredBytes.get()
            set(value) { _transferredBytes.set(value) }
        fun addTransferredBytes(delta: Long): Long = _transferredBytes.addAndGet(delta)

        // Number of HTTP connections currently contributing bytes to this
        // one logical transfer. Always 1 for the ordinary case. Bumped when
        // claimOrStartActiveQuickShare / claimOrStartActivePlainDownload
        // recognize a new request as another connection for a download
        // that's already ACTIVE (a browser's parallel/segmented Range
        // requests for one large file) rather than starting an independent,
        // duplicate-looking entry for it. complete() only finalizes once
        // every attached connection has finished; fail() finalizes right
        // away and unwinds the rest, since a transfer missing any one of
        // its byte-spans can never be a successful delivery regardless of
        // how the others turn out.
        val connectionCount = AtomicInteger(1)

        @Volatile var status: Status = Status.PENDING
        @Volatile var speedBps: Double = 0.0
        @Volatile var cancelRequested: Boolean = false
        @Volatile var pauseRequested: Boolean = false
        @Volatile var lastSampleAtMs: Long = System.currentTimeMillis()
        @Volatile var lastSampleBytes: Long = 0
        // ── GOAT-PASS 7 (§2B — honest speed from the first byte): set by every ──
        // "this row just began (or re-began) streaming" site (claim/re-open/
        // start), consumed exactly once by [sampleSpeed]. While armed, the
        // NEXT progress update only LATCHES the sample baseline (time + byte
        // count at the first real byte) and reports no speed yet — so the
        // first computed sample measures a PURE streaming window instead of
        // dividing the first burst of bytes by the row's whole PENDING
        // lifetime (manifest registration, accept latency, queue position).
        // The pre-GOAT-PASS-7 behavior divided by that idle time, which made
        // every mid-batch item's first reading a fabricated "KB/s" figure
        // the EMA then needed seconds to climb out of — the reported
        // "starts at KB/s and ramps up over 2.5–3.5 s" defect, on the exact
        // rows whose PENDING pre-registration was oldest.
        @Volatile var speedBaseArmed: Boolean = false
        val startedAt: Long = System.currentTimeMillis()
        @Volatile var finishedAt: Long = 0L

        // ── Download-manager handoff state (jailbroken/enhanced Safari) ────────
        // A download-manager-capable browser (Safari Plus-class tweaks, any
        // DM that intercepts at the navigation-response layer) CANCELS the
        // webview's original request the moment it decides to take the file
        // over, then re-issues the SAME URL through its own HTTP stack after
        // the user confirms its save dialog. From this server's point of view
        // that is one logical delivery in two connections: the first aborts
        // at ~0 bytes, the second carries the payload. These three fields let
        // the abort of the FIRST connection revert the transfer to PENDING
        // ("re-await") instead of finalizing it FAILED, so the DM's re-request
        // claims the SAME transfer identity — same row, same queue position,
        // same Cancel semantics — instead of spawning a duplicate entry next
        // to a phantom "failed" one.
        //   handoffEligible — set once by the endpoint that served a request
        //     explicitly flagged as handoff-eligible by the client that KNOWS
        //     it's on a download-manager platform (see LumiDropServer's
        //     handoff=1 parameter). Never set for ordinary requests, so
        //     modern-browser abort semantics are bit-for-bit unchanged.
        //   reAwaitUntilMs — deadline while a re-awaited PENDING entry still
        //     believes a DM re-request is coming; the stale-pending sweep
        //     finalizes it FAILED once this passes with no re-request.
        //   reAwaitedOnce — latched after the first re-await so the endpoint
        //     can skip the pre-body settle delay for the DM's own re-request
        //     (the delay exists ONLY to let the interception cancel land
        //     before the first body byte would complete a small file).
        @Volatile var handoffEligible: Boolean = false
        @Volatile var reAwaitUntilMs: Long = 0L
        @Volatile var reAwaitedOnce: Boolean = false
        // Final Release (false-Failed root fix): latched when a connection
        // aborts mid-span — its byte coverage is missing from the delivery.
        // complete() consults this against transferredBytes vs totalBytes
        // when the LAST connection reports in, so an abort can no longer
        // fail a transfer whose siblings delivered every byte (Safari's
        // classic first-fetch-teardown double connection on attachment
        // downloads), and a real hole can no longer complete either.
        @Volatile var hasHole: Boolean = false
        // Phase 7 of 10 (§7.9 progress correctness): latched the moment a
        // SECOND handoff-flagged request arrives for this transfer — either
        // the download manager's confirmed re-request (re-await claim) or a
        // sibling attaching while the first connection is still inside its
        // pre-body settle sleep. Either way the settling connection's bytes
        // are superseded waste: the DM only ever re-requests AFTER it
        // canceled the original load, so its arrival proves the cancel
        // landed. The SettleStream polls this flag and tears that first
        // connection down immediately instead of letting it stream (and
        // double-count) beside the confirmed one. Never set for non-handoff
        // transfers, so ordinary modern-browser downloads are untouched.
        // Phase 10 (first-file glitch, root fix b): the superseded signal is
        // now PER-CONNECTION, not per-transfer. handoffSupersededBelowConn
        // stores the connection token of the newest handoff request at the
        // moment the flag latched; any connection whose OWN token is strictly
        // older is settling waste and must die — but the request that latched
        // the flag (the download manager's own confirmed re-request, the
        // newest token) always survives its own latch. The old Boolean made
        // the delivery re-request kill ITSELF on its first read — the
        // "accept the first file of the session and it stops client-side"
        // glitch, exactly.
        @Volatile var handoffSupersededBelowConn: Long = 0L
        // Phase 10 (§speed): last wall-clock ms this transfer's progress was
        // allowed to bump the SSE epoch chain (global+peer+upload versions).
        // Per-chunk touch() (per 256KB) hammered shared atomics ~2000x/s at
        // LAN speed and woke every client's rebuild check; 100ms granularity
        // still beats the 250ms SSE tick and the 400ms speed sampler.
        @Volatile var lastTouchAtMs: Long = 0L
        // Pass-5 (R5 — "last file stuck on Waiting"): wall-clock ms of the
        // moment this PENDING download's real byte-request ARRIVED at a
        // download endpoint (before/immediately after gate queueing). 0 =
        // no request has ever arrived. The never-requested sweep in
        // expireStalePending uses it to tell "queued for its turn behind
        // the gate" (honest Waiting — exempt) apart from "the browser
        // silently dropped the dispatch" (self-heals in ~25s instead of
        // sitting five minutes).
        @Volatile var requestedAtMs: Long = 0L
        // Pass-8 (§7 — pause/reconnect/resume): latched when a transfer
        // failed because its CONNECTION was lost (socket abort, network
        // drop) rather than because of a deliberate cancel, a validation
        // failure, or a host decision. An interrupted transfer is NOT a
        // permanent failure: its partial data and resume metadata are kept
        // and both UIs present it as "Interrupted" with a reconnect path,
        // for the full FAILED_DOWNLOAD_LINGER_MS window, instead of the
        // harsher "Failed" label. Purely presentational — the underlying
        // status stays FAILED so every existing guard (no auto-retry, no
        // completion race, terminal-state immutability) keeps working
        // unchanged.
        @Volatile var interrupted: Boolean = false
        // ── Pass-17 (browser-side cancel verdict) ───────────────────────────
        // True during the sub-2-second window after a NON-cancelled download
        // connection aborted, while the liveness challenge decides between a
        // deliberate browser-DM cancel and a genuine network drop. Purely
        // presentational: the row keeps its live (frozen) state so neither
        // "Waiting for reconnection" nor "Failed" flashes for what is about to
        // resolve as a clean Cancelled. Cleared by bytes flowing again, by
        // completion, by a host cancel, and by the verdict itself.
        @Volatile var verdictPending: Boolean = false
        // ── Pass-17 (one card per transfer, forever) ────────────────────────
        // Generation counter, incremented on EVERY in-place re-open of this
        // row (resume legs of all kinds — see reopenForResume and friends).
        // A superseded worker leg (the blocked read of a connection that died
        // right before a NEWER leg re-opened the same row) captures the
        // generation at its own start and simply STOPS when it changes — its
        // late failure/cleanup tail likewise no-ops — so an old leg can never
        // clobber the resumed leg sharing its row id (the reported "two
        // cards: one Waiting-for-reconnection, one continuing").
        @Volatile var resumeGen: Int = 0
        // ── GOAT-PASS 3 (§6 — the slot-reacquire contract) ──────────────────
        // How many execution-slot permits [schedulePausePermitYield] handed
        // back to the queue while this row's worker(s) were parked in
        // [awaitUnpaused]. The pause-yield itself is correct and stays: a
        // paused stream must not freeze the queue. What was MISSING is the
        // other half of the contract — when the pause lifts, the row must
        // RE-ACQUIRE those permits through the very same gates before any
        // further byte flows. Without it, every paused-then-resumed stream
        // resumed flowing PERMIT-FREE: resume five paused items and all five
        // streamed simultaneously beside the current slot holder (the
        // reported one-active-transfer violation), and the queue's own
        // accounting drifted with every such stream. This counter records the
        // debt for exactly one pause episode; the FIRST worker to wake
        // performs the re-acquire of every freed permit (see
        // [awaitSlotBackAfterPause]), sibling workers of the same row wait
        // for it to complete, and every leg transition (fail/complete/demote/
        // reopen) zeroes it so a dead leg's debt can never reach a fresh one.
        val pauseYieldedSlots = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile var pauseReacquireArmed: Boolean = false
        // Pass-10 (automatic resume overhaul): latched while the transfer's
        // connection died and the sender/receiver is now automatically
        // reconnecting (probing the peer, backing off, re-sending the
        // remainder). Purely presentational — the underlying status stays
        // ACTIVE so every existing guard keeps working; the UI renders
        // "Reconnecting..." instead of a frozen progress bar. Cleared the
        // moment bytes flow again (updateProgress/addProgress) or the
        // attempt window ends in a terminal state.
        @Volatile var reconnecting: Boolean = false
        // Pass-14 (resume wording, both systems): > 0 while this row's live
        // leg is a RESUMED continuation that picked up from an existing
        // partial (the byte offset it picked up from). Purely presentational:
        // the status chip renders "Resuming X%" instead of "Sending/"
        // "Receiving X%" so a 60%-checkpoint resume honestly reads as a
        // resume on both sides, never as a mysterious restart from a
        // non-zero number. Stamped by the reopen/claim/adoption paths below
        // whenever the checkpoint is > 0; cleared never (it naturally ends
        // with the row's own terminal resolution).
        @Volatile var resumedFromBytes: Long = 0L
        // ── Pass-18 (resume-one-of-all-paused): when THIS row's pause flag was ──
        // first raised, so the concurrency gates can tell a pause that has
        // genuinely settled (older than the yield grace) from a sub-second
        // double-tap blip before handing the paused row's permit to an
        // explicitly resumed waiter (see acquireDownloadSlot /
        // acquireReceivingSlot preemption). 0 = not paused right now.
        @Volatile var pauseStartedAtMs: Long = 0L
        // ── GOAT-PASS 6 (§3 — the pause-parked connection marker) ──────────
        // True while this DOWNLOAD row's serve leg sits parked inside
        // awaitUnpaused with its browser connection still open (the pause
        // settled past the grace window and the permit was yielded, but the
        // connection itself is deliberately untouched so the download
        // manager resumes in place byte-exactly when the pause lifts). A
        // connection that dies in that state CANNOT be noticed while parked
        // — the death only surfaces when the pause lifts and the next write
        // fails, i.e. AFTER requestResume already cleared pauseRequested,
        // which is exactly why the old abort/verdict path misread it as a
        // browser cancel or a network interruption and the reported paused
        // items never came back. Set by the pause-yield at settle time;
        // consumed by downloadConnectionAborted (silent connection drop,
        // the row keeps waiting for its resume re-dispatch) and cleared the
        // moment bytes flow again (addProgress) — the parked connection
        // resumed in place, the immunity is no longer needed.
        @Volatile var pauseParkedConn: Boolean = false
        // ── PASS 26 (receiver-acknowledged completion, the iPad 4 fix) ───────
        // The stock iOS 10.3.3 Safari on an iPad 4 has NO download manager and
        // no <a download> support: an attachment response handed to that
        // browser is silently slurped-and-discarded (small files) or aborted
        // mid-body (large ones) — a socket-write "success" from THIS host's
        // side proved NOTHING about the receiving side. The new client-side
        // receive pump therefore requests the bytes itself (a tracked XHR
        // into the page, "recv=1") and CONFIRMS finalization with an explicit
        // POST /api/client-transfer/ack once its own load genuinely finished.
        //   ackExpected — latched by the endpoint that served a request the
        //     client explicitly flagged recv=1 (the page-JS receive). While
        //     set, complete() may NOT finalize the row on the socket-write
        //     path alone: the terminal callback arms the ack window instead
        //     (below), and only ackDownloadReceive — the receiver's own
        //     finalization confirmation — completes the row. Never set for
        //     any modern-browser request, so their pipeline is bit-for-bit
        //     unchanged; never set for octet/handoff (download-manager)
        //     requests, so the enhanced-Safari interception flow is equally
        //     unchanged.
        //   ackGen — the resumeGen captured at markDownloadAckExpected time:
        //     the attempt identity the ack must match. A re-opened row (an
        //     interrupted receive's re-dispatch — reopenInterruptedDownload-
        //     ForRange bumps resumeGen) is a NEWER attempt whose predecessor's
        //     late ack (older gen) must not be able to complete it mid-flight,
        //     and whose own fresh recv=1 request re-marks with the new gen.
        //   ackDeadlineMs — 0 while the stream is still delivering; armed by
        //     the terminal success callback (the conversion inside complete())
        //     to now + DOWNLOAD_ACK_GRACE_MS. The stall sweep resolves the row
        //     FAILED-interrupted past it (an honest interruption: the bytes
        //     left the socket but the receiver never confirmed finalization),
        //     never COMPLETED — and within it the row is left alone, still
        //     genuinely "Receiving". Idempotent-by-construction: a duplicate
        //     terminal callback finds the deadline already armed and re-arms
        //     nothing.
        @Volatile var ackExpected: Boolean = false
        @Volatile var ackGen: Int = -1
        @Volatile var ackDeadlineMs: Long = 0L

        val progressPct: Int
            get() = when {
                totalBytes <= 0L -> if (status == Status.COMPLETED) 100 else 0
                else -> ((transferredBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
            }

        // True while actively transferring with no known total size — a ZIP
        // stream's archive size can't be known until generation finishes, so
        // progressPct above is stuck at 0 the entire time even though bytes
        // are flowing normally. This is purely a UI signal: the Transfers
        // list uses it to show an animated/indeterminate indicator instead
        // of a percentage bar that looks stalled when it isn't. Excludes a
        // paused transfer deliberately — nothing is actively happening while
        // paused, regardless of whether the total size is known, so an
        // animating "working" indicator would be its own kind of
        // misleading. Deliberately NOT folded into progressPct itself,
        // which stays a plain "best known percentage" (0 when unknown) —
        // this is a separate "how should the UI represent that" concern
        // layered on top.
        val isIndeterminate: Boolean
            get() = status == Status.ACTIVE && !pauseRequested && totalBytes <= 0L
    }

    private val transfers = ConcurrentHashMap<String, Transfer>()

    // ── Pass-11 (queue-ordering root fix): monotonic row-creation sequence.
    // See Transfer.seq for why this exists — every Transfer constructor call
    // takes the next number, so a batch minted in one loop gets strictly
    // increasing seq values in creation (== selection) order even when the
    // whole loop lands inside one clock millisecond.
    private val seqCounter = java.util.concurrent.atomic.AtomicLong(0L)

    // ── Phase 8 of 10 (§8.3 event-frequency audit): mutation epochs ───────────
    // The SSE ticker used to rebuild every connected client's full state
    // payload (a snapshot sort + ~10 map scans + a JSON serialization, per
    // client) on EVERY 250ms tick — even when absolutely nothing had
    // changed anywhere, which is the steady state of an open-but-idle web
    // page. With C idle clients that is C × 4 full payload builds per
    // second of pure waste on the phone in the person's pocket, plus the
    // GC churn of thousands of short-lived JSON objects on a low-end host.
    // These two counters let LumiDropServer.sseTick() skip the build
    // entirely unless something a client could actually SEE has changed:
    //   globalEpoch — any mutation anywhere in the engine (coarse signal:
    //     "does ANY client anywhere need a rebuild check this tick?").
    //   peerEpoch(ip) — mutations of transfers whose peerIp is [ip]. A
    //     client's payload only ever contains ITS OWN rows (xfers are
    //     filtered by peerIp, and the paused/priority/cancelled indication
    //     sets are queried per-peer), so a transfer progressing for client
    //     X says nothing about client Y's payload — Y must not pay for it.
    // Every mutation site in this object calls touch(); the discipline is
    // CONSERVATIVE by design: when a site can't cheaply attribute the
    // change to one peer it bumps the global counter alone, which costs at
    // most one extra rebuild, never a missed update. A missed touch() would
    // mean a stale drawer — the one failure mode worse than wasted CPU —
    // so the bump sites deliberately over-approximate.
    private val globalVersion = AtomicLong(0)
    private val peerVersions = ConcurrentHashMap<String, AtomicLong>()
    fun globalEpoch(): Long = globalVersion.get()
    fun peerEpoch(peerIp: String): Long = peerVersions[peerIp]?.get() ?: 0L
    // Phase 10 (light-speed sync): epoch of UPLOAD-direction mutations.
    // Every client's payload now carries an "inc" array (other peers'
    // incoming files, live), so an upload mutation is globally visible —
    // but it must NOT cost non-involved clients a rebuild on every download
    // mutation. This counter is the upload-only twin of globalVersion and
    // participates ONLY in the per-client dirty gate, keeping Phase 8's
    // idle-storm optimization intact for download-heavy scenarios.
    private val uploadVersion = AtomicLong(0)
    fun uploadEpoch(): Long = uploadVersion.get()

    private fun touch(peerIp: String?) {
        globalVersion.incrementAndGet()
        if (peerIp != null) {
            peerVersions.getOrPut(peerIp) { AtomicLong(0) }.incrementAndGet()
        }
    }

    // ── Pass-14 (P4 — instant previewer): optional state-nudge hook ─────────
    // The engine knows WHEN something a client can see changed; the server
    // knows how to push it instantly (LumiDropServer's sseNudge wires itself
    // here at construction). Invoked from touchT — the lifecycle-mutation
    // path (start/claim/fail/complete/cancel/pause/resume/reopen, plus the
    // 100ms-throttled progress touch) — so a host-side Cancel tap reaches
    // every open web client in milliseconds instead of waiting out the
    // SSE ticker's 250ms quantum. Deliberately a nullable lambda rather
    // than a server dependency: the engine stays framework-free and the
    // JVM tests never need a server.
    @Volatile var onStateNudge: (() -> Unit)? = null

    // ── GOAT-PASS 7 (§2A — event-driven UI sync): the app-UI twin of ─────────
    // [onStateNudge]. Every lifecycle mutation and every (100 ms-throttled)
    // progress touch fires it, so a surface that renders from the engine can
    // refresh the MOMENT the engine changed instead of waiting out its poll
    // tick — the visible half of "the next item must begin immediately": a
    // completion, the next row's PENDING→ACTIVE flip, and its first bytes all
    // reach the Transfers tab within the nudge's own coalescing window rather
    // than up to one full 500 ms tick later (and a brand-new transfer started
    // while the tab idled at its 2 s cadence refreshes immediately too).
    // Nullable and exception-proof for the same reasons as onStateNudge; the
    // wiring surface coalesces (removeCallbacks + postDelayed), so the call
    // rate is bounded by the touch throttle no matter how hot the engine runs.
    @Volatile var onUiStateNudge: (() -> Unit)? = null
    /** GOAT-PASS 10 (background continuity): the application context the
     *  WakeGuard acquires against; set once by ServerForegroundService at
     *  startup, cleared (with releaseAll) when the service dies. */
    @Volatile var appContextRef: android.content.Context? = null


    /** Transfer-carrying touch: bumps the upload epoch too when [t] is an
     *  UPLOAD-direction transfer, so the SSE layer can push "incoming from
     *  <peer>" rows to every open page the moment they appear / progress /
     *  finish. Used by every mutation site that has the Transfer in hand. */
    private fun touchT(t: Transfer) {
        touch(t.peerIp)
        if (t.direction == Direction.UPLOAD) uploadVersion.incrementAndGet()
        // ── GOAT-PASS 10 (background continuity): every in-flight transfer ──
        // re-asserts its WakeGuard membership on each state touch — a partial
        // WakeLock exists exactly while at least one PENDING/ACTIVE transfer
        // lives, so minimizing the app to the recent-apps drawer can never
        // freeze the CPU mid-stream on Doze/OEM throttling builds. Terminal
        // states are released by complete/fail/decline below (and by the
        // service's onDestroy safety valve).
        if (t.status == Status.PENDING || t.status == Status.ACTIVE) {
            appContextRef?.let { WakeGuard.acquire(it, t.id) }
        } else {
            WakeGuard.release(t.id)
        }
        try { onStateNudge?.invoke() } catch (e: Exception) { /* never let a listener break the engine */ }
        try { onUiStateNudge?.invoke() } catch (e: Exception) { /* never let a listener break the engine */ }
    }

    /** Progress-path touch, time-throttled to [TOUCH_THROTTLE_MS]: per-chunk
     *  calls (256KB granularity) collapse to ~10 bumps/s per transfer —
     *  still 2-4x faster than the SSE tick that consumes them, but without
     *  the shared-atomic contention of thousands/s. Terminal and lifecycle
     *  mutations always use touchT directly (never throttled). */
    private fun touchProgress(t: Transfer) {
        val now = System.currentTimeMillis()
        if (now - t.lastTouchAtMs < TOUCH_THROTTLE_MS) return
        t.lastTouchAtMs = now
        touchT(t)
    }
    private const val TOUCH_THROTTLE_MS = 100L

    // Transfer ids the host explicitly asked to jump the queue via a
    // per-item Resume tap on a transfer that was still Waiting (PENDING) —
    // i.e. nothing was actually blocked on a live connection yet, so
    // un-pausing it alone can't make any bytes start moving on its own; the
    // CLIENT's own download/upload queue is what decides when to fire the
    // real request. This set is what lets that specific request jump ahead
    // of plain FIFO order the next time that client's queue has a free
    // slot — see priorityDownloadNamesFor / priorityUploadRequestIdsFor
    // below. Entries are removed the moment the transfer is actually
    // claimed, cancelled, or dismissed, so this never grows unbounded.
    // Phase 2 of 10 (API-floor correctness): Collections.newSetFromMap over a plain
    // ConcurrentHashMap — the Java-5-safe idiom this codebase already uses for
    // QSItem.consumedByIps — instead of the Java 8 concurrent-set factory,
    // which is absent from Android's API 21-23 platform classes (the same
    // NoSuchMethodError hazard class as Map.compute, documented elsewhere in
    // this phase). Identical semantics for every operation used here.
    private val prioritized = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // Recompute the smoothed speed at most this often per transfer — keeps the
    // hot per-chunk update path (called many times a second for fast LAN
    // transfers) down to a single volatile field write in the common case.
    private const val SPEED_SAMPLE_INTERVAL_MS = 400L
    private const val MAX_FINISHED_HISTORY = 60

    // How long a pause loop sleeps between checks. Short enough that Resume
    // feels instant; long enough not to spin a worker thread hot while paused.
    private const val PAUSE_POLL_MS = 200L

    // ── Pass-18 (resume-one-of-all-paused) ────────────────────────────────────
    // How long a pause must have been in place before the gates will hand a
    // paused holder's permit to an explicitly resumed waiter — the download/
    // receiving twin of NativePushClient's 500ms pause-yield window. A pause
    // younger than this never loses its permit, so a double-tap or a quick
    // pause-toggle blip shuffles nothing at all; only a REAL pause yields.
    private const val PAUSE_YIELD_GRACE_MS = 750L

    // Counts transfers that finished (any terminal state) since the Transfers
    // tab's Completed section was last opened — powers its badge. A plain
    // Int behind an AtomicInteger since it's bumped from many worker threads
    // and read/reset from the UI thread.
    private val unseenReceivedCount = AtomicInteger(0)
    private val unseenSentCount = AtomicInteger(0)
    // ── SELECTION FINAL PASS 1 (#13.1 — the History tab's own unseen counter): ──
    // the History badge must count EVERY newly completed item — sent AND
    // received alike — while staying INDEPENDENT of the Received tab's own
    // badge (visiting one tab must never clear the other's). A third
    // counter, incremented at exactly the same two chokepoints the two
    // existing ones are (every successful completion, native- and browser-
    // originated alike) and cleared by its own markHistorySeen() when the
    // History tab is genuinely visited, gives each pill its own honest
    // viewed-state without any cross-clearing.
    private val unseenHistoryCount = AtomicInteger(0)

    // Monotonically increasing count of uploads that have ever completed —
    // lets MainActivity detect "something new just landed" with one cheap
    // int comparison per poll tick, instead of either doing a full disk
    // rescan on every tick (wasteful) or never refreshing automatically at
    // all (which was the "Host misses newly received files" bug).
    private val completedUploadCount = AtomicInteger(0)
    fun completedUploadCountSnapshot(): Int = completedUploadCount.get()

    // ── Folder groups (Part 2 — folder as a single transfer item) ───────────
    // A folder transfer is still, underneath, one Transfer per real file —
    // that's what actually streams bytes and is what every existing
    // per-file mechanism (pause/resume/cancel/retry/progress) already
    // operates on correctly, untouched. What changes is purely how the UI
    // is allowed to VIEW a set of files that all belong to the same
    // folder: as one row with one aggregate progress bar, instead of one
    // row per file. TransferGroupInfo carries only the folder's own fixed
    // identity (name/direction/peer/source/when it started) — never
    // derived state like progress or status, which is always computed
    // fresh from the member Transfers below (groupProgress/groupStatus),
    // so there is exactly one source of truth for "how is this folder
    // doing" and no way for a cached aggregate to drift out of sync with
    // its members.
    private val groups = ConcurrentHashMap<String, TransferGroupInfo>()

    class TransferGroupInfo(
        val id: String, val name: String, val direction: Direction,
        val peerIp: String, val peerName: String, val source: Source,
        val startedAt: Long = System.currentTimeMillis(),
    )

    fun startGroup(name: String, direction: Direction, peerIp: String, peerName: String, source: Source): String {
        val id = UUID.randomUUID().toString()
        groups[id] = TransferGroupInfo(id, name, direction, peerIp, peerName, source)
        return id
    }

    /** Like [startGroup], but for callers that need a STABLE, deterministic
     *  id rather than a fresh random one every time — the Received tab's
     *  filesystem reconciliation (TransfersFragment.buildReconciledReceived)
     *  re-derives its group list from what's actually on disk on every
     *  scan, and needs the SAME folder to map to the SAME group id across
     *  repeated scans (and across an app restart, since that reconciliation
     *  is exactly what covers a folder received in a past session) or its
     *  expand state / DiffUtil identity would never stay stable. */
    fun getOrCreateGroup(id: String, name: String, direction: Direction, peerIp: String, peerName: String, source: Source): String {
        groups.putIfAbsent(id, TransferGroupInfo(id, name, direction, peerIp, peerName, source))
        return id
    }

    fun groupInfo(groupId: String): TransferGroupInfo? = groups[groupId]

    /** Every Transfer currently registered under [groupId], regardless of
     *  status — the expanded per-file view of a folder row, AND (see
     *  foldIntoRows) the one true source for that row's own derived
     *  status/progress/done-count. Both need the same complete picture: a
     *  file the folder is still waiting on doesn't stop being part of the
     *  folder just because some snapshot elsewhere only wanted to list,
     *  say, the still-active ones. */
    fun membersOf(groupId: String): List<Transfer> = transfers.values.filter { it.groupId == groupId }

    /** ── GOAT-PASS 14 (Phase 1, §4 — the folder's ELIGIBLE manifest) ────────
     * The members a folder's derived status, progress and "N/M files" total
     * are computed over: every member EXCEPT the DECLINED ones. A child the
     * receiver excluded (the ✕ inside an expanded incoming folder, carried
     * back on the status polls as `excludedNested` and resolved Declined on
     * this side) is OUTSIDE the eligible manifest by definition — it will
     * never stream and never complete, so counting it in the denominator is
     * exactly the stuck-at-"25/30" over a folder whose receiver long since
     * finished "25/25". The declined rows themselves still auto-dismiss on
     * their own 400 ms linger (and still render inside the expanded per-file
     * view until they do — [membersOf] is unchanged); this helper only feeds
     * the DERIVED aggregates, so both sides converge on the same eligible
     * total the moment the decline lands. CANCELLED members deliberately
     * stay in (a sender-side cancel is not a receiver decision about the
     * manifest — it keeps its classic presentation and dismisses on its
     * own schedule). */
    fun eligibleMembers(members: List<Transfer>): List<Transfer> =
        if (members.none { it.status == Status.DECLINED }) members
        else members.filter { it.status != Status.DECLINED }

    fun groupProgressPct(members: List<Transfer>): Int {
        // ── GOAT-PASS 14 (§4): byte-weighted progress over the ELIGIBLE ─────
        // members only — a declined child's bytes are not part of what this
        // folder is going to deliver, and leaving them in the denominator
        // held the bar short of 100% over an otherwise-complete folder for
        // as long as the declined rows lingered.
        val eligible = eligibleMembers(members)
        if (eligible.isEmpty()) return 0
        val knownTotal = eligible.filter { it.totalBytes > 0L }
        if (knownTotal.isEmpty()) {
            // Nothing in the group has a known size yet (e.g. the fast
            // "announce" phase — see NativePushClient/apiNativeAnnounce —
            // filled this group in before real per-file sizes were ever
            // known). Fall back to a completed-file-count percentage
            // rather than showing a stuck 0%.
            return (eligible.count { it.status == Status.COMPLETED } * 100) / eligible.size
        }
        // Bytes-weighted progress across every member whose size IS known.
        // A member with an unknown size simply doesn't participate in
        // either side of this ratio — mixing its (unknowable) contribution
        // into a byte-weighted average would be meaningless, and in
        // practice this case is transient anyway: every file's size is
        // known once the full manifest arrives (see NativePushClient's
        // two-phase send), so knownTotal converges to all of [members]
        // within moments of the group being created.
        val totalBytes = knownTotal.sumOf { it.totalBytes }
        val doneBytes = knownTotal.sumOf { it.transferredBytes.coerceIn(0L, it.totalBytes) }
        return ((doneBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
    }

    fun groupIsIndeterminate(members: List<Transfer>): Boolean =
        // ── GOAT-PASS 14 (§4): a DECLINED member never contributes bytes — ──
        // its (possibly known) size must not break the "nothing measurable
        // has a size" indeterminacy rule of the members that still count.
        members.any { it.status == Status.ACTIVE } && eligibleMembers(members).all { it.totalBytes <= 0L }

    /** A folder's own status is derived, never stored: ACTIVE beats
     *  PENDING beats a terminal state, matching how a user actually reads
     *  "is this folder still doing something" — one file still going means
     *  the whole folder counts as still going, even if 299 others finished
     *  already. */
    fun groupStatus(members: List<Transfer>): Status {
        // ── GOAT-PASS 14 (Phase 1, §4): derived over the ELIGIBLE members ─────
        // ([eligibleMembers] — every child except the DECLINED ones). The
        // receiver's per-child exclusions resolve Declined here on the sender;
        // those children are outside the eligible manifest and must not hold
        // the folder open: a folder whose 25 eligible children all completed
        // while 5 declined rows still linger their 400 ms reads COMPLETED
        // (and moves on to Sent/Received) — never a false "Declined", never
        // stuck in Active at "25/30". A folder whose every member is Declined
        // (the receiver turned the whole thing down) still reads DECLINED.
        val eligible = eligibleMembers(members)
        if (eligible.isEmpty()) {
            // Every member is Declined — the receiver turned the folder down.
            return if (members.isEmpty()) Status.PENDING else Status.DECLINED
        }
        return when {
            eligible.any { it.status == Status.ACTIVE } -> Status.ACTIVE
            eligible.any { it.status == Status.PENDING } -> Status.PENDING
            eligible.all { it.status == Status.COMPLETED } -> Status.COMPLETED
            eligible.any { it.status == Status.FAILED } -> Status.FAILED
            eligible.any { it.status == Status.CANCELLED } -> Status.CANCELLED
            // v1.0 (§decline-sync): a folder the receiver turned down reads as
            // Declined, not the generic PENDING the else-branch would show once
            // every member's own DECLINED terminal state lands here.
            eligible.any { it.status == Status.DECLINED } -> Status.DECLINED
            else -> Status.PENDING   // an announced-but-still-empty group (Phase 1 of a push, before any files registered)
        }
    }

    /** One row for the UI to render: either a standalone file, or a folder
     *  collapsed to a single entry. See [activeSnapshotGrouped] /
     *  [completedSnapshotGrouped] for how a flat, already-correctly-ordered
     *  Transfer list gets folded into this without changing that order. */
    sealed class TransferRowItem {
        data class Solo(val transfer: Transfer) : TransferRowItem()
        data class Group(val info: TransferGroupInfo, val members: List<Transfer>) : TransferRowItem()
    }

    /** Folds a flat, already-ordered Transfer list into [TransferRowItem]s
     *  by collapsing every member of the same group into ONE entry, placed
     *  at the position of that group's FIRST-encountered (i.e. best-sorted,
     *  per whatever ordering [flat] already has) member. A group with one
     *  file still ACTIVE and the rest already done naturally lands where
     *  that active file would have — exactly where a folder that's still
     *  doing something belongs, with no separate group-ordering logic
     *  needed beyond reusing the per-file order that's already correct. */
    /** Folds a flat, already-ordered Transfer list into [TransferRowItem]s.
     *  A solo (ungrouped) transfer is taken as-is from [flat] -- its own
     *  status already decided whether it belongs in this particular
     *  snapshot, same as always. A GROUPED transfer's row is different:
     *  [flat] only decides whether this group gets *encountered* at all in
     *  this pass (i.e. whether any one of its members happens to be in
     *  this particular status-filtered snapshot) and, via the sort order
     *  [flat] already has, where the row lands -- never which members it
     *  reports having, or whether it truly belongs in this list. Both of
     *  those are decided from the group's real, complete membership (see
     *  [membersOf]) and its own derived [groupStatus], gated by
     *  [wantGroupStatus] so each snapshot only keeps groups matching the
     *  direction it actually cares about (still-in-progress vs fully
     *  COMPLETED) -- exactly mirroring how a solo transfer's own status
     *  gates its inclusion in [flat] in the first place.
     *
     *  GOAT (large-folder flicker fix): this used to build a group's row
     *  from flat.filter { it.groupId == gid } -- i.e. from whatever subset
     *  of that group's members happened to already be in [flat]. For
     *  activeSnapshot() (PENDING/ACTIVE/FAILED/CANCELLED only), that meant
     *  a completed file dropped OUT of the group's own members the moment
     *  it finished, so groupProgressPct/filesDone were computed from a
     *  shrinking, completed-excluded subset instead of the whole folder --
     *  a 300-file folder 250 files in could show "0 of 50 done" instead of
     *  "250 of 300", with progress swinging unpredictably depending on
     *  which files still happened to be counted. Worse, for
     *  receivedSnapshot() (COMPLETED only), any non-empty filtered subset
     *  is trivially "all COMPLETED", so a folder still very much in
     *  progress could appear -- partially, incorrectly -- in Received/Sent
     *  the moment its first file finished, growing there file-by-file
     *  while simultaneously still shown in Active. [membersOf] already
     *  existed with exactly the right "every member regardless of status"
     *  semantics (built for the expanded per-file view) but was never
     *  actually wired into row construction itself -- this is that wiring. */
    fun foldIntoRows(flat: List<Transfer>, wantGroupStatus: (Status) -> Boolean = { true }): List<TransferRowItem> {
        val seenGroups = HashSet<String>()
        val out = ArrayList<TransferRowItem>(flat.size)
        for (t in flat) {
            val gid = t.groupId
            if (gid == null) {
                out.add(TransferRowItem.Solo(t))
            } else if (seenGroups.add(gid)) {
                val info = groups[gid] ?: continue   // group was somehow never registered -- skip rather than crash
                val allMembers = membersOf(gid)
                if (wantGroupStatus(groupStatus(allMembers))) {
                    out.add(TransferRowItem.Group(info, allMembers))
                }
            }
            // else: this group was already added (or deliberately left
            // out by wantGroupStatus) the first time we hit one of its
            // members above -- either way, every later member of the same
            // group is a pure duplicate signal, not a reason to re-add or
            // re-evaluate it.
        }
        return out
    }

    fun activeSnapshotGrouped(): List<TransferRowItem> =
        foldIntoRows(activeSnapshot()) { it != Status.COMPLETED }
    fun receivedSnapshotGrouped(): List<TransferRowItem> =
        foldIntoRows(receivedSnapshot()) { it == Status.COMPLETED }
    fun sentSnapshotGrouped(): List<TransferRowItem> =
        foldIntoRows(sentSnapshot()) { it == Status.COMPLETED }

    /**
     * Mirror of [startPendingUpload] for the SENDING side. Two callers:
     *
     * 1. Phase 1 continuation, item 2 (SendFragment/NativePushClient.
     *    sendOrQueueForPeer): when Auto Send has no eligible device to
     *    send to yet, this makes the item visibly "Waiting" in the
     *    Transfers/Active tab immediately, rather than it living only in
     *    some invisible in-memory queue until a peer shows up. Peer info
     *    isn't known yet, so it's left at the "Waiting for device"
     *    placeholder default and this entry is quietly superseded via
     *    [cancelPendingSend] once a real peer appears and the real send
     *    starts under a fresh id.
     *
     * 2. Phase 1 continuation, item 1 (NativePushClient.sendToPeers):
     *    pre-registers EVERY file of a folder send as PENDING, all at
     *    once, the moment a peer is already known and the manifest is
     *    built — mirroring registerPendingTransfersFor on the receiving
     *    side exactly, real peer info and all. THIS caller passes
     *    [peerIp]/[peerName]/[filePath] explicitly since it already has
     *    them, and later promotes the SAME entry in place via
     *    [claimPendingSend] rather than superseding it — a file's
     *    Transfer object (and its correct groupId) exists from the
     *    moment the whole folder is accepted, not just from whenever its
     *    own turn comes up under the send concurrency gate. That gap was
     *    exactly what made a folder's sender-side row show the wrong
     *    (constantly growing) total file count and occasionally vanish
     *    from the Active tab entirely.
     */
    fun startPendingSend(fileName: String, totalBytes: Long, groupId: String? = null,
                          peerIp: String = "", peerName: String = "Waiting for device", filePath: String? = null,
                          // ── GOAT-PASS 15 (§3.4): marks the pre-registered row of a
                          // streamed ZIP-batch archive (see Transfer.isZipStream).
                          zipStream: Boolean = false): String {
        val id = UUID.randomUUID().toString()
        transfers[id] = Transfer(id, Direction.SEND, fileName, totalBytes,
            peerIp = peerIp, peerName = peerName, source = Source.NATIVE_PUSH, filePath = filePath, groupId = groupId,
            isZipStream = zipStream)
        pruneFinishedHistory()
        expireStalePending()
        touch(peerIp)
        return id
    }

    /** Mirror of [cancelPendingUpload] for [startPendingSend]'s
     *  sendOrQueueForPeer use — a quiet removal (never shown as
     *  Cancelled/Failed) once superseded by the real send under a fresh
     *  id. A no-op if [id] is missing or already promoted past PENDING. */
    fun cancelPendingSend(id: String) {
        val candidate = transfers[id] ?: return
        if (candidate.status != Status.PENDING) return
        prioritized.remove(id)
        transfers.remove(id)
        touch(candidate.peerIp)
    }

    /** Phase 8 (§8.4): is [id] still a live PENDING send placeholder? The
     *  NativePushClient "waiting for a device" drain needs exactly this —
     *  an entry the person already cancelled in the Transfers tab must not
     *  be resurrected into a real send just because a peer finally showed
     *  up (see NativePushClient's drain listener). */
    fun isPendingSendAlive(id: String): Boolean {
        val t = transfers[id] ?: return false
        return t.status == Status.PENDING && t.direction == Direction.SEND
    }

    /** Mirror of [claimPendingUpload] for [startPendingSend]'s
     *  sendToPeers use (see that doc comment's caller 2) — promotes the
     *  SAME pre-registered entry to ACTIVE in place the moment
     *  NativePushClient.sendOneFile actually starts streaming this file,
     *  rather than superseding it with a fresh id the way
     *  sendOrQueueForPeer's placeholder is. Returns false if [id] is
     *  missing or no longer PENDING (already cancelled out from under it,
     *  say) — the caller falls back to a fresh [start] in that case, the
     *  same defensive shape claimPendingUpload's own callers use. */
    fun claimPendingSend(id: String): Boolean {
        val t = transfers[id] ?: return false
        if (t.status != Status.PENDING) return false
        t.status = Status.ACTIVE
        // ── GOAT-PASS 7 (§2B): a SEND row pre-registered at manifest time can ──
        // have waited its whole batch's duration in PENDING — without this
        // reset, its first speed sample divided the first chunk of bytes by
        // that entire idle lifetime (the fabricated "KB/s" start on every
        // mid-batch item). The claim is the moment streaming begins: arm a
        // clean baseline so the first computed rate measures pure streaming.
        armSpeedBaseline(t)
        touch(t.peerIp)
        return true
    }

    fun start(
        direction: Direction, fileName: String, totalBytes: Long,
        peerIp: String, peerName: String,
        source: Source = Source.PLAIN, qsItemName: String? = null, filePath: String? = null,
        groupId: String? = null,
        // Pass-15 (ghost-item root fix, receiver half): rows minted by the
        // claim-chain's start() FALLBACK (a native-push receive whose
        // pre-registered row was already consumed/dismissed — exactly the
        // re-send-after-discard case) used to carry NO uploadRequestId, so a
        // later Cancel of that row recorded NO durable tombstone and every
        // one of the sender's re-POSTs sailed past the "Cancelled by host"
        // guard, resurrecting the transfer as a fresh ghost row again and
        // again. Tagging the fallback row with the composite
        // "pushId::relPath" request id makes requestCancel's existing
        // tombstone writer work for these rows exactly like pre-registered
        // ones — cancel once, refused forever after.
        uploadRequestId: String? = null,
        // ── GOAT-PASS 15 (§3.4): the streamed-zip marker for the fallback
        // row of an unknown-size native-push entry (see Transfer.isZipStream).
        zipStream: Boolean = false,
    ): String {
        val id = UUID.randomUUID().toString()
        val t = Transfer(id, direction, fileName, totalBytes, peerIp, peerName, source, qsItemName, filePath, groupId = groupId, isZipStream = zipStream)
        t.uploadRequestId = uploadRequestId
        t.status = Status.ACTIVE   // start() always means "bytes begin flowing now"
        // ── GOAT-PASS 7 (§2B): even a directly-started row benefits from the ──
        // first-byte baseline latch — connection setup and the source open
        // legitimately precede the first byte, and no honest rate can be
        // computed over a window that includes them.
        armSpeedBaseline(t)
        transfers[id] = t
        pruneFinishedHistory()
        expireStalePending()
        ensureStallWatchdogStarted()
        if (direction == Direction.UPLOAD) { touch(peerIp); uploadVersion.incrementAndGet() }
        else touch(peerIp)
        return id
    }

    /**
     * Creates a transfer in PENDING state — accepted, but no bytes moving
     * yet. Used by the Quick-Share accept endpoint so the host's Transfers
     * tab can show "Waiting" immediately when a client accepts, without
     * waiting for that client's own download-manager concurrency limit to
     * actually let the byte-request through.
     */
    fun startPending(
        direction: Direction, fileName: String, totalBytes: Long,
        peerIp: String, peerName: String,
        source: Source = Source.PLAIN, qsItemName: String? = null, filePath: String? = null,
        // FINAL PASS 4 (§2): the streamed-archive marker rides the pending
        // mint too (see claimOrStartPending's own note) — a ZIP-batch
        // quick-share row is born marked, exactly like a native leg's.
        zipStream: Boolean = false
    ): String {
        val id = UUID.randomUUID().toString()
        transfers[id] = Transfer(id, direction, fileName, totalBytes, peerIp, peerName, source, qsItemName, filePath,
            isZipStream = zipStream)
        pruneFinishedHistory()
        expireStalePending()
        if (direction == Direction.UPLOAD) { touch(peerIp); uploadVersion.incrementAndGet() }
        else touch(peerIp)
        return id
    }

    /**
     * Mirror of [startPending] for the upload-approval flow: creates a
     * PENDING upload transfer the instant an incoming-upload request is
     * accepted (auto-accept, or an explicit host tap) — well before the
     * client's own upload-concurrency queue actually lets the real POST
     * through. Correlates with the later real upload via [requestId], the
     * same id already used by /api/upload/request's approval bookkeeping.
     */
    fun startPendingUpload(
        requestId: String, fileName: String, totalBytes: Long, peerIp: String, peerName: String,
        groupId: String? = null,
        // Pass-8 (§13): receiver-side native-push pre-registrations are
        // NATIVE_PUSH-sourced so every consumer that scopes by source
        // (per-row pause/cancel propagation in TransfersFragment, the
        // receiver's Cancel-All whole-push collection, group buttons) can
        // recognize them — they used to inherit PLAIN, which silently
        // disconnected the receiving side from the push it belonged to.
        source: Source = Source.PLAIN,
        // ── GOAT-PASS 15 (§3.4): marks the receiver's mirror row of a
        // streamed ZIP-batch archive (an unknown-size manifest entry) so
        // its own UI also refuses to pause a live-synthesized archive.
        zipStream: Boolean = false,
    ): String {
        val id = UUID.randomUUID().toString()
        transfers[id] = Transfer(id, Direction.UPLOAD, fileName, totalBytes, peerIp, peerName,
            uploadRequestId = requestId, groupId = groupId, source = source, isZipStream = zipStream)
        pruneFinishedHistory()
        expireStalePending()
        touch(peerIp); uploadVersion.incrementAndGet()
        return id
    }

    /**
     * Looks for an un-claimed PENDING upload transfer matching [requestId]
     * and promotes it to ACTIVE, optionally filling in [filePath] now that
     * the real destination is known (the PENDING entry couldn't know this
     * at accept time). Returns null if no matching PENDING entry exists —
     * the caller should then fall back to [start] directly (e.g. a client
     * that hasn't been updated to send reqId yet).
     */
    @Synchronized
    fun claimPendingUpload(requestId: String, filePath: String? = null): String? {
        val candidate = transfers.values.firstOrNull {
            it.status == Status.PENDING && it.uploadRequestId == requestId
        } ?: return null
        candidate.status = Status.ACTIVE
        candidate.lastSampleAtMs = System.currentTimeMillis()
        candidate.lastSampleBytes = candidate.transferredBytes
        // ── GOAT-PASS 7 (§2B): claim = streaming starts now; the first byte's ──
        // progress latches a pure-streaming baseline (see armSpeedBaseline).
        armSpeedBaseline(candidate)
        if (filePath != null) candidate.filePath = filePath
        prioritized.remove(candidate.id)   // claimed — no longer "waiting to jump the queue"
        // Phase 7 (§7.9): symmetric count reset — the claiming upload POST
        // is one live connection (see claimPending's note).
        candidate.connectionCount.set(1)
        // Pass-14 (resume wording): a pre-registered row that carries bytes
        // from a previous interrupted leg (an adopted "Waiting for
        // reconnection" card) is being resumed, not started — stamp the
        // checkpoint so the chip reads "Resuming X%" and progress continues
        // from the partial instead of visually restarting at 0%.
        if (candidate.transferredBytes > 0L) candidate.resumedFromBytes = candidate.transferredBytes
        touch(candidate.peerIp)
        return candidate.id
    }

    /**
     * Read-only counterpart to [claimPendingUpload] — finds the same PENDING
     * entry by [requestId] without promoting it. GOAT (transfer stability
     * pass, unified receiving concurrency): this is what lets a file sit
     * visibly "Waiting" for a free receiving slot (see [acquireReceivingSlot])
     * instead of jumping straight to "Active" the moment its HTTP request
     * merely arrives — promotion via [claimPendingUpload] now only happens
     * once a slot is actually granted.
     */
    fun findPendingUploadId(requestId: String): String? =
        transfers.values.firstOrNull { it.status == Status.PENDING && it.uploadRequestId == requestId }?.id

    // ── GOAT-PASS 3 (§4 — the Windows reconnect regression, root cause) ──────
    /** The still-live UPLOAD row for [requestId] (PENDING or ACTIVE), when
     *  one exists. A browser whose connection died SILENTLY (Wi-Fi/hotspot
     *  drop with no FIN/RST) reconnects and asks /api/upload/resume for its
     *  checkpoint — but the server-side read loop of the dead leg can stay
     *  blocked on the dead socket for seconds (until its read errors, the
     *  session sweep, or the stall watchdog), and the resumable record is
     *  only REGISTERED when that loop exits. Answering the reconnect with
     *  the old bare "No resumable upload" made the client treat the answer
     *  as a refusal and RESTART THE FILE FROM BYTE 0 — the reported
     *  "interrupted item does not continue from the progress where it
     *  stopped". A row found here proves the upload is still being resolved;
     *  the endpoint answers "pending" and the client keeps backing off
     *  until the record lands (bounded by its own reconnect window). */
    fun findLiveUploadRowByRequestId(requestId: String): Transfer? =
        transfers.values.firstOrNull {
            it.direction == Direction.UPLOAD && it.uploadRequestId == requestId &&
                (it.status == Status.PENDING || it.status == Status.ACTIVE)
        }

    /** Same lookup as [findPendingUploadId], without the status filter —
     *  for the whole-push cancel handler (apiNativePushControl's
     *  scope=push), which needs to reach a file regardless of whether it's
     *  still PENDING (never started) or already ACTIVE (mid-stream) when
     *  the cancel arrives; requestCancel itself already handles either
     *  status correctly, this just needs to find the right id first. */
    fun findTransferByUploadRequestId(requestId: String): Transfer? =
        transfers.values.firstOrNull { it.uploadRequestId == requestId }

    /**
     * Removes a PENDING upload transfer whose approval request was
     * cancelled/withdrawn or expired before the real upload ever started.
     * Without this, an accepted-then-abandoned upload would leave a
     * permanently-stuck "Waiting" entry — nothing else would ever move it
     * out of PENDING, since [complete]/[fail] are only ever reached via a
     * real transfer's own stream callback, which never happens if the real
     * POST never arrives. A no-op if nothing matches (e.g. the upload had
     * already started and is now tracked as ACTIVE instead).
     */
    fun cancelPendingUpload(requestId: String) {
        val candidate = transfers.values.firstOrNull {
            it.status == Status.PENDING && it.uploadRequestId == requestId
        } ?: return
        prioritized.remove(candidate.id)
        transfers.remove(candidate.id)
        touch(candidate.peerIp)
    }

    /**
     * Pass-12 (cancel-finality root fix): cancels EVERY live transfer bound
     * to one upload request id — the waiting PENDING row AND any row that is
     * already ACTIVE and streaming. [cancelPendingUpload] above only ever
     * covered the waiting case ("a no-op if the upload had already started"),
     * which left exactly the reported hole: a sender (web client) that
     * cancelled an IN-FLIGHT upload aborted its XHR, the aborted connection
     * looked like an ordinary network death to the receiver, and the host's
     * row resolved FAILED+interrupted ("Interrupted") with its staging
     * partial deliberately KEPT for a resume that nobody wanted — a cancel
     * on one side reading as an interruption on the other, plus an orphaned
     * temp resume file.
     *
     * Routing every stage through [requestCancel] is deliberate: it records
     * the durable cancelled-upload tombstone (so any in-flight retry leg is
     * answered "Cancelled by host" instead of resurrecting a ghost), flips
     * the row to its honest CANCELLED terminal state immediately, and lets
     * the receiver's own per-chunk cancel check + apiUpload's cleanup tail
     * hard-delete the staging partial and the resumable record. Callers
     * should still call their own record cleanup (dropResumableUpload /
     * pendingUploads removal) alongside this.
     */
    @Synchronized
    fun cancelUploadRequestEverywhere(requestId: String) {
        if (requestId.isEmpty()) return
        val matches = transfers.values.filter {
            it.uploadRequestId == requestId && (it.status == Status.PENDING || it.status == Status.ACTIVE)
        }
        for (t in matches) requestCancel(t.id)
    }

    /**
     * Looks for an un-claimed PENDING transfer matching this exact
     * Quick-Share item + client IP and promotes it to ACTIVE (resetting its
     * speed/sample tracking so the transition is clean). Returns its id, or
     * null if no matching PENDING entry exists — in which case the caller
     * should fall back to [start] and create a fresh ACTIVE entry directly.
     *
     * This is intentionally a narrow match (PENDING only): if the matching
     * entry was already cancelled by the host in the meantime, this
     * correctly returns null rather than resurrecting a cancelled slot —
     * the caller's [start] fallback then serves the file as an independent,
     * freshly-tracked transfer rather than leaving anything in a stuck state.
     */
    @Synchronized
    fun claimPending(qsItemName: String, peerIp: String): String? {
        val candidate = transfers.values.firstOrNull {
            it.status == Status.PENDING && it.source == Source.QUICK_SHARE &&
                it.qsItemName == qsItemName && it.peerIp == peerIp
        } ?: return null
        candidate.status = Status.ACTIVE
        candidate.lastSampleAtMs = System.currentTimeMillis()
        candidate.lastSampleBytes = candidate.transferredBytes
        // ── GOAT-PASS 7 (§2B): claim = streaming starts now; the first byte's ──
        // progress latches a pure-streaming baseline (see armSpeedBaseline).
        armSpeedBaseline(candidate)
        prioritized.remove(candidate.id)   // claimed — no longer "waiting to jump the queue"
        // Handoff re-request claiming a re-awaited QS entry — same reasoning
        // as claimPendingPlainDownload's identical line.
        candidate.reAwaitUntilMs = 0L
        // Phase 7 (§7.9): the claiming request IS one live connection. After
        // a re-await the counter reads 0 (the aborted first connection
        // decremented it on the way out), so without this reset the DM's
        // re-request streams as an uncounted connection — completion then
        // lands at -1 (works only by accident for a lone connection) and any
        // sibling Range attach completes the transfer prematurely while
        // bytes are still in flight. set(1) is a no-op on a first claim,
        // where the constructor already counted the future connection.
        candidate.connectionCount.set(1)
        // ── PASS 26: a claim-promoted row is an ACTIVE row — the stall ──────
        // watchdog must own it exactly like a start()-created one. Before
        // this, a process whose only activity was pre-registered receives
        // (accept → claim, never a plain start()) never started the
        // watchdog: a claim-promoted row could sit ACTIVE forever — no
        // stall sweep, no interrupted resolution, and (the case that
        // exposed the gap) no receiver-ack deadline. Idempotent and cheap
        // (a latched flag + one already-scheduled executor check).
        ensureStallWatchdogStarted()
        touch(candidate.peerIp)
        return candidate.id
    }

    /**
     * Resolves the transfer id for a real Quick-Share byte request (the
     * actual /api/qs/dl GET, as opposed to the earlier /accept step that
     * creates the PENDING placeholder). Tries, in order:
     *  1. Promote a matching un-claimed PENDING entry to ACTIVE ([claimPending]).
     *  2. Attach to an already-ACTIVE entry for this exact (qsItemName,
     *     peerIp) as an additional connection.
     *  3. Otherwise start a genuinely new ACTIVE entry.
     *
     * Step 2 is what closes the "duplicate active transfer" bug: a browser
     * downloading one large file — a video is the most visible case —
     * commonly opens several parallel HTTP Range connections to fetch
     * different byte-spans of it at once for speed. Each of those lands
     * here within milliseconds of the others. Without step 2, the first one
     * claims (or creates) the transfer and every other one finds nothing
     * left to claim, so each falls through to [start] and creates its own
     * independent Transfers-tab entry for what is, from the user's point of
     * view, one download — and cancelling any single one of those entries
     * tears down its own connection, which the browser's download manager
     * then treats as the whole download failing, taking the rest down with
     * it. Doing the "does a live entry already exist" check and the
     * "attach to it / create it" step as one atomic operation — rather than
     * two separate calls from the caller, which is what [claimPending] on
     * its own used to leave room for — is what actually closes the race:
     * two near-simultaneous requests must never both observe "nothing to
     * attach to yet."
     */
    @Synchronized
    fun claimOrStartActiveQuickShare(
        qsItemName: String, peerIp: String, fileName: String, totalBytes: Long,
        peerName: String, filePath: String?, rangeStart: Long = 0L,
    ): String {
        claimPending(qsItemName, peerIp)?.let { return it }
        transfers.values.firstOrNull {
            it.status == Status.ACTIVE && it.source == Source.QUICK_SHARE &&
                it.qsItemName == qsItemName && it.peerIp == peerIp
        }?.let {
            // Phase 10 (first-file glitch, root fix c): the attach target may
            // be a zombie — an ACTIVE entry whose connection died mid-body
            // and, pre-SafeResponse, never fired its close callback (and even
            // post-fix can be momentarily wedged on a silently-dead socket).
            // Attaching blindly to it (connectionCount 1->2) means the retry's
            // own completion decrements to 1 and the transfer NEVER resolves
            // terminal. If it has zero counted bytes and its last activity
            // sample is stale past the handoff settle window, this request IS
            // the delivery: reset the counter to exactly 1 for it instead of
            // counting a dead sibling that will never report in.
            if (it.transferredBytes == 0L && System.currentTimeMillis() - it.lastSampleAtMs > 2500L) {
                it.connectionCount.set(1)
                it.reAwaitUntilMs = 0L
                // ── GOAT-PASS 7 (§2B): the healing request is this row's ──
                // delivery now — its first byte latches a clean baseline.
                armSpeedBaseline(it)
                touch(it.peerIp)
            } else if (rangeStart == 0L && it.connectionCount.get() <= 0 &&
                !it.verdictPending && !it.reconnecting && !it.pauseRequested) {
                // ── GOAT-PASS 6 (§3 — the pause-parked leg's replacement) ────
                // A from-0 request attaching to a row whose connections are
                // ALL gone is THE delivery now: the pause-parked serve leg
                // died while parked (downloadConnectionAborted's parked
                // immunity kept the row alive for exactly this re-dispatch),
                // so the fresh leg must count from a clean slate — the same
                // "one card, now honestly from 0%" discipline
                // reopenInterruptedDownloadForRange applies to interrupted
                // rows — never additively on top of the dead leg's partial
                // count. A Range re-request (rangeStart > 0) keeps the
                // existing accounting: its offset is the DM's own partial.
                it.connectionCount.set(1)
                it.transferredBytes = 0L
                it.hasHole = false
                it.reAwaitUntilMs = 0L
                // ── GOAT-PASS 7 (§2B): the replacement leg's first byte ──
                // latches a clean baseline (see armSpeedBaseline).
                armSpeedBaseline(it)
                touch(it.peerIp)
            } else {
                it.connectionCount.incrementAndGet()
            }
            return it.id
        }
        // ── Pass-14 (P18 — app→browser resume in place) ─────────────────────
        // A reconnecting download manager re-requests with "Range: bytes=N-"
        // while THIS row (interrupted at N bytes) still lingers as "Waiting
        // for reconnection". Minting a fresh row made the host read 0% under
        // a browser already at N (the reported bug); re-opening the ORIGINAL
        // row with its counter set to N keeps one card whose progress is a
        // true continuation. rangeStart == 0 (a from-scratch restart, e.g.
        // the page's own reconnect re-dispatch) re-opens the same row with a
        // reset counter — still one card, now honestly from 0%.
        reopenInterruptedDownloadForRange(qsItemName, fileName, peerIp, rangeStart)?.let { return it }
        return start(Direction.DOWNLOAD, fileName, totalBytes, peerIp, peerName,
            source = Source.QUICK_SHARE, qsItemName = qsItemName, filePath = filePath)
    }

    /** True if a PENDING or ACTIVE plain-download transfer already exists for
     *  this exact file+client — mirrors [hasLiveEntry] for the plain-download
     *  side; used by [claimOrStartPendingPlainDownload] to stay idempotent
     *  against a retried/duplicate registration call. */
    fun hasLivePlainEntry(fileName: String, peerIp: String): Boolean =
        transfers.values.any {
            (it.status == Status.PENDING || it.status == Status.ACTIVE) &&
                it.source == Source.PLAIN && it.direction == Direction.DOWNLOAD &&
                it.fileName == fileName && it.peerIp == peerIp
        }

    /**
     * Plain-download mirror of [claimOrStartPending]: atomically checks-and-
     * creates a PENDING entry for a file the client has just told the host
     * it's about to download (see /api/download/register), so the Host's
     * Transfers tab can show it as "Waiting" the moment the client queues it
     * — exactly like it already does for a Quick-Share item the instant it's
     * accepted — rather than only once that file's turn in the client's own
     * download-queue concurrency limit actually comes up. Returns null (and
     * creates nothing) if a PENDING or ACTIVE entry for this exact file+
     * client already exists, same idempotency guarantee as the Quick-Share
     * version. Also clears any stale CANCELLED/FAILED tombstone for this
     * exact file+client first, for the identical reason
     * [claimOrStartPending]'s doc gives: once a fresh registration for the
     * same file is genuinely happening, an old tombstone has done its job.
     */
    @Synchronized
    fun claimOrStartPendingPlainDownload(
        fileName: String, totalBytes: Long, peerIp: String, peerName: String, filePath: String?
    ): String? {
        if (hasLivePlainEntry(fileName, peerIp)) return null
        transfers.values.filter {
            it.source == Source.PLAIN && it.direction == Direction.DOWNLOAD &&
                it.fileName == fileName && it.peerIp == peerIp &&
                (it.status == Status.CANCELLED || it.status == Status.FAILED)
        }.forEach { transfers.remove(it.id); touch(it.peerIp) }
        // ── Pass-17: a fresh REGISTRATION is a fresh decision — see ─────
        // claimOrStartPending's identical call. /api/download/register is
        // only ever driven by a deliberate user selection on the page, never
        // by a download manager's own retry machinery.
        clearCancelledDownloadTombstone("pd", fileName, peerIp)
        return startPending(Direction.DOWNLOAD, fileName, totalBytes, peerIp, peerName, filePath = filePath)
    }

    /**
     * Plain-download mirror of [claimPending]: looks for an un-claimed
     * PENDING plain-download transfer matching this exact file+client and
     * promotes it to ACTIVE. Returns null if none exists, in which case the
     * caller ([claimOrStartActivePlainDownload]) falls through to its
     * existing attach-or-start logic exactly as before this registration
     * system existed — a direct download that skipped registration (an
     * older cached WebUI, or a one-off request built by hand) still works
     * precisely as it always has.
     */
    @Synchronized
    fun claimPendingPlainDownload(fileName: String, peerIp: String): String? {
        val candidate = transfers.values.firstOrNull {
            it.status == Status.PENDING && it.source == Source.PLAIN &&
                it.direction == Direction.DOWNLOAD && it.fileName == fileName && it.peerIp == peerIp
        } ?: return null
        candidate.status = Status.ACTIVE
        candidate.lastSampleAtMs = System.currentTimeMillis()
        candidate.lastSampleBytes = candidate.transferredBytes
        // ── GOAT-PASS 7 (§2B): claim = streaming starts now; the first byte's ──
        // progress latches a pure-streaming baseline (see armSpeedBaseline).
        armSpeedBaseline(candidate)
        prioritized.remove(candidate.id)
        // A re-awaited entry just got its download-manager re-request: the
        // handoff window is over (either the DM took the file, or this is a
        // plain retry after one — either way it is a REAL request now, not
        // an expected one) and the short sweep deadline must no longer fire.
        candidate.reAwaitUntilMs = 0L
        // Phase 7 (§7.9): the claiming request IS one live connection — see
        // claimPending's note for why the post-re-await counter must return
        // to exactly 1 here instead of streaming as an uncounted 0.
        candidate.connectionCount.set(1)
        // ── PASS 26: same ACTIVE-row guarantee as claimPending — a ──────────
        // claim-promoted row needs the stall watchdog armed (see that note).
        ensureStallWatchdogStarted()
        touch(candidate.peerIp)
        return candidate.id
    }

    /**
     * Same idea as [claimOrStartActiveQuickShare], for plain (non-Quick-Share)
     * downloads: [apiDownload], the APK installer download, and the
     * single-file fast path of the zip-stream endpoint all funnel through
     * here instead of calling [start] unconditionally. Tries, in order:
     *  1. Promote a matching un-claimed PENDING entry to ACTIVE (created by
     *     an earlier /api/download/register call — [claimPendingPlainDownload]).
     *  2. Attach to an already-ACTIVE entry for this exact file+client as an
     *     additional connection (a browser's parallel Range requests for one
     *     large file).
     *  3. Pass-14 (P18): re-open THIS client's interrupted row in place for
     *     a reconnecting request (see reopenInterruptedDownloadForRange).
     *  4. Otherwise start a genuinely new ACTIVE entry — the path a request
     *     that skipped registration always takes, unchanged from before.
     */
    @Synchronized
    fun claimOrStartActivePlainDownload(
        fileName: String, totalBytes: Long, peerIp: String, peerName: String, filePath: String?,
        rangeStart: Long = 0L,
    ): String {
        claimPendingPlainDownload(fileName, peerIp)?.let { return it }
        transfers.values.firstOrNull {
            it.status == Status.ACTIVE && it.source == Source.PLAIN &&
                it.direction == Direction.DOWNLOAD && it.fileName == fileName && it.peerIp == peerIp
        }?.let {
            // Phase 10 (first-file glitch, root fix c — plain-download twin):
            // never attach onto a zero-byte stale ACTIVE zombie; the retry
            // becomes its single authoritative connection instead.
            if (it.transferredBytes == 0L && System.currentTimeMillis() - it.lastSampleAtMs > 2500L) {
                it.connectionCount.set(1)
                // ── GOAT-PASS 7 (§2B): the healing request is this row's ──
                // delivery now — its first byte latches a clean baseline.
                armSpeedBaseline(it)
                touch(it.peerIp)
            } else if (rangeStart == 0L && it.connectionCount.get() <= 0 &&
                !it.verdictPending && !it.reconnecting && !it.pauseRequested) {
                // ── GOAT-PASS 6 (§3 — plain-download twin of the QS re-dispatch ──
                // adoption): a from-0 request attaching to a row whose legs are
                // all gone (the pause-parked connection died while parked)
                // becomes THE delivery with a clean byte count — one card,
                // honestly from 0%, never additive on the dead leg's partial.
                it.connectionCount.set(1)
                it.transferredBytes = 0L
                it.hasHole = false
                // ── GOAT-PASS 7 (§2B): the replacement leg's first byte ──
                // latches a clean baseline (see armSpeedBaseline).
                armSpeedBaseline(it)
                touch(it.peerIp)
            } else {
                it.connectionCount.incrementAndGet()
            }
            return it.id
        }
        // ── Pass-14 (P18 — app→browser resume in place, plain-download twin) ─
        reopenInterruptedDownloadForRange(null, fileName, peerIp, rangeStart)?.let { return it }
        // §2 re-open: same file, same client, COMPLETED within the window —
        // the SAME logical download re-requested (browser retry / download-
        // manager re-request / double-tap that outlived a small file). Re-open
        // the existing identity instead of minting a twin Sent-history row.
        if (filePath != null) {
            val now = System.currentTimeMillis()
            transfers.values.firstOrNull {
                it.status == Status.COMPLETED && it.source == Source.PLAIN &&
                    it.direction == Direction.DOWNLOAD && it.fileName == fileName &&
                    it.peerIp == peerIp && it.filePath == filePath &&
                    now - it.finishedAt <= SAME_LOGICAL_DOWNLOAD_REOPEN_MS
            }?.let { return reopenCompleted(it) }
        }
        return start(Direction.DOWNLOAD, fileName, totalBytes, peerIp, peerName, filePath = filePath)
    }

    /** §2 (duplicate history) shared re-open body: a recent COMPLETED
     *  transfer becomes ACTIVE again under its ORIGINAL id — one logical
     *  transfer, one history row, reconnect-safe accounting. Caller must
     *  hold the monitor. */
    private fun reopenCompleted(t: Transfer): String {
        t.status = Status.ACTIVE
        t.pauseYieldedSlots.set(0)   // GOAT-PASS 3 (§6): fresh leg, clean slot ledger
        t.pauseReacquireArmed = false
        t.transferredBytes = 0L
        t.hasHole = false
        t.speedBps = 0.0
        t.cancelRequested = false
        t.interrupted = false
        t.reAwaitUntilMs = 0L
        t.reAwaitedOnce = false
        t.handoffSupersededBelowConn = 0L
        t.connectionCount.set(1)
        // ── GOAT-PASS 7 (§2B): re-opened for a fresh leg — arm the baseline ──
        // so the first byte of the re-request starts a pure measurement.
        armSpeedBaseline(t)
        prioritized.remove(t.id)
        bumpResumeGen(t)   // Pass-17: a genuinely new leg of the same identity
        touchT(t)
        return t.id
    }

    /** True if a PENDING or ACTIVE Quick-Share transfer already exists for this
     *  exact item+client — used by the accept endpoint to stay idempotent
     *  against a retried/duplicate accept call (e.g. a flaky network causing
     *  the client to resend the same batch), so it never creates a second
     *  "Waiting" row for something already accepted. */
    fun hasLiveEntry(qsItemName: String, peerIp: String): Boolean =
        transfers.values.any {
            (it.status == Status.PENDING || it.status == Status.ACTIVE) &&
                it.source == Source.QUICK_SHARE && it.qsItemName == qsItemName && it.peerIp == peerIp
        }

    /** True if ANY client still has an un-claimed PENDING Quick-Share
     *  transfer for this exact item name, regardless of which peer. Lets
     *  the host server's short-lived item cache (inFlightQS) know it must
     *  NOT expire an item out from under a transfer that's still
     *  legitimately Waiting — a transfer can now sit paused-while-Waiting
     *  for an arbitrary, host-controlled length of time (see requestPause /
     *  requestResume above), which can easily outlast a cache TTL that was
     *  originally sized only for "just in case of an HTTP Range follow-up
     *  request a few seconds/minutes after the main download." Without
     *  this, a file paused and left Waiting long enough would have its
     *  underlying item silently expire, so the real download request that
     *  finally arrives once the host resumes it finds nothing left to
     *  serve — permanently stuck showing "Waiting" with no error and no
     *  way to recover short of the host re-sending the file from scratch. */
    fun hasPendingQuickShare(qsItemName: String): Boolean =
        transfers.values.any {
            it.status == Status.PENDING && it.source == Source.QUICK_SHARE && it.qsItemName == qsItemName
        }

    /** ── Pass-18 (app→browser resume reliability): true while ANY row still ──
     *  legitimately needs this Quick-Share item to stay resolvable — an
     *  ACTIVE row (the item's download is in flight right now, however slow)
     *  or an interrupted row inside its reconnect window (ACTIVE+latched
     *  "Auto-Resume on Reconnection" after a liveness-confirmed drop, or
     *  FAILED+interrupted awaiting the browser's own auto-reconnect
     *  re-request). LumiDropServer's in-flight item cache used to expire an
     *  item 5 minutes after its last REQUEST even while the interrupted row
     *  was still promising the user "Auto-Resume on Reconnection" — the
     *  page's re-dispatch then answered "Not in queue: name" and nothing
     *  ever resumed. The item now lives exactly as long as its resumable
     *  rows do. */
    fun hasResumableQuickShare(qsItemName: String): Boolean {
        val now = System.currentTimeMillis()
        return transfers.values.any {
            it.source == Source.QUICK_SHARE && it.qsItemName == qsItemName && (
                it.status == Status.ACTIVE ||
                    (it.status == Status.FAILED && it.interrupted &&
                        (it.finishedAt <= 0L || now - it.finishedAt < FAILED_DOWNLOAD_LINGER_MS)))
        }
    }

    /**
     * Atomically checks-and-creates: if a PENDING/ACTIVE Quick-Share transfer
     * already exists for this exact (qsItemName, peerIp), does nothing and
     * returns null — otherwise creates a fresh PENDING entry and returns its
     * id. Doing the check and the create as ONE atomic step (rather than
     * hasLiveEntry() + startPending() as two separate calls from the caller)
     * closes a race where two near-simultaneous accept requests for the same
     * item — e.g. a flaky connection causing the client to resend its own
     * accept call — could both observe "no live entry yet" and each create
     * their own PENDING row: the exact "duplicate transfer entries" bug.
     *
     * Also clears any stale CANCELLED/FAILED tombstone for this exact
     * (qsItemName, peerIp) pair before creating the fresh entry. Those
     * tombstones exist purely to stop an automatic retry from silently
     * undoing a deliberate host Cancel (see wasCancelledForQuickShare) — but
     * once the host genuinely starts a NEW send of the same file to the same
     * client, that old tombstone has done its job; leaving it in place is
     * exactly what made a resend fail until the host manually cleared
     * transfer history.
     */
    fun claimOrStartPending(
        fileName: String, totalBytes: Long, peerIp: String, peerName: String,
        qsItemName: String, filePath: String? = null,
        // FINAL PASS 4 (§2): a ZIP-batch quick-share row carries the same
        // streamed-archive marker the native leg mints — see the call site.
        zipStream: Boolean = false
    ): String? {
        val retired = ArrayList<String>()
        val r = claimOrStartPendingLocked(fileName, totalBytes, peerIp, peerName, qsItemName, filePath, retired, zipStream)
        // Outside the monitor (see the inversion note on the locked body) —
        // hand every retired zombie's download permits back.
        for (zid in retired) {
            val freed = forceReleaseDownloadSlot(zid)
            if (freed > 0) {
            }
        }
        return r
    }

    /** Pass-24: [claimOrStartPending]'s monitor-held body. [retiredOut], when
     *  supplied, collects the ids whose download permits must be handed back
     *  for the CALLER to release AFTER the monitor is dropped — the download
     *  gate takes its own lock, and the gate's grant path itself takes THIS
     *  object's monitor (bumpHolderCount) while holding that lock, so a
     *  force-release under the monitor here would be a classic lock-order
     *  inversion. */
    @Synchronized
    private fun claimOrStartPendingLocked(
        fileName: String, totalBytes: Long, peerIp: String, peerName: String,
        qsItemName: String, filePath: String?, retiredOut: MutableList<String>?,
        zipStream: Boolean = false
    ): String? {
        // ── Pass-24 (a wedged zombie must not own the identity) ─────────────────
        // A live (PENDING/ACTIVE) entry for this exact (qsItemName, peerIp)
        // used to make a FRESH send silently reuse the OLD row: hasLiveEntry
        // returned true, no new PENDING row was minted, and the new batch's
        // first GET then ATTACHED to the stale ACTIVE row — whose leaked
        // connectionCount meant the delivery could never finalize. That is
        // the reported "re-sent 340 files, only 339 appeared: the previously
        // stuck item is missing entirely". A live entry is only genuinely
        // live while it is queued (PENDING), paused by a deliberate user
        // decision, or actually moving bytes. An ACTIVE row that has made no
        // progress past the full stall window — or is latched reconnecting —
        // is a wedged zombie whose delivery is dead; a fresh registration is
        // a NEW decision about this item, so the zombie is retired (failed
        // interrupted, permits freed, row purged exactly like the CANCELLED/
        // FAILED purge below) and the fresh batch gets a fresh, independent
        // row. A paused row is never touched — a pause is the user's own
        // state and keeps its row, progress, and resume story intact.
        val now = System.currentTimeMillis()
        transfers.values.filter {
            it.source == Source.QUICK_SHARE && it.qsItemName == qsItemName && it.peerIp == peerIp &&
                it.status == Status.ACTIVE && !it.pauseRequested &&
                (it.reconnecting || now - it.lastSampleAtMs > STALL_TIMEOUT_MS)
        }.forEach { zombie ->
            zombie.interrupted = true
            zombie.status = Status.FAILED
            zombie.finishedAt = now
            pendingRemovals.remove(zombie.id)?.cancel()
            prioritized.remove(zombie.id)
            if (receivingSlotHolders.remove(zombie.id)) receivingGate.release()
            retiredOut?.add(zombie.id)
            transfers.remove(zombie.id)
            touch(zombie.peerIp)
        }
        if (hasLiveEntry(qsItemName, peerIp)) return null
        transfers.values.filter {
            it.source == Source.QUICK_SHARE && it.qsItemName == qsItemName && it.peerIp == peerIp &&
                (it.status == Status.CANCELLED || it.status == Status.FAILED)
        }.forEach { transfers.remove(it.id); touch(it.peerIp) }
        // ── Pass-17: a fresh ACCEPT is a fresh decision — the durable ──────
        // anti-retry tombstone for this (item, peer) pair has done its job
        // (the old queue item can never be re-offered: cancelled items are
        // deliberately never re-armed). Without this, re-accepting an item
        // that shares its name with a recently-cancelled transfer — the
        // normal case for a deliberate host re-send, which reuses the file's
        // name via uniqueQueueName — answered the very first download
        // request with "Cancelled by host".
        clearCancelledDownloadTombstone("qs", qsItemName, peerIp)
        return startPending(Direction.DOWNLOAD, fileName, totalBytes, peerIp, peerName,
            source = Source.QUICK_SHARE, qsItemName = qsItemName, filePath = filePath,
            zipStream = zipStream)
    }

    /** Absolute-progress update: the caller knows the exact cumulative total
     *  itself, which is only meaningful when a transfer has exactly one
     *  contributing connection — the upload byte-copy loop in
     *  LumiDropServer, which never shares a transfer across connections the
     *  way parallel-Range downloads can. Downloads should use [addProgress]
     *  instead. */
    fun updateProgress(id: String, bytesSoFar: Long) {
        val t = transfers[id] ?: return
        if (t.status != Status.ACTIVE) return
        if (t.reconnecting) t.reconnecting = false   // bytes are flowing again
        // ── Pass-17: flowing bytes end any pending cancel verdict — the
        // retry landed and is delivering, so the aborted connection was not
        // a decision anyone needs answered anymore.
        if (t.verdictPending) t.verdictPending = false
        t.transferredBytes = bytesSoFar
        sampleSpeed(t, bytesSoFar)
        touchProgress(t)
    }

    /** Delta-progress update: adds [deltaBytes] just read to the transfer's
     *  running total rather than overwriting it, so multiple connections
     *  sharing one transfer (see [claimOrStartActiveQuickShare] /
     *  [claimOrStartActivePlainDownload]) each contribute correctly instead
     *  of clobbering one another's counts. Used by every download path via
     *  [ProgressTrackingInputStream]; behaves identically to
     *  [updateProgress] for the ordinary single-connection case. */
    fun addProgress(id: String, deltaBytes: Long) {
        val t = transfers[id] ?: return
        if (t.status != Status.ACTIVE) return
        if (t.reconnecting) t.reconnecting = false   // bytes are flowing again
        if (t.verdictPending) t.verdictPending = false   // Pass-17: delivery outranks the verdict
        // GOAT-PASS 6 (§3): flowing bytes also end the pause-parked-connection
        // immunity window — the parked leg resumed in place (or a fresh leg
        // is delivering), so a LATER connection abort is an honest abort
        // again, never the pause's own fallout.
        if (t.pauseParkedConn) t.pauseParkedConn = false
        sampleSpeed(t, t.addTransferredBytes(deltaBytes))
        touchProgress(t)
    }

    // ── Pass-10 (automatic resume): reconnection presentation ────────────
    /** Latches "this transfer's connection died and an automatic reconnect
     *  is under way" — the UI swaps the progress label for "Reconnecting…"
     *  (with the last-known percentage retained on the bar). No state
     *  transition: the row stays ACTIVE so every existing lifecycle guard
     *  keeps working; cleared automatically by the next progress update or
     *  terminal resolution. */
    fun markReconnecting(id: String) {
        val t = transfers[id] ?: return
        // Pass-11: also latches on a FAILED-but-interrupted row — the sender's
        // resume loop may still own it (e.g. the row was failed out from
        // under a live retry by some other path); the UI then honestly shows
        // "Reconnecting…" instead of a premature Failed/Interrupted label.
        // Cleared the moment progress flows (updateProgress) or the row is
        // re-opened for the resumed leg (reopenForResume).
        if (t.status != Status.ACTIVE && !(t.status == Status.FAILED && t.interrupted)) return
        if (!t.reconnecting) { t.reconnecting = true; touchT(t) }
    }

    // ── FINAL PASS 2 (§1 — the streamed-ZIP "Auto-Continue on Reconnection") ─
    /** RECEIVER half: resolves a streamed-ZIP UPLOAD row whose attempt was
     *  severed by a disconnection into the kept, honest auto-continue state —
     *  the same logical transfer card stays visible while the sender's
     *  auto-resume window can still deliver a fresh attempt:
     *
     *   • FAILED + interrupted, so the re-POST's claim chain
     *     (findInterruptedUploadId → reopenInterruptedUploadByExactId)
     *     re-opens THIS row in place — never a second incoming card beside
     *     the lingering one (the reported duplicate);
     *   • NOT registered in resumableRowIds — the deleted partial can never
     *  be byte-resumed, so the row is prune-immune (a fresh start elsewhere
     *  cannot dismiss it) and no "Auto-Resume 62%" byte-checkpoint chip is
     *  implied; the UI keys its distinct "Auto-Continue on Reconnection"
     *  chip on the row's isZipStream marker instead;
     *   • progress zeroed — the next attempt restarts from 0 MB and the
     *  waiting card must not display the dead attempt's frozen bytes as if
     *  they were live progress (§1.2 "do not falsely display ongoing byte
     *  progress");
     *   • bounded life: auto-removal at AUTO_CONTINUE_ZIP_LINGER_MS, so a
     *  sender that never returns (its own window closed and its row
     *  resolved FAILED) cannot leave this card waiting forever (§1.3).
     *
     *  A genuine, unrecoverable failure (retry window exhausted, EOCD gate
     *  refusal, explicit cancellation) never passes through here — those
     *  keep the honest plain-Failed / Cancelled resolutions they already
     *  have, with Resend offered on the sender exactly as before. */
    @Synchronized
    fun failForZipAutoContinue(id: String) {
        val t = transfers[id] ?: return
        if (t.status != Status.ACTIVE && t.status != Status.PENDING) return
        val wasHostCancelled = t.cancelRequested
        if (wasHostCancelled) return   // a deliberate cancel keeps requestCancel's own CANCELLED resolution
        t.cancelRequested = false      // Pass-11: an interruption is never a cancel latch
        t.status = Status.FAILED
        t.interrupted = true
        t.verdictPending = false
        t.reconnecting = false
        t.transferredBytes = 0L
        t.resumedFromBytes = 0L
        t.speedBps = 0.0
        t.finishedAt = System.currentTimeMillis()
        rememberTerminal(t)
        resumableRowIds.remove(id)     // never a byte-resume card (unlike the ordinary kept rows)
        scheduleAutoRemoval(id, AUTO_CONTINUE_ZIP_LINGER_MS)
        touchT(t)
    }

    /** SENDER half: latches the live SEND row of a streamed ZIP whose
     *  connection died into the calm "Auto-Continue on Reconnection"
     *  presentation — markReconnecting's state (row stays ACTIVE, every
     *  lifecycle guard keeps working) with the ZIP's honest progress
     *  contract: the bytes already streamed are worthless (the next attempt
     *  restarts from 0), so the dead attempt's counter is zeroed instead of
     *  frozen on the bar as if it were live progress. The first progress
     *  tick of the fresh attempt re-owns the bar from zero. */
    fun markZipAutoContinue(id: String) {
        val t = transfers[id] ?: return
        if (t.status != Status.ACTIVE && !(t.status == Status.FAILED && t.interrupted)) return
        if (t.transferredBytes != 0L || t.resumedFromBytes != 0L) {
            t.transferredBytes = 0L
            t.resumedFromBytes = 0L
        }
        if (!t.reconnecting) { t.reconnecting = true; touchT(t) }
    }

    // ── Pass-17 (immediate disconnect states) ────────────────────────────────
    /** Latches "Reconnecting…/Waiting for reconnection/Auto-Resume on
     *  Reconnection" presentation on EVERY live row the moment this
     *  device's own LAN vanishes or changes underneath it (Wi-Fi turned
     *  off, hotspot disabled, moved to another network — detected by
     *  LumiDropServer's session sweep within ~2 s). The OS usually tears
     *  the sockets down quickly too, but a silently-dead connection can
     *  block a read/write far longer (TCP retransmit windows) — which is
     *  exactly why the expected states "sometimes failed to appear" until
     *  the 15-second stall watchdog eventually fired. Idempotent and
     *  purely presentational: bytes flowing again clear the flag on their
     *  own, and every row kind keeps its own honest chip (a SEND reads
     *  "Auto-Resume on Reconnection"; everything else reads "Waiting for
     *  reconnection"). */
    fun markAllActiveReconnecting() {
        var marked = 0
        for (t in transfers.values) {
            if (t.status != Status.ACTIVE || t.pauseRequested) continue
            if (!t.reconnecting) { t.reconnecting = true; touchT(t); marked++ }
        }
    }

    // ── Pass-18 (app→browser resume, event-driven twin) ───────────────────────
    /** A browser client's page just PROVABLY came back — its first SSE event
     *  stream since the drop attached (LumiDropServer calls this from the
     *  stream-creation path exactly on the no-stream → stream transition).
     *  Every DOWNLOAD row latched "Auto-Resume on Reconnection" for that
     *  peer is resolved INTERRUPTED right now instead of waiting for the
     *  blocked socket write's own error — TCP retransmission backoff can
     *  hold a dead write silent for a minute or more, while the browser's
     *  page is already back and its auto-reconnect engine is armed and
     *  waiting for exactly this FAILED-with-bytes snapshot to re-dispatch.
     *  This is the instant, event-driven twin of the stall watchdog's
     *  flagged-DOWNLOAD re-probe branch (its 15s cadence is the backstop
     *  for poll-only clients, where no stream attach exists to hook).
     *
     *  The freed permit matters just as much: a latched row's download
     *  slot was force-released when the page went dark, and the re-request
     *  that now follows re-acquires it through the ordinary gate. Rows
     *  that merely paused (pauseRequested) are none of this function's
     *  business — a pause is the user's own state, not an outage. */
    fun onPageReconnected(peerIp: String) {
        if (peerIp.isEmpty() || peerIp == "unknown") return
        val now = System.currentTimeMillis()
        // ── GOAT-PASS 7 (§2C — the reconnect misverdict, root fix) ─────────────
        // A page whose event stream JUST re-attached was, by definition, GONE
        // moments ago — its download connection's death in that window was a
        // NETWORK outage, not a browser-side cancel decision. The abort
        // verdict's liveness challenge runs ~150 ms after the blocked write
        // finally errors, which on a phone-hotspot host (this device's own
        // network never dropped) is exactly the moment the re-attached page
        // answers "alive" — the old code convicted the row CANCELLED on that
        // answer, tombstoned the item, and every one of the download
        // manager's own Range retries bounced off "Cancelled by host": the
        // reported reproducible Windows case where the browser abandons the
        // original download and only a fresh, from-0 re-download can ever
        // succeed. For a short window after any re-attach, this peer's
        // download-abort verdicts resolve INTERRUPTED (resumable) instead —
        // the DM's Range re-request then re-opens the SAME row in place and
        // the transfer continues from its real byte position.
        browserCancelImmunity[peerIp] = now + browserCancelReattachImmunityMs
        for (t in transfers.values.toList()) {
            if (t.direction != Direction.DOWNLOAD || t.status != Status.ACTIVE) continue
            if (!t.reconnecting || t.pauseRequested) continue
            if (t.peerIp != peerIp) continue
            if (now - t.lastSampleAtMs < STALL_TIMEOUT_MS) continue   // bytes flowing again — the row healed itself, nothing to resolve
            fail(t.id, interrupted = true)
            forceReleaseDownloadSlot(t.id)
        }
    }

    // ── GOAT-PASS 7 (§2C): reconnect-reattach immunity window — see ─────────
    // onPageReconnected. peerIp → wall-clock ms until which a with-bytes
    // download-abort verdict for this peer must resolve INTERRUPTED rather
    // than CANCELLED (the page provably just came back from an outage; its
    // download connection dying inside that window is the outage's own
    // fallout, never a deliberate browser cancel). Bounded and self-cleaning
    // (entries are overwritten per re-attach and swept lazily by the verdict
    // itself — the map never grows past one entry per peer that ever
    // reconnected).
    private val browserCancelImmunity = java.util.concurrent.ConcurrentHashMap<String, Long>()
    // @Volatile var (not a const) purely so the JVM engine tests can shrink
    // the window and exercise the semantics without sleeping (the same
    // discipline nativeSendOrphanMs already uses).
    @Volatile var browserCancelReattachImmunityMs: Long = 12_000L

    // ── GOAT-PASS 7 (§2C): the with-bytes re-request evidence window. When a ──
    // download that had already delivered bytes aborts while its page answers
    // the liveness challenge "alive", the honest answer is still AMBIGUOUS:
    // a deliberate download-manager cancel (no further request will ever
    // come) and a client-side network blip on a live page (the download
    // manager's own auto-resume re-requests with Range within seconds) look
    // identical at the abort. The old code convicted CANCELLED instantly,
    // which turned every blip into a permanent refusal. Now the row resolves
    // INTERRUPTED (resumable, honest "Auto-Resume on Reconnection") first,
    // and the CANCELLED conviction only lands if NO re-request arrived within
    // this window — a deliberate cancel is merely reported a few seconds
    // late (the DM already showed it cancelled locally), while a blip's
    // re-request re-opens the row in place and the conviction dissolves.
    // @Volatile var (not a const) purely so the JVM engine tests can shrink
    // the window and exercise the conviction race without real waiting.
    @Volatile var browserCancelEvidenceMs: Long = 5_000L

    // ── Pass-17 (one card per transfer, forever): generation stamping ────────
    /** The row's current resume generation — every in-place re-open (a
     *  resumed leg of any kind re-using the SAME row id) increments it, so
     *  a superseded worker leg that captured an older generation can detect
     *  it and stop writing/failing the row a newer leg now owns. See
     *  [Transfer.resumeGen]'s own doc. */
    fun resumeGenOf(id: String): Int = transfers[id]?.resumeGen ?: -1

    /** Bumps [id]'s resume generation — the ONE writer, called by every
     *  re-open path below (reopenForResume / reopenInterruptedDownloadForRange /
     *  reopenCompleted / adoptResumableRowAsPending). */
    private fun bumpResumeGen(t: Transfer) { t.resumeGen = t.resumeGen + 1 }

    private fun sampleSpeed(t: Transfer, bytesSoFar: Long) {
        val now = System.currentTimeMillis()
        // ── GOAT-PASS 7 (§2B): the first progress after a (re)start only ──────
        // latches the baseline — the row's true "first byte" moment. The
        // sample that follows (≥ [SPEED_SAMPLE_INTERVAL_MS] of pure streaming
        // later) is the first HONEST rate: measured over a window that
        // contains nothing but actual byte movement. Never a rate the
        // transfer has not actually achieved, and never a slow-start-shaped
        // lie born from dividing by queue idle time.
        if (t.speedBaseArmed) {
            t.speedBaseArmed = false
            t.lastSampleAtMs = now
            t.lastSampleBytes = bytesSoFar
            return
        }
        val dt = now - t.lastSampleAtMs
        if (dt >= SPEED_SAMPLE_INTERVAL_MS) {
            val db = bytesSoFar - t.lastSampleBytes
            val instBps = if (dt > 0) db * 1000.0 / dt else 0.0
            // Exponential moving average smooths bursty chunk timing into a
            // stable, readable speed instead of a jittery instantaneous value.
            // ── GOAT-PASS 7 (§2B): the smoothing is now ASYMMETRIC — a RISING
            // instantaneous rate is tracked at alpha 0.5 (a genuinely
            // accelerating transfer reaches its true speed in ~1.2 s of
            // samples instead of the old 0.3-alpha's ~2.6–3.5 s cosmetic
            // ramp), while a FALLING one is smoothed at 0.25 (bursty chunk
            // timing and disk-latency dips still read as a stable figure).
            // Reporting accuracy is preserved on both sides: the display
            // only ever shows rates the byte counter actually produced.
            if (t.speedBps <= 0.0) {
                t.speedBps = instBps
            } else {
                val alpha = if (instBps >= t.speedBps) 0.5 else 0.25
                t.speedBps = t.speedBps * (1.0 - alpha) + instBps * alpha
            }
            t.lastSampleAtMs = now
            t.lastSampleBytes = bytesSoFar
        }
    }

    /** GOAT-PASS 7 (§2B): the shared "streaming (re)started NOW" reset —
     *  zeroes the displayed speed and arms the first-sample baseline latch,
     *  so the next progress update begins a pure-streaming measurement
     *  window. Called by every claim/re-open/start site below; the row's own
     *  [lastSampleAtMs] (which the stall watchdog reads as "last real
     *  activity") is refreshed to the claim moment and then again by the
     *  latch at the first byte — it can only ever lag true activity, never
     *  lead it. */
    private fun armSpeedBaseline(t: Transfer) {
        t.speedBps = 0.0
        t.speedBaseArmed = true
        t.lastSampleAtMs = System.currentTimeMillis()
        t.lastSampleBytes = t.transferredBytes
    }

    /** Finalizes a transfer as COMPLETED — but only once every connection
     *  sharing it (see [Transfer.connectionCount]) has reported in. A
     *  browser's parallel Range connections for one large file each call
     *  this independently as they individually finish; the transfer as a
     *  whole is only genuinely done once the last of them lands, so an
     *  earlier caller here just decrements the shared counter and leaves
     *  the transfer ACTIVE for the others still in flight. */
    @Synchronized
    fun complete(id: String) {
        WakeGuard.release(id)   // GOAT-PASS 10: terminal → release the CPU guard
        val t = transfers[id] ?: return
        if (t.status != Status.ACTIVE && t.status != Status.PENDING) return
        if (t.connectionCount.decrementAndGet() > 0) return
        // Final Release: a sibling aborted mid-span and the bytes really
        // are missing -> this connection's own success must not complete
        // the transfer. Only decide here, at the last-connection moment.
        if (t.hasHole && !t.cancelRequested && t.totalBytes >= 0 && t.transferredBytes < t.totalBytes) {
            // ── Pass-24 (a hole is an interruption, not a hard failure) ─────────
            // A sibling connection aborted mid-span — the delivery is missing a
            // byte range, which is exactly the interruption shape: the client's
            // download manager re-requests with Range and reopenInterrupted-
            // DownloadForRange re-opens THIS row in place. fail(id) without the
            // interrupted flag rendered the sender's chip a red "Failed" AND
            // made the in-place resume impossible (the re-open path matches on
            // interrupted) — the reported "next item briefly enters Failed"
            // during a normal browser-side cancel (the aborted sibling is the
            // cancelled item's teardown landing on the shared row).
            fail(id, interrupted = true)
            return
        }
        // ── PASS 26 (receiver-acknowledged completion, the iPad 4 fix) ─────
        // The socket write is NOT the completion on a receiver-acked receive.
        // A recv=1 request (the page's OWN tracked receive — the only way a
        // browser with no download manager, like the iPad 4's stock iOS 10.3.3
        // Safari, can genuinely take a file) carries its own finalization
        // signal: the page CONFIRMS via POST /api/client-transfer/ack once
        // its load truly finished. Hold the row ACTIVE here — an honest live
        // receive at real progress — and arm the ack window; only
        // ackDownloadReceive may complete it now. A window that closes with
        // no ack resolves FAILED-interrupted (never COMPLETED), and the
        // existing interrupted/reconnect regime owns the recovery after.
        if (t.ackExpected && t.direction == Direction.DOWNLOAD && !t.cancelRequested) {
            t.interrupted = false
            t.reconnecting = false
            if (t.ackDeadlineMs == 0L) t.ackDeadlineMs = System.currentTimeMillis() + DOWNLOAD_ACK_GRACE_MS
            touchT(t)
            return
        }
        t.interrupted = false
        t.reconnecting = false
        finalizeCompletedBody(t)
    }

    // ── PASS 26 (receiver-acknowledged completion) ────────────────────────────
    /** Marks a DOWNLOAD transfer as receiver-ack-expected — called by the
     *  endpoint that served a request the web client explicitly flagged
     *  "recv=1" (the legacy-iOS page's own tracked XHR receive; the ONLY
     *  receive path a browser with no download manager — the iPad 4's stock
     *  iOS 10.3.3 Safari — genuinely has). From this moment the row may not
     *  complete on the socket-write path alone: [complete] arms the ack
     *  window instead, and only [ackDownloadReceive] — the receiver's own
     *  POSTed finalization confirmation — finalizes it. Also captures the
     *  CURRENT [Transfer.resumeGen] as this attempt's ack generation, so an
     *  ack minted by a superseded attempt (a re-opened/re-dispatched row
     *  bumps the gen) can never complete the newer attempt mid-flight.
     *  Modern-browser and octet/handoff (download-manager) requests never
     *  call this — their completion semantics are unchanged. Idempotent:
     *  a re-attached second recv=1 connection re-marks the same intent. */
    @Synchronized
    fun markDownloadAckExpected(id: String) {
        val t = transfers[id] ?: return
        if (t.direction != Direction.DOWNLOAD) return
        t.ackExpected = true
        t.ackGen = t.resumeGen
        t.ackDeadlineMs = 0L   // in-flight again; the terminal callback re-arms it
        touchT(t)
    }

    /** The receiver's OWN finalization confirmation (POST
     *  /api/client-transfer/ack from the page whose tracked receive just
     *  genuinely finished). Completes the row — the ONLY completion path an
     *  ack-expected row has. Attempt-safe and idempotent by construction:
     *   • the ack must reference THIS row's id (from the response's
     *     X-LDX-ACK header of the very connection that received the bytes)
     *     and the peer it was served to;
     *   • it must match the attempt generation captured at mark time — a
     *     re-opened row (an interrupted receive's re-dispatch) is a NEWER
     *     attempt a predecessor's late ack must not complete;
     *   • a duplicate ack (the row already completed via an earlier ack)
     *     is a plain no-op false — completion stays idempotent;
     *   • a stale ack for a row that already failed (the ack window
     *     expired) or was cancelled changes nothing — the auto-resume
     *     regime / the host's own decision owns that row now.
     *  Returns true only when THIS call completed the row. */
    @Synchronized
    fun ackDownloadReceive(id: String, gen: Int, peerIp: String): Boolean {
        val t = transfers[id] ?: return false
        if (t.direction != Direction.DOWNLOAD || t.peerIp != peerIp) return false
        if (t.status != Status.ACTIVE) return false          // already terminal or not live: idempotent no-op
        if (!t.ackExpected) return false                    // not an ack-confirmed receive (modern/DM path)
        if (t.ackGen != gen) return false                   // a superseded attempt's late ack
        if (t.cancelRequested) return false                 // a cancel outranks any ack
        t.ackExpected = false
        t.ackDeadlineMs = 0L
        t.interrupted = false
        t.reconnecting = false
        finalizeCompletedBody(t)
        return true
    }

    /** FINAL PASS 3 (§1 — the real final size of a completed ZIP-streamed
     *  transfer). An unknown-length, live-synthesized archive row carries
     *  totalBytes = -1 from its manifest entry, which is the honest live
     *  presentation ("35.2 MB / ?" while streaming) but leaves the
     *  completed card — and the history entry recorded at completion — with
     *  no size to render ("?"). This stamps the REAL final byte count onto
     *  the row. Call it right BEFORE complete(): finalizeCompletedBody then
     *  syncs transferredBytes to it, and the onTerminalRecord hook
     *  (HistoryStore) persists it, so the size survives navigation,
     *  refresh and restart.
     *
     *  [actualBytes] must be an EXACT figure from a source that is already
     *  on the completion path — the received file's own length() after the
     *  staging rename, or the exact byte count this side streamed/handed to
     *  the socket. Never an estimate. A no-op (by design) when the row is
     *  gone, when the total was already known (>= 0), or when the figure
     *  is not positive — in those cases the existing behaviour is kept
     *  exactly as it was. */
    @Synchronized
    fun stampFinalSize(id: String, actualBytes: Long) {
        val t = transfers[id] ?: return
        if (t.totalBytes >= 0L) return          // a declared total is already exact — never rewrite it
        if (actualBytes <= 0L) return           // nothing truthful to stamp — keep the honest "?"
        t.totalBytes = actualBytes
        t.transferredBytes = actualBytes        // the completed row shows size/size, never size/"?"
        touchT(t)
    }

    /** Shared COMPLETED finalization — called by [complete] (last
     *  connection) and by [downloadConnectionAborted]'s
     *  delivered-everything path. Callers must hold the monitor. */
    private fun finalizeCompletedBody(t: Transfer) {
        t.status = Status.COMPLETED
        // ── GOAT-PASS 3 (§6): a terminal row owes the gates nothing — any
        // stale pause-yield debt from its last parked episode dies here so a
        // later re-open (reopenCompleted) starts from a clean slate.
        t.pauseYieldedSlots.set(0)
        t.pauseReacquireArmed = false
        // ── Pass-17: completion outranks any pending verdict — every byte
        // genuinely arrived (a re-attached sibling or the retry delivered
        // the remainder), so the challenge is moot.
        t.verdictPending = false
        // ── PASS 26: a terminal row has no ack debt — a late duplicate ack
        // finds the flags already clear and idempotently no-ops.
        t.ackExpected = false
        t.ackDeadlineMs = 0L
        if (t.totalBytes >= 0) t.transferredBytes = t.totalBytes
        t.finishedAt = System.currentTimeMillis()
        t.speedBps = 0.0
        rememberTerminal(t)
        if (t.direction == Direction.UPLOAD) { unseenReceivedCount.incrementAndGet(); completedUploadCount.incrementAndGet() }
        else unseenSentCount.incrementAndGet()
        unseenHistoryCount.incrementAndGet()   // SELECTION FINAL PASS 1 (#13.1): every completed item, both directions
        // ── Pass-17 (History is a durable log, not a derived view): every
        // COMPLETED transfer is handed to the persistent history store via
        // this nullable hook (wired by LumiDropServer at construction — see
        // HistoryStore). Best-effort by design: a throwing listener must
        // never break the engine's own completion bookkeeping.
        try { onTerminalRecord?.invoke(t) } catch (e: Exception) { /* never break completion */ }
        touchT(t)
    }

    // ── Pass-17 (durable History): completion listener owned by whichever
    // layer has a Context (LumiDropServer wires HistoryStore here). Invoked
    // exactly once per terminal COMPLETED transfer (and per completed text
    // row in recordTextTransfer below) so the History tab's persistent log
    // gains its entry at the very same moment the live row completes.
    @Volatile var onTerminalRecord: ((Transfer) -> Unit)? = null

    // ── Pass-16 (text messages are History-tab transfers) ─────────────────────
    /** Records a text message as a terminal transfer row — the one shared
     *  writer for every text arrival/departure path (native push accept on
     *  the receiver, web /api/text/send on the host, the sender's own
     *  record of what it pushed, and a declined push's DECLINED verdict).
     *  The row carries the message content itself and NEVER a filePath:
     *  the History tab renders it as a message card with a Copy button,
     *  while the Received tab's disk-reconcile never sees a file to show —
     *  the exact requested split ("text messages appear in the History
     *  tab with a Copy button, not the Received tab").
     *
     *  [direction] is UPLOAD for a RECEIVED text and SEND for one this
     *  device SENT; [declined] mints the row terminal-DECLINED instead of
     *  COMPLETED (the receiver answered "no" — a decline is a decision,
     *  not a failure). Sizes are the UTF-8 byte length of the content. */
    @Synchronized
    fun recordTextTransfer(direction: Direction, text: String, peerIp: String, peerName: String,
                           source: Source, declined: Boolean = false): String {
        val t = Transfer(
            id = "txt-" + java.util.UUID.randomUUID().toString().replace("-", ""),
            direction = direction,
            fileName = "Text message",
            totalBytes = text.toByteArray(Charsets.UTF_8).size.toLong(),
            peerIp = peerIp,
            peerName = peerName,
            source = source,
        )
        t.isTextMessage = true
        t.textContent = text
        if (declined) {
            t.status = Status.DECLINED
        } else {
            t.status = Status.COMPLETED
            // ── Pass-17 (durable History): a completed TEXT exchange is part
            // of the permanent history log exactly like any completed file.
            try { onTerminalRecord?.invoke(t) } catch (e: Exception) { /* visibility-only */ }
        }
        t.transferredBytes = t.totalBytes
        t.finishedAt = System.currentTimeMillis()
        transfers[t.id] = t
        if (!declined) {
            // ── PASS 20 (§2 — text-transfer badge classification): an ──
            // accepted TEXT item is a content record, not a newly received
            // filesystem item. It must NOT increment the Received tab's
            // new-items badge (that badge represents actual newly received
            // files); it increments ONLY the History badge, exactly once,
            // per the existing History badge rules. Real files keep the
            // complete() path below and are untouched by this change.
            unseenHistoryCount.incrementAndGet()   // SELECTION FINAL PASS 1 (#13.1)
        }
        touchT(t)
        return t.id
    }

    /** Finalizes a transfer as FAILED/CANCELLED immediately — unlike
     *  [complete], this does not wait for sibling connections: a transfer
     *  missing any one of its byte-spans can never be a successful delivery
     *  no matter how the others turn out. Setting [Transfer.cancelRequested]
     *  here (after capturing whether the host had already requested it, so
     *  a genuine network failure is never miscategorized as a host Cancel)
     *  also unwinds any sibling connection: each one's own read loop
     *  already checks [isCancelled] every chunk, so it self-terminates on
     *  its very next read instead of continuing to transfer bytes toward a
     *  transfer that's already doomed. */
    @Synchronized
    fun fail(id: String, interrupted: Boolean = false, keepForResume: Boolean = false) {
        WakeGuard.release(id)   // GOAT-PASS 10: terminal → release the CPU guard
        val t = transfers[id] ?: return
        if (t.status != Status.ACTIVE && t.status != Status.PENDING) return
        val wasHostCancelled = t.cancelRequested
        // ── PASS 26 (receiver-acknowledged completion): a failed row's ack ──
        // debt dies with the attempt — a late ack for the failed attempt must
        // not be able to complete it (a re-dispatch re-marks with the NEW
        // generation through markDownloadAckExpected on its own request).
        t.ackExpected = false
        t.ackDeadlineMs = 0L
        // ── Pass-11 (auto-reconnect root fix) ────────────────────────────────
        // A genuine INTERRUPTION must no longer latch cancelRequested. The
        // latch is what isCancelled() reads, and isCancelled() drives every
        // auto-resume decision: the sender's resume loop exits as "Cancelled",
        // the receiver's receive-slot gate refuses the resumed leg with
        // "Cancelled by host", and the stream loops break mid-body. With the
        // latch set, a watchdog-failed (interrupted) row was indistinguishable
        // from a deliberately cancelled one, so reconnecting devices never
        // resumed — the row just read Cancelled and nothing happened.
        // Sibling unwind is not needed on the interrupted path either: the
        // connection that triggered this failure IS dead (that is what
        // "interrupted" means here); any still-blocked sibling read will
        // error out on its own dead socket. A genuine (non-interrupted)
        // failure KEEPS the latch — the original unwind semantics for a
        // transfer whose bytes are provably unusable stand unchanged.
        if (interrupted && !wasHostCancelled) {
            t.cancelRequested = false
        } else {
            t.cancelRequested = true
        }
        t.status = if (wasHostCancelled) Status.CANCELLED else Status.FAILED
        // ── GOAT-PASS 3 (§6): a terminal row owes the gates nothing — the
        // parked leg that accrued the pause-yield debt is over (its
        // connection is gone or tearing down), and any later resume takes
        // the ordinary fresh-leg path (reopenForResume zeroes this too).
        t.pauseYieldedSlots.set(0)
        t.pauseReacquireArmed = false
        // Pass-8 (§7): only a genuine, non-cancel connection loss may present
        // as "Interrupted" — a deliberate stop stays a plain Cancelled and a
        // validation failure stays a plain Failed.
        t.interrupted = interrupted && !wasHostCancelled
        // ── Pass-17: a row that fails is no longer awaiting a verdict — the
        // stall-watchdog fallback inside the verdict window lands here, and
        // its honest interrupted presentation IS the verdict's not-visible
        // outcome (resolveBrowserCancelVerdict's tail calls this very path).
        t.verdictPending = false
        t.reconnecting = false   // terminal — no reconnect attempt is running anymore
        t.finishedAt = System.currentTimeMillis()
        t.speedBps = 0.0
        rememberTerminal(t)
        // Pass-14 (resumable receives): a FAILED-because-interrupted receive
        // whose partial data is deliberately kept for resume is NOT scheduled
        // for auto-removal — its "Waiting for reconnection" card stays in the
        // Active tab for exactly as long as its partial exists (persisted
        // across app restarts by the caller's registry), instead of sweeping
        // out with the 10-minute reconnect window. Everything else keeps its
        // existing linger discipline untouched.
        if (keepForResume && t.interrupted && t.direction == Direction.UPLOAD) {
            pendingRemovals.remove(id)?.cancel()
            resumableRowIds.add(id)
        } else {
            resumableRowIds.remove(id)
            scheduleAutoRemoval(id, when {
            wasHostCancelled -> CANCELLED_LINGER_MS
            // v1.0 (§hotspot-off): an interrupted transfer — failed, not
            // cancelled — now stays visible for the full reconnect window
            // on BOTH directions, not just receives. An upload severed by
            // the hotspot dying used to vanish from the host's Transfers tab
            // after ONE second while the web client still held a resumable
            // row for it, so "both sides stayed visible with a reconnect
            // path" was impossible by construction. SEND keeps the short
            // blink: native pushes own their own retry loop on the sender,
            // so the row there is informational, not a reconnect handle.
            t.direction == Direction.DOWNLOAD || t.direction == Direction.UPLOAD -> FAILED_DOWNLOAD_LINGER_MS
            else -> FAILED_LINGER_MS
            })
        }
        touchT(t)
        if (wasHostCancelled) {
        } else if (t.interrupted) {
        } else {
        }
    }

    /** v1.0 (§decline-sync): finalizes a transfer as DECLINED — the receiver
     *  explicitly answered "no". Used by NativePushClient when the receiving
     *  device declines (whole session or per top-level item): resolves the
     *  SENDER's pre-registered PENDING/ACTIVE rows IN PLACE (by their own
     *  transfer ids) so the sender's Active tab shows "Declined" on the same
     * rows that were "waiting", instead of the old behavior that minted a
     *  brand-new FAILED row and left the original PENDING rows stuck
     *  "Waiting" forever — the exact "receiver declined, sender shows Failed
     *  AND the file still sits as waiting" report. Terminal linger matches
     *  CANCELLED's short flash: a decline is a decided outcome with nothing
     *  to resume, so it sweeps itself out of Active promptly and lands in
     *  history via rememberTerminal. */
    @Synchronized
    fun decline(id: String) {
        WakeGuard.release(id)   // GOAT-PASS 10: terminal → release the CPU guard
        val t = transfers[id] ?: return
        if (t.status != Status.ACTIVE && t.status != Status.PENDING) return
        t.status = Status.DECLINED
        t.finishedAt = System.currentTimeMillis()
        t.speedBps = 0.0
        rememberTerminal(t)
        scheduleAutoRemoval(id, CANCELLED_LINGER_MS)
        touchT(t)
    }

    /** v1.0 (§decline-sync): resolve every pre-registered PENDING row of a
     *  native-push session that will never stream — the rejected /
     *  timed-out / errored manifest paths. The rows the SENDER already sees
     *  as "Waiting" become their own honest terminal state instead of being
     *  orphaned forever while a synthetic summary row is minted on top.
     * Returns true when at least one row was resolved in place (the caller
     * then skips minting the synthetic entry — the visible signal already
     * exists). */
    fun resolvePendingSends(ids: Collection<String>, terminal: (String) -> Unit): Boolean {
        var resolvedAny = false
        for (id in ids) {
            val t = transfers[id] ?: continue
            if (t.status == Status.PENDING || t.status == Status.ACTIVE) {
                terminal(id)
                resolvedAny = true
            }
        }
        return resolvedAny
    }

    // ── Download-manager handoff (enhanced-Safari receive path) ───────────────
    // Research-verified behavior of a Safari-Plus-class download manager
    // (opa334 Safari Plus 1.7.22, iOS 8–14.8.1, source-audited): it hooks the
    // webview's navigation-RESPONSE policy, CANCELS the original load, and
    // re-issues the SAME URL through its own NSURLSession after the user
    // confirms its save dialog. The abort of that first request is therefore
    // not a failure — it is the interception handshake itself. These three
    // entry points give such requests abort semantics that match reality
    // while leaving every ordinary (modern-browser) request's abort behavior
    // byte-for-byte identical to what it always was.

    /** How long a re-awaited PENDING download waits for the download
     *  manager's own re-request before the sweep honestly finalizes it
     *  FAILED. Generous relative to a native save-dialog confirmation
     *  (typically 1–3s), short enough that a browser with NO download
     *  manager (stock iOS 10 Safari) surfaces its fallback path promptly. */
    private const val HANDOFF_GRACE_MS = 10_000L

    /** Marks a transfer as handoff-eligible — called by the download
     *  endpoints when the request itself carried the client's explicit
     *  handoff flag (only ever sent by a legacy routing layer that has
     *  already decided a download manager may take this file over). Safe to
     *  call repeatedly and from any connection; the flag is sticky for the
     *  transfer's lifetime because the platform property it represents
     *  cannot change mid-transfer. */
    fun markHandoffEligible(id: String) {
        transfers[id]?.handoffEligible = true
    }

    /** True when this transfer has already been re-awaited at least once —
     *  the download endpoints use this to skip the pre-body settle delay for
     *  the download manager's own re-request (the delay only ever existed to
     *  let the interception cancel land before the FIRST body byte could
     *  complete a small file; the re-request is already the confirmed
     *  delivery and must start streaming immediately). */
    fun wasReAwaited(id: String): Boolean = transfers[id]?.reAwaitedOnce ?: false

    // ── Pass-17 (browser-side download cancel, the VERDICT regime) ──────
    // A download connection that dies without a host cancel is either (a) the
    // user cancelling from the BROWSER'S OWN download UI — the page is still
    // very much alive — or (b) a genuine network death — page included. The
    // pass-15 probe answered "is the peer's SSE stream still attached?",
    // which CANNOT tell those apart on a network drop: a socket whose client
    // vanished keeps accepting writes into its kernel send buffer, so the
    // SSE stream reads "live" until the 30-second back-pressure stall-drop
    // notices. Every interrupted download on a Wi-Fi/hotspot drop was thus
    // misread as a DELIBERATE cancel (CANCELLED + a 2-minute anti-retry
    // tombstone), which is exactly the reported "even when the connection is
    // restored, the file doesn't resume at all / the expected states never
    // appear" — the resume machinery was being shot at the abort.
    //
    // The new contract: on such an abort the row does NOT fail immediately.
    // It flips [Transfer.verdictPending] and stays visibly live (frozen
    // progress — never a "Waiting for reconnection" flash for what turns out
    // to be a cancel), and a short-delay verdict runs the ACTIVE liveness
    // prober below: the server CHALLENGES the page (a nonce pushed over its
    // SSE stream and echoed by /api/qstate) and waits ~1.6 s for the page to
    // POST /api/alive. A page that answers is provably alive → deliberate
    // browser cancel → CANCELLED + tombstone ("cancels properly and
    // immediately, without ever entering WFR"). A page that cannot answer —
    // because its whole network is down — leaves the honest interruption
    // intact → the automatic reconnect/resume machinery works exactly as
    // designed. Framework-free by design (nullable lambda) so the JVM engine
    // tests never need a server.
    @Volatile var browserLivenessProber: ((peerIp: String) -> Boolean)? = null
    // ── Pass-18 (sender-side disconnect wording) ─────────────────────────────
    // "The sender's row kept reading 'Sending…' after the receiver's Wi-Fi
    // went off" — the receiving device gets its instant "Waiting for
    // reconnection" from its own LAN-transition latch (markAllActive-
    // Reconnecting), but the SENDING device's network is often untouched (a
    // hotspot still on, its client simply gone), so nothing latches there
    // and the blocked TCP write can sit silent for minutes before the OS
    // notices. This hook lets the stall watchdog ask the NATIVE push
    // client "is this SEND's peer provably unreachable RIGHT NOW?" (one
    // tiny /api/ping at the peer's own server) and flip the row's
    // presentation to "Auto-Resume on Reconnection" the moment the answer
    // is no — a live-but-slow peer answers and the row honestly keeps
    // "Sending…", so a slow link can never false-positive. Nullable +
    // default-alive so headless engine tests are untouched.
    @Volatile var sendLivenessProber: ((tid: String) -> Boolean)? = null
    // ── GOAT-PASS 8 (§11): the RECEIVER-side twin — “is the SENDER of this ──
    // native UPLOAD row provably unreachable right now?” One tiny GET
    // /api/ping at the sender's own server, resolved through the push
    // record's control link (the sender's ip:port arrived with its
    // manifest). Consulted by sweepStalledActive's UPLOAD branch at the
    // UPLOAD_STALL_PROBE_MS bound; null/default-alive keeps the headless
    // JVM engine tests untouched.
    @Volatile var uploadLivenessProber: ((tid: String) -> Boolean)? = null
    // ── Pass-22 (#6 — orphaned native SEND rows, sender-side backstop) ────────
    // True when [id] is a native-send row the dispatch machinery still owns
    // (a live session, a parked paused send, or an in-flight retry). Wired by
    // NativePushClient's init; nullable so the headless engine tests never
    // depend on it. expireStalePending uses it to give an honestly-FAILED
    // resolution to PENDING SEND rows whose owning machinery is provably
    // gone — the exact "receiver accepted but nothing ever streamed, sender
    // sits on Waiting forever" wedge (any rare interleaving that kills a
    // session coroutine now self-reports instead of hanging).
    @Volatile var nativeSendRowOwner: ((id: String) -> Boolean)? = null
    // @Volatile var (not a const) purely so the JVM engine tests can shrink
    // the window and exercise the sweep's semantics without sleeping.
    @Volatile var nativeSendOrphanMs: Long = 15 * 60_000L   // > decision deadline + retry windows
    // The verdict runs this long after the abort — just enough for the
    // abort's own socket dust to settle; the prober itself then spends up to
    // ~1.6 s waiting for the page's /api/alive answer, so the whole verdict
    // resolves well inside 2 s of the abort either way. If the prober itself
    // never returns, the 15-second stall watchdog is the row's safety net.
    private const val BROWSER_CANCEL_VERDICT_DELAY_MS = 150L

    /** Pass-17: schedules the ONE verdict for a just-aborted download whose
     *  row now carries verdictPending — see the regime's doc above. Runs on
     *  the existing auto-removal timer; a clean no-op whenever the row moved
     *  on (bytes flowed again, completed, cancelled, dismissed). */
    private fun scheduleBrowserCancelVerdict(id: String) {
        val task = object : java.util.TimerTask() {
            override fun run() {
                try { resolveBrowserCancelVerdict(id) } catch (e: Exception) {
                    /* one bad verdict must never kill the shared timer */ }
            }
        }
        try { autoRemovalTimer.schedule(task, BROWSER_CANCEL_VERDICT_DELAY_MS) } catch (e: Exception) {
            /* timer died with the process — the stall watchdog still bounds the row */ }
    }

    /** ── GOAT-PASS 7 (§2C): the DEFERRED cancel conviction for a with-bytes ────
     *  abort on a live page (see [BROWSER_CANCEL_EVIDENCE_MS]). By the time
     *  this fires, one of two things is true: the download manager's own
     *  re-request re-opened the row (ACTIVE again, or already re-resolved) —
     *  the conviction dissolves and the in-place resume carries on — or the
     *  row still sits FAILED+interrupted with nothing having touched it, in
     *  which case the re-request is provably not coming and the deliberate
     *  browser cancel's honest CANCELLED verdict lands here, a few seconds
     *  later than it used to and with byte-for-byte the same end state
     *  (tombstone included). */
    private fun scheduleBrowserCancelConviction(id: String) {
        val task = object : java.util.TimerTask() {
            override fun run() {
                try {
                    val t = transfers[id] ?: return
                    if (t.status != Status.FAILED || !t.interrupted) return   // resumed, re-opened, or already decided
                    if (t.pauseRequested) return                               // paused since — the pause owns the row now
                    convertInterruptedToCancelled(t)
                } catch (e: Exception) {
                    /* one bad conviction must never kill the shared timer */ }
            }
        }
        try { autoRemovalTimer.schedule(task, browserCancelEvidenceMs) } catch (e: Exception) {
            /* timer died with the process — the row honestly rests interrupted */ }
    }

    /** Pass-17: converts a verdict-affirmed browser-cancel into a deliberate
     *  CANCELLED — the honest resolution for "the user cancelled it from the
     *  browser's download UI" — recording the same durable tombstone a
     *  host-side Cancel does, so the cancelled item can never be silently
     *  resurrected by a late retry/reconnect. Handles BOTH the pass-17
     *  ACTIVE+verdictPending entry state and (defensively) the older
     *  FAILED+interrupted shape, so a verdict racing any legacy path still
     *  resolves the same way. */
    @Synchronized
    private fun convertInterruptedToCancelled(t: Transfer) {
        if (!(t.status == Status.ACTIVE && t.verdictPending) &&
            !(t.status == Status.FAILED && t.interrupted)) return
        pendingRemovals.remove(t.id)?.cancel()
        resumableRowIds.remove(t.id)
        t.verdictPending = false
        t.interrupted = false
        t.cancelRequested = true
        t.status = Status.CANCELLED
        t.finishedAt = System.currentTimeMillis()
        t.speedBps = 0.0
        rememberTerminal(t)
        if (t.direction == Direction.DOWNLOAD) {
            when {
                t.source == Source.QUICK_SHARE && !t.qsItemName.isNullOrEmpty() ->
                    rememberCancelledDownload("qs", t.qsItemName!!, t.peerIp)
                t.source == Source.PLAIN ->
                    rememberCancelledDownload("pd", t.fileName, t.peerIp)
                else -> Unit
            }
        }
        scheduleAutoRemoval(t.id, CANCELLED_LINGER_MS)
        touchT(t)
    }

    /** Pass-17: the verdict body — one ACTIVE liveness challenge decides
     *  deliberate browser cancel vs. genuine network death. See the regime's
     *  doc at browserLivenessProber for why the passive pass-15 check could
     *  never tell them apart and what each answer resolves to. */
    private fun resolveBrowserCancelVerdict(id: String) {
        val t = transfers[id] ?: return
        if (!t.verdictPending) return   // bytes flowed again / already decided / dismissed
        // ── Pass-18 (app→browser resume after reconnection, root fix) ─────────
        // If this row was ALREADY latched reconnecting, the engine has
        // already concluded its connection died in a NETWORK-LEVEL outage —
        // either the stall watchdog's liveness probe failed (the page was
        // provably gone while the write sat blocked) or this device's own
        // LAN vanished underneath it (markAllActiveReconnecting). A genuine
        // browser-side cancel NEVER sets that flag: a deliberate DM cancel
        // errors the connection at once (the row never stalls long enough
        // to be probed) on a page that stays perfectly alive. Skipping the
        // challenge here closes the drop-then-RETURN misverdict: without
        // this, a receiver whose network came back a few seconds before the
        // blocked write finally errored answered the challenge "alive" and
        // had its interrupted download resolved CANCELLED + tombstoned —
        // exactly the reported "after reconnection the transfer doesn't
        // resume at all" (every re-request was refused 'Cancelled by host').
        if (t.reconnecting) {
            t.verdictPending = false
            fail(id, interrupted = true)
            return
        }
        // ── GOAT-PASS 6 (§3 — the paused row is verdict-immune) ─────────────
        // A pause that landed (or lingers) while the verdict window was open
        // makes the abort a pause-side effect, not a browser decision and not
        // an outage: leave the row exactly as the pause left it (live,
        // paused, checkpoint intact) and let the resume re-dispatch carry it.
        // This is the belt-and-braces twin of downloadConnectionAborted's
        // pre-verdict parked check — a race where the pause lands between
        // the abort's entry and this resolution must resolve the same way.
        if (t.pauseParkedConn || t.pauseRequested) {
            t.verdictPending = false
            t.pauseParkedConn = false
            return
        }
        // ── GOAT-PASS 7 (§2C — the reconnect-reattach immunity, verdict half) ──
        // The page's event stream re-attached moments ago: whatever killed
        // this connection happened while the page was provably GONE, which a
        // deliberate browser cancel never is (cancelling keeps the page
        // alive throughout). Resolve the honest interruption — the download
        // manager's own Range re-request re-opens this exact row in place.
        if (System.currentTimeMillis() < (browserCancelImmunity[t.peerIp] ?: 0L)) {
            t.verdictPending = false
            fail(id, interrupted = true)
            return
        }
        val visible = try { browserLivenessProber?.invoke(t.peerIp) } catch (e: Exception) { false }
        if (visible == true) {
            // ── GOAT-PASS 7 (§2C — the with-bytes evidence window) ─────────────
            // Bytes had already been delivered when the connection aborted, so
            // "page alive" cannot tell a deliberate download-manager cancel
            // from a client-side network blip on an otherwise live page (the
            // reproducible Windows case). Resolve the row INTERRUPTED now —
            // the honest, resumable state whose re-request re-opens THIS row
            // in place — and defer the CANCELLED conviction by
            // [BROWSER_CANCEL_EVIDENCE_MS]: a blip's DM auto-resume lands
            // inside the window and the conviction dissolves; a deliberate
            // cancel produces no re-request and the conviction lands a few
            // seconds late with identical end state to the old behavior.
            // A ZERO-byte abort keeps the immediate conviction — nothing was
            // delivered, so a live page plus a dead connection is exactly
            // the shape of a first-connection cancel it always was.
            if (t.transferredBytes > 0L) {
                t.verdictPending = false
                fail(id, interrupted = true)
                scheduleBrowserCancelConviction(id)
                return
            }
            convertInterruptedToCancelled(t)
            return
        }
        // No provable liveness — a genuine network drop. The honest
        // interruption presentation and the whole automatic-resume story
        // (browser reconnect, Range re-request, in-place row re-open) take
        // over from here, exactly as before this regime existed.
        t.verdictPending = false
        fail(id, interrupted = true)
    }

    /** The abort path for download connections. For an ordinary transfer
     *  this is exactly [fail] — unchanged semantics, including the
     *  "a transfer missing any byte-span can never complete" guarantee.
     *  For a handoff-eligible transfer it is connection-aware instead:
     *   - If sibling connections are still streaming (a parallel-Range
     *     fetch, or the download manager's re-request racing the tail of the
     *     webview's aborted one), this connection simply drops out and the
     *     transfer keeps flowing through them.
     *   - If this was the last connection, the transfer reverts to PENDING
     *     ("re-await") with a short deadline instead of failing: the
     *     download manager's re-request for the SAME URL then claims the
     *     SAME identity and carries the real bytes, so the person sees one
     *     row, one queue position, one Cancel action for one logical
     *     delivery. If no re-request arrives before the deadline (a stock
     *     browser with no download manager at all), the sweep finalizes it
     *     FAILED — an honest outcome, just a few seconds later.
     *  A host-requested cancel always wins over the soft path: the host's
     *  intent outranks any browser's internal plumbing. */
    @Synchronized
    fun downloadConnectionAborted(id: String) {
        val t = transfers[id] ?: return
        if (t.status != Status.ACTIVE && t.status != Status.PENDING) return
        // ── Pass-17 (the verdict regime, entry point) ──────────────────────
        // A NON-cancelled download whose last connection aborted is genuinely
        // ambiguous AT the abort (browser-DM cancel vs. network death — see
        // the regime's doc at browserLivenessProber). Instead of flashing a
        // premature "Waiting for reconnection" (the reported "shows WFR and
        // then immediately disappears"), the row FREEZES in its live state
        // for the sub-2-second verdict window and only then resolves — to a
        // clean CANCELLED when the page answers the liveness challenge, or to
        // the honest interrupted/WFR when it cannot. Bytes flowing again at
        // any point (the browser's retry landing, a sibling connection)
        // simply clear the flag and the row continues as if nothing happened.
        if (t.status == Status.ACTIVE && !t.cancelRequested &&
            t.direction == Direction.DOWNLOAD && !t.handoffEligible) {
            // Connection accounting FIRST, exactly like the plain-fail path
            // below: release THIS connection's claim and record its missing
            // span. A sibling still streaming means the transfer is alive —
            // no ambiguity to resolve, nothing to do here.
            t.hasHole = true
            if (t.connectionCount.decrementAndGet() > 0) return
            if (t.totalBytes >= 0 && t.transferredBytes >= t.totalBytes) {
                // ── PASS 26 (receiver-acknowledged completion) ──────────────
                // On an ack-expected receive (the page's own recv=1 XHR), a
                // delivered-everything abort STILL cannot complete the row —
                // but every byte genuinely left the socket, so the receiver's
                // own finalization ack is the only verdict left. Arm the ack
                // window exactly like complete() does: the ack completes the
                // row, and the deadline sweep resolves an ack that never
                // lands as an honest interruption.
                if (t.ackExpected) {
                    t.interrupted = false
                    t.reconnecting = false
                    if (t.ackDeadlineMs == 0L) t.ackDeadlineMs = System.currentTimeMillis() + DOWNLOAD_ACK_GRACE_MS
                    touchT(t)
                    return
                }
                // Everything genuinely arrived before the abort — the same
                // delivered-everything finalization the plain path takes.
                finalizeCompletedBody(t)
                return
            }
            // ── GOAT-PASS 6 (§3 — the pause-parked connection immunity) ──────
            // A connection that died while its row was deliberately
            // PAUSE-PARKED is the pause's own fallout, never a
            // cancel/interruption verdict. The parked serve leg cannot
            // notice the browser's teardown while blocked in awaitUnpaused —
            // the death surfaces only when the pause lifts and the next
            // write fails, i.e. AFTER requestResume already cleared
            // pauseRequested, which is exactly why the old path misread it
            // (page alive → CANCELLED; probe raced → interrupted / an
            // "Auto-Resume on Reconnection" that nothing ever re-dispatched
            // — the reported paused items that "became failed" and never
            // came back). Drop the connection silently: the row KEEPS its
            // live state and its byte checkpoint, and the browser's pump
            // re-dispatches it once the pause lifts (index.html's _qsPumpTick
            // pause-lift watcher — the fresh request attaches to this exact
            // row through the ordinary claim chain).
            if (t.pauseParkedConn || t.pauseRequested) {
                t.pauseParkedConn = false
                return
            }
            if (browserLivenessProber == null) {
                // Headless/test environments wire no server-side prober —
                // there is no liveness challenge to run, so the ambiguity is
                // moot: resolve the honest interruption immediately (the
                // pre-pass-17 semantics, which is exactly the verdict's
                // not-visible outcome without the wait).
                fail(id, interrupted = true)
                return
            }
            t.verdictPending = true
            touchT(t)
            scheduleBrowserCancelVerdict(id)
            return
        }
        if (t.handoffEligible && !t.cancelRequested) {
            if (t.connectionCount.decrementAndGet() > 0) return   // siblings still delivering
            // Last connection gone — re-await the download manager's own
            // re-request rather than declaring the delivery failed.
            t.status = Status.PENDING
            t.transferredBytes = 0
            t.hasHole = false          // fresh re-await = fresh byte accounting
            t.interrupted = false
            t.reconnecting = false
            t.speedBps = 0.0
            t.lastSampleAtMs = System.currentTimeMillis()
            t.lastSampleBytes = 0
            t.reAwaitUntilMs = t.lastSampleAtMs + HANDOFF_GRACE_MS
            t.reAwaitedOnce = true
            // ── PASS 26: a re-awaited row follows the download-manager ──
            // semantics from here (its re-request completes on socket write,
            // like every octet/handoff request) — recv=1 and handoff=1 are
            // mutually exclusive by construction; this clears the defensive
            // case where both could ever be set on one row.
            t.ackExpected = false
            t.ackDeadlineMs = 0L
            prioritized.remove(id)
            touchT(t)
        } else {
            // Final Release (false-Failed root fix, bug "completed downloads
            // marked Failed"): the OLD code failed the whole transfer the
            // instant ANY connection aborted — even while siblings were
            // still streaming (parallel Range sets, Safari's first-fetch
            // double connection). The surviving connection then delivered
            // 100% of the file under a FAILED row, and the follow-up
            // re-request attached to a fresh id — a false "Failed" plus a
            // duplicate row. Correct discipline: release THIS connection's
            // claim, mark that its span is missing, and let the last
            // connection standing decide — COMPLETED when every byte of
            // the file arrived (abort-after-full-delivery teardown noise
            // included), FAILED only when the hole is real.
            t.hasHole = true
            if (t.connectionCount.decrementAndGet() > 0) return   // siblings still delivering
            // ── PASS 26: belt-and-braces twin of the regime path above — an ──
            // ack-expected row never completes on socket-write evidence alone.
            if (!t.cancelRequested && !t.ackExpected && t.totalBytes >= 0 && t.transferredBytes >= t.totalBytes) {
                finalizeCompletedBody(t)
            } else {
                // Non-cancel, non-handoff aborts of a DOWNLOAD now enter the
                // Pass-17 verdict regime at the TOP of this function — a
                // download reaching THIS line was either explicitly cancelled
                // (fail() renders the honest CANCELLED via wasHostCancelled)
                // or is an UPLOAD/SEND-direction abort with no page to probe.
                fail(id, interrupted = true)   // Pass-8 §7: a dropped connection is an interruption, not a hard failure
            }
        }
    }

    // ── Phase 7 of 10: handoff heartbeat + terminal memory (§7.2/§7.8) ────
    // Two blind spots made the jailbroken-Safari receive path fragile:
    //
    //  (a) Terminal blindness. CANCELLED rows are dismissed from the live
    //      map after 400ms and FAILED rows after 1s, while the web client's
    //      state channel only ticks about once a second — so a cancelled or
    //      failed download usually never SHOWS its terminal state to the
    //      page at all; the row simply vanishes. The legacy pump then sat
    //      there watching for a state it could never see, timed out, and
    //      "fell back" by delivering the same file a second time through
    //      the viewer/new-tab — the exact "cancellation is unreliable /
    //      some files download twice, some never" symptom set. This memory
    //      keeps every download's terminal outcome answerable by transfer
    //      id (or name+peer) for a short window after dismissal, so the
    //      pump's beat gets the REAL verdict instead of guessing from a
    //      vanished row.
    //
    //  (b) The 10-second re-await grace raced Safari Plus's own save sheet
    //      (its instantDownloads preference defaults OFF — verified in the
    //      tweak's source — so every interception pops a confirmation that
    //      a human answers in their own time). A sheet held open longer
    //      than the grace finalized the transfer FAILED out from under a
    //      delivery that was still perfectly on track; the late confirm
    //      then re-requested into a dead identity and spawned a duplicate
    //      row. The beat endpoint below lets the page — which is still
    //      alive, since the interception cancels only the subframe —
    //      shepherd the window: every beat extends the deadline while the
    //      pump is genuinely still waiting, and stops the moment it isn't.
    private const val TERMINAL_MEMORY_TTL_MS = 60_000L
    private const val TERMINAL_MEMORY_CAP = 64
    private const val HANDOFF_BEAT_EXTEND_MS = 6_000L

    private class TerminalRec(val fileName: String, val peerIp: String,
                               val status: Status, val finishedAt: Long,
                               // Pass-21: the interrupted flag and final byte count of
                               // the row at its terminal moment — the QS re-arm
                               // sweep's new feed (zeroByteFailedDownloadPeersFor)
                               // must be able to tell a never-started delivery
                               // (re-armable — nothing was ever at stake) from an
                               // interrupted-with-bytes one (the download manager
                               // owns its in-place Range resume; re-offering it
                               // would race a SECOND, from-zero download against
                               // the real one).
                               val interrupted: Boolean, val bytes: Long)

    private val terminalMemory = java.util.concurrent.ConcurrentHashMap<String, TerminalRec>()

    /** Records a download's terminal outcome for [TERMINAL_MEMORY_TTL_MS] so
     *  the handoff-sync beat can answer "what actually happened to <tid>"
     *  even after auto-removal has dismissed the row from the live map.
     *  Uploads/SENDs are excluded — only the receive path is beat-driven. */
    private fun rememberTerminal(t: Transfer) {
        if (t.direction != Direction.DOWNLOAD) return
        terminalMemory[t.id] = TerminalRec(t.fileName, t.peerIp, t.status, System.currentTimeMillis(),
            t.interrupted, t.transferredBytes)
        // Bounded by construction: TTL-sweep first, then oldest-first trim.
        if (terminalMemory.size > TERMINAL_MEMORY_CAP) {
            val cutoff = System.currentTimeMillis() - TERMINAL_MEMORY_TTL_MS
            val iter = terminalMemory.entries.iterator()
            while (iter.hasNext()) if (iter.next().value.finishedAt < cutoff) iter.remove()
            while (terminalMemory.size > TERMINAL_MEMORY_CAP) {
                var oldestKey: String? = null; var oldestAt = Long.MAX_VALUE
                for ((k, v) in terminalMemory) if (v.finishedAt < oldestAt) { oldestAt = v.finishedAt; oldestKey = k }
                if (oldestKey == null) break
                terminalMemory.remove(oldestKey)
            }
        }
    }

    /** Terminal status of a recently-finished download by transfer id, or
     *  null once the memory entry has aged out. */
    fun terminalStateFor(id: String): Status? = terminalMemory[id]?.status

    /** §2 Reconnect: how long the engine keeps a FAILED download's record —
     *  the window in which /api/transfer/reconnect can still resolve it and
     *  both UIs keep the row visible with its reconnect affordance. Exposed
     *  because LumiDropServer's snapshot include-window mirrors it. */
    fun failedDownloadLingerMs(): Long = FAILED_DOWNLOAD_LINGER_MS

    /** Recently-terminal downloads for one peer — (id, fileName, status)
     *  triples within the TTL — for the beat payload's rows list. */
    fun recentTerminalDownloadsFor(peerIp: String): List<Triple<String, String, Status>> {
        val cutoff = System.currentTimeMillis() - TERMINAL_MEMORY_TTL_MS
        return terminalMemory.entries
            .filter { it.value.finishedAt >= cutoff && it.value.peerIp == peerIp }
            .map { Triple(it.key, it.value.fileName, it.value.status) }
    }

    /** Extends the re-await window of a handoff transfer while the page's
     *  pump is still actively waiting for the download manager's re-request
     *  (each beat pushes the deadline a few seconds out). Returns true when
     *  an extension actually happened — false for anything that isn't a
     *  re-awaiting PENDING download, which is also the beat's signal that
     *  this transfer is queued/active/terminal rather than shepherded. */
    @Synchronized
    fun extendHandoffWindow(id: String): Boolean {
        val t = transfers[id] ?: return false
        if (t.status != Status.PENDING || t.reAwaitUntilMs <= 0L) return false
        t.reAwaitUntilMs = System.currentTimeMillis() + HANDOFF_BEAT_EXTEND_MS
        touch(t.peerIp)
        return true
    }

    /** Latches the per-connection superseded marker — see
     *  Transfer.handoffSupersededBelowConn's doc. [newestConnToken] is the
     *  connection token of the request ARRIVING right now (the download
     *  manager's confirmed re-request, or a sibling attaching while the
     *  first connection still settles): every connection with a strictly
     *  older token is settling waste and dies on its next read; the request
     *  carrying [newestConnToken] itself always survives. */
    fun markHandoffSuperseded(id: String, newestConnToken: Long) {
        transfers[id]?.let { if (newestConnToken > it.handoffSupersededBelowConn) it.handoffSupersededBelowConn = newestConnToken }
    }

    fun isHandoffSuperseded(id: String): Boolean = (transfers[id]?.handoffSupersededBelowConn ?: 0L) > 0L

    /** Phase 10 (first-file glitch, root fix d — feed for LumiDropServer's
     *  sweepFailedQuickShare): peer IPs with a terminal FAILED DOWNLOAD
     *  whose fileName matches — the QS re-arm sweep un-consumes exactly
     *  those (name, ip) pairs so a failed first-file delivery becomes
     *  retryable from the chip instead of silently dead. Sourced from
     *  terminalMemory (60s window) so it keeps answering after the
     *  auto-removal has already dismissed the row from the live map.
     *
     *  Pass-3 (R14 — cancelled items stay cancelled): CANCELLED deliveries
     *  are deliberately EXCLUDED now. A cancel is a decision someone made
     *  (receiver Cancel button, host Cancel) — re-arming it silently put
     *  the item back into the receiver's queue and, with Auto-Accept on,
     *  could re-fire the download automatically, which is precisely the
     *  "cancelled items returning to the queue / being sent again" bug.
     *  Only a genuine network failure (FAILED) re-arms — an interruption
     *  deserves a fresh Accept; a decision does not. */
    fun failedDownloadPeersFor(fileName: String): List<String> =
        terminalMemory.values.filter {
            it.fileName == fileName && it.status == Status.FAILED
        }.map { it.peerIp }

    /** ── Pass-21 (reconnection regression, the re-arm half) ────────────────
     *  The QS re-arm sweep's new feed: only peers whose delivery failed
     *  WITHOUT ever moving a byte and WITHOUT an interrupted (resumable)
     *  record. A zero-byte failure is a dropped/swallowed dispatch —
     *  re-offering the chip is a plain restart with nothing to lose, the
     *  exact case the re-arm exists for. A FAILED+interrupted row, by
     *  contrast, is a genuine mid-transfer interruption: its one true
     *  recovery is the download manager's own "Range: bytes=N-" retry,
     *  which the host answers with an in-place 206 re-open from the real
     *  byte offset. Re-arming THAT item put its chip back into the
     *  auto-accept queue, and auto-accept then re-dispatched it from byte
     *  zero while the DM was already resuming it — the reported "multiple
     *  items unexpectedly start transferring simultaneously" + "the same
     *  file starts over" storm after every reconnection. Those rows now
     *  rest in their honest resumable state (the QS item cache keeps them
     *  servable for the DM's retry via hasResumableQuickShare) and the
     *  chip NEVER reappears for them. */
    fun zeroByteFailedDownloadPeersFor(fileName: String): List<String> =
        terminalMemory.values.filter {
            it.fileName == fileName && it.status == Status.FAILED &&
                !it.interrupted && it.bytes <= 0L
        }.map { it.peerIp }

    /** Pass-3 (R14): resolves this client's still-waiting Quick-Share entry
     *  as DECLINED — used when the receiver turns the item down from the
     *  queue chip (apiQSCancel) or Decline-All, so the sender's Active-tab
     *  row honestly reads "Declined" instead of sitting "Waiting" until
     *  the stale-pending sweep eventually fails it. Only touches PENDING
     *  rows: an already-streaming delivery is not something a chip tap can
     *  reach. Returns true when a row was resolved. */
    /** ── FINAL PASS 4 (§9): stamp a just-minted Quick-Share row as a TEXT ──
     *  message row (content rides the row; the Active/History tabs render
     *  it as a message card). Called by addTextToQuickShare the instant the
     *  PENDING row is minted, so the row is a message from birth and every
     *  isTextMessage-keyed presentation and completion path applies. */
    fun markQuickShareRowAsText(id: String, text: String) {
        val t = transfers[id] ?: return
        t.isTextMessage = true
        t.textContent = text
        touchT(t)
    }

    fun declinePendingQuickShare(qsItemName: String, peerIp: String): Boolean {
        val t = transfers.values.firstOrNull {
            it.status == Status.PENDING && it.source == Source.QUICK_SHARE &&
                it.direction == Direction.DOWNLOAD && it.qsItemName == qsItemName && it.peerIp == peerIp
        } ?: return false
        decline(t.id)
        return true
    }

    /** ── FINAL PASS 4 (§9 — text Quick Share joins the normal Active-tab ──
     *  lifecycle). The one shared COMPLETION writer for a pre-registered
     *  text row: a browser's View/Copy/Accept-All consume of the message
     *  resolves the row that addTextToQuickShare minted as PENDING, the
     *  same instant the queue item's own consumedByIps bookkeeping lands.
     *  The row was born with isTextMessage=true and its textContent, so
     *  finalizeCompletedBody's History hook (onTerminalRecord) writes the
     *  durable message card exactly once — a completed text row is part of
     *  the permanent history log exactly like any completed file (Pass-17),
     *  and a text row minted here must never also pass through
     *  recordTextTransfer (that would double-write History). Idempotent by
     *  construction: a row already terminal (a racing decline/cancel, or a
     *  second consume from the same accept) is a no-op, so a broadcast's
     *  mint-on-first-accept and a targeted send's pre-registered row are
     *  both safe under any consume ordering. */
    fun completeQuickShareText(qsItemName: String, peerIp: String): Boolean {
        val t = transfers.values.firstOrNull {
            (it.status == Status.PENDING || it.status == Status.ACTIVE) &&
                it.source == Source.QUICK_SHARE && it.isTextMessage &&
                it.qsItemName == qsItemName && it.peerIp == peerIp
        } ?: return false
        t.transferredBytes = t.totalBytes
        finalizeCompletedBody(t)
        touchT(t)
        return true
    }

    /** ── FINAL PASS 4 (§9 — the broadcast half of the text lifecycle): a ──
     *  broadcast text (no targets pinned at queue time) has no pre-
     *  registered row — exactly like a broadcast FILE, its row is minted at
     *  the moment a client actually accepts, and it completes in the same
     *  step (the accept IS the delivery: nothing is ever downloaded). The
     *  claim half reuses claimOrStartPending's atomic identity (a re-offer
     *  of the same item to the same client re-uses the existing live row
     *  instead of minting a second one), then the row is completed in
     *  place — one message card in History per (item, client) delivery,
     *  never two. */
    fun claimAndCompleteQuickShareText(qsItemName: String, fileName: String, totalBytes: Long,
                                       peerIp: String, peerName: String, text: String): Boolean {
        val tid = claimOrStartPending(fileName, totalBytes, peerIp, peerName, qsItemName = qsItemName)
            ?: return false
        val t = transfers[tid] ?: return false
        t.isTextMessage = true
        t.textContent = text
        t.transferredBytes = totalBytes
        if (t.status == Status.PENDING || t.status == Status.ACTIVE) {
            finalizeCompletedBody(t)
            touchT(t)
        }
        return true
    }

    /** Pass-3 (R14): host-side queue removal (per-item Cancel / Clear on the
     *  Send tab) must also stop every live Quick-Share delivery that item
     *  pre-registered — otherwise the rows linger "Waiting" in Active for
     *  a queue the host just tore down. A deliberate removal resolves as
     *  CANCELLED (visible, honest, briefly) rather than vanishing. */
    fun cancelLiveQuickShareByName(qsItemName: String) {
        transfers.values.filter {
            (it.status == Status.PENDING || it.status == Status.ACTIVE ||
                // ── Pass-21 (cancel never lands in WFR, QS half) ────────────
                // A FAILED row — the interrupted/kept-for-resume variant the
                // watchdogs and the abort verdicts produce mid-stream — is
                // ALSO a live delivery this cancel must resolve. The old
                // PENDING||ACTIVE filter skipped exactly those rows, so a
                // host cancel racing its own stall machinery left the row
                // reading "Waiting for reconnection"/"Auto-Resume" after a
                // deliberate Cancel. requestCancel itself keeps
                // COMPLETED/CANCELLED/DECLINED immutable — and pass-20 taught
                // it to flip FAILED rows to CANCELLED — so passing FAILED
                // rows through is the fix, not a risk.
                it.status == Status.FAILED) &&
                it.source == Source.QUICK_SHARE && it.direction == Direction.DOWNLOAD &&
                it.qsItemName == qsItemName
        }.forEach { requestCancel(it.id) }
    }

    /** Pass-6 (R-H): the PEER-SCOPED twin of [cancelLiveQuickShareByName] —
     *  resolves only ONE client's live rows for the item. The host cancelling
     *  a waiting row in the Transfers tab is a decision about THAT client's
     *  offer; the name-wide variant would also kill the identical rows every
     *  other targeted client is waiting on. Used by LumiDropServer.
     *  cancelQuickShareForPeer (the host's per-row Cancel path). */
    fun cancelLiveQuickShareForPeer(qsItemName: String, peerIp: String) {
        transfers.values.filter {
            (it.status == Status.PENDING || it.status == Status.ACTIVE ||
                // Pass-21: FAILED rows included — see cancelLiveQuickShareByName's
                // note; the same cancel-races-the-watchdog hole applied per-peer.
                it.status == Status.FAILED) &&
                it.source == Source.QUICK_SHARE && it.direction == Direction.DOWNLOAD &&
                it.qsItemName == qsItemName && it.peerIp == peerIp
        }.forEach { requestCancel(it.id) }
    }

    /** True when the connection carrying [connToken] is superseded waste for
     *  transfer [id] — i.e. a NEWER handoff request latched after this
     *  connection was issued. The newest request itself (and any request
     *  newer than the latch) is never superseded by its own latch. */
    fun isConnectionSuperseded(id: String, connToken: Long): Boolean {
        val below = transfers[id]?.handoffSupersededBelowConn ?: 0L
        return below > 0L && connToken in 1 until below
    }

    /** Live connection count of a transfer (0 for unknown/terminal ids) —
     *  used by the download endpoints to detect "a sibling already
     *  attached while the first connection is still settling". */
    fun liveConnectionCount(id: String): Int = transfers[id]?.connectionCount?.get() ?: 0

    /** Seeds a freshly-claimed transfer's byte counter with a Range request's
     *  start offset (§7.6/§7.9 — resumable receive): a download manager that
     *  resumes a killed download re-requests with "Range: bytes=N-" and the
     *  new identity would otherwise report progress restarting from 0% and
     *  "complete" at (size-N)/size. Seeding the counter makes the bar pick
     *  up exactly where the previous attempt died. No-ops unless the entry
     *  is ACTIVE with zero counted bytes, so an ordinary parallel-Range
     *  sibling attaching to a progressing transfer can never rewind it. */
    @Synchronized
    fun setRangeStartIfFresh(id: String, rangeStart: Long) {
        val t = transfers[id] ?: return
        if (t.status != Status.ACTIVE || rangeStart <= 0L || t.transferredBytes != 0L) return
        t.transferredBytes = rangeStart
        t.lastSampleBytes = rangeStart
        touch(t.peerIp)
    }

    /** Host/UI-side cancellation request. Two regimes:
     *
     *  PENDING — nothing is streaming yet, so this finalizes the entry
     *  directly (CANCELLED + auto-removal), exactly as before.
     *
     *  ACTIVE — the entry is ALSO finalized immediately now (Phase 1 of
     *  10): status/finishedAt/speed flip right away so every consumer of
     *  TransferManager (native Transfers tab, web xfers payload via SSE/
     *  poll) sees "Cancelled" the instant the host acts, instead of
     *  "Sending/Receiving" persisting until the worker thread happens to
     *  hit its next chunk-boundary cancel check — which, for a connection
     *  wedged on a dead socket, could be never. The worker-side teardown
     *  is unchanged: cancelRequested stays set, so ProgressTracking-
     *  InputStream / the upload copy loop still throw on their next read,
     *  and every finally/callback still runs (slot release, staging
     *  cleanup). Because [complete]/[fail] guard on status ∈ {PENDING,
     *  ACTIVE}, their late arrival after this early finalize is a clean
     *  no-op — no double terminal write, no duplicate auto-removal, no
     *  way for a stale event to resurrect anything. First finalizer wins,
     *  deterministically: if completion lands first, the transfer is
     *  COMPLETED and this cancel is ignored (every byte was already
     *  delivered); if the cancel lands first, late completion is ignored.
     *  That is the exact completion-vs-cancellation ordering rule Phase 1
     *  requires — no timing assumption, no delay, just an atomic
     *  synchronized transition. @Synchronized for exactly that atomicity:
     *  requestCancel, complete() and fail() all serialize on the same lock,
     *  so whichever finalizer takes it first wins outright and the others
     *  become clean no-ops — the read-check-write below can never interleave
     *  with a concurrent completion's own read-check-write. */
    fun requestCancel(id: String) {
        requestCancelLocked(id)
        releaseCancelSlots(id)
    }

    /** ── GOAT-PASS 11 (§7 — atomic, fast whole-folder cancellation) ─────────
     *  The bulk twin of [requestCancel], purpose-built for a folder group's
     *  Cancel: ONE monitor acquisition resolves every member row (instead of
     *  N re-entrant acquisitions — the historical per-row log writes were
     *  the multi-second UI-thread stall behind the ANR users perceived as
     *  "cancellation crashes the app" on large folders). Per-row semantics
     *  are IDENTICAL to [requestCancel]:
     *  every deliberate-cancel side effect (tombstones, latches, terminal
     *  memory, slot release, auto-removal scheduling) runs for every id,
     *  and COMPLETED/CANCELLED/DECLINED rows stay immutable. Safe to call
     *  from the UI thread even for hundreds of members. */
    fun requestCancelBulk(ids: List<String>) {
        if (ids.isEmpty()) return
        val summary = cancelRowsLocked(ids)
        for (id in ids) releaseCancelSlots(id)
        if (summary != null) {
        }
    }

    /** Pass-24: the permit half of a deliberate cancel, shared by the
     *  single-row and bulk paths — hands every concurrency permit the row
     *  holds back immediately (see requestCancel's own Pass-24 note). */
    private fun releaseCancelSlots(id: String) {
        var freed = forceReleaseDownloadSlot(id)
        if (receivingSlotHolders.remove(id)) { receivingGate.release(); freed++ }
        if (freed > 0) {
            val name = transfers[id]?.fileName ?: id
        }
    }

    /** Pass-24: [requestCancel]'s monitor-held body — the atomic cancel
     *  decision itself, unchanged from when it lived inline. */
    @Synchronized
    private fun requestCancelLocked(id: String) {
        val t = transfers[id] ?: return
        cancelRowUnlocked(t)
    }

    /** GOAT-PASS 11 (§7): the bulk monitor-held body — one lock, N rows, one
     *  summary log. Returns (folderHint, resolvedCount) when at least one row
     *  was newly resolved CANCELLED by this pass, else null. The folder hint
     *  is the first resolved row's name prefix — purely for the one log line. */
    @Synchronized
    private fun cancelRowsLocked(ids: List<String>): Pair<String, Int>? {
        var resolved = 0
        var folderHint: String? = null
        for (id in ids) {
            val t = transfers[id] ?: continue
            val before = t.status
            cancelRowUnlocked(t)
            if (before != Status.CANCELLED && t.status == Status.CANCELLED) {
                resolved++
                if (folderHint == null) folderHint = t.fileName.substringBefore('/').ifBlank { t.fileName }
            }
        }
        return if (resolved > 0) (folderHint ?: "?") to resolved else null
    }

    /** GOAT-PASS 11 (§7): the per-row deliberate-cancel body — semantics
     *  byte-identical to the old inline body of requestCancelLocked; the
     *  caller holds this object's monitor. */
    private fun cancelRowUnlocked(t: Transfer) {
        val id = t.id
        t.cancelRequested = true
        t.reconnecting = false   // a deliberate cancel ends any reconnect loop's ownership of this row
        t.verdictPending = false   // Pass-17: the host's own decision outranks any pending verdict
        // ── PASS 26 (receiver-acknowledged completion): a deliberate cancel ──
        // outranks any in-flight ack too — the row is CANCELLED, and a late
        // receiver ack for the cancelled attempt must not be able to complete
        // it out from under the host's own decision.
        t.ackExpected = false
        t.ackDeadlineMs = 0L
        prioritized.remove(id)
        // ── Pass-15 (ghost-item root fix, sender half): the durable tid ──
        // latch — the row auto-dismisses 400 ms below, and every reader of
        // "was this deliberately cancelled?" afterwards (the sender's retry
        // loop above all) must still find the verdict. See the latch's own
        // declaration block for the ghost-item failure mode this closes.
        rememberCancelledTid(id)
        // ── Pass-11 (ghost-transfer root fix): record a durable tombstone ─
        // for this REQUEST the instant a deliberate cancel is requested, so
        // the refusal outlives the row's own 400 ms auto-dismissal — the
        // sender's auto-resume loop may re-POST this file seconds later and
        // must be answered "Cancelled by host", never allowed to mint a
        // ghost row via the start() fallback. Uploads only: a SEND row on
        // the sender carries no uploadRequestId, and downloads use their
        // own name-keyed tombstones.
        rememberCancelledUpload(t.uploadRequestId)
        // ── Pass-13 (ghost-transfer root fix, download half): the same ─────
        // durable refusal for DOWNLOAD rows, keyed by the (source, item,
        // peer) identity the download endpoints' guards already consult
        // (see wasCancelledForQuickShare/wasCancelledForPlainDownload). The
        // live-row halves of those guards die with the 400 ms auto-dismiss;
        // this tombstone holds the intended window so a late browser retry
        // can never resurrect the cancelled transfer as a fresh ghost row.
        if (t.direction == Direction.DOWNLOAD) {
            when {
                t.source == Source.QUICK_SHARE && !t.qsItemName.isNullOrEmpty() ->
                    rememberCancelledDownload("qs", t.qsItemName!!, t.peerIp)
                t.source == Source.PLAIN ->
                    rememberCancelledDownload("pd", t.fileName, t.peerIp)
                else -> Unit
            }
        }
        // A PENDING transfer has no active read-loop checking cancelRequested
        // yet (nothing is blocked in a read() call for it), so it must be
        // finalized here directly rather than relying on a stream callback
        // that will never fire for a transfer that never started.
        // ── Pass-20 (cancel never lands in "Waiting for reconnection") ────────
        // A FAILED row — including every interrupted/kept-for-resume variant
        // the watchdog, the verdict machinery, or an exhausted retry window
        // can produce — is ALSO finalized here. The reported regression:
        // the peer's Cancel (or the local one, racing a watchdog flip)
        // arrived a moment after the row had already been resolved
        // interrupted; requestCancel only touched PENDING/ACTIVE rows, so
        // the deliberate decision silently no-op'd and the row SAT in
        // "Waiting for reconnection"/"Auto-Resume on Reconnection" — a
        // cancel that provably ran on both ends yet the item read as
        // awaiting the network. An explicit cancel OUTRANKS every
        // resumable presentation by definition ("must never place the
        // item into Waiting for Reconnection"), so the flip now covers
        // FAILED too: whichever of the two genuinely happened FIRST, the
        // CANCELLED verdict is the one the person sees. COMPLETED,
        // CANCELLED and DECLINED remain immutable — those are finished
        // decisions, not races.
        if (t.status == Status.PENDING || t.status == Status.ACTIVE || t.status == Status.FAILED) {
            if (t.status == Status.FAILED) {
                pendingRemovals.remove(t.id)?.cancel()
                // The kept-for-resume registration dies with the decision: a
                // deliberately cancelled row renders as a plain Cancelled
                // card (ordinary dismiss), never the discard-with-confirm X
                // — that flow belongs to rows still genuinely awaiting their
                // resume. Staging partials are cleaned by the cancel paths
                // that own them (scope=file native cancels delete staging
                // themselves) and by the stale-staging sweep otherwise.
                resumableRowIds.remove(id)
            }
            t.status = Status.CANCELLED
            t.finishedAt = System.currentTimeMillis()
            t.speedBps = 0.0
            rememberTerminal(t)
            scheduleAutoRemoval(id, CANCELLED_LINGER_MS)
            touch(t.peerIp)
        }
    }

    // ── GOAT-PASS 5 (§7 — the authoritative "is this row EXECUTING?" signal) ──
    // The UI's active presentation (the purple "Sending…" / orange
    // "Receiving…" chip) must derive from whether THIS row genuinely owns an
    // execution slot right now — never from "it was resumed" or "a resume
    // request was accepted". A resumed-but-queued row is ACTIVE in the
    // engine (its identity, checkpoint and resume story are all live) while
    // a DIFFERENT row holds the one permit its direction allows; rendering
    // both as active is exactly the reported "several cards show Sending…/
    // Receiving… while only one transfer is actually running".
    //
    // Direction mapping:
    //   UPLOAD  → holds a receiving-gate permit ([receivingSlotHolders]);
    //   DOWNLOAD→ holds ≥1 download-gate permit ([downloadSlotHolders]);
    //   SEND    → its streaming leg is registered with NativePushClient
    //             ([ownsSendSlotHook] — the dispatch gate's holder set,
    //             wired by NativePushClient at boot so headless engine
    //             tests never depend on it).
    // A paused or non-ACTIVE row is never "executing" by definition.
    @Volatile private var ownsSendSlotHook: ((String) -> Boolean)? = null

    /** GOAT-PASS 5: NativePushClient wires its dispatch-gate holder test
     *  here at boot (nullable, same pattern as the liveness probers — the
     *  JVM engine tests run without it). */
    fun wireOwnsSendSlot(hook: (String) -> Boolean) { ownsSendSlotHook = hook }

    /** GOAT-PASS 5 (§9 park-resume, final round): the LIVE PARKED upload row
     *  for [requestId] — a row whose settled host pause deliberately ENDED
     *  its leg while keeping it ACTIVE (identity, progress and checkpoint
     *  preserved). A fresh re-POST under this requestId (the browser's
     *  park-refusal restart after its checkpoint record was lost, or any
     *  deliberate re-send) must ADOPT this row instead of minting a second
     *  one beside it — one row per logical transfer, even on the restart
     *  path. Read-only lookup, so it can serve as a receiving-slot wait key
     *  before the row is re-claimed. */
    fun findParkedUploadId(requestId: String): String? =
        transfers.values.firstOrNull {
            it.direction == Direction.UPLOAD && it.uploadRequestId == requestId &&
                it.status == Status.ACTIVE
        }?.id

    /** GOAT-PASS 5 (§7 ledger truth, final round): moves an in-flight
     *  receiving-slot REGISTRATION from one row id to another, atomically.
     *
     *  Why: apiUpload acquires its receiving-slot permit under the PENDING
     *  row's id (the wait key) BEFORE it can know which row will actually
     *  stream — and in the rare fallback case (the PENDING row was swept or
     *  cancelled while the request waited at the gate) a FRESH row is minted
     *  by start(). The permit stays registered under the dead id, so the
     *  live row never answers isExecuting() true — it renders "Waiting"
     *  while genuinely streaming (a §7 violation), and a park/resume cycle
     *  on that row would mis-key its checkpoint ledger too.
     *
     *  The caller MUST thread the returned key into its own
     *  releaseReceivingSlot() so the exactly-once release discipline holds
     *  (releasing the dead id after a rekey would no-op and leak the
     *  permit). No-op when [fromId] holds no registration — a gate reset or
     *  watchdog force-release already moved on, and the caller's own release
     *  key then stays [fromId] harmlessly (its release is the same no-op the
     *  pre-existing code already tolerated). */
    fun rekeyReceivingSlot(fromId: String, toId: String) {
        if (fromId == toId) return
        // ConcurrentHashMap-backed set: remove+add is the atomic swap the
        // ledger needs (a permit's OWNER changes identity, its COUNT does
        // not — the gate itself is untouched).
        if (receivingSlotHolders.remove(fromId)) {
            receivingSlotHolders.add(toId)
        }
    }

    /** GOAT-PASS 5 (§7): true when [id] is an ACTIVE, unpaused row that
     *  genuinely owns an execution slot in its direction RIGHT NOW — the
     *  one signal the active (purple/orange) presentation must derive
     *  from. False for resumed-but-waiting rows (they render the neutral
     *  waiting state with their progress preserved). */
    fun isExecuting(id: String): Boolean {
        val t = transfers[id] ?: return false
        if (t.status != Status.ACTIVE || t.pauseRequested) return false
        return when (t.direction) {
            Direction.UPLOAD -> id in receivingSlotHolders
            Direction.DOWNLOAD -> (downloadSlotHolders[id] ?: 0) > 0
            Direction.SEND -> ownsSendSlotHook?.invoke(id) ?: false
        }
    }

    /** GOAT-PASS 5 (§8 — the pause-park debt clear): a receiving leg whose
     *  connection was deliberately ENDED because its row's pause settled
     *  (see LumiDropServer.apiUpload's parked exit) must not carry the
     *  pause-yield's re-acquire debt into the grave — the parked connection
     *  is gone, and the resumed POST acquires its own permit through the
     *  ordinary claim chain. A stale debt would otherwise make a LATER
     *  pause+resume cycle on the same row try to re-acquire permits it no
     *  longer owes (over-acquiring on a 1-permit gate = self-deadlock).
     *  Mirrors the exact resets reopenForResume/demoteToPendingForPause
     *  already perform for their own leg endings. */
    fun clearPauseSlotDebt(id: String) {
        val t = transfers[id] ?: return
        t.pauseYieldedSlots.set(0)
        t.pauseReacquireArmed = false
    }

    fun isCancelled(id: String): Boolean = transfers[id]?.cancelRequested == true

    // Deliberately allowed on PENDING as well as ACTIVE: a transfer paused
    // while still waiting stays paused straight through its PENDING→ACTIVE
    // promotion (claimPending/claimPendingUpload never touch pauseRequested),
    // so the moment it actually starts, its very first read() call already
    // sees pauseRequested=true and blocks immediately — "pause a waiting
    // file so it never even begins" falls out of the existing mechanism for
    // free, with no separate pre-start pause path to build or test.
    fun requestPause(id: String) {
        transfers[id]?.let {
            if (it.status == Status.ACTIVE || it.status == Status.PENDING) {
                it.pauseRequested = true
                // Pass-18: stamp the moment the pause began (kept across the
                // PENDING demotion a native sender's pause-yield performs — a
                // demoted row is still paused); the gates' preemption paths
                // only act on pauses older than PAUSE_YIELD_GRACE_MS so a
                // quick toggle never shuffles any permit at all.
                if (it.pauseStartedAtMs == 0L) it.pauseStartedAtMs = System.currentTimeMillis()
                // ── Pass-19 (queue auto-promotion, both gates) ─────────────────
                // An IN-FLIGHT transfer freezes inside awaitUnpaused holding a
                // concurrency permit. The queue must not freeze with it: once
                // this pause has genuinely settled (past the same grace the
                // preemption paths use), its permits are handed back so the
                // next Waiting item starts immediately — on the download gate
                // (app→browser: the next Quick-Share/plain download request
                // parked in the waiter queue) AND on the receiving gate
                // (app→app: the sender's next file POST queued for a slot).
                // The paused connection itself is untouched — it resumes in
                // place on its own socket when its user resumes it, and the
                // exactly-once holder-entry discipline makes its eventual
                // normal release a no-op, so permits can never double-count.
                if (it.status == Status.ACTIVE) schedulePausePermitYield(id)
                touch(it.peerIp)
            }
        }
    }
    /**
     * Clears the pause flag for [id]. For an ACTIVE transfer this alone is
     * enough — its connection is already open and blocked in
     * [awaitUnpaused], so it resumes flowing on its own existing connection
     * with no ambiguity about "which one." For a still-PENDING (Waiting)
     * transfer, nothing is blocked on a live connection yet — the CLIENT's
     * own queue decides when to actually fire the real request — so this
     * also marks it [prioritized]: the explicit signal that this exact
     * transfer, not simply whichever one is first in that client's local
     * FIFO order, should claim the next free slot. This is what makes an
     * explicit per-item Resume on a Waiting transfer always win, instead of
     * a different (earlier-queued) transfer silently starting in its place.
     */
    fun requestResume(id: String) {
        val t = transfers[id] ?: return
        t.pauseRequested = false
        t.pauseStartedAtMs = 0L
        if (t.status == Status.PENDING) prioritized.add(id)
        touch(t.peerIp)
    }
    fun isPaused(id: String): Boolean = transfers[id]?.pauseRequested == true

    /** ── GOAT-PASS 6 (§3 — a paused download never parks a second connection) ──
     *  The PENDING-or-ACTIVE DOWNLOAD row for exactly this QS item (or plain
     *  file name, when [qsItemName] is null) and peer, when it has been
     *  deliberately paused PAST the toggle grace — the download endpoints
     *  consult this to answer a download manager's retry of a paused item
     *  with "Paused by host" (+ Connection: close) instead of parking yet
     *  another browser connection beside the paused one for the whole
     *  duration of the pause. This is the download-side twin of the upload
     *  park-refusal GOAT-PASS 5 gave apiUpload. The browser's own dispatch
     *  pump only dispatches once the pause lifts (its pd hold), and
     *  requestResume zeroes pauseStartedAtMs, so a legitimate resume is
     *  never refused. Null = nothing paused-past-grace for this identity. */
    fun pausedDownloadPastGrace(qsItemName: String?, fileName: String, peerIp: String): Transfer? =
        transfers.values.firstOrNull {
            it.direction == Direction.DOWNLOAD && it.peerIp == peerIp &&
                (it.status == Status.PENDING || it.status == Status.ACTIVE) && it.pauseRequested &&
                it.pauseStartedAtMs != 0L &&
                System.currentTimeMillis() - it.pauseStartedAtMs > PAUSE_YIELD_GRACE_MS &&
                ((qsItemName != null && it.source == Source.QUICK_SHARE && it.qsItemName == qsItemName) ||
                    (qsItemName == null && it.source != Source.QUICK_SHARE && it.fileName == fileName))
        }

    // ── Pass-16 (pause-yield, receiver half) ──────────────────────────────────
    /** The sender of an app-to-app push deliberately aborted its POST because
     *  its user PAUSED the file (its send loop yields the dispatch permit so
     *  a different waiting item can start instead — see NativePushClient's
     *  pause path). The receiving row returns to PENDING — "Waiting" again,
     *  with its byte checkpoint preserved and its pause flag untouched — so
     *  the sender's later resumed POST re-claims THIS exact row via the
     *  unchanged composite request id and the one-card continuation survives
     *  the pause. Not FAILED, not interrupted, no tombstone, no auto-removal:
     *  a pause is a scheduled continuation, not an outcome. */
    @Synchronized
    fun demoteToPendingForPause(id: String): Boolean {
        val t = transfers[id] ?: return false
        if (t.status != Status.ACTIVE) return false
        t.status = Status.PENDING
        t.reconnecting = false
        t.interrupted = false
        t.speedBps = 0.0
        t.lastSampleAtMs = System.currentTimeMillis()
        t.lastSampleBytes = t.transferredBytes
        // ── GOAT-PASS 3 (§6): the parked leg is over (its connection died
        // with the sender's deliberate abort) — its slot debt must not
        // outlive it into the resumed POST's fresh leg, which acquires its
        // own permit through the ordinary claim chain.
        t.pauseYieldedSlots.set(0)
        t.pauseReacquireArmed = false
        pendingRemovals.remove(id)?.cancel()
        touchT(t)
        return true
    }

    // ── Pass-19 (queue auto-promotion, both gates) ────────────────────────────
    /** The scheduler half of "pausing the in-progress item immediately starts
     *  the next Waiting one". Pausing an ACTIVE transfer parks its worker
     *  inside awaitUnpaused, and until pass-19 that worker kept its
     *  concurrency permit for as long as the pause lasted — with one permit
     *  per direction, every Waiting item behind it was frozen too, on BOTH
     *  legs of an app-to-app transfer (the sender's dispatch gate on one
     *  side, the receiver's receiving gate on the other) and on the
     *  app-to-browser download gate. Pass-18's preemptPaused*Holders only
     *  freed such permits when an explicitly resumed waiter happened to
     *  arrive next — the queue moved ONLY for that one resumed item, never
     *  on the pause itself.
     *
     *  This schedules the yield the moment the pause lands, delayed by
     *  PAUSE_YIELD_GRACE_MS so a double-tap or a quick toggle blip (< 750 ms)
     *  shuffles nothing at all — identical to the preemption discipline.
     *  At fire time the pause is re-verified (still ACTIVE, still paused,
     *  past the grace), then EVERY permit the row holds is handed back:
     *  download-gate permits (a browser's parallel Range connections can
     *  hold several under one id) and the receiving-gate permit, each with
     *  the exact holder-entry removal that makes the paused worker's own
     *  eventual release a guaranteed no-op. The parked connection is never
     *  touched: it resumes in place on its own socket when the user resumes
     *  it — it simply no longer blocks the queue while it waits. */
    private val pauseYieldExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ldx-pause-yield").apply { isDaemon = true }
    }
    private val pauseYieldScheduled = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private fun schedulePausePermitYield(id: String) {
        if (!pauseYieldScheduled.add(id)) return   // already armed — the chain below re-checks at fire time anyway
        // ── Pass-24 (the no-re-arm hole, root fix) ──────────────────────────────
        // The pass-19 shape removed the id from the armed set FIRST and then
        // bailed without re-arming when the fire-time grace check failed —
        // which it does whenever pauseStartedAtMs was RE-STAMPED after this
        // task was scheduled (a pause→resume→re-pause inside the window: the
        // toggle path zeroes the stamp and the re-pause re-stamps it). With
        // the set entry consumed and nothing rescheduled, a paused ACTIVE
        // row then held the only download permit forever — every watchdog
        // exempts pauseRequested rows, so the queue stayed wedged behind it
        // (the reported "second consecutive pause never starts the next
        // item"). The task now RESCHEDULES ITSELF for the remainder of the
        // (new) grace window instead of dropping the arming, and the set
        // entry survives until the chain reaches a real outcome — yield,
        // unpause, cancel, or the row disappearing. The stamp can only move
        // on pause/resume transitions, so the chain is short by construction.
        val task = object : Runnable {
            override fun run() {
                try {
                    val t = transfers[id]
                    if (t == null || t.status != Status.ACTIVE || !t.pauseRequested) {
                        pauseYieldScheduled.remove(id)
                        return
                    }
                    val stamp = t.pauseStartedAtMs
                    if (stamp != 0L) {
                        val remainMs = stamp + PAUSE_YIELD_GRACE_MS - System.currentTimeMillis()
                        if (remainMs > 0L) {
                            // Inside a (re-stamped) grace window — come back
                            // when the CURRENT pause has genuinely settled.
                            try {
                                pauseYieldExec.schedule(this, remainMs, java.util.concurrent.TimeUnit.MILLISECONDS)
                            } catch (e: Exception) {
                                pauseYieldScheduled.remove(id)   // scheduler died — the watchdog backstop below still bounds the row
                            }
                            return
                        }
                    }
                    pauseYieldScheduled.remove(id)
                    var freed = forceReleaseDownloadSlot(id)
                    if (receivingSlotHolders.remove(id)) {
                        receivingGate.release()
                        freed++
                    }
                    if (freed > 0) {
                        // ── GOAT-PASS 3 (§6): record the re-acquire debt. ──
                        // The queue may reuse these permits immediately (that
                        // is the yield's whole purpose), but THIS row now
                        // owes the gates exactly [freed] permits back before
                        // it may stream again — see [awaitSlotBackAfterPause],
                        // the resume-side half of the contract this pass adds.
                        // Without the debt record a resumed stream had no way
                        // to know its slot had been handed back at all.
                        t.pauseYieldedSlots.addAndGet(freed)
                    }
                    // ── GOAT-PASS 6 (§3 — mark the parked download connection) ──
                    // For a DOWNLOAD row the settled pause leaves its serve leg
                    // parked inside awaitUnpaused with the browser's connection
                    // still open — that connection's eventual death must be
                    // recognized as the pause's own fallout (see Transfer.
                    // pauseParkedConn and downloadConnectionAborted), never as
                    // a cancel/interruption verdict. Native receives and uploads
                    // deliberately END their legs on a settled pause (the sender
                    // aborts its POST / apiUpload answers "Paused by host"), so
                    // only the download direction carries a parked connection.
                    if (t.direction == Direction.DOWNLOAD) {
                        t.pauseParkedConn = true
                    }
                } catch (e: Exception) {
                    // never let a yield task kill the scheduler — but never
                    // strand the armed-set entry either
                    pauseYieldScheduled.remove(id)
                }
            }
        }
        try {
            pauseYieldExec.schedule(task, PAUSE_YIELD_GRACE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: Exception) { pauseYieldScheduled.remove(id) }
    }

    /**
     * Blocks the calling (worker) thread while [id]'s transfer is paused,
     * waking promptly on resume or cancel. Safe to call from inside a hot
     * read loop: each iteration is a short, bounded sleep, never a spin.
     * Call sites: [com.lumidrop.x.transfer.ProgressTrackingInputStream] for
     * downloads, and the raw-upload byte-copy loop in LumiDropServer for
     * uploads — both are per-transfer worker threads, so blocking here only
     * stalls that one transfer's own thread, never any other transfer.
     */
    fun awaitUnpaused(id: String) {
        // ── Pass-19 (queue auto-promotion, the universal hook) ────────────────
        // Whatever path parks a stream on a paused row — a mid-flight pause, a
        // Pause-All flag raised while the row was still WAITING that only
        // bites once the row claims and its first read begins, a demoted-
        // back-to-PENDING row re-claimed by a resumed POST — the stream parks
        // holding a concurrency permit. Scheduling the grace-delayed permit
        // yield HERE (on entry, once per id via the scheduler's own set
        // guard) covers every one of those paths with a single hook, instead
        // of trying to enumerate claim sites. The scheduled task re-verifies
        // ACTIVE+paused+grace at fire time, so a pause that lifted before the
        // grace elapsed releases nothing and shuffles nothing; ids that hold
        // no gate permit (a native SEND's own dispatch gate lives in
        // NativePushClient) make the yield a harmless no-op.
        if (isPaused(id)) {
            val t = transfers[id]
            if (t != null && t.status == Status.ACTIVE) schedulePausePermitYield(id)
        }
        var parked = false
        while (isPaused(id) && !isCancelled(id)) {
            parked = true
            try { Thread.sleep(PAUSE_POLL_MS) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); return
            }
        }
        // ── GOAT-PASS 3 (§6 — the resume-side half of the pause-yield) ──────
        // A worker that actually PARKED may only continue streaming once its
        // row holds its execution slot(s) again. The pause-yield handed them
        // back to the queue (correctly — the queue must not freeze behind a
        // paused stream); resuming WITHOUT taking them back through the very
        // same gates is how five paused items could all stream at once beside
        // the current slot holder. No-op when the yield never fired (a blip
        // shorter than the grace window — the worker still holds its slot).
        if (parked) awaitSlotBackAfterPause(id)
    }

    // ── GOAT-PASS 3 (§6) ────────────────────────────────────────────────────
    /** Thrown by [awaitUnpaused] when a parked worker's re-acquire of its
     *  yielded execution slot was aborted outright (a gate reset or the
     *  sliding-window expiry on a genuinely wedged gate): the stream must
     *  STOP rather than flow unslotted. Extends IOException so the
     *  download-side ProgressTrackingInputStream propagates it through the
     *  exact channel a deliberate cancel already uses (connection teardown →
     *  interrupted resolution → checkpoint preserved), while the receive-side
     *  loops catch it EXPLICITLY and break into their interruption paths
     *  (drain + durable checkpoint + honest interrupted state) — never their
     *  server-fault paths, which would destroy the very checkpoint the
     *  client's reconnect needs. */
    class SlotReacquireAbortedException : java.io.IOException("Transfer slot re-acquisition aborted")

    /** Re-acquires, through the authoritative gates, every execution-slot
     *  permit the pause-yield handed back while this row's worker(s) were
     *  parked — the missing other half of Pass-19's pause-yield. Exactly one
     *  worker per row performs the acquisition (sibling connections of the
     *  same row — a browser's parallel Range set — wait for it, preserving
     *  the pre-pause "the row owns its slots" accounting). Blocks behind
     *  whichever transfer currently holds the gate, exactly like every other
     *  waiter, ordered by the row's original startedAt (an explicitly resumed
     *  earlier item therefore takes the next free slot ahead of later queued
     *  ones). Throws IOException only when the wait was aborted outright (a
     *  gate reset or the sliding-window expiry on a genuinely wedged gate):
     *  the row must never stream on a slot it could not get back, and the
     *  same exception channel a deliberate cancel uses tears the parked
     *  connection down honestly — its checkpoint survives for the client's
     *  own retry to re-open. */
    private fun awaitSlotBackAfterPause(id: String) {
        while (true) {
            val t = transfers[id] ?: return
            if (t.pauseYieldedSlots.get() <= 0) return   // yield never fired — this worker still holds its slot
            if (isCancelAuthoritative(id)) return        // deliberate stop — the caller's cancel check tears the stream down
            var armedByMe = false
            synchronized(t) {
                if (!t.pauseReacquireArmed) { t.pauseReacquireArmed = true; armedByMe = true }
            }
            if (!armedByMe) {
                // A sibling worker of the SAME row is mid-re-acquire for the
                // whole row. Wait it out: neither connection may flow a byte
                // before the row holds its slots again. (If the sibling bails
                // without acquiring — armed cleared with debt still owed —
                // the loop's next iteration promotes THIS worker to armer.)
                while (transfers[id]?.pauseReacquireArmed == true && !isCancelAuthoritative(id)) {
                    try { Thread.sleep(PAUSE_POLL_MS) } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt(); return
                    }
                }
                continue
            }
            try {
                val n = t.pauseYieldedSlots.getAndSet(0)
                var aborted = false
                for (i in 0 until n) {
                    val ok = when (t.direction) {
                        Direction.UPLOAD -> acquireReceivingSlot(id)
                        Direction.DOWNLOAD -> acquireDownloadSlot(id)
                        // A native SEND never held the local gates — its
                        // dispatch permit is re-acquired by NativePushClient
                        // .resumeFile before the relaunched POST's first byte.
                        Direction.SEND -> true
                    }
                    if (!ok) { aborted = true; break }
                }
                if (aborted) {
                    throw SlotReacquireAbortedException()
                }
                return
            } finally {
                t.pauseReacquireArmed = false
            }
        }
    }

    // ── Pass-19 (queue auto-promotion, sender half) ───────────────────────────
    /** Bounded twin of [awaitUnpaused]: waits for the pause to lift for at
     *  most [maxWaitMs], then RETURNS ANYWAY so the caller can decide what
     *  to do with a pause that has genuinely settled. Returns true = the
     *  transfer is STILL paused past the grace window (the caller should
     *  yield/park it); false = it resumed (or was cancelled/unknown) within
     *  the window and streaming simply continues.
     *
     *  Why this exists: the plain [awaitUnpaused] above never returns while
     *  paused — which is CORRECT for the server-side download/upload stream
     *  loops (a parked connection holds no permit once the scheduled yield
     *  below frees it), but was silently DEADLY for the native send loop:
     *  NativePushClient.streamFile's "if (isPaused) yield" check sat right
     *  AFTER an awaitUnpaused that only ever returned un-paused, so the
     *  pass-16 pause-yield NEVER fired. A paused in-flight send therefore
     *  held its dispatch permit forever — the next Waiting item could never
     *  start (the reported "pausing the in-progress item doesn't start the
     *  next one"), and only the previously-in-progress item could ever
     *  resume (its blocked loop simply continued), never any of the others.
     *  The sender now uses this bounded form: a sub-500 ms blip resumes in
     *  place with zero disruption, and a REAL pause yields the permit so
     *  the queue keeps moving. */
    fun awaitUnpausedOrYield(id: String, maxWaitMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (isPaused(id) && !isCancelled(id) && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(PAUSE_POLL_MS) } catch (e: InterruptedException) {
                Thread.currentThread().interrupt(); return false
            }
        }
        return isPaused(id) && !isCancelled(id)
    }

    // ── GOAT-PASS 2 (concurrency = 1, STRICT) ──────────────────────────────────
    // How many transfers may move bytes in EACH direction (receiving and
    // downloads) at the same time. GOAT-PASS 1 raised both gates from their
    // long-standing 1 to 3 to fill single-stream backoff gaps with parallel
    // streams. GOAT-PASS 2's specification REJECTS that approach as an
    // explicit, non-negotiable requirement: transfer concurrency must remain
    // EXACTLY 1 — no parallel file transfers, no parallel chunk transfers,
    // no multiple simultaneous upload workers, and no hidden equivalent
    // under another name. The gates are therefore returned to 1, and the
    // throughput work moves to where the specification directs it: the
    // INTERNAL processing pipeline of the single active transfer (see
    // transfer/PipelineIO.kt — a bounded reader/writer decoupling that keeps
    // one transfer's TCP stream continuously fed/drained across disk-latency
    // spikes without ever creating a second transfer).
    //
    // Everything the 3-permit experiment touched is per-transfer-id
    // machinery that was built to serve a set of holders (receivingSlot-
    // Holders, downloadSlotHolders per-id counts, multi-connection Range
    // holds, watchdog force-release, exactly-once release discipline), so
    // returning the permit COUNT to 1 changes no ordering, preemption or
    // release semantics — the multi-connection Range hold paths still
    // function when a browser itself opens parallel connections for ONE
    // logical download, which is the browser's own behavior, not a transfer
    // our engine started in parallel.
    //
    // Client-visible advertisement stays wired (rconc/dconc in the state
    // payload, receivingConcurrency in the native push status) but now
    // always reports 1; the web pumps and NativePushClient's dispatch gate
    // ALSO hard-cap at 1 on their side so a mixed-version peer that still
    // advertises 3 (a GOAT-PASS 1 build) can never coax this app into
    // running parallel transfers.
    private const val TRANSFER_CONCURRENCY = 1

    // ── Unified concurrency-slot architecture (Phase 1 of 10) ────────────────
    // Both directions of byte-movement now run through the SAME slot
    // discipline, previously split across two asymmetric implementations:
    //
    //  INBOUND (receiving: uploads + native pushes)  — Semaphore + holder
    //  tracking + stall-watchdog force-release + drain-and-rebuild on
    //  (re)configuration. Unchanged in spirit from before.
    //
    //  OUTBOUND (downloads: plain files, APKs, Quick-Share, ZIP streams) —
    //  previously a bare synchronized counter in LumiDropServer with NO
    //  holder identity, NO watchdog force-release, and NO reset on server
    //  restart. Three concrete failure modes came from that asymmetry:
    //    (a) a download whose worker thread wedged on a dead-but-unreset
    //        client socket held its slot FOREVER (the stall watchdog failed
    //        the TRANSFER but had no way to free the SLOT) — with the
    //        default concurrency of 1, one such connection wedged every
    //        future download for the rest of the process's life;
    //    (b) a request cancelled while still waiting in the queue still
    //        waited for a full slot (only a 15-minute fail-open ever broke
    //        the wait) — the wait/notify loop had no cancel awareness;
    //    (c) a server restart (any settings change) left pre-restart
    //        downloadGateActive counts behind with no reset path.
    //  The download gate now lives HERE, next to the receiving gate, with
    //  the same holder-set discipline, the same watchdog force-release,
    //  the same drain-and-rebuild configuration, and the same
    //  prompt-cancel-abort wait loop as the receiving side. Call sites in
    //  LumiDropServer changed shape only minimally (see
    //  [acquireDownloadSlot] / [releaseDownloadSlot] /
    //  [moveDownloadSlot]).
    //
    // EXACTLY-ONCE RELEASE, both gates: a permit is released only when a
    // real hold is consumed ([releaseReceivingSlot] /
    // [releaseDownloadSlot] both check holder membership first). The old
    // receiving-side code released the Semaphore unconditionally, so a
    // watchdog force-release followed by a LATE recovery of the stuck
    // worker thread (its finally block eventually running anyway) inflated
    // the permit count above the configured limit, permanently — the
    // mirror image of a leak, and just as real. Force-release removes the
    // holder entry, so the late normal release becomes a no-op; the count
    // can now only ever move between 0 and the configured limit.
    //
    // MULTI-CONNECTION HOLDS (download side only): a browser's parallel
    // Range connections for ONE logical transfer each acquire their own
    // permit under the SAME transfer id (see claimOrStartActive*), so the
    // download holder map counts holds per id rather than just membership.
    // Each connection's own close() callback releases exactly its own
    // permit; a force-release of a dead multi-connection transfer frees
    // ALL of its remaining permits at once.
    private val receivingGate = java.util.concurrent.Semaphore(TRANSFER_CONCURRENCY, true)
    @Volatile private var receivingGateLimit = TRANSFER_CONCURRENCY
    // GOAT (critical fix — stuck receiving slot): which transfer id
    // currently holds each permit — purely so sweepStalledActive (below)
    // can force a permit free when the transfer holding it has gone
    // silent for STALL_TIMEOUT_MS. A dead-but-not-cleanly-closed
    // connection (network drop with no FIN/RST — see STALL_TIMEOUT_MS's
    // own doc comment) leaves writeRawStream's inp.read() blocked
    // indefinitely with no exception ever thrown to reach its finally
    // block, so nothing would otherwise ever release that permit — and
    // with the default receiving concurrency of 1, a single such
    // connection would silently and permanently wedge every future
    // receive for the rest of the process's life. A normal completion
    // always removes its own entry here first (see releaseReceivingSlot),
    // so the watchdog only ever acts on an id that's genuinely still here
    // long after it should have finished one way or another.
    private val receivingSlotHolders = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    // Ids currently INSIDE acquireReceivingSlot's wait loop (or holding a
    // permit). Lets the upload-request TTL sweep (LumiDropServer.
    // sweepStalePendingUploads) distinguish "this client is gone" from
    // "this upload is queued behind a receiving slot the SERVER itself
    // controls" — sweeping the latter's PENDING row out from under its
    // own live POST request is exactly what used to make that request
    // complete as an untracked ghost (apiUpload's claimPendingUpload
    // falling back to the now-dead pending id, with no Transfer row ever
    // recording the bytes that landed).
    private val receivingInFlightIds = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    private val downloadGateLock = java.util.concurrent.locks.ReentrantLock(true)
    private val downloadGateCond = downloadGateLock.newCondition()
    @Volatile private var downloadGateLimit = TRANSFER_CONCURRENCY
    // transfer id -> number of permits currently held under that id
    // (usually 1; >1 for a browser's parallel Range connections attached
    // to one logical transfer — each gated connection holds its own).
    private val downloadSlotHolders = java.util.concurrent.ConcurrentHashMap<String, Int>()

    // ── Pass-10 (queue-order fix): strictly-ordered download admission ─────
    // The old gate was a plain FAIR SEMAPHORE: the free permit went to
    // whichever waiting HTTP request ARRIVED at the semaphore first. A
    // browser does not fire a batch's download requests in the order it
    // displayed them (Chrome's download manager re-queues <a download>
    // starts, connection coalescing shuffles arrival by tens of
    // milliseconds), so with File 1 streaming and Files 2-5 visibly queued,
    // the next permit could land on File 3 — the exact "a different item
    // starts instead of the next one in the queue" report. The gate now
    // admits the waiter whose TRANSFER has the oldest startedAt — the same
    // order the Transfers tab renders (PENDING sorted ascending by
    // startedAt) — with requests that never pre-registered a PENDING row
    // (zip streams, hand-built URLs) falling behind them on arrival order.
    // Displayed sequence and actual transfer sequence are now the same
    // sequence by construction, for every download path (Quick-Share,
    // plain, multi-select, APK).
    //
    // ── Pass-18 (resume-one-of-all-paused): two additions ────────────────────
    //  1. PRIORITY ADMISSION — a waiter whose key is in [prioritized] (the
    //     host just tapped Resume on exactly that Waiting row) is admitted
    //     ahead of the ordinary (startedAt, seq) head, so the item the host
    //     explicitly picked is always the one that starts next, never an
    //     earlier-queued sibling. requestResume's own contract ("this exact
    //     transfer, not simply whichever one is first in the client's local
    //     FIFO order") is now honored by the gate itself, not just by the
    //     client-side queue hint.
    //  2. PAUSED-HOLDER PREEMPTION — with one permit per direction, a
    //     transfer the host PAUSED mid-stream used to keep its permit while
    //     its worker sat blocked in awaitUnpaused, so with every item
    //     paused the resumed one could NEVER start: its request waited for
    //     a permit that would only free when the paused stream finished or
    //     was cancelled — the reported "resume one item while all are
    //     paused and it stays stuck on Waiting". A prioritized waiter that
    //     would otherwise wait now force-releases the permits of ACTIVE
    //     holders that have been PAUSED longer than PAUSE_YIELD_GRACE_MS.
    //     The paused stream's own connection is untouched (still open,
    //     still stalled — it resumes in place on its own socket when its
    //     user resumes it); only the PERMIT is handed over, and the
    //     exactly-once release discipline already makes the paused stream's
    //     eventual releaseReceivingSlot/download-slot release a no-op. A
    //     pause younger than the grace (a double-tap blip, a quick toggle)
    //     never loses its permit at all.
    private class DownloadWaiter(val startedAt: Long, val seq: Long, val key: String) : Comparable<DownloadWaiter> {
        override fun compareTo(other: DownloadWaiter): Int {
            val a = startedAt.compareTo(other.startedAt)
            return if (a != 0) a else seq.compareTo(other.seq)
        }
    }
    private val downloadWaiters = java.util.TreeSet<DownloadWaiter>()
    private val downloadWaitSeq = java.util.concurrent.atomic.AtomicLong(0)
    private var downloadGatePermits = TRANSFER_CONCURRENCY

    /**
     * Reliability pass §3 established the gate-resizing entry point for
     * server (re)starts; GOAT-PASS 2 returns the fixed limit to exactly 1
     * (see [TRANSFER_CONCURRENCY]'s doc for the contract).
     * Called right after [failAllActive] (see ServerForegroundService),
     * which is exactly the "everything's already been drained, safe to
     * reset" moment the drain-and-rebuild needs.
     */
    fun resetGates() {
        receivingGate.drainPermits()
        receivingSlotHolders.clear()
        receivingInFlightIds.clear()
        receivingGate.release(TRANSFER_CONCURRENCY)
        receivingGateLimit = TRANSFER_CONCURRENCY
        downloadGateLock.lock()
        try {
            downloadGatePermits = TRANSFER_CONCURRENCY
            downloadSlotHolders.clear()
            // Waiters are released from the queue outright: their requests
            // belong to the dying server instance. Each blocked acquire
            // notices its waiter vanished (membership check at loop top) and
            // aborts within one await cycle instead of parking for its full
            // 60s deadline against a gate that will never turn again.
            downloadWaiters.clear()
            downloadGateCond.signalAll()
        } finally { downloadGateLock.unlock() }
        downloadGateLimit = TRANSFER_CONCURRENCY
    }

    fun receivingConcurrencyLimit(): Int = receivingGateLimit
    fun downloadConcurrencyLimit(): Int = downloadGateLimit

    /** How many UPLOAD-direction transfers other than [id] (null = count
     *  them all) are currently ACTIVE (streaming) — used by the
     *  upload-progress notification to decide whether finishing one upload
     *  may clear the notification while sibling uploads are still in
     *  flight. GOAT-PASS 2 pins the receiving gate at 1 so siblings no
     *  longer occur from this host's own admission; the helper stays as the
     *  notification's decoupled, constant-agnostic guard. */
    fun activeUploadsOtherThan(id: String?): Int =
        transfers.values.count { it.status == Status.ACTIVE && it.direction == Direction.UPLOAD && it.id != id }

    /**
     * Blocks the calling thread until a receiving slot is free, so [id]
     * stays visibly "Waiting" (still PENDING — see [findPendingUploadId] /
     * [claimPendingUpload]) for exactly as long as it's actually waiting on
     * something, rather than showing "Active" the instant its HTTP request
     * merely arrives. Polls at the same cadence as [awaitUnpaused] so a host
     * Cancel issued while a file is still waiting for a slot — i.e. before
     * it ever holds one — takes effect within one poll tick instead of
     * holding a NanoHTTPD worker thread (and the sender's connection) open
     * indefinitely for a file nobody wants anymore.
     *
     * Returns false if [id] was cancelled before a slot was ever acquired —
     * the caller must not proceed to stream bytes in that case, and must
     * NOT call [releaseReceivingSlot] either, since nothing was acquired.
     */
    /**
     * ── FINAL PRODUCTION PASS 18 (§5/§6 — the bounded, observable slot wait).──
     * Outcome of one attempt to obtain a receiving slot:
     *  ACQUIRED — the permit is held (release exactly as before);
     *  CANCELLED — the row was deliberately cancelled (same as the old false);
     *  STILL_QUEUED — [maxWaitMs] elapsed while genuinely queued behind other
     *  waiters (the row is alive, not cancelled — the caller answers the
     *  sender HTTP 425 with the queue depth so it can retry WITHOUT burning
     *  its backoff budget, instead of the old silent unbounded block whose
     *  45-second read-timeout death re-POSTed to the FIFO TAIL and starved
     *  later files of a large batch behind one slow first file).
     */
    enum class SlotWait { ACQUIRED, CANCELLED, STILL_QUEUED }

    /** The bounded wait the file endpoints use; null [maxWaitMs] preserves the
     *  old unbounded semantics for every existing caller. */
    fun acquireReceivingSlotBounded(id: String, maxWaitMs: Long): SlotWait {
        val waitStartMs = System.currentTimeMillis()
        receivingInFlightIds.add(id)
        try {
            while (!receivingGate.tryAcquire(PAUSE_POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                if (isCancelAuthoritative(id)) return SlotWait.CANCELLED
                if (id in prioritized) preemptPausedReceivingHolders()
                // FP18: the bounded half — a live, uncancelled waiter that has
                // queued past the bound reports STILL_QUEUED. The HTTP layer
                // turns this into an immediate 425 + queueDepth answer, so the
                // SENDER never eats a socket timeout for an ordinary queue
                // turn that simply hasn't come up yet.
                if (maxWaitMs > 0 && System.currentTimeMillis() - waitStartMs >= maxWaitMs) {
                    return SlotWait.STILL_QUEUED
                }
            }
            if (isCancelAuthoritative(id)) {
                receivingGate.release()
                return SlotWait.CANCELLED
            }
            receivingSlotHolders.add(id)
            return SlotWait.ACQUIRED
        } finally {
            receivingInFlightIds.remove(id)
        }
    }

    fun acquireReceivingSlot(id: String): Boolean {
        val waitStartMs = System.currentTimeMillis()
        receivingInFlightIds.add(id)
        try {
            while (!receivingGate.tryAcquire(PAUSE_POLL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                // Pass-17: the AUTHORITATIVE cancel check — a deliberately
                // cancelled row auto-dismisses after 400 ms, and a queued
                // request must still abort the moment its transfer is gone.
                if (isCancelAuthoritative(id)) {
                    return false
                }
                // ── Pass-18 (resume-one-of-all-paused): a HOST-PRIORITIZED ──
                // waiter (an explicit per-item Resume on exactly this queued
                // upload) must not park behind a permit an ACTIVE holder only
                // holds because the host paused it mid-stream — its worker is
                // blocked in awaitUnpaused and would keep the permit for as
                // long as the pause lasts. Hand the paused holder's permit
                // over (connection untouched — see
                // preemptPausedReceivingHolders); the fair semaphore then
                // serves its FIFO head with it, and if a PAUSED jumper ahead
                // of us takes it instead, the next tick preempts that jumper
                // too, so the resumed item climbs to its turn within a tick
                // or two — "resumes instantly and correctly without affecting
                // anything else".
                if (id in prioritized) preemptPausedReceivingHolders()
            }
            // Cancel may have landed in the window between the last failed
            // tryAcquire and this successful one — check once more AFTER the
            // grant, and hand the permit straight back if so. Without this,
            // a host-cancelled upload whose slot happened to be free would
            // sail through and claim/start a transfer the host just killed.
            if (isCancelAuthoritative(id)) {
                receivingGate.release()
                return false
            }
            receivingSlotHolders.add(id)
        } finally {
            // Holder registration (above) now carries the identity from the
            // moment of grant, so the in-flight marker is only needed while
            // WAITING — exactly what the TTL sweep wants to know about.
            receivingInFlightIds.remove(id)
        }
        val waitedMs = System.currentTimeMillis() - waitStartMs
        // Only logged past a threshold -- most acquires are effectively
        // instant (a free slot, or a very short queue) and logging every
        // single one would bury the entries that actually matter under
        // routine, expected waiting.
        return true
    }

    /** Always pair with a successful [acquireReceivingSlot] call, exactly
     *  once, whether the transfer that held the slot finished, failed, or
     *  was cancelled — a missed release permanently shrinks capacity by one
     *  for the rest of the server's lifetime. [id] must be the SAME id
     *  passed to [acquireReceivingSlot] — that's what lets this remove its
     *  own entry from [receivingSlotHolders] so the stall watchdog doesn't
     *  later mistake a cleanly-finished transfer for a stuck one.
     *  Exactly-once: a no-op when this id isn't a current holder (already
     *  force-released by the watchdog, or cleared by a gate reset). */
    fun releaseReceivingSlot(id: String) {
        if (receivingSlotHolders.remove(id)) receivingGate.release()
    }

    /** True while [id] is queued for or holding a receiving slot — i.e.
     *  there is a LIVE server-side worker request for it. See
     *  [receivingInFlightIds]'s doc for the ghost-completion race this
     *  closes for the upload-request TTL sweep. */
    fun isReceivingInFlight(id: String): Boolean =
        id in receivingInFlightIds || id in receivingSlotHolders

    /** ── FP18 (§20 diagnostics): how many receives are currently waiting for
     *  a slot (not counting the holder(s)) — the queue depth a 425 answer
     *  reports so the sender can show an honest "queued behind N" instead of
     *  an opaque zero-progress wait. */
    fun receivingQueueDepth(): Int = receivingInFlightIds.size

    /**
     * Download-side twin of [acquireReceivingSlot]. [key] is whatever
     * identity the caller has at queue time — usually the PENDING transfer
     * id when the client pre-registered the download (so a host Cancel of
     * that exact entry aborts this wait within one poll tick), or a fresh
     * synthetic key when it didn't. Once the real transfer id is known,
     * [moveDownloadSlot] transfers the hold onto it so completion/cancel
     * bookkeeping all keys off one identity. Returns false when the wait
     * was aborted (caller must NOT stream, and must NOT release).
     *
     * Pass-10 (queue-order fix): admission is strictly ordered — the waiter
     * whose transfer row has the oldest startedAt (the exact order the
     * Transfers tab shows) gets the next free permit, with unregistered
     * requests (no PENDING row ⇒ [key] unknown here) falling behind them
     * on plain arrival order. Pass-19: the wait deadline is a SLIDING
     * 60-second window (refreshed while the gate is streaming or this row
     * is host-paused, hard-capped at 10 minutes) and the cancel-abort
     * behavior of the old semaphore gate is unchanged.
     */
    fun acquireDownloadSlot(key: String): Boolean {
        val waitStartMs = System.currentTimeMillis()
        // ── Pass-19 (large batches / big files) ────────────────────────────────
        // The old FIXED 60-second wait deadline was the second stacked root
        // cause of "large transfers stop partway": a browser fires its whole
        // batch's byte-requests nearly at once, so a request for file N+5
        // parks in this queue while file N streams — and the moment ONE
        // file takes longer than 60 s (a 2 GB video, a slow link), every
        // parked waiter expired, the browser's download manager marked its
        // items failed, and the batch died mid-way with the sender showing
        // Failed rows (the reported 20-file case). The deadline is now a
        // SLIDING window: while any download is genuinely streaming (bytes
        // moved within the last 15 s) — or while THIS row is deliberately
        // paused by the host and is supposed to wait — the window keeps
        // refreshing, so a healthy batch of any size waits out its queue
        // turn however long the queue is. A truly wedged gate (no progress
        // anywhere, no deliberate pause) still expires its waiters after
        // the honest 60 s, and a 10-minute absolute cap bounds even the
        // most pathological case, mirroring the auto-resume windows.
        // Pass-20: `var` — the absolute deadline now SLIDES with the same
        // gate-liveness signal that refreshes the 60 s window (see the
        // refresh site inside the wait loop), so a healthy batch of any
        // size can legitimately park its tail waiters for as long as the
        // gate keeps moving bytes.
        var waitAbsoluteDeadlineMs = waitStartMs + 10 * 60_000L
        var waitDeadlineMs = waitStartMs + 60_000L
        // Ordering priority: the PENDING row's startedAt when the key IS a
        // transfer id (every pre-registered download), else Long.MAX_VALUE
        // so hand-built/zip requests queue behind all registered rows on
        // their (unique, monotonic) arrival sequence alone.
        val startedAt = transfers[key]?.startedAt ?: java.lang.Long.MAX_VALUE
        val seq = downloadWaitSeq.incrementAndGet()
        val waiter = DownloadWaiter(startedAt, seq, key)
        downloadGateLock.lock()
        try {
            downloadWaiters.add(waiter)
            try {
                while (true) {
                    // Pass-17: the AUTHORITATIVE cancel check — the CANCELLED
                    // row auto-dismisses 400 ms after the host's tap; a queued
                    // byte-request must still abort within one poll tick, not
                    // hold a waiter's connection open for its full 60 s
                    // deadline. The endpoint's post-gate re-check is the second
                    // line of defense below it.
                    if (isCancelAuthoritative(key)) {
                        return false
                    }
                    // resetGates() clears the wait set under a dying
                    // server instance — a waiter that vanished from the set
                    // must abort, never keep waiting for a turn that will
                    // never come.
                    if (!downloadWaiters.contains(waiter)) return false
                    // Pass-18: a HOST-PRIORITIZED waiter (an explicit per-item
                    // Resume on exactly this Waiting row) is the head REGARDLESS
                    // of the ordinary (startedAt, seq) order — the item the
                    // host picked always starts next (see the class-level
                    // Pass-18 note above).
                    val head = downloadWaiters.firstOrNull { it.key in prioritized } ?: downloadWaiters.firstOrNull()
                    if (head === waiter && downloadGatePermits > 0) {
                        downloadGatePermits--
                        bumpHolderCount(key, 1)
                        val waitedMs = System.currentTimeMillis() - waitStartMs
                        return true
                    }
                    // ── Pass-18 (resume-one-of-all-paused): the prioritized ──
                    // head would wait only because every permit is held — and
                    // a holder that the host PAUSED (past the grace window) is
                    // by definition not moving any bytes. Hand its permit to
                    // the explicitly resumed item instead of parking it behind
                    // a stream that may stay paused for minutes. The paused
                    // holder keeps its (stalled) connection and resumes on it
                    // later; its own release path is a guaranteed no-op after
                    // the force-release, so the permit arithmetic can never
                    // double-count.
                    if (head === waiter && key in prioritized && preemptPausedDownloadHolders() > 0) {
                        continue   // a permit may be free now — re-check the head/grant conditions
                    }
                    // Pass-19: refresh the sliding wait window while the gate
                    // is genuinely alive — any download streaming, or this own
                    // row deliberately paused by the host (its turn is
                    // supposed to wait out the pause).
                    if (anyDownloadStreaming() || transfers[key]?.pauseRequested == true) {
                        waitDeadlineMs = System.currentTimeMillis() + 60_000L
                        // ── Pass-20 (the deterministic ~101-of-340 batch kill) ──
                        // The ABSOLUTE deadline must slide with it. Pass-19
                        // made the 60 s window sliding but kept the hard
                        // 10-minute wall — and a browser fires its whole
                        // batch's byte-requests nearly at once (all ~340 of
                        // them inside ~10 s), so every request beyond the
                        // first few PARKS in this queue at essentially the
                        // same moment and would hit the SAME wall at the
                        // same moment: with the gate serializing at one or
                        // two permits, ~6 s per file meant ~100 files
                        // streamed inside 10 minutes and then every
                        // remaining waiter (~239 of them) was force-released
                        // IN ONE CLUSTER — each answered "Cancelled by host"
                        // (a lie: nothing was cancelled), its PENDING row
                        // left to the 5-minute stale sweep, and the sender's
                        // Active tab showed the whole remaining batch as
                        // Failed. Exactly the reported "received 101 of 340,
                        // remaining 239 Failed", twice, at ~100 ± 1 — the
                        // wall is the only mechanism in the pipeline that
                        // deterministically kills everything still waiting
                        // at one fixed moment. While the gate keeps
                        // streaming (bytes within 15 s anywhere) admission
                        // is FIFO-guaranteed — every waiter eventually
                        // becomes the head and every permit cycles — so a
                        // healthy 400-, 1,000-file batch is a LEGITIMATE
                        // arbitrarily-long park per connection, and the wall
                        // must move with the same liveness signal the 60 s
                        // window uses. The wall's real job — bounding a
                        // WEDGED gate — is already done better by the 60 s
                        // sliding window: a gate with no progress anywhere
                        // stops refreshing it and the waiter honestly
                        // expires in 60 s. 10 minutes since the gate last
                        // moved a byte is the true pathology bound.
                        waitAbsoluteDeadlineMs = System.currentTimeMillis() + 10 * 60_000L
                    }
                    if (System.currentTimeMillis() >= waitAbsoluteDeadlineMs) {
                        return false
                    }
                    val remains = waitDeadlineMs - System.currentTimeMillis()
                    if (remains <= 0) {
                        return false
                    }
                    try {
                        downloadGateCond.await(remains, java.util.concurrent.TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return false
                    }
                }
            } finally {
                downloadWaiters.remove(waiter)
                // A waiter leaving the queue (granted, cancelled, timed out)
                // can only ever un-block a LATER waiter — wake everyone so
                // the new head takes its turn immediately.
                downloadGateCond.signalAll()
            }
        } finally { downloadGateLock.unlock() }
    }

    /** Pass-19: true while some DOWNLOAD transfer is actively moving bytes
     *  (progress sampled within the last 15 s) — the signal the download
     *  gate's waiters use to keep their sliding wait window refreshing. A
     *  gate whose streamers are all wedged (no progress anywhere) does NOT
     *  count, so waiters still expire on the honest 60-second window. */
    private fun anyDownloadStreaming(): Boolean {
        val now = System.currentTimeMillis()
        return transfers.values.any {
            it.direction == Direction.DOWNLOAD && it.status == Status.ACTIVE &&
                now - it.lastSampleAtMs <= 15_000L
        }
    }

    /** Moves a download-slot hold from [fromKey] (the queue-time key) onto
     *  the real transfer id [toKey] — atomic re-keying of the holder map
     *  entry, permit untouched. A no-op for equal keys or an already-gone
     *  from-entry (e.g. the watchdog force-released it in the microsecond
     *  window between grant and move — in which case the permit was
     *  legitimately freed and the later release under toKey simply no-ops,
     *  leaving the gate exactly balanced). */
    fun moveDownloadSlot(fromKey: String, toKey: String) {
        if (fromKey == toKey) return
        val n = downloadSlotHolders.remove(fromKey) ?: return
        bumpHolderCount(toKey, n)
    }

    /** Releases exactly ONE permit held under [id] (see the multi-connection
     *  holds note above). Exactly-once per hold: a no-op when [id] holds
     *  nothing (already force-released, moved, or reset). */
    fun releaseDownloadSlot(id: String) {
        if (dropOneHolderCount(id)) {
            downloadGateLock.lock()
            try { downloadGatePermits++; downloadGateCond.signalAll() } finally { downloadGateLock.unlock() }
        }
    }

    // ── Holder-count helpers (Phase 2 of 10) ─────────────────────────────────
    // API-21-safe replacements for Map.merge/Map.compute (Java 8 default
    // methods — see the note up at the acquire site). Both run under the
    // manager monitor, the same discipline every other check-then-act
    // sequence in this file already uses, so a concurrent acquire/release/
    // move/watchdog pair can never interleave inside a count update.
    @Synchronized private fun bumpHolderCount(key: String, n: Int) {
        val v = downloadSlotHolders[key] ?: 0
        downloadSlotHolders[key] = v + n
    }

    /** Decrements [id]'s hold count by one, removing the entry at zero;
     *  returns true iff a permit is actually held and must be released. */
    @Synchronized private fun dropOneHolderCount(id: String): Boolean {
        val v = downloadSlotHolders[id] ?: return false
        if (v <= 0) { downloadSlotHolders.remove(id); return false }
        if (v == 1) downloadSlotHolders.remove(id) else downloadSlotHolders[id] = v - 1
        return true
    }

    /** Watchdog/server-stop path: frees EVERY permit still held under [id]
     *  (a dead multi-connection transfer can hold several) and returns how
     *  many were freed. */
    private fun forceReleaseDownloadSlot(id: String): Int {
        val n = downloadSlotHolders.remove(id) ?: 0
        if (n > 0) {
            downloadGateLock.lock()
            try { downloadGatePermits += n; downloadGateCond.signalAll() } finally { downloadGateLock.unlock() }
        }
        return n
    }

    // ── Pass-18 (resume-one-of-all-paused): paused-holder preemption ─────────
    /** Force-releases the DOWNLOAD permits of every holder that is ACTIVE,
     *  paused, and has been paused longer than [PAUSE_YIELD_GRACE_MS] —
     *  called ONLY from a host-prioritized waiter's gate loop (an explicit
     *  per-item Resume), which by definition has a live request waiting for
     *  exactly the permit such a holder is parking on. Returns how many
     *  permits were freed. The paused holders' connections are deliberately
     *  NOT touched: each keeps its open (stalled) socket and resumes in
     *  place on it when its own user resumes it; the exactly-once release
     *  discipline (holder-entry removal) guarantees their eventual release
     *  call is a no-op, so the permit count can never double-count. */
    private fun preemptPausedDownloadHolders(): Int {
        val now = System.currentTimeMillis()
        var freed = 0
        for (id in downloadSlotHolders.keys.toList()) {
            val t = transfers[id] ?: continue
            if (t.status != Status.ACTIVE || !t.pauseRequested) continue
            if (t.pauseStartedAtMs == 0L || now - t.pauseStartedAtMs < PAUSE_YIELD_GRACE_MS) continue
            val n = forceReleaseDownloadSlot(id)
            if (n > 0) {
                freed += n
            }
        }
        return freed
    }

    /** Receiving-gate twin of [preemptPausedDownloadHolders]: force-releases
     *  the receiving permit of an ACTIVE holder paused past the grace
     *  window, so an explicitly resumed queued upload's POST can start.
     *  Same guarantees: the paused upload's connection is untouched and its
     *  later releaseReceivingSlot is a no-op after the holder entry is
     *  removed here. */
    private fun preemptPausedReceivingHolders(): Int {
        val now = System.currentTimeMillis()
        var freed = 0
        for (id in receivingSlotHolders.toList()) {
            val t = transfers[id] ?: continue
            if (t.status != Status.ACTIVE || !t.pauseRequested) continue
            if (t.pauseStartedAtMs == 0L || now - t.pauseStartedAtMs < PAUSE_YIELD_GRACE_MS) continue
            if (receivingSlotHolders.remove(id)) {
                receivingGate.release()
                freed++
            }
        }
        return freed
    }

    /** Pauses every in-flight transfer — ACTIVE (stalls it immediately) and
     *  PENDING alike (it starts pre-paused the moment its turn comes up). */
    fun pauseAllActive() {
        val now = System.currentTimeMillis()
        transfers.values.filter { it.status == Status.ACTIVE || it.status == Status.PENDING }
            .forEach {
                // ── GOAT-PASS 15 (§3.4): a streamed ZIP-batch archive is never
                // pausable — a bulk Pause All must not shove it into an
                // unsupported paused state (aborting its stream mid-archive
                // leaves a truncated zip the receiver must restart from byte
                // 0). See Transfer.isZipStream.
                if (it.isZipStream) return@forEach
                it.pauseRequested = true
                if (it.pauseStartedAtMs == 0L) it.pauseStartedAtMs = now   // Pass-18
            }
    }

    fun resumeAllActive() {
        transfers.values.forEach { it.pauseRequested = false; it.pauseStartedAtMs = 0L }
    }

    /** Cancels every in-flight transfer (PENDING and ACTIVE alike). */
    fun cancelAllActive() {
        transfers.values.filter { it.status == Status.PENDING || it.status == Status.ACTIVE }
            .forEach { requestCancel(it.id) }
    }

    /** Cancels every in-flight transfer belonging to [peerIp] — used when
     *  the host kicks a device: "disconnect that device" must also stop
     *  its traffic, not merely refuse its FUTURE requests (the pre-Phase-1
     *  kick left the peer's already-streaming transfers running to
     *  completion). Ownership matches on the same peerIp identity every
     *  transfer already carries. */
    fun cancelAllFor(peerIp: String) {
        transfers.values.filter { it.peerIp == peerIp && (it.status == Status.PENDING || it.status == Status.ACTIVE) }
            .forEach { requestCancel(it.id) }
    }

    // ── True-cancellation guard ────────────────────────────────────────────
    // A cancelled ACTIVE transfer's connection is torn down (see
    // ProgressTrackingInputStream, which throws rather than returning EOF
    // specifically so the client sees a hard connection failure, not a
    // silent stall) — but browsers and this app's own download/upload
    // managers both auto-retry a small number of times after a network
    // error. Without a way to say "no, this one was deliberately cancelled,
    // don't just quietly re-serve it," an automatic retry would silently
    // undo a host's Cancel action. These checks let the download endpoints
    // refuse a retry — but only for a short window after the cancel, long
    // enough to absorb the handful of automatic retries a client fires off
    // right after the connection drops. A cancelled record staying visible
    // in the Completed tab (until dismissed, or aged out of the bounded
    // history) must never, by itself, keep blocking a fresh attempt well
    // after that: a completed/cancelled/failed transfer is history, not a
    // standing block, and the host resending the file — or the person
    // just trying again a minute later — always has to go through. That
    // distinction (a brief anti-retry window vs. an indefinite block tied
    // to how long the row happens to stay in history) is exactly what made
    // a legitimate resend fail until the host manually cleared history.
    private const val CANCEL_TOMBSTONE_WINDOW_MS = 120_000L
    // ── Pass-16: 15 s → 2 min ────────────────────────────────────────────
    // "The download continues in the browser's Downloads section even after
    // the host clicks Cancel": the first half of that report was the stream
    // race ProgressTrackingInputStream now closes with the durable latch;
    // this is the second half — a browser's download manager retries a
    // failed download on its OWN schedule (Chrome can sit seconds before a
    // re-request, and a manual "Resume" tap can come any time), and a 15 s
    // refusal window simply expired before some of those retries arrived,
    // letting the cancelled download quietly finish. Two minutes covers
    // every automatic retry cadence and a normal pause-then-Resume, while
    // never blocking a genuine re-send: a deliberate re-send from the host
    // creates a NEW uniquely-named queue item, and a browser deliberately
    // re-requesting the SAME file much later (a new download decision)
    // falls well outside the window.

    fun wasCancelledForQuickShare(qsItemName: String, peerIp: String): Boolean {
        val now = System.currentTimeMillis()
        if (transfers.values.any {
            it.status == Status.CANCELLED && it.source == Source.QUICK_SHARE &&
                it.qsItemName == qsItemName && it.peerIp == peerIp &&
                now - it.finishedAt < CANCEL_TOMBSTONE_WINDOW_MS
        }) return true
        // Pass-13: the durable tombstone outlives the row's own 400 ms
        // auto-dismissal — this is the half that made cancelled downloads
        // resurrect as Active-tab ghost rows via a late re-request.
        return wasCancelledDownloadTombstoned("qs", qsItemName, peerIp)
    }

    fun wasCancelledForPlainDownload(fileName: String, peerIp: String): Boolean {
        val now = System.currentTimeMillis()
        if (transfers.values.any {
            it.status == Status.CANCELLED && it.source == Source.PLAIN &&
                it.direction == Direction.DOWNLOAD && it.fileName == fileName && it.peerIp == peerIp &&
                now - it.finishedAt < CANCEL_TOMBSTONE_WINDOW_MS
        }) return true
        // Pass-13: same durable tombstone as the Quick-Share guard above.
        return wasCancelledDownloadTombstoned("pd", fileName, peerIp)
    }

    fun wasCancelledForUpload(requestId: String): Boolean {
        val now = System.currentTimeMillis()
        if (transfers.values.any {
            it.status == Status.CANCELLED && it.uploadRequestId == requestId &&
                now - it.finishedAt < CANCEL_TOMBSTONE_WINDOW_MS
        }) return true
        // Pass-11: the durable tombstone outlives the row's 400 ms
        // auto-dismissal for the whole 10-minute window the sender's
        // auto-resume loop may still be re-POSTing within — this is the
        // half that made cancelled transfers resurrect as ghosts.
        val at = cancelledUploadRequests[requestId] ?: return false
        return now - at < CANCELLED_UPLOAD_TOMBSTONE_MS
    }

    /** Pass-11: tombstone-only O(1) companion to [wasCancelledForUpload] for
     *  hot per-chunk loops (writeRawStream), where the live-row half of the
     * cancel state is already covered by a direct [isCancelled] check on the
     * row id. A cancelled row auto-dismisses after only 400 ms — this check
     * keeps a deliberate cancel authoritative for the whole tombstone window
     * even after the row has left the map, so a mid-stream cancel can never
     * be outrun by its own row's dismissal. */
    fun wasCancelledForUploadDurable(requestId: String?): Boolean {
        if (requestId == null) return false
        val at = cancelledUploadRequests[requestId] ?: return false
        return System.currentTimeMillis() - at < CANCELLED_UPLOAD_TOMBSTONE_MS
    }

    // ── "Paused by host" client-side indication ───────────────────────────
    // Pausing is otherwise a purely server-side mechanism (the client just
    // sees its transfer stall) — these expose which specific in-flight
    // transfers for a given client are currently paused, so /api/qstate can
    // tell that client's WebUI to show "Paused by Host" instead of leaving
    // the person guessing whether it's just a slow connection.
    fun pausedDownloadNamesFor(peerIp: String): List<String> =
        transfers.values.filter {
            it.direction == Direction.DOWNLOAD && it.peerIp == peerIp && it.pauseRequested &&
                (it.status == Status.ACTIVE || it.status == Status.PENDING)
        }.map { it.fileName }.distinct()

    /** Phase 1 (tid-keyed web-client indication): the transfer IDS of this
     *  client's paused downloads, not their filenames — two same-named
     *  transfers (a retry racing a tombstone, a plain download racing a
     *  Quick-Share item) must never both light up "Paused by Host" because
     *  they share a name, and the client's drawer now keys rows by tid. */
    fun pausedDownloadTidsFor(peerIp: String): List<String> =
        transfers.values.filter {
            it.direction == Direction.DOWNLOAD && it.peerIp == peerIp && it.pauseRequested &&
                (it.status == Status.ACTIVE || it.status == Status.PENDING)
        }.map { it.id }.distinct()

    fun pausedUploadRequestIdsFor(peerIp: String): List<String> =
        transfers.values.filter {
            it.direction == Direction.UPLOAD && it.peerIp == peerIp && it.pauseRequested &&
                it.uploadRequestId != null && (it.status == Status.ACTIVE || it.status == Status.PENDING)
        }.mapNotNull { it.uploadRequestId }.distinct()

    // ── Host-directed "start this one next" indication ─────────────────────
    // Same wire-format idea as the paused indicators above: tells this
    // specific client's queue manager which of ITS OWN still-Waiting items
    // the host explicitly picked via a per-item Resume tap, so that pick
    // always wins the next free concurrency slot instead of whatever
    // happens to be first in that client's local FIFO order. Filtered to
    // PENDING + not-paused only: a re-paused item shouldn't jump anything
    // until it's explicitly resumed again, and a claimed/cancelled one has
    // already had its id removed from [prioritized] at the call sites above.
    fun priorityDownloadNamesFor(peerIp: String): List<String> =
        transfers.values.filter {
            it.direction == Direction.DOWNLOAD && it.peerIp == peerIp &&
                it.status == Status.PENDING && !it.pauseRequested && it.id in prioritized
        }.map { it.fileName }.distinct()

    /** Phase 1 (tid-keyed): transfer IDS of this client's host-picked
     *  next-up downloads — see pausedDownloadTidsFor for why ids, not names. */
    fun priorityDownloadTidsFor(peerIp: String): List<String> =
        transfers.values.filter {
            it.direction == Direction.DOWNLOAD && it.peerIp == peerIp &&
                it.status == Status.PENDING && !it.pauseRequested && it.id in prioritized
        }.map { it.id }.distinct()

    fun priorityUploadRequestIdsFor(peerIp: String): List<String> =
        transfers.values.filter {
            it.direction == Direction.UPLOAD && it.peerIp == peerIp &&
                it.status == Status.PENDING && !it.pauseRequested &&
                it.uploadRequestId != null && it.id in prioritized
        }.mapNotNull { it.uploadRequestId }.distinct()

    // ── "Cancelled by host" client-side indication ────────────────────────
    // Same idea as the paused-state helpers above: lets /api/qstate tell a
    // client proactively "this specific transfer of yours was just
    // cancelled," so its own DLQ/upload-drawer row can be cleared right
    // away — instead of relying purely on that row's own network request
    // happening to error out on its own timing, which is what let the
    // client's transfer popup linger open after a host Cancel All. Safe to
    // report the same cancelled transfer on every poll tick: the client
    // side is idempotent (a no-op once that row is already gone/clearing).
    fun cancelledDownloadNamesFor(peerIp: String): List<String> =
        transfers.values.filter {
            it.direction == Direction.DOWNLOAD && it.peerIp == peerIp && it.status == Status.CANCELLED
        }.map { it.fileName }.distinct()

    fun cancelledUploadRequestIdsFor(peerIp: String): List<String> =
        transfers.values.filter {
            it.direction == Direction.UPLOAD && it.peerIp == peerIp && it.status == Status.CANCELLED &&
                it.uploadRequestId != null
        }.mapNotNull { it.uploadRequestId }.distinct()

    /** Removes one entry from view — used by the Retry/Dismiss action once a
     *  failed/cancelled entry has been acknowledged, and has no further use. */
    fun dismiss(id: String) {
        val t = transfers[id]
        pendingRemovals.remove(id)?.cancel()   // v1.0 resume: no dangling task once actually dismissed
        resumableRowIds.remove(id)             // Pass-14: a dismissed row is never resumable anymore
        prioritized.remove(id); transfers.remove(id)
        touch(t?.peerIp)
    }

    fun dismissAllFinished() {
        transfers.values.filter { it.status != Status.PENDING && it.status != Status.ACTIVE }
            .forEach { transfers.remove(it.id); touch(it.peerIp) }
    }

    // ── GOAT-PASS 3 (§7 — the floating "Clear Failed" action's engine half) ──
    /** How many FAILED rows in the live map are ELIGIBLE for the bulk
     *  clear: a genuine failure (not [Transfer.interrupted] — those are the
     *  "Waiting for reconnection"/"Auto-Resume on Reconnection" rows whose
     *  partials and resume windows must survive, and not the kept-for-resume
     *  cards either). The Active tab shows the pill only when this count is
     *  high enough for a bulk action to be worth its footprint. */
    fun eligibleFailedCount(): Int =
        transfers.values.count { it.status == Status.FAILED && !it.interrupted && it.id !in resumableRowIds }

    /** ── FINAL PASS 3 (§8): read-only view of the kept-for-resume id set ──
     *  for the Active tab's failed-pill Resend filter (the same exclusion
     *  eligibleFailedCount applies — interrupted/kept-for-resume rows are
     *  somebody's reconnect story, never bulk-resent). */
    fun resumableRowIdsPublic(): Set<String> = java.util.Collections.unmodifiableSet(HashSet(resumableRowIds))

    /** GOAT-PASS 9 (§1 — Clear Partial Received Items): the RECEIVER's
     *  "Auto-Resume on Reconnection" rows — incoming (UPLOAD-direction, this
     *  device receiving) transfers interrupted mid-file that are waiting for
     *  the connection to return: FAILED+interrupted kept-for-resume rows AND
     *  ACTIVE rows currently inside the reconnect loop. Read-only snapshot;
     *  the caller (the Active tab's bulk menu) owns the row resolution
     *  (requestCancel — which also tombstones each upload request id so the
     *  sender's next checkpoint probe reads "cancelled" instead of
     *  re-sending), the staging-partial deletion, the ResumableReceiveStore
     *  cleanup and the discard receipts. */
    fun autoResumeReceiverRows(): List<Transfer> =
        transfers.values.asSequence()
            .filter { it.direction == Direction.UPLOAD }
            .filter {
                (it.status == Status.FAILED && it.interrupted) ||
                    (it.status == Status.ACTIVE && it.reconnecting)
            }
            .toList()

    /** Dismisses every eligible FAILED row (see [eligibleFailedCount]) from
     *  the live map — the engine half of the Active tab's floating "Clear
     *  Failed" pill. Strictly view-level: no file on device storage is ever
     *  touched (failed partials belong to their own cleanup paths), no
     *  active/waiting/paused transfer is affected, no history entry and no
     *  interrupted/kept-for-resume row is removed, and repeated calls are
     *  naturally idempotent (the second call finds nothing left). Returns
     *  how many rows were cleared. */
    fun dismissAllFailedEligible(): Int {
        val doomed = transfers.values.filter {
            it.status == Status.FAILED && !it.interrupted && it.id !in resumableRowIds
        }
        if (doomed.isEmpty()) return 0
        for (t in doomed) {
            transfers.remove(t.id)
            terminalMemory.remove(t.id)
            touch(t.peerIp)
        }
        return doomed.size
    }

    /** Clears only one direction-group of the permanent (successful-only)
     *  history — e.g. tapping "Clear History" while looking at Received
     *  shouldn't also silently wipe Sent out from under the other tab.
     *  Recently-failed/cancelled rows (shown in activeSnapshot(), never in
     *  here — see its doc comment) are untouched by this either way. */
    fun clearCompletedGroup(received: Boolean) {
        val toRemove = if (received) receivedSnapshot() else sentSnapshot()
        toRemove.forEach { transfers.remove(it.id); touch(it.peerIp) }
    }

    fun get(id: String): Transfer? = transfers[id]

    /** Every transfer, most relevant first: in-flight (PENDING/ACTIVE) ahead of
     *  finished, oldest-queued first within each group.
     *
     *  GOAT (transfer stability pass): the within-group tiebreaker used to be
     *  `.thenByDescending { it.startedAt }` (newest first). startedAt never
     *  changes for a given Transfer once created, so that sounds stable —
     *  but a transfer that's been PENDING for a while and a transfer that
     *  was just created are both in the SAME "in-flight" group together, and
     *  the brand-new one (larger startedAt) would rank ABOVE one that's been
     *  waiting/running longer, reshuffling anything already using this list
     *  every time something new starts. Ascending is the stable choice: the
     *  thing that's been in-flight longest stays on top, and new arrivals
     *  are appended after it, never inserted above it. */
    // ── Phase 4 (v3 §5 item 5): server-side download-gate support ─────────
    /** True while THIS client already has an ACTIVE download entry for this
     *  exact filename — what lets a browser's second connection for the same
     *  transfer (a parallel Range fetch, or a download manager's duplicate
     *  follow-up GET) attach to the existing entry without consuming a second
     *  concurrency slot. */
    fun hasActiveDownloadFor(fileName: String, peerIp: String): Boolean =
        transfers.values.any {
            it.direction == Direction.DOWNLOAD && it.peerIp == peerIp &&
                it.fileName == fileName && it.status == Status.ACTIVE
        }

    /** The id of this client's still-live (PENDING or ACTIVE) download entry
     *  for this exact filename — the web client's own Cancel button targets
     *  exactly this entry: the browser owns the download connection now, so
     *  the only honest cancel is server-side (requestCancel + the stream
     *  closing under the browser's read). LEGACY lookup kept only for the
     *  name-based fallback path in apiClientTransferCancel (a stale cached
     *  WebUI page from before tid-keyed cancels); new pages cancel by the
     *  transfer id itself — see clientTransfersJson's "i" field. */
    fun findLiveDownloadId(fileName: String, peerIp: String): String? =
        transfers.values.firstOrNull {
            it.direction == Direction.DOWNLOAD && it.peerIp == peerIp &&
                it.fileName == fileName && (it.status == Status.ACTIVE || it.status == Status.PENDING)
        }?.id

    /** PENDING-only lookup for this client's pre-registered plain download —
     *  what apiDownload/apiDownloadApk use as their download-slot WAIT KEY:
     *  waiting under the entry's own id means a host Cancel of that exact
     *  entry aborts the queued request within one poll tick (see
     *  acquireDownloadSlot's cancel check). Null when nothing is
     *  pre-registered (a request that skipped /api/download/register). */
    fun findPendingPlainDownloadId(fileName: String, peerIp: String): String? =
        transfers.values.firstOrNull {
            it.status == Status.PENDING && it.source == Source.PLAIN &&
                it.direction == Direction.DOWNLOAD && it.fileName == fileName && it.peerIp == peerIp
        }?.id

    /** Pass-5 (R5): stamps that THIS pending download's real byte-request
     *  has arrived (see Transfer.requestedAtMs). Called by the download
     *  endpoints BEFORE the concurrency-gate wait, so a request parked in
     *  the queue counts as live work for its peer and the never-requested
     *  sweep never touches it. */
    fun markPendingRequested(id: String) {
        transfers[id]?.let { it.requestedAtMs = System.currentTimeMillis() }
    }

    /** Pass-23: the byte-request that carried this PENDING row into the
     *  download-gate wait was REFUSED (its sliding window expired, or a
     *  queued cancel aborted it) — the endpoint answered the client and the
     *  row stays PENDING (the browser's own retry may re-fire it), but the
     *  "requested" stamp must NOT survive: a stamped row counts as live
     *  work for the never-requested sweep forever, wedging the whole tail
     *  of the batch behind a request that is no longer coming. Clearing the
     *  stamp lets the honest 25s never-requested clock own the row again. */
    fun clearPendingRequested(id: String) {
        val t = transfers[id] ?: return
        if (t.status == Status.PENDING && t.requestedAtMs != 0L) {
            t.requestedAtMs = 0L
            touch(t.peerIp)
        }
    }

    /** PENDING-only lookup for this client's accepted Quick-Share item — the
     *  apiQSDl wait-key twin of [findPendingPlainDownloadId] (the PENDING
     *  entry was created by the /api/quickshare/accept call moments before
     *  the real byte-request). */
    fun findPendingQuickShareId(qsItemName: String, peerIp: String): String? =
        transfers.values.firstOrNull {
            it.status == Status.PENDING && it.source == Source.QUICK_SHARE &&
                it.qsItemName == qsItemName && it.peerIp == peerIp
        }?.id

    /** The id of this client's still-live (PENDING or ACTIVE) DOWNLOAD entry
     *  matching a server transfer id exactly — the tid-based ownership check
     *  behind /api/client-transfer/cancel's tid path: only this client's own
     *  live download may be cancelled through it, never another client's and
     *  never a terminal (already finished) one. */
    fun findLiveDownloadByIdAndPeer(tid: String, peerIp: String): String? {
        val t = transfers[tid] ?: return null
        if (t.direction != Direction.DOWNLOAD || t.peerIp != peerIp) return null
        if (t.status != Status.ACTIVE && t.status != Status.PENDING) return null
        return t.id
    }

    // Phase 10 (§speed): the sorted snapshot is now cached against the
    // global mutation epoch — clientStateJson runs once per dirty SSE tick
    // PER CLIENT, and every rebuild used to re-sort the whole transfer map.
    // With the cache, N clients in one tick share one sort; any mutation
    // anywhere (globalVersion moves on every touch) invalidates it. Benign
    // race on fill: two threads may sort simultaneously; both are
    // consistent snapshots of the same map.
    @Volatile private var snapshotCache: List<Transfer>? = null
    @Volatile private var snapshotCacheEpoch: Long = -1L
    fun snapshot(): List<Transfer> {
        val v = globalVersion.get()
        val cached = snapshotCache
        if (cached != null && snapshotCacheEpoch == v) return cached
        val fresh = transfers.values.sortedWith(
            compareByDescending<Transfer> { it.status == Status.PENDING || it.status == Status.ACTIVE }
                .thenBy { it.startedAt }
                // Pass-11 (queue-ordering root fix): the monotonic creation
                // sequence is the FINAL tiebreaker — startedAt collides at
                // millisecond resolution for every row of a multi-file batch
                // (they are minted in one tight loop), and without this the
                // stable sort silently fell back to ConcurrentHashMap hash
                // order: an arbitrary permutation of the sender's selection
                // order on both devices' Active tabs.
                .thenBy { it.seq }
        )
        snapshotCache = fresh
        snapshotCacheEpoch = v
        return fresh
    }

    /**
     * PENDING + ACTIVE (currently in flight) PLUS recently-terminal
     * FAILED/CANCELLED transfers, shown here only TEMPORARILY until the
     * user dismisses them (same ✕ action as always — see
     * TransfersFragment.buildActionButtons) — they never make it into
     * completedSnapshot()'s permanent history at all. This is what "these
     * events may remain available in temporary transfer views... but
     * should never appear in the permanent Completed History" means in
     * practice: a failure needs to be visible somewhere the moment it
     * happens, and the already-live, frequently-refreshing Active view is
     * that place, rather than inventing a third list for it.
     */
    /**
     * Genuinely in-flight transfers only (PENDING + ACTIVE) — deliberately
     * narrower than activeSnapshot(), which additionally includes
     * recently-failed/cancelled transfers so the Active TAB can show them
     * temporarily (see that function's own doc comment). Anywhere that asks
     * "is X actually happening right now" rather than "what should the
     * Active tab display" must use this one instead — e.g. the Nearby
     * Devices list's per-peer status text (DeviceAdapter, via
     * SendFragment.updateDevicesUI), which
     * previously read activeSnapshot() and kept showing "Sending 1 file"
     * for a transfer that had already failed or been cancelled, simply
     * because that broader set still contained it.
     */
    fun inFlightSnapshot(): List<Transfer> =
        transfers.values.filter { it.status == Status.PENDING || it.status == Status.ACTIVE }

    /**
     * GOAT (transfer stability pass — Active tab ordering): three fixed
     * buckets, ACTIVE / FAILED-or-CANCELLED / PENDING, each internally
     * sorted by ascending startedAt (oldest first) rather than the previous
     * descending order. Descending meant a transfer that had been ACTIVE
     * for a while got visually overtaken by one that started a moment ago
     * (larger startedAt sorts first), and — separately — the PENDING
     * section showed the NEXT file to actually be processed at the BOTTOM,
     * farthest from the Active section above it, which reads backwards for
     * a queue. Ascending fixes both: within ACTIVE, whichever transfer has
     * been running longest stays on top and newly-promoted ones append
     * below it, never cutting in front; within PENDING, the next one in
     * line sits closest to Active, exactly where "what's coming up next"
     * belongs. A transfer's position now only ever changes by moving
     * between buckets as its status genuinely changes — never by another
     * transfer merely existing — which is what makes this the same,
     * predictable order on native sender, native receiver, and (already
     * true today, since both are plain insertion-ordered id lists — see
     * _uploads/_upQueue and DLQ.items in index.html) web sender/receiver.
     */
    fun activeSnapshot(): List<Transfer> =
        transfers.values.filter {
            it.status == Status.PENDING || it.status == Status.ACTIVE ||
            it.status == Status.FAILED  || it.status == Status.CANCELLED ||
            // v1.0 (§decline-sync): DECLINED rides the Active list for its
            // short terminal flash exactly like CANCELLED does, then the same
            // auto-removal sweeps it — never lands in Completed history.
            it.status == Status.DECLINED
        }.sortedWith(
            compareByDescending<Transfer> { it.status == Status.ACTIVE }
                .thenByDescending { it.status == Status.FAILED || it.status == Status.CANCELLED || it.status == Status.DECLINED }
                .thenBy { it.startedAt }
                // Pass-11 (queue-ordering root fix): same-millisecond ties
                // resolve by creation sequence == the sender's selection /
                // manifest order — the Active tab now lists a batch in
                // EXACTLY the order the Incoming Files modal showed it and
                // the engine actually transfers it (runSession dispatches in
                // manifest order under the pre-acquired semaphore).
                .thenBy { it.seq }
        )

    /** Only genuinely successful transfers — the permanent Completed
     *  History. Failed/cancelled transfers are deliberately never stored
     *  here; see activeSnapshot()'s doc comment for where they live instead. */
    fun completedSnapshot(): List<Transfer> =
        transfers.values.filter { it.status == Status.COMPLETED }.sortedByDescending { it.finishedAt }

    /** Completed transfers where this device was the recipient — the
     *  Received tab. */
    fun receivedSnapshot(): List<Transfer> =
        completedSnapshot().filter { it.direction == Direction.UPLOAD }

    /** Completed transfers where this device was the source — the Sent
     *  tab. Covers both a browser client pulling a file (DOWNLOAD) and a
     *  native peer push (SEND); both mean "this device sent it". */
    fun sentSnapshot(): List<Transfer> =
        completedSnapshot().filter { it.direction == Direction.DOWNLOAD || it.direction == Direction.SEND }

    /** Count of transfers currently in flight — PENDING (waiting to start) or
     *  ACTIVE (bytes moving). This is the number shown on the FAB badge, the
     *  floating popup trigger, and the Transfers tab title: from the user's
     *  point of view, a "waiting" transfer is still something they're
     *  tracking, not something that's finished. */
    fun activeCount(): Int =
        transfers.values.count { it.status == Status.PENDING || it.status == Status.ACTIVE }

    /** Newly-finished-since-last-seen counts for the Received/Sent tab badges. */
    fun unseenReceived(): Int = unseenReceivedCount.get()
    fun unseenSent(): Int = unseenSentCount.get()

    /** ── SELECTION FINAL PASS 1 (#13.1): the History tab badge's own count ──
     *  — sent AND received completions since the History tab was last
     *  genuinely visited. Independent of the Received tab's counter by
     *  construction: each has its own increment sites' shared chokepoints
     *  but its own clear. */
    fun unseenHistory(): Int = unseenHistoryCount.get()

    /** Resets the Received/Sent badges to 0 — call when each tab is opened. */
    fun markReceivedSeen() { unseenReceivedCount.set(0) }
    fun markSentSeen() { unseenSentCount.set(0) }

    /** ── SELECTION FINAL PASS 1 (#13.1): clears the History badge — call when ──
     *  the History tab is genuinely visited (a user pill tap, never the
     *  screen's automatic landing). */
    fun markHistorySeen() { unseenHistoryCount.set(0) }

    /** Keeps memory bounded over a long-running server session — old finished
     *  entries beyond [MAX_FINISHED_HISTORY] are dropped, oldest-finished
     *  first. PENDING/ACTIVE transfers are never pruned, regardless of count.
     *  Pass-8 (§10 — large-batch resource bounds): folder GROUP records are
     *  pruned here too — one is minted per top-level folder per push, every
     *  send/receive session adds more, and nothing ever removed them. A
     *  group whose member transfers have all been dismissed/pruned is dead
     *  weight (a later Received-tab reconciliation re-creates its own via
     *  getOrCreateGroup), so it goes. Groups with any member still present
     *  are untouched. */
    private fun pruneFinishedHistory() {
        val finished = transfers.values.filter { it.status != Status.PENDING && it.status != Status.ACTIVE }
        if (finished.size <= MAX_FINISHED_HISTORY) {
            pruneEmptyGroups()
            return
        }
        finished.sortedBy { it.finishedAt }
            .take(finished.size - MAX_FINISHED_HISTORY)
            .forEach { transfers.remove(it.id); touch(it.peerIp) }
        pruneEmptyGroups()
    }

    /** §10 companion of [pruneFinishedHistory]: drops group records that no
     *  longer have a single member transfer. Bounded to a cheap pass and only
     *  ever runs from pruneFinishedHistory (i.e. on transfer creation), never
     *  on a hot path. */
    private fun pruneEmptyGroups() {
        if (groups.isEmpty()) return
        val liveGroupIds = java.util.HashSet<String>()
        for (t in transfers.values) t.groupId?.let { liveGroupIds.add(it) }
        val it2 = groups.entries.iterator()
        while (it2.hasNext()) if (it2.next().key !in liveGroupIds) it2.remove()
    }

    // Registration (see claimOrStartPendingPlainDownload / claimOrStartPending)
    // creates a PENDING entry before the client has necessarily committed to
    // actually requesting the file — its turn in a large batch may be
    // minutes away behind a low concurrency setting. If the client's own
    // queue never gets there (the tab closes, the selection is cleared, the
    // app backgrounds and the browser drops it), nothing would otherwise
    // ever move that entry out of PENDING: claim()/complete()/fail() are
    // only ever reached via a real connection's own lifecycle, which never
    // happens if the real request never arrives. Without this, that shows
    // up as a permanently-stuck "Waiting" row nothing can clear except a
    // manual host Cancel. Sized generously relative to how long a real
    // queue realistically takes to reach any one item, so this never fires
    // for a transfer that's genuinely still just waiting its turn.
    /** Pass-5 (R5): how long an ACCEPTED Quick-Share download may sit as a
     *  PENDING row whose byte-request never arrived before the sweep
     *  finalizes it FAILED. 25s is far beyond any honest dispatch (the
     *  client staggers <a> clicks by 30ms; download managers fire within a
     *  second or two) yet short enough that a dropped dispatch self-heals
     *  before a human gives up on it. */
    private const val NEVER_REQUESTED_TIMEOUT_MS = 25_000L
    /** PASS 22 §1: plain (media-tab) pre-registered downloads — never-
     *  requested liveness with a quiet window sized above the legacy iOS
     *  receive pump's inter-item turnaround (see the sweep's doc). Read
     *  PER SWEEP PASS (not captured at class init) so the test seam
     *  property is honored whenever it is set. */
    internal fun plainNeverRequestedTimeoutMs(): Long =
        System.getProperty("ldx.test.plainNeverRequestedMs")?.toLongOrNull() ?: 45_000L
    private const val STALE_PENDING_TIMEOUT_MS = 5 * 60_000L

    /** Sweeps PENDING entries old enough to be considered abandoned (see
     *  [STALE_PENDING_TIMEOUT_MS] above) and finalizes them as FAILED — the
     *  same visible-in-history treatment as any other transfer that didn't
     *  complete, rather than silently vanishing or lingering forever. Scoped
     *  to downloads only (Quick-Share and plain alike) — uploads have their
     *  own, more specific sweep already (LumiDropServer's
     *  sweepStalePendingUploads, tied to the upload-approval request's own
     *  shorter lifetime, which also cleans up its TransferManager shadow via
     *  [cancelPendingUpload]), so this deliberately leaves those alone
     *  rather than risk the two disagreeing about when something counts as
     *  abandoned. Cheap (a linear scan of what's normally a small live set)
     *  and safe to call often; hooked into both transfer creation and the
     *  WebUI's state poll so it runs promptly whenever the server has any
     *  activity at all, without needing a dedicated background thread for
     *  what's fundamentally a rare cleanup case. */
    @Synchronized
    fun expireStalePending() {
        val now = System.currentTimeMillis()
        // ── Pass-19 (large batches never stall at the ~100-file mark) ────────
        // The generic 5-minute staleness sweep used to fail every PENDING
        // download row that was older than 5 minutes from its CREATION —
        // with a single-permit gate serializing a 340-file batch, the rows
        // for files past ~#100 (5 min ÷ per-file time) were all older than
        // the window while they were STILL LEGITIMATELY QUEUED BEHIND their
        // siblings. They failed wholesale mid-batch (the deterministic
        // "stopped at exactly 100 completed, 240 remaining"), their QS
        // items then expired out of the server's in-flight cache, and the
        // browser's late byte-requests were answered "Not in queue" — the
        // batch could never finish no matter how healthy the network was.
        // The sweep now measures staleness against the PEER'S LAST DOWNLOAD
        // ACTIVITY, not the row's own creation: while anything from that
        // peer streamed, errored, or requested within the last 60 s, every
        // still-waiting row of that peer is doing exactly what a queue
        // does, and is exempt. Only when the whole batch has gone quiet for
        // the full window do leftovers honestly finalize FAILED.
        val peerLastDownloadActivity = HashMap<String, Long>()
        transfers.values.forEach { t ->
            if (t.direction != Direction.DOWNLOAD) return@forEach
            val act = maxOf(t.lastSampleAtMs, t.requestedAtMs, t.finishedAt)
            val cur = peerLastDownloadActivity[t.peerIp] ?: 0L
            if (act > cur) peerLastDownloadActivity[t.peerIp] = act
        }
        // Pass-5 (R5 — the "last file stuck on Waiting" fix): a Quick-Share
        // download the client ACCEPTED but whose byte-request never arrived
        // (the browser throttled/dropped the <a> dispatch — Chrome's
        // automatic-download guard is the classic) used to sit as "Waiting"
        // on BOTH screens for the generic five-minute staleness window. It
        // now finalizes FAILED after 25s — but ONLY when nothing else from
        // the same peer is actively downloading or parked-with-a-request:
        // a serial browser (legacy Safari's one-at-a-time receive pump)
        // legitimately fires later items' requests only after earlier ones
        // complete, and those siblings are exactly that live work. The
        // failed row re-arms the client's chip (failed rows release the
        // dispatched filter) and the client's bounded auto-retry re-fires
        // the dropped URL — the file still arrives, no user action.
        run {
            val peerHasLiveWork = HashSet<String>()
            transfers.values.forEach {
                if (it.direction == Direction.DOWNLOAD && it.status == Status.ACTIVE) peerHasLiveWork.add(it.peerIp)
                else if (it.direction == Direction.DOWNLOAD && it.status == Status.PENDING &&
                    (it.requestedAtMs > 0L || it.pauseRequested)) peerHasLiveWork.add(it.peerIp)
            }
            // ── Pass-23 (never-requested sweep: peer-quiet + oldest-only) ────────
            // Two rules that make this sweep safe to run on a fixed cadence (it
            // is now driven from the server's SSE ticker every few seconds, not
            // only opportunistically):
            //  (a) PEER-QUIET — the 25s clock now ALSO requires the peer's last
            //      download ACTIVITY (bytes flowed, a byte-request arrived, or
            //      a completion landed anywhere in its batch) to be older than
            //      the window. A batch mid-flight is never quiet — files
            //      complete every second or two — so the tail queued behind the
            //      page's one-at-a-time dispatch pump is doing exactly what a
            //      queue does and is exempt even in the gap between two files.
            //      The sweep only acts once the whole batch has gone silent for
            //      the full window, which is the honest meaning of "the browser
            //      dropped the dispatch".
            //  (b) OLDEST-ONLY — when it does act, it finalizes exactly ONE
            //      never-requested row per peer: the OLDEST, i.e. the one whose
            //      dispatch turn provably came and was dropped (every earlier
            //      row is already resolved). The previous shape could fail the
            //      ENTIRE remaining tail in one cluster the instant no sibling
            //      was streaming — a single swallowed <a download> dispatch then
            //      read as a whole-batch abandonment, which is precisely the
            //      variable-count "batch stops, most files unfinished" report.
            //      One row per pass keeps the self-heal targeted: the failed
            //      row's page-side bounded auto-retry re-fires it while every
            //      later row keeps waiting its turn behind the pump.
            val candidates = transfers.values.filter {
                it.status == Status.PENDING && it.direction == Direction.DOWNLOAD &&
                    it.source == Source.QUICK_SHARE && it.requestedAtMs == 0L && !it.pauseRequested &&
                    // ── FINAL PASS 4 (§9): a TEXT row never receives a byte ──
                    // request — its "delivery" is the receiver's consume (View/
                    // Copy/Accept-All), which completes it through
                    // completeQuickShareText. Without this exemption every
                    // pre-registered text row would be auto-failed ~25 s after
                    // queueing (the browser never fires /api/qs/dl for a text),
                    // which would reintroduce exactly the "waiting for the
                    // receiver must not auto-fail" defect this pass removes for
                    // native pushes. A text row's honest exits are: completed
                    // (consume), DECLINED (its decline paths), CANCELLED (queue
                    // teardown) — never the never-requested sweep.
                    !it.isTextMessage &&
                    it.peerIp !in peerHasLiveWork &&
                    (now - it.startedAt) > NEVER_REQUESTED_TIMEOUT_MS &&
                    (now - (peerLastDownloadActivity[it.peerIp] ?: 0L)) > NEVER_REQUESTED_TIMEOUT_MS
            }.groupBy { it.peerIp }
            for ((_, rows) in candidates) {
                val oldest = rows.minByOrNull { it.startedAt } ?: continue
                oldest.status = Status.FAILED
                oldest.finishedAt = now
                prioritized.remove(oldest.id)
                scheduleAutoRemoval(oldest.id, FAILED_DOWNLOAD_LINGER_MS)
                touch(oldest.peerIp)
            }
            // ── PASS 22 (§1.4 — plain-download twin of the never-requested ──
            // sweep): a PLAIN row is created ONLY by /api/download/register,
            // which the page fires in the same breath as its <a download>
            // dispatches (dlSelected/dlFile/dlApk) — there is NO undecided-
            // offer state for plain downloads (the QS exemption's whole
            // reason), so a never-requested PLAIN row is ALWAYS a dropped
            // dispatch. The one deliberate exception is the legacy iOS
            // receive pump: it registers a whole selection up front but
            // dispatches one iframe at a time, so its queued tail is
            // legitimately unrequested while earlier items stream — and the
            // peer-activity stamp those streams keep refreshing exempts it.
            // The quiet window (45s, vs the QS 25s) is sized above that
            // pump's every inter-item gap with margin; total peer silence
            // for 45s means the batch is genuinely gone. rq is deliberately
            // NOT consulted: a row whose request ARRIVED but died parked at
            // the gate (browser dropped the stalled connection) has
            // requestedAtMs > 0 and is exactly the same wedge — the claim
            // only ever happens after gate admission, which a dead socket
            // can never complete.
            val plainCandidates = transfers.values.filter {
                it.status == Status.PENDING && it.direction == Direction.DOWNLOAD &&
                    it.source == Source.PLAIN && !it.pauseRequested && !it.isTextMessage &&
                    it.requestedAtMs == 0L &&
                    it.peerIp !in peerHasLiveWork &&
                    (now - it.startedAt) > plainNeverRequestedTimeoutMs() &&
                    (now - (peerLastDownloadActivity[it.peerIp] ?: 0L)) > plainNeverRequestedTimeoutMs()
            }.groupBy { it.peerIp }
            for ((_, rows) in plainCandidates) {
                val oldest = rows.minByOrNull { it.startedAt } ?: continue
                oldest.status = Status.FAILED
                oldest.finishedAt = now
                prioritized.remove(oldest.id)
                scheduleAutoRemoval(oldest.id, FAILED_DOWNLOAD_LINGER_MS)
                touch(oldest.peerIp)
            }
        }
        transfers.values.filter {
            it.status == Status.PENDING && it.direction == Direction.DOWNLOAD &&
                !it.pauseRequested &&   // Phase 1: a Waiting transfer the HOST deliberately paused is doing exactly what it was told — "no progress" is the point of a pause, not abandonment. Failing it out from under an explicit host Pause silently converted "paused for 5+ minutes" into "Failed", which is precisely the kind of state disagreement Phase 1 exists to remove.
                // Pass-19: the peer-activity exemption (see the block comment
                // above) — a row queued behind a HEALTHY, still-moving batch
                // is not stale no matter how old it is.
                ((peerLastDownloadActivity[it.peerIp] ?: 0L) == 0L ||
                    now - (peerLastDownloadActivity[it.peerIp] ?: 0L) > 60_000L) &&
                // ── FINAL PASS 3 (§6 — an UNDECIDED offer never auto-fails) ──
                // A Quick-Share row the browser client has not ACCEPTED yet
                // (requestedAtMs == 0 — its byte-request never came, because
                // the user simply hasn't tapped Get/Copy) is PENDING
                // ACCEPTANCE, not an abandoned dispatch: waiting for a user
                // decision is not a transfer failure, however long it takes.
                // The row resolves the moment the client decides (accept →
                // the request path claims it; decline → declinePending-
                // QuickShare; host cancel → cancelLiveQuickShareByName), and
                // a genuinely gone CLIENT is caught by the sender-side/
                // queue-side lifecycles instead. Never-requested SWEEP B
                // still covers the post-accept dropped-dispatch case.
                // ── FINAL PASS 4 (§9): the exemption now covers TEXT rows ──
                // by the same argument and one more — a text row's delivery
                // IS the consume (completeQuickShareText), so it never
                // receives a byte request even after the user accepts the
                // OFFER. requestedAtMs stays 0 forever on a text row, which
                // is exactly what this exemption keys on.
                !(it.source == Source.QUICK_SHARE && it.requestedAtMs == 0L) &&
                // FINAL PASS 4 (§9): belt-and-braces for text rows on engines
                // where a stale row could predate the marker — a text row is
                // never a stale byte download, whatever its clocks say.
                !it.isTextMessage &&
                (now - it.startedAt) > STALE_PENDING_TIMEOUT_MS
        }.forEach {
            it.status = Status.FAILED
            it.finishedAt = now
            prioritized.remove(it.id)
            scheduleAutoRemoval(it.id, FAILED_DOWNLOAD_LINGER_MS)
            touch(it.peerIp)
        }
        // Handoff re-await deadline: a download whose first (webview)
        // connection aborted at the interception point reverts to PENDING
        // expecting the browser's download manager to re-request the SAME
        // URL within HANDOFF_GRACE_MS. If that window closes with no
        // re-request, this browser genuinely has no download manager taking
        // the file (stock iOS 10 Safari is the canonical case) — finalize
        // the entry FAILED so the client's own fallback path can take over,
        // rather than leaving a "Waiting" row that nothing will ever move.
        // Its own filter (not the generic 5-minute staleness above, which
        // only ever catches it minutes later) so the failure reason stays
        // distinguishable in the log.
        transfers.values.filter {
            it.status == Status.PENDING && it.direction == Direction.DOWNLOAD &&
                it.reAwaitUntilMs > 0L && now > it.reAwaitUntilMs
        }.forEach {
            it.status = Status.FAILED
            it.finishedAt = now
            it.reAwaitUntilMs = 0L
            prioritized.remove(it.id)
            scheduleAutoRemoval(it.id, FAILED_DOWNLOAD_LINGER_MS)
            touch(it.peerIp)
        }
        // ── Pass-22 (#6 — orphaned native SEND rows) ─────────────────────────
        // A PENDING SEND row whose session machinery no longer owns it can
        // never be claimed or resolved by anything — without this sweep it
        // sat "Waiting" forever while the receiver's own rows idled equally
        // (the rare reported accept-then-nothing wedge). Generously beyond
        // every legitimate window (the 2-minute decision deadline, the
        // retry ladder, the deferred/paused paths — a parked paused row is
        // exempt via its own pauseRequested flag), then the honest FAILED
        // resolution, visible in the Active tab exactly like a decision
        // timeout. NOT an interruption: an orphaned pre-start row moved no
        // bytes, so there is nothing to resume — Failed is the accurate
        // terminal, and a genuine failure is reported, never hidden.
        val sendOwner = nativeSendRowOwner
        if (sendOwner != null) {
            transfers.values.filter {
                it.status == Status.PENDING && it.direction == Direction.SEND &&
                    it.source == Source.NATIVE_PUSH && !it.pauseRequested &&
                    (now - it.startedAt) > nativeSendOrphanMs && !sendOwner(it.id)
            }.forEach {
                it.status = Status.FAILED
                it.finishedAt = now
                prioritized.remove(it.id)
                scheduleAutoRemoval(it.id, FAILED_DOWNLOAD_LINGER_MS)
                touch(it.peerIp)
            }
        }
        sweepDuplicatePendingQuickShare()
    }

    /** Pass-8 (§3 — eliminate duplicate Active-tab rows): a targeted
     *  Quick-Share send pre-registers ONE PENDING row per (item, client) the
     *  moment the host taps Send, and that same row is the one promoted by
     *  the accept/claim chain — identity is stable by construction. This
     *  sweep is the belt-and-braces backstop for any path that could still
     *  leave a SECOND PENDING row behind for an (item, client) pair that
     *  already has an ACTIVE or COMPLETED transfer: every such stale row is
     *  resolved as CANCELLED (a quiet, visible resolution — never left as a
     *  ghost "Waiting" entry next to its own live twin). Matching is by the
     *  full (qsItemName, peerIp) IDENTITY pair, never by filename, so two
     *  intentionally sent same-named files never collide. Cheap linear scan,
     *  runs inside expireStalePending's existing cadence. */
    @Synchronized
    fun sweepDuplicatePendingQuickShare() {
        // (qsItemName|peerIp) -> best live status for that identity
        val advanced = HashMap<String, Int>()   // 1 = ACTIVE seen
        for (t in transfers.values) {
            if (t.source != Source.QUICK_SHARE || t.direction != Direction.DOWNLOAD) continue
            val name = t.qsItemName ?: continue
            // ── Pass-22 (#4 — re-sends after COMPLETION, root fix) ────────────
            // COMPLETED rows no longer count as "its live transfer already
            // exists". The sweep was built on the invariant that a second
            // PENDING row for one (item, client) can only ever be a bug — true
            // until re-sending existed: a deliberate re-send of a file this
            // same client already completed mints a fresh PENDING row beside
            // the old COMPLETED one, and this sweep then resolved the NEW
            // row as a "duplicate" — quietly cancelling the re-send moments
            // after the user tapped Send (the reported "the same file cannot
            // be sent again after a completed transfer"). Only an ACTIVE row
            // — an actually-streaming twin — is a duplicate; a COMPLETED row
            // is finished history, and its identity is free for a new send.
            if (t.status == Status.ACTIVE) {
                advanced["$name\u0000${t.peerIp}"] = 1
            }
        }
        if (advanced.isEmpty()) return
        transfers.values.filter {
            it.status == Status.PENDING && it.source == Source.QUICK_SHARE &&
                it.direction == Direction.DOWNLOAD &&
                it.qsItemName != null && advanced["${it.qsItemName}\u0000${it.peerIp}"] == 1
        }.forEach {
            requestCancel(it.id)
        }
    }

    /** Pass-8 (§13 — receiver-side Cancel All): every DISTINCT native-push
     *  session id that still has at least one live (PENDING/ACTIVE) UPLOAD
     *  row on this device. The receiver's Cancel All uses this to issue ONE
     *  whole-push cancellation per sender session (scope=push), instead of a
     *  per-file signal that only ever reached already-dispatched files. */
    fun liveUploadPushIds(): Set<String> {
        val out = java.util.HashSet<String>()
        for (t in transfers.values) {
            if (t.direction != Direction.UPLOAD || t.source != Source.NATIVE_PUSH) continue
            if (t.status != Status.PENDING && t.status != Status.ACTIVE) continue
            val req = t.uploadRequestId ?: continue
            val pushId = req.substringBefore("::")
            if (pushId.isNotEmpty()) out.add(pushId)
        }
        return out
    }

    // A connection can go silently dead without either side's socket ever
    // erroring out — a peer walking out of WiFi range, its process being
    // killed, or its hotspot switching off mid-transfer often produces no
    // clean TCP FIN/RST at all, just a read() that blocks forever. Nothing
    // in that blocked read loop would ever reach its own try/catch to mark
    // the transfer failed, so left unchecked this is exactly what leaves a
    // transfer permanently "Active" — and, for a native peer, the Nearby
    // Devices list permanently showing "Sending"/"Receiving" for something
    // that's actually going nowhere. Sized generously (transferring even a
    // single 256KB chunk at a genuinely awful 10KB/s still takes well under
    // this) so a merely-slow connection is never mistaken for a dead one.
    // Phase 10: with SafeResponse guaranteeing data-stream close on EVERY
    // send outcome (including mid-body aborts — NanoHTTPD 2.3.1 stock swallows
    // the IOException and never closes), terminal bookkeeping is now prompt;
    // the stall watchdog shrinks from 45s to 15s (check cadence 15s -> 5s)
    // and the orphan grace from 30s to 10s, so a genuinely wedged connection
    // frees its concurrency slot in seconds instead of most of a minute.
    private const val STALL_TIMEOUT_MS = 15_000L
    private const val STALL_CHECK_INTERVAL_MS = 5_000L
    // ── PASS 26 (receiver-acknowledged completion) ─────────────────────────────
    // How long an ackExpected DOWNLOAD row may sit with its bytes fully
    // delivered but no receiver finalization ack yet, before the stall sweep
    // resolves it FAILED-interrupted (the honest interruption — never a
    // phantom COMPLETED: a socket-write "success" that the receiving side
    // never confirmed is provably not a completion on a browser that cannot
    // download). Sized generously past the healthy path (the ack POST fires
    // within ~a second of the client-side load completing — the window only
    // ever really opens when the page died between receiving the last byte
    // and confirming) and far below the auto-resume window, so the row lands
    // in the same resumable regime any other interrupted receive uses.
    private const val DOWNLOAD_ACK_GRACE_MS = 20_000L
    // Pass-11: a SEND row's stall bound = the sender's auto-resume window
    // (10 min, mirrors NativePushClient.AUTO_RESUME_WINDOW_MS) plus a
    // margin — see sweepStalledActive's SEND branch for why the ordinary
    // 15-second failure no longer applies to outbound native pushes.
    private const val AUTO_RESUME_SEND_STALL_MS = 11 * 60_000L
    // ── Pass-19 (sender flips to "Auto-Resume on Reconnection" fast) ────────
    // A native send whose receiver vanished used to sit on the honest
    // "Sending…" until the generic 15-second stall bound let the watchdog
    // probe the peer — and then up to another full cycle before the probe
    // ran again. The reported case ("receiver instantly shows Waiting for
    // Reconnection, the sender keeps saying Sending… while progress is
    // frozen") is exactly that latency, doubled by the OS's own silence
    // on a blocked write. SEND rows now enter the liveness policy after
    // just 5 s of zero progress: on this 5 s check cadence the peer is
    // probed at ~5–10 s past the drop, so the sender's chip flips to
    // "Auto-Resume on Reconnection" essentially as fast as the receiver's
    // own presentation — and a live-but-slow peer still answers and keeps
    // the honest "Sending…". (Downloads/uploads keep the 15 s bound: their
    // prober is heavier — a page challenge with a forced SSE push — and
    // their blocked-write cases are already covered by the page-reconnect
    // event twin.)
    private const val SEND_STALL_PROBE_MS = 5_000L
    // ── Pass-23 (the receiver's Wi-Fi drops, the sender keeps "Sending…") ────
    // A DOWNLOAD row enters the liveness policy at this bound (7 s of zero
    // progress) instead of the ordinary 15-second stall: a browser whose
    // Wi-Fi just vanished is invisible to every local signal on this side —
    // its SSE stream object lingers until the 30 s back-pressure drop, its
    // download socket keeps absorbing writes into the kernel send buffer —
    // so the ACTIVE page challenge is the only fast evidence this sender
    // gets. The probe answers in ~2 s: a page that cannot answer is
    // provably gone and the row flips to "Auto-Resume on Reconnection"
    // within ~9 s of the drop; a page that answers keeps the honest
    // "Sending…" (the wedged-connection FAILURE still requires the full
    // STALL_TIMEOUT_MS — the early entry only accelerates the disconnect
    // presentation, never the failure verdict).
    private const val DOWNLOAD_STALL_PROBE_MS = 7_000L
    // ── GOAT-PASS 8 (§11 — the hotspot host's own honest presentation) ──────
    // A native UPLOAD row (THIS device is receiving a push) whose sender's
    // Wi-Fi just vanished is invisible to every local signal here: this
    // device's hotspot AP stays perfectly up (no LAN transition fires),
    // and the receive socket simply goes silent. The ordinary 15-second
    // stall bound then FAILED the row outright — honest but slow, and
    // latching the calm "Auto-Resume on Reconnection" presentation is the
    // better state while the sender's own retry loop owns the recovery.
    // Native UPLOAD rows now enter a liveness policy at 7 s of zero
    // progress: the prober (one tiny GET /api/ping at the SENDER's own
    // server — its address rides the push record's control link) answers
    // in ~1.3–2.6 s. A sender that cannot answer → latch reconnecting +
    // free the receiving slot (the row stays ACTIVE so the sender's re-POST
    // re-opens it in place through the existing claim chain — no duplicate,
    // no reset). A sender that answers keeps the honest "Receiving…" until
    // the ordinary stall bound decides. Browser uploads (Source.PLAIN)
    // keep the plain 15 s rule — their prober is a page challenge, not a
    // native ping, and their covered cases already have the page-reconnect
    // twin.
    private const val UPLOAD_STALL_PROBE_MS = 7_000L
    // ── Pass-19 (wedged data-socket self-heal) ───────────────────────────────
    // A stalled SEND whose peer IS alive (its server answers /api/ping) but
    // whose bytes have not moved for this long is a wedged DATA connection
    // — the transfer socket died silently while the peer's process stayed
    // perfectly reachable (a socket-level reset with no FIN, a paused
    // receiver-side read on an older peer, a half-open NAT mapping). TCP
    // retransmission can hold such a write "blocked" for minutes with no
    // exception ever reaching the retry loop. Resolving the row INTERRUPTED
    // right now is strictly better: the sender's own auto-resume loop
    // re-probes the receiver's preserved checkpoint and re-POSTs the
    // remainder over a FRESH socket within a second (backoff slot 0), so
    // the file finishes instead of hanging. Bytes flowing again clear the
    // reconnecting latch on their own, so a wrongly-timed flip self-heals
    // the moment real progress lands.
    private const val SEND_WEDGED_SELF_HEAL_MS = 30_000L
    // ── Pass-18 (app→browser auto-resume): a DOWNLOAD row latched ────────────
    // "Auto-Resume on Reconnection" (its receiving page provably went away
    // mid-stream) stays ACTIVE for exactly as long as the browser's own
    // reconnect window — FAILED_DOWNLOAD_LINGER_MS plus a margin — before
    // the watchdog honestly resolves it interrupted. Mirrors the SEND cap.
    private const val AUTO_RESUME_DOWNLOAD_STALL_MS = 11 * 60_000L
    // ── Pass-18 (zombie-row close): an UPLOAD row latched reconnecting by ────
    // the LAN-transition latch previously kept its `reconnecting` exemption
    // from the stall failure FOREVER — a receiver whose browser never came
    // back left an eternally-ACTIVE row. Same window-plus-margin cap as the
    // other directions now honestly resolves it interrupted.
    private const val AUTO_RESUME_UPLOAD_STALL_MS = 11 * 60_000L
    @Volatile private var stallWatchdogStarted = false
    private val stallWatchdog = java.util.concurrent.Executors.newSingleThreadScheduledExecutor()

    /** Started lazily on the very first transfer and then simply left
     *  running for the rest of the process's life — unlike
     *  [expireStalePending]'s opportunistic, call-triggered pattern, a
     *  stalled ACTIVE transfer has no other TransferManager activity to
     *  piggyback a check on, so this genuinely needs its own timer rather
     *  than hooking into an existing call site. */
    private fun ensureStallWatchdogStarted() {
        if (stallWatchdogStarted) return
        synchronized(this) {
            if (stallWatchdogStarted) return
            stallWatchdogStarted = true
        }
        stallWatchdog.scheduleWithFixedDelay({
            try { sweepStalledActive() } catch (e: Exception) { /* one bad cycle must never kill the recurring schedule */ }
        }, STALL_CHECK_INTERVAL_MS, STALL_CHECK_INTERVAL_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /** Reuses [Transfer.lastSampleAtMs] — already refreshed by every
     *  [updateProgress]/[addProgress] call for speed-sampling — as the "last
     *  real activity" signal rather than tracking a second timestamp for
     *  the same thing. Paused transfers are deliberately exempt: no
     *  progress for a long time is the whole point of a pause, not a sign
     *  anything is actually stuck.
     *
     *  Pass-18 restructure — every direction now runs its own liveness-aware
     *  policy at the ordinary 15-second stall bound, so a dead peer is
     *  DETECTED and presented within ~15s on the side whose own network is
     *  still up (the reported "sender kept reading 'Sending…' while the
     *  receiver was gone"), and resumption stays automatic:
     *   • SEND — hard cap at AUTO_RESUME_SEND_STALL_MS (unchanged). Before
     *     that, an unflagged stalled row is probed ONCE per cycle through
     *     [sendLivenessProber]; a peer that cannot answer flips the row to
     *     "Auto-Resume on Reconnection" (presentation only — the sender's
     *     auto-resume loop owns the outcome). A probe that answers (a
     *     live-but-slow peer) leaves the honest "Sending…" alone.
     *   • DOWNLOAD (a browser is receiving from THIS device) — unflagged:
     *     probe the page ([browserLivenessProber]); a page that cannot
     *     answer means the receiving client is gone → latch "Auto-Resume on
     *     Reconnection" (row stays ACTIVE so the browser's own reconnect
     *     re-request ATTACHES or re-opens it in place) and free its wedged
     *     download permit. A page that answers = a genuinely wedged
     *     connection → the ordinary interrupted failure. Flagged: re-probe
     *     — a page answering AGAIN means the network is BACK, so the row is
     *     resolved interrupted NOW: the browser's auto-reconnect engine
     *     (which arms on the failed snapshot) re-dispatches immediately
     *     instead of waiting for the blocked write's own error (TCP
     *     retransmission can take a minute or more). Hard cap at
     *     AUTO_RESUME_DOWNLOAD_STALL_MS.
     *   • UPLOAD — unchanged 15s interrupted failure, plus a cap so a
     *     LAN-latched reconnecting row can not stay ACTIVE forever.
     */
    /** Pass-24: true (and the row finalized COMPLETED) when [t] is an ACTIVE
     *  download whose delivery provably already moved every expected byte
     *  ([Transfer.transferredBytes] >= [Transfer.totalBytes] > 0) AND whose
     *  progress has been silent for the full honest stall window — the
     *  delivered-everything rescue the abort path has always applied, gated
     *  on a genuine stall so a live stream is never touched. A paused row is
     *  never finalized here: a pause is a user decision, and its bytes
     *  simply wait. Callers hold no monitor — the re-check inside makes the
     *  decision atomic against a concurrent complete/cancel (first
     *  finalizer wins — a COMPLETED/CANCELLED row makes this a no-op). The
     *  caller performs the permit force-release itself, OUTSIDE this
     *  monitor (see claimOrStartPendingLocked's lock-order note). */
    @Synchronized
    private fun finalizeDeliveredEverythingIfNeeded(t: Transfer): Boolean {
        if (t.status != Status.ACTIVE || t.direction != Direction.DOWNLOAD) return false
        if (t.pauseRequested || t.handoffEligible) return false
        // ── PASS 26 (receiver-acknowledged completion): an ack-expected row ──
        // NEVER force-completes here, however full its counter — a socket-write
        // "success" on a receiver-acked receive proves nothing about the
        // receiving side (the iPad 4's stock Safari discards exactly such
        // bodies). The row's own ack window (armed by complete()) or its
        // honest FAILED-interrupted sweep resolution owns the verdict.
        if (t.ackExpected) return false
        if (t.totalBytes <= 0L || t.transferredBytes < t.totalBytes) return false
        val now = System.currentTimeMillis()
        if (now - t.lastSampleAtMs < STALL_TIMEOUT_MS) return false
        finalizeCompletedBody(t)
        return true
    }

    private fun sweepStalledActive() {
        val now = System.currentTimeMillis()
        transfers.values.filter {
            it.status == Status.ACTIVE && !it.pauseRequested &&
                // Pass-19: SEND rows enter the liveness policy at the much
                // tighter SEND_STALL_PROBE_MS bound (see its constant) so the
                // sender's chip flips fast; every other direction keeps the
                // ordinary 15-second stall bound.
                // Pass-23: DOWNLOAD rows (a browser receiving from THIS
                // device) also enter the liveness policy early, at
                // DOWNLOAD_STALL_PROBE_MS — a receiver whose Wi-Fi just went
                // dark while this host's hotspot stays up is invisible to
                // every local signal (its SSE stream object lingers, its
                // dead socket absorbs writes silently), so the ACTIVE probe
                // is the ONLY fast evidence the sender gets. 15s read as
                // "still Sending…" for a quarter of a minute; the probe
                // answers in ~2s and flips the presentation.
                ((it.direction == Direction.SEND && (now - it.lastSampleAtMs > SEND_STALL_PROBE_MS)) ||
                    (it.direction == Direction.DOWNLOAD && (now - it.lastSampleAtMs > DOWNLOAD_STALL_PROBE_MS)) ||
                    // ── GOAT-PASS 8 (§11): native UPLOAD rows (this device is ──
                    // receiving a push) enter the liveness policy early too — a
                    // sender whose Wi-Fi vanished is invisible to every local
                    // signal on the hotspot host, and the 15 s bound used to
                    // fail the row before anything calmer could happen.
                    (it.direction != Direction.SEND && it.direction != Direction.DOWNLOAD &&
                        it.source == Source.NATIVE_PUSH && (now - it.lastSampleAtMs > UPLOAD_STALL_PROBE_MS)) ||
                    (it.direction != Direction.SEND && it.direction != Direction.DOWNLOAD && (now - it.lastSampleAtMs > STALL_TIMEOUT_MS)))
        }.forEach {
            val stalledMs = now - it.lastSampleAtMs
            when (it.direction) {
                Direction.SEND -> {
                    // ── Pass-11 (auto-reconnect root fix): the sender's own ──
                    // sendOneFile auto-resume loop owns this row's outcome for
                    // its full 10-minute window; the watchdog only presents
                    // (and hard-caps past the window).
                    if (stalledMs > AUTO_RESUME_SEND_STALL_MS) {
                        fail(it.id, interrupted = true)
                        return@forEach
                    }
                    if (!it.reconnecting) {
                        // ── Pass-18/19: presentation only, and only on PROOF ──
                        // the peer is unreachable — one tiny /api/ping at the
                        // peer's own server (sendLivenessProber), now probed
                        // from the first 5 s of silence. A live but slow peer
                        // answers and the row honestly keeps "Sending…"; a
                        // genuinely gone one flips to "Auto-Resume on
                        // Reconnection" within ~5–10 s of the drop.
                        val alive = try { sendLivenessProber?.invoke(it.id) } catch (e: Exception) { true } ?: true
                        if (!alive) {
                            markReconnecting(it.id)
                        } else if (stalledMs > SEND_WEDGED_SELF_HEAL_MS) {
                            // ── Pass-19 (wedged data-socket self-heal) ──────────
                            // Peer alive, bytes frozen 30 s+: the transfer
                            // socket itself is dead while the peer process is
                            // fine. Resolve interrupted NOW so the sender's
                            // auto-resume loop re-probes the checkpoint and
                            // continues over a fresh socket — one that can
                            // actually carry bytes — instead of parking the
                            // row on a write TCP will never error out of.
                            fail(it.id, interrupted = true)
                        }
                    }
                    return@forEach
                }
                Direction.DOWNLOAD -> {
                    // ── PASS 26 (receiver-acknowledged completion): the ack ──
                    // window rules an ack-expected row's silence. With the
                    // deadline armed (every byte left the socket; the receiver's
                    // own finalization ack is the only verdict left), within the
                    // window the row is an honest live receive at real progress —
                    // leave it alone; past it the bytes left but the receiving
                    // side never confirmed finalization, which is exactly an
                    // interruption: FAILED-interrupted (the auto-resume regime
                    // owns the recovery), NEVER a phantom COMPLETED.
                    if (it.ackExpected && it.ackDeadlineMs > 0L) {
                        if (now >= it.ackDeadlineMs) {
                            fail(it.id, interrupted = true)
                            forceReleaseDownloadSlot(it.id)
                        }
                        return@forEach
                    }
                    // ackExpected with NO deadline armed = the stream itself is
                    // still (or was) in flight — the ordinary stall policy
                    // below owns it unchanged.
                    // ── Pass-24 (delivered-everything rescue) ─────────────────────
                    // An ACTIVE row that already handed EVERY byte of the file
                    // to the socket but whose stream-close callback never
                    // finalized it (a wedged final write the kernel accepted,
                    // a connectionCount leak from an attached sibling, or a
                    // reconnecting latch on a page whose own resume has
                    // nothing left to transfer) used to sit at 100% "Sending"
                    // forever — while the page-side dispatch pump held its
                    // slot and the whole batch froze behind it (the reported
                    // "one item stuck at a full progress bar blocks the
                    // entire 340-file batch"). This is NOT a force-complete
                    // on a full progress bar: the rule is the same one
                    // downloadConnectionAborted's delivered-everything path
                    // has always applied — every byte of expectedTotal was
                    // pulled off the stream — but gated on the row having
                    // provably gone silent for the full honest stall window
                    // first, so a legitimately still-streaming transfer can
                    // never be touched (its lastSampleAtMs keeps refreshing).
                    if (finalizeDeliveredEverythingIfNeeded(it)) {
                        forceReleaseDownloadSlot(it.id)
                        return@forEach
                    }
                    if (stalledMs > AUTO_RESUME_DOWNLOAD_STALL_MS) {
                        fail(it.id, interrupted = true)
                        forceReleaseDownloadSlot(it.id)
                        return@forEach
                    }
                    if (it.reconnecting) {
                        // Already presented as awaiting the client's return —
                        // re-probe: a page that answers again is proof the
                        // network is BACK, and the blocked write's own error
                        // may still be a minute away (TCP retransmission
                        // backoff). Resolving interrupted now lets the
                        // browser's auto-reconnect engine re-dispatch within
                        // moments of the reconnection instead of stalling
                        // behind a dead socket. (LumiDropServer's
                        // onPageReconnected is the faster, event-driven twin
                        // of this check; this one is the cadence backstop.)
                        val back = try { browserLivenessProber?.invoke(it.peerIp) } catch (e: Exception) { false } ?: false
                        if (back) {
                            fail(it.id, interrupted = true)
                            forceReleaseDownloadSlot(it.id)
                        }
                        return@forEach
                    }
                    // Unflagged stall — one liveness probe decides "receiver
                    // gone" (presentation + permit free, row stays live for
                    // the in-place resume) from "connection genuinely wedged
                    // on a live page" (the ordinary interrupted failure).
                    val pageAlive = try { browserLivenessProber?.invoke(it.peerIp) } catch (e: Exception) { true } ?: true
                    if (!pageAlive) {
                        markReconnecting(it.id)
                        forceReleaseDownloadSlot(it.id)
                        return@forEach
                    }
                    // ── Pass-23: the page ANSWERED — the connection may ──
                    // simply be slow, not wedged. The fast 7s probe entry
                    // above only accelerates the *disconnect* presentation
                    // (the probe-backed flip), never the wedged-connection
                    // failure: that keeps requiring the full honest
                    // STALL_TIMEOUT_MS of zero progress on a page that is
                    // demonstrably still there.
                    if (stalledMs >= STALL_TIMEOUT_MS) {
                        fail(it.id, interrupted = true)   // Pass-8 §7: a silently-dead connection is an interruption
                        forceReleaseDownloadSlot(it.id)
                    }
                    return@forEach
                }
                else -> {
                    // UPLOAD — the ordinary 15-second interrupted failure, …
                    if (it.reconnecting) {
                        // … except a LAN-latched reconnecting receive can no
                        // longer stay ACTIVE forever (Pass-18 zombie close).
                        if (stalledMs <= AUTO_RESUME_UPLOAD_STALL_MS) return@forEach
                        fail(it.id, interrupted = true)
                        if (receivingSlotHolders.remove(it.id)) {
                            receivingGate.release()
                        }
                        return@forEach
                    }
                    // ── GOAT-PASS 8 (§11 — the native push liveness policy): ──
                    // before the ordinary failure verdict, one bounded probe of
                    // the SENDER's own server (its address rides the push's
                    // control link). A sender that cannot answer means the
                    // SENDER's network vanished — latch the calm reconnecting
                    // presentation and free the receiving slot, keeping the row
                    // alive for the sender's automatic re-POST to re-open in
                    // place (no duplicate row, no progress reset). A sender that
                    // answers (a live-but-slow or merely wedged-socket peer)
                    // falls through to the ordinary honest rules: its own side's
                    // watchdogs own the wedged-socket self-heal, and the 15 s
                    // bound resolves what genuinely never comes back.
                    if (it.source == Source.NATIVE_PUSH && stalledMs > UPLOAD_STALL_PROBE_MS && stalledMs < STALL_TIMEOUT_MS) {
                        val senderAlive = try { uploadLivenessProber?.invoke(it.id) } catch (e: Exception) { true } ?: true
                        if (!senderAlive) {
                            markReconnecting(it.id)
                            if (receivingSlotHolders.remove(it.id)) {
                                receivingGate.release()
                            }
                            return@forEach
                        }
                        // Sender alive at 7–15 s of silence: keep the honest
                        // "Receiving…" until the ordinary stall bound decides.
                        return@forEach
                    }
                    // The unflagged, fully-stalled case — including a native
                    // row whose sender was STILL alive at the probe (the
                    // sender's own watchdogs will drive its re-POST; the
                    // re-open claim chain then continues THIS row in place).
                    if (stalledMs >= STALL_TIMEOUT_MS) {
                        fail(it.id, interrupted = true)   // Pass-8 §7: a silently-dead connection is an interruption
                    }
                }
            }
            // GOAT (critical fix — stuck slots, both directions): a stalled
            // transfer may hold a RECEIVING slot (pre-existing force-release
            // below) or DOWNLOAD slot(s) (new in Phase 1 — the old download
            // gate had no holder identity at all, so a wedged download held
            // its slot forever with nothing able to free it). The connection
            // is presumed dead — nothing else would leave a transfer with
            // zero progress for this long — and nothing else will ever
            // release those permits for threads stuck in blocking socket
            // operations with no timeouts of their own. (The DOWNLOAD branch
            // above force-releases on its own paths and returns; this tail
            // covers the UPLOAD failure paths.)
            if (receivingSlotHolders.remove(it.id)) {
                receivingGate.release()
            }
            val freed = forceReleaseDownloadSlot(it.id)
            if (freed > 0) {
            }
        }
        // Phase 1 (orphaned-slot sweep): a slot holder whose transfer is
        // terminal (or gone entirely) should have released within
        // microseconds of finalizing — every release path is a finally or a
        // stream-close callback that runs on the same code path that flips
        // the status. Anything STILL holding a slot well past its transfer's
        // finish time is by definition an orphaned worker (the exact
        // early-cancelled-but-wedged-socket case requestCancel's immediate
        // finalize now makes reachable: the status flips to CANCELLED right
        // away, the worker thread stays blocked on a dead socket, and the
        // slot would otherwise never come back). Generous grace so a
        // legitimately-finishing tail (media scan between complete() and the
        // finally-release, say) is never mistaken for an orphan.
        val orphanGraceMs = 10_000L
        for (id in receivingSlotHolders.toList()) {
            val t = transfers[id]
            if (t == null || (t.status != Status.PENDING && t.status != Status.ACTIVE &&
                    now - t.finishedAt > orphanGraceMs)) {
                if (receivingSlotHolders.remove(id)) {
                    receivingGate.release()
                }
            }
        }
        for (id in downloadSlotHolders.keys.toList()) {
            val t = transfers[id]
            if (t == null || (t.status != Status.PENDING && t.status != Status.ACTIVE &&
                    now - t.finishedAt > orphanGraceMs)) {
                // Use remove()'s OWN return (the live count at removal), not
                // a pre-snapshot — a sibling connection may have released one
                // hold between the snapshot and here, and releasing the stale
                // count would over-free by exactly that difference. The
                // permit arithmetic + wakeups run under the gate lock (the
                // Pass-10 ordered gate is a lock/condition pair now, not a
                // semaphore — an unlocked ++ could be lost against a
                // concurrent release's own ++).
                val cur = downloadSlotHolders.remove(id)
                if (cur != null && cur > 0) {
                    downloadGateLock.lock()
                    try { downloadGatePermits += cur; downloadGateCond.signalAll() }
                    finally { downloadGateLock.unlock() }
                }
            }
        }
        // ── Pass-24 (paused-holder permit backstop) ──────────────────────────────
        // The pause-yield chain (schedulePausePermitYield) hands a settled
        // pause's permits back within ~750ms. Every other watchdog DELIBERATELY
        // exempts paused rows — which made that chain the single point of
        // failure for the whole queue: one lost yield (a scheduler thread that
        // died, an arming swallowed by the old no-re-arm hole) left a paused
        // ACTIVE row holding the only download permit forever, with nothing
        // else allowed to reclaim it (the reported "consecutive pauses wedge
        // the queue until the app is minimized"). This backstop reclaims, on
        // every watchdog cycle, the permits of any holder that is STILL paused
        // well past three grace windows — a healthy pause already released
        // them (making this a no-op), so the only rows it can ever touch are
        // ones whose yield genuinely went missing. The paused connection
        // itself is untouched, exactly like the yield: it resumes in place.
        val pausedBackstopMs = 3L * PAUSE_YIELD_GRACE_MS
        for (id in downloadSlotHolders.keys.toList()) {
            val t = transfers[id] ?: continue
            if (t.status != Status.ACTIVE || !t.pauseRequested) continue
            val stamp = t.pauseStartedAtMs
            if (stamp == 0L || now - stamp < pausedBackstopMs) continue
            val freed = forceReleaseDownloadSlot(id)
            if (freed > 0) {
            }
        }
    }

    /** Called when the server stops (explicit stop, or a settings-triggered
     *  restart) — an in-progress transfer can't finish once the HTTP server
     *  itself is gone, so anything still PENDING/ACTIVE is marked failed
     *  rather than left as a permanently-stuck progress bar with a badge
     *  count that can never clear on its own. Any concurrency slots those
     *  transfers still hold are force-released too: their worker threads
     *  belong to the dying server and may never run their finally blocks,
     *  and both gates are drained-and-rebuilt by configure*Concurrency
     *  right after this anyway — with the exactly-once release discipline,
     *  a LATE release from a pre-restart worker simply no-ops instead of
     *  inflating the fresh gate. */
    fun failAllActive() {
        val now = System.currentTimeMillis()
        transfers.values.filter { it.status == Status.ACTIVE || it.status == Status.PENDING }.forEach {
            it.status = Status.FAILED; it.finishedAt = now; it.speedBps = 0.0
            prioritized.remove(it.id)
            scheduleAutoRemoval(it.id, if (it.direction == Direction.DOWNLOAD) FAILED_DOWNLOAD_LINGER_MS else FAILED_LINGER_MS)
            if (receivingSlotHolders.remove(it.id)) receivingGate.release()
            forceReleaseDownloadSlot(it.id)
        }
    }
}
