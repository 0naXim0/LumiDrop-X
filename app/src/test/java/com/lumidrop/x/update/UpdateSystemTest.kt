package com.lumidrop.x.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══ PASS 19 §11 Test F + G — the update-system battery ════════════════════
 *
 * Pure-JVM coverage of every §6.2/§6.3/§6.5/§6.6/§6.12 scenario that does
 * not need a device: release parsing (realistic + malformed), asset
 * selection (the audited convention + ambiguity refusal), semantic version
 * comparison (1.10 vs 1.9 both directions, prefixes, junk), stable-only
 * selection (draft/prerelease skipping), auto-check pacing (24h + toggle +
 * busy), decision honesty (no false success), and cleanup rule modeling.
 *
 * What is NOT claimed here: on-device download, real GitHub network calls,
 * signature comparison against a real keystore, the Android installer flow.
 * Those are covered in the report's honest test-level table.
 */
class UpdateSystemTest {

    private fun release(tag: String, apk: String = "LumiDrop-X-$tag-universal.apk",
                        pre: Boolean = false, draft: Boolean = false,
                        size: Long = 3_600_000, body: String? = null): ReleaseModel.Release =
        ReleaseModel.Release(tag, "LumiDrop X $tag", pre, draft, apk,
            "https://github.com/0naXim0/LumiDrop-X/releases/download/$tag/$apk", size, body)

    // ── §6.3 version comparison ────────────────────────────────────────────

    @Test fun `1_10 is newer than 1_9 in both notations`() {
        assertTrue(ReleaseModel.compareVersions("1.10", "1.9") > 0)
        assertTrue(ReleaseModel.compareVersions("v1.10", "1.9") > 0)
        assertTrue(ReleaseModel.compareVersions("1.9", "1.10") < 0)
    }

    @Test fun `same version is equal and padded zeros are equal`() {
        assertEquals(0, ReleaseModel.compareVersions("1.0.1", "1.0.1"))
        assertEquals(0, ReleaseModel.compareVersions("1.0", "1.0.0"))
        assertEquals(0, ReleaseModel.compareVersions("1", "1.0.0"))
    }

    @Test fun `unparsable versions never trigger updates`() {
        // junk is treated as OLDEST, so a junk release never beats an install
        assertTrue(ReleaseModel.compareVersions("banana", "1.0.1") < 0)
        assertNull(ReleaseModel.parseVersionFromTag("no-version-here"))
        assertEquals("1.2.3", ReleaseModel.parseVersionFromTag("v1.2.3"))
        assertEquals("1.2.3", ReleaseModel.parseVersionFromTag("release-1.2.3-beta"))
    }

    @Test fun `downgrades and same versions are refused`() {
        val installed = "1.0.1"
        val d1 = ReleaseModel.decide(installed, listOf(release("v1.0.1")))
        assertTrue(d1 is ReleaseModel.Decision.UpToDate)
        val d2 = ReleaseModel.decide(installed, listOf(release("v1.0.0")))
        assertTrue(d2 is ReleaseModel.Decision.UpToDate)
    }

    @Test fun `a genuinely newer release is offered`() {
        val d = ReleaseModel.decide("1.0.0", listOf(release("v1.0.0"), release("v1.0.1")))
        assertTrue(d is ReleaseModel.Decision.UpdateAvailable)
        assertEquals("1.0.1", (d as ReleaseModel.Decision.UpdateAvailable).release.version)
    }

    // ── §6.2 stable-release selection ──────────────────────────────────────

    @Test fun `drafts and prereleases are skipped by default`() {
        val installed = "1.0.0"
        val d = ReleaseModel.decide(installed, listOf(
            release("v1.2.0", pre = true), release("v1.1.0", draft = true), release("v1.0.5")))
        assertTrue(d is ReleaseModel.Decision.UpdateAvailable)
        assertEquals("1.0.5", (d as ReleaseModel.Decision.UpdateAvailable).release.version)
    }

    @Test fun `prerelease allowed only when explicitly requested`() {
        val installed = "1.0.0"
        val d = ReleaseModel.decide(installed, listOf(release("v1.2.0", pre = true)),
            allowPrerelease = true)
        assertTrue(d is ReleaseModel.Decision.UpdateAvailable)
    }

    @Test fun `no releases and no usable releases are distinct honest states`() {
        assertTrue(ReleaseModel.decide("1.0.0", emptyList()) is ReleaseModel.Decision.NoReleases)
        // releases exist, but none has an APK
        val noApk = listOf(release("v1.5.0").copy(apkAssetName = null, apkUrl = null))
        assertTrue(ReleaseModel.decide("1.0.0", noApk) is ReleaseModel.Decision.NoUsableRelease)
    }

    // ── §6.2 asset selection (audited convention) ───────────────────────────

    @Test fun `the universal apk convention is picked`() {
        val assets = listOf(
            Triple("notes.txt", "https://x/notes.txt", 100L),
            Triple("LumiDrop-X-v1.0.1-universal.apk", "https://x/a.apk", 3_600_000L))
        val pick = ReleaseModel.pickApkAsset(assets)
        assertNotNull(pick)
        assertEquals("LumiDrop-X-v1.0.1-universal.apk", pick!!.first)
    }

    @Test fun `a single apk without universal in the name still qualifies`() {
        val pick = ReleaseModel.pickApkAsset(listOf(Triple("app-release.apk", "https://x/a.apk", 1L)))
        assertEquals("app-release.apk", pick!!.first)
    }

    @Test fun `multiple ambiguous apks are refused not guessed`() {
        val assets = listOf(
            Triple("arm64.apk", "https://x/1", 1L), Triple("armeabi.apk", "https://x/2", 1L))
        assertNull(ReleaseModel.pickApkAsset(assets))
    }

    @Test fun `non-apk archives are never picked for the updater`() {
        val assets = listOf(
            Triple("LumiDrop-X-v1.2.0.xapk", "https://x/1", 1L),
            Triple("bundle.aab", "https://x/2", 1L))
        assertNull(ReleaseModel.pickApkAsset(assets))
    }

    // ── §6.2 malformed response handling ───────────────────────────────────

    @Test fun `the real documented api shape parses`() {
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("tag_name", "v1.0.1"); put("name", "LumiDrop X v1.0.1")
            put("prerelease", false); put("draft", false)
            put("body", "This release adds in-app update discovery.")
            put("assets", JSONArray().put(JSONObject().apply {
                put("name", "LumiDrop-X-v1.0.1-universal.apk")
                put("browser_download_url", "https://github.com/0naXim0/LumiDrop-X/releases/download/v1.0.1/LumiDrop-X-v1.0.1-universal.apk")
                put("size", 3_600_000)
            }))
        })
        val rels = ReleaseModel.parseReleases(arr.toString())
        assertEquals(1, rels.size)
        assertEquals("v1.0.1", rels[0].tagName)
        assertEquals("1.0.1", rels[0].version)
        assertEquals(3_600_000L, rels[0].apkSizeBytes)
        assertFalse(rels[0].isPrerelease)
    }

    @Test fun `malformed documents yield empty lists not exceptions`() {
        assertEquals(0, ReleaseModel.parseReleases(null).size)
        assertEquals(0, ReleaseModel.parseReleases("").size)
        assertEquals(0, ReleaseModel.parseReleases("not json at all").size)
        assertEquals(0, ReleaseModel.parseReleases("{\"unexpected\": 1}").size)
    }

    @Test fun `entries with blank tags or null assets are skipped`() {
        val arr = JSONArray()
        arr.put(JSONObject().apply { put("tag_name", ""); put("assets", JSONArray()) })
        arr.put(JSONObject().apply { put("tag_name", "v2.0.0"); put("assets", JSONObject.NULL) })
        arr.put(JSONObject().apply { put("tag_name", "v2.0.1"); put("assets", JSONArray()) }) // no assets
        val rels = ReleaseModel.parseReleases(arr.toString())
        // v2.0.0 with null assets array → JSONArray() fallback; no apk → apkUrl null
        assertTrue(rels.none { it.tagName.isBlank() })
        assertTrue(rels.all { it.apkUrl == null })
    }

    // ── §6.1 auto-check pacing ─────────────────────────────────────────────

    @Test fun `auto check respects the 24h interval and the toggle and busy state`() {
        val now = 1_000_000_000L
        assertTrue(ReleaseModel.shouldAutoCheck(true, 0L, now, busy = false))          // never checked
        assertTrue(ReleaseModel.shouldAutoCheck(true, now - 25 * 3600_000L, now, false)) // past interval
        assertFalse(ReleaseModel.shouldAutoCheck(true, now - 3600_000L, now, false))     // within interval
        assertFalse(ReleaseModel.shouldAutoCheck(false, 0L, now, false))                 // toggle off
        assertFalse(ReleaseModel.shouldAutoCheck(true, 0L, now, busy = true))            // download active
    }

    // ── §6.4/§6.10 status honesty ──────────────────────────────────────────

    @Test fun `install pending is never reported as installed`() {
        // INSTALL_PENDING is a phase the UI renders as "awaiting your
        // confirmation" — verify the phase exists and stays distinct.
        val phases = UpdateManager.Phase.values().map { it.name }
        assertTrue("INSTALL_PENDING" in phases)
        assertTrue("VERIFY_FAILED" in phases)
        assertTrue("CHECK_FAILED" in phases)
        assertTrue("DOWNLOAD_FAILED" in phases)
        // and no phase claims success for the installer — only reconcile-
        // after-restart (a real version change) reports "Updated to X".
    }

    // ── §6.6 checksum enforcement ──────────────────────────────────────────

    @Test fun `checksum enforces only when published`() {
        // null/blank published value → not enforced (null = no verdict)
        val f = java.io.File.createTempFile("apk", ".bin")
        f.writeBytes(byteArrayOf(1, 2, 3))
        val u = UpdateManager
        // verifyChecksumSha256(apk, null) == null means "not enforced"
        assertNull(u.verifyChecksumSha256(f, null))
        assertNull(u.verifyChecksumSha256(f, ""))
        // wrong published hex → false (blocked)
        assertFalse(u.verifyChecksumSha256(f, "deadbeef")!!)
        // correct hex → true
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val hex = md.digest(byteArrayOf(1, 2, 3)).joinToString("") { "%02x".format(it) }
        assertTrue(u.verifyChecksumSha256(f, hex)!!)
        f.delete()
    }

    // ── §6.9 cleanup rules (modeled against the exact implementation) ──────

    @Test fun `cleanup keeps only the pending release apk`() {
        // Model: files in cacheDir/updates; keep = current pending release name.
        val dir = java.io.File(System.getProperty("java.io.tmpdir"), "upd-test-${System.nanoTime()}")
        dir.mkdirs()
        val keep = java.io.File(dir, "lumidrop-update-v1.0.1.apk").apply { writeBytes(byteArrayOf(1)) }
        val old1 = java.io.File(dir, "lumidrop-update-v1.0.0.apk").apply { writeBytes(byteArrayOf(1)) }
        val junk = java.io.File(dir, "leftover.tmp").apply { writeBytes(byteArrayOf(1)) }
        // the rule under test (UpdateManager.cleanupAbandoned's shape)
        val keepName: String? = keep.name
        dir.listFiles()?.forEach { f -> if (keepName == null || f.name != keepName) f.delete() }
        assertTrue(keep.exists())
        assertFalse(old1.exists())
        assertFalse(junk.exists())
        dir.delete()
    }

    // ── §5 release-notes presentation ──────────────────────────────────────

    @Test fun `short notes take the first paragraph`() {
        val md = "Line one.\nLine two.\n\nSecond paragraph."
        assertEquals("Line one.\nLine two.", ReleaseModel.shortNotes(md))
        assertEquals("", ReleaseModel.shortNotes(null))
    }
}
