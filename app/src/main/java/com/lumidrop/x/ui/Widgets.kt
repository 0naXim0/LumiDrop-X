package com.lumidrop.x.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.lumidrop.x.R

/**
 * ── Pass-27 (#4): dialog/activity window chrome now lives in SystemBarChrome ──
 *
 * The Pass-26 helper `Dialog.applyLdxSystemBarColors` (bar COLORS only) grew
 * into [com.lumidrop.x.ui.applyLdxWindowChrome] — colors for every window
 * (still never black) PLUS the "Hide Navigation Bar" preference applied to
 * each dialog window that owns the screen, so the Android navigation buttons
 * stay hidden across every sheet, modal, picker and sub-settings screen in
 * the app. See SystemBarChrome.kt for the full mechanism; every construction
 * site that used to call applyLdxSystemBarColors calls applyLdxWindowChrome
 * now (same one-line discipline, complete behavior).
 */

/**
 * Shared, reusable widget builders for the Phase 15-17 design system.
 *
 * MainActivity, TransfersActivity and SettingsActivity each used to carry
 * near-identical private copies of "make me a rounded button", "make me a
 * section label", etc. Centralising them here means every screen a) looks
 * consistent by construction rather than by convention, and b) only needs
 * fixing in one place if the design language changes again later — see the
 * brief's own "no layout or component needs to be refactored twice".
 *
 * Deliberately built from framework/AndroidX-core primitives only (no
 * com.google.android.material.*) — see UiTokens.kt's doc comment for why.
 */
object Widgets {

    /** Forces a scrollable view to keep a drag gesture that starts inside
     *  it, instead of an ancestor's own scrolling stealing it partway
     *  through. GOAT (Phase 2 regression, reported after testing): every
     *  folder-expansion NestedScrollView in this app sits inside SOME
     *  further scrolling ancestor — QueueAdapter's inside a RecyclerView
     *  that's itself inside fragment_send.xml's own page-level
     *  NestedScrollView (three levels deep), SendFragment's incoming-sheet
     *  one inside a plain android.widget.ScrollView (which doesn't
     *  implement the nested-scrolling protocol at all), TransfersFragment's
     *  inside a RecyclerView with no further outer NestedScrollView above
     *  it. That last one is the only one ever actually verified working
     *  end to end -- the other two were assumed to cooperate the same way
     *  by analogy, without accounting for what's different about them: a
     *  plain ScrollView never participating in nested scrolling at all,
     *  or a genuinely competing outer NestedScrollView that has real
     *  content of its own to scroll and can claim the gesture first. The
     *  nested-scrolling protocol (NestedScrollingChild/Parent) is
     *  COOPERATIVE — it only works if every ancestor plays along, and a
     *  plain ScrollView simply doesn't. requestDisallowInterceptTouchEvent
     *  is the other, blunter Android mechanism for the same goal: it
     *  propagates up through every ancestor's onInterceptTouchEvent
     *  regardless of type, plain ScrollView included, so setting it
     *  explicitly the moment a touch lands on the inner scrollable view —
     *  rather than trusting each ancestor to correctly defer through the
     *  cooperative protocol — is what actually guarantees the inner box
     *  keeps the gesture everywhere it's used, not just in the one
     *  configuration that happened to already work.
     *
     *  GOAT v2 (retest regression — this function alone still wasn't
     *  enough): requestDisallowInterceptTouchEvent only blocks the classic
     *  touch-INTERCEPTION path (ViewGroup.onInterceptTouchEvent). It has no
     *  effect on the separate, cooperative Nested Scrolling API
     *  (NestedScrollingChild/Parent — startNestedScroll/
     *  dispatchNestedPreScroll), which a NestedScrollView participates in
     *  by default (isNestedScrollingEnabled defaults to true) regardless of
     *  this fix. Where a genuine NestedScrollingParent sits further up the
     *  tree with real content of its own to scroll — fragment_send.xml's
     *  own root NestedScrollView, for the Queue's case; found by walking
     *  UP PAST the RecyclerView in between, since RecyclerView doesn't
     *  itself arbitrate this the way an explicit ancestor NestedScrollView
     *  does — the inner box proactively offers that ancestor first refusal
     *  on every drag delta via dispatchNestedPreScroll BEFORE scrolling its
     *  own content. Whatever the ancestor consumes never reaches the inner
     *  box at all: a fraction of every finger-move goes to the page instead
     *  of the folder tree, which is exactly what reads as laggy, stuttery,
     *  "doesn't really scroll" — not a clean fix and not a clean total
     *  failure either, which is what made it easy to miss by reading alone.
     *  This is a second, independent mechanism from the one this function
     *  addresses, so a caller using this function for a box that must own
     *  100% of its own drag needs BOTH fixes together: this function, AND
     *  isNestedScrollingEnabled = false set directly on that box (disabling
     *  its participation in the cooperative protocol entirely, so there is
     *  no ancestor to hand delta to in the first place — regardless of
     *  whether one exists or would otherwise qualify). Set at all three
     *  call sites (QueueAdapter, SendFragment, TransfersFragment) for the
     *  same reasoning this function itself already uses: harmless no-op
     *  anywhere a competing NestedScrollingParent ancestor doesn't actually
     *  exist (SendFragment's plain ScrollView, TransfersFragment's
     *  unwrapped root), the actual fix exactly where one does (Queue).
     *
     *  GOAT v3 (§3.1 gap #3 — two more refinements, both reported-through:
     *  "I was scrolling and it just stopped working"):
     *
     *  a) **Never claim a drag a box can't scroll with in the first
     *     place.** The old version set the parent lock unconditionally on
     *     ACTION_DOWN — including for a box whose content FITS inside it
     *     (canScrollVertically fails in both directions), for which
     *     claiming meant the drag went nowhere at all: the box can't
     *     scroll it AND every ancestor is locked out of it. A dead
     *     gesture, from the person's point of view indistinguishable from
     *     "scrolling broken". Now the lock is only taken when the box can
     *     actually scroll; a content-fits box lets the page/list behind
     *     it scroll naturally from the very first pixel.
     *
     *  b) **Hand the leftover movement to the ancestor at the box's own
     *     limit — same finger, no lift.** Once a drag starts inside a
     *     scrollable box and claims it, the ancestor is blocked for the
     *     rest of that one continuous touch — that is what makes the box
     *     scroll cleanly at all, and stays. But when the finger reaches
     *     the box's own top/bottom mid-gesture, the OLD behavior just
     *     went dead until the finger lifted (the accepted trade-off the
     *     v3 brief explicitly called out and asked to either accept
     *     deliberately or smooth over). Smoothed over: on each move, when
     *     the box is pinned at its edge in the direction the finger is
     *     now travelling, the FIRST scrollable ancestor is fed the exact
     *     same per-move delta it would have consumed itself
     *     (`scrollBy(0, lastY - y)` — the platform ScrollView's own touch
     *     convention, sign for sign). Per-pixel continuity, and the
     *     finger reversing back into the box's scrollable range resumes
     *     the box's own scrolling seamlessly, because the box never lost
     *     the event stream.
     *
     *     Deliberately MANUAL feeding rather than releasing the lock
     *     (`requestDisallowInterceptTouchEvent(false)`) at the limit and
     *     letting the parent intercept: interception mid-gesture is the
     *     one path whose delta bookkeeping varies by container
     *     implementation (a parent that skipped every event while the
     *     lock was held has a stale reference point, and can jump by the
     *     whole accumulated drag in one frame on some container types).
     *     Feeding the parent a per-move scrollBy is deterministic on
     *     every ancestor this app has — RecyclerView, plain ScrollView,
     *     NestedScrollView — and also works on the plain-ScrollView case
     *     (the incoming sheet) that never participates in any protocol at
     *     all. scrollBy self-clamps at every container's own bounds, so
     *     over-feeding past the ancestor's content is a harmless no-op.
     *     Note the hand-off is one level deep: at the box's limit the
     *     first scrollable ancestor takes over; once THAT is also pinned,
     *     movement stops until a fresh gesture, which is standard
     *     platform behavior for nested scrolling. */
    fun claimVerticalDrags(view: View) {
        val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
        var lastY = 0f
        var downY = 0f
        var dragging = false
        var claimed = false
        // Pass-3 (R9c — folder scrolling fights the list): tracks whether
        // THIS listener has actually seen the gesture's DOWN. A drag that
        // starts on a clickable CHILD (a file row inside the expanded
        // folder) delivers its DOWN to the row, not to this container —
        // this container only joins the event stream AFTER it intercepts
        // the drag mid-gesture. Without the flag, that joined-late gesture
        // ran with stale down/last coordinates and NEVER took the parent
        // lock, so the outer RecyclerView was still free to steal the drag
        // right after the box scrolled its first few pixels — the exact
        // "scrolling inside expanded folders is glitchy" report.
        var inGesture = false
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    inGesture = true
                    downY = event.y; lastY = event.y
                    dragging = false
                    // Only a box that can actually scroll may claim the
                    // gesture — see doc note (a) above.
                    claimed = v.canScrollVertically(1) || v.canScrollVertically(-1)
                    if (claimed) v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    // Joined-late gesture (started on a clickable child,
                    // intercepted into this container): initialize the
                    // tracking and take the parent lock NOW.
                    if (!inGesture) {
                        inGesture = true
                        downY = event.y; lastY = event.y
                        dragging = false
                        claimed = v.canScrollVertically(1) || v.canScrollVertically(-1)
                        if (claimed) v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    if (!dragging && kotlin.math.abs(event.y - downY) > touchSlop) dragging = true
                    if (dragging && claimed) {
                        val atTop = !v.canScrollVertically(-1)
                        val atBottom = !v.canScrollVertically(1)
                        val fingerDown = event.y > lastY
                        val pinned = (fingerDown && atTop) || (!fingerDown && atBottom)
                        if (pinned) {
                            // Feed the ancestor the same delta it would
                            // have consumed natively — see doc note (b).
                            scrollableAncestor(v)?.scrollBy(0, (lastY - event.y).toInt())
                        }
                    }
                    lastY = event.y
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (claimed) v.parent?.requestDisallowInterceptTouchEvent(false)
                    claimed = false; dragging = false; inGesture = false
                }
            }
            false
        }
    }

    /** First ancestor of [start] that can scroll vertically at all right
     *  now (checked live, per gesture, via canScrollVertically — works for
     *  RecyclerView, ScrollView and NestedScrollView alike, no type checks
     *  needed). Non-scrolling intermediates (row cards, padding containers)
     *  are walked past; returns null at the window root when nothing above
     *  can take the movement. */
    private fun scrollableAncestor(start: View): ViewGroup? {
        var p = start.parent as? ViewGroup
        while (p != null) {
            if (p.canScrollVertically(1) || p.canScrollVertically(-1)) return p
            p = p.parent as? ViewGroup
        }
        return null
    }

    /** A rounded, ripple-fed rectangle — the programmatic equivalent of
     *  bg_btn_primary.xml/bg_btn_secondary.xml, for spots that build the
     *  Drawable at runtime (dynamic Start/Stop coloring, status pills, tab
     *  selection state) instead of via a static XML background. Always
     *  `.mutate()`'d internally so tinting one instance never bleeds into
     *  every other View sharing the same drawable resource/constant state. */
    fun rippleRect(
        context: Context,
        @ColorInt fillColor: Int,
        radiusDp: Int = 14,
        @ColorInt strokeColor: Int? = null,
        strokeWidthDp: Float = 1f,
        @ColorInt rippleColor: Int = ContextCompat.getColor(context, R.color.overlay_press)
    ): Drawable {
        val radiusPx = context.dpf(radiusDp.toFloat())
        val base = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(fillColor)
            if (strokeColor != null) setStroke(context.dp(strokeWidthDp.toInt()).coerceAtLeast(1), strokeColor)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx
            setColor(android.graphics.Color.WHITE)
        }
        // v1.0 (§ripple-stretch): the ripple drawable's own outline is the
        // exact rounded shape, so any view it backs can (and should) set
        // clipToOutline — see Widgets.iconButton/ripplePill call sites. The
        // drawable itself derives a correct rounded outline automatically
        // from its cornerRadius, which is what makes that clip exact.
        return RippleDrawable(android.content.res.ColorStateList.valueOf(rippleColor), base, mask)
    }

    /** v1.0 (§ripple-stretch): assigns [drawable] as [view]'s background AND
     * clips all drawing to the background's own outline — the canonical fix
     * for the touch-ripple stretching visibly outside custom rounded/pill
     * containers. Call this everywhere a ripple-backed background is set on
     * a tappable view instead of assigning background directly. */
    fun View.applyRippleBackground(drawable: Drawable) {
        background = drawable
        clipToOutline = true
    }

    /** The circular counterpart to rippleRect() above — a bordered, ripple-
     *  fed circle for the server card's start/stop toggle (Phase 18: was a
     *  full-width rippleRect button, now the prototype's round icon-only
     *  control). Same "build fresh, no shared constant state" reasoning;
     *  no .mutate() needed for the same reason rippleRect doesn't need one
     *  either -- these GradientDrawables are constructed new each call,
     *  never loaded from a shared resource id. */
    fun circleToggle(
        context: Context,
        @ColorInt fillColor: Int,
        @ColorInt strokeColor: Int,
        strokeWidthDp: Float = 1f,
        @ColorInt rippleColor: Int = ContextCompat.getColor(context, R.color.overlay_press)
    ): Drawable {
        val base = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(fillColor)
            setStroke(context.dp(strokeWidthDp.toInt()).coerceAtLeast(1), strokeColor)
        }
        val mask = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(android.graphics.Color.WHITE)
        }
        return RippleDrawable(android.content.res.ColorStateList.valueOf(rippleColor), base, mask)
    }

    /** Loads a vector icon and tints a fresh, un-shared copy of it — always
     *  `.mutate()` first, since Android caches/shares Drawable constant
     *  state across every place that references the same resource id;
     *  skipping mutate() here would make tinting one icon silently re-tint
     *  every other use of that same icon elsewhere in the app. */
    fun icon(context: Context, @DrawableRes res: Int, @ColorInt tint: Int): Drawable {
        val d = (ResourcesCompat.getDrawable(context.resources, res, context.theme)
            ?: ContextCompat.getDrawable(context, res)!!).mutate()
        d.setTint(tint)
        return d
    }

    /** GOAT (Phase 3, item 7 -- Settings floating Save button, API 21):
     *  the exact same un-shared-constant-state issue icon() above already
     *  guards against, just hit via a *background* instead of an icon --
     *  `view.background = ContextCompat.getDrawable(...); view.
     *  backgroundTintList = ColorStateList.valueOf(...)` never mutates the
     *  drawable it loads, so the tint call was mutating a constant state
     *  potentially shared with every other view referencing the same
     *  drawable resource, on top of setBackgroundTintList's own native
     *  tinting path having a well-documented GradientDrawable rounded-
     *  corner rendering bug specifically on API 21 (correct on both older
     *  and newer OS versions) -- together, what "renders as an opaque
     *  rectangle" was actually reporting. AppCompatResources (rather than
     *  ContextCompat) plus DrawableCompat.wrap()+mutate()+setTintList()
     *  is the AndroidX-recommended pairing precisely because it degrades
     *  to a safe, non-native color-filter-based tinting strategy on API
     *  levels where the platform's own tint pipeline is unreliable,
     *  instead of trusting the OS to get it right — "a tinting approach
     *  that degrades correctly on API 21" is what this buys, for any
     *  drawable type, not only vector ones. */
    fun tintedBackground(context: Context, @DrawableRes res: Int, @ColorInt tint: Int): Drawable {
        val base = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, res)!!
        val wrapped = androidx.core.graphics.drawable.DrawableCompat.wrap(base).mutate()
        androidx.core.graphics.drawable.DrawableCompat.setTintList(wrapped, android.content.res.ColorStateList.valueOf(tint))
        return wrapped
    }

    /** A small square icon-only "button" — the ImageView equivalent of the
     *  emoji TextViews (btnSettings "⚙", btnBlackout "⬛", etc.) it replaces,
     *  with a properly bounded circular ripple instead of a plain click. */
    fun iconButton(
        context: Context, @DrawableRes iconRes: Int, @ColorInt tint: Int,
        sizeDp: Int = 40, iconSizeDp: Int = 20, contentDescription: String? = null
    ): ImageView = ImageView(context).apply {
        setImageDrawable(icon(context, iconRes, tint))
        background = ContextCompat.getDrawable(context, R.drawable.bg_icon_ripple)
        // v1.0 (§ripple-stretch): clips ALL drawing — including the ripple —
        // to the view's rounded/oval outline, so the press feedback can
        // never visibly stretch past the button's real shape onto the
        // surrounding card. This is the canonical fix for the
        // "ripple stretches outside custom rounded containers" glitch.
        clipToOutline = true
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = context.dp((sizeDp - iconSizeDp) / 2)
        setPadding(pad, pad, pad, pad)
        isClickable = true; isFocusable = true
        this.contentDescription = contentDescription
        layoutParams = ViewGroup.LayoutParams(context.dp(sizeDp), context.dp(sizeDp))
    }

    /** Section-card wrapper: rounded elevated surface + vertical padding,
     *  matching the CardView look used everywhere but for spots (Settings
     *  groups, sheets) that are simplest to build purely in Kotlin. */
    fun sectionCard(context: Context, paddingDp: Int = 16): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = ContextCompat.getDrawable(context, R.drawable.bg_card_elevated)
        setPadding(context.dp(paddingDp), context.dp(paddingDp), context.dp(paddingDp), context.dp(paddingDp))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        // Phase 18: real elevation instead of the old border-only depth cue
        // (see bg_card_elevated.xml). GradientDrawable derives its shadow
        // outline from the drawable's own <corners>, so this casts a
        // correctly-rounded shadow with no extra outlineProvider needed.
        elevation = context.resources.getDimension(R.dimen.elevation_card)
    }

    fun sectionHeading(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(ContextCompat.getColor(context, R.color.text_muted))
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        letterSpacing = 0.06f
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.bottomMargin = context.dp(10)
        }
    }

    fun bodyText(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(ContextCompat.getColor(context, R.color.text_muted))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.bottomMargin = context.dp(10)
        }
    }

    /** A labelled switch row: icon + label on the left, SwitchCompat on the
     *  right. SwitchCompat itself is kept (not swapped for MaterialSwitch)
     *  — it's already proven safe on API 21 in this codebase. */
    fun switchRow(context: Context, @DrawableRes iconRes: Int?, label: String, initial: Boolean): Pair<LinearLayout, SwitchCompat> {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        if (iconRes != null) {
            row.addView(ImageView(context).apply {
                setImageDrawable(icon(context, iconRes, ContextCompat.getColor(context, R.color.text_secondary)))
                layoutParams = LinearLayout.LayoutParams(context.dp(18), context.dp(18)).also { it.marginEnd = context.dp(10) }
            })
        }
        row.addView(TextView(context).apply {
            text = label; textSize = 13.5f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val switch = SwitchCompat(context).apply {
            isChecked = initial
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        row.addView(switch)
        return row to switch
    }

    /** A labelled numeric stepper row: label on its own line, then a
     *  centered [-] value [+] control beneath it. Used for small bounded
     *  integer settings (concurrency limits and similar) where a free-text
     *  EditText would invite invalid input this makes structurally
     *  impossible. [value] is the currently displayed number; [onChange]
     *  fires with the new value on either button tap — callers re-render
     *  (rebuild the row with the new value) the same way every other
     *  settings control in this app reacts to a change, rather than this
     *  widget trying to own its own state. */
    fun stepperRow(context: Context, label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit): LinearLayout {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        column.addView(TextView(context).apply {
            text = label; textSize = 13.5f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = context.dp(10)
            }
        })
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        fun ghostBtn(glyph: String, enabled: Boolean, onTap: () -> Unit) = TextView(context).apply {
            text = glyph; textSize = 16f; gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, if (enabled) R.color.text_primary else R.color.text_muted))
            background = ContextCompat.getDrawable(context, R.drawable.bg_btn_secondary)
            layoutParams = LinearLayout.LayoutParams(context.dp(36), context.dp(36))
            isClickable = enabled; isFocusable = enabled
            if (enabled) setOnClickListener { onTap() }
        }
        row.addView(ghostBtn("\u2212", value > min) { onChange(value - 1) })
        row.addView(TextView(context).apply {
            text = value.toString(); textSize = 15f; gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            layoutParams = LinearLayout.LayoutParams(context.dp(46), ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        row.addView(ghostBtn("+", value < max) { onChange(value + 1) })
        column.addView(row)
        return column
    }

    fun inputField(context: Context, value: String, inputType: Int): android.widget.EditText =
        android.widget.EditText(context).apply {
            setText(value); this.inputType = inputType
            // ── FINAL PASS 2 (§3 — input-field comfort, at the shared source): a
            // comfortable 48dp minimum height and a 15sp text size, so the
            // Rename / New Folder / Rename-device dialogs read as properly
            // sized native inputs inside the now-explicitly-wide dialog card,
            // never as a squeezed single-line sliver.
            // ── FULL FINAL PASS 2 (§7 — the comfort pass): 52dp minimum, ──
            // 16/14dp padding and a 16dp start/end inset rhythm matching the
            // card's own — the field reads as a roomy, deliberate native
            // input on every screen size, with text comfortably clear of the
            // rounded background's corners.
            minHeight = context.dp(52)
            textSize = 15f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(context, R.color.text_muted))
            background = ContextCompat.getDrawable(context, R.drawable.bg_input_field)
            setPadding(context.dp(16), context.dp(14), context.dp(16), context.dp(14))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

    /** Starts the "server online" breathing indicator: a slow, gentle
     *  alpha pulse on the status dot itself. Deliberately simple (alpha
     *  only, no scale/expanding ring) -- motion polish is one thing I
     *  can't verify the way a static render can be checked, and a plain
     *  breathe is much harder to get looking janky than an expanding-ring
     *  effect. Pure alpha animation, so it's cheap and fully hardware
     *  accelerated. Callers MUST call stopPulse() from onPause/
     *  onHiddenChanged so it doesn't keep animating once off-screen. */
    fun startPulse(dot: View): ValueAnimator {
        dot.alpha = 1f
        val animator = ValueAnimator.ofFloat(1f, 0.45f).apply {
            duration = Motion.DURATION_PULSE
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = Motion.STANDARD
            addUpdateListener { a -> dot.alpha = a.animatedValue as Float }
        }
        animator.start()
        return animator
    }

    fun stopPulse(dot: View, animator: ValueAnimator?) {
        animator?.cancel()
        dot.alpha = 1f
    }
}

/** Vertical edge-fade overlay (Phase 16 §1) — a thin gradient strip from the
 *  surface color to transparent, laid on top of a scrolling list's edges so
 *  content doesn't clip abruptly. Built as a plain View with a
 *  GradientDrawable background rather than a 9-patch/PNG, so it stays crisp
 *  at any density and can be re-tinted from code if the surface color under
 *  it ever changes.
 *
 *  GOAT-PASS 12 (§5A): [bottomCornerRadiusDp] rounds ONLY the fade's bottom
 *  corners to match the containing card's own corner radius — for the
 *  boxes whose bottom edge sits flush on a rounded card (the Incoming
 *  modal's expanded folder), the fade's opaque band otherwise drew as a
 *  square-cornered rectangle poking past the card's rounded corners. 0
 *  (the default) keeps the exact previous behavior for every other
 *  instance. */
fun edgeFadeView(context: Context, fromTop: Boolean, @ColorInt surfaceColor: Int,
                 bottomCornerRadiusDp: Float = 0f): View =
    View(context).apply {
        val colors = if (fromTop) intArrayOf(withAlpha(surfaceColor, 255), withAlpha(surfaceColor, 0))
                     else intArrayOf(withAlpha(surfaceColor, 0), withAlpha(surfaceColor, 255))
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            if (!fromTop && bottomCornerRadiusDp > 0f) {
                val r = bottomCornerRadiusDp * context.resources.displayMetrics.density
                // [tl-x, tl-y, tr-x, tr-y, br-x, br-y, bl-x, bl-y] — the
                // OPAQUE end of the bottom fade is the edge that must
                // respect the card's rounded corners.
                cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
            }
        }
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, context.dp(18)).also {
            it.gravity = if (fromTop) Gravity.TOP else Gravity.BOTTOM
        }
        isClickable = false; isFocusable = false
    }

/** Same idea, horizontal — used for the Quick Share row so it's clear
 *  there are more tiles to scroll to, matching Phase 15 §1's edge-fade
 *  spec for horizontally-scrollable rows specifically. */
fun horizontalEdgeFadeView(context: Context, fromStart: Boolean, @ColorInt surfaceColor: Int): View =
    View(context).apply {
        val colors = if (fromStart) intArrayOf(withAlpha(surfaceColor, 255), withAlpha(surfaceColor, 0))
                     else intArrayOf(withAlpha(surfaceColor, 0), withAlpha(surfaceColor, 255))
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors)
        layoutParams = FrameLayout.LayoutParams(context.dp(20), ViewGroup.LayoutParams.MATCH_PARENT).also {
            it.gravity = if (fromStart) Gravity.START else Gravity.END
        }
        isClickable = false; isFocusable = false
    }

private fun withAlpha(@ColorInt color: Int, alpha: Int): Int =
    (color and 0x00FFFFFF) or (alpha shl 24)

// ── Pass-8 follow-up: the redesigned Save/Cancel dock ──────────────────────

/**
 * The shared "unsaved changes" dock used by BOTH settings surfaces
 * (SettingsFragment's tab and WebUiSettingsActivity), rebuilt per the
 * pass-8 follow-up brief: minimal, professional, and VISUALLY DISTINCT
 * from the page behind it.
 *
 * What changed vs. the pass-8 bar it replaces:
 *  - Surface: bg_save_dock (bg_secondary, #2A2E37) instead of a
 *    bg_card_elevated tint — one full step lighter than every card on the
 *    page, with a crisp border_subtle edge, so the dock can no longer
 *    melt into the cards behind it.
 *  - Elevation: elevation_sheet (12dp) — it now floats above every card's
 *    4dp shadow, not level with them.
 *  - Save: bg_save_action's accent→accent_dark gradient — the app's
 *    strongest action treatment, unmistakably the primary control.
 *  - Discard: the quietest affordance on the dock (hairline outline,
 *    muted label) — present, honest, never competing.
 *  - One construction site for both screens, so the two can never drift
 *    apart again (the old pair were near-duplicates with three small
 *    inconsistencies between them).
 *
 * State model (setState): "idle" (dirty, actionable — caption explains
 * what saving will do), "saving" (double-tap locked, controls dimmed),
 * "saved" (brief confirmation, auto-hidden by the host screen), "error"
 * (actionable again, caption says what went wrong).
 *
 * Visibility is the host's business (see show()/hide()) — the dock only
 * animates between the two, with the same gentle settle both settings
 * screens already used.
 */
class SaveDock(context: Context) {

    enum class State { IDLE, SAVING, SAVED, ERROR }

    val view: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        elevation = context.resources.getDimension(R.dimen.elevation_sheet)
        background = ContextCompat.getDrawable(context, R.drawable.bg_save_dock)
        setPadding(context.dp(14), context.dp(12), context.dp(14), context.dp(12))
        visibility = android.view.View.GONE
        alpha = 0f
        translationY = context.dp(24).toFloat()
        clipToOutline = false
    }

    val caption: TextView = TextView(context).apply {
        textSize = 11.5f
        setTextColor(ContextCompat.getColor(context, R.color.text_muted))
        maxLines = 2
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.bottomMargin = context.dp(10)
        }
    }

    /** The quiet secondary control. Hidden entirely by hosts that have no
     *  discard concept (WebUiSettingsActivity keeps its classic "switch
     *  tabs = discard" model instead). */
    val discard: TextView = TextView(context).apply {
        text = "Discard"
        textSize = 12.5f
        setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        gravity = Gravity.CENTER
        background = ContextCompat.getDrawable(context, R.drawable.bg_save_discard)
        setPadding(context.dp(16), context.dp(11), context.dp(16), context.dp(11))
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    val action: TextView = TextView(context).apply {
        text = "Save changes"
        textSize = 14.5f
        setTextColor(android.graphics.Color.WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        background = ContextCompat.getDrawable(context, R.drawable.bg_save_action)
        setPadding(context.dp(20), context.dp(12), context.dp(20), context.dp(12))
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also {
            it.marginStart = context.dp(10)
        }
    }

    init {
        view.addView(caption)
        view.addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            addView(discard)
            addView(action)
        })
    }

    /** Applies a state to caption + action. [idleCaption] is the dock's
     *  explanation line while dirty (e.g. which settings restart the
     *  server); the non-idle states carry their own copy. */
    fun setState(state: State, idleCaption: String = "Unsaved changes") {
        when (state) {
            State.SAVING -> {
                caption.text = "Saving…"
                action.text = "Saving…"
                action.isEnabled = false
                discard.isEnabled = false
                action.alpha = 0.6f
            }
            State.SAVED -> {
                caption.text = "All changes saved."
                action.text = "Saved"
                action.isEnabled = false
                discard.isEnabled = false
                action.alpha = 0.85f
            }
            State.ERROR -> {
                caption.text = "Could not save — check the highlighted field."
                action.text = "Retry save"
                action.isEnabled = true
                discard.isEnabled = true
                action.alpha = 1f
            }
            State.IDLE -> {
                caption.text = idleCaption
                action.text = "Save changes"
                action.isEnabled = true
                discard.isEnabled = true
                action.alpha = 1f
            }
        }
    }

    val isShown: Boolean get() = view.visibility == android.view.View.VISIBLE && view.alpha > 0.5f

    /** Gentle in — cancels any in-flight hide first so the two can't fight. */
    fun show() {
        view.animate().cancel()
        view.visibility = android.view.View.VISIBLE
        view.animate().alpha(1f).translationY(0f).setDuration(180).start()
    }

    /** Gentle out; [endAction] runs once fully hidden (the standard
     *  "then set GONE" contract). A no-op when already hidden. */
    fun hide(endAction: (() -> Unit)? = null) {
        if (view.visibility == android.view.View.GONE && view.alpha == 0f) { endAction?.invoke(); return }
        view.animate().cancel()
        view.animate().alpha(0f).translationY(view.context.dp(24).toFloat()).setDuration(150)
            .withEndAction {
                if (view.alpha == 0f) view.visibility = android.view.View.GONE
                endAction?.invoke()
            }.start()
    }

    /** Snap straight to the hidden end-state, no animation — for
     *  discarding while off-screen. */
    fun hideNow() {
        view.animate().cancel()
        view.visibility = android.view.View.GONE
        view.alpha = 0f
        view.translationY = view.context.dp(24).toFloat()
    }
}

// ══ Pass-36 (#3): the shared selection-count pill and the unified ═══════════
// All/Clear/Add segmented control — one design, used verbatim by the Files
// and Music sheets' toolbars.

/** ── #3A: the compact selection counter — one quiet pill ("5 selected · ──
 *  12.4 MB") instead of the old full-width sentence line. Weight-grown in
 *  its toolbar row; single line, end-ellipsized, never visually dominant. */
fun selectionCountPill(context: Context): TextView = TextView(context).apply {
    textSize = 11.5f
    setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
    maxLines = 1
    ellipsize = android.text.TextUtils.TruncateAt.END
    background = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.RECTANGLE
        cornerRadius = context.dpf(13f)
        setColor(ContextCompat.getColor(context, R.color.bg_deep))
        setStroke(context.dp(1), 0x1FFFFFFF)
    }
    setPadding(context.dp(10), context.dp(5), context.dp(10), context.dp(5))
    isClickable = false; isFocusable = false
    layoutParams = android.view.ViewGroup.LayoutParams(
        android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
}

/** ── #3B: All / Clear / Add as ONE professionally segmented control pill. ──
 *  Not three buttons in a box: a single 34dp pill whose quiet segments (All,
 *  Clear) sit beside the emphasized affirmative (Add — accent-filled, rounded
 *  into the pill's right end like a toggle's active thumb). Every segment is
 *  its own full-height touch target; Add carries its disabled state itself
 *  (mute + non-clickable) so the pill's geometry never shifts.
 *
 *  ── GOAT-PASS 14 Phase 2 (§5): the affirmative segment's LABEL is now the ──
 *  caller's to name — "Add" when Auto Send is off (the batch is staged in the
 *  Queue for a later manual send), "Send" when it is on (the batch leaves
 *  immediately). [affirmativeLabel] sets the initial word (same typeface,
 *  size, padding and pill geometry either way — "Add" and "Send" are both
 *  short single words, so the pill's width envelope barely moves and the
 *  segments' positions are governed by the weighted/segment layout, not the
 *  label); [setAffirmativeLabel] is the live-refresh hook every sheet calls
 *  from its own selection-refresh path so the word always tracks the real
 *  setting. The button's ACTION is unchanged everywhere — this is the label
 *  only, honestly describing what the same tap does under the current
 *  setting. */
class SelectionActionPill(context: Context, affirmativeLabel: String = "Add") : LinearLayout(context) {
    val allBtn: TextView
    val clearBtn: TextView
    val addBtn: TextView

    init {
        orientation = HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val radius = context.dpf(17f)
        background = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(ContextCompat.getColor(context, R.color.bg_deep))
            setStroke(context.dp(1), 0x1FFFFFFF)
        }
        setPadding(context.dp(3), context.dp(3), context.dp(3), context.dp(3))
        layoutParams = android.view.ViewGroup.LayoutParams(
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT, context.dp(34))

        fun quietSegment(label: String): TextView = TextView(context).apply {
            text = label; textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            gravity = android.view.Gravity.CENTER
            setPadding(context.dp(13), 0, context.dp(13), 0)
            isClickable = true; isFocusable = true
            // A quiet pressed wash — feedback without changing the pill's shape.
            background = android.graphics.drawable.StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = context.dpf(14f); setColor(0x14FFFFFF)
                })
                addState(intArrayOf(), android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = context.dpf(14f); setColor(android.graphics.Color.TRANSPARENT)
                })
            }
            layoutParams = LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }

        allBtn = quietSegment("All")
        clearBtn = quietSegment("Clear")
        addBtn = TextView(context).apply {
            text = affirmativeLabel; textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(context.dp(15), 0, context.dp(15), 0)
            isClickable = true; isFocusable = true
            // The affirmative segment fills the pill's RIGHT end — square on
            // its leading edge, rounded into the container's corner on its
            // trailing one, so it reads as one control, not a stacked button.
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                // radii: [tl, tr, br, bl] pairs — round only the right end.
                cornerRadii = floatArrayOf(0f, 0f, context.dpf(14f), context.dpf(14f),
                    context.dpf(14f), context.dpf(14f), 0f, 0f)
                setColor(ContextCompat.getColor(context, R.color.accent))
            }
            layoutParams = LayoutParams(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
        }
        addView(allBtn)
        addView(clearBtn)
        addView(addBtn)
    }

    /** Add's enabled state — muted and inert with nothing selected, exactly
     *  the old standalone button's contract, without moving a pixel. */
    fun setAddEnabled(enabled: Boolean) {
        addBtn.alpha = if (enabled) 1f else 0.45f
        addBtn.isEnabled = enabled
        addBtn.isClickable = enabled
    }

    /** ── GOAT-PASS 14 Phase 2 (§5): the affirmative segment's live label ─────
     *  refresh — every sheet calls this from its selection-refresh path with
     *  the CURRENT Auto Send verb, so the pill's word can never go stale
     *  against the real setting. Text-only write: the segment's padding,
     *  typeface, tint and the pill's geometry are untouched. */
    fun setAffirmativeLabel(label: String) {
        if (addBtn.text.toString() != label) addBtn.text = label
    }
}

/** ── GOAT-PASS 14 Phase 2 (§5): the ONE shared verb for every Quick Share ────
 *  selection surface's affirmative button — the Files, Media, Music, Text and
 *  Apps sheets all ask this what their action button should SAY, and all read
 *  the SAME live preference, so five surfaces can never disagree:
 *    • Auto Send OFF → "Add"  (the tap stages the batch into the Queue for a
 *      later manual Send — the classic staging flow);
 *    • Auto Send ON  → "Send" (the tap dispatches the batch immediately to
 *      the connected devices, exactly what stageOrSendItems does in this
 *      mode).
 *  Label ONLY — every button's actual click behavior, dimensions, padding
 *  and loading/disabled states are untouched. (The Paste tile is
 *  deliberately NOT a caller: it has no selection sheet or action button,
 *  and its queue-or-send behavior keeps its own contract.) */
fun quickShareActionVerb(context: Context): String =
    if (com.lumidrop.x.settings.SettingsManager.isAutoSendEnabled(context)) "Send" else "Add"
