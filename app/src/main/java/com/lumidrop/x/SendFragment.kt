package com.lumidrop.x

import android.Manifest
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.ColorUtils
import androidx.core.widget.NestedScrollView
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.lumidrop.x.databinding.FragmentSendBinding
import com.lumidrop.x.media.MediaAlbum
import com.lumidrop.x.media.MediaLibraryItem
import com.lumidrop.x.media.ThumbnailEngine
import com.lumidrop.x.p2p.NativeDiscovery
import com.lumidrop.x.p2p.NativePushClient
import com.lumidrop.x.server.LumiDropServer
import com.lumidrop.x.server.MimeTypes
import com.lumidrop.x.ui.BottomSheet
import com.lumidrop.x.service.ServerForegroundService
import com.lumidrop.x.settings.SettingsManager
import com.lumidrop.x.transfer.TransferManager
import com.lumidrop.x.transfer.PreviewItem
import com.lumidrop.x.transfer.textPreviewTitle
import com.lumidrop.x.ui.DeviceAdapter
import com.lumidrop.x.ui.DeviceRow
import com.lumidrop.x.ui.FormatIcons
import com.lumidrop.x.ui.Motion
import com.lumidrop.x.ui.QueueAdapter
import com.lumidrop.x.ui.QueueRow
import com.lumidrop.x.ui.Widgets
import com.lumidrop.x.ui.SystemBarChrome
import com.lumidrop.x.ui.applyLdxWindowChrome
import com.lumidrop.x.ui.attachDragSelect
import com.lumidrop.x.ui.LdxDialogAction
import com.lumidrop.x.ui.LdxButtonStyle
import com.lumidrop.x.ui.dp
import com.lumidrop.x.ui.dpf
import com.lumidrop.x.ui.showLdxDialog
import com.lumidrop.x.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import android.provider.DocumentsContract
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The "Send" tab -- this is MainActivity's entire previous screen, ported to
 * a Fragment as part of Phase 17's consolidated bottom-nav shell (see
 * MainActivity.kt, now the shell, and activity_main.xml).
 *
 * Mechanical changes from the old Activity version (behavior-preserving):
 *  - ActivityMainBinding -> FragmentSendBinding, nullable-until-onDestroyView
 *    per the standard Fragment ViewBinding pattern.
 *  - this-as-Context / getSystemService / contentResolver / window /
 *    bare checkSelfPermission -> requireContext()/requireActivity() /
 *    ContextCompat.checkSelfPermission, since Fragment isn't a Context.
 *  - lifecycleScope -> viewLifecycleOwner.lifecycleScope for anything that
 *    touches binding after a suspend point, so an in-flight coroutine
 *    can't run past onDestroyView and hit a null binding.
 *  - onResume/onPause -> also mirrored in onHiddenChanged(), because
 *    switching bottom-nav tabs uses FragmentManager show()/hide() (keeps
 *    scroll position / expanded cards alive) rather than replace() -- and
 *    show()/hide() does NOT call onPause/onResume, only onHiddenChanged.
 *    Without this, the poll loop would keep running (draining battery)
 *    while a completely different tab is on screen.
 *
 * Real changes (Phase 15-17 redesign):
 *  - blackoutOverlay / the FAB / the floating transfer popup all moved to
 *    the shell (MainActivity) since they need to float above every tab,
 *    not just this one -- this file only still owns the btnBlackout
 *    button, which now calls up to the shell to actually trigger it.
 *  - Every emoji is gone -- file-type glyphs, accept/decline/kick/clear
 *    icons, section headers -- replaced with the vector icon set.
 *  - fileIconRes() now returns a drawable resource id backed by
 *    ThumbnailEngine's real async thumbnails (with the vector icon as
 *    fallback + an extension badge) instead of an emoji string.
 *  - updateUI()'s URL section now renders one row per enabled+detected
 *    network interface when more than one is active (Phase 17 par 2),
 *    instead of always assuming exactly one network path.
 *  - Phase 18: the Recent Files section (grid, quick-actions sheet,
 *    rename/share/details/delete) was removed in full -- it has no
 *    equivalent in the Phase 18 design prototype. fileIconRes() stays,
 *    since the transfer queue (queueAdapter) still needs it.
 */
class SendFragment : Fragment() {

    // v1.0 (§devices-card-no-grow) → post-v1.0 refinement (§devices-card-
    // EXACT-size): the list area now holds ONE fixed height forever — the
    // height the empty state itself occupies — so the parent card renders
    // at EXACTLY the same size with any number of devices as it does with
    // none (the explicit spec), and any device count that overflows it
    // scrolls INSIDE the card instead of stretching it. The height is
    // captured from the real, laid-out empty state whenever it is visible
    // (re-captured every empty frame — rotation/locale can change its text
    // wrapping), with a dp fallback for the connect-before-any-empty-frame
    // edge (host restart with an instantly-reconnecting peer).
    private companion object {
        const val DEVICES_AREA_FALLBACK_DP = 164   // ~illustration(120) + two text lines + paddings
    }

    /** §devices-card-exact-size: the last measured height of the empty-state
     *  block — the one true reference for "the card's no-device size". Read
     *  post-layout only (never a mid-layout guess), kept in px because it
     *  is captured and consumed within the same configuration. */
    private val microScrollGuard = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        if (_binding != null && isAdded) clampSendMicroScroll()
    }

    private var devicesAreaH = 0   // retired by Pass-12 (weight-fill fix) — kept only as a harmless leftover field

    private var _binding: FragmentSendBinding? = null
    private val binding get() = _binding!!

    private val server: LumiDropServer? get() = ServerForegroundService.instance?.server
    private val isRunning: Boolean get() = ServerForegroundService.isRunning

    private val previewItems = mutableListOf<PreviewItem>()

    // ── Pass-31 (#2): pick batches currently being resolved for staging, and ──
    // a Send tap that arrived while one was in flight. A batch is only ever
    // committed WHOLE (one stageOrSendItems call per pick — see enqueueUris),
    // so a partially populated queue can never be presented as sendable; this
    // pair closes the one remaining window: a Send tapped during the (short)
    // resolution wait is remembered and runs the instant the commit lands,
    // against the complete queue. Main-thread only — both writers run in the
    // UI message that starts/ends a batch.
    private var stagingBatchesInFlight = 0
    private var sendWantedWhileStaging = false

    // GOAT v2 (item 11) — RESTORED (GOAT v3, compiler-caught): the App
    // Selection sheet's icon cache, fragment-level so every decoded app
    // icon survives the sheet being closed. Its two call sites
    // (showApkPickerSheet's `val iconCache = apkIconCache` and the
    // progressive loader's containsKey check) shipped referencing this
    // field, but the declaration itself was absent from the source zip —
    // a loss no previous round could catch, because no round had a
    // compiler (see the v3 brief's own Section 0 note); the first real
    // build here failed on exactly this one unresolved reference. Restored
    // to the documented intent: a cache shared across every opening of the
    // sheet, so a reopen's first pass paints most tiles immediately from
    // cache instead of placeholder-then-decode. ConcurrentHashMap rather
    // than a plain HashMap: the loader's containsKey pre-check runs on
    // Dispatchers.IO while every read-for-display and every write happens
    // on the main thread — plain HashMap would make that a cross-thread
    // read of a concurrently-mutated map, exactly the visibility/atomicity
    // hole ConcurrentHashMap exists to close. One Drawable instance per
    // package is safe here: each package renders at most one tile at a
    // time (the apps list is distinct by package, and rebuild() clears the
    // grid before re-adding), all tiles size icons identically (56dp), and
    // pm.getApplicationIcon() hands out a fresh instance per call anyway —
    // this cache is the one deliberate exception, which is the point.
    private val apkIconCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.drawable.Drawable>()

    // Phase 5 (§5.6 — repeated-open performance, root cause): the app LIST
    // itself is now cached at fragment level, exactly like the icon cache
    // beside it already was. Every previous opening re-ran the full
    // PackageManager enumeration (getInstalledApplications + a label lookup
    // per app + a sort) on Dispatchers.IO — a few hundred milliseconds of
    // disk/CPU per open, repeated forever, for a list that changes only
    // when the person installs or uninstalls something. With the cache, a
    // reopen within the freshness window submits the known list
    // synchronously (the sheet's first frame renders real, labeled tiles
    // from cache instead of "Loading apps...") while a refresh still runs
    // in the background and re-submits if anything actually changed — so
    // an app installed seconds ago still appears without waiting a full
    // window. 30s: long enough that the common "share two or three APKs in
    // a row" flow never re-enumerates, short enough that a just-installed
    // app shows up on the very next open.
    private var apkListCache: List<Pair<ApplicationInfo, String>>? = null
    private var apkListCachedAtMs = 0L
    private val apkListFreshnessMs = 30_000L

    // Phase 5 (§5.6): ONE decode gate for the app sheet across all of its
    // openings (each previous opening built its own Semaphore(4), so two
    // sheets open in quick succession could run 8 concurrent icon decodes
    // — and, worse, the coroutines lived on the FRAGMENT's lifecycle scope
    // and kept decoding for a sheet that was already dismissed). Shared
    // here, and the decode jobs themselves now live on the SHEET's scope,
    // cancelled at dismiss.
    private val apkIconGate = Semaphore(4)

    // ── Pass-26 (#3.1): the App Picker's one-live-instance guard. Set
    // synchronously when showApkPickerSheet starts building a sheet, cleared
    // in the sheet dialog's dismiss listener (the one funnel every close
    // path — backdrop, drag-to-dismiss, Share — ends in). Rapid taps on the
    // Apps tile can therefore never stack a second sheet over a live one.
    private var apkPickerShowing = false

    private var qrVisible = false
    private var pollJob: Job? = null
    private var connDialogShowing = false
    private var pulseAnimator: android.animation.ValueAnimator? = null
    // Whichever URL the QR modal currently displays -- defaults to the
    // primary (most-reachable) enabled interface; the modal's own source
    // chips re-target it from inside the dialog.
    private var activeQrUrl: String? = null

    // Pass-7: the enabled, priority-ordered interface list behind the server
    // card's fixed-height URL row (primary = first) — the single source for
    // the source chip, the addresses dialog, the QR modal's labeled chips
    // and every copy action. Rebuilt by renderServerUrls(); empty while
    // stopped/waiting.
    private var serverIfaces: List<NetworkUtils.DetectedInterface> = emptyList()

    // QR modal's live sub-views (rebuilt per open; chips switch the shown
    // QR without reopening the dialog).
    private var qrDialogChips: List<TextView> = emptyList()
    private var qrDialogUrlText: TextView? = null
    private var qrDialogUrlScroll: android.widget.HorizontalScrollView? = null
    private var qrDialogCaption: TextView? = null

    private val queueAdapter: QueueAdapter by lazy {
        QueueAdapter(
            // §static-format-icons: icon+tint resolved together by the one
            // shared FormatIcons resolver — every queued file renders its
            // family glyph in its family color (pdf red, sheet green…).
            formatIcon = { ext -> FormatIcons.icon(requireContext(), ext) },
            // Phase 5 (§5.2): the queue rows' thumbnail decode scope — this
            // lazy is first touched in setupButtons() during onViewCreated,
            // at which point viewLifecycleOwner exists and dies with this
            // screen's view exactly like every other row-work scope here.
            thumbScope = viewLifecycleOwner.lifecycleScope,
            onRemoveStaged = { item -> previewItems.remove(item); updatePreviewCard() },
            onRemoveLive = { item -> server?.removeFromQueueByName(item.name); updatePreviewCard() },
            onExcludeChild = { item, path ->
                // Matches by stable identity (uri/path/name), not by
                // reference/structural equality — the same identity
                // concept QueueAdapter's own keyOf uses internally, so
                // "this item" means the same thing on both sides.
                val idx = previewItems.indexOfFirst { (it.uri ?: it.path ?: it.name) == (item.uri ?: item.path ?: item.name) }
                if (idx >= 0) {
                    previewItems[idx] = previewItems[idx].copy(excludedChildPaths = previewItems[idx].excludedChildPaths + path)
                    updatePreviewCard()
                }
            },
            // ── Pass-32 (#2): the row-CONTAINER open handler (the Pass-29 eye ──
            // button is gone — tapping the item is the affordance now).
            // Media routes to the app's own previewer below; every other
            // openable file type routes to Android's ACTION_VIEW flow in
            // onQueueItemOpen.
            onOpenItem = { row -> onQueueItemOpen(row) },
            // ── Pass-33 (#3): the same click-to-open affordance for files ──
            // INSIDE an expanded staged folder — routed through the child
            // resolver below (manifest member, plain path join, or SAF walk).
            onOpenChild = { folder, relPath -> onQueueChildOpen(folder, relPath) },
            // ── Pass-35 (#7): a queued TEXT message opens the dedicated text ──
            // viewing modal — the exact content, verbatim, in a bounded
            // scrolling sheet. Purely a read path: nothing here sends,
            // edits or mutates the queue.
            onOpenText = { item -> com.lumidrop.x.ui.showTextContentViewer(this, item.name, item.textContent ?: "") },
        )
    }

    /** ── Pass-33 (#3): resolves one child of a staged folder to a real, ────────
     *  openable address and opens it with the same routing a top-level queue
     *  row gets: previewable media (images/videos) opens the app's previewer
     *  with the FOLDER's previewable children as the navigation (so the
     *  filmstrip reads the expansion top-to-bottom, and the preview's select
     *  control carries the folder's own exclude/include semantics); every
     *  other file type goes to Android's ACTION_VIEW through the same
     *  openFileExternally path. Resolution order:
     *    1. the folder's childManifest (a media album) — the member's own
     *       real uri/path/mime/size, zero I/O;
     *    2. a plain path folder — folderPath + "/" + relPath, mime from the
     *       extension (the same mapping the queue's own icon resolver uses);
     *    3. a SAF tree folder — a bounded DocumentFile walk along relPath's
     *       segments (a per-tap cost only), failing with an honest toast.
     */
    private fun onQueueChildOpen(folder: PreviewItem, relPath: String) {
        if (context == null) return
        val manifestChild = folder.childManifest?.firstOrNull { it.relPath == relPath }
        if (manifestChild != null) {
            openQueueChildAddress(manifestChild.path, manifestChild.uri, relPath.substringAfterLast('/'),
                manifestChild.mime, folder, relPath)
            return
        }
        if (folder.path != null) {
            val childFile = File(folder.path!!, relPath)
            openQueueChildAddress(childFile.absolutePath, null, childFile.name,
                MimeTypes.getMimeType(childFile.extension), folder, relPath)
            return
        }
        if (folder.uri != null) {
            // SAF walk — bounded by the child's own path depth, only on tap.
            try {
                var doc: androidx.documentfile.provider.DocumentFile? =
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(requireContext(), Uri.parse(folder.uri))
                for (seg in relPath.split('/')) {
                    if (seg.isEmpty()) continue
                    doc = doc?.findFile(seg) ?: break
                }
                if (doc != null && doc.isFile) {
                    openQueueChildAddress(null, doc.uri.toString(), doc.name ?: relPath.substringAfterLast('/'),
                        doc.type ?: "application/octet-stream", folder, relPath)
                    return
                }
            } catch (e: Exception) { /* fall through to the honest toast */ }
            toast("This file can't be opened here")
            return
        }
        toast("This file can't be opened here")
    }

    /** ── Pass-41 (§7) + Pass-42 (§4): opens ONE file picked straight from ──
     *  the Files sheet's body tap. AUDIO goes through LumiDrop X's own
     *  SHARED music player (the one controller, its compact pill + expanded
     *  overlay, the playback notification) — a library-known file resolves
     *  to its real MediaStore row (title, artist, album id, duration, the
     *  album-keyed artwork cache), an unindexed one (nested folders,
     *  received files) plays as a file-shaped Track whose tags, duration
     *  and embedded artwork enrichTrackMetadata fills from the file itself.
     *  Images and videos keep the app's OWN internal previewer — the exact
     *  same media-picker preview the queue's expanded folders use (a
     *  single-item queuePreview). Everything else (documents, PDFs, APKs,
     *  archives, …) goes through the existing Android open flow with the
     *  correct MIME, exactly like a queued file's open. Nothing is added to
     *  the queue by opening; the sheet's folder context and selection state
     *  are untouched. */
    private fun openStandaloneFile(path: String, name: String) {
        val ctx = context ?: return
        val ext = name.substringAfterLast('.', "")
        val mime = MimeTypes.getMimeType(ext)
        // ── GOAT-PASS 15 (§1 — ONE installation system, EVERY surface): the ──
        // Files sheet's file-body tap is the third entry into the SHARED
        // archive-install flow (Received cards, the Transfers browse sheet and
        // this one all funnel into XapkInstallFlow). A supported package
        // archive (.xapk/.apkm/.apks) opens LumiDrop X's own install modal —
        // never Android's "Open with" chooser — while an ordinary .apk keeps
        // the system-installer flow in openFileExternally and media keeps the
        // internal players below: the exact routing contract every surface
        // already follows, now enforced here too.
        if (com.lumidrop.x.transfer.XapkInstallFlow.isArchiveInstallFormat(name)) {
            openArchiveInstall(null, path, name)
            return
        }
        // ── Pass-42 (§4): the audio test mirrors QueueAdapter.isAudioMedia —
        // audio/* by MIME, or a non-media MIME whose extension resolves to
        // audio (an octet-stream .flac still counts).
        val isAudio = mime.startsWith("audio/") ||
            (!mime.startsWith("image/") && !mime.startsWith("video/") && !mime.startsWith("text/") &&
                ext.isNotEmpty() && MimeTypes.getMimeType(ext).startsWith("audio/"))
        if (isAudio) {
            val f = File(path)
            if (!f.isFile) { toast("This file can't be opened here"); return }
            viewLifecycleOwner.lifecycleScope.launch {
                val act = activity ?: return@launch
                val item = try { com.lumidrop.x.media.MediaLibrary.queryAudioForPath(ctx, path) } catch (e: Exception) { null }
                val track = if (item != null) {
                    // The SAME Track shape the Music sheet's playFn builds —
                    // one identity (the path), one controller, one artwork
                    // pipeline (the album-keyed cache).
                    com.lumidrop.x.ui.MusicPlayback.Track(
                        key = item.path ?: item.uri.toString(),
                        uri = item.uri,
                        title = item.title ?: item.name,
                        artist = item.artist,
                        albumId = item.albumId,
                        durationMs = item.durationMs,
                        size = item.size,
                    )
                } else {
                    // Unindexed file: the arturi artKey — enrich decodes the
                    // embedded cover and fills title/artist/duration.
                    com.lumidrop.x.ui.MusicPlayback.Track(
                        key = path,
                        uri = Uri.fromFile(f),
                        title = name.substringBeforeLast('.').ifBlank { name },
                        artist = null,
                        albumId = 0L,
                        durationMs = 0L,
                        size = f.length(),
                    )
                }
                com.lumidrop.x.ui.MusicPlayback.play(act, listOf(track), 0)
            }
            return
        }
        if (queueAdapter.isPreviewableMedia(mime, name, isFolder = false, isText = false)) {
            com.lumidrop.x.ui.showMediaPickerSheet(
                fragment = this,
                onConfirm = { _, _ -> /* preview-only entry; nothing to confirm */ },
                queuePreview = listOf(MediaLibraryItem(
                    id = 0L,
                    uri = Uri.fromFile(File(path)),
                    name = name,
                    path = path,
                    size = try { File(path).length() } catch (e: Exception) { 0L },
                    mime = mime,
                    dateAddedSec = 0L,
                    isVideo = mime.startsWith("video/") || (!mime.startsWith("image/") && MimeTypes.isVideo(ext)),
                    durationMs = 0L,
                    bucketId = -1L,
                    bucketName = "",
                )),
                queuePreviewIndex = 0,
            )
        } else {
            openFileExternally(null, path, name, mime)
        }
    }

    /** The shared second half of #3's router: media → the previewer with the
     *  folder's previewable children as navigation; anything else → ACTION_VIEW. */
    private fun openQueueChildAddress(path: String?, uriStr: String?, name: String, mime: String,
                                      folder: PreviewItem, relPath: String) {
        val ctx = context ?: return
        if (queueAdapter.isPreviewableMedia(mime, name, isFolder = false, isText = false)) {
            // The preview navigation: this folder's previewable children, in
            // the expansion's own display order (excluded children excluded,
            // subfolders' contents included as their natural paths sort).
            val visibleChildren: List<Pair<String, Long>> = when {
                folder.childManifest != null -> folder.childManifest.map { it.relPath to it.size }
                folder.path != null -> try {
                    val root = File(folder.path!!)
                    val out = ArrayList<Pair<String, Long>>()
                    fun walk(dir: File, prefix: String) {
                        val kids = dir.listFiles() ?: return
                        for (k in kids) {
                            val rel = if (prefix.isEmpty()) k.name else "$prefix/${k.name}"
                            if (k.isDirectory) walk(k, rel)
                            else out.add(rel to k.length())
                        }
                    }
                    walk(root, "")
                    out
                } catch (e: Exception) { emptyList() }
                else -> emptyList()
            }.filter { (p, _) -> p !in folder.excludedChildPaths && folder.excludedChildPaths.none { p.startsWith("$it/") } }
            val members = visibleChildren.filter { (p, _) ->
                val n = p.substringAfterLast('/')
                val m = if (folder.childManifest != null) (folder.childManifest.firstOrNull { it.relPath == p }?.mime ?: "")
                        else MimeTypes.getMimeType(n.substringAfterLast('.', ""))
                queueAdapter.isPreviewableMedia(m, n, isFolder = false, isText = false)
            }
            val start = members.indexOfFirst { it.first == relPath }.coerceAtLeast(0)
            // Position-keyed ids (Pass-30 #4's discipline): unique for this
            // snapshot, resolved back through the same list by the membership
            // lambdas below.
            val items = members.mapIndexed { idx, (p, _) ->
                val n = p.substringAfterLast('/')
                val mc = folder.childManifest?.firstOrNull { it.relPath == p }
                val m = mc?.mime ?: MimeTypes.getMimeType(n.substringAfterLast('.', ""))
                MediaLibraryItem(
                    id = idx.toLong(),
                    uri = mc?.uri?.let(Uri::parse) ?: Uri.fromFile(File(folder.path ?: "", p)),
                    name = n,
                    path = mc?.path ?: folder.path?.let { File(it, p).absolutePath },
                    size = mc?.size ?: 0L,
                    mime = m,
                    dateAddedSec = 0L,
                    isVideo = m.startsWith("video/") || (!m.startsWith("image/") && MimeTypes.isVideo(n.substringAfterLast('.', ""))),
                    durationMs = 0L,
                    bucketId = -1L,
                    bucketName = "",
                )
            }
            if (items.isNotEmpty()) {
                com.lumidrop.x.ui.showMediaPickerSheet(
                    fragment = this,
                    onConfirm = { _, _ -> /* preview-only entry; nothing to confirm */ },
                    queuePreview = items,
                    queuePreviewIndex = start,
                    // The select control carries the folder's exclude/include
                    // semantics: selected mark = still part of the send, one
                    // tap = exclude/re-include through the queue's existing
                    // onExcludeChild path.
                    queueItemIsQueued = { m ->
                        val p = members.getOrNull(m.id.toInt())?.first ?: return@showMediaPickerSheet false
                        p !in folder.excludedChildPaths
                    },
                    onQueueItemFlip = { m, add ->
                        val p = members.getOrNull(m.id.toInt())?.first ?: return@showMediaPickerSheet
                        excludeOrRestoreChild(folder, p, add)
                    },
                )
            } else {
                // No previewable siblings — still honor the tap on the media
                // child itself through a single-item preview.
                com.lumidrop.x.ui.showMediaPickerSheet(
                    fragment = this,
                    onConfirm = { _, _ -> },
                    queuePreview = listOf(MediaLibraryItem(
                        id = 0L, uri = uriStr?.let(Uri::parse) ?: Uri.EMPTY, name = name, path = path,
                        size = 0L, mime = mime, dateAddedSec = 0L,
                        isVideo = mime.startsWith("video/"), durationMs = 0L, bucketId = -1L, bucketName = "")),
                    queuePreviewIndex = 0,
                )
            }
            return
        }
        openFileExternally(uriStr, path, name, mime)
    }

    /** #3's select-control flip inside a folder-child preview: add=false
     *  excludes the child from the folder's send (the queue's existing
     *  onExcludeChild path, by stable identity); add=true re-includes it. */
    private fun excludeOrRestoreChild(folder: PreviewItem, relPath: String, add: Boolean) {
        val key = folder.uri ?: folder.path ?: folder.name
        val idx = previewItems.indexOfFirst { (it.uri ?: it.path ?: it.name) == key }
        if (idx < 0) return
        val cur = previewItems[idx]
        previewItems[idx] = if (add) cur.copy(excludedChildPaths = cur.excludedChildPaths - relPath)
        else cur.copy(excludedChildPaths = cur.excludedChildPaths + relPath)
        updatePreviewCard()
    }

    /** ── Pass-32 (#2): the queue row open router. Previewable media (images ──
     *  and videos) opens the app's own media previewer — the exact path the
     *  old eye used, unchanged. Every OTHER openable queued file (PDFs,
     *  documents, audio, archives, …) opens through Android's native
     *  file-opening mechanism (ACTION_VIEW on a properly-addressed content
     *  URI with the item's MIME type), with graceful feedback for files
     *  that can't be addressed or have no handler installed. */
    private fun onQueueItemOpen(row: QueueRow) {
        val uriStr: String?; val path: String?; val name: String; val mime: String; val isFolder: Boolean; val isText: Boolean
        when (row) {
            is QueueRow.Staged -> {
                uriStr = row.item.uri; path = row.item.path; name = row.item.name
                mime = row.item.mime; isFolder = row.item.isFolder; isText = row.item.textContent != null
            }
            is QueueRow.Live -> {
                uriStr = row.item.contentUri; path = row.item.filePath; name = row.item.name
                mime = row.item.mime
                isFolder = row.item.folderUri != null || row.item.folderPath != null
                isText = row.item.textContent != null
            }
        }
        // The same predicate the previewer's own list build uses: media
        // belongs to the in-app preview (playback controls, navigation,
        // selection), everything else goes to the system.
        if (!isFolder && !isText && queueAdapter.isPreviewableMedia(mime, name, isFolder = false, isText = false)) {
            onQueueMediaPreview(row)
            return
        }
        if (isFolder || isText) return   // folders expand via their own affordance; text isn't file-backed
        // ── GOAT-PASS 14 (PART 7): a multi-component Android package ──
        // archive (.xapk/.apkm/.apks) opens the app's OWN shared install
        // flow — the exact one the Received tab uses (XapkInstallFlow: same
        // inspection, base resolver, split validation, icon pipeline,
        // PackageInstaller session and result handling). An ordinary .apk
        // does NOT match this predicate and keeps Android's own system
        // installer flow below — the GP10 contract, unchanged. Both a plain
        // filesystem path and a content:// address work: content URIs are
        // copied once into the app cache (a bounded, honest bridge — the
        // ZipFile-based inspector needs a seekable file) and the temp copy
        // is deleted when the dialog closes.
        if (com.lumidrop.x.transfer.XapkInstallFlow.isArchiveInstallFormat(name)) {
            openArchiveInstall(uriStr, path, name)
            return
        }
        openFileExternally(uriStr, path, name, mime)
    }

    /** ── GOAT-PASS 14 (PART 7): the Files-sheet entry into the SHARED ─────
     *  archive-install flow. Resolves the tapped row to a readable file and
     *  hands it to XapkInstallFlow — one installation system for Received
     *  and Files alike. */
    private fun openArchiveInstall(uriStr: String?, path: String?, name: String) {
        val ctx = context ?: return
        val scope = viewLifecycleOwner.lifecycleScope
        val direct = path?.let { File(it) }?.takeIf { it.exists() }
        if (direct != null) {
            com.lumidrop.x.transfer.XapkInstallFlow.launch(scope, ctx, direct)
            return
        }
        if (uriStr != null && uriStr.startsWith("content://")) {
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                // Bounded copy: refuse absurdly large SAF documents (the
                // engine streams from ZipFile anyway; a real XAPK is fine).
                val uri = android.net.Uri.parse(uriStr)
                val tmp = File.createTempFile("ldx-arch-", ".xapk", ctx.cacheDir)
                try {
                    ctx.contentResolver.openInputStream(uri)?.use { ins ->
                        tmp.outputStream().use { outs ->
                            val buf = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val n = ins.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > 4L * 1024 * 1024 * 1024) throw IllegalStateException("too large")
                                outs.write(buf, 0, n)
                            }
                        }
                    } ?: throw IllegalStateException("unreadable")
                } catch (e: Exception) {
                    tmp.delete()
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        toast("This file can't be opened here")
                    }
                    return@launch
                }
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    com.lumidrop.x.transfer.XapkInstallFlow.launch(scope, ctx, tmp) {
                        // Dialog finished — clean the bridge copy.
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch { tmp.delete() }
                    }
                }
            }
            return
        }
        toast("This file can't be opened here")
    }

    /** ── Pass-32 (#2): Android's native open flow for a queued non-media ─────
     *  file. Content URIs (SAF/MediaStore picks) are handed over as-is; plain
     *  filesystem paths are re-addressed through the app's FileProvider (the
     *  same provider TransfersFragment's own open flow uses — file:// URIs
     *  may not leave the app on modern Android). Every failure mode is a
     *  toast, never a crash and never a silently-dropped tap: no resolvable
     *  address, a file that vanished from disk, or no installed app for the
     *  type. */
    private fun openFileExternally(uriStr: String?, path: String?, name: String, mime: String) {
        val ctx = context ?: return
        val uri: Uri = when {
            uriStr != null && uriStr.startsWith("content://") -> Uri.parse(uriStr)
            path != null -> {
                val file = File(path)
                if (!file.exists()) {
                    toast("File no longer exists on this device")
                    return
                }
                try {
                    FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
                } catch (e: Exception) {
                    toast("This file can't be opened here")
                    return
                }
            }
            else -> { toast("This file can't be opened here"); return }
        }
        // A meaningful MIME when the row carries one; the extension-derived
        // type otherwise (a generic octet-stream hides the type from
        // intent filters and turns every tap into "no app found").
        val ext = name.substringAfterLast('.', "").lowercase()
        val effectiveMime = if (!mime.isNullOrEmpty() && mime != "application/octet-stream") mime
        else if (ext.isNotEmpty()) MimeTypes.getMimeType(ext)
        else "application/octet-stream"
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, effectiveMime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (intent.resolveActivity(ctx.packageManager) != null) {
                startActivity(intent)
            } else {
                toast("No app found to open this file")
            }
        } catch (e: Exception) {
            // ActivityNotFoundException, SecurityException on odd providers,
            // a crashed resolver — all the same honest answer.
            toast("No app found to open this file")
        }
    }

    /** ── Pass-29 Part 2 (#2): the Queued Files eye control's handler. ─────────
     *  Opens the app's EXISTING media previewer (the Media Picker sheet's
     *  preview overlay, reused whole — pager, filmstrip, arrows, zoom, video
     *  controls) on the tapped row. The preview navigation carries EVERY
     *  image and video currently in the Queued Files section, in the
     *  section's EXACT order: the rows snapshot the queue list is showing
     *  right now (staged items in pick order, then live items — the same
     *  order updatePreviewCard submits), filtered through the SAME
     *  previewable-media predicate that decided the eye's own visibility,
     *  so the filmstrip reads top-to-bottom of the queue, left-to-right,
     *  images and videos interleaved exactly as queued and nothing
     *  re-sorted. The preview lands on the row whose eye was tapped. */
    private fun onQueueMediaPreview(row: QueueRow) {
        if (context == null) return
        // One entry per previewable queued media row, in queue order.
        // ── Pass-30 (#4): [address] is the row's QUEUE IDENTITY — the same
        // (uri ?: path ?: name) key the staging and live representations are
        // matched by everywhere else — and [uriStr] keeps the ORIGINAL string
        // form so a re-select can re-stage the file with exactly the address
        // it was picked with (PreviewItem.uri is a String).
        class QMedia(val key: String, val address: String, val uri: Uri?, val uriStr: String?,
                     val path: String?, val name: String,
                     val size: Long, val mime: String, val isVideo: Boolean)
        fun qMediaOf(prefix: String, uriStr: String?, pathStr: String?, name: String,
                     size: Long, mime: String): QMedia? {
            if (!queueAdapter.isPreviewableMedia(mime, name,
                    isFolder = false, isText = false)) return null
            val m = (mime ?: "").lowercase()
            var video = m.startsWith("video/")
            if (!video) {
                val ext = name.substringAfterLast('.', "").lowercase()
                video = ext.isNotEmpty() && MimeTypes.isVideo(ext)
            }
            return QMedia(
                key = prefix + (uriStr ?: pathStr ?: name),
                address = uriStr ?: pathStr ?: name,
                uri = uriStr?.let(Uri::parse) ?: pathStr?.let { Uri.fromFile(File(it)) },
                uriStr = uriStr,
                path = pathStr, name = name, size = size, mime = mime, isVideo = video)
        }
        val media = ArrayList<QMedia>(8)
        for (r in queueAdapter.currentRows()) {
            when (r) {
                is QueueRow.Staged -> {
                    // The adapter's own predicate already excluded folders and
                    // text items from the eye; the same guard here keeps the
                    // navigation list and the icons in lockstep.
                    if (r.item.isFolder || r.item.textContent != null) continue
                    qMediaOf("s:", r.item.uri, r.item.path, r.item.name, r.item.size, r.item.mime)?.let { media.add(it) }
                }
                is QueueRow.Live -> {
                    if (r.item.textContent != null || r.item.folderUri != null || r.item.folderPath != null) continue
                    qMediaOf("l:", r.item.contentUri, r.item.filePath, r.item.name, r.item.size, r.item.mime)?.let { media.add(it) }
                }
            }
        }
        if (media.isEmpty()) return
        // The tapped row's position — identity by the same prefix+uri/path/name
        // key the entries above were keyed with, so the preview opens on
        // exactly the row whose eye was tapped (first match; the queue's own
        // dedup discipline keeps identities unique).
        val wantedKey = when (row) {
            is QueueRow.Staged -> "s:" + (row.item.uri ?: row.item.path ?: row.item.name)
            is QueueRow.Live -> "l:" + (row.item.contentUri ?: row.item.filePath ?: row.item.name)
        }
        var start = media.indexOfFirst { it.key == wantedKey }
        if (start < 0) start = 0
        // ── Pass-30 (#4): the preview items' ids are their POSITIONS in this ──
        // snapshot — unique, stable for the preview's lifetime (the snapshot
        // list is built exactly once), and hash-free (the old
        // key.hashCode().toLong() could collide between two different queued
        // files). The two membership lambdas below resolve an id straight back
        // to its snapshot entry, so identity can never be ambiguous.
        val items = media.mapIndexed { idx, m ->
            MediaLibraryItem(
                id = idx.toLong(),
                uri = m.uri ?: Uri.EMPTY,
                name = m.name,
                path = m.path,
                size = m.size,
                mime = m.mime,
                dateAddedSec = 0L,
                isVideo = m.isVideo,
                durationMs = 0L,
                bucketId = -1L,
                bucketName = "",
            )
        }
        // ── Pass-30 (#4): the queue-membership bridge the preview's ────────────
        // select/deselect control reads and writes. NO second selection system:
        // the reader answers "is this item still in the Queued Files section
        // right now?" from the queue adapter's LIVE rows (the same rows the
        // list renders, same address identity), and the flip applies the
        // user's toggle through the queue's EXISTING paths —
        //   • deselect (queued → not) is EXACTLY the row X's own action:
        //     a staged item leaves previewItems via the same
        //     onRemoveStaged-style removal, a live item through the server's
        //     removeFromQueueByName (the X control's own call);
        //   • select (not → queued) re-stages the file through the same
        //     stageOrSendItem staging path every picker/folder/text pick uses.
        // Both directions end in updatePreviewCard — the single writer that
        // keeps the rows, the counter pill and the shared card's face in
        // lockstep with the queue's real state.
        fun queuedAddresses(): Set<String> {
            val out = HashSet<String>(media.size * 2)
            for (r in queueAdapter.currentRows()) {
                when (r) {
                    is QueueRow.Staged -> out.add(r.item.uri ?: r.item.path ?: r.item.name)
                    is QueueRow.Live -> out.add(r.item.contentUri ?: r.item.filePath ?: r.item.name)
                }
            }
            return out
        }
        com.lumidrop.x.ui.showMediaPickerSheet(this, { _, _ -> /* queue preview: no picker confirm path */ },
            queuePreview = items, queuePreviewIndex = start,
            queueItemIsQueued = { mli ->
                val entry = media.getOrNull(mli.id.toInt())
                entry != null && queuedAddresses().contains(entry.address)
            },
            onQueueItemFlip = { mli, wantQueued ->
                val entry = media.getOrNull(mli.id.toInt())
                if (entry != null) {
                    if (!wantQueued) {
                        // Deselect = remove from the queue — the row X's own action,
                        // applied to whichever representation holds the item.
                        val stagedHit = previewItems.firstOrNull { (it.uri ?: it.path ?: it.name) == entry.address }
                        if (stagedHit != null) {
                            previewItems.remove(stagedHit)
                        } else {
                            server?.removeFromQueueByName(entry.name)
                        }
                        updatePreviewCard()
                    } else {
                        // Select = put it back — the same staging path every other
                        // pick uses (a live item that was removed re-enters as a
                        // staged item, exactly like a fresh pick of that file).
                        stageOrSendItem(PreviewItem(entry.name, entry.path, entry.uriStr, entry.size, entry.mime))
                    }
                }
            })
    }

    // Phase 18 V8: browser clients and native (P2P) peers now render in one
    // unified list/card instead of two separate ones -- see DeviceAdapter's
    // own doc comment for why, and updateDevicesUI() below for how the two
    // sources are merged into it.
    private val deviceAdapter: DeviceAdapter by lazy {
        DeviceAdapter(
            onRenameBrowser = { ip, currentName -> showRenameDialog(ip, currentName) },
            // Phase 5 (§5.3): absolute segment semantics — setDeviceMode is
            // invoked with exactly the mode whose segment was tapped, then
            // the list immediately re-renders from the authoritative read
            // (getDeviceMode). A rapid double-tap on the same segment is a
            // no-op re-assert, and a double-tap split across segments lands
            // deterministically on the last one tapped.
            onSetMode = { ip, mode ->
                val srv = server ?: return@DeviceAdapter
                srv.setDeviceMode(ip, mode)
                updateDevicesUI()
            },
            onKickBrowser = { ip, displayName -> showKickConfirm(ip, displayName) },
        )
    }

    private val legacyStorageLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { startServer() }
    private val manageAllFilesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()) { startServer() }
    private val mediaPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()) { startServer() }
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()) { }
    // ── Pass-33 (#6): the system file/folder pickers (GetMultipleContents /
    // OpenDocumentTree) are RETIRED — the Files tile opens the app's own
    // unified Files picker (see btnQuickShare's wiring), and folder
    // selection lives inside it, so neither launcher nor enqueueFolder's
    // SAF ceremony has a caller anymore.

    // Lifecycle ---------------------------------------------------------------

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSendBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // ── GOAT-PASS 12 (§10): the Send tab is the REPORTED Android 5 crash ──────────
        // point — every step of its initialization gets a breadcrumb so the
        // diagnostic log shows exactly how far a crashing launch got (the
        // last line before the crash IS the evidence Pass 13 needs).
        binding.devicesEmptyIllustration.sizeDp = resources.getDimensionPixelSize(R.dimen.devices_empty_illustration_size).let {
            (it / resources.displayMetrics.density).toInt()
        }
        setupButtons()
        // ── Pass-36 Part 2 (#7): register the Server Card's title-row slot as ──
        // the compact music pill's dock. While music is loaded and the player
        // is collapsed, the pill lives HERE — right of "LumiDrop X" — and the
        // Send tab's view lifetime governs it (detached in onDestroyView).
        // ── Pass-38 (#7): the pill now docks on ALL three tabs; this slot is ──
        // simply THIS tab's dock — re-registered from onBecameVisible every
        // time the Send tab becomes the visible one. The !isHidden guard
        // covers the restore case (the shell re-creates every tab's view on a
        // config change, and the VISIBLE tab at that moment may not be Send).
        if (!isHidden) com.lumidrop.x.ui.MusicPlayback.attachCompactHost(binding.serverCardMusicSlot)
        // Pass-6 (R-E): one global-layout listener owns the micro-scroll clamp —
        // every layout pass (card toggles, list growth, rotation, inset change)
        // re-derives the excess from scratch. Removed in onDestroyView.
        binding.sendScroll.viewTreeObserver.addOnGlobalLayoutListener(microScrollGuard)
        // ── Pass-26 (#6): re-latch the Connected Devices card the MOMENT the ──
        // viewport resizes. The card's height is a per-viewport PIN
        // (pinnedDevicesCardHeightPx), and hiding/showing the Android
        // navigation buttons resizes the window — but the re-latch previously
        // only happened on the next poll tick, and never at all while another
        // tab was showing (the poll is cancelled on hide), so the card kept
        // its stale buttons-visible height after the "Hide Navigation Bar"
        // toggle until some later event happened to re-derive it. This
        // listener closes that gap: ANY real viewport change (nav buttons
        // hidden/restored, rotation, keyboard, split-screen) invalidates the
        // pin and re-applies it immediately, so the card always sizes itself
        // against the space that is actually available now. No-op when
        // nothing changed — the card's appearance with the buttons VISIBLE is
        // byte-for-byte what it always was.
        binding.sendScroll.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (v.width > 0 && v.height > 0) onSendViewportChanged(v.width, v.height)
        }
        // ── Pass-27 (#4.1): the Send column's bottom clearance tracks the REAL ──
        // navigation-bar inset. MainActivity's window is edge-to-edge on API 30+
        // (see its onCreate), which means the window frame no longer ends above
        // the Android buttons — THIS code reserves their zone instead, by adding
        // the live navigationBars inset to the content column's nav_bar_clearance
        // bottom padding. The two states the "Hide Navigation Bar" preference
        // produces then fall out of the geometry automatically:
        //   • buttons VISIBLE → inset = real bar height → clearance grows by it —
        //     the exact space the window frame itself used to reserve, so the
        //     layout with the preference OFF is byte-for-byte what it always was;
        //   • buttons HIDDEN → inset = 0 → clearance drops to the base value and
        //     every pixel the buttons occupied returns to the card — the true
        //     Gesture-Navigation look, with no stale dimensions and no restart.
        // On API < 30 nothing here runs: the legacy window frame already excludes
        // (or, with the preference on, extends behind) the bar, and the legacy
        // LAYOUT_HIDE_NAVIGATION recipe carries that side by itself.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ViewCompat.setOnApplyWindowInsetsListener(binding.sendScroll) { _, insets ->
                val nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
                applySendBottomClearance(nav)
                insets   // pass the dispatch on untouched — siblings (the other tabs' roots) track their own clearance
            }
        }
        requestNotifPermission()
        // ── Pass-26 (#5): Automatic Server Startup ──────────────────────────────
        // The launch-time auto-start is now gated by its Settings toggle (ON
        // by default — the exact behavior every earlier pass shipped). With
        // the toggle off, a fresh launch leaves the server STOPPED until the
        // person presses the card's own Start button, which runs this SAME
        // permission-checked start path unchanged. Nothing else about the
        // lifecycle changes: the toggle is read only here (launch), and
        // flipping it in Settings never stops an already-running server.
        if (SettingsManager.isAutoServerStart(requireContext())) {
            checkPermissionsAndStart()
        } else {
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // FINAL PASS 9 (PART 1): the Connected Devices card's height pin is
        // per-viewport; with configChanges there is no auto re-derive — the
        // existing sendScroll layout listener fires on the resize itself,
        // so nothing extra is needed beyond keeping the micro-scroll clamp
        // honest for the new frame.
        if (_binding != null) clampSendMicroScroll()
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) onBecameVisible()
    }

    override fun onPause() {
        super.onPause()
        onBecameHidden()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) onBecameHidden() else if (isResumed) onBecameVisible()
    }

    private fun onBecameVisible() {
        // ── Pass-38 (#7): THIS tab is the visible one again — its Server Card ──
        // title-row slot is the pill's dock once more (attachCompactHost
        // replaces the previous tab's slot; the one shared player re-parents
        // its one compact representation, nothing is duplicated).
        com.lumidrop.x.ui.MusicPlayback.attachCompactHost(binding.serverCardMusicSlot)
        // ── Pass-27 (#4.1): a tab that was HIDDEN may have missed an insets ────
        // dispatch while the "Hide Navigation Bar" preference (or a system bar
        // show/hide) changed the navigation-bar inset — ask for a fresh dispatch
        // so the Send column's bottom clearance and the card pin re-derive
        // against the geometry that is actually live NOW. Idempotent: the
        // listener no-ops when nothing changed, and on API < 30 there is no
        // listener at all.
        try { binding.sendScroll.requestApplyInsets() } catch (e: Exception) { /* view not attached yet */ }
        updateUI()
        // ── Pass-29 Part 1 (#4C): the transfer quick toggles re-read the ONE ────
        // shared preference on every return to this tab, so a change saved in
        // Settings is reflected by the header icons the moment the Send tab is
        // visible again (the tab model means this is the earliest honest
        // moment — Settings edits persist on Save, never live).
        paintQuickToggles()
        updatePreviewCard()
        startPolling()
    }

    /** ── Pass-32 (#4): blackout height, made correct BY CONSTRUCTION. ────────
     *  The Pass-30 exit strategy (re-derive immediately + once more after
     *  450ms) still left a window the reported bug walked through: bar
     *  restoration is ANIMATED, a re-derivation that lands mid-animation
     *  mints a pin from transient geometry, and with ZERO devices connected
     *  and the server stopped NOTHING ever recomputes again — startPolling's
     *  `if (!isRunning) continue` means no poll tick, so whatever number the
     *  exit sequence last applied stayed applied until some unrelated sheet
     *  or modal happened to churn a layout (the reported "fixed only by
     *  opening another sheet"; with devices connected the server is running,
     *  the 500ms poll recomputes the pin twice a second, and the bug could
     *  never survive — which is exactly the observed "only happens when no
     *  devices are connected").
     *
     *  The permanent fix removes the dependence on re-derivation timing
     *  altogether for the one transition whose END STATE IS KNOWN: blackout
     *  changes NOTHING about the Send tab's content — the bars return to
     *  exactly the state they left, so the card height that was correct the
     *  instant BEFORE blackout is, by construction, the correct height the
     *  instant AFTER it. So:
     *    • onBlackoutEntered() snapshots the card's LIVE applied height plus
     *      the (vpW, vpH, clearance) keys it was derived from;
     *    • onBlackoutExited() writes that snapshot straight back into the
     *      latch AND the card's layout params — correct on the FIRST frame
     *      after exit, no animation frame, dispatch, or delay involved;
     *    • a `postBlackoutRestorePending` guard then freezes the pin (exactly
     *      like the during-blackout freeze) until the settle pass ~600ms
     *      later, so the mid-animation viewport frames and any updateDevicesUI
     *      call that lands inside the window can neither re-mint a transient
     *      pin nor drop the restored one;
     *    • the settle pass compares the LIVE geometry against the snapshot's
     *      keys: identical (the normal case — same bars, same rotation) →
     *      nothing to do, the restored pin is already the deterministic
     *      answer; genuinely different (the system rotated the device or
     *      changed the bar mode while blacked out) → drop the latch and
     *      re-derive from the now-final honest geometry, exactly as a
     *      rotation always has.
     *  No arbitrary delays govern correctness — the 600ms pass is only the
     *  genuine-change DETECTOR; the restored height is right the moment
     *  blackout ends, and stays right whether the next recompute arrives in
     *  half a second or never (zero devices, stopped server). */
    private var preBlackoutCardHeightPx = 0
    private var preBlackoutVpW = 0
    private var preBlackoutVpH = 0
    private var preBlackoutClearance = -1
    private var postBlackoutRestorePending = false

    /** ── Pass-32 (#4): MainActivity calls this the instant blackout STARTS ───
     *  (flag already true, overlay still fading in). Snapshots the card's
     *  currently-applied height — the LIVE laid-out number the user sees and
     *  accepts as correct — plus the pin-latch keys it belongs to. Safe to
     *  call at any moment: the value read is whatever the card is rendering
     *  right now, which by definition was produced by the pre-blackout
     *  (honest, non-frozen) geometry. */
    fun onBlackoutEntered() {
        if (_binding == null) return
        preBlackoutCardHeightPx = binding.cardDevices.height
        preBlackoutVpW = devicesCardPinVpW
        preBlackoutVpH = devicesCardPinVpH
        preBlackoutClearance = devicesCardPinClearance
    }

    fun onBlackoutExited() {
        if (_binding == null || !isAdded) return
        // The restore: the card goes back to its pre-blackout height NOW, on
        // the first frame after exit — not on a re-derivation that could land
        // mid-animation. The latch keys ride along so nothing downstream sees
        // a "stale latch" it feels compelled to recompute.
        postBlackoutRestorePending = true
        if (preBlackoutCardHeightPx > 0) {
            devicesCardPinnedPx = preBlackoutCardHeightPx
            devicesCardPinVpW = binding.sendScroll.width
            devicesCardPinVpH = binding.sendScroll.height
            devicesCardPinClearance = sendBottomClearancePx
            val cardLp = binding.cardDevices.layoutParams as? LinearLayout.LayoutParams
            if (cardLp != null && (cardLp.height != preBlackoutCardHeightPx || cardLp.weight != 0f)) {
                cardLp.height = preBlackoutCardHeightPx
                cardLp.weight = 0f
                binding.cardDevices.layoutParams = cardLp
            }
        }
        // Fresh insets for THIS view — the authoritative current bar state
        // (also re-delivers the clearance the freeze withheld during blackout).
        try { binding.sendScroll.requestApplyInsets() } catch (e: Exception) { /* not attached yet */ }
        // The genuine-change detector: after the bar restoration has certainly
        // settled (~300ms animations + margin), compare the LIVE geometry
        // against what the snapshot was minted from. Identical → the restored
        // pin IS the deterministic answer; different (a rotation or bar-mode
        // change that happened while blacked out) → re-derive honestly.
        view?.postDelayed({
            if (_binding != null && isAdded && !blackoutActive() && postBlackoutRestorePending) {
                postBlackoutRestorePending = false
                val w = binding.sendScroll.width
                val h = binding.sendScroll.height
                if (preBlackoutCardHeightPx > 0 &&
                    (w != preBlackoutVpW || h != preBlackoutVpH || sendBottomClearancePx != preBlackoutClearance)) {
                    try { binding.sendScroll.requestApplyInsets() } catch (e: Exception) { /* detached mid-flight */ }
                    reassertGeometryAfterBlackout()
                }
            }
        }, 600)
    }

    /** Pass-30 (#1): one honest re-derivation pass — drop the (possibly
     *  mid-animation-minted) latch and rebuild clearance + pin from the
     *  CURRENT viewport, then apply. No-op in effect when the geometry
     *  already agrees (the deterministic computation returns the same
     *  number and the apply writes nothing), so ordinary flows never churn. */
    private fun reassertGeometryAfterBlackout() {
        if (_binding == null) return
        val w = binding.sendScroll.width
        val h = binding.sendScroll.height
        if (w <= 0 || h <= 0) return
        // Zero the latch unconditionally: a latch minted from a mid-animation
        // frame can KEY-match the final geometry forever (the exact trap that
        // kept the short pin sticky). Re-latching from live geometry every
        // time this runs is what makes the pass deterministic.
        devicesCardPinnedPx = 0
        devicesCardPinVpW = 0
        devicesCardPinVpH = 0
        devicesCardPinClearance = -1
        updateDevicesUI()
    }

    /** ── Pass-29 Part 1 (#4): the quick toggles' single painter. Reads the ─────
     *  ONE shared preference pair the Settings tab's own toggles persist
     *  through and tints the two header icons accordingly — accent when the
     *  feature is on (the same active-accent language as the device-count
     *  badge), text_secondary when off. Every state change funnels through
     *  here (setup, tab return, the icons' own taps), so the icons can never
     *  drift from the persisted truth.
     *  ── GOAT-PASS 14 Phase 2 (§2/§4): the ZIP sending mode joined this one ──
     *  painter. BOTH of its exposures — the Nearby Devices header's
     *  btnZipModeToggle and the Queue Files top row's btnZipModeToggleQueue —
     *  are tinted from the SAME single read of the ONE preference
     *  (zip_send_enabled) the Settings screen's own switch persists, in the
     *  identical accent-on / secondary-off language. Because every state
     *  change (either icon's tap, a Settings-screen Save, a tab return)
     *  funnels through this painter, the two icons can never disagree, and
     *  toggling from either location updates the other immediately. */
    private fun paintQuickToggles() {
        if (_binding == null) return
        val ctx = requireContext()
        binding.btnAutoSendToggle.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(ctx, if (SettingsManager.isAutoSendEnabled(ctx)) R.color.accent else R.color.text_secondary))
        binding.btnAutoAcceptToggle.imageTintList = android.content.res.ColorStateList.valueOf(
            ContextCompat.getColor(ctx, if (SettingsManager.isAutoAcceptUploads(ctx)) R.color.accent else R.color.text_secondary))
        // ── GOAT-PASS 14 Phase 2 (§2/§4): one preference read paints BOTH ZIP ──
        // icons (the 24dp Nearby Devices header cell and the 32dp Queue Files
        // row cell) — the same active/inactive tint language as the toggles
        // beside them, never any separate per-location state.
        val zipTint = ContextCompat.getColor(ctx,
            if (SettingsManager.isZipSendEnabled(ctx)) R.color.accent else R.color.text_secondary)
        binding.btnZipModeToggle.imageTintList = android.content.res.ColorStateList.valueOf(zipTint)
        binding.btnZipModeToggleQueue.imageTintList = android.content.res.ColorStateList.valueOf(zipTint)
    }

    /** ── GOAT-PASS 14 Phase 2 (§2/§3): the ZIP sending mode's ONE toggle path. ──
     *  Both icon exposures (Nearby Devices header + Queue Files row) call this;
     *  it writes the ONE Phase 1 preference (zip_send_enabled) the Settings
     *  screen's switch also persists — no second setting, no duplicated state.
     *
     *  Disabling is the plain preference flip + repaint + toast, exactly like
     *  Auto Send / Auto Accept: an in-flight transfer is owned by the Phase 1
     *  engines (the mode is captured at each send operation's start and the
     *  batch snapshot is frozen then), so turning the mode off mid-transfer
     *  cannot change any already-started transfer — it only shapes the NEXT
     *  send operation.
     *
     *  Enabling runs through the FIRST-TIME explanation contract (§3): the
     *  very first enable ever (zip_send_explained_ack not yet latched) shows
     *  the "Send as ZIP" explanation modal FIRST, and the setting only flips
     *  on the modal's explicit Enable action — Cancel or a backdrop dismissal
     *  leaves ZIP mode OFF (never silently enabled). Confirming latches the
     *  acknowledgment (persisted through the existing settings store), so a
     *  later disable → re-enable never shows the explanation again. The
     *  explanation never touches Auto Send or Auto Accept and never blocks
     *  anything outside its own modal window. */
    private fun toggleZipSendMode() {
        val ctx = requireContext()
        if (SettingsManager.isZipSendEnabled(ctx)) {
            SettingsManager.setZipSendEnabled(ctx, false)
            paintQuickToggles()
            toast("ZIP sending off")
            return
        }
        if (SettingsManager.isZipSendExplained(ctx)) {
            SettingsManager.setZipSendEnabled(ctx, true)
            paintQuickToggles()
            toast("ZIP sending on — next send becomes one ZIP")
            return
        }
        // ── GOAT-PASS 15 (§5): the first-time explanation — benefits AND
        // limitations, one clear action. "Understood" acknowledges AND
        // enables in a single tap; dismissing the modal any other way
        // (backdrop, back) leaves ZIP mode OFF exactly as it was, and the
        // explanation will simply reappear on the next first-time enable
        // attempt (the acknowledgment latch is only ever written by the
        // Understood action itself — nothing is enabled or acknowledged
        // without it).
        showLdxDialog(
            ctx,
            title = "Send as ZIP",
            bodyView = buildZipExplanationBody(ctx),
            iconRes = R.drawable.ic_zip_mode,
            iconTintRes = R.color.accent_light,
            actions = listOf(
                com.lumidrop.x.ui.LdxDialogAction("Understood", com.lumidrop.x.ui.LdxButtonStyle.PRIMARY) {
                    SettingsManager.setZipSendEnabled(ctx, true)
                    SettingsManager.setZipSendExplained(ctx, true)
                    paintQuickToggles()
                    toast("ZIP sending on — next send becomes one ZIP")
                },
            ),
        )
    }

    /** ── GOAT-PASS 15 (§5): the first-time "Send as ZIP" explanation body —
     *  the one-paragraph description, then the app's own visual language for
     *  the BENEFITS and LIMITATIONS sections (small caption + compact bullet
     *  lines, the same 13.5sp/12sp text scale every LdxDialog message uses).
     *  Every limitation listed reflects the ACTUAL implementation: the
     *  archive is synthesized live while sending, an interrupted ZIP never
     *  byte-resumes (the automatic retry restarts it from the beginning —
     *  unknown-length archives have no stable per-offset identity), a failed
     *  ZIP is re-sent through the established Resend flow, and the receiver
     *  gets one archive item it must extract. */
    private fun buildZipExplanationBody(ctx: android.content.Context): android.view.View {
        fun col(res: Int) = androidx.core.content.ContextCompat.getColor(ctx, res)
        fun dp(n: Int) = ctx.dp(n)
        val root = android.widget.LinearLayout(ctx).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        fun para(text: String, colorRes: Int = R.color.text_secondary, size: Float = 13.5f, bold: Boolean = false) =
            android.widget.TextView(ctx).apply {
                this.text = text; textSize = size
                setTextColor(col(colorRes))
                if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
                setLineSpacing(dp(5).toFloat(), 1f)
                layoutParams = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(12) }
            }
        fun caption(text: String) = android.widget.TextView(ctx).apply {
            this.text = text; textSize = 11f
            setTextColor(col(R.color.accent_light))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing = 0.09f
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(6) }
        }
        fun bullet(text: String) = android.widget.TextView(ctx).apply {
            this.text = "·  $text"; textSize = 12.5f
            setTextColor(col(R.color.text_secondary))
            setLineSpacing(dp(4).toFloat(), 1f)
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.marginStart = dp(6); it.bottomMargin = dp(7)
            }
        }
        root.addView(para("Selected items will be combined into a single ZIP file before being sent. The receiver will receive one ZIP file instead of separate files."))
        root.addView(caption("BENEFITS"))
        root.addView(bullet("Sending many small files can be faster because they are combined into one transfer."))
        root.addView(bullet("The receiver gets one organized archive instead of many individual incoming items."))
        root.addView(bullet("You can combine eligible items from the Quick Share queue into one ZIP."))
        root.addView(caption("LIMITATIONS"))
        root.addView(bullet("ZIP mode may take additional time to prepare the archive before or during transmission."))
        root.addView(bullet("If the network connection is interrupted, the ZIP transfer cannot resume from where it stopped — it restarts from the beginning."))
        root.addView(bullet("A failed ZIP transfer may need to be sent again through Resend."))
        root.addView(bullet("The receiver must extract the ZIP to access its individual files."))
        root.addView(bullet("The transfer is represented as one ZIP item rather than separate individual transfers."))
        return root
    }

    /** ── Pass-29 Part 1 (#4D): enabling Auto Accept immediately processes every ──
     *  eligible pending incoming file — through the EXACT accept-all path the
     *  Incoming modal's own button uses (server-side eligibility rules, the
     *  status-gated "pending" filter that makes double acceptance impossible,
     *  and the pre-registered TransferManager rows that keep every accepted
     *  file's state consistent). A plain no-op when nothing is pending or the
     *  server isn't running. */
    private fun acceptPendingIncomingNow() {
        val srv = server ?: return
        if (!isRunning) return
        if (srv.pendingIncomingCount() <= 0) return
        srv.acceptAllUploads()
        srv.acceptAllNativePush()
    }

    private fun onBecameHidden() {
        pollJob?.cancel()
        pulseAnimator?.let { Widgets.stopPulse(binding.tvStatusDot, it) }
    }

    override fun onDestroyView() {
        pollJob?.cancel()
        pulseAnimator?.cancel()
        try { binding.sendScroll.viewTreeObserver.removeOnGlobalLayoutListener(microScrollGuard) } catch (e: Exception) { /* view already torn down */ }
        // ── Pass-36 Part 2 (#7): the pill's dock dies with this view — release ──
        // it before the binding does, so the player never holds a dead slot.
        com.lumidrop.x.ui.MusicPlayback.detachCompactHost(binding.serverCardMusicSlot)
        // ── Pass-40 (#3): the Music-sheet opener hook dies with this view ──
        // too — a destroyed fragment must never be asked to open a sheet.
        com.lumidrop.x.ui.MusicPlayback.musicSheetOpener = null
        _binding = null
        super.onDestroyView()
    }

    // Button wiring -------------------------------------------------------------

    private fun setupButtons() {
        binding.btnStartStop.setOnClickListener {
            if (isRunning) stopServer() else checkPermissionsAndStart()
        }

        binding.btnCopyUrl.setOnClickListener {
            // Pass-7: copy respects the multi-address reality — one enabled
            // address copies instantly; several open the SAME labeled list
            // the QR modal uses so it's always evident WHICH address is being
            // copied (and where it came from).
            val urls = serverUrlsForActions()
            when {
                urls.isEmpty() -> toast("Server is not running")
                urls.size == 1 -> copyUrlToClipboard(urls[0].second)
                else -> showCopyUrlDialog(urls)
            }
        }

        binding.btnQrToggle.setOnClickListener {
            if (!isRunning) { toast("Start the server first"); return@setOnClickListener }
            val urls = serverUrlsForActions()
            when {
                urls.isEmpty() -> toast("No address available yet")
                else -> showQrDialog(activeQrUrl ?: urls.first().second)
            }
        }

        binding.btnMoreUrls.setOnClickListener {
            // The fixed-height row only ever SHOWS the primary address; this
            // chevron is where every other running address lives (labeled,
            // copyable, QR-able per row).
            val urls = serverUrlsForActions()
            if (urls.isEmpty()) toast("No address available yet")
            else showServerAddressesDialog(urls)
        }

        binding.btnHotspotSettings.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) }
            catch (e: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS)) }
        }


        binding.btnBlackout.setOnClickListener { (requireActivity() as MainActivity).enterBlackout() }

        // ── Pass-29 Part 1 (#4): the two transfer quick toggles ─────────────────
        // Icon-only header controls for Auto Send and Auto Accept. Both write
        // the ONE shared SettingsManager preference the Settings tab's own
        // toggles persist through (no duplicated state — #4C), answer with a
        // short toast, and engage the existing systems immediately on enable:
        //   • Auto Send ON → updatePreviewCard's Auto-Send flush, which routes
        //     every staged file through autoSendOrPickTargets — the SAME entry
        //     decision a fresh pick uses, including the Send To recipient
        //     selection when multiple devices are connected (#4E / #3);
        //   • Auto Accept ON → the exact accept-all path the Incoming modal's
        //     own button uses, server-side eligibility and all (#4D).
        // Disabling is the preference flip alone: an in-flight transfer is
        // owned by TransferManager/the server, never by this switch, so
        // turning it off cannot corrupt anything already moving — it only
        // changes what happens to the NEXT arrivals/picks, which is the
        // established behavior of both Settings toggles.
        binding.btnAutoSendToggle.setOnClickListener {
            val now = !SettingsManager.isAutoSendEnabled(requireContext())
            SettingsManager.setAutoSendEnabled(requireContext(), now)
            paintQuickToggles()
            toast(if (now) "Quick Send on" else "Quick Send off")
            if (now) updatePreviewCard()
        }
        binding.btnAutoAcceptToggle.setOnClickListener {
            val now = !SettingsManager.isAutoAcceptUploads(requireContext())
            SettingsManager.setAutoAcceptUploads(requireContext(), now)
            paintQuickToggles()
            toast(if (now) "Quick Accept on" else "Quick Accept off")
            if (now) acceptPendingIncomingNow()
        }
        // ── GOAT-PASS 14 Phase 2 (§2/§4): the ZIP sending mode's TWO icon ────────
        // exposures, one shared toggle path. Both the Nearby Devices header
        // icon and the Queue Files top-row icon route through the SAME
        // toggleZipSendMode (one preference, one painter, one first-time
        // explanation contract), so a tap on either updates both instantly and
        // neither can drift from the Settings screen's own switch either. The
        // touch-disallow guard below mirrors the Refresh / Block / switch
        // controls' own — a control press must never scroll the card behind it.
        binding.btnZipModeToggle.setOnClickListener { toggleZipSendMode() }
        binding.btnZipModeToggleQueue.setOnClickListener { toggleZipSendMode() }
        binding.btnZipModeToggle.setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }
        binding.btnZipModeToggleQueue.setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }
        paintQuickToggles()

        binding.btnQuickShare.setOnClickListener {
            if (!isRunning) { toast("Start the server first"); return@setOnClickListener }
            // ── Pass-33 (#6): the Files tile opens LumiDrop X's OWN unified ────
            // file/folder browser now — one integrated browsing + selection
            // experience replacing the two SYSTEM pickers this tile and the
            // old Folder tile used to launch (ACTION_OPEN_DOCUMENT /
            // OpenDocumentTree). The sheet hands back ready-made PreviewItems
            // (folders as path-backed folder items, files with real
            // name/size/mime), staged through the SAME single entry point
            // every other pick uses — so dedup, the counter pill, Auto Send
            // and both transfer engines behave exactly as before.
            // ── Pass-41 (§7): a FILE row's body tap in the sheet now OPENS the
            // file (openStandaloneFile below); selection stays on the icon.
            com.lumidrop.x.ui.showFilesPickerSheet(this, onOpenFile = { f -> openStandaloneFile(f.absolutePath, f.name) }) { items -> stageOrSendItems(items) }
        }
        binding.btnQuickShareImage.setOnClickListener {
            if (!isRunning) { toast("Start the server first"); return@setOnClickListener }
            // Part 4.3: was imagePicker.launch("image/*") -- the bare
            // system content picker, images only, no albums, no visible
            // selection state. showMediaPickerSheet's result Uris are fed
            // through the exact same enqueueUris() every other picker
            // result in this file already goes through, so staging
            // behavior (name/size/real-path resolution, dedup, and now
            // batched native-push delivery -- see item 3) is identical no
            // matter which picker the person used.
            // Pass-29 Part 2 (#2): the onConfirm lambda is passed EXPLICITLY now
            // (the sheet's signature gained the queue-preview parameters after
            // it, so it is no longer the trailing-lambda slot).
            com.lumidrop.x.ui.showMediaPickerSheet(this, { uris, albums -> enqueueUris(uris); enqueueAlbums(albums) })
        }
        binding.btnShareMusic.setOnClickListener {
            if (!isRunning) { toast("Start the server first"); return@setOnClickListener }
            // ── Pass-33 (#7): the Music tile — the Folder tile's replacement, ──
            // opening LumiDrop X's own audio picker (browse by folder, search,
            // sort, select files or whole folders, built-in playback preview).
            com.lumidrop.x.ui.showMusicPickerSheet(this) { items -> stageOrSendItems(items) }
        }
        // ── Pass-40 (#3) + Pass-41 (§6): the floating player's Music-sheet ──
        // OPENER — a tap on the playback card's main container navigates here.
        // The exact tile behavior (the server gate, the same staging callback),
        // plus the playing TRACK so the sheet opens already scrolled to it —
        // matched by its full canonical identity set (see the Music sheet's
        // focusOnTrack), so a song started from the Queue reveals the same
        // row a Music-sheet start would. Registered for the fragment's view
        // lifetime and cleared on destroy, so a dead fragment can never be
        // asked to open a sheet. The sheet itself, while live, takes priority
        // (its own in-place scroll handler runs first — see
        // MusicPlayback.requestMusicSheetFocus).
        com.lumidrop.x.ui.MusicPlayback.musicSheetOpener = { track ->
            if (isAdded && isRunning) {
                com.lumidrop.x.ui.showMusicPickerSheet(this, focusTrack = track) { items -> stageOrSendItems(items) }
            } else if (isAdded) {
                toast("Start the server first")
            }
        }
        binding.btnQuickShareText.setOnClickListener {
            if (!isRunning) { toast("Start the server first"); return@setOnClickListener }
            showTextShareDialog()
        }
        binding.btnQuickSharePaste.setOnClickListener {
            if (!isRunning) { toast("Start the server first"); return@setOnClickListener }
            val clip = (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            // Phase 6 (§6.6): pasted text is sent EXACTLY as it sits in the
            // clipboard — blank-only is still rejected, but real leading/
            // trailing whitespace (incl. newlines) survives the send instead
            // of being silently trimmed away, matching the text modal's own
            // fidelity rule below.
            val text = if (clip.hasPrimaryClip() && (clip.primaryClip?.itemCount ?: 0) > 0)
                clip.primaryClip?.getItemAt(0)?.coerceToText(requireContext())?.toString()
            else null
            if (text.isNullOrEmpty() || text.isBlank()) {
                toast("Clipboard is empty or doesn't contain text")
            } else {
                // ── Pass-36 Part 2 (#1): the queue title is a preview of the ──
                // ACTUAL pasted content (first words of it, whitespace
                // normalized — see textPreviewTitle), not the generic
                // "Text message" label. The full clipboard text still rides
                // the item verbatim as textContent for previewing and sending.
                stageOrSendItem(PreviewItem(textPreviewTitle(text), null, null, text.length.toLong(),
                    "text/plain", textContent = text))
            }
        }
        binding.btnShareApk.setOnClickListener {
            if (!isRunning) { toast("Start the server first"); return@setOnClickListener }
            showApkShareDialog()
        }

        binding.btnSendShare.setOnClickListener { showTargetPickerAndSend() }
        binding.btnClearShare.setOnClickListener {
            server?.clearQuickShare()
            previewItems.clear()
            updatePreviewCard()
        }
        // ── Pass-25 (#5.4): the shared card's view-switch control, both ────────
        // faces. From Queued Files: the user deliberately wants Connected
        // Devices — the manual choice sticks across every poll tick while the
        // selection remains (see updatePreviewCard's rule). From Devices: back
        // to Queued Files, which also clears the manual choice. Neither touch
        // affects the selection, the queue's contents, or any adapter state —
        // only which face of the shared card is visible.
        binding.btnSwitchCardView.setOnClickListener {
            queueManualDevices = true
            setSharedCardView(false)
        }
        binding.btnSwitchCardViewDevices.setOnClickListener {
            queueManualDevices = false
            setSharedCardView(true)
        }

        binding.btnRefreshDevices.setOnClickListener { v ->
            // Pass-7 (genuine refresh): a tap now FORCES the truth, it
            // doesn't just redraw the cache:
            //  1. WEB — forceRefreshSessions(): immediate sweep + a state
            //     push that makes writing to a closed browser's socket fail
            //     (dropping its stream now, not at the 30s stall-drop), then
            //     a prune pass one probe window later that terminates every
            //     session with no live stream and no touch — a browser
            //     closed seconds ago leaves the list on THIS tap instead of
            //     riding its 30s visibility TTL;
            //  2. NATIVE — requestImmediateBeacon() (live peers' own 1.5s
            //     beacons refresh us within moments) plus
            //     forceExpireStalePeers(): peers 12s+ silent (app swiped
            //     away) drop now instead of greying out through the 40s
            //     expiry window;
            //  3. the control stays busy across the whole probe window and
            //     re-renders land at each checkpoint, so the pruned list is
            //     what's on screen when the spin settles.
            // Nothing here disconnects anyone or touches a live transfer.
            v.isEnabled = false
            v.postDelayed({ v.isEnabled = true }, 4200)
            try {
                val anim = android.view.animation.RotateAnimation(0f, 1080f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f).apply {
                    duration = 3600; repeatCount = 0
                    interpolator = android.view.animation.LinearInterpolator()
                }
                v.startAnimation(anim)
            } catch (e: Exception) { /* purely cosmetic */ }
            server?.forceRefreshSessions()
            NativeDiscovery.requestImmediateBeacon()
            NativeDiscovery.forceExpireStalePeers()
            updateDevicesUI()
            // Checkpoints: right after the immediate expiry pass, then just
            // past the server's 3.5s prune, then a settle beat — each one
            // only re-reads state (updateDevicesUI is a pure read).
            v.postDelayed({ if (_binding != null) updateDevicesUI() }, 900)
            v.postDelayed({ if (_binding != null) updateDevicesUI() }, 3900)
            v.postDelayed({ if (_binding != null && isRunning) updateDevicesUI() }, 4400)
        }
        binding.btnRefreshDevices.setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }

        // ── Pass-18 (session-scoped unblock): the blocked-clients sheet. ──────
        // The no-entry icon beside Refresh is invisible until some client is
        // actually blocked (updateDevicesUI drives its visibility every
        // refresh, so it appears on the Kick and vanishes the moment the
        // last blocked device is unblocked). The sheet lists every client
        // blocked in THIS server session with a per-device Unblock action
        // — a block is still a real, full 403 wall (unchanged), it just no
        // longer needs a server restart to undo. Restarting still clears
        // everything, exactly as before.
        binding.btnBlockedDevices.setOnClickListener { showBlockedDevicesSheet() }
        binding.btnBlockedDevices.setOnTouchListener { v, _ -> v.parent.requestDisallowInterceptTouchEvent(true); false }

        binding.previewList.layoutManager = LinearLayoutManager(requireContext())
        binding.previewList.adapter = queueAdapter
        // Pass-12 (Required Implementations 3 + 6 — queue fades + scrollbar):
        // the sending queue receives the exact same card-colored scroll-edge
        // fade treatment the Transfers subtabs and the Connected Devices card
        // already use (same drawables, same only-when-scrollable visibility
        // rule, same gentle crossfade), plus the shared minimalist LdxScrollbar
        // thumb — so every scrollable list surface in the app now behaves and
        // reads identically. The bands + thumb live in previewListWrap (added
        // in fragment_send.xml), painted over the rows.
        binding.previewList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { updateQueueEdgeFades() }
        })
        binding.previewListWrap.viewTreeObserver.addOnGlobalLayoutListener { updateQueueEdgeFades() }
        com.lumidrop.x.ui.LdxScrollbar.attach(binding.previewListWrap, binding.previewList)
        // GOAT (Part 3 item 4 — true single-finger Queue scrolling):
        // nestedScrollingEnabled="false" (see fragment_send.xml's own
        // comment on previewList) correctly stops the Queue from HANDING
        // OFF a scroll to the page once it reaches its own top/bottom --
        // that's the cooperative nested-scrolling protocol, and turning it
        // off is what fixed the earlier "reaching the edge moves the whole
        // page" bug. But it's a DIFFERENT mechanism from what's needed
        // here: the outer NestedScrollView is still an ordinary scrolling
        // ViewGroup underneath, with its own independent
        // onInterceptTouchEvent that has nothing to do with the child's
        // nested-scrolling opt-in -- it can still occasionally decide a
        // drag that started on the Queue belongs to IT instead, which is
        // what actually produced "sometimes needs two fingers": a second
        // simultaneous pointer gets dispatched differently and happens to
        // land on the Queue's own touch handling instead of the outer
        // view's. requestDisallowInterceptTouchEvent on ACTION_DOWN is the
        // standard, correct fix for exactly this class of ambiguity — it
        // tells every ancestor "don't intercept anything in this gesture,
        // it's mine," independent of and complementary to the nested-
        // scrolling setting above. Already used once in this same file
        // (btnRefreshDevices, a few lines up) for the identical reason.
        binding.previewList.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                        rv.parent?.requestDisallowInterceptTouchEvent(false)
                }
                return false   // never actually intercept for itself here -- only ever influences the ancestor chain's willingness to
            }
            override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
        })

        binding.devicesList.layoutManager = LinearLayoutManager(requireContext())
        binding.devicesList.adapter = deviceAdapter
        // Pass-3 (R2): the devices list gets the SAME two-part scroll-island
        // treatment the queue list has proven out — (1) the touch guard so
        // the outer NestedScrollView's own onInterceptTouchEvent can never
        // steal a drag that started on the list (the "scrolling the device
        // list shifts the whole Send tab" bug), and (2) a scroll listener
        // keeping the two edge-fade overlays honest while the list scrolls.
        // nestedScrollingEnabled=false itself lives in fragment_send.xml.
        binding.devicesList.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        rv.parent?.requestDisallowInterceptTouchEvent(true)
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                        rv.parent?.requestDisallowInterceptTouchEvent(false)
                }
                return false
            }
            override fun onTouchEvent(rv: RecyclerView, e: android.view.MotionEvent) {}
            override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
        })
        // Pass-6 (R-B — scroll-edge fades): the list-area fades are REAL scroll
        // chrome again — card-colored, 18dp, and visible only on the side(s)
        // that actually have content hidden past the rim. Every scroll tick
        // re-evaluates both directions.
        binding.devicesList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { updateDeviceEdgeFades() }
        })
        // Pass-12 (Required Implementation 3): the Connected Devices list also
        // carries the shared minimalist scrollbar — same thumb, same immediate
        // appearance, as every other scrollable list in the app.
        com.lumidrop.x.ui.LdxScrollbar.attach(binding.devicesListWrap, binding.devicesList)
        // Pass-8 (§4 — fades must appear without interaction): the pass-6
        // recompute rode a plain View.post() after each adapter submit, which
        // can run BEFORE the layout/traversal pass it depends on — so a
        // freshly-grown list read canScrollVertically()==false and its bottom
        // fade stayed dark until the first manual scroll (the reported
        // "doesn't appear until I scroll up and down"). A GLOBAL LAYOUT
        // listener on the wrapper recomputes on EVERY geometry change —
        // initial render, adapter submits, the fixed-height application in
        // updateDevicesUI, device count changes, rotation, and the
        // empty→populated transition — with zero interaction required. Cheap
        // by design: the check itself is two canScrollVertically() calls.
        binding.devicesListWrap.viewTreeObserver.addOnGlobalLayoutListener(deviceFadeLayoutListener)
        // ── Pass-16: the pass-13/15 height LATCH is retired ──────────────
        // Its two capture paths were exactly the source of the rare
        // "card suddenly shrinks" glitch: (a) the listener armed whenever
        // the card was in its natural weighted configuration, but a
        // natural card inside an OVER-TALL content column is not weighted —
        // LinearLayout measures it at its CONTENT height instead, and that
        // small number got latched and then PINNED on the next
        // devices-connected tick; (b) the weighted-measure fallback itself
        // collapses to ~minHeight when the sibling cards alone exceed the
        // viewport. The card is now pinned to a DETERMINISTIC height — the
        // two-regime computation, computed ONCE per viewport and frozen
        // (pinnedDevicesCardHeightPx → computeDevicesCardConsistentHeightPx) —
        // so neither a raced first layout nor later sibling churn can ever
        // move it.
        // Pass-8 (§4 — bottom flush with the card edge): the wrapper extends
        // into the card's own bottom padding by a small negative margin so
        // the bottom fade band sits close to the parent card's actual bottom
        // edge instead of a ~14dp dead strip of padding below it. Applied
        // once here (and preserved by updateDevicesUI's height writes, which
        // only touch lp.height/weight).
        run {
            val lp = binding.devicesListWrap.layoutParams as LinearLayout.LayoutParams
            if (lp.bottomMargin != -dp(10)) {
                lp.bottomMargin = -dp(10)
                binding.devicesListWrap.layoutParams = lp
            }
        }
    }

    /** §4: recompute both fades after any layout change of the device list
     *  wrapper — attached once in onViewCreated, never removed while the
     *  binding lives (the observer dies with the view). */
    private val deviceFadeLayoutListener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
        updateDeviceEdgeFades()
    }

    // ── Pass-13 (Connected Devices card dimensions are FIXED) ────────────────
    // The card must keep EXACTLY the dimensions it has in its empty state
    // ("No devices connected currently.") no matter how many devices connect
    // AND no matter what its sibling cards do (Pass-21 — see the latch note
    // below).
    //
    // ── Pass-16 (deterministic pin) ───────────────────────────────────────
    // The pin is computed by mirroring the layout system's own two regimes —
    // weighted fill when the column fits the viewport, content height when
    // it doesn't — in the EMPTY-content configuration (Pass-20), so a
    // device connect/disconnect changes nothing the computation reads.
    //
    // ── Pass-21 (the once-per-viewport LATCH — the Queue-Files regression) ──
    // Pass-16 recomputed the pin EVERY tick, which made it a function of the
    // LIVE sibling set: opening or closing the Queue Files card changed the
    // column's total height, flipped the weighted/content regime, and the
    // pin ITSELF moved — the reported "right after closing Queue Files the
    // card quickly recalculates and changes its height". The recompute was
    // the bug, in exactly the way the old per-tick notes feared a latch
    // would be: the value must be stable, not fresh. The pin is now computed
    // ONCE — on the first tick with a genuinely measured viewport, i.e. the
    // configuration the user calls "from the beginning" — and then frozen
    // for the viewport's entire life (recomputed only when the viewport
    // itself changes: rotation, resize). Device connects/disconnects and
    // Queue-Files open/close can no longer move it by construction, because
    // they no longer trigger a computation at all.

    /** The latched pin — 0 until the first measured frame. Pass-27 (#4.1): the
     *  latch key now ALSO includes the Send column's bottom-clearance base, so
     *  an edge-to-edge navigation-bar inset change (the "Hide Navigation Bar"
     *  preference flipping, on API 30+) re-derives the pin even though the
     *  scroll viewport's own size never changes in that world — the space the
     *  Android buttons occupy is handed to the column through its PADDING, not
     *  through the window frame. */
    private var devicesCardPinnedPx = 0
    private var devicesCardPinVpW = 0
    private var devicesCardPinVpH = 0
    private var devicesCardPinClearance = -1

    /** The Send column's CURRENT bottom clearance base (nav_bar_clearance +
     *  the live navigation-bar inset on API 30+; the plain nav_bar_clearance
     *  everywhere else). Written ONLY by applySendBottomClearance — the
     *  micro-scroll guard reads it so its trims scale with the real clearance
     *  instead of a hardwired 86dp. */
    private var sendBottomClearancePx = 0

    /** ── Pass-28 (blackout height freeze) ──────────────────────────────────
     *  True while the shell's blackout overlay is up (see MainActivity's own
     *  isBlackoutActive doc). Blackout deliberately distorts the window's
     *  geometry — both system bars hidden, the root top inset forced to zero,
     *  the scroll viewport grown by both — for exactly as long as it lasts.
     *  A card-height pin, a bottom-clearance base, or a column-padding write
     *  derived from THAT state is wrong by precisely the bars the moment
     *  blackout ends, and the old flow's recovery depended on a fresh insets
     *  dispatch reaching this tab after exit to undo it — a dependency that
     *  broke on devices where the bar-state change after blackout exit
     *  doesn't produce a full re-measure, leaving the Connected Devices card
     *  stuck at a height minted from blackout geometry. Every geometry latch
     *  below is therefore FROZEN while blackout is active: the pin, the
     *  clearance base and the padding all keep their pre-blackout values
     *  (invisible under the pitch-black overlay, and blackout exit
     *  re-asserts the SAME bar state that produced them), so the card's
     *  height cannot change across a blackout round-trip by construction. */
    private fun blackoutActive(): Boolean =
        (activity as? MainActivity)?.isBlackoutActive == true

    /** Pass-27 (#4.1): applies the inset-aware bottom clearance to the Send
     *  column and invalidates the card pin whenever it actually changes.
     *  Idempotent per value — the insets listener fires on every animation
     *  frame of a bar show/hide, and each frame simply moves the clearance
     *  one step closer to the bar's final state, so the whole adaptation
     *  animates WITH the system bar instead of snapping. */
    private fun applySendBottomClearance(navInsetPx: Int) {
        if (_binding == null) return
        if (blackoutActive()) return   // frozen — see blackoutActive(): no clearance/padding write may be derived from blackout's bars-hidden insets
        val content = binding.sendScroll.getChildAt(0) as? ViewGroup ?: return
        val target = resources.getDimensionPixelSize(R.dimen.nav_bar_clearance) + navInsetPx.coerceAtLeast(0)
        if (target == sendBottomClearancePx) return
        sendBottomClearancePx = target
        // Restore the FULL clearance before re-deriving the pin: the micro-scroll
        // guard may have trimmed it earlier, and the pin's regime-1 measure must
        // see the honest base, not a leftover trim.
        if (content.paddingBottom != target) {
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, target)
        }
        onSendViewportChanged(binding.sendScroll.width, binding.sendScroll.height)
    }

    /** Pass-21: the frozen-per-viewport wrapper around the deterministic
     *  computation. The first call with a laid-out scroll viewport latches
     *  the value for that viewport; every later call is a pure read.
     *  Pass-27 (#4.1): "viewport" now includes the clearance base — see
     *  devicesCardPinClearance.
     *
     *  ── Pass-28 (#3): no pin can be minted from an unmeasured viewport ─────────
     *  The old fallback answered a pre-layout call (sendScroll still 0×0 —
     *  onBecameVisible's updateUI runs at onResume, BEFORE the first
     *  traversal) with a displayMetrics-derived number: the full display
     *  height, which is wrong by exactly the status-bar inset the
     *  edge-to-edge window subtracts and by whatever the live navigation
     *  inset is — the precise family of "first launch shows the wrong card
     *  height" the Hide-Navigation-Bar report described. That garbage value
     *  was then APPLIED (the function returned it) while never being
     *  LATCHED (the latch required vpW/vpH > 0) — and onSendViewportChanged's
     *  old no-latch gate made the first real layout a no-op, so the wrong
     *  pin survived until some unrelated updateDevicesUI pass (a running
     *  server's poll tick, or opening a sheet/modal) happened to run with
     *  measured geometry. Now: unmeasured viewport → return the LATCHED pin
     *  only (a value earlier derived from honest geometry), or 0 — and the
     *  callers never apply a 0 pin, so the card renders its natural weighted
     *  configuration (which sendScroll's fillViewport makes the correct
     *  full height for the very first frame) until the first MEASURED pass
     *  latches the true pin. No displayMetrics viewport guess exists
     *  anywhere anymore. */
    private fun pinnedDevicesCardHeightPx(): Int {
        val vpW = binding.sendScroll.width
        val vpH = binding.sendScroll.height
        if (vpW <= 0 || vpH <= 0) return devicesCardPinnedPx   // unmeasured: only a previously-latched (honest) pin may answer
        if (blackoutActive()) return devicesCardPinnedPx   // frozen — blackout geometry must never mint (or re-mint) a pin; only a pre-blackout latch may answer
        // ── Pass-32 (#4): the post-blackout restore window is frozen too ──────
        // — the restored pre-blackout pin is correct by construction, and the
        // frames while the bars animate back carry transient viewports. The
        // settle pass in onBlackoutExited clears the flag once the geometry
        // has stopped moving (and re-derives only for a GENUINE change).
        if (postBlackoutRestorePending) return devicesCardPinnedPx
        // ── Pass-29 Part 1 (#1A/#1B): the per-key shortcut is GONE. It used ──
        // to answer "same vpW/vpH/clearance → return the latched number
        // untouched", which made ANY latch minted from a transiently-wrong
        // measure (a not-yet-settled sibling height, a mid-animation frame)
        // permanent — with the server stopped and zero devices connected the
        // 500ms poll never re-derived it, and the card stayed short until a
        // sheet/modal happened to churn the key (the reported "fixed only by
        // opening another sheet"). Recomputing is cheap (two small measure
        // passes) and DETERMINISTIC since Pass-20 flips the card to the empty
        // configuration for the measures, Pass-25 moved the queue INSIDE the
        // shared card, and (this pass) the probe now always measures the full
        // clearance base — so the same geometry always yields the same number
        // and a changed geometry (rotation, bar reveal, inset change) yields
        // the honest new one within one tick or one layout, by construction.
        val computed = computeDevicesCardConsistentHeightPx()
        devicesCardPinnedPx = computed
        devicesCardPinVpW = vpW
        devicesCardPinVpH = vpH
        devicesCardPinClearance = sendBottomClearancePx
        return computed
    }

    /** ── Pass-26 (#6): the viewport-change half of the pin's lifecycle ──────
     *  Called from sendScroll's OnLayoutChangeListener the instant the
     *  scroll viewport's size changes. The pin's own latch condition already
     *  keys on (vpW, vpH) — the missing piece was only ever WHEN the check
     *  runs: at launch it latched against the buttons-visible window, and
     *  with the "Hide Navigation Bar" setting flipping the window's height
     *  the recompute waited for a poll tick that never comes while another
     *  tab is showing. Here the change is detected at the layout that
     *  PRODUCED it: drop the latch, then immediately re-latch against the
     *  new viewport and re-apply it to the card in the same UI message — no
     *  stale-dimension window, no restart needed, and repeated toggling
     *  simply alternates between the two honest heights.
     *
     *  ── Pass-28 (#3): the FIRST measured layout is the latch moment too ────
     *  The old `if (devicesCardPinnedPx <= 0) return` gate here assumed "the
     *  next updateDevicesUI pass will take care of it" — but with the server
     *  stopped, startPolling's `if (!isRunning) continue` means NO such pass
     *  ever runs, and the wrong pre-layout pin stuck until an unrelated
     *  surface (sheet, modal, picker) happened to churn the layout. Now the
     *  gate is measurability itself: a viewport with real dimensions IS the
     *  honest-geometry moment, so drop whatever stale latch exists and let
     *  updateDevicesUI latch + apply the true pin right here — the card is
     *  correct from its first measured frame with no dependence on the
     *  poll, the server, or any other UI surface. */
    private fun onSendViewportChanged(w: Int, h: Int) {
        if (_binding == null) return
        if (blackoutActive()) return   // frozen — the latch is never invalidated against blackout's deliberately distorted viewport; exit returns to the pre-blackout key
        // ── Pass-32 (#4): mid-restoration frames after blackout exit are the ──
        // transient geometry a pin must never be minted from — the restored
        // pre-blackout height is already applied and correct; the settle pass
        // decides, once the bars have stopped moving, whether anything
        // genuinely changed.
        if (postBlackoutRestorePending) return
        if (w == devicesCardPinVpW && h == devicesCardPinVpH &&
            sendBottomClearancePx == devicesCardPinClearance) return   // nothing to do
        if (w <= 0 || h <= 0) return                                   // not measurable yet — the first measured layout below is the latch moment
        devicesCardPinnedPx = 0                                        // invalidate the stale latch…
        updateDevicesUI()                                             // …and re-latch + re-apply right now
    }

    /** Pass-16: THE deterministic pin. Reproduces, in one measure pass, the
     *  exact height the layout system would hand the card RIGHT NOW in its
     *  natural weighted configuration — which is precisely the height the
     *  EMPTY state is rendering at this instant, since it IS in that natural
     *  configuration. Two regimes, mirroring LinearLayout's own:
     *   1. Column fits the viewport → weights apply → the card gets the
     *      viewport remainder minus every sibling. Measured by restoring the
     *      card to height=0/weight=1 and measuring the column EXACTLY at
     *      the viewport height.
     *   2. Column over-tall → weights don't apply (the empty state itself
     *      renders at its content height in this regime) → the card pins to
     *      the same content-height number the empty state shows:
     *      header + empty-state illustration + paddings, floored by the
     *      layout's own minHeight.
     *  Pass-20: the card's children are flipped to the EMPTY configuration
     *  for the duration of the measures (device-count immunity). Pass-21:
     *  this function runs ONCE per viewport (see pinnedDevicesCardHeightPx),
     *  so sibling churn — the Queue Files card — can never move the result.
     *  Falls back to the content measure only if the view tree isn't
     *  measurable yet. */
    private fun computeDevicesCardConsistentHeightPx(): Int {
        val content = binding.sendScroll.getChildAt(0) as? ViewGroup
            ?: return computeDevicesCardFallbackHeightPx()
        // Pass-28 (#3): the displayMetrics viewport guesses are GONE. This
        // function only ever runs behind pinnedDevicesCardHeightPx's measured-
        // viewport gate now; if the scroll frame somehow still reports 0, the
        // honest answer is the deterministic CONTENT-based fallback below —
        // never a full-display measure that is wrong by exactly the system
        // bars (the first-launch bug this pass fixes).
        val vpH = binding.sendScroll.height.takeIf { it > 0 } ?: return computeDevicesCardFallbackHeightPx()
        val width = binding.sendScroll.width.takeIf { it > 0 } ?: return computeDevicesCardFallbackHeightPx()
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val cardLp = binding.cardDevices.layoutParams as? LinearLayout.LayoutParams
            ?: return computeDevicesCardFallbackHeightPx()
        val savedHeight = cardLp.height
        val savedWeight = cardLp.weight
        // ── Pass-20 (device-count can never move the pin) ─────────────────────
        // The regime probe and both measures used to run against the card's
        // CURRENT content — so a connect/disconnect changed the card's own
        // measured height, flipped the weighted/content regime, and the PIN
        // ITSELF moved for a frame or two (the reported "card becomes
        // smaller when a device connects or disconnects, then returns").
        // The whole point of this function is to reproduce THE EMPTY STATE's
        // height — so measure the empty state, not the live list: flip the
        // card's children to the empty configuration for the duration of the
        // measures and restore them before returning. Visibility flips post a
        // requestLayout, but restoring within the same UI message means the
        // single traversal at the end of the message sees the restored values
        // — no intermediate frame ever renders. Connect/disconnect now
        // changes NOTHING this function reads (only its OWN card content,
        // which no longer participates), so the pin is byte-identical before,
        // during, and after any device-list churn.
        val savedListWrapVis = binding.devicesListWrap.visibility
        val savedListVis = binding.devicesList.visibility
        val savedEmptyVis = binding.devicesEmptyState.visibility
        binding.devicesListWrap.visibility = View.GONE
        binding.devicesList.visibility = View.GONE
        binding.devicesEmptyState.visibility = View.VISIBLE
        // ── Pass-25 (#5): the shared card's OTHER face must not participate ─────
        // either — the pin is the height the EMPTY DEVICES face renders at, so
        // the queue's content (which can be tall) must be GONE for the duration
        // of both measures, whatever it happens to be showing right now.
        val savedQueueViewVis = binding.queueView.visibility
        binding.queueView.visibility = View.GONE
        // ── Pass-29 Part 1 (#1A/#1B — the probe always measures the FULL ────────
        // clearance base). clampSendMicroScroll legitimately trims the column's
        // bottom padding by a few dp whenever the content overflows the
        // viewport by a small excess, and the probe used to measure whatever
        // padding was live at call time — so the SAME geometry could answer a
        // different pin depending on whether a trim had landed since the last
        // pass. With the per-key shortcut gone (see
        // pinnedDevicesCardHeightPx) the probe MUST be trim-immune, or the
        // recompute could oscillate between two numbers. applySendBottom-
        // Clearance already restores the full base before its own
        // re-derivations; the probe enforces the same honesty itself now:
        // the measures run with the column padded to the full clearance base,
        // restored in the finally below.
        val savedContentPaddingBottom = content.paddingBottom
        val fullClearanceBase = if (sendBottomClearancePx > 0) sendBottomClearancePx
            else resources.getDimensionPixelSize(R.dimen.nav_bar_clearance)
        if (content.paddingBottom != fullClearanceBase) {
            content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, fullClearanceBase)
        }
        return try {
            cardLp.height = 0
            cardLp.weight = 1f
            // Regime probe: measure the column NATURALLY (the same
            // UNSPECIFIED height the ScrollView itself uses) and see whether
            // it fits the viewport at all.
            content.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            if (content.measuredHeight <= vpH) {
                // Regime 1: weights genuinely apply — read the card's
                // weighted height from an EXACTLY-viewport measure.
                content.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(vpH, View.MeasureSpec.EXACTLY))
                binding.cardDevices.measuredHeight.takeIf { it > 0 } ?: computeDevicesCardFallbackHeightPx()
            } else {
                // Regime 2: the column is over-tall — the empty state renders
                // at its content height here, so pin the same number.
                computeDevicesCardFallbackHeightPx()
            }
        } catch (e: Exception) {
            computeDevicesCardFallbackHeightPx()
        } finally {
            cardLp.height = savedHeight
            cardLp.weight = savedWeight
            if (content.paddingBottom != savedContentPaddingBottom) {
                content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, savedContentPaddingBottom)
            }
            binding.devicesListWrap.visibility = savedListWrapVis
            binding.devicesList.visibility = savedListVis
            binding.devicesEmptyState.visibility = savedEmptyVis
            binding.queueView.visibility = savedQueueViewVis
        }
    }

    /** Deterministic fallback for the rare "never saw an empty frame" case
     *  (a host restart with an instantly-reconnecting peer — the exact
     *  scenario pass-12's old 164dp fallback mishandled on the Android 5
     *  tablet): the height the empty state would naturally produce, measured
     *  directly from the header + empty-state content + the card's own
     *  paddings, floored by the layout's own minHeight. Same value every
     *  call, independent of device count — the card cannot grow with it. */
    private fun computeDevicesCardFallbackHeightPx(): Int {
        val card = binding.cardDevices
        val width = card.width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels
        val widthSpec = View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY)
        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        try {
            binding.devicesHeader.measure(widthSpec, unspec)
            binding.devicesEmptyState.measure(widthSpec, unspec)
        } catch (e: Exception) { /* fall through to the minHeight floor */ }
        val inner = card.getChildAt(0) as? ViewGroup
        val padV = (inner?.paddingTop ?: 0) + (inner?.paddingBottom ?: 0)
        val gap = dp(6)   // devicesListWrap's topMargin — the header→content spacing
        val content = binding.devicesHeader.measuredHeight + binding.devicesEmptyState.measuredHeight + padV + gap
        return maxOf(content, resources.getDimensionPixelSize(R.dimen.devices_card_min_height))
    }

    // Pass-6 (R-B): fade visibility = "is there content past this rim?" —
    // top fades in only once the list is scrolled DOWN past its first row,
    // bottom only while more rows remain below. A list that fits entirely
    // shows neither band (the old always-on overlay's "logic is bad"
    // complaint), and the empty state never sees them at all — the fades live
    // inside devicesListWrap, which goes GONE with the list.
    // Pass-8 (§4): also re-run on every wrapper layout via
    // deviceFadeLayoutListener, and on fragment resume (a device list that
    // changed while the tab was hidden gets correct fades the moment it is
    // shown again).
    private fun updateDeviceEdgeFades() {
        if (_binding == null) return
        val list = binding.devicesList
        val wrapVisible = binding.devicesListWrap.visibility == View.VISIBLE && list.visibility == View.VISIBLE
        val canUp = wrapVisible && list.canScrollVertically(-1)
        val canDown = wrapVisible && list.canScrollVertically(1)
        setEdgeFade(binding.devicesFadeTop, canUp)
        setEdgeFade(binding.devicesFadeBottom, canDown)
    }

    /** Pass-12 (Required Implementation 6): queue fade visibility = "is there
     *  content past this rim?" — the same rule updateDeviceEdgeFades applies
     *  to the devices list. Called from the scroll listener, every wrapper
     *  layout pass, and after each queue re-bind in updatePreviewCard, so the
     *  bands are correct through adapter growth and card show/hide without
     *  requiring any interaction first. */
    private fun updateQueueEdgeFades() {
        if (_binding == null) return
        val list = binding.previewList
        // ── Pass-25 (#5): the queue list now lives in the shared card's ──────────
        // queueView — its visibility IS the queue face's presence.
        val visible = binding.queueView.visibility == View.VISIBLE && list.visibility == View.VISIBLE
        setEdgeFade(binding.queueFadeTop, visible && list.canScrollVertically(-1))
        setEdgeFade(binding.queueFadeBottom, visible && list.canScrollVertically(1))
    }

    private fun setEdgeFade(v: View, visible: Boolean) {
        val want = if (visible) View.VISIBLE else View.GONE
        if (v.visibility == want) return
        v.visibility = want
        // A short crossfade reads as polish; animating GONE is a no-op, and
        // View.VISIBLE here is measured-before-draw, so alpha settles before
        // the band is ever composited — no flash-in.
        v.alpha = if (visible) 0f else 1f
        v.animate().alpha(if (visible) 1f else 0f).setDuration(140).start()
    }

    // Pass-6 (R-E — no micro-scroll): the Send tab's three main cards used to
    // jiggle by a few dp because the content column's natural height exceeded
    // the viewport by that same few dp (the Connected Devices card's minHeight
    // floor, plus small per-device rounding), and a NestedScrollView exposes
    // EVERY excess as scroll range — even one that reveals nothing but the
    // bottom clearance padding behind the floating nav bar. Fix: after each
    // layout, measure the excess; while it is small (≤ 48dp) it lives entirely
    // inside the 86dp nav-bar clearance padding, so trimming the padding by
    // exactly the excess is PIXEL-IDENTICAL on screen (no card moves — the
    // padding is the last element) and the content fits the viewport exactly:
    // scroll range becomes hard zero. A large excess (queue/incoming cards,
    // multi-URL rows, long content) is REAL scroll need and restores the full
    // clearance + normal scrolling. Both states converge in one pass and are
    // stable — trimming only happens from the small-excess state (which zeroes
    // the excess), restoring only from the large-excess state — so this can
    // never oscillate.
    private fun clampSendMicroScroll() {
        if (_binding == null) return
        val sv = binding.sendScroll
        val content = sv.getChildAt(0) as? ViewGroup ?: return
        if (sv.height <= 0 || content.height <= 0) return
        val excess = content.height - sv.height
        val microMax = dp(48)
        // ── Pass-27 (#4.1): the guard's restore floor/ceiling now scale with ──
        // the REAL bottom clearance (nav_bar_clearance + the live navigation-bar
        // inset on API 30+) instead of the old hardwired 86dp, so an inset change
        // can never leave the guard fighting the inset-aware padding it re-trims.
        val fullClearance = (if (sendBottomClearancePx > 0) sendBottomClearancePx else dp(78)) + dp(8)
        val minClearance = dp(40) + ((if (sendBottomClearancePx > 0) sendBottomClearancePx else dp(78)) - dp(78))  // never trim past this — the nav bar's own zone
        when {
            excess in 1..microMax -> {
                val np = (content.paddingBottom - excess).coerceAtLeast(minClearance)
                if (content.paddingBottom != np)
                    content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, np)
            }
            excess > microMax -> {
                if (content.paddingBottom < fullClearance)
                    content.setPadding(content.paddingLeft, content.paddingTop, content.paddingRight, fullClearance)
            }
        }
    }

    private fun dp(n: Int): Int = requireContext().dp(n)

    // Permissions -----------------------------------------------------------------

    private fun requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun checkPermissionsAndStart() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                if (Environment.isExternalStorageManager()) startServer()
                else showLdxDialog(
                    context = requireContext(),
                    title = "Storage Permission",
                    message = "LumiDrop X needs \"Allow access to manage all files\" to serve and receive files.\n\nGrant it, enable the toggle, then return here.",
                    iconRes = R.drawable.ic_lock,
                    actions = listOf(
                        LdxDialogAction("Skip", LdxButtonStyle.GHOST) { startServer() },
                        LdxDialogAction("Grant", LdxButtonStyle.PRIMARY) {
                            manageAllFilesLauncher.launch(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${requireContext().packageName}")))
                        },
                    ),
                )
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                val p = arrayOf(Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
                if (p.all { ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }) startServer()
                else mediaPermLauncher.launch(p)
            }
            else -> {
                val p = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                if (p.all { ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }) startServer()
                else legacyStorageLauncher.launch(p)
            }
        }
    }

    // Server lifecycle ------------------------------------------------------------

    private fun startServer() {
        val intent = Intent(requireContext(), ServerForegroundService::class.java).apply {
            action = ServerForegroundService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) requireContext().startForegroundService(intent)
        else requireContext().startService(intent)
        if (SettingsManager.isKeepScreenOn(requireContext()))
            requireActivity().window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewLifecycleOwner.lifecycleScope.launch { delay(500); withContext(Dispatchers.Main) { updateUI() } }
    }

    private fun stopServer() {
        requireContext().stopService(Intent(requireContext(), ServerForegroundService::class.java))
        previewItems.clear()
        requireActivity().window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        viewLifecycleOwner.lifecycleScope.launch { delay(200); withContext(Dispatchers.Main) { updateUI() } }
    }

    // Appearance helpers --------------------------------------------------------

    private fun setDotColor(colorRes: Int) {
        val d = binding.tvStatusDot.background?.mutate()
        if (d is GradientDrawable) d.setColor(color(colorRes))
        else binding.tvStatusDot.setBackgroundColor(color(colorRes))
    }

    private fun setStartStopStyle(running: Boolean) {
        val ctx = requireContext()
        // ── Pass-25 (#4.1): the START (green) state draws the dedicated ──────
        // power glyph (ic_server_start) — it previously reused the SAME
        // circular-arrows drawable as the Connected Devices Refresh button
        // and the Transfers Active pill, the three-look-alike set this pass
        // breaks apart. The STOP state keeps its existing stop glyph, and
        // nothing about the state behavior changes.
        binding.btnStartStop.setImageDrawable(Widgets.icon(
            ctx, if (running) R.drawable.ic_stop else R.drawable.ic_server_start,
            color(if (running) R.color.red_stop else R.color.green)
        ))
        binding.btnStartStop.background = Widgets.circleToggle(
            ctx,
            fillColor = if (running) color(R.color.error_bg) else ColorUtils.setAlphaComponent(color(R.color.green), 51),
            strokeColor = if (running) color(R.color.error_border) else color(R.color.green)
        )
        binding.btnStartStop.contentDescription = if (running) "Stop server" else "Start server"
    }

    private fun updateUI() {
        if (_binding == null) return
        if (!isRunning) {
            // Pass-12 (Send tab redesign): no separate "Running" label exists
            // anymore — the URL slot itself carries the honest stopped state in
            // the URL's muted style, next to the grey dot.
            setDotColor(R.color.text_muted)
            pulseAnimator?.let { Widgets.stopPulse(binding.tvStatusDot, it) }
            clearServerUrlRow()
            qrDialog?.dismiss()
            setStartStopStyle(false)
            // Connected Devices stays visible (empty state) even while
            // stopped, matching the prototype. The incoming-request sheet
            // used to also close explicitly here -- now lives in
            // MainActivity.kt (item 8), whose own updateIncomingRequests
            // checks ServerForegroundService.isRunning itself and tears
            // down the same way, on its own independent tick, so nothing
            // further is needed from this branch for it.
            updateDevicesUI()
            updatePreviewCard()
            return
        }

        // GOAT (transfer stability pass — instant incoming response):
        // devices are a cheap, already-in-memory read
        // (NativeDiscovery/TransferManager — no network or filesystem I/O
        // of their own) — updated here, immediately, on every poll tick,
        // rather than only after the network-interface scan below
        // finishes. That scan (used purely to decide which URL(s) to
        // display) used to gate this behind itself every single tick even
        // though it doesn't need to. (Incoming-request state used to be
        // updated here too -- see item 8's move to MainActivity.kt, which
        // now polls that independently of this Fragment's own tick.)
        updateDevicesUI()

        // ── Pass-26 (#4): a state-accurate Start/Stop button ────────────────────
        // The button mirrors the SERVER LIFECYCLE the instant we know it is
        // running — the red stop treatment — no matter what the network scan
        // below concludes. Previously ONLY markRunning() styled it (i.e. just
        // the with-a-network states), so a running server that was WAITING
        // for a network kept the green START glyph it had as a stopped
        // server: green said "can be started" about a server that was
        // already running — exactly the reported confusion. Now:
        //   stopped                → green START glyph (unchanged);
        //   running, waiting for a network → RED stop glyph (this line);
        //   running, with a network → RED stop glyph (unchanged, markRunning).
        // The dot/status text still tell the three states apart (grey/
        // orange "Waiting for network…"/green pulsing), and nothing about
        // the actual lifecycle, network detection or auto-recovery changes.
        setStartStopStyle(true)

        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Pass-7: getEnabledInterfaces is now priority-ordered (Hotspot
            // first, then Wi-Fi/LAN, Mobile Data last), so the ONE address
            // the fixed-height row shows is always the most-reachable path —
            // every other running address stays one chevron-tap away.
            val enabledInterfaces = NetworkUtils.getEnabledInterfaces(requireContext())
            // All-toggles-off fallback: keep the legacy "best IP" behavior
            // so the card never shows nothing while a LAN-family interface
            // is genuinely up, but resolve which interface class that IP
            // actually belongs to (for the chip) here on the IO dispatcher
            // where interface enumeration belongs. Pass-8 follow-up: the
            // fallback itself is now cellular-aware (see NetworkUtils.
            // getLocalIpAddress) — with only a mobile-data interface up it
            // resolves null and the card shows its honest "Waiting for
            // network…" state instead of an unreachable carrier address.
            val fallback: NetworkUtils.DetectedInterface? = if (enabledInterfaces.isEmpty()) {
                val ip = NetworkUtils.getLocalIpAddress(requireContext())
                if (ip != null) {
                    val detected = try { NetworkUtils.getAllInterfaces(requireContext()) } catch (e: Exception) { emptyList() }
                    // Belt-and-braces: the fallback IP must never be a
                    // Mobile Data address even if classification and the
                    // best-IP picker were to disagree on some device.
                    val di = detected.firstOrNull { it.ip == ip }
                        ?: NetworkUtils.DetectedInterface(NetworkUtils.InterfaceKind.LAN_WIFI, "LAN", ip)
                    if (di.kind == NetworkUtils.InterfaceKind.MOBILE_DATA) null else di
                } else null
            } else null
            withContext(Dispatchers.Main) {
                if (_binding == null) return@withContext
                if (enabledInterfaces.isNotEmpty()) {
                    markRunning()
                    renderServerUrls(enabledInterfaces)
                } else if (fallback != null) {
                    // Pass-7 fix folded in: this branch used to show the URL
                    // without ever setting the Running status/dot/stop
                    // button, leaving a stale "Stopped" style on a card that
                    // plainly had an address. Same treatment as above now.
                    markRunning()
                    renderServerUrls(listOf(fallback))
                } else {
                    showWaitingForNetwork()
                }
            }
        }
    }

    private fun markRunning() {
        // Pass-12: the dot itself IS the running indicator now — the old
        // "Running" text label is gone and the URL line sits right beside
        // the dot (set by renderServerUrls below).
        setDotColor(R.color.green)
        if (pulseAnimator == null) pulseAnimator = Widgets.startPulse(binding.tvStatusDot)
        setStartStopStyle(true)
    }

    private fun showWaitingForNetwork() {
        pulseAnimator?.let { Widgets.stopPulse(binding.tvStatusDot, it) }
        setDotColor(R.color.orange)
        binding.urlSourceChip.text = "—"
        binding.tvUrl.text = "Waiting for network…"
        binding.tvUrl.setTextColor(color(R.color.text_secondary))
        binding.btnMoreUrls.visibility = View.GONE
        serverIfaces = emptyList()
    }

    /** Pass-7: the ONE writer of the fixed-height URL row. Chip = primary
     *  interface's label, URL = its address, chevron = only when more
     *  addresses exist. Never changes the row's height in any state.
     *  Pass-12: the row now lives directly beside the status dot (one line,
     *  no "Running" label) and the text color follows content — the accent
     *  monospace treatment for a live address, the muted treatment for
     *  stopped/waiting states. */
    private fun renderServerUrls(ifaces: List<NetworkUtils.DetectedInterface>) {
        val primary = ifaces.firstOrNull() ?: return clearServerUrlRow()
        serverIfaces = ifaces
        val port = SettingsManager.getPort(requireContext())
        val primaryUrl = "http://${primary.ip}:$port"
        binding.urlSourceChip.text = primary.label
        binding.tvUrl.text = primaryUrl
        binding.tvUrl.setTextColor(color(R.color.accent))
        binding.btnMoreUrls.visibility = if (ifaces.size > 1) View.VISIBLE else View.GONE
        if (activeQrUrl == null || ifaces.none { "http://${it.ip}:$port" == activeQrUrl }) {
            activeQrUrl = primaryUrl
        }
        if (qrVisible) refreshQrModalSelection()
    }

    private fun clearServerUrlRow() {
        serverIfaces = emptyList()
        binding.urlSourceChip.text = "—"
        binding.tvUrl.text = "Server stopped"
        binding.tvUrl.setTextColor(color(R.color.text_secondary))
        binding.btnMoreUrls.visibility = View.GONE
    }

    /** (label, url, kind) for every enabled address — the one list the QR
     *  modal's chips, the addresses dialog and every copy action share.
     *  (Pass-8 follow-up: the ACTIVE remote-tunnel "Internet" entry is gone
     *  with the whole Internet Sharing system — strictly LAN addresses now.) */
    private fun serverUrlsForActions(): List<Triple<String, String, NetworkUtils.InterfaceKind>> {
        val port = try { SettingsManager.getPort(requireContext()) } catch (e: Exception) { 8888 }
        return serverIfaces.map { Triple(it.label, "http://${it.ip}:$port", it.kind) }
    }

    private fun copyUrlToClipboard(url: String) {
        (requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("LumiDrop X URL", url))
        toast("Address copied")
    }

    /** A one-per-row UI shared by the addresses dialog and the copy dialog:
     *  quiet source chip + monospace address, so every place an address can
     *  be read says the same thing the same way. [onUrlTap] fires for a tap
     *  on the whole row; the trailing copy glyph always copies. */
    private fun buildUrlRow(
        label: String, url: String, kind: NetworkUtils.InterfaceKind,
        onUrlTap: () -> Unit,
    ): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true; isFocusable = true
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_icon_ripple)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onUrlTap() }
        }.also { row ->
            row.addView(TextView(ctx).apply {
                text = label
                textSize = 10f
                setTextColor(color(R.color.accent_light))
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_ghost)
                setPadding(dp(7), dp(2), dp(7), dp(2))
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.marginEnd = dp(8) }
            })
            // Pass-8 (S2 - full URLs, never shortened): the URL itself is
            // never ellipsised anymore. It lives in its own horizontally
            // scrollable view - the complete address is always readable with
            // a swipe, exactly like the server card's own row - with
            // selectable text for long-press copy, and the row's dedicated
            // copy action copies the full value. The URL string in app state
            // and the clipboard is only ever the real, complete address.
            row.addView(android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = url
                    textSize = 12f
                    setTextColor(color(R.color.accent))
                    typeface = android.graphics.Typeface.MONOSPACE
                    maxLines = 1
                    // No ellipsize - the HorizontalScrollView pans instead.
                    setTextIsSelectable(true)
                })
            })
            row.addView(ImageView(ctx).apply {
                setImageDrawable(Widgets.icon(ctx, R.drawable.ic_copy, color(R.color.text_secondary)))
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_icon_ripple)
                setPadding(dp(5), dp(5), dp(5), dp(5))
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(dp(26), dp(26))
                    .also { it.marginStart = dp(6) }
                setOnClickListener { copyUrlToClipboard(url) }
            })
        }
    }

    /** The chevron dialog: every running address, labeled by its network —
     *  tap a row for its QR, the copy glyph to copy just that one, or Copy
     *  all for the whole list. This is the card's "more than one URL"
     *  affordance (the card itself never grows). */
    private fun showServerAddressesDialog(urls: List<Triple<String, String, NetworkUtils.InterfaceKind>>) {
        val ctx = requireContext()
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        urls.forEach { (label, url, kind) -> list.addView(buildUrlRow(label, url, kind) { showQrDialog(url) }) }
        val scroll = android.widget.ScrollView(ctx).apply { addView(list) }
        capScrollHeight(scroll, list)
        showLdxDialog(
            context = ctx,
            title = "Server addresses",
            iconRes = R.drawable.ic_globe,
            bodyView = scroll,
            actions = listOf(
                LdxDialogAction("Copy all", LdxButtonStyle.PRIMARY) {
                    val joined = urls.joinToString("\n") { it.second }
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("LumiDrop X URLs", joined))
                    toast("${urls.size} addresses copied")
                },
                LdxDialogAction("Close", LdxButtonStyle.GHOST),
            ),
        )
    }

    /** The copy icon's multi-address flow: identical labeled rows; a tap
     *  anywhere on a row copies that one address (and closes). */
    private fun showCopyUrlDialog(urls: List<Triple<String, String, NetworkUtils.InterfaceKind>>) {
        val ctx = requireContext()
        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        urls.forEach { (label, url, kind) -> list.addView(buildUrlRow(label, url, kind) { copyUrlToClipboard(url) }) }
        val scroll = android.widget.ScrollView(ctx).apply { addView(list) }
        capScrollHeight(scroll, list)
        showLdxDialog(
            context = ctx,
            title = "Copy address",
            iconRes = R.drawable.ic_copy,
            bodyView = scroll,
            actions = listOf(
                LdxDialogAction("Copy all", LdxButtonStyle.PRIMARY) {
                    val joined = urls.joinToString("\n") { it.second }
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("LumiDrop X URLs", joined))
                    toast("${urls.size} addresses copied")
                },
                LdxDialogAction("Cancel", LdxButtonStyle.GHOST),
            ),
        )
    }

    /** Phase 18: QR used to be an inline card inside the Server Card,
     *  which could overflow that card's now-fixed height and effectively
     *  push/hide its other content -- exactly the "hides other elements"
     *  bug reported. A dialog isn't bound by the card's height at all, so
     *  this can't happen anymore; background dimming comes for free from
     *  AlertDialog's default window behavior rather than anything
     *  hand-rolled.
     *
     *  Pass-7: the modal is now the ONE place every running address can be
     *  scanned from — one labeled chip per enabled network (Wi-Fi / Hotspot
     *  / Mobile Data), the QR and the URL text below it re-targeting on
     *  every chip tap. With a single address the chips hide (the URL says
     *  everything); with several, it's always evident WHICH network the
     *  shown code belongs to — the exact "I ran Hotspot and Mobile Data and
     *  didn't know which QR this was" fix. Mobile Data carries its honest
     *  reachability caption while selected. */
    private var qrDialog: AlertDialog? = null
    private var qrDialogImage: ImageView? = null

    private fun showQrDialog(url: String) {
        if (qrDialog?.isShowing == true) { selectQrUrl(url); return }
        val ctx = requireContext()
        val urls = serverUrlsForActions()
        val targetUrl = if (urls.any { it.second == url }) url else urls.firstOrNull()?.second ?: url
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_card_elevated)
            setPadding(dp(24), dp(24), dp(24), dp(20))
            elevation = resources.getDimension(R.dimen.elevation_sheet)
        }
        container.addView(TextView(ctx).apply {
            text = "Scan to connect"
            textSize = 16f; setTextColor(color(R.color.text_primary))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        // Source chips — only when there's a choice to make.
        if (urls.size > 1) {
            val chipsRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.topMargin = dp(12) }
            }
            val chips = mutableListOf<TextView>()
            for ((label, u, _) in urls) {
                chipsRow.addView(TextView(ctx).apply {
                    text = label
                    textSize = 11.5f; gravity = Gravity.CENTER
                    setPadding(dp(12), dp(7), dp(12), dp(7))
                    maxLines = 1
                    isClickable = true; isFocusable = true
                    tag = u
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .also { it.marginEnd = dp(6) }
                    setOnClickListener { selectQrUrl(u) }
                    chips.add(this)
                })
            }
            container.addView(android.widget.HorizontalScrollView(ctx).apply {
                isHorizontalScrollBarEnabled = false
                overScrollMode = android.view.View.OVER_SCROLL_NEVER
                addView(chipsRow)
            })
            qrDialogChips = chips
        }
        val qrSurface = androidx.cardview.widget.CardView(ctx).apply {
            radius = dpf(12f)
            setCardBackgroundColor(android.graphics.Color.WHITE)
            cardElevation = 0f
            layoutParams = LinearLayout.LayoutParams(dp(220), dp(220)).also { it.topMargin = dp(16) }
        }
        val iv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_XY
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        qrSurface.addView(iv)
        qrDialogImage = iv
        container.addView(qrSurface)
        qrDialogUrlText = TextView(ctx).apply {
            textSize = 12f
            setTextColor(color(R.color.text_secondary))
            typeface = android.graphics.Typeface.MONOSPACE
            // Pass-8 (S2): the complete URL, selectable for long-press copy.
            maxLines = 1
            setTextIsSelectable(true)
        }
        qrDialogUrlScroll = android.widget.HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(14) }
            addView(qrDialogUrlText)
        }
        container.addView(qrDialogUrlScroll)
        qrDialogCaption = TextView(ctx).apply {
            textSize = 11f; setTextColor(color(R.color.text_muted))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(6) }
        }
        container.addView(qrDialogCaption)
        // Actions: Copy (the address currently shown) + Close, one row.
        container.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.topMargin = dp(6) }
        }.also { actions ->
            fun actionBtn(label: String, onTap: () -> Unit) = TextView(ctx).apply {
                text = label
                textSize = 14f; setTextColor(color(R.color.accent_light))
                gravity = Gravity.CENTER
                isClickable = true; isFocusable = true
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_icon_ripple)
                setPadding(dp(20), dp(10), dp(20), dp(10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.marginEnd = dp(6) }
                setOnClickListener { onTap() }
            }
            actions.addView(actionBtn("Copy") { activeQrUrl?.let { copyUrlToClipboard(it) } })
            actions.addView(actionBtn("Close") { qrDialog?.dismiss() })
        })

        qrDialog = AlertDialog.Builder(ctx)
            .setView(container)
            .setOnDismissListener {
                qrVisible = false; qrDialog = null; qrDialogImage = null
                qrDialogChips = emptyList(); qrDialogUrlText = null; qrDialogUrlScroll = null; qrDialogCaption = null
            }
            .create()
        qrDialog?.window?.setBackgroundDrawableResource(android.R.color.transparent)
        qrDialog?.window?.setDimAmount(0.55f)
        // ── Pass-26 (#9): the QR modal is a full-screen AlertDialog window —
        // apply the app's system-bar colors so it never drops a black
        // status/navigation bar zone over the screen while it is open.
        qrDialog?.applyLdxWindowChrome()
        qrVisible = true
        qrDialog?.show()
        selectQrUrl(targetUrl)
    }

    /** Point the open QR modal at [url]: chips re-tint, the URL text and
     *  caption follow, the bitmap swaps (memoized per URL). */
    private fun selectQrUrl(url: String) {
        activeQrUrl = url
        refreshQrModalSelection()
        showQrFor(url)
    }

    private fun refreshQrModalSelection() {
        val urls = serverUrlsForActions()
        for (chip in qrDialogChips) {
            val sel = chip.tag == activeQrUrl
            chip.background = ContextCompat.getDrawable(requireContext(), if (sel) R.drawable.bg_btn_primary else R.drawable.bg_btn_secondary)
            chip.setTextColor(if (sel) android.graphics.Color.WHITE else color(R.color.text_secondary))
        }
        qrDialogUrlText?.text = activeQrUrl ?: ""
        qrDialogCaption?.text = ""
    }

    private fun showQrFor(url: String) {
        // Phase 8 (§8.3): a QR encode allocates a 512×512 matrix + a 262 KB
        // IntArray + a 512 KB RGB_565 bitmap. Pass-7 upgraded the
        // single-entry memo to a tiny per-URL LRU map: the modal's source
        // chips switch between addresses, and switching back must not pay
        // the encode again.
        qrBmpCache.remove(url)?.let { bmp ->
            qrBmpCache[url] = bmp   // refresh LRU order
            qrDialogImage?.setImageBitmap(bmp); return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
            val matrix = QRCodeWriter().encode(url, BarcodeFormat.QR_CODE, 512, 512, hints)
            val w = matrix.width; val h = matrix.height
            val pix = IntArray(w * h) { i -> if (matrix[i % w, i / w]) 0xFF0d0d1e.toInt() else 0xFFFFFFFF.toInt() }
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.RGB_565)
            bmp.setPixels(pix, 0, w, 0, 0, w, h)
            if (qrBmpCache.size >= 6) qrBmpCache.remove(qrBmpCache.keys.first())
            qrBmpCache[url] = bmp
            withContext(Dispatchers.Main) { qrDialogImage?.setImageBitmap(bmp) }
        }
    }
    // Phase 8 (§8.3) → Pass-7: QR encode memoization, now a tiny LRU map so
    // chip-switching between several addresses never re-encodes (see
    // showQrFor's comment). A LinkedHashMap with access-order off — manual
    // remove+re-put keeps insertion order = LRU order, no extra imports.
    private val qrBmpCache = LinkedHashMap<String, android.graphics.Bitmap>()

    /** Phase 18 V8: builds ONE list mixing browser clients (LumiDropServer)
     *  and native P2P peers (NativeDiscovery) for DeviceAdapter, instead of
     *  the two being tracked, counted and rendered by entirely separate
     *  functions/cards. Native peers are listed first -- they're literally
     *  other LumiDrop X installs, the closest match to what "Connected
     *  Devices" suggests, with browser sessions (any device with the web
     *  page open, not just other copies of this app) after. Sending logic
     *  is untouched: showTargetPickerAndSend() below still reads
     *  server.connectedIPs and NativeDiscovery.snapshot() independently.
     *
     *  Phase 2 of 10: this function is now a PURE READ of authoritative
     *  server state. It used to REAP the server's session maps itself
     *  (connectedIPs.removeAll older-than-30s + connectedNames.retainAll,
     *  from the UI thread) — a second mutator with its own staleness
     *  opinion, racing the SSE ticker's liveness refreshes: a live device's
     *  fresh timestamp could be deleted anyway by the iteration gap (the
     *  residual "connected device randomly disappears" root cause), and
     *  with this fragment's poll paused (other tab / backgrounded) nothing
     *  reaped at all. Termination is sweepSessions()'s job alone now, on
     *  the server's own clock; liveBrowserIps() answers "connected right
     *  now" with the same TTL-or-live-SSE rule every other consumer uses. */
    private fun updateDevicesUI() {
        if (_binding == null) return
        // Phase 18: card itself is never hidden anymore -- it shows
        // devicesEmptyState instead. server can still be null (not started
        // yet / just stopped), in which case there's simply nothing active.
        binding.cardDevices.visibility = View.VISIBLE

        val srv = server
        // Read-only: one authoritative answer, deterministically IP-sorted
        // so cards never reshuffle between ticks, approval-filtered when
        // Connection Confirmation is on.
        val browserIps: List<String> =
            srv?.liveBrowserIps(SettingsManager.isConfirmConn(requireContext())) ?: emptyList()
        val nativePeers = if (isRunning) NativeDiscovery.snapshot() else emptyList()

        val rows = mutableListOf<DeviceRow>()
        for (peer in nativePeers) {
            val activity = TransferManager.inFlightSnapshot().filter { it.peerIp == peer.ip }
            val sending = activity.count { it.direction == TransferManager.Direction.SEND }
            val receiving = activity.size - sending
            val statusText = when {
                activity.isEmpty() -> peer.ip
                sending > 0 && receiving > 0 -> "Sending $sending . Receiving $receiving"
                sending > 0 -> if (sending == 1) "Sending 1 file" else "Sending $sending files"
                else -> if (receiving == 1) "Receiving 1 file" else "Receiving $receiving files"
            }
            rows.add(DeviceRow.Native(peer.ip, peer.displayName, peer.isStale, statusText))
        }
        if (srv != null) for (ip in browserIps) {
            val customName = srv.deviceNames[ip] ?: ""
            val ua = srv.connectedUAs[ip] ?: ""
            val autoLabel = if (ua.isNotEmpty()) srv.parseDeviceLabel(ua) else null
            val primaryLabel = customName.ifEmpty { autoLabel ?: ip }
            rows.add(DeviceRow.Browser(ip, primaryLabel, customName,
                srv.getDeviceMode(ip) == "full", srv.clientCaps[ip] == true))
        }

        val hasDevices = rows.isNotEmpty()
        // Count only means something once there's something to count;
        // refresh stays visible regardless -- Phase 18 dropped the expand
        // toggle entirely, so the list (or the empty state) is always
        // shown directly, never gated behind a tap.
        binding.tvDeviceCount.visibility = if (hasDevices) View.VISIBLE else View.GONE
        // Pass-18: the blocked-clients indicator lives in the header, above
        // every early return below — a kicked client often leaves ZERO
        // connected devices behind (its row left the list with it), and the
        // icon must still show so the block can be discovered and undone.
        // Server null (stopped) = nothing can be blocked = gone, matching
        // the server-instance lifecycle exactly (kickedIPs dies with the
        // instance — a restart unblocks everything, as it always has).
        binding.btnBlockedDevices.visibility =
            if (srv != null && srv.hasBlockedClients()) View.VISIBLE else View.GONE
        binding.devicesEmptyState.visibility = if (hasDevices) View.GONE else View.VISIBLE
        binding.devicesList.visibility = if (hasDevices) View.VISIBLE else View.GONE
        // Pass-5 (R1 — THE fix): the WRAPPER is toggled with the list it
        // hosts. It used to stay visible (an empty but weight-bearing
        // FrameLayout) and split the card's height with the empty state —
        // clipping the ring, hiding the texts, and shrinking the later
        // device list to the same squeezed half. GONE here = the empty
        // state owns the full area.
        binding.devicesListWrap.visibility = if (hasDevices) View.VISIBLE else View.GONE

        if (!hasDevices) {
            deviceAdapter.submit(emptyList())
            // ── Pass-22 (#7 — the LAST height-changing transition, root-fixed) ──
            // Until here the empty state sized the card by its natural WEIGHT
            // (height=0/weight=1 — whatever the column hands it) while the
            // populated state pinned an EXACT latched height — two different
            // regimes, so every connect/disconnect flipped the card between
            // two numbers that only USUALLY agreed. They disagreed precisely
            // when the latch's regime probe read a different sibling/column
            // configuration than the one live at the transition (the reported
            // "connecting a device made the card smaller"): the pin latched in
            // the over-tall regime reads the empty state's CONTENT height,
            // which is smaller than the weighted fill the card was rendering
            // at a moment before the device arrived — the connect then SNAPPED
            // the card down to the pin. Both states now use the SAME exact
            // number: the empty branch pins to the identical latched height,
            // so the card's height is byte-stable across empty↔populated by
            // construction — a connect or disconnect changes ONLY the card's
            // content, never its frame. (The pin itself is the height the
            // EMPTY state naturally rendered at latch time — the exact
            // configuration the requirement calls "from the beginning" —
            // computed in the empty-content configuration, latched once per
            // viewport, re-latched only on rotation/resize.)
            val cardLp = binding.cardDevices.layoutParams as LinearLayout.LayoutParams
            val pinnedHeight = pinnedDevicesCardHeightPx()
            // Pass-28 (#3): 0 = no honest geometry yet (nothing latched) — leave
            // the card in its natural weighted configuration; the first measured
            // layout pass latches the true pin (see onSendViewportChanged).
            if (pinnedHeight > 0 && (cardLp.height != pinnedHeight || cardLp.weight != 0f)) {
                cardLp.height = pinnedHeight
                cardLp.weight = 0f
                binding.cardDevices.layoutParams = cardLp
            }
            return
        }
        binding.tvDeviceCount.text = "(${rows.size})"
        deviceAdapter.submit(rows)
        // Pass-6 (R-B): a freshly-bound list (row count changed, or the wrapper
        // was just re-sized above) must re-derive its fade visibility after the
        // new layout settles — a list that grew past its box lights its bottom
        // fade now, before the first scroll ever happens.
        binding.devicesListWrap.post { if (_binding != null) updateDeviceEdgeFades() }
        // ── Pass-12 (Connected Devices gradient fix, Android 5 tablets) ──────
        // The pass-8 "exact size" mechanism latched the wrapper to the height
        // captured from the EMPTY state (or a 164dp fallback when no empty
        // frame had ever been laid out — a host restart with an instantly
        // reconnecting peer). On a 10-inch Android 5 tablet the card is far
        // taller than that number, so the wrapper (and with it the bottom fade
        // band, pinned to the wrapper's bottom edge) ended up in the MIDDLE of
        // the card — exactly the reported gradient bug. The wrapper now simply
        // FILLS whatever space the card gives it (height=0dp + weight=1f, the
        // same mechanism the Transfers subtabs use — the reference
        // implementation the person confirmed works on that same tablet), so
        // the fade bands sit at the card's true rims on every screen size and
        // Android version, and the empty state fills exactly the same space,
        // keeping the card byte-for-byte the same size either way.
        val lp = binding.devicesListWrap.layoutParams as LinearLayout.LayoutParams
        if (lp.height != 0 || lp.weight != 1f) {
            lp.height = 0
            lp.weight = 1f
            binding.devicesListWrap.layoutParams = lp
        }
        // ── Pass-13 (the card itself is pinned to the empty-state height) ────
        // Weight-fill alone only holds while the Send tab's natural content
        // is shorter than the viewport; past it, the NestedScrollView measures
        // unconstrained and the card grows with every device (the reported
        // regression). While devices are shown, the CARD is pinned so its
        // dimensions are identical to the empty state by construction, and
        // the list scrolls inside it. The empty state restores the natural
        // weighted sizing.
        val cardLp = binding.cardDevices.layoutParams as LinearLayout.LayoutParams
        // ── Pass-21: the LATCHED pin ─────────────────────────────────────
        // Same discipline as Pass-16's re-derive, but computed once per
        // viewport and frozen (pinnedDevicesCardHeightPx): the number cannot
        // move when the Queue Files card opens or closes, because nothing
        // recomputes it. Layout params are only touched when the value
        // actually changed, so a stable column costs nothing.
        val pinnedHeight = pinnedDevicesCardHeightPx()
        // Pass-28 (#3): same unmeasured-viewport guard as the empty branch —
        // never pin from anything but a latched, honestly-measured value.
        if (pinnedHeight > 0 && (cardLp.height != pinnedHeight || cardLp.weight != 0f)) {
            cardLp.height = pinnedHeight
            cardLp.weight = 0f
            binding.cardDevices.layoutParams = cardLp
        }
    }

    private fun showRenameDialog(ip: String, currentCustomName: String) {
        val input = Widgets.inputField(requireContext(), currentCustomName, android.text.InputType.TYPE_CLASS_TEXT).apply {
            hint = "Custom device name (leave blank to reset)"
        }
        showLdxDialog(
            context = requireContext(),
            title = "Rename device",
            iconRes = R.drawable.ic_edit,
            bodyView = input,
            actions = listOf(
                LdxDialogAction("Cancel", LdxButtonStyle.GHOST),
                LdxDialogAction("Reset", LdxButtonStyle.GHOST) { server?.setDeviceName(ip, ""); updateDevicesUI() },
                LdxDialogAction("Save", LdxButtonStyle.PRIMARY) { server?.setDeviceName(ip, input.text.toString().trim()); updateDevicesUI() },
            ),
        )
    }

    // ── Pass-18 (session-scoped unblock): the blocked-devices sheet ────────────
    /** Lists every client blocked in THIS server session with a per-device
     *  Unblock action. Rows leave the sheet the moment their Unblock tap
     *  lands (server.unblockDevice + row removal, so the list is always the
     *  live truth), and the sheet dismisses itself when the last row goes —
     *  the header icon disappears on the very same tap via the poller's
     *  next updateDevicesUI pass. Build-once-then-mutate: the dialog and its
     *  rows are plain views, re-rendered only by re-opening the sheet (the
     *  blocked set can only change through THIS sheet or a Kick, both of
     *  which the host user is doing, so there is no third-party mutation to
     *  race). */
    private fun showBlockedDevicesSheet() {
        val ctx = requireContext()
        val blocked = server?.blockedClientsSnapshot().orEmpty()
        if (blocked.isEmpty()) { toast("No blocked devices in this session"); return }

        val list = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // LdxDialog renders EITHER a message OR a bodyView — the sheet's
        // explanatory line therefore lives as the body's own muted header.
        list.addView(TextView(ctx).apply {
            text = "Blocked in this server session. Unblock a device to let it reconnect, or restart the server to clear all blocks."
            textSize = 12.5f
            setTextColor(color(R.color.text_secondary))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.bottomMargin = dp(6) }
        })
        var dialog: Dialog? = null
        for ((ip, label) in blocked) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(8), dp(2), dp(8))
            }
            val nameBlock = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            nameBlock.addView(TextView(ctx).apply {
                text = label
                textSize = 14f; typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(color(R.color.text_primary))
            })
            nameBlock.addView(TextView(ctx).apply {
                text = ip
                textSize = 11f
                setTextColor(color(R.color.text_muted))
            })
            row.addView(nameBlock)
            row.addView(TextView(ctx).apply {
                text = "Unblock"; textSize = 11.5f
                setTextColor(color(R.color.accent_light))
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_ghost)
                setPadding(dp(10), dp(7), dp(10), dp(7))
                isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).also { it.marginStart = dp(10) }
                setOnClickListener {
                    server?.unblockDevice(ip)
                    toast("$label unblocked — it can reconnect now")
                    list.removeView(row)
                    if (server?.hasBlockedClients() != true) dialog?.dismiss()
                }
            })
            list.addView(row)
        }
        dialog = showLdxDialog(
            context = ctx,
            title = "Blocked devices",
            iconRes = R.drawable.ic_blocked,
            iconTintRes = R.color.orange,
            bodyView = list,
            actions = listOf(LdxDialogAction("Close", LdxButtonStyle.GHOST)),
        )
    }

    private fun showKickConfirm(ip: String, displayName: String) {
        showLdxDialog(
            context = requireContext(),
            title = "Kick $displayName?",
            message = "This device will be blocked from LumiDrop X for the rest of this server session — you can unblock it from the blocked-devices icon in this card, or restart the server to clear all blocks.",
            iconRes = R.drawable.ic_devices,
            iconTintRes = R.color.red_stop,
            actions = listOf(
                LdxDialogAction("Cancel", LdxButtonStyle.GHOST),
                LdxDialogAction("Kick", LdxButtonStyle.DESTRUCTIVE) { server?.kickDevice(ip); updateDevicesUI(); toast("$displayName kicked") },
            ),
        )
    }

    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                delay(500)
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    // ── Pass-29 Part 1 (#1A/#1B): the card-height verification ────
                    // runs even with the server STOPPED. The old
                    // `if (!isRunning) continue` gate meant that with zero
                    // devices connected and the server not running, NOTHING
                    // ever re-derived the Connected Devices card's pin — so a
                    // value minted from a transiently-wrong measure (or one
                    // left over from a mid-animation blackout-exit frame)
                    // stayed wrong forever, which read on the device as "the
                    // card stays short after Blackout until I open some other
                    // sheet" (the sheet's bar interaction churned the layout
                    // key, and THAT is what actually forced the re-derivation).
                    // updateDevicesUI is cheap and read-only with the server
                    // down — empty rows plus one deterministic two-pass
                    // measure for the pin (see pinnedDevicesCardHeightPx's
                    // Pass-29 note). This also covers rotation: the very first
                    // tick after any orientation change re-derives the pin at
                    // the new geometry even if no other event did.
                    updateDevicesUI()
                    // Pass-29 Part 1 (#6/#7): the queue card's tick is
                    // fingerprint-gated inside updatePreviewCard and staged
                    // items are purely local state — they keep rendering (and
                    // their counter keeps settling) with the server stopped.
                    updatePreviewCard()
                    if (!isRunning) return@withContext
                    updateUI()
                    ServerForegroundService.instance?.refreshNotification()
                    checkPendingConnRequests()
                }
            }
        }
    }

    private fun checkPendingConnRequests() {
        if (connDialogShowing || !isAdded || _binding == null) return
        val pending = server?.pendingConnRequests()?.firstOrNull() ?: return
        val ip = pending.first
        val req = pending.second
        connDialogShowing = true
        showLdxDialog(
            context = requireContext(),
            title = "Connection request",
            message = "${req.label}\n${req.deviceDetail}\nIP: ${req.ip}\n\nAllow this device to connect for this session?",
            iconRes = R.drawable.ic_devices,
            cancelable = false,
            actions = listOf(
                LdxDialogAction("Reject", LdxButtonStyle.GHOST) { server?.decideConnRequest(ip, false); connDialogShowing = false; checkPendingConnRequests() },
                LdxDialogAction("Accept", LdxButtonStyle.PRIMARY) { server?.decideConnRequest(ip, true); connDialogShowing = false; checkPendingConnRequests() },
            ),
        )
    }

    // Quick Share -- files --------------------------------------------------------

    // GOAT (Phase 1, item 3): resolves every uri's metadata concurrently --
    // same per-item IO as the old enqueueUri (name/size/real-path lookups
    // don't depend on each other) -- but waits for the WHOLE batch before
    // staging or sending anything. The previous shape (uris.forEach {
    // enqueueUri(it) }) launched one independent coroutine per uri, each
    // hopping to Main and calling stageOrSendItem() for just its own item
    // the instant ITS OWN lookup finished, in whatever order those N
    // independent IO calls happened to complete. With Auto Send on, that
    // meant an N-item selection fired N separate, uncoordinated
    // single-item NativePushClient.sendToPeers() calls -- N separate
    // announce/manifest/accept round-trips racing each other to the same
    // peer(s) -- instead of the one batched session runSession's own
    // deterministic-admission-order logic assumes, which is what actually
    // dropped items under real concurrent load (see stageOrSendItems).
    // Batching the resolve step here is what lets every item reach
    // sendToPeers() together, as one real batch, exactly like a folder or
    // a manual multi-select "Send" already do.
    private fun enqueueUris(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // ── Pass-31 (#2): ALL-AT-ONCE staging, restored — with the large-batch ──
        // latency fixed at its actual source. Pass 29 resolved every picked
        // uri with THREE separate content-resolver queries (display name,
        // size, real path) and only then staged the batch once: complete, but
        // a second or more of nothing-happens on a thousand-file pick. Pass 30
        // made the card appear instantly by staging in 24-file chunks — which
        // traded the completeness guarantee away: the queue presented a
        // PARTIAL batch as if it were ready, and a quick Send tap could ship
        // half a selection (the reported regression).
        // The correct shape is BOTH properties, and neither is sacrificed:
        //   • ONE MERGED metadata query per file (name + size + _data in a
        //     single IPC round-trip instead of three — the real large-batch
        //     cost, cut to a third), resolved for the WHOLE batch in parallel
        //     on Dispatchers.IO;
        //   • ONE stageOrSendItems commit for the entire pick, in exact pick
        //     order — the card appears with the complete batch, the counters
        //     are accurate from the first frame, and no partial queue is ever
        //     exposed. Auto-Send flows through the same single commit
        //     (stageOrSendItems reads the setting itself, exactly as it did
        //     before Pass 30 added its separate branch).
        // The manual Send button is additionally gated on stagingBatchesInFlight
        // (see showTargetPickerAndSend), so a Send tapped inside the now-short
        // resolution window waits for the commit instead of racing it.
        stagingBatchesInFlight++
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val items = uris.map { uri ->
                async { resolvePreviewItem(uri) }
            }.awaitAll()
            withContext(Dispatchers.Main) {
                stagingBatchesInFlight--
                if (_binding != null && isAdded) {
                    stageOrSendItems(items)
                    // A Send tap landed while this batch was still resolving —
                    // it deliberately waited; now that the complete batch is
                    // committed, run it against the full queue.
                    if (sendWantedWhileStaging && stagingBatchesInFlight == 0) {
                        sendWantedWhileStaging = false
                        showTargetPickerAndSend()
                    }
                } else {
                    // The view died mid-resolution — nothing was committed and
                    // nothing will be; a deferred send dies with it.
                    sendWantedWhileStaging = false
                }
            }
        }
    }

    /** ── Pass-31 (#2): ONE content-resolver query per picked file — the merged ──
     *  replacement for Pass-29's three separate helpers (display name, size,
     *  real path: three IPC round-trips per file, which is what made a
     *  thousand-file pick take a second or more before the queue could even
     *  appear). The semantics are exactly the three old reads, unchanged:
     *  the display name (with the same share_<timestamp> fallback when the
     *  provider has none), the size (0 when absent), and the real filesystem
     *  path — kept ONLY when the file it names actually exists on disk, the
     *  exact guard getRealPath always applied. */
    private fun resolvePreviewItem(uri: Uri): PreviewItem {
        var name: String? = null
        var size = 0L
        var realPath: String? = null
        try {
            requireContext().contentResolver.query(uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE, "_data"),
                null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) name = c.getString(ni)
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    val di = c.getColumnIndex("_data")
                    if (di >= 0) realPath = c.getString(di)
                }
            }
        } catch (e: Exception) { /* unreadable provider → the same fallbacks the old helpers had */ }
        return PreviewItem(
            name ?: "share_${System.currentTimeMillis()}",
            realPath?.takeIf { File(it).exists() },
            uri.toString(),
            size)
    }

    // ── Pass-33 (#6): enqueueFolder (the SAF tree stager behind the retired
    // Folder tile) is removed with its only caller — folder selection now
    // stages PATH-backed folder items straight from the unified Files
    // picker, which the queue's existing physical-folder pipeline (expand,
    // exclude-children, native push, browser zip) handles identically.

    // GOAT v2 (item 2 — album send): the album stager, for
    // one or more whole albums selected as a unit in the Media Selector.
    // Each becomes its own folder-type PreviewItem using the album's exact
    // name as the root folder name (see MediaAlbum.folderPath's own doc
    // comment for why every item sharing one bucket is guaranteed to
    // share one real parent directory, making this always a genuine,
    // single, existing folder rather than a synthetic grouping) and its
    // real path rather than a SAF uri -- there is no SAF grant for an
    // arbitrary MediaStore bucket the person never explicitly picked via
    // a tree chooser, only the broad storage access already required to
    // reach the Media Selector at all (see MediaLibrary.kt). Batched
    // through stageOrSendItems together, the same way a manual multi-
    // select send already batches, rather than one stageOrSendItem call
    // per album.
    // ── Pass-32 (#1): an album selection now queues the album's ACTUAL ───────
    // MEDIA, not its storage folder. The old path staged [folderPath] as a
    // folder-type item — so selecting e.g. the "Download" album queued the
    // entire physical Download directory: APKs, documents, subfolders and
    // everything else the album view never displayed rode along through the
    // folder-send engine. The Media Picker already knows the exact member
    // set (MediaAlbum.items — the same newest-first list the album grid was
    // grouped from), so each member stages as its OWN ordinary media item,
    // carrying its real filename, size, MIME type, content URI and — when
    // the provider exposes one — its filesystem path. No folder item is
    // created, nothing is walked recursively, and no per-folder special
    // case exists: the same code path handles every album (including
    // thousand-item buckets) identically, batched through the same single
    // all-at-once commit (Pass 31) that a manual multi-select uses.
    private fun enqueueAlbums(albums: List<MediaAlbum>) {
        if (albums.isEmpty()) return
        val items = ArrayList<PreviewItem>(albums.size)
        for (album in albums) {
            if (album.items.isEmpty()) {
                toast("Can't send \"${album.name}\" — it has no media to queue")
                continue
            }
            // ── Pass-33 (#1): the album queues as ONE FOLDER — the ────────────
            // representation the Queued Files section always had for albums,
            // restored — but now a LOGICAL folder whose contents are FIXED at
            // this moment to exactly the members the Media Picker displayed in
            // the album (MediaAlbum.items, built from the same filtered bucket
            // list the album grid grouped from; under a Photos/Videos filter
            // that is the filtered subset, never the whole bucket). The
            // manifest rides the PreviewItem through every consumer — the
            // queue's expand view and counter pill, the native-push manifest,
            // the browser ZIP streamer — so "Download" expands, counts and
            // transfers as precisely its shown images/videos, with the
            // physical directory's APKs, documents and subfolders excluded by
            // construction (no directory is ever walked). name/path keep the
            // album's own identity and real bucket directory so the folder
            // reads and dedups like the folder it represents.
            items.add(PreviewItem(
                name = album.name,
                path = album.folderPath,
                uri = null,
                size = album.totalBytes,
                mime = "application/zip",
                isFolder = true,
                childManifest = album.items.map { m ->
                    PreviewItem.FolderChild(
                        relPath = m.name,
                        path = m.path,
                        uri = m.uri.toString(),
                        size = m.size,
                        mime = m.mime.ifEmpty { "application/octet-stream" },
                    )
                },
            ))
        }
        if (items.isNotEmpty()) stageOrSendItems(items)
    }

    // ── GOAT-PASS 14 Phase 2 (§6/§7): the Text Share modal, rebuilt as a CENTERED
    // keyboard-aware composer inside the platform's standard editing window.
    //
    // GOAT-PASS 7–12 progressively fixed the theme (GP7), the field (GP8), the
    // web half (GP9), the immersive interference (GP10), the dialog class
    // (GP11) and the soft-input mode (GP12) — and the native long-press /
    // double-tap / Select-All / Copy / Cut / Paste toolbar STILL failed to
    // appear reliably on Android 14.
    //
    // ── FINAL PASS 6 (PART 1) — the actual root cause, found in the AOSP ──────
    // Android 14 sources (Editor.java, TextView.java): every one of those
    // passes fixed a REAL interference layer, but none of them addressed the
    // one gate that decides whether the platform's own long-press selection
    // chain runs at all — Editor.extractedTextModeWillBeStarted(), which
    // returns true (and silently cancels the selection AND the floating
    // toolbar) whenever the connected IME is serving the field in extract/
    // fullscreen mode. On Android 14 the IME makes that per-field decision
    // from the EditorInfo it is handed: a MULTILINE field in a dialog window
    // with no imeOptions gets extract mode whenever the IME judges the
    // available height insufficient (small screens, large font scale,
    // landscape, most OEM keyboards). The fix is the platform's own per-field
    // opt-out — IME_FLAG_NO_EXTRACT_UI — plus the removal of GP14P2's
    // setTextIsSelectable(true) (a read-only-TextView API that was adding
    // editor-state risk to an editable field; an EditText is selectable by
    // default). See the input's own block comment for the full trace.
    //
    //   A. CONTEXT CHAIN — every view inside the dialog, the EditText
    //      included, is now constructed with the DIALOG's own themed context
    //      (dialog.context), not the host Activity's raw context. An
    //      AppCompatDialog hosting views minted from a DIFFERENT context is
    //      exactly the half-wired pairing where the floating text-selection
    //      toolbar — whose presentation and theme resolve through the view's
    //      context chain into the window's ActionMode machinery — fails to
    //      come up on OEM Android 14 builds while plain themed dialogs on the
    //      same device work. One context, one theme chain, end to end.
    //
    //   B. NO IMMERSIVE RESIDUE — the window applies bar COLORS only
    //      (SystemBarChrome.applyColors) and explicitly shows the navigation
    //      bar. The old applyLdxWindowChrome()+showNavigationBar() pair first
    //      HID the bar (while the Hide-Navigation-Bar preference is on) and
    //      then re-showed it — leaving the window's transient-bars-by-swipe
    //      behavior ARMED behind an editing surface, a quasi-immersive state
    //      some builds suppress floating UI over. The hide step is now never
    //      taken for THIS window at all (colors yes, hide never), so no
    //      immersive flag or behavior residue can exist on any API level.
    //
    //   C. CENTERED, KEYBOARD-AWARE POSITIONING (§7) — the card is centered
    //      in the window frame (Gravity.CENTER) instead of bottom-anchored.
    //      With SOFT_INPUT_ADJUST_RESIZE the frame shrinks when the IME opens
    //      and grows back when it closes, so the centered card rides up
    //      smoothly with the frame (animated by the platform's insets-synced
    //      resize on API 30+) and returns to its centered rest position on
    //      keyboard dismissal — it can never "fall toward the bottom", because
    //      nothing anchors it there. A short frame (small screen + tall
    //      keyboard) squeezes exactly the WEIGHTED input, which scrolls its
    //      own six lines — the header and both actions always stay reachable.
    //      The fit listener writes ONLY on a genuine window resize (keyboard
    //      open/close, rotation — never during a selection gesture) and only
    //      when the value actually changes; in the common case (frame taller
    //      than the card) it never writes at all.
    //
    //   D. UNCHANGED FROM GP8/GP12 — AppCompatDialog + Theme.AppCompat
    //      window, androidx AppCompatEditText, no scrollable ancestor, no
    //      custom touch/click/long-press listeners anywhere on the field or
    //      its card, fixed six-line input scrolling internally, and the
    //      platform's own selection handles, insertion menu and floating
    //      toolbar as the whole editing story (textIsSelectable asserted
    //      explicitly, belt-and-braces, after the inputType configuration).
    //
    //   E. The design — the app's own dialog card language (bg_dialog surface,
    //      accent icon chip + title header, ghost Cancel beside the primary
    //      affirmative pill, 180ms decelerate slide-up entrance / 150ms exit),
    //      sized to the content and centered like the composer it is. The
    //      affirmative label follows the shared Auto Send verb (§5): "Add"
    //      stages the text in the Queue, "Send" dispatches it immediately.
    private fun showTextShareDialog() {
        // ── FINAL PASS 7 (PART 1) — THE INPUT-ARCHITECTURE RECONSTRUCTION. ──────
        //
        // WHY RECONSTRUCT: six passes (GP7→FP6) progressively hardened a CUSTOM
        // editing window — a full-screen AppCompatDialog on the app's own
        // transparent theme, MATCH_PARENT window, custom clickable backdrop,
        // themed-context view chain — and the Android 14 native selection
        // toolbar still failed on the reporting device, even after FP6's
        // per-field IME_FLAG_NO_EXTRACT_UI (which closed a genuine AOSP gate:
        // Editor.extractedTextModeWillBeStarted() cancels the whole selection
        // chain while the IME serves the field in extract mode — see the
        // input's block comment below, kept). The one layer NO previous pass
        // ever changed is the WINDOW ITSELF: every fix kept rebuilding the
        // field inside the same nonstandard shell, and OEM floating-toolbar /
        // selection-handle implementations (DecorView's FloatingActionMode →
        // FloatingToolbar popup, a TYPE_APPLICATION_ABOVE_SUB_PANEL sub-window
        // of the HOSTING window) are only ever exercised by appcompat+OEM QA
        // against STANDARD floating dialog windows. A custom transparent
        // full-screen window is off that tested path — and it is also the one
        // variable this app can eliminate for free, because the app ALREADY
        // CONTAINS its own proof: LdxDialog (the Rename / New Folder dialogs)
        // is a plain android.app.Dialog on Theme_Translucent_NoTitleBar with
        // a plain framework EditText — and native long-press selection
        // demonstrably WORKS in those dialogs on the same device that fails
        // here. The composer now uses EXACTLY that known-good recipe.
        //
        // WHAT CHANGED (window scaffold only — every other behavior identical):
        //   • android.app.Dialog (was androidx AppCompatDialog) — the pure
        //     framework window. AppCompat's window callback wraps
        //     onWindowStartingActionMode (verified in the appcompat 1.6.1
        //     bytecode: startAsSupportActionMode intercepts even TYPE_FLOATING
        //     presentation through SupportActionModeWrapper layers) — one
        //     less re-wrapping between the editor's floating toolbar and the
        //     window; the platform's own DecorView path hosts it directly.
        //   • android.R.style.Theme_Translucent_NoTitleBar (was the custom
        //     Theme_LumiDropX_TransparentModal on Theme.AppCompat.NoActionBar)
        //     — the exact theme LdxDialog uses, i.e. the exact theme the
        //     working rename dialogs run under on this device class.
        //   • Views minted from the ACTIVITY's context (requireContext()), as
        //     LdxDialog does — the themed-context indirection GP14P2 added
        //     (views from dialog.context) is gone; the field is the same
        //     class LdxDialog's working fields are (framework EditText via
        //     AppCompatEditText inflation compatibility).
        //   • The dim/scrim behavior is reproduced EXACTLY as LdxDialog does
        //     it (FLAG_DIM_BEHIND + 0.55 amount) so the visual result — dim
        //     scrim, centered card, keyboard-aware ADJUST_RESIZE — is
        //     unchanged from FP6's.
        //
        // WHAT DID NOT CHANGE: the card's design and pinned-height keyboard
        // geometry (§7.C), the six-line internal-scrolling field with
        // IME_FLAG_NO_EXTRACT_UI, the counter, the Auto-Send-verb actions,
        // the verbatim-multiline send path (stageOrSendItem/PreviewItem),
        // the entrance/exit animations and the bounded dismiss, the
        // SystemBarChrome colors + explicit nav-bar show. One source of truth
        // for the text (the field itself); no duplicate state, no clipboard
        // code, no custom selection UI anywhere.
        val dialog = Dialog(requireContext(), android.R.style.Theme_Translucent_NoTitleBar)
        val ctx = requireContext()   // the ACTIVITY's context — same chain the working LdxDialog fields use
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        // Full-screen window: the dim scrim covers everything and the centered
        // card has the whole (keyboard-resized) frame to center in (see C).
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        // ── FINAL PASS 8 (PART 2): the chrome recipe is now LdxDialog's ─────────
        // VERBATIM — the ONE call it makes and nothing else. FP7 still called
        // SystemBarChrome.showNavigationBar(dialog.window) explicitly; that
        // call is exactly the "immersive-interference class alive again
        // through the back door" this codebase's own GP10 notes warn about:
        // on API 30+ it drives WindowInsetsController.show() on an EDITING
        // window, which arms the transient-bars-by-swipe behavior over the
        // field — and a selection-handle drag near the bottom of the window
        // reads to the system as that swipe. LdxDialog (the dialogs whose
        // native selection demonstrably works on the reporting Android 14
        // device) NEVER calls it; applyLdxWindowChrome() (colors + the
        // preference-gated hide path LdxDialog itself uses) replaces both
        // FP7 chrome calls so the recipe matches the working dialogs
        // character-for-character.
        dialog.applyLdxWindowChrome()
        dialog.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        dialog.window?.setDimAmount(0.55f)
        // ── GP12 (§7), kept: ADJUST_RESIZE — the bog-standard editing window. ──
        // The platform consumes the IME insets itself and resizes the decor
        // exactly like every standard dialog; the centered card (C) rides the
        // frame; no custom inset plumbing exists in this window.
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )

        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_dialog)
            setPadding(dp(20), dp(20), dp(20), dp(18))
            elevation = dp(10).toFloat()
        }

        // Header — icon chip + title, the app's standard dialog header.
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.bottomMargin = dp(14)
            }
        }
        header.addView(ImageView(ctx).apply {
            setImageDrawable(Widgets.icon(ctx, R.drawable.ic_edit, color(R.color.accent_light)))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_accent_badge)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).also { it.marginEnd = dp(10) }
        })
        header.addView(TextView(ctx).apply {
            text = "Quick Share Text"
            textSize = 16f; setTextColor(color(R.color.text_primary))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(header)

        // The input — a FIXED six-line field that scrolls internally. Its
        // height is a constant of the line count (never of the content), which
        // is what makes the card's pinned height genuinely permanent.
        // ── GOAT-PASS 8 (§6 — the native-selection rebuild), kept: the field is ──
        // an androidx.appcompat.widget.AppCompatEditText (the properly themed
        // editor this app's AppCompat window expects — a bare framework
        // EditText constructed in code never participates in AppCompat's
        // editor wiring), explicitly longClickable, with NO custom touch,
        // click or long-press listener anywhere on it or any ancestor inside
        // the card, and — the structural fix — NO parent ScrollView: a
        // scrollable ancestor is a classic interceptor of the selection-
        // handle drag gesture (the parent steals the move to scroll itself,
        // so the handle never extends the selection, and on several OEM
        // builds the whole long-press interaction reads as broken). The
        // EditText scrolls its own six lines internally
        // (isVerticalScrollBarEnabled + setLines(6)); the weighted layout
        // below lets a keyboard clamp squeeze exactly this field, which
        // then simply scrolls its own content — no outer scroller needed.
        // The platform's OWN selection handles, insertion menu and floating
        // toolbar (Select all / Copy / Cut / Paste) are the whole story.
        // ── GOAT-PASS 14 Phase 2 (§6.D): the field is constructed from the ──
        // DIALOG's themed context (ctx above — see §6.A).
        //
        // ── FINAL PASS 6 (PART 1 — THE ROOT-CAUSE FIX, traced through the ──────
        // ── actual Android 14 AOSP sources: TextView.java, Editor.java, ────────
        // ── ArrowKeyMovementMethod.java, DecorView.java): ──────────────────────
        //
        // WHY GP7→GP14P2's fixes were insufficient — the trace:
        //   Long-press on an editable field runs TextView.performLongClick →
        //   Editor.performLongClick → selectCurrentWordAndStartDrag()
        //   (Editor.java). That function's FIRST gate is
        //   extractedTextModeWillBeStarted(): "imm != null &&
        //   imm.isFullscreenMode()". When the soft keyboard decides to serve
        //   this field in EXTRACT (fullscreen) mode — which on Android 14 is
        //   the DEFAULT for multiline fields whenever the IME judges the
        //   available window height too small (small screens, large font
        //   scale, landscape, most OEM IMEs) — the function returns false
        //   BEFORE ever selecting a word or starting the drag: no selection,
        //   no handles, no floating toolbar, nothing. The same gate also
        //   blocks startActionModeInternal(), so even an existing selection
        //   could not raise its toolbar. None of the previous passes could
        //   have fixed this from the theme, the context chain or the window
        //   flags — the IME's extract decision is per-field EditorInfo state.
        //
        // THE FIX (two lines, at the exact layer the platform reads them):
        //   1. imeOptions = IME_FLAG_NO_EXTRACT_UI — the documented per-field
        //      EditorInfo flag that tells the IME to NEVER enter extract mode
        //      for THIS field. With the extract UI off, isFullscreenMode()
        //      stays false, the extractedTextModeWillBeStarted() gate passes,
        //      and the platform's own long-press chain (word select → drag
        //      handles → floating toolbar with Select all / Copy / Cut /
        //      Paste) runs exactly as it does in every standard edit dialog.
        //   2. setTextIsSelectable(true) is REMOVED — the GP14P2
        //      "belt-and-braces" call. Traced in TextView.java: that method
        //      is built for NON-editable TextViews ("mInputType should
        //      already be EditorInfo.TYPE_NULL" — its own comment). On an
        //      editable field it re-forces ArrowKeyMovementMethod (already
        //      the editable default) and re-runs setText(mText, SPANNABLE),
        //      which on several OEM builds re-enters the editor wiring AFTER
        //      the inputType was configured — the class of half-reconfigured
        //      editor state the previous passes kept hunting. An EditText is
        //      selectable BY DEFAULT (textCanBeSelected() → isTextEditable()
        //      is true); the call added risk and fixed nothing.
        //
        // Everything else GP7→GP14P2 established stays verbatim: no
        // scrollable ancestor, no custom touch listeners, ADJUST_RESIZE, the
        // explicit showNavigationBar, the pinned-height/weighted-squeeze
        // layout.
        //
        // ── FINAL PASS 7 (PART 1): the field itself is now the EXACT class ─────
        // the working rename dialogs use — a plain framework EditText minted
        // from the ACTIVITY's context (LdxDialog's Widgets.inputField recipe:
        // same class, same context chain, same platform editor wiring). The
        // androidx AppCompatEditText-from-dialog-context pairing GP14P2 built
        // is retired with the custom window; every property FP6 established
        // (IME_FLAG_NO_EXTRACT_UI above all) carries over unchanged.
        val input = android.widget.EditText(ctx).apply {
            setText("")
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            // FINAL PASS 6 (PART 1): never let the IME take this field
            // fullscreen — the extract-UI decision is exactly what the
            // Android 14 selection gate (extractedTextModeWillBeStarted)
            // consults. NO_EXTRACT_UI is honored by every conforming IME
            // (Gboard, Samsung, SwiftKey included).
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            hint = "Paste or type text to share..."
            setLines(6)
            gravity = Gravity.TOP or Gravity.START
            textSize = 14f
            setTextColor(color(R.color.text_primary))
            setHintTextColor(color(R.color.text_muted))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_input_field)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            isVerticalScrollBarEnabled = true
            setHorizontallyScrolling(false)
            // The explicit native-selection contract: a long-press inside THIS
            // field is the platform's to own — selection handles, the floating
            // toolbar, the clipboard integration. Nothing custom anywhere.
            // (FINAL PASS 6: isLongClickable/Clickable/Focusable stay — they
            // are the editable defaults restated; textIsSelectable is GONE,
            // see the block comment above.)
            isLongClickable = true
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val counter = TextView(ctx).apply {
            text = "0 characters"
            textSize = 10.5f; setTextColor(color(R.color.text_muted))
            gravity = Gravity.END
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(6)
            }
        }
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) {
                val n = s?.length ?: 0
                counter.text = "$n character${if (n != 1) "s" else ""}"
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // ── GOAT-PASS 8 (§6): the ScrollView wrapper is GONE — the field owns ────
        // its own internal scrolling (six fixed lines, its own vertical
        // scrollbar), and a scrollable ANCESTOR is precisely the interceptor
        // class the requirement calls out: it fights the selection-handle
        // drag (the parent steals the move to scroll itself, so the handle
        // never extends the selection) and on several OEM builds eats the
        // whole long-press interaction. The input + counter ride the card
        // directly; the pinned-height phase below weights the INPUT itself,
        // so a keyboard clamp squeezes exactly the field — which then
        // scrolls its own content, with the header and actions always
        // pinned (the same short-screen guarantee the ScrollView gave,
        // without the interception).
        val inputBody = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(input, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(counter)
        }
        card.addView(inputBody, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Actions — full-width ghost Cancel beside the primary affirmative pill,
        // pinned at the card's bottom by the fixed content above them.
        // ── GOAT-PASS 14 Phase 2 (§5): the affirmative label is the shared
        // Auto Send verb (quickShareActionVerb) — "Add" stages the text in the
        // Queue, "Send" dispatches it immediately; the tap's actual behavior
        // (stageOrSendItem) already follows that same setting, so the label
        // and the action can never disagree. Same padding, typeface and
        // styling; only the word is dynamic.
        val actionVerb = com.lumidrop.x.ui.quickShareActionVerb(requireContext())
        val actions = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.topMargin = dp(14)
            }
        }
        actions.addView(TextView(ctx).apply {
            text = "Cancel"; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(color(R.color.text_secondary))
            background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_ghost)
            setPadding(0, dp(13), 0, dp(13))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = dp(8) }
            setOnClickListener { dismissTextShareDialog(dialog, card) }
        })
        actions.addView(TextView(ctx).apply {
            text = actionVerb; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(color(R.color.white))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = Widgets.rippleRect(ctx, color(R.color.accent), radiusDp = 12)
            setPadding(0, dp(13), 0, dp(13))
            isClickable = true; isFocusable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                // Phase 6 (§6.6): what the person typed/pasted is what gets
                // delivered — verbatim, multiline/Unicode/whitespace intact.
                // Only a genuinely BLANK input is refused (isBlank covers
                // whitespace-only); trim() would corrupt content fidelity
                // (a trailing newline or indented first line would silently
                // change on arrival).
                val text = input.text.toString()
                if (text.isBlank()) { toast("Nothing to share"); return@setOnClickListener }
                // ── Pass-36 Part 2 (#1): content-derived queue title (see ──
                // textPreviewTitle) — the row names what was typed/pasted;
                // the verbatim payload still rides textContent untouched.
                stageOrSendItem(PreviewItem(textPreviewTitle(text), null, null, text.length.toLong(), "text/plain", textContent = text))
                dialog.dismiss()
            }
        })
        card.addView(actions)

        // ── FINAL PASS 8 (PART 2 — the geometry reconstruction): the whole ──────
        // §7.C pinned-height machinery is RETIRED — the pre-measure pass, the
        // height cap, the inputBody/input 0dp weight swap, and above all the
        // backdrop OnLayoutChangeListener that wrote card.layoutParams.height
        // on every window resize. That listener was the last structural
        // difference from the working rename dialogs: a keyboard-open inset
        // arrival mid-selection re-laid-out the ENTIRE card (a
        // layoutParams.height write is a full measure/layout pass), which on
        // several OEM builds cancels the in-flight selection drag and dismisses
        // the floating toolbar the moment it appears. LdxDialog's card is
        // plain WRAP_CONTENT with NO insets listener of its own — the
        // platform's ADJUST_RESIZE does the whole job (the window shrinks,
        // the CENTER-gravity card rides the smaller frame), and its selection
        // works on the exact device this pass is about.
        //
        // The six-line field keeps a FIXED height by construction (setLines(6)
        // — a constant of the line count, never of the content), so the card's
        // wrap height is just as stable as the old pinned one. A short frame
        // (small screen + tall keyboard) no longer squeezes the field — the
        // centered card simply sits within what remains, exactly like every
        // standard dialog; the backdrop keeps 16dp vertical margins.
        val sideMargin = dp(20)

        // Backdrop — full-screen, tap to dismiss, card CENTERED: the
        // resting position is the middle of the screen; the margins keep the
        // centered card clear of both screen edges.
        val backdrop = FrameLayout(ctx).apply {
            isClickable = true
            setOnClickListener { dismissTextShareDialog(dialog, card) }
        }
        backdrop.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
            it.gravity = Gravity.CENTER_VERTICAL or Gravity.CENTER_HORIZONTAL
            it.marginStart = sideMargin
            it.marginEnd = sideMargin
            it.topMargin = dp(16)
            it.bottomMargin = dp(16)
        })
        dialog.setContentView(backdrop)

        // ── FINAL PASS 8 (PART 2): the entrance is LdxDialog's exact ────────────
        // alpha/scale fade-in — NO translationY. The old slide-up (translation
        // only, 56dp) could be cancelled mid-flight by the window resize the
        // keyboard's first inset causes, stranding the card at a half-entry
        // position; an alpha+scale entrance has no positional state to strand
        // and is precisely what the working dialogs run.
        val animState = TextModalAnimState().also { card.tag = it }
        card.scaleX = 0.92f; card.scaleY = 0.92f; card.alpha = 0f
        dialog.show()
        // ── Pass-36 Part 2 (#2), kept: the alpha ride is a SEPARATE
        // ObjectAnimator — an animator cancel must never take the alpha down
        // with it (the invisible-modal bug that guard closed stays closed).
        animState.entranceAlpha = android.animation.ObjectAnimator.ofFloat(card, View.ALPHA, 0f, 1f).apply {
            duration = 180
            interpolator = Motion.DECELERATE
            start()
        }
        card.animate().scaleX(1f).scaleY(1f).setDuration(180).setInterpolator(Motion.DECELERATE).start()

        // Focus + open the keyboard only once the view is actually attached
        // and measured (post{}) — a bare requestFocus() called immediately
        // after show(), before the window has finished attaching, is exactly
        // the kind of half-measure that produces delayed-focus/keyboard-
        // flicker symptoms on some devices.
        input.post {
            input.requestFocus()
            val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(input, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** ── Pass-36 Part 2 (#2): the Text Share modal's animation state, ────────
     *  carried on the card's view tag so the follower, the entrance and the
     *  close path (a separate method) all share one truth. [entranceAlpha] is
     *  the entrance's own alpha animator — deliberately NOT a ViewPropertyAnimator
     *  ride, so the IME follower's translation-cancel can never freeze the
     *  card mid-fade; [dismissing] stands the follower down while the exit
     *  owns the card. */
    private class TextModalAnimState {
        var entranceAlpha: android.animation.ObjectAnimator? = null
        var dismissing = false
    }

    /** The Text Share modal's animated close: scale-down + fade over 150ms,
     *  then dismiss. Safe to call twice (the guard collapses it into a plain
     *  dismiss) — both the backdrop tap and the Cancel action route here.
     *  ── Pass-36 Part 2 (#2): the close is BOUNDED like the BottomSheet's —
     *  an animation cancel used to be able to kill the exit animation
     *  mid-flight and drop its withEndAction, leaving a fully transparent
     *  window (and its dim) darkening the app with no way out. The
     *  entrance stands down via the tag's dismissing flag, and a 300ms
     *  fallback closes the window even if the animation's end action never
     *  runs.
     *  ── FINAL PASS 8 (PART 2): the exit mirrors the entrance — scale+alpha
     *  (the LdxDialog rhythm), no translationY to strand. */
    private fun dismissTextShareDialog(dialog: Dialog, card: View) {
        if (!dialog.isShowing) { dialog.dismiss(); return }
        val animState = card.tag as? TextModalAnimState
        if (animState?.dismissing == true) return   // already closing
        animState?.let { it.dismissing = true; it.entranceAlpha?.cancel() }
        card.animate().cancel()
        card.animate()
            .scaleX(0.92f).scaleY(0.92f).alpha(0f)
            .setDuration(150).setInterpolator(Motion.STANDARD)
            .withEndAction {
                try { dialog.dismiss() } catch (e: Exception) { /* already gone */ }
            }
            .start()
        // The bounded guarantee: a cancel mid-exit still closes the window —
        // 300ms is the full animation + margin, so a normal close is always
        // the end action's, never this fallback's.
        card.postDelayed({
            if (dialog.isShowing) try { dialog.dismiss() } catch (e: Exception) { /* already gone */ }
        }, 300)
    }

    // Quick Share -- APK (Phase 15 par 4: searchable, multi-select) --------------

    // ── Pass-12 (robust search, hoisted Pass-13): the app matcher, shared by ──
    // the sheet's filter and its header counter line. The old matcher was a
    // raw label.contains(query) — typing "Lumi " (trailing space) found nothing
    // because "LumiDrop X" has no "Lumi " substring, and the package name was
    // never searched at all. This matcher:
    //   1. tokenizes the query on whitespace and requires EVERY token to match
    //      somewhere (label OR package name), so "Lumi ", "  Lumi ", "lumi x"
    //      and "lumidrop" all find LumiDrop X;
    //   2. also compares against letters/digits-only forms of all three
    //      strings, so punctuation and casing differences ("photo-editor" vs
    //      "Photo Editor") never hide a match.
    private fun matchesAppQuery(query: String, label: String, pkg: String): Boolean {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return true
        val hayLabel = label.lowercase()
        val hayPkg = pkg.lowercase()
        val normLabel = hayLabel.filter { it.isLetterOrDigit() }
        val normPkg = hayPkg.filter { it.isLetterOrDigit() }
        val tokens = q.split(Regex("\\s+")).filter { it.isNotBlank() }
        return tokens.all { t ->
            val nt = t.filter { it.isLetterOrDigit() }
            hayLabel.contains(t) || hayPkg.contains(t) ||
                (nt.isNotEmpty() && (normLabel.contains(nt) || normPkg.contains(nt)))
        }
    }

    private fun showApkShareDialog() {
        // Part 4 fix (App Selection Sheet startup lag): this used to launch
        // the IO work FIRST and only call showApkPickerSheet() once the
        // entire enumeration + per-app label lookup had finished -- for
        // 150+ installed apps, that's real wall-clock time spent with
        // nothing on screen reacting to the tap at all, which is what read
        // as a freeze. The sheet's OWN rendering was never the slow part
        // (see the chunked rebuild() below, and apkIconCache making every
        // reopen after the first essentially instant) -- the slow part was
        // making the person wait for data before they saw anything happen.
        // showApkPickerSheet() now shows immediately and loads its own data
        // in the background, the same fix applied to the Media Selector's
        // MediaStore query in this same phase.
        showApkPickerSheet()
    }


    // Phase 5 (§5.6 — the app picker's repeated-opening freeze, root cause
    // and fix): the old implementation rebuilt a ScrollView+LinearLayout
    // grid of up to ~1000 live Views (200 tiles x 5 views per tile) inside
    // a brand-new dialog on EVERY opening, chunked across Handler posts —
    // the first open looked smooth only because the entrance animation ran
    // while the grid filled in behind "Loading apps..."; every reopen paid
    // the full inflation cost again PLUS garbage-collected the previous
    // open's thousand views mid-animation, which is the visible stutter.
    // On top of that, the background work (PackageManager enumeration and
    // every icon decode) ran on the FRAGMENT's lifecycle scope, so closing
    // the sheet never cancelled any of it — rapid open/close cycles piled
    // orphaned decode storms on top of each other.
    //
    // This implementation follows the architecture the Media Selector
    // already proved in this codebase (see MediaPickerSheet.kt): a real
    // RecyclerView grid (only enough tiles to cover the viewport exist,
    // recycled as the person scrolls), a sheet-owned coroutine scope
    // cancelled the moment the sheet is dismissed, the app list cached at
    // fragment level with a 30s freshness window (re-submitted from cache
    // synchronously so a reopen's FIRST frame is fully populated), one
    // shared decode gate, and per-ViewHolder decode jobs cancelled on
    // rebind/recycle. No arbitrary delays anywhere — the sheet opens
    // instantly because there is nothing left to wait for.
    private fun showApkPickerSheet() {
        val ctx = requireContext()
        val pm = ctx.packageManager
        // ── GOAT-PASS 8 (§4): the selection-style read for this sheet's ──
        // gesture wiring — an application context captured once, read live.
        val appPickerCtx = ctx.applicationContext
        // ── Pass-26 (#3.1): one live App Picker, ever ────────────────────────────
        // The old flow built a brand-new BottomSheet dialog on every call with
        // nothing preventing a second call while the first was still on screen —
        // and "still on screen" is exactly the window a rapid double-tap on the
        // Apps tile (especially right after launch, when the first open is busy
        // enumerating and reads as "didn't register") falls into: two stacked
        // sheets, two dim layers, one broken dismissal. The guard below is set
        // SYNCHRONOUSLY, before any dialog machinery runs, and cleared in the
        // one dismiss listener every close path funnels through — so one tap
        // opens exactly one sheet no matter how fast the taps land, the sheet
        // still opens immediately, and no arbitrary delay is involved.
        if (apkPickerShowing) return
        apkPickerShowing = true
        // ── Pass-34 (#9): the App Picker gains ONLY drag-to-expand. The sheet ──
        // keeps its natural wrap-content height and its whole design; dragging
        // the handle upward grows it to full-screen height, and this single
        // callback is the one hook it needs for that — when expanded, the
        // grid's fixed-height frame trades its fixed px for weight so the
        // SAME grid simply gets the taller sheet's extra room (no layout
        // redesign, no new rows, everything else untouched). Collapse
        // restores the exact original geometry. The frame reference and its
        // default height arrive late (they are built inside show()'s content
        // builder below), so the callback reads them through these slots.
        var flexFrame: android.widget.FrameLayout? = null
        var flexFrameDefaultHeight = 0
        // ── Pass-35 (#1): the header row is also a drag region once the sheet
        // exists — built inside the content builder below, so it arrives
        // through a slot exactly like flexFrame above.
        var headerSlot: LinearLayout? = null
        val sheet = BottomSheet(ctx, expandable = true, onExpandedChange = { expanded ->
            try {
                val frame = flexFrame
                if (frame != null) {
                    val frameParams = frame.layoutParams
                    if (expanded) {
                        frameParams.height = 0
                        (frameParams as? LinearLayout.LayoutParams)?.weight = 1f
                    } else {
                        (frameParams as? LinearLayout.LayoutParams)?.weight = 0f
                        frameParams.height = flexFrameDefaultHeight
                    }
                    frame.layoutParams = frameParams
                    frame.requestLayout()
                }
            } catch (e: Exception) { /* never let a geometry swap break the sheet */ }
        })

        // Phase 5 (§5.6): sheet-scoped lifetime, exactly like the Media
        // Selector's pickerScope — everything this sheet launches dies with
        // it, so a dismissed sheet can never still be burning decode permits
        // or pushing icons into views nobody can see. SupervisorJob so one
        // failed decode can't cancel its siblings; Main.immediate because
        // every job starts and ends on the UI thread (the decode suspends
        // into the shared IO gate below).
        val sheetScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        // Selection state for THIS opening, keyed by package name (the old
        // HashSet<ApplicationInfo> relied on reference equality against list
        // instances that are now cached and re-served across openings —
        // package-name keys are stable no matter which list instance a row
        // came from).
        val checked = HashSet<String>()
        // The full list this opening knows about; the adapter always sees
        // the SEARCH-FILTERED subset of it. Declared here (before the
        // content lambda below) because the summary updater and the
        // adapter's onSelectionChanged both capture it by reference and
        // must read whatever the load/refresh path has most recently put in
        // it.
        var apps: List<Pair<ApplicationInfo, String>> = emptyList()
        var appsLoaded = false

        sheet.show { s ->
        // ── Pass-13 (App Picker redesign): professional, minimalist, compact ──
        // The pass-12 chrome carried a full-width action row (Cancel + "Share
        // selected"), a separate result-count caption under the grid, AND a
        // separate selection summary line — three rows of chrome around the
        // grid, with the app count repeated in two of them. The redesign:
        //   • ONE header (icon chip + "Share Apps") whose single subtitle line
        //     carries ALL live numbers — app count, filter effect, selection
        //     count and total size — so nothing occupies its own row and no
        //     number is ever shown twice.
        //   • NO Cancel button — the sheet already closes by dragging the
        //     handle down, tapping the backdrop, or the system back key; a
        //     fourth affordance was pure clutter.
        //   • The search field and a compact Share button live together in
        //     ONE statically-positioned bar floating over the BOTTOM of the
        //     list (search fills the row, Share sits right, smaller), with a
        //     card-colored gradient scrim so rows glide under it; the grid's
        //     own bottom padding clears the bar so every icon can scroll
        //     clear of it — it never permanently covers a tile.
        // All performance machinery is byte-for-byte unchanged: fragment-level
        // list/icon caches with the 30s freshness window, sheet-scoped
        // coroutines, the shared Semaphore(4) decode gate, off-main-thread
        // icon flattening, the 150ms search debounce, DiffUtil grid, 200 cap.
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), 0, dp(18), dp(16))
        }
        // ── Header: identity + the ONE integrated counter line ─────────────
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .also { it.bottomMargin = dp(12) }
        }
        header.addView(android.widget.FrameLayout(ctx).apply {
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpf(10f)
                setColor((color(R.color.accent) and 0x00FFFFFF) or 0x26000000)
                setStroke(dp(1), (color(R.color.accent_light) and 0x00FFFFFF) or 0x3D000000)
            }
            addView(ImageView(ctx).apply {
                setImageDrawable(Widgets.icon(ctx, R.drawable.ic_tile_apps, color(R.color.accent_light)))
                setPadding(dp(6), dp(6), dp(6), dp(6))
            })
            layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).also { it.marginEnd = dp(10) }
        })
        // The subtitle is the sheet's single live information line: app count
        // ("142 apps"), the filter's effect ("8 of 142 apps"), or the
        // selection ("3 selected · 12.4 MB") — integrated WITH THE TITLE as
        // its second line (per the pass-13 brief: never a separate row), and
        // never duplicated elsewhere (the Share button deliberately carries
        // no count; the count lives HERE and only here).
        val headerInfo = TextView(ctx).apply {
            text = "Loading apps…"
            textSize = 11.5f
            setTextColor(color(R.color.text_secondary))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        header.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(ctx).apply {
                text = "Share Apps"; textSize = 16f
                setTextColor(color(R.color.text_primary))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
            addView(headerInfo)
        })
        root.addView(header)
        headerSlot = header   // Pass-35 (#1): drag region, registered after show

        // ── Content frame: the grid, its states, and the floating bottom bar ─
        // Same landscape-safe height cap as before (a fixed dp alone can
        // exceed a landscape phone's entire screen height) — slightly taller
        // than pass-12's cap because the caption/summary/action rows it used
        // to reserve space for are gone; every reclaimed pixel is the grid's.
        val gridHeightPx = minOf(dp(404), (ctx.resources.displayMetrics.heightPixels * 0.46f).toInt())
        val contentFrame = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gridHeightPx)
        }
        // Pass-34 (#9): hand the drag-to-expand callback its frame + default
        // geometry (see BottomSheet's construction above).
        flexFrame = contentFrame
        flexFrameDefaultHeight = gridHeightPx
        val grid = RecyclerView(ctx).apply {
            layoutManager = GridLayoutManager(ctx, 4)
            clipToPadding = false
            setPadding(dp(2), dp(2), dp(2), dp(8))
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // In-place content swaps for a live-filtering list, same as the
            // Transfers tabs (see TransfersFragment's identical line).
            (itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        }
        contentFrame.addView(grid)

        val statusText = TextView(ctx).apply {
            textSize = 12.5f; setTextColor(color(R.color.text_muted))
            gravity = Gravity.CENTER
            text = "Loading apps…"
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).also {
                it.gravity = Gravity.CENTER; it.topMargin = dp(48)
            }
        }
        contentFrame.addView(statusText)

        // ── The floating bottom bar: card-colored scrim + search + Share ────
        // The scrim fades the sheet's own surface color in over the scrolling
        // tiles (same visual language as the app's scroll-edge fades), so rows
        // dissolve under the controls instead of clipping hard against them.
        //
        // ── Pass-15 (Android 5 glitchy-fade root fix): the gradient's first ──
        // stop was Color.TRANSPARENT — transparent BLACK. GradientDrawable
        // interpolates ARGB per channel, so on the un-premultiplied render
        // paths of API 21-22 every intermediate pixel blends from BLACK
        // toward the sheet color as its alpha rises: the band above the bar
        // rendered as a dark gray smear (the reported "glitchy fade effect
        // entirely above the Search and Share buttons") instead of a clean
        // dissolve. Both stops now carry the SHEET's own RGB — the first at
        // zero alpha — so the interpolation is alpha-only and the fade is
        // clean on every API level. The scrim keeps sitting UNDERNEATH the
        // Search/Share bar (z-order unchanged): the gradient renders behind
        // the buttons, never above them.
        val sheetRgb = color(R.color.bg_sheet) and 0x00FFFFFF
        val scrim = View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    (0x00 shl 24) or sheetRgb,                     // sheet color, fully transparent — NOT black
                    (0xE6 shl 24) or sheetRgb,                     // ~90% sheet surface
                    (0xFF shl 24) or sheetRgb,                     // fully opaque at the rim
                ),
            )
            isClickable = false; isFocusable = false
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(84), Gravity.BOTTOM)
        }
        contentFrame.addView(scrim)

        // ── Pass-15 (top gradient, scroll-gated): tiles that scroll up ────
        // under the "Share Apps" title card dissolve into the same sheet-
        // surface gradient, appearing ONLY while content is genuinely hidden
        // past the frame's top rim (grid.canScrollVertically(-1)) — exactly
        // the same only-when-scrollable rule the app's other edge fades use,
        // invisible at rest so the sheet stays clean and simple.
        val topFade = View(ctx).apply {
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    (0xFF shl 24) or sheetRgb,
                    (0xE6 shl 24) or sheetRgb,
                    (0x00 shl 24) or sheetRgb,
                ),
            )
            isClickable = false; isFocusable = false
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(22), Gravity.TOP)
        }
        contentFrame.addView(topFade)

        val bottomBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        }
        val BAR_H_DP = 48
        // The search slot: the app's standard input field with its leading
        // glyph, stretched to the bar's fixed height for a clean baseline with
        // the Share button beside it.
        val searchSlot = android.widget.FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(0, dp(BAR_H_DP), 1f)
        }
        val search = Widgets.inputField(ctx, "", android.text.InputType.TYPE_CLASS_TEXT).apply {
            hint = "Search apps"
            setPadding(dp(36), paddingTop, dp(12), paddingBottom)
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            // Enter closes the keyboard ONLY (Pass-13, per direct feedback):
            // the query stays, the filtered results stay, the selection stays,
            // the sheet stays. Consuming the action/key on both channels is
            // what keeps every soft keyboard's Enter (IME action AND raw key
            // event forms) from doing anything else.
            setOnEditorActionListener { v, _, _ ->
                val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                imm?.hideSoftInputFromWindow(v.windowToken, 0)
                true
            }
            setOnKeyListener { v, keyCode, event ->
                if (keyCode == android.view.KeyEvent.KEYCODE_ENTER &&
                    (event.action == android.view.KeyEvent.ACTION_UP || event.action == android.view.KeyEvent.ACTION_DOWN)) {
                    if (event.action == android.view.KeyEvent.ACTION_UP) {
                        val imm = ctx.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
                        imm?.hideSoftInputFromWindow(v.windowToken, 0)
                    }
                    true
                } else false
            }
        }
        searchSlot.addView(search)
        searchSlot.addView(ImageView(ctx).apply {
            setImageDrawable(Widgets.icon(ctx, R.drawable.ic_search, color(R.color.text_muted)))
            layoutParams = android.widget.FrameLayout.LayoutParams(dp(17), dp(17), Gravity.CENTER_VERTICAL or Gravity.START).also {
                it.leftMargin = dp(12)
            }
            isClickable = false; isFocusable = false
        })
        bottomBar.addView(searchSlot)
        // The Share action: compact (smaller than the search field it shares
        // the bar with), same fixed height for a flush row, icon + short
        // label, the app's primary surface — and deliberately NO count in its
        // label (the header's counter line is the one place numbers live).
        val shareBtn = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), 0, dp(16), 0)
            isClickable = true; isFocusable = true
            background = Widgets.rippleRect(ctx, color(R.color.accent), radiusDp = 12)
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(BAR_H_DP)).also {
                it.marginStart = dp(10)
            }
            addView(ImageView(ctx).apply {
                setImageDrawable(Widgets.icon(ctx, R.drawable.ic_send, color(R.color.white)))
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(16)).also { it.marginEnd = dp(7) }
            })
            addView(TextView(ctx).apply {
                // ── GOAT-PASS 14 Phase 2 (§5): the Apps sheet's action label is ──
                // the shared Auto Send verb — "Add" stages the checked apps in
                // the Queue for a later manual Send, "Send" dispatches them
                // immediately (stageOrSendItems already branches on that same
                // setting, so the label and the action can never disagree).
                // Same typeface, size, color and padding — only the word is
                // dynamic, read at the sheet's build (the sheet is modal, so
                // the header's Auto Send toggle cannot change while it is
                // open; the next opening re-reads).
                text = com.lumidrop.x.ui.quickShareActionVerb(ctx); textSize = 13.5f
                setTextColor(color(R.color.white))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })
        }
        bottomBar.addView(shareBtn)
        contentFrame.addView(bottomBar)
        root.addView(contentFrame)

        // ── Pass-15 (top gradient, scroll-gated): the visibility writer ─────
        // for the top fade — one canScrollVertically(-1) read per scroll
        // tick, a class-level alpha crossfade only on the crossing itself
        // (the exact discipline setEdgeFade uses elsewhere in this app), so
        // the fade appears the moment tiles slide under the title card and
        // disappears again at the grid's top. The initial state is checked
        // on the grid's first layout via the wrapper listener below, so the
        // fade is also correct after a filter change without any scroll.
        fun updateTopFade() {
            val want = if (grid.canScrollVertically(-1)) View.VISIBLE else View.GONE
            if (topFade.visibility == want) return
            topFade.alpha = if (want == View.VISIBLE) 0f else 1f
            topFade.visibility = want
            topFade.animate().alpha(if (want == View.VISIBLE) 1f else 0f).setDuration(140).start()
        }
        grid.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { updateTopFade() }
        })
        contentFrame.viewTreeObserver.addOnGlobalLayoutListener { updateTopFade() }

        // The grid must be able to scroll its LAST row clear of the floating
        // bar: the bar's real on-screen height (plus breathing room) becomes
        // the grid's bottom padding once the first layout pass has measured
        // it — never a guessed constant. The post simply never runs if the
        // sheet was dismissed before its first layout.
        bottomBar.post {
            val padPx = bottomBar.height + dp(16)
            if (grid.paddingBottom != padPx) grid.setPadding(grid.paddingLeft, grid.paddingTop, grid.paddingRight, padPx)
        }

        val placeholderIcon = Widgets.icon(ctx, R.drawable.ic_package, color(R.color.text_secondary))

        // ── ONE writer for the header's live counter line ──────────────────
        // Declared BEFORE the adapter below: the adapter's onSelectionChanged
        // lambda captures it by name, and Kotlin resolves local functions by
        // declaration point (the same load-bearing ordering MediaPickerSheet
        // documents for its own render()/adapters pair).
        // App sizes are read from sourceDir lazily, only for apps actually
        // checked (a handful at most) rather than upfront for all ~150+
        // installed apps — kept from the previous implementation.
        // ── SELECTION FINAL PASS 1 (§6 — the App picker's O(1) selection ──
        // header): refreshHeaderInfo used to re-scan the WHOLE app list per
        // checked package (O(checked × apps) string comparisons) and stat()
        // every checked APK's sourceDir on the main thread — per selection
        // change, per crossed Hold & Slide tile. Both are gone: a
        // package→ApplicationInfo index (rebuilt when the app list is
        // (re)assigned) makes each checked entry one map read, and the
        // per-APK size is stat'ed ONCE per sourceDir and served from a cache
        // afterwards (still lazy — only checked apps are ever stat'ed at all,
        // preserving the old "never ~150 upfront stats" property).
        var appIndex: Map<String, ApplicationInfo> = emptyMap()
        val apkSizeCache = HashMap<String, Long>()   // sourceDir -> bytes
        fun rebuildAppIndex() { appIndex = apps.associate { it.first.packageName to it.first } }
        fun apkSizeOf(info: ApplicationInfo): Long =
            apkSizeCache.getOrPut(info.sourceDir) { try { File(info.sourceDir).length() } catch (e: Exception) { 0L } }
        // The current filter's shown count — applyFilter owns the figure and
        // refreshHeaderInfo only reads it (the query cannot change without
        // applyFilter running, so the cached count can never go stale).
        var lastShownCount = 0

        fun refreshHeaderInfo() {
            val f = search.text?.toString() ?: ""
            val filtering = f.isNotBlank()
            if (!appsLoaded) {
                headerInfo.text = "Loading apps…"
                return
            }
            if (apps.isEmpty()) {
                headerInfo.text = "No user-installed apps found"
                return
            }
            if (checked.isNotEmpty()) {
                var totalBytes = 0L
                for (pn in checked) totalBytes += appIndex[pn]?.let { apkSizeOf(it) } ?: 0L
                val sizeStr = when {
                    totalBytes >= 1_073_741_824L -> "%.1f GB".format(totalBytes / 1_073_741_824.0)
                    else -> "%.1f MB".format(totalBytes / 1_048_576.0)
                }
                headerInfo.text = if (filtering) {
                    "${checked.size} selected · $sizeStr · $lastShownCount of ${apps.size}"
                } else "${checked.size} selected · $sizeStr"
            } else if (filtering) {
                headerInfo.text = "$lastShownCount of ${apps.size} apps"
            } else {
                headerInfo.text = "${apps.size} apps"
            }
        }

        // ── The grid adapter (unchanged machinery, see ApkGridAdapter) ─────
        // ── GOAT-PASS 7 (§5A — GRID RANGE SELECTION): the anchor lives INSIDE ──
        // the adapter (see ApkGridAdapter.longPressRange) — adapter order is
        // the grid's visual row-by-row, left-to-right order for any column
        // count, so "select the 1st, hold the 11th" selects exactly items
        // 1–11 however many columns the grid currently shows.
        val adapter = ApkGridAdapter(
            scope = sheetScope,
            iconGate = apkIconGate,
            iconCache = apkIconCache,
            placeholder = placeholderIcon,
            isSelected = { pn -> pn in checked },
            onToggle = { pn -> if (pn in checked) checked.remove(pn) else checked.add(pn) },
            onSelectionChanged = { refreshHeaderInfo() },
        )
        grid.adapter = adapter
        // ── Pass-26 (#3.2): hold-and-slide multi-selection ──────────────────────
        // The EXACT gesture the Media Picker already uses (attachDragSelect —
        // one shared implementation, not a re-invented variant): press-and-hold
        // an app tile toggles it and arms the drag; sliding (still holding)
        // across other tiles applies the same captured mode to every tile
        // crossed — select a whole run of apps, or deselect one, in ONE
        // continuous gesture. A plain tap keeps its single-toggle meaning,
        // the grid keeps scrolling normally (the helper only claims the
        // gesture AFTER a real long-press), and nothing here can launch an
        // app or dismiss the sheet — the tiles only ever toggle selection.
        attachDragSelect(
            grid,
            { pos -> adapter.isSelectedAt(pos) },
            { pos, add ->
                if (adapter.applyDragSelection(pos, add)) refreshHeaderInfo()
            },
            // ── GOAT-PASS 11 (§12): the shared helper now applies the ───────────────────
            // initiating row itself (with the direction captured at DOWN)
            // and cancels the tile's own pending long-press at arm time —
            // one owner, no skip, no double-toggle. The tile's long-press
            // keeps its Range-mode anchoring role.
            // ── GOAT-PASS 8 (§4): the drag arms only in Hold & Slide mode; ──
            // in Range mode (the default) the tile's own long-press owns the
            // whole interaction. The app context is captured ONCE at wiring
            // time (it outlives any detach race a late touch could ride).
            slideEnabled = { com.lumidrop.x.settings.SettingsManager.isSlideSelection(appPickerCtx) },
            // ── GOAT-PASS 9 (§4): the App Picker grid joins the edge ──
            // auto-scroll club — Hold & Slide through a long app list reaches
            // the ends without lifting the finger, same curve and same
            // boundary-crossing continuation as every other surface.
            edgeAutoScroll = true)

        fun applyFilter() {
            val f = search.text?.toString() ?: ""
            val filtering = f.isNotBlank()
            val shown = if (!filtering) apps else apps.filter { (info, label) ->
                matchesAppQuery(f, label, info.packageName)
            }
            lastShownCount = shown.size   // SELECTION FINAL PASS 1 (§6): the header reads this instead of re-scanning
            val capped = shown.take(200)
            adapter.submitList(capped)
            statusText.visibility = if (!appsLoaded || shown.isEmpty()) View.VISIBLE else View.GONE
            statusText.text = when {
                !appsLoaded -> "Loading apps…"
                apps.isEmpty() -> "No user-installed apps found"
                shown.isEmpty() -> "No apps match \"${f.trim()}\""
                else -> ""
            }
            refreshHeaderInfo()
        }

        var searchDebounce: Runnable? = null
        val searchHandler = android.os.Handler(android.os.Looper.getMainLooper())
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(txt: android.text.Editable?) {
                searchDebounce?.let { searchHandler.removeCallbacks(it) }
                val r = Runnable { applyFilter() }
                searchDebounce = r
                searchHandler.postDelayed(r, 150)
            }
            override fun beforeTextChanged(cs: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(cs: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        // Instant-open path: a fresh-enough cached list renders fully on
        // the sheet's very first frame — no "Loading apps..." flash at all.
        val cached = apkListCache
        if (cached != null && System.currentTimeMillis() - apkListCachedAtMs < apkListFreshnessMs) {
            apps = cached
            appsLoaded = true
            rebuildAppIndex()   // SELECTION FINAL PASS 1 (§6)
        }
        applyFilter()
        // Posted (not run inline) so the sheet's entrance animation starts
        // first, uncontended — same reasoning as the previous chunked
        // builder, now spreading essentially zero work.
        searchHandler.post { applyFilter() }

        // The (re)enumeration: runs whenever the cache is empty, stale, or
        // absent — i.e. the first open of a session, or 30s+ after the last
        // one. On the SHEET's scope, so dismissing the sheet cancels it.
        val needsRefresh = cached == null || System.currentTimeMillis() - apkListCachedAtMs >= apkListFreshnessMs
        if (needsRefresh) {
            sheetScope.launch(Dispatchers.IO) {
                val installed: List<ApplicationInfo> = try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
                    else @Suppress("DEPRECATION") pm.getInstalledApplications(0)
                } catch (e: Exception) { emptyList() }
                val withLabels = installed.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                    .map { it to pm.getApplicationLabel(it).toString() }
                    .sortedBy { it.second.lowercase() }
                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    apkListCache = withLabels
                    apkListCachedAtMs = System.currentTimeMillis()
                    apps = withLabels
                    appsLoaded = true
                    rebuildAppIndex()   // SELECTION FINAL PASS 1 (§6)
                    applyFilter()
                }
            }
        }

        // The Share action — the sheet's single affirmative control.
        // ── Pass-16 (single-result instant share) ─────────────────────────
        // A tap with nothing checked used to always answer the honest
        // toast — but when the CURRENT filtered list holds exactly ONE
        // app, that toast is a pointless extra step: the person already
        // narrowed the list down to the one app they want, so Share shares
        // THAT app immediately (exactly what selecting it and tapping Share
        // would have done, minus two taps). Anything checked still wins over
        // the shortcut, and a multi-result filter keeps the honest toast.
        shareBtn.setOnClickListener {
            if (checked.isEmpty()) {
                val f = search.text?.toString() ?: ""
                val filtered = if (f.isBlank()) apps
                    else apps.filter { (info, label) -> matchesAppQuery(f, label, info.packageName) }
                if (filtered.size == 1 && appsLoaded) {
                    val (info, label) = filtered[0]
                    val apkFile = File(info.sourceDir)
                    val apkName = label.replace(Regex("[^a-zA-Z0-9_\\-]"), "_") + ".apk"
                    stageOrSendItems(listOf(PreviewItem(apkName, apkFile.absolutePath, null,
                        apkFile.length(), "application/vnd.android.package-archive")))
                    s.dismiss()
                    return@setOnClickListener
                }
                toast("Select at least one app"); return@setOnClickListener
            }
            val apkItems = checked.mapNotNull { pn -> apps.firstOrNull { it.first.packageName == pn }?.let { (info, label) ->
                val apkFile = File(info.sourceDir)
                val apkName = label.replace(Regex("[^a-zA-Z0-9_\\-]"), "_") + ".apk"
                PreviewItem(apkName, apkFile.absolutePath, null, apkFile.length(), "application/vnd.android.package-archive")
            } }
            stageOrSendItems(apkItems)
            s.dismiss()
        }
        refreshHeaderInfo()
        root
        }

        // ── Pass-35 (#1): the App Picker's header (icon chip + title block)
        // is a drag region now — empty chrome drags the sheet exactly like
        // the handle, while the chip, the texts and every control keep
        // their own touches (the hit-test gate in BottomSheet decides per
        // press). Together with the keyboard clamp this is also what keeps
        // the expanded sheet's search results inside the visible frame
        // while the keyboard is up (#2C): the sheet's height follows the
        // shrunken window instead of extending above it.
        headerSlot?.let { sheet.registerDragRegion(it) }

        // Phase 5 (§5.6): the sheet's own lifetime hook — cancels every
        // coroutine this opening launched (pending enumeration, in-flight
        // icon decodes) the moment the sheet goes away. BottomSheet's own
        // dismiss path (backdrop tap, drag-to-dismiss, Cancel button)
        // funnels through dialog.dismiss(), so this one listener covers all
        // of them — including the Pass-26 (#3.1) duplicate-open guard's
        // release.
        sheet.dialog.setOnDismissListener {
            sheetScope.cancel()
            apkPickerShowing = false
        }
    }

    /**
     * Phase 5 (§5.6): the App Selection grid's adapter. ListAdapter +
     * DiffUtil keyed by package name, so a search filter animates in only
     * the tiles that actually changed instead of flashing the whole grid;
     * per-ViewHolder decode jobs are cancelled the instant a tile rebinds
     * or recycles, so a fast scroll can never accumulate a backlog of
     * decodes for tiles that are already gone (the exact bug the Media
     * Selector's Phase 1 pass fixed for ITS grid).
     */
    private inner class ApkGridAdapter(
        private val scope: CoroutineScope,
        private val iconGate: Semaphore,
        private val iconCache: java.util.concurrent.ConcurrentHashMap<String, android.graphics.drawable.Drawable>,
        private val placeholder: android.graphics.drawable.Drawable,
        private val isSelected: (String) -> Boolean,
        private val onToggle: (String) -> Unit,
        private val onSelectionChanged: () -> Unit,
    ) : ListAdapter<Pair<ApplicationInfo, String>, ApkGridAdapter.VH>(object : DiffUtil.ItemCallback<Pair<ApplicationInfo, String>>() {
        override fun areItemsTheSame(oldItem: Pair<ApplicationInfo, String>, newItem: Pair<ApplicationInfo, String>) =
            oldItem.first.packageName == newItem.first.packageName
        override fun areContentsTheSame(oldItem: Pair<ApplicationInfo, String>, newItem: Pair<ApplicationInfo, String>) =
            oldItem.first.packageName == newItem.first.packageName && oldItem.second == newItem.second
    }) {

        // ── GOAT-PASS 7 (§5A — GRID RANGE SELECTION, the anchor model): a ──────
        // long-press with NO valid anchor TOGGLES the pressed tile (the grid's
        // historic long-press feel — a selected tile starts a deselect pass)
        // and makes it the anchor; a long-press WITH a valid anchor selects the
        // whole inclusive positional span between them, additively, and moves
        // the anchor to the pressed tile. The anchor is a package name,
        // re-resolved against the CURRENT filtered list on every press, so a
        // search/filter change simply invalidates a stale anchor (the next
        // press becomes a fresh one) — never a wrong range. Adapter order IS
        // the grid's visual row-by-row, left-to-right order for any column
        // count, because GridLayoutManager fills cells in adapter order.
        private var rangeAnchorPn: String? = null
        fun longPressRange(pos: Int) {
            val targetPn = packageNameAt(pos) ?: return
            // ── GOAT-PASS 8 (§3 — the ghost-anchor guard): an anchor that is ──
            // no longer SELECTED is not an anchor — only a still-checked tile
            // may extend a range, so a deselection path that didn't clear the
            // anchor (a drag REMOVE pass over it) can never conjure a range
            // from a previous selection session's anchor.
            // ── GOAT-PASS 8 (§4 — Hold & Slide mode): the long-press applies ──
            // only the initiating TOGGLE (the armed drag owns the rest of the
            // gesture), never a range.
            val slideMode = com.lumidrop.x.settings.SettingsManager.isSlideSelection(requireContext())
            val anchorValid = rangeAnchorPn != null && rangeAnchorPn?.let { isSelected(it) } == true
            val anchorPos = if (anchorValid) rangeAnchorPn?.let { aid ->
                (0 until itemCount).indexOfFirst { packageNameAt(it) == aid }
            } ?: -1 else -1
            if (anchorPos < 0 || slideMode) {
                onToggle(targetPn)
                if (pos in 0 until itemCount) notifyItemChanged(pos, selPayload)
                onSelectionChanged()
                rangeAnchorPn = targetPn
                return
            }
            val from = minOf(anchorPos, pos)
            val to = maxOf(anchorPos, pos)
            for (i in from..to) {
                packageNameAt(i)?.let { pn ->
                    if (!isSelected(pn)) onToggle(pn)
                }
            }
            rangeAnchorPn = targetPn
            if (to - from + 1 > 0) notifyItemRangeChanged(from, to - from + 1, selPayload)
            onSelectionChanged()
        }

        inner class VH(
            val tile: LinearLayout,
            val iconFrame: android.widget.FrameLayout,
            val iconView: ImageView,
            val checkBadge: ImageView,
            val labelView: TextView,
        ) : RecyclerView.ViewHolder(tile) {
            var decodeJob: Job? = null
        }

        // ── Pass-16 (modern selected-state redesign) ──────────────────────────
        // The old state was a quiet outline ring + an 18dp checkmark badge
        // — readable, but read as "a simple checkbox" next to everything
        // else this app does. The new language is the same one the Send To
        // modal's selected device cards already speak, so the two pickers
        // feel like one system:
        //  • the icon tile fills with an accent-tinted rounded square and a
        //    crisp 1.5dp accent stroke (the "selected card" treatment);
        //  • the app icon itself tucks in — a subtle scale to 0.86 with a
        //    soft overshoot — so the selected tile reads as "held";
        //  • the checkmark becomes a FLOATING accent disc with a white
        //    check and a thin white ring, popping in at the tile's corner
        //    with a small bounce instead of just flipping visible.
        // Unselected tiles keep the quiet transparent rest state; the
        // label still lifts to text_primary when selected. Everything is
        // mutated in place (no drawable churn per bind) and the animations
        // are plain ViewPropertyAnimator runs on tap only — nothing per
        // frame, nothing Android 5 can't do at full frame rate.
        private val selectedTile = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpf(16f)
            setColor((color(R.color.accent) and 0x00FFFFFF) or 0x2E000000)
            setStroke(dpf(1.5f).toInt().coerceAtLeast(2), color(R.color.accent))
        }
        private val unselectedRing = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.RECTANGLE
            cornerRadius = dpf(16f)
            setColor(android.graphics.Color.TRANSPARENT)
        }
        private val badgeDisc = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(color(R.color.accent))
            setStroke(dpf(1.5f).toInt().coerceAtLeast(1), color(R.color.bg_sheet))
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val iconFrame = android.widget.FrameLayout(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56)).also { it.bottomMargin = dp(5) }
            }
            val iconView = ImageView(ctx).apply {
                setImageDrawable(placeholder)
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(56), dp(56))
            }
            iconFrame.addView(iconView)
            val checkBadge = ImageView(ctx).apply {
                setImageDrawable(Widgets.icon(ctx, R.drawable.ic_check, color(R.color.white)))
                // Pass-16: the badge is now a floating accent disc — solid
                // accent circle, crisp white check, hairline sheet-colored
                // ring so it stays legible over ANY app icon color.
                background = badgeDisc
                setPadding(dp(3), dp(3), dp(3), dp(3))
                visibility = View.GONE
                layoutParams = android.widget.FrameLayout.LayoutParams(dp(20), dp(20), Gravity.BOTTOM or Gravity.END).also {
                    it.marginEnd = -dp(3); it.bottomMargin = -dp(3)
                }
            }
            iconFrame.addView(checkBadge)
            val labelView = TextView(ctx).apply {
                textSize = 10.5f; setTextColor(color(R.color.text_secondary))
                gravity = Gravity.CENTER
                maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val tile = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true; isFocusable = true
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_icon_ripple)
                setPadding(dp(4), dp(8), dp(4), dp(8))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                addView(iconFrame); addView(labelView)
            }
            return VH(tile, iconFrame, iconView, checkBadge, labelView)
        }

        // ── Pass-26 (#3.2): hold-and-slide multi-selection support ──────────────
        // The selection-only payload token (the same discipline the Media
        // Picker's grids use): a drag pass over a tile rebinds ONLY its
        // selection chrome — the app icon, its decode job and the label are
        // untouched, so nothing flashes and no decode is re-queued mid-gesture.
        // A shared token instance rather than an object declaration — this is
        // an inner class, and the payload only ever needs identity, not a type.
        private val selPayload = Any()

        override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty()) {
                val pn = currentList.getOrNull(position)?.first?.packageName ?: return
                paintSelection(holder, pn, animated = true)
                return
            }
            super.onBindViewHolder(holder, position, payloads)
        }

        /** The package at [position] as this adapter's CURRENT (filtered)
         *  list holds it — the drag-select helper's way back from a touched
         *  cell to the app it represents. */
        fun packageNameAt(position: Int): String? =
            currentList.getOrNull(position)?.first?.packageName

        /** Whether the tile at [position] is selected right now (the shared
         *  attachDragSelect helper's probe for capturing the pass mode). */
        fun isSelectedAt(position: Int): Boolean =
            packageNameAt(position)?.let { isSelected(it) } ?: false

        /** Applies one drag-pass delta to the tile at [position] — add=true
         *  selects (idempotent), add=false deselects (idempotent). Toggling
         *  through the existing onToggle callback from a KNOWN opposite
         *  state IS the set operation, so no new mutation channel is needed.
         *  Returns true when the state actually flipped, so the caller
         *  refreshes the header's live counter exactly then. */
        fun applyDragSelection(position: Int, add: Boolean): Boolean {
            val pn = packageNameAt(position) ?: return false
            val has = isSelected(pn)
            if (add == has) return false
            onToggle(pn)
            if (position in 0 until itemCount) notifyItemChanged(position, selPayload)
            return true
        }

        // ── Pass-16: one painter for the whole selected state ──
        // tile fill/stroke, icon tuck scale, badge visibility and label
        // lift all move together, from bind, from the tap, and from every
        // drag-pass payload rebind — previously a local closure inside
        // onBindViewHolder, now a member so all three paths share it.
        private fun paintSelection(holder: VH, pn: String, animated: Boolean) {
            val on = isSelected(pn)
            holder.iconFrame.background = if (on) selectedTile else unselectedRing
            holder.labelView.setTextColor(if (on) color(R.color.text_primary) else color(R.color.text_secondary))
            holder.checkBadge.visibility = if (on) View.VISIBLE else View.GONE
            val scale = if (on) 0.86f else 1f
            if (animated) {
                holder.iconView.animate().cancel()
                holder.iconView.animate().scaleX(scale).scaleY(scale)
                    .setDuration(Motion.DURATION_MEDIUM)
                    .setInterpolator(if (on) Motion.OVERSHOOT_SOFT else Motion.DECELERATE)
                    .start()
                if (on) {
                    holder.checkBadge.alpha = 0f
                    holder.checkBadge.scaleX = 0.4f; holder.checkBadge.scaleY = 0.4f
                    holder.checkBadge.animate().alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(Motion.DURATION_MEDIUM)
                        .setInterpolator(Motion.OVERSHOOT_SOFT).start()
                }
            } else {
                holder.iconView.animate().cancel()
                holder.iconView.scaleX = scale; holder.iconView.scaleY = scale
                holder.checkBadge.alpha = 1f
                holder.checkBadge.scaleX = 1f; holder.checkBadge.scaleY = 1f
            }
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val (info, label) = getItem(position)
            val ctx = holder.tile.context
            val pn = info.packageName
            holder.labelView.text = label
            paintSelection(holder, pn, animated = false)
            holder.tile.setOnClickListener {
                // ── GOAT-PASS 9 (§3 — click-path anchoring): a plain click ──
                // that selects an app anchors it for a later range hold —
                // the same contract the Files/Music sheets implement and the
                // media picker now implements too; deselecting the anchored
                // app clears the anchor (no ghost anchors). Previously only
                // the long-press path wrote rangeAnchorPn, so a click-
                // selected item could never start a range.
                val wasSelected = isSelected(pn)
                onToggle(pn)
                if (wasSelected) {
                    if (rangeAnchorPn == pn) rangeAnchorPn = null
                } else {
                    rangeAnchorPn = pn
                }
                // ── Pass-26 (#3.2): paint through the member function now ──
                // (same visuals as before, one implementation shared with the
                // drag-select payload path below).
                paintSelection(holder, pn, animated = true)
                onSelectionChanged()
            }
            // ── GOAT-PASS 7 (§5A): long-press anchors/range-selects through the ──
            // adapter's own range model (adapter order). Consumed so it can
            // never also fire the tap toggle; the drag-select helper arms on
            // top of it with its pass mode captured at DOWN
            // (the gesture itself now applies the initiating row).
            holder.tile.setOnLongClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                longPressRange(pos)
                true
            }
            // Icon: cache first (instant on reopen — the whole point of the
            // fragment-level cache), placeholder + bounded async decode
            // otherwise. The tag guard plus the per-holder job cancellation
            // together guarantee a late decode can never land on a tile that
            // is already showing a different app.
            //
            // Pass-3 (R7 — app picker scroll performance): the decode itself
            // moved OFF the main dispatcher. getApplicationIcon loads the
            // app's resources and constructs its drawable (adaptive-icon
            // inflation included) — real work that used to run on Main
            // inside the semaphore, so a fast scroll through 150+ apps
            // queued up main-thread decodes and visibly janked the grid
            // while the sheet's opening animation stayed smooth. The gate
            // (Semaphore(4)) now bounds CONCURRENT I/O decodes, the decode
            // runs on Dispatchers.IO, and only the (cheap) setImageDrawable
            // hop lands on Main.
            holder.iconView.tag = pn
            holder.decodeJob?.cancel()
            val cachedIcon = iconCache[pn]
            if (cachedIcon != null) {
                holder.iconView.setImageDrawable(cachedIcon)
            } else {
                holder.iconView.setImageDrawable(placeholder)
                val appContext = holder.tile.context.applicationContext
                holder.decodeJob = scope.launch {
                    val decoded = iconGate.withPermit {
                        try {
                            withContext(Dispatchers.IO) {
                                // Pass-5 (R2 — app picker scroll smoothness, the
                                // REAL fix): the decoded launcher icon is now
                                // FLATTENED to one small bitmap sized exactly to
                                // the 56dp tile before it is cached or shown.
                                // getApplicationIcon returns full launcher art
                                // (adaptive icons up to 432x432) that must be
                                // GPU-texture-uploaded and, for
                                // AdaptiveIconDrawable, LAYER-COMPOSITED on
                                // every draw frame — dozens of those under a
                                // fast scroll is the residual jank the pass-3
                                // async-decode fix could never remove by
                                // itself. One downscale here, off the main
                                // dispatcher inside the existing semaphore, and
                                // every tile forever draws a single tiny
                                // BitmapDrawable: ~10-40x less texture memory
                                // per icon, zero per-frame composition work.
                                flattenAppIcon(appContext, appContext.packageManager.getApplicationIcon(info))
                            }
                        } catch (e: Exception) { null }
                        catch (e: OutOfMemoryError) { null }
                    }
                    if (decoded != null) {
                        iconCache[pn] = decoded
                        withContext(Dispatchers.Main) {
                            if (holder.iconView.tag == pn) holder.iconView.setImageDrawable(decoded)
                        }
                    }
                }
            }
        }

        override fun onViewRecycled(holder: VH) {
            holder.decodeJob?.cancel()
            holder.decodeJob = null
        }
    }

    // Pass-5 (R2): flattens any launcher icon (bitmap, vector, or adaptive
    // layered) into ONE small ARGB_8888 bitmap drawn at exactly the picker
    // tile's 56dp size — see the decode call site for why this is the app
    // picker's real scroll-smoothness fix. Runs on the IO dispatcher under
    // the decode semaphore; the result is what the fragment-level icon
    // cache stores, so every later opening reuses the small flat form.
    private fun flattenAppIcon(ctx: Context, icon: Drawable): Drawable {
        val sizePx = ctx.dp(56).coerceAtLeast(56)
        val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        icon.setBounds(0, 0, sizePx, sizePx)
        icon.draw(cv)
        return BitmapDrawable(ctx.resources, bmp)
    }

    // Outgoing staging gate ------------------------------------------------------

    private fun stageOrSendItem(item: PreviewItem) = stageOrSendItems(listOf(item))

    /** ── GOAT-PASS 10 (§9): the external staging entry — the full-screen ──
     *  Files Sheet opened from the Transfers tab (the internal "Open
     *  Received Folder" flow) routes its Add button here through
     *  MainActivity, so picked items land in the SAME queue (and the same
     *  auto-send-or-stage decision) as a pick made from the Send tab's own
     *  Files tile. No-op while the fragment is detached — the bridge already
     *  brought the tab forward, and the next attach picks the queue up. */
    fun stageItemsFromExternal(items: List<PreviewItem>) {
        if (!isAdded || items.isEmpty()) return
        stageOrSendItems(items)
    }

    // GOAT (Phase 1, item 3): the real fix belongs here, not in
    // enqueueUris -- this is the single place that decides how a batch
    // actually goes out. Quick-Share (browser) delivery stays per-item
    // (addToQuickShare's queue is a plain list a browser client polls
    // piecemeal regardless, so there's nothing to batch there), but native
    // peers now get the WHOLE batch in one sendToPeers() call: one
    // session, one manifest, one accept/decline decision -- same shape as
    // a folder or a manual multi-select "Send to" already use, and the
    // same admission-order guarantee runSession's own deterministic-order
    // fix depends on. This used to be just pushItemLive(item), which only
    // ever calls server.addToQuickShare — the browser-facing HTTP pool.
    // That's correct for browser clients (they pick it up on their own),
    // but it never calls NativePushClient.sendToPeers, which is the ONLY
    // thing that actually delivers a file to another LumiDrop X install. A
    // native peer got nothing, silently — no error, because nothing had
    // failed; the code simply never attempted it.
    //
    // GOAT (Phase 1 continuation, item 2): sendOrQueueForPeer (not a bare
    // snapshot()-then-maybe-send) is what makes "no device connected yet"
    // wait internally and auto-send the moment one appears, instead of
    // just silently never sending — same target-gathering
    // NativeDiscovery.snapshot() the manual "Send to" picker already uses
    // under the hood, so auto-send and manual send agree on who counts as
    // an active target.
    private fun stageOrSendItems(items: List<PreviewItem>) {
        if (items.isEmpty()) return
        // Pass-7: Auto-Send is now the plain mirror of Auto-Accept — one
        // boolean, read right here at the moment of selection. ON means the
        // batch leaves IMMEDIATELY for every device connected at that moment
        // (autoSendNow); OFF stages it in the queue card exactly as before.
        // There is no engine, no folder, no per-device ledger anymore — the
        // host's own selection is the one and only trigger.
        if (SettingsManager.isAutoSendEnabled(requireContext())) {
            autoSendOrPickTargets(items)
        } else {
            previewItems.addAll(items)
            // ── Pass-25 (#5): a NEW selection action always brings the ──────────
            // Queued Files view forward, and clears any manual Devices-side
            // choice made against an EARLIER selection (the requirement's
            // "preserve the user's manual view choice until a meaningful state
            // change occurs, such as a new selection action"). Everything
            // funnels through here — picker multi-select, folders, text,
            // clipboard-paste, APKs — so one site covers them all.
            queueManualDevices = false
            updatePreviewCard()
        }
    }

    /** ── Pass-29 Part 1 (#3/#4E): the ONE Auto-Send entry decision. With ──────
     *  MORE THAN ONE eligible receiver connected, the automatic blast goes
     *  through the app's EXISTING Send To modal instead — the same recipient
     *  picker a manual Send uses, reused as-is (never a second selection
     *  surface), so the queued files go exactly to the devices the user
     *  picks and nothing is sent anywhere until the selection is confirmed.
     *  One connected receiver (or none) keeps the classic behavior: a single
     *  target cannot be mis-sent, and an empty room keeps the established
     *  wait-for-a-device hold inside autoSendNow. Both Auto-Send callers —
     *  stageOrSendItems (a fresh pick) and updatePreviewCard's flush (files
     *  already staged when the setting turned on, #4E) — route through here,
     *  so the rule can never differ between them. */
    private fun autoSendOrPickTargets(items: List<PreviewItem>) {
        val webTargets = server?.liveBrowserIps(SettingsManager.isConfirmConn(requireContext())) ?: emptyList()
        val nativeCount = if (isRunning) NativeDiscovery.snapshot().size else 0
        if (webTargets.size + nativeCount > 1) showTargetPickerAndSend(items)
        else autoSendNow(items)
    }

    /** Pass-7 (Auto-Send remake) — the entire "instantly send to all
     *  connected devices" behavior in one place, deliberately routed through
     *  the exact channels a manual send uses so every existing guarantee is
     *  inherited rather than bypassed:
     *
     *  - WEB clients currently connected each become a TARGETED send
     *    (targetIps = live browser IPs): every browser's incoming list gets
     *    the offer instantly, the host's Active tab pre-registers one
     *    Waiting row per client, the item leaves the queue card immediately
     *    (it has per-client rows now), and the receiver's own Auto-Accept
     *    decides whether it lands without a tap. The transfer previewer
     *    still stays closed until they accept (Pass-6 R-A gate).
     *  - NATIVE peers get the whole batch through sendOrQueueForPeer — one
     *    manifest push to every discovered peer, or visibly-waiting Pending
     *    rows that fire the moment a peer appears (its existing contract).
     *
     *  If NO web client is connected the web push is skipped entirely (a
     *  broadcast would linger in the queue card as a nothing-yet item — the
     *  exact "queued section" this setting removes); native keeps its
     *  wait-for-device behavior, so with nothing connected at all the
     *  selection still goes out the moment something connects.
     */
    private fun autoSendNow(items: List<PreviewItem>) {
        val srv = server
        val webTargets = srv?.liveBrowserIps(SettingsManager.isConfirmConn(requireContext())) ?: emptyList()
        if (webTargets.isNotEmpty()) {
            val targets = webTargets.toSet()
            // ── GOAT-PASS 14 (Phase 1, §1.3): ONE Auto-Send operation = ONE ──
            // batch — in ZIP mode every currently-connected browser is offered
            // the same single archive (pushBatchLive's mode check happens once,
            // for THIS batch, so unrelated later operations can never combine).
            pushBatchLive(items, targets)
        }
        val nativeCount = if (isRunning) NativeDiscovery.snapshot().size else 0
        // Pass-8 (§3 — "18 rows for 9 files" ROOT CAUSE): the native leg used
        // to run UNCONDITIONALLY, so with a browser connected and no native
        // peer around, sendOrQueueForPeer parked a SECOND set of
        // "Waiting for device" SEND rows for the very same files the browser
        // leg had already delivered — 9 real transfers plus 9 duplicate
        // waiting ghosts on the Active tab. The native path now only runs
        // when it can actually contribute: a native peer is connected (real
        // mixed-target send), or nothing else took the batch (the documented
        // "sending when one joins" hold). When browser targets exist AND no
        // native peer is connected, the batch has been fully dispatched to
        // every eligible receiver — no phantom rows.
        if (nativeCount > 0 || webTargets.isEmpty()) {
            // ── GOAT-PASS 14 (Phase 1, §1.3): the mode decision is THIS batch's ──
            // (a parked ZIP batch drains as its own one-archive send later).
            NativePushClient.sendOrQueueForPeer(requireContext(), items, zipMode = zipSendModeOn())
        }
        val webCount = webTargets.size
        val total = webCount + nativeCount
        toast(when {
            total == 1 -> "Quick-sending to 1 device…"
            total > 1 -> "Quick-sending to $total devices…"
            else -> "No device connected — sending when one joins"
        })
    }

    private fun pushItemLive(item: PreviewItem, targetIps: Set<String>? = null) {
        // GOAT v2 (item 2 — album send): used to gate on item.uri != null
        // specifically, which silently mis-routed any folder identified by
        // a plain path instead (a selected media album never has a SAF
        // uri at all) into the plain-file branch below as if the folder's
        // own path were a single file to serve. Any folder goes through
        // addFolderToQuickShare now, regardless of which representation it
        // carries — see that function's own doc comment for how it
        // resolves either one.
        if (item.textContent != null) server?.addTextToQuickShare(item.textContent, targetIps)
        // ── Pass-33 (#1): the album manifest rides along — the live (web) half ──
        // of the queue receives the same fixed child list the staged half
        // carries, so the browser ZIP of an album folder contains exactly
        // its shown media (see LumiDropServer.addFolderToQuickShare).
        else if (item.isFolder) server?.addFolderToQuickShare(item.path, item.uri, item.name, targetIps, item.childManifest,
            // ── Pass-36 Part 2 (#4): the staged hidden eligibility rides the ──
            // live item — the browser ZIP walk honors the same rule.
            item.includeHiddenChildren,
            // ── GOAT-PASS 14 (Phase 1, §2.1): the staged child REMOVALS ride ──
            // the live item too — previously dropped here, so a folder the
            // sender pruned in the Queue still arrived with every original
            // child in the browser's ZIP (the "removed 6 of 30, receiver
            // got all 30" root cause; the native leg is fixed the same way
            // in NativePushClient.buildManifest).
            item.excludedChildPaths)
        else server?.addToQuickShare(item.path, item.uri, item.name, item.size, item.mime, targetIps)
    }

    /** ── GOAT-PASS 14 (Phase 1, §1.2): ZIP send mode, read at the exact ────────
     *  moment of a send operation (the Phase-2 Connected-Devices-card toggle
     *  writes this same setting; OFF keeps the classic per-item behavior
     *  byte-for-byte). */
    private fun zipSendModeOn(): Boolean =
        com.lumidrop.x.settings.SettingsManager.isZipSendEnabled(requireContext())

    /** ── GOAT-PASS 14 (Phase 1, §1.3): the browser leg of a batch dispatch. ────
     *  ZIP mode offers ONE archive Quick-Share item built from the caller's
     *  captured snapshot; the classic path pushes each item exactly as
     *  before. [items] is always the caller's own immutable batch list. */
    private fun pushBatchLive(items: List<PreviewItem>, targetIps: Set<String>?) {
        if (zipSendModeOn()) server?.addZipBatchToQuickShare(items, targetIps)
        else for (item in items) pushItemLive(item, targetIps)
    }

    /** ── Pass-29 Part 1 (#3): [items] is the batch being sent. The manual ──────
     *  callers pass the staged queue with [fromStaging] = true — exactly the
     *  captured entries leave the staging list when the send lands, the
     *  behavior the manual Send always had. The Auto-Send multi-device path
     *  passes the freshly picked batch, which was NEVER staged — so it must
     *  not touch the staging list. */
    private fun sendQueuedShare(items: List<PreviewItem>, targetIps: Set<String>? = null, fromStaging: Boolean = false) {
        if (items.isEmpty()) return
        // ── GOAT-PASS 14 (Phase 1, §1.3): the batch is dispatched as ONE unit —
        // in ZIP mode that means one archive offer per targeted browser; the
        // classic path pushes each item exactly as before. Either way the
        // CAPTURED batch (never a live queue view) is what leaves staging.
        pushBatchLive(items, targetIps)
        if (fromStaging) { previewItems.removeAll(items.toHashSet()); updatePreviewCard() }
    }

    /** ── Pass-29 Part 1 (#3): same parameterization as sendQueuedShare. */
    private fun sendMixedShare(items: List<PreviewItem>, browserTargetIps: Set<String>, nativePeers: List<NativeDiscovery.NativePeer>, fromStaging: Boolean = false) {
        if (items.isEmpty()) return
        // ── GOAT-PASS 14 (Phase 1, §1.3): both legs of a mixed-target send see ──
        // the SAME mode decision over the SAME captured batch — the browsers
        // get one archive offer, the native peers get one archive push, and
        // each recipient keeps its own independent transfer identity.
        if (browserTargetIps.isNotEmpty()) pushBatchLive(items, browserTargetIps)
        if (nativePeers.isNotEmpty()) {
            toast(if (nativePeers.size == 1) "Sending to ${nativePeers[0].displayName}..." else "Sending to ${nativePeers.size} devices...")
            NativePushClient.sendToPeers(requireContext(), nativePeers, items, zipMode = zipSendModeOn())
        }
        if (fromStaging) { previewItems.removeAll(items.toHashSet()); updatePreviewCard() }
    }

    private fun showTargetPickerAndSend() {
        // ── Pass-31 (#2): never send a batch that is still being prepared. ─────
        // A pick's metadata resolution is short now (one merged query per
        // file, in parallel), but a fast user can still reach Send inside
        // that window — and the Send To modal must not open against a queue
        // that is about to grow by a whole batch. The tap is remembered and
        // runs the instant the commit lands (see enqueueUris), against the
        // COMPLETE queue.
        if (stagingBatchesInFlight > 0) {
            sendWantedWhileStaging = true
            toast("Preparing the selected files…")
            return
        }
        showTargetPickerAndSend(previewItems.toList(), fromStaging = true)
    }

    // ── Pass-29 Part 1 (#3): the Send To modal takes the batch it will send. ──
    // The manual Send button passes the staged queue (a copy — a concurrent
    // stage during the modal's life must not mutate the list being sent, and
    // only the CAPTURED entries leave the staging list when the send lands);
    // Auto-Send's multi-device path (autoSendOrPickTargets) passes the fresh,
    // never-staged batch the same way. Everything below is the existing modal,
    // unchanged: the live device list, the whole-row selection cards (every
    // connected device starts checked), the selected-count title, and the
    // live 1s poller that keeps both current while it is open.
    private fun showTargetPickerAndSend(items: List<PreviewItem>, fromStaging: Boolean = false) {
        val srv = server
        // Phase 2 of 10: same authoritative, approval-filtered, IP-sorted
        // list the Connected Devices card shows — one definition of "which
        // browser sessions are connected", not a second 30s re-derivation
        // that could disagree with the card in the instant between the two
        // reads (and could offer an unaccepted-but-recent device as a
        // target while the card correctly hid it).
        val browserActive = srv?.liveBrowserIps(SettingsManager.isConfirmConn(requireContext())) ?: emptyList()
        val nativeActive = NativeDiscovery.snapshot()
        val totalTargets = browserActive.size + nativeActive.size
        if (totalTargets == 0) { toast("No devices currently connected"); return }

        if (totalTargets == 1) {
            // Pass-3 (R14): a deliberate send to the one connected browser is
            // an EXPLICIT targeted send now, not a broadcast — the items
            // pre-register waiting rows in the Active tab for exactly that
            // client and leave the queue card immediately (the
            // native-to-native lifecycle), instead of sitting in the queue
            // as an everyone-share until the client happens to accept.
            if (browserActive.size == 1) sendQueuedShare(items, browserActive.toSet(), fromStaging) else sendMixedShare(items, emptySet(), nativeActive, fromStaging)
            return
        }

        // ── Pass-13 (Send To modal: fixed dimensions + LIVE device list) ────
        // Two reported defects, both structural in the pass-12 shape:
        //   1. DIMENSIONS DRIFTED — the LdxDialog card is WRAP_CONTENT, so a
        //      longer device name (or a different device count) changed the
        //      modal's width mid-session. The body now carries an EXACT dp
        //      width; rows ellipsize their names, and the card is pixel-
        //      identical no matter what connects, how it's named, or what's
        //      checked.
        //   2. THE LIST WAS A SNAPSHOT — devices appearing/disappearing while
        //      the modal was open never showed. The modal now re-reads the
        //      same authoritative sources on a 1s tick for as long as it is
        //      open: a newly connected device appears immediately and is
        //      selectable; a vanished one disappears; selections are keyed
        //      by stable identity (type+IP) so they survive rebuilds; the
        //      title's count follows the live list.
        val ctx = requireContext()

        // ── Pass-16 (live "Send to X Devices" selection count) ───────────
        // The title now reports the SELECTED count, not the total device
        // count — and it refreshes THE INSTANT a card is tapped (the
        // previous version recomputed it only on the 1s device poll, so it
        // never followed the user's own taps at all). Declared as a var
        // lambda HERE (before the row builder, whose click listener must
        // already be able to call it) and assigned its real body once the
        // dialog exists below — the one assignment point serves all three
        // call sites: row taps, the poller tick, and the dialog's own
        // construction (a selected device that VANISHED stops counting,
        // a freshly-arrived device starts unchecked, so the number can
        // never drift from what the Send action will actually send).
        var refreshSendToTitle: () -> Unit = {}

        // Row identity: stable across rebuilds, unique across types.
        data class DeviceRowKey(val isNative: Boolean, val ip: String) {
            val id: String get() = (if (isNative) "n:" else "b:") + ip
        }
        // Live snapshot of every selectable target, in display order
        // (browsers first, then native — same order as before).
        data class TargetRow(val key: DeviceRowKey, val displayName: String, val address: String)

        fun snapshotRows(): List<TargetRow> {
            val rows = mutableListOf<TargetRow>()
            val s = server
            if (s != null) for (ip in s.liveBrowserIps(SettingsManager.isConfirmConn(ctx))) {
                val customName = s.deviceNames[ip]
                val ua = s.connectedUAs[ip] ?: ""
                val autoLabel = if (ua.isNotEmpty()) s.parseDeviceLabel(ua) else null
                rows.add(TargetRow(DeviceRowKey(false, ip), customName ?: autoLabel ?: ip, ip))
            }
            for (peer in NativeDiscovery.snapshot()) {
                rows.add(TargetRow(DeviceRowKey(true, peer.ip), peer.displayName, peer.ip))
            }
            return rows
        }

        // Selection state, keyed by row identity. Devices present at open
        // start CHECKED (the previous behavior); devices arriving later start
        // UNCHECKED — they appear, and the person decides.
        val selected = HashMap<String, Boolean>()
        for (r in snapshotRows()) selected[r.key.id] = true

        val listContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            // THE fixed dimension — Pass-15 re-derived it against the REAL
            // available width. The pass-13 formula (screen - 72dp) ignored
            // LdxDialog's own chrome: the backdrop adds 28dp per side and the
            // card 20dp per side, so the card's true content width is
            // (screen - 96dp). On a 360dp phone the old body demanded 288dp
            // inside a 264dp content area — every device card was cropped on
            // its right edge, the reported "cards are cropped on the right
            // side". Now: min(300dp, screen-96dp) — a professional compact
            // width on tablets, and always exactly inside the card on phones.
            layoutParams = LinearLayout.LayoutParams(
                minOf(dp(300), resources.displayMetrics.widthPixels - dp(96)).coerceAtLeast(dp(200)),
                ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        // Pass-14 (Send To selection redesign): the pass-13 rows carried a
        // small checkmark badge whose 22dp accent chip read as a cramped,
        // partially-cropped checkbox, and a badge is a second-class
        // selection affordance anyway. Selection is now expressed by the
        // WHOLE row container: a selected device card lights up with an
        // accent-tinted fill + accent stroke and its icon chip inverts to a
        // solid accent tile; an unselected one sits quiet on the card
        // surface with a hairline border. No checkbox anywhere — the entire
        // container IS the toggle, which also makes the tap target bigger
        // and the state impossible to miss.
        fun addDeviceRow(row: TargetRow) {
            val isNative = row.key.isNative
            // ── Pass-15 (compact professional rows): the pass-14 row was a ──
            // 34dp icon chip on 11dp vertical padding inside a 316dp-wide
            // body — oversized next to the rest of the app's modal language
            // ("the cards are currently too large, which doesn't look
            // professional"). The row keeps its whole-container-selection
            // design but tightens to the same scale the app's other sheets
            // use: a 30dp chip, 10/9dp padding, and the narrower body above
            // does the rest.
            val sel = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isClickable = true; isFocusable = true
                setPadding(dp(10), dp(9), dp(10), dp(9))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.bottomMargin = dp(6) }
            }
            // Leading icon chip: rounded square carrying the type glyph; its
            // fill/tint follow the selection state (see paint()).
            // ── Pass-15 (globe icon everywhere): browser-connected devices ──
            // render the SAME globe glyph the Connected Devices card uses —
            // the monitor icon is retired from every device-identity surface.
            val chipBg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpf(9f)
            }
            val chipIcon = ImageView(ctx).apply {
                setPadding(dp(6), dp(6), dp(6), dp(6))
            }
            sel.addView(android.widget.FrameLayout(ctx).apply {
                background = chipBg
                addView(chipIcon)
                layoutParams = LinearLayout.LayoutParams(dp(30), dp(30)).also { it.marginEnd = dp(10) }
            })
            // Name + address — ellipsized against the FIXED row width, so a
            // 20-character name and a 5-character one render identically.
            sel.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                addView(TextView(ctx).apply {
                    text = row.displayName; textSize = 13.5f
                    setTextColor(color(R.color.text_primary))
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                })
                addView(TextView(ctx).apply {
                    text = row.address; textSize = 10.5f
                    setTextColor(color(R.color.text_muted))
                    maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
                })
            })
            // Type tag: Browser / This app.
            sel.addView(TextView(ctx).apply {
                text = if (isNative) "This app" else "Browser"
                textSize = 9.5f
                setTextColor(color(R.color.accent_light))
                background = ContextCompat.getDrawable(ctx, R.drawable.bg_btn_ghost)
                setPadding(dp(7), dp(2), dp(7), dp(2))
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .also { it.marginStart = dp(8) }
            })
            // The one painter for both selection states — row container,
            // chip fill, and glyph tint always move together.
            val paint = {
                val on = selected[row.key.id] == true
                val rowBg = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dpf(14f)
                    if (on) {
                        // Accent-tinted fill (≈18% accent over the card) with a
                        // crisp 1.4dp accent stroke — the "selected card" look
                        // (same stroke weight as the focused input border).
                        setColor((color(R.color.accent) and 0x00FFFFFF) or 0x2E000000)
                        setStroke(dpf(1.4f).toInt(), color(R.color.accent))
                    } else {
                        setColor(color(R.color.bg_secondary))
                        setStroke(Math.max(1, dp(1)), color(R.color.border_subtle))
                    }
                }
                sel.background = android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf((color(R.color.accent) and 0x00FFFFFF) or 0x33000000),
                    rowBg, rowBg)
                if (on) {
                    chipBg.setColor(color(R.color.accent))
                    chipIcon.setImageDrawable(Widgets.icon(ctx,
                        if (isNative) R.drawable.ic_phone else R.drawable.ic_globe,
                        color(R.color.white)))
                } else {
                    chipBg.setColor((color(R.color.accent) and 0x00FFFFFF) or 0x20000000)
                    chipIcon.setImageDrawable(Widgets.icon(ctx,
                        if (isNative) R.drawable.ic_phone else R.drawable.ic_globe,
                        color(R.color.accent_light)))
                }
            }
            paint()
            sel.setOnClickListener {
                selected[row.key.id] = !(selected[row.key.id] == true)
                paint()
                // Pass-16: the title's count is a live function of the
                // selection — refresh it in the same tap that changed it.
                refreshSendToTitle()
            }
            listContainer.addView(sel)
        }

        // ── Pass-15 (proper scrolling with many devices): the ScrollView ──────
        // now carries explicit layout params from birth AND the height cap is
        // applied AFTER the dialog attaches it (LdxDialog wraps the body with
        // its own LayoutParams, which used to REPLACE the pre-show params —
        // the cap's write landed on orphaned params and the modal grew
        // unbounded, pushing the footer actions off-screen instead of
        // scrolling). capScrollHeight also measures against the body's REAL
        // width now (see its own change), so the wrap-vs-cap decision is
        // exact. A re-cap rides every live-list rebuild below.
        val listScroll = android.widget.ScrollView(ctx).apply {
            isFillViewport = false
            addView(listContainer)
        }

        // ── Pass-16: pre-declared so the Send action lambda (inside the
        // dialog's own initializer) can dismiss the dialog — Kotlin forbids
        // capturing a local val inside its own initializer; a var holds the
        // reference by the time any action ever runs.
        var dialog: android.app.Dialog? = null
        dialog = showLdxDialog(
            context = ctx,
            title = "Send to",
            iconRes = R.drawable.ic_send,
            bodyView = listScroll,
            actions = listOf(
                LdxDialogAction("Cancel", LdxButtonStyle.GHOST),
                // Pass-16: Send no longer closes the modal when nothing is
                // selected — dismisses=false keeps the sheet up and the
                // prompt toast asks for at least one device. A REAL send
                // (≥ 1 selection) dismisses exactly as before.
                LdxDialogAction("Send", LdxButtonStyle.PRIMARY, dismisses = false) {
                    // Resolve the CURRENT live device lists (a device may have
                    // arrived after the modal opened — sending to it must
                    // work; one that vanished is skipped).
                    val s = server
                    val browserNow = s?.liveBrowserIps(SettingsManager.isConfirmConn(ctx)) ?: emptyList()
                    val nativeNow = if (isRunning) NativeDiscovery.snapshot() else emptyList()
                    val selectedBrowser = browserNow.filter { selected["b:$it"] == true }
                    val selectedNative = nativeNow.filter { selected["n:${it.ip}"] == true }
                    if (selectedBrowser.isEmpty() && selectedNative.isEmpty()) {
                        toast("Select at least one device")
                    } else {
                        sendMixedShare(items, selectedBrowser.toSet(), selectedNative, fromStaging)
                        dialog?.dismiss()
                    }
                },
            ),
        )
        // ── Pass-16: the real body of the live title writer, assigned now
        // that the dialog exists. Counting happens against a FRESH snapshot so
        // devices that vanished since the last tick never inflate the number.
        refreshSendToTitle = {
            (dialog?.window?.decorView?.findViewWithTag<TextView>("ldx_title"))?.let { tv ->
                val n = snapshotRows().count { selected[it.key.id] == true }
                val t = if (n == 1) "Send to 1 device" else "Send to $n devices"
                if (tv.text.toString() != t) tv.text = t
            }
        }
        refreshSendToTitle()
        // Pass-15: attach-time cap — the ScrollView is now inside the dialog's
        // card with real LayoutParams, so this write sticks and the list
        // SCROLLS at its 48%-of-screen cap no matter how many devices are
        // connected, while short lists still wrap naturally.
        capScrollHeight(listScroll, listContainer, measureWidthPx = minOf(dp(300), resources.displayMetrics.widthPixels - dp(96)))

        // The live updater: re-reads the authoritative sources every second
        // while the modal is open. Rebuilds the rows ONLY when the device set
        // or a label actually changed (a signature over identity+name+
        // address) so selection taps and scroll positions are never disturbed
        // by churn; the title's count follows the live list on every tick.
        val pollHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var lastSignature = ""
        val poller = object : Runnable {
            override fun run() {
                val d = dialog ?: return   // belt-and-braces; the dismiss listener stops the tick
                if (!d.isShowing) return
                val rows = snapshotRows()
                // Pass-16: the title tracks the SELECTED count (a vanished
                // device stops counting the moment it disappears — its
                // selection key simply never matches a live row again).
                refreshSendToTitle()
                val sig = rows.joinToString("|") { it.key.id + "=" + it.displayName + "/" + it.address }
                if (sig != lastSignature) {
                    lastSignature = sig
                    // Preserve the scroll position across the rebuild, and
                    // cap the scroll height again for the new row count.
                    val scrollY = listScroll.scrollY
                    listContainer.removeAllViews()
                    for (r in rows) addDeviceRow(r)
                    listContainer.requestLayout()
                    capScrollHeight(listScroll, listContainer,
                        measureWidthPx = minOf(dp(300), resources.displayMetrics.widthPixels - dp(96)))
                    listScroll.post { listScroll.scrollTo(0, scrollY.coerceAtMost((listContainer.height - listScroll.height).coerceAtLeast(0))) }
                }
                pollHandler.postDelayed(this, 1000)
            }
        }
        dialog?.setOnDismissListener { pollHandler.removeCallbacksAndMessages(null) }
        for (r in snapshotRows()) addDeviceRow(r)
        pollHandler.postDelayed(poller, 1000)
    }

    // GOAT v2 (item 8): this one helper is duplicated rather than moved
    // outright — everything else under the old "Incoming transfer
    // approval" section below went to MainActivity.kt in full, but this
    // particular function ALSO backs showApkPickerSheet's own scrolling
    // app list (see its call site further up), a completely separate
    // feature that has nothing to do with the incoming sheet. Kept here
    // for that caller, and duplicated (not shared) into MainActivity.kt
    // for the incoming-sheet code that now lives there — same reasoning
    // already used for e.g. fmtBytes across multiple files in this app.
    private fun capScrollHeight(scroll: android.widget.ScrollView, content: View,
                               // Pass-15 (Send To accuracy): the wrap-vs-cap decision measures
                               // the content at the width it will ACTUALLY render at. The old
                               // unconditional full-screen measure overstated the available
                               // line width for the Send To modal's fixed 300dp body, so
                               // wrapping rows measured artificially short and a long device
                               // list under-capped (the modal grew past its cap).
                               measureWidthPx: Int = resources.displayMetrics.widthPixels) {
        content.measure(
            View.MeasureSpec.makeMeasureSpec(measureWidthPx, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.UNSPECIFIED,
        )
        val maxH = (resources.displayMetrics.heightPixels * 0.48f).toInt()
        val targetH = if (content.measuredHeight > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT
        val lp = scroll.layoutParams
        if (lp != null && lp.height != targetH) {
            lp.height = targetH
            scroll.layoutParams = lp
        }
    }

    // GOAT v2 (item 8 — Universal Receiving Modal): the incoming-transfer
    // approval sheet (incomingSheet and everything that used to be here —
    // state, updateIncomingRequests, renderIncomingRows, capScrollHeight,
    // buildIncomingRow/buildPushItemRows/buildIncomingPreparingRow/
    // buildIncomingFolderRow) moved to MainActivity.kt in full (capScrollHeight
    // duplicated rather than removed outright — see its own copy just
    // above for why). It used to live here on this Fragment's own poll
    // loop, which deliberately stops while this Fragment is hidden (see
    // onBecameHidden/startPolling above) — correct for everything else on
    // this screen, but wrong for this one piece specifically: a request
    // arriving while the person is on Transfers or Settings would go
    // completely undetected until they switched back here. See
    // MainActivity.kt's own class doc comment for the full reasoning;
    // every row-building function carried over unchanged, only the class
    // it lives on (and therefore how it stays alive regardless of tab)
    // changed.

    // Shared Connected Devices / Queued Files card ------------------------------


    // ── Pass-25 (#5): the dynamic Connected Devices / Queued Files card ─────
    // The shared card's view state. Exactly one writer decides what shows:
    // updatePreviewCard (the queue's presence + the manual-override rule) and
    // the two switch buttons. `queueManualDevices` is the user's deliberate
    // "show me Devices even though files are queued" choice: it survives every
    // poll tick (updatePreviewCard refuses to flip the card back while it is
    // set and the selection remains) and resets ONLY on a meaningful state
    // change — a NEW selection action (stageOrSendItems), the queue emptying
    // (Clear / last item removed / all delivered / Auto-Send flush), or the
    // user switching back to Queued Files themselves.
    private var sharedCardQueueVisible = false
    private var queueManualDevices = false
    private var queueHasContentNow = false

    /** Applies the shared card's view. Idempotent; flips at most one child's
     *  visibility per call; the incoming content gets a 110ms fade-in ONLY
     *  (the outgoing view is GONE immediately), so the card's frame never
     *  hosts both views and no layout jump is possible. */
    private fun setSharedCardView(showQueue: Boolean) {
        if (_binding == null) return
        if (sharedCardQueueVisible == showQueue) { updateSwitchIconVisibility(); return }
        sharedCardQueueVisible = showQueue
        val incoming = if (showQueue) binding.queueView else binding.devicesView
        val outgoing = if (showQueue) binding.devicesView else binding.queueView
        outgoing.animate().cancel()
        outgoing.visibility = View.GONE
        outgoing.alpha = 1f   // a later re-show must never inherit a faded-out alpha
        incoming.visibility = View.VISIBLE
        incoming.animate().cancel()
        incoming.alpha = 0f
        incoming.animate().alpha(1f).setDuration(110).start()
        // ── Pass-30 (#2): the pill rides the queue face's visibility explicitly ──
        // now that it is anchored in its own FrameLayout slot rather than
        // inheriting the face's GONE from a direct-child position. Same rule
        // as every element of the face: present exactly while the Queued
        // Files view is showing, so it can never render over the Connected
        // Devices content in the other face.
        binding.queuePillAnchor.visibility = if (showQueue) View.VISIBLE else View.GONE
        // The two lists' fades + scrollbar recompute once the new view has
        // laid out (a switch can change either list's measurable height).
        binding.queueView.post {
            if (_binding == null) return@post
            updateQueueEdgeFades()
            updateDeviceEdgeFades()
        }
        updateSwitchIconVisibility()
    }

    /** The devices-header switch glyph is visible exactly while the DEVICES
     *  view is showing AND a queued selection exists — with an empty queue
     *  there is no second view and the header is exactly as it always was. */
    private fun updateSwitchIconVisibility() {
        if (_binding == null) return
        binding.btnSwitchCardViewDevices.visibility =
            if (!sharedCardQueueVisible && queueHasContentNow) View.VISIBLE else View.GONE
    }

    private fun updatePreviewCard() {
        if (_binding == null) return
        val staged = previewItems
        val autoSend = SettingsManager.isAutoSendEnabled(requireContext())
        // Pass-7 (Auto-Send remake): while Auto-Send is on there IS no queue
        // view — selected items leave the instant they're picked
        // (stageOrSendItems → autoSendNow), anything staged before the toggle
        // flipped follows on the very next refresh, and the shared card shows
        // its Connected Devices face. Progress lives where it always did once
        // a send is real: the Transfers tab's per-client Waiting/Active rows —
        // never a staged list waiting for a "Send" tap that doesn't exist in
        // this mode.
        if (autoSend) {
            if (staged.isNotEmpty()) {
                val flush = staged.toList()
                previewItems.clear()
                // ── Pass-29 Part 1 (#4E): enabling Auto Send (the setting flip ──
                // itself, the new quick icon, or anything else that lands here
                // with a staged queue) engages the SAME entry decision a fresh
                // pick uses — including the Send To recipient selection when
                // multiple devices are connected (#3). The files leave the
                // staging list exactly as they always did on this path.
                autoSendOrPickTargets(flush)
            }
            // ── Pass-25 (#5): Auto-Send means no queue content exists at all ──
            // — the shared card reads exactly as the old card's GONE did:
            // Connected Devices, with no lingering manual choice.
            queueHasContentNow = false
            queueManualDevices = false
            setSharedCardView(false)
            return
        }
        // Pass-3 (R14 — queue and Active tab never duplicate): a live queue
        // item whose every targeted receiver already has a transfer row
        // (pre-registered the moment it was sent — see LumiDropServer's
        // preRegisterQuickShareSends) has MOVED to the Active tab, where it
        // waits for the receiver's response; it must not also render here.
        // Broadcast shares (targetIps == null) keep the classic queue-card
        // behavior — they only become per-client transfers on accept.
        val liveItems = (server?.quickShareQueue?.toList() ?: emptyList()).filter { item ->
            val targets = item.targetIps
            targets == null || targets.isEmpty() ||
                targets.any { ip -> !TransferManager.hasLiveEntry(item.name, ip) }
        }
        val hasStagedUnsent = !autoSend && staged.isNotEmpty()
        val hasLive = liveItems.isNotEmpty()
        // Post-v1.0 (§previewer-instant-dismiss): a queue whose ONLY content
        // is fully-delivered live items (every item's expected receivers
        // have all consumed it — the same "All received" state the rows
        // render) is a COMPLETED batch: the previewer hides immediately
        // instead of lingering as a card of dead "All received" rows until
        // the server's own queue sweep eventually drops them. Staged items
        // or any undelivered live item keep the card exactly as before.
        val cutoff = System.currentTimeMillis() - 30_000L
        val allDelivered = !hasStagedUnsent && liveItems.isNotEmpty() && liveItems.all { item ->
            val consumed = item.consumedByIps.size
            // EXACTLY the row-level expected-set logic below (targetIps when
            // the item carries its own targets, else recently-connected IPs
            // with the same 30s freshness cutoff) — the card's verdict and
            // the row's own "All received" label can never disagree.
            val expected = (item.targetIps ?: server?.connectedIPs?.filter { it.value > cutoff }?.keys?.toSet() ?: emptySet<String>()).size
            expected > 0 && consumed >= expected
        }
        // ── Pass-25 (#5): the queue's presence now drives the SHARED CARD's ────
        // VIEW — the queue is no longer a separate card appearing between the
        // Server card and Quick Share. The rules, exactly as specified:
        //   • no queue content → Connected Devices view, manual choice reset;
        //   • content + no manual choice → Queued Files view (selection
        //     auto-shows the queue — the natural default);
        //   • content + the user deliberately switched to Devices → stays on
        //     Devices (no fighting the user while the selection remains);
        //     a NEW selection action or the queue emptying resets the choice.
        // Idempotent per tick: setSharedCardView touches nothing when the
        // target view is already showing, so the 500ms poll passes are free.
        val queueHasContent = (hasStagedUnsent || hasLive) && !allDelivered
        queueHasContentNow = queueHasContent
        if (!queueHasContent) queueManualDevices = false
        setSharedCardView(queueHasContent && !queueManualDevices)

        // ── Pass-29 Part 1 (#6/#7): the expensive half of this pass — folder ────
        // stats, the counter pill, the rows list and the adapter submit — runs
        // ONLY when something that can change its output actually changed. The
        // fingerprint covers every input: the staged set (size + every
        // folder's exclusion set), the live set's membership and its consumed
        // counters, the connected-device count (a Live row's "n/m received"
        // denominators derive from it), allDelivered, the folder-walk epoch
        // (bumped each time a walk lands, so the pill always re-settles even
        // though the walk's own inputs didn't move — see requestFolderStats),
        // and — while any live item exists — a 30s time bucket (the
        // expected-set freshness window above slides with the clock). String
        // hashCode is cached on the immutable name instances, so the whole
        // check is a few thousand integer adds even for a 1,858-item queue:
        // the steady state costs ZERO stats/rows/adapter work, where the old
        // pass rebuilt the signature string and the whole rows list twice a
        // second. The gating NEVER hides a real change — every mutation site
        // (stage, remove, exclude, send, toggle) either changes one of these
        // inputs or calls updatePreviewCard after bumping the epoch.
        var fp = 31L * staged.size + folderStatsEpoch
        for (item in staged) if (item.isFolder) fp = fp * 31 + item.excludedChildPaths.hashCode()
        for (item in liveItems) {
            fp = fp * 31 + item.name.hashCode()
            fp = fp * 31 + item.consumedByIps.size
        }
        fp = fp * 31 + (server?.connectedIPs?.size ?: 0)
        fp = fp * 31 + (if (allDelivered) 1 else 0)
        if (liveItems.isNotEmpty()) fp = fp * 31 + (System.currentTimeMillis() / 30_000L)
        if (fp == lastQueueFingerprint) return
        lastQueueFingerprint = fp

        // ── Pass-27 (#3.1/#3.2): the counter counts FILES, not queue entries ────
        // A selected folder used to count as "1 file" with its size field
        // (−1 at staging, 0 once live) — so a 40-photo folder showed "1 file ·
        // 0 B". The pill now aggregates the ACTUAL content: every staged and
        // live item contributes its real files (a folder contributes its
        // walked tree, recursively — see the folder-stats engine below this
        // function), every file contributes exactly one entry, an empty
        // folder contributes nothing at all, and one shared identity set
        // deduplicates across entries so a file that is reachable twice (a
        // folder AND an individually-picked copy of a file inside it) is
        // counted — and sized — exactly once. The walk is cached and
        // asynchronous (folderStatsCache).
        //
        // ── Pass-29 Part 1 (#7 — the pill never shows a PARTIAL total) ───────────
        // A folder whose walk is still in flight contributes nothing to this
        // pass (the storage I/O must never block the tick). The pill used to
        // render that partial figure for the walk's duration — read on the
        // device as "adding a folder replaced the counter with just the
        // folder's own items". It now holds the LAST COMPLETE figure until
        // the walk lands (the landing bumps folderStatsEpoch → this whole
        // pass re-runs → the pill settles to the true whole-queue total),
        // and only a queue that never had a settled figure renders the
        // partial number (still better than a blank pill on the first frame).
        val stats = computeQueuedFileStats(staged, hasStagedUnsent, liveItems)
        fun pillText(count: Int, bytes: Long) = if (bytes > 0L)
            "$count file${if (count != 1) "s" else ""} · ${fmtBytesCompact(bytes)}"
        else
            "$count file${if (count != 1) "s" else ""}"
        when {
            stats.pendingWalks == 0 -> {
                lastSettledQueueStats = stats.count to stats.bytes
                binding.tvPreviewTitle.text = pillText(stats.count, stats.bytes)
                fitQueueCounterPill()
            }
            lastSettledQueueStats != null -> {
                // hold the last COMPLETE figure — the walk's landing re-runs
                // this pass and settles the pill; never a partial flash.
            }
            else -> {
                binding.tvPreviewTitle.text = pillText(stats.count, stats.bytes)
                fitQueueCounterPill()
            }
        }
        // ── Pass-27 (#3.3/#3.4): the pill now lives in the card's thin bottom ────
        // gradient frame (fragment_send.xml), where the whole card width is
        // available to it. fitQueueCounterPill still guarantees the COMPLETE
        // figure is visible — stepping the text size down (10.5sp → 9sp floor)
        // only in the rare case an unusually long figure would overflow the
        // frame — never an ellipsis, never a second line, never a height
        // change anywhere in the card.
        fitQueueCounterPill()
        binding.btnSendShare.visibility = if (hasStagedUnsent) View.VISIBLE else View.GONE

        // No item cap here on purpose -- RecyclerView recycles views, so it
        // handles any queue length cheaply. The old version capped this at
        // 20 with a plain "...and N more" text row, which meant anything
        // past the 20th item had no remove button at all.
        // (Post-v1.0 compile fix: `cutoff` is declared ONCE above — the
        // §previewer-instant-dismiss insertion duplicated it here and the
        // whole file failed to compile; same value, same scope, one name.)
        val rows = mutableListOf<QueueRow>()
        if (hasStagedUnsent) rows.addAll(staged.map { QueueRow.Staged(it) })
        if (hasLive) rows.addAll(liveItems.map { item ->
            val consumed = item.consumedByIps.size
            val expected = (item.targetIps ?: server?.connectedIPs?.filter { it.value > cutoff }?.keys?.toSet() ?: emptySet<String>()).size
            QueueRow.Live(item, consumed, expected)
        })
        queueAdapter.submit(rows)
        // Pass-12 (Required Implementation 6): the queue's rows just changed —
        // re-derive the fade bands once the new layout settles so a queue that
        // grew past its box lights its bottom fade immediately.
        binding.previewListWrap.post { if (_binding != null) updateQueueEdgeFades() }
    }

    // ── Pass-27 (#3.1/#3.2): the queue counter's folder-stats engine ──────────
    // A queued folder's real content (files + bytes, recursively) is walked
    // ONCE per folder, off the main thread, and cached by the folder's stable
    // identity (its SAF tree uri or filesystem path — the same folder staged
    // and later sent live reuses the one entry). The walk output carries, per
    // file: its path RELATIVE to the folder root (so PreviewItem.
    // excludedChildPaths — the per-file removals the expanded folder row
    // offers — can be honored with the exact same filter showChildren uses),
    // a cross-entry IDENTITY key (absolute path for filesystem files,
    // provider-authority + documentId for SAF files) so overlapping
    // selections deduplicate, and the file's size.
    private class QueuedFolderFile(val relPath: String, val identity: String, val size: Long)

    /** ── Pass-29 Part 1 (#6/#7): the queue card's change-tracking state. ──────
     *  folderStatsEpoch — bumped every time a folder-stats walk LANDS; part of
     *  updatePreviewCard's fingerprint so the pill always re-settles when a
     *  walk finishes (the walk's own inputs didn't move, only its result
     *  did). lastQueueFingerprint — the last fingerprint updatePreviewCard ran
     *  its expensive half for; an identical fingerprint short-circuits the
     *  whole stats/rows/adapter pass. lastSettledQueueStats — the last figure
     *  the pill showed with NO walk outstanding, held while a later walk is
     *  in flight so the pill never flashes a partial total. */
    private var folderStatsEpoch = 0
    private var lastQueueFingerprint = Long.MIN_VALUE
    private var lastSettledQueueStats: Pair<Int, Long>? = null

    /** Pass-29 Part 1 (#7): the counting pass's result — totals plus how many
     *  folder walks were REQUESTED but not yet landed during the pass (each
     *  such folder contributed nothing this tick; its landing re-runs the
     *  pass). */
    private class QueueStats(val count: Int, val bytes: Long, val pendingWalks: Int)

    /** Bounded LRU for the walked folder stats — same discipline as
     *  QueueAdapter's childrenCache (synchronized guard point, access-order,
     *  24 entries is far beyond any real queue's folder diversity). */
    private val folderStatsCache = object : LinkedHashMap<String, List<QueuedFolderFile>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<QueuedFolderFile>>): Boolean {
            return size > 24
        }
    }
    private val folderStatsInFlight = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    /** One shared, bounded executor for the stats walks — near-pure I/O-wait
     *  storage calls, never CPU work; two concurrent walks keep a large
     *  folder's first count quick without ever contending the UI thread. */
    private val folderStatsExecutor = java.util.concurrent.Executors.newFixedThreadPool(2) { r ->
        Thread(r, "wsx-queue-stats").apply { isDaemon = true }
    }

    private fun folderStatsGet(key: String): List<QueuedFolderFile>? = synchronized(folderStatsCache) { folderStatsCache[key] }
    private fun folderStatsPut(key: String, files: List<QueuedFolderFile>) { synchronized(folderStatsCache) { folderStatsCache[key] = files } }

    /** The stable cache key for a folder's representation — exactly one of
     *  uri/path is expected non-null (PreviewItem carries both fields for its
     *  dual representation; QSItem splits them into folderUri/folderPath). */
    private fun folderStatsKey(uri: String?, path: String?): String? =
        (uri ?: path)?.takeIf { it.isNotBlank() }

    /** Kicks the async walk for [key] if this folder's stats are neither
     *  cached nor already in flight. The completed walk stores its result and
     *  schedules one updatePreviewCard on the main thread, so the pill
     *  settles to the true figure without the poll ever blocking on I/O.
     *  ── Pass-36 Part 2 (#4): [includeHidden] is part of the walk's identity —
     *  the cache key carries it, and the walk itself skips hidden descendants
     *  unless the staged item was picked with the Files picker's hidden mode
     *  deliberately ON. The counter pill therefore counts exactly what the
     *  transfer will enumerate — hidden items never inflate it. */
    private fun requestFolderStats(key: String, uri: String?, path: String?, includeHidden: Boolean) {
        val statsKey = "$key|hid=$includeHidden"
        if (!folderStatsInFlight.add(statsKey)) return
        val appCtx = requireContext().applicationContext
        folderStatsExecutor.execute {
            val files = try { walkFolderStats(appCtx, uri, path, includeHidden) } catch (e: Exception) { emptyList() }
            folderStatsPut(statsKey, files)
            folderStatsInFlight.remove(statsKey)
            // ── Pass-29 Part 1 (#6/#7): the epoch bump is what makes the pill ────
            // settle — the walk's own inputs never changed, so without it the
            // fingerprint gate would skip the very pass this result exists
            // for. Bumped on the worker, read on the main thread; int writes
            // are atomic and strictly increasing, and a momentarily-stale read
            // only costs the walk's own post below re-running the pass.
            folderStatsEpoch++
            if (_binding != null && isAdded) {
                view?.post { if (_binding != null && isAdded) updatePreviewCard() }
            }
        }
    }

    /** The counting pass: staged items first (folders filtered by their live
     *  excludedChildPaths), then live server-queue items (folders counted in
     *  full — the send pipeline itself carries no per-child exclusions).
     *  ── Pass-34 Part 2 (#5): every queue ENTRY counts on its own, in full:
     *  an intentionally repeated folder, album, nested folder or file is a
     *  real second queue entry the send pipeline will actually deliver a
     *  second time, so its contents count a second time too — the counter
     *  represents the COMPLETE actual queue, never a name/path/content-
     *  deduplicated view of it (the old single shared identity set collapsed
     *  a duplicate folder onto its twin and silently ignored it). Dedup
     *  remains only WITHIN one entry — a manifest that lists the same member
     *  twice by accident still counts once — which is exactly the
     *  "prevent accidental duplication within a single selection operation"
     *  half of the contract. Removing one duplicate entry updates the
     *  totals by exactly that entry, because every entry contributed its own.
     *  Directories never contribute a count or a byte; nested folders
     *  flatten through the same recursive walk. Returns the totals plus how
     *  many folder walks were requested-but-not-landed during the pass
     *  (see QueueStats). */
    private fun computeQueuedFileStats(
        staged: List<PreviewItem>,
        hasStagedUnsent: Boolean,
        liveItems: List<LumiDropServer.QSItem>,
    ): QueueStats {
        var count = 0
        var bytes = 0L
        var pendingWalks = 0
        // One identity set PER ENTRY, allocated by the entry loops below and
        // threaded through every add — the within-entry dedup guard, never a
        // cross-entry one.
        fun addFile(entrySeen: HashSet<String>, identity: String, size: Long) {
            if (entrySeen.add(identity)) { count++; bytes += size.coerceAtLeast(0L) }
        }
        // ── Pass-33 (#1): a MANIFEST folder's stats are already known — its ─────
        // exact staged members and their sizes — so the pill counts it with
        // ZERO disk I/O, synchronously, in this very pass (never a pending
        // walk, never a placeholder window). Since Pass-34 Part 2 (#5) the
        // identity set is the ENTRY's, so the SAME album queued twice counts
        // fully twice — two real queue entries — while a manifest that
        // accidentally repeats one member still counts it once.
        fun addManifestFolder(entrySeen: HashSet<String>, manifest: List<PreviewItem.FolderChild>, excluded: Set<String>) {
            for (child in manifest) {
                if (child.relPath in excluded) continue
                if (excluded.any { child.relPath.startsWith("$it/") }) continue
                addFile(
                    entrySeen,
                    if (!child.path.isNullOrBlank()) "p:${File(child.path!!).absolutePath}"
                    else if (!child.uri.isNullOrBlank()) "u:${child.uri}"
                    else "n:${child.relPath}",
                    child.size)
            }
        }
        fun addFolder(entrySeen: HashSet<String>, uri: String?, path: String?, excluded: Set<String>,
                      manifest: List<PreviewItem.FolderChild>?, includeHidden: Boolean) {
            if (manifest != null) { addManifestFolder(entrySeen, manifest, excluded); return }
            val key = folderStatsKey(uri, path) ?: return
            // ── Pass-36 Part 2 (#4): hidden eligibility is part of the cached ──
            // walk's identity (see requestFolderStats) — the pill counts the
            // same tree the transfer enumerates, hidden children included only
            // when the pick deliberately allowed them.
            val files = folderStatsGet("$key|hid=$includeHidden") ?: run {
                requestFolderStats(key, uri, path, includeHidden)
                pendingWalks++
                return   // contributes nothing this tick; the landed walk re-runs the pass
            }
            for (f in files) {
                // The exact exclusion rule the expanded folder row and the
                // send pipelines use: a removed file by its own path, or an
                // entire removed subfolder by its prefix.
                if (f.relPath in excluded) continue
                if (excluded.any { f.relPath.startsWith("$it/") }) continue
                addFile(entrySeen, f.identity, f.size)
            }
        }
        // ── Pass-34 Part 2 (#5): a FRESH set per entry — the within-entry
        // dedup guard only. Two identical folders, an album queued twice, a
        // file that is also inside a queued album: every one of those is a
        // separate queue entry and counts in full, exactly as it will send.
        if (hasStagedUnsent) for (item in staged) {
            val entrySeen = HashSet<String>()
            when {
                item.isFolder -> addFolder(entrySeen, item.uri, item.path, item.excludedChildPaths, item.childManifest,
                    item.includeHiddenChildren)
                item.textContent != null ->
                    // A staged text message is one queued item of its own — its
                    // size is the UTF-8 payload length, exactly what the live
                    // side reports for it too.
                    addFile(entrySeen, "text:${item.name}:${item.size}", item.size)
                else -> addFile(entrySeen, fileIdentityKey(item.path, item.uri, item.name), item.size)
            }
        }
        for (item in liveItems) {
            val entrySeen = HashSet<String>()
            when {
                item.folderUri != null || item.folderPath != null ->
                    addFolder(entrySeen, item.folderUri, item.folderPath, emptySet(), item.childManifest,
                        item.includeHiddenChildren)
                item.textContent != null -> addFile(entrySeen, "text:${item.name}:${item.size}", item.size)
                else -> addFile(entrySeen, fileIdentityKey(item.filePath, item.contentUri, item.name), item.size)
            }
        }
        return QueueStats(count, bytes, pendingWalks)
    }

    /** A single queued FILE's cross-entry identity: the absolute filesystem
     *  path when one is known (the case for individually-picked files with a
     *  resolvable real path, and for every file inside a path-based folder),
     *  else the SAF provider authority + documentId (matching the identity
     *  the SAF folder walk derives for the same document), else a stable
     *  per-item fallback so the file still counts exactly once. */
    private fun fileIdentityKey(path: String?, uri: String?, name: String): String {
        if (!path.isNullOrBlank() && File(path).exists()) return "p:${File(path).absolutePath}"
        if (!uri.isNullOrBlank()) safDocumentKey(uri)?.let { return it }
        return "n:$name"
    }

    /** "u:<authority>|<documentId>" for a document-style SAF uri, or null for
     *  uri shapes that carry no document id (MediaStore content ids etc.). */
    private fun safDocumentKey(uriStr: String): String? = try {
        val uri = Uri.parse(uriStr)
        val authority = uri.authority ?: ""
        val segs = uri.pathSegments ?: emptyList<String>()
        var docId: String? = null
        for (i in segs.indices) if (segs[i] == "document" && i + 1 < segs.size) { docId = segs[i + 1]; break }
        docId?.let { "u:$authority|$it" }
    } catch (e: Exception) { null }

    /** The recursive walk behind the counter — one pass over the folder's
     *  whole tree (filesystem path or SAF tree uri), collecting every FILE
     *  with its path relative to the folder root, its deduplicating identity
     *  and its size. Directory entries themselves contribute nothing (an
     *  empty folder adds zero files, zero bytes — never "1 file"), and a
     *  folder that cannot be read degrades to "appears empty" rather than
     *  crashing or blocking the count. Mirrors the batched-children query
     *  pattern FileTreeNode.DocNode already proved for this app (one query
     *  per DIRECTORY, never one query per child). */
    private fun walkFolderStats(ctx: Context, uriStr: String?, pathStr: String?, includeHidden: Boolean): List<QueuedFolderFile> {
        val out = ArrayList<QueuedFolderFile>()
        if (!pathStr.isNullOrBlank()) {
            val root = File(pathStr)
            if (root.isDirectory) walkFsFolderStats(root, "", out, includeHidden)
            return out
        }
        if (!uriStr.isNullOrBlank()) {
            try {
                val treeUri = Uri.parse(uriStr)
                val rootDocId = try {
                    DocumentsContract.getTreeDocumentId(treeUri)
                } catch (e: Exception) {
                    try { DocumentsContract.getDocumentId(treeUri) } catch (e2: Exception) { null }
                } ?: return out
                val authority = treeUri.authority ?: ""
                // BFS over directory document uris; each level is one batched
                // children query (id, display name, mime, size).
                val queue = ArrayDeque<Pair<Uri, String>>()   // (document uri, prefix)
                queue.add((DocumentsContract.buildDocumentUriUsingTree(treeUri, rootDocId) ?: return out) to "")
                val proj = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                )
                while (queue.isNotEmpty()) {
                    val (dirUri, prefix) = queue.removeFirst()
                    val dirDocId = try { DocumentsContract.getDocumentId(dirUri) } catch (e: Exception) { continue }
                    val childrenUri = try {
                        DocumentsContract.buildChildDocumentsUriUsingTree(dirUri, dirDocId)
                    } catch (e: Exception) { continue }
                    try {
                        ctx.contentResolver.query(childrenUri, proj, null, null, null)?.use { c ->
                            val iId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                            val iName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                            val iMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                            val iSize = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                            while (c.moveToNext()) {
                                val docId = if (iId >= 0) c.getString(iId) else null
                                if (docId == null) continue
                                val name = (if (iName >= 0) c.getString(iName) else null) ?: continue
                                // ── Pass-36 Part 2 (#4): the hidden rule for SAF trees — ──
                                // a dot-prefixed document never counts unless the pick
                                // deliberately allowed hidden children.
                                if (!includeHidden && name.startsWith(".")) continue
                                val mime = if (iMime >= 0) c.getString(iMime) else null
                                val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                                val size = if (!isDir && iSize >= 0) c.getLong(iSize) else 0L
                                val rel = if (prefix.isEmpty()) name else "$prefix/$name"
                                if (isDir) {
                                    DocumentsContract.buildDocumentUriUsingTree(dirUri, docId)?.let { queue.add(it to rel) }
                                } else {
                                    out.add(QueuedFolderFile(rel, "u:$authority|$docId", size))
                                }
                            }
                        }
                    } catch (e: Exception) { /* unreadable directory → contributes nothing */ }
                }
            } catch (e: Exception) { /* malformed/unavailable uri → empty stats */ }
        }
        return out
    }

    /** ── Pass-36 Part 2 (#4): [includeHidden] skips hidden entries (dot- ──────
     *  prefixed names, or File.isHidden) at every depth unless the pick
     *  deliberately allowed them — the counter pill counts the same tree the
     *  transfer walks. */
    private fun walkFsFolderStats(dir: File, prefix: String, out: MutableList<QueuedFolderFile>, includeHidden: Boolean) {
        val kids = try { dir.listFiles() } catch (e: Exception) { null } ?: return
        for (k in kids) {
            if (!includeHidden && (k.name.startsWith(".") || (try { k.isHidden } catch (e: Exception) { false }))) continue
            val rel = if (prefix.isEmpty()) k.name else "$prefix/${k.name}"
            if (k.isDirectory) walkFsFolderStats(k, rel, out, includeHidden)
            else out.add(QueuedFolderFile(rel, "p:${k.absolutePath}", try { k.length() } catch (e: Exception) { 0L }))
        }
    }

    // ── Pass-28 (#2.3): the counter pill's deterministic fit, slim-frame edit ──
    // One measure-based pass: if the pill's text, at its base size, is wider
    // than the bottom frame that hosts it (the card's full inner width minus
    // a small symmetric breathing margin), step the text size down (9.5sp in
    // 0.5sp steps to an 8sp floor) until it fits — measured with the
    // TextView's own Paint, so the verdict is exact for the actual typeface,
    // density and string. The 9.5sp base matches the pill's new 12dp frame
    // height (fragment_send.xml): the figure is small, quiet and perfectly
    // legible, and stepping down is reserved for genuinely extreme figures.
    // The (text, frame width, chosen size) triple is cached; a pill whose
    // content and frame haven't changed is a pure no-op, so the 500ms poll
    // tick never re-measures needlessly. Runs via post when the frame hasn't
    // been laid out yet (the queue face starts GONE) — the next
    // updatePreviewCard after the face becomes visible re-runs it against
    // real numbers. The COMPLETE figure is ALWAYS shown — never an ellipsis,
    // never truncated (Pass-28 #2.4 contract).
    private var queuePillFitText: String? = null
    private var queuePillFitSlotW = -1
    private var queuePillFitSizeSp = -1f

    private fun fitQueueCounterPill() {
        if (_binding == null) return
        val pill = binding.tvPreviewTitle
        val text = pill.text?.toString() ?: return
        val frame = binding.previewListWrap
        val slotW = frame.width
        if (slotW <= 0) {
            // Not laid out yet (queue face GONE on first ticks) — retry on
            // the frame's first real layout.
            frame.post { if (_binding != null) fitQueueCounterPill() }
            return
        }
        // The pill is centered in the bottom frame; reserve a small symmetric
        // margin so it never kisses the card's side padding at any width.
        val availW = slotW - 2 * dp(14)
        if (text == queuePillFitText && availW == queuePillFitSlotW) return
        // Measure with a Paint at the display's exact scaled density, so the
        // verdict matches what the TextView will actually render for this
        // typeface/string — then step down from the full 9.5sp base (never
        // from the previous fit, so a shorter figure always climbs back up)
        // in 0.5sp steps to an 8sp floor until the COMPLETE string fits.
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        fun widthAt(sp: Float): Float {
            paint.textSize = sp * resources.displayMetrics.scaledDensity
            return paint.measureText(text)
        }
        val padH = pill.paddingLeft + pill.paddingRight
        val maxW = availW - padH
        var sizeSp = 9.5f
        while (sizeSp > 8f && widthAt(sizeSp) > maxW) sizeSp -= 0.5f
        val apply = sizeSp != queuePillFitSizeSp
        queuePillFitText = text
        queuePillFitSlotW = availW
        queuePillFitSizeSp = sizeSp
        if (apply) pill.textSize = sizeSp
    }

    /** Pass-27 (#3.4): the counter's compact, professional size format —
     *  single-decimal below 100 of a unit, plain integers at and above it
     *  ("3.4 MB", "128 MB", "1.2 GB"), never a trailing zero decimal and
     *  never more than one. Used ONLY by the queue pill — every other surface
     *  keeps its existing formatter. */
    private fun fmtBytesCompact(b: Long): String {
        if (b < 1024L) return "$b B"
        val kb = b / 1024.0
        if (kb < 1024.0) return if (kb >= 100.0) "${kb.toInt()} KB" else "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024.0) return if (mb >= 100.0) "${mb.toInt()} MB" else "%.1f MB".format(mb)
        val gb = mb / 1024.0
        return if (gb >= 100.0) "${gb.toInt()} GB" else "%.1f GB".format(gb)
    }

    // URI helpers -------------------------------------------------------------------

    // ── Pass-31 (#2): the three per-attribute helpers (getRealPath, ─────────────
    // getDisplayName, getContentSize — one content-resolver query each, three
    // IPC round-trips per picked file) are RETIRED in favor of the single
    // merged read in resolvePreviewItem: same name/size/path semantics, one
    // query per file. This is where Pass-29's large-batch latency actually
    // lived; nothing else in the fragment read them.

    // Helpers -------------------------------------------------------------------------

    private fun color(res: Int) = ContextCompat.getColor(requireContext(), res)
    private fun toast(msg: String) = Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()

    private fun fileTypeBadgeLabel(ext: String): String? = when (ext.lowercase()) {
        "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif",
        "mp4", "mkv", "avi", "mov", "webm", "wmv", "flv", "3gp", "m4v", "ts",
        "mp3", "flac", "ogg", "wav", "aac", "m4a", "opus", "wma", "aiff" -> null
        "" -> null
        else -> ext.uppercase().take(4)
    }

    private fun fmtTs(ms: Long) = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(ms))
}
