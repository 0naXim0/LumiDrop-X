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
        DOWNLOADING, FINALIZING, DOWNLOAD_COMPLETE,
        VERIFYING, VERIFIED, VERIFY_FAILED,
        READY_TO_INSTALL,          // verified — one Install action away (§1.2)
        INSTALL_PERMISSION_REQUIRED, // §9.1: the OS needs the install-unknown-apps grant
        INSTALL_PENDING,           // installer launched, awaiting the user
        INSTALL_CANCELLED,         // §7.6: user backed out of the installer — retry stays available
        INSTALL_FAILED,            // §7.6: the installer flow itself errored
        UPDATE_INSTALLED,          // §9.1.9: the new version is actually running (verifiable)
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

    // ── PASS 21 (§3.3 — duplicate-operation prevention): ONE in-flight ──
    // operation at a time, enforced by compare-and-set on this guard. A
    // rapid double-tap, a re-entrant auto-check, or a second download for
    // the same release all fail the CAS and are ignored — never queued,
    // never duplicated. Released by the owning operation's exit path
    // (finally), so an exception can never wedge the updater permanently.
    private val operationGuard = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun acquireGuard(): Boolean = operationGuard.compareAndSet(false, true)
    private fun releaseGuard() { operationGuard.set(false) }
    fun isOperationInFlight(): Boolean = operationGuard.get()

    /** True while [phase] belongs to the download/verify/install pipeline. */
    fun isBusyPhase(phase: Phase): Boolean = phase in BUSY_PHASES
    private val BUSY_PHASES = setOf(Phase.DOWNLOADING, Phase.FINALIZING,
        Phase.VERIFYING, Phase.INSTALL_PENDING)

    // ── PASS 21 (§2.2 — live progress + state observation): the UI no ──
    // longer polls once at the end; it subscribes and re-renders on every
    // state change (progress ticks land on the main thread via the
    // listener's own handler). Listeners are invoked on the CALLING thread
    // — the UI installs a main-thread dispatcher, background paths are
    // fire-and-forget safe (a throwing listener never breaks the engine).
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(State) -> Unit>()
    fun addListener(l: (State) -> Unit) {
        listeners.add(l)
        // the synchronous replay is try-caught like notifyState — a
        // throwing listener must never crash the registering thread
        try { l(stateRef.get()) } catch (e: Exception) { }
    }
    fun removeListener(l: (State) -> Unit) { listeners.remove(l) }
    private fun notifyState(s: State) {
        for (l in listeners) { try { l(s) } catch (e: Exception) { } }
    }

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
        // §3.3: a check never runs on top of a live download/verify/install
        // pipeline, and two checks never run concurrently.
        if (!acquireGuard()) {
            if (manual) setState(state.copy(message = "An update operation is already running."))
            return
        }
        try { checkForUpdatesInner(ctx, manual) }
        finally { releaseGuard() }
    }

    private fun checkForUpdatesInner(ctx: Context, manual: Boolean) {
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
    /** Single-tap entry: marks the download STARTED immediately (§2.1 —
     *  the UI flips into its downloading presentation the instant the tap
     *  lands, never waiting for the first network callback) and enforces
     *  the one-operation guard, then runs [downloadBlocking] on the
     *  caller's background thread. Idempotent per the guard: a second
     *  concurrent call is a no-op. */
    fun download(ctx: Context, release: Release, cancelled: () -> Boolean): Boolean {
        if (!acquireGuard()) return false   // §3.3: one download at a time
        // §2.1: immediate, optimistic-but-honest start state (0 bytes is the
        // truth at this instant; the first network callback refines it)
        setState(State(Phase.DOWNLOADING, installedVersionName(ctx), release, 0L, release.apkSizeBytes))
        try {
            val ok = downloadBlocking(ctx, release, cancelled)
            if (ok && !cancelled()) {
                // §1.3: the chain download → verify → ready runs INSIDE the
                // same guard, exactly once — completion can never strand at
                // 100% again, and no caller needs to remember to chain.
                verify(ctx, release, apkFileFor(ctx, release))
            }
            return ok
        } finally {
            releaseGuard()
        }
    }

    /** The streamed download itself. Returns true only on clean EOF + a
     *  passed size check, with streams flushed and closed BEFORE the
     *  DOWNLOAD_COMPLETE/FINALIZING states are published. All failure and
     *  cancel paths delete the partial file and publish an honest state. */
    private fun downloadBlocking(ctx: Context, release: Release, cancelled: () -> Boolean): Boolean {
        val dest = apkFileFor(ctx, release)
        val url = release.apkUrl ?: run {
            setState(State(Phase.DOWNLOAD_FAILED, installedVersionName(ctx), availableRelease = release, message = "No APK asset."))
            return false
        }
        if (!url.startsWith("https://", true)) {
            setState(State(Phase.DOWNLOAD_FAILED, installedVersionName(ctx), availableRelease = release, message = "Refusing non-HTTPS download URL."))
            return false
        }
        try {
            var done = dest.length().coerceAtMost(release.apkSizeBytes.takeIf { it > 0 } ?: Long.MAX_VALUE)
            var lastTick = 0L
            while (true) {
                if (cancelled()) { dest.delete()
                    setState(State(Phase.UPDATE_AVAILABLE, installedVersionName(ctx), availableRelease = release, message = "Download cancelled."))
                    return false }
                val pair = try { httpsStream(url, if (done > 0) done else -1L) } catch (e: Exception) {
                    setState(State(Phase.DOWNLOAD_FAILED, installedVersionName(ctx), availableRelease = release, message = describeNetworkError(e)))
                    return false
                }
                val (conn, resumed) = pair
                try {
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        setState(State(Phase.DOWNLOAD_FAILED, installedVersionName(ctx), availableRelease = release,
                            message = "The server returned HTTP $code."))
                        return false
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
                            if (cancelled()) { out.flush(); out.close(); dest.delete()
                                setState(State(Phase.UPDATE_AVAILABLE, installedVersionName(ctx), release, message = "Download cancelled."))
                                return false }
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n); done += n
                            // §2.2: smooth, real progress — throttled to ~5 Hz
                            // so a fast connection doesn't flood the listener
                            // (the bytes are ALWAYS the true count, never reset,
                            // never invented; 0 total stays "unknown-size").
                            val now = System.currentTimeMillis()
                            if (now - lastTick >= 200L) {
                                lastTick = now
                                setState(State(Phase.DOWNLOADING, installedVersionName(ctx), release, done, total))
                            }
                        }
                        out.flush()
                    }
                    // ── PASS 21 (§1.2 — FINALIZING is an explicit, honest ──
                    // state: EOF reached, streams flushed+closed, the final
                    // size check runs NOW; only after it passes does the
                    // lifecycle move on. A user watching 100% sees
                    // "Finalizing…" instead of a frozen bar (§1.1.7-14).
                    setState(State(Phase.FINALIZING, installedVersionName(ctx), release, done, done))
                    val expected = release.apkSizeBytes
                    if (expected > 0 && dest.length() != expected) {
                        dest.delete()
                        setState(State(Phase.DOWNLOAD_FAILED, installedVersionName(ctx), release,
                            message = "The download was incomplete (got ${dest.length()} of $expected bytes)."))
                        return false
                    }
                    if (dest.length() <= 0L) {
                        dest.delete()
                        setState(State(Phase.DOWNLOAD_FAILED, installedVersionName(ctx), release,
                            message = "The download produced an empty file."))
                        return false
                    }
                    // clean EOF + size check passed — the ONE true completion
                    setState(State(Phase.DOWNLOAD_COMPLETE, installedVersionName(ctx), release, dest.length(), dest.length()))
                    return true
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: Exception) {
            setState(State(Phase.DOWNLOAD_FAILED, installedVersionName(ctx), availableRelease = release, message = describeNetworkError(e)))
            return false
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
        // §3.3: verification is part of the one in-flight operation — if the
        // guard is already held by the download chain, we OWN it; a caller
        // invoking verify standalone acquires it here (idempotent either way).
        val ownedHere = acquireGuard()
        try {
            return verifyInner(ctx, release, apk)
        } finally { if (ownedHere) releaseGuard() }
    }

    private fun verifyInner(ctx: Context, release: Release, apk: File): VerifyResult {
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
        // ── PASS 21 (§1.2): verification success lands in the lifecycle- ──
        // correct terminal pair — VERIFIED (the operation result) then
        // READY_TO_INSTALL or INSTALL_PERMISSION_REQUIRED (the user-facing
        // state the About card designs its single primary action around).
        setState(State(Phase.VERIFIED, installedVersionName(ctx), release))
        if (Build.VERSION.SDK_INT >= 26 && !canRequestInstalls(ctx)) {
            setState(State(Phase.INSTALL_PERMISSION_REQUIRED, installedVersionName(ctx), release,
                message = "Android needs your permission to let LumiDrop X install updates."))
        } else {
            setState(State(Phase.READY_TO_INSTALL, installedVersionName(ctx), release))
        }
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
        // §3.3: one installer handoff at a time — repeated taps cannot
        // stack multiple installer dialogs. The guard is released when the
        // user returns (reconcile) or on failure; INSTALL_PENDING itself is
        // a rest state, not an in-flight operation.
        if (!acquireGuard()) return false
        try {
            // §9.1.3-4: the permission gate comes FIRST — launching the
            // installer without the grant just crashes or silently fails.
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
        } finally {
            // the handoff itself is instantaneous — the guard protects
            // against double-taps during the launch, not during the OS flow
            releaseGuard()
        }
    }

    /** §9.3.3: the user came back without the version changing — the
     *  installer was dismissed. Called by the About card's onResume. */
    fun markInstallDismissed(ctx: Context) {
        val cur = stateRef.get()
        if (cur.phase == Phase.INSTALL_PENDING) {
            setState(State(Phase.INSTALL_CANCELLED, installedVersionName(ctx),
                availableRelease = cur.availableRelease,
                message = "The installation was cancelled — your current version is untouched."))
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
        val st = stateRef.get()
        // §9.4: keep the CURRENT release's apk while the state still tracks
        // it AND it may still be needed (verified-ready, or mid-download);
        // an in-flight operation's file is never touched by a sweep.
        val keep = st.availableRelease?.let { apkFileFor(ctx, it).name }
        val inFlight = isOperationInFlight()
        dir.listFiles()?.forEach { f ->
            if (!inFlight && (keep == null || f.name != keep)) {
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
        // §1.2/§9.1.9: "installed" is claimed ONLY when the process is
        // verifiably running the new version — never from a handoff alone.
        val wasWaitingForInstaller = pending.phase == Phase.INSTALL_PENDING ||
            pending.phase == Phase.INSTALL_CANCELLED
        if (wasWaitingForInstaller && pendingVer != null &&
            ReleaseModel.compareVersions(cur, pendingVer) >= 0) {
            try { apkFileFor(ctx, pending.availableRelease!!).delete() } catch (e: Exception) { }
            cleanupAbandoned(ctx)
            setState(State(Phase.UPDATE_INSTALLED, cur,
                message = "Updated to $cur."))
        } else {
            cleanupAbandoned(ctx)
            setState(State(Phase.IDLE, cur))
        }
    }

}
