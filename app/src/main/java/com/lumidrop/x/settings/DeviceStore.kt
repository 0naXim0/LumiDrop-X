package com.lumidrop.x.settings

import android.content.Context
import org.json.JSONObject

/**
 * Persists per-device settings (assigned mode, custom name) keyed by IP
 * address. IP is the only practical device identifier available for web
 * clients on this LAN-only server — reasonably stable for a home/personal
 * network, and requires no client-side changes to support.
 *
 * LumiDropServer keeps its own in-memory copy of this data (read on every
 * 250ms /api/qstate poll) and only touches this store on writes — see
 * LumiDropServer.persistDevices(). This file is never read on a hot path.
 */
object DeviceStore {
    private const val PREFS = "wsx_devices"
    private const val KEY   = "devices_json"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Returns IP -> (mode, customNameOrNull). */
    fun loadAll(ctx: Context): Map<String, Pair<String, String?>> {
        val out = LinkedHashMap<String, Pair<String, String?>>()
        try {
            val root = JSONObject(prefs(ctx).getString(KEY, "{}") ?: "{}")
            val keys = root.keys()
            while (keys.hasNext()) {
                val ip = keys.next()
                val o = root.optJSONObject(ip) ?: continue
                val mode = o.optString("mode", "transfer")
                val name = o.optString("name", "").ifEmpty { null }
                out[ip] = Pair(mode, name)
            }
        } catch (e: Exception) { /* corrupt or missing — start clean */ }
        return out
    }

    /** Overwrites the entire store. IP -> (mode, customNameOrNull). */
    fun saveAll(ctx: Context, devices: Map<String, Pair<String, String?>>) {
        val root = JSONObject()
        for ((ip, pair) in devices) {
            root.put(ip, JSONObject().apply {
                put("mode", pair.first)
                put("name", pair.second ?: "")
            })
        }
        prefs(ctx).edit().putString(KEY, root.toString()).apply()
    }
}
