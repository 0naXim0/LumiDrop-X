package com.lumidrop.x.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * GOAT-PASS 12 (§9): the password failed-attempt policy's JUnit suite.
 * PwdGate is deliberately Android-free (see its doc), so this runs headless
 * beside the transfer-engine suite with the same setup.
 *
 * The required-behavior matrix from the brief:
 *   • correct password on the first attempt → ALLOW (and resets state);
 *   • incorrect passwords counted per client;
 *   • temporary lockout arms at the configured threshold;
 *   • attempts during lockout are refused — including CORRECT passwords;
 *   • after the wait expires the client may try again;
 *   • the configured number of ADDITIONAL wrong attempts → BLOCK;
 *   • a different client is never affected (independent state);
 *   • a correct password (while allowed) resets the failure state.
 */
class PwdGateTest {

    private fun gate() = PwdGate(maxFailsBeforeLock = 5, lockMs = 30_000L, maxFailsBeforeBlock = 10)
    private fun allow(v: PwdGate.Verdict) = v is PwdGate.Verdict.ALLOW
    private fun lockedMs(v: PwdGate.Verdict) = (v as? PwdGate.Verdict.LOCKED)?.remainingMs ?: -1L
    private fun block(v: PwdGate.Verdict) = v is PwdGate.Verdict.BLOCK

    @Test
    fun `wrong attempts below the threshold are allowed and counted`() {
        val g = gate()
        val t = 1_000_000L
        for (i in 1..4) assertTrue(allow(g.onWrongPassword("a", t + i)))
    }

    @Test
    fun `the fifth wrong attempt arms the temporary lockout`() {
        val g = gate()
        val t = 1_000_000L
        repeat(4) { g.onWrongPassword("a", t) }
        val v = g.onWrongPassword("a", t + 5)
        assertTrue(v is PwdGate.Verdict.LOCKED)
        assertEquals(30_000L, lockedMs(v))
    }

    @Test
    fun `attempts during the lockout are refused even when correct`() {
        val g = gate()
        val t = 1_000_000L
        repeat(5) { g.onWrongPassword("a", t) }
        // inside the 30s window: a CORRECT password is still refused
        val v = g.onCorrectPassword("a", t + 10_000L)
        assertTrue(v is PwdGate.Verdict.LOCKED)
        assertTrue(g.isLocked("a", t + 10_000L))
        // and further wrong attempts do not extend the wait
        val v2 = g.onWrongPassword("a", t + 15_000L)
        assertTrue(v2 is PwdGate.Verdict.LOCKED)
        assertEquals(15_000L, lockedMs(v2))
    }

    @Test
    fun `after the wait expires the client may try again and the next five failures block`() {
        val g = gate()
        val t = 1_000_000L
        repeat(5) { g.onWrongPassword("a", t) }
        // the wait expires at t+30_000: fails 6..9 are allowed again…
        for (i in 6..9) assertTrue(allow(g.onWrongPassword("a", t + 31_000L + i)))
        // …and the configured number of ADDITIONAL attempts (the 10th) blocks.
        assertTrue(block(g.onWrongPassword("a", t + 40_000L)))
    }

    @Test
    fun `a correct password while allowed resets the failure state completely`() {
        val g = gate()
        val t = 1_000_000L
        repeat(4) { g.onWrongPassword("a", t) }   // one away from the lockout
        assertTrue(allow(g.onCorrectPassword("a", t + 1)))
        // a fresh cycle: the counter restarted, so five MORE wrong attempts
        // are needed before the lockout arms again.
        for (i in 1..4) assertTrue(allow(g.onWrongPassword("a", t + 10_000L + i)))
        assertTrue(g.onWrongPassword("a", t + 20_000L) is PwdGate.Verdict.LOCKED)
    }

    @Test
    fun `clients are independent - one client's failures never touch another`() {
        val g = gate()
        val t = 1_000_000L
        repeat(5) { g.onWrongPassword("bad-client", t) }
        assertTrue(g.isLocked("bad-client", t + 1_000L))
        assertFalse(g.isLocked("other-client", t + 1_000L))
        assertTrue(allow(g.onCorrectPassword("other-client", t + 1_000L)))
    }

    @Test
    fun `after a block the caller's session-block takes over - reset clears the slate`() {
        val g = gate()
        val t = 1_000_000L
        repeat(10) { g.onWrongPassword("a", t) }
        // the 10th returned BLOCK and consumed the tracked state; the host's
        // Unblock path calls reset(), and the client genuinely starts fresh.
        g.reset("a")
        assertFalse(g.isLocked("a", t + 60_000L))
        for (i in 1..4) assertTrue(allow(g.onWrongPassword("a", t + 100_000L + i)))
    }

    @Test
    fun `lockout expiry is a matter of time, not of a new attempt`() {
        val g = gate()
        val t = 1_000_000L
        repeat(5) { g.onWrongPassword("a", t) }
        assertTrue(g.isLocked("a", t + 29_999L))
        assertFalse(g.isLocked("a", t + 30_000L))
    }
}
