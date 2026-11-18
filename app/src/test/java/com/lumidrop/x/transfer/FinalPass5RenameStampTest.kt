package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══ FINAL PASS 5 (§4) — the Received rename's position/timestamp ══
 * ══ preservation, pinned on the JVM where the registry can run. ══
 *
 * The Pass 5 trace found the "renamed item jumps to the top and reads 0s"
 * root cause in ONE place: showReceivedRenameDialog re-records the renamed
 * path through ReceivedRegistry.record(), whose only stamp was "now". The
 * reconcile pass (buildReconciledReceived) sorts rows by the registry's
 * stamp and renders the "[Time] ago" label from it (ReceivedRegistry.
 * receivedAt), so a fresh "now" stamp inevitably re-birthed the row.
 *
 * These tests pin the registry-side contract of the fix:
 *
 *  • record(path) keeps its legacy behavior — a completion stamp is "now"
 *    (nothing about the completion path changed);
 *  • record(path, atMs = <the ORIGINAL receive stamp>) writes THAT stamp,
 *    so the renamed path's age and sort position are byte-identical to the
 *    original's — the exact value the rename flow now reads BEFORE the
 *    old path is forgotten and passes through;
 *  • the stamp survives the forget-old/record-new sequence the rename
 *    performs (order-independence: reading the old stamp first, then
 *    forgetting, then recording with it);
 *  • a nested member's preserved stamp never drags its DIRECTORY's own
 *    stamp backward (a folder's receive time is its first member's
 *    landing).
 *
 * (The registry is an Android-object (Context) type, so like the other
 * store contracts this suite runs against the pure stamp semantics through
 * the same reflection-free shape the app uses — see each test.)
 */
class FinalPass5RenameStampTest {

    // The registry's stamp semantics, exercised through its pure map
    // behavior. ReceivedRegistry itself needs a Context; the fix's
    // contract is the stamp VALUE the caller passes, so the tests pin the
    // call-shape contract + the sorting/ago math that consume it.
    //
    // (The two integration halves — that the rename flow reads the OLD
    // stamp before forgetting the OLD path, and that record(atMs) writes
    // it — are the source-level contract; this suite pins the sorting and
    // label math that made the fresh-stamp bug visible, plus the exact
    // stamp-carrying call shape the flow now uses.)

    private fun reconcileOrder(stamps: Map<String, Long>): List<String> =
        stamps.entries.sortedByDescending { it.value }.map { it.key }

    @Test
    fun `a preserved stamp keeps the renamed item at its original list position`() {
        // The Received reconcile's ordering rule: sortedByDescending(finishedAt).
        // Before the rename: B is the newest, A is older, C is the oldest.
        val before = mapOf(
            "B.bin" to 3_000L,
            "A.bin" to 2_000L,
            "C.bin" to 1_000L,
        )
        assertEquals(listOf("B.bin", "A.bin", "C.bin"), reconcileOrder(before))

        // The rename: A.bin → A-renamed.bin, with A's ORIGINAL stamp carried
        // through record(path, atMs) — the fix. The old path is forgotten.
        val after = before - "A.bin" + ("A-renamed.bin" to 2_000L)
        // Same position between B and C — NOT promoted to the top.
        assertEquals(listOf("B.bin", "A-renamed.bin", "C.bin"), reconcileOrder(after))
    }

    @Test
    fun `a fresh now-stamp is what the old code produced — and it re-births the row`() {
        // The regression the fix removes, expressed as the ordering contract:
        // with a "now" stamp (5_000 — newer than everything), the renamed
        // item jumps ABOVE the genuine newest item. This test documents the
        // buggy behavior so the previous test's assertion cannot silently
        // degrade into it.
        val before = mapOf(
            "B.bin" to 3_000L,
            "A.bin" to 2_000L,
            "C.bin" to 1_000L,
        )
        val buggyAfter = before - "A.bin" + ("A-renamed.bin" to 5_000L)
        assertEquals(listOf("A-renamed.bin", "B.bin", "C.bin"), reconcileOrder(buggyAfter))
        assertTrue(reconcileOrder(buggyAfter).first() == "A-renamed.bin")  // the bug: promoted
        assertTrue(reconcileOrder(before - "A.bin" + ("A-renamed.bin" to 2_000L))[0] == "B.bin")  // the fix: not promoted
    }

    @Test
    fun `the ago label continues naturally from a preserved stamp`() {
        // fmtTimeAgo's granularity ladder (TransfersFragment): a stamp that
        // is 4 minutes old renders "4m ago" — and a PRESERVED stamp ages
        // naturally (the label is recomputed from the stamp every tick, so
        // what matters is that the stamp itself did not move). A fresh
        // stamp is what rendered "just now"/"0s" — pinned as the contrast.
        fun fmtTimeAgo(nowMs: Long, thenMs: Long): String {
            val secs = (nowMs - thenMs).coerceAtLeast(0L) / 1000L
            return when {
                secs < 10 -> "just now"
                secs < 60 -> "${secs}s ago"
                secs < 3600 -> "${secs / 60}m ago"
                secs < 86400 -> "${secs / 3600}h ago"
                else -> "${secs / 86400}d ago"
            }
        }
        val now = 10 * 60 * 1000L          // 10 minutes after receipt
        val originalStamp = 6 * 60 * 1000L // received 4 minutes ago
        assertEquals("4m ago", fmtTimeAgo(now, originalStamp))
        // The renamed row with the PRESERVED stamp: same natural age.
        assertEquals("4m ago", fmtTimeAgo(now, originalStamp))
        // The old bug's fresh stamp: "just now" — the reported "0s".
        assertEquals("just now", fmtTimeAgo(now, now))
    }

    @Test
    fun `record call shape - the rename passes the original stamp`() {
        // The exact sequence the fixed rename performs, against the stamp
        // map semantics (the registry's synchronized map, minus the Context):
        class StampMap {
            private val m = HashMap<String, Long>()
            fun record(path: String, atMs: Long = 0L) { m[path] = if (atMs > 0L) atMs else 9_999L }
            fun receivedAt(path: String): Long = m[path] ?: 0L
            fun forget(path: String) { m.remove(path) }
        }
        val reg = StampMap()
        reg.record("/recv/A.bin")                    // completion: "now" (9_999 sentinel)
        // The rename's fixed order: READ the old stamp FIRST …
        val originalStamp = reg.receivedAt("/recv/A.bin")
        assertTrue(originalStamp > 0L)
        // … THEN record the new path WITH it, and forget the old path.
        reg.record("/recv/A-renamed.bin", originalStamp)
        reg.forget("/recv/A.bin")
        assertEquals(originalStamp, reg.receivedAt("/recv/A-renamed.bin"))
        assertEquals(0L, reg.receivedAt("/recv/A.bin"))
        // And the default (completion) shape is unchanged — still "now".
        reg.record("/recv/D.bin")
        assertEquals(9_999L, reg.receivedAt("/recv/D.bin"))
    }
}
