package com.lumidrop.x.transfer

/**
 * ── GOAT-PASS 6 (§1 — the Pause-All/resume cancellation cascade, root fix) ──
 *
 * Pure, Android-free decision core for NativePushClient's session tail.
 * Extracted so the JVM suite can pin the exact lifecycle rule the native
 * push dispatcher obeys, without any Android dependency (the same
 * discipline the engine's own tests use).
 *
 * THE BUG THIS CLOSES (the reported critical): "Pause All → resume the
 * first item → it completes → every remaining item becomes CANCELLED."
 *
 * The chain was:
 *  1. Pause All pauses every row. The ONE in-flight file parks in
 *     NativePushClient.pausedSends; every still-WAITING sibling is deferred
 *     by the dispatch loop and holds its pause ONLY in the engine's
 *     pauseRequested flag — invisible to the old hold-open test.
 *  2. Resuming and completing the one parked file let finishSession fire
 *     with its siblings still deliberately paused: the session flipped
 *     terminal ("done_with_errors"), every session index was released, and
 *     sendOneFile's terminal-session guard resolved each subsequently
 *     admitted sibling CANCELLED.
 *
 * THE RULE NOW: a session may only finish when its work is genuinely
 * finished — nothing parked, nothing paused, and no file still queued,
 * active, or engine-live. A session "done_with_errors" can never coexist
 * with work the scheduler still owns.
 */
object NativeSessionPolicy {

    /** GOAT-PASS 6 (§1): true when the session MUST stay open because it
     *  still owns live or recoverable work.
     *
     *  A file keeps the session open when ANY of these hold:
     *   • its state is one of [liveFileStates] (still queued for dispatch,
     *     actively streaming, or parked-paused for a later resume);
     *   • its engine row is deliberately paused (a Pause-All sibling the
     *     FileProgress may have moved on from, but whose row the user can
     *     still resume);
     *   • its engine row is still live (PENDING/ACTIVE — e.g. resumed and
     *     legitimately waiting at the receiver's gate).
     *
     *  [parkedCount] is the number of mid-stream parks (pausedSends
     *  entries) held for this session; a parked file is a scheduled
     *  continuation by definition, so any park holds the session open even
     *  if its FileProgress state has already moved on.
     *
     *  @param fileStates one (transferId, stateName) per session file; a
     *         null transferId is treated as live work only when its state
     *         is in the live set (the pre-registration window).
     *  @param rowPaused probes the engine: is this row's pauseRequested set?
     *  @param rowLive probes the engine: is this row PENDING or ACTIVE? */
    fun sessionHeldOpen(
        fileStates: List<Pair<String?, String>>,
        parkedCount: Int,
        rowPaused: (String) -> Boolean,
        rowLive: (String) -> Boolean,
    ): Boolean {
        if (parkedCount > 0) return true
        for ((tid, state) in fileStates) {
            if (state in LIVE_FILE_STATES) return true
            if (tid == null) continue
            if (rowPaused(tid)) return true
            if (rowLive(tid)) return true
        }
        return false
    }

    /** The FileProgress states that mean "the dispatcher still owns this
     *  file": QUEUED (waiting its dispatch turn), ACTIVE (streaming now) or
     *  PAUSED (parked mid-stream for a scheduled resume). DONE and NOT_DONE
     *  are terminal for the session's purposes. */
    val LIVE_FILE_STATES = setOf("QUEUED", "ACTIVE", "PAUSED")

    /** GOAT-PASS 6 (§1, belt-and-braces): the session statuses under which
     *  an in-flight file's own loop must resolve itself cancelled. Only a
     *  session that was deliberately TORN DOWN qualifies — "rejected" (a
     *  whole-push cancel from either side, whose teardown paths have already
     *  requestCancel'd every row themselves) or "error" (the session died
     *  before the manifest landed, whose rows recordBatchFailure resolves).
     *  The "done"/"done_with_errors" statuses are FINISHED states: with the
     *  hold-open rule above they can no longer be reached while any file is
     *  still dispatchable, and a file that nevertheless finds itself under a
     *  finished-looking session (a defensive path, a future regression) must
     *  STREAM, never cancel itself — item-level completion was silently
     *  becoming queue-level cancellation, which is exactly the reported
     *  defect class. */
    fun sessionTornDown(status: String): Boolean =
        status == "rejected" || status == "error"
}
