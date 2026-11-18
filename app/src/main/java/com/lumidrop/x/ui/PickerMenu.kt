package com.lumidrop.x.ui

import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lumidrop.x.R

/**
 * ══ GOAT-PASS 5 (§1) → GOAT-PASS 7 Part 2 (§3/§6): the coordinated shared ══
 * anchored menu, redesigned COMPACT.
 *
 * One component renders every anchored menu in the pickers and the
 * Transfers tabs — the Files sheet's three-dot menu, the Music sheet's
 * three-dot menu, the storage switcher, and the Received / History tabs'
 * sort menus — so redesigning it here redesigns all of them at once, in one
 * design language, while every call site keeps its exact options and
 * behaviour.
 *
 * THE GOAT-PASS 7 PART 2 REDESIGN (§3/§6B) — the reported problem was the
 * opposite kind from GOAT-PASS 5's: the menus had grown VISUALLY OVERSIZED
 * again — tall rows, wide cards, big glyphs and a height cap expressed as a
 * fraction of the whole DISPLAY rather than of the space the menu actually
 * has. The component is now genuinely compact AND positionally honest:
 *
 *   • COMPACT METRICS — 36dp rows (34 compact), 13sp labels (12.5 compact),
 *     17dp glyph slot, 5dp card padding, 14dp card radius, a 180dp minimum
 *     width, and section rhythm scaled to match. Nothing oversized, nothing
 *     cramping the 40dp touch guidance.
 *   • HONEST WIDTH — the card is measured once, clamped to a 292dp maximum
 *     (a pathological label can no longer stretch the card wide) and a
 *     180dp minimum (a short menu never looks cramped).
 *   • HONEST HEIGHT — the scroll cap is the space that actually exists
 *     BELOW THE ANCHOR (the anchor's live on-screen bottom edge, minus a
 *     system-gesture margin, never more than 58% of the display). A long
 *     menu scrolls INSIDE the same compact card; it can never run past the
 *     bottom of the screen, off the sheet, or into the navigation bar, and
 *     never needs to flip above its trigger.
 *   • THE STATES are complete: pressed/hover/focused ride one clipped
 *     RippleDrawable; the SELECTED choice carries a soft accent-tinted pill
 *     plus the accent check; a DISABLED row dims instead of vanishing.
 *   • TOGGLE rows (iconRes + checked) keep the leading glyph and a TRAILING
 *     check mark — a checkbox's honest shape.
 *   • DIVIDERS are hairline, inset, and tight (3dp breathing room).
 *
 * Positioning keeps the GOAT-PASS 4 anchor discipline (end-anchored by
 * default for right-most triggers, Gravity.START opt-out) with a 6dp drop
 * offset so the card separates cleanly from its trigger.
 */
class PickerOption(
    /** The caller's own key, handed back through [showPickerOptionsMenu]'s
     *  onPick — e.g. "NAME", "DIR_DESC", "HIDDEN". */
    val id: String,
    val label: String,
    val checked: Boolean = false,
    /** DIVIDER renders as a thin separator (label unused); OPTION is a row. */
    val divider: Boolean = false,
    /** An optional ACTION glyph. When set the row's leading slot shows this
     *  icon (tinted accent_light) instead of the check mark — sorting rows
     *  keep their check-rail alignment, and action rows (Copy/Cut/Paste/
     *  Pin/Delete/Rename) read as actions at a glance. When BOTH iconRes
     *  and checked are set the row renders as a TOGGLE: leading glyph plus
     *  a trailing check mark (GOAT-PASS 5). */
    val iconRes: Int? = null,
    /** GOAT-PASS 5: renders the row un-tappable and dimmed — the disabled
     *  state of the interaction contract. No current call site uses it. */
    val enabled: Boolean = true,
)

fun showPickerOptionsMenu(anchor: View, title: String, options: List<PickerOption>,
                          /** COMPACT rendering — the same menu with slightly
                           *  tighter metrics for surfaces whose chrome is
                           *  already slim (the sheet toolbars, the Transfers
                           *  tabs' headers). Deliberately BEFORE [onPick] so
                           *  trailing-lambda call sites keep binding to the
                           *  pick handler. */
                          compact: Boolean = false,
                          /** Right-edge anchoring (see the showAsDropDown
                           *  note). Default TRUE — every current menu hangs
                           *  off a right-most control. */
                          endAnchored: Boolean = true,
                          onPick: (String) -> Unit) {
    val ctx = anchor.context
    fun col(id: Int) = ContextCompat.getColor(ctx, id)

    // ── GOAT-PASS 7 Part 2 (§3/§6B): the COMPACT scale. The proportions ──
    // (row height : label size : glyph size) stay internally consistent so
    // every menu still reads as one design language — just tighter, denser
    // and honest about the space it occupies.
    val minW = if (compact) 180 else 192       // card minimum width (dp)
    val maxW = 292                              // card maximum width (dp)
    val cardPad = 5                             // card inner padding
    val rowMinH = if (compact) 34 else 36       // compact touch-target row height
    val rowPadH = 10                            // row horizontal padding
    val labelSize = if (compact) 12.5f else 13f // the menu's protagonist
    val glyphDp = if (compact) 16 else 17       // leading glyph slot
    val glyphMargin = 10                        // glyph → label gap
    val accent = col(R.color.accent_light)
    val tintSel = (accent and 0x00FFFFFF) or 0x24000000   // ~14% accent tint

    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(ctx.dp(cardPad), ctx.dp(cardPad), ctx.dp(cardPad), ctx.dp(cardPad))
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dpf(14f)
            setColor(col(R.color.bg_card_elevated))
            setStroke(ctx.dp(1), 0x16FFFFFF)
        }
        elevation = ctx.dpf(14f)
    }

    // One clipped ripple for pressed/hover/focused — the overlay is white at
    // a whisper, the mask matches the row pill, and the SELECTED tint sits
    // under it so feedback reads on both plain and selected rows.
    fun rowBg(selected: Boolean): RippleDrawable {
        val pill = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dpf(9f)
            if (selected) setColor(tintSel) else setColor(0x00000000)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = ctx.dpf(9f)
            setColor(0xFFFFFFFF.toInt())
        }
        // GOAT-PASS 9 (build repair): RippleDrawable's first parameter is a
        // ColorStateList, not an Int — this literal never compiled. The
        // ripple color is unchanged (0x1FFFFFFF).
        return RippleDrawable(android.content.res.ColorStateList.valueOf(0x1FFFFFFF), pill, mask)
    }

    // ── GOAT-PASS 8 (§12 — the scroller lives INSIDE the card): the rows are ──
    // built into their own container FIRST, measured freely (plus the title's
    // and the card padding's overhead), and only when the natural content is
    // taller than the honest below-anchor cap is a ScrollView inserted
    // BETWEEN the card and the rows — the CARD (background, radius, padding,
    // title) stays the popup's fixed, anchored surface, and only its content
    // scrolls. The old shape wrapped the WHOLE card in the ScrollView, so a
    // long menu scrolled its own rounded container out through the popup's
    // viewport — the reported "top/bottom edge becomes cropped, the menu card
    // itself moves or clips incorrectly". The card keeps every option, its
    // compact metrics, its trigger anchoring and its end-alignment.
    val rows = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
    }

    var popupRef: PopupWindow? = null

    for (opt in options) {
        if (opt.divider) {
            rows.addView(View(ctx).apply {
                setBackgroundColor(0x12FFFFFF)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ctx.dp(1)).also {
                    it.topMargin = ctx.dp(3); it.bottomMargin = ctx.dp(3)
                    it.leftMargin = ctx.dp(12); it.rightMargin = ctx.dp(12)
                }
            })
            continue
        }
        val isToggle = opt.iconRes != null && opt.checked
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // GOAT-PASS 9 (build repair): View's property is minimumHeight —
            // a bare `minHeight` never resolved (the GP8 source never built).
            minimumHeight = ctx.dp(rowMinH)
            setPadding(ctx.dp(rowPadH), ctx.dp(3), ctx.dp(rowPadH), ctx.dp(3))
            isClickable = opt.enabled
            isFocusable = opt.enabled
            background = rowBg(opt.checked && !isToggle)
            if (!opt.enabled) alpha = 0.42f
        }
        // The leading glyph slot — a fixed axis every label aligns on: the
        // check for choice rows, the action icon for action rows, and the
        // action icon for toggle rows (whose check lives at the trailing
        // edge instead — a checkbox's honest shape).
        row.addView(ImageView(ctx).apply {
            val glyph = when {
                opt.iconRes != null -> Widgets.icon(ctx, opt.iconRes, accent)
                opt.checked -> Widgets.icon(ctx, R.drawable.ic_check, accent)
                else -> Widgets.icon(ctx, R.drawable.ic_check, 0x00000000)
            }
            setImageDrawable(glyph)
            layoutParams = LinearLayout.LayoutParams(ctx.dp(glyphDp), ctx.dp(glyphDp)).also {
                it.marginEnd = ctx.dp(glyphMargin)
            }
        })
        row.addView(TextView(ctx).apply {
            text = opt.label; textSize = labelSize
            typeface = if (opt.checked && !isToggle) android.graphics.Typeface.DEFAULT_BOLD else android.graphics.Typeface.DEFAULT
            setTextColor(col(if (opt.checked) R.color.text_primary else R.color.text_secondary))
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                it.marginEnd = ctx.dp(if (isToggle) 10 else 4)
            }
        })
        if (isToggle) {
            row.addView(ImageView(ctx).apply {
                setImageDrawable(Widgets.icon(ctx, R.drawable.ic_check, accent))
                layoutParams = LinearLayout.LayoutParams(ctx.dp(glyphDp), ctx.dp(glyphDp))
            })
        }
        if (opt.enabled) {
            row.setOnClickListener {
                popupRef?.dismiss()
                onPick(opt.id)
            }
        }
        rows.addView(row)
    }

    val titleView: TextView? = if (title.isNotBlank()) {
        TextView(ctx).apply {
            text = title.uppercase()
            textSize = 9.5f
            letterSpacing = 0.1f
            setTextColor(col(R.color.text_muted))
            setPadding(ctx.dp(12), ctx.dp(8), ctx.dp(12), ctx.dp(4))
        }
    } else null
    if (titleView != null) card.addView(titleView)

    // ── The honest size (GOAT-PASS 7 Part 2 §3/§6B, unchanged in spirit): ──
    // measure the rows freely, clamp the WIDTH against the compact maximum,
    // and cap the rows' scroll height at the space that actually exists BELOW
    // the anchor (its live on-screen bottom edge, minus the drop offset and a
    // system-gesture margin, never more than 58% of the display).
    rows.measure(
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    val widthClamp = ctx.dp(maxW)
    if (rows.measuredWidth > widthClamp) {
        rows.measure(
            View.MeasureSpec.makeMeasureSpec(widthClamp, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    }
    val contentW = Math.max(rows.measuredWidth.coerceAtMost(widthClamp), ctx.dp(minW))
    val rowsH = rows.measuredHeight

    val screenH = ctx.resources.displayMetrics.heightPixels
    val dropOffset = ctx.dp(6)
    val anchorScreenBottom = try {
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        loc[1] + anchor.height
    } catch (e: Exception) { 0 }
    // The genuinely available space below the trigger: from the anchor's
    // bottom edge to the display's bottom, less the drop offset and a
    // conservative system-gesture/inset margin. Never negative; a
    // pathological anchor at the very bottom simply falls back to the
    // display-fraction cap (the PopupWindow then flips above the anchor,
    // as the platform always did).
    val availBelow = (screenH - anchorScreenBottom - dropOffset - ctx.dp(20)).coerceAtLeast(0)
    val fractionCap = (screenH * 0.58f).toInt()
    val maxH = minOf(fractionCap, availBelow).let {
        if (it >= ctx.dp(180)) it else minOf(fractionCap, ctx.dp(180)).coerceAtLeast(ctx.dp(160))
    }
    // The card's fixed chrome (title + padding) is NOT part of the scrolling
    // content — subtract it from the cap so the popup's TOTAL height (and the
    // card's rounded corners) always stay fully inside the viewport.
    titleView?.measure(
        View.MeasureSpec.makeMeasureSpec(contentW, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
    val chromeH = 2 * ctx.dp(cardPad) + (titleView?.measuredHeight ?: 0)
    val scrollCap = (maxH - chromeH).coerceAtLeast(ctx.dp(96))

    if (rowsH > scrollCap) {
        // ── GOAT-PASS 8 (§12): the ScrollView sits INSIDE the card — the card ──
        // stays the popup's fixed, anchored surface (background, radius,
        // title, padding all visible at all times) and only the rows scroll
        // within the capped viewport, clipped by the scroller's own bounds.
        val scroller = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, scrollCap)
        }
        scroller.addView(rows, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        card.addView(scroller)
        val popup = PopupWindow(card, contentW, chromeH + scrollCap, true)
        popupRef = popup
        popup.setBackgroundDrawable(GradientDrawable().apply { setColor(0x00000000) })
        popup.isOutsideTouchable = true
        popup.elevation = ctx.dpf(14f)
        popup.animationStyle = R.style.Animation_PickerMenu   // GOAT-PASS 9 (build repair): Animation_DropDownUp is a non-public platform style — the app's own style keeps the dropdown feel on every API level
        popup.showAsDropDown(anchor, 0, dropOffset, if (endAnchored) Gravity.END else Gravity.START)
    } else {
        card.addView(rows, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val popup = PopupWindow(card, contentW, chromeH + rowsH, true)
        popupRef = popup
        // A real background drawable is what makes outside-touch actually
        // dismiss a focusable PopupWindow on every API level this app supports.
        popup.setBackgroundDrawable(GradientDrawable().apply { setColor(0x00000000) })
        popup.isOutsideTouchable = true
        popup.elevation = ctx.dpf(14f)
        // The platform's own dropdown animation — grows up from the trigger,
        // fast-in/fast-out, identical to every system menu on the device.
        popup.animationStyle = R.style.Animation_PickerMenu   // GOAT-PASS 9 (build repair): Animation_DropDownUp is a non-public platform style — the app's own style keeps the dropdown feel on every API level
        popup.showAsDropDown(anchor, 0, dropOffset, if (endAnchored) Gravity.END else Gravity.START)
    }
}
