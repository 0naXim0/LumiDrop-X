package com.lumidrop.x.update

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * ══ PASS 19 §6 — the GitHub release model + version comparison ════════════
 *
 * Repository conventions, taken from the ACTUAL repository (audited this
 * pass, HTML/atom scrape of github.com/0naXim0/LumiDrop-X because the API
 * rate limit blocked the sandbox's shared IP):
 *
 *   - releases: "v1.0.0" (Latest), "v1.0.1" — tags like "v" + versionName
 *   - assets:   LumiDrop-X-v1.0.1-universal.apk (one universal APK)
 *   - both published, neither prerelease nor draft
 *   - the v1.0.1 notes state the v1.0.0 signing keystore was LOST — the two
 *     releases are signed DIFFERENTLY. The installer therefore cannot
 *     replace v1.0.0 with v1.0.1 in place; that is a truth the updater must
 *     surface, never hide (see UpdateManager's install handoff).
 *
 * Version comparison: numeric component-wise on the tag's trailing version
 * ("1.10" > "1.9", never lexicographic), with an explicit versionCode
 * escalation channel — a release whose tag does not strictly exceed the
 * installed version NEVER installs (no downgrades, no same-version
 * reinstall loops). Pure JVM logic: fully unit-tested without Android.
 */
object ReleaseModel {

    const val REPO_OWNER = "0naXim0"
    const val REPO_NAME = "LumiDrop-X"
    const val RELEASES_API = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases"
    const val RELEASES_PAGE = "https://github.com/$REPO_OWNER/$REPO_NAME/releases"

    data class Release(
        val tagName: String,
        val name: String?,
        val isPrerelease: Boolean,
        val isDraft: Boolean,
        val apkAssetName: String?,
        val apkUrl: String?,       // the browser_download_url (HTTPS, github.com)
        val apkSizeBytes: Long,    // 0 when unknown
        val bodyMarkdown: String?
    ) {
        val version: String? get() = parseVersionFromTag(tagName)
    }

    /** "v1.0.1" / "1.0.1" / "1" / "v1.0.1-beta" → "1.0.1" / "1" (numeric part only).
     *  PASS 19 test catch: the regex used to require at least one ".x"
     *  component, so a single-component version ("1", "v2") parsed as null
     *  and compared as OLDEST — a "v2" release could never beat "1.0.1".
     *  The group now matches one-or-more components. */
    fun parseVersionFromTag(tag: String?): String? {
        if (tag.isNullOrBlank()) return null
        val m = Regex("""v?(\d+(?:\.\d+)*)""").find(tag) ?: return null
        return m.groupValues[1]
    }

    /**
     * Component-wise numeric compare: 1.10 > 1.9 > 1.2. Missing components
     * are zero: 1 == 1.0 == 1.0.0. Non-numeric tails are ignored.
     * Returns positive when [a] is NEWER than [b], negative when older,
     * 0 when equal. null parses as OLDEST-possible (caller decides policy).
     */
    fun compareVersions(a: String?, b: String?): Int {
        val pa = a?.let { parseVersionFromTag(it) }?.split('.')?.mapNotNull { it.toIntOrNull() }
        val pb = b?.let { parseVersionFromTag(it) }?.split('.')?.mapNotNull { it.toIntOrNull() }
        if (pa == null && pb == null) return 0
        if (pa == null) return -1
        if (pb == null) return 1
        val n = maxOf(pa.size, pb.size)
        for (i in 0 until n) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }

    /**
     * Pick the APK asset for a release. Convention is one universal APK
     * ("*-universal.apk"); tolerate any single .apk asset, and any name
     * containing "universal" when several exist. Never picks .zip/.apks/
     * .aab — the updater installs a plain APK only (XAPK splits keep their
     * own in-app flow, untouched).
     */
    fun pickApkAsset(assets: List<Triple<String, String, Long>>): Triple<String, String, Long>? {
        val apks = assets.filter { it.first.lowercase().endsWith(".apk") }
        if (apks.isEmpty()) return null
        apks.firstOrNull { it.first.lowercase().contains("universal") }?.let { return it }
        if (apks.size == 1) return apks[0]
        return null   // ambiguous multi-APK release — refuse rather than guess
    }

    /**
     * The update decision. Stable-only by default: drafts are invisible to
     * the public API anyway, prereleases are skipped unless explicitly
     * allowed. A release qualifies only when it has an APK asset AND its
     * version strictly exceeds the installed version.
     */
    sealed class Decision {
        object UpToDate : Decision()
        data class UpdateAvailable(val release: Release) : Decision()
        object NoReleases : Decision()
        object NoUsableRelease : Decision()   // releases exist but none stable+apk+newer
    }

    fun decide(
        installedVersionName: String?,
        releases: List<Release>,
        allowPrerelease: Boolean = false
    ): Decision {
        if (releases.isEmpty()) return Decision.NoReleases
        val usable = releases.filter { !it.isDraft && (allowPrerelease || !it.isPrerelease) && it.apkUrl != null }
        if (usable.isEmpty()) return Decision.NoUsableRelease
        // PASS 19 test catch #2: the old selector compared each version
        // against "" — which returns 1 (a positive sentinel) for EVERY
        // parseable version, so maxByOrNull kept the FIRST release instead
        // of the newest. Pairwise reduction compares releases AGAINST EACH
        // OTHER, which is the actual question.
        val newest = usable.reduce { acc, r ->
            if (compareVersions(r.version, acc.version) > 0) r else acc
        }
        val newer = newest.version != null &&
            compareVersions(newest.version, installedVersionName) > 0
        return if (newer) Decision.UpdateAvailable(newest) else Decision.UpToDate
    }

    /**
     * Parse the GitHub Releases API JSON array. Defensive against every
     * malformed shape §6.2 lists: missing tags, null assets, non-APK-only
     * releases, string-typed sizes. Never throws — a malformed entry is
     * skipped, a malformed document yields an empty list.
     */
    fun parseReleases(json: String?): List<Release> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val tagName = o.optString("tag_name", "")
                if (tagName.isBlank()) null else {
                    val assets = o.optJSONArray("assets") ?: JSONArray()
                    val apks = (0 until assets.length()).mapNotNull { j ->
                        val a = assets.optJSONObject(j) ?: return@mapNotNull null
                        val name = a.optString("name", "")
                        val url = a.optString("browser_download_url", "")
                        if (name.isBlank() || url.isBlank()) null
                        else Triple(name, url, if (a.has("size") && !a.isNull("size")) a.optLong("size", 0L) else 0L)
                    }
                    val apk = pickApkAsset(apks)
                    Release(
                        tagName = tagName,
                        name = o.optString("name", "").ifBlank { null },
                        isPrerelease = o.optBoolean("prerelease", false),
                        isDraft = o.optBoolean("draft", false),
                        apkAssetName = apk?.first,
                        apkUrl = apk?.second,
                        apkSizeBytes = apk?.third ?: 0L,
                        bodyMarkdown = o.optString("body", "").ifBlank { null }
                    )
                }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ── auto-check pacing (§6.1/§6.11) ─────────────────────────────────────
    /** One auto-check per 24h maximum, checked on Settings/About entry only —
     *  never on tab navigation, never in a background service. */
    const val AUTO_CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L   // 24h (§6.1)

    fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences("lumidrop_updates", Context.MODE_PRIVATE)

    /** Should an automatic check run now? Interval + toggle + never during
     *  an active download/install. Pure decision, no I/O. */
    fun shouldAutoCheck(autoEnabled: Boolean, lastCheckAtMs: Long, nowMs: Long, busy: Boolean): Boolean =
        autoEnabled && !busy && (lastCheckAtMs <= 0L || nowMs - lastCheckAtMs >= AUTO_CHECK_INTERVAL_MS)

    /** A release's notes, trimmed to the first paragraph for the update card
     *  (the full notes stay one tap away). */
    fun shortNotes(md: String?): String {
        if (md.isNullOrBlank()) return ""
        return md.trim().take(400).let { cut ->
            cut.indexOf("\n\n").takeIf { it in 1 until cut.length }?.let { cut.substring(0, it) } ?: cut
        }
    }
}
