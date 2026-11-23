package com.lumidrop.x.settings

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.lumidrop.x.R
import com.lumidrop.x.ui.Widgets
import com.lumidrop.x.ui.dp
import com.lumidrop.x.ui.dpf

/**
 * The "Settings" tab -- ported from SettingsActivity as part of Phase 17's
 * bottom-nav shell, with its Phase 17 par 1-3 redesign applied: settings
 * grouped into distinct card modules, three new independent network-
 * interface toggles, and a credits card.
 *
 * Behavior carried over unchanged from the Activity version: saving still
 * detects which settings actually need a server restart (port / password /
 * connection confirmation) and sends the same ACTION_RESTART_SERVER
 * broadcast RestartServerReceiver already handles -- see saveSettings().
 * One real behavior change: since Settings is now a persistent bottom-nav
 * tab rather than a screen you navigate back out of, saving no longer
 * calls finish() (there's no "back" to return to) -- it just toasts and
 * stays put, the same way most system Settings apps behave.
 *
 * Card groups (adapted from the brief's example list to what this app
 * actually has settings for -- forcing an empty "Appearance" card just to
 * match the example verbatim would be padding, not a real group):
 *  - Network & Discovery: discovery name, default access mode
 *    (Pass-11: the HTTP Port moved to Web Client Settings; the interface
 *    toggle is REMOVED — local sharing is permanently enabled)
 *  - Storage & Paths: upload folder
 *  - Transfer Engine: auto-accept / auto-send, floating popup, Web Client link
 *  - Screen & Battery: keep screen on, full screen (Pass-11)
 *  - Diagnostics + Credits
 *  (Pass-11: the Security card moved — connection confirmation and password
 *   now live in Web Client Settings, next to the port they configure.)
 *
 * Pass-7 (declutter): the long explanation paragraphs that used to sit
 * under every control are gone -- labels stand on their own, and anything
 * that genuinely needs more than a line carries a small (i) glyph that
 * opens the full explanation in an LdxDialog. The screen reads like a
 * modern settings page: dense, quiet, nothing to read past.
 */
class SettingsFragment : Fragment() {
    // ── PASS 19 (§5/§6): the About + Updates section — a field so the
    //  fragment's own lifecycle can drive it (auto-check pacing on resume,
    //  post-install reconciliation, re-render after check/download).
    private var aboutSection: com.lumidrop.x.update.AboutUpdateSection.Section? = null
    // §6.5: the download-cancel flag — consulted per chunk by UpdateManager.
    private val cancelRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    // ── Pass-38 (#7): this tab's compact music-pill dock (built in
    // onCreateView's header row, registered with MusicPlayback while this
    // tab is the visible one — one shared player, one pill, all three tabs).
    private var musicPillSlot: android.widget.FrameLayout? = null

    private lateinit var pathEdit: EditText
    private lateinit var discoveryNameEdit: EditText
    private lateinit var keepScreenSwitch: SwitchCompat
    // Pass-11 (Required Implementation 4): the Full Screen immersive toggle.
    private lateinit var fullScreenSwitch: SwitchCompat
    private lateinit var autoAcceptSwitch: SwitchCompat
    private lateinit var autoSendSwitch: SwitchCompat
    // ── GOAT-PASS 14 (Phase 1, §1.2): the app-side "send queue as one ZIP" ──
    // master switch. OFF (default) keeps the classic per-item sends
    // byte-for-byte; ON packages each send operation's captured batch as
    // ONE streamed archive for browser and native recipients alike.
    private lateinit var zipSendSwitch: SwitchCompat
    private lateinit var floatPopupSwitch: SwitchCompat
    // ── SELECTION FINAL PASS 1 (#13.3): the Received / History tab badges' ──
    // enable switches — one per tab, both defaulting ON (the badges' existing
    // behavior); read LIVE at every badge paint, saved like every other
    // preference in this card.
    private lateinit var receivedBadgeSwitch: SwitchCompat
    private lateinit var historyBadgeSwitch: SwitchCompat
    // ── GOAT-PASS 10 (§9): the Open Received Folder Internally toggle.
    private lateinit var openReceivedInternalSwitch: SwitchCompat
    // ── Pass-26 (#5): Automatic Server Startup — on by default, preserving ──
    // the app's launch behavior since v1.0; off means the server waits for
    // an explicit Start tap on each fresh launch.
    private lateinit var autoServerStartSwitch: SwitchCompat
    // Pass-11 (Required Implementations 3+5): the HTTP Port / password /
    // connection-confirmation rows moved to WebUiSettingsActivity (now
    // "Web Client Settings"), and the Hotspot/Wi-Fi/LAN toggle is REMOVED
    // entirely — local sharing is permanently on (see NetworkUtils and
    // ServerForegroundService, which no longer consult any toggle).
    private lateinit var defaultModeFullBtn: TextView
    private lateinit var defaultModeTransferBtn: TextView
    private var defaultModeSelection: String = "transfer"
    // ── GOAT-PASS 8 (§4): the selection interaction style chips' selection ────
    // ("range" | "slide") — the same chip + Save-dock pattern the default
    // access row above uses. "range" is the DEFAULT everywhere (a fresh
    // install or an untouched setting gets Range Selection), exactly as the
    // requirement's <badge>Default</badge> demands; "slide" opts every
    // selection surface into Hold & Slide instead. The gesture code reads the
    // persisted value LIVE at gesture time, so a Save applies immediately —
    // no sheet to reopen, no gesture from the previous mode that can continue.
    private var selectionStyleChoice: String = "range"
    private lateinit var selectionRangeBtn: TextView
    private lateinit var selectionSlideBtn: TextView
    // Pass-8 follow-up (§5 — redesigned save action): the shared SaveDock
    // (see ui/Widgets.kt) — a visually distinct elevated dock (lighter
    // surface than the cards, sheet-level shadow, gradient primary action)
    // with a caption line, a quiet Discard, and proper saving/saved/failed
    // states with double-tap protection. Hidden completely while nothing
    // has changed.
    private lateinit var saveDock: com.lumidrop.x.ui.SaveDock
    @Volatile private var saveInProgress = false
    // ── Pass-29 Part 2 (Fix F): the programmatic-sync guard. Every code ──────
    // path that writes a control's state FROM the persisted preferences (the
    // on-show re-sync of the two transfer toggles below, and the full
    // revert inside discardUnsavedChanges) sets this flag for the duration:
    // refreshSaveVisibility() refuses to evaluate the dirty state while it
    // is set. That makes the "a quick-toggle change can never raise the
    // Save Changes dock" guarantee STRUCTURAL rather than incidental —
    // CompoundButton.setOnCheckedChangeListener and the EditText watchers
    // fire on PROGRAMMATIC writes exactly like user edits, so without the
    // guard any re-sync momentarily feeds the dirty tracker (today it
    // happens to compare clean because computeChanges() reads the live
    // preferences; with the guard, the evaluation simply never runs).
    private var syncingFromPrefs = false
    // Reliability pass §3: the Concurrent Receiving / Concurrent Sending
    // steppers are REMOVED — transfer concurrency is fixed in the engine
    // (GOAT-PASS 2: exactly ONE per direction, by explicit specification —
    // see TransferManager's TRANSFER_CONCURRENCY doc), never a user setting.
    // No field, no value, no save/discard/restart plumbing for them remains.

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = requireContext()

        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(col(R.color.bg_deep))
            layoutParams = ViewGroup.LayoutParams(MATCH, MATCH)
            // Phase 18: cards now cast a real elevation shadow (see
            // Widgets.sectionCard). ViewGroup clips children to its own
            // bounds by default, which was cutting shadows off wherever a
            // card sat near an edge -- most visibly on the credits card.
            // The fix is disabling that clipping, not another background
            // layer to hide the seam.
            clipChildren = false
            clipToPadding = false
        }
        // ── Pass-27 (#4.1): the Settings tab's own inset handling — this tab ────
        // hosts the app's only two INLINE text fields, so its root tracks BOTH
        // the navigation-bar inset and the IME inset (the main window is
        // edge-to-edge on API 30+ and never resizes for any keyboard):
        //   • navigation bar visible → viewport bottom padding = the bar's zone —
        //     exactly the space the window frame itself used to reserve, so the
        //     layout is byte-for-byte what it always was; hidden ("Hide
        //     Navigation Bar" on) → 0 and the content fills down to the tab bar;
        //   • keyboard open → the padding grows to the IME's zone instead, so
        //     the scroll viewport shrinks above the keyboard and the focused
        //     field can always be scrolled into view — the classic edge-to-edge
        //     IME pattern, which with the window's adjustPan mode keeps BOTH of
        //     this tab's fields reachable on every device. No-op below API 30.
        // ── Pass-29 Part 1 (#8): the same listener now also lifts the SaveDock. ──
        // The dock's bottom margin used to be a FIXED 88dp — correct only
        // while the Android navigation bar's zone was zero (bar hidden, or
        // gesture nav with no inset). With the system 3-button bar visible,
        // the floating tabs navigation bar rides up by the bar's inset (it
        // tracks the same live inset in MainActivity), and its zone reaches
        // ~112dp above the window bottom — straight over the dock's lower
        // edge: the reported "Save Changes modal appears behind the tabs
        // navigation bar". The dock's bottom margin now tracks the SAME live
        // inset as the content padding — the bar/IME zone added on top of the
        // 88dp tabs-bar clearance — so the whole Save/Discard dock stays
        // above the tabs bar in every configuration, and stays put
        // byte-for-byte wherever the inset is zero (the already-correct
        // cases).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
                val nav = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.navigationBars()).bottom
                val ime = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.ime()).bottom
                val target = maxOf(nav, ime).coerceAtLeast(0)
                if (v.paddingBottom != target) v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, target)
                if (::saveDock.isInitialized) {
                    val lp = saveDock.view.layoutParams as? android.widget.FrameLayout.LayoutParams
                    if (lp != null) {
                        val dockBottom = dp(88) + target
                        if (lp.bottomMargin != dockBottom) {
                            lp.bottomMargin = dockBottom
                            saveDock.view.layoutParams = lp
                        }
                    }
                }
                insets
            }
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(100))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
            clipChildren = false
            clipToPadding = false
        }

        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(MATCH, WRAP).also { it.bottomMargin = dp(20) }
        }
        headerRow.addView(ImageView(ctx).apply {
            setImageDrawable(Widgets.icon(ctx, R.drawable.ic_settings, col(R.color.text_primary)))
            layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).also { it.marginEnd = dp(8) }
        })
        headerRow.addView(TextView(ctx).apply {
            text = "Settings"
            textSize = 20f; setTextColor(col(R.color.text_primary))
            // ── Pass-39 (#9): one-line title at natural width, with the pill
            // dock as the row's flexible remainder (see the matching note in
            // TransfersFragment) — the header can never grow a second line,
            // and the pill sizes itself to the space (≤152dp).
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        })
        // ── Pass-38 (#7) + Pass-39 (#9): THIS tab's compact music-pill dock — ──
        // the row's flexible remainder, never a fixed width; GONE while empty.
        val musicSlot = android.widget.FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(26), 1f).also { it.marginStart = dp(10) }
            visibility = View.GONE
        }
        musicPillSlot = musicSlot
        headerRow.addView(musicSlot)
        root.addView(headerRow)

        // Network & Discovery ---------------------------------------------------------
        // Pass-11 (Required Implementation 5): the HTTP Port row now lives in
        // Web Client Settings — next to the password and approval setting that
        // configure the same embedded server. This card opens with the
        // discovery name instead.
        val networkCard = Widgets.sectionCard(ctx)
        networkCard.addView(Widgets.sectionHeading(ctx, "NETWORK & DISCOVERY"))
        networkCard.addView(labeledRow("Discovery Name") { infoIcon("Discovery Name", "The name other nearby LumiDrop X apps see when they discover this device over peer-to-peer. Defaults to your phone model.") })
        discoveryNameEdit = Widgets.inputField(ctx, SettingsManager.getDiscoveryName(ctx), android.text.InputType.TYPE_CLASS_TEXT).apply {
            hint = android.os.Build.MODEL ?: "Android Device"
        }
        discoveryNameEdit.watchForChanges()
        networkCard.addView(discoveryNameEdit)
        networkCard.addView(spacer(18))

        networkCard.addView(divider())
        networkCard.addView(spacer(14))
        // Pass-11 (Required Implementation 3): the Hotspot / Wi-Fi / LAN toggle
        // is REMOVED — local network sharing is permanently enabled (the
        // server card, QR picker and discovery no longer depend on any toggle
        // state; see NetworkUtils.getEnabledInterfaces and
        // ServerForegroundService.startServerInternal, which were un-gated).
        networkCard.addView(labeledRow("Default access for new devices") { infoIcon("Default access", "What a brand-new browser session starts as: Transfer Only (send/receive files) or Full Access (also browse your files). Applies the next time someone new joins; per-session changes live in Connected Devices.") })
        networkCard.addView(spacer(8))
        val modeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(MATCH, WRAP) }
        val savedMode = SettingsManager.getDefaultClientMode(ctx)
        defaultModeSelection = savedMode
        defaultModeTransferBtn = TextView(ctx).apply {
            text = "Transfer Only"; textSize = 12f; gravity = Gravity.CENTER
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).also { it.marginEnd = dp(6) }
            isClickable = true; isFocusable = true
        }
        defaultModeFullBtn = TextView(ctx).apply {
            text = "Full Access"; textSize = 12f; gravity = Gravity.CENTER
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            isClickable = true; isFocusable = true
        }
        // Part 4.1: refreshModeButtons() used to be a local fun trapped
        // inside onCreateView's closure -- fine for the click listeners
        // right here, but unreachable from discardUnsavedChanges() below,
        // which also needs to resync these two buttons when a navigate-
        // away-without-saving revert restores defaultModeSelection. Now a
        // member function (below, near the other Save-system helpers) so
        // both call sites share one implementation.
        defaultModeTransferBtn.setOnClickListener { defaultModeSelection = "transfer"; refreshModeButtons(); refreshSaveVisibility() }
        defaultModeFullBtn.setOnClickListener { defaultModeSelection = "full"; refreshModeButtons(); refreshSaveVisibility() }
        refreshModeButtons()
        modeRow.addView(defaultModeTransferBtn); modeRow.addView(defaultModeFullBtn)
        networkCard.addView(modeRow)
        root.addView(networkCard)
        root.addView(spacer(16))

        // Storage & Paths ----------------------------------------------------------------
        val storageCard = Widgets.sectionCard(ctx)
        storageCard.addView(Widgets.sectionHeading(ctx, "STORAGE & PATHS"))
        storageCard.addView(labeledRow("Default Upload Folder") { infoIcon("Upload folder", "Where files sent TO you are saved on this phone. Applies to new receives immediately.") })
        val pathRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(MATCH, WRAP)
        }
        pathEdit = Widgets.inputField(ctx, SettingsManager.getUploadPath(ctx), android.text.InputType.TYPE_CLASS_TEXT).apply {
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(8) }
        }
        pathEdit.watchForChanges()
        pathRow.addView(pathEdit)
        pathRow.addView(TextView(ctx).apply {
            text = "Browse"
            textSize = 13f; setTextColor(col(R.color.text_primary))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_secondary)
            setCompoundDrawablesWithIntrinsicBounds(Widgets.icon(ctx, R.drawable.ic_folder, col(R.color.text_primary)), null, null, null)
            compoundDrawablePadding = dp(6)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            isClickable = true; isFocusable = true
            layoutParams = lp(WRAP, WRAP)
            setOnClickListener {
                com.lumidrop.x.ui.showFolderPickerDialog(ctx, java.io.File(SettingsManager.getUploadPath(ctx))) { path, _ ->
                    pathEdit.setText(path)
                }
            }
        })
        storageCard.addView(pathRow)
        // ── GOAT-PASS 11 (§1 — balanced spacing around the internal-receive ──
        // toggle): the toggle used to sit FLUSH against the directory
        // controls above it (no spacer at all between pathRow and the
        // switch row), reading as one cramped control cluster. The same
        // 12dp rhythm this screen already uses between distinct control
        // groups now separates the directory preview row from the toggle;
        // below the toggle, the section card's own bottom padding plus the
        // standard 16dp section spacer carry the gap — the toggle touches
        // neither neighbor on any screen width.
        storageCard.addView(spacer(12))
        // ── GOAT-PASS 10 (§9 — Open Received Folder Internally): a new ──
        // toggle in the STORAGE & PATHS section, right under the receive
        // destination it governs the opening of. Default OFF — the external
        // file-manager ladder (and its Android 5/6 fallback) remains exactly
        // as before until the user deliberately opts into the internal
        // full-screen Files Sheet. Read live at every "Open Received
        // Folder" tap, so Save is the only commit point this screen needs.
        val openInternalRow = switchRowInfo("Open Received Folder Internally",
            SettingsManager.isOpenReceivedFolderInternally(ctx),
            "Open Received Folder Internally",
            "When ON, the Received tab's Open Received Folder button always opens LumiDrop X's own full-screen Files view at the exact receive destination — instead of Android's external file-manager flow.\n\nWhen OFF (default), the normal external flow is used on devices that support it, with the app's internal full-screen view as the fallback where it doesn't.\n\nThis never changes where received files are saved.")
        openReceivedInternalSwitch = openInternalRow.second
        openReceivedInternalSwitch.setOnCheckedChangeListener { _, _ -> refreshSaveVisibility() }
        storageCard.addView(openInternalRow.first)
        root.addView(storageCard)
        root.addView(spacer(16))

        // Selection ────────────────────────────────────────────────────────────
        // ── GOAT-PASS 8 (§4): the SELECTION INTERACTION STYLE, one global ──
        // choice applied consistently to every selection surface — the Files
        // sheet, the Music sheet, the Received tab, the History tab, the Media
        // picker and the App picker. Range Selection keeps the
        // long-press anchor model (select one item, hold another to select the
        // whole span between them); Hold & Slide (the default since
        // GOAT-PASS 9 §4) makes a held press begin a
        // slide gesture that selects (or, from a selected row, deselects)
        // everything it crosses. The two never run simultaneously — switching
        // takes effect on the very next touch, and the choice persists across
        // restarts.
        val selectionCard = Widgets.sectionCard(ctx)
        selectionCard.addView(Widgets.sectionHeading(ctx, "SELECTION"))
        selectionCard.addView(labeledRow("Selection style") { infoIcon("Selection style",
            "How you select multiple items everywhere in the app — the Files and Music sheets, the Received and History tabs, and the photo and app pickers.\n\nHold & Slide Selection (default): hold an item and slide your finger across others to select them; starting on a selected item deselects as you slide. Long lists auto-scroll faster and faster as your finger nears the edge, and keep scrolling even slightly past it while you keep holding.\n\nRange Selection: long-press an item to select it, then long-press another to select everything between them.") })
        selectionCard.addView(spacer(8))
        selectionRangeBtn = TextView(ctx).apply {
            text = "Range Selection"; textSize = 12f; gravity = Gravity.CENTER
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f).also { it.marginEnd = dp(6) }
            isClickable = true; isFocusable = true
        }
        selectionSlideBtn = TextView(ctx).apply {
            text = "Hold & Slide"; textSize = 12f; gravity = Gravity.CENTER
            setPadding(dp(10), dp(9), dp(10), dp(9))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            isClickable = true; isFocusable = true
        }
        selectionStyleChoice = SettingsManager.getSelectionStyle(ctx)
        selectionRangeBtn.setOnClickListener { selectionStyleChoice = "range"; refreshSelectionStyleButtons(); refreshSaveVisibility() }
        selectionSlideBtn.setOnClickListener { selectionStyleChoice = "slide"; refreshSelectionStyleButtons(); refreshSaveVisibility() }
        refreshSelectionStyleButtons()
        val selectionRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = lp(MATCH, WRAP) }
        selectionRow.addView(selectionRangeBtn); selectionRow.addView(selectionSlideBtn)
        selectionCard.addView(selectionRow)
        root.addView(selectionCard)
        root.addView(spacer(16))

        // Transfer Engine ------------------------------------------------------------------
        // Pass-7: with the declutter pass the card is just its controls —
        // the two auto toggles (Auto-Send now the plain mirror of
        // Auto-Accept: pick = sent, no queue), the fixed one-at-a-time
        // engine contract as a quiet info line, the popup toggle and the
        // WebUI link. Every long paragraph now lives behind an (i).
        val transferCard = Widgets.sectionCard(ctx)
        transferCard.addView(Widgets.sectionHeading(ctx, "TRANSFER ENGINE"))
        // ── Pass-26 (#5): Automatic Server Startup ──────────────────────────
        // First control of the card — it governs whether the transfer
        // engine's server comes up on its own at all. ON by default (the
        // app's behavior since the first release), so existing installs see
        // no change until they turn it off. Like every toggle here it takes
        // effect on SAVE and applies to SUBSEQUENT launches; flipping it
        // never stops an already-running server.
        val autoStartRow = switchRowInfo("Automatic Server Startup",
            SettingsManager.isAutoServerStart(ctx),
            "Automatic Server Startup", "Automatically start the server when the app launches. Turn it off to start the server manually with the Start button on the Send tab — the server then stays stopped after each fresh launch until you start it.")
        autoServerStartSwitch = autoStartRow.second
        autoServerStartSwitch.setOnCheckedChangeListener { _, _ -> refreshSaveVisibility() }
        transferCard.addView(autoStartRow.first)
        transferCard.addView(spacer(16))
        transferCard.addView(divider())
        transferCard.addView(spacer(14))
        val autoAcceptRow = switchRowInfo("Quick-accept incoming files",
            SettingsManager.isAutoAcceptUploads(ctx),
            "Quick-accept", "When on, files sent to you save automatically — no Accept tap. When off, they wait in the Incoming list until you accept or decline them.")
        autoAcceptSwitch = autoAcceptRow.second
        autoAcceptSwitch.setOnCheckedChangeListener { _, _ -> refreshSaveVisibility() }
        transferCard.addView(autoAcceptRow.first)
        transferCard.addView(spacer(12))
        // ── Pass-7 (Auto-Send remake) ─────────────────────────────────────
        // The pass-6 watched-folder engine (folder picker / scope chips /
        // per-device ledger) is deleted wholesale. Auto-Send is now ONE
        // boolean applied at selection time in SendFragment.stageOrSendItems,
        // exactly the way Auto-Accept is one boolean applied when an offer
        // arrives: ON = every item picked from any Quick Share tile leaves
        // INSTANTLY for all connected devices (no Send tap, nothing ever
        // staged in the queue card); OFF = the classic stage-then-Send flow.
        // The receiver's own Auto-Accept still decides what lands without a
        // tap on their side.
        val autoSendRow = switchRowInfo("Quick-send to connected devices",
            SettingsManager.isAutoSendEnabled(ctx),
            "Quick-send", "When on, anything you pick from a Quick Share tile is sent instantly to every device connected at that moment — browsers get it in their incoming list, LumiDrop X apps get a push — with no Send tap and nothing sitting in a queue. If nothing is connected yet, the send holds as 'Waiting' and fires the moment a device joins. When off, picks are staged and you press Send.")
        autoSendSwitch = autoSendRow.second
        autoSendSwitch.setOnCheckedChangeListener { _, _ -> refreshSaveVisibility() }
        transferCard.addView(autoSendRow.first)
        transferCard.addView(spacer(16))
        transferCard.addView(divider())
        transferCard.addView(spacer(14))
        // ── GOAT-PASS 14 (Phase 1, §1.2): the "Send queue as one ZIP" toggle ──
        // in the TRANSFERS card, right under Auto-send — the natural home for
        // a what-gets-sent behavior. Read live at the exact moment of every
        // send operation (SendFragment.zipSendModeOn), so a Save here applies
        // to the very next Send/Auto-Send with nothing to restart. Phase 2's
        // Connected-Devices-card toggle writes this SAME preference, so the
        // two surfaces can never disagree (the on-show sync below keeps this
        // switch current when that happens, exactly like Auto-send's own
        // quick-toggle does).
        val zipSendRow = switchRowInfo("Send queue as one ZIP",
            SettingsManager.isZipSendEnabled(ctx),
            "Send as one ZIP", "When on, every send operation — a Send tap or a Quick-Send — packages the entire selected batch as ONE ZIP archive and delivers that single file to browsers and LumiDrop X apps alike: one incoming item to accept or decline, one transfer row, one archive on disk. Folders keep their structure and your per-child removals inside the archive; text messages ride as .txt entries. When off, each item is sent individually exactly as before. Either way, each send operation is its own batch — two separate sends never merge into one archive.")
        zipSendSwitch = zipSendRow.second
        zipSendSwitch.setOnCheckedChangeListener { _, _ -> refreshSaveVisibility() }
        transferCard.addView(zipSendRow.first)
        transferCard.addView(spacer(16))
        transferCard.addView(divider())
        transferCard.addView(spacer(14))
        // Pass-8 (§6): the explanatory-only "One transfer at a time, per
        // direction" info line is REMOVED — it contained no control, and the
        // behavior it described is a fixed engine guarantee the user cannot
        // change; the (i) dialogs on the switches that remain carry anything
        // actually needed to make a decision.
        val floatRow = switchRowInfo("Show Transfers as Floating Popup",
            SettingsManager.isFloatingTransferPopup(ctx),
            "Floating Popup", "A compact live overlay over Send/Settings while transfers run. The Transfers tab always shows full details either way.")
        floatPopupSwitch = floatRow.second
        floatPopupSwitch.setOnCheckedChangeListener { _, _ -> refreshSaveVisibility() }
        transferCard.addView(floatRow.first)
        transferCard.addView(spacer(16))
        transferCard.addView(divider())
        transferCard.addView(spacer(14))
        // ── SELECTION FINAL PASS 1 (#13.3): the Received tab badge's switch — ──
        // OFF hides that pill's new-items counter (nothing else about the
        // tab, its contents or its seen-state tracking changes).
        val receivedBadgeRow = switchRowInfo("Received tab new-items badge",
            SettingsManager.isReceivedTabBadgeEnabled(ctx),
            "Received tab badge", "When on, the Received tab shows its circular counter of items received since you last opened the tab. When off, the badge stays hidden — the tab itself, its contents and everything else work exactly the same.")
        receivedBadgeSwitch = receivedBadgeRow.second
        receivedBadgeSwitch.setOnCheckedChangeListener { _, _ -> refreshSaveVisibility() }
        transferCard.addView(receivedBadgeRow.first)
        transferCard.addView(spacer(12))
        // ── SELECTION FINAL PASS 1 (#13.3): the History tab badge's switch. ──
        val historyBadgeRow = switchRowInfo("History tab new-items badge",
            SettingsManager.isHistoryTabBadgeEnabled(ctx),
            "History tab badge", "When on, the History tab shows its circular counter of items sent or received since you last opened the tab. When off, the badge stays hidden — the tab itself, its contents and everything else work exactly the same.")
        historyBadgeSwitch = historyBadgeRow.second
        historyBadgeSwitch.setOnCheckedChangeListener { _, _ -> refreshSaveVisibility() }
        transferCard.addView(historyBadgeRow.first)
        transferCard.addView(spacer(16))
        transferCard.addView(divider())
        transferCard.addView(spacer(14))
        val webUiBtn = TextView(ctx).apply {
            // Pass-11 (Required Implementation 5): renamed — this screen now
            // also carries the HTTP port, password, and connection-approval
            // settings that moved out of the main Settings tab.
            text = "Web Client Settings"
            textSize = 14f
            setTextColor(col(R.color.text_primary))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_secondary)
            setPadding(dp(14), dp(13), dp(14), dp(13))
            compoundDrawablePadding = dp(8)
            setCompoundDrawablesWithIntrinsicBounds(Widgets.icon(ctx, R.drawable.ic_globe, col(R.color.text_primary)), null,
                Widgets.icon(ctx, R.drawable.ic_chevron_right, col(R.color.text_muted)), null)
            isClickable = true; isFocusable = true
            layoutParams = lp(MATCH, WRAP)
            setOnClickListener { startActivity(Intent(requireContext(), WebUiSettingsActivity::class.java)) }
        }
        transferCard.addView(webUiBtn)
        root.addView(transferCard)
        root.addView(spacer(16))

        // Screen & Battery -------------------------------------------------------------------
        val screenCard = Widgets.sectionCard(ctx)
        screenCard.addView(Widgets.sectionHeading(ctx, "SCREEN & BATTERY"))
        val keepRow = switchRowInfo("Keep screen on while serving",
            SettingsManager.isKeepScreenOn(ctx),
            "Keep screen on", "Holds the screen awake while the server runs, so Android never dozes the transfer engine mid-send. Uses more battery — turn it off for long idle sessions.")
        keepScreenSwitch = keepRow.second
        keepScreenSwitch.setOnCheckedChangeListener { _, _ -> refreshSaveVisibility() }
        screenCard.addView(keepRow.first)
        screenCard.addView(spacer(16))
        screenCard.addView(divider())
        screenCard.addView(spacer(14))
        // ── Pass-11 toggle, renamed for Pass-12 ──────────────────────────────
        // Per direct feedback the feature now hides ONLY the Android navigation
        // buttons (Back / Home / Recents); the status bar stays fully visible.
        // The label and description say exactly what it does — no "full screen"
        // overpromise. Same persisted key as pass 11 (full_screen), so an
        // upgrade keeps the user's preference and simply changes what it does.
        // Implemented in MainActivity.applyFullScreenBars — WindowInsetsController
        // on navigationBars() alone for API 30+, the IMMERSIVE_STICKY +
        // HIDE_NAVIGATION flags (no FULLSCREEN flags) for API 21-29.
        val fullRow = switchRowInfo("Hide Navigation Bar",
            SettingsManager.isFullScreen(ctx),
            "Hide navigation bar", "Hides only the Android navigation buttons (Back, Home, Recents) for a cleaner, edge-to-edge look — the status bar with your clock, battery, and signal stays visible. Swipe from the bottom edge to reveal the buttons briefly; they hide again on their own. Turn it off to restore the buttons.")
        fullScreenSwitch = fullRow.second
        fullScreenSwitch.setOnCheckedChangeListener { _, _ -> refreshSaveVisibility() }
        screenCard.addView(fullRow.first)
        root.addView(screenCard)
        root.addView(spacer(16))

        // ── GOAT-PASS 13 (§10): the DIAGNOSTICS card (the "Application Logs"
        // row that opened the in-app log viewer) is removed together with the
        // whole diagnostic logging system — AppLog, DiagLog, LogsActivity and
        // their infrastructure. The Settings screen carries no diagnostics
        // surface anymore.

        // Save note stays inline; the button itself is a floating action at
        // the fragment root now (see below onCreateView's return), not a
        // permanent bar-style button that sits there whether or not there's
        // anything to save.
        // Pass-11: the port / password / connection-approval trio moved to Web
        // Client Settings (they configure the embedded web server) — this note
        // now points at their new home.
        root.addView(descText("Server port, password, and connection approval live in Web Client Settings."))
        root.addView(spacer(24))

        // ── PASS 19 (§5+§6): the About section is now the dedicated,
        // professionally designed identity + updates screen — see
        // update/AboutUpdateSection.kt. The old inline credits card (logo,
        // name, version chip, two link rows) is fully replaced by it: real
        // launcher icon, genuine package metadata, the creator credit, the
        // project repository, and the complete §6 update surface (auto-check
        // toggle with 24h pacing, manual check, in-app download with progress
        // and safe resumption, package + signing verification against THIS
        // installation, and the Android installer handoff).
        // local non-null alias so the lambdas below capture a stable ref
        var sectionRef: com.lumidrop.x.update.AboutUpdateSection.Section? = null
        fun sec() = sectionRef!!
        aboutSection = com.lumidrop.x.update.AboutUpdateSection.build(
            ctx, ::dp, { res -> col(res) }, root,
            onCheckNow = { manual ->
                sec().render(com.lumidrop.x.update.UpdateManager.State(
                    com.lumidrop.x.update.UpdateManager.Phase.CHECKING,
                    com.lumidrop.x.update.UpdateManager.installedVersionName(ctx)))
                Thread {
                    com.lumidrop.x.update.UpdateManager.checkForUpdates(ctx, manual)
                    android.os.Handler(android.os.Looper.getMainLooper()).post { sectionRef?.render() }
                }.start()
            },
            onDownload = { release ->
                cancelRequested.set(false)
                Thread {
                    com.lumidrop.x.update.UpdateManager.download(ctx, release) { cancelRequested.get() }
                    val st = com.lumidrop.x.update.UpdateManager.state
                    if (st.phase == com.lumidrop.x.update.UpdateManager.Phase.DOWNLOAD_COMPLETE) {
                        // §6.6: verify BEFORE any install handoff
                        val apk = com.lumidrop.x.update.UpdateManager.apkFileFor(ctx, release)
                        val verdict = com.lumidrop.x.update.UpdateManager.verify(ctx, release, apk)
                        if (verdict is com.lumidrop.x.update.UpdateManager.VerifyResult.Blocked) {
                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                android.widget.Toast.makeText(ctx, verdict.reason, android.widget.Toast.LENGTH_LONG).show()
                                sectionRef?.render()
                            }
                        }
                    }
                    android.os.Handler(android.os.Looper.getMainLooper()).post { sectionRef?.render() }
                }.start()
            },
            onCancelDownload = { cancelRequested.set(true) },
            onInstall = { release ->
                val apk = com.lumidrop.x.update.UpdateManager.apkFileFor(ctx, release)
                val ok = com.lumidrop.x.update.UpdateManager.launchInstall(ctx, apk)
                if (!ok) android.widget.Toast.makeText(ctx,
                    "The installer could not be opened", android.widget.Toast.LENGTH_LONG).show()
                sectionRef?.render()
            }
        )
        sectionRef = aboutSection
        aboutSection?.attach()

        scroll.addView(root)

        // ── Pass-8 follow-up (§5 — redesigned save action) ────────────────
        // The shared SaveDock: full-width bottom bar (above the nav
        // clearance), discoverable at the thumb, carrying an honest caption
        // (restart notice / saving / saved / error), a quiet Discard, and
        // one unmistakable gradient Save button with real states and
        // double-tap protection. Hidden completely while nothing changed.
        val frame = FrameLayout(ctx).apply { layoutParams = ViewGroup.LayoutParams(MATCH, MATCH) }
        frame.addView(scroll)
        saveDock = com.lumidrop.x.ui.SaveDock(ctx).also { dock ->
            dock.discard.setOnClickListener {
                discardUnsavedChanges()
                android.widget.Toast.makeText(context, "Changes discarded", android.widget.Toast.LENGTH_SHORT).show()
            }
            dock.action.setOnClickListener { saveSettings() }
            frame.addView(dock.view, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).also {
                it.leftMargin = dp(16); it.rightMargin = dp(16)
                it.bottomMargin = dp(88)   // clears the floating bottom nav pill
                it.topMargin = dp(8)
            })
        }
        // ── Pass-38 (#7): this tab's header slot joins the music pill's dock ──
        // pool — but ONLY when this tab is actually the visible one (all three
        // tabs create their views during the shell's single setup transaction;
        // see the matching note in TransfersFragment). onHiddenChanged
        // re-registers on every later entry into the Settings tab.
        if (!isHidden) com.lumidrop.x.ui.MusicPlayback.attachCompactHost(musicPillSlot)
        return frame
    }

    override fun onResume() {
        super.onResume()
        // §6.1/§6.10: every resume reconciles (a version change means an
        // install SUCCEEDED — report and clean the temp APK) and runs the
        // paced auto-check (24h interval, toggle-respecting, never during
        // an active download/install).
        aboutSection?.onResume()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // ── Pass-38 (#7): the dock dies with this view — release it before
        // the slot reference does, so the player never holds a dead slot.
        musicPillSlot?.let { com.lumidrop.x.ui.MusicPlayback.detachCompactHost(it) }
        musicPillSlot = null
    }

    /** §5: the dock's caption + button state. Maps the old string states
     *  onto SaveDock.State one-to-one. */
    private fun applySaveBarState(state: String) {
        if (!::saveDock.isInitialized) return
        saveDock.setState(when (state) {
            "saving" -> com.lumidrop.x.ui.SaveDock.State.SAVING
            "saved"  -> com.lumidrop.x.ui.SaveDock.State.SAVED
            "error"  -> com.lumidrop.x.ui.SaveDock.State.ERROR
            else     -> com.lumidrop.x.ui.SaveDock.State.IDLE
        }, idleCaption = "Unsaved changes")
    }

    /** Fires from MainActivity.switchTab()'s FragmentTransaction show()/
     *  hide() calls -- the mechanism this whole app uses instead of
     *  navigating to/from a separate Settings screen (see MainActivity's
     *  class doc). Because this fragment's view is never destroyed on a
     *  tab switch (hide() just toggles visibility), any typed-but-unsaved
     *  edit would otherwise still be sitting in these EditTexts -- and the
     *  floating Save button still showing -- the next time the user came
     *  back to this tab. There was no "leaving without saving" moment at
     *  all. Part 4.1 spec: leaving without saving must revert/discard.
     *  Reloading on the transition INTO hidden (not out of it) is what
     *  makes the revert happen exactly once, silently, at the moment the
     *  user actually leaves -- not on the way back in, which would flash
     *  stale values before overwriting them. */
    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) discardUnsavedChanges()
        else {
            // ── Pass-38 (#7): this tab is the visible one — its header slot ──
            // is the music pill's dock now (one shared player, one compact
            // pill, whichever tab is on screen).
            musicPillSlot?.let { com.lumidrop.x.ui.MusicPlayback.attachCompactHost(it) }
            // ── Pass-27 (#4.1): a hidden tab may have missed an insets dispatch ──
            // while the navigation-bar inset changed — re-request one so this
            // tab's bottom clearance is re-derived against the live geometry
            // (idempotent; a no-op when nothing changed and on API < 30).
            try { view?.requestApplyInsets() } catch (e: Exception) { /* not attached */ }
            // ── Pass-29 Part 1 (#4C): re-sync the two transfer toggles from the ──
            // persisted preferences. Safe by construction: this tab reverts
            // every unsaved edit the moment it is LEFT (discardUnsavedChanges
            // above), so at the instant it becomes visible again there are
            // never staged edits to clobber — the only thing that can have
            // moved these two values meanwhile is the Send tab's quick-toggle
            // icons writing the SAME shared preference, which is exactly what
            // must show up here immediately.
            // ── Pass-29 Part 2 (Fix F): the sync runs under the programmatic- ──
            // sync guard, so the isChecked writes (which fire the switches'
            // change listeners exactly like a user tap) can never feed the
            // dirty tracker — a quick-toggle flip made on the Send tab is
            // reflected here instantly and silently, with the Save Changes
            // dock staying hidden. Only a REAL edit inside this tab (a user
            // tap on a switch, typing in a field, a mode-chip pick) raises
            // the dock.
            if (::autoAcceptSwitch.isInitialized && ::autoSendSwitch.isInitialized && ::zipSendSwitch.isInitialized) {
                val ctx = requireContext()
                syncingFromPrefs = true
                try {
                    autoAcceptSwitch.isChecked = SettingsManager.isAutoAcceptUploads(ctx)
                    autoSendSwitch.isChecked = SettingsManager.isAutoSendEnabled(ctx)
                    // GOAT-PASS 14 (§1.2): same live re-sync — Phase 2's
                    // Connected-Devices-card toggle writes this preference
                    // directly, and this screen must reflect it on return.
                    zipSendSwitch.isChecked = SettingsManager.isZipSendEnabled(ctx)
                    // SELECTION FINAL PASS 1 (#13.3): same live re-sync.
                    receivedBadgeSwitch.isChecked = SettingsManager.isReceivedTabBadgeEnabled(ctx)
                    historyBadgeSwitch.isChecked = SettingsManager.isHistoryTabBadgeEnabled(ctx)
                } finally { syncingFromPrefs = false }
                // The dock was forced hidden by the leave-discard; re-assert
                // the hidden end-state so no transient evaluation can leave
                // a phantom "Unsaved changes" dock over a tab whose fields
                // all equal the persisted baseline.
                if (!saveInProgress) saveDock.hideNow()
            }
        }
    }

    /** Resets every field back to whatever is currently persisted in
     *  SettingsManager, discarding anything typed/toggled but not saved.
     *  Reuses the exact same getters computeChanges() compares against, so
     *  "revert" and "what counts as changed" can never drift apart. */
    private fun discardUnsavedChanges() {
        if (!::saveDock.isInitialized) return
        val ctx = requireContext()
        // ── Pass-29 Part 2 (Fix F): the whole revert runs under the ─────────
        // programmatic-sync guard. setText/isChecked writes fire the very
        // same watchers a user edit does, and MID-REVERT the fields have NOT
        // all been reset yet — an unguarded listener fire there could read
        // a half-reverted form as "changed" and flash the dock. Guarded, the
        // dirty tracker stays untouched until every field is back at its
        // persisted value and the forced hide below lands.
        syncingFromPrefs = true
        try {
            pathEdit.setText(SettingsManager.getUploadPath(ctx))
            discoveryNameEdit.setText(SettingsManager.getDiscoveryName(ctx))
            keepScreenSwitch.isChecked = SettingsManager.isKeepScreenOn(ctx)
            fullScreenSwitch.isChecked = SettingsManager.isFullScreen(ctx)
            autoServerStartSwitch.isChecked = SettingsManager.isAutoServerStart(ctx)
            autoAcceptSwitch.isChecked = SettingsManager.isAutoAcceptUploads(ctx)
            autoSendSwitch.isChecked = SettingsManager.isAutoSendEnabled(ctx)
            zipSendSwitch.isChecked = SettingsManager.isZipSendEnabled(ctx)
            floatPopupSwitch.isChecked = SettingsManager.isFloatingTransferPopup(ctx)
            // SELECTION FINAL PASS 1 (#13.3): same revert discipline.
            receivedBadgeSwitch.isChecked = SettingsManager.isReceivedTabBadgeEnabled(ctx)
            historyBadgeSwitch.isChecked = SettingsManager.isHistoryTabBadgeEnabled(ctx)
            defaultModeSelection = SettingsManager.getDefaultClientMode(ctx)
            selectionStyleChoice = SettingsManager.getSelectionStyle(ctx)
            refreshModeButtons()
            refreshSelectionStyleButtons()
        } finally { syncingFromPrefs = false }
        // Snap straight to the hidden end-state rather than calling
        // refreshSaveVisibility()'s animated path -- this all runs while
        // the tab is already off-screen (see onHiddenChanged above), so an
        // animation is wasted work nobody is there to see, and cancelling
        // any in-flight show/hide animation first avoids it fighting with
        // this forced end-state.
        saveDock.hideNow()
        saveInProgress = false
    }

    /** Syncs the selection-style chip buttons' visuals to ──
     *  [selectionStyleChoice] — the same member-function shape
     *  refreshModeButtons uses, so the click listeners and the
     *  leave-without-saving revert share one implementation. */
    private fun refreshSelectionStyleButtons() {
        val ctx = requireContext()
        selectionRangeBtn.background = ContextCompat.getDrawable(ctx, if (selectionStyleChoice == "range") R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
        selectionRangeBtn.setTextColor(if (selectionStyleChoice == "range") android.graphics.Color.WHITE else col(R.color.text_secondary))
        selectionSlideBtn.background = ContextCompat.getDrawable(ctx, if (selectionStyleChoice == "slide") R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
        selectionSlideBtn.setTextColor(if (selectionStyleChoice == "slide") android.graphics.Color.WHITE else col(R.color.text_secondary))
    }

    /** Syncs the two mode-selection chip buttons' visuals to
     *  defaultModeSelection. Called both from onCreateView's click
     *  listeners and from discardUnsavedChanges() above, so this must stay
     *  a member function rather than a local closure. */
    private fun refreshModeButtons() {
        val ctx = requireContext()
        defaultModeTransferBtn.background = ContextCompat.getDrawable(ctx, if (defaultModeSelection == "transfer") R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
        defaultModeTransferBtn.setTextColor(if (defaultModeSelection == "transfer") android.graphics.Color.WHITE else col(R.color.text_secondary))
        defaultModeFullBtn.background = ContextCompat.getDrawable(ctx, if (defaultModeSelection == "full") R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
        defaultModeFullBtn.setTextColor(if (defaultModeSelection == "full") android.graphics.Color.WHITE else col(R.color.text_secondary))
    }

    /** Called from every input's own change listener. Cheap enough to run on
     *  every keystroke/toggle: reuses computeChanges() below rather than a
     *  separate parallel diff, so this can never disagree with what
     *  saveSettings() actually persists. */
    private fun refreshSaveVisibility() {
        if (!::saveDock.isInitialized) return
        // ── Pass-29 Part 2 (Fix F): never evaluate the dirty state while a ──
        // PROGRAMMATIC sync is writing control states from the persisted
        // preferences (the on-show quick-toggle re-sync, the leave-discard
        // revert). Only genuine user edits — a switch tap, typing, a mode
        // chip pick — may raise the dock, so a quick-toggle flip on the
        // Send tab can never surface the Save Changes dock here.
        if (syncingFromPrefs) return
        val show = computeChanges().second && !saveInProgress
        val currentlyShown = saveDock.isShown
        if (show == currentlyShown) {
            if (show) applySaveBarState("idle")
            return
        }
        if (show) {
            applySaveBarState("idle")
            saveDock.show()
        } else {
            saveDock.hide()
        }
    }

    /** first = needsRestart, second = anythingChanged at all. Single source
     *  of truth for "is there anything to save" -- both the live floating-
     *  button check and the actual save use this, so they can't drift.
     *  Pass-11: every restart-relevant setting (port / password / connection
     *  approval) moved to Web Client Settings, so nothing on THIS screen
     *  needs a server restart anymore — the flag stays for shape
     *  compatibility and is always false. */
    private fun computeChanges(): Pair<Boolean, Boolean> {
        val ctx = requireContext()
        val needsRestart = false
        val anyChanged =
            pathEdit.text.toString().trim() != SettingsManager.getUploadPath(ctx) ||
            discoveryNameEdit.text.toString().trim() != SettingsManager.getDiscoveryName(ctx) ||
            keepScreenSwitch.isChecked != SettingsManager.isKeepScreenOn(ctx) ||
            fullScreenSwitch.isChecked != SettingsManager.isFullScreen(ctx) ||
            autoServerStartSwitch.isChecked != SettingsManager.isAutoServerStart(ctx) ||
            autoAcceptSwitch.isChecked != SettingsManager.isAutoAcceptUploads(ctx) ||
            autoSendSwitch.isChecked != SettingsManager.isAutoSendEnabled(ctx) ||
            zipSendSwitch.isChecked != SettingsManager.isZipSendEnabled(ctx) ||
            floatPopupSwitch.isChecked != SettingsManager.isFloatingTransferPopup(ctx) ||
            receivedBadgeSwitch.isChecked != SettingsManager.isReceivedTabBadgeEnabled(ctx) ||
            historyBadgeSwitch.isChecked != SettingsManager.isHistoryTabBadgeEnabled(ctx) ||
            openReceivedInternalSwitch.isChecked != SettingsManager.isOpenReceivedFolderInternally(ctx) ||
            defaultModeSelection != SettingsManager.getDefaultClientMode(ctx) ||
            selectionStyleChoice != SettingsManager.getSelectionStyle(ctx)
        return Pair(needsRestart, anyChanged)
    }

    private fun saveSettings() {
        val ctx = requireContext()
        // §5: double-tap protection — a save already in flight swallows any
        // further tap until it finishes.
        if (saveInProgress) return
        saveInProgress = true
        applySaveBarState("saving")

        // Phase 2 of 10: the default new-session mode is read LIVE by
        // getDeviceMode() for every client without an explicit per-session
        // override — so changing it must propagate to those already-
        // connected clients immediately (their next state payload), not
        // silently wait for some unrelated version bump. Captured before
        // the setters write the new value.
        val defaultModeChanged = defaultModeSelection != SettingsManager.getDefaultClientMode(ctx)
        // Pass-11 (Required Implementation 4): captured BEFORE the write so
        // the immersive system bars flip only on a genuine change.
        val fullScreenChanged = fullScreenSwitch.isChecked != SettingsManager.isFullScreen(ctx)

        SettingsManager.setUploadPath(ctx, pathEdit.text.toString().trim())
        SettingsManager.setDiscoveryName(ctx, discoveryNameEdit.text.toString().trim())
        SettingsManager.setKeepScreenOn(ctx, keepScreenSwitch.isChecked)
        SettingsManager.setFullScreen(ctx, fullScreenSwitch.isChecked)
        // Pass-26 (#5): persisted like every other preference; deliberately
        // NO server interaction here — the toggle is only ever read at
        // launch (SendFragment.onViewCreated), so saving it can never stop
        // or start a running server.
        SettingsManager.setAutoServerStart(ctx, autoServerStartSwitch.isChecked)
        SettingsManager.setAutoAcceptUploads(ctx, autoAcceptSwitch.isChecked)
        SettingsManager.setAutoSendEnabled(ctx, autoSendSwitch.isChecked)
        // GOAT-PASS 14 (§1.2): persisted like every other preference; read
        // live at the moment of every send operation, so nothing to restart
        // or notify — the next Send/Auto-Send simply follows the new mode.
        SettingsManager.setZipSendEnabled(ctx, zipSendSwitch.isChecked)
        SettingsManager.setFloatingTransferPopup(ctx, floatPopupSwitch.isChecked)
        // ── SELECTION FINAL PASS 1 (#13.3): persisted like every other ──
        // preference; read live at every badge paint, so nothing to restart.
        SettingsManager.setReceivedTabBadgeEnabled(ctx, receivedBadgeSwitch.isChecked)
        SettingsManager.setHistoryTabBadgeEnabled(ctx, historyBadgeSwitch.isChecked)
        // ── GOAT-PASS 10 (§9): persisted like every other preference; read ──
        // live at every Open Received Folder tap, so the change applies at
        // the next opening (or immediately after this Save) with nothing to
        // restart or notify.
        SettingsManager.setOpenReceivedFolderInternally(ctx, openReceivedInternalSwitch.isChecked)
        SettingsManager.setDefaultClientMode(ctx, defaultModeSelection)
        // ── GOAT-PASS 8 (§4): persisted like every other preference; applied ──
        // immediately by every gesture site (each reads the live value at
        // gesture time), so nothing else needs informing.
        SettingsManager.setSelectionStyle(ctx, selectionStyleChoice)

        // Pass-11: apply the Full Screen toggle the moment Save commits — no
        // restart needed (MainActivity re-asserts it on every window-focus
        // change and startup while the setting stays on).
        if (fullScreenChanged) {
            try { (activity as? com.lumidrop.x.MainActivity)?.applyFullScreenFromSettings() } catch (e: Exception) {}
        }

        val svc = com.lumidrop.x.service.ServerForegroundService.instance
        svc?.refreshNotification()
        // Phase 2 of 10: bump the unified state version so every connected
        // client whose session follows the DEFAULT mode (no per-session
        // override) applies the new mode from its next SSE/poll payload —
        // the same channel a per-device toggle uses. Sessions with an
        // explicit override are untouched (their override pins them, per
        // the isolation rule).
        if (defaultModeChanged) {
            svc?.server?.stateVersion?.incrementAndGet()
        }
        Toast.makeText(ctx, "Settings saved.", Toast.LENGTH_SHORT).show()

        // §5: honest saved state, then a quiet auto-hide once everything
        // committed cleanly.
        applySaveBarState("saved")
        saveInProgress = false
        refreshSaveVisibility()
        saveDock.view.postDelayed({
            if (context != null && !computeChanges().second) refreshSaveVisibility()
        }, 1400)
        // Deliberately no finish() here (unlike the old Activity version) --
        // Settings is now a persistent bottom-nav tab, not a screen you
        // navigate back out of, so staying put after saving is the correct
        // behavior (matches how most system Settings apps behave inline).
    }

    // Widget helpers ------------------------------------------------------------------------

    private fun col(res: Int) = ContextCompat.getColor(requireContext(), res)
    private fun EditText.watchForChanges() {
        addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { refreshSaveVisibility() }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
    }
    private val MATCH get() = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP  get() = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    private fun spacer(d: Int) = View(requireContext()).apply { layoutParams = LinearLayout.LayoutParams(MATCH, dp(d)) }
    private fun divider() = View(requireContext()).apply {
        setBackgroundColor(col(R.color.divider))
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1))
    }
    private fun tv(text: String, size: Float, colorRes: Int) = TextView(requireContext()).apply {
        this.text = text; textSize = size; setTextColor(col(colorRes)); layoutParams = lp(MATCH, WRAP)
    }
    private fun descText(text: String) = tv(text, 12f, R.color.text_muted).also {
        (it.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
    }

    // ── Pass-7 (declutter) info affordances ─────────────────────────────────────
    // The (i) pattern: labels and switches stand alone; anything that needs
    // more than a line carries a small muted (i) glyph that opens the full
    // explanation in an LdxDialog — the modern settings idiom, replacing
    // the paragraph-under-every-control layout this screen used to have.

    /** A small (i) glyph that opens [title]/[body] in an LdxDialog. */
    private fun infoIcon(title: String, body: String): ImageView = ImageView(requireContext()).apply {
        setImageDrawable(Widgets.icon(requireContext(), R.drawable.ic_info, col(R.color.text_muted)))
        isClickable = true; isFocusable = true
        background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_icon_ripple)
        setPadding(dp(3), dp(3), dp(3), dp(3))
        layoutParams = LinearLayout.LayoutParams(dp(22), dp(22)).also { it.marginStart = dp(6) }
        setOnClickListener {
            com.lumidrop.x.ui.showLdxDialog(
                context = requireContext(),
                title = title,
                message = body,
                iconRes = R.drawable.ic_info,
                actions = listOf(com.lumidrop.x.ui.LdxDialogAction("Got it", com.lumidrop.x.ui.LdxButtonStyle.PRIMARY)),
            )
        }
    }

    /** Section-label row (12sp muted) with an optional trailing (i) glyph
     *  supplied by [buildTrailing] — for inputs whose label needs context. */
    private fun labeledRow(label: String, buildTrailing: () -> View): LinearLayout =
        LinearLayout(requireContext()).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(MATCH, WRAP).also { it.bottomMargin = dp(6) }
            addView(TextView(requireContext()).apply {
                text = label; textSize = 12f; setTextColor(col(R.color.text_muted))
                layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
            })
            addView(buildTrailing())
        }

    /** A switch row with its (i): [SwitchCompat] carries the label (weight
     *  1), the (i) sits at the row's end, vertically centered with it. */
    private fun switchRowInfo(label: String, initial: Boolean, title: String, body: String): Pair<LinearLayout, SwitchCompat> {
        val ctx = requireContext()
        val sw = SwitchCompat(ctx).apply {
            text = label; isChecked = initial
            setTextColor(col(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = lp(MATCH, WRAP)
            addView(sw)
            addView(infoIcon(title, body))
        }
        return row to sw
    }

    /** A quiet one-line info row — kept for any future statement row; the
     *  pass-8 declutter removed its last use (the one-at-a-time info line,
     *  which was explanatory-only). */
    private fun infoLine(label: String, body: String): LinearLayout =
        labeledRow(label) { infoIcon(label, body) }
}
