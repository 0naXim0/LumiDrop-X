package com.lumidrop.x

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.core.content.ContextCompat
import com.lumidrop.x.databinding.ActivityMainBinding
import com.lumidrop.x.service.ServerForegroundService
import com.lumidrop.x.settings.SettingsFragment
import com.lumidrop.x.transfer.TransferManager
import com.lumidrop.x.ui.BottomNavBar
import com.lumidrop.x.ui.LdxButtonStyle
import com.lumidrop.x.ui.LdxDialogAction
import com.lumidrop.x.ui.ReceivingModal
import com.lumidrop.x.ui.SystemBarChrome
import com.lumidrop.x.ui.Widgets
import com.lumidrop.x.ui.dp
import com.lumidrop.x.ui.showLdxDialog

/**
 * Application shell (Phase 17 par 4: Consolidated Bottom Navigation
 * Architecture).
 *
 * Previously MainActivity WAS the Send screen, with Settings and Transfers
 * as separate Activities reached via startActivity() -- a full window
 * transition (and, for Transfers, its own poll-loop restart) on every tab
 * switch. Now this class only hosts three fragments (SendFragment /
 * TransfersFragment / SettingsFragment) inside fragmentContainer, added
 * ONCE and switched via FragmentManager show()/hide() -- never replace() --
 * so scroll position, expanded device/peer lists, and staged-but-unsent
 * queue items in SendFragment all survive a tab switch instead of being
 * torn down and rebuilt.
 *
 * Three things that used to live in the Send screen's own layout moved
 * here instead, because all three are meant to float above EVERY tab, not
 * just Send:
 *  - blackoutOverlay -- blackout should black out the bottom nav too.
 *  - the floating transfer popup -- shown over Send/Settings, hidden only
 *    on the Transfers tab itself (which already shows full detail).
 *  - (the old FAB was NOT carried over -- the bottom nav's own Transfers
 *    tab, with its own active-count badge, already gives one-tap access;
 *    keeping both would have been the same destination reachable two
 *    redundant ways.)
 *
 * The floating popup needs its own independent poll loop here (rather than
 * reusing SendFragment's) since it must keep working while, say, Settings
 * is the visible tab and SendFragment is hidden.
 *
 * GOAT v2 (item 8 — Universal Receiving Modal): the incoming-transfer
 * accept/decline sheet and its minimized floating badge are NOT part of
 * this Activity anymore — they live in ui/ReceivingModal.kt, a component
 * every screen in the app hosts (this Activity,
 * WebUiSettingsActivity), so a request shows up wherever the person is,
 * not only on the tab or screen that happened to own it. See that class's
 * own doc comment for the full history (SendFragment -> here -> its own
 * component) and why each step was needed. All this Activity does is
 * create one, and start()/stop() it from onResume/onPause below.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sendFragment: SendFragment
    private lateinit var transfersFragment: TransfersFragment
    private lateinit var settingsFragment: SettingsFragment
    private var currentTabIndex = 0
    private var blackoutActive = false
    private var floatBarExpanded = false

    // GOAT v2 (item 8): see this class's doc comment. Badge parent is the
    // root FrameLayout so blackoutOverlay (a later sibling with a higher
    // elevation) still covers it, same as every other floating element.
    private lateinit var receiving: ReceivingModal

    private val handler = Handler(Looper.getMainLooper())
    private val floatingPopupTick = object : Runnable {
        override fun run() {
            updateFloatingPopupAndBadge()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // ── GOAT-PASS 3 (§1): the one-shot, delayed, all-background sheet ──────
        // prewarm — SharedPreferences, StorageManager, the Files sheet's
        // root-listing cache and one pass each of the MediaStore queries, so
        // the FIRST opening of any sheet after a fresh launch finds its data
        // sources warm instead of paying their cold-start cost mid-animation.
        // See AppPrewarm's doc for the full reasoning; strictly best-effort.
        com.lumidrop.x.AppPrewarm.start(this)

        try {
            window.statusBarColor = ContextCompat.getColor(this, R.color.bg_deep)
            window.navigationBarColor = ContextCompat.getColor(this, R.color.bg_deep)
        } catch (e: Exception) { /* older API */ }

        // ── Pass-27 (#4.1): the main window is laid out EDGE-TO-EDGE on ─────────
        // API 30+. The whole point: the layout no longer depends on WHETHER (or
        // WHEN) the system resizes the window when the "Hide Navigation Bar"
        // preference hides the Android buttons — that behavior differs across
        // Android versions and OEMs, and a window that keeps its frame leaves
        // the freed space as a dead band (the reported "card keeps its old
        // height, big gap above the bottom tabs"). Edge-to-edge makes the
        // geometry DETERMINISTIC instead: the window always fills the display,
        // the navigation-bar inset is 0 exactly while the bar is hidden and its
        // real height exactly while it is shown, and the ONE insets listener
        // below (plus each fragment's own) derives every bottom clearance from
        // that number. Hiding the buttons now ALWAYS hands the space back to
        // the layout — the "looks like Gesture Navigation mode" requirement —
        // and showing them always re-reserves it, with no stale dimensions and
        // no restart needed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.setDecorFitsSystemWindows(false)
                // White bar icons on the app's dark surfaces, on every
                // bar-visibility state this shell drives. (Pass-28 compile fix:
                // the per-type isAppearanceLight* properties live on androidx's
                // WindowInsetsControllerCompat — the RAW framework
                // android.view.WindowInsetsController exposes only the aggregate
                // setSystemBarsAppearance(appearance, mask) pair, so the bare
                // window.insetsController property syntax Pass-27 shipped never
                // resolved and the project could not compile.)
                androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            } catch (e: Exception) { /* never let a system-bar hiccup crash the shell */ }
        }

        setupFragments(savedInstanceState)
        setupBottomNav()
        setupBlackout()
        setupFloatingPopup()
        // ── Pass-38 (#6): the persistent music pill. App launch AND activity
        // recreation both land here. A previous session that was left paused
        // (and never explicitly dismissed) restores as the compact pill —
        // correct track, remembered position, PAUSED until the user plays;
        // a live session on a recreated window just rebinds its visual.
        // maybeRestore itself owns every guard (live session, deleted
        // source, corrupted store) — it can never double-restore or
        // auto-play.
        com.lumidrop.x.ui.MusicPlayback.maybeRestore(this)
        // Pass-32 (#5): seed the ramp at inset 0 — the insets listener below
        // rebuilds it with the real navigation inset on the first dispatch.
        applyNavUnderGradient(0)
        receiving = ReceivingModal(this) { binding.rootFrame }
        // ── Pass-15 (ghost-item ask-to-resend): the sender-side UI half of the ──
        // receiver's discard receipt. When the receiving device deletes the
        // partial file while our retry loop is still alive, the send loop
        // suspends and asks THIS question instead of silently re-sending the
        // whole file from 0%: "Send again from the beginning, or cancel?".
        // The queue below shows one dialog at a time (a multi-file batch may
        // ask for several files); a refusal or a backdrop-dismiss answers
        // "cancel", and the engine-side 2-minute timeout treats silence as
        // a refusal too — a deliberately discarded file is never re-sent
        // without an explicit yes.
        com.lumidrop.x.p2p.NativePushClient.onAskResendFromScratch =
            { fileName, peerName, answer -> enqueueResendAsk(fileName, peerName, answer) }
        setupBackNavigation()
        // ── Pass-16 (Android 5 "Hide Navigation Bar" crop root fix) ─────────
        // On API 21-22 the LAYOUT_HIDE_NAVIGATION flag drags the window into
        // edge-to-edge layout at the TOP too — content then renders under the
        // still-visible status bar, which is exactly the reported "server
        // card cropped from the top" on Android 5 the moment the toggle is
        // enabled. The window-relative systemWindowInsetTop is, by
        // definition, how much the window overlaps the bar — so applying it
        // back as rootFrame's top padding pushes the content (all three
        // fragments + the nav bar's container, blackout included) below the
        // bar on every affected device, and pads ZERO everywhere the window
        // already starts below it (API 23+ and every device where the toggle
        // is off). Blackout is the one exception: it hides the status bar
        // itself and must stay truly full-screen, so its active window must
        // keep 0 top padding — otherwise a bg_deep strip would sit above a
        // supposedly pitch-black screen (the LAYOUT_STABLE insets report the
        // bar height even while hidden).
        //
        // ── Pass-27 (#4.1): the same listener now owns the BOTTOM half of the ───
        // edge-to-edge geometry on API 30+ (top half unchanged): the floating
        // bottom-nav pill's bottom margin and the under-bar gradient band's
        // height both track the REAL navigation-bar inset. The visual result
        // on every state is the design's own: the pill floats 12dp above the
        // Android buttons while they are visible, and drops flush to the
        // screen's bottom edge the moment the "Hide Navigation Bar" preference
        // hides them — the exact Gesture-Navigation look — while the gradient
        // band always reaches the true bottom edge so the fade never stops
        // short above a dead band. On API < 30 the window frame already
        // excludes (or, with the preference on, extends behind) the bar, so
        // the static 12dp/88dp values stay correct there and nothing below
        // the legacy branch runs.
        binding.rootFrame.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val top = if (blackoutActive) 0 else insets.getInsets(WindowInsets.Type.statusBars()).top
                if (v.paddingTop != top) v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
                val nav = if (blackoutActive) 0 else insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                val navLp = binding.bottomNav.layoutParams as android.widget.FrameLayout.LayoutParams
                val wantNavMargin = dp(12) + nav
                if (navLp.bottomMargin != wantNavMargin) {
                    navLp.bottomMargin = wantNavMargin
                    binding.bottomNav.layoutParams = navLp
                }
                val gradLp = binding.navUnderGradient.layoutParams as android.widget.FrameLayout.LayoutParams
                val wantGradHeight = dp(88) + nav
                if (gradLp.height != wantGradHeight) {
                    gradLp.height = wantGradHeight
                    binding.navUnderGradient.layoutParams = gradLp
                }
                // ── Pass-32 (#5): the ramp itself is inset-aware — the visible ──
                // band above the Android buttons carries the identical fade on
                // gesture and three-button navigation alike.
                applyNavUnderGradient(nav)
                // The floating transfer popup clears the tab pill by the same
                // margin it always did — its clearance must grow with the
                // system bar's zone exactly like the pill's own margin does,
                // or a 3-button device would see the two bars overlap.
                val floatLp = binding.floatingTransferBar.layoutParams as android.widget.FrameLayout.LayoutParams
                val wantFloatMargin = resources.getDimensionPixelSize(R.dimen.nav_bar_clearance) + nav
                if (floatLp.bottomMargin != wantFloatMargin) {
                    floatLp.bottomMargin = wantFloatMargin
                    binding.floatingTransferBar.layoutParams = floatLp
                }
            } else {
                @Suppress("DEPRECATION")
                val top = if (blackoutActive) 0 else insets.systemWindowInsetTop
                if (v.paddingTop != top) v.setPadding(v.paddingLeft, top, v.paddingRight, v.paddingBottom)
                // ── Pass-32 (#5): the under-bar gradient is inset-aware on the ──
                // legacy branch too. On a window that already ends above the
                // 3-button bar this inset reads 0 and nothing changes (the
                // static 88dp band sits fully above the bar, as it always
                // did); on the Android 5-style edge-to-edge layouts (the
                // LAYOUT_HIDE_NAVIGATION world Pass-16's top-padding fix
                // handles) the window genuinely overlaps the bar, and the
                // gradient now grows by exactly that overlap with its ramp
                // pinned to the visible band — instead of the old static 88dp
                // losing its opaque half behind the opaque buttons.
                @Suppress("DEPRECATION")
                val navBottom = if (blackoutActive) 0 else insets.systemWindowInsetBottom
                if (navBottom > 0) {
                    val gradLp = binding.navUnderGradient.layoutParams as android.widget.FrameLayout.LayoutParams
                    val wantGradHeight = dp(88) + navBottom
                    if (gradLp.height != wantGradHeight) {
                        gradLp.height = wantGradHeight
                        binding.navUnderGradient.layoutParams = gradLp
                    }
                }
                applyNavUnderGradient(navBottom)
            }
            insets
        }
        // Pass-11 (Required Implementation 4): apply the persisted Full Screen
        // preference from the very first frame — hiding (or restoring) the
        // system bars before anything is visible.
        syncSystemBars()
    }

    override fun onResume() {
        super.onResume()
        handler.post(floatingPopupTick)
        receiving.start()
        // Pass-11: re-assert the Full Screen preference whenever the activity
        // comes back to the front (onWindowFocusChanged covers the focus-gain
        // path too; this one is belt-and-braces for odd OEM focus lifecycles).
        syncSystemBars()
        // Pass-3 (R3): coming back from the background re-announces THIS
        // device to every native peer immediately (a short beacon burst —
        // see NativeDiscovery.onAppForeground), so both ends of a
        // native-to-native connection recover from the backgrounding
        // symmetrically and near-instantly instead of one side waiting out
        // the beacon interval (or the expiry window, if it had already
        // given up) while its Connected Devices card looks broken.
        com.lumidrop.x.p2p.NativeDiscovery.onAppForeground()
        // ── GOAT-PASS 16 (§1.2/§1.3): returning from Android's ─────────────
        // "install unknown apps" screen rechecks the REAL permission state
        // and resumes a permission-blocked archive install exactly once —
        // no re-tap, no duplicate operation, nothing at all when no install
        // was pending (the common case costs one volatile read).
        try {
            com.lumidrop.x.transfer.XapkInstallFlow.PendingPermissionResume
                .onHostResume(lifecycleScope, this)
        } catch (e: Exception) { /* never block resume over a pending install */ }
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(floatingPopupTick)
        receiving.stop()
    }

    // Fragment shell ------------------------------------------------------------------------

    private fun setupFragments(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) {
            sendFragment = SendFragment()
            transfersFragment = TransfersFragment()
            settingsFragment = SettingsFragment()
            supportFragmentManager.beginTransaction()
                .add(R.id.fragmentContainer, sendFragment, TAG_SEND)
                .add(R.id.fragmentContainer, transfersFragment, TAG_TRANSFERS).hide(transfersFragment)
                .add(R.id.fragmentContainer, settingsFragment, TAG_SETTINGS).hide(settingsFragment)
                .commit()
        } else {
            // Process death / config-change restore: the FragmentManager
            // already recreated these, just re-grab the references.
            sendFragment = supportFragmentManager.findFragmentByTag(TAG_SEND) as? SendFragment ?: SendFragment()
            transfersFragment = supportFragmentManager.findFragmentByTag(TAG_TRANSFERS) as? TransfersFragment ?: TransfersFragment()
            settingsFragment = supportFragmentManager.findFragmentByTag(TAG_SETTINGS) as? SettingsFragment ?: SettingsFragment()
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setItems(
            listOf(
                BottomNavBar.NavItem(R.drawable.ic_send, "Send"),
                BottomNavBar.NavItem(R.drawable.ic_swap, "Transfers"),
                BottomNavBar.NavItem(R.drawable.ic_settings, "Settings"),
            ),
            initialSelection = 0
        ) { index -> switchTab(index) }
    }

    // ── Pass-19/20 (the nav under-gradient, done right) ──────────────────────
    /** Builds the band under the tab bar as a CODE-constructed multi-stop
     *  gradient instead of the XML shape's 3 fixed stops.
     *
     *  Why: the XML <gradient> only exposes start/center/end — three stops
     *  pinned at 0 %, 50 %, 100 %, no way to place them anywhere else — and
     *  every pass that tried to "anchor the fade at the bottom" with those
     *  three stops produced either a full-height wash (the pass-17 bug) or a
     *  top half that renders nothing at all and then STARTS DARKENING
     *  ABRUPTLY at the band's exact midpoint (the pass-18 shape): a hard,
     *  visible "fade begins here" line across the middle of the band that
     *  read as exactly the reported "starts at the top and extends
     *  downward / glitchy". A professional bottom-anchored fade is a smooth
     *  CONTINUOUS ramp: no hard line anywhere, the darkening accelerating
     *  gently from the band's top, concentrated toward the bottom edge.
     *  GradientDrawable's constructor takes any NUMBER of equally-spaced
     *  colors (API 1+), so a 9-stop ease-in curve over bg_deep's own RGB
     *  renders it properly on every API level — including Android 5, where
     *  the multi-color LinearGradient path is the oldest, most-tested code in
     *  the whole drawable pipeline.
     *
     *  Pass-20 changes, per this round's feedback: the band is now TALLER
     *  than the bar's own footprint (88dp vs the 52dp bar + 12dp margin),
     *  so a genuine ~24dp of fade is visible ABOVE the bar as content
     *  scrolls under it — "increase the height/range of the gradient effect
     *  by a small amount" — and every stop past the first two is DARKER
     *  than the pass-19 curve, ending in a near-opaque glow at the screen's
     *  bottom edge: the "darker, more professional" reading. The curve
     *  (alpha over the band's 88dp, top → bottom):
     *    0 %, 1 %, 3 %, 7 %, 13 %, 23 %, 36 %, 54 %, 97 %
     *  — imperceptible over the bar's upper half, plainly visible in the
     *  fade's visible stretch above the bar, darkest exactly at the
     *  bottom edge. The band's footprint, the bar's pill, and every other
     *  chrome element are untouched. */
    private fun applyNavUnderGradient(navInsetPx: Int) {
        try {
            // ── Pass-32 (#5): the ramp is pinned to the band ABOVE the ─────────
            // navigation bar, so the VISIBLE gradient is identical for gesture
            // and three-button navigation. The old single stretched drawable
            // mapped its alpha ramp across the WHOLE (88dp + inset) view: the
            // opaque half of the ramp landed behind the opaque 3-button bar,
            // so button users saw only the ramp's first ~65% — a visibly
            // fainter, shorter fade than gesture users (whose small inset
            // leaves ~85% of the ramp visible). Now a LayerDrawable draws the
            // SAME 9-stop ramp in exactly the top 88dp (the region above the
            // bar in every mode) and extends the card surface color solidly
            // through the bar's own zone, so the effect that "starts at the
            // bottom and rises" looks the same on both navigation
            // configurations. Idempotent per inset value — the insets listener
            // fires on every animation frame of a bar show/hide.
            if (navInsetPx == lastNavGradientInset) return
            lastNavGradientInset = navInsetPx
            val deep = ContextCompat.getColor(this, R.color.bg_deep)
            val rgb = deep and 0x00FFFFFF
            val alphas = intArrayOf(0x00, 0x03, 0x08, 0x12, 0x22, 0x3A, 0x5C, 0x8A, 0xF7)
            val colors = IntArray(alphas.size) { i -> (alphas[i] shl 24) or rgb }
            val ramp = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, colors)
            @Suppress("DEPRECATION")
            ramp.setDither(true)   // banding insurance on unpremultiplied legacy paths
            val layers = android.graphics.drawable.LayerDrawable(arrayOf(
                ramp,
                android.graphics.drawable.ColorDrawable(deep)))
            val bandPx = dp(88)
            layers.setLayerInset(0, 0, 0, 0, navInsetPx)   // ramp: the full 88dp band above the bar
            layers.setLayerInset(1, 0, bandPx, 0, 0)       // solid: the bar's own zone, to the true bottom edge
            binding.navUnderGradient.background = layers
        } catch (e: Exception) {
            // The XML fallback (bg_nav_under_gradient.xml) stays in place — a
            // rare failure here must never cost the layout its background.
        }
    }

    /** Pass-32 (#5): the last navigation inset the under-bar gradient was
     *  built for (see applyNavUnderGradient) — makes the per-frame inset
     *  dispatches during a bar animation a cheap no-op. */
    private var lastNavGradientInset = -1

    private fun switchTab(index: Int) {
        currentTabIndex = index
        val target = when (index) { 1 -> transfersFragment; 2 -> settingsFragment; else -> sendFragment }
        val txn = supportFragmentManager.beginTransaction()
        // ── Pass-26 (#8): the tab-content transition ─────────────────────────────
        // A short shared-axis-Y crossfade (fade + 2.5% rise, 150ms — see
        // res/anim/nav_tab_enter.xml / nav_tab_exit.xml) rides the SAME
        // show()/hide() transaction the shell has always used, so:
        //   • every fragment stays added exactly once — scroll positions,
        //     staged queues, expanded folders and every other piece of tab
        //     state survive the switch exactly as before (nothing is
        //     recreated, nothing reloads);
        //   • the 150ms matches the bottom-nav indicator's own crossfade, so
        //     the pill, the icon tint and the content settle as one motion —
        //     the cohesive navigation experience the brief asked for;
        //   • it is alpha+translate on already-laid-out views: light enough
        //     for the weakest supported devices, no interaction is blocked,
        //     and a rapid re-switch simply replaces the running transition
        //     with the next one (FragmentManager cancels the superseded
        //     animation and applies the new op — the hidden/shown state is
        //     committed synchronously, so no inconsistent UI state is
        //     possible even mid-animation);
        //   • the FIRST transaction (setupFragments' initial add) stays
        //     animation-free on purpose — the app opens on Send instantly.
        txn.setCustomAnimations(R.anim.nav_tab_enter, R.anim.nav_tab_exit,
            R.anim.nav_tab_enter, R.anim.nav_tab_exit)
        listOf(sendFragment, transfersFragment, settingsFragment).forEach {
            if (it === target) txn.show(it) else txn.hide(it)
        }
        txn.commit()
        // The floating popup is redundant on the Transfers tab itself (that
        // screen already shows full detail) -- hide it immediately on
        // switch rather than waiting for the next poll tick.
        if (index == 1) binding.floatingTransferBar.visibility = View.GONE else updateFloatingPopupAndBadge()

        // (The "landing on Send restores a minimized incoming sheet" hook
        // that used to sit here is gone on purpose: incoming requests are
        // not a Send-tab concern. The receiving modal and its floating
        // badge (ui/ReceivingModal.kt) work identically on every screen,
        // so nothing about them is special-cased to any one tab.)
    }

    /** ── GOAT-PASS 10 (§9): the bridge for the internal "Open Received ──
     *  Folder" flow — the full-screen Files Sheet opened from the Transfers
     *  tab stages its picked items into the Send tab's queue (the one and
     *  only staging surface) and brings that tab forward, so the sheet's Add
     *  button behaves exactly as it does when the sheet is opened from Send
     *  itself. Windowed: the fragments live for the activity's lifetime, but
     *  a detached fragment must never be touched. */
    fun stagePickedFilesAndShowSend(items: List<com.lumidrop.x.transfer.PreviewItem>) {
        try {
            if (items.isEmpty()) return
            binding.bottomNav.select(0, animate = true, notify = true)
            sendFragment.stageItemsFromExternal(items)
        } catch (e: Exception) {
            /* a dead fragment must never crash the shell over a staging pick */
        }
    }

    /** Back press: if a non-Send tab is showing, go to Send first (standard
     *  bottom-nav behavior) -- only exits the app from Send itself. */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentTabIndex != 0) {
                    binding.bottomNav.select(0, animate = true, notify = true)
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    // Blackout (moved from the old Send-screen layout -- see class doc) --------------------

    /** True while the AMOLED blackout overlay is up. SendFragment's card-pin
     *  machinery reads this to FREEZE its geometry latches for the whole
     *  blackout window: blackout deliberately distorts the window's layout
     *  (both system bars hidden, root top inset forced to zero, the scroll
     *  viewport grown by both), so any card-height pin or bottom-clearance
     *  base derived DURING blackout is wrong by exactly the bars the moment
     *  it ends. Freezing keeps the Connected Devices card at its exact
     *  pre-blackout height across the round-trip by construction — exit
     *  re-asserts the same bar state that produced those values. */
    val isBlackoutActive: Boolean get() = blackoutActive

    fun enterBlackout() {
        blackoutActive = true
        binding.blackoutOverlay.visibility = View.VISIBLE
        binding.blackoutOverlay.alpha = 0f
        binding.blackoutOverlay.animate().alpha(1f).setDuration(350).start()
        // ── Pass-32 (#4): snapshot the Send tab's current card geometry ──────
        // BEFORE the bars-hidden state can touch any layout — the exit
        // restores this exact height by construction (see SendFragment's
        // onBlackoutEntered/onBlackoutExited pair).
        try { sendFragment.onBlackoutEntered() } catch (e: Exception) { /* never let a geometry pass crash the shell */ }
        applyBlackoutSystemBars()
        Toast.makeText(this, "Long-press anywhere to exit blackout mode", Toast.LENGTH_SHORT).show()
    }

    private fun exitBlackout() {
        binding.blackoutOverlay.animate().alpha(0f).setDuration(300).withEndAction {
            binding.blackoutOverlay.visibility = View.GONE
        }.start()
        blackoutActive = false
        // Pass-11: exiting blackout restores the NORMAL system-bar state —
        // which itself re-hides the bars when the Full Screen setting is on.
        syncSystemBars()
        // ── Pass-30 (#1): the deterministic geometry hand-off to the Send ──────
        // tab. Bar restoration is ANIMATED — the root padding and the
        // navigation inset converge back over several frames, and the Send
        // tab's card-height pin used to ride whatever insets dispatches
        // happened to arrive mid-animation: a frame with the smaller
        // still-settling geometry could mint (or key-match) a short pin that
        // then stuck forever when no further dispatch landed — the reported
        // "Connected Devices card stays short after blackout until another
        // sheet/modal is opened" (those surfaces force fresh insets
        // dispatches, which is exactly why they 'fixed' it). The hook makes
        // the re-derivation explicit and idempotent: fresh insets + one
        // re-latch now, and one more after the bar animation has certainly
        // settled — the card is correct on the first frame after exit and
        // stays correct, with zero dependence on dispatch luck. Safe to call
        // while a fragment transaction is idle (the fragment is added once
        // and only shown/hidden); the hook itself re-checks the binding.
        try { sendFragment.onBlackoutExited() } catch (e: Exception) { /* never let a geometry pass crash the shell */ }
    }

    private fun applyBlackoutSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.insetsController?.let {
                    // Without this, once the person swipes down to peek the status
                    // bar, some Android versions/OEMs leave it visible indefinitely
                    // -- hide() alone only hides it once, at the moment blackout
                    // starts. This tells the system a swipe-reveal is meant to be
                    // transient: shown briefly, then auto-hidden again on its own.
                    it.systemBarsBehavior = android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    it.hide(android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars())
                }
            } catch (e: Exception) { /* never let a system-UI hiccup crash the app */ }
        } else {
            // ── Pass-15 (blackout status-bar root fix): the legacy branch used ──
            // to reuse applyLegacyImmersiveFlags() — the Hide-Navigation-Bar
            // SETTING's recipe, which deliberately never touches the status
            // bar (Pass-12). Blackout is a different feature with a different
            // contract (a fully black screen, both bars gone), so it gets its
            // own legacy recipe here: IMMERSIVE_STICKY + HIDE_NAVIGATION + the
            // FULLSCREEN flag pair that actually removes the status bar on
            // API 21-29. Previously blackout on Android 5-9 left the clock /
            // battery strip lit at the top of a supposedly pitch-black screen.
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    // ── Pass-11 (Required Implementation 4): Full Screen immersive mode ──────

    /** Called by SettingsFragment the moment Save commits a Full Screen
     *  toggle change — applies (or removes) the immersive system bars
     *  immediately, with no restart. */
    fun applyFullScreenFromSettings() {
        syncSystemBars()
    }

    /** The ONE system-bar arbiter: blackout wins while active, otherwise the
     *  persisted Full Screen preference decides hidden-vs-restored. */
    private fun syncSystemBars() {
        try {
            if (blackoutActive) { applyBlackoutSystemBars(); return }
            if (com.lumidrop.x.settings.SettingsManager.isFullScreen(this)) applyFullScreenBars()
            else restoreSystemBars()
        } catch (e: Exception) { /* never let a system-UI hiccup crash the app */ }
        // Pass-16: the flag changes above retrigger an insets dispatch on most
        // devices, but not on every Android 5 OEM build — ask for one
        // explicitly so the rootFrame top-padding guard (onCreate) always
        // re-evaluates right after the bar state changed.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            try { binding.rootFrame.requestApplyInsets() } catch (e: Exception) {}
        }
    }

    private fun applyFullScreenBars() {
        // Pass-12 (per direct feedback): the setting now hides ONLY the
        // Android navigation buttons — the status bar (clock, battery,
        // signal) stays fully visible. The old behavior hid both, which the
        // person explicitly rejected.
        // ── Pass-27 (#4.1): the actual hide/show now runs through the shared ─────
        // SystemBarChrome primitives (API 30+ WindowInsetsController.hide with
        // swipe-transient behavior; API 21-29 the classic IMMERSIVE_STICKY
        // recipe WITHOUT any FULLSCREEN flags) — the exact same recipes every
        // dialog window and sub-settings screen uses, so no two call sites can
        // drift. With the main window edge-to-edge (see onCreate), hiding the
        // bar zeroes the navigation-bar inset and the rootFrame insets listener
        // hands that space straight back to the layout — no dependence on the
        // OEM-specific "does the window resize when bars hide" behavior at all.
        SystemBarChrome.hideNavigationBar(window)
        // ── Pass-28 (Blackout exit keeps the status bar) ────────────────────────
        // Blackout hides BOTH bars (see applyBlackoutSystemBars), and this
        // recipe used to re-assert only the navigation bar's hidden state —
        // so a blackout round-trip with the "Hide Navigation Bar" preference
        // ON left the STATUS bar in the state blackout put it in: hidden
        // forever, the top of the screen a dead black strip with no clock,
        // battery or signal until the app was restarted. The preference must
        // define BOTH bars' state, not just the half it owns — nav hidden,
        // status ALWAYS visible (the Pass-12 contract above) — so the status
        // bar is re-shown explicitly here on API 30+, where the two-bar
        // hide() left no flag assignment behind that would restore it by
        // itself. On API 21-29 the recipe above is a full systemUiVisibility
        // ASSIGNMENT that already clears blackout's FULLSCREEN flags (the
        // status bar returns with them); a no-op here whenever the status
        // bar is already visible, and syncSystemBars re-asserts this whole
        // state on every resume/focus gain, so repeated blackout cycles
        // cannot drift.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.insetsController?.show(android.view.WindowInsets.Type.statusBars())
            } catch (e: Exception) { /* never let a system-UI hiccup crash the app */ }
        }
    }

    private fun restoreSystemBars() {
        // Pass-12: only the navigation bars were ever hidden by the setting —
        // restoring means showing exactly those back.
        // ── Pass-15 (the reported "status bar stays black after blackout") ──
        // BLACKOUT hides the status bar too (both APIs — see
        // applyBlackoutSystemBars), and this restore path is the one that runs
        // when the Full Screen setting is OFF. It used to show ONLY the
        // navigationBars() on API 30+, so a blackout exit left the status bar
        // hidden forever: the top of the screen stayed a dead black strip with
        // no clock, battery or signal. Showing BOTH is always correct here —
        // the setting itself never hides the status bar, so the extra show()
        // is a no-op for it, and the legacy `flags = 0` below already showed
        // both on API 21-29.
        // ── Pass-27 (#4.1): shared primitive, same reasoning as everywhere ───
        // else — with the edge-to-edge window the restored inset re-reserves
        // the bar area in the same layout pass.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                window.insetsController?.show(android.view.WindowInsets.Type.statusBars() or
                    android.view.WindowInsets.Type.navigationBars())
            } catch (e: Exception) { /* never let a system-UI hiccup crash the app */ }
            SystemBarChrome.setAppearanceLightBars(window)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = 0
        }
    }

    /** The API 21-29 recipe for the Hide Navigation Bar setting:
     * IMMERSIVE_STICKY (the buttons return transiently on a swipe from the
     * bottom edge, then auto-hide again on their own) plus
     * HIDE_NAVIGATION + its LAYOUT flag — content lays out behind the hidden
     * button area instead of leaving a black strip where it used to be. The
     * window and every fragment root paint bg_deep, so the revealed area
     * always matches the app's background. Deliberately NO FULLSCREEN /
     * LAYOUT_FULLSCREEN flags: the status bar remains visible (Pass-12).
     * Kept as the one legacy definition even though Pass-27 routes through
     * SystemBarChrome.hideNavigationBar (identical flags) — this doc is the
     * design contract for WHY the status bar is never touched here. */
    private fun applyLegacyImmersiveFlags() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
    }

    private fun setupBlackout() {
        binding.blackoutOverlay.setOnLongClickListener { exitBlackout(); true }
    }

    // The notification shade (recents/app-switcher, a system dialog, split-
    // screen entry, etc.) takes real window focus away from the app; when
    // focus returns, this re-asserts the hidden system bars if blackout is
    // still meant to be active -- the transient-swipe behavior above only
    // covers a quick peek, this covers anything that fully steals focus.
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Pass-11: re-assert whichever system-bar policy is currently meant to
        // be active — blackout's hidden bars AND the Full Screen preference
        // both survive a focus steal (notification shade, system dialog).
        if (hasFocus) syncSystemBars()
    }

    // Floating transfer popup (moved from the old Send-screen layout) ----------------------

    // ── Pass-15 (ask-before-resend) queue: one dialog at a time, in arrival ──
    // order. The hook runs on the send loop's IO thread; everything below
    // hops to the main thread, and each dialog's dismiss pumps the next.
    private val resendAsks = java.util.concurrent.ConcurrentLinkedQueue<Triple<String, String, (Boolean) -> Unit>>()
    @Volatile private var resendAskShowing = false

    private fun enqueueResendAsk(fileName: String, peerName: String, answer: (Boolean) -> Unit) {
        resendAsks.add(Triple(fileName, peerName, answer))
        handler.post { pumpResendAsks() }
    }

    private fun pumpResendAsks() {
        if (resendAskShowing) return
        val next = resendAsks.poll() ?: return
        if (isFinishing || (Build.VERSION.SDK_INT >= 17 && isDestroyed)) {
            // No window to ask in — never silently resend a deliberately
            // discarded file. The engine-side timeout would reach the same
            // verdict; answering now just releases the loop sooner.
            next.third(false)
            handler.post { pumpResendAsks() }
            return
        }
        resendAskShowing = true
        val answered = java.util.concurrent.atomic.AtomicBoolean(false)
        val dialog = showLdxDialog(
            context = this,
            title = "Send again from the beginning?",
            message = "\"${next.first}\" was interrupted while being sent to ${next.second}, " +
                "and the partially received data was deleted on that device.\n\n" +
                "Send the whole file again, or cancel this transfer?",
            iconRes = R.drawable.ic_send,
            actions = listOf(
                LdxDialogAction("Cancel", LdxButtonStyle.GHOST) {
                    if (answered.compareAndSet(false, true)) next.third(false)
                },
                LdxDialogAction("Send again", LdxButtonStyle.PRIMARY) {
                    if (answered.compareAndSet(false, true)) next.third(true)
                },
            ),
        )
        dialog.setOnDismissListener {
            // Backdrop/back dismissal is an answer too — never leave the send
            // loop waiting on silence when the person walked away.
            if (answered.compareAndSet(false, true)) next.third(false)
            resendAskShowing = false
            handler.post { pumpResendAsks() }
        }
    }

    private fun setupFloatingPopup() {
        binding.floatBarCollapsed.setOnClickListener {
            floatBarExpanded = !floatBarExpanded
            binding.floatBarExpanded.visibility = if (floatBarExpanded) View.VISIBLE else View.GONE
            binding.tvFloatBarChevron.setImageResource(if (floatBarExpanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_up)
            if (floatBarExpanded) renderFloatingList()
        }
    }

    private fun updateFloatingPopupAndBadge() {
        val active = TransferManager.activeCount()
        binding.bottomNav.setBadge(1, active)

        // (The pending-incoming badge that used to sit on the Send tab's
        // own icon is gone on purpose, for the same reason as the
        // restore-on-Send hook in switchTab: incoming requests aren't a
        // Send-tab concern, and the floating badge in ui/ReceivingModal.kt
        // already shows the live count on every screen.)

        // Hidden entirely on the Transfers tab (redundant with the full
        // detail that screen already shows) or when the setting is off, or
        // when the server isn't running (ServerForegroundService.tearDown()
        // already fails every active transfer when the server stops, so
        // `active` should already be 0 then -- the isRunning check is a
        // defensive second guard against that invariant ever being violated).
        val show = currentTabIndex != 1 &&
            com.lumidrop.x.settings.SettingsManager.isFloatingTransferPopup(this) &&
            active > 0 && ServerForegroundService.isRunning

        binding.floatingTransferBar.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            binding.tvFloatBarCount.text = "$active active transfer${if (active != 1) "s" else ""}"
            if (floatBarExpanded) renderFloatingList()
        }
    }

    private fun renderFloatingList() {
        val list = binding.floatBarList
        list.removeAllViews()
        val actives = TransferManager.snapshot().filter { it.status == TransferManager.Status.ACTIVE }.take(4)
        for (t in actives) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            row.addView(android.widget.ImageView(this).apply {
                setImageDrawable(Widgets.icon(this@MainActivity,
                    if (t.direction == TransferManager.Direction.UPLOAD) R.drawable.ic_arrow_down else R.drawable.ic_arrow_up,
                    color(if (t.direction == TransferManager.Direction.UPLOAD) R.color.orange else R.color.accent_light)))
                layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).also { it.marginEnd = dp(8) }
            })
            row.addView(TextView(this).apply {
                text = t.fileName; textSize = 12f
                setTextColor(color(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            row.addView(TextView(this).apply {
                text = when {
                    t.pauseRequested  -> "Paused"
                    t.isIndeterminate -> "..."
                    else              -> "${t.progressPct}%"
                }
                textSize = 11f
                setTextColor(if (t.pauseRequested) color(R.color.orange) else color(R.color.text_muted))
                setPadding(dp(8), 0, 0, 0)
            })
            list.addView(row)
            list.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                isIndeterminate = t.isIndeterminate
                if (!t.isIndeterminate) progress = t.progressPct
                alpha = if (t.pauseRequested) 0.45f else 1f
                val tint = android.content.res.ColorStateList.valueOf(
                    if (t.pauseRequested) color(R.color.text_muted) else color(R.color.accent))
                progressTintList = tint
                indeterminateTintList = tint
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(2)).also { it.bottomMargin = dp(4) }
            })
        }
        list.addView(TextView(this).apply {
            text = "View all in Transfers"
            textSize = 12f
            setTextColor(color(R.color.accent_light))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(2))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { binding.bottomNav.select(1, animate = true, notify = true) }
        })
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    companion object {
        private const val TAG_SEND = "send"
        private const val TAG_TRANSFERS = "transfers"
        private const val TAG_SETTINGS = "settings"
    }
}
