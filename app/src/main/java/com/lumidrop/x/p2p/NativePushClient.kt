package com.lumidrop.x.p2p

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.lumidrop.x.transfer.PipelineIO
import com.lumidrop.x.transfer.PreviewItem
import com.lumidrop.x.transfer.FileTreeNode
import com.lumidrop.x.transfer.TransferManager
import com.lumidrop.x.transfer.uniqueTreePath
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Phase 14 — sending side of the native device-to-device push protocol. The
 * receiving side lives in LumiDropServer's apiNativePushRequest/
 * apiNativePushFile family (see the comment block there for the shared
 * approval-then-stream shape this mirrors).
 *
 * One [Session] = one folder/multi-select send to one peer. sendToPeers()
 * fires one Session per peer concurrently, so a "Smart Mixed-Target"
 * send — some recipients native, some browser — has every native leg
 * running in parallel with each other and with whatever
 * addFolderToQuickShare()/addToQuickShare() calls are simultaneously
 * driving the browser-target legs of that same user action.
 *
 * Every file within a session becomes its own TransferManager entry
 * (Direction.SEND), exactly like every file in a folder already becomes its
 * own entry on the *receiving* end (Direction.UPLOAD) — no batch-level
 * transfer object was invented, so Pause/Resume/Cancel/Retry/progress all
 * fall out of the existing per-transfer machinery for free.
 */
object NativePushClient {

    init {
        // GOAT (Phase 3, item 10 -- stale-connection self-heal): see
        // LumiDropServer's own init block for the full reasoning. Same
        // process-wide JVM property, set here too since this device might
        // only ever be a sender (never touching LumiDropServer's own
        // outbound calls) in a given session -- redundant with that one,
        // never conflicting.
        System.setProperty("http.keepAlive", "false")
        // ── GOAT-PASS 5 (§7): wire the dispatch-gate holder test into the ──
        // engine so TransferManager.isExecuting answers "is this SEND row
        // genuinely streaming right now" from REAL slot ownership — the
        // signal the Transfers tab's active presentation derives from.
        try {
            com.lumidrop.x.transfer.TransferManager.wireOwnsSendSlot { tid -> isSendStreaming(tid) }
        } catch (e: Exception) { /* headless engine tests never reach this object */ }
        // ── Pass-22 (#6): wire the row-ownership hook TransferManager's ──────
        // orphan sweep reads (nullable lambda, same pattern as the liveness
        // probers, so headless engine tests never depend on this object).
        TransferManager.nativeSendRowOwner = { tid -> ownsSendRow(tid) }
    }

    private const val CONNECT_TIMEOUT_MS = 6000
    private const val STATUS_POLL_MS = 700L
    private const val STATUS_TIMEOUT_MS = 2 * 60_000L   // give an UNREACHABLE host 2 minutes before giving up
    // ── FULL FINAL PASS 2 (§4): the ABSOLUTE bound on an otherwise liveness- ──
    // extended decision wait — a reachable-but-undecided host keeps the wait
    // alive (see runSession), but no session may sit in decision-wait forever.
    private const val STATUS_TIMEOUT_ABSOLUTE_MS = 30 * 60_000L

    // ── GOAT-PASS 15 (§4.3 — the streaming-phase sync poll cadence) ─────────
    // While dispatched legs of an accepted batch are still streaming, one
    // tiny status GET per ~2 s carries the receiver's CURRENT exclusions and
    // decisions back to this side (see the sync poller in runSession). The
    // cadence is deliberately an order of magnitude gentler than the
    // decision-phase poll (STATUS_POLL_MS) — the bytes on the wire are
    // what matter during a stream, and one ~300-byte GET per 2 s is
    // invisible next to a 256 KB chunk, while still converging the two
    // sides' eligible-child totals within a couple of seconds of any
    // receiver-side ✕.
    private const val STREAMING_SYNC_POLL_MS = 2_000L

    // ── GOAT-PASS 6 (§1 companion — the long-pause keep-alive cadence) ──────
    // While EVERY remaining file of a progressive-dispatch session is
    // deliberately paused, the Pass-19 poll-skip parks the dispatcher
    // quietly — but the receiver's dead-sender sweep only sees liveness
    // through lastActivityAt, which the status poll refreshes. A pause that
    // outlives NATIVE_PUSH_DEAD_SENDER_MS (10 min) would otherwise lose the
    // receiver's push record — and every sibling's resume checkpoint with
    // it — while both devices sit there alive and paused. One poll every
    // 45 s is invisible on the wire and keeps the record honest for as
    // long as the pause legitimately stands.
    private const val PAUSE_KEEPALIVE_POLL_MS = 45_000L
    // ── FINAL PASS 4 (§3): the manifest-window keep-alive cadence. 15 s is
    // comfortably inside every receiver-side silence window (the placeholder
    // branch's 10-minute dead-sender conjunct, the undecided branch's
    // 10-minute one) while costing one tiny GET per quarter-minute during
    // the only sender-silent window the protocol has.
    private const val PLACEHOLDER_KEEPALIVE_POLL_MS = 15_000L

    // GOAT-PASS 2 (§modern-Android — scheduling): bounded boost for the
    // send pipeline's own prefetch thread (lives exactly as long as one
    // file's stream, dies with it). Same reasoning and same conservative
    // -4 band as LumiDropServer's twin helper — kept as a private copy so
    // the p2p layer never reaches into the server object for a scheduling
    // side effect. Best-effort always.
    private fun boostPipelineThread() {
        try { android.os.Process.setThreadPriority(-4) } catch (e: Exception) { /* best-effort */ }
    }

    // ── Pass-10 (automatic resume overhaul) ────────────────────────────────
    // The sending half of the automatic-resume system. A connection loss
    // (Wi-Fi/hotspot drop, peer walking out of range, socket reset) is no
    // longer a terminal failure: the sender probes the receiver's preserved
    // byte checkpoint, re-sends ONLY the remainder, and keeps retrying with
    // a bounded backoff for this whole window. There is deliberately NO
    // manual "resume" action anywhere — the row stays live, reads
    // "Reconnecting…", and picks up from the exact byte where the
    // connection died. Mirrors TransferManager.FAILED_DOWNLOAD_LINGER_MS so
    // both sides keep a visible, resumable row for the same window.
    private const val AUTO_RESUME_WINDOW_MS = 10 * 60_000L
    private val RESUME_BACKOFF_MS = longArrayOf(1000L, 2000L, 4000L, 8000L, 10_000L)

    // ── Pass-11 (ghost-transfer root fix): the file-status probe sentinel ──
    // Returned by probeExistingBytes() when the receiver reports this exact
    // file was DELIBERATELY cancelled (a durable tombstone answered the
    // probe — see LumiDropServer.apiNativePushFileStatus). Distinct from
    // null ("peer unreachable — retryable") so the sender's auto-resume
    // loop resolves the row CANCELLED and stops immediately instead of
    // blindly re-POSTing the remainder of a file the receiver just refused.
    private const val PROBE_REFUSED_CANCELLED = -2L
    // GOAT-PASS 11 (§7): the probe's "declined on the receiver" sentinel.
    private const val PROBE_REFUSED_DECLINED = -3L

    // ── Pass-15 (ghost-item ask-to-resend): returned by probeExistingBytes() ──
    // when the receiver answers `discarded: true` — the receiving side deleted
    // the partial file on purpose (the X on the "Waiting for reconnection"
    // card) while this sender was still inside its retry window. Distinct
    // from a plain 0-byte answer ("partial lost — just resend from 0%") so
    // the loop can ASK THE USER — "Send again from the beginning, or
    // cancel?" — instead of silently re-sending a file the receiving side
    // deliberately threw away.
    private const val PROBE_DISCARDED = -6L
    // ── GOAT-PASS 14 (Phase 1, §1.8 — pre-existing defect repair, see ──────────
    // PROBE_DISCARDED's own value above): this alias used to share -3L with
    // PROBE_REFUSED_DECLINED, so a `discarded` answer matched the DECLINED
    // branch first and the ask-before-resend dialog (askResendFromScratch)
    // was dead code — a receiver's deliberate discard silently resolved the
    // row Declined instead of asking "Send again from the beginning, or
    // cancel?". -6L makes the documented distinction real.

    // ── Pass-19 (cross-device pause sync, sender half) ────────────────────────
    // Returned by probeExistingBytes() when the RECEIVER deliberately paused
    // this file (its row carries pauseRequested, reported by
    // apiNativePushFileStatus's new `paused` field). Distinct from every
    // other answer so the send loop can mirror the pause HERE — mark this
    // row Paused, park the file in pausedSends, and stop trying — instead of
    // hammering a receiver that asked for a pause, or misreading it as an
    // error. When the receiver resumes, its "resume" control reaches this
    // device's server, which relaunches the parked send from the checkpoint
    // — the mirror image of this device's own pause propagation.
    private const val PROBE_PEER_PAUSED = -4L

    // ── GOAT-PASS 14 (Phase 1, §1.8): returned by probeExistingBytes() when ─────
    // the receiver reports `complete: true` for an UNKNOWN-SIZE entry (the
    // streamed zip-batch sends) — the destination already holds the finished
    // archive, so the send short-circuits Done without re-streaming a byte.
    // (Deliberately its OWN sentinel rather than reusing the positive
    // existingBytes path: a positive answer for an unknown-size file could
    // also mean a staging partial, which must restart — only this explicit
    // verdict means "already delivered in full".)
    private const val PROBE_ALREADY_COMPLETE = -5L

    // ── FINAL PASS 3 (§1): the byte figure the receiver reported alongside ──
    // its "complete: true" probe answer (its own finished archive's length —
    // see apiNativePushFileStatus). Written by probeExistingBytes() on every
    // call (reset to -1 first) and read only by the PROBE_ALREADY_COMPLETE
    // consumer in the same send flow, so the value can never leak across
    // files: it exists so a resend that finds the archive already delivered
    // can stamp the sender's row with the REAL final size instead of leaving
    // "?" in its History (zero bytes stream in that path, so the row's own
    // counters cannot supply the figure).
    @Volatile private var lastProbeCompleteBytes = -1L

    /** Outcome of one file-stream attempt — drives the automatic-resume
     *  loop: a RETRYABLE result (network death) backs off and tries again
     *  from the receiver's real checkpoint; the peer's DELIBERATE refusals
     *  resolve this row to CANCELLED / DECLINED (never "Failed" — a decision
     *  is not an error); only a genuinely unrecoverable condition or an
     *  exhausted resume window ends in Failed/Interrupted. */
    // GOAT-PASS 11 (§7): SESSION_GONE — the receiver answered (it is
    // reachable) but holds no record of this push: "Unknown or expired
    // session". Nothing this leg can do will ever resume under that id, so
    // re-POSTing for the full 10-minute auto-resume window — per file,
    // across every remaining file of a large folder — is pure churn the
    // user watches as "the sender keeps transmitting". The row resolves
    // honestly interrupted (manual Retry re-announces a fresh session and
    // adopts preserved partials, exactly as before).
    private enum class StreamOutcome { SUCCESS, RETRYABLE, REFUSED_CANCELLED, REFUSED_DECLINED, FATAL, SESSION_GONE,
        // ── Pass-16 (pause-yield): the user paused this send — abort the POST,
        // park the file (checkpoint preserved on the receiver), free the ──
        // dispatch permit for the next Waiting item. Not an error, not a
        // cancel: a scheduled continuation.
        PAUSED }

    // GOAT-PASS 2: the old MAX_CONCURRENT_FILES knob (3–4 by CPU count,
    // "how many files stream at once to ONE peer") is RETIRED. Transfer
    // concurrency is pinned at exactly 1 by specification — both dispatch
    // paths above hard-code a 1-permit gate — so the knob is deleted
    // outright rather than left at 1: an unused tuning constant whose doc
    // promises parallel dispatch is exactly the kind of leftover that a
    // future pass "helpfully" re-widens. (The receiver's advertised
    // receivingConcurrency is still parsed and logged for mixed-version
    // honesty, but never widens the gate.)

    class FileSpec(val relPath: String, val size: Long, val node: FileTreeNode, val lastModified: Long,
                   // ── Pass-16 (cross-sender resume, sender half) ─────────────────
                   // SHA-256 hex of the first min(4 KB, size) bytes of the source
                   // file — the CONTENT identity declared in the manifest and on
                   // every file-status probe, so the receiver can resume the
                   // same file from THIS device even though a copy of it on a
                   // DIFFERENT device stamped a different mtime. Computed once at
                   // manifest build (one 4 KB read per file — a few ms even for a
                   // multi-hundred-file batch); null when unreadable, in which
                   // case the receiver falls back to the (size, mtime) rules.
                   val fp: String? = null)

    /** GOAT (Part 1 correction #4): a top-level item as announced before
     *  any filesystem/SAF enumeration — just its name and whether it's a
     *  folder, both already known to the picker UI with zero I/O. Phase 6
     *  (§6.6): isText lets the receiver's preparing row already present
     *  itself as a text message instead of a mystery file. */
    data class AnnounceItem(val name: String, val isFolder: Boolean, val isText: Boolean = false,
                            // ── Pass-16: the text content rides the announce so ──
                            // the receiver's PLACEHOLDER phase already renders the
                            // true message card — never the file-like container a
                            // message sometimes used to arrive as.
                            val text: String? = null)

    /** A Quick-Share text/paste item bound for a native peer. Unlike a file,
     *  its whole content rides inside the manifest request itself (see
     *  runSession) — there's nothing left to stream afterward, so it never
     *  becomes a FileSpec/FileProgress. */
    class TextSpec(val relPath: String, val text: String) {
        val sizeBytes: Long get() = text.toByteArray(Charsets.UTF_8).size.toLong()
    }

    private class ManifestResult(
        val files: List<FileSpec>,
        val dirs: List<String>,
        val texts: List<TextSpec>,
        // Pass-8 follow-up (auto-send no-device fix): per-ITEM outcome,
        // parallel to the `items` list the manifest was built from — true
        // when that item contributed (resolved as a file/folder, or was a
        // text item), false when it failed to resolve entirely. sendToPeers
        // uses this to resolve each Auto-Send-parked "Waiting" row: a
        // contributing item's parked row is quietly superseded by its real
        // per-file rows; a dead item's parked row fails VISIBLY — never the
        // old behavior where a failed drain silently vanished the batch.
        val perItemOk: List<Boolean>,
    ) {
        val isEmpty: Boolean get() = files.isEmpty() && dirs.isEmpty() && texts.isEmpty()
    }

    enum class FileState { QUEUED, ACTIVE, DONE, NOT_DONE,
        // ── Pass-16 (pause-yield): a send that deliberately aborted its POST ──
        // because its user PAUSED it, yielded its dispatch permit, and now waits
        // in the pausedSends registry for resumeFile(tid) to relaunch it from
        // the receiver's preserved checkpoint. Not a terminal state — the
        // session is deliberately held open while any file sits here.
        PAUSED }

    class FileProgress(val spec: FileSpec) {
        @Volatile var state: FileState = FileState.QUEUED
        @Volatile var transferId: String? = null
    }

    class Session(val peer: NativeDiscovery.NativePeer, val rootLabel: String) {
        // ── GOAT-PASS 11 (§7): set the moment the receiver answers any ──
        // request with a WHOLE-PUSH cancel verdict ("Push cancelled by
        // host", or the probe's pushCancelled flag) — the session is torn
        // down on the spot (cancelSessionById semantics) so the dispatcher
        // stops admitting the folder's remaining children instead of
        // grinding through them one refusal at a time.
        @Volatile var peerPushCancelled = false

        val id: String = java.util.UUID.randomUUID().toString()
        val files = mutableListOf<FileProgress>()
        // GOAT (Part 1 correction #4): both start empty and are filled in
        // once the real manifest is ready (see sendToPeers) — this Session
        // is now created and announced BEFORE that happens, not after.
        @Volatile var dirPaths: List<String> = emptyList()
        @Volatile var textSpecs: List<TextSpec> = emptyList()
        // GOAT (Part 2 — folder as a single transfer item): top-level
        // folder name -> TransferManager group id, so this SESSION's own
        // sent files collapse into one Active/Sent-tab row per folder on
        // the SENDER's side too, mirroring registerPendingTransfersFor on
        // the receiver. Empty for a session sending only loose files.
        @Volatile var groupIdByTopLevel: Map<String, String> = emptyMap()
        @Volatile var requestId: String? = null
        // ── Pass-16 (pause-yield): the dispatcher's pacing semaphore and the ──
        // application context, captured so a PAUSED file can later be
        // RELAUNCHED (NativePushClient.resumeFile) under the very same
        // concurrency discipline — a resumed file re-acquires a permit from
        // this gate before its first byte, exactly like a first dispatch.
        @Volatile var dispatchGate: Semaphore? = null
        @Volatile var appContext: Context? = null
        // Pass-16: true between "all non-paused work finished" and the
        // session's real finish — set by the dispatch tails when a paused
        // entry keeps the session open, cleared by finishSession itself.
        @Volatile var heldOpenByPause: Boolean = false
        /** announcing | connecting | pending | sending | rejected | error | done | done_with_errors */
        @Volatile var status: String = "announcing"
    }

    /** GOAT-PASS 9 (§14 — discovery/transfer race): sessions must never
     *  trust the captured [Session.peer] instance's IP for the lifetime of
     *  a transfer. NativeDiscovery's peer map is the authority — it mutates
     *  entries in place while they live, and a peer that was ever removed
     *  and re-added (probe-kill + its next beacon) is a NEW instance, which
     *  would leave this session holding an orphan with a frozen IP: every
     *  status poll and file POST would then go to a dead URL (the reported
     *  "sender stuck Waiting, receiver accepted, nothing transferred").
     *  Every outgoing HTTP call therefore resolves the CURRENT entry by the
     *  peer's stable deviceId first, falling back to the captured instance
     *  only when the id has left the map (its last-known address is
     *  strictly better than nothing). */
    private fun baseUrlOf(session: Session): String =
        NativeDiscovery.currentPeer(session.peer.deviceId)?.baseUrl ?: session.peer.baseUrl

    /** GOAT-PASS 9 (§8 — folder-cancel reliability): the entry-side abandon
     *  test every dispatch/admission site must consult. Deliberately
     *  AUTHORITATIVE — the durable per-tid cancel latch, not the bare
     *  isCancelled() that reads false 400 ms after the CANCELLED row
     *  auto-dismisses — plus the session-level teardown verdict, so a
     *  folder cancelled from either side stops admitting its remaining
     *  children no matter how long the queue is. */
    private fun dispatchAbandoned(session: Session, fp: FileProgress): Boolean {
        if (com.lumidrop.x.transfer.NativeSessionPolicy.sessionTornDown(session.status)) return true
        val tid = fp.transferId ?: return false
        return TransferManager.isCancelAuthoritative(tid)
    }

    // tid -> which session/file it belongs to, so a Transfers-tab Retry tap
    // (which only has the TransferManager id to go on) can find its way back.
    private val retryIndex = ConcurrentHashMap<String, Pair<Session, FileProgress>>()

    /** Pass-6 (R-H): session statuses that will never dispatch another
     *  file — shared by propagateCancelForTid's early return and
     *  pruneRetryIndex's eligibility check (promoted from that function's
     *  local set so the two can't drift apart). */
    private val TERMINAL_SESSION_STATES = setOf("done", "done_with_errors", "rejected", "error")

    // Pass-6 (R-H): EVERY pre-registered send tid -> its session (both folder
    // members AND loose files — groupSessionIndex only ever covered folders).
    // This is what lets the Transfers tab's per-row Cancel resolve a WAITING
    // row back to the push it belongs to: cancelling a not-yet-dispatched
    // file must cancel the WHOLE push on both ends (see propagateCancelForTid)
    // — a per-file propagateControl can't reach it, because retryIndex only
    // gains entries the moment a file actually starts streaming.
    private val tidSessionIndex = ConcurrentHashMap<String, Session>()
    // Pass-8 (§13): session id -> Session, so a RECEIVER-side whole-push
    // cancel (scope=push propagated to this device as the SENDER) can find
    // and tear down the sending session by the very id it announced — the
    // receiver's push record and this map key are the same UUID.
    private val sessionById = ConcurrentHashMap<String, Session>()
    // GOAT (receiver-side cancel fix -- reported after testing: cancelling a
    // folder made it disappear on the sender but left it stuck, partially
    // received, on the receiver): groupId -> the Session sending it.
    // Deliberately separate from retryIndex, which only ever holds an entry
    // for a file that has actually been dispatched (see sendOneFile) --
    // exactly the gap that made the per-file propagateControl below a
    // no-op for anything still queued when a group cancel happened.
    // Populated once per session, as soon as groupIdByTopLevel itself is
    // known (manifest arrival, see sendToPeers), not per file, so it's
    // available even if not one single file in the group has started yet.
    private val groupSessionIndex = ConcurrentHashMap<String, Session>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── Pass-16 (pause-yield + resume-one-of-all-paused) ───────────────────────
    // The sender half of "resuming ANY one paused item works on both sides":
    // a file the user PAUSED mid-stream deliberately aborts its POST, parks
    // itself here (keeping its row identity and the receiver's preserved
    // checkpoint), and RELEASES its dispatch permit — so a different Waiting
    // item can start immediately, and the paused one relaunches from the
    // checkpoint when the user resumes it. Cancelling a paused row purges the
    // entry and lets the session finish honestly.
    private val pausedSends = java.util.concurrent.ConcurrentHashMap<String, Pair<Session, FileProgress>>()

    // ── Pass-22 (#6 — sender-side row ownership + orphan backstop) ─────────────
    // Session ids whose runSession coroutine is currently executing. A
    // PENDING SEND row whose session is NOT live (and which is not parked in
    // pausedSends or being retried in retryIndex) is provably orphaned — the
    // dispatch machinery that would eventually claim or resolve it is gone.
    // TransferManager's expireStalePending consults ownsSendRow through the
    // nullable hook below (kept nullable so the JVM engine tests never need
    // this object) and gives such rows their honest FAILED resolution after a
    // generous window, so a native send can never sit on "Waiting" forever
    // while the receiver shows nothing at all.
    private val liveSessionIds = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    // ── GOAT-PASS 5 (§7): the dispatch-gate holder set ─────────────────────────
    // tids whose sendOneFile leg is live right now (added at leg start,
    // removed in the leg's finally). With the 1-permit dispatch gate this
    // holds exactly one tid per session at any moment — the ONE row that is
    // genuinely streaming. TransferManager.isExecuting consults it through
    // the hook wired at init, so the Transfers tab's active (purple
    // "Sending…") presentation derives from real slot ownership: a row that
    // was resumed and is WAITING at gate.acquire() renders the neutral
    // waiting state instead of a second purple "Sending…".
    private val streamingSends = java.util.Collections.newSetFromMap(
        java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** GOAT-PASS 5 (§7): true while [tid]'s send leg is live — the
     *  authoritative "is this SEND actually executing" answer the UI's
     *  active presentation derives from (wired into TransferManager at
     *  init). */
    fun isSendStreaming(tid: String): Boolean = streamingSends.contains(tid)

    fun ownsSendRow(tid: String): Boolean {
        if (pausedSends.containsKey(tid)) return true
        if (retryIndex.containsKey(tid)) return true
        val s = tidSessionIndex[tid] ?: return false
        return liveSessionIds.contains(s.id)
    }

    /** Pass-22 (#6): the catch-path resolution for a session coroutine that
     * died mid-flight — every still-PENDING pre-registered row flips FAILED
     * (visible in the Active tab's terminal flash, exactly like the decision
     * timeout's own resolution), and any row actively claimed by the dead
     * coroutine gets the interrupted treatment so its receiver keeps the
     * partial for resume. */
    private fun resolveOrphanedSessionRows(session: Session) {
        for (fp in session.files.toList()) {
            val tid = fp.transferId ?: continue
            val t = TransferManager.get(tid) ?: continue
            when (t.status) {
                TransferManager.Status.PENDING -> TransferManager.fail(tid)
                TransferManager.Status.ACTIVE -> TransferManager.fail(tid, interrupted = true)
                else -> Unit
            }
        }
        for (t in session.textSpecs) {
            // texts carry no rows until decided — nothing to resolve
        }
    }

    /** Pass-16: relaunches a PAUSED native send from the receiver's preserved
     *  checkpoint — called by the Transfers tab's Resume tap alongside the
     *  ordinary requestResume/propagate pair. No-op for anything that isn't
     *  a parked paused send. Re-acquires the session's own dispatch gate
     *  first, so the resumed file can never exceed the batch's pacing.
     *
     *  ── GOAT-PASS 8 (§13 — the resume that instantly re-paused itself): ──
     *  The relaunched leg's FIRST act is a checkpoint probe, and the probe
     *  asks the RECEIVER "is your row for this file paused?" — a question
     *  whose answer was still "yes" whenever the resume CONTROL POST (sent
     *  separately, fire-and-forget, by the UI's tap handler) had not landed
     *  yet. The raced probe answered paused:true, sendOneFile mirrored that
     *  stale pause right back onto the row the user had JUST resumed
     *  (parkForPeerPause → requestPause), and the item flipped Paused again
     *  until a second Resume happened to win the race — the exact reported
     *  repro. The ordering is now owned HERE: before the leg is relaunched,
     *  the resume control is posted SYNCHRONOUSLY (bounded 3 s + 3 s — the
     *  relay is one small request on a LAN), so by the time the probe asks,
     *  the receiver's flag is provably cleared. A failed or lost control
     *  POST never blocks the relaunch — the leg proceeds exactly as before
     *  and the ordinary retry/probe machinery owns whatever it finds (the
     *  receiver is unreachable anyway in that case, and the probe fails
     *  rather than answering "paused"). */
    fun resumeFile(tid: String): Boolean {
        val entry = pausedSends.remove(tid) ?: return false
        val (session, fp) = entry
        if (session.status in TERMINAL_SESSION_STATES) return false
        scope.launch {
            // §13: the receiver must process our "resume" BEFORE our probe
            // asks it whether it is paused — one bounded, ordered control
            // POST instead of a fire-and-forget racer.
            postControlSync(session, fp, "resume")
            val gate = session.dispatchGate
            if (gate != null) gate.acquire()
            try {
                sendOneFile(session, fp)
            } catch (e: Exception) {
            } finally {
                gate?.release()
                finishSessionIfNotHeld(session)
            }
        }
        return true
    }

    /** ── GOAT-PASS 8 (§13): the SYNCHRONOUS, bounded peer-control POST. ──────
     *  Same wire shape as [propagateControl]'s fire-and-forget call, but
     *  AWAITED on the calling coroutine so ordering against a following
     *  request (the resumed leg's checkpoint probe) is guaranteed. Returns
     *  false for any failure — callers treat that as "peer unreachable / no
     *  answer", never as a reason to stop. */
    private fun postControlSync(session: Session, fp: FileProgress, action: String): Boolean {
        val reqId = session.requestId ?: return false
        return try {
            val encodedPath = URLEncoder.encode(fp.spec.relPath, "UTF-8").replace("+", "%20")
            val url = "${baseUrlOf(session)}/api/native/push/control?id=$reqId&path=$encodedPath&action=$action"
            var conn: HttpURLConnection? = null
            try {
                val c = com.lumidrop.x.utils.PeerHttp.open(session.appContext, url) as HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = 3000; c.readTimeout = 3000
                conn = c
                c.responseCode in 200..299
            } finally { conn?.disconnect() }
        } catch (e: Exception) {
            false
        }
    }

    /** Pass-16: purges a paused entry (cancel paths) so the row's session can
     *  still finish. Returns true when an entry was actually removed. */
    fun discardPausedSend(tid: String): Boolean = pausedSends.remove(tid) != null

    /** Pass-16: a deliberate CANCEL of a PAUSED send — purge the parked entry
     *  (the parked coroutine no longer exists to notice the cancel itself)
     *  and run the deferred session tail so the session can finally finish.
     *  Returns true when a parked entry was actually consumed. */
    fun cancelPausedSendIfParked(tid: String): Boolean {
        val entry = pausedSends.remove(tid) ?: return false
        val (session, fp) = entry
        fp.state = FileState.NOT_DONE
        scope.launch {
            try { finishSessionIfNotHeld(session) } catch (e: Exception) { /* the cancel itself already resolved the row */ }
        }
        return true
    }

    // ── Pass-18 (sender-side "Auto-Resume on Reconnection") ──────────────────
    /** Is the peer of THIS in-flight native SEND provably unreachable right
     *  now?  One tiny GET /api/ping at the peer's own server (every device
     *  runs one — pass-17 added the route for NativeDiscovery's expiry
     *  probes; the exact same convention is reused here: ANY HTTP response
     *  code, even a 4xx/5xx, proves the peer's server is alive on the
     *  network; only a connect/read exception means "gone").
     *
     *  Called synchronously by TransferManager's stall watchdog — at most
     *  once per stalled SEND row per 15s cycle — so the timeouts are kept
     *  tight: worst case ~2.6s against a silently-vanished peer (SYN
     *  retransmits eat the connect window), which is fine on the
     *  watchdog's own timer thread.
     *
     *  Conservative by contract: a tid whose session can't be resolved
     *  (already finished and pruned, or a receiver-only row that never had
     *  a session here) answers ALIVE — the caller keeps the honest
     *  "Sending…" label whenever reachability can't be PROVEN either way,
     *  so a live-but-slow peer can never be mistaken for a dead one. */
    fun isSendPeerAlive(tid: String): Boolean {
        val session = tidSessionIndex[tid] ?: return true   // unresolvable → assume alive
        val base = baseUrlOf(session)
        var conn: java.net.HttpURLConnection? = null
        return try {
            val c = com.lumidrop.x.utils.PeerHttp.open(session.appContext, "$base/api/ping") as java.net.HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = 1300
            c.readTimeout = 1300
            conn = c
            c.responseCode        // any answer = alive
            true
        } catch (e: Exception) { false } finally { conn?.disconnect() }
    }

    /** Pass-16: the deferred session tail — called wherever a dispatch tail
     *  WOULD call finishSession, and again from resumeFile's finally. The
     *  session only truly finishes once no paused entry holds it open.
     *
     *  ── GOAT-PASS 6 (§1 — the Pause-All/resume cancellation cascade, root ──
     *  fix): the old hold-open test counted ONLY mid-stream parks
     *  (pausedSends). Files paused while still WAITING — the overwhelming
     *  majority after a Pause All, since only the one in-flight file ever
     *  parks — hold their pause in the ENGINE (pauseRequested) and their
     *  deferral in the dispatch loop's deferred set, and were completely
     *  invisible to that test. Resuming and completing the single parked
     *  item then let finishSession fire while its siblings were still
     *  deliberately paused: the session flipped terminal
     *  ("done_with_errors"), releaseSessionIndexes orphaned every sibling's
     *  row ownership, and sendOneFile's terminal-session guard resolved each
     *  subsequently resumed sibling CANCELLED — the reported "resume the
     *  first item after Pause All and all remaining items become
     *  cancelled". The decision now lives in NativeSessionPolicy (pure,
     *  JVM-pinned): the session stays open until its work is GENUINELY
     *  finished — nothing parked, nothing paused, and no file still queued,
     *  active or engine-live. The dispatcher's own tail (deferred empty +
     *  jobs.awaitAll) is the natural reacher of that state; the outside
     *  callers (resumeFile's and cancelPausedSendIfParked's tails) merely
     *  re-check it. */
    private suspend fun finishSessionIfNotHeld(session: Session) {
        val ctx = session.appContext ?: return
        val parkedHere = pausedSends.entries.count { it.value.first === session }
        val held = com.lumidrop.x.transfer.NativeSessionPolicy.sessionHeldOpen(
            fileStates = session.files.map { (it.transferId ?: "") to it.state.name },
            parkedCount = parkedHere,
            rowPaused = { tid -> TransferManager.isPaused(tid) },
            rowLive = { tid ->
                TransferManager.get(tid)?.let {
                    it.status == TransferManager.Status.PENDING || it.status == TransferManager.Status.ACTIVE
                } == true
            })
        if (held) {
            session.heldOpenByPause = true
            return
        }
        session.heldOpenByPause = false
        finishSession(session, ctx)
    }

    /**
     * Kicks off one push per peer. `items` is exactly what the host checked
     * in the mixed target picker — the very same PreviewItem list the
     * browser-target (Quick-Share) leg of the same send already uses, so a
     * mixed selection enumerates identically on both legs.
     *
     * GOAT (Part 1 correction #4 — maximum realistic incoming speed): split
     * into two phases. [announce] fires FIRST, for every peer, using only
     * data [items] already has resolved (name/isFolder) — zero filesystem/
     * SAF access, so it goes out essentially the instant this is called,
     * regardless of selection size. Manifest building — which walks SAF
     * DocumentFile trees and, even parallelized (see buildManifest), still
     * takes some real time for a large folder — continues in the
     * background exactly as before and is delivered as a follow-up the
     * moment it's ready. The receiver's Accept/Decline sheet no longer
     * waits on this at all; only the FULL per-file details (and therefore
     * the ability to actually stream bytes) still do, which is exactly the
     * part that genuinely can't be skipped without risking transfer
     * correctness.
     */
    // GOAT (Phase 1 continuation, item 2): items Auto Send is holding
    // because no eligible native peer is discovered yet. Each is already
    // visible in the Transfers/Active tab as PENDING (see
    // TransferManager.startPendingSend) — this map is purely this
    // object's own bookkeeping for WHEN to actually fire the real send,
    // keyed by that same pending id so it can be quietly superseded
    // (never shown as Cancelled/Failed — see cancelPendingSend) the
    // instant a peer shows up, rather than mutated in place.
    private val waitingForPeer = ConcurrentHashMap<String, WaitingBatch>()
    @Volatile private var waitingListenerRegistered = false

    /** Entry point for Auto Send (see SendFragment.stageOrSendItems):
     *  sends immediately if any native peer is currently discovered,
     *  otherwise holds every item as a visibly-"Waiting" PENDING transfer
     *  until NativeDiscovery reports a peer, then sends the whole
     *  still-waiting batch together the moment one does. Manual "Send to"
     *  deliberately keeps calling [sendToPeers] directly — a person who
     *  opened the target picker already knows a peer is there, so there's
     *  nothing to wait for and no reason to route through this. */
    // ── GOAT-PASS 14 (Phase 1, §1.3): one parked "Waiting for device" batch. ──
    // Auto Send with nothing connected parks the send until a peer or browser
    // appears. In the classic mode each item parks as its own row; in ZIP mode
    // the WHOLE batch parks as ONE row (the archive's name), preserving the
    // per-operation batch boundary — two separate Auto Send operations never
    // merge into one archive (each parks its own entry, each drains as its
    // own send).
    private class WaitingBatch(val items: List<PreviewItem>, val zipMode: Boolean)

    fun sendOrQueueForPeer(context: Context, items: List<PreviewItem>, zipMode: Boolean = false) {
        if (items.isEmpty()) return
        val peers = NativeDiscovery.snapshot()
        if (peers.isNotEmpty()) { sendToPeers(context, peers, items, zipMode = zipMode); return }
        val appCtx = context.applicationContext
        if (zipMode) {
            // One row for the whole batch — the archive's name, unknown size
            // (the synthesized total is unknowable until streamed).
            // ── GOAT-PASS 15 (§3.4): zipStream=true — a streamed archive is
            // never pausable on either side (see Transfer.isZipStream).
            val usable = com.lumidrop.x.transfer.usableZipBatchItems(items)
            if (usable.isNotEmpty()) {
                waitingForPeer[TransferManager.startPendingSend(
                    com.lumidrop.x.transfer.zipBatchFileName(usable), -1L, zipStream = true)] = WaitingBatch(usable, true)
            }
        } else {
            for (item in items) {
                waitingForPeer[TransferManager.startPendingSend(item.name, item.size)] = WaitingBatch(listOf(item), false)
            }
        }
        if (!waitingListenerRegistered) {
            synchronized(this) {
                if (!waitingListenerRegistered) {
                    waitingListenerRegistered = true
                    NativeDiscovery.addListener(object : NativeDiscovery.Listener {
                        override fun onPeersChanged(peers: List<NativeDiscovery.NativePeer>) {
                            if (peers.isEmpty() || waitingForPeer.isEmpty()) return
                            // GOAT (Phase 1 continuation, item 3): notifyListeners()
                            // fires from more than one coroutine (a peer-beacon-
                            // received path and a periodic staleness sweep), so two
                            // near-simultaneous calls here are a real possibility,
                            // not just a theoretical one. Without this lock, both
                            // could pass the emptiness check above, both snapshot
                            // the same still-full waitingForPeer, and both call
                            // sendToPeers with the same items -- a real duplicate
                            // send, not just a duplicate no-op. Synchronizing on
                            // the map itself (rather than a separate lock object)
                            // needs no new field and is held only for the cheap
                            // snapshot-and-clear below, never across the actual
                            // network call.
                            val drained = synchronized(waitingForPeer) {
                                if (waitingForPeer.isEmpty()) return
                                val snap = HashMap(waitingForPeer)
                                waitingForPeer.clear()
                                snap
                            }
                            // Phase 8 (§8.4 — cancelled-send resurrection): an
                            // entry whose pending transfer the person already
                            // cancelled in the Transfers tab (cancelPendingSend
                            // removes it from TransferManager but nothing ever
                            // told THIS map) used to ride the drain into a real
                            // sendToPeers anyway — the file the user explicitly
                            // cancelled going out the moment any peer appeared.
                            // Filtering the drain by liveness (is the PENDING
                            // row still there, exactly as this map left it?)
                            // means a cancelled entry is simply dropped, and
                            // only genuinely-still-waiting items fire.
                            //
                            // Pass-8 follow-up (auto-send no-device fix): the
                            // parked rows are NO LONGER cancelled up front.
                            // sendToPeers now receives the parked id per item
                            // and resolves it itself — superseded quietly once
                            // the item's real per-file rows exist, FAILED
                            // visibly when the item can no longer resolve, and
                            // left Waiting untouched when the send never got
                            // that far — so a failed drain can never make a
                            // parked batch silently vanish without a transfer.
                            val live = drained.entries.filter { TransferManager.isPendingSendAlive(it.key) }
                            if (live.isEmpty()) return
                            // ── GOAT-PASS 14 (Phase 1, §1.3): the drain honors ──
                            // each entry's OWN batch boundary. Classic entries still
                            // merge into one session exactly as before; a ZIP-mode
                            // entry is its OWN sendToPeers call (one archive, one
                            // pre-registered row resolved by its own manifest) —
                            // two separately parked Auto Send operations can never
                            // be combined into one archive.
                            val classic = live.filter { !it.value.zipMode }
                            if (classic.isNotEmpty()) {
                                sendToPeers(appCtx, peers,
                                    classic.flatMap { it.value.items },
                                    parkedIds = classic.map { it.key })
                            }
                            for (entry in live.filter { it.value.zipMode }) {
                                sendToPeers(appCtx, peers, entry.value.items,
                                    parkedIds = listOf(entry.key), zipMode = true)
                            }
                        }
                    })
                }
            }
        }
    }

    /** Pass-8 (§3/§11 — the "18 rows for 9 files" fix, browser leg): a
     *  batch Auto Send parked because NOTHING was connected now also fires
     *  the moment a BROWSER session appears — not only when a native peer's
     *  UDP beacon shows up (which was the only drain trigger before, so a
     *  batch parked with only a browser around waited forever as duplicate
     *  "Waiting" rows next to nothing). Called from LumiDropServer the
     *  first time a given IP is seen as a live browser session. Routes each
     *  still-live parked item to that ONE client as a TARGETED Quick-Share
     *  send (the receiver's own Auto-Accept decides, previewer stays closed
     *  until acceptance — every existing browser-side guarantee intact),
     *  quietly superseding the parked SEND rows exactly the way a native
     *  peer drain does.
     *
     *  Pass-8 follow-up (auto-send no-device fix): a parked row is only
     *  superseded AFTER its Quick-Share handoff actually succeeded — the
     *  old blind cancel-first ordering is exactly what made a failed
     *  handoff silently vanish the batch ("files disappear without being
     *  transferred"). An item whose routing throws keeps its Waiting row,
     *  so a later-arriving device can still take it. */
    fun drainWaitingToBrowser(server: com.lumidrop.x.server.LumiDropServer?, ip: String) {
        if (server == null || waitingForPeer.isEmpty()) return
        val drained = synchronized(waitingForPeer) {
            if (waitingForPeer.isEmpty()) return
            val snap = HashMap(waitingForPeer)
            waitingForPeer.clear()
            snap
        }
        val live = drained.entries.filter { TransferManager.isPendingSendAlive(it.key) }
        if (live.isEmpty()) return
        val targets = setOf(ip)
        var handedOff = 0
        for ((parkedId, batch) in live) {
            try {
                if (batch.zipMode) {
                    // ── GOAT-PASS 14 (Phase 1, §1.3): a parked ZIP batch hands ──
                    // off as ONE Quick-Share archive offer (the receiver's
                    // existing incoming mechanism decides), preserving its own
                    // batch boundary — never merged with another parked batch.
                    server.addZipBatchToQuickShare(batch.items, targets)
                } else for (item in batch.items) {
                    if (item.textContent != null) server.addTextToQuickShare(item.textContent, targets)
                    else if (item.isFolder) server.addFolderToQuickShare(item.path, item.uri, item.name, targets,
                        // ── Pass-36 Part 2 (#4): the staged hidden eligibility rides ──
                        // the handoff too — the browser's ZIP of this folder honors the
                        // same hidden-exclusion rule the native push does.
                        includeHiddenChildren = item.includeHiddenChildren,
                        // ── GOAT-PASS 14 (§2.1): the staged child removals ride the ──
                        // handoff too — see SendFragment.pushItemLive.
                        excludedChildPaths = item.excludedChildPaths)
                    else server.addToQuickShare(item.path, item.uri, item.name, item.size, item.mime, targets)
                }
                // Handoff landed (a pre-registered Waiting row per target
                // now exists via preRegisterQuickShareSends) — the parked
                // placeholder row has done its job.
                TransferManager.cancelPendingSend(parkedId)
                handedOff++
            } catch (e: Exception) {
                // This item keeps its parked Waiting row — a later device
                // can still take it. Re-park it for the next drain.
                if (TransferManager.isPendingSendAlive(parkedId)) {
                    waitingForPeer[parkedId] = batch
                }
            }
        }
    }

    fun sendToPeers(context: Context, peers: List<NativeDiscovery.NativePeer>, items: List<PreviewItem>,
                    parkedIds: List<String>? = null,
                    // ── GOAT-PASS 14 (Phase 1, §1.1/§1.3): ZIP mode — the whole ──
                    // batch goes out as ONE streamed archive. The manifest carries
                    // ONE entry (the zip, size unknown), the receiver's incoming
                    // sheet shows ONE file row, and the sender's Active tab holds
                    // ONE row per peer — never N unrelated transfer items. The
                    // batch boundary is the caller's captured snapshot; the flag
                    // rides the WaitingBatch park too, so a drained Auto Send
                    // keeps its own archive.
                    zipMode: Boolean = false) {
        if (peers.isEmpty() || items.isEmpty()) return
        val callStartMs = System.currentTimeMillis()
        val appCtx = context.applicationContext
        val rootLabel = buildRootLabel(items)
        val deviceId = NativeDiscovery.myDeviceId(appCtx)
        val myName = NativeDiscovery.myDisplayName(appCtx)
        val myPort = com.lumidrop.x.settings.SettingsManager.getPort(appCtx)

        val sessions = peers.map { peer -> Session(peer, rootLabel) }
        // ── Pass-16 (pause-yield): every session keeps the application context ──
        // it was launched with, so a paused file can be relaunched (and the
        // session finished) later without threading the context through.
        for (s in sessions) s.appContext = appCtx
        // ── Pass-16: the announce carries each TEXT item's content too, so ──
        // the receiver's placeholder phase renders the real message card
        // (snippet + Copy/Cancel) instead of a file-like container — the
        // reported "same text received twice sometimes arrives as a file
        // item" was exactly the placeholder shape.
        // ── GOAT-PASS 14 (§1.3): in ZIP mode the announce presents the ONE ──
        // archive (a plain file shape) — the batch's individual items never
        // surface as separate incoming rows, and texts ride inside the
        // archive rather than as message cards.
        val announceItems = if (zipMode) {
            val usable = com.lumidrop.x.transfer.usableZipBatchItems(items)
            if (usable.isEmpty()) emptyList() else listOf(AnnounceItem(
                com.lumidrop.x.transfer.zipBatchFileName(usable), false, false, null))
        } else items.map {
            AnnounceItem(it.name, it.isFolder, it.textContent != null,
                if (it.textContent != null) it.textContent else null)
        }
        for (session in sessions) {
            sessionById[session.id] = session
            scope.launch { announce(session, deviceId, myName, myPort, announceItems) }
        }

        scope.launch {
            pruneRetryIndex()
            // ── FINAL PASS 4 (§3 — the placeholder keep-alive). By protocol ──
            // design the sender transmits NOTHING between the announce and
            // the request POST (the 700 ms decision polls only begin after
            // the request POST answers), so during the manifest build the
            // receiver's placeholder record has no liveness signal at all —
            // and the ZIP manifest build (buildZipBatchManifest: recursive
            // resolveTree walk + a per-loose-file openStream validation) is
            // the longest such window of any send type. A gentle keep-alive
            // poll now runs for exactly that window: one tiny
            // /api/native/push/status?id=<sessionId> GET every
            // PLACEHOLDER_KEEPALIVE_POLL_MS, the SAME endpoint the decision
            // polls use — the receiver bumps lastActivityAt for it exactly
            // like any poll, so the placeholder sweeps (both the
            // placeholder TTL branch and the undecided branch) see a live
            // sender and never age a healthy in-flight send out. The poll
            // stops the moment the manifest is built (or the build fails),
            // and a completely unreachable peer costs nothing: each attempt
            // is one short GET with its own timeout, and the request POST's
            // own retry ladder owns the recovery either way.
            val keepAlive = launch {
                try {
                    while (isActive) {
                        delay(PLACEHOLDER_KEEPALIVE_POLL_MS)
                        try {
                            getJson(appCtx, "${sessions.firstOrNull()?.let { baseUrlOf(it) } ?: return@launch}/api/native/push/status?v=4&id=${sessions.firstOrNull()?.id ?: return@launch}")
                        } catch (e: Exception) { /* the build window has no recovery duty of its own */ }
                    }
                } catch (e: Exception) { /* cancelled with the parent */ }
            }
            val manifestStartMs = System.currentTimeMillis()
            val manifest = try {
                if (zipMode) buildZipBatchManifest(appCtx, items) else buildManifest(appCtx, items)
            } finally {
                // The manifest window is over (built or failed) — the
                // placeholder keep-alive's job is done; the ordinary request
                // POST + 700 ms decision polls take over from here.
                keepAlive.cancel()
            }
            val manifestMs = System.currentTimeMillis() - manifestStartMs
            // Pass-8 follow-up (auto-send no-device fix — the "files
            // disappear without being transferred" root): when this send
            // drains a batch that was parked as "Waiting for device" rows,
            // those parked rows are resolved HERE, keyed by what actually
            // happened to their item — never cancelled up front by the
            // drain itself:
            //  - item contributed (resolved, or text): its real per-file
            //    rows exist below, so the parked row is quietly superseded
            //    (cancelPendingSend — the same treatment a live send always
            //    gave it).
            //  - item failed to resolve (deleted/moved/permission revoked
            //    while parked): the parked row FAILS visibly — a reason on
            //    screen, not a silent vanish.
            //  - item was cancelled by the person while parked:
            //    isPendingSendAlive already says no, and that decision
            //    stands untouched.
            // A session-level failure AFTER this point (peer unreachable at
            // the manifest POST) fails the real rows via recordBatchFailure,
            // so every path out of a drain leaves an honest row behind.
            if (parkedIds != null) {
                for (i in items.indices) {
                    val tid = parkedIds.getOrNull(i) ?: continue
                    if (!TransferManager.isPendingSendAlive(tid)) continue
                    if (manifest.perItemOk.getOrNull(i) == true) {
                        TransferManager.cancelPendingSend(tid)
                    } else {
                        TransferManager.fail(tid)
                    }
                }
            }
            val topLevelFolders = manifest.dirs.filter { !it.contains('/') }.toSet()
            for (session in sessions) {
                if (manifest.isEmpty) {
                    // Every selected item failed to resolve (rare — e.g. a
                    // permission revoked between selection and send). The
                    // peer was already told something was coming via
                    // announce; tell it to drop that placeholder rather
                    // than leaving it permanently stuck showing "preparing…".
                    session.status = "error"
                    withdrawAnnounce(session)
                    continue
                }
                session.dirPaths = manifest.dirs
                session.textSpecs = manifest.texts
                if (topLevelFolders.isNotEmpty()) {
                    session.groupIdByTopLevel = topLevelFolders.associateWith { name ->
                        TransferManager.startGroup(name, TransferManager.Direction.SEND,
                            session.peer.ip, session.peer.displayName, TransferManager.Source.NATIVE_PUSH)
                    }
                    session.groupIdByTopLevel.values.forEach { groupSessionIndex[it] = session }
                }
                // GOAT (Phase 1 continuation, item 1 -- sender-side folder
                // progress): pre-registers EVERY file as a PENDING
                // transfer right now, all at once, with the real peer info
                // and its correct groupId already known -- mirroring
                // registerPendingTransfersFor on the receiving side, which
                // is exactly why that side's folder progress was already
                // correct while this side wasn't. Without this,
                // sendOneFile only ever created a file's Transfer object
                // (via TransferManager.start) the moment ITS OWN turn came
                // up under MAX_CONCURRENT_FILES' semaphore gate -- so
                // membersOf(gid) reflected only however many files had
                // been dispatched so far, not the folder's real, whole
                // file count, at any moment before the very last file
                // started. That's what showed a constantly-growing, wrong
                // "total files" figure, and could even make the group
                // briefly vanish from activeSnapshot() entirely: if every
                // file dispatched so far happened to finish in the gap
                // before the concurrency gate let the next one start,
                // membersOf(gid) was briefly empty. sendOneFile claims
                // these ids via TransferManager.claimPendingSend rather
                // than minting fresh ones.
                session.files.addAll(manifest.files.map { spec ->
                    val gid = session.groupIdByTopLevel[spec.relPath.substringBefore('/')]
                    val tid = TransferManager.startPendingSend(spec.relPath, spec.size, gid,
                        session.peer.ip, session.peer.displayName, spec.node.localPath,
                        // ── GOAT-PASS 15 (§3.4): an unknown-size entry is the
                        // streamed zip-batch archive — mark its one row so no
                        // surface ever offers Pause on a live-synthesized
                        // archive (see Transfer.isZipStream).
                        zipStream = spec.size < 0L)
                    tidSessionIndex[tid] = session
                    FileProgress(spec).also { it.transferId = tid }
                })
                // ── Pass-22 (#6 — a dead session can never strand its rows on ────
                // "Waiting"): the session coroutine is now armored end-to-end.
                // A launch that dies on ANY unexpected exception resolves the
                // session's still-PENDING rows honestly FAILED right there
                // (the sender-side twin of the receiver's own sweeps) instead of
                // leaving them as the reported indefinite "Waiting" with the
                // receiver sitting equally idle; liveness is tracked so the
                // TransferManager backstop sweep can tell a live session's rows
                // from truly orphaned ones.
                scope.launch {
                    liveSessionIds.add(session.id)
                    try {
                        runSession(session, deviceId, myName, myPort, appCtx)
                    } catch (e: Exception) {
                        session.status = "error"
                        try { resolveOrphanedSessionRows(session) } catch (e2: Exception) { /* never in the catch path */ }
                    } catch (e: Error) {
                        // ── GOAT-PASS 10 (§8 — the armor must catch EVERYTHING): ──
                        // an Error (OOM foremost — a large batch's manifest
                        // build or a deep decode can raise one on a memory-
                        // pressured device) used to escape this handler
                        // entirely, leaving the session's still-PENDING rows
                        // as the exact reported indefinite "Waiting" (the
                        // 15-minute orphan sweep would eventually resolve
                        // them, but the user watches the tab NOW). Resolving
                        // the rows in-place here is byte-identical to the
                        // Exception arm; the Error itself is then rethrown so
                        // the process-level behavior of a genuine OOM is
                        // unchanged.
                        session.status = "error"
                        try { resolveOrphanedSessionRows(session) } catch (e2: Exception) { /* never in the catch path */ }
                        throw e
                    } finally {
                        liveSessionIds.remove(session.id)
                    }
                }
            }
        }
    }

    /** Best-effort: tells the peer to drop the placeholder an earlier
     *  [announce] created for [session], for the rare case nothing ever
     *  follows it up (see the empty-manifest branch in sendToPeers). Never
     *  surfaced to the host as its own error — recordBatchFailure isn't
     *  called here since session.files/textSpecs are empty at this point
     *  anyway (see its own totalItems==0 guard); there is nothing
     *  meaningful to show as "failed" beyond the fact nothing was ever
     *  actually queued. */
    private suspend fun withdrawAnnounce(session: Session) {
        try {
            var conn: HttpURLConnection? = null
            try {
                val url = "${baseUrlOf(session)}/api/native/push/cancel?id=${session.id}"
                conn = (com.lumidrop.x.utils.PeerHttp.open(session.appContext, url) as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 3000; readTimeout = 3000
                }
                conn.responseCode
            } finally { conn?.disconnect() }
        } catch (e: Exception) { /* peer already gone / unreachable — nothing more to do */ }
    }

    /** ── FULL FINAL PASS 2 (§4): withdraws a push the sender is abandoning ──
     *  AFTER its manifest landed (decision-wait timeout), by the requestId the
     *  receiver actually knows it by — the same /api/native/push/cancel
     *  endpoint withdrawAnnounce uses for the pre-manifest placeholder. The
     *  receiver resolves the record and every still-pending pre-registered row
     *  on the spot, so an abandoned first transfer can never linger as
     *  accepted-but-never-streamed "Waiting" rows on the receiving side while
     *  the sender has already moved on. Best-effort, exactly like its sibling:
     *  an unreachable peer's own dead-sender sweep remains the backstop. */
    private suspend fun withdrawPush(session: Session, reqId: String) {
        try {
            var conn: HttpURLConnection? = null
            try {
                val url = "${baseUrlOf(session)}/api/native/push/cancel?id=$reqId"
                conn = (com.lumidrop.x.utils.PeerHttp.open(session.appContext, url) as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 3000; readTimeout = 3000
                }
                conn.responseCode
            } finally { conn?.disconnect() }
        } catch (e: Exception) { /* peer already gone — its own sweep is the backstop */ }
    }

    /** Fast, zero-I/O first contact — see sendToPeers' doc comment. Failure
     *  here (peer unreachable, timeout) is silently tolerated: the
     *  follow-up in runSession makes the same connection attempt moments
     *  later and is what actually surfaces a real error to the host if the
     *  peer genuinely can't be reached, so failing loudly twice for the
     *  same underlying problem would just be noise. */
    private suspend fun announce(session: Session, deviceId: String, myName: String, myPort: Int, items: List<AnnounceItem>) {
        val startMs = System.currentTimeMillis()
        val body = JSONObject().apply {
            put("id", session.id)
            put("deviceId", deviceId)
            put("name", myName)
            put("myPort", myPort)
            put("topLevel", JSONArray().apply {
                // ── Pass-16: text items carry their CONTENT in the announce ──
                // (the sender has it with zero I/O) so the receiver's
                // placeholder phase renders the true message card with a real
                // snippet — never the generic file container.
                for (it in items) put(JSONObject().put("name", it.name).put("isFolder", it.isFolder).put("isText", it.isText)
                    .apply { if (it.isText && it.text != null) put("text", it.text) })
            })
        }
        // GOAT-PASS 9 (§14): resolved ONCE, before the try — the catch block
        // below logs against it too.
        val announceBase = baseUrlOf(session)
        try {
            val resp = postJson(session.appContext, "$announceBase/api/native/push/announce", body)
            val elapsed = System.currentTimeMillis() - startMs
            if (resp == null) {
            } else if (elapsed > 1000) {
            } else {
            }
        } catch (e: Exception) {
        }
    }

    /** Pass-8 (§10): drops index entries belonging to [session] once it
     *  reaches a terminal state. [keepRetry] is true for a session that ended
     *  with failures — the Transfers tab still offers Retry for those files,
     *  which is exactly what retryIndex exists for — so only the cancel/
     *  group indexes are released there. A fully-clean session ("done" with
     *  zero failures, rejected, or errored with nothing dispatched worth
     *  retrying through this index) releases all three. Without this, a
     *  300-file push left 300 retryIndex + 300 tidSessionIndex entries
     *  behind until an unrelated LATER send tripped the 500-entry prune. */
    private fun releaseSessionIndexes(session: Session, keepRetry: Boolean) {
        if (!keepRetry) retryIndex.entries.removeAll { it.value.first === session }
        tidSessionIndex.entries.removeAll { it.value === session }
        groupSessionIndex.entries.removeAll { it.value === session }
        sessionById.remove(session.id)
    }

    /** Re-attempts one previously failed/cancelled file within its original
     *  session — Transfers tab Retry action for a Direction.SEND row. */
    fun retryFile(tid: String) {
        val (session, fp) = retryIndex[tid] ?: return
        if (fp.state == FileState.ACTIVE) return
        fp.state = FileState.QUEUED
        scope.launch { sendOneFile(session, fp) }
    }

    // ── Pass-15 (ghost-item ask-to-resend): the UI hook for asking the ────────
    // SENDER'S user whether a from-scratch resend should happen. Wired by
    // MainActivity (the only shell that owns a dialog-capable Activity
    // context across every tab); the hook receives the file name, the target
    // device's name, and a one-shot answer callback. While no hook is set —
    // or the dialog never gets answered within ASK_RESEND_TIMEOUT_MS — the
    // answer is CANCEL: the receiving side deleted this file's data on
    // purpose, so silently re-sending it is never the fallback. */
    @Volatile var onAskResendFromScratch:
        ((fileName: String, peerName: String, answer: (approved: Boolean) -> Unit) -> Unit)? = null
    private val ASK_RESEND_TIMEOUT_MS = 2 * 60_000L

    /** Pass-15: suspends until the user answers the resend question (or the
     *  timeout lapses — treated as a refusal). Polls rather than suspending
     *  on a primitive so a hook that never calls back (an Activity dying
     *  mid-dialog) can never wedge the session forever. */
    private suspend fun askResendFromScratch(fileName: String, peerName: String): Boolean {
        val hook = onAskResendFromScratch ?: return false
        val answered = java.util.concurrent.atomic.AtomicInteger(0)   // 0 none / 1 yes / 2 no
        try {
            hook(fileName, peerName) { approved -> answered.set(if (approved) 1 else 2) }
        } catch (e: Exception) {
            return false
        }
        val deadline = System.currentTimeMillis() + ASK_RESEND_TIMEOUT_MS
        while (answered.get() == 0 && System.currentTimeMillis() < deadline) delay(250)
        return answered.get() == 1
    }

    /** Best-effort propagation of a LOCAL pause/resume/cancel (this device
     *  is the sending end of a native push) out to the receiver, so both
     *  Transfers tabs agree — the sending-side counterpart of
     *  LumiDropServer.propagateNativeControl. Reuses retryIndex, which
     *  already maps a local tid back to exactly the (session, requestId,
     *  relPath) a control call needs; no separate bookkeeping required.
     *
     *  ── Pass-19 (Pause-All state sync): retryIndex only ever holds files
     *  that have actually STARTED STREAMING, so a pause/resume on a still-
     *  WAITING row used to silently no-op — the receiver never heard about
     *  it, and with "Pause all" the sender showed every item Paused while
     *  the receiver showed only the in-progress one Paused and the rest
     *  Waiting. The tidSessionIndex (which covers EVERY pre-registered
     *  send row, waiting ones included — see its own doc comment) now
     *  resolves those too, so every paused/resumed item propagates,
     *  whatever its dispatch state. */
    fun propagateControl(tid: String, action: String) {
        val (session, fp) = retryIndex[tid]
            ?: tidSessionIndex[tid]?.let { s ->
                val found = s.files.firstOrNull { it.transferId == tid } ?: return
                s to found
            } ?: return
        val reqId = session.requestId ?: return
        scope.launch {
            var conn: HttpURLConnection? = null
            try {
                val encodedPath = URLEncoder.encode(fp.spec.relPath, "UTF-8").replace("+", "%20")
                val url = "${baseUrlOf(session)}/api/native/push/control?id=$reqId&path=$encodedPath&action=$action"
                conn = (com.lumidrop.x.utils.PeerHttp.open(session.appContext, url) as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 4000; readTimeout = 4000
                }
                conn.responseCode
            } catch (e: Exception) { /* peer unreachable / already finished — best-effort only */ }
            finally { conn?.disconnect() }
        }
    }

    /** Pass-19 (cross-device pause sync): resolves a control call that
     *  arrived at THIS device's server addressed by the receiver's push id
     *  + relPath back to the local pre-registered send row — including rows
     *  that have never been dispatched (their control links are only
     *  registered at dispatch time, so nativeControlByKey can't reach
     *  them). Lets the RECEIVER's pause/resume of a still-Waiting file
     *  reach the SENDER's row and engine, closing the last one-sided gap
     *  in the Pause-All state sync. Null when no live session answers to
     *  that push id (already finished/pruned — the caller no-ops). */
    fun tidForControl(pushId: String, relPath: String): String? {
        val session = sessionById.values.firstOrNull { it.requestId == pushId } ?: return null
        return session.files.firstOrNull { it.spec.relPath == relPath }?.transferId
    }

    /** Pass-13 (individual transfer cancellation): the PER-FILE counterpart
     *  of [propagateCancelForTid] — cancelling ONE row in the Active tab must
     *  cancel exactly that file on BOTH ends, never the whole session.
     *  [propagateCancelForTid]'s whole-session teardown (every sibling row
     *  CANCELLED + one scope=push withdrawal on the receiver) was designed
     *  around the single-file push of its era and is exactly what made the
     *  X button on one row of a multi-file send cancel EVERYTHING — the
     *  reported critical bug. Whole-session teardown remains reserved for
     *  Cancel All, folder-group cancel, and receiver-initiated scope=push.
     *
     *  What this does for the ONE file whose local tid is [tid] (the caller
     *  has already called TransferManager.requestCancel(tid), so the local
     *  row is final):
     *   1. Held for a peer that never appeared (sendOrQueueForPeer)? Just
     *      drop that one entry — nothing was ever announced for it.
     *   2. Otherwise resolve (session, file) via the session indexes, mark
     *      the FileProgress NOT_DONE so the dispatch loop's own
     *      isCancelled(preAssignedId) check skips it (belt-and-braces: the
     *      cancelled row itself already answers that check), and send the
     *      receiver ONE per-file control POST — which (see
     *      LumiDropServer.apiNativePushControl's pass-13 per-file case)
     *      cancels its pre-registered row for that path, declines just that
     *      one undecided top-level item in the incoming sheet, and cleans
     *      the file's staging partial. Siblings and the session itself are
     *      untouched — the receiver keeps every other item and the sender
     *      keeps sending them.
     *  A file already streaming also stops locally: requestCancel breaks the
     *  sender's own stream loop on its next chunk check.
     *
     *  Returns true when the tid resolved to something this could act on. */
    fun propagateSingleFileCancel(tid: String): Boolean {
        // (1) Parked for a peer that never appeared — quiet per-item drop.
        if (waitingForPeer.remove(tid) != null) return true
        // ── Pass-16 (pause-yield): a PAUSED file's cancel must also purge its ──
        // parked registry entry (and let its held-open session finish) —
        // the parked coroutine no longer exists to notice the cancel.
        // ── Pass-20 (the parked early-return skipped the receiver) ───────────
        // This used to `return true` right here, which cancelled the file
        // LOCALLY but never told the RECEIVER: its receiving row stayed
        // parked-paused forever (a paused row is exempt from every stale
        // sweep by design), the exact "cancel a paused item and the other
        // side never hears" hole. The purge is only the LOCAL half of the
        // cancel — the per-file scope=file POST below is the other half,
        // so fall through to it instead of returning.
        cancelPausedSendIfParked(tid)
        // (2) Resolve the session + exact FileProgress for this tid. The
        // session index covers EVERY pre-registered send row (see its own
        // doc comment) — waiting files included, which is exactly the case
        // the old per-file propagateControl could never reach.
        val session = tidSessionIndex[tid] ?: return false
        val fp = session.files.firstOrNull { it.transferId == tid } ?: retryIndex[tid]?.second ?: return false
        if (fp.state == FileState.ACTIVE || fp.state == FileState.QUEUED) fp.state = FileState.NOT_DONE
        val reqId = session.requestId ?: return true   // never announced/manifested — nothing to tell the receiver
        val relPath = fp.spec.relPath
        scope.launch {
            var conn: HttpURLConnection? = null
            try {
                val encodedPath = URLEncoder.encode(relPath, "UTF-8").replace("+", "%20")
                val url = "${baseUrlOf(session)}/api/native/push/control?id=$reqId&path=$encodedPath&action=cancel&scope=file"
                conn = (com.lumidrop.x.utils.PeerHttp.open(session.appContext, url) as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 4000; readTimeout = 4000
                }
                conn.responseCode
            } catch (e: Exception) { /* peer unreachable — local cancel already holds */ }
            finally { conn?.disconnect() }
        }
        return true
    }

    /** Pass-6 (R-H — a cancelled waiting send disappears EVERYWHERE):
     *  Resolves the Transfers tab's Cancel on a native-push SEND row to the
     *  push it belongs to and tears the whole thing down on BOTH ends:
     *
     *   1. Still waiting for a device (sendOrQueueForPeer holds it in
     *      waitingForPeer)? Just drop it from that map — it was never
     *      announced to anyone, so there is nothing to propagate; the
     *      row itself was already cancelled by the caller.
     *   2. Part of a live push: cancel EVERY sibling row this side
     *      (they all belong to the same one-push incoming card on the
     *      receiver, so one row's cancel means the offer is withdrawn,
     *      exactly like the folder-group path), and send the receiver
     *      ONE scope=push cancel — which (see LumiDropServer.cancelWholePush)
     *      resolves its pre-registered rows AND declines every undecided
     *      top-level item, dropping the push from its incoming sheet on the
     *      next tick. propagateControl's per-file call could never do this:
     *      retryIndex only knows files that already started streaming, so a
     *      still-WAITING row used to cancel here and yet keep showing as an
     *      incoming offer on the receiver — the exact reported bug.
     *  Text-only pushes have no file rows; their session is terminal almost
     *  immediately, so the early terminal-status return covers them. */
    fun propagateCancelForTid(tid: String) {
        // (1) Held for a peer that never appeared — quiet drop, nothing to tell.
        if (waitingForPeer.remove(tid) != null) return
        // (2) Belongs to a live push session — cancel the whole push, both ends.
        val session = tidSessionIndex[tid] ?: return
        if (session.status in TERMINAL_SESSION_STATES) return
        for (fp in session.files.toList()) {
            fp.transferId?.let { TransferManager.requestCancel(it) }
            if (fp.state == FileState.QUEUED) fp.state = FileState.NOT_DONE
        }
        session.status = "rejected"   // stops runSession's status poll loop from dispatching anything more
        val reqId = session.requestId
        scope.launch {
            if (reqId == null) {
                // Manifest never delivered (still announcing/connecting): the
                // receiver may still hold the announce PLACEHOLDER — withdraw
                // it so no "preparing…" ghost survives the cancel.
                withdrawAnnounce(session)
                return@launch
            }
            var conn: HttpURLConnection? = null
            try {
                val url = "${baseUrlOf(session)}/api/native/push/control?id=$reqId&action=cancel&scope=push"
                conn = (com.lumidrop.x.utils.PeerHttp.open(session.appContext, url) as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 4000; readTimeout = 4000
                }
                conn.responseCode
            } catch (e: Exception) { /* peer unreachable / already finished — best-effort only */ }
            finally { conn?.disconnect() }
        }
    }

    /** Pass-8 (§13): the RECEIVER cancelled this whole push — as the
     *  SENDER, tear the session down NOW: every still-live row resolves
     *  CANCELLED, the dispatch loop stops scheduling anything more (status
     *  leaves the non-terminal set), and undelivered files never fire.
     *  Called from LumiDropServer when a scope=push cancel arrives for a
     *  push id this device is the SENDER of (its own pendingNativePushes
     *  has no such record — the id is OUR session id).
     *  Returns true when a live session was found and torn down. */
    fun cancelSessionById(sessionId: String): Boolean {
        val session = sessionById[sessionId] ?: return false
        if (session.status in TERMINAL_SESSION_STATES) return false
        for (fp in session.files.toList()) {
            fp.transferId?.let { TransferManager.requestCancel(it) }
            if (fp.state == FileState.QUEUED) fp.state = FileState.NOT_DONE
            // ── Pass-16: a whole-session cancel purges every PAUSED entry ──
            // so the session can finish honestly.
            fp.transferId?.let { pausedSends.remove(it) }
        }
        session.status = "rejected"   // stops runSession's dispatch loop dead
        releaseSessionIndexes(session, keepRetry = false)
        sessionById.remove(sessionId)
        return true
    }

    /** Cancels an entire folder-send group on the peer in ONE request,
     *  rather than depending on propagateControl's per-file, retryIndex-
     *  gated notification, which only ever reaches a file that's actually
     *  been dispatched (see that function's own doc comment) -- for a
     *  folder that's mostly still queued when cancelled, that left most of
     *  the receiver's own pre-registered PENDING rows for it with no
     *  cancel signal ever reaching them, stuck showing the folder as still
     *  incoming after the sender had already stopped. scope=push tells
     *  apiNativePushControl to cancel every remaining file under this
     *  push id at once, not one path.
     *
     *  ── GOAT-PASS 9 (§8 — folder-cancel "reappeared at 99%"): this now ──
     *  tears the session down LOCALLY too, exactly like cancelSessionById.
     *  The old shape only fired the control POST and left session.status
     *  "sending", every still-QUEUED FileProgress dispatchable, and every
     *  parked paused send resumable — and because the dispatch pipeline's
     *  entry guards read the bare isCancelled() that goes blind 400 ms
     *  after each CANCELLED row auto-dismisses, every remaining child kept
     *  being admitted, probing the receiver, and (whenever the tombstone
     *  had lapsed or the control POST was still in flight) minting a fresh
     *  ACTIVE row under the SAME groupId via sendOneFile's start()
     *  fallback — the cancelled folder card reappearing and climbing
     *  toward 99%, one ghost child at a time. With "rejected" set here,
     *  the dispatch loops stop admitting anything, resumeFile refuses, and
     *  every member row (including FAILED/waiting-for-reconnection ones the
     *  UI's liveMembers filter never reached) is cancelled and tombstoned. */
    fun propagateGroupCancel(groupId: String) {
        val session = groupSessionIndex[groupId] ?: return
        if (session.status in TERMINAL_SESSION_STATES) return
        for (fp in session.files.toList()) {
            fp.transferId?.let { TransferManager.requestCancel(it) }   // idempotent — also tombstones WFR members so no auto-resume leg ever retries them
            if (fp.state == FileState.QUEUED) fp.state = FileState.NOT_DONE
            fp.transferId?.let { pausedSends.remove(it) }   // a parked member can never relaunch under a torn-down session
        }
        session.status = "rejected"   // stops runSession's dispatch loops dead; resumeFile() now refuses
        releaseSessionIndexes(session, keepRetry = false)
        sessionById.remove(session.id)
        val reqId = session.requestId
        if (reqId == null) {
            // Manifest never delivered (still announcing/connecting): the
            // receiver may still hold the announce PLACEHOLDER — withdraw it
            // so no "preparing…" ghost survives the cancel.
            scope.launch { withdrawAnnounce(session) }
            return
        }
        scope.launch {
            var conn: HttpURLConnection? = null
            try {
                val url = "${baseUrlOf(session)}/api/native/push/control?id=$reqId&action=cancel&scope=push"
                conn = (com.lumidrop.x.utils.PeerHttp.open(session.appContext, url) as HttpURLConnection).apply {
                    requestMethod = "POST"; connectTimeout = 4000; readTimeout = 4000
                }
                conn.responseCode
            } catch (e: Exception) { /* peer unreachable / already finished — best-effort only */ }
            finally { conn?.disconnect() }
        }
    }

    // Bounds retryIndex's lifetime growth over a long-running app session —
    // only entries whose session has reached a terminal state AND whose
    // file isn't mid-transfer are eligible, so an in-progress retry can
    // never be swept out from under itself.
    private fun pruneRetryIndex() {
        val terminal = setOf("done", "done_with_errors", "rejected", "error")
        if (retryIndex.size >= 500) {
            retryIndex.entries.removeAll { (_, pair) ->
                val (session, fp) = pair
                fp.state != FileState.ACTIVE && session.status in terminal
            }
        }
        // groupSessionIndex grows far slower (one entry per folder, not per
        // file) so 500 here is a generous bound, not a tuned one — same
        // terminal-state condition, no per-file ACTIVE check needed since a
        // group with any file still active also has a non-terminal session.
        if (groupSessionIndex.size >= 500) {
            groupSessionIndex.entries.removeAll { (_, session) -> session.status in terminal }
        }
    }

    // ── Manifest building — walks the exact same FileTreeNode abstraction
    //    (and the exact same de-dup/naming) that ZIP streaming uses, so a
    //    mixed browser+native send names/structures identically on both legs.

    // GOAT (transfer stability pass — instant incoming response): metadata
    // lookups (listFiles/length/lastModified) run concurrently, bounded by
    // this many at once. Unlike MAX_CONCURRENT_FILES below (which bounds
    // actual byte-streaming — CPU/network/battery-sensitive), these are
    // near-pure I/O-wait: each is a single Binder round-trip to a
    // DocumentsProvider (or a cheap local stat() for a plain file) that
    // spends almost all its time blocked waiting, not doing real work.
    // Walking a folder of hundreds of small files one property at a time
    // — as this used to do — is exactly what made the receiver wait
    // several seconds just to show its Accept/Decline sheet, since nothing
    // is sent to the peer (see runSession/apiNativePushRequest) until the
    // manifest below is fully built. 16 stays comfortably inside Android's
    // own binder thread-pool ceiling per process (usually ~16) instead of
    // firing hundreds of simultaneous IPCs that would just queue up again
    // on the far side anyway.
    private const val MAX_CONCURRENT_METADATA_FETCHES = 16

    /** One folder subtree node with every SAF/filesystem property already
     *  fetched — the output of [resolveTree]'s concurrent Phase A walk.
     *  Building this is where all the slow, parallelizable I/O happens;
     *  [flattenResolved] (Phase B) never touches SAF/the filesystem again. */
    private class ResolvedNode(
        val node: FileTreeNode, val isDirectory: Boolean,
        val length: Long, val lastModified: Long, val children: List<ResolvedNode>,
    )

    private sealed class ResolvedItem {
        class SingleFile(val node: FileTreeNode, val length: Long, val lastModified: Long) : ResolvedItem()
        class Folder(val node: FileTreeNode, val tree: ResolvedNode?) : ResolvedItem()
    }

    /**
     * Phase A for one folder's subtree: fetches every child listing and
     * every file's length/lastModified concurrently (bounded by [gate]),
     * making no naming/de-dup decisions yet — those depend on traversal
     * ORDER and stay strictly single-threaded in Phase B ([flattenResolved])
     * so today's exact, deterministic collision-naming is fully preserved.
     * A node that fails to resolve (a transient SAF/permission error) is
     * skipped rather than aborting the whole walk — losing one file out of
     * a few hundred beats failing an entire folder send over it.
     */
    private suspend fun resolveTree(node: FileTreeNode, gate: Semaphore): ResolvedNode? = coroutineScope {
        try {
            if (node.isDirectory) {
                val kids = gate.withPermit { node.children() }
                val resolvedKids = kids.map { child -> async { resolveTree(child, gate) } }.awaitAll().filterNotNull()
                ResolvedNode(node, true, 0L, 0L, resolvedKids)
            } else {
                val len = gate.withPermit { node.length() }
                val mtime = gate.withPermit { node.lastModified() }
                ResolvedNode(node, false, len, mtime, emptyList())
            }
        } catch (e: Exception) { null }
    }

    /** Phase B: the exact traversal/order/naming [flattenNode] (its
     *  pre-parallelization predecessor) always did — unchanged — just
     *  reading each node's already-fetched length/lastModified from Phase A
     *  instead of calling back into SAF for them. Purely in-memory and
     *  single-threaded, so uniqueTreePath's collision naming stays exactly
     *  as deterministic as it always was. Mirrors addZipNode's exact
     *  recursive shape (LumiDropServer) — same traversal order, same
     *  uniqueTreePath de-duplication — just collecting into flat lists
     *  instead of writing zip entries.
     *
     *  ── GOAT-PASS 14 (Phase 1, §2.1 — the exclusion ROOT CAUSE, native leg): ──
     *  the `excluded` parameter is GONE. It never worked: the check compared
     *  the walker's natural path ("<FolderName>/Sub/file.jpg" — the folder's
     *  own name included) against the exclusion set's ROOT-RELATIVE paths
     *  ("Sub/file.jpg" — exactly what the Queue's expand view and
     *  PreviewItem.excludedChildPaths carry), so no path ever matched and a
     *  sender's queue-time child removals were silently ignored on this leg.
     *  Exclusions are now applied UPSTREAM, in buildManifest, by pruning the
     *  resolved tree by its root-relative paths before this walk runs —
     *  order-independent, de-dup-independent, and provably the same rule the
     *  Queue displays. */
    private fun flattenResolved(rn: ResolvedNode, parentPrefix: String, seen: MutableSet<String>,
                                  outFiles: MutableList<FileSpec>, outDirs: MutableList<String>) {
        val naturalPath = parentPrefix + rn.node.name
        if (rn.isDirectory) {
            val dirPath = uniqueTreePath(seen, naturalPath + "/")
            outDirs.add(dirPath.removeSuffix("/"))
            for (child in rn.children) flattenResolved(child, dirPath, seen, outFiles, outDirs)
        } else {
            val filePath = uniqueTreePath(seen, naturalPath)
            outFiles.add(FileSpec(filePath, rn.length, rn.node, rn.lastModified))
        }
    }

    /** ── GOAT-PASS 14 (Phase 1, §2.1): returns [rn]'s subtree with every ────────
     *  excluded child PRUNED out — [excluded] holds ROOT-RELATIVE natural
     *  paths (the Queue's own namespace: a removed file by its own path, a
     *  removed subfolder with its entire subtree). Purely in-memory over the
     *  already-resolved snapshot (no second I/O pass); a fully-excluded root
     *  returns null and the caller drops the folder from the manifest. */
    private fun pruneResolved(rn: ResolvedNode, relPath: String, excluded: Set<String>): ResolvedNode? {
        if (com.lumidrop.x.transfer.ChildExclusions.isExcluded(relPath, excluded)) return null
        if (!rn.isDirectory) return rn
        val kept = rn.children.mapNotNull { c ->
            pruneResolved(c, if (relPath.isEmpty()) c.node.name else "$relPath/${c.node.name}", excluded)
        }
        return ResolvedNode(rn.node, true, 0L, 0L, kept)
    }

    private suspend fun buildManifest(context: Context, items: List<PreviewItem>): ManifestResult = coroutineScope {
        val gate = Semaphore(MAX_CONCURRENT_METADATA_FETCHES)

        // Phase A: resolve every non-text item concurrently — for a folder,
        // its entire metadata subtree via resolveTree; for a single file,
        // its length/lastModified directly. Order preserved 1:1 with
        // `items` (index i here always corresponds to items[i]), so Phase B
        // below can zip the two lists back together with no separate
        // lookup/keying needed. A null result means "this item contributes
        // nothing" — a text-only item (handled entirely in Phase B) or one
        // that failed to resolve at all, same as today's `?: continue`.
        val resolved: List<ResolvedItem?> = items.map { item ->
            async {
                if (item.textContent != null) return@async null
                // ── Pass-33 (#1): a MANIFEST folder (a media album) resolves ─────
                // from its staged member list — resolveTree is never called, no
                // physical directory is walked, and the pushed tree contains
                // precisely the images/videos the Media Picker displayed in
                // that album (each member's own real path/uri streams the
                // bytes). A null child node (a member whose file disappeared
                // after staging) is dropped from the tree, never fatal; an
                // entirely empty resolution leaves the folder as an empty dir
                // push, same as a physical folder that vanished.
                if (item.childManifest != null) {
                    val rootName = item.path?.let { java.io.File(it).name } ?: item.name
                    val kidNodes = ArrayList<FileTreeNode>()
                    val kidResolved = ArrayList<ResolvedNode>()
                    // ── GOAT-PASS 14 (Phase 1, §2.1): the album's queue-time ──
                    // removals are honored on the native leg too — an excluded
                    // member never enters the pushed tree (the same rule the
                    // Queue's expand view and the browser ZIP leg apply).
                    for (child in item.childManifest!!) {
                        if (!com.lumidrop.x.transfer.ChildExclusions.isKept(child.relPath, item.excludedChildPaths)) continue
                        val node = child.toNode(context) ?: continue
                        kidNodes.add(node)
                        kidResolved.add(ResolvedNode(node,
                            isDirectory = false,
                            length = try { node.length() } catch (e: Exception) { child.size },
                            lastModified = try { node.lastModified() } catch (e: Exception) { 0L },
                            children = emptyList()))
                    }
                    val rootNode = FileTreeNode.ManifestNode(rootName, kidNodes)
                    return@async ResolvedItem.Folder(rootNode, ResolvedNode(rootNode, true, 0L, 0L, kidResolved))
                }
                val node = resolveNode(context, item) ?: return@async null
                if (item.isFolder) ResolvedItem.Folder(node, resolveTree(node, gate))
                else {
                    val len = gate.withPermit { node.length() }
                    val mtime = gate.withPermit { node.lastModified() }
                    ResolvedItem.SingleFile(node, len, mtime)
                }
            }
        }.awaitAll()
        // Phase B: exactly today's sequential logic/order/naming, just
        // reading already-fetched metadata instead of making every single
        // property lookup wait its turn on SAF one at a time.
        val files = mutableListOf<FileSpec>()
        val dirs = mutableListOf<String>()
        val texts = mutableListOf<TextSpec>()
        val seen = mutableSetOf<String>()
        // Pass-8 follow-up: per-item outcome, parallel to `items` — see
        // ManifestResult.perItemOk's own doc comment.
        val perItemOk = BooleanArray(items.size)
        for ((index, item) in items.withIndex()) {
            val textContent = item.textContent
            if (textContent != null) {
                // A Quick-Share text/paste item — never file-backed, so it
                // can't go through resolveNode()/flattenResolved() at all.
                // Its whole content rides inside the manifest request
                // itself (see runSession); the receiving device copies it
                // straight to the clipboard, no file ever written (see
                // LumiDropServer.precreateNativePushDirs) — same as a text
                // send from a browser client.
                val rawName = item.name.ifBlank { "shared-text" }
                val fileName = if (rawName.contains('.')) rawName else "$rawName.txt"
                texts.add(TextSpec(uniqueTreePath(seen, fileName), textContent))
                perItemOk[index] = true
                continue
            }
            when (val r = resolved[index]) {
                is ResolvedItem.Folder -> {
                    // ── GOAT-PASS 14 (Phase 1, §2.1): the queue-time child ──
                    // removals are pruned out of the RESOLVED TREE here, by
                    // their ROOT-RELATIVE natural paths ("Subfolder/IMG.jpg") —
                    // exactly the namespace PreviewItem.excludedChildPaths and
                    // the Queue's expand view use. This replaces the old
                    // in-walk exclusion check, which compared against
                    // "<FolderName>/Subfolder/IMG.jpg" and therefore never
                    // matched anything (see flattenResolved's own doc).
                    val tree = r.tree?.let { t ->
                        if (item.excludedChildPaths.isEmpty()) t
                        else pruneResolved(t, "", item.excludedChildPaths)
                    }
                    tree?.let { flattenResolved(it, "", seen, files, dirs) }
                    perItemOk[index] = tree != null
                }
                is ResolvedItem.SingleFile -> {
                    // Single file — use the PreviewItem's OWN name as the
                    // destination filename rather than node.name (the
                    // underlying file's real on-disk name). Critical for
                    // shared installed apps: MainActivity's app picker
                    // already computes a friendly "<AppLabel>.apk" name,
                    // but the actual file on disk (ApplicationInfo.sourceDir)
                    // is always literally named base.apk on Android
                    // regardless of the app's display name — walking by
                    // node.name alone would silently lose that friendly
                    // name and send "base.apk" for every single app. Folder
                    // CONTENTS are unaffected; they still get their real
                    // nested names via flattenResolved above.
                    val displayName = item.name.ifBlank { r.node.name }
                    val uniqueName = uniqueTreePath(seen, displayName)
                    // ── Pass-16 (cross-sender resume, sender half): the 4 KB ──
                    // content fingerprint rides the manifest and every probe
                    // — mtime drifts between devices holding the same original
                    // file; content does not.
                    val fp = fileFingerprint(r.node, r.length)
                    files.add(FileSpec(uniqueName, r.length, r.node, r.lastModified, fp))
                    perItemOk[index] = true
                }
                null -> { /* failed to resolve — skip, same as today */ }
            }
        }
        ManifestResult(files, dirs, texts, perItemOk.toList())
    }

    // ── GOAT-PASS 14 (Phase 1, §1.3/§1.5/§2.1): the ZIP-mode manifest. ─────────
    // ONE FileSpec whose node is a FileTreeNode.ZipBatchNode — a virtual file
    // whose openStream() synthesizes the archive live (the server-side ZIP
    // streaming pattern, through the SHARED ZipTree walker). The batch is
    // SNAPSHOTTED here, at send time: folder trees resolve through the very
    // same resolveTree the ordinary manifest builder uses, the user's
    // queue-time child exclusions are pruned out of the resolved tree, and
    // everything is frozen into immutable SnapshotNodes — an already-started
    // transfer can never silently change its contents when the queue or a
    // source directory changes afterwards. Loose files are validated to exist
    // NOW (a vanished file fails the send truthfully instead of silently
    // vanishing from the archive); texts ride inside the archive as .txt
    // entries rather than as manifest text messages, so the receiver gets
    // exactly ONE incoming item.
    private suspend fun buildZipBatchManifest(context: Context, items: List<PreviewItem>): ManifestResult = coroutineScope {
        val gate = Semaphore(MAX_CONCURRENT_METADATA_FETCHES)
        val usable = com.lumidrop.x.transfer.usableZipBatchItems(items)
        if (usable.isEmpty()) return@coroutineScope ManifestResult(emptyList(), emptyList(), emptyList(), List(items.size) { false })
        val texts = mutableListOf<Pair<String, String>>()
        val roots = mutableListOf<FileTreeNode>()
        var anyUnresolvable = false
        // Resolve every member concurrently (folders via the metadata gate,
        // exactly like buildManifest's Phase A), then freeze.
        val resolved: List<FileTreeNode?> = usable.map { item ->
            async {
                val text = item.textContent
                if (text != null) return@async null   // collected below, not a root
                if (item.isFolder) {
                    if (item.childManifest != null) {
                        // A MANIFEST folder (media album): its members are fixed at
                        // staging — honor the queue-time exclusions on them, then
                        // wrap the kept kids in a ManifestNode (children frozen by
                        // construction). A member whose address no longer resolves
                        // makes the whole send fail truthfully (§1.8) rather than
                        // silently shrinking the archive.
                        val kept = item.childManifest!!.filter {
                            com.lumidrop.x.transfer.ChildExclusions.isKept(it.relPath, item.excludedChildPaths)
                        }
                        val kids = kept.mapNotNull { it.toNode(context) }
                        if (kids.size != kept.size) return@async MISSING_ENTRY
                        val rootName = item.path?.let { java.io.File(it).name } ?: item.name
                        FileTreeNode.ManifestNode(rootName, kids)
                    } else {
                        val node = resolveNode(context, item) ?: return@async MISSING_ENTRY
                        val tree = resolveTree(node, gate) ?: return@async MISSING_ENTRY
                        // §2.1: prune the queue-time exclusions out of the
                        // resolved snapshot (root-relative natural paths — the
                        // same rule the Queue's expand view applies), freezing
                        // the FINAL selection the sender sees into the archive.
                        if (item.excludedChildPaths.isEmpty()) snapshotOf(tree, "", emptySet())
                        else snapshotOf(tree, "", item.excludedChildPaths)
                    }
                } else {
                    val node = resolveNode(context, item) ?: return@async MISSING_ENTRY
                    // Loose file — validate it is genuinely readable NOW (§1.8):
                    // openStream once and close it. One provider round-trip per
                    // file at build time is the price of a truthful send.
                    try { node.openStream().use { it.read() } } catch (e: Exception) { return@async MISSING_ENTRY }
                    node
                }
            }
        }.awaitAll()
        // Collect texts in batch order (stable, deterministic naming).
        for (item in usable) item.textContent?.let { texts.add(item.name to it) }
        for (node in resolved) {
            if (node === MISSING_ENTRY) { anyUnresolvable = true; continue }
            if (node != null) roots.add(node)
        }
        if (anyUnresolvable || (roots.isEmpty() && texts.isEmpty())) {
            // Nothing (or not everything) could be resolved — the honest
            // per-item outcome is failure for the whole batch; the empty
            // manifest withdraws the announce and fails the parked rows the
            // same way the ordinary path does.
            return@coroutineScope ManifestResult(emptyList(), emptyList(), emptyList(), List(items.size) { false })
        }
        val zipName = com.lumidrop.x.transfer.zipBatchFileName(usable)
        val archiveNode = FileTreeNode.ZipBatchNode(zipName, roots, texts)
        val spec = FileSpec(zipName, -1L, archiveNode, 0L, null)
        ManifestResult(listOf(spec), emptyList(), emptyList(), List(items.size) { true })
    }

    // Sentinel for "this member could not be resolved" inside buildZipBatchManifest
    // (a distinct object so a legitimately-null text entry never reads as a failure).
    private val MISSING_ENTRY = FileTreeNode.ManifestNode("__missing__", emptyList())

    /** ── GOAT-PASS 14 (Phase 1, §2.1): freezes a [ResolvedNode] subtree into ────
     *  an immutable SnapshotNode, PRUNING everything under [excluded] (natural
     *  root-relative paths — a removed subfolder drops its whole subtree, a
     *  removed file drops itself). Null only for an excluded root itself, in
     *  which case the caller drops the folder from the archive entirely. */
    private fun snapshotOf(rn: ResolvedNode, relPath: String, excluded: Set<String>): FileTreeNode.SnapshotNode? {
        if (excluded.isNotEmpty() && com.lumidrop.x.transfer.ChildExclusions.isExcluded(relPath, excluded)) return null
        if (rn.isDirectory) {
            val kids = rn.children.mapNotNull { c ->
                snapshotOf(c, if (relPath.isEmpty()) c.node.name else "$relPath/${c.node.name}", excluded)
            }
            return FileTreeNode.SnapshotNode(rn.node.name, true, 0L, 0L, kids) { throw java.io.IOException("directory") }
        }
        val src = rn.node
        val len = rn.length
        val mtime = rn.lastModified
        return FileTreeNode.SnapshotNode(rn.node.name, false, len, mtime, emptyList()) { src.openStream() }
    }

    // ── Pass-16 (cross-sender resume, sender half): SHA-256 hex over the ─────
    // first min(4 KB, size) bytes of the source file. One bounded read per
    // file at manifest-build time (under the metadata gate, off the main
    // thread); null when the file can't be read — the receiver then falls
    // back to the (size, mtime) sidecar rules for legacy senders.
    private fun fileFingerprint(node: FileTreeNode, size: Long): String? {
        // ── GOAT-PASS 14 (§1.5): an unknown-size entry (the streamed zip ──
        // batch) carries no fingerprint — computing one would spin up a whole
        // archive generation just to read its first 4 KB, and a synthesized
        // archive has no cross-sender resume identity worth declaring anyway
        // (the receiver restarts unknown-size entries from byte 0 by design).
        if (size <= 0L) return null
        return try {
            val window = minOf(4096L, if (size > 0) size else 4096L).toInt()
            if (window <= 0) return null
            val buf = ByteArray(window)
            var off = 0
            node.openStream().use { ins ->
                while (off < window) {
                    val n = ins.read(buf, off, window - off)
                    if (n <= 0) break
                    off += n
                }
            }
            if (off != window) return null
            val md = java.security.MessageDigest.getInstance("SHA-256").digest(buf)
            val hex = StringBuilder().apply { for (b in md) append("%02x".format(b.toInt() and 0xFF)) }
            hex.toString()
        } catch (e: Exception) { null }
    }

    private fun resolveNode(context: Context, item: PreviewItem): FileTreeNode? = try {
        when {
            item.isFolder && item.uri != null ->
                DocumentFile.fromTreeUri(context, Uri.parse(item.uri))
                    ?.let { FileTreeNode.DocNode(it, context.contentResolver, item.includeHiddenChildren) }
            // ── Pass-36 Part 2 (#4): the staged item's hidden eligibility rides ──
            // the node — a folder push walks its live tree hidden-safe unless the
            // user picked it with the Files picker's hidden mode deliberately ON.
            item.path != null -> FileTreeNode.FsNode(File(item.path), item.includeHiddenChildren)
            item.uri != null ->
                DocumentFile.fromSingleUri(context, Uri.parse(item.uri))
                    ?.let { FileTreeNode.DocNode(it, context.contentResolver) }
            else -> null
        }
    } catch (e: Exception) { null }

    // Mirrors buildZipName's "single source keeps its own name, multiple
    // sources get a combined label" logic (LumiDropServer) — same naming
    // feel for the destination folder a native push creates as for a ZIP.
    private fun buildRootLabel(items: List<PreviewItem>): String {
        if (items.size == 1) return items[0].name.substringBeforeLast('.').ifBlank { items[0].name }
        val shown = items.take(if (items.size > 3) 2 else 3)
        return shown.joinToString("_") { it.name.substringBeforeLast('.').ifBlank { it.name } }
    }

    // ── Session lifecycle: request → poll for approval → stream files ──────────

    private suspend fun runSession(session: Session, deviceId: String, myName: String, myPort: Int, context: Context) {
        val manifest = JSONObject().apply {
            // GOAT (Part 1 correction #4): correlates this follow-up with
            // the earlier announce() call for the SAME session — see
            // apiNativePushRequest, which replaces its placeholder
            // (same id) rather than creating a second entry for what the
            // host already sees as one incoming transfer.
            put("id", session.id)
            put("deviceId", deviceId)
            put("name", myName)
            put("rootLabel", session.rootLabel)
            put("myPort", myPort)   // where OUR /api/native/push/control lives — lets the receiver propagate its own pause/resume/cancel back to us
            put("entries", JSONArray().apply {
                for (d in session.dirPaths) put(JSONObject().put("path", d).put("isDir", true))
                for (fp in session.files) put(JSONObject()
                    .put("path", fp.spec.relPath).put("size", fp.spec.size).put("isDir", false)
                    // Pass-15: the source file's own mtime — the receiver's
                    // resume-across-senders identity check (see
                    // LumiDropServer.registerPendingTransfersFor and
                    // NativePushEntry.mtime). The same original file sent
                    // from a different device carries the same (size, mtime)
                    // and resumes from the interrupted point; a different file
                    // with the same name does not, and is honestly treated as
                    // a new item.
                    .put("mtime", fp.spec.lastModified)
                    // ── Pass-16: the 4 KB CONTENT fingerprint — the identity ──
                    // that survives cross-sender mtime drift (see FileSpec.fp).
                    .apply { fp.spec.fp?.let { put("fp", it) } })
                for (t in session.textSpecs) put(JSONObject()
                    .put("path", t.relPath).put("isDir", false).put("isText", true).put("text", t.text))
            })
        }

        val postStartMs = System.currentTimeMillis()
        val manifestBase = baseUrlOf(session)
        // ── GOAT-PASS 10 (§8 — the manifest exchange is RETRY-SAFE): the POST ──
        // is idempotent by construction (the receiver keys the whole push on
        // this session's id, and a repeat delivery of the SAME manifest now
        // resolves to the existing record instead of replacing it — see
        // apiNativePushRequest's repeat-manifest branch), so a response lost
        // to the network churn that follows a device returning from the
        // background no longer fails the whole batch on the sender while the
        // receiver still holds a live, acceptable push: the same request is
        // simply made again, up to twice more with a short settle, before the
        // honest "manifest POST got no usable response" error stands.
        var reqResp: JSONObject? = null
        var reqAttempts = 0
        // ── FULL FINAL PASS 2 (§4 — the Recents-restore envelope): a receiver ──
        // whose app is mid-restore from Recents (process recreated, its
        // foreground service and NanoHTTPD bind still coming up) refuses
        // connections for a window that can exceed the old 3-attempt / 1.5 s
        // envelope — the sender then failed the whole batch exactly when the
        // user had "reopened the app and sent immediately", while a re-send
        // moments later worked (the server was up by then). The envelope is
        // widened to 4 attempts with progressive settles (1.5 s → 3 s → 5 s),
        // all AFTER a failed attempt and never before the first one: a healthy
        // receiver still answers the very first POST with zero added latency,
        // and a restarting one gets a genuine ~15 s + timeouts window to come
        // up before the honest failure stands.
        while (reqResp == null && reqAttempts < 4) {
            reqAttempts++
            reqResp = postJson(session.appContext, "$manifestBase/api/native/push/request", manifest)
            if (reqResp == null && reqAttempts < 4) {
                delay(if (reqAttempts == 1) 1500L else if (reqAttempts == 2) 3000L else 5000L)
            }
        }
        val reqId = reqResp?.optString("requestId")?.takeIf { it.isNotBlank() }
        if (reqResp == null || reqId == null) {
            session.status = "error"
            // Pass-10 (stuck "Preparing…" root fix): the receiver is holding
            // the announce PLACEHOLDER for this session (manifestReady=false,
            // its rows reading "Preparing… from <this device>" with no
            // Accept/Cancel controls). The old code returned here without
            // withdrawing it — the placeholder then survived until the TTL
            // sweep, and only if some other native-push request ever tripped
            // that sweep. Withdraw it NOW so the receiver's incoming sheet
            // and pending notification agree with reality immediately.
            withdrawAnnounce(session)
            recordBatchFailure(session)
            return
        }
        session.requestId = reqId

        var status = reqResp.optString("status", "pending")
        var lastResp = reqResp
        // ── FINAL PASS 4 (§3 — the record-lost HEAL used by pollDecisions):──
        // the receiver answered "unknown" for an id this side KNOWS it
        // announced+posted (a new-receiver signal that its record vanished —
        // swept by an edge case, lost to a restart, or the POST response was
        // lost while the record itself landed and later aged out). The heal
        // is one idempotent re-POST of the SAME manifest under the SAME
        // session id: apiNativePushRequest's placeholder replacement creates
        // the fresh record exactly the way the original POST did (a fresh
        // placeholder is re-announced first so the sheet/notification come
        // back if the record is truly gone), and the repeat-manifest guard
        // resolves to the existing record if it actually still exists. The
        // response's requestId is the same session id, so the very next
        // decision poll is against the healed record.
        suspend fun rePostManifest(s: Session) {
            try {
                val m = JSONObject().apply {
                    put("id", s.id); put("deviceId", deviceId); put("name", myName)
                    put("rootLabel", s.rootLabel); put("myPort", myPort)
                    put("entries", JSONArray().apply {
                        for (d in s.dirPaths) put(JSONObject().put("path", d).put("isDir", true))
                        for (fp in s.files) put(JSONObject()
                            .put("path", fp.spec.relPath).put("size", fp.spec.size).put("isDir", false)
                            .put("mtime", fp.spec.lastModified)
                            .apply { fp.spec.fp?.let { put("fp", it) } })
                        for (t in s.textSpecs) put(JSONObject()
                            .put("path", t.relPath).put("isDir", false).put("isText", true).put("text", t.text))
                    })
                }
                postJson(s.appContext, "${baseUrlOf(s)}/api/native/push/request", m)
            } catch (e: Exception) { /* the decision-wait's own unreachability bound owns a truly gone peer */ }
        }
        // ── Pass-14 (per-item instant action) ───────────────────────────────
        // The receiver's status response now carries per-item `decisions`
        // (topLevel → accepted/declined/pending) alongside the aggregate
        // status. When present, the session switches to PROGRESSIVE
        // dispatch: each top-level item streams the moment ITS OWN decision
        // lands — accepting ONE file of a five-file batch starts it
        // immediately, with the still-undecided items held back (not
        // blocking it, and not being skipped). The old aggregate-only
        // contract — under which nothing at all streamed until EVERY item
        // was decided — remains exactly as it was for receivers too old to
        // send the map (decisions == null).
        fun parseDecisions(resp: JSONObject?): MutableMap<String, String>? {
            val obj = resp?.optJSONObject("decisions") ?: return null
            val out = HashMap<String, String>()
            for (key in obj.keys()) out[key] = obj.optString(key, "pending")
            return out
        }
        var decisions = parseDecisions(reqResp)
        // ── GOAT-PASS 14 (Phase 1, §4 — folder progress synchronization): the ──
        // receiver's per-CHILD exclusions (the ✕ inside an expanded incoming
        // folder), delivered on the manifest response and every status poll as
        // { topLevel: [root-relative paths] }. When present, the dispatcher
        // resolves the matching pre-registered rows Declined the moment it
        // learns of them — never dispatching a byte for a not-yet-started
        // excluded child, and declining an already-streaming one's row — so
        // the sender's folder group converges on the receiver's own eligible
        // total ("2/25" on both sides, "25/25 Completed" on both) instead of
        // sitting stuck at "25/30" over children the receiver turned down.
        // Null on an old receiver: the sender keeps its legacy behavior.
        fun parseExcludedNested(resp: JSONObject?): Map<String, Set<String>>? {
            val obj = resp?.optJSONObject("excludedNested") ?: return null
            val out = HashMap<String, Set<String>>()
            for (key in obj.keys()) {
                val arr = obj.optJSONArray(key) ?: continue
                val set = HashSet<String>()
                for (i in 0 until arr.length()) set.add(arr.optString(i))
                if (set.isNotEmpty()) out[key] = set
            }
            return out
        }
        var excludedNested: Map<String, Set<String>>? = parseExcludedNested(reqResp)
        // ── FINAL PASS 1 (§1.3/§1.4 — the exclusion matcher's path shapes) ────
        // The receiver records and serializes its per-child exclusions as
        // FULL paths ("Photos/IMG_02.jpg") — keyed by the top-level name,
        // but carrying it inside the entry too (see excludeNativePush-
        // NestedPath's callers: the incoming sheet's ✕ passes "$top/$child").
        // The documented wire form is top-RELATIVE entries ("IMG_02.jpg").
        // The old matcher stripped the top from the child's relPath and
        // compared against the set AS IF it were top-relative — so against
        // the full-path entries every real receiver sends, NOTHING ever
        // matched: the sender's pre-registered rows never resolved Declined
        // from the status polls, the folder's eligible total kept the
        // original count ("0/30") for the whole batch, and each excluded
        // child was only learned at its own POST refusal — the exact
        // "sender shows 0/30, corrects itself to 6/27 much later" report.
        // Matching BOTH shapes (the documented relative form AND the
        // full-path form every current receiver emits) fixes the sender's
        // convergence on receivers old and new alike, with zero wire
        // changes.
        fun isExcludedNow(relPath: String): Boolean =
            com.lumidrop.x.transfer.ChildExclusions.isExcludedOnWire(relPath, excludedNested)
        // ── FULL FINAL PASS 2 (§4 — the decision wait is LIVENESS-aware, not a ──
        // wall clock): the old deadline (manifest time + 2 minutes) expired the
        // wait no matter what — a receiver whose user simply had not answered
        // yet (most reachable exactly on the FIRST transfer of a session, when
        // the receiving user is not yet looking at the app: just connected,
        // restored from Recents, notification unseen) had its late accept land
        // on a sender that had already given up: rows FAILED, the receiver's
        // accepted-but-never-streamed rows lingered for the 10-minute
        // dead-sender sweep, and re-sending "worked perfectly" — the exact
        // reported intermittent. The wait now closes on EITHER sustained
        // UNREACHABILITY (no successful poll for STATUS_TIMEOUT_MS — the
        // original 2-minute network-outage bound, byte-identical for a gone
        // receiver) or the generous absolute cap below — never on a receiver
        // that keeps answering "pending" while its user is simply slow.
        val decisionStartedAtMs = System.currentTimeMillis()
        val decisionAbsoluteDeadlineMs = decisionStartedAtMs + STATUS_TIMEOUT_ABSOLUTE_MS
        var lastPollOkAtMs = decisionStartedAtMs
        fun decisionWaitExpired(): Boolean {
            val now = System.currentTimeMillis()
            // ── FINAL PASS 9 (PART 4 — the "sender stuck Waiting after Accept"): ──
            // the OLD rule unilaterally expired a push after 2 minutes EVEN
            // WHILE EVERY POLL WAS SUCCEEDING (the host reachable, the sheet
            // simply still open — a person reading a long item list is not a
            // network failure). The sender then marked its files failed and
            // STOPPED POLLING; the moment the receiver finally tapped Accept
            // the decision landed on a sender that was no longer listening —
            // the receiver's row stayed inert and the sender read "Waiting"
            // until manual cancel/resend, exactly the reported intermittent
            // failure (fast Accept = worked; deliberate Accept = dead). The
            // relative bound now applies ONLY to genuine unreachability
            // (polls failing); a reachable-but-undecided host is bounded by
            // the ABSOLUTE deadline alone (30 min — unchanged), which is the
            // one legitimate "nobody will ever answer" ceiling.
            if (now > decisionAbsoluteDeadlineMs) return true
            return now - lastPollOkAtMs > STATUS_TIMEOUT_MS
        }
        fun notePollOk() { lastPollOkAtMs = System.currentTimeMillis() }

        if (decisions == null) {
            // ── Legacy aggregate flow (old receiver) — unchanged ────────────
            if (status == "pending") {
                session.status = "pending"
                while (status == "pending" && !decisionWaitExpired()) {
                    delay(STATUS_POLL_MS)
                    val poll = getJson(session.appContext, "${baseUrlOf(session)}/api/native/push/status?id=$reqId")
                    if (poll != null) { status = poll.optString("status", "pending"); lastResp = poll; notePollOk() }
                }
                if (status == "pending") {
                    // ── FULL FINAL PASS 2 (§4): the wait closed with the host ──
                    // never deciding — withdraw the push on the receiver so its
                    // record and any accepted-but-unstreamed pre-registered rows
                    // resolve NOW (its own sweep would otherwise linger up to 10
                    // minutes), and both sides agree on "this send is over".
                    withdrawPush(session, reqId)
                }
            }
            if (status == "rejected") {
                session.status = "rejected"
                recordSessionDeclined(session)
                return
            }
            // GOAT v2 (item 2): "partial" is new — the receiver decided every
            // top-level item but didn't accept all of them, which the OLD
            // accepted-or-not check here used to treat as a full rejection
            // (recordBatchFailure for literally everything, even items the host
            // DID say yes to). Falls through to the same send path "accepted"
            // always used; declinedTopLevel below is what actually skips the
            // declined items instead.
            if (status != "accepted" && status != "partial") {
                session.status = "error"
                recordBatchFailure(session)
                return
            }
            val declinedTopLevel: Set<String> = if (status == "partial") {
                val arr = lastResp?.optJSONArray("declinedTopLevel")
                (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optString(it) }.toSet()
            } else emptySet()
            if (declinedTopLevel.isNotEmpty()) recordDeclinedItems(session, declinedTopLevel)

            // Text/paste entries are fully delivered the instant their own
            // top-level item is accepted — their content already rode inside
            // the request above, so there's no separate streaming call for them
            // (see LumiDropServer.precreateNativePushDirs on the receiving
            // end). Recorded here purely for Transfers-tab visibility/parity
            // with files; skipped for anything under a declined item, which
            // the receiver never delivered in the first place.
            // ── Pass-16: texts record as MESSAGE rows (content carried in the ──
            // row itself, rendered with a Copy button in History) — the old
            // file-shaped SEND rows are gone, matching the receiver's shape.
            for (t in session.textSpecs) {
                val declinedText = t.relPath.substringBefore('/') in declinedTopLevel
                try {
                    TransferManager.recordTextTransfer(TransferManager.Direction.SEND, t.text,
                        session.peer.ip, session.peer.displayName, TransferManager.Source.NATIVE_PUSH,
                        declined = declinedText)
                } catch (e: Exception) { /* visibility-only */ }
            }

            session.status = "sending"
            dispatchFiles(session, lastResp, session.files.filter { it.spec.relPath.substringBefore('/') !in declinedTopLevel }, context)
            return
        }

        // ── Pass-14: progressive dispatch (new receiver) ─────────────────────
        session.status = "sending"
        val timedOutIds = mutableListOf<String>()
        // ── FINAL PASS 4 (§3): the "unknown → heal once" flag for
        // pollDecisions' record-lost recovery (see its own comment).
        var recordLostHealed = false

        // One poll tick: refreshes the aggregate status + the per-item map.
        suspend fun pollDecisions() {
            val poll = getJson(session.appContext, "${baseUrlOf(session)}/api/native/push/status?v=4&id=$reqId")
            if (poll != null) {
                // ── FULL FINAL PASS 2 (§4): a poll that ANSWERED is liveness — ──
                // it refreshes the unreachability half of the decision-wait
                // deadline (see decisionWaitExpired's own note).
                notePollOk()
                status = poll.optString("status", status)
                // ── FINAL PASS 4 (§3 — record lost on the receiver: heal it ──
                // once instead of mislabeling it). A NEW receiver answers
                // "unknown" (never "rejected") when it has NO record of this
                // id — the record was swept by some edge, the receiver's
                // process restarted, or the request POST never landed — none
                // of which is a decline, but all of which the old flow
                // recorded as one. Both endpoints are idempotent by id (the
                // repeat-manifest guard re-registers under the SAME id; a
                // fresh placeholder is re-announced the same way the very
                // first one was), so exactly ONE re-announce + re-request is
                // fired, and the flag makes it exactly once — a receiver
                // that STILL answers "unknown" after the re-POST is treated
                // as the old "rejected" (stop waiting; the user cancels and
                // re-sends manually, the honest outcome of a genuinely
                // unrecoverable loss). Old receivers never send "unknown".
                if (status == "unknown" && !recordLostHealed) {
                    recordLostHealed = true
                    try {
                        // The re-announce payload is rebuilt from THIS
                        // session's own specs (the top-level names the
                        // placeholder presents — same derivation the
                        // original announce used, self-contained here
                        // inside runSession instead of reaching for the
                        // caller's locals).
                        val reAnnounceItems = session.files.map { fp ->
                            AnnounceItem(fp.spec.relPath.substringBefore('/'), false, false, null)
                        } + session.textSpecs.map { t ->
                            AnnounceItem(t.relPath.substringBefore('/'), false, true, t.text)
                        }
                        announce(session, deviceId, myName, myPort,
                            if (reAnnounceItems.isEmpty())
                                listOf(AnnounceItem(session.rootLabel, false, false, null))
                            else reAnnounceItems)
                        rePostManifest(session)
                    } catch (e: Exception) { /* the retry ladder below owns unreachable peers */ }
                } else if (status == "unknown") {
                    status = "rejected"
                }
                lastResp = poll
                parseDecisions(poll)?.let { decisions = it }
                // ── GOAT-PASS 14 (§4): the same tick refreshes the receiver's ──
                // per-child exclusions, and immediately resolves any file that
                // became excluded — a still-undispatched one right here, and an
                // already-streaming one's ROW (its in-flight leg ends on the
                // receiver's own refusal of the next probe/re-POST; the row's
                // Declined verdict is what both Transfers tabs display).
                parseExcludedNested(poll)?.let { excludedNested = it }
                for (fp in session.files) {
                    if (fp.state != FileState.ACTIVE) continue
                    val tid = fp.transferId ?: continue
                    if (isExcludedNow(fp.spec.relPath)) {
                        fp.state = FileState.NOT_DONE
                        TransferManager.decline(tid)
                    }
                }
            }
        }
        // Non-blocking peek at one item's current decision.
        fun peekDecision(top: String): String {
            val d = decisions?.get(top)
            return when {
                status == "rejected" -> "declined"           // every item declined
                d == "accepted" -> "accepted"
                d == "declined" -> "declined"
                // ── FULL FINAL PASS 2 (§4): the LIVENESS-aware expiry (see ──
                // decisionWaitExpired) — a receiver that keeps answering
                // "pending" never trips this; only sustained unreachability
                // or the absolute cap does.
                decisionWaitExpired() -> "timeout"
                else -> "pending"
            }
        }

        // Text entries first (no semaphore, no streaming): each records its
        // MESSAGE row the moment its own item is decided — accepted records
        // COMPLETED, declined records DECLINED (a decline is a decision, and
        // the History tab shows the exchange honestly either way).
        for (t in session.textSpecs) {
            val top = t.relPath.substringBefore('/')
            var d = peekDecision(top)
            while (d == "pending") { delay(STATUS_POLL_MS); pollDecisions(); d = peekDecision(top) }
            when (d) {
                "accepted" -> {
                    try {
                        TransferManager.recordTextTransfer(TransferManager.Direction.SEND, t.text,
                            session.peer.ip, session.peer.displayName, TransferManager.Source.NATIVE_PUSH)
                    } catch (e: Exception) { /* visibility-only */ }
                }
                "declined" -> {
                    try {
                        TransferManager.recordTextTransfer(TransferManager.Direction.SEND, t.text,
                            session.peer.ip, session.peer.displayName, TransferManager.Source.NATIVE_PUSH,
                            declined = true)
                    } catch (e: Exception) { /* visibility-only */ }
                }
                "timeout" -> { /* never decided: no row was ever minted for this text */ }
            }
        }

        // File dispatch: manifest-ordered among READY files; undecided items
        // are DEFERRED (never blocking an already-accepted later item), and
        // re-checked after each poll tick until decided or the deadline.
        // kotlinx Semaphore (suspend acquire), same discipline as the legacy
        // dispatch loop below.
        val semaphore: Semaphore
        run {
            // GOAT-PASS 2: transfer concurrency is pinned at EXACTLY 1 — the
            // receiver's advertisement (now always 1) is honored, and the
            // cap is hard-coded so a mixed-version peer still advertising a
            // GOAT-PASS 1-era wider gate (3) can never coax this device into
            // dispatching parallel file transfers. The single active file's
            // OWN pipeline (ReadAheadInputStream prefill in streamFile) is
            // where the throughput work lives now — not parallel streams.
            val receiverConcurrency = lastResp?.optInt("receivingConcurrency", 0)?.takeIf { it > 0 }
            semaphore = Semaphore(1)
            if (receiverConcurrency != null && receiverConcurrency > 1) {
            }
        }
        // ── Pass-16 (pause-yield): the pacing gate is captured on the session ──
        // so a paused file's later resumeFile() relaunch re-acquires THE SAME
        // gate — resumed files obey the batch's concurrency exactly like
        // first-dispatched ones.
        session.dispatchGate = semaphore
        // GOAT-PASS 6 (§1 companion): the keep-alive anchor for the all-paused
        // poll-skip below — see PAUSE_KEEPALIVE_POLL_MS. Seeded from the
        // manifest exchange so a session that is paused from its very first
        // tick still measures the 45 s window from a real poll.
        var lastPollAtMs = System.currentTimeMillis()
        coroutineScope {
            val jobs = ArrayList<Deferred<Unit>>()
            var deferred = session.files.toMutableList()
            while (deferred.isNotEmpty()) {
                // ── GOAT-PASS 9 (§8): a session torn down by either side's ──
                // whole-folder cancel stops admitting anything, immediately
                // and permanently — no remaining child is dispatched, probed,
                // or minted into a ghost row under the cancelled group.
                if (com.lumidrop.x.transfer.NativeSessionPolicy.sessionTornDown(session.status)) {
                    for (fp in deferred) fp.state = FileState.NOT_DONE
                    deferred = mutableListOf()
                    break
                }
                val stillDeferred = mutableListOf<FileProgress>()
                var newDeclines = mutableSetOf<String>()
                for (fp in deferred) {
                    val preAssignedId = fp.transferId
                    if (preAssignedId != null && TransferManager.isCancelAuthoritative(preAssignedId)) {
                        fp.state = FileState.NOT_DONE
                        continue
                    }
                    // ── GOAT-PASS 14 (Phase 1, §4): a child the receiver removed ──
                    // from an accepted folder resolves Declined the moment this
                    // side learns of it — never dispatched, never a byte streamed,
                    // and its row leaves the folder group the same way a declined
                    // top-level's rows do (Declined + auto-dismissal), which is
                    // exactly what shrinks the sender's "N/30" to "N/25".
                    if (isExcludedNow(fp.spec.relPath)) {
                        fp.state = FileState.NOT_DONE
                        preAssignedId?.let { tidX ->
                            TransferManager.resolvePendingSends(listOf(tidX)) { id -> TransferManager.decline(id) }
                        }
                        continue
                    }
                    // ── Pass-19 (pause-before-dispatch) ─────────────────────────
                    // A file the user paused while it was still WAITING must not
                    // burn its turn streaming into a deliberate pause — it stays
                    // deferred and is re-checked every poll tick; the moment
                    // its pause lifts (its own Resume tap, Resume All, or the
                    // receiver's "resume" control), the next tick admits it.
                    // This is also what makes the paused-skip IDEMPOTENT: the
                    // file is never admitted while paused, so it can never end
                    // up both parked in pausedSends and still queued here.
                    if (preAssignedId != null && TransferManager.isPaused(preAssignedId)) {
                        stillDeferred.add(fp)
                        continue
                    }
                    when (peekDecision(fp.spec.relPath.substringBefore('/'))) {
                        "accepted" -> {
                            semaphore.acquire()
                            jobs += async {
                                try { sendOneFile(session, fp) } finally { semaphore.release() }
                            }
                        }
                        "declined" -> {
                            fp.state = FileState.NOT_DONE
                            newDeclines.add(fp.spec.relPath.substringBefore('/'))
                        }
                        "timeout" -> {
                            fp.state = FileState.NOT_DONE
                            fp.transferId?.let { timedOutIds.add(it) }
                        }
                        else -> stillDeferred.add(fp)
                    }
                }
                // Declines resolve the moment they are discovered — the
                // sender's pre-registered rows flip to Declined on the same
                // pass that saw the decision, not after the whole batch.
                if (newDeclines.isNotEmpty()) {
                    recordDeclinedItems(session, newDeclines)
                }
                deferred = stillDeferred
                if (deferred.isNotEmpty()) {
                    delay(STATUS_POLL_MS)
                    // ── Pass-19: while EVERY remaining file is deliberately ──
                    // paused, skip the receiver-status poll — nothing on the
                    // wire can advance until some pause lifts, and parking
                    // quietly (a few boolean checks per tick) is honest. The
                    // first tick after any resume picks the decisions back up.
                    // Pass-22 (compile fix): a local val snapshot of the mutable
                    // transferId is the only shape Kotlin can smart-cast for the
                    // non-null isPaused argument.
                    // ── GOAT-PASS 6 (§1 companion — the long-pause keep-alive): ──
                    // a full stop of polls had a hidden cost: the receiver's
                    // dead-sender sweep (NATIVE_PUSH_DEAD_SENDER_MS, 10 min)
                    // only sees liveness through lastActivityAt, which every
                    // status poll refreshes. A deliberately paused session
                    // that outlived 10 quiet minutes therefore lost the
                    // receiver's push record — and with it every sibling's
                    // resume checkpoint — while both devices sat there, alive
                    // and paused. While all remaining work is paused, a slow
                    // keep-alive poll (PAUSE_KEEPALIVE_POLL_MS) still refreshes
                    // the record; the moment any pause lifts, the ordinary
                    // 700 ms cadence resumes.
                    val anyNotPaused = deferred.any { fpx ->
                        val tid0 = fpx.transferId
                        tid0 == null || !TransferManager.isPaused(tid0)
                    }
                    val nowMs = System.currentTimeMillis()
                    if (anyNotPaused) {
                        pollDecisions()
                        lastPollAtMs = nowMs
                    } else if (nowMs - lastPollAtMs > PAUSE_KEEPALIVE_POLL_MS) {
                        pollDecisions()
                        lastPollAtMs = nowMs
                    }
                }
            }
            // ── GOAT-PASS 15 (§4.3 — the streaming-phase sync poll) ─────────────
            // The defer loop above only polls while UNDISPATCHED files remain;
            // the moment every child of an accepted folder has been admitted
            // (each parked on the dispatch semaphore, one streaming at a
            // time), the poll used to stop entirely — so a child the RECEIVER
            // excluded (the ✕ in its expanded incoming folder) while the
            // folder was streaming was learned by THIS side only at that
            // child's own POST refusal, one at a time, and the sender's
            // folder totals kept the original count ("0/30") for as long as
            // the batch ran instead of converging on the receiver's eligible
            // set ("0/25").
            //
            // A gentle companion poll now runs for exactly as long as any
            // admitted leg is still live: one tiny status GET every
            // STREAMING_SYNC_POLL_MS whose answer carries the receiver's
            // CURRENT excludedNested map + per-item decisions — pollDecisions
            // already resolves any ACTIVE row that just became excluded
            // (Declined, never a wasted byte) and records declined
            // top-levels, so both sides converge on the same eligible-child
            // manifest DURING the batch, not after it. The poll also keeps
            // the receiver's push record liveness clock fresh for the whole
            // stream (the dead-sender sweep's lastActivityAt), and stops the
            // instant no leg is live — no idle polling, nothing during an
            // all-paused session (the PAUSE_KEEPALIVE_POLL_MS cadence inside
            // the defer loop above still owns that case while deferred work
            // exists; a session parked entirely on paused sends has NO live
            // leg, so this poller exits immediately).
            if (jobs.isNotEmpty()) {
                val syncPoller = launch {
                    while (isActive) {
                        delay(STREAMING_SYNC_POLL_MS)
                        val anyLegLive = !com.lumidrop.x.transfer.NativeSessionPolicy
                            .sessionTornDown(session.status) &&
                            session.files.any { it.state == FileState.ACTIVE || it.state == FileState.QUEUED }
                        if (!anyLegLive) break
                        try { pollDecisions() } catch (e: Exception) { /* peer briefly gone — the legs' own machinery owns retries */ }
                    }
                }
                try { jobs.awaitAll() } finally { syncPoller.cancel() }
            } else {
                jobs.awaitAll()
            }
        }
        if (timedOutIds.isNotEmpty()) {
            // ── FULL FINAL PASS 2 (§4): the decision wait closed with items ──
            // never decided — withdraw the push on the receiver so its record
            // and any accepted-but-unstreamed pre-registered rows resolve NOW
            // (its own dead-sender sweep would otherwise linger up to 10
            // minutes), and both sides agree on "this send is over". No new
            // transfer can ever be minted from it afterwards: the withdraw
            // removes the record the file POSTs and probes resolve against.
            withdrawPush(session, reqId)
            TransferManager.resolvePendingSends(timedOutIds) { id -> TransferManager.fail(id) }
        }
        // ── Pass-16: a paused file holds the session open — its checkpoint ──
        // and row survive until the user resumes or cancels it.
        finishSessionIfNotHeld(session)
    }

    /** Shared session tail: final status, index release, and the completion
     *  notification — used by both the legacy aggregate dispatch and the
     *  Pass-14 progressive dispatcher. */
    private suspend fun finishSession(session: Session, context: Context) {
        // Only files actually attempted count toward "did anything go
        // wrong" — a declined item isn't an error, it's already been
        // reported on its own via recordDeclinedItems.
        session.status = if (session.files.any { it.state != FileState.DONE }) "done_with_errors" else "done"
        // Pass-8 (§10 — large-batch resource bounds): release this session's
        // index entries now that it is terminal. Retry entries are kept only
        // when something actually failed (Retry is still offered for those
        // rows); a fully-clean session releases everything.
        releaseSessionIndexes(session, keepRetry = session.files.any { it.state != FileState.DONE })
        // GOAT (Phase 1 continuation, item 2): "clean, modern confirmation"
        // once a session actually finishes -- see
        // ServerForegroundService.notifyNativePushSent's doc comment for
        // why this is a system notification rather than a Toast. Fires for
        // every native-push session, manual Send and Auto Send alike (see
        // item 2's "keep Auto Send consistent with manual sending"), using
        // the count that actually succeeded rather than the original
        // size, since a partial failure shouldn't be reported as a full
        // success. Silent when nothing succeeded at all — a batch that
        // fully failed already surfaces as FAILED rows in the Transfers
        // tab; a "Sent to X" notification on top of that would be actively
        // misleading, not reassuring.
        val doneCount = session.files.count { it.state == FileState.DONE }
        if (doneCount > 0) {
            com.lumidrop.x.service.ServerForegroundService.notifyNativePushSent(
                context, session.peer.displayName, doneCount)
        }
    }
    /** Legacy aggregate dispatch (old receivers without per-item decisions)
     *  — every file in [toSend] is already decided, so this is the classic
     *  deterministic-admission loop, followed by the shared session tail.
     *  Used by runSession's legacy branch; the Pass-14 progressive branch
     *  inlines its own defer-loop above. */
    private suspend fun dispatchFiles(session: Session, lastResp: JSONObject?, toSend: List<FileProgress>,
                                      context: Context) {
        // GOAT (critical fix — sender/receiver concurrency mismatch): the
        // receiver's own admission limit (TransferManager.
        // acquireReceivingSlot, default 1) is NOT necessarily this
        // device's own MAX_CONCURRENT_FILES. Pushing more files at once
        // than the receiver will actually start servicing just means the
        // extras sit blocked, waiting for a slot, on the RECEIVER's end —
        // for long enough (hundreds of files, receiving concurrency 1)
        // that this device's own fixed connection timeout below could
        // fire on a file that was never actually broken, just queued.
        // Capping dispatch to whatever the receiver last advertised (see
        // apiNativePushStatus/apiNativePushRequest's receivingConcurrency
        // field) means this device paces itself to match — it simply
        // doesn't attempt file N+1 until the receiver has freed a slot for
        // it, so no request ever sits queued waiting for one.
        // GOAT-PASS 2: same hard cap as the progressive dispatcher above —
        // transfer concurrency is pinned at exactly 1 regardless of what
        // the receiver last advertised. The receivingConcurrency field is
        // still parsed and logged (a wider advertisement from a
        // mixed-version peer is reported honestly) but never widens the
        // dispatch gate.
        val receiverConcurrency = lastResp?.optInt("receivingConcurrency", 0)?.takeIf { it > 0 }
        val effectiveConcurrency = 1
        // GOAT (Part 1 correction — deterministic transfer order): the
        // semaphore permit for each file is acquired HERE, sequentially, on
        // this loop -- not inside the concurrently-launched coroutine below.
        // That's what makes the order files are ADMITTED for sending match
        // toSend's own order exactly, regardless of how the coroutine
        // dispatcher happens to schedule many simultaneously-launched
        // coroutines. Completion order can still vary (a smaller admitted
        // file can finish before a larger one admitted just before it),
        // which is normal and fine; only ADMISSION order needed to be
        // deterministic.
        val semaphore = Semaphore(effectiveConcurrency)
        // ── Pass-16 (pause-yield): capture the gate so a paused file's later ──
        // resumeFile() relaunch re-acquires this very gate.
        session.dispatchGate = semaphore
        coroutineScope {
            val jobs = ArrayList<Deferred<Unit>>(toSend.size)
            for (fp in toSend) {
                // GOAT-PASS 9 (§8): same authoritative abandon test as the
                // progressive dispatcher — a folder cancelled from either
                // side must stop admitting its remaining children here too,
                // and a child whose own row was cancelled 400+ ms ago must
                // not slip through just because the CANCELLED row already
                // auto-dismissed (the bare isCancelled blind spot).
                if (dispatchAbandoned(session, fp)) {
                    fp.state = FileState.NOT_DONE
                    continue
                }
                semaphore.acquire()
                jobs += async {
                    try { sendOneFile(session, fp) } finally { semaphore.release() }
                }
            }
            jobs.awaitAll()
        }
        // ── Pass-16: a paused file holds the session open — its checkpoint ──
        // and row survive until the user resumes or cancels it.
        finishSessionIfNotHeld(session)
    }

    // A manifest that was rejected, timed out, or never reached the peer at
    // all produces zero per-item TransferManager entries (nothing ever
    // started streaming, and text was never recorded either) — without
    // this, the host would get no visible signal at all that the send
    // didn't go through. One synthetic Direction.SEND entry, created and
    // immediately failed, surfaces that exactly where transfer status
    // already lives (Transfers tab) instead of inventing a second place to
    // look.
    //
    // v1.0 (§decline-sync, "waiting" stuck row fix): the session's files
    // each pre-registered a PENDING row the instant the manifest was built
    // (sendToPeers) — those are the rows the sender is LOOKING at as
    // "Waiting" right now. Terminal manifest outcomes now resolve THOSE
    // rows in place (fail() for a genuine error/timeout) and only mint the
    // synthetic summary row when there were no pre-registered rows to
    // resolve — the old shape minted the failure summary on top and left
    // every PENDING row orphaned as "Waiting" forever, which combined with
    // the FAILED summary is exactly the reported "sender shows Failed AND
    // the file still sits in Active as waiting".
    private fun recordBatchFailure(session: Session) {
        session.status = "error"
        releaseSessionIndexes(session, keepRetry = false)
        val preRegistered = session.files.mapNotNull { it.transferId }
        val resolved = TransferManager.resolvePendingSends(preRegistered) { id ->
            TransferManager.fail(id)
        }
        session.files.forEach { it.state = FileState.NOT_DONE }
        if (resolved) return   // the sender's own rows now tell the story
        val totalItems = session.files.size + session.textSpecs.size
        if (totalItems == 0) return
        val label = when {
            totalItems > 1 -> "$totalItems items"
            session.files.isNotEmpty() -> session.files[0].spec.relPath
            else -> session.textSpecs[0].relPath
        }
        val totalBytes = session.files.sumOf { it.spec.size } + session.textSpecs.sumOf { it.sizeBytes }
        val tid = TransferManager.start(TransferManager.Direction.SEND, label, totalBytes,
            session.peer.ip, session.peer.displayName, source = TransferManager.Source.NATIVE_PUSH)
        TransferManager.fail(tid)
    }

    // v1.0 (§decline-sync): the whole-session counterpart — the receiver
    // declined everything. Every pre-registered PENDING row resolves to
    // DECLINED in place (the sender's Active tab flips "Waiting" →
    // "Declined" on the very rows they're watching), and the synthetic
    // fallback row is minted DECLINED too, never FAILED — a decline is a
    // successful protocol answer, not an error, and rendering it as
    // "Failed" is precisely what made accept/decline feel broken.
    private fun recordSessionDeclined(session: Session) {
        session.status = "rejected"
        releaseSessionIndexes(session, keepRetry = false)
        val preRegistered = session.files.mapNotNull { it.transferId }
        val resolved = TransferManager.resolvePendingSends(preRegistered) { id ->
            TransferManager.decline(id)
        }
        session.files.forEach { it.state = FileState.NOT_DONE }
        if (resolved) return
        val totalItems = session.files.size + session.textSpecs.size
        if (totalItems == 0) return
        val label = when {
            totalItems > 1 -> "$totalItems items"
            session.files.isNotEmpty() -> session.files[0].spec.relPath
            else -> session.textSpecs[0].relPath
        }
        val totalBytes = session.files.sumOf { it.spec.size } + session.textSpecs.sumOf { it.sizeBytes }
        val tid = TransferManager.start(TransferManager.Direction.SEND, label, totalBytes,
            session.peer.ip, session.peer.displayName, source = TransferManager.Source.NATIVE_PUSH)
        TransferManager.decline(tid)
    }

    // GOAT v2 (item 2): the partial-acceptance counterpart of
    // recordBatchFailure above — one visible entry per top-level item the
    // receiver declined, not the whole session (that's still
    // recordBatchFailure's job, for when EVERYTHING was turned down) and
    // not one entry per file inside a declined folder, which would just be
    // noisy for something that was never going to transfer in the first
    // place. Deliberately never touches retryIndex: retryFile() only ever
    // makes sense for a file that failed or was cancelled mid-attempt, and
    // nothing about a clean host decline should make Retry meaningful here.
    //
    // v1.0 (§decline-sync): those declined files' own pre-registered PENDING
    // rows are resolved to DECLINED in place — the folder's member rows and
    // the folder's derived group status (members.any { DECLINED }) both read
    // "Declined" on the sender, in the same rows that were "Waiting", with
    // no synthetic FAILED entry stamped on top.
    private fun recordDeclinedItems(session: Session, declinedTopLevel: Set<String>) {
        for (top in declinedTopLevel) {
            val filesForItem = session.files.filter { it.spec.relPath.substringBefore('/') == top }
            if (filesForItem.isEmpty()) continue
            filesForItem.forEach { it.state = FileState.NOT_DONE }
            val resolved = TransferManager.resolvePendingSends(filesForItem.mapNotNull { it.transferId }) { id ->
                TransferManager.decline(id)
            }
            if (resolved) continue
            val label = if (filesForItem.size == 1) filesForItem[0].spec.relPath else top
            val bytes = filesForItem.sumOf { it.spec.size }
            val tid = TransferManager.start(TransferManager.Direction.SEND, label, bytes,
                session.peer.ip, session.peer.displayName, source = TransferManager.Source.NATIVE_PUSH)
            TransferManager.decline(tid)
        }
    }

    /** Marker for a local-source read failure inside the streaming loop —
     *  see streamFile's LocalSourceGone note. Private so nothing outside
     *  this object can ever throw it into the wrong handler. */
    private class LocalSourceGone(cause: Throwable) : Exception(cause)

    private suspend fun sendOneFile(session: Session, fp: FileProgress) {
        val reqId = session.requestId ?: run { fp.state = FileState.NOT_DONE; return }
        // GOAT (continuation brief §3 -- folder-cancel "item by item"
        // defect, root cause): bail out before ANY network I/O if this
        // file was already cancelled while still waiting its turn.
        // runSession's dispatch loop has no cancellation awareness of its
        // own (see its own comment) and will keep launching sendOneFile
        // for every remaining file after a group cancel; this used to be
        // harmless-looking because the claimPendingSend/isCancelled check
        // further below already skipped a cancelled file before it could
        // ever stream a byte -- but that check ran AFTER the unconditional
        // file-status GET just below, so every remaining file in a
        // cancelled folder still cost one real (if pointless) round trip
        // to the peer, one at a time under the concurrency semaphore,
        // before finally being skipped. That serialized tail of real
        // requests hitting the peer's server one after another is what
        // "files keep sending item by item" after Cancel actually was --
        // no bytes were ever streamed for any of them, but a real request
        // still went out for every single one. Checking here first, before
        // any I/O, makes a cancelled file resolve instantly instead.
        val preAssignedId = fp.transferId
        // ── GOAT-PASS 9 (§8): the AUTHORITATIVE abandon test — the bare ──
        // isCancelled() here went blind 400 ms after a cancelled row's
        // auto-dismissal, so a folder cancelled mid-queue kept admitting
        // (and probing, and ghost-minting via the start() fallback) each
        // remaining child one at a time. The durable latch plus the
        // session-teardown verdict close both holes.
        if (dispatchAbandoned(session, fp)) {
            fp.state = FileState.NOT_DONE
            return
        }
        val spec = fp.spec
        val encodedPath = URLEncoder.encode(spec.relPath, "UTF-8").replace("+", "%20")

        // Resume support: ask the receiver how much of this file it already
        // has before sending a single byte — covers both "retry after a
        // dropped connection" and "retry after the app was killed mid-batch".
        // Phase 5 (v3 §6 item 1): the status request now ALSO declares this
        // source file's size and mtime, so the receiver can validate that the
        // partial it's holding really belongs to THIS version of the file
        // before offering it as a resume checkpoint — a re-sent file whose
        // content changed between attempts (same path, same size even) gets a
        // clean 0 answer and restarts from byte 0 instead of finishing as a
        // silently corrupted hybrid. A receiver running older firmware ignores
        // the extra parameters entirely.
        // Returns null when the probe itself could not reach the peer — the
        // automatic-resume loop treats that as a retryable connection loss
        // instead of blindly re-POSTing from byte 0 (which would clobber the
        // receiver's preserved checkpoint).
        // Pass-11 (ghost-transfer root fix): the receiver now ALSO reports
        // "cancelled": true when this exact file was deliberately cancelled
        // (its tombstone answered the probe — that state outlives the row's
        // own 400ms dismissal). The sentinel -2 makes the sender resolve the
        // row CANCELLED and stop, instead of re-POSTing the remainder of a
        // refused file ~1s later and resurrecting it as a ghost.
        suspend fun probeExistingBytes(): Long? {
            // FINAL PASS 3 (§1): each probe starts from a clean figure — a
            // stale complete-size from a previous file's probe must never
            // leak into this one's PROBE_ALREADY_COMPLETE stamp.
            lastProbeCompleteBytes = -1L
            val identityQ = "&size=${spec.size}" + (if (spec.lastModified > 0) "&mtime=${spec.lastModified}" else "") +
                // ── Pass-16: the content fingerprint — the receiver validates ──
                // it against the partial IT holds, so a resume of the SAME file
                // from a DIFFERENT device (different mtime) is recognized.
                (if (!spec.fp.isNullOrEmpty()) "&fp=${spec.fp}" else "")
            return try {
                val json = getJson(session.appContext, "${baseUrlOf(session)}/api/native/push/file-status?id=$reqId&path=$encodedPath$identityQ")
                    ?: return null
                // ── GOAT-PASS 11 (§7): a whole-push cancel answered the ─────
                // probe — the folder is dead on the receiver; latch the
                // session flag so this file's caller tears the whole
                // session down instead of moving to the next child.
                if (json.optBoolean("pushCancelled", false)) {
                    session.peerPushCancelled = true
                    PROBE_REFUSED_CANCELLED
                }
                else if (json.optBoolean("cancelled", false)) PROBE_REFUSED_CANCELLED
                // ── GOAT-PASS 11 (§7): the item was declined on the ────────────
                // receiver (never accepted, or flipped by its whole-push
                // cancel) — a deliberate decline, resolved as Declined.
                else if (json.optBoolean("declined", false)) PROBE_REFUSED_DECLINED
                // Pass-15 (ask-before-resend): the receiver deliberately ──
                // deleted this file's partial while we were still inside the
                // retry window — the caller asks our user before re-sending.
                else if (json.optBoolean("discarded", false)) PROBE_DISCARDED
                // ── Pass-19 (cross-device pause sync): the receiver's row for ──
                // THIS file is deliberately paused over there — mirror it
                // instead of streaming against a peer that asked to wait.
                else if (json.optBoolean("paused", false)) PROBE_PEER_PAUSED
                // ── GOAT-PASS 14 (Phase 1, §1.8): the receiver already holds ──
                // the COMPLETE archive of an unknown-size (zip-batch) entry —
                // short-circuit Done rather than re-streaming the whole thing.
                // FINAL PASS 3 (§1): remember the receiver-reported size that
                // rides the same answer — the PROBE_ALREADY_COMPLETE consumer
                // stamps it onto the row so the sender's History entry carries
                // the real final size, not "?".
                else if (json.optBoolean("complete", false)) {
                    lastProbeCompleteBytes = json.optLong("existingBytes", -1L)
                    PROBE_ALREADY_COMPLETE
                }
                else json.optLong("existingBytes", 0L)
            } catch (e: Exception) { null }
        }
        // ── Pass-19: park helper shared by both peer-pause discovery points ──
        // (pre-send probe and mid-retry probe). Mirrors the receiver's pause
        // on THIS side: the row shows Paused, the FileProgress parks in
        // pausedSends with the session held open, and resumeFile(tid) —
        // fired by OUR user's Resume tap or by the receiver's "resume"
        // control arriving at our server — relaunches from the receiver's
        // preserved checkpoint.
        fun parkForPeerPause() {
            val tid0 = preAssignedId ?: return
            TransferManager.requestPause(tid0)
            fp.state = FileState.PAUSED
            pausedSends[tid0] = session to fp
        }
        val initialExisting = probeExistingBytes()
        if (initialExisting == PROBE_REFUSED_CANCELLED) {
            // The receiver has already deliberately cancelled this file (a
            // decision made before this send even started, or preserved by
            // its durable tombstone). Resolve Cancelled without streaming a
            // byte — never Failed, and never a retry.
            if (preAssignedId != null) TransferManager.requestCancel(preAssignedId)
            fp.state = FileState.NOT_DONE
            // ── GOAT-PASS 11 (§7): a PUSH-level cancel verdict ends ────────────
            // the whole session (see sendOneFile's REFUSED_CANCELLED arm).
            if (session.peerPushCancelled) cancelSessionById(session.id)
            return
        }
        // ── GOAT-PASS 14 (Phase 1, §1.8): the receiver already holds this ──────────
        // unknown-size entry's COMPLETE bytes (a finished archive from an
        // earlier attempt of this same send) — claim the pre-registered row
        // and resolve Done with zero bytes re-streamed, exactly the way a
        // known-size file's "already fully delivered" probe answer does.
        // ── FINAL PASS 3 (§1): stamp the receiver-reported final size first ──
        // so the sender's History entry records the archive's real size.
        if (initialExisting == PROBE_ALREADY_COMPLETE) {
            preAssignedId?.let {
                if (TransferManager.claimPendingSend(it)) {
                    if (spec.size < 0L && lastProbeCompleteBytes > 0L)
                        TransferManager.stampFinalSize(it, lastProbeCompleteBytes)
                    TransferManager.complete(it)
                }
            }
            fp.state = FileState.DONE
            return
        }
        // ── GOAT-PASS 11 (§7): the item was declined on the receiver ────────────
        // before this leg ever opened a socket — a decision, resolved as
        // Declined without a single byte.
        if (initialExisting == PROBE_REFUSED_DECLINED) {
            if (preAssignedId != null) TransferManager.decline(preAssignedId)
            fp.state = FileState.NOT_DONE
            return
        }
        // ── Pass-19 (cross-device pause sync): the RECEIVER paused this file ──
        // before this leg ever opened a socket (its row carried the pause from
        // an earlier pause propagation, or it was paused while we were backed
        // off). Park — same parked registry, same resumeFile relaunch — and
        // leave the row honestly Paused instead of streaming against a peer
        // that asked to wait. The dispatcher's paused-skip keeps this file out
        // of re-admission while the pause stands.
        if (initialExisting == PROBE_PEER_PAUSED) {
            parkForPeerPause()
            return
        }
        // ── Pass-15 (ask-before-resend): the receiver deleted this file's ─────
        // partial on purpose (the X on its "Waiting for reconnection" card)
        // while we were still retrying. Ask OUR user — "Send again from the
        // beginning, or cancel?" — before a single byte is re-sent. A refusal
        // resolves the row Cancelled and nothing ever appears on the receiver;
        // an approval continues this send as a confirmed from-scratch resend
        // (streamFile appends confirmed=1, which the receiver requires for
        // exactly this case).
        var resendConfirmed = false
        if (initialExisting == PROBE_DISCARDED) {
            val approved = askResendFromScratch(spec.relPath.substringAfterLast('/'), session.peer.displayName)
            if (!approved) {
                if (preAssignedId != null) TransferManager.requestCancel(preAssignedId)
                fp.state = FileState.NOT_DONE
                return
            }
            resendConfirmed = true
        }
        val initialExistingBytes = initialExisting ?: 0L
        if (spec.size in 1..initialExistingBytes) {
            // Pass-8 follow-up (auto-accept sync — the "instantly sent but
            // stuck Waiting" root fix, sender half): the receiver already
            // has this exact file, so no byte ever streams and this file's
            // pre-registered PENDING SEND row (created at manifest build)
            // used to be skipped entirely — left "Waiting" forever next to
            // a transfer that was, from the receiver's point of view,
            // already complete. Claim and complete it right here so both
            // sides agree. (The receiver completes its own mirror row when
            // it answers this very probe — see apiNativePushFileStatus.)
            preAssignedId?.let {
                if (TransferManager.claimPendingSend(it)) TransferManager.complete(it)
            }
            fp.state = FileState.DONE
            return
        }   // already fully delivered
        val initialOffset = initialExistingBytes.coerceIn(0L, spec.size.coerceAtLeast(0L))
        // Honest reporting (v3 §6 item 1): a genuine resume is logged as one
        // and its progress starts from the real checkpoint; anything else
        // starts from 0 and is a restart, never mislabeled.
        if (initialOffset > 0L) {
        }

        // ── Pass-16 (pause-yield): capture the PAUSED marker BEFORE the state
        // write below flips it — the relaunch path (resumeFile) arrives with
        // fp.state == PAUSED and must be recognized as such.
        val resumedFromPause = fp.state == FileState.PAUSED
        fp.state = FileState.ACTIVE
        val tid = if (resumedFromPause && preAssignedId != null &&
            TransferManager.get(preAssignedId)?.status == TransferManager.Status.ACTIVE) {
            fp.state = FileState.ACTIVE
            preAssignedId
        } else if (preAssignedId != null && TransferManager.claimPendingSend(preAssignedId)) {
            preAssignedId
        } else if (preAssignedId != null && TransferManager.isCancelAuthoritative(preAssignedId)) {
            // Claim failing here means this specific file was deliberately
            // cancelled — almost certainly the whole folder was cancelled
            // from the Active tab while this file was still PENDING, waiting
            // its turn. The AUTHORITATIVE latch (GOAT-PASS 9 §8) keeps this
            // verdict alive long after the CANCELLED row auto-dismissed,
            // where the old bare isCancelled() went blind and fell through
            // to start() below — minting a fresh ACTIVE ghost row under the
            // cancelled group, the "reappeared at 99%" root cause.
            fp.state = FileState.NOT_DONE
            return
        } else if (com.lumidrop.x.transfer.NativeSessionPolicy.sessionTornDown(session.status)) {
            // GOAT-PASS 9 (§8): never re-open or mint a row for a session
            // that was torn down by either side's whole-folder cancel — a
            // cancel can land while THIS leg sits mid-probe above, and both
            // the reopenForResume branch below and the last-resort start()
            // fallback exist ONLY for a live session; under a torn-down
            // session either one is precisely the ghost-minting defect.
            fp.state = FileState.NOT_DONE
            return
        } else if (preAssignedId != null &&
            TransferManager.get(preAssignedId)?.status == TransferManager.Status.FAILED &&
            // Re-opens the SAME row; if the reopen itself lost a race (the row
            // resolved terminal in the microseconds above), the condition
            // reads false and the honest fresh-row fallback below takes over.
            TransferManager.reopenForResume(preAssignedId)) {
            // ── Pass-17 (one card per transfer, sender half): a manual Retry ──
            // after the auto-resume window exhausted re-opens the SAME row in
            // place instead of minting a fresh one beside the lingering
            // interrupted card — one card whose life walks Waiting → WFR →
            // Retrying → Done, never two.
            preAssignedId
        } else {
            // Defensive only -- claim can't otherwise fail for an id this
            // function itself pre-assigned moments before sendOneFile
            // was ever dispatched.
            TransferManager.start(TransferManager.Direction.SEND, spec.relPath, spec.size,
                session.peer.ip, session.peer.displayName, source = TransferManager.Source.NATIVE_PUSH,
                filePath = spec.node.localPath, groupId = session.groupIdByTopLevel[spec.relPath.substringBefore('/')])
        }
        fp.transferId = tid
        retryIndex[tid] = session to fp
        // ── GOAT-PASS 5 (§7): this leg is live for exactly the duration of ──
        // sendOneFile — the dispatch-gate holder set the Transfers tab's
        // active presentation derives from. Added AFTER the early-out returns
        // above (cancelled-before-start, already-delivered, peer-parked) so
        // those paths never register a leg that never streams; removed in the
        // finally below whichever way the leg ends (park, cancel, decline,
        // fail, window-exhausted, success).
        streamingSends.add(tid)
        // GOAT (Phase 1 continuation, item 4 root cause): registers this
        // send on OUR OWN LumiDropServer's control-link map, keyed by the
        // exact "$reqId:$relPath" the receiver's propagateNativeControl
        // will address it by if the receiver cancels/pauses/resumes it —
        // see registerNativeControlLink's doc comment for the full chain.
        // Every device runs a LumiDropServer regardless of which role
        // it's playing in this particular transfer, so this is always
        // available; a null server (service not yet up) just means
        // nothing to register against, same as if this were never called.
        com.lumidrop.x.service.ServerForegroundService.instance?.server
            ?.registerNativeControlLink(tid, reqId, spec.relPath, baseUrlOf(session))

        // ── Pass-10 (automatic resume): the streaming loop ─────────────────
        // A connection loss is retried AUTOMATICALLY — no manual action, no
        // FAILED-until-tapped row: the row stays live ("Reconnecting…"),
        // each attempt re-probes the receiver's preserved checkpoint so we
        // re-send exactly the remainder, and a bounded backoff keeps the
        // retry cadence gentle while the network is down. The loop ends on:
        //   SUCCESS            -> complete()
        //   REFUSED_CANCELLED  -> requestCancel()  (a decision is never Failed)
        //   REFUSED_DECLINED   -> decline()       (a decision is never Failed)
        //   FATAL              -> fail()          (genuine local error)
        //   window exhausted   -> fail(interrupted = true) — the receiver
        //                         keeps its partial and BOTH rows read
        //                         Interrupted for the same linger window.
        // A user cancel on either end (requestCancel / scope=push) is checked
        // every cycle and exits instantly as CANCELLED — never resumable.
        var offset = initialOffset
        val firstAttemptAtMs = System.currentTimeMillis()
        var backoffIdx = 0
        try {
            while (true) {
                // ── Pass-15 (ghost-item root fix, sender half): the durable ──
                // cancel latch is part of the check. A bare isCancelled(tid)
                // answered "not cancelled" the moment our own CANCELLED row
                // auto-dismissed 400 ms after the cancel — the invisible loop
                // below then re-POSTed the file forever, minting a fresh ghost
                // row on the receiver for every attempt while nothing showed
                // on THIS side. isCancelAuthoritative keeps the verdict alive
                // for the whole auto-resume window.
                // ── GOAT-PASS 6 (§1, belt-and-braces): only a session that was ──
                // deliberately TORN DOWN cancels in-flight work here — the
                // "rejected" set by propagateCancelForTid/cancelSessionById
                // (both of which have already requestCancel'd every row
                // themselves) and "error" (recordBatchFailure resolves the
                // rows its own way). The old blanket membership test also
                // matched "done"/"done_with_errors" — FINISHED states that,
                // combined with the premature-finish bug finishSessionIfNotHeld
                // now closes, turned every subsequently dispatched sibling
                // into an instant CANCELLED. A file the scheduler is still
                // dispatching under a finished-looking session streams; it
                // never cancels itself.
                if (TransferManager.isCancelAuthoritative(tid) ||
                    com.lumidrop.x.transfer.NativeSessionPolicy.sessionTornDown(session.status)) {
                    if (!TransferManager.isCancelled(tid)) TransferManager.requestCancel(tid)
                    fp.state = FileState.NOT_DONE
                    return
                }
                val outcome = try {
                    streamFile(session, spec, tid, offset, encodedPath, reqId, resendConfirmed)
                } catch (e: Exception) {
                    StreamOutcome.RETRYABLE
                }
                when (outcome) {
                    StreamOutcome.SUCCESS -> {
                        // ── Pass-19 (belt-and-braces) ────────────────────────────
                        // The stall watchdog may have resolved this very row
                        // interrupted a heartbeat before the write finished
                        // (the wedged-socket self-heal racing a healthy-but-
                        // slow final flush). If so, re-open the row in place
                        // and complete it — the file genuinely arrived, and
                        // the receiver's own row already says Done; leaving
                        // ours on "Waiting for reconnection" for ten minutes
                        // over a finished delivery would be the one dishonest
                        // state this pass exists to remove.
                        if (TransferManager.reopenableForResume(tid)) TransferManager.reopenForResume(tid)
                        // ── GOAT-PASS 14 (Phase 1, §4/§1.8): two honesty checks ──
                        // before the Completed verdict lands:
                        //  (a) the receiver EXCLUDED this child while it was
                        //      streaming (the poll tick already flipped the row
                        //      Declined) — a decision is never a success;
                        //  (b) a streamed zip-batch archive had to SKIP an
                        //      entry (a source file went unreadable mid-send) —
                        //      an incomplete archive is a Failed transfer with
                        //      a Retry, never a silent success.
                        val rowNow = TransferManager.get(tid)?.status
                        val skippedEntries = (spec.node as? FileTreeNode.ZipBatchNode)?.takeSkippedEntries()
                        if (rowNow == TransferManager.Status.DECLINED || !skippedEntries.isNullOrEmpty()) {
                            if (rowNow != TransferManager.Status.DECLINED) TransferManager.fail(tid)
                            fp.state = FileState.NOT_DONE
                            return
                        }
                        // ── FINAL PASS 3 (§1 — the sender's real size for a ──
                        // completed ZIP stream): the streamed archive's row was
                        // minted with totalBytes = -1 (the archive is synthe-
                        // sized live; its final size cannot be declared), which
                        // is what the sender's own History entry would other-
                        // wise render as "?" forever. The streaming loop's
                        // final updateProgress(tid, written) reported the exact
                        // byte count this attempt handed to the socket — every
                        // one of which the receiver verified and finalized — so
                        // the row's transferredBytes at SUCCESS is the archive's
                        // actual final size. stampFinalSize is a no-op for
                        // ordinary known-size specs. Stamped BEFORE complete()
                        // so the HistoryStore hook records the real figure.
                        if (spec.size < 0L)
                            TransferManager.stampFinalSize(tid, TransferManager.get(tid)?.transferredBytes ?: -1L)
                        TransferManager.complete(tid)
                        fp.state = FileState.DONE
                        return
                    }
                    // ── Pass-16 (pause-yield): park the file, free the permit. ──
                    // The row STAYS Active+Paused in the engine (the user's own
                    // pause action set that), the receiver's copy parks itself
                    // back to Waiting with its checkpoint kept, and the file
                    // waits in the registry for resumeFile(tid)'s relaunch —
                    // which re-probes the checkpoint and continues from exactly
                    // here. Meanwhile the dispatch loop's permit released with
                    // this coroutine's return, so the NEXT Waiting file starts
                    // immediately: resuming ANY one paused item now works,
                    // whichever one the user picks.
                    StreamOutcome.PAUSED -> {
                        fp.state = FileState.PAUSED
                        pausedSends[tid] = session to fp
                        return
                    }
                    StreamOutcome.REFUSED_CANCELLED -> {
                        // The receiver (or this device's own UI) cancelled — a
                        // deliberate stop resolves Cancelled, and its temp
                        // resume data is the RECEIVER's to clean (it does,
                        // in cancelWholePush / the interrupted-leg paths).
                        TransferManager.requestCancel(tid)
                        fp.state = FileState.NOT_DONE
                        // ── GOAT-PASS 11 (§7): a PUSH-level cancel verdict ─────────
                        // (the receiver's "Push cancelled by host" / probe
                        // pushCancelled flag) ends the WHOLE session right
                        // here — the dispatcher stops admitting the
                        // folder's remaining children, parked siblings are
                        // purged, and the rows resolve Cancelled. Without
                        // this, a 694-file folder ground through hundreds
                        // of per-file refusals after a single whole-folder
                        // cancel.
                        if (session.peerPushCancelled) {
                            cancelSessionById(session.id)
                        }
                        return
                    }
                    StreamOutcome.REFUSED_DECLINED -> {
                        TransferManager.decline(tid)
                        fp.state = FileState.NOT_DONE
                        return
                    }
                    // GOAT-PASS 11 (§7): the receiver answered but holds no
                    // record of this push — nothing under this id can ever
                    // resume. Resolve interrupted (the honest "Auto-Resume on
                    // Reconnection"-class state whose manual Retry
                    // re-announces a fresh session and adopts preserved
                    // partials), and move on WITHOUT the 10-minute retry
                    // grind.
                    StreamOutcome.SESSION_GONE -> {
                        // ── FINAL PASS 1 (§4.1 — a streamed ZIP never reads ──
                        // "Auto-Resume on Reconnection"): an unknown-length
                        // archive has no resume identity — nothing about this
                        // state can auto-resume, and marking it interrupted
                        // both told the user a lie AND hid the Retry/Resend
                        // action (offered only for !interrupted rows). A
                        // plain FAILED with Resend is the honest verdict.
                        if (TransferManager.get(tid)?.isZipStream == true)
                            TransferManager.fail(tid)
                        else
                            TransferManager.fail(tid, interrupted = true)
                        fp.state = FileState.NOT_DONE
                        return
                    }
                    StreamOutcome.FATAL -> {
                        TransferManager.fail(tid)
                        fp.state = FileState.NOT_DONE
                        return
                    }
                    StreamOutcome.RETRYABLE -> {
                        val elapsed = System.currentTimeMillis() - firstAttemptAtMs
                        if (elapsed > AUTO_RESUME_WINDOW_MS) {
                            // ── FINAL PASS 1 (§4.1 — a streamed ZIP never reads ──
                            // "Auto-Resume on Reconnection"): the retry window
                            // has CLOSED — nothing will auto-resume, so for a
                            // streamed archive (which by design restarts from
                            // byte 0, never resumes) the honest terminal state
                            // is a plain FAILED whose row offers the Resend
                            // action, exactly like the receiver's mirror row.
                            if (TransferManager.get(tid)?.isZipStream == true)
                                TransferManager.fail(tid)
                            else
                                TransferManager.fail(tid, interrupted = true)
                            fp.state = FileState.NOT_DONE
                            return
                        }
                        // ── Pass-14 (perfect cancel): probe FIRST on the very ──
                        // first failure, BEFORE any "Waiting for reconnection"
                        // presentation or backoff sleep. A receiver that just
                        // cancelled this file answers the probe "cancelled" —
                        // the row resolves CANCELLED instantly and the
                        // never-resumable deliberate-cancel contract holds
                        // with no interrupted-state flash on either side. A
                        // receiver that is simply gone (network down) fails
                        // the probe, and only THEN does the honest
                        // reconnection presentation begin. Subsequent
                        // failures keep the original shape (mark reconnecting
                        // → backoff → probe) so a flapping connection can
                        // never hot-loop the probe.
                        var probed: Long? = null
                        if (backoffIdx == 0) {
                            probed = probeExistingBytes()
                            if (probed == PROBE_REFUSED_CANCELLED) {
                                TransferManager.requestCancel(tid)
                                fp.state = FileState.NOT_DONE
                                // GOAT-PASS 11 (§7): a PUSH-level verdict tears
                                // the whole session down (see REFUSED_CANCELLED).
                                if (session.peerPushCancelled) cancelSessionById(session.id)
                                return
                            }
                            if (probed == PROBE_REFUSED_DECLINED) {
                                TransferManager.decline(tid)
                                fp.state = FileState.NOT_DONE
                                return
                            }
                            if (probed != null) backoffIdx = 1   // immediate probe consumed the first slot
                        }
                        if (probed == null) {
                            // ── FINAL PASS 2 (§1 — the sender's streamed-ZIP ──
                            // half): a live-synthesized archive restarts from
                            // byte 0 on every attempt, so its row latches the
                            // ZIP-specific auto-continue presentation (progress
                            // zeroed — the dead attempt's bytes must not read
                            // as live progress) instead of the ordinary
                            // byte-resume reconnecting latch.
                            if (TransferManager.get(tid)?.isZipStream == true)
                                TransferManager.markZipAutoContinue(tid)
                            else
                                TransferManager.markReconnecting(tid)
                            val backoff = RESUME_BACKOFF_MS[backoffIdx.coerceAtMost(RESUME_BACKOFF_MS.size - 1)]
                            backoffIdx++
                            delay(backoff)
                            // Re-probe the checkpoint; an unreachable probe on a
                            // RETRY means the network is still down — do not
                            // blind-POST from 0 and clobber the preserved partial.
                            probed = probeExistingBytes()
                            if (probed == null) continue
                        }
                        if (probed == PROBE_REFUSED_CANCELLED) {
                            // Pass-11 (ghost-transfer root fix): the receiver's
                            // durable tombstone answered the probe — this file
                            // was deliberately cancelled over there. Resolve
                            // Cancelled and stop the loop for good; never
                            // re-POST a refused file.
                            TransferManager.requestCancel(tid)
                            fp.state = FileState.NOT_DONE
                            // GOAT-PASS 11 (§7): a PUSH-level verdict tears
                            // the whole session down (see REFUSED_CANCELLED).
                            if (session.peerPushCancelled) cancelSessionById(session.id)
                            return
                        }
                        if (probed == PROBE_REFUSED_DECLINED) {
                            TransferManager.decline(tid)
                            fp.state = FileState.NOT_DONE
                            return
                        }
                        // ── Pass-19 (cross-device pause sync, mid-retry) ──────────
                        // The receiver paused this file while we were backed
                        // off (its row's pause flag answers the probe). The
                        // blocked-write self-heal may have resolved our row
                        // interrupted first — either way the honest state is
                        // PAUSED: re-open the row if the watchdog closed it,
                        // mirror the pause, and park for the exact-checkpoint
                        // relaunch when either side resumes.
                        if (probed == PROBE_PEER_PAUSED) {
                            if (TransferManager.reopenableForResume(tid)) {
                                TransferManager.reopenForResume(tid)
                            }
                            val tid0 = preAssignedId ?: tid
                            TransferManager.requestPause(tid0)
                            fp.state = FileState.PAUSED
                            pausedSends[tid0] = session to fp
                            return
                        }
                        // ── Pass-15 (ask-before-resend, mid-retry) ────────────
                        // The receiver discarded the partial during the
                        // disconnection — same question as the pre-send probe,
                        // asked here on the reconnect path where it actually
                        // happens in practice.
                        if (probed == PROBE_DISCARDED) {
                            val approved = askResendFromScratch(spec.relPath.substringAfterLast('/'), session.peer.displayName)
                            if (!approved) {
                                TransferManager.requestCancel(tid)
                                fp.state = FileState.NOT_DONE
                                return
                            }
                            resendConfirmed = true
                            offset = 0L
                            continue
                        }
                        // Pass-11 (auto-reconnect root fix): if some path
                        // failed the row as Interrupted while this loop still
                        // owns it (the receiver's side is fine to resume from
                        // the checkpoint), revive it back to ACTIVE under the
                        // SAME id so progress reporting and completion work on
                        // the original row — one row per logical transfer.
                        if (TransferManager.reopenableForResume(tid)) {
                            TransferManager.reopenForResume(tid)
                            TransferManager.markReconnecting(tid)   // still reconnecting until bytes flow
                        }
                        if (spec.size in 1..probed) {
                            // The receiver reports completion already (e.g. the
                            // last attempt's bytes actually landed after the
                            // exception) — done, no further POST needed.
                            TransferManager.complete(tid)
                            fp.state = FileState.DONE
                            return
                        }
                        offset = probed.coerceIn(0L, spec.size.coerceAtLeast(0L))
                    }
                }
            }
        } finally {
            // GOAT-PASS 5 (§7): the leg is over — parked, cancelled, declined,
            // failed, window-exhausted or delivered — it no longer holds the
            // dispatch gate, so it must stop reading as "executing" the very
            // moment it stops streaming.
            streamingSends.remove(tid)
            com.lumidrop.x.service.ServerForegroundService.instance?.server?.unregisterNativeControlLink(tid)
        }
    }

    private fun streamFile(session: Session, spec: FileSpec, tid: String, offset: Long,
                             encodedPath: String, reqId: String,
                             // Pass-15 (ask-before-resend): true once OUR user approved a
                             // from-scratch resend of a deliberately-discarded partial — the
                             // receiver refuses unconfirmed re-POSTs of a discarded file, so
                             // this flag is what makes the approved resend possible at all.
                             confirmedResend: Boolean = false): StreamOutcome {
        val mtimeQ = if (spec.lastModified > 0) "&mtime=${spec.lastModified}" else ""
        val confirmedQ = if (confirmedResend) "&confirmed=1" else ""
        val url = URL("${baseUrlOf(session)}/api/native/push/file" +
            "?id=$reqId&path=$encodedPath&size=${spec.size}&offset=$offset$mtimeQ$confirmedQ")
        var conn: HttpURLConnection? = null
        // ── GOAT-PASS 4 (§4 — the blocked-write deadlock) ──────────────────────
        // The byte-copy loop below checks pause/cancel only at its top; a
        // receiver that stops draining the socket (it mirrored OUR user's
        // pause, or the network died silently) wedges `out.write` inside the
        // TCP window, the loop-top check never runs again, and the dispatch
        // permit is held forever — the next Waiting item never starts (the
        // reported two-pause queue freeze). The watchdog reconciles this
        // connection with the row's engine state every 250ms and disconnects
        // it the moment the two diverge; the write then unblocks with an
        // immediate IOException and the cause classification below routes
        // it: PAUSE parks the file exactly like a noticed pause, everything
        // else keeps the ordinary retry/cancel classification.
        var abortHandle: com.lumidrop.x.transfer.SendAbortWatchdog.Handle? = null
        return try {
            conn = (com.lumidrop.x.utils.PeerHttp.open(session.appContext, url) as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                // GOAT (critical fix — sender/receiver concurrency
                // mismatch): was 20_000. Widened to match TransferManager.
                // STALL_TIMEOUT_MS (45s) on the receiving end — the two
                // sides should agree on what "stalled" means. The primary
                // fix is runSession capping its own dispatch to the
                // receiver's advertised concurrency (see
                // effectiveConcurrency there), so this is a safety margin
                // for genuine cases (a big file over a slow link, brief
                // scheduling hiccups), not the load-bearing fix.
                readTimeout = 45_000
            }
            abortHandle = com.lumidrop.x.transfer.SendAbortWatchdog.register(tid) {
                try { conn?.disconnect() } catch (e: Exception) { /* already dead */ }
            }
            val remaining = if (spec.size < 0L) -1L else spec.size - offset
            // The long overload of setFixedLengthStreamingMode needs API 19+;
            // minSdk here is 21, so it's always available.
            if (remaining >= 0L) conn.setFixedLengthStreamingMode(remaining)
            else conn.setChunkedStreamingMode(256 * 1024)

            var completed = false
            // Distinguishing a LOCAL source read failure (file vanished /
            // permission revoked mid-send — genuinely fatal) from a NETWORK
            // failure (socket death — retryable) inside one interleaved
            // read/write loop: only the src.read/skipFully calls rethrow as
            // LocalSourceGone; everything else (out.write, connect, headers)
            // that throws stays a plain exception and is treated as a
            // connection loss the automatic resume can recover from.
            try {
                // ── FINAL PASS 2 (§1.5 — skipped entries are PER GENERATION) ────
                // Every openStream() is an independent archive generation, but
                // the skip ledger lives on the SHARED ZipBatchNode — an entry
                // skipped during an attempt that was later severed by a
                // disconnection used to survive into the SUCCESS check of the
                // NEXT attempt, flipping a genuinely complete re-stream into a
                // plain Failed-with-Resend flash the moment the receiver
                // answered 200 (the reported transient). Draining the ledger
                // at generation start scopes it to THIS attempt only: an
                // archive whose own stream skipped a source still resolves
                // honestly Failed, while a fresh attempt starts with a clean
                // ledger and its verdict is its own.
                (spec.node as? com.lumidrop.x.transfer.FileTreeNode.ZipBatchNode)?.takeSkippedEntries()
                val src = try { spec.node.openStream() } catch (e: Exception) { throw LocalSourceGone(e) }
                src.use { s ->
                    try { skipFully(s, offset) } catch (e: Exception) { throw LocalSourceGone(e) }
                    // GOAT-PASS 2 (§throughput — the send pipeline): the
                    // source is wrapped in a bounded read-ahead — one
                    // prefetch thread keeps up to 8×256 KB filled ahead of
                    // this loop, so a storage-read latency spike (journal
                    // commit, GC on the sending phone's own flash) never
                    // stalls the socket. A stalled send pipe is what closes
                    // the RECEIVER's TCP window and collapses its congestion
                    // control into the reported sudden 5–6 MB/s dips; this
                    // keeps the single stream continuously fed instead.
                    // read() semantics are identical to the raw stream's
                    // (blocking, throws the source's own IOException — which
                    // the LocalSourceGone mapping below still catches), and
                    // the use{} above closes the wrapper too (its close
                    // stops the prefetch thread and closes the source; the
                    // second close of the raw stream is a harmless no-op).
                    val piped = PipelineIO.ReadAheadInputStream(s) { boostPipelineThread() }
                    conn.outputStream.use { out ->
                        val buf = ByteArray(256 * 1024)
                        var written = offset
                        var rem = remaining
                        // ── Pass-16 (pause-yield): the flag that turns a sustained ──
                        // pause into a deliberate abort of this POST.
                        var pausedYield = false
                        while (rem != 0L) {
                            // ── Pass-19 (the fix that made the pause-yield real) ──────
                            // This used to be the unbounded awaitUnpaused, which
                            // only ever returns UN-paused — so the isPaused check
                            // below it was DEAD CODE and the whole pass-16 pause-
                            // yield NEVER FIRED. A paused in-flight send therefore
                            // held its dispatch permit for as long as the pause
                            // lasted: the next Waiting item could never start, and
                            // only this one item could ever resume (its own blocked
                            // loop simply continuing). The bounded form returns
                            // once the pause has outlasted the 500 ms grace, the
                            // confirmation sleep below adds its own 500 ms (total
                            // ~1 s, unchanged from the original design), and a
                            // REAL pause then yields the permit — the next
                            // Waiting file starts immediately (queue auto-
                            // promotion) and this file parks itself in
                            // pausedSends for resumeFile(tid)'s exact-checkpoint
                            // relaunch, so resuming ANY paused item works.
                            // A sub-grace blip (double-tap, quick toggle) still
                            // resumes in place with zero disruption.
                            val pausedPastGrace = TransferManager.awaitUnpausedOrYield(tid, 500L)
                            if (TransferManager.isCancelled(tid)) break
                            if (pausedPastGrace) {
                                try { Thread.sleep(500) } catch (ie: InterruptedException) { Thread.currentThread().interrupt(); break }
                                if (TransferManager.isPaused(tid) && !TransferManager.isCancelled(tid)) {
                                    pausedYield = true
                                    break
                                }
                            }
                            val toRead = if (rem < 0L) buf.size else minOf(buf.size.toLong(), rem).toInt()
                            val n = try { piped.read(buf, 0, toRead) } catch (e: Exception) { throw LocalSourceGone(e) }
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            written += n
                            TransferManager.updateProgress(tid, written)
                            if (rem > 0L) rem -= n
                        }
                        if (pausedYield) return StreamOutcome.PAUSED
                        completed = !TransferManager.isCancelled(tid) && (remaining < 0L || written >= spec.size)
                    }
                }
            } catch (e: LocalSourceGone) {
                return StreamOutcome.FATAL
            }
            if (!completed) {
                if (TransferManager.isCancelled(tid)) return StreamOutcome.REFUSED_CANCELLED
                return StreamOutcome.RETRYABLE
            }
            val code = conn.responseCode
            if (code in 200..299) return StreamOutcome.SUCCESS
            // The receiver's DELIBERATE answers — resolve as decisions, never
            // as failures ("cancelled transfers must never show Failed"):
            //   "Cancelled by host"  -> this row was cancelled on the peer.
            //   "Not accepted" / "Excluded by host" -> the peer turned the
            //   item down (a whole-push cancel flips decisions to declined,
            //   which is exactly how a receiver-side Cancel All reaches us
            //   when the scope=push propagation itself was lost).
            val errBody = try { conn.errorStream?.bufferedReader()?.use { it.readText() } } catch (e: Exception) { "" } ?: ""
            // ── GOAT-PASS 11 (§7): the receiver's WHOLE-PUSH cancel ──────────
            // verdict — distinct from the per-file "Cancelled by host" so
            // the caller can tear the entire session down instead of
            // churning one refusal per remaining child.
            if (errBody.contains("Push cancelled by host")) {
                session.peerPushCancelled = true
                return StreamOutcome.REFUSED_CANCELLED
            }
            if (errBody.contains("Cancelled by host")) return StreamOutcome.REFUSED_CANCELLED
            if (errBody.contains("Not accepted") || errBody.contains("Excluded by host")) return StreamOutcome.REFUSED_DECLINED
            // ── GOAT-PASS 11 (§7): the receiver answered but holds no ────────
            // record of this push (swept after its TTL, or the receiver
            // restarted mid-batch). The peer is REACHABLE — this is not a
            // network outage — and no re-POST under this id can ever
            // succeed, so the 10-minute-per-file auto-resume grind would be
            // pure churn. Resolve interrupted; manual Retry re-announces.
            if (errBody.contains("Unknown or expired session") || errBody.contains("Unknown session")) {
                return StreamOutcome.SESSION_GONE
            }
            // 5xx / anything else: the peer may be mid-restart or wedged —
            // retrying is cheap and correct.
            StreamOutcome.RETRYABLE
        } catch (e: Exception) {
            // ── GOAT-PASS 4 (§4): was this exception the watchdog aborting a ──
            // write that was wedged behind a pause? Then park the file through
            // the exact same path the loop-top pause check uses — the row stays
            // Active+paused with its checkpoint, the file parks in pausedSends,
            // and the dispatch permit this coroutine returns releases the next
            // Waiting item. (The row-still-paired check keeps a stale cause
            // from parking a file whose pause has already lifted — that case
            // falls through to the ordinary retry path and resumes from the
            // receiver's preserved checkpoint.)
            val cause = abortHandle?.consumeCause()
            if (cause == com.lumidrop.x.transfer.SendAbortWatchdog.Cause.PAUSE &&
                TransferManager.isPaused(tid) && !TransferManager.isCancelled(tid)) {
                return StreamOutcome.PAUSED
            }
            // ── GOAT-PASS 8 (§11): the watchdog's NETWORK abort — the row's ──
            // peer was probed unreachable (or the LAN blinked) while this
            // write sat wedged in TCP retransmission. The ordinary RETRYABLE
            // classification below is exactly right: the auto-resume loop
            // backs off, re-probes the checkpoint, and continues on a FRESH
            // socket the moment the peer answers — instead of waiting out
            // the kernel's minute-scale retransmission backoff on a
            // connection that is not coming back.
            if (cause == com.lumidrop.x.transfer.SendAbortWatchdog.Cause.NETWORK) {
                return StreamOutcome.RETRYABLE
            }
            StreamOutcome.RETRYABLE
        }
        finally {
            abortHandle?.close()
            conn?.disconnect()
        }
    }

    // GOAT-PASS 2 (§sync — resume latency): the old skipFully READ AND
    // DISCARDED up to `n` bytes at 64 KB a time — on a resume of a large
    // file that was gigabytes of pure waste before the first payload byte
    // could move (re-reading 1.5 GB at ~200 MB/s ≈ 7.5 s of dead time, and
    // all of it competing with the very storage the active stream needs).
    // InputStream.skip() on the sources this engine actually opens
    // (FileInputStream → seek via the file channel; ContentResolver
    // streams over local files behave the same) moves the position with
    // ZERO data transfer. The read-discard loop survives only as the
    // fallback for a stream whose skip() refuses to advance (returns 0) —
    // never worse than the old code, dramatically better on the common
    // case.
    private fun skipFully(stream: InputStream, n: Long) {
        var remaining = n
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val skipped = try { stream.skip(remaining) } catch (e: Exception) { 0L }
            if (skipped > 0) { remaining -= skipped; continue }
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val read = stream.read(buf, 0, toRead)
            if (read <= 0) break
            remaining -= read
        }
    }

    // ── Tiny HTTP JSON helpers (java.net only — no new dependency) ─────────────

    private fun postJson(context: Context?, urlStr: String, body: JSONObject): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (com.lumidrop.x.utils.PeerHttp.open(context, urlStr) as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = CONNECT_TIMEOUT_MS
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }
            readJsonResponse(conn)
        } catch (e: Exception) { null } finally { conn?.disconnect() }
    }

    private fun getJson(context: Context?, urlStr: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (com.lumidrop.x.utils.PeerHttp.open(context, urlStr) as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = CONNECT_TIMEOUT_MS
            }
            readJsonResponse(conn)
        } catch (e: Exception) { null } finally { conn?.disconnect() }
    }

    private fun readJsonResponse(conn: HttpURLConnection): JSONObject? {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return null
        if (code !in 200..299) return null
        return try { JSONObject(text) } catch (e: Exception) { null }
    }
}
