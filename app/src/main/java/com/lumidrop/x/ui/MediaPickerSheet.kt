package com.lumidrop.x.ui

import android.app.Dialog
import android.graphics.Outline
import android.graphics.Rect
import android.net.Uri
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.ViewConfiguration
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.lumidrop.x.R
import com.lumidrop.x.media.MediaAlbum
import com.lumidrop.x.media.MediaLibrary
import com.lumidrop.x.media.MediaLibraryItem
import com.lumidrop.x.media.MediaTypeFilter
import com.lumidrop.x.media.ThumbnailEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Media Selector (Part 4.3) — a first-class, in-app photo/video browser
 * replacing the old single "Image" tile, which only ever opened the bare
 * system content picker for images and had no video, album, or multi-
 * select-with-visible-state story at all.
 *
 * Deliberately a RecyclerView + GridLayoutManager grid, not the hand-built
 * "LinearLayout rows of tiles, chunk-rendered via a Handler" approach
 * showApkPickerSheet uses (see its own item in this same phase) — that
 * approach inflates a real View per tile up front; a device with a few
 * thousand photos would mean a few thousand Views. A recycling grid only
 * ever inflates enough tiles to cover the visible viewport regardless of
 * how large the library is, which is what "extremely fast opening, smooth
 * scrolling, low memory usage" actually requires at library scale rather
 * than app-installed-count scale (a few hundred, at most, for the apps
 * sheet — a few thousand is normal for a phone's camera roll).
 *
 * No separate permission request here: by the time the Send screen's
 * Media tile is reachable, the server is already running, which per
 * SendFragment.checkPermissionsAndStart() already required either
 * MANAGE_EXTERNAL_STORAGE or READ_EXTERNAL_STORAGE — both a superset of
 * what MediaStore querying needs. See MediaLibrary.kt's own doc comment.
 */
fun showMediaPickerSheet(
    fragment: Fragment,
    onConfirm: (uris: List<Uri>, albums: List<MediaAlbum>) -> Unit,
    // ── Pass-29 Part 2 (#2): the QUEUED-FILES preview entry. When non-null
    // the sheet opens STRAIGHT INTO its preview overlay showing these media
    // items (the Queued Files section's images/videos, in exact queue
    // order) instead of the picker grids — reusing the whole existing
    // previewer (pager, filmstrip, arrows, zoom, video controls) rather
    // than any second preview system. Closing the preview (X or Back)
    // dismisses the sheet entirely and returns to the caller's screen.
    // ── Pass-30 (#4): the top bar's select/deselect control — hidden in
    // Pass-29's queue mode — is now SHOWN there too, with queue-membership
    // semantics instead of picker-selection semantics (see the two
    // callbacks below): selected mark = the item is still in the queue,
    // empty ring = it was removed; one tap toggles membership through the
    // caller's existing queue paths.
    queuePreview: List<MediaLibraryItem>? = null,
    queuePreviewIndex: Int = 0,
    // ── Pass-30 (#4): the queue-preview's SELECTION semantics. In picker
    // mode the select control reads/writes the picker's own selection
    // (selected/selectedAlbums). In queue-preview mode the SAME control
    // instead reads/writes QUEUE MEMBERSHIP: [queueItemIsQueued] answers
    // "is this previewed item still in the Queued Files section right
    // now?" and [onQueueItemFlip] applies the user's toggle — deselect =
    // remove that item from the queue, reselect = put it back (the caller
    // owns both operations through the queue's existing removal/re-add
    // paths). Both are nullable-with-default so the picker call sites are
    // untouched.
    queueItemIsQueued: ((MediaLibraryItem) -> Boolean)? = null,
    onQueueItemFlip: ((MediaLibraryItem, Boolean) -> Unit)? = null,
) {
    val ctx = fragment.requireContext()
    // ── Pass-37 (#4): one live Media sheet, ever — the same synchronous ───────
    // acquire / dismiss-release guard the App Picker has had since Pass-26.
    // Rapid double-taps (the tile, or a queue preview tap landing while a
    // previous preview window is still up) can no longer stack a second
    // window, a second backdrop or a second dim layer on top of the first.
    if (!PickerGate.acquire("media")) return
    // ── Pass-35 (#12): the SAME resizable sheet system as the Files and ────────
    // Music pickers — a BottomSheet at the shared 75% default height,
    // expandable to the full frame by drag (handle or empty chrome), with
    // the same keyboard clamp and the same back-priority contract. Every
    // piece of the picker's own UI is untouched; only its host window
    // changed. The full-screen preview overlay rides the sheet's WINDOW
    // layer (addWindowOverlay below the show call), so it still covers
    // everything — sheet included — exactly as it did when this picker
    // owned a full-screen dialog of its own.
    val sheet = BottomSheet(ctx, expandable = true, defaultHeightFraction = 0.75f)
    // The existing code addresses the window through `dialog` throughout;
    // one alias keeps every reference working unchanged.
    val dialog = sheet.dialog

    // Phase 1 of 10 (resource/lifecycle audit — the repeated-open freeze
    // root cause): every coroutine this picker launches (the MediaStore
    // query AND every grid-cell thumbnail decode) now runs on a scope the
    // DIALOG owns, cancelled the moment the dialog is dismissed — NOT on
    // the fragment's view-lifecycle scope, which stays alive after the
    // sheet closes. On the fragment scope, each open/close cycle left the
    // full grid's worth of orphaned decodes queued on ThumbnailEngine's
    // 3-permit semaphore with nothing left to display them; a few cycles
    // in, the NEXT open's thumbnails sat behind a queue of invisible,
    // pointless work and the sheet visibly froze. SupervisorJob so one
    // failed decode doesn't cancel its siblings; Main dispatcher because
    // every one of these coroutines starts and ends on the UI thread (the
    // decode itself suspends into ThumbnailEngine's own IO-gated pool).
    val pickerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    // ── Pass-20 (Media Picker preview): the one video cell the preview pager
    // currently has bound, so the sheet's dismiss path (and closePreview) can
    // stop playback and release its MediaPlayer the moment the preview or the
    // whole sheet goes away — a VideoView left playing behind a GONE overlay
    // is both an audible and a resource bug. Declared here, before the
    // dismiss listener below, purely for Kotlin's local-declaration-order
    // rules; the adapter keeps it current via an onVideoActive callback.
    var activePreviewVideo: TextureMovieView? = null
    // Declared here (before the dismiss listener that reads it) purely for
    // Kotlin's local-declaration-order rules; openPreview assigns the real
    // adapter every time the preview opens over a fresh item list.
    var previewPagerAdapter: PreviewPagerAdapter? = null
    // ── Pass-38 (#9): the playback-coordination registry. Every TextureMovieView
    // reports its own playing-state edges (start/pause/completion/error/release
    // — see the wrapper's setOnPlayingChangedListener); this set is therefore
    // always EXACTLY the videos whose audio is live right now, regardless of
    // which path stopped them (page change, recycle, surface detach, preview
    // close, sheet dismiss). The music player hears only true EDGES of
    // "any video playing" — no timers, no double-resumes.
    val playingPreviewVideos = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<TextureMovieView, Boolean>())
    fun syncVideoAudioCoordination() {
        MusicPlayback.onVideoPlaybackChanged(playingPreviewVideos.isNotEmpty())
    }
    // GOAT-PASS 9 (§5): the live-sync hook is created further down (after
    // allItems/render/queueMode are in scope) and stashed here so THIS
    // dismiss listener — registered earlier — can detach it on close.
    var libSyncHookRef: com.lumidrop.x.media.LibrarySync.Listener? = null
    dialog.setOnDismissListener {
        try { activePreviewVideo?.stopPlayback() } catch (e: Exception) { /* already gone */ }
        activePreviewVideo = null
        // ── Pass-38 (#9 + resource audit): window teardown does NOT run
        // onViewRecycled — every video the pager still had cached (paused
        // neighbors included) would keep its MediaPlayer alive behind a dead
        // window, and any video still playing would leave the music
        // coordinator holding a phantom "video active" state. One explicit
        // release pass over the adapter's live video holders closes both:
        // players released, playing edges reported, the set drains, and the
        // music auto-pause/resume state machine settles on the truth.
        try { previewPagerAdapter?.releaseAllVideoPages() } catch (e: Exception) { /* already gone */ }
        playingPreviewVideos.clear()
        syncVideoAudioCoordination()
        // GOAT-PASS 9 (§5): the live-sync hook dies with the sheet.
        libSyncHookRef?.let { h ->
            try { com.lumidrop.x.media.LibrarySync.removeListener(h) } catch (e: Exception) { /* already gone */ }
        }
        libSyncHookRef = null
        pickerScope.cancel()
        PickerGate.release("media")
    }

    fun col(res: Int) = ContextCompat.getColor(ctx, res)
    fun dp(n: Int) = ctx.dp(n)

    // Pass-3 (R10): three albums per row on every screen width — the
    // layout manager, the spacing decoration, and the cover-sizing math
    // all read this ONE constant so they can never disagree.
    val ALBUM_SPAN_COUNT = 3

    // ---- State -----------------------------------------------------------
    var screen = Screen.ALL_MEDIA
    var typeFilter = MediaTypeFilter.ALL
    var openAlbum: MediaAlbum? = null
    var allItems: List<MediaLibraryItem> = emptyList()
    var loaded = false   // becomes true once the first MediaStore query returns — gates the empty-state text so it can't flash "no results" before there's been a real result to judge
    val selected = LinkedHashMap<Long, MediaLibraryItem>()
    // ── Pass-24 (#4A: Preview opens the MOST RECENTLY selected item) ──────────
    // The media id of the item the user selected LAST (individual tap, drag
    // pass, album pick, or Select All), updated at every selection mutation.
    // openPreview resolves it against buildPreviewList()'s deterministic
    // order; when the hinted item is no longer selected (deselected in the
    // meantime) the fallback is the newest STILL-selected individual item,
    // so removing or changing a selection can never make Preview open an
    // unselected or stale item. -1 = no hint (Clear, or nothing selected
    // yet beyond albums whose members could not be resolved).
    var previewOpenHintId = -1L
    fun notePreviewHint(itemId: Long) { if (itemId >= 0) previewOpenHintId = itemId }
    fun firstMemberIdOfAlbum(bucketId: Long): Long =
        allItems.firstOrNull { it.bucketId == bucketId }?.id ?: -1L
    // GOAT v2 (items 2-4 — album select/Select All/drag-select): whole
    // albums selected as a unit, independent of `selected` above (which
    // stays scoped to individual photos/videos exactly as before). Keyed
    // by bucketId the same way `selected` is keyed by item id, for the
    // same reason: O(1) contains/add/remove from both plain-tap-toggle and
    // the drag-select gesture below, and the map's VALUES double as
    // exactly the list onConfirm needs, with no separate lookup pass.
    val selectedAlbums = LinkedHashMap<Long, MediaAlbum>()
    // Declared (not yet assigned) here so render(), defined below, can
    // legally reference them -- Kotlin resolves local names by DECLARATION
    // point, not assignment point, so this satisfies that even though the
    // real MediaGridAdapter/AlbumGridAdapter instances aren't constructed
    // until after render() itself is defined (they take render() and
    // refreshHeaderSelection() as callbacks, so they have to come after).
    // Both are assigned, in order, before render() is ever actually
    // invoked -- see the bottom of this function.
    lateinit var mediaAdapter: MediaGridAdapter
    // ── Pass-34 Part 2 (#7): the Albums tab's floating search. The live
    // query filters the CURRENT album grouping by name — never the albums'
    // contents or selection identity (selection stays keyed by bucketId) —
    // and `lastAlbums` holds the unfiltered grouping so each keystroke
    // re-filters WITHOUT regrouping the whole library.
    var albumQuery = ""
    var lastAlbums: List<MediaAlbum> = emptyList()
    var albumSearchSerial = 0
    // Declared up here (before updateDock) because updateDock re-places the
    // bar above the selection dock; assigned once the bar exists below.
    var albumSearchSlot: EditText? = null

    lateinit var albumsAdapter: AlbumGridAdapter

    // ---- Build view tree (no adapters yet -- those need render()/
    // refreshHeaderSelection() below as callbacks, so they're built last) --
    val root = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        // ── Pass-36 (#2): NO background of its own. The picker used to paint ──
        // bg_deep over the whole window (its full-screen-dialog heritage):
        // as a sheet that read as a distinctly colored band sitting UNDER the
        // drag-handle strip's bg_sheet surface — the "gap" between the handle
        // row and the title. Letting the BottomSheet's own bg_sheet_top
        // surface carry the whole sheet makes handle + header one continuous
        // header, exactly like the Files and Music sheets.
    }

    // ---- v1.0 chrome redesign: professional app-bar + joined controls ----
    // The header is a real app bar now: back/close on the left, a TWO-LINE
    // identity block (bold title + muted, context-aware subtitle that says
    // what this screen holds), and Select All right-aligned on the same
    // baseline. The old single cramped row made the title, count, and three
    // actions fight for one 40dp-tall line — the "ugly/unprofessional"
    // reading. (Pass-20 moved the Add action — plus the new Preview action
    // with its live selection-count chip — down into the floating bottom
    // dock; the header keeps only browse-level chrome.)
    val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // ── Pass-36 (#2): a tight 6dp of top padding — the drag-handle strip ──
        // directly above is the same surface now, so the header reads as one
        // continuous block (the old 14dp dated from the header being the very
        // top of a full-screen window, not a sheet's content).
        setPadding(dp(16), dp(6), dp(14), dp(10))
    }
    val backOrClose = Widgets.iconButton(ctx, R.drawable.ic_close, col(R.color.text_primary), sizeDp = 40, iconSizeDp = 18)
    val titleView = TextView(ctx).apply {
        text = "All Media"; textSize = 16.5f; setTextColor(col(R.color.text_primary))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    // v1.0: the subtitle is the header's second line — item counts, album
    // counts, and the live selection total land HERE (one place, muted,
    // 11.5sp), so the count pill can stay a small accent chip instead of
    // doing double duty as the only selection signal.
    val subtitleView = TextView(ctx).apply {
        text = "Loading\u2026"; textSize = 11.5f; setTextColor(col(R.color.text_muted))
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.topMargin = dp(1)
        }
    }
    val titleBlock = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginStart = dp(6); it.marginEnd = dp(10)
        }
    }
    titleBlock.addView(titleView); titleBlock.addView(subtitleView)
    val selectAllBtn = TextView(ctx).apply {
        text = "Select All"; textSize = 12.5f; gravity = Gravity.CENTER
        setTextColor(col(R.color.accent))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = Widgets.rippleRect(ctx, android.graphics.Color.TRANSPARENT, radiusDp = 8)
        clipToOutline = true
        setPadding(dp(10), dp(7), dp(10), dp(7))
        isClickable = true; isFocusable = true
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.marginEnd = dp(6)
        }
    }
    // ── Pass-21 (Clear is always reachable) ──────────────────────────────
    // The old single morphing button only ever offered "Clear" in the
    // everything-selected state, so a PARTIAL selection — the normal case —
    // had no way to be cleared without first selecting everything. A dedicated
    // Clear sits beside Select All and is visible whenever ANY selection
    // exists, anywhere in the picker (the dock's count is global, so a
    // selection made on another screen must be clearable from the current one
    // too). It wipes the WHOLE selection; Select All keeps adding the current
    // screen's items.
    val clearBtn = TextView(ctx).apply {
        text = "Clear"; textSize = 12.5f; gravity = Gravity.CENTER
        setTextColor(col(R.color.text_secondary))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = Widgets.rippleRect(ctx, android.graphics.Color.TRANSPARENT, radiusDp = 8)
        clipToOutline = true
        setPadding(dp(10), dp(7), dp(10), dp(7))
        isClickable = true; isFocusable = true
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.marginEnd = dp(6)
        }
    }
    // ── Pass-20 (the confirmation actions moved to a bottom dock) ──────────
    // The old header Add pill is retired: the Add button now lives in a
    // floating dock pinned to the bottom-center of the sheet (always above
    // the scrolling grid, never moving with it), with a Preview button +
    // live selection count immediately to its left — the professional
    // media-picker action bar. Built below, after the content area exists.
    header.addView(backOrClose)
    header.addView(titleBlock)
    header.addView(clearBtn)
    header.addView(selectAllBtn)
    root.addView(header)

    // ---- v1.0: joined segmented control (All Media | Albums) --------------
    // One pill CONTAINER holding two inner segments — reads as a single
    // control (the standard iOS/Google Photos pattern) instead of two
    // floating buttons fighting the filter row below it. The active segment
    // is a solid accent pill; the inactive one is transparent, so the
    // container's own border IS the inactive state. Same visual language as
    // DeviceAdapter's mode control — one idiom across the whole app.
    fun segmentLabel(text: String) = TextView(ctx).apply {
        this.text = text; textSize = 12.5f; gravity = Gravity.CENTER
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isClickable = true; isFocusable = true
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    }
    val tabAllMedia = segmentLabel("All Media")
    val tabAlbums = segmentLabel("Albums")
    val tabsRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_secondary)
        setPadding(dp(3), dp(3), dp(3), dp(3))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.leftMargin = dp(16); it.rightMargin = dp(16); it.topMargin = dp(2)
        }
        addView(tabAllMedia); addView(tabAlbums)
    }
    root.addView(tabsRow)

    // ---- v1.0: compact type filter (All / Photos / Videos) ----------------
    // Small quiet chips one tier below the segmented control: 11.5sp, tight
    // padding, active = soft accent fill + accent text, inactive = muted
    // text on nothing. They are a FILTER, not a navigation — the hierarchy
    // between this row and the segmented control above is now legible.
    fun filterChip(text: String) = TextView(ctx).apply {
        this.text = text; textSize = 11.5f; gravity = Gravity.CENTER
        setPadding(dp(12), dp(6), dp(12), dp(6))
        isClickable = true; isFocusable = true
        clipToOutline = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.marginEnd = dp(6)
        }
    }
    val chipAll = filterChip("All")
    val chipPhotos = filterChip("Photos")
    val chipVideos = filterChip("Videos")
    val filterRow = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(16), dp(8), dp(16), dp(2))
        addView(chipAll); addView(chipPhotos); addView(chipVideos)
    }
    root.addView(filterRow)

    // ---- Content area: grid / albums / loading / empty --------------------
    val contentFrame = FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    // Target cell size computed from the real screen width (dp throughout —
    // no px/dp mixing) rather than a guessed constant, so thumbnails are
    // requested at roughly their true on-screen size: big enough to look
    // sharp, not so big that decoding/caching them wastes memory the grid
    // doesn't need. Matches this same "ask the system, don't guess" bias as
    // the Part 4.1 status-bar-inset fix elsewhere in this phase.
    //
    // Phase 5 (§5.5): the column count itself now scales with the device —
    // a 4-column grid on a tablet's 800dp+ width leaves ~200dp cells that
    // look sparse and waste a third of the screen; >=110dp per cell keeps
    // phone behavior identical (a 360dp screen still computes 4) while a
    // 800dp tablet gets 7 and an album grid gains a third column. Both
    // grids recompute their cell math from their OWN span count below, so
    // nothing else needs to know this changed.
    val screenWidthDp = (ctx.resources.displayMetrics.widthPixels / ctx.resources.displayMetrics.density).toInt()
    val gridSpanCount = (screenWidthDp / 110).coerceAtLeast(4)
    val gridHorizontalPaddingDp = 13
    val gridCellDp = ((screenWidthDp - gridHorizontalPaddingDp * 2) / gridSpanCount).coerceIn(64, 120)

    val mediaGrid = RecyclerView(ctx).apply {
        layoutManager = GridLayoutManager(ctx, gridSpanCount)
        // Pass-20: bottom padding grew 24 → 96dp — the grid's last rows must
        // clear the floating Preview/Add dock (which visually rides ~72dp up
        // from the sheet's bottom edge) so no cell is ever hidden behind it
        // or untappable.
        setPadding(dp(gridHorizontalPaddingDp), dp(4), dp(gridHorizontalPaddingDp), dp(96))
        clipToPadding = false
        addItemDecoration(GridSpacingDecoration(gridSpanCount, dp(3)))
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    val albumsGrid = RecyclerView(ctx).apply {
        // Pass-3 (R10): three albums per row on EVERY screen width (was 2
        // on phones) — balanced spacing via the same GridSpacingDecoration,
        // consistent square sizing via the span-aware cover math below.
        layoutManager = GridLayoutManager(ctx, ALBUM_SPAN_COUNT)
        // Pass-20: 96dp bottom clearance for the floating dock (see mediaGrid).
        // ── Pass-34 Part 2 (#7): 150dp — the Albums tab's floating search
        // bar rides below the dock's slot, so the last row must clear BOTH
        // stacked pills (search at ~18dp, dock above it at ~76dp, plus the
        // row's own breathing room).
        setPadding(dp(13), dp(6), dp(13), dp(150))
        clipToPadding = false
        addItemDecoration(GridSpacingDecoration(ALBUM_SPAN_COUNT, dp(10)))
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        visibility = View.GONE
    }
    val loadingSpinner = ProgressBar(ctx).apply {
        indeterminateTintList = android.content.res.ColorStateList.valueOf(col(R.color.accent))
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.CENTER
        }
    }
    // Phase 5 (§5.5): the empty state is a designed block now (icon + bold
    // title + muted subtitle, centered as a unit) instead of a single bare
    // muted line floating 60dp from the top — "loading / empty states" are
    // explicitly on this phase's list, and a full-screen picker that finds
    // nothing deserves the same visual language as every other empty state
    // in the app (see EmptyStateIllustration's users) rather than a text
    // fragment. showEmpty(title, sub) is the single writer; render() below
    // is its only caller, so the four call sites can't drift apart.
    val emptyState = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.CENTER
            it.leftMargin = dp(36); it.rightMargin = dp(36)
        }
    }
    val emptyIcon = ImageView(ctx).apply {
        setImageDrawable(Widgets.icon(ctx, R.drawable.ic_image, col(R.color.text_muted)))
        alpha = 0.6f
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).also { it.bottomMargin = dp(12) }
    }
    val emptyTitle = TextView(ctx).apply {
        textSize = 15f; setTextColor(col(R.color.text_primary))
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    val emptySub = TextView(ctx).apply {
        textSize = 12f; setTextColor(col(R.color.text_muted))
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(4) }
    }
    emptyState.addView(emptyIcon); emptyState.addView(emptyTitle); emptyState.addView(emptySub)
    fun showEmpty(title: String, sub: String) {
        emptyTitle.text = title
        emptySub.text = sub
        emptyState.visibility = View.VISIBLE
    }
    fun hideEmpty() { emptyState.visibility = View.GONE }
    contentFrame.addView(mediaGrid)
    contentFrame.addView(albumsGrid)
    contentFrame.addView(loadingSpinner)
    contentFrame.addView(emptyState)

    // ════════════════════════════════════════════════════════════════════
    // ── Pass-20: full-screen PREVIEW interface ────────────────────────────
    // A dedicated overlay INSIDE the same dialog (the picker's chrome and
    // grids — and therefore the whole SELECTION state — sit untouched
    // underneath), so closing the preview returns to the Media Picker with
    // every selected item exactly as it was. Holds: a top bar (close,
    // item name, "n / total" position indicator), a snapping one-item-per-
    // page horizontal pager over ONLY the selected items (deterministic
    // order: individually-selected items in selection order, then whole
    // albums' members in album-selection order, deduped by media id), and
    // explicit prev/next chevrons beside the swipe/fling navigation.
    // ════════════════════════════════════════════════════════════════════
    val previewOverlay = FrameLayout(ctx).apply {
        setBackgroundColor(0xF5000000.toInt())
        visibility = View.GONE
        // ── Pass-35 (#12): the overlay lives in the sheet's WINDOW layer ──
        // now, as a sibling above the sheet — inside the same backdrop that
        // owns tap-to-dismiss. It must consume its own taps (the old
        // full-screen stage absorbed them for free): a tap on empty preview
        // space toggles the preview chrome at most, never dismisses the
        // whole picker underneath.
        isClickable = true
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }
    var previewItems: List<MediaLibraryItem> = emptyList()
    // ── Pass-29 Part 2 (#2): true while this sheet was opened as a QUEUED- ────
    // FILES preview (queuePreview above) — the previewer runs over the
    // caller's list instead of the picker's selection, and closing the
    // preview dismisses the whole sheet.
    var queuePreviewMode = false
    // ── Pass-30 (#4): the queue-preview selection bridge — the caller's
    // membership reader and flip handler (see the signature docs). Held as
    // plain locals next to the flag they're qualified by; both are null in
    // ordinary picker mode.
    val queueItemIsQueuedRef = queueItemIsQueued
    val onQueueItemFlipRef = onQueueItemFlip
    // ── Pass-30 (#3): the page the preview CHROME says is showing — the ────────
    // last position updatePreviewChrome painted. Single writer: updatePreviewChrome
    // itself. This is deliberately NOT a RecyclerView layout read: the pager's
    // findFirstVisibleItemPosition is transient mid-settle (it reports the OLD
    // page while a programmatic scroll or snap is still in flight), which is
    // exactly why the filmstrip's tap gate used to swallow a first tap and
    // demand a second one. Decisions that mean "the page the user is looking
    // at" (tile taps, the video-page tap toggle) read this value instead;
    // decisions that mean "where the pager physically is right now" (the
    // scroll-idle chrome sync, arrow math) keep the layout read.
    var previewUiPosition = -1
    // ── Pass-21 (the filmstrip, declarations) ─────────────────────────────
    // Declared early because updatePreviewChrome (below) drives the strip's
    // highlight on every position change; the strip's VIEW is added to the
    // overlay after the nav buttons so its taps layer above the pager, and
    // its adapter is (re)built per openPreview — visible only for
    // multi-item previews.
    var previewFilmAdapter: PreviewFilmstripAdapter? = null
    val previewFilmstrip = androidx.recyclerview.widget.RecyclerView(ctx).apply {
        layoutManager = androidx.recyclerview.widget.LinearLayoutManager(ctx,
            androidx.recyclerview.widget.LinearLayoutManager.HORIZONTAL, false)
        setPadding(dp(16), 0, dp(16), 0)
        clipToPadding = false
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.BOTTOM
            it.bottomMargin = dp(68)   // clears the 44dp video control row + breathing room
        }
    }

    val previewTopBar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(8), dp(14), dp(4))
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.TOP
        }
    }
    // ── Pass-22 (#1B — legibility chrome): a quiet scrim behind the top bar ──
    // Every gallery-grade photo viewer shades the bar zone so white-on-photo
    // text stays readable over a bright sky. A plain four-stop gradient, the
    // same idiom the transfer screens use for their edge fades.
    // ── Pass-29 Part 2 (#3A): the scrim is the floating controls' ────────────
    // SEMI-TRANSPARENT backdrop now. The media pager used to stop 56dp below
    // the top of the overlay, so this zone sat over the overlay's flat black
    // and read as an opaque toolbar row of its own. The pager now renders
    // full-bleed UNDER the bar, and the gradient's leading stops were eased
    // (55%/35% → 45%/30% black) so noticeably more of the photo shows
    // through while the white title and icons keep full contrast.
    val previewTopScrim = View(ctx).apply {
        background = android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x73000000.toInt(), 0x4D000000.toInt(), 0x00000000.toInt(), 0x00000000.toInt())
        )
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(72)).also {
            it.gravity = Gravity.TOP
        }
    }
    val previewCloseBtn = Widgets.iconButton(ctx, R.drawable.ic_close, android.graphics.Color.WHITE, sizeDp = 40, iconSizeDp = 18)
    val previewTitleView = TextView(ctx).apply {
        textSize = 13.5f; setTextColor(android.graphics.Color.WHITE)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginStart = dp(4); it.marginEnd = dp(10)
        }
    }
    val previewPosView = TextView(ctx).apply {
        textSize = 12f; setTextColor(0xB3FFFFFF.toInt())
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    previewTopBar.addView(previewCloseBtn)
    previewTopBar.addView(previewTitleView)
    previewTopBar.addView(previewPosView)
    // ── Pass-29 Part 2 (#3A): the add order matters now. The old layout ──────
    // added the scrim + bar first and the pager BELOW them in z-order was
    // harmless because the pager's 56dp top margin kept the two surfaces
    // from ever overlapping. With the pager FULL-BLEED (topMargin 0), the
    // pager must be the overlay's BOTTOM layer and the floating chrome
    // (scrim, bar, then arrows/zoom/filmstrip above) added ON TOP of it —
    // otherwise the media pages would cover the bar and swallow every tap
    // aimed at its buttons. The addView calls therefore moved here, after
    // the pager's own add below.
    // ── Pass-38 (#3): PreviewPagerRecyclerView — never claims a multi-touch
    // stream (see its class comment); a plain RecyclerView here let a
    // two-finger centroid drift read as a scroll start and CANCEL the image
    // cell mid-pinch, the "pinch only works if both fingers land perfectly"
    // half of the zoom-glitch report.
    val previewPager = PreviewPagerRecyclerView(ctx).apply {
        // One item per page, snapping — RecyclerView's own PagerSnapHelper
        // is part of the same androidx artifact as GridLayoutManager (which
        // this sheet already uses), so no new dependency and it works across
        // the full API range the app supports.
        layoutManager = GridLayoutManager(ctx, 1, GridLayoutManager.HORIZONTAL, false)
        // ── Pass-29 Part 2 (#3A): FULL-BLEED pages. The old dp(56) topMargin ──
        // reserved a whole opaque band for the top bar — the controls
        // consumed a separate row and reduced the media's viewing height.
        // The bar now FLOATS over the media on the semi-transparent scrim
        // (previewTopScrim above), so the pager fills the overlay edge to
        // edge and the media's own aspect handling (FIT_CENTER images,
        // centered video) is untouched — same chrome, more media.
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).also {
            it.topMargin = dp(0); it.bottomMargin = dp(0)
        }
    }
    // ── Pass-33 (#2): the snap helper is a FIELD now. A programmatic page ──────
    // jump (filmstrip tap) must neutralize it for the duration: stopping an
    // in-flight scroll transitions the pager to IDLE, and PagerSnapHelper's
    // own scroll listener answers EVERY idle transition by starting a fresh
    // smooth scroll toward the page nearest to snap — AFTER our stopScroll()
    // already ran. The jump that follows (scrollToPositionWithOffset) does
    // not cancel that new smooth scroller, so the pager used to land BACK on
    // the previous page — a just-played video reappearing over the tapped
    // image — the exact reported regression. Detaching the helper first
    // silences its idle correction; re-attaching after the jump's layout
    // restores normal swipe snapping.
    val previewSnapHelper = androidx.recyclerview.widget.PagerSnapHelper().also { it.attachToRecyclerView(previewPager) }
    previewOverlay.addView(previewPager)          // bottom layer: the media, full-bleed
    previewOverlay.addView(previewTopScrim)       // then the semi-transparent chrome
    previewOverlay.addView(previewTopBar)

    fun previewCurrentPosition(): Int {
        val lm = previewPager.layoutManager as? GridLayoutManager ?: return -1
        val pos = lm.findFirstVisibleItemPosition()
        return if (pos >= 0) pos else -1
    }

    fun navBtn(rotate: Boolean) = Widgets.iconButton(ctx, R.drawable.ic_chevron_right, android.graphics.Color.WHITE, sizeDp = 44, iconSizeDp = 22).apply {
        rotation = if (rotate) 180f else 0f
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(0x33000000.toInt())
            setStroke(dp(1), 0x33FFFFFF.toInt())
        }
        layoutParams = FrameLayout.LayoutParams(dp(44), dp(44)).also {
            // ── Pass-27 (#2.1): the arrows live immediately ABOVE the filmstrip ──
            // The strip (the Images/Videos navigation bar — tap a thumbnail,
            // jump to that item) is BOTTOM-anchored with a 68dp bottom margin
            // (clearing the 44dp video control row + breathing room) and is
            // 44dp tall, so its top edge sits 112dp above the screen bottom;
            // the arrows' own bottom edge sits 120dp up — a small, visually
            // balanced 8dp gap above the strip, one arrow flush to each side
            // (10dp side margins). ONE identical placement for image and video
            // pages alike: they no longer sit beside the video progress bar
            // (the Pass-26 per-page re-alignment is gone with this), they are
            // always vertically aligned with each other by construction (one
            // shared gravity + bottom margin), and their position never moves
            // when the page changes. Both buttons stay 44dp with the same
            // translucent outlined disc they have always worn.
            it.gravity = Gravity.BOTTOM or (if (rotate) Gravity.START else Gravity.END)
            it.bottomMargin = dp(120)   // 68 (strip clearance) + 44 (strip height) + 8 (the gap)
            if (rotate) it.marginStart = dp(10) else it.marginEnd = dp(10)
        }
    }
    val previewPrevBtn = navBtn(true)
    val previewNextBtn = navBtn(false)
    // ── Pass-31 (#1): the arrows compute their target from the page the ──────
    // CHROME is showing (previewUiPosition — kept in lockstep with the pager
    // by updatePreviewChrome's single writer), never from the pager's
    // transient layout read: mid-settle, findFirstVisibleItemPosition can
    // report the page being LEFT, which made an arrow tap step from the WRONG
    // base and land two pages away — another "the category I picked didn't
    // show" shape. smoothScrollToPosition replaces any in-flight smooth
    // scroller on its own (LinearLayoutManager stops the previous one), so no
    // explicit stopScroll is needed here.
    fun previewNavBase(): Int =
        previewUiPosition.takeIf { it >= 0 && it < previewItems.size } ?: previewCurrentPosition().coerceAtLeast(0)
    // ── Pass-35 (#6): the arrows now navigate through the SAME hardened ──
    // jump the filmstrip taps use (stopScroll + snap-detach +
    // scrollToPositionWithOffset, assigned once the jump itself is declared
    // below — the old raw smoothScrollToPosition here was the last
    // unprotected navigation path: an in-flight smooth scroll or the snap
    // helper's idle correction could land the pager BACK on the page it was
    // leaving — the just-played video over the tapped image, first tap
    // swallowed — while the chrome had already painted the target).
    var jumpToPreviewPageFn: (Int) -> Unit = { }
    previewPrevBtn.setOnClickListener { jumpToPreviewPageFn((previewNavBase() - 1).coerceAtLeast(0)) }
    previewNextBtn.setOnClickListener { jumpToPreviewPageFn((previewNavBase() + 1).coerceAtMost(previewItems.size - 1)) }
    previewOverlay.addView(previewPrevBtn)
    previewOverlay.addView(previewNextBtn)

    // ══ Pass-26 (#2.1): the always-reliable "return to 1×" control ══════════
    // Double-tap and pinch-out both return to fit scale, but neither is
    // DISCOVERABLE — the brief asks for a reliable, explicit way back. The
    // pill appears (fade-in) the moment the current page is zoomed past
    // 1.05× and disappears when it settles back; one tap animates the
    // CURRENT page to fit. Top-end, under the top bar and clear of the
    // filmstrip and the video control row on every screen size.
    var zoomResetWantsVisible = false
    // ── Pass-29 Part 2 (#3B): the preview chrome's visibility state. True ────
    // (the default, and every fresh open) shows the top bar + scrim, the
    // nav arrows, the filmstrip, the zoom pill and the per-cell video
    // controls; one confirmed tap on the media flips the whole set in or
    // out together. Declared this early because setZoomResetVisible's
    // chrome gate below reads it.
    var previewChromeVisible = true
    val previewZoomResetBtn = TextView(ctx).apply {
        text = "1\u00d7"
        textSize = 11.5f
        setTextColor(android.graphics.Color.WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(13).toFloat()
            setColor(0x99000000.toInt())
            setStroke(dp(1), 0x33FFFFFF.toInt())
        }
        setPadding(dp(12), dp(5), dp(12), dp(5))
        isClickable = true; isFocusable = true
        visibility = View.GONE
        contentDescription = "Reset zoom"
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.TOP or Gravity.END
            it.topMargin = dp(62); it.marginEnd = dp(14)
        }
    }
    fun setZoomResetVisible(want: Boolean) {
        // ── Pass-29 Part 2 (#3B): the pill's EFFECTIVE visibility is gated ───
        // by the chrome state — while the chrome is hidden it can never
        // appear, and a chrome restore re-evaluates it fresh. The skip-guard
        // compares the effective state against the pill's ACTUAL visibility
        // too, so a pill forced away by a chrome hide comes back on the next
        // evaluation even when its own zoom want never moved.
        val changed = want != zoomResetWantsVisible
        zoomResetWantsVisible = want
        val show = want && previewChromeVisible
        if (!changed && previewZoomResetBtn.visibility == (if (show) View.VISIBLE else View.GONE)) return
        previewZoomResetBtn.animate().cancel()
        if (show) {
            previewZoomResetBtn.visibility = View.VISIBLE
            previewZoomResetBtn.alpha = 0f
            previewZoomResetBtn.animate().alpha(1f).setDuration(Motion.DURATION_SHORT).start()
        } else {
            previewZoomResetBtn.animate().alpha(0f).setDuration(Motion.DURATION_SHORT)
                .withEndAction { if (!(zoomResetWantsVisible && previewChromeVisible)) previewZoomResetBtn.visibility = View.GONE }
                .start()
        }
    }
    previewZoomResetBtn.setOnClickListener { previewPagerAdapter?.resetCurrentZoom(previewPager) }
    previewOverlay.addView(previewZoomResetBtn)

    // ══ Pass-29 Part 2 (#3B): tap on the media toggles the whole chrome ══════
    // One confirmed single tap on the displayed image/video hides EVERY
    // preview overlay element — the top bar with its scrim, title, position,
    // select control and close icon, the left/right arrows, the filmstrip,
    // the "1×" zoom pill and each video cell's control row + big play disc —
    // and a second tap brings the whole set back. Short alpha fades both
    // ways (the same Motion.DURATION_SHORT idiom the zoom pill uses), with
    // the media pager itself untouched: the pages keep their fit-center
    // aspect handling, zoom state and playback exactly as they were, and
    // while the chrome is away the media simply owns the full overlay —
    // which is now genuinely full-bleed (#3A).
    //
    // Accident safety, by construction: the toggle only ever fires from a
    // GestureDetector onSingleTapConfirmed — a scroll (page swipe), a
    // pinch, a double-tap zoom or a drag on the seek bar can never qualify
    // — and taps that land ON a control (arrows, filmstrip tiles, buttons,
    // the seek bar) are consumed by those views and never reach a media
    // tap detector at all.
    fun setPreviewChromeVisible(visible: Boolean, animate: Boolean = true) {
        if (visible == previewChromeVisible) return
        previewChromeVisible = visible
        val chrome = listOf(previewTopScrim, previewTopBar, previewPrevBtn, previewNextBtn, previewFilmstrip)
        for (v in chrome) {
            v.animate().cancel()
            if (animate) {
                if (visible) {
                    v.visibility = View.VISIBLE
                    v.alpha = 0f
                    v.animate().alpha(1f).setDuration(Motion.DURATION_SHORT)
                        .setInterpolator(Motion.DECELERATE).start()
                } else {
                    v.animate().alpha(0f).setDuration(Motion.DURATION_SHORT)
                        .setInterpolator(Motion.DECELERATE)
                        .withEndAction { if (!previewChromeVisible) v.visibility = View.GONE }
                        .start()
                }
            } else {
                v.visibility = if (visible) View.VISIBLE else View.GONE
                v.alpha = 1f
            }
        }
        // The "1×" pill: on restore, re-evaluate it through its own
        // chrome-gated logic (shows again only if the page is still
        // zoomed); on hide, animate it away with the rest of the chrome.
        if (visible) {
            setZoomResetVisible(zoomResetWantsVisible)
        } else {
            previewZoomResetBtn.animate().cancel()
            previewZoomResetBtn.animate().alpha(0f).setDuration(Motion.DURATION_SHORT)
                .withEndAction { if (!previewChromeVisible) previewZoomResetBtn.visibility = View.GONE }
                .start()
        }
        // The per-cell half (video control rows + big play discs) lives in
        // the pager adapter; it applies the state to every attached cell
        // and to any cell that binds while the chrome is away.
        previewPagerAdapter?.setChromeVisible(visible, animate)
    }

    // The single toggle action both tap paths share (the image cells' own
    // gesture detector, and the pager-level observer for video pages).
    val onPreviewMediaTap = { setPreviewChromeVisible(!previewChromeVisible) }

    // ── The pager-level tap observer ──────────────────────────────────────────
    // A VideoView with no MediaController attached does not consume touch
    // events, so a tap on a video page falls through the cell to the pager
    // itself — this observer sees exactly that stream (taps on IMAGE cells
    // never arrive here: the zoomable image consumes every gesture
    // downstream of its own detector, which fires the same toggle). The
    // listener returns false on every event, so the pager's own paging,
    // interception and recycling behavior is completely untouched.
    val previewTapDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // Pass-30 (#3): the CHROME's page decides whether this is a video
            // page (a transient layout read could flip the verdict mid-settle).
            val cur = previewItems.getOrNull(previewUiPosition)
            if (cur != null && cur.isVideo) onPreviewMediaTap()
            return true
        }
    })
    previewPager.setOnTouchListener { _, e -> previewTapDetector.onTouchEvent(e); false }

    // ══ Pass-27 (#2.2): select / deselect from INSIDE the preview — refined ══
    // The Pass-26 control swapped a 40dp iconButton's ICON and full-size
    // background disc between states, which read as a chunky two-piece button
    // rather than one mark. This is the classic minimalist gallery selection
    // mark instead, built as two plain drawables over a 34dp touch target:
    //   • UNSELECTED — a thin 1.5dp semi-transparent white ring, hollow: the
    //     instantly readable "not selected" mark, quiet on any photo;
    //   • SELECTED — the same circle SOLID in the app's accent with a small
    //     white check drawn inside it: unmistakable, and consistent with the
    //     accent-language every other selected state in the picker uses.
    // No label, no oversized disc, no background swap — the circular ripple
    // stays for touch feedback and the tap pop below stays for the flip. One
    // tap toggles the CURRENTLY VIEWED item in the REAL selection (the same
    // `selected`/`selectedAlbums` maps the picker grids and the Add action
    // read) — never a preview-local copy; the preview stays open, and both
    // images and videos use the identical control. Click wiring lives below,
    // after the picker's own selection-sync helpers are declared (Kotlin
    // local declaration order); painting-only helpers stay here so
    // updatePreviewChrome keeps the control in step with the shown page.
    val previewSelectBtn = android.widget.ImageView(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(dp(34), dp(34))
        background = ContextCompat.getDrawable(ctx, R.drawable.bg_icon_ripple)
        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
        setPadding(dp(6), dp(6), dp(6), dp(6))   // 22dp visual mark inside the 34dp touch target
        isClickable = true; isFocusable = true
        contentDescription = "Select this item"
    }
    fun previewSelectMark(on: Boolean) = if (on) {
        android.graphics.drawable.LayerDrawable(arrayOf(
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(col(R.color.accent))
            },
            android.graphics.drawable.InsetDrawable(
                Widgets.icon(ctx, R.drawable.ic_check, android.graphics.Color.WHITE), dp(4), dp(4), dp(4), dp(4)),
        ))
    } else {
        // A hollow ring: 1.5dp stroke, no fill — the immediately
        // distinguishable "empty" half of the pair. (Pass-28 compile fix:
        // dpf is a Context extension in UiTokens, so it must be qualified
        // against the in-scope ctx from inside this GradientDrawable.apply
        // block — the bare call Pass-26/27 shipped resolved against the
        // drawable receiver and never compiled.)
        android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setStroke(ctx.dpf(1.5f).toInt(), 0xB3FFFFFF.toInt())
        }
    }
    fun previewItemSelectionOn(item: MediaLibraryItem?): Boolean =
        item != null && (selected.containsKey(item.id) || selectedAlbums.containsKey(item.bucketId))
    fun paintPreviewSelectState(item: MediaLibraryItem?) {
        // ── Pass-30 (#4): in queue-preview mode the SAME control paints ──────
        // QUEUE MEMBERSHIP — the caller's reader is the source of truth
        // ("is this item still in the Queued Files section right now?"),
        // never the picker's selection maps (which are empty on that path).
        // In picker mode the read is unchanged.
        val on = if (queuePreviewMode && queueItemIsQueuedRef != null) {
            item != null && queueItemIsQueuedRef.invoke(item)
        } else previewItemSelectionOn(item)
        previewSelectBtn.setImageDrawable(previewSelectMark(on))
        previewSelectBtn.contentDescription = if (on) "Deselect this item" else "Select this item"
    }
    previewTopBar.addView(previewSelectBtn)

    fun updatePreviewChrome(position: Int) {
        if (position < 0 || position >= previewItems.size) return
        // Pass-30 (#3): the single-writer update — the chrome's notion of the
        // shown page moves HERE, so the filmstrip tap gate and the video-page
        // tap toggle always read the same page the title/position/select
        // controls are painted for.
        previewUiPosition = position
        // ── Pass-31 (#1): the page changed — every video that is no longer ─────
        // the current page stops playing THIS instant. This is the half of
        // the reported regression the jump fix alone cannot cover: a VideoView
        // scrolled out of view (the pager caches ±1 neighbors) can keep its
        // audio running, and a mid-settle return to the old page used to
        // resume a "ghost" playback nobody was looking at. Pausing here, at
        // the single writer of "which page is showing", guarantees the
        // previous video's picture, sound and progress clock die the moment
        // any navigation — tile tap, arrow, or swipe — lands on a new page,
        // and the per-cell ticker repaints its toggle from the player's real
        // state right after.
        previewPagerAdapter?.pauseVideoPagesExcept(position)
        previewTitleView.text = previewItems[position].name
        previewPosView.text = "${position + 1} / ${previewItems.size}"
        // ── Pass-27 (#2.1): the arrows' placement is FIXED now (immediately ────
        // above the filmstrip, both sides — see navBtn), identical for image
        // and video pages, so there is no per-page re-alignment left to run.
        // Pass-26 (#2.5): the select control reflects the CURRENT page's
        // real selection state on every position change.
        paintPreviewSelectState(previewItems[position])
        // Pass-21: the filmstrip follows the pager — highlight moves, and the
        // strip scrolls just enough to reveal the current tile. A no-op for
        // single-item previews (no adapter, strip GONE).
        previewFilmAdapter?.setCurrent(position)
        if (previewFilmstrip.visibility == View.VISIBLE) previewFilmstrip.smoothScrollToPosition(position)
    }
    previewPager.addOnScrollListener(object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                updatePreviewChrome(previewCurrentPosition())
                // ── Pass-38 (#2): the settle is the memory boundary — every ──
                // cached non-current page trades its zoom-refined bitmap back
                // to the fit one (off-screen, invisible), so at most ONE
                // refined bitmap is live at any moment.
                previewPagerAdapter?.demoteRefinedBitmaps(previewCurrentPosition())
            }
        }
    })

    // ── Pass-35 (#6): the ONE hardened page jump, shared by the filmstrip ────
    // taps AND the nav arrows (assigned into the arrows' slot above). The
    // exact Pass-31/Pass-33 discipline: navigate unless the pager itself is
    // idle and already laid out on the target page (chrome state alone is
    // never trusted); stop any in-flight scroll first so the programmatic
    // jump is final; detach the snap helper for the jump so its idle
    // correction can't scroll back to the page being left; re-attach once
    // the jumped layout has landed. Chrome, filmstrip highlight, the select
    // control and the video-stop pass (updatePreviewChrome →
    // pauseVideoPagesExcept, which FULLY STOPS every other video when the
    // target is an image) all repaint in the same tap — so the tapped image
    // shows on the FIRST attempt and the previous video's frame, sound and
    // progress clock are gone before it renders.
    fun jumpToPreviewPage(pos: Int) {
        if (pos < 0 || pos >= previewItems.size) return
        val settledThere = previewPager.scrollState == RecyclerView.SCROLL_STATE_IDLE &&
            previewCurrentPosition() == pos
        if (settledThere) return
        previewSnapHelper.attachToRecyclerView(null)
        try { previewPager.stopScroll() } catch (e: Exception) { /* no scroller attached */ }
        (previewPager.layoutManager as? GridLayoutManager)
            ?.scrollToPositionWithOffset(pos, 0)
            ?: previewPager.scrollToPosition(pos)
        updatePreviewChrome(pos)
        previewPager.post { previewSnapHelper.attachToRecyclerView(previewPager) }
    }
    jumpToPreviewPageFn = { pos -> jumpToPreviewPage(pos) }

    // Pass-21: the filmstrip layers above the pager — added last so its tile
    // taps always win over the pager's own gesture handling.
    previewOverlay.addView(previewFilmstrip)

    /** The selected items in the preview's deterministic order: individually
     *  selected items first (LinkedHashMap insertion order = the user's
     *  selection order), then each selected album's members (albums in
     *  selection order, members in library order), deduped by media id so an
     *  item that is BOTH individually selected and inside a selected album
     *  appears exactly once, and NEVER an unselected item. */
    fun buildPreviewList(): List<MediaLibraryItem> {
        val out = LinkedHashMap<Long, MediaLibraryItem>()
        for (item in selected.values) out[item.id] = item
        for (album in selectedAlbums.values) {
            for (item in allItems) {
                if (item.bucketId == album.bucketId && !out.containsKey(item.id)) out[item.id] = item
            }
        }
        return out.values.toList()
    }

    fun openPreview(overrideItems: List<MediaLibraryItem>? = null, overrideIndex: Int? = null) {
        // ── Pass-29 Part 2 (#2): the queued-files entry hands the previewer ──
        // its item list DIRECTLY (the Queued Files section's media, in exact
        // queue order) — the picker-selection machinery (buildPreviewList,
        // the selection hint) is skipped entirely for that call shape.
        val items = overrideItems ?: buildPreviewList()
        if (items.isEmpty()) return
        previewItems = items
        val adapter = PreviewPagerAdapter(
            scope = pickerScope,
            items = items,
            onVideoActive = { v -> activePreviewVideo = v },
            onVideoInactive = { v -> if (activePreviewVideo === v) activePreviewVideo = null },
            // ── Pass-38 (#9): the exact set of playing videos, kept by the ──
            // sheet and fed to MusicPlayback as emptiness EDGES — pause all
            // music while any video plays, auto-resume only the session that
            // was playing before, only when the last video actually stops.
            onVideoPlayingChanged = { v, playing ->
                if (playing) playingPreviewVideos.add(v) else playingPreviewVideos.remove(v)
                syncVideoAudioCoordination()
            },
            // Pass-26 (#2.1): the overlay's "1×" control follows the live
            // zoom of the currently bound image cell (visible past 1.05×).
            onZoomScaleChanged = { scale -> setZoomResetVisible(scale > 1.05f) },
            // Pass-29 Part 2 (#3B): a confirmed tap on an image page toggles
            // the whole preview chrome (video pages reach the same toggle
            // through the pager-level observer).
            onMediaTap = onPreviewMediaTap,
        )
        previewPagerAdapter = adapter
        previewPager.adapter = adapter
        // ── Pass-26 (#2.3): no arrows when there is nothing to navigate to ──
        // A single-item preview is a clean full-bleed view — the filmstrip
        // already followed this rule; the arrows now do too, and both come
        // back the moment a multi-item selection is previewed.
        val multi = items.size > 1
        previewPrevBtn.visibility = if (multi) View.VISIBLE else View.GONE
        previewNextBtn.visibility = if (multi) View.VISIBLE else View.GONE
        // ── Pass-24 (#4A: open the MOST RECENTLY selected item) ────────────────
        // Preview used to always land on position 0 — the FIRST item of the
        // selection. It now resolves the selection hint (the last item the
        // user selected, tracked at every selection mutation) inside this
        // preview's deterministic order; if the hinted item is no longer
        // part of the preview (deselected in the meantime), the newest
        // STILL-selected individual item is the honest fallback — never an
        // unselected item, never a stale one.
        // ── Pass-29 Part 2 (#2): a queued-files preview lands on the row ──────
        // whose eye icon was tapped — the caller's index, verbatim.
        var startIdx =
            if (overrideItems != null) (overrideIndex ?: 0).coerceIn(0, items.size - 1)
            else items.indexOfFirst { it.id == previewOpenHintId }
        if (overrideItems == null && startIdx < 0) {
            val fallback = selected.values.lastOrNull()
            if (fallback != null) startIdx = items.indexOfFirst { it.id == fallback.id }
        }
        if (startIdx < 0) startIdx = 0
        previewPager.scrollToPosition(startIdx)
        // ── Pass-21 (the filmstrip lifecycle) ─────────────────────────────
        // Rebuilt per open so it always mirrors THIS preview's item list; a
        // fresh adapter also drops every stale tile bitmap. Tap = instant
        // jump (the pager's snap lands exactly on the page), with the chrome
        // and the highlight following in the same frame.
        previewFilmAdapter = if (multi) PreviewFilmstripAdapter(
            scope = pickerScope,
            items = items,
            cellDp = 44,
            onTap = { pos ->
                // ── Pass-35 (#6): the filmstrip tap and the nav arrows share ──
                // the ONE hardened jump (stopScroll + snap-detach + exact
                // layout + same-frame chrome/video-stop repaint) — see
                // jumpToPreviewPage above for the full race history this
                // discipline exists to kill.
                jumpToPreviewPage(pos)
            },
        ) else null
        previewFilmstrip.adapter = previewFilmAdapter
        previewFilmstrip.visibility = if (multi) View.VISIBLE else View.GONE
        if (multi) previewFilmAdapter?.setCurrent(startIdx)
        updatePreviewChrome(startIdx)
        // ── Pass-29 Part 2 (#3B): every fresh open starts with the chrome ──
        // up (a previous preview may have been closed with it dismissed).
        // Non-animated and ahead of the overlay's own fade-in, so the reset
        // itself is invisible — it only guarantees the bar, arrows,
        // filmstrip and video controls are all in their resting state when
        // the overlay fades in.
        setPreviewChromeVisible(true, false)
        previewOverlay.visibility = View.VISIBLE
        previewOverlay.alpha = 0f
        previewOverlay.animate().alpha(1f).setDuration(140)
            .setInterpolator(Motion.DECELERATE).start()
    }

    fun closePreview() {
        try { activePreviewVideo?.stopPlayback() } catch (e: Exception) { /* already gone */ }
        activePreviewVideo = null
        // ── Pass-31 (#1): a CACHED neighbor video page (paused or not) never ──
        // outlives the preview either — pause every live video page before the
        // adapter reference goes, so closing the preview can leave ZERO
        // playback state behind (the recycled holders' own stopPlayback then
        // releases the players for good, as it always did).
        try { previewPagerAdapter?.pauseVideoPagesExcept(-1) } catch (e: Exception) { /* adapter already gone */ }
        // Detach the adapter too: every cell recycles, which cancels its
        // in-flight decode/progress coroutine — no orphaned work behind a
        // GONE overlay, nothing queued for a next open to wait behind.
        previewPager.adapter = null
        // Pass-26 (#2.1): the reset control belongs to an open preview —
        // force it to its hidden end-state (no animation behind a closing
        // overlay) so the next open starts clean whatever the last page's
        // zoom was.
        previewPagerAdapter = null
        zoomResetWantsVisible = false
        previewZoomResetBtn.animate().cancel()
        previewZoomResetBtn.visibility = View.GONE
        // Pass-21: same discipline for the filmstrip — recycled tiles cancel
        // their decodes, and the strip hides with the overlay.
        previewFilmstrip.adapter = null
        previewFilmAdapter = null
        previewFilmstrip.visibility = View.GONE
        previewOverlay.animate().alpha(0f).setDuration(120)
            .withEndAction {
                if (previewOverlay.alpha == 0f) previewOverlay.visibility = View.GONE
                // ── Pass-29 Part 2 (#2): a queued-files preview is the whole ──
                // reason this sheet was opened — closing it dismisses the
                // sheet and returns to the caller's screen (the picker grids
                // were never the destination on this path).
                if (queuePreviewMode) sheet.dismiss()
            }
            .start()
    }
    previewCloseBtn.setOnClickListener { closePreview() }
    // Back key: the sheet's onBack hook (Pass-35 #4) owns the whole priority
    // chain now — preview, album-detail, selection, dismissal — instead of
    // the old preview-only listener that let every other press fall through
    // to the dialog's default dismissal with no context.

    // ════════════════════════════════════════════════════════════════════
    // ── Pass-20: the floating Preview + Add dock, bottom-center ────────────
    // Both actions live in ONE elevated pill pinned to the sheet's bottom
    // edge, floating OVER the grid (fixed position while content scrolls
    // under it — the grid's own bottom clearance keeps every cell reachable).
    // Preview carries the live selection count in a small accent chip; Add
    // keeps its existing behavior and count. Both appear/disappear together,
    // driven ONLY by refreshHeaderSelection (the single selection writer) —
    // so visibility and counts can never drift from the real selection.
    // ════════════════════════════════════════════════════════════════════
    val dockPill = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(8), dp(8), dp(8))
        elevation = dp(12).toFloat()
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(32).toFloat()
            setColor(0xF20E1115.toInt())
            setStroke(dp(1), 0x2EFFFFFF.toInt())
        }
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, dp(32).toFloat())
            }
        }
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            it.bottomMargin = dp(18)
        }
    }
    val previewBtn = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true; isFocusable = true
        clipToOutline = true
        setPadding(dp(14), dp(10), dp(14), dp(10))
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(24).toFloat()
            setColor(0x1FFFFFFF.toInt())
            setStroke(dp(1), 0x33FFFFFF.toInt())
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.marginEnd = dp(10)
        }
    }
    previewBtn.addView(ImageView(ctx).apply {
        setImageDrawable(Widgets.icon(ctx, R.drawable.ic_eye, android.graphics.Color.WHITE))
        layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).also { it.marginEnd = dp(7) }
    })
    previewBtn.addView(TextView(ctx).apply {
        text = "Preview"; textSize = 13.5f; setTextColor(android.graphics.Color.WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    })
    val previewCountChip = TextView(ctx).apply {
        textSize = 11.5f; setTextColor(col(R.color.accent_light))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minLines = 1; minWidth = dp(22)
        setPadding(dp(7), dp(2), dp(7), dp(2))
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dp(11).toFloat()
            setColor((col(R.color.accent) and 0x00FFFFFF) or 0x33000000)
        }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.marginStart = dp(8)
        }
    }
    previewBtn.addView(previewCountChip)
    val addBtn = TextView(ctx).apply {
        textSize = 14f; gravity = Gravity.CENTER
        setTextColor(col(R.color.white))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = Widgets.rippleRect(ctx, col(R.color.accent), radiusDp = 24)
        clipToOutline = true
        setPadding(dp(22), dp(11), dp(22), dp(11))
        isClickable = true; isFocusable = true
        setOnClickListener {
            if (selected.isEmpty() && selectedAlbums.isEmpty()) return@setOnClickListener
            onConfirm(selected.values.map { it.uri }, selectedAlbums.values.toList())
            dialog.dismiss()
        }
    }
    dockPill.addView(previewBtn)
    dockPill.addView(addBtn)
    previewBtn.setOnClickListener { openPreview() }

    // ── Pass-20: dock show/hide — instant-feeling, glitch-proof ───────────
    // A 110ms scale+fade reads as "appeared immediately" while still feeling
    // polished; the hide is even quicker. The `dockWantsVisible` flag is the
    // authority for the animation's end action, so a rapid deselect→select
    // (or the reverse) can never strand the dock mid-animation in the wrong
    // state — the flag is checked at the end of the hide animation, and the
    // show path unconditionally resets the animated properties.
    // ── Pass-40 (#2): the dock's BERTH — above the search bar, never on it.
    // The search bar's 18dp baseline is now FIXED: it does not jump, shift
    // or move when the Preview/Add dock appears or disappears (the old
    // Pass-34 behavior lifted the bar to 76dp whenever a selection started —
    // exactly the "search bar moves when the pill appears" this pass
    // removes). Instead the DOCK takes the higher berth on the Albums tab:
    // its bottom margin is measured from the search bar's real laid-out
    // height (searchTop + a consistent 6dp gap, at any font scale), settling
    // to the designed 76dp until the bar has measured. On every other tab
    // the dock keeps its classic 18dp bottom edge. The bar's layout
    // listener re-places the dock if its height ever changes, so the gap
    // stays visually constant across screen sizes and text scales.
    fun placeDock() {
        val bar = albumSearchSlot
        val lp = dockPill.layoutParams as? FrameLayout.LayoutParams ?: return
        val berth = if (bar != null && bar.visibility == View.VISIBLE)
            if (bar.height > 0) dp(18) + bar.height + dp(6) else dp(76)
        else dp(18)
        if (lp.bottomMargin != berth) {
            lp.bottomMargin = berth
            dockPill.layoutParams = lp
        }
    }

    var dockWantsVisible = false
    fun updateDock(n: Int) {
        // ── GOAT-PASS 14 Phase 2 (§5): the dock's affirmative word follows the ──
        // LIVE Auto Send setting on every selection update (the same shared
        // quickShareActionVerb the Files/Music/Text/Apps surfaces use — one
        // definition, five callers): "Add n" stages the batch in the Queue,
        // "Send n" dispatches it immediately. The count format, padding,
        // background and the dock's geometry are untouched.
        val verb = quickShareActionVerb(ctx)
        addBtn.text = if (n > 0) "$verb $n" else verb
        previewCountChip.text = n.toString()
        val want = n > 0
        placeDock()
        if (want == dockWantsVisible) return
        dockWantsVisible = want
        dockPill.animate().cancel()
        if (want) {
            dockPill.visibility = View.VISIBLE
            dockPill.alpha = 0f
            dockPill.scaleX = 0.92f; dockPill.scaleY = 0.92f
            dockPill.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(110)
                .setInterpolator(Motion.DECELERATE).start()
        } else {
            dockPill.animate().alpha(0f).scaleX(0.95f).scaleY(0.95f).setDuration(90)
                .withEndAction { if (!dockWantsVisible) dockPill.visibility = View.GONE }
                .start()
        }
    }

    // ---- Pass-20: content host — the grid fills the screen, the dock
    // floats over it, both inside one FrameLayout so the dock NEVER moves
    // with the scrolling content.
    val contentHost = FrameLayout(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
    }
    contentFrame.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    contentHost.addView(contentFrame)

    // ── Pass-34 Part 2 (#7): the Albums tab's floating SEARCH BAR — the same
    // input-field language as the Files/Music pickers' pinned searches,
    // floating at the bottom of the Albums tab (inside the content host, so
    // it never scrolls with the grid), visible only on the Albums screen.
    // ── Pass-40 (#2): its position is PERMANENT now — the fixed 18dp
    // baseline below the dock's berth (see placeDock above); the bar never
    // moves for the dock, the dock moves for the bar. The full-screen
    // preview overlay still covers it — searching and previewing can never
    // interfere with each other.
    // ── GOAT-PASS 7 Part 2 (§8): the bar is TRANSLUCENT now — a ~78%-opacity
    // bg_deep fill in the field's own shape language (same radius, hairline
    // stroke, focused accent ring — see bg_input_field_translucent), so the
    // album grid scrolling beneath it stays visible through the bar while
    // the search text, hint and cursor keep full contrast on the dark fill.
    // One background swap: the field's position, size, focus behavior and
    // the debounced re-filter are untouched, and there is no extra layer,
    // scrim or invalidation — zero rendering overhead.
    val albumSearchBar = Widgets.inputField(ctx, "", android.text.InputType.TYPE_CLASS_TEXT).apply {
        hint = "Search albums"
        background = ContextCompat.getDrawable(ctx, R.drawable.bg_input_field_translucent)
        visibility = View.GONE
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).also {
            it.leftMargin = dp(16); it.rightMargin = dp(16); it.bottomMargin = dp(18)
        }
    }
    albumSearchSlot = albumSearchBar
    // Pass-40 (#2): a size change on the bar (first layout, font-scale
    // change) re-derives the dock's berth so the gap between the two pills
    // stays small and consistent; the bar itself stays exactly where it is.
    albumSearchBar.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> placeDock() }
    // The debounced re-filter — same 120ms rhythm as the Files/Music pickers'
    // searches. The slot indirection is only ordering: render() is declared
    // further down, and locals are strictly before-use.
    var applyAlbumSearchFn: () -> Unit = {}
    albumSearchBar.addTextChangedListener { e ->
        val serial = ++albumSearchSerial
        albumQuery = e?.toString() ?: ""
        pickerScope.launch {
            delay(120)
            if (serial == albumSearchSerial) applyAlbumSearchFn()
        }
    }
    contentHost.addView(albumSearchBar)
    // Pass-40 (#2): the dock is added AFTER the search bar — with the two
    // pills now stacked (dock above, bar below) the dock's scale-in entrance
    // can transiently sweep across the bar's top edge, and it must draw on
    // top when it does, never underneath.
    contentHost.addView(dockPill)
    root.addView(contentHost)

    // ---- Pass-20: the preview overlay is a WINDOW-level layer now — the
    // sheet's addWindowOverlay hosts it above the entire sheet (Pass-35
    // #12), so it still covers everything exactly as the old full-screen
    // stage did; it is attached right after the sheet's show() call below.

    // ---- Rendering (defined before the adapters below, which reference
    // render()/refreshHeaderSelection() as plain already-declared local
    // functions -- Kotlin doesn't allow a lambda to forward-reference a
    // local fun/val declared later in the same scope, so this ordering is
    // load-bearing, not stylistic) ------------------------------------------
    fun typeFiltered(items: List<MediaLibraryItem>) = when (typeFilter) {
        MediaTypeFilter.ALL -> items
        MediaTypeFilter.IMAGES -> items.filter { !it.isVideo }
        MediaTypeFilter.VIDEOS -> items.filter { it.isVideo }
    }

    // ── SELECTION FINAL PASS 1 (§5A — the filtered-library cache): ──
    // refreshHeaderSelection runs on EVERY selection change, and its scope
    // counts re-filtered the WHOLE library per call — a fresh list
    // allocation plus an O(library) membership sweep per crossed Hold &
    // Slide cell. The filtered view only changes when the library itself or
    // the type filter changes (render-time events), so it is computed once
    // per genuine change and served from this cache afterwards.
    var filteredCache: List<MediaLibraryItem> = emptyList()
    var filteredCacheFilter: MediaTypeFilter? = null
    var filteredCacheSource: List<MediaLibraryItem> = emptyList()
    fun cachedTypeFiltered(): List<MediaLibraryItem> {
        if (filteredCacheFilter != typeFilter || filteredCacheSource !== allItems) {
            filteredCache = typeFiltered(allItems)
            filteredCacheFilter = typeFilter
            filteredCacheSource = allItems
        }
        return filteredCache
    }

    fun refreshHeaderSelection() {
        // ── Pass-20: the header count pill is retired along with the header ──
        // Add — the floating bottom dock's Preview chip + "Add N" label now
        // carry the live count (the ONLY counters on screen, one writer: this
        // function runs from every selection path — per-cell toggles, drag
        // select, Select All/Clear, and every render — so the dock's
        // visibility and counts stay perfectly synchronized with the real
        // selection across All Media, Album Detail, and Albums alike).
        val n = selected.size + selectedAlbums.size
        updateDock(n)

        // GOAT v2 (item 3 — Select All): "all" means a different set on
        // each of the three screens (every album; every item in the whole
        // filtered library; every item in just the open album), so this is
        // scoped to whichever one is actually showing right now, same as
        // the grids themselves already are in render() below.
        // ── SELECTION FINAL PASS 1 (§5A): the scope set comes from the ──
        // cached filtered library — no per-call re-filter of allItems.
        val filtered = cachedTypeFiltered()
        val (selectableCount, selectedCount) = when (screen) {
            Screen.ALBUMS -> {
                // Pass-19: the adapter's own snapshot IS the rendered album
                // set — no per-tap O(library) regroup of groupIntoAlbums.
                val albums = albumsAdapter.snapshot()
                albums.size to albums.count { selectedAlbums.containsKey(it.bucketId) }
            }
            Screen.ALL_MEDIA -> filtered.size to filtered.count { selected.containsKey(it.id) }
            Screen.ALBUM_DETAIL -> {
                val inAlbum = filtered.filter { it.bucketId == openAlbum?.bucketId }
                inAlbum.size to inAlbum.count { selected.containsKey(it.id) }
            }
        }
        // ── Pass-21 (the Clear/Select-All visibility contract) ───────────
        // Select All shows while the CURRENT screen has something left to
        // add (hidden once fully selected); Clear shows while ANY selection
        // exists anywhere — the reported "Clear only appears in the Select
        // All state" hole is the old morphing label, which this replaces.
        selectAllBtn.visibility = if (loaded && selectableCount > 0 && selectedCount < selectableCount) View.VISIBLE else View.GONE
        clearBtn.visibility = if (loaded && (selected.isNotEmpty() || selectedAlbums.isNotEmpty())) View.VISIBLE else View.GONE
    }

    // ══ Pass-26 (#2.5): the preview select control's action ════════════════
    // Wired here (after refreshHeaderSelection/typeFiltered exist — Kotlin
    // local declaration order; the BUTTON itself and its painter live with
    // the preview chrome above). One tap toggles the CURRENTLY VIEWED item
    // in the REAL selection — the same maps the grids, the dock count and
    // the Add action all read:
    //  • deselecting an individually-selected item removes exactly it;
    //  • deselecting an item that is selected only THROUGH ITS ALBUM
    //    removes the album and individually re-selects every OTHER member —
    //    the net effect is precisely "this one item left the selection,
    //    everything else unchanged" (the honest per-item semantics, without
    //    inventing an exclusion model the picker doesn't have);
    //  • selecting adds the item individually.
    // The grids behind the preview are patched with the SAME selection-only
    // payload discipline Clear/Select-All use (no full rebind, no flicker),
    // the dock's count chip follows via refreshHeaderSelection, and the
    // preview itself stays open — the control simply flips its own state.
    previewSelectBtn.setOnClickListener {
        // ── Pass-30 (#3/#4): the page the CHROME says is showing — the same ──
        // race-free read the filmstrip's tap gate uses. The old
        // previewCurrentPosition() layout read could report the OLD page
        // while a programmatic scroll was still settling, and a select tap
        // landing in that window toggled the WRONG item.
        val pos = previewUiPosition.takeIf { it >= 0 } ?: previewCurrentPosition()
        val item = previewItems.getOrNull(pos) ?: return@setOnClickListener
        // ── Pass-30 (#4): queue-preview mode — the SAME control, QUEUE ─────
        // semantics: the currently viewed item flips its QUEUE MEMBERSHIP
        // through the caller's single handler (remove from the queue /
        // re-add to the queue), with the caller's live reader as the
        // authority for the very next paint. The picker's selection maps
        // are never touched on this path (they are not in play in queue
        // mode), and the preview stays open so the user can keep
        // navigating the queue and undo the flip with another tap.
        if (queuePreviewMode && onQueueItemFlipRef != null && queueItemIsQueuedRef != null) {
            val queued = queueItemIsQueuedRef.invoke(item)
            onQueueItemFlipRef.invoke(item, !queued)
            paintPreviewSelectState(item)
            previewSelectBtn.animate().cancel()
            previewSelectBtn.scaleX = 0.85f; previewSelectBtn.scaleY = 0.85f
            previewSelectBtn.animate().scaleX(1f).scaleY(1f)
                .setDuration(Motion.DURATION_MEDIUM)
                .setInterpolator(Motion.OVERSHOOT_SOFT).start()
            return@setOnClickListener
        }
        if (previewItemSelectionOn(item)) {
            selected.remove(item.id)
            val album = selectedAlbums[item.bucketId]
            if (album != null) {
                selectedAlbums.remove(album.bucketId)
                for (other in allItems) {
                    if (other.bucketId == album.bucketId && other.id != item.id) selected[other.id] = other
                }
            }
        } else {
            selected[item.id] = item
            notePreviewHint(item.id)
        }
        // Selection-only payload rebinds for whichever grid is showing
        // behind the preview (bounded by the adapter's own item count —
        // exactly what the rendered cells hold, never more).
        when (screen) {
            Screen.ALBUMS -> albumsAdapter.notifySelectionChangedRange()
            Screen.ALL_MEDIA -> mediaAdapter.notifySelectionChangedRange(0, mediaAdapter.itemCount)
            Screen.ALBUM_DETAIL -> mediaAdapter.notifySelectionChangedRange(0, mediaAdapter.itemCount)
        }
        refreshHeaderSelection()
        paintPreviewSelectState(item)
        // A small pop marks the flip — the same tactile bounce the picker's
        // own selection chrome uses, so the two surfaces feel like one.
        previewSelectBtn.animate().cancel()
        previewSelectBtn.scaleX = 0.85f; previewSelectBtn.scaleY = 0.85f
        previewSelectBtn.animate().scaleX(1f).scaleY(1f)
            .setDuration(Motion.DURATION_MEDIUM)
            .setInterpolator(Motion.OVERSHOOT_SOFT).start()
    }

    fun styleToggle(view: TextView, active: Boolean) {
        // v1.0 (joined segmented control): the active segment is a solid
        // accent pill, the inactive one is TRANSPARENT — the container's own
        // border is the inactive state (identical to DeviceAdapter's mode
        // control). REDUNDANT REASSIGNMENT guard kept from the ripple pass:
        // render() runs on every selection change and re-swapping the
        // background mid-press is what glitched the ripple on exactly these
        // pill selection items; the background now only changes when the
        // active state genuinely flipped. clipToOutline keeps the press
        // feedback inside the pill's rounded outline.
        val wantActive = view.tag as? Boolean ?: !active
        if (wantActive == active) return
        view.tag = active
        view.setTextColor(if (active) col(R.color.white) else col(R.color.text_secondary))
        // Post-v1.0 fix (invisible INACTIVE toggle on Android 14+): the
        // inactive state is bg_segment_idle — a transparent drawable with a
        // real outline — never null. A null background resolves an EMPTY
        // outline, and clipToOutline (always true on these toggles) with an
        // empty outline clips the toggle's TEXT to nothing on API 33+.
        view.background = ContextCompat.getDrawable(ctx,
            if (active) R.drawable.bg_btn_primary else R.drawable.bg_segment_idle)
    }
    // v1.0: the filter chips' own two-state styling — active = soft accent
    // fill + accent text (a quiet "on", clearly subordinate to the segmented
    // control's solid pill), inactive = muted text on nothing.
    fun styleFilterChip(view: TextView, active: Boolean) {
        val wantActive = view.tag as? Boolean ?: !active
        if (wantActive == active) return
        view.tag = active
        view.setTextColor(if (active) col(R.color.accent_light) else col(R.color.text_muted))
        // Post-v1.0 fix: idle state uses the outline-providing transparent
        // bg_segment_idle instead of null (see styleToggle's note) — the
        // chips keep clipToOutline from their factory, which erases text
        // under a null background on API 33+.
        view.background = if (active) {
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = ctx.dpf(9f)
                setColor((col(R.color.accent) and 0x00FFFFFF) or 0x26000000)   // ~15% accent tint
            }
        } else ContextCompat.getDrawable(ctx, R.drawable.bg_segment_idle)
    }

    fun render() {
        val filtered = typeFiltered(allItems)
        styleToggle(tabAllMedia, screen != Screen.ALBUMS)
        styleToggle(tabAlbums, screen == Screen.ALBUMS)
        styleFilterChip(chipAll, typeFilter == MediaTypeFilter.ALL)
        styleFilterChip(chipPhotos, typeFilter == MediaTypeFilter.IMAGES)
        styleFilterChip(chipVideos, typeFilter == MediaTypeFilter.VIDEOS)
        tabsRow.visibility = if (screen == Screen.ALBUM_DETAIL) View.GONE else View.VISIBLE
        // ── Pass-34 Part 2 (#7): the floating album search exists only on the
        // Albums tab — hidden on All Media and Album Detail, always over the
        // grid, never over the preview (the stage-level preview overlay
        // covers the whole picker UI including this bar).
        albumSearchBar.visibility = if (screen == Screen.ALBUMS) View.VISIBLE else View.GONE
        // Pass-40 (#2): the tab flip re-derives the dock's berth — above the
        // bar on Albums, at the classic bottom edge everywhere else. The bar
        // itself never moves; only the dock adapts.
        placeDock()
        // ── Pass-33 (#5): the All/Photos/Videos filter bar now shows on the ──────
        // Albums screen too, not just All Media / Album Detail. The Albums
        // grid was ALWAYS regrouped from the filtered item list
        // (groupIntoAlbums(typeFiltered(allItems)) below), so filtering here
        // is real: Photos keeps exactly the photo-bearing albums (regrouped
        // from photos only, counts and sizes recomputed by the grouping
        // itself), Videos the video-bearing ones, and an album with no
        // matching members leaves the list entirely. One shared filter state
        // across all three screens — switching tabs keeps the choice, the
        // chips keep the same styling, and selection semantics are untouched
        // (a selected album stages exactly the members its filtered card
        // displayed — see enqueueAlbums' manifest).
        filterRow.visibility = View.VISIBLE
        // ── Pass-36 (#2): the X/close icon is GONE. The sheet dismisses
        // through its drag gesture (handle + empty chrome), Android Back and
        // the backdrop tap — a permanent close glyph in the header was one
        // affordance too many. The control lives on ONLY as the album-detail
        // BACK chevron (contextual navigation, not dismissal).
        backOrClose.visibility = if (screen == Screen.ALBUM_DETAIL) View.VISIBLE else View.GONE
        backOrClose.setImageDrawable(Widgets.icon(ctx,
            if (screen == Screen.ALBUM_DETAIL) R.drawable.ic_chevron_right else R.drawable.ic_close,
            col(R.color.text_primary)))
        backOrClose.rotation = if (screen == Screen.ALBUM_DETAIL) 180f else 0f
        loadingSpinner.visibility = if (!loaded) View.VISIBLE else View.GONE

        when (screen) {
            Screen.ALL_MEDIA -> {
                titleView.text = "All Media"
                subtitleView.text = "${filtered.size} item${if (filtered.size != 1) "s" else ""} on this device"
                mediaGrid.visibility = View.VISIBLE; albumsGrid.visibility = View.GONE
                mediaAdapter.submit(filtered)
                if (loaded && filtered.isEmpty()) {
                    if (allItems.isEmpty()) showEmpty("No photos or videos found", "Media stored on this device will appear here")
                    else showEmpty("No ${if (typeFilter == MediaTypeFilter.IMAGES) "photos" else "videos"} found", "Try switching the filter above")
                } else hideEmpty()
            }
            Screen.ALBUMS -> {
                titleView.text = "Albums"
                val albums = MediaLibrary.groupIntoAlbums(filtered)
                // ── Pass-34 Part 2 (#7): remember the unfiltered grouping, then
                // apply the live album search on top — each keystroke re-filters
                // THIS list (never regrouping the library), and the filter
                // never touches an album's contents or its selection identity.
                lastAlbums = albums
                val shown = if (albumQuery.isBlank()) albums
                else albums.filter { it.name.lowercase().contains(albumQuery.trim().lowercase()) }
                subtitleView.text = "${shown.size} album${if (shown.size != 1) "s" else ""}"
                mediaGrid.visibility = View.GONE; albumsGrid.visibility = View.VISIBLE
                albumsAdapter.submit(shown)
                if (loaded && shown.isEmpty()) {
                    // Pass-33 (#5): the empty state says WHICH filter came up
                    // empty, same honesty as All Media's own empty state.
                    if (albumQuery.isNotBlank()) showEmpty("No albums match \"${albumQuery.trim()}\"", "Try a different search")
                    else if (typeFilter == MediaTypeFilter.ALL) showEmpty("No albums found", "Photos and videos group into albums automatically")
                    else showEmpty("No ${if (typeFilter == MediaTypeFilter.IMAGES) "photo" else "video"} albums found", "Try switching the filter above")
                } else hideEmpty()
            }
            Screen.ALBUM_DETAIL -> {
                val album = openAlbum
                titleView.text = album?.name ?: "Album"
                val inAlbum = filtered.filter { it.bucketId == album?.bucketId }
                subtitleView.text = "${inAlbum.size} item${if (inAlbum.size != 1) "s" else ""}"
                mediaGrid.visibility = View.VISIBLE; albumsGrid.visibility = View.GONE
                mediaAdapter.submit(inAlbum)
                if (loaded && inAlbum.isEmpty()) {
                    val kind = if (typeFilter == MediaTypeFilter.IMAGES) "photos" else if (typeFilter == MediaTypeFilter.VIDEOS) "videos" else "items"
                    showEmpty("No $kind in this album", "Try switching the filter above")
                } else hideEmpty()
            }
        }
        // Screen/filter changes shift what "all" even means for Select All
        // (see its own comment inside refreshHeaderSelection) just as much
        // as an individual toggle does, so this needs to run on every
        // render(), not only from the adapters' own onToggle callbacks.
        refreshHeaderSelection()
    }

    // ── Pass-34 Part 2 (#7): the debounced album search's landing — a plain
    // re-render, which regroups from the (already in-memory) filtered item
    // list and applies the live query on top. Select All and the selection
    // state read the adapter's OWN snapshot, so they automatically respect
    // the search exactly like every other filter.
    applyAlbumSearchFn = { if (screen == Screen.ALBUMS) render() }

    // GOAT v2 (item 1 — album cards): previously reused gridCellDp (sized
    // for the 4-COLUMN media grid) as the album cover's fixed HEIGHT while
    // its width was MATCH_PARENT inside a 2-column card -- roughly twice
    // as wide as that height, so covers rendered as short, squat
    // rectangles rather than the square tiles a "deliberately designed
    // grid" calls for. Computed from this grid's own actual column math
    // instead (2 columns, this grid's own padding/spacing -- see albumsGrid
    // above), then used as the cover's height too, so width and height
    // genuinely match. Phase 5 (§5.5): the span count matches whatever
    // albumsGrid itself now uses (3 on ≥600dp screens), so the cover math
    // and the layout can't disagree on tablet.
    val albumSpanCount = ALBUM_SPAN_COUNT
    val albumGridPaddingDp = 13
    val albumSpacingDp = 10
    val albumCardOuterDp = (screenWidthDp - albumGridPaddingDp * 2 - albumSpacingDp) / albumSpanCount
    // Pass-6 (R-D): the album card is full-bleed now (no inner horizontal
    // padding — the text block overlays the cover), so the card/cover square
    // is the WHOLE cell, not the old cell minus 20dp of card padding. The
    // card height is set to this in AlbumGridAdapter.onCreateViewHolder.
    val albumCoverDp = albumCardOuterDp.coerceAtLeast(80)

    // ── SELECTION FINAL PASS 1 (§5B — the coalesced visibility recompute): ──
    // one pending post at a time; a slide that crosses ten cells in a single
    // frame pays exactly ONE scope recount for that frame. The recount
    // itself is the same refreshHeaderSelection logic that every tap uses,
    // so the Select All/Clear pills can never drift from the truth.
    // (Declared AFTER refreshHeaderSelection and BEFORE the drag apply
    // functions — Kotlin locals are strictly before-use.)
    var headerVisibilityPostPending = false
    val headerVisibilityRecompute = Runnable {
        headerVisibilityPostPending = false
        if (selected.size + selectedAlbums.size > 0 || selectAllBtn.visibility == View.VISIBLE || clearBtn.visibility == View.VISIBLE) {
            refreshHeaderSelection()
        }
    }
    fun scheduleHeaderVisibilityRecompute() {
        if (headerVisibilityPostPending) return
        headerVisibilityPostPending = true
        contentHost.post(headerVisibilityRecompute)
    }

    // §5 (Reliability pass — long-press toggle semantics): applies one cell's
    // selection delta — add=true selects (idempotent), add=false deselects
    // (idempotent). Targets just that one cell's own bind rather than the
    // whole grid, so a fast drag across many cells doesn't force a full
    // rebind per cell crossed. Both the initiating long-press and every
    // crossed cell of the drag that follows route through here with the
    // SAME mode, so one gesture can never half-select half-deselect.
    fun applyMediaSelection(position: Int, add: Boolean) {
        val item = mediaAdapter.itemAt(position) ?: return
        val has = selected.containsKey(item.id)
        if (add == has) return
        if (add) { selected[item.id] = item; notePreviewHint(item.id) } else selected.remove(item.id)
        // Pass-19: selection-only payload rebind — the crossed cell's photo
        // never blanks mid-drag.
        mediaAdapter.notifySelectionChanged(position)
        // ── SELECTION FINAL PASS 1 (§5B): the dock count is O(1) and stays ──
        // immediate; the Select All/Clear visibility recompute (the
        // O(scope) part of refreshHeaderSelection) is COALESCED — at most one
        // pass per frame no matter how many cells a fast slide crosses,
        // instead of one per crossed cell.
        updateDock(selected.size + selectedAlbums.size)
        scheduleHeaderVisibilityRecompute()
    }
    fun applyAlbumSelection(position: Int, add: Boolean) {
        val album = albumsAdapter.itemAt(position) ?: return
        val has = selectedAlbums.containsKey(album.bucketId)
        if (add == has) return
        if (add) { selectedAlbums[album.bucketId] = album; notePreviewHint(firstMemberIdOfAlbum(album.bucketId)) } else selectedAlbums.remove(album.bucketId)
        // Pass-19: selection-only payload rebind — the crossed card's cover
        // never blanks mid-drag.
        albumsAdapter.notifySelectionChangedFor(album)
        // ── SELECTION FINAL PASS 1 (§5B): same split as applyMediaSelection. ──
        updateDock(selected.size + selectedAlbums.size)
        scheduleHeaderVisibilityRecompute()
    }

    // ---- Adapters (built after render()/refreshHeaderSelection() exist) ---
    // ── GOAT-PASS 7 (§5A — GRID RANGE SELECTION): the anchor model the Files ──
    // and Music lists already use, adapted to the grids' positional order —
    // which IS the grid's visual order (row by row, left to right) for any
    // column count, because GridLayoutManager lays cells out in adapter order.
    // A long-press with NO valid anchor toggles the pressed cell (the grids'
    // historic long-press feel — a selected cell starts a deselect pass) and
    // makes it the anchor; a long-press WITH a valid anchor selects the whole
    // inclusive positional span between them, additively (unrelated picks are
    // preserved), and moves the anchor to the pressed cell. The anchor ids
    // are stable item identities, re-resolved against the CURRENT list every
    // time, so a filter/type/screen change simply invalidates a stale anchor
    // (the next press becomes a fresh anchor) — never a wrong range.
    var mediaAnchorId: Long? = null
    var albumsAnchorBucketId: Long? = null
    /** ── GOAT-PASS 8 (§4): the live selection-style read for this sheet's ──
     *  grids — Range (the default) keeps the anchor model below; Hold &
     *  Slide makes every long-press the initiating TOGGLE (the armed drag
     *  owns the rest of the gesture), never a range. */
    val slideSelect = { com.lumidrop.x.settings.SettingsManager.isSlideSelection(ctx) }
    fun mediaGridLongPress(pos: Int) {
        val target = mediaAdapter.itemAt(pos) ?: return
        // ── GOAT-PASS 8 (§3 — the ghost-anchor guard): an anchor that is no ──
        //  longer SELECTED is not an anchor — a range can only ever extend
        //  from an item the user can still see highlighted. Deselection
        //  paths that don't clear the anchor themselves (drag REMOVE passes,
        //  covering exclusions) can therefore never conjure a range from a
        //  previous session's anchor.
        val anchorValid = mediaAnchorId != null && mediaAnchorId?.let { aid ->
            selected.containsKey(aid)
        } == true
        val anchorPos = if (anchorValid) mediaAnchorId?.let { aid ->
            (0 until mediaAdapter.itemCount).indexOfFirst { mediaAdapter.itemAt(it)?.id == aid }
        } ?: -1 else -1
        // ── GOAT-PASS 8 (§4 — Hold & Slide mode): the long-press is the ──
        //  initiating TOGGLE only — the armed drag owns everything the finger
        //  crosses, and a range can never begin from under the finger.
        if (anchorPos < 0 || slideSelect()) {
            // Historic feel preserved: the initiating press TOGGLES.
            if (selected.containsKey(target.id)) selected.remove(target.id)
            else { selected[target.id] = target; notePreviewHint(target.id) }
            mediaAnchorId = target.id
            mediaAdapter.notifySelectionChanged(pos)
            refreshHeaderSelection()
            return
        }
        val from = minOf(anchorPos, pos)
        val to = maxOf(anchorPos, pos)
        for (i in from..to) {
            mediaAdapter.itemAt(i)?.let { it2 ->
                if (!selected.containsKey(it2.id)) { selected[it2.id] = it2; notePreviewHint(it2.id) }
            }
        }
        mediaAnchorId = target.id
        mediaAdapter.notifySelectionChangedRange(from, to - from + 1)
        refreshHeaderSelection()
    }
    fun albumsGridLongPress(pos: Int) {
        val target = albumsAdapter.itemAt(pos) ?: return
        // ── GOAT-PASS 8 (§3 — the ghost-anchor guard, albums grid): same rule ──
        //  as the media grid — only a still-SELECTED anchor may extend a range.
        val anchorValid = albumsAnchorBucketId != null && albumsAnchorBucketId?.let { bid ->
            selectedAlbums.containsKey(bid)
        } == true
        val anchorPos = if (anchorValid) albumsAnchorBucketId?.let { bid ->
            (0 until albumsAdapter.itemCount).indexOfFirst { albumsAdapter.itemAt(it)?.bucketId == bid }
        } ?: -1 else -1
        if (anchorPos < 0 || slideSelect()) {
            if (selectedAlbums.containsKey(target.bucketId)) selectedAlbums.remove(target.bucketId)
            else { selectedAlbums[target.bucketId] = target; notePreviewHint(firstMemberIdOfAlbum(target.bucketId)) }
            albumsAnchorBucketId = target.bucketId
            albumsAdapter.notifySelectionChangedFor(target)
            refreshHeaderSelection()
            return
        }
        val from = minOf(anchorPos, pos)
        val to = maxOf(anchorPos, pos)
        for (i in from..to) {
            albumsAdapter.itemAt(i)?.let { a ->
                if (!selectedAlbums.containsKey(a.bucketId)) {
                    selectedAlbums[a.bucketId] = a
                    notePreviewHint(firstMemberIdOfAlbum(a.bucketId))
                }
            }
        }
        albumsAnchorBucketId = target.bucketId
        albumsAdapter.notifySelectionChangedRange()
        refreshHeaderSelection()
    }
    mediaAdapter = MediaGridAdapter(
        scope = pickerScope,
        thumbSizeDp = gridCellDp,
        isSelected = { id -> selected.containsKey(id) },
        onToggle = { item ->
            // ── GOAT-PASS 9 (§3 — click-path anchoring): a normal CLICK that ──
            // selects an item now establishes it as the range anchor — the
            // exact contract the Files and Music sheets already implement
            // (toggleFile/toggleTrack). Previously only a long-press wrote
            // mediaAnchorId, so "click the 1st item, then hold the 8th"
            // selected ONLY the 8th (the hold's ghost-anchor guard found no
            // valid anchor and degraded to a lone toggle) — range selection
            // worked only when the initial item had ALSO been held. The
            // deselect branch clears a deselected anchor so it can never
            // become a ghost (same guard as the long-press path).
            if (selected.containsKey(item.id)) {
                selected.remove(item.id)
                if (mediaAnchorId == item.id) mediaAnchorId = null
            } else {
                selected[item.id] = item; notePreviewHint(item.id)
                mediaAnchorId = item.id
            }
            refreshHeaderSelection()
        },
        onLongPress = { pos -> mediaGridLongPress(pos) },
    )
    mediaGrid.adapter = mediaAdapter
    albumsAdapter = AlbumGridAdapter(
        scope = pickerScope,
        cellDp = albumCoverDp,
        isSelected = { bucketId -> selectedAlbums.containsKey(bucketId) },
        // GOAT v2 (item 2 — album select): tap keeps its original browse-
        // only meaning until a selection is already in progress, at which
        // point a plain tap on any OTHER album joins/leaves the selection
        // too instead of opening it -- the standard "once you're
        // selecting, taps select" convention, and the only way to add a
        // single non-adjacent album without long-pressing that one too.
        onTap = { album ->
            if (selectedAlbums.isNotEmpty()) {
                // ── GOAT-PASS 9 (§3 — click-path anchoring, albums grid): a ──
                // selection-in-progress tap that adds an album anchors it for
                // a later range hold, exactly like the media grid's onToggle
                // and the Files/Music sheets' toggles; deselecting the
                // anchored album clears the anchor.
                if (selectedAlbums.containsKey(album.bucketId)) {
                    selectedAlbums.remove(album.bucketId)
                    if (albumsAnchorBucketId == album.bucketId) albumsAnchorBucketId = null
                }
                else {
                    selectedAlbums[album.bucketId] = album
                    notePreviewHint(firstMemberIdOfAlbum(album.bucketId))
                    albumsAnchorBucketId = album.bucketId
                }
                // ── Pass-19 (no flicker): the old notifyDataSetChanged() here ──
                // re-bound EVERY visible album cell — each cover was cleared
                // and re-decoded — so every selection tap flashed the whole
                // Albums grid (the reported "flickers on selection/
                // deselection"). The payload rebind touches exactly the one
                // tapped card's selection chrome; nothing else on screen moves.
                albumsAdapter.notifySelectionChangedFor(album)
                refreshHeaderSelection()
            } else {
                openAlbum = album; screen = Screen.ALBUM_DETAIL; render()
            }
        },
        onLongPress = { pos -> albumsGridLongPress(pos) },
    )
    albumsGrid.adapter = albumsAdapter
    // §5 (Reliability pass — long-press toggle): same gesture, same helper,
    // on both grids -- see attachDragSelect's own doc comment for the full
    // mechanism. The long-press that starts a drag now TOGGLES the pressed
    // cell (a pressed-and-already-selected cell starts a REMOVE pass), and
    // the drag that follows applies that same captured mode to every cell
    // it crosses, so the gesture stays coherent from first cell to last.
    // ── GOAT-PASS 11 (§12): the gesture applies the initiating cell ──────────────────
    // (the range anchor/toggle above) now owns the initiating cell; the
    // helper only arms the drag and captures its ADD/REMOVE mode from the
    // cell's state at DOWN, exactly like the Files/Music lists. The endpoint
    // fix (§3C) rides along for free through the shared helper.
    attachDragSelect(mediaGrid,
        { pos -> mediaAdapter.itemAt(pos)?.let { selected.containsKey(it.id) } ?: false },
        { pos, add -> applyMediaSelection(pos, add) },
        // ── GOAT-PASS 8 (§4): the drag arms only in Hold & Slide mode; in ──
        // Range mode the grid's own long-press above owns the interaction.
        slideEnabled = slideSelect,
        // ── GOAT-PASS 9 (§4): the grids join the list surfaces' edge ──
        // auto-scroll — Hold & Slide through a large photo/album grid now
        // reaches the ends without lifting the finger, with the same
        // progressive acceleration and boundary-crossing continuation the
        // Files/Music/Transfers lists have.
        edgeAutoScroll = true,
        // ── SELECTION FINAL PASS 1 (§5B/§3): once per gesture — the exact ──
        // header settle (and any coalesced recompute flushed) after the
        // finger lifts.
        onGestureEnd = {
            contentHost.removeCallbacks(headerVisibilityRecompute)
            headerVisibilityPostPending = false
            refreshHeaderSelection()
        })
    attachDragSelect(albumsGrid,
        { pos -> albumsAdapter.itemAt(pos)?.let { selectedAlbums.containsKey(it.bucketId) } ?: false },
        { pos, add -> applyAlbumSelection(pos, add) },
        slideEnabled = slideSelect,
        edgeAutoScroll = true,
        // ── SELECTION FINAL PASS 1 (§5B/§3): same once-per-gesture settle. ──
        onGestureEnd = {
            contentHost.removeCallbacks(headerVisibilityRecompute)
            headerVisibilityPostPending = false
            refreshHeaderSelection()
        })

    // GOAT v2 (item 3 — Select All): scoped to whichever screen is showing
    // right now, matching refreshHeaderSelection's own per-screen "all"
    // definition exactly -- see its doc comment. Adds/removes only the
    // items currently in view rather than blindly clearing the whole
    // `selected`/`selectedAlbums` map, so a Clear from inside one album (or
    // one type filter) can never silently wipe a selection made somewhere
    // else in the picker that isn't even on screen right now.
    selectAllBtn.setOnClickListener {
        val filtered = typeFiltered(allItems)
        when (screen) {
            Screen.ALBUMS -> {
                // ── Pass-19 (no flicker): read the adapter's OWN list (it is ──
                // exactly the rendered set — no whole-library regroup) and patch
                // every card with the SELECTION-ONLY payload. The old
                // notifyDataSetChanged() blanked and re-decoded every visible
                // cover, so Select All / Clear on the Albums screen flashed the
                // entire grid.
                val albums = albumsAdapter.snapshot()
                val allSelected = albums.isNotEmpty() && albums.all { selectedAlbums.containsKey(it.bucketId) }
                albums.forEach {
                    if (allSelected) selectedAlbums.remove(it.bucketId)
                    else { selectedAlbums[it.bucketId] = it; notePreviewHint(firstMemberIdOfAlbum(it.bucketId)) }
                }
                albumsAdapter.notifySelectionChangedRange()
            }
            Screen.ALL_MEDIA -> {
                val allSelected = filtered.isNotEmpty() && filtered.all { selected.containsKey(it.id) }
                filtered.forEach { if (allSelected) selected.remove(it.id) else { selected[it.id] = it; notePreviewHint(it.id) } }
                mediaAdapter.notifySelectionChangedRange(0, filtered.size)
            }
            Screen.ALBUM_DETAIL -> {
                val inAlbum = filtered.filter { it.bucketId == openAlbum?.bucketId }
                val allSelected = inAlbum.isNotEmpty() && inAlbum.all { selected.containsKey(it.id) }
                inAlbum.forEach { if (allSelected) selected.remove(it.id) else { selected[it.id] = it; notePreviewHint(it.id) } }
                mediaAdapter.notifySelectionChangedRange(0, inAlbum.size)
            }
        }
        refreshHeaderSelection()
    }

    // ── Pass-21 (Clear — the whole selection, from anywhere) ─────────────
    // One tap wipes every individually-selected item AND every selected
    // album, across all three screens, then rebinds only the CURRENT
    // screen's selection chrome (the same payload discipline Select All
    // uses — no flicker, no thumbnail re-decode). The next
    // refreshHeaderSelection (called right here) hides the button again
    // and refreshes the dock's count.
    clearBtn.setOnClickListener {
        selected.clear()
        selectedAlbums.clear()
        previewOpenHintId = -1L
        when (screen) {
            Screen.ALBUMS -> albumsAdapter.notifySelectionChangedRange()
            Screen.ALL_MEDIA -> mediaAdapter.notifySelectionChangedRange(0, typeFiltered(allItems).size)
            Screen.ALBUM_DETAIL -> mediaAdapter.notifySelectionChangedRange(0,
                typeFiltered(allItems).count { it.bucketId == openAlbum?.bucketId })
        }
        refreshHeaderSelection()
    }

    tabAllMedia.setOnClickListener { if (screen != Screen.ALL_MEDIA) { screen = Screen.ALL_MEDIA; render() } }
    tabAlbums.setOnClickListener { if (screen != Screen.ALBUMS) { screen = Screen.ALBUMS; render() } }
    chipAll.setOnClickListener { if (typeFilter != MediaTypeFilter.ALL) { typeFilter = MediaTypeFilter.ALL; render() } }
    chipPhotos.setOnClickListener { if (typeFilter != MediaTypeFilter.IMAGES) { typeFilter = MediaTypeFilter.IMAGES; render() } }
    chipVideos.setOnClickListener { if (typeFilter != MediaTypeFilter.VIDEOS) { typeFilter = MediaTypeFilter.VIDEOS; render() } }
    backOrClose.setOnClickListener {
        if (screen == Screen.ALBUM_DETAIL) { screen = Screen.ALBUMS; openAlbum = null; render() }
        else sheet.dismiss()
    }

    refreshHeaderSelection()
    render()   // chrome (tabs/chips/icon/spinner) correct immediately; grids stay empty (loaded=false) until the query below returns
    // ── Pass-35 (#12): the BottomSheet provides the entrance (slide-up),
    // the backdrop, the drag system and the window chrome — the picker just
    // hands over its root, then layers the full-window preview above it and
    // nominates its chrome rows as drag regions.
    try {
        sheet.show { root }
    } catch (e: Exception) {
        // The window never made it up — the gate must not stay latched.
        PickerGate.release("media")
        return
    }
    sheet.addWindowOverlay(previewOverlay)
    // The picker's chrome: the app-bar header, the All/Albums segmented
    // control and the All/Photos/Videos filter row — every control on them
    // keeps its own touch (the hit-test gate); the empty chrome drags.
    sheet.registerDragRegion(header)
    sheet.registerDragRegion(tabsRow)
    sheet.registerDragRegion(filterRow)
    // ── Pass-35 (#4): the sheet's back decision, in the app-wide priority
    // order — preview first (the full-window overlay must never trap the
    // key), then the album-detail screen back to its grid, then exit
    // selection mode; only then does the press fall through to the sheet's
    // own dismissal. An open keyboard is consumed by the IME before this
    // hook ever sees a press.
    sheet.onBack = {
        when {
            previewOverlay.visibility == View.VISIBLE -> { closePreview(); true }
            screen == Screen.ALBUM_DETAIL -> { screen = Screen.ALBUMS; openAlbum = null; render(); true }
            selected.isNotEmpty() || selectedAlbums.isNotEmpty() -> {
                selected.clear(); selectedAlbums.clear(); render(); true
            }
            else -> false
        }
    }
    // The MediaStore load's entrance-settle anchor — the sheet's own 220ms
    // entrance replaces the old stage animation; the first render still
    // waits for it to settle (same freeze fix as always).
    val dialogShownAt = android.os.SystemClock.elapsedRealtime()

    // ── Pass-29 Part 2 (#2): the queued-files preview entry — the sheet ───────
    // opens STRAIGHT INTO its previewer over the caller's queue-ordered media
    // list, landing on the row whose eye icon was tapped.
    // ── Pass-37 (#6): a queue preview is now PREVIEW-ONLY in every visible ────
    // sense. The sheet's window is still the host (the whole previewer —
    // pager, filmstrip, arrows, zoom, video controls — is reused verbatim,
    // never a second preview system), but the picker itself never appears:
    // its content view is INVISIBLE, so no grid, no tabs and no chrome ever
    // render or flash, and the MediaStore query underneath never runs at all.
    // A tap on a queue image/video reads as exactly what it is — "my preview
    // opened" — never as "the Media Picker opened"; closing the preview
    // dismisses the window and lands back on the caller's screen. The
    // elevation fix that keeps the overlay above the (now invisible) sheet
    // stays as structural insurance, and with video pages rendering through
    // TextureView (Pass-37 #6/#7) the video pixels live in the window's
    // normal content layer, so videos draw above the sheet exactly like
    // images do — the two halves of the preview-layering regression.
    val queueMode = queuePreview != null && queuePreview.isNotEmpty()
    if (queueMode) {
        queuePreviewMode = true
        root.visibility = View.INVISIBLE
        openPreview(queuePreview, queuePreviewIndex)
    }

    // Chrome is on screen immediately; the actual MediaStore query runs
    // after, off the main thread -- exactly what showApkPickerSheet's OWN
    // sibling item in this phase (App Selection Sheet startup lag)
    // diagnoses as the real fix there too: don't make the person wait for
    // data before they see anything happen at all. (Pass-37 #6: skipped
    // entirely on the queue-preview entry — there is nothing to load.)
    if (!queueMode) {
        pickerScope.launch(Dispatchers.Main) {
            val items = MediaLibrary.query(ctx, MediaTypeFilter.ALL)
            // Post-v1.0 (§instant-open, second half): the first grid render must
            // never land mid-entrance. A fast MediaStore (small library, warm
            // cache) can beat the 160ms slide-up, and a full-list
            // notifyDataSetChanged + first grid layout + the first thumbnail
            // bind-burst DURING the animation is exactly the frame spike that
            // read as "freezes at start". If the query won the race, hold the
            // result for the remaining entrance window — the spinner is honest
            // the whole time, and the grid appears in ONE clean frame after the
            // sheet has settled instead of stuttering into view.
            val entranceSettleMs = 200L
            val elapsed = android.os.SystemClock.elapsedRealtime() - dialogShownAt
            if (elapsed in 0 until entranceSettleMs) delay(entranceSettleMs - elapsed)
            allItems = items
            loaded = true
            render()
        }
    }

    // ── GOAT-PASS 9 (§5 — live synchronization): while the Media sheet is ──────
    // OPEN, a MediaStore change (a photo/video lands from ANY app — camera,
    // a receive, a download) refreshes the grids by themselves: the hook
    // re-queries off-main and re-renders, with selection and the open
    // album intact (id/bucketId-keyed state; render() re-derives albums
    // from the fresh set). Throttled: bursts collapse into one reload, the
    // queue-preview mode never reloads (its data is the caller's queue, not
    // the library), and a quiet library costs nothing.
    if (!queueMode) {
        var lastSyncReloadAt = 0L
        val libSyncHook = com.lumidrop.x.media.LibrarySync.Listener { _, _ ->
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSyncReloadAt < 2_000L) return@Listener
            lastSyncReloadAt = now
            pickerScope.launch(Dispatchers.Main) {
                try {
                    val fresh = MediaLibrary.query(ctx, MediaTypeFilter.ALL)
                    allItems = fresh
                    loaded = true
                    render()
                } catch (e: Exception) { /* never break a live sheet over a sync refresh */ }
            }
        }
        libSyncHookRef = libSyncHook
        com.lumidrop.x.media.LibrarySync.addListener(libSyncHook)
    }
}

private enum class Screen { ALL_MEDIA, ALBUMS, ALBUM_DETAIL }

// ---- Grid adapters -----------------------------------------------------------

private class MediaGridAdapter(
    private val scope: CoroutineScope,
    private val thumbSizeDp: Int,
    private val isSelected: (Long) -> Boolean,
    private val onToggle: (MediaLibraryItem) -> Unit,
    // ── GOAT-PASS 7 (§5A): the cell's own long-press — the grid's RANGE-SELECTION
    // anchor path. Positional (adapter order = row-by-row, left-to-right grid
    // order), invoked with the pressed cell's binding position.
    private val onLongPress: (Int) -> Unit = {},
) : RecyclerView.Adapter<MediaGridAdapter.VH>() {

    init { setHasStableIds(true) }   // Pass-19: media-store ids are unique & stable — holders survive list re-submits

    private val items = mutableListOf<MediaLibraryItem>()

    fun submit(newItems: List<MediaLibraryItem>) {
        items.clear(); items.addAll(newItems)
        notifyDataSetChanged()
    }

    // ── Pass-19 (selection without flicker): the payload token for a
    // selection-only change. A plain notifyItemChanged rebinds the WHOLE
    // cell — which begins by clearing the thumbnail and relaunching the
    // decode, so every select/deselect visibly blanked the photo and
    // reloaded it (the reported tap-to-flash). The payload path below
    // touches ONLY the scrim / outline / check badge, leaving the bitmap
    // completely alone: zero flash, zero decode churn, zero scroll shift.
    object SelPayload

    fun notifySelectionChanged(position: Int) {
        notifyItemChanged(position, SelPayload)
    }

    fun notifySelectionChangedRange(positionStart: Int, itemCount: Int) {
        if (itemCount > 0) notifyItemRangeChanged(positionStart, itemCount, SelPayload)
    }

    // GOAT v2 (item 4 — drag-to-select): lets attachDragSelect (a generic
    // helper shared with AlbumGridAdapter, so it can't reach `items`
    // directly) resolve a touched position back to the real item.
    fun itemAt(position: Int): MediaLibraryItem? = items.getOrNull(position)

    override fun getItemId(position: Int): Long = items.getOrNull(position)?.id ?: RecyclerView.NO_ID

    inner class VH(val frame: FrameLayout) : RecyclerView.ViewHolder(frame) {
        val thumb = ImageView(frame.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        // v1.0 (redesign): the selected cell's crisp 1.5dp white rounded
        // outline — the standard "picked" look of professional media
        // pickers. Paired with the accent scrim it makes selection state
        // legible at a glance from any scroll position, without leaning on
        // the small check badge alone.
        val selBorder = View(frame.context)
        // Phase 1 (recycling discipline): the in-flight thumbnail decode for
        // THIS holder — cancelled the moment the holder rebinds to a different
        // item or scrolls out entirely, so a recycled cell can never keep
        // burning a decode-permit (or land a late bitmap onto a row it no
        // longer represents — the tag check below already guarded the visual
        // half; this guards the resource half).
        var decodeJob: kotlinx.coroutines.Job? = null
        val durationPill = TextView(frame.context)
        val scrim = View(frame.context)
        // ── Pass-29 Part 2 (#4): the top-right checkBadge overlay is REMOVED. ──
        // Selection is communicated by the container-wide highlight alone
        // (the accent scrim + the crisp white outline below) — the extra
        // per-tile checkmark read as cluttered on top of that highlight.
        // Multi-selection, the selection payload rebind, and every other
        // selection behavior are untouched.
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val frame = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, ctx.dp(thumbSizeDp))
        }
        // ── Pass-19 (overlay clipped to the card): the cell frame itself ──
        // now carries the same rounded-corner outline + clipToOutline the
        // thumbnail always had, so EVERY child — the accent scrim, the
        // selection outline, the badge — is clipped to the rounded shape
        // instead of drawing past the thumbnail's corners as a hard square
        // (the reported "selection overlay overflows the item's card,
        // especially around the rounded corners"). The scrim additionally
        // draws its OWN rounded background (see below) so the clip is not
        // the only line of defense on any API level.
        val cellRadius = ctx.resources.getDimension(R.dimen.radius_xl) / 2.2f
        frame.clipToOutline = true
        frame.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, cellRadius)
            }
        }
        val vh = VH(frame)
        vh.thumb.apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_thumb_small)
            clipToOutline = true
            // Uniform-corner background lets Android auto-derive a
            // correctly-rounded clip outline for the common case (see
            // Widgets.sectionCard's own doc comment on this technique) --
            // but that auto-outline is computed from the view's OWN laid-
            // out bounds, which aren't known yet at create time, so it's
            // supplied explicitly here and simply re-evaluates on every
            // layout pass (settling after the first, for a fixed-size grid
            // cell) rather than relying on a one-time-stale 0x0 guess.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = ctx.resources.getDimension(R.dimen.radius_xl) / 2.2f
                    outline.setRoundRect(0, 0, view.width, view.height, r)
                }
            }
        }
        vh.durationPill.apply {
            textSize = 9.5f; setTextColor(android.graphics.Color.WHITE)
            setPadding(ctx.dp(5), ctx.dp(2), ctx.dp(5), ctx.dp(2))
            background = pillBg(ctx)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.BOTTOM or Gravity.END
                it.marginEnd = ctx.dp(4); it.bottomMargin = ctx.dp(4)
            }
        }
        vh.scrim.apply {
            // ── Pass-19 (overlay clipped to the card): the scrim IS the ──
            // accent overlay, so it draws its own rounded shape — the same
            // corner radius as the thumbnail beneath it — instead of the
            // square ColorDrawable setBackgroundColor produced (a plain
            // rectangle whose corners poked out past the thumb's rounded
            // corners on every API level). Wherever the cell's bounds end,
            // the overlay ends with them, corners included.
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = cellRadius
                setColor((ContextCompat.getColor(ctx, R.color.accent) and 0x00FFFFFF) or 0x4D000000)   // 30% accent
            }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        vh.selBorder.apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                // Pass-19: the SAME radius as the cell outline above — the old
                // hard-coded 10dp fought the thumbnail's ~12.7dp corners.
                cornerRadius = cellRadius
                setStroke(ctx.dp(2), android.graphics.Color.argb(0xF2, 0xFF, 0xFF, 0xFF))   // ~95% white, 2dp reads as 1.5 crisp
            }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        frame.addView(vh.thumb)
        frame.addView(vh.scrim)
        frame.addView(vh.selBorder)
        frame.addView(vh.durationPill)
        return vh
    }

    // ── Pass-19 (selection without flicker): payload rebind path. A
    // selection toggle never changes the item, the thumbnail, or the
    // duration pill — rebinding all of them (clearing the bitmap, relaunching
    // the decode) for a visor flash was the reported flicker. Here only the
    // selection chrome updates, in place, with the bitmap untouched.
    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val sel = items.getOrNull(position)?.let { isSelected(it.id) } ?: false
            holder.scrim.visibility = if (sel) View.VISIBLE else View.GONE
            holder.selBorder.visibility = if (sel) View.VISIBLE else View.GONE
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ctx = holder.frame.context
        val item = items[position]
        holder.thumb.tag = item.id
        holder.thumb.setImageDrawable(null)
        val sel = isSelected(item.id)
        holder.scrim.visibility = if (sel) View.VISIBLE else View.GONE
        holder.selBorder.visibility = if (sel) View.VISIBLE else View.GONE
        if (item.isVideo) {
            holder.durationPill.visibility = View.VISIBLE
            holder.durationPill.text = formatDuration(item.durationMs)
        } else {
            holder.durationPill.visibility = View.GONE
        }
        holder.frame.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            onToggle(item)
            // Pass-19: SELECTION-ONLY rebind — the thumbnail is never
            // cleared and never re-decoded on a selection tap.
            notifySelectionChanged(pos)
        }
        // ── GOAT-PASS 7 (§5A): long-press anchors/range-selects through the ──
        // sheet's shared grid-range logic (adapter order). Consumed so it can
        // never also fire the click path; the drag-select helper arms on top
        // of it with its pass mode captured at DOWN (GOAT-PASS 11 §12).
        holder.frame.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
            onLongPress(pos)
            true
        }

        val localPath = item.path
        holder.decodeJob?.cancel()
        // Post-v1.0 fix (§open-freeze): the File.exists() stat ran DIRECTLY
        // in this launch body — scope is Main.immediate, so the body runs
        // synchronously inside onBindViewHolder up to its first suspension
        // point, i.e. a COLD-CACHE DISK STAT ON THE MAIN THREAD per cell.
        // A first screenful of ~30 cells bound in one layout pass did ~30
        // sequential stats during the very frame the sheet appeared — the
        // reported "lags/freezes at start". Wrapping the whole resolution
        // in withContext(Dispatchers.IO) suspends IMMEDIATELY: the stat and
        // the decode both happen off-main, and control returns to the main
        // thread only for the final (cheap) setImageBitmap. Same discipline
        // v1.0 already applied to QueueAdapter.loadRowThumb; this sheet was
        // the missed second instance.
        holder.decodeJob = scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                if (localPath != null && File(localPath).exists()) {
                    ThumbnailEngine.load(ctx, localPath, ThumbnailEngine.categoryOf(localPath.substringAfterLast('.')), thumbSizeDp)
                } else {
                    ThumbnailEngine.loadFromUri(ctx, item.uri, item.isVideo, thumbSizeDp)
                }
            }
            if (bmp != null && holder.thumb.tag == item.id) holder.thumb.setImageBitmap(bmp)
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.decodeJob?.cancel()
        holder.decodeJob = null
    }

    override fun getItemCount() = items.size
}

private class AlbumGridAdapter(
    private val scope: CoroutineScope,
    private val cellDp: Int,
    private val isSelected: (Long) -> Boolean,
    private val onTap: (MediaAlbum) -> Unit,
    // ── GOAT-PASS 7 (§5A): the cell's own long-press — the Albums grid's
    // RANGE-SELECTION anchor path, positional like the media grid's.
    private val onLongPress: (Int) -> Unit = {},
) : RecyclerView.Adapter<AlbumGridAdapter.VH>() {

    init { setHasStableIds(true) }   // Pass-19: bucketIds are unique & stable — holders survive re-submits

    private val albums = mutableListOf<MediaAlbum>()

    fun submit(newAlbums: List<MediaAlbum>) {
        albums.clear(); albums.addAll(newAlbums)
        notifyDataSetChanged()
    }

    // ── Pass-19 (selection without flicker): the selection-only payload for
    // album cells — same discipline as the media grid: a selection toggle
    // rebinds ONLY the scrim / outline / check badge, never the cover, so a
    // tap can no longer blank-and-reload every visible album cover (the
    // reported "Albums tab flickers on every selection/deselection").
    object SelPayload

    fun notifySelectionChangedFor(album: MediaAlbum) {
        val idx = albums.indexOfFirst { it.bucketId == album.bucketId }
        if (idx >= 0) notifyItemChanged(idx, SelPayload)
    }

    fun notifySelectionChangedRange() {
        if (albums.isNotEmpty()) notifyItemRangeChanged(0, albums.size, SelPayload)
    }

    /** Pass-19: the list exactly as the adapter holds it — refreshHeader-
     *  Selection's Albums branch reads THIS instead of re-running
     *  MediaLibrary.groupIntoAlbums over the whole library on every tap (an
     *  O(all items) regroup per selection change, pure waste once the grid
     *  already holds the answer). */
    fun snapshot(): List<MediaAlbum> = albums.toList()

    // GOAT v2 (item 4 — drag-to-select): lets attachDragSelect (shared
    // with MediaGridAdapter, so it can't reach `albums` directly) resolve
    // a touched position back to the real album.
    fun itemAt(position: Int): MediaAlbum? = albums.getOrNull(position)

    override fun getItemId(position: Int): Long = albums.getOrNull(position)?.bucketId ?: RecyclerView.NO_ID

    // Pass-6 (R-D — album cards, full redesign): the old album card was a
    // padded elevated card with a nested rounded cover and two text lines
    // BELOW it — a card-in-card that looked busy and generic. The new card
    // is one full-bleed square photo cover, clipped to a single radius, with
    // a bottom gradient scrim (bg_album_scrim) carrying the album name and
    // "N items · size" ON the cover in white — the modern gallery album
    // language (Google/Microsoft Photos), and materially calmer inside a
    // 3-column grid. A hairline stroke keeps the card's edge readable
    // against same-colored photos; selection keeps the exact visual
    // language the photo cells use (accent scrim + white outline + check
    // badge), so an album never reads as a different kind of thing once
    // it's selected.
    inner class VH(val card: FrameLayout) : RecyclerView.ViewHolder(card) {
        // Phase 1 (recycling discipline) — same as MediaGridAdapter.VH.
        var decodeJob: kotlinx.coroutines.Job? = null
        val cover = ImageView(card.context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        // Bottom gradient scrim that keeps the overlaid text legible.
        val grade = View(card.context)
        // Selection accent scrim (same 30% accent as photo cells).
        val scrim = View(card.context)
        // v1.0 (redesign): the selected album cover's matching white outline.
        val selBorder = View(card.context)
        // ── Pass-29 Part 2 (#4): no checkBadge on album covers either — the ──
        // accent scrim + outline carry selection, same as the photo cells.
        val name = TextView(card.context)
        val meta = TextView(card.context)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val radius = ctx.resources.getDimension(R.dimen.radius_lg)
        val card = FrameLayout(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor(ContextCompat.getColor(ctx, R.color.bg_card_elevated))
                setStroke(ctx.dp(1), android.graphics.Color.argb(0x1F, 0xFF, 0xFF, 0xFF))
            }
            elevation = ctx.dp(2).toFloat()
            isClickable = true; isFocusable = true
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, radius)
                }
            }
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, ctx.dp(cellDp))
        }
        val vh = VH(card)
        vh.cover.apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_thumb_small)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        vh.grade.apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_album_scrim)
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.BOTTOM
            }
        }
        vh.scrim.apply {
            // ── Pass-19 (overlay clipped to the card): the accent overlay ──
            // draws its own ROUNDED shape (the card's radius_lg) instead of
            // the square ColorDrawable setBackgroundColor produced — the
            // overlay's corners can never poke past the card's rounded
            // corners, on any API level, with or without parent clipping.
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = radius
                setColor((ContextCompat.getColor(ctx, R.color.accent) and 0x00FFFFFF) or 0x4D000000)   // 30% accent
            }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        vh.selBorder.apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = radius
                setStroke(ctx.dp(2), android.graphics.Color.argb(0xF2, 0xFF, 0xFF, 0xFF))
            }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        // The overlaid text block rides the scrim at the cover's bottom.
        val textBlock = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(ctx.dp(10), ctx.dp(8), ctx.dp(10), ctx.dp(9))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.BOTTOM
            }
        }
        vh.name.apply {
            textSize = 12.5f; setTextColor(android.graphics.Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(3f, 0f, 1f, android.graphics.Color.argb(0x99, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        vh.meta.apply {
            textSize = 10.5f; setTextColor(android.graphics.Color.argb(0xB3, 0xFF, 0xFF, 0xFF))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setShadowLayer(3f, 0f, 1f, android.graphics.Color.argb(0x99, 0, 0, 0))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        textBlock.addView(vh.name); textBlock.addView(vh.meta)
        card.addView(vh.cover)
        card.addView(vh.grade)
        card.addView(textBlock)
        card.addView(vh.scrim)
        card.addView(vh.selBorder)
        return vh
    }

    // ── Pass-19 (selection without flicker): payload rebind path — a
    // selection toggle touches ONLY the scrim / outline / check badge; the
    // cover bitmap, the text block, and the click binding are all left alone,
    // so no cover ever blanks or re-decodes on a selection change.
    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty()) {
            val album = albums.getOrNull(position)
            val sel = album?.let { isSelected(it.bucketId) } ?: false
            holder.scrim.visibility = if (sel) View.VISIBLE else View.GONE
            holder.selBorder.visibility = if (sel) View.VISIBLE else View.GONE
            return
        }
        super.onBindViewHolder(holder, position, payloads)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ctx = holder.card.context
        val album = albums[position]
        holder.name.text = album.name
        holder.meta.text = "${album.itemCount} item${if (album.itemCount != 1) "s" else ""} · ${fmtBytes(album.totalBytes)}"
        val sel = isSelected(album.bucketId)
        holder.scrim.visibility = if (sel) View.VISIBLE else View.GONE
        holder.selBorder.visibility = if (sel) View.VISIBLE else View.GONE
        holder.cover.setImageDrawable(null)
        holder.cover.tag = album.bucketId
        holder.card.setOnClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnClickListener
            onTap(album)
        }
        // ── GOAT-PASS 7 (§5A): long-press anchors/range-selects through the ──
        // sheet's shared grid-range logic (adapter order). Consumed so it can
        // never also fire the tap path (which would browse into the album on
        // an empty selection).
        holder.card.setOnLongClickListener {
            val pos = holder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
            onLongPress(pos)
            true
        }
        val cover = album.cover
        val localPath = cover.path
        holder.decodeJob?.cancel()
        // Same Main.immediate-stat fix as the media grid above (§open-freeze).
        holder.decodeJob = scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                if (localPath != null && File(localPath).exists()) {
                    ThumbnailEngine.load(ctx, localPath, ThumbnailEngine.categoryOf(localPath.substringAfterLast('.')), cellDp)
                } else {
                    ThumbnailEngine.loadFromUri(ctx, cover.uri, cover.isVideo, cellDp)
                }
            }
            if (bmp != null && holder.cover.tag == album.bucketId) holder.cover.setImageBitmap(bmp)
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.decodeJob?.cancel()
        holder.decodeJob = null
    }

    override fun getItemCount() = albums.size
}

// ═══ Pass-20: the Media Picker PREVIEW pager adapter ═══════════════════════
// One page per selected item: images render through a memory-bounded,
// EXIF-aware decode (never full-resolution-all-at-once — only the ~1-2
// cells the pager keeps bound ever decode, each decode is cancelled the
// moment its cell recycles, and the decode target is capped at roughly the
// screen's own pixel dimensions); videos render through a VideoView with a
// compact custom control row (big one-shot play button, play/pause +
// seekbar + position/duration label), whose MediaPlayer is released the
// instant its cell recycles, the preview closes, or the whole sheet is
// dismissed. Errors (missing file, unsupported codec) surface honestly as
// an on-cell message — never a blank page.
// ── Pass-38 (#2): the zoom level past which a bound image page asks (once)
// for its sharper refine decode — comfortably before the fit bitmap's pixels
// become visible on screen, late enough that an accidental 1.1× brush never
// pays for a second decode.
private const val REFINE_TRIGGER_ZOOM = 1.3f

// ── Pass-38 (#3): the preview pager refuses to CLAIM a multi-touch stream ────
// RecyclerView's interception only knows single-pointer drag slop; a second
// finger landing while the first was still (below slop — the classic pinch
// setup) used to leave the interception decision to a moment where the two-
// finger centroid drift could read as a scroll start, CANCELing the image
// cell mid-pinch and killing the gesture. The page cell already disallows
// interception the instant its second pointer lands; this half closes the
// other side: the pager itself never BEGINS intercepting on any event that
// carries (or just gained) a second pointer. A drag it has legitimately
// claimed (true single-finger paging) is untouched — once a parent has
// intercepted, this callback is no longer consulted for that stream.
private class PreviewPagerRecyclerView(context: android.content.Context) : RecyclerView(context) {
    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN || e.pointerCount > 1) return false
        return super.onInterceptTouchEvent(e)
    }
}

private class PreviewPagerAdapter(
    private val scope: CoroutineScope,
    private val items: List<MediaLibraryItem>,
    private val onVideoActive: (TextureMovieView?) -> Unit,
    // Pass-20: identity-guarded inactive callback — recycling an OLD cell
    // must never clear the reference to a NEWER video that already bound
    // (bind-new runs before recycle-old while the pager scrolls), so the
    // clear only lands when the recycled instance IS the tracked one.
    private val onVideoInactive: (TextureMovieView) -> Unit,
    // ── Pass-38 (#9): the music-coordination edge. Each video view reports
    // its own playing-state transitions; the sheet keeps the exact set of
    // playing videos and tells MusicPlayback when that set's emptiness
    // changes (true→false = the last video stopped → eligible auto-resume).
    private val onVideoPlayingChanged: (TextureMovieView, Boolean) -> Unit,
    // ── Pass-26 (#2.1): live zoom state of the currently bound image cell ──
    // (1f = fit scale). Every applyZoom/zoomToAnimated/reset of a bound cell
    // reports through here, which is what drives the preview overlay's
    // "return to 1×" control in/out — one writer (the cell), one reader
    // (the overlay), no polling, no drift.
    private val onZoomScaleChanged: (Float) -> Unit,
    // ── Pass-29 Part 2 (#3B): fired on a confirmed single tap on a zoomable ──
    // image page — the image cell's own gesture stream. The overlay's
    // chrome-toggle action rides this; video pages reach the same toggle
    // through the pager-level observer in the sheet code (a bare VideoView
    // doesn't consume taps, so its taps never arrive here).
    private val onMediaTap: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val TYPE_IMAGE = 0
    private val TYPE_VIDEO = 1

    // ── Pass-31 (#1): every LIVE video holder, keyed for the page-leaving ────
    // pause. A pager keeps ±1 neighbor pages cached, and a VideoView that
    // scrolled out of view keeps PLAYING (its surface dies with the detach on
    // most builds, but the audio stream does not) — so the sheet's
    // updatePreviewChrome asks the adapter to pause every video whose page is
    // no longer the current one, immediately, on every page change. Holders
    // register in bind and unregister in onViewRecycled; a recycled holder
    // can never be paused "by accident" because it is gone from the set the
    // moment it recycles.
    private val liveVideoHolders = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<VideoVH, Boolean>())

    /** ── Pass-31 (#1): stop playback on every video page EXCEPT [currentPosition] ──
     *  — the "the previous video kept playing / stayed visible" half of the
     *  reported regression. PAUSE (not stopPlayback) is deliberate: the page
     *  is only a swipe away, and pause leaves the player resumable exactly
     *  where it was while stopping the picture, the sound and the progress
     *  clock dead in their tracks; a holder that finally RECYCLES still gets
     *  the full stopPlayback() release it always had. The per-cell 250ms
     *  ticker repaints the toggle icon from the player's own isPlaying right
     *  after, so the control state can never disagree with the media. */
    /** ── Pass-38 (#9 + resource audit): full release of EVERY live video ──────
     *  holder — the sheet-dismiss path. Window teardown does not run
     *  onViewRecycled, so the pager's cached (paused) neighbor players would
     *  otherwise survive behind a dead window; and each stopPlayback reports
     *  its playing edge, which drains the sheet's playing-set and settles the
     *  music coordinator on "no video active" (auto-resume eligible). */
    fun releaseAllVideoPages() {
        for (h in liveVideoHolders.toList()) {
            try { h.video.stopPlayback() } catch (e: Exception) { /* already gone */ }
            h.boundItem = null
            h.boundPosition = -1
        }
        liveVideoHolders.clear()
    }

    /** ── Pass-38 (#2): bound the refine memory to the page the user is on. ──
     * When the pager settles on a new page, every CACHED neighbor still
     * holding its refined bitmap (up to 8 MP each) trades back to the fit
     * bitmap — an off-screen swap, invisible by definition, so the worst
     * case is ONE refined bitmap live at a time no matter how many pages
     * the user zoomed through. `refined` resets with it: returning to that
     * page and zooming re-refines exactly like the first visit. */
    fun demoteRefinedBitmaps(currentPosition: Int) {
        for (h in attachedHolders.toList()) {
            if (h !is ImageVH || h.boundPosition < 0 || h.boundPosition == currentPosition) continue
            if (!h.refined || h.refineJob?.isActive == true) continue
            val item = items.getOrNull(h.boundPosition) ?: continue
            val ctx = h.frame.context
            h.refined = false
            val fitDim = maxOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
            scope.launch {
                val bmp = withContext(Dispatchers.IO) { decodePreviewBitmap(ctx, item, fitDim) }
                if (bmp != null && h.img.tag == "pv:${h.boundPosition}") {
                    // Only trade DOWN when the page hasn't re-refined while
                    // this decode was in flight (its own landing wins then).
                    if (!h.refined) h.img.setImageBitmap(bmp)
                }
            }
        }
    }

    fun pauseVideoPagesExcept(currentPosition: Int) {
        // ── Pass-33 (#2): when the page being navigated TO is an IMAGE, ─────────
        // every other video is fully STOPPED, not paused. A paused VideoView
        // keeps its player alive and its LAST FRAME on the surface — the
        // ghost that could reappear over (or instead of) the tapped image
        // while any layout/snap correction was in flight. stopPlayback()
        // releases the player and clears the surface outright, so the
        // previous video cannot remain visible, audible, or resume itself,
        // no matter what the surrounding layout does; the image binds and
        // decodes on a completely clean page. Video→video navigation keeps
        // the Pass-31 PAUSE (a quick return to the neighboring clip with its
        // position intact is the better experience there, and the paused
        // frame belongs to a page the user can still see).
        val targetIsImage = items.getOrNull(currentPosition)?.isVideo == false
        for (h in liveVideoHolders) {
            if (h.boundPosition != currentPosition) {
                try {
                    if (targetIsImage) h.video.stopPlayback()
                    else if (h.video.isPlaying) h.video.pause()
                } catch (e: Exception) { /* stale player mid-release */ }
            }
        }
    }

    override fun getItemViewType(position: Int): Int = if (items[position].isVideo) TYPE_VIDEO else TYPE_IMAGE

    // ── Image cell ─────────────────────────────────────────────────────────
    // ── Pass-22 (THE build-killer, root-fixed) ───────────────────────────────
    // The pass-21 "case fix" corrected the ImageVh/VideoVh spelling but BOTH
    // holder classes still declared NO supertype — an Adapter<RecyclerView.
    // ViewHolder> cannot return them from onCreateViewHolder, so the delivered
    // source STILL did not compile (six type errors here alone), the filmstrip
    // VH had the same hole, and the "preview images never appear" report was
    // the user being unable to build passes 20/21 at all. Every holder now
    // extends RecyclerView.ViewHolder(itemView = frame) — the declaration the
    // adapter contract always required.
    private class ImageVH(val frame: FrameLayout, val img: ImageView, val spinner: ProgressBar, val error: TextView)
        : RecyclerView.ViewHolder(frame) {
        var decodeJob: kotlinx.coroutines.Job? = null
        // ── Pass-38 (#2): the zoom-refine state. One sharper decode per bind, ──
        // launched the moment the page's zoom crosses the trigger (or the fit
        // bitmap lands while already zoomed); the swap is tag-guarded like the
        // fit decode, and `refined` makes it strictly once per bound item.
        var refineJob: kotlinx.coroutines.Job? = null
        var refined = false
        var boundPosition = -1
        var refineRequest: (() -> Unit)? = null
        // ── Pass-24 (#4B: interactive image zoom) ───────────────────────────────
        // Zoom/pan state for this cell, expressed as view transforms layered on
        // top of the unchanged FIT_CENTER layout (scaleX/scaleY around the view
        // center + translation), so the decode pipeline, the pager's page
        // sizing, and the filmstrip never need to know a zoom exists. A fresh
        // bind or a recycled cell resets it (see onBindViewHolder/onViewRecycled).
        var zoomScale = 1f
        var zoomX = 0f
        var zoomY = 0f
        // ── Pass-26 (#2.1): live zoom reporting ──────────────────────────────
        // The overlay's "return to 1×" control must appear/disappear with the
        // CURRENT page's zoom state, but the state itself is private to this
        // holder. The adapter hands the cell this reporter at bind time (null
        // on recycle); applyZoom/zoomToAnimated invoke it, so the overlay
        // follows every pinch frame, every animated zoom and every reset
        // without the two ever drifting apart.
        var zoomReporter: ((Float) -> Unit)? = null
        fun applyZoom() {
            img.scaleX = zoomScale; img.scaleY = zoomScale
            img.translationX = zoomX; img.translationY = zoomY
            zoomReporter?.invoke(zoomScale)
            // ── Pass-38 (#2): crossing the refine trigger on a page that is ──
            // actually SHOWING its bitmap asks (once) for the sharper decode.
            // An empty page (fit decode still in flight) stays silent — the
            // landing branch in onBindViewHolder fires the request instead if
            // the user was already zoomed by then.
            if (zoomScale >= REFINE_TRIGGER_ZOOM && !refined && img.drawable != null) {
                refineRequest?.invoke()
            }
        }
        fun reportZoom() { zoomReporter?.invoke(zoomScale) }
        fun resetZoom() { zoomScale = 1f; zoomX = 0f; zoomY = 0f; applyZoom() }
        /** Clamps pan so the fitted image can never be dragged entirely off
         *  screen — the classic gallery rule: at fit scale there is nothing to
         *  pan, and beyond it the image stops exactly at its own edges. */
        fun clampPanToDrawable() {
            if (zoomScale <= 1f) { zoomX = 0f; zoomY = 0f; return }
            val vw = img.width.toFloat(); val vh = img.height.toFloat()
            if (vw <= 0f || vh <= 0f) return
            val d = img.drawable ?: return
            val dw = d.intrinsicWidth.toFloat(); val dh = d.intrinsicHeight.toFloat()
            if (dw <= 0f || dh <= 0f) { zoomX = 0f; zoomY = 0f; return }
            val fit = minOf(vw / dw, vh / dh)
            val maxX = ((dw * fit * zoomScale - vw) / 2f).coerceAtLeast(0f)
            val maxY = ((dh * fit * zoomScale - vh) / 2f).coerceAtLeast(0f)
            zoomX = zoomX.coerceIn(-maxX, maxX)
            zoomY = zoomY.coerceIn(-maxY, maxY)
        }
        /** Animated zoom to [target] (double-tap in/out) — the state fields
         *  land at the target immediately so a pinch starting mid-animation
         *  continues smoothly from the RENDERED values (onScale reads the
         *  view, not these fields). ── Pass-38 (#3): an optional FOCAL point
         *  anchors the zoom where the user tapped — the content under the
         *  finger stays under the finger (the pinch's own anchor rule, applied
         *  once from the current rendered state) instead of the old always-
         *  center zoom that visibly slid the image away from the tap. */
        fun zoomToAnimated(target: Float, focusX: Float = Float.NaN, focusY: Float = Float.NaN) {
            zoomScale = target
            if (!focusX.isNaN() && !focusY.isNaN()) {
                val vw = img.width.toFloat(); val vh = img.height.toFloat()
                if (vw > 0f && vh > 0f) {
                    val k = target / (img.scaleX.coerceAtLeast(0.01f))
                    val sx = focusX - vw / 2f
                    val sy = focusY - vh / 2f
                    val tx = img.translationX; val ty = img.translationY
                    zoomX = sx - (sx - tx) * k
                    zoomY = sy - (sy - ty) * k
                    clampPanToDrawable()
                } else { zoomX = 0f; zoomY = 0f }
            } else { zoomX = 0f; zoomY = 0f }
            img.animate().cancel()
            img.animate().scaleX(target).scaleY(target)
                .translationX(zoomX).translationY(zoomY)
                .setDuration(200).setInterpolator(Motion.DECELERATE)
                .withEndAction { applyZoom() }
                .start()
            // Pass-26 (#2.1): report the landing state immediately — the
            // overlay's reset control must appear the moment a zoom-in
            // begins, not 200ms later when the settle animation ends.
            reportZoom()
        }
        fun resetZoomAnimated() = zoomToAnimated(1f)
    }

    private class VideoVH(val frame: FrameLayout, val video: TextureMovieView,
                          val bigPlay: ImageView, val spinner: ProgressBar, val error: TextView,
                          val toggleIcon: ImageView, val seek: android.widget.SeekBar,
                          val timeLabel: TextView,
                          // ── Pass-29 Part 2 (#3B): the cell's control row — the ──
                          // chrome toggle fades this whole pill in/out with the
                          // rest of the preview chrome.
                          val controls: LinearLayout)
        : RecyclerView.ViewHolder(frame) {
        var progressJob: kotlinx.coroutines.Job? = null
        var boundItem: MediaLibraryItem? = null
        // ── Pass-31 (#1): the page this holder is currently bound to — the ──
        // adapter's pauseVideoPagesExcept reads it to stop playback on every
        // video page the preview has navigated away from (see its own doc).
        var boundPosition = -1
        // ── Pass-32 (#3): the duration whose time-label width this holder has ──
        // already reserved (see the bind's reserveTimeLabelWidth). Resets to 0
        // on every rebind so a recycled holder never keeps a previous clip's
        // oversized reservation.
        var pinnedTimeTotalMs = 0L
        // Pass-21 (scrub semantics): true between onStartTrackingTouch and
        // onStopTrackingTouch — the seek bar's position is then the USER's
        // hand, not the player's clock, and neither the progress loop nor
        // any player callback may move it.
        var scrubbing = false
        // ── Pass-35 (#10): the seek the user ASKED FOR, pinned until the ──
        // player's real position converges on it. MediaPlayer.seekTo is
        // asynchronous and getCurrentPosition keeps reporting the OLD
        // position until the seek lands — the 250ms ticker used to read that
        // stale value and snap the bar and the time label BACK to the
        // pre-seek position ("tap 46s, shows 46s, jumps back to 40s"). While
        // a seek is pending, the UI shows the requested target; the pin
        // clears the moment the player's own position reaches it (± one
        // tick), or after a bounded number of ticks if a broken stream never
        // converges (the UI then follows whatever the player really says —
        // honest, never permanently stuck on a lie).
        var pendingSeekMs = -1L
        var pendingSeekTicks = 0
        // Pass-21: relaunches the progress ticker after a scrub released the
        // bar (the listener closures below capture this reference).
        var relaunchTicker: (() -> Unit)? = null
        // ── Pass-29 Part 2 (#3B): the big play disc's playback-logical ──
        // visibility (GONE once tapped/playing, back on completion), saved
        // while the chrome hides it so a chrome restore puts back exactly
        // what the player state says — never a play button over a video
        // that is already running, never a lost one after a pause.
        var bigPlaySaved: Int = View.VISIBLE
        // ── Pass-30 (#6): the last play/pause state the toggle ICON was ──
        // painted for (null = never painted for this binding). Purely a
        // redundant-paint guard — the SOURCE OF TRUTH is always the player's
        // own isPlaying, read at every event and every ticker tick, so the
        // icon can never drift from what the media element is doing.
        var paintedPlaying: Boolean? = null
    }

    // ── Pass-24 (#4B): the zoom interaction wiring ──────────────────────────
    private val maxPreviewZoom = 4f
    private val doubleTapZoom = 2.5f
    private fun attachZoomInteractions(holder: ImageVH) {
        val img = holder.img
        val ctx = img.context
        // ── Pass-38 (#3): PINCH OWNS THE STREAM WHILE IT RUNS ──────────────
        // GestureDetector reports onScroll for the two-finger CENTROID
        // movement too — the old wiring let BOTH the ScaleGestureDetector's
        // focal anchor math AND onScroll's plain translation adjust the same
        // pan on every MOVE of a pinch, and the two fought each frame (the
        // reported "glitchy, jumps around during pinch-to-zoom"). From
        // onScaleBegin to onScaleEnd the pinch is the one and only writer;
        // one-finger panning resumes the moment it ends.
        var pinching = false
        val scaleDetector = android.view.ScaleGestureDetector(ctx,
            object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: android.view.ScaleGestureDetector): Boolean {
                    img.animate().cancel()
                    pinching = true
                    img.parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                    // Continue from the RENDERED values (a double-tap animation
                    // may still be settling) and keep the pinch focus anchored:
                    // the content point under the fingers stays under them.
                    val old = img.scaleX.coerceAtLeast(0.01f)
                    val nw = (old * detector.scaleFactor).coerceIn(1f, maxPreviewZoom)
                    val vw = img.width.toFloat(); val vh = img.height.toFloat()
                    val tx = img.translationX; val ty = img.translationY
                    if (nw != old && vw > 0f && vh > 0f) {
                        val k = nw / old
                        val sx = detector.focusX - vw / 2f
                        val sy = detector.focusY - vh / 2f
                        holder.zoomX = sx - (sx - tx) * k
                        holder.zoomY = sy - (sy - ty) * k
                    } else {
                        holder.zoomX = tx; holder.zoomY = ty
                    }
                    holder.zoomScale = nw
                    holder.clampPanToDrawable()
                    holder.applyZoom()
                    return true
                }
                override fun onScaleEnd(detector: android.view.ScaleGestureDetector) {
                    pinching = false
                    // ── Pass-26 (#2.1): settle out of a near-fit pinch ──────────
                    // A pinch that came back down to (almost) fit now ANIMATES
                    // the last few percent to exactly 1× instead of snapping
                    // there in one frame — the smooth, glitch-free zoom-out
                    // ending. The interception flag is NOT touched here: the
                    // UP/CANCEL branch below releases it only once the rendered
                    // scale is genuinely back at fit, so a settle animation can
                    // never hand the still-zooming page to the pager mid-flight.
                    if (holder.zoomScale <= 1.02f) {
                        holder.resetZoomAnimated()
                    }
                }
            })
        val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            // ── Pass-29 Part 2 (#3B): one confirmed single tap on the image ──
            // toggles the preview chrome. onSingleTapConfirmed fires only
            // after the gesture is provably NOT a double-tap, scroll or
            // pinch — the zoom interactions above keep full ownership of
            // their own gestures, and a tap that moves past touch slop
            // becomes a page swipe in the pager instead.
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onMediaTap()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                // ── Pass-38 (#3): the double-tap zooms toward the TAP ──
                // point (the content under the finger stays under it), and
                // it can never fight the pinch: it fires from the SAME
                // gesture stream the ScaleGestureDetector reads, and an
                // immediately-following pinch simply continues from the
                // rendered state (onScaleBegin cancels the animation first).
                if (img.scaleX > 1.01f) holder.resetZoomAnimated()
                else holder.zoomToAnimated(doubleTapZoom, e.x, e.y)
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                // ── Pass-38 (#3): while a pinch runs, the ScaleGesture─ ──
                // Detector owns translation (see pinching above); while more
                // than one finger is down at all, the centroid is not a pan.
                if (pinching || e2.pointerCount > 1) return false
                if (img.scaleX <= 1.001f) return false   // fit scale — the pager owns drags
                holder.zoomX = img.translationX - distanceX
                holder.zoomY = img.translationY - distanceY
                holder.zoomScale = img.scaleX
                holder.clampPanToDrawable()
                holder.applyZoom()
                return true
            }
        })
        // ── Pass-26 (#2.1): the image now ALWAYS consumes the gesture stream ──
        //
        // The pass-24 listener returned `consumed || consumed2 || scaleX > 1`
        // — which is FALSE for the DOWN of a fit-scale image. In Android's
        // touch model a child that declines the DOWN never receives the rest
        // of that gesture: the pager (RecyclerView) became the touch target
        // on the first finger's move, the second finger's POINTER_DOWN went
        // straight to the pager, and neither the ScaleGestureDetector nor the
        // GestureDetector ever saw a full stream — pinch and double-tap only
        // engaged by luck (typically not at all from fit scale), which is
        // exactly the reported "zoom is not smooth or reliable".
        //
        // The fix is ownership, not thresholds: the image consumes every
        // event, and EXCLUSIVITY with the pager is negotiated explicitly via
        // requestDisallowInterceptTouchEvent —
        //   • DOWN while already zoomed → disallow (a zoomed pan must not
        //     page away under the finger);
        //   • DOWN at fit scale → allow (the pager stays free to intercept
        //     the drag past touch slop, and does — paging keeps working
        //     exactly as before, via the CANCEL it sends the image);
        //   • second finger down → disallow (the pinch takes over);
        //   • UP/CANCEL → release only if the rendered scale is back at fit.
        img.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    img.animate().cancel()
                    img.parent?.requestDisallowInterceptTouchEvent(img.scaleX > 1.001f)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (e.pointerCount >= 2) img.parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            // The detectors' internal state advances via onTouchEvent; the
            // listener returns true unconditionally (see the ownership note
            // above), so their per-event return values no longer gate
            // anything — the real decisions live in the branches above and
            // in the detectors' own callbacks.
            scaleDetector.onTouchEvent(e)
            gestureDetector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // AFTER the detectors have seen the terminal event
                    // (onScaleEnd may have just started a settle animation):
                    // release the pager only at genuine fit scale, and snap
                    // any residual sub-1 state so the next pinch starts from
                    // a clean baseline.
                    if (img.scaleX <= 1.001f) {
                        img.parent?.requestDisallowInterceptTouchEvent(false)
                        if (holder.zoomScale < 1f) {
                            holder.zoomScale = 1f; holder.zoomX = 0f; holder.zoomY = 0f
                            holder.applyZoom()
                        }
                    }
                    holder.reportZoom()
                }
            }
            true
        }
    }

    /** ── Pass-26 (#2.1): reset the CURRENTLY SHOWN image to fit scale ──────
     *  The overlay-level "1×" control's action: resolve the page the pager
     *  is settled on and animate THAT cell back to fit — no cross-component
     *  state, no chance of resetting a recycled cell. */
    fun resetCurrentZoom(pager: RecyclerView) {
        val lm = pager.layoutManager as? GridLayoutManager ?: return
        val pos = lm.findFirstVisibleItemPosition()
        if (pos < 0) return
        (pager.findViewHolderForAdapterPosition(pos) as? ImageVH)?.resetZoomAnimated()
    }

    // ── Pass-29 Part 2 (#3B): the chrome toggle's per-cell half. ─────────────
    // The overlay drives the whole preview chrome; the only chrome that
    // lives INSIDE cells is each video page's control row + big play disc,
    // so the adapter mirrors the overlay's state onto every attached
    // VideoVH (and onto any cell that binds while the chrome is away — a
    // recycled cell never flashes a control row over a hidden-chrome
    // preview). Image pages have no cell-level chrome to hide.
    private var chromeVisible = true
    private val attachedHolders = java.util.Collections.synchronizedList(ArrayList<RecyclerView.ViewHolder>())

    fun setChromeVisible(visible: Boolean, animate: Boolean) {
        if (visible == chromeVisible) return
        chromeVisible = visible
        for (h in attachedHolders.toList()) applyChromeToHolder(h, visible, animate)
    }

    private fun applyChromeToHolder(h: RecyclerView.ViewHolder, visible: Boolean, animate: Boolean) {
        if (h !is VideoVH) return
        h.controls.animate().cancel()
        if (animate) {
            if (visible) {
                h.controls.visibility = View.VISIBLE
                h.controls.alpha = 0f
                h.controls.animate().alpha(1f).setDuration(Motion.DURATION_SHORT)
                    .setInterpolator(Motion.DECELERATE).start()
            } else {
                h.controls.animate().alpha(0f).setDuration(Motion.DURATION_SHORT)
                    .setInterpolator(Motion.DECELERATE)
                    .withEndAction { if (!chromeVisible) h.controls.visibility = View.GONE }
                    .start()
            }
        } else {
            h.controls.visibility = if (visible) View.VISIBLE else View.GONE
            h.controls.alpha = 1f
        }
        if (!visible) {
            // Save the disc's playback-logical visibility before hiding it,
            // so a chrome restore brings back exactly the state the player
            // is in (playing → stays gone; paused/untouched → comes back).
            h.bigPlaySaved = h.bigPlay.visibility
            h.bigPlay.animate().cancel()
            if (animate) {
                h.bigPlay.animate().alpha(0f).setDuration(Motion.DURATION_SHORT)
                    .setInterpolator(Motion.DECELERATE)
                    .withEndAction { if (!chromeVisible) h.bigPlay.visibility = View.GONE }
                    .start()
            } else {
                h.bigPlay.visibility = View.GONE
                h.bigPlay.alpha = 1f
            }
        } else {
            h.bigPlay.animate().cancel()
            h.bigPlay.visibility = h.bigPlaySaved
            h.bigPlay.alpha = 1f
        }
    }

    override fun onViewAttachedToWindow(holder: RecyclerView.ViewHolder) {
        attachedHolders.add(holder)
        // A page attaching while the chrome is away starts hidden — no
        // flash of a control row the user just dismissed.
        if (!chromeVisible) applyChromeToHolder(holder, false, false)
    }

    override fun onViewDetachedFromWindow(holder: RecyclerView.ViewHolder) {
        attachedHolders.remove(holder)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val ctx = parent.context
        fun spinner() = ProgressBar(ctx).apply {
            indeterminateTintList = android.content.res.ColorStateList.valueOf(ContextCompat.getColor(ctx, R.color.accent))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.CENTER
            }
        }
        val frame = FrameLayout(ctx).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        // Pass-22: the honest per-cell failure label finally carries TEXT — the
        // pass-20/21 placeholder was an empty styled TextView, so a failed
        // decode hid the spinner and showed NOTHING (a blank black page with
        // no image and no explanation — "images do not appear"). A failed load
        // is now a stated fact on the cell.
        fun errText() = TextView(ctx).apply {
            text = "Couldn't load this item"
            textSize = 12.5f; setTextColor(0xB3FFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(dpPx(ctx, 24), 0, dpPx(ctx, 24), 0)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.CENTER
            }
        }
        return if (viewType == TYPE_IMAGE) {
            val img = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).also {
                    it.gravity = Gravity.CENTER
                }
            }
            val sp = spinner(); val er = errText()
            frame.addView(img); frame.addView(sp); frame.addView(er)
            // ── Pass-24 (#4B): attach the zoom interactions ─────────────────
            // Pinch to zoom (1x–4x, focused on the pinch point), one-finger
            // pan while zoomed (clamped to the image edges), and double-tap
            // to zoom in / return to the original fit-to-screen view. While
            // zoomed (or the moment a second finger lands) the pager is
            // asked not to intercept, so panning never fights page swipes;
            // at fit scale one-finger drags keep flowing to the pager and
            // page navigation works exactly as before.
            val vh = ImageVH(frame, img, sp, er)
            attachZoomInteractions(vh)
            // Pass-21: the delivered pass-20 code instantiated and type-checked
            // these holders as ImageVh/VideoVh (lowercase h) against
            // declarations named ImageVH/VideoVH — a case mismatch Kotlin
            // cannot resolve, i.e. a guaranteed compile failure that the
            // no-JDK build machine's balance checks could never catch (the
            // reported "tapping an image opens the preview but nothing
            // happens" was this dead code path). Every reference now uses
            // the declared names.
            vh
        } else {
            val video = TextureMovieView(ctx).apply {
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).also {
                    it.gravity = Gravity.CENTER
                }
                // ── Pass-38 (#9): this view reports its OWN playing-state ──────
                // edges (start/pause/completion/error/release — every stop path
                // funnels through the wrapper), so the sheet's playing-set (and
                // through it the music auto-pause/resume coordinator) always
                // mirrors what is actually audible. Wired once at creation: the
                // view is reused across items, and the set membership is per
                // VIEW, independent of which item it currently shows.
                setOnPlayingChangedListener { playing -> onVideoPlayingChanged(this, playing) }
            }
            val bigPlay = ImageView(ctx).apply {
                setImageDrawable(Widgets.icon(ctx, R.drawable.ic_play, android.graphics.Color.WHITE))
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(0x66000000.toInt())
                    setStroke(dpPx(ctx, 1), 0x40FFFFFF.toInt())
                }
                // ── Pass-21 (optical centering of the play glyph) ─────────────
                // The triangle's bounding box runs x 7..19 of the 24dp viewport,
                // so its box-center sits at x=13 — one viewport unit LEFT of the
                // geometric center every media player nudges it right of. The
                // asymmetric padding shifts the drawing box +2dp, putting the
                // perceived weight exactly on the button's center (the standard
                // optical correction for every play glyph since the first
                // media player).
                setPadding(dpPx(ctx, 16), dpPx(ctx, 14), dpPx(ctx, 12), dpPx(ctx, 14))
                layoutParams = FrameLayout.LayoutParams(dpPx(ctx, 72), dpPx(ctx, 72)).also {
                    it.gravity = Gravity.CENTER
                }
            }
            val sp = spinner(); val er = errText()
            // ── Pass-21 (the control row, redesigned) ─────────────────────────
            // Fixed 44dp height, every child vertically centered on the SAME
            // axis: a 32dp toggle, the purpose-built seek bar (thick 4dp
            // track + 14dp thumb — the default hairline SeekBar was the
            // "difficult to use and control" report), and a font-padding-
            // stripped time label that optically centers on the row.
            val controls = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dpPx(ctx, 44)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dpPx(ctx, 22).toFloat()
                    setColor(0xB3000000.toInt())
                }
                setPadding(dpPx(ctx, 10), dpPx(ctx, 6), dpPx(ctx, 14), dpPx(ctx, 6))
                layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                    it.gravity = Gravity.BOTTOM
                    it.marginStart = dpPx(ctx, 24); it.marginEnd = dpPx(ctx, 24)
                    it.bottomMargin = dpPx(ctx, 16)
                }
            }
            val toggleIcon = Widgets.iconButton(ctx, R.drawable.ic_play, android.graphics.Color.WHITE,
                sizeDp = 32, iconSizeDp = 18).apply {
                layoutParams = LinearLayout.LayoutParams(dpPx(ctx, 32), dpPx(ctx, 32)).also {
                    it.marginEnd = dpPx(ctx, 6)
                }
            }
            // ── Pass-21 (the seek bar, rebuilt) ───────────────────────────────
            // LayerDrawable + ClipDrawable with setLayerInset — the API-21-safe
            // construction for a thick centered track (LayerDrawable.setLayerInset
            // exists since API 1; setLayerGravity needs 23, so the inset route it
            // is): a 4dp rounded rail, a same-height accent fill clipped to the
            // progress, and a 14dp thumb. The default hairline track + ~8dp
            // thumb was the entire "difficult to use and control" complaint.
            val seek = android.widget.SeekBar(ctx).apply {
                val trackH = dpPx(ctx, 4)
                val thumbR = dpPx(ctx, 7)
                val insetV = ((thumbR * 2) - trackH) / 2
                val rail = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpPx(ctx, 2).toFloat(); setColor(0x33FFFFFF.toInt())
                }
                val fill = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dpPx(ctx, 2).toFloat()
                    setColor(ContextCompat.getColor(ctx, R.color.accent_light))
                }
                val clipped = android.graphics.drawable.ClipDrawable(fill,
                    Gravity.START, android.graphics.drawable.ClipDrawable.HORIZONTAL)
                val layers = android.graphics.drawable.LayerDrawable(arrayOf(rail, clipped))
                layers.setLayerInset(0, 0, insetV, 0, insetV)
                layers.setLayerInset(1, 0, insetV, 0, insetV)
                layers.setId(0, android.R.id.background)
                layers.setId(1, android.R.id.progress)
                progressDrawable = layers
                thumb = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setSize(thumbR * 2, thumbR * 2)
                    setColor(ContextCompat.getColor(ctx, R.color.accent_light))
                }
                // The thumb reads as one solid accent dot; a touch-target this
                // size (>2x the 22dp minimum, and the whole 44dp row is the
                // effective hit band) makes pixel-accurate seeking comfortable.
                splitTrack = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).also {
                    it.marginEnd = dpPx(ctx, 10)
                }
            }
            val timeLabel = TextView(ctx).apply {
                textSize = 11.5f; setTextColor(0xE6FFFFFF.toInt())
                includeFontPadding = false
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            controls.addView(toggleIcon); controls.addView(seek); controls.addView(timeLabel)
            // ── Pass-29 Part 2 (#3B): the row is clickable so a tap on its ──
            // own background (between the toggle, the seek bar and the time
            // label) is consumed HERE rather than falling through to the
            // pager — aiming for the seek bar and landing 2dp short must
            // never toggle the preview chrome.
            controls.isClickable = true
            // ── Pass-24 (#4E: hold-and-drag seeking) ────────────────────────────
            // The control row lives inside a HORIZONTAL RecyclerView (the
            // one-page-per-item preview pager). Without this, the pager's
            // own interception stole every horizontal drag off the seek bar
            // the moment it passed the touch slop — a tap seeked fine but
            // press-and-hold-then-move never reached the SeekBar (the
            // reported "holding and moving does not work"). While a gesture
            // starts on the bar, the pager is asked not to intercept, so the
            // SeekBar's native drag (and the Pass-21 scrub semantics on top
            // of it) owns the whole gesture; the pager's own swipes are
            // untouched because they never begin on the bar.
            seek.setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> v.parent.requestDisallowInterceptTouchEvent(true)
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.parent.requestDisallowInterceptTouchEvent(false)
                }
                false   // never consume — the SeekBar's own handling must run
            }
            frame.addView(video); frame.addView(sp); frame.addView(bigPlay); frame.addView(er); frame.addView(controls)
            VideoVH(frame, video, bigPlay, sp, er, toggleIcon, seek, timeLabel, controls)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = items[position]
        if (holder is ImageVH) {
            holder.decodeJob?.cancel()
            holder.refineJob?.cancel()
            // Pass-24 (#4B): a fresh bind is a fresh photo — the recycled
            // cell's zoom/pan (and any settling zoom animation) must not
            // leak onto it.
            holder.img.animate().cancel()
            // Pass-26 (#2.1): the reporter attaches BEFORE the reset so the
            // bind itself reports fit scale — the overlay's "1×" control
            // disappears the moment a new (unzoomed) page binds, exactly in
            // step with the page it belongs to.
            holder.zoomReporter = onZoomScaleChanged
            holder.resetZoom()
            holder.error.visibility = View.GONE
            holder.spinner.visibility = View.VISIBLE
            holder.img.setImageDrawable(null)
            // Pass-22: a STRING tag — the old Int tag leaned on autobox
            // identity across holder rebinds; a stable string key makes the
            // recycled-cell guard unambiguous and cheap.
            holder.img.tag = "pv:$position"
            val ctx = holder.frame.context
            // ── Pass-38 (#2): the fit decode is sized for the page at rest ──
            // (the screen's own long edge, EXACTLY — see decodePreviewBitmap's
            // exact-target note). The refine below is the zoom half of the
            // quality contract: past REFINE_TRIGGER_ZOOM the page re-decodes
            // once at up to 1.4× the screen (never above the original, 8 MP
            // cap) and swaps the bitmap in — seamless by construction, since
            // the zoom transform lives on the view and a finer bitmap of the
            // same image renders at identical on-screen geometry.
            val maxDim = maxOf(ctx.resources.displayMetrics.widthPixels, ctx.resources.displayMetrics.heightPixels)
            holder.boundPosition = position
            holder.refined = false
            holder.refineRequest = {
                if (!holder.refined && holder.refineJob?.isActive != true) {
                    holder.refined = true   // claimed NOW: one refine per bind, no double-launch race
                    holder.refineJob = scope.launch {
                        val bmp = withContext(Dispatchers.IO) { decodeRefinedBitmap(ctx, item, maxDim) }
                        if (bmp != null && holder.img.tag == "pv:$position") {
                            holder.img.setImageBitmap(bmp)
                        }
                    }
                }
            }
            holder.decodeJob = scope.launch {
                // Roughly the screen's own pixel size — sharp at fit on every
                // device this app targets (EXACTLY the screen's long edge
                // since Pass-38, never a power-of-two undershoot), without
                // decoding a 12 MP original per tap. Off-main; cancelled on
                // recycle.
                val bmp = withContext(Dispatchers.IO) { decodePreviewBitmap(ctx, item, maxDim) }
                if (holder.img.tag == "pv:$position") {
                    holder.spinner.visibility = View.GONE
                    if (bmp != null) {
                        holder.img.setImageBitmap(bmp)
                        // The user zoomed while the fit was still decoding —
                        // the refine trigger never saw a drawable. Fire it now.
                        if (holder.zoomScale >= REFINE_TRIGGER_ZOOM) holder.refineRequest?.invoke()
                    } else holder.error.visibility = View.VISIBLE
                }
            }
        } else if (holder is VideoVH) {
            val ctx = holder.frame.context
            holder.boundItem = item
            // ── Pass-31 (#1): this holder is now LIVE at [position] — the ─────
            // page-leaving pause (pauseVideoPagesExcept) reads boundPosition
            // to know which videos may keep playing (only the current page's).
            holder.boundPosition = position
            liveVideoHolders.add(holder)
            // Pass-21: a cell recycled mid-scrub must come back neutral — the
            // ticker skips updates while scrubbing, so a stale flag would
            // freeze the new item's bar.
            holder.scrubbing = false
            // Pass-35 (#10): same neutrality for a pending seek pin — a new
            // binding starts from the player's real state, never the previous
            // clip's requested target.
            holder.pendingSeekMs = -1L; holder.pendingSeekTicks = 0
            // ── Pass-30 (#6): THE play/pause state painters, event-driven ──────
            // from the player's own state at every change point. The old
            // wiring optimistically painted whatever the tap handler INTENDED
            // (start() → play icon, pause() → pause icon) — but VideoView's
            // start()/pause() can no-op or throw (invalid state, seek past
            // the end, a not-yet-prepared player), and MediaPlayer state
            // changes complete ASYNCHRONOUSLY, so an optimistic icon could
            // sit on a paused video saying "playing" — the exact reported
            // unreliability. paintPlayState only ever paints a state read
            // FROM the media element; setBigPlayVisible keeps the disc's
            // playback-logical visibility in step with the preview chrome
            // (Pass-29 Part 2 #3B) so a completion can never flash the disc
            // over a hidden-chrome preview.
            fun paintPlayState(playing: Boolean) {
                if (holder.paintedPlaying == playing) return   // steady-state paints are free
                holder.paintedPlaying = playing
                holder.toggleIcon.setImageDrawable(Widgets.icon(ctx,
                    if (playing) R.drawable.ic_pause else R.drawable.ic_play, android.graphics.Color.WHITE))
            }
            fun setBigPlayVisible(want: Boolean) {
                holder.bigPlaySaved = if (want) View.VISIBLE else View.GONE
                if (chromeVisible) holder.bigPlay.visibility = holder.bigPlaySaved
            }
            // A fresh bind is a fresh paint — the guard starts unknown so the
            // first paint below always lands (never inherits the recycled
            // cell's icon).
            holder.paintedPlaying = null
            holder.error.visibility = View.GONE
            holder.spinner.visibility = View.VISIBLE
            setBigPlayVisible(true)
            holder.seek.progress = 0
            // ── Pass-32 (#3): the time label's width is RESERVED, never ──────
            // re-flowed. The label shows "elapsed / total"; elapsed grows in
            // digit count during playback ("0:59" → "1:00", "9:59" → "10:00"),
            // and because it sat wrap-content beside a weighted seek bar,
            // every growth stole width from the BAR — the reported "progress
            // bar becomes smaller when the time counter grows". The fix
            // reserves, once the duration is known, the width of the widest
            // string this label can ever show: "fmt(total) / fmt(total)" —
            // elapsed can never exceed total, and formatDuration's length is
            // monotonic in time, so no later tick or scrub preview can exceed
            // it. The bar's share of the row is then fixed for the whole clip
            // (short and long videos alike), and only the label's own text
            // re-centers inside its reserved box.
            holder.timeLabel.minWidth = 0
            holder.pinnedTimeTotalMs = 0L
            fun reserveTimeLabelWidth(totalMs: Long) {
                if (totalMs <= 0 || totalMs <= holder.pinnedTimeTotalMs) return
                val widest = "${formatDuration(totalMs)} / ${formatDuration(totalMs)}"
                val w = Math.ceil(holder.timeLabel.paint.measureText(widest).toDouble()).toInt()
                if (w > holder.timeLabel.minWidth) holder.timeLabel.minWidth = w
                holder.pinnedTimeTotalMs = totalMs
            }
            holder.timeLabel.text = formatDuration(item.durationMs)
            reserveTimeLabelWidth(item.durationMs)   // MediaStore already knows the duration for most clips
            paintPlayState(false)
            // A recycled cell's previous MediaPlayer must never leak into the
            // next item — stop it before pointing the view at new media.
            try { holder.video.stopPlayback() } catch (e: Exception) { /* fresh view */ }
            // ── Pass-22 (#1A, the video half of the same fix): the URI is the ──
            // proven-readable address (same reasoning as decodePreviewBitmap);
            // the raw path stays as the fallback for any URI a player refuses.
            try {
                holder.video.setVideoURI(item.uri)
            } catch (e: Exception) {
                if (item.path != null) holder.video.setVideoPath(item.path!!)
            }
            holder.video.setOnPreparedListener { mp ->
                holder.spinner.visibility = View.GONE
                holder.seek.max = maxOf(1, mp.duration)
                // Pass-22 (#1C): the bar and label both start from a provably
                // zero position at prepare — never a stale recycled value.
                holder.seek.progress = 0
                holder.timeLabel.text = "0:00 / ${formatDuration(mp.duration.toLong())}"
                // Pass-32 (#3): the player's duration is authoritative now —
                // lock the label's reservation to it before the first tick
                // can ever grow the text (some providers report 0 until the
                // first play; the ticker re-tries below).
                reserveTimeLabelWidth(mp.duration.toLong())
                // Pass-30 (#6): VideoView fires a pre-armed start() BEFORE
                // forwarding this event, so the player's own isPlaying is the
                // honest first paint (true when a tap-while-loading already
                // armed playback, false for a plain load).
                paintPlayState(mp.isPlaying)
            }
            holder.video.setOnCompletionListener {
                setBigPlayVisible(true)
                paintPlayState(false)
                holder.pendingSeekMs = -1L   // Pass-35 (#10): playback ended — no target to converge on
                holder.seek.progress = 0
            }
            holder.video.setOnErrorListener { _, _, _ ->
                holder.spinner.visibility = View.GONE
                setBigPlayVisible(false)
                holder.error.visibility = View.VISIBLE
                holder.error.text = "This video can't be played on this device"
                paintPlayState(false)
                true
            }
            // Pass-30 (#6): buffering/loading surfaces honestly in the
            // existing spinner — the media element reports the transitions
            // itself, so the icon never has to guess.
            holder.video.setOnInfoListener { _, what, _ ->
                when (what) {
                    android.media.MediaPlayer.MEDIA_INFO_BUFFERING_START -> holder.spinner.visibility = View.VISIBLE
                    android.media.MediaPlayer.MEDIA_INFO_BUFFERING_END -> holder.spinner.visibility = View.GONE
                }
                false   // unhandled codes pass through untouched
            }
            holder.bigPlay.setOnClickListener {
                setBigPlayVisible(false)
                try { holder.video.start() } catch (e: Exception) { /* the reconcile below paints the truth */ }
                // The player's OWN answer — a start() that couldn't engage
                // (invalid state) leaves the icon honestly on play.
                paintPlayState(holder.video.isPlaying)
            }
            // Pass-30 (#6): STATELESS toggle — each tap branches on the LIVE
            // isPlaying and then paints whatever the player actually did.
            // Repeated rapid taps can never desynchronize the icon: there is
            // no toggled flag to invert; every tap and every ticker tick
            // converge on the media element's real state.
            holder.toggleIcon.setOnClickListener {
                try {
                    if (holder.video.isPlaying) holder.video.pause() else holder.video.start()
                } catch (e: Exception) { /* invalid player — reconcile below */ }
                paintPlayState(holder.video.isPlaying)
            }
            holder.seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                // ── Pass-21 (scrub semantics — the usable seek bar) ───────────
                // The old listener seekTo()'d on EVERY drag pixel, which made
                // the player stutter under a flood of seeks and the bar fight
                // the user's finger — the reported "difficult to use and
                // control". The standard scrub pattern instead: while the
                // finger is down the thumb is the user's, the time label
                // previews the landing position, and exactly ONE seekTo fires
                // on release.
                override fun onProgressChanged(sb: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
                    // Pass-22 (#1C): only preview a landing time the player can
                    // actually stand behind — a scrub before onPrepared (duration
                    // still 0) reads as the honest "0:00".
                    // ── Pass-24 (#4D): the "→" prefix that used to decorate ──
                    // the preview read as a stray arrow popping up beside the
                    // progress bar on every tap/scrub (the reported "a small
                    // arrow appears on its right side"). The position preview
                    // itself stays — it is useful feedback — shown plainly.
                    if (fromUser && holder.scrubbing) {
                        val total = holder.video.duration
                        holder.timeLabel.text = if (total > 0)
                            "${formatDuration(progress.toLong())} / ${formatDuration(total.toLong())}"
                        else formatDuration(progress.toLong())
                    }
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {
                    holder.scrubbing = true
                    holder.progressJob?.cancel()
                }
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {
                    holder.scrubbing = false
                    val target = sb?.progress ?: 0
                    // ── Pass-35 (#10): pin the REQUESTED position before the ──
                    // async seekTo, so the ticker below can never read the
                    // player's still-stale pre-seek position and snap the UI
                    // back ("46s → 40s").
                    holder.pendingSeekMs = target.toLong()
                    holder.pendingSeekTicks = 0
                    try { holder.video.seekTo(target) } catch (e: Exception) { /* stale player */ }
                    holder.relaunchTicker?.invoke()
                }
            })
            onVideoActive(holder.video)
            fun launchTicker() {
                holder.progressJob?.cancel()
                holder.progressJob = scope.launch {
                    // Plain infinite loop: delay() is the cancellation point —
                    // the Job's cancel (recycle / preview close / sheet dismiss
                    // / a scrub starting) throws out of it and the loop ends.
                    while (true) {
                        delay(250)
                        if (holder.scrubbing) continue   // the thumb is the user's while scrubbing
                        // ── Pass-30 (#6): the convergence pass. Every explicit ──
                        // state change above already paints, but MediaPlayer
                        // also changes state in ways no button press produced
                        // (a seek landing past the end, an async pause/seek
                        // completing, an error mid-play, surface churn). One
                        // cheap paintPlayState(video.isPlaying) per tick — a
                        // free no-op whenever nothing drifted — guarantees the
                        // icon converges on the media element's REAL state
                        // within 250ms of ANY transition, no matter its origin.
                        paintPlayState(holder.video.isPlaying)
                        // ── Pass-35 (#10): the position the UI shows this ──
                        // tick — the pinned REQUESTED target while a seek is
                        // pending and the player hasn't converged on it yet
                        // (the stale pre-seek currentPosition is exactly the
                        // "jumps back to 40s" the old ticker painted), the
                        // player's real position once it has (or after a
                        // bounded number of non-converging ticks).
                        var posMs = holder.video.currentPosition.toLong()
                        if (holder.pendingSeekMs >= 0) {
                            val pending = holder.pendingSeekMs
                            val converged = kotlin.math.abs(posMs - pending) <= 250L
                            holder.pendingSeekTicks++
                            if (converged || holder.pendingSeekTicks > 12) {
                                holder.pendingSeekMs = -1L
                                if (converged) posMs = pending.coerceAtLeast(0L)
                            } else {
                                posMs = pending.coerceAtLeast(0L)
                            }
                        }
                        if (!holder.video.isPlaying && holder.pendingSeekMs < 0) continue
                        holder.seek.progress = posMs.toInt().coerceIn(0, holder.seek.max)
                        val total = holder.video.duration
                        // Pass-32 (#3): covers a player whose duration only
                        // becomes honest after prepare (streams, odd codecs)
                        // — idempotent, and never shrinks a reservation.
                        reserveTimeLabelWidth(total.toLong())
                        holder.timeLabel.text = "${formatDuration(posMs)} / ${formatDuration(total.toLong())}"
                    }
                }
            }
            holder.relaunchTicker = { launchTicker() }
            launchTicker()
            // ── Pass-29 Part 2 (#3B): a cell binding while the chrome is away ──
            // starts in the hidden chrome state — a recycled page never
            // flashes a control row over a dismissed-chrome preview.
            if (!chromeVisible) applyChromeToHolder(holder, false, false)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        if (holder is ImageVH) {
            holder.decodeJob?.cancel(); holder.decodeJob = null
            holder.refineJob?.cancel(); holder.refineJob = null
            holder.refineRequest = null
            holder.refined = false
            holder.boundPosition = -1
            holder.img.animate().cancel()
            holder.resetZoom()
            // Pass-26 (#2.1): a recycled cell reports nothing — the overlay's
            // control must follow the page that binds next, not a dead one.
            holder.zoomReporter = null
            // ── Pass-38 (#2, memory hygiene): a recycled cell keeps its last ──
            // bitmap until the next bind reuses the view — drop it now so the
            // recycler pool never pins a fit bitmap, and never a refined one,
            // behind an off-screen holder.
            holder.img.setImageDrawable(null)
        } else if (holder is VideoVH) {
            holder.progressJob?.cancel(); holder.progressJob = null
            holder.scrubbing = false; holder.relaunchTicker = null
            try { holder.video.stopPlayback() } catch (e: Exception) { /* already gone */ }
            holder.boundItem = null
            // Pass-31 (#1): the holder is dead — leave the live registry so
            // no future page-change pause can ever touch a released player.
            holder.boundPosition = -1
            liveVideoHolders.remove(holder)
            onVideoInactive(holder.video)
        }
    }

    override fun getItemCount() = items.size

    private fun dpPx(ctx: android.content.Context, n: Int): Int = (n * ctx.resources.displayMetrics.density).toInt()
}

// ── Pass-21 (the preview filmstrip) ───────────────────────────────────────────
// A dedicated navigation bar for MULTI-ITEM previews: a horizontally scrolling
// strip of small rounded thumbnails under the pager, the current item ringed in
// accent, tap to jump. It makes jumping to a specific item O(1) instead of N
// swipes, sits at ONE fixed position regardless of whether the current page is
// an image or a video (clear of the video control row), and only exists when
// there is more than one item to navigate — a single-item preview stays the
// clean full-bleed view. Thumbnails ride the same ThumbnailEngine + tag-guard
// + cancel-on-recycle pipeline as the picker grids (never a full-res decode
// per tile), so a 340-item selection scrolls as lightly as a 5-item one.
private class PreviewFilmstripAdapter(
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val items: List<MediaLibraryItem>,
    private val cellDp: Int,
    private val onTap: (Int) -> Unit,
) : RecyclerView.Adapter<PreviewFilmstripAdapter.VH>() {

    init { setHasStableIds(true) }

    // ── Pass-22 (THE build-killer, filmstrip half) ──────────────────────────
    // Same root as the pager holders: this VH declared no supertype, so
    // Adapter<VH> itself was ill-typed ("Type argument is not within its
    // bounds") and the strip could never exist. Extends ViewHolder now; not
    // private because the (file-private) adapter's generic signature exposes
    // it — Kotlin requires the nested type to be at least as visible as the
    // declaration that names it.
    class VH(val frame: FrameLayout, val img: ImageView, val ring: View,
             val playMark: ImageView) : RecyclerView.ViewHolder(frame) {
        var decodeJob: kotlinx.coroutines.Job? = null
    }

    private var currentPos = -1

    /** Highlights [pos] and nothing else — a position change rebinds exactly
     *  the two tiles whose ring state flipped, never the whole strip. */
    fun setCurrent(pos: Int) {
        if (pos == currentPos || pos !in items.indices) return
        val old = currentPos
        currentPos = pos
        if (old in items.indices) notifyItemChanged(old)
        notifyItemChanged(pos)
    }

    override fun getItemId(position: Int): Long = items[position].id

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val frame = FrameLayout(ctx).apply {
            layoutParams = RecyclerView.LayoutParams(ctx.dp(cellDp), ctx.dp(cellDp)).also {
                it.marginEnd = ctx.dp(6)
            }
        }
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_thumb_small)
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, ctx.dp(8).toFloat())
                }
            }
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val ring = View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = ctx.dp(8).toFloat()
                setStroke(ctx.dp(2), ContextCompat.getColor(ctx, R.color.accent_light))
            }
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val playMark = ImageView(ctx).apply {
            setImageDrawable(Widgets.icon(ctx, R.drawable.ic_play, android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF)))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(0x66000000.toInt())
            }
            setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ctx.dp(18), ctx.dp(18)).also {
                it.gravity = Gravity.CENTER
            }
        }
        frame.addView(img); frame.addView(ring); frame.addView(playMark)
        return VH(frame, img, ring, playMark)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ctx = holder.frame.context
        val item = items[position]
        holder.img.tag = item.id
        holder.img.setImageDrawable(null)
        holder.ring.visibility = if (position == currentPos) View.VISIBLE else View.GONE
        holder.playMark.visibility = if (item.isVideo) View.VISIBLE else View.GONE
        holder.frame.setOnClickListener { onTap(position) }
        val localPath = item.path
        holder.decodeJob?.cancel()
        holder.decodeJob = scope.launch {
            val bmp = withContext(kotlinx.coroutines.Dispatchers.IO) {
                if (localPath != null && File(localPath).exists()) {
                    ThumbnailEngine.load(ctx, localPath, ThumbnailEngine.categoryOf(localPath.substringAfterLast('.')), cellDp)
                } else {
                    ThumbnailEngine.loadFromUri(ctx, item.uri, item.isVideo, cellDp)
                }
            }
            if (bmp != null && holder.img.tag == item.id) holder.img.setImageBitmap(bmp)
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.decodeJob?.cancel(); holder.decodeJob = null
    }

    override fun getItemCount() = items.size
}

/** Pass-20: memory-bounded, EXIF-aware preview decode. Decodes at the
 *  smallest power-of-two sample that still covers [maxDimPx] on the long
 *  edge (the standard inSampleSize ladder — never a full 12 MP original
 *  for a screen-sized preview), then applies the JPEG orientation tag so
 *  rotated camera photos preview upright. Local paths read via the file
 *  system (with android.media.ExifInterface(path), every API level);
 *  content URIs read via the resolver, with the stream-based ExifInterface
 *  only where it exists (API 24+) — below that a URI-backed photo simply
 *  previews without the (rare, path-less) orientation correction rather
 *  than crashing. Any failure answers null and the cell shows its honest
 *  error line. */
private fun decodePreviewBitmap(ctx: android.content.Context, item: MediaLibraryItem, maxDimPx: Int): android.graphics.Bitmap? {
    // ── Pass-22 (#1A — the actual image-loading mechanism) ────────────────────
    // Pass-20/21 read the raw FILE PATH first and only fell back to the
    // content URI when the path was absent. That is the exact inverse of what
    // every working media surface in this app does: the grid thumbnails (the
    // ones that DO appear, on every device) decode through
    // ContentResolver.openInputStream(item.uri) — MediaStore's own permission
    // model — while direct File(path) reads are the fragile half (scoped-
    // storage paths that exist but deny reads, OEM variants, revoked
    // all-files grants: File.exists() can be true while the open throws
    // EACCES, and then EVERY preview cell decoded to null and rendered as a
    // blank page with no error text). The preview now uses the same
    // URI-stream-first chain the thumbnails already proved, with the raw
    // path kept only as a secondary fallback, and any total failure answers
    // null so the cell states its honest "Couldn't load this item" line.
    val hasPath = item.path != null && File(item.path).exists()
    fun openStream(): java.io.InputStream? = try {
        ctx.contentResolver.openInputStream(item.uri)
            ?: item.path?.let { p -> if (File(p).exists()) File(p).inputStream() else null }
    } catch (e: Exception) {
        try {
            if (hasPath) File(item.path!!).inputStream() else null
        } catch (e2: Exception) { null }
    }
    return try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // ── Pass-23 (the actual "Couldn't load this item" root cause) ───────────
        // The bounds pass used to be written as
        //     openStream()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        // decodeStream() with inJustDecodeBounds=true returns null BY DESIGN (it
        // only fills the Options bounds and answers null — see the identical,
        // working bounds pass in ThumbnailEngine.loadFromUri, which correctly
        // discards the return value). Chaining `?: return null` onto it made
        // EVERY preview decode bail out right here — the spinner hid, the
        // honest error label appeared, and no image ever rendered, regardless
        // of URI or path readability. The stream-availability check and the
        // decode result are now separated: a null STREAM is a genuine failure;
        // a null decode RESULT is the expected bounds-pass outcome, and the
        // outWidth/outHeight check below is the real readability verdict.
        val boundsStream = openStream() ?: return null
        try {
            boundsStream.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: Exception) { return null }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // ── Pass-38 (#2): EXACT-target decoding. The old power-of-two loop ─────
        // stopped at the first sample whose long edge fit the target, which
        // could land up to 2× BELOW it — a 2600px photo on a 2340px screen
        // decoded at 1300px and was upscaled back at fit scale, visibly soft
        // ("previews look slightly lower quality"). The pow2 sample now only
        // gets CLOSE (never above 2× the target), and the decode lands on the
        // exact target through BitmapFactory's density scaling — one filtered
        // pass, one allocation, no intermediate bitmap, no undershoot. Images
        // already at or below the target decode at native size (never
        // upscaled, never re-filtered).
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxDimPx * 2) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            val sampledLong = maxOf(bounds.outWidth, bounds.outHeight) / sample
            if (sampledLong > maxDimPx) {
                inScaled = true
                inDensity = sampledLong       // the sampled pixels, treated as the "source density"…
                inTargetDensity = maxDimPx    // …so the decoder scales to exactly the target in one pass
            } else {
                inScaled = false
            }
        }
        val bmp = openStream()?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) } ?: return null
        val rotation = when {
            hasPath -> try {
                android.media.ExifInterface(item.path!!)
                    .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
            } catch (e: Exception) { android.media.ExifInterface.ORIENTATION_NORMAL }
            android.os.Build.VERSION.SDK_INT >= 24 -> try {
                ctx.contentResolver.openInputStream(item.uri)?.use {
                    android.media.ExifInterface(it)
                        .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)
                } ?: android.media.ExifInterface.ORIENTATION_NORMAL
            } catch (e: Exception) { android.media.ExifInterface.ORIENTATION_NORMAL }
            else -> android.media.ExifInterface.ORIENTATION_NORMAL
        }
        val degrees = when (rotation) {
            android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        if (degrees != 0f) {
            val m = android.graphics.Matrix().apply { postRotate(degrees) }
            try {
                android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            } catch (e: Exception) { bmp }
        } else bmp
    } catch (e: Exception) { null }
}

// ── Pass-38 (#2): the zoom-refine decode ─────────────────────────────────────
// The fit decode is sized for the page at rest (exact screen long edge — sharp
// at fit on every device, light enough for the pager's cached neighbors). The
// moment the user actually zooms past ~1.3×, the page re-decodes ONCE at a
// sharper target and swaps the bitmap in seamlessly — the zoom transform lives
// on the VIEW, so a higher-resolution bitmap of the same image renders at the
// identical on-screen geometry, just with real pixels under the pinch. The
// target: 1.4× the screen's own long edge, never above the ORIGINAL size
// (never an upscale), and hard-capped at 8 MP (32MB) — largeHeap-safe, and
// proportionally smaller on the low-res screens of the oldest devices. When
// the original offers nothing over the fit decode, the answer is null and the
// zoom simply keeps the fit bitmap (already exact).
private fun decodeRefinedBitmap(ctx: android.content.Context, item: MediaLibraryItem, fitDimPx: Int): android.graphics.Bitmap? {
    return try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val hasPath = item.path != null && File(item.path).exists()
        fun openStream(): java.io.InputStream? = try {
            ctx.contentResolver.openInputStream(item.uri)
                ?: item.path?.let { p -> if (File(p).exists()) File(p).inputStream() else null }
        } catch (e: Exception) {
            try { if (hasPath) File(item.path!!).inputStream() else null } catch (e2: Exception) { null }
        }
        val s = openStream() ?: return null
        try { s.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) } } catch (e: Exception) { return null }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val origLong = maxOf(bounds.outWidth, bounds.outHeight)
        if (origLong <= fitDimPx) return null   // the original IS the fit decode — nothing finer exists
        var target = minOf(origLong, fitDimPx + fitDimPx * 2 / 5)
        val longShort = origLong.toFloat() / maxOf(1, minOf(bounds.outWidth, bounds.outHeight))
        val maxLong = kotlin.math.sqrt(8_000_000f * longShort)
        if (target > maxLong) target = maxLong.toInt()
        if (target <= fitDimPx) return null
        decodePreviewBitmap(ctx, item, target)
    } catch (e: Exception) { null }
}

private class GridSpacingDecoration(private val spanCount: Int, private val spacingPx: Int) : RecyclerView.ItemDecoration() {
    override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
        val position = parent.getChildAdapterPosition(view)
        if (position < 0) return
        val column = position % spanCount
        outRect.left = column * spacingPx / spanCount
        outRect.right = spacingPx - (column + 1) * spacingPx / spanCount
        if (position < spanCount) outRect.top = spacingPx
        outRect.bottom = spacingPx
    }
}

private fun pillBg(ctx: android.content.Context): android.graphics.drawable.Drawable {
    val d = android.graphics.drawable.GradientDrawable()
    d.shape = android.graphics.drawable.GradientDrawable.RECTANGLE
    d.cornerRadius = ctx.dpf(5f)
    d.setColor(android.graphics.Color.parseColor("#B3000000"))
    return d
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

// GOAT v2 (item 1 — album cards / aggregate size): same format/thresholds
// as SendFragment.fmtBytes and TransfersFragment.fmtBytes -- both private
// to their own class, so duplicated here rather than shared, but kept
// letter-for-letter identical so a size reads the same everywhere in the
// app regardless of which screen shows it.
private fun fmtBytes(bytes: Long): String = when {
    bytes < 0 -> "?"
    bytes < 1024 -> "${bytes} B"
    bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
    bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
    else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
}

// §5 (Reliability pass — long-press TOGGLE + drag): shared by both grids in
// the Media Selector, the App Picker sheet's grid (SendFragment), and — since
// Pass-39 (#1) — the Files sheet's and the Music sheet's lists. ONE
// implementation of the hold-and-slide multi-selection gesture everywhere,
// never a re-invented near-copy.
//
// A long-press arms drag mode for the rest of that one continuous touch.
// While armed, the item physically under the finger is re-resolved on every
// move (via [RecyclerView.findChildViewUnder], never by stepping an index)
// so a fast, diagonal drag can't skip past a row boundary and lose track of
// what's underneath it; each crossed item receives the SAME captured mode,
// so the gesture can never half-select, half-deselect. A plain tap remains
// the other (per-item) toggle path — each surface's own click behavior —
// unaffected by this mechanic.
//
// ── Pass-39 (#1): pointer-change safety + [initiatingToggle]. ──────────────
//
//   • POINTER-CHANGE SAFETY. A second finger landing, or the initiating
//     finger lifting mid-drag, ends the gesture cleanly right there — no
//     jump to a stranger's coordinates, no stray applies, and the terminal
//     event is consumed so no child turns it into a click.
//
//   • [initiatingToggle]. The Media/App grids arm AND toggle the initiating
//     cell (their cells have no long-press of their own — the historic
//     behavior, kept byte-for-byte). The Files/Music lists keep their own
//     long-press (the range-selection anchor): there the drag only ARMS,
//     and the mode is captured from the initiating row's state at
//     ACTION_DOWN — BEFORE the child's own long-press handler runs and
//     selects it — so "hold an unselected row, slide" is reliably an
//     ADD pass regardless of handler ordering.
//
// ── Pass-40 (#5): EDGE AUTO-SCROLL REMOVED ENTIRELY (historical note) — ────
// the Pass-39 edge-zone scroll loop was deleted after on-device reports that
// a hold started on the FIRST or LAST row (the finger already resting at
// the list's edge) kicked an unintended auto-scroll that moved the whole
// list under the finger — selecting a run of items the person never crossed.
// ── GOAT-PASS 7 (§3D): edge auto-scroll is back, redesigned around exactly ──
// that failure: the scroller arms only after the finger has GENUINELY MOVED
// from its DOWN position (a resting long-press never scrolls), drives from
// the LIVE finger position each animation frame, re-applies selection under
// the stationary finger as content scrolls past it, and dies the same frame
// the gesture ends. It is OPT-IN per surface (the list surfaces only — see
// edgeAutoScroll below); every surface that does not enable it keeps the
// Pass-40 behavior byte-for-byte (selection exactly on crossed rows,
// nothing else moves).
//
// ── GOAT-PASS 7 (§3C — the endpoint-deselection bug, root fix): the old ──────
// applyUnder resolved the finger's target ONLY through findChildViewUnder,
// which returns null inside the inter-row/card margins — the finger could
// slide across a range and RELEASE inside a gap (or just past the final row's
// edge), leaving the final item never applied: the exact "the final item
// where the finger is released sometimes remains selected" report. Two
// coordinated changes close it:
//   • positionUnder() — a gap-tolerant resolver: the direct child first, then
//     the NEAREST child within a small tolerance band beyond its bounds
//     (covers card margins, list padding rims), chosen by real distance so a
//     point in a gap resolves to the row the finger is visually ON.
//   • the terminal ACTION_UP performs one FINAL apply of the resolved
//     position, ignoring the "already applied" dedupe guard — every surface's
//     onApply is a SET-state operation (idempotent by contract), so the
//     re-apply can never double-toggle; it only guarantees the endpoint of
//     the gesture is exactly as included as every row crossed on the way.
// ── GOAT-PASS 7 (§3D — professional edge auto-scroll, opt-in): while an armed
// drag holds the finger near the list's top/bottom rim, the list scrolls by
// itself so the selection can continue past the visible area. Speed scales
// with proximity (a finger deep in the rim scrolls fast, one at the zone's
// edge barely moves); the scroller only arms once the finger has genuinely
// MOVED since DOWN (a long-press resting on the first/last row — the exact
// shape the old Pass-40 auto-scroll botched — never scrolls anything); it
// stops the frame the gesture ends, cancels, or a second finger lands; and
// every scrolled frame re-applies the selection under the (stationary)
// finger, so rows revealed by the scroll select exactly as if crossed. Opt-in
// via [edgeAutoScroll] — enabled for the LIST surfaces (Files sheet, Music
// sheet, Received tab, History tab); the grid surfaces keep their exact
// pre-existing gesture behavior.
/**
 * ── GOAT-PASS 8 (§4/§14) — the shared hold-and-slide gesture, mode-aware. ──
 *
 * [slideEnabled] is read LIVE at gesture time: when it answers false
 * (Range Selection — the default) the long-press NEVER arms the drag — the
 * row's own long-press (the range anchor machinery) owns the whole
 * interaction — and the terminal UP is still consumed so the row's click
 * path can never fire under a completed long-press. When it answers true
 * (Hold & Slide Selection) the gesture behaves exactly as it always has.
 * The two systems therefore can never activate simultaneously, and a mode
 * switch takes effect on the very next touch.
 *
 * [hitExcluded], when supplied, marks hit-regions that must NEVER resolve
 * to a row position — the Received tab passes the expanded folder box's
 * scroll area, so a press/hold inside an expanded folder's content can
 * neither arm the drag against the parent folder row nor apply a
 * selection to it (the GOAT-PASS 8 §14 accidental-selection fix).
 *
 * ── SELECTION FINAL PASS 1 (§3 — the gesture-end hook): [onGestureEnd] ──
 * runs EXACTLY ONCE when an armed drag finishes (finger lift, cancel, or a
 * second pointer landing) — the single point where a surface's heavier
 * full reconciliation (its surgical scan, its exact footer settle) belongs.
 * Per-row applies stay immediate and cheap; the O(list) work that used to
 * run per crossed cell now runs once per gesture, which is what keeps a
 * fast slide across a large list from falling behind the finger.
 */
internal fun attachDragSelect(recyclerView: RecyclerView,
                              isSelectedAt: (Int) -> Boolean,
                              onApply: (position: Int, add: Boolean) -> Unit,
                              edgeAutoScroll: Boolean = false,
                              slideEnabled: () -> Boolean = { false },
                              hitExcluded: (View) -> Boolean = { false },
                              onGestureEnd: () -> Unit = {}) {
    // GOAT-PASS 11 (§12): the Hold & Slide arm delay — 440 ms, sixty
    // milliseconds under the platform's 500 ms long-press timeout. Enough
    // to feel snappier on the initiating row, far enough above ordinary
    // tap/scroll durations that nothing accidental arms (movement beyond
    // the touch slop still cancels it outright).
    // ── FINAL PASS 2 (§8 — the arm must precede the PLATFORM'S OWN timeout, ──
    // not an assumed 500 ms): ViewConfiguration's real long-press timeout is
    // 400 ms on most current devices, so the hard-coded 440 armed AFTER the
    // row's own platform long-press had already fired — two owners racing on
    // exactly the gesture this handler exists to own. The arm delay is now
    // derived from the device's actual timeout (minus the same 60 ms head
    // start), clamped to [200, 440] so nothing pathological arms absurdly
    // early while every device keeps the arm strictly first.
    val platformLongPressMs = try {
        android.view.ViewConfiguration.getLongPressTimeout().toLong()
    } catch (e: Exception) { 500L }
    val HOLD_SLIDE_ARM_MS = 440L.coerceAtMost(platformLongPressMs - 60L).coerceAtLeast(200L)
    val density = recyclerView.resources.displayMetrics.density
    // Gap tolerance: how far beyond a child's real bounds a touch point may
    // sit and still resolve to that child (card margins + a little reach).
    val gapToleranceXPx = 20f * density
    val gapToleranceYPx = 14f * density
    // Edge auto-scroll tuning (all in dp, converted once).
    val edgeZonePx = 84f * density          // how far into the rim the zone reaches
    val edgeMinSpeedPx = 140f * density     // dp/s at the zone's inner boundary
    // ── GOAT-PASS 9 (§4): the maximum auto-scroll speed is materially ──
    // higher (2600 dp/s, up from 1250) — long lists must be reachable at a ─
    // genuinely fast rate — and the ramp is now a smooth quadratic curve
    // (proximity²) instead of linear: gentle near the zone's inner boundary,
    // accelerating progressively toward the rim, with no sudden speed jumps
    // anywhere on the curve.
    val edgeMaxSpeedPx = 2600f * density    // dp/s hard against (or beyond) the list's edge
    val moveArmThresholdPx = 12f * density  // finger must travel this far before the scroller may arm

    var dragging = false
    var dragAdd = true
    var lastPosition = RecyclerView.NO_POSITION
    // ── FULL FINAL PASS 2 (§8): the point at which the last row was applied ──
    // — the interpolation origin for applyUnder's swept-span walk (see its
    // own note). Maintained alongside lastPosition; reset with the gesture.
    var lastAppliedX = 0f
    var lastAppliedY = 0f
    // ── GOAT-PASS 8 (§4): set the moment the detector sees ANY long-press —
    // in Range mode the drag never arms, but the terminal UP/CANCEL must
    // still be consumed so the row's own click handler can never fire under
    // a completed long-press (exactly the suppression the armed drag always
    // provided). Reset on every DOWN.
    var longPressSeen = false
    // The initiating row's selection state, captured at ACTION_DOWN (see the
    // header note: order-proof against the child's own long-press select).
    var selectedAtDown = false
    // Live finger coordinates + the DOWN origin (edge-scroll arming + the
    // apply-under-finger the scroller needs for a stationary finger).
    var lastX = 0f
    var lastY = 0f
    var downX = 0f
    var downY = 0f
    var movedBeyondArmThreshold = false

    // ── GOAT-PASS 8 (§14): the hit-test helpers come FIRST (locals are ────
    // strictly before-use) — the deepest-descendant resolver and the exclusion
    // walk that positionUnder and the long-press both consult.

    /** Whether [child]'s bounds contain (px, py) given its parent's scroll
     *  offsets — View.hasHitInParent semantics without the framework
     *  dependency on hidden APIs. */
    fun View.hasHitWithin(px: Float, py: Float, parent: ViewGroup): Boolean {
        val cx = px - parent.scrollX
        val cy = py - parent.scrollY
        return cx >= left && cx < right && cy >= top && cy < bottom
    }
    /** The deepest descendant of [card] whose bounds contain the point
     *  (translated from the RecyclerView's coordinate space). Children are
     *  visited in REVERSE drawing order so overlays sitting on top win,
     *  matching how dispatchTouchEvent resolves the target. */
    fun deepestHitIn(card: View, x: Float, y: Float): View? {
        val localX = x - card.left + card.scrollX
        val localY = y - card.top + card.scrollY
        if (localX < 0f || localY < 0f || localX >= card.width.toFloat() || localY >= card.height.toFloat()) return null
        if (card is ViewGroup) {
            for (i in card.childCount - 1 downTo 0) {
                val c = card.getChildAt(i)
                if (c.visibility != View.VISIBLE) continue
                if (!c.hasHitWithin(localX, localY, card)) continue
                deepestHitIn(c, localX, localY)?.let { return it }
            }
        }
        return card
    }


    /** §14: does the point (in the RecyclerView's coordinates) land inside
     *  [card]'s subtree on a view whose ancestor chain carries the
     *  [hitExcluded] mark? Cheap by construction — a row card has a handful
     *  of children, and the walk stops at the first marked ancestor. */
    fun hitInsideExclusion(card: View, x: Float, y: Float): Boolean {
        // Fast bail: nothing is ever excluded when no predicate was supplied.
        val deepest = deepestHitIn(card, x, y) ?: return false
        var v: View? = deepest
        while (v != null && v !== recyclerView) {
            if (hitExcluded(v)) return true
            v = v.parent as? View
        }
        return false
    }

    // ── §3C: the gap-tolerant position resolver (see the header comment). ─────
    fun positionUnder(rv: RecyclerView, x: Float, y: Float): Int {
        val direct = rv.findChildViewUnder(x, y)
        if (direct != null) {
            // ── GOAT-PASS 8 (§14): a hit inside a marked exclusion region ──
            // (the expanded folder box's scroll area on the Received tab)
            // resolves to NO position at all — the parent row's selection
            // handler must never see a press that landed on the expanded
            // content. The walk finds the deepest descendant under the
            // point and checks its ancestor chain up to (and including) the
            // row card itself.
            if (hitInsideExclusion(direct, x, y)) return RecyclerView.NO_POSITION
            return rv.getChildAdapterPosition(direct)
        }
        var bestPos = RecyclerView.NO_POSITION
        var bestDist = java.lang.Float.MAX_VALUE
        for (i in 0 until rv.childCount) {
            val c = rv.getChildAt(i)
            if (hitInsideExclusion(c, x, y)) continue
            val dx = maxOf(c.left - x, x - c.right, 0f)
            val dy = maxOf(c.top - y, y - c.bottom, 0f)
            if (dx > gapToleranceXPx || dy > gapToleranceYPx) continue
            val dist = dx + dy
            if (dist < bestDist) {
                bestDist = dist
                bestPos = rv.getChildAdapterPosition(c)
            }
        }
        return bestPos
    }

    fun applyUnder(x: Float, y: Float, includeEndpoint: Boolean = false) {
        val pos = positionUnder(recyclerView, x, y)
        if (pos == RecyclerView.NO_POSITION) return
        if (includeEndpoint || pos != lastPosition) {
            // ── FULL FINAL PASS 2 (§8 — the swept span is never lost): ──
            // applyUnder used to resolve ONLY the finger's current point.
            // Two consecutive ACTION_MOVE events can sit several rows apart
            // on a fast slide (input batching, a frame drop, the very first
            // movement right after the arm fires) — everything strictly
            // between them was silently skipped, the exact "the initial
            // movement is missed / items are skipped during fast movement"
            // report. The fix walks the STRAIGHT SEGMENT from the previously
            // APPLIED point to the current one, sampling it finer than half
            // the smallest visible cell, and applies every distinct row the
            // segment resolves to — in a list that is exactly the rows
            // crossed; in a grid it is exactly the cells the diagonal
            // geometrically passes through (never the whole bounding
            // rectangle, so no un-crossed cell is ever selected). Idempotent
            // SET operations + the lastPosition dedupe keep any sampled
            // repeat a no-op, and the endpoint apply is forced last so the
            // terminal UP's includeEndpoint contract is preserved.
            val px = lastAppliedX; val py = lastAppliedY
            val havePrev = lastPosition != RecyclerView.NO_POSITION
            if (havePrev && (px != x || py != y)) {
                val dx = x - px; val dy = y - py
                val dist = Math.sqrt((dx * dx + dy * dy).toDouble()).toFloat()
                // Sample step: half the smallest visible child dimension
                // (clamped to something sane) — no cell can be stepped over.
                var stepPx = java.lang.Float.MAX_VALUE
                for (i in 0 until recyclerView.childCount) {
                    val c = recyclerView.getChildAt(i)
                    if (c.width > 0) stepPx = minOf(stepPx, c.width.toFloat())
                    if (c.height > 0) stepPx = minOf(stepPx, c.height.toFloat())
                }
                if (stepPx == java.lang.Float.MAX_VALUE) stepPx = 24f * density
                stepPx = (stepPx * 0.5f).coerceIn(4f * density, 64f * density)
                val steps = Math.max(1, (dist / stepPx).toInt())
                for (s in 1 until steps) {
                    val f = s.toFloat() / steps
                    val sp = positionUnder(recyclerView, px + dx * f, py + dy * f)
                    if (sp != RecyclerView.NO_POSITION && sp != lastPosition) {
                        lastPosition = sp
                        onApply(sp, dragAdd)
                    }
                }
            }
            lastPosition = pos
            lastAppliedX = x; lastAppliedY = y
            onApply(pos, dragAdd)
        }
    }

    // ── §3D: the per-frame edge auto-scroll (see the header comment). ────────
    var lastFrameAtMs = 0L
    val autoScrollFrame = object : Runnable {
        override fun run() {
            if (!dragging) return   // endDrag already removed us; belt-and-braces
            val now = android.os.SystemClock.uptimeMillis()
            val dtMs = if (lastFrameAtMs == 0L) 16L else (now - lastFrameAtMs).coerceIn(8L, 34L)
            lastFrameAtMs = now
            val h = recyclerView.height
            val topZone = edgeZonePx
            val bottomZone = h - edgeZonePx
            // ── GOAT-PASS 9 (§4 — auto-scroll continues OUTSIDE the edge): ──
            // the finger's Y is clamped into the list's bounds for the zone
            // computation. A finger dragged past the top or bottom boundary
            // (still on screen — the gesture is alive and motion events keep
            // flowing with out-of-range local coordinates) therefore reads as
            // MAXIMUM proximity instead of "not in a rim": scrolling that had
            // already started continues at full speed until the finger lifts,
            // the gesture is cancelled, or the content runs out. The old
            // `lastY in 0f..topZone` test went false the moment the finger
            // crossed the boundary and the scroll silently STOPPED — the
            // reported "edge auto-scroll stops when the finger moves beyond
            // the top edge".
            val clampedY = lastY.coerceIn(0f, h.toFloat())
            var speedPxPerSec = 0f
            if (clampedY <= topZone) {
                val proximity = (1f - (clampedY / edgeZonePx)).coerceIn(0f, 1f)   // 0 at the zone edge → 1 at (or beyond) the rim
                // GOAT-PASS 9 (§4): quadratic ease-in — smooth, progressive,
                // predictable; no sudden jumps on any part of the curve.
                val curve = proximity * proximity
                speedPxPerSec = -edgeMinSpeedPx.coerceAtLeast(0f) -
                    (edgeMaxSpeedPx - edgeMinSpeedPx) * curve
            } else if (clampedY >= bottomZone) {
                val intoZone = (clampedY - bottomZone) / edgeZonePx
                val proximity = intoZone.coerceIn(0f, 1f)
                val curve = proximity * proximity
                speedPxPerSec = edgeMinSpeedPx + (edgeMaxSpeedPx - edgeMinSpeedPx) * curve
            }
            if (speedPxPerSec != 0f) {
                // Bounds-aware: never scroll past the content (scrollBy clamps
                // on its own; canScrollVertically keeps the frame loop from
                // spinning hot at either end).
                val dir = if (speedPxPerSec < 0f) -1 else 1
                if (recyclerView.canScrollVertically(dir)) {
                    recyclerView.scrollBy(0, (speedPxPerSec * dtMs / 1000f).toInt().let {
                        // A sub-pixel step must still move (or the scroll stalls at
                        // min speed); a huge frame gap must never jump rows.
                        if (it == 0 && speedPxPerSec != 0f) dir else it
                    })
                    // The content moved under a (possibly stationary) finger —
                    // whatever row is now beneath it joins the pass, exactly as
                    // if the finger had crossed it.
                    applyUnder(lastX, clampedY)
                }
            }
            recyclerView.postOnAnimation(this)
        }
    }

    fun endDrag() {
        val wasDragging = dragging
        dragging = false
        lastPosition = RecyclerView.NO_POSITION
        // ── FULL FINAL PASS 2 (§8): the interpolation origin resets with the ──
        // gesture — a stale point must never seed the next gesture's span.
        lastAppliedX = 0f; lastAppliedY = 0f
        // §3D: the scroller stops the SAME FRAME the gesture ends — no
        // residual drift, no timer outliving the touch.
        recyclerView.removeCallbacks(autoScrollFrame)
        lastFrameAtMs = 0L
        // ── SELECTION FINAL PASS 1 (§3): the once-per-gesture reconciliation ──
        // hook — only for a gesture that genuinely armed (a plain tap or a
        // Range-mode long-press never starts one, so they never pay for it).
        if (wasDragging) {
            recyclerView.post { onGestureEnd() }
        }
    }

    fun maybeStartAutoScroll() {
        if (!edgeAutoScroll || !dragging) return
        // The Pass-40 lesson, honored: a finger that never left the long-press
        // spot does not arm the scroller — only a genuine slide (the finger
        // that is heading toward or into the rim) does.
        if (!movedBeyondArmThreshold) return
        val h = recyclerView.height
        // GOAT-PASS 9 (§4): a finger BEYOND either boundary (negative Y or
        // past the bottom — clamped here exactly like the frame loop) is as
        // deep in the rim as it can get; the scroller arms for it too.
        val clampedY = lastY.coerceIn(0f, h.toFloat())
        if (clampedY > edgeZonePx && clampedY < h - edgeZonePx) return   // not in a rim
        if (lastFrameAtMs == 0L) {
            lastFrameAtMs = android.os.SystemClock.uptimeMillis()
            recyclerView.postOnAnimation(autoScrollFrame)
        }
    }

    // ── GOAT-PASS 11 (§12 — the initiating row is ALWAYS processed, and ───────────────────
    // the gesture arms slightly faster). Two coordinated changes to the
    // arm mechanism, replacing the GestureDetector entirely:
    //
    //  1. THE FIRST-ITEM GUARANTEE. The arm used to ride the platform's
    //     GestureDetector while the ROW's own long-press (the initiating
    //     toggle on every surface) rode the View system's own 500 ms
    //     callback — two independent timers with subtly different
    //     cancellation behavior under interception. When the row's
    //     callback lost the race (the finger leaving right at the
    //     threshold: the parent's scroll interception cancelled the row's
    //     pending press while the detector still armed the drag), the
    //     gesture selected everything it CROSSED but never processed the
    //     row it STARTED on — the reported "first item remains unchanged".
    //     Now the gesture itself applies the initiating row through the
    //     same idempotent SET operation every crossed row gets (direction
    //     captured at DOWN), and it CANCELS the row view's own pending
    //     long-press at arm time — exactly one owner, no ordering, no
    //     double-toggle, no skip. Surfaces whose rows keep a long-press
    //     for OTHER duties (range anchoring, child rows inside expanded
    //     folders) are untouched in Range mode and for excluded regions.
    //
    //  2. SLIGHTLY FASTER ACTIVATION. The arm timer is ours now:
    //     HOLD_SLIDE_ARM_MS (440 ms — 60 ms under the platform's 500 ms
    //     long-press) in Hold & Slide mode; Range mode keeps the exact
    //     platform timeout so its anchoring rhythm is byte-identical.
    //     The reduction stays far above ordinary tap and scroll
    //     durations, and movement beyond the touch slop still cancels
    //     the arm outright — scrolling and tapping cannot activate it.
    val touchSlopPx = ViewConfiguration.get(recyclerView.context).scaledTouchSlop
    val armHandler = android.os.Handler(android.os.Looper.getMainLooper())
    val armLongPress = Runnable {
        // ── FINAL PASS 2 (§8 — arm under the CURRENT finger, gap-tolerantly): ──
        // the initiating row is resolved from the finger's LIVE position
        // (lastX/lastY) through the same gap-tolerant, exclusion-aware
        // resolver the slide pass uses — not the raw DOWN coordinates. A
        // finger that settled slightly inside the arm window selects the row
        // it is actually holding on, a press that began in a gap between rows
        // still resolves to the nearest eligible one instead of silently
        // arming nothing, and an excluded region never arms at all.
        val pos = positionUnder(recyclerView, lastX, lastY)
        if (pos == RecyclerView.NO_POSITION) return@Runnable
        val child = recyclerView.findChildViewUnder(lastX, lastY)
            ?: recyclerView.layoutManager?.findViewByPosition(pos)
            ?: return@Runnable
        // ── GOAT-PASS 8 (§14): a long-press inside an expanded folder's ──
        // content area belongs to that content (its rows scroll, its own
        // handlers act) — never to the parent folder row's selection.
        if (hitInsideExclusion(child, lastX, lastY)) return@Runnable
        longPressSeen = true
        // ── GOAT-PASS 8 (§4 — Range mode): the drag NEVER arms. The row's ──
        // own long-press owns the whole interaction; this listener's only
        // remaining duties are the haptic and the terminal-UP consumption
        // below, so the row's click path cannot fire under the press.
        if (!slideEnabled()) {
            recyclerView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            return@Runnable
        }
        // §5 (Reliability pass): the initiating press decides the pass — an
        // already-selected cell starts a REMOVE pass, an unselected one an
        // ADD pass. Pass-39 (#1): the verdict comes from the state
        // captured at DOWN. GOAT-PASS 11 (§12): the GESTURE now also
        // APPLIES the initiating row (one owner — see the header note) and
        // cancels the row's own pending long-press so the two paths can
        // never double-process it.
        dragAdd = !selectedAtDown
        dragging = true
        lastPosition = pos
        // ── FULL FINAL PASS 2 (§8): the interpolation origin is the finger's ──
        // LIVE position at arm time — the swept-span walk in applyUnder
        // starts from exactly here, so the very first movement after the
        // initiating selection cannot skip the rows it immediately crosses.
        lastAppliedX = lastX; lastAppliedY = lastY
        child.cancelLongPress()
        // v1.0: a single soft LONG_PRESS haptic marks the moment the
        // gesture commits (select OR deselect) — the tactile "click"
        // that makes one-finger toggle selection feel deliberate.
        recyclerView.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onApply(pos, dragAdd)
        // ── FINAL PASS 2 (§8.6 — the first movement is never lost): the row ──
        // under the finger at ARM time is applied immediately, so a slide
        // that began in the same instant does not wait for (or lose) the
        // first ACTION_MOVE to begin crossing rows.
        applyUnder(lastX, lastY)
    }
    recyclerView.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val child = rv.findChildViewUnder(e.x, e.y)
                    val pos = if (child != null) rv.getChildAdapterPosition(child) else RecyclerView.NO_POSITION
                    selectedAtDown = pos != RecyclerView.NO_POSITION && isSelectedAt(pos)
                    downX = e.x; downY = e.y
                    lastX = e.x; lastY = e.y
                    movedBeyondArmThreshold = false
                    longPressSeen = false
                    // GOAT-PASS 11 (§12): OUR arm timer — derived from the
                    // platform's own long-press timeout minus a 60 ms head
                    // start in Hold & Slide mode (see HOLD_SLIDE_ARM_MS's
                    // FINAL PASS 2 note), the exact platform timeout in Range
                    // mode. Cancelled by scroll-intent movement, by any
                    // terminal/pointer event, and by the drag itself once
                    // armed — exactly the GestureDetector's discipline, at
                    // a delay this app now owns.
                    armHandler.removeCallbacks(armLongPress)
                    armHandler.postDelayed(armLongPress,
                        if (slideEnabled()) HOLD_SLIDE_ARM_MS else platformLongPressMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    lastX = e.x; lastY = e.y
                    if (!movedBeyondArmThreshold &&
                        (Math.abs(e.x - downX) > moveArmThresholdPx || Math.abs(e.y - downY) > moveArmThresholdPx))
                        movedBeyondArmThreshold = true
                    // GOAT-PASS 11 (§12) + FINAL PASS 2 (§8): genuine SCROLL intent
                    // cancels the pending arm — and "scroll intent" now means the
                    // SCROLLABLE axis. The old omnidirectional test cancelled the
                    // arm on ANY slop-crossing movement, but a vertical RecyclerView
                    // only claims a drag when the movement exceeds slop VERTICALLY —
                    // so an early predominantly-HORIZONTAL move toward a
                    // neighbouring cell (exactly "slide toward the next item" in a
                    // grid) killed the arm while the platform's own long-press still
                    // fired on the un-intercepted child: the initial row selected
                    // with NO drag armed behind it, and everything the finger
                    // crossed afterwards was silently ignored — the reported
                    // "sliding immediately after the initial selection selects
                    // nothing". Cancelling on the vertical axis alone keeps every
                    // ordinary scroll exactly as safe as before (a scroll always
                    // crosses vertical slop) while a horizontal-reaching hold keeps
                    // its arm and its gesture.
                    if (!dragging && Math.abs(e.y - downY) > touchSlopPx) {
                        armHandler.removeCallbacks(armLongPress)
                    }
                    if (dragging) {
                        applyUnder(e.x, e.y)
                        maybeStartAutoScroll()
                    }
                }
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                    // Pass-39 (#1): a different finger arriving or leaving is
                    // a different gesture — end the drag cleanly and consume
                    // the event so nothing under it re-triggers.
                    armHandler.removeCallbacks(armLongPress)
                    if (dragging) { endDrag(); return true }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    armHandler.removeCallbacks(armLongPress)
                    // Next Release Pass (§8 — the long-press selection that
                    // vanished on finger release): once the gesture was EVER
                    // claimed, the terminal UP/CANCEL is CONSUMED here (returns
                    // true) so no child can turn it into a click — the
                    // selection state committed by the gesture survives the
                    // release, and subsequent plain taps select/deselect
                    // through the normal click path exactly as designed.
                    // ── GOAT-PASS 7 (§3C): a terminal UP performs ONE final ──
                    // gap-tolerant apply of the row under the finger, bypassing
                    // the dedupe guard — the endpoint of the gesture is included
                    // exactly like every row crossed on the way (the reported
                    // "final item sometimes remains selected" defect).
                    if (dragging && e.actionMasked == MotionEvent.ACTION_UP) {
                        applyUnder(e.x, e.y, includeEndpoint = true)
                    }
                    val wasDragging = dragging
                    val consume = wasDragging || longPressSeen
                    endDrag()
                    longPressSeen = false
                    return consume
                }
            }
            return dragging
        }
        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            // Reached only once onInterceptTouchEvent has already claimed the
            // gesture. GOAT-PASS 11 (§12): the arm timer replaced the old
            // GestureDetector feed; the claimed stream only needs the drag
            // maintenance below (the timer is cancelled the moment the
            // gesture is claimed — a claimed stream is already armed).
            armHandler.removeCallbacks(armLongPress)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    lastX = e.x; lastY = e.y
                    if (!movedBeyondArmThreshold &&
                        (Math.abs(e.x - downX) > moveArmThresholdPx || Math.abs(e.y - downY) > moveArmThresholdPx))
                        movedBeyondArmThreshold = true
                    applyUnder(e.x, e.y)
                    maybeStartAutoScroll()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // ── GOAT-PASS 7 (§3C): the terminal endpoint apply lives ──
                    // here too — once the stream is claimed, the UP arrives on
                    // this path, not the intercept path above.
                    if (dragging && e.actionMasked == MotionEvent.ACTION_UP) {
                        applyUnder(e.x, e.y, includeEndpoint = true)
                    }
                    endDrag()
                }
            }
        }
        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    })
}

// ═══ Pass-37 (#6/#7): TextureMovieView — the preview's video renderer ═══════
//
// WHY this class exists. The preview's video pages used android.widget.VideoView,
// which is a SURFACE view: its pixels live in a separate compositor layer that
// renders BEHIND the window's own content. Inside the picker's shared window
// that had two real consequences: the video could only show through wherever
// the window was transparent — the sheet's opaque surface covered it (the
// "preview appears behind the picker sheet" half of the reported regression,
// because the window-overlay elevation fix physically cannot elevate another
// compositor layer) — and a stopped video's last frame stayed alive on that
// surface, showing THROUGH the transparent surroundings of a neighboring image
// page (the "selected an image, the previously played video reappeared" half).
//
// A TextureView renders INTO the window's normal content tree: it participates
// in elevation and z-order like any view, a recycled/stopped cell's texture is
// cleared outright, and the MediaPlayer behind it is owned, generation-guarded
// and released by THIS class — one item per view, one view per item, no stale
// callbacks (every listener checks it is still the CURRENT player before
// acting). The public surface mirrors VideoView's, so the preview's whole
// existing control row, scrub semantics and pause/stop machinery are untouched.
private class TextureMovieView(context: android.content.Context) :
    android.view.TextureView(context), android.view.TextureView.SurfaceTextureListener {

    private var player: android.media.MediaPlayer? = null
    private var pendingUri: Uri? = null
    private var surface: android.view.Surface? = null
    /** A start() that arrived before the player was prepared — honored the
     *  moment onPrepared lands (VideoView's classic pre-armed start). */
    private var wantPlaying = false

    // ── Pass-38 (#1): the video's OWN dimensions, tracked independently of ────
    // the transform so EVERY path that changes them (prepare, a late
    // onVideoSizeChanged, surface re-creation) can pin the SurfaceTexture's
    // buffer to the video's resolution and re-derive the letterbox. The old
    // code read videoWidth/videoHeight only at onPrepared: a video whose real
    // size arrived late, a surface re-created at a different buffer size, or
    // an OEM buffer negotiation left the transform mapping the WRONG buffer —
    // exactly the reported stretched/cropped/displaced-bottom-left rendering.
    private var videoW = 0
    private var videoH = 0

    // ── Pass-38 (#9): the playback-coordination signal. Fired on EVERY
    // playing-state transition this wrapper owns (start engaging, pause,
    // completion, error, full release) so the sheet can keep an exact set of
    // currently-playing videos and drive the music auto-pause/resume
    // coordinator from real edges — never timers, never guesses.
    private var onPlayingChangedCb: ((Boolean) -> Unit)? = null
    fun setOnPlayingChangedListener(l: (Boolean) -> Unit) { onPlayingChangedCb = l }
    private var playingReported = false
    private fun reportPlaying(p: Boolean) {
        if (playingReported == p) return
        playingReported = p
        try { onPlayingChangedCb?.invoke(p) } catch (e: Exception) { /* coordination must not break playback */ }
    }

    private var onPreparedCb: ((android.media.MediaPlayer) -> Unit)? = null
    private var onCompletionCb: (() -> Unit)? = null
    private var onErrorCb: ((android.media.MediaPlayer?, Int, Int) -> Boolean)? = null
    private var onInfoCb: ((android.media.MediaPlayer?, Int, Int) -> Boolean)? = null

    init {
        surfaceTextureListener = this
    }

    // ---- the VideoView-shaped public surface --------------------------------

    fun setVideoURI(uri: Uri) {
        open(uri)
    }

    fun setVideoPath(path: String) {
        open(Uri.fromFile(File(path)))
    }

    val isPlaying: Boolean
        get() = try { player?.isPlaying == true } catch (e: Exception) { false }

    val currentPosition: Int
        get() = try { player?.currentPosition ?: 0 } catch (e: Exception) { 0 }

    val duration: Int
        get() = try { player?.duration ?: 0 } catch (e: Exception) { 0 }

    fun start() {
        val p = player
        if (p == null) { wantPlaying = true; reportPlaying(true); return }
        try {
            p.start()
            wantPlaying = false
            reportPlaying(true)
        } catch (e: Exception) {
            // Still preparing — arm the start; onPrepared honors it.
            wantPlaying = true
            reportPlaying(true)
        }
    }

    fun pause() {
        wantPlaying = false
        try { player?.pause(); reportPlaying(false) } catch (e: Exception) { reportPlaying(false) /* stale player */ }
    }

    fun seekTo(msec: Int) {
        try { player?.seekTo(msec) } catch (e: Exception) { /* stale player */ }
    }

    /** Full release: player gone, deferred work gone, texture cleared. The
     *  next setVideoURI builds a fresh player — a recycled or stopped cell can
     *  never keep, show or re-emit anything from the previous item. */
    fun stopPlayback() {
        releasePlayer()
        pendingUri = null
        wantPlaying = false
        reportPlaying(false)
        clearTexture()
    }

    fun setOnPreparedListener(l: (android.media.MediaPlayer) -> Unit) { onPreparedCb = l }
    fun setOnCompletionListener(l: () -> Unit) { onCompletionCb = l }
    fun setOnErrorListener(l: (android.media.MediaPlayer?, Int, Int) -> Boolean) { onErrorCb = l }
    fun setOnInfoListener(l: (android.media.MediaPlayer?, Int, Int) -> Boolean) { onInfoCb = l }

    // ---- internals -----------------------------------------------------------

    private fun open(uri: Uri) {
        releasePlayer()
        pendingUri = uri
        wantPlaying = false
        videoW = 0; videoH = 0
        // The player is only built once the texture's surface exists — a
        // surface-less video prepare is device-dependent territory, and a
        // laid-out TextureView produces its surface within a frame of
        // attach anyway (onSurfaceTextureAvailable re-runs open() below).
        if (surface == null) return
        val mp = android.media.MediaPlayer()
        player = mp
        try {
            mp.setDataSource(context, uri)
            mp.setSurface(surface)
            mp.setVideoScalingMode(android.media.MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT)
            // ── Pass-38 (#1): the video size is a MOVING target. The container's
            // dimensions can arrive (or change) AFTER prepare — rotation
            // metadata in particular is applied late on several codecs — and
            // the old code read the size exactly once at onPrepared. Track every
            // size change: pin the SurfaceTexture's buffer to the video's own
            // resolution (see pinVideoBufferSize) and re-derive the letterbox.
            mp.setOnVideoSizeChangedListener { p, w, h ->
                if (p !== player) return@setOnVideoSizeChangedListener   // stale — not this item
                noteVideoSize(w, h)
            }
            mp.setOnPreparedListener { p ->
                if (p !== player) return@setOnPreparedListener   // a stale prepare — not this item
                noteVideoSize(p.videoWidth, p.videoHeight)
                onPreparedCb?.invoke(p)
                if (wantPlaying) {
                    wantPlaying = false
                    try { p.start(); reportPlaying(true) } catch (e: Exception) { reportPlaying(false) /* refused — the ticker paints the truth */ }
                }
            }
            mp.setOnCompletionListener { p ->
                if (p === player) { reportPlaying(false); onCompletionCb?.invoke() }
            }
            mp.setOnErrorListener { p, what, extra ->
                if (p === player) { reportPlaying(false); (onErrorCb?.invoke(p, what, extra) ?: true) } else true
            }
            mp.setOnInfoListener { p, what, extra ->
                if (p === player) (onInfoCb?.invoke(p, what, extra) ?: false) else false
            }
            mp.prepareAsync()
        } catch (e: Exception) {
            // DataSource refused outright — surface it through the same error
            // listener the cell already renders honestly.
            player = null
            reportPlaying(false)
            try { mp.release() } catch (ex: Exception) { /* already dead */ }
            onErrorCb?.invoke(null, android.media.MediaPlayer.MEDIA_ERROR_UNKNOWN, 0)
        }
    }

    private fun releasePlayer() {
        val p = player ?: return
        player = null
        pendingUri = null
        reportPlaying(false)
        try { p.setSurface(null) } catch (e: Exception) { /* mid-release */ }
        try { p.release() } catch (e: Exception) { /* already gone */ }
    }

    /** Clears the texture so a stopped/recycled cell shows nothing — no last
     *  frame, no ghost over a neighboring page. Best-effort: a surface that
     *  is already gone simply cannot be cleared.
     *  ── Pass-38 (#1): the clear is a ZERO-SCALE texture transform, not the ──
     *  old lockCanvas() software-canvas pass. lockCanvas forced the
     *  SurfaceTexture into a CPU buffer allocated at the VIEW's size; the
     *  buffer-size flip-flop (view-sized canvas buffer, then video-sized
     *  decoder buffer) is exactly the kind of renegotiation some OEM
     *  compositors answer with displaced or mis-scaled frames. A zero-scale
     *  matrix needs no new buffer at all: whatever the last decoded frame
     *  was, it now maps to nothing, and the next applyVideoTransform()
     *  overwrites the matrix for the next item. */
    private fun clearTexture() {
        try {
            if (isAvailable) {
                val m = android.graphics.Matrix()
                m.setScale(0f, 0f)
                setTransform(m)
                invalidate()
            }
        } catch (e: Exception) { /* surface already gone */ }
    }

    /** ── Pass-38 (#1): the rendering contract, in one place ──────────────────
     *  The letterbox transform maps the video's BUFFER into the view. That is
     *  only correct while the buffer carries the video at exactly
     *  (videoW × videoH) — TextureView otherwise sizes its SurfaceTexture's
     *  default buffer to the VIEW, and a decoder negotiated into a
     *  view-sized buffer while the transform was computed for the video's
     *  dimensions renders stretched, cropped or displaced. So every size
     *  event does BOTH halves of the contract:
     *    1. pin the SurfaceTexture's default buffer to the video's own
     *       resolution (the battle-tested TextureView-video recipe);
     *    2. re-derive the letterbox matrix from the CURRENT view size.
     *  Every path funnels through here: prepare, onVideoSizeChanged, surface
     *  (re)creation, view resize. */
    private fun noteVideoSize(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        if (w != videoW || h != videoH || !sizePinned) {
            videoW = w; videoH = h
            pinVideoBufferSize()
        }
        player?.let { applyVideoTransform(it) }
    }

    private var sizePinned = false

    private fun pinVideoBufferSize() {
        if (videoW <= 0 || videoH <= 0) return
        try {
            surfaceTexture?.setDefaultBufferSize(videoW, videoH)
            sizePinned = true
        } catch (e: Exception) { /* surface gone — re-pinned at next availability */ }
    }

    /** Letterboxed fit (VideoView's default): the video is scaled to fit
     *  inside the view and centered, via the texture matrix. Re-applied on
     *  every size change so rotations/resizes stay correct. Uses the TRACKED
     *  [videoW]/[videoH] — the same dimensions the buffer is pinned to — so
     *  the matrix and the buffer can never describe different videos.
     *
     *  ── Pass-39 (#11 — the actual rendering fix) ────────────────────────────
     *  TextureView's transform matrix operates on the VIEW-STRETCHED buffer,
     *  NOT on raw buffer pixels: with the identity matrix the buffer is
     *  already stretched to fill the whole view (that is the documented
     *  default), and the matrix we set transforms THAT stretched image inside
     *  the view. The old math — setScale(s, s) with s = fit-scale, plus a
     *  translate as if mapping buffer pixels — composed the letterbox against
     *  the wrong base: a portrait video in a taller view kept its vertical
     *  stretch and was pushed DOWN past the frame; a landscape video was
     *  scaled as if the buffer already matched the view and landed shrunk,
     *  bottom-heavy and left-shifted — precisely the reported "stretched
     *  vertically, cropped from the bottom and left, displaced toward the
     *  bottom-left, partially outside the preview".
     *
     *  The correct composition: the buffer is drawn stretched by
     *  (viewW/videoW, viewH/videoH); to land the video at fit-scale s and
     *  centered, our matrix must scale that stretched image by the RATIO of
     *  the fit scale to the fill stretch, then translate to center:
     *      sx = s·videoW/viewW,  sy = s·videoH/viewH,
     *      tx = (viewW − videoW·s)/2,  ty = (viewH − videoH·s)/2
     *  (Equivalently: dmytrodanylyk's classic TextureView video recipe — a
     *  scale about the view center — expressed once, for all aspect ratios.)
     */
    private fun applyVideoTransform(p: android.media.MediaPlayer) {
        val vw = videoW; val vh = videoH
        if (vw <= 0 || vh <= 0 || width <= 0 || height <= 0) return
        val s = minOf(width.toFloat() / vw, height.toFloat() / vh)
        val m = android.graphics.Matrix()
        m.setScale(s * vw / width, s * vh / height)
        m.postTranslate((width - vw * s) / 2f, (height - vh * s) / 2f)
        setTransform(m)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        player?.let { applyVideoTransform(it) }
    }

    // ---- SurfaceTextureListener ----------------------------------------------

    override fun onSurfaceTextureAvailable(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
        surface = android.view.Surface(st)
        val p = player
        if (p != null) {
            // A page re-attaching after surface churn — hand the live player
            // its new surface (a full release only happens on recycle/stop).
            // ── Pass-38 (#1): a FRESH SurfaceTexture starts at the view's own ──
            // default buffer size again — re-pin it to the video's resolution
            // BEFORE the player renders into it, and re-derive the letterbox
            // so the very first frame on the new surface is already correct
            // (the old code left the re-attached surface with a view-sized
            // buffer and a transform computed for the old one).
            pinVideoBufferSize()
            try { p.setSurface(surface) } catch (e: Exception) { /* stale */ }
            applyVideoTransform(p)
        } else {
            // open() deferred waiting for this surface — build the player now.
            // A re-created surface needs the pin too (noteVideoSize re-pins
            // once the item's dimensions arrive with prepare).
            sizePinned = false
            pendingUri?.let { open(it) }
        }
    }

    override fun onSurfaceTextureSizeChanged(st: android.graphics.SurfaceTexture, width: Int, height: Int) {
        player?.let { applyVideoTransform(it) }
    }

    override fun onSurfaceTextureDestroyed(st: android.graphics.SurfaceTexture): Boolean {
        // Pause, keep the player: the page is detaching (pager cache) and may
        // come right back with a fresh surface; a full release happens on
        // recycle/stopPlayback, where the page is genuinely gone. ── Pass-38: ──
        // the player is DETACHED from the surface before the wrapper is
        // released — a MediaPlayer still holding a released Surface is
        // undefined territory on several OEM builds (the "video went black
        // forever after a swipe" class of bug), while a detached one
        // re-attaches cleanly when the page comes back with its new surface.
        // ── Pass-38 (#9): the page leaving means playback genuinely stopped — ──
        // the music coordinator hears the edge from here like any other stop.
        try { player?.pause() } catch (e: Exception) { /* stale */ }
        reportPlaying(false)
        try { player?.setSurface(null) } catch (e: Exception) { /* stale */ }
        surface?.release()
        surface = null
        sizePinned = false
        return true
    }

    override fun onSurfaceTextureUpdated(st: android.graphics.SurfaceTexture) {}
}
