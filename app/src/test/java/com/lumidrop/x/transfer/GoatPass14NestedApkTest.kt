package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ══ GOAT-PASS 14 — the nested-APK compatibility battery ════════════════════
 *
 * GP13's fixtures were BARE AXML bytes standing in for APKs — which passed
 * the reader's direct-AXML path while every REAL .apk (a nested ZIP whose
 * AndroidManifest.xml lives INSIDE, 51–413 MB) hit the two production bugs
 * this pass fixes: the entry-level 8 MB precheck and the missing nested-ZIP
 * hop. These fixtures are honest: every component is a REAL ZIP APK built
 * with aapt2-compiled manifests inside, including the exact Carnage Wars
 * layout (package-named main APK, config split, Unity asset pack, no
 * "base.apk"), plus entry-order and STORED-method variations that real
 * packagers emit.
 */
class GoatPass14NestedApkTest {

    private fun axml(name: String): ByteArray =
        javaClass.classLoader.getResourceAsStream("axml/$name.axml")!!.readBytes()

    /** A REAL APK: a ZIP whose first entry is the compiled AndroidManifest. */
    private fun apk(manifest: ByteArray, extra: List<Pair<String, ByteArray>> = emptyList()): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("AndroidManifest.xml")); zos.write(manifest); zos.closeEntry()
            extra.forEach { (k, v) -> zos.putNextEntry(ZipEntry(k)); zos.write(v); zos.closeEntry() }
        }
        return bos.toByteArray()
    }

    /** APK with the manifest LAST and other entries first (order variation). */
    private fun apkManifestLast(manifest: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.putNextEntry(ZipEntry("classes.dex")); zos.write(ByteArray(64) { 0x6a }); zos.closeEntry()
            zos.putNextEntry(ZipEntry("res/layout.xml")); zos.write(ByteArray(32) { 0x6b }); zos.closeEntry()
            zos.putNextEntry(ZipEntry("AndroidManifest.xml")); zos.write(manifest); zos.closeEntry()
        }
        return bos.toByteArray()
    }

    /** APK written with STORED (uncompressed) entries — some repackagers do. */
    private fun apkStored(manifest: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zos ->
            zos.setMethod(ZipOutputStream.STORED)
            val e = ZipEntry("AndroidManifest.xml").apply { size = manifest.size.toLong(); compressedSize = manifest.size.toLong(); crc = java.util.zip.CRC32().apply { update(manifest) }.value }
            zos.putNextEntry(e); zos.write(manifest); zos.closeEntry()
        }
        return bos.toByteArray()
    }

    private fun zip(dir: File, name: String, entries: Map<String, ByteArray>): File {
        val f = File(dir, name)
        ZipOutputStream(FileOutputStream(f)).use { zos ->
            entries.forEach { (k, v) -> zos.putNextEntry(ZipEntry(k)); zos.write(v); zos.closeEntry() }
        }
        return f
    }

    private fun tmp(n: String) = createTempDir("gp14-$n")

    private val carnageManifestJson = """{
        "xapk_version": 2,
        "package_name": "com.ziczac.shooter",
        "name": "CarnageWars",
        "version_code": 88,
        "version_name": "4.682",
        "min_api": "23",
        "split_apks": [
            {"file": "config.arm64_v8a.apk", "name": "config.arm64_v8a"},
            {"file": "UnityDataAssetPack.apk", "name": "UnityDataAssetPack"}
        ]
    }""".toByteArray()

    // ── Test A: the exact Carnage Wars layout, real nested APKs ─────────────

    @Test
    fun `carnage wars - main apk identified from its nested manifest, installable`() {
        val r = PackageInspector.inspect(zip(tmp("cw"), "Carnage+Wars_4.682_APKPure.xapk", mapOf(
            "com.ziczac.shooter.apk" to apk(axml("base-carnage"),
                listOf("assets/bin/Data/Managed/etc" to ByteArray(128))),
            "config.arm64_v8a.apk" to apk(axml("config-arm64")),
            "UnityDataAssetPack.apk" to apk(axml("assetpack-unity"),
                listOf("assets/AssetPack/bigfile.bin" to ByteArray(2048))),
            "icon.png" to ByteArray(76_000 / 10) { 0x60 },
            "manifest.json" to carnageManifestJson,
        )))
        // THE acceptance assertions of this pass.
        assertEquals("com.ziczac.shooter.apk", r.baseApk?.entryName)
        assertTrue(r.isInstallable)
        assertEquals("com.ziczac.shooter", r.packageName)
        assertEquals("4.682", r.versionName)
        assertEquals(88L, r.versionCode)
        assertEquals("CarnageWars", r.appLabel)
        assertTrue(r.issues.none { it.startsWith("Missing base") })
        assertEquals(2, r.splitCount)
        assertTrue(r.baseSelectionReason!!.contains("own package manifest"))
        // Component-level classification from each APK's OWN nested manifest:
        val cfg = r.splits.first { it.entryName == "config.arm64_v8a.apk" }
        assertEquals("abi", cfg.splitType)
        val unity = r.splits.first { it.entryName == "UnityDataAssetPack.apk" }
        assertEquals("feature", unity.splitType)                 // its own manifest says so
        assertEquals("UnityDataAssetPack", unity.declaredName)
        // Per-component diagnostics recorded for forensics.
        assertTrue(r.diagnostics.any { it.contains("com.ziczac.shooter.apk") && it.contains("package=com.ziczac.shooter") })
    }

    @Test
    fun `carnage wars - manifest-last and stored apk variants still read`() {
        for ((tag, bytes) in listOf(
            "manifest-last" to apkManifestLast(axml("base-carnage")),
            "stored" to apkStored(axml("base-carnage")))) {
            val r = PackageInspector.inspect(zip(tmp("v-$tag"), "v.xapk", mapOf(
                "com.ziczac.shooter.apk" to bytes,
                "config.arm64_v8a.apk" to apk(axml("config-arm64")),
                "manifest.json" to carnageManifestJson,
            )))
            assertEquals("[$tag] base", "com.ziczac.shooter.apk", r.baseApk?.entryName)
            assertTrue("[$tag] installable", r.isInstallable)
            assertEquals("[$tag] splits", 1, r.splitCount)
        }
    }

    @Test
    fun `large component sizes are no longer a precheck rejection`() {
        // 12 MB of padding INSIDE the nested APK — far past GP13's broken 8 MB
        // entry-level precheck; the manifest is still a few KB in.
        val big = apk(axml("assetpack-unity"), listOf("assets/huge.bin" to ByteArray(12 * 1024 * 1024)))
        val r = PackageInspector.inspect(zip(tmp("big"), "big.xapk", mapOf(
            "com.ziczac.shooter.apk" to apk(axml("base-carnage")),
            "UnityDataAssetPack.apk" to big,
            "manifest.json" to carnageManifestJson,
        )))
        assertEquals("com.ziczac.shooter.apk", r.baseApk?.entryName)
        assertEquals(1, r.splitCount)
        assertTrue(r.isInstallable)
    }

    // ── Test C: LAC regression with a REAL nested APK ───────────────────────

    @Test
    fun `los angeles crimes - nested single-apk layout unchanged`() {
        val r = PackageInspector.inspect(zip(tmp("lac"), "lac.xapk", mapOf(
            "com.MA.LAC.apk" to apk(axml("base-lac"), listOf("AndroidObbExtra" to ByteArray(64))),
            "icon.png" to ByteArray(41) { 0x55 },
            "manifest.json" to """{"package_name":"com.MA.LAC","name":"Los Angeles Crimes",
                "version_code":91,"version_name":"1.32","min_api":23}""".toByteArray(),
            "Android/obb/com.MA.LAC/main.91.com.MA.LAC.obb" to ByteArray(1024),
        )))
        assertEquals("com.MA.LAC.apk", r.baseApk?.entryName)
        assertEquals("com.MA.LAC", r.packageName)
        assertEquals(0, r.splitCount)
        assertEquals(1, r.obbs.size)
        assertTrue(r.isInstallable)
    }

    // ── Exclusion fallback (no readable inner manifests at all) ─────────────

    @Test
    fun `exclusion fallback - unparseable apks resolved via container split declarations`() {
        val r = PackageInspector.inspect(zip(tmp("excl"), "excl.xapk", mapOf(
            "com.ziczac.shooter.apk" to "obfuscated-not-a-zip".toByteArray(),
            "config.arm64_v8a.apk" to "obfuscated-not-a-zip".toByteArray(),
            "UnityDataAssetPack.apk" to "obfuscated-not-a-zip".toByteArray(),
            "manifest.json" to carnageManifestJson,
        )))
        // No inner manifest parses; the container declares the two splits;
        // the undeclared APK is the base by elimination — deterministic.
        assertEquals("com.ziczac.shooter.apk", r.baseApk?.entryName)
        assertTrue(r.baseSelectionReason!!.contains("not declared as a split"))
        assertTrue(r.isInstallable)
    }

    @Test
    fun `exclusion inconclusive when declarations do not isolate a base`() {
        val r = PackageInspector.inspect(zip(tmp("excl2"), "excl2.xapk", mapOf(
            "one.apk" to "junk".toByteArray(),
            "two.apk" to "junk".toByteArray(),
            "manifest.json" to """{"package_name":"com.example","version_code":1,
                "split_apks":[{"file":"one.apk"},{"file":"two.apk"}]}""".toByteArray(),
        )))
        // BOTH junk APKs are declared splits → ZERO undeclared candidates →
        // exclusion isolates nothing → the honest missing-base verdict.
        assertNull(r.baseApk)
        assertFalse(r.isInstallable)
        assertTrue(r.issues.any { it.startsWith("Missing base") })
    }

    // ── Reader-level honesty: both forms, garbage, empty ────────────────────

    @Test
    fun `reader accepts nested apk and bare axml, rejects garbage`() {
        val nested = com.lumidrop.x.archive.ApkManifestReader.readApkOrAxml(apk(axml("base-carnage")).inputStream())
        assertNotNull(nested)
        assertEquals("com.ziczac.shooter", nested!!.packageName)
        val bare = com.lumidrop.x.archive.ApkManifestReader.readApkOrAxml(axml("base-carnage").inputStream())
        assertNotNull(bare)                              // the fixture path stays supported
        assertNull(com.lumidrop.x.archive.ApkManifestReader.readApkOrAxml("garbage".toByteArray().inputStream()))
        assertNull(com.lumidrop.x.archive.ApkManifestReader.readApkOrAxml(byteArrayOf().inputStream()))
        // A ZIP with NO AndroidManifest.xml inside is not a parseable APK.
        val noMf = ByteArrayOutputStream()
        ZipOutputStream(noMf).use { it.putNextEntry(ZipEntry("classes.dex")); it.write(ByteArray(8)); it.closeEntry() }
        assertNull(com.lumidrop.x.archive.ApkManifestReader.readApkOrAxml(noMf.toByteArray().inputStream()))
    }

    // ── Mixed/invalid variants over nested APKs (the GP13 suite, re-pinned) ─

    @Test
    fun `conventional base apk nested layout still recognized`() {
        val r = PackageInspector.inspect(zip(tmp("conv"), "conv.xapk", mapOf(
            "base.apk" to apk(axml("base-carnage")),
            "split_config.en.apk" to apk(axml("split-required")),
            "toc.pb" to ByteArray(16),
        )))
        assertEquals("base.apk", r.baseApk?.entryName)
        assertEquals(1, r.splitCount)
        assertTrue(r.splits[0].splitType == "lang")
        assertTrue(r.isInstallable)
    }

    @Test
    fun `mixed packages with nested manifests give the conflict verdict`() {
        val r = PackageInspector.inspect(zip(tmp("mix"), "mix.xapk", mapOf(
            "com.ziczac.shooter.apk" to apk(axml("base-carnage")),
            "com.MA.LAC.apk" to apk(axml("base-lac")),
        )))
        assertFalse(r.isInstallable)
        assertTrue(r.issues.any { it.startsWith("Conflicting base") })
        assertNull(r.baseApk)
    }

    @Test
    fun `nested directories with nested apks work`() {
        val r = PackageInspector.inspect(zip(tmp("nest"), "nest.xapk", mapOf(
            "files/com.ziczac.shooter.apk" to apk(axml("base-carnage")),
            "files/config.arm64_v8a.apk" to apk(axml("config-arm64")),
            "files/manifest.json" to """{"package_name":"com.ziczac.shooter"}""".toByteArray(),
        )))
        assertEquals("files/com.ziczac.shooter.apk", r.baseApk?.entryName)
        assertTrue(r.isInstallable)
    }

    @Test
    fun `invalid container manifest degrades to nested-apk identity`() {
        val r = PackageInspector.inspect(zip(tmp("badmj"), "badmj.xapk", mapOf(
            "com.ziczac.shooter.apk" to apk(axml("base-carnage")),
            "config.arm64_v8a.apk" to apk(axml("config-arm64")),
            "manifest.json" to "{ not json".toByteArray(),
        )))
        assertEquals("com.ziczac.shooter", r.packageName)
        assertEquals(88L, r.versionCode)                      // from the APK's own manifest
        assertTrue(r.isInstallable)
    }
}
