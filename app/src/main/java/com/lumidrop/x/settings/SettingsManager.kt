package com.lumidrop.x.settings

import android.content.Context
import android.os.Environment
import java.io.File

object SettingsManager {
    private const val PREFS = "lumidrop_prefs"

    fun getPort(ctx: Context) = prefs(ctx).getInt("port", 8888)
    fun setPort(ctx: Context, v: Int) = edit(ctx) { putInt("port", v) }

    fun getUploadPath(ctx: Context): String {
        // Default is a dedicated subfolder rather than dumping received
        // files directly into the shared Download folder — keeps them
        // easy to find and doesn't mix them in with everything else the
        // user downloads from browsers/other apps. Existing installs that
        // already have a persisted "upload_path" (including ones still
        // pointed at plain Download from before this default changed) are
        // completely unaffected — this fallback only applies when nothing
        // has been saved yet.
        val def = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "LumiDrop X").absolutePath
        val stored = prefs(ctx).getString("upload_path", null) ?: def
        // ── Pass-44 (§8): the availability fallback. The stored destination ──
        // may live on removable storage (SD card / USB OTG) the user chose.
        // If that volume is removed, unmounted or its permission was
        // revoked, receives must not stall or fail on a dead path: they
        // fall back to the DEFAULT location for as long as the configured
        // one is unusable. The STORED SETTING IS NEVER MODIFIED — when the
        // volume returns, receives follow the setting again (the user's
        // choice keeps governing; this is per-use behavior, not a silent
        // settings rewrite). The change is reported once per unavailability
        // episode (toast + log), not per call — getUploadPath is hit on
        // every receive path, restore scan and listing.
        var path = stored
        if (stored != def) {
            val dir = File(stored)
            val usable = try { dir.isDirectory && dir.canWrite() } catch (e: Exception) { false }
            if (!usable) {
                path = def
                if (!_uploadFallbackAnnounced) {
                    _uploadFallbackAnnounced = true
                    try {
                        android.util.Log.w("LumiDrop", "Upload destination unavailable: $stored — receiving to $def")
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            try {
                                android.widget.Toast.makeText(ctx.applicationContext,
                                    "SD card / USB storage unavailable — receiving to $def",
                                    android.widget.Toast.LENGTH_LONG).show()
                            } catch (e: Exception) { /* activity gone — the log line above still records it */ }
                        }
                    } catch (e: Exception) { /* context gone — the log line above still records it */ }
                }
            } else {
                // The configured destination works again: re-arm the
                // one-shot report so a FUTURE removal is announced again.
                _uploadFallbackAnnounced = false
            }
        }
        // Auto-create whichever directory is actually in effect (default or
        // custom) — mkdirs() on an already-existing directory is a cheap,
        // harmless no-op, so this is safe to do on every call and makes a
        // fresh install's very first receive, or a custom folder the user
        // picked but hasn't used yet, work without a separate setup step.
        try { File(path).mkdirs() } catch (e: Exception) { /* best-effort — an actual write failure surfaces on its own later */ }
        return path
    }
    /** One-shot latch for the §8 destination-fallback report (see above). */
    @Volatile private var _uploadFallbackAnnounced = false
    fun setUploadPath(ctx: Context, v: String) = edit(ctx) { putString("upload_path", v) }

    fun isPasswordEnabled(ctx: Context) = prefs(ctx).getBoolean("pwd_on", false)
    fun setPasswordEnabled(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("pwd_on", v) }
    fun getPassword(ctx: Context) = prefs(ctx).getString("pwd", "") ?: ""
    fun setPassword(ctx: Context, v: String) = edit(ctx) { putString("pwd", v) }

    // (Permanent URL / mDNS system fully removed — was unreliable across
    // target devices, especially Android Chrome, which never resolves
    // .local addresses. See git history / previous phase notes if a future
    // phase wants to revisit local-network discovery differently.)
    //
    // Phase 14 native device-to-device discovery deliberately does NOT
    // revisit mDNS/NSD for the reason above plus one more: phone-hosted WiFi
    // hotspots (this app's primary use case) very commonly do not forward
    // multicast traffic between connected stations at all, which is exactly
    // what mDNS depends on — it would silently fail on the single most
    // important network shape for this app. Plain UDP broadcast has no such
    // dependency, needs no extra permission (no CHANGE_WIFI_MULTICAST_STATE),
    // and is what NativeDiscovery uses. See p2p/NativeDiscovery.kt.

    /** This device's name as shown to other LumiDrop X apps during native
     *  discovery. Empty (the default) means "use Build.MODEL" — see
     *  NativeDiscovery.myDisplayName(). */
    fun getDiscoveryName(ctx: Context) = prefs(ctx).getString("discovery_name", "") ?: ""
    fun setDiscoveryName(ctx: Context, v: String) = edit(ctx) { putString("discovery_name", v.trim()) }

    /** Stable per-install identifier so a native peer can be recognized
     *  across IP changes (hotspot re-joins, DHCP lease changes, etc.).
     *  Generated once on first use and persisted forever after. */
    // @Synchronized: the read-check-then-write below isn't atomic on its
    // own — two concurrent first-ever callers (no id persisted yet) could
    // otherwise each generate and persist their OWN UUID, last write wins,
    // leaving whichever caller cached the losing value permanently
    // disagreeing with everyone else about "this device's id". This is
    // exactly what happened between NativeDiscovery's broadcast and listen
    // loops before that call site was fixed to resolve the id once up
    // front; synchronizing here closes the same race for any other caller.
    @Synchronized
    fun getOrCreateDeviceId(ctx: Context): String {
        prefs(ctx).getString("device_id", null)?.let { return it }
        val fresh = java.util.UUID.randomUUID().toString()
        edit(ctx) { putString("device_id", fresh) }
        return fresh
    }

    fun isConfirmConn(ctx: Context) = prefs(ctx).getBoolean("confirm_conn", false)
    fun setConfirmConn(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("confirm_conn", v) }

    fun isBatteryOptSkipped(ctx: Context) = prefs(ctx).getBoolean("bat_skip", false)
    fun setBatteryOptSkipped(ctx: Context) = edit(ctx) { putBoolean("bat_skip", true) }

    fun isKeepScreenOn(ctx: Context) = prefs(ctx).getBoolean("keep_screen_on", true)
    fun setKeepScreenOn(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("keep_screen_on", v) }

    // ── Pass-11 (Required Implementation 4): the Full Screen toggle ──────────
    // OFF by default. When on, MainActivity hides the status bar and
    // navigation buttons for an immersive, edge-to-edge experience
    // (applyFullScreenBars re-asserts it on every window-focus change while
    // the setting stays on). The window background is bg_deep either way,
    // so no black bar is ever left behind where the system bars were.
    fun isFullScreen(ctx: Context): Boolean = prefs(ctx).getBoolean("full_screen", false)
    fun setFullScreen(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("full_screen", v) }

    // ── Pass-26 (#5): Automatic Server Startup ────────────────────────────────
    // ON by default — the app's behavior since the very first release (the
    // server starts during launch, gated only by the storage/notification
    // permission flow) — so existing users see exactly the same automatic
    // startup unless they explicitly turn it off. When off, SendFragment's
    // launch path skips the auto-start entirely and the server stays
    // stopped until the person presses the existing Start button (which
    // runs the SAME permission flow and start call as always). Changing the
    // preference never touches a RUNNING server: it is read exactly once,
    // at launch, and nothing in the toggle's save path talks to the service.
    fun isAutoServerStart(ctx: Context): Boolean = prefs(ctx).getBoolean("auto_server_start", true)
    fun setAutoServerStart(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("auto_server_start", v) }

    // (Global Transfer Mode setting removed in Phase 7 — mode is now assigned
    // per-device via DeviceStore, not as one app-wide flag.)

    // Auto-Accept: when off (default), incoming files from connected devices sit in an
    // Incoming list awaiting the Host's explicit Accept/Decline before they're saved.
    fun isAutoAcceptUploads(ctx: Context) = prefs(ctx).getBoolean("auto_accept_uploads", false)
    fun setAutoAcceptUploads(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("auto_accept_uploads", v) }

    // ── Pass-7 (Auto-Send remake) ──────────────────────────────────────────
    // Auto-Send is now the exact MIRROR of Auto-Accept: one plain boolean,
    // no engine, no folder, no ledger. When ON, anything the host picks from
    // any Quick Share tile goes out INSTANTLY to every device connected at
    // that moment — browsers get a targeted Quick Share item each (their
    // incoming list shows the offer; the receiver's own Auto-Accept decides
    // whether anything downloads without a tap), native peers get one
    // manifest push (or a visibly-waiting Pending row that fires the moment
    // a peer appears). Nothing is ever staged in the queue card while the
    // setting is on. The pass-6 watched-folder engine is gone entirely; its
    // keys are left in storage as inert leftovers (reading them would only
    // resurrect behavior the user explicitly asked to have removed).
    fun isAutoSendEnabled(ctx: Context) =
        prefs(ctx).getBoolean("auto_send_files_enabled", prefs(ctx).getBoolean("auto_send_share", false))
    fun setAutoSendEnabled(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("auto_send_files_enabled", v) }

    // ── GOAT-PASS 14 (Phase 1, §1.2): the app-side "send queue as one ZIP" ─────
    // mode — read at the moment of every send operation (SendFragment's
    // dispatch paths and the Auto-Send/native park). OFF (the default) keeps
    // the classic per-item behavior byte-for-byte; ON packages each send
    // operation's captured batch as ONE streamed archive for browser AND
    // native recipients alike. Phase 1 exposes it through this setting (the
    // Settings screen's switch); Phase 2's Connected-Devices-card toggle
    // writes this SAME preference, so the two surfaces can never disagree.
    fun isZipSendEnabled(ctx: Context) = prefs(ctx).getBoolean("zip_send_enabled", false)
    fun setZipSendEnabled(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("zip_send_enabled", v) }

    // ── GOAT-PASS 14 (Phase 2, §3): the FIRST-TIME explanation latch for the ──
    // ZIP mode. The very first time the user enables the mode (from EITHER
    // the Nearby Devices header icon or the Queue Files row icon — both call
    // the same SendFragment.toggleZipSendMode), the "Send as ZIP" explanation
    // modal is shown BEFORE the preference flips; confirming the explanation
    // enables the mode AND latches this acknowledgment, so the explanation is
    // never shown again on any later enable (disable → re-enable included —
    // the latch is about the FIRST use ever, not the first use per session).
    // Canceling/dismissing the explanation leaves ZIP mode OFF — the setting
    // only ever flips on an explicit confirm. Opt-OUT storage (default
    // false = never acknowledged), persisted through the same prefs file as
    // the mode itself so the two can never drift apart across backups or
    // clears of one but not the other.
    fun isZipSendExplained(ctx: Context) = prefs(ctx).getBoolean("zip_send_explained_ack", false)
    fun setZipSendExplained(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("zip_send_explained_ack", v) }

    // ── Pass-34 (#4): the Files and Music pickers' hidden-file visibility ──────
    // Hidden content is hidden by DEFAULT (the pref's false default); the
    // toggle lives in each picker's sorting menu and is deliberately ONE
    // shared preference — the user's visibility intent should not flip
    // between the two pickers, and both read/write the same key.
    fun isPickerShowHidden(ctx: Context) = prefs(ctx).getBoolean("picker_show_hidden", false)
    fun setPickerShowHidden(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("picker_show_hidden", v) }

    // ── GOAT-PASS 5 (§5): REMEMBER LAST OPENED DIRECTORY. Enabled by default
    // for existing and new users (getBoolean's fallback = true): the Files
    // sheet reopens on the last directory the user actually browsed, across
    // sheet reopenings AND app restarts. The remembered path is only ever a
    // STARTING POINT — the sheet validates it on open (an unreachable,
    // deleted or permission-revoked directory falls back to its nearest
    // living parent, then the storage root), so a stale path can never wedge
    // navigation. A temporary search-results view never writes the
    // preference (only navigate() does), and disabling the toggle restores
    // the default root-opening behaviour immediately.
    fun isRememberLastDir(ctx: Context) = prefs(ctx).getBoolean("files_remember_last_dir", true)
    fun setRememberLastDir(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("files_remember_last_dir", v) }
    fun getLastFilesDir(ctx: Context): String? = prefs(ctx).getString("files_last_dir", null)
    fun setLastFilesDir(ctx: Context, path: String?) {
        if (path == null) edit(ctx) { remove("files_last_dir") } else edit(ctx) { putString("files_last_dir", path) }
    }

    // ── Pass-44 (§7): PINNED FILES-SHEET ITEMS. A pin is a pure DISPLAY
    // preference keyed by absolute path: pinned entries float to the top of
    // their own directory (the directory where the pin was applied — a pin
    // on /sdcard/A/B.txt only ever groups above unpinned entries in A's
    // listing), the underlying file is never moved or touched, and the set
    // survives refreshes, sheet reopenings, app restarts and reconnections.
    // Unpinning removes exactly the stored path — nothing is inferred.
    fun getPinnedPaths(ctx: Context): MutableSet<String> =
        prefs(ctx).getStringSet("files_pinned", emptySet())?.toMutableSet() ?: mutableSetOf()

    fun setPinnedPaths(ctx: Context, paths: Set<String>) =
        edit(ctx) { putStringSet("files_pinned", paths) }

    // ── Pass-39 (#7): the Received and History tabs' sort settings, one ──────
    // "key:dir" string each (dir "a"/"d"). The stored value ONLY drives the
    // display order — the transfer/history records themselves are never
    // rewritten. Defaults: date-descending (newest first), the order both
    // tabs always implied.
    fun getTransfersSort(ctx: Context, tab: String): String =
        prefs(ctx).getString("transfers_sort_$tab", "date:d") ?: "date:d"
    fun setTransfersSort(ctx: Context, tab: String, value: String) =
        edit(ctx) { putString("transfers_sort_$tab", value) }

    // ── GOAT-PASS 8 (§4): the SELECTION INTERACTION STYLE, one global choice ──
    // applied consistently to every selection surface (Files sheet, Music
    // sheet, Received tab, History tab, Media picker, App picker):
    //
    //   "range" — Range Selection: long-press selects the row
    //     and anchors it; a later long-press selects the whole inclusive
    //     span between anchor and target.
    //   "slide" (the DEFAULT) — Hold & Slide Selection: holding an item
    //     begins the gesture, sliding across items selects (or, from a
    //     selected row, deselects) them, with the edge auto-scroll on every
    //     selection surface.
    //
    //  ── GOAT-PASS 9 (§4): the DEFAULT is now Hold & Slide — the spec ──
    //  change of this pass. A fresh install (no stored preference) gets
    //  "slide"; a user who explicitly chose a style keeps it untouched —
    //  the default only ever applies when the key has never been written.
    //
    // The two systems never run simultaneously: in Range mode the drag
    // gesture never arms (the row's own long-press owns everything); in
    // Hold & Slide mode the long-press applies only the initiating
    // toggle — never a range. The value is read LIVE at gesture time by
    // every surface, so a saved change applies immediately, with no sheet
    // to reopen and no gesture from the previous mode able to continue.
    fun getSelectionStyle(ctx: Context): String =
        prefs(ctx).getString("selection_style", "slide") ?: "slide"
    fun setSelectionStyle(ctx: Context, v: String) = edit(ctx) { putString("selection_style", v) }
    /** The gesture sites' one-line read: true only in Hold & Slide mode. */
    fun isSlideSelection(ctx: Context): Boolean = getSelectionStyle(ctx) == "slide"

    // ── Pass-40 (#1): the Files picker's PER-DIRECTORY sorting store. One ────
    // "key:dir" string per directory path ("name:a" / "date:d" / "size:a" /
    // "type:d") — each directory remembers its own criterion and direction
    // independently, and reopening a directory restores exactly what was
    // left there, across navigation, sheet reopenings and app restarts.
    // A DEDICATED prefs file keeps the unbounded per-directory map out of
    // the main store; a bulk-write helper serves the Apply-to-all walk (one
    // editor, one apply). The values only ever drive DISPLAY order — no
    // file is ever moved, renamed or otherwise touched by them.
    private fun filesSortPrefs(ctx: Context) =
        ctx.getSharedPreferences("lumidrop_files_sort", Context.MODE_PRIVATE)

    // ── GOAT-PASS 10 (§3): the Music sheet's OWN per-directory sorting store ──
    // — the exact same system the Files sheet has had since Pass-40, kept in
    // a SEPARATE prefs file because the two sheets' value vocabularies differ
    // (Files: name/date/size/type; Music: title/artist/date/duration/size) and
    // the same browsed path must be able to carry one configuration in Files
    // and an independent one in Music without either ever clobbering the
    // other. Keys are the browsed scope: the music root's path for the folder
    // list, a folder's own path inside it, and the All-Tracks sentinel for
    // the flat library view. Values drive DISPLAY order only.
    private fun musicSortPrefs(ctx: Context) =
        ctx.getSharedPreferences("lumidrop_music_sort", Context.MODE_PRIVATE)

    /** The remembered sorting for one Music browsing scope, or null when it
     *  has none (the caller applies the title-ascending default). */
    fun getMusicDirSort(ctx: Context, scopeKey: String): String? =
        try { musicSortPrefs(ctx).getString(scopeKey, null) } catch (e: Exception) { null }

    fun setMusicDirSort(ctx: Context, scopeKey: String, value: String) {
        try { musicSortPrefs(ctx).edit().putString(scopeKey, value).apply() } catch (e: Exception) { /* never break browsing */ }
    }

    /** Music's Apply-to-all bulk write — the same one-commit discipline as
     *  [setFilesDirSortBulk] (a large library can carry thousands of audio
     *  folders). */
    fun setMusicDirSortBulk(ctx: Context, entries: Map<String, String>) {
        try {
            val ed = musicSortPrefs(ctx).edit()
            for ((path, value) in entries) ed.putString(path, value)
            ed.apply()
        } catch (e: Exception) { /* never break browsing */ }
    }

    /** The remembered sorting for one directory, or null when it has none
     *  (the caller applies its default). Never throws — a broken store must
     *  never break browsing. */
    fun getFilesDirSort(ctx: Context, dirPath: String): String? =
        try { filesSortPrefs(ctx).getString(dirPath, null) } catch (e: Exception) { null }

    fun setFilesDirSort(ctx: Context, dirPath: String, value: String) {
        try { filesSortPrefs(ctx).edit().putString(dirPath, value).apply() } catch (e: Exception) { /* never break browsing */ }
    }

    /** Apply-to-all's bulk write: every directory → one value, in ONE
     *  editor commit (a deep tree can carry thousands of directories — the
     *  store must never see them one apply() at a time). */
    fun setFilesDirSortBulk(ctx: Context, entries: Map<String, String>) {
        try {
            val ed = filesSortPrefs(ctx).edit()
            for ((path, value) in entries) ed.putString(path, value)
            ed.apply()
        } catch (e: Exception) { /* never break browsing */ }
    }

    // ── Pass-14 (resumable receives, requirement 3): the "Don't show again" ──
    // half of the discard-resumable confirmation. Discarding a kept partial
    // permanently deletes real received bytes, so the FIRST few discards ask
    // for confirmation; once the user has ticked the box (or confirmed a few
    // times), the X discards immediately with only a toast. Opt-OUT storage
    // (default false = always ask), so a fresh install never silently
    // deletes data the user might not have known was partial.
    fun isDiscardResumableDontAsk(ctx: Context) = prefs(ctx).getBoolean("discard_resumable_dont_ask", false)
    fun setDiscardResumableDontAsk(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("discard_resumable_dont_ask", v) }

    // ── GOAT-PASS 10 (§9 — Open Received Folder Internally): when ON, the ──
    // Received tab's "Open Received Folder" action always opens LumiDrop X's
    // OWN full-screen Files Sheet at the exact configured receive
    // destination, instead of Android's external file-manager flow. Default
    // OFF: the external ladder (and its Android 5/6 fallback) stays exactly
    // as it is. The toggle changes PRESENTATION only — it never touches the
    // configured destination itself, and it is read live at every opening.
    fun isOpenReceivedFolderInternally(ctx: Context) = prefs(ctx).getBoolean("open_received_folder_internally", false)
    fun setOpenReceivedFolderInternally(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("open_received_folder_internally", v) }

    // ── Phase 10: Transfers tab ───────────────────────────────────────────────
    // Floating popup: when enabled, active transfers appear as a collapsible
    // mini-bar overlay on the home screen instead of requiring the host to
    // navigate to the Transfers tab manually. The main Transfers tab always
    // exists regardless.
    //
    // GOAT v2: default flipped back to OFF (was: on for a fresh install,
    // off for an existing one carrying its prior behavior forward). Both
    // cases want the same fallback now, so the fresh-install distinction
    // that used to matter here doesn't anymore.
    fun isFloatingTransferPopup(ctx: Context): Boolean = prefs(ctx).getBoolean("float_transfer_popup", false)
    fun setFloatingTransferPopup(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("float_transfer_popup", v) }

    // ── SELECTION FINAL PASS 1 (#13.3): the Received / History tab badges' ─────
    // enable switches — read LIVE by TransfersFragment.updateTabBadges on
    // every badge paint, so a change applies from the very next paint with
    // nothing to restart. Both default ON (the badges' existing behavior).
    // With a switch OFF the badge simply never shows; the unseen counters
    // keep running underneath exactly as before, so re-enabling surfaces
    // whatever accumulated while it was off — no state is destroyed.
    fun isReceivedTabBadgeEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("received_tab_badge_enabled", true)
    fun setReceivedTabBadgeEnabled(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("received_tab_badge_enabled", v) }
    fun isHistoryTabBadgeEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean("history_tab_badge_enabled", true)
    fun setHistoryTabBadgeEnabled(ctx: Context, v: Boolean) = edit(ctx) { putBoolean("history_tab_badge_enabled", v) }

    // ── Transfer concurrency: REMOVED as a setting (Reliability pass §3) ──────
    // Both directions are permanently fixed (GOAT-PASS 2: at EXACTLY ONE
    // transfer at a time per direction, by explicit specification — see
    // TransferManager.TRANSFER_CONCURRENCY's doc for the contract), enforced
    // by the engine itself, with the UI-side control removed everywhere it
    // used to appear (SettingsFragment steppers, WebUiSettingsActivity's
    // upload stepper, the web client's wsxSettings.concurrentUploads). The
    // old receiving_concurrency / download_concurrency pref keys may still
    // sit in existing installs' SharedPreferences; nothing reads them
    // anymore and they are left as inert data (a settings file with two dead
    // integer keys is cheaper and safer than a migration write).

    // GOAT v2: the persistent half of the default-mode setting for new web
    // clients. "transfer" matches what every client already defaulted to
    // before this setting existed, so installs updating into this version
    // see no behavior change until they actually open the new toggle.
    // LumiDropServer.getDeviceMode() is what actually consumes this — see
    // its own doc comment for the full fallback chain (per-device choice,
    // then this session's temporary override if any, then this).
    fun getDefaultClientMode(ctx: Context): String = prefs(ctx).getString("default_client_mode", "transfer") ?: "transfer"
    fun setDefaultClientMode(ctx: Context, mode: String) = edit(ctx) { putString("default_client_mode", mode) }

    // ── Phase 17: independent network-interface toggles — REMOVED (Pass-11) ──
    // Required Implementation 3 removed the Hotspot / Wi-Fi / LAN toggle from
    // Settings entirely: local network sharing is PERMANENTLY enabled. The
    // readers that used to consult these keys (NetworkUtils's interface
    // filter, ServerForegroundService's start/restart guards,
    // LumiDropServer's request-layer gate) were un-gated in the same pass,
    // so nothing consumes these keys anymore. They are left in existing
    // installs' SharedPreferences as inert data (the same convention as the
    // retired concurrency keys above); accessors are deleted so no future
    // call site can silently resurrect a half-read toggle.

    // (Pass-8 follow-up: Internet Sharing fully removed.) The app is now
    // strictly LAN-based: Hotspot / Wi-Fi / wired networks are the only
    // surfaces a server URL is ever advertised on. The two prior systems —
    // raw Mobile-Data sharing (serving the carrier-assigned interface IP
    // directly; unreachable behind CGNAT, unencrypted) and the managed
    // relay tunnel (remote/RelayTunnelClient + RemoteTunnelManager +
    // tools/relay) — are deleted wholesale. Any legacy pref keys left in
    // existing installs' SharedPreferences are inert data: nothing reads
    // them, nothing writes them. Mobile Data interfaces remain CLASSIFIED
    // in NetworkUtils purely so the request-layer gate can recognize and
    // refuse direct hits on the carrier address, and so the dashboard's
    // no-LAN fallback never advertises one.

    /** Intra-app broadcast sent by SettingsActivity when a server-dependent
     *  setting changes; received by RestartServerReceiver. */
    const val ACTION_RESTART_SERVER_ACTION = "com.lumidrop.x.ACTION_RESTART_SERVER"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun edit(ctx: Context, block: android.content.SharedPreferences.Editor.() -> Unit) =
        prefs(ctx).edit().apply(block).apply()
}
