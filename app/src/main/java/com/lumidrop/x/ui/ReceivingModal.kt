package com.lumidrop.x.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import com.lumidrop.x.R
import com.lumidrop.x.server.LumiDropServer
import com.lumidrop.x.service.ServerForegroundService

/**
 * GOAT v2 (item 8 — Universal Receiving Modal): the incoming-transfer
 * accept/decline sheet plus its minimized floating badge, as a component
 * ANY screen can host — deliberately not owned by one tab, one Fragment,
 * or even one Activity.
 *
 * How it got here, since each step was a real fix for a real gap:
 *  1. It used to live inside SendFragment, on that Fragment's own poll
 *     loop — which stops whenever the Fragment is hidden (correct for the
 *     rest of that screen, to save battery). A request arriving while the
 *     person was on Transfers or Settings was never even DETECTED.
 *  2. Moved to MainActivity, on the Activity's own tick. That fixed the
 *     tabs, but this app has one more screen — WebUiSettingsActivity —
 *     which is a separate Activity stacked ON TOP
 *     of MainActivity. MainActivity is paused there, so its tick (and any
 *     dialog it owned) simply wasn't there: same gap, different screens.
 *  3. This class: each Activity creates one and drives it from its own
 *     onResume/onPause via [start]/[stop]. The dialog is always built from
 *     the Activity that's actually on top (a Dialog can only show over the
 *     window that created it), and only one Activity is ever resumed at a
 *     time, so only one instance is ever ticking.
 *
 * Any NEW screen (Activity) added to this app needs the same three lines
 * the existing ones have: construct one after setContentView, [start] in
 * onResume, [stop] in onPause.
 *
 * What's shared across every screen (companion, below): which pending set
 * the person deliberately dismissed (so navigating somewhere else doesn't
 * re-pop a sheet they just swiped away), which incoming folders are
 * expanded, and where they last dragged the floating badge (so it doesn't
 * jump back to its default corner on every screen change). What's per
 * screen: the sheet's Dialog and the badge View, since both are attached
 * to one specific Activity's window.
 *
 * [stop] dismisses the sheet WITHOUT recording that as a deliberate
 * dismissal (references are cleared before dismiss(), which is what the
 * dismiss listener checks) — leaving a screen is not the person choosing
 * to ignore the request, so it comes right back on whichever screen they
 * land on next.
 *
 * Background (no screen of ours in front at all): a Dialog can't show
 * there, and doesn't need to — ServerForegroundService already posts
 * notifications for incoming requests that open the app; landing back in
 * any screen re-runs this on start().
 */
class ReceivingModal(
    private val activity: Activity,
    private val badgeParent: () -> FrameLayout,
) {
    private val server: LumiDropServer? get() = ServerForegroundService.instance?.server

    /** ── FINAL PASS 9 (PART 4 — the intermittent "Accept did nothing"): the ──
     *  Accept/Decline row handlers used `server?.` — and [server] is a LIVE
     *  lookup (ServerForegroundService.instance?.server) that reads null
     *  during the brief window where the foreground service is being
     *  restarted (system reclaim, "Stop server"/"Start server" toggle, the
     *  app returning from background on some OEM builds). A tap landing in
     *  that window was silently swallowed by the safe-call operator: no
     *  decision written, no feedback, the row stayed pending — and the
     *  sender kept "Waiting" until its own deadline. The decision now
     *  either reaches a live server or the tap FAILS LOUDLY (a Toast), so
     *  the person taps again half a second later and the retry path (same
     *  idempotent endpoint) completes it — never a silent loss. */
    /** ── GOAT-PASS 17 (§14 — an ineffective tap is never silent): runs one ──
     *  accept/decline action and verifies it CHANGED the pending set. The
     *  decision writes are synchronous in-memory map writes on the server
     *  object, so a decision that landed is visible in pendingNativePushList
     *  the moment the call returns; re-checking once at +600ms covers the
     *  sheet's own render cadence. If the pending signature is UNCHANGED,
     *  the tap did nothing (unknown push, swept record, mid-tap edge) — the
     *  user gets a one-shot honest toast and can retry, instead of staring
     *  at a row that pretends to work. Throttled to one toast at a time. */
    private var ineffectiveTapToastAt = 0L
    private fun withEffectFeedback(action: () -> Unit) {
        val srv = server
        val before = srv?.let { pendingSignatureOf(it) }
        action()
        if (srv == null || before == null) return
        uiHandler.postDelayed({
            try {
                val after = pendingSignatureOf(srv)
                if (after == before) {
                    val now = System.currentTimeMillis()
                    if (now - ineffectiveTapToastAt > 4000L) {
                        ineffectiveTapToastAt = now
                        android.widget.Toast.makeText(activity,
                            "That item is no longer available — refresh incoming in a moment and try again",
                            android.widget.Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) { /* server gone — nothing more to say */ }
        }, 600L)
    }
    private fun pendingSignatureOf(srv: LumiDropServer): String = try {
        srv.pendingIncomingCount().toString() + "|" +
            srv.pendingNativePushList().joinToString(";") { p ->
                p.id + ":" + (if (p.manifestReady)
                    p.topLevelNames.filter { p.itemDecisions[it] == "pending" }.joinToString(",")
                else p.announcedTopLevel.filter { p.preDecisions[it.name] == null }.joinToString(",") { it.name })
            }
    } catch (e: Exception) { "" }

    private fun serverOrToast(): LumiDropServer? {
        val srv = server
        if (srv == null) {
            try {
                android.widget.Toast.makeText(activity, "Server restarting — tap Accept again",
                    android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { /* activity gone — nothing more to say */ }
        }
        return srv
    }


    // Named uiHandler, not handler: inside the apply {} blocks below a bare
    // `handler` would resolve to the View's own getHandler() instead.
    private val uiHandler = Handler(Looper.getMainLooper())

    // Same 500ms cadence the original (SendFragment) loop used for this
    // exact job — how quickly an incoming request shows up is exactly the
    // responsiveness that matters here (see the "instant incoming
    // response" note in SendFragment.updateUI for the history of why).
    //
    // v1.0 (critical — "incoming push never appears on the receiver's
    // screen"): this loop is now UNKILLABLE. The old shape ran the two
    // update calls bare and only THEN re-armed postDelayed — so ANY single
    // exception anywhere in the render chain (a BadTokenException out of
    // dialog.show() on a finishing window, an NPE in a mid-teardown view
    // tree, an indexing edge in a folder row) killed the loop PERMANENTLY
    // while the app stayed foregrounded, because nothing re-arms it except
    // start() on the NEXT onResume — which never comes for an app that
    // never left the foreground. The system notification still fired (it's
    // posted by the service's HTTP thread, fully independent of this loop),
    // which is exactly the reported "notification arrived, but the
    // accept/decline sheet never appeared". Two structural changes: (1) the
    // next tick is scheduled BEFORE the body runs, so no exception in the
    // body can ever prevent re-arming; (2) the body itself is isolated,
    // logged, and swallowed — one bad tick costs at most 500ms, never the
    // whole incoming-transfer UI for the rest of the session.
    private val tick = object : Runnable {
        override fun run() {
            uiHandler.postDelayed(this, 500L)
            if (activity.isFinishing || activity.isDestroyed) return
            try {
                updateIncomingRequests()
            } catch (e: Exception) {
                // one bad tick costs at most 500ms — see the loop's doc above
            }
        }
    }

    private fun dp(n: Int) = activity.dp(n)
    private fun color(res: Int) = ContextCompat.getColor(activity, res)

    // Floating minimized badge ---------------------------------------------

    // ── Pass-12 (Required Implementation 10 — minimized badge redesign) ────
    // Rebuilt to the requested spec: a professional, minimalist marker with
    // a DARK GRAY interior and a DARK PURPLE outline — no gradient, no sheen,
    // no glow, no lightning flourishes. Exactly the same confirmed behavior
    // (drag/clamp/tap-to-restore via wireBadgeGestures, cross-screen position
    // via the companion badgeTx/badgeTy, and updateIncomingFloatBtn's
    // visibility & count logic); only the visual changed:
    //  - 48dp touch frame (Android's tap-target floor — unchanged) holding a
    //    42dp circular disc: flat bg_secondary (the app's dark-gray surface
    //    tone) with a 1.5dp accent_dark (dark purple) ring — quiet against
    //    every background it drifts over, unmistakably on-brand.
    //  - The glyph keeps the purpose-built ic_incoming_tray (open tray +
    //    descending file) — "incoming files, waiting" — rendered in the soft
    //    purple-white the app uses for selected icons (nav_icon_selected),
    //    crisp on the dark disc.
    //  - The count moved from a white pill to a DARK pill — bg_secondary
    //    fill, 20%-accent hairline, light digits — so it no longer flashes
    //    white over every surface.
    //  - Elevation sits ABOVE the bottom navigation bar's (elevation_nav =
    //    22dp) and below blackout (32dp), fixing the reported layering bug
    //    where a badge dragged over the nav bar sank BEHIND it and became
    //    unreachable — it now floats above the entire app UI while blackout
    //    still covers it.
    private val badge = FrameLayout(activity).apply {
        elevation = activity.resources.getDimension(R.dimen.elevation_nav) + dp(2)
        isClickable = true; isFocusable = true
        visibility = View.GONE
        contentDescription = "Incoming transfer requests waiting \u2014 drag to move, tap to open"
    }

    private val badgeIconView = ImageView(activity).apply {
        setImageDrawable(Widgets.icon(activity, R.drawable.ic_incoming_tray,
            ContextCompat.getColor(activity, R.color.nav_icon_selected)))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val pad = dp((VISUAL_BADGE_DP - BADGE_ICON_DP) / 2)
        setPadding(pad, pad, pad, pad)
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            // Pass-12: flat dark-gray disc + dark purple ring — the exact
            // spec (no gradient, no glow).
            setColor(color(R.color.bg_secondary))
            setStroke(dp(2), color(R.color.accent_dark))
        }
        layoutParams = FrameLayout.LayoutParams(dp(VISUAL_BADGE_DP), dp(VISUAL_BADGE_DP), Gravity.CENTER)
    }

    /** Count pill, overlaid top-end on the disc. Pass-12: dark surface +
     *  20%-accent hairline + light digits — subtle, consistent with the
     *  redesigned badge, never a white flash over whatever the badge drifts
     *  across. Its contentDescription-free design is fine: the badge frame's
     *  own contentDescription (updated live in updateIncomingFloatBtn)
     *  speaks the count for accessibility. */
    private val badgePill = TextView(activity).apply {
        textSize = 9.5f
        setTextColor(color(R.color.text_primary))
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(16); minHeight = dp(16)
        setPadding(dp(4), 0, dp(4), dp(1))
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color(R.color.bg_secondary))
            setStroke(dp(1), color(R.color.border_accent_20))
        }
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
    }

    init {
        badge.addView(badgeIconView)
        badge.addView(badgePill)
        wireBadgeGestures()
    }

    /** Called from start() rather than the constructor: the host's content
     *  view may not exist yet at construction time. Idempotent. */
    private fun attachBadgeIfNeeded() {
        if (badge.parent != null) return
        val parent = badgeParent()
        // Default: upper-end corner, clear of the bottom nav and the
        // floating transfer popup (both at the bottom), and of
        // WebUiSettingsActivity's own top-center Save button. 48dp frame =
        // the touch target; the 42dp orb is centered inside it (see the
        // badge's doc comment above).
        parent.addView(badge, FrameLayout.LayoutParams(dp(BADGE_TOUCH_DP), dp(BADGE_TOUCH_DP), Gravity.TOP or Gravity.END).also {
            it.topMargin = dp(90); it.marginEnd = dp(16)
        })
        badge.translationX = badgeTx; badge.translationY = badgeTy
        // A stored drag offset that was valid on one screen/orientation can
        // put the badge off-screen on another (dragged to the bottom in
        // portrait, then rotated to a much shorter landscape) — re-clamp
        // whenever the host's size changes so it can never get lost.
        parent.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(
                v: View?, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int,
            ) { clampBadge() }
        })
    }

    private fun clampF(v: Float, lo: Float, hi: Float): Float = if (hi < lo) lo else v.coerceIn(lo, hi)

    private fun clampBadge() {
        val parent = badge.parent as? View ?: return
        if (badge.visibility != View.VISIBLE || parent.width == 0 || badge.width == 0) return
        badge.translationX = clampF(badge.translationX, -badge.left.toFloat(), (parent.width - badge.right).toFloat())
        badge.translationY = clampF(badge.translationY, -badge.top.toFloat(), (parent.height - badge.bottom).toFloat())
        badgeTx = badge.translationX; badgeTy = badge.translationY
    }

    /** Plain setOnTouchListener rather than the OnItemTouchListener
     *  technique the Media Selector's drag-to-select uses (see
     *  attachDragSelect in MediaPickerSheet.kt) — there's no
     *  RecyclerView/scroll conflict to resolve here, just one standalone
     *  view being moved around its parent. A tap (movement under touch
     *  slop) restores the sheet via performClick(), the accessibility-aware
     *  way to fire a view's own click listener from custom touch handling. */
    private fun wireBadgeGestures() {
        val touchSlop = ViewConfiguration.get(activity).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var startTx = 0f
        var startTy = 0f
        var dragging = false
        badge.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX; downRawY = event.rawY
                    startTx = v.translationX; startTy = v.translationY
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) dragging = true
                    if (dragging) {
                        val parent = v.parent as? View
                        if (parent != null) {
                            v.translationX = clampF(startTx + dx, -v.left.toFloat(), (parent.width - v.right).toFloat())
                            v.translationY = clampF(startTy + dy, -v.top.toFloat(), (parent.height - v.bottom).toFloat())
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) { badgeTx = v.translationX; badgeTy = v.translationY }
                    else if (event.actionMasked == MotionEvent.ACTION_UP) v.performClick()
                    true
                }
                else -> true
            }
        }
        badge.setOnClickListener { restoreIncomingSheet() }
    }

    /** Keeps the badge in sync with the exact state updateIncomingRequests
     *  just computed — called from every one of its exit paths (via its own
     *  try/finally) rather than on a separate timer, so the two can never
     *  disagree even briefly. Recomputes pendingIncomingCount() fresh
     *  rather than threading a value out of updateIncomingRequests's local
     *  scope: a cheap, already-in-memory read, and it keeps this function
     *  self-contained regardless of which branch above it returned from.
     *  Shown on every screen the moment something is pending and the full
     *  sheet isn't already open (redundant otherwise).
     *
     *  GOAT v3 (§3.2): the count now renders on the pill rather than filling
     *  the orb, the contentDescription speaks the live count (a bare static
     *  string used to leave a screen-reader user with no idea how many were
     *  waiting), the moment it first appears it scales in with a soft
     *  overshoot rather than cutting in flat — and the count CHANGING while
     *  already visible gives the pill a quick bump so the change is
     *  noticeable without being loud. Visibility/clamp/gesture logic is
     *  untouched. */
    private fun updateIncomingFloatBtn() {
        val pending = if (!ServerForegroundService.isRunning) 0 else (server?.pendingIncomingCount() ?: 0)
        val show = pending > 0 && !isIncomingSheetShowing
        val wasHidden = badge.visibility != View.VISIBLE
        badge.visibility = if (show) View.VISIBLE else View.GONE
        if (show) {
            val countText = if (pending > 99) "99+" else pending.toString()
            val countChanged = badgePill.text.toString() != countText
            badgePill.text = countText
            badge.contentDescription =
                "$pending incoming transfer request${if (pending != 1) "s" else ""} waiting \u2014 drag to move, tap to open"
            if (wasHidden) {
                badge.post { clampBadge() }
                // Entrance (§3.2 "Motion"): scale-in + fade-in, soft
                // overshoot — small, tasteful, finished in 220ms. No
                // explicit pivot: the default (view center) tracks whatever
                // size the badge lays out at, including before its first
                // layout pass completes.
                badge.scaleX = 0.6f; badge.scaleY = 0.6f; badge.alpha = 0f
                badge.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(Motion.DURATION_MEDIUM)
                    .setInterpolator(Motion.OVERSHOOT_SOFT)
                    .start()
            } else if (countChanged) {
                // Live count change: a quick 1→1.25→1 bump on the pill alone.
                badgePill.animate().cancel()
                badgePill.animate().scaleX(1.25f).scaleY(1.25f)
                    .setDuration(Motion.DURATION_INSTANT)
                    .withEndAction {
                        badgePill.animate().scaleX(1f).scaleY(1f)
                            .setDuration(Motion.DURATION_SHORT)
                            .start()
                    }
                    .start()
            }
        }
    }

    // Lifecycle -------------------------------------------------------------

    /** Call from the host Activity's onResume. */
    fun start() {
        attachBadgeIfNeeded()
        updateIncomingFloatBtn()
        uiHandler.removeCallbacks(tick)
        uiHandler.post(tick)
        // ── FINAL PASS 7 (PART 2 — first-launch resume reconciliation): the ──
        // tick's 500ms cadence is fine in steady state, but onResume is the
        // single most important reconciliation moment of the whole lifecycle —
        // it is exactly when the app returns from the ALL-FILES permission
        // settings screen (first install), from a notification tap, or from a
        // background spell, any of which may have left a sheet stranded or an
        // un-presented pending item behind. Run the reconciliation body ONCE,
        // IMMEDIATELY (not on the tick's own schedule): pending state is read
        // from the authoritative server maps either way, so this can never
        // duplicate a sheet (the build path is only reachable with zero live
        // sheets) and can never show an empty one (the total == 0 branch tears
        // down instead of building). The tick then continues as the ongoing
        // safety net it already was.
        try { updateIncomingRequests() } catch (e: Exception) { /* same isolation as the tick body */ }
    }

    /** Call from the host Activity's onPause. */
    fun stop() {
        uiHandler.removeCallbacks(tick)
        val s = incomingSheet
        // Clear first: the dismiss listener only records "the person
        // dismissed this on purpose" if incomingSheet is still this sheet.
        incomingSheet = null; incomingSheetList = null; incomingSheetScroll = null; incomingSheetTitle = null
        lastRenderedSignature = null
        lastSeenPendingSignature = null   // GOAT-PASS 13 (§8): dies with the screen, like its siblings
        // FINAL PASS 7 (PART 2): the grace-window map dies with the screen
        // too — whatever entries lived in it belong to the sheet being torn
        // down below, and the next start() rebuilds from empty.
        sheetBornAtMs.clear()
        // dialog.dismiss() directly, not BottomSheet.dismiss(): that one
        // animates first and finishes on an end action, which may never
        // run on a window that's already going away.
        if (s != null) {
            try { s.dialog.dismiss() } catch (e: Exception) { /* window already gone */ }
        }
    }

    // Incoming-request sheet -------------------------------------------------

    private var incomingSheet: BottomSheet? = null
    private val isIncomingSheetShowing: Boolean get() = incomingSheet != null
    private var incomingSheetList: LinearLayout? = null
    private var incomingSheetScroll: android.widget.ScrollView? = null
    private var incomingSheetTitle: TextView? = null

    /** ── GOAT-PASS 13 (§8 — the accidental-reopen root fix) ──────────────────
     *  The pending-set signature as of the LAST poll tick — including the
     *  in-place update branch, which grows/shrinks the sheet's rendered rows
     *  without ever rebuilding the sheet object. The dismiss listener below
     *  records THIS value (not the build-time closure the old code captured)
     *  as the deliberately-dismissed signature, so what it stores always
     *  matches what the very next tick recomputes for unchanged data.
     *
     *  The old build-time capture was the "the sheet opens again immediately
     *  after closing it" bug: an outside-click on a sheet whose content had
     *  grown since it was built (a second request arriving during the first
     *  one's lifetime, a placeholder resolving into a real manifest) stored
     *  the STALE build-time signature; the next tick then saw a "different"
     *  set — a genuinely processed, already-rendered state — and immediately
     *  re-showed the sheet the person had just closed. A genuinely NEW
     *  request still changes the signature beyond this value and reopens the
     *  sheet exactly as before. */
    private var lastSeenPendingSignature: String? = null

    /** Re-opens the incoming sheet after it was minimized (Back, drag-down,
     *  or the floating badge's own tap) — clearing dismissedIncomingSignature
     *  is what actually allows it to reappear: normally, once a given SET
     *  of pending items has been explicitly dismissed, updateIncomingRequests
     *  deliberately won't re-show that exact same sheet on its own, so a
     *  stray poll tick right after a deliberate dismissal can't immediately
     *  reopen it — this is the one path that's SUPPOSED to override that. */
    private fun restoreIncomingSheet() {
        dismissedIncomingSignature = null
        updateIncomingRequests()
    }

    /** ── FINAL PASS 7 (PART 2): the stranded-sheet visual check — true when ──
     *  a showing sheet's entrance never landed (see the stale-sheet guard in
     *  updateIncomingRequests for the full failure trace). All three gates
     *  must hold:
     *  • entranceSettled — the entrance runnable ran at least once; before
     *    that a displaced root is an entrance that simply hasn't started
     *    (the normal first fraction of a second), never a stranded sheet;
     *  • the root is still displaced far beyond any resting state (a
     *    settled sheet sits at translationY == 0; drag gestures are upward
     *    or end in a real dismiss — a sheet that both IS showing AND sits
     *    >100dp down never got its entrance to land);
     *  • grace — the sheet has lived at least 1s (two tick periods; the
     *    entrance is 220ms), so a healthy sheet is never mistaken for a
     *    stranded one, and a stranded one is reconciled ~1s after the
     *    first tick that sees it.
     *  Cheap: two float compares + one clock read, once per tick. */
    private fun isSheetVisuallyStranded(sheet: BottomSheet): Boolean {
        if (!sheet.entranceSettled) return false
        if (sheet.rootTranslationY < activity.dpf(100f)) return false
        return System.currentTimeMillis() - sheetBornAtMs.getOrPut(sheet) { System.currentTimeMillis() } > 1000L
    }

    /** Birth times of live sheets' entrances, keyed by identity — a rebuilt
     *  sheet (after a stranded dismissal) starts its own grace window, and
     *  entries die with their sheet in the dismiss path below. */
    private val sheetBornAtMs = java.util.concurrent.ConcurrentHashMap<BottomSheet, Long>()

    /** GOAT v2 (item 6): populates the row list only — shared between the
     *  initial sheet build and the live "update in place" refresh so the
     *  two can never drift into showing different content for the same
     *  underlying state.
     *
     *  GOAT v3 (§3.1 — scroll preservation): a re-render (only ever runs
     *  now when the pending data genuinely changed — see
     *  updateIncomingRequests) captures each expanded folder box's current
     *  scroll offset from the outgoing rows and re-applies it to the
     *  freshly built ones, keyed by the row key tagged onto each folder
     *  card. Previously a re-render always meant every expanded box snapped
     *  back to its top — which at the old every-500ms cadence was
     *  "scrolling resets while I scroll", the single most literal reading
     *  of "can't scroll properly" this sheet had. */
    private fun renderIncomingRows(list: LinearLayout, pushes: List<LumiDropServer.PendingNativePush>, uploads: List<LumiDropServer.PendingUpload>) {
        // ── Pass-40 (#8): the REBUILD GATE. This sheet's render loop fires
        // every ~500ms, and the old code rebuilt EVERY row on every tick — a
        // large pending folder meant hundreds of view constructions twice a
        // second for content that usually hadn't changed at all (the exact
        // churn pattern TransfersFragment.renderGroupChildren's own signature
        // gate already fixed for its tree). A cheap signature over exactly
        // what the rows RENDER — each push's identity, phase, sender and
        // VISIBLE children (the exclusion-filtered paths, which is also what
        // every accept/decline/exclude decision changes), each upload's
        // identity/size/sender, and the sheet's own expansion state (a
        // subfolder toggle re-renders through this same path with no data
        // change at all) — now gates the rebuild: identical signature, zero
        // work, the existing views (and their scroll offsets, and every
        // captured listener — all id-keyed server calls) stay exactly as
        // they are. Any genuine change alters the signature and the rebuild
        // runs as it always did.
        val renderSig = buildString {
            for (p in pushes) {
                append("p:").append(p.id).append(':').append(p.fromName).append(':').append(p.manifestReady).append('|')
                if (p.manifestReady) {
                    for (top in p.topLevelNames.filter { p.itemDecisions[it] == "pending" }) {
                        append(top)
                        for (e in p.entries) {
                            if (!e.isDir && (e.path == top || e.path.startsWith("$top/")) && !p.isExcludedNested(e.path))
                                append('\n').append(e.path).append(':').append(e.size)
                        }
                        append('|')
                    }
                } else {
                    for (item in p.announcedTopLevel)
                        append(item.name).append(':').append(item.isFolder).append(':').append(item.isText)
                            .append(':').append(p.preDecisions[item.name] ?: "-").append('|')
                }
            }
            for (u in uploads) append("u:").append(u.id).append(':').append(u.name).append(':').append(u.size).append(':').append(u.fromName).append('|')
            append("x:").append(expandedIncomingFolderKey ?: "-")
            for (k in expandedIncomingSubKeys.sorted()) append('|').append(k)
        }
        if (renderSig == lastIncomingRenderSig && list.childCount > 0) return
        lastIncomingRenderSig = renderSig
        val offsets = HashMap<String, Int>()
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            val key = row.tag as? String ?: continue
            val sc = findNestedScrollIn(row) ?: continue
            if (sc.scrollY != 0) offsets[key] = sc.scrollY
        }
        list.removeAllViews()
        for (p in pushes) buildPushItemRows(p).forEach { list.addView(it) }
        for (p in uploads) list.addView(buildIncomingRow(
            icon = FormatIcons.iconFor(p.name.substringAfterLast('.')), iconTint = color(FormatIcons.tintFor(p.name.substringAfterLast('.'))),
            title = p.name,
            subtitle = "${fmtBytes(p.size)}  from ${p.fromName}",
            onAccept = { withEffectFeedback { serverOrToast()?.acceptUpload(p.id) }; updateIncomingRequests() },
            onDecline = { withEffectFeedback { serverOrToast()?.rejectUpload(p.id) }; updateIncomingRequests() },
        ))
        if (offsets.isNotEmpty()) {
            for (i in 0 until list.childCount) {
                val row = list.getChildAt(i)
                val key = row.tag as? String ?: continue
                val target = offsets[key] ?: continue
                // Same key ⇒ same children data ⇒ same content height, so
                // restoring pre-layout is exact (an in-flight change that
                // shrank this folder's tree can only over-scroll by what
                // shrank; the next scroll gesture re-clamps it).
                findNestedScrollIn(row)?.scrollTo(0, target)
            }
        }
    }

    /** First NestedScrollView inside [v]'s subtree — the folder box (the
     *  only scroll container any row in this sheet ever builds). */
    private fun findNestedScrollIn(v: View): NestedScrollView? {
        if (v is NestedScrollView) return v
        if (v !is ViewGroup) return null
        for (i in 0 until v.childCount) {
            findNestedScrollIn(v.getChildAt(i))?.let { return it }
        }
        return null
    }

    /** ── Pass-28 (#3A): true while any clickable control inside the rendered
     *  rows is mid-press (its DOWN was delivered, the UP hasn't arrived yet) —
     *  see updateIncomingRequests' deferred re-render. A plain view-tree walk:
     *  Android keeps a pressed view's isPressed set for the whole gesture, so
     *  one cheap pass decides whether rebuilding the rows right now would
     *  detach the view under someone's finger. */
    private fun anyRowControlPressed(v: View): Boolean {
        if (v.isPressed) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (anyRowControlPressed(v.getChildAt(i))) return true
            }
        }
        return false
    }

    /** GOAT v3 (§3.1): everything the rendered rows actually display, folded
     *  into one cheap comparison string — push identity/manifest state/
     *  announced names/per-item decisions/entry sizes, upload identity/
     *  name/size/sender. Expand state deliberately NOT part of
     *  it: expansion is an in-place view toggle now, and a same-data tick
     *  re-rendering just because a folder was expanded would kill the
     *  toggle's animation and reset scroll for zero informational gain. */
    private fun contentSignature(
        pushes: List<LumiDropServer.PendingNativePush>,
        uploads: List<LumiDropServer.PendingUpload>,
    ): String {
        val sb = StringBuilder()
        for (p in pushes) {
            sb.append('p').append(p.id).append(';')
                .append(p.manifestReady).append(';')
                .append(p.fromName).append(';')
                .append(p.announcedTopLevel.size).append(';')
                .append(p.topLevelNames).append(';')
                .append(p.itemDecisions.entries.sortedBy { it.key }).append(';')
                // ── Pass-15 (decided row that never disappears) ──────────
                // A PLACEHOLDER push's itemDecisions is EMPTY (its rows are
                // decided through preDecisions instead), so a per-item
                // Accept/Decline on a "Preparing…" row changed the header's
                // waiting-count but NOT this signature — the gated re-render
                // never ran and the decided row stayed on screen: the reported
                // "the number of waiting items decreases, but the item itself
                // doesn't disappear". Pre-decisions are part of the signature
                // now, so a decided placeholder row leaves the sheet on the
                // very same tick, exactly like a decided real row always did.
                .append(p.preDecisions.entries.sortedBy { it.key }).append(';')
                .append(p.entries.size).append(';')
                .append(p.entries.sumOf { it.size }).append(';')
                // ── FINAL PASS 1 (§1.2 — the ✕ must leave the screen NOW) ──────
                // The receiver's per-child exclusions. The rows render the
                // exclusion-FILTERED child set (buildPushItemRows filters
                // !isExcludedNested), but this signature never carried the
                // exclusion map — so tapping ✕ inside an expanded incoming
                // folder changed the data the rows show WITHOUT changing the
                // signature, the gated re-render never fired, and the
                // declined children stayed on screen until the sheet was
                // closed and reopened (the exact reported behavior). The
                // sorted, stable fold below changes the signature the moment
                // ANY exclusion is added (or removed), so the very same tick
                // that processed the ✕ re-renders the sheet with the child
                // gone — and the inner renderIncomingRows gate (which
                // already carried the filtered children) stays exactly as
                // it was.
                .append(p.excludedNestedByTopLevel.entries
                    .sortedBy { it.key }
                    .joinToString(";") { (top, set) -> "$top=" + set.toSortedSet().joinToString(",") })
                .append('|')
        }
        for (u in uploads) {
            sb.append('u').append(u.id).append(';').append(u.name).append(';')
                .append(u.size).append(';').append(u.fromName).append('|')
        }
        return sb.toString()
    }

    /** Set by the last actual renderIncomingRows pass (see
     *  updateIncomingRequests) — null whenever the sheet is gone. */
    private var lastRenderedSignature: String? = null

    /** GOAT v2 (item 6): a short list should just wrap naturally — no
     *  empty scrollable space below two rows — while a long one needs a
     *  real cap so it scrolls instead of pushing the fixed Accept All /
     *  Decline All footer (item 7) off the bottom of the screen, or past
     *  it entirely on a small phone. Measuring [content] with an
     *  UNSPECIFIED height spec is the same technique the layout system
     *  itself uses internally to resolve wrap_content — correct
     *  regardless of whatever height happens to be applied to [scroll] or
     *  its ancestors going in, since it's measuring the row list on its
     *  own terms first. Re-run on every refresh, not just the initial
     *  build: the right answer (wrap vs. capped-and-scrolling) can change
     *  as items are accepted/declined out of the list.
     *
     *  Pass-10 (modal fill-screen root fix): the width spec is now a few
     *  dp NARROWER than the screen (the sheet's own side padding — plus a
     *  safety margin so a wrapping-sensitive row errs TALLER, i.e. toward
     *  capping, never toward silently under-measuring past the cap), and
     *  this is re-derived on EVERY 500ms tick and on every folder expand /
     *  collapse — content height changes without the data signature
     *  changing (an expanded folder box adds up to 280dp of height the
     *  signature deliberately ignores), and the old signature-gated recap
     *  missed exactly those: an uncapped wrap_content ScrollView then grew
     *  the whole sheet to the full screen and pushed Accept All / Decline
     *  All off the bottom — the reported "after about ten items the modal
     *  fills the entire screen; removing one item returns it to normal".
     *  The measure itself never touches input state or scroll position;
     *  a layout param is written ONLY when the target actually changed. */
    private fun capScrollHeight(scroll: android.widget.ScrollView, content: View) {
        val measureWidth = (activity.resources.displayMetrics.widthPixels - dp(36)).coerceAtLeast(dp(120))
        content.measure(
            View.MeasureSpec.makeMeasureSpec(measureWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.UNSPECIFIED,
        )
        val maxH = (activity.resources.displayMetrics.heightPixels * 0.48f).toInt()
        val targetH = if (content.measuredHeight > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT
        val lp = scroll.layoutParams
        if (lp != null && lp.height != targetH) {
            lp.height = targetH
            scroll.layoutParams = lp
        }
    }

    /** Pass-10: re-derives the incoming list's height cap against the views
     *  this sheet is actually holding right now — invoked from the 500ms
     *  tick, from every folder-row expand/collapse, and once post-layout
     *  after the sheet is built, so no path can grow the sheet past its
     *  cap without the very next beat correcting it. */
    private fun recapIncomingSheet() {
        val sc = incomingSheetScroll ?: return
        val list = incomingSheetList ?: return
        capScrollHeight(sc, list)
    }

    /** True while [sheet] is still THE incoming sheet being shown — a
     *  deferred re-cap (root.post) must never act on a sheet that was
     *  dismissed and replaced between scheduling and running. */
    private fun _sheetStillCurrent(sheet: BottomSheet?): Boolean =
        sheet != null && incomingSheet === sheet && sheet.dialog.isShowing

    // GOAT v2 (item 8): used to check "if (_binding == null) return" first
    // (a Fragment-view-not-ready guard) -- meaningless here, this Activity
    // has no such window, so simply dropped rather than translated into
    // something that doesn't apply. The !isRunning branch below is carried
    // over from SendFragment.updateUI()'s own early-return for exactly
    // this state (that Fragment no longer touches any of it at all) --
    // wrapping the whole body in try/finally guarantees
    // updateIncomingFloatBtn stays in sync on every exit path below
    // without needing a separate call at each individual return.
    private fun updateIncomingRequests() {
        try {
            if (!ServerForegroundService.isRunning) {
                incomingSheet?.dismiss()
                incomingSheet = null; incomingSheetList = null; incomingSheetScroll = null; incomingSheetTitle = null
                dismissedIncomingSignature = null
                lastRenderedSignature = null
                lastSeenPendingSignature = null
                return
            }
            // ── Pass-15 (single-modal guarantee) ──────────────────────────
            // Files arriving one by one must always land in ONE sheet. Any
            // sheet reference whose dialog is no longer showing (mid-teardown
            // between BottomSheet's exit animation and its dismiss listener)
            // is cleared the moment it is noticed — otherwise the in-place
            // update branch below would skip, the builder below would mint a
            // SECOND sheet on top of the half-gone first one, and a rapid
            // stream of arrivals could stack multiple modals. With this
            // guard the builder is only ever reachable with zero live sheets,
            // so duplicates are impossible by construction.
            //
            // ── FINAL PASS 7 (PART 2 — the notification-without-sheet race's ──
            // ── presentation half): a sheet can be "showing" and still be ─────
            // INVISIBLE. BottomSheet.show() runs its slide-up entrance inside
            // root.post{} — root.translationY starts at 400dp (fully off the
            // bottom) and only reaches 0 when that posted runnable's
            // animate() completes. A window-focus transition in that window
            // (first launch returning from the ALL-FILES permission settings
            // screen, an app switch, an OEM launcher relaunch) can cancel the
            // posted runnable or its animation: the dialog's window stays
            // attached (isShowing == true) while its content sits 400dp below
            // the screen and the dim scrim never faded in. Every later tick
            // then takes the in-place update branch — which only checks
            // isShowing — and NEVER re-presents: the system notification
            // (posted independently by the service's HTTP thread) fired, the
            // sheet never became visible, and the sender's row sat Waiting.
            // THE FIX: the stale-sheet guard now also reconciles VISUAL
            // state — a sheet whose root is still displaced (beyond a slack
            // that no healthy entrance or drag gesture can occupy at rest,
            // and only after the entrance window has long closed) is treated
            // as stranded: dismissed, state cleared, and the normal build
            // path re-presents it on THIS SAME TICK. The 220ms entrance +
            // slack fits inside one 500ms tick, so a healthy entrance is
            // never mistaken for a stranded one; a real one-time miss costs
            // at most one tick. The signature gates (dismissedIncoming/
            // lastRendered) are reset with the rest, so the rebuild is
            // exactly the normal first-build path — idempotent by the same
            // construction as every other build.
            val staleSheet = incomingSheet
            if (staleSheet != null && (!staleSheet.dialog.isShowing || isSheetVisuallyStranded(staleSheet))) {
                try { staleSheet.dialog.dismiss() } catch (e: Exception) { /* window already gone */ }
                incomingSheet = null; incomingSheetList = null; incomingSheetScroll = null; incomingSheetTitle = null
                lastRenderedSignature = null
                dismissedIncomingSignature = null   // a stranded sheet was never a deliberate dismissal
            }
            val uploads = server?.pendingUploads?.values?.filter { it.status == "pending" }?.sortedBy { it.createdAt } ?: emptyList()
            val pushes = server?.pendingNativePushList() ?: emptyList()
            // GOAT v2 (item 2): "waiting" now counts individual top-level items,
            // not raw push objects — a single 3-file push renders as 3 rows
            // below (buildPushItemRows), so the header number needs to agree
            // with what's actually on screen instead of undercounting it.
            //
            // GOAT (Part 1 correction #4): a push still in its fast "announce"
            // phase (manifestReady == false — see LumiDropServer.
            // apiNativePushAnnounce) has no real entries yet, so topLevelNames
            // is empty and would otherwise silently count as zero here —
            // exactly the gap that would make an announced-but-not-yet-
            // detailed transfer invisible, defeating the entire point of
            // announcing before the manifest is ready. Counts its
            // announcedTopLevel instead for that case.
            //
            // Pass-14 (stuck "Preparing…" fix): placeholder items the host
            // already pre-decided are no longer waiting on anyone here —
            // they don't count and don't render, exactly like a decided
            // real item.
            val pushItemCount = pushes.sumOf { p ->
                if (!p.manifestReady) p.announcedTopLevel.count { p.preDecisions[it.name] == null }
                else p.topLevelNames.count { p.itemDecisions[it] == "pending" }
            }
            val total = uploads.size + pushItemCount

            if (total == 0) {
                incomingSheet?.dismiss()
                incomingSheet = null; incomingSheetList = null; incomingSheetScroll = null; incomingSheetTitle = null
                dismissedIncomingSignature = null
                lastRenderedSignature = null
                lastSeenPendingSignature = null
                expandedIncomingFolderKey = null
                expandedIncomingBox = null
                expandedIncomingChevron = null
                return
            }

            // Pass-14: the signature also carries each placeholder's
            // pre-decisions — deciding an announced item must re-render the
            // sheet the very same tick (the decided row disappears), and the
            // push-id set alone would never change for that.
            val signature = (uploads.map { "u${it.id}" } + pushes.map { p ->
                if (!p.manifestReady) "p${p.id}:" + p.announcedTopLevel.map { p.preDecisions[it.name] ?: "?" }.joinToString(".")
                else "p${p.id}"
            }).sorted().joinToString(",")

            val list = incomingSheetList
            val scroll = incomingSheetScroll
            // GOAT-PASS 13 (§8): THIS tick's view of the pending set — recorded
            // BEFORE any branch can return, so the dismiss listener always
            // stores the CURRENT set (the in-place update path grows the
            // rendered rows without rebuilding the sheet object, and the
            // build-time-only capture was exactly the stale value that made a
            // closed sheet reopen itself on the very next tick).
            lastSeenPendingSignature = signature
            if (list != null && scroll != null && incomingSheet?.dialog?.isShowing == true) {
                incomingSheetTitle?.text = "$total waiting"
                // GOAT v3 (§3.1 — this sheet's own real "can't scroll
                // properly" root cause): this branch used to tear the whole
                // row list down and rebuild it on EVERY 500ms poll tick,
                // unconditionally — resetting every expanded folder box's
                // scroll to top twice a second, cancelling any in-flight
                // drag inside one mid-gesture (the views under the finger
                // were being detached), and killing any expand animation
                // mid-flight. Gated on a content signature now: identical
                // pending data (the overwhelmingly common steady state —
                // nothing accepted/declined/announced since the last tick)
                // leaves the built views completely untouched; genuinely
                // different data still re-renders on the same frame, with
                // renderIncomingRows preserving scroll offsets when it
                // does. capScrollHeight rides the same gate — it measures
                // the whole row list, so it had the same twice-a-second
                // cost for nothing.
                //
                // Pass-10 (modal fill-screen root fix): …except the HEIGHT
                // CAP is now re-derived every tick regardless of the
                // signature: a folder row expanding (or its children list
                // growing) changes rendered height WITHOUT changing the
                // data signature, and a missed cap on those paths is exactly
                // what let the sheet grow to the full screen with the
                // footer buttons pushed off. The re-cap only writes a layout
                // param when the wrap/capped answer actually flips, so the
                // steady state remains a single cheap measure pass.
                val renderSignature = contentSignature(pushes, uploads)
                if (renderSignature != lastRenderedSignature) {
                    if (anyRowControlPressed(list)) {
                        // ── Pass-28 (#3A): a re-render rebuilds every row view ──
                        // (renderIncomingRows' removeAllViews), and a row that is
                        // detached mid-gesture swallows its own in-flight tap —
                        // the "clicking the copy/accept icon sometimes does
                        // nothing" race, hit exactly when the pending data
                        // changed (typically a placeholder's manifest landing)
                        // between the finger's DOWN and its UP. Defer the
                        // rebuild to the next tick instead: the press ends
                        // within moments, the decision the tap was making
                        // lands, and the next tick re-renders the genuinely
                        // changed data. lastRenderedSignature is deliberately
                        // NOT updated on this path — the deferred render must
                        // still see the mismatch.
                    } else {
                        lastRenderedSignature = renderSignature
                        renderIncomingRows(list, pushes, uploads)
                        capScrollHeight(scroll, list)
                    }
                } else {
                    capScrollHeight(scroll, list)
                }
                return
            }

            // Already showing this exact set and the person swiped it away on
            // purpose — leave it dismissed until the set changes.
            if (signature == dismissedIncomingSignature) return

            incomingSheet = BottomSheet(activity).also { sheet ->
                // FINAL PASS 7 (PART 2): the sheet's grace window starts at
                // construction — the stranded check reads this to give a
                // healthy 220ms entrance its 1s of slack before a displaced
                // root can ever count as stranded.
                sheetBornAtMs[sheet] = System.currentTimeMillis()
                sheet.show { s ->
                    val ctx = activity
                    val root = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(16), dp(4), dp(16), dp(14))
                    }
                    incomingSheetTitle = TextView(ctx).apply {
                        text = "$total waiting"
                        textSize = 15f; setTextColor(color(R.color.text_primary))
                        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                            .also { it.bottomMargin = dp(10) }
                    }
                    root.addView(incomingSheetTitle)

                    // GOAT v2 (item 6): everything that scrolls lives in here,
                    // and ONLY in here — Accept All/Decline All below are a
                    // sibling of this ScrollView, not inside it, so they're
                    // never something the list can carry off past the bottom
                    // of the screen as more items arrive.
                    val listContainer = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
                    incomingSheetList = listContainer
                    renderIncomingRows(listContainer, pushes, uploads)
                    val scrollView = android.widget.ScrollView(ctx).apply {
                        isFillViewport = false
                        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
                        addView(listContainer)
                    }
                    incomingSheetScroll = scrollView
                    capScrollHeight(scrollView, listContainer)
                    root.addView(scrollView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    // Pass-10 (modal fill-screen root fix): one post-layout
                    // re-derivation — the measure above ran before the sheet
                    // was attached; once real layout has happened, verify the
                    // cap against the actually-laid-out list (and keep
                    // verifying every tick thereafter via updateIncomingRequests).
                    root.post { if (_sheetStillCurrent(sheet)) recapIncomingSheet() }

                    // GOAT v2 (item 7): fixed footer — a hairline separator is
                    // what actually reads as "this area is pinned, not just
                    // more scrolling content" once the list above it is long
                    // enough to scroll; harmless (invisibly thin, no visual
                    // weight) on the common case of a short list that never
                    // scrolls at all.
                    root.addView(View(ctx).apply {
                        setBackgroundColor(color(R.color.border_subtle))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).also {
                            it.topMargin = dp(10); it.bottomMargin = dp(10)
                        }
                    })
                    val footer = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.END
                    }
                    footer.addView(TextView(ctx).apply {
                        text = "Decline All"; textSize = 12.5f; setTextColor(color(R.color.red_stop))
                        background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_ghost)
                        setPadding(dp(14), dp(9), dp(14), dp(9))
                        isClickable = true; isFocusable = true
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginEnd = dp(8) }
                        setOnClickListener { server?.rejectAllUploads(); server?.rejectAllNativePush(); updateIncomingRequests() }
                    })
                    footer.addView(TextView(ctx).apply {
                        text = "Accept All"; textSize = 12.5f; setTextColor(color(R.color.white))
                        background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_primary)
                        setPadding(dp(14), dp(9), dp(14), dp(9))
                        isClickable = true; isFocusable = true
                        setOnClickListener { server?.acceptAllUploads(); server?.acceptAllNativePush(); updateIncomingRequests() }
                    })
                    root.addView(footer)
                    root
                }
                sheet.dialog.setOnDismissListener {
                    // FINAL PASS 7 (PART 2): the sheet's grace-window entry
                    // dies with the sheet, whichever dismiss path ran.
                    sheetBornAtMs.remove(sheet)
                    if (incomingSheet === sheet) {
                        // ── GOAT-PASS 13 (§8): record the LAST-SEEN pending ──
                        // set (the value the very next tick recomputes for
                        // unchanged data), NOT the build-time closure — the
                        // stale build-time capture was what made a sheet whose
                        // content had grown in place reopen immediately after
                        // an outside-click close. Null only in the impossible
                        // no-tick window, where the closure value is the best
                        // available answer.
                        dismissedIncomingSignature = lastSeenPendingSignature ?: signature
                        incomingSheet = null; incomingSheetList = null; incomingSheetScroll = null; incomingSheetTitle = null
                        lastRenderedSignature = null
                    }
                }
            }
            // The sheet-build path above already rendered this exact set
            // (renderIncomingRows ran inside sheet.show) — record it so the
            // very next 500ms tick doesn't pointlessly rebuild identical
            // rows (which would reset scroll and interrupt any touch in
            // progress, the exact thing the signature gate is for).
            lastRenderedSignature = contentSignature(pushes, uploads)
        } finally {
            updateIncomingFloatBtn()
        }
    }

    private fun buildIncomingRow(
        icon: Int, iconTint: Int, title: String, subtitle: String,
        onAccept: () -> Unit, onDecline: () -> Unit,
    ): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = ContextCompat.getDrawable(activity, R.drawable.bg_btn_secondary)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(6) }
        }
        row.addView(ImageView(activity).apply {
            setImageDrawable(Widgets.icon(activity, icon, iconTint))
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { it.marginEnd = dp(8) }
        })
        val col = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        col.addView(TextView(activity).apply { text = title; textSize = 12.5f; setTextColor(color(R.color.text_primary)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        col.addView(TextView(activity).apply { text = subtitle; textSize = 10.5f; setTextColor(color(R.color.text_muted)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        row.addView(col)
        row.addView(Widgets.iconButton(activity, R.drawable.ic_check, color(R.color.green), sizeDp = 32, iconSizeDp = 16, contentDescription = "Accept").also {
            it.setOnClickListener { onAccept() }
        })
        row.addView(Widgets.iconButton(activity, R.drawable.ic_close, color(R.color.red_stop), sizeDp = 32, iconSizeDp = 16, contentDescription = "Decline").also {
            it.setOnClickListener { onDecline() }
        })
        return row
    }

    // GOAT v2 (item 2 + item 4): a native push used to render as ONE row —
    // "N files — Accept/Decline" — no matter how many unrelated files or
    // folders the sender had actually selected, which is exactly the
    // combined-transfer-object pattern the spec calls out by name. Every
    // top-level entry the sender selected (File A, File B, a whole Vacation
    // folder — each one is its own top-level name; see PendingNativePush)
    // gets its own row here instead, wired to LumiDropServer's per-item
    // acceptNativePushItem/declineNativePushItem rather than the whole-push
    // versions — accepting or declining one has zero effect on the others,
    // and the ones still pending after this push shrinks stay put instead
    // of the whole thing disappearing. A loose file renders through the
    // existing buildIncomingRow (unchanged); a folder gets its own
    // dedicated, expandable card below rather than being squeezed into the
    // same plain-file row shape.
    private fun buildPushItemRows(push: LumiDropServer.PendingNativePush): List<View> {
        // ── Pass-14 (stuck "Preparing…" root fix): placeholder rows are now ───
        // ACTIONABLE. The old shape rendered announce-phase items (manifest-
        // Ready == false) as inert "Preparing… from X" rows with a spinner
        // and NO controls — correct for the sub-second manifest handoff it
        // was designed for, but a sender that died (or lost its manifest
        // POST) between announce and request left those rows stuck that way
        // for the full push TTL with nothing the host could do. The same
        // per-item Accept/Decline controls the real rows use now appear the
        // moment the row does: LumiDropServer records the decision as a
        // pre-decision and applies it the instant the manifest lands (or the
        // placeholder TTL sweep resolves a sender that never comes). Decided
        // placeholder rows disappear from the sheet exactly like decided
        // real ones (the pendingNativePushList filter + signature above).
        if (!push.manifestReady) {
            return push.announcedTopLevel
                .filter { push.preDecisions[it.name] == null }
                .map { item ->
                    // ── Pass-16 (a text NEVER renders as a file container) ──
                    // The announce already carries the message content, so
                    // even this sub-second placeholder phase shows the TRUE
                    // text card — snippet, character count, Copy/Cancel —
                    // instead of the generic file row the reported "second
                    // identical text arrives looking like a file item" was.
                    // Accept on a placeholder records the pre-decision the
                    // manifest applies the moment it lands (and precreate's
                    // clip+dialog fulfill the copy); decline removes it.
                    if (item.isText) {
                        buildIncomingTextRow(
                            msgText = item.text ?: "",
                            fromName = push.fromName,
                            preparingHint = true,
                            onAccept = { withEffectFeedback { serverOrToast()?.acceptNativePushItem(push.id, item.name) }; updateIncomingRequests() },
                            onDecline = { withEffectFeedback { serverOrToast()?.declineNativePushItem(push.id, item.name) }; updateIncomingRequests() },
                        )
                    } else {
                        buildIncomingRow(
                            icon = if (item.isFolder) R.drawable.ic_folder
                                   else FormatIcons.iconFor(item.name.substringAfterLast('.')),
                            iconTint = if (item.isFolder) color(R.color.accent_light)
                                   else color(FormatIcons.tintFor(item.name.substringAfterLast('.'))),
                            title = item.name,
                            subtitle = "Preparing\u2026 from ${push.fromName}",
                            onAccept = { withEffectFeedback { serverOrToast()?.acceptNativePushItem(push.id, item.name) }; updateIncomingRequests() },
                            onDecline = { withEffectFeedback { serverOrToast()?.declineNativePushItem(push.id, item.name) }; updateIncomingRequests() },
                        )
                    }
                }
        }
        val pending = push.topLevelNames.filter { push.itemDecisions[it] == "pending" }
        return pending.map { top ->
            val isFolder = push.entries.any { it.path == top && it.isDir }
            val isText = push.entries.any { it.path == top && it.isText }
            val fileEntries = push.entries.filter {
                !it.isDir && (it.path == top || it.path.startsWith("$top/")) && !push.isExcludedNested(it.path)
            }
            val totalBytes = fileEntries.sumOf { it.size }
            if (isText) {
                // Phase 6 (§6.6): a text message renders as WHAT IT IS — a
                // Copy/Cancel row with a character count and a one-line
                // snippet of the real content — never as a byte-sized
                // "file" row for content that will never exist on disk.
                // Accept runs the exact same session semantics as any
                // other item (per-item acceptNativePushItem → clipboard +
                // History card + quiet Toast, with no follow-up dialog —
                // Pass-28 #5); decline is the item's Cancel/remove.
                buildIncomingTextRow(
                    msgText = push.entries.first { it.path == top && it.isText }.text ?: "",
                    fromName = push.fromName,
                    onAccept = { withEffectFeedback { serverOrToast()?.acceptNativePushItem(push.id, top) }; updateIncomingRequests() },
                    onDecline = { withEffectFeedback { serverOrToast()?.declineNativePushItem(push.id, top) }; updateIncomingRequests() },
                )
            } else if (isFolder) {
                buildIncomingFolderRow(
                    key = "${push.id}::$top",
                    name = top,
                    fileCount = fileEntries.size,
                    totalBytes = totalBytes,
                    fromName = push.fromName,
                    // Pass-11 (queue-ordering fix): folder children render in the
                    // sender's MANIFEST order (= selection order = the order the
                    // engine will actually transfer them) — push.entries is
                    // manifest-ordered, so the .sorted() call that used to
                    // alphabetize the preview is simply gone. The Incoming Files
                    // modal, the Active tab, and the transfer queue now all show
                    // the same sequence.
                    // ── GOAT-PASS 14 (Phase 1, §5): children now carry their SIZES ──
                    // too, so the expanded rows can present exactly the Queue
                    // section's "name · size" item shape (see
                    // renderIncomingChildLevel) instead of a bare filename.
                    children = fileEntries.map { it.path.removePrefix("$top/") to it.size },
                    onAccept = { withEffectFeedback { serverOrToast()?.acceptNativePushItem(push.id, top) }; updateIncomingRequests() },
                    onDecline = { withEffectFeedback { serverOrToast()?.declineNativePushItem(push.id, top) }; updateIncomingRequests() },
                    onExcludeChild = { childPath -> server?.excludeNativePushNestedPath(push.id, top, "$top/$childPath"); updateIncomingRequests() },
                )
            } else {
                buildIncomingRow(
                    icon = FormatIcons.iconFor(top.substringAfterLast('.')), iconTint = color(FormatIcons.tintFor(top.substringAfterLast('.'))),
                    title = top,
                    // ── GOAT-PASS 14 (Phase 1, §1.6): an entry whose size was ──
                    // never declared (the streamed zip-batch archive: size=-1)
                    // shows no byte figure at all rather than a nonsensical
                    // "-1 B" — the archive's own length is genuinely unknown
                    // until it has been streamed.
                    subtitle = (if (totalBytes > 0) "${fmtBytes(totalBytes)}  " else "") + "from ${push.fromName}",
                    onAccept = { withEffectFeedback { serverOrToast()?.acceptNativePushItem(push.id, top) }; updateIncomingRequests() },
                    onDecline = { withEffectFeedback { serverOrToast()?.declineNativePushItem(push.id, top) }; updateIncomingRequests() },
                )
            }
        }
    }

    // (Pass-14: buildIncomingPreparingRow — the inert "Preparing…" placeholder
    //  row with no controls — is retired with the actionable-placeholder fix
    //  in buildPushItemRows above; the row builder was rebuilt around
    //  buildIncomingRow so every incoming item, announced or manifest-ready,
    //  carries the same Accept/Decline affordance.)

    /** Phase 6 (§6.6): the incoming text-message row — Copy (accept) and
     *  Cancel (decline) actions over a message icon, character count, and a
     *  one-line snippet of the actual content. Content stays a message on
     *  both ends: accepting copies it to the receiver's clipboard (the
     *  server-side exactly-once delivery in LumiDropServer — immediately from
     *  the announce for a "Preparing…" row, Pass-28 #3A) and quietly Toasts;
     *  declining is the item's Cancel/remove. The row renders identically
     *  for native and web senders — both enqueue into the same pending-push
     *  machinery (Pass-28 #3C). */
    private fun buildIncomingTextRow(
        msgText: String, fromName: String,
        // ── Pass-16: true while the manifest is still on its way — the row ──
        // keeps the true text design (message icon, snippet, Copy/Cancel)
        // and only its second line reads "Preparing… from X" so the user
        // knows what is coming. (Pass-28 #3A: the COPY no longer waits for
        // the manifest — it fires immediately from the announce's content —
        // so the hint is informational only.)
        preparingHint: Boolean = false,
        onAccept: () -> Unit, onDecline: () -> Unit,
    ): View {
        val ctx = activity
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_secondary)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(6) }
        }
        row.addView(ImageView(ctx).apply {
            setImageDrawable(Widgets.icon(ctx, R.drawable.ic_copy, color(R.color.accent_light)))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_accent_badge)
            setPadding(dp(4), dp(4), dp(4), dp(4))
            layoutParams = LinearLayout.LayoutParams(dp(26), dp(26)).also { it.marginEnd = dp(10) }
        })
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        col.addView(TextView(ctx).apply {
            text = "Text message"; textSize = 12.5f
            setTextColor(color(R.color.text_primary)); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        col.addView(TextView(ctx).apply {
            text = if (preparingHint) "Preparing\u2026 from $fromName"
                   else "${msgText.length} characters · from $fromName"
            textSize = 10.5f
            setTextColor(color(R.color.text_muted)); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        // One-line snippet of the REAL content — what a text message is
        // actually about, which a byte count could never say. Newlines fold
        // to spaces for the single-line preview only; the full text rides
        // the dialog, and the copy operation always copies it verbatim.
        val snippetRaw = msgText.replace("\r", "").replace('\n', ' ').trim()
        val snippet = if (snippetRaw.length > 64) snippetRaw.substring(0, 64) + "\u2026" else snippetRaw
        if (snippet.isNotEmpty()) col.addView(TextView(ctx).apply {
            text = snippet; textSize = 10.5f
            setTextColor(color(R.color.text_secondary)); maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        row.addView(col)
        row.addView(Widgets.iconButton(ctx, R.drawable.ic_copy, color(R.color.green), sizeDp = 32, iconSizeDp = 16, contentDescription = "Copy text message").also {
            it.setOnClickListener { onAccept() }
        })
        row.addView(Widgets.iconButton(ctx, R.drawable.ic_close, color(R.color.red_stop), sizeDp = 32, iconSizeDp = 16, contentDescription = "Cancel text message").also {
            it.setOnClickListener { onDecline() }
        })
        return row
    }

    // ── Pass-28 (#5): the host-side text-message DIALOG is retired ───────────
    // An accepted text's only effects are the clipboard copy plus the quiet
    // confirmation Toast — both written by the server AT ACCEPTANCE (see
    // LumiDropServer.deliverTextToClipboard and precreateNativePushDirs; the
    // old PendingTextMessage queue that fed a Copy/Remove dialog is gone).
    // Nothing may interrupt the receiver's current activity; the durable
    // History message card still records every accepted exchange, and the
    // Incoming Files sheet's own Copy/Cancel row still governs not-yet-
    // accepted text — from native AND web senders alike (Pass-28 #3C:
    // apiTextSend enqueues browser text through this same pending-push
    // machinery, so it renders through these very rows instead of being
    // auto-copied on arrival).

    /** GOAT v2 (item 4): a folder's dedicated card — visually distinct from
     *  a plain file row (folder icon, its own background, an expand
     *  chevron) rather than reusing buildIncomingRow with a different icon.
     *  Tapping the row (anywhere except the two action buttons) expands it
     *  in place to show what's actually inside, so "inspect the contents"
     *  doesn't require a separate screen; Accept/Decline apply to the whole
     *  folder either way, expanded or not, matching the spec's "entire
     *  folder can be accepted/declined directly." Per-child removal from
     *  inside an otherwise-accepted folder is intentionally not part of
     *  this pass — everything here is about top-level items being
     *  independent of each other, not about editing what's inside one. */
    private fun buildIncomingFolderRow(
        key: String, name: String, fileCount: Int, totalBytes: Long, fromName: String,
        // ── GOAT-PASS 14 (Phase 1, §5): (relPath, size) pairs — the size rides ──
        // the tree so every file row can render the Queue section's own
        // "name · size" item shape (QueueAdapter.renderChildLevel), not a
        // bare filename.
        children: List<Pair<String, Long>>,
        onAccept: () -> Unit, onDecline: () -> Unit, onExcludeChild: (String) -> Unit,
    ): View {
        val ctx = activity
        // bg_btn_secondary is itself a RippleDrawable (fill + border +
        // press ripple, see its XML) — putting it on container rather than
        // header, and making container the clickable target below, means
        // the same ripple this app already uses for every other row-like
        // control fires correctly from a tap anywhere on the card (the two
        // icon buttons still intercept their own taps for Accept/Decline —
        // Widgets.iconButton() makes each one its own clickable View with
        // its own background, so those never reach container's listener).
        //
        // GOAT v3 (§3.1): tag = key so renderIncomingRows can capture/restore
        // each expanded box's scroll offset across the re-renders that still
        // happen on real data changes.
        val container = LinearLayout(ctx).apply {
            tag = key
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_secondary)
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(6) }
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(8))
        }
        header.addView(ImageView(ctx).apply {
            setImageDrawable(Widgets.icon(ctx, R.drawable.ic_folder, color(R.color.accent_light)))
            layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { it.marginEnd = dp(8) }
        })
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f) }
        col.addView(TextView(ctx).apply { text = name; textSize = 12.5f; setTextColor(color(R.color.text_primary)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
        col.addView(TextView(ctx).apply {
            text = "$fileCount file${if (fileCount != 1) "s" else ""}  .  ${fmtBytes(totalBytes)}  from $fromName"
            textSize = 10.5f; setTextColor(color(R.color.text_muted)); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        header.addView(col)
        val chevron = ImageView(ctx).apply {
            setImageDrawable(Widgets.icon(ctx, R.drawable.ic_chevron_right, color(R.color.text_muted)))
            layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).also { it.marginEnd = dp(4) }
        }
        header.addView(chevron)
        header.addView(Widgets.iconButton(ctx, R.drawable.ic_check, color(R.color.green), sizeDp = 32, iconSizeDp = 16, contentDescription = "Accept folder").also {
            it.setOnClickListener { onAccept() }
        })
        header.addView(Widgets.iconButton(ctx, R.drawable.ic_close, color(R.color.red_stop), sizeDp = 32, iconSizeDp = 16, contentDescription = "Decline folder").also {
            it.setOnClickListener { onDecline() }
        })
        container.addView(header)

        // GOAT v3 (§3.1): the shared FolderBox component (wrap-up-to-280dp
        // height, edge fades, custom scrollbar indicator, upgraded
        // claimVerticalDrags with scroll-limit hand-off, nested-scrolling
        // opt-out) — same construction the Queue and Transfers instances
        // use now, so all three behave and animate identically. The old
        // hand-rolled fixed dp(280) NestedScrollView this replaced was the
        // bare-default "ugly" box the brief called out.
        //
        // ── GOAT-PASS 12 (§5A): fadeBottomCornerRadiusDp matches the folder ──
        // card's own bg_btn_secondary corners (16dp) — the box's bottom sits
        // flush on the card's bottom edge, and the bottom fade's opaque band
        // used to draw as a square-cornered rectangle poking past the card's
        // rounded corners (the reported gradient overlap). The Queue and
        // Received instances keep their exact previous geometry (radius 0).
        //
        // Deliberately does NOT try to preserve scroll position across
        // re-renders via any view-local state — renderIncomingRows owns that
        // now (capture-and-restore keyed off this row's tag), which works
        // with the ~500ms data-change rebuilds instead of fighting them.
        val folderBox = FolderBox(ctx, 28, 2, 10, 8, fadeBottomCornerRadiusDp = 16f)
        val childList = folderBox.content
        // GOAT v4: expand geometry is FolderBox-owned now (accordion: at
        // most ONE incoming folder expanded at a time — see the toggle
        // below); the box applies the persisted state instantly on build,
        // and this row registers itself as the live expanded row so a
        // later expand of a DIFFERENT folder can collapse this one in place.
        val rowExpanded = key == expandedIncomingFolderKey
        folderBox.setExpandedInstant(rowExpanded)
        if (rowExpanded) {
            expandedIncomingBox = folderBox
            expandedIncomingChevron = chevron
        }
        // GOAT (Part 2 completion — folder accept/decline, "individual
        // internal files/folders can be managed"): grouped by subfolder
        // exactly like the Queue's own expanded preview
        // (QueueAdapter.showChildren) — a subfolder removes as one unit via
        // onExcludeChild(subfolderName), a loose file removes individually.
        // ── Pass-39 (#4): SUBFOLDERS ARE NAVIGABLE now, not a dead end — ──
        // each one expands in place (its own subfolders and files beneath,
        // indented, to any depth), with the expansion state in
        // [expandedIncomingSubKeys] so the ~500ms re-renders never snap the
        // tree shut. The header row above keeps its own expand/collapse; a
        // subfolder's chevron is its own back-to-parent control.
        // ── Pass-40 (#6): the compact folder-location breadcrumb rides at
        // the tree's top (only while a nested subfolder is open).
        renderIncomingBreadcrumb(childList, key, name)
        renderIncomingChildLevel(childList, key, children, prefix = "", depth = 0, onExcludeChild = onExcludeChild)
        container.addView(folderBox.root)

        chevron.rotation = if (key == expandedIncomingFolderKey) 90f else 0f
        // (Pass-26 #7: the box's bottom Collapse pill is gone with its
        // onRequestCollapse wiring — the row header's own click below is the
        // one expand/collapse path, reachable at any inner scroll offset.)
        container.setOnClickListener {
            val nowExpanded = expandedIncomingFolderKey != key
            if (nowExpanded) {
                // Accordion: expanding this folder collapses whichever
                // folder was expanded first — in place, animated — so the
                // sheet never stacks two tall boxes.
                val prevBox = expandedIncomingBox
                val prevChevron = expandedIncomingChevron
                expandedIncomingFolderKey = key
                if (prevBox != null && prevBox !== folderBox) {
                    FolderBox.animateChevron(prevChevron, expanded = false)
                    prevBox.collapse()
                }
                expandedIncomingBox = folderBox
                expandedIncomingChevron = chevron
                FolderBox.animateChevron(chevron, expanded = true)
                folderBox.expand()
            } else {
                expandedIncomingFolderKey = null
                expandedIncomingBox = null
                expandedIncomingChevron = null
                FolderBox.animateChevron(chevron, expanded = false)
                folderBox.collapse()
            }
            // Pass-10 (modal fill-screen root fix): an expand/collapse
            // changes the rendered list height WITHOUT touching the data
            // signature the 500ms re-render gate keys on — re-derive the
            // ScrollView cap immediately so the sheet can never outgrow it
            // between ticks (this was the >10-items fill-the-screen path).
            recapIncomingSheet()
        }
        return container
    }

    /** ── Pass-40 (#6): the compact folder-location row for an expanded ──────
     *  incoming folder — the same design the Queue's and the Received tab's
     *  trees carry. Rendered only while a nested subfolder of THIS folder is
     *  open: the pending folder's own name, then every ancestor of the
     *  deepest open branch, with the CURRENT folder accented at the end.
     *  Every PARENT segment is a one-tap "go back up" that folds the levels
     *  below it (depth-gated, so unrelated shallower state is untouched) and
     *  re-renders through the sheet's established updateIncomingRequests
     *  path. ~22dp, only mid-navigation; nothing else about the box changes. */
    private fun renderIncomingBreadcrumb(childList: LinearLayout, key: String, rootName: String) {
        val ctx = activity
        val keyPrefix = "$key::"
        val deepest = expandedIncomingSubKeys
            .filter { it.startsWith(keyPrefix) }
            .map { it.removePrefix(keyPrefix) }
            .maxByOrNull { it.count { c -> c == '/' } } ?: return
        val parts = deepest.split('/')
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.setPadding(dp(2), dp(2), dp(2), dp(4))
        fun collapseToDepth(depth: Int) {
            expandedIncomingSubKeys.removeAll { k ->
                k.startsWith(keyPrefix) && k.removePrefix(keyPrefix).count { c -> c == '/' } + 1 > depth
            }
            updateIncomingRequests()   // the established re-render path — scroll offsets restore via the row tags
        }
        fun seg(text: String, depth: Int, current: Boolean) {
            if (row.childCount > 0) row.addView(TextView(ctx).apply {
                this.text = "/"; textSize = 10.5f; setTextColor(color(R.color.text_muted))
                setPadding(dp(1), 0, dp(1), 0)
            })
            row.addView(TextView(ctx).apply {
                this.text = text; textSize = 10.5f; maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                setTextColor(color(if (current) R.color.accent_light else R.color.text_secondary))
                if (current) typeface = android.graphics.Typeface.DEFAULT_BOLD
                else {
                    isClickable = true; isFocusable = true
                    setOnClickListener { collapseToDepth(depth) }
                }
                setPadding(dp(2), dp(2), dp(2), dp(2))
            })
        }
        seg(rootName, 0, parts.size == 1)
        for (i in parts.indices) seg(parts[i], i + 1, i == parts.lastIndex)
        childList.addView(row)
    }

    /** ── Pass-39 (#4): one recursion level of an incoming folder's tree ──────
     *  (see buildIncomingFolderRow). [prefix] is the relPath of the subfolder
     *  being rendered ("" = the folder's own root); [paths] are the
     *  exclusion-filtered child relPaths still visible. Subfolders expand in
     *  place (chevron + count + whole-subfolder remove), files remove
     *  individually — the same affordances at every depth.
     *  ── GOAT-PASS 12 (§5): the rows now use the Queue section's and the ──
     *  Received tab's EXACT internal-item visual language — carded rows
     *  (bg_btn_secondary), (6+indent)/5/6/5 padding with a 3dp inter-row
     *  gap, 17dp chevron + folder/format glyphs, 12sp labels and a
     *  12dp-per-depth indent — so all three expanded-folder interfaces read
     *  as one system. The Incoming-specific actions (per-child remove) and
     *  the expand/collapse behavior are unchanged.
     *  ── GOAT-PASS 14 (Phase 1, §5 — sizing parity completed): file rows ──
     *  now carry the Queue's own "name  ·  size" label (same
     *  Formatter.formatShortFileSize call QueueAdapter's child rows use)
     *  and every remove ✕ is byte-for-byte the Queue's childRemoveButton
     *  geometry (26dp touch target, 5dp inset, text_secondary glyph) — the
     *  last two places an expanded incoming folder still read smaller/
     *  barer than its Queue twin. */
    private fun renderIncomingChildLevel(childList: LinearLayout, key: String, paths: List<Pair<String, Long>>,
                                         prefix: String, depth: Int, onExcludeChild: (String) -> Unit) {
        val ctx = activity
        val indent = dp(12) * depth.coerceAtMost(4)
        val subfolders = LinkedHashSet<String>()
        val files = mutableListOf<Pair<String, Long>>()
        for ((childPath, childSize) in paths) {
            val rest = if (prefix.isEmpty()) childPath else childPath.removePrefix("$prefix/")
            val slash = rest.indexOf('/')
            if (slash < 0) files.add(childPath to childSize)
            else subfolders.add(if (prefix.isEmpty()) rest.substring(0, slash) else "$prefix/${rest.substring(0, slash)}")
        }
        for (folderPath in subfolders) {
            val name = folderPath.substringAfterLast('/')
            val count = paths.count { it.first.startsWith("$folderPath/") }
            val expandKey = "$key::$folderPath"
            val expanded = expandedIncomingSubKeys.contains(expandKey)
            // GOAT-PASS 12 (§5): the carded subfolder row — byte-for-byte
            // the Queue/Received internal design (same paddings, 17dp
            // glyphs, 12sp label, 3dp gap, ripple surface, 12dp indent).
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6) + indent, dp(5), dp(6), dp(5))
                isClickable = true; isFocusable = true
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_secondary)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.bottomMargin = dp(3) }
            }
            val chevron = ImageView(ctx).apply {
                setImageDrawable(Widgets.icon(ctx, R.drawable.ic_chevron_right, color(R.color.accent_light)))
                layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).also { it.marginEnd = dp(6) }
                rotation = if (expanded) 90f else 0f
            }
            row.addView(chevron)
            row.addView(ImageView(ctx).apply {
                setImageDrawable(Widgets.icon(ctx, R.drawable.ic_folder, color(R.color.text_muted)))
                layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).also { it.marginEnd = dp(8) }
            })
            row.addView(TextView(ctx).apply {
                text = "$name/  ($count file${if (count == 1) "" else "s"})"
                textSize = 12f; setTextColor(color(R.color.text_secondary))
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(incomingChildRemoveButton(ctx) { onExcludeChild(folderPath) })
            row.setOnClickListener {
                if (expandedIncomingSubKeys.contains(expandKey)) expandedIncomingSubKeys.remove(expandKey)
                else expandedIncomingSubKeys.add(expandKey)
                updateIncomingRequests()   // the established re-render path — scroll offsets restore via the row tags
            }
            childList.addView(row)
            if (expanded) {
                renderIncomingChildLevel(childList, key,
                    paths.filter { it.first.startsWith("$folderPath/") },
                    prefix = folderPath, depth = depth + 1, onExcludeChild = onExcludeChild)
            }
        }
        for ((childPath, childSize) in files) {
            val label = childPath.removePrefix(if (prefix.isEmpty()) "" else "$prefix/")
            // GOAT-PASS 12 (§5): the carded file row — the same shared
            // design, with the format-aware glyph (FormatIcons) the Queue
            // and Received rows carry.
            // ── GOAT-PASS 14 (Phase 1, §5): the label now carries the size in ──
            // the Queue's own exact form — "name  ·  <formatted size>" with
            // android.text.format.Formatter.formatShortFileSize, the very
            // call QueueAdapter's child rows use — so an expanded incoming
            // folder's items read EXACTLY like the Queue section's items
            // (same "what am I getting" information, same sizing), not a
            // barer filename-only cousin.
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6) + indent, dp(5), dp(6), dp(5))
                isClickable = true; isFocusable = true
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_secondary)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.bottomMargin = dp(3) }
            }
            row.addView(ImageView(ctx).apply {
                val ext = label.substringAfterLast('.', "")
                setImageDrawable(Widgets.icon(ctx,
                    com.lumidrop.x.ui.FormatIcons.iconFor(ext),
                    color(com.lumidrop.x.ui.FormatIcons.tintFor(ext))))
                layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).also { it.marginEnd = dp(8) }
            })
            row.addView(TextView(ctx).apply {
                text = "$label  ·  ${android.text.format.Formatter.formatShortFileSize(ctx, childSize)}"
                textSize = 12f; setTextColor(color(R.color.text_secondary))
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(incomingChildRemoveButton(ctx) { onExcludeChild(childPath) })
            childList.addView(row)
        }
    }

    /** ── GOAT-PASS 14 (Phase 1, §5): the per-child remove ✕ of an expanded ──
     *  incoming folder — byte-for-byte the Queue section's childRemoveButton
     *  geometry (QueueAdapter): a 26dp touch target with a 5dp inset and the
     *  text_secondary glyph, clickable and focusable in its own right. The
     *  old Widgets.iconButton form rendered a smaller visual ✕ than the
     *  Queue's, the last sizing mismatch between the two interfaces. */
    private fun incomingChildRemoveButton(ctx: android.content.Context, onClick: () -> Unit) = ImageView(ctx).apply {
        setImageDrawable(Widgets.icon(ctx, R.drawable.ic_close, color(R.color.text_secondary)))
        layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
        setPadding(dp(5), dp(5), dp(5), dp(5))
        isClickable = true; isFocusable = true
        contentDescription = "Remove"
        setOnClickListener { onClick() }
    }

    // Duplicated from SendFragment (also used by its Queue/preview card)
    // rather than shared — small, pure, stateless, same pattern already
    // used for e.g. fmtBytes across SendFragment/TransfersFragment/
    // MediaPickerSheet. Kept letter-for-letter identical to SendFragment's
    // own copy so an icon/size reads the same regardless of which screen
    // shows it.

    private fun fmtBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val kb = b / 1024.0; if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0; if (mb < 1024) return "%.1f MB".format(mb)
        return "%.1f GB".format(mb / 1024.0)
    }

    companion object {
        // GOAT v3 (§3.2 — badge geometry): 48dp touch target (the accessibility
        // floor — deliberately NOT shrunk with the visual), 42dp visual orb
        // (down from 52dp), 20dp icon inside it.
        private const val BADGE_TOUCH_DP = 48
        private const val VISUAL_BADGE_DP = 42
        private const val BADGE_ICON_DP = 20

        // GOAT v2 (item 8): shared across every screen's instance — see
        // the class doc for why each of these needs to be.
        //
        // Which pending SET was deliberately dismissed (swipe/back): the
        // sheet only auto-reopens when the set changes, or via restore.
        private var dismissedIncomingSignature: String? = null

        // GOAT (Part 1 correction — folder expand persistence): which
        // incoming folder row is currently expanded, keyed by
        // "${push.id}::$topName". renderIncomingRows fully rebuilds every
        // row from scratch on every ~500ms tick, so a plain local `var
        // expanded` inside the row-builder — what this used to be — got a
        // brand-new, reset-to-false instance every tick: a folder would
        // expand for a moment and snap back closed on the very next
        // refresh. Same root cause, same fix shape, as QueueAdapter's own
        // documented reasoning for keeping expand state off the View.
        // GOAT v4: single key now (accordion — one expanded folder at a
        // time), still companion so it survives the sheet being rebuilt on
        // a different Activity's instance.
        private var expandedIncomingFolderKey: String? = null

        // ── Pass-39 (#4): which NESTED subfolders of an incoming folder are ──
        // expanded, keyed by "<row-key>::<subfolder relPath>" — companion so
        // the tree survives both the ~500ms re-renders and the sheet being
        // rebuilt on a different Activity's instance (the same reasoning as
        // expandedIncomingFolderKey above).
        private val expandedIncomingSubKeys = java.util.HashSet<String>()

        // Where the floating badge was last dragged (translation offsets
        // from its default corner), so it stays put across screens.
        private var badgeTx = 0f
        private var badgeTy = 0f
    }

    // GOAT v4 (accordion): the LIVE box + chevron of the row currently
    // displaying the companion's expandedIncomingFolderKey on THIS
    // instance's sheet — instance state because both views belong to this
    // Activity's dialog. Re-registered by buildIncomingFolderRow on every
    // rebuild; nulled whenever the expanded row collapses or its pending
    // set empties (a stale-but-detached reference is inert: collapsing a
    // detached box is a harmless no-op on views with no parent).
    private var expandedIncomingBox: FolderBox? = null
    private var expandedIncomingChevron: ImageView? = null

    // ── Pass-40 (#8): the renderIncomingRows rebuild gate's last signature —
    // INSTANCE state on purpose: a recreated sheet (new Activity) starts
    // with a null signature and unconditionally rebuilds its first frame,
    // never inheriting a stale gate from a previous instance.
    private var lastIncomingRenderSig: String? = null
}
