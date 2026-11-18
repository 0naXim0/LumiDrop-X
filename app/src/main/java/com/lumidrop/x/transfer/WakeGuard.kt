package com.lumidrop.x.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.os.PowerManager

/**
 * ══ GOAT-PASS 10 (background continuity) — the transfer CPU guard ═══════════
 *
 * The user's requirement: transfers must NEVER stop when the app is
 * minimized to the recent-apps drawer.
 *
 * What already keeps them alive (untouched — this is the "don't break the
 * existing optimization" half): the embedded NanoHTTPD server runs inside
 * ServerForegroundService's FOREGROUND process with its own thread pool,
 * and NativePushClient streams on its own Dispatchers.IO scope — neither
 * touches any Activity lifecycle, which is exactly why transfers already
 * survived backgrounding in principle.
 *
 * The gap: on CPU-aggressive OEM builds (and Doze on battery), an app in
 * the recent-apps drawer has its processes frozen/throttled despite the
 * foreground service — mid-transfer sockets stall for seconds to minutes.
 * A PARTIAL WakeLock held ONLY while transfers are actually in flight
 * (never permanently, never on idle) is the sanctioned fix: it keeps the
 * CPU running for exactly as long as bytes are moving, and is released
 * the moment the last transfer goes idle — battery cost is bounded by
 * transfer activity itself, the same cost the transfer already implies.
 *
 * Ref-counted: every active/pending transfer [acquire]s once; [release]
 * when it reaches a terminal state. The lock exists only while the count
 * is positive. Safe to call redundantly — a Terminal callback that fires
 * twice for one tid cannot underflow (floored at zero).
 */
object WakeGuard {

    @Volatile private var wakeLock: PowerManager.WakeLock? = null
    private val held = java.util.Collections.synchronizedSet(LinkedHashSet<String>())
    private const val TAG = "LumiDropX:transfers"

    /** [TransferManager] calls this for every tid entering PENDING/ACTIVE. */
    @SuppressLint("WakelockTimeout")
    fun acquire(context: Context, tid: String) {
        val ctx = context.applicationContext
        synchronized(held) {
            if (!held.add(tid)) return   // already counted — no double lock per tid
            if (wakeLock == null) {
                try {
                    val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
                    wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply {
                        setReferenceCounted(false)   // ONE logical lock, manual lifecycle
                        acquire()
                    }
                } catch (e: Exception) { /* a denied/broken wakelock must never break a transfer */ }
            }
        }
    }

    /** [TransferManager] calls this on every terminal state (completed,
     *  failed, cancelled, declined). Idempotent per tid. */
    fun release(tid: String) {
        synchronized(held) {
            held.remove(tid)
            if (held.isEmpty()) {
                try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { /* never fatal */ }
                wakeLock = null
            }
        }
    }

    /** True when the guard is currently holding a lock (diagnostics only). */
    fun isActive(): Boolean = synchronized(held) { held.isNotEmpty() }

    /** Safety valve: everything terminal → drop the set (the service's own
     *  onDestroy path — a leaked tid must never pin the CPU forever). */
    fun releaseAll() {
        synchronized(held) {
            held.clear()
            try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { /* never fatal */ }
            wakeLock = null
        }
    }
}
