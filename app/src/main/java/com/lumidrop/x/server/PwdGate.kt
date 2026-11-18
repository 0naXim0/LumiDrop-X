package com.lumidrop.x.server

/**
 * ══ GOAT-PASS 12 (§9): the password failed-attempt policy ══
 *
 * A pure, Android-free policy object (unit-tested headless, same discipline
 * as the transfer engine's own tests) that tracks consecutive wrong
 * passwords per client key and decides, atomically, what the NEXT attempt
 * is allowed to do:
 *
 *   • [Verdict.ALLOW]        — the attempt may be evaluated normally;
 *   • [Verdict.LOCKED]       — a temporary waiting period is active; the
 *                              attempt is refused WITHOUT evaluation (even a
 *                              correct password cannot shortcut the wait —
 *                              enforced on the authoritative side, so no
 *                              refresh, reconnect or parallel request can
 *                              bypass it);
 *   • [Verdict.BLOCK]        — the client has exhausted its second chance;
 *                              the caller blocks it through the EXISTING
 *                              session-blocking mechanism (kickedIPs — see
 *                              LumiDropServer.apiLogin).
 *
 * Policy shape (defaults; deliberately simple — proportionate, not an
 * account-lockout system):
 *   fails 1..4   → ALLOW, counted;
 *   fail  5      → LOCKED for [lockMs] (default 30 s);
 *   fails 6..9   → ALLOW after the wait expires, counted;
 *   fail 10      → BLOCK (5 additional failures after the first lockout —
 *                  exactly the "configured number of additional incorrect
 *                  attempts" the brief describes).
 *   A CORRECT password (only reachable while ALLOW) resets the client's
 *   state entirely — a legitimate user who typos a few times is never
 *   penalized after succeeding.
 *
 * Thread-safety: every state mutation runs inside a ConcurrentHashMap
 * compute(), so simultaneous password attempts from the same client are
 * serialized and every one of them counts — no race can slip an evaluation
 * past a lock that just armed, and no double-increment can skip a
 * threshold. The verdict and the count update are ONE atomic step.
 */
class PwdGate(
    private val maxFailsBeforeLock: Int = 5,
    private val lockMs: Long = 30_000L,
    private val maxFailsBeforeBlock: Int = 10,
) {

    /** What the caller must do with an incoming password attempt. */
    sealed class Verdict {
        object ALLOW : Verdict()
        /** The client must wait [remainingMs] before its next attempt. */
        class LOCKED(val remainingMs: Long) : Verdict()
        /** The client exhausted its second chance — block it this session. */
        object BLOCK : Verdict()
    }

    private class State {
        var fails: Int = 0
        var lockedUntil: Long = 0L
    }

    private val states = HashMap<String, State>()
    private val lock = Any()

    /**
     * The verdict for an attempt from [key] at time [now], AND the state
     * update that goes with it — one atomic step:
     *   • while a temporary lock is active → LOCKED (state untouched: the
     *     waiting period does not extend or reset on new attempts);
     *   • otherwise the fail counter increments; crossing the FIRST
     *     threshold (exactly — see below) arms the temporary lock; crossing
     *     the second returns BLOCK and clears the tracked state (the
     *     caller's session block takes over enforcement from here — keeping
     *     the entry would only make an unblocked client inherit a stale
     *     counter).
     *
     * The lock arms only at the exact crossing ([fails] == maxFailsBeforeLock),
     * never on later failures: after the wait expires, the post-lockout
     * attempts 6..9 are ALLOW-then-counted (the client's genuine second
     * chance — re-arming on every fail ≥ 5 would make the block threshold
     * unreachable), and the 10th returns BLOCK.
     */
    fun onWrongPassword(key: String, now: Long): Verdict {
        synchronized(lock) {
            val st = states.getOrPut(key) { State() }
            if (st.lockedUntil > now) return Verdict.LOCKED(st.lockedUntil - now)
            st.fails += 1
            return when {
                st.fails >= maxFailsBeforeBlock -> {
                    states.remove(key)
                    Verdict.BLOCK
                }
                st.fails == maxFailsBeforeLock -> {
                    st.lockedUntil = now + lockMs
                    Verdict.LOCKED(lockMs)
                }
                else -> Verdict.ALLOW
            }
        }
    }

    /**
     * The pre-evaluation verdict for a CORRECT password: a correct password
     * submitted while LOCKED is still refused (the wait is the wait), and
     * only a correct password while ALLOW resets the client's failure
     * state completely.
     */
    fun onCorrectPassword(key: String, now: Long): Verdict {
        synchronized(lock) {
            val st = states[key]
            if (st != null && st.lockedUntil > now) return Verdict.LOCKED(st.lockedUntil - now)
            states.remove(key)
            return Verdict.ALLOW
        }
    }

    /** Whether [key] is currently inside a temporary waiting period. */
    fun isLocked(key: String, now: Long): Boolean {
        synchronized(lock) {
            val st = states[key] ?: return false
            return st.lockedUntil > now
        }
    }

    /** ── GOAT-PASS 13 (§4): the consecutive-failure count for [key] after ──
     *  the latest verdict — the one number the login response reports so the
     *  browser can show an honest "N attempts remaining" line. Strictly
     *  read-only: nothing a client can send reaches this counter except a
     *  genuine wrong-password evaluation, and no caller can reset, bypass or
     *  manipulate the authoritative state through it. */
    fun failedAttempts(key: String): Int {
        synchronized(lock) { return states[key]?.fails ?: 0 }
    }

    /**
     * Forgets [key]'s failure state entirely — the unblock path calls this
     * so a client the host explicitly unblocked starts genuinely fresh
     * (no inherited counter, no inherited lock).
     */
    fun reset(key: String) {
        synchronized(lock) { states.remove(key) }
    }
}
