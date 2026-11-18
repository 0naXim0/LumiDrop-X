package com.lumidrop.x.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Outline
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.lumidrop.x.R

/**
 * A plain-Dialog-based bottom sheet with a slide-up entrance, a slide-down
 * exit, and real swipe-to-dismiss on the drag handle — what the previous
 * `Dialog(context, Theme_Translucent_NoTitleBar)` + `dialog.show()` calls
 * (APK picker, file quick-actions) didn't have at all: no entrance
 * animation, and closing was an instant cut with no gesture support,
 * which is what read as "abrupt and slightly laggy."
 *
 * Deliberately not MDC's BottomSheetDialog/BottomSheetBehavior — same
 * Theme.MaterialComponents concern as everywhere else in this app (see
 * UiTokens.kt). The drag gesture is a manual ACTION_DOWN/MOVE/UP tracker
 * with EXPLICIT touch ownership, so it never fights a scrollable
 * grid/list inside the sheet.
 *
 * ── Pass-34 (#9): DRAG-TO-EXPAND ────────────────────────────────────────────
 * [expandable] sheets can be dragged UP to ~full-screen height and dragged
 * back DOWN to their default height, with the classic drag-down-to-dismiss
 * kept for the default (collapsed) state:
 *
 *   • [expandable] + [defaultHeightFraction] > 0 — FIXED default height mode
 *     (the Files, Music and Media pickers): the sheet opens at EXACTLY that
 *     fraction of the screen height regardless of content, and the body is
 *     stretched to fill it, so the sheet never grows or shrinks with the
 *     number of rows.
 *
 *   • [expandable] + [defaultHeightFraction] == 0 — CONTENT height mode (the
 *     App Picker): the sheet keeps its natural wrap-content height (design
 *     untouched); only the upward drag is added, and [onExpandedChange]
 *     lets that one caller flex its own fixed-height grid when the sheet
 *     grows — the single surgical hook the App Picker needs, nothing else.
 *
 * Release settling is threshold-based on how far the drag moved plus a
 * fling check, and the height animation is a plain ValueAnimator over the
 * layout params — predictable, cancellable, no physics engine.
 *
 * ══ Pass-35 (#1): dragging from the sheet's HEADER, not just the handle ══
 * The handle stays (with its TouchDelegate), but it is no longer the only
 * grab zone: [registerDragRegion] attaches the very same gesture to any
 * chrome container a sheet nominates — a title row, a controls strip —
 * with a REAL touch-ownership rule rather than a blanket listener:
 *
 *   • ACTION_DOWN on a registered region is hit-tested to the deepest
 *     descendant under the finger. If that view — or ANY of its ancestors
 *     up to the region — is interactive (clickable, long-clickable, or a
 *     focusable-in-touch-mode input such as an EditText), the region does
 *     NOT take the gesture and the control receives its normal touch.
 *   • Only presses that land on genuinely EMPTY chrome (the row's own
 *     background, non-interactive labels, padding) start the sheet drag.
 *   • Scrollable content (lists, grids) is never registered, so the drag
 *     can never compete with scrolling for a touch.
 *
 * ══ Pass-35 (#2): dismissal and expansion made crash- and dim-proof ══
 *   • The CONTENT-mode collapse restore used to re-assign the body a bare
 *     ViewGroup.LayoutParams — a LinearLayout child measured through that
 *     cast is the ClassCastException behind the App Picker's
 *     expand→collapse crash. The restore now builds the correct
 *     LinearLayout.LayoutParams.
 *   • A canceled height animator used to run its end action anyway
 *     (AnimatorListenerAdapter.onAnimationEnd fires for cancels too), so
 *     a drag that grabbed the sheet mid-settle executed the restore
 *     synchronously mid-gesture. Canceled animations now run nothing.
 *   • The animated dismiss used to depend entirely on the exit animation's
 *     withEndAction to close the window — any dropped end action left a
 *     fully transparent window (and its FLAG_DIM_BEHIND scrim) holding the
 *     screen dark with no visible sheet. The dim now leaves with the
 *     finger, and a bounded completion guarantee closes the window even
 *     if the end action never fires.
 *   • The sheet's height is CLAMPED to the backdrop's live frame whenever
 *     the window resizes (keyboard opening/closing): a bottom-anchored
 *     full-height root used to extend above the shrunken window, clipping
 *     the header and pushing search results out of view (#2C). The clamp
 *     keeps the whole sheet inside the visible frame and springs it back
 *     to its proper default/expanded height when the keyboard goes away.
 */
class BottomSheet(
    private val context: Context,
    private val expandable: Boolean = false,
    private val defaultHeightFraction: Float = 0f,
    private val onExpandedChange: ((Boolean) -> Unit)? = null,
) {
    val dialog = Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
    private lateinit var sheetRoot: View
    private lateinit var backdropHost: FrameLayout
    private var dismissing = false

    // ── FINAL PASS 7 (PART 2): visual-state reconciliation read access ─────────
    // A caller that re-presents a sheet from AUTHORITATIVE STATE on a poll
    // tick (ReceivingModal) must be able to tell a sheet that is genuinely
    // on screen from one whose entrance never ran — the dialog window is
    // attached (isShowing == true) while its root still sits at the 400dp
    // off-screen start position because the posted entrance runnable was
    // cancelled by a window-focus transition. Both values below exist for
    // exactly that check and nothing else; both are read-only.
    /** The sheet root's current vertical displacement, in pixels. */
    val rootTranslationY: Float get() = if (::sheetRoot.isInitialized) sheetRoot.translationY else 0f
    /** True once the entrance runnable has run at least once (the root left
     *  its initial 400dp position at some point) — before that, a displaced
     *  root is just an entrance that hasn't started yet, never a stranded
     *  sheet. */
    private var entranceRan = false
    /** Whether this sheet's entrance has settled — ReceivingModal's stranded
     *  check only treats a DISPLACED root as stranded when the entrance
     *  window (first ~500ms after show) is long past. */
    val entranceSettled: Boolean get() = entranceRan
    internal fun markEntranceRan() { entranceRan = true }

    // ── Pass-35 (#4): back interception. The sheet's dialog asks this hook
    // FIRST on a BACK press; true means a higher-priority action consumed it
    // (close a preview, navigate up, exit selection mode), false lets the
    // dialog's default dismissal run. Exactly one handler decides per press. ──
    var onBack: (() -> Boolean)? = null

    // ── Pass-34 (#9) expansion state ──────────────────────────────────────────
    private var backdrop: FrameLayout? = null
    /** ── Pass-36 (#4): the dim scrim VIEW (see show()'s own comment for why ──
     *  it replaced FLAG_DIM_BEHIND). Fades in with the sheet's entrance, out
     *  with its exit; dies with the window on every dismiss path. */
    private var dimScrim: View? = null
    private var heightAnimator: android.animation.ValueAnimator? = null
    private var expandedNow = false
    /** The sheet's default height in px — FIXED mode: fraction × screen;
     *  CONTENT mode: the wrap-content height captured before the first
     *  expansion (the content itself never changes size while expanded). */
    private var defaultHeightPx = 0
    private var fullHeightPx = 0
    private var contentModeBodyParams: ViewGroup.LayoutParams? = null
    /** The sheet's intended height right now (default, expanded, or a
     *  mid-drag value). The keyboard clamp re-derives from this + the live
     *  flags so a resize never permanently latches a shrunken height. */
    private var sheetTargetHeightPx = 0

    private fun computeHeights() {
        fullHeightPx = context.resources.displayMetrics.heightPixels
        if (expandable && defaultHeightFraction > 0f) {
            defaultHeightPx = (fullHeightPx * defaultHeightFraction).toInt().coerceAtLeast(context.dp(200))
        }
    }

    private fun applySheetHeight(px: Int) {
        if (!::sheetRoot.isInitialized) return
        sheetTargetHeightPx = px
        val root = sheetRoot
        if (root.height == px) return   // no layout churn for no-ops
        val lp = root.layoutParams
        lp.height = px
        root.layoutParams = lp
    }

    /** Animates the sheet to [targetPx]. A CANCELED run never executes its
     *  [endAction] — a new gesture or dismiss that interrupts the settle
     *  owns the sheet's state from that moment (the old onAnimationEnd-
     *  fires-on-cancel behavior is what ran collapse restores mid-drag). */
    private fun animateSheetHeight(targetPx: Int, endAction: () -> Unit) {
        heightAnimator?.cancel()
        val from = sheetRoot.height
        if (from == targetPx) { endAction(); return }
        var cancelled = false
        val anim = android.animation.ValueAnimator.ofInt(from, targetPx).apply {
            duration = 200
            interpolator = Motion.DECELERATE
            addUpdateListener { a -> applySheetHeight(a.animatedValue as Int) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (!cancelled) endAction()
                }
            })
        }
        heightAnimator = anim
        anim.start()
    }

    private fun setExpanded(expanded: Boolean) {
        if (expandedNow == expanded) return
        expandedNow = expanded
        try { onExpandedChange?.invoke(expanded) } catch (e: Exception) { /* listener must never break the sheet */ }
    }

    /** ── GOAT-PASS 12 (§1): programmatic expandToFullHeight() is RETIRED. ──
     *  Its only caller was the GOAT-PASS 10 internal "Open Received Folder"
     *  flow, which this pass returns to the sheet's normal initial size
     *  (the user stretches it manually, as they always could). The sheet's
     *  manual drag-to-expand (DragTracker) is untouched. */

    /** [content] receives this BottomSheet so action rows etc. can call
     *  [dismiss] for the same animated close instead of `dialog.dismiss()`
     *  directly (which would skip the exit animation). */
    fun show(content: (BottomSheet) -> View) {
        computeHeights()
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // ── Pass-27 (#4.2/#4.3): this sheet is a full-screen WINDOW — without
        // this, its window's platform-default BLACK system bars replace the
        // app's seamless bg_deep ones for as long as the sheet owns the screen
        // (the reported "navigation bar turns black when a sheet opens"), and
        // while the Hide Navigation Bar preference is on the Android nav buttons
        // would come back for exactly as long. applyLdxWindowChrome fixes both on
        // THIS window; dismissal restores the activity's own bars automatically.
        dialog.applyLdxWindowChrome()
        // GOAT (Phase 3, item 5 -- soft keyboard audit): ADJUST_RESIZE on
        // every sheet, unconditionally — nothing in a BottomSheet
        // auto-focuses on open, so this only ever activates once the user
        // taps an EditText themselves, and it is what lets the frame (and
        // the clamp below) respond to the keyboard at all.
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        // GOAT (Phase 3, item 11 -- modal backdrop, system-wide): a
        // WRAP_CONTENT-height window only covers the sheet's own bounds, so
        // a backdrop dim was visually there but not touchable above the
        // sheet. A MATCH_PARENT window with the sheet bottom-anchored inside
        // its own backdrop child fixes both.
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // ── Pass-36 (#4): the dim is a SCRIM VIEW inside the backdrop, not ──
        // FLAG_DIM_BEHIND. The window flag's dim lives in the WindowManager's
        // surface lifecycle — on OEM builds whose surface teardown lags
        // dialog.dismiss(), the dimmed surface can keep darkening the app
        // after the sheet is visually gone (the App Picker's reported
        // "screen stays dark"), and no amount of resetting the flag helps
        // because the flag only matters while OUR window exists. A view we
        // own fades deterministically with the sheet, dies with the window,
        // and can never outlive either. 0x8C = 55% black, the same visual
        // weight the window dim had.
        dimScrim = View(context).apply {
            setBackgroundColor(0x8C000000.toInt())
            alpha = 0f
        }
        dialog.setCancelable(true)

        // ── Pass-35 (#4): the single BACK decision point. The hook gets the
        // press first; only an unconsumed one falls through to the dialog's
        // default dismiss. No other handler can double-execute behind it.
        dialog.setOnKeyListener { _, keyCode, event ->
            if (keyCode == android.view.KeyEvent.KEYCODE_BACK && event.action == android.view.KeyEvent.ACTION_UP) {
                onBack?.invoke() == true
            } else false
        }

        val handle = View(context).apply {
            background = ContextCompat.getDrawable(context, R.drawable.bg_sheet_handle)
            // ── Pass-37 (#5): bottomMargin 8dp → 3dp. The 8dp read as an empty ──
            // strip between the drag-handle row and the title/header row below
            // it — the reported "unwanted visible gap". 3dp keeps a hair of
            // breathing room under the bar while the handle and the header
            // read as one continuous header block, on every sheet that rides
            // this shared surface (App, Files, Music, Media pickers alike).
            layoutParams = LinearLayout.LayoutParams(context.dp(36), context.dp(4)).also {
                it.gravity = android.view.Gravity.CENTER_HORIZONTAL
                it.topMargin = context.dp(10); it.bottomMargin = context.dp(3)
            }
        }
        val body = content(this)
        // ── Pass-34 (#9): FIXED-height sheets stretch their body to fill the
        // pinned height (the body's own internals keep their weights), so the
        // sheet's size never depends on how many rows it happens to hold.
        if (expandable && defaultHeightFraction > 0f) {
            body.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(context, R.drawable.bg_sheet_top)
            elevation = context.resources.getDimension(R.dimen.elevation_sheet)
            // bg_sheet_top rounds only its top two corners (bottom is a
            // flush 0dp, since the sheet meets the screen edge there).
            // Outline only auto-derives a rounded rect for a uniform corner
            // set; this asymmetric shape needs an explicit path or the
            // shadow falls back to the view's full rectangular bounds.
            // Built from the view's own measured size in getOutline()
            // (called on layout), so it stays correct at any size.
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    val r = context.resources.getDimension(R.dimen.radius_xl)
                    val path = android.graphics.Path().apply {
                        addRoundRect(
                            0f, 0f, view.width.toFloat(), view.height.toFloat(),
                            floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f),
                            android.graphics.Path.Direction.CW,
                        )
                    }
                    outline.setConvexPath(path)
                }
            }
            // Claims its own taps so a press anywhere on the visible sheet
            // body never bubbles up to the backdrop's dismiss listener
            // below — same reasoning as LdxDialog's card.isClickable.
            isClickable = true
            // ── GOAT-PASS 13 (§9 — the Android 5 auto-search fix) ──────────────
            // A Dialog's initial touch-mode focus lands on the FIRST view in
            // the content that is focusable-in-touch-mode — for these sheets
            // that was always the header's search EditText (the only such
            // view), so on Android 5 — where the IME follows that automatic
            // focus at window-attach — opening the Files/Music sheet popped
            // the keyboard straight into the search field: "the search bar
            // opens by itself". The sheet BODY now claims the initial focus
            // (a plain focusable container, no visual, no input): the search
            // field stays an ordinary untyped-into field until the user
            // actually taps it, on every Android version. Soft-input
            // semantics (ADJUST_RESIZE above) are untouched — a deliberate
            // tap still focuses the field and shows the keyboard exactly as
            // before.
            isFocusable = true
            isFocusableInTouchMode = true
            addView(handle)
            addView(body)
        }
        sheetRoot = root
        attachDragToDismiss(handle, root, body)

        val backdropView = FrameLayout(context).apply {
            isClickable = true
            // ── GOAT-PASS 7 Part 2 (§7A): the backdrop's tap-to-dismiss must ──
            // never swallow a tap on the compact music pill. The pill docks in
            // the app's own window (the Server Card / tab header strip this
            // sheet deliberately leaves exposed), but this sheet's full-screen
            // backdrop window sits ABOVE it — so while any sheet is up, a tap
            // on the visible pill used to land HERE and silently DISMISS the
            // sheet instead of opening the player (the reported "sometimes
            // the pill only opens on the second click": tap one closed the
            // sheet, tap two finally reached the pill). The backdrop now
            // records the DOWN coordinates (a non-consuming touch listener —
            // the click semantics are byte-for-byte what they always were)
            // and offers the tap to the pill's live screen bounds FIRST: a
            // hit expands the player (which re-stacks above this sheet) and
            // the sheet stays exactly where it is.
            var backdropDownRaw = intArrayOf(0, 0)
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                    backdropDownRaw[0] = e.rawX.toInt()
                    backdropDownRaw[1] = e.rawY.toInt()
                }
                false   // never consumed — the click listener below keeps its exact semantics
            }
            setOnClickListener {
                if (MusicPlayback.interceptModalBackdropTap(backdropDownRaw[0], backdropDownRaw[1]))
                    return@setOnClickListener
                dismiss()
            }
            // Pass-36 (#4): the scrim is the backdrop's FIRST child — under
            // the sheet, over the app — and NOT clickable, so a tap on it
            // falls through to this container's own dismiss listener exactly
            // like a tap on any other empty backdrop area always did.
            addView(dimScrim, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val rootParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                if (expandable && defaultHeightFraction > 0f) defaultHeightPx else ViewGroup.LayoutParams.WRAP_CONTENT
            ).also { it.gravity = android.view.Gravity.BOTTOM }
            addView(root, rootParams)
        }
        backdrop = backdropView
        backdropHost = backdropView
        dialog.setContentView(backdropView)
        // NOTE (Pass-35): no setOnDismissListener here — callers own the
        // dismiss hook (scope cancellation, guard release), several of them
        // attach it BEFORE show(), and an empty listener set here would
        // silently replace theirs.
        dialog.show()
        // Pass-36 (#4): the scrim arrives WITH the sheet's entrance — one
        // motion, one owner. (The window flag it replaced had no transition
        // of its own: the dim appeared in one frame with the window.)
        dimScrim?.animate()?.alpha(1f)?.setDuration(Motion.DURATION_SHORT)
            ?.setInterpolator(Motion.DECELERATE)?.start()

        // ── Pass-35 (#2C): the keyboard clamp. This window is
        // ADJUST_RESIZE, so an opening IME shrinks the backdrop's own
        // frame; a bottom-anchored root pinned at the FULL screen height
        // (or a 75% default taller than the shrunken frame) then extends
        // above the window — the sheet's header clipped off-screen and the
        // list pushed out of the visible area, readable again only after
        // the keyboard closed. Whenever the BACKDROP itself resizes, the
        // sheet's height is re-derived from its current intended state
        // (default or expanded) and clamped to the live frame, so the
        // whole sheet — header, search, list — always stays inside the
        // visible window, and springs back to its proper height the moment
        // the frame grows again. Child-only relayouts (this sheet's own
        // height changes) never change the backdrop's bounds, so this
        // listener cannot loop with the drag.
        backdropView.addOnLayoutChangeListener { v, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom == oldBottom) return@addOnLayoutChangeListener
            val avail = v.height
            if (avail <= 0) return@addOnLayoutChangeListener
            fullHeightPx = avail.coerceAtLeast(context.dp(200))
            val desired = if (expandedNow) fullHeightPx else defaultHeightPx
            if (desired > 0) applySheetHeight(desired.coerceAtMost(avail))
        }

        // Slide-up entrance instead of the platform's abrupt default.
        root.translationY = context.dpf(400f)
        root.post {
            // ── Pass-37 (#5): a CONTENT-mode sheet's NATURAL height is its ──
            // default-height anchor, captured the moment the first real
            // layout exists. defaultHeightPx used to stay 0 until the first
            // UP-drag happened to capture it — and with it 0, a plain
            // drag-DOWN on a freshly opened sheet fell into the resize
            // branch (sheet.height > 0 + 2 is trivially true) and the
            // release settle then animated the sheet to a height of ZERO:
            // visually gone, but the window — and its dim scrim — stayed.
            // That is the whole "App Picker dragged away, screen stays
            // darkened" regression. With a real anchor from the first
            // layout, the down-drag is the classic translation dismissal it
            // always was, and the settle can never target 0.
            if (expandable && defaultHeightFraction <= 0f && defaultHeightPx <= 0 && root.height > 0) {
                defaultHeightPx = root.height
            }
            root.translationY = root.height.toFloat().coerceAtLeast(context.dpf(400f))
            // FINAL PASS 7 (PART 2): the entrance runnable is running — from
            // here on, a root that never reaches translationY ≈ 0 means the
            // animate() below was cancelled (window-focus transition), not
            // that the entrance hasn't started. ReceivingModal's stranded-
            // sheet reconciliation keys off this flag.
            markEntranceRan()
            root.animate().translationY(0f).setDuration(Motion.DURATION_MEDIUM).setInterpolator(Motion.DECELERATE).start()

            // GOAT v2 (item 8): the visual handle is deliberately small — a
            // 36x4dp bar is the right VISUAL weight for a drag affordance —
            // but its actual touch target used to be that exact same
            // 36x4dp box, a needle-thin sliver that's genuinely hard to
            // land a thumb on. TouchDelegate expands the AREA that routes
            // touches to handle's own listener without changing handle's
            // layout or paint size at all, so the visual design is
            // untouched while the actual grabbable zone becomes the
            // sheet's whole top strip — generous in every direction, but
            // still only at the very top of the sheet, above where
            // scrollable content begins, so it still never competes with a
            // list/grid below it for touch ownership.
            val hitRect = android.graphics.Rect()
            handle.getHitRect(hitRect)
            hitRect.left -= context.dp(24); hitRect.right += context.dp(24)
            hitRect.top -= context.dp(14); hitRect.bottom += context.dp(18)
            root.touchDelegate = android.view.TouchDelegate(hitRect, handle)
        }
    }

    /** ── Pass-35 (#1): nominate a chrome container as an additional drag ──
     *  region. Presses on its EMPTY areas (background, non-interactive
     *  labels, padding) drive the same expand/collapse/dismiss gesture as
     *  the handle; presses that hit-test to an interactive control
     *  (clickable, long-clickable, or a text input) go to that control
     *  untouched. Register title rows and control strips — never the
     *  scrollable body, which keeps its touches for scrolling. */
    fun registerDragRegion(region: View) {
        if (!::sheetRoot.isInitialized) return
        region.setOnTouchListener(HeaderDragRegion(region))
    }

    /** A registered header region's gate + gesture: the touch listener the
     *  region itself uses. Owns the stream ONLY from a DOWN on empty
     *  chrome; every other DOWN declines (returns false) so the control
     *  beneath processes the touch normally. */
    private inner class HeaderDragRegion(private val region: View) : View.OnTouchListener {
        private val tracker = DragTracker(sheetRoot, currentBody())

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN && interactiveAt(region, event.x, event.y)) {
                return false   // a control under the finger keeps its touch
            }
            return tracker.onTouch(v, event)
        }
    }

    /** The body handed to [show] — the sheet's second child (after the
     *  handle); CONTENT-mode flex needs it for restore. */
    private fun currentBody(): View? =
        if (::sheetRoot.isInitialized && sheetRoot is ViewGroup && (sheetRoot as ViewGroup).childCount > 1)
            (sheetRoot as ViewGroup).getChildAt(1) else null

    /** Deepest-descendant hit test + interactivity walk: true when the
     *  press landed on something the user can interact with — a control
     *  (button, row, input) OR a scrollable container (the Files picker's
     *  breadcrumb pill scrolls horizontally inside its drag region) —
     *  because those touches belong to that view, not the sheet. */
    private fun interactiveAt(region: View, x: Float, y: Float): Boolean {
        var hit: View = region
        var px = x; var py = y
        var descend = true
        while (descend) {
            descend = false
            if (hit is ViewGroup) {
                var i = 0
                while (i < (hit as ViewGroup).childCount) {
                    val c = (hit as ViewGroup).getChildAt(i)
                    if (px >= c.left && px < c.left + c.width && py >= c.top && py < c.top + c.height) {
                        px -= c.left; py -= c.top
                        hit = c
                        descend = true
                        break
                    }
                    i++
                }
            }
        }
        var v: View? = hit
        while (v != null && v !== region) {
            if (v.isClickable || v.isLongClickable || v.isFocusableInTouchMode ||
                v is android.widget.HorizontalScrollView || v is android.widget.ScrollView ||
                v is androidx.recyclerview.widget.RecyclerView || v is android.widget.AbsListView) return true
            v = v.parent as? View
        }
        return false
    }

    /** ── Pass-35 (#12): a full-window overlay ABOVE the sheet — the Media ──
     *  Picker's preview rides here, so it still covers the entire window
     *  (sheet included) exactly as it did when that picker owned its own
     *  full-screen dialog. Call after [show]; visibility is the caller's.
     *
     *  ── Pass-36 (#5): the overlay's ELEVATION is raised above the sheet ──
     *  root's. The sheet root carries the sheet's 12dp drop-shadow elevation,
     *  and among siblings elevation — not child order — decides z: an overlay
     *  appended at elevation 0 rendered BEHIND the elevated sheet (the
     *  preview-under-the-sheet regression, and the queue preview that showed
     *  the picker grid instead of the tapped image). One explicit z above the
     *  sheet makes the layering structural, not positional. */
    fun addWindowOverlay(overlay: View) {
        if (!::backdropHost.isInitialized) return
        overlay.elevation = sheetRoot.elevation + 1f
        backdropHost.addView(overlay, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    /** Animated slide-down-then-dismiss, for the drag gesture, the backdrop
     *  tap, and any action row/button that wants the same close feel
     *  `dialog.dismiss()` alone doesn't give.
     *
     *  ── Pass-35 (#2A): dismissal no longer depends on the exit animation
     *  alone. The window's dim leaves IMMEDIATELY (the sheet follows on the
     *  slide), and a bounded completion guarantee closes the window even if
     *  the animation's end action is dropped — the state that used to leave
     *  a fully transparent window (and its modal scrim) darkening the app
     *  with no visible sheet behind it. */
    fun dismiss() {
        if (dismissing || !::sheetRoot.isInitialized) { dialog.dismiss(); return }
        dismissing = true
        heightAnimator?.cancel()
        // ── Pass-36 (#4): the scrim leaves WITH the sheet — same duration, ──
        // same curve, one motion. This is the whole dim cleanup: the scrim is
        // a view in this window, so the moment the window closes (the end
        // action below, or the bounded fallback) the scrim is GONE — there is
        // no window-manager dim state left to reset on any path, and even a
        // pathologically slow surface teardown shows an alpha-0 scrim.
        // Cancel-path dismissals (back key, dialog.cancel()) skip this
        // animation entirely and close the window immediately — the scrim
        // dies with it, so every dismiss method cleans up identically.
        dimScrim?.animate()?.alpha(0f)?.setDuration(Motion.DURATION_SHORT)
            ?.setInterpolator(Motion.STANDARD)?.start()
        sheetRoot.animate()
            .translationY(sheetRoot.height.toFloat() + context.dpf(40f))
            .setDuration(Motion.DURATION_SHORT)
            .setInterpolator(Motion.STANDARD)
            .withEndAction { dialog.dismiss() }
            .start()
        // The bounded guarantee: if the end action never runs (an
        // animation cancelled by the system mid-exit), the window still
        // closes — 350ms is the full animation + margin, so a normal exit
        // is always the end action's, never this fallback's.
        sheetRoot.postDelayed({ if (dialog.isShowing) dialog.dismiss() }, 350)
    }

    /** The drag state machine, shared verbatim by the handle and every
     *  registered header region — one gesture, one owner, one behavior. */
    private inner class DragTracker(private val sheet: View, private val body: View?) : View.OnTouchListener {
        private var startY = 0f
        private var startTranslation = 0f
        private var anchorHeight = 0
        private var resizing = false
        private var contentBodyFlexed = false
        private var lastY = 0f
        private var lastT = 0L
        private var velocityY = 0f

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startY = event.rawY
                    lastY = event.rawY; lastT = android.os.SystemClock.uptimeMillis(); velocityY = 0f
                    startTranslation = sheet.translationY
                    anchorHeight = sheet.height
                    resizing = false
                    heightAnimator?.cancel()
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now > lastT) {
                        velocityY = (event.rawY - lastY) / (now - lastT) * 1000f
                        lastY = event.rawY; lastT = now
                    }
                    val delta = event.rawY - startY
                    if (!expandable) {
                        // The classic gesture: drag DOWN to dismiss, never up.
                        val down = delta.coerceAtLeast(0f)
                        sheet.translationY = startTranslation + down
                    } else if (delta < 0) {
                        // Dragging UP: grow toward full height, finger-tracked.
                        // Any partial down-translation from earlier in this
                        // same gesture snaps back so the sheet edges stay
                        // flush with the screen while it grows.
                        resizing = true
                        sheet.translationY = 0f
                        // CONTENT mode: the body must stretch before the very
                        // first growth or the sheet grows around a wrap-content
                        // body (empty space below it) — the sheet's natural
                        // height is captured FIRST so a later collapse can
                        // restore it exactly.
                        if (!contentBodyFlexed && defaultHeightFraction <= 0f) {
                            if (defaultHeightPx <= 0) defaultHeightPx = sheet.height
                            if (contentModeBodyParams == null) contentModeBodyParams = sheet.layoutParams
                            body?.layoutParams = LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                            contentBodyFlexed = true
                        }
                        val target = (anchorHeight - delta).toInt().coerceAtMost(fullHeightPx)
                        applySheetHeight(target)
                    } else if (defaultHeightPx > 0 && (sheet.height > defaultHeightPx + 2 || resizing)) {
                        // Dragging DOWN while taller than the default: shrink
                        // back toward it (clamped), still finger-tracked.
                        // ── Pass-37 (#5): the branch requires a KNOWN default ──
                        // (defaultHeightPx > 0 — captured at the first layout
                        // since this pass). With an unknown default this is a
                        // plain drag-down at the natural height, which belongs
                        // to the classic translation dismissal below, never to
                        // the resize machinery (a resize against a 0 anchor
                        // could only ever settle to a zero-height sheet that
                        // LOOKS dismissed while the window and its scrim
                        // quietly stay on screen).
                        resizing = true
                        val target = (anchorHeight - delta).toInt().coerceAtLeast(defaultHeightPx)
                        applySheetHeight(target)
                    } else {
                        // At the default height: the classic drag-down-to-
                        // dismiss translation.
                        sheet.translationY = startTranslation + delta
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        // The system took the gesture away mid-flight: settle
                        // from wherever the sheet is, exactly as a release
                        // would — never leave a half-dragged sheet stuck.
                        settleAfterRelease()
                        return true
                    }
                    if (expandable) {
                        if (resizing) settleAfterRelease()
                        else {
                            // A plain drag-down at default height: dismiss past
                            // the threshold, spring back otherwise — exactly
                            // the classic contract.
                            val draggedFraction = sheet.translationY / sheet.height.coerceAtLeast(1)
                            if (draggedFraction > 0.28f) dismiss()
                            else sheet.animate().translationY(0f).setDuration(Motion.DURATION_SHORT)
                                .setInterpolator(Motion.STANDARD).start()
                        }
                    } else {
                        val draggedFraction = sheet.translationY / sheet.height.coerceAtLeast(1)
                        if (draggedFraction > 0.28f) {
                            dismiss()
                        } else {
                            sheet.animate().translationY(0f).setDuration(Motion.DURATION_SHORT).setInterpolator(Motion.STANDARD).start()
                        }
                    }
                    return true
                }
                else -> return false
            }
        }

        /** Release/fling settling for a resize gesture: toward whichever
         *  bound the drag (or a fling) came closest to. The collapse
         *  restore is the Pass-35-fixed path — the body gets the correct
         *  LinearLayout.LayoutParams back, not the bare ViewGroup one that
         *  crashed the App Picker (a LinearLayout child is measured through
         *  a LayoutParams cast it cannot survive). */
        private fun settleAfterRelease() {
            if (!resizing) return
            // Settle toward whichever bound the drag (or a fling) came
            // closest to.
            val flingExpand = velocityY < -1800f
            val flingCollapse = velocityY > 1800f
            // Pass-37 (#5): a settle must never target a height of 0 — an
            // unknown (never-captured) default falls back to the sheet's live
            // height, i.e. "stay as you are", rather than collapsing to an
            // invisible zero-height sheet behind a live scrim.
            val defaultAnchor = defaultHeightPx.takeIf { it > 0 } ?: sheet.height
            val mid = (defaultAnchor + fullHeightPx) / 2
            val cur = sheet.height
            val target = when {
                flingExpand -> fullHeightPx
                flingCollapse && expandedNow -> defaultAnchor
                cur > mid -> fullHeightPx
                else -> defaultAnchor
            }
            if (target == fullHeightPx) {
                animateSheetHeight(target) { setExpanded(true) }
            } else {
                animateSheetHeight(target) {
                    setExpanded(false)
                    // CONTENT-mode sheets restore their natural wrap-content
                    // sizing once collapsed — with the LayoutParams type the
                    // parent LinearLayout actually measures through.
                    if (defaultHeightFraction <= 0f && contentModeBodyParams != null) {
                        body?.layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        sheet.layoutParams = contentModeBodyParams
                        contentBodyFlexed = false
                    }
                }
            }
        }
    }

    private fun attachDragToDismiss(handle: View, sheet: View, body: View?) {
        handle.setOnTouchListener(DragTracker(sheet, body))
    }
}

/**
 * ── Pass-37 (#4): the shared one-live-instance gate for the picker sheets. ──
 *
 * The App Picker has had exactly this protection since Pass-26 (#3.1) — a
 * flag set SYNCHRONOUSLY before any dialog machinery runs and cleared in the
 * one dismiss listener every close path funnels through. The Media, Music and
 * Files pickers had nothing: a rapid double-tap on their tiles (especially a
 * second tap while the first sheet is still entering or busy loading, which
 * reads as "didn't register") built a SECOND full sheet on top of the first —
 * two backdrops, two dim layers, overlapping sheets, and a dismissal that
 * only closed one of them.
 *
 * The gate is keyed per sheet kind, acquired before the sheet's window is
 * created, and released in the dialog's setOnDismissListener — which fires
 * exactly when the window is really gone, after the exit animation, so:
 *   • a tap while a sheet is open (or still animating out) is a no-op;
 *   • the first tap AFTER the previous sheet fully closed opens normally —
 *     no arbitrary delay, no timed window, no unresponsive feel.
 */
object PickerGate {
    private val open = java.util.Collections.synchronizedSet(HashSet<String>())

    /** True when the caller owns the sheet kind's single live slot — call
     *  BEFORE creating the sheet. False means one is already open: return. */
    fun acquire(kind: String): Boolean = open.add(kind)

    /** Releases the slot in the dismiss listener — the one place every close
     *  path (drag, backdrop tap, back key, programmatic) reaches. */
    fun release(kind: String) { open.remove(kind) }
}
