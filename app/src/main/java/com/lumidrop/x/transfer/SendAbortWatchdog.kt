package com.lumidrop.x.transfer

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ══ GOAT-PASS 4 (§4) — the blocked-write deadlock the queue froze behind ══
 *
 * ROOT CAUSE this object closes: [com.lumidrop.x.p2p.NativePushClient].
 * streamFile]'s byte-copy loop checks pause and cancel only at the TOP of
 * each iteration. When the receiving side stops draining the socket — a
 * peer that mirrored a deliberate pause (its receive loop parks inside
 * [TransferManager.awaitUnpaused] and stops reading), or a network that
 * died without FIN/RST — the sender's next `out.write` blocks INSIDE the
 * socket, and the loop-top check never runs again:
 *
 *   • the send coroutine never returns, so its dispatch-semaphore permit
 *     is never released → the next Waiting item never starts (the reported
 *     "pause item 1 → item 2 starts; pause item 2 → item 3 sometimes does
 *     not start" — the freeze is probabilistic because it needs the TCP
 *     window to be exactly full when the receiver parks);
 *   • the stall watchdog exempts paused rows by design, so nothing ever
 *     resolves the wedged leg while the pause stands;
 *   • for a genuinely dead network the watchdog eventually resolves the
 *     ROW interrupted — but the coroutine stays blocked in the write, so
 *     the queue is still frozen behind a leg the engine already gave up
 *     on (TCP retransmission can hold a write open for minutes);
 *   • a deliberate Cancel is equally invisible to a blocked write.
 *
 * THE FIX (root, one mechanism): every live send connection is registered
 * here for exactly the lifetime of its streaming loop, and one small
 * watchdog thread reconciles the CONNECTION's state with the ROW's engine
 * state every 250 ms. The moment the two diverge — the row was cancelled,
 * has been paused past the settle grace, or is no longer genuinely
 * executing (FAILED / COMPLETED / gone underneath the leg) — the
 * connection is forcibly disconnected, which unblocks the wedged write
 * with an immediate IOException. streamFile then classifies the abort via
 * [Handle.consumeCause]: a PAUSE abort returns [StreamOutcome].PAUSED so
 * the file parks through the exact same path a noticed-at-loop-top pause
 * uses (permit released, checkpoint preserved, resume relaunches from
 * it), and every other cause falls into the ordinary retry loop, whose
 * top-of-loop cancel check and probe/reopen machinery already own the
 * outcomes.
 *
 * ══ GOAT-PASS 8 (§11 — Cause.NETWORK, the minute-scale write purgatory) ══
 *
 * The Pass-18 stall machinery already flips a stalled SEND's PRESENTATION
 * to "Auto-Resume on Reconnection" within ~5–8 s (the liveness probe at
 * SEND_STALL_PROBE_MS) — but the leg itself stayed blocked in out.write,
 * because "the peer is unreachable" was never an abort condition HERE:
 * the row was still ACTIVE, unpaused and uncancelled, so this watchdog
 * kept licensing the connection to exist. TCP retransmission backoff then
 * held the write open for tens of seconds to minutes AFTER the sender's
 * Wi-Fi returned — the reported "transfer only progressed again after
 * 35–45 s, sometimes longer". The row's own auto-resume loop (the thing
 * that actually recovers the transfer on a FRESH socket) could never
 * start while the old leg sat blocked in the kernel.
 *
 * New rule, same reconciliation shape: a row that is latched reconnecting
 * (the stall watchdog's probe-backed verdict, or the LAN-transition
 * latch) AND whose bytes have now been silent past a bounded grace gets
 * its connection aborted with Cause.NETWORK. streamFile's classifier maps
 * that to the ordinary RETRYABLE outcome, so the sendOneFile loop does
 * exactly what it always does for a lost connection: bounded backoff,
 * checkpoint probe, fresh-socket resume — the moment the peer is
 * reachable again. Bytes that resume flowing clear the reconnecting
 * latch (updateProgress) and refresh the sample clock along the way, so
 * the rule can never fire on a live stream; the pause path keeps its own
 * dedicated cause and grace.
 *
 * Deliberately Android-free (java.util.concurrent + TransferManager only)
 * so the decision contract is pinned by JVM tests — see
 * app/src/test/java/com/lumidrop/x/transfer/SendAbortWatchdogTest.kt.
 *
 * Thread-safety: register/close are called from the sending coroutines
 * (Dispatchers.IO workers); sweep() runs on the single watchdog thread;
 * consumeCause from the send coroutine that owns the leg. All structures
 * are concurrent, and the registry is keyed by transfer id with
 * IDENTITY-based removal (see [Handle.close]), so a retry leg that
 * replaces a finishing one can never be unregistered by its
 * predecessor's late finally.
 */
object SendAbortWatchdog {

    /** Why a leg was (or should be) aborted. */
    enum class Cause { PAUSE, CANCEL, STATE, NETWORK }

    /**
     * How long a pause must have been standing before the watchdog will
     * abort a leg that has not noticed it. The streaming loop's own
     * yield cadence (awaitUnpausedOrYield's 500 ms wait + 500 ms
     * confirmation sleep ≈ 1 s from the pause stamp) is the fast path;
     * this grace sits just past it so the watchdog only ever acts on a
     * write that is provably wedged, never one the loop was a
     * heartbeat away from handling itself.
     */
    private const val PAUSE_ABORT_GRACE_MS = 1_200L

    /** Sweep cadence — bounds how long a wedged write outlives its row
     *  state divergence. */
    private const val SWEEP_INTERVAL_MS = 250L

    /** ── GOAT-PASS 8 (§11): how long a reconnecting-latched SEND row's bytes ──
     *  must stay silent before the wedged write is aborted onto the network
     *  recovery path. The stall watchdog latches `reconnecting` at
     *  TransferManager.SEND_STALL_PROBE_MS (5 s) of silence plus its probe
     *  round trip; this grace sits past that so a leg whose peer was merely
     *  slow to answer (the probe succeeds, the latch never fires) or whose
     *  bytes resumed (updateProgress clears the latch AND refreshes the
     *  sample clock) can never be touched. A JVM-test tunable. */
    @JvmField val networkAbortSilenceMs = 9_000L

    /**
     * One registered live send leg — the handle returned by [register].
     * [tid] is the row this leg streams for; the disconnect action is
     * installed by the owner; [cause] is written exactly once by the
     * watchdog when it aborts the leg and consumed by the owner's
     * write-exception path.
     */
    class Handle internal constructor(internal val tid: String) {
        /** Performs the actual disconnect of the live connection. Set
         *  once at registration; volatile so sweep() always sees it. */
        @Volatile internal var disconnect: (() -> Unit)? = null
        @Volatile internal var cause: Cause? = null
        internal val aborted = AtomicBoolean(false)

        /**
         * The abort cause recorded for THIS leg, if any — consumed
         * (cleared) on read so it can never be observed twice. Returns
         * null when the watchdog never aborted this leg (the write
         * failed on its own, or the loop finished normally).
         */
        fun consumeCause(): Cause? {
            val c = cause
            cause = null
            return c
        }

        /** Releases the registration — call from the stream loop's
         *  finally, exactly once per successful register. Identity-based:
         *  a newer leg that already replaced this one in the registry is
         *  never disturbed. */
        fun close() {
            legs.remove(tid, this)
        }
    }

    private val legs = ConcurrentHashMap<String, Handle>()
    private val started = AtomicBoolean(false)
    private val exec = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "ldx-send-abort").apply { isDaemon = true }
    }

    /**
     * Registers the live send connection of [tid]. [disconnect] must, when
     * invoked from any thread, promptly unblock the sending thread's
     * blocked write (HttpURLConnection.disconnect() closes the underlying
     * socket — the canonical way). A second registration for the same tid
     * (a retry leg replacing a finishing one) simply replaces the entry;
     * the predecessor's [Handle.close] is identity-guarded and leaves the
     * new leg in place.
     */
    fun register(tid: String, disconnect: () -> Unit): Handle {
        val handle = Handle(tid)
        handle.disconnect = disconnect
        legs[tid] = handle
        ensureStarted()
        return handle
    }

    private fun ensureStarted() {
        if (started.get()) return
        if (!started.compareAndSet(false, true)) return
        exec.scheduleWithFixedDelay({
            try { sweep() } catch (e: Exception) { /* one bad sweep must never kill the schedule */ }
        }, SWEEP_INTERVAL_MS, SWEEP_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * One reconciliation pass. Public visibility is for the JVM tests —
     * the watchdog thread is the only production caller. For every live
     * leg, decides whether the row's engine state still licenses the
     * connection to exist:
     *
     *   row gone                  → STATE   (orphaned leg)
     *   cancel-authoritative      → CANCEL  (a deliberate stop always wins)
     *   row not ACTIVE            → STATE   (engine already moved on — the
     *                              wedged-socket self-heal, the stall caps,
     *                              or a completed sibling resolved it while
     *                              this write stayed blocked)
     *   paused past the grace     → PAUSE   (park the file, free the permit)
     *   reconnecting + silent past the network grace
     *                            → NETWORK (GOAT-PASS 8 §11 — the peer was
     *                              probed unreachable / the LAN blinked;
     *                              abort the write so the auto-resume loop's
     *                              fresh-socket retry, not TCP's minute-
     *                              scale retransmission backoff, owns the
     *                              recovery)
     *   otherwise                 → keep streaming
     */
    fun sweep() {
        val now = System.currentTimeMillis()
        for (leg in legs.values) {
            val tid = leg.tid
            val t = TransferManager.get(tid)
            val cause: Cause = when {
                t == null -> Cause.STATE
                TransferManager.isCancelAuthoritative(tid) -> Cause.CANCEL
                t.status != TransferManager.Status.ACTIVE -> Cause.STATE
                TransferManager.isPaused(tid) && t.pauseStartedAtMs != 0L &&
                    now - t.pauseStartedAtMs > PAUSE_ABORT_GRACE_MS -> Cause.PAUSE
                // ── GOAT-PASS 8 (§11): silence measured from the row's own ──
                // sample clock (refreshed by every progress update, frozen
                // by a stall). A row can only be latched reconnecting after
                // the stall watchdog's probe or the LAN transition latch —
                // both mean "this leg's connection is not coming back on its
                // own" — and the silence grace here additionally proves no
                // bytes resumed in the meantime (they would have cleared
                // the latch AND moved the clock).
                t.reconnecting && !t.pauseRequested &&
                    now - t.lastSampleAtMs > networkAbortSilenceMs -> Cause.NETWORK
                else -> continue
            }
            abortLeg(leg, cause)
        }
    }

    /** Records the cause and fires the disconnect exactly once per leg. */
    private fun abortLeg(leg: Handle, cause: Cause) {
        if (!leg.aborted.compareAndSet(false, true)) return
        leg.cause = cause
        val act = leg.disconnect
        if (act != null) {
            try { act() } catch (e: Exception) { /* disconnecting a dead connection is fine */ }
        }
        val why = when (cause) {
            Cause.PAUSE -> "paused — parking the file for resume (checkpoint preserved)"
            Cause.CANCEL -> "cancelled"
            Cause.STATE -> "row no longer executing (engine moved on)"
            Cause.NETWORK -> "peer unreachable (row latched reconnecting) — handing recovery to the auto-resume loop's fresh-socket retry"
        }
    }
}
