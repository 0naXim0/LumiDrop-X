package com.lumidrop.x.transfer

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * ══ Pass-33 (#4): provenance for the Received tab ══
 *
 * The Received tab must show only content LumiDrop X actually received —
 * never every file that happens to sit inside the receive directory. The
 * old view reconciled the DIRECTORY (buildReconciledReceived listed
 * everything on disk), so a file or folder dropped in by hand with an
 * external file manager rendered as if the app had received it — and was
 * one tap away from being deleted by the tab's own multi-delete.
 *
 * This registry is the durable answer to "was THIS absolute path received
 * by the app?": one small JSON set of paths in filesDir, written at the
 * exact moment a receive completes (wired beside HistoryStore in
 * LumiDropServer's onTerminalRecord — the same hook that already fires
 * exactly once for every completion path), read by the Received tab's
 * reconcile pass. Membership, not directory location, is what renders a
 * row.
 *
 * Rules:
 *  • Files: a top-level file shows only when its own path is registered.
 *  • Folders: a top-level directory shows only when the DIRECTORY path is
 *    registered (recorded alongside its first completed member), and its
 *    group lists only REGISTERED member files — a foreign file dropped
 *    inside an app-received folder stays invisible to the tab and untouchable
 *    by its delete actions.
 *  • A path deleted through the tab (or anywhere else) is forgotten from
 *    the registry; a missing path simply stops rendering, and can never be
 *    mistaken for app-received content again.
 *  • FIRST LAUNCH (no registry file): the CURRENT contents of the receive
 *    directory are snapshotted once as app-received. Files received by
 *    earlier versions keep rendering exactly as they always did; only
 *    content added AFTER this pass needs a completion record to appear.
 *
 * Thread-safety: recorded from NanoHTTPD worker threads, read from the UI
 * reconcile scan. The in-memory set is synchronized; disk writes are
 * serialized on a single daemon executor (the same discipline as
 * HistoryStore's) so a completion never waits on I/O.
 *
 * Shape on disk (received_registry.json in filesDir):
 *   {"v":1,"paths":{"/abs/path":finishedAtMs,…}}
 */
object ReceivedRegistry {

    private const val FILE_NAME = "received_registry.json"
    private val lock = Any()
    private val paths = HashMap<String, Long>()
    @Volatile private var loaded = false
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "recv-registry").apply { isDaemon = true } }

    /** Loads the registry once; the very first load with no file present
     *  performs the one-time grandfather snapshot of the receive directory
     *  (see the class doc) and persists it — so the migration itself can
     *  never run twice. Safe to call from any thread. */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(lock) {
            if (loaded) return
            val ctx = context.applicationContext
            val f = File(ctx.filesDir, FILE_NAME)
            var fileExisted = false
            var parseOk = false
            if (f.exists()) {
                fileExisted = true
                try {
                    val obj = JSONObject(f.readText(Charsets.UTF_8))
                    val arr = obj.optJSONObject("paths") ?: JSONObject()
                    for (key in arr.keys()) paths[key] = arr.optLong(key, 0L)
                    parseOk = true
                } catch (e: Exception) {
                    paths.clear()
                }
            }
            // Snapshot ONLY on a first launch (no file) or a corrupt file.
            // An existing, validly-parsed EMPTY registry is a real state —
            // everything received was deleted — and must not re-adopt
            // whatever sits on disk.
            if (!fileExisted || !parseOk) {
                try {
                    val root = File(com.lumidrop.x.settings.SettingsManager.getUploadPath(ctx))
                    if (root.isDirectory) {
                        val now = System.currentTimeMillis()
                        root.listFiles()?.forEach { top ->
                            if (top.isFile) {
                                paths[top.absolutePath] = maxOf(top.lastModified(), 0L).let { if (it > 0) it else now }
                            } else if (top.isDirectory) {
                                paths[top.absolutePath] = now
                                top.walkTopDown().filter { it.isFile }.forEach {
                                    paths[it.absolutePath] = maxOf(it.lastModified(), 0L).let { m -> if (m > 0) m else now }
                                }
                            }
                        }
                    }
                } catch (e: Exception) { /* snapshot is best-effort; an empty registry just hides unproven rows */ }
                persistLocked(ctx)
            }
            loaded = true
        }
    }

    /** Records one completed receive: the file's own path plus its
     *  top-level ancestor directory inside the receive root (so the folder
     *  row renders even before its second member lands). Best-effort by
     *  design — a recording failure must never break completion.
     *  ── FINAL PASS 5 (§4): [atMs] lets a caller preserve an EXISTING
     *  receive timestamp instead of stamping "now" — the Received rename
     *  uses it so a renamed file keeps its original "[Time] ago" and list
     *  position (the reconcile sorts by this stamp; re-stamping "now" was
     *  what made a renamed item jump to the top and read "0s"). */
    fun record(context: Context, absolutePath: String, atMs: Long = 0L) {
        if (absolutePath.isBlank()) return
        val ctx = context.applicationContext
        ensureLoaded(ctx)
        val now = if (atMs > 0L) atMs else System.currentTimeMillis()
        synchronized(lock) {
            paths[absolutePath] = now
            try {
                val root = File(com.lumidrop.x.settings.SettingsManager.getUploadPath(ctx)).absolutePath
                val rel = absolutePath.removePrefix("$root/")
                val firstSeg = rel.substringBefore('/', "")
                if (firstSeg.isNotEmpty() && rel.contains('/')) {
                    // A nested member's completion may carry a preserved
                    // stamp (rename) — the DIRECTORY's own stamp only moves
                    // FORWARD, never backward (the folder's receive time is
                    // its first member's landing; a rename must not reset it).
                    val dirKey = File(root, firstSeg).absolutePath
                    val prev = paths[dirKey]
                    if (prev == null || prev < now) paths[dirKey] = now
                }
            } catch (e: Exception) { /* dir bookkeeping is cosmetic */ }
        }
        persistAsync(ctx)
    }

    /** ── FINAL PASS 3 (§10): drops one path's provenance record — used by ──
     *  the Received card's Rename, which records the NEW path and forgets
     *  the old one so the next reconcile never shows both. */
    fun forgetPath(context: Context, absolutePath: String) {
        if (absolutePath.isBlank()) return
        val ctx = context.applicationContext
        ensureLoaded(ctx)
        synchronized(lock) { paths.remove(absolutePath) }
        persistAsync(ctx)
    }

    /** The provenance verdict for the Received tab's reconcile pass. */
    fun isAppReceived(context: Context, absolutePath: String): Boolean {
        ensureLoaded(context.applicationContext)
        synchronized(lock) { return paths.containsKey(absolutePath) }
    }

    /** ── GOAT-PASS 10 (§5): the recorded RECEIVE-completion time for ──────
     *  [absolutePath] (0 when unknown) — the timestamp the Received tab's
     *  "[Time] ago" labels must show. Recorded at the exact moment the file
     *  (or, for a directory key, its first member) finished landing, it is
     *  deliberately independent of the file's own filesystem mtime — a
     *  sender's original mtime must never masquerade as the receive time. */
    fun receivedAt(context: Context, absolutePath: String): Long {
        ensureLoaded(context.applicationContext)
        synchronized(lock) { return paths[absolutePath] ?: 0L }
    }

    /** Forgets one path (and everything under it) after a deletion — the
     *  registry must not keep claiming provenance for content that no
     *  longer exists. */
    fun forgetTree(context: Context, absolutePath: String) {
        val ctx = context.applicationContext
        ensureLoaded(ctx)
        synchronized(lock) {
            paths.remove(absolutePath)
            val prefix = absolutePath.trimEnd('/') + "/"
            paths.keys.removeAll { it.startsWith(prefix) }
        }
        persistAsync(ctx)
    }

    private fun persistAsync(ctx: Context) {
        try {
            io.execute { synchronized(lock) { persistLocked(ctx) } }
        } catch (e: Exception) { /* shutting down */ }
    }

    private fun persistLocked(ctx: Context) {
        try {
            val obj = JSONObject()
            val body = JSONObject()
            for ((k, v) in paths) body.put(k, v)
            obj.put("v", 1); obj.put("paths", body)
            val tmp = File(ctx.filesDir, "$FILE_NAME.tmp")
            tmp.writeText(obj.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(File(ctx.filesDir, FILE_NAME))) {
                File(ctx.filesDir, FILE_NAME).writeText(obj.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
        }
    }
}
