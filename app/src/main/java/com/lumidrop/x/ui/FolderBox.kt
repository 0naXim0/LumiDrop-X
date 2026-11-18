package com.lumidrop.x.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.RecyclerView
import com.lumidrop.x.R

/**
 * GOAT v4 (the folder-expansion scroll box, shared by ALL THREE instances:
 * QueueAdapter's queue, TransfersFragment's received groups, ReceivingModal's
 * incoming-sheet folders).
 *
 * **The v4 rewrite — why the animation system was replaced wholesale.** The
 * v3 version animated expand/collapse with `TransitionManager.
 * beginDelayedTransition(sceneRoot, ChangeBounds + ChangeTransform + Fade)`
 * and flipped the box between `GONE` and `VISIBLE` inside it. That had a
 * real, on-device-confirmed failure class the transition framework cannot
 * avoid here: a `beginDelayedTransition` animates a *snapshot* of the new
 * layout, through animators that override views' bounds/alpha/visibility
 * *visually* — and when the transition is interrupted mid-flight (a second
 * toggle during the 220ms window, a rebind from the 500ms poll tick, a
 * RecyclerView relayout, recycling), its end-of-transition cleanup never
 * runs. The interrupted row is then left with the box still VISIBLE and its
 * bounds pinned at whatever intermediate height the animator was at: the
 * reported "after collapsing, the container keeps the increased height"
 * bug, persistent precisely because the queue's signature-gated rebinds
 * skip unchanged rows, so nothing ever re-applies `GONE`.
 *
 * The fix is architectural, not cosmetic: **FolderBox now owns the entire
 * expand/collapse state machine and drives the height animation itself with
 * a plain [ValueAnimator] on the scroll container's real
 * `layoutParams.height`** (plus `requestLayout()` per frame). Every
 * animation frame IS the actual layout pass, so there is no separate
 * "visual" state that can diverge from the "real" state: cancel-and-restart
 * always converges on the correct final geometry, `isExpanded` is
 * authoritative and set synchronously at call time, and the box's
 * visibility is only ever written by this class. Sibling rows below still
 * slide out of the way during the animation, because each frame's real
 * layout re-positions them — the same visual result ChangeBounds produced,
 * with none of its interruption semantics.
 *
 * Also new in v4, both from the person's on-device report:
 *
 * 1. **The box can never out-grow the list it lives in.** v3 capped it at
 *    a flat dp(280) — taller than the Send queue's own 220dp container,
 *    which is exactly the "its height is bigger than the whole container
 *    and looks ugly" report. The cap is now resolved live at every
 *    expand: walk up to the first real list/scroll viewport ancestor
 *    (RecyclerView / ScrollView / NestedScrollView / ListView) and cap at
 *    [HOST_HEIGHT_FRACTION] of *that* viewport's height, clamped to
 *    [MIN_HEIGHT_DP]..[MAX_HEIGHT_DP]. A 220dp queue gets a 132dp box; a
 *    tall Transfers list still gets up to the 280dp ceiling.
 *
 * 2. **Collapsing never requires scrolling back.** The header row with the
 *    chevron is the primary toggle, but a drag inside the box hands its
 *    leftover movement to the outer list once the box hits its own limit —
 *    which can scroll the header off-screen while the box itself is still
 *    visible. (Pass-26 #7: the small pinned "Collapse" pill that used to
 *    float at the bottom edge for this case is REMOVED — the header control
 *    it duplicated is the one control, per direct feedback; the box still
 *    collapses from the header at any scroll offset, exactly as before.)
 *
 * Carried over unchanged from v3 (all still deliberate, see the v3/v4
 * briefs): the wrap-up-to-cap height (short folder = short box), the
 * `edgeFadeView` fades in the card's surface color, the custom-drawn pill
 * scrollbar indicator (API 21 has no way to style the OS scrollbar),
 * `OVER_SCROLL_NEVER`, `isNestedScrollingEnabled = false`, and the upgraded
 * [Widgets.claimVerticalDrags] (scroll-limit hand-off included).
 *
 * One supporting fix this rewrite forced, worth recording: the old
 * `CappedScrollView.onMeasure` replaced *every* incoming height spec with
 * `AT_MOST(max)` — including `EXACTLY` specs, which is how a parent
 * ( LinearLayout resolving the animated explicit pixel height) asks for an
 * exact size. That silently overrode any explicit height request and made
 * per-frame height animation impossible to honor; the new onMeasure
 * respects the incoming mode and only clamps its size, so an animated
 * `EXACTLY(h)` is measured as exactly h (still never above the cap).
 */
class FolderBox(
    context: Context,
    contentPaddingLeftDp: Int,
    contentPaddingTopDp: Int,
    contentPaddingRightDp: Int,
    contentPaddingBottomDp: Int,
    /** ── GOAT-PASS 12 (§5A): round the BOTTOM fade's corners to this ──
     *  radius (dp), for instances whose box bottom sits flush on a rounded
     *  card — the Incoming modal's expanded folder. The fade is a
     *  full-width rectangle pinned to the box's bottom; on a
     *  bg_btn_secondary card (16dp corners) its opaque band otherwise drew
     *  past the card's rounded corners. 0 keeps every existing instance
     *  byte-identical. */
    fadeBottomCornerRadiusDp: Float = 0f,
) {

    /** The vertical list rows get added to (each call site's own row
     *  builders — unchanged in shape from what they used to add straight
     *  into their own NestedScrollView). */
    val content: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(context.dp(contentPaddingLeftDp), context.dp(contentPaddingTopDp),
            context.dp(contentPaddingRightDp), context.dp(contentPaddingBottomDp))
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** The scroll container itself — wraps its content up to the live cap.
     *  Exposed for the call sites' existing direct uses (`scrollTo` for
     *  fresh-binds/recycles, keying/tagging, scroll-offset capture). The
     *  box's *visibility and height*, however, are now written ONLY by this
     *  class — sites go through [expand]/[collapse]/[setExpandedInstant] so
     *  no stray visibility write can fight the animation state machine. */
    val scroll: NestedScrollView = CappedScrollView(context).apply {
        maxHeightPx = context.dp(MAX_HEIGHT_DP)
        visibility = View.GONE
        // The §2.5 fix, applied here for all three instances at once — see
        // Widgets.claimVerticalDrags's doc comment for the full mechanism.
        isNestedScrollingEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        isVerticalScrollBarEnabled = false
        isFillViewport = false
        addView(content)
        // §3.1 gap #3's other half: the upgraded claimer (scroll-limit
        // hand-off + never-claim-when-content-fits).
        Widgets.claimVerticalDrags(this)
    }

    /** Authoritative expand state — set synchronously by [expand]/
     *  [collapse]/[setExpandedInstant], never by an animation callback, so
     *  accordion logic at the call sites can rely on it immediately. */
    var isExpanded: Boolean = false
        private set

    // (Pass-26 #7: the bottom-edge "Collapse" pill and its onRequestCollapse
    // hook are REMOVED — the header chevron is the one collapse control, as
    // the brief asked. Expansion logic, animations and layout untouched.)

    // The single height animator. Exactly one exists at a time; a new
    // expand/collapse always detaches-and-cancels it first, so a
    // superseded animation can never apply a stale final state.
    private var heightAnim: ValueAnimator? = null

    // Scroll affordances -----------------------------------------------------

    // bg_secondary is the fill of bg_btn_secondary — the exact surface
    // color of the row/card all three instances sit on, so the fades read
    // as "content sinking into the card" rather than as foreign strips.
    // GOAT-PASS 12 (§5A): the bottom fade optionally carries the containing
    // card's corner radius (see the constructor doc).
    private val fadeTop: View = edgeFadeView(context, fromTop = true, surfaceColor = ContextCompat.getColor(context, R.color.bg_secondary))
    private val fadeBottom: View = edgeFadeView(context, fromTop = false, surfaceColor = ContextCompat.getColor(context, R.color.bg_secondary),
        bottomCornerRadiusDp = fadeBottomCornerRadiusDp)

    /** Thin pill position indicator — this app's own scrollbar, drawn by
     *  hand because styling the platform's is impossible below API 29. */
    private val indicator: View = View(context).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = context.dpf(1.5f)
            setColor(ContextCompat.getColor(context, R.color.text_muted))
        }
        visibility = View.GONE
        isClickable = false; isFocusable = false
        layoutParams = FrameLayout.LayoutParams(context.dp(3), context.dp(32), Gravity.END or Gravity.TOP).also {
            it.marginEnd = context.dp(3)
        }
    }

    /** Add THIS to the card/row — a FrameLayout layering [scroll] under
     *  the fade overlays and the scrollbar indicator. Wrap-content height
     *  (the cap lives in [scroll]), match-parent width. (Pass-26 #7: the
     *  collapse pill that used to layer here as a fourth child is removed.)
     */
    val root: FrameLayout = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        addView(scroll, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(fadeTop)
        addView(fadeBottom)
        addView(indicator)
    }

    // Insets the indicator travels within (top/bottom margins inside the
    // box) — kept visually clear of the fade peaks.
    private val indicatorInsetTopDp = 4
    private val indicatorInsetBottomDp = 4

    private val uiHandler = Handler(Looper.getMainLooper())
    private val indicatorIdleFade = Runnable {
        indicator.animate().alpha(0.4f).setDuration(Motion.DURATION_MEDIUM).start()
    }

    init {
        (scroll as CappedScrollView).box = this

        // ── GOAT-PASS 11 (§9 — the ROOT is the exclusion marker). The ──────────────────
        // hold-and-slide gesture's hit-test exclusion (attachDragSelect's
        // hitExcluded) used to key on the SCROLLER's and CONTENT's tags —
        // but the fade strips and the scrollbar indicator sit ON TOP of
        // the scroller as SIBLINGS under this root, so a touch on a rim
        // strip resolved through an ancestry that carried NEITHER tag,
        // fell out of the exclusion, and the gesture's long-press armed
        // against the PARENT folder row — a scroll started with a hold on
        // the rim selected the parent. The root tag is a property of the
        // component (exactly like the click-consumption below): every
        // view inside the box — scroller, content, fades, indicator, and
        // anything added later — resolves through THIS node, and the
        // drag-select call sites match it alongside their own tags.
        root.tag = "folder_box"

        // ── GOAT-PASS 10 (§6 — the box's own tap containment, structural) ──
        // A tap that lands on any of the box's NON-INTERACTIVE areas (the
        // content's padding, the gaps between rows, the scroller's own
        // margins) must terminate INSIDE the box. Previously this was
        // enforced per call site (TransfersFragment + QueueAdapter set
        // scroll/content clickable in GOAT-PASS 9 §11; the ReceivingModal's
        // instance never got it) — and any spot none of those layers covered
        // fell through to whatever consumed next, which on the Received tab
        // is the PARENT FOLDER CARD: a tap (or a scroll gesture that never
        // exceeded the touch slop) on the box activated the parent's own
        // click/long-press — the repeatedly reported accidental parent
        // SELECTION. Owning the consumption here makes the defense a property
        // of the component itself: root, scroller and content are clickable
        // with a deliberate no-op click; children still dispatch FIRST (a
        // clickable parent only sees events nothing beneath it claimed), and
        // scrolling is untouched (drag handling never consults
        // clickability; claimVerticalDrags' listener still returns false).
        //
        // The FADE strips and the INDICATOR are deliberately left
        // NON-clickable: they sit ON TOP of the scroller as SIBLINGS, and a
        // sibling cannot hand a gesture it consumed back to the scroller —
        // a clickable fade would swallow drags started on the very rim
        // strips people grab to scroll. Non-clickable, a press on them
        // falls through the dispatch order to the clickable scroller right
        // beneath, which is what keeps those strips BOTH tap-contained AND
        // draggable.
        root.isClickable = true
        scroll.isClickable = true
        content.isClickable = true

        // Every scroll and every layout pass re-derives both fades and the
        // indicator from live measurements — nothing about overflow is
        // cached, because the right answer changes as rows are added,
        // excluded, loaded, or as the box itself animates to its cap. Only
        // mutating values when they actually changed keeps this from
        // re-entering layout (setting a differing visibility requests
        // layout, which re-fires this listener: the loop terminates exactly
        // one pass after values stabilize).
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> refreshIndicators(pokeIndicator = true) }
        root.viewTreeObserver.addOnGlobalLayoutListener { refreshIndicators(pokeIndicator = false) }
    }

    // Expand / collapse state machine -----------------------------------------

    /**
     * Expands this box: resolves the live container-aware cap, makes the
     * box visible, and (when [animated]) grows it from its current height
     * to min(cap, content height) on the app's standard motion curve.
     * Content-fade-in rides the same animator. Safe to call repeatedly —
     * e.g. once at toggle time and again after an async walk replaces
     * "Loading…" with the real tree — each call re-measures and animates
     * from wherever the box currently is.
     */
    fun expand(animated: Boolean = true) {
        isExpanded = true
        cancelHeightAnim()
        refreshCap()
        if (scroll.visibility != View.VISIBLE) {
            // Coming from GONE (or a fresh bind): grow from zero.
            scroll.layoutParams.height = 0
            scroll.visibility = View.VISIBLE
            scroll.alpha = if (animated) 0f else 1f
        }
        val target = measureNaturalHeightPx()
        if (!animated || target <= 0) {
            settleExpanded()
            return
        }
        val from = currentHeightPx()
        if (from >= target) {
            // Already at/above the target (re-expand of a cached-walk box,
            // or the cap just shrank below the current height): settle now;
            // the following layout pass re-clamps a taller box to the cap.
            settleExpanded()
            return
        }
        startHeightAnim(from, target, targetAlpha = 1f)
    }

    /**
     * Collapses this box: shrinks it to zero (fading its content out with
     * the same motion) and only then hides it — deterministically, whether
     * the animation runs to completion or is superseded by a new call.
     */
    fun collapse(animated: Boolean = true) {
        isExpanded = false
        if (scroll.visibility != View.VISIBLE) {
            cancelHeightAnim()
            settleCollapsed()
            return
        }
        cancelHeightAnim()
        if (!animated) {
            settleCollapsed()
            return
        }
        val from = currentHeightPx()
        if (from <= 0) {
            settleCollapsed()
            return
        }
        startHeightAnim(from, 0, targetAlpha = 0f)
    }

    /** Applies an expand state with no animation — the bind/recycle path.
     *  Cancels any in-flight animation first so a rebound row always lands
     *  on exact final state. */
    fun setExpandedInstant(expanded: Boolean) {
        isExpanded = expanded
        cancelHeightAnim()
        if (expanded) {
            refreshCap()
            scroll.visibility = View.VISIBLE
            scroll.alpha = 1f
            scroll.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        } else {
            scroll.visibility = View.GONE
            scroll.alpha = 1f
            scroll.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        }
        scroll.requestLayout()
    }

    /** The height the box should animate/settle to right now: the content
     *  measured with an UNSPECIFIED height spec (exactly how the layout
     *  system itself resolves wrap_content), clamped to the live cap. The
     *  explicit width spec mirrors what CappedScrollView will hand the
     *  content on the next real pass, so the target matches what the box
     *  will actually measure once WRAP_CONTENT settles. */
    private fun measureNaturalHeightPx(): Int {
        val cap = (scroll as CappedScrollView).maxHeightPx
        val w = root.width.takeIf { it > 0 }
            ?: (root.parent as? View)?.width?.takeIf { it > 0 }
        val wSpec = if (w != null) View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY)
        else View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        content.measure(wSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val natural = content.measuredHeight
        return if (cap > 0) natural.coerceAtMost(cap) else natural
    }

    /** The box's current visual height — the explicit animated pixel
     *  height while one is in flight (set before its layout completes, so
     *  it is the freshest value), else the last laid-out height. */
    private fun currentHeightPx(): Int {
        val lpH = scroll.layoutParams.height
        return if (lpH >= 0) lpH else scroll.height.coerceAtLeast(0)
    }

    /** Walks up from the box to the first real list/scroll viewport
     *  ancestor (RecyclerView / ListView / ScrollView / NestedScrollView)
     *  and re-derives the height cap from ITS height: the container the
     *  expanded row actually lives inside. This is what keeps an expanded
     *  box proportional to (and never larger than) the list it sits in —
     *  the Send queue's fixed 220dp list yields a 132dp box, a tall
     *  Transfers list still gets up to the dp(280) ceiling. Non-scrolling
     *  intermediates (row cards, padding containers) are walked past; an
     *  unmeasured/unattached host falls back to the absolute ceiling. */
    private fun refreshCap() {
        val ctx = root.context
        var host: View? = null
        var node: View = root
        while (true) {
            val parent = node.parent as? View ?: break
            if (parent is RecyclerView || parent is AbsListView ||
                parent is ScrollView || parent is NestedScrollView) {
                host = parent
                break
            }
            node = parent
        }
        val hostH = host?.let { (it.height - it.paddingTop - it.paddingBottom).coerceAtLeast(0) } ?: 0
        val cap = if (hostH > 0) {
            (hostH * HOST_HEIGHT_FRACTION).toInt().coerceIn(ctx.dp(MIN_HEIGHT_DP), ctx.dp(MAX_HEIGHT_DP))
        } else {
            ctx.dp(MAX_HEIGHT_DP)
        }
        (scroll as CappedScrollView).maxHeightPx = cap
    }

    private fun startHeightAnim(from: Int, to: Int, targetAlpha: Float) {
        val anim = ValueAnimator.ofFloat(0f, 1f)
        anim.duration = Motion.DURATION_MEDIUM
        anim.interpolator = Motion.STANDARD
        val alphaFrom = scroll.alpha
        anim.addUpdateListener { a ->
            val t = a.animatedValue as Float
            scroll.layoutParams.height = (from + (to - from) * t).toInt().coerceAtLeast(0)
            scroll.alpha = alphaFrom + (targetAlpha - alphaFrom) * t
            scroll.requestLayout()
        }
        anim.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                // Only the CURRENT animator may finalize state — a
                // superseded one had its listeners detached before cancel
                // and never reaches this branch.
                if (heightAnim === animation) {
                    heightAnim = null
                    if (to > 0) settleExpanded() else settleCollapsed()
                }
            }
        })
        heightAnim = anim
        anim.start()
    }

    /** Final expanded state: visible, full opacity, WRAP_CONTENT height —
     *  CappedScrollView's AT_MOST cap is what keeps WRAP_CONTENT bounded,
     *  so the settled height equals the animation target exactly and later
     *  content changes (walk completion, exclusions) re-measure naturally. */
    private fun settleExpanded() {
        scroll.visibility = View.VISIBLE
        scroll.alpha = 1f
        scroll.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        scroll.requestLayout()
    }

    /** Final collapsed state: hidden, height back to WRAP_CONTENT so the
     *  next expand re-measures from the (possibly changed) content. */
    private fun settleCollapsed() {
        scroll.visibility = View.GONE
        scroll.alpha = 1f
        scroll.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
        scroll.requestLayout()
    }

    private fun cancelHeightAnim() {
        heightAnim?.let { a ->
            heightAnim = null
            // Detach BEFORE cancel so the superseded animator's end
            // listener never fires its (now stale) finalization.
            a.removeAllListeners()
            a.removeAllUpdateListeners()
            a.cancel()
        }
    }

    /** Fades + indicator state, derived fresh from current
     *  measurements. [pokeIndicator] restores the indicator to full alpha
     *  and re-arms its idle dim (a real scroll just happened); layout-only
     *  passes leave its idle timer alone. */
    private fun refreshIndicators(pokeIndicator: Boolean) {
        // Post-v1.0 fix (§collapse-height residual): a COLLAPSED box must
        // never leave any overlay child measurable. The old body derived
        // overflow from content.height vs scroll.height — but when the box
        // collapses, scroll is GONE (height 0) while `content` (measured
        // with an UNSPECIFIED spec by NestedScrollView's fillViewport=false
        // path) KEEPS its full natural height, so overflow computed
        // positive and fadeBottom (18dp) + the indicator (fixed 32dp tall)
        // stayed VISIBLE inside the wrap-content root FrameLayout — the
        // box measured ~32dp tall after collapsing instead of the 0 it was
        // before its first expansion, i.e. the row "slightly grows" after
        // a collapse. Guarding on the authoritative expanded state (and
        // the scroll's actual visibility, which covers mid-animation
        // settle frames too) hides every overlay deterministically.
        if (!isExpanded || scroll.visibility != View.VISIBLE) {
            fadeTop.visibility = View.GONE
            fadeBottom.visibility = View.GONE
            indicator.visibility = View.GONE
            return
        }
        val childHeight = content.height
        val boxHeight = scroll.height
        val overflow = childHeight - boxHeight
        if (overflow <= 0) {
            fadeTop.visibility = View.GONE
            fadeBottom.visibility = View.GONE
            indicator.visibility = View.GONE
            return
        }
        val sy = scroll.scrollY
        fadeBottom.visibility = if (sy < overflow) View.VISIBLE else View.GONE
        fadeTop.visibility = if (sy > 0) View.VISIBLE else View.GONE

        val ctx = root.context
        val insetTop = ctx.dp(indicatorInsetTopDp)
        val insetBottom = ctx.dp(indicatorInsetBottomDp)
        val trackHeight = (boxHeight - insetTop - insetBottom).coerceAtLeast(1)
        val visibleFraction = (boxHeight.toFloat() / childHeight).coerceIn(0.15f, 1f)
        val thumbHeight = (trackHeight * visibleFraction).toInt().coerceAtLeast(ctx.dp(28)).coerceAtMost(trackHeight)
        val maxTravel = trackHeight - thumbHeight
        val travel = (sy.toFloat() / overflow * maxTravel).toInt()
        if (indicator.layoutParams.height != thumbHeight) {
            indicator.layoutParams.height = thumbHeight
            indicator.requestLayout()
        }
        indicator.translationY = (insetTop + travel).toFloat()
        indicator.visibility = View.VISIBLE
        if (pokeIndicator) {
            indicator.animate().cancel()
            indicator.alpha = 1f
            uiHandler.removeCallbacks(indicatorIdleFade)
            uiHandler.postDelayed(indicatorIdleFade, INDICATOR_IDLE_DELAY_MS)
        }
    }

    /** A NestedScrollView whose height wraps its content up to
     *  [maxHeightPx] instead of being a fixed block. The v3 version
     *  replaced every incoming height spec with AT_MOST(max) — which also
     *  clobbered EXACTLY specs, silently overriding any explicit height a
     *  parent asked for (and making per-frame height animation impossible
     *  to honor). This one respects the incoming mode and only clamps the
     *  size: an animated EXACTLY(h ≤ cap) now measures as exactly h, and
     *  the belt-and-braces final clamp after super() still guarantees the
     *  cap is never exceeded under any spec combination. */
    private class CappedScrollView(context: Context) : NestedScrollView(context) {
        var maxHeightPx: Int = 0

        /** Back-reference for [ownerOf] — call sites hold the scroll view
         *  (typed NestedScrollView) and sometimes need the owning box. */
        internal var box: FolderBox? = null

        /** Pass-8 (§12 — Received-tab folder expansion scrolling): a drag
         *  that starts on a CLICKABLE child row (any completed file inside
         *  the expanded tree — most of the box's area) delivers its DOWN to
         *  the child, not to this scroll view, so Widgets.claimVerticalDrags'
         *  DOWN-time parent lock never engages for that gesture. On MOVE, the
         *  OUTER RecyclerView's onInterceptTouchEvent runs FIRST in the
         *  dispatch chain and steals the drag — the expanded content never
         *  scrolled no matter how the person swiped. The fix lives here, at
         *  the one place every gesture into this subtree MUST pass through
         *  regardless of which child ends up consuming the DOWN: dispatch
         *  runs BEFORE interception, so asking the ancestor chain not to
         *  intercept on the very first event keeps MOVE delivery inside this
         *  subtree — claimVerticalDrags' joined-late handling then claims the
         *  gesture (this view can scroll) and its deterministic
         *  edge-hand-off feeds the outer list once the box is pinned at
         *  either limit. A box that CANNOT scroll never asks, so outer-list
         *  drags over a fitting box behave exactly as before. ViewGroup
         *  resets the disallow flag on every new ACTION_DOWN, so a plain tap
         *  on a child leaves nothing stuck behind. */
        override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN &&
                (canScrollVertically(1) || canScrollVertically(-1))) {
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            return super.dispatchTouchEvent(ev)
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            if (maxHeightPx <= 0) {
                super.onMeasure(widthMeasureSpec, heightMeasureSpec)
                return
            }
            val mode = View.MeasureSpec.getMode(heightMeasureSpec)
            val size = View.MeasureSpec.getSize(heightMeasureSpec)
            val clampedSpec = when (mode) {
                View.MeasureSpec.EXACTLY ->
                    View.MeasureSpec.makeMeasureSpec(size.coerceAtMost(maxHeightPx), View.MeasureSpec.EXACTLY)
                View.MeasureSpec.AT_MOST ->
                    View.MeasureSpec.makeMeasureSpec(size.coerceAtMost(maxHeightPx), View.MeasureSpec.AT_MOST)
                else -> View.MeasureSpec.makeMeasureSpec(maxHeightPx, View.MeasureSpec.AT_MOST)
            }
            super.onMeasure(widthMeasureSpec, clampedSpec)
            if (measuredHeight > maxHeightPx) setMeasuredDimension(measuredWidth, maxHeightPx)
        }
    }

    companion object {
        /** Absolute ceiling — tall enough that a real folder's tree is
         *  comfortably browsable in place, small enough that one expanded
         *  folder never eats the screen. The *live* cap is usually smaller
         *  (see [HOST_HEIGHT_FRACTION]). */
        const val MAX_HEIGHT_DP = 280

        /** Floor for the container-proportional cap — keeps a very short
         *  host list from shrinking the box into uselessness. */
        const val MIN_HEIGHT_DP = 112

        /** Fraction of the hosting list/scroll viewport that an expanded
         *  box may occupy — the "never taller than the container it lives
         *  in" rule. 60% keeps the folder's header row, the rows around
         *  it, and the list's own context on screen while browsing. */
        const val HOST_HEIGHT_FRACTION = 0.6f

        private const val INDICATOR_IDLE_DELAY_MS = 1400L

        /** The FolderBox owning [scrollView] (any site's `folderBox.scroll`
         *  reference), or null — for call sites that reach the scroll view
         *  through a tagged lookup rather than a stored field. */
        fun ownerOf(scrollView: View?): FolderBox? = (scrollView as? CappedScrollView)?.box

        /** Chevron rotation for an expand toggle — animated from wherever
         *  the chevron currently is (ViewPropertyAnimator is
         *  interruption-safe by construction: a superseded animation
         *  simply starts from the current rotation), on the same motion
         *  tokens as the height animation so the two read as one gesture. */
        fun animateChevron(chevron: View?, expanded: Boolean) {
            chevron ?: return
            chevron.animate().rotation(if (expanded) 90f else 0f)
                .setDuration(Motion.DURATION_MEDIUM)
                .setInterpolator(Motion.STANDARD)
                .start()
        }
    }
}
