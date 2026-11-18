package com.lumidrop.x.ui

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.lumidrop.x.R

/**
 * ══ Pass-35 (#7): the dedicated TEXT VIEWING modal for the Queued Files ══
 * section.
 *
 * Text arrives on the queue through the Paste Clipboard and Text Field
 * tiles; until now a queued text message had no read affordance at all —
 * the row's own controls were remove/send-side only. This viewer shows the
 * item's content EXACTLY as it was pasted or typed (verbatim — line breaks,
 * spacing and every Unicode character intact), scrolls long content inside
 * a bounded sheet, and closes by the header's ✕, Android Back, the backdrop
 * tap or a drag on the handle/header — the app's standard sheet contract.
 *
 * It is a plain viewer: opening it can never send, edit or mutate the queue
 * (the caller passes the text by value; nothing here touches PreviewItem
 * state). Built as a classic BottomSheet in CONTENT mode — the body's fixed
 * scroll height bounds the sheet, and the drag gesture is dismiss-only.
 */
fun showTextContentViewer(fragment: Fragment, title: String, text: String) {
    val ctx = fragment.requireContext()
    val sheet = BottomSheet(ctx)

    fun col(id: Int) = ContextCompat.getColor(ctx, id)

    val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

    // Header: icon chip + title left, close ✕ right — the app's dialog
    // header language, on its own full-width row.
    val header = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ctx.dp(18), ctx.dp(4), ctx.dp(12), ctx.dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    header.addView(ImageView(ctx).apply {
        setImageDrawable(Widgets.icon(ctx, R.drawable.ic_tile_text, col(R.color.accent_light)))
        background = ContextCompat.getDrawable(ctx, R.drawable.bg_accent_badge)
        setPadding(ctx.dp(7), ctx.dp(7), ctx.dp(7), ctx.dp(7))
        layoutParams = LinearLayout.LayoutParams(ctx.dp(32), ctx.dp(32)).also { it.marginEnd = ctx.dp(10) }
    })
    header.addView(TextView(ctx).apply {
        this.text = title.ifBlank { "Text message" }
        textSize = 16f; setTextColor(col(R.color.text_primary))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    val closeBtn = Widgets.iconButton(ctx, R.drawable.ic_close, col(R.color.text_secondary),
        sizeDp = 36, iconSizeDp = 16, contentDescription = "Close")
    header.addView(closeBtn)
    body.addView(header)

    // The content: a FIXED-height scroll area (55% of the screen — generous
    // for reading, bounded so the sheet never outgrows the window) holding
    // the verbatim text. monospace keeps indented/code pastes legible; a
    // little line spacing keeps prose airy. No selection (the viewer is a
    // reader, and selectable text fights the scroll for touches).
    val scrollHeight = (ctx.resources.displayMetrics.heightPixels * 0.55f).toInt().coerceAtLeast(ctx.dp(240))
    val scroll = ScrollView(ctx).apply {
        isVerticalScrollBarEnabled = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, scrollHeight)
    }
    val content = TextView(ctx).apply {
        this.text = text
        textSize = 13.5f
        setTextColor(col(R.color.text_primary))
        typeface = android.graphics.Typeface.MONOSPACE
        setLineSpacing(ctx.dp(2).toFloat(), 1f)
        setPadding(ctx.dp(18), ctx.dp(4), ctx.dp(18), ctx.dp(14))
        // ── FINAL PASS 6 (PART 2 §2.3): native selection + Copy in the ─────────
        // read-only body — the platform's own long-press word-select, drag
        // handles and Copy toolbar, exactly like the History text viewer
        // this pass rebuilt. The old comment ("No selection — selectable
        // text fights the scroll for touches") described the previous
        // state, not a constraint: on a read-only TextView inside a
        // ScrollView the two gestures coexist by platform design (the
        // scroller owns drags past the touch slop; the selection owns the
        // still-finger long-press; the handles' own drags request
        // disallow-intercept through ArrowKeyMovementMethod in AOSP). This
        // is the one place setTextIsSelectable genuinely belongs (a
        // non-editable body — the same API on an EDITABLE field is the
        // PART 1 bug this pass removes). The verbatim content, the fixed
        // scroll height and the sheet's dismiss-drag contract are
        // untouched.
        setTextIsSelectable(true)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    scroll.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    body.addView(scroll)

    closeBtn.setOnClickListener { sheet.dismiss() }

    sheet.show { body }
    // The header's empty areas join the drag surface (drag-down dismiss);
    // the chip, title and ✕ keep their own touches.
    sheet.registerDragRegion(header)
}
