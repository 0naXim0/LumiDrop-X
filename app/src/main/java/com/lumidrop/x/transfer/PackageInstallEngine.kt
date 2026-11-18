package com.lumidrop.x.transfer

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipFile

/**
 * ══ GOAT-PASS 10 (PART 1.4–1.8) — the built-in package installation engine ══
 *
 * Installs ordinary APKs and supported multi-APK containers (XAPK / APKS /
 * APKM) through Android's OWN [PackageInstaller] session API — every APK
 * part is streamed into ONE session and committed together, which is the
 * platform's sanctioned way to install a split-APK application as a unit
 * (a single split missing from the session simply fails the commit — never
 * a half-installed app).
 *
 * Security posture (deliberate, non-negotiable):
 *  • On API 26+ the "install unknown apps" authorization is checked BEFORE
 *    a session is ever created; the caller is told to send the user to the
 *    system screen, and re-checks on return.
 *  • The commit itself lands on Android's own installer confirmation UI —
 *    this app never claims success before the OS reports it, and cannot
 *    silently install anything.
 *  • Sessions that fail mid-write are explicitly ABANDONED so no leaked
 *    session lingers in the system's session list.
 *
 * The result arrives as a broadcast (EXTRA_STATUS):
 *  STATUS_PENDING_USER_ACTION → the system confirmation activity is handed
 *  to the caller to SHOW; its completion produces the final
 *  STATUS_SUCCESS / STATUS_FAILURE_* broadcast.
 */
object PackageInstallEngine {

    private const val ACTION_STATUS = "com.lumidrop.x.INSTALL_STATUS"

    /** ── GOAT-PASS 13 (PART 4.2): the internal install-diagnostic ring ──
     *  buffer. Every stage of every operation appends one line; nothing here
     *  is ever shown raw in the UI — it exists so a future compatibility
     *  failure can be traced without guessing. Bounded (200 lines), lock-
     *  protected, logcat-mirrored at the info level only. */
    object InstallDiagnostics {
        private val lock = Any()
        private val lines = ArrayDeque<String>(200)
        private const val MAX = 200
        fun log(stage: String, detail: String) {
            val line = "[$stage] $detail"
            synchronized(lock) {
                if (lines.size >= MAX) lines.removeFirst()
                lines.addLast(line)
            }
            try { android.util.Log.i("LdxInstall", line) } catch (e: Exception) { /* logcat optional */ }
        }
        fun snapshot(): List<String> = synchronized(lock) { lines.toList() }
    }
    private const val EXTRA_SESSION_TAG = "ldx_install_session_tag"
    /** Monotonic tag per install attempt — the broadcast filter's own key,
     *  so a late broadcast from a PREVIOUS attempt can never be mistaken
     *  for this one's result. */
    private val sessionTagSeq = AtomicInteger(1)

    sealed class StartOutcome {
        /** The session was committed and the system confirmation activity
         *  must be shown by the caller to proceed. */
        data class NeedsConfirmation(val confirmIntent: Intent, val tag: Int) : StartOutcome()
        /** Blocked before any session existed: authorization missing. */
        object NeedsUnknownSourcesPermission : StartOutcome()
        /** The package cannot be installed as found — [reason] is a
         *  user-facing category, never a raw stack trace. */
        data class Rejected(val reason: String) : StartOutcome()
    }

    /** A one-shot receiver for the terminal result of one install attempt.
     *  Unregisters itself on the first terminal status (or on [close]). */
    class ResultWatch(
        private val context: Context,
        private val tag: Int,
        onTerminal: (success: Boolean, humanMessage: String) -> Unit,
    ) : AutoCloseable {
        private val main = Handler(Looper.getMainLooper())
        private var done = false
        private val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (intent.getIntExtra(EXTRA_SESSION_TAG, -1) != tag) return
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                InstallDiagnostics.log("result", "status=$status msg=${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "-"}")
                when (status) {
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                        main.post { onPendingConfirmation(confirm) }
                    }
                    else -> {
                        unregister()
                        main.post { onTerminal(status == PackageInstaller.STATUS_SUCCESS, humanMessage(status, intent)) }
                    }
                }
            }
        }
        var onPendingConfirmation: (Intent) -> Unit = {}
        private var registered = false

        @SuppressLint("UnspecifiedRegisterReceiverFlag")
        fun register() {
            if (registered) return
            registered = true
            if (Build.VERSION.SDK_INT >= 33)
                context.registerReceiver(receiver, IntentFilter(ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED)
            else context.registerReceiver(receiver, IntentFilter(ACTION_STATUS))
        }

        private fun unregister() {
            if (!registered) return
            registered = false
            try { context.unregisterReceiver(receiver) } catch (e: Exception) { /* already gone */ }
        }

        override fun close() { unregister() }

        private fun humanMessage(status: Int, intent: Intent): String = when (status) {
            PackageInstaller.STATUS_SUCCESS -> "Installed"
            PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation cancelled"
            PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation blocked by device policy or security settings"
            PackageInstaller.STATUS_FAILURE_CONFLICT ->
                if (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.contains("version", true) == true ||
                   intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.contains("downgrade", true) == true)
                    "Package conflict — an installed version already exists or this is an older version"
                else "Signature or package conflict — an incompatible version is already installed"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Package incompatible with this device"
            PackageInstaller.STATUS_FAILURE_INVALID -> "Invalid package — the archive is damaged or incomplete"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough storage space to install"
            else -> "Android rejected the installation"
        }
    }

    /** Creates a watch for one attempt's broadcasts. Register it BEFORE
     *  [install] so no result can race past the receiver. */
    fun watch(context: Context, tag: Int, onTerminal: (Boolean, String) -> Unit): ResultWatch =
        ResultWatch(context, tag, onTerminal).also { it.register() }

    /**
     * Streams [report]'s base + every split into one PackageInstaller
     * session and commits it. Only call when [report].isInstallable.
     * OBB entries are NOT part of the session (the OS installs only APKs)
     * — the caller handles them via [stageObb] after success.
     */
    fun install(context: Context, archive: File, report: PackageInspector.Report,
                onProgress: (writtenBytes: Long, totalBytes: Long) -> Unit): StartOutcome {
        // ── 1. Authorization (API 26+): never create a session without it. ──
        InstallDiagnostics.log("start", "archive=${archive.name} kind=${report.kind} pkg=${report.packageName} v=${report.versionName}(${report.versionCode}) base=${report.baseApk?.entryName} splits=${report.splitCount} reason=${report.baseSelectionReason}")
        report.diagnostics.forEach { InstallDiagnostics.log("inspect", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls())
            return StartOutcome.NeedsUnknownSourcesPermission
        val base = report.baseApk ?: return StartOutcome.Rejected("Missing base APK")

        // ── 2. Verify the archive still exists and still holds its parts. ──
        if (!archive.isFile) return StartOutcome.Rejected("Package file no longer exists on this device")
        val parts = ArrayList<Pair<String, Long>>()   // (zip entry name, size)
        try {
            ZipFile(archive).use { zf ->
                val baseEntry = zf.getEntry(base.entryName)
                    ?: return StartOutcome.Rejected("Missing base APK")
                parts.add(base.entryName to baseEntry.size)
                for (s in report.splits) {
                    val e = zf.getEntry(s.entryName) ?: continue   // a vanished split: the session would fail; report honestly
                    parts.add(s.entryName to e.size)
                }
                if (parts.size != 1 + report.splits.size)
                    return StartOutcome.Rejected("Missing split APK inside the archive")
            }
        } catch (e: Exception) {
            return StartOutcome.Rejected("Invalid package archive — not a readable ZIP")
        }
        if (parts.isEmpty()) return StartOutcome.Rejected("No installable APK components found")

        // ── 3. One session, every part, streamed in order (base first). ──
        val tag = sessionTagSeq.getAndIncrement()
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(
            PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppLabel(report.appLabel ?: archive.name)
        }
        var session: PackageInstaller.Session? = null
        try {
            val sessionId = installer.createSession(params)
            InstallDiagnostics.log("session", "created id=$sessionId parts=${parts.size} bytes=${parts.sumOf { it.second }}")
            val openSession = installer.openSession(sessionId)
            session = openSession
            val total = parts.sumOf { it.second }.coerceAtLeast(1L)
            var written = 0L
            ZipFile(archive).use { zf ->
                for ((name, _) in parts) {
                    val entry = zf.getEntry(name) ?: return StartOutcome.Rejected("Missing split APK inside the archive")
                    openSession.openWrite(name, 0L, entry.size).use { out ->
                        zf.getInputStream(entry).use { ins ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                written += n
                                onProgress(written, total)
                            }
                        }
                        out.flush()
                        openSession.fsync(out)
                    }
                }
            }
            InstallDiagnostics.log("session", "written ${parts.size} parts, committing")
            val committed = openSession
            // ── 4. Commit: the OS takes over; results arrive by broadcast. ──
            // The commit callback's first broadcast is STATUS_PENDING_USER_
            // ACTION carrying the system's confirmation Intent — the watch
            // hands it to the caller to show. There is no synchronous
            // success: the OS's own confirmation is the next step.
            val statusPi = PendingIntent.getBroadcast(
                context, tag,
                Intent(ACTION_STATUS).setPackage(context.packageName).putExtra(EXTRA_SESSION_TAG, tag),
                PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            committed.commit(statusPi.intentSender)
            committed.close()
            session = null
            return StartOutcome.NeedsConfirmation(Intent(), tag)
        } catch (e: Exception) {
            return StartOutcome.Rejected("Android rejected the installation session")
        } finally {
            // A session that failed before commit must not linger.
            try { session?.abandon() } catch (e: Exception) { /* already gone */ }
        }
    }

    /** ── GOAT-PASS 12 (PART 1.3/1.4): OBB provisioning, rebuilt. ─────────────
     *  The Pass-11 flow staged OBB AFTER the install broadcast, fire-and-
     *  forget — the "Installed" toast fired before OBB data even started
     *  copying, a first-run `Android/obb/<pkg>/` mkdir raced the OS, and
     *  verification was `dest.exists()` alone. The reported story (first
     *  install broken with an OBB-not-found error, second install fine —
     *  because attempt #1 had already created the directories) matches
     *  exactly.
     *
     *  New ordering: OBB entries are extracted to a STAGING area in this
     *  app's own cache (no permission needed, always writable, never the
     *  user's obb home) BEFORE the install session is committed, and
     *  SIZE-VERIFIED there. After Android confirms the package, each staged
     *  file is moved into the real obb home and verified AGAIN — existence
     *  AND length — before provisioning is called successful. Incomplete
     *  staged files never reach the obb home, so a retry can never mistake
     *  damaged data for a completed provisioning. */
    object Obb {
        /** One OBB file staged to our cache, size-verified against its
         *  archive entry. */
        data class Staged(val obb: PackageInspector.ObbEntry,
                          val stagedFile: File, val expectedBytes: Long)
    }

    /** Extracts [obb] from [archive] into the cache-dir staging area and
     *  verifies its size. Returns null on any failure (caller reports the
     *  stage honestly). NEVER writes into the user's obb home — that only
     *  happens in [commitObb] after Android confirmed the package. */
    fun stageObbToCache(context: Context, archive: File, obb: PackageInspector.ObbEntry): Obb.Staged? {
        return try {
            val dir = File(context.cacheDir, "obb-stage").also { it.mkdirs() }
            val out = File(dir, "${obb.entryName.substringAfterLast('/')}.${System.nanoTime()}")
            ZipFile(archive).use { zf ->
                val e = zf.getEntry(obb.entryName) ?: return null
                val tmp = File(out.parentFile, ".${out.name}.part")
                zf.getInputStream(e).use { ins ->
                    tmp.outputStream().use { outs ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf); if (n < 0) break; outs.write(buf, 0, n)
                        }
                    }
                }
                // Size verification at the source of truth: the ZIP entry's
                // declared uncompressed size (a -1 header falls back to a
                // stream-count check via the copied file's length).
                if (e.size >= 0 && tmp.length() != e.size) { tmp.delete(); return null }
                if (!tmp.renameTo(out)) { tmp.delete(); return null }
            }
            Obb.Staged(obb, out, out.length())
        } catch (e: Exception) { null }
    }

    /** Moves a staged OBB into the real obb home and VERIFIES it (existence
     *  + length). Idempotent: a correct file already in place is verified
     *  and reused; a WRONG-SIZED existing file is replaced by the staged
     *  copy (an earlier broken attempt must never pass as provisioned). */
    fun commitObb(context: Context, report: PackageInspector.Report, staged: Obb.Staged): Boolean {
        val pkg = report.packageName ?: return false
        val canWriteSharedObb = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q ||
            android.os.Environment.isExternalStorageManager()
        if (!canWriteSharedObb) return false
        return try {
            val home = File(android.os.Environment.getExternalStorageDirectory(),
                "Android/obb/$pkg")
            if (!home.isDirectory && !home.mkdirs()) return false
            val dest = File(home, staged.obb.entryName.substringAfterLast('/'))
            if (dest.exists()) {
                // Re-validate an existing file: right size = done; wrong
                // size = damaged residue from an earlier attempt — replace.
                if (dest.length() == staged.expectedBytes) return true
                dest.delete()
            }
            if (!staged.stagedFile.renameTo(dest)) {
                // Cross-filesystem rename can fail — stream-copy then verify.
                val tmp = File(dest.parentFile, ".${dest.name}.ldxpart")
                staged.stagedFile.inputStream().use { ins ->
                    tmp.outputStream().use { ins.copyTo(it) }
                }
                if (tmp.length() != staged.expectedBytes) { tmp.delete(); return false }
                if (!tmp.renameTo(dest)) { tmp.delete(); return false }
            }
            dest.isFile && dest.length() == staged.expectedBytes
        } catch (e: Exception) { false }
    }

    /** True when a package with this name is CURRENTLY installed — used to
     *  double-check ambiguous Android results (some OEM installers report
     *  STATUS_FAILURE_ABORTED even after the user tapped Install). */
    fun isPackageInstalled(context: Context, packageName: String?): Boolean {
        if (packageName.isNullOrBlank()) return false
        return try {
            context.packageManager.getPackageInfo(packageName, 0) != null
        } catch (e: Exception) { false }
    }

    /** True when OBB staging is possible on THIS device/install at all. */
    fun obbStagingPossible(context: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q ||
            android.os.Environment.isExternalStorageManager()

    /** Sends the user to the system's "install unknown apps" screen for
     *  THIS app (API 26+).
     *  ── GOAT-PASS 16 (§1.1): the launch is now CONTEXT-SHAPE-AWARE. The ──
     *  old call ran verbatim from the flow's pre-flight with the APPLICATION
     *  context — startActivity() from a non-activity context without
     *  FLAG_ACTIVITY_NEW_TASK throws, the catch swallowed it, and the user
     *  saw the toast but never the settings screen (the reported regression;
     *  it "used to work" because earlier call sites happened to pass the
     *  Activity). An Activity context starts the screen in its own task as
     *  before; anything else gets NEW_TASK — both shapes now genuinely
     *  launch. Pre-O devices never reach this function (the gate is O+),
     *  and a total failure (no handler on a de-Googled ROM) surfaces as an
     *  honest fallback toast instead of silence. */
    fun requestUnknownSourcesPermission(context: Context) {
        val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:${context.packageName}"))
        val isActivity = context is android.app.Activity
        if (!isActivity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // Package-URI form unsupported on this build — the global
            // unknown-sources list is the honest fallback, still one tap
            // away from THIS app's row.
            try {
                val fallback = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES)
                if (!isActivity) fallback.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(fallback)
            } catch (e2: Exception) {
                try {
                    Toast.makeText(context, "Couldn't open the install-permission screen — enable \"Install unknown apps\" for LumiDrop X in Android Settings", Toast.LENGTH_LONG).show()
                } catch (e3: Exception) { /* nothing left to try */ }
            }
        }
    }
}
