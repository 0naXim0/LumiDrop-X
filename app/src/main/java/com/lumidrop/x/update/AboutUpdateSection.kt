package com.lumidrop.x.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
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
 * ══ PASS 20 §6 — ONE unified About & Update Center card ══════════════════
 *
 * Pass 19 delivered the About identity and the update system as TWO separate
 * section cards. §6 replaces them with ONE cohesive card with a clear
 * internal hierarchy (identity / project / updates groups separated by
 * hairlines — nested layout sections, never a second card):
 *
 * Icon presentation (§6.2 root cause): Pass 19 rendered R.mipmap.ic_launcher
 * — a full 108dp adaptive-icon canvas whose white droplet mark occupies only
 * the central 66dp safe zone (~61% of the canvas) — inside a 72dp
 * accent-tinted tile WITH 14dp of extra padding: a dark tile-inside-a-tile
 * with a small floating mark. This pass stacks the adaptive icon's OWN two
 * layers directly: the gradient background drawable fills the rounded tile,
 * the mark-only foreground drawable draws on top at the launcher's own
 * proportions — no mipmap canvas, no extra padding, no distortion, no crop.
 *
 * Palette: all update controls bind to the app's themed purple/dark resources
 * so the section stays visually consistent with the rest of Settings in both
 * dark and light themes.
 */
object AboutUpdateSection {

    fun build(
        ctx: Context,
        dp: (Int) -> Int,
        col: (Int) -> Int,
        mount: LinearLayout,
        onCheckNow: (manual: Boolean) -> Unit,
        onDownload: (Release) -> Unit,
        onCancelDownload: () -> Unit,
        onInstall: (Release) -> Unit
    ): Section = Section(ctx, dp, col, mount, onCheckNow, onDownload, onCancelDownload, onInstall)

    class Section(
        private val ctx: Context,
        private val dp: (Int) -> Int,
        private val col: (Int) -> Int,
        private val mount: LinearLayout,
        private val onCheckNow: (Boolean) -> Unit,
        private val onDownload: (Release) -> Unit,
        private val onCancelDownload: () -> Unit,
        private val onInstall: (Release) -> Unit
    ) {
        private lateinit var card: LinearLayout
        private lateinit var statusLine: TextView
        private lateinit var lastCheckedLine: TextView
        private lateinit var latestLine: TextView
        private lateinit var autoPill: TextView
        private lateinit var actionBtn: TextView
        private lateinit var secondaryBtn: TextView
        private lateinit var progressBar: ProgressBar
        private lateinit var progressText: TextView
        private lateinit var releaseBody: LinearLayout

        fun attach() { buildCard() }

        @SuppressLint("SetTextI18n")
        private fun buildCard() {
            card = Widgets.sectionCard(ctx, paddingDp = 20)

            // ══ Identity group ════════════════════════════════════════════
            val identityRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val logoTile = FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(64), dp(64))
                // the adaptive icon's own background gradient fills the tile
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
                    // the foreground's 108dp canvas already centers the mark
                    // in its 66dp safe zone — the tile shows exactly the
                    // launcher's own proportions
                    layoutParams = FrameLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                })
                // rounded-rect clip, the shape the launcher would mask
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

            // ══ Project group ═════════════════════════════════════════════
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

            // ══ Update group ══════════════════════════════════════════════
            val updatesHeader = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            updatesHeader.addView(groupLabel("Updates").apply {
                layoutParams = LinearLayout.LayoutParams(0,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            autoPill = TextView(ctx).apply {
                textSize = 11f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setPadding(dp(11), dp(6), dp(11), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener {
                    val enabled = !ReleaseModel.prefs(ctx)
                        .getBoolean("auto_check_enabled", true)
                    ReleaseModel.prefs(ctx).edit()
                        .putBoolean("auto_check_enabled", enabled).apply()
                    updateAutoPill(enabled)
                    if (enabled) onCheckNow(true)
                }
            }
            updateAutoPill(ReleaseModel.prefs(ctx)
                .getBoolean("auto_check_enabled", true))
            updatesHeader.addView(autoPill)
            card.addView(updatesHeader)
            statusLine = TextView(ctx).apply {
                textSize = 12.5f; setTextColor(col(R.color.text_secondary)); setLineSpacing(dp2f(), 1f)
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10) }
            }
            card.addView(statusLine)
            latestLine = TextView(ctx).apply {
                textSize = 11.5f; setTextColor(col(R.color.accent_light)); visibility = View.GONE
            }
            card.addView(latestLine)
            lastCheckedLine = TextView(ctx).apply {
                textSize = 10.5f; setTextColor(col(R.color.text_muted))
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(3) }
            }
            card.addView(lastCheckedLine)

            val ctlRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            actionBtn = pill("Check for Updates", filled = true) { onPrimary() }
            ctlRow.addView(actionBtn)
            card.addView(ctlRow)

            // Release card body — a nested section INSIDE the one card
            releaseBody = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                setPadding(dp(14), dp(12), dp(14), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(12) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = ctx.dpf(14f); setColor(col(R.color.bg_card))
                }
            }
            card.addView(releaseBody)

            progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = false; max = 100; progress = 0
                visibility = View.GONE
                progressTintList = android.content.res.ColorStateList.valueOf(col(R.color.accent))
                layoutParams = LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).also { it.topMargin = dp(12) }
            }
            card.addView(progressBar)
            progressText = TextView(ctx).apply {
                textSize = 10.5f; setTextColor(col(R.color.text_muted)); visibility = View.GONE
            }
            card.addView(progressText)

            secondaryBtn = pill("Later", filled = false) { releaseBody.visibility = View.GONE }
            secondaryBtn.visibility = View.GONE
            val secRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
            secRow.addView(secondaryBtn)
            card.addView(secRow)

            mount.addView(card)
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
            textSize = 10.5f; setTextColor(col(R.color.text_muted))
            typeface = android.graphics.Typeface.DEFAULT_BOLD; letterSpacing = 0.1f
        }

        private fun updateAutoPill(enabled: Boolean) {
            if (!::autoPill.isInitialized) return
            autoPill.text = if (enabled) "Auto on" else "Auto off"
            autoPill.setTextColor(if (enabled) col(R.color.text_primary) else col(R.color.text_secondary))
            autoPill.contentDescription = if (enabled)
                "Automatic update checks enabled. Tap to disable."
            else "Automatic update checks disabled. Tap to enable."
            autoPill.background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ctx.dpf(20f)
                if (enabled) {
                    setColor(col(R.color.accent_dark))
                    setStroke(dp(1), col(R.color.accent))
                } else {
                    setColor(col(R.color.bg_secondary))
                    setStroke(dp(1), col(R.color.border_subtle))
                }
            }
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

        private fun pill(label: String, filled: Boolean, onClick: () -> Unit): TextView = TextView(ctx).apply {
            text = label; textSize = 12.5f; gravity = Gravity.CENTER
            isClickable = true; isFocusable = true
            setPadding(dp(16), dp(10), dp(16), dp(10))
            if (filled) setTextColor(col(R.color.text_primary))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = ctx.dpf(12f)
                if (filled) setColor(col(R.color.accent))
                else { setColor(col(R.color.bg_secondary)); setStroke(dp(1), col(R.color.border_subtle)) }
            }
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.marginStart = dp(10) }
        }

        private fun onPrimary() {
            val st = UpdateManager.state
            when (st.phase) {
                Phase.UPDATE_AVAILABLE -> st.availableRelease?.let(onDownload)
                Phase.DOWNLOADING -> onCancelDownload()
                Phase.DOWNLOAD_COMPLETE, Phase.VERIFIED -> st.availableRelease?.let(onInstall)
                else -> onCheckNow(true)
            }
        }

        @SuppressLint("SetTextI18n")
        fun render(st: State = UpdateManager.state) {
            val rel = st.availableRelease
            val installed = UpdateManager.installedVersionName(ctx)
            statusLine.text = when (st.phase) {
                Phase.IDLE -> if (installed.isNotEmpty()) "You're on version $installed." else ""
                Phase.CHECKING -> "Checking for updates\u2026"
                Phase.UP_TO_DATE -> if (st.message.isNotEmpty()) st.message
                    else if (installed.isNotEmpty()) "LumiDrop X is up to date (version $installed)." else ""
                Phase.UPDATE_AVAILABLE ->
                    if (rel != null) "A new version is available: ${rel.version} (you have ${installed})."
                    else "A new version is available."
                Phase.DOWNLOADING -> "Downloading ${rel?.version}\u2026"
                Phase.DOWNLOAD_COMPLETE -> "Download complete."
                Phase.VERIFYING -> "Verifying the download\u2026"
                Phase.VERIFIED -> "Verified \u2014 ready to install."
                Phase.VERIFY_FAILED -> "The update could not be verified.\n${st.message}"
                Phase.INSTALL_PENDING -> "The system installer is open \u2014 confirm the update there to finish."
                Phase.CHECK_FAILED -> "Update check failed.\n${st.message}"
                Phase.DOWNLOAD_FAILED -> "Download failed.\n${st.message}"
                Phase.INSTALL_CANCELLED -> "The installation was cancelled \u2014 your current version is untouched. You can retry any time."
                Phase.INSTALL_FAILED -> "The installation could not start.\n${st.message}"
            }
            latestLine.visibility = if (rel?.version != null) View.VISIBLE else View.GONE
            latestLine.text = if (rel?.version != null) "Latest release: ${rel.version}" else ""

            val lastAt = ReleaseModel.prefs(ctx).getLong("last_check_at", 0L)
            lastCheckedLine.text = if (lastAt > 0)
                "Last checked: " + java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(lastAt))
            else "Never checked"

            val downloading = st.phase == Phase.DOWNLOADING
            progressBar.visibility = if (downloading) View.VISIBLE else View.GONE
            progressText.visibility = if (downloading) View.VISIBLE else View.GONE
            if (downloading) {
                progressText.text = if (st.totalBytes > 0)
                    "${fmt(st.progressBytes)} / ${fmt(st.totalBytes)}  (${(st.progressBytes * 100 / st.totalBytes)}%)"
                else "${fmt(st.progressBytes)} downloaded (size unknown)"
                progressBar.progress = if (st.totalBytes > 0)
                    (st.progressBytes * 100 / st.totalBytes).toInt() else 0
            }

            val showCard = st.phase in setOf(Phase.UPDATE_AVAILABLE, Phase.DOWNLOADING,
                Phase.DOWNLOAD_COMPLETE, Phase.VERIFIED, Phase.VERIFY_FAILED, Phase.DOWNLOAD_FAILED, Phase.INSTALL_PENDING)
            releaseBody.visibility = if (showCard && rel != null) View.VISIBLE else View.GONE
            if (showCard && rel != null) {
                releaseBody.removeAllViews()
                releaseBody.addView(TextView(ctx).apply {
                    text = "Version ${rel.version}"
                    textSize = 14f; setTextColor(col(R.color.text_primary))
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
                releaseBody.addView(pill(
                    when (st.phase) {
                        Phase.UPDATE_AVAILABLE -> "Update Now"
                        Phase.DOWNLOADING -> "Cancel Download"
                        else -> "Install Update"
                    }, filled = true) { onPrimary() }.apply {
                    layoutParams = LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.topMargin = dp(10) }
                })
            }

            actionBtn.text = when (st.phase) {
                Phase.UPDATE_AVAILABLE -> "Update Now"
                Phase.DOWNLOADING -> "Cancel"
                Phase.DOWNLOAD_COMPLETE, Phase.VERIFIED -> "Install Update"
                Phase.INSTALL_CANCELLED, Phase.INSTALL_FAILED -> "Check for Updates"
                else -> "Check for Updates"
            }
            secondaryBtn.visibility = if (showCard) View.VISIBLE else View.GONE
            actionBtn.isEnabled = st.phase !in setOf(Phase.CHECKING, Phase.VERIFYING)
        }

        fun onResume() {
            UpdateManager.reconcileAfterRestart(ctx)
            val prefs = ReleaseModel.prefs(ctx)
            val busy = UpdateManager.state.phase in setOf(Phase.DOWNLOADING, Phase.VERIFYING, Phase.INSTALL_PENDING)
            if (ReleaseModel.shouldAutoCheck(
                    prefs.getBoolean("auto_check_enabled", true),
                    prefs.getLong("last_check_at", 0L),
                    System.currentTimeMillis(), busy)) {
                onCheckNow(false)
            } else {
                render()
            }
        }

        private fun dp2f(): Float = ctx.resources.displayMetrics.density * 2f
    }

    private fun fmt(b: Long): String = when {
        b >= 1_048_576 -> String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0)
        b >= 1024 -> String.format(java.util.Locale.US, "%.0f KB", b / 1024.0)
        else -> "$b B"
    }
}
