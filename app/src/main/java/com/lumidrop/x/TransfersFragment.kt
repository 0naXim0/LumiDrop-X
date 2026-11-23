package com.lumidrop.x

import android.content.Intent
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.lumidrop.x.p2p.NativePushClient
import com.lumidrop.x.server.MimeTypes
import com.lumidrop.x.server.LumiDropServer
import com.lumidrop.x.service.ServerForegroundService
import com.lumidrop.x.transfer.HistoryStore
import com.lumidrop.x.transfer.TransferManager
import com.lumidrop.x.transfer.TransferManager.Direction
import com.lumidrop.x.transfer.TransferManager.Status
import com.lumidrop.x.transfer.TransferManager.Source
import com.lumidrop.x.ui.BottomSheet
import com.lumidrop.x.ui.FormatIcons
import com.lumidrop.x.ui.FolderBox
import com.lumidrop.x.ui.Widgets
import com.lumidrop.x.ui.LdxDialogAction
import com.lumidrop.x.ui.LdxButtonStyle
import com.lumidrop.x.ui.dp
import com.lumidrop.x.ui.dpf
import com.lumidrop.x.ui.trackNavBarBottomPadding
import com.lumidrop.x.ui.showLdxDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The "Transfers" tab -- ported from TransfersActivity as part of Phase 17's
 * bottom-nav shell. Structure and behavior are unchanged from the original
 * (see the section comments below, carried over) -- this port is mechanical
 * (Activity -> Fragment, the refresh Handler now also respects
 * onHiddenChanged so it stops polling when a different tab is on screen)
 * plus the Phase 15/16 visual pass: every emoji swapped for the shared
 * vector icon set, action buttons re-skinned onto the same token system as
 * the rest of the app.
 *
 * Three tabs:
 *  Active    -- PENDING + ACTIVE together in ONE unified list (Active rows
 *              first, Waiting below, Paused shown in place via its own
 *              status chip), PLUS recently-failed/cancelled transfers shown
 *              here temporarily until dismissed.
 *  Received  -- successfully completed transfers where this device was the
 *              recipient.
 *  Sent      -- successfully completed transfers where this device was the
 *              source.
 *
 * One unified list for Active rather than separate Active/Waiting/Failed
 * sections is deliberate -- see TransferManager.activeSnapshot()'s comment
 * for why. Keeping that ordering intact through to what's actually on
 * screen is TransferRowAdapter's job (RecyclerView + DiffUtil, replacing
 * this port's original hand-rolled diffRenderList) -- see its own comment
 * for why a plain LinearLayout diff wasn't enough to avoid a flicker/
 * reordering regression on every refresh tick once a folder transfer put
 * a few hundred rows through it at once.
 */
/**
 * GOAT (Part 1 correction — card flickering): an immutable point-in-time
 * copy of exactly the fields a row's UI depends on.
 *
 * The problem this replaces: TransferManager.Transfer is a live, mutable,
 * SHARED object — the same instance is hand back on every poll tick with
 * its fields simply updated in place. Feeding those live objects straight
 * into DiffUtil doesn't work: "old" and "new" are frequently the exact
 * same reference, so a naive equals()/reference comparison always reports
 * "unchanged" even when the visible fields plainly differ from what was
 * last drawn (stale display) — and the workaround of always reporting
 * "changed" instead (what this project shipped first) swings too far the
 * other way: it forces DiffUtil to treat every row as different on every
 * single tick, so onBindViewHolder fully re-runs for every visible row
 * every ~1-2s regardless of whether anything about that transfer actually
 * changed — including resetting the thumbnail to its placeholder and
 * re-triggering the async decode, which is exactly what the flickering
 * was.
 *
 * The real fix: capture a genuinely independent VALUE right before each
 * submitRows() call (see TransferRowAdapter), so DiffUtil is comparing
 * two separate snapshots in time — what a row looked like last tick vs.
 * what it looks like now — instead of a mutable object against itself. A
 * plain data class gets correct structural equals()/hashCode() for free,
 * so two snapshots with identical values are indistinguishable to
 * DiffUtil regardless of whether they came from the same underlying
 * Transfer instance.
 *
 * GOAT (Part 2 — folder as a single transfer item): sealed over both a
 * solo file and a folder group, for exactly the same reason —
 * TransferManager.TransferRowItem.Group's aggregate progress/status is
 * computed fresh from its (also live, mutable) member Transfers on every
 * call, so it needs the same "independent snapshot" treatment as a solo
 * file's own fields do.
 */
private sealed class TransferRowState {
    abstract val key: String

    data class Solo(
        val id: String,
        val fileName: String,
        val direction: TransferManager.Direction,
        val status: TransferManager.Status,
        val progressPct: Int,
        val isIndeterminate: Boolean,
        val pauseRequested: Boolean,
        val transferredBytes: Long,
        val totalBytes: Long,
        val speedBps: Double,
        val peerName: String,
        val filePath: String?,
        val finishedAt: Long,
        // Phase 5 (§5.4 — multi-item delete): both selection fields ride the
        // SAME snapshot mechanism every other visual field already uses —
        // DiffUtil compares two point-in-time values and rebinds exactly the
        // rows whose selection actually changed, so toggling one row never
        // rebuilds the list, and entering/exiting selection mode (which
        // flips selectionActive on every row) rebinds precisely once.
        val selectionActive: Boolean = false,
        val selected: Boolean = false,
    ) : TransferRowState() {
        override val key get() = "solo:$id"
    }

    data class Group(
        val groupId: String,
        val name: String,
        val status: TransferManager.Status,
        val progressPct: Int,
        val isIndeterminate: Boolean,
        val filesDone: Int,
        val filesTotal: Int,
        val anyPaused: Boolean,
        val expanded: Boolean,
        val selectionActive: Boolean = false,
        val selected: Boolean = false,
    ) : TransferRowState() {
        override val key get() = "group:$groupId"
    }
}

/** Stable per-row identity independent of any in-progress state — what
 *  [TRANSFER_ROW_DIFF] uses for areItemsTheSame, and what the adapter's
 *  liveById map is keyed by. Prefixed by kind so a (theoretically
 *  possible, if UUIDs ever collided) solo id and group id can never be
 *  mistaken for the same row. */
private fun TransferManager.TransferRowItem.rowKey(): String = when (this) {
    is TransferManager.TransferRowItem.Solo -> "solo:${transfer.id}"
    is TransferManager.TransferRowItem.Group -> "group:${info.id}"
}

private fun TransferManager.TransferRowItem.toRowState(expanded: Boolean, selectionActive: Boolean = false, selected: Boolean = false): TransferRowState = when (this) {
    is TransferManager.TransferRowItem.Solo -> {
        val t = transfer
        TransferRowState.Solo(
            id = t.id, fileName = t.fileName, direction = t.direction, status = t.status,
            progressPct = t.progressPct, isIndeterminate = t.isIndeterminate, pauseRequested = t.pauseRequested,
            transferredBytes = t.transferredBytes, totalBytes = t.totalBytes, speedBps = t.speedBps,
            peerName = t.peerName, filePath = t.filePath, finishedAt = t.finishedAt,
            selectionActive = selectionActive, selected = selected,
        )
    }
    is TransferManager.TransferRowItem.Group -> TransferRowState.Group(
        groupId = info.id, name = info.name, status = TransferManager.groupStatus(members),
        progressPct = TransferManager.groupProgressPct(members), isIndeterminate = TransferManager.groupIsIndeterminate(members),
        // ── GOAT-PASS 14 (§4): the DiffUtil total is the ELIGIBLE count — ──
        // the same denominator groupDetailLine renders (see its own doc).
        filesDone = members.count { it.status == TransferManager.Status.COMPLETED },
        filesTotal = TransferManager.eligibleMembers(members).size,
        // GOAT (continuation brief §2.5 spot-check): scoped to PENDING/
        // ACTIVE, matching updateGroupRow's own anyPaused below and
        // buildGroupActionButtons' liveMembers exactly -- this value only
        // ever feeds DiffUtil's areContentsTheSame (oldItem == newItem on
        // this whole data class), never the rendered icon directly, so an
        // unscoped version couldn't show the wrong Pause/Resume button --
        // but a stale pauseRequested flag left on an already-COMPLETED
        // member could still make DiffUtil see a change (or miss one)
        // that was never real, an inconsistency worth closing now that
        // it's right next to the two spots that already got this right.
        anyPaused = members.any { (it.status == Status.PENDING || it.status == Status.ACTIVE) && it.pauseRequested }, expanded = expanded,
        selectionActive = selectionActive, selected = selected,
    )
}

private val TRANSFER_ROW_DIFF = object : DiffUtil.ItemCallback<TransferRowState>() {
    override fun areItemsTheSame(oldItem: TransferRowState, newItem: TransferRowState) = oldItem.key == newItem.key
    override fun areContentsTheSame(oldItem: TransferRowState, newItem: TransferRowState) = oldItem == newItem
    // Phase 8 (§8.3 — the 500ms full-rebind churn): while anything is
    // ACTIVE, every poll tick produces snapshots whose ONLY diffs are the
    // live progress fields (pct/bytes/speed for a solo row, pct/filesDone
    // for a folder row). DiffUtil correctly reported "changed" — and the
    // adapter's only response was a FULL onBindViewHolder: action
    // buttons rebuilt, vector icons re-inflated, a fresh thumbnail decode
    // Job launched, listeners reset — twice per second per active row,
    // for a 300-file folder transfer essentially continuously. The
    // payload marks the progress-only case so the adapter can patch just
    // the bar / chip / detail line in place instead.
    override fun getChangePayload(oldItem: TransferRowState, newItem: TransferRowState): Any? {
        if (oldItem is TransferRowState.Solo && newItem is TransferRowState.Solo) {
            val o = oldItem.copy(progressPct = 0, isIndeterminate = false, transferredBytes = 0L, speedBps = 0.0)
            val n = newItem.copy(progressPct = 0, isIndeterminate = false, transferredBytes = 0L, speedBps = 0.0)
            if (o == n) return PROGRESS_PAYLOAD
        }
        if (oldItem is TransferRowState.Group && newItem is TransferRowState.Group) {
            val o = oldItem.copy(progressPct = 0, isIndeterminate = false, filesDone = 0)
            val n = newItem.copy(progressPct = 0, isIndeterminate = false, filesDone = 0)
            if (o == n) return PROGRESS_PAYLOAD
        }
        return null
    }
}

/** Marker payload: only the live progress fields changed — see
 *  TRANSFER_ROW_DIFF.getChangePayload. */
private val PROGRESS_PAYLOAD = Any()

/** ── SELECTION FINAL PASS 1 (§7): the transfer rows' selection-only rebind ──
 * payload (the sibling of PROGRESS_PAYLOAD — see TransferRowAdapter's own
 * notifyRowSelectionChanged). */
private val SEL_ROW_PAYLOAD = Any()

/**
 * Top-level (not nested inside TransferRowAdapter) because Kotlin does not
 * allow a plain nested class declaration inside an `inner class` --
 * that's the exact cause of the "Class is not allowed here" compile error
 * this fixes. A plain top-level class is also simply correct here: a
 * ViewHolder never needs access to the outer Fragment, so there was never
 * a reason for it to be inner in the first place.
 *
 * Phase 5 (§5.1 audit): now also carries the row's in-flight thumbnail
 * decode job so a rebind or recycle CANCELS it — the same discipline the
 * Media Picker's grid already has (see its VH.decodeJob). The tag guard
 * in loadThumbnailInto already kept a late bitmap off the wrong row
 * visually; this additionally stops the decode itself from burning a
 * ThumbnailEngine permit after its row is gone, which is exactly the
 * orphaned-work class of bug that made the picker freeze before its own
 * Phase 1 fix.
 */
private class TransferRowViewHolder(itemView: LinearLayout) : RecyclerView.ViewHolder(itemView) {
    var thumbJob: kotlinx.coroutines.Job? = null
}

class TransfersFragment : Fragment() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentTab = "active"
    // ── FULL FINAL PASS 2 (§3): the badge-hold mechanism is GONE. FULL FINAL ──
    // PASS 1 kept an automatically-landed tab's unseen badge alive until a
    // "genuine" visit; the user explicitly wants the ORIGINAL behavior
    // back — landing on the Received tab (automatically or by hand) IS a
    // visit and consumes the badge. selectTab and updateTabBadges clear on
    // entry exactly as they did before that change; no hold state exists.
    // ── Pass-38 (#7): this tab's compact music-pill dock (built in
    // onCreateView, registered with MusicPlayback while this tab is the
    // visible one — see the header-row construction).
    private var musicPillSlot: android.widget.FrameLayout? = null
    // ── Pass-16 (the "[Time] Ago" refresh cadence) ────────────────────────────
    // 30s keeps every label on the tab within one granularity step of the
    // truth ("4m" stays "4m" for a whole minute, so a 30s refresh can never
    // read stale) while costing at most one string compare per visible row
    // per tick — nothing measurable even on Android 5 hardware.
    private val AGO_TICK_MS = 30_000L
    private val server: LumiDropServer? get() = ServerForegroundService.instance?.server

    private lateinit var globalControlsRow: LinearLayout
    // Final Stabilization (§6): compact bulk-controls state — the live
    // summary label and the currently-open management sheet.
    private var activeSummaryLabel: TextView? = null
    private var bulkMenuSheet: BottomSheet? = null
    private lateinit var tabActiveBtn: TabPill
    private lateinit var tabReceivedBtn: TabPill
    private lateinit var tabSentBtn: TabPill
    private lateinit var tabActiveBadge: TextView
    private lateinit var tabReceivedBadge: TextView
    private lateinit var tabSentBadge: TextView

    private lateinit var activeTabContent: LinearLayout
    private lateinit var transferList: RecyclerView
    // ── GOAT-PASS 3 (§7): the Active tab's floating "Clear Failed" pill and
    // its label — nullable because they're built during view construction
    // (see buildClearFailedPill) and every accessor guards on null.
    private var clearFailedPill: LinearLayout? = null
    private var clearFailedLabel: TextView? = null
    // Pass-8 follow-up (consistent scroll fades): the top/bottom edge-fade
    // bands over each subtab's list — the exact treatment the Connected
    // Devices card's list has (same drawables, same visibility rule, same
    // short crossfade). Nullable because they're assigned during view
    // construction; updateTabEdgeFades() guards on null.
    private var activeFadeTop: View? = null
    private var activeFadeBottom: View? = null
    private lateinit var activeEmptyLabel: LinearLayout

    private lateinit var receivedTabContent: LinearLayout
    private lateinit var receivedList: RecyclerView
    private var receivedFadeTop: View? = null
    private var receivedFadeBottom: View? = null
    private lateinit var receivedEmptyLabel: LinearLayout
    // Phase 5 (§5.4): the Received tab's header row — its contents are
    // rebuilt by refreshReceivedHeader() depending on selection mode.
    private lateinit var receivedHeaderRow: LinearLayout
    private lateinit var sentTabContent: LinearLayout
    private lateinit var sentList: RecyclerView
    private var sentFadeTop: View? = null
    private var sentFadeBottom: View? = null
    private lateinit var sentEmptyLabel: LinearLayout

    // GOAT (transfer stability pass — large folder transfers): one adapter
    // instance per tab. See TransferRowAdapter's own doc comment for why
    // this replaced the old hand-rolled diffRenderList(LinearLayout, ...) —
    // in short, that function only ever inserted new rows and removed gone
    // ones; it never moved an existing row to its new position, so the
    // on-screen order silently drifted from TransferManager's actual order
    // the moment anything was re-sorted (which is exactly what happens
    // constantly with a few hundred files in flight). RecyclerView +
    // ListAdapter + DiffUtil is a real, well-tested implementation of the
    // same "diff two lists, apply the minimal set of changes" idea this
    // code was already trying to hand-roll — including MOVE, which is the
    // part that was missing — plus actual view recycling, which a plain
    // LinearLayout inside a ScrollView never had: with a few hundred rows,
    // every single one stayed inflated and alive in memory at once,
    // whether on screen or not.
    private val activeAdapter = TransferRowAdapter(groupsExpandable = false, selectionTab = "active", selectionCapable = true)
    private val receivedAdapter = TransferRowAdapter(groupsExpandable = true, selectionTab = "received", selectionCapable = true)
    // ── Pass-28 (#1.1): the History tab joins the SAME hold-to-select system ──
    // the Received tab has carried since Phase 5 — long-press enters
    // selection, taps toggle, the header swaps to the "N selected ·
    // Delete / Cancel" bar. selectionCapable=true + selectionTab="sent"
    // wires the identical machinery to History's rows and its own selection
    // set; deletion routes through the record-removal the tab's per-row ✕
    // already performs (see confirmDeleteSentSelection).
    private val sentAdapter = TransferRowAdapter(groupsExpandable = false, selectionTab = "sent", selectionCapable = true)

    // Phase 5 (§5.4 — multi-item delete in Received): long-press any row to
    // enter selection mode; taps toggle rows; the header swaps to a
    // "N selected · Delete / Cancel" bar; one confirmation covers the whole
    // set (files deleted directly, folders recursively). Selection is keyed
    // by the SAME stable rowKey DiffUtil uses, so toggles re-diff cleanly
    // through the existing snapshot machinery instead of any second,
    // parallel row-tracking system. lastReceivedRows is the most recent
    // submitted row list — re-submitting it is what makes a toggle visible
    // immediately instead of waiting for the next 2s reconcile scan.
    // Pass-28 (#1.1): the History tab carries its own parallel trio below —
    // same mechanisms, independent state, so the two tabs never fight over
    // one key set.
    private var receivedSelectionMode = false
    private val receivedSelectionKeys = mutableSetOf<String>()
    private var lastReceivedRows: List<TransferManager.TransferRowItem> = emptyList()

    // ── Pass-28 (#1.1): the History ("sent") tab's selection state ─────────────
    private var sentSelectionMode = false
    private val sentSelectionKeys = mutableSetOf<String>()
    private var lastSentRows: List<TransferManager.TransferRowItem> = emptyList()

    // ── GOAT-PASS 15 (§9): the ACTIVE tab's selection state — the same trio ────
    //  of mechanisms Received and History have used since Pass-28 (long-press
    //  entry with the pressed row selected, plain taps toggling inside the
    //  mode, range anchoring, Select All over the tab's own eligibility, drag
    //  select in Hold & Slide mode), with its own independent key set so the
    //  three tabs never contaminate each other. The ACTIVE mode's eligibility
    //  and its toolbar's actions are transfer-lifecycle ones (Pause / Resume /
    //  Cancel of live work), not the deletion contract the other two tabs
    //  carry — see isRangeSelectableIn and buildSelectionActionBar's "active"
    //  branch. Rows that complete, fail or vanish drop out of the set on the
    //  tab's own refresh (renderActiveTab's retainAll), exactly like the
    //  Received reconcile does for its keys.
    private var activeSelectionMode = false
    private val activeSelectionKeys = mutableSetOf<String>()
    private var lastActiveRows: List<TransferManager.TransferRowItem> = emptyList()
    private var activeAnchorKey: String? = null
    /** The ACTIVE selection toolbar's live count chip (patched in place by
     *  refreshActiveHeader on every selection mutation). */
    private var activeSelCountLabel: TextView? = null
    /** Tracks which face the Active header row was last built with (null =
     *  never built) — the same nullable-flag discipline refreshReceivedHeader
     *  uses so a recreated view always builds fresh. */
    private var activeHeaderIsSelection: Boolean? = null

    // ── Pass-28 (#1.3): the History header's live count label — rebuilt only
    // when the header crosses the selection-mode boundary, text-patched on
    // every render after that (no per-tick view churn). The nullable mode
    // flag starts null (= "never built") so the very first refresh ALWAYS
    // constructs the header, not just on a mode flip.
    private lateinit var sentHeaderRow: LinearLayout
    private var sentHeaderIsSelection: Boolean? = null
    private var sentHeaderCountLabel: TextView? = null
    /** ── GOAT-PASS 12 (§2): the HISTORY selection pill's live count chip — ──
     *  the exact same minimalist number-only capsule the Received tab's pill
     *  carries (GOAT-PASS 11 §10), patched in place by refreshSentHeader on
     *  every selection change. */
    private var sentSelCountLabel: TextView? = null

    // Phase 5 (§5.1 audit — expanded-folder child churn): signature of the
    // children list currently rendered inside the expanded group row. The
    // 2s reconcile re-submits identical-looking group rows constantly while
    // a folder sits expanded (aggregate progress ticks), and the OLD code
    // rebuilt every child row on every such rebind — a 300-file folder meant
    // 300 view rebuilds every 2 seconds for nothing. Children now rebuild
    // only when one of their own (id, status, progress, path) tuples
    // actually changed, which is what the visual state depends on.
    private var lastGroupChildrenSig: String? = null

    // GOAT (Part 2 — folder as a single transfer item): which received
    // folder-group row is currently expanded, keyed by "group:$groupId".
    // Lives here, on the Fragment, and nowhere else — same reasoning as
    // the incoming sheet's own expandedIncomingFolderKey: every row gets
    // rebuilt from its current TransferRowState on every refresh, so state
    // that lived as a plain local var inside the row-builder would reset
    // to collapsed on the very next poll tick.
    //
    // GOAT v4 (accordion, per the person's on-device report): exactly ONE
    // group may be expanded at a time — expanding another collapses the
    // first automatically. The old Set became a single key plus the live
    // FolderBox/chevron of the row currently displaying it (for the
    // in-place animated collapse; nulled by any rebind that reuses that
    // card for different content — see updateRow/updateGroupRow).
    private var expandedGroupKey: String? = null
    // ── Pass-39 (#4): which NESTED subfolders of an expanded group are ──
    // expanded, keyed by "<group-key>::<subfolder relPath>" — survives the
    // ~2s rescans and recycled-row rebinds, scoped per group.
    private val expandedGroupSubKeys = java.util.HashSet<String>()

    // ── Pass-39 (#7): the Received and History tabs' sort state —─
    // loaded from (and persisted to) SettingsManager's per-tab "key:dir"
    // strings. Sorting is applied to the DISPLAY list only; the transfer and
    // history records are never rewritten for a different order.
    private var receivedSortKey = "date"; private var receivedSortDesc = true
    private var sentSortKey = "date"; private var sentSortDesc = true
    private var expandedGroupBox: FolderBox? = null
    private var expandedGroupChevron: ImageView? = null

    private val refresh = object : Runnable {
        override fun run() {
            if (view == null) return
            renderCurrentTab()
            updateTabBadges()
            val active = TransferManager.activeCount()
            handler.postDelayed(this, if (active > 0) 500L else 2000L)
        }
    }

    // ── GOAT-PASS 7 (§2A — event-driven UI sync, the wiring half) ───────────
    // The engine's onUiStateNudge (fired by every lifecycle mutation and every
    // 100 ms-throttled progress touch — see TransferManager.touchT) lands here
    // as a COALESCED render request: at most one render every ~120 ms, posted
    // on the main-thread handler, regardless of how hot the engine runs. The
    // periodic [refresh] tick above stays exactly as it is — the nudge makes
    // transitions INSTANT (a completing item, the next row flipping ACTIVE, its
    // first bytes, a brand-new transfer while this tab idled at its 2 s
    // cadence), while the tick remains the steady backstop that re-schedules
    // itself and keeps the "[Time] ago" labels and tab badges honest. The
    // runnable re-checks liveness (view + resumed + not hidden) so a nudge
    // that lands between lifecycle states is a clean no-op, and the refresh
    // tick's own self-re-posting is untouched — the two schedules coexist
    // because both go through the same handler and the tick re-posts itself
    // on every run (a nudge never strands the tick: at worst one extra tick
    // fires within 120 ms of the last one).
    private val nudgeRender = Runnable {
        if (view == null || !isResumed || isHidden) return@Runnable
        renderCurrentTab()
        updateTabBadges()
    }
    private fun wireUiNudge() {
        TransferManager.onUiStateNudge = {
            // Called from engine threads — hop to this fragment's main-thread
            // handler with coalescing (removeCallbacks + postDelayed = one
            // pending render max, at a bounded ~8 Hz even under full-speed
            // progress churn).
            handler.removeCallbacks(nudgeRender)
            handler.postDelayed(nudgeRender, 120L)
        }
    }
    private fun unwireUiNudge() {
        handler.removeCallbacks(nudgeRender)
        // TransfersFragment is this build's sole engine-driven surface; the
        // slot is released outright so a paused screen never holds a stale
        // main-thread hop alive. A future second consumer would swap this for
        // an identity-checked detach.
        TransferManager.onUiStateNudge = null
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()
        server?.let { } ?: run { /* server may not be up yet; rows just won't have data until it is */ }

        // Phase 18: root is plain MATCH_PARENT (not wrapped in a
        // NestedScrollView like Send/Settings) so the content card below
        // can use real layout_weight to fill remaining space exactly, the
        // way the prototype's Modifier.weight(1f) does -- Transfers has
        // exactly two cards and no variable-count conditional ones the way
        // Send does, so there's no competing need for the whole page to
        // scroll; only the list inside the content card scrolls, via its
        // own ScrollView per tab (unchanged from before).
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(col(R.color.bg_deep))
            clipChildren = false
            clipToPadding = false
            setPadding(dp(16), dp(14), dp(16), resources.getDimensionPixelSize(R.dimen.nav_bar_clearance))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            // ── Pass-27 (#4.1): this tab's bottom clearance tracks the LIVE ──────
            // navigation-bar inset (edge-to-edge main window on API 30+): with the
            // Android buttons visible the clearance grows by exactly their zone —
            // the space the window frame itself used to reserve — and the moment
            // the "Hide Navigation Bar" preference hides them, the inset drops to
            // 0 and the content card fills down to the floating tab bar with no
            // dead band. No-op below API 30 (the legacy frame handles both states).
            trackNavBarBottomPadding(resources.getDimensionPixelSize(R.dimen.nav_bar_clearance))
        }

        // ══════ TOP CARD: header + tab pills (prototype's first
        // LumiDropCard in TransfersScreen) ══════
        val topCardInner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            layoutParams = lp(MATCH, WRAP)
        }
        val topCard = Widgets.sectionCard(ctx, paddingDp = 0).apply {
            layoutParams = lp(MATCH, WRAP).also { it.bottomMargin = dp(16) }
            addView(topCardInner)
        }

        // Leading icon: the prototype uses a back arrow here, but Transfers
        // is a bottom-nav peer tab with nothing to "go back" to -- a back
        // affordance that goes nowhere would be a real navigation bug, not
        // faithful reproduction. ic_swap (already used in this exact spot
        // pre-Phase-18) fills the same leading-icon slot without that problem.
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(MATCH, WRAP)
        }
        headerRow.addView(ImageView(ctx).apply {
            setImageDrawable(Widgets.icon(ctx, R.drawable.ic_swap, col(R.color.text_primary)))
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).also { it.marginEnd = dp(8) }
        })
        headerRow.addView(TextView(ctx).apply {
            text = "Transfers"
            textSize = 26f; setTextColor(col(R.color.text_primary))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            // ── Pass-39 (#9): the title takes its natural width on ONE line ──
            // (maxLines=1 + END ellipsis as the ultra-narrow safety) — the old
            // weighted title squeezed below its text width whenever the fixed
            // 152dp pill dock claimed its space, and the title WRAPPED onto a
            // second row, growing the header. The pill dock below is now the
            // row's FLEXIBLE remainder instead, and the pill itself sizes to
            // fit it (MusicPlayback.fitPillToHost caps it at 152dp and shrinks
            // it with the available width) — the header keeps its exact
            // single-line height on every screen.
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        // ── Pass-38 (#7) + Pass-39 (#9): THIS tab's compact music-pill dock — ──
        // the flexible remainder of the header row (never a fixed 152dp), so
        // the pill shrinks with the space instead of pushing the title to a
        // second line. GONE while empty; the pill right-aligns inside it.
        val musicSlot = android.widget.FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f).also { it.marginStart = dp(10) }
            visibility = View.GONE
        }
        musicPillSlot = musicSlot
        headerRow.addView(musicSlot)
        topCardInner.addView(headerRow)

        val tabRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(MATCH, WRAP).also { it.topMargin = dp(24) }
        }
        tabActiveBtn   = tabButton("Active")   { selectTab("active") }
        tabReceivedBtn = tabButton("Received") { selectTab("received") }
        // Pass-11 (Required Implementation 1): the Sent pill is now HISTORY —
        // a single, professional record of everything that went either way:
        // files this device received (the same disk-reconciled truth the
        // Received tab shows) AND files this device sent (native pushes and
        // browser downloads). The internal tab id stays "sent" so the rest
        // of the tab machinery (selection state, badges, visibility wiring)
        // is untouched; only the label and the list's contents changed.
        tabSentBtn     = tabButton("History")   { selectTab("sent") }
        val (activeWrap, activeBadge) = wrapWithBadge(tabActiveBtn)
        val (receivedWrap, receivedBadge) = wrapWithBadge(tabReceivedBtn)
        val (sentWrap, sentBadge) = wrapWithBadge(tabSentBtn)
        tabActiveBadge = activeBadge; tabReceivedBadge = receivedBadge; tabSentBadge = sentBadge
        tabRow.addView(activeWrap); tabRow.addView(receivedWrap); tabRow.addView(sentWrap)
        topCardInner.addView(tabRow)
        root.addView(topCard)

        // ══════ CONTENT CARD: global controls + whichever tab is active
        // (prototype's second LumiDropCard, Modifier.weight(1f)) ══════
        val contentCard = Widgets.sectionCard(ctx, paddingDp = 0).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            // Pass-3 (R2 — last item stays inside the card): the card clips
            // its children to its own rounded outline. The Active/Received/
            // Sent lists scroll inside this card; without this, rows drawn
            // during scroll/overscroll could paint over the card's rounded
            // bottom corners — the reported "clipping and overflow" —
            // because clipToPadding=false on the RecyclerViews lets rows
            // render into the padding zone. clipToOutline (API 21+, our
            // minSdk) is the hard, shape-accurate boundary.
            clipToOutline = true
        }
        val contentInner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            // Pass-3 (R2): a real bottom inset (was 0) — the scrollable tab
            // content ends 12dp above the card's bottom edge, so even with
            // clipToPadding=false rows gliding through the RV's own 16dp
            // bottom padding never touch the card's rounded corner zone.
            setPadding(dp(8), dp(16), dp(8), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, MATCH)
        }
        contentCard.addView(contentInner)
        root.addView(contentCard)

        globalControlsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), 0, dp(6), dp(10))
            layoutParams = lp(MATCH, WRAP)
            visibility = View.GONE
        }
        // Part 4 fix (Active Tab Controls sizing): smallActionBtn()'s own
        // default is WRAP_CONTENT width, which is correct for its OTHER
        // call sites (a single Retry/Install/Open Folder/Clear button
        // sharing a row with unrelated content) but wrong here, where
        // three of them sit side by side with nothing to stop their
        // combined natural width from exceeding the card on narrower
        // screens -- a plain HORIZONTAL LinearLayout doesn't wrap, so
        // overflow just clips past the edge instead. Overriding just these
        // three instances to share the row equally (same weighted-row
        // idiom as the Settings mode toggle and elsewhere) fixes the
        // overflow without touching smallActionBtn's other callers. The
        // ellipsize/maxLines safety net only matters at extreme system
        // font-scale settings -- at normal scale these labels fit their
        // third of the row on every screen this app targets.
        // Final Stabilization (§6): the three full-width bulk buttons are
        // gone. In their place: a slim contextual line — a live summary of
        // what is actually running — with ONE compact overflow (⋮) control
        // that opens a BottomSheet action menu carrying the same three bulk
        // operations (and nothing else). The row keeps the exact visibility
        // rule it always had (Active tab + at least one live transfer), so
        // no dead chrome appears on an idle queue.
        fun bulkRow(): LinearLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(MATCH, WRAP)
        }
        activeSummaryLabel = TextView(ctx).apply {
            textSize = 12f
            setTextColor(col(R.color.text_secondary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val manageBtn = Widgets.iconButton(ctx, R.drawable.ic_more, col(R.color.text_primary), sizeDp = 38, iconSizeDp = 18).apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_icon_ripple)
            contentDescription = "Manage transfers"
            // ── GOAT-PASS 15 (§9.2): the header swap hook — refreshActiveHeader
            // hides/shows this exact view by tag when the selection toolbar
            // takes the row.
            tag = "manage_btn"
            layoutParams = LinearLayout.LayoutParams(dp(38), dp(38)).also { it.marginStart = dp(8) }
            setOnClickListener { showBulkTransferMenu() }
        }
        globalControlsRow.addView(bulkRow().apply {
            setPadding(dp(6), 0, dp(6), dp(10))
            addView(activeSummaryLabel)
            addView(manageBtn)
        })
        contentInner.addView(globalControlsRow)

        activeTabContent = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            // GOAT (Phase 3, item 9 -- list clipping through/behind the
            // header): explicit rather than relied-on-as-default, since
            // contentInner one level up deliberately sets clipChildren =
            // false (individual row cards need that, for shadows that
            // render slightly past their own bounds) -- this container is
            // the actual boundary between globalControlsRow and the
            // scrolling list, so it's the one place that specifically
            // needs the opposite. Couldn't isolate the exact mechanism
            // (overscroll stretch vs. a transient post-visibility-toggle
            // layout pass are both plausible) without seeing it happen --
            // this closes every version of it that clipping the boundary
            // can close, but flagging the uncertainty rather than
            // presenting it with the same confidence as items 7/11.
            clipChildren = true
        }
        activeEmptyLabel = emptyState(
            "No transfers right now.",
            "Files being sent or received will appear here.")
        activeTabContent.addView(activeEmptyLabel)
        transferList = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = activeAdapter
            clipToPadding = false
            setPadding(dp(6), 0, dp(6), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            // RecyclerView implements NestedScrollingChild correctly (see
            // the identical reasoning already established for the
            // Connected Devices / Queue lists in fragment_send.xml) — a
            // plain ScrollView does not, which is part of what let a few
            // hundred rows measure/lay out all at once instead of only the
            // ones actually on screen.
            isNestedScrollingEnabled = true
            // GOAT v3 (§3.1 — the Transfers screen's own "glitchy"
            // contributor): these lists rebind rows through DiffUtil on
            // every poll tick (500ms while anything is active) the moment
            // a row's snapshot changes — which is exactly what a live
            // progress UI SHOULD do. DefaultItemAnimator's change animation
            // is a full cross-fade of the row, though, so an actively-
            // transferring folder's row was fading out and back in twice a
            // second, stacking on top of the expand/collapse path's old
            // cross-fade (see updateGroupRow's click listener for the other
            // half of this). Live-updating lists should swap content in
            // place: supportsChangeAnimations = false keeps add/remove/move
            // animations and renders in-place rebinds as an instant content
            // swap — the correct behavior for data that changes this often.
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }
        val activeListWrap = wrapListWithEdgeFades(transferList).also {
            activeFadeTop = it.getChildAt(1) as? View
            activeFadeBottom = it.getChildAt(2) as? View
        }
        activeTabContent.addView(activeListWrap)
        // ── GOAT-PASS 3 (§7): the floating “Clear Failed” pill, overlaid on ──
        // the Active tab's list (bottom-end, above the edge fades and every
        // row). Visible only while the live map holds a meaningful number of
        // ELIGIBLE failed rows (genuine failures — interrupted/
        // kept-for-resume rows are the reconnect story and stay); one tap
        // clears them all, repeated taps are naturally idempotent, and it
        // hides the moment nothing eligible remains. See updateClearFailed-
        // Pill for the visibility rule and dismissAllFailedEligible for the
        // engine half.
        clearFailedPill = buildClearFailedPill().also { activeListWrap.addView(it) }
        // ── GOAT-PASS 15 (§9): the Active list joins the same hold-and-slide +
        // range-anchor selection gestures the other two tabs use.
        attachTabDragSelect(transferList, "active")
        contentInner.addView(activeTabContent)

        val recv = buildHistoryTab(receivedAdapter,
            "No received files",
            "Files sent to this device from other devices will appear here.")
        receivedTabContent = recv.content; receivedList = recv.list; receivedEmptyLabel = recv.emptyLabel
        receivedHeaderRow = recv.header
        receivedFadeTop = recv.listWrap.getChildAt(1) as? View
        receivedFadeBottom = recv.listWrap.getChildAt(2) as? View
        contentInner.addView(receivedTabContent)
        // ── GOAT-PASS 7 (§3B + §3D): hold-and-slide selection with edge ──
        // auto-scroll on the Received tab's list — the same shared gesture
        // the Files and Music sheets use (see attachTabDragSelect).
        attachTabDragSelect(receivedList, "received")
        // Phase 5 (§5.4): install the selection-aware header (normal mode on
        // entry) as the single source for this tab's header from here on.
        // Pass-28 (#1.3): buildHistoryTab now builds the header EMPTY —
        // refreshReceivedHeader owns every view in it (see the function).
        // ── GOAT-PASS 8 (§1): a (re)created view ALWAYS builds its header ──
        // fresh — the mode flag is reset here so a leftover value from a
        // previous view can never suppress the first build against this
        // brand-new, empty header container.
        receivedHeaderIsSelection = null
        refreshReceivedHeader()

        // Pass-11 (History tab): shows ALL completed transfers — received AND
        // sent, rendered from the DURABLE HistoryStore merged with live rows.
        // "Clear History" clears the record for BOTH directions at once (the
        // received half of the disk-reconciled view represents real files that
        // live in the receive folder; removing those is the Received tab's
        // "Delete Received Files" job).
        val sent = buildHistoryTab(sentAdapter,
            "No history yet",
            "Files this device received and sent will appear here.")
        sentTabContent = sent.content; sentList = sent.list; sentEmptyLabel = sent.emptyLabel
        sentHeaderRow = sent.header
        sentFadeTop = sent.listWrap.getChildAt(1) as? View
        sentFadeBottom = sent.listWrap.getChildAt(2) as? View
        contentInner.addView(sentTabContent)
        // ── GOAT-PASS 7 (§3B + §3D): the History tab's list gets the same ──
        // hold-and-slide + edge auto-scroll treatment as Received (see
        // attachTabDragSelect).
        attachTabDragSelect(sentList, "sent")
        // ── Pass-28 (#1.1/#1.3): install the History tab's own selection-aware ──
        // header — the normal-mode face carries the live total item count
        // (replacing the retired "Tap a file to open it" hint), and selection
        // mode swaps it for the compact Cancel · Delete · Select All pill the
        // Received tab uses. renderHistoryList keeps both figures current.
        // ── GOAT-PASS 8 (§1): same view-recreation reset as the Received ──
        // tab's header flag.
        sentHeaderIsSelection = null
        refreshSentHeader()

        // ── Pass-39 (#7): the two tabs' persisted sort settings —─
        // date-descending (newest first) is the default both tabs implied.
        fun loadSort(tab: String): Pair<String, Boolean> {
            val parts = com.lumidrop.x.settings.SettingsManager.getTransfersSort(ctx, tab).split(":")
            return (parts.getOrNull(0) ?: "date") to ((parts.getOrNull(1) ?: "d") != "a")
        }
        loadSort("received").let { receivedSortKey = it.first; receivedSortDesc = it.second }
        loadSort("sent").let { sentSortKey = it.first; sentSortDesc = it.second }
        // ── SELECTION FINAL PASS 1 (#13.2 — the entry default is an AUTOMATIC ──
        // landing) → FULL FINAL PASS 2 (§3): the landing clears the badge
        // exactly like a manual visit — the restored original behavior.
        selectTab(initialTab())
        // ── Pass-38 (#7): this tab's header slot joins the music pill's dock ──
        // pool — but ONLY when this tab is actually the visible one. All three
        // tabs create their views during the shell's single setup transaction
        // (add + hide together), and an unguarded attach here would leave the
        // LAST-created tab's slot holding the pill while Send is on screen.
        // The hidden flag is set before the state moves, so !isHidden is the
        // exact truth; onHiddenChanged re-registers on every later entry.
        if (!isHidden) com.lumidrop.x.ui.MusicPlayback.attachCompactHost(musicPillSlot)
        return root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // ── Pass-38 (#7): the dock dies with this view — release it before
        // the slot reference does, so the player never holds a dead slot.
        musicPillSlot?.let { com.lumidrop.x.ui.MusicPlayback.detachCompactHost(it) }
        musicPillSlot = null
    }

    /** Pass-8 (§14 — automatically select the appropriate transfer tab):
     *  entering the Transfers screen opens Active when anything is actually
     *  in flight (PENDING or ACTIVE — waiting/paused work belongs on Active
     *  too), and Received otherwise. Evaluated ONCE per screen ENTRY (initial
     *  creation and every show from hidden) from the authoritative engine
     *  state, never on progress events, so a manual selection is never
     *  overridden while the screen stays open. No flicker: the decision runs
     *  before the first tab content is bound. */
    private fun initialTab(): String =
        if (TransferManager.activeCount() > 0) "active" else "received"

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // ── FINAL PASS 9 (PART 1): with configChanges declared there is NO
        // automatic re-render on rotation — the lists re-measure (the
        // framework pass) but every derived surface (edge fades, the
        // LdxScrollbar thumb, the auto-fitted metadata lines, tab badges)
        // only refreshes on the next poll tick. One explicit re-render on
        // the new configuration makes the rotated frame correct at once.
        if (view != null && !isHidden) { handler.post(nudgeRender); updateTabEdgeFades() }
    }

    override fun onResume() {
        super.onResume()
        wireUiNudge()   // GOAT-PASS 7 (§2A): engine changes render instantly while resumed
        if (!isHidden) handler.post(refresh)
        startAgoTicker()
    }

    override fun onPause() {
        super.onPause()
        unwireUiNudge()
        handler.removeCallbacks(refresh)
        stopAgoTicker()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            handler.removeCallbacks(refresh); handler.removeCallbacks(nudgeRender); stopAgoTicker()
        }
        else {
            // ── Pass-38 (#7): this tab is the visible one — its header slot ──
            // is the pill's dock now (one shared player, one compact pill,
            // whichever tab is on screen).
            com.lumidrop.x.ui.MusicPlayback.attachCompactHost(musicPillSlot)
            // ── Pass-27 (#4.1): a hidden tab may have missed an insets dispatch ──
            // while the navigation-bar inset changed — re-request one so this
            // tab's bottom clearance is re-derived against the live geometry
            // (idempotent; a no-op when nothing changed and on API < 30).
            try { view?.requestApplyInsets() } catch (e: Exception) { /* not attached */ }
            // Pass-8 (§14): re-apply the entry default each time the screen is
            // (re-)entered from hidden — while it stays open, the user's own
            // manual selection is never touched (selectTab is only ever called
            // from a pill tap otherwise).
            // ── FULL FINAL PASS 2 (§3): automatic landing consumes the badge ──
            // too — the original behavior, explicitly requested back.
            selectTab(initialTab())
            if (isResumed) handler.post(refresh)
            startAgoTicker()
        }
    }

    // ── Pass-16 ("[Time] Ago" labels that actually keep time) ─────────────────
    // The relative labels rendered into terminal rows ("4m ago", "3h ago")
    // used to be written ONCE at bind time and never again — a terminal row
    // doesn't bump the engine epoch, so nothing ever re-rendered it and the
    // text literally got stuck at whatever it said when the row first bound
    // (the reported "labels get stuck and don't update"). The fix is a
    // 30-second in-place ticker: terminal rows' detail TextViews register
    // themselves at bind time, and the tick recomputes JUST the detail line
    // (a string compare + at most a setText per visible row — no adapter
    // rebind, no diff, no layout). Live rows never register: they re-render
    // on every real change anyway, and their exact byte/speed readout must
    // not be throttled. Recycled/re-bound rows overwrite their entry; a
    // detached TextView is dropped on the next sweep, so the registry can
    // never grow past the number of visible rows.
    // ── GOAT-PASS 10 (§5): the registry now holds a detail-line SUPPLIER ──
    // per registered view, not a bare Transfer — folder GROUP rows carry an
    // ago label now too, and their line is built from the group's live
    // members rather than one Transfer. The captured member/transfer objects
    // are the engine's own in-place-mutated instances, so every tick reads
    // genuinely current state; the supplier's view-identity key keeps the
    // recycled-row overwrite semantics unchanged.
    private val agoTargets = java.util.concurrent.ConcurrentHashMap<TextView, () -> String>(16)
    private val agoTicker = object : Runnable {
        override fun run() {
            // Pass-22 (compile fix): this fragment builds its view tree in
            // code (no ViewBinding) — the ticker's old `_binding` reference
            // never resolved and kept the app from compiling. `view` is the
            // Fragment's own live view, the same null-check discipline every
            // other path in this file already uses.
            if (view == null) return
            val iter = agoTargets.entries.iterator()
            while (iter.hasNext()) {
                val (tv, supplier) = iter.next()
                if (!tv.isAttachedToWindow) { iter.remove(); continue }
                val fresh = try { supplier() } catch (e: Exception) {
                    // a row whose transfer vanished mid-tick simply stops
                    // refreshing — never a crash off the ticker
                    iter.remove(); continue
                }
                if (tv.text.toString() != fresh) tv.text = fresh
            }
            handler.postDelayed(this, AGO_TICK_MS)
        }
    }
    private var agoTickerRunning = false
    private fun startAgoTicker() {
        if (agoTickerRunning) return
        agoTickerRunning = true
        handler.postDelayed(agoTicker, AGO_TICK_MS)
    }
    private fun stopAgoTicker() {
        agoTickerRunning = false
        handler.removeCallbacks(agoTicker)
    }
    /** Registers a terminal row's detail line for the in-place refresh —
     *  live rows are deliberately skipped (they own a live progress readout
     *  that re-renders on every engine change already).
     *  ── FINAL PASS 5 (§2): [hidePeer] rides the registered closure so a
     *  Received-tab row's ticker refreshes keep omitting the sender-origin
     *  segment exactly like its initial paint (History rows keep it).
     *  ── FINAL PASS 6 (PART 5.2): [history] rides it too — the History
     *  tab's bare device-name wording stays put across ticks. */
    private fun registerAgoTarget(tv: TextView?, t: TransferManager.Transfer, hidePeer: Boolean = false, history: Boolean = false) {
        if (tv == null) return
        val terminal = t.status == Status.COMPLETED || t.status == Status.CANCELLED ||
            t.status == Status.DECLINED || t.status == Status.FAILED
        if (terminal) agoTargets[tv] = { detailLine(t, hidePeer, history) } else agoTargets.remove(tv)
    }

    /** ── GOAT-PASS 10 (§5): the folder-row twin — registers a FINISHED ──
     *  group's detail line (its new "[Time] ago" suffix is the one part of
     *  the line that changes with time). A group still in progress never
     *  registers, exactly like a live solo row.
     *  ── FINAL PASS 5 (§2): [hidePeer] rides the closure, same as the solo
     *  twin — the Received tab's ticker refreshes keep omitting the
     *  sender-origin segment. */
    private fun registerGroupAgoTarget(tv: TextView?, info: TransferManager.TransferGroupInfo, members: List<TransferManager.Transfer>, hidePeer: Boolean = false, history: Boolean = false) {
        if (tv == null) return
        val finished = members.isNotEmpty() && members.all { it.status == Status.COMPLETED }
        if (finished) {
            agoTargets[tv] = { groupDetailLine(info, members, members.count { it.status == Status.COMPLETED }, hidePeer, history) }
        } else {
            agoTargets.remove(tv)
        }
    }

    // Tabs --------------------------------------------------------------------------

    private fun selectTab(tab: String) {
        // Phase 5 (§5.4): leaving the Received tab exits selection mode —
        // the mode's tap/long-press contract is Received-specific, and a
        // stale selection surviving a tab switch would resurface with rows
        // whose identities may have been reconciled away in the meantime.
        // Pass-28 (#1.1): the History tab's selection gets the identical
        // rule — either tab's selection ends the moment the tab is left.
        if (currentTab == "received" && tab != "received" && receivedSelectionMode) exitReceivedSelection()
        if (currentTab == "sent" && tab != "sent" && sentSelectionMode) exitSentSelection()
        // ── GOAT-PASS 15 (§9): the Active tab's selection gets the identical
        // rule — leaving the tab ends the mode.
        if (currentTab == "active" && tab != "active" && activeSelectionMode) exitActiveSelection()
        // ── GOAT-PASS 12 (§6): entering Received or History ALWAYS opens ──
        // the list at the TOP — regardless of where the user last left it.
        // Two halves, deliberately:
        //   1. an IMMEDIATE scrollToPosition(0) so the tab paints at the top
        //      the moment it becomes visible (never flashing the old
        //      position first), and
        //   2. a one-shot commit-scroll (pendingEntryTopScroll) consumed by
        //      THIS entry's own render, so a data-restoring diff that would
        //      otherwise re-anchor the viewport cannot drag it back down.
        // After that one commit the flag is gone — normal scrolling while
        // the user stays in the tab is never touched again. selectTab is
        // the ONLY entry path (pill taps, view creation, and the
        // onHiddenChanged re-entry which re-selects the entry default), so
        // this fires exactly once per genuine tab visit.
        if (tab == "received" || tab == "sent") {
            val list = if (tab == "received") receivedList else sentList
            try {
                (list.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                    ?.scrollToPositionWithOffset(0, 0)
            } catch (e: Exception) { /* layout not ready yet — the commit half still covers it */ }
            pendingEntryTopScroll = tab
            if (tab == "received") {
                // The Received render is scan-throttled (2s); entering the
                // tab must not wait out a stale throttle window before its
                // data-restoring render (and the commit half of this reset)
                // runs — force the entry scan through.
                lastReceivedScanAtMs = 0L
            }
        }
        currentTab = tab
        activeTabContent.visibility   = if (tab == "active")   View.VISIBLE else View.GONE
        receivedTabContent.visibility = if (tab == "received") View.VISIBLE else View.GONE
        sentTabContent.visibility     = if (tab == "sent")     View.VISIBLE else View.GONE
        // ── FULL FINAL PASS 2 (§3 — visiting IS visiting): entering the ──
        // Received tab consumes its unseen badge whether the entry came from
        // a user pill tap or the screen's own automatic landing — the
        // original behavior, restored per the user's explicit correction of
        // FULL FINAL PASS 1's "genuine visit only" distinction. Entering
        // History consumes both its counters the same way.
        if (tab == "received") TransferManager.markReceivedSeen()
        if (tab == "sent") { TransferManager.markSentSeen(); TransferManager.markHistorySeen() }
        refreshTabStyles()
        renderCurrentTab()
    }

    private fun refreshTabStyles() {
        // Pass-12: the History pill carries the new circular-arrow history
        // glyph (ic_history); Received keeps its download arrow.
        // ── Pass-25 (#4.3): the ACTIVE pill now draws the activity-pulse ──
        // glyph (ic_activity) instead of the refresh arrows it shared with
        // the Connected Devices Refresh button and the server card's start
        // state — the three look-alike icons this pass breaks apart. "Active"
        // reads as live activity, not as "reload".
        val specs = listOf(
            Triple(tabActiveBtn, "active", R.drawable.ic_activity),
            Triple(tabReceivedBtn, "received", R.drawable.ic_download),
            Triple(tabSentBtn, "sent", R.drawable.ic_history)
        )
        for ((pill, tab, iconRes) in specs) {
            val isSel = currentTab == tab
            val tint = if (isSel) android.graphics.Color.WHITE else col(R.color.text_secondary)
            pill.background = Widgets.rippleRect(
                requireContext(),
                fillColor = if (isSel) col(R.color.accent_dark) else android.graphics.Color.TRANSPARENT,
                radiusDp = 20,
                strokeColor = if (isSel) null else col(R.color.bg_secondary)
            )
            pill.label.setTextColor(tint)
            // Pass-6 (R-F): icon and label are siblings now — the 18dp icon is
            // the ImageView's own dimensions (the old setCompoundDrawables
            // bounds dance existed only to shrink a 24dp intrinsic drawable).
            pill.icon.setImageDrawable(Widgets.icon(requireContext(), iconRes, tint))
        }
    }

    private fun updateTabBadges() {
        val activeCount = TransferManager.activeCount()
        tabActiveBadge.text = if (activeCount > 99) "99+" else activeCount.toString()
        tabActiveBadge.visibility = if (activeCount > 0) View.VISIBLE else View.GONE
        // ── FULL FINAL PASS 2 (§3): the Received badge clears whenever the ──
        // Received tab is the current tab — automatic landing included (the
        // restored original behavior; the FULL FINAL PASS 1 hold that kept an
        // auto-landed tab's badge alive is gone).
        if (currentTab == "received") TransferManager.markReceivedSeen()
        // ── SELECTION FINAL PASS 1 (#13.1): the HISTORY tab's badge carries ──
        // its OWN counter — sent AND received completions — cleared by its
        // own visit. (markSentSeen stays alongside for tidiness; the History
        // pill no longer reads that counter.)
        if (currentTab == "sent") {
            TransferManager.markSentSeen()
            TransferManager.markHistorySeen()
        }
        val unseenR = TransferManager.unseenReceived()
        val unseenH = TransferManager.unseenHistory()
        val ctx = context   // null-safe: a detached fragment never paints badges
        val receivedBadgesOn = ctx == null || com.lumidrop.x.settings.SettingsManager.isReceivedTabBadgeEnabled(ctx)
        val historyBadgesOn = ctx == null || com.lumidrop.x.settings.SettingsManager.isHistoryTabBadgeEnabled(ctx)
        tabReceivedBadge.text = if (unseenR > 99) "99+" else unseenR.toString()
        tabReceivedBadge.visibility = if (unseenR > 0 && receivedBadgesOn) View.VISIBLE else View.GONE
        tabSentBadge.text = if (unseenH > 99) "99+" else unseenH.toString()
        tabSentBadge.visibility = if (unseenH > 0 && historyBadgesOn) View.VISIBLE else View.GONE
    }

    private fun renderCurrentTab() {
        when (currentTab) {
            "active"   -> renderActiveTab()
            "received" -> renderReceivedTab()
            else       -> renderHistoryTab()
        }
        // ── GOAT-PASS 10 (§1 — the manage row must not vanish on a ──
        // WFR-only Active tab). The old rule gated on activeCount()
        // (PENDING + ACTIVE only), so a tab whose every card was a
        // receiver-side "Auto-Resume on Reconnection" partial
        // (FAILED + interrupted, kept-for-resume) — or a sender-side
        // interrupted retry — showed its cards but LOST the status row
        // and the ⋮ menu, stranding the "Clear Partial Received Items"
        // cleanup whose only enable condition is exactly those rows.
        // Visibility now keys on the SAME grouped snapshot the tab just
        // rendered (anything the Active tab can show, it can manage);
        // a genuinely empty Active tab still shows no dead chrome, and
        // the rule no longer depends on whether *ordinary* live
        // transfers exist.
        val activeRowsVisible = currentTab == "active" &&
            TransferManager.activeSnapshotGrouped().isNotEmpty()
        globalControlsRow.visibility = if (activeRowsVisible) View.VISIBLE else View.GONE
        refreshBulkSummary()
        // GOAT-PASS 3 (§7): keep the floating Clear Failed pill honest with
        // whatever the render just put on screen.
        updateClearFailedPill()
        // Pass-8 follow-up (consistent scroll fades): rows were just
        // (re)bound — the rims' scrollability may have changed with them.
        // A plain post() can run before the layout pass it depends on, so
        // the heavy lifting is the per-list GLOBAL LAYOUT listeners
        // installed by wrapListWithEdgeFades; this call keeps the CURRENT
        // tab honest at render time regardless.
        updateTabEdgeFades()
    }

    // ── GOAT-PASS 3 (§7): the floating “Clear Failed” pill ──────────────────

    /** ── FINAL PASS 3 (§8): the pill appears the moment ONE eligible ──
     *  failed row exists (the old threshold of 3 left 1–2 failures with no
     *  bulk affordance at all — "failed items exist" is exactly when the
     *  pill should be there). The per-row dismiss buttons remain for
     *  one-off cleanup either way. */
    private val clearFailedThreshold = 1

    private fun buildClearFailedPill(): LinearLayout {
        val ctx = requireContext()
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor((col(R.color.bg_deep) and 0x00FFFFFF) or 0xF2000000.toInt())   // GOAT-PASS 9 (build repair): the bare hex literal exceeds Int's signed range and never compiled
                setStroke(dp(1), (col(R.color.red_stop) and 0x00FFFFFF) or 0x40FFFFFF)
            }
            elevation = dp(8).toFloat()
            isClickable = true; isFocusable = true
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).also {
                it.bottomMargin = dp(18); it.marginEnd = dp(18)
            }
        }
        pill.addView(ImageView(ctx).apply {
            setImageDrawable(Widgets.icon(ctx, R.drawable.ic_delete, col(R.color.red_stop)))
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).also { it.marginEnd = dp(7) }
        })
        val label = TextView(ctx).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(col(R.color.red_stop))
        }
        clearFailedLabel = label
        pill.addView(label)
        pill.setOnClickListener {
            // Double-tap safe by construction: the dismiss is idempotent,
            // the immediate re-render updates the count (to zero — the pill
            // hides), and a second tap that lands before the render simply
            // finds nothing left to clear.
            val cleared = TransferManager.dismissAllFailedEligible()
            if (cleared > 0) {
                Toast.makeText(ctx, "Cleared $cleared failed item${if (cleared != 1) "s" else ""}", Toast.LENGTH_SHORT).show()
            }
            renderCurrentTab()
        }
        // ── FINAL PASS 3 (§8 — Resend Failed, in the same pill) ──────────
        // A hairline separator then the Resend segment: same height, same
        // minimalist capsule language as the Received/History selection
        // toolbar. Bulk resend routes EVERY eligible failed row through the
        // SAME normal mechanism its own per-row Resend uses (QS offers →
        // retryQuickShareForIp, which re-offers the item to its ORIGINAL
        // recipient IP and moves the row out of Failed into the normal
        // Waiting workflow; native sends → NativePushClient.retryFile, the
        // same session leg re-queued). No second protocol, no duplicate
        // request — each action is idempotent against a live entry, and a
        // row whose recipient is gone simply stays (its own toast says so).
        pill.addView(View(ctx).apply {
            setBackgroundColor(0x26FFFFFF)
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(16)).also {
                it.marginStart = dp(10); it.marginEnd = dp(10)
            }
        })
        pill.addView(TextView(ctx).apply {
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(col(R.color.accent_light))
            text = "Resend"
            setOnClickListener {
                var resent = 0; var kept = 0
                val doomed = ArrayList<String>()
                // FINAL PASS 3 (§8): activeSnapshot() is List<Transfer> (solo
                // rows flat, never TransferRowItem.Solo wrappers) — the
                // filterIsInstance variant compiled but always matched zero
                // rows. The eligible-failed predicate is the exact one
                // eligibleFailedCount applies, so the pill's count and its
                // Resend scope can never disagree.
                val rows = TransferManager.activeSnapshot()
                    .filter { it.status == Status.FAILED && !it.interrupted && it.id !in TransferManager.resumableRowIdsPublic() }
                for (t in rows) {
                    val ok = when {
                        t.source == Source.QUICK_SHARE && !t.qsItemName.isNullOrEmpty() ->
                            resendFailedRow(t.qsItemName!!, t.peerIp)
                        t.source == Source.NATIVE_PUSH && t.direction == Direction.SEND -> {
                            NativePushClient.retryFile(t.id); true
                        }
                        else -> false
                    }
                    if (ok) { resent++; doomed.add(t.id) } else kept++
                }
                for (id in doomed) TransferManager.dismiss(id)
                if (resent > 0) Toast.makeText(ctx, "Resending $resent item${if (resent != 1) "s" else ""}", Toast.LENGTH_SHORT).show()
                if (kept > 0) Toast.makeText(ctx, "$kept item${if (kept != 1) "s" else ""} can’t be resent (recipient gone)", Toast.LENGTH_SHORT).show()
                renderCurrentTab()
            }
        })
        return pill
    }

    /** ── FINAL PASS 3 (§8): one failed Quick-Share row's deliberate re-──
     *  offer to its ORIGINAL recipient — the per-row Resend's exact server
     *  call, shared with the pill's bulk Resend Failed so the two can never
     *  drift. False when the item is no longer re-offerable (queue gone —
     *  the recipient case the caller reports honestly). */
    private fun resendFailedRow(qsName: String, peerIp: String): Boolean =
        server?.retryQuickShareForIp(qsName, peerIp) ?: false

    /** Pill visibility rule: Active tab only, and only while at least
     *  [clearFailedThreshold] ELIGIBLE failed rows exist (genuine failures —
     *  interrupted / kept-for-resume rows are somebody's reconnect story and
     *  are never bulk-cleared). The label always carries the live count so
     *  the pill can never misrepresent what it will do. */
    private fun updateClearFailedPill() {
        val pill = clearFailedPill ?: return
        if (currentTab != "active") { pill.visibility = View.GONE; return }
        val n = TransferManager.eligibleFailedCount()
        // ── FINAL PASS 3 (§8): threshold is 1 — the pill exists the instant ──
        // the first eligible failed row does, and the nudge-driven render
        // (onUiStateNudge fires on every fail()) lands it within ~120 ms of
        // the state change, never waiting for the 2s idle tick.
        if (n >= clearFailedThreshold) {
            clearFailedLabel?.text = "Clear failed ($n)"
            pill.visibility = View.VISIBLE
        } else {
            pill.visibility = View.GONE
        }
    }

    // ── Pass-8 follow-up: consistent scroll-edge fades on every subtab ──────

    /** Wraps a subtab's RecyclerView in a FrameLayout carrying the same
     *  card-colored 18dp edge-fade bands the Connected Devices card's list
     *  uses (bg_fade_top / bg_fade_bottom — same drawables, same color as
     *  this content card's surface), visible only on the side(s) that
     *  actually have content past the rim. Child order is fixed:
     *  [0] = the list, [1] = the top band, [2] = the bottom band — the
     *  onCreateView/buildHistoryTab call sites read the bands back by
     *  index. A scroll listener per list plus ONE global-layout listener
     *  per list keep both bands honest through adapter growth, tab
     *  switches and rotation, with zero interaction required — the exact
     *  lesson SendFragment's pass-8 fade rework learned (a plain post()
     *  after each submit races the layout pass it depends on). */
    private fun wrapListWithEdgeFades(list: RecyclerView): FrameLayout {
        val ctx = requireContext()
        val wrap = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
        }
        list.layoutParams = FrameLayout.LayoutParams(MATCH, MATCH)
        wrap.addView(list)
        val fadeH = resources.getDimensionPixelSize(R.dimen.edge_fade_size)
        val top = View(ctx).apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_fade_top)
            layoutParams = FrameLayout.LayoutParams(MATCH, fadeH, Gravity.TOP)
            visibility = View.GONE
            isClickable = false; isFocusable = false
        }
        val bottom = View(ctx).apply {
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_fade_bottom)
            layoutParams = FrameLayout.LayoutParams(MATCH, fadeH, Gravity.BOTTOM)
            visibility = View.GONE
            isClickable = false; isFocusable = false
        }
        wrap.addView(top)
        wrap.addView(bottom)
        // Pass-12 (Required Implementations 3 + 11): every subtab list also
        // carries the shared minimalist scrollbar — a thin rounded thumb on
        // the right edge that appears the INSTANT a drag or fling begins and
        // fades out shortly after scrolling goes idle. Styled once, identical
        // on Android 5 through 16 (styling the platform scrollbar is
        // impossible below API 29).
        com.lumidrop.x.ui.LdxScrollbar.attach(wrap, list)
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { updateTabEdgeFades() }
        })
        list.viewTreeObserver.addOnGlobalLayoutListener { updateTabEdgeFades() }
        return wrap
    }

    /** Recomputes all three tabs' fade visibility — "is there content past
     *  this rim?" — exactly like SendFragment.updateDeviceEdgeFades. A
     *  hidden tab's bands are moot (the whole container is GONE) but the
     *  flags stay correct so a tab switch lands with the right fades
     *  already in place. */
    private fun updateTabEdgeFades() {
        if (view == null) return
        setTabEdgeFade(activeFadeTop, transferList.canScrollVertically(-1))
        setTabEdgeFade(activeFadeBottom, transferList.canScrollVertically(1))
        setTabEdgeFade(receivedFadeTop, receivedList.canScrollVertically(-1))
        setTabEdgeFade(receivedFadeBottom, receivedList.canScrollVertically(1))
        setTabEdgeFade(sentFadeTop, sentList.canScrollVertically(-1))
        setTabEdgeFade(sentFadeBottom, sentList.canScrollVertically(1))
    }

    /** Same gentle crossfade the Connected Devices fades use: visible
     *  bands fade in over 140ms, hidden ones fade out; a no-op when the
     *  band is already in the wanted state. */
    private fun setTabEdgeFade(v: View?, visible: Boolean) {
        val band = v ?: return
        val want = if (visible) View.VISIBLE else View.GONE
        if (band.visibility == want) return
        band.visibility = want
        band.alpha = if (visible) 0f else 1f
        band.animate().alpha(if (visible) 1f else 0f).setDuration(140).start()
    }

    // Active tab rendering ------------------------------------------------------------

    private fun renderActiveTab() {
        val rows = TransferManager.activeSnapshotGrouped()
        lastActiveRows = rows
        activeEmptyLabel.visibility = if (rows.isNotEmpty()) View.GONE else View.VISIBLE
        if (activeSelectionMode) {
            // ── GOAT-PASS 15 (§9.6): rows that completed, failed, cancelled
            // or were dismissed leave the selection set on the very refresh
            // that dropped them — a selected id can never dangle against a
            // different row (or nothing) while the toolbar still counts it.
            // The mode itself only ends when the list is genuinely empty or
            // the user exits it (the same committed-mode rule the Received
            // tab has had since §7 of its selection pass).
            val liveKeys = rows.map { it.rowKey() }.toSet()
            activeSelectionKeys.retainAll(liveKeys)
            if (rows.isEmpty()) exitActiveSelection()
        }
        activeAdapter.submitRows(rows)
        refreshActiveHeader()
    }

    /** ── GOAT-PASS 15 (§9.2): the ACTIVE tab's header — the SAME header row
     *  that carries the ⋮ overflow and the live summary line in normal mode,
     *  swapped in place to the selection toolbar while the mode is active
     *  (never a second toolbar below it, never dead space: the summary label
     *  and the ⋮ button go GONE and the pill takes the row, matching the
     *  positioning and dimensions the Received/History headers use). */
    private fun refreshActiveHeader() {
        if (!::globalControlsRow.isInitialized) return
        val selection = activeSelectionMode
        if (selection != activeHeaderIsSelection) {
            val row = globalControlsRow.getChildAt(0) as? LinearLayout ?: return
            if (selection) {
                activeSummaryLabel?.visibility = View.GONE
                row.findViewWithTag<android.view.View>("manage_btn")?.visibility = View.GONE
                activeSelCountLabel = null
                row.addView(buildSelectionActionBar("active"))
            } else {
                activeSummaryLabel?.visibility = View.VISIBLE
                row.findViewWithTag<android.view.View>("manage_btn")?.visibility = View.VISIBLE
                // Remove every selection pill previously added to the row
                // (the pill is rebuilt on the next entry; views are tagged at
                // creation — see buildSelectionActionBar).
                val toRemove = ArrayList<android.view.View>()
                for (i in 0 until row.childCount) row.getChildAt(i)?.let { v ->
                    if (v.tag == "selection_action_bar") toRemove.add(v)
                }
                toRemove.forEach { row.removeView(it) }
                activeSelCountLabel = null
            }
            activeHeaderIsSelection = selection
            refreshBulkSummary()
        }
        if (selection) {
            activeSelCountLabel?.text = "${activeSelectionKeys.size}"
        }
    }

    private var lastReceivedScanAtMs = 0L
    private var receivedScanInFlight = false

    /**
     * The Received tab used to read TransferManager.receivedSnapshot()
     * directly -- an in-memory, session-only log by design (see
     * TransferManager's own class doc: "nothing here is persisted across
     * app restarts"). That's the right call for a *live* monitor, but it
     * means a file the host received yesterday, with the app since
     * restarted, would show "no history" even though the file is sitting
     * right there in the receive folder -- exactly the reported bug.
     *
     * Fix: reconcile against the actual receive directory. A file on disk
     * always shows, whether or not this session's in-memory log still has
     * a matching record; a record whose file has been deleted never shows.
     * Where a live record DOES exist (matched by absolute path) it's used
     * for the richer "from <peer>" detail; otherwise a synthetic COMPLETED
     * Transfer is built from the file's own size/mtime so the existing row
     * renderer doesn't need a parallel implementation for the two cases.
     *
     * Throttled to once every 2s (real disk I/O, and the tab's own poll
     * loop ticks as often as every 500ms) rather than skipped/cached
     * indefinitely, so a file deleted from outside the app (a connected
     * file manager, adb, etc.) still disappears from history reasonably
     * promptly without a full directory listing on every single tick.
     */
    /** ── Pass-39 (#7): sort a Received/History row list for display. ─────────
     *  Folders (Groups) and files (Solos) sort together by the chosen
     *  criterion — a folder's name/size/date is its whole tree's (size sums
     *  its members; date is its newest member), and its TYPE is the empty
     *  string so folders group as one consistent block ahead of the
     *  extensions. The comparator chains a name tiebreak so the order is
     *  always deterministic, and Kotlin's stable sort keeps equal-key rows
     *  in their incoming (engine) order — identities never shuffle. */
    private fun sortTransferRows(items: List<TransferManager.TransferRowItem>,
                                 key: String, desc: Boolean): List<TransferManager.TransferRowItem> {
        fun nameOf(i: TransferManager.TransferRowItem) = when (i) {
            is TransferManager.TransferRowItem.Solo -> i.transfer.fileName
            is TransferManager.TransferRowItem.Group -> i.info.name
        }.lowercase()
        fun dateOf(i: TransferManager.TransferRowItem) = when (i) {
            is TransferManager.TransferRowItem.Solo -> i.transfer.finishedAt
            is TransferManager.TransferRowItem.Group ->
                i.members.maxOfOrNull { it.finishedAt }?.takeIf { it > 0 } ?: i.info.startedAt
        }
        fun sizeOf(i: TransferManager.TransferRowItem) = when (i) {
            is TransferManager.TransferRowItem.Solo -> i.transfer.totalBytes
            is TransferManager.TransferRowItem.Group -> i.members.sumOf { it.totalBytes }
        }
        fun typeOf(i: TransferManager.TransferRowItem) = when (i) {
            is TransferManager.TransferRowItem.Solo -> i.transfer.fileName.substringAfterLast('.', "").lowercase()
            is TransferManager.TransferRowItem.Group -> ""   // the folders' one consistent block
        }
        val cmp: Comparator<TransferManager.TransferRowItem> = when (key) {
            "name" -> compareBy<TransferManager.TransferRowItem> { nameOf(it) }
            "size" -> compareBy<TransferManager.TransferRowItem> { sizeOf(it) }
            "type" -> compareBy<TransferManager.TransferRowItem> { typeOf(it) }.thenBy { nameOf(it) }
            else   -> compareBy<TransferManager.TransferRowItem> { dateOf(it) }   // "date"
        }.thenBy { nameOf(it) }   // the deterministic tiebreak
        // ── GOAT-PASS 13 (§1 — the Android 5 startup crash, root fix) ──────────
        // The descending branch used to read `cmp.reversed()`. Kotlin resolves
        // that call to the JAVA 8 INTERFACE DEFAULT METHOD
        // java.util.Comparator.reversed() — members win over same-named
        // stdlib extensions — and that method only exists on the platform's
        // Comparator from API 24. On Android 5/6 (API 21-23) it throws
        //    java.lang.NoSuchMethodError: No interface method
        //    reversed()Ljava/util/Comparator; in class Ljava/util/Comparator;
        // — exactly the captured crash (the "material.datepicker.e" frame in
        // that log is only R8's reuse of a tree-shaken package name for its
        // desugaring dispatch helper; no Material code calls reversed()).
        // D8 cannot desugar platform-interface methods, so the call reached
        // the release dex verbatim. A hand-written reversed comparator — two
        // swapped arguments, API-1-safe everywhere — replaces it. (Audited:
        // this was the ONLY .reversed()/Java-8 Comparator member call in the
        // app; putIfAbsent sites are all ConcurrentHashMap-typed, which has
        // had that method since API 9.)
        return if (desc)
            items.sortedWith(Comparator { a, b -> cmp.compare(b, a) })
        else
            items.sortedWith(cmp)
    }

    /** ── Pass-39 (#7): the sort menu both tabs share — the app's existing ────
     *  PickerMenu (the Files/Music pickers' sorting control, check marks on
     *  the active criterion and direction), anchored on the header's sort
     *  button. A pick persists to SettingsManager and re-renders the tab.
     *  ── Pass-40 (#4): rendered COMPACT — the tabs' headers are slim rows,
     *  and the classic picker proportions read as an oversized panel off
     *  them (same options, same behavior, tighter card/row/text metrics). */
    private fun showTransfersSortMenu(anchor: View, tab: String) {
        val ctx = requireContext()
        val isReceived = tab == "received"
        val key = if (isReceived) receivedSortKey else sentSortKey
        val desc = if (isReceived) receivedSortDesc else sentSortDesc
        com.lumidrop.x.ui.showPickerOptionsMenu(anchor, "Sort by", listOf(
            com.lumidrop.x.ui.PickerOption("KEY_NAME", "Name", key == "name"),
            com.lumidrop.x.ui.PickerOption("KEY_DATE", if (isReceived) "Date received" else "Date", key == "date"),
            com.lumidrop.x.ui.PickerOption("KEY_TYPE", "File type", key == "type"),
            com.lumidrop.x.ui.PickerOption("KEY_SIZE", "Size", key == "size"),
            com.lumidrop.x.ui.PickerOption("", "", divider = true),
            com.lumidrop.x.ui.PickerOption("DIR_ASC", "Ascending", !desc),
            com.lumidrop.x.ui.PickerOption("DIR_DESC", "Descending", desc),
        ), compact = true, endAnchored = true) { id ->
            val newKey = when (id) {
                "KEY_NAME" -> "name"; "KEY_TYPE" -> "type"; "KEY_SIZE" -> "size"
                "KEY_DATE" -> "date"
                else -> key
            }
            val newDesc = when (id) {
                "DIR_ASC" -> false; "DIR_DESC" -> true
                else -> desc
            }
            if (isReceived) { receivedSortKey = newKey; receivedSortDesc = newDesc }
            else { sentSortKey = newKey; sentSortDesc = newDesc }
            com.lumidrop.x.settings.SettingsManager.setTransfersSort(ctx, tab, "$newKey:${if (newDesc) "d" else "a"}")
            // ── GOAT-PASS 10 (§3): the new order's first rows are what the ──
            // user just asked to see — arm the one-shot top-return and let
            // the render that applies the new order consume it (Received
            // renders through the async reconcile; History synchronously).
            // Instant, not animated: an animated scroll over a re-sorted
            // list shows intermediate orderings, which the requirement
            // forbids. Normal renders never touch the scroll position.
            sortScrollTopPending = tab
            if (isReceived) { lastReceivedScanAtMs = 0L; renderReceivedTab() }
            else renderHistoryTab()
        }
    }

    private fun renderReceivedTab() {
        val now = System.currentTimeMillis()
        if (receivedScanInFlight || now - lastReceivedScanAtMs < 2000L) return
        receivedScanInFlight = true
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val ctx = context
            val reconciled = if (ctx != null) buildReconciledReceived(ctx) else emptyList()
            withContext(Dispatchers.Main) {
                receivedScanInFlight = false
                lastReceivedScanAtMs = System.currentTimeMillis()
                if (view == null) return@withContext
                // Phase 5 (§5.4): remember the submitted rows — a selection
                // toggle re-submits THESE rather than waiting for the next
                // scan, and rows that vanished from the folder drop out of
                // the selection set so Delete can never target a ghost.
                // ── Pass-39 (#7): the persisted sort, applied to the DISPLAY ──
                // list; the stored snapshot is the sorted one so every
                // selection-mode re-submit shows the same order.
                val sortedReconciled = sortTransferRows(reconciled, receivedSortKey, receivedSortDesc)
                lastReceivedRows = sortedReconciled
                if (receivedSelectionMode) {
                    // §7 (selection-mode persistence): rows that vanished
                    // from the folder drop OUT of the selection set (Delete
                    // must never target a ghost), but an empty SET no longer
                    // exits selection mode — the mode was COMMITTED by the
                    // long-press and only an explicit user action (Cancel,
                    // Delete, leaving the tab) or a genuinely empty folder
                    // ends it. The old auto-exit on empty keys silently
                    // killed a live selection whenever a reconcile tick
                    // produced keys that didn't match (the "selection
                    // disappears after the finger lifts" family).
                    val liveKeys = reconciled.map { it.rowKey() }.toSet()
                    receivedSelectionKeys.retainAll(liveKeys)
                    if (reconciled.isEmpty()) exitReceivedSelection()
                }
                receivedEmptyLabel.visibility = if (reconciled.isEmpty()) View.VISIBLE else View.GONE
                // ── GOAT-PASS 10 (§3) + GOAT-PASS 11 (§2): a sorting change ──
                // returns the list to the top the moment the new order is
                // applied — the flag is armed by the sort menu and consumed
                // in the DIFF COMMIT callback (GP10 fired it synchronously
                // after submitRows, which races the async DiffUtil: the
                // scroll landed on the old layout and the structural change
                // then dragged the viewport down with the re-anchored rows —
                // the reported "sometimes scrolls to the bottom"). Inside
                // the callback the new ordering is genuinely committed.
                // ── GOAT-PASS 12 (§6): the tab-ENTRY top-return rides the ──
                // same commit — armed by selectTab, consumed exactly once by
                // the entry render, never again while the user browses.
                val consumeReceivedSortScroll = sortScrollTopPending == "received"
                if (consumeReceivedSortScroll) sortScrollTopPending = null
                val consumeReceivedEntryScroll = pendingEntryTopScroll == "received"
                if (consumeReceivedEntryScroll) pendingEntryTopScroll = null
                receivedAdapter.submitRows(sortedReconciled) {
                    if (view != null && (consumeReceivedSortScroll || consumeReceivedEntryScroll)) {
                        (receivedList.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                            ?.scrollToPositionWithOffset(0, 0)
                    }
                }
                // ── GOAT-PASS 8 (§1): the header's live count patches on EVERY ──
                // completed scan, not only inside selection mode — a receive
                // that lands while the tab is open (or a delete done elsewhere)
                // is reflected by the very next reconcile tick, and the very
                // first scan of a fresh view lands the counter it builds.
                if (::receivedHeaderRow.isInitialized) refreshReceivedHeader()
            }
        }
    }

    /**
     * GOAT (Part 2 — folder as a single transfer item): now recurses into
     * subfolders instead of only ever looking at dir.listFiles().filter
     * { it.isFile } — that only ever saw LOOSE files sitting directly in
     * the receive root; every file inside a received folder (which always
     * lands in its own real subdirectory — see LumiDropServer.
     * precreateNativePushDirs) was silently invisible here, both for a
     * folder received this session and, since this function is what
     * covers an app restart in the first place, for one from any past
     * session too. Each top-level subdirectory becomes one
     * TransferRowItem.Group via TransferManager.getOrCreateGroup, keyed by
     * the folder's own absolute path so the SAME folder maps to the SAME
     * group id across repeated scans (this runs every ~2s) and across an
     * app restart — required for expand state and DiffUtil identity to
     * stay stable rather than resetting/rebuilding every time.
     */
    private fun buildReconciledReceived(ctx: android.content.Context): List<TransferManager.TransferRowItem> {
        val dir = File(com.lumidrop.x.settings.SettingsManager.getUploadPath(ctx))
        val topEntries = try { dir.listFiles() ?: emptyArray() } catch (e: Exception) { emptyArray() }
        val liveByPath = TransferManager.receivedSnapshot().filter { it.filePath != null }.associateBy { it.filePath }

        // ── Pass-33 (#4): PROVENANCE, not directory membership, decides what ──────
        // the Received tab renders. A file or folder that merely EXISTS in the
        // receive directory is invisible here unless LumiDrop X actually
        // received it (ReceivedRegistry — recorded at the moment of
        // completion, grandfathered once for pre-pass content at first
        // launch). Foreign content is therefore also structurally outside
        // every delete action this tab offers: no row, no selection, no
        // deletion — an external file manager's files can never be deleted
        // from here. Live in-session receives are registered by the same
        // completion hook, so they pass this filter like any settled one.
        fun proven(path: String): Boolean = try {
            com.lumidrop.x.transfer.ReceivedRegistry.isAppReceived(ctx, path)
        } catch (e: Exception) { false }   // an unreadable registry hides rows; it never invents them

        fun reconcile(file: File, groupId: String?): TransferManager.Transfer =
            liveByPath[file.absolutePath] ?: TransferManager.Transfer(
                id = "fsrecv:${file.absolutePath}",
                direction = TransferManager.Direction.UPLOAD,
                fileName = file.name,
                totalBytes = file.length(),
                peerIp = "",
                // ── Pass-15 (no more "Unknown sender") ────────────────────────
                // The completion path recorded WHO delivered every file it
                // finalized (ReceivedFileMeta — native pushes and browser
                // uploads alike), so a past session's rows render the real
                // device name. A file with no record (evicted cap, received
                // on an older build, or dropped into the folder by hand)
                // gets an EMPTY name — detailLine then omits the "From X"
                // segment entirely, which reads clean and honest; it never
                // guesses "Unknown sender" for a transfer the app could in
                // fact identify.
                peerName = try {
                    com.lumidrop.x.transfer.ReceivedFileMeta.senderFor(ctx, file.absolutePath) ?: ""
                } catch (e: Exception) { "" },
                filePath = file.absolutePath,
                groupId = groupId,
            ).also {
                it.status = TransferManager.Status.COMPLETED
                it.transferredBytes = file.length()
                // ── GOAT-PASS 10 (§5 — the "[Time] ago" label shows the ──
                // RECEIVE time, never the file's own date): the registry
                // records the completion stamp at the exact moment this
                // file landed; a file's filesystem mtime can be the sender's
                // ORIGINAL mtime (or a copy preserving it), which used to
                // read as "5d ago" for a file received a minute ago.
                // Grandfathered/pre-pass content with no recorded stamp
                // falls back to the mtime exactly as before, and a broken
                // registry read degrades the same way — never a crash, never
                // a misleading zero.
                it.finishedAt = try {
                    com.lumidrop.x.transfer.ReceivedRegistry.receivedAt(ctx, file.absolutePath)
                        .takeIf { ts -> ts > 0L } ?: file.lastModified()
                } catch (e: Exception) { file.lastModified() }
            }

        val rows = mutableListOf<TransferManager.TransferRowItem>()
        for (entry in topEntries) {
            // Pass-33 (#4): the provenance gate, BEFORE any row is minted —
            // foreign top-level files and folders never reach the tab at all.
            if (!proven(entry.absolutePath)) continue
            if (entry.isFile) {
                rows += TransferManager.TransferRowItem.Solo(reconcile(entry, groupId = null))
            } else if (entry.isDirectory) {
                // Pass-33 (#4): members are provenance-filtered too — a file
                // dropped INSIDE an app-received folder by an external manager
                // renders nowhere and cannot be selected or deleted from here.
                val nestedFiles = try { entry.walkTopDown().filter { it.isFile && proven(it.absolutePath) }.toList() } catch (e: Exception) { emptyList() }
                if (nestedFiles.isEmpty()) continue   // nothing proven inside — nothing this tab owns
                // Pass-15: reconcile the members FIRST, then derive the
                // folder group's sender label from the first member that
                // carries one — never a synthesized "Unknown sender".
                val preGroupId = "fsrecv-dir:${entry.absolutePath}"
                val members = nestedFiles.map { reconcile(it, preGroupId) }
                val groupSender = members.firstOrNull { it.peerName.isNotBlank() }?.peerName ?: ""
                val groupId = TransferManager.getOrCreateGroup(
                    id = preGroupId, name = entry.name,
                    direction = TransferManager.Direction.UPLOAD, peerIp = "", peerName = groupSender,
                    source = TransferManager.Source.NATIVE_PUSH)
                rows += TransferManager.TransferRowItem.Group(TransferManager.groupInfo(groupId)!!, members)
            }
        }
        return rows.sortedByDescending { item ->
            when (item) {
                is TransferManager.TransferRowItem.Solo -> item.transfer.finishedAt
                is TransferManager.TransferRowItem.Group -> item.members.maxOf { it.finishedAt }
            }
        }
    }

    /** Pass-17 (History is a durable, INSTANT, deletion-proof log): the
     *  History tab no longer derives anything from the disk. It renders the
     *  persistent HistoryStore (written at the exact moment every transfer
     *  completes, surviving restarts) merged with the live engine rows that
     *  aren't in the store yet — all in-memory, zero I/O, so the tab paints
     *  on the very same 500ms render tick it is opened on (the old render
     *  shared the Received tab's 2-second-throttled walkTopDown disk scan,
     *  which is what "History takes some time to load" was). Deleting the
     *  backing file from the Received tab — or the file leaving the device
     *  entirely — no longer touches history: the store's record is the
     *  history, and it is only removed by the tab's own per-row ✕. */
    /** ── GOAT-PASS 10 (§3): one-shot top-return armed by the sort menu and ──
     *  consumed by whichever render applies the new order — null means no
     *  sorting change is pending and no render may touch the scroll. */
    private var sortScrollTopPending: String? = null

    /** ── GOAT-PASS 12 (§6): one-shot top-return armed by selectTab when the ──
     *  Received or History tab is (re-)entered and consumed by that entry's
     *  own render commit — the tab ALWAYS opens at the top, then normal
     * scrolling inside the tab is never touched again. */
    private var pendingEntryTopScroll: String? = null

    private fun renderHistoryTab() {
        val ctx = context ?: return
        if (view == null) return
        // ── GOAT-PASS 10 (§3) + GOAT-PASS 11 (§2): a sorting change returns ──
        // the list to the top — consumed in the DIFF COMMIT callback, not
        // synchronously after submitRows (the async DiffUtil made the old
        // synchronous scroll land on the pre-diff layout, and the re-sort's
        // structural changes then re-anchored the viewport down the list —
        // the reported bottom-scroll). The callback runs only when the new
        // ordering is genuinely committed to the adapter.
        val consumeSortScroll = sortScrollTopPending == "sent"
        if (consumeSortScroll) sortScrollTopPending = null
        // ── GOAT-PASS 12 (§6): a TAB-ENTRY top-return rides the very same ──
        // commit — armed by selectTab, consumed once by the entry render, so
        // the reset lands only after the (possibly data-restoring) render's
        // diff is genuinely applied and never again while the user browses.
        val consumeEntryScroll = pendingEntryTopScroll == "sent"
        if (consumeEntryScroll) pendingEntryTopScroll = null
        // ── FINAL PASS 5 (§1B): a Show-in-History reveal armed by the
        // Received card's ⋮ is consumed by this entry's own commit — the
        // top-return and the reveal never both fire (the reveal owns the
        // scroll when present), and the reveal resolves only after the diff
        // has genuinely committed the rows it targets.
        val consumeHistoryReveal = pendingHistoryRevealKey != null
        if (consumeHistoryReveal) pendingEntryTopScroll = null
        // ── FINAL PASS 8 (PART 4 — the History-entry freeze): HistoryStore's ──
        // FIRST snapshot of a process reads and JSON-parses the whole
        // history file (up to 600 entries + 4096 tombstones) under the
        // store's monitor — and this render ran it ON THE MAIN THREAD, a
        // multi-hundred-millisecond synchronous stall exactly when the user
        // tapped the History pill (the reported 3–4s freezes on a large
        // history: the parse, then the merge/transform below, all before
        // the first frame of the new tab). The store read now runs on
        // Dispatchers.IO — the same shape renderReceivedTab already uses —
        // and the merge + submit still run on Main once the (usually
        // already-warm) snapshot arrives. Steady-state renders are an
        // in-memory copy either way, so this changes only the cold path.
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val snap = HistoryStore.snapshot(ctx)
            withContext(Dispatchers.Main) {
                if (view == null) return@withContext
                renderHistoryList(snap) {
                    if (view == null) return@renderHistoryList
                    if (consumeHistoryReveal) {
                        // The reveal OWNS this listing's scroll — the entry
                        // top-return yields to it exactly the way the Files sheet's
                        // reveal owns its listing's scroll over the per-directory
                        // restore.
                        revealPendingHistoryRow()
                    } else if (consumeSortScroll || consumeEntryScroll) {
                        (sentList.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)
                            ?.scrollToPositionWithOffset(0, 0)
                    }
                }
            }
        }
    }

    /** The merged-list render itself — store entries (past + present) plus
     *  live engine rows the store hasn't seen (its hook write failed, or a
     *  completion raced the read), de-duplicated by transfer id, with the
     *  store's consecutive same-folder entries folded back into single
     *  folder rows exactly the way the Sent/Received tabs group them. */
    private fun renderHistoryList(storeEntries: List<com.lumidrop.x.transfer.HistoryStore.Entry>,
                                  onCommitted: (() -> Unit)? = null) {
        // GOAT-PASS 12 (§3D): the tombstone verdicts below need the context
        // (HistoryStore's own lazy load rides it); the render is a no-op
        // without one.
        val ctx = context ?: return
        val storeById = HashMap<String, com.lumidrop.x.transfer.HistoryStore.Entry>()
        for (e in storeEntries) storeById[e.id] = e

        fun transferFromEntry(e: com.lumidrop.x.transfer.HistoryStore.Entry): TransferManager.Transfer {
            val dir = when (e.direction) {
                "SEND" -> TransferManager.Direction.SEND
                "DOWNLOAD" -> TransferManager.Direction.DOWNLOAD
                else -> TransferManager.Direction.UPLOAD
            }
            // ── FINAL PASS 3 (§1 — the real size of LEGACY streamed-ZIP ──
            // records): entries recorded before this pass persisted
            // totalBytes = -1 (an unknown-length archive has no declared
            // size, and the completion-time stamp this pass adds did not
            // exist yet), so they render "?" forever. For a RECEIVED entry
            // the file itself is still the ground truth — its own length is
            // the actual final size, read once per legacy -1 row at render
            // time (never written back; the display simply tells the truth
            // the file already holds). Sent entries keep "?" honestly —
            // only the receiving device ever held the file.
            val legacySize = if (e.totalBytes >= 0L) e.totalBytes else
                e.filePath?.let { p ->
                    try { val f = java.io.File(p); if (f.isFile) f.length() else -1L }
                    catch (ex: Exception) { -1L }
                } ?: -1L
            val t = TransferManager.Transfer(
                id = "hist:${e.id}",
                direction = dir,
                fileName = e.fileName,
                totalBytes = legacySize,
                peerIp = e.peerIp,
                peerName = e.peerName,
                filePath = e.filePath,
                groupId = e.groupId?.let { "hist:$it" },
            )
            t.status = TransferManager.Status.COMPLETED
            t.isTextMessage = e.isText
            t.textContent = e.textContent
            // ── FINAL PASS 3 (§2): the persisted streamed-ZIP marker comes ──
            // back onto the row so selection eligibility (isRangeSelectableIn)
            // and every isZipStream-keyed presentation survive a restart.
            t.isZipStream = e.isZipStream
            t.transferredBytes = legacySize
            t.finishedAt = e.finishedAt
            return t
        }

        val merged = ArrayList<TransferManager.TransferRowItem>()
        // (a) Store entries, folder runs folded into single Group rows.
        var i = 0
        while (i < storeEntries.size) {
            val e = storeEntries[i]
            val gid = e.groupId
            if (e.isText || gid == null) {
                merged += TransferManager.TransferRowItem.Solo(transferFromEntry(e))
                i++
                continue
            }
            val members = ArrayList<TransferManager.Transfer>()
            var j = i
            while (j < storeEntries.size && storeEntries[j].groupId == gid &&
                !storeEntries[j].isText) {
                members += transferFromEntry(storeEntries[j]); j++
            }
            if (members.isNotEmpty()) {
                val stableGid = "hist:$gid"
                TransferManager.getOrCreateGroup(stableGid, (e.groupName ?: "").ifBlank { e.fileName },
                    members[0].direction, e.peerIp, e.peerName,
                    TransferManager.Source.NATIVE_PUSH)
                TransferManager.groupInfo(stableGid)?.let { info ->
                    merged += TransferManager.TransferRowItem.Group(info, members)
                }
            }
            i = j
        }
        // (b) Live engine rows whose id has NOT been stored yet — the store
        // normally covers them the instant they complete; this is the
        // belt-and-braces half for a raced/failed hook write.
        // ── GOAT-PASS 12 (§3D): …and whose id (or group) has NOT been ──────────
        // DELIBERATELY DELETED. A completed transfer lives in BOTH layers —
        // the store entry AND the engine's live row — so deleting only one
        // layer used to resurrect the row through this very merge on the
        // next 500ms tick (the reported "deleted items reappear"). The
        // tombstone set (HistoryStore) is the one authoritative "this record
        // was removed and must stay removed" verdict; every live row and
        // every group member is checked against it here.
        val liveRows = TransferManager.sentSnapshotGrouped() +
            TransferManager.receivedSnapshot().map { TransferManager.TransferRowItem.Solo(it) }
        for (row in liveRows) {
            when (row) {
                is TransferManager.TransferRowItem.Solo ->
                    if (row.transfer.id !in storeById &&
                        !HistoryStore.isTombstoned(ctx, row.transfer.id) &&
                        !HistoryStore.isGroupTombstoned(ctx, row.transfer.groupId)) merged += row
                is TransferManager.TransferRowItem.Group -> {
                    if (HistoryStore.isGroupTombstoned(ctx, row.info.id.removePrefix("hist:"))) continue
                    val missing = row.members.filter {
                        it.id !in storeById &&
                            !HistoryStore.isTombstoned(ctx, it.id) &&
                            !HistoryStore.isGroupTombstoned(ctx, it.groupId)
                    }
                    if (missing.isNotEmpty()) merged += TransferManager.TransferRowItem.Group(row.info, missing)
                }
            }
        }
        val newestFirst = merged.sortedByDescending { item ->
            when (item) {
                is TransferManager.TransferRowItem.Solo -> item.transfer.finishedAt
                is TransferManager.TransferRowItem.Group -> item.members.maxOf { it.finishedAt }
            }
        }
        if (view == null) return
        // ── Pass-28 (#1.1/#1.3): the History tab's submitted rows are the ────
        // selection system's working set (toggles re-submit THESE) AND the
        // header count's source of truth. Selection keys that no longer
        // match a live row drop OUT of the set — a completed live row is
        // replaced by its store record (a different key) on the very next
        // render, and a stored record removed elsewhere must never be
        // deletable as a ghost — while an empty tab ends the mode outright,
        // exactly like Received's reconcile pass.
        // ── Pass-39 (#7): the persisted sort REPLACES the old hard-coded ──
        // newest-first ordering (date-descending is simply the default); the
        // stored snapshot IS the sorted list, so selection re-submits keep
        // the same order.
        val finalRows = sortTransferRows(newestFirst, sentSortKey, sentSortDesc)
        lastSentRows = finalRows
        if (sentSelectionMode) {
            val liveKeys = finalRows.map { it.rowKey() }.toSet()
            sentSelectionKeys.retainAll(liveKeys)
            if (finalRows.isEmpty()) exitSentSelection()
        }
        sentEmptyLabel.visibility = if (finalRows.isEmpty()) View.VISIBLE else View.GONE
        // GOAT-PASS 11 (§2): the optional commit callback rides the diff
        // submit (see submitRows) — the sort-change scroll consumes it, so
        // it fires only after the new ordering is genuinely in the adapter.
        sentAdapter.submitRows(finalRows) { onCommitted?.invoke() }
        // The header's live count (or, while selecting, the selected count)
        // patches on every render — additions, deletions and Clear History
        // are reflected the same tick they happen.
        if (::sentHeaderRow.isInitialized) refreshSentHeader()
    }

    /** Phase 8 (§8.3): in-place patch of a solo row's live progress views —
     *  the exact same writes updateRow performs for these three elements,
     *  extracted so the payload rebind path and the full bind path can
     *  never drift apart.
     *  ── FINAL PASS 5 (§2): [hidePeer] keeps the payload path's detail line
     *  in lockstep with the full bind's (Received omits sender-origin). */
    private fun patchProgressViews(card: LinearLayout, t: TransferManager.Transfer, hidePeer: Boolean = false, history: Boolean = false) {
        findTagged<TextView>(card, "tv_detail")?.text = detailLine(t, hidePeer, history)
        findTagged<ProgressBar>(card, "pb")?.apply {
            visibility = if (t.status == Status.ACTIVE) View.VISIBLE else View.GONE
            if (t.status == Status.ACTIVE) {
                isIndeterminate = t.isIndeterminate
                if (!t.isIndeterminate) progress = t.progressPct
                val tint = android.content.res.ColorStateList.valueOf(
                    if (t.pauseRequested) col(R.color.orange) else col(R.color.accent))
                progressTintList = tint
                indeterminateTintList = tint
            }
        }
        // Pass-24 (#5): the speed line beneath the bar patches in place with
        // the bar — same value source as the full bind, never drifting.
        applySpeedView(card, if (t.status == Status.ACTIVE) speedTextFor(t) else null)
        // ── GOAT-PASS 8 (§8): the chip now lives in the LOWER action row; the ──
        // payload path implies a prior FULL bind created it (the shell always
        // does), and if it's somehow absent the next structural change's full
        // bind lands it.
        findStatusChip(card)?.let { applyStatusChip(it, t) }
    }

    /** Phase 8 (§8.3): group-row twin of [patchProgressViews] — the
     *  aggregate progress bar, status chip and detail line only, with the
     *  same started-gap flicker rule updateGroupRow itself applies.
     *  ── FINAL PASS 5 (§2): [hidePeer] threads through, same as the solo
     *  twin — the payload path's detail line matches the full bind's. */
    private fun patchGroupProgressViews(card: LinearLayout, info: TransferManager.TransferGroupInfo, members: List<TransferManager.Transfer>, hidePeer: Boolean = false, history: Boolean = false) {
        val status = TransferManager.groupStatus(members)
        val progressPct = TransferManager.groupProgressPct(members)
        val isIndeterminate = TransferManager.groupIsIndeterminate(members)
        val filesDone = members.count { it.status == Status.COMPLETED }
        findTagged<TextView>(card, "tv_detail")?.text = groupDetailLine(info, members, filesDone, hidePeer, history)
        findTagged<ProgressBar>(card, "pb")?.apply {
            val started = filesDone > 0 || progressPct > 0
            val show = status == Status.ACTIVE || (status == Status.PENDING && started)
            visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                this.isIndeterminate = status == Status.ACTIVE && isIndeterminate
                if (!this.isIndeterminate) progress = progressPct
                val anyPaused = members.any { (it.status == Status.PENDING || it.status == Status.ACTIVE) && it.pauseRequested }
                val tint = android.content.res.ColorStateList.valueOf(if (anyPaused) col(R.color.orange) else col(R.color.accent))
                progressTintList = tint; indeterminateTintList = tint
            }
        }
        // Pass-24 (#5): the aggregate speed of the folder's live members,
        // under the bar — null (hidden) whenever the group is not ACTIVE.
        applySpeedView(card, if (status == Status.ACTIVE) groupSpeedTextFor(members) else null)
        // ── GOAT-PASS 8 (§8): the chip now lives in the LOWER action row. ──
        findStatusChip(card)?.let {
            applyGroupStatusChip(it, status, progressPct, isIndeterminate, filesDone > 0)
        }
    }

    /** ── GOAT-PASS 8 (§8): resolves a card's status chip in its new home — ────
     *  the LOWER action row ("act_row"), where the shell builds it as the
     *  first child, LEFT of the speed caption and the action buttons. Every
     *  bind path (full, payload, group) and patchProgressViews go through
     *  this one resolver, so no path can ever look for (or add) a chip in the
     *  retired title-row location. */
    private fun findStatusChip(card: LinearLayout): TextView? {
        val actRow = findTagged<LinearLayout>(card, "act_row") ?: return null
        return findTagged<TextView>(actRow, "status_chip")
    }

    // ── Pass-24 (#5): the speed line beneath the progress bar ─────────────────
    /** The live speed text for a solo transfer — the exact values the old
     *  detail-line tail carried ("Paused", "1.2 MB/s", "…"), now on their
     *  own accent-tinted line directly under the bar. */
    private fun speedTextFor(t: TransferManager.Transfer): String =
        when {
            t.pauseRequested -> "Paused"
            t.speedBps > 0 -> "${fmtBytes(t.speedBps.toLong())}/s"
            else -> "…"
        }

    /** The aggregate speed text for a live folder group: the summed speed of
     *  its currently-streaming members, "Paused" when every live member is
     *  paused, and null (line hidden) when nothing is live. */
    private fun groupSpeedTextFor(members: List<TransferManager.Transfer>): String? {
        val active = members.filter { it.status == Status.ACTIVE }
        if (active.isEmpty()) return null
        val streaming = active.filter { !it.pauseRequested }
        if (streaming.isEmpty()) return "Paused"
        val sum = streaming.sumOf { it.speedBps }
        return if (sum > 0) "${fmtBytes(sum.toLong())}/s" else "…"
    }

    /** Writes (or hides) the row's speed caption — the single writer both
     *  bind paths and both patch paths share, so the four can never drift.
     *  Pass-25 (#1): the caption now lives INSIDE the row's action row (the
     *  Pause/Resume + Cancel strip), in its weighted leading slot — see
     *  createRowShell. */
    private fun applySpeedView(card: LinearLayout, text: String?) {
        findTagged<TextView>(card, "tv_speed")?.apply {
            if (text != null) { this.text = text; visibility = View.VISIBLE } else visibility = View.GONE
        }
    }

    // Compact row ---------------------------------------------------------------------

    /**
     * Builds one row's fixed view STRUCTURE only — no data. RecyclerView
     * calls this once per view (onCreateViewHolder) and reuses the result
     * across many different Transfers over that view's lifetime as the
     * list scrolls, so nothing here can depend on which Transfer will
     * eventually be bound to it. [updateRow] (onBindViewHolder) is the
     * single source of truth for every data-dependent piece of this row —
     * including ones that used to only ever be set once, right here, back
     * when one row = one Transfer for the view's entire on-screen lifetime
     * and nothing was ever recycled onto a different Transfer: the
     * thumbnail fallback icon, the send/receive direction badge, the
     * actual async thumbnail load, and (this part was a real, standalone
     * bug even before recycling existed at all — see updateRow) the
     * progress bar.
     */
    private fun createRowShell(): LinearLayout {
        val ctx = requireContext()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            background  = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_secondary)
            // ── FINAL PASS 6 (PART 4/5/6 — the shared card rhythm): the card's ──
            // padding is rebalanced for the redesigned rows — 14dp horizontal
            // (a touch more breathing room than the old 12 for the title and
            // its ⋮), 10dp vertical top / 10dp bottom (the rows are one line
            // tighter now, so the vertical padding carries the breathing
            // room instead). Card-to-card spacing stays 6dp — one consistent
            // gap scale across both tabs.
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = dp(6) }
        }

        val row1 = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(MATCH, WRAP)
        }
        // ── Pass-28 (#1.2): the 22dp selection circle that used to lead this ────
        // row is GONE. The entire row container now communicates selection —
        // see applySelectionVisual: a restrained accent-tinted fill plus a
        // hairline accent-dark border over the whole card, identical in both
        // selection-capable tabs, with the row's content geometry untouched
        // (selection mode no longer shifts the thumbnail/name by an inset —
        // the old indicator's 22dp+8dp push). A recycled row can never carry
        // a stale visual either: the state is re-applied per bind from the
        // row's snapshot fields.
        // Phase 5 (§5.2): 34dp -> 40dp thumbnails. The transfer row is the
        // app's single most information-dense surface, and a 34dp preview
        // was small enough that identifying WHICH photo/video sat in a row
        // often needed the filename line anyway — 40dp keeps the row compact
        // (padding unchanged) while making the thumbnail genuinely readable.
        // ── FINAL PASS 6 (PART 4.2): the 40dp thumbnail + 12dp gap stays the ──
        // one icon/thumbnail size across BOTH tabs (the consistency half of
        // the redesign); a hairline 1dp start margin keeps the thumb optically
        // aligned with the card's rounded corner.
        val thumbStack = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).also {
                it.marginStart = dp(1); it.marginEnd = dp(12)
            }
        }
        val thumbView = ImageView(ctx).apply {
            tag = "thumb"
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_thumb_small)
            clipToOutline = true
            // ── Pass-20 (rounded corners on the REAL thumbnails) ────────────────
            // Every bind resets the background to null (updateRow) so the
            // placeholder glyph sits on transparency — and Android's
            // auto-derived clip outline comes FROM the background, so the
            // moment it went null the clip silently stopped working and the
            // actual decoded bitmaps rendered as SQUARE 40dp tiles (the
            // reported "make the file thumbnails in the Active/Received/
            // History tabs rounded"). An EXPLICIT outline provider keeps the
            // rounded clip alive regardless of what the background is doing
            // — placeholder glyph, real bitmap, accent badge — at the same
            // 8dp radius bg_thumb_small always used. API 21+ (minSdk), no
            // effect on any other surface.
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setRoundRect(0, 0, view.width, view.height, dp(8).toFloat())
                }
            }
            layoutParams = FrameLayout.LayoutParams(dp(40), dp(40))
        }
        thumbStack.addView(thumbView)
        thumbStack.addView(ImageView(ctx).apply {
            tag = "dir_badge"
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_accent_badge)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = FrameLayout.LayoutParams(dp(16), dp(16), Gravity.BOTTOM or Gravity.END).also {
                it.rightMargin = dp(-2); it.bottomMargin = dp(-2)
            }
        })
        row1.addView(thumbStack)
        // ── FINAL PASS 3 (§9): the title and its metadata form one VERTICAL ──
        // text column beside the thumbnail — the device + time-ago line sits
        // directly under the filename (smaller, muted, single-line) instead
        // of on its own full-width row below, which is what made the cards
        // tall. A proper flex layout (weighted column), never absolute
        // positioning: long filenames ellipsize in the middle, long device
        // names ellipsize at the end, and small screens simply narrow the
        // column.
        // ── FINAL PASS 5 (§6): the ⋮ slot is BUILT IN THE SHELL now, as the ──
        // LAST child of the title's own row (row1) — the top-right of the
        // title row, exactly the required layout (left: thumbnail; middle:
        // title column; right: ⋮). The shell builds it once, GONE by default;
        // updateRow's qualifying Received bind flips it VISIBLE with THIS
        // bind's transfer wired to it. Because the view is a permanent shell
        // child (not dynamically added/removed), a recycled card can never
        // carry a stale button, and the button's presence is a plain
        // visibility flag — no findViewWithTag-on-rebind cost at all.
        // The title column is weighted so the ⋮ can never be pushed out of
        // the card by a long filename: the column's 0dp weighted width
        // shrinks first, the MIDDLE ellipsis absorbs the loss.
        val textCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        textCol.addView(TextView(ctx).apply {
            tag = "tv_name"
            // ── FINAL PASS 6 (PART 4.3): 12.5sp → 13sp — the title reads as the ──
            // card's protagonist at a production weight while staying one
            // compact single line (the MIDDLE ellipsis + displayTitleFor's
            // head…tail pre-shortening handle the fit; the ⋮'s weighted
            // column can never be pushed by it).
            textSize = 13f
            setTextColor(col(R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        })
        row1.addView(textCol)
        // ── FINAL PASS 5 (§6): the shell-built ⋮, at the right of the SAME ────
        // row as tv_name (top-right of the card, vertically centered on the
        // title's row — never in the gray metadata row below). Same shared
        // iconButton component; GONE until a qualifying Received bind shows
        // it. FINAL PASS 6 (PART 4.4): the touch target widens to a
        // comfortable 32dp (the 48dp-quarter standard for a dense row),
        // end-aligned with the card's own 14dp padding rhythm; still one
        // permanent shell child — no add/remove cost, no stale recycling.
        row1.addView(Widgets.iconButton(ctx, R.drawable.ic_more,
            col(R.color.text_secondary), sizeDp = 32, iconSizeDp = 16,
            contentDescription = "More").apply {
            tag = "recv_menu_btn"
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).also {
                it.marginStart = dp(6); it.marginEnd = dp(-4)
            }
            isClickable = true; isFocusable = true
        })
        // GOAT (Part 2 — folder as a single transfer item): only ever
        // shown for an expandable folder-group row (Received tab — see
        // updateGroupRow); GONE for every solo-file row and for a group
        // row on a tab that doesn't support expansion (Active/Sent, per
        // spec: "Do NOT expand it here" / "No unnecessary expansion
        // complexity").
        row1.addView(ImageView(ctx).apply {
            tag = "chevron"
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).also { it.marginStart = dp(4) }
        })
        card.addView(row1)

        // ── FINAL PASS 3 (§9): tv_detail lives INSIDE the title's column ──
        // (directly under the name, beside the thumbnail) — no 22dp inset
        // (the column provides alignment) and no extra card row.
        // ── FINAL PASS 5 (§6): the FP4 "detail_row" wrapper is retired —
        // the ⋮ moved UP into the title's own row (see above), so the
        // metadata returns to the plain Pass 3 shape: a single weighted
        // TextView directly inside the title column. One less layout level,
        // and the gray line can never again share a row with the menu.
        // ── FINAL PASS 6 (PART 4.1/6): the secondary row settles at 10.5sp ──
        // muted — the same quiet gray metadata treatment in BOTH tabs — with
        // a 3dp top gap from the title (a consistent title→metadata rhythm)
        // instead of the old 1dp hairline, and the alpha dropped in favor of
        // the palette's own text_muted (no double-dimming — the muted color
        // is already the right gray; 0.7 alpha over it made it
        // indistinguishable from the background on dim screens).
        // ── FINAL PASS 7 (PART 3 — the "metadata invisible" root cause): ──────
        // text_muted (#676E77) on the cards' actual surface (bg_secondary
        // #2A2E37, via bg_btn_secondary) computes to a 2.64:1 contrast ratio
        // — below even WCAG's large-text 3:1 floor and far below AA's 4.5:1.
        // At 10.5sp that is, in practice, INVISIBLE on a real screen: the
        // requirement's "I see nothing" report was exactly right, and the
        // data flow was never broken (tv_detail is set from detailLine with
        // real fmtBytes/fmtTimeAgo values on every bind — verified line by
        // line). The fix is the palette's own next step — text_secondary
        // (#8F97A3), 4.61:1 on the same surface (WCAG AA, still quiet gray),
        // and one size step up (10.5sp → 11sp) for small-size legibility.
        // The title stays text_primary + 13sp, so the hierarchy (protagonist
        // title, quiet-but-readable metadata) is exactly the required
        // "minimalist, subtle grayish" — visible this time.
        textCol.addView(TextView(ctx).apply {
            tag = "tv_detail"
            textSize = 11f
            setTextColor(col(R.color.text_secondary))
            setPadding(0, dp(3), 0, 0)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            // ── FINAL PASS 8 (PART 1 — THE REAL root cause of "invisible ────
            // ── metadata", after four passes of treating symptoms): FP4 ──
            // introduced this line's params as LayoutParams(0, WRAP, 1f) —
            // a HORIZONTAL-container recipe (0dp weighted width so the
            // ellipsis bounds to the parent) that moved into this VERTICAL
            // column during the FP5 shell rebuild WITHOUT adaptation. In a
            // vertical LinearLayout the child's width comes from its layout
            // params directly; weight is meaningless for width. width=0 ⇒
            // the TextView measured EXACTLY ZERO PIXELS WIDE — the whole
            // metadata line (time-ago · size · peer) was laid out as a
            // zero-width object on every card in every tab, whatever color
            // or size it was given. FP7's contrast fix (text_muted →
            // text_secondary) treated a real-but-secondary issue and could
            // never have made this line visible — a 0px-wide view is
            // invisible in ANY color, which is exactly why the user still
            // saw nothing after FP7. The correct vertical-column recipe is
            // the one tv_name (two views above, same column) always used:
            // MATCH_PARENT width, WRAP_CONTENT height — the column ITSELF
            // is already the weighted 0dp child of the HORIZONTAL row1,
            // which is what actually bounds this line's width and lets the
            // END ellipsis work. FP7's contrast+size work (text_secondary,
            // 4.61:1 AA, 11sp) is kept — NOW it finally applies to a view
            // that actually renders.
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        })

        // GOAT (transfer stability pass — progress visibility): always
        // present now, visibility toggled in updateRow instead of only
        // ever being created here, conditionally, based on whichever
        // Transfer this row happened to be FIRST built for. That was a
        // real, standalone bug even before RecyclerView recycling existed:
        // a row first built while its transfer was still PENDING never
        // gained a progress bar at all once it turned ACTIVE, because the
        // old updateRow only ever updated a progress bar if one already
        // existed on that row — it never created one.
        card.addView(ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            tag = "pb"
            max = 100
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(3)).also { it.topMargin = dp(5) }
        })

        // ── Pass-25 (#1: the live speed, IN the action row — no extra row) ──
        // Pass-24 gave the live speed its own line under the bar, which made
        // every card a full row taller than the compact layout it was meant
        // to be. The action row directly beneath the bar was 36dp tall with a
        // completely empty leading half — so the speed caption now lives in
        // THAT leading half: a weighted (fills the left space) TextView whose
        // trailing 36dp buttons keep their exact end-anchored position, with
        // the row's CENTER_VERTICAL gravity keeping the ~13dp caption riding
        // the middle of the 36dp button band. The row is no taller than it
        // ever was (the 36dp buttons dominate it), so the item's height is
        // back to the compact layout with the speed still directly under the
        // progress bar, in the same quiet accent tint as before. Populated
        // only for live (ACTIVE) rows — see applySpeedView. The view is
        // created here in the shell like every other tagged child, so a
        // recycled row can never misplace it; buildActionButtons' rebuilds
        // go through clearActionButtons, which deliberately preserves it.
        val speedCaption = TextView(ctx).apply {
            tag = "tv_speed"
            textSize = 10f
            setTextColor(col(R.color.accent_light))
            // Aligns the caption with the detail line above (the same 22dp
            // inset Pass-24 used), with an end pad so the longest speed text
            // can never collide with the first action button (which adds its
            // own 8dp leading margin on top).
            setPadding(dp(22), 0, dp(8), 0)
            maxLines = 1
            visibility = View.GONE
            // weight=1 / width=0: consumes the row's whole leading half, so
            // the buttons appended after it always pack flush against the
            // row's trailing edge exactly as they did pre-Pass-24. A GONE
            // weighted child is skipped entirely by LinearLayout (no space,
            // no weight), so terminal rows' buttons right-align exactly as
            // they always have.
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        // ── GOAT-PASS 8 (§8 — the status chip's new home): the transfer-state ──
        // text now lives in THIS row (the lower information band), as its
        // FIRST child — immediately LEFT of the transfer-speed caption, which
        // in turn sits left of the Pause/Cancel buttons that pack flush right.
        // The title row above keeps its full width for the item's name (a long
        // "Auto-Resume on Reconnection 62%" chip used to squeeze the MIDDLE-
        // ellipsized title into an unreadable sliver); on a recovery card
        // (whose only action is the ✕) the chip reads as sitting immediately
        // left of the ✕, exactly as the requirement describes. Built here in
        // the shell (data-independent views only — the text/color land per
        // bind through applyStatusChip), so a recycled row can never misplace
        // it and clearActionButtons preserves it by tag.
        val shellStatusChip = TextView(ctx).apply {
            tag = "status_chip"
            textSize = 9.5f; typeface = Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(7), dp(3), dp(7), dp(3))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).also { it.marginEnd = dp(8) }
        }
        card.addView(LinearLayout(ctx).apply {
            tag = "act_row"
            orientation = LinearLayout.HORIZONTAL
            // CENTER_VERTICAL keeps the caption vertically centered against
            // the 36dp buttons; END keeps the button cluster flush right
            // whenever the speed slot is GONE (every non-live row) — the
            // exact pre-Pass-25 alignment.
            gravity = Gravity.CENTER_VERTICAL or Gravity.END
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.topMargin = dp(4) }
            addView(shellStatusChip)
            addView(speedCaption)
        })

        // GOAT (Phase 2, item 4 -- independent scroll container): the
        // expanded tree used to be a plain LinearLayout added directly to
        // the card, which meant a big folder's expansion just grew the
        // card to fit every child, turning the OUTER RecyclerView's own
        // scroll into the only way to see the rest of the tree -- and
        // forced the artificial 50-item "+X more" cap in
        // renderGroupChildren, since an unbounded card that size for a
        // 300-file folder was unusable. Wrapping child_list in a
        // NestedScrollView with a capped height instead gives the tree
        // its own bounded, independently-scrollable viewport: a vertical
        // drag starting inside it scrolls IT first, handing off to the
        // outer list's own scroll only once the tree has nothing further
        // to scroll in that direction. That's also what makes the cap
        // removable: the tree can now hold every file a folder actually
        // has, genuinely reachable by scrolling, with no dead end.
        //
        // GOAT v3 (§3.1): the box itself is now the shared FolderBox
        // component (wrap-up-to-280dp instead of a fixed 280dp block, edge
        // fades, custom scrollbar indicator, the upgraded
        // claimVerticalDrags with scroll-limit hand-off) — same tags as
        // before so every findTagged lookup below is unchanged.
        //
        // GOAT (Phase 2 regression fix): this card previously claimed the
        // nested-scrolling protocol alone was enough here ("no manual
        // touch-interception needed"), on the reasoning that this
        // RecyclerView has no further outer NestedScrollView competing
        // for the same drag the way the Queue's row does. Testing found
        // scrolling broken in "all places where we see folder expansion,"
        // without pinning down whether this specific instance was
        // included -- rather than leave that ambiguous, claimVerticalDrags
        // (see its own doc comment) is applied here too. It's a no-op
        // exactly where the original claim was correct, and closes the
        // gap everywhere it wasn't. (FolderBox applies it, along with
        // isNestedScrollingEnabled = false — the §2.5 retest-regression
        // companion fix — for all three instances at once.)
        val folderBox = FolderBox(ctx, 14, 4, 4, 2).also {
            it.scroll.tag = "child_list_scroll"
            it.content.tag = "child_list"
            // ── GOAT-PASS 9 (§11 — accidental parent selection while ──
            // interacting with expanded contents): a tap that lands on the
            // box's non-row areas (the content's padding, the gaps between
            // rows, the breadcrumb strip's empty spans) used to synthesize
            // a CLICK on the nearest clickable ancestor — the PARENT FOLDER
            // CARD — toggling its expansion, or its SELECTION while a
            // selection was active (the reported "tried to scroll the
            // contents and the parent became selected", typically on a
            // second, shorter gesture whose movement stayed under the touch
            // slop). Both layers now CONSUME clicks themselves: the deepest
            // clickable target for a padding/gap tap is the box, whose click
            // is a deliberate no-op — the parent card can never be activated
            // from inside its own expanded content. Child rows, breadcrumb
            // segments and scrolling are untouched (children dispatch first;
            // claimVerticalDrags' touch listener returns false).
            it.scroll.isClickable = true
            it.content.isClickable = true
        }
        card.addView(folderBox.root)

        return card
    }

    /**
     * Single source of truth for everything about a row that depends on
     * [t] — called on every bind, whether this is a brand-new row or one
     * recycled from a completely different Transfer a moment ago, so
     * nothing here can assume anything about the row's current state
     * beyond what [createRowShell] always guarantees exists.
     *
     * Phase 5: [selectionActive]/[selected] come from the row's snapshot
     * state (they're what DiffUtil compares, so a toggle rebinds exactly
     * the affected row); [holder] carries the row's in-flight thumbnail
     * job so a rebind cancels the departed decode instead of letting it
     * burn a permit for a row that no longer shows that file.
     */
    private fun updateRow(card: LinearLayout, t: TransferManager.Transfer, selectionActive: Boolean, selected: Boolean, canEnterSelection: Boolean, selTab: String, holder: TransferRowViewHolder? = null) {
        // Pass-28 (#1.2): the row a recycled card LAST showed — used to decide
        // whether a selection-state change is a state flip on the SAME row
        // (animate smoothly) or a fresh bind of a different row (apply
        // instantly, never animating a recycled card from a stale tint).
        val prevRowId = card.tag as? String
        card.tag = t.id
        // Pass-28 (#1.2): the checkmark circle is GONE — the entire row
        // container now carries the selection state (see applySelectionVisual).
        applySelectionVisual(card, selectionActive, selected, sameRow = prevRowId == t.id)
        // ── Pass-16 (text messages render as MESSAGES, never files) ──────────
        // A text row carries its content in the engine, not on disk: the
        // leading tile is the copy glyph on the accent badge, the detail
        // line is the snippet + character count, and the tap opens the full
        // text dialog (Copy inside). Nothing about it can fall back to a
        // file-type icon or a byte-count line — the recycled-row reset
        // below still runs for FILE rows exactly as before.
        if (t.isTextMessage) {
            findTagged<ImageView>(card, "thumb")?.apply {
                setImageDrawable(Widgets.icon(requireContext(), R.drawable.ic_copy,
                    ContextCompat.getColor(requireContext(), R.color.accent_light)))
                background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_accent_badge)
                setPadding(dp(5), dp(5), dp(5), dp(5))
            }
            findTagged<ImageView>(card, "dir_badge")?.apply {
                // Recycled rows must never keep the previous file's badge —
                // the text branch sets its own, exactly like the file branch.
                setImageDrawable(Widgets.icon(requireContext(),
                    if (t.direction == Direction.UPLOAD) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up,
                    android.graphics.Color.WHITE))
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (t.direction == Direction.UPLOAD) col(R.color.accent) else col(R.color.orange))
                visibility = View.VISIBLE
            }
            findTagged<TextView>(card, "tv_name")?.text = "Text message"
            paintDetailLine(findTagged(card, "tv_detail"), t, selTab == "received", selTab == "sent")
            registerAgoTarget(findTagged(card, "tv_detail"), t, selTab == "received", selTab == "sent")
            // ── GOAT-PASS 8 (§8): the status chip lives in the LOWER action row ──
            // (findStatusChip resolves it there — the shell builds it once),
            // leaving the title row's full width to the item's name.
            findStatusChip(card)?.let { applyStatusChip(it, t) }
            findTagged<View>(card, "chevron")?.visibility = View.GONE
            findTagged<ProgressBar>(card, "pb")?.visibility = View.GONE
            applySpeedView(card, null)   // Pass-25 (#1): a text row has no speed caption
            FolderBox.ownerOf(findTagged<NestedScrollView>(card, "child_list_scroll"))?.setExpandedInstant(false)
            findTagged<LinearLayout>(card, "child_list")?.removeAllViews()
            val actRowT = findTagged<LinearLayout>(card, "act_row")
            clearActionButtons(actRowT)
            if (actRowT != null) buildActionButtons(t, actRowT)
            card.isClickable = true
            // ── Pass-28 (#1.1): text rows select like every other row ──────
            // While selection is active a tap toggles the row; the full-text
            // dialog stays the normal-mode tap action, unchanged.
            // ── GOAT-PASS 7 (§3A): long-press is the range anchor, exactly ──
            // like every other row on this surface.
            if (selectionActive) {
                val selKey = "solo:${t.id}"
                card.setOnClickListener { toggleSelection(selTab, selKey) }
                // ── GOAT-PASS 7 (§3A): long-press is the RANGE anchor inside ──
                // selection mode too — the Files sheet's exact contract.
                card.setOnLongClickListener { longPressSelection(selTab, selKey); true }
            } else {
                card.setOnClickListener { if (t.status != Status.PENDING && t.status != Status.ACTIVE) showTextMessageDialog(t) }
                // ── FINAL PASS 5 (§6): a text row never carries the ⋮ — this ──
                // branch returns below, so the shell slot must be retired HERE
                // (a recycled card from a received file row would otherwise
                // keep its visible ⋮ and its stale listener).
                findTagged<ImageView>(card, "recv_menu_btn")?.visibility = View.GONE
                // ── GOAT-PASS 15 (§9.1): the Active tab's live-row entry (its
                // lifecycle toolbar); every other tab keeps the completed-row
                // deletion entry.
                val textMayEnter = if (selTab == "active")
                    (t.status == Status.PENDING || t.status == Status.ACTIVE)
                else t.status == Status.COMPLETED
                card.setOnLongClickListener(
                    if (canEnterSelection && textMayEnter) {
                        { longPressSelection(selTab, "solo:${t.id}"); true }
                    } else null
                )
            }
            return
        }
        findTagged<ImageView>(card, "thumb")?.let { thumbView ->
            // Reset to the generic file-type icon first — loadThumbnailInto
            // only overwrites this asynchronously, if/once a real thumbnail
            // decodes successfully, so a recycled row must not keep
            // showing whatever bitmap the PREVIOUS Transfer it displayed
            // happened to have loaded.
            thumbView.setImageDrawable(FormatIcons.icon(ctxOrNull() ?: return, t.fileName.substringAfterLast('.', "")))
            // ── FINAL PASS 8 (PART 3): the rounded backing now STAYS on every ──
            // bind (it is exactly the "neutral theme-compatible container
            // background" small icons need); only the padding reset below
            // remains. The generic file-type glyph tiles are opaque, so
            // nothing changes for them.
            ctxOrNull()?.let { thumbView.background = ContextCompat.getDrawable(it, R.drawable.bg_thumb_small) }
            thumbView.setPadding(0, 0, 0, 0)
            // ── FINAL PASS 8 (PART 3 — scale type per category): rows are ──
            // RECYCLED, so the scale type must be re-derived per bind, not
            // just set once in the shell. Media thumbnails (photos, video
            // frames, album art) fill edge-to-edge with CENTER_CROP — the
            // correct crop for real photographs; an APK launcher icon rides
            // FIT_CENTER — its artwork is smaller than the tile with wide
            // transparent margins, and CENTER_CROP would crop into the
            // artwork exactly like setBounds-stretching distorts it. Same
            // category source the thumbnail load itself uses.
            val bindCategory = com.lumidrop.x.media.ThumbnailEngine.categoryOf(t.fileName.substringAfterLast('.', ""))
            thumbView.scaleType =
                if (bindCategory == com.lumidrop.x.media.ThumbnailEngine.FileCategory.APK)
                    ImageView.ScaleType.FIT_CENTER
                else ImageView.ScaleType.CENTER_CROP
            loadThumbnailInto(thumbView, t, holder)
        }
        findTagged<ImageView>(card, "dir_badge")?.apply {
            // ── FINAL PASS 5 (§7): the purple direction badge is hidden on ─────
            // the RECEIVED tab's solo cards — every row there is by definition
            // an incoming item, so the downward-arrow badge communicates
            // nothing and its bottom-right corner is exactly the visual noise
            // this pass removes. History keeps the badge system exactly as it
            // was (received = purple down, sent = orange up), and the Active
            // tab keeps it too (live rows genuinely benefit from the
            // direction cue). The badge VIEW is merely hidden — no layout
            // hole is left (GONE views occupy no space in the FrameLayout
            // stack), and the underlying status/classification is untouched.
            if (selTab == "received") {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                setImageDrawable(Widgets.icon(requireContext(),
                    if (t.direction == Direction.UPLOAD) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up,
                    android.graphics.Color.WHITE))
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (t.direction == Direction.UPLOAD) col(R.color.accent) else col(R.color.orange))
            }
        }
        findTagged<TextView>(card, "tv_name")?.let { nameView ->
            // ── FINAL PASS 5 (§5): polished, compact title truncation. The raw
            // filename is still the source of truth (nothing on disk changes);
            // what the CARD shows is a display form tuned for the row's real
            // width — see displayTitleFor's own doc. The Received tab gets the
            // refined form (the requirement targets it); every other tab
            // keeps the exact text it always showed.
            nameView.text = if (selTab == "received") displayTitleFor(t.fileName) else t.fileName
        }
        paintDetailLine(findTagged(card, "tv_detail"), t, selTab == "received", selTab == "sent")
        registerAgoTarget(findTagged(card, "tv_detail"), t, selTab == "received", selTab == "sent")
        findTagged<ProgressBar>(card, "pb")?.apply {
            visibility = if (t.status == Status.ACTIVE) View.VISIBLE else View.GONE
            if (t.status == Status.ACTIVE) {
                isIndeterminate = t.isIndeterminate
                if (!t.isIndeterminate) progress = t.progressPct
                val tint = android.content.res.ColorStateList.valueOf(
                    if (t.pauseRequested) col(R.color.orange) else col(R.color.accent))
                progressTintList = tint
                indeterminateTintList = tint
            }
        }
        // Pass-24 (#5): the speed line directly beneath this item's bar.
        // ── FINAL PASS 3 (§2 — the auto-continue caption cleanup): the
        // streamed-ZIP waiting states (sender: live row latched reconnecting;
        // receiver: the kept "Auto-Continue on Reconnection" card) no longer
        // carry the former "Next attempt restarts from 0 MB" caption line —
        // the chip alone tells the story, so the speed slot is hidden
        // entirely for those rows (no orphaned "…" placeholder where a
        // reading can never exist: no bytes flow while the connection is
        // down, and the dead attempt's counter is zeroed on purpose).
        applySpeedView(card, when {
            t.isZipStream && t.status == Status.ACTIVE && t.reconnecting -> null
            t.status == Status.ACTIVE -> speedTextFor(t)
            else -> null
        })
        // ── GOAT-PASS 8 (§8): the status chip lives in the LOWER action row — ──
        // findStatusChip resolves the shell-built chip there; a recycled row
        // can never duplicate it, and the title row above keeps its full
        // width for the file name.
        findStatusChip(card)?.let { applyStatusChip(it, t) }
        val row1 = card.getChildAt(0) as? LinearLayout
        // A solo file never shows the expand chevron -- only a group
        // row does (see updateGroupRow). Explicit, not conditional:
        // this row may have been recycled from a group a moment ago.
        row1?.let { findTagged<View>(it, "chevron")?.visibility = View.GONE }
        // GOAT v4: this card may be recycled straight from the expanded
        // group's row — if its previous tag WAS the expanded group, the
        // live box/chevron refs just became stale (that card now shows a
        // solo transfer), so drop them before anything can act on them.
        val soloPrevTag = card.tag as? String
        if (soloPrevTag != null && soloPrevTag == expandedGroupKey) {
            expandedGroupBox = null
            expandedGroupChevron = null
        }
        FolderBox.ownerOf(findTagged<NestedScrollView>(card, "child_list_scroll"))?.setExpandedInstant(false)
        findTagged<LinearLayout>(card, "child_list")?.removeAllViews()

        val actRow = findTagged<LinearLayout>(card, "act_row")
        clearActionButtons(actRow)
        if (actRow != null) buildActionButtons(t, actRow)

        val shouldBeClickable = t.status != Status.PENDING && t.status != Status.ACTIVE && t.filePath != null
        card.isClickable = true
        // Phase 5 (§5.4): in Received's selection mode a tap toggles the
        // row instead of opening the file — accidental open-while-selecting
        // is exactly the destructive-action slip the confirmation
        // consistency item exists to prevent. Long-press ENTERS selection
        // mode on a completed row (and is consumed so it can't also fire
        // the click).
        if (selectionActive) {
            val key = "solo:${t.id}"
            card.setOnClickListener { toggleSelection(selTab, key) }
            // ── GOAT-PASS 7 (§3A): long-press is the RANGE anchor inside ──
            // selection mode too — with a valid anchor it selects the whole
            // inclusive span to this row, additively (the Files sheet's exact
            // contract, now identical on all four list surfaces).
            card.setOnLongClickListener { longPressSelection(selTab, key); true }
            // ── FINAL PASS 5 (§6/§7): the ⋮ is suppressed while a row is ──
            // SELECTED (an action menu mid-selection invites destructive
            // slips) — a row that carried it before the mode flipped must
            // retire it here, on the selection rebind, exactly the way the
            // non-selection branch below re-shows it when the mode ends.
            findTagged<ImageView>(card, "recv_menu_btn")?.visibility = View.GONE
        } else {
            // ── FINAL PASS 5 (§8): the card click routes through ──────────────
            // openTransferFile — images, videos and audio open in the app's
            // OWN internal players (the Media Picker's preview overlay for
            // images/videos, the shared MusicPlayback controller for audio),
            // and every other type (documents, APKs, archives…) keeps the
            // exact openFile behavior it always had. openFile itself is
            // untouched (still the fallback inside openTransferFile).
            card.setOnClickListener { if (shouldBeClickable) openTransferFile(t) }
            // ── FINAL PASS 5 (§6 + §1): the shell-built ⋮ slot (top-right of ──
            // the title row) is wired HERE, on every qualifying bind. The
            // FP4 dynamic add/remove is retired: the button is a permanent
            // shell child, so a recycled card never carries a stale one and
            // the visibility flag is the entire rebind cost. Qualification
            // is unchanged from FP4 (completed received FILE rows on the
            // Received tab); the click closure always references THIS bind's
            // transfer because the listener is re-set right here, every
            // bind, whether the button was already visible or not.
            // FINAL PASS 5 (§7): the menu is suppressed while a row is
            // SELECTED — same discipline as the FP4 selection branch (an
            // action menu mid-selection invites destructive slips), and it
            // returns the instant selection mode ends.
            val recvMenu = findTagged<ImageView>(card, "recv_menu_btn")
            if (selTab == "received" && t.status == Status.COMPLETED &&
                t.direction == Direction.UPLOAD && !t.isTextMessage && t.filePath != null) {
                recvMenu?.visibility = View.VISIBLE
                recvMenu?.setOnClickListener { v -> showReceivedCardMenu(v, t) }
                recvMenu?.setOnLongClickListener { v -> showReceivedCardMenu(v, t); true }
            } else {
                recvMenu?.visibility = View.GONE
                recvMenu?.setOnClickListener(null)
                recvMenu?.setOnLongClickListener(null)
            }
            // Phase 5 (§5.4): long-press a COMPLETED row on the Received tab
            // to enter multi-select mode (consumed so it can't also fire the
            // click). Only rows that can actually be deleted may enter — a
            // PENDING/ACTIVE row has no file on disk yet, so selecting it
            // for deletion would be a promise the Delete action can't keep.
            // ── GOAT-PASS 15 (§9.1): the ACTIVE tab's entry contract is the
            // transfer lifecycle — a LIVE row (waiting, streaming, paused or
            // reconnecting) is exactly what its toolbar's Pause/Resume/Cancel
            // actions can act on, so a long-press on one enters selection
            // there.
            val mayEnter = if (selTab == "active")
                (t.status == Status.PENDING || t.status == Status.ACTIVE)
            else t.status == Status.COMPLETED
            if (canEnterSelection && mayEnter) {
                card.setOnLongClickListener { longPressSelection(selTab, "solo:${t.id}"); true }
            } else {
                card.setOnLongClickListener(null)
            }
        }
    }

    /**
     * GOAT (Part 2 — folder as a single transfer item): the group
     * counterpart of [updateRow] — same shell (see [createRowShell]),
     * same tagged-child-lookup approach, bound from a folder's aggregate
     * state (via TransferManager.groupStatus/groupProgressPct/...)
     * instead of one file's own fields. [expandable] is false for
     * Active/Sent (the spec is explicit: "Do NOT expand it here" / "No
     * unnecessary expansion complexity" — those tabs only ever show the
     * collapsed summary row) and true for Received, where inspecting and
     * managing individual files is meaningful.
     */
    private fun updateGroupRow(card: LinearLayout, info: TransferManager.TransferGroupInfo, members: List<TransferManager.Transfer>, expandable: Boolean, selectionActive: Boolean = false, selected: Boolean = false, canEnterSelection: Boolean = false, selTab: String = "received") {
        // GOAT v3 (§3.1 — scroll preservation across rebinds): card.tag
        // ("group:<id>", set a few lines down) is the identity this row
        // LAST showed; capturing it before it's overwritten distinguishes
        // a same-group in-place refresh (progress/filesDone changed while
        // expanded — restore the box's scroll offset after re-rendering)
        // from a fresh/recycled bind of a different group (reset to top,
        // exactly the recycled-row guard that unconditional scrollTo(0,0)
        // used to try to provide — at the cost of also snapping every
        // same-group refresh to the top).
        val previousKey = card.tag as? String
        val childListScrollEarly = findTagged<NestedScrollView>(card, "child_list_scroll")
        val savedScrollY = childListScrollEarly?.scrollY ?: 0
        card.tag = "group:${info.id}"
        val status = TransferManager.groupStatus(members)
        val progressPct = TransferManager.groupProgressPct(members)
        val isIndeterminate = TransferManager.groupIsIndeterminate(members)
        val filesDone = members.count { it.status == Status.COMPLETED }

        // Pass-28 (#1.2): the container carries the selection state now —
        // no more checkmark circle (see applySelectionVisual). previousKey
        // is already captured at the top of this function.
        applySelectionVisual(card, selectionActive, selected, sameRow = previousKey == "group:${info.id}")
        findTagged<ImageView>(card, "thumb")?.setImageDrawable(Widgets.icon(requireContext(), R.drawable.ic_folder, col(R.color.accent_light)))
        findTagged<ImageView>(card, "dir_badge")?.apply {
            // ── FINAL PASS 5 (§7): the group badge hides on the Received tab ──
            // exactly like the solo rows' badge — History and Active keep the
            // full direction-badge system.
            if (selTab == "received") {
                visibility = View.GONE
            } else {
                visibility = View.VISIBLE
                setImageDrawable(Widgets.icon(requireContext(),
                    if (info.direction == Direction.UPLOAD) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up,
                    android.graphics.Color.WHITE))
                backgroundTintList = android.content.res.ColorStateList.valueOf(
                    if (info.direction == Direction.UPLOAD) col(R.color.accent) else col(R.color.orange))
            }
        }
        findTagged<TextView>(card, "tv_name")?.text = info.name
        // ── FINAL PASS 5 (§2): Received's folder cards hide the sender ────────
        // segment too (History keeps it); (§7) the group purple badge hides
        // on the Received tab exactly like the solo rows' badge.
        paintGroupDetailLine(findTagged(card, "tv_detail"), info, members, filesDone, selTab == "received", selTab == "sent")
        registerGroupAgoTarget(findTagged(card, "tv_detail"), info, members, selTab == "received", selTab == "sent")
        findTagged<ProgressBar>(card, "pb")?.apply {
            // GOAT (Phase 1 continuation, item 1): a folder with hundreds
            // of files sitting behind a small concurrency limit
            // (receivingGate on receive, MAX_CONCURRENT_FILES on send)
            // spends real, repeated moments where EVERY member is
            // momentarily not ACTIVE -- file N just finished, file N+1
            // hasn't been promoted from PENDING yet. groupStatus()
            // correctly reports PENDING for that instant, but hiding the
            // bar on every one of those gaps (which repeats once per
            // file -- up to hundreds of times over one folder's transfer)
            // reads as exactly the flicker this fix is for. Once the
            // folder has genuinely started (any file already done, or any
            // bytes at all transferred), keep the bar visible through a
            // PENDING gap too, showing the same real aggregate percentage
            // — only a folder that hasn't started at all yet hides it.
            val started = filesDone > 0 || progressPct > 0
            val show = status == Status.ACTIVE || (status == Status.PENDING && started)
            visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                // Indeterminate only while truly ACTIVE with no known
                // sizes yet — a PENDING gap always has a real, known
                // percentage (from whatever's already completed), so it
                // should show that number rather than a spinner.
                this.isIndeterminate = status == Status.ACTIVE && isIndeterminate
                if (!this.isIndeterminate) progress = progressPct
                val anyPaused = members.any { (it.status == Status.PENDING || it.status == Status.ACTIVE) && it.pauseRequested }
                val tint = android.content.res.ColorStateList.valueOf(if (anyPaused) col(R.color.orange) else col(R.color.accent))
                progressTintList = tint; indeterminateTintList = tint
            }
        }
        // Pass-24 (#5): the aggregate speed of the folder's live members,
        // under the bar — null (hidden) whenever the group is not ACTIVE.
        applySpeedView(card, if (status == Status.ACTIVE) groupSpeedTextFor(members) else null)

        val row1 = card.getChildAt(0) as? LinearLayout
        val chevron = row1?.let { findTagged<ImageView>(it, "chevron") }
        // ── FINAL PASS 5 (§6): a group row never carries the ⋮ (the menu is
        // a received FILE row's action; folder rename/share is a filesystem
        // operation over many files — the FP4 scope decision, unchanged).
        // A card recycled from a qualifying file row is retired here.
        findTagged<ImageView>(card, "recv_menu_btn")?.visibility = View.GONE
        // ── GOAT-PASS 8 (§8): the group's aggregate chip lives in the LOWER ──
        // action row with every other status (findStatusChip resolves the
        // shell-built chip there).
        findStatusChip(card)?.let { applyGroupStatusChip(it, status, progressPct, isIndeterminate, filesDone > 0) }

        val childListScroll = findTagged<NestedScrollView>(card, "child_list_scroll")
        val childList = findTagged<LinearLayout>(card, "child_list")
        val folderBox = FolderBox.ownerOf(childListScroll)
        // (Pass-26 #7: the box's bottom Collapse pill is gone, so its
        // onRequestCollapse wiring is gone with it — the group card's own
        // header click remains the one collapse path, as before.)
        val groupKey = "group:${info.id}"
        val isExpanded = expandable && groupKey == expandedGroupKey
        // GOAT v4 (accordion): if this card USED to show the expanded group
        // and now shows something else, the stored live refs are stale.
        if (previousKey != null && previousKey == expandedGroupKey && groupKey != expandedGroupKey) {
            expandedGroupBox = null
            expandedGroupChevron = null
        }
        if (expandable && !selectionActive) {
            chevron?.visibility = View.VISIBLE
            chevron?.setImageDrawable(Widgets.icon(requireContext(), R.drawable.ic_chevron_right, col(R.color.text_muted)))
            chevron?.rotation = if (isExpanded) 90f else 0f
        } else {
            // Phase 5 (§5.4): no expand affordance while selecting — a tap
            // means "toggle selection" here, and a chevron that promises
            // expansion would be a lie in exactly the mode where accidental
            // destructive slips matter most.
            chevron?.visibility = View.GONE
        }
        folderBox?.setExpandedInstant(isExpanded)
        if (isExpanded) {
            // GOAT v4: this card is the live home of the expanded group.
            expandedGroupBox = folderBox
            expandedGroupChevron = chevron
            val freshBind = previousKey != "group:${info.id}"
            if (freshBind) {
                // Collapsing and re-expanding (or expanding a different group
                // entirely, on a recycled row) should always land back at the
                // top of the tree, never wherever a previous group's scroll
                // position happened to be left.
                childListScroll?.scrollTo(0, 0)
            }
            renderGroupChildren(childList!!, members, groupKey)
            if (!freshBind) {
                // Same group, in-place refresh: put the box back where the
                // person had scrolled it instead of snapping to the top.
                childListScroll?.scrollTo(0, savedScrollY)
            }
        }

        card.isClickable = true
        // Phase 5 (§5.4): same selection-mode contract as solo rows — tap
        // toggles while selecting (expansion is suspended), long-press on
        // a completed group enters selection with the whole folder as one
        // selectable unit.
        if (selectionActive) {
            val key = "group:${info.id}"
            card.setOnClickListener { toggleSelection(selTab, key) }
            // ── GOAT-PASS 7 (§3A): long-press is the RANGE anchor inside ──
            // selection mode too — the same contract every other row uses.
            card.setOnLongClickListener { longPressSelection(selTab, key); true }
        } else {
            if (expandable) {
                card.setOnClickListener {
                    // GOAT v4 — accordion + deterministic animation. At most
                    // one group expanded at a time (expanding another collapses
                    // the first in place); the box's geometry runs through
                    // FolderBox's own ValueAnimator-on-layoutParams height
                    // machine instead of TransitionManager — the v3
                    // beginDelayedTransition approach leaked intermediate
                    // bounds/visibility whenever its 220ms window was
                    // interrupted (the on-device-confirmed "container keeps
                    // its increased height after collapse"), and every frame
                    // of the new animation IS a real layout pass, so there is
                    // no separate visual state left behind to leak. No rebind
                    // runs on this path at all.
                    val nowExpanded = expandedGroupKey != groupKey
                    if (nowExpanded) {
                        val prevBox = expandedGroupBox
                        val prevChevron = expandedGroupChevron
                        expandedGroupKey = groupKey
                        if (prevBox != null && prevBox !== folderBox) {
                            FolderBox.animateChevron(prevChevron, expanded = false)
                            prevBox.collapse()
                        }
                        expandedGroupBox = folderBox
                        expandedGroupChevron = chevron
                        FolderBox.animateChevron(chevron, expanded = true)
                        renderGroupChildren(childList!!, members, groupKey)
                        childListScroll?.scrollTo(0, 0)
                        folderBox?.expand()
                    } else {
                        expandedGroupKey = null
                        expandedGroupBox = null
                        expandedGroupChevron = null
                        FolderBox.animateChevron(chevron, expanded = false)
                        folderBox?.collapse()
                    }
                }
                // ── GOAT-PASS 15 (§9.1): live groups enter selection on the
                // Active tab (its lifecycle toolbar can act on them); completed
                // groups keep the classic deletion entry elsewhere.
                val groupMayEnter = if (selTab == "active")
                    members.any { it.status == Status.PENDING || it.status == Status.ACTIVE }
                else status == Status.COMPLETED
                if (canEnterSelection && groupMayEnter) {
                    card.setOnLongClickListener { longPressSelection(selTab, "group:${info.id}"); true }
                } else {
                    card.setOnLongClickListener(null)
                }
            } else {
                // ── Pass-28 (#1.1): non-expandable group rows (the History ──
                // tab's folder records) select too — same gesture contract
                // as every other row. Normal mode keeps the existing no-op
                // tap (a collapsed History folder opens nothing, exactly as
                // before), while long-press enters selection.
                val historyGroupMayEnter = if (selTab == "active")
                    members.any { it.status == Status.PENDING || it.status == Status.ACTIVE }
                else status == Status.COMPLETED
                if (canEnterSelection && historyGroupMayEnter) {
                    card.setOnClickListener(null)
                    card.setOnLongClickListener { longPressSelection(selTab, "group:${info.id}"); true }
                } else {
                    card.setOnClickListener(null)
                    card.setOnLongClickListener(null)
                }
            }
        }

        val actRow = findTagged<LinearLayout>(card, "act_row")
        clearActionButtons(actRow)
        if (actRow != null) buildGroupActionButtons(info, members, actRow, receivedFolder = canEnterSelection)
    }

    /** ── FINAL PASS 5 (§2): [hidePeer] — the folder card's twin of ────────────
     *  detailLine's own flag: the Received tab's group cards omit the
     *  "from [Device]" segment while History's keep it. The folder's sender
     *  is still carried on the group info itself (nothing deleted — only
     *  this display omits it). */
    private fun groupDetailLine(info: TransferManager.TransferGroupInfo, members: List<TransferManager.Transfer>, filesDone: Int, hidePeer: Boolean = false, history: Boolean = false): String {
        // ── GOAT-PASS 14 (Phase 1, §4): the "N/M files" total counts the ──────
        // ELIGIBLE members (TransferManager.eligibleMembers — every child
        // except the DECLINED ones). A child the receiver excluded never
        // streams and never completes, so it must leave the denominator the
        // moment its Declined verdict lands: a folder that finished "25/25"
        // on the receiver reads "25/25 files" here too, never a lingering
        // "25/30" while the declined rows dismiss on their own 400 ms.
        val eligible = TransferManager.eligibleMembers(members)
        val totalBytes = eligible.sumOf { it.totalBytes.coerceAtLeast(0L) }
        val sizeLabel = if (totalBytes > 0) fmtBytes(totalBytes) else ""
        val countLabel = "$filesDone/${eligible.size} files"
        // Pass-15 (no more "Unknown sender"): a blank peer label (nothing
        // recorded for this folder) is simply omitted — never a guess.
        // ── FINAL PASS 5 (§2): hidePeer drops the segment on the Received ──
        // tab's cleaner cards; History keeps rendering it.
        val peerLabel = when {
            hidePeer -> ""
            // ── FINAL PASS 9 (PART 3.1): History rows show the BARE device ──
            // name — the prefix is gone; direction rides the device-name
            // COLOR painted by updateGroupRow's history span below. The
            // non-History ("from X"/"to X") labels stay exactly as they were.
            history -> if (info.peerName.isBlank()) "" else info.peerName
            info.direction != Direction.UPLOAD -> "to ${info.peerName}"
            info.peerName.isBlank() -> ""
            else -> "from ${info.peerName}"
        }
        // ── GOAT-PASS 10 (§5 — folder cards show the received-time text ──
        // too): a FINISHED folder carries the same "[Time] ago" suffix the
        // solo rows show. The folder-level receive/completion timestamp is
        // its LAST member's completion — the moment the whole folder had
        // actually arrived — never a child file's filesystem mtime and never
        // a member that is still mid-flight. In-progress and partially-lost
        // folders keep their existing line (an "ago" next to a live
        // progress readout would be noise), and old records with no usable
        // stamp omit the segment rather than lying with "just now".
        val agoLabel = if (members.isNotEmpty() && TransferManager.eligibleMembers(members).all { it.status == Status.COMPLETED }) {
            val lastDone = members.maxOf { it.finishedAt }
            if (lastDone > 0L) fmtTimeAgo(lastDone) else ""
        } else ""
        return listOf(countLabel, sizeLabel, peerLabel, agoLabel).filter { it.isNotBlank() }.joinToString("  ·  ")
    }

    // ── GOAT-PASS 8 (§8): the groupStatusChip BUILDER is retired — the shell
    // builds the one chip per card now (createRowShell's "status_chip", in
    // the lower action row), and applyGroupStatusChip below is the single
    // writer of its text/color on every bind, full or payload. Same split,
    // same reason as the solo-row twin: updateGroupRow rebinds on every
    // progress/status/filesDone change, and a large folder's group row
    // rebinds far more often than any solo file's row ever would — one chip
    // view for the row's whole lifetime, only its content ever changes.

    // GOAT (Phase 1 continuation, item 1): [started] mirrors the same
    // signal updateGroupRow's progress-bar visibility now uses -- once a
    // folder has genuinely begun (any file already done), a momentary
    // PENDING gap between one file finishing and the next being promoted
    // should still read as "in progress" (show the real percentage,
    // orange) rather than snapping to "Waiting" (grey) and back on every
    // single file, which is a label/color flicker just as visible as the
    // View-recreation one this same pass fixed.
    private fun applyGroupStatusChip(chip: TextView, status: Status, progressPct: Int, isIndeterminate: Boolean, started: Boolean) {
        // ── FINAL PASS 3 (§9): the group twin — a fully COMPLETED folder ──
        // group shows no "Done" pill either; live/failed groups keep theirs.
        if (status == Status.COMPLETED) {
            chip.visibility = View.GONE
            return
        }
        chip.visibility = View.VISIBLE
        val (label, colorRes) = when {
            status == Status.PENDING && started -> "$progressPct%" to R.color.orange
            status == Status.PENDING -> "Waiting" to R.color.text_muted
            status == Status.ACTIVE -> (if (isIndeterminate) "Working…" else "$progressPct%") to R.color.orange
            status == Status.COMPLETED -> "Done" to R.color.green
            status == Status.FAILED -> "Failed" to R.color.red_stop
            // v1.0 (§decline-sync): the receiver turned this down — its own
            // calm, honest label, never lumped into Failed/Cancelled.
            status == Status.DECLINED -> "Declined" to R.color.text_muted
            else -> "Cancelled" to R.color.red_stop
        }
        chip.text = label
        val chipColor = col(colorRes)
        chip.setTextColor(chipColor)
        (chip.background as android.graphics.drawable.GradientDrawable).setColor((chipColor and 0x00FFFFFF) or 0x33000000)
    }

    /** Every member, genuinely reachable — see createRowShell's
     *  child_list_scroll for the independent scroll container that makes
     *  this safe to do without turning a 300-file folder's expansion into
     *  an unusable wall (the old 50-item "+X more" cap this replaced was
     *  a dead end: Phase 2 explicitly requires every item in the tree to
     *  be reachable by expanding/scrolling into it, never placeholder'd
     *  away).
     *
     *  Phase 5 (§5.1 audit — child-list churn gate): the reconcile loop
     *  re-submits the Received list every 2s, and while a folder sits
     *  expanded every one of those re-submits rebinds its group row (the
     *  aggregate progress/filesDone snapshot changes constantly while
     *  receiving). Rebuilding all child rows on each of those rebinds —
     *  removeAllViews + re-add, ~5 views per child — was hundreds of view
     *  constructions every couple of seconds for a list whose own content
     *  usually didn't change at all. A cheap signature over exactly the
     *  fields a child row's VISUALS depend on (id, status, progress, path)
     *  now gates the rebuild: identical signature = the existing views are
     *  already correct, nothing to do. The signature resets whenever the
     *  expanded group itself changes, so a freshly expanded different
     *  folder always renders fresh.
     */
    private fun renderGroupChildren(container: LinearLayout, members: List<TransferManager.Transfer>,
                                    groupKey: String, force: Boolean = false) {
        val sig = members.joinToString("|") { "${it.id}:${it.status}:${it.progressPct}:${it.filePath}" }
        if (!force && sig == lastGroupChildrenSig && container.childCount > 0) return
        lastGroupChildrenSig = sig
        container.removeAllViews()
        val ctx = requireContext()
        // ── Pass-39 (#4): the group's file tree is RECURSIVE now. The old ──
        // flat member list turned "Folder/sub/x.mp4" into one undifferentiated
        // row among hundreds; the members are now grouped by their path
        // RELATIVE to the group's common root directory — subfolders render
        // as expandable rows (their own subfolders and files beneath,
        // indented, to any depth), loose files keep the exact row they
        // always had. Expansion state lives in [expandedGroupSubKeys],
        // scoped per group; a toggle re-renders the tree instantly from the
        // same in-memory members (no IO, no engine round-trip).
        val root = commonParentDir(members.mapNotNull { it.filePath })
        // ── Pass-40 (#6): the folder-location breadcrumb — one compact row ──
        // (rendered only while a nested subfolder is open) that names the
        // group's root and every ancestor of the deepest open branch, the
        // CURRENT folder accented at the end. Every parent segment is a
        // one-tap "go back up" that folds everything below its level;
        // nothing about the rows' sizes or the box's height changes.
        renderGroupBreadcrumb(container, members, groupKey, root)
        // ── GOAT-PASS 9 (§9 + §10 — the Queue's batched rendering): rows are ──
        // COLLECTED as builders and flushed in responsive batches (first 80
        // synchronously, then 160 per frame) — the exact discipline the
        // Queue's expanded folders have used since Pass-41 §8, so a huge
        // received folder opens without one long main-thread block, and the
        // two surfaces share one rendering strategy instead of two.
        val gen = ++groupChildrenGen
        val builders = mutableListOf<() -> Unit>()
        renderGroupLevel(container, members, groupKey, root, prefix = "", depth = 0, out = builders)
        flushGroupChildRows(container, builders, gen)
    }

    /** GOAT-PASS 9 (§9/§10): generation guard for the batched row flush — a
     *  re-render (subfolder toggle, member status change, the same box
     *  re-rendered) bumps the generation, and any still-streaming batch from
     *  the PREVIOUS tree stops itself instead of appending stale rows to the
     *  new one. */
    private var groupChildrenGen = 0

    /** GOAT-PASS 9 (§9/§10): the Queue's batched flush — the first 80 rows
     *  land synchronously (every realistic folder is fully on screen in the
     *  first batch), the rest stream in 160-row batches on the next frames.
     *  A superseded generation stops mid-stream; a completed one ends. */
    private fun flushGroupChildRows(container: LinearLayout, builders: List<() -> Unit>, gen: Int) {
        var i = 0
        val firstBatch = minOf(builders.size, 80)
        while (i < firstBatch) {
            try { builders[i]() } catch (_: Exception) { /* one bad row never kills the tree */ }
            i++
        }
        if (i >= builders.size) return
        container.post(object : Runnable {
            override fun run() {
                if (gen != groupChildrenGen) return   // superseded by a newer render
                val end = minOf(i + 160, builders.size)
                while (i < end) {
                    try { builders[i]() } catch (_: Exception) { /* same discipline as the first batch */ }
                    i++
                }
                if (i < builders.size && gen == groupChildrenGen) container.post(this)
            }
        })
    }

    /** ── Pass-40 (#6): the compact location row for an expanded received
     *  group (see renderGroupChildren). No-op while no nested subfolder is
     *  open. The group's own root name (its common directory's last segment,
     *  or the group's display name when the members share no directory)
     *  starts the chain; each parent segment folds the levels below it and
     *  re-renders instantly from the same in-memory members. */
    private fun renderGroupBreadcrumb(container: LinearLayout, members: List<TransferManager.Transfer>,
                                      groupKey: String, root: String) {
        val ctx = requireContext()
        val keyPrefix = "$groupKey::"
        val deepest = expandedGroupSubKeys
            .filter { it.startsWith(keyPrefix) }
            .map { it.removePrefix(keyPrefix) }
            .maxByOrNull { it.count { c -> c == '/' } } ?: return
        val parts = deepest.split('/')
        val rootName = root.substringAfterLast('/').ifEmpty {
            (members.firstOrNull()?.filePath ?: groupKey).substringAfterLast('/')
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(4))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        fun collapseToDepth(depth: Int) {
            expandedGroupSubKeys.removeAll { key ->
                key.startsWith(keyPrefix) && key.removePrefix(keyPrefix).count { c -> c == '/' } + 1 > depth
            }
            renderGroupChildren(container, members, groupKey, force = true)
        }
        fun seg(text: String, depth: Int, current: Boolean) {
            if (row.childCount > 0) row.addView(TextView(ctx).apply {
                this.text = "/"; textSize = 10.5f
                setTextColor(col(R.color.text_muted))
                setPadding(dp(1), 0, dp(1), 0)
            })
            row.addView(TextView(ctx).apply {
                this.text = text; textSize = 10.5f; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(col(if (current) R.color.accent_light else R.color.text_secondary))
                if (current) typeface = Typeface.DEFAULT_BOLD
                else {
                    isClickable = true; isFocusable = true
                    setOnClickListener { collapseToDepth(depth) }
                }
                setPadding(dp(2), dp(2), dp(2), dp(2))
            })
        }
        seg(rootName, 0, parts.size == 1)
        for (i in parts.indices) seg(parts[i], i + 1, i == parts.lastIndex)
        container.addView(row)
    }

    /** ── Pass-40 (#7): whether [name] is an audio file — the extension ──
     *  routes through the app's one MIME source of truth (the same map the
     *  transfer pipeline and the queue's own audio detection use), so the
     *  verdict can never drift between surfaces. */
    private fun isAudioFileName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return false
        return MimeTypes.getMimeType(ext).lowercase().startsWith("audio/")
    }

    /** ── Pass-40 (#7): a completed AUDIO member of an expanded received
     *  group plays IN-APP through the ONE shared controller (the same player
     *  and floating overlay the Music sheet, the queue's expanded folders and
     *  every other surface use — never a second, parallel implementation),
     *  with the group's completed audio members in transfer order as the
     *  playlist. The exact CLICKED file is what plays: the session key is the
     *  file's own path (stable identity), never a display-name match, so a
     *  same-named sibling can never steal the tap. Tapping the live row
     *  toggles play/pause, exactly like the queue's audio rows; the shared
     *  controller's metadata pass fills the title/artist/duration/artwork as
     *  soon as they are readable. */
    private fun playReceivedGroupAudio(members: List<TransferManager.Transfer>, tapped: TransferManager.Transfer) {
        val act = activity ?: return
        val key = "q:${tapped.filePath}"
        if (com.lumidrop.x.ui.MusicPlayback.isCurrent(key)) {
            com.lumidrop.x.ui.MusicPlayback.toggle(); return
        }
        val playlist = members
            .filter { it.status == Status.COMPLETED && it.filePath != null && isAudioFileName(it.fileName) }
            .sortedBy { it.seq }
            .map { m2 ->
                com.lumidrop.x.ui.MusicPlayback.Track(
                    key = "q:${m2.filePath}",
                    uri = android.net.Uri.fromFile(File(m2.filePath!!)),
                    title = m2.fileName.substringBeforeLast('.').ifBlank { m2.fileName },
                    artist = null,
                    albumId = 0L,
                    durationMs = 0L,
                    size = m2.totalBytes,
                )
            }
        val idx = playlist.indexOfFirst { it.key == key }
        if (idx >= 0) com.lumidrop.x.ui.MusicPlayback.play(act, playlist, idx)
    }

    /** ── Pass-39 (#4): one recursion level of an expanded group's tree. ──────
     *  [root] is the members' common directory; [prefix] is the relPath of
     *  the subfolder being rendered ("" = the group's own root). Members
     *  with no file path (text messages) always render at the root level,
     *  exactly as they did in the flat list — nothing vanishes from the
     *  tree. */
    private fun renderGroupLevel(container: LinearLayout, members: List<TransferManager.Transfer>,
                                 groupKey: String, root: String, prefix: String, depth: Int,
                                 out: MutableList<() -> Unit>) {
        val ctx = requireContext()
        // ── GOAT-PASS 9 (§9 — the Queue's expanded-folder design): the ──
        // child rows now use the Queue section's exact visual language —
        // carded rows (bg_btn_secondary), (6+indent)/5/6/5 padding with a 3dp
        // inter-row gap, 17dp chevron + folder/format glyphs, 12sp labels and
        // a 12dp-per-depth indent — so an expanded received folder reads as
        // the same professional block the Queue already shows. Received's own
        // information (per-member status, the delete action, in-app audio)
        // is preserved on top of that shared design.
        val indent = dp(12) * depth.coerceAtMost(4)
        val subfolders = LinkedHashSet<String>()
        val rootFiles = mutableListOf<TransferManager.Transfer>()
        for (m in members) {
            val rel = m.filePath?.let { if (root.isNotEmpty() && it.startsWith("$root/")) it.removePrefix("$root/") else it }
            when {
                rel == null -> if (prefix.isEmpty()) rootFiles.add(m)   // a text member: root-level, never dropped
                prefix.isEmpty() -> {
                    val slash = rel.indexOf('/')
                    if (slash < 0) rootFiles.add(m) else subfolders.add(rel.substring(0, slash))
                }
                else -> {
                    if (!rel.startsWith("$prefix/")) continue
                    val rest = rel.removePrefix("$prefix/")
                    val slash = rest.indexOf('/')
                    if (slash < 0) rootFiles.add(m) else subfolders.add("$prefix/${rest.substring(0, slash)}")
                }
            }
        }
        for (folderPath in subfolders) {
            val name = folderPath.substringAfterLast('/')
            val count = members.count { m ->
                m.filePath?.let { if (root.isNotEmpty() && it.startsWith("$root/")) it.removePrefix("$root/") else it }?.startsWith("$folderPath/") == true
            }
            val expandKey = "$groupKey::$folderPath"
            val expanded = expandedGroupSubKeys.contains(expandKey)
            out.add {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(6) + indent, dp(5), dp(6), dp(5))
                    isClickable = true; isFocusable = true
                    background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_secondary)
                    layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = dp(3) }
                }
                row.addView(ImageView(ctx).apply {
                    setImageDrawable(Widgets.icon(ctx, R.drawable.ic_chevron_right, col(R.color.accent_light)))
                    layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).also { it.marginEnd = dp(6) }
                    rotation = if (expanded) 90f else 0f
                })
                row.addView(ImageView(ctx).apply {
                    setImageDrawable(Widgets.icon(ctx, R.drawable.ic_folder, col(R.color.text_muted)))
                    layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).also { it.marginEnd = dp(8) }
                })
                row.addView(TextView(ctx).apply {
                    text = "$name/  ($count file${if (count == 1) "" else "s"})"
                    textSize = 12f; setTextColor(col(R.color.text_secondary))
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                })
                row.setOnClickListener {
                    if (expandedGroupSubKeys.contains(expandKey)) expandedGroupSubKeys.remove(expandKey)
                    else expandedGroupSubKeys.add(expandKey)
                    renderGroupChildren(container, members, groupKey, force = true)
                }
                container.addView(row)
            }
            if (expanded) {
                renderGroupLevel(container, members, groupKey, root,
                    prefix = folderPath, depth = depth + 1, out = out)
            }
        }
        for (m in rootFiles.sortedBy { it.seq }) {
            out.add {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // GOAT-PASS 9 (§9): the Queue's carded child-row design —
                // bg_btn_secondary surface, (6+indent)/5/6/5 padding, 3dp
                // gap, 17dp format glyph, 12sp label. Received's own
                // trailing status text and per-file delete ride on top.
                setPadding(dp(6) + indent, dp(5), dp(6), dp(5))
                isClickable = m.status == Status.COMPLETED && m.filePath != null
                if (isClickable) setOnClickListener {
                    // ── Pass-40 (#7): a completed AUDIO member plays in-app ──
                    // through the shared controller (the folder's whole audio
                    // set as its playlist); every other file keeps the exact
                    // open routing it always had.
                    // ── FINAL PASS 5 (§8): the remaining media members
                    // (images/videos) route through openTransferFile's
                    // internal previewer too — a folder child's tap gets the
                    // same internal-player treatment the top-level cards do.
                    if (m.filePath != null && isAudioFileName(m.fileName)) playReceivedGroupAudio(members, m)
                    else openTransferFile(m)
                }
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_secondary)
                // Part 4.4 fix: completes the width chain fixed in
                // createRowShell's child_list above -- this row is where
                // the filename TextView's weight=1 actually needs
                // somewhere real to flex into.
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also { it.bottomMargin = dp(3) }
            }
            row.addView(ImageView(ctx).apply {
                // GOAT-PASS 9 (§9): every child file row carries its FORMAT
                // glyph (same shared resolver + family colors the Queue's
                // child rows use) — a received folder's contents read at a
                // glance instead of as an undifferentiated text list.
                setImageDrawable(FormatIcons.icon(ctx, m.fileName.substringAfterLast('.', "")))
                layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).also { it.marginEnd = dp(8) }
            })
            row.addView(TextView(ctx).apply {
                text = m.fileName   // the member's own leaf name, at any depth
                textSize = 12f; setTextColor(col(R.color.text_secondary))
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            row.addView(TextView(ctx).apply {
                // Same text applyStatusChip computes for a non-completed
                // transfer, inlined rather than building (and immediately
                // discarding) a whole chip View just to read its .text
                // back -- this now runs once per row across a
                // potentially large, fully-shown tree, so that allocation
                // is worth avoiding. Keep in sync with applyStatusChip if
                // its label wording ever changes.
                text = if (m.status == Status.COMPLETED) fmtBytes(m.totalBytes) else {
                    val base = when {
                        m.status == Status.PENDING && m.pauseRequested -> "Paused"
                        m.status == Status.PENDING -> "Waiting"
                        m.status == Status.ACTIVE && m.pauseRequested -> "Paused"
                        // GOAT-PASS 5 (§7): the member-row twin of the Waiting
                        // gate — an ACTIVE, unpaused member that does not own
                        // an execution slot is resumed-but-queued, not
                        // streaming (keep in sync with applyStatusChip).
                        m.status == Status.ACTIVE && !TransferManager.isExecuting(m.id) -> "Waiting"
                        m.status == Status.ACTIVE && (m.direction == Direction.DOWNLOAD || m.direction == Direction.SEND) -> "Sending"
                        m.status == Status.ACTIVE -> "Receiving"
                        m.status == Status.FAILED -> "Failed"
                        m.status == Status.DECLINED -> "Declined"   // v1.0 §decline-sync
                        else -> "Cancelled"
                    }
                    when {
                        m.status != Status.ACTIVE || m.pauseRequested -> base
                        m.isIndeterminate -> "$base..."
                        else -> "$base ${m.progressPct}%"
                    }
                }
                textSize = 10f; setTextColor(col(R.color.text_muted)); alpha = 0.8f
            })
            if (m.status == Status.COMPLETED && m.direction == Direction.UPLOAD && m.filePath != null) {
                row.addView(ImageView(ctx).apply {
                    // GOAT-PASS 9 (§9): the Queue's compact 26dp remove-button
                    // scale (was the 36dp rowside iconBtn) — matches the
                    // carded child row's tighter rhythm.
                    setImageDrawable(Widgets.icon(ctx, R.drawable.ic_close, col(R.color.text_secondary)))
                    layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                    setPadding(dp(5), dp(5), dp(5), dp(5))
                    isClickable = true; isFocusable = true
                    setOnClickListener {
                        // Phase 5 (§5.4): per-child delete now confirms before
                        // destroying — it was the one destructive action in the
                        // whole received-files surface that fired instantly on
                        // a small icon sitting directly beside the row's open
                        // target, one thumb-slip away from an undeletable
                        // mistake. Same LdxDialog confirmation every other
                        // destructive action in this tab already uses.
                        confirmDeleteOneFile(m)
                    }
                })
            }
            container.addView(row)
            }
        }
    }

    /** The longest directory that is an ancestor of every [paths] entry
     *  ("" when the members share none — the tree then degrades gracefully
     *  to the old flat list). */
    private fun commonParentDir(paths: List<String>): String {
        if (paths.isEmpty()) return ""
        var root = paths.first().substringBeforeLast('/')
        for (p in paths) {
            while (root.isNotEmpty() && !p.startsWith("$root/")) {
                root = root.substringBeforeLast('/')
            }
            if (root.isEmpty()) return ""
        }
        return root
    }

    /** Pass-3 (R9): the finished-received-folder branch is GONE — folder
     *  deletion uses the SAME long-press selection + shared Delete action
     *  as every other item in the Received tab (enterSelection on a
     *  completed group selects the whole folder), so no separate Delete
     *  Folder button clutters the row. Active groups keep Pause/Resume and
     *  Cancel (whole-folder, both sides). */
    private fun buildGroupActionButtons(info: TransferManager.TransferGroupInfo, members: List<TransferManager.Transfer>, row: LinearLayout, receivedFolder: Boolean = false) {
        val status = TransferManager.groupStatus(members)
        // ── GOAT-PASS 10 (§4 — cancellation works at EVERY transfer stage): ──
        // a folder whose remaining members are all FAILED/interrupted (the
        // receiver-side "Auto-Resume on Reconnection" partials) used to show
        // NO action buttons at all — groupStatus() is FAILED, and the old
        // PENDING||ACTIVE gate left the folder un-cancellable as a unit
        // (only the global ⋮ cleanup or per-row discard could reach it, and
        // only while those rows were still around). A FAILED folder now
        // gets the SAME Cancel button — it resolves every member (the
        // handler's cancelIncomingPushFromUi + the group-scoped fallback
        // cover the interrupted rows and their staging data too), while the
        // Pause/Resume pair stays hidden (there is nothing live to pause).
        val folderCancellable = status == Status.PENDING || status == Status.ACTIVE ||
            (status == Status.FAILED && members.any { it.status == Status.FAILED })
        if (folderCancellable) {
            // GOAT (Phase 1 continuation): only the members still actually
            // in flight matter here. members is now the group's TRUE full
            // membership (see foldIntoRows' doc comment on membersOf) --
            // for a large folder that's mostly done, that includes
            // hundreds of already-COMPLETED files. Scoping to PENDING/
            // ACTIVE only means (a) "Cancel" on a 300-file folder that's
            // 250 done doesn't fire 250 pointless local no-ops and 250
            // pointless cross-device control POSTs at files that already
            // finished, and (b) a stale pauseRequested flag left on some
            // already-terminal member from earlier in its life can't
            // wrongly flip this button to "Resume" for the whole group.
            val liveMembers = members.filter { it.status == Status.PENDING || it.status == Status.ACTIVE }
            val anyPaused = liveMembers.any { it.pauseRequested }
            // GOAT-PASS 10 (§4): the Pause/Resume pair exists only while
            // something is actually live — a FAILED (interrupted-only) folder
            // enters this branch for the Cancel button alone, and an empty
            // liveMembers must never mint a Pause button that pauses nothing.
            if (liveMembers.isNotEmpty()) {
                if (anyPaused) {
                    row.addView(iconBtn(R.drawable.ic_play, R.color.accent_light) {
                        for (m in liveMembers) {
                            TransferManager.requestResume(m.id); propagateIfNative(m, "resume")
                            // ── Pass-18 (resume-one-of-all-paused, group half): ──
                            // a paused native SEND member YIELDED its dispatch
                            // coroutine on purpose and sits parked in
                            // NativePushClient.pausedSends — clearing the pause
                            // flag alone (requestResume) leaves it parked forever,
                            // the exact single-row deadlock this pass fixed, so
                            // the group's Resume must relaunch each parked member
                            // exactly like the per-row Resume tap does. No-op for
                            // every non-parked member kind.
                            if (m.direction == Direction.SEND && m.source == Source.NATIVE_PUSH) {
                                NativePushClient.resumeFile(m.id)
                            }
                        }
                        renderCurrentTab()
                    })
                } else {
                    row.addView(iconBtn(R.drawable.ic_pause, R.color.text_secondary) {
                        for (m in liveMembers) { TransferManager.requestPause(m.id); propagateIfNative(m, "pause") }
                        renderCurrentTab()
                    })
                }
            }
            row.addView(iconBtn(R.drawable.ic_close, R.color.red_stop) {
                // ── GOAT-PASS 11 (§7 — the whole-folder cancel is ONE ──────────
                // operation, not N): the old loop fired one PER-FILE control
                // POST per live member — for a 694-file folder that was
                // hundreds of concurrent HTTP requests hammering the peer's
                // server (a thread/connection storm the low-memory peer
                // could genuinely crash on), and a hundred round trips the
                // user watched as "the folder gets cancelled item by item".
                // The local rows resolve in ONE atomic bulk pass, and the
                // peer learns about the cancel through exactly ONE
                // scope=push control request (propagateGroupCancel for our
                // own sends; cancelIncomingPushFromUi — which now fires that
                // POST itself — for incoming ones). Every remaining child,
                // queued or in flight, is covered by the push-level teardown
                // those calls perform.
                if (liveMembers.isNotEmpty()) {
                    TransferManager.requestCancelBulk(liveMembers.map { it.id })
                }
                val first = members.firstOrNull()
                if (first?.source == Source.NATIVE_PUSH) {
                    if (first.direction == Direction.SEND) {
                        NativePushClient.propagateGroupCancel(info.id)
                    } else if (first.direction == Direction.UPLOAD) {
                        // Resolve the sender through any live member's push
                        // record (each carries "pushId::relPath"); one
                        // whole-push cancel covers them all.
                        // ── GOAT-PASS 9 (§8 — the local half): the ────────────
                        // full local teardown (every member row, staging,
                        // resume registry, item decisions) runs inside
                        // cancelIncomingPushFromUi — and GOAT-PASS 11 made it
                        // ALSO notify the sender directly, so the separate
                        // propagateNativeGroupControl call is now a
                        // belt-and-braces idempotent repeat rather than the
                        // only propagation path.
                        val resolver = liveMembers.firstOrNull() ?: members.firstOrNull()
                        resolver?.let { m ->
                            server?.cancelIncomingPushFromUi(m.id)
                            server?.propagateNativeGroupControl(m.id, "cancel")
                        }
                    }
                }
                renderCurrentTab()
            })
        }
        // finished groups: no buttons — deletion is the shared long-press
        // selection + Delete action in the Received tab (Pass-3, R9).
    }

    // ── GOAT-PASS 8 (§8): the statusChip BUILDER is retired with its group ──
    // twin — the shell builds the one chip per card (createRowShell's
    // "status_chip", in the lower action row), and applyStatusChip below is
    // the single writer of its text/color on every bind, full or payload.
    private fun applyStatusChip(chip: TextView, t: TransferManager.Transfer) {
        // ── FINAL PASS 3 (§9): a COMPLETED row shows NO status pill on the ──
        // Received and History tabs — "Done" added card height without
        // information (the card's whole presence says it finished). The
        // Active tab's completed flash is transient (FAILED_LINGER/
        // terminal rows leave the Active list quickly), so hiding the chip
        // for every COMPLETED row is the compact-card rule this pass asks
        // for; every non-completed state keeps its chip exactly as before.
        if (t.status == Status.COMPLETED) {
            chip.visibility = View.GONE
            return
        }
        chip.visibility = View.VISIBLE
        val pair = when {
            t.status == Status.PENDING && t.pauseRequested -> "Paused" to R.color.orange
            t.status == Status.PENDING -> "Waiting" to R.color.text_muted
            t.status == Status.ACTIVE && t.pauseRequested -> "Paused" to R.color.orange
            // Pass-12 (wording): the automatic-resume live state reads
            // "Waiting for reconnection" — the calm, professional, non-manual
            // phrasing asked for. No button, no "Reconnect" imperative: the
            // engine owns recovery and the chip simply reports it.
            // ── Pass-15 (sender wording): a SEND row inside its automatic ──
            // retry window reads "Auto-Resume on Reconnection" — the same
            // calm, engine-owns-it message, distinct from the RECEIVER's
            // card so each side honestly says what IT is doing (this device
            // is actively re-sending; the other device is passively waiting).
            // ── GOAT-PASS 4 (§5 — one wording on BOTH sides): an UPLOAD row ──
            // (this device is the RECEIVER) uses the SAME "Auto-Resume on
            // Reconnection" wording now — the sender's retry loop or the
            // browser's auto-reconnect engine will resume exactly this row
            // from its preserved checkpoint, which is precisely what the
            // label must tell the user on both ends of the wire.
            // ── FINAL PASS 2 (§1): a streamed ZIP is the ONE direction that ──
            // never byte-resumes — its automatic retry restarts the archive
            // from byte 0 — so both the live latched state and the kept
            // waiting card read the distinct, honest "Auto-Continue on
            // Reconnection" instead (sender and receiver alike).
            t.status == Status.ACTIVE && t.reconnecting && t.isZipStream -> "Auto-Continue on Reconnection" to R.color.orange
            t.status == Status.ACTIVE && t.reconnecting -> "Auto-Resume on Reconnection" to R.color.orange
            // ── GOAT-PASS 5 (§7 — the purple-state accuracy root fix) ──────────
            // An ACTIVE, unpaused row that does NOT own an execution slot in
            // its direction right now is RESUMED-BUT-WAITING, not executing:
            // a batch where several paused items were resumed shows one
            // genuinely streaming row and N rows parked at the gates — the
            // neutral muted "Waiting" (with the row's preserved progress
            // percentage, the chip text logic below appends it) is the honest
            // presentation, and the moment the slot is granted the chip flips
            // to the normal active "Sending…"/"Receiving…" style. isExecuting
            // derives from the authoritative slot ledgers (receiving-gate
            // holders, download-gate holders, the sender's dispatch-gate leg
            // set) — never from "a resume was requested".
            t.status == Status.ACTIVE && !TransferManager.isExecuting(t.id) -> "Waiting" to R.color.text_muted
            // Pass-14 (resume wording, both systems): a live leg that picked
            // up from an existing partial reads as what it IS — "Resuming
            // 62%" — so a checkpoint resume never looks like a mysterious
            // restart from a non-zero number, on either side of the
            // transfer.
            t.status == Status.ACTIVE && t.resumedFromBytes > 0L && (t.direction == Direction.DOWNLOAD || t.direction == Direction.SEND) -> "Resuming" to R.color.accent_light
            t.status == Status.ACTIVE && t.resumedFromBytes > 0L -> "Resuming" to R.color.orange
            t.status == Status.ACTIVE && (t.direction == Direction.DOWNLOAD || t.direction == Direction.SEND) -> "Sending" to R.color.accent_light
            t.status == Status.ACTIVE -> "Receiving" to R.color.orange
            t.status == Status.COMPLETED -> "Done" to R.color.green
            // Pass-12 (wording): a transfer severed by a lost connection no
            // longer reads "Interrupted" — recovery is AUTOMATIC on both
            // sides (the sender's resume loop for sends, the browser's
            // auto-reconnect for web deliveries), so the honest live label is
            // the same calm "Auto-Resume on Reconnection". A deliberate CANCEL,
            // by contrast, always resolves through CANCELLED below and never
            // lands on this branch (fail() routes host-cancelled rows to
            // Status.CANCELLED, and requestCancel finalizes them directly).
            // ── Pass-18 (app→browser sender side, resolved phase): a ──
            // DOWNLOAD row that the watchdog/onPageReconnected resolved
            // interrupted is mid-HANDOFF, not abandoned — the browser's
            // auto-reconnect engine is armed on exactly this snapshot and
            // its re-request re-opens THIS row in place (byte-resuming from
            // the checkpoint) within moments, for the whole
            // FAILED_DOWNLOAD_LINGER window. The host is the SENDER here, so
            // the chip keeps the same "Auto-Resume on Reconnection" wording
            // the row showed while it was latched live — no misleading
            // blink between the resolve and the re-attach.
            // ── Pass-20 (sender wording through the RESOLVED phase too) ─────
            // A FAILED+interrupted NATIVE SEND row is inside THIS device's
            // own auto-resume loop (sendOneFile keeps re-POSTing the remainder
            // for its full window) — the sender's side of a severed transfer.
            // It reads the SAME "Auto-Resume on Reconnection" the row showed
            // while latched ACTIVE (and the same wording a resolved
            // DOWNLOAD sender-row shows).
            // ── GOAT-PASS 4 (§5): the RECEIVER's resolved UPLOAD rows join ──
            // the same wording — the sender's engine owns the recovery of
            // exactly this row, and both sides of the transfer must tell the
            // user the same thing.
            t.status == Status.FAILED && t.interrupted && t.isZipStream -> "Auto-Continue on Reconnection" to R.color.orange
            t.status == Status.FAILED && t.interrupted -> "Auto-Resume on Reconnection" to R.color.orange
            t.status == Status.FAILED -> "Failed" to R.color.red_stop
            // v1.0 (§decline-sync): declined reads as declined — calm, honest,
            // and distinct from both Failed and Cancelled.
            t.status == Status.DECLINED -> "Declined" to R.color.text_muted
            else -> "Cancelled" to R.color.red_stop
        }
        val label = pair.first
        val colorRes = pair.second
        chip.text = when {
            // ── GOAT-PASS 6 (§2 — the recovery card's saved progress) ──────────
            // An interrupted kept-for-resume row ("Auto-Resume on Reconnection",
            // the "Waiting for reconnection" recovery card) carries REAL saved
            // progress: X% of the file is provably on disk as the resume
            // checkpoint. The chip now says so with the same convention the
            // active chip uses — "Auto-Resume on Reconnection 62%" — so the
            // recovery card tells the user exactly how much will continue
            // when the matching file is sent again, instead of a bare label
            // next to a frozen bar.
            // ── FINAL PASS 2 (§1): a streamed ZIP's kept auto-continue card ──
            // never shows a percentage — its progress is deliberately zeroed
            // (the next attempt restarts from 0 MB), and a "0%" suffix
            // would read as a live measurement.
            t.status == Status.FAILED && t.interrupted && t.isZipStream -> label
            t.status == Status.FAILED && t.interrupted && t.progressPct > 0 ->
                "$label ${t.progressPct}%"
            t.status != Status.ACTIVE || t.pauseRequested -> label
            t.isIndeterminate -> "$label..."
            else -> "$label ${t.progressPct}%"
        }
        val chipColor = col(colorRes)
        chip.setTextColor(chipColor)
        // 20% alpha of the same status color, not a fixed neutral tint --
        // keeps the pill legible against bg_btn_secondary's dark card fill
        // without needing a color per status. Updating the SAME
        // GradientDrawable instance's color (rather than assigning a new
        // one) is the other half of what makes this a true in-place
        // update with zero View/Drawable churn.
        (chip.background as android.graphics.drawable.GradientDrawable).setColor((chipColor and 0x00FFFFFF) or 0x33000000)
    }

    /**
     * Drives all three tabs — Active/Received/Sent each hold their own
     * instance (activeAdapter/receivedAdapter/sentAdapter). The row UI and
     * click-handling are identical across all three; only which snapshot
     * function feeds a given instance, and which action buttons apply for
     * a given status, differ — and buildActionButtons already branches on
     * status for that, same as before this migration. Inner (not a
     * standalone top-level class) specifically so it can call straight
     * into this Fragment's existing row-building/action private methods
     * (createRowShell, updateRow, openFile, buildActionButtons, ...)
     * exactly as they already are, rather than turning every one of them
     * into a constructor-injected callback for what would otherwise be a
     * purely mechanical, higher-risk refactor for no behavioral benefit.
     *
     * GOAT (transfer stability pass — large folder transfers /
     * ordering / progress visibility): replaces the old hand-rolled
     * diffRenderList(LinearLayout, List<Transfer>), which only ever
     * inserted new rows and removed gone ones — it never moved an
     * existing row to reflect a new sort position, so the on-screen order
     * silently drifted from TransferManager's actual order the moment
     * anything re-sorted, which happens constantly with a few hundred
     * files in flight (see TransferManager.activeSnapshot's own doc
     * comment on the ordering fix that went with this). RecyclerView +
     * ListAdapter + DiffUtil is a real, well-tested implementation of the
     * same "diff two lists, apply the minimal set of changes" idea that
     * code was already trying to hand-roll — including MOVE, the part
     * that was missing — plus actual view recycling, which a plain
     * LinearLayout inside a ScrollView never had: with a few hundred
     * rows, every single one stayed fully inflated and alive in memory at
     * once, whether on screen or not.
     */
    private inner class TransferRowAdapter(private val groupsExpandable: Boolean,
                                           private val selectionTab: String = "received",
                                           private val selectionCapable: Boolean = false) : ListAdapter<TransferRowState, TransferRowViewHolder>(TRANSFER_ROW_DIFF) {
        // Parallel key -> live TransferRowItem lookup, rebuilt every time a
        // new list comes in (see submitRows). ListAdapter's own list holds
        // immutable TransferRowState snapshots -- that's what DiffUtil
        // needs for a real content comparison (see TransferRowState's doc
        // comment) -- but updateRow/updateGroupRow/buildActionButtons/etc.
        // all want the live, mutable Transfer (or live group members), and
        // rightly so: they want whatever is truest right now, not a
        // snapshot from whenever this diff last ran. This map is what lets
        // onBindViewHolder still hand them that live data without
        // threading a second parameter through more than a dozen existing
        // functions. Covers the Received tab's filesystem-reconciled
        // entries correctly too -- those are synthesized fresh per render
        // and never registered into TransferManager's own map, so a plain
        // id lookup there wouldn't find them; this map always reflects
        // exactly whatever was last submitted, live-or-synthetic alike.
        private val liveById = HashMap<String, TransferManager.TransferRowItem>()

        fun submitRows(items: List<TransferManager.TransferRowItem>, onCommitted: (() -> Unit)? = null) {
            liveById.clear()
            for (item in items) liveById[item.rowKey()] = item
            // Pass-28 (#1.1): selection state is read per-TAB — the History
            // adapter keeps its own mode and key set, so the two tabs can
            // never cross-contaminate each other's selections.
            val selectionActive = selectionCapable && selectionActiveFor(selectionTab)
            val selectionKeys = selectionKeysFor(selectionTab)
            val mapped = items.map {
                it.toRowState(
                    expanded = it.rowKey() == expandedGroupKey,
                    selectionActive = selectionActive,
                    selected = selectionActive && it.rowKey() in selectionKeys,
                )
            }
            // ── GOAT-PASS 11 (§2): the optional commit callback runs ONLY ──────────
            // after DiffUtil has genuinely committed the new order to the
            // adapter. submitList's diff is ASYNC — a scroll issued right
            // after the submit call fires against the OLD layout, and the
            // structural change then re-anchors the viewport wherever the
            // moves land it (with a flipped sort: far DOWN the list — the
            // reported "sorting sometimes scrolls to the bottom"). The
            // sort-change scroll is threaded through here so it can never
            // race the rows it belongs to.
            if (onCommitted != null) submitList(mapped) { onCommitted() }
            else submitList(mapped)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = TransferRowViewHolder(createRowShell())

        override fun onBindViewHolder(holder: TransferRowViewHolder, position: Int) {
            val card = holder.itemView as LinearLayout
            val state = getItem(position)
            // The sealed parent carries no selection fields — extract them
            // once, up front, from whichever concrete row state this is.
            val (selActive, sel) = when (state) {
                is TransferRowState.Solo -> state.selectionActive to state.selected
                is TransferRowState.Group -> state.selectionActive to state.selected
            }
            when (val live = liveById[state.key] ?: return) {
                is TransferManager.TransferRowItem.Solo -> updateRow(card, live.transfer, selActive, sel, selectionCapable, selectionTab, holder)
                is TransferManager.TransferRowItem.Group -> updateGroupRow(card, live.info, live.members, groupsExpandable, selActive, sel, selectionCapable, selectionTab)
            }
        }

        // Phase 8 (§8.3): the payload path — a progress-only change (see
        // TRANSFER_ROW_DIFF.getChangePayload) patches the bar / chip /
        // detail line of the EXISTING views in place. No button rebuild, no
        // icon re-inflation, no thumbnail decode relaunch, no listener
        // reset — the row's structure is by definition unchanged.
        // ── SELECTION FINAL PASS 1 (§7 — the selection-only row payload): a ──
        // crossed Hold & Slide row's flip rebinds ONLY its selection chrome
        // (applySelectionVisual is self-contained and keyed on the card's own
        // state tag, so the live selection sets are all it needs) — no full
        // submitRows, no snapshot re-map of the whole tab, no DiffUtil pass.
        // The drag's fast path dispatches this; the gesture end runs the one
        // full reconciliation (repaintSelection) the way every tap always
        // has.
        fun notifyRowSelectionChanged(position: Int) {
            if (position in 0 until itemCount) notifyItemChanged(position, SEL_ROW_PAYLOAD)
        }

        override fun onBindViewHolder(holder: TransferRowViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && payloads.contains(SEL_ROW_PAYLOAD)) {
                val card = holder.itemView as LinearLayout
                val state = getItem(position)
                val selActive = selectionCapable && selectionActiveFor(selectionTab)
                // LIVE reads — the ListAdapter's snapshot still carries the
                // pre-flip flags until the gesture-end reconciliation lands.
                val sel = selActive && state.key in selectionKeysFor(selectionTab)
                applySelectionVisual(card, selActive, sel, sameRow = true)
                return
            }
            if (payloads.isNotEmpty() && payloads.contains(PROGRESS_PAYLOAD)) {
                val card = holder.itemView as LinearLayout
                val state = getItem(position)
                // ── FINAL PASS 5 (§2): the payload path threads the same ──
                // hidePeer flag the full bind uses (the Received tab's rows
                // omit the sender-origin segment on progress patches too).
                when (val live = liveById[state.key] ?: return) {
                    is TransferManager.TransferRowItem.Solo -> patchProgressViews(card, live.transfer, selectionTab == "received", selectionTab == "sent")
                    is TransferManager.TransferRowItem.Group -> patchGroupProgressViews(card, live.info, live.members, selectionTab == "received", selectionTab == "sent")
                }
                return
            }
            onBindViewHolder(holder, position)
        }

        override fun onViewRecycled(holder: TransferRowViewHolder) {
            // Phase 5 (§5.1): cancels the departed row's in-flight thumbnail
            // decode — a recycled row's job can no longer ever become
            // visible, so the decode itself (not just its result) should
            // stop. Mirrors the Media Picker grid's own onViewRecycled.
            holder.thumbJob?.cancel()
            holder.thumbJob = null
        }
    }

    // Transfer state sync -------------------------------------------------------------

    private fun propagateIfNative(t: TransferManager.Transfer, action: String) {
        if (t.source != Source.NATIVE_PUSH) return
        if (t.direction == Direction.SEND) NativePushClient.propagateControl(t.id, action)
        else server?.propagateNativeControl(t.id, action)
    }

    /** Pass-25 (#1): clears ONLY the row's action buttons, preserving the two
     *  tagged shells that live in the same row — the speed caption slot
     *  ("tv_speed") and, since GOAT-PASS 8 (§8), the status chip
     *  ("status_chip") that leads it. Every button added by
     *  buildActionButtons/buildGroupActionButtons is a child of this row with
     *  no tag, so "untagged child" is exactly the safe removal predicate. */
    private fun clearActionButtons(row: LinearLayout?) {
        if (row == null) return
        for (i in row.childCount - 1 downTo 0) {
            val c = row.getChildAt(i)
            if (c.tag != "tv_speed" && c.tag != "status_chip") row.removeViewAt(i)
        }
    }

    private fun buildActionButtons(t: TransferManager.Transfer, row: LinearLayout) {
        // ── Pass-16 (text rows carry ONE action: Copy) ─────────────────────
        // A message card in History offers its one meaningful action — copy
        // the content to the clipboard — in the same small-action style the
        // file rows' Install/Retry buttons use. No open-with, no install,
        // nothing that assumes a file on disk.
        if (t.isTextMessage) {
            if (t.status == Status.COMPLETED) {
                row.addView(smallActionBtn("Copy", R.drawable.ic_copy, R.color.accent_light) { copyTextToClipboard(t.textContent ?: "") })
            }
            return
        }
        when (t.status) {
            Status.PENDING, Status.ACTIVE -> {
                // ── GOAT-PASS 15 (§3.4): a streamed ZIP-batch archive is NEVER
                // pausable — aborting its stream mid-archive leaves a truncated
                // zip the receiver must restart from byte 0 (unknown-length
                // archives are never byte-resumed by design), which is exactly
                // the corrupt-archive behavior the pass removes. No Pause
                // button is offered for such a row on EITHER side (the
                // receiver's mirror row carries the same isZipStream marker).
                // Cancel, failure and the established Resend flow are
                // untouched. A stale pause flag set by an older-version peer
                // still gets its Resume button — the row must never be
                // stranded in a paused state its own UI can't lift.
                if (t.isZipStream) {
                    if (t.pauseRequested) {
                        row.addView(iconBtn(R.drawable.ic_play, R.color.accent_light) {
                            TransferManager.requestResume(t.id); propagateIfNative(t, "resume"); renderCurrentTab()
                            if (t.direction == Direction.SEND && t.source == Source.NATIVE_PUSH) {
                                NativePushClient.resumeFile(t.id)
                            }
                        })
                    }
                } else if (t.pauseRequested) {
                    row.addView(iconBtn(R.drawable.ic_play, R.color.accent_light) {
                        TransferManager.requestResume(t.id); propagateIfNative(t, "resume"); renderCurrentTab()
                        // ── Pass-16 (resume-one-of-all-paused, sender half) ──
                        // A paused app-to-app send YIELDED its dispatch
                        // permit (see NativePushClient.sendOneFile's pause
                        // path) — resuming it must RELAUNCH the send loop,
                        // not just clear the pause flag, or the row would
                        // sit unpaused with nothing streaming. No-op for
                        // every other paused row kind.
                        if (t.direction == Direction.SEND && t.source == Source.NATIVE_PUSH) {
                            NativePushClient.resumeFile(t.id)
                        }
                    })
                } else {
                    row.addView(iconBtn(R.drawable.ic_pause, R.color.text_secondary) {
                        TransferManager.requestPause(t.id); propagateIfNative(t, "pause"); renderCurrentTab()
                    })
                }
                row.addView(iconBtn(R.drawable.ic_close, R.color.red_stop) {
                    TransferManager.requestCancel(t.id)
                    // ── Pass-13 (individual cancellation root fix) ──────────────
                    // The X on ONE row cancels exactly THAT transfer — the
                    // critical "clicking X cancelled every transfer in the
                    // Active tab" bug was propagateCancelForTid's deliberate
                    // WHOLE-SESSION teardown (every sibling row CANCELLED + a
                    // scope=push withdrawal on the receiver), which treated
                    // one file's cancel as a decision about the entire push.
                    // The per-file path now:
                    //  - Quick-Share → cancelQuickShareForPeer: this client's
                    //    IP leaves the item's target set, so its incoming list
                    //    drops the file on the next payload (and the Send tab's
                    //    queue card stops re-showing it) while other targeted
                    //    clients keep theirs.
                    //  - Native push (send side) → propagateSingleFileCancel:
                    //    the receiver cancels just THIS file's rows and
                    //    declines just this file's undecided incoming item —
                    //    every sibling keeps flowing, and the sender's own
                    //    dispatch loop skips only this file (Cancel All and
                    //    the folder-group Cancel remain the whole-session
                    //    actions, via propagateCancelForTid/propagateGroupCancel).
                    // Everything else keeps the classic per-row propagation.
                    when {
                        t.source == Source.NATIVE_PUSH && t.direction == Direction.SEND ->
                            NativePushClient.propagateSingleFileCancel(t.id)
                        t.source == Source.QUICK_SHARE && !t.qsItemName.isNullOrEmpty() ->
                            server?.cancelQuickShareForPeer(t.qsItemName, t.peerIp)
                        else -> propagateIfNative(t, "cancel")
                    }
                    renderCurrentTab()
                })
            }
            Status.FAILED, Status.CANCELLED, Status.DECLINED -> {
                val qsName = t.qsItemName
                // ── Pass-17 (no useless Retry during a cancel) ──────────────────
                // A CANCELLED row shows its 400 ms dismissal flash next to a ──
                // Retry button that must NEVER be there (the reported useless
                // Retry that briefly appears when Cancel is tapped): a deliberate
                // cancel is a DECISION — the failed-delivery re-arm sweep already
                // deliberately excludes cancelled items, the tombstone refuses
                // the browser's own retries, and a DECLINED send is equally
                // final. Retry is now offered ONLY for a genuine FAILED
                // (non-interrupted) delivery, where recovery is real.
                if (t.status == Status.FAILED && !t.interrupted &&
                    t.source == Source.QUICK_SHARE && !qsName.isNullOrEmpty()) {
                    // ── FINAL PASS 3 (§8): renamed Retry → Resend — the ──
                    // action RESENDS the item to its original recipient
                    // (retryQuickShareForIp re-offers the same queue item);
                    // "Resend" says exactly what it does.
                    row.addView(smallActionBtn("Resend", null, R.color.accent_light) {
                        server?.retryQuickShareForIp(qsName, t.peerIp)
                        TransferManager.dismiss(t.id)
                        renderCurrentTab()
                    })
                } else if (t.status == Status.FAILED && !t.interrupted &&
                    t.source == Source.NATIVE_PUSH && t.direction == Direction.SEND) {
                    // v1.0 (§decline-sync): a DECLINED native-push row never
                    // offers Retry — the receiver answered "no"; re-sending
                    // is a fresh deliberate send, not a retry of this one.
                    // Pass-10 (automatic resume): an interrupted send's
                    // recovery ran automatically; a row that still reads
                    // interrupted had its window close — nothing to retry.
                    row.addView(smallActionBtn("Resend", null, R.color.accent_light) {
                        NativePushClient.retryFile(t.id)
                        TransferManager.dismiss(t.id)
                        renderCurrentTab()
                    })
                } else if (t.status == Status.FAILED && t.interrupted && t.direction == Direction.UPLOAD) {
                    // Pass-8 (§7) + Pass-10 + Pass-12: an interrupted RECEIVE
                    // (a sender pushing to this device lost its connection).
                    // The SENDER's automatic resume loop owns the recovery — it
                    // re-probes this device's preserved checkpoint and re-sends
                    // the remainder on its own. The status chip already says
                    // "Waiting for reconnection"; per the direct pass-12
                    // feedback there is deliberately NO button-like hint here
                    // anymore ("Resuming automatically" read as a manual
                    // control) — the chip is the single, minimalist source of
                    // truth for this state.
                    // Pass-14 (resumable receives): with the partial now
                    // persisted (registry + staging), the row no longer sweeps
                    // itself out at any linger deadline — it stays until the
                    // transfer resumes on this very card, the partial is
                    // discarded via the X (confirmation below), or the partial
                    // itself disappears.
                }
                // Pass-14 (resumable receives, requirement 3): the X on a
                // kept-for-resume card is a DISCARD — it permanently deletes
                // the partial data, so it routes through the confirmation
                // modal (with its Don't-show-again escape) instead of the
                // plain dismiss every other terminal row keeps.
                // ── FINAL PASS 7 (PART 4): the ACTIVE tab only. A FAILED
                // interrupted row also renders in History (its record is
                // durable), and the requirement is explicit that NO History
                // card carries an individual ✕ — the partial-discard control
                // stays reachable exactly where the live recovery story lives
                // (the Active tab, where the row's Pause/Resume/Resend
                // siblings already are); History manages the RECORD through
                // Hold & Select / Delete Selected, never this per-row control.
                if (currentTab != "sent") {
                    row.addView(iconBtn(R.drawable.ic_close, R.color.text_secondary) {
                        if (TransferManager.isKeptForResume(t.id)) discardResumableTransfer(t)
                        else { TransferManager.dismiss(t.id); renderCurrentTab() }
                    })
                }
            }
            Status.COMPLETED -> {
                // ── FINAL PASS 5 (§3): the separate Install button is REMOVED ──
                // from received APK cards (both tabs render through this one
                // builder). The card's own click already opens the install
                // flow — openFile now routes an APK row to installApk (the
                // same permission-gated, API-21-safe installer handoff the
                // button used), so the button was a second path to the same
                // action that only added card height. APK detection, MIME
                // handling and the install flow itself are untouched.
                // ── FINAL PASS 3 (§10): the RECEIVED tab's per-card ⋮ menu ──
                // (Share / Rename) lives on the card's title row, not in the
                // action band — see updateRow's received-file branch, which
                // attaches the ⋮ itself. History keeps its plain rows.
                if (t.direction == Direction.UPLOAD) {
                    // Final Stabilization (§7): received rows no longer
                    // carry a per-row delete X in normal browsing. The
                    // destructive action lives exactly where §7 puts it —
                    // long-press/range selection mode (single or multi),
                    // confirmed through confirmDeleteSelected. A completed
                    // received row now shows only its open-on-tap target
                    // (and the title-row ⋮ on the Received tab): clean,
                    // minimal, nothing destructive visible. (GOAT-PASS 7 §4:
                    // the old "Delete Received Files" bulk pill is retired —
                    // the selection system is the bulk path now.)
                } else {
                    // Sent: dismissing history shouldn't delete the user's
                    // own source file — it lives wherever they picked it
                    // from, not in the receive folder.
                    // ── Pass-17 (durable History): a store-backed row's ✕ ──
                    // removes the HISTORY RECORD itself (its id is the store
                    // entry id under the "hist:" prefix); live engine rows
                    // keep the ordinary engine dismissal exactly as before.
                    // ── GOAT-PASS 12 (§3A): BOTH layers, always. A completed ──
                    // transfer exists as a store entry AND (often) as a
                    // still-live engine row — removing only one layer let the
                    // other re-render the row on the very next tick (the
                    // reported "deleted items reappear"). deleteHistoryRecord
                    // dismisses the engine row AND removes + tombstones the
                    // store record in one step.
                    // ── FINAL PASS 6 (PART 3): ZIP-STREAMED History records ──
                    // no longer carry the per-row ✕.
                    // ── FINAL PASS 7 (PART 4): NO History record carries the ──
                    // per-row ✕ anymore — the requirement covers every item
                    // type (received, sent, media, documents, text messages,
                    // ZIP-streamed, everything). The individual ✕ was also
                    // the last control inflating the action band's width: with
                    // it gone from ALL rows, no row reserves icon space for
                    // it (the band builds per-row from the qualifying actions
                    // alone, so an empty band contributes zero width — no
                    // residual gap). History's removal paths are exactly the
                    // ones that remain: long-press Hold & Select, range/Hold
                    // & Slide, bulk Delete Selected, and Clear History — all
                    // untouched, as are the Active tab's live-transfer
                    // controls (those are transfer-lifecycle actions, not
                    // History record removals).
                }
            }
        }
    }

    // ── Pass-14 (resumable receives, requirement 3): discard-with-confirm ────
    // The X on a kept-for-resume "Waiting for reconnection" card permanently
    // deletes real partially-received bytes. The first few times, that
    // deserves an explicit confirmation — a minimalist modal explaining
    // exactly what is about to be lost, with a "Don't show again" checkbox
    // for the person who already knows what the button does. Confirming
    // deletes the staging partial AND its identity sidecar AND the durable
    // registry entry, then removes the card; a future send of the same
    // file is a genuinely fresh transfer. Cancelling keeps everything
    // exactly as it was.
    private fun discardResumableTransfer(t: TransferManager.Transfer) {
        val ctx = requireContext()
        fun performDiscard() {
            t.filePath?.let { path ->
                com.lumidrop.x.transfer.ResumableReceiveStore.deleteStaging(path)
                com.lumidrop.x.transfer.ResumableReceiveStore.remove(ctx, path)
            }
            // ── Pass-15 (ghost-item ask-to-resend): tell the sender. Without ──
            // this receipt the sender's retry loop read the deleted partial as
            // "checkpoint lost" and silently re-sent the whole file from 0% the
            // moment the connection returned; with it, the receiver's next
            // checkpoint probe answers `discarded: true` and the SENDER asks
            // its own user — "Send again from the beginning, or cancel?" —
            // before a single byte is re-sent. A refusal resolves Cancelled on
            // the sender and NOTHING ever appears here. Keyed by both the
            // composite push request id (the pre-registered/adopted row's
            // identity) and the destination path (covers a row that lost its
            // request id), exactly the pair the probe checks.
            try {
                server?.rememberDiscardedReceive(t.uploadRequestId, t.filePath)
            } catch (e: Exception) {
                // Server not running → no live sender to inform; the files are
                // already gone and the sender's own retry window ends on its own.
            }
            TransferManager.dismissResumable(t.id)
            Toast.makeText(ctx, "Resumable transfer discarded", Toast.LENGTH_SHORT).show()
            renderCurrentTab()
        }
        if (com.lumidrop.x.settings.SettingsManager.isDiscardResumableDontAsk(ctx)) {
            performDiscard()
            return
        }
        // Body: explanation + Don't-show-again checkbox, in the same quiet
        // style the rest of the sheet system uses.
        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        body.addView(TextView(ctx).apply {
            text = "Discarding this transfer permanently deletes the partially received data " +
                "(${android.text.format.Formatter.formatShortFileSize(ctx, t.transferredBytes)} of " +
                (if (t.totalBytes > 0) android.text.format.Formatter.formatShortFileSize(ctx, t.totalBytes) else "unknown size") +
                "). If the same file is sent again, it will start from the beginning."
            textSize = 12.5f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setLineSpacing(dp(2).toFloat(), 1f)
        })
        var dontAskAgain = false
        val check = android.widget.CheckBox(ctx).apply {
            text = "Don\u2019t show again"
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            setOnCheckedChangeListener { _, isChecked -> dontAskAgain = isChecked }
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(10) }
        }
        body.addView(check)
        showLdxDialog(
            context = ctx,
            title = "Discard resumable transfer?",
            iconRes = R.drawable.ic_delete,
            iconTintRes = R.color.red_stop,
            bodyView = body,
            actions = listOf(
                LdxDialogAction("Keep", LdxButtonStyle.GHOST),
                LdxDialogAction("Discard", LdxButtonStyle.DESTRUCTIVE) {
                    if (dontAskAgain)
                        com.lumidrop.x.settings.SettingsManager.setDiscardResumableDontAsk(ctx, true)
                    performDiscard()
                },
            ),
        )
    }

    // Open completed file ---------------------------------------------------------------

    /** ── FINAL PASS 5 (§8): the media-aware open router for transfer rows. ────
     *  The app already owns internal players — the Media Picker sheet's
     *  preview overlay (images + videos: pager, filmstrip, zoom, video
     *  controls) and the shared MusicPlayback controller (audio: the one
     *  controller, its compact pill + expanded overlay, the playback
     *  notification) — but a Received/History card's tap went straight to
     *  ACTION_VIEW, handing the person's own media to an external app.
     *
     *  This router reuses those existing players VERBATIM (no second
     *  implementation), exactly the way the Send tab's queue rows and the
     *  Files sheet's body taps already open media (the same
     *  queuePreview-entry shape SendFragment.openQueueItemAddr uses, and
     *  the same file-shaped Track the queue's unindexed audio builds):
     *
     *   • IMAGE/VIDEO → showMediaPickerSheet in queue-preview mode over a
     *     single MediaLibraryItem wrapping the file (Uri.fromFile — the
     *     previewer's decode path resolves a file URI through its own
     *     path-first fallback), landing on it directly. Closing the preview
     *     dismisses the sheet and returns here. Works identically from
     *     Received AND History — the row's filePath is the identity, so a
     *     History row that no longer matches any in-memory Received object
     *     still opens correctly.
     *   • AUDIO → MusicPlayback.play with a file-shaped Track (key = the
     *     file's path — stable identity; MediaLibrary.queryAudioForPath
     *     enriches title/artist/album/duration when the file is indexed,
     *     else the enrich pass fills them from the file itself, the exact
     *     openStandaloneFile shape). One track, index 0 — a single tap is
     *     a single play, never a queue mutation.
     *   • everything else → the existing [openFile] (ACTION_VIEW), and an
     *     APK → installApk (§3). Files-tab and all other entry points are
     *     untouched — this router is only called from transfer cards.
     */
    private fun openTransferFile(t: TransferManager.Transfer) {
        val path = t.filePath ?: return
        val file = File(path)
        if (!file.isFile) {
            Toast.makeText(requireContext(), "File no longer exists on this device", Toast.LENGTH_SHORT).show()
            return
        }
        val name = file.name
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypes.getMimeType(ext)
        // SVG/ICO carry image/* MIMEs but have no raster pixels the internal
        // previewer can decode — the SAME exclusion QueueAdapter's own
        // isPreviewableMedia applies — so they keep the honest external-open
        // flow instead of a blank preview page.
        val previewableByMime = mime.startsWith("image/") && ext != "svg" && ext != "ico"
        val isImage = previewableByMime ||
            (!mime.startsWith("video/") && !mime.startsWith("audio/") && MimeTypes.isImage(ext) && ext != "svg" && ext != "ico")
        val isVideo = mime.startsWith("video/") ||
            (!mime.startsWith("image/") && !mime.startsWith("audio/") && MimeTypes.isVideo(ext))
        val isAudio = mime.startsWith("audio/") ||
            (!mime.startsWith("image/") && !mime.startsWith("video/") && MimeTypes.isAudio(ext))
        when {
            isImage || isVideo -> {
                // The internal previewer, in its queue-preview (preview-only)
                // entry: the sheet opens straight into the preview overlay on
                // this file, never showing any picker grid. One item — the
                // card's own file — so the filmstrip/arrows stay hidden and
                // the page is exactly this media.
                com.lumidrop.x.ui.showMediaPickerSheet(
                    fragment = this,
                    onConfirm = { _, _ -> /* preview-only entry; nothing to confirm */ },
                    queuePreview = listOf(com.lumidrop.x.media.MediaLibraryItem(
                        id = 0L,
                        uri = android.net.Uri.fromFile(file),
                        name = name,
                        path = path,
                        size = file.length(),
                        mime = mime,
                        dateAddedSec = 0L,
                        isVideo = isVideo,
                        durationMs = 0L,
                        bucketId = -1L,
                        bucketName = "",
                    )),
                    queuePreviewIndex = 0,
                )
            }
            isAudio -> playAudioFile(path, name)
            else -> openFile(t)
        }
    }

    /** ── FINAL PASS 5 (§8): one received/history audio file through the ONE ───
     *  shared music controller — the exact Track shape the Send tab's
     *  openStandaloneFile builds for an unindexed file, with the same
     *  MediaStore enrichment attempt first (real title/artist/album/
     *  duration + the album-keyed artwork cache when the file is indexed).
     *  The session key is the file's own PATH (stable identity — never a
     *  display-name match), so the correct track loads and a same-named
     *  sibling can never steal the tap. */
    private fun playAudioFile(path: String, name: String) {
        val act = activity ?: return
        val file = File(path)
        if (!file.isFile) {
            Toast.makeText(requireContext(), "File no longer exists on this device", Toast.LENGTH_SHORT).show()
            return
        }
        viewLifecycleOwner.lifecycleScope.launch {
            val ctx = context ?: return@launch
            val item = try { com.lumidrop.x.media.MediaLibrary.queryAudioForPath(ctx, path) } catch (e: Exception) { null }
            val track = if (item != null) {
                com.lumidrop.x.ui.MusicPlayback.Track(
                    key = item.path ?: item.uri.toString(),
                    uri = item.uri,
                    title = item.title ?: item.name,
                    artist = item.artist,
                    albumId = item.albumId,
                    durationMs = item.durationMs,
                    size = item.size,
                )
            } else {
                // Unindexed file (a received file, typically): the enrich
                // pass decodes the embedded cover and fills title/artist/
                // duration from the file itself.
                com.lumidrop.x.ui.MusicPlayback.Track(
                    key = path,
                    uri = android.net.Uri.fromFile(file),
                    title = name.substringBeforeLast('.').ifBlank { name },
                    artist = null,
                    albumId = 0L,
                    durationMs = 0L,
                    size = file.length(),
                )
            }
            com.lumidrop.x.ui.MusicPlayback.play(act, listOf(track), 0)
        }
    }

    private fun openFile(t: TransferManager.Transfer) {
        val path = t.filePath ?: return
        val file = File(path)
        val ctx = requireContext()
        if (!file.exists()) {
            Toast.makeText(ctx, "File no longer exists on this device", Toast.LENGTH_SHORT).show()
            return
        }
        // ── FINAL PASS 5 (§3): an APK card's tap opens the INSTALL flow ──────
        // directly (the exact installApk the removed Install button used —
        // same O+ install-permission handoff, same API-21-safe file:// URI,
        // same confirmation behavior). Every other type keeps the plain
        // ACTION_VIEW below, and media types are intercepted even earlier
        // (see openTransferFile — the internal players own images, videos
        // and audio before this function is ever reached).
        // ── GOAT-PASS 11 (PART 1): the two installation paths are STRICTLY ──
        // separated. Ordinary ".apk" keeps the ORIGINAL system-installer flow
        // (tap → Android's own installer, no custom modal — the exact
        // pre-Pass-10 behavior, restored verbatim below). ONLY the ZIP-based
        // package archives (.xapk/.apkm/.apks) route to the built-in
        // inspection + PackageInstaller-session engine.
        val ext = t.fileName.substringAfterLast('.', "").lowercase()
        if (ext == "xapk" || ext == "apkm" || ext == "apks") {
            installPackageArchive(t)
            return
        }
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val mime = MimeTypes.getMimeType(file.extension)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // GOAT v2 fix: was wrapped in Intent.createChooser(), which
            // forces its own "Open with" picker on every single tap and
            // deliberately has no "Always" option (that's what
            // createChooser is for -- one-off choices like a Share sheet,
            // by design not meant to become a persisted default). Plain
            // startActivity() is what actually gives the normal Android
            // behavior asked for: exactly one matching app (or an
            // existing saved default) launches directly, more than one
            // shows Android's own chooser with Just Once/Always, and only
            // apps actually registered for this specific MIME type are
            // offered -- not "every installed app" the way an unbounded
            // */* match would.
            if (intent.resolveActivity(ctx.packageManager) != null) {
                startActivity(intent)
            } else {
                Toast.makeText(ctx, "No app found to open this file", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(ctx, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    // ── Pass-16 (text message actions) ───────────────────────────────────────

    /** Copies [text] to the system clipboard with the app's standard toast
     *  confirmation — the one writer behind both the History Copy button
     *  and the full-text dialog's Copy action. */
    /** ── FINAL PASS 3 (§10): the Received card's ⋮ menu — Share first ─────
     *  (the reason the menu exists), Rename second, both through the app's
     *  existing mechanisms: Share uses the same FileProvider grant
     *  openFile's ACTION_VIEW uses (ACTION_SEND this time, with the real
     *  filename + MIME type); Rename opens the same improved rename modal
     *  the Files/Music sheets use (extension-preserving selection,
     *  duplicate-extension guard, collision + "/" validation, fs-refusal
     *  honesty) and refreshes the Received reconcile afterward.
     *  ── FINAL PASS 5 (§1): two new actions join the menu —
     *   • Open With: the platform's app-selection picker (ACTION_VIEW through
     *     Intent.createChooser) over the SAME FileProvider content URI and
     *     MIME the direct card click uses, so the person can deliberately
     *     pick a non-default handler without disturbing the saved default.
     *     It is additional — the card's normal click (internal players /
     *     installer / ACTION_VIEW) is untouched.
     *   • Show in History: locates the EXACT corresponding History record
     *     (by the received file's path — see showInHistory's own doc), hops
     *     to the History tab, scrolls the card into a comfortable position
     *     and flashes it with the same reveal-highlight the Files sheet's
     *     search-locate uses. A missing record is a small honest toast,
     *     never a jump to a wrong row or a fake entry. */
    private fun showReceivedCardMenu(anchor: View, t: TransferManager.Transfer) {
        val opts = listOf(
            com.lumidrop.x.ui.PickerOption("SHARE", "Share", iconRes = R.drawable.ic_share),
            // ── FINAL PASS 6 (PART 4.4): Open With's glyph is ic_arrow_forward ──
            // (the outgoing "hand this to another app" arrow) instead of
            // ic_android — the little robot read as an app/APK action, not as
            // "choose a different application to open this file". Behavior
            // unchanged.
            com.lumidrop.x.ui.PickerOption("OPEN_WITH", "Open With", iconRes = R.drawable.ic_arrow_forward),
            com.lumidrop.x.ui.PickerOption("SHOW_IN_HISTORY", "Show in History", iconRes = R.drawable.ic_history),
            com.lumidrop.x.ui.PickerOption("RENAME", "Rename", iconRes = R.drawable.ic_edit),
        )
        com.lumidrop.x.ui.showPickerOptionsMenu(anchor, "Received item", opts, compact = true, endAnchored = true) { id ->
            when (id) {
                "SHARE" -> shareReceivedFile(t)
                "OPEN_WITH" -> openReceivedFileWith(t)
                "SHOW_IN_HISTORY" -> showInHistory(t)
                "RENAME" -> showReceivedRenameDialog(t)
            }
        }
    }

    /** ── FINAL PASS 5 (§1A): Open With — the explicit app-selection flow ──────
     *  for a received file. The direct card click runs a plain ACTION_VIEW
     *  (the saved default launches immediately); THIS action deliberately
     *  wraps the same intent in Intent.createChooser so Android's own
     *  "choose an app" sheet appears and the choice applies just once —
     *  the standard "open with" contract, and precisely why it must be a
     *  SEPARATE action rather than a replacement for the card click.
     *  The FileProvider content URI + MIME are identical to Share's/the
     *  click's (no raw paths leave the app); an unreachable resolver or a
     *  device with no compatible app gets the same honest toast the direct
     *  open path uses. */
    private fun openReceivedFileWith(t: TransferManager.Transfer) {
        val path = t.filePath ?: return
        val file = File(path)
        val ctx = requireContext()
        if (!file.exists()) {
            Toast.makeText(ctx, "File no longer exists on this device", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val mime = MimeTypes.getMimeType(file.extension)
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            // createChooser guarantees the picker even when a default exists
            // (its documented purpose), and its EXTRA_INITIAL_INTENTS form is
            // not needed: an unresolvable VIEW intent simply yields the
            // system's own "no apps" response rather than a crash.
            val chooser = Intent.createChooser(view, "Open " + file.name + " with")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(ctx, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    /** ── FINAL PASS 5 (§1B): Show in History — hop from a Received card to ────
     *  its EXACT History record.
     *
     *  Identity: the strongest stable link between a Received row and its
     *  History entry is the FILE'S ABSOLUTE PATH. A received file's path is
     *  written once at completion — HistoryStore.Entry.filePath (recorded by
     *  the completion hook) and the live engine row's filePath both carry it,
     *  and the received reconcile mints its "fsrecv:<path>" row from the very
     *  same file. Matching by path cannot be fooled by two files with the
     *  same name in different folders (the "not simply the first matching
     *  filename" requirement), and renaming follows too: the rename flow
     *  updates the engine row and re-registers provenance, and a renamed
     *  file simply matches its own new path or honestly reports absence.
     *
     *  Behavior: switch to the History tab (its existing render paints
     *  synchronously from the in-memory store), then scroll the matched card
     *  to a comfortable position (a third of the list down — the same rhythm
     *  the Files sheet's search reveal uses) and flash the reveal-highlight
     *  overlay (the same ~30% accent fill + fade FilesPickerSheet.
     *  revealSearchTarget established). History's order is never touched;
     *  no entry is created. A missing record is a small toast, nothing else.
     */
    private fun showInHistory(t: TransferManager.Transfer) {
        val ctx = context ?: return
        val path = t.filePath
        if (path == null) {
            Toast.makeText(ctx, "No history record for this item", Toast.LENGTH_SHORT).show()
            return
        }
        // Resolve against the SAME merged source renderHistoryList builds
        // (store entries + live engine rows), so the jump lands on the very
        // row the tab will show — including its folder grouping.
        val storeEntries = HistoryStore.snapshot(ctx)
        var targetKey: String? = null
        // (a) a direct store entry whose file path matches — the common case.
        // (A tombstoned entry can never be a target: snapshot() only holds
        // live records, and the deleted record's tombstone IS the honest
        // "no history record for this item" answer below.)
        for (e in storeEntries) {
            if (e.filePath != null && e.filePath == path) { targetKey = "solo:hist:${e.id}"; break }
        }
        // (b) a member of a folded folder run — the entry's own key, found
        //     through the group the tab will actually render. The group must
        //     not be tombstoned either (a deleted folder record stays deleted
        //     — renderHistoryList's merge filter would drop the row, so the
        //     reveal would scroll to nothing).
        if (targetKey == null) {
            val byDir = storeEntries.filter { !it.isText && it.groupId != null && it.filePath != null }
                .groupBy { it.groupId!! }
            for ((_, groupEntries) in byDir) {
                if (groupEntries.any { it.filePath == path }) {
                    // renderHistoryList folds a group run under "hist:<gid>".
                    val gid = groupEntries.first().groupId!!
                    if (!HistoryStore.isGroupTombstoned(ctx, gid)) targetKey = "group:hist:$gid"
                    break
                }
            }
        }
        // (c) a live engine row not yet in the store (the belt-and-braces
        //     merge half) — its own solo key.
        if (targetKey == null) {
            for (row in TransferManager.receivedSnapshot()) {
                if (row.filePath == path && !HistoryStore.isTombstoned(ctx, row.id)) {
                    targetKey = "solo:${row.id}"; break
                }
            }
        }
        if (targetKey == null) {
            // Honest absence: no store entry, no live row, nothing to show.
            // (A deleted History record is a REAL absence — tombstoned on
            // purpose — and must report "no record", never fabricate one.)
            Toast.makeText(ctx, "No history record for this item", Toast.LENGTH_SHORT).show()
            return
        }
        // The scroll+flash must run against the RENDERED History list: hop
        // tabs; this entry's own render commit (see renderHistoryTab) carries
        // the reveal. renderHistoryList is synchronous in BUILDING the rows,
        // but the DiffUtil commit rides the adapter asynchronously — the
        // reveal must resolve ONLY in the commit callback (resolved there by
        // revealPendingHistoryRow, never here, or the position lookup would
        // race the rows it targets).
        pendingHistoryRevealKey = targetKey
        selectTab("sent")
    }

    /** FINAL PASS 5 (§1B): the one-shot reveal armed by showInHistory.
     *  Cleared the moment it fires (or the row genuinely isn't present in
     *  the rendered list — a tombstone landed mid-hop, say). */
    private var pendingHistoryRevealKey: String? = null

    /** FINAL PASS 5 (§1B): scrolls the pending reveal target into a
     *  comfortable position and flashes the same reveal-highlight the Files
     *  sheet's search-locate uses. Called from the History render's diff
     *  commit; safe to call when nothing is pending. */
    private fun revealPendingHistoryRow() {
        val key = pendingHistoryRevealKey ?: return
        pendingHistoryRevealKey = null
        if (view == null) return
        sentList.post {
            try {
                val lm = sentList.layoutManager as? LinearLayoutManager ?: return@post
                // currentList (the public AsyncListDiffer snapshot) — the same
                // rows the adapter has genuinely committed; getItem itself is
                // protected and stays inside the adapter.
                val rows = sentAdapter.currentList
                var idx = -1
                for (i in 0 until rows.size) {
                    if (rows[i].key == key) { idx = i; break }
                }
                if (idx < 0) return@post
                // Comfortable: the target about a third of the list down,
                // snapped instantly — the Files sheet reveal's exact rhythm.
                lm.scrollToPositionWithOffset(idx, (sentList.height * 0.33f).toInt())
                sentList.post { flashHistoryRow(idx) }
            } catch (e: Exception) { /* a reveal is best-effort — never blocks navigation */ }
        }
    }

    /** FINAL PASS 5 (§1B): the reveal flash — the same accent-fill overlay +
     *  hold-then-fade rhythm FilesPickerSheet.revealSearchTarget uses (a
     *  ~30% accent fill held 800ms, fading 450ms, inside the row's own
     *  overlay so selection state/ripple/layout are untouched). Reuses the
     *  existing mechanism's exact values rather than inventing a new
     *  animation, per the requirement. */
    private fun flashHistoryRow(idx: Int) {
        try {
            val vh = sentList.findViewHolderForAdapterPosition(idx) ?: return
            val row = vh.itemView
            if (row.width <= 0 || row.height <= 0) return
            val startAlpha = 0x4D
            val flash = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(12).toFloat()
                setColor(col(R.color.accent) or 0xFF000000.toInt())
                alpha = startAlpha
            }
            flash.setBounds(0, 0, row.width, row.height)
            row.overlay.add(flash)
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
        } catch (e: Exception) { /* best-effort reveal */ }
    }

    private fun shareReceivedFile(t: TransferManager.Transfer) {
        val path = t.filePath ?: return
        val file = File(path)
        val ctx = requireContext()
        if (!file.exists()) {
            Toast.makeText(ctx, "File no longer exists on this device", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val mime = MimeTypes.getMimeType(file.extension)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(send, "Share " + file.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Toast.makeText(ctx, "Couldn’t share this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showReceivedRenameDialog(t: TransferManager.Transfer) {
        val path = t.filePath ?: return
        val target = File(path)
        val ctx = requireContext()
        if (!runCatching { target.exists() }.getOrDefault(false)) {
            Toast.makeText(ctx, "File no longer exists on this device", Toast.LENGTH_SHORT).show()
            return
        }
        val oldName = target.name
        val input = Widgets.inputField(ctx, oldName, android.text.InputType.TYPE_CLASS_TEXT).apply {
            setSingleLine()
            val dot = oldName.lastIndexOf('.')
            setSelection(0, if (dot > 0) dot else oldName.length)
        }
        val dlgRef = arrayOfNulls<android.app.Dialog>(1)
        fun tryApply() {
            var newName = input.text.toString().trim()
            // The same duplicate-extension typo guard the Files sheet's modal
            // uses — one trailing extension, never two.
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
                    Toast.makeText(ctx, '"' + newName + '"' + " already exists in this folder", Toast.LENGTH_SHORT).show()
                else -> {
                    val ok = try { target.renameTo(File(target.parentFile, newName)) } catch (e: Exception) { false }
                    if (!ok) { Toast.makeText(ctx, "Couldn't rename — the storage refused", Toast.LENGTH_LONG).show(); return }
                    // The engine row + provenance registries follow the file
                    // to its new path so the next reconcile keeps the row
                    // (now under its new name) instead of dropping it.
                    // ── FINAL PASS 5 (§4): the ORIGINAL receive timestamp is ──
                    // captured BEFORE any registry write and re-applied to the
                    // new path's record — the root cause of the "renamed item
                    // jumps to the top and reads 0s" bug was record()'s
                    // unconditional "now" stamp: the reconcile sorts by this
                    // stamp and the "[Time] ago" label renders from it, so a
                    // fresh stamp inevitably re-birthed the row. With the
                    // preserved stamp the renamed file keeps BOTH its list
                    // position and its natural age. (The old path's stamp
                    // is read first because forgetPath below removes it.)
                    val originalReceivedAt = try {
                        com.lumidrop.x.transfer.ReceivedRegistry.receivedAt(ctx, path).takeIf { it > 0L }
                            ?: t.finishedAt.takeIf { it > 0L }
                            ?: target.lastModified()
                    } catch (e: Exception) { 0L }
                    try {
                        t.filePath = File(target.parentFile, newName).absolutePath
                        // The engine row's own completion stamp stays as-is —
                        // renaming never touches the receipt identity; only
                        // the display name changed.
                        com.lumidrop.x.transfer.ReceivedRegistry.record(ctx, File(target.parentFile, newName).absolutePath, originalReceivedAt)
                        com.lumidrop.x.transfer.ReceivedFileMeta.rememberSender(ctx, File(target.parentFile, newName).absolutePath, t.peerName)
                        com.lumidrop.x.transfer.ReceivedRegistry.forgetPath(ctx, path)
                    } catch (e: Exception) { /* cosmetic bookkeeping */ }
                    dlgRef[0]?.dismiss()
                    lastReceivedScanAtMs = 0L
                    renderCurrentTab()
                    Toast.makeText(ctx, '"' + "Renamed to " + newName + '"', Toast.LENGTH_SHORT).show()
                }
            }
        }
        dlgRef[0] = com.lumidrop.x.ui.showLdxDialog(
            context = ctx,
            title = "Rename",
            bodyView = input,
            iconRes = R.drawable.ic_edit,
            actions = listOf(
                com.lumidrop.x.ui.LdxDialogAction("Cancel"),
                com.lumidrop.x.ui.LdxDialogAction("Rename", dismisses = false) { tryApply() },
            ),
        )
    }

    private fun copyTextToClipboard(text: String) {
        if (text.isEmpty()) return
        try {
            val cm = requireContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("LumiDrop X", text))
            Toast.makeText(requireContext(), "Text copied", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(requireContext(), "Couldn't copy", Toast.LENGTH_SHORT).show()
        }
    }

    /** The full-text reader for a History message card — shows the message
     *  content (sender + relative time + the text itself) with a Copy
     *  action, in the same LdxDialog shell every other small modal uses.
     *  Declined rows keep the same read-only view; the Copy button rides
     *  only on COMPLETED rows. */
    private fun showTextMessageDialog(t: TransferManager.Transfer) {
        val msg = t.textContent ?: return
        // ── FINAL PASS 6 (PART 2): the History text-message viewer is rebuilt ──
        // as a proper three-region modal — header (who/when), the message
        // body as its OWN bounded vertical scroller, and the existing actions
        // (Close / Copy) pinned at the bottom. The old shape rendered the
        // whole message through LdxDialog's plain message TextView: long
        // messages grew the card past the screen (permanently cropped — no
        // scroller anywhere) and the text was not selectable.
        // PART 2 §2.3: the body is a setTextIsSelectable(true) TextView —
        // the platform's own long-press selection, handles and Copy toolbar
        // work inside it (a read-only viewer: Paste/Cut never apply). The
        // existing Copy action stays.
        val ctx = requireContext()
        val who = if (t.direction == TransferManager.Direction.UPLOAD) "From ${t.peerName}" else "To ${t.peerName}"
        val when_ = if (t.finishedAt > 0) fmtTimeAgo(t.finishedAt) else ""
        val headerLine = listOf(who, when_).filter { it.isNotBlank() }.joinToString("  ·  ")
        val copyable = t.status == Status.COMPLETED

        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        // Region 1 — header: icon chip + title left, ✕ right.
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(4), dp(12), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        header.addView(android.widget.ImageView(ctx).apply {
            setImageDrawable(Widgets.icon(ctx, R.drawable.ic_tile_text, col(R.color.accent_light)))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_accent_badge)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).also { it.marginEnd = dp(10) }
        })
        header.addView(android.widget.TextView(ctx).apply {
            this.text = "Text message"
            textSize = 16f; setTextColor(col(R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val closeBtn = Widgets.iconButton(ctx, R.drawable.ic_close, col(R.color.text_secondary),
            sizeDp = 36, iconSizeDp = 16, contentDescription = "Close")
        header.addView(closeBtn)
        body.addView(header)

        // The who/when line — the identification the old dialog carried.
        if (headerLine.isNotBlank()) {
            body.addView(android.widget.TextView(ctx).apply {
                this.text = headerLine
                textSize = 11f; setTextColor(col(R.color.text_muted))
                setPadding(dp(18), dp(0), dp(18), dp(0))
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            })
        }

        // Region 2 — the message itself: a bounded scroller (60% of the
        // screen height — generous for reading, never past the window) over
        // the VERBATIM text (line breaks, whitespace, every character
        // exactly as sent — nothing here mutates the content).
        val scrollHeight = (ctx.resources.displayMetrics.heightPixels * 0.60f).toInt().coerceAtLeast(dp(240))
        val scroll = android.widget.ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, scrollHeight)
                    .also { it.topMargin = dp(8) }
        }
        val content = android.widget.TextView(ctx).apply {
            this.text = msg
            textSize = 13.5f
            setTextColor(col(R.color.text_primary))
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(18), dp(4), dp(18), dp(14))
            // PART 2 §2.3 — native selection in the read-only body: the
            // platform's own long-press word-select, drag handles and Copy
            // toolbar. On a NON-editable TextView this is exactly what
            // setTextIsSelectable was built for (unlike the editable field
            // in PART 1, where it had to go). Scrolling still works: the
            // scroller owns vertical drags that move beyond the slop, the
            // selection gesture owns the still-finger long-press, and the
            // selection handles' drags request their own disallow-intercept
            // (ArrowKeyMovementMethod does this in AOSP).
            setTextIsSelectable(true)
            layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        scroll.addView(content, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        body.addView(scroll)

        // The same full-screen dim + centered card shell LdxDialog uses.
        // The card's height is WRAP_CONTENT but the body's ScrollView is
        // already bounded at 60% of the screen, so the whole card always
        // fits; the scrim tap and Back both dismiss.
        val dlg = android.app.Dialog(ctx, android.R.style.Theme_Translucent_NoTitleBar)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        try { com.lumidrop.x.ui.SystemBarChrome.applyColors(dlg.window) } catch (e: Exception) { /* cosmetic */ }
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dlg.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dlg.window?.setDimAmount(0.55f)
        dlg.setCancelable(true)

        // Region 3 — the existing actions, pinned: Copy (completed rows
        // only, exactly as before) + Close.
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            setPadding(dp(14), dp(6), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        if (copyable) {
            actions.addView(android.widget.TextView(ctx).apply {
                text = "Copy"; textSize = 14f
                setTextColor(col(R.color.text_secondary))
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_ghost)
                setPadding(dp(18), dp(11), dp(18), dp(11))
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .also { it.marginEnd = dp(8) }
                setOnClickListener { copyTextToClipboard(msg) }
            })
        }
        actions.addView(android.widget.TextView(ctx).apply {
            text = "Close"; textSize = 14f; gravity = android.view.Gravity.CENTER
            setTextColor(col(R.color.white))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = Widgets.rippleRect(ctx, col(R.color.accent), radiusDp = 12)
            setPadding(dp(18), dp(11), dp(18), dp(11))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { dlg.dismiss() }
        })
        body.addView(actions)

        val backdrop = android.widget.FrameLayout(ctx).apply {
            isClickable = true
            setOnClickListener { dlg.dismiss() }
        }
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_dialog)
            setPadding(dp(6), dp(12), dp(6), dp(4))
            elevation = dp(10).toFloat()
            isClickable = true
        }
        val maxCardW = dp(420).coerceAtMost(ctx.resources.displayMetrics.widthPixels - dp(48))
        card.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        backdrop.addView(card, android.widget.FrameLayout.LayoutParams(maxCardW, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.gravity = android.view.Gravity.CENTER_VERTICAL or android.view.Gravity.CENTER_HORIZONTAL
        })

        dlg.setContentView(backdrop)
        closeBtn.setOnClickListener { dlg.dismiss() }
        dlg.show()
    }

    /** ── GOAT-PASS 10 (PART 1.9): the unified package install flow. ────────────
     *  Ordinary .apk AND the ZIP-based containers (.xapk/.apkm/.apks) all
     *  land here — PackageInspector reads the archive's REAL structure
     *  (never the extension alone), the info dialog presents it honestly
     *  (unknowns show as "Unknown", never invented), and the install runs
     *  through Android's own PackageInstaller session: one session, base +
     *  every split, committed together. The system's own confirmation
     *  screen is the ONLY thing that installs; success is claimed only
     *  after the OS's STATUS_SUCCESS broadcast.
     *
     *  Authorization (API 26+): checked BEFORE any session exists; the
     *  user is sent to the system screen and re-checks on their return.
     *
     *  OBB: staged best-effort after a successful install, only where the
     *  storage model permits (Android ≤10 or all-files access); a package
     *  whose OBB could not be placed reports the gap honestly instead of
     *  claiming completeness. */
    /** ── GOAT-PASS 11 (PART 1.1/1.3): the ORIGINAL ordinary-APK install ──────
     *  flow, restored verbatim from before Pass 10 introduced the archive
     *  engine — tap an ".apk" and Android's own system installer appears,
     *  with no custom modal, no inspection screen, no extra steps.
     *
     *  The flow is exactly the pre-Pass-10 implementation (kept, not
     *  re-approximated): API 26+ checks the unknown-apps authorization and
     *  routes to the system screen (the user returns and taps again — the
     *  toast says so); the URI is a FileProvider content URI with
     *  FLAG_GRANT_READ_URI_PERMISSION on API 24+ (Android 7+ file-URI
     *  restriction respected) and Uri.fromFile only on API ≤ 23 where it is
     *  still the platform's own convention; MIME is Android's package-archive
     *  type; returning from the installer is the normal activity lifecycle.
     */
    private fun installApk(t: TransferManager.Transfer) {
        val path = t.filePath ?: return
        val file = File(path)
        val ctx = ctxOrNull() ?: return
        if (!file.exists()) {
            Toast.makeText(ctx, "File no longer exists on this device", Toast.LENGTH_SHORT).show()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !ctx.packageManager.canRequestPackageInstalls()) {
            Toast.makeText(ctx, "Allow LumiDrop X to install apps, then tap Install again", Toast.LENGTH_LONG).show()
            com.lumidrop.x.transfer.PackageInstallEngine.requestUnknownSourcesPermission(ctx)
            return
        }
        try {
            val uri = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) {
                android.net.Uri.fromFile(file)
            } else {
                FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            }
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(ctx, "Couldn't start the installer", Toast.LENGTH_SHORT).show()
        }
    }

    /** ── GOAT-PASS 11 (PART 1.2 Path B): the ARCHIVE installation entry — ────
     *  .xapk/.apkm/.apks ONLY (the routing above guarantees it; an ordinary
     *  .apk can never reach here). Runs the structural inspection off the UI
     *  thread and presents the built-in install dialog. */
    private fun installPackageArchive(t: TransferManager.Transfer) {
        val path = t.filePath ?: return
        val file = File(path)
        val ctx = ctxOrNull() ?: return
        // ── GOAT-PASS 14 (PART 7): Received and the Files sheet share ONE ──
        // installation system — this tab's private copy of the archive
        // install flow (GP11–GP13) moved to the shared XapkInstallFlow,
        // which now serves BOTH surfaces with the same inspection, resolver,
        // validation, icon pipeline, session and result handling.
        com.lumidrop.x.transfer.XapkInstallFlow.launch(viewLifecycleOwner.lifecycleScope, ctx, file)
    }

    // ── Phase 5 (§5.4): destructive-action consistency + multi-select ────
    //
    // One confirmation gate for EVERY way files leave the Received tab:
    //   · single file (row action + expanded-folder child action)
    //   · single folder (row action — folders are now deletable at all)
    //   · multiple items (long-press/range → selection mode → Delete Selected)
    //   · everything (select-all via a range, then Delete Selected — the
    //     retired "Delete Received Files" pill's job, now one gesture)
    // All of them run the actual deletion on Dispatchers.IO, dismiss any
    // live in-memory records the deleted files had, force the reconcile
    // scan to re-run immediately (bypassing its 2s throttle), and report
    // an honest failure toast when the filesystem refused a delete.

    /** Single-file confirmation — used by BOTH the row action button and
     *  the per-child delete inside an expanded folder, so the two paths
     *  can't drift apart again (they had: one confirmed, one didn't). */
    private fun confirmDeleteOneFile(t: TransferManager.Transfer) {
        val ctx = requireContext()
        val path = t.filePath ?: return
        showLdxDialog(
            context = ctx,
            title = "Delete \"${t.fileName}\"?",
            message = "This permanently deletes the file from this device. This can't be undone.",
            iconRes = R.drawable.ic_delete,
            iconTintRes = R.color.red_stop,
            actions = listOf(
                LdxDialogAction("Cancel", LdxButtonStyle.GHOST),
                LdxDialogAction("Delete", LdxButtonStyle.DESTRUCTIVE) {
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        // ── Pass-33 (#4): defense in depth — the row came from ──
                        // the provenance-filtered reconcile, but the LIVE path
                        // (a row minted from a just-completed transfer) is
                        // re-checked here anyway: an unproven path is never
                        // deleted, whatever rendered its row.
                        val proven = try { com.lumidrop.x.transfer.ReceivedRegistry.isAppReceived(ctx, path) } catch (e: Exception) { false }
                        val deleted = proven && try { File(path).delete() } catch (e: Exception) { false }
                        if (proven) try { com.lumidrop.x.transfer.ReceivedRegistry.forgetTree(ctx, path) } catch (e2: Exception) {}
                        TransferManager.dismiss(t.id)
                        withContext(Dispatchers.Main) {
                            if (view == null) return@withContext
                            if (!deleted) Toast.makeText(ctx, if (proven) "Couldn't delete ${t.fileName}" else "This file wasn't received through LumiDrop X", Toast.LENGTH_SHORT).show()
                            lastReceivedScanAtMs = 0L
                            renderCurrentTab()
                        }
                    }
                },
            ),
        )
    }

    /** Pass-3 (R9): the standalone whole-folder Delete confirmation is
     *  RETIRED along with its Delete Folder button — folder deletion goes
     *  through the Received tab's long-press selection + the shared
     *  "Delete Selected" action (confirmDeleteSelected), which resolves the
     *  exact same folderDeletionTarget below. The resolver itself stays:
     *  the shared path depends on it. */

    /** The directory a received folder-group should be deleted as, or null
     *  when no single safe directory represents it. For filesystem-
     *  reconciled groups the group id IS the directory (buildReconciledReceived
     *  keys them "fsrecv-dir:<absolute path>"); for live session groups the
     *  members' common parent is used when it is a genuine subdirectory of
     *  the receive root. Falls back to null — the shared delete path then
     *  deletes just the member files — never anything outside the receive
     *  tree. */
    private fun folderDeletionTarget(info: TransferManager.TransferGroupInfo, members: List<TransferManager.Transfer>): File? {
        val ctx = context ?: return null
        if (info.id.startsWith("fsrecv-dir:")) return File(info.id.removePrefix("fsrecv-dir:"))
        val root = File(com.lumidrop.x.settings.SettingsManager.getUploadPath(ctx))
        val paths = members.mapNotNull { it.filePath }
        if (paths.isEmpty()) return null
        var common: File? = File(paths[0]).parentFile
        for (p in paths.drop(1)) {
            var c: File? = common
            while (c != null && !p.startsWith(c.absolutePath + File.separator)) c = c.parentFile
            common = c
            if (common == null) return null
        }
        val dir = common ?: return null
        // Only ever a STRICT subdirectory of the receive root — the root
        // itself is Delete-All's territory, and anything outside the
        // receive tree is somebody else's files entirely.
        return if (dir.absolutePath != root.absolutePath && dir.absolutePath.startsWith(root.absolutePath + File.separator)) dir else null
    }

    // ── Pass-28 (#1.1): the tab-general selection machinery ─────────────────
    // One mechanism, two independent instances: the Received tab's original
    // hold-to-select system, and the History ("sent") tab's identical one.
    // All row wiring goes through these tab-aware primitives; the Received
    // tab keeps its thin wrappers below so every existing call site keeps
    // its exact meaning.
    private fun selectionActiveFor(tab: String): Boolean =
        when (tab) {
            "sent" -> sentSelectionMode
            "active" -> activeSelectionMode
            else -> receivedSelectionMode
        }

    private fun selectionKeysFor(tab: String): MutableSet<String> =
        when (tab) {
            "sent" -> sentSelectionKeys
            "active" -> activeSelectionKeys
            else -> receivedSelectionKeys
        }

    /** Selection-mode entry: long-pressed row starts selected. */
    private fun enterSelection(tab: String, key: String) {
        when (tab) {
            "sent" -> {
                sentSelectionMode = true
                sentSelectionKeys.clear()
                sentSelectionKeys.add(key)
                sentAnchorKey = key   // GOAT-PASS 7 (§3A): the entering press is the range anchor
                sentAdapter.submitRows(lastSentRows)
                refreshSentHeader()
            }
            "active" -> {
                // ── GOAT-PASS 15 (§9): the Active tab's own entry — same
                // contract, its own state, and the Active header's swap to
                // the selection toolbar (refreshActiveHeader).
                activeSelectionMode = true
                activeSelectionKeys.clear()
                activeSelectionKeys.add(key)
                activeAnchorKey = key
                activeAdapter.submitRows(lastActiveRows)
                refreshActiveHeader()
            }
            else -> {
                receivedSelectionMode = true
                receivedSelectionKeys.clear()
                receivedSelectionKeys.add(key)
                receivedAnchorKey = key   // GOAT-PASS 7 (§3A): the entering press is the range anchor
                receivedAdapter.submitRows(lastReceivedRows)
                refreshReceivedHeader()
            }
        }
    }

    /** Selection-mode toggle for one row key. */
    private fun toggleSelection(tab: String, key: String) {
        if (!selectionActiveFor(tab)) { enterSelection(tab, key); return }
        val keys = selectionKeysFor(tab)
        // ── GOAT-PASS 9 (§3 — click-path anchoring): a plain tap that SELECTS ──
        // a row establishes it as the range anchor (the same contract the
        // Files/Music sheets, the media picker and the app picker implement),
        // and deselecting the anchored row clears the anchor — no ghost
        // anchors. Previously only the long-press paths wrote the tab's
        // anchor, so a range could never start from a tap-selected row.
        val nowSelected: Boolean
        if (!keys.remove(key)) { keys.add(key); nowSelected = true } else { nowSelected = false }
        when (tab) {
            "sent" -> {
                if (nowSelected) sentAnchorKey = key else if (sentAnchorKey == key) sentAnchorKey = null
                sentAdapter.submitRows(lastSentRows)
                refreshSentHeader()
            }
            "active" -> {
                if (nowSelected) activeAnchorKey = key else if (activeAnchorKey == key) activeAnchorKey = null
                activeAdapter.submitRows(lastActiveRows)
                refreshActiveHeader()
            }
            else -> {
                if (nowSelected) receivedAnchorKey = key else if (receivedAnchorKey == key) receivedAnchorKey = null
                receivedAdapter.submitRows(lastReceivedRows)
                refreshReceivedHeader()
            }
        }
    }

    /** Leaves selection mode and restores the tab's normal header. */
    private fun exitSelection(tab: String) {
        when (tab) {
            "sent" -> {
                sentSelectionMode = false
                sentSelectionKeys.clear()
                sentAnchorKey = null   // GOAT-PASS 7 (§3A): the anchor dies with the mode
                if (::sentHeaderRow.isInitialized) {
                    sentAdapter.submitRows(lastSentRows)
                    refreshSentHeader()
                }
            }
            "active" -> {
                activeSelectionMode = false
                activeSelectionKeys.clear()
                activeAnchorKey = null
                activeAdapter.submitRows(lastActiveRows)
                refreshActiveHeader()
            }
            else -> {
                receivedSelectionMode = false
                receivedSelectionKeys.clear()
                receivedAnchorKey = null   // GOAT-PASS 7 (§3A): the anchor dies with the mode
                if (::receivedHeaderRow.isInitialized) {
                    receivedAdapter.submitRows(lastReceivedRows)
                    refreshReceivedHeader()
                }
            }
        }
    }

    // ── GOAT-PASS 7 (§3A — RANGE SELECTION, the Files-sheet model): the ─────────
    // last long-pressed row is the ANCHOR; the next long-press selects the
    // whole inclusive span between anchor and target in the CURRENT row
    // order (the same order the tab renders), additively — unrelated picks
    // are preserved — and moves the anchor to the target. Without a usable
    // anchor (none yet, or it left the list through a sort/rescan), the press
    // selects just this row (entering selection mode when needed) and becomes
    // the anchor. This is the exact longPress contract the Files and Music
    // sheets already implement, so all four list surfaces behave identically.
    private var receivedAnchorKey: String? = null
    private var sentAnchorKey: String? = null
    private fun anchorKeyFor(tab: String): String? = when (tab) {
        "sent" -> sentAnchorKey
        "active" -> activeAnchorKey
        else -> receivedAnchorKey
    }
    private fun setAnchorKeyFor(tab: String, key: String?) {
        when (tab) {
            "sent" -> sentAnchorKey = key
            "active" -> activeAnchorKey = key
            else -> receivedAnchorKey = key
        }
    }
    private fun rowsFor(tab: String): List<TransferManager.TransferRowItem> = when (tab) {
        "sent" -> lastSentRows
        "active" -> lastActiveRows
        else -> lastReceivedRows
    }

    /** Only rows the Delete action can actually honor may join a range or a
     *  drag pass: a COMPLETED solo row with a real file path, a completed
     *  TEXT MESSAGE row (no file by construction — its content rides the row
     *  itself, and deleteHistoryRecord removes exactly that record), or a
     *  folder group whose members have ALL completed (the same aggregate gate
     *  the long-press entry always used — a group that is still mid-flight on
     *  another surface must never be deletable from here). A live/PENDING
     *  row entering the selection set would be a promise the action bar
     *  can't keep.
     *
     *  ── GOAT-PASS 13 (§7): text-message rows are range/slidable again. ──
     *  The old `filePath != null` half of the Solo gate silently excluded
     *  every Text Message History item from RANGE selection and Hold &
     *  Slide (a plain tap/long-press still worked — those paths never
     *  consult this predicate), so a range spanning a text message jumped
     *  over it and a slide pass ignored it: the reported "Text Message
     *  containers don't select". A completed text row is exactly as
     *  deletable as a completed file row, so it now passes the same gate. */
    private fun isRangeSelectable(item: TransferManager.TransferRowItem): Boolean =
        isRangeSelectableIn("received", item)

    /** ── GOAT-PASS 15 (§9.5): the tab-aware form. Received/History keep the
     *  classic DELETION contract below (a completed row whose Delete action
     *  can be honored); the ACTIVE tab's contract is transfer-lifecycle —
     *  exactly the rows the toolbar's Pause/Resume/Cancel actions can act
     *  on: any still-live solo row (PENDING or ACTIVE, whatever its kind —
     *  waiting, streaming, paused, reconnecting, a streamed zip included)
     *  and any group with at least one live member. Terminal rows (the
     *  completed/failed flash, dismissed cards) are never selectable — no
     *  action could ever honor them. */
    private fun isRangeSelectableIn(tab: String, item: TransferManager.TransferRowItem): Boolean = when (item) {
        is TransferManager.TransferRowItem.Solo -> if (tab == "active")
            // ── FINAL PASS 1 (§7.5): "Auto-Resume on Reconnection" rows are ──
            // incomplete transfers this device can genuinely cancel (the
            // kept-for-resume FAILED+interrupted recovery card — the live
            // ACTIVE+reconnecting leg was already covered by ACTIVE). They
            // must be individually selectable, join Select All, and count in
            // the toolbar's counter — the lifecycle actions can honor them.
            item.transfer.status == Status.PENDING || item.transfer.status == Status.ACTIVE ||
                (item.transfer.status == Status.FAILED && item.transfer.interrupted)
        else
            // ── FINAL PASS 3 (§2 — ZIP-streaming History rows select like ──
            // every other row). The old `filePath != null` half silently
            // excluded the SENT archive row of a streamed ZIP batch: the
            // sender mints it from a live-synthesized ZipBatchNode whose
            // localPath is null (nothing of the archive ever exists on the
            // sender's disk), so Hold & Slide and Range selection — the two
            // paths that consult this predicate — jumped over it while a
            // plain tap still worked (taps never consult the gate). The
            // exact defect class GOAT-PASS 13 (§7) fixed for text messages.
            // A completed streamed-ZIP row is exactly as deletable as any
            // other completed row, so it now passes the same gate.
            item.transfer.status == Status.COMPLETED &&
                (item.transfer.isTextMessage || item.transfer.isZipStream || item.transfer.filePath != null)
        is TransferManager.TransferRowItem.Group -> if (tab == "active")
            item.members.any { it.status == Status.PENDING || it.status == Status.ACTIVE ||
                (it.status == Status.FAILED && it.interrupted) }
        else
            TransferManager.groupStatus(item.members) == Status.COMPLETED
    }

    /** Long-press: the range-selection entry (see the anchor model above).
     *
     *  ── GOAT-PASS 8 (§3 — the ghost-anchor guard): the anchor must still be ──
     *  a SELECTED row for a range to extend from it — the old check accepted
     *  any anchor key still present in the list, so a key left behind by a
     *  deselection path that didn't clear it (a tap toggling the anchor off
     *  didn't, a drag REMOVE pass over it didn't, a rescan's retainAll didn't)
     *  made the NEXT long-press select a surprise RANGE from a previous
     *  session's anchor — the exact reported "hold the seventh item, get a
     *  range from somewhere else".
     *  ── GOAT-PASS 8 (§4 — Hold & Slide mode): the press applies only the ──
     *  initiating toggle (an unselected row selects, a selected row deselects
     *  — matching the drag pass mode captured at DOWN), never a range. */
    private fun longPressSelection(tab: String, key: String) {
        val rows = rowsFor(tab)
        val keys = selectionKeysFor(tab)
        val slideMode = com.lumidrop.x.settings.SettingsManager.isSlideSelection(requireContext())
        val anchor = anchorKeyFor(tab)
        val anchorPos = if (anchor != null) rows.indexOfFirst { it.rowKey() == anchor } else -1
        val anchorValid = anchor != null && anchorPos >= 0 && anchor in keys
        val targetPos = rows.indexOfFirst { it.rowKey() == key }
        if (anchor == null || !anchorValid || targetPos < 0 || slideMode) {
            if (!selectionActiveFor(tab)) {
                enterSelection(tab, key)
            } else if (slideMode) {
                // Hold & Slide: the initiating press toggles the row.
                if (key in keys) keys.remove(key) else keys.add(key)
                repaintSelection(tab)
            } else if (key !in keys) {
                keys.add(key)
                repaintSelection(tab)
            }
            setAnchorKeyFor(tab, key)
            return
        }
        val from = minOf(anchorPos, targetPos)
        val to = maxOf(anchorPos, targetPos)
        for (i in from..to) {
            rows.getOrNull(i)?.let { r -> if (isRangeSelectableIn(tab, r)) keys.add(r.rowKey()) }
        }
        setAnchorKeyFor(tab, key)
        repaintSelection(tab)
    }

    /** One shared repaint for every selection mutation: re-diff the tab's
     *  rows (selection state rides the same TransferRowState fields the taps
     *  use) and refresh the header's live count. */
    private fun repaintSelection(tab: String) {
        when (tab) {
            "sent" -> { sentAdapter.submitRows(lastSentRows); refreshSentHeader() }
            "active" -> { activeAdapter.submitRows(lastActiveRows); refreshActiveHeader() }
            else -> { receivedAdapter.submitRows(lastReceivedRows); refreshReceivedHeader() }
        }
    }

    // ── GOAT-PASS 7 (§3B + §3D — HOLD-AND-SLIDE with edge auto-scroll): the ─────
    // same shared attachDragSelect gesture the Files and Music sheets use —
    // the rows keep their own long-press (the range anchor above), the drag
    // arms on top of it and captures its ADD/REMOVE mode from the row's state
    // at DOWN, every crossed row lands on that captured state through the
    // same selection-key primitives the taps use, and the list auto-scrolls
    // while the finger holds near its rim (speed by proximity, selection
    // continues under the stationary finger, stops with the gesture).
    private fun attachTabDragSelect(list: RecyclerView, tab: String) {
        // ── GOAT-PASS 8 (§4): captured ONCE at wiring time (an application ──
        // context outlives any detach race a late touch could ride) — the
        // setting is still read LIVE from it on every gesture.
        val appCtx = requireContext().applicationContext
        com.lumidrop.x.ui.attachDragSelect(
            list,
            isSelectedAt = { pos ->
                rowsFor(tab).getOrNull(pos)?.let { it.rowKey() in selectionKeysFor(tab) } ?: false
            },
            onApply = { pos, add ->
                val item = rowsFor(tab).getOrNull(pos) ?: return@attachDragSelect
                if (!isRangeSelectableIn(tab, item)) return@attachDragSelect
                val keys = selectionKeysFor(tab)
                val key = item.rowKey()
                if (add) {
                    if (!selectionActiveFor(tab)) { enterSelection(tab, key); return@attachDragSelect }
                    keys.add(key)
                } else {
                    keys.remove(key)
                }
                // ── SELECTION FINAL PASS 1 (§7 — the drag fast path): the ──
                // crossed row's OWN chrome (a selection-only payload rebind
                // through the adapter, no submitRows, no snapshot re-map, no
                // DiffUtil) plus the header's in-place count patch, NOW; the
                // one full reconciliation lands at gesture end (below). The
                // old per-row full repaintSelection was exactly the cost that
                // made a slide across a long History/Received list fall
                // behind the finger.
                adapterFor(tab)?.notifyRowSelectionChanged(pos)
                when (tab) {
                    "sent" -> refreshSentHeader()
                    "active" -> refreshActiveHeader()
                    else -> refreshReceivedHeader()
                }
            },
            edgeAutoScroll = true,
            // ── GOAT-PASS 8 (§4): the drag arms only in Hold & Slide mode; ──
            // in Range mode (the default) the row's own long-press (the
            // range anchor machinery) owns the whole interaction.
            slideEnabled = { com.lumidrop.x.settings.SettingsManager.isSlideSelection(appCtx) },
            // ── SELECTION FINAL PASS 1 (§7/§3): once per gesture — the full ──
            // submitRows reconciliation (snapshots, liveById, diff) that
            // settles the tab exactly as every tap always has.
            onGestureEnd = { repaintSelection(tab) },
            // ── GOAT-PASS 8 (§14): a press inside an EXPANDED received ──
            // folder's scrollable content area belongs to that content — its
            // rows scroll and open on their own — and must never resolve to
            // the parent folder row's selection. The expanded box's scroll
            // view carries exactly this tag (createRowShell's FolderBox), so
            // the gesture's hit-test walks it as an exclusion region.
            // ── GOAT-PASS 9 (§11): widened to the box's CONTENT tag too — the ──
            // exclusion must cover every view inside the expanded box (the
            // breadcrumb lives beside the rows inside the content), not only
            // the scroller itself.
            // ── GOAT-PASS 11 (§9): "folder_box" — FolderBox's ROOT ──────────────────────────
            // now carries the marker too. The scroller/content tags alone
            // left the fade strips and the scrollbar indicator — SIBLINGS
            // of the scroller, not its descendants — outside the
            // exclusion: a hold that started on a rim strip armed the drag
            // against the parent folder row, and the scroll that followed
            // SELECTED it. Every view inside the expanded box resolves
            // through the tagged root; scrolling (from anywhere inside the
            // box, rim strips included) can never activate the parent's
            // selection.
            hitExcluded = { it.tag == "child_list_scroll" || it.tag == "child_list" || it.tag == "folder_box" },
        )
    }

    private fun exitReceivedSelection() = exitSelection("received")
    private fun exitSentSelection() = exitSelection("sent")
    private fun exitActiveSelection() = exitSelection("active")

    /** ── SELECTION FINAL PASS 1 (§7): the tab's adapter, for the drag fast ──
     *  path's targeted selection-payload rebind. */
    private fun adapterFor(tab: String): TransferRowAdapter = when (tab) {
        "sent" -> sentAdapter
        "active" -> activeAdapter
        else -> receivedAdapter
    }

    /** The Received tab's header, rebuilt per mode with the SAME churn
     *  discipline the History tab's uses (views rebuilt only when the mode
     *  actually flips, the count label text-patched in place afterwards —
     *  the GOAT-PASS 7 nudge-driven render cadence makes the old rebuild-
     *  every-call shape genuinely wasteful).
     *
     *  ── GOAT-PASS 7 (§4 — the redesigned normal-mode header): ──
     *    LEFT    the live total item counter, styled EXACTLY like the
     *            History tab's count label (one consistent design);
     *    MIDDLE  the "Open Received Folder" pill (the counter's flexible
     *            width keeps it right of the count);
     *    RIGHT   the sorting icon, fully to the right of the pill.
     *  The old "Delete Received Files" pill is retired — the professional
     *  selection system (range selection, hold-and-slide, edge auto-scroll
     *  and the selection action bar) is the way to select and manage
     *  received items now. Selection mode keeps the exact "N selected ·
     *  Cancel / Delete Selected" action bar it always had. */
    private var receivedHeaderIsSelection: Boolean? = null
    private var receivedHeaderCountLabel: TextView? = null
    /** GOAT-PASS 11 (§10): the RECEIVED selection pill's live count chip —
     *  patched in place by refreshReceivedHeader on every selection change. */
    private var receivedSelCountLabel: TextView? = null
    private fun refreshReceivedHeader() {
        if (!::receivedHeaderRow.isInitialized) return
        val ctx = requireContext()
        val header = receivedHeaderRow
        val selection = receivedSelectionMode
        // ── GOAT-PASS 8 (§1 — the initialization root fix): the mode flag is ──
        // NULLABLE and starts null ("never built"), exactly like the History
        // tab's own sentHeaderIsSelection has since Pass-28: the plain
        // `= false` default made the very first refresh see selection(false)
        // == flag(false) and SKIP the build entirely, so the Received tab
        // opened with an EMPTY header — no item counter, no Open Received
        // Folder pill, no sort icon — until the user happened to enter and
        // leave selection mode (the only path that flipped the flag and
        // rebuilt the row). The flag is also reset to null in onCreateView so
        // a recreated view always builds fresh against its new (empty) header
        // container, never against a flag left over from a previous view.
        if (selection != receivedHeaderIsSelection) {
            header.removeAllViews()
            receivedHeaderCountLabel = TextView(ctx).apply {
                textSize = 11.5f
                setTextColor(col(R.color.text_secondary))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            header.addView(receivedHeaderCountLabel)
            if (selection) {
                // ── GOAT-PASS 9 (§6): the items counter is HIDDEN while ──
                // selection mode is active — the selection action pill owns
                // the row, aligned to its leading edge with no empty gap
                // where the counter sat (GONE, not INVISIBLE), and the
                // counter reappears the moment the mode ends.
                receivedHeaderCountLabel?.visibility = View.GONE
                header.addView(buildSelectionActionBar("received"))
            } else {
                receivedHeaderCountLabel?.visibility = View.VISIBLE
                header.addView(smallActionBtn("Open Received Folder", null, R.color.text_muted) { openReceiveFolder() })
                header.addView(sortIconBtn { btn -> showTransfersSortMenu(btn, "received") })
            }
            receivedHeaderIsSelection = selection
        }
        // The live figure, patched in place — only in the normal face (the
        // label is hidden for the whole selection session, §6).
        if (!selection) {
            receivedHeaderCountLabel?.text = "${lastReceivedRows.size} item${if (lastReceivedRows.size != 1) "s" else ""}"
            receivedSelCountLabel = null   // the counter chip left with the selection pill
        } else {
            // ── GOAT-PASS 11 (§10): the selection pill's number-only counter,
            // patched in place on EVERY selection mutation (toggles, ranges,
            // drag passes, Select All, deselections) — refreshReceivedHeader
            // is exactly the refresh point repaintSelection and every row
            // toggle already funnel through, so the chip always matches the
            // actual selected-item collection.
            receivedSelCountLabel?.text = "${receivedSelectionKeys.size}"
        }
    }

    /** ── GOAT-PASS 8 (§2 — the compact selection action bar, ONE pill) + ──────
     *  ── GOAT-PASS 15 (§9.3/§9.4 — the ACTIVE toolbar and the shared Close) ──
     *
     *  RECEIVED and HISTORY: counter · Delete · Select All · Close(icon).
     *  The old text "Cancel" exit segment was renamed and moved — GOAT-PASS
     *  15 (§9.4) replaced it with the same minimalist Close glyph the Active
     *  tab's toolbar carries, at the FAR RIGHT, so the exit control is one
     *  consistent affordance across all three tabs and can never be confused
     *  with Active's genuinely destructive red Cancel action. Delete and
     *  Select All keep their exact behavior and the counter its exact
     *  styling/sizing/padding treatment.
     *
     *  ACTIVE: counter · Pause · Resume · Cancel · Close(icon), in exactly
     *  that order, on the exact same pill foundation — Pause in the accent
     *  purple the app's "Sending…" presentation uses, Resume in the orange
     *  the Pause action is associated with, Cancel in the app's red. Every
     *  action applies only to the rows it can legitimately affect (see the
     *  bulk*Selected functions); the Close glyph exits the mode and cancels
     *  nothing. */
    private fun buildSelectionActionBar(tab: String): LinearLayout {
        val ctx = requireContext()
        fun segmentLabel(text: String, colorRes: Int, bold: Boolean = false) = TextView(ctx).apply {
            this.text = text; textSize = 11f
            setTextColor(col(colorRes))
            if (bold) typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
        fun segmentIcon(iconRes: Int, colorRes: Int) = ImageView(ctx).apply {
            setImageDrawable(Widgets.icon(ctx, iconRes, col(colorRes)))
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).also { it.marginEnd = dp(5) }
        }
        fun separator() = View(ctx).apply {
            setBackgroundColor(col(R.color.divider))
            layoutParams = LinearLayout.LayoutParams(dp(1), dp(16)).also {
                it.marginStart = dp(9); it.marginEnd = dp(9)
            }
        }
        fun touch(target: LinearLayout, onClick: () -> Unit) {
            target.isClickable = true; target.isFocusable = true
            target.background = ContextCompat.getDrawable(ctx, R.drawable.bg_icon_ripple)
            target.setPadding(dp(9), dp(7), dp(9), dp(7))
            target.setOnClickListener { onClick() }
        }
        /** FINAL PASS 1 (§6): the icon-only segments' touch wiring — wider
         *  horizontal padding than the labeled ones (no text to spread the
         *  target), same ripple, same 34dp pill height, same tap contract. */
        fun touchIconOnly(target: LinearLayout, onClick: () -> Unit) {
            target.isClickable = true; target.isFocusable = true
            target.background = ContextCompat.getDrawable(ctx, R.drawable.bg_icon_ripple)
            target.setPadding(dp(13), dp(7), dp(13), dp(7))
            target.setOnClickListener { onClick() }
        }
        val pill = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(col(R.color.bg_deep))
                setStroke(dp(1), col(R.color.bg_secondary))
            }
            setPadding(dp(4), dp(2), dp(4), dp(2))
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            // ── GOAT-PASS 15 (§9.2): the Active header's swap machinery finds
            // this view by tag to remove it when the mode ends.
            tag = "selection_action_bar"
        }

        if (tab == "active") {
            // ── FINAL PASS 1 (§6 — icon-only Pause/Resume/Cancel) ────────────────
            // The five-segment pill (counter · Pause · Resume · Cancel ·
            // Close) crowded narrow screens enough to crop or squeeze its
            // labels. Pause, Resume AND Cancel are now ICON-ONLY segments —
            // the same professional glyphs the per-row buttons and the bulk
            // menu already use, in their established colors (Pause keeps the
            // Sending purple, Resume keeps the Pause action's orange, Cancel
            // keeps the app's red) — with their actions byte-identical. The
            // counter chip (the one element whose text must stay readable)
            // and the minimalist far-right Close keep their exact designs,
            // and every icon-only segment widens its horizontal padding so
            // the tap target stays comfortable at the pill's 34dp height.
            // Content descriptions carry the semantics the labels dropped.
            fun iconOnlySegment(iconRes: Int, colorRes: Int, desc: String): LinearLayout =
                LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    addView(ImageView(ctx).apply {
                        setImageDrawable(Widgets.icon(ctx, iconRes, col(colorRes)))
                        layoutParams = LinearLayout.LayoutParams(dp(16), dp(16))
                        contentDescription = desc
                    })
                }
            // Pause — the accent purple of the app's "Sending…" presentation;
            // pauses only the selected transfers that are genuinely pausable
            // (never a streamed zip, never an already-paused one).
            val pause = iconOnlySegment(R.drawable.ic_pause, R.color.accent, "Pause selected")
            touchIconOnly(pause) { bulkPauseSelected() }
            // Resume — the orange the Pause action is associated with; resumes
            // only the selected paused transfers. It can never pause a running
            // one (§9.5).
            val resume = iconOnlySegment(R.drawable.ic_play, R.color.orange, "Resume selected")
            touchIconOnly(resume) { bulkResumeSelected() }
            // Cancel — the app's red, the recognizable X/stop glyph; cancels
            // the selected applicable transfers through the same cancellation
            // system the per-row and whole-folder buttons use.
            val cancelAction = iconOnlySegment(R.drawable.ic_close, R.color.red_stop, "Cancel selected")
            touchIconOnly(cancelAction) { bulkCancelSelected() }
            pill.addView(pause)
            pill.addView(separator())
            pill.addView(resume)
            pill.addView(separator())
            pill.addView(cancelAction)
        } else {
            val delete = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(segmentIcon(R.drawable.ic_delete, R.color.red_stop))
                addView(segmentLabel("Delete", R.color.red_stop, bold = true))
            }
            touch(delete) { if (tab == "sent") confirmDeleteSentSelection() else confirmDeleteSelected() }
            val selectAll = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(segmentIcon(R.drawable.ic_select_all, R.color.accent_light))
                addView(segmentLabel("Select All", R.color.accent_light))
            }
            touch(selectAll) { toggleSelectAllForTab(tab) }
            pill.addView(delete)
            pill.addView(separator())
            pill.addView(selectAll)
        }

        // ── GOAT-PASS 15 (§9.3 part 5 / §9.4): the Close glyph — the one
        //  shared selection-exit affordance across Active, Received and
        //  History: icon-only, minimalist, muted (visually distinct from the
        //  red Cancel), always the LAST segment at the toolbar's far right.
        //  Active: Close exits selection mode; Cancel cancels transfers —
        //  the two can never be confused.
        val closeSegment = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(ctx).apply {
                setImageDrawable(Widgets.icon(ctx, R.drawable.ic_close, col(R.color.text_muted)))
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14))
                contentDescription = "Close selection"
            })
        }
        touch(closeSegment) {
            when (tab) {
                "sent" -> exitSentSelection()
                "active" -> exitActiveSelection()
                else -> exitReceivedSelection()
            }
        }
        pill.addView(separator())
        pill.addView(closeSegment)

        // ── GOAT-PASS 11 (§10) + GOAT-PASS 12 (§2): the RECEIVED/HISTORY pill's
        // selected-item counter, and ── GOAT-PASS 15 (§9.3 part 1): ── the
        // ACTIVE toolbar's identical chip — a small, minimalist, NUMBER-ONLY
        // capsule at the pill's leading edge, patched in place on every
        // selection mutation by the tab's refresh*Header. One counter design
        // across all three selection-capable tabs.
        if (tab == "received" || tab == "sent" || tab == "active") {
            val count = TextView(ctx).apply {
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(col(R.color.accent_light))
                gravity = Gravity.CENTER
                maxLines = 1
                minWidth = dp(22)
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(11).toFloat()
                    setColor((col(R.color.accent) and 0x00FFFFFF) or 0x26000000)
                }
                setPadding(dp(7), dp(3), dp(7), dp(3))
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            }
            when (tab) {
                "received" -> receivedSelCountLabel = count
                "sent" -> sentSelCountLabel = count
                "active" -> activeSelCountLabel = count
            }
            // The counter LEADS the pill (left of every action in every locale
            // the pill itself lays out left-to-right): it is the mode's
            // headline fact, and the buttons keep their exact order.
            pill.addView(count, 0)
            pill.addView(separator(), 1)
        } else {
            receivedSelCountLabel = null
            sentSelCountLabel = null
        }
        return pill
    }

    // ── GOAT-PASS 15 (§9.3/§9.5): the ACTIVE toolbar's bulk actions ────────────

    /** Resolves the current Active selection to the live Transfer rows it
     *  refers to (stable row keys — a key whose row completed or vanished
     *  between taps is dropped by renderActiveTab's retainAll, and a missing
     *  key is simply skipped here). */
    private fun selectedActiveTransfers(): List<TransferManager.Transfer> {
        val out = ArrayList<TransferManager.Transfer>()
        for (item in lastActiveRows) {
            if (item.rowKey() !in activeSelectionKeys) continue
            when (item) {
                is TransferManager.TransferRowItem.Solo -> out.add(item.transfer)
                is TransferManager.TransferRowItem.Group ->
                    out.addAll(item.members.filter { it.status == Status.PENDING || it.status == Status.ACTIVE })
            }
        }
        return out
    }

    /** Pause: only genuinely pausable rows — live, not already paused, and
     *  never a streamed zip archive (§3.4's never-pausable marker). Honest
     *  feedback distinguishes paused / already paused / not pausable. */
    private fun bulkPauseSelected() {
        if (activeSelectionKeys.isEmpty()) return
        val targets = selectedActiveTransfers()
            .filter { (it.status == Status.PENDING || it.status == Status.ACTIVE) && !it.pauseRequested && !it.isZipStream }
        val alreadyPaused = selectedActiveTransfers().count { it.pauseRequested }
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(),
                if (alreadyPaused > 0) "Already paused" else "Nothing to pause",
                Toast.LENGTH_SHORT).show()
            return
        }
        for (t in targets) {
            TransferManager.requestPause(t.id)
            propagateIfNative(t, "pause")
        }
        renderCurrentTab()
        Toast.makeText(requireContext(), when {
            alreadyPaused > 0 -> "Paused ${targets.size} · $alreadyPaused already paused"
            else -> "Paused ${targets.size}"
        }, Toast.LENGTH_SHORT).show()
    }

    /** Resume: only genuinely paused rows; running rows are never touched
     *  (§9.5 — Resume must never pause anything). A paused native send's
     *  parked dispatch leg is relaunched exactly like the per-row Resume. */
    private fun bulkResumeSelected() {
        if (activeSelectionKeys.isEmpty()) return
        val targets = selectedActiveTransfers()
            .filter { it.pauseRequested && (it.status == Status.PENDING || it.status == Status.ACTIVE) }
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), "Nothing to resume", Toast.LENGTH_SHORT).show()
            return
        }
        for (t in targets) {
            TransferManager.requestResume(t.id)
            propagateIfNative(t, "resume")
            // Pass-16 (resume-one-of-all-paused, sender half): a paused
            // app-to-app send YIELDED its dispatch permit — relaunch it.
            if (t.direction == Direction.SEND && t.source == Source.NATIVE_PUSH) {
                NativePushClient.resumeFile(t.id)
            }
        }
        renderCurrentTab()
        Toast.makeText(requireContext(), "Resumed ${targets.size}", Toast.LENGTH_SHORT).show()
    }

    /** Cancel: every selected applicable transfer through the established
     *  cancellation system — the per-row propagation contract for solos
     *  (single-file cancel for native sends, the Quick-Share withdrawal for
     *  its rows, the ordinary native propagation otherwise) and the
     *  whole-folder ONE-request teardown for groups, exactly like their own
     *  buttons. No duplicate requests: a group is cancelled as a unit, never
     *  also per-member (§9.5). */
    private fun bulkCancelSelected() {
        if (activeSelectionKeys.isEmpty()) return
        var soloCount = 0
        var groupCount = 0
        val appCtx = requireContext().applicationContext
        // ── FINAL PASS 1 (§7.5): the shared cleanup for a selected "Auto- ──
        // Resume on Reconnection" row — the exact same lifecycle the bulk
        // menu's Cancel All runs for these rows (performClearPartialReceived
        // pass 1): resolve the row through the authoritative cancellation
        // system, stop its resume story for good (staging partial + identity
        // sidecar + durable registry entry deleted), and leave the discard
        // receipt that makes the sender ASK before ever re-sending. A
        // cancelled row can never resurrect: the durable tombstone refuses
        // the sender's in-flight re-POST and its next probe answers
        // "cancelled", so no stale reconnect callback can restart it.
        fun cancelKeptForResume(t: TransferManager.Transfer) {
            try {
                TransferManager.requestCancel(t.id)
                t.filePath?.let { path ->
                    com.lumidrop.x.transfer.ResumableReceiveStore.deleteStaging(path)
                    com.lumidrop.x.transfer.ResumableReceiveStore.remove(appCtx, path)
                }
                try { server?.rememberDiscardedReceive(t.uploadRequestId, t.filePath) } catch (e: Exception) { /* no live sender to inform */ }
                TransferManager.dismissResumable(t.id)
            } catch (e: Exception) { /* the row still resolved via requestCancel above */ }
        }
        for (item in lastActiveRows) {
            if (item.rowKey() !in activeSelectionKeys) continue
            when (item) {
                is TransferManager.TransferRowItem.Solo -> {
                    val t = item.transfer
                    // FINAL PASS 1 (§7.5): a kept-for-resume recovery card is
                    // cancellable here too — its cleanup runs through the same
                    // discard lifecycle Cancel All uses.
                    if (t.status == Status.FAILED && t.interrupted) {
                        cancelKeptForResume(t)
                        soloCount++
                        continue
                    }
                    if (t.status != Status.PENDING && t.status != Status.ACTIVE) continue
                    TransferManager.requestCancel(t.id)
                    when {
                        t.source == Source.NATIVE_PUSH && t.direction == Direction.SEND ->
                            NativePushClient.propagateSingleFileCancel(t.id)
                        t.source == Source.QUICK_SHARE && !t.qsItemName.isNullOrEmpty() ->
                            server?.cancelQuickShareForPeer(t.qsItemName, t.peerIp)
                        else -> propagateIfNative(t, "cancel")
                    }
                    soloCount++
                }
                is TransferManager.TransferRowItem.Group -> {
                    // FINAL PASS 1 (§7.5): interrupted kept-for-resume members
                    // are part of the folder's cancellable set — resolved
                    // through the shared discard lifecycle, not skipped.
                    item.members.filter { it.status == Status.FAILED && it.interrupted }
                        .forEach { cancelKeptForResume(it) }
                    val liveMembers = item.members.filter { it.status == Status.PENDING || it.status == Status.ACTIVE }
                    if (liveMembers.isEmpty()) {
                        if (item.members.any { it.status == Status.FAILED && it.interrupted }) groupCount++
                        continue
                    }
                    // GOAT-PASS 11 (§7): the whole-folder cancel is ONE
                    // operation — the local rows resolve in one bulk pass and
                    // the peer learns through exactly ONE scope=push request.
                    TransferManager.requestCancelBulk(liveMembers.map { it.id })
                    val first = item.members.firstOrNull()
                    if (first?.source == Source.NATIVE_PUSH) {
                        if (first.direction == Direction.SEND) {
                            NativePushClient.propagateGroupCancel(item.info.id)
                        } else {
                            val resolver = liveMembers.firstOrNull() ?: item.members.firstOrNull()
                            resolver?.let { m ->
                                server?.cancelIncomingPushFromUi(m.id)
                                server?.propagateNativeGroupControl(m.id, "cancel")
                            }
                        }
                    }
                    groupCount++
                }
            }
        }
        if (soloCount + groupCount == 0) {
            Toast.makeText(requireContext(), "Nothing to cancel", Toast.LENGTH_SHORT).show()
            return
        }
        renderCurrentTab()
        val label = buildString {
            append("Cancelled $soloCount item")
            if (soloCount != 1) append('s')
            if (groupCount > 0) {
                append(" and $groupCount folder")
                if (groupCount != 1) append('s')
            }
        }
        Toast.makeText(requireContext(), label, Toast.LENGTH_SHORT).show()
    }

    /** ── GOAT-PASS 8 (§2 — Select All): selects every ELIGIBLE row in the ─────
     *  current tab (the same isRangeSelectable gate every selection entry
     *  uses — only rows the Delete action can actually honor may join), or
     *  deselects exactly those rows when they are all selected already. The
     *  count label and the rows refresh immediately; the selection's other
     *  invariants (the range anchor, the mode's committed state) are
     *  untouched. */
    private fun toggleSelectAllForTab(tab: String) {
        val rows = rowsFor(tab)
        val eligible = rows.filter { isRangeSelectableIn(tab, it) }
        if (eligible.isEmpty()) return
        val keys = selectionKeysFor(tab)
        val allSelected = eligible.all { it.rowKey() in keys }
        if (allSelected) {
            for (item in eligible) keys.remove(item.rowKey())
        } else {
            for (item in eligible) keys.add(item.rowKey())
        }
        repaintSelection(tab)
    }

    /** One confirmation for the whole selected set. Files delete directly;
     *  folder groups delete recursively via the same folderDeletionTarget
     *  resolution the single-folder action uses. */
    private fun confirmDeleteSelected() {
        if (receivedSelectionKeys.isEmpty()) return
        val ctx = requireContext()
        val keys = receivedSelectionKeys.toList()
        showLdxDialog(
            context = ctx,
            title = "Delete ${keys.size} selected item${if (keys.size != 1) "s" else ""}?",
            message = "Selected files and whole folders will be permanently deleted. This can't be undone.",
            iconRes = R.drawable.ic_delete,
            iconTintRes = R.color.red_stop,
            actions = listOf(
                LdxDialogAction("Cancel", LdxButtonStyle.GHOST),
                LdxDialogAction("Delete", LdxButtonStyle.DESTRUCTIVE) {
                    // ── GOAT-PASS 11 (§11 — same root cause as the History ───────────────
                    // tab's bulk-delete fix): the complete selected set is
                    // captured as ITEM REFERENCES on the UI thread, BEFORE
                    // the file-deleting IO loop starts. The old loop
                    // re-looked each key up in lastReceivedRows while the
                    // ~2s reconcile kept replacing that list — and this
                    // loop is SLOW (real file deletions), so it crossed
                    // several render ticks mid-flight; group rows re-folded
                    // and member sets shrank as earlier items resolved, and
                    // every later key that missed took the silent null
                    // branch. The captured references can never skip.
                    val items = keys.mapNotNull { k ->
                        lastReceivedRows.firstOrNull { it.rowKey() == k }
                    }
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        var deleted = 0
                        // Pass-33 (#4): the provenance verdict re-checked at delete
                        // time — only paths LumiDrop X received may leave the disk,
                        // and only provenance-cleared rows count toward the total.
                        fun proven(p: String?): Boolean = p != null &&
                            try { com.lumidrop.x.transfer.ReceivedRegistry.isAppReceived(ctx, p) } catch (e: Exception) { false }
                        for (item in items) {
                            when (item) {
                                is TransferManager.TransferRowItem.Solo -> {
                                    val p = item.transfer.filePath
                                    val ok = proven(p) && try { File(p!!).delete() } catch (e: Exception) { false }
                                    if (ok) deleted++
                                    if (proven(p)) try { com.lumidrop.x.transfer.ReceivedRegistry.forgetTree(ctx, p!!) } catch (e2: Exception) {}
                                    TransferManager.dismiss(item.transfer.id)
                                }
                                is TransferManager.TransferRowItem.Group -> {
                                    val target = folderDeletionTarget(item.info, item.members)
                                    val targetProven = target != null && proven(target.absolutePath)
                                    val ok = if (target != null && targetProven && target.exists()) {
                                        try { target.deleteRecursively() } catch (e: Exception) { false }
                                    } else {
                                        // No proven single directory (or the target itself is
                                        // foreign): fall back to deleting the PROVEN member
                                        // files individually — a foreign folder dropped around
                                        // proven files is never touched.
                                        item.members.all { m ->
                                            val p = m.filePath ?: return@all true
                                            proven(p) && try { File(p).delete() } catch (e: Exception) { false }
                                        }
                                    }
                                    if (ok) deleted++
                                    if (targetProven && target != null)
                                        try { com.lumidrop.x.transfer.ReceivedRegistry.forgetTree(ctx, target.absolutePath) } catch (e2: Exception) {}
                                    else item.members.forEach { m ->
                                        if (proven(m.filePath)) try { com.lumidrop.x.transfer.ReceivedRegistry.forgetTree(ctx, m.filePath!!) } catch (e3: Exception) {} }
                                    item.members.forEach { TransferManager.dismiss(it.id) }
                                }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            if (view == null) return@withContext
                            receivedSelectionMode = false
                            receivedSelectionKeys.clear()
                            lastReceivedScanAtMs = 0L
                            // ── GOAT-PASS 8 (§10): the received files just ──
                            // deleted on disk are exactly the listings the Files
                            // sheet's process-level cache may hold — invalidate
                            // the receive folder (and its parent, whose cached
                            // row for it carries the child count) so the other
                            // surface can never re-serve a deleted entry or a
                            // stale folder count.
                            try {
                                val recvRoot = File(com.lumidrop.x.settings.SettingsManager.getUploadPath(ctx))
                                com.lumidrop.x.ui.invalidateFilesSheetCacheFor(recvRoot)
                            } catch (e: Exception) { /* cross-surface hygiene only */ }
                            Toast.makeText(ctx, "Deleted $deleted item${if (deleted != 1) "s" else ""}", Toast.LENGTH_SHORT).show()
                            renderCurrentTab()
                            refreshReceivedHeader()
                        }
                    }
                },
            ),
        )
    }

    // ── Pass-28 (#1.3): the History tab's header ─────────────────────────────
    // Two faces, exactly like the Received tab's: the normal one carries the
    // LIVE TOTAL ITEM COUNT in the old instructional text's place ("Tap a
    // file to open it" is gone for good — removed as clutter), with Clear
    // History right-aligned; selection mode swaps the whole row for the same
    // "N selected · Cancel / Delete Selected" action bar Received uses.
    // Built with the same churn discipline as the list: the views are only
    // rebuilt when the mode actually flips, and the count label is text-
    // patched afterwards, so the 500ms render tick costs one string compare.
    private fun refreshSentHeader() {
        if (!::sentHeaderRow.isInitialized) return
        val ctx = requireContext()
        val header = sentHeaderRow
        val selection = sentSelectionMode
        if (selection != sentHeaderIsSelection) {
            header.removeAllViews()
            sentHeaderCountLabel = TextView(ctx).apply {
                textSize = 11.5f
                setTextColor(col(R.color.text_secondary))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            header.addView(sentHeaderCountLabel)
            if (selection) {
                // ── GOAT-PASS 9 (§6): the items counter is HIDDEN while ──
                // selection mode is active — the selection action pill owns
                // the row (same treatment as the Received tab).
                sentHeaderCountLabel?.visibility = View.GONE
                header.addView(buildSelectionActionBar("sent"))
            } else {
                sentHeaderCountLabel?.visibility = View.VISIBLE
                // ── Pass-40 (#4): the SAME compact sort icon the Received ──
                // tab carries (see refreshReceivedHeader), placed immediately
                // RIGHT of the Clear pill with balanced spacing — one
                // consistent sorting design across both tabs, and the count
                // label keeps the whole flexible width it always had.
                header.addView(smallActionBtn("Clear History", null, R.color.text_muted) { clearHistoryNow() })
                header.addView(sortIconBtn { btn -> showTransfersSortMenu(btn, "sent") })
            }
            sentHeaderIsSelection = selection
        }
        // The live figure, patched in place — only in the normal face (the
        // label is hidden for the whole selection session, §6).
        // renderHistoryList calls this on every render, so additions,
        // deletions and Clear History are all reflected immediately.
        if (!selection) {
            sentHeaderCountLabel?.text = "${lastSentRows.size} item${if (lastSentRows.size != 1) "s" else ""}"
            sentSelCountLabel = null   // ── GOAT-PASS 12 (§2): the chip left with the selection pill
        } else {
            // ── GOAT-PASS 12 (§2): the History pill's number-only counter — ──
            // the same live patch the Received tab's chip gets, on EVERY
            // selection mutation (toggles, ranges, drag passes, Select All,
            // deselections): refreshSentHeader is the funnel point every
            // selection change already re-renders through, so the number
            // always matches the actual selected-item collection, and it
            // resets with the mode itself (the label is nulled the moment
            // the header flips back to its normal face above).
            sentSelCountLabel?.text = "${sentSelectionKeys.size}"
        }
    }

    /** ── GOAT-PASS 12 (§3A/§3C/§3D): the ONE History record-deletion ──────────
     *  primitive every path uses (per-row ✕, bulk selection delete, folder
     *  member removal). A row id here is either a store-backed
     *  "hist:<storeId>" or a live engine "<id>" — but the SAME transfer id
     *  keys BOTH layers, so both are cleaned in one step:
     *    • the ENGINE row is dismissed (it is only ever a view of a
     *      completed transfer here — never a live one History can show), and
     *    • the STORE record is removed AND tombstoned, so no merge, no late
     *      completion hook, no restart migration can resurrect it.
     *  Files on disk are never touched: History is a LOG, not a view of the
     *  Received directory (Pass-17), and this deletion stays entirely
     *  inside that log's own two layers. (Deleted GROUP rows tombstone the
     *  folder id separately, at their call site, via
     *  HistoryStore.GROUP_TOMB_PREFIX — see confirmDeleteSentSelection.) */
    private fun deleteHistoryRecord(rowId: String) {
        val cx = context ?: return
        val rawId = rowId.removePrefix("hist:")
        try { TransferManager.dismiss(rawId) } catch (e: Exception) { /* engine row may already be gone */ }
        com.lumidrop.x.transfer.HistoryStore.removeAll(cx, listOf(rawId))
    }

    /** Pass-28 (#1.1): the History tab's own bulk "Clear History" action —
     *  the exact store + engine clears the old header button performed,
     *  kept as its own function so the count and the list refresh together.
     *  ── GOAT-PASS 12 (§3B): Clear History now goes through the EXACT ──
     *  confirmation modal the selection-delete path uses (same component,
     *  same design language, same Cancel arrangement — one confirmation
     *  system, not two), with a double-execution guard: the action is
     *  single-shot per dialog (the dialog dismisses on tap) and an
     *  in-flight latch keeps a second dialog's Confirm from racing the
     *  first while its renders settle. */
    private var clearHistoryInFlight = false
    private fun clearHistoryNow() {
        if (clearHistoryInFlight) return
        val ctx = requireContext()
        showLdxDialog(
            context = ctx,
            title = "Clear history?",
            message = "All ${lastSentRows.size} history record${if (lastSentRows.size != 1) "s" else ""} will be removed. Only the history records are cleared — your files themselves are not deleted.",
            iconRes = R.drawable.ic_delete,
            iconTintRes = R.color.red_stop,
            actions = listOf(
                LdxDialogAction("Cancel", LdxButtonStyle.GHOST),
                LdxDialogAction("Clear", LdxButtonStyle.DESTRUCTIVE) {
                    if (clearHistoryInFlight) return@LdxDialogAction
                    clearHistoryInFlight = true
                    try {
                        HistoryStore.clear(ctx)
                        TransferManager.clearCompletedGroup(received = true)
                        TransferManager.clearCompletedGroup(received = false)
                        if (sentSelectionMode) exitSentSelection()
                        renderHistoryTab()
                        refreshSentHeader()
                    } finally {
                        clearHistoryInFlight = false
                    }
                },
            ),
        )
    }

    /** Pass-28 (#1.1): one confirmation for the History tab's selected set.
     *  Deletion is RECORD removal — the exact operation the tab's per-row ✕
     *  performs — so no file on disk is ever touched. Groups remove every
     *  member's record.
     *  ── GOAT-PASS 12 (§3A): every member now goes through ──
     *  deleteHistoryRecord — BOTH layers (engine row + store record with its
     *  tombstone) in one primitive, and each deleted GROUP row additionally
     *  tombstones the folder itself, so no member can re-fold it. */
    private fun confirmDeleteSentSelection() {
        if (sentSelectionKeys.isEmpty()) return
        val ctx = requireContext()
        val keys = sentSelectionKeys.toList()
        showLdxDialog(
            context = ctx,
            title = "Remove ${keys.size} selected item${if (keys.size != 1) "s" else ""} from history?",
            message = "Only the history records are removed. Your files themselves are not deleted.",
            iconRes = R.drawable.ic_delete,
            iconTintRes = R.color.red_stop,
            actions = listOf(
                LdxDialogAction("Cancel", LdxButtonStyle.GHOST),
                LdxDialogAction("Remove", LdxButtonStyle.DESTRUCTIVE) {
                    // ── GOAT-PASS 11 (§11 — the complete selected set is ────────────────────
                    // captured BEFORE any deletion begins). The old loop
                    // re-looked EVERY key up in lastSentRows from inside the
                    // IO coroutine while the ~500ms render loop kept
                    // replacing that list — and the deletion itself drove
                    // those renders (each engine dismiss touches the peer
                    // clock; each store removal changes the next fold), so
                    // the list the Nth key was searched in no longer held
                    // rows that were present when the FIRST key processed.
                    // Group rows re-folded (their member sets shrank as
                    // members were dismissed), live rows dropped out, and
                    // every mid-loop miss silently took the null branch —
                    // exactly the reported "selected 96, only 35 removed,
                    // the remaining 61 still there" (the second attempt,
                    // starting from the now-stable list, found them all).
                    // The items are captured ONCE, on the UI thread, before
                    // the coroutine launches; the deletion iterates THOSE
                    // immutable references and can never skip one.
                    val items = keys.mapNotNull { k ->
                        lastSentRows.firstOrNull { it.rowKey() == k }
                    }
                    val unresolvable = keys.size - items.size
                    viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
                        var removed = 0
                        for (item in items) {
                            when (item) {
                                is TransferManager.TransferRowItem.Solo -> {
                                    deleteHistoryRecord(item.transfer.id)
                                    removed++
                                }
                                is TransferManager.TransferRowItem.Group -> {
                                    // ── GOAT-PASS 12 (§3E): every member's record ──
                                    // goes through the same two-layer deletion,
                                    // and the FOLDER itself is tombstoned — a
                                    // straggler file completing after this
                                    // deletion can never re-fold the deleted
                                    // folder into a fresh History row. (The
                                    // "hist:" prefix store-folded group ids
                                    // carry is stripped — the store's groupId
                                    // is the raw engine group id.)
                                    item.members.forEach { deleteHistoryRecord(it.id) }
                                    context?.let { cx ->
                                        HistoryStore.removeAll(cx, listOf(
                                            HistoryStore.GROUP_TOMB_PREFIX + item.info.id.removePrefix("hist:")))
                                    }
                                    removed++
                                }
                            }
                        }
                        withContext(Dispatchers.Main) {
                            if (view == null) return@withContext
                            sentSelectionMode = false
                            sentSelectionKeys.clear()
                            // Honest reporting: an item that could not be
                            // resolved even at CAPTURE time (a row that
                            // genuinely vanished between selection and the
                            // delete tap) is reported instead of silently
                            // claimed.
                            val msg = if (unresolvable > 0)
                                "Removed $removed item${if (removed != 1) "s" else ""} ($unresolvable already gone)"
                            else
                                "Removed $removed item${if (removed != 1) "s" else ""}"
                            Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                            renderHistoryTab()
                            refreshSentHeader()
                        }
                    }
                },
            ),
        )
    }

    // ── Pass-28 (#1.2): the container-based selection visual ─────────────────
    // The old 22dp checkmark circle is retired. The ENTIRE row container now
    // communicates the selection state, in BOTH selection-capable tabs, with
    // the same restrained, theme-aware treatment:
    //   • selected            → quiet accent-tinted fill over the row's own
    //                           surface color + a 1dp accent-dark border —
    //                           unmistakable at a glance, no icon required;
    //   • unselected (in mode) → the row's normal appearance, untouched;
    //   • outside selection   → the original bg_btn_secondary ripple.
    // The state rides the SAME DiffUtil snapshot fields the checkmark used,
    // so toggling rebinds exactly the affected row. Transitions between the
    // two states of the SAME row animate as a short 170ms color blend
    // (fill + border together); a recycled card bound to a different row, or
    // a first-ever paint, applies instantly — never animating from a stale
    // tint. The per-card state (and the in-flight animator) live in a keyed
    // tag so the plain tag keeps carrying the row id.
    private class SelVisualState {
        var applied = 0            // 0 = normal, 1 = selection-mode unselected, 2 = selected
        var fill = 0
        var stroke = 0
        var animator: android.animation.ValueAnimator? = null
    }

    /** Straight-line argb blend — [t] 0f..1f from [from] toward [to]. */
    private fun blendCol(from: Int, to: Int, t: Float): Int {
        val cl = t.coerceIn(0f, 1f)
        fun ch(f: Int, s: Int): Int = f + ((s - f) * cl).toInt()
        return android.graphics.Color.argb(
            ch(android.graphics.Color.alpha(from), android.graphics.Color.alpha(to)),
            ch(android.graphics.Color.red(from), android.graphics.Color.red(to)),
            ch(android.graphics.Color.green(from), android.graphics.Color.green(to)),
            ch(android.graphics.Color.blue(from), android.graphics.Color.blue(to)),
        )
    }

    private fun applySelectionVisual(card: LinearLayout, active: Boolean, selected: Boolean, sameRow: Boolean) {
        val want = if (!active) 0 else if (selected) 2 else 1
        @Suppress("UNCHECKED_CAST")
        var st = card.getTag(R.id.sel_visual) as? SelVisualState
        if (st == null) {
            st = SelVisualState()
            card.setTag(R.id.sel_visual, st)
        }
        if (st.applied == want) return
        st.animator?.cancel()
        st.animator = null
        if (want == 0) {
            // Outside selection mode: the row's original ripple background,
            // byte-identical to the non-selecting appearance.
            card.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_secondary)
            st.applied = 0
            st.fill = 0; st.stroke = 0
            return
        }
        val radius = resources.getDimensionPixelSize(R.dimen.radius_md).toFloat()
        val normalFill = col(R.color.bg_secondary)
        val selectedFill = blendCol(normalFill, col(R.color.accent), 0.22f)
        val normalStroke = col(R.color.border_subtle)
        val selectedStroke = col(R.color.accent_dark)
        val targetFill = if (want == 2) selectedFill else normalFill
        val targetStroke = if (want == 2) selectedStroke else normalStroke
        // The content shape starts at the TARGET state; a same-row transition
        // animates the colors from what the card currently shows. (The locals
        // are deliberately named selShape/selMask — a plain `shape`/`mask`
        // local would shadow the GradientDrawable receivers' own properties
        // inside the apply blocks below.)
        val startFill = if (sameRow && st.fill != 0) st.fill else targetFill
        val startStroke = if (sameRow && st.fill != 0) st.stroke else targetStroke
        val selShape = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(startFill)
            setStroke(dp(1), startStroke)
        }
        val selMask = GradientDrawable().apply {
            this.shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(android.graphics.Color.WHITE)
        }
        card.background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(col(R.color.overlay_press)), selShape, selMask)
        st.applied = want
        st.fill = targetFill
        st.stroke = targetStroke
        if (startFill != targetFill || startStroke != targetStroke) {
            val anim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 170
                addUpdateListener { a ->
                    val f = a.animatedValue as Float
                    selShape.setColor(blendCol(startFill, targetFill, f))
                    selShape.setStroke(dp(1), blendCol(startStroke, targetStroke, f))
                }
            }
            anim.start()
            st.animator = anim
        }
    }

    /** Final Stabilization (§6): the compact bulk-transfer action menu.
     *  One BottomSheet, three rows, state-aware availability — Pause All
     *  only when something is running, Resume All only when something is
     *  paused, Cancel All (visually separated as the destructive action)
     *  only when anything can still be stopped. Every row executes the
     *  exact logic the old full-width buttons ran, including the
     *  native-push propagation and Cancel All's confirmation dialog; the
     *  sheet closes itself on tap so a double-tap cannot double-fire. */
    private fun showBulkTransferMenu() {
        val ctx = requireContext()
        val snapshot = TransferManager.activeSnapshot()
        val live = snapshot.filter { it.status == Status.PENDING || it.status == Status.ACTIVE }
        val pausable = live.any { !it.pauseRequested }
        val resumable = snapshot.any { it.status == Status.PENDING || it.status == Status.ACTIVE } &&
                        snapshot.any { it.pauseRequested }
        // ── FINAL PASS 1 (§7.2): "Auto-Resume on Reconnection" items are ──
        // incomplete operations that MUST be cancellable — an Active tab
        // holding only such rows used to grey out Cancel All (they are not
        // PENDING/ACTIVE), leaving uncancellable work on screen. The button
        // stays enabled whenever there is anything it can genuinely stop:
        // live work OR the receiver's auto-resume recovery cards (the same
        // population autoResumeReceiverRows() defines — both phases of the
        // state). The action itself already routes those rows through the
        // full cancellation lifecycle via performClearPartialReceived.
        val anyStoppable = live.isNotEmpty() || TransferManager.autoResumeReceiverRows().isNotEmpty()

        fun actionRow(label: String, sub: String, iconRes: Int, tintRes: Int, enabled: Boolean, onClick: () -> Unit): LinearLayout {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                alpha = if (enabled) 1f else 0.42f
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_icon_ripple)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            }
            row.addView(ImageView(ctx).apply {
                setImageDrawable(Widgets.icon(ctx, iconRes, col(tintRes)))
                layoutParams = LinearLayout.LayoutParams(dp(24), dp(24)).also { it.marginEnd = dp(14) }
            })
            row.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                addView(TextView(ctx).apply {
                    text = label; textSize = 14.5f; setTextColor(col(tintRes))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                addView(TextView(ctx).apply {
                    text = sub; textSize = 11.5f; setTextColor(col(R.color.text_muted))
                    layoutParams = lp(MATCH, WRAP).also { it.topMargin = dp(2) }
                })
            })
            if (enabled) row.setOnClickListener {
                bulkMenuSheet?.dismiss()
                onClick()
            } else row.setOnClickListener(null)
            return row
        }

        bulkMenuSheet = BottomSheet(ctx)
        bulkMenuSheet?.show { _ ->
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(18), 0, dp(18), dp(14))
            }
            root.addView(TextView(ctx).apply {
                text = "Manage transfers"
                textSize = 15f; setTextColor(col(R.color.text_primary))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                layoutParams = lp(MATCH, WRAP).also { it.bottomMargin = dp(4) }
            })
            root.addView(TextView(ctx).apply {
                val n = live.size
                text = if (n == 1) "1 transfer in progress" else "$n transfers in progress"
                textSize = 12f; setTextColor(col(R.color.text_muted))
                layoutParams = lp(MATCH, WRAP).also { it.bottomMargin = dp(8) }
            })
            // Pass-19: nativesOf() (the sheet-open `live` snapshot) is retired —
            // both bulk actions re-snapshot at TAP time so rows that appeared
            // after the sheet opened are covered too.
            root.addView(actionRow("Pause all", "Pause every waiting and in-progress transfer",
                R.drawable.ic_pause, R.color.text_primary, pausable) {
                // ── Pass-19: re-snapshot at TAP time (the sheet's own `live`
                // list was built when it opened) and propagate every native
                // row's pause — waiting rows included, which the receiver
                // now marks on its mirror rows too, so BOTH devices show
                // every item Paused.
                val pauseSnapshot = TransferManager.activeSnapshot()
                    .filter { it.status == Status.PENDING || it.status == Status.ACTIVE }
                pauseSnapshot.filter { it.source == Source.NATIVE_PUSH }.forEach { propagateIfNative(it, "pause") }
                TransferManager.pauseAllActive()
                renderCurrentTab()
            })
            root.addView(actionRow("Resume all", "Resume every paused transfer",
                R.drawable.ic_play, R.color.accent_light, resumable) {
                // ── Pass-19 (Resume All relaunches parked sends) ─────────────────
                // Re-snapshot NOW (the sheet was built when it opened; rows may
                // have changed state since) and resolve every still-paused row
                // BEFORE the flags are cleared below. A paused native SEND
                // parked its dispatch coroutine in NativePushClient.pausedSends
                // (the pass-16 pause-yield); clearing the pause flag alone
                // (resumeAllActive) leaves it parked forever — the exact
                // single-row deadlock the per-row and group Resume buttons
                // already fixed, which the BULK action used to recreate for
                // every item it touched. resumeFile() is a no-op for anything
                // not parked (still-queued rows get picked up by the
                // dispatcher's next tick once their pause flag lifts), and the
                // receiver-side "resume" propagation below now reaches WAITING
                // rows too, so incoming paused pushes resume on the SENDER
                // just as completely as outgoing ones resume here.
                val pausedSnapshot = TransferManager.activeSnapshot()
                    .filter { it.pauseRequested && (it.status == Status.PENDING || it.status == Status.ACTIVE) }
                pausedSnapshot.filter { it.source == Source.NATIVE_PUSH }.forEach { propagateIfNative(it, "resume") }
                pausedSnapshot.filter { it.direction == Direction.SEND && it.source == Source.NATIVE_PUSH }
                    .forEach { NativePushClient.resumeFile(it.id) }
                TransferManager.resumeAllActive()
                renderCurrentTab()
            })
            // Destructive action: separated by a hairline and red-tinted,
            // and it still routes through the same confirmation dialog the
            // old button used — the sheet closes first so the dialog is the
            // only thing on screen.
            root.addView(View(ctx).apply {
                setBackgroundColor(col(R.color.border_subtle))
                layoutParams = lp(MATCH, 1).also { it.topMargin = dp(8); it.bottomMargin = dp(8) }
            })
            root.addView(actionRow("Cancel all", "Stop every waiting and in-progress transfer — can't be undone",
                R.drawable.ic_close, R.color.red_stop, anyStoppable) {
                showLdxDialog(
                    context = requireContext(),
                    title = "Cancel all transfers?",
                    message = "Every waiting and in-progress transfer will be stopped. This can't be undone.",
                    iconRes = R.drawable.ic_stop,
                    iconTintRes = R.color.red_stop,
                    actions = listOf(
                        LdxDialogAction("Keep going", LdxButtonStyle.GHOST),
                        LdxDialogAction("Cancel All", LdxButtonStyle.DESTRUCTIVE) {
                            val natives = TransferManager.activeSnapshot().filter {
                                it.source == Source.NATIVE_PUSH && (it.status == Status.PENDING || it.status == Status.ACTIVE)
                            }
                            // Pass-6 (R-H): Cancel-all propagates the same way
                            // the per-row cancel now does — undecided Quick-
                            // Share offers are withdrawn for exactly the
                            // clients whose rows are being cancelled, and
                            // native sends get the whole-push teardown.
                            val qsRows = TransferManager.activeSnapshot().filter {
                                it.source == Source.QUICK_SHARE && it.direction == Direction.DOWNLOAD &&
                                    !it.qsItemName.isNullOrEmpty() && (it.status == Status.PENDING || it.status == Status.ACTIVE)
                            }
                            // Pass-8 (§13 — receiver-side Cancel All must stop
                            // the SENDERS too): every incoming native push with
                            // live work gets its whole batch cancelled on BOTH
                            // sides — locally (rows resolved, staging partials
                            // deleted, decisions closed) and on the sender (one
                            // scope=push POST per session, which stops its
                            // dispatch loop). This runs BEFORE the local
                            // cancel-all so the push records are still present
                            // to resolve through, and it is what makes "queued
                            // files never start, no further files arrive" true —
                            // previously only the currently-streaming file died
                            // and the sender kept delivering the rest.
                            server?.cancelAllIncomingNativePushes()
                            TransferManager.cancelAllActive()
                            natives.forEach { propagateIfNative(it, "cancel") }
                            natives.filter { it.direction == Direction.SEND }.forEach { NativePushClient.propagateCancelForTid(it.id) }
                            qsRows.forEach { server?.cancelQuickShareForPeer(it.qsItemName!!, it.peerIp) }
                            renderCurrentTab()
                            // ── GOAT-PASS 15 (§8): Cancel All now also performs the ──
                            // partial-received cleanup the old separate "Clear
                            // Partial Received Items" action owned. The
                            // three-pass cleanup (live interrupted-receive
                            // cards + registry orphans + orphaned staging
                            // partials) only ever touches incomplete data that
                            // can be reliably associated with interrupted
                            // receives — every transfer Cancel All just resolved
                            // is already terminal, completed files and their
                            // History entries are untouched, and a cleanup pass
                            // already in flight is simply swallowed (the
                            // partialCleanupInFlight guard inside). One action,
                            // one complete story: nothing incomplete remains.
                            performClearPartialReceived()
                        },
                    ),
                )
            })
            // ── GOAT-PASS 15 (§8): the separate "Clear Partial Received Items"
            // action is RETIRED. Its three-pass cleanup (live interrupted-
            // receive cards + registry orphans + orphaned staging partials)
            // now runs as an integral part of Cancel All above — the same
            // cancellation lifecycle, one action, nothing incomplete left
            // behind. The engine half (performClearPartialReceived) is kept
            // exactly as it was; only its separate entry point is gone.
            root
        }
    }

    // ── GOAT-PASS 9 (§1): Clear Partial Received Items ─────────────────────────

    /** True while a partial-cleanup pass is running — repeated taps (menu
     *  re-opened mid-cleanup) are swallowed so two concurrent passes can
     *  never interleave their deletions or double-report. */
    @Volatile private var partialCleanupInFlight = false

    /** The engine + storage half of §1. Runs the deletions on a background
     *  thread (a slow flash wipe must never jank the tab), then re-renders
     *  and reports on the main thread. Three passes, each idempotent:
     *
     *   1. every live auto-resume row — requestCancel (resolves the row,
     *      writes the durable upload tombstone so the sender's next probe
     *      reads "cancelled" instead of re-sending), delete the staging
     *      partial + sidecar, drop the ResumableReceiveStore entry, and
     *      leave a discard receipt (the sender asks its user instead of
     *      silently restarting), exactly like the per-row discard flow;
     *   2. registry orphans — entries with no live row still own partial
     *      data that a restart would restore as cards; cleared the same way
     *      so nothing can reappear later;
     *   3. orphaned .wsxpart files inside the receive root (a registry write
     *      that failed mid-session) — swept with a bounded walk that NEVER
     *      touches the staging of a live receive in progress.
     */
    private fun performClearPartialReceived() {
        if (partialCleanupInFlight) return
        partialCleanupInFlight = true
        val ctx = requireContext()
        Thread {
            var cleared = 0
            var failed = 0
            // Pass 1 — the live rows the user is looking at.
            for (t in TransferManager.autoResumeReceiverRows()) {
                try {
                    TransferManager.requestCancel(t.id)
                    t.filePath?.let { path ->
                        com.lumidrop.x.transfer.ResumableReceiveStore.deleteStaging(path)
                        com.lumidrop.x.transfer.ResumableReceiveStore.remove(ctx, path)
                    }
                    try { server?.rememberDiscardedReceive(t.uploadRequestId, t.filePath) } catch (e: Exception) { /* no live sender to inform */ }
                    TransferManager.dismissResumable(t.id)
                    cleared++
                } catch (e: Exception) { failed++ }
            }
            // Pass 2 — registry entries without a live row.
            try {
                for (e in com.lumidrop.x.transfer.ResumableReceiveStore.all(ctx)) {
                    try {
                        com.lumidrop.x.transfer.ResumableReceiveStore.deleteStaging(e.destPath)
                        com.lumidrop.x.transfer.ResumableReceiveStore.remove(ctx, e.destPath)
                        cleared++
                    } catch (ex: Exception) { failed++ }
                }
            } catch (e: Exception) { /* unreadable registry — the rows pass already covered the visible cards */ }
            // Pass 3 — orphaned staging inside the receive root. A receive
            // that is running RIGHT NOW owns its .wsxpart; everything else
            // under the root (bounded depth: receives only create the root
            // plus per-transfer subfolders) is leftover partial data.
            try {
                val protectedPaths = HashSet<String>()
                TransferManager.activeSnapshot().filter {
                    it.direction == Direction.UPLOAD && (it.status == Status.PENDING || it.status == Status.ACTIVE)
                }.forEach { t -> t.filePath?.let { protectedPaths.add(File(it).parentFile?.absolutePath ?: "") } }
                val root = File(com.lumidrop.x.settings.SettingsManager.getUploadPath(ctx))
                val dirsToSweep = ArrayList<File>(8)
                if (root.isDirectory) {
                    dirsToSweep.add(root)
                    root.listFiles()?.forEach { c -> if (c.isDirectory) dirsToSweep.add(c) }
                }
                for (d in dirsToSweep) {
                    if (d.absolutePath in protectedPaths) continue
                    val kids = try { d.listFiles() } catch (e: Exception) { null } ?: continue
                    for (k in kids) {
                        val name = k.name
                        if (name.endsWith(".wsxpart") || name.endsWith(".wsxpart.meta")) {
                            try { if (k.delete()) cleared++ } catch (e: Exception) { failed++ }
                        }
                    }
                }
            } catch (e: Exception) { /* best-effort sweep — the registry pass already owns the known partials */ }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                partialCleanupInFlight = false
                if (!isAdded) return@post
                val msg = when {
                    failed > 0 -> "Cleared $cleared item${if (cleared != 1) "s" else ""} — couldn't clear $failed"
                    cleared > 0 -> "Cleared $cleared partial item${if (cleared != 1) "s" else ""}"
                    else -> "Nothing to clear"
                }
                Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
                renderCurrentTab()
            }
        }.apply { isDaemon = true }.start()
    }

    /** Final Stabilization (§6): the slim summary line — "N transferring ·
     *  M paused" — refreshed from the same live snapshot the tab already
     *  polls, so the overflow button's context is always current. */
    private fun refreshBulkSummary() {
        if (!isAdded) return
        val snapshot = TransferManager.activeSnapshot()
        val live = snapshot.count { it.status == Status.PENDING || it.status == Status.ACTIVE }
        val paused = snapshot.count { (it.status == Status.PENDING || it.status == Status.ACTIVE) && it.pauseRequested }
        // ── GOAT-PASS 11 (§8 — the Waiting-to-Resume count derives from the ──
        // ACTUAL "Auto-Resume on Reconnection" state, nothing else). GP10's
        // first cut counted every FAILED+interrupted row in the snapshot — a
        // generic interrupted-flag read that swept in rows no Auto-Resume
        // card was showing (sender-side legs whose recovery this device's
        // own engine owns, expired-window leftovers, rows mid-teardown).
        // The count now reads the SAME population the tab's own cleanup
        // action acts on — autoResumeReceiverRows(): the receiver's genuine
        // Auto-Resume-on-Reconnection items, BOTH phases of that state
        // (FAILED+interrupted recovery cards AND live ACTIVE+reconnecting
        // legs in their back-off), and NOTHING else. Failed transfers,
        // cancelled transfers, ordinary Waiting rows and every other
        // terminal state are structurally excluded, and the count and the
        // "Clear Partial Received Items" eligibility can never disagree.
        val waitingResume = TransferManager.autoResumeReceiverRows().size
        activeSummaryLabel?.let { lbl ->
            val parts = mutableListOf<String>()
            parts += if (live == 1) "1 active" else "$live active"
            if (paused > 0) parts += "$paused paused"
            if (waitingResume > 0) {
                parts += if (waitingResume == 1) "1 waiting to resume" else "$waitingResume waiting to resume"
            }
            lbl.text = parts.joinToString("  ·  ")
        }
    }

    private var lastOpenFolderAt = 0L
    private fun openReceiveFolder() {
        // Final Stabilization (§8): rapid repeat taps must not stack
        // multiple resolver sheets — one tap inside 800ms of the last is
        // swallowed. The first launch still always goes through Android's
        // own resolver (Just once/Always semantics untouched below).
        //
        // Pass-11 (Required Implementation 2 — Android 5 crash guard): the
        // whole launch ladder below is additionally wrapped in one outer
        // try/catch — the reported Android 5 crash on this action leaves no
        // surviving failure mode (every individual rung was already guarded,
        // so this closes any device-specific resolver quirk we could not
        // reproduce in inspection), and a toast is strictly better than a
        // hard crash either way.
        try {
            openReceiveFolderInner()
        } catch (e: Exception) {
            try {
                Toast.makeText(requireContext(), "Couldn't open the receive folder", Toast.LENGTH_SHORT).show()
            } catch (ignored: Exception) {}
        }
    }

    private fun openReceiveFolderInner() {
        val now = System.currentTimeMillis()
        if (now - lastOpenFolderAt < 800L) return
        lastOpenFolderAt = now
        val ctx = requireContext()
        val dir = File(com.lumidrop.x.settings.SettingsManager.getUploadPath(ctx))
        if (!dir.exists()) dir.mkdirs()

        // ── GOAT-PASS 10 (§9 — Open Received Folder Internally): when the ──
        // setting is ON, the app's OWN full-screen Files Sheet opens at the
        // EXACT configured destination, always — no external flow, no
        // resolver, no near-miss rung. Availability is validated first (the
        // directory must genuinely exist and be readable): an unavailable
        // destination gets an honest error, never a silent open of some
        // unrelated directory.
        if (com.lumidrop.x.settings.SettingsManager.isOpenReceivedFolderInternally(ctx)) {
            openReceiveFolderInternally(dir)
            return
        }

        // ── Post-v1.0 fix (§open-exact-folder) ────────────────────────────
        // THE reported failure: choosing Android's own "Files" app from the
        // resolver opened the Download ROOT, not the receive subfolder.
        // Cause: the launch handed every manager a FileProvider content URI
        // with the resource/folder convention — DocumentsUI (the "Files"
        // app) cannot browse an arbitrary FileProvider tree, so it falls
        // back to its own roots view. DocumentsUI DOES navigate directly to
        // a folder when handed that folder's OWN ExternalStorageProvider
        // document URI — so when the receive directory lives under primary
        // external storage (the default: /storage/emulated/0/Download/
        // LumiDrop X), that URI is now the PRIMARY launch: the resolver
        // sheet still appears exactly as before (Android's own Just once /
        // Always, one fixed signature so "Always" keeps sticking), and
        // every handler that accepts it opens THE EXACT FOLDER.
        val extRoot = try { android.os.Environment.getExternalStorageDirectory().canonicalFile } catch (e: Exception) { null }
        val canon = try { dir.canonicalFile } catch (e: Exception) { dir }
        val relUnderPrimary: String? = extRoot?.let { root ->
            if (canon.path == root.path || canon.path.startsWith(root.path + "/"))
                canon.path.removePrefix(root.path).removePrefix("/")
            else null
        }
        val docUri = relUnderPrimary?.takeIf { it.isNotBlank() }?.let {
            try { DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$it") }
            catch (e: Exception) { null }
        }
        // ── GOAT-PASS 9 (§13 — SD-card destinations): the exact-folder ──
        // document URI now also covers REMOVABLE volumes. A receive
        // directory on an SD card (/storage/XXXX-XXXX/...) is under no
        // primary root, so the deep-link rungs below used to skip entirely
        // and the action fell straight to the generic legacy ladder.
        // ExternalStorageProvider addresses removable volumes by their
        // volume ID — the same directory name the card mounts under — so
        // "<volumeId>:<relative path>" gives the SAME DocumentsUI
        // exact-folder deep link the primary rung has always used, for the
        // card too. Resolved from the SAME authoritative receive-destination
        // setting the receiving system uses (SettingsManager.getUploadPath
        // above); no separate hardcoded path exists for this button.
        val sdDocUri: android.net.Uri? = if (docUri == null && canon.path.startsWith("/storage/")) {
            val segments = canon.path.split('/')
            if (segments.size >= 3 && segments[1] == "storage" && segments[2].contains('-')) {
                val volumeId = segments[2]
                val rel = segments.subList(3, segments.size).joinToString("/")
                if (rel.isNotBlank()) {
                    try { DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "$volumeId:$rel") }
                    catch (e: Exception) { null }
                } else null
            } else null
        } else null
        val exactFolderDocUri: android.net.Uri? = docUri ?: sdDocUri

        val pm = ctx.packageManager
        fun handlersFor(uri: android.net.Uri, mime: String): List<android.content.pm.ResolveInfo> = try {
            pm.queryIntentActivities(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }, 0)
        } catch (e: Exception) { emptyList() }

        // ── Pass-14 (P16 — system Files app, never third-party) ─────────────
        // The reported defect: "Open Received Folder" launched the generic
        // resolver, and a previously-saved "Always" choice (ZArchiver, any
        // other installed manager) kept winning — the pass-3 design honored
        // saved defaults on purpose, which is exactly what the user now
        // wants gone. The new contract: Android's OWN file manager opens the
        // EXACT received-files directory, on every Android 5–16 device,
        // whether or not third-party managers are installed. The ladder:
        //   1. DocumentsUI (the built-in "Files" app) by explicit package,
        //      handed the folder's own ExternalStorageProvider document URI.
        //   2. Any OTHER preinstalled system app (FLAG_SYSTEM — OEM "Files"
        //      apps) that can VIEW that exact-folder URI, launched directly.
        //   3. DocumentsUI at the Download root (one tap away) when the
        //      receive dir lives under Download, with a toast saying where
        //      the files are.
        //   4. Legacy ladder (FileProvider URI → resolver/chooser) ONLY when
        //      nothing system-provided can serve the folder at all — better
        //      a working third-party manager than a dead end, but it can
        //      never be the first choice again.
        fun isSystemHandler(ri: android.content.pm.ResolveInfo): Boolean = try {
            val flags = ri.activityInfo?.applicationInfo?.flags ?: 0
            (flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) { false }

        fun launchDirect(intent: Intent): Boolean = try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) { false }

        if (exactFolderDocUri != null) {
            // Rung 1: the built-in Files app, by package, at the EXACT folder
            // (primary volume or SD card — GOAT-PASS 9 §13).
            for (pkg in listOf("com.android.documentsui", "com.google.android.documentsui")) {
                val direct = try {
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(exactFolderDocUri, DocumentsContract.Document.MIME_TYPE_DIR)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        setPackage(pkg)
                    }
                } catch (e: Exception) { null }
                if (direct != null) {
                    val canServe = try { pm.queryIntentActivities(direct, 0).isNotEmpty() } catch (e: Exception) { false }
                    if (canServe && launchDirect(direct)) return
                }
            }
            // Rung 2: any preinstalled system file manager that can open the
            // exact folder (OEM "Files"/"My Files" apps are FLAG_SYSTEM;
            // ZArchiver and every sideloaded manager is not, by design).
            val sysHandlers = handlersFor(exactFolderDocUri, DocumentsContract.Document.MIME_TYPE_DIR).filter { isSystemHandler(it) }
            if (sysHandlers.isNotEmpty()) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(exactFolderDocUri, DocumentsContract.Document.MIME_TYPE_DIR)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                intent.setPackage(sysHandlers[0].activityInfo.packageName)
                if (launchDirect(intent)) return
            }
        }

        // Rung 3: receive dir under Download → the built-in Files app at the
        // Download root, one tap from the files, with an honest toast.
        if (relUnderPrimary != null && relUnderPrimary.substringBefore('/').equals("Download", ignoreCase = true)) {
            val dlRoot = try {
                DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download")
            } catch (e: Exception) { null }
            if (dlRoot != null) {
                for (pkg in listOf("com.android.documentsui", "com.google.android.documentsui")) {
                    val direct = try {
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(dlRoot, DocumentsContract.Document.MIME_TYPE_DIR)
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            setPackage(pkg)
                        }
                    } catch (e: Exception) { null }
                    if (direct != null) {
                        val canServe = try { pm.queryIntentActivities(direct, 0).isNotEmpty() } catch (e: Exception) { false }
                        if (canServe && launchDirect(direct)) {
                            Toast.makeText(ctx, "Opened Download \u2014 the received files are in ${dir.name}", Toast.LENGTH_LONG).show()
                            return
                        }
                    }
                }
            }
        }

        // Rung 4a (Android 5/6, where DocumentsUI cannot ACTION_VIEW a
        // directory document URI): the era's file managers speak plain
        // file:// URIs. System-installed managers still get first refusal,
        // launched directly — the third-party resolver only ever runs in
        // the legacy ladder below.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            val fileUri = android.net.Uri.fromFile(canon)
            val signatures = listOf(
                // Some OEM managers only declare the classic convention…
                "resource/folder" to true,
                // …some declare the SAF directory type for file:// too…
                DocumentsContract.Document.MIME_TYPE_DIR to true,
                // …and some match the bare URI scheme only. A bare
                // ACTION_VIEW with no type was previously never tried,
                // which is exactly how a preinstalled manager could be
                // present and still unreachable while ZArchiver won.
                null to false)
            for ((mime, useType) in signatures) {
                val sysFile = if (useType) handlersFor(fileUri, mime!!).filter { isSystemHandler(it) }
                              else try {
                                  pm.queryIntentActivities(Intent(Intent.ACTION_VIEW).apply {
                                      data = fileUri
                                      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                  }, 0).filter { isSystemHandler(it) }
                              } catch (e: Exception) { emptyList() }
                if (sysFile.isNotEmpty()) {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        data = fileUri
                        if (useType) type = mime
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    intent.setPackage(sysFile[0].activityInfo.packageName)
                    if (launchDirect(intent)) return
                }
            }
            // ── Pass-15 (Android 5 "opens in ZArchiver" root fix) ────────
            // Rung 4b: the era's PREINSTALLED file managers by package — the
            // one discovery channel the ladder never tried. An OEM "Files" /
            // "My Files" / "File Manager" app often declares NO View intent
            // filter for foreign file:// or content:// URIs at all (so every
            // query above misses it) yet opens its own browser perfectly
            // well from its launcher intent. Each candidate is verified as
            // genuinely preinstalled (FLAG_SYSTEM) before its launcher is
            // used — a sideloaded third-party manager can never take this
            // rung. The exact folder can't be deep-linked this way, so the
            // toast says exactly where to go.
            val oemPkgs = listOf(
                "com.sec.android.app.myfiles",            // Samsung My Files
                "com.sec.android.app.myfiles.files",       // Samsung (split builds)
                "com.mediatek.filemanager",                 // MTK-era tablets/phones
                "com.android.filemanager",                  // AOSP-ish OEM builds
                "com.lenovo.filer",                         // Lenovo tablets
                "com.lenovo.FileManager2",
                "com.asus.filemanager",                     // ASUS
                "com.asusFileManager",                      // ASUS (older)
                "com.alps.filemanager",                     // ALPS/OEM tablets
                "com.huawei.hidisk",                        // Huawei File Manager
                "com.hihonor.filemanager",                  // Honor
                "com.coloros.filemanager",                  // Oppo
                "com.oneplus.filemanager",                  // OnePlus
                "com.miui.filemanager"                      // Xiaomi (MIUI)
            )
            for (pkg in oemPkgs) {
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    if (ai == null || (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) continue
                    val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
                    if (launchDirect(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                        Toast.makeText(ctx, "Opened " + ai.loadLabel(pm) +
                            " \u2014 open " + (relUnderPrimary ?: dir.name) + " to reach the received files", Toast.LENGTH_LONG).show()
                        return
                    }
                } catch (e: Exception) { /* not installed — next candidate */ }
            }
            // Rung 4c: Android 6+ carries a real "Files" activity inside
            // DocumentsUI even where ACTION_VIEW of a directory URI
            // doesn't resolve; 5.x has none, so this simply misses there.
            try {
                val filesAct = Intent().setClassName("com.android.documentsui",
                    "com.android.documentsui.files.FilesActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (pm.queryIntentActivities(filesAct, 0).isNotEmpty() && launchDirect(filesAct)) {
                    Toast.makeText(ctx, "Opened Files \u2014 open " + (relUnderPrimary ?: dir.name) +
                        " to reach the received files", Toast.LENGTH_LONG).show()
                    return
                }
            } catch (e: Exception) { /* not present on this build — skip */ }
            // ── Rung 4d (Pass-18 — Android 5 "opens in ZArchiver" root fix) ──
            // THE remaining Android-5 gap: on a stock 5.x device EVERY earlier
            // rung legitimately misses — DocumentsUI there cannot ACTION_VIEW
            // a directory document URI (rungs 1-3), the device typically has
            // no preinstalled OEM manager declaring a file:// VIEW filter
            // (4a), no OEM Files package at all (4b), and no FilesActivity —
            // that activity only exists from 6.0 (4c). The ladder then fell
            // all the way through to the legacy chooser, where ZArchiver was
            // the ONE handler of the content:// resource/folder URI — and a
            // single-target createChooser on Android 5 launches that one
            // target DIRECTLY, no sheet: exactly the reported "clicking it
            // directly opens ZArchiver".
            //
            // The fix is the discovery channel the ladder never used: the
            // built-in Files app's own LAUNCHER intent, resolved by package
            // name (getLaunchIntentForPackage — no hardcoded activity class,
            // so it finds whatever the build's launcher entry actually is:
            // LauncherActivity on 5.x, FilesActivity on 6.x+). Android 5's
            // DocumentsUI is present and FLAG_SYSTEM on every stock 5.x
            // device and appears in the launcher as "Files" — opening its
            // roots view is exactly "Android's built-in Files app", one tap
            // from Download → LumiDrop X, which the toast spells out. This
            // rung therefore fires BEFORE the legacy ladder ever can, and a
            // third-party manager can no longer win this action on any
            // device that has a system Files app at all.
            for (pkg in listOf("com.android.documentsui", "com.google.android.documentsui")) {
                try {
                    val ai = pm.getApplicationInfo(pkg, 0)
                    if (ai == null || (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0) continue
                    val launch = pm.getLaunchIntentForPackage(pkg) ?: continue
                    if (launchDirect(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) {
                        Toast.makeText(ctx, "Opened " + ai.loadLabel(pm) + " \u2014 open " +
                            (relUnderPrimary ?: dir.name) + " to reach the received files", Toast.LENGTH_LONG).show()
                        return
                    }
                } catch (e: Exception) { /* not installed on this build — next candidate */ }
            }
        }

        // ── Legacy ladder (custom receive paths outside primary storage, or ──
        //    a device whose file managers only speak the older conventions).
        //    resource/folder is the one MIME convention real file managers
        //    adopt SPECIFICALLY to say "I can browse a directory"; using
        //    exactly one type every time is also what makes a saved "Always"
        //    stick for it (Android's preferred-activity system is keyed to
        //    the action+type signature that was launched).
        val uri = try { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", dir) }
                  catch (e: Exception) { openReceiveFolderInternally(dir, becauseUnavailable = true); return }
        val folderMimes = listOf("resource/folder", DocumentsContract.Document.MIME_TYPE_DIR)
        val candidates = folderMimes.map { it to handlersFor(uri, it) }.filter { it.second.isNotEmpty() }
        if (candidates.isEmpty()) {
            // ── GOAT-PASS 10 (§10C): the external flow is UNAVAILABLE on this ──
            // device — nothing on it can open the folder. The app's own
            // full-screen Files Sheet at the exact destination is the
            // fallback, independently of the new toggle: the received files
            // stay reachable no matter what the device has installed.
            openReceiveFolderInternally(dir, becauseUnavailable = true)
            return
        }
        // ── Pass-18 (never auto-launch a lone third-party manager on 5.x) ──
        // Android 5's createChooser launches its single target DIRECTLY — no
        // sheet, no user pick. Combined with the old fall-through, that is
        // precisely how "clicking it directly opens ZArchiver" happened: the
        // only candidate was third-party, so the "chooser" was a silent
        // launch. Now this ladder is only ever reached on 5.x when even the
        // built-in Files app (rung 4d) was missing — an exotic build — and
        // in that case a single THIRD-PARTY candidate resolves to an honest
        // toast with the folder's location instead of auto-launching an app
        // the user never asked for. Multiple candidates (a genuine choice)
        // and any system candidate (an OEM manager the device shipped with)
        // keep the ordinary chooser behavior.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            val anySystem = candidates.any { c -> c.second.any { isSystemHandler(it) } }
            val totalHandlers = candidates.sumOf { it.second.size }
            if (!anySystem && totalHandlers == 1) {
                // ── GOAT-PASS 10 (§10C): the only "handler" is a lone ──
                // third-party manager on a device with no system one — the
                // external flow is unsupported here, and silently launching
                // an unrequested app was never acceptable anyway. The app's
                // own full-screen Files Sheet at the exact destination is
                // the honest fallback.
                openReceiveFolderInternally(dir, becauseUnavailable = true)
                return
            }
        }
        val (bestMime, bestHandlers) = candidates.maxByOrNull { it.second.size }!!
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, bestMime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            // ── Pass-15 (never silently re-open a saved "Always") ────────
            // ALWAYS the chooser here — the bare resolver honored a
            // previously-saved "Always" default, which is precisely how
            // ZArchiver kept winning this action on devices where a system
            // manager never got the chance. The chooser asks every time,
            // so a third-party manager can only ever open by the person's
            // own explicit pick; with nothing system-provided available at
            // all, that explicit pick is still better than a dead end.
            startActivity(Intent.createChooser(intent, "Open receive folder"))
        } catch (e: Exception) {
            // ── GOAT-PASS 10 (§10C): even the chooser failed — the internal ──
            // full-screen Files Sheet keeps the received files reachable.
            openReceiveFolderInternally(dir, becauseUnavailable = true)
        }
    }

    /** ── GOAT-PASS 10 (§9 + §10C): the INTERNAL receiver — LumiDrop X's own ──
     *  Files Sheet opened at the EXACT configured receive destination, used
     *  both by the Open Received Folder Internally setting (always, when ON)
     *  and as the fallback wherever the external flow is unavailable or
     *  unsupported (independently of the toggle).
     *
     *  Honest availability first: a destination that does not exist or
     *  cannot be read (SD card removed, USB storage disconnected, storage
     *  permission revoked) produces a clear error and NOTHING opens — never
     *  an unrelated directory, never a silent success. When the folder IS
     *  available, the sheet navigates directly to it, roots itself on the
     *  correct volume (internal, SD, USB), and carries the sheet's whole
     *  existing behavior: navigation, sorting, selection,
     *  delete/rename/copy/cut/paste/new-folder, Back navigation (the
     *  sheet's own up-directory contract, then its dismissal) and the drag
     *  handle's collapse. The Add button routes through the app's one
     *  staging queue via MainActivity.
     *
     *  ── GOAT-PASS 12 (§1): the sheet opens at its NORMAL size — the exact ──
     *  same 75%-height presentation the Files tile has always used (rounded
     *  top corners, bottom-anchored, drag-to-expand intact). The GOAT-PASS 10
     *  auto-full-screen stretch is retired: this is a navigation request,
     *  not a presentation change, and the user stretches it manually exactly
     *  as they always could. */
    private fun openReceiveFolderInternally(dir: File, becauseUnavailable: Boolean = false) {
        val ctx = requireContext()
        val alive = try { dir.isDirectory && dir.canRead() } catch (e: Exception) { false }
        if (!alive) {
            Toast.makeText(ctx, "The received-files folder isn't available right now — check that its storage is connected",
                Toast.LENGTH_LONG).show()
            return
        }
        if (becauseUnavailable) {
            Toast.makeText(ctx, "No file manager could open it — opening LumiDrop X's own Files view instead",
                Toast.LENGTH_LONG).show()
        }
        com.lumidrop.x.ui.showFilesPickerSheet(
            this,
            onOpenFile = { f -> openFileFromSheet(f) },
            startDir = dir,
        ) { items ->
            (activity as? com.lumidrop.x.MainActivity)?.stagePickedFilesAndShowSend(items)
        }
    }

    /** ── GOAT-PASS 10 (§9): the internal Files Sheet's open-a-file handler — ──
     *  the same FileProvider + MIME routing the tab's own rows use (see
     *  openFile), factored for a plain File instead of a Transfer row. */
    private fun openFileFromSheet(file: File) {
        val ctx = requireContext()
        if (!file.exists()) {
            Toast.makeText(ctx, "File no longer exists on this device", Toast.LENGTH_SHORT).show()
            return
        }
        // ── FINAL PASS 5 (§8): the Transfers screen's own browse sheet (the ──
        // received-folder picker) routes media through the same internal
        // players the cards now use — one consistent open experience across
        // every Transfers surface. A plain File has no Transfer row, so a
        // minimal synthetic row carries the path/name to openTransferFile's
        // router (its only consumed fields); every non-media type falls
        // straight through to the exact ACTION_VIEW this function always ran.
        openTransferFile(TransferManager.Transfer(
            id = "sheet:${file.absolutePath}",
            direction = Direction.UPLOAD,
            fileName = file.name,
            totalBytes = file.length(),
            peerIp = "",
            peerName = "",
            filePath = file.absolutePath,
        ).also { it.status = Status.COMPLETED })
    }

    // Label helpers ----------------------------------------------------------------------

    /** ── FINAL PASS 5 (§5): the Received card's display title. ──────────────────
     *  The MIDDLE-ellipsized single line the shell's tv_name already provides
     *  is kept as the layout mechanism (it measures the REAL available width
     *  — never a guessed character count), and this helper refines what text
     *  is fed into it so long names look intentional rather than aggressively
     *  cut:
     *
     *  • A SHORT-to-moderate name (≤ 48 chars) is shown VERBATIM — the
     *    ellipsize still handles any width overflow the char count can't
     *    predict, so a 40-char name on a narrow screen still truncates
     *    cleanly.
     *  • A LONG name is pre-shortened to "first 30 … last 15" chars, which
     *    MIDDLE-ellipsize then fits to the row. The 30-char head keeps the
     *    meaningful prefix readable; the 15-char tail keeps the extension
     *    AND any suffix (".apk", " (1).jpg", a versioned ".zip") visibly
     *    identifiable — the exact types the requirement names. A 48-char
     *    budget was chosen against the card's real geometry: a 40dp
     *    thumbnail + a 30dp ⋮ + card padding leave ~230dp of title width
     *    at 360dp (the smallest supported screens), which holds ~44-50
     *    chars at the shell's 12.5sp — so names under the budget render
     *    whole on every target device, and only genuinely long ones enter
     *    the pre-shortened path.
     *  • No-extension names, names with spaces, and short names all pass
     *    through untouched; the FULL filename is never modified — this is
     *    presentation only, and every other surface (History, the open/
     *    share intents, the rename dialog) continues to use the real name.
     */
    private fun displayTitleFor(fileName: String): String {
        if (fileName.length <= 48) return fileName
        val head = fileName.take(30).trimEnd()
        val tail = fileName.takeLast(15)
        return "$head…$tail"
    }

    /** ── FINAL PASS 5 (§2): [hidePeer] omits the sender-origin segment ────────
     *  ("From Windows · Chrome" / "From [Device]") from the line — the
     *  Received tab's cleaner, more compact cards. The UNDERLYING metadata
     *  is untouched (peerName still lives on the row, History still renders
     *  it, ReceivedFileMeta still records it); only this display omits it.
     *  History and every other surface call the no-arg form and keep the
     *  segment exactly as before.
     *  ── FINAL PASS 6 (PART 5.2): [history] — true for the History tab's ──────
     *  rows — History shows the BARE device name (FINAL PASS 9: direction
     *  travels as the name's COLOR, not as wording;
     *  participant-identifying cards). */
    // ── FINAL PASS 9 (PART 3.2/3.3): History's device-name segment now ─────────
    // carries the DIRECTION as color — the exact badge tokens the rows
    // already use (R.color.accent for received, R.color.orange for sent,
    // the same colors the purple-down/orange-up dir badges paint with) —
    // and the metadata line AUTO-FITS: it measures itself against the
    // card's real width and steps the font size down (11sp → 9sp floor)
    // before ever showing an ellipsis, re-deriving on every bind so a
    // rotation/resize simply lands the correct size on the next pass.
    // ── PASS 20 (§3 — reliable direction-colored device names): the span ──
    // position is now COMPUTED from the line's construction, never searched.
    // detailLine/groupDetailLine always place the bare peer name as a
    // distinct leading segment of its part ("peer  ·  rest" / parts[0]);
    // the exact index is found by matching the peer as a COMPLETE segment
    // (bounded by the line start or the "  ·  " separator), so a device
    // name that also appears inside a filename, a text snippet, or a size
    // label can never capture the span, and a name that somehow cannot be
    // located (defensive) leaves the line uncolored rather than coloring a
    // wrong occurrence. The color still comes from the row's RELIABLE
    // direction metadata (received=purple accent, sent=orange) — never from
    // the device name's text.
    private fun historyDetailSpan(full: String, peer: String, received: Boolean): android.text.SpannableString {
        val sp = android.text.SpannableString(full)
        if (peer.isNotEmpty()) {
            val start = peerSegmentStart(full, peer)
            if (start >= 0) {
                val color = if (received) col(R.color.accent) else col(R.color.orange)
                sp.setSpan(android.text.style.ForegroundColorSpan(color), start, start + peer.length,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return sp
    }

    /** The byte offset of [peer] as a COMPLETE segment of [full] — a segment
     *  is the run between line start / a "  ·  " separator and the next
     *  separator / line end. Returns -1 when the peer is not present as a
     *  whole segment (never a substring hit inside another word). */
    private fun peerSegmentStart(full: String, peer: String): Int {
        if (peer.isEmpty()) return -1
        val sep = "  ·  "
        val sep2 = "  .  "
        var search = 0
        while (search <= full.length - peer.length) {
            val hit = full.indexOf(peer, search)
            if (hit < 0) return -1
            val atStart = hit == 0 ||
                full.regionMatches(hit - sep.length, sep, 0, sep.length) ||
                (hit >= sep2.length && full.regionMatches(hit - sep2.length, sep2, 0, sep2.length))
            val end = hit + peer.length
            val atEnd = end == full.length ||
                full.regionMatches(end, sep, 0, sep.length) ||
                full.regionMatches(end, sep2, 0, sep2.length)
            if (atStart && atEnd) return hit
            search = hit + 1
        }
        return -1
    }

    private fun paintDetailLine(tv: TextView?, t: TransferManager.Transfer, hidePeer: Boolean, history: Boolean) {
        if (tv == null) return
        val line = detailLine(t, hidePeer, history)
        if (history && !hidePeer && t.peerName.isNotBlank()) {
            tv.text = historyDetailSpan(line, t.peerName, t.direction == TransferManager.Direction.UPLOAD)
        } else {
            tv.text = line
        }
        autoFitDetailLine(tv, line)
    }

    private fun paintGroupDetailLine(tv: TextView?, info: TransferManager.TransferGroupInfo, members: List<TransferManager.Transfer>, filesDone: Int, hidePeer: Boolean, history: Boolean) {
        if (tv == null) return
        val line = groupDetailLine(info, members, filesDone, hidePeer, history)
        if (history && !hidePeer && info.peerName.isNotBlank()) {
            tv.text = historyDetailSpan(line, info.peerName, info.direction == TransferManager.Direction.UPLOAD)
        } else {
            tv.text = line
        }
        autoFitDetailLine(tv, line)
    }

    /** PART 3.3 — prefer the COMPLETE text over an ellipsis: step the font
     *  down (11sp → 9sp, the readable floor) while the painted width still
     *  exceeds the card's text column; only when even 9sp cannot fit does
     *  the END ellipsis take over (the line's existing maxLines=1). Runs on
     *  every bind — cheap (≤3 paint measurements) and self-correcting
     *  across rotation, resize and recycle. */
    private fun autoFitDetailLine(tv: TextView, text: String) {
        val lp = tv.layoutParams
        val parentW = (tv.parent as? View)?.width ?: 0
        val avail = if (parentW > 0) parentW else resources.displayMetrics.widthPixels - dp(76)
        val paint = tv.paint
        var size = 11f
        tv.textSize = size
        while (size > 9f && paint.measureText(text) > avail) {
            size -= 0.5f
            tv.textSize = size
        }
        // The ago-ticker rewrites plain text on ticks — it must preserve
        // both the span and the fitted size; the registered supplier
        // closures return the plain string, so the ticker's equality
        // check keeps the spanned/fitted state stable between ticks.
        lp.width = LinearLayout.LayoutParams.MATCH_PARENT
        tv.layoutParams = lp
    }

    private fun detailLine(t: TransferManager.Transfer, hidePeer: Boolean = false, history: Boolean = false): String {
        // ── Pass-16 (text messages detail as MESSAGES) ──────────────────────
        // A text row's useful facts are WHO and WHAT — a one-line snippet of
        // the real content plus the character count — never a byte count for
        // content that was never a file. Same "[Time] Ago" suffix as every
        // other terminal row (kept fresh by the ago ticker above).
        if (t.isTextMessage) {
            val peer = when {
                hidePeer -> ""
                t.direction == TransferManager.Direction.UPLOAD && t.peerName.isBlank() -> ""
                // ── FINAL PASS 9 (PART 3.1): History rows show the BARE ──
                // device name — the direction-prefix wording is
                // gone; the direction now travels as the device name's
                // COLOR (see updateRow's historySpan paint below).
                t.direction == TransferManager.Direction.UPLOAD -> t.peerName
                else -> t.peerName
            }
            val text = t.textContent ?: ""
            val snippet = text.replace("\r", "").replace('\n', ' ').trim().let {
                if (it.length > 42) it.take(42) + "…" else it
            }
            val parts = mutableListOf<String>()
            if (peer.isNotEmpty()) parts.add(peer)
            parts.add("${text.length} chars")
            if (snippet.isNotEmpty()) parts.add(snippet)
            when (t.status) {
                Status.PENDING, Status.ACTIVE -> return parts.joinToString("  ·  ")
                Status.DECLINED -> return (parts + "Declined").joinToString("  ·  ")
                else -> return (parts + fmtTimeAgo(t.finishedAt)).joinToString("  ·  ")
            }
        }
        // ── Pass-15 ("[Time] Ago" in Received/History) ──────────────────────
        // Terminal rows now show WHEN they finished, in the minimalist
        // relative form ("just now", "4m ago", "3h ago", "2d ago") — a received
        // item's useful time fact is when it landed, not how long the byte
        // copy itself took. Live rows keep the exact byte/speed readout.
        // ── FINAL PASS 6 (PART 5.2): [history] switches the direction wording ──
        // as History's bare device name (FINAL PASS 9) —
        // direction rides the color span, where the record's
        // participants ARE the card's identifying information. The Active
        // tab's live rows keep the compact From/To forms (byte counters
        // dominate there); hidePeer (the Received tab's cleaner cards, FINAL
        // PASS 5) keeps suppressing the segment entirely.
        val peer = when {
            hidePeer -> ""
            t.direction == TransferManager.Direction.UPLOAD && t.peerName.isBlank() -> ""
            // FINAL PASS 9 (PART 3.1): bare device name in History —
            // direction rides the color span (see paintDetailLine).
            t.direction == TransferManager.Direction.UPLOAD -> t.peerName
            else -> t.peerName
        }
        return when (t.status) {
            Status.PENDING -> (if (peer.isEmpty()) "" else "$peer  .  ") + fmtBytes(t.totalBytes)
            Status.ACTIVE  -> {
                // ── Pass-24 (#5): the live speed no longer rides this line ──
                // — it has its own accent-tinted line directly beneath the
                // progress bar (see tv_speed / applySpeedView). The detail
                // line keeps the peer and the byte counts.
                (if (peer.isEmpty()) "" else "$peer  .  ") + "${fmtBytes(t.transferredBytes)}/${fmtBytes(t.totalBytes)}"
            }
            else -> {
                val parts = mutableListOf<String>()
                if (peer.isNotEmpty()) parts.add(peer)
                parts.add(fmtBytes(t.totalBytes))
                parts.add(fmtTimeAgo(t.finishedAt))
                parts.joinToString("  .  ")
            }
        }
    }

    /** Pass-15: minimalist relative time — the professional "[Time] Ago"
     *  format the Received and History item cards asked for. Falls back to
     *  a short absolute date past a week (a card from months ago reads
     *  better as a date than "57d ago"). */
    private fun fmtTimeAgo(thenMs: Long): String {
        val then = if (thenMs > 0L) thenMs else System.currentTimeMillis()
        val secs = (System.currentTimeMillis() - then).coerceAtLeast(0L) / 1000L
        return when {
            secs < 10      -> "just now"
            secs < 60      -> "${secs}s ago"
            secs < 3600    -> "${secs / 60}m ago"
            secs < 86400   -> "${secs / 3600}h ago"
            secs < 7 * 86400 -> "${secs / 86400}d ago"
            else -> java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault()).format(java.util.Date(then))
        }
    }

    private fun fmtBytes(bytes: Long): String = when {
        bytes < 0    -> "?"
        bytes < 1024 -> "${bytes} B"
        bytes < 1024 * 1024 -> "${"%.1f".format(bytes / 1024.0)} KB"
        bytes < 1024 * 1024 * 1024 -> "${"%.1f".format(bytes / (1024.0 * 1024))} MB"
        else -> "${"%.2f".format(bytes / (1024.0 * 1024 * 1024))} GB"
    }

    private fun fmtElapsed(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60   -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else     -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    // Small widget helpers ------------------------------------------------------------------

    /** GOAT v2: ThumbnailEngine (media/ThumbnailEngine.kt) already existed
     *  fully built — memory+disk LRU cache, category detection, async
     *  decode off the main thread — just never actually called from here.
     *  Every row got the same generic file-type icon regardless of
     *  whether it was a photo, a video, or an APK.
     *
     *  GOAT (transfer stability pass): the tag-based guard below used to be
     *  purely defensive ("won't happen currently... but safe if that ever
     *  changes") back when a row was a persistent view tied to one
     *  Transfer for its entire on-screen lifetime. Now that Active/
     *  Received/Sent are real RecyclerViews (see TransferRowAdapter), a
     *  row IS recycled onto a different Transfer as the list scrolls, so
     *  this guard is what stops a slow decode for the PREVIOUS Transfer a
     *  recycled view showed from landing on the NEW one it's since been
     *  rebound to. */
    private fun loadThumbnailInto(thumbView: ImageView, t: TransferManager.Transfer, holder: TransferRowViewHolder? = null) {
        // Final Stabilization (§5): retire the departed decode FIRST, even
        // when this Transfer has no file path to load — the early return
        // below used to leave a previously-bound row's job running, still
        // holding a ThumbnailEngine decodeGate permit for a view that no
        // longer shows that file.
        holder?.thumbJob?.cancel()
        holder?.thumbJob = null
        val path = t.filePath ?: return
        val ext = t.fileName.substringAfterLast('.', "")
        val category = com.lumidrop.x.media.ThumbnailEngine.categoryOf(ext)
        val id = t.id
        // v1.0 (§wrong-thumbnails — ROOT CAUSE): the bind identity rides a
        // KEYED tag (R.id.thumb_bind), never the view's plain tag. The old
        // `thumbView.tag = id` DESTROYED the plain "thumb" tag that
        // findTagged(card, "thumb") uses to locate this very view — so on
        // every rebind after the first, findTagged found NOTHING, the
        // reset-to-generic-icon line and the fresh load were both skipped,
        // and a recycled row went on showing whatever bitmap the PREVIOUS
        // Transfer happened to have decoded ("thumbnails of other items").
        // With the keyed tag, the plain tag stays "thumb" for the view's
        // whole lifetime and the async completion still verifies it is
        // painting the row's CURRENT transfer — never a stale one.
        thumbView.setTag(R.id.thumb_bind, id)
        // Phase 5 (§5.1): cancel the row's PREVIOUS decode before launching
        // the new one — with the holder passed in from the adapter, a
        // rebind now retires the departed file's decode instead of letting
        // it finish (and hold a decodeGate permit) for a row that no longer
        // shows that file.
        holder?.thumbJob?.cancel()
        val job = viewLifecycleOwner.lifecycleScope.launch {
            val appCtx = context?.applicationContext ?: return@launch
            val bmp = com.lumidrop.x.media.ThumbnailEngine.load(appCtx, path, category, sizeDp = 40)
            if (!isAdded || bmp == null) return@launch
            if (thumbView.getTag(R.id.thumb_bind) == id) thumbView.setImageBitmap(bmp)
        }
        holder?.thumbJob = job
    }

    private fun <T : View> findTagged(parent: ViewGroup, tag: String): T? {
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            if (c.tag == tag) return c as? T
            if (c is ViewGroup) findTagged<T>(c, tag)?.let { return it }
        }
        return null
    }

    /** Phase 5 (§5.4): buildHistoryTab's return bundle — a 4-tuple (no
     *  native Kotlin 4-tuple exists). [header] is exposed specifically so
     *  refreshReceivedHeader() can re-purpose the Received tab's header as
     *  the selection-mode action bar. */
    private class HistoryTab(
        val content: LinearLayout,
        val list: RecyclerView,
        val emptyLabel: LinearLayout,
        val header: LinearLayout,
        val listWrap: FrameLayout,
    )

    private fun buildHistoryTab(adapter: TransferRowAdapter, emptyMsg: String, emptySub: String): HistoryTab {
        val ctx = requireContext()
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            visibility = View.GONE
            // GOAT (Phase 3, item 9): same boundary hardening as
            // activeTabContent above, for the identical header sitting above
            // this container's own RecyclerView -- see that comment for
            // what this does and doesn't rule out.
            clipChildren = true
        }
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(6))
            layoutParams = lp(MATCH, WRAP)
        }
        // ── Pass-28 (#1.3): the header is built EMPTY — each tab's own ──────
        // refresh function owns every view inside it:
        //   • Received: refreshReceivedHeader (spacer + Open Received Folder +
        //     Delete Received Files, or the selection action bar);
        //   • History:  refreshSentHeader (the live total item count + Clear
        //     History, or the selection action bar).
        // The old generic content — the "Tap a file to open it" instructional
        // hint plus the per-tab clear button — is retired for good: the hint
        // was explicitly requested out, and the duplicated button-building
        // now lives in exactly one place per tab.
        content.addView(header)

        val emptyLabel = emptyState(emptyMsg, emptySub)
        content.addView(emptyLabel)
        val list = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            this.adapter = adapter
            clipToPadding = false
            setPadding(dp(6), 0, dp(6), dp(16))
            layoutParams = LinearLayout.LayoutParams(MATCH, 0, 1f)
            isNestedScrollingEnabled = true
            // Same reasoning as transferList above — see its comment.
            (itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        }
        // Pass-8 follow-up (consistent scroll fades): every Transfers
        // subtab list gets the SAME card-colored edge-fade treatment the
        // Connected Devices card uses — 18dp gradient bands over the
        // list's rims, visible only on the side(s) with content past them
        // (see wrapListWithEdgeFades below).
        val wrap = wrapListWithEdgeFades(list)
        content.addView(wrap)
        return HistoryTab(content, list, emptyLabel, header, wrap)
    }

    /** Prototype's TransferTab: filled pill (accent bg, white text/icon)
     *  when active; transparent + bordered (SurfaceDark/bg_secondary) when
     *  not. Icon/tint/background are all reapplied per-selection in
     *  refreshTabStyles() -- this just establishes the label/sizing/shape. */
    // Pass-6 (R-F — centered pill content): the tab pills used to be a single
    // TextView with a compound START drawable, which Android positions at the
    // CONTENT BOX's left edge — not next to the glyphs — so with center
    // gravity the label centered while the icon stayed glued to the pill's
    // left rim ("icons too much at the left"). A LinearLayout(icon + label)
    // with center gravity centers the ICON+LABEL GROUP as one unit — exactly
    // what a pill should look like, on every API level, with zero drawable
    // bounds gymnastics. Same height, same paddings, same ripple/selection
    // styling — only the content arrangement is fixed.
    private inner class TabPill(ctx: android.content.Context) : LinearLayout(ctx) {
        val icon: ImageView = ImageView(ctx).apply {
            // Explicit LinearLayout.LayoutParams: unqualified LayoutParams
            // inside this apply{} would resolve to View.LayoutParams (the
            // ImageView's own inherited nested class), which carries no
            // margins — marginEnd below wouldn't exist, let alone apply.
            layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).also { it.marginEnd = dp(6) }
        }
        val label: TextView = TextView(ctx).apply {
            textSize = 12f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(10), dp(4), dp(10))
            isClickable = true; isFocusable = true
            layoutParams = FrameLayout.LayoutParams(MATCH, WRAP)
            addView(icon); addView(label)
        }
    }

    private fun tabButton(label: String, onClick: () -> Unit) = TabPill(requireContext()).apply {
        this.label.text = label
        setOnClickListener { onClick() }
    }

    /** Wraps a tab pill with a small circular count badge pinned to its
     *  top-end corner, instead of the number living inside the pill's own
     *  text ("Received (3)") -- GOAT v2 asked for a proper badge shape
     *  specifically because embedded numbers can't be one: a circle sized
     *  for "3" either clips "12" or has to stop being circular for it.
     *  minWidth/minHeight plus horizontal padding is what lets this same
     *  shape hold both without looking stretched or cramped -- a true
     *  circle for one digit, a pill for two, same visual weight either
     *  way. Returns the outer wrapper (goes in tabRow, carries the
     *  weight=1f the pill used to carry directly) and the badge itself
     *  (text/visibility updated per-render). */
    private fun wrapWithBadge(pill: View): Pair<FrameLayout, TextView> {
        val ctx = requireContext()
        val wrap = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).also { it.marginEnd = dp(6) }
        }
        wrap.addView(pill)
        val badge = TextView(ctx).apply {
            textSize = 9.5f
            setTextColor(android.graphics.Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_badge)
            backgroundTintList = android.content.res.ColorStateList.valueOf(col(R.color.accent))
            minWidth = dp(16); minHeight = dp(16)
            setPadding(dp(4), 0, dp(4), 0)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(WRAP, dp(16), Gravity.TOP or Gravity.END).also {
                it.topMargin = dp(2); it.rightMargin = dp(2)
            }
        }
        wrap.addView(badge)
        return Pair(wrap, badge)
    }

    /** iconRes == null keeps a plain text pill (Retry / Open Folder / Clear
     *  History -- these read fine as words, an icon wouldn't add clarity). */
    private fun smallActionBtn(label: String, iconRes: Int?, colorRes: Int, onClick: () -> Unit) = TextView(requireContext()).apply {
        text = label; textSize = 11.5f
        setTextColor(col(colorRes))
        background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_btn_ghost)
        setPadding(dp(10), dp(7), dp(10), dp(7))
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).also { it.marginEnd = dp(6) }
        if (iconRes != null) {
            setCompoundDrawablesWithIntrinsicBounds(Widgets.icon(requireContext(), iconRes, col(colorRes)), null, null, null)
            compoundDrawablePadding = dp(5)
        }
        setOnClickListener { onClick() }
    }

    /** ── Pass-40 (#4): the tabs' compact sorting control — a small standalone ──
     *  ICON button (30dp touch target, 15dp glyph, no label, no pill, no
     *  container) in the app's new minimal up/down-arrow mark, quieter than
     *  the pills beside it and never taller than they are: the header row's
     *  height is exactly what it was. The 6dp trailing margin keeps balanced
     *  spacing against whatever follows it.
     *  ── GOAT-PASS 7 Part 2 (§6A): the click hands the BUTTON ITSELF to the ──
     *  menu — the sort menu is anchored to the icon that opened it (and
     *  end-aligned with it), never to the full-width header row, which is
     *  what previously made a right-side icon open its menu at the screen's
     *  left edge. */
    private fun sortIconBtn(onClick: (View) -> Unit) =
        Widgets.iconButton(requireContext(), R.drawable.ic_sort_arrows, col(R.color.text_secondary),
            sizeDp = 30, iconSizeDp = 15, contentDescription = "Sort").apply {
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).also { it.marginEnd = dp(6) }
            setOnClickListener { onClick(this) }
        }

    private fun iconBtn(iconRes: Int, colorRes: Int, onClick: () -> Unit) =
        Widgets.iconButton(requireContext(), iconRes, col(colorRes), sizeDp = 36, iconSizeDp = 16).apply {
            // Phase 5 (§5.2): 32dp -> 36dp rowside action targets (16dp glyph
            // instead of 15) — the transfer rows are the app's busiest small-
            // target cluster (pause/cancel/retry/dismiss sit directly beside
            // each other), and 36dp keeps the row compact while landing every
            // tap that 32dp turned into a mis-press on smaller screens.
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(36)).also { it.marginStart = dp(8) }
            setOnClickListener { onClick() }
        }

    /** Prototype's transfer-tab empty state: the same EmptyStateIllustration
     *  used on Send's Connected Devices card, isTransfer=true this time
     *  (phone -> arrow -> monitor instead of phone + monitor), a per-tab
     *  title, and — Pass-12 (Required Implementation 4) — a per-tab SUBTITLE
     *  that actually describes what belongs in THAT tab. The old single
     *  "Files you send or receive will appear here." line was shown on every
     *  tab, which read as plainly wrong on Received (a tab that holds ONLY
     *  received files). */
    private fun emptyState(title: String, subtitle: String) = LinearLayout(requireContext()).apply {
        val ctx = requireContext()
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = lp(MATCH, WRAP).also { it.topMargin = dp(28); it.bottomMargin = dp(12) }

        addView(com.lumidrop.x.ui.EmptyStateIllustration(ctx).apply { isTransfer = true },
            LinearLayout.LayoutParams(dp(120), dp(120)))
        addView(TextView(ctx).apply {
            text = title; textSize = 17f
            setTextColor(col(R.color.text_primary))
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            layoutParams = lp(WRAP, WRAP).also { it.topMargin = dp(32) }
        })
        addView(TextView(ctx).apply {
            text = subtitle
            textSize = 12f
            setTextColor(col(R.color.text_secondary))
            gravity = Gravity.CENTER
            setLineSpacing(dpf(2f), 1f)
            layoutParams = lp(WRAP, WRAP).also {
                it.topMargin = dp(8); it.leftMargin = dp(24); it.rightMargin = dp(24)
            }
        })
    }

    /** FINAL PASS 9 (PART 1): a null-safe context for bind/async paths —
     *  requireContext() throws IllegalStateException once a fragment
     *  detaches, and rotation-triggered rebinds (configChanges relayout,
     *  returning from Web Client Settings which DOES recreate the stack on
     *  some OEM builds) can land exactly there. Returns null instead. */
    private fun ctxOrNull(): android.content.Context? = context

    private fun col(res: Int) = ContextCompat.getColor(requireContext(), res)
    private val MATCH get() = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP  get() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
}
