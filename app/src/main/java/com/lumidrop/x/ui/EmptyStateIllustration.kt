package com.lumidrop.x.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.content.ContextCompat
import com.lumidrop.x.R

/**
 * Empty-state illustration from the Phase 18 design prototype
 * (EmptyStateIllustration in the prototype's Components.kt): a slowly
 * rotating dashed ring with a small icon pair/triple centered inside it.
 * Used by the Connected Devices card (isTransfer = false) and reusable
 * as-is for the Transfers screen's empty state (isTransfer = true).
 *
 * Built as a composed FrameLayout (a small custom Canvas ring + real
 * ImageViews for the icons) rather than hand-drawing the phone/monitor
 * glyphs on the canvas too -- reusing Widgets.icon()/the app's existing
 * vector icons for the icon layer is far more robust than approximating
 * their paths a second time freehand.
 *
 * The prototype's dash pattern (floatArrayOf(30f, 30f)) is unconverted
 * raw pixels in Compose, which would render at inconsistent physical
 * sizes across densities -- treated as 30dp here instead, consistent
 * with the ring's own dp-based stroke width.
 */
class EmptyStateIllustration @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    var isTransfer: Boolean = false
        set(value) { field = value; rebuild() }

    /** Ring + icon sizes all scale off this one number (proportionally,
     *  relative to the prototype's 120dp reference), so a card with less
     *  room to give -- Send's Connected Devices card, sized tighter than
     *  the prototype's own scrollable layout allowed -- can ask for a
     *  smaller illustration without a second, hand-tuned copy of the
     *  icon-sizing logic living elsewhere. */
    var sizeDp: Int = 120
        set(value) { field = value; rebuild() }

    private val ring = DashedRingView(context)
    private val iconRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    init {
        addView(ring)
        addView(iconRow, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).also { it.gravity = Gravity.CENTER })
        rebuild()
    }

    private fun rebuild() {
        val px = context.dp(sizeDp)
        layoutParams = (layoutParams ?: LayoutParams(px, px)).also { it.width = px; it.height = px }
        ring.layoutParams = LayoutParams(px, px).also { it.gravity = Gravity.CENTER }
        rebuildIcons()
        requestLayout()
    }

    private fun rebuildIcons() {
        iconRow.removeAllViews()
        val tint = ContextCompat.getColor(context, R.color.text_secondary)
        val scale = sizeDp / 120f
        fun scaledIcon(res: Int, baseSizeDp: Int, marginEndDp: Int = 0) = ImageView(context).apply {
            setImageDrawable(Widgets.icon(context, res, tint))
            scaleType = ImageView.ScaleType.FIT_CENTER
            val sizePx = context.dp((baseSizeDp * scale).toInt().coerceAtLeast(1))
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx).also {
                it.marginEnd = context.dp((marginEndDp * scale).toInt())
            }
        }
        // ── Pass-16 (combined icon restored): the circular combined icon ─────
        // went back to the COMPUTER glyph it always used — per direct
        // feedback the globe is now reserved for the Connected Devices
        // CARD's actual browser rows (DeviceRow.Browser, where it reads as
        // "this connection is a web client"); the generic phone + computer
        // pair inside this decorative ring says "devices connect to a
        // computer" without implying any specific connection kind.
        if (isTransfer) {
            iconRow.addView(scaledIcon(R.drawable.ic_phone, 20))
            iconRow.addView(scaledIcon(R.drawable.ic_arrow_forward, 16))
            iconRow.addView(scaledIcon(R.drawable.ic_monitor, 26))
        } else {
            iconRow.addView(scaledIcon(R.drawable.ic_phone, 22, marginEndDp = 4))
            iconRow.addView(scaledIcon(R.drawable.ic_monitor, 30))
        }
    }
}

/**
 * The animated dashed ring on its own -- a plain View so its onDraw/
 * attach lifecycle stays simple, no reasoning about compositing with the
 * icon layer on top of it.
 *
 * ── Pass-15 (Android 5 dash-animation root fix) ─────────────────────────────
 * The reported defect: on Android 5 the ring's dashes "move far too
 * quickly, move back and forth, and look broken" while every newer phone
 * ran it smoothly. The old implementation drove the dash phase with an
 * infinite ValueAnimator whose per-frame listener allocated a BRAND-NEW
 * DashPathEffect and invalidated the view — the only Canvas way to
 * animate a phase, but on API 21-22's still-young animation pipeline the
 * resulting per-frame allocation churn + invalidate storm produced
 * exactly the hot, erratic, direction-flipping march that was reported
 * (a mis-paced AnimationHandler on those builds can deliver bursts of
 * updates far faster than vsync, which reads as "too quickly" and
 * "back and forth").
 *
 * The new implementation removes the animator ENTIRELY:
 *   • the phase is derived from SystemClock.uptimeMillis() inside onDraw,
 *     so the visual speed is wall-clock deterministic and IDENTICAL on
 *     every device and API level — no animator pacing to glitch;
 *   • the phase is quantized to 64 steps per cycle and every step's
 *     DashPathEffect is preallocated once, so the steady state performs
 *     ZERO allocations per frame (nothing for a GC-starved old device
 *     to jank on);
 *   • the loop is driven by postInvalidateOnAnimation() (vsync-aligned
 *     by construction, API 16+), which is exactly how the platform's own
 *     indeterminate ProgressBar animates on old devices.
 * When the view is hidden or detached the onDraw loop simply stops;
 * there is nothing left running to leak or to cancel.
 */
private class DashedRingView(context: Context) : View(context) {

    private val dashLenPx = context.dpf(30f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.dpf(2f)
        color = ContextCompat.getColor(context, R.color.border_subtle)
    }

    // Pass-15: one full dash cycle every 2000ms (the prototype's pace),
    // rendered as 64 preallocated phase steps — 31.25ms of visual motion
    // per step, indistinguishable from the continuous original at a
    // 2000ms cycle while costing exactly one array read per frame.
    private val cycleMs = 2000L
    private val phaseSteps = 64
    private val phases = Array(phaseSteps) { i ->
        DashPathEffect(floatArrayOf(dashLenPx, dashLenPx), dashLenPx * 2f * i / phaseSteps)
    }
    private var phaseIdx = -1

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = (minOf(width, height) / 2f) - paint.strokeWidth
        if (radius > 0f) {
            val idx = ((SystemClock.uptimeMillis() % cycleMs) * phaseSteps / cycleMs).toInt()
            if (idx != phaseIdx) {
                phaseIdx = idx
                paint.pathEffect = phases[idx]
            }
            canvas.drawCircle(width / 2f, height / 2f, radius, paint)
        }
        // The single vsync-aligned self-repaint that keeps the march
        // moving; no animator, no per-frame allocation, stops by itself
        // the moment this view stops drawing.
        postInvalidateOnAnimation()
    }
}
