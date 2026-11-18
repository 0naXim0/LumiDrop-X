package com.lumidrop.x.ui

import android.content.Context
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.Interpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.PathInterpolator
import androidx.fragment.app.Fragment

/**
 * Central motion/shape constants for the Phase 15-17 design system.
 *
 * Everything here is deliberately built from plain framework/AndroidX-core
 * primitives (ValueAnimator, the interpolators below, GradientDrawable) —
 * NOT com.google.android.material.* — because this app's theme is
 * Theme.AppCompat.NoActionBar on purpose (see themes.xml): several Material
 * Components widgets assert `Theme.MaterialComponents` at runtime and throw
 * on inflation if the app theme doesn't extend it, which is exactly what
 * caused real crashes on API 21 hardware here before. Re-introducing MDC
 * anywhere in this app would reopen that crash class, so every "modern"
 * widget in this redesign (bottom nav, sheets, switches' track/thumb
 * skinning) is a small custom View/Drawable instead.
 */
object Motion {
    const val DURATION_INSTANT = 100L
    const val DURATION_SHORT   = 150L
    const val DURATION_MEDIUM  = 220L
    const val DURATION_LONG    = 320L
    const val DURATION_PULSE   = 1400L   // one breathe cycle of the server-status glow

    // PathInterpolator has existed since API 21, so it's safe on this app's
    // full min-SDK range. Cubic-bezier approximation of a standard
    // "emphasized decelerate" curve — quick start, gentle settle.
    val STANDARD: Interpolator = PathInterpolator(0.2f, 0f, 0f, 1f)
    val DECELERATE: Interpolator = DecelerateInterpolator(1.4f)
    val OVERSHOOT_SOFT: Interpolator = OvershootInterpolator(1.2f)
}

/** dp → px, callable from any Context without redefining the same one-liner
 *  in every Activity/Fragment (MainActivity, TransfersActivity and
 *  SettingsActivity each used to carry an identical private copy). */
fun Context.dp(n: Int): Int = (n * resources.displayMetrics.density).toInt()
fun Context.dpf(n: Float): Float = n * resources.displayMetrics.density

fun View.dp(n: Int): Int = context.dp(n)

fun Fragment.dp(n: Int): Int = requireContext().dp(n)
fun Fragment.dpf(n: Float): Float = requireContext().dpf(n)
