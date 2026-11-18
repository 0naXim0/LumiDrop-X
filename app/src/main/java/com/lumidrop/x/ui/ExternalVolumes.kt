package com.lumidrop.x.ui

import android.content.Context
import java.io.File

/**
 * ══ Pass-44 (§8): removable-storage discovery for the Files sheet ══
 *
 * Finds every MOUNTED, readable removable volume (SD card / USB OTG) whose
 * root this app can browse as plain [java.io.File] paths.
 *
 * Detection uses ONLY public, all-version APIs:
 *  • [Context.getExternalFilesDirs] (public since API 19) returns the app's
 *    external files dir on EVERY mounted volume — primary internal storage
 *    first (`/storage/emulated/0/Android/data/<pkg>/files`), then one entry
 *    per removable volume (`/storage/XXXX-XXXX/Android/data/<pkg>/files`).
 *    The volume ROOT is everything before the `/Android` segment.
 *  • Non-removable primary storage is identified by the emulated prefix,
 *    so the internal entry never appears as a switchable "volume".
 *
 * This works on every Android version this app supports because the app
 * already holds MANAGE_EXTERNAL_STORAGE (its whole file-server model is
 * built on it): with that grant, ordinary File reads/writes against
 * `/storage/XXXX-XXXX/...` are permitted. No SAF indirection is needed and
 * none is pretended: if the grant is absent (or a volume is ejected,
 * unmounted or permission-revoked) the volume simply stops appearing here —
 * nothing stale is ever surfaced.
 *
 * The list is derived from the LIVE array on every call, so a volume
 * removed mid-session disappears from the switcher on the next query; the
 * sheet's own availability guard (load()) handles a current directory that
 * vanished underneath the user.
 */
data class RemovableVolume(val label: String, val root: File) {
    val id: String get() = root.absolutePath
}

object ExternalVolumes {

    /** Every mounted, readable removable volume right now (empty list =
     * internal-only — the storage switcher stays hidden entirely). */
    fun mounted(context: Context): List<RemovableVolume> {
        val out = LinkedHashMap<String, RemovableVolume>()
        // Primary emulated storage prefix — getExternalStorageDirectory()
        // (e.g. "/storage/emulated"). Entries under it are the internal
        // volume, not removable ones.
        val primaryPrefix = try {
            @Suppress("DEPRECATION")
            android.os.Environment.getExternalStorageDirectory().parentFile?.absolutePath
                ?: "/storage/emulated"
        } catch (e: Exception) { "/storage/emulated" }

        val dirs = try { context.getExternalFilesDirs(null) } catch (e: Exception) { null } ?: return emptyList()
        for (dir in dirs) {
            if (dir == null) continue
            val path = dir.absolutePath
            val cut = path.indexOf("/Android/")
            if (cut <= 0) continue
            val root = File(path.substring(0, cut))
            val rp = root.absolutePath
            if (rp.startsWith(primaryPrefix)) continue        // internal storage
            // Mount ids look like "XXXX-XXXX" (vfat/exfat volumes) — a clean
            // short label. Anything else falls back to the last path segment.
            val seg = rp.substringAfterLast('/')
            val label = if (seg.length == 9 && seg[4] == '-')
                "SD / USB · $seg"
            else if (seg.isNotBlank()) "External · $seg"
            else "External storage"
            // Only volumes that are actually readable surface — an ejected
            // card can keep a stale app-dir entry on some builds.
            val readable = try { root.exists() && root.canRead() } catch (e: Exception) { false }
            if (!readable) continue
            // GOAT-PASS 9 (Android 5–7): a plain contains-check instead of
            // Map.putIfAbsent — a Java-8 default method platform maps only
            // carry from API 24.
            if (!out.containsKey(rp)) out[rp] = RemovableVolume(label, root)
        }
        return out.values.toList()
    }

    /** The internal-storage root the sheet browses by default (the same
     *  root the pre-Pass-44 sheet always used). */
    fun internalRoot(): File = try {
        @Suppress("DEPRECATION")
        android.os.Environment.getExternalStorageDirectory()
    } catch (e: Exception) { File("/storage/emulated/0") }
}
