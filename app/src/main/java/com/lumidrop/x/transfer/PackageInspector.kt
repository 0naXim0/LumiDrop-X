package com.lumidrop.x.transfer

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * ══ GOAT-PASS 10 (PART 1.3) — the Android package archive inspector ═══════════
 *
 * A pure-JVM-friendly structural scanner for the ZIP-based Android package
 * containers this app can install:
 *
 *  • `.xapk` — APKPure's format: a ZIP whose root holds ONE base APK plus
 *    optional split APKs, a `manifest.json` (package_name, version_name,
 *    version_code, min_api/target_api and per-split names/types), optional
 *    `icon.png`, and optional `Android/obb/<package>/<name>.obb` expansion
 *    data.
 *  • `.apks` — the split-APK bundle format produced by ADB's
 *    `build-apks`/bundletool: a ZIP of `base.apk`, `split_config.*.apk` and
 *    possibly `toc.pb` — no JSON manifest; structure IS the metadata.
 *  • `.apkm` — APKMirror's format, also a ZIP of a base + splits (newer
 *    files; the older proprietary-compressed variant fails to open as a
 *    plain ZIP and is honestly reported unsupported).
 *  • `.apk` — an ordinary single-APK package (delegated to the platform's
 *    own parser for metadata; listed here only for one unified surface).
 *
 * Detection is STRUCTURAL, never extension-only: an archive is what its
 * ENTRIES say it is (a ZIP with a real `base.apk`/single-`*.apk` layout and
 * plausible package parts), not what its name claims. A ZIP with a `.xapk`
 * name but no APK inside is not a package and never reaches the installer.
 *
 * Every property the format genuinely provides is surfaced; everything the
 * format does not carry reports as null/unknown — the UI shows "Unknown",
 * never an invented value.
 */
object PackageInspector {

    /** What a file claims to be vs. what its bytes say it is. */
    enum class Kind { APK, XAPK, APKM, APKS, NOT_A_PACKAGE }

    /** One APK inside the container, classified by role. */
    data class InnerApk(
        val entryName: String,
        val sizeBytes: Long,
        val isBase: Boolean,
        /** The split's role when identified: "abi", "density", "lang",
         *  "feature", "asset" — null for unknown/base. */
        val splitType: String?,
        /** The manifest-declared name for XAPKs ("config.arm64_v8a" etc.),
         *  null when derived from the filename alone. */
        val declaredName: String? = null,
        /** ── GOAT-PASS 13: the APK's OWN binary-manifest identity, when its
         *  AndroidManifest.xml parsed. Null when unreadable (the filename
         *  heuristics then carry the classification, as in GP10). */
        val apkIdentity: com.lumidrop.x.archive.ApkManifestReader.ApkIdentity? = null,
    )

    /** One OBB/expansion entry found inside the archive. */
    data class ObbEntry(val entryName: String, val sizeBytes: Long)

    /** The full inspection result. [issues] carries the honest problems —
     *  a package with a fatal issue must not be offered for installation. */
    data class Report(
        val kind: Kind,
        val appLabel: String?,          // XAPK's label, or the base APK's label when the platform parser can read it
        val packageName: String?,       // from manifest.json / the base APK's own manifest
        val versionName: String?,
        val versionCode: Long?,
        val minSdk: Int?,               // manifest.json's min_api, or the APK's own uses-sdk
        val targetSdk: Int?,
        val baseApk: InnerApk?,         // exactly one base, or null when missing (fatal issue)
        val splits: List<InnerApk>,     // every non-base APK, sorted: abi, density, lang, feature, unknown
        val obbs: List<ObbEntry>,
        val totalApkBytes: Long,
        val hasManifestJson: Boolean,
        val iconPngEntry: String?,      // XAPK's icon.png entry, when present
        val issues: List<String>,       // "Missing base APK", "No APK entries", "Unsupported .apkm structure", …
        /** ── GOAT-PASS 13: why THIS apk was chosen as the base (user-facing
         *  honesty + the no-more-false-"missing-base" contract). */
        val baseSelectionReason: String? = null,
        /** ── GOAT-PASS 13: internal trace — every stage's outcome, for
         *  compatibility diagnostics; NEVER shown raw in the UI. */
        val diagnostics: List<String> = emptyList(),
    ) {
        val isInstallable: Boolean get() = kind != Kind.NOT_A_PACKAGE && baseApk != null &&
            issues.none { it.startsWith("Missing base") || it.startsWith("No APK") || it.startsWith("Unsupported") || it.startsWith("Components belong") || it.startsWith("Conflicting") || it.startsWith("Multiple") }
        val splitCount: Int get() = splits.size
    }

    private const val MAX_ENTRIES = 5_000
    private const val MAX_TOTAL_APK_BYTES = 4L * 1024 * 1024 * 1024   // 4 GB across all APK parts
    private val APK_SUFFIX = ".apk"

    // ── Public API ────────────────────────────────────────────────────────────

    /** Structural classification by extension + actual ZIP content. Cheap:
     *  opens the archive, lists entries, closes. Never parses APK payloads. */
    fun inspect(file: File): Report {
        val ext = file.extension.lowercase()
        if (ext == "apk") return inspectPlainApk(file)
        if (ext !in setOf("xapk", "apkm", "apks")) return notAPackage("Not an Android package archive")
        return try {
            ZipFile(file).use { zf -> inspectZipContainer(file, zf) }
        } catch (e: Exception) {
            // A ZIP container that cannot even be opened: APKM's proprietary
            // compressed variant is exactly this. Honest, not a crash. The
            // exception's class name rides along for diagnosability (never a
            // raw stack trace in the UI).
            notAPackage("Unsupported or damaged ${ext.uppercase()} structure (not a readable ZIP archive: ${e.javaClass.simpleName})")
        }
    }

    /** True when the extension alone makes a file worth inspecting. */
    fun isPackageExt(ext: String): Boolean =
        ext.lowercase() in setOf("apk", "xapk", "apkm", "apks")

    /** Extracts the XAPK's declared icon.png to a decode-ready bitmap,
     *  or null (the caller falls back to the vector file-type icon). */
    fun extractIcon(context: Context, file: File, report: Report?): Bitmap? {
        val entry = report?.iconPngEntry ?: return null
        return try {
            ZipFile(file).use { zf ->
                val e = zf.getEntry(entry) ?: return null
                zf.getInputStream(e).use { ins ->
                    BitmapFactory.decodeStream(ins, null,
                        BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                }
            }
        } catch (e: Exception) { null }
    }

    // ── Plain .apk ────────────────────────────────────────────────────────────

    private fun inspectPlainApk(file: File): Report {
        // The platform's own parser is authoritative for a real APK — one
        // call, no bytes read manually. Anything it cannot read is honestly
        // reported; a truncated/corrupt APK is NOT offered for install.
        return try {
            val pm = packageManagerRef?.invoke() ?: return apkFallbackReport(file, "Package parser unavailable")
            val info: PackageInfo = pm.getPackageArchiveInfo(file.absolutePath, 0)
                ?: return apkFallbackReport(file, "Not a readable APK package")
            Report(
                kind = Kind.APK,
                appLabel = info.applicationInfo?.loadLabel(pm)?.toString(),
                packageName = info.packageName,
                versionName = info.versionName,
                versionCode = pkgVersionCode(info),
                minSdk = if (android.os.Build.VERSION.SDK_INT >= 24 && info.applicationInfo != null)
                    info.applicationInfo.minSdkVersion else null,
                targetSdk = if (android.os.Build.VERSION.SDK_INT >= 24 && info.applicationInfo != null)
                    info.applicationInfo.targetSdkVersion else null,
                baseApk = InnerApk(file.name, file.length(), isBase = true, splitType = null),
                splits = emptyList(), obbs = emptyList(),
                totalApkBytes = file.length(),
                hasManifestJson = false, iconPngEntry = null,
                issues = emptyList(),
            )
        } catch (e: Exception) {
            apkFallbackReport(file, "Not a readable APK package")
        }
    }

    private fun apkFallbackReport(file: File, issue: String) = Report(
        kind = Kind.APK, appLabel = null, packageName = null, versionName = null, versionCode = null,
        minSdk = null, targetSdk = null,
        baseApk = InnerApk(file.name, file.length(), isBase = true, splitType = null),
        splits = emptyList(), obbs = emptyList(), totalApkBytes = file.length(),
        hasManifestJson = false, iconPngEntry = null, issues = listOf(issue),
    )

    // ── ZIP containers (.xapk/.apkm/.apks) ────────────────────────────────────

    internal fun inspectZipContainer(file: File, zf: ZipFile): Report {
        val ext = file.extension.lowercase()
        // One pass over the central directory — entries are the structure.
        val entries = ArrayList<ZipEntry>(minOf(zf.size(), MAX_ENTRIES))
        var truncated = false
        val en = zf.entries()
        while (en.hasMoreElements()) {
            if (entries.size >= MAX_ENTRIES) { truncated = true; break }
            entries.add(en.nextElement())
        }
        val names = entries.map { it.name }
        val apkEntries = entries.filter { it.name.endsWith(APK_SUFFIX, true) && !it.isDirectory }
        val manifestJsonEntry = names.firstOrNull { it.equals("manifest.json", true) }
        val obbEntries = entries.filter {
            it.name.startsWith("Android/obb/", true) && it.name.endsWith(".obb", true) && !it.isDirectory
        }.map { ObbEntry(it.name, it.size) }
        val iconEntry = names.firstOrNull { it.equals("icon.png", true) }
        val hasToc = names.any { it.equals("toc.pb", true) }

        val issues = ArrayList<String>()
        val diag = ArrayList<String>()
        if (truncated) issues.add("Archive has an excessive entry count — refused to scan further")
        if (apkEntries.isEmpty()) {
            issues.add("No APK entries found inside the archive")
            return containerReport(Kind.NOT_A_PACKAGE, null, null, null, null, null, null,
                null, emptyList(), obbEntries, 0, manifestJsonEntry != null, iconEntry, issues)
        }

        // ══ GOAT-PASS 13 (PART 2): the metadata-driven pipeline. Roles, ══
        // identity and versions come from each APK's OWN binary
        // AndroidManifest (ApkManifestReader) and the container manifest
        // (XapkManifest normalization) — filenames are supporting evidence
        // only. The GP10 filename heuristics remain as the FALLBACK when an
        // APK's manifest cannot be parsed (obfuscated/odd tools), so no
        // previously-working archive regresses.
        val xm = com.lumidrop.x.archive.XapkManifest.read(zf)
        if (xm != null) {
            diag.add("container manifest: package=${xm.packageName} version=${xm.versionName}(${xm.versionCode}) base=${xm.baseEntryName} splits=${xm.apks.size}")
            xm.notes.forEach { diag.add("container manifest note: $it") }
        } else {
            diag.add("container manifest: ${if (manifestJsonEntry == null) "absent" else "unparseable"}")
        }

        // Parse EVERY inner APK's own manifest (bounded: each manifest is KBs).
        data class Cand(val entry: ZipEntry, val id: com.lumidrop.x.archive.ApkManifestReader.ApkIdentity?)
        val cands = apkEntries.map { Cand(it, com.lumidrop.x.archive.ApkManifestReader.readFromZip(zf, it.name)) }
        cands.forEach { c ->
            diag.add("apk ${c.entry.name} (${c.entry.size} B): " + (c.id?.let {
                "package=${it.packageName} split=${it.splitName ?: "-"} vCode=${it.versionCode} feature=${it.isFeatureSplit} required=${it.isSplitRequired}"
            } ?: "manifest unreadable (not a ZIP with AndroidManifest.xml, not a bare AXML document)"))
        }

        // ── Base identification (PART 2.2's evidence hierarchy) ──────────
        // 1. The container manifest's declared base, validated.
        // 2. The APK whose OWN manifest declares a package and NO split
        //    attribute — the platform definition of a base APK.
        // 3. Exactly one APK overall (GP10's lone-apk rule).
        // 4. Filename conventions (base.apk / the single non-config APK).
        // Multiple genuine base candidates from DIFFERENT packages → an
        // honest ambiguity error, never a silent pick.
        var base: Cand? = null
        var baseReason: String? = null
        val declaredBase = xm?.baseEntryName
        val byManifest = cands.filter { it.id?.isBase == true }
        when {
            declaredBase != null && cands.any { it.entry.name.equals(declaredBase, true) } -> {
                base = cands.first { it.entry.name.equals(declaredBase, true) }
                baseReason = "declared by the archive manifest"
            }
            byManifest.size == 1 -> {
                base = byManifest[0]
                baseReason = "identified by its own package manifest (no split declaration)"
            }
            byManifest.size > 1 -> {
                val pkgs = byManifest.mapNotNull { it.id?.packageName }.toSet()
                if (pkgs.size > 1) {
                    issues.add("Conflicting base APK candidates — the archive mixes components of different apps (${pkgs.joinToString()})")
                    diag.add("base: AMBIGUOUS — multiple base-qualified APKs from $pkgs")
                } else {
                    // Same package, several base-qualified manifests (some
                    // tools stamp the base twice); prefer the largest, note it.
                    base = byManifest.maxByOrNull { it.entry.size }
                    baseReason = "multiple same-package base candidates — largest chosen"
                    diag.add("base: ${byManifest.size} same-package candidates, chose ${base?.entry?.name} by size")
                }
            }
            cands.size == 1 -> {
                base = cands[0]
                baseReason = "single APK in the archive"
            }
            else -> {
                // ── GOAT-PASS 14 (PART 3.3): container-manifest EXCLUSION ──
                // when no inner APK's own manifest parsed (obfuscated tools,
                // exotic compression), the container's split_apks[]
                // declarations are the archive's own statement of which
                // components are SPLITS; the one APK NOT declared as a split
                // is the base by elimination. The documented deterministic
                // strategy for the metadata-unreadable case — it selects
                // nothing when the declaration set doesn't uniquely exclude
                // exactly one candidate.
                val declared = xm?.apks?.map { it.entryName.lowercase() }?.toSet() ?: emptySet()
                val notDeclared = if (declared.isNotEmpty())
                    cands.filter { it.entry.name.lowercase() !in declared } else emptyList()
                if (notDeclared.size == 1) {
                    base = notDeclared[0]
                    baseReason = "the only APK not declared as a split by the archive manifest"
                } else {
                    if (declared.isNotEmpty())
                        diag.add("base: exclusion inconclusive — ${notDeclared.size} undeclared candidates")
                    // Filename fallback (GP10 behavior): literal base.apk,
                    // else the lone APK without a config-style name.
                    val literal = cands.firstOrNull { it.entry.name.equals("base.apk", true) }
                    val nonConfig = cands.filter { splitTypeOf(it.entry.name) == null }
                    if (literal != null) {
                        base = literal; baseReason = "named base.apk"
                    } else if (nonConfig.size == 1) {
                        base = nonConfig[0]
                        baseReason = "the only non-configuration APK (its own manifest is unreadable)"
                    } else {
                        base = null
                        issues.add("Missing base APK — no APK in this archive declares itself as the main application (no parseable package manifest, no archive-manifest declaration, and no unique base candidate)")
                        diag.add("base: NONE — candidates lack parseable manifests and names give no unique base")
                    }
                }
            }
        }
        if (base != null && baseReason != null) diag.add("base: ${base.entry.name} — $baseReason")

        // ── Splits + cross-validation (PART 2.2 steps 5–7) ───────────────
        val splitCands = cands.filter { it !== base }
        val basePkg = base?.id?.packageName ?: xm?.packageName
        val baseVCode = base?.id?.versionCode ?: xm?.versionCode
        val splits = ArrayList<InnerApk>()
        val dropped = ArrayList<String>()
        for (sc in splitCands) {
            val id = sc.id
            if (basePkg != null && id?.packageName != null && id.packageName != basePkg) {
                dropped.add("${sc.entry.name} (belongs to ${id.packageName}, not $basePkg)")
                continue
            }
            if (baseVCode != null && id?.versionCode != null && id.versionCode != baseVCode) {
                dropped.add("${sc.entry.name} (version ${id.versionCode} ≠ base $baseVCode)")
                continue
            }
            splits.add(InnerApk(
                entryName = sc.entry.name, sizeBytes = sc.entry.size, isBase = false,
                splitType = classifySplit(sc.entry.name, id, xm),
                declaredName = id?.splitName,
                apkIdentity = id,
            ))
        }
        dropped.forEach { diag.add("split dropped: $it") }
        if (dropped.isNotEmpty())
            issues.add("Some components were excluded because they do not match the base application: ${dropped.joinToString()}")
        splits.sortWith(compareBy({ splitTypeRank(it.splitType) }, { it.entryName }))

        val totalApkBytes = apkEntries.sumOf { it.size }
        if (totalApkBytes > MAX_TOTAL_APK_BYTES) issues.add("Package parts exceed the 4 GB safety ceiling")

        val kind = when (ext) {
            "xapk" -> Kind.XAPK
            "apks" -> Kind.APKS
            "apkm" -> if (base != null) Kind.APKM else {
                issues.add("Unsupported .apkm structure — no installable base APK found")
                Kind.NOT_A_PACKAGE
            }
            else -> Kind.NOT_A_PACKAGE
        }

        val baseInfo: InnerApk? = base?.let {
            InnerApk(it.entry.name, it.entry.size, isBase = true, splitType = null,
                declaredName = null, apkIdentity = it.id)
        }
        // Identity: the base APK's own manifest is authoritative now; the
        // container manifest fills gaps; the platform parser adds the label
        // only it can resolve (resource-backed labels).
        var label: String? = base?.id?.appLabel ?: xm?.label
        val pkg: String? = basePkg
        var vName: String? = base?.id?.versionName ?: xm?.versionName
        var vCode: Long? = baseVCode
        var minSdk: Int? = xm?.minSdk
        var targetSdk: Int? = xm?.targetSdk
        if (base != null && (label == null || minSdk == null)) {
            readBaseApkIdentity(file, zf, base.entry, null)?.let { (l, p, vn, vc, ms, ts) ->
                if (label == null) label = l
                if (pkg == null && p != null) { /* unreachable: pkg non-null only when base null */ }
                if (vName == null) vName = vn
                if (vCode == null) vCode = vc
                if (minSdk == null) minSdk = ms
                if (targetSdk == null) targetSdk = ts
            }
        }
        // The device's SDK level is injected (deviceSdkRef) so the JVM unit
        // tests can pin the classification logic without phantom issues.
        val deviceSdk = deviceSdkRef?.invoke() ?: android.os.Build.VERSION.SDK_INT
        if (deviceSdk > 0 && minSdk != null && minSdk!! > deviceSdk)
            issues.add("Package needs Android $minSdk or newer (this device reports $deviceSdk)")

        return containerReport(kind, label, pkg, vName, vCode, minSdk, targetSdk,
            baseInfo, splits, obbEntries, totalApkBytes, manifestJsonEntry != null, iconEntry, issues,
            baseReason, diag)
    }

    /** PART 2.2: a split's role from its OWN manifest name first (the
     *  authoritative source), the container manifest's type second, the
     *  filename last (the GP10 heuristic, kept as fallback). */
    private fun classifySplit(entryName: String,
                              id: com.lumidrop.x.archive.ApkManifestReader.ApkIdentity?,
                              xm: com.lumidrop.x.archive.XapkManifest.Normalized?): String? {
        id?.splitName?.let {
            // The split NAME ("config.arm64_v8a") is classified through the
            // same entry-name heuristic the filename path uses — suffixed
            // with .apk so the stem logic sees the identical shape.
            return splitTypeOf("$it.apk") ?: if (id.isFeatureSplit) "feature" else "asset"
        }
        val declared = xm?.apks?.firstOrNull { it.entryName.equals(entryName, true) }
        declared?.roleType?.let { t ->
            return when (t.lowercase()) {
                "abi" -> "abi"; "density", "screen" -> "density"; "lang", "language" -> "lang"
                "feature" -> "feature"; "asset", "asset_pack" -> "asset"; else -> null
            }
        }
        return splitTypeOf(entryName)
    }

    private fun containerReport(kind: Kind, label: String?, pkg: String?, vName: String?, vCode: Long?,
                                minSdk: Int?, targetSdk: Int?, base: InnerApk?, splits: List<InnerApk>,
                                obbs: List<ObbEntry>, total: Long, hasMj: Boolean, icon: String?,
                                issues: List<String>, baseReason: String? = null,
                                diag: List<String> = emptyList()) = Report(
        kind, label, pkg, vName, vCode, minSdk, targetSdk, base, splits, obbs, total, hasMj,
        icon, issues, baseReason, diag)

    /** Extracts the base APK to cacheDir, hands it to the platform parser,
     *  deletes the temp file. Null when anything fails — identity simply
     *  shows as Unknown rather than blocking install of a valid package. */
    private fun readBaseApkIdentity(archive: File, zf: ZipFile, baseEntry: ZipEntry,
                                    mj: JSONObject?): Identity6? {
        // manifest.json (XAPK) first — no extraction needed at all.
        try {
            if (mj != null) {
                val pkg = mj.optString("package_name").takeIf { it.isNotBlank() }
                if (pkg != null) {
                    return Identity6(
                        label = mj.optString("name").takeIf { it.isNotBlank() },
                        pkg = pkg,
                        vName = mj.optString("version_name").takeIf { it.isNotBlank() },
                        vCode = mj.optLong("version_code", -1L).takeIf { it > 0 },
                        minSdk = mj.optInt("min_api", 0).takeIf { it > 0 },
                        targetSdk = mj.optInt("target_api", 0).takeIf { it > 0 },
                    )
                }
            }
        } catch (e: Exception) { /* a manifest that cannot be interrogated → identity via the base APK path */ }
        val pm = packageManagerRef?.invoke() ?: return null
        val tmp = File.createTempFile("ldx-inspect-", ".apk", archive.parentFile ?: return null)
        return try {
            zf.getInputStream(baseEntry).use { ins ->
                tmp.outputStream().use { ins.copyTo(it) }
            }
            val info = pm.getPackageArchiveInfo(tmp.absolutePath, 0) ?: return null
            Identity6(
                label = info.applicationInfo?.loadLabel(pm)?.toString(),
                pkg = info.packageName,
                vName = info.versionName,
                vCode = pkgVersionCode(info),
                minSdk = if (android.os.Build.VERSION.SDK_INT >= 24 && info.applicationInfo != null)
                    info.applicationInfo.minSdkVersion else null,
                targetSdk = if (android.os.Build.VERSION.SDK_INT >= 24 && info.applicationInfo != null)
                    info.applicationInfo.targetSdkVersion else null,
            )
        } catch (e: Exception) { null } finally { try { tmp.delete() } catch (e: Exception) {} }
    }
    private data class Identity6(val label: String?, val pkg: String?, val vName: String?,
                                 val vCode: Long?, val minSdk: Int?, val targetSdk: Int?)

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** The split role implied by the canonical names of BOTH container
     *  families — bundletool's `split_config.*` (.apks) and APKPure's
     *  `config.*` (.xapk, whose manifest often omits the type). */
    internal fun splitTypeOf(name: String): String? {
        val n = name.lowercase()
        // Normalize to one family: "config.arm64_v8a.apk" and
        // "split_config.arm64_v8a.apk" both classify from "config.arm64_v8a".
        val stem = n.removePrefix("split_").substringBeforeLast('.')
        val langCodes = setOf("en","de","fr","es","it","ja","ko","zh","ru","pt","hi","ar","th","vi","id","tr","pl","nl","sv","da","fi","nb","cs","el","he","hu","ro","uk","ms","bn","ta","te","mr","fa","sw")
        return when {
            !stem.startsWith("config.") -> null
            stem == "config.arm64_v8a" || stem == "config.armeabi_v7a" || stem == "config.x86" ||
                stem == "config.x86_64" || stem.startsWith("config.arm") || stem.startsWith("config.x86") -> "abi"
            stem.contains("dpi") || stem.contains("density") -> "density"
            langCodes.contains(stem.removePrefix("config.")) -> "lang"
            else -> "feature"
        }
    }

    private fun splitTypeRank(t: String?): Int = when (t) { "abi" -> 0; "density" -> 1; "lang" -> 2; "feature" -> 3; else -> 4 }

    private fun pkgVersionCode(info: PackageInfo): Long =
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()

    private fun notAPackage(issue: String) = Report(
        Kind.NOT_A_PACKAGE, null, null, null, null, null, null, null, emptyList(), emptyList(),
        0, false, null, listOf(issue))

    /** Injected by the app (a PackageManager) — kept indirect so the JVM
     *  unit tests can exercise the pure structural logic without Android. */
    @Volatile var packageManagerRef: (() -> PackageManager?)? = { null }

    /** The device's SDK level, injectable for the same JVM-test reason. */
    @Volatile var deviceSdkRef: (() -> Int)? = null
}
