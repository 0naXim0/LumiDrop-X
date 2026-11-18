package com.lumidrop.x.ui

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ══ Pass-44 (§7): the Files sheet's local file-operation engine ══
 *
 * Copy / Move (cut) / Delete over the sheet's selection model, with:
 *  • COLLISION SAFETY — a destination name that already exists gets
 *    "name (1).ext", "name (2).ext"…; an existing file is NEVER overwritten.
 *  • CONTAINMENT SAFETY — a pick is never pasted into itself or into a
 *    descendant of itself (which would recurse forever); destinations are
 *    ordinary directories picked by the user.
 *  • COOPERATIVE CANCELLATION — [cancel] is checked before every file and
 *    inside every copy chunk. A cancelled copy DELETES the partially
 *    written destination file, so no half files are ever left behind; the
 *    sources are always intact (copy) or a clean prefix of completed moves
 *    (move fallback — a file is deleted from the source only after its
 *    copy fully landed and was verified).
 *  • HONEST PROGRESS — a bounded pre-walk counts files+bytes first (the
 *    plan phase, reported as indeterminate), then byte-accurate progress
 *    while streaming; a same-volume rename is instant and reports by items.
 *
 * The engine is deliberately Android-free (java.io + coroutines only) so it
 * runs on any dispatcher the caller chooses; callers drive it from the UI
 * and observe [onProgress] on the main thread.
 */
object FileOps {

    /** One clipboard/operation pick: a root file/folder plus the SAME
     *  exclusion set the sheet's send flow uses (a folder pick with
     *  deselected descendants copies only the still-selected subtree —
     *  "Copy the selected files and folders" means exactly what the user
     *  selected, nothing more). */
    class Pick(val root: File, val excluded: Set<String>)

    /** What happened: how many top-level picks fully succeeded, how many
     *  failed (with the first reason), and whether the run was cancelled.
     *  Nothing is ever reported as success that did not succeed. */
    class Result(val ok: Int, val failed: Int, val cancelled: Boolean, val firstError: String?) {
        val allOk: Boolean get() = !cancelled && failed == 0 && ok > 0
    }

    class CancelledException : Exception("cancelled")

    /** Progress snapshot; totalBytes/totalItems are 0 during the plan phase
     *  (the caller shows an indeterminate bar until the pre-walk lands). */
    class Progress(val phase: String, val doneBytes: Long, val totalBytes: Long,
                   val doneItems: Int, val totalItems: Int) {
        val percent: Int get() =
            if (totalBytes > 0) ((doneBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
    }

    @Volatile private var cancelled = false
    /** True while an engine run is in flight — callers use this to refuse
     *  duplicate operations and stale progress rows. */
    @Volatile var running: Boolean = false
        private set

    fun cancel() { cancelled = true }

    // ── Planning ──────────────────────────────────────────────────────────────

    /** One physical file to handle, with the pick-root-relative path that
     *  decides its destination layout (a folder's internal hierarchy is
     *  preserved, never flattened). */
    private class Planned(val src: File, val relPath: String)

    /** ── FINAL PASS 2 (§4 — empty folders are real filesystem items): the ──────
     *  plan now carries DIRECTORIES too, not just files. The old file-only
     *  plan meant a destination directory was only ever created as the
     *  parent of a landing FILE — an empty folder (or a folder whose every
     *  child is itself empty / excluded / hidden) pasted as NOTHING while
     *  the result still counted it "Copied 1 item", and a cross-volume CUT
     *  of an empty folder even DELETED the source and created no destination
     *  — the reported defects. [dirs] holds every surviving directory of
     *  every pick, root-relative exactly like the files; the operations
     *  create each of them under the pick's landed name before any file
     *  streams, so an empty folder lands as the empty folder it is. */
    private class Plan(val files: List<Planned>, val dirs: List<Planned>)

    /**
     * Walks the picks (bounded by the same caps as the sheet's other
     * walkers) into a flat file list plus the directory list. Exclusions
     * prune whole subtrees; hidden entries follow [includeHidden] — the
     * copy sees exactly the entries the selection the user made would send.
     */
    private fun plan(picks: List<Pick>, includeHidden: Boolean): Plan {
        val files = ArrayList<Planned>(256)
        val dirs = ArrayList<Planned>(64)
        for (pick in picks) {
            val root = pick.root
            if (!root.exists()) continue                     // vanished between arm and paste
            if (root.isFile) { files.add(Planned(root, root.name)); continue }
            val queue = ArrayDeque<File>()
            queue.add(root)
            var queued = 1
            while (queue.isNotEmpty() && files.size < 25_000 && queued < 60_000) {
                val dir = queue.removeFirst()
                val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
                for (c in kids) {
                    if (files.size >= 25_000 || queued >= 60_000) break
                    if (!includeHidden && c.name.startsWith(".")) continue
                    val cp = try { c.absolutePath } catch (e: Exception) { continue }
                    if (pick.excluded.any { cp == it || cp.startsWith("$it/") }) continue
                    val isDir = try { c.isDirectory } catch (e: Exception) { false }
                    val rel = cp.removePrefix(root.absolutePath + "/")
                    if (isDir) {
                        queue.add(c); queued++
                        dirs.add(Planned(c, rel))
                    }
                    else files.add(Planned(c, rel))
                }
            }
        }
        return Plan(files, dirs)
    }

    // ── Collision-safe destination naming ────────────────────────────────────

    /** The first free name for [name] inside [dir]: "name" when nothing is
     *  there, else "name (1)", "name (2)"… with the extension preserved.
     *  Never overwrites — the sheet's own receive path uses the same rule. */
    fun uniqueName(dir: File, name: String): String {
        if (!File(dir, name).exists()) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (!File(dir, candidate).exists()) return candidate
            n++
            if (n > 9999) return "$base (${System.currentTimeMillis()})$ext"   // pathological — unique by construction
        }
    }

    // ── Safety ────────────────────────────────────────────────────────────────

    /** True when pasting [pick] into [destDir] would put the destination
     *  inside the source itself (directly or nested) — always refused. */
    private fun contains(destDir: File, pick: File): Boolean {
        val d = try { destDir.canonicalPath } catch (e: Exception) { destDir.absolutePath }
        val s = try { pick.canonicalPath } catch (e: Exception) { pick.absolutePath }
        return d == s || d.startsWith("$s/")
    }

    // ── The streaming core ─────────────────────────────────────────────────────

    /** Copies one file in 64 KB chunks, reporting cumulative bytes through
     *  [tick] and honouring cancellation mid-file (the partial destination
     *  is deleted by the caller's per-file error path — see copyBatch). */
    private fun streamCopy(src: File, dst: File, tick: (copiedNow: Long) -> Unit) {
        FileInputStream(src).use { fi ->
            FileOutputStream(dst).use { fo ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    if (cancelled) throw CancelledException()
                    val n = fi.read(buf)
                    if (n < 0) break
                    fo.write(buf, 0, n)
                    tick(n.toLong())
                }
                fo.flush()
                try { fo.fd.sync() } catch (e: Exception) { /* best-effort durability — not fatal on vfat/sdcardfs */ }
            }
        }
    }

    // ── Operations ─────────────────────────────────────────────────────────────

    /**
     * Copies every planned file of every pick into [destDir], preserving
     * each pick's internal hierarchy (a folder arrives as a folder).
     * Report cadence: plan phase first, then every chunk. On cancellation
     * the in-flight destination file is deleted and the run returns
     * [Result.cancelled] with the completed count.
     */
    suspend fun copyBatch(picks: List<Pick>, destDir: File, includeHidden: Boolean,
                          onProgress: (Progress) -> Unit): Result = withContext(Dispatchers.IO) {
        cancelled = false; running = true
        try {
            // Containment: never paste a pick into itself or beneath itself.
            for (p in picks) if (contains(destDir, p.root))
                return@withContext Result(0, picks.size, false,
                    "'${p.root.name}' cannot be copied into itself")
            onProgress(Progress("Preparing…", 0, 0, 0, 0))
            val plan = plan(picks, includeHidden)
            val planned = plan.files
            val totalBytes = planned.sumOf { runCatching { it.src.length() }.getOrDefault(0L) }
            val perRootName = HashMap<File, String>()     // pick root → landed top-level name
            var doneBytes = 0L; var doneItems = 0
            var ok = 0; var failed = 0; var firstError: String? = null
            for (p in picks) {
                if (cancelled) return@withContext Result(ok, failed, true, firstError)
                try {
                    if (!destDir.isDirectory && !destDir.mkdirs())
                        throw java.io.IOException("destination unavailable")
                    // The pick's own top-level name lands collision-safely.
                    val landedName = uniqueName(destDir, p.root.name)
                    perRootName[p.root] = landedName
                    if (p.root.isFile) {
                        val dst = File(destDir, landedName)
                        try {
                            streamCopy(p.root, dst) { n ->
                                doneBytes += n
                                onProgress(Progress("Copying", doneBytes, totalBytes, doneItems, planned.size))
                            }
                            doneItems++; ok++
                        } catch (ce: CancelledException) {
                            dst.delete()                  // no partial files, ever
                            throw ce
                        }
                    } else {
                        val dstRoot = File(destDir, landedName)
                        val rootPrefix = p.root.absolutePath + "/"
                        // ── FINAL PASS 2 (§4): the pick's root directory and every ──
                        // planned SUBDIRECTORY are created up-front — an empty
                        // folder (or a folder holding only empty/excluded/hidden
                        // children) lands as the real empty directory it is, and
                        // a mixed folder's empty interior dirs survive alongside
                        // its files. mkdirs is idempotent; the per-file parents
                        // below keep their own calls as the belt-and-braces.
                        try { dstRoot.mkdirs() } catch (e: Exception) { /* surfaced per-file below */ }
                        for (d in plan.dirs) {
                            if (!d.src.absolutePath.startsWith(rootPrefix)) continue
                            try { File(dstRoot, d.relPath).mkdirs() } catch (e: Exception) { /* surfaced per-file below */ }
                        }
                        val files = planned.filter { it.src.absolutePath.startsWith(rootPrefix) }
                        for (f in files) {
                            if (cancelled) return@withContext Result(ok, failed, true, firstError)
                            // relPath is already relative to the pick's ROOT —
                            // the folder's own hierarchy lands verbatim.
                            val dst = File(dstRoot, f.relPath)
                            dst.parentFile?.mkdirs()
                            try {
                                streamCopy(f.src, dst) { n ->
                                    doneBytes += n
                                    onProgress(Progress("Copying", doneBytes, totalBytes, doneItems, planned.size))
                                }
                                doneItems++
                            } catch (ce: CancelledException) {
                                dst.delete()
                                throw ce
                            } catch (e: Exception) {
                                failed++
                                if (firstError == null) firstError = "'${f.src.name}': ${e.message ?: "copy failed"}"
                            }
                        }
                        if (files.isNotEmpty() || p.root.isDirectory) ok++
                    }
                } catch (ce: CancelledException) {
                    return@withContext Result(ok, failed, true, firstError)
                } catch (e: Exception) {
                    failed++
                    if (firstError == null) firstError = "'${p.root.name}': ${e.message ?: "copy failed"}"
                }
            }
            Result(ok, failed, false, firstError)
        } finally {
            running = false
        }
    }

    /**
     * Moves the picks into [destDir]. A same-volume move is one atomic
     * [File.renameTo] per pick (instant, no byte copy); the cross-volume
     * fallback (and any renameTo the filesystem refuses) copies tree-by-
     * tree and deletes each source FILE only after its copy fully landed.
     * Cancellation therefore leaves either fully-moved files or untouched
     * ones — plus at most the partial destination file, which is deleted.
     */
    suspend fun moveBatch(picks: List<Pick>, destDir: File, includeHidden: Boolean,
                          onProgress: (Progress) -> Unit): Result = withContext(Dispatchers.IO) {
        cancelled = false; running = true
        try {
            for (p in picks) if (contains(destDir, p.root))
                return@withContext Result(0, picks.size, false,
                    "'${p.root.name}' cannot be moved into itself")
            var ok = 0; var failed = 0; var firstError: String? = null
            var doneItems = 0
            val totalItems = picks.size
            // Phase 1: try the atomic rename for every pick (same-volume).
            val remaining = ArrayList<Pick>()
            for (p in picks) {
                if (cancelled) return@withContext Result(ok, failed, true, firstError)
                val target = File(destDir, uniqueName(destDir, p.root.name))
                val renamed = try { !target.exists() && p.root.renameTo(target) } catch (e: Exception) { false }
                if (renamed) { ok++; doneItems++
                    onProgress(Progress("Moving", 0, 0, doneItems, totalItems)) }
                else remaining.add(p)
            }
            // Phase 2: cross-volume / refused renames — stream copy + delete.
            if (remaining.isNotEmpty()) {
                if (!destDir.isDirectory) try { destDir.mkdirs() } catch (e: Exception) { /* surfaced per-file below */ }
                val plan = plan(remaining, includeHidden)
                val planned = plan.files
                val totalBytes = planned.sumOf { runCatching { it.src.length() }.getOrDefault(0L) }
                var doneBytes = 0L
                for (p in remaining) {
                    if (cancelled) return@withContext Result(ok, failed, true, firstError)
                    try {
                        val landedName = uniqueName(destDir, p.root.name)
                        if (p.root.isFile) {
                            val dst = File(destDir, landedName)
                            try {
                                streamCopy(p.root, dst) { n ->
                                    doneBytes += n
                                    onProgress(Progress("Moving", doneBytes, totalBytes, doneItems, planned.size + totalItems))
                                }
                                if (!p.root.delete()) throw java.io.IOException("could not remove the original")
                                doneItems++; ok++
                            } catch (ce: CancelledException) { dst.delete(); throw ce }
                        } else {
                            val dstRoot = File(destDir, landedName)
                            val rootPrefix = p.root.absolutePath + "/"
                            // ── FINAL PASS 2 (§4): the destination tree exists BEFORE ──
                            // any file moves and BEFORE the source cleanup below can
                            // delete anything — an empty folder therefore MOVES as the
                            // real empty folder it is (created at the destination,
                            // original removed only afterwards), never the old
                            // data-loss shape where the source vanished and nothing
                            // replaced it.
                            try { dstRoot.mkdirs() } catch (e: Exception) { /* surfaced per-file below */ }
                            for (d in plan.dirs) {
                                if (!d.src.absolutePath.startsWith(rootPrefix)) continue
                                try { File(dstRoot, d.relPath).mkdirs() } catch (e: Exception) { /* surfaced per-file below */ }
                            }
                            val files = planned.filter { it.src.absolutePath.startsWith(rootPrefix) }
                            for (f in files) {
                                if (cancelled) return@withContext Result(ok, failed, true, firstError)
                                val dst = File(dstRoot, f.relPath)
                                dst.parentFile?.mkdirs()
                                try {
                                    streamCopy(f.src, dst) { n ->
                                        doneBytes += n
                                        onProgress(Progress("Moving", doneBytes, totalBytes, doneItems, planned.size + totalItems))
                                    }
                                    if (!f.src.delete()) throw java.io.IOException("could not remove the original")
                                    doneItems++
                                } catch (ce: CancelledException) { dst.delete(); throw ce }
                                catch (e: Exception) {
                                    failed++
                                    if (firstError == null) firstError = "'${f.src.name}': ${e.message ?: "move failed"}"
                                }
                            }
                            // The pick's root directory tree: remove now-empty
                            // dirs bottom-up; failures are non-fatal (the
                            // content is safely moved).
                            // ── GOAT-PASS 9 (Android 5–7 compatibility): ──
                            // java.nio.file.Files.walk is API 26+ — on
                            // Android 5/6/7 this cleanup threw NoSuchMethodError
                            // AFTER the content had already moved (the reported
                            // folder-move crash: files moved, then the app
                            // died cleaning up). The identical post-order
                            // "delete whatever is still there, bottom-up,
                            // failures non-fatal" walk in plain java.io —
                            // available on every API level this app supports.
                            try {
                                fun deleteTreeBottomUp(f: File) {
                                    val kids = try { f.listFiles() } catch (e: Exception) { null }
                                    if (kids != null) for (k in kids) deleteTreeBottomUp(k)
                                    try { f.delete() } catch (_: Exception) {}
                                }
                                deleteTreeBottomUp(p.root)
                            } catch (e: Exception) { /* best-effort cleanup */ }
                            ok++
                        }
                    } catch (ce: CancelledException) {
                        return@withContext Result(ok, failed, true, firstError)
                    } catch (e: Exception) {
                        failed++
                        if (firstError == null) firstError = "'${p.root.name}': ${e.message ?: "move failed"}"
                    }
                }
            }
            Result(ok, failed, false, firstError)
        } finally {
            running = false
        }
    }

    /**
     * Deletes each root recursively. Refuses filesystem/volume roots
     * outright (deleting "/storage/XXXX-XXXX" or internal storage is never
     * an accident a user means). Returns (succeeded, failed) top-level
     * counts; a failure never stops the rest of the batch, and a missing
     * file counts as failure (the caller refreshes regardless).
     */
    suspend fun deleteBatch(roots: List<File>): Pair<Int, Int> = withContext(Dispatchers.IO) {
        cancelled = false; running = true
        try {
            var ok = 0; var failed = 0
            for (root in roots) {
                if (cancelled) break
                val rp = try { root.canonicalPath } catch (e: Exception) { root.absolutePath }.trimEnd('/')
                // Volume roots and storage roots are never deletable here:
                // "/", "/storage", any "/storage/<mount>", and the internal
                // storage root itself (deleting any of these is never what
                // a folder-action tap means).
                val segs = rp.split('/').filter { it.isNotBlank() }
                val isMountRoot = rp == "/storage" ||
                        (rp.startsWith("/storage/") && segs.size == 2 &&
                         segs[1].length == 9 && segs[1][4] == '-')
                val internalRootPath = runCatching {
                    ExternalVolumes.internalRoot().canonicalPath.trimEnd('/')
                }.getOrDefault("")
                if (rp == "/" || segs.size <= 1 || isMountRoot || rp == internalRootPath) {
                    failed++; continue
                }
                val deleted = try { root.deleteRecursively() } catch (e: Exception) { false }
                if (deleted && !root.exists()) ok++ else failed++
            }
            ok to failed
        } finally {
            running = false
        }
    }
}
