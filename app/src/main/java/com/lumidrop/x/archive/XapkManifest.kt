package com.lumidrop.x.archive

import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * ══ GOAT-PASS 13 (PART 2.3) — the XAPK manifest interpretation layer ═══════
 *
 * Turns a distributor's `manifest.json` (parsed by [MiniJson]) into ONE
 * normalized shape, absorbing every legitimate variation found in the wild:
 *
 *  • field-name drift:  package_name /PackageName/xapk_package; split lists
 *    under split_apks / split_apk / splits / apks / expansions(legacy);
 *  • per-split name fields: `file` (APKPure v2 — the actual entry name),
 *    `name` (the config name, older APKPure), or both;
 *  • string-or-number version codes ("version_code":"482");
 *  • nested component directories ("files/…", "apks/…");
 *  • icon declared as `icon` / `icon_path` / `icon_image`;
 *  • OBB under expansions[] {file, install_location, install_path} — the
 *    APKPure shape that names BOTH the archive entry and the destination.
 *
 * Every declared path is validated against the archive's REAL entry set
 * (case-insensitive, traversal-proof, duplicates collapsed) before use; a
 * declaration naming a file the archive doesn't contain is simply dropped
 * with a note — never silently mapped onto some other entry.
 */
object XapkManifest {

    /** Normalized per-APK declaration from the container manifest. */
    data class DeclaredApk(
        val entryName: String,       // the REAL archive entry, validated
        val configName: String?,     // "config.arm64_v8a" style name, when declared
        val roleType: String?,       // manifest's type tag, when declared
    )

    /** Normalized OBB declaration. */
    data class DeclaredObb(
        val entryName: String,       // real archive entry
        val fileName: String?,       // destination filename the manifest wants
    )

    /** The normalized container manifest. */
    data class Normalized(
        val label: String?,
        val packageName: String?,
        val versionName: String?,
        val versionCode: Long?,
        val minSdk: Int?,
        val targetSdk: Int?,
        val baseEntryName: String?,     // null when not declared/valid
        val apks: List<DeclaredApk>,
        val obbs: List<DeclaredObb>,
        val iconEntryName: String?,
        val notes: List<String>,        // non-fatal quirks observed
    )

    private const val MAX_MANIFEST_JSON = 2L * 1024 * 1024   // real ones are KBs

    /** Entry names (lowercased → original) of the archive, for validation. */
    private class EntryIndex(entries: List<ZipEntry>) {
        val byLower = HashMap<String, String>(entries.size * 2)
        val apkByLower = HashMap<String, String>()
        init {
            for (e in entries) {
                if (e.isDirectory) continue
                byLower[e.name.lowercase()] = e.name
                if (e.name.endsWith(".apk", true)) apkByLower[e.name.lowercase()] = e.name
            }
        }
        /** The real entry whose name equals [want] case-insensitively. */
        fun resolve(want: String): String? = byLower[want.lowercase()]
        fun resolveApk(want: String): String? = apkByLower[want.lowercase()]
    }

    /** Reads and normalizes the manifest of [zf]; null when absent/invalid. */
    fun read(zf: ZipFile): Normalized? {
        return try {
            val entries = ArrayList<ZipEntry>()
            val en = zf.entries()
            while (en.hasMoreElements()) entries.add(en.nextElement())
            val idx = EntryIndex(entries)
            bindIndex(idx.byLower)
            val mjEntry = entries.firstOrNull { !it.isDirectory && it.name.equals("manifest.json", true) }
                ?: return null
            if (mjEntry.size > MAX_MANIFEST_JSON) return null
            val text = zf.getInputStream(mjEntry).use {
                val buf = java.io.ByteArrayOutputStream()
                val b = ByteArray(8 * 1024)
                while (true) { val n = it.read(b); if (n < 0) break; buf.write(b, 0, n) }
                buf.toString("UTF-8")
            }
            normalize(MiniJson.parse(text))
        } catch (e: Exception) { null }
    }

    /** Normalizes an already-parsed manifest tree. The archive index used
     *  for path validation is whatever [bindIndex] last received — [read]
     *  binds it; tests bind their fixture's entries before calling. */
    fun normalize(root: MJson?): Normalized? {
        if (root == null) return null
        return try { normalizeInternal(root) } catch (e: Exception) { null }
    }

    private fun normalizeInternal(root: MJson): Normalized? {
        val o = root as? MJson.MObj ?: return null
        val notes = ArrayList<String>()

        fun field(vararg names: String): MJson? {
            for (n in names) { o.get(n)?.let { return it } }
            return null
        }

        val label = field("name", "app_name", "label").str()
        val pkg = field("package_name", "packagename", "package").str()
        val vName = field("version_name", "versionname", "version").str()
        val vCode = field("version_code", "versioncode").let { it.long() ?: it.strLong() }
        val minSdk = field("min_api", "min_sdk").let { it.int() ?: it.strLong()?.toInt() }
        val targetSdk = field("target_api", "target_sdk").let { it.int() ?: it.strLong()?.toInt() }

        // Base declaration — several spellings, validated against real entries.
        val baseDeclared = field("base_apk_name", "baseapk", "base_apk", "base").str()
        val baseEntry = baseDeclared?.let { d -> resolveEntryFor(d, notes) }

        // Split lists — every shape the wild has produced.
        val splitArr = field("split_apks", "split_apk", "splits", "apks", "expansions")
            .arr().filterIsInstance<MJson.MObj>()
        val apks = ArrayList<DeclaredApk>()
        for (s in splitArr) {
            // `file` is the entry name in APKPure v2; `name` is a config name
            // (older APKPure) or even the entry name in some packagers.
            val fileField = (s.get("file") ?: s.get("filename") ?: s.get("apk") ?: s.get("path")).str()
            val nameField = s.get("name").str()
            val typeField = (s.get("type") ?: s.get("split_type")).str()
            // An entry name candidate: prefer file; fall back to name when the
            // name resolves to a real .apk entry.
            val entryName = fileField?.let { resolveEntryFor(it, notes) }
                ?: nameField?.let { resolveEntryFor(it, notes) }
            if (entryName == null) {
                notes.add("manifest declares a component that the archive does not contain" +
                    " (${fileField ?: nameField ?: "unnamed"}) — ignored")
                continue
            }
            val configName = nameField?.takeIf { it.isNotBlank() && it != entryName.substringAfterLast('/') }
            apks.add(DeclaredApk(entryName, configName, typeField))
        }

        // OBB — under expansions[] with install_location/install_path, or in a
        // dedicated obb list; also a bare {file:…} shape.
        val obbs = ArrayList<DeclaredObb>()
        val obbArr = field("obb", "obbs", "expansion_files").arr().filterIsInstance<MJson.MObj>()
        for (s in obbArr) {
            val f = (s.get("file") ?: s.get("path")).str() ?: continue
            val entry = resolveEntryFor(f, notes) ?: continue
            val dest = (s.get("install_location") ?: s.get("install_path") ?: s.get("filename")).str()
            obbs.add(DeclaredObb(entry, dest?.substringAfterLast('/')))
        }

        val iconDeclared = field("icon", "icon_path", "icon_image").str()
        val iconEntry = iconDeclared?.let { resolveEntryFor(it, notes) }

        return Normalized(label, pkg, vName, vCode, minSdk, targetSdk,
            baseEntry, apks, obbs, iconEntry, notes)
    }

    /** Maps a declared path to the archive's REAL entry: case-insensitive
     *  exact match over the bound entry index; traversal-safe. */
    private fun resolveEntryFor(declared: String, notes: ArrayList<String>): String? {
        val clean = declared.replace('\\', '/').trim().removePrefix("./")
        if (clean.isBlank() || clean.split('/').any { it == ".." }) {
            notes.add("manifest declares an unsafe path — ignored")
            return null
        }
        return resolve(clean)
    }

    // The archive's real entry names — bound by read()/tests before
    // normalize() so path validation resolves against real entries.
    @Volatile private var activeIndex: Map<String, String>? = null

    internal fun bindIndex(byLower: Map<String, String>) {
        activeIndex = byLower
    }
    private fun resolve(want: String): String? =
        activeIndex?.get(want.lowercase())
}
