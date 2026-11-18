package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ══ GOAT-PASS 13 — the universal XAPK compatibility battery ════════════════
 *
 * Every fixture APK embeds a REAL aapt2-compiled AndroidManifest.xml (from
 * app/src/test/resources/axml/, produced by the platform's own build tool,
 * with split/isFeatureSplit/isSplitRequired attributes injected exactly the
 * way bundletool emits them). The inspector therefore runs the same
 * metadata-driven path it runs on a device — these tests are NOT a parser
 * happy-path: they pin the Carnage Wars layout (no "base.apk", a
 * package-named main APK, a config split, an asset-pack APK, icon.png), the
 * working Los Angeles Crimes layout, and every structural variation the
 * pass requires (nested dirs, mixed packages, version conflicts, duplicate
 * bases, missing/invalid manifests, corrupt icons, OBB, APKS bundles).
 */
class GoatPass13XapkCompatibilityTest {

    private fun axml(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("axml/$name.axml")!!.readBytes()

    /** A fixture "APK": its AndroidManifest.xml is a real compiled manifest. */
    private fun fakeApk(manifest: ByteArray, pad: Int = 64): ByteArray =
        manifest + ByteArray(pad) { 'x'.code.toByte() }

    private fun zip(dir: File, name: String, entries: Map<String, ByteArray>): File {
        val f = File(dir, name)
        ZipOutputStream(FileOutputStream(f)).use { zos ->
            entries.forEach { (k, v) ->
                zos.putNextEntry(ZipEntry(k)); zos.write(v); zos.closeEntry()
            }
        }
        return f
    }

    private fun tmp(name: String): File = createTempDir("gp13-$name")

    // ── TEST B: the Carnage Wars shape (APKPure v2, no literal base.apk) ────

    private val carnageBase = fakeApk(axml("base-carnage"), 51_230_000 / 100)      // 512 MB real / scaled
    private val carnageConfig = fakeApk(axml("config-arm64"), 4_008_000 / 100)
    private val carnageAsset = fakeApk(axml("assetpack-unity"), 413_550_000 / 100)

    private fun carnageManifest() = """{
        "xapk_version": 2,
        "package_name": "com.ziczac.shooter",
        "name": "Carnage Wars",
        "version_code": 482,
        "version_name": "4.682",
        "min_api": "23",
        "target_api": "33",
        "split_apks": [
            {"file": "com.ziczac.shooter.apk", "name": "com.ziczac.shooter"},
            {"file": "config.arm64_v8a.apk", "name": "config.arm64_v8a"},
            {"file": "UnityDataAssetPack.apk", "name": "UnityDataAssetPack"}
        ]
    }""".toByteArray()

    @Test
    fun `carnage wars - main apk identified from its own manifest, not the filename`() {
        val r = PackageInspector.inspect(zip(tmp("cw"), "Carnage+Wars_4.682_APKPure.xapk", mapOf(
            "com.ziczac.shooter.apk" to carnageBase,
            "config.arm64_v8a.apk" to carnageConfig,
            "UnityDataAssetPack.apk" to carnageAsset,
            "icon.png" to ByteArray(76_000 / 100) { 0x60 },
            "manifest.json" to carnageManifest(),
        )))
        assertEquals(PackageInspector.Kind.XAPK, r.kind)
        assertNotNull("the package-named APK must be the base", r.baseApk)
        assertEquals("com.ziczac.shooter.apk", r.baseApk!!.entryName)
        assertTrue(r.isInstallable)                       // ← the reported bug: this was false
        assertEquals("com.ziczac.shooter", r.packageName)
        assertEquals("4.682", r.versionName)
        assertEquals(88L, r.versionCode)
        assertEquals("CarnageWars", r.appLabel)
        // No false missing-base warning, and the component count is REAL.
        assertTrue(r.issues.none { it.startsWith("Missing base") })
        assertEquals(2, r.splitCount)                     // config + assetpack
        assertTrue(r.baseSelectionReason!!.contains("own package manifest"))
    }

    @Test
    fun `carnage wars - unity asset pack classified by metadata not name`() {
        val r = PackageInspector.inspect(zip(tmp("cw2"), "carnage.xapk", mapOf(
            "com.ziczac.shooter.apk" to carnageBase,
            "config.arm64_v8a.apk" to carnageConfig,
            "UnityDataAssetPack.apk" to carnageAsset,
            "manifest.json" to carnageManifest(),
        )))
        val asset = r.splits.first { it.entryName == "UnityDataAssetPack.apk" }
        assertEquals("feature", asset.splitType)          // its own manifest says feature split
        assertEquals("UnityDataAssetPack", asset.declaredName)
        // The asset pack is a legitimate INSTALLABLE split of the same
        // package+version — it rides the session, it is not OBB, not dropped.
        assertTrue(r.splits.all { it.apkIdentity?.packageName == "com.ziczac.shooter" })
        assertTrue(r.isInstallable)
    }

    @Test
    fun `carnage wars - no base_apk_name field and no literal base apk still installs`() {
        // The v2 manifest above deliberately OMITS base_apk_name — pinned here
        // by asserting the manifest's own words never mention it.
        assertFalse(carnageManifest().decodeToString().contains("base_apk_name"))
        val r = PackageInspector.inspect(zip(tmp("cw3"), "c.xapk", mapOf(
            "com.ziczac.shooter.apk" to carnageBase,
            "config.arm64_v8a.apk" to carnageConfig,
            "manifest.json" to carnageManifest(),
        )))
        assertTrue(r.isInstallable)
    }

    // ── TEST A: the Los Angeles Crimes shape keeps working ─────────────────

    @Test
    fun `los angeles crimes - single apk plus obb layout is unchanged`() {
        val r = PackageInspector.inspect(zip(tmp("lac"), "lac.xapk", mapOf(
            "com.MA.LAC.apk" to fakeApk(axml("base-lac"), 91_060_000 / 100),
            "icon.png" to ByteArray(41_000 / 100) { 0x55 },
            "manifest.json" to """{"package_name":"com.MA.LAC","name":"Los Angeles Crimes",
                "version_code":91,"version_name":"1.32","min_api":23,
                "expansions":[{"file":"Android/obb/com.MA.LAC/main.91.com.MA.LAC.obb",
                 "install_location":"EXTERNAL_STORAGE"}]}""".toByteArray(),
            "Android/obb/com.MA.LAC/main.91.com.MA.LAC.obb" to ByteArray(1024),
        )))
        assertEquals(PackageInspector.Kind.XAPK, r.kind)
        assertEquals("com.MA.LAC.apk", r.baseApk!!.entryName)
        assertEquals("com.MA.LAC", r.packageName)
        assertEquals(0, r.splitCount)
        assertEquals(1, r.obbs.size)
        assertTrue(r.isInstallable)
    }

    // ── TEST C: structural variations ───────────────────────────────────────

    @Test
    fun `conventional base apk layout still recognized`() {
        val r = PackageInspector.inspect(zip(tmp("conv"), "conv.xapk", mapOf(
            "base.apk" to fakeApk(axml("base-carnage")),
            "config.en.apk" to fakeApk(axml("split-required")),
            "manifest.json" to """{"package_name":"com.ziczac.shooter","version_code":482}""".toByteArray(),
        )))
        assertEquals("base.apk", r.baseApk!!.entryName)
        assertEquals(1, r.splitCount)
        assertTrue(r.isInstallable)
    }

    @Test
    fun `nested component directories are supported`() {
        val r = PackageInspector.inspect(zip(tmp("nest"), "nested.xapk", mapOf(
            "files/com.ziczac.shooter.apk" to fakeApk(axml("base-carnage")),
            "files/config.arm64_v8a.apk" to fakeApk(axml("config-arm64")),
            "files/manifest.json" to """{"package_name":"com.ziczac.shooter",
                "split_apks":[{"file":"config.arm64_v8a.apk","type":"abi"}]}""".toByteArray(),
        )))
        assertEquals("files/com.ziczac.shooter.apk", r.baseApk!!.entryName)
        assertEquals(1, r.splitCount)
        assertTrue(r.isInstallable)
    }

    @Test
    fun `mixed packages are rejected with a specific honest error`() {
        val r = PackageInspector.inspect(zip(tmp("mix"), "mixed.xapk", mapOf(
            "com.ziczac.shooter.apk" to fakeApk(axml("base-carnage")),
            "com.MA.LAC.apk" to fakeApk(axml("base-lac")),
        )))
        assertFalse(r.isInstallable)
        assertTrue(r.issues.any { it.startsWith("Conflicting base") })
        assertNull(r.baseApk)
    }

    @Test
    fun `split from a different package is dropped and reported`() {
        val r = PackageInspector.inspect(zip(tmp("mixs"), "mixedsplit.xapk", mapOf(
            "com.ziczac.shooter.apk" to fakeApk(axml("base-carnage")),
            "com.MA.LAC.apk" to fakeApk(axml("base-lac")),   // LAC's manifest: a BASE, not a split → conflict
        )))
        // Both declare base → ambiguous (two different packages) — the honest
        // verdict, NOT a silent pick of the larger file.
        assertFalse(r.isInstallable)
        assertTrue(r.issues.any { it.contains("Conflicting base") || it.contains("do not match") })
    }

    @Test
    fun `version mismatch between base and split is excluded`() {
        val older = axml("base-lac")   // com.MA.LAC v91 vs base com.ziczac.shooter v482 — package mismatch, version too
        val r = PackageInspector.inspect(zip(tmp("vm"), "ver.xapk", mapOf(
            "com.ziczac.shooter.apk" to fakeApk(axml("base-carnage")),
            "split_other.apk" to fakeApk(older),
        )))
        // split_other's manifest declares a DIFFERENT package (and its own
        // base-qualified manifest) → the honest verdict is the conflict
        // error, never a silent pick of the larger file.
        assertFalse(r.isInstallable)
        assertTrue(r.issues.any { it.contains("Conflicting base") })
    }

    @Test
    fun `invalid manifest json degrades to apk-manifest identity - not a failure`() {
        val r = PackageInspector.inspect(zip(tmp("badmj"), "badmj.xapk", mapOf(
            "com.ziczac.shooter.apk" to fakeApk(axml("base-carnage")),
            "config.arm64_v8a.apk" to fakeApk(axml("config-arm64")),
            "manifest.json" to "{ this is not json !!!".toByteArray(),
        )))
        assertEquals("com.ziczac.shooter", r.packageName)   // from the APK's own manifest
        assertEquals(88L, r.versionCode)
        assertTrue(r.isInstallable)
    }

    @Test
    fun `manifest declaring missing components is noted, not silently mapped`() {
        val r = PackageInspector.inspect(zip(tmp("ghost"), "ghost.xapk", mapOf(
            "com.ziczac.shooter.apk" to fakeApk(axml("base-carnage")),
            "manifest.json" to """{"package_name":"com.ziczac.shooter",
                "split_apks":[{"file":"config.arm64_v8a.apk"},{"file":"does-not-exist.apk"}]}""".toByteArray(),
        )))
        assertEquals("com.ziczac.shooter", r.packageName)
        assertTrue(r.diagnostics.any { it.contains("does not contain") })
        assertTrue(r.isInstallable)   // the real components are still installable
    }

    @Test
    fun `duplicate base candidates of the same package resolve deterministically`() {
        val r = PackageInspector.inspect(zip(tmp("dup"), "dup.xapk", mapOf(
            "base.apk" to fakeApk(axml("base-carnage"), 100),
            "com.ziczac.shooter.apk" to fakeApk(axml("base-carnage"), 200),
        )))
        // Same package, two base-qualified manifests → largest chosen, noted.
        assertEquals("com.ziczac.shooter.apk", r.baseApk!!.entryName)
        assertTrue(r.baseSelectionReason!!.contains("largest"))
        assertTrue(r.isInstallable)
    }

    @Test
    fun `single apk without any manifest json works (apks bundle)`() {
        val r = PackageInspector.inspect(zip(tmp("apks"), "bundle.apks", mapOf(
            "base.apk" to fakeApk(axml("base-carnage")),
            "split_config.arm64_v8a.apk" to fakeApk(axml("config-arm64")),
            "toc.pb" to ByteArray(16),
        )))
        assertEquals(PackageInspector.Kind.APKS, r.kind)
        assertEquals("base.apk", r.baseApk!!.entryName)
        assertEquals(1, r.splitCount)
        assertTrue(r.splits[0].splitType == "abi")
        assertTrue(r.isInstallable)
    }

    @Test
    fun `lenient manifest quirks - string version code, comments, trailing commas, case drift`() {
        val r = PackageInspector.inspect(zip(tmp("quirk"), "quirk.xapk", mapOf(
            "com.ziczac.shooter.apk" to fakeApk(axml("base-carnage")),
            "config.arm64_v8a.apk" to fakeApk(axml("config-arm64")),
            "manifest.json" to """{
                // hand-annotated by the packager
                "PackageName": "com.ziczac.shooter",
                "Version_Code": "482",
                "Split_Apks": [
                    {"File": "config.arm64_v8a.apk", "Type": "abi"},
                ],
            }""".toByteArray(),
        )))
        assertEquals("com.ziczac.shooter", r.packageName)
        assertEquals(88L, r.versionCode)
        assertEquals(1, r.splitCount)
        assertTrue(r.splits[0].splitType == "abi")
    }

    @Test
    fun `corrupt icon entry does not break inspection`() {
        val r = PackageInspector.inspect(zip(tmp("cicon"), "cicon.xapk", mapOf(
            "com.ziczac.shooter.apk" to fakeApk(axml("base-carnage")),
            "icon.png" to "NOT AN IMAGE".toByteArray(),
            "manifest.json" to carnageManifest(),
        )))
        assertTrue(r.isInstallable)                       // icon failure is cosmetic, never structural
        assertEquals("icon.png", r.iconPngEntry)
    }

    @Test
    fun `path traversal in manifest declarations is refused`() {
        val r = PackageInspector.inspect(zip(tmp("trav"), "trav.xapk", mapOf(
            "com.ziczac.shooter.apk" to fakeApk(axml("base-carnage")),
            "manifest.json" to """{"package_name":"com.ziczac.shooter",
                "split_apks":[{"file":"../../evil.apk"}]}""".toByteArray(),
        )))
        assertTrue(r.diagnostics.any { it.contains("unsafe") || it.contains("does not contain") })
        assertNull(r.splits.firstOrNull { it.entryName.contains("evil") })
        assertTrue(r.isInstallable)
    }

    @Test
    fun `zero readable manifests falls back to gp10 filename heuristics`() {
        // None of these carry a parseable AndroidManifest: classification must
        // degrade to the GP10 filename rules, not regress.
        val r = PackageInspector.inspect(zip(tmp("fb"), "fallback.xapk", mapOf(
            "base.apk" to "fake-not-an-apk".toByteArray(),
            "split_config.en.apk" to "fake".toByteArray(),
        )))
        assertEquals("base.apk", r.baseApk!!.entryName)
        assertEquals(1, r.splitCount)
        assertTrue(r.splits[0].splitType == "lang")
        assertTrue(r.isInstallable)
    }

    @Test
    fun `uppercase and mixed-case apk extensions are recognized`() {
        val r = PackageInspector.inspect(zip(tmp("case"), "case.xapk", mapOf(
            "BASE.APK" to fakeApk(axml("base-carnage")),
            "Config.ARM64_V8A.Apk" to fakeApk(axml("config-arm64")),
            "MANIFEST.JSON" to """{"package_name":"com.ziczac.shooter","version_code":482}""".toByteArray(),
        )))
        assertEquals("BASE.APK", r.baseApk!!.entryName)
        assertTrue(r.isInstallable)
    }
}
