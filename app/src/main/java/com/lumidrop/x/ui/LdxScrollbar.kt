package com.lumidrop.x.ui

import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.lumidrop.x.R

/**
 * Pass-12 (Required Implementations 3 + 11): the app's own minimalist
 * scrollbar, shared by every scrollable list surface — the Transfers subtabs
 * (Active / Received / History), the Send tab's file-sending queue and the
 * Connected Devices list.
 *
 * WHY hand-drawn instead of the platform scrollbar: styling the native
 * scrollbar is impossible below API 29 (and inconsistent even above), while
 * this app's design calls for one quiet, rounded, theme-consistent pill on
 * every supported device (Android 5 through 16). The FolderBox component
 * already proved this exact approach for the expanded-folder tree; this is
 * the same idea generalized to RecyclerView hosts.
 *
 * Behavior contract (matching the reported requirements):
 *  - The thumb appears IMMEDIATELY when a scroll begins (drag OR fling), with
 *    no fade-in delay — only the idle fade-OUT is animated.
 *  - While the finger is down or a fling is running the thumb stays fully
 *    opaque and tracks position/extent live.
 *  - ~450ms after scrolling goes idle the thumb fades to nothing; it never
 *    occupies space or intercepts touches (width 3dp, non-clickable).
 *  - The thumb only exists while the content genuinely overflows the
 *    viewport — a list that fits never shows one.
 *
 * Usage: [attach] the host FrameLayout that already wraps the RecyclerView
 * (the same wrapper the edge-fade bands live in — the thumb is simply added
 * as one more overlay child, pinned to the END edge).
 */
object LdxScrollbar {

    /** Thumb cross-section: 3dp wide, right-inset 3dp. */
    private const val THUMB_W_DP = 3
    private const val THUMB_INSET_DP = 3
    /** Shortest the thumb may ever render — keeps a fast scroll visible. */
    private const val THUMB_MIN_H_DP = 28
    /** How long after idle before the thumb starts fading out. */
    private const val IDLE_HIDE_DELAY_MS = 450L

    /**
     * Adds the scrollbar overlay to [host] driven by [list]. Idempotent —
     * a host that already carries a thumb is re-bound to [list] instead of
     * growing a second one. Returns the thumb view (rarely needed by
     * callers; exposed for tests).
     */
    fun attach(host: FrameLayout, list: RecyclerView): View {
        val existing = findExistingThumb(host)
        val thumb = existing ?: View(host.context).apply {
            tag = TAG
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = host.context.dpf(1.5f)
                // text_secondary at ~55% — visible against every card surface
                // this app uses, quiet enough to never read as chrome.
                val base = ContextCompat.getColor(host.context, R.color.text_secondary)
                setColor((base and 0x00FFFFFF) or (0x8C shl 24))
            }
            isClickable = false
            isFocusable = false
            visibility = View.GONE
            alpha = 0f
            layoutParams = FrameLayout.LayoutParams(
                host.context.dp(THUMB_W_DP), host.context.dp(THUMB_MIN_H_DP),
                Gravity.TOP or Gravity.END).apply {
                marginEnd = host.context.dp(THUMB_INSET_DP)
            }
            host.addView(this)
        }

        val uiHandler = Handler(Looper.getMainLooper())
        val hideRunnable = Runnable {
            thumb.animate().alpha(0f).setDuration(280L).withEndAction {
                if (thumb.alpha == 0f) thumb.visibility = View.GONE
            }.start()
        }

        fun poke() {
            uiHandler.removeCallbacks(hideRunnable)
            if (thumb.visibility != View.VISIBLE) {
                thumb.visibility = View.VISIBLE
                thumb.alpha = 1f   // immediate — no fade-in delay by design
            } else {
                thumb.animate().cancel()
                thumb.alpha = 1f
            }
            uiHandler.postDelayed(hideRunnable, IDLE_HIDE_DELAY_MS)
        }

        fun reposition() {
            // Only meaningful once both views are laid out.
            if (host.height <= 0 || list.height <= 0) return
            val range = list.computeVerticalScrollRange()
            val extent = list.computeVerticalScrollExtent()
            if (range <= extent) {
                // Content fits — no scrollbar, ever.
                if (thumb.visibility == View.VISIBLE) {
                    uiHandler.removeCallbacks(hideRunnable)
                    thumb.animate().cancel()
                    thumb.visibility = View.GONE
                }
                return
            }
            val offset = list.computeVerticalScrollOffset()
            val scrollable = (range - extent).coerceAtLeast(1)
            val trackTop = host.context.dp(4)
            val trackH = (host.height - 2 * trackTop).coerceAtLeast(1)
            val minH = host.context.dp(THUMB_MIN_H_DP)
            val thumbH = (extent.toLong() * trackH / range).toInt().coerceIn(minH, trackH)
            val travel = (trackH - thumbH).coerceAtLeast(0)
            val top = trackTop + (offset.toLong() * travel / scrollable).toInt()
            val lp = thumb.layoutParams as FrameLayout.LayoutParams
            if (lp.height != thumbH || lp.topMargin != top) {
                lp.height = thumbH
                lp.topMargin = top
                thumb.layoutParams = lp
            }
        }

        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
                // Appears the INSTANT a drag or fling begins — including the
                // very first frame, before any onScrolled delta lands.
                if (newState != RecyclerView.SCROLL_STATE_IDLE) poke()
                else reposition()   // settle to the final geometry; the idle
                                    // fade is already scheduled by poke()
            }
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                poke()
                reposition()
            }
        })
        // Adapter growth / tab switches / rotation keep the thumb honest
        // without any interaction.
        host.viewTreeObserver.addOnGlobalLayoutListener { reposition() }
        return thumb
    }

    private fun findExistingThumb(host: FrameLayout): View? {
        for (i in 0 until host.childCount) {
            val c = host.getChildAt(i)
            if (c.tag == TAG) return c
        }
        return null
    }

    private const val TAG = "ldx_scrollbar"
}
