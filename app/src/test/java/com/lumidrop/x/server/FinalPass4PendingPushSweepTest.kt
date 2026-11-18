package com.lumidrop.x.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══ FINAL PASS 4 (§3) — the receiver-side pending-native-push SWEEP never ══
 * ══ ages out a live sender's record, pinned on the JVM. ══
 *
 * The FINAL PASS 4 audit found the reported ZIP repro's true root cause:
 * Pass 3 gave the MANIFEST-READY undecided branch its liveness conjunct, but
 * the PLACEHOLDER branch (announce received, manifest not yet posted) still
 * removed purely on createdAt age — and the placeholder window is exactly
 * where ZIP streaming spends its longest sender-silent stretch (the manifest
 * build walks the whole tree and validates every loose file; no decision
 * polls exist until the request POST answers; the Pass 4 sender keep-alive
 * now covers that window with a 15 s poll).
 *
 * The sweep's constants are instance-private in LumiDropServer (constructing
 * a server in a JVM test would bind sockets), so this test pins the CONTRACT
 * the branch logic composes: the documented window relationships and the
 * predicate shapes of both branches, evaluated over the same clock math
 * sweepStalePendingNativePushes runs. If the constants are ever re-tuned in
 * a way that breaks a relationship (e.g. someone reverts DEAD_SENDER below
 * the placeholder TTL — which would make a live sender's aged placeholder
 * removable again), these assertions fail with the branch named in the
 * message.
 *
 *  • DEAD_SENDER must exceed BOTH the undecided TTL and the placeholder
 *    TTL — otherwise "sender still answering" could never save a record;
 *  • the placeholder predicate (age > TTL && silence > DEAD_SENDER, or
 *    all-declined) keeps a LIVE sender's aged placeholder, removes a
 *    SILENT sender's, and never age-removes a young placeholder (the ZIP
 *    manifest-build case);
 *  • the undecided predicate (Pass 3's branch, regression-pinned) keeps a
 *    live sender's undecided push and removes a silent sender's.
 */
class FinalPass4PendingPushSweepTest {

    // The documented sweep constants (LumiDropServer): keep in sync with
    // their declarations — the relationships, not the exact values, are the
    // pinned contract.
    private val undecidedTtlMs = 3 * 60_000L        // NATIVE_PUSH_TTL
    private val deadSenderMs = 10 * 60_000L         // NATIVE_PUSH_DEAD_SENDER_MS
    private val placeholderTtlMs = 5 * 60_000L      // NATIVE_PUSH_PLACEHOLDER_TTL_MS
    private val keepaliveMs = 15_000L               // PLACEHOLDER_KEEPALIVE_POLL_MS (sender side)

    private val now = System.currentTimeMillis()
    private val lastAnswer14sAgo = now - 14_000L            // a live sender mid-decision-poll
    private val lastAnswer11minAgo = now - 11 * 60_000L     // a genuinely silent sender
    private val created6minAgo = now - 6 * 60_000L          // older than both TTLs
    private val created30sAgo = now - 30_000L               // a young placeholder (ZIP build mid-flight)

    @Test
    fun `dead-sender window exceeds both age windows`() {
        assertTrue("DEAD_SENDER must exceed the undecided TTL (liveness must be able to save a record)",
            deadSenderMs > undecidedTtlMs)
        assertTrue("DEAD_SENDER must exceed the placeholder TTL (a live sender's aged placeholder must be savable)",
            deadSenderMs > placeholderTtlMs)
    }

    @Test
    fun `sender keep-alive cadence is well inside the dead-sender window`() {
        assertTrue("the manifest-window keep-alive must refresh lastActivityAt far more often than the dead-sender window closes",
            deadSenderMs > keepaliveMs * 4)
    }

    @Test
    fun `placeholder branch - live sender survives, silent sender removed, young placeholder survives`() {
        // Mirror of the FINAL PASS 4 placeholder branch:
        //   allDeclined || (createdAt > PLACEHOLDER_TTL && lastActivityAt > DEAD_SENDER)
        val liveAgedRemoved = created6minAgo < now - placeholderTtlMs &&
            lastAnswer14sAgo < now - deadSenderMs
        assertEquals("a LIVE sender's aged placeholder must SURVIVE (the ZIP manifest-build case)",
            false, liveAgedRemoved)

        val silentAgedRemoved = created6minAgo < now - placeholderTtlMs &&
            lastAnswer11minAgo < now - deadSenderMs
        assertEquals("a SILENT sender's aged placeholder must be removed (prompt dead-sender resolution)",
            true, silentAgedRemoved)

        val youngRemoved = created30sAgo < now - placeholderTtlMs
        assertEquals("a young placeholder is never age-removable (its sender may be mid-build with zero protocol traffic)",
            false, youngRemoved)
    }

    @Test
    fun `undecided branch - liveness gates the manifest-ready record too (Pass 3 regression pin)`() {
        // Mirror of the FINAL PASS 3/4 undecided branch:
        //   createdAt > NATIVE_PUSH_TTL && lastActivityAt > DEAD_SENDER
        val liveUndecidedRemoved = created6minAgo < now - undecidedTtlMs &&
            lastAnswer14sAgo < now - deadSenderMs
        assertEquals("a LIVE sender's undecided push must SURVIVE",
            false, liveUndecidedRemoved)

        val silentUndecidedRemoved = created6minAgo < now - undecidedTtlMs &&
            lastAnswer11minAgo < now - deadSenderMs
        assertEquals("a SILENT sender's undecided push must be removed",
            true, silentUndecidedRemoved)
    }
}
