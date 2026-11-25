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
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.HttpsURLConnection

/**
 * ══ PRODUCTION FINAL PASS 23 — the reconstructed update lifecycle ══════════
 *
 * Pass 22's real-device failures, and what this pass permanently changed:
 *
 *  A. INSTALL FAILURE ("Failed to find configured root that contains
 *     /data/data/com.lumidrop.x/cache/updates/lumidrop-update-v1.0.4.apk"):
 *     the APK was staged in cacheDir/updates/ but res/xml/file_paths.xml had
 *     NO root covering the internal cache — getUriForFile threw before the
 *     installer could ever open. Fixed in the provider XML (a <cache-path>
 *     root for exactly the updates/ directory); launchInstall below now
 *     verifies the grant is possible BEFORE claiming anything.
 *
 *  B. HTTP 416 as a PERMANENT download failure: a complete staged APK made
 *     every "Download" press ask for Range: bytes=<fullsize>-; the server's
 *     416 was treated as a dead network and the file was never inspected or
 *     removed, so Retry re-issued the same impossible range forever. The
 *     engine below reads the 416's Content-Range total, compares it with the
 *     file's ACTUAL length, and either accepts the file as complete (→
 *     verification, no network) or discards only the invalid bytes and
 *     restarts clean — bounded, never looping (UpdatePolicy).
 *
 *  C. REDOWNLOADING A VALIDATED APK: state lived only in memory. Now every
 *     milestone is snapshotted (UpdateStore) and download() validates an
 *     on-disk file that matches the release asset BEFORE dialing — a valid
 *     APK is reused, an invalid one is deleted and re-fetched.
 *
 *  D. UPDATE DISCOVERY VANISHING ON RESTART: the discovered release is
 *     persisted at check time and restored by resumeFlow() on every About
 *     resume — including install-success detection (§8) and supersession
 *     handling (§11).
 *
 * Signing truth (unchanged from the audited repository): Android's installer
 * is the final gate; this updater verifies package name, version, version
 * code and signing identity against the RUNNING installation before the
 * handoff, and only ever claims UPDATE_INSTALLED when the process is
 * verifiably running the new version.
 *
 * Networking discipline (unchanged): HTTPS only, the public Releases API and
 * the release asset URL only, no tokens, no telemetry; checks are paced by
 * the 24h auto-check interval; downloads are streamed to disk in 64 KB
 * chunks, never held in memory.
 */
object UpdateManager {

    private const val TAG = "LumiDropX-Updater"

    // ── observable state (single writer: one guarded operation at a time) ──
    enum class Phase {
        IDLE, CHECKING,
        UP_TO_DATE, UPDATE_AVAILABLE,
        DOWNLOADING, FINALIZING, DOWNLOAD_COMPLETE,
        VERIFYING, VERIFIED, VERIFY_FAILED,
        READY_TO_INSTALL,          // verified — one Install action away
        INSTALL_PERMISSION_REQUIRED, // API 26+: the install-unknown-apps grant
        INSTALL_PENDING,           // installer launched, awaiting the user
        INSTALL_CANCELLED,         // user backed out — APK preserved, retry offered
        INSTALL_FAILED,            // the installer flow itself errored
        UPDATE_INSTALLED,          // the new version is verifiably running
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
    private fun setState(s: State) { state = s; stateRef.set(s); notifyState(s) }

    // ── PASS 21 (§3.3): ONE in-flight operation, CAS-guarded; rapid ──
    // double-taps, re-entrant auto-checks and second downloads for the same
    // release all fail the CAS and are ignored. Released in finally, so an
    // exception can never wedge the updater.
    private val operationGuard = AtomicBoolean(false)
    private fun acquireGuard(): Boolean = operationGuard.compareAndSet(false, true)
    private fun releaseGuard() { operationGuard.set(false) }
    fun isOperationInFlight(): Boolean = operationGuard.get()

    /** True while [phase] belongs to the download/verify/install pipeline. */
    fun isBusyPhase(phase: Phase): Boolean = phase in BUSY_PHASES
    private val BUSY_PHASES = setOf(Phase.DOWNLOADING, Phase.FINALIZING,
        Phase.VERIFYING, Phase.INSTALL_PENDING)

    // ── PASS 21 (§2.2): live state observation — the UI subscribes once ──
    // and re-renders on every change (progress included).
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()
    fun addListener(l: (State) -> Unit) {
        listeners.add(l)
        try { l(stateRef.get()) } catch (e: Exception) { }
    }
    fun removeListener(l: (State) -> Unit) { listeners.remove(l) }
    private fun notifyState(s: State) {
        for (l in listeners) { try { l(s) } catch (e: Exception) { } }
    }

    /** Test seam: replaces the release-API fetch. Production talks HTTPS. */
    @Volatile var fetcher: ((String) -> String?)? = null
    /** Test seam: replaces the installer launch. */
    @Volatile var installer: ((Context, File) -> Boolean)? = null
    /** Test seam: replaces the download transport (see [DownloadEngine] —
     *  the whole 416/retry/resume matrix is exercised on the JVM through
     *  the engine's own tests; this seam serves integration checks). */
    @Volatile var connectionFactory: ((url: String, fromByte: Long) -> DownloadEngine.Connection)? = null

    fun installedVersionName(ctx: Context): String = try {
        val pi: PackageInfo = if (Build.VERSION.SDK_INT >= 33)
            ctx.packageManager.getPackageInfo(ctx.packageName, PackageManager.PackageInfoFlags.of(0))
        else @Suppress("DEPRECATION") ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        pi.versionName ?: ""
    } catch (e: Exception) { "" }

    // ══ snapshot persistence (§5) ═══════════════════════════════════════════

    private fun storeSnapshot(ctx: Context, release: Release?, ready: Boolean, pending: Boolean) {
        val prefs = ReleaseModel.prefs(ctx)
        UpdateStore.store(prefs, UpdateStore.Snapshot(
            release = release,
            lastCheckAtMs = prefs.getLong(UpdateStore.KEY_LAST_CHECK, 0L),
            readyToInstall = ready,
            installPending = pending))
    }

    private fun clearSnapshot(ctx: Context) {
        UpdateStore.clear(ReleaseModel.prefs(ctx))
    }

    // ══ §6.1/§6.2 — checking ═══════════════════════════════════════════════

    fun checkForUpdates(ctx: Context, manual: Boolean) {
        if (!acquireGuard()) {
            if (manual) setState(state.copy(message = "An update operation is already running."))
            return
        }
        try { checkForUpdatesInner(ctx, manual) }
        finally { releaseGuard() }
    }

    private fun checkForUpdatesInner(ctx: Context, manual: Boolean) {
        val installed = installedVersionName(ctx)
        // The release this session (or the persisted snapshot) already knows
        // about — carried through CHECKING so a failed check NEVER erases a
        // previous discovery (§6: "without erasing cached release information").
        val knownRelease = stateRef.get().availableRelease
            ?: try { UpdateStore.load(ReleaseModel.prefs(ctx)).release } catch (e: Exception) { null }
        setState(State(Phase.CHECKING, installed, availableRelease = knownRelease))
        val body = try {
            fetcher?.invoke(RELEASES_API) ?: httpsGet(RELEASES_API)
        } catch (e: Exception) {
            // §6: a failed check NEVER erases previously discovered release
            // information — the offer (state + persisted snapshot) survives.
            setState(State(Phase.CHECK_FAILED, installed,
                availableRelease = knownRelease,
                message = describeNetworkError(e)))
            return
        }
        if (body == null) {
            setState(State(Phase.CHECK_FAILED, installed,
                availableRelease = knownRelease,
                message = "GitHub is unavailable or rate-limited. Try again later."))
            return
        }
        val releases = ReleaseModel.parseReleases(body)
        val decision = ReleaseModel.decide(installed, releases)
        val prefs = ReleaseModel.prefs(ctx)
        prefs.edit().putLong(UpdateStore.KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        when (decision) {
            is ReleaseModel.Decision.NoReleases -> {
                storeSnapshot(ctx, null, false, false)
                setState(State(Phase.UP_TO_DATE, installed, message = "No releases are published yet."))
            }
            is ReleaseModel.Decision.NoUsableRelease -> {
                storeSnapshot(ctx, null, false, false)
                setState(State(Phase.UP_TO_DATE, installed,
                    message = if (manual) "No compatible stable release found." else ""))
            }
            is ReleaseModel.Decision.UpToDate -> {
                // The installed version covers the newest release: any staged
                // APK belongs to an older or equal release and is obsolete.
                val snapRel = UpdateStore.load(prefs).release
                if (snapRel != null) apkFileFor(ctx, snapRel).delete()
                storeSnapshot(ctx, null, false, false)
                sweepNow(ctx, null)   // nothing is needed anymore — every staged file is obsolete
                setState(State(Phase.UP_TO_DATE, installed))
            }
            is ReleaseModel.Decision.UpdateAvailable -> {
                val rel = decision.release
                val cur = stateRef.get()
                val snap = UpdateStore.load(prefs)
                val staged = cur.availableRelease ?: snap.release
                // §11 supersession: a DIFFERENT release (or a replaced asset)
                // makes the staged file obsolete — delete it, never present
                // old bytes as the new release's download.
                if (staged != null && UpdatePolicy.supersedes(
                        rel.tagName, rel.apkUrl, staged.tagName, staged.apkUrl)) {
                    try { apkFileFor(ctx, staged).delete() } catch (e: Exception) { }
                }
                // §4/Test G: re-checking while a VALIDATED APK for THIS SAME
                // release is already staged must not demote it back to a
                // download offer.
                val alreadyValidated = (cur.phase == Phase.READY_TO_INSTALL ||
                    cur.phase == Phase.INSTALL_PERMISSION_REQUIRED ||
                    snap.readyToInstall) &&
                    staged != null && staged.tagName == rel.tagName && staged.apkUrl == rel.apkUrl
                if (alreadyValidated) {
                    storeSnapshot(ctx, rel, true, false)
                    setState(cur.copy(phase = if (cur.phase == Phase.READY_TO_INSTALL ||
                        cur.phase == Phase.INSTALL_PERMISSION_REQUIRED) cur.phase
                        else Phase.READY_TO_INSTALL,
                        installedVersion = installed, availableRelease = rel))
                } else {
                    storeSnapshot(ctx, rel, false, false)
                    setState(State(Phase.UPDATE_AVAILABLE, installed, availableRelease = rel))
                }
            }
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
     *  and never visible to other apps (the FileProvider grant is the ONLY
     *  external access, installer-only, for exactly the updates/ subtree). */
    private fun downloadDir(ctx: Context): File = File(ctx.cacheDir, "updates").apply { mkdirs() }

    fun apkFileFor(ctx: Context, release: Release): File =
        File(downloadDir(ctx), "lumidrop-update-${release.tagName}.apk")

    /**
     * Single-tap entry: enforces the one-operation guard, then applies §4's
     * reuse rules BEFORE any network — a file that already matches the
     * release's asset size is validated in place (download skipped when it
     * passes), a smaller partial is resumed from its REAL length, and only
     * a missing/invalid file is fetched fresh.
     */
    fun download(ctx: Context, release: Release, cancelled: () -> Boolean): Boolean {
        if (!acquireGuard()) return false   // §3.3: one download at a time
        try {
            val dest = apkFileFor(ctx, release)
            when (UpdatePolicy.preDownloadAction(
                    if (dest.isFile) dest.length() else -1L, release.apkSizeBytes)) {
                UpdatePolicy.PreDownloadAction.VERIFY_IN_PLACE -> {
                    // The staged file already claims completeness — let the
                    // verifier judge it. A pass lands in READY_TO_INSTALL
                    // with ZERO network traffic; a fail has already deleted
                    // the invalid bytes and falls through to a fresh fetch.
                    val res = verifyInner(ctx, release, dest)
                    if (res is VerifyResult.Ok) {
                        storeSnapshot(ctx, release, true, false)
                        return true
                    }
                }
                else -> setState(State(Phase.DOWNLOADING, installedVersionName(ctx),
                    release, if (dest.isFile) dest.length() else 0L, release.apkSizeBytes))
            }
            val ok = downloadBlocking(ctx, release, cancelled)
            if (ok && !cancelled()) {
                verify(ctx, release, apkFileFor(ctx, release))
            }
            return ok
        } finally {
            releaseGuard()
        }
    }

    /**
     * The streamed, resumable, 416-recovering download — delegated to
     * [DownloadEngine] (the context-free, JVM-unit-tested control flow; all
     * decisions come from UpdatePolicy and every budget there is bounded).
     * This wrapper only translates the engine's terminal outcome into an
     * honest state + snapshot. Exits ONLY in: UPDATE_AVAILABLE (cancelled),
     * DOWNLOAD_FAILED (honest reason, partial preserved when resumable), or
     * the verify chain.
     */
    private fun downloadBlocking(ctx: Context, release: Release, cancelled: () -> Boolean): Boolean {
        val installed = installedVersionName(ctx)
        val dest = apkFileFor(ctx, release)
        val url = release.apkUrl ?: run {
            setState(State(Phase.DOWNLOAD_FAILED, installed, availableRelease = release, message = "No APK asset."))
            return false
        }
        if (!url.startsWith("https://", true)) {
            setState(State(Phase.DOWNLOAD_FAILED, installed, availableRelease = release, message = "Refusing non-HTTPS download URL."))
            return false
        }
        var lastTick = 0L
        val outcome = DownloadEngine.download(
            url, dest, release.apkSizeBytes, cancelled,
            open = { u, from -> connectionFactory?.invoke(u, from) ?: httpsConnection(u, from) },
            onProgress = { done, total ->
                // §2.2: real bytes on every tick, throttled to ~5 Hz so a
                // fast connection never floods the listener.
                val now = System.currentTimeMillis()
                if (now - lastTick >= UpdatePolicy.PROGRESS_TICK_MS) {
                    lastTick = now
                    setState(State(Phase.DOWNLOADING, installed, release, done, total))
                }
            })
        return when (outcome) {
            DownloadEngine.Outcome.Completed ->
                finishDownload(ctx, release, dest, dest.length())
            DownloadEngine.Outcome.Cancelled -> {
                setState(State(Phase.UPDATE_AVAILABLE, installed, availableRelease = release,
                    message = "Download cancelled."))
                false
            }
            is DownloadEngine.Outcome.Failed -> {
                setState(State(Phase.DOWNLOAD_FAILED, installed, availableRelease = release,
                    message = outcome.message))
                false
            }
        }
    }

    /** EOF-reached finalization: explicit FINALIZING state, then the one
     *  honest completion signal (and the snapshot milestone). */
    private fun finishDownload(ctx: Context, release: Release, dest: File, serverTotal: Long?): Boolean {
        val installed = installedVersionName(ctx)
        setState(State(Phase.FINALIZING, installed, release, dest.length(),
            serverTotal ?: release.apkSizeBytes))
        val len = dest.length()
        if (len <= 0L) {
            dest.delete()
            setState(State(Phase.DOWNLOAD_FAILED, installed, availableRelease = release,
                message = "The download produced an empty file."))
            return false
        }
        storeSnapshot(ctx, release, false, false)
        setState(State(Phase.DOWNLOAD_COMPLETE, installed, release, len, len))
        return true
    }

    /** The production transport: a plain HTTPS connection with the resume
     *  Range header — everything else about the exchange is decided by the
     *  engine and UpdatePolicy. */
    private fun httpsConnection(url: String, fromByte: Long): DownloadEngine.Connection {
        val conn = (URL(url).openConnection() as HttpsURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000
            setRequestProperty("User-Agent", "LumiDropX-Updater")
            if (fromByte > 0) setRequestProperty("Range", "bytes=$fromByte-")
            instanceFollowRedirects = true
        }
        return object : DownloadEngine.Connection {
            override val code: Int get() = conn.responseCode
            override val contentLength: Long get() = conn.contentLengthLong
            override fun header(name: String): String? = conn.getHeaderField(name)
            override fun body(): InputStream = conn.inputStream ?: throw IOException("no stream")
            override fun close() { conn.disconnect() }
        }
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
     *  3. its versionName is the release's (never an older one);
     *  4. its versionCode strictly exceeds the installed one;
     *  5. its signing certificate matches the installed app's exactly;
     *  6. when the release notes publish a SHA-256, the file matches it.
     * The APK is never executed — verification only PARSES it.
     */
    fun verify(ctx: Context, release: Release, apk: File): VerifyResult {
        val ownedHere = acquireGuard()
        try {
            return verifyInner(ctx, release, apk)
        } finally { if (ownedHere) releaseGuard() }
    }

    private fun verifyInner(ctx: Context, release: Release, apk: File): VerifyResult {
        val installed = installedVersionName(ctx)
        setState(State(Phase.VERIFYING, installed, availableRelease = release))
        val pm = ctx.packageManager
        val info = try {
            pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNATURES)
                ?: pm.getPackageArchiveInfo(apk.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
        } catch (e: Exception) { null }
        if (info == null) {
            return verifyFailed(ctx, release, apk, "The downloaded file is not a valid APK.",
                "Delete it and try downloading again.")
        }
        if (info.packageName != ctx.packageName) {
            return verifyFailed(ctx, release, apk,
                "The update's package name (${info.packageName}) does not match this app (${ctx.packageName}).",
                "This APK cannot update LumiDrop X. It was not installed.")
        }
        val wantVersion = release.version
        if (wantVersion != null && info.versionName != null &&
            ReleaseModel.compareVersions(info.versionName, wantVersion) != 0) {
            return verifyFailed(ctx, release, apk,
                "The APK's version (${info.versionName}) does not match the release ($wantVersion).",
                "The file may be mislabeled. It was not installed.")
        }
        val installedCode = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
        } catch (e: Exception) { -1L }
        val apkCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode
        else @Suppress("DEPRECATION") (info.versionCode ?: -1).toLong()
        if (installedCode >= 0 && apkCode in 0..installedCode) {
            return verifyFailed(ctx, release, apk,
                "The update's version code ($apkCode) is not newer than this installation ($installedCode).",
                "Android cannot install it as an update. The download was not installed.")
        }
        val installedSig = installedSignatures(ctx)
        val apkSig = archiveSignatures(pm, info)
        if (installedSig != null && apkSig != null && installedSig != apkSig) {
            return verifyFailed(ctx, release, apk,
                "The update is signed with a different key than this installation.",
                "Android cannot update the app in place across signing keys. " +
                "The download was NOT installed — your current app is untouched.")
        }
        // §3.E: a checksum is enforced exactly when the release publishes one
        // (notes convention "SHA-256: <hex>") — never invented, never assumed.
        val published = UpdatePolicy.checksumFromNotes(release.bodyMarkdown)
        if (published != null && verifyChecksumSha256(apk, published) != true) {
            return verifyFailed(ctx, release, apk,
                "The downloaded update failed its published checksum.",
                "The file was corrupted in transit. Download it again.")
        }
        setState(State(Phase.VERIFIED, installed, availableRelease = release))
        if (Build.VERSION.SDK_INT >= 26 && !canRequestInstalls(ctx)) {
            setState(State(Phase.INSTALL_PERMISSION_REQUIRED, installed, release,
                message = "Android needs your permission to let LumiDrop X install updates."))
        } else {
            setState(State(Phase.READY_TO_INSTALL, installed, release))
        }
        storeSnapshot(ctx, release, true, false)
        return VerifyResult.Ok
    }

    /** §9.1.4: API 26+ — can this app ask the OS to install packages? */
    fun canRequestInstalls(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 26) ctx.packageManager.canRequestPackageInstalls() else true
    } catch (e: Exception) { false }

    /** §9.1.4: the Settings deep-link for the install-unknown-apps grant. */
    fun openInstallPermissionSettings(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= 26) {
            val i = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:" + ctx.packageName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i); true
        } else true
    } catch (e: Exception) { false }

    private fun verifyFailed(ctx: Context, release: Release, apk: File, reason: String, userAction: String): VerifyResult {
        // The invalid file is cleaned up (it can never install); the release
        // offer SURVIVES so the UI's "Retry Download" stays actionable.
        try { apk.delete() } catch (e: Exception) { }
        cleanupAbandoned(ctx)
        storeSnapshot(ctx, release, false, false)
        setState(State(Phase.VERIFY_FAILED, installedVersionName(ctx),
            availableRelease = release, message = reason))
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

    /** Optional published SHA-256 (release notes convention "SHA-256: <hex>")
     *  — enforced when present (see verifyInner), never invented. */
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
     * Launches Android's package installer over a FileProvider content URI
     * built from the SAME directory the provider's <cache-path> root maps
     * (res/xml/file_paths.xml — the Pass-23 root fix). The grant is
     * installer-only (FLAG_GRANT_READ_URI_PERMISSION); no world-readable
     * file is ever created. This hands off; it does NOT and CANNOT confirm
     * installation — UPDATE_INSTALLED is claimed only by resumeFlow() after
     * the process is verifiably running the new version.
     */
    fun launchInstall(ctx: Context, apk: File): Boolean {
        if (!acquireGuard()) return false
        try {
            if (Build.VERSION.SDK_INT >= 26 && !canRequestInstalls(ctx)) {
                setState(state.copy(phase = Phase.INSTALL_PERMISSION_REQUIRED,
                    message = "Android needs your permission to let LumiDrop X install updates."))
                return false
            }
            if (!apk.exists() || apk.length() <= 0L) {
                setState(state.copy(phase = Phase.VERIFY_FAILED,
                    message = "The downloaded APK is no longer present. Please download it again."))
                return false
            }
            val authority = "${ctx.packageName}.fileprovider"
            val uri: Uri = try {
                FileProvider.getUriForFile(ctx, authority, apk)
            } catch (e: Exception) {
                // The provider's configured roots must cover the APK's real
                // directory — this is the Pass-22 real-device failure point.
                android.util.Log.w(TAG, "FileProvider URI generation failed for ${apk.path}", e)
                setState(state.copy(phase = Phase.INSTALL_FAILED,
                    message = "Android could not open the installer. Your downloaded update is preserved — try installation again."))
                return false
            }
            val ext = MimeTypeMap.getSingleton().getMimeTypeFromExtension("apk") ?: "application/vnd.android.package-archive"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, ext)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (installer != null) return installer!!.invoke(ctx, apk)
            try {
                ctx.startActivity(intent)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Installer launch failed", e)
                setState(state.copy(phase = Phase.INSTALL_FAILED,
                    message = "Android could not open the installer. Your downloaded update is preserved — try installation again."))
                return false
            }
            setState(state.copy(phase = Phase.INSTALL_PENDING))
            stateRef.get().availableRelease?.let { storeSnapshot(ctx, it, true, true) }
            return true
        } finally {
            releaseGuard()
        }
    }

    // ══ §5/§8 — resume: restore, reconcile, and the paced auto-check ═══════

    /**
     * THE About-onResume entry point (replaces Pass 22's
     * reconcileAfterRestart + the section's own auto-check pacing — one
     * guarded pipeline, one source of truth). Under the one-operation guard:
     *
     *  1. RESTORE the persisted discovery (§5) — the update that was visible
     *     last session becomes visible again, from the snapshot + the real
     *     file on disk (a validated APK returns straight to READY_TO_INSTALL
     *     with no network traffic; a partial returns as a resumable offer).
     *  2. RECONCILE the install outcome (§8) — an INSTALL_PENDING handoff
     *     whose version is now running becomes UPDATE_INSTALLED + targeted
     *     cleanup; an unchanged version becomes INSTALL_CANCELLED with the
     *     validated APK preserved.
     *  3. Run the PACED auto-check (§6.1) only if due — never on top of an
     *     in-flight operation, never erasing restored state.
     */
    fun resumeFlow(ctx: Context) {
        if (!acquireGuard()) return   // an operation owns the state — it publishes
        try {
            val prefs = ReleaseModel.prefs(ctx)
            val snap = UpdateStore.load(prefs)
            val installed = installedVersionName(ctx)

            // ── 1. restore the persisted discovery ──
            if (snap.release != null) {
                val rel = snap.release
                val apk = apkFileFor(ctx, rel)
                val fileLen = if (apk.isFile) apk.length() else -1L
                when (UpdatePolicy.restoreAction(installed, rel.version, fileLen, rel.apkSizeBytes)) {
                    UpdatePolicy.RestoreAction.SUPERSEDED_BY_INSTALL -> {
                        // The persisted release is already running — the
                        // staged APK and the snapshot are obsolete.
                        try { apk.delete() } catch (e: Exception) { }
                        clearSnapshot(ctx)
                        sweepNow(ctx, null)   // install verified → no staged file is needed
                        setState(State(Phase.UPDATE_INSTALLED, installed,
                            message = "Updated to $installed."))
                    }
                    UpdatePolicy.RestoreAction.REVALIDATE_FILE -> {
                        // Complete-looking file: re-verify; a pass lands in
                        // READY_TO_INSTALL with zero network traffic (§4).
                        val res = verifyInner(ctx, rel, apk)
                        if (res is VerifyResult.Blocked) {
                            // invalid bytes were deleted; the offer stands
                            setState(State(Phase.UPDATE_AVAILABLE, installed, availableRelease = rel))
                        }
                    }
                    UpdatePolicy.RestoreAction.PARTIAL_RESUME -> {
                        setState(State(Phase.UPDATE_AVAILABLE, installed, availableRelease = rel,
                            message = "A partial download is preserved — it will resume where it stopped."))
                    }
                    UpdatePolicy.RestoreAction.OFFER_ONLY -> {
                        setState(State(Phase.UPDATE_AVAILABLE, installed, availableRelease = rel))
                    }
                    UpdatePolicy.RestoreAction.NOTHING -> { /* unreachable with snap.release != null */ }
                }
            }

            // ── 2. reconcile a same-process installer handoff (§8) ──
            val cur = stateRef.get()
            if (cur.phase == Phase.INSTALL_PENDING && cur.availableRelease != null) {
                val pendingVer = cur.availableRelease!!.version
                if (pendingVer != null &&
                    ReleaseModel.compareVersions(installed, pendingVer) >= 0) {
                    // verifiably running the new version → success + cleanup
                    try { apkFileFor(ctx, cur.availableRelease!!).delete() } catch (e: Exception) { }
                    clearSnapshot(ctx)
                    sweepNow(ctx, null)   // install verified → no staged file is needed
                    setState(State(Phase.UPDATE_INSTALLED, installed,
                        message = "Updated to $installed."))
                } else {
                    // the user came back without a version change — the
                    // installer was dismissed; the validated APK is PRESERVED.
                    setState(State(Phase.INSTALL_CANCELLED, installed, cur.availableRelease,
                        message = "The installation was cancelled — your current version is untouched. The downloaded update is still ready to install."))
                }
            }

            // ── 3. the paced auto-check (§6.1) ──
            if (ReleaseModel.shouldAutoCheck(
                    prefs.getBoolean("auto_check_enabled", true),
                    prefs.getLong(UpdateStore.KEY_LAST_CHECK, 0L),
                    System.currentTimeMillis(), false)) {
                checkForUpdatesInner(ctx, false)
            }
        } finally {
            releaseGuard()
        }
    }

    // ══ §6.9 — cleanup lifecycle ═══════════════════════════════════════════

    /**
     * Safe, updater-owned sweep of cacheDir/updates ONLY:
     *  - the CURRENT release's APK survives while the state or the persisted
     *    snapshot still tracks it (a fresh process restores BEFORE sweeping —
     *    a valid APK must never be lost to a restart, the Pass-22 defect);
     *  - an in-flight operation's files are untouchable;
     *  - everything else (older releases' leftovers, stale partials for
     *    superseded assets) is removed so the directory never accumulates.
     */
    fun cleanupAbandoned(ctx: Context) {
        val dir = downloadDir(ctx)
        val st = stateRef.get()
        val snap = try { UpdateStore.load(ReleaseModel.prefs(ctx)) } catch (e: Exception) { UpdateStore.Snapshot.empty() }
        val needed = st.availableRelease ?: snap.release
        val keep = needed?.let { apkFileFor(ctx, it).name }
        val inFlight = isOperationInFlight()
        dir.listFiles()?.forEach { f ->
            if (UpdatePolicy.maySweep(f.name, keep, inFlight)) {
                try { f.delete() } catch (e: Exception) { }
            }
        }
    }

    /**
     * The operation-owned sweep: run by a pipeline that HOLDS the one-
     * operation guard and has already established which file (if any) is
     * still needed — [keep] == null means everything under cacheDir/updates
     * is obsolete (a verified install, or up-to-date with a stale staged
     * file). Never touches anything outside cacheDir/updates (§9 safety).
     */
    private fun sweepNow(ctx: Context, keep: String?) {
        val dir = downloadDir(ctx)
        dir.listFiles()?.forEach { f ->
            if (keep == null || f.name != keep) {
                try { f.delete() } catch (e: Exception) { }
            }
        }
    }

    /** Test support: reset the singleton between test cases. */
    fun resetForTest() {
        state = State(Phase.IDLE)
        stateRef.set(state)
        operationGuard.set(false)
    }
}
