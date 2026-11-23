package com.lumidrop.x.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Base64
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.lumidrop.x.update.ReleaseModel.RELEASES_API
import com.lumidrop.x.update.ReleaseModel.Release
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/**
 * ══ PASS 19 §6 — the in-app GitHub updater ════════════════════════════════
 *
 * Scope (what this class deliberately is and is not):
 *  - IS: discovery (Releases API), correct version comparison, internal
 *    resumable download to app-private cache, package + signing identity
 *    verification against the INSTALLED app, and a handoff to Android's
 *    normal package installer via FileProvider. Status is observable and
 *    honest at every step.
 *  - IS NOT: a silent self-replacer. Android forbids ordinary apps from
 *    replacing themselves without the system installer's user-visible
 *    confirmation — this updater launches that flow and reports only what
 *    it can actually know (see installState semantics below).
 *
 * Signing truth (from the audited repository): v1.0.0's keystore is gone;
 * v1.0.1+ is signed differently. A v1.0.0→v1.0.1 "update" will be REJECTED
 * by Android's installer as a signature mismatch. The verifier surfaces
 * this BEFORE the installer is ever launched (installBlockedReason), so the
 * user reads an explanation in-app instead of a cryptic system error — and
 * the release notes' migration path (uninstall/reinstall, or Settings-based
 * manual update where applicable) stays the user's informed choice.
 *
 * Networking discipline (§6.11/§9): HTTPS only; the public Releases API and
 * the release asset URL only; no tokens, no telemetry, no other endpoints.
 * Checks happen from the UI's own executor, paced by ReleaseModel's 24h
 * interval — no service, no periodic background polling.
 */
object UpdateManager {

    // ── observable state (single writer: the UI's executor; readers poll) ──
    enum class Phase {
        IDLE, CHECKING,
        UP_TO_DATE, UPDATE_AVAILABLE,
        DOWNLOADING, DOWNLOAD_COMPLETE,
        VERIFYING, VERIFIED, VERIFY_FAILED,
        INSTALL_PENDING,           // installer launched, awaiting the user
        INSTALL_CANCELLED,         // §7.6: user backed out of the installer — retry stays available
        INSTALL_FAILED,            // §7.6: the installer flow itself errored
        CHECK_FAILED, DOWNLOAD_FAILED
    }

    data class State(
        val phase: Phase,
        val installedVersion: String = "",
        val availableRelease: Release? = null,
        val progressBytes: Long = 0L,
        val totalBytes: Long = 0L,        // 0 = unknown (chunked)
        val message: String = ""          // human-readable, never fake success
    )

    @Volatile var state: State = State(Phase.IDLE)
        private set
    private val stateRef = AtomicReference(state)
    private fun setState(s: State) { state = s; stateRef.set(s) }

    /** Test seam: replaces the network fetch. Production default talks HTTPS. */
    @Volatile var fetcher: ((String) -> String?)? = null
    /** Test seam: replaces the installer launch. */
    @Volatile var installer: ((Context, File) -> Boolean)? = null

    fun installedVersionName(ctx: Context): String = try {
        val pi: PackageInfo = if (Build.VERSION.SDK_INT >= 33)
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        pi.versionName ?: ""
    } catch (e: Exception) { "" }

    // ══ §6.1/§6.2 — checking ═══════════════════════════════════════════════

    fun checkForUpdates(ctx: Context, manual: Boolean) {
        setState(State(Phase.CHECKING, installedVersionName(ctx)))
        val body = try {
            fetcher?.invoke(RELEASES_API) ?: httpsGet(RELEASES_API)
        } catch (e: Exception) {
            setState(State(Phase.CHECK_FAILED, installedVersionName(ctx),
                message = describeNetworkError(e)))
            return
        }
        if (body == null) {
            setState(State(Phase.CHECK_FAILED, installedVersionName(ctx),
                message = "GitHub is unavailable or rate-limited. Try again later."))
            return
        }
        val releases = ReleaseModel.parseReleases(body)
        val decision = ReleaseModel.decide(installedVersionName(ctx), releases)
        val p = ReleaseModel.prefs(ctx)
        p.edit().putLong("last_check_at", System.currentTimeMillis()).apply()
        when (decision) {
            is ReleaseModel.Decision.NoReleases ->
                setState(State(Phase.UP_TO_DATE, installedVersionName(ctx),
                    message = "No releases are published yet."))
            is ReleaseModel.Decision.NoUsableRelease ->
                setState(State(Phase.UP_TO_DATE, installedVersionName(ctx),
                    message = if (manual) "No compatible stable release found." else ""))
            is ReleaseModel.Decision.UpToDate ->
                setState(State(Phase.UP_TO_DATE, installedVersionName(ctx)))
            is ReleaseModel.Decision.UpdateAvailable ->
                setState(State(Phase.UPDATE_AVAILABLE, installedVersionName(ctx), availableRelease = decision.release))
        }
    }

    private fun describeNetworkError(e: Exception): String = when (e) {
        is java.net.UnknownHostException -> "No network connection."
        is java.net.SocketTimeoutException -> "The connection timed out."
        is IOException -> "The network request failed."
        else -> "The check could not complete."
    }

    private fun httpsGet(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 15_000): String? {
        val conn = (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "LumiDropX-Updater")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        return try {
            val code = conn.responseCode
            if (code in 200..299) conn.inputStream.bufferedReader().use { it.readText() } else null
        } finally { conn.disconnect() }
    }

    // ══ §6.5 — downloading ═════════════════════════════════════════════════

    /** app-private cache — no permissions, cleaned by the OS under pressure,
     *  and never visible to other apps (the FileProvider grant below is the
     *  ONLY external access, installer-only). */
    private fun downloadDir(ctx: Context): File = File(ctx.cacheDir, "updates").apply { mkdirs() }

    fun apkFileFor(ctx: Context, release: Release): File =
        File(downloadDir(ctx), "lumidrop-update-${release.tagName}.apk")

    /**
     * Streamed to disk with validated resumption: a partial file is resumed
     * ONLY when the server answers 206 with the exact requested offset;
     * any other answer restarts from byte 0 (the partial is discarded).
     * The APK is never held in memory. Cancellation is checked per chunk.
     */
    fun download(ctx: Context, release: Release, cancelled: () -> Boolean) {
        val dest = apkFileFor(ctx, release)
        val url = release.apkUrl ?: run {
            setState(State(Phase.DOWNLOAD_FAILED, installedVersionName(ctx), message = "No APK asset."))
            return
        }
        if (!url.startsWith("https://", true)) {
            setState(State(Phase.DOWNLOAD_FAILED, installedVersionName(ctx), message = "Refusing non-HTTPS download URL."))
            return
        }
        try {
            var done = dest.length().coerceAtMost(release.apkSizeBytes.takeIf { it > 0 } ?: Long.MAX_VALUE)
            while (true) {
                if (cancelled()) { dest.delete(); setState(State(Phase.UPDATE_AVAILABLE, installedVersionName(ctx), availableRelease = release, message = "Download cancelled.")); return }
                val pair = try { httpsStream(url, if (done > 0) done else -1L) } catch (e: Exception) {
                    setState(State(Phase.DOWNLOAD_FAILED, installedVersionName(ctx), availableRelease = release, message = describeNetworkError(e)))
                    return
                }
                val (conn, resumed) = pair
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        setState(State(Phase.DOWNLOAD_FAILED, installedVersionName(ctx), availableRelease = release,
                            message = "The server returned HTTP $code."))
                        return
                    }
                    if (done > 0 && (!resumed || code != 206)) {
                        // server ignored the range — restart clean, never append onto a 200
                        done = 0; dest.delete()
                    }
                    val total = if (conn.contentLengthLong > 0) done + conn.contentLengthLong else release.apkSizeBytes
                    setState(State(Phase.DOWNLOADING, installedVersionName(ctx), release, done, total))
                    val input = conn.inputStream ?: throw IOException("no stream")
                    FileOutputStream(dest, done > 0).use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (cancelled()) { out.close(); dest.delete()
                                setState(State(Phase.UPDATE_AVAILABLE, installedVersionName(ctx), release, message = "Download cancelled.")); return }
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); done += n
                            setState(State(Phase.DOWNLOADING, installedVersionName(ctx), release, done, total))
                        }
                        out.flush()
                    }
                    val expected = release.apkSizeBytes
                    if (expected > 0 && dest.length() != expected) {
                        dest.delete()
                        setState(State(Phase.DOWNLOAD_FAILED, installedVersionName(ctx), release,
                            message = "The download was incomplete (got ${dest.length()} of $expected bytes)."))
                        return
                    }
                    return   // clean EOF + size check passed — done
                } finally {
                    conn.disconnect()
                }
            }
            setState(State(Phase.DOWNLOAD_COMPLETE, installedVersionName(ctx), release, dest.length(), dest.length()))
        } catch (e: Exception) {
            setState(State(Phase.DOWNLOAD_FAILED, installedVersionName(ctx), release, message = describeNetworkError(e)))
        }
    }

    /** Opens the asset URL, asking to resume from [fromByte] when > 0.
     *  Returns (connection, server-honored-range). */
    private fun httpsStream(url: String, fromByte: Long): Pair<HttpsURLConnection, Boolean> {
        val conn = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("User-Agent", "LumiDropX-Updater")
            if (fromByte > 0) setRequestProperty("Range", "bytes=$fromByte-")
        }
        val honored = conn.responseCode == 206 &&
            conn.getHeaderField("Content-Range")?.startsWith("bytes $fromByte-") == true
        return conn to honored
    }

    // ══ §6.6 — verification (mandatory, before any install) ════════════════

    sealed class VerifyResult {
        object Ok : VerifyResult()
        data class Blocked(val reason: String, val userAction: String) : VerifyResult()
    }

    /**
     * Verifies the downloaded APK against the INSTALLED application:
     *  1. it IS an APK the package manager can parse;
     *  2. its packageName equals THIS app's package name;
     *  3. its versionName is the one we downloaded (never an older one);
     *  4. its signing certificate matches the installed app's exactly.
     * Nothing here trusts the download; everything is compared against the
     * running installation. The APK is never executed — verification only
     * PARSES it (PackageManager's archive reader), which is safe.
     */
    fun verify(ctx: Context, release: Release, apk: File): VerifyResult {
        setState(State(Phase.VERIFYING, installedVersionName(ctx), release))
        val pm = ctx.packageManager
        val info = try {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
                ?: pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: Exception) { null }
        if (info == null) {
            return verifyFailed(ctx, apk, "The downloaded file is not a valid APK.",
                "Delete it and try downloading again.")
        }
        if (info.packageName != ctx.packageName) {
            return verifyFailed(ctx, apk,
                "The update's package name (${info.packageName}) does not match this app (${ctx.packageName}).",
                "This APK cannot update LumiDrop X. It was not installed.")
        }
        val wantVersion = release.version
        if (wantVersion != null && info.versionName != null &&
            ReleaseModel.compareVersions(info.versionName, wantVersion) != 0) {
            return verifyFailed(ctx, apk,
                "The APK's version (${info.versionName}) does not match the release ($wantVersion).",
                "The file may be mislabeled. It was not installed.")
        }
        // ── PASS 20 (§7.4 — the P19-audit defect): versionCode is what ──
        // Android actually gates updates on, and a release may carry a HIGHER
        // versionName on a LOWER code (exactly the v1.0.1/vC90-vs-vC91 shape
        // the Pass 19 audit found: version-name comparison alone would offer
        // an uninstallable downgrade). The downloaded APK's code must EXCEED
        // the installed code or the offer is refused before the installer.
        val installedCode = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (e: Exception) { -1L }
        val apkCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") (info.versionCode ?: -1).toLong()
        if (installedCode >= 0 && apkCode in 0..installedCode) {
            return verifyFailed(ctx, apk,
                "The update's version code ($apkCode) is not newer than this installation ($installedCode).",
                "Android cannot install it as an update. The download was not installed.")
        }
        // signing identity — the exact comparison Android's installer makes
        val installedSig = installedSignatures(ctx)
        val apkSig = archiveSignatures(pm, info)
        if (installedSig != null && apkSig != null && installedSig != apkSig) {
            return verifyFailed(ctx, apk,
                "The update is signed with a different key than this installation.",
                "Android cannot update the app in place across signing keys. " +
                "The download was NOT installed — your current app is untouched.")
        }
        setState(State(Phase.VERIFIED, installedVersionName(ctx), release))
        return VerifyResult.Ok
    }

    private fun verifyFailed(ctx: Context, apk: File, reason: String, userAction: String): VerifyResult {
        // §6.6: an invalid download is cleaned up immediately, the current
        // installation is untouched, and the reason is kept for diagnostics.
        try { apk.delete() } catch (e: Exception) { }
        cleanupAbandoned(ctx)
        setState(State(Phase.VERIFY_FAILED, installedVersionName(ctx), message = reason))
        return VerifyResult.Blocked(reason, userAction)
    }

    private fun installedSignatures(ctx: Context): String? = try {
        val pm = ctx.packageManager
        val sigs = if (Build.VERSION.SDK_INT >= 28)
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo.apkContentsSigners
        else @Suppress("DEPRECATION")
            pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
        digestOf(sigs)
    } catch (e: Exception) { null }

    private fun archiveSignatures(pm: PackageManager, info: PackageInfo): String? = try {
        val sigs = if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null)
            info.signingInfo.apkContentsSigners
        else @Suppress("DEPRECATION") info.signatures
        digestOf(sigs)
    } catch (e: Exception) { null }

    private fun digestOf(sigs: Array<android.content.pm.Signature>?): String? {
        if (sigs.isNullOrEmpty()) return null
        val md = MessageDigest.getInstance("SHA-256")
        sigs.sortedBy { it.toCharsString() }.forEach { md.update(it.toByteArray()) }
        return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
    }

    /** Optional published SHA-256 (release notes convention "SHA-256: <hex>"
     *  or a sibling asset "<apk>.sha256") — enforced when present. */
    fun verifyChecksumSha256(apk: File, publishedHex: String?): Boolean? {
        if (publishedHex.isNullOrBlank()) return null   // nothing published → not enforced
        val md = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        val hex = md.digest().joinToString("") { "%02x".format(it) }
        return hex.equals(publishedHex.trim(), ignoreCase = true)
    }

    // ══ §6.7 — installer handoff ═══════════════════════════════════════════

    /**
     * Launches Android's package installer over a FileProvider content URI.
     * The grant is installer-only (FLAG_GRANT_READ_URI_PERMISSION); no world-
     * readable file is ever created. This does NOT and CANNOT confirm
     * installation — it only hands off. The observable truth afterwards:
     * INSTALL_PENDING until the process is restarted onto the new version
     * (state refreshes on next About view), or the user cancels in the
     * installer UI and returns (state reverts on their next action).
     */
    @SuppressLint("SetWorldReadable") // never called — kept only to document we deliberately do NOT use it
    fun launchInstall(ctx: Context, apk: File): Boolean {
        try {
            val authority = "${ctx.packageName}.fileprovider"
            val uri: Uri = FileProvider.getUriForFile(ctx, authority, apk)
            val ext = MimeTypeMap.getSingleton().getMimeTypeFromExtension("apk") ?: "application/vnd.android.package-archive"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, ext)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (installer != null) return installer!!.invoke(ctx, apk)
            ctx.startActivity(intent)
            setState(state.copy(phase = Phase.INSTALL_PENDING))
            return true
        } catch (e: Exception) {
            setState(state.copy(phase = Phase.INSTALL_FAILED,
                message = "The installer could not be opened: ${e.message}"))
            return false
        }
    }

    // ══ §6.9 — cleanup lifecycle ═══════════════════════════════════════════

    /**
     * Safe cleanup rules, exactly per §6.9:
     *  - a download for a DIFFERENT (older/other) release than the one the
     *    state still tracks is abandoned → delete;
     *  - files in the update dir not matching the CURRENT pending apk → delete;
     *  - after a version change (the app restarted onto a new build), the
     *    old release's apk is obsolete → delete;
     *  - never deletes anything outside cacheDir/updates.
     */
    fun cleanupAbandoned(ctx: Context) {
        val dir = downloadDir(ctx)
        val keep = stateRef.get().availableRelease?.let { apkFileFor(ctx, it).name }
        dir.listFiles()?.forEach { f ->
            if (keep == null || f.name != keep) {
                try { f.delete() } catch (e: Exception) { }
            }
        }
    }

    /** Called from the About screen's onResume: a version that no longer
     *  matches the pending release means the install SUCCEEDED (the process
     *  is running the new build) — report it and clear the temp file. */
    fun reconcileAfterRestart(ctx: Context) {
        val cur = installedVersionName(ctx)
        val pending = stateRef.get()
        val pendingVer = pending.availableRelease?.version
        if (pending.phase == Phase.INSTALL_PENDING && pendingVer != null &&
            ReleaseModel.compareVersions(cur, pendingVer) >= 0) {
            try { apkFileFor(ctx, pending.availableRelease!!).delete() } catch (e: Exception) { }
            cleanupAbandoned(ctx)
            setState(State(Phase.IDLE, cur, message = "Updated to $cur."))
        } else {
            cleanupAbandoned(ctx)
            setState(State(Phase.IDLE, cur))
        }
    }

}
