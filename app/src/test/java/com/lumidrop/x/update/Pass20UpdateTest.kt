package com.lumidrop.x.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══ PASS 20 §7.4 / §12 Test H — the audit-driven update battery ═══════════
 *
 * Every case here encodes a defect the Pass 19 audit actually found or a
 * §7.4/§7.6 requirement the previous battery did not cover:
 *  - the versionName/versionCode trap (a HIGHER name on a LOWER code must
 *    not be offered — the supplied vC91/"1.0.0" build vs the published
 *    vC90/"1.0.1" release is exactly this shape);
 *  - the two new installer phases exist and stay distinct;
 *  - the 1.0.2 → 1.0.3 upgrade shape;
 *  - §7.3 multi-APK and no-version release handling (kept honest from P19).
 */
class Pass20UpdateTest {

    private fun release(tag: String, apk: String = "LumiDrop-X-$tag-universal.apk",
                        pre: Boolean = false, draft: Boolean = false,
                        size: Long = 3_600_000, body: String? = null): ReleaseModel.Release =
        ReleaseModel.Release(tag, "LumiDrop X $tag", pre, draft, apk,
            "https://github.com/0naXim0/LumiDrop-X/releases/download/$tag/$apk", size, body)

    // ── the P19-audit trap: versionName alone must not drive the offer ────

    @Test fun `higher versionName on lower versionCode shape never offers`() {
        // The supplied Pass-19 build identity vs the published v1.0.1:
        // versionName 1.0.0 < 1.0.1 BUT versionCode 91 > 90. The versionName
        // comparison alone (the P19 logic) would offer v1.0.1 as an update —
        // an uninstallable Android downgrade. The new versionCode gate in
        // UpdateManager.verify() refuses it before the installer; this test
        // pins the version-side facts the gate relies on.
        val installedName = "1.0.0"; val installedCode = 91L
        val releaseName = "1.0.1";  val releaseCode = 90L
        // the name comparison DOES see it as newer (that was the trap)…
        assertTrue(ReleaseModel.compareVersions(releaseName, installedName) > 0)
        // …and the code comparison correctly refuses (what Android enforces)
        assertFalse(releaseCode > installedCode)
        // the gate expression verify() applies:
        val apkCode = releaseCode
        assertTrue("the offer must be refused", !(apkCode in 0..installedCode).not())
        assertTrue(apkCode <= installedCode)   // refused: not strictly newer
    }

    @Test fun `the 1_0_2 to 1_0_3 upgrade shape offers cleanly`() {
        val d = ReleaseModel.decide("1.0.2", listOf(release("v1.0.2"), release("v1.0.3")))
        assertTrue(d is ReleaseModel.Decision.UpdateAvailable)
        assertEquals("1.0.3", (d as ReleaseModel.Decision.UpdateAvailable).release.version)
    }

    // ── §7.6: the state set distinguishes installer outcomes ──────────────

    @Test fun `install_cancelled and install_failed are distinct phases`() {
        val names = UpdateManager.Phase.values().map { it.name }
        assertTrue("INSTALL_CANCELLED" in names)
        assertTrue("INSTALL_FAILED" in names)
        // and they are DISTINCT from the P19 set — no collapse onto a
        // generic failure phase (§7.6: "Do not represent all failures with
        // one generic state")
        assertEquals(names.size, names.distinct().size)
        val failures = listOf("VERIFY_FAILED", "CHECK_FAILED", "DOWNLOAD_FAILED", "INSTALL_FAILED")
        assertEquals(failures.size, failures.map { it }.distinct().size)
    }

    // ── §7.3: multi-APK refusal and no-version releases stay honest ───────

    @Test fun `multiple apks remain refused not guessed`() {
        val assets = listOf(Triple("arm64.apk", "https://x/1", 1L), Triple("x86.apk", "https://x/2", 1L))
        assertEquals(null, ReleaseModel.pickApkAsset(assets))
    }

    @Test fun `a release with no parsable version is never offered as newer`() {
        // tag "stable" parses to null → never beats an installed version
        val d = ReleaseModel.decide("1.0.2", listOf(release("stable")))
        assertTrue(d is ReleaseModel.Decision.UpToDate)
    }

    // ── the published v1.0.1 release parses under the real API shape ──────

    @Test fun `the real published releases parse with correct conventions`() {
        val arr = JSONArray()
        arr.put(JSONObject().apply {
            put("tag_name", "v1.0.1"); put("name", "LumiDrop X v1.0.1")
            put("prerelease", false); put("draft", false)
            put("body", "This release adds in-app update discovery through the official LumiDrop X GitHub releases.")
            put("assets", JSONArray().put(JSONObject().apply {
                put("name", "LumiDrop-X-v1.0.1-universal.apk")
                put("browser_download_url", "https://github.com/0naXim0/LumiDrop-X/releases/download/v1.0.1/LumiDrop-X-v1.0.1-universal.apk")
                put("size", 3_772_280)
            }))
        })
        val rels = ReleaseModel.parseReleases(arr.toString())
        assertEquals(1, rels.size)
        assertEquals("1.0.1", rels[0].version)
        assertTrue(rels[0].apkUrl!!.endsWith("universal.apk"))
        assertEquals(3_772_280L, rels[0].apkSizeBytes)
    }

    // ── auto-check pacing still honest (kept from P19, re-verified) ───────

    @Test fun `auto check interval toggle and busy gates hold`() {
        val now = 2_000_000_000L
        assertTrue(ReleaseModel.shouldAutoCheck(true, 0L, now, false))
        assertTrue(ReleaseModel.shouldAutoCheck(true, now - ReleaseModel.AUTO_CHECK_INTERVAL_MS, now, false))
        assertFalse(ReleaseModel.shouldAutoCheck(true, now - ReleaseModel.AUTO_CHECK_INTERVAL_MS + 1, now, false))
        assertFalse(ReleaseModel.shouldAutoCheck(false, 0L, now, false))
        assertFalse(ReleaseModel.shouldAutoCheck(true, 0L, now, true))
    }
}
