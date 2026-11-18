package com.lumidrop.x.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Environment
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.lumidrop.x.R
import java.io.File

/**
 * A visual, in-app folder browser -- Storage Access Framework's
 * ACTION_OPEN_DOCUMENT_TREE returns a content:// tree URI, not a plain
 * filesystem path, and this app's file-serving code (LumiDropServer's
 * upload handling, Recent Files scanning, etc.) is built entirely around
 * java.io.File paths. Converting that URI back to a real path is possible
 * but brittle across storage providers/SD cards; a plain in-app browser
 * sidesteps the mismatch entirely while still giving the person a proper
 * visual "tap through folders" picker instead of typing a path by hand.
 *
 * This was originally a private function inside WebUiSettingsActivity
 * (used for picking a music-scan folder) and is extracted here, unchanged
 * in behavior, so the receive-folder picker in SettingsFragment uses the
 * same already-working implementation instead of a second parallel one.
 */
fun showFolderPickerDialog(context: Context, start: File, onPicked: (path: String, name: String) -> Unit) {
    // ── Pass-44 (§8b): the picker is MULTI-ROOT. Internal storage is joined ──
    // by every mounted, readable removable volume (SD card / USB OTG — see
    // ExternalVolumes), so a Received Files destination can live on external
    // storage wherever the platform's permissions make that real for this
    // app (the app holds MANAGE_EXTERNAL_STORAGE; nothing here pretends a
    // volume that isn't mounted exists). Navigation stays INSIDE whichever
    // root is selected — "Up one level" stops at that root, never at
    // /storage — and the roots are one pill-row away whenever there is more
    // than one to choose from.
    val internalRoot = ExternalVolumes.internalRoot()
    val roots = listOf(internalRoot) + ExternalVolumes.mounted(context).map { it.root }
    fun rootOf(f: File): File =
        roots.filter { f.absolutePath == it.absolutePath || f.absolutePath.startsWith(it.absolutePath + "/") }
            .maxByOrNull { it.absolutePath.length } ?: internalRoot
    var current = if (start.exists() && start.isDirectory) start else internalRoot
    var currentRoot = rootOf(current)
    val dialog = AlertDialog.Builder(context).create()

    fun col(res: Int) = ContextCompat.getColor(context, res)
    fun dp(n: Int) = context.dp(n)
    fun tv(text: String, size: Float, colorRes: Int) = TextView(context).apply {
        this.text = text; textSize = size; setTextColor(col(colorRes))
    }
    fun spacer(d: Int) = android.view.View(context).apply { layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(d)) }

    val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(10))
        background = ContextCompat.getDrawable(context, R.drawable.bg_dialog)
    }
    container.addView(tv("Choose a folder", 16f, R.color.text_primary).also {
        it.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { lp -> lp.bottomMargin = dp(8) }
    })
    val pathTv = tv("", 11f, R.color.accent_light).also {
        it.maxLines = 2
    }
    container.addView(pathTv)
    // ── Pass-44 (§8b): the storage pills — one per root, the selected one ──
    // highlighted; hidden entirely while only internal storage exists.
    val storageBox = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    container.addView(storageBox)
    container.addView(spacer(10))

    val list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    val scroller = ScrollView(context).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(340))
        addView(list)
    }
    container.addView(scroller)
    container.addView(spacer(12))

    val selectBtn = TextView(context).apply {
        text = "Select This Folder"
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(android.graphics.Color.WHITE)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        background = ContextCompat.getDrawable(context, R.drawable.bg_btn_primary)
        setPadding(dp(14), dp(13), dp(14), dp(13))
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }
    container.addView(selectBtn)

    fun folderRow(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label; textSize = 14f
        setTextColor(col(R.color.text_primary))
        background = ContextCompat.getDrawable(context, R.drawable.bg_btn_secondary)
        setPadding(dp(12), dp(12), dp(12), dp(12))
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(6) }
        setOnClickListener { onClick() }
    }

    // Phase 5 (v3 §6 item 4): the directory listing now runs on a background
    // thread. listFiles() on the UI thread was a real jank source exactly
    // where this dialog is used most — Settings' receive-folder picker and
    // the WebUI music-folder picker both start at external storage, whose
    // top-level listing can be slow on low-tier hardware and USB/OTG mounts.
    // One single-thread executor per dialog (its only job is serializing
    // rapid navigation taps so the latest listing always wins), posted back
    // to the main thread with a stale-result guard (the dialog must still be
    // showing AND still be on the directory the listing was taken for), and
    // shut down when the dialog goes away.
    val listExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "wsx-folder-picker").apply { isDaemon = true }
    }
    // Read and written only on the main thread (the dismiss listener and the
    // main-thread Handler post below); the background executor never touches
    // it, so a plain captured local is correct without @Volatile (which is
    // not applicable to locals anyway).
    var pickerDismissed = false

    fun refresh() {
        currentRoot = rootOf(current)
        pathTv.text = current.absolutePath
        // Storage pills (§8b): rebuilt from the LIVE volume list on every
        // refresh, so a card pulled while the dialog is open simply stops
        // being offered (the current root's own pill stays — navigating it
        // is harmless, and selecting it would be refused by the caller's
        // own availability check).
        storageBox.removeAllViews()
        val liveRoots = listOf(internalRoot) + ExternalVolumes.mounted(context).map { it.root }
        if (liveRoots.size > 1) {
            for ((i, r) in liveRoots.withIndex()) {
                val selected = r.absolutePath == currentRoot.absolutePath
                val label = if (i == 0) "Internal" else (ExternalVolumes.mounted(context)
                    .firstOrNull { it.root.absolutePath == r.absolutePath }?.label ?: "External")
                storageBox.addView(TextView(context).apply {
                    text = label; textSize = 11.5f
                    setTextColor(col(if (selected) R.color.text_primary else R.color.text_secondary))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE; cornerRadius = context.dpf(14f)
                        setColor(if (selected) col(R.color.accent) and 0x33FFFFFF else 0x14000000 or 0x00FFFFFF)
                        setStroke(dp(1), if (selected) col(R.color.accent_light) else 0x1FFFFFFF)
                    }
                    setPadding(dp(12), dp(6), dp(12), dp(6))
                    isClickable = true; isFocusable = true
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                    ).also { if (i > 0) it.marginStart = dp(8) }
                    setOnClickListener { current = r; currentRoot = r; refresh() }
                })
            }
        }
        list.removeAllViews()
        if (current.absolutePath != currentRoot.absolutePath && current.parentFile != null) {
            list.addView(folderRow(".. (Up one level)") {
                current = current.parentFile ?: current; refresh()
            })
        }
        list.addView(tv("Loading folders…", 12f, R.color.text_muted).also {
            it.setPadding(dp(10), dp(14), dp(10), dp(14))
        })
        val snapshot = current
        listExec.execute {
            val subs = try {
                (snapshot.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: emptyList())
                    .sortedBy { it.name.lowercase() }
            } catch (e: Exception) { emptyList() }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                // Stale-guard: a faster finger (or a dismissal) may have moved
                // on while this listing was being read — apply only what is
                // still current.
                if (pickerDismissed || snapshot.absolutePath != current.absolutePath) return@post
                list.removeAllViews()
                if (current.absolutePath != currentRoot.absolutePath && current.parentFile != null) {
                    list.addView(folderRow(".. (Up one level)") {
                        current = current.parentFile ?: current; refresh()
                    })
                }
                if (subs.isEmpty()) {
                    list.addView(tv("(no subfolders here)", 12f, R.color.text_muted).also {
                        it.setPadding(dp(10), dp(14), dp(10), dp(14))
                    })
                }
                for (d in subs) list.addView(folderRow(d.name) { current = d; refresh() })
            }
        }
    }
    selectBtn.setOnClickListener {
        onPicked(current.absolutePath, current.name.ifEmpty { "Storage" })
        dialog.dismiss()
    }
    dialog.setOnDismissListener {
        pickerDismissed = true
        listExec.shutdownNow()
    }
    refresh()

    dialog.setView(container)
    // Every other custom-view AlertDialog in this codebase clears the
    // theme's default window background so only the container's own
    // rounded bg_dialog shows -- this one had been missing that call,
    // leaving the theme's un-rounded default window frame rendering
    // around the custom rounded content behind it (the reported squared-
    // corner artifact on this specific modal).
    dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
    dialog.window?.setDimAmount(0.55f)
    // ── Pass-26 (#9): this picker is a full-screen AlertDialog window —
    // apply the app's system-bar colors so it never drops a black
    // status/navigation bar zone over the screen while it is open.
    dialog.applyLdxWindowChrome()
    dialog.show()
}
