package com.lumidrop.x.transfer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Pass-15 (no more "Unknown sender" in Received/History): the Received and
 * History tabs reconcile their rows from what is actually ON DISK — past
 * sessions' completed receives have no live TransferManager row, so every
 * one of them used to render with a synthesized "Unknown sender" label even
 * though the app genuinely knew which device delivered the file at the time.
 *
 * This tiny durable sidecar closes that gap: every completed native-push or
 * web-upload receive records its (absolute destination path → sender display
 * name) here at completion time, and the reconcile synthesizes its rows with
 * the REAL name instead of a guess. Cap-and-evict keeps it bounded (600
 * entries ≈ months of typical receive traffic; oldest evicted first), and a
 * missing entry (evicted, received on an older build, or a file the person
 * dropped into the folder themselves) simply falls back to no sender label
 * — never a wrong one.
 *
 * Same reliability-over-throughput shape as ResumableReceiveStore: a small
 * JSON file in app-private storage, one object monitor, best-effort writes
 * that never throw into a transfer's success path.
 */
object ReceivedFileMeta {

    private const val FILE_NAME = "received_file_senders.json"
    private const val CAP = 600
    private val lock = Any()

    // ── FINAL PASS 8 (PART 4 — the tab-switch freeze's hot half): this ────────
    // store used to re-read and re-parse the JSON file on EVERY senderFor()
    // call — no cache at all. The Received tab's reconcile calls senderFor
    // once per file on disk (a folder-heavy session: hundreds of calls), so
    // one tab entry could cost hundreds of disk reads + full-file JSON
    // parses under the one object monitor — seconds of work, exactly the
    // reported multi-second freeze on entering Received (and any main-
    // thread detail-line render that happened to want a sender name sat
    // behind the same lock). The fix is the standard warm-cache shape both
    // sibling stores (HistoryStore, ReceivedRegistry) already use: load
    // ONCE into memory, serve reads from the map, and keep the cache in
    // lock-step with every write (the one writer updates the same map it
    // persists — there is no path that can desynchronize them).
    @Volatile private var cached: LinkedHashMap<String, String>? = null

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    /** Records who sent the file that now lives at [destPath] (absolute). */
    fun rememberSender(ctx: Context, destPath: String, senderName: String) {
        if (destPath.isEmpty() || senderName.isBlank()) return
        synchronized(lock) {
            val map = readUnlocked(ctx)
            map[destPath] = senderName
            // Evict oldest entries beyond the cap. LinkedHashMap iteration is
            // insertion-ordered and rememberSender re-inserts an existing
            // path to the END (a re-received file is the newest fact), so
            // "first CAP entries" == "oldest CAP facts" — exactly the policy.
            while (map.size > CAP) {
                val it = map.entries.iterator()
                if (!it.hasNext()) break
                it.next()
                it.remove()
            }
            writeUnlocked(ctx, map)
            cached = map   // the write IS the cache refresh — same object
        }
    }

    /** The sender recorded for [destPath], or null when nothing is known.
     *  Served from the in-memory map after the first load (one file read
     *  per process, not one per row). */
    fun senderFor(ctx: Context, destPath: String): String? {
        synchronized(lock) { return readUnlocked(ctx)[destPath] }
    }

    private fun readUnlocked(ctx: Context): LinkedHashMap<String, String> {
        cached?.let { return it }
        val out = LinkedHashMap<String, String>()
        val f = file(ctx)
        if (!f.exists()) { cached = out; return out }
        return try {
            val arr = JSONArray(f.readText())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val p = o.optString("p")
                if (p.isNotEmpty()) out[p] = o.optString("s")
            }
            cached = out
            out
        } catch (e: Exception) { cached = out; out }
    }

    private fun writeUnlocked(ctx: Context, map: Map<String, String>) {
        try {
            val arr = JSONArray()
            for ((p, s) in map) arr.put(JSONObject().put("p", p).put("s", s))
            file(ctx).writeText(arr.toString())
        } catch (e: Exception) { /* best-effort — worst case the label is absent */ }
    }
}
