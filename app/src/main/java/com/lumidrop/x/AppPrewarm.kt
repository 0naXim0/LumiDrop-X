package com.lumidrop.x

import android.content.Context
import com.lumidrop.x.media.MediaLibrary
import com.lumidrop.x.media.MediaTypeFilter
import com.lumidrop.x.settings.SettingsManager
import com.lumidrop.x.ui.ExternalVolumes
import com.lumidrop.x.ui.prewarmFilesSheetCache
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * ══ GOAT-PASS 3 (§1) — the start-up sheet prewarm ═══════════════════════════
 *
 * THE PROBLEM THIS FIXES
 * ----------------------
 * "Launching the app and opening the Files Sheet for the first time has
 * noticeable lag; opening it again afterward is much smoother."
 *
 * The first opening of a session pays a stack of one-time costs that later
 * openings do not: the first SharedPreferences load (XML read + parse on the
 * calling thread), the first StorageManager binder round-trips, the first
 * MediaStore queries (the media provider process spins up, its database
 * opens, its cursor cache is cold), and the first directory walk of external
 * storage (every stat()/listFiles() misses the kernel's dentry cache). All
 * of that previously ran AT sheet-open time — on or blocking the open
 * animation — which is exactly why the first open lagged and later ones
 * (kernel + provider + prefs all warm) did not.
 *
 * THE FIX, AT THE ROOT
 * --------------------
 * One bounded, one-shot background pass, started shortly after the app's
 * onCreate and deliberately DELAYED past the startup window so it never
 * competes with the launch itself:
 *   • touches the app's SharedPreferences (the file loads once, in the
 *     background — every later read is memory-speed);
 *   • performs one StorageManager volume query (warms the binder service);
 *   • lists the storage root INTO the Files sheet's process-level cache
 *     (see FilesSheetCache — the first Files sheet opens onto resident
 *     data, complete with per-folder child counts);
 *   • runs one images+videos MediaStore query and one audio query (the
 *     provider, its database and its page cache are warm by the time the
 *     Media/Music sheets first ask).
 *
 * Nothing here loops, retries, or runs more than once per process
 * ([started] latch). Every step is best-effort with its own guard: the
 * sheets' own load paths remain the sole correctness surfaces and behave
 * exactly as before with or without a warmed cache — this only removes the
 * cold-start cost from the user's first interaction, it does not change any
 * data path. The pass is bounded by construction (single query per source)
 * and adds no background work beyond that one pass.
 */
object AppPrewarm {
    @Volatile private var started = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Idempotent per process. [delayMs] keeps the pass clear of the app's
     *  own startup burst (view inflation, server bind, first frame). */
    fun start(context: Context, delayMs: Long = 1200L) {
        if (started) return
        started = true
        val app = context.applicationContext
        scope.launch {
            try { delay(delayMs) } catch (e: Exception) { return@launch }
            // SharedPreferences: the first read loads and parses the whole
            // file — do it here, once, off the UI thread.
            try { SettingsManager.getUploadPath(app) } catch (e: Exception) { /* best-effort */ }
            try { SettingsManager.getPinnedPaths(app) } catch (e: Exception) { /* best-effort */ }
            // StorageManager binder warm-up (the sheet's volume switcher and
            // every storage-availability check go through it).
            try { ExternalVolumes.mounted(app) } catch (e: Exception) { /* best-effort */ }
            // The Files sheet's root listing, into the process-level cache —
            // the single biggest first-open win (cold dentry walks are the
            // slowest of the stack on f2fs/ext4 with many top-level dirs).
            try { prewarmFilesSheetCache() } catch (e: Exception) { /* best-effort */ }
            // ── GOAT-PASS 4 (§2 — first-query speed): the whole-storage search ──
            // index is built HERE, at start-up, in the background — the
            // Files sheet's search is whole-storage again (one unified
            // experience), and its FIRST query lands on a resident index
            // instead of paying the inline "Indexing…" build. The engine's
            // own one-walk / 25k-entry / 60k-queue discipline bounds this.
            try {
                val root = try {
                    @Suppress("DEPRECATION")
                    android.os.Environment.getExternalStorageDirectory()
                } catch (e: Exception) { null }
                if (root != null) com.lumidrop.x.ui.RecursiveFileSearchAccess.prewarm(root)
            } catch (e: Exception) { /* best-effort */ }
            // MediaStore: images+videos and audio, one query each. The
            // provider process spin-up and database open are the dominant
            // first-query costs; a warm provider serves the sheets' own
            // fresh queries fast.
            try { MediaLibrary.query(app, MediaTypeFilter.ALL) } catch (e: Exception) { /* best-effort */ }
            try { MediaLibrary.queryAudio(app) } catch (e: Exception) { /* best-effort */ }
        }
    }
}
