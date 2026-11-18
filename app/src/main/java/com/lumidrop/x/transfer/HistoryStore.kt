package com.lumidrop.x.transfer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * ══ Pass-17: the durable, INSTANT, deletion-proof History log ══
 *
 * The History tab used to derive its rows from the Received tab's DISK
 * reconciliation (buildReconciledReceived — a walkTopDown() of the whole
 * receive folder plus per-file sidecar reads) merged with the live engine
 * snapshot. Two reported defects come from that single design choice:
 *
 *  1. SLOW — the disk walk runs on an IO dispatcher and the tab was
 *     throttled to one scan per 2 s, so opening History could show nothing
 *     (or stale rows) for seconds.
 *  2. COUPLED — deleting the file from the Received tab deleted the very
 *     record History rendered, because the record WAS the file.
 *
 * History is a LOG, not a view of the disk. This store appends one small
 * JSON entry at the exact moment a transfer completes (wired through
 * TransferManager.onTerminalRecord, which fires in finalizeCompletedBody
 * and recordTextTransfer — so EVERY completion path records exactly once,
 * regardless of which tab is open or whether any UI exists at all), keeps
 * the full list in memory for instant renders, and persists it to a
 * compact sidecar file so it survives restarts. Received-tab deletions,
 * file deletions, and even the file leaving the device entirely can never
 * erase history again.
 *
 * Coverage of files received BEFORE this pass (no store entries exist):
 * a ONE-TIME migration, kicked at server start, walks the receive folder
 * in the background and mints entries from ReceivedFileMeta + the file's
 * own mtime/size — after it completes, History never needs the disk
 * again. The migration is per-process (a volatile flag) and idempotent
 * across restarts (existing paths are skipped), so the cost is paid once
 * per app session, entirely off the request path.
 *
 * Shape on disk (transfer_history.json in filesDir):
 *   {"v":2,"entries":[{i,t,d,n,p,g,k,b,f,x,tx}, …],"tomb":[id, …]} — bounded to
 *   [MAX_ENTRIES] newest, appended oldest-first-trimmed. Written on a
 *   single background thread (a daemon executor serializes record()
 *   calls) so a completion never waits on disk I/O.
 *
 * ══ GOAT-PASS 12 (§3D) — the durable tombstone set ══
 *
 * Deletion used to be a one-layer operation with a resurrection hole:
 * the History tab renders the store MERGED with the engine's live rows
 * (see TransfersFragment.renderHistoryList), and the same completed
 * transfer exists in BOTH layers — the store entry (id "abc") and the
 * engine row (the same "abc", still COMPLETED in TransferManager's map
 * until the TTL prune or a dismiss takes it). Removing ONLY the store
 * entry made the very next render re-add the row through the merge's
 * live-rows branch ("not in storeById anymore"); dismissing ONLY the
 * engine row made the store entry re-render it as a "hist:" row. Which
 * one a given deletion hit depended on which layer happened to still
 * hold the transfer — exactly the reported "some selected items vanish,
 * others stay".
 *
 * The tombstone set is the durable, surviving half of the fix: every
 * deletion (single, bulk, Clear) records WHAT was deleted, in the SAME
 * file as the entries, and every path that could re-introduce a record
 * consults it —
 *   • record() never re-adds a tombstoned transfer id (a raced/late
 *     completion hook can never resurrect what the user deleted);
 *   • record() never re-adds an entry whose GROUP was tombstoned
 *     ("grp:<gid>" — a folder's straggler file completing after the
 *     folder's record was deleted must not re-fold the folder);
 *   • the one-time disk migration skips tombstoned "fs:/path" pseudo-ids
 *     (this was the reported "History populates again after reopening
 *     the app after Clear" — clear wiped the entries, restart re-ran the
 *     migration, and every still-existing received file was re-minted);
 *   • TransfersFragment's merge filter drops live engine rows whose id
 *     (or group) is tombstoned, so a reconnect, a restore, or the row
 *     simply still living in the engine map can never re-render it.
 *
 * Tombstones are bounded ([MAX_TOMBSTONES], oldest-first eviction) — they
 * only need to outlive the engine's own in-memory rows and the migration
 * pass, both of which are far shorter-lived than the bound. Clearing
 * tombstones on purpose (a fresh start) happens nowhere automatically.
 */
object HistoryStore {

    data class Entry(
        val id: String,            // the engine transfer id (or fs-migrated pseudo id)
        val finishedAt: Long,
        val direction: String,     // "UPLOAD" (received) | "DOWNLOAD" | "SEND" (sent)
        val fileName: String,
        val peerName: String,
        val peerIp: String,
        val totalBytes: Long,
        val filePath: String?,     // null for text messages
        val isText: Boolean,
        val textContent: String?,  // only for text messages
        val groupId: String?,     // folder grouping (native pushes)
        val groupName: String?,   // the folder's display name (groupId is a bare UUID)
        // ── FINAL PASS 3 (§2): a streamed ZIP-batch archive's marker rides ──
        // the durable record so a RESTARTED app's History tab still knows the
        // row is a streamed archive (selection eligibility above, the no-
        // Pause rule, the "Auto-Continue" chip wording). Absent on every
        // legacy record — optInt default 0 — exactly like every other flag.
        val isZipStream: Boolean = false,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("i", id); put("t", finishedAt); put("d", direction); put("n", fileName)
            put("p", peerName); put("pi", peerIp); put("b", totalBytes)
            if (filePath != null) put("f", filePath)
            if (isText) { put("x", 1); if (!textContent.isNullOrEmpty()) put("tx", textContent) }
            if (groupId != null) put("g", groupId)
            if (!groupName.isNullOrEmpty()) put("gn", groupName)
            if (isZipStream) put("z", 1)
        }

        companion object {
            fun fromJson(o: JSONObject): Entry = Entry(
                id = o.optString("i"),
                finishedAt = o.optLong("t", 0L),
                direction = o.optString("d", "UPLOAD"),
                fileName = o.optString("n", "file"),
                peerName = o.optString("p", ""),
                peerIp = o.optString("pi", ""),
                totalBytes = o.optLong("b", 0L),
                filePath = if (o.has("f")) o.optString("f") else null,
                isText = o.optInt("x", 0) == 1,
                textContent = if (o.has("tx")) o.optString("tx") else null,
                groupId = if (o.has("g")) o.optString("g") else null,
                groupName = if (o.has("gn")) o.optString("gn") else null,
                isZipStream = o.optInt("z", 0) == 1,
            )
        }
    }

    private const val MAX_ENTRIES = 600
    /** GOAT-PASS 12 (§3D): bounded durable deletion memory — comfortably
     *  outlives the engine's in-memory rows and the once-per-process
     *  migration, while never growing without limit. */
    private const val MAX_TOMBSTONES = 4096
    private const val FILE_NAME = "transfer_history.json"

    /** Prefix distinguishing a tombstoned GROUP id from transfer ids
     *  (group ids are UUIDs minted per push — never colliding with a
     *  transfer id, but prefixed anyway so the set stays self-describing). */
    const val GROUP_TOMB_PREFIX = "grp:"

    /** In-memory list, newest-first. Loaded lazily on first access from ANY
     *  thread (synchronized double-check — the first reader pays one small
     *  file read, every later reader a volatile read). */
    @Volatile private var loaded = false
    private val entries = ArrayList<Entry>(64)
    /** GOAT-PASS 12 (§3D): ids (and "grp:"-prefixed group ids) whose records
     *  were deliberately deleted — insertion-ordered for bounded eviction. */
    private val tombstones = LinkedHashSet<String>(64)
    private val lock = Any()

    /** Serialized writer — record() is called from NanoHTTPD worker threads
     *  and must never make a completion wait on disk I/O, and the
     *  load-modify-write persistence below is not safe to interleave. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "wsx-history").apply { isDaemon = true }
    }

    private fun file(ctx: Context): File = File(ctx.filesDir, FILE_NAME)

    @Synchronized
    private fun ensureLoaded(ctx: Context) {
        if (loaded) return
        loaded = true
        try {
            val f = file(ctx)
            if (f.exists()) {
                val root = JSONObject(f.readText())
                val arr = root.optJSONArray("entries") ?: JSONArray()
                entries.clear()
                for (i in 0 until arr.length()) {
                    try { entries.add(Entry.fromJson(arr.optJSONObject(i) ?: continue)) } catch (e: Exception) {}
                }
                entries.sortByDescending { it.finishedAt }
                // GOAT-PASS 12 (§3D): the tombstone set rides in the same
                // file ("tomb" array; absent in pre-GP12 files = empty set).
                val tarr = root.optJSONArray("tomb")
                if (tarr != null) {
                    for (i in 0 until tarr.length()) {
                        try { tombstones.add(tarr.optString(i)) } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: Exception) {
            entries.clear()
        }
    }

    /** Newest-first snapshot of the whole log. Cheap: in-memory after the
     *  first call — this is what makes the History tab INSTANT. */
    fun snapshot(ctx: Context): List<Entry> {
        ensureLoaded(ctx)
        synchronized(lock) { return ArrayList(entries) }
    }

    /** True when [id] already has an entry (dedupe for live-row merging). */
    fun containsId(ctx: Context, id: String): Boolean {
        if (id.isEmpty()) return false
        ensureLoaded(ctx)
        synchronized(lock) { return entries.any { it.id == id } }
    }

    /** GOAT-PASS 12 (§3D): has this transfer id (or its group) been
     *  deliberately deleted? Consulted by record(), the migration and
     * TransfersFragment's live-row merge filter — the one authoritative
     * "this record must not come back" verdict. */
    fun isTombstoned(ctx: Context, id: String): Boolean {
        if (id.isEmpty()) return false
        ensureLoaded(ctx)
        synchronized(lock) { return tombstones.contains(id) }
    }

    /** GOAT-PASS 12 (§3E): has this GROUP been deleted (any member of a
     *  deleted folder — late stragglers must not re-fold it)? */
    fun isGroupTombstoned(ctx: Context, groupId: String?): Boolean {
        if (groupId.isNullOrEmpty()) return false
        return isTombstoned(ctx, GROUP_TOMB_PREFIX + groupId)
    }

    /** Records one COMPLETED transfer — the single writer invoked from
     *  TransferManager.onTerminalRecord. Asynchronous by design: the
     *  in-memory list is updated immediately (the very next History render
     *  sees it), the disk flush rides the writer thread. */
    fun record(context: Context, t: TransferManager.Transfer) {
        if (t.status != TransferManager.Status.COMPLETED) return
        val ctx = context.applicationContext
        ensureLoaded(ctx)
        // ── GOAT-PASS 12 (§3D): a deliberately deleted record stays ──────────
        // deleted. A late/duplicated completion hook (or a member of a
        // folder whose History record was just deleted) must never
        // resurrect what the user removed — the tombstone, not the
        // completion event, is authoritative for deleted records.
        if (isTombstoned(ctx, t.id)) return
        if (isGroupTombstoned(ctx, t.groupId)) return
        val e = Entry(
            id = t.id,
            finishedAt = if (t.finishedAt > 0) t.finishedAt else System.currentTimeMillis(),
            direction = t.direction.name,
            fileName = t.fileName,
            peerName = t.peerName,
            peerIp = t.peerIp,
            totalBytes = t.totalBytes,
            filePath = t.filePath,
            isText = t.isTextMessage,
            textContent = if (t.isTextMessage) t.textContent else null,
            groupId = t.groupId,
            // The folder's display name travels WITH the entry — the group id
            // itself is a bare UUID that means nothing to a future render.
            groupName = t.groupId?.let { TransferManager.groupInfo(it)?.name },
            isZipStream = t.isZipStream,
        )
        synchronized(lock) {
            // Idempotent: a completion hook firing twice for one tid (it
            // can't today, but a future reopen/complete cycle could) never
            // duplicates the log entry.
            if (entries.any { it.id == e.id }) return
            entries.add(0, e)
            while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
            val snap = ArrayList(entries)
            val tombSnap = ArrayList(tombstones)
            writer.execute { persist(ctx, snap, tombSnap) }
        }
    }

    /** Removes one entry (the History tab's per-row X on a store-backed
     *  row — the user's explicit "remove this record" action).
     *  GOAT-PASS 12 (§3D): now also TOMBSTONES the id — see the class doc;
     *  removal without the tombstone was the resurrection hole. */
    fun remove(context: Context, id: String) {
        removeAll(context, listOf(id))
    }

    /** ── GOAT-PASS 12 (§3A/§3D): the bulk, tombstone-writing deletion — ──────
     *  ONE synchronized pass removes every matching entry, every given id
     *  is tombstoned (transfer ids raw; group ids "grp:"-prefixed by the
     *  caller), and ONE persist write lands the whole state. Returns how
     *  many entries were actually removed so callers can report honestly. */
    fun removeAll(context: Context, ids: Collection<String>): Int {
        if (ids.isEmpty()) return 0
        val ctx = context.applicationContext
        ensureLoaded(ctx)
        var removed = 0
        var stateChanged = false
        synchronized(lock) {
            val idSet = HashSet(ids)
            val it2 = entries.iterator()
            while (it2.hasNext()) {
                val e = it2.next()
                if (idSet.contains(e.id)) { it2.remove(); removed++ }
            }
            for (id in idSet) {
                if (id.isNotEmpty()) stateChanged = tombstones.add(id) || stateChanged
            }
            while (tombstones.size > MAX_TOMBSTONES) {
                tombstones.remove(tombstones.iterator().next())
            }
            if (removed > 0 || stateChanged) {
                val snap = ArrayList(entries)
                val tombSnap = ArrayList(tombstones)
                writer.execute { persist(ctx, snap, tombSnap) }
            }
        }
        return removed
    }

    /** ── Pass-22 (#8 — the Clear History action's missing half): wipes the ────
     *  whole durable log, memory AND disk, in one synchronized step. The
     *  History tab renders THIS store (merged with the engine's live rows), so
     *  clearing the engine alone never changed what the tab showed — the store
     *  re-painted the same entries on the next render tick and the button read
     *  as dead. Clearing here is what makes the on-screen action real, and the
     *  immediate re-render rides the caller (TransfersFragment's tap handler).
     *  The migration's one-time flag deliberately stays raised: re-minting
     *  pre-store files after a clear the user asked for would resurrect the
     *  very history they just erased.
     *
     *  GOAT-PASS 12 (§3D): Clear now ALSO tombstones every removed entry id
     *  and every folder group id — without this, (a) the engine's still-live
     *  completed rows re-rendered through the merge the very next tick, and
     *  (b) after a RESTART the once-per-process migration re-minted a fresh
     *  "fs:/path" entry for every still-existing received file (the store
     *  was empty, so "knownPaths" matched nothing) — the reported "History
     *  populates again after reopening the app". The tombstones ride the
     *  same persisted file, so the cleared state survives restarts and no
     *  stale write can overwrite it (the single writer serializes every
     *  persist in submission order). */
    fun clear(context: Context) {
        val ctx = context.applicationContext
        ensureLoaded(ctx)
        synchronized(lock) {
            // GOAT-PASS 12 (§3E): folders' group ids are tombstoned with
            // their members, so a straggler file of a cleared folder can
            // never re-fold the folder into a fresh History row.
            for (e in entries) {
                if (e.id.isNotEmpty()) tombstones.add(e.id)
                if (!e.groupId.isNullOrEmpty()) tombstones.add(GROUP_TOMB_PREFIX + e.groupId)
            }
            while (tombstones.size > MAX_TOMBSTONES) {
                tombstones.remove(tombstones.iterator().next())
            }
            entries.clear()
            val snap = ArrayList(entries)
            val tombSnap = ArrayList(tombstones)
            writer.execute { persist(ctx, snap, tombSnap) }
        }
    }

    private fun persist(ctx: Context, snap: List<Entry>, tombSnap: List<String>) {
        try {
            val arr = JSONArray()
            for (e in snap) arr.put(e.toJson())
            val tomb = JSONArray()
            for (t in tombSnap) tomb.put(t)
            val body = JSONObject().put("v", 2).put("entries", arr).put("tomb", tomb).toString()
            val f = file(ctx)
            val tmp = File(f.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(f)) { tmp.delete(); f.writeText(body) }
        } catch (e: Exception) {
        }
    }

    // ── Pass-17: the ONE-TIME migration of pre-store received files ────────
    // Files received before this pass have no store entries; without this
    // they would vanish from History (which no longer reads the disk). The
    // walk runs in the background at server start, is idempotent (existing
    // paths skipped via the in-memory + on-disk entries), and after it
    // finishes History is fully self-sufficient.
    @Volatile private var migrationStarted = false

    fun kickMigration(context: Context) {
        if (migrationStarted) return
        synchronized(this) {
            if (migrationStarted) return
            migrationStarted = true
        }
        val ctx = context.applicationContext
        Thread({
            try {
                ensureLoaded(ctx)
                val root = File(com.lumidrop.x.settings.SettingsManager.getUploadPath(ctx))
                if (!root.isDirectory) return@Thread
                val knownPaths = HashSet<String>()
                // GOAT-PASS 12 (§3D): the tombstone snapshot is captured under
                // the SAME lock as knownPaths — both feed the migration's
                // "already known / deliberately deleted" verdicts, and the
                // set is mutated by user deletions on other threads.
                val migTombstones: Set<String>
                synchronized(lock) {
                    for (e in entries) e.filePath?.let { knownPaths.add(it) }
                    migTombstones = HashSet(tombstones)
                }
                var migrated = 0
                root.walkTopDown().maxDepth(6).filter { it.isFile &&
                    !it.name.startsWith(".") && !it.name.endsWith(".wsxpart") && !it.name.endsWith(".meta") }
                    .forEach { f ->
                        try {
                            // GOAT-PASS 12 (§3D): the tombstone check IS the
                            // "stays deleted after restart" fix — without it,
                            // a cleared store re-adopted every still-existing
                            // received file on the next app start (empty
                            // knownPaths matched nothing), repopulating the
                            // History the user had just cleared.
                            if (f.absolutePath !in knownPaths &&
                                !migTombstones.contains("fs:" + f.absolutePath)) {
                                val sender = try {
                                    com.lumidrop.x.transfer.ReceivedFileMeta.senderFor(ctx, f.absolutePath) ?: ""
                                } catch (e: Exception) { "" }
                                synchronized(lock) {
                                    entries.add(Entry(
                                        id = "fs:" + f.absolutePath,
                                        finishedAt = f.lastModified(),
                                        direction = "UPLOAD",
                                        fileName = f.name,
                                        peerName = sender,
                                        peerIp = "",
                                        totalBytes = f.length(),
                                        filePath = f.absolutePath,
                                        isText = false,
                                        textContent = null,
                                        groupId = null,
                                        groupName = null,
                                    ))
                                    migrated++
                                }
                            }
                        } catch (e: Exception) { /* one unreadable file never stops the migration */ }
                    }
                if (migrated > 0) {
                    synchronized(lock) {
                        entries.sortByDescending { it.finishedAt }
                        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
                        val snap = ArrayList(entries)
                        val tombSnap = ArrayList(tombstones)
                        writer.execute { persist(ctx, snap, tombSnap) }
                    }
                }
            } catch (e: Exception) {
            }
        }, "wsx-history-mig").apply { isDaemon = true }.start()
    }
}
