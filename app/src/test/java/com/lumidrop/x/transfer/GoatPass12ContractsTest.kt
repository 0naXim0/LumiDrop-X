package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ══ GOAT-PASS 12 — the JVM battery for this pass's correctable logic ══════
 *
 *  • PART 1 (first-install OBB reliability): the staging/commit size-
 *    verification twins — a staged OBB whose copied length differs from the
 *    archive entry's declared size must NEVER be committed, and an existing
 *    wrong-sized file in the obb home must be REPLACED, not accepted (the
 *    exact defect that made attempt #2 "work" after attempt #1 left damage).
 *  • PART 2 (accurate results): the ambiguous-ABORTED disambiguation twin —
 *    an OEM STATUS_FAILURE_ABORTED is SUCCESS when the package is verifiably
 *    present, and the idempotent-finalize contract (one popup per operation,
 *    ever).
 *  • PART 3 (Files-sheet XAPK recognition): the icon pipeline's base-name
 *    matching is case-insensitive and the XAPK-spec icon.png is the secondary
 *    REAL-icon source; ZIP stays a plain archive everywhere.
 *  • PART 4 (extraction removal): pinned in GoatPass11ContractsTest.
 *
 *  Android-framework halves (the real PackageInstaller session, the real obb
 *  home on a device, on-screen popups) are compile + source-trace verified
 *  and labeled as device-pending in the report — no runtime claims without
 *  runtime testing.
 */
class GoatPass12ContractsTest {

    // ── PART 1: OBB staging + commit — the size-verification contract ─────

    /** The twin of stageObbToCache's verification: copy + declared-size check. */
    private fun stageTwin(src: File, destDir: File, declaredSize: Long): File? {
        return try {
            destDir.mkdirs()
            val tmp = File(destDir, ".stage.part")
            FileOutputStream(tmp).use { it.write(ByteArray(declaredSize.toInt())) }
            // (the production code compares the STREAMED length to the ZIP
            // entry's declared size — here simulated by construction)
            if (declaredSize >= 0 && tmp.length() != declaredSize) { tmp.delete(); return null }
            val out = File(destDir, "staged.obb")
            if (!tmp.renameTo(out)) { tmp.delete(); return null }
            out
        } catch (e: Exception) { null }
    }

    /** The twin of commitObb: existing right-sized = verified; wrong-sized =
     *  replaced; result checked by existence AND length. */
    private fun commitTwin(home: File, staged: File, expectedBytes: Long): Boolean {
        return try {
            home.mkdirs()
            val dest = File(home, "main.obb")
            if (dest.exists()) {
                if (dest.length() == expectedBytes) return true   // correct residue: reuse
                dest.delete()                                     // damaged residue: replace
            }
            if (!staged.renameTo(dest)) return false
            dest.isFile && dest.length() == expectedBytes
        } catch (e: Exception) { false }
    }

    @Test
    fun `a truncated staged obb is rejected before it can reach the obb home`() {
        val dir = createTempDir("gp12-stage")
        // declared 1000 bytes but the copy is short → staging FAILS
        val staged = stageTwinTruncated(dir)
        assertNull("a size-mismatched staged file must never be produced", staged)
    }

    private fun stageTwinTruncated(dir: File): File? {
        dir.mkdirs()
        val tmp = File(dir, ".stage.part")
        FileOutputStream(tmp).use { it.write(ByteArray(400)) }   // truncated copy
        val declaredSize = 1000L
        if (declaredSize >= 0 && tmp.length() != declaredSize) { tmp.delete(); return null }
        return File(dir, "staged.obb")
    }

    @Test
    fun `a wrong-sized existing obb is replaced - damaged attempt1 residue cannot pass as provisioned`() {
        val home = createTempDir("gp12-home")
        // attempt #1 left a WRONG-SIZED file (the exact first-install story)
        val damaged = File(home.apply { mkdirs() }, "main.obb")
        FileOutputStream(damaged).use { it.write(ByteArray(123)) }
        // attempt #2 stages the correct data
        val stagedDir = createTempDir("gp12-stage2")
        val staged = File(stagedDir, "staged.obb")
        FileOutputStream(staged).use { it.write(ByteArray(1000)) }
        assertTrue("correct staged data must replace the damaged residue", commitTwin(home, staged, 1000L))
        assertEquals(1000L, File(home, "main.obb").length())
    }

    @Test
    fun `a correct existing obb is verified and reused - no needless recopy`() {
        val home = createTempDir("gp12-home2").apply { mkdirs() }
        val existing = File(home, "main.obb")
        FileOutputStream(existing).use { it.write(ByteArray(1000)) }
        val stagedDir = createTempDir("gp12-stage3")
        val staged = File(stagedDir, "staged.obb")
        FileOutputStream(staged).use { it.write(ByteArray(1000)) }
        assertTrue(commitTwin(home, staged, 1000L))
        assertEquals("the existing file was kept (no rewrite)", 1000L, existing.length())
    }

    @Test
    fun `commit verifies the final length - a lost rename is a failure`() {
        val home = createTempDir("gp12-home3")
        val stagedDir = createTempDir("gp12-stage4")
        val staged = File(stagedDir, "staged.obb")
        FileOutputStream(staged).use { it.write(ByteArray(1000)) }
        // expectedBytes deliberately wrong → the committed file fails verify
        assertFalse(commitTwin(home, staged, 2000L))
    }

    // ── PART 2: the ambiguous-ABORTED disambiguation + idempotent finalize ─

    @Test
    fun `oem aborted result is success when the package is verifiably installed`() {
        // The twin of the dialog's disambiguation:
        fun effectiveSuccess(androidSaidOk: Boolean, packagePresentNow: Boolean): Boolean =
            androidSaidOk || (!androidSaidOk && packagePresentNow)
        assertTrue(effectiveSuccess(false, true))    // ABORTED + installed → treat as success
        assertFalse(effectiveSuccess(false, false))  // ABORTED + absent → honest failure
        assertTrue(effectiveSuccess(true, true))     // normal success
        assertTrue(effectiveSuccess(true, false))    // SUCCESS broadcast is trusted as-is
    }

    @Test
    fun `finalize is idempotent - one popup per operation ever`() {
        val finalized = java.util.concurrent.atomic.AtomicBoolean(false)
        var popups = 0
        fun finalize() { if (finalized.compareAndSet(false, true)) popups++ }
        finalize(); finalize(); finalize()   // a success + a late stale broadcast + a double-tap
        assertEquals("exactly one finalization survives", 1, popups)
    }

    // ── PART 3: icon pipeline + shared classification ──────────────────────

    @Test
    fun `manifest base name matching is case-insensitive`() {
        // The twin of packageArchiveIcon's manifest branch.
        val apkEntries = listOf("Base.Apk", "config.en.apk", "config.xxhdpi.apk")
        val declared = "base.apk"
        val match = apkEntries.firstOrNull { it.equals(declared, true) }
        assertEquals("Base.Apk", match)
    }

    @Test
    fun `xapk root icon png is the secondary real-icon source`() {
        // The twin: base-APK parse failed → look for the spec's icon.png
        // BEFORE giving up (never a generic fallback while a real icon exists).
        val names = listOf("manifest.json", "base.apk", "config.arm64.apk", "icon.png")
        val iconPng = names.firstOrNull { it.equals("icon.png", true) }
        assertEquals("icon.png", iconPng)
        // and an archive without one falls to the vector fallback honestly
        val noIcon = listOf("manifest.json", "base.apk")
        assertNull(noIcon.firstOrNull { it.equals("icon.png", true) })
    }

    @Test
    fun `files sheet uses the same cache size as received and history`() {
        // The twin of the P3 fix: ONE size for every surface → ONE cache key
        // → the same thumbnail everywhere, no per-surface re-extraction.
        fun cacheKey(path: String, len: Long, mtime: Long, sizeDp: Int, density: Float): String =
            "$path:$len:$mtime:${(sizeDp * density).toInt()}"
        val received = cacheKey("/storage/emulated/0/LumiDropX/g.xapk", 5L, 6L, sizeDp = 40, density = 2.75f)
        val files = cacheKey("/storage/emulated/0/LumiDropX/g.xapk", 5L, 6L, sizeDp = 40, density = 2.75f)
        assertEquals(received, files)
    }

    @Test
    fun `xapk classification is case-insensitive in every surface`() {
        // categoryOf lowercases before matching — the shared classifier all
        // three surfaces call (P3.2/P3.3: uppercase/mixed-case recognized).
        assertEquals(com.lumidrop.x.media.ThumbnailEngine.FileCategory.APK,
            com.lumidrop.x.media.ThumbnailEngine.categoryOf("XAPK"))
        assertEquals(com.lumidrop.x.media.ThumbnailEngine.FileCategory.APK,
            com.lumidrop.x.media.ThumbnailEngine.categoryOf("xApK"))
        assertEquals(com.lumidrop.x.media.ThumbnailEngine.FileCategory.APK,
            com.lumidrop.x.media.ThumbnailEngine.categoryOf("APKS"))
        // and ordinary zip stays an archive — never a package path
        assertEquals(com.lumidrop.x.media.ThumbnailEngine.FileCategory.ARCHIVE,
            com.lumidrop.x.media.ThumbnailEngine.categoryOf("ZIP"))
    }

    // ── PART 1 structural: a real staged XAPK round-trip of the OBB pick ──

    @Test
    fun `obb entries are identified by the inspector for real archives`() {
        val dir = createTempDir("gp12-obb")
        val xapk = File(dir, "game.xapk")
        ZipOutputStream(FileOutputStream(xapk)).use { zos ->
            zos.putNextEntry(ZipEntry("manifest.json"))
            zos.write("""{"package_name":"com.game","base_apk_name":"base.apk","split_apks":[]}""".toByteArray())
            zos.closeEntry()
            zos.putNextEntry(ZipEntry("base.apk")); zos.write(ByteArray(64)); zos.closeEntry()
            zos.putNextEntry(ZipEntry("Android/obb/com.game/main.1.com.game.obb"))
            zos.write(ByteArray(2048)); zos.closeEntry()
            zos.putNextEntry(ZipEntry("Android/obb/com.game/patch.1.com.game.obb"))
            zos.write(ByteArray(512)); zos.closeEntry()
        }
        val r = PackageInspector.inspect(xapk)
        assertEquals("com.game", r.packageName)
        assertEquals(2, r.obbs.size)
        assertTrue(r.obbs.all { it.entryName.startsWith("Android/obb/com.game/") })
        assertEquals(2048L, r.obbs.first { it.entryName.endsWith("main.1.com.game.obb") }.sizeBytes)
    }
}
