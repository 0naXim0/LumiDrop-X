package com.lumidrop.x.settings

import android.media.MediaScannerConnection
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.lumidrop.x.R
import com.lumidrop.x.ui.ReceivingModal
import com.lumidrop.x.ui.applyLdxWindowChrome
import com.lumidrop.x.ui.SaveDock
import com.lumidrop.x.ui.Widgets
import java.io.File

/**
 * Host-side screen for WebUI customization.
 *
 * Lets the Host fully customize what every connecting browser sees and
 * does, independently for Android / Windows / iOS:
 *  - Default file-manager sort field + direction
 *  - Home screen layout (category order + visibility)
 *  - Pinned folders shown on the WebUI home screen
 *
 * Plus global (non-OS-specific) settings: one or more custom Music folders,
 * since those are server-side filesystem locations, not something that
 * differs by which device is browsing, and the Quick Play default player
 * per client platform.
 *
 * All values are persisted via WebUiSettingsManager and served to the WebUI
 * through GET /api/webui-config — picked up automatically on every page
 * load, no client action required. Nothing here changes app behaviour
 * unless the Host actually edits something and taps Save.
 *
 * ── Pass-8 follow-up (full redesign) ─────────────────────────────────────
 * The screen used to be a flat, ungrouped column of section labels and
 * rows — functional, but visually unrelated to the app's main Settings
 * tab. It is now built from the exact same design language SettingsFragment
 * uses (see that class's own doc): Widgets.sectionCard surfaces with
 * Widgets.sectionHeading group headers, 12sp muted labels with (i) info
 * affordances for anything needing more than a line, hairline dividers
 * between controls inside a card, the same chip/toggle/row treatments, and
 * the shared SaveDock (ui/Widgets.kt) as the save/cancel action — one
 * visual system across both settings surfaces.
 *
 * GOAT v2 (item 8 — Universal Receiving Modal): also hosts a
 * [ReceivingModal], same as MainActivity — see that
 * class's own doc comment for why every screen needs to. A host tweaking
 * WebUI settings is a perfectly normal place to be sitting when a file
 * lands from someone else.
 */
class WebUiSettingsActivity : AppCompatActivity() {

    private lateinit var receiving: ReceivingModal

    private var currentOs = "android"
    // In-memory editable state for the OS profile currently on screen.
    // Reloaded from persisted storage every time a tab is selected.
    private var sortField   = "name"
    private var sortAsc     = true
    private var homeOrder: MutableList<String> = WebUiSettingsManager.HOME_SECTIONS.toMutableList()
    private var homeHidden: MutableSet<String> = mutableSetOf()
    private var pinnedFolders: MutableList<WebUiSettingsManager.PinnedFolder> = mutableListOf()

    // Music folders are global (not per-OS-profile) — edited in-memory here,
    // committed on Save, independent of which OS tab is currently selected.
    private var musicFolders: MutableList<String> = mutableListOf()
    private lateinit var musicFoldersContainer: LinearLayout

    // Quick Play default external player, per client platform (global like
    // Music folders — a Windows player default is meaningless for an
    // Android browser, so all three platform rows live in one section).
    // Empty string = OFF (the fresh-install default); committed on Save
    // alongside everything else.
    private var quickPlayDefaults: MutableMap<String, String> = mutableMapOf(
        "android" to "", "windows" to "", "ios" to "")
    private lateinit var quickPlayValues: Map<String, TextView>
    private lateinit var quickPlayContainer: LinearLayout

    private lateinit var profileContainer: LinearLayout
    private lateinit var tabRow: LinearLayout
    // Pass-8 follow-up: the save action is the shared SaveDock — same
    // surface, shadow, gradient primary action and state model as the main
    // Settings tab, plus a proper Discard that reloads from persisted
    // state (the old screen had no discard at all; the only way back was
    // switching tabs).
    private lateinit var saveDock: SaveDock
    private val saveTapLock = java.util.concurrent.locks.ReentrantLock()

    // ── Pass-11 (Required Implementation 5): the SERVER settings that moved ──
    // here from the main Settings tab (HTTP port, password, connection
    // approval). They ride the same SaveDock + snapshot dirty system as the
    // profile data, and a change to any of them fires the server-restart
    // broadcast on Save exactly like SettingsFragment used to.
    private lateinit var serverPortEdit: android.widget.EditText
    private lateinit var pwdOnSwitch: androidx.appcompat.widget.SwitchCompat
    private lateinit var pwdEdit: android.widget.EditText
    /** GOAT-PASS 12 (§8): the password field + eye toggle's wrapper — the
     *  switch and Discard drive THIS container's visibility (the field
     *  always fills it), and the reveal state resets (re-masks) whenever
     *  the screen reloads its fields. */
    private lateinit var pwdFieldWrap: android.widget.FrameLayout
    private var pwdRevealed = false
    private var pwdRevealIcon: android.widget.ImageView? = null

    /** GOAT-PASS 12 (§8): presentation-only reveal/mask — swaps the input
     *  variation (never the stored value), mirrors the icon, and keeps the
     *  caret at the end. */
    private fun applyPwdReveal(reveal: Boolean) {
        pwdRevealed = reveal
        val masked = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        val shown = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        pwdEdit.inputType = if (reveal) shown else masked
        pwdRevealIcon?.setImageDrawable(com.lumidrop.x.ui.Widgets.icon(this,
            if (reveal) R.drawable.ic_eye_off else R.drawable.ic_eye, col(R.color.text_muted)))
        pwdRevealIcon?.contentDescription = if (reveal) "Hide password" else "Show password"
        pwdEdit.setSelection(pwdEdit.text?.length ?: 0)
    }
    private lateinit var confirmConnSwitch: androidx.appcompat.widget.SwitchCompat
    private fun serverPortValue(): Int = serverPortEdit.text.toString().toIntOrNull() ?: -1

    // Structural dirty-source tracking: a snapshot of everything Save
    // actually persists, taken the moment a tab's data loads (selectOs) or
    // a save/discard completes, compared structurally against the live
    // values on every edit — the same technique SettingsFragment's own
    // computeChanges() uses.
    // Pass-11 (Required Implementation 5): the SERVER trio (HTTP port,
    // password enable+value, connection approval) moved here from the main
    // Settings tab — they dirty the SAME snapshot so one Save commits them
    // alongside the profile, and the server restart broadcast fires when
    // any of them changed.
    private data class ProfileSnapshot(
        val sortField: String, val sortAsc: Boolean,
        val homeOrder: List<String>, val homeHidden: Set<String>,
        val pinnedFolders: List<WebUiSettingsManager.PinnedFolder>,
        val musicFolders: List<String>,
        val quickPlayDefaults: Map<String, String>,
        val serverPort: Int, val pwdOn: Boolean, val pwd: String, val confirmConn: Boolean,
    )
    private fun currentSnapshot() = ProfileSnapshot(
        sortField, sortAsc,
        homeOrder.toList(), homeHidden.toSet(), pinnedFolders.toList(), musicFolders.toList(),
        quickPlayDefaults.toMap(),
        serverPortValue(), pwdOnSwitch.isChecked, pwdEdit.text.toString(), confirmConnSwitch.isChecked,
    )
    private var originalSnapshot: ProfileSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // ── Pass-27 (#4.2/#4.3): this sub-settings screen is its own WINDOW — ───
        // applyLdxWindowChrome gives it the app's bg_deep bar colors (never the
        // platform black) AND, while the "Hide Navigation Bar" preference is
        // enabled, keeps the Android nav buttons hidden here too, so the setting
        // holds across every application-owned screen instead of only the three
        // main tabs. Re-asserted on every resume and window-focus regain below —
        // a focus steal (notification shade, system dialog) can otherwise leave
        // this window's bars restored.
        applyLdxWindowChrome()

        // ── FINAL PASS 1 (§5 — Google Password Manager, the WINDOW-level half) ──
        // The GOAT-PASS 14 field-level fix (importantForAutofill=NO on the
        // one password EditText) was still not enough in the field: Android's
        // autofill SAVE flow snapshots the whole window when the activity is
        // left, and some OEM/Play-Services builds compose that save request
        // even when the only password-type view in it was excluded at the
        // view level — the prompt still appeared "after saving the password
        // settings or when leaving the settings screen". The narrowest
        // supported lever above the view level is applied HERE: this ONE
        // screen's decor view is marked importantForAutofill =
        // NO_EXCLUDE_DESCENDANTS, which excludes the entire view tree of THIS
        // activity's window from both fill and save autofill sessions. The
        // scope is exactly this settings screen — every other activity in the
        // app, every other input in the OS, and the user's global autofill
        // setting are untouched (this screen's only credential-like field is
        // LumiDrop X's own app-access gate password; there is nothing on it
        // that is legitimately save-worthy as a web/account credential). The
        // field-level NO stays as belt-and-braces. Honest limit, stated in
        // the report: an OEM keyboard shipping its OWN password manager
        // (e.g. Samsung Pass) can still offer its own prompt — no app API
        // can control those.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                window?.decorView?.importantForAutofill =
                    android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            } catch (e: Exception) { /* no window yet — the field-level NO still applies */ }
        }

        musicFolders = WebUiSettingsManager.getMusicFolderPaths(this).toMutableList()
        for (os in WebUiSettingsManager.VALID_OS) {
            quickPlayDefaults[os] = WebUiSettingsManager.getQuickPlayDefault(this, os)
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(col(R.color.bg_deep))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // Same outer padding/spacing rhythm SettingsFragment uses:
            // 20dp sides, cards separated by 16dp, bottom clearance for
            // the SaveDock (below) plus breathing room.
            setPadding(dp(20), dp(18), dp(20), dp(140))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            clipChildren = false
            clipToPadding = false
        }

        // ── Header: back affordance + icon + title, one quiet row ────────
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(MATCH, WRAP).also { it.bottomMargin = dp(4) }
        }
        headerRow.addView(ImageView(this).apply {
            setImageDrawable(Widgets.icon(this@WebUiSettingsActivity, R.drawable.ic_back, col(R.color.text_primary)))
            background = ContextCompat.getDrawable(this@WebUiSettingsActivity, R.drawable.bg_icon_ripple)
            setPadding(dp(6), dp(6), dp(6), dp(6))
            isClickable = true; isFocusable = true
            contentDescription = "Back"
            layoutParams = LinearLayout.LayoutParams(dp(34), dp(34)).also { it.marginEnd = dp(6) }
            setOnClickListener { finish() }
        })
        headerRow.addView(TextView(this).apply {
            // Pass-11 (Required Implementation 5): renamed from "WebUI Settings"
            // — this screen now owns the whole web-client configuration story
            // (server port, password, connection approval, profiles, quick play).
            text = "Web Client Settings"
            textSize = 20f
            setTextColor(col(R.color.text_primary))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        })
        headerRow.addView(ImageView(this).apply {
            setImageDrawable(Widgets.icon(this@WebUiSettingsActivity, R.drawable.ic_globe, col(R.color.text_secondary)))
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22))
        })
        root.addView(headerRow)
        root.addView(descText("What every browser sees when it connects. Saved changes apply to new page loads.").also {
            (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(0)
        })
        root.addView(spacer(18))

        // ══════ Card: SERVER (Pass-11 — moved from main Settings) ════════
        // The embedded web server's own configuration lives here now: the
        // HTTP port, the password gate browsers must pass, and the
        // connection-approval policy — everything a WEB CLIENT experiences
        // before it ever reaches the browser-profile customizations below.
        // Saving any of these restarts the server (same broadcast the main
        // Settings tab used to fire for them).
        val serverCard = card("SERVER")
        serverCard.addView(labeledRow("HTTP Port") { infoIcon("HTTP Port", "The port the embedded web server listens on. Browsers and LumiDrop X apps connect to your addresses on this port. Changing it restarts the server.") })
        serverPortEdit = Widgets.inputField(this, SettingsManager.getPort(this).toString(), android.text.InputType.TYPE_CLASS_NUMBER)
        serverPortEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { markDirty() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        serverCard.addView(serverPortEdit)
        serverCard.addView(spacer(16))
        serverCard.addView(divider())
        serverCard.addView(spacer(14))
        // Connection approval (was "Ask before accepting new connections").
        val connRow = switchRow("Ask before accepting new connections",
            SettingsManager.isConfirmConn(this),
            "Connection approval", "When on, every NEW browser session waits for your explicit Accept before it can access anything — the page shows a neutral waiting screen until you approve. Devices you already approved stay connected. Changing it restarts the server.")
        confirmConnSwitch = connRow.second
        serverCard.addView(connRow.first)
        serverCard.addView(spacer(16))
        serverCard.addView(divider())
        serverCard.addView(spacer(14))
        // Password protection (switch + field, same pairing the old Security
        // card used).
        val pwdRow = switchRow("Enable password protection",
            SettingsManager.isPasswordEnabled(this),
            "Password protection", "Browsers must enter this password before they see the web interface. Saving it restarts the server.")
        pwdOnSwitch = pwdRow.second
        serverCard.addView(pwdRow.first)
        pwdEdit = Widgets.inputField(this, SettingsManager.getPassword(this),
            android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD).apply {
            hint = "Enter password..."
            // ── GOAT-PASS 13 (§2 — immediate visibility fix) ──────────────────
            // The field itself is ALWAYS visible; pwdFieldWrap below is the
            // single owner of the shown/hidden state (driven by the switch
            // listener and Discard). The old `visibility = if (checked)` here
            // left the FIELD itself GONE when the screen opened with the
            // switch off — so the first toggle ON made only the eye icon
            // appear (the wrap went VISIBLE but its child stayed GONE) until
            // a save + reopen rebuilt the whole screen. One owner, one state.
            //
            // ── GOAT-PASS 14 Phase 2 (§8 — no Google Password Manager prompts) ──
            // This field is LumiDrop X's APPLICATION-ACCESS password (the
            // gate browsers must pass to reach the web UI), NOT a website /
            // account login credential — but Android's autofill system
            // classifies any TYPE_TEXT_VARIATION_PASSWORD field as a
            // save-worthy credential, so Google Password Manager offered to
            // save it on every enable/change/save (and even on saving
            // unrelated settings with the field merely present). The
            // narrowest standards-based exclusion is applied HERE, on THIS
            // ONE field: importantForAutofill = NO tells the framework this
            // view is not an autofill target at all, which removes both the
            // fill AND the save prompts for it. Nothing else is touched —
            // no global autofill disabling (every other field in the app,
            // and the OS-wide setting, keep their behavior), no fake
            // username fields, no extra hidden inputs, and the eye toggle /
            // save / disable flows below are byte-identical. The value is
            // still stored exactly where it always was (the app's own
            // preferences) — no second copy anywhere. API 26+ only; on
            // API 21-25 the platform has no autofill save flow for native
            // fields, so there is nothing to exclude.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO
            }
        }
        pwdEdit.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { markDirty() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        serverCard.addView(spacer(10))
        // ── GOAT-PASS 12 (§8): the password field's visibility toggle. A ────
        // small, minimalist eye/eye-off icon INSIDE the field (trailing
        // edge, vertically centered) reveals/masks the entered password.
        // The field is wrapped in a FrameLayout so the icon floats over the
        // input's right edge; the input gets extra right padding so typed
        // text can never run under the icon. Reveal state is presentation
        // ONLY — the stored value, the save/discard logic, and the server's
        // verification are untouched, no second copy of the password exists
        // anywhere, and the field re-masks whenever the screen (re)loads
        // its fields (loadFields below always starts masked).
        val pwdFieldWrap = android.widget.FrameLayout(this).apply {
            layoutParams = lp(MATCH, WRAP)
            visibility = if (pwdOnSwitch.isChecked) android.view.View.VISIBLE else android.view.View.GONE
        }
        this.pwdFieldWrap = pwdFieldWrap
        pwdEdit.layoutParams = android.widget.FrameLayout.LayoutParams(MATCH, WRAP)
        pwdEdit.setPadding(pwdEdit.paddingLeft, pwdEdit.paddingTop, dp(46), pwdEdit.paddingBottom)
        pwdFieldWrap.addView(pwdEdit)
        val pwdVisibilityIcon = android.widget.ImageView(this).apply {
            setImageDrawable(com.lumidrop.x.ui.Widgets.icon(this@WebUiSettingsActivity,
                R.drawable.ic_eye, col(R.color.text_muted)))
            contentDescription = "Show password"
            isClickable = true; isFocusable = true
            background = ContextCompat.getDrawable(this@WebUiSettingsActivity, R.drawable.bg_icon_ripple)
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(40), dp(40),
                android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL).also { it.marginEnd = dp(2) }
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        pwdFieldWrap.addView(pwdVisibilityIcon)
        // The switch's visibility toggle drives the WRAPPER now (the field
        // itself always fills it).
        pwdOnSwitch.setOnCheckedChangeListener { _, on ->
            pwdFieldWrap.visibility = if (on) android.view.View.VISIBLE else android.view.View.GONE
            markDirty()
        }
        pwdRevealIcon = pwdVisibilityIcon
        pwdVisibilityIcon.setOnClickListener { applyPwdReveal(!pwdRevealed) }
        serverCard.addView(pwdFieldWrap)
        root.addView(serverCard)
        root.addView(spacer(16))

        // ══════ Card: global content sources (Music folders) ═════════════
        val musicCard = card("MUSIC FOLDERS")
        musicCard.addView(labeledRow("Music sources") { infoIcon("Music folders", "The browser's Music tab normally shows every audio file on this device. Add folders here to restrict it to just those locations — useful when your music lives in one place and the rest of your audio (recordings, notification sounds) shouldn't crowd the list. Empty shows everything.") })
        musicFoldersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(MATCH, WRAP)
        }
        musicCard.addView(musicFoldersContainer)
        renderMusicFolders()
        musicCard.addView(addRow("Add Music Folder", R.drawable.ic_audio) {
            com.lumidrop.x.ui.showFolderPickerDialog(this@WebUiSettingsActivity, File(Environment.getExternalStorageDirectory().absolutePath)) { path, _ ->
                if (musicFolders.none { it == path }) {
                    musicFolders.add(path)
                    markDirty()
                    renderMusicFolders()
                } else {
                    Toast.makeText(this@WebUiSettingsActivity, "Already added", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(musicCard)
        root.addView(spacer(16))

        // ══════ Card: Quick Play default external player ═════════════════
        val quickPlayCard = card("QUICK PLAY")
        quickPlayCard.addView(labeledRow("Default external player") { infoIcon("Quick Play player", "When someone taps Quick Play in the browser, the media can open straight in an external app (VLC, mx player, or the platform's stock player) instead of the in-browser player. Set a default per platform here; Off means Quick Play always opens the Open With menu first. Each browser can still choose a different player for itself — its own choice always wins.") })
        quickPlayContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        quickPlayCard.addView(quickPlayContainer)
        buildQuickPlayRows()
        root.addView(quickPlayCard)
        root.addView(spacer(16))

        // ══════ Card: the browser profile(s) ═════════════════════════════
        val profileCard = card("BROWSER PROFILE")
        profileModeDesc = descText("")
        profileCard.addView(profileModeDesc)
        profileModeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(MATCH, WRAP).also { it.bottomMargin = dp(4) }
        }
        profileCard.addView(profileModeRow)
        buildProfileModeTabs()
        profileCard.addView(spacer(10))
        tabRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = lp(MATCH, WRAP)
        }
        profileCard.addView(tabRow)

        profileCard.addView(spacer(14))
        profileCard.addView(divider())
        profileCard.addView(spacer(14))

        profileContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(MATCH, WRAP)
        }
        profileCard.addView(profileContainer)

        root.addView(profileCard)
        root.addView(spacer(14))
        root.addView(descText("Unsaved profile edits are kept while you switch between platform tabs, and apply on Save."))

        scroll.addView(root)

        buildTabs()
        applyProfileModeUi(initial = true)

        // ── Pass-8 follow-up: the shared SaveDock ─────────────────────────
        val frame = FrameLayout(this).apply { layoutParams = ViewGroup.LayoutParams(MATCH, MATCH) }
        frame.addView(scroll)
        saveDock = SaveDock(this).also { dock ->
            dock.discard.setOnClickListener { discardAll() }
            dock.action.setOnClickListener {
                if (!saveTapLock.tryLock()) return@setOnClickListener
                try { saveAll() } finally { saveTapLock.unlock() }
            }
            frame.addView(dock.view, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).also {
                it.leftMargin = dp(16); it.rightMargin = dp(16); it.bottomMargin = dp(20)
            })
        }
        // ── Pass-29 Part 1 (#8): this surface's SaveDock tracks the live ─────────
        // system insets too. The dock's 20dp base margin is correct while the
        // Android navigation bar's zone is not part of this window's content
        // (the window frame reserving it, or the bar hidden by the "Hide
        // Navigation Bar" preference). In the states where the window DOES
        // draw behind the bar (the API 21-29 LAYOUT_HIDE_NAVIGATION recipe
        // with the sticky bar swipe-revealed over the window, or an
        // insets-controller reveal on API 30+), that fixed margin put the
        // dock's Save/Discard buttons underneath the system bar — the same
        // layering class the Settings tab's dock had against the tabs bar.
        // Padding the FRAME by the live navigation-bar (and IME) inset lifts
        // BOTH children in one write: the scroll viewport ends above the
        // bar's zone, and the bottom-anchored dock rides up with the frame's
        // content area (its own 20dp margin now measured from above the
        // bar). A no-op whenever the inset is 0 — every already-correct
        // configuration keeps today's layout byte-for-byte.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(frame) { v, insets ->
            val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
            val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
            val lift = maxOf(nav, ime).coerceAtLeast(0)
            if (v.paddingBottom != lift) {
                v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, lift)
            }
            insets
        }
        setContentView(frame)
        receiving = ReceivingModal(this) { frame }
    }

    override fun onResume() {
        super.onResume()
        applyLdxWindowChrome()   // Pass-27 (#4.2): re-assert bar state on return
        receiving.start()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Pass-27 (#4.2): a focus steal can restore this window's system bars —
        // re-assert the preference's state the moment focus comes back.
        if (hasFocus) applyLdxWindowChrome()
    }

    override fun onPause() {
        super.onPause()
        receiving.stop()
        // ── FINAL PASS 1 (§5 — Google Password Manager, the cancel half) ──────
        // "The prompt appears ... when leaving the settings screen" is the
        // autofill framework's SAVE trigger firing on the activity
        // transition. The window-level exclusion above should already keep
        // this window out of any save session, but if a session was somehow
        // opened against it anyway (a build that composes the save snapshot
        // before the exclusion, or an OEM variation), explicitly cancelling
        // the manager's current session here retires it before the save UI
        // can be offered — the documented, supported way for an app to say
        // "there is nothing to save from this screen". Scoped to this one
        // activity leaving the foreground; never throws, never affects any
        // other app or the OS-wide setting.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            try {
                (getSystemService(android.view.autofill.AutofillManager::class.java))?.cancel()
            } catch (e: Exception) { /* no autofill service / not enabled — nothing to cancel */ }
        }
    }

    // ── Dirty tracking ────────────────────────────────────────────────────────

    /** Called from every mutation site below rather than generically inside
     *  renderProfileSection() — that function also runs on tab switch, to
     *  render freshly-loaded (unmodified) data, which must NOT count as a
     *  change. Safe (and cheap) to call after every single edit: it just
     *  recomputes whether the live state still differs from
     *  originalSnapshot. */
    private fun markDirty() = refreshSaveVisibility()

    private fun refreshSaveVisibility() {
        if (!::saveDock.isInitialized) return
        val show = originalSnapshot?.let { it != currentSnapshot() } ?: false
        val currentlyShown = saveDock.isShown
        if (show == currentlyShown) {
            if (show) saveDock.setState(SaveDock.State.IDLE, idleCaption())
            return
        }
        if (show) {
            saveDock.setState(SaveDock.State.IDLE, idleCaption())
            saveDock.show()
        } else {
            saveDock.hide()
        }
    }

    private fun idleCaption(): String =
        if (WebUiSettingsManager.profileMode(this) == WebUiSettingsManager.PROFILE_MODE_UNIVERSAL)
            "Unsaved changes to the browser profile"
        else
            "Unsaved changes to the ${labelFor(currentOs)} profile"

    /** The dock's Discard: reloads every editable value (globals + current
     *  profile) from persisted storage — the exact reverse of Save, which
     *  the old screen simply had no equivalent of. */
    private fun discardAll() {
        musicFolders = WebUiSettingsManager.getMusicFolderPaths(this).toMutableList()
        for (os in WebUiSettingsManager.VALID_OS) {
            quickPlayDefaults[os] = WebUiSettingsManager.getQuickPlayDefault(this, os)
        }
        // Pass-11 (SERVER card): the moved trio reverts with everything else.
        serverPortEdit.setText(SettingsManager.getPort(this).toString())
        pwdOnSwitch.isChecked = SettingsManager.isPasswordEnabled(this)
        pwdEdit.setText(SettingsManager.getPassword(this))
        // GOAT-PASS 12 (§8): Discard re-masks the password field and drives
        // the WRAPPER's visibility (the field itself always fills it).
        pwdFieldWrap.visibility = if (pwdOnSwitch.isChecked) android.view.View.VISIBLE else android.view.View.GONE
        applyPwdReveal(false)
        confirmConnSwitch.isChecked = SettingsManager.isConfirmConn(this)
        renderMusicFolders()
        refreshQuickPlayValues()
        selectOs(currentOs)   // reloads the profile tab + retakes the snapshot
        Toast.makeText(this, "Changes discarded", Toast.LENGTH_SHORT).show()
    }

    // ── Music folders list ───────────────────────────────────────────────────

    private fun renderMusicFolders() {
        if (!::musicFoldersContainer.isInitialized) return
        musicFoldersContainer.removeAllViews()
        if (musicFolders.isEmpty()) {
            musicFoldersContainer.addView(descText("No folders added — the Music tab shows all audio.").also {
                (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(2)
            })
            return
        }
        for (path in musicFolders.toList()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@WebUiSettingsActivity, R.drawable.bg_btn_secondary)
                setPadding(dp(12), dp(11), dp(8), dp(11))
                layoutParams = lp(MATCH, WRAP).also { it.bottomMargin = dp(6) }
            }
            row.addView(ImageView(this).apply {
                setImageDrawable(Widgets.icon(this@WebUiSettingsActivity, R.drawable.ic_audio, col(R.color.accent_light)))
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).also { it.marginEnd = dp(10) }
            })
            row.addView(tv(path, 12f, R.color.text_primary).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            })
            row.addView(ghostButton("Remove") {
                musicFolders.remove(path)
                markDirty()
                renderMusicFolders()
            })
            musicFoldersContainer.addView(row)
        }
    }

    // ── Quick Play default player rows ────────────────────────────────────────

    /** One row per client platform: label + the currently-selected player
     *  (or "Off"), tapping opens a single-choice picker (Off + the players
     *  the web client can launch on that platform — the exact same list the
     *  Open With sheet shows). */
    private fun buildQuickPlayRows() {
        if (!::quickPlayContainer.isInitialized) return
        quickPlayContainer.removeAllViews()
        val valueViews = HashMap<String, TextView>()
        val osLabels = linkedMapOf("android" to "Android", "windows" to "Windows", "ios" to "iOS / iPadOS")
        for ((os, osLabel) in osLabels) {
            val value = TextView(this).apply {
                textSize = 12f
                setTextColor(col(R.color.text_muted))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                gravity = Gravity.END
            }
            valueViews[os] = value
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@WebUiSettingsActivity, R.drawable.bg_btn_secondary)
                setPadding(dp(12), dp(11), dp(8), dp(11))
                isClickable = true; isFocusable = true
                layoutParams = lp(MATCH, WRAP).also { it.bottomMargin = dp(6) }
                setOnClickListener { showQuickPlayPicker(os) }
            }
            row.addView(TextView(this).apply {
                text = osLabel; textSize = 13.5f
                setTextColor(col(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
            })
            row.addView(value)
            row.addView(ghostButton("Change") { showQuickPlayPicker(os) })
            quickPlayContainer.addView(row)
        }
        quickPlayValues = valueViews
        refreshQuickPlayValues()
    }

    private fun refreshQuickPlayValues() {
        if (!::quickPlayValues.isInitialized) return
        for ((os, view) in quickPlayValues) {
            val id = quickPlayDefaults[os] ?: ""
            val label = if (id.isEmpty()) "Off"
                else WebUiSettingsManager.QUICK_PLAY_PLAYERS[os]?.firstOrNull { it.id == id }?.label ?: "Off"
            view.text = label
            view.setTextColor(col(if (id.isEmpty()) R.color.text_muted else R.color.accent_light))
        }
    }

    private fun showQuickPlayPicker(os: String) {
        val players = WebUiSettingsManager.QUICK_PLAY_PLAYERS[os] ?: return
        val options = listOf("" to "Off \u2014 choose via Open With") + players.map { it.id to it.label }
        val current = quickPlayDefaults[os] ?: ""
        val osLabel = when (os) { "android" -> "Android"; "windows" -> "Windows"; "ios" -> "iOS / iPadOS"; else -> os }

        // The app's LdxDialog shell: icon+title header, scaled entrance, and
        // a custom bodyView rendering the choice list as proper option rows
        // — leading glyph, label, trailing check on the current pick,
        // selected row tinted with the app accent — instead of platform
        // radio buttons.
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = lp(MATCH, WRAP)
        }
        // Declared before the rows so their click listeners can close the
        // dialog (assigned after showLdxDialog returns below).
        var dlg: android.app.Dialog? = null
        val optionViews = mutableListOf<LinearLayout>()
        for ((id, label) in options) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(11), dp(12), dp(11))
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).also {
                    if (optionViews.isNotEmpty()) it.topMargin = dp(4)
                }
                // The trailing check flips per selection below; the leading
                // glyph marks the option kind (player vs Off).
                addView(ImageView(this@WebUiSettingsActivity).apply {
                    setImageDrawable(Widgets.icon(
                        this@WebUiSettingsActivity,
                        if (id.isEmpty()) R.drawable.ic_close else R.drawable.ic_play,
                        col(if (id.isEmpty()) R.color.text_muted else R.color.accent_light)))
                    layoutParams = LinearLayout.LayoutParams(dp(20), dp(20)).also { it.marginEnd = dp(10) }
                })
                addView(TextView(this@WebUiSettingsActivity).apply {
                    text = label; textSize = 13.5f
                    setTextColor(col(R.color.text_primary))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
                })
                addView(ImageView(this@WebUiSettingsActivity).apply {
                    setImageDrawable(Widgets.icon(
                        this@WebUiSettingsActivity, R.drawable.ic_check, col(R.color.accent)))
                    layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).also { it.marginStart = dp(8) }
                    visibility = View.GONE
                    tag = "check"
                })
                tag = id
                setOnClickListener {
                    quickPlayDefaults[os] = id
                    markDirty()
                    refreshQuickPlayValues()
                    dlg?.dismiss()
                }
            }
            optionViews.add(row)
            list.addView(row)
        }
        // Apply the current selection's visuals once every row exists.
        for (row in optionViews) {
            val sel = row.tag == current
            row.background = if (sel)
                Widgets.rippleRect(this, col(R.color.border_accent_20), radiusDp = 12)
            else
                Widgets.rippleRect(this, android.graphics.Color.TRANSPARENT, radiusDp = 12,
                    strokeColor = col(R.color.bg_secondary))
            (row.findViewWithTag<View>("check")).visibility = if (sel) View.VISIBLE else View.GONE
        }

        dlg = com.lumidrop.x.ui.showLdxDialog(
            context = this,
            title = "Quick Play Player \u2014 $osLabel",
            iconRes = R.drawable.ic_play,
            bodyView = list,
            actions = listOf(
                com.lumidrop.x.ui.LdxDialogAction("Cancel", com.lumidrop.x.ui.LdxButtonStyle.GHOST),
            ),
        )
    }

    // ── Profile mode + OS tabs ────────────────────────────────────────────────

    // The mode switcher row + its description label — built once, restyled
    // on every mode change.
    private lateinit var profileModeRow: LinearLayout
    private lateinit var profileModeDesc: TextView

    private fun buildProfileModeTabs() {
        profileModeRow.removeAllViews()
        val modes = listOf(
            WebUiSettingsManager.PROFILE_MODE_UNIVERSAL to "Universal",
            WebUiSettingsManager.PROFILE_MODE_PER_OS to "Per Platform"
        )
        for ((mode, label) in modes) {
            val btn = TextView(this).apply {
                text = label; textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).also {
                    it.marginEnd = dp(6)
                }
                setOnClickListener { switchProfileMode(mode) }
                tag = mode
            }
            profileModeRow.addView(btn)
        }
        refreshProfileModeStyles()
    }

    private fun refreshProfileModeStyles() {
        if (!::profileModeRow.isInitialized) return
        val active = WebUiSettingsManager.profileMode(this)
        for (i in 0 until profileModeRow.childCount) {
            val c = profileModeRow.getChildAt(i) as TextView
            val isSel = c.tag == active
            c.background = ContextCompat.getDrawable(this, if (isSel) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
            c.setTextColor(if (isSel) android.graphics.Color.WHITE else col(R.color.text_secondary))
        }
    }

    /** Switches Universal ↔ Per-Platform. Unsaved edits to the tab being
     *  left are discarded (identical to the existing tab-switch behavior).
     *  The mode itself is persisted immediately — it's a structural choice,
     *  not a profile edit, and the manager seeds the universal bucket on
     *  first entry so nothing is lost. */
    private fun switchProfileMode(mode: String) {
        if (mode == WebUiSettingsManager.profileMode(this)) return
        WebUiSettingsManager.setProfileMode(this, mode)
        refreshProfileModeStyles()
        applyProfileModeUi()
        try {
            com.lumidrop.x.service.ServerForegroundService.instance?.server?.notifyWebUiConfigChanged()
        } catch (e: Exception) { /* offline — durable, applies at next start */ }
    }

    /** Applies the ACTIVE mode to the UI: in Universal mode the three OS
     *  tabs hide entirely and the single universal profile loads (there is
     *  exactly one thing to edit); in Per-Platform mode the tabs return and
     *  the selected platform profile loads. */
    private fun applyProfileModeUi(initial: Boolean = false) {
        val universal = WebUiSettingsManager.profileMode(this) == WebUiSettingsManager.PROFILE_MODE_UNIVERSAL
        if (::profileModeDesc.isInitialized) {
            profileModeDesc.text = if (universal)
                "One set of settings applies to every connected device — phone, tablet and computer alike."
            else
                "Each platform keeps its own independent sort, layout and pinned-folder settings."
        }
        if (::tabRow.isInitialized) {
            tabRow.visibility = if (universal) View.GONE else View.VISIBLE
        }
        // In universal mode the editable target is the universal profile
        // itself (selectOs("universal") reads/writes it through the manager's
        // own universal routing); in per-platform mode the previously-selected
        // platform reloads (android on the very first entry).
        if (universal) {
            selectOs("universal")
        } else {
            val target = if (initial || currentOs == "universal") "android" else currentOs
            selectOs(target)
        }
    }

    private fun buildTabs() {
        tabRow.removeAllViews()
        val labels = listOf("android" to "Android", "windows" to "Windows", "ios" to "iOS")
        for ((os, label) in labels) {
            val btn = TextView(this).apply {
                text = label; textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(10), dp(10), dp(10))
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).also { it.marginEnd = dp(6) }
                setOnClickListener { selectOs(os) }
                tag = os
            }
            tabRow.addView(btn)
        }
        refreshTabStyles()
    }

    private fun refreshTabStyles() {
        for (i in 0 until tabRow.childCount) {
            val c = tabRow.getChildAt(i) as TextView
            val isSel = c.tag == currentOs
            c.background = ContextCompat.getDrawable(this, if (isSel) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
            c.setTextColor(if (isSel) android.graphics.Color.WHITE else col(R.color.text_secondary))
        }
    }

    private fun selectOs(os: String) {
        currentOs = os
        refreshTabStyles()
        val profile = WebUiSettingsManager.getProfile(this, os)
        sortField          = profile.sort.field
        sortAsc            = profile.sort.asc
        homeOrder          = profile.homeLayout.order.toMutableList()
        homeHidden         = profile.homeLayout.hidden.toMutableSet()
        pinnedFolders      = profile.pinnedFolders.toMutableList()
        // Freshly loaded, unmodified data — this IS "no unsaved changes"
        // for this tab, so the snapshot is taken here, after every field
        // above is set, not before.
        originalSnapshot = currentSnapshot()
        refreshSaveVisibility()
        renderProfileSection()
    }

    // ── Profile section (rebuilt on every tab switch / in-place edit) ────────

    private fun renderProfileSection() {
        profileContainer.removeAllViews()

        // Default Sort
        profileContainer.addView(labeledRow("Default file-manager sort") { infoIcon("Default sort", "The ordering the browser's Files tab opens with. The person browsing can always re-sort for themselves; this is just the first impression.") })
        val fieldRow = chipRow(
            listOf("name" to "Name", "date" to "Date", "size" to "Size", "type" to "Type"),
            { sortField }) { sortField = it; markDirty(); renderProfileSection() }
        profileContainer.addView(fieldRow)
        profileContainer.addView(spacer(8))
        val dirRow = chipRow(
            listOf("asc" to "Ascending", "desc" to "Descending"),
            { if (sortAsc) "asc" else "desc" }) { sortAsc = (it == "asc"); markDirty(); renderProfileSection() }
        profileContainer.addView(dirRow)
        profileContainer.addView(spacer(18))
        profileContainer.addView(divider())
        profileContainer.addView(spacer(18))

        // Web Client Layout & Tabs — governs BOTH the home cards AND the
        // web client's sidebar tabs (a hidden category removes its tab
        // entirely, not just its home tile).
        profileContainer.addView(labeledRow("Layout & tabs order") { infoIcon("Layout & tabs", "The order and visibility of the browser's categories. Hiding one removes its tab and home card — the files themselves stay on disk untouched.") })
        val sectionLabels = mapOf(
            "images" to "Images", "videos" to "Videos", "music" to "Music",
            "docs" to "Docs", "apps" to "Apps", "files" to "Files"
        )
        for ((idx, key) in homeOrder.withIndex()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@WebUiSettingsActivity, R.drawable.bg_btn_secondary)
                setPadding(dp(12), dp(6), dp(8), dp(6))
                layoutParams = lp(MATCH, WRAP).also { it.bottomMargin = dp(6) }
            }
            row.addView(ImageView(this).apply {
                setImageDrawable(Widgets.icon(this@WebUiSettingsActivity,
                    when (key) {
                        "images" -> R.drawable.ic_image
                        "videos" -> R.drawable.ic_video
                        "music"  -> R.drawable.ic_audio
                        "docs"   -> R.drawable.ic_file
                        // ── Pass-20 (Layout row icon polish): the Apps row ──
                        // now carries the dedicated line-styled package glyph
                        // (ic_file_apk — the same professional mark every
                        // transfers row already uses for .apk files) instead
                        // of the loud filled ic_android robot, and "docs"
                        // rides the Pass-20 redesigned folded-document
                        // ic_file. The whole row set now reads as one family.
                        "apps"   -> R.drawable.ic_file_apk
                        else     -> R.drawable.ic_folder
                    },
                    col(if (key in homeHidden) R.color.text_muted
                        // ── Pass-21 (icon color consistency) ─────────────────────
                        // Pass-20 tinted the Apps row's new ic_file_apk glyph
                        // with FormatIcons' green Android-family cue — the
                        // ONE differently-colored icon in this whole section
                        // (the reported "make sure its colors match the other
                        // icons in that section perfectly"). The glyph itself
                        // stays (the professional package mark); the TINT now
                        // matches every sibling: accent_light when visible,
                        // text_muted when hidden.
                        else R.color.accent_light)))
                layoutParams = LinearLayout.LayoutParams(dp(18), dp(18)).also { it.marginEnd = dp(10) }
            })
            row.addView(tv(sectionLabels[key] ?: key, 13.5f,
                if (key in homeHidden) R.color.text_muted else R.color.text_primary).apply {
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            val visBtn = ghostButton(if (key in homeHidden) "Hidden" else "Visible") {
                if (key in homeHidden) homeHidden.remove(key) else homeHidden.add(key)
                markDirty()
                renderProfileSection()
            }
            val upBtn = iconGhostButton(R.drawable.ic_arrow_up, "Move up") {
                if (idx > 0) { val tmp = homeOrder[idx]; homeOrder[idx] = homeOrder[idx - 1]; homeOrder[idx - 1] = tmp; markDirty(); renderProfileSection() }
            }
            val downBtn = iconGhostButton(R.drawable.ic_arrow_down, "Move down") {
                if (idx < homeOrder.size - 1) { val tmp = homeOrder[idx]; homeOrder[idx] = homeOrder[idx + 1]; homeOrder[idx + 1] = tmp; markDirty(); renderProfileSection() }
            }
            row.addView(visBtn); row.addView(upBtn); row.addView(downBtn)
            profileContainer.addView(row)
        }
        profileContainer.addView(spacer(18))
        profileContainer.addView(divider())
        profileContainer.addView(spacer(18))

        // Pinned folders
        profileContainer.addView(labeledRow("Pinned folders (${labelFor(currentOs)})") { infoIcon("Pinned folders", "Folders pinned to the browser's home screen for this platform — one-tap access to the places this device's users actually need. They open in the web file manager exactly like any other folder.") })
        if (pinnedFolders.isEmpty()) {
            profileContainer.addView(descText("No pinned folders yet.").also {
                (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(2)
            })
        }
        for (pf in pinnedFolders.toList()) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ContextCompat.getDrawable(this@WebUiSettingsActivity, R.drawable.bg_btn_secondary)
                setPadding(dp(12), dp(10), dp(8), dp(10))
                layoutParams = lp(MATCH, WRAP).also { it.bottomMargin = dp(6) }
            }
            val info = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            }
            info.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(this@WebUiSettingsActivity).apply {
                    setImageDrawable(Widgets.icon(this@WebUiSettingsActivity, R.drawable.ic_folder, col(R.color.accent_light)))
                    layoutParams = LinearLayout.LayoutParams(dp(17), dp(17)).also { it.marginEnd = dp(8) }
                })
                addView(tv(pf.name, 13.5f, R.color.text_primary))
            })
            info.addView(tv(pf.path, 11f, R.color.text_muted).also {
                (it.layoutParams as LinearLayout.LayoutParams).topMargin = dp(2)
            })
            val rmBtn = ghostButton("Remove") {
                pinnedFolders.remove(pf)
                markDirty()
                renderProfileSection()
            }
            row.addView(info); row.addView(rmBtn)
            profileContainer.addView(row)
        }
        profileContainer.addView(addRow("Add Pinned Folder", R.drawable.ic_folder) {
            com.lumidrop.x.ui.showFolderPickerDialog(this@WebUiSettingsActivity, File(Environment.getExternalStorageDirectory().absolutePath)) { path, name ->
                if (pinnedFolders.none { it.path == path }) {
                    pinnedFolders.add(WebUiSettingsManager.PinnedFolder(path, name))
                    markDirty()
                    renderProfileSection()
                } else {
                    Toast.makeText(this@WebUiSettingsActivity, "Already pinned", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun labelFor(os: String) = when (os) { "universal" -> "Universal"; "android" -> "Android"; "windows" -> "Windows"; "ios" -> "iOS"; else -> os }

    // ── Save ─────────────────────────────────────────────────────────────────

    private fun saveAll() {
        // ── Pass-11 (SERVER card): the moved trio validates and commits here ──
        // with the SAME restart broadcast SettingsFragment used to fire for
        // them (RestartServerReceiver is manifest-registered).
        val port = serverPortEdit.text.toString().toIntOrNull()
        if (port == null || port < 1024 || port > 65535) {
            saveDock.setState(SaveDock.State.ERROR, "Port must be between 1024 and 65535")
            Toast.makeText(this, "Port must be between 1024 and 65535", Toast.LENGTH_SHORT).show()
            return
        }
        val portChanged = port != SettingsManager.getPort(this)
        // ── GOAT-PASS 13 (§3 — empty-password enable is refused, an existing ──
        // password survives a blank field). The effective password is what the
        // SAVE actually applies: a blank field while protection stays ON means
        // "keep the currently-configured password" (the user is editing other
        // settings), never an erase. Enabling protection with NEITHER a typed
        // password NOR an existing one is invalid: the save is refused, the
        // field is shown and focused so it can be corrected, and nothing is
        // persisted. The validation lives here in the save logic itself — not
        // only in the UI — so no path can persist an enabled-but-unconfigured
        // state. The password value itself is never logged or echoed back.
        val pwdOn = pwdOnSwitch.isChecked
        val existingPwd = SettingsManager.getPassword(this)
        val effectivePwd = pwdEdit.text.toString().ifBlank { existingPwd }
        if (pwdOn && effectivePwd.isBlank()) {
            saveDock.setState(SaveDock.State.ERROR, "Enter a password before enabling password protection")
            Toast.makeText(this, "Enter a password before enabling password protection", Toast.LENGTH_SHORT).show()
            // The correction path: the field is shown and focused so the
            // password can be typed right away — nothing is persisted, the
            // screen stays open exactly as it was.
            pwdFieldWrap.visibility = android.view.View.VISIBLE
            pwdEdit.requestFocus()
            try {
                @Suppress("DEPRECATION")
                val imm = getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(pwdEdit, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            } catch (e: Exception) { /* best-effort focus aid */ }
            return
        }
        val pwdChanged = pwdOn != SettingsManager.isPasswordEnabled(this) ||
            (pwdOn && effectivePwd != existingPwd)
        val connChanged = confirmConnSwitch.isChecked != SettingsManager.isConfirmConn(this)
        val serverNeedsRestart = portChanged || pwdChanged || connChanged
        SettingsManager.setPort(this, port)
        SettingsManager.setPasswordEnabled(this, pwdOn)
        if (pwdOn) SettingsManager.setPassword(this, effectivePwd)
        SettingsManager.setConfirmConn(this, confirmConnSwitch.isChecked)

        val oldMusicFolders = WebUiSettingsManager.getMusicFolderPaths(this)
        WebUiSettingsManager.setMusicFolderPaths(this, musicFolders)

        // Quick Play defaults are global (per client platform, not per
        // profile tab) — committed on the same Save as everything else, and
        // the config revision bump propagates them to every connected
        // client without a page refresh (cfgv-driven refetch).
        for ((os, id) in quickPlayDefaults) {
            if (WebUiSettingsManager.getQuickPlayDefault(this, os) != id) {
                WebUiSettingsManager.setQuickPlayDefault(this, os, id)
            }
        }

        // The downloadManager block is pure legacy round-trip — nothing in
        // it is editable anymore and the engine ignores both concurrency
        // fields (fixed at 1). Written back exactly as parsed so existing
        // profile JSON never needs a migration.
        val existingDm = WebUiSettingsManager.getProfile(this, currentOs).downloadManager
        val profile = WebUiSettingsManager.OsProfile(
            sort = WebUiSettingsManager.SortPref(sortField, sortAsc),
            downloadManager = existingDm,
            homeLayout = WebUiSettingsManager.HomeLayoutPref(homeOrder.toList(), homeHidden.toList()),
            pinnedFolders = pinnedFolders.toList()
        )
        WebUiSettingsManager.saveProfile(this, currentOs, profile)

        // Live propagation: the save already bumped the manager's config
        // revision; bump the server's state version too so every connected
        // client's next SSE/poll payload carries the new cfgv, refetches
        // /api/webui-config, and rebuilds its navigation in place (a
        // just-disabled tab disappears without any page refresh). Server
        // not running (configured before first start) is fine — the
        // revision is durable and ships with the first payload anyway.
        try {
            com.lumidrop.x.service.ServerForegroundService.instance?.server?.notifyWebUiConfigChanged()
        } catch (e: Exception) { /* offline save — durable, applies at next start */ }

        // If any music folder is new, make sure MediaStore actually knows
        // about everything in it right away — otherwise newly-set folders
        // with files that were never scanned would show up empty in the
        // Music tab until Android happens to scan them on its own schedule.
        val newlyAdded = musicFolders.filter { it !in oldMusicFolders }
        if (newlyAdded.isNotEmpty()) {
            Thread {
                try {
                    val files = newlyAdded.flatMap { folder ->
                        File(folder).walkTopDown().filter { it.isFile }.map { it.absolutePath }.toList()
                    }.toTypedArray()
                    if (files.isNotEmpty()) MediaScannerConnection.scanFile(this, files, null, null)
                } catch (e: Exception) { /* best-effort refresh only */ }
            }.start()
        }

        Toast.makeText(this, "${labelFor(currentOs)} profile saved", Toast.LENGTH_SHORT).show()
        originalSnapshot = currentSnapshot()
        refreshSaveVisibility()

        // Pass-11 (SERVER card): a changed port / password / approval policy
        // restarts the embedded server — the same ACTION_RESTART_SERVER
        // broadcast SettingsFragment fired for these before the move.
        if (serverNeedsRestart) {
            try {
                val intent = android.content.Intent(SettingsManager.ACTION_RESTART_SERVER_ACTION)
                intent.setPackage(packageName)
                sendBroadcast(intent)
                Toast.makeText(this, "Server restarting…", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) { /* receiver is manifest-registered; a miss just means no restart */ }
        }
    }

    // ── Widget helpers (the same design language SettingsFragment uses) ──────

    private fun col(res: Int) = ContextCompat.getColor(this, res)
    private fun dp(n: Int)    = (n * resources.displayMetrics.density).toInt()

    private val MATCH get() = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP  get() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)

    private fun spacer(d: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(MATCH, dp(d)) }

    private fun divider() = View(this).apply {
        setBackgroundColor(col(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
    }

    private fun tv(text: String, size: Float, colorRes: Int) = TextView(this).apply {
        this.text = text; textSize = size
        setTextColor(col(colorRes))
        layoutParams = lp(WRAP, WRAP)
    }

    /** A section card in the app's standard settings style (Widgets'
     *  sectionCard + sectionHeading) — the surface every group on this
     *  screen now sits on, matching the main Settings tab. */
    private fun card(heading: String): LinearLayout =
        Widgets.sectionCard(this, paddingDp = 16).also { it.addView(Widgets.sectionHeading(this, heading)) }

    /** Pass-11 (SERVER card): a labelled switch row with an (i) explainer —
     *  the exact idiom SettingsFragment's switchRowInfo uses, reproduced
     *  here (this screen previously had no switch rows of its own). */
    private fun switchRow(label: String, checked: Boolean, infoTitle: String, infoBody: String): Pair<LinearLayout, androidx.appcompat.widget.SwitchCompat> {
        val sw = androidx.appcompat.widget.SwitchCompat(this).apply {
            isChecked = checked
            layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(MATCH, WRAP).also { it.bottomMargin = dp(8) }
            addView(TextView(this@WebUiSettingsActivity).apply {
                text = label; textSize = 13.5f
                setTextColor(col(R.color.text_primary))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addView(sw)
        }
        sw.setOnCheckedChangeListener { _, _ -> markDirty() }
        row.addView(infoIcon(infoTitle, infoBody))
        return row to sw
    }

    private fun descText(text: String) = tv(text, 12f, R.color.text_muted).apply {
        layoutParams = lp(MATCH, WRAP).also { it.bottomMargin = dp(8) }
    }

    /** Section-label row (12sp muted) with an optional trailing (i) glyph —
     *  the exact idiom SettingsFragment's screen uses for anything that
     *  needs more than a line of explanation. */
    private fun labeledRow(label: String, buildTrailing: () -> View): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(MATCH, WRAP).also { it.bottomMargin = dp(8) }
            addView(TextView(this@WebUiSettingsActivity).apply {
                text = label; textSize = 12f; setTextColor(col(R.color.text_muted))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addView(buildTrailing())
        }

    /** A small (i) glyph that opens [title]/[body] in an LdxDialog. */
    private fun infoIcon(title: String, body: String): ImageView = ImageView(this).apply {
        setImageDrawable(Widgets.icon(this@WebUiSettingsActivity, R.drawable.ic_info, col(R.color.text_muted)))
        isClickable = true; isFocusable = true
        background = ContextCompat.getDrawable(this@WebUiSettingsActivity, R.drawable.bg_icon_ripple)
        setPadding(dp(3), dp(3), dp(3), dp(3))
        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).also { it.marginStart = dp(6) }
        setOnClickListener {
            com.lumidrop.x.ui.showLdxDialog(
                context = this@WebUiSettingsActivity,
                title = title,
                message = body,
                iconRes = R.drawable.ic_info,
                actions = listOf(com.lumidrop.x.ui.LdxDialogAction("Got it", com.lumidrop.x.ui.LdxButtonStyle.PRIMARY)),
            )
        }
    }

    /** The "+ Add ..." row — a full-width secondary button with a leading
     *  glyph, consistent with the app's other add-affordances. */
    private fun addRow(label: String, iconRes: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            textSize = 13f
            setTextColor(col(R.color.accent_light))
            gravity = Gravity.CENTER
            background = ContextCompat.getDrawable(this@WebUiSettingsActivity, R.drawable.bg_btn_secondary)
            setPadding(dp(12), dp(11), dp(12), dp(11))
            isClickable = true; isFocusable = true
            layoutParams = lp(MATCH, WRAP).also { it.topMargin = dp(4) }
            setOnClickListener { onClick() }
            setCompoundDrawablesWithIntrinsicBounds(Widgets.icon(this@WebUiSettingsActivity, iconRes, col(R.color.accent_light)), null, null, null)
            compoundDrawablePadding = dp(8)
        }

    /** Small ghost button used for remove/change/visibility controls. */
    private fun ghostButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label; textSize = 12.5f
        gravity = Gravity.CENTER
        setTextColor(col(R.color.text_secondary))
        background = ContextCompat.getDrawable(this@WebUiSettingsActivity, R.drawable.bg_btn_ghost)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).also { it.marginStart = dp(6) }
        setOnClickListener { onClick() }
    }

    /** Compact icon-only ghost button (the layout order arrows) — 32dp
     *  touch target, 14dp glyph, quiet muted tint. */
    private fun iconGhostButton(iconRes: Int, desc: String, onClick: () -> Unit) = ImageView(this).apply {
        setImageDrawable(Widgets.icon(this@WebUiSettingsActivity, iconRes, col(R.color.text_secondary)))
        background = ContextCompat.getDrawable(this@WebUiSettingsActivity, R.drawable.bg_icon_ripple)
        setPadding(dp(7), dp(7), dp(7), dp(7))
        isClickable = true; isFocusable = true
        contentDescription = desc
        layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
        setOnClickListener { onClick() }
    }

    /** A row of mutually-exclusive chip buttons; [selected] returns the
     *  active value, [onSelect] commits a tap. */
    private fun chipRow(options: List<Pair<String, String>>, selected: () -> String, onSelect: (String) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(MATCH, WRAP) }
        val chips = options.map { (value, label) ->
            TextView(this).apply {
                text = label; textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(9), dp(10), dp(9))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).also { it.marginEnd = dp(6) }
                isClickable = true; isFocusable = true
                tag = value
                setOnClickListener { onSelect(value) }
            }
        }
        chips.forEach { row.addView(it) }
        chips.forEach { c ->
            val isSel = c.tag == selected()
            c.background = ContextCompat.getDrawable(this, if (isSel) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
            c.setTextColor(if (isSel) android.graphics.Color.WHITE else col(R.color.text_secondary))
        }
        return row
    }
}
