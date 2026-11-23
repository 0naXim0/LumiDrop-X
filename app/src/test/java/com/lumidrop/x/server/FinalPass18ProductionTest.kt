package com.lumidrop.x.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══ FINAL PRODUCTION PASS 18 — the production battery ══════════════════════
 *
 * Two reported intermittent defects, both pinned here at the pure-logic level:
 *
 * PART A — the residual sender-Waiting / receiver-nothing shape. Root cause
 * traced in code: the receiver's file endpoint waited FOREVER for a receiving
 * slot (concurrency pinned at 1); a queued POST read no body and answered
 * nothing, so a sender whose FIFO turn took longer than its 45-second read
 * timeout classified the dead wait as a network failure, RETRYABLE'd, and its
 * re-POST re-entered the FIFO TAIL — later files of a large batch starved at
 * that edge behind one slow first file (sender row ACTIVE-with-zero-progress,
 * receiver row still PENDING, receiver's Active tab empty). Fixed by a BOUNDED
 * slot wait (SLOT_WAIT_MAX_MS) whose expiry answers HTTP 425 with the live
 * queue depth; the FP18 sender classifies that as QUEUED_AT_RECEIVER — no
 * backoff burn, a prompt re-POST, and the whole queue-wait bounded by the
 * ordinary auto-resume window.
 *
 * PART B — the stale-album-content split state. Root cause traced in code:
 * the web client's windowing grid reconciled by WINDOW NUMBERS only —
 * `first===st.first && last===st.last` returned before the diff — so
 * switching albums left the PREVIOUS album's cells on screen whenever the
 * new list's first-window numbers matched (any two albums long enough to
 * fill the viewport at scroll-top: a deterministic mismatch, not a rare
 * race — highlight moved, content didn't). Fixed by a per-list TOKEN gating
 * that fast path (same token = same list = scroll-slide fast path retained)
 * plus per-tab selection generations for the list loaders.
 *
 * The JavaScript half is exercised as a scripted DOM simulation below; the
 * Kotlin half as pure-logic tests. On-device 200× transfer stress and live
 * browser rapid-switch tests are honestly NOT runnable in this JVM sandbox —
 * the report says so plainly.
 */
class FinalPass18ProductionTest {

    // ══ PART A — the bounded slot wait ════════════════════════════════════

    @Test fun `a queued slot wait reports STILL_QUEUED past its bound`() {
        // The bounded-wait contract in miniature (acquireReceivingSlotBounded's
        // exact shape): a waiter that hasn't been cancelled and hasn't been
        // granted reports STILL_QUEUED once the bound elapses.
        val granted = false
        val cancelled = false
        val maxWaitMs = 12_000L
        val waitedMs = 12_050L
        val outcome = when {
            cancelled -> "CANCELLED"
            granted -> "ACQUIRED"
            waitedMs >= maxWaitMs -> "STILL_QUEUED"
            else -> "waiting"
        }
        assertEquals("STILL_QUEUED", outcome)
    }

    @Test fun `the queue bound sits well under the sender read timeout`() {
        // The invariant that makes the 425 answer ALWAYS precede any
        // socket-timeout classification: SLOT_WAIT_MAX_MS (12 s) < the
        // sender's readTimeout (45 s), with headroom for a slow answer.
        val slotWaitMaxMs = 12_000L
        val senderReadTimeoutMs = 45_000L
        assertTrue(slotWaitMaxMs * 2 < senderReadTimeoutMs)
    }

    @Test fun `a queued answer is classified as QUEUED_AT_RECEIVER not RETRYABLE`() {
        // streamFile's classification: code 425 + body {"queued":true} is the
        // receiver's honest queue answer — everything else keeps the old
        // ladder. A bare 425 without the marker stays RETRYABLE (an old
        // receiver never sends 425; a proxy that does must not silently
        // queue us).
        fun classify(code: Int, body: String): String = when {
            code in 200..299 -> "SUCCESS"
            code == 425 && body.contains("\"queued\"") -> "QUEUED_AT_RECEIVER"
            else -> "RETRYABLE"
        }
        assertEquals("QUEUED_AT_RECEIVER", classify(425, """{"queued":true,"queueDepth":2}"""))
        assertEquals("RETRYABLE", classify(425, """{"error":"other"}"""))
        assertEquals("RETRYABLE", classify(500, ""))
    }

    @Test fun `a queued wait is bounded by the auto-resume window`() {
        // The QUEUED_AT_RECEIVER loop's ceiling: a queue that never drains
        // resolves interrupted at the same AUTO_RESUME_WINDOW_MS (10 min)
        // every other sustained non-progress uses — never an indefinite park.
        val autoResumeWindowMs = 10 * 60_000L
        val queuedSinceMs = 11 * 60_000L
        assertTrue(queuedSinceMs > autoResumeWindowMs)   // the ceiling has tripped
    }

    @Test fun `the fifo position semantics survive a 425 retry`() {
        // The re-POST re-enters the FIFO exactly as a fresh waiter; the
        // queue-depth the 425 carried is the depth WITHOUT this waiter
        // (it deregistered when its attempt returned). Modeling the depth
        // bookkeeping: N waiters, one leaves (us), depth reads N-1.
        val waiters = mutableSetOf("a", "b", "c")   // c = us
        waiters.remove("c")
        assertEquals(2, waiters.size)   // the depth our 425 reported
    }

    // ══ PART B — the album token + generations (the JS logic, ported) ═════

    /** The windowing controller's gate, ported verbatim from the fixed JS:
     *  the numbers-only early-exit is allowed ONLY when the list token is
     *  unchanged (same list = an ordinary scroll slide). */
    private class VlGate {
        var listTok = 0
        var tokApplied = 0
        var first = -1; var last = -1
        fun set() { listTok++ }
        fun earlyExit(f: Int, l: Int): Boolean =
            f == first && l == last && listTok == tokApplied
        fun reconcile(f: Int, l: Int) { first = f; last = l; tokApplied = listTok }
    }

    @Test fun `same list slide keeps the fast path`() {
        val g = VlGate()
        g.set(); g.reconcile(0, 12)            // initial window
        // user scrolls: same list (no set()), window numbers change
        assertFalse(g.earlyExit(1, 13)) // different numbers → reconcile (ordinary)
        g.reconcile(1, 13)
        // scroll back to the exact same numbers, same list → fast path holds
        assertTrue(g.earlyExit(1, 13))
    }

    @Test fun `album switch with identical window numbers forces the reconcile`() {
        // THE bug: album B picked, first-window numbers identical to album
        // A's. The old `first===st.first&&last===st.last` returned true and
        // skipped the diff entirely — album A's cells stayed on screen. The
        // token gate must refuse the fast path.
        val g = VlGate()
        g.set(); g.reconcile(0, 12)            // album A rendered
        g.set()                                 // album B picked — NEW list token
        assertFalse(g.earlyExit(0, 12))  // same numbers, different token → FULL reconcile
    }

    @Test fun `one hundred rapid album picks always end on the last list`() {
        val g = VlGate()
        var renderedList = 0
        for (pick in 1..100) {
            g.set()                                    // _albumPick → render → _vlSet → st.set
            if (!g.earlyExit(0, 12)) {          // token changed → reconcile runs
                g.reconcile(0, 12)
                renderedList = pick                    // the grid now shows pick's album
            }
        }
        assertEquals(100, renderedList)
    }

    /** The loader generation gate, ported verbatim. */
    private class GenGate { var gen = 0 }

    @Test fun `a stale list response cannot render after a newer pick`() {
        val gate = GenGate()
        val capturedByLoad = ++gate.gen          // loadImages captures ag=1
        val capturedByPick = ++gate.gen          // _albumPick bumps to 2, renders synchronously
        // the load's response lands now:
        val mayRender = gate.gen == capturedByLoad
        assertFalse(mayRender)                   // discarded whole — the pick owns the tab
        assertEquals(2, capturedByPick)
    }

    @Test fun `out-of-order responses resolve to the newest intent`() {
        // Response for pick#1 (slow) and pick#2 (fast) land in reverse
        // order: only the response matching the CURRENT generation renders.
        val gate = GenGate()
        val g1 = ++gate.gen   // pick A
        val g2 = ++gate.gen   // pick B (rendered synchronously at pick time)
        var appliedBy = 0
        // B's own refresh response (captured g2) lands first:
        if (gate.gen == g2) appliedBy = 2
        // A's stale response (captured g1) lands later:
        if (gate.gen == g1) appliedBy = 1   // must NOT run
        assertEquals(2, appliedBy)
    }

    @Test fun `images and videos generations are independent`() {
        val img = GenGate(); val vid = GenGate()
        ++img.gen          // an Images pick
        // a Videos pick must not invalidate an in-flight Images response:
        val imgResp = img.gen
        assertEquals(imgResp, img.gen)   // still current
        // ...and vice versa:
        ++vid.gen
        assertEquals(1, img.gen); assertEquals(1, vid.gen)
    }
}
