package com.lumidrop.x.transfer

import com.lumidrop.x.server.PwdGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ── GOAT-PASS 13 (§4): JVM verification of the password gate's read-only
 * failure-count accessor — the number the /api/login 401 response reports so
 * the browser can show "N attempts remaining".
 *
 * The gate itself is Android-free by design (same discipline as the transfer
 * engine's own tests), so its policy — including the new accessor — runs
 * headless here. What must hold:
 *   • failedAttempts() mirrors exactly the verdicts onWrongPassword issued;
 *   • it never counts an empty/no-body submission (those never reach the
 *     gate — verified by the endpoint's own ordering);
 *   • a correct password (while ALLOW) resets the visible count to zero
 *     together with the real state — a successful login can never show a
 *     stale counter afterwards;
 *   • the count a client sees is purely a REPORT: nothing in the accessor's
 *     contract can reset, bypass or manipulate the authoritative counters
 *     (read-only by construction — no mutation path exists).
 */
class PwdGateTest {

    private fun gate() = PwdGate(maxFailsBeforeLock = 5, maxFailsBeforeBlock = 10)

    @Test
    fun `failedAttempts tracks consecutive wrong passwords`() {
        val g = gate()
        assertEquals(0, g.failedAttempts("a"))
        g.onWrongPassword("a", 1000L)   // 1
        assertEquals(1, g.failedAttempts("a"))
        g.onWrongPassword("a", 2000L)   // 2
        g.onWrongPassword("a", 3000L)   // 3
        assertEquals(3, g.failedAttempts("a"))
        // independent clients are independent counters
        assertEquals(0, g.failedAttempts("b"))
    }

    @Test
    fun `lockout arms at the configured threshold and the count survives it`() {
        val g = gate()
        repeat(4) { assertEquals(PwdGate.Verdict.ALLOW, g.onWrongPassword("a", it.toLong() * 1000)) }
        assertEquals(4, g.failedAttempts("a"))
        val v = g.onWrongPassword("a", 5000L)   // 5th — arms the lock
        assertTrue(v is PwdGate.Verdict.LOCKED)
        assertEquals(5, g.failedAttempts("a"))
        // attempts during the lock are refused WITHOUT evaluation — the
        // reported count must not move (they were never evaluated)
        g.onWrongPassword("a", 6000L)
        assertEquals(5, g.failedAttempts("a"))
        // after the lock expires the count continues toward the block
        assertEquals(PwdGate.Verdict.ALLOW, g.onWrongPassword("a", 60000L))  // 6
        assertEquals(6, g.failedAttempts("a"))
    }

    @Test
    fun `block at the second threshold clears the tracked state`() {
        val g = gate()
        repeat(5) { g.onWrongPassword("a", it.toLong() * 1000) }            // lock arms
        repeat(4) { g.onWrongPassword("a", 60000L + it.toLong() * 1000) }   // 6..9
        assertEquals(9, g.failedAttempts("a"))
        val v = g.onWrongPassword("a", 70000L)   // 10th — BLOCK, state handed to the session block
        assertTrue(v is PwdGate.Verdict.BLOCK)
        assertEquals(0, g.failedAttempts("a"))
    }

    @Test
    fun `correct password while allowed resets the visible count with the state`() {
        val g = gate()
        repeat(3) { g.onWrongPassword("a", it.toLong() * 1000) }
        assertEquals(3, g.failedAttempts("a"))
        assertEquals(PwdGate.Verdict.ALLOW, g.onCorrectPassword("a", 4000L))
        assertEquals(0, g.failedAttempts("a"))
        assertFalse(g.isLocked("a", 4000L))
    }

    @Test
    fun `correct password while locked is still refused and keeps the count`() {
        val g = gate()
        repeat(5) { g.onWrongPassword("a", it.toLong() * 1000) }
        val v = g.onCorrectPassword("a", 6000L)   // inside the 30 s wait
        assertTrue(v is PwdGate.Verdict.LOCKED)
        assertEquals(5, g.failedAttempts("a"))
    }

    @Test
    fun `reset forgets the client entirely`() {
        val g = gate()
        repeat(5) { g.onWrongPassword("a", it.toLong() * 1000) }
        g.reset("a")
        assertEquals(0, g.failedAttempts("a"))
        assertEquals(PwdGate.Verdict.ALLOW, g.onWrongPassword("a", 7000L))
        assertEquals(1, g.failedAttempts("a"))
    }
}
