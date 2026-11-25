package com.lumidrop.x.update

import com.lumidrop.x.update.AboutUpdateSection.ActionKind
import com.lumidrop.x.update.DownloadEngine.Connection
import com.lumidrop.x.update.DownloadEngine.Outcome
import com.lumidrop.x.update.UpdateManager.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.UnknownHostException

/**
 * ══ PRODUCTION FINAL PASS 23 — the updater-reconstruction battery ══════════
 *
 * Pure-JVM coverage of the §15 test matrix that does not need a device.
 * Every case encodes one of the audited Pass-22 root causes or one §3/§4/§5/
 * §7/§9 requirement:
 *
 *  Test E (416 recovery)   — policy matrix + engine scenarios
 *  Test F (interruption)   — mid-body reset resumes from the REAL length,
 *                            bounded retries, partial always preserved
 *  Test G (reuse)          — pre-download policy + complete-file-416 engine
 *  Test B/I (install fail) — the primary-action contract: install-shaped
 *                            states retry the INSTALLATION, never download
 *  Test C/D (restart)      — snapshot round-trip + restore-action matrix
 *  Test H (invalid APK)    — cleanup keep-rules + eof verdicts
 *  Test K (supersession)   — tag/URL identity decisions
 *
 * What is NOT claimed here: on-device FileProvider resolution against the
 * real Android installer, real GitHub network I/O, and PackageManager
 * signature comparison — those are covered in the report's honest
 * verification-level table.
 */
class Pass23UpdaterReconstructionTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun release(tag: String = "v1.0.4", size: Long = 1000L, body: String? = null,
                        url: String = "https://github.com/0naXim0/LumiDrop-X/releases/download/$tag/LumiDrop-X-$tag-universal.apk") =
        ReleaseModel.Release(tag, "LumiDrop X $tag", false, false,
            "LumiDrop-X-$tag-universal.apk", url, size, body)

    // ══ PASS 23 §2 — the FileProvider root fix, pinned forever ═════════════

    @Test fun `provider paths cover the updater staging directory`() {
        // The real-device failure: no configured root contained
        // /data/data/com.lumidrop.x/cache/updates/lumidrop-update-v1.0.4.apk.
        // The fix is the <cache-path> root for exactly updates/ — pinned here
        // so no future pass can silently remove it.
        val xml = File("src/main/res/xml/file_paths.xml")
        org.junit.Assume.assumeTrue("file_paths.xml readable from module dir", xml.isFile)
        val text = xml.readText()
        val entry = Regex("<cache-path[^>]*>").findAll(text)
            .map { it.value }.firstOrNull { it.contains("path=\"updates/\"") }
        assertNotNull("file_paths.xml must map cacheDir/updates via <cache-path>", entry)
    }

    @Test fun `manifest declares the matching provider authority and install permission`() {
        val mf = File("src/main/AndroidManifest.xml")
        org.junit.Assume.assumeTrue("AndroidManifest.xml readable from module dir", mf.isFile)
        val text = mf.readText()
        assertTrue("authority must be \${applicationId}.fileprovider (UpdateManager builds packageName+\".fileprovider\")",
            text.contains("android:authorities=\"\${applicationId}.fileprovider\""))
        assertTrue("REQUEST_INSTALL_PACKAGES must be declared (the installer handoff)",
            text.contains("android.permission.REQUEST_INSTALL_PACKAGES"))
    }

    // ══ §3.B — Content-Range parsing ═══════════════════════════════════════

    @Test fun `content-range parsing handles every response shape`() {
        assertEquals(3774873L, UpdatePolicy.contentRangeTotal("bytes */3774873"))       // 416
        assertEquals(3774873L, UpdatePolicy.contentRangeTotal("bytes 1000-3774872/3774873")) // 206
        assertEquals(null, UpdatePolicy.contentRangeTotal("bytes 1000-3774872/*"))     // unknown total
        assertEquals(null, UpdatePolicy.contentRangeTotal(null))
        assertEquals(null, UpdatePolicy.contentRangeTotal("garbage"))
        assertEquals(null, UpdatePolicy.contentRangeTotal(""))
        assertTrue(UpdatePolicy.rangeHonoredAt("bytes 1000-3774872/3774873", 1000L))
        assertFalse(UpdatePolicy.rangeHonoredAt("bytes 0-3774872/3774873", 1000L))
        assertFalse(UpdatePolicy.rangeHonoredAt("bytes 999-3774872/3774873", 1000L))
        assertFalse(UpdatePolicy.rangeHonoredAt(null, 1000L))
        assertFalse(UpdatePolicy.rangeHonoredAt("bytes 1000-3774872/3774873", 0L))
    }

    // ══ §3.C / Test E — the HTTP 416 decision matrix ═══════════════════════

    @Test fun `416 on an already-complete file is FILE_ALREADY_COMPLETE`() {
        // The exact real-device loop: the staged APK had every byte, every
        // Download press asked for bytes=<fullsize>- and failed forever.
        assertEquals(UpdatePolicy.RangeMismatchAction.FILE_ALREADY_COMPLETE,
            UpdatePolicy.rangeMismatchAction(partialLen = 1000, serverTotal = 1000,
                expectedSize = 1000, recoveryAttemptsUsed = 0))
    }

    @Test fun `416 with an oversized or short partial restarts clean`() {
        // asset replaced by a smaller one → stale bytes unusable
        assertEquals(UpdatePolicy.RangeMismatchAction.RESTART_CLEAN,
            UpdatePolicy.rangeMismatchAction(1100, 1000, 1000, 0))
        // legit-looking offset the server still refused (CDN range weirdness)
        assertEquals(UpdatePolicy.RangeMismatchAction.RESTART_CLEAN,
            UpdatePolicy.rangeMismatchAction(400, 1000, 1000, 0))
    }

    @Test fun `416 recovery is bounded - never the same refused range forever`() {
        // unknown total: one clean restart, then honest failure
        assertEquals(UpdatePolicy.RangeMismatchAction.RESTART_CLEAN,
            UpdatePolicy.rangeMismatchAction(400, null, 0, 0))
        assertEquals(UpdatePolicy.RangeMismatchAction.FAIL_HONEST,
            UpdatePolicy.rangeMismatchAction(400, null, 0, 1))
        // known total: the recovery budget still bounds it
        assertEquals(UpdatePolicy.RangeMismatchAction.FAIL_HONEST,
            UpdatePolicy.rangeMismatchAction(400, 1000, 1000, UpdatePolicy.MAX_RECOVERY_ATTEMPTS))
    }

    @Test fun `416 never deletes a complete valid file`() {
        // policy: complete → verify in place; engine: bytes untouched (below)
        val p = UpdatePolicy.rangeMismatchAction(1000, 1000, 1000, 0)
        assertTrue(p == UpdatePolicy.RangeMismatchAction.FILE_ALREADY_COMPLETE)
    }

    // ══ §3.E — end-of-stream verdicts ══════════════════════════════════════

    @Test fun `eof verdicts decide completion honestly`() {
        assertEquals(UpdatePolicy.EofVerdict.COMPLETE, UpdatePolicy.eofVerdict(1000, 1000, 1000))
        assertEquals(UpdatePolicy.EofVerdict.INCOMPLETE, UpdatePolicy.eofVerdict(400, 1000, 1000))
        assertEquals(UpdatePolicy.EofVerdict.INVALID, UpdatePolicy.eofVerdict(1001, 1000, 1000))
        assertEquals(UpdatePolicy.EofVerdict.INVALID, UpdatePolicy.eofVerdict(0, 1000, 1000))
        // unknown sizes everywhere: clean EOF = done (verifier is the gate)
        assertEquals(UpdatePolicy.EofVerdict.COMPLETE, UpdatePolicy.eofVerdict(700, 0, null))
        // stale metadata: the SERVER total outranks it — verifier judges
        assertEquals(UpdatePolicy.EofVerdict.COMPLETE, UpdatePolicy.eofVerdict(1200, 1000, 1200))
        assertEquals(UpdatePolicy.EofVerdict.INCOMPLETE, UpdatePolicy.eofVerdict(700, 0, 1000))
    }

    // ══ §3.D — retry policy ════════════════════════════════════════════════

    @Test fun `transient errors retry - dead radios and refusals do not`() {
        assertTrue(UpdatePolicy.shouldAutoRetry(UpdatePolicy.ErrorKind.RESET, 1))
        assertTrue(UpdatePolicy.shouldAutoRetry(UpdatePolicy.ErrorKind.TIMEOUT, 1))
        assertTrue(UpdatePolicy.shouldAutoRetry(UpdatePolicy.ErrorKind.SERVER, 1))
        assertTrue(UpdatePolicy.shouldAutoRetry(UpdatePolicy.ErrorKind.RATE_LIMITED, 1))
        assertFalse("no network — never hammer a dead radio",
            UpdatePolicy.shouldAutoRetry(UpdatePolicy.ErrorKind.NO_NETWORK, 1))
        assertFalse(UpdatePolicy.shouldAutoRetry(UpdatePolicy.ErrorKind.CANCELLED, 1))
        assertFalse(UpdatePolicy.shouldAutoRetry(UpdatePolicy.ErrorKind.HTTP_REFUSED, 1))
        assertFalse("budget exhausted", UpdatePolicy.shouldAutoRetry(UpdatePolicy.ErrorKind.RESET, 3))
        assertTrue(UpdatePolicy.isRetryableHttp(429))
        assertTrue(UpdatePolicy.isRetryableHttp(503))
        assertFalse(UpdatePolicy.isRetryableHttp(403))
        assertFalse(UpdatePolicy.isRetryableHttp(404))
        assertEquals(1000L, UpdatePolicy.retryDelayMs(0))
        assertEquals(4000L, UpdatePolicy.retryDelayMs(9))   // hard cap
    }

    // ══ §4 / Test G — download each version only once ══════════════════════

    @Test fun `pre-download policy reuses before dialing`() {
        assertEquals(UpdatePolicy.PreDownloadAction.FRESH, UpdatePolicy.preDownloadAction(-1, 1000))
        assertEquals(UpdatePolicy.PreDownloadAction.FRESH, UpdatePolicy.preDownloadAction(0, 1000))
        assertEquals(UpdatePolicy.PreDownloadAction.VERIFY_IN_PLACE,
            UpdatePolicy.preDownloadAction(1000, 1000))
        // more bytes than (stale) metadata claims — verifier decides, not the name
        assertEquals(UpdatePolicy.PreDownloadAction.VERIFY_IN_PLACE,
            UpdatePolicy.preDownloadAction(1200, 1000))
        assertEquals(UpdatePolicy.PreDownloadAction.RESUME, UpdatePolicy.preDownloadAction(400, 1000))
        // unknown expected size: any non-empty file is worth verifying
        assertEquals(UpdatePolicy.PreDownloadAction.VERIFY_IN_PLACE,
            UpdatePolicy.preDownloadAction(400, 0))
    }

    @Test fun `restore action maps restart state correctly`() {
        fun ra(installed: String, fileLen: Long, expected: Long = 1000) =
            UpdatePolicy.restoreAction(installed, "1.0.4", fileLen, expected)
        // Test C: discovery survives restart; Test D: validated APK is reused
        assertEquals(UpdatePolicy.RestoreAction.REVALIDATE_FILE, ra("1.0.3", 1000))
        assertEquals(UpdatePolicy.RestoreAction.PARTIAL_RESUME, ra("1.0.3", 400))
        assertEquals(UpdatePolicy.RestoreAction.OFFER_ONLY, ra("1.0.3", -1))
        assertEquals(UpdatePolicy.RestoreAction.OFFER_ONLY, ra("1.0.3", 0))
        // the persisted release is already running → obsolete
        assertEquals(UpdatePolicy.RestoreAction.SUPERSEDED_BY_INSTALL, ra("1.0.4", 1000))
        assertEquals(UpdatePolicy.RestoreAction.SUPERSEDED_BY_INSTALL, ra("1.0.5", -1))
        // no snapshot → nothing to restore
        assertEquals(UpdatePolicy.RestoreAction.NOTHING,
            UpdatePolicy.restoreAction("1.0.3", null, -1, 1000))
    }

    // ═══ §5 — snapshot persistence round-trips ═════════════════════════════

    @Test fun `snapshot round-trips every persisted field`() {
        val r = release(body = "LumiDrop X v1.0.4 — Production Final Pass 22\n\nSHA-256: " + "ab".repeat(32))
        val s = UpdateStore.Snapshot(r, 1_735_000_000_000L, readyToInstall = true, installPending = true)
        val back = UpdateStore.decode(UpdateStore.encode(s))
        assertEquals(r.tagName, back.release!!.tagName)
        assertEquals(r.name, back.release!!.name)
        assertEquals(r.isPrerelease, back.release!!.isPrerelease)
        assertEquals(r.isDraft, back.release!!.isDraft)
        assertEquals(r.apkAssetName, back.release!!.apkAssetName)
        assertEquals(r.apkUrl, back.release!!.apkUrl)
        assertEquals(r.apkSizeBytes, back.release!!.apkSizeBytes)
        assertEquals(r.bodyMarkdown, back.release!!.bodyMarkdown)
        assertEquals(s.lastCheckAtMs, back.lastCheckAtMs)
        assertTrue(back.readyToInstall)
        assertTrue(back.installPending)
    }

    @Test fun `snapshot decodes empty and corrupt payloads as empty`() {
        assertEquals(null, UpdateStore.decode(null).release)
        assertEquals(null, UpdateStore.decode("").release)
        assertEquals(null, UpdateStore.decode("{corrupt json").release)
        assertEquals(0L, UpdateStore.decode("{corrupt json").lastCheckAtMs)
        val empty = UpdateStore.decode(UpdateStore.encode(UpdateStore.Snapshot.empty()))
        assertEquals(null, empty.release)
        assertFalse(empty.readyToInstall)
        assertFalse(empty.installPending)
    }

    // ═══ §11 / Test K — supersession ═══════════════════════════════════════

    @Test fun `supersession is decided by tag AND asset identity`() {
        assertTrue(UpdatePolicy.supersedes("v1.0.5", "u5", "v1.0.4", "u4"))
        assertFalse(UpdatePolicy.supersedes("v1.0.4", "u4", "v1.0.4", "u4"))
        assertTrue("a replaced asset is a new identity even under the same tag",
            UpdatePolicy.supersedes("v1.0.4", "u4-new", "v1.0.4", "u4"))
        assertFalse(UpdatePolicy.supersedes(null, null, "v1.0.4", "u4"))
        assertFalse(UpdatePolicy.supersedes("v1.0.5", "u5", null, null))
    }

    // ═══ §9 / Test H — cleanup keep-rules ══════════════════════════════════

    @Test fun `cleanup sweeps only what nothing needs`() {
        val keep = "lumidrop-update-v1.0.4.apk"
        assertTrue("another release's leftovers go", UpdatePolicy.maySweep("lumidrop-update-v1.0.3.apk", keep, false))
        assertTrue("stale partials go when nothing is tracked", UpdatePolicy.maySweep("anything", null, false))
        assertFalse("the CURRENT release's APK stays", UpdatePolicy.maySweep(keep, keep, false))
        assertFalse("an in-flight operation's files are untouchable", UpdatePolicy.maySweep("other.apk", keep, true))
        assertFalse(UpdatePolicy.maySweep(keep, keep, true))
    }

    // ═══ §3.E — published checksum extraction ══════════════════════════════

    @Test fun `checksum extraction finds published sha-256 only`() {
        val hex = "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2"
        assertEquals(hex, UpdatePolicy.checksumFromNotes("Release notes\nSHA-256: $hex\nmore text"))
        assertEquals(hex, UpdatePolicy.checksumFromNotes("sha256=$hex"))
        assertEquals(null, UpdatePolicy.checksumFromNotes("no checksum here"))
        assertEquals(null, UpdatePolicy.checksumFromNotes("SHA-256: too-short"))
        assertEquals(null, UpdatePolicy.checksumFromNotes(null))
        assertEquals(null, UpdatePolicy.checksumFromNotes(""))
    }

    // ═══ §7 / Tests B+I — the state→action contract ════════════════════════

    @Test fun `install-shaped states retry the INSTALLATION - never a re-download`() {
        // THE Pass-23 fix: Pass 22's "Retry Install" button re-downloaded.
        for (p in listOf(Phase.READY_TO_INSTALL, Phase.INSTALL_CANCELLED, Phase.INSTALL_FAILED)) {
            val a = AboutUpdateSection.primaryAction(p, true)
            assertEquals("$p must INSTALL", ActionKind.INSTALL, a.kind)
            assertTrue("$p must be actionable", a.enabled)
        }
        assertEquals(ActionKind.INSTALL, AboutUpdateSection.primaryAction(Phase.READY_TO_INSTALL, true).kind)
        assertEquals("Retry Install", AboutUpdateSection.primaryAction(Phase.INSTALL_FAILED, true).label)
    }

    @Test fun `every phase maps to exactly one honest action`() {
        val expected: Map<Phase, Triple<ActionKind, String, Boolean>> = mapOf(
            Phase.IDLE to Triple(ActionKind.CHECK, "Check for Updates", true),
            Phase.CHECKING to Triple(ActionKind.NONE, "Working\u2026", false),
            Phase.UP_TO_DATE to Triple(ActionKind.CHECK, "Check for Updates", true),
            Phase.UPDATE_AVAILABLE to Triple(ActionKind.DOWNLOAD, "Download Update", true),
            Phase.DOWNLOADING to Triple(ActionKind.CANCEL_DOWNLOAD, "Cancel Download", true),
            Phase.FINALIZING to Triple(ActionKind.NONE, "Finishing\u2026", false),
            Phase.DOWNLOAD_COMPLETE to Triple(ActionKind.NONE, "Working\u2026", false),
            Phase.VERIFYING to Triple(ActionKind.NONE, "Working\u2026", false),
            Phase.VERIFIED to Triple(ActionKind.CHECK, "Check for Updates", true),
            Phase.VERIFY_FAILED to Triple(ActionKind.DOWNLOAD, "Retry Download", true),
            Phase.READY_TO_INSTALL to Triple(ActionKind.INSTALL, "Install Update", true),
            Phase.INSTALL_PERMISSION_REQUIRED to Triple(ActionKind.GRANT_PERMISSION, "Grant Permission", true),
            Phase.INSTALL_PENDING to Triple(ActionKind.NONE, "Waiting for Installer\u2026", false),
            Phase.INSTALL_CANCELLED to Triple(ActionKind.INSTALL, "Retry Install", true),
            Phase.INSTALL_FAILED to Triple(ActionKind.INSTALL, "Retry Install", true),
            Phase.UPDATE_INSTALLED to Triple(ActionKind.CHECK, "Check for Updates", true),
            Phase.CHECK_FAILED to Triple(ActionKind.CHECK, "Check for Updates", true),
            Phase.DOWNLOAD_FAILED to Triple(ActionKind.DOWNLOAD, "Retry Download", true))
        assertEquals("every phase is mapped", Phase.values().size, expected.size)
        Phase.values().forEach { p ->
            val e = expected[p]!!
            val a = AboutUpdateSection.primaryAction(p, true)
            assertEquals("$p kind", e.first, a.kind)
            assertEquals("$p label", e.second, a.label)
            assertEquals("$p enabled", e.third, a.enabled)
        }
        // release-dependent enabling degrades honestly
        assertFalse(AboutUpdateSection.primaryAction(Phase.DOWNLOAD_FAILED, false).enabled)
        assertFalse(AboutUpdateSection.primaryAction(Phase.VERIFY_FAILED, false).enabled)
        assertFalse(AboutUpdateSection.primaryAction(Phase.INSTALL_CANCELLED, false).enabled)
    }

    // ═══ THE DOWNLOAD ENGINE — Test E/F/G against fake transports ═══════════

    /** canned connection */
    private class FakeConn(
        override val code: Int,
        private val headers: Map<String, String>,
        override val contentLength: Long,
        private val stream: InputStream
    ) : Connection {
        constructor(code: Int, headers: Map<String, String>, contentLength: Long, body: ByteArray)
            : this(code, headers, contentLength, ByteArrayInputStream(body))
        override fun header(name: String): String? = headers[name]
        override fun body(): InputStream = stream
        override fun close() { }
    }

    /** a body that yields [good] bytes, then throws Connection-reset
     *  ([throwAfter]=true) or signals clean EOF (false) — one fake for both
     *  the interrupted and the recovering rounds. */
    private class FlakyBody(private val good: Int, private val throwAfter: Boolean) : InputStream() {
        private var served = 0
        override fun read(): Int = when {
            served < good -> { served++; 0x41 }
            throwAfter -> throw IOException("Connection reset")
            else -> -1
        }
    }

    private class Rec {
        val fromBytes = mutableListOf<Long>()
        var calls = 0
        fun factory(script: (index: Int, fromByte: Long) -> Connection): (String, Long) -> Connection =
            { _, from -> calls++; fromBytes.add(from); script(calls - 1, from) }
    }

    private val noSleep = DownloadEngine.Sleeper { _, _ -> }

    private fun runEngine(dest: File, expected: Long, rec: Rec,
                          cancelled: () -> Boolean = { false },
                          onProgress: (Long, Long) -> Unit = { _, _ -> },
                          script: (index: Int, fromByte: Long) -> Connection): Outcome =
        DownloadEngine.download("https://example/asset.apk", dest, expected, cancelled,
            rec.factory(script), onProgress, noSleep)

    @Test fun `engine - 416 on a complete file completes with zero network body`() {
        // THE real-device loop, ended: the file already holds every byte.
        val dest = tmp.newFile("apk")
        dest.writeBytes(ByteArray(1000) { it.toByte() })
        val before = dest.readBytes()
        val rec = Rec()
        val out = runEngine(dest, 1000, rec) { _, _ ->
            FakeConn(416, mapOf("Content-Range" to "bytes */1000"), 0, ByteArray(0)) }
        assertTrue(out is Outcome.Completed)
        assertEquals("exactly one request — no body read", 1, rec.calls)
        assertTrue("the complete file was never touched", dest.readBytes().contentEquals(before))
    }

    @Test fun `engine - oversized stale partial plus 416 restarts clean and completes`() {
        val dest = tmp.newFile("apk")
        dest.writeBytes(ByteArray(1100) { 0x7f })          // stale, bigger than the asset
        val good = ByteArray(1000) { (it % 251).toByte() }
        val rec = Rec()
        val out = runEngine(dest, 1000, rec) script@{ i, _ ->
            if (i == 0) FakeConn(416, mapOf("Content-Range" to "bytes */1000"), 0, ByteArray(0))
            else FakeConn(200, emptyMap(), 1000, good)
        }
        assertTrue(out is Outcome.Completed)
        assertEquals(listOf(1100L, 0L), rec.fromBytes)
        assertTrue("final file is exactly the fresh body", dest.readBytes().contentEquals(good))
    }

    @Test fun `engine - persistent 416 is bounded and fails honestly`() {
        val dest = tmp.newFile("apk")
        dest.writeBytes(ByteArray(400) { 0x11 })
        val rec = Rec()
        val out = runEngine(dest, 1000, rec) { _, _ ->
            FakeConn(416, mapOf("Content-Range" to "bytes */1000"), 0, ByteArray(0)) }
        assertTrue(out is Outcome.Failed)
        // 1 initial + MAX_RECOVERY_ATTEMPTS restarts — never an endless loop
        assertEquals(1 + UpdatePolicy.MAX_RECOVERY_ATTEMPTS, rec.calls)
        // each retry asked from byte 0 — never the same refused range again
        assertEquals(listOf(400L, 0L, 0L), rec.fromBytes)
    }

    @Test fun `engine - mid-body reset resumes from the ACTUAL file length`() {
        // Test F: interrupt, restore, retry — the resume offset is the real
        // on-disk length, never a saved offset. Rounds 1-2 reset mid-body;
        // round 3 serves the last bytes and EOFs cleanly.
        val dest = tmp.newFile("apk")
        val rec = Rec()
        val out = runEngine(dest, 30, rec) { i, from ->
            FakeConn(206, mapOf("Content-Range" to "bytes $from-29/30"), 30 - from,
                FlakyBody(good = 10, throwAfter = i < 2)) }
        assertTrue(out is Outcome.Completed)
        assertEquals(listOf(0L, 10L, 20L), rec.fromBytes)
        assertEquals(30L, dest.length())
    }

    @Test fun `engine - exhausted retries preserve the partial and say so`() {
        val dest = tmp.newFile("apk")
        val rec = Rec()
        val out = runEngine(dest, 1000, rec) { _, from ->
            FakeConn(206, mapOf("Content-Range" to "bytes $from-999/1000"), 1000 - from,
                FlakyBody(good = 10, throwAfter = true)) }
        assertTrue(out is Outcome.Failed)
        assertTrue((out as Outcome.Failed).partialPreserved)
        assertTrue(out.message.contains("preserved", ignoreCase = true))
        assertEquals(UpdatePolicy.MAX_NETWORK_ATTEMPTS, rec.calls)
        assertEquals(30L, dest.length())   // 3 × 10 resumable bytes still on disk
    }

    @Test fun `engine - no network fails immediately without hammering`() {
        val dest = tmp.newFile("apk")
        dest.writeBytes(ByteArray(400) { 0x22 })
        var calls = 0
        val out = DownloadEngine.download("https://example/a.apk", dest, 1000, { false },
            { _, _ -> calls++; throw UnknownHostException("no dns") }, { _, _ -> }, noSleep)
        assertTrue(out is Outcome.Failed)
        assertTrue((out as Outcome.Failed).partialPreserved)
        assertTrue(out.message.contains("No network connection"))
        assertEquals("one attempt — a dead radio is not retried", 1, calls)
        assertEquals(400L, dest.length())
    }

    @Test fun `engine - 429 rate limit backs off and then completes`() {
        val dest = tmp.newFile("apk")
        val good = ByteArray(500) { 0x33 }
        val rec = Rec()
        val out = runEngine(dest, 500, rec) script@{ i, _ ->
            if (i == 0) FakeConn(429, emptyMap(), 0, ByteArray(0))
            else FakeConn(200, emptyMap(), 500, good)
        }
        assertTrue(out is Outcome.Completed)
        assertEquals(2, rec.calls)
        assertTrue(dest.readBytes().contentEquals(good))
    }

    @Test fun `engine - premature eof retries and preserves the partial`() {
        val dest = tmp.newFile("apk")
        val rec = Rec()
        val out = runEngine(dest, 1000, rec) { i, from ->
            // every response claims the full remaining range but EOFs early
            FakeConn(206, mapOf("Content-Range" to "bytes $from-999/1000"), 1000 - from,
                ByteArray(100)) }
        assertTrue(out is Outcome.Failed)
        assertTrue((out as Outcome.Failed).partialPreserved)
        assertEquals(UpdatePolicy.MAX_NETWORK_ATTEMPTS, rec.calls)
        assertEquals(300L, dest.length())
    }

    @Test fun `engine - 200 over a stale partial truncates never appends`() {
        val dest = tmp.newFile("apk")
        dest.writeBytes("GARBAGE-GARBAGE".toByteArray())
        val rec = Rec()
        val out = runEngine(dest, 3, rec) { _, _ ->
            FakeConn(200, emptyMap(), 3, "ABC".toByteArray()) }
        assertTrue(out is Outcome.Completed)
        assertEquals("ABC", dest.readBytes().decodeToString())
    }

    @Test fun `engine - cancellation mid-body stops cleans up and stays resumable-honest`() {
        val dest = tmp.newFile("apk")
        var cancel = false
        val rec = Rec()
        val out = runEngine(dest, 1000, rec,
            cancelled = { cancel },
            onProgress = { d, _ -> if (d > 0) cancel = true }) { _, _ ->
            FakeConn(200, emptyMap(), 1000, ByteArray(50) { 0x55 }) }
        assertEquals(Outcome.Cancelled, out)
        assertFalse("cancelled partial is deleted (never half-offered)", dest.isFile)
    }

    @Test fun `engine - clean single-shot download completes`() {
        val dest = tmp.newFile("apk")
        val good = ByteArray(1000) { (it * 7 % 256).toByte() }
        var lastProgress = Pair(-1L, -1L)
        val out = runEngine(dest, 1000, Rec(), onProgress = { d, t -> lastProgress = d to t }) { _, _ ->
            FakeConn(200, emptyMap(), 1000, good) }
        assertTrue(out is Outcome.Completed)
        assertTrue(dest.readBytes().contentEquals(good))
        assertEquals(1000L to 1000L, lastProgress)
    }
}
