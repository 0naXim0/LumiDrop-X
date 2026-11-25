package com.lumidrop.x.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.lumidrop.x.R
import com.lumidrop.x.ui.Widgets
import com.lumidrop.x.ui.dpf
import com.lumidrop.x.update.ReleaseModel.Release
import com.lumidrop.x.update.UpdateManager.Phase
import com.lumidrop.x.update.UpdateManager.State

/**
 * ══ PASS 21 §4 — the redesigned UPDATES section ═══════════════════════════
 *
 * One cohesive update component inside the (unchanged, Pass-20) About card:
 *
 *   UPDATES ────────────────────────────  [ ● AUTO CHECK ]   ← header row
 *   status line (state-specific, honest)                     ← quiet text
 *   [ ••••• progress •••••  2.1 MB / 3.7 MB ]                ← only while downloading
 *   [ PRIMARY ACTION ]                                       ← ONE per state
 *
 * What changed from Pass 20 and why (each traceable to the brief):
 *  - §4.2/§4.3: the UPDATES heading is a proper header row; the compact
 *    AUTO CHECK pill sits at its RIGHT edge — aligned, small, never a
 *    separate large row. It is a custom TextView pill, NOT a SwitchCompat:
 *    the platform switch's sky-blue thumb/track was the off-palette color
 *    the brief called out (§0.6/§4.4), and a full-width switch row was the
 *    space hog (§4.3 "Do not use an oversized switch").
 *  - §4.4: colors are the established LumiDrop X system — the app's theme
 *    accent (#9172F6 purple family) for the enabled pill / primary action,
 *    theme surfaces for the neutral pill, themed text colors everywhere.
 *    The Pass-20 hero blues (#3B82F6/#60A5FA) are GONE from this section.
 *  - §3/§4.6: ONE primary action. Its label and behavior follow the state:
 *    up-to-date → Check for updates; available → Download; downloading →
 *    Cancel (the only secondary control that state needs); verifying →
 *    disabled "Verifying…"; permission-required → Grant permission (opens
 *    the OS page, returns to the flow); ready → Install; failed → Retry.
 *    The Pass-20 duplicate (an extra action pill inside the release body)
 *    is REMOVED (§3.1).
 *  - §2.1: the pill/action responds to the FIRST tap by flipping the UI
 *    into its state presentation instantly — the state listener re-renders
 *    on every UpdateManager state change (download progress included), so
 *    feedback never waits for the next network callback.
 *  - §4.6/§4.7: the release body (version · size · notes) shows only when
 *    an offer/ready state is active; progress shows only while downloading;
 *    no layout jumps (the areas keep their slots, visibility flips).
 */
object AboutUpdateSection {

    // ── PASS 23 §7: the state→action contract, as ONE pure function ────────
    // (single source of truth for what the primary button IS and DOES —
    // render() paints it, onPrimary() executes it, and the JVM battery
    // pins it, including the Pass-23 fix: every install-shaped state
    // retries the INSTALLATION with the already-downloaded APK, never a
    // re-download).

    /** What the ONE primary action does for a phase. */
    enum class ActionKind { CHECK, DOWNLOAD, CANCEL_DOWNLOAD, INSTALL, GRANT_PERMISSION, NONE }

    data class PrimaryAction(val kind: ActionKind, val label: String, val enabled: Boolean)

    fun primaryAction(phase: Phase, releaseKnown: Boolean): PrimaryAction = when (phase) {
        Phase.CHECKING, Phase.VERIFYING, Phase.DOWNLOAD_COMPLETE ->
            PrimaryAction(ActionKind.NONE, "Working\u2026", false)
        Phase.FINALIZING -> PrimaryAction(ActionKind.NONE, "Finishing\u2026", false)
        Phase.UP_TO_DATE, Phase.IDLE, Phase.UPDATE_INSTALLED, Phase.VERIFIED ->
            PrimaryAction(ActionKind.CHECK, "Check for Updates", true)
        Phase.CHECK_FAILED -> PrimaryAction(ActionKind.CHECK, "Check for Updates", true)
        Phase.UPDATE_AVAILABLE -> PrimaryAction(ActionKind.DOWNLOAD, "Download Update", true)
        Phase.DOWNLOADING -> PrimaryAction(ActionKind.CANCEL_DOWNLOAD, "Cancel Download", true)
        Phase.DOWNLOAD_FAILED, Phase.VERIFY_FAILED ->
            PrimaryAction(ActionKind.DOWNLOAD, "Retry Download", releaseKnown)
        Phase.READY_TO_INSTALL -> PrimaryAction(ActionKind.INSTALL, "Install Update", true)
        Phase.INSTALL_PERMISSION_REQUIRED ->
            PrimaryAction(ActionKind.GRANT_PERMISSION, "Grant Permission", true)
        Phase.INSTALL_PENDING -> PrimaryAction(ActionKind.NONE, "Waiting for Installer\u2026", false)
        // §7 critical rule: a downloaded APK keeps offering INSTALL — the
        // Pass-22 wiring made these two re-download the same release.
        Phase.INSTALL_CANCELLED -> PrimaryAction(ActionKind.INSTALL, "Retry Install", releaseKnown)
        Phase.INSTALL_FAILED -> PrimaryAction(ActionKind.INSTALL, "Retry Install", true)
    }

    fun build(
        ctx: Context,
        dp: (Int) -> Int,
        col: (Int) -> Int,
        mount: LinearLayout,
        onCheckNow: (manual: Boolean) -> Unit,
        onDownload: (Release) -> Unit,
        onCancelDownload: () -> Unit,
        onInstall: (Release) -> Unit,
        onResumeFlow: () -> Unit
    ): Section = Section(ctx, dp, col, mount, onCheckNow, onDownload, onCancelDownload, onInstall, onResumeFlow)

    class Section(
        private val ctx: Context,
        private val dp: (Int) -> Int,
        private val col: (Int) -> Int,
        private val mount: LinearLayout,
        private val onCheckNow: (Boolean) -> Unit,
        private val onDownload: (Release) -> Unit,
        private val onCancelDownload: () -> Unit,
        private val onInstall: (Release) -> Unit,
        private val onResumeFlow: () -> Unit
    ) {
        private val main = Handler(Looper.getMainLooper())
        private lateinit var card: LinearLayout
        private lateinit var statusLine: TextView
        private lateinit var lastCheckedLine: TextView
        private lateinit var autoPill: TextView
        private lateinit var actionBtn: TextView
        private lateinit var progressBar: ProgressBar
        private lateinit var progressText: TextView
        private lateinit var releaseBody: LinearLayout

        // the ONE state listener — installed on attach, removed on detach;
        // renders on the main thread whatever the worker threads publish.
        private val stateListener: (State) -> Unit = { st -> main.post { render(st) } }

        fun attach() {
            buildCard()
            UpdateManager.addListener(stateListener)
        }

        fun detach() { UpdateManager.removeListener(stateListener) }

        // ── preference access: one source of truth, persisted ──────────────
        private fun autoCheckEnabled(): Boolean =
            ReleaseModel.prefs(ctx).getBoolean("auto_check_enabled", true)
        private fun setAutoCheckEnabled(on: Boolean) {
            ReleaseModel.prefs(ctx).edit().putBoolean("auto_check_enabled", on).apply()
        }

        @SuppressLint("SetTextI18n")
        private fun buildCard() {
            card = Widgets.sectionCard(ctx, paddingDp = 20)

            // ══ Identity group (Pass 20 §6 — preserved exactly) ═══════════
            val identityRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val logoTile = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                val bgLayer = try {
                    ctx.getDrawable(R.drawable.ic_launcher_background)?.constantState?.newDrawable()?.mutate()
                } catch (e: Exception) { null }
                val fgLayer = try {
                    ctx.getDrawable(R.drawable.ic_launcher_foreground)?.constantState?.newDrawable()?.mutate()
                } catch (e: Exception) { null }
                if (bgLayer != null) addView(View(ctx).apply {
                    background = bgLayer
                    layoutParams = FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                })
                if (fgLayer != null) addView(ImageView(ctx).apply {
                    setImageDrawable(fgLayer)
                    layoutParams = FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                })
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, ctx.dpf(18f))
                    }
                }
            }
            identityRow.addView(logoTile)
            val idTextCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .also { it.marginStart = dp(16) }
            }
            idTextCol.addView(TextView(ctx).apply {
                text = ctx.getString(R.string.app_name)
                textSize = 20f; setTextColor(col(R.color.text_primary))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD)
                letterSpacing = 0.01f
            })
            val ver = UpdateManager.installedVersionName(ctx)
            idTextCol.addView(TextView(ctx).apply {
                text = if (ver.isNotEmpty()) "Version $ver" else ""
                textSize = 12f; setTextColor(col(R.color.text_secondary)); letterSpacing = 0.03f
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(3) }
            })
            identityRow.addView(idTextCol)
            card.addView(identityRow)
            card.addView(TextView(ctx).apply {
                text = "Offline file sharing across Android, iOS and any browser \u2014 fast, private, no internet needed."
                textSize = 12f; setTextColor(col(R.color.text_muted)); setLineSpacing(dp2f(), 1f)
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(14) }
            })
            hairline()

            // ══ Project group (Pass 20 §6 — preserved exactly) ════════════
            card.addView(groupLabel("Project"))
            card.addView(linkRow("github.com/0naXim0/LumiDrop-X", "https://github.com/0naXim0/LumiDrop-X"))
            card.addView(TextView(ctx).apply {
                text = "Created by Nazim Shah"
                textSize = 12.5f; setTextColor(col(R.color.text_secondary))
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(8) }
            })
            hairline()

            // ══ UPDATES header row — §4.2/§4.3 ═════════════════════════════
            // heading on the left, compact AUTO CHECK pill on the right,
            // ONE balanced row; collapses gracefully on narrow screens
            // (the pill never wraps under the heading — it shrink-wraps its
            // short label, the heading takes the remaining width).
            val headerRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            headerRow.addView(TextView(ctx).apply {
                text = "UPDATES"
                textSize = 11f; setTextColor(col(R.color.text_muted))
                typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.1f
                layoutParams = LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            autoPill = TextView(ctx).apply {
                textSize = 10f; letterSpacing = 0.06f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                isClickable = true; isFocusable = true
                setPadding(dp(11), dp(5), dp(11), dp(5))
                // §4.3: tap flips the preference IMMEDIATELY; the visual
                // state is re-derived from the SAVED preference, so the two
                // can never disagree (§10). Enabling also paces a check via
                // the same 24h/shouldAutoCheck gate the resume path uses.
                setOnClickListener {
                    val now = !autoCheckEnabled()
                    setAutoCheckEnabled(now)
                    paintAutoPill()
                    if (now) {
                        val prefs = ReleaseModel.prefs(ctx)
                        if (ReleaseModel.shouldAutoCheck(true,
                                prefs.getLong("last_check_at", 0L),
                                System.currentTimeMillis(),
                                UpdateManager.isOperationInFlight())) {
                            onCheckNow(true)
                        }
                    }
                }
            }
            headerRow.addView(autoPill)
            card.addView(headerRow)
            paintAutoPill()

            // ══ status area (§4.5 — quiet, aligned, state-specific) ═══════
            statusLine = TextView(ctx).apply {
                textSize = 12.5f; setTextColor(col(R.color.text_secondary)); setLineSpacing(dp2f(), 1f)
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10) }
            }
            card.addView(statusLine)

            // release body — nested detail area, visible only for offers
            releaseBody = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(13), dp(11), dp(13), dp(11))
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = ctx.dpf(13f); setColor(col(R.color.bg_secondary))
                }
            }
            card.addView(releaseBody)

            // progress — §2.2/§4.5: only while downloading; slot kept
            // reserved so appearing/disappearing never jumps the layout
            progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false; max = 100; progress = 0
                visibility = View.GONE
                progressTintList = android.content.res.ColorStateList.valueOf(col(R.color.accent))
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).also { it.topMargin = dp(10) }
            }
            card.addView(progressBar)
            progressText = TextView(ctx).apply {
                textSize = 10.5f; setTextColor(col(R.color.text_muted)); visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(4) }
            }
            card.addView(progressText)

            // ONE primary action (§3.2) — full-width, state-adaptive
            actionBtn = TextView(ctx).apply {
                textSize = 13.5f; gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                isClickable = true; isFocusable = true
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener { onPrimary() }
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(12) }
            }
            card.addView(actionBtn)

            lastCheckedLine = TextView(ctx).apply {
                textSize = 10.5f; setTextColor(col(R.color.text_muted))
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(8) }
            }
            card.addView(lastCheckedLine)

            mount.addView(card)
        }

        /** §4.3/§4.4: the pill's two visual states, derived ONLY from the
         *  saved preference — enabled = accent-tinted highlight with a dot;
         *  disabled = quiet neutral surface. Theme-aware via R.color, never
         *  a hardcoded foreign hue. */
        private fun paintAutoPill() {
            val on = autoCheckEnabled()
            val dot = if (on) "\u25CF " else "\u25CB "
            autoPill.text = dot + "AUTO CHECK"
            autoPill.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ctx.dpf(999f)
                if (on) {
                    setColor(col(R.color.accent_20))
                    setStroke(dp(1), col(R.color.accent))
                } else {
                    setColor(col(R.color.bg_secondary))
                    setStroke(dp(1), col(R.color.border_subtle))
                }
            }
            autoPill.setTextColor(if (on) col(R.color.accent_light) else col(R.color.text_muted))
        }

        private fun hairline() {
            card.addView(View(ctx).apply {
                setBackgroundColor(col(R.color.border_subtle))
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1).also {
                    it.topMargin = dp(16); it.bottomMargin = dp(12)
                }
            })
        }

        private fun groupLabel(t: String): TextView = TextView(ctx).apply {
            text = t.uppercase(java.util.Locale.getDefault())
            textSize = 11f; setTextColor(col(R.color.text_muted))
            typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.1f
        }

        private fun linkRow(label: String, url: String): TextView = TextView(ctx).apply {
            text = label; textSize = 12.5f; setTextColor(col(R.color.accent_light))
            isClickable = true
            setPadding(0, dp(6), 0, dp(6))
            setOnClickListener {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                catch (e: Exception) { Toast.makeText(ctx, "No app found to open this link", Toast.LENGTH_SHORT).show() }
            }
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(4) }
        }

        /** §3.2/§7: THE single primary action, executed through the pure
         *  [primaryAction] contract (same table render() paints from —
         *  PASS 23: INSTALL-shaped states retry the INSTALLATION with the
         *  already-downloaded APK, never a re-download). */
        private fun onPrimary() {
            val st = UpdateManager.state
            val action = primaryAction(st.phase, st.availableRelease != null)
            when (action.kind) {
                ActionKind.CHECK -> onCheckNow(true)
                ActionKind.DOWNLOAD -> st.availableRelease?.let(onDownload)
                ActionKind.CANCEL_DOWNLOAD -> onCancelDownload()
                ActionKind.INSTALL -> st.availableRelease?.let(onInstall)
                ActionKind.GRANT_PERMISSION -> UpdateManager.openInstallPermissionSettings(ctx)
                ActionKind.NONE -> { /* busy — nothing to do */ }
            }
        }

        // ── the ONE render entry: state → UI, truthfully (§4.6) ────────────
        @SuppressLint("SetTextI18n")
        fun render(st: State = UpdateManager.state) {
            val rel = st.availableRelease
            val installed = UpdateManager.installedVersionName(ctx)

            statusLine.text = when (st.phase) {
                Phase.IDLE -> if (installed.isNotEmpty()) "You're on version $installed." else ""
                Phase.CHECKING -> "Checking for updates\u2026"
                Phase.UP_TO_DATE -> if (st.message.isNotEmpty()) st.message
                    else if (installed.isNotEmpty()) "LumiDrop X is up to date (version $installed)." else ""
                Phase.UPDATE_AVAILABLE -> {
                    val base = if (rel != null)
                        "Version ${rel.version} is available (you have ${if (installed.isEmpty()) "?" else installed})."
                    else "A new version is available."
                    // §7 "Download interrupted": a preserved partial is told
                    // to the user (restore/verify paths set the message).
                    if (st.message.isNotEmpty()) "$base\n${st.message}" else base
                }
                Phase.DOWNLOADING ->
                    if (st.totalBytes > 0) "Downloading ${rel?.version} \u2014 ${fmt(st.progressBytes)} of ${fmt(st.totalBytes)}"
                    else "Downloading ${rel?.version} \u2014 ${fmt(st.progressBytes)}"
                Phase.FINALIZING -> "Finalizing the download\u2026"
                Phase.DOWNLOAD_COMPLETE -> "Download complete. Verifying\u2026"
                Phase.VERIFYING -> "Verifying the update\u2026"
                Phase.VERIFIED -> "Verified."
                Phase.VERIFY_FAILED -> "The update could not be verified.\n${st.message}"
                Phase.READY_TO_INSTALL -> "Version ${rel?.version} is verified and ready to install."
                Phase.INSTALL_PERMISSION_REQUIRED -> st.message.ifEmpty {
                    "Android needs your permission to let LumiDrop X install updates." }
                Phase.INSTALL_PENDING -> "The system installer is open \u2014 confirm the update there to finish."
                Phase.INSTALL_CANCELLED -> st.message.ifEmpty {
                    "Installation was cancelled \u2014 your current version is untouched. The downloaded update is still ready to install." }
                Phase.INSTALL_FAILED -> st.message.ifEmpty {
                    "The installation could not start.\nAndroid could not open the installer. Your downloaded update is preserved \u2014 try installation again." }
                Phase.UPDATE_INSTALLED -> st.message.ifEmpty { "Updated to $installed." }
                Phase.CHECK_FAILED -> {
                    // §7: the network failure is reported WITHOUT erasing the
                    // cached release information the user already has.
                    val kept = if (rel != null) "\nYour previously discovered update (version ${rel.version}) is still available." else ""
                    "Update check failed.\n${st.message}$kept"
                }
                Phase.DOWNLOAD_FAILED -> "Download failed.\n${st.message}"
            }

            // progress (§2.2): real bytes; indeterminate visuals when the
            // total is unknown; visible ONLY in the downloading phases
            val downloading = st.phase == Phase.DOWNLOADING || st.phase == Phase.FINALIZING
            progressBar.visibility = if (downloading) View.VISIBLE else View.GONE
            progressText.visibility = if (downloading) View.VISIBLE else View.GONE
            if (downloading) {
                if (st.phase == Phase.FINALIZING) {
                    progressBar.isIndeterminate = true
                    progressText.text = "Finishing\u2026"
                } else if (st.totalBytes > 0) {
                    progressBar.isIndeterminate = false
                    val pct = ((st.progressBytes * 100) / st.totalBytes).toInt()
                    progressBar.progress = pct
                    progressText.text = "$pct%  \u00b7  ${fmt(st.progressBytes)} / ${fmt(st.totalBytes)}"
                } else {
                    progressBar.isIndeterminate = true
                    progressText.text = fmt(st.progressBytes)
                }
            } else {
                progressBar.isIndeterminate = false
            }

            // release detail body — offers, ready, and honest failures only
            val showBody = st.phase in setOf(Phase.UPDATE_AVAILABLE, Phase.DOWNLOADING,
                Phase.FINALIZING, Phase.DOWNLOAD_COMPLETE, Phase.VERIFYING,
                Phase.READY_TO_INSTALL, Phase.INSTALL_PERMISSION_REQUIRED,
                Phase.DOWNLOAD_FAILED, Phase.VERIFY_FAILED, Phase.INSTALL_CANCELLED, Phase.INSTALL_PENDING)
            releaseBody.visibility = if (showBody && rel != null) View.VISIBLE else View.GONE
            if (showBody && rel != null) {
                releaseBody.removeAllViews()
                releaseBody.addView(TextView(ctx).apply {
                    text = "Version ${rel.version}"
                    textSize = 13.5f; setTextColor(col(R.color.text_primary))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                if (rel.apkSizeBytes > 0) releaseBody.addView(TextView(ctx).apply {
                    text = fmt(rel.apkSizeBytes); textSize = 11f; setTextColor(col(R.color.text_secondary))
                })
                val notes = ReleaseModel.shortNotes(rel.bodyMarkdown)
                if (notes.isNotEmpty()) releaseBody.addView(TextView(ctx).apply {
                    text = notes; textSize = 11.5f; setTextColor(col(R.color.text_secondary))
                    setLineSpacing(dp2f(), 1f)
                    layoutParams = LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(6) }
                })
            }

            // ONE primary action (§3.2 table) — label, enabled, behavior all
            // come from the SAME pure contract onPrimary() executes (§7).
            val action = primaryAction(st.phase, rel != null)
            actionBtn.text = action.label
            actionBtn.isEnabled = action.enabled
            actionBtn.alpha = if (action.enabled) 1f else 0.55f
            actionBtn.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ctx.dpf(12f)
                if (action.enabled) setColor(col(R.color.accent))
                else setColor(col(R.color.bg_secondary))
            }
            actionBtn.setTextColor(if (action.enabled) 0xFFF8FAFC.toInt() else col(R.color.text_muted))

            // last-checked footnote
            val lastAt = ReleaseModel.prefs(ctx).getLong("last_check_at", 0L)
            lastCheckedLine.text = if (lastAt > 0)
                "Last checked: " + java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(lastAt))
            else "Never checked"

            paintAutoPill()
        }

        fun onResume() {
            // PASS 23 §5/§8: one entry point — UpdateManager.resumeFlow runs
            // restore-from-snapshot, install-outcome reconciliation and the
            // paced (24h, toggle-respecting) auto-check as ONE guarded
            // pipeline off the UI thread. The section no longer duplicates
            // any of that logic (§10: one source of truth); rendering stays
            // listener-driven.
            onResumeFlow()
        }

        private fun dp2f(): Float = ctx.resources.displayMetrics.density * 2f
    }

    private fun fmt(b: Long): String = when {
        b >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }
}
