package com.lumidrop.x.settings

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Host-controlled customization for the WebUI (the browser-facing client
 * served from assets/index.html).
 *
 * Everything here is configured once by the Host (in WebUiSettingsActivity)
 * and pushed to every connecting browser via LumiDropServer's
 * GET /api/webui-config — the WebUI applies it automatically on every page
 * load. None of this requires any action from the person browsing.
 *
 * Three independent profiles are kept — "android", "windows", "ios" — so the
 * Host can tune sort order, download-manager behavior, home layout and
 * pinned folders differently per platform (e.g. iOS Safari benefits from a
 * lower default download concurrency than a Windows desktop browser).
 * Which profile a given request gets is decided by [detectOsProfile], which
 * mirrors the WebUI's own client-side `pwDetectOS()` exactly so the bucket a
 * device falls into is always the same on both sides.
 *
 * As of the "transfer system polish" phase, transfer BEHAVIOR settings
 * (concurrent uploads/downloads, Internal Download Manager on/off, Auto-Save)
 * moved here from the WebUI's own client-side settings modal — the Host is
 * now the single place that configures them, per OS profile, alongside sort
 * order and pinned folders. Only "Download as zip" remains a WebUI
 * (per-browser) preference, since it's a personal display choice with no
 * effect on server load or transfer reliability.
 */
object WebUiSettingsManager {
    private const val PREFS               = "wsx_webui_settings"
    private const val KEY_MUSIC_PATH      = "music_folder_path"       // legacy single-path key (migrated)
    private const val KEY_MUSIC_PATHS     = "music_folder_paths_json" // current multi-path key
    private const val KEY_PROFILES        = "os_profiles_json"
    private const val KEY_REVISION        = "config_revision"         // Next Release Pass §10: live-sync revision
    // Pass-3 (R1): per-client-OS default Quick Play external player. A map
    // of os -> player id, persisted as JSON. ABSENT/empty for every OS on a
    // fresh install — the setting is OFF by default, and a browser with no
    // host default and no client-chosen player never auto-launches one
    // (Quick Play falls back to the Open With menu instead).
    private const val KEY_QUICKPLAY       = "quickplay_defaults_json"

    val VALID_OS      = listOf("android", "windows", "ios")
    val HOME_SECTIONS = listOf("images", "videos", "music", "docs", "apps", "files")
    val SORT_FIELDS   = listOf("name", "date", "size", "type")

    data class SortPref(val field: String = "name", val asc: Boolean = true)
    data class DownloadManagerPref(
        val internalEnabled: Boolean = true,
        val concurrency: Int = 1,           // concurrent downloads (host → client)
        val concurrentUploads: Int = 2,     // concurrent uploads (client → host) — Phase 10: 1 → 2, server gate defaults higher and batch wall-time halves
        val autoSaveDownloads: Boolean = true
    )
    data class HomeLayoutPref(
        val order: List<String> = listOf("images", "videos", "music", "docs", "apps", "files"),
        val hidden: List<String> = emptyList()
    )
    data class PinnedFolder(val path: String, val name: String)
    data class OsProfile(
        val sort: SortPref = SortPref(),
        val downloadManager: DownloadManagerPref = DownloadManagerPref(),
        val homeLayout: HomeLayoutPref = HomeLayoutPref(),
        val pinnedFolders: List<PinnedFolder> = emptyList()
    )

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ── Profile mode (v1.0 §universal-settings) ───────────────────────────
    // "universal" (the DEFAULT from v1.0 on): ONE profile serves every
    // platform — the host edits a single set of sort/layout/pinned-folder
    // settings and every connected client, on any OS, gets exactly those.
    // "peros": the classic three independent per-platform profiles
    // (Android / Windows / iOS), unchanged from before, for hosts who
    // genuinely want per-platform tuning. Switching back and forth is a
    // plain settings action; nothing is ever lost (per-OS buckets keep
    // their stored values while universal mode is active — they're simply
    // not consulted).
    private const val KEY_PROFILE_MODE = "profile_mode"
    const val PROFILE_MODE_UNIVERSAL = "universal"
    const val PROFILE_MODE_PER_OS = "peros"
    private const val UNIVERSAL_BUCKET = "universal"   // internal profiles-JSON key

    fun profileMode(ctx: Context): String {
        val m = prefs(ctx).getString(KEY_PROFILE_MODE, PROFILE_MODE_UNIVERSAL) ?: PROFILE_MODE_UNIVERSAL
        return if (m == PROFILE_MODE_PER_OS) PROFILE_MODE_PER_OS else PROFILE_MODE_UNIVERSAL
    }

    /** Switches the active profile mode. Entering UNIVERSAL mode seeds the
     *  universal profile from the stored "android" bucket when none exists
     *  yet — the most honest migration available (no silently-fabricated
     *  defaults; the host's most-common platform's tuning carries over and
     *  evolves from there). Re-entering per-OS mode keeps every bucket's
     *  stored values untouched. */
    fun setProfileMode(ctx: Context, mode: String) {
        val target = if (mode == PROFILE_MODE_PER_OS) PROFILE_MODE_PER_OS else PROFILE_MODE_UNIVERSAL
        if (target == profileMode(ctx)) return
        prefs(ctx).edit().putString(KEY_PROFILE_MODE, target).apply()
        if (target == PROFILE_MODE_UNIVERSAL) {
            val root = try {
                JSONObject(prefs(ctx).getString(KEY_PROFILES, "{}") ?: "{}")
            } catch (e: Exception) { JSONObject() }
            if (!root.has(UNIVERSAL_BUCKET)) {
                val seed = root.optJSONObject("android") ?: profileToJson(OsProfile())
                root.put(UNIVERSAL_BUCKET, seed)
                prefs(ctx).edit().putString(KEY_PROFILES, root.toString()).apply()
            }
        }
        bumpRevision(ctx)
    }

    // ── Music folder paths (global — not OS-specific; these are server-side
    // filesystem locations, the same regardless of which device is browsing).
    // Now supports multiple folders (previously a single path). ──────────────

    fun getMusicFolderPaths(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY_MUSIC_PATHS, null)
        if (raw != null) {
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).mapNotNull { arr.optString(it, null)?.trim()?.ifEmpty { null } }
            } catch (e: Exception) { emptyList() }
        }
        // One-time migration from the old single-path preference, so an
        // existing Host's previously-configured folder isn't silently lost.
        val legacy = (prefs(ctx).getString(KEY_MUSIC_PATH, "") ?: "").trim()
        return if (legacy.isNotEmpty()) listOf(legacy) else emptyList()
    }

    fun setMusicFolderPaths(ctx: Context, paths: List<String>) {
        val cleaned = paths.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        prefs(ctx).edit()
            .putString(KEY_MUSIC_PATHS, JSONArray(cleaned).toString())
            .remove(KEY_MUSIC_PATH)   // legacy key fully retired once saved in the new format
            .apply()
        bumpRevision(ctx)
    }

    // ── Config revision (Next Release Pass §10 — live tab/layout sync) ───────
    // A monotonic counter bumped by EVERY settings write. The server ships
    // it as "cfgv" in the unified state payload; a connected client that
    // sees it change refetches /api/webui-config and rebuilds its
    // navigation in place — no page refresh, no full re-render. Only ever
    // moves forward, so a late transport delivery can never "restore" a
    // tab the host just disabled.
    fun revision(ctx: Context): Int =
        prefs(ctx).getInt(KEY_REVISION, 1)

    private fun bumpRevision(ctx: Context) {
        prefs(ctx).edit().putInt(KEY_REVISION, revision(ctx) + 1).apply()
    }

    // ── Quick Play default player (Pass-3, R1) ───────────────────────────────
    // The players offered to the Host are EXACTLY the ones the web client's
    // own Play With sheet can launch on that client platform — same ids,
    // same display names (see the PW table in assets/index.html; the ids are
    // the contract between the two sides, so any future player addition must
    // be made here and there together).
    data class QuickPlayPlayer(val id: String, val label: String)
    val QUICK_PLAY_PLAYERS: Map<String, List<QuickPlayPlayer>> = mapOf(
        "android" to listOf(
            QuickPlayPlayer("rex",     "REX Player"),
            QuickPlayPlayer("vlc",     "VLC for Android"),
            QuickPlayPlayer("mx",      "MX Player"),
            QuickPlayPlayer("xplayer", "XPlayer"),
        ),
        "windows" to listOf(
            QuickPlayPlayer("vlcwin",   "VLC Media Player"),
            QuickPlayPlayer("potplayer", "PotPlayer"),
            QuickPlayPlayer("mpc",      "MPC-HC"),
            QuickPlayPlayer("mpcbe",    "MPC-BE"),
            QuickPlayPlayer("mpv",      "mpv"),
        ),
        "ios" to listOf(
            QuickPlayPlayer("nplayer", "nPlayer"),
            QuickPlayPlayer("vlc",     "VLC for Mobile"),
        ),
    )

    /** The host-configured Quick Play default for a client platform, or ""
     *  when none is configured (the default on a fresh install). A stored
     *  value that no longer matches any known player id is treated as unset —
     *  a stale id can never make a client auto-launch something unsupported. */
    fun getQuickPlayDefault(ctx: Context, os: String): String {
        val bucket = if (os in VALID_OS) os else "android"
        val stored = try {
            JSONObject(prefs(ctx).getString(KEY_QUICKPLAY, "{}") ?: "{}").optString(bucket, "")
        } catch (e: Exception) { "" }
        return if (QUICK_PLAY_PLAYERS[bucket]?.any { it.id == stored } == true) stored else ""
    }

    fun setQuickPlayDefault(ctx: Context, os: String, playerId: String) {
        val bucket = if (os in VALID_OS) os else "android"
        val root = try {
            JSONObject(prefs(ctx).getString(KEY_QUICKPLAY, "{}") ?: "{}")
        } catch (e: Exception) { JSONObject() }
        if (playerId.isEmpty()) root.remove(bucket) else root.put(bucket, playerId)
        prefs(ctx).edit().putString(KEY_QUICKPLAY, root.toString()).apply()
        bumpRevision(ctx)
    }

    // ── OS detection ─────────────────────────────────────────────────────────

    /**
     * Buckets a request's User-Agent into "ios" | "android" | "windows".
     * Deliberately mirrors the WebUI's own client-side pwDetectOS() test
     * (same patterns, same fallback) so a device the WebUI calls "iOS" is
     * always the same device this picks the Host's "iOS" profile for.
     */
    fun detectOsProfile(userAgent: String?): String {
        val ua = userAgent ?: ""
        return when {
            Regex("iPad|iPhone|iPod").containsMatchIn(ua) -> "ios"
            ua.contains("Android")                        -> "android"
            ua.contains("Win")                             -> "windows"
            else -> "ios"
        }
    }

    // ── Per-OS profile persistence ───────────────────────────────────────────

    fun getProfile(ctx: Context, os: String): OsProfile {
        // v1.0 (§universal-settings): in UNIVERSAL mode every OS reads the
        // ONE universal profile; in per-OS mode each platform reads its own
        // bucket exactly as before. A fresh v1.0 host with no stored
        // universal bucket falls back to the stored android profile (or
        // defaults), so the very first read is always coherent.
        val bucket = if (profileMode(ctx) == PROFILE_MODE_UNIVERSAL) UNIVERSAL_BUCKET
                     else (if (os in VALID_OS) os else "android")
        return try {
            val root = JSONObject(prefs(ctx).getString(KEY_PROFILES, "{}") ?: "{}")
            val o = root.optJSONObject(bucket)
                ?: if (bucket == UNIVERSAL_BUCKET) root.optJSONObject("android") else null
                ?: return OsProfile()
            parseProfile(o)
        } catch (e: Exception) { OsProfile() }
    }

    fun saveProfile(ctx: Context, os: String, profile: OsProfile) {
        // v1.0 (§universal-settings): persists under the ACTIVE bucket — the
        // universal profile in UNIVERSAL mode, the [os] bucket in per-OS mode.
        val bucket = if (profileMode(ctx) == PROFILE_MODE_UNIVERSAL) UNIVERSAL_BUCKET
                     else (if (os in VALID_OS) os else "android")
        val root = try {
            JSONObject(prefs(ctx).getString(KEY_PROFILES, "{}") ?: "{}")
        } catch (e: Exception) { JSONObject() }
        root.put(bucket, profileToJson(profile))
        prefs(ctx).edit().putString(KEY_PROFILES, root.toString()).apply()
        bumpRevision(ctx)
    }

    private fun parseProfile(o: JSONObject): OsProfile {
        val sortO = o.optJSONObject("sort")
        val field = sortO?.optString("field", "name") ?: "name"
        val sort = SortPref(
            field = if (field in SORT_FIELDS) field else "name",
            asc   = sortO?.optBoolean("asc", true) ?: true
        )

        val dmO = o.optJSONObject("downloadManager")
        // Reliability pass §3: concurrency values are inert legacy data now —
        // both engine gates are permanently fixed at 1. Parsed back purely so
        // existing profile JSON round-trips unchanged (no migration), never
        // read by anything that gates transfers anymore.
        val dm = DownloadManagerPref(
            internalEnabled   = dmO?.optBoolean("internalEnabled", true) ?: true,
            concurrency       = 1,
            concurrentUploads = 1,
            autoSaveDownloads = dmO?.optBoolean("autoSaveDownloads", true) ?: true
        )

        val hlO = o.optJSONObject("homeLayout")
        val storedOrder = hlO?.optJSONArray("order")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it, null) }.filter { it in HOME_SECTIONS }
        } ?: HOME_SECTIONS
        // Any section missing from a previously-saved order (e.g. a new
        // category shipped in a future update) is appended at the end —
        // never silently dropped from the home screen.
        val fullOrder = storedOrder + HOME_SECTIONS.filter { it !in storedOrder }
        val hidden = hlO?.optJSONArray("hidden")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it, null) }
        } ?: emptyList()
        val homeLayout = HomeLayoutPref(fullOrder, hidden)

        val pinsArr = o.optJSONArray("pinnedFolders")
        val pins = if (pinsArr != null) (0 until pinsArr.length()).mapNotNull { i ->
            val p = pinsArr.optJSONObject(i) ?: return@mapNotNull null
            val path = p.optString("path", "")
            if (path.isEmpty()) null
            else PinnedFolder(path, p.optString("name", path.substringAfterLast('/').ifEmpty { path }))
        } else emptyList()

        return OsProfile(sort, dm, homeLayout, pins)
    }

    private fun profileToJson(p: OsProfile): JSONObject = JSONObject().apply {
        put("sort", JSONObject().put("field", p.sort.field).put("asc", p.sort.asc))
        put("downloadManager", JSONObject()
            .put("internalEnabled", p.downloadManager.internalEnabled)
            .put("concurrency", p.downloadManager.concurrency)
            .put("concurrentUploads", p.downloadManager.concurrentUploads)
            .put("autoSaveDownloads", p.downloadManager.autoSaveDownloads))
        put("homeLayout", JSONObject().apply {
            put("order", JSONArray(p.homeLayout.order))
            put("hidden", JSONArray(p.homeLayout.hidden))
        })
        put("pinnedFolders", JSONArray().apply {
            p.pinnedFolders.forEach { put(JSONObject().put("path", it.path).put("name", it.name)) }
        })
    }

    // ── Pinned-folder convenience helpers (used by WebUiSettingsActivity) ────

    fun addPinnedFolder(ctx: Context, os: String, path: String, name: String) {
        val profile = getProfile(ctx, os)
        if (profile.pinnedFolders.any { it.path == path }) return   // no duplicates
        saveProfile(ctx, os, profile.copy(pinnedFolders = profile.pinnedFolders + PinnedFolder(path, name)))
    }

    fun removePinnedFolder(ctx: Context, os: String, path: String) {
        val profile = getProfile(ctx, os)
        saveProfile(ctx, os, profile.copy(pinnedFolders = profile.pinnedFolders.filter { it.path != path }))
    }

    // ── Full payload served to the WebUI ─────────────────────────────────────

    /** Builds the JSON object returned by GET /api/webui-config for a given request. */
    fun buildConfigJson(ctx: Context, userAgent: String?): JSONObject {
        val os = detectOsProfile(userAgent)
        val profile = getProfile(ctx, os)
        return JSONObject().apply {
            put("os", os)
            put("musicFolderPaths", JSONArray(getMusicFolderPaths(ctx)))
            // Pass-3 (R1): this client platform's host-configured Quick Play
            // default external player ("" when OFF/unset — the default). The
            // web client adopts it only until the person using that browser
            // picks their own player via Open With; a client-side choice
            // always wins over the host default.
            put("quickPlayDefault", getQuickPlayDefault(ctx, os))
            // ── GOAT-PASS 5 (§5 final round): the host's "Remember last opened ──
            // directory" preference (the Files sheet's three-dot toggle), served
            // to the web Files tab so BOTH surfaces honor ONE preference — the
            // browser restores its last browsed directory on the tab's first
            // open when (and only when) the host enabled it. Default true,
            // exactly like the native sheet.
            put("rememberLastFilesDir", SettingsManager.isRememberLastDir(ctx))
            put("sort", JSONObject().put("field", profile.sort.field).put("asc", profile.sort.asc))
            put("downloadManager", JSONObject()
                // Phase 4 (v3 §5 item 5): internalEnabled and autoSaveDownloads
                // are no longer served — the WebUI downloads exclusively via
                // the browser's native pipeline, so there IS no internal
                // download manager for a client to enable, and nothing is
                // ever buffered in page memory for an auto-save toggle to
                // govern. (The per-profile fields remain in persisted storage,
                // round-tripped by WebUiSettingsActivity, purely so existing
                // profile JSON never needs a migration.)
                // Reliability pass §3: concurrency is no longer configurable
                // AT ALL — one upload and one receive at a time, permanently,
                // enforced by TransferManager's fixed gates. Served as the
                // constant 1 for both fields purely so older web-client builds
                // still reading them see the true engine value; current builds
                // ignore them.
                .put("concurrency", 1)
                .put("concurrentUploads", 1))
            put("homeLayout", JSONObject().apply {
                put("order", JSONArray(profile.homeLayout.order))
                put("hidden", JSONArray(profile.homeLayout.hidden))
            })
            put("pinnedFolders", JSONArray().apply {
                profile.pinnedFolders.forEach { put(JSONObject().put("path", it.path).put("name", it.name)) }
            })
        }
    }
}
