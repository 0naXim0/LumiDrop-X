package com.lumidrop.x.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lumidrop.x.R

/**
 * One button in a [showLdxDialog] action row.
 *
 * Every button dismisses the dialog after running [onClick] — callers
 * never call dismiss() themselves, matching how a system AlertDialog's
 * buttons already behave (which every call site converted to this was
 * already written around). Pass actions in left-to-right display order —
 * by house convention (see every dialog this replaces) that means the
 * dismiss/cancel action first, the primary or destructive one last.
 *
 * ── Pass-16 ("Send with nothing selected must not close the modal") ──
 * [dismisses] is the one escape from that contract: an action that keeps
 * the dialog open (the Send To modal's Send action when no device card is
 * checked) sets it to false, runs [onClick] (the prompt toast) and leaves
 * the dialog exactly where it was. Every existing call site keeps the
 * default (true), so no behavior anywhere else changes.
 */
data class LdxDialogAction(
    val label: String,
    val style: LdxButtonStyle = LdxButtonStyle.GHOST,
    val dismisses: Boolean = true,
    val onClick: (() -> Unit)? = null,
)

enum class LdxButtonStyle { GHOST, PRIMARY, DESTRUCTIVE }

/**
 * The shared "LumiDropX modal" shell for confirmations, permission asks,
 * and other small secondary dialogs (Part 5.2 — Kick Connected Device,
 * App Permissions, Connection request, Cancel/Delete/Clear confirmations,
 * and so on all use this now instead of each being its own bespoke system
 * AlertDialog). Modeled directly on showTextShareDialog's already-proven
 * look — same bg_dialog card, same icon+title header, same button
 * styling, same dim amount and scale+fade entrance — so every small modal
 * in the app shares one visual language instead of looking like leftover
 * platform UI that happens to sit inside a LumiDropX-themed app.
 *
 * [bodyView], if supplied, replaces [message] entirely (e.g. the rename-
 * device dialog's text field) — a dialog needing custom content just
 * builds that view itself and passes it here rather than this function
 * trying to anticipate every shape a body could take.
 */
fun showLdxDialog(
    context: Context,
    title: String,
    message: String? = null,
    iconRes: Int,
    iconTintRes: Int = R.color.accent_light,
    bodyView: View? = null,
    cancelable: Boolean = true,
    actions: List<LdxDialogAction>,
): Dialog {
    fun col(res: Int) = ContextCompat.getColor(context, res)
    fun dp(n: Int) = context.dp(n)

    val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    // ── Pass-27 (#4.2/#4.3): every LdxDialog is a full-screen window — apply
    // the app's own system-bar colors (never black) AND, while the Hide
    // Navigation Bar preference is on, keep the nav bar hidden on THIS window
    // too (the activity's own bars return automatically on dismiss). See
    // SystemBarChrome.applyToDialog for the full mechanism.
    dialog.applyLdxWindowChrome()
    // GOAT (Phase 3, item 11 -- modal backdrop, system-wide): the window
    // used to be sized WRAP_CONTENT, matching only the card's own bounds,
    // with the platform's default CENTER window gravity doing the
    // positioning. FLAG_DIM_BEHIND below dims the FULL screen visually
    // regardless of the window's own size -- but a window that's only as
    // tall as the card means everything in the dimmed area ABOVE and
    // BELOW the card sits outside the window's actual touchable bounds,
    // so a tap there was never seen by this dialog at all and fell
    // straight through to whatever's underneath. Full-screen window +
    // explicit backdrop view below is what actually gives the backdrop
    // something to swallow the touch with.
    dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    // Theme_Translucent_NoTitleBar makes the WINDOW transparent, not the
    // scrim behind it -- set explicitly rather than assumed, same
    // reasoning as BottomSheet.show() and showTextShareDialog.
    dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    dialog.window?.setDimAmount(0.55f)
    dialog.setCancelable(cancelable)

    // GOAT (Phase 3, item 5 -- soft keyboard audit): the rename-device
    // dialog is built through this shell (its input field passed as
    // bodyView) but was getting none of showTextShareDialog's keyboard
    // handling -- no ADJUST_RESIZE, no auto-focus, nothing -- since this
    // function was written for confirmations/messages, and simply never
    // considered a text-input bodyView. Auto-detecting one here, rather
    // than adding a new parameter every caller would need to remember to
    // pass, means the fix (and any future LdxDialog call site with its
    // own text field) can't be forgotten the way the original gap was.
    // Recurses into bodyView in case a future caller wraps its EditText
    // in a container, rather than only handling today's actual case
    // (bodyView being the EditText directly).
    fun findEditText(v: View?): android.widget.EditText? = when (v) {
        is android.widget.EditText -> v
        is ViewGroup -> (0 until v.childCount).asSequence().mapNotNull { findEditText(v.getChildAt(it)) }.firstOrNull()
        else -> null
    }
    val textInput = findEditText(bodyView)
    if (textInput != null) {
        // Same "set before show()/setContentView(), or it's too late for
        // the window's initial attachment to honor it" reasoning as
        // showTextShareDialog's own comment. No dynamic resize-listener
        // to go with it, unlike that dialog -- ADJUST_RESIZE alone is
        // enough to keep a short header+single-line-input+button-row
        // dialog fully visible above the keyboard, and every actual
        // bodyView passed to this shell today is exactly that shape, not
        // the large multi-line text area the Text Share modal has to
        // actively cap and re-cap as the keyboard opens and closes. If a
        // future caller ever passes a bodyView tall enough to need that,
        // it should get the fuller treatment then, against real content
        // to size it against, rather than untested machinery added now.
        dialog.window?.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
            WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
    }

    // Backdrop dismiss is its own, narrower gate than the cancelable
    // param above (which still governs the back button/system cancel
    // exactly as before, unchanged) -- a destructive action forces this
    // false regardless of what the caller passed for cancelable, so a
    // destructive confirmation can never be built in a way that's
    // backdrop-dismissable by accident.
    val backdropDismissable = cancelable && actions.none { it.style == LdxButtonStyle.DESTRUCTIVE }

    val outer = FrameLayout(context).apply {
        // ── FULL FINAL PASS 2 (§7): 24dp gutters (was 28) — the dialog keeps a ──
        // generous backdrop margin while small screens gain card width.
        setPadding(dp(24), dp(24), dp(24), dp(24))
        // isClickable alone is what makes this FrameLayout consume a tap
        // that lands on it (rather than on the card below, which claims
        // its own taps via its own isClickable = true) instead of letting
        // it pass through to the window behind -- true either way,
        // regardless of backdropDismissable, since swallowing the touch
        // and deciding whether to dismiss are two separate questions.
        isClickable = true
        setOnClickListener { if (backdropDismissable) dialog.dismiss() }
    }
    val card = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_dialog)
        // ── FULL FINAL PASS 2 (§7 — the comfort pass on top of the §3 sizing): ──
        // the §3-era explicit width already killed the squeezed-popup defect
        // (WRAP_CONTENT cards sized from the narrowest intrinsic child); this
        // pass widens the comfortable cap a notch (380 → 420dp), tightens the
        // outer gutter (28 → 24dp) so small screens gain card width, and
        // deepens the card's own padding rhythm (22/22/22/20) so the header,
        // input and action row each get real breathing room. Height stays
        // WRAP_CONTENT — the content decides, never a fixed box.
        setPadding(dp(22), dp(22), dp(22), dp(20))
        elevation = dp(8).toFloat()
        // Claims its own taps so a press anywhere on the card -- including
        // blank padding not covered by the header/message/buttons below --
        // never bubbles up to outer's own click listener above.
        isClickable = true
        val outerPadPx = dp(24) * 2
        val maxCardPx = dp(420)
        val availablePx = context.resources.displayMetrics.widthPixels - outerPadPx
        layoutParams = FrameLayout.LayoutParams(minOf(availablePx, maxCardPx), ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.CENTER
        }
    }

    val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.bottomMargin = dp(16)
        }
    }
    header.addView(ImageView(context).apply {
        setImageDrawable(Widgets.icon(context, iconRes, col(iconTintRes)))
        background = ContextCompat.getDrawable(context, R.drawable.bg_accent_badge)
        setPadding(dp(7), dp(7), dp(7), dp(7))
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).also { it.marginEnd = dp(10) }
    })
    header.addView(TextView(context).apply {
        text = title
        textSize = 16f; setTextColor(col(R.color.text_primary))
        typeface = Typeface.DEFAULT_BOLD
        // Pass-13 (Send To live title): a stable tag so a caller that keeps
        // the dialog open and live-updates its content (the Send To modal's
        // device list) can also refresh this title in place — e.g. "Send to
        // 2 devices" → "Send to 3 devices" when a device connects while the
        // modal is already open. Purely additive; nothing reads it otherwise.
        tag = "ldx_title"
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    card.addView(header)

    if (bodyView != null) {
        card.addView(bodyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.bottomMargin = dp(18)
        })
    } else if (message != null) {
        card.addView(TextView(context).apply {
            text = message
            textSize = 13.5f; setTextColor(col(R.color.text_secondary))
            // Pass-11 (Android 5 crash root fix): was `lineHeight = dp(19)` —
            // TextView.setLineHeight(Int) only exists from API 28, so the
            // synthesized Kotlin property call threw NoSuchMethodError on
            // EVERY API 21-27 device the moment any dialog with a message
            // body built its views — which is exactly the reported
            // "Delete Received Files crashes the app on Android 5" (its
            // confirmation dialog) and every other (i)-info/confirm dialog.
            // setLineSpacing(add, mult) is API 1 and visually equivalent at
            // these sizes (13.5sp text + ~5dp leading ≈ 19dp line height).
            setLineSpacing(dp(5).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = dp(18)
            }
        })
    }

    val actionsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    actions.forEachIndexed { index, action ->
        actionsRow.addView(TextView(context).apply {
            text = action.label; textSize = 14f; gravity = Gravity.CENTER
            // ── FINAL PASS 2 (§3 — labels never crop): two lines are the ──────
            // intentional responsive layout for the rare narrow screen that
            // genuinely cannot fit two actions side by side — the label WRAPS
            // (centered, readable, same touch target height growth) instead of
            // degrading into "Cancel…"/"Renam…". With the card's new explicit
            // width there is room for both labels on one line on every normal
            // screen; this is purely the honest floor below it.
            maxLines = 2
            when (action.style) {
                LdxButtonStyle.GHOST -> {
                    setTextColor(col(R.color.text_secondary))
                    background = ContextCompat.getDrawable(context, R.drawable.bg_btn_ghost)
                }
                LdxButtonStyle.PRIMARY -> {
                    setTextColor(col(R.color.white))
                    typeface = Typeface.DEFAULT_BOLD
                    background = Widgets.rippleRect(context, col(R.color.accent), radiusDp = 12)
                }
                LdxButtonStyle.DESTRUCTIVE -> {
                    setTextColor(col(R.color.white))
                    typeface = Typeface.DEFAULT_BOLD
                    background = Widgets.rippleRect(context, col(R.color.red_stop), radiusDp = 12)
                }
            }
            setPadding(dp(12), dp(14), dp(12), dp(14))
            isClickable = true; isFocusable = true
            // ── FULL FINAL PASS 2 (§7): a guaranteed 48dp touch target — the ──
            // WRAP_CONTENT height could dip below it on dense text scales.
            minHeight = dp(48)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
                if (index < actions.lastIndex) it.marginEnd = dp(10)
            }
            // Pass-16: the action's own dismisses flag decides — the default
            // (true) keeps the always-dismiss contract every other call site
            // was written around; a keep-open action (Send To's Send with an
            // empty selection) prompts and leaves the dialog up.
            setOnClickListener { action.onClick?.invoke(); if (action.dismisses) dialog.dismiss() }
        })
    }
    card.addView(actionsRow)
    outer.addView(card)
    dialog.setContentView(outer)

    // Same subtle scale+fade entrance as showTextShareDialog, rather than
    // the platform's default dialog transition -- "smooth animations" per
    // the Part 5.2 spec, and one more thing every converted dialog now
    // shares instead of each looking slightly different.
    // ── FULL FINAL PASS 2 (§7 — no scale under the keyboard): a dialog that ──
    // is about to raise the soft keyboard (an input bodyView) animates its
    // ALPHA ONLY. The window is simultaneously resizing (SOFT_INPUT_ADJUST_
    // RESIZE) and the keyboard itself is animating in — compounding a scale
    // transform on top of both reads as the reported "glitchy" entrance
    // jitter. Confirmation dialogs (no input, no resize) keep the full
    // scale+fade exactly as before.
    card.scaleX = 0.92f; card.scaleY = 0.92f; card.alpha = 0f
    dialog.show()
    if (textInput != null) {
        card.animate().alpha(1f).setDuration(150).setInterpolator(Motion.DECELERATE).start()
    } else {
        card.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(180).setInterpolator(Motion.DECELERATE).start()
    }

    // Deferred via post{} for the same reason as showTextShareDialog's own
    // identical comment: a bare requestFocus() called immediately after
    // show(), before the window has finished attaching, is exactly the
    // kind of half-measure that produces delayed-focus/keyboard-flicker
    // symptoms on some devices instead of a clean failure.
    if (textInput != null) {
        textInput.post {
            textInput.requestFocus()
            val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(textInput, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    return dialog
}
