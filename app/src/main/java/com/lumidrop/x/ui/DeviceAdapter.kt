package com.lumidrop.x.ui

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lumidrop.x.R

/**
 * One row in the unified Connected Devices list.
 *
 * Phase 18 (this pass): browser/HTTP clients (talking to LumiDropServer) and
 * native LumiDrop X peers (Phase 14 P2P, discovered via NativeDiscovery) used
 * to render in two entirely separate cards. Both kinds are unified into this
 * one adapter/list.
 *
 * Next Release Pass (§6 — compact professional redesign): the card is a tight
 * two-line identity block plus ONE deliberate control row. Identity = icon
 * tile with a live-status dot, device name, and a single muted info line
 * (address + capability). The mode's display and its action are the SAME
 * element — a slim two-segment control whose ACTIVE segment shows the
 * authoritative current mode and whose inactive segment is the available
 * action. Less-frequent row management (rename, disconnect) moved into a
 * compact overflow menu (⋮) instead of a permanently visible oversized
 * button; every action from the previous design is still present, and the
 * list's diff-based submit (unchanged) keeps updates incremental — a mode
 * flip or status change rebinds only the rows whose content actually
 * differs, never a full list reconstruction.
 */
sealed class DeviceRow {
    data class Browser(
        val ip: String,
        val displayName: String,
        val customName: String,
        val modeIsFull: Boolean,
        // Phase 2 of 10: this session's Quick-Play external-player capability
        // as the host's authoritative session state knows it (reported by the
        // client via /api/client/capability).
        val extPlayer: Boolean = false,
    ) : DeviceRow()

    data class Native(
        val peerIp: String,
        val displayName: String,
        val isStale: Boolean,
        val statusText: String,
    ) : DeviceRow()
}

class DeviceAdapter(
    private val onRenameBrowser: (ip: String, currentCustomName: String) -> Unit,
    // Phase 5 (§5.3): the mode control's callback carries the ABSOLUTE mode
    // requested by the tapped segment ("full" / "transfer") instead of
    // toggling server-side. Segment semantics — tap the mode you want — are
    // what make the control reliable under rapid tapping. Still per-session
    // only: the write targets this row's ip, never the global default.
    private val onSetMode: (ip: String, mode: String) -> Unit,
    private val onKickBrowser: (ip: String, displayName: String) -> Unit,
) : RecyclerView.Adapter<DeviceAdapter.VH>() {

    private val rows = mutableListOf<DeviceRow>()

    fun submit(newRows: List<DeviceRow>) {
        // Phase 5 (§5.2/§5.1 audit — no gratuitous rebinds): identical
        // content means nothing on screen can have changed, so skip
        // notifyDataSetChanged entirely. When the shape matches, rebind
        // exactly the positions whose content differs (mid-transfer status
        // churn rebinds one row, not the list).
        if (rows == newRows) return
        if (rows.size == newRows.size) {
            var changed = false
            for (i in rows.indices) {
                if (rows[i] != newRows[i]) { notifyItemChanged(i); changed = true }
            }
            if (changed) {
                rows.clear(); rows.addAll(newRows)
                return
            }
        }
        rows.clear()
        rows.addAll(newRows)
        notifyDataSetChanged()
    }

    /** All row-scoped widgets are pre-built and visibility-toggled per bind
     *  rather than using multiple view types — this list is never long. */
    inner class VH(val root: LinearLayout) : RecyclerView.ViewHolder(root) {
        val typeIconBg = View(root.context)
        val typeIcon = ImageView(root.context)
        val dot = View(root.context)
        // ── Pass-29 Part 1 (#5): the browser row's inline status dot + IP ──
        // label. Web-client cards put name AND address on ONE horizontal row
        // now, with the green connection dot sitting BETWEEN the Globe icon
        // and the name — so the row carries its own 7dp dot (the iconStack's
        // corner dot stays exactly where it always was for native-peer rows,
        // which this requirement does not touch) and its own single-line IP
        // label after the name. Both are per-row-type: browser binds show
        // them, native binds hide them.
        val inlineDot = View(root.context)
        val ip = TextView(root.context)
        val name = TextView(root.context)
        // §6: slim two-segment mode control — display AND action in one
        // element (~30dp tall, half-row-wide segments each).
        val segTransfer = TextView(root.context)
        val segFull = TextView(root.context)
        val modeControl = LinearLayout(root.context)
        val nativeBadge = TextView(root.context)
        // §6: compact overflow (⋮) — rename and disconnect live here for
        // browser rows instead of a permanently visible oversized kick
        // button; nothing was removed, only grouped.
        // Post-v1.0 pass 2 (§unmissable-overflow): the glyph is tinted
        // text_PRIMARY at 18dp — the user's report was "clickable but
        // invisible" twice in a row, and the REAL killer in the shipped
        // build was the mask-only bg_icon_ripple (empty outline +
        // clipToOutline erased the glyph on Android 13+); that is fixed at
        // the drawable. The loud tint was the belt-and-braces half of that
        // fix.
        // Pass-14 (per direct feedback): now that the ripple bug is long
        // fixed, that loud 18dp/text_PRIMARY render reads too heavy —
        // "make it more minimalist and less bold". Quiet chrome: 16dp at
        // text_secondary (#8F97A3 on the #2A2E37 row — the standard
        // secondary-action tint used by every other quiet control), on the
        // same 32dp ripple touch target. The ic_more glyph itself also
        // dropped to a refined 1.8-radius dot, so dot ink is down ~44%
        // versus pass-2 while the touch affordance is unchanged.
        val overflow = Widgets.iconButton(root.context, R.drawable.ic_more,
            ContextCompat.getColor(root.context, R.color.text_secondary), sizeDp = 32, iconSizeDp = 16,
            contentDescription = "Device options")
        val subtitle = TextView(root.context)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val ctx = parent.context
        // Pass-3 (R2 — compact professional polish): the row is FLAT now
        // (no elevation shadow — tonal separation between the row surface
        // and the card behind it does the work, which reads tighter and
        // cleaner inside a dark card), paddings are a notch tighter, and
        // every text size dropped half a step so more of each row is
        // information and less is chrome. Structure is unchanged.
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_device_row)
            setPadding(ctx.dp(10), ctx.dp(7), ctx.dp(8), ctx.dp(8))
            layoutParams = RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = ctx.dp(6) }
        }
        val vh = VH(root)

        // ── Identity line: icon+dot · name (+P2P badge) · overflow ────────
        val identity = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val iconStack = android.widget.FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ctx.dp(28), ctx.dp(28)).also { it.marginEnd = ctx.dp(10) }
        }
        vh.typeIconBg.layoutParams = android.widget.FrameLayout.LayoutParams(ctx.dp(28), ctx.dp(28))
        vh.typeIcon.apply {
            layoutParams = android.widget.FrameLayout.LayoutParams(ctx.dp(14), ctx.dp(14), Gravity.CENTER)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        vh.dot.layoutParams = android.widget.FrameLayout.LayoutParams(ctx.dp(7), ctx.dp(7), Gravity.BOTTOM or Gravity.END).also {
            it.bottomMargin = ctx.dp(-1); it.rightMargin = ctx.dp(-1)
        }
        iconStack.addView(vh.typeIconBg); iconStack.addView(vh.typeIcon); iconStack.addView(vh.dot)
        identity.addView(iconStack)

        // ── Pass-29 Part 1 (#5): the browser row's inline status dot — 7dp, ──
        // the same round dot language as the corner dot, sitting BETWEEN the
        // Globe icon and the device name (GONE for native rows; the bind
        // paints its color).
        vh.inlineDot.layoutParams = LinearLayout.LayoutParams(ctx.dp(7), ctx.dp(7)).also {
            it.marginEnd = ctx.dp(7); it.gravity = Gravity.CENTER_VERTICAL
        }
        vh.inlineDot.visibility = View.GONE
        identity.addView(vh.inlineDot)

        vh.name.apply {
            textSize = 13f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        // ── Pass-29 Part 1 (#5): the IP rides the SAME row, after the name. ──
        // wrap_content + maxLines=1 means it never wraps to a second line and
        // never shrinks — when a long name and the address together would
        // overflow a narrow screen, the WEIGHTED name is the element that
        // gives way (its END ellipsis), so the address always stays complete
        // and readable, exactly the graceful degradation the requirement asks
        // for. Muted 10sp — the same quiet metadata treatment the old second
        // line had, now inline.
        vh.ip.apply {
            textSize = 10f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.marginStart = ctx.dp(8)
            }
            visibility = View.GONE
        }
        // Native peers keep their compact inline badge — a P2P row has no
        // mode concept, so the segmented control never appears for it.
        vh.nativeBadge.apply {
            text = "P2P"; textSize = 9f
            setTextColor(ContextCompat.getColor(ctx, R.color.accent_light))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_ghost)
            setPadding(ctx.dp(7), ctx.dp(2), ctx.dp(7), ctx.dp(2))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginStart = ctx.dp(6) }
        }
        identity.addView(vh.name); identity.addView(vh.ip); identity.addView(vh.nativeBadge)
        vh.overflow.visibility = View.GONE
        identity.addView(vh.overflow)
        root.addView(identity)

        // ── Info line: address + capability, single muted line ───────────
        // Pass-3 (R2): 10sp, aligned to the NAME text's start edge
        // (padding 10 + icon 28 + gap 10 = 48dp) so the two lines read as
        // one clean left-aligned block under the identity row.
        vh.subtitle.apply {
            textSize = 10f
            setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.marginStart = ctx.dp(48); it.topMargin = ctx.dp(1)
            }
        }
        root.addView(vh.subtitle)

        // ── Control line: the mode segmented control (slim, §6) ──────────
        fun segment(tv: TextView, label: String) = tv.apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 1
            setPadding(ctx.dp(4), ctx.dp(5), ctx.dp(4), ctx.dp(5))
            isClickable = true; isFocusable = true
            // Never null (see styleSegment's post-v1.0 note): starts in the
            // outline-providing idle state so the very first frame a segment
            // is visible it can never be clipped to an empty outline.
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_segment_idle)
            // v1.0 (§ripple-stretch): the active segment's ripple-backed
            // background stays clipped to the segment's rounded outline —
            // press feedback never stretches past the segment shape.
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        segment(vh.segTransfer, "Transfer")
        segment(vh.segFull, "Full Access")
        vh.modeControl.apply {
            orientation = LinearLayout.HORIZONTAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_secondary)
            setPadding(ctx.dp(3), ctx.dp(3), ctx.dp(3), ctx.dp(3))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = ctx.dp(6) }
            contentDescription = "Access mode for this device"
            addView(vh.segTransfer); addView(vh.segFull)
        }
        root.addView(vh.modeControl)
        return vh
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val ctx = holder.root.context
        fun setDot(colorRes: Int) {
            holder.dot.background = GradientDrawable().also {
                it.shape = GradientDrawable.OVAL
                it.setColor(ContextCompat.getColor(ctx, colorRes))
                it.setStroke(ctx.dp(2), ContextCompat.getColor(ctx, R.color.bg_secondary))
            }
        }
        // ── Pass-29 Part 1 (#5): the browser row's inline twin of setDot — ──
        // the same round-dot style, painted on the dot that sits between the
        // Globe icon and the name.
        fun setInlineDot(colorRes: Int) {
            holder.inlineDot.background = GradientDrawable().also {
                it.shape = GradientDrawable.OVAL
                it.setColor(ContextCompat.getColor(ctx, colorRes))
                it.setStroke(ctx.dp(2), ContextCompat.getColor(ctx, R.color.bg_secondary))
            }
        }
        // Pass-29 Part 1 (#5): the info line aligns under the NAME's start
        // edge, which sits further right on browser rows (the inline status
        // dot adds 14dp to the identity row's leading block). Guarded write —
        // a recycled holder rebinding to the same row type changes nothing.
        fun setSubtitleIndent(dp: Int) {
            val lp = holder.subtitle.layoutParams as? LinearLayout.LayoutParams ?: return
            val px = ctx.dp(dp)
            if (lp.marginStart != px) { lp.marginStart = px; holder.subtitle.layoutParams = lp }
        }
        fun setTypeIcon(iconRes: Int, tintRes: Int) {
            holder.typeIconBg.background = GradientDrawable().also { it.shape = GradientDrawable.OVAL; it.setColor(ContextCompat.getColor(ctx, R.color.bg_secondary)) }
            holder.typeIcon.setImageDrawable(Widgets.icon(ctx, iconRes, ContextCompat.getColor(ctx, tintRes)))
        }
        when (val row = rows[position]) {
            is DeviceRow.Browser -> {
                // ── Pass-29 Part 1 (#5): the web-client card is ONE horizontal ──
                // identity row — Globe icon · green status dot · name · IP —
                // with the second (address) line gone. The iconStack's corner
                // dot is hidden for this row type (the inline dot IS the
                // status dot here); the info line below survives only for the
                // Quick Play capability note, so the common browser card is a
                // clean single row plus its mode control — noticeably shorter
                // than before, with nothing removed.
                holder.dot.visibility = View.GONE
                holder.inlineDot.visibility = View.VISIBLE
                setInlineDot(R.color.green)
                setTypeIcon(R.drawable.ic_globe, R.color.text_secondary)
                holder.name.text = row.displayName
                holder.ip.text = row.ip
                holder.ip.visibility = View.VISIBLE
                holder.nativeBadge.visibility = View.GONE
                holder.modeControl.visibility = View.VISIBLE
                holder.overflow.visibility = View.VISIBLE
                // Active/inactive styling re-applied from AUTHORITATIVE state
                // on every bind — after a tap the next updateDevicesUI()
                // re-renders exactly what the server now reports.
                fun styleSegment(tv: TextView, active: Boolean) {
                    tv.setTextColor(ContextCompat.getColor(ctx, if (active) R.color.white else R.color.text_secondary))
                    tv.typeface = if (active) android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                                  else android.graphics.Typeface.DEFAULT
                    // Post-v1.0 fix (invisible INACTIVE segment on Android 14+):
                    // the inactive state used to set background=null while
                    // clipToOutline stayed true from the segment factory — a
                    // null background resolves an EMPTY outline, and on API
                    // 33+ (Outline.canClip() now unconditional) clipToOutline
                    // with an empty outline clips the segment's TEXT to
                    // nothing. bg_segment_idle is the visually identical
                    // transparent state WITH a real outline-providing content
                    // layer (plus a quiet press ripple). The state-flip guard
                    // avoids reassigning a background mid-press (ripple
                    // glitch discipline retained from v1.0).
                    val wantBg = if (active) R.drawable.bg_btn_primary else R.drawable.bg_segment_idle
                    // (Post-v1.0 compile fix: the elvis right side is itself
                    // nullable, so the expression's type is Drawable? — assert
                    // non-null AFTER the fallback instead of calling through
                    // the nullable result; getDrawable(bg_segment_idle)
                    // cannot be null for a resource that exists in this
                    // module's own drawable set.)
                    val curBg = (tv.background ?: ContextCompat.getDrawable(ctx, R.drawable.bg_segment_idle))!!
                    val want = ContextCompat.getDrawable(ctx, wantBg)!!
                    if (curBg.constantState !== want.constantState) {
                        tv.background = want
                    }
                }
                styleSegment(holder.segTransfer, !row.modeIsFull)
                styleSegment(holder.segFull, row.modeIsFull)
                holder.segTransfer.setOnClickListener { onSetMode(row.ip, "transfer") }
                holder.segFull.setOnClickListener { onSetMode(row.ip, "full") }
                // §6: rename + disconnect grouped in the compact overflow
                // menu. The whole-sheet layout matches the app's other
                // BottomSheets (same paddings, hairline before the
                // destructive action) so this surface reads as one system.
                holder.overflow.setOnClickListener {
                    val sheet = BottomSheet(ctx)
                    sheet.show { _ ->
                        val root = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(ctx.dp(18), 0, ctx.dp(18), ctx.dp(14))
                        }
                        root.addView(TextView(ctx).apply {
                            text = row.displayName
                            textSize = 14.5f; setTextColor(ContextCompat.getColor(ctx, R.color.text_primary))
                            typeface = android.graphics.Typeface.DEFAULT_BOLD
                            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = ctx.dp(2) }
                        })
                        root.addView(TextView(ctx).apply {
                            text = row.ip
                            textSize = 11.5f; setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = ctx.dp(6) }
                        })
                        fun menuRow(label: String, iconRes: Int, tintRes: Int, onClick: () -> Unit): LinearLayout {
                            val r = LinearLayout(ctx).apply {
                                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                                isClickable = true; isFocusable = true
                                background = ContextCompat.getDrawable(ctx, R.drawable.bg_icon_ripple)
                                setPadding(ctx.dp(12), ctx.dp(12), ctx.dp(12), ctx.dp(12))
                                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            }
                            r.addView(ImageView(ctx).apply {
                                setImageDrawable(Widgets.icon(ctx, iconRes, ContextCompat.getColor(ctx, tintRes)))
                                layoutParams = LinearLayout.LayoutParams(ctx.dp(22), ctx.dp(22)).also { it.marginEnd = ctx.dp(13) }
                            })
                            r.addView(TextView(ctx).apply {
                                text = label; textSize = 14f
                                setTextColor(ContextCompat.getColor(ctx, tintRes))
                                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            })
                            r.setOnClickListener { sheet.dismiss(); onClick() }
                            return r
                        }
                        root.addView(menuRow("Rename device", R.drawable.ic_edit, R.color.text_primary) {
                            onRenameBrowser(row.ip, row.customName)
                        })
                        root.addView(View(ctx).apply {
                            setBackgroundColor(ContextCompat.getColor(ctx, R.color.border_subtle))
                            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).also { it.topMargin = ctx.dp(6); it.bottomMargin = ctx.dp(6) }
                        })
                        root.addView(menuRow("Disconnect device", R.drawable.ic_close, R.color.red_stop) {
                            onKickBrowser(row.ip, row.displayName)
                        })
                        root
                    }
                }
                // Session capability rides the info line — sourced from the
                // authoritative session state, not UI inference. The address
                // itself lives on the identity row now (#5), so this line
                // exists only while there is a capability to state.
                if (row.extPlayer) {
                    holder.subtitle.text = "Quick Play ready"
                    holder.subtitle.visibility = View.VISIBLE
                    setSubtitleIndent(62)
                } else {
                    holder.subtitle.visibility = View.GONE
                }
            }
            is DeviceRow.Native -> {
                // §6: the connection state IS visible on the row — live dot
                // green, stale gray, and the status line says what's known.
                // (Pass-29 Part 1 #5: native-peer rows keep their original
                // identity geometry — corner status dot, no inline dot, no IP
                // label — this requirement is about the web-client cards.)
                holder.inlineDot.visibility = View.GONE
                holder.ip.visibility = View.GONE
                holder.dot.visibility = View.VISIBLE
                setDot(if (row.isStale) R.color.text_muted else R.color.green)
                setTypeIcon(R.drawable.ic_phone, R.color.accent_light)
                holder.name.text = row.displayName
                holder.modeControl.visibility = View.GONE
                holder.overflow.visibility = View.GONE
                holder.nativeBadge.visibility = View.VISIBLE
                holder.subtitle.text = row.statusText
                holder.subtitle.visibility = View.VISIBLE
                setSubtitleIndent(48)
            }
        }
    }

    override fun getItemCount() = rows.size
}
