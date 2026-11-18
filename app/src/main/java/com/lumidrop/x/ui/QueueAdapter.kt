package com.lumidrop.x.ui

import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.lumidrop.x.R
import com.lumidrop.x.media.ThumbnailEngine
import com.lumidrop.x.server.LumiDropServer
import com.lumidrop.x.transfer.FileTreeNode
import com.lumidrop.x.transfer.PreviewItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One queue row is either a not-yet-sent staged item (removable from the
 *  local list only) or a live item already on the server's quick-share
 *  queue (removable via the server, which un-publishes it for connected
 *  devices too). */
sealed class QueueRow {
    data class Staged(val item: PreviewItem) : QueueRow()
    data class Live(val item: LumiDropServer.QSItem, val consumed: Int, val expected: Int) : QueueRow()
}

/**
 * RecyclerView adapter for the Quick Share queue.
 *
 * Replaces a plain ScrollView(maxHeight)+LinearLayout that had the same
 * nested-scrolling problem Recent Files did, PLUS a manual "...and N more"
 * text cap at 20 items that made anything past the 20th item completely
 * unreachable -- there was no way to remove a queued file once you had more
 * than 20 staged. RecyclerView removes the need for a cap at all: view
 * recycling means the list handles any length cheaply, so every item stays
 * scrollable to and removable regardless of how many are queued.
 */
class QueueAdapter(
    // §static-format-icons: the queue's per-format glyph resolver is now a
    // DRAWABLE factory (icon+tint resolved together by the shared
    // FormatIcons object at the SendFragment call site) instead of the old
    // bare drawable-id fileIconRes — every queued file's identity renders
    // in its family color (pdf red, sheet green, archive amber…), not one
    // undifferentiated gray.
    private val formatIcon: (String) -> android.graphics.drawable.Drawable,
    private val onRemoveStaged: (PreviewItem) -> Unit,
    private val onRemoveLive: (LumiDropServer.QSItem) -> Unit,
    // GOAT (Part 2 completion — folder expansion system): excludes one
    // path, or an entire subfolder prefix, from a QUEUED folder item
    // before it's ever sent. The [String] is a natural (pre-dedup-rename)
    // relative path exactly as PreviewItem.excludedChildPaths already
    // expects — see its own doc comment — so this plugs straight into
    // plumbing (NativePushClient.flattenResolved / LumiDropServer.
    // addZipNode) that already fully respects it; only the UI control to
    // actually reach it was missing until now.
    private val onExcludeChild: (PreviewItem, String) -> Unit,
    // ── Pass-33 (#3): opens ONE child of an expanded staged folder — a file
    // at [String] natural relative path inside [PreviewItem]'s tree.
    // SendFragment routes it exactly like a top-level queued item: media
    // opens the app's own previewer (with the folder's previewable children
    // as the navigation), everything else goes to Android's ACTION_VIEW.
    private val onOpenChild: (PreviewItem, String) -> Unit,
    // ── Pass-32 (#2): the row's OPEN handler, replacing the Pass-29 eye ──────
    // button. Receives the row whose CONTAINER was tapped (resolved at tap
    // time, never a captured position) — SendFragment routes it: previewable
    // media opens the app's own media previewer on the whole queue's media
    // exactly as the eye did; every other openable file type goes to
    // Android's own ACTION_VIEW flow through the installed app for its MIME
    // type. The eye button itself is gone — tapping the item IS the open
    // affordance now.
    private val onOpenItem: (QueueRow) -> Unit,
    // ── Pass-35 (#7): opens a queued TEXT message in the dedicated text ──────
    // viewing modal — the queue's own read path for textContent items, which
    // had no open affordance at all until now. Receives the staged item; the
    // caller renders item.textContent verbatim.
    private val onOpenText: (PreviewItem) -> Unit,
    // Phase 5 (§5.2 — real thumbnails in the transfer staging queue): the
    // scope row thumbnails decode on. The queue is the transfer surface
    // the person stares at while staging a send, and every row was a
    // generic file-type glyph even when the item is a photo/video/song
    // sitting at a known path — the SAME information the Transfers tab
    // already shows for live transfers. Passed in (rather than created
    // here) so the owner controls the lifetime: SendFragment hands over
    // its viewLifecycleOwner scope, matching exactly how that fragment
    // already scopes its other row-work.
    private val thumbScope: CoroutineScope? = null,
) : RecyclerView.Adapter<QueueAdapter.VH>() {

    private val rows = mutableListOf<QueueRow>()

    // GOAT v2 (item 4/5): expand state and loaded-children cache both live
    // HERE, on the adapter, keyed by a stable per-item identity — never on
    // the ViewHolder itself. A ViewHolder gets reused for a totally
    // different row the moment it scrolls off-screen; anything about
    // "is THIS folder open" stored on the View instead of the data would
    // either vanish or, worse, leak onto whatever unrelated row gets
    // recycled into that same ViewHolder next.
    //
    // GOAT v4 (accordion, per the person's on-device report): only ONE
    // queued folder may be expanded at a time — expanding another collapses
    // the first automatically. A single key (plus the live ViewHolder
    // currently displaying it, for the in-place collapse) replaces the old
    // Set; the childrenCache below is still per-key and unaffected.
    private var expandedKey: String? = null
    private var expandedHolder: VH? = null
    // Phase 8 (§8.3/§8.4): bounded LRU for the walked-children cache. The
    // old plain map never evicted — every folder ever expanded kept its
    // full (relPath, size) list for the adapter's lifetime, so a long
    // picker session across many large folders grew without limit.
    // LinkedHashMap(accessOrder=true) gives real LRU semantics with one
    // synchronized guard point (touched from the walk executor's results
    // and the main thread's reads alike); 24 entries is far beyond the
    // accordion's single-expanded-folder interaction model ever needs.
    private val childrenCache = object : LinkedHashMap<String, List<Pair<String, Long>>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Pair<String, Long>>>): Boolean {
            return size > 24
        }
    }
    private fun cacheGet(key: String): List<Pair<String, Long>>? = synchronized(childrenCache) { childrenCache[key] }
    private fun cachePut(key: String, list: List<Pair<String, Long>>) { synchronized(childrenCache) { childrenCache[key] = list } }
    private fun keyOf(item: PreviewItem) = item.uri ?: item.path ?: item.name

    fun submit(newRows: List<QueueRow>) {
        // ── Pass-29 Part 1 (#6 — DiffUtil, not a signature + ────────────────────
        // notifyDataSetChanged). The old gate built one big content-signature
        // string on EVERY call — per row a string template over
        // name/folder-ness/uri/path PLUS excludedChildPaths.sorted() (an
        // allocation and a sort per row, per submit) — and, when it differed,
        // notifyDataSetChanged()'d: every attached row fully rebound
        // (thumbnails cancelled and reloaded, any expanded folder box torn
        // down) and the whole list invalidated mid-scroll, twice per second,
        // forever. With the reported ~1,858-file queue that was tens of
        // milliseconds of string churn plus a full rebind storm on every
        // 500ms tick — the "queue takes seconds to appear, then scrolling is
        // extremely laggy" report. DiffUtil against per-row identities
        // (occurrence-disambiguated so the same file genuinely staged twice
        // keeps its two rows, exactly as the full-rebind render showed)
        // produces targeted insert/remove/change notifications instead: an
        // unchanged steady state costs one cheap O(n) identity pass with ZERO
        // notifications, a single item change rebinds ONLY that row (with a
        // payload that refreshes its text without ever touching its
        // thumbnail — see the payload bind below), and inserts/removes never
        // disturb scroll position or the accordion. detectMoves=false: queue
        // order only ever changes through genuine add/remove events, so move
        // detection would be pure cost.
        if (newRows === rows) return
        val old = rows.toList()
        val oldIds = rowIdentities(old)
        val newIds = rowIdentities(newRows)
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newRows.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int) = oldIds[oldPos] == newIds[newPos]
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean {
                val a = old[oldPos]; val b = newRows[newPos]
                return when {
                    a is QueueRow.Staged && b is QueueRow.Staged ->
                        a.item.name == b.item.name && a.item.isFolder == b.item.isFolder &&
                            a.item.size == b.item.size && a.item.excludedChildPaths == b.item.excludedChildPaths &&
                            a.item.childManifest == b.item.childManifest   // Pass-33 (#1): a manifest folder's contents are part of its identity
                    a is QueueRow.Live && b is QueueRow.Live ->
                        a.consumed == b.consumed && a.expected == b.expected && a.item.name == b.item.name
                    else -> false
                }
            }
            override fun getChangePayload(oldPos: Int, newPos: Int): Any? {
                // Non-null → the payload bind below runs instead of the full
                // rebind: same item identity, only presentation fields moved.
                return CHANGE_PAYLOAD
            }
        }, false)
        rows.clear()
        rows.addAll(newRows)
        diff.dispatchUpdatesTo(this)
    }

    private object CHANGE_PAYLOAD

    /** Stable per-row identity for the diff: "s:<uri|path|name>" for staged
     *  rows, "l:<name>" for live ones, with an occurrence suffix so the same
     *  key appearing twice (the same file genuinely staged twice) keeps its
     *  two separate rows — exactly what the old full-rebind render showed. */
    private fun rowIdentities(list: List<QueueRow>): List<String> {
        val seen = HashMap<String, Int>(list.size * 2)
        val out = ArrayList<String>(list.size)
        for (r in list) {
            val base = when (r) {
                is QueueRow.Staged -> "s:" + keyOf(r.item)
                is QueueRow.Live -> "l:" + r.item.name
            }
            val n = seen[base] ?: 0
            seen[base] = n + 1
            out.add("$base#$n")
        }
        return out
    }

    inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val icon = ImageView(root.context)
        val name = TextView(root.context)
        val status = TextView(root.context)
        // Phase 5 (§5.2): this row's in-flight thumbnail decode — cancelled
        // on rebind/recycle so a fast-scrolling queue never queues decodes
        // for rows that are already gone (same discipline as the Media
        // Picker grid and the Transfers rows after this same phase).
        var thumbJob: kotlinx.coroutines.Job? = null
        val remove = Widgets.iconButton(root.context, R.drawable.ic_close,
            ContextCompat.getColor(root.context, R.color.red_stop), sizeDp = 28, iconSizeDp = 13, contentDescription = "Remove")
        val chevron = ImageView(root.context)
        // ── Pass-30 (#5): the minimal VIDEO indicator. The thumbnail slot ──────
        // is a plain ImageView, so a video's poster frame and a photo look
        // near-identical at 30dp. A quiet 11dp play glyph in a small rounded
        // scrim at the slot's bottom-right corner marks the difference at a
        // glance — the same minimal play-mark language the previewer's
        // filmstrip and the picker's video tiles already use, scaled down to
        // the slot — positioned in the corner where it never covers the
        // subject of a poster frame. It layers INSIDE the 30dp icon slot's
        // own bounds, so the row's height, the eye/remove controls beside it
        // and every touch target are untouched, and it is not clickable:
        // taps fall through to the row's own affordances exactly as before.
        val videoMark = ImageView(root.context).apply {
            setImageDrawable(Widgets.icon(root.context, R.drawable.ic_play,
                android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF)))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = root.context.dpf(4f)
                setColor(0x66000000.toInt())
            }
            setPadding(root.context.dp(2), root.context.dp(2), root.context.dp(3), root.context.dp(2))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(root.context.dp(17), root.context.dp(17)).also {
                it.gravity = Gravity.BOTTOM or Gravity.END
                it.bottomMargin = root.context.dp(2); it.marginEnd = root.context.dp(2)
            }
        }
        // ── Pass-35 (#8A): the AUDIO twin of the video mark — the same ──
        // quiet corner-mark language, a small note glyph in the same scrim,
        // so queued music (with or without artwork) is identified as audio
        // at a glance and can never read as an image thumbnail. Audio and
        // video are mutually exclusive per row, so the two marks share the
        // one corner slot without ever colliding.
        val audioMark = ImageView(root.context).apply {
            setImageDrawable(Widgets.icon(root.context, R.drawable.ic_audio,
                android.graphics.Color.argb(0xE6, 0xFF, 0xFF, 0xFF)))
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = root.context.dpf(4f)
                setColor(0x66000000.toInt())
            }
            setPadding(root.context.dp(3), root.context.dp(2), root.context.dp(3), root.context.dp(2))
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(root.context.dp(17), root.context.dp(17)).also {
                it.gravity = Gravity.BOTTOM or Gravity.END
                it.bottomMargin = root.context.dp(2); it.marginEnd = root.context.dp(2)
            }
        }

        // GOAT v3 (§3.1): the shared FolderBox component now owns this box's
        // whole construction + scroll treatment (capped wrap-height, edge
        // fades, custom scrollbar indicator, claimVerticalDrags, nested-
        // scrolling opt-out) — see FolderBox.kt's doc comment for the full
        // reasoning behind each piece. childrenBox/childrenScroll keep their
        // names and roles so every existing reference below reads unchanged.
        // ── Pass-34 (#6C): the expanded content's left inset drops from 26dp
        // to 14dp — the old indent wasted a quarter of the row's breathing
        // room on a hierarchy cue the header row above already carries; 14dp
        // keeps the nesting unambiguous while giving the child rows real
        // usable width (and it aligns with the status line's own 24→16dp
        // inset below, so the folder's header, status and children all share
        // one left rhythm).
        val folderBox = FolderBox(root.context, 14, 4, 4, 2).also {
            // ── GOAT-PASS 9 (§11 — same hardening as the Received tab's ──
            // expanded folders): both layers CONSUME clicks so a tap on the
            // box's non-row areas (padding, gaps, the breadcrumb's empty
            // spans) can never synthesize a click on the PARENT queue row
            // (which toggles the folder's expansion). Child rows, breadcrumb
            // segments and scrolling are untouched.
            it.scroll.isClickable = true
            it.content.isClickable = true
        }
        val childrenBox: LinearLayout get() = folderBox.content
        val childrenScroll: NestedScrollView get() = folderBox.scroll

        // GOAT v3 (§3.1 — scroll position across the 500ms rebinds that DO
        // still happen when the queue's content changes): which folder this
        // ViewHolder's box was last bound to, plus the scroll offset to
        // restore into it. Same-key rebind (the row still shows the same
        // folder) restores the offset instead of snapping to the top; a
        // different-or-no key (fresh/recycled ViewHolder) resets to 0 —
        // exactly the recycled-row guard childrenScroll's own doc comment
        // below has always described, now scoped to when it's actually the
        // recycled case instead of every single rebind.
        var childrenKey: String? = null
        var restoreScrollY: Int = 0

        /** ── Pass-41 (§8): the batched children-render serial. Every
         *  showChildren() bumps it; the batched appends it schedules check it
         *  before touching the box, so a re-render (subfolder toggle,
         *  exclusion change, a rebind to a different folder) supersedes every
         *  in-flight batch cleanly — no late appends, no duplicate rows, no
         *  rows landing in a recycled holder's stale box. */
        var childRowSerial: Int = 0

        // GOAT (continuation brief §2/§3 -- third instance of the same
        // "+X more" dead end, found while checking whether the Queue's own
        // folder-expansion view had the identical pattern already fixed
        // elsewhere for TransfersFragment/SendFragment): identical
        // technique (see TransfersFragment.renderGroupChildren's doc
        // comment) -- childrenBox now lives inside a bounded (was fixed-height,
        // now wrap-up-to-cap — FolderBox) NestedScrollView instead of growing
        // this row to fit every child, so showChildren's old 50-item cap and
        // "+ N more" text are removable; every child is genuinely reachable
        // by scrolling this box. Same 280dp bound as the other two instances,
        // now a ceiling rather than a constant height.
        // Unlike SendFragment's buildIncomingFolderRow (a fresh, never-
        // recycled View tree every rebuild), this VH is a genuinely
        // RECYCLED RecyclerView ViewHolder -- the same childrenScroll
        // instance gets rebound to a completely different queued item as
        // the list scrolls, so its scroll position MUST be reset on every
        // bind to a DIFFERENT folder (see showChildren + childrenKey) or a
        // recycled row could open a different, possibly shorter, folder
        // already scrolled to wherever the PREVIOUS folder shown in this
        // same ViewHolder was left.
        // GOAT (Phase 2 regression fix): this box sits inside a
        // RecyclerView row that's itself inside fragment_send.xml's own
        // page-level NestedScrollView -- three levels deep. The outer
        // NestedScrollView DOES implement the nested-scrolling protocol
        // correctly (unlike SendFragment's incoming-sheet case), but
        // that's a COOPERATIVE protocol: when the page has real content of
        // its own to scroll, it can still claim a drag that started in
        // here before this box gets a turn. See Widgets.claimVerticalDrags's
        // own doc comment for the full reasoning.
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ctx.dp(10), ctx.dp(7), ctx.dp(10), ctx.dp(7))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_secondary)
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = ctx.dp(5)
            }
        }
        val vh = VH(root)
        val topRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // Phase 5 (§5.2): 18dp -> 30dp icon slot. The old 18dp glyph-only
        // size stays correct for the vector fallbacks; the new real
        // thumbnails need enough room to be recognizable at arm's length,
        // and 30dp keeps the single-line row compact while making an
        // identifiable preview possible at all. Rounded corners match
        // bg_thumb_small's shape so a thumbnail sits in the same visual
        // language as every other preview surface in the app.
        // ── Pass-30 (#5): the slot is now a thin FrameLayout WRAPPER around ──
        // the unchanged ImageView — same 30dp footprint, same margin, and the
        // ImageView itself keeps its exact size/scale/background/clip (byte-
        // identical rendering for images, folders, APKs and every other
        // glyph), while the wrapper can host the minimal video indicator
        // INSIDE the slot's own bounds. Nothing about the row's height,
        // spacing or any control beside it changes.
        val iconWrap = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(30), ctx.dp(30)).also { it.marginEnd = ctx.dp(10) }
        }
        vh.icon.layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        vh.icon.scaleType = ImageView.ScaleType.CENTER_CROP
        vh.icon.background = ContextCompat.getDrawable(ctx, R.drawable.bg_thumb_small)
        vh.icon.clipToOutline = true
        iconWrap.addView(vh.icon)
        iconWrap.addView(vh.videoMark)
        iconWrap.addView(vh.audioMark)
        vh.name.apply {
            textSize = 12f; setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        vh.chevron.apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(16), ctx.dp(16)).also { it.marginEnd = ctx.dp(4) }
            visibility = View.GONE
        }
        // ── Pass-32 (#2): the eye's former slot is GONE — the row container ────
        // itself is the open affordance now (see the bind wiring below), so the
        // control cluster at the row's end is just the remove glyph and the
        // row's height/spacing are exactly what they always were.
        vh.videoMark.visibility = View.GONE
        vh.audioMark.visibility = View.GONE
        topRow.addView(iconWrap); topRow.addView(vh.name); topRow.addView(vh.chevron); topRow.addView(vh.remove)
        root.addView(topRow)
        vh.status.apply {
            textSize = 10f; alpha = 0.7f
            setPadding(ctx.dp(16), ctx.dp(1), 0, 0)
        }
        root.addView(vh.status)
        root.addView(vh.folderBox.root)
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.isEmpty()) { onBindViewHolder(holder, position); return }
        // ── Pass-29 Part 1 (#6): payload bind — areItemsTheSame matched, so ─────
        // this is the SAME item with changed presentation fields (a Live row's
        // consumed/expected counters ticking up, a staged row's name).
        // Refresh ONLY the text fields; never cancel/reload the thumbnail,
        // never tear down the expand state or the folder box — the exact churn
        // that made the ~1,858-file queue laggy under the old every-tick full
        // rebind. A staged FOLDER is the one exception: its expanded box
        // renders the exclusion set, so a content change needs the full
        // rebind (folders carry a vector icon — no thumbnail to churn).
        val row = rows.getOrNull(position) ?: return
        when {
            row is QueueRow.Staged && row.item.isFolder -> onBindViewHolder(holder, position)
            row is QueueRow.Staged -> holder.name.text = row.item.name
            row is QueueRow.Live -> {
                holder.name.text = row.item.name
                bindLiveStatus(holder, row)
            }
        }
    }

    /** The Live row's "Waiting n/m received / All received" line — shared by
     *  the full bind and the payload bind so the two can never drift. */
    private fun bindLiveStatus(holder: VH, row: QueueRow.Live) {
        val ctx = holder.root.context
        if (row.expected > 0) {
            val done = row.consumed >= row.expected
            holder.status.visibility = android.view.View.VISIBLE
            holder.status.text = if (done) "All received" else "Waiting  ${row.consumed}/${row.expected} received"
            holder.status.setTextColor(ContextCompat.getColor(ctx, if (done) R.color.green else R.color.text_muted))
            if (done) {
                holder.status.setCompoundDrawablesWithIntrinsicBounds(
                    Widgets.icon(ctx, R.drawable.ic_check, ContextCompat.getColor(ctx, R.color.green)), null, null, null)
                holder.status.compoundDrawablePadding = ctx.dp(4)
            } else {
                holder.status.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
            }
        } else {
            holder.status.visibility = android.view.View.GONE
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ctx = holder.root.context
        // Capture the scroll position this ViewHolder's box is currently
        // holding BEFORE the reset below clears it — the same-folder
        // rebind path in showChildren restores it (see VH.childrenKey).
        val incomingRow = rows[position]
        val incomingKey = (incomingRow as? QueueRow.Staged)?.takeIf { it.item.isFolder }?.let { keyOf(it.item) }
        holder.restoreScrollY = if (incomingKey != null && holder.childrenKey == incomingKey) holder.childrenScroll.scrollY else 0
        holder.childrenKey = incomingKey
        // Every bind starts from a clean slate regardless of row type —
        // the exact guard a recycled folder row's leftover expand state
        // needs against silently carrying over onto a plain file row (or
        // a DIFFERENT folder) that happens to land in the same ViewHolder
        // next. FolderBox.setExpandedInstant also cancels any height
        // animation still running on a recycled row (the v4 deterministic
        // state machine — see FolderBox.kt's doc comment), so a recycled
        // row can never hand its successor a half-animated box.
        if (expandedHolder === holder) expandedHolder = null
        holder.chevron.visibility = View.GONE
        holder.folderBox.setExpandedInstant(false)
        holder.childrenBox.removeAllViews()
        holder.root.setOnClickListener(null)
        // ── Pass-41 (§8): a fresh bind supersedes every batched children ──
        // render this ViewHolder may still have in flight — the cleared box
        // above is the last thing an old batch may ever see (the serial check
        // in flushChildRows stops them from appending into a row that now
        // shows something else).
        holder.childRowSerial++

        when (val row = rows[position]) {
            is QueueRow.Staged -> {
                holder.name.text = row.item.name
                holder.status.visibility = android.view.View.GONE
                holder.remove.setOnClickListener { onRemoveStaged(row.item) }
                // ── Pass-32 (#2): the ROW CONTAINER is the open control. A ─────
                // folder keeps its expand affordance (bindFolderExpand owns the
                // row click there), a text message has nothing to open, and
                // every real file — media or not — opens through
                // onOpenItem, which routes previewable media to the app's
                // previewer and everything else to Android's ACTION_VIEW.
                // The remove glyph, the chevron and every control inside the
                // row are clickable children in their own right: they consume
                // their own taps, so a remove press can never fall through to
                // the row's open action, and scrolling is unaffected.
                // ── Pass-34 Part 2 (#4): AUDIO is the one exception — an
                // audio row plays IN-APP through the shared MusicPlayback
                // controller (the same player + floating overlay the Music
                // picker uses), with the queue's audio list as its playback
                // context. Tapping the current track toggles play/pause.
                // ── Pass-35 (#7): a TEXT message opens the dedicated text
                // viewing modal — reading is a first-class action on the
                // queue now, never an accidental send/edit (the row's other
                // affordances still consume their own taps).
                if (!row.item.isFolder && row.item.textContent == null) {
                    if (isAudioMedia(row.item.mime, row.item.name)) {
                        val audioKey = queueAudioKey(row.item.uri, row.item.path, row.item.name)
                        holder.root.setOnClickListener {
                            val pos = holder.bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION) {
                                playQueueAudio(holder.root.context, audioKey, queueAudioPlaylist())
                            }
                        }
                    } else {
                        holder.root.setOnClickListener {
                            val pos = holder.bindingAdapterPosition
                            if (pos != RecyclerView.NO_POSITION) rows.getOrNull(pos)?.let(onOpenItem)
                        }
                    }
                } else if (!row.item.isFolder && row.item.textContent != null) {
                    holder.root.setOnClickListener {
                        val pos = holder.bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) rows.getOrNull(pos)?.let { r -> (r as? QueueRow.Staged)?.item?.let(onOpenText) }
                    }
                }
                // ── Pass-30 (#5): the video mark rides the SAME thumbnail ──────
                // branch as the open affordance — a queued video shows the
                // quiet corner play glyph, every other row type stays clean.
                // ── Pass-35 (#8A): an AUDIO row shows the matching note ──
                // mark in the same corner language — queued music (or any
                // audio) is identified as audio at a glance, exactly like
                // the picker's thumbs, never mistaken for an image.
                holder.videoMark.visibility =
                    if (isVideoMedia(row.item.mime, row.item.name,
                            row.item.isFolder, row.item.textContent != null)) View.VISIBLE else View.GONE
                holder.audioMark.visibility =
                    if (!row.item.isFolder && row.item.textContent == null &&
                        isAudioMedia(row.item.mime, row.item.name)) View.VISIBLE else View.GONE
                if (row.item.isFolder) {
                    // GOAT v2 (item 4): a folder is never routed through
                    // fileIconRes's extension guess (there is no
                    // meaningful "extension" for a directory — and before
                    // this fix, the item's OWN name used to literally end
                    // in ".zip", which made the guess actively wrong).
                    // ic_folder + a dedicated expand affordance instead,
                    // so a folder is visually unmistakable from a file at
                    // a glance, exactly the "distinguish File / Image /
                    // Video / Folder" the spec asks for.
                    // Post-v1.0 fix: background is a TRANSPARENT rounded
                    // placeholder, never null — a null background leaves
                    // clipToOutline (set once at ViewHolder creation)
                    // clipping to an EMPTY outline on API 33+, which
                    // erases the folder glyph entirely on Android 14+.
                    // Visually identical to null (nothing painted), but
                    // the outline stays valid.
                    holder.icon.background = transparentTileBg(ctx)
                    holder.icon.setImageDrawable(Widgets.icon(ctx, R.drawable.ic_folder, ContextCompat.getColor(ctx, R.color.accent_light)))
                    bindFolderExpand(holder, row.item)
                } else if (row.item.textContent != null) {
                    // Phase 6 (§6.6): a staged text message gets the message
                    // glyph, not fileIconRes's extension guess ("Text message"
                    // has no meaningful extension — the guess landed on the
                    // generic file icon for every text item ever staged).
                    // A text item is never file-backed, so there is also
                    // deliberately no thumbnail path for it to attempt.
                    // Post-v1.0 fix: transparent placeholder, never null —
                    // same empty-outline erasure as the folder branch above.
                    holder.icon.background = transparentTileBg(ctx)
                    holder.icon.setImageDrawable(Widgets.icon(ctx, R.drawable.ic_copy, ContextCompat.getColor(ctx, R.color.accent_light)))
                } else {
                    // ── Pass-24 (#6): the item's URI rides along so a SAF-only ──
                    // pick (the Files tile — no resolvable filesystem path)
                    // still renders its real thumbnail through the shared
                    // engine's URI path instead of silently keeping the
                    // generic format glyph.
                    loadRowThumb(holder, row.item.path, row.item.uri, row.item.name)
                }
            }
            is QueueRow.Live -> {
                holder.name.text = row.item.name
                loadRowThumb(holder, row.item.filePath, row.item.contentUri, row.item.name)
                holder.remove.setOnClickListener { onRemoveLive(row.item) }
                // ── Pass-32 (#2): same open affordance as staged rows — a live ──
                // (already-sending) file opens by tap; live FOLDERS and text
                // messages have no single-file open (exactly the rows the old
                // eye excluded too).
                if (row.item.folderUri == null && row.item.folderPath == null && row.item.textContent == null) {
                    holder.root.setOnClickListener {
                        val pos = holder.bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) rows.getOrNull(pos)?.let(onOpenItem)
                    }
                }
                holder.videoMark.visibility =
                    if (isVideoMedia(row.item.mime, row.item.name,
                            row.item.folderUri != null || row.item.folderPath != null, row.item.textContent != null)) View.VISIBLE else View.GONE
                holder.audioMark.visibility =
                    if (row.item.folderUri == null && row.item.folderPath == null && row.item.textContent == null &&
                        isAudioMedia(row.item.mime, row.item.name)) View.VISIBLE else View.GONE
                bindLiveStatus(holder, row)
            }
        }
    }

    /** ── Pass-30 (#5): whether this row is a queued VIDEO — MIME first, then ──
     *  the shared extension set for generic octet-stream items; folders and
     *  text never qualify (a folder's NAME may contain any characters —
     *  including ".mp4" — so the guards must be explicit, never inferred
     *  from the name alone; and neither carries a poster frame at all).
     *  Kept deliberately SEPARATE from isPreviewableMedia: the mark is
     *  about WHAT KIND of media the thumbnail shows, not about whether the
     *  previewer can open it. */
    private fun isVideoMedia(mime: String?, name: String, isFolder: Boolean, isText: Boolean): Boolean {
        if (isFolder || isText) return false
        val m = (mime ?: "").lowercase()
        if (m.startsWith("video/")) return true
        if (m.startsWith("image/") || m.startsWith("text/") || m.startsWith("audio/")) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext.isNotEmpty() && com.lumidrop.x.server.MimeTypes.isVideo(ext)
    }

    // ── Pass-34 Part 2 (#4): in-app AUDIO PLAYBACK for the Queued Files ────────
    // section. An audio row (and an audio child inside an expanded album/
    // music folder) plays through the ONE shared MusicPlayback controller —
    // the same player and floating overlay the Music picker uses — instead of
    // bouncing out to ACTION_VIEW. Tapping the CURRENT track toggles
    // play/pause; any other track starts the queue's audio list (queue order,
    // manifest folders expanded inline at their position) from that track.

    /** Same guard shape as isVideoMedia, for audio. An unknown extension
     *  resolves through MimeTypes' extension→mime map — the same source of
     *  truth the transfer pipeline uses — rather than a second hand-kept
     *  audio-extension set that could drift from it. */
    private fun isAudioMedia(mime: String?, name: String, isFolder: Boolean = false, isText: Boolean = false): Boolean {
        if (isFolder || isText) return false
        val m = (mime ?: "").lowercase()
        if (m.startsWith("audio/")) return true
        if (m.startsWith("image/") || m.startsWith("video/") || m.startsWith("text/")) return false
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty()) return false
        return com.lumidrop.x.server.MimeTypes.getMimeType(ext).lowercase().startsWith("audio/")
    }

    private fun queueAudioKey(uri: String?, path: String?, name: String): String =
        "q:${uri ?: path ?: name}"

    private fun trackOf(key: String, uri: String?, path: String?, name: String, size: Long): MusicPlayback.Track? {
        val resolved = when {
            !uri.isNullOrBlank() -> android.net.Uri.parse(uri)
            !path.isNullOrBlank() -> android.net.Uri.fromFile(File(path))
            else -> return null
        }
        return MusicPlayback.Track(
            key = key,
            uri = resolved,
            title = name.substringBeforeLast('.').ifBlank { name },
            artist = null,
            albumId = 0L,   // unknown here — the overlay's art route reads embedded art by uri
            durationMs = 0L,
            size = size,
        )
    }

    /** The queue's audio files, in queue order — top-level staged audio rows
     *  plus the audio members of manifest folders (albums, music folders,
     *  partial file-folder picks), exclusion-filtered exactly like the
     *  expanded views. */
    private fun queueAudioPlaylist(): List<MusicPlayback.Track> {
        val out = ArrayList<MusicPlayback.Track>()
        for (row in rows) {
            val item = (row as? QueueRow.Staged)?.item ?: continue
            if (!item.isFolder && item.textContent == null && isAudioMedia(item.mime, item.name)) {
                trackOf(queueAudioKey(item.uri, item.path, item.name), item.uri, item.path, item.name, item.size)?.let { out.add(it) }
            } else if (item.isFolder && item.childManifest != null) {
                for (child in item.childManifest!!) {
                    if (child.relPath in item.excludedChildPaths) continue
                    if (item.excludedChildPaths.any { child.relPath.startsWith("$it/") }) continue
                    if (isAudioMedia(child.mime, child.relPath)) {
                        trackOf("q:${child.relPath}@${item.name}", child.uri, child.path, child.relPath, child.size)?.let { out.add(it) }
                    }
                }
            }
        }
        return out
    }

    private fun playQueueAudio(context: android.content.Context, key: String, playlist: List<MusicPlayback.Track>,
                               /** ── Pass-40 (#7): invoked when the key is in NO
                                *  playlist (a SAF tree folder, whose children
                                *  cannot be cheaply mass-resolved) — resolves
                                *  the tapped child alone and plays it as a
                                *  one-track session through the same shared
                                *  controller. */
                               resolveMissing: (() -> MusicPlayback.Track?)? = null) {
        val act = context as? android.app.Activity ?: return
        if (MusicPlayback.isCurrent(key)) MusicPlayback.toggle()
        else {
            val idx = playlist.indexOfFirst { it.key == key }
            if (idx >= 0) MusicPlayback.play(act, playlist, idx)
            else resolveMissing?.invoke()?.let { MusicPlayback.play(act, listOf(it), 0) }
        }
    }

    /** ── Pass-40 (#7): the tapped audio child's playable address — a manifest
     *  member (uri/path from the staged manifest), a real file under the staged
     *  folder's filesystem path, or a DocumentFile located by walking the SAF
     *  tree uri segment by segment (bounded by the child's own depth — the
     *  exact routing SendFragment's open path uses). Null when the source
     *  genuinely cannot be resolved (deleted mid-gesture, provider gone) —
     *  the caller then simply does nothing, never a wrong-track play. */
    private fun resolveChildTrack(context: android.content.Context, item: PreviewItem, relPath: String, size: Long): MusicPlayback.Track? {
        item.childManifest?.firstOrNull { it.relPath == relPath }?.let { ch ->
            return trackOf("q:$relPath@${item.name}", ch.uri, ch.path, relPath, ch.size)
        }
        item.path?.let { root ->
            return trackOf("q:$relPath@${item.name}", null, File(root, relPath).absolutePath, relPath, size)
        }
        item.uri?.let { treeUriStr ->
            try {
                var doc: androidx.documentfile.provider.DocumentFile? =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(treeUriStr))
                for (seg in relPath.split('/')) {
                    if (seg.isEmpty()) continue
                    doc = doc?.findFile(seg) ?: break
                }
                if (doc != null && doc.isFile) {
                    return trackOf("q:$relPath@${item.name}", doc.uri.toString(), null, relPath, size)
                }
            } catch (e: Exception) { /* fall through to the honest null */ }
        }
        return null
    }

    /** ── Pass-29 Part 2 (#2): whether this row is previewable MEDIA — an ──────
     *  image or video file, the only rows the eye control appears on. MIME
     *  first (staged picker items and live queue items both carry a real
     *  type), with the app's shared MimeTypes extension sets as the fallback
     *  for generic octet-stream items. Folders and text messages never
     *  qualify; vector-only "images" the previewer cannot honestly decode
     *  (SVG/ICO) are excluded too so a tap never opens a page that can
     *  only end in the error label. INTERNAL, not private: SendFragment's
     *  queue-open router re-runs the EXACT same predicate over the rows
     *  snapshot to decide which taps go to the previewer (and build its
     *  navigation list), so a row can never be routed to the preview the
     *  preview then fails to include. */
    internal fun isPreviewableMedia(mime: String?, name: String, isFolder: Boolean, isText: Boolean): Boolean {
        if (isFolder || isText) return false
        val m = (mime ?: "").lowercase()
        if (m.startsWith("image/") || m.startsWith("video/")) return m != "image/svg+xml"
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isEmpty() || ext == "svg" || ext == "ico") return false
        return com.lumidrop.x.server.MimeTypes.isImage(ext) || com.lumidrop.x.server.MimeTypes.isVideo(ext)
    }

    /** ── Pass-29 Part 2 (#2): the rows exactly as the list shows them right ──
     *  now — the queue-ordered snapshot the eye control's preview builds its
     *  navigation from (SendFragment maps it to the previewer's media list
     *  1:1, top of the queue first). */
    fun currentRows(): List<QueueRow> = rows.toList()

    // Phase 5 (§5.2): shared thumbnail-or-vector binder for queue rows.
    // Resets to the file-type vector first (a recycled row must never keep
    // the previous item's preview), then — when a real filesystem path
    // exists AND a decode scope was supplied — swaps in the actual preview
    // asynchronously with the same tag-guard + per-holder job cancellation
    // discipline every other thumbnail surface uses. Folders and text items
    // never reach here; undecodable types keep the vector.
    //
    // ── Pass-24 (#6 — the Files tile's missing thumbnails, root fix) ─────────
    // The old binder hard-required a real filesystem path: an item picked
    // through the system file picker (the Files tile) usually arrives as a
    // SAF content:// URI with no resolvable path, so images/videos/apps
    // staged from Files ALWAYS showed the generic format glyph even though
    // the very same file picked through the Media or Apps tile rendered its
    // thumbnail fine. The binder now falls back to the item's URI through
    // the SAME shared ThumbnailEngine the dedicated tiles use — images and
    // videos via the provider thumbnailer, APKs via the bounded cache-dir
    // spool — never a second, parallel implementation. The real-path branch
    // keeps priority (it also feeds the disk cache tier); the generic glyph
    // remains only the genuine no-thumbnail fallback.
    private fun loadRowThumb(holder: VH, path: String?, uri: String?, displayName: String) {
        val ctx = holder.root.context
        holder.thumbJob?.cancel()
        holder.thumbJob = null
        holder.icon.background = ContextCompat.getDrawable(ctx, R.drawable.bg_thumb_small)
        holder.icon.setImageDrawable(formatIcon(displayName.substringAfterLast('.')))
        val scope = thumbScope ?: return
        // Final Stabilization (§5): the File.exists() stat moved OFF the
        // main thread (it ran inside onBindViewHolder — a disk stat per
        // bind is exactly the class of work that stutters fast scrolls on
        // slow storage), and the async guard tag is keyed by the item's
        // address (path, else uri) ONLY: a recycled-position suffix used to
        // invalidate a perfectly valid in-flight decode whenever the same
        // file rebound at a different position, forcing a redundant
        // re-decode that read as thumbnail flicker.
        val category = ThumbnailEngine.categoryOf(displayName.substringAfterLast('.', ""))
        if (category == ThumbnailEngine.FileCategory.GENERIC || category == ThumbnailEngine.FileCategory.DOC) return
        if (path == null && uri == null) return
        val tag = "qthumb:${path ?: uri}"
        holder.icon.tag = tag
        holder.thumbJob = scope.launch {
            val realPath = withContext(Dispatchers.IO) { path?.takeIf { File(it).exists() } }
            val bmp = if (realPath != null) {
                ThumbnailEngine.load(ctx, realPath, category, sizeDp = 30)
            } else if (uri != null) {
                // SAF-only pick — the shared engine's URI path (never a
                // second implementation at the call site).
                ThumbnailEngine.loadFromUri(ctx, android.net.Uri.parse(uri),
                    isVideo = category == ThumbnailEngine.FileCategory.VIDEO,
                    sizeDp = 30,
                    isApk = category == ThumbnailEngine.FileCategory.APK)
            } else null
            if (bmp != null && holder.icon.tag == tag) holder.icon.setImageBitmap(bmp)
        }
    }

    // GOAT (Part 1 correction — instant incoming/queue expand): bounded
    // parallelism for walkChildren below, matching
    // NativePushClient.MAX_CONCURRENT_METADATA_FETCHES both in value and in
    // reasoning -- these are near-pure I/O-wait SAF/filesystem calls, not
    // CPU work, so this can and should run well above the device's core
    // count without any real contention.
    private companion object {
        const val MAX_CONCURRENT_WALK_THREADS = 16

        /** Post-v1.0 fix: a null-safe stand-in for `background = null` on the
         *  icon slot — a fully transparent rounded square. Provides the
         *  non-empty outline clipToOutline needs (see the folder/text bind
         *  branches) while painting exactly nothing. Fresh instance per
         *  call, same discipline as Widgets' drawable builders — never a
         *  shared mutated constant state. */
        fun transparentTileBg(ctx: android.content.Context): android.graphics.drawable.GradientDrawable =
            android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = ctx.dpf(8f)
                setColor(android.graphics.Color.TRANSPARENT)
            }
        // Phase 8 (§8.4 thread-stack hardening): ONE process-lifetime walk
        // executor + ONE shared ForkJoinPool replace the old per-expansion
        // raw Thread + per-walk new ForkJoinPool(16). Rapidly toggling
        // several different folders' expansion used to stack N unmanaged
        // threads each owning its own 16-thread pool — up to 16N threads,
        // none cancellable, each pinning its ViewHolder until its walk
        // finished. The executor bounds DISPATCHED walks to 2 concurrent
        // (further walks queue behind them — a folder toggle that arrives
        // while busy simply waits its turn instead of exploding the thread
        // count), and the shared pool keeps the intra-walk SAF fan-out at
        // the same 16-wide parallelism a single walk always had.
        val walkExecutor = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
            Thread(r, "wsx-queue-walk").apply { isDaemon = true }
        }
        val sharedWalkPool = java.util.concurrent.ForkJoinPool(MAX_CONCURRENT_WALK_THREADS)
    }

    /** GOAT v2 (item 4/5): tap-to-expand for a queued folder — shows its
     *  real contents (relative paths + sizes) instead of leaving a folder
     *  as an opaque single line. Children are walked off the main thread
     *  the first time a given folder is expanded (real folders can hold
     *  thousands of files; walking that synchronously in onBindViewHolder
     *  would jank the whole list) and cached from then on by the same key
     *  the expanded key itself uses, so re-collapsing and re-expanding — or
     *  simply scrolling the row off-screen and back — never re-walks the
     *  filesystem/SAF tree a second time.
     *
     *  GOAT (Part 2 completion): per-child removal (individual files, or
     *  an entire subfolder at once) is wired up in showChildren via
     *  onExcludeChild — the excludedChildPaths plumbing itself already
     *  fully respected removal (see PreviewItem/flattenResolved), this is
     *  what gives the user an actual control to reach it from. */
    private fun bindFolderExpand(holder: VH, item: PreviewItem) {
        val ctx = holder.root.context
        val key = keyOf(item)
        holder.chevron.visibility = View.VISIBLE
        val expanded = expandedKey == key
        holder.chevron.setImageDrawable(Widgets.icon(ctx, R.drawable.ic_chevron_right, ContextCompat.getColor(ctx, R.color.text_muted)))
        holder.chevron.rotation = if (expanded) 90f else 0f
        if (expanded) {
            expandedHolder = holder
            renderChildren(holder, item, key)
            holder.folderBox.setExpandedInstant(true)
        }
        // (Pass-26 #7: the box's bottom Collapse pill — and with it the
        // pill-toggle wiring below — is gone; the row header's own click,
        // wired right here, is the one expand/collapse path.)
        holder.root.setOnClickListener { toggleFolderExpand(holder, item, key) }
    }

    /** GOAT v4 — the one expand/collapse toggle path (header tap and the
     *  FolderBox Collapse pill both land here). Accordion semantics: at
     *  most one queued folder is expanded at a time; expanding a folder
     *  collapses whichever folder was expanded first, in place and
     *  animated, so the queue never turns into a stack of tall boxes.
     *  Geometry runs through FolderBox's deterministic animator — no
     *  TransitionManager anywhere on this path (see FolderBox.kt's doc
     *  comment for why that class of framework-mediated state was
     *  removed: interrupted transitions were the on-device-confirmed
     *  "container keeps its increased height after collapse" bug). */
    private fun toggleFolderExpand(holder: VH, item: PreviewItem, key: String) {
        val nowExpanded = expandedKey != key
        if (nowExpanded) {
            val prevKey = expandedKey
            val prevHolder = expandedHolder
            expandedKey = key
            // Accordion: collapse the previously-expanded folder's row in
            // place — guarded on the holder still actually showing that
            // same folder (childrenKey), so a recycled/rebound holder can
            // never be collapsed out from under unrelated content.
            if (prevHolder != null && prevHolder !== holder &&
                prevKey != null && prevKey != key && prevHolder.childrenKey == prevKey) {
                FolderBox.animateChevron(prevHolder.chevron, expanded = false)
                prevHolder.folderBox.collapse()
            }
            expandedHolder = holder
            FolderBox.animateChevron(holder.chevron, expanded = true)
            renderChildren(holder, item, key)
            holder.folderBox.expand()
        } else {
            expandedKey = null
            if (expandedHolder === holder) expandedHolder = null
            // In-place collapse (no rebind runs on this path), so remember
            // the box's offset HERE — re-expanding without an intervening
            // rebind restores it via showChildren.
            holder.restoreScrollY = holder.childrenScroll.scrollY
            FolderBox.animateChevron(holder.chevron, expanded = false)
            holder.folderBox.collapse()
        }
    }

    private fun renderChildren(holder: VH, item: PreviewItem, key: String) {
        val ctx = holder.root.context
        // ── Pass-36 Part 2 (#4): the walk cache is keyed by row identity AND ────
        // the item's hidden eligibility — the same folder staged once with and
        // once without hidden children must never reuse the other's listing.
        val walkCacheKey = "$key|hid=${item.includeHiddenChildren}"
        val cached = cacheGet(walkCacheKey)
        if (cached != null) {
            showChildren(holder, item, cached)
            return
        }
        holder.childrenBox.addView(TextView(ctx).apply {
            text = "Loading…"; textSize = 11f; setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
        })
        val boundPosition = holder.bindingAdapterPosition
        // Phase 8 (§8.4): the walk runs on the shared bounded executor (see
        // the companion's doc) instead of an unmanaged per-expansion Thread.
        walkExecutor.execute {
            val list = try { walkChildren(ctx, item) } catch (e: Exception) { emptyList() }
            cachePut(walkCacheKey, list)
            holder.root.post {
                // Only touch the view if it's still showing the SAME
                // folder — position alone isn't quite enough to rule out
                // recycling having handed this ViewHolder to a different
                // row at the same index while this walk was in flight, so
                // the actual row identity at that position is checked too.
                val freshItem = (rows.getOrNull(boundPosition) as? QueueRow.Staged)?.item
                val stillSameRow = holder.bindingAdapterPosition == boundPosition &&
                    freshItem != null && keyOf(freshItem) == key
                if (stillSameRow && expandedKey == key) {
                    // GOAT v4: the walk finishing swaps "Loading…" for the
                    // real tree; re-running expand() animates the box's
                    // growth from its current (Loading-height) size to the
                    // new content target — deterministic and interruption-
                    // safe by construction (FolderBox owns the animation;
                    // there is no transition to interrupt).
                    showChildren(holder, freshItem!!, list)
                    holder.folderBox.expand()
                }
            }
        }
    }

    /** GOAT (Part 2 completion — folder expansion system): the piece the
     *  class doc comment above used to flag as missing. Groups the flat
     *  (relPath, size) list into top-level subfolders (shown as one
     *  removable group each — tapping its [X] excludes the WHOLE
     *  subfolder via [onExcludeChild], which flattenResolved now honors
     *  for a directory's own path, not just individual files) plus
     *  loose root-level files (each individually removable). Re-applies
     *  [PreviewItem.excludedChildPaths] on every call rather than caching
     *  a filtered view — the walk result cached by the caller is the
     *  expensive, rarely-changing part (the real disk contents); which of
     *  those the user has excluded changes far more often and is cheap to
     *  re-filter fresh every time.
     *
     *  ── Pass-39 (#4): RECURSIVE navigation. Subfolders are no longer a ─────
     *  display-only dead end: every subfolder row carries its own chevron and
     *  expands IN PLACE — its own subfolders and files render beneath it,
     *  indented, to any depth, each level with the same expand/remove/open
     *  affordances. Expansion state lives in [expandedChildPaths] (keyed by
     *  the staged folder + the subfolder's path), so it survives the 500ms
     *  poll rebinds exactly like the box's own expand state. Collapsing a
     *  subfolder's chevron is the "navigate back to the parent" control;
     *  the header row collapses the whole tree. Indentation steps 12dp per
     *  level, capped at 4 steps — deep trees stay readable, never shrunken.
     *  ── Pass-39 (#6): every FILE row at every depth gets the same open
     *  affordance as the top level — audio plays in-app through the shared
     *  controller with the folder's full audio playlist (nested members
     *  included) as its context, everything else opens through onOpenChild.
     */
    private fun showChildren(holder: VH, item: PreviewItem, children: List<Pair<String, Long>>) {
        val ctx = holder.root.context
        holder.childrenBox.removeAllViews()
        // GOAT v4: visibility/height are FolderBox-owned now — the caller's
        // expand()/setExpandedInstant() call is what shows the box; a direct
        // write here could fight the deterministic animator.
        // GOAT v3 (§3.1 — scroll preservation): back to the top only for a
        // genuinely FRESH folder in this ViewHolder (different/no key — see
        // VH.childrenKey); a same-folder re-render (queue content changed,
        // 500ms poll, or a nested subfolder toggled — Pass-39 #4) restores
        // the offset the box was already scrolled to.
        holder.childrenScroll.scrollTo(0, holder.restoreScrollY)
        if (children.isEmpty()) {
            holder.childrenBox.addView(TextView(ctx).apply {
                text = "Empty folder"; textSize = 11f; setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            })
            return
        }
        val excluded = item.excludedChildPaths
        val visible = children.filter { (path, _) -> path !in excluded && excluded.none { ex -> path.startsWith("$ex/") } }
        if (visible.isEmpty()) {
            holder.childrenBox.addView(TextView(ctx).apply {
                text = "All contents removed"; textSize = 11f; setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            })
            return
        }
        // ── Pass-39 (#6) + Pass-40 (#7): ONE playback context for every ──
        // audio row in this tree, at any depth — the staged folder's full
        // audio manifest (nested relPaths included, exclusions applied), in
        // manifest order. The keys match queueAudioPlaylist()'s nested keys
        // exactly, so a track started from inside "Artist/Album/" is the
        // same session the queue's own audio rows produce.
        // ── Pass-40 (#7): the ROOT CAUSE of "audio inside expanded folders
        // doesn't play" was here — a PHYSICAL folder (staged from the Files
        // tile, no childManifest) produced an EMPTY playlist, so a nested
        // audio row's tap resolved index -1 and silently did nothing. The
        // playlist now derives from the WALK result for path-backed folders
        // (every audio child, real File uri, the same "q:<relPath>@<name>"
        // keys), so prev/next walk the folder's whole audio set exactly like
        // a manifest folder's do. A SAF tree-uri folder cannot cheaply mint
        // child uris for the whole set (each is a provider round-trip); its
        // audio rows resolve the TAPPED child on demand instead (see
        // renderChildLevel) and play it with the same shared controller.
        val manifestAudio = item.childManifest.orEmpty()
            .filter { it.relPath !in excluded && excluded.none { ex -> it.relPath.startsWith("$ex/") } }
            .filter { isAudioMedia(it.mime, it.relPath) }
            .mapNotNull { ch -> trackOf("q:${ch.relPath}@${item.name}", ch.uri, ch.path, ch.relPath, ch.size) }
        val folderPlaylist: List<MusicPlayback.Track> = if (manifestAudio.isNotEmpty()) manifestAudio else {
            val rootPath = item.path
            if (rootPath != null) children
                .filter { (path, _) -> path !in excluded && excluded.none { ex -> path.startsWith("$ex/") } }
                .filter { (path, _) -> isAudioMedia(null, path) }
                .mapNotNull { (path, size) ->
                    trackOf("q:$path@${item.name}", null, File(rootPath, path).absolutePath,
                        path.substringAfterLast('/'), size)
                }
            else emptyList()   // SAF tree folder: per-tap resolution below
        }
        val mimeByPath = HashMap<String, String?>()
        item.childManifest.orEmpty().forEach { mimeByPath[it.relPath] = it.mime }
        // ── Pass-40 (#6): the folder-location breadcrumb. Recursive expansion ──
        // lets a tree grow deep fast; this one compact row (rendered only
        // while a nested subfolder is actually open) says exactly where the
        // view is — the staged folder's own name, then every ancestor of the
        // deepest open branch, with the CURRENT folder accented at the end.
        // Every PARENT segment is a one-tap "go back up": it collapses that
        // level's descendants (the whole branch below it, across the folder —
        // depth-gated, so unrelated shallower state is untouched) and
        // re-renders instantly from the cached walk. Nothing about the
        // header row, the children's sizes or the box's height changes; the
        // row is ~22dp and only exists mid-navigation.
        renderChildBreadcrumb(holder, item, children)
        // ── Pass-41 (§8): BATCHED rendering. The old code constructed every
        // child row synchronously right here — a directory with 1,800 files
        // built ~9,000 views in ONE main-thread block, the exact
        // "expansion freezes the app at the opening stage" report. The rows
        // are now COLLECTED as builders (document order preserved by the
        // collector) and flushed by flushChildRows: the first 80 land in the
        // opening frame (the box is capped at ~280dp ≈ a dozen visible rows,
        // so it is visually complete at once), the rest append in 160-row
        // batches on successive frames. The serial guards every batch: any
        // re-render or rebind supersedes the tail, so nothing late can land
        // in a superseded or recycled box.
        val serial = ++holder.childRowSerial
        val builders = ArrayList<() -> Unit>(visible.size)
        renderChildLevel(holder, item, visible, folderPlaylist, mimeByPath,
            prefix = "", depth = 0, children = children, out = builders)
        flushChildRows(holder, serial, builders)
    }

    /** ── Pass-41 (§8): flushes the collected child-row builders in responsive ──
     *  batches. The opening frame renders the first 80 rows synchronously;
     *  the remainder append in 160-row batches on later frames, in order,
     *  each batch checking the render serial first. Failure of a single
     *  builder is contained (one malformed row never takes the tree down). */
    private fun flushChildRows(holder: VH, serial: Int, pending: List<() -> Unit>) {
        if (pending.isEmpty()) return
        var i = 0
        val first = minOf(80, pending.size)
        while (i < first) {
            try { pending[i]() } catch (e: Exception) { /* one row never breaks the tree */ }
            i++
        }
        fun nextBatch() {
            if (holder.childRowSerial != serial) return   // superseded — stop touching this box
            val end = minOf(i + 160, pending.size)
            while (i < end) {
                try { pending[i]() } catch (e: Exception) { /* one row never breaks the batch */ }
                i++
            }
            if (i < pending.size) holder.root.post { nextBatch() }
        }
        if (i < pending.size) holder.root.post { nextBatch() }
    }

    /** ── Pass-40 (#6): the compact location row for an expanded queued folder
     *  (see showChildren). No-op while no nested subfolder is open. */
    private fun renderChildBreadcrumb(holder: VH, item: PreviewItem, children: List<Pair<String, Long>>) {
        val ctx = holder.root.context
        val keyPrefix = "${keyOf(item)}//"
        val deepest = expandedChildPaths
            .filter { it.startsWith(keyPrefix) }
            .map { it.removePrefix(keyPrefix) }
            .maxByOrNull { it.count { c -> c == '/' } } ?: return
        val parts = deepest.split('/')
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun collapseToDepth(depth: Int) {
            // Keep every expanded subfolder at [depth] or shallower; the
            // clicked level's own children stay visible, everything below
            // folds. Instant re-render from the same cached walk — no IO.
            expandedChildPaths.removeAll { key ->
                key.startsWith(keyPrefix) && key.removePrefix(keyPrefix).count { c -> c == '/' } + 1 > depth
            }
            showChildren(holder, item, children)
        }
        fun seg(text: String, depth: Int, current: Boolean) {
            if (row.childCount > 0) row.addView(TextView(ctx).apply {
                this.text = "/"; textSize = 10.5f
                setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                setPadding(ctx.dp(1), 0, ctx.dp(1), 0)
            })
            row.addView(TextView(ctx).apply {
                this.text = text; textSize = 10.5f; maxLines = 1
                ellipsize = TextUtils.TruncateAt.MIDDLE
                setTextColor(ContextCompat.getColor(ctx,
                    if (current) R.color.accent_light else R.color.text_secondary))
                if (current) typeface = android.graphics.Typeface.DEFAULT_BOLD
                else {
                    isClickable = true; isFocusable = true
                    setOnClickListener { collapseToDepth(depth) }
                }
                setPadding(ctx.dp(2), ctx.dp(2), ctx.dp(2), ctx.dp(2))
            })
        }
        seg(item.name, 0, parts.size == 1)
        for (i in parts.indices) seg(parts[i], i + 1, i == parts.lastIndex)
        holder.childrenBox.addView(row)
    }

    /** ── Pass-39 (#4): one recursion level of the expanded folder tree. ──────
     *  [prefix] is the relPath of the folder being rendered ("" = the staged
     *  folder's own root); [children] is the ORIGINAL walk result, carried so
     *  a nested expand/collapse can re-render the whole tree instantly
     *  without re-walking the disk.
     *  ── Pass-41 (§8): rows are COLLECTED into [out] as builders, in exact
     *  document order, instead of being constructed here — showChildren
     *  flushes them in responsive batches (see flushChildRows), so opening a
     *  directory with hundreds or thousands of children no longer builds
     *  every row in one main-thread block. The builders capture exactly what
     *  the synchronous renderer read at the same point, so row content,
     *  ordering and every listener are identical to the batch-free code. */
    private fun renderChildLevel(holder: VH, item: PreviewItem, visible: List<Pair<String, Long>>,
                                 folderPlaylist: List<MusicPlayback.Track>, mimeByPath: Map<String, String?>,
                                 prefix: String, depth: Int, children: List<Pair<String, Long>>,
                                 out: MutableList<() -> Unit>) {
        val ctx = holder.root.context
        val indent = ctx.dp(12) * depth.coerceAtMost(4)   // readable nesting, never shrunken content
        val subfolders = LinkedHashSet<String>()
        val rootFiles = mutableListOf<Pair<String, Long>>()
        for ((path, size) in visible) {
            val rest = if (prefix.isEmpty()) path else path.removePrefix("$prefix/")
            val slash = rest.indexOf('/')
            if (slash < 0) rootFiles.add(path to size)
            else subfolders.add(if (prefix.isEmpty()) rest.substring(0, slash) else "$prefix/${rest.substring(0, slash)}")
        }
        val folderKey = keyOf(item)
        for (folderPath in subfolders) {
            val name = folderPath.substringAfterLast('/')
            val fileCount = visible.count { it.first.startsWith("$folderPath/") }
            val expandKey = "$folderKey//$folderPath"
            val expanded = expandedChildPaths.contains(expandKey)
            out.add {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(ctx.dp(6) + indent, ctx.dp(5), ctx.dp(6), ctx.dp(5))
                    isClickable = true; isFocusable = true
                    background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_secondary)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .also { it.bottomMargin = ctx.dp(3) }
                }
                val chevron = ImageView(ctx).apply {
                    setImageDrawable(Widgets.icon(ctx, R.drawable.ic_chevron_right, ContextCompat.getColor(ctx, R.color.accent_light)))
                    layoutParams = LinearLayout.LayoutParams(ctx.dp(17), ctx.dp(17)).also { it.marginEnd = ctx.dp(6) }
                    rotation = if (expanded) 90f else 0f
                }
                row.addView(chevron)
                row.addView(ImageView(ctx).apply {
                    setImageDrawable(Widgets.icon(ctx, R.drawable.ic_folder, ContextCompat.getColor(ctx, R.color.text_muted)))
                    layoutParams = LinearLayout.LayoutParams(ctx.dp(17), ctx.dp(17)).also { it.marginEnd = ctx.dp(8) }
                })
                row.addView(TextView(ctx).apply {
                    text = "$name/  ($fileCount file${if (fileCount == 1) "" else "s"})"
                    textSize = 12f; setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                row.addView(childRemoveButton(ctx) { onExcludeChild(item, folderPath) })
                row.setOnClickListener {
                    // Toggle this subfolder's expansion — a full instant re-render
                    // of the same walk (no disk IO), keeping the box's scroll
                    // offset where the user is looking.
                    holder.restoreScrollY = holder.childrenScroll.scrollY
                    if (expandedChildPaths.contains(expandKey)) expandedChildPaths.remove(expandKey)
                    else expandedChildPaths.add(expandKey)
                    showChildren(holder, item, children)
                }
                holder.childrenBox.addView(row)
            }
            if (expanded) {
                renderChildLevel(holder, item, visible.filter { it.first.startsWith("$folderPath/") },
                    folderPlaylist, mimeByPath, prefix = folderPath, depth = depth + 1, children = children, out = out)
            }
        }
        for ((relPath, size) in rootFiles) {
            // ── Pass-33 (#3): child FILE rows carry the queue's own format ─────
            // glyph (same resolver, same family colors as every top-level row)
            // and are click-to-open containers — the same Pass-32 affordance
            // the top-level rows have, extended inside folders (and, since
            // Pass-39, to every nesting level). The row's click is resolved
            // against the LIVE staged item at tap time (never a captured
            // bind), and the remove ✕ beside it is its own clickable control,
            // so excluding a child can never also open it. Taps on the row
            // itself do not expand/collapse any folder — only the subfolder
            // rows' own taps do that.
            // ── Pass-34 (#6C): same vertical rhythm and glyph scale as the
            // folder rows above — 5dp padding + a 3dp inter-row gap, 17dp
            // glyph, 12sp label — so one expanded folder reads as one evenly
            // set block.
            // ── Pass-41 (§8): the row is built as a COLLECTED builder (the
            // batch flush in showChildren decides WHEN it is constructed) —
            // the body is byte-for-byte the synchronous renderer's.
            val childMime = mimeByPath[relPath]
            out.add {
                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(ctx.dp(6) + indent, ctx.dp(5), ctx.dp(6), ctx.dp(5))
                    isClickable = true; isFocusable = true
                    background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_secondary)
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .also { it.bottomMargin = ctx.dp(3) }
                }
                row.addView(ImageView(ctx).apply {
                    setImageDrawable(formatIcon(relPath.substringAfterLast('.', "")))
                    layoutParams = LinearLayout.LayoutParams(ctx.dp(17), ctx.dp(17)).also { it.marginEnd = ctx.dp(8) }
                })
                row.addView(TextView(ctx).apply {
                    text = "${relPath.removePrefix(if (prefix.isEmpty()) "" else "$prefix/")}  ·  ${android.text.format.Formatter.formatShortFileSize(ctx, size)}"
                    textSize = 12f; setTextColor(ContextCompat.getColor(ctx, R.color.text_secondary))
                    maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                val boundKey = keyOf(item)
                // ── Pass-34 Part 2 (#4) + Pass-39 (#6) + Pass-40 (#7): an AUDIO ──
                // child — at ANY nesting depth — plays in-app through the shared
                // controller with the folder's full audio set as the playback
                // context (the manifest's audio for a media folder, the walked
                // audio children's real File paths for a physical folder — both
                // built in showChildren). A SAF tree folder, whose whole set
                // cannot be resolved without a provider round-trip per child,
                // resolves the TAPPED child on demand (resolveChildTrack) and
                // plays it as a one-track session — still the one shared player,
                // still the same key identity, so tapping the live row toggles
                // play/pause exactly like any other. Every other child keeps the
                // live-resolved open. The mime comes from the manifest member
                // with this relPath — the (relPath, size) pair carries no mime
                // of its own.
                if (isAudioMedia(childMime, relPath)) {
                    val audioKey = "q:${relPath}@${item.name}"
                    row.setOnClickListener {
                        val pos = holder.bindingAdapterPosition
                        if (pos != RecyclerView.NO_POSITION) {
                            val fresh = (rows.getOrNull(pos) as? QueueRow.Staged)?.item
                            if (fresh != null && keyOf(fresh) == boundKey) {
                                playQueueAudio(row.context, audioKey, folderPlaylist,
                                    resolveMissing = { resolveChildTrack(row.context, fresh, relPath, size) })
                            }
                        }
                    }
                } else {
                    row.setOnClickListener {
                        val pos = holder.bindingAdapterPosition
                        val fresh = (rows.getOrNull(pos) as? QueueRow.Staged)?.item
                        if (fresh != null && keyOf(fresh) == boundKey) onOpenChild(fresh, relPath)
                    }
                }
                row.addView(childRemoveButton(ctx) { onExcludeChild(item, relPath) })
                holder.childrenBox.addView(row)
            }
        }
    }

    /** ── Pass-39 (#4): which nested subfolders are expanded, keyed by
     *  "<staged-folder-key>//<subfolder relPath>" — adapter-level so the
     *  tree survives the 500ms poll rebinds and recycled-row rebinds of the
     *  same folder. Keys are folder-scoped; stale keys of other folders are
     *  inert. */
    private val expandedChildPaths = java.util.HashSet<String>()

    private fun childRemoveButton(ctx: android.content.Context, onClick: () -> Unit) = ImageView(ctx).apply {
        // Post-v1.0 pass 2 (§unmissable-x): text_secondary, not text_muted —
        // the exclude-child ✕ sat at the dimmest body color on the card
        // surface and read as absent; the row's own remove button above is
        // red_stop, this sibling now at least matches normal secondary
        // affordances while staying visually quieter than the primary ✕.
        setImageDrawable(Widgets.icon(ctx, R.drawable.ic_close, ContextCompat.getColor(ctx, R.color.text_secondary)))
        layoutParams = LinearLayout.LayoutParams(ctx.dp(26), ctx.dp(26))
        setPadding(ctx.dp(5), ctx.dp(5), ctx.dp(5), ctx.dp(5))
        isClickable = true; isFocusable = true
        setOnClickListener { onClick() }
    }

    private fun walkChildren(ctx: android.content.Context, item: PreviewItem): List<Pair<String, Long>> {
        // ── Pass-33 (#1): a MANIFEST folder (a media album) answers its ─────────
        // staged member list DIRECTLY — no physical directory is walked, so
        // expansion shows precisely the images/videos the Media Picker
        // displayed in that album (their real names and sizes), instantly,
        // and an APK/document/subfolder sitting in the same physical
        // directory can never appear here. The pairs are the exact shape
        // every downstream consumer of this walk already speaks, so
        // exclusion/removal and the "Empty folder"/"All contents removed"
        // states keep working unchanged.
        item.childManifest?.let { manifest -> return manifest.map { it.relPath to it.size } }
        // ── Pass-36 Part 2 (#4): the staged item's hidden eligibility rides the ──
        // root node — an expanded folder's children list (this walk) hides
        // hidden descendants unless the picker's hidden mode deliberately
        // included them, exactly like the transfer walks below do.
        val root: FileTreeNode = when {
            item.uri != null -> androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, android.net.Uri.parse(item.uri))
                ?.let { FileTreeNode.DocNode(it, ctx.contentResolver, item.includeHiddenChildren) } ?: return emptyList()
            item.path != null -> FileTreeNode.FsNode(File(item.path), item.includeHiddenChildren)
            else -> return emptyList()
        }
        // GOAT (Part 1 correction — instant incoming/queue expand): fans out
        // across a small bounded thread pool instead of visiting one
        // SAF/filesystem entry at a time. Every child's
        // length()/listFiles() is its own Binder round-trip to a
        // DocumentsProvider (or a cheap local stat() for a plain file) that
        // spends almost all its time blocked waiting, not doing real work —
        // visiting a folder of hundreds of files one at a time serialized
        // hundreds of waits that could otherwise overlap, which is exactly
        // what left this stuck showing "Loading…" for several seconds.
        // ForkJoinPool + RecursiveAction (rather than hand-rolling a
        // counter/latch) specifically because the amount of work isn't
        // known up front — each folder found only discovers its own
        // children once visited — and invokeAll's join semantics handle
        // "wait for everything, including work only just discovered" for
        // free, correctly, with no custom synchronization to get wrong.
        val out = java.util.Collections.synchronizedList(ArrayList<Pair<String, Long>>())
        // Phase 8 (§8.4): the shared process-lifetime pool replaces a fresh
        // per-walk ForkJoinPool — identical invoke semantics (fork/join of
        // unknown-width work), zero pool-construction churn, and no
        // shutdown() to get wrong (the pool simply outlives every walk).
        try {
            val topKids = try { root.children() } catch (e: Exception) { emptyList() }
            if (topKids.isEmpty()) return emptyList()
            sharedWalkPool.invoke(FolderWalkTask(topKids.map { WalkNode(it, "") }, out))
        } finally {
            // No per-walk shutdown — the pool is shared and never drained
            // here (see the companion's doc).
        }
        return out.toList().sortedBy { it.first.lowercase() }
    }

    override fun onViewRecycled(holder: VH) {
        // GOAT v4: the live expanded-holder reference dies with the row —
        // the KEY deliberately survives (scrolling the expanded folder away
        // and back re-expands it on rebind), but this ViewHolder is about
        // to be handed to unrelated content. Cancel the box's animation so
        // the recycled view carries no state.
        if (expandedHolder === holder) expandedHolder = null
        holder.folderBox.setExpandedInstant(false)
        // Phase 5 (§5.2): retire this row's in-flight thumbnail decode too —
        // its result can no longer ever become visible.
        holder.thumbJob?.cancel()
        holder.thumbJob = null
    }

    override fun getItemCount() = rows.size
}

private data class WalkNode(val node: FileTreeNode, val prefix: String)

/** One JDK RecursiveAction per BATCH of sibling nodes (not one per node) —
 *  keeps task-creation overhead low for a folder with thousands of files,
 *  since a single call to [walkChildren] on such a folder would otherwise
 *  fork thousands of individually tiny tasks. */
private class FolderWalkTask(
    private val nodes: List<WalkNode>,
    private val out: MutableList<Pair<String, Long>>,
) : java.util.concurrent.RecursiveAction() {
    override fun compute() {
        val subTasks = mutableListOf<FolderWalkTask>()
        for ((node, prefix) in nodes) {
            if (node.isDirectory) {
                val kids = try { node.children() } catch (e: Exception) { emptyList() }
                if (kids.isNotEmpty()) subTasks += FolderWalkTask(kids.map { WalkNode(it, prefix + node.name + "/") }, out)
            } else {
                val len = try { node.length() } catch (e: Exception) { 0L }
                out.add((prefix + node.name) to len)
            }
        }
        if (subTasks.isNotEmpty()) invokeAll(subTasks)
    }
}
