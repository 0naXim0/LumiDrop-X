package com.lumidrop.x.server

/**
 * ══ PASS 27 — the handoff settle policy, as pure logic ═════════════════════
 *
 * The settle's job (Phase 4, unchanged in purpose) is narrow: hold the FIRST
 * body byte of a download-manager-interceptable response back just long
 * enough for the interception's policy-cancel to land, so a SMALL body
 * cannot fully "complete" on the very connection the interception is about
 * to throw away.
 *
 * What PASS 27 changes is the SIZE AWARENESS: a body of [MAX_BYTES] or more
 * cannot plausibly reach its last byte inside the settle window on any link
 * this app serves (the cancel arrives within tens-to-hundreds of
 * milliseconds; multi-megabyte bodies take multiples of the window to
 * stream), so settling them only delayed EVERY large direct download by
 * ~1.1 s per item for zero protective value. Large KNOWN-size bodies now
 * stream immediately; unknown sizes (live-synthesized archives) keep the
 * settle — a tiny folder zip is exactly the shape the window exists for.
 *
 * Pure JVM logic (no Android imports) so the matrix is unit-testable.
 */
object DownloadSettlePolicy {

    /** The pre-body hold for a first interception-eligible connection. */
    const val SETTLE_MS = 1100L

    /** Bodies at or below this size can plausibly fully deliver inside the
     *  settle window — they keep it. Larger known bodies skip it. */
    const val MAX_BYTES = 4L * 1024 * 1024

    /**
     * The settle to apply to a handoff-flagged first connection.
     *
     * @param handoff the request carried handoff=1 (the download-manager
     *        interception flow) — no flag, no settle, unchanged behavior.
     * @param wasReAwaited the row was ALREADY re-awaited once: this request
     *        is the DM's own CONFIRMED re-request — stream immediately.
     * @param sizeBytes the body size this response will serve, or <= 0 when
     *        unknown (live-synthesized archives) — unknown keeps the settle.
     */
    fun settleMs(handoff: Boolean, wasReAwaited: Boolean, sizeBytes: Long): Long =
        if (handoff && !wasReAwaited && (sizeBytes <= 0L || sizeBytes <= MAX_BYTES)) SETTLE_MS else 0L
}
