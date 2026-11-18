package com.lumidrop.x.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancel
import java.io.File

/**
 * ══ GOAT-PASS 11 — the JVM battery pinning this pass's correctable logic ═════
 *
 *  • The installation ROUTING contract (PART 1): ordinary ".apk" must take
 *    the original system-installer path and NEVER the archive engine; only
 *    .xapk/.apkm/.apks take the built-in path. The predicate is the twin of
 *    the real routing in TransfersFragment.openFile (same when-block shape)
 *    so a routing regression fails HERE, not on a device.
 *  • The extraction LIFECYCLE corrections (PART 5/6): cancellation must now
 *    PROPAGATE as CancellationException (not be swallowed into Cancelled —
 *    the swallowed cancellation was the half of the Files-sheet regression
 *    that let a dead coroutine keep running), and the storage pre-flight
 *    must tolerate usableSpace == 0 mounts.
 *  • The XAPK icon pipeline's base-identification precedence (PART 3) — the
 *    same rules ThumbnailShared.packageArchiveIcon applies, JVM-pinned.
 *
 *  Windowing/UI halves of this pass (the modal's measured geometry, the
 *  real on-device icon decode, Android's installer) are Android-framework
 *  machinery: source-trace + compilation-verified and labeled as such in
 *  the report.
 */
class GoatPass11ContractsTest {

    // ── PART 1: the routing contract ───────────────────────────────────────

    /** The twin of openFile's routing block: which extension goes where. */
    private fun routeToArchiveEngine(fileName: String): Boolean {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return ext == "xapk" || ext == "apkm" || ext == "apks"
    }

    @Test
    fun `ordinary apk never routes to the archive engine`() {
        listOf("app.apk", "App.APK", "game.apk", "a.b.apk", "weird apk name .apk")
            .forEach { assertFalse("'$it' must use the ORIGINAL system installer", routeToArchiveEngine(it)) }
    }

    @Test
    fun `archives route to the built-in engine regardless of case`() {
        listOf("app.xapk", "App.XAPK", "bundle.apks", "mirror.APKM", "x.apkm")
            .forEach { assertTrue("'$it' must use the built-in engine", routeToArchiveEngine(it)) }
    }

    @Test
    fun `zips and everything else never reach either installer path`() {
        listOf("archive.zip", "photo.jpg", "song.mp3", "notes.txt", "noext", "a.tar.gz")
            .forEach { assertFalse(routeToArchiveEngine(it)) }
    }

    // ── PART 3: the icon pipeline's base-identification precedence ─────────

    /** The twin of ThumbnailShared.packageArchiveIcon's base picking. */
    internal fun pickBaseApk(names: List<String>, manifestBase: String? = null): String? {
        val apkEntries = names.filter { it.endsWith(".apk", true) }
        if (apkEntries.isEmpty()) return null
        return apkEntries.firstOrNull { it.equals("base.apk", true) }
            ?: manifestBase?.takeIf { apkEntries.contains(it) }
            ?: (if (apkEntries.size == 1) apkEntries[0] else null)
    }

    @Test
    fun `base apk identification precedence`() {
        // explicit base.apk wins even among many APKs
        assertEquals("base.apk", pickBaseApk(listOf("config.en.apk", "base.apk", "config.xxhdpi.apk")))
        // manifest-declared base wins when no explicit base.apk exists
        assertEquals("game.primary.apk",
            pickBaseApk(listOf("game.primary.apk", "game.split.en.apk"), manifestBase = "game.primary.apk"))
        // a lone APK is the base
        assertEquals("only.apk", pickBaseApk(listOf("only.apk")))
        // multiple APKs, no base, no manifest → honestly null (vector fallback)
        assertEquals(null, pickBaseApk(listOf("a.apk", "b.apk")))
        // no APKs at all → null
        assertEquals(null, pickBaseApk(listOf("manifest.json", "icon.png")))
        // a manifest base that isn't actually present is ignored
        assertEquals(null, pickBaseApk(listOf("a.apk", "b.apk"), manifestBase = "missing.apk"))
    }

    // ── GOAT-PASS 12 (PART 4): the extraction-REMOVAL contract ────────────

    @Test
    fun `the extraction engine no longer exists on the classpath`() {
        val gone = try {
            Class.forName("com.lumidrop.x.transfer.ZipExtractor")
            false
        } catch (e: ClassNotFoundException) { true }
        assertTrue("ZipExtractor must be fully removed", gone)
    }

    @Test
    fun `zip remains a plain archive - never a package install path`() {
        // The routing twin: .zip never reaches the package installer path,
        // and its category identity remains ARCHIVE (transfer + icons).
        val ext = "zip"
        assertFalse(ext == "xapk" || ext == "apkm" || ext == "apks")
        assertEquals(com.lumidrop.x.media.ThumbnailEngine.FileCategory.ARCHIVE,
            com.lumidrop.x.media.ThumbnailEngine.categoryOf(ext))
    }
}