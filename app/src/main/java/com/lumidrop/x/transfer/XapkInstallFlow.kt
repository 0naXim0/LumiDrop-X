package com.lumidrop.x.transfer

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.lumidrop.x.R
import com.lumidrop.x.ui.LdxButtonStyle
import com.lumidrop.x.ui.LdxDialogAction
import com.lumidrop.x.ui.Widgets
import com.lumidrop.x.ui.applyLdxWindowChrome
import com.lumidrop.x.ui.showLdxDialog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ══ GOAT-PASS 14 (PART 7) — the ONE shared XAPK-installation flow ═══════════
 *
 * Received and the Files sheet both land here: one inspection service, one
 * base resolver, one validator, one icon pipeline, one PackageInstaller
 * orchestration, one result handling. The entry point is [launch] — it
 * inspects the archive off the main thread and presents the SAME install
 * modal TransfersFragment has shown since GP11 (rebuilt here verbatim), so
 * an XAPK received through a transfer and the identical file stored locally
 * (or copied in by another file manager) install through the identical
 * code path. Ordinary `.apk` files NEVER reach this class — callers route
 * them to Android's own system-installer flow, as every pass since GP10
 * has guaranteed.
 *
 * The controller owns no Fragment lifecycle: a plain [CoroutineScope]
 * (supplied by the host activity) replaces viewLifecycleOwner, main-thread
 * hops go through Dispatchers.Main, and every dialog action is guarded so
 * a dead activity can never crash the tail of an install that Android has
 * already committed.
 */
object XapkInstallFlow {

    /** GOAT-PASS 16 (§4.3 audit): ONE live install dialog, process-wide —
     *  the same one-live gate the sheets use. A rapid double-tap on an
     *  archive row used to stack two skeletons (each with its own IO
     *  inspection and its own install button); the gate makes the second
     *  tap a no-op while the first dialog is on screen. Cleared when the
     *  dialog dismisses — including the NOT_A_PACKAGE and permission paths. */
    @Volatile private var liveDialog: android.app.Dialog? = null

    /** The host's scope — the caller passes its activity-scoped launcher so
     *  jobs die with the UI that owns them. */
    fun launch(hostScope: CoroutineScope, context: Context, file: File,
               onFinished: (() -> Unit)? = null) {
        val appCtx = context.applicationContext
        // One live dialog: a second launch while one is showing is a
        // double-tap, not a second intent — ignore it. The dialog's own
        // dismiss listener clears the slot.
        if (liveDialog?.isShowing == true) return
        if (!file.exists()) {
            Toast.makeText(appCtx, "File no longer exists on this device", Toast.LENGTH_SHORT).show()
            return
        }
        // ── GOAT-PASS 16 (§1.1 — the permission screen OPENS, first): the ────
        // old pre-flight asked Android Settings to start from the APPLICATION
        // context without FLAG_ACTIVITY_NEW_TASK — a pattern startActivity()
        // rejects with an exception, which the engine's catch block swallowed
        // whole. The toast showed; the screen never opened. The settings
        // launch now runs through the one canonical helper (Activity context
        // when the host is one, NEW_TASK otherwise), so the user is ALWAYS
        // taken straight to LumiDrop X's own "install unknown apps" page.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
            !appCtx.packageManager.canRequestPackageInstalls()) {
            PendingPermissionResume.remember(hostScope, context, file, onFinished)
            PackageInstallEngine.requestUnknownSourcesPermission(context)
            return
        }
        // ── GOAT-PASS 16 (§2 — the modal is INSTANT): the dialog used to ─────
        // appear only after PackageInspector.inspect() finished — on the
        // 504.9 MB Carnage Wars archive that is 3–4 s of nothing between tap
        // and first pixel. The skeleton now shows IMMEDIATELY (file name +
        // placeholder icon + an honest "Inspecting package…" line, Install
        // disabled), and inspection fills the real metadata in when it
        // lands. Nothing is fabricated while inspection runs — the
        // placeholder says exactly what is happening.
        showInstallDialog(hostScope, context, appCtx, file, null, onFinished)
    }

    /** ── GOAT-PASS 16 (§1.2/§1.3): the permission-RETURN state machine. ─────
     *  A launch blocked on the install-unknown-apps permission registers ONE
     *  pending resume (monotonic op id + the file + the callback) and the
     *  host activity's next RESUME rechecks the REAL permission state:
     *  granted → the exact same install modal reopens automatically (no
     *  re-tap, no duplicate operation — the id proves the operation);
     *  still-denied → nothing auto-launches, no loop, no duplicate Settings
     *  trips (the slot is consumed on first read); replaced → a newer launch
     *  wins and the older resume is discarded. Process death simply drops
     *  the pending op — the user taps again, an honest cold start. */
    object PendingPermissionResume {
        /** One pending permission-blocked install: the monotonic operation id
         *  (§1.3's stable identity — a newer launch replaces an older one,
         *  and a resume can never duplicate a live operation), the archive
         *  file, and the caller's completion callback. Main-thread confined:
         *  remember() and onHostResume() both run on the main thread (the
         *  launch path pre-dialog, and Activity.onResume). */
        private class Pending(val opId: Long, val file: File, val onFinished: (() -> Unit)?)
        private var pending: Pending? = null
        private val nextId = java.util.concurrent.atomic.AtomicLong(0)

        fun remember(hostScope: CoroutineScope, context: Context, file: File,
                     onFinished: (() -> Unit)?) {
            // A newer attempt always replaces the older pending one — the
            // user changed their mind about WHICH archive to install; the
            // stale resume must not resurrect the previous file.
            pending = Pending(nextId.incrementAndGet(), file, onFinished)
        }

        /** The pending operation's id — exposed for tests and for callers
         *  that want to prove no duplicate was created. */
        fun pendingOpId(): Long = pending?.opId ?: -1L

        /** Called from the host activity's onResume: rechecks the REAL
         *  permission state (never assumes a grant) and resumes the pending
         *  install exactly once when it is now granted. */
        fun onHostResume(hostScope: CoroutineScope, context: Context) {
            val p = pending ?: return
            pending = null   // consumed on first read — no repeat, no loop
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) {
                // Pre-O never gates on this permission at all — a pending
                // entry here is a lifecycle oddity, not a user flow.
                return
            }
            val granted = try {
                context.packageManager.canRequestPackageInstalls()
            } catch (e: Exception) { false }
            if (granted) {
                if (p.file.exists()) {
                    Toast.makeText(context.applicationContext,
                        "Permission granted — resuming installation", Toast.LENGTH_SHORT).show()
                    launch(hostScope, context, p.file, p.onFinished)
                }
            }
            // Still denied / user returned without changing anything: quiet.
            // The user retries normally — no false failure, no loop.
        }
    }

    // ── the modal (the GP11/GP13 recipe, relocated verbatim) ─────────────────

    private fun dp(ctx: Context, v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    private fun dpf(ctx: Context, v: Float): Float = v * ctx.resources.displayMetrics.density
    private fun col(ctx: Context, res: Int): Int = ContextCompat.getColor(ctx, res)

    private fun showInstallDialog(hostScope: CoroutineScope, context: Context, appCtx: Context,
                                  file: File, report: PackageInspector.Report?,
                                  onFinished: (() -> Unit)?) {
        // ── GOAT-PASS 16 (§2): report == null means "inspect now, skeleton ──
        // first". The modal appears IMMEDIATELY with the file name, a
        // placeholder icon and an honest "Inspecting package…" line; the
        // real metadata fills the SAME views when the IO inspection lands
        // (op-id guarded — a stale inspection can never overwrite a newer
        // dialog). A non-null report (the cached or resumed path) skips the
        // skeleton and paints the full card directly.
        if (report != null && report.kind == PackageInspector.Kind.NOT_A_PACKAGE) {
            showLdxDialog(
                context = context,
                title = "Unsupported package",
                message = report.issues.firstOrNull() ?: "This file is not a supported Android package",
                iconRes = R.drawable.ic_file_xapk, iconTintRes = R.color.orange,
                actions = listOf(LdxDialogAction("Close", LdxButtonStyle.GHOST)))
            onFinished?.invoke()
            return
        }
        val dlg = android.app.Dialog(context, android.R.style.Theme_Translucent_NoTitleBar)
        dlg.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // ── GOAT-PASS 15 (§5): the same window recipe EVERY LdxDialog uses —
        // app system-bar chrome (never black bars), a real dim scrim behind
        // the card (0.55, the app-wide modal darkness), and a FULL-SCREEN
        // window so the backdrop actually receives the outside tap instead
        // of letting it fall through to the sheet underneath.
        dlg.applyLdxWindowChrome()
        dlg.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dlg.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dlg.window?.setDimAmount(0.55f)
        // §4.3's one-live gate: this dialog owns the slot until it dismisses.
        liveDialog = dlg
        dlg.setOnDismissListener { if (liveDialog === dlg) liveDialog = null }
        // ── GOAT-PASS 16 (§2): the view is built SYNCHRONOUSLY (pure view ──
        // construction, zero I/O) — skeleton when report == null, full card
        // otherwise — and dlg.show() runs BEFORE any inspection starts. The
        // first visible frame costs view-build time only, never
        // archive-scan time; the 3–4 s Carnage Wars gap between tap and
        // modal is gone.
        val view = buildDialogView(hostScope, context, appCtx, file, report, dlg, onFinished)
        dlg.setContentView(view)
        dlg.show()
        if (report == null) {
            // The inspection (the only expensive part) starts AFTER the modal
            // is on screen, on IO. The op-id guard makes a stale inspection
            // harmless: the user may dismiss this dialog and tap a DIFFERENT
            // archive — the older fill can never touch the newer dialog.
            val opId = ++dialogOpSeq
            hostScope.launch(Dispatchers.IO) {
                PackageInspector.packageManagerRef = { try { appCtx.packageManager } catch (e: Exception) { null } }
                val inspected = inspectCached(file)
                kotlinx.coroutines.withContext(Dispatchers.Main) {
                    if (opId != dialogOpSeq) return@withContext   // superseded by a newer dialog
                    if (!dlg.isShowing) return@withContext        // dismissed mid-inspection
                    view.fill(inspected)
                }
            }
        }
    }

    /** §2's staleness guard: the id of the MOST RECENT dialog built. An
     *  in-flight inspection for an older dialog can never fill a newer one. */
    private var dialogOpSeq = 0L

    /** ── §2.4: the inspection cache. Key = path|mtime|length — a replaced ──
     *  archive (same name, different bytes) misses by construction; an
     *  unchanged archive (the second tap, the Files row after the Received
     *  row) hits without re-scanning a single byte. Bounded access-order
     *  LRU (16) — no unbounded retention. */
    private val inspectCache = object : LinkedHashMap<String, PackageInspector.Report>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PackageInspector.Report>): Boolean = size > 16
    }
    private fun inspectCached(file: File): PackageInspector.Report {
        val key = try {
            "${file.absolutePath}|${file.lastModified()}|${file.length()}"
        } catch (e: Exception) { return PackageInspector.inspect(file) }
        synchronized(inspectCache) { inspectCache[key] }?.let { return it }
        val r = PackageInspector.inspect(file)
        synchronized(inspectCache) { inspectCache[key] = r }
        return r
    }

    /** The report → the modal's info text (the exact GP13/GP14 line set,
     *  extracted verbatim so the skeleton fill renders IDENTICALLY to the
     *  old pre-computed dialog). */
    internal fun buildInfoLines(report: PackageInspector.Report, appCtx: Context): String {
        fun s(v: Any?): String = v?.toString() ?: "Unknown"
        val kindLabel = when (report.kind) {
            PackageInspector.Kind.APK -> "APK package"
            PackageInspector.Kind.XAPK -> "XAPK bundle"
            PackageInspector.Kind.APKM -> "APKM bundle"
            else -> "Split-APK bundle"
        }
        val lines = StringBuilder()
        lines.append("Type: ").append(kindLabel)
        if (report.appLabel != null) lines.append("\nApp: ").append(report.appLabel)
        if (report.packageName != null) lines.append("\nPackage: ").append(report.packageName)
        lines.append("\nVersion: ").append(s(report.versionName)).append(if (report.versionCode != null) " (${report.versionCode})" else "")
        if (report.minSdk != null) lines.append("\nNeeds Android ").append(androidVersionLabel(report.minSdk)).append("+")
        lines.append("\nSize: ").append(fmtBytes(report.totalApkBytes))
        if (report.baseApk != null) {
            lines.append("\nMain APK: ").append(report.baseApk.entryName.substringAfterLast('/'))
            if (!report.baseSelectionReason.isNullOrBlank() && report.baseSelectionReason != "named base.apk")
                lines.append(" — ").append(report.baseSelectionReason)
            lines.append(if (report.splitCount > 0)
                "\nComponents: main APK + ${report.splitCount} split${if (report.splitCount != 1) "s" else ""}" +
                    (if (report.splits.any { it.splitType == "asset" }) " (includes asset data)" else "")
                else "\nComponents: single APK")
        } else {
            lines.append("\nComponents: ${report.splitCount} APK part${if (report.splitCount != 1) "s" else ""} — no main application APK identified")
        }
        if (report.obbs.isNotEmpty()) {
            val obbNote = if (PackageInstallEngine.obbStagingPossible(appCtx))
                " — will be installed after the app" else " — cannot be placed on this Android version"
            lines.append("\nExpansion data: ").append(report.obbs.size).append(" file").append(if (report.obbs.size != 1) "s" else "").append(obbNote)
        }
        report.issues.forEach { lines.append("\n⚠ ").append(it) }
        return lines.toString()
    }

    /**
     * ── GOAT-PASS 15 (§3): the user-facing Android version NAME for an API ──
     * level — "6.0" for 23, "13" for 33 — because "Android 23+" reads as a
     * version number that does not exist. The mapping is the public
     * platform table (API 21 through 36); anything outside it (a future or
     * forged value) falls back to the HONEST "API N" form rather than a
     * wrong guess. Internal compatibility checks keep using the raw API
     * level — only this display conversion changes. Public + pure so the
     * unit suite can pin every row.
     */
    fun androidVersionLabel(api: Int): String = when (api) {
        21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"; 24 -> "7.0"; 25 -> "7.1"
        26 -> "8.0"; 27 -> "8.1"; 28 -> "9"; 29 -> "10"; 30 -> "11"
        31 -> "12"; 32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"; 36 -> "16"
        else -> "API $api"   // unknown/future — honest, never a wrong name
    }

    private fun fmtBytes(b: Long): String {
        if (b < 1024) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(java.util.Locale.US, "%.1f KB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(java.util.Locale.US, "%.1f MB", mb)
        return String.format(java.util.Locale.US, "%.1f GB", mb / 1024.0)
    }

    /** The dialog view: a FrameLayout subtype carrying [fill] — the GP16 §2
     *  progressive contract. Built SYNCHRONOUSLY (no I/O) with either the
     *  full card (report != null) or the skeleton, then filled in place. */
    private class InstallDialogView(ctx: Context): FrameLayout(ctx) {
        lateinit var fill: (PackageInspector.Report) -> Unit
    }

    private fun buildDialogView(hostScope: CoroutineScope, context: Context, appCtx: Context,
                                file: File, report: PackageInspector.Report?,
                                dlg: android.app.Dialog, onFinished: (() -> Unit)?): InstallDialogView {
        val ctx = context
        val body = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // The LIVE report the install button reads — set at build time for a
        // pre-inspected report, replaced by fill() when the skeleton fills.
        var reportRef: PackageInspector.Report? = report
        val bodyView = InstallDialogView(ctx)
        // GP13: the REAL application icon — the shared resolution pipeline.
        val headerRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val iconView = ImageView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 40)).also { it.rightMargin = dp(ctx, 10) }
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageDrawable(com.lumidrop.x.ui.FormatIcons.icon(ctx, file.extension))
        }
        headerRow.addView(iconView)
        val titleView = TextView(ctx).apply {
            text = report?.appLabel ?: file.name; textSize = 18f
            setTextColor(col(ctx, R.color.text_primary))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.gravity = Gravity.CENTER_VERTICAL }
        }
        headerRow.addView(titleView)
        body.addView(headerRow)
        // GP16 §2.2: the icon extraction starts ONLY when a real report
        // exists (it needs one); the skeleton shows the format placeholder
        // until then — never a fabricated icon.
        fun startIconExtraction(r: PackageInspector.Report) {
            hostScope.launch(Dispatchers.IO) {
                val bmp = PackageInspector.extractIcon(ctx, file, r)
                    ?: com.lumidrop.x.media.ThumbnailShared.packageArchiveIcon(ctx, file.absolutePath, dp(ctx, 40))
                if (bmp != null) withContext(Dispatchers.Main) {
                    if (dlg.isShowing) iconView.setImageBitmap(bmp)
                }
            }
        }
        if (report != null) startIconExtraction(report)
        val infoView = TextView(ctx).apply {
            // §2.2: while inspection runs, the line says EXACTLY that — no
            // fabricated package/version/component data. The fixed-height
            // reserve (4 lines) absorbs the typical real report without a
            // layout jump when it lands.
            text = if (report != null) buildInfoLines(report, appCtx)
                   else "Inspecting package…"
            textSize = 12.5f
            setTextColor(col(ctx, R.color.text_secondary))
            setLineSpacing(dpf(ctx, 3f), 1f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(ctx, 10) }
            if (report == null) minHeight = dp(ctx, 64)
        }
        body.addView(infoView)
        val progressLabel = TextView(ctx).apply {
            textSize = 11.5f; setTextColor(col(ctx, R.color.text_muted)); visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(ctx, 10) }
        }
        val bar = android.widget.ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = 0; visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(ctx, 8) }
        }
        body.addView(progressLabel); body.addView(bar)
        // §5.3's one-flag channel between the install click and the backdrop.
        val installInProgressHolder = object { var value = false }
        val installBtn = TextView(ctx).apply {
            text = if (report?.isInstallable == true) "Install" else if (report != null) "Cannot install" else "Inspecting…"
            textSize = 14f; gravity = Gravity.CENTER
            setTextColor(col(ctx, R.color.white))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = Widgets.rippleRect(ctx, col(ctx, if (report?.isInstallable == true) R.color.accent else R.color.text_muted), radiusDp = 12)
            setPadding(dp(ctx, 18), dp(ctx, 11), dp(ctx, 18), dp(ctx, 11))
            isClickable = report?.isInstallable == true; isFocusable = report?.isInstallable == true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(ctx, 16) }
        }
        installBtn.setOnClickListener {
            val report = reportRef ?: return@setOnClickListener   // skeleton: not inspectable yet
            if (!report.isInstallable) return@setOnClickListener
            installInProgressHolder.value = true   // §5.3: the session now owns this modal
            installBtn.isClickable = false
            installBtn.text = "Preparing…"
            progressLabel.visibility = View.VISIBLE; bar.visibility = View.VISIBLE
            progressLabel.text = "Reading package components…"
            // GP12: one operation, one finalization — AtomicBoolean.
            val opFinalized = AtomicBoolean(false)
            val opLabel = report.appLabel ?: file.nameWithoutExtension
            fun finalize(success: Boolean, title: String, message: String,
                         obbNote: String?, offerOpen: Boolean) {
                if (!opFinalized.compareAndSet(false, true)) return
                try { dlg.dismiss() } catch (e: Exception) { /* already gone */ }
                if (success) {
                    val bodyView = TextView(appCtx).apply {
                        text = buildString {
                            append(opLabel); append(" was installed successfully.")
                            obbNote?.let { append("\n\n"); append(it) }
                        }
                        textSize = 13.5f; setTextColor(col(appCtx, R.color.text_secondary))
                        setPadding(0, dp(appCtx, 2), 0, dp(appCtx, 2))
                    }
                    val openAction = if (offerOpen && report.packageName != null)
                        LdxDialogAction("Open", LdxButtonStyle.PRIMARY, onClick = {
                            try {
                                appCtx.startActivity(appCtx.packageManager
                                    .getLaunchIntentForPackage(report.packageName!!)
                                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            } catch (e: Exception) { /* not launchable */ }
                            onFinished?.invoke()
                        }) else null
                    showLdxDialog(
                        context = ctx,
                        title = "Installation Successful",
                        iconRes = R.drawable.ic_file_xapk, iconTintRes = R.color.green,
                        bodyView = bodyView,
                        actions = listOfNotNull(
                            openAction,
                            LdxDialogAction("Close", LdxButtonStyle.GHOST, onClick = { onFinished?.invoke() })))
                } else {
                    showLdxDialog(
                        context = ctx,
                        title = title, message = message,
                        iconRes = R.drawable.ic_file_xapk, iconTintRes = R.color.red_stop,
                        actions = listOf(LdxDialogAction("Close", LdxButtonStyle.GHOST, onClick = { onFinished?.invoke() })))
                }
            }
            hostScope.launch(Dispatchers.IO) {
                // GP12 §1.4: OBB staged & size-verified in our own cache first.
                var stagedObbs: List<PackageInstallEngine.Obb.Staged> = emptyList()
                var obbStageFailed: String? = null
                if (report.obbs.isNotEmpty()) {
                    withContext(Dispatchers.Main) { progressLabel.text = "Preparing expansion data…" }
                    stagedObbs = report.obbs.mapNotNull { obb ->
                        PackageInstallEngine.stageObbToCache(appCtx, file, obb)
                    }
                    if (stagedObbs.size != report.obbs.size)
                        obbStageFailed = "expansion data could not be read from the archive"
                }
                var lastPct = -1
                val outcome = PackageInstallEngine.install(ctx, file, report) { w, tot ->
                    val pct = ((w * 1000f) / tot).toInt().coerceIn(0, 1000)
                    if (pct != lastPct) { lastPct = pct
                        hostScope.launch(Dispatchers.Main) {
                            progressLabel.text = "Writing package to installer… ${(pct / 10f).toInt()}%"
                            bar.progress = pct
                        } }
                }
                withContext(Dispatchers.Main) {
                    when (outcome) {
                        is PackageInstallEngine.StartOutcome.Rejected ->
                            finalize(false, "Cannot install", outcome.reason, null, false)
                        is PackageInstallEngine.StartOutcome.NeedsUnknownSourcesPermission -> {
                            try { dlg.dismiss() } catch (e: Exception) { /* gone */ }
                            // GP16 §1.2: BOTH permission sites resume. The mid-
                            // install gate registers the SAME pending resume
                            // the pre-flight uses — grant it in Settings and
                            // the modal reopens with the archive already
                            // inspected (the report is right here); the user
                            // just taps Install again inside it. No manual
                            // Settings hunt, no repeated trips.
                            PendingPermissionResume.remember(hostScope, ctx, file, onFinished)
                            Toast.makeText(appCtx, "Allow LumiDrop X to install apps, then tap Install again", Toast.LENGTH_LONG).show()
                            PackageInstallEngine.requestUnknownSourcesPermission(ctx)
                        }
                        is PackageInstallEngine.StartOutcome.NeedsConfirmation -> {
                            progressLabel.text = "Waiting for Android's confirmation…"
                            val watch = PackageInstallEngine.watch(appCtx, outcome.tag) { ok, msg ->
                                if (opFinalized.get()) return@watch
                                // GP12 §2.2: OEM ABORTED cross-check.
                                val installedNow = PackageInstallEngine.isPackageInstalled(appCtx, report.packageName)
                                val actuallyOk = ok || (!ok && installedNow)
                                if (!actuallyOk) {
                                    // ── GOAT-PASS 15 (§4): USER cancellation is QUIET — ──
                                    // the user just told Android "no"; a second
                                    // "Installation Cancelled" popup on top of
                                    // that answers a question nobody asked.
                                    // Dismiss the flow, clean up, done. Genuine
                                    // failures (a real installer STATUS_FAILURE,
                                    // a rejected session, missing expansion
                                    // data) still get their explanation modal.
                                    if (msg == "Installation cancelled") {
                                        try { dlg.dismiss() } catch (e: Exception) { /* gone */ }
                                        onFinished?.invoke()
                                        opFinalized.set(true)   // idempotence: no late callback double-finishes
                                    } else {
                                        finalize(false, "Installation failed", msg, null, false)
                                    }
                                    return@watch
                                }
                                hostScope.launch(Dispatchers.IO) {
                                    var obbNote: String? = null
                                    var obbAllOk = true
                                    if (report.obbs.isNotEmpty()) {
                                        if (!PackageInstallEngine.obbStagingPossible(appCtx)) {
                                            obbAllOk = false
                                            obbNote = "This Android version does not allow LumiDrop X to place expansion data — copy the OBB files manually if the app needs them"
                                        } else if (obbStageFailed != null) {
                                            obbAllOk = false
                                            obbNote = "The APK installed, but its expansion data could not be read from the archive"
                                        } else {
                                            val okFiles = stagedObbs.map { st ->
                                                PackageInstallEngine.commitObb(appCtx, report, st) }
                                            obbAllOk = okFiles.all { it }
                                            obbNote = if (obbAllOk)
                                                "Expansion data installed and verified (${report.obbs.size} file${if (report.obbs.size != 1) "s" else ""})"
                                            else "The APK installed, but ${okFiles.count { !it }} of ${report.obbs.size} expansion file(s) could not be placed — retry Install to finish provisioning"
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        if (obbAllOk)
                                            finalize(true, "", "$opLabel was installed successfully.", obbNote, true)
                                        else
                                            finalize(false, "Installed — expansion data incomplete",
                                                "$opLabel's APK installed, but its required expansion data could not be provisioned.",
                                                obbNote, false)
                                    }
                                }
                            }
                            watch.onPendingConfirmation = { confirmIntent ->
                                try { ctx.startActivity(confirmIntent) } catch (e: Exception) {
                                    finalize(false, "Cannot install", "Couldn't open Android's installer confirmation", null, false)
                                    watch.close()
                                }
                            }
                        }
                    }
                }
            }
        }
        body.addView(installBtn)
        val closeBtn = TextView(ctx).apply {
            text = "Close"; textSize = 13f; gravity = Gravity.CENTER
            setTextColor(col(ctx, R.color.text_secondary))
            setPadding(dp(ctx, 18), dp(ctx, 10), dp(ctx, 18), dp(ctx, 10)); isClickable = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(ctx, 4) }
            setOnClickListener { dlg.dismiss(); onFinished?.invoke() }
        }
        body.addView(closeBtn)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_dialog)
            setPadding(dp(ctx, 22), dp(ctx, 22), dp(ctx, 22), dp(ctx, 20))
            elevation = dp(ctx, 8).toFloat()
            isClickable = true
            addView(body, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val outerPadPx = dp(ctx, 24) * 2
        val maxCardPx = dp(ctx, 420)
        val availablePx = ctx.resources.displayMetrics.widthPixels - outerPadPx
        // ── GOAT-PASS 15 (§5.2): the backdrop is the SAME outside-tap ────────
        // affordance every LdxDialog has — tap the dimmed area, the modal
        // closes. It is armed only while no installation operation is in
        // flight (§5.3): after Install is tapped, an outside tap is swallowed
        // WITHOUT dismissing, so an active PackageInstaller session is never
        // orphaned mid-write and the pending Android confirmation (or its
        // result dialog) always has a live surface to land on.
        val backdrop = FrameLayout(ctx).apply {
            isClickable = true   // always swallows the tap — never leaks it to the sheet below
            setOnClickListener {
                if (!installInProgressHolder.value) { try { dlg.dismiss() } catch (e: Exception) { /* gone */ } }
            }
        }
        backdrop.addView(card, FrameLayout.LayoutParams(
            maxCardPx.coerceAtMost(availablePx).coerceAtLeast(dp(ctx, 280)),
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        // ── GOAT-PASS 16 (§2): the progressive fill. One function, called ────
        // exactly once per dialog with the REAL inspection result: real info
        // lines, real title, icon extraction armed, and the Install button
        // enabled precisely when the validated report says installable. A
        // NOT_A_PACKAGE result converts the SAME modal into the honest
        // unsupported-package state (no second popup on top of the skeleton,
        // no flash). The reserved min-height is released so the card settles
        // to its natural size without a jump.
        bodyView.fill = { r ->
            reportRef = r
            if (r.kind == PackageInspector.Kind.NOT_A_PACKAGE) {
                titleView.text = "Unsupported package"
                infoView.text = r.issues.firstOrNull() ?: "This file is not a supported Android package"
                infoView.setTextColor(col(ctx, R.color.text_secondary))
                infoView.minHeight = 0
                installBtn.text = "Close"
                installBtn.background = Widgets.rippleRect(ctx, col(ctx, R.color.text_muted), radiusDp = 12)
                installBtn.isClickable = true; installBtn.isFocusable = true
                installBtn.setOnClickListener { try { dlg.dismiss() } catch (e: Exception) { /* gone */ }; onFinished?.invoke() }
            } else {
                titleView.text = r.appLabel ?: file.name
                infoView.text = buildInfoLines(r, appCtx)
                infoView.minHeight = 0
                installBtn.text = if (r.isInstallable) "Install" else "Cannot install"
                installBtn.background = Widgets.rippleRect(ctx, col(ctx, if (r.isInstallable) R.color.accent else R.color.text_muted), radiusDp = 12)
                installBtn.isClickable = r.isInstallable; installBtn.isFocusable = r.isInstallable
                startIconExtraction(r)
            }
        }
        bodyView.addView(backdrop, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // The arm happens inside the install click listener (see above): the
        // assignment is captured through this small holder so the click
        // listener compiled before the backdrop can still reach it.
        return bodyView
    }

    /** ── PART 7: the ROUTING PREDICATE both surfaces share. An ordinary ──
     *  `.apk` is NOT an archive-install: it keeps Android's own system
     *  installer flow. Only the multi-component container formats route
     *  into this shared flow. */
    fun isArchiveInstallFormat(name: String?): Boolean {
        val ext = name?.substringAfterLast('.', "")?.lowercase() ?: return false
        return ext == "xapk" || ext == "apkm" || ext == "apks"
    }
}
