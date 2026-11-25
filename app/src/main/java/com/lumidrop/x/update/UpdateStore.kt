package com.lumidrop.x.update

import org.json.JSONObject

/**
 * ══ PRODUCTION FINAL PASS 23 (§5) — persisted update discovery ═════════════
 *
 * Why this exists: Pass 22 kept the entire update lifecycle in
 * UpdateManager's in-memory state object. A process death (the user swiping
 * the app away, Android reclaiming the cache-shell process, a crash in an
 * unrelated subsystem) erased the discovered release — the About card fell
 * back to "You're on version 1.0.3" until the next network check, and any
 * already-downloaded APK became invisible (and, worse, cleanup-prone).
 *
 * The snapshot stores EXACTLY what the spec's §5 list requires and nothing
 * else — no UI messages (they are transient by design), no byte offsets
 * (the partial file's REAL length on disk is the only resume truth, §3.A),
 * no tokens. Written atomically through SharedPreferences.apply() at every
 * lifecycle milestone; read back by UpdateManager.restore() on resume.
 *
 * Pure encode/decode over org.json: byte-identical API on-device (Android
 * framework) and on the JVM test classpath (the pinned json-20231013 jar),
 * so round-trips are unit-testable without a device.
 */
object UpdateStore {

    data class Snapshot(
        val release: ReleaseModel.Release?,
        val lastCheckAtMs: Long,
        val readyToInstall: Boolean,      // a validated APK was staged
        val installPending: Boolean       // the OS installer was launched
    ) {
        companion object {
            fun empty(): Snapshot = Snapshot(null, 0L, false, false)
        }
    }

    /** The prefs key the whole updater shares (ReleaseModel.prefs). */
    const val KEY_SNAPSHOT = "snapshot_v1"
    const val KEY_LAST_CHECK = "last_check_at"

    // ── encode ──────────────────────────────────────────────────────────────

    fun encode(s: Snapshot): String {
        val o = JSONObject()
        o.put("v", 1)
        o.put(KEY_LAST_CHECK, s.lastCheckAtMs)
        o.put("ready", s.readyToInstall)
        o.put("pending", s.installPending)
        val r = s.release
        if (r != null) {
            val ro = JSONObject()
            ro.put("tag", r.tagName)
            ro.put("name", r.name ?: "")
            ro.put("pre", r.isPrerelease)
            ro.put("draft", r.isDraft)
            ro.put("asset", r.apkAssetName ?: "")
            ro.put("url", r.apkUrl ?: "")
            ro.put("size", r.apkSizeBytes)
            ro.put("body", r.bodyMarkdown ?: "")
            o.put("release", ro)
        }
        return o.toString()
    }

    // ── decode ──────────────────────────────────────────────────────────────

    /** Never throws: a corrupt/foreign payload decodes as an empty snapshot
     *  (an obsolete discovery is recoverable by one check; a crash here
     *  would take the whole Settings tab down). */
    fun decode(json: String?): Snapshot {
        if (json.isNullOrBlank()) return Snapshot.empty()
        return try {
            val o = JSONObject(json)
            val ro = o.optJSONObject("release")
            val release = if (ro == null) null else run {
                val tag = ro.optString("tag", "")
                if (tag.isBlank()) null else ReleaseModel.Release(
                    tagName = tag,
                    name = ro.optString("name", "").ifBlank { null },
                    isPrerelease = ro.optBoolean("pre", false),
                    isDraft = ro.optBoolean("draft", false),
                    apkAssetName = ro.optString("asset", "").ifBlank { null },
                    apkUrl = ro.optString("url", "").ifBlank { null },
                    apkSizeBytes = if (ro.has("size") && !ro.isNull("size")) ro.optLong("size", 0L) else 0L,
                    bodyMarkdown = ro.optString("body", "").ifBlank { null }
                )
            }
            Snapshot(
                release = release,
                lastCheckAtMs = o.optLong(KEY_LAST_CHECK, 0L),
                readyToInstall = o.optBoolean("ready", false),
                installPending = o.optBoolean("pending", false)
            )
        } catch (e: Exception) {
            Snapshot.empty()
        }
    }

    // ── read/write against the shared prefs ─────────────────────────────────

    fun load(prefs: android.content.SharedPreferences): Snapshot =
        decode(prefs.getString(KEY_SNAPSHOT, null))

    fun store(prefs: android.content.SharedPreferences, s: Snapshot) {
        prefs.edit().putString(KEY_SNAPSHOT, encode(s)).apply()
    }

    fun clear(prefs: android.content.SharedPreferences) {
        prefs.edit().remove(KEY_SNAPSHOT).apply()
    }
}
