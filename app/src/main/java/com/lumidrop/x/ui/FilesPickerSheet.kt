package com.lumidrop.x.ui

import android.app.Dialog
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lumidrop.x.R
import com.lumidrop.x.media.ThumbnailEngine
import com.lumidrop.x.server.MimeTypes
import com.lumidrop.x.settings.SettingsManager
import com.lumidrop.x.transfer.PreviewItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** The sort menu's keys (file scope: local enum classes are not legal Kotlin). */
private enum class FilesSortKey(val label: String) { NAME("Name"), DATE("Date modified"), SIZE("Size"), TYPE("Type") }

/** GOAT-PASS 8 (§9): the surgical selection repaint's bulk fallback bound —
 *  past this many changed rows, one DiffUtil pass dispatches cheaper than
 *  N individual notifyItemChanged calls. */
private const val SURGICAL_REPAINT_MAX = 320

/** A row's selection state — the third state is the whole point of Pass-34
 *  Part 2's folder selection (#3C). */
private enum class SelState { NONE, FULL, PARTIAL }

/** One browsable entry: a directory or a file of the CURRENT listing.
 *  ── GOAT-PASS 3 (§1): promoted from a sheet-local class to a top-level
 *  private one so the PROCESS-LEVEL listing cache (see [FilesSheetCache])
 *  can hold entries across sheet reopenings — the sheet-local cache was
 *  empty on every reopen, which is half of why the first open of a session
 *  felt slow while later ones (kernel page cache warm) did not. */
private class Entry(val file: File, val isDir: Boolean, val size: Long, val mtime: Long,
            /** A directory's item count, filled by a background pass (see
             *  listDir's GOAT-PASS 3 note) — 0 until known, never negative. */
            @Volatile var childCount: Int,
            /** ── Pass-36 (#7): non-null on a recursive-search hit — the ──
             *  item's folder path from the storage root ("0/Music/Rock"),
             *  rendered as the row's context line. Null on normal
             *  browsing rows. */
            val searchContext: String? = null) {
    val key: String get() = (if (isDir) "d:" else "f:") + file.absolutePath
}

// ── GOAT-PASS 8 (§9 — the visible-order cache's key + sort decoration): ────
// The key compares the two potentially-huge lists by IDENTITY (their content
// only ever changes through wholesale replacement — load()'s `entries =
// listed`, the search paths' fresh hit lists) and every small input by value,
// so "same key" is provably "same order" at a few-reference-compare cost.
private class VisibleOrderKey(val entries: List<Entry>, val hits: List<Entry>, val hidden: Boolean,
                               val query: String, val sort: FilesSortKey, val desc: Boolean,
                               val pinned: Set<String>, val search: Boolean) {
    override fun equals(other: Any?): Boolean =
        other is VisibleOrderKey && other.entries === entries && other.hits === hits &&
            other.hidden == hidden && other.query == query && other.sort == sort &&
            other.desc == desc && other.pinned == pinned && other.search == search
    override fun hashCode(): Int = System.identityHashCode(entries) * 31 + System.identityHashCode(hits) +
        (if (hidden) 1 else 0) + query.hashCode() * 7 + sort.hashCode() * 13 +
        (if (desc) 1 else 0) + pinned.hashCode() * 3
}

/** One entry with its sort keys precomputed (decorate-sort-undecorate) —
 *  a lowercase NAME and EXTENSION per entry instead of per comparison. */
private class DecoratedEntry(val e: Entry, val lcName: String, val ext: String)

// ── GOAT-PASS 3 (§1 — instant sheet opens) ─────────────────────────────────
/** The PROCESS-LEVEL per-directory listing LRU. The sheet-local cache this
 *  replaces was born empty on every sheet opening, so every reopening of
 *  the Files sheet re-paid the full directory listing I/O even for the very
 *  directory the user just left; only the kernel's page cache made later
 *  opens feel faster. Holding the LRU at process scope means the SECOND
 *  opening of a session is a pure cache read (no I/O at all), and the app's
 *  start-up prewarm (see AppPrewarm) fills the storage root's entry before
 *  the first opening ever happens — the reported "first open lags, later
 *  opens are smooth" becomes "every open is smooth".
 *
 *  NOT a correctness surface: entries are only ever read through load(),
 *  which still validates the directory is alive and still refreshes on
 *  demand (pull-navigate, delete/rename invalidations, storage switches).
 *  Cap and access-order semantics are identical to the old local map. */
private object FilesSheetCache {
    /** GOAT-PASS 9 (§5): a cached listing plus the directory's mtime AT THE
     *  MOMENT it was cached. A directory's mtime changes whenever its direct
     *  children change (create/delete/rename) — exactly what a listing shows
     *  — so one cheap stat() at serve time detects ANY external change to
     *  the directory (files added by other apps, camera output, downloads)
     *  and treats the entry as a miss. This is the correctness backstop for
     *  directories the observers do not watch; the open-sheet reload hook
     *  uses the same signal live. */
    private class CachedListing(val entries: List<Entry>, val dirMtimeAtCache: Long)
    private val listings = object : LinkedHashMap<String, CachedListing>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CachedListing>): Boolean = size > 24
    }
    /** Process-wide access is synchronized: the sheet reads/writes on the
     *  main thread, the start-up prewarm fills from a background one. */
    // ── GOAT-PASS 16 (§3): a 2-second memo for the freshness stat. The ──
    //  serve-time lastModified() is a REAL disk operation on the MAIN
    //  thread — microseconds on idle storage, but 100s of ms when a
    //  thumbnail burst is saturating the same device's I/O (the reported
    //  intermittent ~1.5 s tile freezes). Memoizing the answer per path for
    //  2 s removes the stat from the hot path entirely; a change that
    //  lands inside the memo window is caught by the very next serve past
    //  it or by the LibrarySync hook's own mtime comparison on the
    //  completed listing — the same reconciliation that already exists.
    private class StatMemo(var mtime: Long, var atMs: Long)
    private val statMemo = HashMap<String, StatMemo>()
    private fun memoizedMtime(path: String): Long {
        val now = System.currentTimeMillis()
        synchronized(statMemo) {
            val m = statMemo[path]
            if (m != null && now - m.atMs < 2_000L) return m.mtime
        }
        val live = try { java.io.File(path).lastModified() } catch (e: Exception) { -1L }
        synchronized(statMemo) {
            statMemo[path] = StatMemo(live, now)
            if (statMemo.size > 64) {   // bounded — one sheet browses a handful of dirs
                val it = statMemo.entries.iterator()
                var n = 0
                while (it.hasNext() && n < 32) { it.next(); it.remove(); n++ }
            }
        }
        return live
    }

    fun get(path: String): List<Entry>? = synchronized(listings) {
        val cached = listings[path] ?: return null
        val liveMtime = memoizedMtime(path)
        if (liveMtime != cached.dirMtimeAtCache) {
            // The directory changed since it was cached (external writer) —
            // the entry is stale; drop it so the caller re-lists from disk.
            listings.remove(path)
            return null
        }
        cached.entries
    }
    fun put(path: String, list: List<Entry>) {
        val mtime = try { java.io.File(path).lastModified() } catch (e: Exception) { 0L }
        synchronized(listings) { listings[path] = CachedListing(list, mtime) }
    }
    /** One-shot invalidation for a path (and its descendants) — used by the
     *  sheet's own delete/rename/paste flows so a cached listing can never
     *  resurrect removed entries within the LRU's lifetime.
     *  ── GOAT-PASS 8 (§10): the prefix test now requires a real PATH-SEGMENT ──
     *  boundary — the old bare startsWith made invalidating "/a/b" also nuke
     *  "/a/bc", an unrelated directory whose listing then had to be rebuilt
     *  from disk for nothing (and, worse, briefly re-listed without counts). */
    fun invalidate(path: String) {
        synchronized(listings) {
            val it = listings.entries.iterator()
            while (it.hasNext()) {
                val k = it.next().key
                if (k == path || k.startsWith("$path/")) it.remove()
            }
        }
    }
}

/** GOAT-PASS 8 (§10): cross-surface invalidation — file operations run by ──
 *  OTHER surfaces (the Music sheet's Delete/Rename/Copy/Cut/Paste, the
 *  Received tab's deletions) change exactly the same directories this
 *  sheet's process-level cache holds listings for. Every one of those
 *  completion sites calls this with the changed directory (and the changed
 *  directory's parent, whose cached row for it carries the childCount), so
 *  the Files sheet can never re-serve a stale listing or a stale folder
 *  count after an operation it did not drive itself. */
internal fun invalidateFilesSheetCacheFor(changedDir: File?) {
    if (changedDir == null) return
    try {
        FilesSheetCache.invalidate(changedDir.absolutePath)
        changedDir.parentFile?.let { FilesSheetCache.invalidate(it.absolutePath) }
    } catch (e: Exception) { /* never break a foreign surface's completion */ }
}

/** GOAT-PASS 9 (§5 — live synchronization): drops the native whole-storage
 *  search index so the next query rebuilds it from the REAL filesystem
 *  instead of serving results that predate the change. Called by the
 *  LibrarySync observers (external media/file changes) and the server's
 *  landing paths (received files) — the index rebuilds itself in the
 *  background (single-flight, capped), never blocking a query. */
internal fun invalidateNativeSearchIndex() {
    try { RecursiveFileSearch.invalidate() } catch (e: Exception) { /* the freshness window still bounds staleness */ }
}

/** GOAT-PASS 3 (§1 — instant sheet opens): one-shot, best-effort background
 *  prewarm of the storage ROOT's listing (child counts included — the prewarm
 *  is off the critical path, so it can pay the per-directory listFiles()
 *  passes the interactive listing defers) into the process-level cache, so
 *  the very FIRST opening of the Files sheet after a fresh app launch finds
 *  its content already resident instead of paying the cold dentry-cache
 *  walk. Called exactly once per process by AppPrewarm, never from the UI.
 *  Failure of any step is silently ignored — the sheet's own load() is the
 *  correctness path and works with or without a warmed cache. */
internal fun prewarmFilesSheetCache() {
    try {
        val root = try {
            @Suppress("DEPRECATION")
            android.os.Environment.getExternalStorageDirectory()
        } catch (e: Exception) { null } ?: return
        if (FilesSheetCache.get(root.absolutePath) != null) return   // already warm
        val kids = try { root.listFiles() } catch (e: Exception) { null } ?: return
        val entries = kids.mapNotNull { f ->
            try {
                val isDir = f.isDirectory
                Entry(
                    file = f,
                    isDir = isDir,
                    size = if (isDir) 0L else f.length(),
                    mtime = try { f.lastModified() } catch (e: Exception) { 0L },
                    childCount = if (isDir) try { f.listFiles()?.size ?: 0 } catch (e: Exception) { 0 } else 0,
                )
            } catch (e: Exception) { null }
        }
        FilesSheetCache.put(root.absolutePath, entries)
    } catch (e: Exception) { /* prewarm is best-effort, always */ }
}

/**
 * ══ Pass-33 (#6) / Pass-34 Parts 1–2: the unified FILES picker ══
 *
 * Part 1 built the pinned toolbar, breadcrumb from "0/", card rows, the sort
 * menu, hidden-file control and the fixed 75%/drag-expand sheet. Part 2 keeps
 * every bit of that and replaces the selection layer:
 *
 *   • NO CHECK CIRCLES (#1): the icon slot is the selection target. FULL —
 *     accent chip with a white check; PARTIAL (folders only) — accent chip
 *     with a white dash; NONE — the normal glyph. The card's tint + stroke
 *     carry the same three states, nothing moves, nothing resizes.
 *   • HOLD-TO-SELECT + RANGE (#2): long-press selects the row and anchors it;
 *     a later long-press selects the whole inclusive span between anchor and
 *     target in the CURRENT visible+sorted order (both directions, hidden/
 *     filtered rows excluded by construction, unrelated picks preserved).
 *   • RECURSIVE FOLDER SELECTION (#3): a selected folder is a ROOT PICK —
 *     everything eligible inside it is selected, recursively, and stays
 *     selected when the user navigates INTO it. Deselecting a child (file or
 *     subfolder) records an EXCLUSION against the root pick; the parent then
 *     shows the distinct PARTIAL state. Reselecting clears the exclusion.
 *     A tap on a PARTIAL folder finishes the selection (clears its nested
 *     exclusions); a tap on a FULL one deselects the whole subtree.
 *   • ADD builds the queue from the REAL selection (#3D): a pick with zero
 *     exclusions stays the physical folder (the server walks it, exactly as
 *     before); a pick with exclusions becomes a MANIFEST folder whose
 *     children are exactly the still-selected descendants, with relPaths
 *     that preserve the folder's own hierarchy — never flattened into
 *     top-level entries, never re-including deselected files.
 *   • The Part 1 diff's live-state read (selection toggles never rebound
 *     rows) is fixed the same way as the Music picker's: state is SNAPSHOTTED
 *     into each row at build time, so a toggle is a real content change on
 *     exactly that row.
 */
fun showFilesPickerSheet(
    fragment: Fragment,
    /** ── Pass-41 (§7): a FILE row's body tap OPENS the file through this
     *  handler — the app's internal previewer for images/videos/audio, the
     *  system open flow for everything else. Selection stays on the icon
     *  (and on the body while selection mode is active). Null restores the
     *  legacy behavior (body tap selects). Deliberately BEFORE [onAdd] so
     *  the Send tile's trailing-lambda call site keeps binding to Add. */
    onOpenFile: ((File) -> Unit)? = null,
    /** ── GOAT-PASS 10 (§9 — Open Received Folder Internally): an EXPLICIT ──
     *  opening directory. Null (every existing caller) keeps the exact
     *  behaviour the sheet always had — remembered-last-directory when the
     *  preference is on, the storage root otherwise. A live, readable
     *  directory opens the sheet DIRECTLY there (rooting the browsing volume
     *  at whichever mounted volume contains it, so an SD-card receive
     *  destination works too); an unavailable one is honestly ignored here
     *  and the caller — which validated availability BEFORE opening — shows
     *  its own error state. */
    startDir: File? = null,
    onAdd: (List<PreviewItem>) -> Unit,
) {
    // ── Pass-37 (#4): one live Files sheet, ever — the same synchronous ──────
    // acquire / dismiss-release guard the App Picker has had since Pass-26.
    // A rapid double-tap on the Files tile (the second landing while the
    // first sheet is still entering or listing) used to stack a second full
    // sheet with its own backdrop and dim; now it is a clean no-op, and the
    // first tap after a real close opens normally — the slot is released in
    // the dismiss listener, which fires exactly when the window is gone.
    if (!PickerGate.acquire("files")) return
    val ctx = fragment.requireContext()
    val sheet = BottomSheet(ctx, expandable = true, defaultHeightFraction = 0.75f)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // ── Model ─────────────────────────────────────────────────────────────
    /** One WHOLE-FOLDER selection: the root directory plus the set of
     *  deselected descendants (absolute paths; an exclusion prunes that path's
     *  entire subtree). Empty exclusions = FULL pick — the physical folder. */
    class FolderPick(val root: File) {
        val excluded = HashSet<String>()
        val partial: Boolean get() = excluded.isNotEmpty()
    }

    // Pass-44 (§8): the browsed ROOT is now MUTABLE — storage switching
    // swaps it between internal storage and a mounted removable volume.
    var rootDir: File = try {
        @Suppress("DEPRECATION")
        android.os.Environment.getExternalStorageDirectory()
    } catch (e: Exception) { File("/") }
    var currentDir = rootDir
    var entries: List<Entry> = emptyList()
    var loading = true
    var query = ""
    // ── Pass-36 (#7): the recursive global search's state. Search is no ───────
    // longer a filter over the current directory — it queries a budgeted
    // one-time index of the whole accessible hierarchy (see
    // RecursiveFileSearch at the bottom of this file) and renders RANKED
    // hits, each carrying its path from the storage root as context.
    var searchActive = false
    var searchIndexing = false
    var searchHits: List<Entry> = emptyList()
    /** A file hit to reveal — scroll to + select — once its parent's listing
     *  lands. Navigation is async, so the reveal rides the first submit that
     *  carries real (non-loading) rows. */
    var pendingRevealPath: String? = null
    /** ── GOAT-PASS 7 Part 2 (§2A): set by a search-locate jump — the reveal ──
     *  must position the item COMFORTABLY (the viewport's upper third) so it
     *  is unambiguously in view and never land flush against the list's
     *  edges, and it flashes a touch longer and stronger so the highlight is
     *  clearly visible. Cleared with the reveal itself. */
    var pendingRevealComfortable = false
    // ── Pass-42 (§6): the PER-DIRECTORY scroll anchor map. Every directory
    // the sheet visits remembers its first-visible ROW (by entry key — the
    // stable identity, so an item inserted/removed above it cannot silently
    // shift the restore) plus the row's pixel offset and adapter position.
    // Navigating away snapshots the outgoing directory; the incoming
    // directory's submit restores ITS remembered anchor — returning from a
    // child lands on the same items at the same viewport position, at any
    // nesting depth, each directory independently. A directory visited for
    // the first time starts at the top, exactly as before.
    val dirScrolls = HashMap<String, Triple<String, Int, Int>>()   // dirPath → (rowKey, topOffsetPx, position)
    /** The directory whose NEXT completed listing should restore its anchor
     *  (set by navigate; consumed by maybeRestoreDirScroll in load). */
    var pendingScrollRestore: String? = null
    // ── Pass-37 (#3C): the exact search screen to restore on Back. Set when ──
    // a result's navigation control jumps to the containing directory; Back
    // from THAT directory replays the saved state verbatim (query, ranked
    // hits, scroll, selection interface) without re-running the search.
    var searchRestore: (() -> Unit)? = null
    var searchRestoreDir: File? = null
    /** ── Pass-37 (#3C)/Pass-38: true while the search box's text changes ──
     *  PROGRAMMATICALLY — a Back-restore replaying the saved query, or
     *  navigate() clearing the box while leaving the results view. The
     *  change watcher must not treat either as a new query: a restore must
     *  replay its saved state verbatim, and navigate() already resets every
     *  search field itself (the watcher firing would only double-submit and,
     *  since Pass-38, wrongly drop the just-captured restore snapshot). */
    var programmaticSearchText = false
    val loadSerial = AtomicInteger(0)    // navigation/listing supersession
    val searchSerial = AtomicInteger(0)  // debounce supersession (never drops a listing)

    var sortKey = FilesSortKey.NAME
    var sortDesc = false
    var showHidden = SettingsManager.isPickerShowHidden(ctx)
    // ── Pass-44 (§7): PINNED ITEMS. A pin is a display preference keyed by ──
    // absolute path (persisted via SettingsManager — it survives refreshes,
    // sheet reopenings and app restarts); the pinned entries of a directory
    // float to the top of THAT directory's listing as their own group, the
    // underlying file is never moved, and unpinning restores the normal
    // order. Loaded once per sheet open; mutated through togglePins().
    var pinnedPaths: MutableSet<String> = SettingsManager.getPinnedPaths(ctx)
    // ── Pass-44 (§7): the copy/cut CLIPBOARD. Armed from the live selection
    // (folder picks carry their exclusion sets — a partial folder pastes
    // exactly the still-selected subtree, the same rule the send flow
    // uses); cleared on a completed paste, replaced by the next Copy/Cut,
    // and discarded with the sheet.
    class SheetClipboard(val isCut: Boolean, val picks: List<FileOps.Pick>)
    var clipboard: SheetClipboard? = null
    var opJob: kotlinx.coroutines.Job? = null
    /** GOAT-PASS 11 (P5.4): false once the sheet's dismiss listener ran —
     *  every extraction/terminal UI step checks it, so a dismissed sheet's
     *  views are never touched again, by any callback, from any dispatch.
     *  (Plain var: this is a captured local of the sheet builder, not a
     *  class property — reads/writes only happen at lifecycle boundaries.) */
    var sheetAlive = true
    // ── Pass-44 (§8): removable volumes (SD / USB OTG), refreshed on open,
    // on navigation and before every switch — the list is always LIVE, so a
    // pulled card disappears from the switcher instead of surfacing a dead
    // path. Empty list = internal-only and the switcher stays hidden.
    var volumesCache: List<RemovableVolume> = ExternalVolumes.mounted(ctx)
    // ── GOAT-PASS 16 (§3): the volume probe is NOT free — it stats every ──
    //  mounted root's existence and readability, and load() called it on
    //  EVERY navigation (three stats minimum, more with cards). A 5-second
    //  memo keeps the switcher honest across real card insertion (a card
    //  pull is ALREADY handled instantly by load()'s dirAlive recovery)
    //  while taking the probe off the navigation hot path.
    var volumesProbedAtMs = 0L
    fun refreshVolumes(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - volumesProbedAtMs < 5_000L) return
        volumesProbedAtMs = now
        volumesCache = ExternalVolumes.mounted(ctx)
    }

    // ── The selection model (#3) ──────────────────────────────────────────────
    /** Root picks by absolute path (insertion = pick order). */
    val folderPicks = LinkedHashMap<String, FolderPick>()
    /** Standalone file picks by absolute path — never a path already covered
     *  by a folder pick (a pick absorbs the file picks beneath it). */
    val filePicks = LinkedHashMap<String, File>()
    /** Range-selection anchor: the last SELECTED row's key, validated against
     *  the visible list before every use. */
    var anchorKey: String? = null

    fun selectionMode(): Boolean = folderPicks.isNotEmpty() || filePicks.isNotEmpty()

    // Behavior slot — declared up here because the model functions below
    // call it after every mutation, and locals are strictly before-use.
    var repaint: () -> Unit = {}
    /** ── GOAT-PASS 8 (§9): the SURGICAL selection repaint slot — assigned ──────
     *  once the adapter exists (see repaintStates below). Selection mutations
     *  (toggles, the drag's set-states, the long-press range) route through it
     *  instead of the full-list rebuild: only the rows whose SelState actually
     *  changed are re-bound, with NO re-sort, NO full Row rebuild and NO
     *  DiffUtil pass — the per-toggle cost on a several-thousand-entry
     *  directory drops from a full main-thread sort+diff to a linear state
     *  scan. Falls back to the full path (bulk changes, loading, empty). */
    var repaintSelection: () -> Unit = {}
    // ── SELECTION FINAL PASS 1 (§2D): the chrome slot — paintChrome itself is ──
    // declared much later (after every view and helper it touches), so the
    // drag fast path (declared with the gesture wiring, before it) reaches it
    // through this slot — the exact discipline `repaint`/`repaintSelection`
    // already follow. Assigned once paintChrome exists (see below).
    var paintChromeSlot: () -> Unit = {}

    /** The pick whose root is a STRICT ancestor of [path], if any. */
    fun coveringPick(path: String): FolderPick? =
        folderPicks.values.firstOrNull { path.startsWith(it.root.absolutePath + "/") }

    /** Whether [path] is out of [cover] — deselected directly, or by living
     *  inside a deselected subfolder. Everything under a deselected subtree
     *  is out, all the way down; a state query that checked only the exact
     *  path would show a deselected folder's children as selected. */
    fun isExcludedBy(cover: FolderPick, path: String): Boolean =
        path in cover.excluded || cover.excluded.any { path.startsWith("$it/") }

    fun fileState(f: File): SelState {
        val path = f.absolutePath
        val cover = coveringPick(path)
        if (cover != null) return if (isExcludedBy(cover, path)) SelState.NONE else SelState.FULL
        return if (filePicks.containsKey(path)) SelState.FULL else SelState.NONE
    }

    /** A folder's three-state verdict (#3C): a root pick is FULL or PARTIAL by
     *  its own exclusions; a covered folder is NONE if its whole subtree is
     *  deselected (directly or by an ancestor exclusion), PARTIAL if some but
     *  not all of it is, FULL otherwise. */
    fun folderState(g: File): SelState {
        val path = g.absolutePath
        folderPicks[path]?.let { return if (it.partial) SelState.PARTIAL else SelState.FULL }
        val cover = coveringPick(path) ?: return SelState.NONE
        if (isExcludedBy(cover, path)) return SelState.NONE
        return if (cover.excluded.any { it.startsWith("$path/") }) SelState.PARTIAL else SelState.FULL
    }

    fun selectFileOn(f: File) {
        val path = f.absolutePath
        val cover = coveringPick(path)
        if (cover != null) {
            // Covered: select = restore — the file's own exclusion, or the
            // deselected subfolder covering it, goes (tapping inside a
            // deselected subfolder brings that subfolder back selected).
            cover.excluded.removeAll { it == path || path.startsWith("$it/") }
        } else filePicks[path] = f
    }

    fun selectFolderOn(g: File) {
        val path = g.absolutePath
        folderPicks[path]?.let { it.excluded.clear(); return }   // already a root pick: FULL
        val cover = coveringPick(path)
        if (cover != null) {
            // Covered: FULL means clear nested exclusions; a deselected
            // subtree (directly or under a deselected ancestor) restores
            // entirely — the covering exclusion goes with it.
            cover.excluded.removeAll { it == path || it.startsWith("$path/") || path.startsWith("$it/") }
        } else {
            // New root pick: absorb every pick strictly inside (redundant
            // once the ancestor is fully selected — one clean root instead
            // of overlapping state).
            folderPicks.keys.removeAll { it.startsWith("$path/") }
            filePicks.keys.removeAll { it.startsWith("$path/") }
            folderPicks[path] = FolderPick(g)
        }
    }

    /** ── Pass-39 (#1): set-state selection primitives for the hold-and-slide ──
     *  gesture — unlike the TOGGLE paths, these land on an exact wanted state
     *  (the gesture captured ONE mode for its whole run), reusing the model's
     *  own primitives so every bookkeeping rule (absorption, exclusions,
     *  anchors) behaves exactly like the equivalent taps. */
    fun setFileSelected(f: File, want: Boolean) {
        val path = f.absolutePath
        if (want) {
            if (fileState(f) != SelState.FULL) selectFileOn(f)
        } else {
            // ── GOAT-PASS 8 (§3): a deselection through the drag's set-state ──
            // primitive clears the range anchor when it points at THIS item —
            // the same invariant every toggle path already maintains, so a
            // REMOVE pass over the anchor can never leave a ghost behind.
            if (anchorKey == "f:$path") anchorKey = null
            val cover = coveringPick(path)
            if (cover != null) {
                if (!isExcludedBy(cover, path)) cover.excluded.add(path)
            } else filePicks.remove(path)
        }
    }

    fun setFolderSelected(g: File, want: Boolean) {
        val path = g.absolutePath
        if (want) {
            if (folderState(g) == SelState.NONE) selectFolderOn(g)
            else folderPicks[path]?.excluded?.clear()   // partial → full
        } else {
            // ── GOAT-PASS 8 (§3): same anchor-clearing invariant on folders. ──
            if (anchorKey == "d:$path") anchorKey = null
            if (folderPicks.containsKey(path)) folderPicks.remove(path)
            else {
                val cover = coveringPick(path)
                if (cover != null && !isExcludedBy(cover, path)) cover.excluded.add(path)
            }
        }
    }

    fun toggleFile(f: File) {
        val path = f.absolutePath
        val cover = coveringPick(path)
        if (cover != null) {
            if (isExcludedBy(cover, path)) {
                // Deselected (directly, or inside a deselected subfolder):
                // the tap restores it — the covering exclusion goes.
                cover.excluded.removeAll { it == path || path.startsWith("$it/") }
                anchorKey = "f:$path"
            } else {
                cover.excluded.add(path)
                if (anchorKey == "f:$path") anchorKey = null
            }
        } else if (filePicks.containsKey(path)) {
            filePicks.remove(path)
            if (anchorKey == "f:$path") anchorKey = null
        } else {
            filePicks[path] = f
            anchorKey = "f:$path"
        }
        repaintSelection()
    }

    /** Folder toggle (#1/#3): tap = toggle between the meaningful states —
     *  FULL → deselect the subtree; PARTIAL → finish (clear nested
     *  exclusions); NONE (uncovered) → new root pick; a root pick → drop it. */
    fun toggleFolder(g: File) {
        val path = g.absolutePath
        if (folderPicks.containsKey(path)) {
            folderPicks.remove(path)
            if (anchorKey == "d:$path") anchorKey = null
        } else {
            val cover = coveringPick(path)
            if (cover != null) {
                if (isExcludedBy(cover, path)) {
                    // Deselected subtree (directly, or under a deselected
                    // ancestor): reselect restores the covering subtree.
                    cover.excluded.removeAll { it == path || it.startsWith("$path/") || path.startsWith("$it/") }
                    anchorKey = "d:$path"
                } else if (cover.excluded.any { it.startsWith("$path/") }) {
                    // PARTIAL: finish the selection.
                    cover.excluded.removeAll { it.startsWith("$path/") }
                    anchorKey = "d:$path"
                } else {
                    // FULL: deselect the whole subtree.
                    cover.excluded.add(path)
                    if (anchorKey == "d:$path") anchorKey = null
                }
            } else {
                selectFolderOn(g)
                anchorKey = "d:$path"
            }
        }
        repaintSelection()
    }

    // (GOAT-PASS 3 §1: the listing LRU is now the process-level
    //  FilesSheetCache — see its doc at the top of this file.)

    fun isHiddenName(f: File): Boolean = f.name.startsWith(".") ||
        (try { f.isHidden } catch (e: Exception) { false })

    fun listDir(dir: File): List<Entry> {
        val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: return emptyList()
        // ── GOAT-PASS 3 (§1): the per-directory childCount is no longer on ──
        // the listing's critical path. Computing it inline ran ONE FULL
        // listFiles() per directory in the listing — on a cold dentry cache
        // (exactly the first-open-after-launch case) that is the dominant
        // cost of showing the folder at all. The listing now materializes
        // with childCount unknown (0 → renders as "Folder") and a bounded
        // background pass fills the counts and refreshes the rows — the
        // directory is on screen immediately, the counts land a moment
        // later as a quiet text update. (The prewarmed/cached path usually
        // already carries the counts, so the pass is usually a no-op.)
        return kids.mapNotNull { f ->
            try {
                val isDir = f.isDirectory
                Entry(
                    file = f,
                    isDir = isDir,
                    size = if (isDir) 0L else f.length(),
                    mtime = try { f.lastModified() } catch (e: Exception) { 0L },
                    childCount = 0,
                )
            } catch (e: Exception) { null }
        }
    }

    fun col(id: Int) = ContextCompat.getColor(ctx, id)
    // ── Pass-39 (extra fix 2): REAL folder sizes in the selection total. ──────
    // The old counter summed only the standalone FILE picks — a selected
    // folder contributed ZERO bytes (and the files it covered weren't in
    // filePicks either, by design). Every directory's recursive total is now
    // walked ONCE per sheet session on a background dispatcher and cached by
    // absolute path; the footer shows the known part immediately and a "…"
    // while any walk is still in flight, then settles on the accurate figure.
    val dirSizeCache = HashMap<String, Long>()
    val dirSizeInFlight = HashSet<String>()

    // ── SELECTION FINAL PASS 1 (§2A): the standalone-file length cache. A ──────
    // pick's size is stat'ed exactly ONCE (the first time it is needed) and
    // served from this map afterwards — selection repaints never touch the
    // filesystem again, however many items are selected (see
    // selectedBytesDetailed's own note for why this matters). Bounded by one
    // sheet session's picked set (the same lifetime dirSizeCache has), and it
    // stays VALID across deselections — a path's length does not change by
    // being un-picked, so a re-pick reads the same figure without a new stat.
    val lenCache = HashMap<String, Long>()
    fun cachedFileLen(f: File): Long =
        lenCache.getOrPut(f.absolutePath) { try { f.length() } catch (e: Exception) { 0L } }

    /** A directory's full recursive size, or null while its (one-session)
     *  walk is still in flight. Unreadable content contributes nothing —
     *  an inaccessible subtree is skipped, never guessed. */
    fun dirTotal(dir: File): Long? {
        val path = dir.absolutePath
        dirSizeCache[path]?.let { return it }
        if (dirSizeInFlight.contains(path)) return null
        if (!dir.isDirectory) return dir.length()
        dirSizeInFlight.add(path)
        scope.launch {
            val total = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    dir.walkTopDown().filter { it.isFile }.sumOf { runCatching { it.length() }.getOrDefault(0L) }
                } catch (e: Exception) { 0L }
            }
            dirSizeCache[path] = total
            dirSizeInFlight.remove(path)
            // Repaint only if this folder is still part of the selection —
            // a finished walk for a deselected folder lands silently.
            // (repaint, not paintChrome: this local is declared below, and
            // by the time a walk lands it points at the real refresh.)
            if (folderPicks.containsKey(path) || folderPicks.values.any { it.excluded.contains(path) }) {
                repaint()
            }
        }
        return null
    }

    /** The selection's total size: standalone files plus every picked
     *  folder's recursive total minus its deselected subtrees. [pending] is
     *  true while any needed walk is still running (the footer's "…").
     *
     *  ── SELECTION FINAL PASS 1 (§2A — no stat() on the selection path): the ──
     *  standalone-file total used to call File.length() on EVERY selected
     *  file on EVERY selection mutation — a real filesystem syscall per
     *  pick, per toggle, per crossed Hold & Slide row, all on the main
     *  thread. That is exactly the cost that grew linearly with the size of
     *  the selection (102 picks → 102+ syscalls per interaction; 500 →
     *  500+), and it was the dominant cause of the reported progressive
     *  freeze. The figure is now served from [lenCache] — one real stat the
     *  FIRST time a path is picked (the very same stat listDir already paid
     *  to show the row's size), a map read every time after that. */
    fun selectedBytesDetailed(): Pair<Long, Boolean> {
        var total = 0L
        for (f in filePicks.values) total += cachedFileLen(f)
        var pending = false
        for (pick in folderPicks.values) {
            when (val t = dirTotal(pick.root)) {
                null -> pending = true
                else -> {
                    total += t
                    // Subtract each TOP-LEVEL excluded subtree once (nested
                    // exclusions are already inside their ancestor's total).
                    val excluded = pick.excluded
                    for (e in excluded) {
                        if (excluded.none { other -> other != e && e.startsWith("$other/") }) {
                            when (val sub = dirTotal(File(e))) {
                                null -> pending = true
                                else -> total -= sub
                            }
                        }
                    }
                }
            }
        }
        return total.coerceAtLeast(0L) to pending
    }

    // ── Chrome ────────────────────────────────────────────────────────────────
    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    // ── Pass-35 (#3): the title row IS the navigation bar now. "Files" keeps ──
    // its place on the left; the breadcrumb moved INTO the same row as a
    // compact rounded pill on the right — back icon + horizontally scrollable
    // "0/…/here" segments — and the old separate crumb row below the title is
    // gone, along with the folder/file counter subtitle that lived under the
    // title. One row carries identity + location, and every pixel the two
    // removed rows used to take is the browsing list's again.
    val titleView = TextView(ctx).apply {
        text = "Files"; textSize = 16.5f; setTextColor(col(R.color.text_primary)); maxLines = 1
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    // The back-navigation icon: navigates to the parent directory, exactly
    // like Android Back does (the sheet's onBack mirrors this handler).
    val navBackBtn = Widgets.iconButton(ctx, R.drawable.ic_chevron_right, col(R.color.text_secondary),
        sizeDp = 30, iconSizeDp = 16, contentDescription = "Back to parent folder").apply {
        rotation = 180f
        layoutParams = LinearLayout.LayoutParams(ctx.dp(30), ctx.dp(30))
    }
    // Breadcrumb: "0/" + segments, clickable, horizontally scrollable for
    // deep paths. The pill is weight-constrained inside the title row, so a
    // long path can never push the "Files" title or overflow the row — it
    // scrolls inside its own pill instead, auto-scrolled to the current
    // segment on every navigation.
    val crumbBox = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }
    // ── SELECTION FINAL PASS 1 (§2B): the breadcrumb's identity key — see ──────
    // rebuildBreadcrumb's own guard for why the rebuild is now change-gated.
    var lastBreadcrumbIdentity: String? = null
    val crumbScroller = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    crumbScroller.addView(crumbBox)
    val navPill = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(4), ctx.dp(3), ctx.dp(10), ctx.dp(3))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dpf(14f)
            setColor(col(R.color.bg_deep))
            setStroke(ctx.dp(1), 0x1FFFFFFF)
        }
        // The pill itself is NOT clickable as a whole: only its real controls
        // (back icon, crumb segments) are, which is also what lets the
        // header drag region's hit test pass presses on its empty padding
        // through to the sheet drag.
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginStart = ctx.dp(10)
        }
        addView(navBackBtn)
        addView(crumbScroller)
    }
    val titleRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(18), ctx.dp(2), ctx.dp(18), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(titleView)
        addView(navPill)
    }
    root.addView(titleRow)

    // Search row: the field fills, the sort button is a FIXED-size icon
    // button — its dimensions never change, so the search width is stable
    // no matter what the sort menu does.
    val controls = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(18), ctx.dp(8), ctx.dp(18), 0)
    }
    // GOAT-PASS 3 (§2): assigned once the sheet's search machinery exists
    // (see the assignment next to the text watcher) — the EditText's IME
    // action callback is registered before those locals are declared, so it
    // routes through this late-bound hook. Finalizes the query and dismisses
    // the keyboard.
    var searchFinalizer: (() -> Unit)? = null
    val searchBox: EditText = Widgets.inputField(ctx, "", android.text.InputType.TYPE_CLASS_TEXT).apply {
        // ── GOAT-PASS 4 (§2): ONE unified search, and it searches ALL ─────────
        // accessible storage — the GOAT-PASS 3 current-directory restriction
        // and its "This folder / All storage" scope chip are retired. One
        // search bar, one behaviour: ranked whole-storage results with path
        // context, backed by a background-prewarmed index (AppPrewarm +
        // this sheet's own open) so the first query is already fast.
        hint = "Search all storage"
        // ── GOAT-PASS 3 (§2 — keyboard Enter): the IME's Search action ─────
        // FINALIZES the query immediately (any pending debounce is flushed
        // and the results render NOW), then clears focus so the keyboard
        // dismisses at once. The query stays in the box, the results stay on
        // screen, and nothing else is triggered — no directory reset, no
        // unrelated action. IME_ACTION_UNSPECIFIED is accepted because some
        // keyboards deliver plain Enter that way.
        setSingleLine()
        imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE ||
                actionId == android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
                searchFinalizer?.invoke()
                clearFocus()
                true
            } else false
        }
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = ctx.dp(8) }
    }
    // ── Pass-44 (§8): the storage switcher — the same quiet 40dp icon slot ──
    // style as the actions button, shown ONLY while a readable removable
    // volume exists (visibility is managed by paintChrome from the live
    // volumes cache; tapping it lists Internal storage + every volume).
    val storageBtn = Widgets.iconButton(ctx, R.drawable.ic_sd, col(R.color.text_secondary),
        sizeDp = 40, iconSizeDp = 19, contentDescription = "Switch storage").apply {
        visibility = View.GONE
    }
    // ── Pass-44 (§7): the sort icon is now the minimalist THREE-DOT actions ──
    // menu — the exact same position and size as the button it replaces.
    // Every sorting option lives on inside it (unchanged), joined by the
    // conditional file-management actions (Copy / Cut / Paste / Pin /
    // Delete / Rename) that appear only while they can act.
    val sortBtn = Widgets.iconButton(ctx, R.drawable.ic_more, col(R.color.accent_light),
        sizeDp = 40, iconSizeDp = 19, contentDescription = "Sort and more")
    controls.addView(searchBox); controls.addView(storageBtn); controls.addView(sortBtn)
    root.addView(controls)

    // Action row: live selection line (weight — the ONLY element whose text
    // changes with the count), then three fixed compact actions that never
    // move or resize: Select all, Clear, Add.
    // ── Pass-36 (#3): the toolbar is two compact pills now — the selection ────
    // counter (#3A) and the unified All/Clear/Add segmented control (#3B),
    // shared verbatim with the Music sheet. One quiet row; the freed width
    // belongs to the browsing list.
    val actionRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(18), ctx.dp(10), ctx.dp(18), ctx.dp(8))
    }
    val footerInfo = selectionCountPill(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginEnd = ctx.dp(10)
        }
    }
    val actionPill = SelectionActionPill(ctx, quickShareActionVerb(ctx))
    val selectAllBtn = actionPill.allBtn
    val clearBtn = actionPill.clearBtn
    val addBtn = actionPill.addBtn
    actionRow.addView(footerInfo)
    actionRow.addView(actionPill)
    root.addView(actionRow)

    // The scrolling area, with the pinned toolbar's gradient separation
    // strip laid over its very top.
    val list = RecyclerView(ctx).apply {
        layoutManager = LinearLayoutManager(ctx)
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        clipToPadding = false
        setPadding(ctx.dp(14), ctx.dp(4), ctx.dp(14), ctx.dp(10))
        (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
    }
    // ── Pass-44 (§7): the copy/cut progress bar — one slim, bottom-pinned ──
    // card over the list: determinate bar (indeterminate while planning),
    // phase + percent, and a Cancel that stops the ENGINE (the in-flight
    // partial file is deleted; nothing half survives).
    val opProgress = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
        isIndeterminate = true
        progressTintList = android.content.res.ColorStateList.valueOf(col(R.color.accent))
        progressBackgroundTintList = android.content.res.ColorStateList.valueOf(0x33FFFFFF)
        layoutParams = LinearLayout.LayoutParams(0, ctx.dp(8), 1f).also { it.marginEnd = ctx.dp(10) }
    }
    val opPct = TextView(ctx).apply {
        textSize = 11.5f; setTextColor(col(R.color.text_primary)); text = "Working…"
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.marginEnd = ctx.dp(8)
        }
    }
    val opCancelBtn = Widgets.iconButton(ctx, R.drawable.ic_close, col(R.color.text_secondary),
        sizeDp = 30, iconSizeDp = 14, contentDescription = "Cancel the operation")
    val opBar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setPadding(ctx.dp(14), ctx.dp(10), ctx.dp(14), ctx.dp(10))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = ctx.dpf(14f)
            setColor(col(R.color.bg_card_elevated)); setStroke(ctx.dp(1), 0x33FFFFFF)
        }
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).also {
            it.marginStart = ctx.dp(10); it.marginEnd = ctx.dp(10); it.bottomMargin = ctx.dp(10)
        }
        addView(opProgress); addView(opPct); addView(opCancelBtn)
    }
    opCancelBtn.setOnClickListener {
        if (FileOps.running) {
            FileOps.cancel()
            Toast.makeText(ctx, "Cancelling…", Toast.LENGTH_SHORT).show()
        }
    }
    // ── GOAT-PASS 8 (§7): the floating PASTE/CANCEL bar. One compact, ────────
    // bottom-pinned card in the same 14dp-radius surface language the opBar
    // uses: a Paste primary pill (icon + label + the armed item count), a
    // hairline divider, and a ghost Cancel that discards the pending
    // clipboard operation WITHOUT touching the original files. Declared here
    // (before the list container below adds it) so the click wiring can live
    // beside it; visibility is owned solely by paintChrome — the bar shows
    // exactly while a Copy/Cut clipboard is armed and no engine operation is
    // in flight, so repeated Copy/Cut operations can never stack a second
    // bar and the opBar's progress never fights it for the same spot.
    val pasteFloatPasteBtn = TextView(ctx).apply {
        textSize = 12.5f; typeface = android.graphics.Typeface.DEFAULT_BOLD
        setTextColor(android.graphics.Color.WHITE)
        gravity = Gravity.CENTER
        background = Widgets.rippleRect(ctx, col(R.color.accent), radiusDp = 11)
        setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        text = "Paste"
    }
    val pasteFloatCancelBtn = TextView(ctx).apply {
        textSize = 12.5f
        setTextColor(col(R.color.text_secondary))
        gravity = Gravity.CENTER
        background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_ghost)
        setPadding(ctx.dp(14), ctx.dp(8), ctx.dp(14), ctx.dp(8))
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        text = "Cancel"
    }
    val pasteFloatBar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        visibility = View.GONE
        setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(8))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = ctx.dpf(14f)
            setColor(col(R.color.bg_card_elevated)); setStroke(ctx.dp(1), 0x33FFFFFF)
        }
        elevation = ctx.dpf(10f)
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).also {
            it.marginStart = ctx.dp(10); it.marginEnd = ctx.dp(10); it.bottomMargin = ctx.dp(10)
        }
        addView(pasteFloatPasteBtn)
        addView(pasteFloatCancelBtn)
    }
    root.addView(FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(list)
        addView(View(ctx).apply {
            background = GradientDrawable().apply {
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(col(R.color.bg_sheet), (col(R.color.bg_sheet) and 0x00FFFFFF))
            }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(10), Gravity.TOP)
        })
        addView(opBar)
        // ── GOAT-PASS 8 (§7): the floating PASTE/CANCEL bar — shown exactly ──
        // while a Copy/Cut clipboard is armed (see paintPasteBar), pinned to
        // the list container's bottom edge with the same 14dp radius card
        // language the opBar uses. Paste runs the same pasteClipboard() the
        // ⋮ menu's Paste entry runs; Cancel discards the pending operation
        // WITHOUT touching the original files (the clipboard is simply
        // dropped). It never overlaps the opBar: paintPasteBar hides it
        // while an engine operation is in flight.
        addView(pasteFloatBar)
    })

    /** The adapter's row model: the entry plus its SNAPSHOT selection state —
     *  the snapshot is what makes a toggle a real DiffUtil content change on
     *  exactly that row (see submitVisible). Declared before the slot below,
     *  whose type mentions it (locals are strictly before-use). */
    class Row(val entry: Entry, val state: SelState, val pinned: Boolean)

    // ── Behavior slots (the adapter ↔ toggle ↔ repaint cycle). `repaint`
    // itself is declared up with the model, before its first callers. ────────
    var toggleFileFn: (File) -> Unit = {}
    var toggleFolderFn: (File) -> Unit = {}
    var navigateFn: (File) -> Unit = {}
    /** ── Pass-41 (§7): a normal (non-search) FILE row's body tap — OPENS the
     *  file through the caller's handler. Assigned from showFilesPickerSheet's
     *  [onOpenFile] parameter; the default (and a null handler) keeps the
     *  legacy select-on-body behavior, so nothing but the body-tap branch
     *  below ever changes. */
    var openFileFn: (File) -> Unit = {}
    var openSearchFileFn: (File) -> Unit = {}
    // (Pass-37 #3A retired openSearchFile: a search result's main area now
    // SELECTS the item; navigation is the dedicated arrow's job. The slot
    // stays assigned-empty — harmless, and the pattern is shared.)
    /** ── Pass-37 (#3B): the search-result row's dedicated navigate control ──
     *  (assigned once navigateToSearchHit exists below — the same
     *  declaration-before-use slot discipline every other behavior fn here
     *  follows). */
    var navigateSearchHitFn: (Entry) -> Unit = {}
    var longPressFn: (Row) -> Unit = {}

    var shown: List<Row> = emptyList()

    // ── GOAT-PASS 8 (§9): the visible-order cache and its key (see ────────────
    // visibleEntries). The big lists compare by IDENTITY — their contents
    // only ever change through wholesale replacement — so the equality check
    // is a few reference compares however large the directory is.
    var visibleOrderCache: List<Entry> = emptyList()
    var visibleOrderKey: VisibleOrderKey? = null

    fun visibleEntries(): List<Entry> {
        // ── GOAT-PASS 8 (§9 — huge directories, the ORDER CACHE): the sorted ──
        // visible order is now computed ONCE per genuine input change, not on
        // every repaint. Selection toggles (the hottest path — one per drag
        // row, one per tap) re-read the cache and pay nothing but the state
        // reads; the sort itself was the dominant per-toggle cost on a
        // several-thousand-entry directory (a full re-sort with a lowercase
        // allocation per COMPARISON — tens of thousands of allocations and
        // O(N log N) string compares on the main thread, for a list whose
        // ORDER did not change at all). The cache key compares the two big
        // lists by IDENTITY (their content only ever changes by replacement —
        // see load()'s `entries = listed` and the search paths' fresh lists)
        // and the small inputs (hidden flag, query, sort key/direction, pin
        // set, search mode) by value, so any real change still re-computes.
        val key = VisibleOrderKey(entries, searchHits, showHidden, query, sortKey, sortDesc, pinnedPaths, searchActive)
        if (key == visibleOrderKey) return visibleOrderCache
        // ── Pass-36 (#7): an active search shows RANKED GLOBAL hits — the ─────
        // current directory's filter and sort do not apply (relevance IS the
        // order, and each hit carries its own path context). "All" selecting
        // the visible list therefore selects the results, which is exactly
        // what a person asking Select All in search means.
        if (searchActive) {
            visibleOrderCache = searchHits
            visibleOrderKey = key
            return searchHits
        }
        // Hidden first, then the query — a hidden item can therefore never
        // surface through search while hidden mode is off.
        var out = if (showHidden) entries else entries.filter { !isHiddenName(it.file) }
        if (query.isNotBlank()) {
            val q = query.trim().lowercase()
            out = out.filter { it.file.name.lowercase().contains(q) }
        }
        // ── GOAT-PASS 8 (§9 — decorate-sort-undecorate): one lowercase per ──
        // ENTRY instead of one per COMPARISON, and comparator chains that
        // read precomputed fields. Folders stay FIRST in both directions —
        // the browsing hierarchy reads top-down — so only the key itself
        // reverses. Pinned entries of THIS directory float to the top as
        // their own group; inside BOTH groups the user's remembered order
        // (folders first, then criterion + direction) is untouched.
        val decorated = ArrayList<DecoratedEntry>(out.size)
        for (e in out) decorated.add(DecoratedEntry(e, e.file.name.lowercase(), e.file.extension.lowercase()))
        val pinnedSet = pinnedPaths
        decorated.sortWith { a, b ->
            val ap = a.e.file.absolutePath in pinnedSet
            val bp = b.e.file.absolutePath in pinnedSet
            if (ap != bp) return@sortWith if (ap) -1 else 1
            if (a.e.isDir != b.e.isDir) return@sortWith if (a.e.isDir) -1 else 1
            val cmp = when (sortKey) {
                FilesSortKey.NAME -> a.lcName.compareTo(b.lcName)
                FilesSortKey.DATE -> a.e.mtime.compareTo(b.e.mtime)
                FilesSortKey.SIZE -> a.e.size.compareTo(b.e.size)
                FilesSortKey.TYPE -> a.ext.compareTo(b.ext)
            }
            if (cmp != 0) return@sortWith if (sortDesc) -cmp else cmp
            val tie = a.lcName.compareTo(b.lcName)   // the deterministic tiebreak
            if (tie != 0) tie else a.e.file.absolutePath.compareTo(b.e.file.absolutePath)
        }
        val result = ArrayList<Entry>(decorated.size)
        for (d in decorated) result.add(d.e)
        visibleOrderCache = result
        visibleOrderKey = key
        return result
    }

    fun buildRows(): List<Row> = visibleEntries().map {
        Row(it, if (it.isDir) folderState(it.file) else fileState(it.file),
            pinnedPaths.contains(it.file.absolutePath)) }

    // ── The adapter ───────────────────────────────────────────────────────────
    val TYPE_ROW = 0; val TYPE_STATE = 1

    /** ── Pass-36 Part 2 (#3): a row ViewHolder that owns its in-flight ──────
     *  thumbnail decode — cancelled on recycle and on rebind, so scrolling a
     *  huge directory never stacks orphan decodes and a recycled row can
     *  never receive a stale bitmap. */
    class RowHolder(row: View) : RecyclerView.ViewHolder(row) {
        var thumbJob: Job? = null
    }

    val listAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(true) }
        private fun rowsVisible(): Boolean = !loading && shown.isNotEmpty()

        // ── SELECTION FINAL PASS 1 (§2C — the selection-only payload rebind). ──
        // repaintStates used to dispatch notifyItemChanged WITHOUT a payload,
        // so every selection flip ran the FULL bind: name/meta text rewrites,
        // chevron re-wiring and — most expensively — a thumbnail-job cancel +
        // relaunch for the unselected side and a fresh GradientDrawable +
        // inflated vector glyph for the selected side, per flipped row, per
        // crossed Hold & Slide cell. The payload path below rebinds ONLY the
        // selection chrome (card tint, stroke, chip/ glyph, chevron
        // visibility); the thumbnail still flips to the chip when selected —
        // that IS the selection visual — but nothing else on the row moves.
        // The chip background and the check/dash glyphs are created ONCE per
        // adapter and reused (constant tint; Widgets.icon already mutate()s),
        // so a flip costs zero allocations and zero inflations.
        val SelStatePayload = Any()
        val selChipBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dpf(8f)
            setColor(col(R.color.accent))
        }
        val selCheckIcon = Widgets.icon(ctx, R.drawable.ic_check, col(R.color.text_primary))
        val selDashIcon = Widgets.icon(ctx, R.drawable.ic_dash, col(R.color.text_primary))

        fun bindSelectionChrome(holder: RecyclerView.ViewHolder, rIn: Row) {
            // ── FULL FINAL PASS 2 (§2 — the LIVE model is the paint authority). ──
            // The Row snapshots exist for CHANGE DETECTION (which rows a
            // mutation touched → which notifyItemChanged calls to dispatch),
            // never for the paint itself. FULL FINAL PASS 1's drag fast path
            // updated the drag WORK list while the payload rebind read
            // `shown` — a list that only settles at gesture end — so every
            // Hold & Slide rebind painted the row's PRE-gesture state and the
            // gesture-end diff (work == live) dispatched nothing to correct
            // it: each gesture left every touched row's visuals exactly one
            // gesture behind its logical state (select showed nothing,
            // deselect showed selected, the stale NONE paint relaunching the
            // thumbnail job read as flicker). Deriving the state HERE, from
            // the same live reads the model's own verdicts use, makes the
            // paint correct no matter which path dispatched the rebind or
            // when it runs relative to any list snapshot update — one source
            // of truth, both bind paths, zero drift possible.
            val liveState = if (rIn.entry.isDir) folderState(rIn.entry.file) else fileState(rIn.entry.file)
            val r = if (liveState == rIn.state) rIn else Row(rIn.entry, liveState, rIn.pinned)
            val row = holder.itemView as LinearLayout
            val icon = row.getChildAt(0) as ImageView
            val chevron = row.getChildAt(2) as ImageView
            // Card + icon chip carry the three states; nothing about the
            // geometry changes.
            (row.background as? GradientDrawable)?.let { bg ->
                when (r.state) {
                    SelState.FULL -> {
                        bg.setColor((col(R.color.accent) and 0x00FFFFFF) or 0x26000000)
                        bg.setStroke(ctx.dp(1), (col(R.color.accent_light) and 0x00FFFFFF) or 0x66000000)
                    }
                    SelState.PARTIAL -> {
                        // The distinct in-between: same family, quieter fill
                        // + stroke than FULL, unmistakable beside it.
                        bg.setColor((col(R.color.accent) and 0x00FFFFFF) or 0x15000000)
                        bg.setStroke(ctx.dp(1), (col(R.color.accent_light) and 0x00FFFFFF) or 0x42000000)
                    }
                    SelState.NONE -> {
                        bg.setColor(col(R.color.bg_deep))
                        bg.setStroke(ctx.dp(1), 0x1FFFFFFF)
                    }
                }
            }
            when (r.state) {
                SelState.FULL -> icon.setImageDrawable(selCheckIcon)
                SelState.PARTIAL -> icon.setImageDrawable(selDashIcon)
                SelState.NONE -> {}
            }
            // ── FINAL PASS 2 (§6 — the selection indicator is AUTHORITATIVE): ──
            // a row bound while SELECTED must kill the thumbnail job its
            // previous unselected bind launched (and clear the tag the late
            // bitmap's guard reads), or a thumbnail that finishes loading
            // AFTER the selection lands overwrites the check/dash chip with
            // the plain picture. The cancel comes BEFORE the state branches'
            // effects so both directions are covered by one code path; the
            // NONE branch re-arms a fresh job below exactly as before.
            (holder as? RowHolder)?.thumbJob?.cancel()
            icon.tag = null
            if (r.state != SelState.NONE) {
                icon.background = selChipBg
                icon.setPadding(ctx.dp(6), ctx.dp(6), ctx.dp(7), ctx.dp(7))
            } else {
                icon.background = null
                icon.setPadding(0, 0, 0, 0)
                // ── Pass-36 Part 2 (#3): REAL thumbnails in the Files sheet ──
                // — the same shared engine the queue rows and the Media
                // picker use. On the payload path this relaunch is a warm
                // LRU hit (the bitmap was just showing a moment ago), so an
                // un-select costs one cache read, never a decode.
                icon.scaleType = ImageView.ScaleType.FIT_CENTER
                if (r.entry.isDir) {
                    icon.setImageDrawable(Widgets.icon(ctx, R.drawable.ic_folder, col(R.color.accent_light)))
                } else {
                    icon.setImageDrawable(FormatIcons.icon(ctx, r.entry.file.extension))
                    val category = ThumbnailEngine.categoryOf(r.entry.file.extension)
                    if (category == ThumbnailEngine.FileCategory.IMAGE ||
                        category == ThumbnailEngine.FileCategory.VIDEO ||
                        category == ThumbnailEngine.FileCategory.AUDIO ||
                        category == ThumbnailEngine.FileCategory.APK) {
                        val thumbTag = "fthumb:${r.entry.file.absolutePath}"
                        icon.tag = thumbTag
                        (holder as? RowHolder)?.thumbJob = scope.launch {
                            val bmp = try {
                                // ── GOAT-PASS 12 (PART 3.2/3.3): the SAME size ──
                                // Request as Received/History (40dp) → the SAME
                                // memory+disk cache entry: an existing XAPK is
                                // recognized and thumbnailed by filename via the
                                // one shared ThumbnailEngine pipeline, with no
                                // dependence on Received events or transfer
                                // metadata, and no second extraction pass that
                                // could diverge (the Pass-11 30dp key forced a
                                // fresh decode per surface and any failure left
                                // the format vector on Files only).
                                ThumbnailEngine.load(ctx, r.entry.file.absolutePath, category, sizeDp = 40)
                            } catch (e: Exception) { null }
                            if (bmp != null && icon.tag == thumbTag) {
                                // ── FINAL PASS 8 (PART 3): media thumbnails ──
                                // keep the edge-to-edge CENTER_CROP fill, but
                                // an APK launcher icon rides FIT_CENTER —
                                // its artwork is typically much smaller than
                                // the decoded tile with wide transparent
                                // margins, and cropping that wastes the icon
                                // exactly like stretching distorts it (the
                                // two halves of the black-corner/cropping
                                // artifact this pass removes). Aspect
                                // preserved, centered, transparent margins
                                // over the tile's own rounded backing.
                                icon.scaleType = if (category == ThumbnailEngine.FileCategory.APK)
                                    ImageView.ScaleType.FIT_CENTER
                                else ImageView.ScaleType.CENTER_CROP
                                icon.setImageBitmap(bmp)
                            }
                        }
                    }
                }
            }
            // GOAT-PASS 7 Part 2 (§2D): the folder chevron appears only while
            // that folder is actually selected — INVISIBLE (not GONE) keeps
            // the trailing slot, and the slot goes non-clickable with it.
            // A SEARCH RESULT's chevron is the always-visible LOCATE mark —
            // its visibility never rides the selection state.
            if (r.entry.searchContext != null) {
                chevron.visibility = View.VISIBLE
                chevron.isClickable = true
                chevron.isFocusable = true
            } else {
                val chevronLive = r.entry.isDir && r.state != SelState.NONE
                chevron.visibility = if (chevronLive) View.VISIBLE else View.INVISIBLE
                chevron.isClickable = chevronLive
                chevron.isFocusable = chevronLive
            }
        }

        override fun getItemViewType(position: Int) = if (rowsVisible()) TYPE_ROW else TYPE_STATE
        override fun getItemCount(): Int = if (rowsVisible()) shown.size else 1
        override fun getItemId(position: Int): Long =
            if (rowsVisible()) shown[position].entry.key.hashCode().toLong() else -1L
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_STATE) {
                val tv = TextView(parent.context).apply {
                    textSize = 12.5f; setTextColor(col(R.color.text_muted))
                    gravity = Gravity.CENTER; setPadding(0, ctx.dp(36), 0, ctx.dp(36))
                }
                return object : RecyclerView.ViewHolder(tv) {}
            }
            val c = parent.context
            // The CARD: full-width rounded container with its own vertical
            // breathing room (the gap between cards), an icon column sized
            // for recognition (not decoration), and a fixed control column.
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(c.dp(10), c.dp(9), c.dp(8), c.dp(9))
                isClickable = true; isFocusable = true
                isLongClickable = true   // hold-to-select / range selection
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = c.dpf(12f)
                    setColor(col(R.color.bg_deep))
                    setStroke(c.dp(1), 0x1FFFFFFF)
                }
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.bottomMargin = c.dp(6) }
            }
            // ── Pass-34 Part 2 (#1): the CHECK CIRCLE IS GONE. The icon ──────
            // slot is the selection target and the selection indicator in
            // one: the normal glyph when unselected; an accent chip with a
            // white check when FULL; an accent chip with a white dash when
            // PARTIAL. 30dp — bigger than a check circle, easier to land.
            // ── Pass-36 Part 2 (#3): the slot also hosts REAL thumbnails ──
            // now (images, videos, audio art, APK icons) — rounded to the
            // same 8dp radius the check chip uses, CENTER_CROP while a
            // bitmap is showing (fills the slot, never stretched) and
            // FIT_CENTER for the vector glyphs (they keep their padding).
            val icon = ImageView(c).apply {
                layoutParams = LinearLayout.LayoutParams(c.dp(30), c.dp(30)).also { it.marginEnd = c.dp(12) }
                isClickable = true; isFocusable = true
                scaleType = ImageView.ScaleType.FIT_CENTER
                clipToOutline = true
                outlineProvider = object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, c.dpf(8f))
                    }
                }
            }
            val texts = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val name = TextView(c).apply {
                textSize = 13.5f; setTextColor(col(R.color.text_primary)); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            }
            val meta = TextView(c).apply {
                textSize = 11f; setTextColor(col(R.color.text_secondary)); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            texts.addView(name); texts.addView(meta)
            val chevron = ImageView(c).apply {
                setImageDrawable(Widgets.icon(c, R.drawable.ic_chevron_right, col(R.color.text_muted)))
                // A full 38dp touch target — the dedicated folder-opening
                // affordance must be easy to land, every time.
                layoutParams = LinearLayout.LayoutParams(c.dp(38), c.dp(38))
                isClickable = true; isFocusable = true
                setPadding(c.dp(10), 0, c.dp(2), 0)
            }
            row.addView(icon); row.addView(texts); row.addView(chevron)
            return RowHolder(row)
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            // ── SELECTION FINAL PASS 1 (§2C): a selection-only payload rebind — ──
            // the flip touches nothing but the chrome, so the name, meta,
            // listeners and every other part of the row keep whatever the
            // last full bind left in place.
            if (payloads.contains(SelStatePayload) && rowsVisible()) {
                bindSelectionChrome(holder, shown[position])
                return
            }
            super.onBindViewHolder(holder, position, payloads)
            onBindViewHolder(holder, position)
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (!rowsVisible()) {
                (holder.itemView as TextView).text = when {
                    loading -> "Loading…"
                    searchIndexing -> "Indexing storage for search…"
                    searchActive -> "No results for \"${query.trim()}\""
                    query.isNotBlank() -> "No files match \"${query.trim()}\""
                    else -> "This folder is empty"
                }
                return
            }
            val r = shown[position]
            val entry = r.entry
            val row = holder.itemView as LinearLayout
            val icon = row.getChildAt(0) as ImageView
            val texts = row.getChildAt(1) as LinearLayout
            val name = texts.getChildAt(0) as TextView
            val meta = texts.getChildAt(1) as TextView
            val chevron = row.getChildAt(2) as ImageView

            // ── SELECTION FINAL PASS 1 (§2C): every selection visual lives in ──
            // ONE shared rebind (bindSelectionChrome above) — the full bind
            // calls it exactly like the payload path does, so a full rebind
            // and a selection flip can never drift apart.
            bindSelectionChrome(holder, r)
            // ── Pass-44 (§7): a pinned row says so in its own meta line — ──
            // quiet, textual, no layout change, and never on a search hit
            // (its context line is where the hit IS, not what it is).
            val pinTag = if (r.pinned) "Pinned · " else ""
            if (entry.isDir) {
                name.text = entry.file.name
                // ── Pass-36 (#7): a search hit's context line is WHERE it is ──
                // ("0/Music/Rock"), not what's inside it — that's the point of
                // a global search result.
                meta.text = entry.searchContext
                    ?: (pinTag + (if (entry.childCount > 0) "${entry.childCount} item${if (entry.childCount != 1) "s" else ""}" else "Folder"))
            } else {
                name.text = entry.file.name
                meta.text = entry.searchContext
                    ?: (pinTag + android.text.format.Formatter.formatShortFileSize(ctx, entry.size))
            }
            // ── Pass-37 (#3B) + GOAT-PASS 7 Part 2 (§2B): every search ──
            // result — file or folder — carries a dedicated LOCATE control
            // in the chevron slot: a minimal accent target mark (ic_locate —
            // deliberately NOT the chevron, whose established meaning in
            // this sheet is "open the selected folder") that opens the
            // item's containing directory AND scrolls to the exact item
            // inside it, files and folders alike. Normal rows keep the
            // classic chevron — shown ONLY while the folder is selected
            // (GOAT-PASS 7 §2D) — as the folder-opening affordance. The two
            // never conflict: the locate mark is its own 38dp touch target
            // at the row's end, entirely outside the main selection area.
            if (entry.searchContext != null) {
                chevron.visibility = View.VISIBLE
                chevron.setImageDrawable(Widgets.icon(ctx, R.drawable.ic_locate, col(R.color.accent_light)))
                chevron.contentDescription = "Open location"
                chevron.setOnClickListener { navigateSearchHitFn(entry) }
                chevron.setOnLongClickListener {
                    // The "tooltip": one plain sentence about what the control does.
                    Toast.makeText(ctx, "Opens the folder containing \"${entry.file.name}\"", Toast.LENGTH_SHORT).show()
                    true
                }
            } else {
                chevron.setImageDrawable(Widgets.icon(ctx, R.drawable.ic_chevron_right, col(R.color.text_muted)))
                chevron.contentDescription = null
                chevron.setOnClickListener { if (entry.isDir) navigateFn(entry.file) }
                chevron.setOnLongClickListener(null)
                // (GOAT-PASS 7 Part 2 §2D: the folder chevron's selection-
                // dependent visibility/clickability is owned by
                // bindSelectionChrome above, so the payload path flips it
                // together with the rest of the selection chrome.)
            }
            // #1/#6B: the icon TOGGLES selection (the only selection
            // affordance now); the row opens a folder when nothing is
            // selected and toggles once a selection exists; a file row always
            // toggles; the chevron ALWAYS opens, on its own touch target, and
            // never selects. Long-press selects/range-selects.
            // ── Pass-37 (#3A) → GOAT-PASS 7 Part 2 (§2C): a search result's ──
            // MAIN area performs the item's NORMAL action, exactly like a
            // browsing row of the same kind — a FILE opens through the
            // caller's open handler, a FOLDER navigates into it — never a
            // bare selection toggle (an ordinary click must not select or
            // deselect anything; selection stays on the icon slot, the
            // long-press and the hold-and-slide gesture, the same rules the
            // normal sheet follows). While a selection is already active the
            // body toggles, matching the browsing rows' selection-mode rule.
            icon.setOnClickListener { if (entry.isDir) toggleFolderFn(entry.file) else toggleFileFn(entry.file) }
            row.setOnClickListener {
                if (entry.searchContext != null) {
                    if (selectionMode()) {
                        if (entry.isDir) toggleFolderFn(entry.file) else toggleFileFn(entry.file)
                    } else if (entry.isDir) {
                        navigateFn(entry.file)   // the normal folder body action: open it
                    } else {
                        openFileFn(entry.file)   // the normal file body action: open it
                    }
                } else if (entry.isDir && !selectionMode()) navigateFn(entry.file)
                else if (entry.isDir) toggleFolderFn(entry.file)
                // ── Pass-41 (§7): a FILE's body OPENS the file — through the ──
                // caller's handler (the app's internal previewer for images,
                // videos and audio; the system open flow for everything else).
                // The ICON remains the one select affordance, and while a
                // selection is already active the body still toggles — the
                // existing selection rules apply unchanged. With no handler
                // wired (the legacy default) the body keeps selecting.
                else if (selectionMode()) toggleFileFn(entry.file)
                else openFileFn(entry.file)
            }
            row.setOnLongClickListener { longPressFn(r); true }
        }

        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            // ── Pass-36 Part 2 (#3): a recycled row's in-flight thumbnail ──
            // decode dies with it — its result could never become visible,
            // and the decode gate belongs to the rows that are on screen.
            (holder as? RowHolder)?.thumbJob?.cancel()
            // ── Pass-38 (#5): a recycled row surrenders any in-flight ──────
            // search-jump flash with the view — the recycler must never hand
            // a NEW item a row that still wears the old one's highlight.
            try { holder.itemView.overlay.clear() } catch (e: Exception) { /* gone */ }
        }
    }
    list.adapter = listAdapter

    // ── Pass-39 (#1): HOLD-AND-SLIDE selection — the exact gesture the Media ──
    // Picker and App Picker grids already use (one shared attachDragSelect),
    // adapted to this list's row model: press and hold a row, then slide
    // across the others to select (or deselect) them continuously, with edge
    // auto-scrolling for long directories. The gesture itself applies the
    // initiating row (GOAT-PASS 11 §12); Range mode is unchanged because
    // this sheet's rows keep their own long-press (the range-selection
    // anchor): the gesture arms on top of it and captures its ADD/REMOVE mode
    // from the row's state at DOWN. Every crossed row lands on the captured
    // state through setFileSelected/setFolderSelected — the same model
    // primitives the taps use, so counts, pills, coverage and exclusions all
    // behave identically; the terminal event is consumed, so no crossed row
    // can also open/navigate, and a plain tap keeps its single-toggle
    // meaning.
    //
    // ── SELECTION FINAL PASS 1 (§2D — the Hold & Slide FAST PATH): the ──
    // shared gesture used to route every crossed row through repaintStates()
    // — an O(list) live-state re-scan per crossed cell (plus the full chrome
    // pass), so a fast slide through a big directory paid rows-crossed ×
    // list-size state reads and could visibly fall behind the finger — the
    // exact "lags and skips items while selecting many" report. The drag
    // now applies each crossed row through the SET primitives and updates
    // EXACTLY that row's chrome (payload rebind) plus the footer counter,
    // and the ONE full reconciliation — the full repaint that catches any
    // covered-folder side effects the fast path deliberately does not chase —
    // runs a single time when the gesture ends (attachDragSelect's
    // onGestureEnd). Nothing about the interaction model changes: the same
    // primitives, the same visuals, the same terminal state.
    var dragRowsWork: MutableList<Row>? = null
    fun dragApplyRow(pos: Int, add: Boolean) {
        // Belt-and-braces: a work list whose size no longer matches `shown`
        // (a listing change between gestures — navigation cannot happen mid-
        // drag, but a dropped gesture-end post across a dismiss could in
        // principle leave one behind) is re-seeded, never trusted.
        val work = dragRowsWork?.takeIf { it.size == shown.size }
            ?: shown.toMutableList().also { dragRowsWork = it }
        val e = work.getOrNull(pos)?.entry ?: return
        if (e.isDir) setFolderSelected(e.file, add) else setFileSelected(e.file, add)
        val newState = if (e.isDir) folderState(e.file) else fileState(e.file)
        if (pos in work.indices) {
            work[pos] = Row(work[pos].entry, newState, work[pos].pinned)
            listAdapter.notifyItemChanged(pos, listAdapter.SelStatePayload)
        }
        paintChromeSlot()
    }
    fun dragGestureEnd() {
        // One full reconcile at gesture end: the work list's per-row snapshots
        // are exact for the rows the finger crossed, and the ordinary repaint
        // (fresh rows from the live entries + live states, DiffUtil against
        // the work list) settles anything an async writer did mid-gesture —
        // a directory-size walk landing, a live-sync refresh — the same
        // recomputed-from-truth discipline every other selection path uses.
        dragRowsWork?.let { work -> shown = work }
        dragRowsWork = null
        repaint()
    }

    com.lumidrop.x.ui.attachDragSelect(
        list,
        isSelectedAt = { pos ->
            val e = shown.getOrNull(pos)?.entry
            when {
                e == null -> false
                e.isDir -> folderState(e.file) != SelState.NONE
                else -> fileState(e.file) == SelState.FULL
            }
        },
        onApply = { pos, add ->
            // ── SELECTION FINAL PASS 1 (§2D): the fast path — the crossed ──
            // row's own chrome + the footer update NOW; the one full
            // reconciliation at gesture end (below). Same model primitives,
            // same visuals, same terminal state as before.
            dragApplyRow(pos, add)
        },
        // ── GOAT-PASS 7 (§3D): edge auto-scroll while the armed drag holds ──
        // the finger near the list's rim — speed by proximity, selection
        // continues under the stationary finger, stops with the gesture.
        edgeAutoScroll = true,
        // ── GOAT-PASS 8 (§4): the drag arms only in Hold & Slide mode; in ──
        // Range mode (the default) the row's own long-press owns everything
        // and this listener only guarantees the terminal-UP consumption.
        slideEnabled = { SettingsManager.isSlideSelection(ctx) },
        // ── SELECTION FINAL PASS 1 (§2D/§3): once per gesture — the full ──
        // surgical scan + chrome settle (and the safety net for anything an
        // async writer did mid-gesture).
        onGestureEnd = { dragGestureEnd() },
    )

    // ── Pass-38 (#5): the search-to-location highlight. When a search ─────────
    // result's arrow navigated to the item's directory, the reveal above
    // scrolled the EXACT item into view (matched by absolute path — never by
    // display name, so a same-named sibling can never steal the flash). This
    // is its visual landing: a subtle accent tint over the row card, held
    // ~500ms, then faded out smoothly. It rides the row's ViewOverlay —
    // drawn ABOVE the card, its selection state and its ripple, touching
    // none of them — and a recycled row clears it with the view (see the
    // overlay.clear() in onViewRecycled), so a stale flash can never ride a
    // recycled row onto a different item.
    fun revealSearchTarget(idx: Int, secondPulse: Boolean = false) {
        val vh = list.findViewHolderForAdapterPosition(idx) ?: return
        val row = vh.itemView
        if (row.width <= 0 || row.height <= 0) return
        // ── GOAT-PASS 7 Part 2 (§2A): the highlight is now unmistakable at a ──
        // glance — ~30% accent fill (was ~22%), held 800ms (was 500ms) before
        // a 450ms fade — while staying inside the row's own overlay so the
        // selection state, ripple and layout beneath are untouched.
        val startAlpha = 0x4D
        val flash = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dpf(12f)   // the row card's own radius
            setColor(col(R.color.accent) or 0xFF000000.toInt())
            alpha = startAlpha
        }
        flash.setBounds(0, 0, row.width, row.height)
        try { row.overlay.add(flash) } catch (e: Exception) { return }
        val fade = android.animation.ValueAnimator.ofInt(startAlpha, 0)
        fade.startDelay = 800
        fade.duration = 450
        fade.addUpdateListener { a -> flash.alpha = a.animatedValue as Int }
        fade.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                try { row.overlay.remove(flash) } catch (e: Exception) { /* row gone — recycle cleared it */ }
            }
        })
        fade.start()
        // ── GOAT-PASS 7 Part 2 (§2A): a second pulse for the search-locate ──
        // reveals — the first flash fires the very frame the freshly
        // scrolled viewport settles, and one re-flash 420ms in guarantees the
        // highlight is genuinely SEEN rather than lost against the motion.
        if (secondPulse) {
            row.postDelayed({ try { revealSearchTarget(idx, false) } catch (e: Exception) { /* row gone */ } }, 420)
        }
    }

    // ── List updates: DiffUtil with REAL state snapshots ─────────────────────
    fun submitVisible() {
        val newRows = if (loading) emptyList() else buildRows()
        // ── Pass-36 (#7): the search-hit reveal. A tapped file result
        // navigated to its parent (async listing) and selected itself; the
        // first submit carrying real rows scrolls it into view. One-shot —
        // a deleted-in-the-meantime hit simply never reveals.
        if (!loading) pendingRevealPath?.let { path ->
            val idx = newRows.indexOfFirst { it.entry.file.absolutePath == path }
            if (idx >= 0) {
                val comfortable = pendingRevealComfortable
                pendingRevealPath = null
                pendingRevealComfortable = false
                // ── GOAT-PASS 7 Part 2 (§2A): a reveal OWNS this listing's ──
                // scroll. The per-directory anchor restore (maybeRestoreDir-
                // Scroll, armed by navigate) must not post its remembered
                // position ON TOP of the reveal — in a previously-visited
                // directory that restore used to land after the reveal's
                // positioning and drag the list (and the just-highlighted
                // item) back to the old viewport, exactly the "navigated
                // but the item isn't visibly positioned" this pass fixes.
                pendingScrollRestore = null
                // Defer one frame so the diff has laid the rows out before
                // the scroll targets them; one frame more so the target row
                // itself is measured — then the search-jump highlight lands
                // (Pass-38 #5 + GOAT-PASS 7 §2A: the polished "which one was
                // it" answer — a comfortably-positioned, clearly-visible
                // target, files AND folders alike).
                list.post {
                    if (comfortable) {
                        // A comfortable position: the target about a third of
                        // the list down, snapped instantly (no fling fighting
                        // the reveal) — the exact rhythm the Music sheet's
                        // own track reveal uses.
                        (list.layoutManager as? LinearLayoutManager)
                            ?.scrollToPositionWithOffset(idx, (list.height * 0.33f).toInt())
                    } else {
                        list.scrollToPosition(idx)
                    }
                    list.post { revealSearchTarget(idx, secondPulse = comfortable) }
                }
            }
        }
        if (newRows.isEmpty() || shown.isEmpty()) {
            // Entering/leaving the state row (loading/empty/list) — a full
            // swap is the honest dispatch; this transition is rare.
            shown = newRows
            listAdapter.notifyDataSetChanged()
            return
        }
        val oldRows = shown
        // ── GOAT-PASS 9 (§10 — large-directory opening): for BIG lists the ──
        // O(N) DiffUtil pass (a string key compare per row pair, plus the
        // move detection sweep) costs more main-thread time than a full
        // notifyDataSetChanged, whose re-layout touches only the ~dozen
        // VISIBLE rows — the Camera-folder case (1,800+ files) is exactly
        // this shape. Above the threshold the swap is dispatched wholesale;
        // below it the incremental diff keeps its precise per-row updates
        // (child-count fills, sort changes, selection snapshots).
        if (oldRows.size >= 400 || newRows.size >= 400) {
            shown = newRows
            listAdapter.notifyDataSetChanged()
            return
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldRows.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = oldRows[oldPos].entry.key == newRows[newPos].entry.key
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                // SNAPSHOT states: a selection flip is a content change on
                // exactly that row — the live-map read the Part 1 diff used
                // could never see one.
                val a = oldRows[oldPos]; val b = newRows[newPos]
                // Pass-44 (§7): the pin state is part of the snapshot — a
                // pin toggle is a content change on exactly the affected
                // rows, never a full-list rebuild.
                return a.state == b.state && a.pinned == b.pinned &&
                    a.entry.size == b.entry.size && a.entry.mtime == b.entry.mtime &&
                    a.entry.childCount == b.entry.childCount
            }
        })
        shown = newRows
        diff.dispatchUpdatesTo(listAdapter)
    }

    // ── Breadcrumb ────────────────────────────────────────────────────────────
    fun rebuildBreadcrumb() {
        // ── SELECTION FINAL PASS 1 (§2B): a NO-OP unless the location (or its ──
        // root volume) genuinely changed. paintChrome runs on EVERY selection
        // mutation, and the old unconditional rebuild tore the crumb row down
        // and re-created every segment TextView (plus a posted scroll) on each
        // one — pure view churn while selecting, dozens of times over a single
        // Hold & Slide pass. The rebuild now keys on the breadcrumb's own
        // identity inputs: root + current directory; navigation and volume
        // switches still rebuild exactly as before, selection repaints skip
        // the whole thing.
        val identity = rootDir.absolutePath + "\u0000" + currentDir.absolutePath
        if (identity == lastBreadcrumbIdentity && crumbBox.childCount > 0) return
        lastBreadcrumbIdentity = identity
        crumbBox.removeAllViews()
        fun seg(text: String, target: File?, current: Boolean) {
            if (crumbBox.childCount > 0) crumbBox.addView(TextView(ctx).apply {
                this.text = "/"; textSize = 13f; setTextColor(col(R.color.text_muted))
                setPadding(ctx.dp(1), 0, ctx.dp(1), 0)
            })
            crumbBox.addView(TextView(ctx).apply {
                this.text = text; textSize = 13f
                setTextColor(col(if (current) R.color.text_primary else R.color.text_secondary))
                isClickable = target != null; isFocusable = target != null
                if (target != null) setOnClickListener { navigateFn(target) }
                setPadding(ctx.dp(3), ctx.dp(4), ctx.dp(3), ctx.dp(4))
            })
        }
        val rel = currentDir.absolutePath.removePrefix(rootDir.absolutePath).trim('/')
        seg("0", rootDir, rel.isEmpty())
        if (rel.isNotEmpty()) {
            val parts = rel.split('/')
            var acc = rootDir.absolutePath
            parts.forEachIndexed { i, part ->
                acc = "$acc/$part"
                seg(part, File(acc), i == parts.lastIndex)
            }
        }
        // Keep the deepest (current) segment in view on deep paths.
        crumbScroller.post { crumbScroller.fullScroll(View.FOCUS_RIGHT) }
    }

    // ── Behavior, now that every view exists ──────────────────────────────────
    /** ── FINAL PASS 2 (§5 — the paste bar never covers the last item): the ──────
     *  floating paste/op bars are bottom-pinned overlays on the list's own
     *  container, so whatever they cover is unreachable — the last row sat
     *  beneath the bar with no way to scroll it clear. The list's bottom
     *  padding now grows to the bar's real height (+ margins) whenever one
     *  is visible and returns to the resting 10dp when neither is: with
     *  clipToPadding already false, the content scrolls cleanly clear of the
     *  bar and the final item stays fully visible AND clickable. Measured
     *  live (with a generous pre-layout fallback for the frame a bar just
     *  became visible on), so no hard-coded height can drift from the real
     *  bar, and no blank space is reserved when nothing is showing. */
    fun updateListBottomPad() {
        val basePad = ctx.dp(10)
        val barActive = pasteFloatBar.visibility == View.VISIBLE || opBar.visibility == View.VISIBLE
        val target = if (barActive) {
            val barH = maxOf(
                if (pasteFloatBar.visibility == View.VISIBLE) pasteFloatBar.height else 0,
                if (opBar.visibility == View.VISIBLE) opBar.height else 0)
            if (barH > 0) basePad + ctx.dp(10) + barH else basePad + ctx.dp(84)   // pre-layout fallback: bar ≈ 56dp + 10dp margin
        } else basePad
        val oldPad = list.paddingBottom
        if (oldPad != target) {
            list.setPadding(list.paddingLeft, list.paddingTop, list.paddingRight, target)
            // ── FULL FINAL PASS 2 (§5 — the bottom stays BOTTOM-anchored): the ──
            // padding alone made the last row REACHABLE (clipToPadding=false
            // lets content scroll clear of the bar), but a list that was
            // already scrolled to its end kept the last row visually UNDER
            // the freshly-appeared bar until the user scrolled again — the
            // residual "the paste bar covers the last item". When the pad
            // GROWS and the list is at (or within one row of) its bottom,
            // the content is scrolled up by exactly the delta so the bottom
            // row lands clear of the bar immediately. A shrinking pad needs
            // no compensation — the freed space is simply scrollable room.
            val delta = target - oldPad
            if (delta > 0) {
                val lm = list.layoutManager as? LinearLayoutManager
                if (lm != null) {
                    val lastVisible = lm.findLastCompletelyVisibleItemPosition()
                    val lastIdx = listAdapter.itemCount - 1
                    if (lastVisible >= 0 && lastIdx >= 0 && lastVisible >= lastIdx - 1) {
                        try { list.scrollBy(0, delta) } catch (e: Exception) { /* layout not ready — the pad still guarantees reachability */ }
                    }
                }
            }
        }
        // The height is 0 on the very frame a bar flips visible — re-run once
        // laid out so the padding lands on the real measurement.
        if (barActive) list.post { updateListBottomPad() }
    }

    fun paintChrome() {
        // Pass-35 (#3): the counter subtitle row is gone; the back icon
        // appears exactly when there IS a parent to go back to.
        navBackBtn.visibility = if (currentDir.absolutePath != rootDir.absolutePath) View.VISIBLE else View.INVISIBLE
        val selFolders = folderPicks.size
        val selFiles = filePicks.size
        // ── Pass-36 (#3A) + Pass-39 (extra 2): the compact count pill — totals ──
        // first, size when it exists, and a trailing "…" while any selected
        // folder's recursive size walk is still running (the accurate figure
        // replaces it the moment the walk lands — folders now count fully,
        // not just standalone files).
        val (bytes, sizePending) = selectedBytesDetailed()
        footerInfo.text = buildString {
            append("${selFolders + selFiles} selected")
            if (bytes > 0 || sizePending) append(" · ${android.text.format.Formatter.formatShortFileSize(ctx, bytes)}")
            if (sizePending) append("…")
        }
        // ── Pass-44 (§7/§8): with nothing selected, an armed clipboard owns ──
        // the counter line (its paste hint is the selection's successor);
        // the storage switcher shows only while a readable volume exists.
        if (selFolders + selFiles == 0) {
            clipboard?.let { cb ->
                footerInfo.text = "${cb.picks.size} ${if (cb.isCut) "cut" else "copied"} — tap ⋮ → Paste in a destination folder"
            }
        }
        storageBtn.visibility = if (volumesCache.isNotEmpty()) View.VISIBLE else View.GONE
        // ── GOAT-PASS 14 Phase 2 (§5): the affirmative segment's word tracks the ──
        // LIVE Auto Send setting on every chrome repaint (paintChrome is this
        // sheet's single selection/refresh writer — every toggle, submit and
        // navigation funnels through it), so the button says exactly what the
        // same tap does under the current setting: "Add" to the queue, or
        // "Send" immediately. Label only; the action, geometry and enabled
        // state are untouched.
        actionPill.setAffirmativeLabel(quickShareActionVerb(ctx))
        actionPill.setAddEnabled(selectionMode())
        // ── GOAT-PASS 8 (§7): the floating PASTE/CANCEL bar's visibility and ──
        // label — exactly while a clipboard is armed and no engine operation
        // is in flight (the opBar owns the same bottom slot while it runs).
        // The label carries the real armed count and verb, so the pill can
        // never misrepresent what it will do.
        val cbLive = clipboard
        pasteFloatBar.visibility = if (cbLive != null && !FileOps.running) View.VISIBLE else View.GONE
        if (cbLive != null) {
            pasteFloatPasteBtn.text = "Paste ${cbLive.picks.size} item${if (cbLive.picks.size != 1) "s" else ""}"
        }
        updateListBottomPad()
        rebuildBreadcrumb()
    }

    fun submitAll() { submitVisible(); paintChrome() }

    // ── SELECTION FINAL PASS 1 (§2D): the chrome slot points at the real ──
    // paintChrome from here on (declared above — the slot exists only so the
    // earlier-declared drag fast path can reach it).
    paintChromeSlot = { paintChrome() }

    /** ── GOAT-PASS 8 (§9 — the surgical selection repaint): re-reads every ────
     *  visible row's LIVE state and re-binds ONLY the rows whose SelState
     *  actually changed — no re-sort (the cached order), no full Row rebuild,
     *  no DiffUtil pass. Recompute-from-truth by design: the caller does not
     *  have to enumerate which rows a mutation touched (a covered file's
     *  toggle, a folder pick's absorption — all of it falls out of the state
     *  reads). A bulk change (more than [SURGICAL_REPAINT_MAX] rows) falls
     *  back to the ordinary full diff path — at that scale the diff's own
     *  batching is cheaper than N notifyItemChanged dispatches. */
    fun repaintStates() {
        if (loading || shown.isEmpty()) { submitVisible(); paintChrome(); return }
        val newList = shown.toMutableList()
        var changed = 0
        for (i in newList.indices) {
            val r = newList[i]
            val newState = if (r.entry.isDir) folderState(r.entry.file) else fileState(r.entry.file)
            if (newState != r.state) {
                newList[i] = Row(r.entry, newState, r.pinned)
                // ── SELECTION FINAL PASS 1 (§2C): payload dispatch — only the ──
                // selection chrome rebinds (no name/meta rewrites, no listener
                // re-wiring, no fresh drawables), so a scan that flips many
                // rows stays proportionally cheap.
                listAdapter.notifyItemChanged(i, listAdapter.SelStatePayload)
                changed++
                if (changed > SURGICAL_REPAINT_MAX) {
                    submitVisible()   // bulk change — the diff path batches better
                    paintChrome()
                    return
                }
            }
        }
        if (changed == 0) {
            // Nothing on screen moved — the footer counter still refreshes
            // (a covered pick's byte total can change without a visible state
            // flip).
            paintChrome()
            return
        }
        shown = newList
        paintChrome()
    }

    /** GOAT-PASS 3 (§1): fills the deferred per-directory child counts for
     *  [entries] on a background dispatcher and refreshes the list ONCE at
     *  the end. Bounded to the entries the listing actually shows; a
     *  superseded navigation (a newer listing landed) is detected via
     *  [serial] and the pass simply stops. (Declared after submitAll —
     *  Kotlin local functions resolve lexically.) */
    fun fillChildCounts(entries: List<Entry>, serial: Int) {
        val dirs = entries.filter { it.isDir && it.childCount == 0 }
        if (dirs.isEmpty()) return
        scope.launch {
            // The per-directory listFiles() calls are REAL I/O — they belong
            // on Dispatchers.IO, never the main thread (the sheet's scope is
            // Main-dispatched; the whole point of this pass is that the
            // counts never delay or jank anything the user can see).
            withContext(Dispatchers.IO) {
                for (d in dirs) {
                    if (serial != loadSerial.get()) return@withContext   // a newer listing superseded this pass
                    d.childCount = try { d.file.listFiles()?.size ?: 0 } catch (e: Exception) { 0 }
                }
            }
            if (serial == loadSerial.get()) submitAll()
        }
    }

    /** Hold-to-select and RANGE selection (#2): with a valid anchor in the
     *  visible list, a long-press selects the whole inclusive span between
     *  anchor and target (current visible+sorted order; hidden/filtered rows
     *  are simply not in `shown`); without one it selects just this row and
     *  becomes the anchor. Additive — unrelated picks are preserved.
     *
     *  ── GOAT-PASS 8 (§3 — the ghost-anchor guard): the anchor is additionally
     *  validated against the LIVE selection state — an anchor that is no
     *  longer selected (deselected through a path that didn't clear it — a
     *  covering exclusion, a prune after a filesystem change) is treated as
     *  no anchor at all, so a fresh selection session can never begin a
     *  range from an item selected in a PREVIOUS session.
     *
     *  ── GOAT-PASS 8 (§4 — Hold & Slide mode): when the selection-style
     *  setting is "slide", the long-press applies only the initiating
     *  TOGGLE (an unselected row selects, a selected row deselects — the
     *  mode the armed drag captured at DOWN) and never extends a range;
     *  the drag gesture owns everything the finger crosses afterwards. */
    fun longPress(target: Row) {
        val slideMode = SettingsManager.isSlideSelection(ctx)
        val anchor = anchorKey
        // ── GOAT-PASS 8 (§3 — the ghost-anchor guard): the anchor must still ──
        // exist in the visible list AND still be SELECTED (its LIVE state, not
        // the last snapshot) — only then may a range extend from it.
        val anchorPos = if (anchor != null) shown.indexOfFirst { it.entry.key == anchor } else -1
        val anchorValid = anchorPos >= 0 && shown[anchorPos].let { row ->
            if (row.entry.isDir) folderState(row.entry.file) != SelState.NONE
            else fileState(row.entry.file) != SelState.NONE
        }
        val targetPos = shown.indexOfFirst { it.entry.key == target.entry.key }
        if (!anchorValid || targetPos < 0) {
            // No usable anchor (never selected, no longer selected, or it
            // left the list through navigation/search/sort): select THIS row
            // and anchor.
            if (slideMode) {
                // Hold & Slide: the initiating press toggles, matching the
                // drag pass mode captured at DOWN.
                if (target.entry.isDir) setFolderSelected(target.entry.file, folderState(target.entry.file) == SelState.NONE)
                else setFileSelected(target.entry.file, fileState(target.entry.file) == SelState.NONE)
            } else {
                if (target.entry.isDir) selectFolderOn(target.entry.file) else selectFileOn(target.entry.file)
            }
            anchorKey = target.entry.key
            repaint()
            return
        }
        if (slideMode) {
            // Hold & Slide mode never ranges — the press still toggles the
            // initiating row and re-anchors (harmless bookkeeping for a
            // later switch back to Range mode).
            if (target.entry.isDir) setFolderSelected(target.entry.file, folderState(target.entry.file) == SelState.NONE)
            else setFileSelected(target.entry.file, fileState(target.entry.file) == SelState.NONE)
            anchorKey = target.entry.key
            repaint()
            return
        }
        val from = minOf(anchorPos, targetPos)
        val to = maxOf(anchorPos, targetPos)
        for (i in from..to) {
            val e = shown[i].entry
            if (e.isDir) selectFolderOn(e.file) else selectFileOn(e.file)
        }
        anchorKey = target.entry.key
        repaint()
    }

    /** The partial-folder manifest (#3D): every still-selected descendant of
     *  [pick]'s root, as relPaths that preserve the folder's own hierarchy.
     *  Runs on IO; an exclusion prunes that path's whole subtree.
     *  ── Pass-36 Part 2 (#4): hidden descendants are SKIPPED unless the ──
     *  picker's hidden mode is ON — the manifest (which fixes the folder's
     *  contents at staging time) can never silently include an invisible
     *  hidden file or subfolder just because its parent was selected. When
     *  the hidden mode IS on, hidden rows were visible and eligible, so they
     *  stage like any other row. */
    fun buildManifestFor(pick: FolderPick, includeHidden: Boolean): List<PreviewItem.FolderChild> {
        val out = ArrayList<PreviewItem.FolderChild>()
        fun walk(dir: File, rel: String) {
            val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: return
            for (f in kids) {
                if (!includeHidden && isHiddenName(f)) continue   // Pass-36 Part 2 (#4)
                val abs = f.absolutePath
                if (abs in pick.excluded) continue
                if (pick.excluded.any { abs.startsWith("$it/") }) continue   // pruned subtree
                val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
                if (f.isDirectory) walk(f, r)
                else out.add(PreviewItem.FolderChild(
                    relPath = r,
                    path = abs,
                    uri = null,
                    size = try { f.length() } catch (e: Exception) { 0L },
                    mime = MimeTypes.getMimeType(f.extension),
                ))
            }
        }
        walk(pick.root, "")
        return out
    }

    // ── Pass-42 (§6): the scroll-anchor half of the per-directory memory. ────
    // snapshotDirScroll records the OUTGOING directory's first-visible row
    // (by stable entry key + anchor offset + adapter position); maybeRestore-
    // DirScroll re-anchors the INCOMING directory's freshly-submitted list to
    // its remembered row. Key-first resolution means an inserted/deleted item
    // above the anchor shifts the restore WITH the content ("the same visible
    // items, approximate viewport position"); when the remembered item itself
    // is gone (the directory genuinely changed while away), the remembered
    // adapter position — clamped to the fresh length — is the closest valid
    // position. A reveal request (a search hit's open-location jump) always
    // owns the scroll; the anchor step yields to it.
    // ── Pass-43 (§5 — the drift fix): the stored offset now uses ────────────
    // LinearLayoutManager's OWN anchor coordinate — view.top minus the
    // layout's start-after-padding (exactly what AnchorInfo.assignFromView
    // stores, and exactly what scrollToPositionWithOffset consumes), so
    // save→restore is a bit-exact round trip. The first cut stored the
    // NEGATED top: every restore landed one row-fraction off in the same
    // direction, and each following cycle snapshotted the slightly-moved
    // result again — the reported "position is almost restored, but it
    // shifts a little further upward every time I go into a folder and
    // come back". With the anchor's own coordinate, N repeated cycles
    // accumulate ZERO pixels: the same rows land at the same offsets every
    // time, independent of the list's padding/clipToPadding configuration.
    fun snapshotDirScroll(dir: File) {
        try {
            val lm = list.layoutManager as? LinearLayoutManager ?: return
            val pos = lm.findFirstVisibleItemPosition()
            if (pos < 0) return
            val view = lm.findViewByPosition(pos) ?: return
            val startAfterPadding = if (list.clipToPadding) list.paddingTop else 0
            val off = view.top - startAfterPadding
            val key = shown.getOrNull(pos)?.entry?.key ?: return
            dirScrolls[dir.absolutePath] = Triple(key, off, pos)
        } catch (e: Exception) { /* a snapshot is best-effort — never block navigation */ }
    }

    fun maybeRestoreDirScroll() {
        val dirPath = pendingScrollRestore ?: return
        if (dirPath != currentDir.absolutePath) { pendingScrollRestore = null; return }
        pendingScrollRestore = null
        if (pendingRevealPath != null) return          // a reveal owns this scroll
        val saved = dirScrolls[dirPath] ?: return      // first visit — top, as always
        list.post {
            try {
                if (loading || shown.isEmpty()) return@post
                var idx = -1
                for (i in shown.indices) if (shown[i].entry.key == saved.first) { idx = i; break }
                if (idx < 0) idx = saved.third          // content changed — closest valid position
                (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(
                    idx.coerceIn(0, shown.size - 1), saved.second)
            } catch (e: Exception) { /* restore is best-effort */ }
        }
    }

    /** Restores [dir]'s remembered sorting (or the name-ascending default
     *  when it has none). Round-trips exactly with [currentSortValue]. */
    fun applyStoredSort(dir: File) {
        val stored = SettingsManager.getFilesDirSort(ctx, dir.absolutePath)
        val k = stored?.substringBefore(':')
        sortKey = when (k) {
            "date" -> FilesSortKey.DATE
            "size" -> FilesSortKey.SIZE
            "type" -> FilesSortKey.TYPE
            else -> FilesSortKey.NAME
        }
        sortDesc = stored != null && stored.substringAfter(':', "a") == "d"
    }

    // ── GOAT-PASS 9 (§5 — live synchronization): the OPEN sheet's change ─────
    // detector. The LibrarySync hook (registered below, after the sheet is
    // up) compares the CURRENT directory's mtime against the value recorded
    // here at every completed listing — one cheap stat() decides whether an
    // external change (any app, any observer stream) touched the directory
    // the user is actually looking at; anything else costs nothing. A
    // directory's mtime changes exactly when its direct children are
    // created/deleted/renamed, which is precisely what this listing shows.
    var lastSyncDirMtime = -1L
    var lastSyncDirPath: String? = null
    fun noteDirMtimeForSync(d: File) {
        lastSyncDirPath = d.absolutePath
        lastSyncDirMtime = try { d.lastModified() } catch (e: Exception) { -1L }
    }

    fun load() {
        // ── Pass-44 (§8): keep the switcher honest on every navigation ──
        // (a card pulled mid-browse disappears from the list right away).
        refreshVolumes()
        // ── Pass-44 (§8): the browsed volume can vanish underneath the ──
        // user (card pulled, unmounted, permission revoked). Honest
        // recovery, never a silent empty listing of a dead path: say what
        // happened and return to internal storage at its root.
        val dirAlive = try { currentDir.isDirectory && currentDir.canRead() } catch (e: Exception) { false }
        if (!dirAlive) {
            Toast.makeText(ctx, "That storage is no longer available — back to internal storage", Toast.LENGTH_LONG).show()
            rootDir = ExternalVolumes.internalRoot()
            currentDir = rootDir
            pendingScrollRestore = null
            applyStoredSort(rootDir)
        }
        val dir = currentDir
        val serial = loadSerial.incrementAndGet()
        FilesSheetCache.get(dir.absolutePath)?.let { cached ->
            entries = cached; loading = false; submitAll(); maybeRestoreDirScroll(); noteDirMtimeForSync(dir); return
        }
        loading = true; submitAll()
        scope.launch {
            val listed = withContext(Dispatchers.IO) { listDir(dir) }
            if (serial != loadSerial.get()) return@launch   // a newer navigation superseded this listing
            FilesSheetCache.put(dir.absolutePath, listed)
            entries = listed; loading = false
            submitAll()
            maybeRestoreDirScroll()
            noteDirMtimeForSync(dir)
            // ── GOAT-PASS 3 (§1): the directory is on screen NOW; the
            // per-folder item counts land as a quiet background update.
            fillChildCounts(listed, serial)
        }
    }

    // ══ Pass-40 (#1): the PER-DIRECTORY persistent sorting system ═══════════
    //
    // A Windows-style contract: every directory remembers its own criterion
    // and direction (SharedPreferences, surviving refreshes, navigation,
    // sheet reopenings and app restarts); changing one directory's order
    // never touches another's; and "Apply to all subfolders" stamps the
    // CURRENT configuration across the current directory's whole subtree —
    // recursively, efficiently off the UI thread, and writing nothing but
    // display-order preferences (no file is ever moved, renamed or touched).
    // Sorting itself always runs over the LIVE listing at render time
    // (visibleEntries), so added/removed/renamed files and refreshed
    // listings stay correctly ordered under the remembered rule; identities
    // stay the rows' absolute paths, so no sort change can ever cost a
    // selection or duplicate a row.

    /** The stored "key:dir" string for the CURRENT configuration. */
    fun currentSortValue(): String = "${sortKey.name.lowercase()}:${if (sortDesc) "d" else "a"}"

    
    fun navigate(dir: File) {
        if (!dir.isDirectory) return
        // ── Pass-42 (§6): remember the OUTGOING directory's viewport (the
        // same instant, key-based snapshot at every level of nesting), and
        // arm the INCOMING directory's restore — load()'s completed listing
        // re-anchors it to whatever the user was last looking at there.
        // Nothing about the sort/selection/navigation behavior below changes.
        snapshotDirScroll(currentDir)
        pendingScrollRestore = dir.absolutePath
        currentDir = dir
        // ── GOAT-PASS 5 (§5): the directory the user ACTUALLY browsed is the
        // one the sheet reopens on (when Remember Last Opened Directory is
        // enabled — the default). Only a genuine navigate() ever writes the
        // preference: a search-results view never does (it is a temporary
        // scope, not a browsing location), and neither does the internal
        // not-alive fallback above (which is recovery, not navigation).
        try {
            if (SettingsManager.isRememberLastDir(ctx))
                SettingsManager.setLastFilesDir(ctx, dir.absolutePath)
        } catch (e: Exception) { /* preference-only — never block navigation */ }
        // ── Pass-40 (#1): the directory's OWN remembered sorting — each
        // directory restores exactly what was left there, independently of
        // every other directory; an unconfigured one keeps the Windows-style
        // default (name, ascending).
        applyStoredSort(dir)
        query = ""
        // Pass-36 (#7): leaving the results view — the search state goes with
        // it (the next query rebuilds it fresh).
        searchActive = false; searchIndexing = false; searchHits = emptyList()
        // ── Pass-38: the box's clear is PROGRAMMATIC — suppressed so the ──
        // change watcher neither double-submits (navigate() resets everything
        // and load() re-renders right here) nor, worse, drops the Pass-37
        // (#3C) restore snapshot that navigateToSearchHit captured for Back
        // RIGHT BEFORE this call runs.
        programmaticSearchText = true
        try { searchBox.setText("") } finally { programmaticSearchText = false }
        anchorKey = null   // a different scope invalidates the range anchor
        load()
    }

    /** ── Pass-40 (#1): the recursive apply. Walks DIRECTORIES ONLY (files are ──
     *  irrelevant — the store is per-directory) on the IO dispatcher, bounded
     *  by the same 25,000-entry / 60,000-queue discipline as the search
     *  engine so a pathological tree cannot run away; hidden directories are
     *  skipped unless hidden mode is on (the same eligibility rule every
     *  other walker here follows). The CURRENT directory's preference lands
     *  synchronously first (it is the one on screen), the whole subtree then
     *  lands in ONE bulk commit, and the toast reports the real count. The
     *  walk starts AT the current directory — nothing outside it is ever
     *  written, and nothing on disk is ever modified. */
    fun applySortToAllBelow() {
        val value = currentSortValue()
        val root = currentDir
        SettingsManager.setFilesDirSort(ctx, root.absolutePath, value)
        scope.launch {
            val dirs = withContext(Dispatchers.IO) {
                val out = ArrayList<String>(64)
                val queue = ArrayDeque<File>()
                var queued = 0
                queue.add(root); queued++
                while (queue.isNotEmpty() && out.size < 25_000 && queued < 60_000) {
                    val dir = queue.removeFirst()
                    out.add(dir.absolutePath)
                    val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
                    for (c in kids) {
                        if (out.size >= 25_000 || queued >= 60_000) break
                        val isDir = try { c.isDirectory } catch (e: Exception) { false }
                        if (!isDir) continue
                        if (!showHidden && c.name.startsWith(".")) continue
                        queue.add(c); queued++
                    }
                }
                out
            }
            val bulk = HashMap<String, String>(dirs.size * 2)
            for (p in dirs) bulk[p] = value
            SettingsManager.setFilesDirSortBulk(ctx, bulk)
            withContext(Dispatchers.Main) {
                Toast.makeText(ctx,
                    "Sorting applied to ${dirs.size} folder${if (dirs.size != 1) "s" else ""}",
                    Toast.LENGTH_SHORT).show()
            }
        }
    }
    /** ── Pass-40 (#1): Apply-to-all's confirmation — one brief dialog in the ──
     *  app's existing modal language, because this action writes a preference
     *  into every subdirectory of the current folder at once and deserves a
     *  plain-language explanation before it does. */
    fun confirmApplySortToAll() {
        showLdxDialog(
            context = ctx,
            title = "Apply this sorting to all subfolders?",
            message = "\"${sortKey.label} — ${if (sortDesc) "Descending" else "Ascending"}\" will be remembered for " +
                "\"${currentDir.name.ifBlank { currentDir.absolutePath }}\" and every folder inside it. " +
                "Files are never moved or changed.",
            iconRes = R.drawable.ic_sort,
            iconTintRes = R.color.accent_light,
            actions = listOf(
                LdxDialogAction("Cancel", LdxButtonStyle.GHOST),
                LdxDialogAction("Apply", LdxButtonStyle.PRIMARY) { applySortToAllBelow() },
            ),
        )
    }

    /** ── Pass-37 (#3B/#3C) + GOAT-PASS 7 Part 2 (§2A): the search result's ──
     * dedicated LOCATE control — opens the item's containing directory
     * (scrolling to the EXACT item inside it, files and folders alike)
     * while capturing the EXACT search screen for Back: query, ranked
     * results, scroll position, anchor and the selection-interface state
     * (selections themselves are model state and were never lost). Back
     * from that directory replays the snapshot VERBATIM — no re-query, no
     * re-rank, no reset; the person is exactly where they were.
     * ── GOAT-PASS 7 (§2A): the keyboard closes IMMEDIATELY on the locate ──
     * tap — the IME is hidden and the field's focus released synchronously,
     * BEFORE the navigation runs, so the directory listing never renders
     * under a keyboard that is already on its way out. */
    fun navigateToSearchHit(e: Entry) {
        val parent = e.file.parentFile ?: return
        try {
            val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(searchBox.windowToken, 0)
        } catch (ex: Exception) { /* the IME service is best-effort — navigation never waits on it */ }
        try { searchBox.clearFocus() } catch (ex: Exception) { /* field already gone */ }
        val savedQuery = query
        val savedHits = searchHits
        val savedIndexing = searchIndexing
        val savedAnchor = anchorKey
        val lm = list.layoutManager as? LinearLayoutManager
        val savedScroll = lm?.findFirstCompletelyVisibleItemPosition()?.takeIf { it >= 0 } ?: 0
        searchRestoreDir = parent
        searchRestore = {
            programmaticSearchText = true
            try {
                searchActive = true
                searchIndexing = savedIndexing
                searchHits = savedHits          // the SAME list: order and ranking preserved, zero re-search
                query = savedQuery
                searchBox.setText(savedQuery)   // suppressed — no watcher relaunch
                searchBox.setSelection(savedQuery.length)
            } finally {
                programmaticSearchText = false
            }
            anchorKey = savedAnchor
            entries = emptyList()               // the results view renders searchHits, not a directory listing
            loading = false
            submitVisible()
            // The rows re-exist after the diff lands — restore the scroll in
            // the next frame, clamped to the (unchanged) list length.
            list.post {
                val target = savedScroll.coerceIn(0, (shown.size - 1).coerceAtLeast(0))
                (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(target, 0)
            }
            repaint()
        }
        pendingRevealPath = e.file.absolutePath   // scroll to the item inside its directory
        pendingRevealComfortable = true           // GOAT-PASS 7 (§2A): position it in the viewport's upper third, never at the raw edge
        navigate(parent)
    }

    repaint = { submitVisible(); paintChrome() }
    repaintSelection = { repaintStates() }

    toggleFileFn = { f -> toggleFile(f) }
    toggleFolderFn = { g -> toggleFolder(g) }
    navigateFn = { f -> navigate(f) }
    // ── Pass-41 (§7): the body-tap open handler — the caller's when wired,
    // the legacy select when not (the slot's default keeps this exact branch
    // identical to the old body behavior).
    openFileFn = onOpenFile ?: { f -> toggleFile(f) }
    openSearchFileFn = { _ -> }   // Pass-37 #3A: search-result clicks select; navigation is the arrow's job
    navigateSearchHitFn = { e -> navigateToSearchHit(e) }   // Pass-37 (#3B)
    longPressFn = { r -> longPress(r) }

    // ══ Pass-44 (§7): the file-management machinery behind the ⋮ menu ═════════
    //
    // Selection → clipboard: Copy/Cut snapshot the CURRENT picks (a folder
    // pick with exclusions pastes exactly the still-selected subtree) and
    // then clear the selection — the items are marked, the counter hands
    // its line to the paste hint, and the user navigates to a destination.
    // Paste completes into the CURRENT directory (never overwriting —
    // collisions land as "name (1).ext"), with byte-accurate progress and
    // a Cancel that stops the ENGINE, not just the bar: a cancelled copy
    // deletes its partial file, and a move only deletes a source file
    // after its copy fully landed.
    fun selectionPicks(): List<FileOps.Pick> {
        val out = ArrayList<FileOps.Pick>(folderPicks.size + filePicks.size)
        for (p in folderPicks.values) out.add(FileOps.Pick(p.root, HashSet(p.excluded)))
        for (f in filePicks.values) out.add(FileOps.Pick(f, emptySet()))
        return out
    }

    fun armClipboard(isCut: Boolean) {
        val picks = selectionPicks().filter { runCatching { it.root.exists() }.getOrDefault(false) }
        if (picks.isEmpty()) { Toast.makeText(ctx, "Nothing selected", Toast.LENGTH_SHORT).show(); return }
        clipboard = SheetClipboard(isCut, picks)
        folderPicks.clear(); filePicks.clear(); anchorKey = null
        submitAll()
        Toast.makeText(ctx,
            "${picks.size} item${if (picks.size != 1) "s" else ""} ${if (isCut) "cut" else "copied"} — open a destination folder and tap ⋮ → Paste",
            Toast.LENGTH_SHORT).show()
    }

    fun togglePins() {
        val paths = ArrayList<String>(folderPicks.keys) + ArrayList(filePicks.keys)
        if (paths.isEmpty()) return
        val allPinned = paths.all { pinnedPaths.contains(it) }
        if (allPinned) pinnedPaths.removeAll(paths.toHashSet()) else pinnedPaths.addAll(paths)
        SettingsManager.setPinnedPaths(ctx, pinnedPaths)
        submitAll()   // diff: the pinned snapshot + order change — scroll keeps
    }

    /** Refresh the listing after a real filesystem change (paste / delete /
     *  rename) without a scroll reset: the outgoing viewport is snapshotted
     *  and the reload re-anchors it by the same Pass-42 key machinery a
     *  folder round-trip uses. Selections referencing vanished paths are
     *  pruned; every affected directory's cached listing dies. */
    fun afterFsChange(sourceParents: List<File?> = emptyList()) {
        // GOAT-PASS 3 (§1): invalidation goes through the process-level
        // cache — path + descendants, so a renamed/deleted subtree can never
        // surface again from any cached listing.
        // ── GOAT-PASS 8 (§10 — the folder-count refresh, root fix): the ──
        // invalidation set now covers THE PARENT of every changed directory
        // too. A directory's own row — with its childCount — lives in its
        // PARENT's cached listing, so a paste into /a/b (currentDir = b)
        // previously invalidated only b: navigating back to a re-served a's
        // STALE cached copy and b kept reading "Folder" (the reported repro
        // — the folder shows the pasted file when opened, but the parent
        // still calls it empty). Every changed directory — the destination,
        // every cut source, a rename's target — now takes its parent's cache
        // entry with it, so the count is recomputed from the real disk on the
        // next visit.
        val changed = ArrayList<File?>(sourceParents.size + 2)
        changed.add(currentDir)
        changed.addAll(sourceParents)
        for (d in changed) {
            d?.let { dir ->
                FilesSheetCache.invalidate(dir.absolutePath)
                // §10: the changed directory's OWN row (name + childCount)
                // lives in its parent's cached listing — that entry is stale
                // the moment the child's entry set changed.
                dir.parentFile?.let { p -> FilesSheetCache.invalidate(p.absolutePath) }
            }
        }
        // ── GOAT-PASS 4 (§2): the whole-storage search index goes stale with ──
        // the same event — invalidate() drops it AND schedules the background
        // rebuild, so the next query is answered from a fresh index without
        // ever paying an inline rebuild.
        try { RecursiveFileSearch.invalidate() } catch (e: Exception) { /* index rebuild is best-effort */ }
        snapshotDirScroll(currentDir)
        pendingScrollRestore = currentDir.absolutePath
        folderPicks.entries.retainAll { runCatching { File(it.key).exists() }.getOrDefault(false) }
        filePicks.entries.retainAll { runCatching { File(it.key).exists() }.getOrDefault(false) }
        load()
        // GOAT-PASS 8 (§7): the paste bar's label reflects whatever clipboard
        // state the completed operation left (a fully-pasted clipboard is
        // consumed; a cancelled one stays armed).
        paintChrome()
    }

    fun startOpUi(phase: String) {
        opProgress.isIndeterminate = true
        opPct.text = phase
        opBar.visibility = View.VISIBLE
        // ── GOAT-PASS 8 (§7): the opBar owns the bottom slot while an engine ──
        // operation runs — the floating paste bar stands down for exactly that
        // window (paintChrome re-evaluates it after the operation settles: a
        // fully-pasted clipboard is consumed and the bar stays hidden; a
        // cancelled one stays armed and the bar returns).
        pasteFloatBar.visibility = View.GONE
        // ── FINAL PASS 2 (§5): the list's bottom padding follows the swap — the ──
        // progress bar must not cover the final row either.
        updateListBottomPad()
    }

    var opLastUiMs = 0L
    fun updateOpUi(p: FileOps.Progress) {
        val now = android.os.SystemClock.uptimeMillis()
        if (p.totalBytes > 0 && now - opLastUiMs < 80) return   // ≤12 updates/s on the main thread
        opLastUiMs = now
        if (p.totalBytes <= 0) { opProgress.isIndeterminate = true; opPct.text = p.phase }
        else {
            opProgress.isIndeterminate = false
            opProgress.max = 100; opProgress.progress = p.percent
            opPct.text = "${p.phase} · ${p.percent}%"
        }
    }

    fun endOpUi() {
        opBar.visibility = View.GONE
        // ── FINAL PASS 2 (§5): the opBar's reserved space goes with it (a paste
        // bar re-arming right after re-reserves it through paintChrome).
        updateListBottomPad()
    }

    fun pasteClipboard() {
        val cb = clipboard ?: return
        if (FileOps.running) { Toast.makeText(ctx, "An operation is already running", Toast.LENGTH_SHORT).show(); return }
        val dest = currentDir
        if (!runCatching { dest.isDirectory && dest.canWrite() }.getOrDefault(false)) {
            Toast.makeText(ctx, "This folder is not writable", Toast.LENGTH_SHORT).show()
            return
        }
        opJob = scope.launch {
            startOpUi(if (cb.isCut) "Moving…" else "Copying…")
            val onProgress: (FileOps.Progress) -> Unit = { p -> scope.launch { updateOpUi(p) } }
            val result = if (cb.isCut)
                FileOps.moveBatch(cb.picks, dest, showHidden, onProgress)
            else
                FileOps.copyBatch(cb.picks, dest, showHidden, onProgress)
            endOpUi()
            if (!result.cancelled) clipboard = null
            val verb = if (cb.isCut) "Moved" else "Copied"
            val msg = when {
                result.cancelled -> "Cancelled — ${result.ok} of ${cb.picks.size} completed"
                result.failed > 0 -> "$verb ${result.ok}, couldn't ${if (cb.isCut) "move" else "copy"} ${result.failed}" +
                    (result.firstError?.let { " ($it)" } ?: "")
                else -> "$verb ${result.ok} item${if (result.ok != 1) "s" else ""}"
            }
            Toast.makeText(ctx, msg, if (result.failed > 0 || result.cancelled) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            afterFsChange(if (cb.isCut) cb.picks.map { it.root.parentFile } else emptyList())
        }
    }

    // ── GOAT-PASS 8 (§7): the floating paste bar's two actions, wired here ────
    // (AFTER pasteClipboard/paintChrome exist — locals are strictly
    // before-use). Paste runs the exact same pasteClipboard() the ≮ menu's
    // Paste entry runs (collision-safe landing, cut removes sources only
    // after their copies landed, the destination listing and every relevant
    // folder count refresh through afterFsChange); Cancel drops the pending
    // clipboard state and hides the bar — nothing on disk is touched.
    pasteFloatPasteBtn.setOnClickListener { pasteClipboard() }
    pasteFloatCancelBtn.setOnClickListener {
        clipboard = null
        pasteFloatBar.visibility = View.GONE
        paintChrome()
        Toast.makeText(ctx, "Paste cancelled", Toast.LENGTH_SHORT).show()
    }

    fun startDelete(roots: List<File>) {
        if (FileOps.running) { Toast.makeText(ctx, "An operation is already running", Toast.LENGTH_SHORT).show(); return }
        opJob = scope.launch {
            startOpUi("Deleting…")
            val (ok, failed) = FileOps.deleteBatch(roots)
            endOpUi()
            folderPicks.clear(); filePicks.clear(); anchorKey = null
            val msg = when {
                ok > 0 && failed == 0 -> "Deleted $ok item${if (ok != 1) "s" else ""}"
                ok > 0 -> "Deleted $ok, couldn't delete $failed"
                else -> "Couldn't delete — ${roots.size} item${if (roots.size != 1) "s" else ""} failed"
            }
            Toast.makeText(ctx, msg, if (failed > 0) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
            afterFsChange(emptyList())
        }
    }

    fun confirmDelete() {
        val roots = (folderPicks.keys.toList() + filePicks.keys.toList()).map { File(it) }
        if (roots.isEmpty()) return
        val folders = roots.count { runCatching { it.isDirectory }.getOrDefault(false) }
        val label = if (roots.size == 1) '"' + roots[0].name + '"' else "${roots.size} items"
        showLdxDialog(
            context = ctx,
            title = "Delete $label?",
            message = (if (folders > 0)
                "Everything inside the selected folder${if (folders != 1) "s" else ""} goes with it. " else "") +
                "This permanently deletes from this device and cannot be undone.",
            iconRes = R.drawable.ic_delete,
            actions = listOf(
                LdxDialogAction("Cancel"),
                LdxDialogAction("Delete", style = LdxButtonStyle.DESTRUCTIVE) { startDelete(roots) },
            ),
        )
    }

    fun showRenameDialog() {
        val paths = folderPicks.keys.toList() + filePicks.keys.toList()
        if (paths.size != 1) return
        val target = File(paths[0])
        val oldName = target.name
        if (!runCatching { target.exists() }.getOrDefault(false)) return
        val input = Widgets.inputField(ctx, oldName, android.text.InputType.TYPE_CLASS_TEXT).apply {
            setSingleLine()
            // The BASE name starts selected, so an ordinary edit preserves
            // the extension unless the user deliberately changes it.
            val dot = oldName.lastIndexOf('.')
            setSelection(0, if (dot > 0) dot else oldName.length)
        }
        val dlgRef = arrayOfNulls<Dialog>(1)
        fun tryApply() {
            var newName = input.text.toString().trim()
            // ── FULL FINAL PASS 2 (§7A — the duplicate-extension typo guard): a ──
            // name typed as "photo.jpg.jpg" (an IME autocomplete or a manual
            // slip over the pre-filled extension) is trimmed back to ONE
            // trailing extension. The success toast below shows the FINAL
            // name, so the correction is never silent.
            val oldExt = oldName.substringAfterLast('.', "")
            if (oldExt.isNotEmpty() && newName.endsWith(".$oldExt.$oldExt"))
                newName = newName.removeSuffix(".$oldExt")
            when {
                newName.isEmpty() || newName == "." || newName == ".." ->
                    Toast.makeText(ctx, "Enter a valid name", Toast.LENGTH_SHORT).show()
                newName.contains('/') ->
                    Toast.makeText(ctx, "A name cannot contain \"/\"", Toast.LENGTH_SHORT).show()
                newName == oldName -> dlgRef[0]?.dismiss()
                File(target.parentFile, newName).exists() ->
                    Toast.makeText(ctx, "" + '"' + newName + '"' + " already exists in this folder", Toast.LENGTH_SHORT).show()
                else -> {
                    val ok = try { target.renameTo(File(target.parentFile, newName)) } catch (e: Exception) { false }
                    if (!ok) { Toast.makeText(ctx, "Couldn't rename — the storage refused", Toast.LENGTH_LONG).show(); return }
                    // The pin follows the file to its new identity.
                    val newKey = File(target.parentFile, newName).absolutePath
                    if (pinnedPaths.remove(target.absolutePath)) { pinnedPaths.add(newKey); SettingsManager.setPinnedPaths(ctx, pinnedPaths) }
                    dlgRef[0]?.dismiss()
                    folderPicks.clear(); filePicks.clear(); anchorKey = null
                    afterFsChange(emptyList())
                    Toast.makeText(ctx, "" + '"' + "Renamed to " + newName + '"', Toast.LENGTH_SHORT).show()
                }
            }
        }
        dlgRef[0] = showLdxDialog(
            context = ctx,
            title = "Rename",
            bodyView = input,
            iconRes = R.drawable.ic_edit,
            actions = listOf(
                LdxDialogAction("Cancel"),
                LdxDialogAction("Rename", dismisses = false) { tryApply() },
            ),
        )
    }

    /** ── GOAT-PASS 8 (§5): NEW FOLDER — the same input-dialog pattern the ─────
     *  Rename action uses, creating a directory inside the CURRENTLY OPEN ──
     *  directory (never a search-results view — a search hit's “location” is
     *  a display context, not a browsing destination; the action is only
     *  offered while genuinely browsing). Validation mirrors the platform's
     *  own rules: a blank, ".", ".." or "/"-carrying name is refused with a
     *  plain-language toast, an existing collision is refused (never
     *  auto-suffixed — the user asked for THAT name), and a storage refusal
     *  (read-only SD card, full disk) is reported honestly. Success refreshes
     *  the listing AND the parent directory's cached copy of THIS directory's
     *  row (afterFsChange's parent invalidation) so the new folder's count
     *  is correct everywhere immediately. */
    fun showNewFolderDialog() {
        // A search view is a temporary scope — creating a folder "into" it
        // would target a directory the user is not looking at.
        if (searchActive) {
            Toast.makeText(ctx, "Open a folder to create a new one inside it", Toast.LENGTH_SHORT).show()
            return
        }
        val input = Widgets.inputField(ctx, "", android.text.InputType.TYPE_CLASS_TEXT).apply {
            setSingleLine()
            hint = "Folder name"
        }
        val dlgRef = arrayOfNulls<Dialog>(1)
        fun tryCreate() {
            val name = input.text.toString().trim()
            when {
                name.isEmpty() || name == "." || name == ".." ->
                    Toast.makeText(ctx, "Enter a valid folder name", Toast.LENGTH_SHORT).show()
                name.contains('/') ->
                    Toast.makeText(ctx, "A name cannot contain \"/\"", Toast.LENGTH_SHORT).show()
                File(currentDir, name).exists() ->
                    Toast.makeText(ctx, "" + '"' + name + '"' + " already exists in this folder", Toast.LENGTH_SHORT).show()
                else -> {
                    val dir = File(currentDir, name)
                    val ok = try { dir.exists() || dir.mkdirs() } catch (e: Exception) { false }
                    if (!ok) { Toast.makeText(ctx, "Couldn't create the folder — the storage refused", Toast.LENGTH_LONG).show(); return }
                    dlgRef[0]?.dismiss()
                    // ── GOAT-PASS 9 (§7): the NEW folder receives the SAME ──
                    // highlight the search-result locate uses — armed BEFORE
                    // the refresh so the first submit carrying the refreshed
                    // listing scrolls to it and fires the reveal flash, even
                    // when the directory's sort places it far from the top.
                    // Reuses the existing reveal machinery verbatim
                    // (pendingRevealPath + submitVisible's scroll/flash +
                    // revealSearchTarget's lifecycle); no second highlight
                    // system. The reveal also cancels the viewport restore
                    // (submitVisible owns that when a reveal is pending), so
                    // the two scrolls can never fight.
                    pendingRevealPath = dir.absolutePath
                    pendingRevealComfortable = false
                    afterFsChange()
                    Toast.makeText(ctx, "Created " + '"' + name + '"', Toast.LENGTH_SHORT).show()
                }
            }
        }
        dlgRef[0] = showLdxDialog(
            context = ctx,
            title = "New folder",
            bodyView = input,
            iconRes = R.drawable.ic_folder,
            actions = listOf(
                LdxDialogAction("Cancel"),
                LdxDialogAction("Create", dismisses = false) { tryCreate() },
            ),
        )
    }

    // ══ Pass-44 (§8): storage switching ═════════════════════════════════════
    fun switchStorage(root: File) {
        refreshVolumes()
        val stillThere = root.absolutePath == ExternalVolumes.internalRoot().absolutePath ||
                volumesCache.any { it.root.absolutePath == root.absolutePath }
        if (!stillThere) {
            Toast.makeText(ctx, "That storage is no longer available", Toast.LENGTH_SHORT).show()
            return
        }
        if (root.absolutePath == rootDir.absolutePath) return
        snapshotDirScroll(currentDir)
        rootDir = root
        currentDir = root
        // The volume switch is a fresh browsing session: search state, the
        // range anchor and any pending scroll restore all belong to the OLD
        // root; the per-directory sort memory loads for the new one (each
        // directory's setting is keyed by absolute path, so both volumes
        // keep independent memories).
        query = ""; searchActive = false; searchIndexing = false; searchHits = emptyList()
        searchRestore = null; searchRestoreDir = null
        anchorKey = null; pendingScrollRestore = null
        applyStoredSort(root)
        programmaticSearchText = true
        try { searchBox.setText("") } finally { programmaticSearchText = false }
        load()
    }

    storageBtn.setOnClickListener {
        refreshVolumes()
        if (volumesCache.isEmpty()) {
            storageBtn.visibility = View.GONE
            Toast.makeText(ctx, "No SD card or USB storage is available", Toast.LENGTH_SHORT).show()
            return@setOnClickListener
        }
        val internal = ExternalVolumes.internalRoot()
        val opts = ArrayList<PickerOption>()
        opts.add(PickerOption("INT", "Internal storage", rootDir.absolutePath == internal.absolutePath))
        for ((i, v) in volumesCache.withIndex())
            opts.add(PickerOption("VOL$i", v.label, rootDir.absolutePath == v.root.absolutePath))
        showPickerOptionsMenu(storageBtn, "Storage", opts, compact = true, endAnchored = true) { id ->
            when (id) {
                "INT" -> switchStorage(internal)
                else -> {
                    val idx = id.removePrefix("VOL").toIntOrNull() ?: -1
                    volumesCache.getOrNull(idx)?.let { switchStorage(it.root) }
                }
            }
        }
    }

    // ── Pass-44 (§7): the three-dot menu itself. Sorting stays available ──
    // with NOTHING selected; the management actions appear only while they
    // can act (Copy/Cut/Pin/Delete need a selection, Rename exactly one,
    // Paste an armed clipboard) and disappear the instant that changes —
    // the list is rebuilt fresh on every open from live state.
    fun showSheetMenu() {
        val opts = ArrayList<PickerOption>()
        opts.add(PickerOption("KEY_NAME", "Name", sortKey == FilesSortKey.NAME))
        opts.add(PickerOption("KEY_DATE", "Date modified", sortKey == FilesSortKey.DATE))
        opts.add(PickerOption("KEY_SIZE", "Size", sortKey == FilesSortKey.SIZE))
        opts.add(PickerOption("KEY_TYPE", "Type", sortKey == FilesSortKey.TYPE))
        opts.add(PickerOption("", "", divider = true))
        opts.add(PickerOption("DIR_ASC", "Ascending", !sortDesc))
        opts.add(PickerOption("DIR_DESC", "Descending", sortDesc))
        opts.add(PickerOption("", "", divider = true))
        opts.add(PickerOption("HIDDEN", "Show hidden files", showHidden))
        // ── GOAT-PASS 5 (§5): Remember Last Opened Directory — enabled by ──
        // default for existing and new users; the check mark shows the live
        // state, a tap flips it immediately (persisted), and the sheet's
        // next open honours whichever state is current.
        opts.add(PickerOption("LAST_DIR", "Remember last opened folder",
            SettingsManager.isRememberLastDir(ctx), iconRes = R.drawable.ic_folder))
        // ── Pass-40 (#1): Apply to all — one clearly-labelled action at ──
        // the menu's end (after the hidden toggle, separated by its own
        // divider, so the everyday picks stay a tight group and the bulk
        // action reads as the deliberate extra it is). It applies the
        // CURRENT criterion + direction recursively to every subfolder of
        // the CURRENT directory, after a one-tap confirmation.
        opts.add(PickerOption("", "", divider = true))
        opts.add(PickerOption("APPLY_ALL", "Apply to all subfolders", false))
        // ── GOAT-PASS 8 (§5): NEW FOLDER — always available while genuinely ──
        // browsing (a destination that always makes sense), placed with the
        // other always-there actions before the selection-conditional group.
        // In a search view it reports honestly instead of targeting a
        // directory the user is not looking at.
        opts.add(PickerOption("NEW_FOLDER", "New folder", iconRes = R.drawable.ic_folder_plus))
        // ── Pass-44 (§7): the conditional actions ──
        val selPaths = folderPicks.keys.toList() + filePicks.keys.toList()
        if (selPaths.isNotEmpty()) {
            val allPinned = selPaths.all { pinnedPaths.contains(it) }
            opts.add(PickerOption("", "", divider = true))
            opts.add(PickerOption("COPY", "Copy", iconRes = R.drawable.ic_copy))
            opts.add(PickerOption("CUT", "Cut", iconRes = R.drawable.ic_cut))
            opts.add(PickerOption(if (allPinned) "UNPIN" else "PIN",
                if (allPinned) "Unpin" else "Pin", iconRes = R.drawable.ic_pin))
            opts.add(PickerOption("DELETE", "Delete", iconRes = R.drawable.ic_delete))
            if (selPaths.size == 1) opts.add(PickerOption("RENAME", "Rename", iconRes = R.drawable.ic_edit))
            // ── GOAT-PASS 12 (PART 4): the in-app ZIP extraction feature ──
            // is REMOVED entirely — the three-dot menu returns to its normal
            // actions. ZIPs remain selectable/transferable/openable through
            // external apps; extraction is delegated to external tools such
            // as ZArchiver by final product decision.
        }
        clipboard?.let { cb ->
            opts.add(PickerOption("", "", divider = true))
            opts.add(PickerOption("PASTE",
                "Paste ${cb.picks.size} item${if (cb.picks.size != 1) "s" else ""} here",
                iconRes = R.drawable.ic_paste))
        }
        showPickerOptionsMenu(sortBtn, "Files", opts, compact = true, endAnchored = true) { id ->
            // Actions first — none of them touches sorting state.
            when (id) {
                "COPY" -> { armClipboard(false); return@showPickerOptionsMenu }
                "CUT" -> { armClipboard(true); return@showPickerOptionsMenu }
                "PIN", "UNPIN" -> { togglePins(); return@showPickerOptionsMenu }
                "DELETE" -> { confirmDelete(); return@showPickerOptionsMenu }
                "RENAME" -> { showRenameDialog(); return@showPickerOptionsMenu }
                "PASTE" -> { pasteClipboard(); return@showPickerOptionsMenu }
                "NEW_FOLDER" -> { showNewFolderDialog(); return@showPickerOptionsMenu }
            }
            when (id) {
                "KEY_NAME" -> sortKey = FilesSortKey.NAME
                "KEY_DATE" -> sortKey = FilesSortKey.DATE
                "KEY_SIZE" -> sortKey = FilesSortKey.SIZE
                "KEY_TYPE" -> sortKey = FilesSortKey.TYPE
                "DIR_ASC" -> sortDesc = false
                "DIR_DESC" -> sortDesc = true
                // ── GOAT-PASS 5 (§5): flip and persist immediately. Enabling
                // adopts the CURRENT directory as the remembered one (the
                // location the user is looking at right now is the most
                // truthful "last opened"); disabling keeps the stored value
                // dormant — the sheet simply opens on the root until the
                // user re-enables, and re-enabling re-adopts whatever
                // directory is current then.
                "LAST_DIR" -> {
                    val nowOn = !SettingsManager.isRememberLastDir(ctx)
                    SettingsManager.setRememberLastDir(ctx, nowOn)
                    if (nowOn) SettingsManager.setLastFilesDir(ctx, currentDir.absolutePath)
                    return@showPickerOptionsMenu
                }
                "HIDDEN" -> {
                    showHidden = !showHidden
                    SettingsManager.setPickerShowHidden(ctx, showHidden)
                    // Pass-36 (#7): the search index bakes the hidden rule in
                    // at build time — a flip invalidates it so the next query
                    // respects the new eligibility.
                    // GOAT-PASS 4 (§2): the hidden rule changed — rebuild the
                    // whole-storage index in the background under the NEW rule.
                    RecursiveFileSearch.invalidate()
                    RecursiveFileSearch.prewarm(rootDir, showHidden)
                }
                // ── Pass-40 (#1): the recursive apply goes through its own
                // confirmation dialog — the menu itself stays one tap for the
                // everyday picks, and the bulk write never fires by accident.
                "APPLY_ALL" -> { confirmApplySortToAll(); return@showPickerOptionsMenu }
            }
            // ── Pass-40 (#1): every sorting change is remembered for THIS
            // directory alone — reopening it restores exactly this
            // configuration, and no other directory's setting is touched.
            if (id != "HIDDEN") SettingsManager.setFilesDirSort(ctx, currentDir.absolutePath, currentSortValue())
            anchorKey = null   // a new order invalidates the range anchor
            submitAll()
            // ── GOAT-PASS 10 (§3): every sorting change returns the list to ──
            // the top — the new order's first rows are what the user just
            // asked to see. Instant (not animated): an animated scroll over a
            // re-sorted list would show intermediate orderings, exactly what
            // the requirement forbids; the DiffUtil move animations keep the
            // transition itself visually calm. Only genuine sorting changes
            // land here — the hidden toggle keeps its own behavior, and
            // normal navigation/scroll restoration is untouched.
            if (id != "HIDDEN") {
                (list.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0)
            }
        }
    }

    sortBtn.setOnClickListener { showSheetMenu() }

    // ── GOAT-PASS 4 (§2): the one shared query-apply, used by the debounced ──
    // watcher and the IME Search action alike. ONE scope, ONE behaviour:
    //   blank query → ordinary browsing of the current directory;
    //   any query   → the recursive WHOLE-STORAGE search (the restored
    //                 Pass-36 behaviour), ranked hits with path context,
    //                 answered from the background-prewarmed index — the
    //                 GOAT-PASS 3 "This folder / All storage" chip is gone,
    //                 and the first query no longer waits for a cold index
    //                 build (AppPrewarm + this sheet's own open both warm
    //                 it, and filesystem changes refresh it in the
    //                 background).
    // ── GOAT-PASS 15 (§6): the sheet session's open moment. The index's ──
    // 5-minute freshness window is the belt for EXTERNAL writers, but a
    // fresh-but-OLD index (prewarmed at app start, before the received
    // .xapk files landed, or before a file manager copied new files in)
    // still honestly answered "no such file" for exactly those files —
    // the reported "first search missed some files, the second search
    // worked" repro: by then the window had lapsed and the rebuild saw
    // them. The fix is deterministic, not heuristic: the FIRST query of a
    // sheet session is never served by an index built before the sheet
    // opened — force one rebuild at most, and every later query inside the
    // session rides the normal freshness machinery.
    val sheetOpenedAtMs = System.currentTimeMillis()
    var firstQueryOfSession = true

    fun runSearchNow() {
        anchorKey = null   // the visible set changed; the anchor may be gone
        // ── Pass-38: a REAL query (or a deliberate return to browsing) ──
        // invalidates any pending Pass-37 (#3C) restore snapshot. The
        // snapshot exists only to bring the user BACK from a directory
        // they jumped into from THOSE results; the moment they start a
        // different search — or clear the box to browse — the old
        // results-view is no longer where they came from, and Back must
        // not clobber their new screen with it.
        searchRestore = null
        searchRestoreDir = null
        if (query.isBlank()) {
            // Back to browsing the current directory.
            searchActive = false; searchIndexing = false; searchHits = emptyList()
            submitAll()
        } else {
            // ── Pass-36 (#7), restored by GOAT-PASS 4 (§2): RECURSIVE ──
            // GLOBAL SEARCH — the whole accessible hierarchy from the
            // storage root, through a budgeted index (built on IO the
            // first time, reused for every keystroke after; refreshed in
            // the background when the filesystem changes). The UI shows an
            // honest "Indexing…" state only for a genuinely cold index —
            // the prewarm paths make that the rare case — then the ranked
            // hits with their path context.
            val serial = searchSerial.get()
            // §6: the first query of a session may be served ONLY by an
            // index built after the sheet opened — the received/copied files
            // the user is about to look for happened before that moment's
            // disk state only by luck otherwise.
            val forceFreshForFirstQuery = firstQueryOfSession
            firstQueryOfSession = false
            searchActive = true
            searchIndexing = !RecursiveFileSearch.isIndexReady(rootDir, showHidden) ||
                (forceFreshForFirstQuery && !RecursiveFileSearch.isIndexBuiltAfter(rootDir, showHidden, sheetOpenedAtMs))
            searchHits = emptyList()
            submitAll()
            scope.launch {
                if (forceFreshForFirstQuery)
                    RecursiveFileSearch.ensureIndexBuiltAfter(rootDir, showHidden, sheetOpenedAtMs)
                else
                    RecursiveFileSearch.ensureIndex(rootDir, showHidden)
                if (serial != searchSerial.get()) return@launch   // a newer keystroke owns the results
                // The ranked query runs on Dispatchers.Default — a linear
                // scan of up to 25k indexed names is single-digit
                // milliseconds, but on an old phone's single-core burst it
                // is still not main-thread work.
                val hits = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    RecursiveFileSearch.query(query)
                }
                if (serial != searchSerial.get()) return@launch   // superseded while ranking
                searchIndexing = false
                searchHits = hits.map { hit ->
                    Entry(hit.file, hit.isDir, hit.size, hit.mtime, 0, searchContext = hit.context)
                }
                submitAll()
            }
        }
    }
    searchFinalizer = {
        // The IME's Search/Enter action: flush any pending debounce and
        // apply the query RIGHT NOW (searchSerial invalidates the in-flight
        // debounce coroutine), leaving the results on screen exactly as
        // they are. The caller clears focus, which dismisses the keyboard.
        searchSerial.incrementAndGet()
        runSearchNow()
    }
    searchBox.addTextChangedListener { e ->
        // ── Pass-37 (#3C): a programmatic restore (Back from a result's ──
        // directory) replays the SAVED search state verbatim — the text
        // change is not a new query, and re-running it would needlessly
        // flash the indexing state and re-rank the very results being
        // restored.
        if (programmaticSearchText) return@addTextChangedListener
        val serial = searchSerial.incrementAndGet()
        query = e?.toString() ?: ""
        scope.launch {
            delay(120)   // the App picker's own debounce rhythm
            if (serial != searchSerial.get()) return@launch   // superseded mid-debounce
            runSearchNow()
        }
    }
    clearBtn.setOnClickListener { folderPicks.clear(); filePicks.clear(); anchorKey = null; repaint() }
    selectAllBtn.setOnClickListener {
        // All ELIGIBLE items in the current scope: the visible list already
        // carries the hidden filter, the search filter and the sort.
        for (entry in visibleEntries()) {
            if (entry.isDir) selectFolderOn(entry.file) else selectFileOn(entry.file)
        }
        repaint()
    }
    addBtn.setOnClickListener {
        if (!selectionMode()) return@setOnClickListener
        scope.launch {
            // #3D: FULL picks stay the physical folder (the server walks it,
            // exactly as before); PARTIAL picks become manifest folders whose
            // children are exactly the still-selected descendants — hierarchy
            // preserved through the relPaths, deselected files never
            // restored, nothing flattened into top-level entries.
            //
            // ── Pass-36 Part 2 (#4): the picker's CURRENT hidden mode is the ──
            // eligibility rule, captured at Add time: a folder picked while
            // hidden rows were visible carries includeHiddenChildren = true;
            // otherwise every walker of its tree (queue expand, stats pill,
            // native push, browser ZIP — and the manifest build below) skips
            // hidden descendants. A pick whose ROOT itself is hidden stages
            // only while the hidden mode that made it selectable is still ON.
            val includeHidden = showHidden
            val items = ArrayList<PreviewItem>(folderPicks.size + filePicks.size)
            var skippedEmpty = 0
            for (pick in folderPicks.values) {
                if (!includeHidden && isHiddenName(pick.root)) { skippedEmpty++; continue }
                if (!pick.partial) {
                    items.add(PreviewItem(
                        name = pick.root.name,
                        path = pick.root.absolutePath,
                        uri = null,
                        size = -1L,
                        mime = "application/zip",
                        isFolder = true,
                        includeHiddenChildren = includeHidden,
                    ))
                } else {
                    val manifest = withContext(Dispatchers.IO) { buildManifestFor(pick, includeHidden) }
                    if (manifest.isNotEmpty()) {
                        items.add(PreviewItem(
                            name = pick.root.name,
                            path = pick.root.absolutePath,
                            uri = null,
                            size = manifest.sumOf { it.size },
                            mime = "application/zip",
                            isFolder = true,
                            childManifest = manifest,
                            includeHiddenChildren = includeHidden,
                        ))
                    } else {
                        skippedEmpty++
                    }
                }
            }
            for (f in filePicks.values) {
                items.add(PreviewItem(
                    name = f.name,
                    path = f.absolutePath,
                    uri = null,
                    size = f.length(),
                    mime = MimeTypes.getMimeType(f.extension),
                ))
            }
            withContext(Dispatchers.Main) {
                if (skippedEmpty > 0)
                    Toast.makeText(ctx, "$skippedEmpty selected folder${if (skippedEmpty != 1) "s" else ""} had nothing left to add", Toast.LENGTH_SHORT).show()
                sheet.dismiss()
                onAdd(items)
            }
        }
    }

    // ── GOAT-PASS 9 (§5 — live synchronization): while the sheet is OPEN, ─────
    // external filesystem/media changes refresh the CURRENT directory by ──
    // themselves. The mtime comparison (see noteDirMtimeForSync) gates the
    // work: a change that did not touch the browsed directory is a no-op,
    // and one that did reuses afterFsChange — the same path the sheet's own
    // operations take (cache invalidation, fresh listing, scroll and
    // selection preservation, vanished-pick pruning). Runs on main
    // (LibrarySync posts listener callbacks there).
    val libSyncHook = com.lumidrop.x.media.LibrarySync.Listener { _, _ ->
        try {
            val dirPath = lastSyncDirPath
            if (dirPath != null && dirPath == currentDir.absolutePath) {
                val nowMtime = try { currentDir.lastModified() } catch (e: Exception) { -1L }
                if (nowMtime != lastSyncDirMtime) afterFsChange()
            } else {
                noteDirMtimeForSync(currentDir)
            }
        } catch (e: Exception) { /* never break a live sheet over a sync refresh */ }
    }
    com.lumidrop.x.media.LibrarySync.addListener(libSyncHook)

    // The sheet's own dismissal (backdrop, drag, back key) cancels the
    // sheet-scoped scope — no listing can outlive the sheet — and releases
    // the one-live-sheet slot (Pass-37 #4).
    sheet.dialog.setOnDismissListener {
        // ── GOAT-PASS 11 (P5.4): mark the sheet dead FIRST — every ──
        // UI-touching callback from here on no-ops (kept for any future
        // long-running op surface; the GP10/GP11 in-app ZIP extraction
        // engine itself is REMOVED in GOAT-PASS 12 by final product
        // decision). The Pass-44/Pass-7 teardown below is unchanged.
        sheetAlive = false
        // ── Pass-44: the engine's cooperative cancel runs even when the ──
        // sheet dies mid-operation — the copy loop stops promptly and its
        // partial destination file is deleted (nothing half survives).
        // ── GOAT-PASS 7 Part 2 (§5): but ONLY when THIS sheet's operation ──
        // is the one actually in flight — the Music sheet now shares the
        // FileOps engine, and its live operation must not be cancelled by a
        // Files-sheet dismissal that knows nothing about it (the Music sheet
        // guards its own dismissal symmetrically).
        if (opJob?.isActive == true) {
            try { FileOps.cancel() } catch (e: Exception) { /* engine idle */ }
            try { opJob?.cancel() } catch (e: Exception) { /* already gone */ }
        }
        try { scope.cancel() } catch (e: Exception) { /* already gone */ }
        // GOAT-PASS 9 (§5): the live-sync hook dies with the sheet.
        try { com.lumidrop.x.media.LibrarySync.removeListener(libSyncHook) } catch (e: Exception) { /* already gone */ }
        PickerGate.release("files")
        // ── Pass-44 (extra): the floating music card's coexistence hook — ──
        // the Files sheet counts as a modal sheet for z-order purposes, so
        // a card that appears while the sheet is up re-stacks ABOVE it
        // (Pass-40's bringAboveSheets) and the sheet's close releases it.
        try { MusicPlayback.onModalSheetVisibilityChanged(false) } catch (e: Exception) { /* playback UI gone */ }
    }

    // ── Pass-44 (extra): while this sheet is up, the floating music card ──
    // must be able to appear ON TOP of it (the reported 4-second overlay
    // showing BEHIND the Files sheet) — register the sheet with the
    // playback overlay's modal-coexistence state on open.
    try { MusicPlayback.onModalSheetVisibilityChanged(true) } catch (e: Exception) { /* playback UI gone */ }

    // ── GOAT-PASS 5 (§5): REMEMBER LAST OPENED DIRECTORY (enabled by default).
    // Before the sheet's first frame, restore the last directory the user
    // genuinely browsed — validated, never trusted: a directory that no
    // longer exists (or lost its permissions) falls back to its NEAREST
    // LIVING PARENT, then the storage root; a remembered path outside the
    // current root volume is ignored entirely (the volume switcher keeps
    // its own behaviour). Disabling the preference keeps the default
    // root-opening behaviour, and this whole block is a no-op then.
    try {
        if (startDir != null) {
            // ── GOAT-PASS 10 (§9): the explicit opening directory — the ──
            // internal "Open Received Folder" flow lands HERE, at the exact
            // configured destination, on whichever mounted volume contains
            // it. The remembered-directory preference is deliberately
            // skipped: this open is a NAVIGATION REQUEST to a specific
            // folder, not a resumption of browsing. Navigating away from
            // here afterwards writes the preference exactly as any genuine
            // navigation does.
            refreshVolumes()
            val sdPath = startDir.canonicalFile.absolutePath
            val volRoot = volumesCache.firstOrNull { v ->
                sdPath == v.root.absolutePath || sdPath.startsWith(v.root.absolutePath + "/")
            }
            if (volRoot != null) rootDir = volRoot.root
            currentDir = startDir.canonicalFile
        } else if (SettingsManager.isRememberLastDir(ctx)) {
            val remembered = SettingsManager.getLastFilesDir(ctx)
            if (remembered != null) {
                // Walk up to the nearest ancestor that is a readable
                // directory INSIDE the current root volume — at most to the
                // storage root itself; a remembered path on another volume
                // (or above the root) is ignored entirely.
                var candidate: File? = File(remembered)
                var guard = 0
                while (candidate != null && guard++ < 64) {
                    val p = candidate.absolutePath
                    val insideRoot = p == rootDir.absolutePath || p.startsWith(rootDir.absolutePath + "/")
                    if (!insideRoot) { candidate = null; break }
                    val alive = try { candidate.isDirectory && candidate.canRead() } catch (e: Exception) { false }
                    if (alive) break
                    candidate = candidate.parentFile
                }
                if (candidate != null && candidate.absolutePath != rootDir.absolutePath) {
                    currentDir = candidate
                    // The remembered directory's own stored sorting is applied
                    // by the chrome block just below (applyStoredSort
                    // (currentDir)) — restoring currentDir here is all that
                    // is needed.
                }
            }
        }
    } catch (e: Exception) { /* preference-only — never block the sheet open */ }

    try {
        sheet.show { root }
    } catch (e: Exception) {
        // The window never made it up (activity racing death) — the gate must
        // not stay latched against the next open.
        PickerGate.release("files")
        return
    }
    // ── GOAT-PASS 12 (§1): the internal "Open Received Folder" flow opens ──
    // this sheet at EXACTLY the size every other open uses — its normal
    // 75% initial height with the drag-to-expand affordance intact. The
    // GOAT-PASS 10 auto-full-screen expansion (openExpanded →
    // expandToFullHeight) is retired: "open the Received Folder internally"
    // is a NAVIGATION request, not a presentation change, and the user
    // stretches the sheet manually exactly as they always could.

    // ── Pass-35 (#3): the pill's back icon and Android Back navigate the ──────
    // SAME way — one shared action, so the two can never drift: up one
    // directory when there is one (selection preserved — navigating back
    // never clears picks), never out of the picker.
    fun navigateUpIfPossible(): Boolean {
        if (currentDir.absolutePath == rootDir.absolutePath) return false
        currentDir.parentFile?.let { navigate(it) }
        return true
    }
    navBackBtn.setOnClickListener { navigateUpIfPossible() }

    // ── Pass-35 (#4): the sheet's back decision, in the app-wide priority
    // order — an open keyboard is consumed by the IME itself before this
    // hook ever sees the press; then (Pass-37 #3C) a directory reached from
    // a search result returns to the EXACT search screen it was jumped from;
    // then parent directory; then exit selection mode; only then does the
    // press fall through to the sheet's own dismissal. Selection is NEVER
    // cleared by a folder navigation — the up-action above deliberately
    // leaves every pick in place.
    sheet.onBack = {
        when {
            searchRestoreDir != null && currentDir.absolutePath == searchRestoreDir?.absolutePath -> {
                // ── Pass-37 (#3C): Back from a search-jump target restores ──
                // the saved search screen verbatim (query, ranked results,
                // scroll, interface state — no re-query). Deeper navigation
                // below the jump target keeps the normal up-one-directory
                // behavior until the user returns to the jump target itself.
                searchRestore?.invoke()
                searchRestore = null
                searchRestoreDir = null
                true
            }
            navigateUpIfPossible() -> true
            selectionMode() -> {
                folderPicks.clear(); filePicks.clear(); anchorKey = null
                repaint()
                true
            }
            else -> false
        }
    }

    // ── Pass-35 (#1): the chrome rows are drag regions — empty areas drag
    // the sheet (expand/collapse/dismiss, exactly like the handle); the
    // search field, sort button, breadcrumb segments, back icon and action
    // buttons keep their own touches (the hit-test gate decides per press).
    // The browsing list itself is deliberately NOT registered — scrolling
    // owns its touches.
    sheet.registerDragRegion(titleRow)
    sheet.registerDragRegion(controls)
    sheet.registerDragRegion(actionRow)

    // Chrome paints instantly; the (cached) listing fills the list the
    // moment it lands — the same instant-open discipline as the App and
    // Media pickers.
    // ── Pass-40 (#1): the ROOT directory's own remembered sorting applies to
    // the sheet's very first frame too (the default stays name-ascending for
    // a never-configured root).
    applyStoredSort(currentDir)
    paintChrome()
    load()
    // ── GOAT-PASS 4 (§2 — first-query speed): warm the whole-storage search ──
    // index the moment the sheet opens (AppPrewarm already did it once at
    // process start; this refreshes it if the process has been alive a
    // while) — so the user's first query is answered from a resident index
    // instead of an inline build. Best-effort, background, bounded by the
    // engine's own one-walk discipline.
    try { RecursiveFileSearch.prewarm(rootDir, showHidden) } catch (e: Exception) { /* best-effort */ }
}

// ═══════════════════════════════════════════════════════════════════════════
// ── Pass-36 (#7): the recursive global-search engine ───────────────────────
//
// The picker's search used to filter the CURRENT directory's listing. This
// engine indexes the whole accessible hierarchy from the storage root ONCE
// per freshness window (a budgeted breadth-first walk on Dispatchers.IO) and
// answers every keystroke from that in-memory index — so search stays fast
// and responsive even on a full device, and the storage is never re-scanned
// per character (or even per sheet reopening, inside the window).
//
//   • TRAVERSAL — BFS from the root, 25,000 entries hard-capped, hidden
//     names skipped unless the picker's hidden mode is on, unreadable
//     directories (EACCES, Android/data on most builds) skipped silently.
//     File sizes/mtimes are captured during the walk so result rows render
//     without a second stat pass.
//   • RANKING — exact name (1000) > prefix (700) > word-boundary match (550,
//     so "report" finds "Annual_Report.pdf") > substring (300); ties break
//     to the shorter name, then the shallower path. The strongest match is
//     the first row, always.
//   • FRESHNESS — an index is reused for 60s; a later first-query rebuilds
//     in the background. Toggling hidden mode invalidates immediately.
//     Result rows act on REAL files (navigating a hit opens the live
//     directory), so a slightly stale index can never select something that
//     isn't there — the honest cost is at most a stale hit, never a wrong
//     action.
//   • CONCURRENCY — one Mutex serializes builds; concurrent first-queries
//     all await the SAME build instead of racing duplicates.
private object RecursiveFileSearch {

    /** One indexed filesystem node: its address, kind, lowercase name for
     *  matching, and its path from the storage root (the result rows'
     *  context line). */
    private class Indexed(val file: File, val isDir: Boolean, val lowerName: String,
                          val relPath: String, val size: Long, val mtime: Long)

    /** A ranked result: the node plus the score that ordered it and the
     *  PARENT path from the root ("0/Music/Rock") for display. */
    class Hit(val file: File, val isDir: Boolean, val context: String,
              val size: Long, val mtime: Long, val score: Int)

    @Volatile private var index: List<Indexed>? = null
    @Volatile private var builtAtMs = 0L
    // ── Pass-44 (§8): the index remembers WHICH root and hidden-rule it ──
    // was built for. Freshness was time-only before, so switching storage
    // volumes inside the 60s window would have served the OTHER volume's
    // index — a wrong-results class this sheet can no longer produce.
    @Volatile private var builtRoot: File? = null
    @Volatile private var builtHidden = false
    private val buildMutex = Mutex()

    /** Hard caps: entries in the index, and directories queued while walking
     *  (the queue can outgrow the index because files are counted only when
     *  stored). Together they bound the worst-case walk on pathological
     *  trees (node-heavy roots like Android/) instead of stalling it.
     *  ── GOAT-PASS 5 (§3 — first-attempt correctness): the GOAT-PASS 4 caps
     *  (25k/60k) were a SILENT completeness limit — on a media-heavy device
     *  (the reported "0/movies/moviebox/" tree) the breadth-first walk
     *  stopped before the deep late folders were ever listed, and a search
     *  for an episode living there honestly answered "no results" from a
     *  truncated index (the "found only after searching again later"
     *  symptom: a later rebuild after filesystem churn happened to cover a
     *  different slice). The caps are now 8× higher AND the Android/ system
     *  forest is deferred to the very END of the walk, so a cap — if one is
     *  ever hit on a genuinely pathological tree — eats system caches,
     *  never user content. A fresh index build is also fast enough that the
     *  honest "Indexing…" state stays rare (prewarm) and brief. */
    private const val MAX_INDEXED = 200_000
    private const val MAX_QUEUED = 400_000
    // ── GOAT-PASS 4 (§2): 60s → 5min. Freshness is no longer time-only:
    // the sheet's own filesystem-change hook (afterFsChange — every
    // delete/rename/paste) and the hidden-mode toggle both invalidate the
    // index IMMEDIATELY and trigger a background rebuild, so the window is
    // merely the belt that catches changes made OUTSIDE this app (another
    // file manager, a camera burst). Result rows always act on REAL files
    // (a stale hit navigates to a live directory or fails honestly), never
    // on index phantoms.
    private const val FRESH_WINDOW_MS = 5 * 60_000L

    /** Result cap per query — enough to be useful, bounded so the list stays
     *  instant on huge matches ("whatsapp" on a chatty device). */
    private const val MAX_RESULTS = 80

    // ── GOAT-PASS 4 (§2): the invalidation is EVENT-DRIVEN and self-healing —
    // the moment anything marks the index stale, a background rebuild
    // (same one-walk discipline as ensureIndex) refreshes it so the NEXT
    // query is answered from a fresh index instead of paying the build
    // inline. The rebuild root/hidden-rule are the ones the index was last
    // built with — the sheet re-invalidates on hidden-toggle and volume
    // switches, so they are always current.
    @Volatile private var lastRoot: File? = null
    @Volatile private var lastHidden = false
    private val refreshScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    fun invalidate() {
        index = null
        builtAtMs = 0L
        val root = lastRoot
        if (root != null) {
            val hidden = lastHidden
            refreshScope.launch {
                try { ensureIndex(root, hidden) } catch (e: Exception) { /* the next query rebuilds inline */ }
            }
        }
    }

    /** GOAT-PASS 4 (§2): true when a fresh index for exactly this root and
     *  hidden-rule is already resident — lets the search UI skip the honest
     *  "Indexing…" flash for the (now common) prewarmed case. */
    fun isIndexReady(root: File, includeHidden: Boolean): Boolean = isFreshFor(root, includeHidden)

    /** ── GOAT-PASS 15 (§6): is the resident index (a) fresh AND (b) built ──
     *  after [atMs]? The first query of a Files-sheet session demands (b):
     *  a prewarm snapshot taken before the sheet opened predates the very
     *  files the user is looking for (received mid-session, copied by a file
     *  manager) within the 5-minute freshness window — the "first search
     *  missed them, second search found them" repro. */
    fun isIndexBuiltAfter(root: File, includeHidden: Boolean, atMs: Long): Boolean =
        isFreshFor(root, includeHidden) && builtAtMs >= atMs

    /** §6's build-side twin: ensures an index that is fresh AND built after
     *  [atMs] — rebuilding exactly once when the resident one is older, and
     *  suspending behind any concurrent build so two walkers never race. */
    suspend fun ensureIndexBuiltAfter(root: File, includeHidden: Boolean, atMs: Long) {
        if (isIndexBuiltAfter(root, includeHidden, atMs)) return
        buildMutex.withLock {
            // Re-check under the lock: a concurrent build may already be new
            // enough — accept it instead of walking again.
            if (isIndexBuiltAfter(root, includeHidden, atMs)) return
        }
        // A fresh-but-old index is dropped so ensureIndex rebuilds; the
        // builtAt stamp then reflects THIS session's disk state.
        invalidateWithoutRebuild()
        ensureIndex(root, includeHidden)
    }

    /** Drops the resident index WITHOUT scheduling the background rebuild —
     *  the §6 caller rebuilds inline right after, under its own discipline. */
    private fun invalidateWithoutRebuild() {
        index = null
        builtAtMs = 0L
    }

    /** GOAT-PASS 4 (§2): fire-and-forget background warm-up — called by
     *  AppPrewarm (start-up) and the sheet's own first open so the first
     *  query lands on a resident index. Never blocks, never retries. */
    fun prewarm(root: File, includeHidden: Boolean) {
        refreshScope.launch {
            try { ensureIndex(root, includeHidden) } catch (e: Exception) { /* best-effort, always */ }
        }
    }

    /** Ensures a usable index, building (on IO) only when missing or stale.
     *  Suspends every caller behind the one build — no duplicate walks. */
    suspend fun ensureIndex(root: File, includeHidden: Boolean) {
        if (isFreshFor(root, includeHidden)) return
        buildMutex.withLock {
            if (isFreshFor(root, includeHidden)) return   // someone else built it while we waited
            val rootPath = root.absolutePath
            val acc = ArrayList<Indexed>(4096)
            val queue = ArrayDeque<File>()
            // ── GOAT-PASS 5 (§3): system-forest deferral — Android/ (the ──────
            // app's own caches and SDK forests: tens of thousands of entries
            // the user did not put there and will never search for) is walked
            // only AFTER every user-content directory, so the caps can never
            // eat a moviebox while caches still count.
            val deferred = ArrayDeque<File>()
            var queued = 0
            withContext(Dispatchers.IO) {
                queue.add(root); queued++
                while ((queue.isNotEmpty() || deferred.isNotEmpty()) && acc.size < MAX_INDEXED && queued < MAX_QUEUED) {
                    val dir = if (queue.isNotEmpty()) queue.removeFirst() else deferred.removeFirst()
                    val children = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
                    for (c in children) {
                        if (acc.size >= MAX_INDEXED || queued >= MAX_QUEUED) break
                        if (!includeHidden && c.name.startsWith(".")) continue
                        val isDir = c.isDirectory
                        val rel = c.absolutePath.removePrefix("$rootPath/")
                        // Capture size/mtime during the walk — the walk is
                        // already paying for the stat the listing implied.
                        var size = 0L; var mtime = 0L
                        if (!isDir) {
                            try { size = c.length(); mtime = c.lastModified() } catch (e: Exception) { /* unreadable */ }
                        }
                        acc.add(Indexed(c, isDir, c.name.lowercase(), rel, size, mtime))
                        if (isDir) {
                            if (rel == "Android") deferred.add(c) else queue.add(c)
                            queued++
                        }
                    }
                }
            }
            index = acc
            builtRoot = root
            builtHidden = includeHidden
            builtAtMs = System.currentTimeMillis()
            lastRoot = root
            lastHidden = includeHidden
        }
    }

    private fun isFreshFor(root: File, includeHidden: Boolean): Boolean =
        index != null && builtRoot == root && builtHidden == includeHidden &&
            System.currentTimeMillis() - builtAtMs < FRESH_WINDOW_MS

    /** Ranked query over the in-memory index — a linear lowercase scan of a
     *  capped list (single-digit milliseconds at the cap), main-thread safe. */
    fun query(rawQuery: String): List<Hit> {
        val idx = index ?: return emptyList()
        val q = rawQuery.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val hits = ArrayList<Hit>(64)
        for (n in idx) {
            val score = when {
                n.lowerName == q -> 1000
                n.lowerName.startsWith(q) -> 700
                // Word boundaries: preceded by a space, underscore, hyphen or
                // a dot extension seam, so "report" matches
                // "Annual_Report.pdf" and "2024 report.txt".
                n.lowerName.contains(" $q") || n.lowerName.contains("_$q") ||
                    n.lowerName.contains("-$q") -> 550
                n.lowerName.contains(q) -> 300
                else -> continue
            }
            // Context = the hit's folder path from the root ("0/Music/Rock"),
            // in the breadcrumb's own "0/" language.
            val parentRel = n.relPath.substringBeforeLast('/', "")
            hits.add(Hit(n.file, n.isDir,
                if (parentRel.isEmpty()) "0/" else "0/$parentRel",
                n.size, n.mtime, score))
        }
        hits.sortWith(compareByDescending<Hit> { it.score }
            .thenBy { it.file.name.length }
            .thenBy { it.file.absolutePath })
        return hits.take(MAX_RESULTS)
    }
}

/** GOAT-PASS 4 (§2): the public face of the private whole-storage search
 *  engine above. AppPrewarm (start-up) warms the index through this so the
 *  Files sheet's first whole-storage query lands on a resident index —
 *  without the engine itself ever leaving this file. The hidden-rule
 *  default (false) matches the sheet's default browsing mode; the sheet's
 *  own open/prewarm paths rebuild under the live rule when it differs. */
object RecursiveFileSearchAccess {
    fun prewarm(root: File) {
        try { RecursiveFileSearch.prewarm(root, includeHidden = false) } catch (e: Exception) { /* best-effort, always */ }
    }
}
