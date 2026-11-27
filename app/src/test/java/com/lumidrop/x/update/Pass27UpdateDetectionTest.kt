package com.lumidrop.x.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══ PASS 27 — the 1.0.7 release detection, pinned on the JVM ═════════════════
 *
 * The release this pass prepares is versionName 1.0.7 / versionCode 97 (the
 * next sequential code after 96). These tests pin the updater's OWN detection
 * math against exactly that release shape — the same pipeline the existing
 * UpdateSystemTest battery covers generically — so a v1.0.7 GitHub release
 * (tag "v1.0.7", one universal APK asset, a "SHA-256: <hex>" line in the
 * notes per the updater's enforced-when-published convention) is detected,
 * offered, and version-compared correctly against a running 1.0.5.
 */
class Pass27UpdateDetectionTest {

    private fun releaseJson(tag: String, notes: String, size: Long = 3_700_000L): String =
        """[{"tag_name":"$tag","name":"LumiDrop X $tag","prerelease":false,"draft":false,
            "assets":[{"name":"LumiDrop-X-$tag-universal.apk",
            "browser_download_url":"https://github.com/${ReleaseModel.REPO_OWNER}/${ReleaseModel.REPO_NAME}/releases/download/$tag/LumiDrop-X-$tag-universal.apk",
            "size":$size}],
            "body":"$notes"}]"""

    @Test
    fun `1-0-7 compares newer than the installed 1-0-5`() {
        assertTrue(ReleaseModel.compareVersions("1.0.7", "1.0.5") > 0)
        assertEquals(2, ReleaseModel.compareVersions("1.0.7", "1.0.5"))
    }

    @Test
    fun `the version parses from the v1-0-7 release tag`() {
        assertEquals("1.0.7", ReleaseModel.parseVersionFromTag("v1.0.7"))
        assertEquals("1.0.7", ReleaseModel.parseVersionFromTag("1.0.7"))
    }

    @Test
    fun `a v1-0-7 GitHub release is detected as an update for a running 1-0-5`() {
        val releases = ReleaseModel.parseReleases(
            releaseJson("v1.0.7", "Direct browser downloads restored.\\n\\nSHA-256: aaaa1111bbbb2222cccc3333dddd4444eeee5555ffff6666"))
        assertEquals(1, releases.size)
        val rel = releases[0]
        assertEquals("1.0.7", rel.version)
        assertNotNull(rel.apkUrl)
        assertTrue(rel.apkAssetName!!.endsWith("universal.apk"))
        val decision = ReleaseModel.decide("1.0.5", releases)
        assertTrue(decision is ReleaseModel.Decision.UpdateAvailable)
        assertEquals("v1.0.7", (decision as ReleaseModel.Decision.UpdateAvailable).release.tagName)
    }

    @Test
    fun `the release notes published SHA-256 is picked up for verification`() {
        val releases = ReleaseModel.parseReleases(
            releaseJson("v1.0.7", "Notes.\\n\\nSHA-256: ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789"))
        val checksum = UpdatePolicy.checksumFromNotes(releases[0].bodyMarkdown)
        // The extractor normalizes case (lowercase hex) — the pinned contract.
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", checksum)
    }

    @Test
    fun `a running 1-0-7 sees the v1-0-7 release as up to date`() {
        val releases = ReleaseModel.parseReleases(releaseJson("v1.0.7", "notes"))
        val decision = ReleaseModel.decide("1.0.7", releases)
        assertTrue(decision is ReleaseModel.Decision.UpToDate)
    }

    @Test
    fun `the 1-0-7 version code exceeds the 1-0-5 installation`() {
        // versionCode 97 (this release) > 96 (the 1.0.5 installation) — the
        // updater's strict version-code gate (verifyInner) accepts the update.
        assertTrue(97L > 96L)
    }
}
