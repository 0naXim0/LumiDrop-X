package com.lumidrop.x.server

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * ══ PASS 27 — the size-aware handoff settle policy ═══════════════════════════
 *
 * The settle's purpose (Phase 4, unchanged): hold a first interception-eligible
 * connection's first body byte back just long enough for the download
 * manager's policy-cancel to land, so a SMALL body cannot fully "complete" on
 * the very connection the interception is about to throw away.
 *
 * What PASS 27 adds is the size awareness: a body of MAX_BYTES or more cannot
 * plausibly reach its last byte inside the settle window on any link this app
 * serves, so settling it delayed every large direct download by ~1.1 s per
 * item for zero protective value. Unknown sizes (live-synthesized archives —
 * a tiny folder zip is exactly the shape the window protects) keep it.
 */
class Pass27SettlePolicyTest {

    private val S = DownloadSettlePolicy.SETTLE_MS
    private val MAX = DownloadSettlePolicy.MAX_BYTES

    @Test fun `no handoff flag - no settle, byte-identical modern behavior`() {
        assertEquals(0L, DownloadSettlePolicy.settleMs(handoff = false, wasReAwaited = false, sizeBytes = 10L))
        assertEquals(0L, DownloadSettlePolicy.settleMs(handoff = false, wasReAwaited = false, sizeBytes = 0L))
        assertEquals(0L, DownloadSettlePolicy.settleMs(handoff = false, wasReAwaited = true, sizeBytes = 10L))
    }

    @Test fun `small bodies keep the protective settle`() {
        assertEquals(S, DownloadSettlePolicy.settleMs(true, false, 10L))
        assertEquals(S, DownloadSettlePolicy.settleMs(true, false, 100 * 1024L))          // 100 KB
        assertEquals(S, DownloadSettlePolicy.settleMs(true, false, 2L * 1024 * 1024))   // 2 MB
        assertEquals(S, DownloadSettlePolicy.settleMs(true, false, MAX))                 // the boundary itself
    }

    @Test fun `large known bodies skip the settle - the per-item cost this pass removes`() {
        assertEquals(0L, DownloadSettlePolicy.settleMs(true, false, MAX + 1))
        assertEquals(0L, DownloadSettlePolicy.settleMs(true, false, 30L * 1024 * 1024)) // the ~30 MB ZIP report
        assertEquals(0L, DownloadSettlePolicy.settleMs(true, false, 500L * 1024 * 1024))
    }

    @Test fun `unknown sizes keep the settle - a tiny folder zip is the exact shape it protects`() {
        assertEquals(S, DownloadSettlePolicy.settleMs(true, false, 0L))
        assertEquals(S, DownloadSettlePolicy.settleMs(true, false, -1L))
    }

    @Test fun `the download manager's confirmed re-request never settles`() {
        // The re-await already happened: this request IS the confirmed
        // delivery — stream immediately, however small the body.
        assertEquals(0L, DownloadSettlePolicy.settleMs(true, true, 10L))
        assertEquals(0L, DownloadSettlePolicy.settleMs(true, true, 0L))
        assertEquals(0L, DownloadSettlePolicy.settleMs(true, true, MAX + 1))
    }
}
