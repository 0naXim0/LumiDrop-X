package com.lumidrop.x.transfer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pass-14 (resumable receives, requirement 2 — persistence across receiver
 * app restarts).
 *
 * A native-push receive that was interrupted with real partial data on disk
 * is a RESUMABLE transfer: its "Waiting for reconnection" card must stay in
 * the Active tab for as long as its staging partial exists — including
 * across the receiver closing or restarting the app. The engine's rows are
 * in-memory only, so this little store is the durable half:
 *
 *  • an interrupted-with-partial receive writes an entry here (LumiDropServer's
 *    apiNativePushFile interruption path);
 *  • a completed resume, a deliberate cancel, or a confirmed discard removes
 *    the entry (and the staging partial itself);
 *  • at every server cold start, LumiDropServer restores one
 *    "Waiting for reconnection" card per surviving entry (verifying the
 *    staging file still exists), and additionally ADOPTS orphaned staging
 *    partials from before this registry existed.
 *
 * The store is deliberately tiny and synchronous: a handful of entries, a
 * JSON file in the app's private storage, guarded by one object monitor —
 * the same reliability-over-throughput trade the engine's own tombstones
 * make. Staging-file naming mirrors LumiDropServer's private stagingFileFor
 * (".<name>.wsxpart" next to the destination, plus the ".meta" identity
 * sidecar); it is duplicated here rather than shared because the server
 * class is not constructible from every context that needs to delete a
 * partial (the Active tab's discard confirmation, for one).
 */
object ResumableReceiveStore {

    class Entry(
        val destPath: String,        // absolute path of the FINAL destination file
        val fileName: String,        // display name
        val senderName: String,      // who was sending it when it was interrupted
        val senderIp: String,
        val totalBytes: Long,        // declared size, -1 when unknown
        val savedAtMs: Long,
    )

    private const val FILE_NAME = "resumable_receives.json"
    private val lock = Any()

    // ── Staging partial naming (mirrors LumiDropServer.stagingFileFor) ──────
    fun stagingFileFor(destPath: String): File {
        val dest = File(destPath)
        return File(dest.parentFile, ".${dest.name}.wsxpart")
    }
    fun stagingMetaFor(destPath: String): File {
        val dest = File(destPath)
        return File(dest.parentFile, ".${dest.name}.wsxpart.meta")
    }

    /** Best-effort delete of a partial + its identity sidecar. Never throws. */
    fun deleteStaging(destPath: String) {
        try { stagingFileFor(destPath).delete() } catch (ignored: Exception) {}
        try { stagingMetaFor(destPath).delete() } catch (ignored: Exception) {}
    }

    /** Reads the declared size out of a staging sidecar, -1 when absent or
     *  unparseable — used when adopting legacy orphans with no registry
     *  entry to carry their metadata. */
    fun metaDeclaredSize(destPath: String): Long {
        return try {
            val meta = stagingMetaFor(destPath)
            if (!meta.exists()) return -1L
            val parts = meta.readText().split(':')
            parts.getOrNull(0)?.toLongOrNull() ?: -1L
        } catch (e: Exception) { -1L }
    }

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    fun all(ctx: Context): List<Entry> {
        synchronized(lock) {
            val f = file(ctx)
            if (!f.exists()) return emptyList()
            return try {
                val arr = JSONArray(f.readText())
                val out = ArrayList<Entry>(arr.length())
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    out.add(Entry(
                        destPath = o.optString("p"),
                        fileName = o.optString("n"),
                        senderName = o.optString("s"),
                        senderIp = o.optString("i"),
                        totalBytes = o.optLong("t", -1L),
                        savedAtMs = o.optLong("a", 0L),
                    ))
                }
                out
            } catch (e: Exception) { emptyList() }
        }
    }

    fun upsert(ctx: Context, entry: Entry) {
        synchronized(lock) {
            val list = allUnlocked(ctx).filter { it.destPath != entry.destPath }.toMutableList()
            list.add(entry)
            saveUnlocked(ctx, list)
        }
    }

    fun remove(ctx: Context, destPath: String) {
        synchronized(lock) {
            val list = allUnlocked(ctx).filter { it.destPath != destPath }
            saveUnlocked(ctx, list)
        }
    }

    /** ── GOAT-PASS 11 (§7 — whole-folder cancellation must not thrash the ──
     *  disk): the bulk twin of [remove]. A cancelled folder's teardown used
     *  to call remove() once per manifest entry — 694 calls, EACH a full
     *  read-modify-write of the store's JSON file (O(n²) parsing plus 694
     *  file rewrites), all from whichever thread the cancel ran on (the UI
     *  thread, for a Transfers-tab Cancel). On a large folder that is a
     *  multi-second main-thread stall — the ANR users reported as
     *  "cancellation crashes the app". One bulk read-modify-write here does
     *  the identical job in O(n). */
    fun removeAll(ctx: Context, destPaths: Collection<String>) {
        if (destPaths.isEmpty()) return
        synchronized(lock) {
            val drop = destPaths.toHashSet()
            val list = allUnlocked(ctx).filter { it.destPath !in drop }
            saveUnlocked(ctx, list)
        }
    }

    private fun allUnlocked(ctx: Context): List<Entry> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<Entry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(Entry(
                    destPath = o.optString("p"),
                    fileName = o.optString("n"),
                    senderName = o.optString("s"),
                    senderIp = o.optString("i"),
                    totalBytes = o.optLong("t", -1L),
                    savedAtMs = o.optLong("a", 0L),
                ))
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    private fun saveUnlocked(ctx: Context, list: List<Entry>) {
        try {
            val arr = JSONArray()
            for (e in list) {
                arr.put(JSONObject()
                    .put("p", e.destPath)
                    .put("n", e.fileName)
                    .put("s", e.senderName)
                    .put("i", e.senderIp)
                    .put("t", e.totalBytes)
                    .put("a", e.savedAtMs))
            }
            file(ctx).writeText(arr.toString())
        } catch (e: Exception) { /* best-effort: worst case the row is not persisted */ }
    }
}
