package com.lumidrop.x.ui

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lumidrop.x.R

/**
 * Custom bottom navigation bar for the Phase 17 "Consolidated Bottom
 * Navigation Architecture", re-skinned in Phase 18 to match the AI-Studio
 * design prototype: a floating pill-shaped bar with a rounded highlight
 * fading in behind the selected item's icon, instead of the earlier
 * sliding underline.
 *
 * ── Pass-25 (the complete navigation-bar redesign) ─────────────────────
 * The visual system is unified rather than re-changed: the selected-item
 * highlight is now a full CAPSULE (bg_nav_indicator → radius_pill) so the
 * bar's own floating-capsule container and its selection highlight speak
 * the same shape language; the three tab icons are now one stroke-drawn
 * set (ic_send redrawn as the classic stroke paper plane to match
 * ic_swap / ic_settings' identical 1.65dp stroke, inset grid and optical
 * weight — the old ic_send was the one filled glyph and read visibly
 * heavier at 24dp); and the labels gain a hint of letter tracking for a
 * cleaner type rhythm at 11sp. Proportions, touch targets, weights,
 * animation timing and every behavior are deliberately UNCHANGED — the
 * 52dp bar, 64×32 pill geometry, equal-thirds cells, crossfade + icon
 * bounce and haptic tick are the audited, working design. Deliberately NOT
 * com.google.android.material.bottomnavigation.BottomNavigationView:
 * MDC widgets require the app theme to extend Theme.MaterialComponents,
 * and this app uses Theme.AppCompat.NoActionBar on purpose after that
 * exact combination caused InflateException crashes on real API 21
 * hardware (see themes.xml). Everything here is plain View/Drawable/
 * ValueAnimator, safe on the app's full API 21+ range.
 */
class BottomNavBar @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : FrameLayout(context, attrs, defStyle) {

    data class NavItem(val iconRes: Int, val label: String)

    private val itemsRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    }

    /** icon+badge sit inside `pill`, which IS the selection highlight
     *  (bg_nav_indicator) -- faded in/out per item rather than a single
     *  shared element sliding between items like the old underline was. */
    private data class ItemViews(
        val root: LinearLayout, val pill: FrameLayout, val icon: ImageView,
        val label: TextView, val badge: TextView
    )
    private val itemViews = mutableListOf<ItemViews>()
    private var selectedIndex = 0
    private val accentColor = ContextCompat.getColor(context, R.color.accent)
    private val unselectedColor = ContextCompat.getColor(context, R.color.text_secondary)
    private val iconSelectedColor = ContextCompat.getColor(context, R.color.nav_icon_selected)
    private var onSelectedListener: ((Int) -> Unit)? = null

    init {
        background = ContextCompat.getDrawable(context, R.drawable.bg_nav_bar)
        elevation = resources.getDimension(R.dimen.elevation_nav)  // dimen resources already resolve to px
        addView(itemsRow)
    }

    fun setItems(items: List<NavItem>, initialSelection: Int = 0, onSelected: (Int) -> Unit) {
        onSelectedListener = onSelected
        itemsRow.removeAllViews()
        itemViews.clear()

        items.forEachIndexed { index, item ->
            val icon = ImageView(context).apply {
                setImageDrawable(Widgets.icon(context, item.iconRes, unselectedColor))
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                layoutParams = FrameLayout.LayoutParams(context.dp(24), context.dp(24)).also {
                    it.gravity = Gravity.CENTER
                }
            }
            val badge = TextView(context).apply {
                textSize = 9f
                setTextColor(ContextCompat.getColor(context, R.color.white))
                background = ContextCompat.getDrawable(context, R.drawable.bg_badge)
                gravity = Gravity.CENTER
                minWidth = context.dp(15); minimumHeight = context.dp(15)
                setPadding(context.dp(3), 0, context.dp(3), 0)
                visibility = android.view.View.GONE
            }
            // GOAT (Phase 3, item 9 -- cropped/misaligned badge dot): two
            // compounding issues. First, pill below is a fixed 64x32dp box
            // with clipChildren left at its ViewGroup default of true, and
            // the badge's negative margins (below) deliberately push it
            // PAST that box's own top/end edges to sit over the icon's
            // corner -- exactly the part a default-clipping parent cuts
            // off, which is what "cropped" was describing. Second, those
            // margins anchor to pill's own 64dp-wide corner, not the 24dp
            // icon's -- since icon sits centered with ~20dp of pill space
            // to its right, the badge was floating well clear of the icon
            // it's meant to mark, which is what "misaligned" was
            // describing. iconWrap, sized to the icon itself and given its
            // own clipChildren=false, fixes both: the badge now anchors to
            // (and can render outside of) the icon's own tight bounds.
            val iconWrap = FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(context.dp(24), context.dp(24)).also { it.gravity = Gravity.CENTER }
                clipChildren = false
                addView(icon)
                addView(badge, FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also {
                    it.gravity = Gravity.TOP or Gravity.END
                    it.topMargin = -context.dp(4); it.marginEnd = -context.dp(4)
                })
            }
            // The pill IS the highlight, not a separate element sliding
            // under one -- each item gets its own copy, faded 0<->1.
            val pill = FrameLayout(context).apply {
                // Alpha is set on the DRAWABLE, not the View: View.alpha
                // fades the whole subtree, including icon/badge nested
                // inside, which is exactly the bug this replaced -- an
                // unselected item's pill.alpha=0f made its icon invisible
                // too (only "appearing" once selected, i.e. alpha->1),
                // since icon/badge are children of this FrameLayout, not
                // siblings. Mutate first, same reasoning as Widgets.icon():
                // un-mutated drawables share constant state, so animating
                // one pill's alpha would otherwise animate every pill.
                background = ContextCompat.getDrawable(context, R.drawable.bg_nav_indicator)?.mutate()?.also { it.alpha = 0 }
                layoutParams = LinearLayout.LayoutParams(
                    context.dp(64), context.dp(32)
                ).also { it.gravity = Gravity.CENTER_HORIZONTAL }
                // Also left un-clipped -- iconWrap's own badge overflow sits
                // well within pill's remaining slack either way (64x32 vs a
                // centered 24x24 child), but this way correctness doesn't
                // quietly depend on that margin always staying small.
                clipChildren = false
                addView(iconWrap)
            }
            val label = TextView(context).apply {
                text = item.label
                // ── Pass-21 (the tab label, rebuilt for perfect balance) ────────
                // The old label sized itself by raw font metrics: ascent +
                // descent padding made the row's visual BOTTOM whitespace
                // bigger than its top, and the 1dp margin stacked on the
                // pill's own 4dp icon slack read as an uneven icon→label
                // gap. The redesign pins every dimension: font padding OFF,
                // a FIXED 14dp box with the text centered inside it, maxLines
                // 1 — so the item's content stack is exactly pill(32) +
                // gap(1) + label(14) = 47dp, leaving a symmetric 2.5dp above
                // and below inside the 52dp bar (itemRoot centers it). The
                // top and bottom padding are now provably identical on
                // every density and every font scale.
                // ── Pass-25 (#3): +0.04em tracking — a hint of air between ──
                // the letters of "Transfers" at 11sp, the same typographic
                // polish the app's other small caps-ish labels use; the
                // 14dp box already has room, so nothing about the stack
                // geometry changes.
                textSize = 11f
                letterSpacing = 0.04f
                maxLines = 1
                includeFontPadding = false
                setTextColor(unselectedColor)
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, context.dp(14)).also {
                    it.topMargin = context.dp(1)
                }
            }
            val itemRoot = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true; isFocusable = true
                background = ContextCompat.getDrawable(context, R.drawable.bg_nav_item_ripple)
                // ── Pass-21 (deterministic vertical balance) ────────────────────
                // The content stack (pill 32 + gap 1 + label 14 = 47dp) is
                // centered as ONE unit inside the full-weight item cell, so
                // the bar's remaining 5dp splits into an identical 2.5dp at
                // the top and the bottom — the balanced, professional spacing
                // this round's feedback asked for, with the pill's internal
                // 4dp-per-side icon slack as the only (symmetric) slack left.
                // Full weighted-thirds touch target preserved.
                layoutParams = LinearLayout.LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                addView(pill)
                addView(label)
                setOnClickListener {
                    if (index != selectedIndex) {
                        it.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK)
                    }
                    select(index, animate = true, notify = true)
                }
            }
            itemsRow.addView(itemRoot)
            itemViews.add(ItemViews(itemRoot, pill, icon, label, badge))
        }

        selectedIndex = initialSelection.coerceIn(0, items.size - 1)
        // No layout-dependent positioning anymore (alpha doesn't need a
        // measured pass the way the old sliding indicator's x/y did), so
        // this can apply immediately instead of waiting on post{}.
        select(selectedIndex, animate = false, notify = false)
    }

    /** Switch the highlighted tab. `notify=false` is used for programmatic /
     *  initial selection so it doesn't re-trigger the fragment-switch
     *  callback that's already the reason the selection is changing. */
    fun select(index: Int, animate: Boolean = true, notify: Boolean = true) {
        if (index !in itemViews.indices) return
        val previous = selectedIndex
        selectedIndex = index

        itemViews.forEachIndexed { i, v ->
            val selected = i == index
            val wasSelected = i == previous

            // Label crossfades muted <-> accent; icon crossfades muted <->
            // nav_icon_selected -- a distinct, slightly lighter tint than
            // the label's accent color, per the prototype.
            val fromLabel = if (wasSelected) accentColor else unselectedColor
            val toLabel = if (selected) accentColor else unselectedColor
            val fromIcon = if (wasSelected) iconSelectedColor else unselectedColor
            val toIcon = if (selected) iconSelectedColor else unselectedColor

            if (animate && (fromLabel != toLabel || fromIcon != toIcon)) {
                ValueAnimator.ofObject(ArgbEvaluator(), fromLabel, toLabel).apply {
                    duration = Motion.DURATION_SHORT
                    addUpdateListener { a -> v.label.setTextColor(a.animatedValue as Int) }
                }.start()
                ValueAnimator.ofObject(ArgbEvaluator(), fromIcon, toIcon).apply {
                    duration = Motion.DURATION_SHORT
                    addUpdateListener { a -> v.icon.setColorFilter(a.animatedValue as Int) }
                }.start()
            } else {
                v.label.setTextColor(toLabel)
                v.icon.setColorFilter(toIcon)
            }
            // Weight change reads instantly (unlike color, there's no
            // meaningful "halfway" typeface to animate through), so it's
            // set directly rather than tied to the crossfade duration --
            // selection already reads clearly from the pill+color+bounce,
            // this is a small reinforcing detail, not the primary cue.
            v.label.typeface = android.graphics.Typeface.create(
                "sans-serif" + if (selected) "-medium" else "", android.graphics.Typeface.NORMAL)

            // Pill fades in/out behind the icon instead of a shared
            // indicator sliding between items. Drawable alpha (0-255), not
            // View alpha -- see the constructor comment above for why.
            val fromPillAlpha = if (wasSelected) 255 else 0
            val toPillAlpha = if (selected) 255 else 0
            if (animate && fromPillAlpha != toPillAlpha) {
                ValueAnimator.ofInt(fromPillAlpha, toPillAlpha).apply {
                    duration = Motion.DURATION_SHORT
                    interpolator = Motion.STANDARD
                    addUpdateListener { a -> v.pill.background?.alpha = a.animatedValue as Int }
                }.start()
            } else {
                v.pill.background?.alpha = toPillAlpha
            }

            if (selected && animate && i != previous) {
                v.icon.animate().cancel()
                v.icon.scaleX = 0.85f; v.icon.scaleY = 0.85f
                v.icon.animate().scaleX(1f).scaleY(1f).setDuration(Motion.DURATION_MEDIUM)
                    .setInterpolator(Motion.OVERSHOOT_SOFT).start()
            }
        }

        if (notify) onSelectedListener?.invoke(index)
    }

    fun setBadge(index: Int, count: Int) {
        val v = itemViews.getOrNull(index) ?: return
        if (count <= 0) {
            v.badge.visibility = android.view.View.GONE
        } else {
            v.badge.visibility = android.view.View.VISIBLE
            v.badge.text = if (count > 99) "99+" else count.toString()
        }
    }
}
