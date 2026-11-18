package com.lumidrop.x.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.lumidrop.x.R
import com.lumidrop.x.settings.SettingsManager

/**
 * ══ Pass-27 (#4.2 / #4.3): the ONE owner of the app's system-bar window chrome ══
 *
 * History of this problem in one paragraph: the "Hide Navigation Bar" setting used
 * to be applied ONLY to MainActivity's window (its syncSystemBars arbiter). Every
 * OTHER window the app owns — the BottomSheets, LdxDialogs, the Media Picker, the
 * folder/QR/text dialogs, and the two sub-settings Activities — carried a separate
 * window configuration that the main window's flags never touch, so the Android
 * navigation buttons reappeared the moment any of them took over the screen, and
 * any window that also missed the bar-COLOR override showed the platform default
 * opaque BLACK bar instead. Pass-26 (#9) fixed the color half of that (bar colors
 * themed + applied per dialog); this object completes the mechanism:
 *
 *   • [applyToDialog] / [applyToActivity] give ANY app-owned window the app's own
 *     bg_deep bar colors (never black — #4.3) AND, while the "Hide Navigation
 *     Bar" preference is enabled, hide the navigation bar on THAT window too
 *     (#4.2) — including windows opened later, because every dialog/sheet
 *     construction site already funnels through here.
 *   • [hideNavigationBar] / [showNavigationBar] are the per-window primitives the
 *     MainActivity arbiter (which additionally owns blackout and the immersive
 *     setting itself) and the sub-settings Activities share, so no two call
 *     sites can drift into different recipes across API levels.
 *   • [setAppearanceLightBars] keeps the system's bar ICONS light (white) on the
 *     app's dark surfaces, so hiding/restoring bars never leaves unreadable
 *     dark-on-dark buttons.
 *
 * API split, identical in spirit to MainActivity's own (kept) arbiter:
 *   • API 30+  — WindowInsetsController.hide(navigationBars()) with
 *     BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE (the documented replacement for the
 *     IMMERSIVE_STICKY recipe).
 *   • API 21-29 — the classic flag set on that window's decor (IMMERSIVE_STICKY |
 *     HIDE_NAVIGATION | LAYOUT_HIDE_NAVIGATION | LAYOUT_STABLE), deliberately
 *     WITHOUT any FULLSCREEN flags: the status bar is never touched by this
 *     setting (Pass-12 contract).
 */
object SystemBarChrome {

    /** The app's seamless bar color — the exact color the window behind the
     *  bars already paints, so the bars read as edges of the app surface. */
    fun barColor(context: Context): Int = ContextCompat.getColor(context, R.color.bg_deep)

    /** Applies the app's bar colors to [window]. Windowed try/catch — a system-bar
     *  hiccup must never cost anyone a dialog or a screen. */
    fun applyColors(window: Window?) {
        try {
            val bg = barColor(window?.context ?: return)
            window.statusBarColor = bg
            window.navigationBarColor = bg
        } catch (e: Exception) { /* never let a system-bar hiccup break a window */ }
    }

    /** Keeps the system bar ICONS light (white) on the app's dark surfaces.
     *  API 30+ only; a no-op elsewhere (older devices already infer icon color
     *  from the dark navigationBarColor).
     *  (Pass-28 compile fix: the per-type isAppearanceLight* properties exist
     *  on androidx's WindowInsetsControllerCompat, NOT on the raw framework
     *  android.view.WindowInsetsController — that interface only carries the
     *  aggregate setSystemBarsAppearance(appearance, mask) pair — so the bare
     *  window.insetsController property syntax Pass-27 shipped never resolved
     *  and the project could not compile. WindowCompat.getInsetsController
     *  wraps the same controller with the friendly API.) */
    fun setAppearanceLightBars(window: Window?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val w = window ?: return
                WindowCompat.getInsetsController(w, w.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            } catch (e: Exception) { /* cosmetic only */ }
        }
    }

    /** Hides the navigation bar on [window] (API-appropriate recipe). Does not
     *  touch the status bar — that is a different feature with a different
     *  contract (blackout hides both; see MainActivity.applyBlackoutSystemBars). */
    fun hideNavigationBar(window: Window?) {
        val w = window ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                w.insetsController?.let {
                    it.systemBarsBehavior =
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    it.hide(WindowInsets.Type.navigationBars())
                }
            } else {
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
            }
        } catch (e: Exception) { /* never let a system-bar hiccup break a window */ }
        setAppearanceLightBars(w)
    }

    /** Shows the navigation bar on [window] (API-appropriate recipe). */
    fun showNavigationBar(window: Window?) {
        val w = window ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                w.insetsController?.show(WindowInsets.Type.navigationBars())
            } else {
                @Suppress("DEPRECATION")
                w.decorView.systemUiVisibility = 0
            }
        } catch (e: Exception) { /* never let a system-bar hiccup break a window */ }
    }

    /** Full chrome for a DIALOG window: bar colors (never black, #4.3) plus, when
     *  the "Hide Navigation Bar" preference is enabled, the hidden nav bar on
     *  THIS window too (#4.2). Called from every dialog construction site via
     *  [applyLdxWindowChrome]. */
    fun applyToDialog(dialog: Dialog) {
        val w = dialog.window ?: return
        applyColors(w)
        try {
            if (SettingsManager.isFullScreen(dialog.context)) hideNavigationBar(w)
        } catch (e: Exception) { /* preference read must never break a dialog */ }
    }

    /** Full chrome for a standalone ACTIVITY window (the sub-settings screens —
     *  Web Client Settings, Application Logs): bar colors plus the hidden nav
     *  bar while the preference is enabled, so the setting holds across every
     *  application-owned screen (#4.2). MainActivity does NOT use this — its own
     *  syncSystemBars arbiter additionally owns blackout; the primitives above
     *  are shared so the recipes cannot drift. */
    fun applyToActivity(activity: Activity) {
        applyColors(activity.window)
        try {
            if (SettingsManager.isFullScreen(activity)) hideNavigationBar(activity.window)
            else showNavigationBar(activity.window)
        } catch (e: Exception) { /* preference read must never break a screen */ }
    }
}

/** Dialog-window chrome (colors always; immersive nav-hiding while the
 *  "Hide Navigation Bar" preference is on). The Pass-26 successor of
 *  Widgets.applyLdxSystemBarColors — same call sites, complete behavior. */
fun Dialog.applyLdxWindowChrome() = SystemBarChrome.applyToDialog(this)

/** Activity-window chrome for the standalone sub-settings screens. */
fun Activity.applyLdxWindowChrome() = SystemBarChrome.applyToActivity(this)

/**
 * ── Pass-27 (#4.1): keeps a tab root's bottom PADDING tracking the live ──────
 * navigation-bar inset.
 *
 * MainActivity's window is edge-to-edge on API 30+ (see its onCreate), so the
 * window frame no longer ends above the Android buttons — each tab reserves
 * their zone itself, by adding the live navigationBars inset to its content's
 * existing bottom clearance. The two states of the "Hide Navigation Bar"
 * preference then fall out of the geometry automatically: buttons visible →
 * inset = real bar height → the clearance grows by exactly the space the window
 * frame used to reserve (layout byte-identical to before); buttons hidden →
 * inset = 0 → every pixel the buttons occupied returns to the content.
 *
 * A no-op on API < 30, where the legacy window frame already reserves (or,
 * with the preference on and the LAYOUT_HIDE_NAVIGATION recipe, releases) the
 * bar's zone by itself. The listener returns the insets untouched so sibling
 * views and descendants keep their own dispatch.
 */
fun View.trackNavBarBottomPadding(basePaddingPx: Int) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        val target = basePaddingPx + nav.coerceAtLeast(0)
        if (v.paddingBottom != target) v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, target)
        insets
    }
}
