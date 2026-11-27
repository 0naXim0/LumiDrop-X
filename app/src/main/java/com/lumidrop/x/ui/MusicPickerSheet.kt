package com.lumidrop.x.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lumidrop.x.R
import com.lumidrop.x.media.AudioItem
import com.lumidrop.x.media.MediaLibrary
import com.lumidrop.x.settings.SettingsManager
import com.lumidrop.x.transfer.PreviewItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The sort menu's keys (file scope: local enum classes are not legal Kotlin). */
private enum class MusicSortKey(val label: String) { TITLE("Title"), ARTIST("Artist"), DATE("Date added"), DURATION("Duration"), SIZE("Size") }

/** ── Pass-34 (#8): the audio library cache — a reopen within the freshness ───
 *  window renders its FIRST frame fully populated with no MediaStore query at
 *  all (the same discipline as the App picker's app-list cache).
 *  ── GOAT-PASS 7 Part 2 (§4A/§5): the cache is also the INVALIDATION point ──
 *  for the Music sheet's own file operations — every Delete / Rename / Copy /
 *  Cut / Paste clears it (plus a MediaStore rescan of the affected paths) so
 *  the next load reflects the REAL library instead of the 30s window. */
@Volatile private var cachedAudio: List<AudioItem>? = null
@Volatile private var cachedAudioAt = 0L
private const val AUDIO_CACHE_FRESH_MS = 30_000L

/** ── SELECTION FINAL PASS 1 (§4A): the surgical repaint's bulk threshold — ──
 *  above this many changed rows the ordinary full diff (which batches)
 *  beats N individual payload dispatches; the same discipline the Files
 *  sheet's SURGICAL_REPAINT_MAX follows. */
private const val MUSIC_SURGICAL_REPAINT_MAX = 320

/** GOAT-PASS 9 (§5 — live synchronization): cross-surface audio-library
 *  invalidation. The MediaStore observers (a song landed from any app —
 *  camera-adjacent downloads, receives, file managers) and the server's
 *  own landing paths call this so the freshness window can never hide a
 *  newly available track: the next Music-sheet open re-queries MediaStore
 *  instead of painting the stale set. An OPEN sheet reacts through its own
 *  LibrarySync hook (registered in showMusicPickerSheet). Thread-safe —
 *  called from the server's threads and the observer thread, both fields
 *  @Volatile above. */
internal fun clearAudioLibraryCache() {
    cachedAudio = null
    cachedAudioAt = 0L
}

/**
 * ══ Pass-33/34: the MUSIC picker ══
 *
 * Part 1 built the pinned toolbar / card rows / sort menu / artwork / fixed
 * 75% sheet; Part 2 the icon-slot selection, hold-to-select + ranges, the
 * shared playback controller and snapshot-diff row states.
 *
 * ══ Pass-35 changes ══
 *
 *   • #8C: the counter subtitle row under the "Music" title is GONE — the
 *     title row is a single clean line, and the browsing list keeps the
 *     pixels. (The All-tracks row already carries the library totals.)
 *   • #8A: every track's artwork now carries a small corner music-note mark
 *     in a quiet scrim — the same identification language the queue's video
 *     thumbs use — so album artwork can never read as an image thumbnail.
 *   • #8B: folder glyphs render at the Files picker's proportions (a padded
 *     28dp glyph inside the 42dp slot) instead of stretching the 24dp art
 *     frame to a blob, in normal, selected and partial states alike.
 *   • #13: "All tracks" is a REAL folder-like pick — the whole collection
 *     selects as one, opens for per-track deselect, shows the same
 *     FULL (check chip) / PARTIAL (dash chip) states as every other folder,
 *     and queues as one manifest folder of exactly its eligible audio —
 *     never as the physical storage root. Folder picks carry EXCLUSIONS
 *     now (the deselected track keys inside a pick), which is what makes
 *     the partial states and the exact manifest possible.
 *   • #1/#4: the chrome rows are drag regions (empty areas drag the sheet;
 *     controls keep their touches), and Android Back navigates the
 *     folder/All-tracks view up, exits selection mode, then dismisses.
 *   • Range anchors are stored in the ROW key space ("t:<key>"/"fd:<path>")
 *     — the un-prefixed track key the Part 2 code stored never matched a
 *     row, so a range anchored on a track silently degraded to a single
 *     select; that mismatch is fixed.
 *
 * ══ Pass-36 Part 1 changes ══
 *
 *   • #1: the row snapshot carries the FULL playback state (current AND
 *     playing). The old isCurrent-only snapshot never changed on a
 *     pause/resume of the loaded track, so DiffUtil skipped the rebind and
 *     the row's play/pause icon sat stale until the row changed for another
 *     reason (selecting it — the reported symptom). Every playback
 *     transition now rebinds the affected row immediately, driven by the
 *     controller's own state listeners.
 *   • #6: selection mode is the Files sheet's model, verbatim — the circular
 *     corner badge and the artwork accent ring are GONE; a picked track's
 *     art slot becomes the accent check chip exactly as a picked Files row's
 *     icon slot does, and the row card's tint is the second indicator. Tap
 *     and long-press semantics already matched Files (slot toggles, row
 *     opens/toggles, tail plays, long-press range-selects) and are unchanged.
 *   • #3: the toolbar is the two shared compact pills — the selection
 *     counter ("5 selected · 12.4 MB") and the unified All/Clear/Add
 *     segmented control (see Widgets.kt), identical to the Files sheet's.
 */
fun showMusicPickerSheet(
    fragment: Fragment,
    /** ── Pass-40 (#3) + Pass-41 (§6): open the sheet already focused on this
     *  track — the floating player's main-container tap navigates here through
     *  the controller's [MusicPlayback.musicSheetOpener] hook. The reveal
     *  rides the first submit that carries a loaded library, and the track is
     *  matched through its FULL canonical identity set (see focusOnTrack), so
     *  the same song resolves whether it was started from the Music sheet or
     *  from the Queue. Deliberately BEFORE [onAdd] so the Send tile's
     *  existing trailing-lambda call site keeps binding to the add handler. */
    focusTrack: MusicPlayback.Track? = null,
    onAdd: (List<PreviewItem>) -> Unit,
) {
    // ── Pass-37 (#4): one live Music sheet, ever — the same synchronous ───────
    // acquire / dismiss-release guard the App Picker has had since Pass-26
    // (rapid double-taps on the Music tile can no longer stack sheets).
    if (!PickerGate.acquire("music")) return
    val ctx = fragment.requireContext()
    val activity = fragment.activity
    val sheet = BottomSheet(ctx, expandable = true, defaultHeightFraction = 0.75f)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun col(id: Int) = ContextCompat.getColor(ctx, id)
    fun fmtDuration(ms: Long): String {
        if (ms <= 0) return "0:00"
        val total = ms / 1000
        val h = total / 3600; val m = (total % 3600) / 60; val s = total % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }
    val rootDirPath = try {
        @Suppress("DEPRECATION")
        android.os.Environment.getExternalStorageDirectory().absolutePath
    } catch (e: Exception) { "/storage/emulated/0" }

    // ── Model ─────────────────────────────────────────────────────────────────
    var allTracks: List<AudioItem> = emptyList()
    var folders: List<AudioFolder> = emptyList()
    var loaded = false
    /** null = the folder browser; non-null = that folder's tracks ("ALL" = flat library). */
    var openFolder: String? = null
    val ALL_TRACKS = "\u0000ALL"

    /** ── Pass-35 (#13): the All-Tracks pseudo-folder — ONE instance, so the
     *  pick's exclusion set survives across rebuilds; its track list is the
     *  currently ELIGIBLE library (hidden filter applied) at all times.
     *  refreshAllTracksFolder() is declared BELOW the hidden-filter helpers
     *  it reads (locals are strictly before-use). */
    val allTracksFolder = AudioFolder(ALL_TRACKS, "All tracks")

    var query = ""
    val searchSerial = java.util.concurrent.atomic.AtomicInteger(0)

    /** ── Pass-40 (#3) + Pass-41 (§6): a track reveal that is waiting for the ──
     *  library — either the opener's focus track (the sheet was opened BY the
     *  floating player) or a focus request that raced the load. Applied by the
     *  first submitAll() that runs with loaded == true. */
    var pendingFocusTrack: MusicPlayback.Track? = focusTrack

    var sortKey = MusicSortKey.TITLE
    var sortDesc = false

    // ══ GOAT-PASS 10 (§3): the PER-DIRECTORY persistent sorting system — ══
    // the SAME Windows-style contract the Files sheet has had since Pass-40:
    // every browsing scope remembers its own criterion and direction
    // (SharedPreferences, surviving navigation, sheet reopenings and app
    // restarts); changing one scope's order never touches another's; and
    // "Apply to all subfolders" stamps the CURRENT configuration across the
    // current scope's whole subtree. A Music browsing scope is one of:
    //   • the folder list (root)        → keyed by the music root's path
    //   • one audio folder's track view → keyed by the folder's own path
    //   • the All-Tracks flat view       → keyed by the ALL_TRACKS sentinel
    // The store is SEPARATE from Files' (the two sheets' value vocabularies
    // differ — title/artist/date/duration/size vs name/date/size/type — so a
    // path browsed in both sheets keeps two independent configurations).
    // Sorting itself always runs over the LIVE library at render time
    // (buildRows), so MediaStore updates stay correctly ordered under the
    // remembered rule; row identities stay stable keys, so no sort change
    // can ever cost a selection.

    /** The stored "key:dir" string for the CURRENT configuration — the
     *  exact same convention the Files sheet's store uses. */
    fun currentSortValue(): String = "${sortKey.name.lowercase()}:${if (sortDesc) "d" else "a"}"

    /** The CURRENT browsing scope's store key (root list / one folder /
     *  the All-Tracks sentinel). */
    fun currentSortScopeKey(): String = openFolder ?: rootDirPath

    /** Restores [scopeKey]'s remembered sorting (or the title-ascending
     *  default when it has none). Round-trips exactly with
     *  [currentSortValue]. */
    fun applyStoredSort(scopeKey: String) {
        val stored = SettingsManager.getMusicDirSort(ctx, scopeKey) ?: return
        sortKey = when (stored.substringBefore(':', "title")) {
            "artist" -> MusicSortKey.ARTIST
            "date" -> MusicSortKey.DATE
            "duration" -> MusicSortKey.DURATION
            "size" -> MusicSortKey.SIZE
            else -> MusicSortKey.TITLE
        }
        sortDesc = stored.substringAfter(':', "a") == "d"
    }

    // The sheet's initial scope is the root folder list — restore whatever
    // the user last left there (no-op on a fresh install: title, ascending).
    applyStoredSort(rootDirPath)

    /** ── GOAT-PASS 10 (§3): the recursive apply, Music edition. The Music ──
     *  sheet's "subdirectories" are the AUDIO FOLDERS themselves (the
     *  library's buckets — the same universe the root list browses), so the
     *  walk needs no filesystem I/O at all: from the root (or the All-Tracks
     *  library view) the configuration lands on the root scope AND every
     *  audio folder; from inside one folder it lands on that folder AND
     *  every audio folder nested beneath it in the real filesystem
     *  (path-prefix match — exactly the subtree discipline the Files walk
     *  enforces with a queue). Nothing outside the current scope is ever
     *  written, nothing on disk is ever touched, and the CURRENT scope lands
     *  synchronously first (it is the one on screen). */
    fun applySortToAllBelow() {
        val value = currentSortValue()
        val scopeKey = currentSortScopeKey()
        val scopePath = if (scopeKey == ALL_TRACKS) null else scopeKey
        val targets = LinkedHashMap<String, String>(folders.size + 2)
        targets[scopeKey] = value
        if (scopePath == null) {
            // Root / All-Tracks: every audio folder in the library.
            for (f in folders) targets[f.path] = value
        } else {
            // One folder: itself + every bucket nested beneath it.
            val prefix = scopePath.trimEnd('/') + "/"
            for (f in folders) {
                if (f.path == scopePath || f.path.startsWith(prefix)) targets[f.path] = value
            }
        }
        SettingsManager.setMusicDirSortBulk(ctx, targets)
        android.widget.Toast.makeText(ctx,
            "Sorting applied to ${targets.size} folder${if (targets.size != 1) "s" else ""}",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Apply-to-all's confirmation — the same one-tap dialog in the app's
     *  existing modal language the Files sheet uses, because this action
     *  writes a preference into many scopes at once. */
    fun confirmApplySortToAll() {
        val scopeName = when (val k = currentSortScopeKey()) {
            rootDirPath -> "Music"
            ALL_TRACKS -> allTracksFolder.name
            else -> k.trimEnd('/').substringAfterLast('/')
        }
        showLdxDialog(
            context = ctx,
            title = "Apply this sorting to all subfolders?",
            message = "\"${sortKey.label} — ${if (sortDesc) "Descending" else "Ascending"}\" will be remembered for " +
                "\"$scopeName\" and every music folder inside it. " +
                "Files are never moved or changed.",
            iconRes = R.drawable.ic_sort,
            iconTintRes = R.color.accent_light,
            actions = listOf(
                LdxDialogAction("Cancel", LdxButtonStyle.GHOST),
                LdxDialogAction("Apply", LdxButtonStyle.PRIMARY) { applySortToAllBelow() },
            ),
        )
    }
    var showHidden = SettingsManager.isPickerShowHidden(ctx)

    /** Selection: folders (incl. All Tracks) by path — each pick carries its
     *  exclusions — and standalone tracks by uri-or-path key, in pick order. */
    val selectedTracks = LinkedHashMap<String, AudioItem>()
    val selectedFolders = LinkedHashMap<String, AudioFolder>()
    fun selectionMode(): Boolean = selectedTracks.isNotEmpty() || selectedFolders.isNotEmpty()

    // ── SELECTION FINAL PASS 1 (§4C — the incremental selection totals). ────────
    // paintChrome used to recompute the count/size footer over the WHOLE
    // selection on EVERY mutation: every track of every picked folder, with a
    // trackKey() string built per track — and the All-Tracks pick IS the whole
    // library, so a single toggle inside it cost one full-library pass with a
    // string allocation per song. The exact same figures are now served from
    // two caches: the standalone picks' total (a plain sum, no string work —
    // recomputed only when the standalone set actually changed) and ONE
    // (count, bytes) contribution per folder pick (recomputed only when THAT
    // pick's membership or exclusion set changed). A toggle inside All-Tracks
    // now costs one map read. (folderContrib/selectionTotals themselves live
    // just after the selection-model helpers they read — locals are strictly
    // before-use.)
    var standaloneTotalsDirty = true
    var standaloneCountCache = 0
    var standaloneBytesCache = 0L
    val folderContribCache = HashMap<String, LongArray>()   // path -> [count, bytes]
    fun invalidateFolderContrib(path: String) { folderContribCache.remove(path) }
    fun invalidateAllSelTotals() {
        standaloneTotalsDirty = true
        folderContribCache.clear()
    }
    fun invalidateStandaloneTotals() { standaloneTotalsDirty = true }

    // ══ GOAT-PASS 7 Part 2 (§5): the Music sheet's file-operation state ══════
    //
    // The SAME FileOps engine the Files sheet uses (collision-safe names,
    // containment safety, cooperative cancellation, no overwrite — ever),
    // driven from THIS sheet's selection model: a physical folder pick
    // carries its deselected members as the pick's exclusion set; the
    // All-Tracks collection (a smart view, never a directory) resolves to
    // its still-selected member tracks as individual file picks; a
    // standalone track is one file pick. Copy/Cut arm the clipboard and
    // clear the selection; Paste completes into the CURRENTLY OPEN real
    // music folder — and the All-Tracks view is NEVER a paste destination
    // (refused inside the operation itself, not by hiding a menu entry).
    class MusicClipboard(val isCut: Boolean, val picks: List<FileOps.Pick>)
    var musicClipboard: MusicClipboard? = null
    var musicOpJob: kotlinx.coroutines.Job? = null

    /** Range-selection anchor: a ROW key ("t:…" / "fd:…"), validated against
     *  the visible list before every use. */
    var anchorKey: String? = null

    fun trackKey(t: AudioItem): String = t.path ?: t.uri.toString()
    fun artKeyOf(t: AudioItem): String =
        if (t.albumId > 0) "art:${t.albumId}" else "arturi:${t.uri}"
    fun isHiddenTrack(t: AudioItem): Boolean = t.name.startsWith(".") || (t.title ?: "").startsWith(".")
    fun isHiddenFolder(f: AudioFolder): Boolean =
        f.path.removePrefix(rootDirPath).split('/').any { it.startsWith(".") }

    /** Pass-35 (#13): re-syncs the All-Tracks collection's members with the
     *  current eligibility rule (the hidden filter) — the library load and
     *  the hidden toggle both call it. */
    fun refreshAllTracksFolder() {
        allTracksFolder.tracks.clear()
        allTracksFolder.tracks.addAll(if (showHidden) allTracks else allTracks.filter { !isHiddenTrack(it) })
        // ── SELECTION FINAL PASS 1 (§4C): the membership changed — the cached ──
        // byte total and this pick's contribution go with it.
        allTracksFolder.invalidateBytesCache()
        invalidateFolderContrib(ALL_TRACKS)
    }

    /** The pick that covers [key]'s track — All Tracks covers every eligible
     *  track (its track list IS the eligible library), a folder pick covers
     *  its own members. Null = standalone. */
    fun coveringPickFor(key: String): AudioFolder? =
        selectedFolders.values.firstOrNull { f -> f.tracks.any { trackKey(it) == key } }

    /** The pick's exact (count, bytes) contribution — its full total minus
     *  every excluded member (an excluded key that is no longer a member
     *  simply contributes nothing, exactly like the old live loop). */
    fun folderContrib(f: AudioFolder): LongArray {
        folderContribCache[f.path]?.let { return it }
        var count = 0L; var bytes = 0L
        for (t in f.tracks) {
            val k = trackKey(t)
            if (k in f.excluded) continue
            count++; bytes += t.size
        }
        val out = longArrayOf(count, bytes)
        folderContribCache[f.path] = out
        return out
    }
    /** The whole selection's (count, bytes) — the ONE reader the footer uses. */
    fun selectionTotals(): LongArray {
        if (standaloneTotalsDirty) {
            var c = 0L; var b = 0L
            for (t in selectedTracks.values) { c++; b += t.size }
            standaloneCountCache = c.toInt(); standaloneBytesCache = b
            standaloneTotalsDirty = false
        }
        var count = standaloneCountCache.toLong()
        var bytes = standaloneBytesCache
        for (f in selectedFolders.values) {
            val contrib = folderContrib(f)
            count += contrib[0]; bytes += contrib[1]
        }
        return longArrayOf(count, bytes)
    }

    /** Effective selected state of one track (standalone pick, or covered by
     *  a folder/All-Tracks pick that hasn't excluded it). */
    fun trackIsOn(key: String): Boolean {
        val cover = coveringPickFor(key)
        if (cover != null) return key !in cover.excluded
        return selectedTracks.containsKey(key)
    }

    /** A folder row's three-state verdict: 2 = FULL, 1 = PARTIAL, 0 = NONE.
     *  A picked folder reads its own exclusions; an unpicked folder inherits
     *  only from what covers its tracks among its OWN picks — standalone
     *  track picks and regular folder picks. ── Pass-39 (#2): the ALL-TRACKS
     *  pick deliberately does NOT leak into other containers' rows. Selecting
     *  All Tracks highlights exactly the All-Tracks row; every other folder
     *  keeps its own independent state (the reported "selecting All Tracks
     *  lights up every container" was this inheritance rule firing for a
     *  pick that covers the whole library). The tracks themselves remain
     *  honestly covered inside an opened folder — that is the exclusion
     *  carve-out interaction, and the All-Tracks row's own PARTIAL chip
     *  depends on it. */
    fun folderRowState(f: AudioFolder): Int {
        val picked = selectedFolders[f.path]
        if (picked != null) return if (picked.excluded.isEmpty()) 2 else 1
        var any = false; var all = true
        for (t in f.tracks) {
            val k = trackKey(t)
            val on = when (val cover = coveringPickFor(k)) {
                allTracksFolder -> false   // the All-Tracks pick is not THIS folder's own state
                null -> selectedTracks.containsKey(k)
                else -> k !in cover.excluded
            }
            if (on) any = true else all = false
            if (any && !all) return 1
        }
        return if (any && all) 2 else 0
    }

    // ── Chrome (the pinned toolbar) ───────────────────────────────────────────
    val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    val titleView = TextView(ctx).apply {
        text = "Music"; textSize = 16.5f; setTextColor(col(R.color.text_primary)); maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
    }
    // Back-to-folders affordance — visible only inside a folder/All-tracks
    // view, on its own touch target. (Pass-35 #8C: the counter subtitle that
    // used to sit under this title is gone; the row is one clean line.)
    val upBtn = Widgets.iconButton(ctx, R.drawable.ic_chevron_right, col(R.color.text_secondary),
        sizeDp = 30, iconSizeDp = 16, contentDescription = "Back to folders").apply {
        rotation = 180f
        visibility = View.GONE
    }
    val titleRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(12), ctx.dp(2), ctx.dp(18), 0)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        addView(upBtn)
        addView(titleView)
    }
    root.addView(titleRow)

    val controls = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(18), ctx.dp(8), ctx.dp(18), 0)
    }
    val searchBox: EditText = Widgets.inputField(ctx, "", android.text.InputType.TYPE_CLASS_TEXT).apply {
        hint = "Search music"
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = ctx.dp(8) }
    }
    val sortBtn = Widgets.iconButton(ctx, R.drawable.ic_more, col(R.color.accent_light),
        sizeDp = 40, iconSizeDp = 19, contentDescription = "Sort and more")
    controls.addView(searchBox); controls.addView(sortBtn)
    root.addView(controls)

    // ── Pass-36 (#3): the same two compact pills the Files sheet uses — the ───
    // selection counter (#3A) and the unified All/Clear/Add segmented control
    // (#3B). Identical design, identical proportions: one picker system.
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

    val list = RecyclerView(ctx).apply {
        layoutManager = LinearLayoutManager(ctx)
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        clipToPadding = false
        setPadding(ctx.dp(14), ctx.dp(4), ctx.dp(14), ctx.dp(10))
        (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
    }
    root.addView(FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        addView(list)
        addView(View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                orientation = android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(col(R.color.bg_sheet), (col(R.color.bg_sheet) and 0x00FFFFFF))
            }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(10), Gravity.TOP)
        })
    })

    /** Row identity model for the adapter — folders, tracks, the All-tracks
     *  entry — one stable key each, plus the SNAPSHOT state flags DiffUtil
     *  compares (picked / partial / playing). Declared BEFORE the slots
     *  below, whose types mention it (locals are strictly before-use). */
    class Row(val key: String, val folder: AudioFolder?, val track: AudioItem?, val isAllEntry: Boolean = false) {
        var picked: Boolean = false
        var partial: Boolean = false
        var playing: Boolean = false
    }

    // ── Behavior slots ────────────────────────────────────────────────────────
    var repaint: () -> Unit = {}
    var openFolderFn: (String?) -> Unit = {}
    var toggleTrackFn: (AudioItem) -> Unit = {}
    var toggleFolderFn: (AudioFolder) -> Unit = {}
    var playFn: (AudioItem) -> Unit = {}
    var longPressFn: (Row) -> Unit = {}

    /** The adapter's current rows — the DiffUtil base. */
    var shown: List<Row> = emptyList()

    // ── SELECTION FINAL PASS 1 (§4B): the selection-only payload and the ──
    // shared, create-once drawables its rebind uses — declared BEFORE the
    // adapter (locals are strictly before-use) so both the adapter's payload
    // path and the surgical repaint can reach them.
    val MusicSelPayload = Any()   // a marker instance — Kotlin forbids local `object` declarations
    // ── PASS 25 (the native half of the Pass-24 selection redesign): the ──────
    // art slot's accent CHECK/DASH CHIP is RETIRED. The chip model — a picked
    // row's artwork was hidden and replaced by a solid-accent rounded square
    // with a check (FULL) or dash (PARTIAL) glyph — was the exact native twin
    // of the on-thumbnail badge the Web Client's Pass 24 deleted: with the
    // artwork gone, similar albums/tracks became indistinguishable exactly
    // while the user was selecting them. The artwork (or the folder/notes
    // glyph where there is no art) now stays visible in EVERY state, and
    // selection is the SAME language the web client settled on: a thin 2dp
    // accent ring drawn as a pointer-transparent overlay ON TOP of the art
    // slot (rounded to the slot's own 8dp radius, transparent fill — covers
    // no content, changes no geometry) plus the card tint the row already
    // carried. FULL = solid-accent ring; PARTIAL = the quieter accent_light
    // ring at 66 alpha, matching the card-tint family contrast. Shared,
    // created once per sheet session: a flip allocates nothing.
    val musicRingFull = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = ctx.dpf(8f)
        setStroke(ctx.dp(2), col(R.color.accent))
    }
    val musicRingPartial = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = ctx.dpf(8f)
        setStroke(ctx.dp(2), (col(R.color.accent_light) and 0x00FFFFFF) or 0x66000000)
    }
    val musicIdleArtBg = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = ctx.dpf(8f)
        setColor(0x14FFFFFF)
    }

    fun buildRows(): List<Row> {
        val rows = ArrayList<Row>()
        if (openFolder == null) {
            var fs = if (showHidden) folders else folders.filter { !isHiddenFolder(it) }
            val searching = query.isNotBlank()
            if (searching) {
                // ── GOAT-PASS 7 Part 2 (§4B): a search from the folders view ──
                // finds the SONGS THEMSELVES. Every track of the whole
                // library whose title, artist or file name matches is its
                // own playable, selectable row — the user no longer has to
                // open a folder to reach the song they already searched
                // for. Folders whose own NAME matches still appear (a
                // useful folder result), but never hide or replace the
                // matching song rows: the TRACKS come first, the folders
                // after them. The whole pass is over the resident in-memory
                // library — no rescan, no MediaStore round-trip, the same
                // 120ms debounce rhythm the sheet always had.
                val q = query.trim().lowercase()
                val trackCmp = when (sortKey) {
                    MusicSortKey.TITLE -> compareBy<AudioItem> { (it.title ?: it.name).lowercase() }
                    MusicSortKey.ARTIST -> compareBy<AudioItem> { it.artist?.lowercase() ?: "" }
                    MusicSortKey.DATE -> compareBy<AudioItem> { it.dateAddedSec }
                    MusicSortKey.DURATION -> compareBy<AudioItem> { it.durationMs }
                    MusicSortKey.SIZE -> compareBy<AudioItem> { it.size }
                }
                val library = if (showHidden) allTracks else allTracks.filter { !isHiddenTrack(it) }
                val matchedTracks = library.filter { t ->
                    (t.title ?: t.name).lowercase().contains(q) ||
                        (t.artist ?: "").lowercase().contains(q) ||
                        t.name.lowercase().contains(q)
                }.sortedWith(Comparator { a, b ->
                    if (sortDesc) trackCmp.compare(b, a) else trackCmp.compare(a, b)
                })
                for (t in matchedTracks) {
                    val key = trackKey(t)
                    val r = Row("t:$key", null, t)
                    r.picked = trackIsOn(key)
                    r.playing = MusicPlayback.isCurrent(key) && MusicPlayback.isPlaying
                    rows.add(r)
                }
                // The folder results: only folders whose OWN name matches —
                // a folder that merely CONTAINS a match is redundant now
                // (its song is already a row above).
                fs = fs.filter { f -> f.name.lowercase().contains(q) }
                val folderCmp = when (sortKey) {
                    MusicSortKey.TITLE -> compareBy<AudioFolder> { it.name.lowercase() }
                    MusicSortKey.ARTIST -> compareBy<AudioFolder> { it.tracks.firstOrNull()?.artist?.lowercase() ?: "" }
                    MusicSortKey.DATE -> compareBy<AudioFolder> { f -> f.tracks.maxOf { it.dateAddedSec } }
                    MusicSortKey.DURATION -> compareBy<AudioFolder> { it.tracks.size }
                    MusicSortKey.SIZE -> compareBy<AudioFolder> { it.bytes }
                }
                val sortedFolders = fs.sortedWith(Comparator { a, b ->
                    if (sortDesc) folderCmp.compare(b, a) else folderCmp.compare(a, b)
                })
                sortedFolders.forEach {
                    val r = Row(it.key, it, null)
                    val state = folderRowState(it)
                    r.picked = state > 0
                    r.partial = state == 1
                    rows.add(r)
                }
                // The All-Tracks container row is deliberately absent during
                // a search: it is a VIEW over everything, not a result, and
                // its matching members are already individual rows above.
                return rows
            }
            val keyCmp = when (sortKey) {
                MusicSortKey.TITLE -> compareBy<AudioFolder> { it.name.lowercase() }
                MusicSortKey.ARTIST -> compareBy<AudioFolder> { it.tracks.firstOrNull()?.artist?.lowercase() ?: "" }
                MusicSortKey.DATE -> compareBy<AudioFolder> { f -> f.tracks.maxOf { it.dateAddedSec } }
                MusicSortKey.DURATION -> compareBy<AudioFolder> { it.tracks.size }
                MusicSortKey.SIZE -> compareBy<AudioFolder> { it.bytes }
            }
            val sorted = fs.sortedWith(Comparator { a, b ->
                if (sortDesc) keyCmp.compare(b, a) else keyCmp.compare(a, b)
            })
            if (sorted.isNotEmpty() || allTracksFolder.tracks.isNotEmpty()) {
                // ── Pass-35 (#13): the All-Tracks container is a selectable ──
                // folder-like row: the whole collection, one pick, with the
                // same FULL/PARTIAL chip states as every other folder.
                val allRow = Row("all", allTracksFolder, null, isAllEntry = true)
                allRow.picked = selectedFolders.containsKey(ALL_TRACKS)
                allRow.partial = allRow.picked && allTracksFolder.excluded.isNotEmpty()
                rows.add(allRow)
            }
            sorted.forEach {
                val r = Row(it.key, it, null)
                val state = folderRowState(it)
                r.picked = state > 0
                r.partial = state == 1
                rows.add(r)
            }
        } else {
            var ts = when (openFolder) {
                ALL_TRACKS -> allTracksFolder.tracks
                else -> folders.firstOrNull { it.path == openFolder }?.tracks ?: emptyList()
            }
            if (query.isNotBlank()) {
                val q = query.trim().lowercase()
                ts = ts.filter { (it.title ?: it.name).lowercase().contains(q) || (it.artist ?: "").lowercase().contains(q) }
            }
            val keyCmp = when (sortKey) {
                MusicSortKey.TITLE -> compareBy<AudioItem> { (it.title ?: it.name).lowercase() }
                MusicSortKey.ARTIST -> compareBy<AudioItem> { it.artist?.lowercase() ?: "" }
                MusicSortKey.DATE -> compareBy<AudioItem> { it.dateAddedSec }
                MusicSortKey.DURATION -> compareBy<AudioItem> { it.durationMs }
                MusicSortKey.SIZE -> compareBy<AudioItem> { it.size }
            }
            val sorted = ts.sortedWith(Comparator { a, b ->
                if (sortDesc) keyCmp.compare(b, a) else keyCmp.compare(a, b)
            })
            sorted.forEach {
                val key = trackKey(it)
                val r = Row("t:$key", null, it)
                r.picked = trackIsOn(key)
                // ── Pass-36 (#1): the snapshot carries the FULL playback state ──
                // (current AND playing). The old isCurrent-only snapshot never
                // changed on a pause/resume of the loaded track, so DiffUtil
                // saw an identical row and skipped the rebind — the row's
                // play/pause icon sat stale until something ELSE changed the
                // row (selecting it — the exact reported symptom). With the
                // complete state in the snapshot, every playback transition
                // (start/pause/resume/stop/track change/completion/seek/
                // interruption — all of which fire notifyState → this
                // rebuild) is a real content change on exactly the affected
                // row, and the icon rebinds immediately.
                r.playing = MusicPlayback.isCurrent(key) && MusicPlayback.isPlaying
                rows.add(r)
            }
        }
        return rows
    }

    // ── The adapter ───────────────────────────────────────────────────────────
    val TYPE_ROW = 0; val TYPE_STATE = 1
    val listAdapter = object : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        init { setHasStableIds(true) }
        private fun rowsVisible(): Boolean = loaded && shown.isNotEmpty()
        override fun getItemViewType(position: Int) = if (rowsVisible()) TYPE_ROW else TYPE_STATE
        override fun getItemCount(): Int = if (rowsVisible()) shown.size else 1
        override fun getItemId(position: Int): Long =
            if (rowsVisible()) shown[position].key.hashCode().toLong() else -1L

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == TYPE_STATE) {
                return object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                    textSize = 12.5f; setTextColor(col(R.color.text_muted))
                    gravity = Gravity.CENTER; setPadding(0, ctx.dp(36), 0, ctx.dp(36))
                }) {}
            }
            val c = parent.context
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(c.dp(10), c.dp(8), c.dp(8), c.dp(8))
                isClickable = true; isFocusable = true
                isLongClickable = true   // hold-to-select / range selection
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = c.dpf(12f)
                    setColor(col(R.color.bg_deep))
                    setStroke(c.dp(1), 0x1FFFFFFF)
                }
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.bottomMargin = c.dp(6) }
            }
            // The art slot: a rounded thumbnail frame. Its CONTENT is now
            // state-independent (PASS 25 — artwork or glyph in every state);
            // selection is the ring overlay stacked on top of it, plus the
            // row card's tint.
            val artFrame = FrameLayout(c).apply {
                layoutParams = LinearLayout.LayoutParams(c.dp(42), c.dp(42)).also { it.marginEnd = c.dp(12) }
                isClickable = true; isFocusable = true
            }
            val art = ImageView(c).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = c.dpf(8f)
                    setColor(0x14FFFFFF)
                }
                // ── GOAT-PASS 7 Part 2 (§7B): the artwork is clipped by the ──
                // view itself (clipToOutline + a round-rect outline provider)
                // so the 8dp corner radius is EXACT on every bitmap size —
                // the paint-level rounding alone let the effective radius
                // shrink with the CENTER_CROP matrix (as tight as ~4dp on a
                // larger decoded bitmap), which read as sharp edges.
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, view.context.dpf(8f))
                    }
                }
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
            // ── Pass-36 (#6): the circular selection BADGE is GONE. ──
            // ── PASS 25: so is the accent CHECK CHIP that replaced the ──
            // artwork. Selection is the ring overlay below (over the intact
            // artwork) plus the row card's tint — no separate checkbox
            // anywhere, no artwork hiding anywhere.
            // ── Pass-35 (#8A): the audio identity mark — a small music note ──
            // in a quiet rounded scrim at the art slot's TOP-end corner, the
            // same identification language the queue's video thumbs use.
            val audioMark = ImageView(c).apply {
                setImageDrawable(Widgets.icon(c, R.drawable.ic_audio, 0xE6FFFFFF.toInt()))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = c.dpf(4f)
                    setColor(0x66000000.toInt())
                }
                setPadding(c.dp(2), c.dp(2), c.dp(3), c.dp(2))
                visibility = View.GONE
                layoutParams = FrameLayout.LayoutParams(c.dp(16), c.dp(16),
                    Gravity.TOP or Gravity.END)
            }
            // ── PASS 25: the selection ring overlay — GONE until a picked /
            // partial bind turns it on. A plain non-clickable View over the
            // artwork: it paints the thin accent stroke, covers nothing and
            // never intercepts the art frame's toggle touch.
            val musicSelRing = View(c).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                visibility = View.GONE
            }
            artFrame.addView(art); artFrame.addView(audioMark); artFrame.addView(musicSelRing)
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
            // The trailing control slot: a folder's chevron (its dedicated
            // open affordance) or a track's play/pause — never both.
            val tail = ImageView(c).apply {
                layoutParams = LinearLayout.LayoutParams(c.dp(38), c.dp(38))
                isClickable = true; isFocusable = true
                setPadding(c.dp(10), 0, c.dp(2), 0)
            }
            row.addView(artFrame); row.addView(texts); row.addView(tail)
            return object : RecyclerView.ViewHolder(row) {}
        }

        // ── SELECTION FINAL PASS 1 (§4B): the selection-only payload rebind. ──
        // A picked/partial flip touches nothing but the row's selection
        // chrome — the card tint, the art slot's ring overlay, and the folder
        // chevron's picked-only visibility — so the payload path rebinds
        // exactly those and leaves the artwork, the texts, the audio mark,
        // the listeners and every other part of the row exactly as the last
        // full bind left them. Shared, create-once drawables
        // (musicRingFull / musicRingPartial / musicIdleArtBg, declared above
        // the adapter) keep the flip allocation-free.
        // ── PASS 25: the artwork is no longer touched AT ALL on a flip — ──
        // it was never removed, so there is nothing to restore; select and
        // deselect each cost one card-color mutation and one ring visibility
        // flip, never an artwork re-dispatch, never a cache read.
        fun bindSelChrome(holder: RecyclerView.ViewHolder, r: Row) {
            val row = holder.itemView as LinearLayout
            val artFrame = row.getChildAt(0) as FrameLayout
            val selRing = artFrame.getChildAt(2) as View
            val texts = row.getChildAt(1) as LinearLayout
            val name = texts.getChildAt(0) as TextView
            val tail = row.getChildAt(2) as ImageView
            val playing = r.playing && MusicPlayback.isPlaying
            // Card tint (the paintCard discipline from the full bind).
            (row.background as? android.graphics.drawable.GradientDrawable)?.let { bg ->
                when {
                    r.picked && r.partial -> {
                        bg.setColor((col(R.color.accent) and 0x00FFFFFF) or 0x15000000)
                        bg.setStroke(ctx.dp(1), (col(R.color.accent_light) and 0x00FFFFFF) or 0x42000000)
                    }
                    r.picked -> {
                        bg.setColor((col(R.color.accent) and 0x00FFFFFF) or 0x26000000)
                        bg.setStroke(ctx.dp(1), (col(R.color.accent_light) and 0x00FFFFFF) or 0x66000000)
                    }
                    else -> {
                        bg.setColor(col(R.color.bg_deep))
                        bg.setStroke(ctx.dp(1), 0x1FFFFFFF)
                    }
                }
            }
            name.setTextColor(col(if (playing) R.color.accent_light else R.color.text_primary))
            // ── PASS 25: the selection ring over the (intact) artwork — ──
            // one visibility flip plus at most one shared-drawable
            // assignment; FULL is the solid-accent ring, PARTIAL the
            // quieter accent_light one, and the artwork underneath never
            // changes.
            if (r.picked) {
                selRing.background = if (r.partial) musicRingPartial else musicRingFull
                selRing.visibility = View.VISIBLE
            } else {
                selRing.visibility = View.GONE
            }
            // The folder chevron's picked-only visibility (GOAT-PASS 7 §2D)
            // rides the same payload path it always did.
            if (r.isAllEntry || r.folder != null) {
                tail.visibility = if (r.picked) View.VISIBLE else View.INVISIBLE
                tail.isClickable = r.picked
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.contains(MusicSelPayload) && rowsVisible()) {
                bindSelChrome(holder, shown[position])
                return
            }
            super.onBindViewHolder(holder, position, payloads)
            onBindViewHolder(holder, position)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (!rowsVisible()) {
                (holder.itemView as TextView).text = when {
                    !loaded -> "Loading music…"
                    query.isNotBlank() -> "No tracks match \"${query.trim()}\""
                    openFolder == null -> "No audio found on this device"
                    else -> "No tracks in this folder"
                }
                return
            }
            val r = shown[position]
            val row = holder.itemView as LinearLayout
            val artFrame = row.getChildAt(0) as FrameLayout
            val art = artFrame.getChildAt(0) as ImageView
            val audioMark = artFrame.getChildAt(1) as ImageView
            val texts = row.getChildAt(1) as LinearLayout
            val name = texts.getChildAt(0) as TextView
            val meta = texts.getChildAt(1) as TextView
            val tail = row.getChildAt(2) as ImageView

            fun idleArt() {
                art.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = ctx.dpf(8f)
                    setColor(0x14FFFFFF)
                }
                audioMark.visibility = View.GONE
                art.setImageDrawable(null)
                art.setPadding(0, 0, 0, 0)
            }
            /** ── PASS 25: the glyph bind for slots with no artwork — the ──
             *  state-independent replacement of the Pass-35 three-state chip:
             *  the glyph shows in EVERY state (picked or not); the ring
             *  overlay on top of it (see paintCard) is the only thing that
             *  moves with the selection. A 28dp glyph inside the 42dp slot —
             *  the Files picker's proportions, not a stretched blob. */
            fun glyphArt(glyphRes: Int, glyphTint: Int) {
                idleArt()
                art.setImageDrawable(Widgets.icon(ctx, glyphRes, glyphTint))
                art.setPadding(ctx.dp(7), ctx.dp(7), ctx.dp(7), ctx.dp(7))
            }
            fun paintCard(picked: Boolean, partial: Boolean, playing: Boolean) {
                (row.background as? android.graphics.drawable.GradientDrawable)?.let { bg ->
                    when {
                        picked && partial -> {
                            // The distinct in-between: same family, quieter
                            // fill + stroke than FULL.
                            bg.setColor((col(R.color.accent) and 0x00FFFFFF) or 0x15000000)
                            bg.setStroke(ctx.dp(1), (col(R.color.accent_light) and 0x00FFFFFF) or 0x42000000)
                        }
                        picked -> {
                            bg.setColor((col(R.color.accent) and 0x00FFFFFF) or 0x26000000)
                            bg.setStroke(ctx.dp(1), (col(R.color.accent_light) and 0x00FFFFFF) or 0x66000000)
                        }
                        else -> {
                            bg.setColor(col(R.color.bg_deep))
                            bg.setStroke(ctx.dp(1), 0x1FFFFFFF)
                        }
                    }
                }
                // ── PASS 25: the selection ring over the artwork — the ──
                // full bind flips it here, the payload rebind flips it in
                // bindSelChrome; both read the same shared drawables, so the
                // two paths can never drift. The artwork underneath is
                // untouched by the state.
                val selRing = artFrame.getChildAt(2) as View
                if (picked) {
                    selRing.background = if (partial) musicRingPartial else musicRingFull
                    selRing.visibility = View.VISIBLE
                } else {
                    selRing.visibility = View.GONE
                }
                name.setTextColor(col(if (playing) R.color.accent_light else R.color.text_primary))
            }

            if (r.isAllEntry) {
                // ── Pass-35 (#13): "All tracks" — a real folder-like row: ──
                // selectable as one collection (FULL/PARTIAL ring states),
                // openable via the row and the chevron, exactly like any
                // folder. PASS 25: the notes glyph stays visible in every
                // state; the ring + card tint carry the selection.
                glyphArt(R.drawable.ic_audio, col(R.color.accent_light))
                name.text = allTracksFolder.name
                val n = allTracksFolder.tracks.size
                meta.text = "$n track${if (n != 1) "s" else ""} · ${android.text.format.Formatter.formatShortFileSize(ctx, allTracksFolder.bytes)}"
                tail.setImageDrawable(Widgets.icon(ctx, R.drawable.ic_chevron_right, col(R.color.text_muted)))
                // ── GOAT-PASS 7 Part 2 (§2D): the opening chevron appears ──
                // ONLY while the collection is actually SELECTED — the same
                // folder-interaction contract the Files sheet now follows.
                // An unselected container's body opens it (no selection
                // active) or toggles it (one active), and an invisible-but-
                // clickable chevron would shadow that body behavior, so the
                // tail is non-clickable whenever it is not the live
                // affordance. INVISIBLE keeps the slot — no text reflow.
                tail.visibility = if (r.picked) View.VISIBLE else View.INVISIBLE
                tail.isClickable = r.picked
                artFrame.setOnClickListener { toggleFolderFn(allTracksFolder) }
                row.setOnClickListener { if (!selectionMode()) openFolderFn(ALL_TRACKS) else toggleFolderFn(allTracksFolder) }
                row.setOnLongClickListener { longPressFn(r); true }
                tail.setOnClickListener { openFolderFn(ALL_TRACKS) }
                paintCard(r.picked, r.partial, false)
            } else if (r.folder != null) {
                val folder = r.folder
                // ── GOAT-PASS 9 (§12 — album covers on folder rows): the ──
                // folder/album row's art slot now shows its FIRST TRACK's
                // album cover (the same shared cache, decode and pending-
                // dispatch the track rows use — one decode serves the whole
                // album) instead of a bare folder glyph. PASS 25: the cover
                // (or the folder glyph when there is no art) stays visible in
                // EVERY state — the ring + card tint carry the selection; a
                // folder with no tracks (or a decode that genuinely finds no
                // art anywhere) falls back to the folder glyph exactly as
                // before.
                val coverTrack = folder.tracks.firstOrNull()
                if (coverTrack != null) {
                    art.background = android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                        cornerRadius = ctx.dpf(8f)
                        setColor(0x14FFFFFF)
                    }
                    art.setPadding(0, 0, 0, 0)
                    val fkey = artKeyOf(coverTrack)
                    art.tag = fkey
                    bindAlbumArt(ctx, scope, art, fkey, coverTrack.uri, coverTrack.albumId, ctx.dp(42), col(R.color.text_muted))
                } else {
                    glyphArt(R.drawable.ic_folder, col(R.color.accent_light))
                }
                name.text = folder.name
                meta.text = "${folder.tracks.size} track${if (folder.tracks.size != 1) "s" else ""} · ${android.text.format.Formatter.formatShortFileSize(ctx, folder.bytes)}"
                tail.setImageDrawable(Widgets.icon(ctx, R.drawable.ic_chevron_right, col(R.color.text_muted)))
                // The art slot TOGGLES selection; the row opens when nothing
                // is selected and toggles once a selection exists; the
                // chevron — the dedicated open affordance — shows ONLY while
                // the folder is selected (GOAT-PASS 7 §2D, the Files sheet's
                // contract), and is non-clickable otherwise so its area
                // behaves as part of the body (INVISIBLE keeps the slot —
                // no text reflow).
                tail.visibility = if (r.picked) View.VISIBLE else View.INVISIBLE
                tail.isClickable = r.picked
                artFrame.setOnClickListener { toggleFolderFn(folder) }
                row.setOnClickListener { if (!selectionMode()) openFolderFn(folder.path) else toggleFolderFn(folder) }
                row.setOnLongClickListener { longPressFn(r); true }
                tail.setOnClickListener { openFolderFn(folder.path) }
                paintCard(r.picked, r.partial, false)
            } else {
                val track = r.track!!
                val key = trackKey(track)
                val picked = r.picked
                val playing = r.playing && MusicPlayback.isPlaying
                // ── PASS 25: the artwork is bound identically in EVERY ──
                // state — picked or not, the album cover stays visible (the
                // native half of the Pass-24 selection redesign: the accent
                // check chip that hid it is retired). The ring overlay plus
                // the card tint (paintCard) are the selection treatment; the
                // audio identity mark (#8A) keeps riding the slot's corner.
                // Artwork: the shared cache + one decode per source; the tag
                // guard keeps a recycled frame from receiving another track's
                // cover.
                art.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = ctx.dpf(8f)
                    setColor(0x14FFFFFF)
                }
                art.setPadding(0, 0, 0, 0)
                art.tag = artKeyOf(track)
                bindAlbumArt(ctx, scope, art, artKeyOf(track), track.uri, track.albumId, ctx.dp(42), col(R.color.text_muted))
                audioMark.visibility = View.VISIBLE
                name.text = track.title ?: track.name
                val bits = mutableListOf<String>()
                track.artist?.let { bits.add(it) }
                if (track.durationMs > 0) bits.add(fmtDuration(track.durationMs))
                bits.add(android.text.format.Formatter.formatShortFileSize(ctx, track.size))
                if (playing) bits.add("playing")
                meta.text = bits.joinToString("  ·  ")
                // ── GOAT-PASS 10 (§7B — the play/pause control can never ──
                // vanish on a recycled row): folder and All-Tracks binds leave
                // the tail INVISIBLE and non-clickable whenever the container
                // is not picked (their chevron is a selected-only affordance),
                // and this row frame is SHARED by every row type — a holder
                // recycled from such a bind carried that state onto the track
                // it was rebound to, whose play/pause control then simply
                // wasn't there (and its click area dead). Every track bind now
                // restores the control explicitly, so the icon, its
                // visibility and its clickability are always the values THIS
                // row type means, never whatever the previous bind left.
                tail.visibility = View.VISIBLE
                tail.isClickable = true
                tail.setImageDrawable(Widgets.icon(ctx,
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                    col(R.color.accent_light)))
                artFrame.setOnClickListener { toggleTrackFn(track) }
                // ── Pass-42 (§5): the body and the icon are DIFFERENT ──
                // affordances, exactly like the Files sheet's model. The
                // ICON (the artwork frame) selects and activates selection
                // mode; the BODY plays the song through the shared music
                // player when nothing is selected, and only toggles
                // selection while a selection is active (the existing
                // selection rules — highlighting, counters, bulk actions —
                // stay exactly as they were). The old body-tap activated
                // selection mode on every plain click, which is the
                // interaction this pass retires.
                row.setOnClickListener {
                    if (selectionMode()) toggleTrackFn(track)
                    else playFn(track)
                }
                row.setOnLongClickListener { longPressFn(r); true }
                tail.setOnClickListener { playFn(track) }
                paintCard(picked, false, playing)
            }
        }
    }
    list.adapter = listAdapter


    // ── List updates ──────────────────────────────────────────────────────────
    fun submitVisible() {
        val newRows = if (loaded) buildRows() else emptyList()
        if (newRows.isEmpty() || shown.isEmpty()) {
            shown = newRows
            listAdapter.notifyDataSetChanged()
            return
        }
        val oldRows = shown
        // ── GOAT-PASS 9 (§10 — large-folder opening): the same wholesale ──
        // dispatch the Files sheet uses for big lists — an O(N) DiffUtil
        // pass over a 1,000+ song folder costs more main-thread time than a
        // full notifyDataSetChanged, which re-lays-out only the visible
        // rows. Below the threshold the incremental diff keeps its precise
        // per-row updates (selection snapshots, playing states).
        if (oldRows.size >= 400 || newRows.size >= 400) {
            shown = newRows
            listAdapter.notifyDataSetChanged()
            return
        }
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldRows.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = oldRows[oldPos].key == newRows[newPos].key
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val a = oldRows[oldPos]; val b = newRows[newPos]
                // GOAT-PASS 9 (§5/§12): a FOLDER row's content includes its
                // live track count — a library reload that kept the folder
                // (same key) but changed its size (a received song landed in
                // it) must rebind the row, not keep the stale count until the
                // next scroll.
                if (a.folder != null || b.folder != null) {
                    return a.picked == b.picked && a.partial == b.partial && a.playing == b.playing &&
                        a.folder?.tracks?.size == b.folder?.tracks?.size
                }
                return a.picked == b.picked && a.partial == b.partial && a.playing == b.playing &&
                    (a.track == null || b.track == null || a.track.id == b.track.id)
            }
        })
        shown = newRows
        diff.dispatchUpdatesTo(listAdapter)
    }

    // ── Behavior, now that every view exists ──────────────────────────────────
    fun paintChrome() {
        val inFolder = openFolder != null
        titleView.text = when {
            !inFolder -> "Music"
            openFolder == ALL_TRACKS -> allTracksFolder.name
            else -> openFolder!!.trimEnd('/').substringAfterLast('/')
        }
        // ── SELECTION FINAL PASS 1 (§4C): the count/size footer reads the ──
        // incremental totals (see selectionTotals) instead of re-walking
        // every track of every picked folder on every mutation.
        val totals = selectionTotals()
        val trackCount = totals[0].toInt()
        val bytes = totals[1]
        // ── Pass-36 (#3A): the compact count pill — totals first, size only ──
        // when it exists (the same format the Files sheet uses).
        footerInfo.text = buildString {
            append("${selectedFolders.size + trackCount} selected")
            if (bytes > 0) append(" · ${android.text.format.Formatter.formatShortFileSize(ctx, bytes)}")
        }
        // ── GOAT-PASS 7 Part 2 (§5): with nothing selected, an armed ──
        // clipboard owns the counter line (the same paste hint the Files
        // sheet shows) — its picks are the selection's successor.
        if (selectedFolders.isEmpty() && selectedTracks.isEmpty()) {
            musicClipboard?.let { cb ->
                footerInfo.text = "${cb.picks.size} ${if (cb.isCut) "cut" else "copied"} — tap ⋮ → Paste in a destination folder"
            }
        }
        // ── GOAT-PASS 14 Phase 2 (§5): the affirmative segment's word tracks the ──
        // LIVE Auto Send setting on every chrome repaint (paintChrome is this
        // sheet's single selection/refresh writer — every toggle, submit and
        // navigation funnels through it): "Add" to the queue when Auto Send is
        // off, "Send" immediately when it is on. Label only; the action,
        // geometry and enabled state are untouched.
        actionPill.setAffirmativeLabel(quickShareActionVerb(ctx))
        actionPill.setAddEnabled(selectionMode())
        upBtn.visibility = if (inFolder) View.VISIBLE else View.GONE
    }

    fun submitAll() { submitVisible(); paintChrome() }

    // ── SELECTION FINAL PASS 1 (§4A/§4B — the surgical selection repaint): ──
    // the Music sheet used to run the FULL repaint (a fresh buildRows + a
    // DiffUtil pass over the whole list) on EVERY tap and every crossed Hold
    // & Slide row — the exact per-interaction cost that grew with the
    // collection and made large selections sluggish. The surgical path
    // re-reads each visible row's LIVE state, rebinds ONLY the rows whose
    // picked/partial flags actually moved (a payload rebind — nothing but
    // the row's selection chrome; no text rewrites, no listener re-wiring,
    // no artwork re-dispatch beyond the chip↔art flip that IS the selection
    // visual), and settles the footer from the incremental totals (§4C).
    // Bulk changes beyond the threshold fall back to the ordinary full
    // diff, exactly like the Files sheet's own surgical repaint.
    /** A row's live (picked, partial) verdict — the same reads buildRows uses.
     *  (Declared BEFORE repaintSelection — Kotlin locals are strictly
     *  before-use.) */
    fun rowSelFlags(r: Row): Pair<Boolean, Boolean> = when {
        r.folder != null -> {
            val state = folderRowState(r.folder)
            (state != 0) to (state == 1)
        }
        r.track != null -> trackIsOn(trackKey(r.track)) to false
        else -> selectedFolders.containsKey(ALL_TRACKS) to
            (selectedFolders.containsKey(ALL_TRACKS) && allTracksFolder.excluded.isNotEmpty())
    }

    fun repaintSelection() {
        if (!loaded || shown.isEmpty()) { submitVisible(); paintChrome(); return }
        var changed = 0
        for (i in shown.indices) {
            val r = shown[i]
            val (picked, partial) = rowSelFlags(r)
            if (picked != r.picked || partial != r.partial) {
                r.picked = picked; r.partial = partial
                listAdapter.notifyItemChanged(i, MusicSelPayload)
                changed++
                if (changed > MUSIC_SURGICAL_REPAINT_MAX) {
                    submitVisible()   // bulk change — the full diff path batches better
                    paintChrome()
                    return
                }
            }
        }
        paintChrome()
    }

    fun toggleTrack(track: AudioItem, silent: Boolean = false) {
        val key = trackKey(track)
        val rowKey = "t:$key"
        val cover = coveringPickFor(key)
        if (cover != null) {
            // ── Pass-35 (#13): inside a picked folder/All-Tracks, a track ──
            // toggle is an EXCLUSION flip on that pick — deselect keeps the
            // rest of the collection selected (the partial state), reselect
            // restores the member.
            if (key in cover.excluded) { cover.excluded.remove(key); anchorKey = rowKey }
            else { cover.excluded.add(key); if (anchorKey == rowKey) anchorKey = null }
            invalidateFolderContrib(cover.path)   // SELECTION FINAL PASS 1 (§4C)
        } else if (selectedTracks.containsKey(key)) {
            selectedTracks.remove(key)
            invalidateStandaloneTotals()           // SELECTION FINAL PASS 1 (§4C)
            if (anchorKey == rowKey) anchorKey = null
        } else {
            selectedTracks[key] = track
            invalidateStandaloneTotals()           // SELECTION FINAL PASS 1 (§4C)
            anchorKey = rowKey
        }
        // ── SELECTION FINAL PASS 1 (§4D): [silent] is the drag fast path — ──
        // the caller applies its own single-row payload update and defers
        // the scan to the gesture end.
        if (!silent) repaintSelection()
    }

    fun toggleFolder(folder: AudioFolder) {
        if (selectedFolders.containsKey(folder.path)) {
            selectedFolders.remove(folder.path)
            invalidateFolderContrib(folder.path)     // SELECTION FINAL PASS 1 (§4C)
            if (anchorKey == folder.key) anchorKey = null
        } else {
            // A fresh pick starts FULL (no stale exclusions), and subsumes
            // the standalone track picks inside it.
            folder.excluded.clear()
            selectedFolders[folder.path] = folder
            invalidateFolderContrib(folder.path)     // SELECTION FINAL PASS 1 (§4C)
            folder.tracks.forEach { selectedTracks.remove(trackKey(it)) }
            invalidateStandaloneTotals()             // SELECTION FINAL PASS 1 (§4C)
            anchorKey = folder.key
        }
        repaintSelection()
    }

    /** Additive select (range / Select All): ON, never off. */
    fun selectTrackOn(track: AudioItem) {
        val key = trackKey(track)
        val cover = coveringPickFor(key)
        if (cover != null) {
            cover.excluded.remove(key)
            invalidateFolderContrib(cover.path)   // SELECTION FINAL PASS 1 (§4C)
        } else {
            selectedTracks[key] = track
            invalidateStandaloneTotals()           // SELECTION FINAL PASS 1 (§4C)
        }
    }

    /** Additive folder select (range / Select All): FULL, never off. */
    fun selectFolderOn(folder: AudioFolder) {
        val picked = selectedFolders[folder.path]
        if (picked != null) {
            picked.excluded.clear()
            invalidateFolderContrib(picked.path)   // SELECTION FINAL PASS 1 (§4C)
        } else {
            folder.excluded.clear()
            selectedFolders[folder.path] = folder
            invalidateFolderContrib(folder.path)   // SELECTION FINAL PASS 1 (§4C)
            folder.tracks.forEach { selectedTracks.remove(trackKey(it)) }
            invalidateStandaloneTotals()           // SELECTION FINAL PASS 1 (§4C)
        }
    }

    /** Hold-to-select and RANGE selection: with a valid anchor in the visible
     *  list, a long-press selects the whole inclusive span between them (in
     *  the current sorted/filtered order — hidden and filtered-out rows are
     *  simply not in `shown`); without one, the press selects just this row
     *  and becomes the anchor. Additive — unrelated picks are preserved.
     *
     *  ── GOAT-PASS 8 (§3 — the ghost-anchor guard): the anchor must still be
     *  SELECTED for a range to extend from it — a stale key from a previous
     *  selection session (left behind by a deselection path that didn't clear
     *  it) reads as no anchor, and the press becomes a fresh selection.
     *  ── GOAT-PASS 8 (§4 — Hold & Slide mode): the press applies only the
     *  initiating toggle (matching the drag's captured pass mode), never a
     *  range — the armed drag owns everything the finger crosses. */
    fun longPress(target: Row) {
        val slideMode = com.lumidrop.x.settings.SettingsManager.isSlideSelection(ctx)
        val anchor = anchorKey
        val anchorPos = if (anchor != null) shown.indexOfFirst { it.key == anchor } else -1
        val anchorValid = anchorPos >= 0 && shown[anchorPos].let { row ->
            when {
                row.folder != null -> selectedFolders.containsKey(row.folder.path)
                row.track != null -> trackIsOn(trackKey(row.track))
                else -> selectedFolders.containsKey(ALL_TRACKS)   // the All-Tracks container
            }
        }
        val targetPos = shown.indexOfFirst { it.key == target.key }
        if (anchor == null || !anchorValid || targetPos < 0 || slideMode) {
            when {
                target.folder != null -> if (!selectedFolders.containsKey(target.folder.path)) toggleFolder(target.folder)
                target.track != null -> if (!trackIsOn(trackKey(target.track))) toggleTrack(target.track)
            }
            return
        }
        val from = minOf(anchorPos, targetPos)
        val to = maxOf(anchorPos, targetPos)
        for (i in from..to) {
            val r = shown[i]
            when {
                r.folder != null -> selectFolderOn(r.folder)
                r.track != null -> selectTrackOn(r.track)
            }
        }
        anchorKey = target.key
        repaint()
    }

    fun open(path: String?) {
        openFolder = path
        // ── GOAT-PASS 10 (§3): the scope's OWN remembered sorting — every
        // browsing scope restores exactly what was left there, independently
        // of every other scope; an unconfigured one keeps the default
        // (title, ascending).
        applyStoredSort(path ?: rootDirPath)
        query = ""
        searchBox.setText("")
        anchorKey = null   // a different scope invalidates the anchor
        submitAll()
    }

    // ── Pass-40 (#3) + Pass-41 (§6): the exact-track REVEAL ────────────────────
    // The floating player's main-container tap lands here (through the
    // controller's musicSheetFocus hook while this sheet is live, or the
    // opener's focusTrack when it opened the sheet). The track is located by
    // its FULL CANONICAL IDENTITY — never by display name, so a same-named
    // sibling can never steal the reveal, and never by the session key ALONE,
    // so the same song resolves whether the player started it from this sheet
    // (a MediaStore path/uri) or from the Queue (a "q:…" session key over a
    // file path or a SAF/content uri — the exact Pass-41 §6 case where the
    // key-only match wrongly said "isn't in your music library"). The
    // containing folder's view (or the All-Tracks view for a library-only
    // track) opens with any live search cleared, the row scrolls into a
    // comfortable position, and a subtle accent flash marks it — no sheet
    // recreation, no full rebuild beyond the folder view's own, no disruptive
    // animated scroll.
    fun flashRow(idx: Int) {
        val vh = list.findViewHolderForAdapterPosition(idx) ?: return
        val row = vh.itemView
        if (row.width <= 0 || row.height <= 0) return
        val flash = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = ctx.dpf(12f)   // the row card's own radius
            setColor(col(R.color.accent) or 0xFF000000.toInt())
            alpha = 0x38   // ~22% — subtle, unmistakably the playing one
        }
        flash.setBounds(0, 0, row.width, row.height)
        try { row.overlay.add(flash) } catch (e: Exception) { return }
        val fade = android.animation.ValueAnimator.ofInt(0x38, 0)
        fade.startDelay = 500
        fade.duration = 350
        fade.addUpdateListener { a -> flash.alpha = a.animatedValue as Int }
        fade.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: android.animation.Animator) {
                try { row.overlay.remove(flash) } catch (e: Exception) { /* row gone — recycle cleared it */ }
            }
        })
        fade.start()
    }

    /** Focuses [track]'s row: opens its folder context, scrolls to it and
     *  flashes it. Returns false only when the library cannot resolve the
     *  track through ANY of its identities (deleted source, or a SAF-sourced
     *  queue file that was never in MediaStore) — the caller decides what
     *  that means; here it is a quiet toast and the sheet stays where it is. */
    fun focusOnTrack(track: MusicPlayback.Track?): Boolean {
        if (track == null) return false
        if (!loaded) { pendingFocusTrack = track; return true }   // the load's first submit reveals it
        // ── Pass-41 (§6): the CANONICAL IDENTITY SET. The same physical song ──
        // is addressed differently per starting surface; every form the
        // library could possibly key it under is collected here, then matched
        // against each library row's own path and uri. The queue-prefix strip
        // bridges "q:<path>" ↔ "<path>"; the file-uri path extraction bridges
        // a queue track's file:// uri ↔ the MediaStore DATA column; the raw
        // uri string bridges a manifest (album-staged) queue track ↔ its
        // MediaStore uri verbatim.
        val candidates = LinkedHashSet<String>()
        candidates.add(track.key)
        candidates.add(track.key.removePrefix("q:"))
        candidates.add(track.uri.toString())
        if (track.uri.scheme == "file" && !track.uri.path.isNullOrBlank()) candidates.add(track.uri.path!!)
        fun matches(t: AudioItem): Boolean =
            (t.path != null && candidates.contains(t.path)) ||
            (t.uri != null && candidates.contains(t.uri.toString()))
        val found = allTracks.firstOrNull { matches(it) } ?: run {
            android.widget.Toast.makeText(ctx, "This track isn't in your music library", android.widget.Toast.LENGTH_SHORT).show()
            return false
        }
        // The containing folder (a MediaStore track always has one; a stray
        // uri-only track falls to the All-Tracks view, which holds every
        // eligible library track regardless of grouping).
        val folder = folders.firstOrNull { f -> f.tracks.any { matches(it) } }
        val target = folder?.path ?: ALL_TRACKS
        if (openFolder != target) open(target) else if (query.isNotBlank()) open(target)
        val idx = shown.indexOfFirst { it.track != null && it.track!!.path == found.path && it.track!!.uri == found.uri }
        if (idx < 0) return false
        // A comfortable position: the target about a third of the list down,
        // snapped instantly (no fling animation fighting the reveal).
        (list.layoutManager as? LinearLayoutManager)
            ?.scrollToPositionWithOffset(idx, list.height / 3)
        // The row exists after the scroll lays it out — flash it one frame
        // later, and once more after layout settles (the two-post rhythm the
        // Files sheet's own reveal uses).
        list.post { flashRow(idx) }
        list.postDelayed({ flashRow(idx) }, 120)
        return true
    }

    repaint = { submitVisible(); paintChrome() }
    // ── Pass-39 (#1): HOLD-AND-SLIDE selection — the same shared gesture the ──
    // Media/App grids and the Files sheet use (one attachDragSelect), adapted
    // to this list: press and hold a container or track, slide to select (or
    // deselect) continuously, with edge auto-scrolling for long collections.
    // GOAT-PASS 11 (§12): the gesture itself applies the initiating row;
    // this sheet's rows keep their own long-press for Range mode
    // (the range-selection anchor); the gesture arms on top of it, captures
    // its mode from the row's state at DOWN, and every crossed row lands on
    // that state through the same model primitives the taps use — so the
    // count pill, coverage, and the All-Tracks partial chip stay exact. The
    // terminal event is consumed: no crossed row can also open a folder or
    // start playback, and a plain tap keeps its single meaning.
    // ── SELECTION FINAL PASS 1 (§4D — the Hold & Slide fast path): the ──
    // crossed row's own mutation + payload rebind + footer NOW; the one full
    // reconciliation at gesture end. Same model primitives as before (the
    // drag used to run the FULL repaint per crossed row — the whole-list
    // DiffUtil that made big selections sluggish).
    fun dragApplyRow(pos: Int, add: Boolean) {
        val r = shown.getOrNull(pos) ?: return
        when {
            r.folder != null -> if (add) selectFolderOn(r.folder)
            else {
                selectedFolders.remove(r.folder.path)
                invalidateFolderContrib(r.folder.path)   // SELECTION FINAL PASS 1 (§4C)
            }
            r.track != null -> {
                val k = trackKey(r.track)
                if (trackIsOn(k) != add) toggleTrack(r.track, silent = true)
            }
            r.isAllEntry -> if (add) {
                if (!selectedFolders.containsKey(ALL_TRACKS)) {
                    selectedFolders[ALL_TRACKS] = allTracksFolder
                    invalidateFolderContrib(ALL_TRACKS)   // SELECTION FINAL PASS 1 (§4C)
                }
            } else {
                selectedFolders.remove(ALL_TRACKS)
                invalidateFolderContrib(ALL_TRACKS)       // SELECTION FINAL PASS 1 (§4C)
            }
        }
        // The crossed row's own chrome, settled immediately.
        val (picked, partial) = rowSelFlags(r)
        r.picked = picked; r.partial = partial
        listAdapter.notifyItemChanged(pos, MusicSelPayload)
        paintChrome()
    }

    attachDragSelect(
        list,
        isSelectedAt = { pos ->
            val r = shown.getOrNull(pos) ?: return@attachDragSelect false
            when {
                r.folder != null -> folderRowState(r.folder) != 0
                r.track != null -> trackIsOn(trackKey(r.track))
                else -> selectedFolders.containsKey(ALL_TRACKS)   // the All-Tracks container
            }
        },
        onApply = { pos, add ->
            // ── SELECTION FINAL PASS 1 (§4D): fast path — see dragApplyRow. ──
            dragApplyRow(pos, add)
        },
        // ── GOAT-PASS 7 (§3D): edge auto-scroll while the armed drag holds ──
        // the finger near the list's rim — speed by proximity, selection
        // continues under the stationary finger, stops with the gesture.
        edgeAutoScroll = true,
        // ── GOAT-PASS 8 (§4): the drag arms only in Hold & Slide mode; in ──
        // Range mode (the default) the row's own long-press owns everything.
        slideEnabled = { com.lumidrop.x.settings.SettingsManager.isSlideSelection(ctx) },
        // ── SELECTION FINAL PASS 1 (§4D/§3): once per gesture — the full ──
        // surgical scan (which also catches any covered-row side effects the
        // fast path deliberately does not chase, e.g. a folder row flipping
        // partial while the drag crosses its members) and the chrome settle.
        onGestureEnd = { repaintSelection() },
    )

    openFolderFn = { p -> open(p) }
    toggleTrackFn = { t -> toggleTrack(t) }
    toggleFolderFn = { f -> toggleFolder(f) }
    longPressFn = { r -> longPress(r) }
    playFn = { t ->
        val act = activity
        if (MusicPlayback.isCurrent(trackKey(t))) {
            MusicPlayback.toggle()
        } else if (act != null) {
            // The playlist context is the picker's CURRENT visible track
            // list (its live sort/filter/search order) starting at this
            // track — prev/next then walk exactly what the user sees.
            val playlist = shown.filter { it.track != null }.map { rr ->
                val tr = rr.track!!
                MusicPlayback.Track(
                    key = trackKey(tr),
                    uri = tr.uri,
                    title = tr.title ?: tr.name,
                    artist = tr.artist,
                    albumId = tr.albumId,
                    durationMs = tr.durationMs,
                    size = tr.size,
                )
            }
            val idx = playlist.indexOfFirst { it.key == trackKey(t) }
            if (idx >= 0) MusicPlayback.play(act, playlist, idx)
        }
        repaint()
    }
    upBtn.setOnClickListener { openFolderFn(null) }

    // ══ GOAT-PASS 7 Part 2 (§5): the file operations behind the Music ⋮ menu ══
    //
    // The SAME FileOps engine the Files sheet uses — collision-safe landing
    // names (never an overwrite), containment safety (nothing pastes into
    // itself), cooperative cancellation — driven from THIS sheet's selection
    // model. MediaStore honesty is part of the contract: every operation
    // rescans the affected paths (a scan of a deleted/renamed-away path
    // clears its stale row; a scan of a landed path indexes it) and drops
    // the 30s library cache, so the sheet re-reads the REAL library.

    /** Re-queries the library and rebuilds the folder model — the honest
     *  refresh after any real filesystem change. A folder view whose
     *  directory vanished (deleted / cut away) returns to the folders list,
     *  never a dead listing. */
    fun reloadLibrary() {
        if (openFolder != null && openFolder != ALL_TRACKS) {
            val dirAlive = try { File(openFolder!!).isDirectory } catch (e: Exception) { false }
            if (!dirAlive) { openFolder = null; anchorKey = null; query = ""; searchBox.setText("") }
        }
        scope.launch {
            val audio = MediaLibrary.queryAudio(ctx)
            cachedAudio = audio
            cachedAudioAt = System.currentTimeMillis()
            allTracks = audio
            folders = groupFolders(audio)
            refreshAllTracksFolder()
            loaded = true
            submitAll()
        }
    }

    /** Rescans [paths] with the media scanner, drops the library cache, and
     *  reloads — the one shared post-operation step. Best-effort: the scan
     *  is asynchronous by design, the reload reads whatever the provider
     *  already knows, and the NEXT sheet open (cache cleared) reads the
     *  settled truth.
     *  ── GOAT-PASS 8 (§10): the same filesystem change also invalidates the ──
     *  FILES sheet's process-level cached listings for every affected
     *  directory (and each one's parent — a folder's own count row lives
     *  there), so this surface's operations can never leave a stale listing
     *  or a stale folder count behind on the other sheet. */
    fun rescanAndReload(paths: Array<String>) {
        try { android.media.MediaScannerConnection.scanFile(ctx.applicationContext, paths, null, null) }
        catch (e: Exception) { /* best-effort — the next open re-queries anyway */ }
        try {
            val parents = HashSet<File>(paths.size)
            for (p in paths) {
                val f = File(p)
                invalidateFilesSheetCacheFor(f)          // the file's own listing (delete/rename landed)
                f.parentFile?.let { parents.add(it) }    // …and every touched directory
            }
            for (d in parents) invalidateFilesSheetCacheFor(d)
        } catch (e: Exception) { /* foreign-surface hygiene only */ }
        cachedAudio = null
        cachedAudioAt = 0L
        reloadLibrary()
    }

    /** The current selection as FileOps picks: a physical folder pick carries
     *  its deselected members as the pick's exclusion set (the engine prunes
     *  exactly those files); the All-Tracks collection resolves to its
     *  still-selected member tracks as individual file picks; a standalone
     *  track is one file pick. Tracks with no on-device path (SAF-only rows)
     *  are honestly skipped by the callers, never guessed. */
    fun selectionFileOpsPicks(): List<FileOps.Pick> {
        val out = ArrayList<FileOps.Pick>()
        fun pathOfKey(key: String): String? = allTracks.firstOrNull { trackKey(it) == key }?.path
        for (folder in selectedFolders.values) {
            if (folder.path == ALL_TRACKS) {
                for (t in folder.tracks) if (trackKey(t) !in folder.excluded && t.path != null)
                    out.add(FileOps.Pick(File(t.path!!), emptySet()))
            } else {
                val excluded = folder.excluded.mapNotNull { k -> pathOfKey(k) }.toHashSet()
                out.add(FileOps.Pick(File(folder.path), excluded))
            }
        }
        for (t in selectedTracks.values) t.path?.let { out.add(FileOps.Pick(File(it), emptySet())) }
        return out
    }

    /** The selection's delete targets: physical folder roots, and the
     *  All-Tracks pick's member files. Returns (roots, unaddressable) — the
     *  count of SAF-only tracks no File-based engine can delete. */
    fun musicDeleteRoots(): Pair<List<File>, Int> {
        val roots = ArrayList<File>()
        var unaddressable = 0
        for (folder in selectedFolders.values) {
            if (folder.path == ALL_TRACKS) {
                for (t in folder.tracks) if (trackKey(t) !in folder.excluded) {
                    val p = t.path
                    if (p != null) roots.add(File(p)) else unaddressable++
                }
            } else roots.add(File(folder.path))
        }
        for (t in selectedTracks.values) {
            val p = t.path
            if (p != null) roots.add(File(p)) else unaddressable++
        }
        return roots to unaddressable
    }

    fun startMusicDelete(roots: List<File>) {
        if (FileOps.running) {
            android.widget.Toast.makeText(ctx, "An operation is already running", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        musicOpJob = scope.launch {
            footerInfo.text = "Deleting…"
            val (ok, failed) = FileOps.deleteBatch(roots)
            selectedTracks.clear(); selectedFolders.clear(); anchorKey = null; invalidateAllSelTotals()   // SELECTION FINAL PASS 1 (§4C)
            val paths = Array(roots.size) { i -> roots[i].absolutePath }
            val msg = when {
                ok > 0 && failed == 0 -> "Deleted $ok item${if (ok != 1) "s" else ""}"
                ok > 0 -> "Deleted $ok, couldn't delete $failed"
                else -> "Couldn't delete — ${roots.size} item${if (roots.size != 1) "s" else ""} failed"
            }
            android.widget.Toast.makeText(ctx, msg,
                if (failed > 0) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()
            rescanAndReload(paths)
        }
    }

    fun confirmMusicDelete() {
        val (roots, unaddressable) = musicDeleteRoots()
        if (roots.isEmpty()) {
            android.widget.Toast.makeText(ctx, "Nothing deletable is selected", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val folders = roots.count { runCatching { it.isDirectory }.getOrDefault(false) }
        val label = if (roots.size == 1) '"' + roots[0].name + '"' else "${roots.size} items"
        showLdxDialog(
            context = ctx,
            title = "Delete $label?",
            message = (if (folders > 0)
                "Everything inside the selected folder${if (folders != 1) "s" else ""} goes with it. " else "") +
                "This permanently deletes from this device and cannot be undone." +
                (if (unaddressable > 0) "\n($unaddressable cloud-only track${if (unaddressable != 1) "s" else ""} can't be deleted from here.)" else ""),
            iconRes = R.drawable.ic_delete,
            actions = listOf(
                LdxDialogAction("Cancel"),
                LdxDialogAction("Delete", style = LdxButtonStyle.DESTRUCTIVE) { startMusicDelete(roots) },
            ),
        )
    }

    fun showMusicRenameDialog() {
        if (selectedTracks.size + selectedFolders.size != 1) return
        val target: File; val oldName: String
        if (selectedFolders.isNotEmpty()) {
            val f = selectedFolders.values.first()
            if (f.path == ALL_TRACKS) {
                android.widget.Toast.makeText(ctx, "All tracks is a collection — open a folder to rename it", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            target = File(f.path); oldName = f.name
        } else {
            val t = selectedTracks.values.first()
            val p = t.path
            if (p == null) {
                android.widget.Toast.makeText(ctx, "This track isn't a file on this device", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
            target = File(p); oldName = t.name
        }
        if (!runCatching { target.exists() }.getOrDefault(false)) return
        val input = Widgets.inputField(ctx, oldName, android.text.InputType.TYPE_CLASS_TEXT).apply {
            setSingleLine()
            // The BASE name starts selected, so an ordinary edit preserves
            // the extension unless the user deliberately changes it.
            val dot = oldName.lastIndexOf('.')
            setSelection(0, if (dot > 0) dot else oldName.length)
        }
        val dlgRef = arrayOfNulls<android.app.Dialog>(1)
        fun tryApply() {
            var newName = input.text.toString().trim()
            // ── FULL FINAL PASS 2 (§7A — the duplicate-extension typo guard, ──
            // shared with the Files sheet's rename): "song.mp3.mp3" trims to
            // one trailing extension; the success toast shows the final name.
            val oldExt = oldName.substringAfterLast('.', "")
            if (oldExt.isNotEmpty() && newName.endsWith(".$oldExt.$oldExt"))
                newName = newName.removeSuffix(".$oldExt")
            when {
                newName.isEmpty() || newName == "." || newName == ".." ->
                    android.widget.Toast.makeText(ctx, "Enter a valid name", android.widget.Toast.LENGTH_SHORT).show()
                newName.contains('/') ->
                    android.widget.Toast.makeText(ctx, "A name cannot contain \"/\"", android.widget.Toast.LENGTH_SHORT).show()
                newName == oldName -> dlgRef[0]?.dismiss()
                File(target.parentFile, newName).exists() ->
                    android.widget.Toast.makeText(ctx, "\"$newName\" already exists in this folder", android.widget.Toast.LENGTH_SHORT).show()
                else -> {
                    val newFile = File(target.parentFile, newName)
                    val ok = try { target.renameTo(newFile) } catch (e: Exception) { false }
                    if (!ok) {
                        android.widget.Toast.makeText(ctx, "Couldn't rename — the storage refused", android.widget.Toast.LENGTH_LONG).show()
                        return
                    }
                    dlgRef[0]?.dismiss()
                    selectedTracks.clear(); selectedFolders.clear(); anchorKey = null; invalidateAllSelTotals()   // SELECTION FINAL PASS 1 (§4C)
                    android.widget.Toast.makeText(ctx, "Renamed to " + newName, android.widget.Toast.LENGTH_SHORT).show()
                    // MediaStore honesty: scanning the OLD path clears its
                    // stale row, scanning the NEW one indexes the file.
                    rescanAndReload(arrayOf(target.absolutePath, newFile.absolutePath))
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

    fun armMusicClipboard(isCut: Boolean) {
        val picks = selectionFileOpsPicks()
        if (picks.isEmpty()) {
            android.widget.Toast.makeText(ctx, "Nothing selected can be ${if (isCut) "cut" else "copied"}", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        musicClipboard = MusicClipboard(isCut, picks)
        selectedTracks.clear(); selectedFolders.clear(); anchorKey = null; invalidateAllSelTotals()   // SELECTION FINAL PASS 1 (§4C)
        repaint()
        android.widget.Toast.makeText(ctx,
            "${picks.size} item${if (picks.size != 1) "s" else ""} ${if (isCut) "cut" else "copied"} — open a destination folder and tap ⋮ → Paste",
            android.widget.Toast.LENGTH_SHORT).show()
    }

    fun pasteMusicClipboard() {
        val cb = musicClipboard ?: return
        if (FileOps.running) {
            android.widget.Toast.makeText(ctx, "An operation is already running", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        // ── GOAT-PASS 7 (§5): the All-Tracks paste protection, enforced ──
        // INSIDE the operation — never merely by hiding a menu entry. The
        // All-Tracks container is a smart VIEW over the whole library
        // (its "path" is a sentinel, not a directory); nothing can ever be
        // pasted into it, and the sentinel is not a real filesystem path
        // either, so no lower-level call could target it even if one
        // existed. Ordinary folders paste exactly like the Files sheet.
        val destPath = openFolder
        if (destPath == null || destPath == ALL_TRACKS) {
            android.widget.Toast.makeText(ctx,
                if (destPath == ALL_TRACKS) "All tracks is a smart collection — paste into a real folder instead"
                else "Open a music folder first, then tap ⋮ → Paste",
                android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val dest = File(destPath)
        if (!runCatching { dest.isDirectory && dest.canWrite() }.getOrDefault(false)) {
            android.widget.Toast.makeText(ctx, "This folder is not writable", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        musicOpJob = scope.launch {
            var lastUi = 0L
            val onProgress: (FileOps.Progress) -> Unit = { p ->
                val now = android.os.SystemClock.uptimeMillis()
                if (p.totalBytes <= 0 || now - lastUi >= 80) {
                    lastUi = now
                    footerInfo.text = if (p.totalBytes <= 0) p.phase else "${p.phase} · ${p.percent}%"
                }
            }
            val result = if (cb.isCut) FileOps.moveBatch(cb.picks, dest, showHidden, onProgress)
            else FileOps.copyBatch(cb.picks, dest, showHidden, onProgress)
            if (!result.cancelled) musicClipboard = null
            val verb = if (cb.isCut) "Moved" else "Copied"
            val msg = when {
                result.cancelled -> "Cancelled — ${result.ok} of ${cb.picks.size} completed"
                result.failed > 0 -> "$verb ${result.ok}, couldn't ${if (cb.isCut) "move" else "copy"} ${result.failed}" +
                    (result.firstError?.let { " ($it)" } ?: "")
                else -> "$verb ${result.ok} item${if (result.ok != 1) "s" else ""}"
            }
            android.widget.Toast.makeText(ctx, msg,
                if (result.failed > 0 || result.cancelled) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT).show()
            // MediaStore honesty: the landed names are best-effort (a
            // collision lands as "name (1).ext", unknowable from here); the
            // cut-away sources are exact. The cleared cache + the next query
            // settle whatever the scanner has indexed by then.
            val landed = cb.picks.map { p -> File(dest, p.root.name).absolutePath }
            val scanTargets = if (cb.isCut) landed + cb.picks.map { p -> p.root.absolutePath } else landed
            rescanAndReload(scanTargets.toTypedArray())
        }
    }

    sortBtn.setOnClickListener {
        // ── GOAT-PASS 7 Part 2 (§5): the Music sheet's ⋮ menu — the SAME ──
        // design language as the Files sheet's (the three-dot icon replaced
        // the old standalone sorting glyph): the full sorting section stays,
        // joined by the conditional file-management actions that appear only
        // while they can act (Delete/Rename/Copy/Cut need a selection,
        // Rename exactly one, Paste an armed clipboard) and disappear the
        // instant that changes — the list is rebuilt fresh on every open
        // from live state.
        val opts = ArrayList<PickerOption>()
        opts.add(PickerOption("KEY_TITLE", "Title", sortKey == MusicSortKey.TITLE))
        opts.add(PickerOption("KEY_ARTIST", "Artist", sortKey == MusicSortKey.ARTIST))
        opts.add(PickerOption("KEY_DATE", "Date added", sortKey == MusicSortKey.DATE))
        opts.add(PickerOption("KEY_DURATION", "Duration", sortKey == MusicSortKey.DURATION))
        opts.add(PickerOption("KEY_SIZE", "Size", sortKey == MusicSortKey.SIZE))
        opts.add(PickerOption("", "", divider = true))
        opts.add(PickerOption("DIR_ASC", "Ascending", !sortDesc))
        opts.add(PickerOption("DIR_DESC", "Descending", sortDesc))
        opts.add(PickerOption("", "", divider = true))
        opts.add(PickerOption("HIDDEN", "Show hidden files", showHidden))
        // ── GOAT-PASS 10 (§3): Apply to all — the same clearly-labelled ──
        // deliberate extra the Files sheet's menu carries (after the hidden
        // toggle, separated by its own divider): it applies the CURRENT
        // criterion + direction to the current scope and every music folder
        // under it, after a one-tap confirmation.
        opts.add(PickerOption("", "", divider = true))
        opts.add(PickerOption("APPLY_ALL", "Apply to all subfolders", false))
        val selCount = selectedTracks.size + selectedFolders.size
        if (selCount > 0) {
            opts.add(PickerOption("", "", divider = true))
            opts.add(PickerOption("DELETE", "Delete", iconRes = R.drawable.ic_delete))
            if (selCount == 1) opts.add(PickerOption("RENAME", "Rename", iconRes = R.drawable.ic_edit))
            opts.add(PickerOption("COPY", "Copy", iconRes = R.drawable.ic_copy))
            opts.add(PickerOption("CUT", "Cut", iconRes = R.drawable.ic_cut))
        }
        musicClipboard?.let { cb ->
            opts.add(PickerOption("", "", divider = true))
            opts.add(PickerOption("PASTE",
                "Paste ${cb.picks.size} item${if (cb.picks.size != 1) "s" else ""} here",
                iconRes = R.drawable.ic_paste))
        }
        showPickerOptionsMenu(sortBtn, "Music", opts, compact = true, endAnchored = true) { id ->
            // Actions first — none of them touches sorting state.
            when (id) {
                "DELETE" -> { confirmMusicDelete(); return@showPickerOptionsMenu }
                "RENAME" -> { showMusicRenameDialog(); return@showPickerOptionsMenu }
                "COPY" -> { armMusicClipboard(false); return@showPickerOptionsMenu }
                "CUT" -> { armMusicClipboard(true); return@showPickerOptionsMenu }
                "PASTE" -> { pasteMusicClipboard(); return@showPickerOptionsMenu }
            }
            when (id) {
                "KEY_TITLE" -> sortKey = MusicSortKey.TITLE
                "KEY_ARTIST" -> sortKey = MusicSortKey.ARTIST
                "KEY_DATE" -> sortKey = MusicSortKey.DATE
                "KEY_DURATION" -> sortKey = MusicSortKey.DURATION
                "KEY_SIZE" -> sortKey = MusicSortKey.SIZE
                "DIR_ASC" -> sortDesc = false
                "DIR_DESC" -> sortDesc = true
                "HIDDEN" -> {
                    showHidden = !showHidden
                    SettingsManager.setPickerShowHidden(ctx, showHidden)
                    refreshAllTracksFolder()   // eligibility follows the filter
                }
                // The recursive apply goes through its own confirmation —
                // the menu stays one tap for the everyday picks, and the
                // bulk write never fires by accident (Files' contract).
                "APPLY_ALL" -> { confirmApplySortToAll(); return@showPickerOptionsMenu }
            }
            // ── GOAT-PASS 10 (§3): every sorting change is remembered for THIS ──
            // browsing scope alone — reopening it restores exactly this
            // configuration, and no other scope's setting is touched.
            if (id != "HIDDEN") {
                SettingsManager.setMusicDirSort(ctx, currentSortScopeKey(), currentSortValue())
            }
            anchorKey = null   // a new order invalidates the range anchor
            submitAll()
            // ── GOAT-PASS 10 (§3): every sorting change returns the list to ──
            // the top — the new order's first rows are what the user just
            // asked to see. Instant (not animated): an animated scroll over a
            // re-sorted list would show intermediate orderings, exactly what
            // the requirement forbids; the DiffUtil move animations keep the
            // transition itself visually calm. Only genuine sorting changes
            // land here — the hidden toggle and the file actions return early
            // or keep their own behavior, and normal navigation/scroll
            // restoration is untouched.
            if (id != "HIDDEN") {
                (list.layoutManager as? LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0)
            }
        }
    }
    searchBox.addTextChangedListener { e ->
        val serial = searchSerial.incrementAndGet()
        query = e?.toString() ?: ""
        scope.launch {
            delay(120)
            if (serial == searchSerial.get()) {
                anchorKey = null   // the visible set changed; the anchor may be gone
                submitAll()
            }
        }
    }
    // ── SELECTION FINAL PASS 1 (§4C): the totals caches drop with the bulk clear. ──
    clearBtn.setOnClickListener { selectedTracks.clear(); selectedFolders.clear(); anchorKey = null; invalidateAllSelTotals(); repaint() }
    selectAllBtn.setOnClickListener {
        // ── GOAT-PASS 7 Part 2 (§4B): Select All follows the VISIBLE rows — ──
        // on the folders view that means folders while browsing and the
        // matching TRACK rows while searching (the search results are the
        // current scope, exactly as the Files sheet treats its results
        // view). The All-Tracks container row is deliberately excluded on
        // the browse view — it is a VIEW over the whole library, not a
        // member of this list, and selecting it here would silently
        // swallow every folder pick.
        buildRows().forEach { r ->
            if (!r.isAllEntry) {
                r.folder?.let { f ->
                    if (!selectedFolders.containsKey(f.path)) {
                        selectedFolders[f.path] = f
                        invalidateFolderContrib(f.path)   // SELECTION FINAL PASS 1 (§4C) — a re-pick must never inherit a stale cached contribution
                    }
                }
                r.track?.let { selectTrackOn(it) }
            }
        }
        repaint()
    }
    addBtn.setOnClickListener {
        if (selectedTracks.isEmpty() && selectedFolders.isEmpty()) return@setOnClickListener
        val items = ArrayList<PreviewItem>(selectedFolders.size + selectedTracks.size)
        var skippedEmpty = 0
        // Folders (and the All-Tracks collection) stage as MANIFEST folders —
        // exactly the audio still selected inside them (exclusions honored),
        // the folder's own name, and a hierarchy that is the manifest itself.
        // The All-Tracks folder's path is a SENTINEL, never a physical
        // directory: nothing ever walks storage for it, the manifest is the
        // one source of its contents.
        for (folder in selectedFolders.values) {
            val members = folder.tracks.filter { trackKey(it) !in folder.excluded }
            if (members.isEmpty()) { skippedEmpty++; continue }
            items.add(PreviewItem(
                name = folder.name,
                path = folder.path,
                uri = null,
                size = members.sumOf { it.size },
                mime = "application/zip",
                isFolder = true,
                childManifest = members.map { t ->
                    PreviewItem.FolderChild(
                        relPath = t.name,
                        path = t.path,
                        uri = t.uri.toString(),
                        size = t.size,
                        mime = t.mime.ifEmpty { "audio/*" },
                    )
                },
            ))
        }
        for (track in selectedTracks.values) {
            items.add(PreviewItem(
                name = track.name,
                path = track.path,
                uri = track.uri.toString(),
                size = track.size,
                mime = track.mime.ifEmpty { "audio/*" },
            ))
        }
        if (skippedEmpty > 0)
            android.widget.Toast.makeText(ctx, "$skippedEmpty selected folder${if (skippedEmpty != 1) "s" else ""} had nothing left to add", android.widget.Toast.LENGTH_SHORT).show()
        sheet.dismiss()
        onAdd(items)
    }

    // ── The shared playback controller's state feed ───────────────────────────
    // Row indicators repaint on every controller change; playback itself is
    // global, so closing the picker does NOT stop the audio — the floating
    // overlay keeps it accurate.
    val stateListener: (String?) -> Unit = { repaint() }
    MusicPlayback.addListener(stateListener)

    // ── Pass-40 (#3) + Pass-41 (§6): THIS sheet is the live focus target ──────
    // while it is up — the floating player's main-container tap lands in
    // focusOnTrack (full canonical identity, correct folder context, scroll +
    // flash, never a reopen). A miss (deleted source, or a SAF-sourced queue
    // file that never was in MediaStore) is handled gracefully in place; the
    // sheet is never recreated and the browse state is never reset for it.
    // Always "handled" — the sheet is already open, so the controller never
    // falls through to the opener (which the live-sheet gate would no-op
    // anyway).
    MusicPlayback.musicSheetFocus = { track ->
        if (loaded) focusOnTrack(track)
        else pendingFocusTrack = track   // the load's first submit reveals it
        true
    }

    // ── Pass-40 (#3): the modal-sheet edge — the floating player folds an
    // expanded card to its pill (the dock above this sheet keeps it visible
    // and one tap from the full controls) and re-stacks above the sheet
    // whenever it is re-expanded from the pill.
    MusicPlayback.onModalSheetVisibilityChanged(true)

    // ── GOAT-PASS 9 (§5 — live synchronization): while the Music sheet is ─────
    // OPEN, a MediaStore change (a song lands from ANY app — a receive, a
    // download, a file manager) reloads the library by itself: the 30 s
    // freshness window is cleared by the observer fan-out, and this hook
    // re-queries and re-submits with selection and scroll intact (stable
    // per-track keys). Min-interval throttled: a burst of observer events
    // collapses into one reload, and a quiet library never reloads at all.
    var lastSyncReloadAt = 0L
    val libSyncHook = com.lumidrop.x.media.LibrarySync.Listener { _, _ ->
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastSyncReloadAt < 2_000L) return@Listener
        lastSyncReloadAt = now
        try { reloadLibrary() } catch (e: Exception) { /* never break a live sheet over a sync refresh */ }
    }
    com.lumidrop.x.media.LibrarySync.addListener(libSyncHook)

    // Dismissal cancels the sheet-scoped scope and detaches the listener;
    // audio (if playing) keeps going under the floating overlay. The one-live-
    // sheet slot is released here too (Pass-37 #4) — every close path lands
    // in this listener, so the gate never latches against the next open.
    sheet.dialog.setOnDismissListener {
        // ── GOAT-PASS 7 Part 2 (§5): an in-flight file operation dies with ──
        // the sheet — the engine's cooperative cancel runs (its partial
        // destination file is deleted; nothing half survives) — but ONLY
        // when THIS sheet's operation is the one in flight, so a
        // simultaneously-live Files sheet's dismissal can never cancel a
        // Music operation it knows nothing about (and vice versa).
        if (musicOpJob?.isActive == true) {
            try { FileOps.cancel() } catch (e: Exception) { /* engine idle */ }
            try { musicOpJob?.cancel() } catch (e: Exception) { /* already gone */ }
        }
        MusicPlayback.removeListener(stateListener)
        MusicPlayback.musicSheetFocus = null
        MusicPlayback.onModalSheetVisibilityChanged(false)
        try { scope.cancel() } catch (e: Exception) { /* already gone */ }
        // GOAT-PASS 9 (§5): the live-sync hook dies with the sheet.
        try { com.lumidrop.x.media.LibrarySync.removeListener(libSyncHook) } catch (e: Exception) { /* already gone */ }
        PickerGate.release("music")
    }

    try {
        sheet.show { root }
    } catch (e: Exception) {
        // The window never made it up — the gate must not stay latched, and
        // the Pass-40 hooks must not stay armed against a dead sheet.
        MusicPlayback.musicSheetFocus = null
        MusicPlayback.onModalSheetVisibilityChanged(false)
        PickerGate.release("music")
        return
    }

    // ── Pass-35 (#4): the sheet's back decision — an open keyboard is
    // consumed by the IME first; then the folder/All-tracks view navigates
    // up; then selection mode exits; only then does the sheet dismiss.
    // Navigating up never clears selection.
    sheet.onBack = {
        when {
            openFolder != null -> { openFolderFn(null); true }
            selectionMode() -> {
                selectedTracks.clear(); selectedFolders.clear(); anchorKey = null; invalidateAllSelTotals()   // SELECTION FINAL PASS 1 (§4C)
                repaint()
                true
            }
            else -> false
        }
    }

    // ── Pass-35 (#1): the chrome rows are drag regions — empty areas drag
    // the sheet exactly like the handle; search, sort, back and the action
    // buttons keep their own touches. The track list is never registered.
    sheet.registerDragRegion(titleRow)
    sheet.registerDragRegion(controls)
    sheet.registerDragRegion(actionRow)

    paintChrome()
    // Freshness-window cache: a reopen inside 30s paints its first frame
    // fully populated with no MediaStore query at all.
    val cached = cachedAudio
    if (cached != null && System.currentTimeMillis() - cachedAudioAt < AUDIO_CACHE_FRESH_MS) {
        allTracks = cached
        folders = groupFolders(cached)
        refreshAllTracksFolder()
        loaded = true
        submitAll()
        // ── Pass-40 (#3) + Pass-41 (§6): the opener's focus reveal — the first
        // frame with a loaded library is where a sheet opened BY the floating
        // player scrolls to (and flashes) the playing track.
        pendingFocusTrack?.let { t -> pendingFocusTrack = null; focusOnTrack(t) }
    } else {
        scope.launch {
            val audio = MediaLibrary.queryAudio(ctx)
            cachedAudio = audio
            cachedAudioAt = System.currentTimeMillis()
            allTracks = audio
            folders = groupFolders(audio)
            refreshAllTracksFolder()
            loaded = true
            submitAll()
            pendingFocusTrack?.let { t -> pendingFocusTrack = null; focusOnTrack(t) }
        }
    }
}

/** One audio-containing folder — the sheet's model type, top-level only so
 *  the shared grouping helper below can build it (local classes can't be
 *  constructed outside their function).
 *
 *  ── Pass-35 (#13): a folder pick carries EXCLUSIONS — the track keys the
 *  user deselected INSIDE a picked folder (or the All-Tracks collection) —
 *  which is what gives music folders the same FULL/PARTIAL selection states
 *  the Files picker's folder picks have. [nameOverride] exists for the
 *  All-Tracks pseudo-folder, whose sentinel path is not a real directory. */
private class AudioFolder(val path: String, nameOverride: String? = null) {
    val tracks = ArrayList<AudioItem>()
    /** Track keys deselected inside this pick; empty = FULL pick. */
    val excluded = HashSet<String>()
    val name: String = nameOverride ?: path.trimEnd('/').substringAfterLast('/')
    /** ── SELECTION FINAL PASS 1 (§4C): the folder's byte total, cached. The ──
     *  old `get()` re-summed every track on EVERY read — and the All-Tracks
     *  row's own meta line reads it on every bind, so a full repaint of the
     *  folders view paid one whole-library summation for that single row.
     *  The cache is dropped by refreshAllTracksFolder (the only writer that
     *  mutates a live folder's track list after construction). */
    private var bytesCache: Long = -1L
    val bytes: Long get() { if (bytesCache < 0L) bytesCache = tracks.sumOf { it.size }; return bytesCache }
    fun invalidateBytesCache() { bytesCache = -1L }
    val key: String get() = "fd:$path"
}

/** Folder grouping shared by both load paths (cache hit and fresh query):
 *  real parent directories, newest-content first. */
private fun groupFolders(audio: List<AudioItem>): List<AudioFolder> {
    val byParent = LinkedHashMap<String, AudioFolder>()
    for (t in audio) {
        val parent = t.parentPath ?: continue   // a track with no path can't be folder-grouped; it stays in All tracks
        byParent.getOrPut(parent) { AudioFolder(parent) }.tracks.add(t)
    }
    return byParent.values.sortedByDescending { f -> f.tracks.maxOf { it.dateAddedSec } }
}
