package com.lumidrop.x.server

import android.content.Context
import com.lumidrop.x.transfer.ResumableReceiveStore
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.lumidrop.x.p2p.NativeDiscovery
import com.lumidrop.x.settings.SettingsManager
import com.lumidrop.x.settings.WebUiSettingsManager
import com.lumidrop.x.transfer.FileTreeNode
import com.lumidrop.x.transfer.PipelineIO
import com.lumidrop.x.transfer.ProgressTrackingInputStream
import com.lumidrop.x.transfer.TransferManager
import com.lumidrop.x.transfer.textPreviewTitle
import com.lumidrop.x.transfer.uniqueTreePath
import com.lumidrop.x.utils.NetworkUtils
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response.Status
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class LumiDropServer(
    private val context: Context,
    private val port: Int
) : NanoHTTPD(port) {

    // Pass-6 (R-I): the Auto-Send engine (and any future component outside
    // this class) needs the server's own Context to read settings / start
    // sends from non-HTTP triggers (discovery events, its file observer).
    // Exposed read-only; the constructor field stays private as before.
    val appContext: Context get() = context

    // (Pass-8 follow-up: the relay-tunnel identity machinery — per-instance
    // tunnel secret + X-LumiDrop-Real-IP attribution via effectiveClientIp —
    // is removed together with the whole Internet Sharing system. Every
    // client is now attributed by its socket's own remote address; the
    // loopback exemption in blockedByInterfaceToggle remains for purely
    // local requests, which were the only other users of that path.)

    // Phase 10 (§speed D2): TCP_NODELAY on every accepted socket. NanoHTTPD
    // sets no socket options beyond an optional SO timeout — Nagle stays ON,
    // so every SSE push, every chunked-zip 3-write pattern, and every small
    // JSON response risks the 40ms delayed-ACK/Nagle stall on WiFi. The
    // factory must be installed BEFORE start() binds the socket; this init
    // block runs at construction, which precedes every start() call site.
    //
    // GOAT-PASS 1 (§speed — socket window sizing): every accepted socket now
    // also requests explicit 1MB send/receive buffers. Android kernels cap
    // SO_SNDBUF/SO_RCVBUF at net.core.{w,r}mem_max — frequently 512KB or
    // less on mid-range devices — and a transfer whose bandwidth×delay
    // product exceeds that window simply cannot go faster no matter how
    // efficient the userspace path is (a ~20ms RTT over 5GHz hotspot needs
    // ~800KB in flight to sustain 40MB/s). Where the kernel allows it this
    // lifts the per-stream ceiling; where it clamps, the socket lands at
    // the same maximum autotuning would have reached, so the change is
    // never worse. Best-effort like the NODELAY set: a socket option must
    // never break a transfer. Effective values are read back and logged
    // once per server start (diagnostics for the throughput report — not
    // per-connection, so a 300-file batch does not spam the log).
    private val socketTuningLogged = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun logSocketTuningOnce(s: java.net.Socket) {
        if (!socketTuningLogged.compareAndSet(false, true)) return
        try {
        } catch (e: Exception) { /* diagnostics only */ }
    }

    // ── GOAT-PASS 2 (§modern-Android — scheduling) ────────────────────────────
    // Bounded scheduling boost for the transfer pipeline's OWN threads (the
    // disk writer on receives, the prefetch reader on sends). These threads
    // live exactly as long as one active transfer and die with it, so the
    // boost is self-limiting by construction. Rationale: the byte pump is
    // the user-visible, time-critical work of the moment, and on a busy
    // device it competes with thumbnail decoding, SSE payload builds and
    // UI inflation for CPU — a scheduler hiccup there is a pipeline gap,
    // and a pipeline gap is a TCP window dip (the exact instability this
    // pass eliminates). -4 is the same band the platform grants display/
    // compositor work — deliberately NOT the audio/-16 class, which would
    // be abusive for file transfer. Best-effort: a failure to set a
    // priority must never break a transfer.
    private fun boostPipelineThread() {
        try { android.os.Process.setThreadPriority(-4) } catch (e: Exception) { /* best-effort */ }
    }
    init {
        setServerSocketFactory(NanoHTTPD.ServerSocketFactory {
            object : java.net.ServerSocket() {
                override fun accept(): java.net.Socket {
                    val s = super.accept()
                    try {
                        try { s.sendBufferSize = 1024 * 1024 } catch (e: java.net.SocketException) { /* clamped or unsupported */ }
                        try { s.receiveBufferSize = 1024 * 1024 } catch (e: java.net.SocketException) { /* clamped or unsupported */ }
                        s.tcpNoDelay = true
                        logSocketTuningOnce(s)
                    } catch (e: java.net.SocketException) { /* best-effort */ }
                    return s
                }
            }
        })
        // ── GOAT-PASS 5 (§3 final round): prewarm the WEB whole-storage ──────
        // search index at server start, completing the app-start prewarm the
        // native engine already gets (AppPrewarm → RecursiveFileSearchAccess).
        // The Files tab's own open already warmed it (apiFileList), but a
        // browser that connects and searches WITHOUT ever opening the Files
        // tab would otherwise pay the whole bounded walk inline on its FIRST
        // query — correct, just slower than it needs to be. warmAsync is
        // best-effort on its own daemon executor: construction never blocks,
        // and a failed warm simply falls back to the inline build.
        try { SearchIndex.warmAsync() } catch (e: Exception) { /* best-effort, always */ }
        // ── PASS 22 (§2 — first-open latency): prewarm EVERY Web-Client ──
        // tab's list payload at server start, on a daemon, through the exact
        // endpoint builders (so the responseCache and the Pass-17 thumb
        // prewarm fill as side effects). The audited shape: the first visit
        // to a tab paid the COLD enumeration inline — a full MediaStore
        // query for images/videos/music, a Documents+Downloads walkTopDown
        // for docs, a whole-package enumeration for apps and the home
        // screen's stats — seconds on the hardware this app targets, while
        // every later visit ran on warm OS caches (the user-visible "first
        // open slow, everything after fast"). The prewarm moves the cold
        // cost to boot, when no client is waiting: a browser arriving later
        // rebuilds on warm caches — the same fast path repeat visits always
        // had. TTLs stay untouched (2-5s): freshness semantics are exactly
        // what they were; only the COLD first hit is eliminated. A client
        // racing the prewarm simply triggers the same build itself (last
        // write wins, both correct). Best-effort like the search warm: a
        // failure leaves the old inline path, never blocks the socket.
        Thread({
            try {
                apiStats(); apiImages(); apiVideos(); apiMusic()
                scanRootsToJson(arrayOf(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath,
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath)
                ) { MimeTypes.isDoc(it.extension.lowercase()) }
                    .let { responseCache["media-docs"] = CachedJson(it.toString(), System.currentTimeMillis(), filesVersion.get()) }
                apiInstalledApps()
            } catch (e: Exception) { /* best-effort prewarm — inline paths own correctness */ }
        }, "wsx-tab-prewarm").apply { isDaemon = true }.start()
    }

    // Phase 10 (§speed D6): gzip OFF on the LAN. NanoHTTPD gzips every
    // text/* and JSON response through a fresh Deflater — including the SSE
    // event stream, where it silently buffers pushes and burns CPU per
    // tick. On a LAN the payloads are small enough that bandwidth is never
    // the constraint; latency and host CPU are. One override kills the
    // whole class.
    override fun useGzipWhenAccepted(r: Response): Boolean = false

    init {
        // GOAT (Phase 3, item 10 -- stale-connection self-heal): reported
        // symptom was two native devices losing the ability to INITIATE
        // transfers with each other until both apps were force-restarted --
        // never a slow request timing out, an outright hang instead, and
        // needing the whole process killed (not just retrying, not just
        // toggling the server) to clear. That signature matches a well-
        // documented HttpURLConnection behavior, not a bug specific to any
        // one endpoint here: by default the JVM pools and reuses TCP
        // connections per host:port across requests, and on a LAN/hotspot
        // topology a peer's socket can go dead without a clean FIN/RST --
        // the other side's app backgrounded, its hotspot recycled the NAT
        // mapping, a brief WiFi drop -- with nothing to tell THIS process
        // its pooled connection to that peer is no longer any good. Every
        // subsequent request to that same peer then keeps trying to reuse
        // the same dead pooled connection instead of opening a fresh one,
        // which is exactly "stuck until force-restarted" -- restarting is
        // the only thing that clears a JVM-level connection pool the app
        // has no other handle on. Disabling keep-alive entirely means every
        // request opens its own fresh connection, so there is never a
        // pooled connection left around to go stale in the first place --
        // full self-heal, at the cost of a new TCP handshake per request,
        // which is negligible next to the file transfers this is for. Set
        // here AND in NativePushClient's own init (this device might only
        // ever be a receiver, or only ever a sender, in a given session) --
        // http.keepAlive is a process-wide JVM property either way, so
        // setting it twice is redundant, never conflicting.
        System.setProperty("http.keepAlive", "false")
    }

    // ── Pass-14 (P4 — instant previewer): the SSE nudge ────────────────────
    // The 250 ms ticker already pushes every state change to every open
    // event stream, which is fast — but a USER action (a Cancel tap on the
    // host, an accept, a dispatch) can land anywhere inside that 250 ms
    // window, and "instant" should not be timer-quantized. sseNudge()
    // schedules ONE out-of-band sseTick on the SAME single-threaded executor
    // the periodic tick runs on (serialized with it by construction), with
    // an in-flight guard so a burst of mutations — a cancelling multi-file
    // batch, per-chunk progress — coalesces into one immediate tick instead
    // of stampeding. Idle cost when nothing changed: the tick's own epoch
    // gates skip the payload build entirely, exactly like a periodic tick.
    private val sseNudgeInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun sseNudge() {
        if (!sseTickerStarted) return
        if (!sseNudgeInFlight.compareAndSet(false, true)) return   // one in flight is enough
        try {
            sseExec.execute {
                try { sseNudgeInFlight.set(false); sseTick() } catch (e: Exception) {
                    sseNudgeInFlight.set(false) /* never let a nudge kill anything */ }
            }
        } catch (e: Exception) {
            sseNudgeInFlight.set(false)   // executor rejected (shutting down) — the periodic tick still runs
        }
    }

    // ── Pass-14 (resumable receives, requirement 2): cold-start restore ─────
    // Runs once per server construction on a background thread: every
    // registry entry whose staging partial still exists becomes a live
    // "Waiting for reconnection" card again (same row semantics the
    // interruption itself created), and orphaned staging partials with NO
    // registry entry — leftovers from before this registry existed, or from
    // a crash between partial-write and registry-write — are adopted with
    // best-effort metadata so their data is visible and discardable too.
    // The sender-side rule is untouched: THIS device's own SEND rows never
    // come back after a restart; only receives (where the partial lives on
    // THIS device's disk) do.
    private val resumableRestoreDone = java.util.concurrent.atomic.AtomicBoolean(false)
    private fun restoreResumableReceivesAsync() {
        if (!resumableRestoreDone.compareAndSet(false, true)) return
        Thread({
            try { restoreResumableReceives() } catch (e: Exception) {
            }
        }, "wsx-resumable-restore").apply { isDaemon = true }.start()
    }
    private fun restoreResumableReceives() {
        val rootPath = try { SettingsManager.getUploadPath(context) } catch (e: Exception) { null }
        var restored = 0
        // 1) Registry entries (the normal path — interrupted after this
        //    build was installed). TransferManager is a PROCESS singleton
        //    that outlives any one server instance, so the restore must be
        //    idempotent across server restarts: an entry whose card already
        //    exists (a previous restore, or the interruption itself if the
        //    server restarted without the process dying) is left alone.
        for (entry in ResumableReceiveStore.all(context)) {
            val staging = ResumableReceiveStore.stagingFileFor(entry.destPath)
            if (!staging.exists() || staging.length() <= 0L) {
                // Partial gone (consumed, deleted, or swept): the entry must
                // not outlive its data.
                ResumableReceiveStore.remove(context, entry.destPath)
                continue
            }
            if (TransferManager.findResumableUploadByDestPath(entry.destPath) != null) continue
            TransferManager.createResumableWaitingRow(
                fileName = entry.fileName,
                totalBytes = entry.totalBytes,
                peerIp = entry.senderIp,
                peerName = entry.senderName,
                filePath = entry.destPath,
                transferredBytes = staging.length(),
            )
            restored++
        }
        // 2) Orphaned staging partials with no registry entry: adopt them so
        //    their data is visible and discardable (pre-pass-14 interruptions,
        //    or a crash in the tiny window before the registry write). Same
        //    idempotence rule: a partial that already has a card is skipped.
        if (rootPath != null) {
            val root = File(rootPath)
            if (root.isDirectory) try {
                root.walkTopDown().maxDepth(4)
                    .filter { it.isFile && it.name.endsWith(".wsxpart") && it.length() > 0L }
                    .forEach { partial ->
                        val destPath = File(partial.parentFile, partial.name.removePrefix(".").removeSuffix(".wsxpart")).absolutePath
                        if (TransferManager.findResumableUploadByDestPath(destPath) != null) return@forEach
                        val size = ResumableReceiveStore.metaDeclaredSize(destPath)
                        ResumableReceiveStore.upsert(context, ResumableReceiveStore.Entry(
                            destPath = destPath,
                            fileName = File(destPath).name,
                            senderName = "Unknown Device",
                            senderIp = "",
                            totalBytes = size,
                            savedAtMs = partial.lastModified(),
                        ))
                        TransferManager.createResumableWaitingRow(
                            fileName = File(destPath).name,
                            totalBytes = size,
                            peerIp = "",
                            peerName = "Unknown Device",
                            filePath = destPath,
                            transferredBytes = partial.length(),
                        )
                        restored++
                    }
            } catch (e: Exception) { /* best-effort adoption only */ }
        }
        // 3) Prune any kept-for-resume rows whose partial vanished between
        //    the registry write and now (belt-and-braces; the registry loop
        //    above already removed the registry half of those).
        TransferManager.pruneResumableRows { path ->
            path != null && ResumableReceiveStore.stagingFileFor(path).let { it.exists() && it.length() > 0L }
        }
        if (restored > 0) {
            stateVersion.incrementAndGet()
        }
    }

    data class QSItem(
        val name: String,
        val filePath: String?,
        val contentUri: String?,
        val size: Long,
        val mime: String = "application/octet-stream",
        val addedAt: Long = System.currentTimeMillis(),
        val folderUri: String? = null,
        // GOAT v2 (item 2 — album send): a folder identified by a plain
        // absolute filesystem path rather than a SAF tree uri. Added
        // alongside folderUri, never replacing it — enqueueFolder's
        // existing SAF-picked folders still go through folderUri exactly
        // as before; this is for a caller (a selected media album) that
        // already knows a real directory path and has no SAF grant for it
        // at all, only the broad storage access this app already requires
        // to reach the Media Selector in the first place (see
        // MediaLibrary.kt's own doc comment). Every read site below that
        // branches on folderUri now branches on "either" — see each site's
        // own comment for why.
        val folderPath: String? = null,
        // null = visible to every connected device (broadcast).
        // Non-null = only these specific IPs see this item.
        // Pass-6 (R-H): var (not val) now — cancelQuickShareForPeer removes
        // ONE client's IP from the set when the host cancels that client's
        // waiting row, scoping the withdrawal to the client whose offer was
        // cancelled while every other target keeps theirs. @Volatile for the
        // same cross-thread visibility reason the queue itself is a COW list.
        @Volatile var targetIps: Set<String>? = null,
        // When set, this item IS the text itself — there's no file anywhere
        // on disk. The client shows it in a Copy/Cancel popup instead of
        // downloading anything (Quick Share Text no longer creates a
        // temporary .txt file at all).
        val textContent: String? = null,
        // Tracks which client IPs have already downloaded this item.
        // Item is auto-removed from queue once every targeted client
        // (or every currently-connected client for broadcast items)
        // has consumed it. Using a thread-safe set so concurrent
        // downloads from different clients never race.
        val consumedByIps: MutableSet<String> =
            java.util.Collections.newSetFromMap(ConcurrentHashMap()),
        // ── Pass-33 (#1): non-null for a MANIFEST folder — one whose contents ──
        // were fixed at staging time to exactly this list (a media-album
        // folder; see PreviewItem.childManifest's own doc). The ZIP streamer
        // (apiQSDl) builds a virtual ManifestNode from it instead of walking
        // the physical directory, so a browser download of the "Download"
        // album zips precisely the album's shown images/videos — never the
        // APKs, documents or subfolders sitting in the same directory.
        val childManifest: List<com.lumidrop.x.transfer.PreviewItem.FolderChild>? = null,
        // ── Pass-36 Part 2 (#4): hidden-descendant eligibility — see ───────────
        // PreviewItem.includeHiddenChildren. False (the default) makes every
        // live walk of this folder (the ZIP stream below) skip hidden files
        // and hidden subfolders at every depth; true only for a folder the
        // user picked while the Files picker's "Show hidden files" mode was
        // deliberately ON.
        val includeHiddenChildren: Boolean = false,
        // ── GOAT-PASS 14 (Phase 1, §2.1): the sender's queue-time child ────────
        // removals for a FOLDER item — natural root-relative paths ("Subfolder/
        // IMG_02.jpg") exactly as PreviewItem.excludedChildPaths carries them.
        // The native-push leg has always intended to honor these; the browser
        // leg dropped them at pushItemLive and re-walked the FULL directory
        // here — the "removed 6 of 30 items, the receiver still got all 30"
        // root cause. The ZIP stream below now filters this folder's entries
        // through them (remapped into the archive's own "<folderName>/<relPath>"
        // namespace).
        val excludedChildPaths: Set<String> = emptySet(),
        // ── GOAT-PASS 14 (Phase 1, §1.1): non-null when this queued item is ────
        // the app-side "send queue as one ZIP" batch — an IMMUTABLE snapshot of
        // the exact PreviewItems that made up the sent batch (names, addresses,
        // sizes, folder exclusions, manifest children, hidden eligibility and
        // text payloads alike). The item behaves as ONE ordinary Quick-Share
        // file offer (one chip, one Accept/Decline, one transfer row, one
        // streamed archive via /api/qs/dl) whose bytes are the zip synthesized
        // live from this snapshot at download time — the same streaming
        // pattern the folder branch of apiQSDl has always used, never a second
        // ZIP system. Because the snapshot rides the QSItem itself, a Retry
        // after a failed delivery re-offers EXACTLY this batch — it can never
        // be silently rebuilt from a queue that has since changed.
        val zipBatch: List<com.lumidrop.x.transfer.PreviewItem>? = null,
    )

    val quickShareQueue  = CopyOnWriteArrayList<QSItem>()
    val connectedClients = AtomicInteger(0)
    // IP → last-seen timestamp (ms); updated on every request AND by the SSE
    // ticker for every live event stream (Phase 1's sseTick refresh — a
    // pure-SSE client makes no other requests).
    val connectedIPs     = ConcurrentHashMap<String, Long>()
    // IP → resolved display name (hostname or device name)
    val connectedNames   = ConcurrentHashMap<String, String>()

    // ── Phase 2 of 10: the authoritative session layer ────────────────────────
    // One place owns browser-session lifecycle. The maps above (plus
    // deviceModes / deviceNames / connectedUAs / clientCaps below) stay the
    // raw per-IP state every existing consumer already reads; what changed
    // is WHO decides lifecycle transitions. Before Phase 2, three different
    // consumers each mutated these maps with their own staleness opinion:
    //   - SendFragment.updateDevicesUI removed entries older than 30s (from
    //     the UI thread, racing the SSE ticker's refresh writes — a
    //     TOCTOU where a live device's fresh timestamp got deleted anyway:
    //     the residual "device randomly disappears" root cause)
    //   - apiClients() did its own removeAll with the same race
    //   - serve() dropped a session's mode override on a 30s gap — a gap
    //     the browser's own SSE reconnect window (stall-drop at 30s +
    //     EventSource backoff) routinely trips with the page still open:
    //     the "mode silently reverts" root cause.
    // Now: VISIBILITY (shows in Connected Devices) = last-seen within
    // SESSION_TTL_MS or a live SSE stream. TERMINATION (session-scoped state
    // dropped, entry reaped) = no activity of any kind for SESSION_GRACE_MS.
    // The grace deliberately covers the real reconnect envelope, so a
    // returning client RESUMES its session (mode override + capability
    // report intact) instead of being read as a blank new one, while a
    // genuinely departed client is still reaped deterministically by the
    // single sweep — never by whichever reader polled first.
    private val SESSION_TTL_MS    = 30_000L   // visible/recent window (unchanged semantics)
    private val SESSION_GRACE_MS  = 90_000L   // same-session window (covers SSE stall-drop
                                              // 30s + browser reconnect backoff + a blip)

    /** Identity of THIS server instance (fresh per construction). Carried in
     *  every state payload as "sid" so the Web Client can tell "the host
     *  restarted" (new instance, version numbering restarts — that is a NEW
     *  ordering domain, not a stale event) apart from "this payload is older
     *  than what I already applied" (same instance, monotonic version). */
    val serverInstanceId: String = java.util.UUID.randomUUID().toString()

    /** Authoritative "is this browser session connected right now": recent
     *  activity OR a live, draining SSE stream. Read-only — termination is
     *  sweepSessions()'s job alone. */
    fun isSessionConnected(ip: String): Boolean {
        val lastSeen = connectedIPs[ip] ?: return hasLiveSse(ip)
        return System.currentTimeMillis() - lastSeen < SESSION_TTL_MS || hasLiveSse(ip)
    }

    /** The one list every host-side consumer asks for "which browser
     *  sessions are connected": Connected Devices, the send-target picker.
     *  Deterministically ordered (IP-sorted) so cards never reshuffle
     *  between refresh ticks, and filtered by approval state when
     *  Connection Confirmation is on — the same two rules the card itself
     *  applies, now expressed once instead of re-derived per consumer. */
    fun liveBrowserIps(confirmOn: Boolean = SettingsManager.isConfirmConn(context)): List<String> =
        connectedIPs.keys.asSequence()
            .filter { isSessionConnected(it) }
            .filter { !confirmOn || connRequests[it]?.decision == "accepted" }
            .sorted()
            .toList()

    /** Authoritative session sweep — runs on the single SSE ticker thread
     *  (serialized with itself), with compute()-guarded removal so a
     *  concurrent activity write can never lose a race against it: either
     *  the fresh timestamp lands first and this sweep sees it (session
     *  kept), or the entry is removed first and the fresh write re-creates
     *  it (session reborn) — a live device can never be deleted. Terminated
     *  sessions drop every piece of session-scoped state: mode override,
     *  capability report, UA/hostname caches, the last-seen entry itself.
     *  Custom NAMES survive (durable by spec — DeviceStore persists them);
     *  still-pENDING connection requests survive (never silently dropped).
     *
     *  Pass-10 (real-time connected-device cleanup): the sweep ALSO watches
     *  this device's own LAN. The moment no LAN-family interface remains
     *  (Wi-Fi AND hotspot off) or the local IP CHANGED (left the network),
     *  every browser session is unreachable by definition — none of them
     *  can deliver a request over an interface that no longer exists — so
     *  they are all terminated NOW instead of riding their 30s visibility
     *  TTL / 90s grace. This is the host half of "a turned-off hotspot
     *  clears Connected Devices immediately" (the native-peer half lives in
     *  NativeDiscovery's own network watch). */
    private fun sweepSessions() {
        val now = System.currentTimeMillis()
        // Pass-10: own-network liveness. sseTick calls this sweep up to 4x/s,
        // and getLocalIpAddress enumerates interfaces + ConnectivityManager —
        // so the check itself is timestamp-gated to ~2s, matching the UI's
        // own beat and keeping the "immediate" detection well inside what a
        // human reads as instant while costing one subtraction when idle.
        if (now - lastLanCheckAtMs > 2000L) {
            lastLanCheckAtMs = now
            val lanNow = try {
                com.lumidrop.x.utils.NetworkUtils.getLocalIpAddress(context)
            } catch (e: Exception) { null }
            if (lanNow != lastKnownLanIp) {
                if (lastKnownLanIp != null && connectedIPs.isNotEmpty()) {
                }
                // ── Pass-17 (immediate disconnect states): every live transfer ──
                // connection just became unreachable by definition — latch the
                // transition so the very next sseTick marks all active rows as
                // awaiting reconnection ("Waiting for reconnection" / "Auto-
                // Resume on Reconnection") instead of waiting seconds for a
                // blocked TCP write to notice (the reported "the expected
                // states sometimes never appear").
                networkLostOrChangedLatch = true
                lastKnownLanIp = lanNow
                if (lanNow == null) {
                    // No LAN at all: every session dies now. closeSseFor makes
                    // each stream's writer notice and drop the client too.
                    for (ip in connectedIPs.keys.toList()) {
                        connectedIPs.remove(ip)
                        deviceModes.remove(ip)
                        clientCaps.remove(ip)
                        connectedNames.remove(ip)
                        connectedUAs.remove(ip)
                        closeSseFor(ip)
                    }
                    if (pendingUploads.isNotEmpty()) {
                        // Their uploaders can no longer reach this host; resolve
                        // the approval rows honestly instead of leaving the
                        // Incoming Files sheet waiting on ghosts.
                        val ids = pendingUploads.keys.toList()
                        for (id in ids) {
                            pendingUploads.remove(id)
                            TransferManager.cancelPendingUpload(id)
                        }
                        refreshPendingNotif()
                    }
                    sessionEpoch.incrementAndGet()
                    return
                }
                // LAN changed (rare — left one network for another): sessions
                // from the old subnet are equally dead; age every last-seen
                // stamp past the grace so the normal guarded sweep below reaps
                // them on THIS pass (a live-SSE stream still attached to a
                // somehow-surviving socket keeps its session, exactly as before).
                if (lastKnownLanIp != null || lanNow != null) {
                    for (ip in connectedIPs.keys.toList()) {
                        connectedIPs.replace(ip, now - SESSION_GRACE_MS - 5_000L)
                    }
                }
            }
        }
        for (ip in connectedIPs.keys.toList()) {
            val lastSeen = connectedIPs[ip] ?: continue
            if (now - lastSeen < SESSION_GRACE_MS || hasLiveSse(ip)) continue
            // Guarded removal via remove(key, value) — a Java 5 ConcurrentMap
            // API, deliberately NOT Map.compute (a Java 8 default method:
            // NoSuchMethodError on this project's API 21-23 floor, the same
            // hazard class as the removeIf note in
            // sweepStalePendingUploads). remove(key, value) deletes the entry
            // ONLY while it still holds the exact stale timestamp this sweep
            // read — a racing liveness refresh (a request landing, or sseTick
            // writing for a just-registered stream) stores a different value
            // and the removal no-ops; a refresh landing AFTER a successful
            // removal re-creates the entry via serve()'s write. Either way a
            // live device can never be deleted.
            if (connectedIPs.remove(ip, lastSeen)) {
                val hadOverride = deviceModes.remove(ip) != null
                val hadCaps = clientCaps.remove(ip) != null
                connectedNames.remove(ip)
                connectedUAs.remove(ip)
                // Phase 8: a terminated session's payload-visible state
                // reverts (mode override, capability echo, device label) —
                // bump the session epoch so the SSE dirty gate rebuilds
                // any still-connected client that had seen the old values.
                sessionEpoch.incrementAndGet()
            }
        }
    }
    // Pass-10 (network watch state): the LAN this host last knew itself to
    // be on — see sweepSessions' network-watch block for the full reasoning.
    @Volatile private var lastKnownLanIp: String? = null
    @Volatile private var lastLanCheckAtMs: Long = 0L
    // ── Pass-17 (immediate disconnect states): set by sweepSessions' network ──
    // watch on the tick where the LAN vanished or changed, consumed by the
    // very next sseTick — the transfer rows are latched reconnecting within
    // one 250ms tick of detection, and the latch pattern keeps the marking
    // idempotent across the sweep's own cadence.
    @Volatile private var networkLostOrChangedLatch = false

    // ── Incoming transfer approval gate ─────────────────────────────────────
    // A lightweight PRE-flight request/approval step, deliberately kept
    // separate from the actual (unmodified) apiUpload byte-transfer path
    // below. The client asks "may I send X?" first; only once the Host
    // explicitly Accepts (or Auto-Accept is on) does the client proceed with
    // the real upload via the existing, proven /api/upload endpoint. This
    // means zero changes were needed to the actual transfer mechanism itself.
    data class PendingUpload(
        val id: String,
        val name: String,
        val size: Long,
        val fromName: String,
        val fromIp: String,
        @Volatile var status: String = "pending",   // pending | accepted | rejected
        val createdAt: Long = System.currentTimeMillis(),
        // ── GOAT-PASS 17 (§6 — the accepted-but-queued upload must survive ──
        // its queue wait): the moment the host accepts (or the auto-accept
        // path does), the request's liveness clock restarts HERE. The old
        // sweep keyed everything off createdAt, so an accepted upload whose
        // POST was still queued behind the client's own concurrency limit
        // (a slow earlier file) was swept out from under its pending row the
        // instant createdAt crossed the 3-minute TTL — the client's eventual
        // POST answered "Unknown request" and the sender waited forever,
        // the browser-upload twin of the reported Accept-does-nothing.
        // decidedAt advances on every status poll (a queued client polls),
        // and the sweep below only removes an ACCEPTED request after the
        // DECISION window lapses in silence.
        @Volatile var decidedAt: Long = 0L
    )
    val pendingUploads = ConcurrentHashMap<String, PendingUpload>()
    private val PENDING_UPLOAD_TTL = 3 * 60_000L  // 3 minutes total lifetime, any status —
                                                   // generous for a host who's briefly away,
                                                   // bounded so abandoned requests don't pile up

    // ── GOAT-PASS 17 (§13 — acceptance-lifecycle observability) ────────────────
    // The intermittent "Accept did nothing" class of failure was invisible:
    // every accept/decline endpoint returned Unit and a dropped decision left
    // no trace anywhere. AcceptTrace is the structured, BOUNDED record of the
    // acceptance transaction: correlation ids (push/request id + item key),
    // the lifecycle stage, the outcome, and a timestamp. It stores no file
    // contents, no auth material, no network addresses, and never grows
    // unbounded (a fixed ring; the oldest entry is overwritten). The next
    // intermittent report can be answered with the actual decision trail.
    object AcceptTrace {
        private const val CAP = 256
        private class Entry(val atMs: Long, val pushId: String, val key: String,
                            val stage: String, val outcome: String)
        private val ring = ArrayDeque<Entry>(CAP)
        fun record(pushId: String, key: String, stage: String, outcome: String) {
            synchronized(ring) {
                if (ring.size == CAP) ring.removeFirst()
                ring.addLast(Entry(System.currentTimeMillis(), pushId, key, stage, outcome))
            }
        }
        /** Read-only snapshot (diagnostics): "[at] pushId key stage -> outcome" lines. */
        fun dump(): List<String> = synchronized(ring) {
            ring.map { e -> "[${e.atMs}] ${e.pushId} ${e.key} ${e.stage} -> ${e.outcome}" }
        }
    }

    private fun sweepStalePendingUploads() {
        val now = System.currentTimeMillis()
        // Clean up any PENDING TransferManager entry for a request that's
        // about to be swept — otherwise an accepted-but-abandoned upload
        // (host never got a real POST, e.g. the client's tab was closed)
        // would leave a permanently-stuck "Waiting" ghost in the Transfers
        // tab, since nothing else ever moves a PENDING entry out of that
        // state on its own.
        //
        // Phase 1 of 10 (ghost-completion root cause): an upload whose real
        // POST is ALIVE but queued behind a server-side receiving slot is
        // NOT abandoned — sweeping its PENDING row out from under that live
        // request is exactly what made apiUpload's claimPendingUpload come
        // up empty (see the start() fallback there) and, before that fix,
        // complete the upload under a dead id with NO Transfers row ever
        // recording it. isReceivingInFlight covers both "waiting for a slot"
        // and "holding one".
        // ── GOAT-PASS 17 (§6): the age key is the DECISION clock, not the ──
        // birth clock. A still-undecided request (the host hasn't tapped
        // anything) keeps the original 3-minute createdAt bound exactly as
        // before; an ACCEPTED one measures from its verdict (decidedAt),
        // refreshed by every status poll a queued client makes — so the
        // only accepted request this removes is one whose client has been
        // SILENT for the full TTL after the verdict (tab closed, upload
        // abandoned), which is the genuine abandonment this sweep exists
        // for. The in-flight guard still wins over everything.
        fun ageOf(pu: PendingUpload): Long =
            now - (if (pu.status == "accepted" && pu.decidedAt > 0L) pu.decidedAt else pu.createdAt)
        pendingUploads.entries.filter { ageOf(it.value) > PENDING_UPLOAD_TTL }
            .forEach { entry ->
                val t = TransferManager.findTransferByUploadRequestId(entry.key)
                if (t != null && TransferManager.isReceivingInFlight(t.id)) {
                    return@forEach
                }
                if (entry.value.status == "accepted")
                    AcceptTrace.record(entry.key, entry.value.name, "sweep",
                        "accepted-but-silent past TTL — resolving pending row (client gone)")
                TransferManager.cancelPendingUpload(entry.key)
            }
        // NOTE: MutableCollection.removeIf() is a Java 8 default method that Android's
        // own platform collection classes only implement from API 24 onward — calling
        // it directly throws NoSuchMethodError on API 21-23. Kotlin's OWN stdlib
        // removeAll(predicate) extension does the same job via plain Iterator.remove()
        // (a pre-Java-8, always-available method), so it's safe on every API level.
        pendingUploads.entries.removeAll { ageOf(it.value) > PENDING_UPLOAD_TTL }
    }

    // Accepting a request (host tap, or Accept-All) is the moment the
    // Transfers tab should start showing "Waiting" for it — exactly
    // mirroring how a Quick-Share item's PENDING entry is created at
    // /api/quickshare/accept time, not once the real byte-transfer begins.
    // Without this, only whichever upload(s) currently fit the client's own
    // concurrency limit would ever show up host-side; everything else
    // accepted-but-not-yet-started would be invisible until its own turn
    // came up — which is exactly the "only the active transfer" bug.
    fun acceptUpload(id: String) {
        val pu = pendingUploads[id]
        if (pu == null) {
            AcceptTrace.record(id, "(upload)", "accept-upload", "unknown-request (no record — swept or expired?)")
            return
        }
        if (pu.status == "pending") {
            pu.status = "accepted"
            pu.decidedAt = System.currentTimeMillis()
            AcceptTrace.record(id, pu.name, "accept-upload", "decision=accepted; registering pending row")
        } else {
            AcceptTrace.record(id, pu.name, "accept-upload", "already '${pu.status}' (idempotent no-op)")
            return   // an accepted/rejected verdict is final — never re-register
        }
        TransferManager.startPendingUpload(id, pu.name, pu.size.takeIf { it > 0 } ?: -1L, pu.fromIp, pu.fromName)
        refreshPendingNotif()
        // Phase 10: the verdict is a PUSH — the uploader's SSE payload
        // carries "ur" and the sessionEpoch bump makes every client's next
        // tick rebuild it. The 1200ms poll retires to a safety net.
        sessionEpoch.incrementAndGet()
    }
    fun rejectUpload(id: String) {
        val pu = pendingUploads[id] ?: return
        if (pu.status == "pending") {
            pu.status = "rejected"
            pu.decidedAt = System.currentTimeMillis()
            AcceptTrace.record(id, pu.name, "reject-upload", "decision=rejected")
        }
        refreshPendingNotif(); sessionEpoch.incrementAndGet()
    }
    fun acceptAllUploads() {
        pendingUploads.values.filter { it.status == "pending" }.forEach {
            it.status = "accepted"
            it.decidedAt = System.currentTimeMillis()
            AcceptTrace.record(it.id, it.name, "accept-all-upload", "decision=accepted; registering pending row")
            TransferManager.startPendingUpload(it.id, it.name, it.size.takeIf { s -> s > 0 } ?: -1L, it.fromIp, it.fromName)
        }
        refreshPendingNotif()
        sessionEpoch.incrementAndGet()
    }
    fun rejectAllUploads() {
        pendingUploads.values.forEach {
            if (it.status == "pending") {
                it.status = "rejected"
                it.decidedAt = System.currentTimeMillis()
                AcceptTrace.record(it.id, it.name, "reject-all-upload", "decision=rejected")
            }
        }
        refreshPendingNotif()
        sessionEpoch.incrementAndGet()
    }
    private fun refreshPendingNotif() {
        if (pendingUploads.values.none { it.status == "pending" })
            com.lumidrop.x.service.ServerForegroundService.clearPendingUploadNotif(context)
    }

    // Streaming ZIP: no async job state needed — ZipOutputStream pipes directly
    // to the HTTP response via PipedInputStream/PipedOutputStream.

    // In-flight QS downloads: name → (QSItem, expiry-ms).
    // Keeps items reachable for range/resume HTTP requests that arrive AFTER the item
    // has already been dequeued by the first download hit — preventing the browser from
    // receiving a tiny JSON-error body (≈101 bytes) instead of the full file stream.
    private val inFlightQS    = ConcurrentHashMap<String, Pair<QSItem, Long>>()
    // Phase 10 (light-speed sync): filesystem epoch. Bumped whenever a file
    // lands on (or leaves / renames on) the host: web-upload completion,
    // native-push file completion, delete, rename. Every client's SSE
    // payload carries the current value ("fv"); a change means "your Files/
    // Images/Videos/Music/Apps/Recent/Storage views are stale — refetch
    // what's on screen now", which is exactly the "client still has to
    // refresh the browser to see changes from the host" gap: the page now
    // refreshes ITSELF the moment the host's content actually changes.
    private val filesVersion = java.util.concurrent.atomic.AtomicLong(0)
    /** GOAT-PASS 9 (§5): [changedPath] scopes the NATIVE fan-out (Files-sheet
     *  listing cache invalidation for the path and its parent) so a landed
     *  or removed file refreshes the app's own surfaces — not just the web
     *  views — without a restart. Null falls back to the receive root. */
    private fun bumpFilesVersion(changedPath: String? = null) {
        filesVersion.incrementAndGet(); sseNudge()
        // ── GOAT-PASS 5 (§3): the app's own filesystem just changed (a file
        // landed, something was deleted/renamed/pasted) — drop the cached
        // whole-storage search index and start the background rebuild now,
        // so the next query is answered from a fresh index instead of
        // waiting out the 5-minute freshness window.
        try { SearchIndex.invalidateAndRewarm() } catch (e: Exception) { /* best-effort — the TTL still bounds staleness */ }
        // ── GOAT-PASS 9 (§5): the native half of the same notification — ──
        // the Files sheet's process cache, the native search index and the
        // Music sheet's audio window all learn about the change NOW, and
        // any OPEN sheet reloads itself through its LibrarySync hook.
        try { com.lumidrop.x.media.LibrarySync.notifyAppStorageChanged(context, changedPath) } catch (e: Exception) { /* never break the landing path */ }
    }

    // ── GOAT-PASS 15 (§1/§2 — the prompt-completion arrival pipeline) ─────────
    // The single-threaded executor that owns every POST-ARRIVAL side effect of
    // a received file: the MediaProvider scan, the coalesced storage-change
    // nudge, and the batch-completion notification. These are genuine
    // consequences of the landing, but they are NOT part of the transfer
    // contract — the sender's next file is gated on THIS endpoint's HTTP
    // response, so every millisecond of scan/bind/index work that used to run
    // between the last byte and the response was pure, per-file dead time on
    // the wire (the reported 3–4 s "stuck at 100%" after large files, and a
    // meaningful share of a folder's per-child overhead). The response now
    // goes out the instant the file is durably received, registered and the
    // row Completed; the arrival work runs here, serialized (one scan at a
    // time — the MediaProvider is a single service anyway) and off the
    // critical path.
    private val arrivalExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "ldx-arrival").apply { isDaemon = true; try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND) } catch (_: Exception) {} }
    }

    /** GOAT-PASS 15 (§1): the COALESCED storage-change nudge for burst
     *  arrivals. A folder of N children used to fire N search-index
     *  invalidations + N LibrarySync fan-outs back-to-back (each kicking a
     *  background storage walk) while the next child was already streaming —
     *  pure CPU/IO churn that grew with folder size and competed with the
     *  very disk the receive pipeline needed. The trailing window collapses
     *  a burst into ONE rebuild, ~600 ms after the last arrival; the files
     *  version itself (the SSE "something changed" signal) is bumped
     *  immediately per landing, so the web views stay exactly as live as
     *  before — only the expensive rebuild is coalesced. */
    private val arrivalNudgePending = java.util.concurrent.atomic.AtomicBoolean(false)
    private val arrivalNudgeLastPath = java.util.concurrent.atomic.AtomicReference<String?>(null)
    private val arrivalNudgeHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private fun scheduleArrivalNudge(changedPath: String?) {
        arrivalNudgeLastPath.set(changedPath)
        if (arrivalNudgePending.getAndSet(true)) return   // a trailing pass is already armed
        arrivalNudgeHandler.postDelayed({
            arrivalNudgePending.set(false)
            // Runs on the main looper; the work itself is the same cheap
            // invalidation call bumpFilesVersion makes, safe from any thread.
            try {
                try { SearchIndex.invalidateAndRewarm() } catch (e: Exception) { /* best-effort */ }
                try { com.lumidrop.x.media.LibrarySync.notifyAppStorageChanged(context, arrivalNudgeLastPath.get()) } catch (e: Exception) { /* never break the landing path */ }
            } catch (e: Exception) { /* never break the landing path */ }
        }, 600L)
    }

    /** GOAT-PASS 15 (§2): the per-landing version bump WITHOUT the eager
     *  rebuild — cheap (an atomic increment + one SSE nudge), immediate, and
     *  paired with the coalesced nudge above for the expensive part. */
    private fun bumpFilesVersionArrival(changedPath: String?) {
        filesVersion.incrementAndGet(); sseNudge()
        scheduleArrivalNudge(changedPath)
    }

    /** GOAT-PASS 15 (§2): queues one file's post-arrival side effects onto
     *  the arrival executor — the MediaProvider scan (its service bind can
     *  cost real time on busy devices and has no place between the last byte
     *  and the response) and the coalesced storage nudge. */
    private fun deferArrivalSideEffects(scanPath: String, changedPath: String?) {
        try {
            arrivalExec.execute {
                try { MediaScannerConnection.scanFile(context, arrayOf(scanPath), null, null) }
                catch (e: Exception) { /* best-effort — the file is already safely on disk */ }
                bumpFilesVersionArrival(changedPath)
            }
        } catch (e: Exception) {
            // Executor rejected (shutdown): fall back to the synchronous form
            // rather than losing the scan entirely.
            try { MediaScannerConnection.scanFile(context, arrayOf(scanPath), null, null) } catch (e2: Exception) {}
            bumpFilesVersionArrival(changedPath)
        }
    }

    /** GOAT-PASS 9 (§5 — live synchronization): the OBSERVER-driven entry —
     *  the MediaStore/receive-root watchers report EXTERNAL changes (camera,
     *  downloads, other apps). Open web views refetch their data over SSE
     *  exactly as they do for app-driven changes. */
    fun notifyExternalLibraryChanged() {
        filesVersion.incrementAndGet(); sseNudge()
        try { SearchIndex.invalidateAndRewarm() } catch (e: Exception) { /* best-effort — the TTL still bounds staleness */ }
    }
    private val IN_FLIGHT_TTL = 5 * 60_000L  // 5 minutes — covers slow / large downloads

    // ── Pass-28 (#5): the host-UI text-message QUEUE is retired in full ─────────
    // (the old MAX_PENDING_TEXT_MESSAGES / TEXT_MESSAGE_TTL_MS bounds and the
    // PendingTextMessage queue below). No Copy/Remove dialog may ever enqueue
    // over whatever the receiver is doing. Incoming text — native OR web (see
    // apiTextSend, Pass-28 #3C) — is governed by the incoming sheet's
    // acceptance flow, and delivers exactly two real effects at acceptance
    // (Pass-28 #3A/#3B): the exactly-once clipboard copy (individually, or as
    // one combined entry for Accept All) plus the quiet confirmation Toast
    // (see precreateNativePushDirs and deliverTextToClipboard).

    // Devices explicitly kicked by the host. Blocked at the very top of serve()
    // — every request from a kicked IP gets a 403, with no exemptions (not even
    // /api/login). Intentionally in-memory only: "stays kicked until server
    // restart" per spec, so this must NOT survive a restart.
    val kickedIPs = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    // ── Pass-18 (session-scoped unblock) ─────────────────────────────────────
    // The name each blocked client was known by at the moment of its kick —
    // kickDevice() clears the live session records (connectedNames/UAs) as
    // part of the session termination, so WITHOUT this snapshot the unblock
    // sheet could only ever list raw IPs. Same lifecycle as kickedIPs
    // (in-memory only, reborn empty on every restart — a restart unblocks
    // everything, exactly as before).
    val kickedNames = ConcurrentHashMap<String, String>()

    // ── GOAT-PASS 12 (§9): password failed-attempt limiting ───────────────────
    // Progressive, server-enforced protection for /api/login, keyed by the
    // SAME client identity every other gate in this server uses (the
    // connection's IP — see kickedIPs/connRequests above). Two stages:
    //   1. 5 consecutive wrong passwords → a 30 s temporary lockout
    //      (HTTP 429 with the remaining wait; even a CORRECT password is
    //      refused during the wait — refreshing, reopening the modal,
    //      reconnecting or parallel requests cannot bypass it, because the
    //      verdict and the counter update are one atomic step inside
    //      PwdGate);
    //   2. after the wait, 5 MORE wrong passwords → automatic session block
    //      through the EXISTING mechanism (kickDevice — the client appears
    //      in the same Blocked-devices sheet and the same Unblock action
    //      releases it; no second blocking system anywhere).
    // A correct password resets the client's failure state completely.
    // In-memory and session-scoped, exactly like kickedIPs: a server
    // restart clears it (the pre-existing session model), and the unblock
    // path clears an individual client's counter so an unblocked client
    // genuinely starts fresh. Pure policy in PwdGate.kt (unit-tested).
    // ── GOAT-PASS 13 (§4): the thresholds are named constants so the login
    // response's "attempts remaining" arithmetic (below, in apiLogin) can
    // never drift from the policy actually enforced here.
    private val PWD_FAILS_BEFORE_LOCK = 5
    private val PWD_FAILS_BEFORE_BLOCK = 10
    private val pwdGate = PwdGate(
        maxFailsBeforeLock = PWD_FAILS_BEFORE_LOCK,
        maxFailsBeforeBlock = PWD_FAILS_BEFORE_BLOCK,
    )

    // Per-device mode ("transfer" | "full") and optional custom display name,
    // keyed by IP. Read on the hot 250ms /api/qstate poll, so these stay as
    // plain in-memory maps — DeviceStore (SharedPreferences-backed) is only
    // touched on writes, via persistDevices() below, never on every request.
    val deviceModes = ConcurrentHashMap<String, String>()
    val deviceNames = ConcurrentHashMap<String, String>()

    fun getDeviceMode(ip: String): String = deviceModes[ip] ?: SettingsManager.getDefaultClientMode(context)
    // GOAT (Phase 1, item 6): no persistDevices() call here -- a mode
    // override is session-scoped only (rules c/d/e), so changing it never
    // touches disk. Contrast setDeviceName just below, which still
    // persists, since custom names ARE meant to be durable.
    // Phase 1 of 10: bumps stateVersion so "v" stays a complete change
    // signal for every consumer of the unified state payload.
    // Phase 2 of 10: the override entry is ALWAYS written (even when it
    // equals the current effective mode — an explicit override must PIN the
    // session against a later change of the global default, which is the
    // §9 isolation rule), but the version bump + transition log fire only
    // when the EFFECTIVE mode actually changed: rapid toggle-tap settling
    // on the same value produces no event storm and no gratuitous QS
    // refetch on clients. The write itself is a single ConcurrentHashMap
    // put — last-write-wins under rapid interaction, deterministic.
    fun setDeviceMode(ip: String, mode: String) {
        val m = if (mode == "full") "full" else "transfer"
        val before = getDeviceMode(ip)
        deviceModes[ip] = m
        if (before != m) {
            stateVersion.incrementAndGet()
        }
    }
    // ── Web → host capability channel (Phase 2 of 10) ──────────────────────────
    // The Web Client reports session capabilities here — currently: whether
    // a Quick-Play-capable external player is configured for the client's
    // current OS. Applied to the REQUESTING socket's session only (the ip
    // comes from the connection itself, never from the body), so one client
    // can never flip another's capability. Stored as a presence map ("in
    // the map" = capable); a "false" report removes the entry entirely, so
    // the sweep/kick cleanup paths are trivial and absence always means
    // "not capable". Idempotent: a no-change report does NOT bump the state
    // version — a real change does, which is what makes both the client's
    // next SSE payload and the host's Connected Devices card react
    // immediately, with no refresh anywhere.
    val clientCaps = ConcurrentHashMap<String, Boolean>()   // ip → external-player ready
    // Custom name (set by the host) > User-Agent-derived label (e.g. "Android Chrome") >
    // reverse-DNS-resolved hostname > raw IP. Mirrors MainActivity's own device-row
    // logic exactly, so a name shown in the Transfers tab always matches what the
    // Connected Devices card already calls that same IP.
    fun getDeviceDisplayName(ip: String): String {
        deviceNames[ip]?.let { if (it.isNotBlank()) return it }
        connectedUAs[ip]?.takeIf { it.isNotBlank() }?.let { return parseDeviceLabel(it) }
        connectedNames[ip]?.let { if (it != ip) return it }
        return ip
    }
    fun setDeviceName(ip: String, name: String) {
        if (name.isBlank()) deviceNames.remove(ip) else deviceNames[ip] = name.trim()
        persistDevices()
        stateVersion.incrementAndGet()
    }
    private fun persistDevices() {
        val merged = HashMap<String, Pair<String, String?>>()
        for (ip in (deviceModes.keys + deviceNames.keys)) merged[ip] = Pair(deviceModes[ip] ?: "transfer", deviceNames[ip])
        com.lumidrop.x.settings.DeviceStore.saveAll(context, merged)
    }

    fun kickDevice(ip: String) {
        // Pass-18: capture the display name BEFORE the session records below
        // are torn down — the unblock sheet lists this client later, when the
        // live UA/name maps no longer know it. Falls back to the persisted
        // custom name at unblock-sheet time too (getDeviceDisplayName still
        // consults DeviceStore-backed deviceNames, which a kick deliberately
        // keeps — a custom name is a durable setting, not session state).
        try { kickedNames[ip] = getDeviceDisplayName(ip) } catch (e: Exception) { kickedNames[ip] = ip }
        kickedIPs.add(ip)
        connectedIPs.remove(ip)
        connectedNames.remove(ip)
        // Phase 2 of 10: the kicked session's capability report and captured
        // User-Agent go with it — a kick is a session TERMINATION (the
        // client-side mirror clears everything too), not a mere visibility
        // toggle, so no session-scoped fragment may outlive it.
        clientCaps.remove(ip)
        connectedUAs.remove(ip)
        deviceModes.remove(ip)
        pendingUploads.values.removeAll { it.fromIp == ip }
        refreshPendingNotif()
        // Phase 1 of 10 (kick = real disconnect, not just "refuse future
        // requests"): the pre-Phase-1 kick left the kicked client's LIVE
        // SSE event stream open (it was established before the kick, and
        // serve() only rejects NEW requests), kept its in-flight transfers
        // streaming to completion, and left its incoming native pushes
        // running. A kicked device's stream is closed so it stops receiving
        // state, its transfers are cancelled (and their slots released —
        // requestCancel's immediate finalize plus the worker teardown),
        // and its unfinished pushes are torn down the same way a
        // whole-push cancel would.
        closeSseFor(ip)
        TransferManager.cancelAllFor(ip)
        val kickedPushIds = pendingNativePushes.values.filter { it.fromIp == ip }.map { it.id }
        kickedPushIds.forEach { cancelWholePush(it) }
        pendingNativePushes.entries.removeAll { it.value.fromIp == ip }
        // Pass-28 (#3A/#3B): a kicked device's delivered-text keys die with its
        // pushes — same lifetime rule as the stale sweep's removal loop.
        // (Collected BEFORE the removeAll above — the map is empty by then.)
        kickedPushIds.forEach { purgeDeliveredTextKeys(it) }
        // Next Release Pass (§12.6): a kicked client's bridge tokens die with
        // its session — any stream a player still holds dies on its next
        // range request.
        // GOAT-PASS 9 (Android 5–7): removeIf is a Java-8 default method the
        // platform collections only carry from API 24 — it threw
        // NoSuchMethodError on Android 5/6 mid-kick. Kotlin's removeAll
        // predicate extension walks a plain Iterator instead (same note as
        // the pendingUploads sweep above); identical semantics.
        bridgeTokens.entries.removeAll { it.value.ip == ip }
        stateVersion.incrementAndGet()
    }

    // ── Pass-18 (session-scoped unblock) ──────────────────────────────────────
    /** Re-admits a previously kicked client DURING this server session. The
     *  whole kick machinery stays exactly as it was — serve()'s 403 gate,
     *  stream close, transfer cancels, token revocation all happened at kick
     *  time and are NOT undone retroactively (the client's dead transfers
     *  stay cancelled; its page reconnects and rebuilds its own state, the
     *  same way a page refresh after any long disconnect does). What changes
     *  is purely future-facing: requests from this IP reach the server
     *  again. A server restart still empties BOTH maps (the pre-existing
     *  "restart unblocks everything" behavior is untouched). */
    fun unblockDevice(ip: String) {
        if (!kickedIPs.remove(ip)) return
        kickedNames.remove(ip)
        // ── GOAT-PASS 12 (§9): an unblocked client starts genuinely fresh — ──
        // its password-failure counter (if the block was password-abuse
        // triggered) does not survive the host's explicit Unblock.
        try { pwdGate.reset(ip) } catch (e: Exception) { /* never block an unblock */ }
        stateVersion.incrementAndGet()
    }

    /** The blocked list for the sheet: ip → best-known label, ordered by
     *  label for a stable, readable list. Kicked-name snapshot first (what
     *  the client was called when the host blocked it), then today's
     *  getDeviceDisplayName (covers a durable custom name set later), then
     *  the raw IP. */
    fun blockedClientsSnapshot(): List<Pair<String, String>> =
        kickedIPs.toList().map { ip ->
            val label = kickedNames[ip]?.takeIf { it.isNotBlank() && it != ip }
                ?: getDeviceDisplayName(ip)
            ip to label
        }.sortedBy { it.second }

    /** Drives the header icon's visibility (SendFragment reads this on
     *  every devices-UI refresh — appears only while ≥1 client is blocked,
     *  disappears the instant the last one is unblocked). */
    fun hasBlockedClients(): Boolean = kickedIPs.isNotEmpty()

    // Session tokens for password protection: token → expiry (ms)
    private val validSessions = ConcurrentHashMap<String, Long>()

    // ── Connection Confirmation ─────────────────────────────────────────────
    // Independent of the password session above — the two features can be on
    // together, on separately, or both off.
    //
    // Keyed by IP, not a per-browser-session cookie — matching the SAME
    // device identity every other part of this app already uses
    // (DeviceStore, connectedIPs, getDeviceDisplayName). An earlier version
    // of this used a per-session cookie instead, on the theory that IP alone
    // could let one tab's approval silently cover another. In practice that
    // was stricter than wanted and broke a real case a cookie fundamentally
    // can't handle: an external app (e.g. a native video player launched via
    // "Play With") makes its own HTTP requests with no access to the WebUI's
    // cookies at all, so every one of its requests looked like a brand new,
    // unknown connection and re-prompted the host mid-playback. A decision
    // now persists for as long as this server process keeps running — one
    // approval per device per server session, exactly as specified — and
    // resets naturally on restart, since this is in-memory state.
    class ConnRequest(val ip: String, val label: String, val deviceDetail: String, val requestedAt: Long) {
        @Volatile var decision: String = "pending"   // "pending" | "accepted" | "rejected"
    }
    val connRequests = ConcurrentHashMap<String, ConnRequest>()   // ip -> request

    /** Oldest-first list of connection requests still awaiting a host decision. */
    fun pendingConnRequests(): List<Pair<String, ConnRequest>> =
        connRequests.entries.filter { it.value.decision == "pending" }
            .sortedBy { it.value.requestedAt }.map { it.key to it.value }

    fun decideConnRequest(ip: String, accept: Boolean) {
        connRequests[ip]?.decision = if (accept) "accepted" else "rejected"
        if (connRequests.values.none { it.decision == "pending" })
            com.lumidrop.x.service.ServerForegroundService.clearPendingConnNotif(context)
    }

    private fun registerConnRequest(ip: String) {
        // Atomic get-or-create. Phase 2 of 10 (API-floor correctness): this
        // used computeIfAbsent — a Java 8 default method on ConcurrentMap,
        // absent from Android's API 21-23 platform classes (NoSuchMethodError
        // at runtime, the exact hazard class as removeIf — see the note in
        // sweepStalePendingUploads). putIfAbsent is the Java 5 ConcurrentMap
        // primitive with the same at-most-once insert semantics: two
        // near-simultaneous first-touch requests from the same IP (the
        // handful of requests a browser fires loading a page for the very
        // first time) can never each create their own separate entry — the
        // exact race that used to cause duplicate confirmation dialogs for
        // one connection attempt back when this was keyed by a cookie token
        // instead of the IP itself. The ConnRequest construction is a cheap
        // pure read (UA + display-name lookups), so building one that loses
        // the insert race is free.
        val ua = connectedUAs[ip]
        val deviceDetail = if (ua.isNullOrBlank()) "Unknown device/browser" else parseDeviceLabel(ua)
        val fresh = ConnRequest(ip, getDeviceDisplayName(ip), deviceDetail, System.currentTimeMillis())
        if (connRequests.putIfAbsent(ip, fresh) != null) return

        // Bounded like every other in-memory history in this file — a
        // long-running server on a busy network shouldn't accumulate
        // decided requests forever. Only ever trims already-decided
        // entries; a still-pending request is never silently dropped.
        if (connRequests.size > 200) {
            connRequests.entries.filter { it.value.decision != "pending" }
                .sortedBy { it.value.requestedAt }
                .take(connRequests.size - 200)
                .forEach { connRequests.remove(it.key) }
        }
        com.lumidrop.x.service.ServerForegroundService.notifyPendingConnRequest(
            context, connRequests.values.count { it.decision == "pending" })
    }

    // Single-thread executor for async hostname resolution (non-blocking)
    private val nameResolver  = java.util.concurrent.Executors.newSingleThreadExecutor()
    // Phase: transfer state sync — short-lived, infrequent, fire-and-forget
    // outbound control calls (pause/resume/cancel propagation to a peer).
    // Separate from nameResolver (a different concern, and single-threaded)
    // so a burst of "Pause All" on several native transfers at once doesn't
    // queue behind unrelated hostname lookups or each other.
    private val controlExecutor = java.util.concurrent.Executors.newCachedThreadPool()
    // State version — incremented on every QS or transfer-mode change so clients
    // can detect updates with a single cheap /api/qstate poll instead of fetching
    // the full queue every time.  Starts at 1 so clients initialised to 0 always
    // fetch the full queue on their very first poll.
    val stateVersion = AtomicInteger(1)

    init {
        // GOAT (Phase 1, item 6): only custom device NAMES are restored
        // here now -- those are genuinely durable. Per-device MODE
        // overrides are deliberately NOT restored: rule (e) of the
        // session-based Web client access mode spec requires an override
        // to reset back to the live persistent default on every server
        // restart (and again on that client's own next reconnect -- see
        // the connectedIPs staleness check in serve() above). deviceModes
        // therefore always starts empty here, same spirit as kickedIPs
        // just below -- DeviceStore's persisted "mode" field (still
        // written by persistDevices() so its JSON schema stays uniform
        // with what setDeviceName touches) is intentionally never read
        // back into deviceModes.
        for ((ip, entry) in com.lumidrop.x.settings.DeviceStore.loadAll(context)) {
            entry.second?.let { deviceNames[ip] = it }
        }

        // ── Pass-14 (P4 — instant previewer): engine mutations now nudge ────
        // the SSE ticker directly, so every transfer lifecycle change a web
        // client can see (a host-side Cancel tap, a completion, a reconnect
        // flip) is pushed within milliseconds rather than waiting out the
        // ticker's 250ms quantum. The nudge coalesces bursts and is a no-op
        // before the ticker ever starts (no event streams yet — nothing to
        // push to).
        TransferManager.onStateNudge = { sseNudge() }
        // ── Pass-15 (browser-side download cancel honesty): the engine's ──
        // deliberate-cancel probe for aborted downloads asks exactly one
        // question — "is this client's event stream still live?" — and this
        // is the authoritative answer (a page whose network dropped loses
        // its stream within a tick or two; a page that cancelled its own
        // download in the browser UI keeps a perfectly healthy one).
        // ── Pass-17 (browser-side download cancel, VERDICT regime): the ──
        // engine no longer asks the passive "is the SSE stream attached?"
        // question — on a genuine network drop a dead-but-undrained stream
        // still reads "live" until the 30-second back-pressure stall-drop
        // notices, so every interrupted download was misread as a DELIBERATE
        // cancel and tombstoned against its own resume (the reported
        // "connection restored but nothing resumes"). The new hook runs an
        // ACTIVE challenge: the page is handed a nonce over its SSE stream
        // (and echoed via /api/qstate for poll-only clients) and must POST
        // /api/alive within the window — only a page whose network is GONE
        // cannot answer. See probePeerAlive below.
        TransferManager.browserLivenessProber = { ip -> probePeerAlive(ip) }
        // ── Pass-18 (sender-side disconnect wording, app→app half) ───────────
        // The SEND twin of the prober above: when a native send stalls, the
        // engine asks NativePushClient "is THIS send's peer provably
        // unreachable RIGHT NOW?" and gets a one-shot /api/ping at the
        // peer's own server for an answer. This is what finally makes the
        // SENDER's row flip to "Auto-Resume on Reconnection" within ~15s of
        // the receiver's Wi-Fi going off (the reported 200 MB @ 16% file
        // whose sender kept reading "Sending…" for minutes) — before this,
        // only the device whose OWN network vanished (the receiver) got an
        // instant state, because the sender's hotspot stays up and nothing
        // local latches. A live-but-slow peer answers the ping and honestly
        // keeps "Sending…". Wired here (next to its browser twin) because a
        // send can only exist once this server is up; harmless to re-run on
        // a restart — the hook is stateless.
        TransferManager.sendLivenessProber = { tid -> com.lumidrop.x.p2p.NativePushClient.isSendPeerAlive(tid) }
        // ── GOAT-PASS 8 (§11 — the receiver-side twin of the SEND prober): ────
        // when a native UPLOAD row (this device is RECEIVING a push) stalls,
        // the engine asks "is THIS row's SENDER provably unreachable right
        // now?" — one tiny GET /api/ping at the sender's own server, whose
        // address rides the push record's control link (registered at
        // stream-start, see registerNativeControlLink). A sender that cannot
        // answer flips the receiving row to the calm "Auto-Resume on
        // Reconnection" presentation within ~9 s of the drop and frees its
        // receiving slot, instead of the old flat 15 s failure — the exact
        // "hotspot host kept showing Receiving… after the client's Wi-Fi went
        // off" half of the reported repro. A live-but-slow sender answers and
        // the row honestly keeps "Receiving…". Stateless, like its twins.
        TransferManager.uploadLivenessProber = { tid -> isNativePushSenderAlive(tid) }
        // ── Pass-17 (durable, instant, deletion-proof History): every ──
        // COMPLETED transfer (files, folders, texts — both directions) is
        // appended to the persistent history log the very moment it
        // completes, regardless of which tab is open; the one-time
        // migration below backfills files received before this pass.
        TransferManager.onTerminalRecord = { t ->
            try { com.lumidrop.x.transfer.HistoryStore.record(context, t) } catch (e: Exception) { /* never break completion */ }
            // ── Pass-33 (#4): provenance for the Received tab — every ─────────
            // completed RECEIVE with a real file on disk is registered the
            // moment it completes (the same single hook that feeds History),
            // so the tab renders and deletes only what the app actually
            // received, never a file an external file manager dropped into
            // the receive directory. Text messages have no file; sends are
            // not the Received tab's business.
            try {
                if (t.direction == TransferManager.Direction.UPLOAD && !t.filePath.isNullOrBlank())
                    com.lumidrop.x.transfer.ReceivedRegistry.record(context, t.filePath!!)
            } catch (e: Exception) { /* never break completion */ }
        }
        // Pass-33 (#4): eager load at server start, OFF the caller's thread —
        // the first load performs the one-time grandfather snapshot (a full
        // receive-directory walk on a first launch), which must never land
        // on whatever thread starts the server. The registry's own lock
        // makes this race-free with any concurrent record()/read().
        Thread({
            try { com.lumidrop.x.transfer.ReceivedRegistry.ensureLoaded(context) }
            catch (e: Exception) { /* provenance degrades to hiding rows, never to crashing */ }
        }, "recv-registry-load").apply { isDaemon = true }.start()
        com.lumidrop.x.transfer.HistoryStore.kickMigration(context)
        // ── Pass-14 (resumable receives, requirement 2): restore the ─────────
        // "Waiting for reconnection" cards for interrupted receives whose
        // partial data survived this app's own restart (and adopt orphaned
        // partials from before the registry existed). Async by design — the
        // server must never block its constructor on disk I/O.
        restoreResumableReceivesAsync()

        // ── Fix NanoHTTPD temp-file location on Android ───────────────────────
        // NanoHTTPD's DefaultTempFileManager uses java.io.tmpdir which resolves to
        // /data/local/tmp on many Android builds — a path that is NOT writable by
        // normal app processes.  Routing uploads to the app's own cache directory
        // fixes the HTTP 400 "Body parse failed" crash that affects ALL file sizes.
        setTempFileManagerFactory {
            object : NanoHTTPD.TempFileManager {
                private val tmpDir  = File(context.cacheDir, "wsx_uploads").also { it.mkdirs() }
                private val created = mutableListOf<NanoHTTPD.TempFile>()
                override fun clear() { created.forEach { runCatching { it.delete() } }; created.clear() }
                override fun createTempFile(filename_hint: String?): NanoHTTPD.TempFile {
                    val f = File.createTempFile("upload_", "_tmp", tmpDir)
                    return object : NanoHTTPD.TempFile {
                        override fun open(): OutputStream = FileOutputStream(f)
                        override fun delete() { f.delete() }
                        override fun getName(): String = f.absolutePath
                    }.also { created.add(it) }
                }
            }
        }

        // Phase 2 of 10: the ticker is started lazily by the FIRST request
        // (see serve()), not here — Kotlin initializes properties in
        // declaration order and sseExec/sseClients are declared further down
        // the class body, so an init-block call would reach them before
        // their initializers ran. Lazy-on-first-request is also exactly the
        // right semantics: no session exists before the first request
        // arrives, so there is nothing for the sweep to do until then, and
        // the ticker (which then runs for the server's whole life —
        // including with zero SSE streams, since the sweep is its job too
        // now) is guaranteed up before any session can possibly age out.
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    override fun serve(session: IHTTPSession): Response {
        connectedClients.incrementAndGet()
        val ip = try { session.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }

        // Kicked devices are blocked completely — no exemptions, not even
        // /api/login — until the host restarts the server.
        if (ip in kickedIPs) {
            connectedClients.decrementAndGet()
            return newFixedLengthResponse(Status.FORBIDDEN, "application/json",
                JSONObject().put("error", "This device was disconnected by the host").toString())
        }

        // Phase 17 — independent Mobile Data / Hotspot / LAN toggles.
        // NanoHTTPD binds every interface at once (this class's constructor
        // only takes a port), so per-interface "off" is enforced here at the
        // request layer instead of by refusing to bind a socket. Deliberately
        // fail-open: this can only additionally BLOCK a request it can
        // confidently attribute to one specific, currently-disabled
        // interface's own IP — any ambiguity (no Host header, header isn't a
        // bare IP, IP doesn't match a currently-detected interface at all)
        // falls through to normal handling. A missed block is a minor
        // settings inconsistency; a false-positive block would silently
        // break the app's core sharing feature, which is the far worse
        // failure mode to risk here.
        blockedByInterfaceToggle(session)?.let { return it }

        // Requests to the native P2P namespace come from another LumiDrop X
        // app's own embedded server acting as an HTTP client (see
        // NativePushClient), never from a browser. Tracking those here
        // would make a native peer show up in the Web Connected Clients
        // list too — a phantom "browser" entry for a device that's already
        // correctly shown in Nearby Devices via NativeDiscovery's own
        // separate UDP-based tracking. The two device lists are otherwise
        // completely independent; excluding by URI namespace here is the
        // one place they need to agree, and it holds up across reconnects
        // and repeated discovery since it's a property of the request
        // itself, not any per-IP state that could drift over a long
        // session.
        // ── Pass-20 (/api/ping was the ghost-browser leak) ─────────────────────
        // The liveness-probe route /api/ping is ALSO native-only traffic:
        // NativeDiscovery's expiry probes and NativePushClient's
        // send-liveness probes both hit it on the PEER's server every few
        // seconds while a transfer is stalled — and each hit used to stamp
        // connectedIPs, so a native peer (often the HOST of the other
        // device, probing this one) appeared as a live BROWSER session on
        // this device's Connected Devices card, right beside its own
        // native card: the same device represented twice, once under the
        // wrong connection type (the reported "native app device shown as
        // a browser client while its native card is already displayed",
        // plus the rare "browser client with nobody connected"). The web
        // client NEVER calls /api/ping (its liveness answer is
        // /api/alive, which stays tracked — it is genuine browser
        // evidence), so excluding the probe route can never hide a real
        // browser session; it only stops probe traffic from minting one.
        if (!session.uri.startsWith("/api/native/") && session.uri != "/api/ping") {
            // Phase 2 of 10: the session-sweep ticker starts with the first
            // tracked request and runs for the rest of this server
            // instance's life (stopped by the stop() override). One volatile
            // read when already started; see the init block's comment for
            // why this is lazy and not constructor-time.
            ensureSseTicker()
            // GOAT (Phase 1, item 6, rule e) + Phase 2 of 10: a per-device
            // mode override is session-scoped, so it must drop when the
            // session ENDS. Pre-Phase-2 used the same 30s window as
            // visibility here — but a 30s activity gap is exactly what the
            // browser's own SSE reconnect envelope produces (host-side
            // stall-drop at 30s + EventSource backoff) with the page still
            // open, which silently reverted a live client's mode (the
            // "wrong mode in Connected Devices" root cause). The reset now
            // uses SESSION_GRACE_MS (90s): a returning client inside the
            // grace window RESUMES its session (override + capability
            // report intact); only a genuinely departed client — no
            // requests AND no live stream for the full grace window, the
            // same condition sweepSessions() reaps by — reads as a fresh
            // session. This block and the sweep now agree on one
            // definition of "session ended" (belt-and-suspenders: the
            // sweep usually gets there first on its own clock).
            val lastSeen = connectedIPs[ip]
            if (lastSeen == null) {
                // Pass-7: the pass-6 Auto-Send "new session" trigger is gone
                // with the engine itself — Auto-Send is now the plain
                // mirror of Auto-Accept (selection-time instant send, no
                // background machinery), so there is nothing to hook here.
                // Pass-8 (§3/§11 — parked Auto-Send meets a browser): a
                // batch parked because NOTHING was connected fires now, for
                // exactly this client, as a targeted Quick-Share send — the
                // browser-side twin of the native-peer drain in
                // NativePushClient.sendOrQueueForPeer's discovery listener.
                // Without it, a batch parked with only a browser around sat
                // as "Waiting for device" rows forever (the receiver's own
                // Auto-Accept never even got the chance to decide).
                if (ip != "unknown") {
                    com.lumidrop.x.p2p.NativePushClient.drainWaitingToBrowser(this, ip)
                }
            }
            if (!hasLiveSse(ip) && (lastSeen == null || System.currentTimeMillis() - lastSeen >= SESSION_GRACE_MS)) {
                if (lastSeen != null && deviceModes.remove(ip) != null) {
                    // Phase 8: this in-band mode-override reset is
                    // payload-visible (the client's "mode" reverts) — wake
                    // the SSE dirty gate for it.
                    sessionEpoch.incrementAndGet()
                }
            }
            connectedIPs[ip] = System.currentTimeMillis()
            // Capture User-Agent for device-label display in the host UI.
            // Phase 8: a NEW (or changed) UA is payload-visible — the client's
            // own deviceLabel flips from blank to named — so it bumps the
            // session epoch; a same-value rewrite on every request does NOT,
            // or every single request from any client would wake every SSE
            // rebuild on the next tick.
            session.headers["user-agent"]?.takeIf { it.isNotBlank() }?.let { ua ->
                if (connectedUAs[ip] != ua) {
                    connectedUAs[ip] = ua
                    sessionEpoch.incrementAndGet()
                }
            }
            // Resolve hostname asynchronously on first encounter
            if (ip != "unknown" && !connectedNames.containsKey(ip)) {
                nameResolver.submit {
                    try {
                        val resolved = java.net.InetAddress.getByName(ip).canonicalHostName
                        connectedNames[ip] = if (resolved != ip) resolved else ip
                    } catch (e: Exception) { connectedNames[ip] = ip }
                }
            }
        }

        return try {
            val r = route(session)
            r.addHeader("Access-Control-Allow-Origin", "*")
            r.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            r.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, Cookie")
            r.addHeader("Cache-Control", "no-store")
            r
        } catch (e: Exception) {
            newFixedLengthResponse(Status.INTERNAL_ERROR, "text/plain", "Error: ${e.message}")
        } finally {
            connectedClients.decrementAndGet()
        }
    }

    /** Returns a FORBIDDEN response iff this request can be attributed to a
     *  currently-DISABLED sharing interface; returns null (meaning "let it
     *  through") whenever attribution is ambiguous — the fail-open halves of
     *  this gate are deliberate (a false-positive block would silently break
     *  core sharing in edge-case network setups) and the server-wide stop
     *  that Settings performs when local sharing is off is the hard
     *  backstop behind it.
     *
     *  Pass-8 (§9 — real boundaries) upgrades over the pass-7 gate:
     *   1. Attribution now also works when the Host header is a hostname or
     *      missing: a request whose REMOTE address sits in the same /24 as a
     *      disabled local interface is refused. LAN (192.168.1.x) and
     *      hotspot (192.168.43.x) subnets do not overlap on any real device,
     *      so this cannot cross-block an enabled mode.
     *   2. Mobile Data: Internet sharing is removed entirely — a direct hit
     *      on the carrier address is refused with an honest message. CGNAT
     *      makes such hits practically impossible from the internet anyway;
     *      the address is never advertised anywhere. */
    private fun blockedByInterfaceToggle(session: IHTTPSession): Response? {
        try {
            val rawRemote = try { session.remoteIpAddress ?: "" } catch (e: Exception) { "" }
            // Purely local requests (this device probing its own server)
            // are exempt — they arrive on loopback.
            if (rawRemote == "127.0.0.1" || rawRemote == "::1" || rawRemote == "localhost") return null

            val hostHeader = session.headers["host"]
            val requestIp = hostHeader?.substringBefore(":")?.trim() ?: ""
            if (requestIp.isNotEmpty()) {
                val match = NetworkUtils.getAllInterfaces(context).firstOrNull { it.ip == requestIp }
                if (match != null && NetworkUtils.getEnabledInterfaces(context).none { it.ip == requestIp }) {
                    val msg = if (match.kind == NetworkUtils.InterfaceKind.MOBILE_DATA)
                        "Mobile data sharing is not supported — connect over Wi-Fi or hotspot"
                    else
                        "Sharing over ${match.label} is currently turned off on this device"
                    return newFixedLengthResponse(Status.FORBIDDEN, "application/json",
                        JSONObject().put("error", msg).toString())
                }
            }

            // Subnet attribution for hostname/missing-Host requests: refuse
            // when the peer sits inside a disabled local interface's /24.
            if (rawRemote.isNotEmpty() && !rawRemote.contains(':')) {
                val enabledIps = NetworkUtils.getEnabledInterfaces(context).map { it.ip }.toSet()
                val viaDisabled = NetworkUtils.getAllInterfaces(context).any { iface ->
                    iface.kind != NetworkUtils.InterfaceKind.MOBILE_DATA &&
                        iface.ip !in enabledIps && sameIpv4Slash24(rawRemote, iface.ip)
                }
                if (viaDisabled) {
                    return newFixedLengthResponse(Status.FORBIDDEN, "application/json",
                        JSONObject().put("error", "Local sharing is currently turned off on this device").toString())
                }
            }
            return null
        } catch (e: Exception) {
            return null  // any failure here must never block a legitimate request
        }
    }

    private fun sameIpv4Slash24(a: String, b: String): Boolean {
        val pa = a.split('.'); val pb = b.split('.')
        if (pa.size != 4 || pb.size != 4) return false
        return pa[0] == pb[0] && pa[1] == pb[1] && pa[2] == pb[2]
    }

    /** Pass-8 (§9 — a disabled sharing mode is a REAL boundary): drops every
     *  browser session that arrived through a now-disabled local interface
     *  (subnet-attributed), closing its SSE stream so the page learns
     *  immediately instead of timing out on a silently-dead socket. Persisted
     *  per-device preferences (mode overrides, custom names) survive — a
     *  reconnect once sharing is re-enabled resumes them; only the live
     *  session state goes. */
    fun invalidateSessionsForDisabledModes() {
        val enabledIps = NetworkUtils.getEnabledInterfaces(context).map { it.ip }.toSet()
        val all = NetworkUtils.getAllInterfaces(context)
        var dropped = 0
        for ((ip, _) in connectedIPs.toList()) {
            if (ip == "unknown" || ip.startsWith("127.") || ip == "::1") continue
            if (ip.contains(':')) continue   // IPv6 peers: no subnet heuristic, leave to the Host-header gate
            val viaDisabled = all.any { iface ->
                iface.kind != NetworkUtils.InterfaceKind.MOBILE_DATA &&
                    iface.ip !in enabledIps && sameIpv4Slash24(ip, iface.ip)
            }
            if (viaDisabled) {
                closeSseFor(ip)
                connectedIPs.remove(ip)
                connectedUAs.remove(ip)
                connectedNames.remove(ip)
                clientCaps.remove(ip)
                dropped++
            }
        }
        if (dropped > 0) {
            sessionEpoch.incrementAndGet()
        }
    }

    private fun route(s: IHTTPSession): Response {
        val uri = s.uri.trimEnd('/')
        val m   = s.method

        // CORS preflight
        if (m == Method.OPTIONS) return newFixedLengthResponse(Status.OK, "text/plain", "")
        val isPageOrAsset = uri == "" || uri == "/" || uri == "/index.html" || !uri.startsWith("/api")

        // ── Pass-13 (native-to-native connections never ask for confirmation) ─
        // /api/native/push/* is the NATIVE app-to-app channel: another
        // LumiDrop X install's NativePushClient speaking its own wire protocol
        // (announce → manifest → per-file POST → status/control probes). It is
        // not a browser session and must NEVER trigger the Connection
        // Confirmation modal or the password wall below — the reported defect
        // was exactly that: a native app-to-app connection popping the
        // connection-confirmation dialog on the receiving host. The gate
        // previously blocked EVERY uri except /api/connstate, so the peer's
        // very first announce registered a ConnRequest and the host saw a
        // "connection request" for a device that is not a web client at all.
        //
        // This is a fix of the underlying condition, not a visual hide: these
        // endpoints simply are not part of the web-client surface the gates
        // exist to guard. Nothing is lost on the protection side — a native
        // push's own approval flow is the receiver's Incoming Files sheet
        // (accept/decline per item, Accept All, auto-accept setting), which is
        // the intended confirmation surface for this connection type, and none
        // of these endpoints expose any browsing surface (they can only push
        // bytes the host must still accept, or control a push already known to
        // both sides). Browser flows keep BOTH gates exactly as before.
        val isNativePeerApi = uri.startsWith("/api/native/")
        // ── FINAL PASS 2 (§7 — /api/ping is native-only traffic too) ──────────
        // The liveness-probe route is the OTHER native pathway: NativeDiscovery's
        // expiry probes, NativePushClient's send-liveness probes and the
        // receiver's upload-liveness probes all hit it on the PEER's server —
        // and the web client NEVER calls it (its liveness answer is /api/alive,
        // which stays fully gated). The Connection Confirmation gate above used
        // to register a ConnRequest for every one of those probes, so a native
        // peer (typically right after a service restart or a minimize/reopen,
        // when the fresh probe is the first request to land) popped the
        // browser-only "Connection request" modal — the long-reported
        // persistent bug. Exempting the probe route at this classification
        // level means a native peer can never enter the browser approval
        // workflow through ANY lifecycle pathway: its entire HTTP surface is
        // /api/native/* + /api/ping. The password gate below is deliberately
        // unchanged — a 401 pong still proves liveness to the prober, and no
        // data is exposed.
        val isNativeProbeApi = uri == "/api/ping"

        // ── Connection Confirmation ─────────────────────────────────────────
        // /api/connstate is always reachable (even while pending/rejected) so
        // the page shell's own JS can poll its status; everything else is
        // refused outright — "no bypasses" — until the host taps Accept.
        // Identity here is IP (see ConnRequest's own comment above for why),
        // so this also correctly covers a request that can't carry the
        // WebUI's cookies at all — an external app like a video player
        // launched via "Play With" — as long as that same IP was already
        // decided.
        if (!isNativePeerApi && !isNativeProbeApi && SettingsManager.isConfirmConn(context) && uri != "/api/connstate") {
            val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
            when (connRequests[ip]?.decision) {
                "accepted" -> { /* fall through to normal routing below */ }
                "rejected" -> return if (isPageOrAsset) serveIndex(s)
                    else noCache(newFixedLengthResponse(Status.FORBIDDEN, "application/json",
                        JSONObject().put("error", "Connection declined by host").put("connState", "rejected").toString()))
                "pending"  -> return if (isPageOrAsset) serveIndex(s)
                    else noCache(newFixedLengthResponse(Status.FORBIDDEN, "application/json",
                        JSONObject().put("error", "Waiting for host approval").put("connState", "pending").toString()))
                else -> {
                    // Never seen this IP before in this server session —
                    // starts pending. registerConnRequest is idempotent
                    // (putIfAbsent), so a burst of near-simultaneous
                    // first-touch requests from the same IP all land here
                    // safely without creating duplicate requests/dialogs.
                    registerConnRequest(ip)
                    return if (isPageOrAsset) serveIndex(s)
                        else noCache(newFixedLengthResponse(Status.FORBIDDEN, "application/json",
                            JSONObject().put("error", "Waiting for host approval").put("connState", "pending").toString()))
                }
            }
        }

        // ── Password protection ──────────────────────────────────────────────
        // Pass-12 (approval-screen root fix): /api/connstate is ALSO exempt
        // here, exactly as it already is from the Connection Confirmation gate
        // above. It is the one endpoint a still-unapproved page shell must be
        // able to poll — it leaks nothing but THIS caller's own pending /
        // accepted / rejected state — and with a password configured it was
        // answering 401 to every poll from the unauthenticated pending page,
        // so the "Waiting for approval" screen never learned the host had
        // tapped Accept until a manual refresh re-ran the whole page flow
        // (cookie now present, page served through the normal path). That is
        // precisely the reported "confirmation screen only disappears after I
        // refresh" bug. Real enforcement is unchanged: every OTHER endpoint
        // still demands the session cookie, and the accepted page itself still
        // requires login before anything meaningful is served.
        if (!isNativePeerApi && uri != "/api/login" && uri != "/api/connstate" && SettingsManager.isPasswordEnabled(context)) {
            val pwd = SettingsManager.getPassword(context)
            if (pwd.isNotEmpty()) {
                val cookieHdr = s.headers["cookie"] ?: ""
                val authenticated = cookieHdr.split(";").any { part ->
                    val kv = part.trim().split("=", limit = 2)
                    kv.size == 2 && kv[0].trim() == "wsx_sess" &&
                    (validSessions[kv[1].trim()] ?: 0L) > System.currentTimeMillis()
                }
                // Next Release Pass (§12.6): an external media player cannot
                // send the browser's session cookie. A live, IP-scoped,
                // single-path bridge token minted by an AUTHENTICATED session
                // stands in for the cookie on /api/media only — nothing else,
                // and only for the token's own file and its short lifetime.
                val btStream = uri == "/api/media" && bridgeTokenValidFor(s)
                if (!authenticated && !btStream) {
                    // Serve login page for browser roots; 401 for API calls
                    return if (isPageOrAsset)
                        serveLoginPage()
                    else
                        newFixedLengthResponse(Status.UNAUTHORIZED, "application/json",
                            JSONObject().put("error", "Unauthorized").toString())
                }
            }
        }

        // ── Final Release (§13): server-side authorization ──────────────────
        // (1) Read-only Files tab for browser clients: rename/delete are
        //     rejected at the API layer for EVERY HTTP caller, whatever the
        //     UI shows — crafted requests included. The host app manages its
        //     own storage natively and never goes through these routes, so
        //     nothing legitimate is lost.
        // (2) Strict transfer-mode enforcement: a client the host placed in
        //     Transfer Mode may transfer (upload, QS receive, its own
        //     tracked downloads) but must not browse or mine the host's file
        //     tree, storage, stats, apps, or media listings, and must not
        //     read files through the untracked media/thumb/zip surfaces.
        //     Mode is host-owned (setDeviceMode is only called from the host
        //     app's device card) and read live here, so a legitimate mode
        //     flip applies on the very next request — no caching to stale.
        val guardIp = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        if (m == Method.POST && (uri == "/api/files/delete" || uri == "/api/files/rename")) {
            return newFixedLengthResponse(Status.FORBIDDEN, "application/json",
                JSONObject().put("error", "Files are read-only for web clients").toString())
        }
        if (getDeviceMode(guardIp) != "full") {
            val browseBlocked = uri == "/api/files" || uri == "/api/storage" || uri == "/api/stats" ||
                uri == "/api/media/images" || uri == "/api/media/videos" || uri == "/api/media/music" ||
                uri == "/api/media/docs" || uri == "/api/apps/installed" || uri == "/api/apps/download" ||
                uri == "/api/media" || uri == "/api/thumb" || uri == "/api/zip-stream" ||
                uri == "/api/zip-stream/select" ||
                uri == "/api/search"   // GOAT-PASS 4 (§2): whole-storage search browses storage — same gate
            if (browseBlocked) {
                return newFixedLengthResponse(Status.FORBIDDEN, "application/json",
                    JSONObject().put("error", "Not available in Transfer Mode").toString())
            }
        }

        return when {
            // ── Auth ─────────────────────────────────────────────────────────
            m == Method.POST && uri == "/api/login"          -> apiLogin(s)

            // ── Static / PWA ──────────────────────────────────────────────────
            uri == "" || uri == "/index.html"                -> serveIndex(s)
            uri == "/manifest.json"                          -> apiManifest()
            uri == "/icon.png"                               -> apiIcon()
            // ── Pass-32 (#7): the bundled ES5 PDF.js engine for the web ──────
            // preview's embedded PDF renderer (legacy iOS Safari can't render
            // PDFs in an iframe, so the Document Viewer paints pages to a
            // canvas with this build there). Same unauthenticated static class
            // as the app shell itself — it is viewer ENGINE code only, carries
            // no file data, and respects every permission the shell does.
            uri == "/pdf.min.js"                             -> asset("pdf.min.js", "application/javascript; charset=utf-8")
            uri == "/pdf.worker.min.js"                      -> asset("pdf.worker.min.js", "application/javascript; charset=utf-8")

            // ── Quick state (cheap version+count+mode poll — replaces two separate polls) ──
            m == Method.GET  && uri == "/api/qstate"         -> apiQState(s)
            m == Method.GET  && uri == "/api/events"         -> apiEvents(s)
            m == Method.POST && uri == "/api/client-transfer/cancel" -> apiClientTransferCancel(s)
            // Pass-17: the liveness ACK endpoint (see probePeerAlive) — answers
            // to a challenge nonce carried in the "pn" state field. No body,
            // no side effects beyond recording the answer.
            m == Method.POST && uri == "/api/alive"          -> apiAlive(s)
            // Pass-17: the ultra-cheap liveness PROBE target for native peers
            // (NativeDiscovery's fast expiry — "is this device's app still
            // alive?"). Deliberately unauthenticated: ANY HTTP answer from this
            // port proves the process is running, which is the entire
            // question — a 401 from a password-protected host is still proof
            // of life.
            m == Method.GET  && uri == "/api/ping"           -> apiPing()
            // Phase 7 of 10 (iPad deep compatibility): the legacy pump's
            // heartbeat + authoritative state channel — see apiClientHandoffSync.
            m == Method.GET  && uri == "/api/client/handoff-sync"  -> apiClientHandoffSync(s)
            // Phase 2 of 10: web → host capability report (Quick-Play external
            // player availability). Applies to the requesting session only.
            m == Method.POST && uri == "/api/client/capability" -> apiClientCapability(s)

            // ── WebUI customization (host-controlled — Additional Phase before Phase 10) ──
            m == Method.GET  && uri == "/api/webui-config"   -> apiWebUiConfig(s)

            // ── External-player bridge (Next Release Pass §12) ──────────────────
            // Windows external players stream the file DIRECTLY from this host.
            // /api/bridge/launch mints a short-lived, path-scoped media token
            // (players can't use the browser session's cookies); /api/bridge/
            // verify lets the launch UI and the one-time Windows bridge confirm
            // a launch honestly; /api/bridge/installer serves the one-click,
            // .vbs-free, registry-free-by-hand bridge setup.
            m == Method.POST && uri == "/api/bridge/launch"   -> apiBridgeLaunch(s)
            m == Method.GET  && uri == "/api/bridge/verify"   -> apiBridgeVerify(s)
            m == Method.GET  && uri == "/api/bridge/installer" -> apiBridgeInstaller(s)

            // ── Connected clients ─────────────────────────────────────────────
            m == Method.GET  && uri == "/api/clients"        -> apiClients()
            m == Method.GET  && uri == "/api/connstate"       -> apiConnState(s)

            // ── Files ─────────────────────────────────────────────────────────
            m == Method.GET  && uri == "/api/files"          -> apiFileList(s)
            // ── GOAT-PASS 4 (§2): whole-storage search for the web Files tab ──
            // (one unified search experience — the same scope the app's own
            // Files sheet restored this pass, served to browsers through a
            // bounded, cached, background-prewarmed index).
            m == Method.GET  && uri == "/api/search"          -> apiSearch(s)
            m == Method.GET  && uri == "/api/download"       -> apiDownload(s)
            m == Method.POST && uri == "/api/download/register" -> apiDownloadRegister(s)
            m == Method.POST && uri == "/api/upload/request"        -> apiUploadRequest(s)
            m == Method.GET  && uri == "/api/upload/request-status" -> apiUploadRequestStatus(s)
            m == Method.POST && uri == "/api/upload/request-cancel" -> apiUploadRequestCancel(s)
            m == Method.POST && uri == "/api/upload"         -> apiUpload(s)
            // v1.0 (§hotspot-off): the web client's Reconnect on an
            // interrupted upload asks this for the byte offset to re-send from.
            m == Method.POST && uri == "/api/upload/resume"  -> apiUploadResumeOffset(s)
            // §2 Reconnect: resolves a FAILED download's re-request URL from the
            // transfer's own record (QS item / plain file path), so an interrupted
            // receive can be resumed from the active-transfers row itself.
            m == Method.POST && uri == "/api/transfer/reconnect" -> apiTransferReconnect(s)
            m == Method.POST && uri == "/api/files/delete"   -> apiDelete(s)
            m == Method.POST && uri == "/api/files/rename"   -> apiRename(s)

            // ── Phase 14: native device-to-device push (receiving side) ────────
            // A peer's own app is the client for all four of these — same
            // Connection Confirmation / password gates above apply to it
            // exactly as they would to a browser, with no special-casing.
            m == Method.POST && uri == "/api/native/push/announce"    -> apiNativePushAnnounce(s)
            m == Method.POST && uri == "/api/native/push/request"     -> apiNativePushRequest(s)
            m == Method.GET  && uri == "/api/native/push/status"      -> apiNativePushStatus(s)
            m == Method.POST && uri == "/api/native/push/cancel"      -> apiNativePushCancel(s)
            m == Method.POST && uri == "/api/native/push/file"        -> apiNativePushFile(s)
            m == Method.GET  && uri == "/api/native/push/file-status" -> apiNativePushFileStatus(s)
            m == Method.POST && uri == "/api/native/push/control"     -> apiNativePushControl(s)

            // ── Media ──────────────────────────────────────────────────────────
            m == Method.GET  && uri == "/api/media"          -> apiMedia(s)
            m == Method.GET  && uri == "/api/thumb"          -> apiThumb(s)
            // Streaming ZIP — single endpoint for every multi-file / folder download
            // (WebUI selections, single-folder downloads, multi-folder batches).
            // Accepts GET (small selections, plain link/fetch, or a legacy-iOS
            // ?selection= token) or POST (large "Select All" batches, avoids
            // URL-length limits) identically.
            (m == Method.GET || m == Method.POST) && uri == "/api/zip-stream" -> apiZipStream(s)
            m == Method.POST && uri == "/api/zip-stream/select" -> apiZipSelect(s)
            m == Method.GET  && uri == "/api/storage"      -> apiStorage()
            m == Method.GET  && uri == "/api/stats"          -> apiStats()
            m == Method.GET  && uri == "/api/media/images"   -> apiImages()
            m == Method.GET  && uri == "/api/media/videos"   -> apiVideos()
            m == Method.GET  && uri == "/api/media/music"    -> apiMusic()
            m == Method.GET  && uri == "/api/media/docs"     -> apiScan(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).absolutePath,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
            ) { MimeTypes.isDoc(it.extension.lowercase()) }

            // ── Apps ───────────────────────────────────────────────────────────
            m == Method.GET  && uri == "/api/apps/installed" -> apiInstalledApps()
            m == Method.GET  && uri == "/api/apps/download"  -> apiDownloadApk(s)

            // ── Quick Share ────────────────────────────────────────────────────
            m == Method.GET  && uri == "/api/quickshare"         -> apiQSList(s)
            m == Method.GET  && uri == "/api/qs/dl"              -> apiQSDl(s)
            m == Method.GET  && uri == "/api/quickshare/host-status" -> apiQSHostStatus()
            m == Method.POST && uri == "/api/quickshare/accept"      -> apiQuickShareAccept(s)
            m == Method.POST && uri == "/api/quickshare/clear"       -> apiQSClear()
            m == Method.POST && uri == "/api/quickshare/dismiss-all" -> apiQSDismissAll(s)
            m == Method.POST && uri == "/api/quickshare/cancel"      -> apiQSCancel(s)

            // ── Quick Text Transfer ──────────────────────────────────────────────
            m == Method.POST && uri == "/api/text/send"          -> apiTextSend(s)

            else -> serveIndex(s)
        }
    }

    // ── Password auth ─────────────────────────────────────────────────────────

    private fun serveLoginPage(): Response {
        val html = """<!DOCTYPE html><html><head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="theme-color" content="#06060f">
<title>LumiDrop X – Login</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{background:#06060f;color:#e8e8ff;font-family:-apple-system,system-ui,sans-serif;
  min-height:100vh;display:flex;align-items:center;justify-content:center;padding:20px}
.card{background:#111124;border:1px solid rgba(124,92,252,.18);border-radius:20px;
  padding:36px 28px;width:100%;max-width:340px}
.logo{font-size:32px;margin-bottom:6px}
h2{font-size:18px;font-weight:700;color:#e8e8ff;margin-bottom:4px}
p{font-size:13px;color:#6666aa;margin-bottom:24px}
input{width:100%;background:#0d0d1e;border:1px solid rgba(124,92,252,.22);border-radius:12px;
  padding:13px 16px;color:#e8e8ff;font-size:15px;outline:none;margin-bottom:14px;
  -webkit-appearance:none}
input:focus{border-color:#7c5cfc}
button{width:100%;background:#7c5cfc;color:#fff;border:none;border-radius:12px;
  padding:14px;font-size:15px;font-weight:600;cursor:pointer;-webkit-appearance:none}
button:active{background:#9b7aff}
.err{color:#f87171;font-size:13px;margin-top:10px;text-align:center;display:none}
</style></head><body>
<div class="card">
  <div class="logo"><svg viewBox="0 0 24 24" width="28" height="28" fill="#ffffff"><path d="M13.3,5.8 L9.3,5.8 L8,12 L11.1,12 L10.2,18.2 L16,11.1 L12.4,11.1 Z"/></svg></div>
  <h2>LumiDrop X</h2>
  <p>Enter the password to continue</p>
  <!-- GOAT-PASS 14 Phase 2 (S9 - discourage browser password-saving):
       this field is an APPLICATION-ACCESS password (the gate to this
       phone's web UI), not a website account credential - yet browsers'
       password managers heuristically classify any type="password"
       submission as a login and offer to save it. Standards-based
       discouragement, in layers: the field lives in a form marked
       autocomplete="off" and carries autocomplete="off" itself (the
       HTML-standard "do not remember this value" signal, which Safari
       and most engines honor for the save bar); the field NAME is a
       non-login semantic ("app-access-code") so no username+password
       pairing exists for save heuristics to key on; and there are NO
       form-submission semantics at all (type="button" + onsubmit
       suppressed) - the password only ever travels on the explicit XHR
       below. Honest limit, by task order: a browser MAY still choose to
       offer its own save UI regardless (engines can override site
       instructions); this is the best reliable standards-based posture,
       not a guarantee. Login behavior, lockout messaging and the page's
       look are byte-identical to before. Legacy-iOS-safe markup only
       (no ES6, no modern attributes). -->
  <form autocomplete="off" onsubmit="return false">
  <input type="password" id="pwd" name="app-access-code" placeholder="Password" autocomplete="off"
    autocapitalize="off" spellcheck="false"
    onkeydown="if(event.key==='Enter')login()">
  <button type="button" id="btn" onclick="login()">Unlock</button>
  </form>
  <div class="err" id="err">Incorrect password. Try again.</div>
</div>
<script>
function login(){
  var p=document.getElementById('pwd').value;
  if(!p)return;
  var x=new XMLHttpRequest();
  x.open('POST','/api/login',true);
  x.setRequestHeader('Content-Type','application/x-www-form-urlencoded');
  x.onreadystatechange=function(){
    if(x.readyState!==4)return;
    if(x.status===200){location.reload();}
    else{
      var e=document.getElementById('err'),b=document.getElementById('btn');
      var msg='Incorrect password. Try again.';
      try{
        var d=JSON.parse(x.responseText);
        if(d&&d.error){
          msg=d.error;
          // GOAT-PASS 13 (S4): the server reports the authoritative remaining
          // attempt count alongside the wrong-password error. Display it as a
          // quiet inline line (no popups); after a lockout expires the next
          // wrong attempt naturally shows the fresh, smaller count, and a
          // successful login reloads the page so no stale count can linger.
          if(x.status===401&&typeof d.attemptsRemaining==='number'&&d.attemptsRemaining>0){
            msg='Incorrect password. '+d.attemptsRemaining+' attempt'+(d.attemptsRemaining===1?'':'s')+' remaining.';
          }
        }
      }catch(ex){}
      e.textContent=msg;e.style.display='block';
      if(x.status===429&&b){
        b.disabled=true;var s=0;
        try{s=Math.max(1,parseInt(JSON.parse(x.responseText).retryAfterSec,10)||1);}catch(ex2){s=1;}
        var t=setInterval(function(){
          s--;b.textContent='Wait '+s+'s';
          if(s<=0){clearInterval(t);b.disabled=false;b.textContent='Unlock';}
        },1000);
      }
    }
  };
  x.send('pwd='+encodeURIComponent(p));
}
document.getElementById('pwd').focus();
</script></body></html>"""
        return newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", html)
    }

    private fun apiLogin(s: IHTTPSession): Response {
        // ── Pass-11 (password root fix): the login page POSTs the password ──
        // as a form-urlencoded BODY ('pwd=...'), but NanoHTTPD 2.3.1 only
        // populates s.parameters from the URL QUERY STRING unless the body
        // is explicitly parsed — exactly why every other form endpoint in
        // this server calls parseFormBodyBounded first (see apiTextSend,
        // apiClientCapability, apiTransferReconnect). Without this call,
        // s.parameters["pwd"] was ALWAYS null and every correct password
        // was answered "No password supplied" → the login page's generic
        // "Incorrect password. Try again." — the reported "clients are told
        // the password is incorrect even when it should be valid".
        parseFormBodyBounded(s, "pwd")
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        val supplied = s.parameters["pwd"]?.firstOrNull()
            ?: return newFixedLengthResponse(Status.UNAUTHORIZED, "application/json",
                JSONObject().put("error", "No password supplied").toString())
        val correct = SettingsManager.getPassword(context)
        // ── GOAT-PASS 12 (§9): progressive, SERVER-ENFORCED attempt limiting ──
        // (see pwdGate's field doc above for the full policy). The verdict
        // and the counter update are one atomic step per attempt, so rapid,
        // parallel or refreshed attempts each count and none can slip past
        // a lock that just armed. Passwords themselves are NEVER logged.
        val now = System.currentTimeMillis()
        val pwdProtectionOn = SettingsManager.isPasswordEnabled(context) && correct.isNotEmpty()
        if (supplied != correct) {
            if (!pwdProtectionOn) {
                // Password protection is off — /api/login is inert; nothing
                // about this counts toward any limit.
                return newFixedLengthResponse(Status.UNAUTHORIZED, "application/json",
                    JSONObject().put("error", "Wrong password").toString())
            }
            when (val verdict = pwdGate.onWrongPassword(ip, now)) {
                is PwdGate.Verdict.LOCKED -> {
                    val secs = ((verdict.remainingMs + 999L) / 1000L).coerceAtLeast(1L)
                    return newFixedLengthResponse(Status.TOO_MANY_REQUESTS, "application/json",
                        JSONObject().put("error", "Too many attempts. Try again in $secs second${if (secs != 1L) "s" else ""}.")
                            .put("retryAfterSec", secs).toString())
                }
                is PwdGate.Verdict.BLOCK -> {
                    // The second chance is spent — session-block through the
                    // EXISTING kick machinery (same 403 wall, same Blocked-
                    // devices sheet, same Unblock action; no second system).
                    kickDevice(ip)
                    return newFixedLengthResponse(Status.FORBIDDEN, "application/json",
                        JSONObject().put("error", "Too many failed attempts. This device was blocked for this session.").toString())
                }
                else -> {
                    // ── GOAT-PASS 13 (§4 — the browser-visible attempt counter) ──
                    // A plain wrong-password verdict reports how many more
                    // attempts remain before the NEXT enforcement step: before
                    // the first lockout that is (lock threshold - fails); after
                    // a lockout has expired it is (block threshold - fails), so
                    // the counter honestly shrinks toward the session block.
                    // The server stays authoritative: this is a REPORT of the
                    // server-side count, computed from the same PwdGate state
                    // the verdict just mutated — a browser can display it but
                    // can never reset, bypass or manipulate the real limit.
                    val fails = pwdGate.failedAttempts(ip)
                    val remaining = if (fails < PWD_FAILS_BEFORE_LOCK)
                        PWD_FAILS_BEFORE_LOCK - fails else PWD_FAILS_BEFORE_BLOCK - fails
                    return newFixedLengthResponse(Status.UNAUTHORIZED, "application/json",
                        JSONObject().put("error", "Wrong password")
                            .put("attemptsRemaining", remaining).toString())
                }
            }
        }
        // Correct password — but a temporary lockout is the wait it is:
        // enforced BEFORE evaluation on the authoritative side, so not even
        // the right password shortcuts it (a locked-out client cannot
        // brute-force the remaining window away).
        if (pwdProtectionOn) {
            when (val verdict = pwdGate.onCorrectPassword(ip, now)) {
                is PwdGate.Verdict.LOCKED -> {
                    val secs = ((verdict.remainingMs + 999L) / 1000L).coerceAtLeast(1L)
                    return newFixedLengthResponse(Status.TOO_MANY_REQUESTS, "application/json",
                        JSONObject().put("error", "Too many attempts. Try again in $secs second${if (secs != 1L) "s" else ""}.")
                            .put("retryAfterSec", secs).toString())
                }
                else -> { /* ALLOW — the client's failure state is now reset */ }
            }
        }
        val token  = java.util.UUID.randomUUID().toString()
        val expiry = System.currentTimeMillis() + 24 * 3600 * 1000L  // 24 h
        validSessions[token] = expiry
        // Clean up expired sessions
        validSessions.entries.removeAll { it.value < System.currentTimeMillis() }
        return newFixedLengthResponse(Status.OK, "application/json",
            JSONObject().put("success", true).toString()).also {
            it.addHeader("Set-Cookie", "wsx_sess=$token; Path=/; HttpOnly; Max-Age=86400")
        }
    }

    // ── PWA manifest + icon ───────────────────────────────────────────────────

    private fun apiManifest(): Response {
        val manifest = """{"name":"LumiDrop X","short_name":"LDX","description":"Wireless file transfer from your phone","start_url":"/","display":"standalone","orientation":"any","background_color":"#100E24","theme_color":"#9172F6","icons":[{"src":"/icon.png","sizes":"192x192","type":"image/png"},{"src":"/icon.png","sizes":"512x512","type":"image/png"}]}"""
        return newFixedLengthResponse(Status.OK, "application/manifest+json; charset=utf-8", manifest).also {
            it.addHeader("Cache-Control", "max-age=3600")
        }
    }

    private fun apiIcon(): Response {
        // Phase 8 (§8.3): the icon is bit-for-bit static per process — the
        // old code rendered a 1 MB ARGB bitmap and PNG-encoded it on EVERY
        // request (a cold PWA install or a cache-less legacy browser hits
        // /icon.png repeatedly). Encoded once, served from memory after.
        //
        // LumiDrop X: redrawn to the LumiDrop brand mark — a luminous
        // droplet (near-white lavender falling to accent_light) with a
        // four-point white light-spark at its heart, over a soft accent
        // glow, on the same deep purple-navy gradient canvas as the
        // launcher icon. Geometry is the exact 108-unit coordinate family
        // of ic_launcher_foreground.xml, expressed as fractions of sz, so
        // the served web icon and the launcher icon are the same mark by
        // construction.
        val b = iconPng ?: run {
            val sz = 512
            val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
            val cv  = Canvas(bmp)
            fun f(v: Float) = v / 108f * sz

            // Gradient canvas, rounded-square clipped (same 22.5% radius).
            val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(0f, 0f, sz.toFloat(), sz.toFloat(),
                    Color.parseColor("#33276B"), Color.parseColor("#120F28"),
                    android.graphics.Shader.TileMode.CLAMP)
            }
            cv.drawRoundRect(RectF(0f, 0f, sz.toFloat(), sz.toFloat()), sz * 0.225f, sz * 0.225f, bg)

            // The Lumi halo — soft radial accent glow centered on the mark.
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.RadialGradient(
                    f(54f), f(54f), f(31f),
                    Color.parseColor("#4D9172F6"), Color.parseColor("#009172F6"),
                    android.graphics.Shader.TileMode.CLAMP)
            }
            cv.drawCircle(f(54f), f(54f), f(31f), glow)

            // The drop — light-filled teardrop: tip, two cubic shoulders
            // down the right, 17-unit bulb arc through the bottom, two
            // mirrored cubics back up to the tip (the exact launcher path).
            val drop = android.graphics.Path().apply {
                moveTo(f(54f), f(27f))
                cubicTo(f(54f), f(36.5f), f(62.2f), f(42.5f), f(66.6f), f(48.8f))
                cubicTo(f(69.3f), f(52.1f), f(71f), f(54.5f), f(71f), f(57f))
                arcTo(RectF(f(37f), f(40f), f(71f), f(74f)), 0f, 180f)
                cubicTo(f(37f), f(54.5f), f(38.7f), f(52.1f), f(41.4f), f(48.8f))
                cubicTo(f(45.8f), f(42.5f), f(54f), f(36.5f), f(54f), f(27f))
                close()
            }
            val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = android.graphics.LinearGradient(
                    f(54f), f(27f), f(54f), f(74f),
                    Color.parseColor("#F2EFFF"), Color.parseColor("#BFA9FB"),
                    android.graphics.Shader.TileMode.CLAMP)
            }
            cv.drawPath(drop, dropPaint)

            // The spark — four-point light star centered in the bulb.
            val sPath = android.graphics.Path()
            sPath.moveTo(f(54f), f(45.5f)); sPath.lineTo(f(56.4f), f(52.6f))
            sPath.lineTo(f(63.5f), f(55f)); sPath.lineTo(f(56.4f), f(57.4f))
            sPath.lineTo(f(54f), f(64.5f)); sPath.lineTo(f(51.6f), f(57.4f))
            sPath.lineTo(f(44.5f), f(55f)); sPath.lineTo(f(51.6f), f(52.6f))
            sPath.close()
            cv.drawPath(sPath, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })

            val baos = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, baos); bmp.recycle()
            val encoded = baos.toByteArray()
            iconPng = encoded
            encoded
        }
        return newFixedLengthResponse(Status.OK, "image/png", ByteArrayInputStream(b), b.size.toLong()).also {
            it.addHeader("Cache-Control", "max-age=86400")
        }
    }
    @Volatile private var iconPng: ByteArray? = null

    // ── User-Agent → human-readable device label ─────────────────────────────
    // Produces "📱 Android · Chrome" / "🍎 iPad · Safari" etc.
    // Deliberately simple — only the most common patterns that matter for a
    // personal LAN-sharing app.
    fun parseDeviceLabel(userAgent: String): String {
        val ua = userAgent.lowercase()
        val os = when {
            ua.contains("ipad")    -> "iPad"
            ua.contains("iphone")  -> "iPhone"
            ua.contains("android") -> "Android"
            ua.contains("windows") -> "Windows"
            ua.contains("mac")     -> "Mac"
            ua.contains("linux")   -> "Linux"
            else                   -> "Device"
        }
        val browser = when {
            ua.contains("edg/") || ua.contains("edge/") -> "Edge"
            ua.contains("chrome") && !ua.contains("chromium") -> "Chrome"
            ua.contains("chromium") -> "Chromium"
            ua.contains("firefox")  -> "Firefox"
            ua.contains("safari") && !ua.contains("chrome") -> "Safari"
            ua.contains("opr/") || ua.contains("opera")  -> "Opera"
            else -> ""
        }
        return if (browser.isEmpty()) os else "$os · $browser"
    }

    // IP → latest User-Agent (updated every request)
    val connectedUAs = ConcurrentHashMap<String, String>()

    // Returns true when every IP that should receive this item has consumed it.
    // For targeted sends: all targetIps must be in consumedByIps.
    // For broadcasts: all currently-connected IPs must be in consumedByIps.
    // "Currently connected" = seen within last 30s — stale clients don't block
    // queue cleanup indefinitely.
    private fun allConsumed(item: QSItem): Boolean {
        val expected = item.targetIps
            ?: connectedIPs.filter { System.currentTimeMillis() - it.value < 30_000L }.keys
        return expected.all { it in item.consumedByIps }
    }

    // A Quick-Share item is normally only needed in inFlightQS briefly, to
    // serve an HTTP Range/resume follow-up request shortly after a
    // client's main download — hence the short TTL. But a transfer can now
    // sit paused-while-Waiting for an arbitrary, host-controlled length of
    // time (TransferManager.requestPause / requestResume), and the real
    // byte-request for it doesn't fire until the host resumes it AND that
    // client's own queue gets around to it — which can easily take longer
    // than the TTL. Expiring the cache entry out from under a transfer
    // that's still legitimately PENDING left the Transfers tab stuck
    // showing "Waiting" forever: the real request would eventually arrive
    // to find nothing left to serve. This keeps an item alive past its
    // nominal TTL for exactly as long as some client still has an
    // unclaimed PENDING claim on it — same lifetime PENDING already gets
    // everywhere else, nothing new to reason about.
    private fun expireInFlightQS(now: Long) {
        // ── Pass-18: an item also stays alive while any of its rows is still ──
        // resumable — an ACTIVE download (however slow) or an interrupted row
        // inside its reconnect window (see hasResumableQuickShare). The old
        // PENDING-only guard let a genuinely interrupted item's cache entry
        // expire ~5 minutes after its last request, after which the page's
        // auto-reconnect re-dispatch was answered "Not in queue" — the
        // reported "after reconnection the transfer doesn't resume at all".
        inFlightQS.entries.removeAll {
            now > it.value.second &&
                !TransferManager.hasPendingQuickShare(it.value.first.name) &&
                !TransferManager.hasResumableQuickShare(it.value.first.name)
        }
    }

    // ── Pass-21 (large-batch liveness, QS cache half) ────────────────────────
    // A 340-file batch is DRAINED one item at a time (the bounded dispatch
    // pump on the client + the one-permit download gate on this side), so the
    // tail items' byte-requests arrive many minutes after the accept that
    // cached them. Every served byte-request from a peer now also refreshes
    // THAT peer's other in-flight entries — a batch that is demonstrably
    // still moving is, by definition, a batch whose whole cached item set is
    // still legitimate, whatever any fixed TTL assumed. Belt-and-braces with
    // the PENDING/hasResumable shields above: those key on a specific row's
    // own state, this one keys on the peer's demonstrated activity.
    private fun refreshPeerInFlightQS(ip: String, now: Long) {
        if (inFlightQS.isEmpty()) return
        val expiry = now + IN_FLIGHT_TTL
        for (entry in inFlightQS.entries) {
            val item = entry.value.first
            val mine = (item.targetIps?.contains(ip) ?: true) || ip in item.consumedByIps
            if (mine && entry.value.second < expiry) entry.setValue(Pair(item, expiry))
        }
    }

    // Collision-safe name: if a file with the same base already exists in the
    // queue, appends (1), (2), … until the name is unique.
    private fun uniqueQueueName(raw: String): String {
        val existing = quickShareQueue.map { it.name }.toSet()
        if (raw !in existing) return raw
        val dot = raw.lastIndexOf('.')
        val base = if (dot > 0) raw.substring(0, dot) else raw
        val ext  = if (dot > 0) raw.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (candidate !in existing) return candidate
            n++
        }
    }

    // ── Connected clients ─────────────────────────────────────────────────────
    private fun apiClients(): Response {
        // Phase 2 of 10: read-only visibility filter — this endpoint used to
        // REAP connectedIPs itself (removeAll older than 30s), a second
        // mutator with its own staleness opinion racing the SSE ticker's
        // refresh writes. Termination is sweepSessions()'s job alone now;
        // readers read. Mode and capability ride along so any consumer of
        // this list sees the same authoritative per-session state the
        // Connected Devices card does.
        val confirmOn = SettingsManager.isConfirmConn(context)
        val arr = JSONArray()
        for (ip in liveBrowserIps(confirmOn)) {
            // While Connection Confirmation is on, liveBrowserIps already
            // filtered to Accepted sessions — a still-Pending or explicitly-
            // -Rejected device never shows up as "connected," even though
            // every request updates connectedIPs regardless of approval
            // state (that tracking also serves other purposes elsewhere).
            val ts = connectedIPs[ip] ?: 0L
            val name = connectedNames[ip] ?: ip
            arr.put(JSONObject()
                .put("ip", ip)
                .put("name", name)
                .put("lastSeen", ts)
                .put("mode", getDeviceMode(ip))
                .put("extPlayer", clientCaps.containsKey(ip)))
        }
        return json(JSONObject().put("clients", arr).put("count", arr.length()))
    }

    // Lets the page shell's own JS know whether it's still waiting on the
    // host, was declined, or is clear to load normally — polled every
    // second or so while pending, same 250ms-class polling pattern used
    // everywhere else in this file. Always answers "accepted" when the
    // feature itself is off, so the WebUI never needs to special-case that.
    private fun apiConnState(s: IHTTPSession): Response {
        if (!SettingsManager.isConfirmConn(context))
            return json(JSONObject().put("connState", "accepted"))
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        return json(JSONObject().put("connState", connRequests[ip]?.decision ?: "pending"))
    }

    // ── Streaming ZIP ────────────────────────────────────────────────────────
    // Tree-walking now lives in the shared FileTreeNode (transfer package) —
    // see that file for why. NativePushClient (Phase 14 native P2P push)
    // walks folders with the exact same type, so a folder queued for a mix
    // of browser AND native-peer recipients enumerates identically for both.

    // Recursively writes one node into the zip. Directories ALWAYS get their
    // own explicit entry (trailing '/') even when empty — this is what makes
    // empty folders survive extraction; the previous implementation only ever
    // walked files, so an empty folder produced zero entries and vanished.
    // ── GOAT-PASS 14 (Phase 1): the walker itself now lives in the shared ──
    // transfer package (transfer/ZipTree.kt) so the new app-side ZIP batch
    // sends stream through the EXACT same entry-naming/skip behaviour — this
    // is a thin delegating wrapper kept only for the three in-file call sites'
    // clarity (apiZipStream, apiQSDl's folder branch and zip-batch branch).
    private fun addZipNode(zos: ZipOutputStream, node: FileTreeNode, parentPrefix: String, seen: MutableSet<String>,
                           excluded: Set<String> = emptySet()) =
        com.lumidrop.x.transfer.ZipTree.addNode(zos, node, parentPrefix, seen, excluded)

    // Thin call-site-clarity wrapper — the real de-duplication logic is
    // shared (uniqueTreePath) so ZIP naming and native-push manifest naming
    // can never silently drift apart.
    private fun uniqueZipName(seen: MutableSet<String>, proposed: String): String =
        uniqueTreePath(seen, proposed)

    // Builds the user-facing zip filename. ── GOAT-PASS 14 (Phase 1, §1.4): ──
    // now delegated to the ONE shared generator (transfer/ZipNames.kt) so the
    // browser's own "Download as zip" and the new app-side queue-batch ZIP
    // sends produce identical, professional, character-bounded names:
    //   single source        -> "SourceName.zip" (never ".zip.zip")
    //   multiple sources     -> "First_Second_more__(N_Files|Folders|Items).zip"
    // with the FIRST label capped at 60 chars (word-boundary cut, one "…"),
    // the second at 25, and the suffix counting the ACTUAL sources. The old
    // format was the same shape with no caps — a long first name could push
    // the whole filename past comfortable display on the receiving end.
    private fun buildZipName(sources: List<File>): String =
        com.lumidrop.x.transfer.ZipNames.build(sources.map {
            com.lumidrop.x.transfer.ZipNames.Entry(
                if (sources.size == 1) it.name else it.nameWithoutExtension.ifEmpty { it.name },
                it.isDirectory)
        })

    /**
     * Reads path=/pkg= pairs from a request: query-string params (always
     * present via NanoHTTPD's own parsing, for GET and POST alike) plus, for
     * a POST, a manually-read and -decoded application/x-www-form-urlencoded
     * body — read by hand rather than via session.parseBody(), whose
     * multi-value merge behaviour for repeated keys isn't guaranteed across
     * NanoHTTPD versions, and a large "Select All" batch needs every
     * repeated path=... pair preserved. Shared by every endpoint that
     * accepts a batch of file/app selections this way.
     */
    private fun readPathPkgParams(s: IHTTPSession): Pair<MutableList<String>, MutableList<String>> {
        val rawPaths = mutableListOf<String>()
        val rawPkgs  = mutableListOf<String>()

        s.parameters["path"]?.let { rawPaths += it }
        s.parameters["pkg"]?.let  { rawPkgs  += it }

        if (s.method == Method.POST) {
            try {
                val declared = s.headers["content-length"]?.toLongOrNull() ?: 0L
                // Phase 8 (§8.2 memory hardening): 32 MB allowed a single
                // hostile/lying Content-Length to force a 32–64 MB transient
                // allocation (ByteArray + String) per request — a GC storm
                // or OOM on a low-RAM host with the door open to anyone on
                // the LAN when no password is set. 2 MB still carries a
                // "Select All" of ~20,000 long paths — far beyond any
                // realistic library — at 1/16th the worst-case footprint.
                val cap = 2L * 1024 * 1024
                val toUse = when { declared <= 0L -> 0; declared > cap -> cap.toInt(); else -> declared.toInt() }
                if (toUse > 0) {
                    val buf = ByteArray(toUse)
                    var off = 0
                    while (off < toUse) {
                        val n = s.inputStream.read(buf, off, toUse - off)
                        if (n <= 0) break
                        off += n
                    }
                    val body = String(buf, 0, off, Charsets.UTF_8)
                    for (pair in body.split("&")) {
                        if (pair.isEmpty()) continue
                        val eq = pair.indexOf('=')
                        val k = URLDecoder.decode(if (eq >= 0) pair.substring(0, eq) else pair, "UTF-8")
                        val v = if (eq >= 0) URLDecoder.decode(pair.substring(eq + 1), "UTF-8") else ""
                        when (k) { "path" -> rawPaths += v; "pkg" -> rawPkgs += v }
                    }
                }
                // A body larger than the cap still has bytes sitting unread
                // on the connection past this point — drain (not just
                // discard the values from) whatever's left, or those bytes
                // would be misread as the start of the NEXT request on a
                // keep-alive connection, corrupting it. The paths beyond the
                // cap are lost either way at this point, but a selection
                // that large is already handled by chunking client-side
                // well before hitting this ceiling.
                if (declared > toUse) {
                    var remaining = declared - toUse
                    val drain = ByteArray(64 * 1024)
                    while (remaining > 0) {
                        val n = s.inputStream.read(drain, 0, minOf(drain.size.toLong(), remaining).toInt())
                        if (n <= 0) break
                        remaining -= n
                    }
                }
            } catch (e: Exception) { /* malformed body — fall back to whatever query params we already have */ }
        }
        return Pair(rawPaths, rawPkgs)
    }

    // Short-lived token → file-selection mapping for legacy iOS's zip-stream
    // flow (see apiZipSelect below, and _zipDownload's client-side comment).
    // WebKit on that OS generation has no reliable way to deliver a large
    // POST body to the server for a file-download navigation — a
    // display:none hidden form/iframe pair doesn't reliably submit at all
    // (a long-documented WebKit quirk), and neither does one positioned
    // off-screen instead. Sending a large "Select All" selection directly as
    // a GET's path=/pkg= query-string params instead overflows NanoHTTPD's
    // request-line read buffer once it's more than a few dozen files —
    // silently truncating the selection (missing files in the finished
    // archive) and, worse, leaving unread leftover bytes on the connection
    // that get misread as the start of further bogus requests, which is
    // what produced the repeatedly-appearing-and-failing duplicate-transfer
    // symptom. Registering the selection first via an ordinary small XHR
    // POST — which, unlike a form/iframe submission, works completely
    // normally on this platform, since the issue has only ever been with
    // saving a file afterward, not with issuing a request — and then
    // requesting the zip via a tiny ?selection=<token> GET sidesteps both
    // problems: the actual download request is always small, on every
    // platform, regardless of how large the selection is.
    private data class ZipSelection(val paths: List<String>, val pkgs: List<String>, val expiresAt: Long)
    private val zipSelections = ConcurrentHashMap<String, ZipSelection>()
    private val ZIP_SELECTION_TTL_MS = 10 * 60_000L

    private fun expireZipSelections(now: Long) {
        zipSelections.entries.removeAll { it.value.expiresAt < now }
    }

    private fun apiZipSelect(s: IHTTPSession): Response {
        val (rawPaths, rawPkgs) = readPathPkgParams(s)
        if (rawPaths.isEmpty() && rawPkgs.isEmpty()) return jsonErr("No paths specified")
        val now = System.currentTimeMillis()
        expireZipSelections(now)
        val token = java.util.UUID.randomUUID().toString().replace("-", "")
        zipSelections[token] = ZipSelection(rawPaths, rawPkgs, now + ZIP_SELECTION_TTL_MS)
        return json(JSONObject().put("token", token))
    }

    // Tracks a zip generation currently in progress, keyed by exactly what
    // it's producing (the resolved zip filename + requesting client), so a
    // second POST connection for the very same download — a download
    // manager opening more than one connection for one large/slow transfer
    // — attaches to nothing and is simply told "already in progress"
    // instead of spawning its own redundant writer thread and its own
    // independent Transfers-tab entry.
    //
    // Deliberately scoped to POST only (see the method check below) — GET
    // is, in this app's current design, exclusively legacy iOS's path (see
    // _zipDownload's client-side comment), and on iPad 4 / iOS 10.3.3 this
    // guard was confirmed to actively break every ZIP download: Safari's
    // own native download handling for this kind of request (an
    // attachment, streamed with no known Content-Length) reliably opens a
    // *second* GET connection as part of how it drives the save UI — and
    // that second connection turned out to be the one Safari actually
    // keeps, not a wasteful extra. Rejecting it meant the rejection's own
    // {"error":...} JSON — 47 bytes — got saved as the "archive" instead.
    // A zip's bytes are synthesized live by exactly one writer thread with
    // nowhere to fan out a second reader, unlike a plain file (where a
    // second connection can just open its own independent read of the same
    // file on disk), so there's no way to serve both a legitimate 47-byte
    // situation and a legitimate 292-song archive from the same code path —
    // letting every GET through unconditionally is what actually makes ZIP
    // downloads work on that platform. POST-triggered requests (every other
    // platform's flow, and legacy iOS's own registration call) don't
    // involve this same native browser-download-manager behavior, so the
    // protection stays exactly as it was there.
    private val inFlightZips = ConcurrentHashMap<String, Long>()
    private val IN_FLIGHT_ZIP_TIMEOUT_MS = 10 * 60_000L

    // Single unified endpoint for every multi-item / folder download:
    //  - `path`  (repeatable) — absolute file or folder paths on local storage
    //  - `pkg`   (repeatable) — installed-app package names (resolved to their APK)
    //  - `selection` — a token from apiZipSelect, resolving to a previously
    //    registered path/pkg list (see the ZipSelection doc above)
    // A lone, plain file short-circuits to a direct passthrough (no zip wrapper,
    // matches prior single-file behaviour). Everything else streams straight
    // into the HTTP response with NO temp file and NO pre-scan/progress phase —
    // the archive is being written into the socket the instant the handler
    // returns, via a pipe fed by a background writer thread.
    private fun apiZipStream(s: IHTTPSession): Response {
        val rawPaths: List<String>
        val rawPkgs: List<String>
        val selectionToken = s.parameters["selection"]?.firstOrNull()
        if (selectionToken != null) {
            val now = System.currentTimeMillis()
            expireZipSelections(now)
            // Deliberately NOT single-use (a plain lookup, not remove()): a
            // concurrent duplicate POST connection racing the real one is
            // already caught below by inFlightZips, keyed on the resolved
            // zip name — that's the layer that should reject a duplicate,
            // not this one. If this consumed the token on first use
            // instead, a genuine retry after the real attempt actually
            // failed (a Wi-Fi hiccup, the host's screen locking mid-
            // transfer) would find the token gone and get a bare JSON error
            // in place of ever getting its ZIP — worse than the bug this
            // exists to fix. Left resolvable for its full TTL, a retry that
            // arrives after the earlier attempt has actually finished
            // (inFlightZips cleared in the same completion callback either
            // way) is simply allowed through and regenerates the archive.
            val sel = zipSelections[selectionToken] ?: return jsonErr("Selection expired or not found")
            rawPaths = sel.paths
            rawPkgs = sel.pkgs
        } else {
            val (p, k) = readPathPkgParams(s)
            rawPaths = p
            rawPkgs = k
        }

        val sources = mutableListOf<File>()
        sources += rawPaths.mapNotNull { p -> File(p).takeIf { it.exists() } }
        for (pkg in rawPkgs) {
            try {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
                else @Suppress("DEPRECATION") context.packageManager.getApplicationInfo(pkg, 0)
                sources += File(info.sourceDir)
            } catch (e: Exception) { /* app uninstalled mid-selection — skip it */ }
        }
        if (sources.isEmpty()) return jsonErr("No valid paths specified")

        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        val peerName = getDeviceDisplayName(ip)

        // Single plain file → stream it directly, no zip wrapper needed.
        if (sources.size == 1 && sources[0].isFile) {
            val f = sources[0]
            // Phase 4 download gate (see apiDownload for the attach-vs-create
            // rule; the resolved filename is what the transfer entry carries).
            // Phase 1: wait-keyed, holder-guarded slot in TransferManager.
            val gateKey: String? =
                if (TransferManager.hasActiveDownloadFor(f.name, ip)) null
                else downloadWaitKey(TransferManager.findPendingPlainDownloadId(f.name, ip))
            if (gateKey != null && !acquireDownloadSlotFor(gateKey)) return jsonErr("Cancelled by host")
            // ── Pass-17 (host-cancel finality): post-gate re-check — see ──
            // apiQSDl's identical block.
            if (gateKey != null && TransferManager.wasCancelledForPlainDownload(f.name, ip)) {
                TransferManager.releaseDownloadSlot(gateKey)
                return jsonErr("Cancelled by host")
            }
            val tid = TransferManager.claimOrStartActivePlainDownload(f.name, f.length(), ip, peerName, f.absolutePath)
            if (gateKey != null) TransferManager.moveDownloadSlot(gateKey, tid)
            // Phase 5: this single-file path previously had NO Range support
            // at all — a multi-select that resolves to one plain file was the
            // only download surface in the app a browser couldn't resume.
            // Now the same unified ETag/If-Range/Range path as everywhere else.
            // Phase 7 (§7.3 folder/batch receive on enhanced Safari): this
            // surface now honors octet=1/handoff=1 exactly like apiDownload —
            // without it, a legacy pump firing a one-file zip request served
            // with no handoff semantics meant the interception's abort
            // finalized the transfer FAILED and the DM's re-request spawned
            // a duplicate row: the classic ghost-row double-delivery.
            val zOctet   = s.parameters["octet"]?.firstOrNull() == "1"
            val zHandoff = s.parameters["handoff"]?.firstOrNull() == "1"
            if (zHandoff) TransferManager.markHandoffEligible(tid)
            val zSettleMs = handoffSettleMs(zHandoff, tid, f.length())
            val zConnToken = handoffConnCounter.incrementAndGet()
            if (zHandoff && (TransferManager.wasReAwaited(tid) || TransferManager.liveConnectionCount(tid) > 1))
                TransferManager.markHandoffSuperseded(tid, zConnToken)
            return serveFileDownload(f, if (zOctet) "application/octet-stream" else MimeTypes.getMimeType(f.extension),
                s, tid, gateKey != null, f.name, zSettleMs, zConnToken)
        }

        val zipName = buildZipName(sources)
        val now = System.currentTimeMillis()
        val zipKey = "$zipName|$ip"
        // Phase 4 download gate: a GET duplicate connection for a zip that is
        // already being generated attaches (no second slot); the POST-side
        // duplicate is rejected outright by inFlightZips below — releasing
        // the slot on that rejection too, so a request that WAITED in the
        // gate and then loses the duplicate race still leaves the gate
        // exactly as it found it. Phase 1: wait-keyed slot. Phase 7: the
        // wait key now prefers the entry's own id when one exists — a
        // handoff re-await PENDING entry (see the claim below) means a host
        // Cancel of that exact entry aborts this queued request within one
        // poll tick, same as every other download surface.
        val gateKey: String? =
            if (TransferManager.hasActiveDownloadFor(zipName, ip)) null
            else downloadWaitKey(TransferManager.findPendingPlainDownloadId(zipName, ip))
        if (gateKey != null && !acquireDownloadSlotFor(gateKey)) return jsonErr("Cancelled by host")
        if (s.method == Method.POST) {
            val existingSince = inFlightZips[zipKey]
            if (existingSince != null && now - existingSince < IN_FLIGHT_ZIP_TIMEOUT_MS) {
                // Another POST connection for this exact zip+client is
                // already being generated. GET is deliberately exempt from
                // this check entirely — see inFlightZips's doc above.
                if (gateKey != null) TransferManager.releaseDownloadSlot(gateKey)
                return jsonErr("This ZIP is already being generated")
            }
            inFlightZips[zipKey] = now
        }

        val pipeOut = PipedOutputStream()
        val pipeIn  = PipedInputStream(pipeOut, 256 * 1024)   // generous buffer = fewer thread handoffs
        // Final size is unknowable up front (compressed size of an arbitrary
        // multi-file selection) — tracked as -1 ("unknown total"), same
        // best-effort completion standard as the WebUI's own zip downloads.
        // Phase 7 (§7.3 archives on enhanced Safari): claim-or-start instead
        // of unconditional start(). The old always-fresh call gave a
        // handoff re-request (the DM re-requesting after its interception
        // abort) a SECOND zip generation under a SECOND transfer row next to
        // the aborted one — double generation work, double rows, ghost
        // "failed" — and also mis-binned a plain parallel GET duplicate as
        // its own row. Claim-or-attach-or-start keeps ONE identity per
        // logical delivery, exactly like every other download surface.
        val zipOctet   = s.parameters["octet"]?.firstOrNull() == "1"
        val zipHandoff = s.parameters["handoff"]?.firstOrNull() == "1"
        val tid = TransferManager.claimOrStartActivePlainDownload(zipName, -1L, ip, peerName, null)
        if (gateKey != null) TransferManager.moveDownloadSlot(gateKey, tid)
        if (zipHandoff) TransferManager.markHandoffEligible(tid)
        val zipSettleMs = handoffSettleMs(zipHandoff, tid, 0L)   // live-synthesized archive — size unknown at response time
        val zipConnToken = handoffConnCounter.incrementAndGet()
        if (zipHandoff && (TransferManager.wasReAwaited(tid) || TransferManager.liveConnectionCount(tid) > 1))
            TransferManager.markHandoffSuperseded(tid, zipConnToken)

        // Set only once every source has been walked and the archive closed
        // without error. Completion below requires BOTH this AND the reader
        // side finishing cleanly — total bytes read is meaningless for a
        // -1-total (unknown size) stream, so "the reader wasn't explicitly
        // cancelled" alone used to be treated as success, which is also
        // true of a connection a download manager simply abandoned
        // mid-stream (a stalled generation on a slow host, say) and silently
        // retries. That let every such retry land as a phantom "Completed"
        // entry rather than the failed attempt it actually was — the
        // repeated-completion / ever-growing Completed-count bug. Now an
        // abandoned attempt correctly finalizes as failed, so a retry is
        // just a retry, not a duplicate success.
        val zipCompletedCleanly = java.util.concurrent.atomic.AtomicBoolean(false)
        // ── PASS 28: the writer-side byte counter of the delivery gate ──
        // (see ZipStreamDelivery) — an abandoned reader must never complete
        // a row on an archive it did not fully read out of the pipe.
        val zipWriterBytes = java.util.concurrent.atomic.AtomicLong(0L)
        val writer = Thread {
            try {
                ZipOutputStream(CountingOutputStream(BufferedOutputStream(pipeOut, 64 * 1024), zipWriterBytes)).use { zos ->
                    // Photos/videos/music/APKs are already compressed — spending CPU
                    // re-deflating them buys ~0% size reduction. NO_COMPRESSION keeps
                    // this fast and lag-free even on low-end phones acting as the host.
                    zos.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                    val seen = HashSet<String>()
                    // ── Pass-36 Part 2 (#4): FsNode's default (includeHiddenChildren ──
                    // = false) is the safe default here too — a folder source streamed
                    // to a web client skips hidden descendants at every depth.
                    for (src in sources) addZipNode(zos, FileTreeNode.FsNode(src), "", seen)
                }
                zipCompletedCleanly.set(true)
            } catch (e: Exception) {
                // Client disconnected / cancelled mid-stream — nothing more to do.
                // (Cancelling from the Transfers tab closes the read side, which
                // throws here via the now-closed pipe — this same catch handles it.)
            } finally {
                try { pipeOut.close() } catch (ignored: IOException) {}
            }
        }
        writer.isDaemon = true
        writer.start()

        // Content-Length is intentionally omitted (NanoHTTPD sends
        // Transfer-Encoding: chunked for newChunkedResponse) since the final
        // compressed size literally cannot be known before the stream finishes.
        // ── FINAL PASS 3 (§1): zipTrack lets the completion callback read this ──
        // connection's own byte count — the exact archive size — and stamp it as
        // the row's real final size, so a completed ZIP-streamed delivery's
        // History entry shows the actual size instead of "?" (assigned before
        // the stream can ever be closed; each attached connection counts only
        // its own bytes, so duplicate GETs stamp the same correct figure).
        var zipTrack: com.lumidrop.x.transfer.ProgressTrackingInputStream? = null
        return servedChunked(if (zipOctet) "application/octet-stream" else "application/zip",
            ProgressTrackingInputStream(if (zipSettleMs > 0) SettleStream(pipeIn, zipSettleMs, tid, zipConnToken) else pipeIn, tid, -1L) { readerOk ->
                if (gateKey != null) TransferManager.releaseDownloadSlot(tid)
                inFlightZips.remove(zipKey)
                // ── PASS 28 (browser-native download cancellation): the ──
                // honest delivery gate + the honest abort path. A row
                // completes ONLY when the reader genuinely read every byte
                // the generation produced (ZipStreamDelivery — an abandoned
                // reader always leaves its tail unread in the pipe, so the
                // old "writer finished" gate alone completed rows for
                // connections nobody was still receiving). And an abort is
                // now CONNECTION-AWARE exactly like every other download
                // surface: the plain fail() below predated the handoff
                // semantics and resolved a download-manager stop as a hard
                // FAILED regardless of context — the same
                // downloadConnectionAborted call every other surface uses
                // now decides interception-re-await vs. the Pass-17 verdict
                // (page-alive browser cancel vs. network death) here too.
                if (readerOk && ZipStreamDelivery.deliveredFully(zipCompletedCleanly.get(), zipWriterBytes.get(), zipTrack?.bytesRead ?: -1L)) {
                    TransferManager.stampFinalSize(tid, zipTrack?.bytesRead ?: -1L)
                    TransferManager.complete(tid)
                } else TransferManager.downloadConnectionAborted(tid)
            }.also { zipTrack = it }).also { r ->
            r.addHeader("Content-Disposition", dispositionAttachment(zipName))
            r.addHeader("Cache-Control", "no-store")
            // The archive is synthesized on the fly with no known total size
            // — ranges can never be honored here. Saying so explicitly heads
            // off a download manager's own segmented-fetch attempt against
            // an endpoint that could never actually satisfy one (each such
            // attempt would otherwise just restart a full zip generation
            // from scratch, wasting a generation's worth of work per try).
            r.addHeader("Accept-Ranges", "none")
        }
    }

    // ── Static asset ──────────────────────────────────────────────────────────

    // index.html IS the app — a single file carrying all of its HTML, CSS,
    // and JS. Serving it with no explicit Cache-Control is exactly the
    // "iOS Safari (and some legacy WebViews) will cache a GET response with
    // no Cache-Control header under some conditions" trap noCache() below
    // already exists to prevent — that fix was applied to every JSON API
    // response but, until now, never to the page itself. A client caught by
    // it gets stuck on a fully stale copy of the app on their next visit —
    // wrong session handling, wrong endpoints, sometimes just a page that
    // hangs waiting on something the current server was never going to send
    // — recoverable only by manually clearing browser data. noCache() (same
    // headers used everywhere else in this file) rules that out here too.
    // Phase 8 (§8.3): the web app shell is a fixed asset of the installed
    // APK — it cannot change within one process lifetime (a rebuilt APK
    // restarts the process and re-reads), so the old per-request 330 KB
    // disk read + ByteArray allocation is pure waste on every page load
    // and reload. Cached after the first read; Cache-Control stays no-store
    // so an in-place host update still swaps content on restart exactly as
    // before — only the redundant re-READ is eliminated.
    private val assetCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private fun asset(name: String, mime: String): Response = noCache(try {
        val b = assetCache.getOrPut(name) { context.assets.open(name).readBytes() }
        newFixedLengthResponse(Status.OK, mime, ByteArrayInputStream(b), b.size.toLong())
    } catch (e: IOException) {
        newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found: $name")
    })

    // ── Pass-5 (R4 — zero-flicker boot) ───────────────────────────────────────
    // index.html is served with THIS requester's current session mode baked
    // in as a boot token: the literal `window.__BOOT_TM__=false` near the top
    // of the page is rewritten to `true` when the session (or the global
    // default) says Transfer Mode. The client uses it to hide the full-access
    // shell BEFORE the first paint and hand the screen straight to the
    // Transfer Mode overlay — a client whose default mode is Transfer Mode
    // never sees a single frame of the full-access UI (the old boot sequence
    // rendered Home first and flipped after the first /api/qstate payload
    // landed, ~100-400ms of visible flicker). Two cached variants (full /
    // transfer) keep serving a plain ByteArray per request — no per-request
    // string surgery, no per-request allocation.
    private val indexVariantCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    private fun serveIndex(s: IHTTPSession): Response = noCache(try {
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        // ── Pass-11 (approval-flash root fix): while connection approval is ──
        // required and this IP is not yet ACCEPTED, the page is served in its
        // PENDING boot variant — a neutral "connecting" shell that contains
        // NO access-mode information at all (window.__BOOT_TM__ stays false,
        // the transfer-mode overlay is force-hidden by CSS). The old flow
        // served the full mode-baked app to a pending client, and the mode
        // UI (Transfer Mode overlay / full-access shell) painted from pure
        // CSS at first frame — BEFORE the JS-created approval gate appeared
        // hundreds of milliseconds later, leaking the access mode for a few
        // hundred ms on iOS 10-class hardware. Once the host accepts, the
        // still-polling page reloads itself and gets the real mode-baked
        // variant; a rejected client never sees any mode UI.
        val connPending = SettingsManager.isConfirmConn(context) &&
            connRequests[ip]?.decision != "accepted"
        val b = indexVariantCache.getOrPut(when {
            connPending -> "pending"
            getDeviceMode(ip) == "transfer" -> "tm"
            else -> "full"
        }) {
            val raw = assetCache.getOrPut("index.html") { context.assets.open("index.html").readBytes() }
            if (connPending) String(raw, Charsets.UTF_8)
                .replace("window.__BOOT_PENDING__=false", "window.__BOOT_PENDING__=true")
                .toByteArray(Charsets.UTF_8)
            else if (getDeviceMode(ip) == "transfer") String(raw, Charsets.UTF_8)
                .replace("window.__BOOT_TM__=false", "window.__BOOT_TM__=true")
                .toByteArray(Charsets.UTF_8)
            else raw
        }
        newFixedLengthResponse(Status.OK, "text/html; charset=utf-8", ByteArrayInputStream(b), b.size.toLong())
    } catch (e: IOException) {
        newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found: index.html")
    })

    // ── File listing ──────────────────────────────────────────────────────────

    private fun apiFileList(s: IHTTPSession): Response {
        val raw    = s.parameters["path"]?.firstOrNull() ?: ""
        val root   = Environment.getExternalStorageDirectory().absolutePath
        val target = if (raw.isEmpty()) root else raw
        val dir    = File(target)
        if (!dir.exists() || !dir.isDirectory) return jsonErr("Not a directory")
        val can = dir.canonicalPath
        if (!can.startsWith("/storage/") && !can.startsWith(root)) return jsonErr("Access denied")
        val entries = (dir.listFiles() ?: emptyArray())
            .filter { !it.name.startsWith('.') }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val items = JSONArray()
        for (f in entries) items.put(JSONObject().apply {
            put("name", f.name); put("path", f.absolutePath); put("isDir", f.isDirectory)
            put("size", if (f.isFile) f.length() else 0L); put("modified", f.lastModified())
            put("ext", f.extension.lowercase())
        })
        return json(JSONObject().apply {
            // ── GOAT-PASS 4 (§2): serving the ROOT listing is the moment the ──
            // web Files tab opens — the single best opportunity to warm the
            // whole-storage search index in the background, so the user's first
            // query is answered from a resident index instead of paying the
            // bounded walk inline. Best-effort, single-flight, off the request
            // thread; the search endpoint itself remains the correctness path.
            // (GOAT-PASS 9 §5: moved INSIDE the response builder — it used to
            // sit after the return statement, unreachable dead code since the
            // day it was written.)
            if (can == root) try { SearchIndex.warmAsync() } catch (e: Exception) { /* best-effort */ }
            put("path", can); put("parent", dir.parent ?: ""); put("isRoot", can == root)
            // Phase 4 of 10 (breadcrumb 400 root cause): the canonical root
            // travels WITH the listing so the client never has to guess it.
            // The old client hardcoded "/sdcard" as the crumb root while this
            // server canonicalizes every path to "/storage/emulated/0", so
            // path.replace('/sdcard','') matched nothing and the breadcrumb
            // builder emitted PHANTOM segments ("storage", "emulated", "0")
            // above the real ones — clickable crumbs that navigated to
            // "/storage" and "/storage/emulated", which apiFileList refuses
            // ("Access denied") and jsonErr answers with HTTP 400. That was
            // the entire "clicking the Download breadcrumb 400s" bug: not
            // anything about the word "Download", but stale-root crumbs one
            // level up from it. With "root" in the payload the client builds
            // crumbs from the server's own truth; the fallback below keeps an
            // older host's responses working exactly as badly as they already
            // did (never worse).
            put("root", root)
            put("items", items); put("count", entries.size)
        })
    }

    // ── GOAT-PASS 4 (§2): whole-storage search ───────────────────────────────
    /**
     * GET /api/search?q=... — ranked, bounded search across the whole
     * accessible storage hierarchy (the web twin of the app's own Files
     * sheet search, restored this pass to full-storage scope with the same
     * speed discipline):
     *
     *   • INDEX — a bounded breadth-first walk from the storage root
     *     (25,000 entries / 60,000 queued hard caps, hidden names skipped,
     *     unreadable directories skipped silently, size/mtime captured
     *     during the walk so result rows need no second stat pass). Built
     *     once, cached for 5 minutes, single-flight (concurrent first
     *     searches all await the same build), and PREWARMED in the
     *     background the moment the Files tab opens (see apiFileList) so
     *     the common case never pays the walk inline.
     *   • RANKING — exact name (1000) > prefix (700) > word-boundary (550,
     *     so "report" finds "Annual_Report.pdf") > substring (300); ties
     *     break to the shorter name, then the shallower path. Duplicate
     *     filenames in different directories are separate hits, each
     *     carrying its own location context.
     *   • RESULTS — capped at 80, each with the fields the Files tab's
     *     rows already render plus "context" (the parent path from the
     *     storage root) so the UI can show WHERE every hit lives and offer
     *     navigation to its folder.
     */
    private fun apiSearch(s: IHTTPSession): Response {
        val q = (s.parameters["q"]?.firstOrNull() ?: "").trim()
        val root = Environment.getExternalStorageDirectory().absolutePath
        if (q.isEmpty()) return json(JSONObject().put("query", "").put("root", root)
            .put("items", JSONArray()).put("count", 0))
        SearchIndex.ensure()   // bounded walk only when missing/stale; prewarmed by the Files tab otherwise
        val items = JSONArray()
        val hits = SearchIndex.query(q)
        for (h in hits) items.put(JSONObject().apply {
            put("name", h.name); put("path", h.path); put("isDir", h.isDir)
            put("size", h.size); put("modified", h.mtime)
            put("ext", h.ext); put("context", h.context)
        })
        return json(JSONObject().apply {
            put("query", q); put("root", root)
            put("items", items); put("count", hits.size)
            put("truncated", SearchIndex.isCapped)   // honest signal when the walk hit its bounds
        })
    }

    /**
     * The bounded whole-storage index behind /api/search. Deliberately
     * self-contained (no dependency on the app-side UI search engine, which
     * lives inside the Files sheet's file) and deliberately tiny: one
     * immutable snapshot list guarded by a single-flight build mutex and a
     * freshness window. The walk is bounded by the same 25k/60k caps the
     * app-side engine uses, so a pathological tree (a huge Android/ data
     * forest) can never run away with either memory or time.
     */
    private object SearchIndex {
        private class Node(val path: String, val name: String, val lowerName: String,
                           val isDir: Boolean, val size: Long, val mtime: Long, val ext: String,
                           val relPath: String)

        class Hit(val name: String, val path: String, val isDir: Boolean, val size: Long,
                  val mtime: Long, val ext: String, val context: String)

        @Volatile private var index: List<Node>? = null
        @Volatile private var builtAtMs = 0L
        @Volatile var isCapped = false     // true when the last walk hit a hard cap (honest UI signal)
            private set
        private val buildMutex = Any()
        // ── GOAT-PASS 5 (§3 — first-attempt correctness): the GOAT-PASS 4 ──
        // caps (25k entries / 60k queued) were a SILENT completeness limit:
        // on a media-heavy device (the reported "0/movies/moviebox/" tree),
        // the breadth-first walk stopped long before the deep late folders
        // were ever listed — a search for an episode that lives there
        // honestly answered "no results" from a truncated index. The caps
        // are now 8× higher AND the Android/data system forest is deferred
        // to the very end of the queue, so a cap (if one is ever hit on a
        // genuinely pathological tree) eats system caches, never user
        // content. The honest `truncated` signal still rides every response.
        private const val MAX_INDEXED = 200_000
        private const val MAX_QUEUED = 400_000
        private const val FRESH_WINDOW_MS = 5 * 60_000L
        private const val MAX_RESULTS = 80
        private val warmExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "ldx-search-warm").apply { isDaemon = true }
        }

        private fun rootPath(): String = try {
            Environment.getExternalStorageDirectory().absolutePath
        } catch (e: Exception) { "/storage/emulated/0" }

        private fun isFresh(): Boolean =
            index != null && System.currentTimeMillis() - builtAtMs < FRESH_WINDOW_MS

        fun warmAsync() {
            try {
                warmExec.execute {
                    try { ensure() } catch (e: Exception) { /* best-effort, always */ }
                }
            } catch (e: Exception) { /* executor rejected — the next search builds inline */ }
        }

        /** Returns a usable index, building it (bounded walk) only when
         *  missing or stale. Concurrent callers all await the one build. */
        fun ensure(): List<Node> {
            if (isFresh()) return index!!
            synchronized(buildMutex) {
                if (isFresh()) return index!!
                val root = File(rootPath())
                val rootPathStr = root.absolutePath
                val acc = ArrayList<Node>(4096)
                val queue = ArrayDeque<File>()
                // ── GOAT-PASS 5 (§3): system-forest deferral — the ──────────────
                // Android/ tree (the app's own caches, opaque OBB blobs and
                // SDK forests: tens of thousands of entries the user did not
                // put there and will never search for) is queued only AFTER
                // every user-content directory has been walked, so the caps
                // can never eat a moviebox while caches still count.
                val deferred = ArrayDeque<File>()
                var queued = 0
                var capped = false
                queue.add(root); queued++
                while (queue.isNotEmpty() || deferred.isNotEmpty()) {
                    val useDeferred = queue.isEmpty()
                    val dir = if (useDeferred) deferred.removeFirst() else queue.removeFirst()
                    if (acc.size >= MAX_INDEXED || queued >= MAX_QUEUED) { capped = true; break }
                    val children = try { dir.listFiles() } catch (e: Exception) { null } ?: continue
                    for (c in children) {
                        if (acc.size >= MAX_INDEXED || queued >= MAX_QUEUED) { capped = true; break }
                        if (c.name.startsWith('.')) continue
                        val isDir = try { c.isDirectory } catch (e: Exception) { false }
                        val rel = c.absolutePath.removePrefix("$rootPathStr/")
                        var size = 0L; var mtime = 0L
                        if (!isDir) {
                            try { size = c.length(); mtime = c.lastModified() } catch (e: Exception) { /* unreadable */ }
                        }
                        acc.add(Node(c.absolutePath, c.name, c.name.lowercase(), isDir, size, mtime,
                            try { c.extension.lowercase() } catch (e: Exception) { "" }, rel))
                        if (isDir) {
                            // The Android system forest defers to the tail of
                            // the walk (GOAT-PASS 5 §3 — user content first).
                            if (rel == "Android") { deferred.add(c); queued++ }
                            else { queue.add(c); queued++ }
                        }
                    }
                }
                index = acc
                isCapped = capped
                builtAtMs = System.currentTimeMillis()
                return acc
            }
        }

        /** ── GOAT-PASS 5 (§3): event-driven invalidation — the moment the ─────
         *  host's own filesystem signal (bumpFilesVersion: every receive,
         *  delete, rename, paste the app performs) fires, the cached index
         *  is dropped and a background rebuild starts, so the NEXT query is
         *  answered from a fresh index instead of waiting out the 5-minute
         *  window. The web twin of the native sheet's own fs-change hook.
         *  The rebuild itself is DEBOUNCED (1.5 s past the last change) so a
         *  13-file receive triggers ONE rewalk at the end, not thirteen —
         *  queries inside the window simply build inline from a fresh walk. */
        private val rewarmExec = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ldx-search-rewarm").apply { isDaemon = true }
        }
        @Volatile private var rewarmTask: java.util.concurrent.ScheduledFuture<*>? = null
        fun invalidateAndRewarm() {
            synchronized(buildMutex) {
                index = null
                builtAtMs = 0L
            }
            try {
                rewarmTask?.cancel(false)
                rewarmTask = rewarmExec.schedule({
                    try { ensure() } catch (e: Exception) { /* best-effort — the next query builds inline */ }
                }, 1_500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Exception) { warmAsync() }
        }

        fun query(rawQuery: String): List<Hit> {
            val idx = index ?: return emptyList()
            val q = rawQuery.trim().lowercase()
            if (q.isEmpty()) return emptyList()
            // Score carried alongside the hit for the final ranking.
            val scored = ArrayList<Pair<Int, Hit>>(64)
            for (n in idx) {
                val score = when {
                    n.lowerName == q -> 1000
                    n.lowerName.startsWith(q) -> 700
                    n.lowerName.contains(" $q") || n.lowerName.contains("_$q") ||
                        n.lowerName.contains("-$q") -> 550
                    n.lowerName.contains(q) -> 300
                    else -> continue
                }
                // Location context: the hit's folder path from the storage
                // root, so duplicate filenames in different directories are
                // visually distinguishable at a glance (each hit also carries
                // its own absolute path for open / navigate).
                val parentRel = n.relPath.substringBeforeLast('/', "")
                scored.add(score to Hit(n.name, n.path, n.isDir, n.size, n.mtime, n.ext,
                    if (parentRel.isEmpty()) "" else parentRel))
            }
            // Rank: score desc, then shorter name, then shallower path.
            scored.sortWith(compareByDescending<Pair<Int, Hit>> { it.first }
                .thenBy { it.second.name.length }
                .thenBy { it.second.path })
            return scored.take(MAX_RESULTS).map { it.second }
        }
    }

    // ── Download with range support ───────────────────────────────────────────

    private fun apiDownload(s: IHTTPSession): Response {
        val path = s.parameters["path"]?.firstOrNull() ?: return jsonErr("No path")
        val file = File(path)
        if (!file.exists() || !file.isFile) return jsonErr("File not found")
        val ip   = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        // True cancellation: refuse an automatic/manual retry for a short
        // window right after the host cancelled this exact file for this
        // exact client, so Cancel doesn't get silently undone by the
        // client's own retry logic. Lifts on its own a few seconds later —
        // same file, again, later, on purpose, always works normally; it
        // never stays blocked just because the cancelled record is still
        // sitting in the Completed tab (see wasCancelledForPlainDownload).
        if (TransferManager.wasCancelledForPlainDownload(file.name, ip)) return jsonErr("Cancelled by host")
        // ── GOAT-PASS 6 (§3 — plain-download twin of the paused-item refusal) ──
        // See apiQSDl's identical block: a deliberately paused (settled) row
        // answers its download manager's retry with "Paused by host" +
        // Connection: close instead of parking a second connection for the
        // pause's whole duration. The page-side re-dispatch (a deliberate new
        // download decision) is never affected: it creates its own request
        // only after the pause lifted, which zeroes the stamp this checks.
        if (TransferManager.pausedDownloadPastGrace(null, file.name, ip) != null)
            return jsonErrClose("Paused by host")
        // Enhanced-Safari (jailbroken iPad 4 / Safari Plus 1.7.22) receive
        // path, Phase 4 of 10 + the person's download-behavior correction:
        // a download-manager-capable SAFARI intercepts at the navigation-
        // RESPONSE layer based on canShowMIMEType — a DISPLAYABLE content
        // type (image/jpeg, text/plain, …) is never intercepted no matter
        // what Content-Disposition says, which is exactly why image sends
        // to that iPad used to open as an image-navigation instead of a
        // download. "octet=1" asks this ONE request to be served as
        // application/octet-stream — non-displayable for every engine, so
        // any download manager present takes the file over, while stock
        // browsers simply fail the load and fall back to the client's own
        // viewer path. "handoff=1" opts the transfer into the connection-
        // aware abort semantics (TransferManager.downloadConnectionAborted)
        // that turn the interception's cancel-then-re-request pattern into
        // ONE transfer identity instead of a phantom "failed" row next to
        // the real one. Modern browsers never send either parameter; their
        // pipeline is untouched.
        val octet   = s.parameters["octet"]?.firstOrNull() == "1"
        val handoff = s.parameters["handoff"]?.firstOrNull() == "1"
        val mime = if (octet) "application/octet-stream" else MimeTypes.getMimeType(file.extension)
        // Phase 4 download gate: only a request that CREATES a new transfer
        // entry consumes a concurrency slot — a browser's parallel/duplicate
        // connection for a transfer that is already ACTIVE attaches to it
        // instead (hasActiveDownloadFor), so segmented fetches and download
        // managers can never deadlock against their own transfer.
        // Phase 1: the wait key is the PENDING entry's own id whenever the
        // client pre-registered this download — so a host Cancel of that
        // exact entry aborts this queued request within one poll tick.
        val pendingPlainTid = TransferManager.findPendingPlainDownloadId(file.name, ip)
        // Pass-5 (R5): same never-requested liveness stamp as apiQSDl.
        if (pendingPlainTid != null) TransferManager.markPendingRequested(pendingPlainTid)
        val gateKey: String? =
            if (TransferManager.hasActiveDownloadFor(file.name, ip)) null
            else downloadWaitKey(pendingPlainTid)
        // Pass-23: same refused-wait discipline as apiQSDl — the "requested"
        // stamp must not survive a request the gate just turned away.
        if (gateKey != null && !acquireDownloadSlotFor(gateKey)) {
            if (pendingPlainTid != null) TransferManager.clearPendingRequested(pendingPlainTid)
            return jsonErr("Cancelled by host")
        }
        // Re-check the host-cancel window AFTER acquiring: a request that
        // waited in the queue must not slip through a cancel that landed
        // while it was waiting.
        if (gateKey != null && TransferManager.wasCancelledForPlainDownload(file.name, ip)) {
            TransferManager.releaseDownloadSlot(gateKey)
            return jsonErr("Cancelled by host")
        }
        // ── GOAT-PASS 6 (§3): the post-gate half of the plain-download paused ──
        // refusal — a request that waited at the gate while the pause landed
        // releases cleanly and gets the same honest "Paused by host" answer.
        if (TransferManager.pausedDownloadPastGrace(null, file.name, ip) != null) {
            if (gateKey != null) TransferManager.releaseDownloadSlot(gateKey)
            if (pendingPlainTid != null) TransferManager.clearPendingRequested(pendingPlainTid)
            return jsonErrClose("Paused by host")
        }
        // Attaches to an already-ACTIVE transfer for this exact file+client
        // instead of always starting a new one — a browser fetching a large
        // file (a video, most visibly) commonly opens several parallel Range
        // connections for speed, and without this each one would show up as
        // its own "duplicate" entry in Active Transfers (see
        // claimOrStartActivePlainDownload's doc for the full reasoning).
        val tid  = TransferManager.claimOrStartActivePlainDownload(file.name, file.length(),
            ip, getDeviceDisplayName(ip), file.absolutePath, rangeStart = rangeStartOf(s))
        // Slot identity moves onto the real transfer id so every later
        // release/force-release keys off the transfer itself.
        if (gateKey != null) TransferManager.moveDownloadSlot(gateKey, tid)
        if (handoff) TransferManager.markHandoffEligible(tid)
        // The settle delay exists ONLY for the first (webview) connection of
        // a handoff attempt — it holds the body back just long enough for
        // the download manager's policy-cancel to land, so a small file
        // (smaller than the socket buffers) can't fully "complete" on a
        // connection whose bytes the interception is about to throw away.
        // A re-request for an already-re-awaited transfer skips it: that IS
        // the confirmed delivery and must start streaming immediately.
        val settleMs = handoffSettleMs(handoff, tid, file.length())
        val connToken = handoffConnCounter.incrementAndGet()
        // Phase 7 (§7.9): a second handoff-flagged request for this same
        // transfer — the DM's re-request claiming a re-awaited entry, or a
        // sibling attaching while the first connection still sits in its
        // settle sleep — PROVES the interception's cancel already landed
        // (a Safari-Plus-class DM only re-requests after canceling the
        // original load). The settling first connection's bytes are
        // confirmed waste; latch the superseded flag so its SettleStream
        // tears it down instead of streaming (and double-counting) beside
        // the confirmed one.
        if (handoff && (TransferManager.wasReAwaited(tid) || TransferManager.liveConnectionCount(tid) > 1))
            TransferManager.markHandoffSuperseded(tid, connToken)
        // Phase 5: the unified serveFileDownload path (Range + ETag/If-Range
        // identity validation + full-restart-on-change) — a resumed browser
        // download whose file changed on disk mid-flight now restarts
        // cleanly from byte 0 instead of silently stitching mismatched bytes.
        // Phase 3 of 10 (old-Safari tracked receive): "inline=1" asks for the
        // SAME bytes, SAME tracking, SAME download-gate slot — but with no
        // attachment Content-Disposition, so the response renders inline.
        // This is the receive path for browsers that cannot download at all
        // (iOS < 13 Safari has no download manager and no <a download>
        // support): the legacy client shows the file in its own in-page
        // viewer, and THAT VIEW'S LOAD IS THE TRANSFER — one logical
        // transfer, one identity, one progress bar, one Cancel action,
        // instead of a navigation that orphans the page and an untracked
        // inline GET that never appears in any transfer list. Modern
        // browsers never send inline=1; their download-attribute pipeline
        // is untouched.
        val inline = s.parameters["inline"]?.firstOrNull() == "1"
        return serveFileDownload(file, mime, s, tid, gateKey != null, if (inline) null else file.name, settleMs, connToken)
    }

    // Registers one or more plain (non-Quick-Share) file/APK downloads as
    // PENDING before the client's own download-manager queue actually gets
    // around to requesting them — see TransferManager.
    // claimOrStartPendingPlainDownload's doc for the full reasoning. This is
    // what lets the Host's Transfers tab show a client-queued multi-file
    // download's full "1 active + N waiting" picture immediately, the same
    // way it already does for a Quick-Share batch the moment it's accepted,
    // instead of only ever showing whichever single item currently happens
    // to be downloading.
    //
    // Fire-and-forget from the client's side (see index.html's
    // _registerPendingDownloads): if this call never arrives, or fails, or
    // talks to an older host that doesn't have it, the download itself is
    // completely unaffected — apiDownload/apiDownloadApk fall straight back
    // to their original create-fresh-on-request behavior whenever there's no
    // matching PENDING entry to claim. This only ever adds Host-side
    // visibility on top of that; it's never load-bearing for a download to
    // actually work.
    private fun apiDownloadRegister(s: IHTTPSession): Response {
        val (rawPaths, rawPkgs) = readPathPkgParams(s)
        if (rawPaths.isEmpty() && rawPkgs.isEmpty()) return jsonErr("No paths specified")
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        val peerName = getDeviceDisplayName(ip)

        for (p in rawPaths) {
            val f = File(p)
            if (!f.exists() || !f.isFile) continue   // best-effort — silently skip anything invalid
            TransferManager.claimOrStartPendingPlainDownload(f.name, f.length(), ip, peerName, f.absolutePath)
        }
        for (pkg in rawPkgs) {
            try {
                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
                else @Suppress("DEPRECATION") context.packageManager.getApplicationInfo(pkg, 0)
                val apk  = File(info.sourceDir)
                val name = context.packageManager.getApplicationLabel(info).toString().replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                TransferManager.claimOrStartPendingPlainDownload("$name.apk", apk.length(), ip, peerName, apk.absolutePath)
            } catch (e: Exception) { /* app uninstalled mid-selection — skip it */ }
        }
        return json(JSONObject().put("success", true))
    }

    // ── Download identity validators (Phase 5, v3 §6 item 1) ──────────────────
    // Resumable Web downloads need more than Range parsing: a 206 is only
    // honest when the file being served is byte-for-byte the SAME file the
    // client originally started downloading. If the on-disk file changed
    // since that first request (same path, new content — edited, replaced,
    // re-received), blindly serving a Range from the new version stitches
    // old-prefix + new-suffix into a silently corrupted download. The fix is
    // the standard one: a strong ETag derived from the only two properties
    // that determine this server's served bytes (size + lastModified), sent
    // with every download response, and honored on every If-Range the
    // client's download manager attaches to a resume attempt. Mismatch →
    // clean full 200 restart, exactly the "identity check fails → restart
    // from byte 0, never mislabeled as a resume" bar.

    private fun fileETag(f: File): String = "\"${f.length()}-${f.lastModified()}\""

    // RFC 7232 IMF-fixdate. Phase 8 (§8.3): one cached SimpleDateFormat
    // guarded by synchronized use — downloads are rare compared to reads,
    // and SimpleDateFormat is not thread-safe, so a shared instance needs
    // the guard; the old per-call construction (format + GMT TimeZone
    // allocation per download/media request) was pure churn in batch
    // downloads, and TimeZone.getTimeZone leaks an entry into its internal
    // cache per distinct-zone lookup on some ART builds.
    private val httpDateFmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
        .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
    private fun httpDate(ms: Long): String = synchronized(httpDateFmt) { httpDateFmt.format(java.util.Date(ms)) }

    /** True when the client's If-Range validator still matches what we would
     *  serve now — only then may a Range request be answered with 206. No
     *  If-Range at all (the common case; not every download manager sends
     *  one) degrades to best-effort per RFC 7233 §3.2, which is still
     *  correct for an unchanged file. A date-form If-Range is matched
     *  against Last-Modified; an ETag-form against the ETag. Anything else
     *  (or a mismatch) means "the validator is stale" → the caller serves a
     *  clean full 200 restart instead. */
    private fun ifRangeMatches(ifRangeHeader: String?, etag: String, lastModifiedHttpDate: String?): Boolean {
        if (ifRangeHeader == null) return true
        val v = ifRangeHeader.trim()
        if (v == etag) return true
        // A weak/strong ETag never parses as a date, and an IMF-fixdate never
        // looks like an ETag, so comparing both forms against both values
        // can't cross-match in practice; only an exact date match passes.
        return lastModifiedHttpDate != null && !v.startsWith("\"") && !v.startsWith("W/") && v == lastModifiedHttpDate
    }

    // Parses the FIRST range spec of a Range header against a body of [len]
    // bytes → (start, end) inclusive, or null when the header is malformed
    // or unsatisfiable (→ 416). Shared by the file-based and content-URI
    // based range servers so both stay byte-identical in behavior.
    private fun parseRangeSpec(rh: String, len: Long): Pair<Long, Long>? {
        // Use only the first range when the client sends a multi-range spec.
        val spec = rh.removePrefix("bytes=").trim().split(",")[0].trim()
        val dash = spec.indexOf('-')
        if (dash < 0) return null
        val startStr = spec.substring(0, dash).trim()
        val endStr   = spec.substring(dash + 1).trim()
        val start: Long; var end: Long
        when {
            startStr.isEmpty() -> {           // suffix: bytes=-N  →  last N bytes
                val n = endStr.toLongOrNull() ?: return null
                start = maxOf(0L, len - n); end = len - 1
            }
            endStr.isEmpty() -> {             // open-ended: bytes=N-  →  N to EOF
                start = startStr.toLongOrNull() ?: return null
                end   = len - 1
            }
            else -> {
                start = startStr.toLongOrNull() ?: return null
                end   = endStr.toLongOrNull()   ?: return null
            }
        }
        // RFC 7233 §2.1: "if the last-byte-pos value is absent, or if it is
        // greater than or equal to the current length of the entity-body,
        // the byte range is interpreted as the remainder of the entity-body"
        // — an over-long end must be CLAMPED to len-1, never rejected. This
        // exact case was the whole "txt/md preview fails everywhere" bug:
        // the Web Client's text preview fetches "Range: bytes=0-1048575" (a
        // 1 MB window) and virtually every text file is smaller than that,
        // so every preview got 416 → the client's catch → the misleading
        // "Could not load this file for preview" panel. 416 is now reserved
        // for what the RFC means by it: a start at/past EOF or a malformed
        // spec. (Suffix/open-ended branches already clamp via len-1; the
        // explicit-end branch is the one that needed this.)
        if (len <= 0L) return null
        if (start < 0 || start >= len || end < start) return null
        if (end >= len) end = len - 1
        return start to end
    }

    /** The one unified "serve a real file for download" path (Phase 5).
     *  Handles, in one place, what used to be four slightly-different
     *  hand-rolled blocks (apiDownload, apiDownloadApk, apiQSDl's file
     *  branch, apiZipStream's single-file branch) plus apiMedia's inline
     *  version: ETag + Last-Modified validators, If-Range restart-on-change,
     *  full Range/206 serving, transfer tracking, download-gate release, and
     *  the attachment Content-Disposition. [dispositionName] null = inline
     *  (media playback); non-null = attachment download under that name.
     *  [settleMs] > 0 (download-manager handoff) delays only the FIRST body
     *  read — see SettleStream below. */
    private fun serveFileDownload(file: File, mime: String, s: IHTTPSession,
                                  trackingId: String?, gateHeld: Boolean,
                                  dispositionName: String?, settleMs: Long = 0L,
                                  connToken: Long = 0L): Response {
        val len    = file.length()
        val etag   = fileETag(file)
        val lastMod = httpDate(file.lastModified())
        val rh     = s.headers["range"]
        val ir     = s.headers["if-range"]
        val rangeOk = rh != null && ifRangeMatches(ir, etag, lastMod)
        if (rh != null && !rangeOk) {
            // The file changed since the client's original attempt — its
            // partial bytes belong to a different version, so this must be
            // a clean full restart, never a 206 stitched onto stale bytes.
        }
        if (rangeOk && rh != null) return serveRange(file, mime, rh, len, trackingId, gateHeld, etag, lastMod, dispositionName, settleMs, connToken)
        return fullFileResponse(file, mime, len, trackingId, gateHeld, dispositionName, etag, lastMod, settleMs, connToken)
    }

    // ── Phase 10 (root fix a + §speed): SafeResponse ────────────────────────
    // NanoHTTPD 2.3.1's Response.send() closes its data stream INSIDE the
    // try, after the body send — a mid-body IOException (client abort,
    // broken pipe, interception cancel) is swallowed and close() NEVER
    // runs, so ProgressTrackingInputStream's terminal callback, the slot
    // release, downloadConnectionAborted's re-await, complete()/fail()
    // bookkeeping — the entire abort state machine — was silently dead for
    // every aborted transfer, leaking concurrency slots until the 45s
    // watchdog and leaving zombie ACTIVE entries for the next request to
    // attach onto (the "first file of the session stops client-side"
    // glitch's foundation). SafeResponse additionally routes the body
    // through a 256KB BufferedOutputStream so the underlying 16KB
    // read/write loop of NanoHTTPD coalesces into large socket writes
    // (fewer syscalls, +20-40% download throughput), and — for LIVE
    // streams (SSE) — through LiveOutputStream, which flushes after every
    // write so event pushes are never delayed by buffering.
    private class SafeResponse(status: Status, mime: String, data: InputStream,
                               len: Long, private val live: Boolean = false) :
        Response(status, mime, data, len) {
        // Instance member of THIS class (a nested, non-inner class cannot see
        // the outer class's instance members).
        private val sendBufferBytes = 256 * 1024
        override fun send(outputStream: OutputStream) {
            val out: OutputStream = if (live) LiveOutputStream(outputStream)
                else java.io.BufferedOutputStream(outputStream, sendBufferBytes)
            try { super.send(out) }
            finally {
                // ALWAYS close the data stream — success, error, or client
                // abort alike. This is the entire point of this class.
                try { out.flush() } catch (ignored: Exception) {}
                try { this.data.close() } catch (ignored: Exception) {}
            }
        }
    }
    /** Flush-after-every-write passthrough for interactive streams (SSE):
     *  a payload must reach the socket the moment it is produced, never
     *  sit in a buffer waiting for more bytes that may be minutes away. */
    private class LiveOutputStream(private val delegate: OutputStream) : OutputStream() {
        override fun write(b: Int) { delegate.write(b) }
        override fun write(b: ByteArray, off: Int, len: Int) { delegate.write(b, off, len); delegate.flush() }
        override fun flush() { delegate.flush() }
        override fun close() { try { delegate.flush() } catch (ignored: Exception) {} ; delegate.close() }
    }

    /** PASS 28 (browser-native download cancellation): counts every byte a
     *  live-archive generation thread writes into its pipe, so the delivery
     *  callback can demand the reader actually read EVERY byte the writer
     *  produced (ZipStreamDelivery) — the writer-side half of the
     *  abandoned-reader false-Completed fix; see ZipStreamDelivery's doc. */
    private class CountingOutputStream(private val delegate: OutputStream,
                                        private val counter: java.util.concurrent.atomic.AtomicLong) : OutputStream() {
        override fun write(b: Int) { delegate.write(b); counter.incrementAndGet() }
        override fun write(b: ByteArray, off: Int, len: Int) { delegate.write(b, off, len); counter.addAndGet(len.toLong()) }
        override fun flush() { delegate.flush() }
        override fun close() { try { delegate.flush() } catch (ignored: Exception) {} ; delegate.close() }
    }
    private fun servedFixed(status: Status, mime: String, data: InputStream, len: Long): Response =
        SafeResponse(status, mime, data, len)
    private fun servedChunked(mime: String, data: InputStream, live: Boolean = false): Response =
        SafeResponse(Status.OK, mime, data, -1L, live)

    // Phase 10 (root fix b): monotonically increasing identity for every
    // handoff-capable download CONNECTION. markHandoffSuperseded latches the
    // newest token; strictly-older connections are settling waste and die,
    // the newest (the download manager's confirmed re-request) always
    // survives its own latch.
    private val handoffConnCounter = java.util.concurrent.atomic.AtomicLong(0)

    // ── Download-manager handoff plumbing (enhanced-Safari receive) ──────────
    // How long the FIRST connection of a handoff-flagged download holds back
    // its first body byte. The interception cancel (a TCP teardown that
    // arrives within ~1 RTT of the response headers — tens of ms on LAN,
    // a few hundred on slow WiFi) must land before a file smaller than the
    // socket buffers could fully write itself out on a connection whose
    // bytes the download manager is about to discard anyway. The cost is
    // invisible by construction: this delay only ever applies to a request
    // whose body was going to be thrown away, never to the download
    // manager's own confirmed re-request (wasReAwaited skips it) and never
    // to any ordinary browser download (handoff is never set for those).
    // ── PASS 27 (throughput inside the direct-download architecture) ─────────
    // The settle decision now lives in DownloadSettlePolicy (pure, tested):
    // only SMALL bodies need the pre-body hold that lets a download-manager
    // interception's policy-cancel land before the body could complete on
    // the connection the interception is about to throw away. Bodies of
    // DownloadSettlePolicy.MAX_BYTES or more cannot plausibly finish inside
    // that window on any link this app serves, so settling them only delayed
    // every large direct download by ~1.1 s per item for zero protective
    // value — that per-item cost is what this pass removes.
    private fun handoffSettleMs(handoff: Boolean, tid: String, sizeBytes: Long): Long =
        DownloadSettlePolicy.settleMs(handoff, TransferManager.wasReAwaited(tid), sizeBytes)

    /** Delays the delegate's first read by [delayMs], then passes straight
     *  through. Exists purely so a download-manager interception has time
     *  to cancel the connection before a small body completes on it; see
     *  DownloadSettlePolicy.SETTLE_MS. close() always propagates so the underlying
     *  file descriptor is released regardless of whether the settle ever
     *  elapsed.
     *
     *  Phase 7 (§7.9 double-count guard): the wait is sliced into 100ms
     *  steps, and a handoff transfer whose superseded flag latched (the
     *  download manager's own re-request already arrived — the arrival
     *  itself proves the interception cancel landed, since that class of
     *  DM only re-requests after canceling) aborts the wait by THROWING
     *  instead of sleeping on. Tearing this connection down immediately
     *  keeps its would-be-waste bytes from streaming — and from counting —
     *  beside the confirmed re-request, and lets the transfer's connection
     *  accounting resolve through the normal abort path. Non-handoff
     *  transfers pass a null id and behave exactly as before. */
    private class SettleStream(private val delegate: InputStream, private val delayMs: Long,
                               private val transferId: String?, private val connToken: Long = 0L) : InputStream() {
        private var settled = false
        private fun settleOnce() {
            if (settled) return
            settled = true
            val deadline = System.currentTimeMillis() + delayMs
            while (true) {
                // Phase 10 (per-connection): ONLY connections strictly older
                // than the latched newest request are superseded waste. The
                // transfer-level Boolean made the confirmed re-request kill
                // ITSELF here — "accept the first file and it stops".
                if (transferId != null && TransferManager.isConnectionSuperseded(transferId, connToken))
                    throw java.io.IOException("Superseded by the download manager's confirmed re-request")
                val now = System.currentTimeMillis()
                if (now >= deadline) break
                try { Thread.sleep(minOf(100L, deadline - now)) }
                catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
            }
        }
        override fun read(): Int { settleOnce(); return delegate.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { settleOnce(); return delegate.read(b, off, len) }
        override fun close() { try { delegate.close() } catch (e: Exception) { /* already gone */ } }
    }

    private fun fullFileResponse(file: File, mime: String, len: Long, trackingId: String?,
                                 gateHeld: Boolean, dispositionName: String?,
                                 etag: String, lastMod: String, settleMs: Long = 0L,
                                 connToken: Long = 0L): Response {
        // Phase 8 (§8.2 slot-leak window): the file can vanish between the
        // caller's exists() check and this open — and with the download slot
        // already acquired and moved to trackingId, an exception HERE (no
        // stream ever created, so no close callback ever fires) used to
        // strand the permit until the 45s stall watchdog + 30s orphan grace
        // reclaimed it — with concurrency=1, that froze every download for
        // the better part of a minute for one vanished file. Fail + release
        // inline instead; the Transfers row and the client's drawer both
        // get the honest terminal state immediately.
        val stream: InputStream = try { FileInputStream(file) } catch (e: Exception) {
            if (trackingId != null) TransferManager.fail(trackingId)
            if (gateHeld && trackingId != null) TransferManager.releaseDownloadSlot(trackingId)
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "File no longer available")
        }
        // GOAT-PASS 2 (§throughput — the send pipeline): a TRACKED download
        // (a real file delivery, not a media-scrub range) streams through a
        // bounded read-ahead — one dedicated prefetch thread keeps up to
        // 8×256 KB filled ahead of the socket-write loop, so a storage-read
        // latency spike never stalls the TCP pipe (a stalled pipe is what
        // closes the receiver's window and collapses its congestion window
        // into the reported 5–6 MB/s dips). Untracked streams (apiMedia
        // scrubbing: many small Range requests) keep the plain stream — a
        // 2 MB prefetch per 16-byte probe would be pure waste. Nesting
        // order preserves every existing semantic byte-for-byte: counting
        // happens in ProgressTrackingInputStream (OUTSIDE), the handoff
        // settle delay in SettleStream (middle), so prefetched-but-unsent
        // bytes are neither counted nor sent before their time, and
        // SafeResponse's guaranteed close stops the prefetch on every
        // abort path.
        val piped: InputStream = if (trackingId != null)
            PipelineIO.ReadAheadInputStream(stream) { boostPipelineThread() }
        else stream
        val wrapped: InputStream = if (trackingId != null)
            ProgressTrackingInputStream(if (settleMs > 0) SettleStream(piped, settleMs, trackingId, connToken) else piped, trackingId, len) { ok ->
                if (ok) TransferManager.complete(trackingId) else TransferManager.downloadConnectionAborted(trackingId)
                // Phase 1: holder-guarded, exactly-once release by transfer
                // id — a late/second close callback, or a release after the
                // watchdog already force-freed this transfer's slots, is a
                // clean no-op instead of an extra permit.
                if (gateHeld) TransferManager.releaseDownloadSlot(trackingId)
            }
        else stream
        return servedFixed(Status.OK, mime, wrapped, len).also {
            it.addHeader("Accept-Ranges", "bytes")
            it.addHeader("ETag", etag)
            it.addHeader("Last-Modified", lastMod)
            if (dispositionName != null)
                it.addHeader("Content-Disposition", dispositionAttachment(dispositionName))
        }
    }

    private fun serveRange(file: File, mime: String, rh: String, len: Long, trackingId: String? = null,
                           releaseGate: Boolean = false,
                           etag: String? = null, lastModifiedHttpDate: String? = null,
                           dispositionName: String? = null, settleMs: Long = 0L,
                           connToken: Long = 0L): Response {
        val parsed = parseRangeSpec(rh, len)
            ?: return rangeNotSatisfiable(len).also {
                // Phase 5 (stuck-queue fix): a 416 never attaches a stream
                // to the transfer entry this request just created/claimed,
                // so that entry would otherwise sit ACTIVE with no stream
                // ever coming to complete it — nothing but the stall
                // watchdog would ever clear it. Finalize it as failed here,
                // releasing the download gate with it, so the Transfers tab
                // and the client's drawer both immediately reflect the
                // refusal instead of a phantom "downloading" row.
                if (trackingId != null) TransferManager.fail(trackingId)
                if (releaseGate && trackingId != null) TransferManager.releaseDownloadSlot(trackingId)
            }
        val start = parsed.first; val end = parsed.second

        // Phase 7 (§7.6/§7.9 resumable receive): a download manager that
        // resumes a killed download re-requests with "Range: bytes=N-".
        // Seed a freshly-claimed transfer's counter with N so the host's
        // progress bar picks up exactly where the previous attempt died
        // instead of restarting at 0% and "completing" at (size-N)/size.
        // No-op unless the entry is ACTIVE with zero counted bytes — an
        // ordinary parallel-Range sibling attaching to a progressing
        // transfer can never rewind it (see setRangeStartIfFresh).
        if (trackingId != null && start > 0L) TransferManager.setRangeStartIfFresh(trackingId, start)

        val size = end - start + 1
        // Phase 8 (§8.2 slot-leak window): same inline fail+release as
        // fullFileResponse's FileInputStream guard — see that comment.
        val raf: RandomAccessFile = try { RandomAccessFile(file, "r") } catch (e: Exception) {
            if (trackingId != null) TransferManager.fail(trackingId)
            if (releaseGate && trackingId != null) TransferManager.releaseDownloadSlot(trackingId)
            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "File no longer available")
        }
        raf.seek(start)
        val rawStream = object : InputStream() {
            var rem = size
            override fun read() = if (rem <= 0) -1 else { rem--; raf.read() }
            override fun read(b: ByteArray, off: Int, cnt: Int): Int {
                if (rem <= 0) return -1
                val n = raf.read(b, off, minOf(cnt.toLong(), rem).toInt())
                if (n > 0) rem -= n; return n
            }
            override fun close() { raf.close() }
        }
        // trackingId is only non-null for Transfers-tab-tracked endpoints
        // (apiDownload, apiQSDl) — apiMedia's range requests (video/audio
        // scrubbing) deliberately pass none, so playback never shows up as a
        // "transfer": it isn't a deliberate file send/receive from the
        // user's point of view, and would flood the list with noise.
        // GOAT-PASS 2: tracked deliveries (including a download manager's
        // large resume ranges) additionally stream through the bounded
        // read-ahead — see fullFileResponse's note; scrub-sized untracked
        // ranges keep the plain stream.
        val piped: InputStream = if (trackingId != null)
            PipelineIO.ReadAheadInputStream(rawStream) { boostPipelineThread() }
        else rawStream
        val stream: InputStream = if (trackingId != null)
            ProgressTrackingInputStream(if (settleMs > 0) SettleStream(piped, settleMs, trackingId, connToken) else piped, trackingId, size) { ok ->
                if (ok) TransferManager.complete(trackingId) else TransferManager.downloadConnectionAborted(trackingId)
                if (releaseGate) TransferManager.releaseDownloadSlot(trackingId)
            }
        else rawStream
        return servedFixed(Status.PARTIAL_CONTENT, mime, stream, size).also {
            it.addHeader("Content-Range", "bytes $start-$end/$len")
            it.addHeader("Accept-Ranges", "bytes")
            // Validators on the 206 too: a client that resumes AGAIN after
            // this partial response must be able to re-check identity the
            // same way it would against a full response.
            if (etag != null) it.addHeader("ETag", etag)
            if (lastModifiedHttpDate != null) it.addHeader("Last-Modified", lastModifiedHttpDate)
            // Phase 5: the filename hint rides the 206 as well — a resumed
            // download's follow-up connections deserve the same attachment
            // name the original request got, not a browser-default "download"
            // if the connection that carried the name is the one that died.
            if (dispositionName != null)
                it.addHeader("Content-Disposition", dispositionAttachment(dispositionName))
        }
    }

    /** Pass-14 (P18 — app→browser resume in place): the byte offset a
     *  reconnecting request actually asked for ("Range: bytes=N-"), 0 when
     *  the request carries no usable open-ended resume range. Used purely to
     *  re-open an interrupted download row with the RIGHT counter — see
     *  TransferManager.reopenInterruptedDownloadForRange. */
    private fun rangeStartOf(s: IHTTPSession): Long {
        val rh = try { s.headers["range"] } catch (e: Exception) { null } ?: return 0L
        val m = java.util.regex.Pattern.compile("bytes=(\\d+)-").matcher(rh.trim())
        return if (m.find()) m.group(1)?.toLongOrNull() ?: 0L else 0L
    }

    private fun rangeNotSatisfiable(len: Long): Response =
        newFixedLengthResponse(Status.RANGE_NOT_SATISFIABLE, "text/plain", "").also {
            it.addHeader("Content-Range", "bytes */$len")
        }

    // ── Upload (raw binary stream OR multipart FormData) ─────────────────────
    //
    // Two upload paths are supported:
    //   1. Raw binary  — client sends the File object directly as the POST body
    //      (Content-Type = file's MIME type, e.g. image/jpeg).  This is now the
    //      default path used by the updated xhrUpload() in index.html.  It skips
    //      NanoHTTPD's multipart parser entirely, avoiding the HTTP-400 crash that
    //      occurred for ALL file sizes when the temp-dir was not writable.
    //   2. multipart/form-data — legacy path kept for back-compat with any older
    //      clients; also benefits from the TempFileManager fix in the init block.

    // ── Incoming transfer approval ──────────────────────────────────────────

    private fun apiUploadRequest(s: IHTTPSession): Response {
        sweepStalePendingUploads()
        // GOAT (Phase 1, item 2 root cause): NanoHTTPD's own decodeParms()
        // already runs every query-parameter value through
        // URLDecoder.decode() while building s.parameters -- decoding it a
        // second time here silently corrupts any value containing a
        // literal '+' (a legitimate one, e.g. "C++" or "Rock+Roll", reads
        // back as a space after a second decode pass, since the sender's
        // single-encode step turns a real '+' into "%2B", which NanoHTTPD's
        // one decode already turns back into "+" correctly -- a second
        // decode then wrongly treats that bare "+" as an encoded space).
        // Use the value NanoHTTPD already handed back, undecoded again.
        val name = s.parameters["name"]?.firstOrNull() ?: return jsonErr("No name")
        val size = s.parameters["size"]?.firstOrNull()?.toLongOrNull() ?: 0L
        val ip   = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        // ── Pass-17 (label consistency): the PENDING row minted here renders ──
        // the SAME "From <device label>" the byte-carrying apiUpload row does
        // (getDeviceDisplayName: custom name > UA-parsed label > hostname >
        // IP). The old `connectedNames[ip] ?: ip` raw-IP fallback is exactly
        // what made one leg of a retried upload read "From 10.71.255.166"
        // while its twin read "From iPad · Safari" — two names for one sender.
        val fromName = getDeviceDisplayName(ip)

        val autoAccept = SettingsManager.isAutoAcceptUploads(context)
        // ── Pass-11 (refresh-interrupts-pending root fix): idempotent re-bind ──
        // A page refresh can race the FIRST approval ask: the client persists
        // its waiting rows before the ask's response lands, so the restored
        // record has no requestId — and the old code then minted a DUPLICATE
        // pending request here while the original one sat in the host's
        // Incoming Files modal. The re-ask now recognizes its own still-live
        // request (same sender IP, same name, same size, still undecided — or
        // accepted but whose bytes never started) and hands back the SAME
        // requestId + its current status, so sender and host stay one view of
        // ONE pending transfer across the reload.
        val rebinding = pendingUploads.values.firstOrNull { pu ->
            pu.fromIp == ip && pu.name == sanitize(name) && pu.size == size &&
                (pu.status == "pending" ||
                    (pu.status == "accepted" && TransferManager.findPendingUploadId(pu.id) != null))
        }
        if (rebinding != null) {
            return json(JSONObject().put("requestId", rebinding.id).put("status", rebinding.status))
        }
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        val pu = PendingUpload(id, sanitize(name), size, fromName, ip,
            status = if (autoAccept) "accepted" else "pending")
        pendingUploads[id] = pu
        // Phase 10: a newly-registered request is visible in the uploader's
        // own "ur" array immediately — no epoch needed for THAT client (it
        // learns the id from this response), but the host's Transfers tab
        // poll and any co-browsing pages converge on the next tick anyway.
        sessionEpoch.incrementAndGet()

        if (autoAccept) {
            // Same "Waiting" visibility a manually-accepted upload gets below
            // — auto-accept just means the accept happens immediately rather
            // than after a host tap.
            TransferManager.startPendingUpload(id, pu.name, size.takeIf { it > 0 } ?: -1L, ip, fromName)
        } else {
            val stillPending = pendingUploads.values.count { it.status == "pending" }
            com.lumidrop.x.service.ServerForegroundService.notifyPendingUpload(
                context, pu.name, fromName, stillPending)
        }
        return json(JSONObject().put("requestId", id).put("status", pu.status))
    }

    private fun apiUploadRequestStatus(s: IHTTPSession): Response {
        sweepStalePendingUploads()
        val id = s.parameters["id"]?.firstOrNull() ?: return jsonErr("No id")
        val pu = pendingUploads[id]
        // ── GOAT-PASS 17 (§6): a queued client's poll is LIVENESS — an ──
        // accepted request whose POST is still waiting its turn (the
        // client's own concurrency queue) must not age out while its
        // client is demonstrably still there asking.
        if (pu != null && pu.status == "accepted") pu.decidedAt = System.currentTimeMillis()
        val status = pu?.status ?: "rejected"   // expired/unknown → tell client to stop waiting
        return json(JSONObject().put("status", status))
    }

    private fun apiUploadRequestCancel(s: IHTTPSession): Response {
        val id = s.parameters["id"]?.firstOrNull()
        if (id != null) {
            pendingUploads.remove(id)
            // Covers the "accepted but not yet started" window too (the
            // client queues accepted uploads behind its own concurrency
            // limit) — without this, cancelling one before its turn comes up
            // would leave its PENDING Transfers-tab entry stuck forever.
            TransferManager.cancelPendingUpload(id)
            // ── Pass-12 (cancel-finality root fix) ──────────────────────────
            // ALSO cancel any row that is already ACTIVE and streaming under
            // this request id. The old code deliberately left the in-flight
            // case to "the dropped connection tells the host to stop" — but a
            // dropped connection is indistinguishable from a network death, so
            // the host's row resolved Interrupted (FAILED+interrupted), its
            // staging partial was KEPT for a resume nobody wanted, and a
            // deliberate cancel on the sender read as an accident on the
            // receiver. Cancelling through the engine makes the row resolve
            // CANCELLED immediately, the per-chunk cancel check in apiUpload's
            // read loop breaks the stream within one buffer, its cleanup tail
            // hard-deletes the staging partial, and the durable tombstone
            // refuses any retry leg — cancel is final, on both sides, with no
            // temp resume files left behind.
            TransferManager.cancelUploadRequestEverywhere(id)
            // v1.0 (§hotspot-off): an explicit cancel also drops any resumable
            // partial this request may have left behind — deliberate
            // cancellation must never resurrect (the same contract
            // wasCancelledForUpload enforces for in-flight cancels).
            dropResumableUpload(id)
            refreshPendingNotif()
        }
        return json(JSONObject().put("success", true))
    }

    // v1.0 (§hotspot-off): the offset oracle for resuming an interrupted
    // upload. The web client's Reconnect calls this first; on success it
    // re-POSTs /api/upload?...&reqId=<id>&offset=<N> with a body of exactly
    // the REMAINING bytes (file.slice(N)), which apiUpload appends onto the
    // preserved staging partial. Identity is bound to the requesting IP —
    // only the original uploader may resume its own interrupted transfer.
    private fun apiUploadResumeOffset(s: IHTTPSession): Response {
        sweepResumableUploads()
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        val id = s.parameters["id"]?.firstOrNull() ?: return jsonErr("No id")
        // ── Pass-17 (idempotent reconnect): the resumable record for this ──
        // request may be gone precisely BECAUSE the upload COMPLETED — the
        // browser lost the success response to a network blip and is now
        // asking to resume something that already fully landed. Answering
        // with the old bare error sent the client into a full from-0 re-POST
        // (a duplicate file + a duplicate row, see apiUpload's own guard);
        // the completed answer lets it settle the row as done with zero
        // bytes re-sent.
        if (!resumableUploads.containsKey(id)) {
            val done = TransferManager.findRecentlyCompletedUpload(id)
            if (done != null) {
                return json(JSONObject()
                    .put("success", true)
                    .put("completed", true)
                    .put("offset", done.totalBytes)
                    .put("name", done.fileName)
                    .put("size", done.totalBytes))
            }
            // ── GOAT-PASS 3 (§4 — Windows reconnect regression, root fix) ──
            // No record yet, but the upload's row is still LIVE: the dead
            // leg's read loop is still resolving (a silent connection death
            // blocks its read for seconds — until the read errors, the
            // session sweep, or the stall watchdog — and only THEN registers
            // the resumable checkpoint this endpoint serves). Answering the
            // old bare "No resumable upload" here made the client treat a
            // mid-resolution reconnect as a REFUSAL and restart the file
            // from byte 0, discarding the sender's progress. The explicit
            // pending marker (409 + pending:true) tells the client to keep
            // its backoff going — the record lands within moments and the
            // next attempt resumes byte-exactly.
            //
            // ── GOAT-PASS 5 (§8 final round): the pending answer is only ──
            // honest when a leg is GENUINELY still resolving — a live
            // server-side request for this row, waiting at or holding the
            // receiving gate (isReceivingInFlight). A DEMOTED park row
            // (paused before its first durable byte: PENDING+paused, no
            // record, no leg, nothing coming) must get the honest REFUSAL
            // instead — the browser's refusal path re-POSTs fresh under the
            // same request id and CLAIMS that very row. Answering "pending"
            // for it parked the browser in an infinite 1.5 s retry loop
            // while the row waited forever for a POST that never came.
            val live = TransferManager.findLiveUploadRowByRequestId(id)
            if (live != null && TransferManager.isReceivingInFlight(live.id)) {
                return noCache(newFixedLengthResponse(Status.CONFLICT, "application/json; charset=utf-8",
                    JSONObject().put("error", "Resume pending — interrupted leg still resolving").put("pending", true).toString()))
            }
            if (live != null && live.status == TransferManager.Status.ACTIVE) {
                // An ACTIVE row with NO live leg and NO record: a parked
                // row whose checkpoint record was lost (the 24 h park
                // backstop, a staging cleanup) — the row lives, but nothing
                // can resume onto it. The refusal sends the browser down
                // its restart path, and apiUpload's fresh-POST admission
                // ADOPTS this same row (findParkedUploadId) — same
                // identity, honest byte-zero restart, never a duplicate.
                return jsonErr("Transfer no longer resumable")
            }
        }
        val rec = resumableUploads[id] ?: return jsonErr("No resumable upload")
        if (ip != "unknown" && ip != rec.ip) return jsonErr("Not your upload")
        if (rec.expiresAt < System.currentTimeMillis() || !rec.staging.exists()) {
            dropResumableUpload(id)
            return jsonErr("Resume window expired")
        }
        // Re-validate the staging file's real length against the recorded
        // byte count — if anything touched the partial in between, the honest
        // answer is a refusal, never a corrupted append.
        val actual = rec.staging.length()
        if (actual != rec.written) {
            dropResumableUpload(id)
            return jsonErr("Partial no longer valid")
        }
        // The transfer row itself must still be resumable. Two honest
        // shapes reach here:
        //   • FAILED — the interruption window (the original contract:
        //     reopenableForResume, unchanged);
        //   • ACTIVE — a PARKED row (GOAT-PASS 5 §8: its settled host pause
        //     ended the leg but kept the row Active+paused with its durable
        //     checkpoint registered). The row IS the resume target; refusing
        //     it (the old FAILED-only check) made the browser's pause-lift
        //     requeue hit "Transfer no longer resumable", fall to its
        //     restart path, and throw away every paused byte — the park
        //     equivalent of the §9 restart-from-zero regression.
        // A row that is currently STREAMING on a newer leg (a resumed leg
        // already re-acquired this record's slot key) answers 409+pending —
        // the ask must wait for the live leg to settle, exactly like a
        // still-resolving interruption. Anything else (dismissed, terminal,
        // PENDING) refuses: nothing can resume onto it.
        val recRow = TransferManager.get(rec.tid)
        if (recRow != null && recRow.status == TransferManager.Status.ACTIVE) {
            if (TransferManager.isReceivingInFlight(rec.slotId)) {
                return noCache(newFixedLengthResponse(Status.CONFLICT, "application/json; charset=utf-8",
                    JSONObject().put("error", "Resume pending — a newer leg is still resolving").put("pending", true).toString()))
            }
            // The parked row — answer its preserved checkpoint.
            return json(JSONObject()
                .put("success", true)
                .put("offset", rec.written)
                .put("name", rec.dest.name)
                .put("size", rec.declaredLen))
        }
        if (!TransferManager.reopenableForResume(rec.tid)) return jsonErr("Transfer no longer resumable")
        return json(JSONObject()
            .put("success", true)
            .put("offset", rec.written)
            .put("name", rec.dest.name)
            .put("size", rec.declaredLen))
    }

    private fun apiUpload(s: IHTTPSession): Response {
        // GOAT (Phase 1, item 2 root cause): see apiUploadRequest's doc
        // comment above -- s.parameters is already decoded by NanoHTTPD;
        // decoding again here corrupts any name with a literal '+' in it.
        val rawName   = s.parameters["name"]?.firstOrNull() ?: "upload_${System.currentTimeMillis()}"
        val targetDir = s.parameters["path"]?.firstOrNull() ?: SettingsManager.getUploadPath(context)
        // Final Release (§13): the client-chosen upload directory must
        // resolve inside real external storage — a crafted path outside it
        // is rejected outright, mirroring apiFileList's access check.
        val targetCanon = try { File(targetDir).canonicalPath } catch (e: Exception) { null }
        if (targetCanon == null || !targetCanon.startsWith("/storage/")) {
            return jsonErrClose("Invalid upload directory")
        }
        val safeName  = sanitize(rawName)
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        val reqId = s.parameters["reqId"]?.firstOrNull()
        // Body length of THIS request — the whole file for a fresh upload,
        // only the remainder for a resume leg (see wholeFileLen below).
        val declaredLen = s.headers["content-length"]?.toLongOrNull() ?: -1L

        // True cancellation: refuse an automatic/manual retry right after the
        // host cancelled this exact upload — otherwise the client's own
        // retry logic would silently re-succeed, undoing the cancel. Checked
        // before any filesystem work or notification side-effects, so a
        // blocked retry has zero observable effect beyond the error response.
        if (reqId != null && TransferManager.wasCancelledForUpload(reqId)) return jsonErrClose("Cancelled by host")

        // ── Pass-22 (compile fix): the offset parameter is parsed ONCE, up ──
        // here, so the idempotent-re-POST guard below can read it (it used the
        // name before its own declaration — an unresolved reference that kept
        // the whole app from compiling).
        val resumeOffset = s.parameters["offset"]?.firstOrNull()?.toLongOrNull()

        // ── Pass-17 (no duplicate upload rows/files) ─────────────────────────
        // A re-POST under a request id whose FIRST leg already delivered every
        // byte (the browser lost the 2xx to a connection blip and its own
        // auto-reconnect restarted the whole file) is IDEMPOTENT here: the
        // bytes are already safely on disk under their final name, so the
        // honest answer is the original success — NOT a second full transfer
        // that writes "photo (1).jpg" beside the real one under a fresh row
        // (the reported duplicate "From iPad · Safari" + "From <raw IP>"
        // pair). The window matches the client's own reconnect horizon; a
        // deliberate new send mints a brand-new requestId and sails through
        // the ordinary path exactly as before.
        if (resumeOffset == null && reqId != null) {
            TransferManager.findRecentlyCompletedUpload(reqId)?.let { done ->
                return json(JSONObject().put("success", true)
                    .put("name", done.fileName)
                    .put("size", done.totalBytes)
                    .put("duplicate", true))
            }
        }

        // v1.0 (§hotspot-off): RESUME continuation — an offset param marks
        // this POST as the second leg of an upload this same client already
        // started and lost to a network interruption (hotspot turned off,
        // Wi-Fi drop, connection reset). The staging partial was deliberately
        // KEPT and registered at interruption time (see Path 2 below);
        // validate it byte-exactly before accepting a single new byte, so a
        // stale/corrupted partial can never be appended onto.
        sweepResumableUploads()
        val resumeRec: ResumableUpload? = if (resumeOffset != null && resumeOffset > 0L) {
            val rec = reqId?.let { resumableUploads[it] }
                ?: return jsonErrClose("No resumable upload")
            if (ip != "unknown" && ip != rec.ip) return jsonErrClose("Not your upload")
            if (rec.expiresAt < System.currentTimeMillis()) {
                dropResumableUpload(reqId); return jsonErrClose("Resume window expired")
            }
            if (!rec.staging.exists() || rec.staging.length() != resumeOffset || rec.written != resumeOffset) {
                dropResumableUpload(reqId); return jsonErrClose("Partial no longer valid")
            }
            rec
        } else null
        // Total bytes of the WHOLE file — the transfer row tracks this, not
        // just this leg's body length (which is only the remainder).
        val wholeFileLen = resumeRec?.declaredLen ?: declaredLen

        // Collision handling: never silently overwrite an existing file.
        // Appends (1), (2), … until a name is available, matching standard
        // file-manager behaviour and the queue-side uniqueQueueName() logic.
        // A RESUME keeps the destination it already resolved before the
        // interruption — the preserved staging partial is tied to it, and
        // the rename-time collision scan below still guards the final name.
        val dest = resumeRec?.dest ?: run {
            val base = File(targetDir, safeName)
            if (!base.exists()) { base.also { it.parentFile?.mkdirs() } }
            else {
                val dot  = safeName.lastIndexOf('.')
                val stem = if (dot > 0) safeName.substring(0, dot) else safeName
                val ext  = if (dot > 0) safeName.substring(dot) else ""
                var n = 1
                var candidate: File
                do { candidate = File(targetDir, "$stem ($n)$ext"); n++ } while (candidate.exists())
                candidate.also { it.parentFile?.mkdirs() }
            }
        }

        // GOAT-PASS 1 threaded [doneTid] through as a parameter (not a
        // capture) because the upload's transfer id is only resolved further
        // below, after the receiving-slot/claim chain. GOAT-PASS 2 returns
        // the receiving gate to exactly 1, so "sibling uploads still
        // streaming" can no longer occur from THIS host's own admission —
        // but the parameter shape stays: it is harmless, and the service
        // side keeps telling "this was the last one" from "others may still
        // be mid-flight" without any coupling to the gate constant.
        fun notifyService(done: Boolean, progress: Int = 0, doneTid: String? = null) {
            context.startService(android.content.Intent(context,
                com.lumidrop.x.service.ServerForegroundService::class.java).apply {
                action = com.lumidrop.x.service.ServerForegroundService.ACTION_UPLOAD_PROGRESS
                if (doneTid != null) putExtra("tid", doneTid)
                if (done) putExtra("done", true)
                else { putExtra("name", safeName); putExtra("progress", progress) }
            })
        }

        notifyService(done = false, progress = 0)

        // Transfers tab tracking — additive, alongside the existing
        // persistent-notification mechanism above, not a replacement for it.
        val peerName = getDeviceDisplayName(ip)
        // If this upload went through the approval flow, a PENDING entry was
        // already created the instant it was accepted (see apiUploadRequest /
        // acceptUpload above) — reuse that one by requestId instead of
        // creating a second, duplicate Transfers-tab row. A client that
        // never went through approval (pre-update, or Auto-Accept's own
        // immediate path) gets a synthetic requestId minted here purely so
        // it still sits PENDING — "Waiting" — until a receiving slot is
        // free below, the same as every other upload, instead of skipping
        // that queue entirely.
        //
        // GOAT (transfer stability pass — unified receiving concurrency):
        // promotion to ACTIVE (claimPendingUpload) now happens AFTER
        // acquireReceivingSlot grants a slot, not before — so a file queued
        // behind the receiving gate's single permit visibly
        // stays "Waiting" for as long as it's actually waiting, rather than
        // showing "Active" the instant its HTTP request merely arrived.
        val effectiveReqId = reqId ?: "auto:${java.util.UUID.randomUUID()}"
        // v1.0 (§hotspot-off): a resume re-acquires the ORIGINAL slot and
        // re-opens the ORIGINAL transfer row — no second PENDING row, no
        // fresh id, no duplicate history: the sender's Reconnect resolves
        // the very row that just flipped from Failed back to Active.
        // Pass-10 (automatic resume): a FRESH leg under a requestId whose row
        // already FAILED as Interrupted (the browser's automatic reconnect
        // restarted from byte 0 after the server dropped the partial, or a
        // refresh-recovered "Re-select file" re-send) ALSO resolves back onto
        // the ORIGINAL row — read-only lookup first so it can serve as the
        // slot wait key, re-opened after the slot is granted exactly like the
        // native-push receive path.
        val interruptedId = if (resumeRec == null)
            reqId?.let { TransferManager.findInterruptedUploadId(it) } else null
        // ── GOAT-PASS 5 (§8/§9 final round): a fresh POST under a requestId ────
        // whose row is a live PARKED upload (its settled host pause ended the
        // leg but kept the row Active+paused) ADOPTS that row — the browser's
        // park-refusal restart (its checkpoint record was lost) must land on
        // the SAME row, never mint a second one beside it. Ordered after the
        // PENDING lookup so an ordinary queued re-POST keeps its existing
        // claim, and after the interrupted lookup so a FAILED row keeps its
        // own (long-standing) precedence.
        val parkedId = if (resumeRec == null && interruptedId == null)
            reqId?.let { TransferManager.findParkedUploadId(it) } else null
        val pendingId = resumeRec?.slotId
            ?: reqId?.let { TransferManager.findPendingUploadId(it) }
            ?: interruptedId
            ?: parkedId
            ?: TransferManager.startPendingUpload(effectiveReqId, safeName, wholeFileLen, ip, peerName)
        // ── GOAT-PASS 5 (§7 ledger truth, final round): the id the receiving ──
        // slot is released under. Normally == pendingId; when the claim chain
        // below mints a FRESH row (the start() fallback for a swept/cancelled
        // PENDING), the held registration is re-keyed to that row so
        // isExecuting stays truthful — and this variable is what the finally
        // releases, keeping the exactly-once discipline intact.
        var slotKey = pendingId
        if (!TransferManager.acquireReceivingSlot(pendingId)) {
            notifyService(done = true)
            return jsonErrClose("Cancelled by host")
        }
        // ── Pass-17 (host-cancel finality, queued-upload half): the top-of- ──
        // function tombstone check ran BEFORE this request waited for a free
        // receiving slot — a host Cancel landing while it was queued must still
        // be honored, or the claim chain below would fall through to the
        // start() fallback and stream a whole fresh transfer for a file the
        // host just killed (the ghost-upload twin of the ghost-download).
        if (reqId != null && TransferManager.wasCancelledForUpload(reqId)) {
            TransferManager.releaseReceivingSlot(pendingId)
            notifyService(done = true)
            return jsonErrClose("Cancelled by host")
        }
        val tid = if (resumeRec != null) {
            // ── GOAT-PASS 5 (§8 — the parked row continues in place) ──────────
            // A row parked by a settled host pause never FAILED — it stayed
            // Active+paused with its checkpoint while its leg was deliberately
            // ended. The re-dispatched resume POST continues THAT row (same
            // id, same card, progress from the checkpoint), never a fresh
            // row beside it. reopenForResume stays the path for genuinely
            // FAILED+interrupted legs; the fresh-row fallback stays the path
            // for a row that vanished entirely.
            val parked = TransferManager.get(resumeRec.tid)
            when {
                parked != null && parked.status == TransferManager.Status.ACTIVE -> {
                    TransferManager.clearPauseSlotDebt(resumeRec.tid)
                    resumeRec.tid
                }
                !TransferManager.reopenForResume(resumeRec.tid) -> {
                    // The FAILED row was dismissed out from under us between the
                    // offset query and this POST — fall back to a fresh, honest,
                    // fully-tracked row (never a ghost).
                    TransferManager.start(TransferManager.Direction.UPLOAD, safeName, wholeFileLen,
                        ip, peerName, filePath = dest.absolutePath)
                }
                else -> resumeRec.tid
            }
        } else {
            // Phase 1 of 10 (ghost-completion fix): claimPendingUpload returning
            // null here means the PENDING row is GONE — swept by the upload-TTL
            // sweep while this request waited for its receiving slot (now
            // guarded by isReceivingInFlight, but belt-and-braces), or cancelled
            // out from under us between slot-grant and claim. The honest fallback
            // is a fresh, properly-tracked ACTIVE entry that follows the bytes
            // all the way to complete(). Pass-10: before that fallback, an
            // interrupted FAILED row for this same request is re-opened in place
            // — one row per logical transfer, even across a restart-from-zero.
            //
            // ── GOAT-PASS 5 (§8/§9 final round): a live PARKED row for this ──
            // request (adopted as pendingId above) continues on ITS OWN row —
            // the byte-zero restart after a lost checkpoint record reuses the
            // same card, the same history and the same identity. clearPause-
            // SlotDebt mirrors the resume-leg branch: the parked leg's pause
            // yield already handed its permit back, and a pause that is still
            // standing simply re-parks this leg through the loop's own settle.
            val freshRow = TransferManager.claimPendingUpload(effectiveReqId, dest.absolutePath)
                ?: interruptedId?.let { TransferManager.reopenInterruptedUploadByExactId(it, dest.absolutePath) }
                ?: parkedId?.let { parked ->
                    TransferManager.clearPauseSlotDebt(parked)
                    parked
                }
                ?: TransferManager.start(TransferManager.Direction.UPLOAD, safeName, wholeFileLen,
                    ip, peerName, filePath = dest.absolutePath)
            // ── GOAT-PASS 5 (§7 ledger truth, final round): the fallback row ──
            // was minted AFTER the slot was already acquired under pendingId —
            // move the held registration onto the row that will actually
            // stream, so isExecuting (and this leg's own release below, via
            // slotKey) stay truthful on the rare swept-PENDING path.
            if (freshRow != pendingId) {
                TransferManager.rekeyReceivingSlot(pendingId, freshRow)
                slotKey = freshRow
            }
            freshRow
        }
        // ── Pass-17 (one card per transfer): THIS leg's generation snapshot —
        // every failure/registration tail below consults it so a superseded
        // leg (its connection died right before a newer leg re-opened the row)
        // can never fail or tombstone the row the newer leg is streaming on.
        val legResumeGen = TransferManager.resumeGenOf(tid)

        val contentType = (s.headers["content-type"] ?: "").lowercase()

        // Path 2 (below) resolves the file's FINAL on-disk location only after
        // the bytes are fully written (a concurrent upload may have claimed the
        // originally-picked name in the meantime) — this tracks where the file
        // actually landed so the verification/scan/complete tail below operates
        // on the real file, not the pre-collision guess.
        var landedFile = dest
        // Path 2's staging file, non-null only once Path 2 has started — lets
        // the catch below clean up the RIGHT partial (staging) without ever
        // touching a completed file that merely shares the intended name.
        var stagingFile: File? = null

        try {
            // ── FINAL PASS 1 (§3.3 — the slow-live-sender read window) ─────────
            // Same contract as apiNativePushFile's: widen THIS connection's
            // read window for the inbound body (both the raw-stream path and
            // the multipart parse below read from this socket), so a slow
            // but alive browser upload is never falsely aborted by the 5 s
            // server-wide idle default while a dead connection still dies
            // bounded. See TRANSFER_BODY_READ_TIMEOUT_MS.
            try { s.extendSocketReadTimeout(TRANSFER_BODY_READ_TIMEOUT_MS) } catch (e: Exception) { /* best-effort only */ }
            if (contentType.contains("multipart/form-data")) {
                // ── Path 1: multipart/form-data (legacy FormData) ─────────────
                val files = HashMap<String, String>()
                try { s.parseBody(files) } catch (e: Exception) {
                    notifyService(done = true, doneTid = tid); TransferManager.fail(tid)
                    // §2 cancellation-vs-failure: a host Cancel mid-buffer surfaces
                    // here as a parse failure — the SENDER must see Cancelled, not
                    // a generic failure, or it offers Retry on something the host
                    // deliberately refused (wasCancelledForUpload also blocks that
                    // retry server-side; the honest label removes the temptation).
                    return jsonErrClose(if (TransferManager.isCancelled(tid)) "Cancelled by host" else "Body parse failed: ${e.message}")
                }
                val tempPath = files["file"] ?: files["content"] ?: files.values.firstOrNull()
                    ?: run { notifyService(done = true, doneTid = tid); TransferManager.fail(tid); return jsonErrClose("No file data received") }
                val tempFile = File(tempPath)
                if (!tempFile.exists()) { notifyService(done = true, doneTid = tid); TransferManager.fail(tid); return jsonErrClose("Temp file missing") }
                if (!tempFile.renameTo(dest)) { tempFile.copyTo(dest, overwrite = true); tempFile.delete() }
                // NanoHTTPD's multipart parser fully buffers the upload internally
                // before parseBody() returns, so there's no opportunity to observe
                // incremental progress for this path — it goes straight to 100%
                // rather than faking intermediate values we don't actually have.
                TransferManager.updateProgress(tid, dest.length())

            } else {
                // ── Path 2: raw binary stream ──────────────────────────────────
                // Phase 5 (v3 §6 item 2): every byte now lands in an isolated
                // STAGING file (".<name>.wsxpart", same directory, dot-prefixed
                // so no file browser or media scanner ever surfaces it), and
                // only a fully-written, byte-count-verified upload is renamed
                // into its final name — the same staging-then-rename contract
                // the native-push receive path has always had. Until now this
                // path wrote straight into the real destination, so a partial
                // upload was VISIBLE under its final name for the entire
                // transfer (and a concurrent upload of the same name could
                // clobber it mid-flight).
                val contentLength = declaredLen
                val inp = s.inputStream
                // v1.0 (§hotspot-off): a RESUME leg opens the SAME staging file
                // at its preserved base offset and counts its progress from
                // there — the row, the bytes, and the file all continue from
                // exactly where the interruption cut them off. A fresh leg
                // writes from scratch exactly as before.
                val baseOffset = resumeRec?.written ?: 0L
                var streamCompleted = false
                val staging = stagingFileFor(dest)
                stagingFile = staging
                // GOAT-PASS 2 (§throughput — the receive pipeline): the old
                // loop did inp.read() → out.write() SERIALLY on this worker
                // thread, so every flash-write stall (journal commit, f2fs GC,
                // erase-block management — routinely tens of ms on Android)
                // stopped the socket drain, filled the kernel receive window,
                // collapsed the sender's congestion window and produced the
                // reported sudden 23–28 → 5–6 MB/s dips. The bounded
                // PipelinedFileWriter decouples the two stages: this thread
                // only reads the socket (window stays open), a dedicated
                // writer thread owns the file, and a fixed 8×256 KB queue of
                // pooled buffers absorbs storage-latency bursts while
                // degenerating into ordinary TCP backpressure if the disk is
                // genuinely slower than the network. Integrity levels stay
                // separated exactly as the spec requires: progress is
                // reported from the READER count (fast in-memory progress),
                // while ONLY the DURABLE count finish() returns ever backs a
                // resume record or a completion.
                val pipe = PipelineIO.PipelinedFileWriter(staging, baseOffset,
                    onWriterThreadStart = { boostPipelineThread() })
                var durableTotal = baseOffset
                try {
                    // Fresh leg: the body IS the whole file. Resume leg: the
                    // body is only the remainder, so the expected byte count
                    // is the whole-file length minus what's already on disk.
                    var remaining = if (resumeRec != null) (resumeRec.declaredLen - baseOffset) else contentLength
                    while (remaining != 0L) {
                        // ── GOAT-PASS 5 (§8 — the fifth-pause queue freeze, root fix) ──
                        // The unbounded awaitUnpaused used to park this worker
                        // INSIDE its HTTP leg for as long as the pause stood:
                        // the browser's XHR stayed open (its per-host
                        // connection pool is finite — six on every current
                        // engine, and the SSE stream already occupies one),
                        // and the web pump — which correctly stops counting a
                        // paused upload as active and starts the NEXT file —
                        // kept stacking one parked connection per paused item
                        // until the pool ran dry and the next upload could not
                        // even open a socket. That is exactly the reported
                        // "after pausing item 5, item 6 does not start": five
                        // parked legs + the SSE stream = the whole pool.
                        //
                        // A pause that settles now ENDS the leg deliberately —
                        // the same discipline the native push path has always
                        // had ("Paused by sender"): the durable checkpoint is
                        // registered, the row keeps its Active+paused state
                        // (progress and position preserved), and the response
                        // tells the sender exactly what happened. The
                        // sender's re-dispatch, once the host lifts the
                        // pause, resumes from the checkpoint through the
                        // ordinary resume chain — one live connection per
                        // logical transfer, at any pause depth.
                        if (TransferManager.isCancelled(tid)) break
                        if (TransferManager.isPaused(tid)) {
                            val settled = TransferManager.awaitUnpausedOrYield(tid, UPLOAD_PAUSE_SETTLE_MS)
                            if (settled && TransferManager.isPaused(tid) && !TransferManager.isCancelled(tid)) {
                                // Drain the committed buffers first so the
                                // checkpoint is the DURABLE byte count, never
                                // the faster-moving reader figure.
                                durableTotal = try { pipe.finish() } catch (e: Exception) { durableTotal }
                                TransferManager.clearPauseSlotDebt(tid)
                                if (wholeFileLen > 0L && durableTotal > 0L) {
                                    resumableUploads[effectiveReqId] = ResumableUpload(
                                        staging, dest, wholeFileLen, durableTotal, tid, pendingId, ip,
                                        System.currentTimeMillis() + PARK_RESUME_TTL_MS, parked = true)
                                    notifyService(done = true, doneTid = tid)
                                    return jsonErrClose("Paused by host")
                                }
                                // Nothing durable yet — the row returns to
                                // Waiting (still flagged paused, so it shows
                                // Paused with its identity intact) and the
                                // sender's fresh re-POST under the same
                                // request id claims THIS row again.
                                TransferManager.demoteToPendingForPause(tid)
                                notifyService(done = true, doneTid = tid)
                                return jsonErrClose("Paused by host")
                            }
                            // The pause lifted inside the settle window — a
                            // quick toggle: keep streaming in place on this
                            // same connection, exactly as before.
                        }
                        val buf = pipe.borrow()
                        val toRead = if (remaining < 0L) buf.size
                                     else minOf(buf.size.toLong(), remaining).toInt()
                        // ── GOAT-PASS 5 (§9 — the reconnect restart-from-zero, ──
                        // root fix): a network-level read failure (connection
                        // reset, or the 5 s socket timeout after a silent
                        // drop — the NORMAL shape of a Wi-Fi/hotspot
                        // interruption) used to propagate straight to the
                        // outer catch, which DELETED the staging partial and
                        // failed the row: the browser's auto-reconnect then
                        // honestly found "no resumable upload" and restarted
                        // the whole file from byte zero (the reported
                        // laptop↔phone regression — progress bar reset,
                        // duplicate containers). The read failure now breaks
                        // the loop exactly like a clean EOF: the drain below
                        // lands the durable checkpoint and the interrupted
                        // tail registers it, so the sender's reconnect
                        // resumes from the last verified byte. Disk-side
                        // failures (borrow/commit/finish) still throw and
                        // take the honest destructive path.
                        val n = try { inp.read(buf, 0, toRead) } catch (e: java.io.IOException) { break }
                        if (n <= 0) { pipe.abandon(); break }
                        pipe.commit(n)
                        TransferManager.updateProgress(tid, pipe.readerBytes)
                        if (remaining > 0L) remaining -= n
                    }
                    // Complete = unknown-length (streaming) OR all bytes received —
                    // but a host-side cancel always wins over that ambiguity, since
                    // unlike a natural end-of-stream it's unambiguous either way.
                    // A resume leg has a known total by construction, so it can
                    // ONLY complete by receiving every remaining byte.
                    streamCompleted = !TransferManager.isCancelled(tid) &&
                        (if (resumeRec != null) remaining <= 0L else (contentLength < 0L || remaining <= 0L))
                    if (streamCompleted) {
                        // Drains every committed buffer to storage, trims any
                        // stale tail so the finished file is EXACTLY the
                        // declared total, and returns the durable count —
                        // completion may only be declared from here. Throws
                        // the captured disk failure to the outer catch (honest
                        // Failed + no resume record) if the tail never landed.
                        durableTotal = pipe.finish(if (wholeFileLen >= 0L) wholeFileLen else -1L)
                    } else if (!TransferManager.isCancelled(tid) && wholeFileLen > 0L) {
                        // GOAT-PASS 2 (§sync — durable checkpoints): a NON-cancel
                        // interruption of a known-total upload drains the queue
                        // NOW, while the buffers are still in flight — the
                        // checkpoint registered below must be the DURABLE byte
                        // count, never the faster-moving in-memory progress
                        // figure (a displayed percentage is not proof of durable
                        // data), and the finally-Abort below would drop every
                        // undrained buffer. A host cancel never drains — the
                        // cancel contract is stop-fast, and its partial is
                        // deleted anyway. A drain that hits the disk failure
                        // falls through with the honest base count and the
                        // registration below simply declines (partial deleted).
                        // (Both legs of a re-POST stream identical bytes of the
                        // identical file from the identical offset, so draining
                        // here remains byte-safe even if a newer leg has since
                        // re-opened the row — the interleave writes the same
                        // content at the same positions, and only a COMPLETED
                        // drain ever trims.)
                        try { durableTotal = pipe.finish() } catch (e: Exception) {
                            durableTotal = 0L   // no checkpoint off a broken disk
                        }
                    }
                } finally {
                    // Bounded lifetime for the writer thread on EVERY exit:
                    // a no-op after a successful finish(), a fast drain-drop
                    // on cancel, and the safety net for unexpected exceptions.
                    pipe.abort()
                }
                // Hard-delete any partial staging file left by a CANCELLED or
                // unresumable upload — an incomplete file must never be visible
                // in the destination folder or the media library.
                if (!streamCompleted) {
                    val hostCancelled = TransferManager.isCancelled(tid)
                    // Pass-8 (§7): a non-cancel abort of the sender's stream is
                    // an interruption — the partial is kept for the sender's
                    // Reconnect, and the host's row reads Interrupted instead
                    // of the misleading hard Failed.
                    TransferManager.fail(tid, interrupted = !hostCancelled)   // resolves to CANCELLED (host intent) vs FAILED internally
                    notifyService(done = true, doneTid = tid)
                    if (hostCancelled) {
                        // Deliberate cancellation: the partial AND the resume
                        // record are destroyed immediately — an explicit cancel
                        // must never resurrect (mirrored client-side by
                        // request-cancel dropping the record too).
                        // GOAT-PASS 5 (§8): Connection: close — the body is
                        // deliberately unfinished and the socket must die with
                        // the verdict instead of idling in NanoHTTPD's
                        // next-request read.
                        staging.delete()
                        resumableUploads.remove(effectiveReqId)
                        return jsonErrClose("Cancelled by host")
                    }
                    // v1.0 (§hotspot-off): a NON-cancel interruption — hotspot
                    // off, Wi-Fi drop, connection reset — KEEPS the staging
                    // partial and registers it for the reconnect window so
                    // the sender's Reconnect continues from this exact byte.
                    // The transfer row already fail()ed above and now lingers
                    // the full reconnect window (TransferManager), so BOTH
                    // sides keep a visible, actionable row — never again "fail
                    // and instantly disappear from both sides". GOAT-PASS 2:
                    // durableTotal was drained INSIDE the try above (see its
                    // comment) — it is the writer-verified byte count that the
                    // resume-offset endpoint's staging-length validation will
                    // agree with byte-exactly.
                    if (wholeFileLen > 0L && durableTotal > 0L) {
                        resumableUploads[effectiveReqId] = ResumableUpload(
                            staging, dest, wholeFileLen, durableTotal, tid, pendingId, ip,
                            System.currentTimeMillis() + RESUMABLE_UPLOAD_TTL_MS)
                        // GOAT-PASS 5 (§8): close the socket with the verdict.
                        return jsonErrClose("Upload interrupted — reconnect to resume")
                    }
                    // Unknown-length streams can't be validated on resume —
                    // the honest old contract applies.
                    staging.delete()
                    return jsonErrClose("Upload interrupted — partial file deleted")
                }
                // ── Pass-17 (one card per transfer): a fresh leg captured the ──
                // row's resume generation at its start (legResumeGen, below) —
                // if a NEWER leg has since re-opened this same row (the
                // reconnect machinery resolving the interrupted row in place),
                // this leg's failure must not clobber the live resumed
                // transfer: skip the fail() and the resumable-registration
                // entirely and answer honestly.
                if (legResumeGen != TransferManager.resumeGenOf(tid)) {
                    notifyService(done = true, doneTid = tid)
                    return jsonErr("Superseded by a newer transfer leg")
                }
                // Success: a resumed upload's record is obsolete (its staging
                // just became the real file) — drop it now so a stale record
                // can never point at a completed file.
                if (resumeRec != null) resumableUploads.remove(effectiveReqId)
                // The final name was picked before any bytes flowed; another
                // concurrent upload may have claimed it in the meantime —
                // re-run the same collision scan at rename time so this
                // upload never silently overwrites a completed file.
                val finalDest = uniqueDestinationFor(dest)
                if (!staging.renameTo(finalDest)) {
                    // Same-dir rename failing is near-impossible, but the
                    // copy+delete fallback (same as the multipart path) keeps
                    // the upload from failing over a filesystem quirk.
                    try { staging.copyTo(finalDest, overwrite = true); staging.delete() }
                    catch (e: Exception) {
                        staging.delete()
                        notifyService(done = true, doneTid = tid)
                        TransferManager.fail(tid)
                        return jsonErr("Could not finalize upload")
                    }
                }
                landedFile = finalDest
                if (finalDest != dest) {
                }
            }
        } catch (e: Exception) {
            // Path 2's partial bytes live in the staging file (dest was never
            // written directly); the multipart path's partial can only be a
            // failed temp→dest copy. Clean up whichever one this upload was
            // actually using — never both blindly, and never a file this
            // upload didn't create.
            stagingFile?.delete() ?: dest.delete()
            // v1.0 (§hotspot-off): an exception mid-upload is a genuine
            // server-side fault, not a network interruption — the resumable
            // record (if any) goes with the partial, so a later Reconnect
            // gets an honest refusal instead of appending onto a dead file.
            resumableUploads.remove(effectiveReqId)
            notifyService(done = true, doneTid = tid)
            TransferManager.fail(tid)
            // GOAT-PASS 5 (§8): close the socket with the verdict.
            return jsonErrClose("Upload error: ${e.message}")
        } finally {
            // Released as soon as bytes stop flowing, whichever way this
            // upload ends — the remaining work below (media scan, marking
            // complete) doesn't hold a peer waiting on the network, so it
            // shouldn't hold the next queued file's slot either.
            // Phase 1: ALWAYS under the key the slot was acquired with —
            // pendingId, or the re-keyed fresh row (GOAT-PASS 5 §7: the
            // start() fallback mints its row after acquisition, and the
            // registration moved with it; releasing the dead id there would
            // no-op against a key that no longer holds the slot, leaking the
            // permit permanently).
            TransferManager.releaseReceivingSlot(slotKey)
        }

        if (!landedFile.exists() || landedFile.length() == 0L) {
            notifyService(done = true, doneTid = tid)
            TransferManager.fail(tid)
            return jsonErr("Upload produced an empty file")
        }

        // ── GOAT-PASS 15 (§2 — the same prompt completion tail as the native
        // path): everything the upload contract needs (sender memory, the
        // progress notification's done-pass, the row's COMPLETED resolution)
        // runs before the response; the MediaProvider scan and the coalesced
        // storage nudge move to the arrival executor, off the wire's critical
        // path. A browser pushing a 27-file batch gets its next request
        // answered the instant each file is durable, instead of behind every
        // scan-and-rebuild of the batch so far.
        // ── Pass-15 (no more "Unknown sender"): remember the delivering ──
        // browser's display name for this exact file, so the disk-reconciled
        // Received/History rows of this and every future session render the
        // real label instead of a synthesized "Unknown sender".
        com.lumidrop.x.transfer.ReceivedFileMeta.rememberSender(context, landedFile.absolutePath, peerName)
        notifyService(done = true, doneTid = tid)
        // ── FINAL PASS 3 (§1 — the real size of a completed streamed upload): ──
        // a browser ZIP-mode upload is chunk-streamed with no Content-
        // Length, so its row carries totalBytes = -1 and would render "?"
        // in Received/History after completion. The finalized file's own
        // length is the actual final size — stamp it (no-op for ordinary
        // uploads whose wholeFileLen was already known).
        if (wholeFileLen < 0L) TransferManager.stampFinalSize(tid, landedFile.length())
        TransferManager.complete(tid)
        deferArrivalSideEffects(landedFile.absolutePath, landedFile.absolutePath)
        return json(JSONObject().put("success", true).put("name", landedFile.name).put("size", landedFile.length()))
    }

    // ── §2 Reconnect: a FAILED download's resumable re-request URL ─────────────
    // The web client renders a Reconnect action on interrupted receive rows.
    // The client cannot rebuild that URL itself (the engine's snapshot payload
    // deliberately carries only identity+state, never paths — see qstate's
    // own doc), so the SERVER resolves it from the transfer's own record:
    //   • QUICK_SHARE → /api/qs/dl?n=<qsItemName>  (the failed delivery's QS
    //     chip was re-armed on failure — see the re-arm sweep — so the item
    //     is back in the queue and the ordinary download path applies);
    //   • PLAIN with a filePath that still exists → /api/download?path=…
    //     (a fresh claim under a fresh id; browser download managers may
    //     then byte-resume from their own partial via Range, which
    //     serveRange already honors);
    //   • anything else (zip streams whose source selection is gone, native
    //     pushes owned by the sender's own retry loop) → an honest refusal,
    //     never a fabricated URL.
    // Only FAILED rows are reconnectable by design: an explicitly CANCELLED
    // transfer must stay cancelled (its partials are already deleted and its
    // QS chip re-arm is the user's deliberate next choice, not an automatic
    // resurrection), and a COMPLETED one has nothing left to reconnect.
    private fun apiTransferReconnect(s: IHTTPSession): Response {
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        parseFormBodyBounded(s, "tid")
        val tid = s.parameters["tid"]?.firstOrNull() ?: return jsonErr("No tid")
        val t = TransferManager.get(tid) ?: return jsonErr("Unknown transfer")
        // Identity: only the transfer's own receiving client may reconnect it.
        if (ip != "unknown" && ip != t.peerIp) return jsonErr("Not your transfer")
        if (t.direction != TransferManager.Direction.DOWNLOAD) return jsonErr("Not a receive")
        if (t.status != TransferManager.Status.FAILED) return jsonErr("Not reconnectable")
        val url = when (t.source) {
            TransferManager.Source.QUICK_SHARE -> t.qsItemName?.let {
                "/api/qs/dl?n=" + URLEncoder.encode(it, "UTF-8") }
            TransferManager.Source.PLAIN -> t.filePath?.let { p ->
                val f = File(p)
                if (f.exists() && f.isFile) "/api/download?path=" + URLEncoder.encode(f.absolutePath, "UTF-8") else null
            }
            else -> null
        } ?: return jsonErr("No longer available")
        return json(JSONObject()
            .put("success", true)
            .put("url", url)
            .put("name", t.fileName))
    }

    // ── Phase 14: native device-to-device push (receiving side) ────────────────
    // A native peer's OWN app — not a browser — is the HTTP client here,
    // pushing files onto this device via writeRawStream() below, the same
    // chunked-with-Pause/Cancel shape as apiUpload's Path 2 raw-stream
    // branch just above. The two-step request→accept→stream flow mirrors
    // PendingUpload/apiUploadRequest exactly, just batched: ONE host
    // approval covers a whole folder/multi-select, because gating per-file
    // for a 500-photo folder push would be unusable. See NativePushClient
    // (p2p package) for the sending side of this same protocol.

    data class NativePushEntry(val path: String, val size: Long, val isDir: Boolean,
                                 val isText: Boolean = false, val text: String? = null,
                                 // Pass-15 (resume from a different sender, same file only): the
                                 // sender's source-file mtime, declared in the manifest. Two
                                 // different devices sending the SAME original file declare the
                                 // same (size, mtime) identity — a same-named DIFFERENT file
                                 // does not — which is exactly what lets the receiver adopt an
                                 // interrupted partial across senders without ever stitching a
                                 // different file onto another file's prefix. 0 = legacy sender
                                 // that declares nothing (the old best-effort rules apply).
                                 val mtime: Long = 0L,
                                 // ── Pass-16 (cross-sender resume, content identity) ─────────
                                 // SHA-256 of the first 4 KB of the source file, declared by the
                                 // sender in the manifest AND on every file-status probe. mtime
                                 // can differ across devices holding the very same original
                                 // file (a copy stamps its own mtime) — CONTENT cannot lie: the
                                 // same bytes hash the same everywhere, a different file does
                                 // not. The receiver validates it directly against the partial
                                 // it is actually holding (stagingFingerprintMatches), so an
                                 // fp-validated resume never stitches mismatched bytes even
                                 // when both senders' metadata agrees only by coincidence.
                                 // Null = legacy sender — the mtime rules keep applying.
                                 val fp: String? = null)

    /** GOAT (Part 1 correction #4 — maximum realistic incoming speed): a
     *  top-level item as announced BEFORE any filesystem/SAF enumeration
     *  has happened — just its name and whether it's a folder, both
     *  already known to the sender's own picker UI with zero I/O. Shown
     *  while [PendingNativePush.manifestReady] is false; superseded
     *  entirely once the real manifest (with real per-file entries) lands.
     *  Phase 6 (§6.6): isText lets the preparing row already present itself
     *  as a text message rather than a mystery file.
     *  ── Pass-16 (the placeholder renders the REAL text design) ──────
     *  [text] carries the message content itself — the sender already has
     *  it at announce time — so even the sub-second placeholder phase shows
     *  the true message card (snippet, character count, Copy/Cancel
     *  buttons) instead of the generic file container the reported
     *  "sometimes a text arrives looking like a file item" was: the
     *  placeholder branch of buildPushItemRows renders this exact shape. */
    data class AnnouncedItem(val name: String, val isFolder: Boolean, val isText: Boolean = false,
                              val text: String? = null)

    // ── Pass-15 (ghost-item ask-to-resend): receiver-side discard receipts ──
    // Discarding a kept-for-resume "Waiting for reconnection" card (the X →
    // confirm in the Transfers tab) deletes the partial AND means "I do not
    // want this data anymore" — but the SENDER, still inside its retry
    // window with its app open, could not tell a deliberate discard from a
    // lost partial: its next checkpoint probe answered 0 and it silently
    // re-sent the whole file from 0%. The receipt below makes the discard
    // answerable by the composite push request id AND by destination path,
    // so the probe can report `discarded: true` and the SENDER can ask its
    // user — "Send again from the beginning, or cancel?" — before a single
    // byte is re-sent. A confirmed resend (the sender's user said yes, the
    // POST carries confirmed=1) clears the receipt on arrival; the TTL is
    // the same 10-minute window the sender's retry loop lives in.
    private val discardedReceiveByRequest = ConcurrentHashMap<String, Long>()
    private val discardedReceiveByPath = ConcurrentHashMap<String, Long>()
    private val DISCARD_RECEIPT_TTL_MS = 10 * 60_000L
    private val DISCARD_RECEIPT_CAP = 64

    /** Pass-15: records that the receiver deliberately discarded the partial
     *  for [requestId] / [destPath]. Called from the Transfers tab's discard
     *  confirmation right after the files themselves are deleted. */
    fun rememberDiscardedReceive(requestId: String?, destPath: String?) {
        val now = System.currentTimeMillis()
        if (!requestId.isNullOrEmpty()) discardedReceiveByRequest[requestId] = now
        if (!destPath.isNullOrEmpty()) discardedReceiveByPath[destPath] = now
        // Cap + TTL trim — a handful of entries is the realistic ceiling, so
        // this only ever guards a pathological case. Iterator-based removal
        // (not Collection.removeIf — a Java-8 default method absent from
        // API 21-23 platform classes; the same discipline used everywhere
        // else in this codebase).
        if (discardedReceiveByRequest.size > DISCARD_RECEIPT_CAP || discardedReceiveByPath.size > DISCARD_RECEIPT_CAP) {
            val cutoff = now - DISCARD_RECEIPT_TTL_MS
            run {
                val iter = discardedReceiveByRequest.entries.iterator()
                while (iter.hasNext()) if (iter.next().value < cutoff) iter.remove()
            }
            val iter2 = discardedReceiveByPath.entries.iterator()
            while (iter2.hasNext()) if (iter2.next().value < cutoff) iter2.remove()
        }
    }

    private fun discardReceiptLive(at: Long?): Boolean {
        if (at == null) return false
        return System.currentTimeMillis() - at < DISCARD_RECEIPT_TTL_MS
    }

    fun wasReceiveDiscarded(requestId: String, destPath: String): Boolean {
        // ── Pass-16 ("Send Again?" modal on a fresh app restart, root fix) ────
        // The lookup used to ALSO match a destPath-only receipt — a NEW
        // session (the sender restarted its app and deliberately re-sent the
        // same file) resolved the SAME destination path and got answered
        // "discarded", so the fresh send was blocked behind the "Send again
        // from the beginning?" modal that belongs ONLY to the OLD session's
        // auto-resume retry. The composite request id ("pushId::relPath") is
        // unique per push session, so keying the ANSWER strictly on it gives
        // exactly the required behavior: the still-open retry window of the
        // SAME session gets the question, and a genuinely new transfer —
        // same file, same path, new session — is treated as the completely
        // fresh transfer it is, with no modal. The path-keyed map is still
        // written (and cleared) so the bookkeeping stays symmetric, but it
        // no longer participates in any refusal decision.
        return discardReceiptLive(discardedReceiveByRequest[requestId])
    }

    /** Pass-15: clears the receipts — the moment a CONFIRMED resend (or any
     *  genuine fresh start of this file) begins, the old discard decision is
     *  consumed; leaving it would refuse the very transfer the user just
     *  approved. */
    fun clearDiscardedReceive(requestId: String, destPath: String) {
        discardedReceiveByRequest.remove(requestId)
        discardedReceiveByPath.remove(destPath)
    }

    class PendingNativePush(
        val id: String,
        val fromDeviceId: String,
        val fromName: String,
        val fromIp: String,
        val fromPort: Int,   // sender's own LumiDropServer port — where its /api/native/push/control endpoint lives, for state-sync propagation back to it
        val entries: List<NativePushEntry>,   // ORIGINAL paths — exactly what the sender will keep referencing in every apiNativePushFile/apiNativePushFileStatus call
        val totalBytes: Long,
        val destRoot: File,
        // Empty in the overwhelmingly common case (no name already existed).
        // Only populated per top-level name that actually collided — see
        // apiNativePushRequest. Every write for this push must resolve
        // through resolvePath() below rather than using entries' paths
        // directly, or a renamed top-level folder would silently diverge
        // from where its files actually get written.
        val topLevelRenames: Map<String, String> = emptyMap(),
        val createdAt: Long = System.currentTimeMillis(),
        // GOAT (Part 1 correction #4): true once real [entries] (from
        // apiNativePushRequest) have arrived. False only for the brief
        // placeholder window right after apiNativePushAnnounce creates
        // this with no real entries yet — [entries]/[totalBytes]/
        // [topLevelNames]/[itemDecisions] are all meaningless (empty)
        // until this flips true, which happens by apiNativePushRequest
        // REPLACING this object (same [id], in [pendingNativePushes]) with
        // a fully-populated one — not by mutating this one in place, since
        // topLevelNames/itemDecisions/status/totalFiles below are all
        // vals derived once at construction from entries. Defaults to
        // true so the ordinary single-phase construction path (a manifest
        // that arrives with no prior announce — see apiNativePushRequest's
        // own fallback) needs no changes to keep meaning what it always
        // meant.
        val manifestReady: Boolean = true,
        // Cheap "here's roughly what's coming" shown while manifestReady
        // is false — top-level names only, no sizes (unknown this early),
        // no nested structure. Ignored entirely once manifestReady is
        // true; the real entries/topLevelNames below are authoritative
        // from that point on.
        val announcedTopLevel: List<AnnouncedItem> = emptyList(),
    ) {
        // AtomicInteger, not a @Volatile var incremented with ++: up to
        // MAX_CONCURRENT_FILES uploads for this same session can complete on
        // different NanoHTTPD worker threads at nearly the same moment, and
        // "read, add one, write" is not atomic just because the field is
        // volatile — two concurrent increments can silently collapse into
        // one, permanently short-circuiting the >= totalFiles check below
        // and the "push complete" notification never firing.
        val filesDone = java.util.concurrent.atomic.AtomicInteger(0)

        /** Pass-8 follow-up (auto-accept sync): last time the SENDER touched
         *  this push (file POST, resume probe, or status poll). An accepted
         *  push whose sender went silent past NATIVE_PUSH_TTL with files
         *  still undelivered is dead — its never-claimed pre-registered
         *  rows must resolve FAILED (interrupted) instead of sitting
         *  "Waiting" forever. Bumped from apiNativePushFile,
         *  apiNativePushFileStatus and apiNativePushStatus. */
        @Volatile var lastActivityAt: Long = System.currentTimeMillis()

        /** GOAT v2 (item 2): the unit the receiver actually accepts or
         *  declines against — a top-level folder's own name for anything
         *  nested under it, or a loose file's own name if it isn't inside
         *  any folder. Gating per FILE would make a 500-photo folder push
         *  unusable (the reasoning the original single-approval design was
         *  built around); gating per whole-push made "accept File A,
         *  decline File B" impossible. A top-level item is the middle
         *  ground: one decision per item exactly as the sender's own
         *  selection presented it, via itemDecisions below. */
        val topLevelNames: List<String> = entries.map { it.path.substringBefore('/') }.distinct()

        /** One decision per top-level name: "pending" | "accepted" |
         *  "declined". Seeded "pending" for everything at construction —
         *  apiNativePushRequest immediately fast-forwards every entry to
         *  "accepted" via acceptNativePush() when auto-accept is on, the
         *  same path a manual Accept All takes, rather than duplicating
         *  that bookkeeping here. A file only ever streams once its OWN
         *  top-level name resolves to "accepted" (see apiNativePushFile),
         *  and precreateNativePushDirs is filtered the same way, so a
         *  folder the host declines never has so much as an empty
         *  directory show up for it. */
        val itemDecisions = ConcurrentHashMap<String, String>().apply {
            topLevelNames.forEach { put(it, "pending") }
        }

        // ── Pass-14 (stuck "Preparing…" root fix): placeholder pre-decisions.
        // A manifest-not-yet-arrived placeholder (manifestReady == false) used
        // to render its incoming-sheet rows as inert "Preparing…" rows with
        // NO Accept/Cancel controls — and when the sender died between its
        // announce and its manifest (or the manifest POST was lost), those
        // rows sat stuck for the full 3-minute TTL with nothing the host
        // could do about it. The incoming sheet now wires placeholder rows
        // to the SAME per-item accept/decline the real rows use; on a
        // placeholder that decision is recorded HERE, and apiNativePushRe-
        // quest applies it to the real push the moment the manifest lands
        // (same accept path a post-manifest tap takes — dirs pre-created,
        // PENDING rows registered, sender's progressive dispatch sees it on
        // its next poll). Keyed by top-level name; "accepted"/"declined",
        // absent = still undecided.
        val preDecisions = ConcurrentHashMap<String, String>()

        /**
         * GOAT (Part 2 completion — folder accept/decline, "individual
         * internal files can be managed"): natural relative paths (e.g.
         * "Subfolder/IMG_02.jpg", or just "Subfolder" for an entire nested
         * folder) the host removed from an otherwise-accepted top-level
         * folder, BEFORE or after accepting it — keyed by that folder's
         * top-level name. Mutable and separate from [itemDecisions]
         * deliberately: declining a nested path doesn't change whether its
         * PARENT top-level item is "accepted", it just narrows what gets
         * delivered out of it, mirroring how PreviewItem.excludedChildPaths
         * works on the sending side for the exact same reason.
         * registerPendingTransfersFor skips creating a PENDING transfer for
         * anything excluded here; apiNativePushFile independently checks it
         * too (see isExcludedNested) so a sender that doesn't know about
         * this yet — nothing in the wire protocol currently tells it —
         * still can't have an excluded file actually written.
         */
        val excludedNestedByTopLevel = ConcurrentHashMap<String, MutableSet<String>>()

        // ── GOAT-PASS 10 (§4 — receiver-side folder cancellation, structure ──
        // preservation): the top-level-name → folder-GROUP map minted by
        // registerPendingTransfersFor, kept ON the push record so any LATER
        // row minted for one of this push's files (apiNativePushFile's
        // claim-chain start() fallback — taken when the pre-registered row
        // was already consumed/cancelled/swept) can be re-attached to the
        // SAME folder group instead of appearing as a detached, parentless
        // top-level card next to its own folder. Keyed by the ORIGINAL
        // top-level name (pre-rename), exactly like itemDecisions.
        val groupIdByTopLevel = ConcurrentHashMap<String, String>()

        fun isExcludedNested(path: String): Boolean {
            val topName = path.substringBefore('/')
            val excluded = excludedNestedByTopLevel[topName] ?: return false
            return path in excluded || excluded.any { path.startsWith("$it/") }
        }

        /** Aggregate view for the sender's status poll and the existing
         *  whole-push notification/list code: "pending" while anything is
         *  still undecided, "accepted"/"rejected" once every item agrees,
         *  "partial" once every item is decided but not unanimously — see
         *  apiNativePushStatus for how the sender then learns exactly which
         *  top-level names to skip.
         *
         *  Pass-8 follow-up (auto-accept sync fix): an EMPTY decisions map
         *  is the announce-placeholder state (manifestReady == false, no
         *  entries yet) — it used to fall through Kotlin's vacuous-truth
         *  `v.all { it == "declined" }` and report "rejected", which made
         *  the placeholder invisible to pendingNativePushList() (the
         *  incoming sheet), instantly clearable from the pending
         *  notification, and sweepable by the "fully done" branch of
         *  sweepStalePendingNativePushes. An undecided-everything push —
         *  placeholder or not — reads "pending". */
        val status: String
            get() {
                val v = itemDecisions.values
                return when {
                    v.isEmpty()               -> "pending"
                    v.any { it == "pending" } -> "pending"
                    v.all { it == "declined" } -> "rejected"
                    v.all { it == "accepted" } -> "accepted"
                    else -> "partial"
                }
            }

        val declinedTopLevel: List<String> get() =
            itemDecisions.filterValues { it == "declined" }.keys.toList()

        /** How many files this push will actually deliver — i.e. excluding
         *  anything under a top-level item that's been declined. This
         *  shrinks as items get declined, which is exactly why
         *  filesDone-vs-this is still the right "is this push actually
         *  finished" check once some items were turned down; entries under
         *  a still-undecided item are conservatively still counted, since
         *  they may yet be accepted. */
        val totalFiles: Int get() = entries.count {
            !it.isDir && itemDecisions[it.path.substringBefore('/')] != "declined" && !isExcludedNested(it.path)
        }

        /** Maps a manifest-relative path — exactly as the sender references
         *  it in every request for this push — to where it actually lands
         *  on disk, applying this push's top-level collision-safe rename
         *  if one was needed. precreateNativePushDirs, apiNativePushFile,
         *  and apiNativePushFileStatus all resolve through this so they can
         *  never disagree about the real location. */
        fun resolvePath(originalPath: String): String {
            if (topLevelRenames.isEmpty()) return originalPath
            val slash = originalPath.indexOf('/')
            val top = if (slash >= 0) originalPath.substring(0, slash) else originalPath
            val resolved = topLevelRenames[top] ?: return originalPath
            return if (slash >= 0) resolved + originalPath.substring(slash) else resolved
        }
    }

    // ── Transfer state sync (Pause/Resume/Cancel propagation) ──────────────────
    // Each native-push file transfer exists as TWO independent local Transfer
    // objects — one per device, in separate TransferManager instances with no
    // shared state. A control action tapped on either side must reach the
    // other explicitly; this pair of maps is what makes that possible in
    // both directions using each side's already-known local tid, without
    // either side needing to track the OTHER's tid (which it never sees).
    private class NativeControlLink(val pushId: String, val relPath: String, val peerBaseUrl: String)
    /** local tid -> where/how to reach the peer's copy of this same transfer
     *  (used when THIS device's own UI pauses/resumes/cancels it). */
    private val nativeControlByTid = ConcurrentHashMap<String, NativeControlLink>()
    /** "pushId:relPath" -> local tid (used when a control request ARRIVES
     *  from the peer, referencing the transfer by the manifest path both
     *  sides agree on rather than a tid neither side shares). */
    private val nativeControlByKey = ConcurrentHashMap<String, String>()

    // GOAT (Phase 1 continuation, item 4 root cause): these were both
    // `private`, callable only from apiNativePushFile -- i.e. only ever
    // registered on the RECEIVING side of a transfer. That's exactly why
    // a receiver-initiated cancel showed up as Failed on the sender: the
    // sender's own LumiDropServer (every device runs one, since P2P is
    // symmetric) had nothing in nativeControlByKey for its own outgoing
    // files, so the receiver's propagateNativeControl POST to the
    // sender's apiNativePushControl endpoint found no match and the
    // "cancel" was silently dropped -- the sender's local Transfer never
    // got cancelRequested=true, so when the connection then broke (the
    // receiver aborting its read), NativePushClient.sendOneFile's own
    // try/catch saw an ordinary IOException with no cancel intent
    // recorded, and TransferManager.fail() correctly-but-wrongly resolved
    // that to FAILED. No longer private so NativePushClient can register
    // the same link for a file it's SENDING (see its sendOneFile), giving
    // every native-push transfer a control link on BOTH ends regardless
    // of which side is sending vs receiving THIS particular file.
    fun registerNativeControlLink(tid: String, pushId: String, relPath: String, peerBaseUrl: String) {
        val link = NativeControlLink(pushId, relPath, peerBaseUrl)
        nativeControlByTid[tid] = link
        nativeControlByKey["$pushId:$relPath"] = tid
    }
    fun unregisterNativeControlLink(tid: String) {
        val link = nativeControlByTid.remove(tid) ?: return
        nativeControlByKey.remove("${link.pushId}:${link.relPath}")
    }

    /** ── GOAT-PASS 8 (§11): is the SENDER of this native-push receive row ──────
     *  provably unreachable right now? One tiny GET /api/ping at the sender's
     *  own LumiDropServer (the exact convention isSendPeerAlive uses on the
     *  other side of the wire): ANY HTTP response code proves the sender's
     *  app is alive on the network; only a connect/read exception means
     *  "gone". The sender's address rides the push's control link — a row
     *  with no link (never streamed, already finished) answers ALIVE so the
     *  stall watchdog keeps its honest default. Called synchronously by the
     *  watchdog's timer thread at most once per stalled row per cycle, with
     *  tight timeouts for the same reason. */
    fun isNativePushSenderAlive(tid: String): Boolean {
        val link = nativeControlByTid[tid] ?: return true   // unresolvable → assume alive
        var conn: HttpURLConnection? = null
        return try {
            val c = com.lumidrop.x.utils.PeerHttp.open(context, URL("${link.peerBaseUrl}/api/ping")) as HttpURLConnection
            c.requestMethod = "GET"
            c.connectTimeout = 1300
            c.readTimeout = 1300
            conn = c
            c.responseCode        // any answer = alive
            true
        } catch (e: Exception) { false } finally { conn?.disconnect() }
    }

    /** Best-effort propagation of a LOCAL pause/resume/cancel out to the
     *  peer on the other end of this same transfer, so both Transfers tabs
     *  agree. Fire-and-forget by design (see class doc note in
     *  TransfersFragment): this device's own action already happened via
     *  the normal TransferManager call it accompanies, so there's nothing
     *  more useful to do on failure than leave that as the outcome. */
    fun propagateNativeControl(tid: String, action: String) {
        val link = nativeControlByTid[tid]
        if (link != null) {
            controlExecutor.submit {
                var conn: HttpURLConnection? = null
                try {
                    val url = "${link.peerBaseUrl}/api/native/push/control?id=${link.pushId}" +
                        "&path=${URLEncoder.encode(link.relPath, "UTF-8")}&action=$action"
                    // Pass-3 carryover fix (re-applied Pass-5): a nullable `conn`
                    // captured by this closure can't smart-cast at the
                    // responseCode read — configure and read through a non-null
                    // local, keep the nullable handle only for the finally.
                    val c = com.lumidrop.x.utils.PeerHttp.open(context, url) as HttpURLConnection
                    c.requestMethod = "POST"; c.connectTimeout = 4000; c.readTimeout = 4000
                    conn = c
                    c.responseCode // triggers the request; response body is irrelevant here
                } catch (e: Exception) { /* peer unreachable / already finished — best-effort only */ }
                finally { conn?.disconnect() }
            }
            return
        }
        // ── Pass-19 (Pause-All state sync, receiver→sender for WAITING rows) ──
        // No control link means this row never started streaming — until now
        // that made the RECEIVER's own pause/resume of a still-Waiting file a
        // silent local-only action: the sender never heard, its row stayed
        // unpaused, and the two Transfers tabs disagreed. The row's composite
        // "pushId::relPath" request id resolves the push record (which has
        // the sender's ip:port from the manifest), so the very same per-file
        // control call the streaming path makes now also reaches WAITING
        // files — the sender's handler resolves it through its own session
        // index (NativePushClient.tidForControl) and marks its mirror row.
        val t = TransferManager.get(tid) ?: return
        val reqId = t.uploadRequestId ?: return
        val pushId = reqId.substringBefore("::")
        val relPath = reqId.substringAfter("::", "")
        if (relPath.isEmpty()) return
        val push = pendingNativePushes[pushId] ?: return
        val baseUrl = "http://${push.fromIp}:${push.fromPort}"
        controlExecutor.submit {
            var conn: HttpURLConnection? = null
            try {
                val url = "$baseUrl/api/native/push/control?id=${URLEncoder.encode(pushId, "UTF-8")}" +
                    "&path=${URLEncoder.encode(relPath, "UTF-8")}&action=$action"
                val c = com.lumidrop.x.utils.PeerHttp.open(context, url) as HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = 4000; c.readTimeout = 4000
                conn = c
                c.responseCode
            } catch (e: Exception) { /* best-effort, same as the linked path */ }
            finally { conn?.disconnect() }
        }
    }

    /** Pass-3 (R12 — receiver-side folder cancel): the whole-push twin of
     *  [propagateNativeControl]. Called by the RECEIVING device's Transfers
     *  tab when the user cancels a folder group: the receiver asks the
     *  SENDER to stop every remaining file under the push id at once
     *  (scope=push), not just the handful of files already streaming (a
     *  per-file control only ever reaches dispatched files — pending ones
     *  would keep arriving, which is exactly the "cancelled the folder but
     *  files kept coming" bug).
     *
     *  Two resolution paths, because the receiver's rows know the push two
     *  different ways depending on how far the push got:
     *   1. A file already streaming has a NativeControlLink registered by
     *      apiNativePushFile (pushId + the sender's base URL).
     *   2. A still-PENDING file has no link yet, but its TransferManager row
     *      carries the "pushId::relPath" uploadRequestId — and THIS server's
     *      pendingNativePushes map knows the sender's ip/port for that push
     *      id straight from the manifest.
     *  Fire-and-forget, same as the per-file variant. */
    fun propagateNativeGroupControl(tid: String, action: String) {
        val link = nativeControlByTid[tid]
        if (link != null) {
            controlExecutor.submit {
                var conn: HttpURLConnection? = null
                try {
                    val url = "${link.peerBaseUrl}/api/native/push/control?id=${link.pushId}" +
                        "&action=$action&scope=push"
                    val c = com.lumidrop.x.utils.PeerHttp.open(context, url) as HttpURLConnection
                    c.requestMethod = "POST"; c.connectTimeout = 4000; c.readTimeout = 4000
                    conn = c
                    c.responseCode
                } catch (e: Exception) { /* best-effort, same as per-file */ }
                finally { conn?.disconnect() }
            }
            return
        }
        // No per-file link (the cancelled row is still PENDING): resolve the
        // push through the receiver-side manifest record instead.
        val t = TransferManager.get(tid) ?: return
        val reqId = t.uploadRequestId ?: return
        val pushId = reqId.substringBefore("::")
        val push = pendingNativePushes[pushId] ?: return
        val baseUrl = "http://${push.fromIp}:${push.fromPort}"
        controlExecutor.submit {
            var conn: HttpURLConnection? = null
            try {
                val url = "$baseUrl/api/native/push/control?id=${URLEncoder.encode(pushId, "UTF-8")}" +
                    "&action=$action&scope=push"
                // Pass-3 carryover fix (re-applied Pass-5): same smart-cast
                // repair as the two call sites above — configure and read
                // through a non-null local, keep the nullable handle only
                // for the finally.
                val c = com.lumidrop.x.utils.PeerHttp.open(context, url) as HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = 4000; c.readTimeout = 4000
                conn = c
                c.responseCode
            } catch (e: Exception) { /* peer unreachable / push already finished — best-effort */ }
            finally { conn?.disconnect() }
        }
    }


    val pendingNativePushes = ConcurrentHashMap<String, PendingNativePush>()

    // ── GOAT-PASS 11 (§7 — atomic, terminal whole-folder cancellation) ────────
    // THE ROOT FIX for the receiver-side folder-cancel corruption. The old
    // cancellation depended on per-REQUEST artifacts that a 694-file folder
    // destroys: the per-file upload tombstones (capped at 128 slots — 694
    // cancels evicted 566 of them on the floor MID-CANCEL) and the item
    // decisions on the push record (gone the moment the record is TTL-swept
    // or replaced). Every evicted/lost guard re-opened apiNativePushFile's
    // start() fallback, which minted a FRESH row under the folder's group
    // for each re-POST — the cancelled folder reappearing and climbing
    // ("24/25, 25/26, 26/27…") while the still-running sender fed it.
    //
    // The latch below is established FIRST, BEFORE any child cleanup, and
    // is keyed by the PUSH ID itself — one entry per folder, not one per
    // file, so no cap can evict it. Every gate a sender's request can hit
    // (file POST, checkpoint probe, repeat manifest) consults it. It lives
    // for the process lifetime: a cancelled push is never un-cancelled.
    private val cancelledPushIds = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** GOAT-PASS 11 (§7): the push-level cancelled latch. */
    private fun isPushCancelled(pushId: String): Boolean = cancelledPushIds.containsKey(pushId)

    // ── GOAT-PASS 13 (§6): the GOAT-PASS 11 §7 durable cancelled-FOLDER ──────
    // tombstone system (map, windows, cap, JSON persistence file
    // lumidrop_cancelled_folders.json and all four accessors) is REMOVED.
    // It was a folder-only second acceptance system layered on the ordinary
    // one, and it made Auto-Accept unreliable: a folder re-sent within
    // 90 s of any cancel was silently auto-DECLINED (the sender showed
    // Declined while nothing happened on the receiver), and for the rest of
    // a 10-minute window it forced the confirmation modal even with
    // Auto-Accept on — the exact reported inconsistencies. Folders now
    // follow the one ordinary acceptance flow (Auto-Accept ON ⇒ instant
    // accept; OFF ⇒ confirmation sheet), and the per-push latch above still
    // guards every in-flight session.

    /** GOAT-PASS 11 (§7): the background single-thread executor for a
     *  cancelled push's disk cleanup (staging partial deletes + the
     *  resumable-registry bulk rewrite). The ROWS resolve synchronously on
     *  the calling thread (fast, engine-only) so the UI reflects the
     *  cancel immediately; the file I/O that used to run 694 ×
     *  read-modify-write on the UI thread — the ANR behind the reported
     *  "cancellation crashes the receiver" — happens here instead. */
    private val pushCleanupExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "lumidrop-push-cleanup").apply { isDaemon = true }
    }

    private val NATIVE_PUSH_TTL = 3 * 60_000L
    // ── FP18 (§5): the receiving-slot queue-turn bound. Well under the
    // sender's 45-second read timeout so an honest "still queued" answer
    // always precedes any socket-timeout classification; long enough that an
    // ordinary slot handover (one in-flight file finishing) never pays a
    // 425 round trip at all.
    private val SLOT_WAIT_MAX_MS = 12_000L
    // Pass-10 (automatic resume): how long an ACCEPTED push tolerates a
    // silent sender before its record is swept. Now deliberately mirrors the
    // sender's own AUTO_RESUME_WINDOW (10 min) instead of the old 3 minutes:
    // a sender whose connection died mid-batch is backing off and probing,
    // and each successful probe refreshes lastActivityAt — but while the
    // network is DOWN no probe arrives at all, and a 3-minute TTL would
    // destroy the receiver's push record (and with it every resume
    // checkpoint) while the sender is still legitimately trying to come
    // back. 10 minutes matches exactly how long the sender will keep
    // trying, so the record lives precisely as long as the resume possibility
    // does — and not a minute longer.
    private val NATIVE_PUSH_DEAD_SENDER_MS = 10 * 60_000L
    // ── GOAT-PASS 7 (§2C): the extended grace for a "gone" sender whose push ──
    // still has undelivered staging bytes on disk (see sweepStalePending-
    // NativePushes). Three times the sender's own auto-resume window — long
    // enough that any reconnecting sender finds its checkpoint intact, short
    // enough that a truly dead session's record still clears the same hour.
    private val NATIVE_PUSH_RESUMABLE_GRACE_MS = 30 * 60_000L

    // ── Pass-14 (stuck "Preparing…" root fix): how long an announce-phase
    // placeholder (manifestReady == false) may wait for its follow-up
    // manifest before the sweep declares the sender gone. 90 seconds was
    // sized from "even a huge folder's manifest build finishes in
    // seconds-to-tens-of-seconds" — but on a genuinely slow sender (old
    // storage, a cold SAF provider, a multi-thousand-file tree) that build
    // can legitimately run minutes, and the pass-14 report of a
    // "very rare" stuck-Preparing send matches exactly that: the sweep ate
    // the placeholder — and with it every pre-decision the host had already
    // tapped — mid-build, so the manifest that finally landed re-asked
    // everything and the sender's rows sat "Waiting" for a decision the
    // host had already given. 5 minutes still bounds a genuinely dead
    // sender generously below the push TTL while covering real-world slow
    // builds; the incoming sheet rows are actionable during the whole wait
    // (preDecisions), so the window is never a dead end either way.
    private val NATIVE_PUSH_PLACEHOLDER_TTL_MS = 5 * 60_000L

    // ── v1.0 (§hotspot-off): byte-level resumable web→host uploads ────────────
    // The old contract deleted the staging partial on ANY interruption, so a
    // transfer severed by the hotspot dying was honestly un-resumable and
    // could only be restarted from byte 0 — while ALSO vanishing from both
    // screens almost instantly, which read as plain data loss. The new
    // contract: a NON-cancel interruption (network death — hotspot off,
    // Wi-Fi drop, connection reset) keeps the staging partial and registers
    // it here for the reconnect window; the web client's Reconnect asks
    // /api/upload/resume for the byte offset and re-sends ONLY the remainder,
    // which this server appends to the SAME staging file and finalizes under
    // the transfer's ORIGINAL id. An explicit cancel (host Cancel, client
    // Remove/Cancel) still deletes the partial and the record immediately —
    // the deliberate cancel contract is unchanged.
    private class ResumableUpload(
        val staging: File, val dest: File, val declaredLen: Long,
        @Volatile var written: Long, val tid: String, val slotId: String,
        val ip: String, val expiresAt: Long,
        /** GOAT-PASS 5 (§9 final round): true when this record was created
         *  by a SETTLED HOST PAUSE (the leg was deliberately ended while the
         *  row stays Active+paused with its checkpoint) rather than by a
         *  network interruption (row FAILED+interrupted). Park records owe
         *  the row's PAUSE their lifetime — a pause can legitimately stand
         *  for hours — so they carry the long PARK window and the sweep
         *  drops them when their ROW is gone, not merely on a timeout. */
        val parked: Boolean = false,
    )
    private val resumableUploads = ConcurrentHashMap<String, ResumableUpload>()
    private val RESUMABLE_UPLOAD_TTL_MS = 10 * 60_000L   // mirrors the FAILED-row reconnect window

    // ── GOAT-PASS 5 (§9 final round): the pause is a DECISION, not an ─────────
    // outage — a user can leave an upload parked for hours while other
    // transfers run. An interruption record lives only as long as the
    // FAILED-row reconnect window (10 min), but a PARK record must survive
    // the pause itself, so its checkpoint outlives the interruption window
    // by two orders of magnitude. The row-aware sweep (below) is the real
    // lifetime rule; this TTL is only the backstop for a row the engine
    // somehow lost without resolving.
    private val PARK_RESUME_TTL_MS = 24 * 60 * 60_000L

    // ── GOAT-PASS 5 (§8): how long a browser upload's pause may hold its leg ──
    // before the leg is deliberately ended (checkpoint registered, row kept
    // Active+paused). Sized just past TransferManager's PAUSE_YIELD_GRACE_MS
    // (750 ms) so the receiving-slot permit has already been handed back to
    // the queue by the scheduled yield before this leg exits — the exit's own
    // releaseReceivingSlot is then a guaranteed no-op and the permit
    // arithmetic can never double-count. A pause that lifts inside the
    // window keeps streaming in place (a quick toggle shuffles nothing).
    private val UPLOAD_PAUSE_SETTLE_MS = 1_000L

    /** Drops an expired/invalid resumable record and its staging partial. */
    private fun dropResumableUpload(reqId: String) {
        resumableUploads.remove(reqId)?.let { rec ->
            try { rec.staging.delete() } catch (e: Exception) {}
        }
    }

    /** Sweeps expired resumable records (and their partials) — rides the
     *  existing hot spots so no new timer is needed. */
    private fun sweepResumableUploads() {
        val now = System.currentTimeMillis()
        for ((id, rec) in resumableUploads) {
            if (rec.expiresAt < now || !rec.staging.exists()) { dropResumableUpload(id); continue }
            // ── GOAT-PASS 5 (§9 final round): a PARK record's real lifetime ──
            // rule is its ROW, not a clock — the pause may legitimately stand
            // for hours. Drop the checkpoint (and its partial) the moment the
            // row it belongs to is gone or has reached any terminal state
            // (completed by another leg, cancelled, declined) — nothing can
            // ever resume onto a row that no longer exists, and the record
            // must not outlive the data it describes.
            if (rec.parked) {
                val row = TransferManager.get(rec.tid)
                val terminal = row != null && row.status != TransferManager.Status.ACTIVE &&
                    row.status != TransferManager.Status.FAILED
                if (row == null || terminal) { dropResumableUpload(id); continue }
            }
        }
    }

    private fun sweepStalePendingNativePushes() {
        val now = System.currentTimeMillis()
        // GOAT v2 fix: this used to key purely off age, which meant an
        // accepted folder push still genuinely streaming past the 3-minute
        // mark — a large/slow transfer, nothing wrong with it — could get
        // its PendingNativePush record swept out from under it the instant
        // ANY other native-push request or status poll happened to run
        // sweepStalePendingNativePushes() again, since this is a plain
        // age check with no regard for whether the push was still doing
        // something. apiNativePushFile would then fail every remaining file
        // in it with "Unknown or expired session". Only a push nobody has
        // responded to at all (still fully "pending"), one that's
        // completely finished delivering everything it's going to deliver,
        // is actually safe to age out this way.
        //
        // Pass-8 follow-up (auto-accept sync): an ACCEPTED push whose sender
        // went silent (no file POST, no probe, no poll) for the full TTL with
        // files still undelivered is dead too — its pre-registered rows would
        // otherwise wait forever. Removed with the same row resolution a
        // sender-side cancel gets (resolvePendingRowsForVanishedPush); a
        // live sender mid-batch bumps lastActivityAt on every touch, so this
        // can never fire under an active transfer.
        // The in-flight guard: one huge file CAN legitimately stream for
        // longer than the whole TTL with no other request arriving in between
        // — its POST bumped lastActivityAt once, at the start, and nothing
        // else touches the push while the bytes flow. A push that currently
        // has ANY row waiting for or holding a receiving slot is NOT dead,
        // whatever the clock says (TransferManager.isReceivingInFlight
        // covers both "queued for a slot" and "holding one").
        //
        // Pass-10 (dead-sender window): that silent-sender grace is now
        // NATIVE_PUSH_DEAD_SENDER_MS (10 min, mirroring the sender's own
        // auto-resume window) — see its declaration for why 3 minutes was
        // destroying legitimate resume opportunities.
        val toRemove = mutableListOf<PendingNativePush>()
        for ((_, p) in pendingNativePushes) {
            // ── Pass-14 (stuck "Preparing…" root fix): placeholder TTL ──────
            // A manifest-not-yet-arrived placeholder older than PLACEHOLDER_
            // TTL_MS is dead — its sender died or lost the manifest POST
            // between announce and request (a huge folder's buildManifest
            // legitimately takes seconds, but not 90). Previously such a
            // placeholder sat "Preparing…" in the incoming sheet for the
            // FULL 3-minute NATIVE_PUSH_TTL with no action available; now
            // it (and any undecided pre-decisions) resolves promptly. An
            // all-pre-DECLINED placeholder is removed immediately — nothing
            // accepted means nothing can ever arrive that the host wanted.
            // ── FINAL PASS 4 (§3 — a LIVE sender's placeholder never ages ──
            // out either). FINAL PASS 3 gave the MANIFEST-READY undecided
            // branch its liveness conjunct but left this placeholder branch
            // keyed purely off createdAt — and the placeholder window is
            // exactly where the reported ZIP-streaming loss lived: the ZIP
            // manifest build (buildZipBatchManifest: recursive resolveTree
            // walk + a per-loose-file openStream validation) is the longest
            // sender-silent window of any send type — by protocol design
            // the sender transmits NOTHING between the announce and the
            // request POST (the 700 ms decision polls only begin after the
            // request POST answers), so lastActivityAt sat frozen at
            // construction and a genuinely healthy sender's placeholder was
            // swept at the 5-minute mark: the notification had already
            // played at the announce, the sheet silently lost the offer, and
            // the sender's row sat "Waiting" for its 30-minute decision cap
            // — the exact notification-without-Incoming-Items repro. Two
            // halves close it:
            //   (a) the sender now runs a PLACEHOLDER KEEP-ALIVE poll of
            //       /api/native/push/status?id=<sessionId> during the whole
            //       manifest build (NativePushClient.sendToPeers), which
            //       both refreshes lastActivityAt and gets "pending" back —
            //       the receiver treats the poll exactly like the decision
            //       polls (same endpoint, same liveness bump);
            //   (b) this branch now requires the SAME sustained-silence
            //       conjunct the manifest-ready branch got in Pass 3: a
            //       placeholder older than its TTL is only removed when its
            //       sender has ALSO been silent for the full dead-sender
            //       window. A dead sender still resolves promptly (10 min);
            //       a live one, however slow its ZIP build, keeps its offer.
            if (!p.manifestReady) {
                val allDeclined = p.announcedTopLevel.isNotEmpty() &&
                    p.announcedTopLevel.all { p.preDecisions[it.name] == "declined" }
                if (allDeclined || (now - p.createdAt > NATIVE_PUSH_PLACEHOLDER_TTL_MS &&
                        now - p.lastActivityAt > NATIVE_PUSH_DEAD_SENDER_MS)) {
                    toRemove.add(p)
                    continue
                }
            }
            // ── FINAL PASS 3 (§3/§6 — a LIVE sender's undecided push never ──
            // ages out). The old branch keyed purely off createdAt (fixed at
            // construction, never refreshed), so a perfectly healthy sender
            // polling every 700 ms — whose polls DO refresh lastActivityAt —
            // still had its still-pending record swept at the 3-minute
            // NATIVE_PUSH_TTL: the notification sound had already played at
            // the announce, the Incoming Items sheet silently lost the offer,
            // and the sender's row sat "Waiting" until its user cancelled
            // and re-sent (the fresh id/createdAt of attempt 2 then landed
            // inside every TTL — exactly why the second attempt worked).
            // The still-pending branch now requires SUSTAINED SILENCE too:
            // a sender that answered anything within the last
            // NATIVE_PUSH_DEAD_SENDER_MS (every status poll bumps
            // lastActivityAt) keeps its undecided record alive, however long
            // the receiver's user takes to decide — pending acceptance is
            // not a transfer failure (the sender's own decision wait is
            // liveness-aware to a 30-minute absolute cap, and this now
            // matches it). A genuinely gone sender (10 minutes of total
            // silence) is swept exactly as before.
            // ── FINAL PASS 4 (§3): this block was accidentally TRIPICATED by
            // a bad merge in Pass 3 (the comment three times, the conjunct
            // three times). Deduplicated here; the condition is byte-for-
            // byte the intended single form.
            val idleTooLong = now - p.createdAt > NATIVE_PUSH_TTL &&
                now - p.lastActivityAt > NATIVE_PUSH_DEAD_SENDER_MS
            val senderGone = now - p.lastActivityAt > NATIVE_PUSH_DEAD_SENDER_MS &&
                p.status != "pending" && p.filesDone.get() < p.totalFiles &&
                !pushHasInFlightReceive(p)
            if (idleTooLong && (p.status == "pending" || p.filesDone.get() >= p.totalFiles)) {
                toRemove.add(p)
            } else if (senderGone) {
                // ── GOAT-PASS 7 (§2C — the push record IS the resume story): ──
                // a "gone" sender whose push still has a live staging partial
                // for an undelivered file is not dead weight — the record is
                // exactly what validates that file's resume offset on the
                // sender's next probe, and the sender's own auto-resume window
                // (10 min) can legitimately outlast a single NATIVE_PUSH_DEAD_
                // SENDER_MS of silence when the network returns late. Sweeping
                // the record turned every >10-minute outage into a probe-less
                // dead end for the auto-resume loop ("Unknown session" on
                // every attempt, both rows ending Interrupted) and left the
                // partials to be re-sent from 0 by a manual Retry. While any
                // undelivered entry still has bytes on disk, the record lives
                // on an extended grace — bounded, so a sender that truly never
                // returns still gets swept and the manual-retry adoption path
                // (findResumableUploadByDestPath) takes over as designed.
                if (now - p.lastActivityAt > NATIVE_PUSH_RESUMABLE_GRACE_MS ||
                    !pushHasResumablePartial(p)) {
                    toRemove.add(p)
                }
            }
        }
        if (toRemove.isNotEmpty()) {
            // ── FINAL PASS 4 (§3 — the removal is now atomic with the ──────
            // decision writers). Every accept/decline/placeholder-replacement
            // path holds synchronized(pendingNativePushes) while it reads
            // status/lastActivityAt and mutates the record, but this sweep
            // ran its whole scan-and-remove with NO monitor — a decision
            // landing on a push the sweep had already evaluated could be
            // destroyed by the removal (the just-registered rows then failed
            // as interrupted via resolvePendingRowsForVanishedPush, and the
            // sender's next poll answered "rejected" for a push the host had
            // just ACCEPTED). The removal pass now re-checks each candidate
            // under the same monitor: any record whose liveness or decision
            // state changed since the scan is re-evaluated one last time,
            // and only records that still qualify under the lock are
            // removed. The scan itself stays lock-free (it is called from
            // four hot endpoints); the race window is exactly the removal.
            val confirmed = ArrayList<PendingNativePush>(toRemove.size)
            synchronized(pendingNativePushes) {
                for (p in toRemove) {
                    val cur = pendingNativePushes[p.id] ?: continue
                    if (cur === p) { confirmed.add(p); continue }
                    // A DIFFERENT record now lives under this id (the
                    // placeholder was replaced by its manifest under the
                    // monitor — the Pass-2 atomic swap): the old placeholder
                    // object is stale, never remove the fresh one.
                }
            }
            for (p in confirmed) {
                pendingNativePushes.remove(p.id)
                resolvePendingRowsForVanishedPush(p)
                // Pass-28 (#3A/#3B): the push's delivered-text keys die with it —
                // its (push, item) pairs can never be decided again.
                purgeDeliveredTextKeys(p.id)
            }
            // Pass-10 (notification ⇄ modal agreement): a swept push no
            // longer exists as an incoming offer, so the "incoming transfer
            // waiting" system notification must clear WITH it. This sweep
            // used to remove the record silently — the notification then
            // stayed forever (until an app restart), which is exactly the
            // reported "notification and sound arrived, but nothing appears
            // in the Incoming Files modal" desync.
            refreshNativePushNotif()
        }
        // Safety net: apiNativePushFile's own success/failure paths always
        // unregister a control link when they're done with it — this only
        // matters if a transfer was ever interrupted in some way that skips
        // both (e.g. the process itself died mid-request), so it's kept
        // cheap by only scanning once the map has grown large enough to
        // suggest that's actually happened, not on every call.
        if (nativeControlByTid.size > 200) {
            nativeControlByTid.keys.toList().forEach { tid ->
                val t = TransferManager.get(tid)
                if (t == null || (t.status != TransferManager.Status.ACTIVE && t.status != TransferManager.Status.PENDING)) {
                    unregisterNativeControlLink(tid)
                }
            }
        }
        // Phase 5: orphaned-staging housekeeping rides here too — this runs
        // on every native-push announce/status request, so a host that only
        // ever talks to native peers (no web clients hitting apiQState) still
        // gets the periodic sweep. Timestamp-gated inside; one subtraction
        // when idle.
        sweepStaleStagingFiles()
    }

    /** Top-level name a manifest-relative path belongs to — the unit
     *  itemDecisions/topLevelNames track (see PendingNativePush). A loose
     *  file at the manifest root is its own top-level name; anything nested
     *  under a folder belongs to that folder's name instead. */
    private fun topLevelNameOf(path: String): String = path.substringBefore('/')

    /** Where a native-push file's bytes actually land WHILE the transfer is
     *  still in progress — see writeRawStream / apiNativePushFile. Never
     *  the real filename: a leading dot keeps it out of casual folder
     *  browsing and away from anything that scans by extension, and the
     *  distinct suffix makes an abandoned one unambiguous to a human
     *  looking at the folder later. Only a same-filesystem rename (see
     *  apiNativePushFile) ever turns this into [dest] itself, and only once
     *  every byte has been confirmed written. */
    private fun stagingFileFor(dest: File): File = File(dest.parentFile, ".${dest.name}.wsxpart")

    // Phase 5 (v3 §6 item 1 — native resume identity): the sidecar that
    // records WHICH source-file version a staging partial belongs to
    // ("size:mtime" of the sender's original). apiNativePushFileStatus
    // validates a resume against it; apiNativePushFile writes it when a
    // transfer starts from byte 0. Without it, a re-sent file whose content
    // changed between attempts (same path, same size) would resume onto the
    // old version's prefix and finish as a silently corrupted hybrid.
    private fun stagingMetaFor(dest: File): File = File(dest.parentFile, ".${dest.name}.wsxpart.meta")

    private fun writeStagingMeta(dest: File, declaredLen: Long, mtime: Long) {
        try {
            dest.parentFile?.mkdirs()
            stagingMetaFor(dest).writeText("$declaredLen:$mtime")
        } catch (e: Exception) { /* best-effort: worst case the next status check declines to resume */ }
    }

    /** Reads and validates a staging partial's identity sidecar against the
     *  sender's freshly-declared (size, mtime). True = the partial genuinely
     *  belongs to the same source version and may be resumed. A missing or
     *  unreadable sidecar is treated as NOT matching for a sender that
     *  declares identity (never resume an unvalidatable offset), and as
     *  matching for a legacy sender that declares nothing (old behavior). */
    private fun stagingMetaMatches(dest: File, declaredSize: Long, srcMtime: Long): Boolean {
        if (declaredSize < 0 && srcMtime <= 0) return true   // legacy sender — nothing to validate against
        val meta = stagingMetaFor(dest)
        if (!meta.exists()) return false
        return try {
            val parts = meta.readText().split(':')
            if (parts.size != 2) return false
            val mSize = parts[0].toLongOrNull()
            val mMtime = parts[1].toLongOrNull()
            (declaredSize < 0 || mSize == declaredSize) && (srcMtime <= 0 || mMtime == srcMtime)
        } catch (e: Exception) { false }
    }

    private fun deleteStagingFor(dest: File) {
        try { stagingFileFor(dest).delete() } catch (ignored: Exception) {}
        try { stagingMetaFor(dest).delete() } catch (ignored: Exception) {}
    }

    // ── Pass-16 (cross-sender resume, receiver half) ─────────────────────────
    // Byte-level identity for a resume checkpoint: SHA-256 over the FIRST
    // 4 KB of the staging partial, compared against the fingerprint the
    // sender declared (over the first min(4 KB, declaredSize) of ITS source
    // file). The two ranges must cover the SAME byte count to be comparable
    // — a partial shorter than both 4 KB and the full size hashed fewer
    // bytes than the sender did, so there is nothing honest to compare and
    // the answer is simply "not verified" (the caller then restarts from
    // 0, which for a < 4 KB partial costs nothing). Content identity is
    // what makes resuming the SAME file from a DIFFERENT sender device
    // correct even when every filesystem mtime disagrees.
    private fun stagingFingerprintMatches(dest: File, declaredSize: Long, fp: String?): Boolean {
        if (fp.isNullOrEmpty()) return false
        val staging = stagingFileFor(dest)
        if (!staging.exists()) return false
        return try {
            val len = staging.length()
            val window = minOf(4096L, if (declaredSize > 0) declaredSize else len)
            if (len < window) return false          // fewer bytes hashed than the sender's range
            val md = java.security.MessageDigest.getInstance("SHA-256")
            java.io.FileInputStream(staging).use { fis ->
                val buf = ByteArray(window.toInt())
                var off = 0
                while (off < window) {
                    val n = fis.read(buf, off, (window - off).toInt())
                    if (n <= 0) return false        // short read — can't validate
                    off += n
                }
                // Pass-22 (compile fix): MessageDigest.update takes Int bounds —
                // the Long window truncated once, here, after the loop already
                // treated it as Long consistently.
                md.update(buf, 0, window.toInt())
            }
                val hex = StringBuilder()
                for (b in md.digest()) { val v = b.toInt() and 0xFF; hex.append("%02x".format(v)) }
                hex.toString() == fp.lowercase()
        } catch (e: Exception) { false }
    }

    // Phase 5 (v3 §6 item 2 — orphaned-staging sweep): a deliberately kept
    // resume checkpoint is only ever MEANT to be temporary; if its sender
    // never comes back (session expired, app uninstalled, network gone for
    // good), the partial must not squat on disk forever. Anything matching
    // this pattern older than a week is, with certainty, dead: an ACTIVE
    // transfer touches its staging file's mtime continuously, and no
    // legitimate pause in this app lasts seven days. Runs at most every few
    // hours, off the request path's hot spots (timestamp-gated to a single
    // subtraction when idle).
    private val STAGING_MAX_AGE_MS = 7L * 24 * 3600_000L
    private val STAGING_SWEEP_INTERVAL_MS = 6L * 3600_000L
    @Volatile private var lastStagingSweepAtMs = 0L
    private fun sweepStaleStagingFiles() {
        val now = System.currentTimeMillis()
        if (now - lastStagingSweepAtMs < STAGING_SWEEP_INTERVAL_MS) return
        lastStagingSweepAtMs = now
        try {
            val root = File(SettingsManager.getUploadPath(context))
            if (!root.isDirectory) return
            var swept = 0
            root.walkTopDown().maxDepth(4)
                .filter { it.isFile && it.name.endsWith(".wsxpart") && now - it.lastModified() > STAGING_MAX_AGE_MS }
                .forEach {
                    if (it.delete()) {
                        swept++
                        // Pass-14 (resumable receives): a swept partial takes
                        // its registry entry with it, so a restart never
                        // restores a card for data that no longer exists.
                        val destPath = File(it.parentFile, it.name.removePrefix(".").removeSuffix(".wsxpart")).absolutePath
                        ResumableReceiveStore.remove(context, destPath)
                    }
                }
            // Pass-14: any kept-for-resume row whose partial is now gone
            // (this sweep, or a user deleting it behind the app's back)
            // dismisses itself — a "Waiting for reconnection" card with
            // nothing left to reconnect to must not linger.
            TransferManager.pruneResumableRows { path ->
                path != null && stagingFileFor(File(path)).let { it.exists() && it.length() > 0L }
            }
        } catch (e: Exception) { /* best-effort housekeeping only */ }
    }

    // Phase 5 (apiUpload Path 2): the collision re-scan at rename time.
    // Same "(1), (2), …" convention as the pre-transfer scan, extracted so
    // both places run literally the same naming logic. Returns [candidate]
    // itself when its name is still free.
    private fun uniqueDestinationFor(candidate: File): File {
        if (!candidate.exists()) return candidate
        val dir = candidate.parentFile ?: return candidate
        val safeName = candidate.name
        val dot  = safeName.lastIndexOf('.')
        val stem = if (dot > 0) safeName.substring(0, dot) else safeName
        val ext  = if (dot > 0) safeName.substring(dot) else ""
        var n = 1
        var next: File
        do { next = File(dir, "$stem ($n)$ext"); n++ } while (next.exists())
        return next
    }

    /** GOAT v2 (item 2): applies a whole-push Accept — every top-level item
     *  still undecided becomes "accepted". Deliberately scoped to items
     *  that are still "pending" rather than blindly re-marking everything:
     *  called again on a push some of whose items were already decided
     *  (individually, or by a previous Accept-All that raced with this
     *  one) it would otherwise re-run precreateNativePushDirs/
     *  registerPendingTransfersFor for those and mint duplicate PENDING
     *  Transfers-tab rows — the exact class of bug this codebase's own
     *  claim-then-promote / hasLiveEntry idempotency guards elsewhere
     *  exist to prevent.
     *
     *  Pass-28 (#5): the old [manual] flag (Pass-10's single-text-modal
     *  suppressor) is retired together with the text dialog it gated —
     *  accepted text delivers its clipboard copy, History card and quiet
     *  Toast the same way for a sheet tap and for auto-accept, so there is
     *  no mode left to distinguish. */
    fun acceptNativePush(id: String) {
        // ── FINAL PASS 2 (§2 — the placeholder accept is atomic with the ──
        // manifest replacement): the id→record resolution AND the
        // placeholder write run inside ONE critical section on the same
        // monitor apiNativePushRequest snapshots+installs under. An accept
        // that used to resolve the OLD placeholder just before the manifest
        // replaced it could write its decision into a dead object nobody
        // would ever read again — the rare lost-accept that left the sender
        // "Waiting" with no receiver rows. Post-manifest records are never
        // replaced (the repeat-manifest guard returns them untouched), so
        // only the placeholder phase needs the monitor; the heavier accept
        // work below stays outside it.
        // ── GOAT-PASS 17 (§4.1): result-bearing + traced at EVERY outcome, ──
        // including the silent ones that used to be indistinguishable from
        // success (unknown id / already-decided / placeholder pre-decision).
        val p: PendingNativePush
        synchronized(pendingNativePushes) {
            val known = pendingNativePushes[id]
            if (known == null) {
                AcceptTrace.record(id, "(whole)", "accept", "unknown-push (no record — swept or restarted?)")
                return
            }
            p = known
            if (!p.manifestReady) {
                val newlyDecided = p.announcedTopLevel.filter { p.preDecisions[it.name] == null }
                if (newlyDecided.isNotEmpty()) {
                    for (item in newlyDecided) p.preDecisions[item.name] = "accepted"
                    p.lastActivityAt = System.currentTimeMillis()   // Pass-22 (#6): a decision is activity
                    refreshNativePushNotif()
                    for (item in newlyDecided)
                        AcceptTrace.record(id, item.name, "placeholder-accept", "pre-decision recorded")
                } else {
                    AcceptTrace.record(id, "(whole)", "placeholder-accept", "no undecided announced item (already decided)")
                }
                return
            }
        }
        // ── Pass-22 (#6 — a decision is activity): every accept stamps the ─────
        // push's liveness clock. The dead-sender sweep measures from the last
        // touch, and an accept that arrives without an immediate file POST (a
        // slow sender, a manifest-delayed dispatch) used to leave a JUST-
        // accepted push on a stale clock — the sweep could resolve its freshly
        // pre-registered rows while the sender was still working through its
        // queue. Same for the per-item and decline paths below.
        p.lastActivityAt = System.currentTimeMillis()
        val newlyAccepted = p.topLevelNames.filter { p.itemDecisions[it] == "pending" }
        if (newlyAccepted.isEmpty()) {
            AcceptTrace.record(id, "(whole)", "accept", "no undecided item (already decided — idempotent no-op)")
            return
        }
        newlyAccepted.forEach { p.itemDecisions[it] = "accepted" }
        for (top in newlyAccepted) AcceptTrace.record(id, top, "accept", "decision=accepted; registering rows")
        precreateNativePushDirs(p, newlyAccepted.toSet())
        registerPendingTransfersFor(p, newlyAccepted)
        refreshNativePushNotif()
    }
    fun rejectNativePush(id: String) {
        // ── FINAL PASS 2 (§2): see acceptNativePush — the placeholder branch
        // resolves AND writes under the replacement monitor.
        val p: PendingNativePush
        synchronized(pendingNativePushes) {
            val known = pendingNativePushes[id]
            if (known == null) {
                AcceptTrace.record(id, "(whole)", "decline", "unknown-push (no record)")
                return
            }
            p = known
            if (!p.manifestReady) {
                val newlyDecided = p.announcedTopLevel.filter { p.preDecisions[it.name] == null }
                if (newlyDecided.isNotEmpty()) {
                    for (item in newlyDecided) p.preDecisions[item.name] = "declined"
                    p.lastActivityAt = System.currentTimeMillis()   // Pass-22 (#6)
                    refreshNativePushNotif()
                    for (item in newlyDecided)
                        AcceptTrace.record(id, item.name, "placeholder-decline", "pre-decision recorded")
                }
                return
            }
        }
        p.lastActivityAt = System.currentTimeMillis()   // Pass-22 (#6)
        p.topLevelNames.forEach { if (p.itemDecisions[it] == "pending") {
            p.itemDecisions[it] = "declined"
            AcceptTrace.record(id, it, "decline", "decision=declined")
        } }
        refreshNativePushNotif()
    }
    fun acceptAllNativePush() {
        // ── Pass-28 (#3B — one combined clipboard entry, in arrival order) ────
        // This used to iterate the raw ConcurrentHashMap (hash order, not
        // arrival order) and let EACH push's own precreate write its own
        // clipboard entry — so an Accept All spanning several pushes (most
        // commonly a few single-text messages arriving in a row) left the
        // clipboard holding only whichever push hash-iterated LAST; every
        // earlier text was silently overwritten, the exact "only the last
        // item is copied" break this pass fixes. The sweep now runs in
        // arrival order (createdAt — the same order pendingNativePushList()
        // and the incoming sheet render), every text item about to be
        // accepted is collected up front and PRE-marked delivered (each
        // push's own precreate below then delivers its History card,
        // filesDone and notifications as always, but skips its individual
        // clipboard write), and ONE combined entry carries them all — each
        // item's content verbatim, in the order they arrived, joined with
        // the same blank-line separator precreate itself uses within a
        // single push. Placeholder texts (manifest still in flight) are
        // included from the announce, which already carries their full
        // content; their manifest's later precreate skips the clipboard for
        // exactly these keys.
        val pending = pendingNativePushes.values
            .filter { it.status == "pending" }
            .sortedBy { it.createdAt }
        if (pending.isEmpty()) return
        val texts = mutableListOf<Triple<String, String, String>>()   // (pushId, topLevelName, content)
        for (p in pending) {
            if (!p.manifestReady) {
                for (item in p.announcedTopLevel) {
                    if (p.preDecisions[item.name] != null) continue
                    if (item.isText && !item.text.isNullOrEmpty())
                        texts.add(Triple(p.id, item.name, item.text!!))
                }
            } else {
                for (e in p.entries) {
                    if (!e.isText || e.text.isNullOrEmpty()) continue
                    val top = topLevelNameOf(e.path)
                    if (p.itemDecisions[top] != "pending") continue
                    texts.add(Triple(p.id, top, e.text!!))
                }
            }
        }
        for (t in texts) markTextClipDelivered(textClipKey(t.first, t.second))
        for (p in pending) acceptNativePush(p.id)
        if (texts.isNotEmpty()) {
            val combined = texts.joinToString("\n\n") { it.third }
            val senders = pending.filter { p -> texts.any { it.first == p.id } }.map { it.fromName }.distinct()
            val fromLabel = if (senders.size == 1) senders.first() else "${texts.size} messages"
            deliverTextToClipboard(combined, fromLabel)
        }
    }
    fun rejectAllNativePush() {
        pendingNativePushes.values.filter { it.status == "pending" }.forEach { rejectNativePush(it.id) }
    }

    /** GOAT v2 (item 2): per-item counterpart of acceptNativePush/
     *  rejectNativePush above — decides ONE top-level entry (a file, or a
     *  whole folder together) within a multi-item push independently of
     *  the rest, which is exactly what "transfers must always remain
     *  individual items" asks for. The whole-push functions above are kept
     *  exactly as they were (and now just apply this per item under the
     *  hood) for the existing Accept All / Decline All bulk actions and for
     *  the current one-row-per-push incoming sheet — which, pending the
     *  next pass's UI rework, still only ever calls those whole-push
     *  versions. No-ops on an unknown push/item or one already decided,
     *  same idempotency reasoning as acceptNativePush above. */
    fun acceptNativePushItem(id: String, topLevelName: String) {
        // ── FINAL PASS 2 (§2): see acceptNativePush — the placeholder resolve +
        // write is atomic with the manifest replacement (the lost-accept race).
        // ── GOAT-PASS 17 (§4.1/§13): traced at every outcome — the silent ──
        // no-ops (unknown push, unknown item name, already-decided) are now
        // distinguishable from an applied decision in the diagnostics ring.
        val p: PendingNativePush
        var announcedText: AnnouncedItem? = null
        var transitioned = false
        synchronized(pendingNativePushes) {
            val known = pendingNativePushes[id]
            if (known == null) {
                AcceptTrace.record(id, topLevelName, "accept-item", "unknown-push (no record — swept or restarted?)")
                return
            }
            p = known
            // ── Pass-22 (#6): see acceptNativePush — a decision is activity.
            p.lastActivityAt = System.currentTimeMillis()
            // Pass-14 (stuck "Preparing…" fix): on a still-placeholder push there
            // are no real entries yet — record the decision for the manifest's
            // arrival (see PendingNativePush.preDecisions) instead of acting on
            // nothing. The sheet row disappears exactly like a decided real row.
            if (!p.manifestReady) {
                val announced = p.announcedTopLevel.firstOrNull { it.name == topLevelName }
                if (announced != null) {
                    p.preDecisions[topLevelName] = "accepted"
                    if (announced.isText && !announced.text.isNullOrEmpty()) announcedText = announced
                    AcceptTrace.record(id, topLevelName, "placeholder-accept-item", "pre-decision recorded (raw-name key)")
                } else {
                    // ── GOAT-PASS 17 (§3): the name the sheet tapped no longer ──
                    // matches ANY announced item — possible only for a manifest
                    // that arrived between render and tap. The decision is NOT
                    // lost: re-resolve through the manifest path (the sheet's
                    // 500ms re-render presents the real rows next tick), but
                    // record it so the window is visible in diagnostics.
                    AcceptTrace.record(id, topLevelName, "placeholder-accept-item",
                        "announced-item gone (manifest landed mid-tap) — re-resolving on manifest")
                    val decided = p.itemDecisions[topLevelName] == "pending"
                    if (decided) {
                        p.itemDecisions[topLevelName] = "accepted"
                        transitioned = true
                    }
                }
            } else if (p.itemDecisions[topLevelName] == "pending") {
                // Post-manifest path runs OUTSIDE the monitor below (it does
                // real work); the decision write itself stays inside it.
                // `transitioned` preserves the old no-op-on-repeat contract:
                // a second tap on an already-accepted item must never
                // re-register its rows.
                p.itemDecisions[topLevelName] = "accepted"
                transitioned = true
            } else {
                AcceptTrace.record(id, topLevelName, "accept-item",
                    "already decided='${p.itemDecisions[topLevelName]}' (idempotent no-op)")
            }
        }
        if (transitioned) AcceptTrace.record(id, topLevelName, "accept-item", "decision=accepted; registering rows")
        // ── GOAT-PASS 17: re-read the LIVE flag — the mid-tap branch above ──
        // may have transitioned a decision on a record whose manifest landed
        // while the tap was being processed (manifestReady flipped on this
        // very object). In that case fall through to the post-manifest
        // registration tail; otherwise the placeholder branch owns it.
        if (!p.manifestReady) {
            // ── Pass-28 (#3A — the placeholder Copy copies NOW) ──────────
            // A text item's full content already arrived with the announce
            // (Pass-16), so its clipboard copy must not wait for a manifest
            // that may land later — or never, if the sender died between
            // the two posts. The old deferred copy silently never ran in
            // that case, which read on the host as "the Copy button
            // sometimes does nothing": the row vanished on the tap while
            // the clipboard stayed empty (and even in the happy path there
            // was a visible dead window before the manifest landed). Copy
            // from the announce right now; precreateNativePushDirs sees
            // the delivered key when the manifest does land and skips its
            // own clipboard write, so exactly one copy lands either way.
            // The History card still comes from precreate at manifest
            // time, byte-identical to before.
            if (announcedText != null &&
                markTextClipDelivered(textClipKey(id, topLevelName))) {
                deliverTextToClipboard(announcedText!!.text!!, p.fromName)
            }
            refreshNativePushNotif()
            return
        }
        if (!transitioned) return
        precreateNativePushDirs(p, setOf(topLevelName))
        registerPendingTransfersFor(p, listOf(topLevelName))
        refreshNativePushNotif()
    }
    fun declineNativePushItem(id: String, topLevelName: String) {
        // ── FINAL PASS 2 (§2): see acceptNativePushItem — the placeholder
        // resolve + write is atomic with the manifest replacement.
        val p: PendingNativePush
        var decidedManifestPhase = false
        synchronized(pendingNativePushes) {
            p = pendingNativePushes[id] ?: return
            // ── Pass-22 (#6): see acceptNativePush — a decision is activity.
            p.lastActivityAt = System.currentTimeMillis()
            // Pass-14 (stuck "Preparing…" fix): placeholder twin of the accept
            // branch just above.
            if (!p.manifestReady) {
                if (p.announcedTopLevel.any { it.name == topLevelName }) {
                    p.preDecisions[topLevelName] = "declined"
                }
            } else if (p.itemDecisions[topLevelName] == "pending") {
                p.itemDecisions[topLevelName] = "declined"
                decidedManifestPhase = true
            }
        }
        if (!decidedManifestPhase) return
        refreshNativePushNotif()
    }

    /**
     * GOAT (Part 2 completion — folder accept/decline, "individual
     * internal files can be managed"): removes one nested file, or an
     * entire nested subfolder (everything under [nestedPath]), from an
     * incoming top-level folder — either before or after that folder was
     * accepted. Before acceptance this is enough on its own:
     * registerPendingTransfersFor (called when the folder IS accepted)
     * checks PendingNativePush.isExcludedNested and simply never creates a
     * PENDING entry for anything already excluded. After acceptance, a
     * PENDING entry already exists for every file — including whichever
     * one is being excluded now — so this ALSO removes those specific
     * entries directly rather than leaving them stuck "Waiting" for a file
     * that will never actually be written (apiNativePushFile independently
     * refuses any excluded path too — see isExcludedNested there — so this
     * cleanup is about the Transfers tab staying accurate, not primary
     * enforcement).
     */
    fun excludeNativePushNestedPath(id: String, topLevelName: String, nestedPath: String) {
        val p = pendingNativePushes[id] ?: return
        // ── Pass-22 (#6): a decision is activity — see acceptNativePush.
        p.lastActivityAt = System.currentTimeMillis()
        p.excludedNestedByTopLevel.getOrPut(topLevelName) { java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>()) }.add(nestedPath)
        if (p.itemDecisions[topLevelName] == "accepted") {
            val affected = p.entries.filter { !it.isDir && (it.path == nestedPath || it.path.startsWith("$nestedPath/")) }
            // ── GOAT-PASS 14 (Phase 1, §3/§4): cancelPendingUpload only ever ──
            // covered the still-WAITING row — a file already STREAMING kept
            // streaming to completion and landed as a delivered file the host
            // had explicitly removed. cancelUploadRequestEverywhere resolves
            // BOTH stages (PENDING and ACTIVE alike) as an honest Cancelled,
            // and its durable tombstone refuses the sender's in-flight leg on
            // the next chunk/re-POST, so an excluded child can never finish
            // arriving after the ✕.
            for (e in affected) TransferManager.cancelUploadRequestEverywhere("${p.id}::${e.path}")
        }
        refreshNativePushNotif()
    }

    private fun refreshNativePushNotif() {
        // Pass-14: a placeholder whose every announced item was pre-decided
        // is not waiting on the host either — same predicate the incoming
        // sheet's list uses, so the notification and the sheet can never
        // disagree about whether anything is still being asked.
        if (pendingNativePushes.values.none { awaitingHostDecision(it) })
            com.lumidrop.x.service.ServerForegroundService.clearPendingNativePushNotif(context)
    }

    /** Pass-14 (stuck "Preparing…" fix): the ONE predicate for "is this push
     *  still asking the HOST to decide anything" — real pushes wait while
     *  any itemDecisions entry is pending; placeholders wait while any
     *  announced item has no pre-decision yet. Shared by the incoming
     *  sheet's list and the pending notification so the two surfaces can
     *  never disagree. */
    private fun awaitingHostDecision(p: PendingNativePush): Boolean {
        if (p.status != "pending") return false
        if (!p.manifestReady) {
            if (p.announcedTopLevel.isEmpty()) return true
            return p.announcedTopLevel.any { p.preDecisions[it.name] == null }
        }
        return true
    }

    /** Pass-10 (self-healing incoming state): the UI-tick read of the
     *  pending list ALSO sweeps — the sweep previously ran only inside the
     *  native-push HTTP endpoints (announce/request/status/file/file-status),
     *  so a push whose sender died right after announcing (its manifest POST
     *  never landed) could leave an un-actionable "Preparing…" placeholder
     *  in the Incoming Files sheet indefinitely whenever no OTHER native
     *  push happened to arrive and trip the sweep. ReceivingModal calls this
     *  every ~500ms, which bounds any stale placeholder's life to the TTL
     *  regardless of traffic. Cheap: a linear scan of a normally-tiny map
     *  (plus the timestamp-gated staging sweep riding along inside). */
    fun pendingNativePushList(): List<PendingNativePush> {
        sweepStalePendingNativePushes()
        // Pass-14 (stuck "Preparing…" fix): a placeholder whose every
        // announced item was already pre-decided is no longer waiting on
        // the HOST — only on the sender's manifest. It stays out of the
        // incoming sheet (nothing left to ask the host), exactly like a
        // fully-decided real push; the sweep removes the record itself once
        // the placeholder TTL lapses if the manifest never comes.
        return pendingNativePushes.values.filter { awaitingHostDecision(it) }.sortedBy { it.createdAt }
    }

    /**
     * GOAT (Part 3 item 3 — minimized incoming queue): total count of
     * individual top-level items still awaiting a host decision, across
     * both native pushes and plain browser uploads — the exact same
     * computation SendFragment.updateIncomingRequests uses to decide
     * whether its sheet has anything to show, exposed here so
     * MainActivity's floating indicator (visible across every tab, shown
     * only once the sheet itself has been dismissed/minimized) can ask
     * the same question without duplicating the logic.
     */
    fun pendingIncomingCount(): Int {
        val uploadCount = pendingUploads.values.count { it.status == "pending" }
        val pushCount = pendingNativePushList().sumOf { p ->
            if (!p.manifestReady) p.announcedTopLevel.count { p.preDecisions[it.name] == null }
            else p.topLevelNames.count { p.itemDecisions[it] == "pending" }
        }
        return uploadCount + pushCount
    }

    /** GOAT v2 (item 8): pre-creates a PENDING TransferManager row for
     *  every FILE under the given now-accepted top-level name(s) — the
     *  exact pattern claimOrStartPending already uses for Quick-Share,
     *  applied to native push for the first time. Without this, a file's
     *  only TransferManager entry used to appear the instant its own HTTP
     *  POST arrived — bounded by NativePushClient's own send concurrency —
     *  so the receiver's Transfers tab had no visibility into how much work
     *  was actually still queued, and looked like it was processing in
     *  small, unexplained batches. Each entry is later promoted (never
     *  duplicated) via TransferManager.claimPendingUpload, keyed the same
     *  way apiUpload's own approval flow already keys its requestId — a
     *  composite "pushId::relPath" plays exactly that role here, reusing
     *  the existing PENDING→claim machinery rather than inventing a
     *  parallel one. */
    private fun registerPendingTransfersFor(push: PendingNativePush, topLevelNames: Collection<String>) {
        val names = topLevelNames.toSet()
        // GOAT (Part 2 — folder as a single transfer item): one
        // TransferManager group per top-level FOLDER name (never for a
        // standalone file), so every file underneath collapses into one
        // Active-tab row instead of showing up as N separate ones. A
        // top-level name is a folder if the manifest carries an explicit
        // isDir entry for exactly that path — every folder, including an
        // empty one, always gets one (see NativePushClient.flattenResolved).
        val groupIdByTopLevel = HashMap<String, String>()
        for (name in names) {
            val isFolder = push.entries.any { it.isDir && it.path == name }
            if (isFolder) {
                val gid = TransferManager.startGroup(
                    name = name, direction = TransferManager.Direction.UPLOAD,
                    peerIp = push.fromIp, peerName = push.fromName, source = TransferManager.Source.NATIVE_PUSH)
                groupIdByTopLevel[name] = gid
                // GOAT-PASS 10 (§4): publish it on the push record too, so
                // the file-POST claim chain's fallback can never mint a
                // parentless row for this folder (see the class-level note
                // above and apiNativePushFile's start() fallback).
                push.groupIdByTopLevel[name] = gid
            }
        }
        for (e in push.entries) {
            if (e.isDir || e.isText) continue
            val topName = topLevelNameOf(e.path)
            if (topName !in names) continue
            if (push.isExcludedNested(e.path)) continue   // GOAT (Part 2 completion): host removed this file/folder before accepting
            // ── Pass-14 (resumable receives, requirement 4 — reuse the ──────
            // existing card): a NEW send of a file whose interrupted partial
            // still exists on this device must NOT mint a second card next
            // to the lingering "Waiting for reconnection" one — the exact
            // duplicate-card report. When a resumable row for this exact
            // destination exists (and the declared size matches, so a same-
            // named different file can't falsely adopt), the OLD row is
            // re-keyed to this push's request id and re-parented into this
            // push's folder group, keeping its row id, byte checkpoint and
            // history: one card, whose state walks Waiting-for-reconnection
            // → Waiting → Resuming → Done as the resume actually streams.
            val dest = File(push.destRoot, push.resolvePath(e.path))
            try {
                val adoptable = TransferManager.findResumableUploadByDestPath(dest.absolutePath)
                // ── Pass-15 (resume from a different sender, SAME file only) ──
                // The adoption identity is now the STAGING SIDECAR, not the
                // old row's declared size alone: the sender's manifest
                // declares (size, mtime) and stagingMetaMatches validates it
                // against the partial that actually sits on disk. The SAME
                // original file re-sent from a DIFFERENT device declares the
                // same identity and resumes from the interrupted point, even
                // though the row's peerName is the older sender; a same-named
                // DIFFERENT file fails the check, is treated as the genuinely
                // new item it is (fresh row; the fresh-start branch purges the
                // now-obsolete partial), and can never finish as a stitched
                // hybrid of two files. Legacy senders (mtime 0, size unknown)
                // keep the old best-effort size-only agreement.
                // ── Pass-16: the manifest's 4 KB content fingerprint ──
                // takes adoption identity OFF the mtime — validated directly
                // against the staging bytes (stagingFingerprintMatches), so
                // the cross-sender resume ALSO reuses the same card instead
                // of minting a fresh one beside the lingering interrupted
                // row. fp (when declared) wins; the sidecar rules remain the
                // legacy fallback.
                val identityOk = when {
                    !e.fp.isNullOrEmpty() -> stagingFingerprintMatches(dest, e.size, e.fp)
                    e.mtime > 0L || e.size > 0L -> stagingMetaMatches(dest, e.size, e.mtime)
                    else -> true
                }
                if (adoptable != null && identityOk &&
                    (e.size <= 0L || adoptable.totalBytes <= 0L || adoptable.totalBytes == e.size)) {
                    val adopted = TransferManager.adoptResumableRowAsPending(
                        adoptable.id, "${push.id}::${e.path}", dest.absolutePath, groupIdByTopLevel[topName])
                    if (adopted != null) {
                        continue
                    }
                } else if (adoptable != null && !identityOk) {
                }
            } catch (ex: Exception) {
            }
            TransferManager.startPendingUpload(
                requestId = "${push.id}::${e.path}",
                fileName = push.resolvePath(e.path),
                totalBytes = e.size,
                peerIp = push.fromIp,
                peerName = push.fromName,
                groupId = groupIdByTopLevel[topName],
                // Pass-8 (§13): these rows ARE the receiving half of a native
                // push — tag them as such so per-row control propagation and
                // the receiver's Cancel-All whole-push collection recognize
                // them (they used to inherit Source.PLAIN and were invisible
                // to both).
                source = TransferManager.Source.NATIVE_PUSH,
                // ── GOAT-PASS 15 (§3.4): an unknown-size entry is the streamed
                // zip-batch archive — its receive row is marked so the
                // receiver's own UI never offers Pause on a live-synthesized
                // archive either.
                zipStream = e.size < 0L,
            )
        }
    }

    // Directories arrive fully spelled out in the manifest (including ones
    // with zero files inside) — created up-front on accept, so an empty
    // source folder actually survives as an empty destination folder. Doing
    // this only via each file's parent-dir mkdirs() would never create a
    // truly empty leaf folder, since nothing would ever mkdir it.
    //
    // Text/paste entries are ALSO fully delivered right here rather than via
    // apiNativePushFile — their whole content already arrived inside the
    // manifest itself (see NativePushClient's TextSpec), so there's nothing
    // left to stream. No file is ever written for them (the GOAT v2 fix
    // below); the delivery is the clipboard copy through the ONE shared
    // writer (deliverTextToClipboard) with its quiet confirmation Toast —
    // the same path an individual placeholder-row Copy and an Accept-All
    // combined entry use, so text arriving from a native peer and text
    // arriving from a browser client (which enqueues through apiTextSend
    // into this very same pending-push machinery, Pass-28 #3C) look and
    // feel identical on the host.
    //
    // GOAT v2: [topLevelFilter] scopes all of this to a specific
    // just-decided top-level name (individual accept) — null means "every
    // currently-accepted item", used by the whole-push Accept path. Safe to
    // call more than once for the same push as more items get accepted:
    // mkdirs() on an already-existing directory is a no-op, and each text
    // entry's effects (history card + clipboard copy) run exactly once per
    // acceptance because the filter gates which entries are included.
    // ── Pass-28 (#5): the old suppressTextDialog flag and the text-dialog
    // enqueue it gated are GONE — an accepted text's only effects are the
    // clipboard copy, the History message card above, and the quiet
    // confirmation Toast, for manual and auto acceptance alike. No Copy/Remove
    // dialog may ever interrupt the receiver's current activity.
    // ── Pass-28 (#3A/#3B): exactly-once text clipboard delivery ─────────────
    // Keyed "pushId::topLevelName". A key's presence means THAT text item's
    // content has already been written to the clipboard — individually at the
    // moment its row was accepted, or as part of an Accept-All combined
    // entry — so precreateNativePushDirs consults it before writing: a text
    // whose decision pre-dates its manifest (a placeholder-row Copy, an
    // Accept All that raced the manifest's arrival) delivers its clipboard
    // copy exactly once instead of overwriting the clipboard a second time
    // when the manifest lands. The History card and filesDone bookkeeping
    // are deliberately NOT gated by this — they still run at manifest time,
    // exactly as before. Purged with the push in the stale sweep and the
    // kick path; a placeholder→manifest REPLACEMENT keeps its keys valid on
    // purpose (same push id, same item, same delivery).
    private val deliveredTextClipKeys = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    private fun textClipKey(pushId: String, topLevelName: String) = "$pushId::$topLevelName"

    /** True exactly once per (push, item): the first caller wins the
     *  clipboard delivery, every later caller learns it already happened. */
    private fun markTextClipDelivered(key: String): Boolean =
        deliveredTextClipKeys.putIfAbsent(key, true) == null

    /** Drops a swept/kicked push's delivered-keys — the (push, item) pairs
     *  can never be decided again once the push itself is gone. */
    private fun purgeDeliveredTextKeys(pushId: String) {
        val prefix = "$pushId::"
        val it = deliveredTextClipKeys.keys.iterator()
        while (it.hasNext()) { if (it.next().startsWith(prefix)) it.remove() }
    }

    /** ── Pass-28 (#3A/#3B): the ONE clipboard writer for accepted text —
     *  content joined exactly as the caller assembled it, one quiet Toast,
     *  main-thread hop (this runs on a NanoHTTPD worker; ClipboardManager
     *  and Toast are main-thread affairs). Same delivery shape every text
     *  acceptance path shares, so an individual Copy and an Accept-All
     *  combined entry behave identically. */
    private fun deliverTextToClipboard(content: String, fromName: String) {
        if (content.isBlank()) return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("LumiDrop X", content))
                com.lumidrop.x.service.ServerForegroundService.notifyTextReceived(context, fromName)
            } catch (ex: Exception) { /* best-effort — the sender still sees delivery complete either way */ }
        }
    }

    private fun precreateNativePushDirs(p: PendingNativePush, topLevelFilter: Set<String>? = null) {
        p.destRoot.mkdirs()
        for (e in p.entries) {
            if (!e.isDir) continue
            if (topLevelFilter != null && topLevelNameOf(e.path) !in topLevelFilter) continue
            File(p.destRoot, p.resolvePath(e.path)).mkdirs()
        }

        val textEntries = p.entries.filter {
            it.isText && !it.text.isNullOrEmpty() &&
                (topLevelFilter == null || topLevelNameOf(it.path) in topLevelFilter)
        }
        if (textEntries.isNotEmpty()) {
            // ── Pass-16 (received texts get a History card) ─────────────────
            // Every accepted native text mints its terminal message row —
            // COMPLETED, message content carried in the engine, no file on
            // disk — so the History tab shows the exchange with a Copy
            // button and the Received tab's disk-reconcile never sees it.
            for (e in textEntries) {
                try {
                    TransferManager.recordTextTransfer(TransferManager.Direction.UPLOAD,
                        e.text ?: "", p.fromIp, p.fromName, TransferManager.Source.NATIVE_PUSH)
                } catch (ex: Exception) { /* the clipboard/dialog delivery below is the load-bearing half */ }
            }
            // GOAT v2 fix: this used to also write each text entry out as a
            // real .txt file (then media-scan it), on top of the clipboard
            // copy below — meaning a text message pushed between two
            // native peers showed up as an actual file in Received,
            // unlike the exact same text sent from a browser client
            // (apiTextSend, clipboard-only, no file ever created). Text
            // stays text regardless of which transport delivered it now;
            // only a deliberate file/folder share should ever produce a
            // file on disk.
            for (e in textEntries) p.filesDone.incrementAndGet()
            // ── Pass-28 (#5): no dialog follows an accepted text anymore — ──
            // the clipboard copy below (plus its quiet confirmation Toast and
            // the History card above) is the complete delivery. The old
            // ReceivingModal Copy/Remove dialog this used to enqueue was an
            // interrupting modal on top of an already-silent, already-complete
            // delivery.
            // ── Pass-28 (#3A/#3B — exactly-once clipboard delivery): only the ──
            // texts whose (push, item) key has not been delivered yet join this
            // write. A text accepted while its push was still a placeholder
            // (individual Copy or Accept All) already copied from the ANNOUNCE
            // — rewriting it here would clobber a combined Accept-All entry
            // with just this one item. Undelivered texts (the ordinary
            // post-manifest accept, auto-accept) still join with the same
            // blank-line separator within this push as always.
            val undelivered = mutableListOf<NativePushEntry>()
            for (e in textEntries) {
                if (markTextClipDelivered(textClipKey(p.id, topLevelNameOf(e.path)))) undelivered.add(e)
            }
            val combinedText = undelivered.mapNotNull { it.text }.joinToString("\n\n")
            if (combinedText.isNotBlank()) deliverTextToClipboard(combinedText, p.fromName)
        }

        if (p.filesDone.get() >= p.totalFiles) {
            com.lumidrop.x.service.ServerForegroundService.notifyNativePushComplete(context, p.fromName, p.totalFiles)
        }
    }

    // Manifests are small JSON but can describe many thousands of entries for
    // a large folder — read with the exact same bounded, cap-and-drain
    // approach readPathPkgParams() uses for its urlencoded body above, just
    // decoded as JSON (a far better fit for nested manifest data than flat
    // key=value pairs would be).
    private fun readJsonBody(s: IHTTPSession, capBytes: Int = 2 * 1024 * 1024): JSONObject? {
        return try {
            val declared = s.headers["content-length"]?.toLongOrNull() ?: 0L
            val toUse = when { declared <= 0L -> 0; declared > capBytes -> capBytes; else -> declared.toInt() }
            if (toUse <= 0) return null
            val buf = ByteArray(toUse)
            var off = 0
            while (off < toUse) {
                val n = s.inputStream.read(buf, off, toUse - off)
                if (n <= 0) break
                off += n
            }
            if (declared > toUse) {
                var remaining = declared - toUse
                val drain = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val n = s.inputStream.read(drain, 0, minOf(drain.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    remaining -= n
                }
            }
            JSONObject(String(buf, 0, off, Charsets.UTF_8))
        } catch (e: Exception) { null }
    }

    /**
     * GOAT (Part 1 correction #4 — maximum realistic incoming speed): the
     * fast first contact of the two-phase native push protocol. Carries
     * only what the sender's OWN picker UI already knows with zero
     * filesystem/SAF access (top-level item names + whether each is a
     * folder) — see NativePushClient.sendToPeers/announce, which fires
     * this before it starts building the real manifest, not after. This
     * is what lets the Accept/Decline sheet appear here essentially
     * instantly regardless of how large or slow-to-enumerate the actual
     * selection turns out to be: the receiver never has to wait on the
     * sender's own filesystem walk just to learn a transfer is waiting.
     *
     * The real manifest — real per-file entries, real sizes, individual
     * files actually deliverable — always still follows via
     * apiNativePushRequest below, correlated by the same [id] this
     * creates; nothing here ever authorizes writing a single byte to
     * disk. A host tapping Accept while manifestReady is still false is
     * accepting "yes, whatever this turns out to be" in principle, but
     * apiNativePushRequest/registerPendingTransfersFor is still what
     * actually queues real, individually-verified files — this never
     * short-circuits that.
     */
    private fun apiNativePushAnnounce(s: IHTTPSession): Response {
        sweepStalePendingNativePushes()
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        val json = readJsonBody(s) ?: return jsonErr("Bad or missing announcement")
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return jsonErr("Missing id")
        val deviceId = json.optString("deviceId").ifBlank { ip }
        val fromPort = json.optInt("myPort", 8888)
        val fromName = sanitize(json.optString("name").ifBlank { "Unknown Device" })
        val itemsArr = json.optJSONArray("topLevel") ?: JSONArray()
        val announced = mutableListOf<AnnouncedItem>()
        for (i in 0 until itemsArr.length()) {
            val o = itemsArr.optJSONObject(i) ?: continue
            // ── GOAT-PASS 17 (§3 — identity parity, the Accept-does-nothing ──
            // root cause): the placeholder's item names must be the EXACT
            // strings the manifest will later key its decisions by — the RAW
            // sender-side names, NOT sanitize()'d. The old placeholder keys
            // were sanitized (path-reserved chars → '_', leading dots
            // stripped, 255-char cap) while manifest paths are raw, so a
            // pre-decision recorded under "Screenshot_2024.png" never matched
            // the manifest's "Screenshot:2024.png" — the accept was silently
            // DROPPED at manifest application (itemDecisions[raw] != "pending"
            // was simply not "pending"), the incoming row came back undecided
            // or vanished, and the sender sat "Waiting" until its 30-minute
            // cap: the intermittent tap-does-nothing. Safety is unchanged —
            // manifest paths are already refused if they contain "..", disk
            // collisions are resolved by renameMap at manifest time, and the
            // actual writes happen under the receiver's own dest-rooted
            // paths. Blank-only names are still skipped (nothing to show).
            val name = o.optString("name").trim()
            if (name.isEmpty()) continue
            announced.add(AnnouncedItem(name, o.optBoolean("isFolder", false), o.optBoolean("isText", false),
                // ── Pass-16: the message content rides the announce so the ──
                // placeholder phase renders the true text design with a real
                // snippet — never a file-like container for a message.
                if (o.optBoolean("isText", false)) o.optString("text", "").ifBlank { null } else null))
        }
        if (announced.isEmpty()) return jsonErr("Empty announcement")

        val destRoot = File(SettingsManager.getUploadPath(context))
        val push = PendingNativePush(id, deviceId, fromName, ip, fromPort,
            entries = emptyList(), totalBytes = 0L, destRoot = destRoot,
            manifestReady = false, announcedTopLevel = announced)
        // ── GOAT-PASS 12 (§4 — folders are auto-accepted from the ANNOUNCE, ──
        // not just the manifest). The two-phase send protocol fires the
        // announce FIRST and builds the manifest (a full recursive folder
        // walk) afterwards; the placeholder the announce registers is
        // "awaiting the host's decision" by construction
        // (awaitingHostDecision), so the Incoming Files sheet rendered it —
        // for the seconds a large folder's manifest walk takes, WITH
        // Auto-Accept on, exactly the reported "the incoming modal still
        // appears for folders". Ordinary files traverse the same window
        // too, but their manifest is ready almost instantly, which is why
        // the bug read as folder-specific.
        //
        // With Auto-Accept ON, the announced items are PRE-DECIDED
        // "accepted" right here: the placeholder stops counting as
        // awaiting-a-decision (no modal, no notification — the same silent
        // acceptance an ordinary auto-accepted file gets), and when the
        // manifest lands, apiNativePushRequest's existing auto-accept path
        // registers every child transfer exactly as a manual Accept All
        // does.
        //
        // ── GOAT-PASS 13 (§6 — ONE acceptance flow, no folder carve-outs) ──
        // The GOAT-PASS 11 §7 cancelled-folder tombstone carve-out that used
        // to live here (silent-decline / manual-offer windows after a
        // cancel) is REMOVED: it was a folder-only second acceptance system
        // layered on top of the ordinary one, and it made Auto-Accept
        // unreliable in exactly the reported ways — a re-sent folder was
        // silently DECLINED for 90 s after any cancel (the sender's Active
        // tab showed Declined while nothing happened on the receiver), then
        // forced a manual confirmation modal for the rest of a 10-minute
        // window even with Auto-Accept on. A folder now follows the SAME
        // flow as an ordinary file, whatever happened in earlier sends:
        // Auto-Accept ON ⇒ accepted instantly, no modal; Auto-Accept OFF ⇒
        // the normal confirmation sheet. The whole-push cancel latch
        // (cancelledPushIds) still protects any in-flight session; only the
        // cross-session tombstone machinery is gone.
        val autoAccept = SettingsManager.isAutoAcceptUploads(context)
        if (autoAccept) {
            for (a in announced) push.preDecisions[a.name] = "accepted"
            pendingNativePushes[id] = push
            return json(JSONObject().put("acknowledged", true))
        }
        pendingNativePushes[id] = push
        val stillPending = pendingNativePushes.values.count { it.status == "pending" }
        com.lumidrop.x.service.ServerForegroundService.notifyPendingNativePush(
            context, fromName, announced.size, stillPending)
        return json(JSONObject().put("acknowledged", true))
    }

    private fun apiNativePushRequest(s: IHTTPSession): Response {
        sweepStalePendingNativePushes()
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        val json = readJsonBody(s) ?: return jsonErr("Bad or missing manifest")

        val deviceId  = json.optString("deviceId").ifBlank { ip }
        val fromPort  = json.optInt("myPort", 8888)
        val fromName  = sanitize(json.optString("name").ifBlank { "Unknown Device" })
        val rootLabel = sanitize(json.optString("rootLabel").ifBlank { fromName }).ifBlank { "Device" }
        val entriesArr = json.optJSONArray("entries") ?: JSONArray()
        val entries = mutableListOf<NativePushEntry>()
        var totalBytes = 0L
        for (i in 0 until entriesArr.length()) {
            val o = entriesArr.optJSONObject(i) ?: continue
            val path = o.optString("path").trim('/')
            // Reject path traversal / absolute-looking entries outright —
            // this manifest ultimately drives real filesystem writes below.
            if (path.isBlank() || path.contains("..")) continue
            val isDir = o.optBoolean("isDir", false)
            val isText = !isDir && o.optBoolean("isText", false)
            val text = if (isText) o.optString("text", "") else null
            val size = when {
                isDir -> 0L
                isText -> (text ?: "").toByteArray(Charsets.UTF_8).size.toLong()
                else -> o.optLong("size", 0L)
            }
            // Pass-15: the sender's own source-file mtime — the resume-across-
            // senders identity (see NativePushEntry.mtime's doc). Legacy
            // senders don't send it; 0 keeps their old best-effort behavior.
            val mtime = o.optLong("mtime", 0L)
            // ── Pass-16 (cross-sender resume, content identity) ────────────
            // The sender's SHA-256 of the FIRST 4 KB of the file — a
            // byte-level fingerprint that survives the ONE thing mtime
            // cannot: the same original file copied to a DIFFERENT sender
            // device carries a different filesystem mtime there, which
            // used to fail the (size, mtime) identity check and restart an
            // identical cross-sender resume from 0% (the reported bug).
            // Legacy senders declare no fp; their rows fall back to the
            // mtime rules unchanged.
            val fp = o.optString("fp", "").ifBlank { null }
            entries.add(NativePushEntry(path, size, isDir, isText, text, mtime, fp))
            totalBytes += size
        }
        if (entries.isEmpty()) return jsonErr("Empty manifest")

        // Entries land DIRECTLY under the configured receive folder — no
        // synthetic per-push wrapper. Each entry's path already starts with
        // its own top-level item's real name (see NativePushClient's
        // manifest builder, which mirrors the ZIP path's own naming
        // exactly), so writing them as-is reproduces "Seal/photo.jpg"
        // straight into the receive folder, not nested inside a second
        // generated "Seal_<id>/" layer the user never asked for.
        //
        // Collision-safety is applied per TOP-LEVEL name only, and only
        // when that exact name already exists on disk — e.g. a second
        // unrelated "Seal" folder from a different send becomes
        // "Seal (1)", never overwriting the first one. This never renames
        // anything nested deeper (the internal hierarchy under a top-level
        // folder is untouched either way), and never invents a name when
        // there's no actual conflict — the common case (nothing there yet)
        // preserves the original name exactly, with no identifier of any
        // kind, generated or otherwise, ever exposed to the user.
        val uploadRoot = File(SettingsManager.getUploadPath(context))
        val topLevelDirs = entries.filter { it.isDir && !it.path.contains('/') }.map { it.path }.toSet()
        val topLevelNames = entries.map { it.path.substringBefore('/') }.distinct()
        val renameMap = HashMap<String, String>()
        for (topName in topLevelNames) {
            var candidate = topName
            var n = 1
            while (File(uploadRoot, candidate).exists()) {
                candidate = if (topName in topLevelDirs) {
                    "$topName ($n)"
                } else {
                    val dot = topName.lastIndexOf('.')
                    if (dot > 0) "${topName.substring(0, dot)} ($n)${topName.substring(dot)}" else "$topName ($n)"
                }
                n++
            }
            if (candidate != topName) renameMap[topName] = candidate
        }
        val destRoot = uploadRoot

        val autoAccept = SettingsManager.isAutoAcceptUploads(context)
        // GOAT (Part 1 correction #4): reuse the announced id when this
        // manifest is a follow-up to an earlier apiNativePushAnnounce
        // call (see NativePushClient.Session — the SAME id is used for
        // both, minted once by the sender) — this REPLACES the
        // placeholder push in pendingNativePushes rather than creating a
        // second entry for what the host already sees as one incoming
        // transfer. Falls back to minting a fresh id for a sender that
        // never announced (an older client, or an announce that was lost)
        // — the ordinary single-phase path, unchanged.
        val announcedId = json.optString("id").takeIf { it.isNotBlank() }
        val wasAnnounced = announcedId != null && pendingNativePushes[announcedId]?.manifestReady == false
        // ── GOAT-PASS 10 (§8 — a repeat manifest is IDEMPOTENT): the sender ──
        // now retries a manifest POST whose response was lost (network churn
        // after returning from the background) instead of failing the whole
        // batch. That retry is only safe if THIS endpoint answers it from the
        // record it already holds: replacing a manifestReady record with a
        // fresh one would wipe every itemDecisions entry the host already
        // made, orphan the rows registerPendingTransfersFor minted for them,
        // and re-register fresh duplicates beside them — the exact duplicate/
        // stale-request corruption the reliability brief forbids. A repeat
        // delivery from the SAME device for an id whose record is already
        // manifest-complete therefore returns the EXISTING record's live
        // status and decisions, unchanged, and refreshes its liveness stamp.
        if (announcedId != null) {
            val existing = pendingNativePushes[announcedId]
            if (existing != null && existing.manifestReady && existing.fromDeviceId == deviceId) {
                existing.lastActivityAt = System.currentTimeMillis()
                val dj = JSONObject()
                for (name in existing.topLevelNames) dj.put(name, existing.itemDecisions[name] ?: "pending")
                return json(JSONObject().put("requestId", announcedId).put("status", existing.status)
                    .put("receivingConcurrency", TransferManager.receivingConcurrencyLimit())
                    .put("decisions", dj)
                    .put("excludedNested", excludedNestedJson(existing)))
            }
        }
        // ── Pass-14 (stuck "Preparing…" fix): carry the host's placeholder- ──
        // phase decisions (if any) across the replacement — a placeholder the
        // host already accepted/dismissed item-by-item must not re-ask the
        // same questions the moment the real manifest finally lands.
        // ── FINAL PASS 2 (§2 — the lost-accept race, closed at the source): ──
        // the snapshot used to be taken BEFORE the new record replaced the
        // placeholder, so an accept landing in the microseconds between the
        // snapshot and the replacement was silently DROPPED — the incoming
        // row re-appeared as undecided, the receiver never registered its
        // PENDING rows, and the sender sat "Waiting" until its decision
        // deadline (the rare accepted-but-nothing-starts report, most
        // reachable exactly at app start when the manifest lands hot on the
        // announce's heels). The snapshot and the install now run inside ONE
        // critical section on the same monitor the placeholder-accept paths
        // write under: an accept that completes before the snapshot is
        // carried; an accept after it resolves the NEW record by id and
        // applies through its own path. No accept can be lost in between.
        val oldPlaceholder = if (announcedId != null) pendingNativePushes[announcedId] else null
        val id = announcedId ?: java.util.UUID.randomUUID().toString().replace("-", "")
        val push = PendingNativePush(id, deviceId, fromName, ip, fromPort, entries, totalBytes, destRoot,
            topLevelRenames = renameMap)
        val preDecisions: Map<String, String>
        synchronized(pendingNativePushes) {
            preDecisions = oldPlaceholder?.preDecisions?.toMap() ?: emptyMap()
            pendingNativePushes[id] = push
        }

        if (autoAccept) {
            // ── GOAT-PASS 13 (§6 — the plain auto-accept path, identical for ──
            // files and folders). Same path a manual Accept All takes — every
            // item starts "pending" above, this just fast-forwards all of them
            // to "accepted": parent/child rows register through the ordinary
            // registerPendingTransfersFor machinery, dirs pre-create, and the
            // sender's progressive dispatch starts streaming on its next poll.
            // (The GOAT-PASS 11 §7 cancelled-folder tombstone special-casing
            // that used to sit here — silent auto-decline and forced-manual
            // windows for recently-cancelled folders — is removed: it was a
            // folder-only second acceptance system that made Auto-Accept
            // inconsistent; see the announce handler's §6 note for the full
            // reasoning. The in-flight session latch cancelledPushIds still
            // refuses files of any push the host cancelled mid-flight.)
            acceptNativePush(id)
        } else if (preDecisions.isNotEmpty()) {
            // Apply the placeholder-phase decisions through the very same
            // per-item paths a post-manifest tap takes (dirs pre-created,
            // PENDING rows registered — and any resumable card for a re-sent
            // file adopted), so the sender's progressive dispatch starts
            // streaming accepted items on its very next poll with no further
            // host action.
            //
            // ── GOAT-PASS 17 (§3/§4.1 — a placeholder decision can never be ──
            // silently dropped). The OLD loop keyed purely on the literal
            // pre-decision name: a placeholder from a pre-GP17 sender (whose
            // announced names were sanitize()'d) or any key/manifest drift
            // simply failed the itemDecisions[name] != "pending" continue —
            // the host's tap vanished without a trace and the sender sat
            // "Waiting" (the intermittent Accept-does-nothing). Every
            // pre-decision key is now FIRST tried as-is (the GP17+ raw-name
            // identity), then, if it matches no top-level, re-keyed through
            // the SAME sanitize() transform the old announce used — the
            // exact equivalence that heals an old-sender placeholder. A key
            // that matches nothing at all (a genuinely different manifest)
            // is the only drop, and it is traced, never silent.
            val accepted = mutableListOf<String>()
            val rawTopLevels = push.topLevelNames
            fun resolveKey(name: String): String? {
                if (name in rawTopLevels) return name
                val sanitized = sanitize(name)
                return rawTopLevels.firstOrNull { sanitize(it) == sanitized }
            }
            for ((name, decision) in preDecisions) {
                val key = resolveKey(name)
                if (key == null) {
                    AcceptTrace.record(push.id, key = name, stage = "manifest-apply",
                        outcome = "no-matching-item (pre-decision not applicable to this manifest)")
                    continue
                }
                if (push.itemDecisions[key] != "pending") continue
                if (decision == "accepted") {
                    push.itemDecisions[key] = "accepted"
                    accepted.add(key)
                } else if (decision == "declined") {
                    push.itemDecisions[key] = "declined"
                }
            }
            if (accepted.isNotEmpty()) {
                precreateNativePushDirs(push, accepted.toSet())
                registerPendingTransfersFor(push, accepted)
            }
            refreshNativePushNotif()
        } else {
            val stillPending = pendingNativePushes.values.count { awaitingHostDecision(it) }
            // Only fire a NEW system notification (sound/banner) if this
            // manifest wasn't already announced — apiNativePushAnnounce
            // already alerted the host once for this exact transfer;
            // alerting again here would sound/flash twice for what the
            // host correctly perceives as one incoming push. The in-app
            // incoming sheet (SendFragment, polling pendingNativePushList
            // every ~500ms) picks up the now-complete details on its own
            // next tick regardless, with no separate refresh call needed.
            if (!wasAnnounced) {
                com.lumidrop.x.service.ServerForegroundService.notifyPendingNativePush(
                    context, fromName, push.totalFiles, stillPending)
            }
        }
        // ── Pass-15 (per-item instant action — the pass-14 root-cause fix) ──
        // THE reported bug: accepting ONE item of a multi-item push started
        // nothing until EVERY item was decided. The pass-14 sender-side
        // progressive dispatch is gated on the receiver's status response
        // carrying the per-item `decisions` map — but THIS endpoint (the
        // manifest POST, the FIRST response the sender reads) never sent
        // it, so every pass-14 sender fell straight into the legacy
        // aggregate flow ("wait for the whole push to leave 'pending'")
        // regardless of receiver version. Sending the same map
        // apiNativePushStatus already sends arms progressive dispatch from
        // the very first poll — and, when the host pre-accepted placeholder
        // items, directly in this response: the accepted item streams the
        // moment its manifest lands, the others wait for their own
        // decisions, exactly as reported required.
        val decisionsJson = JSONObject()
        for (name in push.topLevelNames) decisionsJson.put(name, push.itemDecisions[name] ?: "pending")
        return json(JSONObject().put("requestId", id).put("status", push.status)
            .put("receivingConcurrency", TransferManager.receivingConcurrencyLimit())
            .put("decisions", decisionsJson)
            .put("excludedNested", excludedNestedJson(push)))
    }

    private fun apiNativePushStatus(s: IHTTPSession): Response {
        sweepStalePendingNativePushes()
        val id = s.parameters["id"]?.firstOrNull() ?: return jsonErr("No id")
        val push = pendingNativePushes[id]
        push?.lastActivityAt = System.currentTimeMillis()   // dead-session sweep liveness (see sweepStalePendingNativePushes)
        // ── FINAL PASS 4 (§3 — "unknown" vs "rejected"). The old answer for
        // an absent id was always "rejected": a record lost to any sweep,
        // a restart, or a never-landed registration was INDISTINGUISHABLE
        // from a genuine host decline, so the sender recorded Declined and
        // stopped — the user sees "receiver declined" when the receiver
        // never got to decide at all (and, pre-keep-alive, a ZIP
        // placeholder swept mid-build answered exactly this way). A new
        // receiver now distinguishes the two: "unknown" means "I have no
        // record of this id" — the sender treats that as a lost record and
        // RE-ANNOUNCES + RE-POSTS once (both endpoints are idempotent by
        // id), healing the loss instead of mislabeling it. OLD senders
        // ignore the distinction (they only compare against "pending"/
        // "rejected" — "unknown" keeps them polling until their own
        // unreachability bound closes, byte-identical behavior); OLD
        // receivers never send it (absent-field default below keeps the
        // legacy "rejected" answer for them).
        val status = push?.status
            ?: if (s.parameters["v"]?.firstOrNull() == "4") "unknown" else "rejected"
        // GOAT v2 (item 2): which top-level items the receiver turned down
        // — only meaningful once status is "partial" or "rejected", but
        // always present (empty otherwise) so the sender's parsing never
        // has to branch on whether the field exists.
        //
        // GOAT (critical fix — sender/receiver concurrency mismatch): tells
        // the sender how many files THIS device will actually admit at
        // once — see NativePushClient.runSession, which caps its own
        // per-session dispatch to this number instead of always using its
        // own fixed MAX_CONCURRENT_FILES. GOAT-PASS 2: the value is now
        // permanently 1 (the specification pins transfer concurrency at
        // exactly 1), and the sender hard-caps its own dispatch at 1 as
        // well, so a mixed-version peer advertising a wider gate can never
        // coax parallel dispatch. Without this field, a sender pushing
        // more files at once than the receiver's own concurrency setting
        // allows queues the extras behind TransferManager.
        // acquireReceivingSlot with no upper bound on the wait — and with
        // hundreds of files and a receiving concurrency of 1, that wait
        // can comfortably exceed the sender's own fixed connection
        // timeout, which is exactly what used to make later files in a
        // large folder fail outright even though nothing was actually
        // broken, just busy.
        //
        // ── Pass-14 (per-item instant action): `decisions` additionally ─────
        // carries every top-level item's CURRENT decision the moment it is
        // made — accepted / declined / pending — so the sender's progressive
        // dispatcher can begin streaming an item the instant ITS decision
        // lands, without waiting for the whole push's aggregate status to
        // leave "pending" (the old contract, under which accepting ONE file
        // of a five-file batch started nothing until all five were decided).
        // Old senders ignore the extra field; old receivers just never send
        // it (the sender treats its absence as the old aggregate-only flow).
        //
        // ── GOAT-PASS 14 (Phase 1, §4 — folder progress synchronization): ─────
        // `excludedNested` additionally carries every top-level folder's
        // CURRENT per-child exclusions (the receiver's ✕ declines inside an
        // expanded incoming folder — see excludeNativePushNestedPath), as
        // natural paths relative to that top-level item. The sender's poll
        // tick resolves the matching pre-registered rows Declined on the
        // spot, so BOTH sides converge on the same eligible-child total:
        // the receiver's "2/25" is the sender's "2/25", the folder completes
        // 25/25 on both, and the sender can never sit stuck at "25/30" over
        // children the receiver explicitly turned down. Absent on old
        // receivers (the sender keeps its legacy behavior); ignored by old
        // senders.
        val decisionsJson = JSONObject()
        for (name in push?.topLevelNames ?: emptyList()) decisionsJson.put(name, push?.itemDecisions?.get(name) ?: "pending")
        return json(JSONObject().put("status", status)
            .put("declinedTopLevel", JSONArray(push?.declinedTopLevel ?: emptyList<String>()))
            .put("receivingConcurrency", TransferManager.receivingConcurrencyLimit())
            .put("decisions", decisionsJson)
            .put("excludedNested", excludedNestedJson(push)))
    }

    /** ── GOAT-PASS 14 (Phase 1, §4): the wire form of a push's per-child ──────
     *  exclusions — { "<topLevel>": ["Subfolder/IMG.jpg", ...] }. Empty (but
     *  present) for a push with no exclusions and for an unknown/expired push,
     *  so a sender's parsing never has to branch on field presence. Shared by
     *  apiNativePushStatus and apiNativePushRequest's responses. */
    private fun excludedNestedJson(push: PendingNativePush?): JSONObject {
        val out = JSONObject()
        if (push == null) return out
        for ((top, set) in push.excludedNestedByTopLevel) {
            if (set.isEmpty()) continue
            out.put(top, JSONArray(set.toList()))
        }
        return out
    }

    private fun apiNativePushCancel(s: IHTTPSession): Response {
        val id = s.parameters["id"]?.firstOrNull()
        if (id != null) {
            // Pass-8 follow-up (auto-accept sync): the sender withdrawing the
            // whole push (empty manifest after announce, or a cancel racing
            // its own manifest) must also resolve every still-PENDING row the
            // push pre-registered — auto-accept creates them the instant the
            // manifest lands, and without this they sat "Waiting" forever on
            // a transfer that will never arrive.
            pendingNativePushes.remove(id)?.let { resolvePendingRowsForVanishedPush(it) }
            refreshNativePushNotif()
        }
        return json(JSONObject().put("success", true))
    }

    /** Pass-8 follow-up (dead-session sweep guard): true while ANY of this
     *  push's pre-registered rows is queued for or holding a receiving slot
     *  — i.e. a file POST for this push is genuinely live right now, so the
     *  push is NOT dead however long ago its last touch was (one big file
     *  can stream past the whole TTL with no other request in between). */
    private fun pushHasInFlightReceive(push: PendingNativePush): Boolean {
        for (e in push.entries) {
            if (e.isDir || e.isText) continue
            val tid = TransferManager.findTransferByUploadRequestId("${push.id}::${e.path}")?.id ?: continue
            if (TransferManager.isReceivingInFlight(tid)) return true
        }
        return false
    }

    // ── GOAT-PASS 7 (§2C): does any UNDELIVERED file of this push still hold ──
    // bytes on disk (a staging partial, or a completed destination the rest of
    // the batch never got past)? Such a push is the live resume story for a
    // sender that comes back late — see sweepStalePendingNativePushes's
    // extended-grace branch. Cheap by design: a straight-line walk of the
    // manifest entries with one File.exists()/length() per file (manifests are
    // bounded by the send-time entry cap), on the sweep's existing cadence.
    private fun pushHasResumablePartial(push: PendingNativePush): Boolean {
        for (e in push.entries) {
            if (e.isDir || e.isText) continue
            try {
                val dest = File(push.destRoot, push.resolvePath(e.path))
                if (dest.isFile && dest.length() > 0L) return true
                val staging = stagingFileFor(dest)
                if (staging.isFile && staging.length() > 0L) return true
            } catch (ex: Exception) { /* one unreadable entry never decides the sweep */ }
        }
        return false
    }

    /** Pass-8 follow-up (auto-accept sync): finalizes every still-PENDING
     *  pre-registered row of a push whose record is going away (sender
     *  cancel, TTL sweep, dead-session sweep) — nothing will ever claim them
     *  now, so the honest terminal state is FAILED ("interrupted"), never a
     *  permanently-stuck "Waiting". Rows already progressed past PENDING
     *  (streaming, done, cancelled) are untouched. */
    private fun resolvePendingRowsForVanishedPush(push: PendingNativePush) {
        var resolved = 0
        for (e in push.entries) {
            if (e.isDir || e.isText) continue
            val reqId = "${push.id}::${e.path}"
            val tid = TransferManager.findPendingUploadId(reqId) ?: continue
            TransferManager.fail(tid, interrupted = true)
            resolved++
        }
        if (resolved > 0) {
        }
    }

    // How many bytes of this ONE manifest entry the receiver already has —
    // lets NativePushClient resume a file from where a dropped connection
    // left off instead of restarting it ("support resumable transfers").
    // GOAT v2 (item 11): reads the STAGING file, not the final destination
    // — while a transfer is in progress the real bytes-so-far live there
    // (see writeRawStream/apiNativePushFile below); the final path only
    // ever holds a genuinely complete file. Falls back to the final path
    // for the case where an earlier attempt actually finished and got
    // renamed but the sender's own bookkeeping doesn't know that yet — same
    // "already fully delivered" signal it always gave, just correct now
    // about which file it's really reading either way.
    private fun apiNativePushFileStatus(s: IHTTPSession): Response {
        val id = s.parameters["id"]?.firstOrNull() ?: return jsonErr("No id")
        // GOAT (Phase 1, item 2 root cause): see apiUploadRequest's doc
        // comment -- s.parameters is already decoded by NanoHTTPD.
        val path = s.parameters["path"]?.firstOrNull()
            ?: return jsonErr("No path")
        val push = pendingNativePushes[id] ?: return jsonErr("Unknown session")
        if (path.contains("..")) return jsonErr("Invalid path")
        push.lastActivityAt = System.currentTimeMillis()   // dead-session sweep liveness (see sweepStalePendingNativePushes)
        // ── Pass-11 (ghost-transfer root fix): the probe consults the same ──
        // durable tombstone the file endpoint does. A sender whose receiver
        // just cancelled this file learns it HERE — on the checkpoint probe
        // that precedes every re-POST — and resolves its row Cancelled
        // without ever re-sending a byte. Previously this endpoint happily
        // reported the preserved staging size (the tombstone had died with
        // the 400ms-dismissed row), the sender re-POSTed the remainder, and
        // the start() fallback minted the ghost ACTIVE row.
        // ── GOAT-PASS 11 (§7): the probe answers a whole-push cancel ────────
        // explicitly. The old probe consulted only the per-file tombstone
        // (capped at 128 slots — mostly evicted for a large folder) and the
        // live row, so a sender backing off mid-auto-resume kept getting
        // "existingBytes" back from a folder the user had just cancelled,
        // re-POSTed the remainder, and ground through the refusal cycle
        // again — for up to the full 10-minute window per file, across
        // every remaining file. pushCancelled makes the sender resolve the
        // file Cancelled AND tear the whole session down in one step.
        if (isPushCancelled(id)) {
            return json(JSONObject().put("pushCancelled", true).put("cancelled", true).put("existingBytes", 0L))
        }
        // A declined top-level item is equally terminal for this file — the
        // POST that follows would be refused "Not accepted", so the probe
        // must not coach the sender into re-sending a checkpoint the
        // decision gate will never accept. "declined" (not "cancelled")
        // keeps the sender's per-row verdict honest: Declined for an item
        // the host turned down, Cancelled for a deliberate cancel.
        if (push.itemDecisions[topLevelNameOf(path)] != "accepted") {
            return json(JSONObject().put("declined", true).put("existingBytes", 0L))
        }
        // ── GOAT-PASS 14 (Phase 1, §4): a child the host removed from an ────────
        // otherwise-accepted folder (the ✕ inside the expanded incoming sheet —
        // see excludeNativePushNestedPath) answers the probe as a decline, so
        // the sender resolves its row Declined without ever streaming a byte
        // of a file that apiNativePushFile would only refuse after the fact.
        // (Before this, the probe happily reported a checkpoint, the sender
        // streamed the WHOLE excluded file, and only the body-completing POST
        // learned it had been turned down — pure waste, and the row's Declined
        // resolution was delayed by the entire transfer.)
        if (push.isExcludedNested(path)) {
            return json(JSONObject().put("declined", true).put("existingBytes", 0L))
        }
        if (TransferManager.wasCancelledForUpload("$id::$path")) {
            return json(JSONObject().put("cancelled", true).put("existingBytes", 0L))
        }
        // Phase 5 (v3 §6 item 1): the sender now declares the identity it
        // expects to resume against — its source file's size and mtime. A
        // partial that belongs to any OTHER version of the file (the source
        // changed between attempts, or the staging bytes are simply not
        // validatable) reports 0 so the sender restarts cleanly from byte 0
        // — and the stale partial is purged on the spot rather than left to
        // poison a future attempt. Legacy senders that declare nothing keep
        // the old best-effort answer.
        // ── Pass-16: srcFp is the sender's 4 KB content fingerprint — the ──
        // identity that survives cross-sender mtime drift (the same original
        // file on two devices carries two mtimes). fp validated against the
        // partial we are HOLDING wins; the (size, mtime) sidecar rules stay
        // as the legacy-sender fallback exactly as before.
        // ── GOAT-PASS 14 (Phase 1, §1.8 — unknown-size entries, i.e. the new ──
        // streamed zip-batch sends): an archive whose total size was never
        // declared can never be byte-resumed against a staging partial — a
        // synthesized archive has no stable per-offset identity contract the
        // receiver could validate (no fp, no mtime), so stitching a new
        // generation onto an old partial could silently produce a corrupt
        // archive. Instead: a partial always answers 0 (the sender restarts
        // the archive cleanly, exactly what a failed browser zip download
        // does), and a COMPLETED destination answers complete=true so a
        // re-send of an already-delivered archive short-circuits Done with
        // zero bytes re-streamed.
        val declaredSize = s.parameters["size"]?.firstOrNull()?.toLongOrNull() ?: -1L
        val srcMtime     = s.parameters["mtime"]?.firstOrNull()?.toLongOrNull() ?: 0L
        // ── Pass-16: the sender's 4 KB content fingerprint. ──
        val srcFp = s.parameters["fp"]?.firstOrNull()
        // GOAT-PASS 14: the unknown-size branch below must only fire for a
        // sender that EXPLICITLY declared a negative size (the zip-batch
        // "size=-1" of the new app-side sends) — a legacy sender that omits
        // the parameter entirely keeps the ordinary best-effort answer the
        // existing code below already gives it.
        val sizeParam = s.parameters["size"]?.firstOrNull()
        if (sizeParam != null && declaredSize < 0L) {
            val destForUnknown = File(push.destRoot, push.resolvePath(path))
            return if (destForUnknown.isFile)
                json(JSONObject().put("existingBytes", destForUnknown.length()).put("complete", true))
            else
                json(JSONObject().put("existingBytes", 0L))
        }
        val dest = File(push.destRoot, push.resolvePath(path))
        // ── Pass-15 (ghost-item ask-to-resend): the receiver deliberately ──
        // DISCARDED this file's partial (X on the WFR card) — tell the
        // sender so it can ask its user whether to send the whole file from
        // the beginning again or cancel outright, instead of silently
        // re-sending from 0% the moment the connection returns.
        if (wasReceiveDiscarded("$id::$path", dest.absolutePath)) {
            return json(JSONObject().put("discarded", true).put("existingBytes", 0L))
        }
        val staging = stagingFileFor(dest)
        val existingBytes: Long
        if (dest.exists()) {
            // Completed earlier — the dest carries the source's own mtime
            // (set at completion), so "already fully delivered" is only
            // honest when that version still matches what's being sent now.
            val sameVersion = (declaredSize < 0 || dest.length() == declaredSize) &&
                (srcMtime <= 0 || dest.lastModified() == srcMtime)
            existingBytes = if (sameVersion) dest.length() else 0L
            if (!sameVersion) {
                deleteStagingFor(dest)   // any stale partial of the old version goes too
            }
        } else if (staging.exists()) {
            // ── Pass-16: content fingerprint FIRST, sidecar metadata ──
            // second. An fp match proves the partial we hold starts with
            // exactly the bytes the sender is about to send — true even
            // across DIFFERENT sender devices whose mtimes disagree, which
            // is the cross-sender resume the old (size, mtime) check used
            // to reject (the reported "resumes from 0 instead of the
            // interrupted point"). A failed fp + failed sidecar purges the
            // stale partial and restarts cleanly, exactly as before.
            val fpOk = if (declaredSize >= 0) stagingFingerprintMatches(dest, declaredSize, srcFp) else false
            val metaOk = stagingMetaMatches(dest, declaredSize, srcMtime)
            existingBytes = if (fpOk || metaOk) staging.length() else 0L
            if (fpOk || metaOk) {
                if (existingBytes > 0) {
                }
            } else {
                deleteStagingFor(dest)
            }
        } else {
            existingBytes = 0L
        }
        // Pass-8 follow-up (auto-accept sync — the "instantly sent but stuck
        // Waiting" root fix, receiver half): when this probe answers "you
        // already have all of it", the sender's sendOneFile short-circuits
        // DONE without ever POSTing the file — so the pre-registered PENDING
        // row auto-accept created for it (registerPendingTransfersFor) would
        // sit "Waiting" forever, nothing else ever claiming it. Complete it
        // right here, under the same composite id the claim chain uses, with
        // the real byte count. Idempotent by construction: once completed,
        // claimPendingUpload finds no PENDING row and returns null.
        if (dest.exists() && existingBytes > 0L && (declaredSize <= 0L || existingBytes >= declaredSize)) {
            val reqId = "$id::$path"
            TransferManager.claimPendingUpload(reqId, dest.absolutePath)?.let { tid ->
                TransferManager.updateProgress(tid, existingBytes)
                // ── FINAL PASS 3 (§1): an unknown-size entry (a streamed ──
                // archive) claimed here by its "already fully delivered"
                // probe still carries totalBytes = -1 — stamp the real
                // on-disk size (existingBytes IS dest.length() for the
                // matching version) so the completed row and its History
                // entry render the actual size instead of "?".
                if (declaredSize < 0L) TransferManager.stampFinalSize(tid, existingBytes)
                TransferManager.complete(tid)
                bumpFilesVersion(dest.absolutePath)
            }
        }
        // ── Pass-19 (cross-device pause sync, receiver half) ────────────────
        // The receiver's row for THIS file is deliberately paused (the
        // receiving user tapped Pause, or a sender-propagated "pause" control
        // landed). Saying so on the probe lets the sender mirror the pause and
        // park instead of hammering a peer that asked to wait — and lets the
        // blocked-write self-heal distinguish "receiver paused us" from a
        // dead socket. Only reported while the row is PENDING/ACTIVE and
        // pauseRequested; old senders ignore the field entirely.
        val pausedOnReceiver = TransferManager.findTransferByUploadRequestId("$id::$path")?.let { t ->
            t.pauseRequested && (t.status == TransferManager.Status.PENDING || t.status == TransferManager.Status.ACTIVE)
        } ?: false
        if (pausedOnReceiver) {
            return json(JSONObject().put("paused", true).put("existingBytes", 0L))
        }
        return json(JSONObject().put("existingBytes", existingBytes))
    }

    private fun apiNativePushFile(s: IHTTPSession): Response {
        val id = s.parameters["id"]?.firstOrNull() ?: return jsonErrClose("No id")
        // GOAT (Phase 1, item 2 root cause -- Section 3b's 340-file batch
        // failure): NanoHTTPD's decodeParms() already URL-decodes every
        // query-parameter value (it runs each one through
        // URLDecoder.decode() itself while populating s.parameters), so
        // s.parameters["path"] here already reads back exactly what the
        // sender's relPath was. Decoding it a SECOND time silently mangled
        // any path containing a literal '+': the sender safely escapes a
        // real '+' to "%2B" and a real space to "%20" (see
        // NativePushClient.streamFile's encodedPath), NanoHTTPD's one
        // decode pass correctly turns "%2B" back into "+", but a second
        // decode pass then wrongly reads that bare "+" AS an encoded space
        // and turns it into " " instead. That corrupted relPath no longer
        // matches the top-level name the manifest was accepted under, so
        // the itemDecisions lookup below missed for every file nested
        // under a "+"-named folder/file and this endpoint returned "Not
        // accepted" (jsonErr -> HTTP 400) for all of them -- exactly the
        // symptom in Section 3b (full body already sent, then a clean 400
        // for literally every file in that batch, while transfers with no
        // '+' anywhere in their path were never affected).
        val relPath = s.parameters["path"]?.firstOrNull()
            ?: return jsonErrClose("No path")
        val declaredLen = s.parameters["size"]?.firstOrNull()?.toLongOrNull() ?: -1L
        val offset = s.parameters["offset"]?.firstOrNull()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        val mtime = s.parameters["mtime"]?.firstOrNull()?.toLongOrNull() ?: 0L

        val push = pendingNativePushes[id] ?: return jsonErrClose("Unknown or expired session")
        if (relPath.contains("..")) return jsonErrClose("Invalid path")
        push.lastActivityAt = System.currentTimeMillis()   // dead-session sweep liveness (see sweepStalePendingNativePushes)
        // ── GOAT-PASS 11 (§7): the PUSH-level cancelled latch — the whole ──
        // folder was deliberately cancelled, so this request is refused no
        // matter what any per-file tombstone or item decision says (a
        // 694-file cancel used to evict most per-file tombstones on the
        // floor via the cap, and a swept record takes the decisions with
        // it — each re-POST then minted a fresh ghost row under the
        // cancelled folder's group). The distinct "Push cancelled by host"
        // body lets the sender classify this as a WHOLE-PUSH cancel and
        // tear its session down instead of churning through the remaining
        // hundreds of files one refusal at a time. jsonErrClose so a
        // body-bearing POST dies with the refusal instead of riding
        // keep-alive into a zombie write.
        if (isPushCancelled(id)) return jsonErrClose("Push cancelled by host")
        // GOAT v2 (item 2): gated per top-level item now, not per whole
        // push — a file belonging to a declined (or still-undecided) item
        // is refused even if some OTHER item in this same push is
        // accepted, which is the entire point of individual accept/decline.
        // ── FINAL PASS 1 (§3.4 — keep-alive body hygiene) ─────────────────────
        // EVERY refusal from here to the streaming section answers a POST
        // whose full BODY is still inbound (often already written by the
        // sender). Answering with the connection kept alive leaves those
        // unread body bytes on the socket, where the session's next
        // request-header parse swallows them as garbage — a corrupted
        // keep-alive connection whose subsequent requests (the next child's
        // probe, its re-POST) fail in ways that look exactly like the
        // reported "receiver stuck at an intermediate child while the
        // sender kept going". The sender's classifier matches on the error
        // BODY, which jsonErrClose preserves byte-for-byte; only the
        // Connection: close header is added, so the sender drops the socket
        // instead of reusing a poisoned one.
        if (push.itemDecisions[topLevelNameOf(relPath)] != "accepted") return jsonErrClose("Not accepted")
        // GOAT (Part 2 completion — folder accept/decline, "removing an
        // internal item must not accidentally remove unrelated items"):
        // refused independently of whether a PENDING entry exists for it —
        // a sender that doesn't yet know about this exclusion (nothing in
        // the wire protocol currently tells it) would otherwise still
        // succeed in writing a file the host explicitly removed.
        if (push.isExcludedNested(relPath)) return jsonErrClose("Excluded by host")

        val resolvedPath = push.resolvePath(relPath)
        val dest = File(push.destRoot, resolvedPath)
        dest.parentFile?.mkdirs()
        // GOAT v2 (item 11): the composite id below is a pre-registered
        // PENDING row (see registerPendingTransfersFor) for every file
        // whose item was already accepted at approval time — claim and
        // promote that one instead of minting a second, duplicate
        // Transfers-tab row. Falls back to a fresh ACTIVE entry only for a
        // file that somehow never got pre-registered (belt-and-braces; the
        // accept path above always creates one for every accepted item).
        //
        // Source.NATIVE_PUSH here (unlike a plain browser upload, which
        // stays Source.PLAIN) is what makes this transfer eligible for
        // state-sync propagation below — see propagateNativeControl and
        // TransfersFragment's pause/resume/cancel handlers. It's safe to
        // tag on the receiving side too now: Retry stays gated separately
        // by direction == SEND (see TransfersFragment.buildActionButtons),
        // so this doesn't resurrect the retry-button-with-no-action
        // problem a receiving-side entry would otherwise have.
        // GOAT (transfer stability pass — unified receiving concurrency):
        // waits here — visibly "Waiting" (still PENDING), not yet "Active"
        // — for a free receiving slot before promoting. Only gated when the
        // pre-registered PENDING row from registerPendingTransfersFor is
        // actually found; the belt-and-braces start() fallback just below
        // stays exactly as it was for the rare file that somehow never got
        // pre-registered, rather than making that fallback depend on a
        // precondition it never used to need.
        // Pass-8 (§13 — receiver-side Cancel All must actually cancel every
        // transfer): a native-push file whose pre-registered row was already
        // cancelled (Cancel All, group cancel, whole-push cancel) used to fall
        // through findPendingUploadId to the start() fallback below, minting a
        // FRESH ACTIVE row and streaming the file anyway — the cancelled batch
        // quietly resurrecting itself one file at a time, exactly the "entries
        // disappear but files keep arriving" report. The tombstone window here
        // refuses the sender's POST for the same short period the browser-upload
        // path already enforces (see apiUpload's identical check), and the
        // scope=push propagation stops the sender for good.
        if (TransferManager.wasCancelledForUpload("${id}::$relPath")) return jsonErrClose("Cancelled by host")
        // ── Pass-15 (ask-before-resend, receiver half) ─────────────────────
        // The receiver DISCARDED this file's partial (the X on the Waiting-
        // for-reconnection card) while the sender was still retrying. An
        // unconfirmed re-POST is refused with the same deliberate-cancel
        // answer the sender's streamFile classifier treats as final — a
        // racing POST from before the sender's user answered can never
        // resurrect the transfer. The sender's CONFIRMED resend (its user
        // answered "Send again from 0%" — streamFile appends confirmed=1)
        // clears the receipt below and streams as a genuinely new transfer.
        val resendConfirmed = s.parameters["confirmed"]?.firstOrNull() == "1"
        if (wasReceiveDiscarded("${id}::$relPath", dest.absolutePath)) {
            if (!resendConfirmed) return jsonErrClose("Cancelled by host")
            clearDiscardedReceive("${id}::$relPath", dest.absolutePath)
        }
        val pendingId = TransferManager.findPendingUploadId("${id}::$relPath")
        // ── Pass-20 (receiver pause keeps progress AND position, claim half): ──
        // A row deliberately parked ACTIVE+paused by the paused-abort branch
        // above (the sender yielded its POST on purpose) is resolved HERE so
        // the sender's resumed POST continues on the very SAME card —
        // progress bar intact at its checkpoint, Active-tab position
        // unchanged — instead of falling through the interrupted/new-row
        // paths that used to mint a fresh identity or flash WFR.
        val pausedActiveId = if (pendingId == null)
            TransferManager.findPausedActiveUploadId("${id}::$relPath") else null
        // ── Pass-17 (one card per transfer, receiver half): a re-POST whose ──
        // predecessor row is still NOMINALLY ACTIVE (its connection died
        // silently; the read is blocked; the stall watchdog hasn't fired yet)
        // must ADOPT that row — the ordinary interrupted claim chain below
        // resolves it once it reads FAILED. Without this the chain minted a
        // FRESH row beside the lingering one, and when the old leg's read
        // finally erroled it failed the ORIGINAL row into "Waiting for
        // reconnection": the reported duplicate pair (one WFR card, one
        // continuing card, same file). A null answer means the old leg is
        // FRESHLY streaming (bytes < 2.5 s ago) — refuse instead, so two
        // writers can never interleave on one staging file.
        val staleRetire = if (pendingId == null && pausedActiveId == null)
            TransferManager.retireStaleActiveUploadForRequest("${id}::$relPath",
                stagingFileFor(dest).let { it.exists() && it.length() > 0L }) else false
        if (staleRetire == null) {
            // GOAT-PASS 5 (§8): a body-bearing duplicate POST must die with
            // its refusal — never ride keep-alive into a zombie read.
            return jsonErrClose("Transfer already in progress")
        }
        // GOAT (continuation brief §3.1 -- confirmed bug, receiving-slot
        // leak): the slot MUST be released under the exact same id it was
        // acquired under. The old code acquired under pendingId but always
        // released under tid -- invisible whenever claimPendingUpload
        // succeeds (tid == pendingId then), but the two diverge the moment
        // claimPendingUpload fails and tid falls through to a fresh
        // TransferManager.start() id instead (e.g. this entry was already
        // flipped to CANCELLED -- see TransferManager.requestCancel -- by
        // a group cancel that landed between findPendingUploadId above and
        // claimPendingUpload below). releaseReceivingSlot's own semaphore
        // release is unconditional either way, so no permit was ever
        // leaked -- but receivingSlotHolders.remove(tid) then removes a
        // key that was never added, leaving pendingId stuck in that set
        // permanently. Traced sweepStalledActive (the only reader of
        // receivingSlotHolders): it only ever matches an id whose Transfer
        // is still ACTIVE, and a leaked pendingId here belongs to an
        // already-CANCELLED entry, so this specific leak was cosmetic
        // bookkeeping drift, not a lost concurrency permit -- but it's a
        // real, confirmed mismatch regardless, and worth being exact about
        // now that a slot's whole lifecycle depends on a single consistent
        // id. Capturing the acquired id itself (rather than a bare
        // Boolean) makes that invariant impossible to violate here again.
        //
        // Pass-10 (automatic resume): a RE-POST for a file whose receiver
        // row already FAILED as Interrupted (the connection died mid-file
        // and the sender's automatic resume is re-sending the remainder)
        // re-opens the ORIGINAL row in place — one row flipping back to
        // Active with progress continuing from the preserved checkpoint,
        // never a duplicate minted beside the lingering Interrupted one.
        // The interrupted row is only LOOKED UP here (read-only) so it can
        // double as the receiving-slot wait key — it is re-opened AFTER a
        // slot is granted, keeping the "Waiting until a slot is free"
        // discipline intact for the resumed leg exactly like a fresh one.
        val interruptedId = if (pendingId == null && pausedActiveId == null)
            TransferManager.findInterruptedUploadId("${id}::$relPath") else null
        // ── FINAL PRODUCTION PASS 18 (§5/§6 — the queue turn must be visible, ──
        // never a silent unbounded block): the old call waited FOREVER for a
        // receiving slot. A queued POST read none of its body and answered
        // nothing, so a sender whose turn took longer than its 45-second
        // read timeout classified the dead wait as a network failure,
        // RETRYABLE'd, and its re-POST re-entered the FIFO TAIL — later
        // files of a large batch starved at that edge behind one slow first
        // file, the sender's rows sat ACTIVE-with-zero-progress and the
        // receiver's mirror rows sat PENDING: exactly the residual
        // sender-Waiting / receiver-nothing shape Pass 18 reopens. The wait
        // is now bounded to SLOT_WAIT_MAX_MS; a live uncancelled waiter that
        // hasn't reached its turn answers 425 TOO EARLY with the live queue
        // depth, which the FP18 sender classifies as "queued, retry soon"
        // — no backoff burn, no socket-timeout guesswork, and the FIFO
        // position is preserved across the retry (the re-POST re-enters the
        // queue exactly as before, but within seconds, not after 45 s of
        // dead air).
        val heldSlotId: String? = if (pendingId != null) {
            when (TransferManager.acquireReceivingSlotBounded(pendingId, SLOT_WAIT_MAX_MS)) {
                TransferManager.SlotWait.CANCELLED -> return jsonErrClose("Cancelled by host")
                TransferManager.SlotWait.STILL_QUEUED -> return jsonQueued()
                else -> {}
            }
            pendingId
        } else (pausedActiveId?.let { pid ->
            // ── Pass-20: the parked receive row re-acquires a slot exactly ──
            // like any other continuation — its old permit was force-yielded
            // by schedulePausePermitYield while it waited out the pause, and
            // the resumed leg waits its FIFO turn (with the ordinary
            // prioritized bump requestResume already applied) so the batch's
            // pacing stays exactly as disciplined as a first dispatch.
            when (TransferManager.acquireReceivingSlotBounded(pid, SLOT_WAIT_MAX_MS)) {
                TransferManager.SlotWait.CANCELLED -> return jsonErrClose("Cancelled by host")
                TransferManager.SlotWait.STILL_QUEUED -> return jsonQueued()
                else -> {}
            }
            pid
            // ── Pass-22 (compile fix): the original chained a second `else`
            // onto this if — two else branches is a syntax error, so this
            // path could never build. The interrupted-row lookup is the
            // fallthrough the elvis below expresses, exactly as intended.
        } ?: interruptedId?.let { iid ->
            when (TransferManager.acquireReceivingSlotBounded(iid, SLOT_WAIT_MAX_MS)) {
                TransferManager.SlotWait.CANCELLED -> return jsonErrClose("Cancelled by host")
                TransferManager.SlotWait.STILL_QUEUED -> return jsonQueued()
                else -> {}
            }
            iid
        })
        // ── Pass-17 (host-cancel finality, queued native push): the request ──
        // may have spent a long time waiting for a receiving slot — a host
        // Cancel landing during that wait must still be honored, or the
        // claim chain below falls to the start() fallback and streams the
        // whole file the host just killed.
        if (TransferManager.wasCancelledForUpload("${id}::$relPath")) {
            if (heldSlotId != null) TransferManager.releaseReceivingSlot(heldSlotId)
            return jsonErrClose("Cancelled by host")
        }
        val tid = (if (heldSlotId != null) TransferManager.claimPendingUpload("${id}::$relPath", dest.absolutePath) else null)
            ?: pausedActiveId   // Pass-20: the parked row continues in place — same id, same card, same position
            ?: interruptedId?.let { TransferManager.reopenInterruptedUploadByExactId(it, dest.absolutePath) }
            ?: TransferManager.start(TransferManager.Direction.UPLOAD, resolvedPath, declaredLen,
                push.fromIp, push.fromName, source = TransferManager.Source.NATIVE_PUSH, filePath = dest.absolutePath,
                // ── GOAT-PASS 10 (§4 — never mint a PARENTLESS row for a ──
                // folder's file): the fallback fires when the pre-registered
                // PENDING row is already gone (consumed, cancelled, swept),
                // and it used to carry NO groupId — a file of a FOLDER push
                // then rendered as a detached, independent top-level card
                // NEXT TO its own parent folder in the Active tab, exactly
                // the "folder's internal items appear outside their parent"
                // report. Re-attach it to the push's group for this
                // top-level name (published by registerPendingTransfersFor;
                // null only for a genuinely top-level FILE, which correctly
                // stays a Solo row). The uploadRequestId tag below keeps its
                // own Pass-15 ghost-prevention role unchanged.
                groupId = push.groupIdByTopLevel[topLevelNameOf(relPath)],
                // ── Pass-15 (ghost-item root fix, receiver half): the start() ──
                // fallback is exactly what a re-send-after-discard takes (the
                // pre-registered row was consumed by the discard) — tag it with
                // the composite request id so a later Cancel of this row writes
                // the durable tombstone that refuses every subsequent re-POST,
                // instead of silently resurrecting a fresh ghost row each time.
                uploadRequestId = "${id}::$relPath",
                // ── GOAT-PASS 15 (§3.4): the fallback row of an unknown-size
                // entry (the streamed zip-batch archive) carries the same
                // never-pausable marker its pre-registered twin would have.
                zipStream = declaredLen < 0L)
        // ── GOAT-PASS 6 (§4 — the receiving-slot ledger truth, native path) ──────
        // Mirrors the discipline GOAT-PASS 5's final round gave apiUpload, for
        // the NATIVE receive path — the two defects it closes here:
        //  (a) a claim chain that fell through to the fresh-row fallback while
        //      a slot was acquired under the wait key (the PENDING row got
        //      resolved between find and claim — a cancel/sweep landing during
        //      the gate wait) left the registration keyed to a DEAD id: the
        //      fresh row streamed but isExecuting answered false — a genuinely
        //      receiving row rendering the neutral "Waiting" chip while its
        //      progress climbed (the reported §4 state-consistency defect).
        //      rekeyReceivingSlot moves the registration onto the row that
        //      actually streams.
        //  (b) the rare nothing-to-wait-on fallback (no PENDING, paused-active
        //      or interrupted row existed at all) streamed with NO receiving
        //      permit — unslotted beside the gate, which is both another face
        //      of the §4 chip defect AND a real hole in the exactly-one-
        //      receiving-transfer contract. It now acquires its own permit
        //      through the ordinary gate — visibly Waiting until granted, then
        //      honestly executing.
        var slotReleaseId = heldSlotId
        if (heldSlotId != null && tid != heldSlotId) {
            TransferManager.rekeyReceivingSlot(heldSlotId, tid)
            slotReleaseId = tid
        } else if (heldSlotId == null) {
            // The wait rides a SYNTHETIC key (the download gate's downloadWaitKey
            // pattern), not the fresh row's id: an ACTIVE row parked at a gate
            // with zero bytes is exactly what the 15-second stall watchdog
            // resolves interrupted, and this request may legitimately queue
            // behind a large in-flight receive. The key is a pure ledger
            // identity — the post-grant tombstone re-check below carries the
            // deliberate-cancel semantics the row-id waiters get from
            // isCancelAuthoritative.
            val waitKey = "npwait:${push.id}::$relPath"
            if (!TransferManager.acquireReceivingSlot(waitKey)) return jsonErrClose("Cancelled by host")
            if (TransferManager.wasCancelledForUpload("${id}::$relPath")) {
                TransferManager.releaseReceivingSlot(waitKey)
                return jsonErrClose("Cancelled by host")
            }
            TransferManager.rekeyReceivingSlot(waitKey, tid)
            slotReleaseId = tid
        }
        registerNativeControlLink(tid, id, relPath, "http://${push.fromIp}:${push.fromPort}")
        // ── Pass-17 (one card per transfer): THIS leg's generation snapshot —
        // the !ok tail below consults it so a superseded leg (its connection
        // died right before a newer leg re-opened this row) can never fail,
        // park, or discard the partial of the transfer the newer leg now owns.
        val legResumeGen = TransferManager.resumeGenOf(tid)

        // GOAT v2 (item 11): every byte is written to a staging file next
        // to the real destination, never to the real destination itself —
        // see stagingFileFor's doc comment and writeRawStream below. Only
        // once writeRawStream confirms the transfer genuinely finished does
        // the rename below make it visible under its real name; a
        // cancelled/interrupted transfer leaves the staging file exactly
        // where it was (still the resume checkpoint apiNativePushFileStatus
        // reports back on the next attempt), and the real filename is never
        // touched at all until that point.
        val staging = stagingFileFor(dest)
        // Phase 5 (v3 §6 item 1): the offset must be backed by real bytes on
        // disk — a staging file shorter than the offset (swept, cancelled, or
        // left by a crashed attempt) would otherwise be seeked PAST by
        // writeRawStream, leaving a silent zero-hole in the middle of what
        // then finishes as a "successful" file. An unvalidatable offset is an
        // honest restart from byte 0, never a fake resume.
        var effOffset = offset
        if (offset > 0L) {
            val have = staging.exists() && staging.length() >= offset
            if (!have) {
                effOffset = 0L
            }
        }
        if (effOffset == 0L) {
            // Fresh start (or a validated restart): remove any pre-existing
            // partial outright so new bytes can never be stitched onto old,
            // and record THIS source version's identity ("size:mtime") for
            // the next file-status check to validate resumes against.
            if (staging.exists()) staging.delete()
            // Pass-14 (resumable receives): the fresh start consumed whatever
            // resumable state pointed at the old partial (a different source
            // version, or a resumed row whose identity check failed) — the
            // registry entry must go with it, or a restart would restore a
            // WFR card for partial data that no longer exists.
            ResumableReceiveStore.remove(context, dest.absolutePath)
            // ── Pass-15 (resume from a different sender, the OTHER half) ──
            // A fresh start whose ADOPTION was refused because the incoming
            // file is a DIFFERENT file (identity check failed) just deleted
            // the old partial — the lingering "Waiting for reconnection"
            // card is now data-less and must not sit next to the new item.
            // Prune every kept-for-resume row whose partial just vanished.
            TransferManager.pruneResumableRows { path ->
                path != null && stagingFileFor(File(path)).let { it.exists() && it.length() > 0L }
            }
            writeStagingMeta(dest, declaredLen, mtime)
        }
        try {
            // ── FINAL PASS 1 (§3.3 — the slow-live-sender read window) ─────────
            // Widens THIS connection's socket read timeout for the body
            // stream: the server-wide 5 s default is the right guard for
            // idle keep-alive connections, but a live-synthesized archive
            // sender (the ZIP-batch stream) can legitimately pause mid-body
            // for seconds — a slow ContentResolver open, a storage-latency
            // spike — and a false "read timed out" here deleted the partial,
            // failed the row and forced the sender's restart-from-byte-0
            // loop: exactly the reported native-to-native ZIP stall. The
            // window matches what the SENDER itself tolerates (its own 45 s
            // read timeout, 3× the engine's 15 s stall watchdog), so a
            // genuinely dead connection still dies — bounded — while a
            // healthy sender is never falsely aborted. Best-effort: a
            // session without its socket simply keeps the default.
            try { s.extendSocketReadTimeout(TRANSFER_BODY_READ_TIMEOUT_MS) } catch (e: Exception) { /* best-effort only */ }
            val ok = writeRawStream(s.inputStream, staging, declaredLen, effOffset, tid, "${id}::$relPath")
            if (!ok) {
                // ── Pass-17 (one card per transfer): a NEWER leg re-opened ──
                // this row after OUR connection died — its generation bumped,
                // and everything this tail would do (pause-park, cancel
                // cleanup, interrupted-keep, fresh-start purges) belongs to
                // the LIVE leg now. Just unregister and answer honestly.
                if (legResumeGen != TransferManager.resumeGenOf(tid)) {
                    unregisterNativeControlLink(tid)
                    return jsonErr("Superseded by a newer transfer leg")
                }
                // ── Pass-16 (pause-yield, receiver half) ─────────────────────
                // The SENDER aborted this POST on purpose because its user
                // paused the file (its stream yields the dispatch permit so a
                // DIFFERENT waiting item can start — see NativePushClient.
                // sendOneFile's pause path). Deliberate cancel still wins
                // (checked first below); a genuine PAUSE is neither a
                // failure nor an interruption.
                // ── Pass-20 (paused receive keeps progress AND position) ──
                // The row STAYS ACTIVE+paused now — the old
                // demoteToPendingForPause shoved it from the Active bucket
                // into the Waiting pile, which both hid its already-achieved
                // progress bar (PENDING rows render no progress) and moved
                // its card to a different list position (the reported
                // "receiver shows the paused state without the progress
                // already achieved" + "the item moves to a different
                // position after pause/resume" — the sender never had
                // either problem because its own parked SEND row always
                // stayed ACTIVE). Paused rows are exempt from the stall
                // watchdog and the stale sweeps by the same
                // pauseRequested flag, the receiving slot was already
                // yielded by schedulePausePermitYield, and the sender's
                // resumed POST adopts this exact row via
                // findPausedActiveUploadId — one card, one identity, byte
                // checkpoint preserved, position preserved.
                if (TransferManager.isPaused(tid) &&
                    !TransferManager.isCancelled(tid) &&
                    !TransferManager.wasCancelledForUploadDurable("${id}::$relPath")) {
                    // ── Pass-18 (resume-one-of-all-paused, receiver half): the ──
                    // control link STAYS registered. It used to be unregistered
                    // here, so the host's own later Resume tap on the parked row
                    // hit a dead nativeControlByTid lookup in
                    // propagateNativeControl and silently no-op'd — the parked
                    // sender was never told, its dispatch coroutine was long
                    // gone, and the row sat "Waiting" forever: exactly the
                    // reported "resume one item while all are paused and it
                    // stays stuck on Waiting". The link is what carries the
                    // resume (and a cancel) back to the sender; it is
                    // unregistered by the paths that genuinely end the file
                    // (completion, cancel, interruption) and re-registered by
                    // the sender's own resumed POST landing here again.
                    return jsonErr("Paused by sender")
                }
                // ── Pass-11 (ghost-transfer root fix): distinguish a ───────
                // DELIBERATE cancel from a genuine connection break BEFORE
                // choosing the response. When the row was cancelled (either
                // the receiver's own tap or a sender-propagated control), the
                // answer MUST be "Cancelled by host" — the exact string the
                // sender's streamFile classifier treats as a final decision
                // (REFUSED_CANCELLED), stopping its auto-resume loop for good.
                // The old generic "Transfer interrupted…" answer was classed
                // RETRYABLE by the sender, whose 1s-later re-POST then minted
                // the ghost row (the row here had already auto-dismissed at
                // 400ms, so wasCancelledForUpload found nothing). A deliberate
                // cancel also deletes the staging partial — there is nothing
                // to resume — and the durable tombstone (recorded by
                // requestCancel itself) keeps refusing any re-POST that raced
                // past the dismissal. The tombstone half of the check is what
                // still sees the truth on a slow leg, after the row itself
                // has already been swept.
                if (TransferManager.isCancelled(tid) ||
                    TransferManager.wasCancelledForUploadDurable("${id}::$relPath")) {
                    deleteStagingFor(dest)
                    // Pass-14 (resumable receives): a deliberately cancelled
                    // receive is NOT resumable — its registry entry goes with
                    // the partial so a restart never resurrects a card for a
                    // transfer the user explicitly stopped.
                    ResumableReceiveStore.remove(context, dest.absolutePath)
                    unregisterNativeControlLink(tid)
                    return jsonErr("Cancelled by host")
                }
                // ── Pass-14 (resumable receives) ─────────────────────────────
                // An interruption keeps its partial ONLY when the partial is
                // real: a staging file that exists and holds at least one
                // byte. In that case the row resolves FAILED+interrupted and
                // is KEPT (no auto-dismissal, registered in the durable
                // resumable store) — its "Waiting for reconnection" card
                // stays in the Active tab for as long as the partial exists,
                // across the sender's window and across this app's own
                // restart, until the same file is sent again (the card is
                // adopted by the new push and resumes from the checkpoint)
                // or the user discards it. With NO valid partial (a
                // connection that died before the first byte landed, or a
                // swept/emptied staging file) there is nothing to resume —
                // the WFR card must never appear for it, the leftover meta
                // goes, and the row resolves as a plain short-lived Failed.
                val partialBytes = if (staging.exists()) staging.length() else 0L
                // ── FINAL PASS 2 (§1 — seamless auto-continue on reconnection, ──
                // receiver half): an interrupted streamed archive no longer
                // resolves as a plain short-lived Failed. The sender's own
                // auto-resume loop is still live for its full window and WILL
                // re-POST a fresh attempt from byte 0 on its own schedule —
                // so the SAME logical card stays, latched into the kept
                // auto-continue state (FAILED+interrupted, progress zeroed,
                // prune-immune, bounded life): the chip reads "Auto-Continue
                // on Reconnection" (keyed on isZipStream in the UI), the
                // useless partial is still deleted, and the re-POST's claim
                // chain (findInterruptedUploadId → reopenInterrupted-
                // UploadByExactId) re-opens THIS row in place — never a
                // second incoming card beside the lingering one, and never a
                // byte-resume promise an unknown-length archive cannot keep
                // (GOAT-PASS 15 §3.5's honesty, restated for the new name).
                // A genuine unrecoverable failure (EOCD refusal, window
                // exhaustion, cancel) still resolves plain Failed/Cancelled
                // exactly as before — only the "attempt severed, auto-retry
                // scheduled" case reads as auto-continue.
                if (declaredLen < 0L) {
                    deleteStagingFor(dest)
                    TransferManager.failForZipAutoContinue(tid)
                    unregisterNativeControlLink(tid)
                    return jsonErr("Transfer interrupted — archive will restart from the beginning")
                }
                if (partialBytes > 0L) {
                    TransferManager.fail(tid, interrupted = true, keepForResume = true)
                    ResumableReceiveStore.upsert(context, ResumableReceiveStore.Entry(
                        destPath = dest.absolutePath,
                        fileName = resolvedPath.substringAfterLast('/'),
                        senderName = push.fromName,
                        senderIp = push.fromIp,
                        totalBytes = declaredLen,
                        savedAtMs = System.currentTimeMillis(),
                    ))
                } else {
                    deleteStagingFor(dest)
                    TransferManager.fail(tid)
                }
                unregisterNativeControlLink(tid)
                return jsonErr("Transfer interrupted — partial file kept for resume")
            }
            if (!staging.renameTo(dest)) {
                TransferManager.fail(tid)
                unregisterNativeControlLink(tid)
                return jsonErr("Could not finalize received file")
            }
            // ── GOAT-PASS 15 (§3.2 — archive integrity for unknown-length ──
            // streams): a size=-1 entry is a live-synthesized ZIP archive.
            // Reaching the stream's end proves the CHUNKS all arrived — it
            // does not by itself prove the archive is complete (a stream
            // that was cut mid-archive by the old undecoded-chunked-body
            // defect, or any future framing hiccup, must never finalize as
            // a "successful" transfer). The End Of Central Directory record
            // is written LAST by ZipOutputStream, so its verified presence
            // at the file's tail is the cheap structural proof of a closed
            // archive. A failed check is an honest failure: the (possibly
            // truncated) file is deleted, the row resolves Failed so the
            // established Resend flow can build a genuinely fresh attempt,
            // and the sender's classifier reads the 500 and retries rather
            // than recording a fake success.
            if (declaredLen < 0L && !com.lumidrop.x.transfer.ZipIntegrity.endsWithCentralDirectory(dest)) {
                try { dest.delete() } catch (ignored: Exception) {}
                try { stagingMetaFor(dest).delete() } catch (ignored: Exception) {}
                ResumableReceiveStore.remove(context, dest.absolutePath)
                TransferManager.fail(tid)
                unregisterNativeControlLink(tid)
                return jsonErr("Incomplete archive received — not finalized")
            }
            // The file is now real and complete — its identity sidecar has
            // no purpose left and must not outlive it (a stale sidecar is
            // exactly what a future re-send's identity check would trip on).
            try { stagingMetaFor(dest).delete() } catch (ignored: Exception) {}
            // Pass-14 (resumable receives): a completed resume consumed its
            // own partial — the registry entry must not outlive it either
            // (a restart would otherwise restore a WFR card for a file that
            // is actually fully delivered).
            ResumableReceiveStore.remove(context, dest.absolutePath)
        } finally {
            // Released the moment bytes stop flowing, same reasoning as
            // apiUpload — the rename/scan/complete work below doesn't hold
            // a peer waiting on the network, so it shouldn't hold the next
            // queued file's slot either. Always the id the registration is
            // CURRENTLY keyed under — heldSlotId for the ordinary claim
            // paths (where it equals tid), or the re-keyed/own tid from the
            // GOAT-PASS 6 §4 block above (a rekeyed release under the dead
            // wait key would no-op and leak the permit; see that block's
            // doc comment).
            if (slotReleaseId != null) TransferManager.releaseReceivingSlot(slotReleaseId)
        }
        // ── GOAT-PASS 15 (§2 — the prompt completion tail) ─────────────────────
        // Everything the transfer contract actually needs runs HERE, before
        // the response: the mtime stamp, the sender memory, and the row's
        // COMPLETED resolution — all local, in-memory or single-syscall
        // operations costing well under a millisecond. The response leaves
        // the instant they are done, which is exactly what un-gates the
        // sender's next file (the reported 3–4 s "stuck at 100%" was this
        // endpoint holding the response while the MediaProvider scan, the
        // search-index invalidation and the batch notification ran in front
        // of it — per file, for every file of a batch).
        if (mtime > 0) try { dest.setLastModified(mtime) } catch (e: Exception) { /* best-effort only */ }
        // ── Pass-15 (no more "Unknown sender") ─────────────────────────────
        // The file is real and complete — remember WHO delivered it, so the
        // disk-reconciled Received/History rows of this and every future
        // session render the sender's real name instead of a synthesized
        // "Unknown sender" placeholder.
        com.lumidrop.x.transfer.ReceivedFileMeta.rememberSender(context, dest.absolutePath, push.fromName)
        // ── FINAL PASS 3 (§1 — the real size of a completed ZIP stream): the ──
        // streamed archive row was minted with totalBytes = -1 (an unknown-
        // length archive cannot declare a size) and that -1 is exactly what
        // the Received tab's live row and the History entry recorded at
        // completion would otherwise render as "?". The finished file is
        // now REAL and renamed — its own length is the actual final size,
        // from the most reliable source there is (the filesystem itself).
        // Stamped BEFORE complete() so finalizeCompletedBody and the
        // HistoryStore hook both see it. Known-size transfers (declaredLen
        // >= 0) are untouched — their declared total is already exact.
        if (declaredLen < 0L) TransferManager.stampFinalSize(tid, dest.length())
        TransferManager.complete(tid)
        unregisterNativeControlLink(tid)
        // ── GOAT-PASS 15 (§1/§2): the arrival side effects — the media scan,
        // the (coalesced) storage-change nudge and the batch notification —
        // run on the arrival executor, AFTER the response is on the wire.
        // Correctness is unchanged: the file is durable and registered, the
        // row is Completed, and none of these affect any subsequent request.
        deferArrivalSideEffects(dest.absolutePath, dest.absolutePath)
        if (push.filesDone.incrementAndGet() >= push.totalFiles) {
            arrivalExec.execute {
                try {
                    com.lumidrop.x.service.ServerForegroundService.notifyNativePushComplete(
                        context, push.fromName, push.totalFiles)
                } catch (e: Exception) { /* visibility-only */ }
            }
        }
        return json(JSONObject().put("success", true))
    }

    // Companion to apiNativePushFile above: applies a pause/resume/cancel
    // that originated on the PEER's own Transfers tab to this device's
    // local copy of the same transfer — see the state-sync data structures
    // and propagateNativeControl() near PendingNativePush. Always reports
    // success regardless of whether a matching local transfer was actually
    // found: this is a best-effort signal, and the caller's own local
    // action (which this call accompanies) already took effect either way.
    private fun apiNativePushControl(s: IHTTPSession): Response {
        val id = s.parameters["id"]?.firstOrNull() ?: return jsonErr("No id")
        val action = s.parameters["action"]?.firstOrNull() ?: return jsonErr("No action")
        // GOAT (receiver-side cancel fix -- reported after testing:
        // cancelling a folder made it disappear on the sender but left it
        // stuck, partially received, on the receiver): a normal per-file
        // control call always carries a path and only ever reaches ONE
        // already-registered nativeControlByKey entry -- see that map's
        // own doc comment -- which for a folder that's mostly still
        // PENDING on the sender's side covers only the handful of files
        // that happened to already be active. scope=push is the new,
        // separate case: no path, cancel every remaining file under this
        // whole push id at once, reaching the PENDING ones too.
        if (s.parameters["scope"]?.firstOrNull() == "push") {
            if (action == "cancel") {
                // Pass-8 (§13): this scope=push cancel arrives from the PEER.
                // If WE hold the push record (we are the receiver), the local
                // teardown below applies. If we DON'T (we are the SENDER — the
                // id is our own session id, mirrored in the receiver's push
                // record), forward it to NativePushClient so the sending
                // session's dispatch loop stops and its still-live rows
                // resolve Cancelled — previously the sender had no record for
                // this id, silently no-op'd, and kept streaming the rest of
                // the batch file by file into "Not accepted" refusals.
                if (pendingNativePushes[id] == null) {
                    com.lumidrop.x.p2p.NativePushClient.cancelSessionById(id)
                } else {
                    // GOAT-PASS 11 (§7): notifyPeer=false — the SENDER sent
                    // this control; telling it to cancel a session it just
                    // cancelled itself would be a pointless round trip (and
                    // a wrong one if the same id ever collided).
                    cancelWholePush(id, notifyPeer = false)
                }
            }
            return json(JSONObject().put("success", true))
        }
        val path = s.parameters["path"]?.firstOrNull() ?: return jsonErr("No path")
        // ── Pass-13 (individual transfer cancellation, receiver half) ────────
        // scope=file is the SENDER withdrawing exactly ONE file of a push
        // (NativePushClient.propagateSingleFileCancel — the X button on one
        // row of a multi-file native send). It must resolve ONLY that file
        // on this side, never the whole push (which is scope=push above):
        //   • the row already streaming (nativeControlByKey) is cancelled,
        //   • the still-PENDING pre-registered row (uploadRequestId
        //     "pushId::relPath" from registerPendingTransfersFor) is
        //     cancelled too — the old per-file handler could never reach it,
        //     which is exactly why the whole-session teardown existed,
        //   • an undecided TOP-LEVEL item matching this path is declined so
        //     it leaves the incoming sheet immediately (nested paths never
        //     touch their folder's decision — the folder's other files keep
        //     coming),
        //   • the file's staging partial is deleted — a deliberate cancel
        //     must not leave resume data behind (same rule as cancelWholePush).
        // Every sibling item, row, and the push record itself are untouched.
        if (s.parameters["scope"]?.firstOrNull() == "file" && action == "cancel") {
            nativeControlByKey["$id:$path"]?.let { tid ->
                TransferManager.requestCancel(tid)
            }
            val push = pendingNativePushes[id]
            if (push != null) {
                TransferManager.findTransferByUploadRequestId("$id::$path")?.let { t ->
                    // ── Pass-21 (individual cancel never lands in WFR) ────────
                    // The old PENDING||ACTIVE guard here was the receiver-side
                    // hole behind the reported inconsistency: an individual
                    // cancel racing the receiver's own stall watchdog lost on
                    // purpose — by the time the sender's scope=file POST
                    // arrived, the row had already been resolved
                    // FAILED+interrupted (it read "Waiting for reconnection"),
                    // this guard refused to even hand it to requestCancel, and
                    // the row SAT in WFR while the sender provably showed
                    // Cancelled. requestCancel is the single authority on what
                    // is cancellable (it flips PENDING/ACTIVE/FAILED and keeps
                    // COMPLETED/CANCELLED/DECLINED immutable — pass-20 gave it
                    // the FAILED flip), so pass EVERY state through and let it
                    // decide. Individual cancellation is now exactly as
                    // reliable as Cancel All (whose sender-side teardown loops
                    // never had this guard).
                    TransferManager.requestCancel(t.id)
                }
                var decidedAny = false
                if (push.itemDecisions[path] == "pending") {
                    push.itemDecisions[path] = "declined"
                    decidedAny = true
                }
                try {
                    val dest = File(push.destRoot, push.resolvePath(path))
                    deleteStagingFor(dest)
                    // Pass-14 (resumable receives): the deliberate per-file
                    // cancel deleted the partial — its registry entry must go
                    // with it so a restart never restores a card for a file
                    // the sender withdrew.
                    ResumableReceiveStore.remove(context, dest.absolutePath)
                } catch (ex: Exception) {
                }
                if (decidedAny) refreshNativePushNotif()
            }
            return json(JSONObject().put("success", true))
        }
        // GOAT (Phase 1, item 2 root cause): see apiUploadRequest's doc
        // comment -- s.parameters is already decoded by NanoHTTPD. Using
        // the same undecoded-again value here also keeps this key
        // consistent with registerNativeControlLink's "$pushId:$relPath",
        // which is built from apiNativePushFile's now-correctly-decoded
        // relPath.
        // ── Pass-19 (Pause-All state sync, both directions) ──────────────────
        // The control key only ever covered files that already STARTED
        // STREAMING (registerNativeControlLink runs at dispatch time on the
        // sender, and at byte-request time on the receiver), so a pause/
        // resume aimed at a still-WAITING row silently no-op'd — the exact
        // "Pause all shows every item Paused on one device but only the
        // in-progress item Paused on the other" report. Two fallbacks now
        // resolve the row from the composite request id BEFORE giving up:
        //   • the receiver's own pre-registered PENDING row ("pushId::relPath"
        //     — created by registerPendingTransfersFor the moment the manifest
        //     landed), for controls arriving from the SENDER;
        //   • the sender's own session (NativePushClient.tidForControl — every
        //     pre-registered send row, waiting ones included), for controls
        //     arriving from the RECEIVER.
        // Whichever device this handler runs on, exactly one of the two
        // resolves, and the action lands on the local mirror of the transfer.
        val controlTid = nativeControlByKey["$id:$path"]
            ?: TransferManager.findTransferByUploadRequestId("$id::$path")?.id
            ?: com.lumidrop.x.p2p.NativePushClient.tidForControl(id, path)
        controlTid?.let { tid ->
            when (action) {
                "pause"  -> TransferManager.requestPause(tid)
                // ── Pass-18 (resume-one-of-all-paused, sender half): a paused ──
                // native send YIELDED its POST on purpose (its user — or the
                // receiver's, via the "pause" control above — paused the
                // file), so its dispatch coroutine has already RETURNED and
                // parked the file in NativePushClient.pausedSends. Merely
                // clearing the pause flag here did nothing at all: nothing
                // was streaming, nothing was scheduled, and the row sat
                // "Waiting" forever — the other half of the reported
                // resume-one-while-all-paused deadlock. resumeFile() relaunches
                // the parked send from the receiver's preserved checkpoint
                // (it re-probes the offset first, so the continuation is
                // exact); it is a no-op for anything that isn't parked, so a
                // merely-stalled send (its flag clear, its loop alive) is
                // untouched.
                "resume" -> {
                    TransferManager.requestResume(tid)
                    com.lumidrop.x.p2p.NativePushClient.resumeFile(tid)
                }
                // ── Pass-19 (remote cancel of a PARKED send) ──────────────────
                // A pause-parked send has NO live coroutine to notice a cancel —
                // its parkedSends entry must be explicitly purged (exactly the
                // discipline the LOCAL cancel path already applies via
                // cancelPausedSendIfParked), or the session stays "held open"
                // by a ghost entry forever. No-op for anything not parked.
                "cancel" -> {
                    TransferManager.requestCancel(tid)
                    com.lumidrop.x.p2p.NativePushClient.cancelPausedSendIfParked(tid)
                }
                // ── Pass-22 (compile fix): a `when` over a free-form protocol
                // string must carry an else — any future/unknown action verb is
                // a no-op, exactly how the endpoint's own response always
                // answered "success" regardless.
                else -> Unit
            }
        }
        return json(JSONObject().put("success", true))
    }

    /** Cancels every remaining file under [id] at once (see
     *  apiNativePushControl's scope=push case above) and cleans up any
     *  partial staging file left by one that was actively streaming —
     *  a DELIBERATE whole-folder cancel should never leave partial data
     *  behind the way an accidental dropped connection is allowed to
     *  (see stagingFileFor's own doc comment on why THAT case keeps its
     *  staging file, for resume). Fully-completed files are untouched:
     *  a completed file's staging file no longer exists by the time it's
     *  done (renamed to its real destination — see apiNativePushFile's
     *  success path), so the exists() check below already leaves them
     *  alone without needing to check each file's status first. */
    private fun cancelWholePush(id: String, notifyPeer: Boolean = true) {
        val push = pendingNativePushes[id] ?: return
        // ── GOAT-PASS 11 (§7 — ATOMIC, TERMINAL, ONE-SHOT whole-folder cancel) ──
        // The latch is established BEFORE any child cleanup so every gate
        // (file POST, probe, repeat manifest) refuses this push from the
        // very first moment of the cancel, no matter what the per-file
        // tombstones' cap or the record's later fate does. Idempotent by
        // construction: a repeated cancel finds the latch already set and
        // only re-runs the (individually idempotent) member resolution.
        val firstCancel = cancelledPushIds.putIfAbsent(id, System.currentTimeMillis()) == null
        if (firstCancel) {
        }
        // ── GOAT-PASS 13 (§6): the cross-session cancelled-folder tombstone ──
        // write that used to live here is removed with the rest of the
        // tombstone system — a cancel ends THIS session (the latch above
        // refuses its in-flight files), and any genuinely new send of the
        // same folder follows the ordinary acceptance flow again, exactly
        // like an ordinary file.
        // Resolve EVERY member row in ONE atomic bulk pass — PENDING, ACTIVE
        // and the FAILED/kept-for-resume "Auto-Resume on Reconnection" ones
        // alike (requestCancelBulk owns the state decisions; COMPLETED files
        // stay). This is the fast, engine-only half that makes the folder
        // card disappear from the Active tab immediately, and — one monitor
        // acquisition and ONE log line instead of 694 of each — it can never
        // ANR the thread it runs on.
        val memberIds = ArrayList<String>(push.entries.size)
        for (e in push.entries) {
            if (e.isDir || e.isText) continue
            TransferManager.findTransferByUploadRequestId("$id::${e.path}")?.let { memberIds.add(it.id) }
        }
        if (memberIds.isNotEmpty()) TransferManager.requestCancelBulk(memberIds)
        // The disk cleanup half — staging partial deletes plus ONE bulk
        // resumable-registry rewrite — runs on the background cleanup
        // executor. Fully-completed files are untouched by construction (a
        // completed file's staging no longer exists; the registry only ever
        // held interrupted partials). Per-path guards keep one unreadable
        // entry from aborting the rest, exactly as before.
        val destPaths = ArrayList<String>(push.entries.size)
        for (e in push.entries) {
            if (e.isDir || e.isText) continue
            try {
                destPaths.add(File(push.destRoot, push.resolvePath(e.path)).absolutePath)
            } catch (ex: Exception) {
            }
        }
        if (destPaths.isNotEmpty()) {
            pushCleanupExecutor.submit {
                for (p in destPaths) {
                    try { deleteStagingFor(File(p)) } catch (ex: Exception) {
                    }
                }
                try { ResumableReceiveStore.removeAll(context, destPaths) } catch (ex: Exception) {
                }
            }
        }
        // Pass-6 (R-H — the incoming offer dies with the push): resolving
        // each undecided item as declined flips the push out of
        // pendingNativePushList() on the very next ReceivingModal tick
        // (500ms worst case) and clears the pending notification — a
        // cancelled send now disappears from BOTH ends.
        var decidedAny = false
        for (top in push.topLevelNames) {
            if (push.itemDecisions[top] == "pending") { push.itemDecisions[top] = "declined"; decidedAny = true }
        }
        // Pass-8 (§13): an already-ACCEPTED item flips to declined too when
        // the WHOLE push is being torn down — the receiver has decided none
        // of this push's remaining files are wanted, and apiNativePushFile's
        // decision gate is what turns that decision into a hard server-side
        // "Not accepted" refusal for any file still in flight from a sender
        // whose cancel propagation hasn't landed yet. Completed files are
        // untouched (they're already on disk and stay Completed); only
        // still-streamable work is refused.
        for (top in push.topLevelNames) {
            if (push.itemDecisions[top] == "accepted") { push.itemDecisions[top] = "declined"; decidedAny = true }
        }
        // ── GOAT-PASS 11 (§7 — bidirectional cancellation is ONE mechanism) ──
        // The sender must learn about the cancel from HERE, not from a
        // separate UI-side call that may never fire: one scope=push control
        // POST stops its dispatch loop and resolves its sibling rows. This
        // was the single biggest hole in the receiver-side cancel — the
        // sender kept scheduling and re-sending children (each re-POST
        // mints/aborts rows on the receiver) until IT was cancelled
        // manually. notifyPeer=false only for the path where the sender
        // itself initiated (apiNativePushControl) — it already knows.
        if (notifyPeer) notifySenderWholePushCancelled(push)
        if (decidedAny) refreshNativePushNotif()
    }

    /** ── GOAT-PASS 11 (§7): tells the SENDER of [push] to tear the whole
     *  session down — one idempotent scope=push control POST. Best-effort:
     *  the push-level latch plus the per-file refusals the sender meets on
     *  its next POST/probe guarantee the same terminal outcome even when
     *  this POST is lost to a network flap. */
    private fun notifySenderWholePushCancelled(push: PendingNativePush) {
        val baseUrl = "http://${push.fromIp}:${push.fromPort}"
        controlExecutor.submit {
            var conn: HttpURLConnection? = null
            try {
                val url = "$baseUrl/api/native/push/control?id=${URLEncoder.encode(push.id, "UTF-8")}" +
                    "&action=cancel&scope=push"
                val c = com.lumidrop.x.utils.PeerHttp.open(context, url) as HttpURLConnection
                c.requestMethod = "POST"; c.connectTimeout = 4000; c.readTimeout = 4000
                conn = c
                c.responseCode
            } catch (e: Exception) { /* peer unreachable — the latch + refusals hold the terminal state */ }
            finally { conn?.disconnect() }
        }
    }

    /** GOAT-PASS 9 (§8 — receiver-side folder cancel, the local half): the
     *  RECEIVING device's folder-group Cancel previously resolved only its
     *  PENDING/ACTIVE member rows (the UI's liveMembers loop) and asked the
     *  sender to stop — leaving this device's own FAILED+interrupted
     *  ("Waiting for reconnection") members with their staging partials and
     *  ResumableReceiveStore entries intact, and the push record's undecided
     *  items open. A re-send of the same folder then re-attached one of
     *  those 99%-checkpoints via adoption, and a cold start restored the
     *  partials as fresh WFR cards — the cancelled folder "reappearing".
     *  This entry point runs the SAME full local teardown the sender-side
     *  scope=push handler performs (cancelWholePush: every member row —
     *  FAILED/WFR ones included — resolved CANCELLED, staging partials and
     *  resume registry entries deleted, every item decision closed), so a
     *  folder cancelled from the receiver is gone from BOTH sides for good.
     *  Returns the resolved push id, or null when the row resolves to no
     *  known push. */
    fun cancelIncomingPushFromUi(tid: String): String? {
        val link = nativeControlByTid[tid]
        if (link != null) {
            if (pendingNativePushes[link.pushId] != null) {
                cancelWholePush(link.pushId)
            } else {
                // ── GOAT-PASS 10 (§4 — cancel must not depend on the push ──
                // record's survival): the control link outlived the push
                // record (TTL sweep / restart), so cancelWholePush would be
                // a silent no-op. The folder's group of member rows is still
                // right here — tear THAT down so the cancel always resolves
                // every member (rows, staging partials, resume registry).
                TransferManager.get(tid)?.groupId?.let { cancelFolderGroupLocal(it) }
            }
            return link.pushId
        }
        val t = TransferManager.get(tid) ?: return null
        val reqId = t.uploadRequestId ?: return null
        val pushId = reqId.substringBefore("::")
        if (pushId.isEmpty() || pushId == reqId) return null
        if (pendingNativePushes[pushId] != null) {
            cancelWholePush(pushId)
        } else {
            // GOAT-PASS 10 (§4): same record-gone fallback — the receiver's
            // folder cancel must work at EVERY transfer stage, including the
            // "waiting" one long after the push record lapsed. Cancelling the
            // group's members directly also writes each row's durable
            // cancelled-upload tombstone, so a still-probing sender's re-POSTs
            // are answered "Cancelled by host" instead of resurrecting the
            // folder one ghost child at a time.
            t.groupId?.let { cancelFolderGroupLocal(it) }
        }
        return pushId
    }

    /** ── GOAT-PASS 10 (§4): the group-scoped local teardown — the half ──────
     *  [cancelWholePush] performs, resolved through the folder's GROUP of
     *  member rows instead of the push record, for every path where that
     *  record is already gone (TTL sweep, app restart). Every member row —
     *  PENDING, ACTIVE and the FAILED/kept-for-resume "Auto-Resume on
     *  Reconnection" ones alike — is resolved through requestCancel
     *  (idempotent; COMPLETED files are immutable and stay), each member's
     *  staging partial is deleted and its resume-registry entry dropped, so
     *  nothing of the cancelled folder survives to re-attach on a re-send or
     *  a cold start. The parent-child structure is preserved to the very end
     *  (rows resolve in place, inside their group — never detached), and the
     *  group record itself is pruned by the engine's own empty-group sweep
     *  once its last member dismisses. */
    private fun cancelFolderGroupLocal(groupId: String) {
        val members = TransferManager.membersOf(groupId)
        if (members.isEmpty()) return
        // ── GOAT-PASS 11 (§7): the group-scoped fallback gets the SAME ──
        // atomic treatment as cancelWholePush. The push record is gone, but
        // every member row carries its composite "pushId::relPath" request
        // id — latching each distinct push id refuses any still-probing
        // sender's re-POSTs for the whole folder even though no record
        // exists to consult, and the bulk resolve keeps the UI thread fast
        // for the same reason. Disk cleanup is likewise deferred to the
        // background executor with ONE bulk registry rewrite.
        val pushIds = java.util.LinkedHashSet<String>()
        for (t in members) {
            val rid = t.uploadRequestId ?: continue
            val pid = rid.substringBefore("::")
            if (pid.isNotEmpty() && pid != rid) pushIds.add(pid)
        }
        for (pid in pushIds) cancelledPushIds.putIfAbsent(pid, System.currentTimeMillis())
        TransferManager.requestCancelBulk(members.map { it.id })
        val destPaths = ArrayList<String>(members.size)
        for (t in members) {
            val p = t.filePath ?: continue
            destPaths.add(p)
        }
        if (destPaths.isNotEmpty()) {
            pushCleanupExecutor.submit {
                for (p in destPaths) {
                    try { deleteStagingFor(File(p)) } catch (ex: Exception) {
                    }
                }
                try { ResumableReceiveStore.removeAll(context, destPaths) } catch (ex: Exception) {
                }
            }
        }
    }

    /** Pass-8 (§13 — receiver-side Cancel All): cancels EVERY incoming
     *  native push that still has live (PENDING/ACTIVE) work on this device,
     *  on BOTH sides — locally (cancelWholePush: member rows resolved
     *  CANCELLED, staging partials deleted, item decisions closed) AND on
     *  each sender (one scope=push control POST per distinct push session,
     *  which stops its dispatch loop and resolves its sibling rows). This is
     *  the missing half of the receiver's Cancel All: without it, cancelling
     *  locally only tore down the CURRENTLY-STREAMING file while the sender
     *  happily streamed the rest of the batch — each remaining file
     *  re-registering as a fresh ACTIVE row on the receiver (see the
     *  wasCancelledForUpload tombstone that now refuses those). */
    fun cancelAllIncomingNativePushes() {
        val pushIds = TransferManager.liveUploadPushIds()
        for (pushId in pushIds) {
            // GOAT-PASS 11 (§7): notifyPeer=false — this function fires its
            // own scope=push control POST to each sender directly below.
            cancelWholePush(pushId, notifyPeer = false)
            val push = pendingNativePushes[pushId] ?: continue
            val baseUrl = "http://${push.fromIp}:${push.fromPort}"
            controlExecutor.submit {
                var conn: HttpURLConnection? = null
                try {
                    val url = "$baseUrl/api/native/push/control?id=${URLEncoder.encode(pushId, "UTF-8")}" +
                        "&action=cancel&scope=push"
                    val c = com.lumidrop.x.utils.PeerHttp.open(context, url) as HttpURLConnection
                    c.requestMethod = "POST"; c.connectTimeout = 4000; c.readTimeout = 4000
                    conn = c
                    c.responseCode
                } catch (e: Exception) { /* peer unreachable — the local cancel above already holds */ }
                finally { conn?.disconnect() }
            }
        }
    }

    // Shared by apiNativePushFile only (apiUpload's own proven raw-stream
    // path above is left untouched rather than retrofitted onto this, to
    // avoid any risk to its recently-stabilized large-file behaviour) — same
    // chunked read/write, same Pause/Cancel plumbing via
    // awaitUnpaused()/isCancelled(). `offset` > 0 means append/resume mode:
    // `dest` already holds `offset` good bytes from an earlier attempt, so
    // the file is opened for random access and seeked rather than truncated.
    // GOAT v2 (item 11): despite the parameter name, the caller always
    // passes the STAGING file here now (stagingFileFor), never the real
    // destination — this function has no idea which one it's writing to
    // and doesn't need to; it just writes bytes to whatever File it's
    // given, and apiNativePushFile is what decides what that file means.
    // GOAT-PASS 2 (§throughput): the loop is now pipelined through
    // PipelineIO.PipelinedFileWriter — this thread only drains the socket
    // (the TCP receive window never waits on a flash-write stall, which is
    // what produced the sudden 5–6 MB/s dips), a dedicated writer thread
    // owns the staging file, and the fixed 8×256 KB bounded queue absorbs
    // storage-latency bursts. The completion trim (setLength to exactly
    // the delivered count) rides finish()'s trimTo; the durable count the
    // caller's checkpoint logic sees is finish()'s return, never the
    // faster-moving reader count.
    private fun writeRawStream(inp: InputStream, dest: File, declaredLen: Long, offset: Long, tid: String,
                                 requestId: String? = null): Boolean {
        var completed = false
        // ── Pass-17 (one card per transfer): this leg's generation — if a NEWER
        // leg re-opens the row while this connection is blocked in a read, the
        // generation moves and this leg stops writing the moment it wakes up,
        // instead of interleaving its stale bytes over the new leg's staging
        // writes and clobbering the transfer with a late failure.
        val legResumeGen = TransferManager.resumeGenOf(tid)
        val pipe = PipelineIO.PipelinedFileWriter(dest, offset,
            onWriterThreadStart = { boostPipelineThread() })
        try {
            var remaining = if (declaredLen < 0L) -1L else declaredLen - offset
            while (remaining != 0L) {
                if (legResumeGen != TransferManager.resumeGenOf(tid)) {
                    return false
                }
                // ── GOAT-PASS 3 (§6): a slot re-acquire abort is an
                // interruption — break into the drain + checkpoint tail
                // below (the generic catch would skip the drain and return
                // false with only the durable prefix).
                try { TransferManager.awaitUnpaused(tid) }
                catch (e: TransferManager.SlotReacquireAbortedException) { break }
                // Pass-11 (ghost-transfer root fix): a deliberate cancel is
                // ALSO read from the durable tombstone — the row itself
                // auto-dismisses 400ms after requestCancel, and on a slow
                // leg that dismissal could previously outrun the next
                // chunk check, letting a cancelled file keep streaming to
                // completion. isCancelled(tid) stays the fast path; the
                // tombstone only matters once the row is gone.
                if (TransferManager.isCancelled(tid) ||
                    TransferManager.wasCancelledForUploadDurable(requestId)) break
                val buf = pipe.borrow()
                val toRead = if (remaining < 0L) buf.size else minOf(buf.size.toLong(), remaining).toInt()
                val n = inp.read(buf, 0, toRead)
                if (n <= 0) { pipe.abandon(); break }
                pipe.commit(n)
                TransferManager.updateProgress(tid, pipe.readerBytes)
                if (remaining > 0L) remaining -= n
            }
            completed = !TransferManager.isCancelled(tid) &&
                !TransferManager.wasCancelledForUploadDurable(requestId) &&
                (declaredLen < 0L || pipe.readerBytes >= declaredLen)
            // Phase 5 (v3 §6 item 2 — “written AND verified”): truncate
            // to exactly the byte count that was actually written. A
            // pre-existing staging file can only be LONGER than this
            // transfer's final size when it carried stale tail bytes
            // from an earlier, differently-sized version that survived
            // the offset check above (validated-but-shorter prefix, or
            // an unknown-length legacy stream) — without this, the
            // finished file would silently carry those stale bytes past
            // its declared length. Only a COMPLETED transfer is trimmed
            // (finish's trimTo): a cancelled/broken one keeps its partial
            // bytes untouched as the resume checkpoint.
            if (completed) {
                val durable = pipe.finish(trimTo = pipe.readerBytes)
                completed = durable == pipe.readerBytes
            } else {
                // GOAT-PASS 2 (§sync — durable checkpoints): a GENUINE
                // interruption (socket death — the sender is already gone)
                // drains the queue so the staging checkpoint — the length
                // apiNativePushFileStatus reports back for byte-level
                // resume — includes every buffer this leg had already
                // received; the finally-Abort would otherwise drop up to
                // 2 MB of received data and force a pointless re-send. A
                // DELIBERATE stop (host cancel, durable cancel tombstone,
                // superseded leg) never drains — stop-fast contract, and a
                // newer leg may already own this staging file. Drain
                // failure leaves the honest durable prefix (the catch is
                // the whole point of a checkpoint: writer-verified bytes).
                val deliberateStop = TransferManager.isCancelled(tid) ||
                    TransferManager.wasCancelledForUploadDurable(requestId) ||
                    legResumeGen != TransferManager.resumeGenOf(tid)
                if (!deliberateStop) {
                    try { pipe.finish() } catch (e: Exception) { /* durable prefix stands */ }
                }
            }
        } catch (e: Exception) { return false }
        finally {
            // Every non-completed exit stops the writer thread promptly:
            // cancel drops the queue, supersede drops it (a newer leg may
            // already be writing this same staging file), and a disk
            // failure has already latched its honest verdict.
            pipe.abort()
        }
        // A cancelled or broken transfer keeps its partial bytes on disk in
        // the STAGING file (unlike apiUpload's from-scratch path, which
        // deletes them outright — that path has no resume story to
        // preserve). GOAT v2 (item 11): because this is always the staging
        // file now, never the real destination, those partial bytes are
        // never visible under the file's real name either way — they're
        // purely the resume checkpoint apiNativePushFileStatus reports back
        // to NativePushClient on the next attempt, not something anything
        // else on the device would ever mistake for a finished file.
        return completed
    }

    // ── Media streaming ───────────────────────────────────────────────────────

    // ── External-player bridge: token system (Next Release Pass §12.5/§12.6) ──
    // A Windows external player (VLC, PotPlayer, MPC-HC, MPC-BE, mpv) cannot
    // use the browser's session cookie, so every launch mints a media-access
    // token scoped to exactly ONE file path for the requesting client's IP.
    // The token:
    //   • grants nothing beyond what the requesting session already had —
    //     minting requires a Full-Access session (Transfer Mode clients are
    //     refused exactly like every other browse surface, and a mid-stream
    //     mode flip revokes further range requests through that same gate);
    //   • stays valid for the WHOLE playback (Reliability pass §1: a fixed
    //     15-minute TTL used to cut off long videos mid-stream — a 20-hour
    //     film died at minute 15). Expiry is now a SLIDING idle window
    //     renewed by every authenticated fetch and every bridge verify: an
    //     actively playing stream keeps renewing itself indefinitely, a
    //     paused player survives BRIDGE_TOKEN_IDLE_MS, and an absolute cap
    //     (BRIDGE_TOKEN_ABSOLUTE_MS) still bounds the token's total lifetime
    //     well beyond any real playback so abandoned tokens can't squat
    //     forever. kickDevice() still revokes wholesale;
    //   • is multi-fetch for its lifetime, because a real player issues many
    //     parallel HTTP Range requests for one stream (seeking!) — a
    //     single-use token would break scrubbing exactly when it matters;
    //   • never appears in any log line.
    // "launched" becomes true when either the Windows bridge calls verify
    // (bridge players) or the player itself starts fetching the stream
    // (direct-protocol players) — the web client polls verify for exactly
    // this bit so its success/failure toast is honest either way.
    private class BridgeToken(val path: String, val player: String, val ip: String,
                              val mintedAt: Long, @Volatile var expiresAt: Long) {
        @Volatile var bridgeConfirmed = false
        @Volatile var streamFetched = false
    }
    private val bridgeTokens = ConcurrentHashMap<String, BridgeToken>()
    private val bridgeTokenRng = java.security.SecureRandom()
    private companion object {
        /** FINAL PASS 1 (§3.3): the per-connection read window the body-
         *  streaming endpoints raise before reading an inbound transfer
         *  body (see HTTPSession.extendSocketReadTimeout). 45 s = the
         *  sender's own HttpURLConnection read timeout (NativePushClient.
         *  streamFile) and 3× the engine's 15 s stall watchdog — a live
         *  sender is never falsely aborted, a dead connection is still
         *  bounded. The server-wide NanoHTTPD default (5 s) keeps guarding
         *  every other request and idle keep-alive connection. */
        const val TRANSFER_BODY_READ_TIMEOUT_MS = 45_000

        // v1.0 (§no-watch-limit): PURE sliding renewal — there is deliberately
        // NO absolute cap anymore. The token lives exactly as long as the
        // host's server does: every authenticated fetch (an actively playing
        // stream, a seek, a paused player's periodic bridge verify) slides
        // the window forward, and only BRIDGE_TOKEN_IDLE_MS of true silence —
        // player closed, page gone, host unreachable — expires it. The old
        // 48h absolute cap was reported as an artificial "watch limit" on
        // multi-day playback sessions; it bought nothing security-wise that
        // kickDevice + the idle window don't already provide (host restart
        // wipes the in-memory map entirely, which remains the real bound).
        const val BRIDGE_TOKEN_IDLE_MS = 8 * 3600_000L
        const val BRIDGE_TOKENS_PER_IP = 8

        /** Sliding-renewal expiry computation: last-use + idle. Renewal
         *  happens ONLY on authenticated use (apiMedia fetch with bt /
         *  bridge verify), never on bare polls of anything else — an
         *  attacker probing the verify endpoint without a valid token
         *  gains nothing, and a valid token's owner renewing is exactly
         *  the behavior playback requires. */
        fun bridgeExpiryAfter(mintedAt: Long, lastUseAt: Long): Long =
            lastUseAt + BRIDGE_TOKEN_IDLE_MS
        // The players the launch endpoint will mint tokens for. Matches the
        // web client's PW.windows registry ids exactly — an id not in this
        // set is refused before anything else happens.
        val BRIDGE_PLAYERS = setOf("vlcwin", "potplayer", "mpc", "mpcbe", "mpv")
    }

    /** Sweeps expired tokens and enforces the per-IP cap (oldest first). */
    private fun sweepBridgeTokens(now: Long = System.currentTimeMillis()) {
        // GOAT-PASS 9 (Android 5–7): same removeIf→removeAll swap as the kick
        // path — iterator-based, safe on API 21+.
        bridgeTokens.entries.removeAll { it.value.expiresAt < now }
        val byIp = bridgeTokens.entries.groupBy { it.value.ip }
        for ((_, entries) in byIp) {
            if (entries.size > BRIDGE_TOKENS_PER_IP) {
                entries.sortedBy { it.value.expiresAt }
                    .take(entries.size - BRIDGE_TOKENS_PER_IP)
                    .forEach { bridgeTokens.remove(it.key, it.value) }
            }
        }
    }

    /** Password-gate helper: is THIS request carrying a live bridge token for
     *  its own IP? (Path matching happens later, in apiMedia, against the
     *  canonical file — this is only the gate-level liveness + IP check.) */
    private fun bridgeTokenValidFor(s: IHTTPSession): Boolean {
        val t = s.parameters["bt"]?.firstOrNull() ?: return false
        val tok = bridgeTokens[t] ?: return false
        if (tok.expiresAt < System.currentTimeMillis()) return false
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        return ip == "unknown" || ip == tok.ip || ip == "127.0.0.1"
    }

    /** Absolute origin (scheme://host:port) as this request's client sees it,
     *  from the Host header the client itself sent (that is the address the
     *  player — on the same machine — can reach too). */
    private fun requestOrigin(s: IHTTPSession): String {
        val hdr = s.headers["host"]
        if (!hdr.isNullOrBlank()) return "http://$hdr"
        val peer = try { s.remoteIpAddress ?: "127.0.0.1" } catch (e: Exception) { "127.0.0.1" }
        return "http://$peer:$port"
    }

    private fun apiBridgeLaunch(s: IHTTPSession): Response {
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        // Same authorization bar as every other browse surface: a Transfer
        // Mode client may transfer, not browse or stream — refuse it here
        // exactly as serve()'s mode gate refuses /api/media.
        if (getDeviceMode(ip) != "full") {
            return newFixedLengthResponse(Status.FORBIDDEN, "application/json",
                JSONObject().put("error", "Not available in Transfer Mode").toString())
        }
        if (ip in kickedIPs) return jsonErr("Kicked")
        parseFormBodyBounded(s, "path", "player")
        val path = (s.parameters["path"] ?: s.parameters["p"])?.firstOrNull()
            ?: return jsonErr("No path")
        val player = (s.parameters["player"] ?: s.parameters["pl"])?.firstOrNull()
            ?: return jsonErr("No player")
        if (player !in BRIDGE_PLAYERS) return jsonErr("Unknown player")
        val file = File(path)
        if (!file.exists() || !file.isFile) return jsonErr("Not found")
        // Path-safety: same boundary the Files tree itself enforces — a
        // canonical path outside /storage is somebody else's files.
        val canon = try { file.canonicalPath } catch (e: Exception) { file.absolutePath }
        if (!canon.startsWith("/storage/")) return jsonErr("Not permitted")

        sweepBridgeTokens()
        val token = StringBuilder(32)
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        repeat(32) { token.append(alphabet[bridgeTokenRng.nextInt(alphabet.length)]) }
        val t = token.toString()
        val now = System.currentTimeMillis()
        bridgeTokens[t] = BridgeToken(canon, player, ip, now,
            bridgeExpiryAfter(now, now))

        val origin = requestOrigin(s)
        val stream = "$origin/api/media?path=${URLEncoder.encode(canon, "UTF-8")}&bt=$t"
        val verify = "$origin/api/bridge/verify?t=$t"
        // Bridge players (MPC-HC / MPC-BE / mpv — no registered URL protocol
        // of their own) receive a lumidrop:// payload carrying ONLY the
        // player id and the verify URL; the media URL itself comes from the
        // verify response, so a hand-crafted payload can at worst make the
        // bridge ask some server whether it may open a FIXED player with an
        // http(s) URL — the launch never executes a shell, never resolves a
        // user-chosen executable, and never passes more than one argument.
        // GOAT-PASS 9 (Android 5–7): java.util.Base64 is API 26+ — the bridge
        // launcher crashed with NoClassDefFoundError on Android 5/6/7. The
        // android.util encoder produces the byte-identical URL-safe,
        // unpadded, unwrapped alphabet the bridge payload has always used.
        val payload = android.util.Base64.encodeToString(
            JSONObject().put("p", player).put("v", verify).toString().toByteArray(Charsets.UTF_8),
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP)
        return json(JSONObject()
            .put("success", true)
            .put("t", t)
            .put("player", player)
            .put("stream", stream)
            .put("verify", "/api/bridge/verify?t=$t")
            .put("scheme", if (player == "vlcwin") "vlc" else if (player == "potplayer") "potplayer" else "lumidrop")
            .put("payload", payload)
            // §1: the token no longer dies on a fixed clock — every fetched
            // byte renews it. The client gets the idle window purely as
            // informational metadata (it renders nothing from it).
            .put("expiresInMs", BRIDGE_TOKEN_IDLE_MS))
    }

    private fun apiBridgeVerify(s: IHTTPSession): Response {
        val t = s.parameters["t"]?.firstOrNull() ?: return jsonErr("No token")
        val tok = bridgeTokens[t]
        if (tok == null || tok.expiresAt < System.currentTimeMillis()) {
            return newFixedLengthResponse(Status.NOT_FOUND, "application/json",
                JSONObject().put("ok", false).put("error", "Token expired").toString())
        }
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        // The bridge runs on the SAME Windows machine as the browser that
        // minted the token, so the peer IP must match — a token leaked to
        // another device does not stream.
        if (ip != "unknown" && ip != tok.ip && ip != "127.0.0.1") {
            return newFixedLengthResponse(Status.FORBIDDEN, "application/json",
                JSONObject().put("ok", false).put("error", "Wrong client").toString())
        }
        // Called by the bridge handler itself → this IS the bridge
        // confirming. (The web client's own polls carry ?poll=1 and are
        // answered WITHOUT flipping the bit, so the UI's confirmation
        // remains honest — only a real bridge call or a real first byte
        // fetched by the player counts as "launched".)
        val isBridgeCall = s.parameters["poll"]?.firstOrNull() != "1"
        if (isBridgeCall) {
            tok.bridgeConfirmed = true
            // §1 long-play: a REAL bridge handshake is an authenticated use —
            // slide the idle window. The web client's ?poll=1 probe is NOT
            // (it carries no bridge-side proof of anything) and does not
            // renew.
            tok.expiresAt = maxOf(tok.expiresAt,
                bridgeExpiryAfter(tok.mintedAt, System.currentTimeMillis()))
        }
        return json(JSONObject()
            .put("ok", true)
            .put("player", tok.player)
            .put("launched", tok.bridgeConfirmed || tok.streamFetched)
            .put("url", "${requestOrigin(s)}/api/media?path=${URLEncoder.encode(tok.path, "UTF-8")}&bt=$t")
            .put("path", File(tok.path).name))
    }

    /** One-click Windows bridge installer. No .vbs, no manual registry
     *  editing, no resident listener/process: the downloaded .bat is a tiny
     *  stub that runs an embedded PowerShell install script (EncodedCommand,
     *  so no escaping fragility), which writes the handler to
     *  %LOCALAPPDATA%\LumiDropX, registers the lumidrop:// protocol under
     *  HKCU (per-user, no admin, with the ABSOLUTE resolved path), and
     *  writes an uninstaller alongside. Removal = one registry key + one
     *  folder. The handler itself validates the payload shape strictly,
     *  resolves the player executable from a FIXED candidate set (plus App
     *  Paths), verifies the launch with this server, and passes the media
     *  URL as a single Start-Process argument — never a shell string. */
    private fun apiBridgeInstaller(s: IHTTPSession): Response {
        // PowerShell content is authored with '§' standing in for '$' so the
        // Kotlin compiler can never try to interpolate it; swapped at the end.
        val handlerPs1 = """
param([string]§Url)
§ErrorActionPreference='Stop'
try {
  if(-not §Url){ exit 2 }
  if(§Url -notmatch '^lumidrop://p/[A-Za-z0-9\-_]+§'){ exit 3 }
  §b64=§Url.Substring(14).Replace('-','+').Replace('_','/')
  while(§b64.Length % 4 -ne 0){ §b64+='=' }
  §j=[Text.Encoding]::UTF8.GetString([Convert]::FromBase64String(§b64)) | ConvertFrom-Json
  §p=[string]§j.p; §v=[string]§j.v
  if(-not §p -or -not §v){ exit 4 }
  if(§v -notmatch '^https?://'){ exit 5 }
  §r=Invoke-WebRequest -UseBasicParsing -TimeoutSec 6 -Uri §v
  if(§r.StatusCode -ne 200){ exit 6 }
  §o=§r.Content | ConvertFrom-Json
  if(-not §o.ok -or [string]§o.player -ne §p){ exit 7 }
  §u=[string]§o.url
  if(§u -notmatch '^https?://'){ exit 8 }
  §pf=[Environment]::GetFolderPath('ProgramFiles')
  §pf86=§pf+' (x86)'
  §local=§env:LOCALAPPDATA
  §cands=@{
    vlcwin=@("§pf\VideoLAN\VLC\vlc.exe","§pf86\VideoLAN\VLC\vlc.exe","§local\VideoLAN\VLC\vlc.exe")
    potplayer=@("§pf\DAUM\PotPlayer\PotPlayer64.exe","§pf\DAUM\PotPlayer\PotPlayerMini64.exe","§pf86\DAUM\PotPlayer\PotPlayerMini64.exe")
    mpc=@("§pf\MPC-HC\mpc-hc64.exe","§pf\MPC-HC\mpc-hc.exe","§pf86\MPC-HC\mpc-hc.exe")
    mpcbe=@("§pf\MPC-BE\x64\mpc-be64.exe","§pf\MPC-BE\x86\mpc-be.exe","§pf86\MPC-BE\x86\mpc-be.exe")
    mpv=@("§local\Programs\mpv\mpv.exe","§pf\mpv\mpv.exe","§env:APPDATA\mpv\mpv.exe","C:\mpv\mpv.exe","§local\Microsoft\WinGet\Links\mpv.exe")
  }
  §exe=§null
  if(§cands.ContainsKey(§p)){ foreach(§c in §cands[§p]){ if(Test-Path §c){ §exe=§c; break } } }
  if(-not §exe){
    §reg=@{ vlcwin='vlc.exe'; potplayer='PotPlayer64.exe'; mpc='mpc-hc64.exe'; mpcbe='mpc-be64.exe'; mpv='mpv.exe' }[§p]
    if(§reg){
      foreach(§root in 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths','HKCU:\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths'){
        §ap=Get-ItemProperty -ErrorAction SilentlyContinue (Join-Path §root §reg)
        if(§ap -and §ap.'(default)' -and (Test-Path §ap.'(default)')){ §exe=§ap.'(default)'; break }
      }
    }
  }
  if(-not §exe){ exit 9 }
  Start-Process -FilePath §exe -ArgumentList @(§u)
  exit 0
} catch { exit 99 }
""".trimIndent().replace("§", "$")

        val installPs1 = """
§ErrorActionPreference='Stop'
§dir=Join-Path §env:LOCALAPPDATA 'LumiDropX'
New-Item -ItemType Directory -Force -Path §dir | Out-Null
§handler=@'
§handlerBody
'@
[IO.File]::WriteAllText((Join-Path §dir 'wsx-bridge.ps1'), §handler)
§key='HKCU:\Software\Classes\lumidrop'
New-Item -Path §key -Force | Out-Null
Set-ItemProperty -Path §key -Name '(default)' -Value 'URL:LumiDrop X player bridge'
Set-ItemProperty -Path §key -Name 'URL Protocol' -Value ''
New-Item -Path (§key+'\shell\open\command') -Force | Out-Null
§script=(Join-Path §dir 'wsx-bridge.ps1')
Set-ItemProperty -Path (§key+'\shell\open\command') -Name '(default)' -Value ('powershell.exe -NoProfile -ExecutionPolicy Bypass -File "'+§script+'" "%1"')
§un='@echo off'+"`r`n"+'reg delete HKCU\Software\Classes\lumidrop /f'+"`r`n"+'rd /s /q "'+§dir+'"'+"`r`n"+'echo LumiDrop X player bridge removed.'+"`r`n"+'pause'
[IO.File]::WriteAllText((Join-Path §dir 'uninstall.cmd'), §un)
Write-Host ''
Write-Host 'LumiDrop X player bridge installed.'
Write-Host 'MPC-HC, MPC-BE and mpv can now stream directly from LumiDrop X.'
Write-Host ('Uninstall anytime: '+(Join-Path §dir 'uninstall.cmd'))
""".trimIndent().replace("§", "$").replace("${'$'}handlerBody", handlerPs1)

        // EncodedCommand = Base64 of UTF-16LE — the one form no cmd quoting
        // can touch.
        // GOAT-PASS 9 (Android 5–7): android.util.Base64 (API 1+) instead of
        // the API-26+ java.util.Base64 — NO_WRAP matches the basic encoder's
        // no-line-separator output byte for byte.
        val enc = android.util.Base64.encodeToString(
            installPs1.toByteArray(Charsets.UTF_16LE), android.util.Base64.NO_WRAP)
        val bat = "@echo off\r\n" +
            "echo Setting up the LumiDrop X player bridge (current user only)...\r\n" +
            "powershell -NoProfile -ExecutionPolicy Bypass -EncodedCommand $enc\r\n" +
            "pause\r\n"
        val bytes = bat.toByteArray(Charsets.ISO_8859_1)
        val resp = newFixedLengthResponse(Status.OK, "application/octet-stream",
            java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
        resp.addHeader("Content-Disposition",
            dispositionAttachment("LumiDropX-Player-Bridge-Setup.bat"))
        return resp
    }

    private fun apiMedia(s: IHTTPSession): Response {
        val path = s.parameters["path"]?.firstOrNull() ?: return jsonErr("No path")
        val file = File(path); if (!file.exists() || !file.isFile) return jsonErr("Not found")
        // Bridge-token stream (external Windows player): the token was
        // minted by an authenticated Full-Access session on this same IP;
        // validate it against the canonical file, mark the stream fetched
        // (the launch UI's honest "launched" bit), and serve. Serving stays
        // inline (no Content-Disposition) with full Range/ETag support so
        // players stream and seek rather than download-first.
        val bt = s.parameters["bt"]?.firstOrNull()
        if (bt != null) {
            val tok = bridgeTokens[bt]
            if (tok == null || tok.expiresAt < System.currentTimeMillis()) return jsonErr("Token expired")
            val reqIp = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
            if (reqIp != "unknown" && reqIp != tok.ip && reqIp != "127.0.0.1") return jsonErr("Wrong client")
            val canon = try { file.canonicalPath } catch (e: Exception) { file.absolutePath }
            if (canon != tok.path) return jsonErr("Wrong file")
            tok.streamFetched = true
            // §1 long-play (the core of the 15-minute fix): every AUTHENTICATED
            // range fetch is a use — slide the idle window forward. A player
            // streaming a 20-hour video renews continuously (players fetch in
            // bursts throughout playback, so the 8h idle window is crossed
            // over and over); only a token nobody has used for 8h, or whose
            // 48h absolute cap arrived, actually expires. maxOf() keeps the
            // absolute cap authoritative: renewal can never push expiry past
            // mint+absolute.
            tok.expiresAt = maxOf(tok.expiresAt,
                bridgeExpiryAfter(tok.mintedAt, System.currentTimeMillis()))
        }
        val mime = MimeTypes.getMimeType(file.extension)
        // ── Pass-15 (fast first image open): scaled lightbox previews ─────
        // The reported defect: opening an image for the first time took a
        // long time — worst on the iPad 4 — because the lightbox streamed
        // the ORIGINAL multi-megapixel file and the client decoded every
        // pixel of it. A preview=1 request for an image is now served as a
        // 2048px JPEG (retina-crisp on every screen this app targets, and
        // comfortably above the 2048×1536 iPad 4 panel): the host decodes
        // and downscales ONCE (LRU-memoized, browser-cacheable via a
        // stable identity URL the client builds with &v=size-mtime), the
        // old client pays a fraction of the decode cost, and the second
        // open is instant from the browser's own cache. Non-image files,
        // and any image whose decode fails, fall through to the original
        // byte stream untouched.
        if (s.parameters["preview"]?.firstOrNull() == "1" &&
            MimeTypes.isImage(file.extension.lowercase())) {
            val previewId = "${file.absolutePath}|pv|${file.length()}-${file.lastModified()}"
            synchronized(previewCache) { previewCache[previewId] }?.let { cached ->
                return newFixedLengthResponse(Status.OK, "image/jpeg",
                    ByteArrayInputStream(cached), cached.size.toLong()).also {
                    it.addHeader("Cache-Control", "max-age=86400")
                }
            }
            val bmp = decodeScaled(file.absolutePath, PREVIEW_MAX_PX, null)   // preview: 2048px — above the store kinds, full decode by design
            if (bmp != null) {
                val baos = ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, baos)
                bmp.recycle()
                val b = baos.toByteArray()
                synchronized(previewCache) { previewCache[previewId] = b }
                return newFixedLengthResponse(Status.OK, "image/jpeg", ByteArrayInputStream(b), b.size.toLong()).also {
                    it.addHeader("Cache-Control", "max-age=86400")
                }
            }
        }
        // Phase 5: media playback gets the same identity validators as
        // downloads — ETag/Last-Modified on every response, If-Range honored
        // on scrub-ranges. A video scrubbed after the underlying file
        // changed on disk must not stitch old-buffer + new-file bytes into a
        // glitching decode; a clean full 200 restart is the correct signal
        // for the player too. Still deliberately NO transfer tracking:
        // playback is not a transfer (unchanged behavior).
        val etag = fileETag(file)
        val lastMod = httpDate(file.lastModified())
        val rh   = s.headers["range"]
        val rangeOk = rh != null && ifRangeMatches(s.headers["if-range"], etag, lastMod)
        if (rh != null && rangeOk) return serveRange(file, mime, rh, file.length(), null, false, etag, lastMod)
        return servedFixed(Status.OK, mime, java.io.BufferedInputStream(FileInputStream(file), 256 * 1024), file.length()).also {
            it.addHeader("Accept-Ranges", "bytes")
            it.addHeader("ETag", etag)
            it.addHeader("Last-Modified", lastMod)
            // Final Stabilization (§10/§15): this endpoint is the app's
            // dedicated INLINE VIEWING surface. PDFs get an explicit
            // inline disposition (browsers' own viewer renders them; the
            // separate /api/download route stays the attachment path, so
            // viewing and downloading can never collide). Ebooks never
            // reach this branch as previews in the client, but if one is
            // addressed directly it is forced to an attachment here —
            // legacy Safari must never be handed epub/mobi bytes it would
            // try to render as a raw text page.
            if (mime == "application/pdf") {
                it.addHeader("Content-Disposition", dispositionInline(file.name))
            } else if (MimeTypes.isBook(file.extension.lowercase())) {
                it.addHeader("Content-Disposition", dispositionAttachment(file.name))
            }
        }
    }

    // ── Thumbnail ─────────────────────────────────────────────────────────────

    // Final Release (§speed / §5): thumbnail bytes memoized in a small LRU
    // keyed by (path, requested size, length-mtime identity). Generation
    // was per-request, which N clients — or one client whose HTTP cache
    // was emptied — turned into N full source decodes of the same file.
    // Identity rides the key, so a changed file can never serve a stale
    // thumbnail. Bounded: 48 entries of already-downscaled JPEG (~1-3 MB).
    // ── PASS 19 (§4.5): the hot tier is budgeted BY BYTES (4 MB), not
    // entries — a 48-entry count cap let 48 near-300 KB 512px tiles occupy
    // ~14 MB while the same cap evicted hundreds of tiny 4 KB micro tiles
    // needlessly. Entry count is still floored at 96 so a burst of small
    // tiles never thrashes. Eviction stays LRU (accessOrder=true above).
    private val thumbCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        private var bytes = 0L
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean {
            val overBytes = bytes > 4 * 1024 * 1024
            val overCount = size > 96
            if (!overBytes && !overCount) return false
            bytes -= eldest.value.size
            return true
        }
        override fun put(key: String, value: ByteArray): ByteArray? {
            val prev = super.put(key, value)
            bytes += value.size - (prev?.size ?: 0)
            return prev
        }
    }

    // ── Pass-15 (fast first image open): the lightbox preview twin of ─────
    // thumbCache — bigger bytes per entry (a 2048px JPEG is 200–800 KB),
    // so a tighter entry cap; identity-keyed the same way so a changed file
    // can never serve a stale preview.
    private val previewCache = object : LinkedHashMap<String, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>): Boolean = size > 12
    }
    private val PREVIEW_MAX_PX = 2048

    // ── Pass-16 (instant thumbnails, two structural halves) ────────────────────
    //  (a) A DISK cache under the app's own cacheDir: a thumbnail's JPEG bytes
    //      are pure content — reproducible from (path, size, length-mtime
    //      identity), so persisting them costs nothing in correctness and
    //      turns "app restarted / reconnected / second device opens the same
    //      album" from a full re-decode storm into an instant file read. The
    //      in-memory LRU above stays as the hot tier; the disk tier has no
    //      eviction beyond Android's own cacheDir management.
    //  (b) A generation gate: one thumbnail DECODE (BitmapFactory for images,
    //      MediaMetadataRetriever for video — expensive, 50–500 ms each) at a
    //      time, MAX 2 in parallel. A legacy browser that fires every tile's
    //      request at once (iOS 10 Safari has no loading=lazy — see the
    //      client's lazy-load shim) can no longer stack dozens of concurrent
    //      decodes on the CPU, which is what made the whole web UI — and
    //      Quick Play, and the lightbox — feel slow while thumbnails were
    //      gray. Requests queue in arrival order; each served result lands in
    //      BOTH caches, so no decode ever happens twice.
    private val thumbDiskCacheDir: File by lazy {
        File(context.cacheDir, "webthumbs").apply { mkdirs() }
    }
    private val thumbGenGate = java.util.concurrent.Semaphore(3, true)
    // PASS 19 (§4.4): one in-flight decode per thumbId — see apiThumb's note.
    private val thumbInflight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<ByteArray?>>()

    // ── Pass-17 (instant media on the iPad 4 — server-side PRE-WARM) ────────
    // A legacy browser (iOS 10: no loading=lazy, no HTTP/2, 4-6 plain HTTP/1.1
    // connections) requests its visible tiles one after another — each one
    // waits for a decode slot behind whatever else is decoding. Pre-warming
    // the FIRST PAGE worth of thumbnails the moment the LIST payload is
    // served means most of those requests land in a warm cache and render
    // in the same frame they are requested — the reported "images and
    // videos take a long time to appear on the iPad 4 while other phones
    // are fine" is exactly this gap. The pre-warm runs through the same
    // generation path (same caches, same gate) on a tiny daemon executor,
    // so it can never decode anything twice and never adds a new mechanism.
    private val thumbPreWarmExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "wsx-thumb-prewarm").apply { isDaemon = true }
    }
    private val preWarmedThumbIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private fun preWarmThumb(path: String, size: Int) {
        try {
            val f = File(path)
            if (!f.exists() || !f.isFile) return
            val thumbId = "$path|$size|${f.length()}-${f.lastModified()}"
            if (preWarmedThumbIds.contains(thumbId)) return
            // Bounded: a long session with many albums could otherwise grow
            // this set forever. Clearing it only ever costs a redundant
            // (cache-hit) warming pass, never a decode.
            if (preWarmedThumbIds.size > 500) preWarmedThumbIds.clear()
            preWarmedThumbIds.add(thumbId)
            thumbPreWarmExec.execute {
                try {
                    // Same tiers and gate as apiThumb: memory LRU → disk cache →
                    // (gated) decode + persist. Reaching this from a background
                    // thread simply warms the tiers ahead of the client.
                    synchronized(thumbCache) { thumbCache[thumbId] }?.let { return@execute }
                    val diskFile = thumbDiskFile(thumbId)
                    if (diskFile.exists()) return@execute
                    val ext = f.extension.lowercase()
                    // ── Pass-41 (§1 — the pre-warm YIELDS to every live request) ──
                    // This used to acquireUninterruptibly() the SAME 3-permit
                    // gate the real /api/thumb requests block on — so a
                    // background pre-warm of up to 20 VIDEO thumbnails (a
                    // MediaMetadataRetriever decode each, seconds apiece on
                    // old hardware) could occupy every permit while a legacy
                    // browser's visible tiles queued behind it, each queued
                    // request HOLDING one of iOS Safari's 6 per-host
                    // connections — and the video the user actually tapped
                    // starved behind the flood for minutes ("stuck loading
                    // 2–3 min, then plays fine"). The pre-warm now takes a
                    // permit ONLY when one is free this instant: busy gate →
                    // skip (the tile decodes on demand when it is really
                    // requested — the pre-warm-free behavior, which never
                    // starved anyone). Warming is pure prefetch; it must
                    // never delay a live request.
                    fun warmDecode(decode: () -> Bitmap?): Bitmap? {
                        if (!thumbGenGate.tryAcquire()) return null
                        try { return decode() } finally { thumbGenGate.release() }
                    }
                    val bmp = when {
                        MimeTypes.isImage(ext) -> warmDecode { decodeScaled(path, size, context) }
                        MimeTypes.isVideo(ext) -> warmDecode { videoThumb(path, size) }
                        MimeTypes.isAudio(ext) -> warmDecode { com.lumidrop.x.media.ThumbnailShared.audioThumb(path, size) }
                        else -> null
                    } ?: return@execute
                    val baos = ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, baos)
                    bmp.recycle()
                    val b = baos.toByteArray()
                    synchronized(thumbCache) { thumbCache[thumbId] = b }
                    try { diskFile.writeBytes(b) } catch (e: Exception) { /* memory tier still serves */ }
                } catch (e: Exception) { /* best-effort warming only */ }
            }
        } catch (e: Exception) { /* never let warming break the listing */ }
    }
    // ── PASS 19 (§4.5): the disk tier is capped at 64 MB, trimmed by
    // last-modified (oldest first) after each write. Android's own cacheDir
    // management remains the outer bound; this keeps a months-long session
    // from growing the tier without limit on its own.
    private fun trimThumbDisk() {
        try {
            val files = thumbDiskCacheDir.listFiles() ?: return
            var total = files.sumOf { it.length() }
            if (total <= 64L * 1024 * 1024) return
            val sorted = files.sortedBy { it.lastModified() }
            for (f in sorted) {
                if (total <= 64L * 1024 * 1024) break
                val len = f.length()
                if (f.delete()) total -= len
            }
        } catch (e: Exception) { /* cache dir trouble — the tier still serves reads */ }
    }

    private fun thumbDiskFile(thumbId: String): File {
        val md = java.security.MessageDigest.getInstance("SHA-256")
            .digest(thumbId.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder().apply { for (b in md) append("%02x".format(b.toInt() and 0xFF)) }.toString()
        return File(thumbDiskCacheDir, hex.take(40) + ".jpg")
    }
    /** ── Pass-44 (§2): the thumb ETag — the stability half of thumb identity.
     *  The URL already carries the version (&v=size-mtime) and the response
     *  already carries max-age, but the web client's WINDOWED lists
     *  (re)create <img> elements as the viewport slides, and every recreate
     *  re-requests the URL. With an ETag, a recreate whose cached copy is
     *  fresh serves from cache with ZERO requests, and an aged one
     *  revalidates with a 304 (no body) instead of re-downloading the
     *  JPEG — on legacy Safari's 6-connection pool that is the difference
     *  between thumbnails that reappear instantly and thumbnails that
     *  blank out behind a request queue. Derived from the same
     *  path|size|version identity the cache key uses. */
    private fun thumbEtag(thumbId: String): String = "\"t${Integer.toHexString(thumbId.hashCode())}\""

    private fun thumbCachedResponse(b: ByteArray, etag: String): Response =
        newFixedLengthResponse(Status.OK, "image/jpeg", ByteArrayInputStream(b), b.size.toLong()).also {
            it.addHeader("Cache-Control", "max-age=86400")
            it.addHeader("ETag", etag)
        }

    /** A revalidation hit: the browser already holds this exact thumbnail.
     *  No body, same identity headers, and no-store-free caching so the
     *  304 itself is remembered. */
    private fun thumbNotModified(etag: String): Response =
        newFixedLengthResponse(Status.NOT_MODIFIED, "text/plain", "").also {
            it.addHeader("Cache-Control", "max-age=86400")
            it.addHeader("ETag", etag)
        }

    private fun apiThumb(s: IHTTPSession): Response {
        val path = s.parameters["path"]?.firstOrNull() ?: return jsonErr("No path")
        val sz   = s.parameters["size"]?.firstOrNull()?.toIntOrNull()?.coerceIn(40, 1024) ?: 256
        val file = File(path); if (!file.exists()) return jsonErr("Not found")
        val thumbId = "$path|$sz|${file.length()}-${file.lastModified()}"
        // Pass-44 (§2): revalidation first — an If-None-Match that matches
        // the identity answers 304 without touching either cache tier.
        val etag = thumbEtag(thumbId)
        val inm = try { s.headers["if-none-match"] } catch (e: Exception) { null }
        if (inm != null && inm == etag) return thumbNotModified(etag)
        synchronized(thumbCache) { thumbCache[thumbId] }?.let { cached ->
            return thumbCachedResponse(cached, etag)
        }
        // Pass-16: the disk tier — a hit here is a plain file read (a
        // millisecond even on old hardware) and also re-warms the hot tier.
        val diskFile = thumbDiskFile(thumbId)
        if (diskFile.exists()) {
            try {
                val fromDisk = diskFile.readBytes()
                synchronized(thumbCache) { thumbCache[thumbId] = fromDisk }
                return thumbCachedResponse(fromDisk, etag)
            } catch (e: Exception) { /* unreadable entry — fall through and regenerate */ }
        }
        // ── PASS 19 (§4.4 — in-flight dedup): a second request for the SAME
        // thumbnail while its decode is still running (a legacy browser's
        // tile burst racing the pre-warm executor, a re-rendered window)
        // used to queue its OWN full decode. It now waits on the in-flight
        // future and answers from its result — one decode per thumbId, ever.
        val existing: java.util.concurrent.CompletableFuture<ByteArray?>? = synchronized(thumbInflight) {
            val f = thumbInflight[thumbId]
            if (f != null) f else {
                val mine = java.util.concurrent.CompletableFuture<ByteArray?>()
                thumbInflight[thumbId] = mine
                null
            }
        }
        if (existing != null) {
            // someone else is decoding this exact id — wait for them
            val theirBytes: ByteArray? = try { existing.get(30, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { null }
            if (theirBytes != null) return thumbCachedResponse(theirBytes, etag)
            // their decode failed — fall through and try our own
        }
        val ext  = file.extension.lowercase()
        // Pass-16: bounded generation — at most 2 concurrent decodes, FIFO.
        // The permit is released in the finally below; queued requests simply
        // start a little later, in arrival (roughly viewport) order.
        // ── Pass-41 (§1): the wait is BOUNDED now. An unbounded block held
        // the client's HTTP connection for as long as the queue took — and a
        // legacy browser has only 6 connections per host, so a deep decode
        // queue (several MediaMetadataRetriever runs on large videos, one
        // permit each, seconds apiece) starved every OTHER request from the
        // same browser, media playback included: the exact "video stuck
        // loading for minutes" report. Five seconds is far beyond any
        // healthy wait for a thumbnail; past it the request fails FAST
        // (503, no-store) and the connection returns to the browser's pool
        // for the requests that actually matter. The client's lazy-loader
        // only asks for near-viewport tiles, so the healthy path is
        // unaffected — and with the pre-warm now yielding (see preWarmThumb)
        // the queue drains at the live decode rate alone.
        val gateAcquired = try {
            thumbGenGate.tryAcquire(5, java.util.concurrent.TimeUnit.SECONDS)
        } catch (ie: InterruptedException) {
            Thread.currentThread().interrupt(); false
        }
        if (!gateAcquired) {
            return newFixedLengthResponse(Status.SERVICE_UNAVAILABLE, "text/plain", "Thumbnail busy").also {
                it.addHeader("Cache-Control", "no-store")
                it.addHeader("Retry-After", "2")
            }
        }
        val bmp: Bitmap?
        val myFuture = synchronized(thumbInflight) { thumbInflight[thumbId] }
        try {
            bmp = when {
                MimeTypes.isImage(ext) -> decodeScaled(path, sz, context)
                MimeTypes.isVideo(ext) -> videoThumb(path, sz)
                // New: embedded album art for audio files, previously not
                // attempted at all here (fell straight to "no thumb" below).
                // Most audio files simply have none — that's fine, this only
                // adds a thumbnail when one genuinely exists.
                MimeTypes.isAudio(ext) -> com.lumidrop.x.media.ThumbnailShared.audioThumb(path, sz)
                else                   -> null
            }
            if (bmp == null) {
                myFuture?.complete(null)
                return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "No thumb")
            }
        } catch (e: Exception) {
            myFuture?.completeExceptionally(e)
            throw e
        } finally {
            thumbInflight.remove(thumbId)
            thumbGenGate.release()
        }
        val baos = ByteArrayOutputStream()
        // Quality 92 (was 82) — eliminates JPEG blocking artefacts that caused the
        // blurry appearance at small tile sizes without meaningfully inflating file size
        bmp!!.compress(Bitmap.CompressFormat.JPEG, 92, baos); bmp.recycle()
        val b = baos.toByteArray()
        synchronized(thumbCache) { thumbCache[thumbId] = b }
        // Pass-16: persist for every future session/restart/device.
        try { diskFile.writeBytes(b); trimThumbDisk() } catch (e: Exception) { /* cache dir full/unwritable — memory tier still serves */ }
        myFuture?.complete(b)
        return thumbCachedResponse(b, etag)
    }

    // Thumbnail generation itself now lives in media/ThumbnailShared.kt —
    // shared verbatim with the native app's own Recent Files thumbnail
    // engine (Phase 16) rather than duplicated here. Kept as thin
    // same-signature wrappers so every call site below is unchanged.
    private fun decodeScaled(path: String, maxSz: Int, context: Context? = null): Bitmap? =
        com.lumidrop.x.media.ThumbnailShared.decodeScaled(path, maxSz, context)

    private fun videoThumb(path: String, maxSz: Int = 256): Bitmap? =
        com.lumidrop.x.media.ThumbnailShared.videoThumb(path, maxSz)

    // ── Quick State — ultra-cheap combined version + count + transfer-mode poll ─
    // Clients call GET /api/qstate every 250 ms.  The payload is ~50 bytes so the
    // polling overhead is negligible on a local network.  When the version counter
    // changes the client then calls the full /api/quickshare to refresh the list.
    // "tm" and "qc" are now resolved PER REQUESTING DEVICE: each connected client
    // gets its own assigned mode and only counts Quick Share items actually
    // targeted at it (or broadcast to everyone) — see deviceModes/QSItem.targetIps.
    private var lastQSweepEpoch: Long = -1L
    private fun apiQState(s: IHTTPSession): Response {
        // Cheap (small linear scan of a normally-tiny live set) and this is
        // the single most frequent point of server activity while any
        // client's WebUI is open — piggybacking the stale-PENDING sweep here
        // (on top of the one already on every transfer creation) keeps an
        // abandoned registration's "Waiting" ghost from lingering on the
        // Host's Transfers tab a moment longer than it has to.
        // Phase 10 (§speed D10): the sweep is @Synchronized on the engine
        // monitor — running it on EVERY poll of the app's most-polled
        // endpoint contended with claim/complete/fail on the byte hot path.
        // It only has work to do when the engine actually mutated, so gate
        // it on the global epoch: identical staleness guarantees (any
        // mutation re-arms the sweep) at zero idle cost.
        if (TransferManager.globalEpoch() != lastQSweepEpoch) {
            lastQSweepEpoch = TransferManager.globalEpoch()
            TransferManager.expireStalePending()
        }
        // Phase 5: orphaned-staging housekeeping rides the same hot spot —
        // timestamp-gated to one subtraction when idle, so the most-polled
        // endpoint in the app pays effectively nothing for it.
        sweepStaleStagingFiles()
        // v1.0 (§hotspot-off): expired resumable-upload records (and their
        // partials) ride the same hot spot — zero idle cost, and every open
        // page's 250ms poll keeps the reconnect window honest.
        sweepResumableUploads()
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        return json(clientStateJson(ip))
    }

    // ── Per-client live state payload (Phase 4, v3 §5 item 5) ────────────────
    // ONE payload, THREE transports: the SSE /api/events stream pushes it the
    // moment anything changes; /api/qstate serves it to the XHR-poll fallback
    // (older Windows browsers with no EventSource) and to a freshly reloaded
    // page rebuilding its Transfers drawer. Identical bytes from all three,
    // so behavior is identical on every engine in the compatibility matrix.
    // The xfers array is THIS client's own downloads — the drawer's entire
    // data source, synchronized to the host's actual byte progress.
    private fun clientStateJson(ip: String): JSONObject {
        val visibleCount = quickShareQueue.count {
            // Pass-6: local capture — targetIps is a mutable var now, so a
            // null-check can't smart-cast the property itself.
            val targets = it.targetIps
            (targets == null || ip in targets) && ip !in it.consumedByIps
        }
        val ua = connectedUAs[ip] ?: ""
        return JSONObject().apply {
            // Phase 2 of 10 — identity + ordering fields. "sid" identifies
            // the server INSTANCE: a different sid means the host restarted
            // (in-place settings change, stop/start), which resets the
            // version numbering — the client treats that as a NEW ordering
            // domain, re-applies everything, and reconciles, instead of
            // misreading the fresh low version numbers as stale events.
            // "v" stays per-instance monotonic, which is what lets the
            // client drop genuinely-late transport deliveries (a slow XHR
            // poll settling after a newer SSE push) without regressing
            // mode/transfer state.
            put("sid",          serverInstanceId)
            put("v",           stateVersion.get())
            put("qc",          visibleCount)
            // "mode" is the explicit authoritative session mode ("transfer"
            // | "full"); "tm" (the boolean) is kept for a page still open
            // from a previous server build across an in-place host update.
            put("mode",        getDeviceMode(ip))
            put("tm",          getDeviceMode(ip) == "transfer")
            // Session capability as the host knows it (reported by this
            // client via /api/client/capability) — echoed back so the round
            // trip is observable and a lost report self-heals on the next
            // mismatch-triggered re-report.
            put("ep",          clientCaps.containsKey(ip))
            put("deviceLabel", if (ua.isNotEmpty()) parseDeviceLabel(ua) else "")
            // Name-keyed arrays (legacy, pre-Phase-1 shape) kept for any
            // WebUI page still open from a previous server build across an
            // in-place host update — the current page ALSO consumes the
            // tid-keyed fields below; see index.html's xfersApply/_applyPausedState.
            put("pausedDownloads", JSONArray(TransferManager.pausedDownloadNamesFor(ip)))
            put("pausedUploads",   JSONArray(TransferManager.pausedUploadRequestIdsFor(ip)))
            put("priorityDownloads",     JSONArray(TransferManager.priorityDownloadNamesFor(ip)))
            put("priorityUploadReqIds",  JSONArray(TransferManager.priorityUploadRequestIdsFor(ip)))
            put("cancelledDownloads",    JSONArray(TransferManager.cancelledDownloadNamesFor(ip)))
            put("cancelledUploadReqIds", JSONArray(TransferManager.cancelledUploadRequestIdsFor(ip)))
            // Phase 1 (stable transfer identity on the wire): the same
            // indications keyed by the server's own transfer ids, so two
            // same-named transfers (a plain download racing a Quick-Share
            // item, a retry landing while a tombstone is still within its
            // 6-second terminal window) can never cross state onto each
            // other's rows. The web drawer keys rows on "i" (see
            // clientTransfersJson) and consults these tid sets.
            put("pdt", JSONArray(TransferManager.pausedDownloadTidsFor(ip)))
            put("prt", JSONArray(TransferManager.priorityDownloadTidsFor(ip)))
            put("xfers", clientTransfersJson(ip))
            // Phase 10 (light-speed sync) — three new payload members:
            //  "fv":  filesystem epoch; change = refetch Files/media/home
            //         views NOW (the no-refresh-required signal).
            //  "inc": OTHER peers' incoming-to-host transfers, live — every
            //         open page sees "Phone is sending 3 files" the moment
            //         it starts, progresses in real time, and flashes
            //         terminal. Never includes this client's own uploads
            //         (its local rows already cover those — no duplicates).
            //  "ur":  this client's pending upload requests + their approval
            //         state, so the approve/reject verdict arrives as a PUSH
            //         (sessionEpoch bumps on every decision) instead of the
            //         1200ms poll the awaiting uploader used to run.
            put("fv", filesVersion.get())
            // GOAT-PASS 2: the receiving-gate capacity advertisement. The
            // field stays wired for forward/backward compatibility (a page
            // from any era reads whatever its host reports), but the value
            // is now permanently 1 — GOAT-PASS 2's specification pins
            // transfer concurrency at exactly 1, and both the engine gates
            // (TRANSFER_CONCURRENCY) and every client pump hard-cap at 1 so
            // not even a mixed-version peer can widen it.
            put("rconc", TransferManager.receivingConcurrencyLimit())
            // GOAT-PASS 2: the download-gate twin of rconc — same story:
            // the field survives for compatibility, the value is 1, and the
            // web client's QS dispatch pump hard-caps its own window at 1
            // regardless of what any host advertises (see QS_PUMP_WINDOW in
            // index.html).
            put("dconc", TransferManager.downloadConcurrencyLimit())
            // Pass-3 (R3 — per-client transfer visibility): the "inc" array
            // (other peers' incoming-to-host transfers) is REMOVED from the
            // per-client payload. Every web client's transfer previewer now
            // shows ONLY that client's own transfers — a file sent from a
            // laptop no longer appears as an uploading/waiting row on a
            // separately connected iPad. The host's own Transfers tab (and
            // its ReceivingModal notification) remains the single surface
            // that shows everything arriving at the host.
            put("ur", uploadRequestsJson(ip))
            // Next Release Pass (§10): web-client configuration revision —
            // bumps whenever the host saves WebUI settings
            // (WebUiSettingsManager), so every connected client refetches
            // /api/webui-config and rebuilds its navigation WITHOUT a page
            // refresh. Stale config events can't restore a disabled tab
            // because the revision only ever moves forward and the refetch
            // is authoritative.
            put("cfgv", WebUiSettingsManager.revision(context))
            // ── Pass-17 (liveness challenge): the CURRENT challenge nonce ──
            // for THIS peer, when one is pending (see probePeerAlive). The
            // page's JS acknowledges it exactly once via POST /api/alive —
            // a page whose network is down physically cannot, which is the
            // whole point of an ACTIVE challenge. Absent for every other
            // payload, so older pages simply never see the field.
            livenessNonceFor?.let { (nip, nonce) -> if (nip == ip) put("pn", nonce) }
        }
    }

    // This client's own DOWNLOAD transfers, live: queue order, state, byte
    // progress, speed. Finished entries are included for a short window so
    // the client's drawer can flash "Completed/Canceled" before sweeping the
    // row — mirroring the timing the old client-side queue had.
    //
    // Phase 1 (stable transfer identity): every entry now carries "i" — the
    // server-side transfer id — and the client keys its drawer rows on it.
    // The filename ("n") remains display-only. This is the fix for the whole
    // same-name collision class: two transfers named "photo.jpg" (a retry
    // racing a just-finished tombstone, a plain download racing a Quick-Share
    // item) previously collapsed onto ONE drawer row keyed by name, whose
    // progress/status cross-contaminated — and whose Cancel button sent a
    // name that findLiveDownloadId resolved to whichever entry it happened
    // to hit first, tearing down the WRONG transfer. Identity on the wire,
    // identity in the UI, identity in the cancel call — one chain.
    private fun clientTransfersJson(ip: String): JSONArray {
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        // Phase 3 of 10 (queue visibility): "q" is the 1-based position of a
        // PENDING entry within THIS client's own pending-download set, in the
        // engine's snapshot order (PENDING/ACTIVE first, then FIFO by
        // startedAt — see snapshot()'s comparator). The web client renders
        // "Waiting — 2nd in queue" from it instead of a bare "0%", so a file
        // parked behind the concurrency limit reads as queued, never as a
        // frozen transfer. Recomputed from the live snapshot on every payload
        // (SSE tick and poll alike), so a promotion or cancellation shifts
        // the numbers on the very next push — no stale positions, exactly
        // the "update it from authoritative state" bar.
        var queuePos = 0
        for (t in TransferManager.snapshot()) {
            if (t.direction != TransferManager.Direction.DOWNLOAD || t.peerIp != ip) continue
            // Pass-6 (R-A — previewer only after acceptance): a targeted
            // Quick-Share send pre-registers a PENDING "Waiting" row per
            // client the moment the host taps Send (preRegisterQuickShareSends
            // — that's what makes the HOST's Active tab honest from second
            // one). But those same rows also rode THIS payload, so the
            // receiver's transfer previewer auto-expanded and listed the
            // files as "Waiting" BEFORE the client had accepted anything —
            // the incoming-files list (Accept All / Get) is the surface that
            // owns an undecided offer, and the previewer should only ever
            // appear for transfers this client has actually taken on. Gate:
            // a PENDING Quick-Share row is held back while its queue item is
            // still live and THIS client hasn't consumed it (accept marks
            // consumedByIps; decline/accept clears the item entirely). The
            // moment the client accepts, the row is released to the payload
            // and the previewer opens on real progress. Terminal states
            // (declined/cancelled/failed/completed) and accepted-but-queued
            // rows are untouched.
            if (t.status == TransferManager.Status.PENDING &&
                t.source == TransferManager.Source.QUICK_SHARE &&
                !t.qsItemName.isNullOrEmpty()) {
                val undecided = quickShareQueue.firstOrNull { it.name == t.qsItemName }
                if (undecided != null && ip !in undecided.consumedByIps) continue
            }
            val terminal = t.status == TransferManager.Status.COMPLETED ||
                t.status == TransferManager.Status.FAILED || t.status == TransferManager.Status.CANCELLED
            // §2 Reconnect: a FAILED receive rides the payload for as long as
            // the engine keeps its record (the 10-minute reconnect window —
            // TransferManager.FAILED_DOWNLOAD_LINGER_MS), because the row IS
            // the user's handle for reconnecting. Completed/Cancelled keep
            // the 6-second flash: they're decided, nothing to resume.
            val terminalWindowMs = if (t.status == TransferManager.Status.FAILED)
                TransferManager.failedDownloadLingerMs() else 6000L
            if (terminal && t.finishedAt > 0 && now - t.finishedAt > terminalWindowMs) continue
            val row = JSONObject()
                .put("i", t.id)
                .put("n", t.fileName)
                .put("s", when (t.status) {
                    TransferManager.Status.ACTIVE -> "active"
                    TransferManager.Status.PENDING -> "pending"
                    TransferManager.Status.COMPLETED -> "completed"
                    TransferManager.Status.FAILED -> "failed"
                    // v1.0 (§decline-sync): explicit wire status for declines —
                    // the web previewer renders its own calm "Declined" label
                    // instead of collapsing it into cancelled.
                    TransferManager.Status.DECLINED -> "declined"
                    else -> "cancelled"
                })
                .put("r", t.transferredBytes)
                .put("t", t.totalBytes)
                .put("b", t.speedBps.toLong())
                .put("d", "dl")
            // ── Pass-22 (#3, pause/queue interplay — the pump's pause signal): ───
            // the per-row paused flag the browser's dispatch pump reads so a
            // deliberately paused row can release its pump slot (its resume is
            // the download manager's own re-request, not a new dispatch) and a
            // paused-but-undispatched item can be held back without wedging
            // the batch behind it.
            if (t.pauseRequested && (t.status == TransferManager.Status.PENDING || t.status == TransferManager.Status.ACTIVE))
                row.put("pd", 1)
            if (t.status == TransferManager.Status.PENDING) {
                queuePos++; row.put("q", queuePos)
                // ── GOAT-PASS 7 (§2A — the swallowed-dispatch signal): 1 when ──
                // this row's REAL byte-request has already arrived at a
                // download endpoint (even if it is still parked at the
                // concurrency gate). The browser dispatch pump reads it to
                // tell "legitimately queued at the gate" (keep waiting,
                // however long the queue is) from "the <a download> dispatch
                // was silently swallowed by the browser" (release the pump
                // slot and re-fire quickly instead of the old 25-second
                // never-requested stall). Absent on older hosts — a page
                // from this build reading an older host keeps the old
                // behavior by treating it as unknown-but-arrived.
                if (t.requestedAtMs > 0L) row.put("rq", 1)
            }
            // ── PASS 27 (the Active→Waiting fix): the handoff re-await ──
            // keeps the row ACTIVE (the browser's download manager is
            // taking over its intercepted download — a live receive, not a
            // queue state), so the honest "Waiting for Safari…" indication
            // rides the ho flag on the ACTIVE row itself. The client renders
            // the label WITHOUT flipping the state out of Active.
            if (t.reAwaitUntilMs > 0L) row.put("ho", 1)
            arr.put(row)
        }
        return arr
    }

    // Phase 10 (light-speed sync): other peers' incoming (UPLOAD-direction)
    // transfers, live — rows for every client's "inc" array. Same shape as
    // the client's own xfers rows plus "f" (the sending device's display
    // name) and direction "ul"; terminal rows ride the same 6-second window
    // so a completing arrival flashes "Received" on every open page before
    // sweeping. Progress rides the existing touch()/uploadEpoch chain.
    //
    // Pass-3 (R3): no longer served — the per-client state payload must
    // carry only that client's own transfers (see clientStateJson). Kept as
    // documentation of the wire format the "inc" field used to carry, for
    // any older page still open across an in-place host update.
    private fun incomingTransfersJson(@Suppress("UNUSED_PARAMETER") excludeIp: String): JSONArray {
        val now = System.currentTimeMillis()
        val arr = JSONArray()
        for (t in TransferManager.snapshot()) {
            if (t.direction != TransferManager.Direction.UPLOAD || t.peerIp == excludeIp) continue
            val terminal = t.status == TransferManager.Status.COMPLETED ||
                t.status == TransferManager.Status.FAILED || t.status == TransferManager.Status.CANCELLED ||
                t.status == TransferManager.Status.DECLINED
            if (terminal && t.finishedAt > 0 && now - t.finishedAt > 6000L) continue
            val row = JSONObject()
                .put("i", t.id)
                .put("n", t.fileName)
                .put("s", when (t.status) {
                    TransferManager.Status.ACTIVE -> "active"
                    TransferManager.Status.PENDING -> "pending"
                    TransferManager.Status.COMPLETED -> "completed"
                    TransferManager.Status.FAILED -> "failed"
                    TransferManager.Status.DECLINED -> "declined"
                    else -> "cancelled"
                })
                .put("r", t.transferredBytes)
                .put("t", t.totalBytes)
                .put("b", t.speedBps.toLong())
                .put("d", "ul")
                .put("f", t.peerName)
            arr.put(row)
        }
        return arr
    }

    // Phase 10 (light-speed sync): this client's pending upload requests —
    // the approval-flow state that used to be reachable only through the
    // 1200ms /api/upload/request-status poll. "pending" rows await a host
    // decision; "accepted" rows are green-lit but the client's own upload
    // queue hasn't fired the POST yet (both need to stay visible);
    // rejected/expired requests are absent — the client's poll handler
    // already treats absence exactly like a rejection verdict.
    private fun uploadRequestsJson(ip: String): JSONArray {
        val arr = JSONArray()
        for (pu in pendingUploads.values) {
            if (pu.fromIp != ip) continue
            if (pu.status != "pending" && pu.status != "accepted") continue
            arr.put(JSONObject().put("id", pu.id).put("n", pu.name).put("st", pu.status))
        }
        return arr
    }

    // ── Server-Sent Events (Phase 4, v3 §5 item 5) ───────────────────────────
    // One persistent connection per client; the host pushes a fresh snapshot
    // only when the client's payload actually differs from the last one sent
    // (idle = zero traffic — this is a push stream, not a disguised poll).
    // A comment-only heartbeat every 10s keeps intermediaries from timing the
    // connection out. Dead clients are detected by the write failure itself
    // (the browser closing the EventSource closes the read side of the pipe,
    // which makes the next flush throw) and are removed lazily.
    //
    // Phase 5 (v3 §6 item 3 — liveness audit): pushing to a client whose TCP
    // link stalled used to BLOCK the single SSE writer thread inside
    // PipedOutputStream.write (a full 64KB pipe with NanoHTTPD's reader
    // stuck on a dead socket never throws, it just blocks) — and since that
    // one thread serves every client, ONE stalled browser froze heartbeats
    // and updates for ALL of them, indefinitely, with the dead-client
    // cleanup never firing because it depends on a write actually failing.
    // Every push is now gated by a NON-BLOCKING back-pressure check first:
    // a client whose pipe still holds unread bytes is skipped this tick and
    // dropped outright after a sustained stall, so the writer thread can
    // never wedge on a client that stopped draining.
    private class SseClient(val ip: String, val out: PipedOutputStream, val pipeIn: PipedInputStream) {
        var lastPayload: String = ""
        var lastBeat: Long = 0L
        var backedUpSinceMs: Long = 0L
        // Phase 8 (§8.3): the per-client payload epochs this client's last
        // pushed payload was built from — see sseTick's dirty gate. -1 so a
        // freshly-registered stream is always "dirty" on its first tick
        // regardless of global state.
        var lastStructV: Long = -1L
        var lastSessionV: Long = -1L
        var lastPeerV: Long = -1L
        // Phase 10 (light-speed sync): the filesystem epoch this client's
        // last pushed payload was built from — see sseTick's extended dirty
        // gate. (The upload epoch is retired with the "inc" array — Pass-3
        // R3: upload-direction changes a client can see are already covered
        // by its own peer-scoped epoch and the session epoch for "ur".)
        var lastFv: Long = -1L
    }
    private val SSE_BACKPRESSURE_LIMIT_BYTES = 8 * 1024
    private val SSE_STALL_DROP_MS = 30_000L
    private val sseClients = java.util.concurrent.CopyOnWriteArrayList<SseClient>()
    private val sseExec: java.util.concurrent.ScheduledExecutorService =
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "wsx-sse").apply { isDaemon = true }
        }
    @Volatile private var sseTickerStarted = false

    // Phase 8 (§8.3): server-session-state epoch — bumped only when a
    // change that is visible in the per-client payload happens OUTSIDE
    // TransferManager and OUTSIDE the stateVersion-bumping endpoints:
    // a session's User-Agent first being recorded (the deviceLabel
    // changing from blank to named), a session being reaped (mode
    // override / capability / label reverting), or serve()'s in-band
    // grace-window mode reset. Deliberately NOT folded into stateVersion:
    // "v" is consumed by the web client as a quickshare-list change
    // signal, and a session lifecycle event must not trigger a
    // /api/quickshare refetch on every open page.
    private val sessionEpoch = java.util.concurrent.atomic.AtomicLong(0)
    // Phase 8: last tick's observed epochs — the whole-tick skip signal.
    private var lastTickStructV: Long = -1L
    private var lastTickSessionV: Long = -1L
    private var lastTickTmV: Long = -1L
    private var lastTickFv: Long = -1L
    // Pass-23: cadence stamp for the periodic expireStalePending drive (see
    // sseTick) — keeps the never-requested / stale-pending sweeps running on
    // a fixed 5-second cadence instead of only when a row is created or a
    // poll-only client happens to ask.
    @Volatile private var lastExpireStaleAtMs: Long = 0L

    private fun apiEvents(s: IHTTPSession): Response {
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        val pipeOut = PipedOutputStream()
        val pipeIn = PipedInputStream(pipeOut, 64 * 1024)
        val client = SseClient(ip, pipeOut, pipeIn)
        // ── Pass-18 (app→browser resume, event-driven half) ──────────────────
        // Detect the no-stream → stream TRANSITION for this client: a browser
        // page whose network dropped loses its event stream (the writer side
        // tears down within a tick or two of the OS noticing), so a NEW
        // stream attaching from the same ip is the moment the page is
        // PROVABLY back. That is the instant TransferManager resolves every
        // DOWNLOAD row latched "Auto-Resume on Reconnection" for this peer
        // into FAILED-interrupted — the browser's own auto-reconnect engine
        // (armed on exactly that failed-with-bytes snapshot) then re-requests
        // the file and the row re-opens in place, byte-resuming from its
        // checkpoint. Without this hook, the row waited for the blocked
        // socket write's own error (TCP retransmission backoff: a minute or
        // more on a long-lived stream) before the browser ever heard "failed"
        // — the reported "after reconnection the transfer doesn't resume".
        // The stall watchdog's flagged-DOWNLOAD re-probe branch is the 15s
        // cadence backstop for pages on the poll transport, where no stream
        // attach exists to hook. First-ever streams are a harmless no-op
        // (nothing is latched yet); extra parallel streams from the same
        // page (a reconnect racing the old stream's teardown) see
        // wasAbsent=false and stay silent.
        val wasAbsent = sseClients.none { it.ip == ip }
        sseClients.add(client)
        if (wasAbsent) TransferManager.onPageReconnected(ip)
        // First snapshot goes out on the stream immediately — the client's
        // drawer is correct from the first message, not after a tick.
        sseExec.execute {
            // GOAT-PASS 1 (§sync — faster EventSource reconnection): the SSE
            // "retry:" field tells every EventSource-capable browser how many
            // milliseconds to wait before reconnecting a dropped stream.
            // Browsers default to ~3–5s (varies by engine), which is exactly
            // why state resync after a brief blip felt slower than it should:
            // the page's own state poll covers the gap at 700ms, but the PUSH
            // channel itself only came back seconds later. 1000ms is a
            // deliberate middle ground — an order of magnitude below the
            // default, still far above anything that could hammer a host with
            // reconnect storms (and the XHR poll keeps state correct in the
            // window regardless). Older engines that ignore the field keep
            // their previous behavior; this is pure latency removal.
            try {
                client.out.write("retry: 1000\n\n".toByteArray(Charsets.UTF_8))
                client.out.flush()
            } catch (e: Exception) { /* the initial snapshot below surfaces any dead pipe anyway */ }
            val initial = clientStateJson(ip).toString()
            client.lastPayload = initial
            pushSse(client, initial)
            // EventSource's own reconnect gives the page a live stream back
            // after a host hiccup; nothing else to do here.
        }
        // (Phase 2: no ensureSseTicker() here anymore — the ticker starts
        // with the server instance itself in the init block, because the
        // session sweep must run even with zero event streams open.)
        // Phase 10: SafeResponse(live) — an aborted SSE connection now
        // closes pipeIn immediately (the stock code never closed it,
        // leaving the shared writer thread to discover the dead client only
        // via the 30s back-pressure stall-drop), and every payload flushes
        // to the socket the moment it is produced.
        return servedChunked("text/event-stream", pipeIn, live = true).also {
            it.addHeader("Cache-Control", "no-cache")
            it.addHeader("X-Accel-Buffering", "no")
        }
    }

    // ── Web → host capability report (Phase 2 of 10) ──────────────────────────
    // Form-encoded POST ("ep=1"/"ep=0"), the same body convention
    // /api/quickshare/accept uses. Only the requesting socket's session is
    // touched; a no-change report is a no-op (no version bump, no event);
    // a real change bumps the version so the reporter's next SSE payload,
    // the host's Connected Devices card, and /api/clients all reflect it on
    // their very next tick — the "green Quick-Play icon needs a refresh"
    // class of bug dies here, at the state-propagation layer, not by
    // force-refreshing any single icon.
    private fun apiClientCapability(s: IHTTPSession): Response {
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        parseFormBodyBounded(s, "ep", "extPlayer")
        val extPlayer = (s.parameters["ep"] ?: s.parameters["extPlayer"])
            ?.firstOrNull()?.let { it == "1" || it.equals("true", ignoreCase = true) } ?: false
        val before = if (extPlayer) clientCaps.put(ip, true) else clientCaps.remove(ip)
        // Bump only when the EFFECTIVE capability changed: an absent entry
        // means "not capable", so a first-ever report of "not capable" is a
        // no-op (no event storm when a page without a Quick-Play default
        // boots and dutifully reports its absence).
        if ((before == true) != extPlayer) {
            stateVersion.incrementAndGet()
        }
        return json(JSONObject().put("success", true).put("ep", extPlayer))
    }

    private fun ensureSseTicker() {
        if (sseTickerStarted) return
        synchronized(this) {
            if (sseTickerStarted) return
            sseTickerStarted = true
            sseExec.scheduleAtFixedRate({
                try { sseTick() } catch (e: Exception) { /* never let the ticker die */ }
            }, 250, 250, java.util.concurrent.TimeUnit.MILLISECONDS)
        }
    }

    private fun sseTick() {
        // Phase 2 of 10: the authoritative session sweep runs FIRST and
        // unconditionally — connection lifecycle is server-owned and must
        // not depend on whether any event stream is currently open (a
        // browser on the XHR-poll fallback has a session too). Cheap: a
        // handful of live IPs, one timestamp compare each, compute()-guarded
        // removal only for genuinely terminated sessions.
        sweepSessions()
        // ── Pass-17 (immediate disconnect states): the session sweep's network ──
        // watch latched a LAN loss/change on its last pass — mark every active
        // transfer as awaiting reconnection NOW. (Bytes flowing again clear
        // the presentation on their own; the marking is idempotent.)
        if (networkLostOrChangedLatch) {
            networkLostOrChangedLatch = false
            TransferManager.markAllActiveReconnecting()
        }
        // Phase 10 (first-file glitch, root fix d): re-arm Quick-Share items
        // whose delivery FAILED or was cancelled — the accept must not be a
        // one-way door. Runs even with zero SSE clients so the XHR-poll
        // fallback clients see the re-armed chips on their next poll too.
        // ── FINAL PASS 3 (§7 — removed): the failed-delivery re-arm sweep ──
        // used to un-consume a failed item for its IP and push it back to
        // the HEAD of the browser's queue — the automatic re-send this pass
        // removes. A failed item stays Failed on the sender's Active tab
        // until the user explicitly resends (per-row Resend or the pill's
        // Resend Failed), exactly as specified.
        // ── Pass-23 (deterministic sweep cadence) ─────────────────────────────
        // expireStalePending used to run only opportunistically (row creation,
        // the XHR-poll fallback), so a healthy SSE session whose current
        // dispatch was silently swallowed had NOTHING driving the
        // never-requested sweep — the row sat "Waiting" (and the page's pump
        // slot behind it) indefinitely, which is exactly the "batch stops,
        // most files unfinished" shape. The sweep is a cheap linear scan and
        // idempotent; a fixed 5-second cadence alongside the QS re-arm sweep
        // makes every abandonment path time-bounded (25 s for a dropped
        // dispatch, per its own peer-quiet + oldest-only rules).
        val sweepNowMs = System.currentTimeMillis()
        if (sweepNowMs - lastExpireStaleAtMs > 5_000L) {
            lastExpireStaleAtMs = sweepNowMs
            try { TransferManager.expireStalePending() } catch (e: Exception) { /* never let the ticker die */ }
            // ── GOAT-PASS 6 (§2 — recovery cards track their partial in real ──
            // time): a "Waiting for reconnection" card must disappear the
            // moment its partial is deleted or moved out from under it — not
            // at the next app restart, which was the only validity check
            // before (the cold-start restore). One cheap pass on the same
            // 5-second cadence: prune kept-for-resume rows whose staging file
            // is gone (FAILED rows only — the engine guard keeps live receives
            // immune), and drop registry entries whose data no longer exists
            // so a restart can never resurrect a card for a deleted partial
            // either. resumableRowIds is a handful of entries and each check
            // is one stat() — invisible next to the sweep it rides on.
            try {
                TransferManager.pruneResumableRows { path ->
                    path != null && ResumableReceiveStore.stagingFileFor(path).let { it.exists() && it.length() > 0L }
                }
                for (entry in ResumableReceiveStore.all(context)) {
                    val st = ResumableReceiveStore.stagingFileFor(entry.destPath)
                    if (!st.exists() || st.length() <= 0L) {
                        ResumableReceiveStore.remove(context, entry.destPath)
                    }
                }
            } catch (e: Exception) { /* best-effort housekeeping */ }
        }
        if (sseClients.isEmpty()) return
        val now = System.currentTimeMillis()
        // Phase 8 (§8.3): snapshot the three epochs this tick. anyChange is
        // the whole-tick coarse signal ("could ANY client's payload have
        // changed?"); the per-client comparisons below then decide whether a
        // specific client actually needs a rebuild. When nothing changed
        // anywhere — the steady state of open-but-idle pages — the loop
        // below degenerates to a few volatile reads and the 10s heartbeats.
        val structV = stateVersion.get().toLong()
        val sessV = sessionEpoch.get()
        val tmV = TransferManager.globalEpoch()
        // Phase 10 (light-speed sync): the filesystem epoch. fv is globally
        // visible (any file mutation changes every client's correct Files
        // view). The upload epoch is retired with the "inc" array (Pass-3
        // R3) — upload changes a client can see in ITS payload are already
        // covered by the per-client peer epoch and the session epoch.
        val fv = filesVersion.get()
        val anyChange = structV != lastTickStructV || sessV != lastTickSessionV || tmV != lastTickTmV ||
            fv != lastTickFv
        lastTickStructV = structV
        lastTickSessionV = sessV
        lastTickTmV = tmV
        lastTickFv = fv
        val dead = mutableListOf<SseClient>()
        for (client in sseClients) {
            try {
                // Phase 5 back-pressure gate — available() is non-blocking:
                // bytes the client's HTTP connection has NOT yet drained. A
                // healthy client sits near zero; anything persistently over
                // the limit means its socket stopped draining, so writing to
                // it now would block the one shared SSE thread. Skip first,
                // drop after a sustained stall.
                val backlog = try { client.pipeIn.available() } catch (e: Exception) { -1 }
                if (backlog < 0 || backlog > SSE_BACKPRESSURE_LIMIT_BYTES) {
                    if (backlog < 0) { dead.add(client); continue }
                    if (client.backedUpSinceMs == 0L) client.backedUpSinceMs = now
                    if (now - client.backedUpSinceMs > SSE_STALL_DROP_MS) dead.add(client)
                    continue
                }
                client.backedUpSinceMs = 0L
                // Phase 1 of 10 (SSE liveness — the "connected device
                // vanishes" root cause): a healthy SSE client makes NO
                // periodic HTTP requests — that was the point of SSE — so
                // its connectedIPs last-seen went stale after 30s and it
                // vanished from Connected Devices and the Web clients list,
                // and its next incidental request read as a fresh session
                // (resetting its mode override — see serve()'s companion
                // skip). A draining stream IS connectivity: refreshing
                // last-seen here keeps every 30s-window consumer
                // (apiClients, allConsumed, the session-reset) in agreement
                // with the transport that is actually live.
                connectedIPs[client.ip] = now
                // Phase 8 (§8.3 — the idle SSE rebuild storm): the payload
                // build is now GATED, not unconditional. Old behavior: build
                // clientStateJson() for every client on every 250ms tick and
                // only diff the WRITE — an open-but-idle page cost a full
                // snapshot sort + ~10 map scans + a JSON serialization per
                // client per tick, forever. New behavior, two levels:
                //   1. Whole-tick: if NOTHING changed anywhere since the
                //      previous tick (no structural stateVersion change, no
                //      TransferManager mutation, no session lifecycle event),
                //      every client takes the cheap heartbeat-only path.
                //   2. Per-client: a client only rebuilds when something IT
                //      can see changed — the global structural version, its
                //      session-visible epoch, or its OWN peer-scoped transfer
                //      epoch (its payload contains only its own rows, so
                //      another client's progress must not cost this one a
                //      rebuild). Conservative by design: an unattributable
                //      change bumps the global counter, which sends every
                //      client through the per-client check — at worst one
                //      extra diff, never a missed push.
                val peerV = TransferManager.peerEpoch(client.ip)
                val dirty = client.lastPayload.isEmpty() ||
                    (anyChange && (structV != client.lastStructV ||
                        sessV != client.lastSessionV || peerV != client.lastPeerV ||
                        fv != client.lastFv))
                if (!dirty) {
                    // Heartbeat maintenance only — identical liveness
                    // semantics to the old unchanged-payload branch.
                    if (now - client.lastBeat > 10_000L) {
                        client.lastBeat = now
                        try {
                            client.out.write(": ping\n\n".toByteArray(Charsets.UTF_8))
                            client.out.flush()
                        } catch (e: Exception) { dead.add(client) }
                    }
                    continue
                }
                val payload = clientStateJson(client.ip).toString()
                client.lastStructV = structV
                client.lastSessionV = sessV
                client.lastPeerV = peerV
                client.lastFv = fv
                if (payload != client.lastPayload) {
                    client.lastPayload = payload
                    pushSse(client, payload)
                } else if (now - client.lastBeat > 10_000L) {
                    client.lastBeat = now
                    // SSE comment line — keeps the connection alive through
                    // proxies without delivering anything to onmessage.
                    try {
                        client.out.write(": ping\n\n".toByteArray(Charsets.UTF_8))
                        client.out.flush()
                    } catch (e: Exception) { dead.add(client) }
                }
            } catch (e: Exception) { dead.add(client) }
        }
        if (dead.isNotEmpty()) {
            for (c in dead) removeSseClient(c)
        }
    }

    private fun pushSse(client: SseClient, payload: String) {
        try {
            client.out.write(("data: " + payload + "\n\n").toByteArray(Charsets.UTF_8))
            client.out.flush()
        } catch (e: Exception) {
            removeSseClient(client)
        }
    }

    // Phase 10 (first-file glitch, root fix d): a Quick-Share item whose
    // delivery for some client FAILED or was cancelled is re-armed — the
    // consuming mark comes off and the chip returns to that client's queue
    // (stateVersion bump -> SSE push -> chip reappears) so "Accept" is
    // never a one-way door on the FIRST file of a session either. A host
    // cancel tombstone (wasCancelledForQuickShare) suppresses the re-arm:
    // the host explicitly killed this delivery and the retry would bounce.
    // ── FINAL PASS 3 (§7): sweepFailedQuickShare() is REMOVED. The sweep ──
    // automatically re-offered a failed Quick-Share delivery to the browser
    // receiver (un-consuming its IP, re-inserting the chip at the queue's
    // HEAD), which read as the item re-appearing as Waiting in the
    // receiver's queue card instead of staying Failed on the sender. The
    // required behavior is the opposite: a failed item stays Failed until
    // the user explicitly resends (per-row Resend or the pill's Resend
    // Failed — both route through retryQuickShareForIp, the ONE deliberate
    // re-offer path, untouched). qsRearmedKeys stays declared: the fresh-
    // send budget resets below still reference it, and a zero-byte failed
    // row simply re-offers only through the explicit actions now.
    private val qsRearmedKeys = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    private fun removeSseClient(client: SseClient) {
        sseClients.remove(client)
        try { client.out.close() } catch (ignored: IOException) {}
    }

    /** True while [ip] has at least one SSE client that is still draining
     *  its stream (i.e. genuinely connected — see the back-pressure gate in
     *  sseTick). Used by serve()'s session-reset logic to keep a pure-SSE
     *  client's session-scoped state alive, and by kickDevice to find the
     *  streams a kick must actually close. */
    private fun hasLiveSse(ip: String): Boolean =
        sseClients.any { it.ip == ip }

    // ── Pass-17 (ACTIVE liveness challenge — the verdict prober) ────────────
    // probePeerAlive is what TransferManager.browserLivenessProber resolves
    // to: a synchronous "is this peer's PAGE genuinely, provably alive RIGHT
    // NOW?" — used exclusively by the download-abort verdict to tell a
    // deliberate browser-DM cancel (page alive, file connection dead) from a
    // genuine network drop (everything dead). The passive pass-15 check
    // (hasLiveSse) could NOT tell them apart: a socket whose client vanished
    // keeps accepting writes into its kernel send buffer, so the stream reads
    // "attached" until the 30-second back-pressure stall-drop fires. Here
    // the page must positively ANSWER within the window:
    //   1. Fast negative — no SSE stream AND no /api/qstate poll from this ip
    //      in the last ~1.2 s: the page is not talking to us at all → dead.
    //   2. Challenge — mint a nonce for this ip; it rides the NEXT state
    //      payload BOTH ways (clientStateJson's "pn" field: pushed on the
    //      SSE stream immediately, and echoed to the 700ms XHR-poll
    //      fallback). The page's JS POSTs /api/alive?n=<nonce> once per
    //      nonce. A page whose network is down physically cannot.
    //   3. Verdict — any /api/alive receipt within ~1.6 s → alive. Otherwise
    //      → dead. Runs on the engine's auto-removal timer thread, so the
    //      blocking wait costs the caller (the verdict task) nothing else.
    private val livenessAcks = ConcurrentHashMap<String, Long>()   // nonce -> received-at
    private val livenessAckAt = ConcurrentHashMap<String, Long>()   // ip -> last /api/alive arrival
    @Volatile private var livenessNonceFor: Pair<String, String>? = null   // ip -> nonce
    private val LIVENESS_WAIT_MS = 1600L

    /** Pass-17: the ACTIVE challenge described above. Called on a worker
     *  (timer) thread; blocks up to ~1.6 s waiting for the page's answer. */
    private fun probePeerAlive(ip: String): Boolean {
        if (ip.isEmpty() || ip == "unknown") return false
        val now = System.currentTimeMillis()
        val hasSse = hasLiveSse(ip)
        val lastPoll = connectedIPs[ip] ?: 0L
        if (!hasSse && now - lastPoll > 1200L) return false   // page silent on every channel
        // Challenge: mint the nonce, push it at the page over SSE NOW (a
        // forced dirty push — don't wait for the next payload-driven tick),
        // and leave it set for the poll path to echo until answered.
        val nonce = java.util.UUID.randomUUID().toString().replace("-", "").take(16)
        livenessNonceFor = ip to nonce
        livenessAcks.remove(nonce)
        // ── Concurrent probes of the SAME page: two browser downloads aborted
        // in one instant (a "cancel all" in the download manager) run two
        // verdicts against one ip, and the single-slot nonce above carries only
        // the latest. The ip-level timestamp below is the resolution: any
        // /api/alive arrival from THIS ip during the window proves the page
        // alive for BOTH probes, so the earlier one can never time out against
        // a page that is demonstrably answering.
        val probeStartMs = System.currentTimeMillis()
        livenessAckAt.remove(ip)
        if (hasSse) {
            try {
                // ── Pass-23 (the probe must never block) ──────────────────────────
                // The nonce push used to write blindly into the client's pipe —
                // but a client whose HTTP connection stopped draining (exactly
                // the page this probe is asking about) has a FULL pipe, and
                // PipedOutputStream.write BLOCKS until its reader drains it:
                // the probe (and the stall-watchdog / verdict thread it runs
                // on) could stall for the full 30-second SSE stall-drop
                // instead of answering in ~1.6 s, which was exactly the
                // window in which a sender's chip kept reading "Sending…"
                // after the receiver's network died. A backed-up client
                // cannot answer the challenge anyway; skip the push for it
                // and let the bounded wait decide.
                val targets = sseClients.toList().filter { it.ip == ip }
                var backedUp = false
                for (c in targets) {
                    val backlog = try { c.pipeIn.available() } catch (e: Exception) { -1 }
                    if (backlog < 0 || backlog > SSE_BACKPRESSURE_LIMIT_BYTES) backedUp = true
                }
                if (!backedUp) {
                    val payload = clientStateJson(ip).toString()
                    for (c in targets) {
                        c.lastPayload = payload
                        c.lastStructV = stateVersion.get().toLong()
                        c.lastSessionV = sessionEpoch.get()
                        c.lastPeerV = TransferManager.peerEpoch(ip)
                        c.lastFv = filesVersion.get()
                        pushSse(c, payload)
                    }
                }
            } catch (e: Exception) { /* a failed push only means one less answer path */ }
        }
        val deadline = System.currentTimeMillis() + LIVENESS_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (livenessAcks.remove(nonce) != null ||
                (livenessAckAt[ip] ?: 0L) > probeStartMs) {
                livenessNonceFor = null
                return true
            }
            try { Thread.sleep(60L) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        livenessNonceFor = null
        return false
    }

    /** Closes every live SSE stream for [ip] (kick path): closing the pipe
     *  makes NanoHTTPD's chunked-response reader see end-of-input and tear
     *  the HTTP connection down, and the browser's EventSource fires its own
     *  reconnect — which serve() then rejects at the kickedIPs gate. */
    private fun closeSseFor(ip: String) {
        for (c in sseClients.toList()) if (c.ip == ip) removeSseClient(c)
    }

    // Phase 2 of 10 — lifecycle cleanup on stop/restart. NanoHTTPD's stop()
    // tears down its listener socket and worker threads; THIS server also
    // owns the SSE ticker thread and every open event-stream pipe. Without
    // this override, each server restart (settings change, stop/start)
    // leaked the old instance's scheduler thread — it kept ticking at 250ms
    // against dead maps forever — and left every open event stream's pipe
    // unclosed until a write failure noticed, which on a stopped server
    // never comes. Closing the pipes here is also what makes the browser's
    // EventSource error fire immediately (instead of after its own read
    // timeout), so an in-place host restart reconnects clients as fast as
    // the browser's own retry logic allows.
    override fun stop() {
        try { sseExec.shutdownNow() } catch (ignored: Exception) {}
        for (c in sseClients.toList()) removeSseClient(c)
        super.stop()
    }

    /** Pass-17: records the page's answer to the liveness challenge — see
     *  probePeerAlive. Idempotent, fire-and-forget by design. */
    private fun apiAlive(s: IHTTPSession): Response {
        val n = s.parameters["n"]?.firstOrNull()
        val now = System.currentTimeMillis()
        if (!n.isNullOrEmpty()) livenessAcks[n] = now
        try { s.remoteIpAddress?.let { livenessAckAt[it] = now } } catch (e: Exception) { }
        return json(JSONObject().put("ok", true))
    }

    /** Pass-17: the native-peer liveness probe target — one tiny JSON body,
    *  zero state, answerable by a process that has just been asked "is
    *  that device's app still alive?" (see NativeDiscovery's probe-based
    *  fast expiry). */
    private fun apiPing(): Response = json(JSONObject().put("ok", true))

    // ── Web-client transfer cancel (Phase 4; tid identity in Phase 1 of 10) ────
    // The browser owns its download connections now, so a cancel from the
    // page can only be honored by the host. Phase 1: the page now sends the
    // SERVER TRANSFER ID ("tid=") it got in the very same xfers entry it
    // rendered the row from — resolved through findLiveDownloadByIdAndPeer's
    // ownership check (this client's own live DOWNLOAD only), so a cancel
    // can never tear down another client's transfer or a different
    // same-named transfer of the same client. The old name-based path
    // ("n=") remains as a fallback for a WebUI page still open from a
    // previous server build across an in-place host update — same
    // first-match semantics it always had, never used by the current page
    // when it has a tid.
    private fun apiClientTransferCancel(s: IHTTPSession): Response {
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        val tidParam = s.parameters["tid"]?.firstOrNull()
        if (tidParam != null) {
            val tid = TransferManager.findLiveDownloadByIdAndPeer(tidParam, ip)
            if (tid != null) {
                TransferManager.requestCancel(tid)
                return json(JSONObject().put("success", true))
            }
            // Final Release (§12, stuck "Cancelling…"): not live — but an
            // ALREADY-TERMINAL transfer (host cancelled it a beat ago, or it
            // completed/cancelled between snapshots) must answer with the
            // real verdict, not success:false. The client resolves its
            // transitional "Cancelling…" from THIS response immediately; a
            // bare false left it hanging until the 8s fallback.
            val verdict = TransferManager.terminalStateFor(tidParam)
            if (verdict != null) {
                return json(JSONObject().put("success", true)
                    .put("state", verdict.name.lowercase())
                    .put("already", true))
            }
            return json(JSONObject().put("success", false))
        }
        val name = s.parameters["n"]?.firstOrNull() ?: return jsonErr("No tid or name")
        val tid = TransferManager.findLiveDownloadId(name, ip)
        if (tid != null) {
            TransferManager.requestCancel(tid)
            return json(JSONObject().put("success", true))
        }
        // Name-path twin of the same honest-verdict ack.
        val termName = TransferManager.recentTerminalDownloadsFor(ip)
            .firstOrNull { it.second == name }
        if (termName != null) {
            return json(JSONObject().put("success", true)
                .put("state", termName.third.name.lowercase())
                .put("already", true))
        }
        return json(JSONObject().put("success", false))
    }

    // ── Phase 7 of 10: handoff heartbeat sync (jailbroken-Safari pump) ───────
    // GET /api/client/handoff-sync?watch=<fileName>
    //
    // The legacy receive pump calls this on a short interval while one of
    // its items is in flight. One cheap call answers everything the pump
    // needs, from authoritative state only — never a DOM guess, never a
    // timing guess:
    //   beat  — the watched item's own row: transfer id (exact identity to
    //           bind to, immune to same-name collisions), state, and the
    //           re-await indication. While that row is PENDING-re-awaiting,
    //           this very call EXTENDS the server's re-await deadline: the
    //           Safari-Plus save sheet (instantDownloads defaults OFF in the
    //           tweak's own source) is answered by a human in their own
    //           time, and a page that is still actively shepherding the
    //           handoff is the one party that knows the delivery is still
    //           on track. Beats stop → the window lapses on its own → the
    //           sweep finalizes honestly. No sheet outlives the patience the
    //           page itself declared.
    //   rows  — this client's live PENDING/ACTIVE downloads plus the
    //           recently-terminal memory: the pump prunes queued items whose
    //   rows the host cancelled mid-batch (the 400ms cancel flash is
    //           invisible to the ~1s state channel — without this list a
    //           host "cancel all" was silently undone minutes later when
    //           the serial pump finally fired those items).
    private fun apiClientHandoffSync(s: IHTTPSession): Response {
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        val watch = s.parameters["watch"]?.firstOrNull()

        val live = TransferManager.snapshot().filter {
            it.direction == TransferManager.Direction.DOWNLOAD && it.peerIp == ip
        }
        fun statusStr(st: TransferManager.Status): String = when (st) {
            TransferManager.Status.ACTIVE -> "active"
            TransferManager.Status.PENDING -> "pending"
            TransferManager.Status.COMPLETED -> "completed"
            TransferManager.Status.FAILED -> "failed"
            else -> "cancelled"
        }
        val rows = JSONArray()
        for (t in live) {
            val row = JSONObject().put("i", t.id).put("n", t.fileName).put("s", statusStr(t.status))
            // ── PASS 27: the re-await keeps rows ACTIVE — the honest
            // handoff indication rides any live row in its window, not a
            // demoted PENDING one (see the demotion-free re-await).
            if (t.reAwaitUntilMs > 0L) row.put("ho", 1)
            // ── GOAT-PASS 3 (§2/§6): the beat now also reports the row's
            // deliberate pause — the legacy serial receive pump must FREE ITS
            // TURN when the host pauses the item it is shepherding (the batch
            // behind it must keep flowing, exactly like the modern QS pump
            // already does), and must NEVER run its 45s give-up clock against
            // an item that is merely paused.
            if (t.pauseRequested) row.put("pd", 1)
            rows.put(row)
        }
        // Terminal memory: verdicts auto-removal already swept out of the
        // live map — the answers the state channel physically could not
        // deliver (see TransferManager's TERMINAL_MEMORY notes).
        for ((id, fileName, st) in TransferManager.recentTerminalDownloadsFor(ip)) {
            if (live.any { it.id == id }) continue
            rows.put(JSONObject().put("i", id).put("n", fileName).put("s", statusStr(st)))
        }

        val beat: JSONObject
        if (watch != null) {
            // Bind to the row the pump can still ACT on — a re-awaiting
            // entry first (ACTIVE since PASS 27's demotion-free re-await,
            // the one the beat extends), then any other PENDING, then
            // ACTIVE. A terminal-only match is deliberately NOT the watch
            // target: a just-finished same-named sibling must not hijack
            // the binding; the fresh row lands within a beat or two and
            // "none" keeps the pump waiting honestly in the meantime.
            val candidates = live.filter { it.fileName == watch }
            val target = candidates.firstOrNull { it.reAwaitUntilMs > 0L }
                ?: candidates.firstOrNull { it.status == TransferManager.Status.PENDING }
                ?: candidates.firstOrNull { it.status == TransferManager.Status.ACTIVE }
            if (target != null) {
                TransferManager.extendHandoffWindow(target.id)
                beat = JSONObject().put("i", target.id).put("n", target.fileName)
                    .put("s", statusStr(target.status))
                if (target.reAwaitUntilMs > 0L) beat.put("ho", 1)
                // GOAT-PASS 3 (§2/§6): same pause flag on the watched beat.
                if (target.pauseRequested) beat.put("pd", 1)
            } else {
                beat = JSONObject().put("s", "none")
            }
        } else {
            beat = JSONObject().put("s", "none")
        }
        return json(JSONObject().put("ok", true).put("beat", beat).put("rows", rows))
    }

    // ── Server-side download concurrency gate (Phase 4, v3 §5 item 5) ────────
    // The honest enforcement point for the host's concurrency setting: with
    // concurrency=1, exactly one tracked download's bytes move at a time;
    // every further request WAITS HERE, server-side, before its headers are
    // even sent — the browser shows no download prompt for it yet, the WebUI
    // drawer shows it as Queued, and it proceeds the moment a slot frees.
    //
    // Phase 1 of 10: the gate itself (semaphore, holder tracking, watchdog
    // force-release, cancel-abort, reset-on-reconfigure) moved INTO
    // TransferManager next to the receiving gate — see the "Unified
    // concurrency-slot architecture" doc there for the three failure modes
    // the old bare-counter implementation here had (permanent slot leak on a
    // wedged socket, no cancel awareness while queued, no reset on server
    // restart). These wrappers stay only as the call-site shape every
    // endpoint already uses, plus the wait-KEY resolution that picks the
    // PENDING transfer id whenever the client pre-registered the download
    // (so a host Cancel of that exact entry aborts the queued request
    // within one poll tick).
    private fun downloadWaitKey(preferredPendingId: String?): String =
        preferredPendingId ?: "dlq:${java.util.UUID.randomUUID()}"

    private fun acquireDownloadSlotFor(waitKey: String): Boolean =
        TransferManager.acquireDownloadSlot(waitKey)

    // ── WebUI customization (host-controlled) ─────────────────────────────────
    // Single combined payload: sort default, download-manager behavior, home
    // layout and pinned folders for whichever OS profile this request's
    // User-Agent matches, plus the (global, non-OS-specific) custom Music
    // folder path. The WebUI fetches this once at boot and applies it before
    // its first render — see fetchHostConfig() in index.html.
    private fun apiWebUiConfig(s: IHTTPSession): Response {
        val ua = s.headers["user-agent"]
        return json(WebUiSettingsManager.buildConfigJson(context, ua))
    }

    // ── Storage / Stats / Recent ──────────────────────────────────────────────

    private fun apiStorage(): Response {
        val r = Environment.getExternalStorageDirectory()
        val total = r.totalSpace; val free = r.freeSpace; val used = total - free
        return json(JSONObject().apply {
            put("total", total); put("used", used); put("free", free)
            put("usedPercent", if (total > 0) (used * 100.0 / total).toInt() else 0)
        })
    }

    // ── Phase 8 (§8.3): short-TTL response caches for the heavyweight
    // on-demand listings ─────────────────────────────────────────────────
    // apiStats (MediaStore counts + two walkTopDowns + PackageManager),
    // apiInstalledApps
    // (full PackageManager enumeration + label sort) each used to run their
    // ENTIRE scan on every single request — so one client's Home refresh
    // while transfers were in flight hammered the filesystem on the same
    // phone that was streaming those transfers. A multi-client burst
    // (page load, reconnect storm) multiplied it. These TTLs coalesce that
    // burst into one scan per window: 1.5s for recent (the only one whose
    // content can change mid-session — files arriving), 2s for stats, 5s
    // for the app list (static while the server runs). Byte-identical
    // response bodies, same no-store discipline as every other endpoint.
    private class CachedJson(val body: String, val atMs: Long, val fv: Long)
    private val responseCache = ConcurrentHashMap<String, CachedJson>()
    private fun cachedJson(key: String, ttlMs: Long, builder: () -> JSONObject): Response {
        val now = System.currentTimeMillis()
        val fv = filesVersion.get()
        val hit = responseCache[key]
        // Phase 10: a file mutation invalidates the cache regardless of TTL
        // — stats/recent reflect the filesystem, and the client's fv-triggered
        // refetch arrives BEFORE the TTL window would have expired. Without
        // this, the "refresh itself" flow would serve stale cached bytes.
        if (hit != null && hit.fv == fv && now - hit.atMs < ttlMs) {
            return noCache(newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", hit.body))
        }
        val body = builder().toString()
        responseCache[key] = CachedJson(body, now, fv)
        return noCache(newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", body))
    }

    private fun apiStats(): Response = cachedJson("stats", 2000L) { JSONObject().apply {
        fun cnt(u: android.net.Uri, selection: String? = null, args: Array<String>? = null) = try {
            context.contentResolver.query(u, arrayOf(MediaStore.MediaColumns._ID),
                selection, args, null)?.use { it.count } ?: 0
        } catch (e: Exception) { 0 }
        val docExts = setOf("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","csv","md","html","json")
        var docCount = 0
        for (root in listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        )) { try { docCount += root.walkTopDown().filter { it.isFile && it.extension.lowercase() in docExts }.take(500).count() } catch (e: Exception) { } }
        val appCount = try {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                context.packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            else @Suppress("DEPRECATION") context.packageManager.getInstalledApplications(0)
            list.count { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
        } catch (e: Exception) { 0 }
        // Custom Music folders (host-configured, one or more) — when set, the
        // Music tile count must match exactly what apiMusic() lists, or the
        // home screen and the Music tab would show two different counts for
        // the same category. Multiple folders are OR-combined.
        val musicPaths = WebUiSettingsManager.getMusicFolderPaths(context)
        val musicCount = if (musicPaths.isNotEmpty()) {
            val selection = musicPaths.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ?" }
            val args = musicPaths.map { "$it%" }.toTypedArray()
            cnt(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, selection, args)
        } else cnt(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        put("images", cnt(MediaStore.Images.Media.EXTERNAL_CONTENT_URI))
        put("videos", cnt(MediaStore.Video.Media.EXTERNAL_CONTENT_URI))
        put("music",  musicCount)
        put("docs",   docCount); put("apps", appCount)
    } }

    // Final Release (§18): the Recent system was removed from the web
    // client outright, and this server surface existed ONLY to feed it —
    // apiRecent (up to 8 walkTopDown directory walks per call) plus the
    // isRecentArtifact filter are deleted with it. The native app's own
    // Recent Files engine (media/ThumbnailShared + the Received tab) never
    // went through this route and is untouched.

    // ── MediaStore ────────────────────────────────────────────────────────────

    // ── Pass-15 (web tabs load quickly with large libraries): the three ──
    // MediaStore listings ride the same filesVersion-gated TTL cache the
    // stats/apps endpoints already use (cachedJson). A 20k-photo library's
    // projection query re-ran on EVERY request — every tab switch back,
    // every SSE-driven re-render — adding hundreds of milliseconds of
    // MediaStore work on the host each time; with the cache a switch back
    // inside 2s is instant, and correctness is untouched: any file mutation
    // bumps filesVersion, which invalidates all three caches immediately
    // (see cachedJson), so a fresh transfer still lands in the listings on
    // the very next tick.
    private fun apiImages(): Response = cachedJson("media-images", 2000L) {
        val proj = arrayOf(MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATA, MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.Images.Media.MIME_TYPE)
        val c = context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
            null, null, "${MediaStore.Images.Media.DATE_MODIFIED} DESC")
            ?: return@cachedJson JSONObject().put("items", JSONArray()).put("count", 0)
        val arr = JSONArray()
        c.use { while (it.moveToNext()) {
            val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)) ?: ""
            arr.put(JSONObject().apply {
                put("name", name); put("path", it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)) ?: "")
                put("size", it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)))
                put("modified", it.getLong(it.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)) * 1000L)
                put("bucket", it.getString(it.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)) ?: "Other")
                put("ext", name.substringAfterLast('.').lowercase())
            })
        }}
        // ── Pass-17 (instant media on the iPad 4): pre-warm the first page of ──
        // grid tiles (size 200, the grid's own request size) + the first album
        // strip cover (80) the moment the list is served — see preWarmThumb.
        if (arr.length() > 0) {
            for (i in 0 until minOf(24, arr.length())) {
                preWarmThumb(arr.optJSONObject(i)?.optString("path") ?: continue, 200)
            }
            preWarmThumb(arr.optJSONObject(0)?.optString("path") ?: "", 80)
        }
        JSONObject().put("items", arr).put("count", arr.length())
    }

    private fun apiVideos(): Response = cachedJson("media-videos", 2000L) {
        val proj = arrayOf(MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATA, MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DURATION, MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
        val c = context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
            null, null, "${MediaStore.Video.Media.DATE_MODIFIED} DESC")
            ?: return@cachedJson JSONObject().put("items", JSONArray()).put("count", 0)
        val arr = JSONArray()
        c.use { while (it.moveToNext()) {
            val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)) ?: ""
            arr.put(JSONObject().apply {
                put("name", name); put("path", it.getString(it.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)) ?: "")
                put("size", it.getLong(it.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)))
                put("modified", it.getLong(it.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)) * 1000L)
                put("duration", it.getLong(it.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)))
                put("bucket", it.getString(it.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)) ?: "Other")
                put("ext", name.substringAfterLast('.').lowercase())
            })
        }}
        // ── Pass-17 (instant media on the iPad 4): pre-warm the first page of ──
        // row thumbs (120, the row's own request size) + the first album strip
        // cover (80) the moment the list is served — see preWarmThumb. Video
        // thumbs are the most expensive decode on the host
        // (MediaMetadataRetriever), so warming them matters most here.
        if (arr.length() > 0) {
            for (i in 0 until minOf(20, arr.length())) {
                preWarmThumb(arr.optJSONObject(i)?.optString("path") ?: continue, 120)
            }
            preWarmThumb(arr.optJSONObject(0)?.optString("path") ?: "", 80)
        }
        JSONObject().put("items", arr).put("count", arr.length())
    }

    // Pass-15: music rides the same filesVersion-gated TTL cache as images
    // and videos (see their note above).
    private fun apiMusic(): Response = cachedJson("media-music", 2000L) {
        val proj = arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.TITLE)
        // Custom Music folders (host-configured, one or more) — when set,
        // restrict the Music tab to just those folders (and their
        // subfolders) instead of every audio file MediaStore knows about on
        // the whole device. Multiple folders are OR-combined.
        val musicPaths = WebUiSettingsManager.getMusicFolderPaths(context)
        val selection: String
        val selectionArgs: Array<String>?
        if (musicPaths.isNotEmpty()) {
            val pathClause = musicPaths.joinToString(" OR ") { "${MediaStore.Audio.Media.DATA} LIKE ?" }
            selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ($pathClause)"
            selectionArgs = musicPaths.map { "$it%" }.toTypedArray()
        } else {
            selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            selectionArgs = null
        }
        val c = context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, proj,
            selection, selectionArgs,
            "${MediaStore.Audio.Media.DATE_MODIFIED} DESC")
            ?: return@cachedJson JSONObject().put("items", JSONArray()).put("count", 0)
        val arr = JSONArray()
        c.use { while (it.moveToNext()) {
            val name = it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)) ?: ""
            arr.put(JSONObject().apply {
                put("name", name); put("path", it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)) ?: "")
                put("size", it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)))
                put("modified", it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)) * 1000L)
                put("duration", it.getLong(it.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)))
                put("artist", it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)) ?: "Unknown")
                put("album",  it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM))  ?: "")
                put("title",  it.getString(it.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE))  ?: "")
                put("ext", name.substringAfterLast('.').lowercase())
            })
        }}
        JSONObject().put("items", arr).put("count", arr.length())
    }

    private fun apiScan(vararg roots: String, filter: (File) -> Boolean): Response =
        // ── PASS 22 (§2): the docs listing joins the cachedJson rails ──────
        // (5s TTL + filesVersion invalidation, the same discipline stats and
        // apps already have). It was the ONLY tab list with NO cache — every
        // Documents/Downloads tab open re-walked both trees inline.
        cachedJson("media-docs", 5000L) {
            scanRootsToJson(roots, filter)
        }

    /** PASS 22 (§2): the walk itself, shared by the endpoint and the boot
     *  prewarm so both produce byte-identical payloads. */
    private fun scanRootsToJson(roots: Array<out String>, filter: (File) -> Boolean): JSONObject {
        val arr = JSONArray()
        for (root in roots) try {
            File(root).walkTopDown().filter { it.isFile && filter(it) }
                .sortedByDescending { it.lastModified() }.take(500).forEach { f ->
                arr.put(JSONObject().apply {
                    put("name", f.name); put("path", f.absolutePath)
                    put("size", f.length()); put("modified", f.lastModified())
                    put("ext", f.extension.lowercase())
                })
            }
        } catch (e: Exception) { }
        return JSONObject().put("items", arr).put("count", arr.length())
    }

    // ── Apps ──────────────────────────────────────────────────────────────────

    private fun apiInstalledApps(): Response = cachedJson("apps", 5000L) {
        val pm = context.packageManager
        val apps: List<ApplicationInfo> = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            else @Suppress("DEPRECATION") pm.getInstalledApplications(0)
        } catch (e: Exception) { emptyList() }
        val arr = JSONArray()
        for (info in apps.sortedBy { pm.getApplicationLabel(it).toString().lowercase() }) {
            if ((info.flags and ApplicationInfo.FLAG_SYSTEM) != 0) continue
            try {
                val pi    = pm.getPackageInfo(info.packageName, 0)
                val label = pm.getApplicationLabel(info).toString()
                arr.put(JSONObject().apply {
                    put("name",     label)
                    put("pkg",      info.packageName)
                    put("apk",      info.sourceDir)
                    put("size",     File(info.sourceDir).length())
                    put("version",  pi.versionName ?: "?")
                    // lastUpdateTime = time the package was last installed/updated.
                    // Falls back to firstInstallTime if somehow zero, then APK mtime.
                    val ts = if (pi.lastUpdateTime > 0L) pi.lastUpdateTime
                             else if (pi.firstInstallTime > 0L) pi.firstInstallTime
                             else File(info.sourceDir).lastModified()
                    put("modified", ts)
                })
            } catch (e: Exception) { }
        }
        JSONObject().put("items", arr).put("count", arr.length())
    }

    private fun apiDownloadApk(s: IHTTPSession): Response {
        val pkg = s.parameters["pkg"]?.firstOrNull() ?: return jsonErr("No pkg")
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                context.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0L))
            else @Suppress("DEPRECATION") context.packageManager.getApplicationInfo(pkg, 0)
            val apk  = File(info.sourceDir)
            val name = context.packageManager.getApplicationLabel(info).toString().replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            val ip   = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
            // Phase 4 download gate (same rules as apiDownload — see there).
            // Phase 1: wait-keyed, holder-guarded slot; the PENDING entry's
            // own id whenever /api/download/register pre-registered this APK.
            val gateKey: String? =
                if (TransferManager.hasActiveDownloadFor("$name.apk", ip)) null
                else downloadWaitKey(TransferManager.findPendingPlainDownloadId("$name.apk", ip))
            if (gateKey != null && !acquireDownloadSlotFor(gateKey)) return jsonErr("Cancelled by host")
            // ── Pass-17 (host-cancel finality): the post-gate re-check every ──
            // download surface now carries — see apiQSDl's identical block for
            // the full reasoning. Keyed by the resolved APK name.
            if (gateKey != null && TransferManager.wasCancelledForPlainDownload("$name.apk", ip)) {
                TransferManager.releaseDownloadSlot(gateKey)
                return jsonErr("Cancelled by host")
            }
            val tid  = TransferManager.claimOrStartActivePlainDownload("$name.apk", apk.length(),
                ip, getDeviceDisplayName(ip), apk.absolutePath, rangeStart = rangeStartOf(s))
            if (gateKey != null) TransferManager.moveDownloadSlot(gateKey, tid)
            // Enhanced-Safari handoff — same two parameters apiDownload
            // documents in full; APKs are already a non-displayable type so
            // octet only matters for MIME consistency, but the handoff
            // semantics (connection-aware abort) apply identically.
            val handoff = s.parameters["handoff"]?.firstOrNull() == "1"
            if (handoff) TransferManager.markHandoffEligible(tid)
            val settleMs = handoffSettleMs(handoff, tid, apk.length())
            val connToken = handoffConnCounter.incrementAndGet()
            if (handoff && (TransferManager.wasReAwaited(tid) || TransferManager.liveConnectionCount(tid) > 1))
                TransferManager.markHandoffSuperseded(tid, connToken)
            // Phase 5: APK downloads are routinely the largest single files
            // this app serves — they now get the SAME Range/resume +
            // ETag/If-Range identity treatment as every other file download
            // (previously "Accept-Ranges: none", so a browser that dropped
            // an APK download mid-flight could only restart from zero, and
            // a changed APK could in principle be range-stitched corrupt).
            return serveFileDownload(apk, "application/vnd.android.package-archive", s,
                tid, gateKey != null, "$name.apk", settleMs, connToken)
        } catch (e: Exception) { jsonErr("App not found: $pkg") }
    }

    // ── File ops ──────────────────────────────────────────────────────────────

    private fun apiDelete(s: IHTTPSession): Response {
        val path = s.parameters["path"]?.firstOrNull() ?: return jsonErr("No path")
        val f    = File(path)
        val ok   = if (f.isDirectory) f.deleteRecursively() else f.delete()
        if (ok) { MediaScannerConnection.scanFile(context, arrayOf(path), null, null); bumpFilesVersion(path) }
        return json(JSONObject().put("success", ok))
    }

    private fun apiRename(s: IHTTPSession): Response {
        val path    = s.parameters["path"]?.firstOrNull() ?: return jsonErr("No path")
        val newName = s.parameters["name"]?.firstOrNull() ?: return jsonErr("No name")
        val f       = File(path)
        val dest    = File(f.parent ?: return jsonErr("No parent"), sanitize(newName))
        val ok      = f.renameTo(dest)
        if (ok) { MediaScannerConnection.scanFile(context, arrayOf(dest.absolutePath), null, null); bumpFilesVersion(dest.absolutePath) }
        return json(JSONObject().put("success", ok).put("newPath", dest.absolutePath))
    }

    // ── Quick Share ───────────────────────────────────────────────────────────

    fun addToQuickShare(filePath: String?, contentUri: String?, name: String, size: Long,
                        mime: String = "application/octet-stream", targetIps: Set<String>? = null) {
        val safeName = uniqueQueueName(name)
        // ── Pass-22 (#4 — repeated sends, re-arm half): a genuinely fresh ──────
        // queue item under this name resets the once-only re-arm budget for
        // every peer. The old guard was keyed "(name, ip) re-arms at most
        // ONCE per process" — correct for one send, but a deliberate RE-send
        // of the same file inherited the exhausted budget of the send before
        // it, so the new item's first zero-byte failure never re-armed its
        // chip: the file sat failed with no self-heal and no chip.
        qsRearmedKeys.removeAll { it.startsWith("$safeName|") }
        quickShareQueue.add(QSItem(safeName, filePath, contentUri, size, mime, targetIps = targetIps))
        preRegisterQuickShareSends(safeName, filePath, size, targetIps)
        stateVersion.incrementAndGet()
    }

    // Pass-3 (R14 — web queuing matches native-to-native): a DELIBERATE send
    // to specific browser client(s) (targetIps non-null) pre-registers a
    // PENDING Quick-Share transfer per target the moment it is queued — the
    // exact same discipline NativePushClient applies to a native send's
    // manifest (startPendingSend per file, before any byte moves). The
    // sender's Active tab therefore shows the items as "Waiting" from the
    // instant Send is tapped (not only once the receiver accepts), and the
    // Send tab's queue card hides them (updatePreviewCard filters items
    // whose every target already has a live row) — one place at a time, the
    // same lifecycle a native-to-native send has always had. The accept path
    // then promotes the SAME entry (claimOrStartPending is idempotent against
    // a live entry) rather than minting a second row. A broadcast share
    // (targetIps == null, the Quick-Share tiles / auto-send path) keeps the
    // classic behavior: it becomes a per-client transfer only when a client
    // accepts it.
    private fun preRegisterQuickShareSends(safeName: String, filePath: String?, size: Long,
                                            targetIps: Set<String>?, zipStream: Boolean = false) {
        if (targetIps.isNullOrEmpty()) return
        for (ip in targetIps) {
            TransferManager.claimOrStartPending(safeName, size.takeIf { it > 0 } ?: -1L,
                ip, getDeviceDisplayName(ip), qsItemName = safeName, filePath = filePath,
                // ── FINAL PASS 4 (§2): a ZIP-BATCH quick-share send marks its
                // pre-registered rows zipStream, exactly as the native leg's
                // startPendingSend already does. Without the marker the row
                // was indistinguishable from an ordinary unknown-size file:
                // its History card lost the no-Pause/“streamed archive”
                // presentation AND — the reported gap — the completed row's
                // isZipStream-keyed selection eligibility (isRangeSelectableIn)
                // fell back to the filePath!=null gate, which a streamed
                // archive row never satisfies (nothing of the archive ever
                // exists on the sender's disk), so Hold & Slide and Range
                // selection skipped it while a plain tap still worked — the
                // same asymmetry FINAL PASS 3 fixed for the native leg only.
                // The browser leg now carries the same marker from the
                // moment its Waiting row is minted.
                zipStream = zipStream)
        }
    }

    // Registers a folder for INSTANT Quick Share — no disk pre-zip, no wait state.
    // GOAT v2 (item 2 — album send): folderPath and folderUriString are both
    // nullable now, same dual-representation shape PreviewItem/addToQuickShare
    // already use for a plain file (path OR uri) — a caller passes whichever
    // one it actually has. Exactly one is expected non-null in practice; if a
    // future caller somehow has neither, the queued item simply falls through
    // every branch in the download handler below to its existing "Folder no
    // longer accessible" / "File missing" failure path, not a crash.
    fun addFolderToQuickShare(folderPath: String?, folderUriString: String?, name: String, targetIps: Set<String>? = null,
                              // ── Pass-33 (#1): the album manifest, when this folder is a logical ──
                              // one (see QSItem.childManifest). Null for an ordinary physical
                              // folder — walked live exactly as before.
                              childManifest: List<com.lumidrop.x.transfer.PreviewItem.FolderChild>? = null,
                              // ── Pass-36 Part 2 (#4): hidden-descendant eligibility for the live ──
                              // walk (see QSItem.includeHiddenChildren) — threaded from the
                              // staged item by every enqueue site.
                              includeHiddenChildren: Boolean = false,
                              // ── GOAT-PASS 14 (Phase 1, §2.1): the staged item's queue-time ──
                              // child removals (PreviewItem.excludedChildPaths), finally carried
                              // through to the browser leg's ZIP stream — previously dropped
                              // here, so a folder the sender pruned in the Queue still arrived
                              // with every original child inside the archive.
                              excludedChildPaths: Set<String> = emptySet()) {
        val safeName = uniqueQueueName("$name.zip")
        // ── Pass-22 (#4): same re-arm-budget reset as the file path above ──────
        // — a re-sent folder keeps its full self-heal ladder too.
        qsRearmedKeys.removeAll { it.startsWith("$safeName|") }
        // Pass-33 (#1): a manifest folder's size is KNOWN at staging (the
        // album's aggregate) — report it so web clients, the host's own live
        // rows and the targeted waiting rows all show the real total instead
        // of a placeholder 0/unknown. An ordinary folder keeps 0 (genuinely
        // unknown until the archive is streamed), exactly as before.
        // GOAT-PASS 14 (§2.1): excluded members are NOT part of the archive —
        // the reported aggregate counts exactly the children that will
        // actually be zipped, matching the Queue's own counter pill.
        val manifestBytes = childManifest
            ?.filter { com.lumidrop.x.transfer.ChildExclusions.isKept(it.relPath, excludedChildPaths) }
            ?.sumOf { it.size } ?: 0L
        quickShareQueue.add(QSItem(safeName, null, null,
            manifestBytes, "application/zip",
            folderUri = folderUriString, folderPath = folderPath, targetIps = targetIps,
            childManifest = childManifest, includeHiddenChildren = includeHiddenChildren,
            excludedChildPaths = excludedChildPaths))
        // Pass-3 (R14): a targeted folder send pre-registers its waiting row
        // too (the manifest total when it is known; otherwise the classic
        // unknown-until-streamed -1).
        preRegisterQuickShareSends(safeName, null, manifestBytes, targetIps)
        stateVersion.incrementAndGet()
    }

    // ── GOAT-PASS 14 (Phase 1, §1.1/§1.3): the app-side queue-batch ZIP send, ──
    // browser leg. ONE Quick-Share item carrying the batch's immutable
    // snapshot — the receiver sees one incoming archive offer (accept/decline
    // through the exact existing incoming-files mechanism, no Full Access
    // needed; /api/qs/* is Transfer-Mode-legal like every QS surface) and the
    // sender's Active tab pre-registers one Waiting row per targeted client,
    // exactly like a targeted folder send. [batch] MUST be the caller's
    // captured send-time snapshot (never a live view of the queue) — callers
    // pass the same list they are removing from staging, so a later queue
    // change can never leak into this archive.
    fun addZipBatchToQuickShare(batch: List<com.lumidrop.x.transfer.PreviewItem>, targetIps: Set<String>? = null) {
        // ── GOAT-PASS 14 (§1.3): the shared filter + naming (transfer/ZipBatch-
        // Naming.kt) — the SAME definition the native leg uses, so both legs of
        // one send agree on the archive's name and contents.
        val usable = com.lumidrop.x.transfer.usableZipBatchItems(batch)
        if (usable.isEmpty()) return
        val zipName = uniqueQueueName(com.lumidrop.x.transfer.zipBatchFileName(usable))
        // ── Pass-22 (#4): a genuinely fresh queue item under this name resets ──
        // the once-only re-arm budget for every peer (same as the file/folder
        // paths above), so a re-sent batch keeps its full self-heal ladder.
        qsRearmedKeys.removeAll { it.startsWith("$zipName|") }
        // The chip's displayed size: the batch's CONTENT size when every
        // member's size is already known, else 0 ("unknown") — the same
        // honesty rule the folder path above applies to physical folders.
        // The TRANSFER row itself is always -1 (the archive's own byte count
        // is genuinely unknowable before it is streamed — NO_COMPRESSION
        // makes them near-identical, but near-identical is not identical).
        val chipSize = zipBatchKnownContentBytes(usable)
        quickShareQueue.add(QSItem(zipName, null, null, chipSize, "application/zip",
            targetIps = targetIps, zipBatch = usable))
        // FINAL PASS 4 (§2): zipStream=true — see preRegisterQuickShareSends.
        preRegisterQuickShareSends(zipName, null, -1L, targetIps, zipStream = true)
        stateVersion.incrementAndGet()
    }

    /** GOAT-PASS 14 (§1.4): the batch's total CONTENT bytes when every
     *  member's size is already known (loose files, texts, and manifest
     *  folders' own aggregates with exclusions applied) — the honest "how
     *  much is inside" figure for the incoming chip. 0 when any ordinary
     *  folder or unknown-size file is present (unknown until streamed). */
    private fun zipBatchKnownContentBytes(usable: List<com.lumidrop.x.transfer.PreviewItem>): Long {
        var total = 0L
        for (item in usable) {
            when {
                item.textContent != null -> total += item.textContent.toByteArray(Charsets.UTF_8).size
                item.isFolder -> {
                    val manifest = item.childManifest ?: return 0L   // physical folder — unknown
                    val excluded = item.excludedChildPaths
                    total += manifest.filter { com.lumidrop.x.transfer.ChildExclusions.isKept(it.relPath, excluded) }.sumOf { it.size }
                }
                else -> {
                    if (item.size <= 0L) return 0L                    // unknown single-file size
                    total += item.size
                }
            }
        }
        return total
    }

    // Quick Share Text: no temporary .txt file is created anywhere — the
    // text itself IS the queued payload. The client recognizes this via
    // apiQSList's "isText" flag and shows a Copy/Cancel popup instead of
    // downloading anything.
    fun addTextToQuickShare(text: String, targetIps: Set<String>? = null) {
        // ── Pass-36 Part 2 (#1): the live queue row's name is a preview of ──
        // the ACTUAL content (the same derivation the staged half uses), so
        // the live row, the browser's offer and the native-push filename all
        // describe what the message IS instead of the generic "Text message".
        val safeName = uniqueQueueName(textPreviewTitle(text))
        quickShareQueue.add(QSItem(safeName, null, null, text.toByteArray(Charsets.UTF_8).size.toLong(),
            "text/plain", targetIps = targetIps, textContent = text))
        stateVersion.incrementAndGet()
        // ── FINAL PASS 4 (§9 — text joins the normal Active-tab lifecycle) ──
        // A TARGETED text now pre-registers one PENDING row per browser —
        // the exact call the file (addToQuickShare), folder (addFolderTo-
        // QuickShare) and ZIP-batch (addZipBatchToQuickShare) paths already
        // make — so the message's Waiting state lives in the SENDER'S ACTIVE
        // TAB from the instant Send is tapped, exactly like a file. The row
        // completes when that browser consumes the message (apiQuickShare-
        // Accept's text branch → TransferManager.completeQuickShareText),
        // declines when it declines (declinePendingQuickShare — already
        // wired into apiQSCancel/apiQSDismissAll, and text rows now carry
        // the qsItemName those helpers key on), and cancels with the queue
        // (cancelLiveQuickShareByName — same keying). The old immediate
        // recordTextTransfer for the targeted case is GONE (it would double-
        // write History against the completion hook); a BROADCAST text
        // (targetIps == null, no pinned receivers — the row can only be
        // minted at the moment some client actually accepts) keeps the
        // immediate record, matching the broadcast-FILE behavior of minting
        // rows at accept time.
        if (!targetIps.isNullOrEmpty()) {
            for (ip in targetIps) {
                val tid = TransferManager.claimOrStartPending(safeName,
                    text.toByteArray(Charsets.UTF_8).size.toLong(),
                    ip, getDeviceDisplayName(ip), qsItemName = safeName)
                // The row must render as a MESSAGE (the Copy-button card, the
                // History message shape) — the text rides the row itself.
                tid?.let { TransferManager.markQuickShareRowAsText(it, text) }
            }
        } else {
            try {
                val peerName = "All connected devices"
                TransferManager.recordTextTransfer(TransferManager.Direction.SEND, text,
                    "", peerName, TransferManager.Source.QUICK_SHARE)
            } catch (e: Exception) { /* the queue item itself is the load-bearing half */ }
        }
    }

    fun clearQuickShare() {
        // Pass-3 (R14): tearing the queue down must also stop every live
        // delivery its items pre-registered — no orphan "Waiting" rows in
        // the Active tab for a queue the host just cleared.
        for (item in quickShareQueue.toList()) TransferManager.cancelLiveQuickShareByName(item.name)
        quickShareQueue.clear(); stateVersion.incrementAndGet()
    }

    private fun apiQSList(s: IHTTPSession): Response {
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        val arr = JSONArray()
        for (item in quickShareQueue) {
            // Pass-6: local capture — targetIps is a mutable var now.
            val listTargets = item.targetIps
            if (listTargets != null && ip !in listTargets) continue
            arr.put(JSONObject().apply {
                put("name",     item.name)
                put("path",     item.filePath ?: item.contentUri ?: item.folderUri ?: item.folderPath ?: "")
                put("isFolder", item.folderUri != null || item.folderPath != null)
                put("isText",   item.textContent != null)
                if (item.textContent != null) put("text", item.textContent)
                put("dlUrl",    "/api/qs/dl?n=" + java.net.URLEncoder.encode(item.name, "UTF-8"))
                put("size",     item.size)
                put("addedAt",  item.addedAt)
                // consumed = true means THIS client already downloaded this item.
                // The frontend hides it locally while leaving it visible to other clients.
                put("consumed", ip in item.consumedByIps)
            })
        }
        return json(JSONObject().put("files", arr).put("count", arr.length()))
    }

    // For the host's own queue card: returns each queued item with how many
    // connected clients have consumed it vs. how many are targeted/expected.
    private fun apiQSHostStatus(): Response {
        val cutoff = System.currentTimeMillis() - 30_000L
        val connectedNow = connectedIPs.filter { it.value > cutoff }.keys
        val arr = JSONArray()
        for (item in quickShareQueue) {
            val expected = (item.targetIps ?: connectedNow.toSet()).size
            val consumed = item.consumedByIps.size
            arr.put(JSONObject().apply {
                put("name",     item.name)
                put("consumed", consumed)
                put("expected", expected)
                // path used by host's Cancel button to call /api/quickshare/clear-one
                put("path",     item.filePath ?: item.contentUri ?: item.folderUri ?: item.folderPath ?: "")
            })
        }
        return json(JSONObject().put("items", arr))
    }

    // HOST-ONLY: removes one specific item from queue globally (host's Cancel per item).
    fun removeFromQueueByName(name: String) {
        val toRemove = quickShareQueue.filter { it.name == name }
        if (toRemove.isNotEmpty()) quickShareQueue.removeAll(toRemove)
        // Pass-3 (R14): removing the queue item also stops the live delivery
        // rows it pre-registered (deliberate host cancel → Cancelled, never
        // a lingering "Waiting" ghost in the Active tab).
        if (toRemove.isNotEmpty()) TransferManager.cancelLiveQuickShareByName(name)
        stateVersion.incrementAndGet()
    }

    // Pass-6 (R-H — cancel reaches every receiver instantly): the host
    // cancelling ONE client's waiting row in the Transfers tab (each targeted
    // send pre-registers one row per client — see preRegisterQuickShareSends)
    // is a DECISION about that one client's offer, not about the whole queue
    // item: the other targeted clients keep their offer. But until now the
    // row cancel only touched the TransferManager entry — the queue item
    // survived untouched, so the SAME file instantly re-appeared on the
    // Send tab's queue card AND stayed in that client's incoming list, the
    // exact "cancelled but still showing as waiting / still incoming"
    // complaint. This scopes the decision correctly: the client's IP leaves
    // the item's target set (both the /api/quickshare list and the qstate
    // "qc" visibility check key on targetIps), the item drops entirely once
    // nobody is left to offer it to, and every open page learns via the
    // state-version push — the client's incoming section loses the file on
    // the very next payload, no refetch of anything else required.
    fun cancelQuickShareForPeer(name: String, ip: String) {
        val item = quickShareQueue.firstOrNull { it.name == name } ?: return
        val targets = item.targetIps
        if (targets != null) {
            val remaining = targets - ip
            item.targetIps = remaining
            if (remaining.isEmpty() && allConsumed(item)) quickShareQueue.remove(item)
        } else {
            // Broadcast item (no explicit targets): mark it consumed for
            // THIS client so both visibility filters stop offering it here,
            // without taking it away from anyone else.
            item.consumedByIps.add(ip)
        }
        // Belt-and-braces: resolve any still-live row this client has for
        // the item (requestCancel from the caller already did the one that
        // was tapped; this catches a second live row from a re-send race) —
        // PEER-SCOPED, so the other targeted clients' identical rows for
        // the same item are left completely alone.
        TransferManager.cancelLiveQuickShareForPeer(name, ip)
        stateVersion.incrementAndGet()
    }

    // HOST-ONLY: wipes the entire queue for every client instantly.
    // Called only by the host's own Clear button — never by a web client.
    private fun apiQSClear(): Response {
        quickShareQueue.clear()
        stateVersion.incrementAndGet()
        return json(JSONObject().put("success", true))
    }

    // CLIENT-LOCAL: marks every item visible to this IP as consumed by it.
    // Only removes an item globally if ALL other targeted clients have also
    // consumed it — other clients are never affected.
    private fun apiQSDismissAll(s: IHTTPSession): Response {
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        val toRemoveGlobally = mutableListOf<QSItem>()
        for (item in quickShareQueue) {
            // Pass-6: local capture — targetIps is a mutable var now.
            val disTargets = item.targetIps
            if (disTargets != null && ip !in disTargets) continue
            item.consumedByIps.add(ip)
            if (allConsumed(item)) toRemoveGlobally.add(item)
            // Pass-3 (R14): Decline-All resolves every pre-registered waiting
            // row for this client as Declined — the sender sees the decision
            // immediately, nothing sits "Waiting" for a queue the receiver
            // just turned down.
            TransferManager.declinePendingQuickShare(item.name, ip)
        }
        if (toRemoveGlobally.isNotEmpty()) quickShareQueue.removeAll(toRemoveGlobally)
        stateVersion.incrementAndGet()
        return json(JSONObject().put("success", true))
    }

    // CLIENT-LOCAL: marks a single named item as consumed by this IP.
    // Global removal only if all targeted clients have consumed it.
    private fun apiQSCancel(s: IHTTPSession): Response {
        val name = s.parameters["n"]?.firstOrNull()
            ?: s.parameters["path"]?.firstOrNull()?.let {
                // Legacy path-based lookup for backward compat with any older call site
                quickShareQueue.firstOrNull { q -> q.filePath == it || q.contentUri == it || q.folderUri == it || q.folderPath == it }?.name
            }
            ?: return jsonErr("No item identifier")
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        val item = quickShareQueue.firstOrNull { it.name == name }
        if (item != null) {
            item.consumedByIps.add(ip)
            if (allConsumed(item)) quickShareQueue.remove(item)
            stateVersion.incrementAndGet()
        }
        // Pass-3 (R14): the receiver turning an item down resolves its
        // pre-registered waiting row as Declined on the sender's side — the
        // decision lands immediately and the row can never come back as a
        // re-queued chip (the re-arm sweep only re-arms FAILED deliveries).
        TransferManager.declinePendingQuickShare(name, ip)
        return json(JSONObject().put("success", true))
    }

    // ── Accept endpoint ("transfer system polish" phase) ─────────────────────
    // Called the INSTANT a client accepts one or more queued Quick-Share items
    // (Accept All, a single Accept, or Auto-Accept) — BEFORE the actual byte
    // transfer request is made. This decouples "the client has decided to
    // take this file" from "the client's own download-manager concurrency
    // limit has let the real HTTP GET through," which is what makes the
    // host's queue card clear instantly on accept (not gradually, as each
    // transfer's turn comes up under concurrency limiting) while the
    // Transfers tab still shows a "Waiting" entry for anything not yet
    // actually moving bytes.
    //
    // Body: application/x-www-form-urlencoded, one or more repeated `n=name`
    // fields — NOT a JSON body or comma-joined list, so filenames containing
    // arbitrary characters (commas, unicode, emoji) are never ambiguous, and
    // the request is body-size-bound rather than URL-length-bound, since a
    // single Accept All on a large host-side batch can mean hundreds of names.
    // Phase 10 (§speed U4): bounded form-body reader for the small form
    // endpoints (quickshare/accept, text/send, client/capability). NanoHTTPD's
    // parseBody spills ANY body >= 1KB to a temp file + mmap — a 300-file
    // Accept-All paid a disk write and a FileChannel.map per call. This
    // reader mirrors readPathPkgParams' proven pattern: 2MB cap, direct
    // UTF-8 read, URL-decode, append into s.parameters (multi-value safe —
    // "n" repeats accumulate exactly like parseBody's), oversized bodies
    // drained so keep-alive framing survives.
    private fun parseFormBodyBounded(s: IHTTPSession, vararg keys: String) {
        if (s.method != Method.POST) return
        try {
            val declared = s.headers["content-length"]?.toLongOrNull() ?: 0L
            val cap = 2L * 1024 * 1024
            val toUse = when { declared <= 0L -> 0; declared > cap -> cap.toInt(); else -> declared.toInt() }
            if (toUse > 0) {
                val buf = ByteArray(toUse)
                var off = 0
                while (off < toUse) {
                    val n = s.inputStream.read(buf, off, toUse - off)
                    if (n <= 0) break
                    off += n
                }
                val body = String(buf, 0, off, Charsets.UTF_8)
                for (pair in body.split("&")) {
                    if (pair.isEmpty()) continue
                    val eq = pair.indexOf('=')
                    val k = URLDecoder.decode(if (eq >= 0) pair.substring(0, eq) else pair, "UTF-8")
                    if (k !in keys) continue
                    val v = if (eq >= 0) URLDecoder.decode(pair.substring(eq + 1), "UTF-8") else ""
                    s.parameters.getOrPut(k) { mutableListOf() }.add(v)
                }
            }
            if (declared > toUse) {
                var remaining = declared - toUse
                val drain = ByteArray(64 * 1024)
                while (remaining > 0) {
                    val n = s.inputStream.read(drain, 0, minOf(drain.size.toLong(), remaining).toInt())
                    if (n <= 0) break
                    remaining -= n
                }
            }
        } catch (e: Exception) { /* malformed body — endpoint's own missing-param guard handles it */ }
    }

    private fun apiQuickShareAccept(s: IHTTPSession): Response {
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        parseFormBodyBounded(s, "n")
        val names = s.parameters["n"] ?: return jsonErr("No names")
        if (names.isEmpty()) return jsonErr("No names")

        val now = System.currentTimeMillis()
        expireInFlightQS(now)
        val peerName = getDeviceDisplayName(ip)
        val accepted = JSONArray()

        for (name in names) {
            val item = quickShareQueue.firstOrNull { it.name == name } ?: inFlightQS[name]?.first
                ?: continue   // already fully consumed/expired — nothing to accept

            // ── Pass-22 (#5 — a deliberate cancel can never be auto-accepted ────
            // back to life): the re-arm sweep can put a JUST-CANCELLED item's
            // chip back into the browser's incoming list a moment before the
            // host's Cancel tombstone exists; auto-accept then re-fired the
            // item and claimOrStartPending — which always clears the
            // tombstone — minted a fresh PENDING row, so the file the person
            // provably cancelled re-entered the queue as "Waiting". The
            // tombstone now applies ONLY to the queue item it was recorded
            // against (item.addedAt <= tombstone time): a GENUINE re-send
            // creates its item AFTER the cancel and sails straight through
            // (#4's "the same file must be sendable again after a cancelled
            // transfer"), while the re-armed chip of the cancelled item is
            // refused here and stays cancelled.
            if (cancelTombstoneAppliesToItem(name, ip, item)) {
                continue
            }

            // Pass-3 (R14 restructure): the consume/in-flight bookkeeping runs
            // for EVERY accept, including one whose PENDING row was already
            // pre-registered at queue time (a targeted send) — the old
            // hasLiveEntry early-continue skipped this mark for exactly those
            // rows, leaving the queue item un-consumed and the Send tab's
            // counter wrong. Idempotency is preserved by the consumed check
            // below plus claimOrStartPending's own atomic live-entry guard.
            if (ip !in item.consumedByIps) {
                item.consumedByIps.add(ip)
                inFlightQS[name] = Pair(item, now + IN_FLIGHT_TTL)
                if (allConsumed(item)) quickShareQueue.remove(item)
            }

            // ── FINAL PASS 4 (§9 — a text consume RESOLVES the sender's ──────
            // Active-tab row). Text messages are still not a byte transfer
            // (nothing is ever downloaded — the client shows View/Copy and
            // the message is delivered the moment it is consumed), but the
            // sender's row lifecycle is now the SAME one files live: a
            // targeted send's pre-registered PENDING row COMPLETES here (the
            // consume is the delivery), and a broadcast — which has no row
            // yet, exactly like a broadcast file — mints its per-client row
            // and completes it in the same step (one History message card
            // per delivery, never two: the claim half reuses any live row).
            if (item.textContent == null) {
                // claimOrStartPending (rather than a plain startPending call)
                // re-checks hasLiveEntry ATOMICALLY — closing the narrow race
                // where two near-simultaneous accept calls for the same item
                // both observe "no live entry yet" — and, for a targeted
                // send's pre-registered row, simply returns null so the SAME
                // entry continues into the accepted download instead of a
                // second row being minted. Either way the caller learns the
                // item is accepted.
                val tid = TransferManager.claimOrStartPending(item.name,
                    // GOAT-PASS 14 (§1.6): a zip-batch item's row total is always
                    // the honest unknown (-1) — the chip's displayed content-size
                    // is NOT the archive's own byte count, and a wrong total
                    // would freeze the progress bar just short of (or past) 100%.
                    if (item.zipBatch != null) -1L else item.size.takeIf { it > 0 } ?: -1L,
                    ip, peerName,
                    qsItemName = item.name, filePath = item.filePath)
                if (tid != null) accepted.put(JSONObject().put("name", item.name).put("tid", tid))
                else accepted.put(JSONObject().put("name", item.name))
            } else {
                // FINAL PASS 4 (§9): the row this consume resolves — a
                // targeted send's pre-registered PENDING row completes in
                // place; a broadcast mints-and-completes through the same
                // claim identity a broadcast file uses.
                if (!TransferManager.completeQuickShareText(item.name, ip)) {
                    TransferManager.claimAndCompleteQuickShareText(item.name, item.name,
                        item.size.takeIf { it > 0 } ?: item.textContent!!.toByteArray(Charsets.UTF_8).size.toLong(),
                        ip, peerName, item.textContent!!)
                }
                accepted.put(JSONObject().put("name", item.name))
            }
        }
        stateVersion.incrementAndGet()
        return json(JSONObject().put("accepted", accepted))
    }

    // ── Quick Text Transfer ────────────────────────────────────────────────────
    // Client pastes/types text in the WebUI and taps Send. Pass-28 (#3C): the
    // text is enqueued on the host as a pending incoming item in the SAME
    // acceptance flow a native peer's text takes (the incoming sheet's
    // Copy/Cancel row, Accept All's combined clipboard entry, the History
    // card at acceptance) — no file, no download, and nothing auto-copied
    // before the host accepts. Body: application/x-www-form-urlencoded,
    // a single `text` field (not a JSON body — same reasoning as the
    // Quick-Share accept endpoint: works with NanoHTTPD's parseBody() with
    // zero extra parsing logic, and handles arbitrarily long / unicode text
    // correctly via normal URL-encoding).
    private fun apiTextSend(s: IHTTPSession): Response {
        parseFormBodyBounded(s, "text")
        val text = s.parameters["text"]?.firstOrNull() ?: return jsonErr("No text")
        if (text.isBlank()) return jsonErr("Empty text")
        val ip = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        val fromName = getDeviceDisplayName(ip)

        // ── Pass-28 (#3C — web text joins the SHARED incoming acceptance flow) ──
        // This endpoint used to be a separate receiving pipeline of its own:
        // it copied the text straight onto the host's clipboard and recorded
        // the History card right here, bypassing the incoming-text sheet
        // entirely — so a browser sender's text landed uninvited on the
        // clipboard with no per-item Copy/Cancel row, no Accept All, and no
        // say at all for the host, while the exact same text from a native
        // peer waited politely in the sheet. The text is now enqueued as a
        // manifest-ready PendingNativePush holding ONE text entry — the very
        // shape a native sender's manifest carries for a Quick-Share text
        // item, down to the same "Text message.txt" top-level name
        // NativePushClient's own builder mints — so every downstream behavior
        // is the one shared path: the incoming sheet's Copy/Cancel row, the
        // exactly-once clipboard delivery at acceptance, the Accept All
        // combined entry, the History card at acceptance
        // (precreateNativePushDirs), the TTL sweep for a text never decided,
        // and the auto-accept setting's fast-forward (identical to
        // apiNativePushRequest's own — with the setting on, delivery is
        // immediate, exactly what the setting promises). Nothing is
        // auto-copied before the host accepts. fromPort 0: a browser client
        // exposes no /api/native/push/control endpoint, and a text push
        // never registers a transfer row (registerPendingTransfersFor skips
        // text entries), so no sender state-sync call can ever target it.
        val id = java.util.UUID.randomUUID().toString().replace("-", "")
        val entry = NativePushEntry(
            path = "Text message.txt",
            size = text.toByteArray(Charsets.UTF_8).size.toLong(),
            isDir = false, isText = true, text = text)
        val push = PendingNativePush(
            id = id, fromDeviceId = "web:$ip", fromName = fromName, fromIp = ip, fromPort = 0,
            entries = listOf(entry), totalBytes = entry.size,
            destRoot = File(SettingsManager.getUploadPath(context)))
        pendingNativePushes[id] = push
        if (SettingsManager.isAutoAcceptUploads(context)) {
            // Same path a manual accept takes — no duplicated bookkeeping.
            acceptNativePush(id)
        } else {
            val stillPending = pendingNativePushes.values.count { awaitingHostDecision(it) }
            com.lumidrop.x.service.ServerForegroundService.notifyPendingNativePush(
                context, fromName, 1, stillPending)
        }
        return json(JSONObject().put("success", true))
    }

    // ── QS Download — multi-client sync ──────────────────────────────────────
    // Item stays in quickShareQueue (visible to all targeted clients) until
    // every targeted client has consumed it. Only then is it removed globally.
    // Client A downloading does NOT make the chip disappear for client B.
    // Host Clear/Cancel still removes everywhere instantly.
    private fun cancelTombstoneAppliesToItem(name: String, ip: String, item: QSItem?): Boolean {
        val tsAt = TransferManager.cancelledDownloadAt("qs", name, ip)
        if (tsAt <= 0L) return false
        // A queue item created AFTER the deliberate cancel is a genuinely
        // new send of the same name — the old verdict must not answer for
        // the new transfer's very first request. (null item = a request that
        // raced the queue; the live tombstone keeps applying.)
        val addedAt = item?.addedAt ?: 0L
        return addedAt <= tsAt
    }

    private fun apiQSDl(s: IHTTPSession): Response {
        val name = s.parameters["n"]?.firstOrNull() ?: return jsonErr("No name param")
        val ip   = try { s.remoteIpAddress ?: "unknown" } catch (e: Exception) { "unknown" }
        val now  = System.currentTimeMillis()

        // True cancellation: refuse an automatic/manual retry for a short
        // window right after the host cancelled this exact item for this
        // exact client — otherwise the client's own retry logic (or a fresh
        // Accept tap) would silently re-succeed, undoing the cancel. Lifts
        // on its own a few seconds later regardless of whether the
        // cancelled record is still sitting in the Completed tab (see
        // wasCancelledForQuickShare) — a later resend always has to go
        // through, whether that's the host re-sending or the client trying
        // again.
        // ── Pass-22 (#4, the byte-request half): the refusal is now scoped ─────
        // to the SAME queue item the cancel was recorded against — a fresh
        // re-send's item is provably newer than the tombstone and its very
        // first GET must never be answered "Cancelled by host" (the exact
        // "cannot send the same file again" report), while the cancelled
        // item's own late retries keep bouncing forever.
        if (TransferManager.wasCancelledForQuickShare(name, ip) &&
            cancelTombstoneAppliesToItem(name, ip, quickShareQueue.firstOrNull { it.name == name } ?: inFlightQS[name]?.first))
            return jsonErr("Cancelled by host")

        // ── GOAT-PASS 6 (§3 — a paused QS item never parks a second connection) ──
        // A request arriving for an item the host deliberately paused (past
        // the toggle grace) is the download manager's own retry of the parked
        // leg — parking it beside the original would hold yet another browser
        // connection hostage for the pause's whole duration, and the browser's
        // connection-starved page is exactly what starved the liveness probes
        // in the reported mixed-pause failure. Answer exactly what the upload
        // path answers (GOAT-PASS 5's "Paused by host" + Connection: close):
        // the DM backs off, the row keeps its paused state and checkpoint, and
        // the browser's pump re-dispatches the item the moment the pause
        // lifts. Checked BEFORE the gate (a late retry) and again AFTER the
        // gate grant (a request that queued while the pause landed) — both
        // release cleanly and never burn a dispatch.
        if (TransferManager.pausedDownloadPastGrace(name, name, ip) != null)
            return jsonErrClose("Paused by host")

        expireInFlightQS(now)

        // Find in active queue or in-flight cache (for range/resume follow-ups)
        var item = quickShareQueue.firstOrNull { it.name == name }
        if (item != null) {
            item.consumedByIps.add(ip)
            inFlightQS[name] = Pair(item, now + IN_FLIGHT_TTL)
            if (allConsumed(item)) {
                quickShareQueue.remove(item)
            }
            stateVersion.incrementAndGet()
        } else {
            item = inFlightQS[name]?.first
                ?: return jsonErr("Not in queue: $name")
            // Range/resume request from same client — just serve, no state change
        }
        // Pass-21 (large-batch liveness): this peer just demonstrated the
        // batch is alive — its whole in-flight item set is legitimate.
        refreshPeerInFlightQS(ip, now)

        val peerName = getDeviceDisplayName(ip)
        // Phase 4 download gate: same attach-vs-create rule as apiDownload —
        // a browser's second connection (range follow-up, the legacy-iOS
        // native download manager's documented duplicate GET) attaches to
        // the already-ACTIVE transfer and takes no second slot.
        // Phase 1: wait-keyed, holder-guarded slot; the PENDING entry's own
        // id (created by the /accept call moments ago) whenever one exists.
        val pendingQsTid = TransferManager.findPendingQuickShareId(item.name, ip)
        // Pass-5 (R5 — "last file stuck on Waiting"): stamp the pending row
        // the moment its real byte-request arrives (even though it may then
        // WAIT behind the concurrency gate). The never-requested sweep
        // distinguishes "queued for its turn" (honest Waiting) from "the
        // browser silently dropped the dispatch" (self-heals in ~25s).
        if (pendingQsTid != null) TransferManager.markPendingRequested(pendingQsTid)
        val gateKey: String? =
            if (TransferManager.hasActiveDownloadFor(item.name, ip)) null
            else downloadWaitKey(pendingQsTid)
        // Pass-23: a refused gate wait (sliding window expired / queued cancel)
        // must not leave the PENDING row's "requested" stamp behind — a
        // stamped row counts as live work forever and wedges the whole tail
        // behind a request that is no longer coming (see
        // TransferManager.clearPendingRequested).
        if (gateKey != null && !acquireDownloadSlotFor(gateKey)) {
            if (pendingQsTid != null) TransferManager.clearPendingRequested(pendingQsTid)
            return jsonErr("Cancelled by host")
        }
        // ── Pass-17 (host-cancel finality, the queued-request hole): this ──
        // endpoint's tombstone check ran BEFORE the request waited for a free
        // download slot — a host Cancel landing while it was QUEUED finalized
        // the row and dismissed it within 400 ms, and the request then sailed
        // past the (now-dead) isCancelled gate check into the start() fallback
        // below, streaming the whole file to completion as a fresh ghost row
        // (the reported "Cancel on the app side does nothing — the browser's
        // download just continues"). The post-gate re-check closes exactly
        // that hole for every download surface; the QS name-keyed tombstone is
        // consulted here, mirroring apiDownload's identical block.
        if (gateKey != null && TransferManager.wasCancelledForQuickShare(item.name, ip)) {
            TransferManager.releaseDownloadSlot(gateKey)
            return jsonErr("Cancelled by host")
        }
        // ── GOAT-PASS 6 (§3): the post-gate half of the paused-item refusal ──
        // above — a request that WAITED for its download slot while the host
        // paused the row must not now claim it and park a fresh connection
        // inside the pause. Release the slot exactly as the cancel re-check
        // does and answer the same "Paused by host".
        if (TransferManager.pausedDownloadPastGrace(item.name, item.name, ip) != null) {
            if (gateKey != null) TransferManager.releaseDownloadSlot(gateKey)
            if (pendingQsTid != null) TransferManager.clearPendingRequested(pendingQsTid)
            return jsonErrClose("Paused by host")
        }
        // Resolves to a PENDING entry (created by apiQuickShareAccept above,
        // moments before this real byte-request arrived) promoted to ACTIVE,
        // an existing ACTIVE entry this request attaches to as an additional
        // connection (a browser's parallel Range requests for one large
        // file — see claimOrStartActiveQuickShare's doc), or a fresh ACTIVE
        // entry for any client that hits this endpoint without calling
        // /accept first (e.g. a very old cached WebUI, or a hand-built URL).
        val tid = TransferManager.claimOrStartActiveQuickShare(item.name, ip, item.name,
            // GOAT-PASS 14 (§1.6): same unknown-total rule as the accept path —
            // a zip batch's streamed byte count is not its content size.
            if (item.zipBatch != null) -1L else item.size.takeIf { it > 0 } ?: -1L,
            peerName, item.filePath, rangeStart = rangeStartOf(s))
        if (gateKey != null) TransferManager.moveDownloadSlot(gateKey, tid)

        // Phase 3 of 10 (old-Safari tracked receive — see apiDownload's own
        // inline comment for the full rationale): a legacy iOS client that
        // taps "Get" on an image QS item fetches it inline=1 and shows it in
        // the in-page receive viewer; the bytes, the transfer identity, the
        // concurrency slot, the progress, and the Cancel semantics are all
        // IDENTICAL to the modern download-attribute path. Folders/ZIPs keep
        // their attachment disposition unconditionally — a streamed archive
        // has no inline representation that could preserve its identity.
        // Phase 4 of 10 (enhanced-Safari correction): octet=1/handoff=1 are
        // the SAME two parameters apiDownload documents — served as
        // application/octet-stream so a Safari-Plus-class download manager
        // intercepts even displayable types, with connection-aware abort
        // semantics so the interception's cancel-then-re-request keeps ONE
        // transfer identity. The legacy client sends them for its real-
        // download path (after the receiver accepts) and inline=1 only for
        // the stock-browser viewer fallback — the flows are mutually
        // exclusive by construction.
        val inline = s.parameters["inline"]?.firstOrNull() == "1"
        val octet   = s.parameters["octet"]?.firstOrNull() == "1"
        val handoff = s.parameters["handoff"]?.firstOrNull() == "1"
        if (handoff) TransferManager.markHandoffEligible(tid)
        // ── PASS 27 (throughput): the settle only guards SMALL bodies from ──
        // completing on the connection a download-manager interception is
        // about to cancel (see handoffSettleMs) — a large body cannot finish
        // inside that window, so it streams immediately. A zip-batch's live-
        // synthesized archive has no known size at response time, so it keeps
        // the settle (a tiny folder zip is exactly the shape it protects).
        val settleMs = handoffSettleMs(handoff, tid,
            if (item.zipBatch != null) 0L else maxOf(item.size, 0L))
        val connToken = handoffConnCounter.incrementAndGet()
        if (handoff && (TransferManager.wasReAwaited(tid) || TransferManager.liveConnectionCount(tid) > 1))
            TransferManager.markHandoffSuperseded(tid, connToken)
        val serveMime = if (octet) "application/octet-stream" else item.mime

        return when {
            // ── GOAT-PASS 14 (Phase 1, §1.5/§1.7/§1.8): the app-side queue-batch ──
            // ZIP — ONE streamed archive synthesized live from the item's
            // immutable batch snapshot. The exact pipe/ZipOutputStream/
            // shared-walker streaming pattern the folder branch below has
            // always used (no temp file, no full-archive buffer, unknown total
            // = -1, Accept-Ranges: none), with every guarantee of that branch
            // inherited: one transfer identity, chunked streaming, progress
            // via the reader callback, honest completion (writer closed
            // cleanly AND reader finished), host-cancel through the same slot
            // gate, and a Failed row + Retry (retryQuickShareForIp re-offers
            // THIS item's unchanged snapshot) after an interruption.
            item.zipBatch != null -> {
                // Resolve every non-text member against its send-time address
                // FIRST — §1.8's truthfulness contract: a retry whose source
                // data is gone must fail with a real reason, never stream an
                // incomplete/different archive under the original filename.
                val roots = ArrayList<Pair<FileTreeNode, Set<String>>>()
                val missing = ArrayList<String>()
                val texts = ArrayList<Pair<String, String>>()
                for (member in item.zipBatch!!) {
                    val text = member.textContent
                    if (text != null) { texts.add(member.name to text); continue }
                    val node: FileTreeNode? = try {
                        when {
                            member.isFolder && member.uri != null ->
                                androidx.documentfile.provider.DocumentFile.fromTreeUri(context, android.net.Uri.parse(member.uri))
                                    ?.let { FileTreeNode.DocNode(it, context.contentResolver, member.includeHiddenChildren) }
                            member.isFolder && member.path != null -> {
                                val dir = File(member.path!!)
                                if (dir.isDirectory) FileTreeNode.FsNode(dir, member.includeHiddenChildren) else null
                            }
                            member.path != null -> {
                                val f = File(member.path!!)
                                if (f.isFile) FileTreeNode.FsNode(f) else null
                            }
                            member.uri != null ->
                                androidx.documentfile.provider.DocumentFile.fromSingleUri(context, android.net.Uri.parse(member.uri))
                                    ?.let { FileTreeNode.DocNode(it, context.contentResolver) }
                            else -> null
                        }
                    } catch (e: Exception) { null }
                    if (node == null) missing.add(member.name)
                    else {
                        // §2.1: a folder member's queue-time child removals are
                        // honored here too — remapped into the archive's own
                        // "<folderName>/<relPath>"" namespace (the walker's natural
                        // paths begin with each root's own name), exactly like the
                        // folder branch below remaps its own item's exclusions.
                        // The exclusion check runs inside the shared walker, on
                        // the natural path BEFORE any de-dup rename — no eager
                        // pre-walk, nothing on the sender's disk is touched.
                        val excludedInZip = if (member.isFolder && member.excludedChildPaths.isNotEmpty())
                            member.excludedChildPaths.map { "${node.name}/$it" }.toSet() else emptySet()
                        roots.add(node to excludedInZip)
                    }
                }
                if (missing.isNotEmpty() || (roots.isEmpty() && texts.isEmpty())) {
                    TransferManager.fail(tid)
                    if (gateKey != null) TransferManager.releaseDownloadSlot(tid)
                    val what = missing.take(3).joinToString(", ")
                    return jsonErr(if (missing.size == 1) "No longer available: $what"
                        else "No longer available: $what ${if (missing.size > 3) "(+${missing.size - 3} more)" else ""}")
                }
                val pipeOut = PipedOutputStream()
                val pipeIn  = PipedInputStream(pipeOut, 256 * 1024)
                val zipCompletedCleanly = java.util.concurrent.atomic.AtomicBoolean(false)
                val writer = Thread {
                    try {
                        ZipOutputStream(BufferedOutputStream(pipeOut, 64 * 1024)).use { zos ->
                            zos.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                            val seen = HashSet<String>()
                            for ((textName, text) in texts) {
                                val entryName = uniqueZipName(seen,
                                    if (textName.endsWith(".txt")) textName else "$textName.txt")
                                zos.putNextEntry(ZipEntry(entryName))
                                try { zos.write(text.toByteArray(Charsets.UTF_8)) } catch (e: Exception) { /* keep the archive alive */ }
                                zos.closeEntry()
                            }
                            for ((root, rootExcluded) in roots) addZipNode(zos, root, "", seen, rootExcluded)
                        }
                        zipCompletedCleanly.set(true)
                    } catch (e: Exception) {
                        // Client disconnected / cancelled mid-stream — the pipe's
                        // own close is all the cleanup this streaming design needs.
                    } finally {
                        try { pipeOut.close() } catch (ignored: IOException) {}
                    }
                }
                writer.isDaemon = true; writer.start()
                // ── FINAL PASS 3 (§1): zipTrack carries this connection's own ──
                // byte count to the completion callback — the exact archive
                // size, stamped onto the row so the completed delivery's
                // History entry shows the real size instead of "?".
                var zipTrack: com.lumidrop.x.transfer.ProgressTrackingInputStream? = null
                servedChunked("application/zip",
                    ProgressTrackingInputStream(if (settleMs > 0) SettleStream(pipeIn, settleMs, tid, connToken) else pipeIn, tid, -1L) { readerOk ->
                        if (readerOk && zipCompletedCleanly.get()) {
                            TransferManager.stampFinalSize(tid, zipTrack?.bytesRead ?: -1L)
                            TransferManager.complete(tid)
                        } else TransferManager.downloadConnectionAborted(tid)
                        if (gateKey != null) TransferManager.releaseDownloadSlot(tid)
                    }.also { zipTrack = it }).also { r ->
                        r.addHeader("Content-Disposition", dispositionAttachment(item.name))
                        r.addHeader("Cache-Control", "no-store")
                        r.addHeader("Accept-Ranges", "none")
                    }
            }
            item.folderUri != null || item.folderPath != null -> {
                // ── Pass-33 (#1): a MANIFEST folder never touches the physical ──
                // directory. The virtual ManifestNode answers exactly the staged
                // members (the album's shown images/videos), so the streamed
                // archive contains precisely what the Queued Files expansion
                // promised — an APK or document sitting in the same directory
                // can never ride along, and no unrelated subfolder is descended
                // into, because no descent happens at all. The root entry keeps
                // the folder's own name, so the archive reads "Download/…"
                // exactly as a physical folder's would.
                val node: FileTreeNode =
                if (item.childManifest != null) {
                    val manifest = item.childManifest!!
                    val rootName = item.folderPath?.let { File(it).name }
                        ?: item.name.removeSuffix(".zip").ifBlank { "Folder" }
                    // ── GOAT-PASS 14 (§2.1): the album's queue-time removals are ──
                    // honored — an excluded member never enters the archive.
                    val kept = manifest.filter { com.lumidrop.x.transfer.ChildExclusions.isKept(it.relPath, item.excludedChildPaths) }
                    val kids = kept.mapNotNull { it.toNode(context) }
                    if (kids.size != kept.size) {
                        TransferManager.fail(tid); if (gateKey != null) TransferManager.releaseDownloadSlot(tid)
                        return jsonErr("Folder no longer accessible")
                    }
                    FileTreeNode.ManifestNode(rootName, kids)
                } else
                // GOAT v2 (item 2 — album send): resolves whichever
                // representation this item actually carries. A SAF tree uri
                // (enqueueFolder's existing folder picker) still resolves via
                // DocumentFile exactly as before; a plain folderPath (a
                // selected media album, which has no SAF grant at all — see
                // QSItem.folderPath's own doc comment) wraps the real
                // directory directly as a FsNode instead. addZipNode below
                // already walks either FileTreeNode kind identically, so
                // nothing past this point needs to know or care which one
                // this particular item is.
                if (item.folderUri != null) {
                    val treeUri = android.net.Uri.parse(item.folderUri)
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(context, treeUri)
                        ?.let { FileTreeNode.DocNode(it, context.contentResolver, item.includeHiddenChildren) }
                        ?: run { TransferManager.fail(tid); if (gateKey != null) TransferManager.releaseDownloadSlot(tid); return jsonErr("Folder no longer accessible") }
                } else {
                    File(item.folderPath!!).let { dir ->
                        if (!dir.isDirectory) { TransferManager.fail(tid); if (gateKey != null) TransferManager.releaseDownloadSlot(tid); return jsonErr("Folder no longer accessible") }
                        // ── Pass-36 Part 2 (#4): the hidden rule rides the QSItem — ──
                        // a physical-folder stream skips hidden descendants unless
                        // the picker's hidden mode deliberately included them.
                        FileTreeNode.FsNode(dir, item.includeHiddenChildren)
                    }
                }
                val pipeOut = PipedOutputStream()
                val pipeIn  = PipedInputStream(pipeOut, 256 * 1024)
                // ── GOAT-PASS 14 (§2.1): the sender's queue-time child removals, ──
                // remapped into this archive's own namespace (the walker's
                // natural paths begin with the folder's own name — the
                // exclusion set the Queue uses is relative to the folder's
                // root). A removal of "Subfolder" drops that whole subtree;
                // "Subfolder/IMG.jpg" drops exactly that file. Nothing on the
                // sender's disk is touched — this is purely the outgoing
                // selection. A single-root walk can never de-dup-rename the
                // root (seen starts empty), so the remap is exact.
                val zipExclusions = item.excludedChildPaths.map { "${node.name}/$it" }.toSet()
                // Set only once the writer thread walks every node and closes
                // the archive without error — see apiZipStream's identical
                // flag for why completion must not rely on "the reader wasn't
                // explicitly cancelled" alone (that's also true of a
                // connection a download manager simply abandoned mid-stream
                // and silently retries, which must land as a failed attempt,
                // not a phantom successful one).
                val zipCompletedCleanly = java.util.concurrent.atomic.AtomicBoolean(false)
                // ── PASS 28: writer-side counter of the delivery gate (see ──
                // ZipStreamDelivery) — an abandoned reader must never complete
                // a row on an archive it did not fully read out of the pipe.
                val zipWriterBytes = java.util.concurrent.atomic.AtomicLong(0L)
                val writer = Thread {
                    try {
                        ZipOutputStream(CountingOutputStream(BufferedOutputStream(pipeOut, 64 * 1024), zipWriterBytes)).use { zos ->
                            zos.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                            val seen = HashSet<String>()
                            addZipNode(zos, node, "", seen, zipExclusions)
                        }
                        zipCompletedCleanly.set(true)
                    } catch (e: Exception) {
                    } finally {
                        try { pipeOut.close() } catch (ignored: IOException) {}
                    }
                }
                writer.isDaemon = true; writer.start()
                // ── FINAL PASS 3 (§1): zipTrack carries this connection's own ──
                // byte count to the completion callback — the exact archive
                // size, stamped onto the row so the completed delivery's
                // History entry shows the real size instead of "?".
                var zipTrack: com.lumidrop.x.transfer.ProgressTrackingInputStream? = null
                servedChunked("application/zip",
                    ProgressTrackingInputStream(if (settleMs > 0) SettleStream(pipeIn, settleMs, tid, connToken) else pipeIn, tid, -1L) { readerOk ->
                        // ── PASS 28 (browser-native download cancellation): ──
                        // the same honest delivery gate as apiZipStream — a
                        // tiny archive finishes into the pipe buffer almost
                        // instantly, so the OLD "reader not cancelled + writer
                        // finished" gate completed the row even when the
                        // connection had been abandoned (a user cancel from
                        // the browser's Downloads section mid-transfer of a
                        // small folder resolved as a false COMPLETED instead
                        // of the cancellation verdict below).
                        if (readerOk && ZipStreamDelivery.deliveredFully(zipCompletedCleanly.get(), zipWriterBytes.get(), zipTrack?.bytesRead ?: -1L)) {
                            TransferManager.stampFinalSize(tid, zipTrack?.bytesRead ?: -1L)
                            TransferManager.complete(tid)
                        } else TransferManager.downloadConnectionAborted(tid)
                        if (gateKey != null) TransferManager.releaseDownloadSlot(tid)
                    }.also { zipTrack = it }).also { r ->
                    r.addHeader("Content-Disposition", dispositionAttachment(item.name))
                    r.addHeader("Cache-Control", "no-store")
                    // Ranges genuinely cannot be honored here (the archive is
                    // synthesized on the fly, size unknown up front) — saying
                    // so explicitly heads off a download manager's own
                    // segmented-fetch attempt against an endpoint that could
                    // never actually satisfy one.
                    r.addHeader("Accept-Ranges", "none")
                }
            }
            item.filePath != null && File(item.filePath).exists() -> {
                val file = File(item.filePath)
                val mime = if (octet) "application/octet-stream" else MimeTypes.getMimeType(file.extension).ifEmpty { item.mime }
                // Phase 5: same unified Range + ETag/If-Range path as every
                // other real-file download (was: hand-rolled full-stream +
                // serveRange pair with no identity validation).
                serveFileDownload(file, mime, s, tid, gateKey != null, if (inline) null else item.name, settleMs, connToken)
            }
            item.contentUri != null -> {
                val uri = android.net.Uri.parse(item.contentUri)
                val streamLen = try {
                    context.contentResolver.openFileDescriptor(uri, "r")
                        ?.use { it.statSize }?.takeIf { it > 0 } ?: item.size
                } catch (e: Exception) { item.size }
                // Phase 5 (v3 §6 item 1): this branch USED to advertise
                // "Accept-Ranges: bytes" while never actually serving a
                // Range request — every range attempt silently got the full
                // body — AND a resume attempt after the underlying media
                // changed had no identity check at all. It now serves real
                // 206s off a seekable file descriptor (FileInputStream.skip
                // is a native lseek on the provider's fd, not a read-and-
                // discard), with an ETag keyed on URI + size as the honest
                // identity this kind of item actually has (content URIs have
                // no reliable mtime). If-Range mismatch → clean full 200
                // restart, same as every other download endpoint.
                val etag = "\"qs-${uri.hashCode()}-$streamLen\""
                val rh = s.headers["range"]
                val ir = s.headers["if-range"]
                val rangeOk = rh != null && ifRangeMatches(ir, etag, null)
                if (rh != null && !rangeOk) {
                }
                val parsed = if (rangeOk) parseRangeSpec(rh ?: "", streamLen) else null
                if (rh != null && rangeOk && parsed == null) {
                    // Malformed / unsatisfiable range — the same honest 416
                    // the real-file path serves, never a silent full body.
                    TransferManager.fail(tid)
                    if (gateKey != null) TransferManager.releaseDownloadSlot(tid)
                    return rangeNotSatisfiable(streamLen)
                }
                if (parsed != null) {
                    val (start, end) = parsed
                    // A fresh descriptor per request: each connection must
                    // seek independently (a browser's parallel range fetches
                    // each need their own position), and the descriptor's
                    // lifetime has to be owned by the response stream, not
                    // by this handler thread. AutoCloseInputStream closes
                    // the ParcelFileDescriptor (and its fd) when NanoHTTPD
                    // closes the response stream in its finally block.
                    val pfd = try { context.contentResolver.openFileDescriptor(uri, "r") } catch (e: Exception) { null }
                    if (pfd != null) {
                        val seekable = android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd)
                        // Robust skip: skip() may legally short-skip on some
                        // providers; fall back to reading single bytes until
                        // the start offset is genuinely reached. EOF before
                        // start means the provider shrank the item → 416.
                        var skipped = 0L
                        try {
                            while (skipped < start) {
                                val n = seekable.skip(start - skipped)
                                if (n > 0) { skipped += n; continue }
                                val b = seekable.read()
                                if (b == -1) break
                                skipped++
                            }
                        } catch (e: Exception) { skipped = -1L }
                        if (skipped == start) {
                            val size = end - start + 1
                            val limited = object : InputStream() {
                                var rem = size
                                override fun read(): Int = if (rem <= 0) -1 else { rem--; seekable.read() }
                                override fun read(b: ByteArray, off: Int, cnt: Int): Int {
                                    if (rem <= 0) return -1
                                    val n = seekable.read(b, off, minOf(cnt.toLong(), rem).toInt())
                                    if (n > 0) rem -= n; return n
                                }
                                override fun close() { seekable.close() }
                            }
                            val tracked = ProgressTrackingInputStream(if (settleMs > 0) SettleStream(limited, settleMs, tid, connToken) else limited, tid, size) { ok ->
                                if (ok) TransferManager.complete(tid) else TransferManager.downloadConnectionAborted(tid)
                                if (gateKey != null) TransferManager.releaseDownloadSlot(tid)
                            }
                            return servedFixed(Status.PARTIAL_CONTENT, serveMime, tracked, size).also {
                                it.addHeader("Content-Range", "bytes $start-$end/$streamLen")
                                it.addHeader("Accept-Ranges", "bytes")
                                it.addHeader("ETag", etag)
                                if (!inline) it.addHeader("Content-Disposition", dispositionAttachment(item.name))
                            }
                        }
                        // Could not reach the requested offset (provider
                        // shrank or refused to seek) — the range is not
                        // satisfiable against what we can actually serve.
                        try { seekable.close() } catch (ignored: Exception) {}
                        TransferManager.fail(tid)
                        if (gateKey != null) TransferManager.releaseDownloadSlot(tid)
                        return rangeNotSatisfiable(streamLen)
                    }
                    // No seekable descriptor available (pipe-backed
                    // provider): the range can't be honored — say so with
                    // 416 rather than pretending (an honest signal beats a
                    // silently-ignored Range, which is what the old code
                    // did while advertising Accept-Ranges: bytes).
                    TransferManager.fail(tid)
                    if (gateKey != null) TransferManager.releaseDownloadSlot(tid)
                    return rangeNotSatisfiable(streamLen)
                }
                // Full response — no Range at all, or a clean full restart
                // after an If-Range identity mismatch. Opened only here so a
                // 206 path never leaks an unused stream.
                val ins = context.contentResolver.openInputStream(uri)
                    ?: run { TransferManager.fail(tid); if (gateKey != null) TransferManager.releaseDownloadSlot(tid); return jsonErr("Cannot open content URI") }
                servedFixed(Status.OK, serveMime,
                    ProgressTrackingInputStream(if (settleMs > 0) SettleStream(ins, settleMs, tid, connToken) else ins, tid, streamLen) { ok ->
                        if (ok) TransferManager.complete(tid) else TransferManager.downloadConnectionAborted(tid)
                        if (gateKey != null) TransferManager.releaseDownloadSlot(tid)
                    }, streamLen).also {
                    if (!inline) it.addHeader("Content-Disposition", dispositionAttachment(item.name))
                    it.addHeader("Accept-Ranges", "bytes")
                    it.addHeader("ETag", etag)
                }
            }
            else -> { TransferManager.fail(tid); if (gateKey != null) TransferManager.releaseDownloadSlot(tid); jsonErr("File missing from filesystem") }
        }
    }

    /**
     * Re-arms a Quick-Share item for one specific client after a failed or
     * cancelled transfer (Transfers tab Retry action — Phase 10). Quick-Share
     * items mark a target IP "consumed" the instant a download is requested
     * (see apiQSDl above), not on successful completion, so a transfer that
     * failed partway through is already marked delivered and won't naturally
     * reappear in that client's own queue view. This undoes exactly that,
     * without touching anything else about the already-finalized Phase 6/8
     * queue behaviour: it un-marks this one IP as having consumed the item,
     * and restores the item to the live queue if it had been removed because
     * every other target had already consumed it.
     */
    fun retryQuickShareForIp(itemName: String, ip: String): Boolean {
        var item = quickShareQueue.firstOrNull { it.name == itemName }
        if (item == null) item = inFlightQS[itemName]?.first
        if (item == null) return false
        item.consumedByIps.remove(ip)
        if (!quickShareQueue.contains(item)) quickShareQueue.add(item)
        stateVersion.incrementAndGet()
        return true
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    // Every JSON API response must be explicitly non-cacheable. Some legacy
    // WebViews (notably Android 5's stock WebView/Chromium build) and certain
    // iOS Safari versions will cache a GET response with no Cache-Control
    // header under some conditions, which — for a *polling* endpoint like
    // /api/qstate or /api/quickshare — silently freezes the client on a
    // stale snapshot until the page is fully reloaded. This was the root
    // cause of "queued files sometimes need a refresh to appear." Applying
    // this uniformly to every JSON response (not just the polling ones)
    // costs nothing and rules the whole class of bug out everywhere.
    private fun noCache(r: Response): Response {
        r.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0")
        r.addHeader("Pragma", "no-cache")
        r.addHeader("Expires", "0")
        return r
    }

    private fun json(o: JSONObject) =
        noCache(newFixedLengthResponse(Status.OK, "application/json; charset=utf-8", o.toString()))
    private fun jsonErr(msg: String) =
        noCache(newFixedLengthResponse(Status.BAD_REQUEST, "application/json; charset=utf-8",
            JSONObject().put("error", msg).toString()))

    /** ── GOAT-PASS 5 (§8 — zombie-connection killer) ──────────────────────────
     *  A terminal early-exit response for a request whose BODY was not fully
     *  read (a browser upload the host paused, cancelled, or lost mid-body).
     *  Riding HTTP/1.1 keep-alive there parks the NanoHTTPD worker in its
     *  next-request read with unread body bytes — a zombie connection for up
     *  to the socket-timeout window, and (repeated across a paused or
     *  cancelled batch, while the browser pool also holds its parked legs)
     *  exactly the class of per-host connection exhaustion that froze the
     *  queue at the Nth paused/cancelled item. Connection: close makes the
     *  socket die WITH the verdict, promptly, on every engine. */
    private fun jsonErrClose(msg: String) =
        noCache(newFixedLengthResponse(Status.BAD_REQUEST, "application/json; charset=utf-8",
            JSONObject().put("error", msg).toString())).also { it.closeConnection(true) }

    // ── FP18 (§5): HTTP 425 Too Early — "genuinely queued behind other
    // receives, retry". Built via Status.lookup(425) because this NanoHTTPD
    // fork's enum may predate the constant; the closest throttling status is
    // the fallback. The answer closes the connection exactly like the
    // jsonErrClose refusals: the file POST's body is mid-flight on the wire
    // (the sender streams it while the receiver waits its slot turn), so a
    // keep-alive answer would park unread body bytes on the socket and
    // corrupt the worker's next-request parse. The sender's re-POST opens a
    // fresh connection — one bounded TCP setup per queued file, in exchange
    // for never classifying an honest queue turn as a 45-second network
    // death.
    private fun status425(): Status =
        try { Status.lookup(425) } catch (e: Exception) { Status.TOO_MANY_REQUESTS }

    private fun jsonQueued(): Response =
        noCache(newFixedLengthResponse(status425(), "application/json; charset=utf-8",
            JSONObject().put("queued", true)
                .put("queueDepth", TransferManager.receivingQueueDepth()).toString()))
            .also { it.closeConnection(true) }   // the POST body is still inbound — die with the answer, never poison keep-alive
    private fun sanitize(s: String) =
        s.replace(sanitizeRegex, "_").replace(controlCharsRegex, "_").trimStart('.').take(255).ifEmpty { "file" }
    // Phase 8 (§8.3): compiled once — sanitize() runs per uploaded/downloaded
    // filename (hundreds of times in a many-small-file batch), and each old
    // call re-compiled the same character-class pattern.
    private val sanitizeRegex = Regex("[/\\\\:*?\"<>|]")

    // Final Stabilization (§10 header correctness): C0/C1 control characters
    // in an uploaded filename could split or corrupt a Content-Disposition
    // header (header injection). sanitize() now strips them alongside the
    // path-reserved set.
    private val controlCharsRegex = Regex("[\\x00-\\x1f\\x7f]")

    /** Final Stabilization (§10): one Content-Disposition value builder for
     *  every attachment site. Emits the RFC 6266 dual form — an ASCII-only
     *  quoted fallback (`filename=`) plus the percent-encoded UTF-8 extended
     *  form (`filename*=UTF-8''…`) — so a file called "报告.pdf" downloads
     *  with its real name on modern browsers and a safe ASCII fallback on
     *  engines that ignore the extended form (old Safari/IE). Unicode names
     *  previously collapsed to "_" characters in the quoted form alone.
     *  Percent-encoding here is HTTP-header filename* encoding (RFC 5987) —
     *  deliberately NOT the URL encoding used in query strings; the two are
     *  built from different reserved sets. */
    private fun dispositionAttachment(name: String): String {
        val ascii = sanitize(name)
        val enc = java.net.URLEncoder.encode(name, "UTF-8")
            .replace("+", "%20").replace("*", "%2A").replace("%7E", "~")
        return if (ascii.all { it.code in 32..126 } && name.all { it.code in 32..126 } && ascii == name) {
            "attachment; filename=\"$ascii\""
        } else {
            "attachment; filename=\"$ascii\"; filename*=UTF-8''$enc"
        }
    }

    /** Final Stabilization (§15): explicit inline disposition for the
     *  browser-viewing surfaces (/api/media). Absence of a disposition
     *  already means inline, but stating it keeps the intent explicit for
     *  the one format (PDF) whose viewing-vs-downloading split is a spec
     *  requirement, and supplies a filename for the tab title. */
    private fun dispositionInline(name: String): String =
        "inline; filename=\"${sanitize(name)}\""

    /** Final Stabilization (§11): the Connected Devices refresh control's
     *  server-side half. A refresh tap must converge on authoritative
     *  device state, not redraw stale cache: run the session sweep NOW
     *  (reaps genuinely-departed sessions per the same TTL/grace rules the
     *  SSE ticker enforces), and bump the state version so every connected
     *  client gets an immediate payload push — each client's handling of
     *  that push touches the server, refreshing its own last-seen, so a
     *  host-resumed-from-background scenario reconciles in one round-trip
     *  instead of waiting out the next poll cycle. Read-only for modes and
     *  transfers: nothing here disconnects anyone or disturbs live work. */
    fun reconcileSessions() {
        sweepSessions()
        stateVersion.incrementAndGet()
    }

    /** Pass-7 (genuine refresh): the refresh button's FORCE half. The old
     *  reconcile-only flow answered "who does the server THINK is alive"
     *  faster, but a browser closed five seconds ago still rode its 30s
     *  visibility TTL (and a native peer its 40s expiry window) — the
     *  refresh tap visibly changed nothing, which is exactly what was
     *  reported. This does what a person pressing "refresh" means:
     *
     *  1. PROBE — bump the state version: the SSE dirty gate (250ms tick)
     *     pushes a payload to every stream immediately, which (a) elicits
     *     an immediate touch from every polling client (1s cadence) and
     *     (b) makes the write to a closed browser's socket FAIL, dropping
     *     it from sseClients right then instead of at the 30s stall-drop.
     *  2. PRUNE — one probe window later (3.5s: well past a full poll
     *     cycle + the SSE flush) any session with NO live stream and NO
     *     touch inside the window is terminated NOW with the exact same
     *     guarded-removal + session-state teardown sweepSessions() performs,
     *     so the UI's next tick (500ms) shows the true list.
     *
     *  Safety: a live client always answers the probe — SSE clients hold
     *  an open stream (hasLiveSse keeps them), XHR clients poll every
     *  second (lastSeen refreshed within the window). The only sessions
     *  pruned are the ones that produced no evidence of life across the
     *  entire window: genuinely closed browsers. Nothing disconnects
     *  anyone; a session wrongly pruned by a pathological blip simply
     *  re-registers on its next request (sessions are reborn by design). */
    fun forceRefreshSessions() {
        reconcileSessions()
        sseExec.schedule({
            try {
                val now = System.currentTimeMillis()
                for (ip in connectedIPs.keys.toList()) {
                    val lastSeen = connectedIPs[ip] ?: continue
                    if (hasLiveSse(ip)) continue
                    if (now - lastSeen < FORCE_PROBE_MS) continue
                    if (connectedIPs.remove(ip, lastSeen)) {
                        deviceModes.remove(ip)
                        clientCaps.remove(ip)
                        connectedNames.remove(ip)
                        connectedUAs.remove(ip)
                        sessionEpoch.incrementAndGet()
                    }
                }
                // Let any still-connected client (and the host UI) see the
                // pruned device list immediately.
                stateVersion.incrementAndGet()
            } catch (e: Exception) {
            }
        }, FORCE_PROBE_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
    }
    private val FORCE_PROBE_MS = 3_500L   // probe window: no stream + no touch
                                          // for this long = departed (live XHR
                                          // clients touch every ~1s; live SSE
                                          // clients are kept by hasLiveSse)

    /** Next Release Pass (§10): called after the host saves WebUI settings —
     *  bumps the state version so every connected client's next payload
     *  carries the new cfgv, refetches /api/webui-config, and rebuilds its
     *  navigation live (no restart, no page refresh). */
    fun notifyWebUiConfigChanged() {
        stateVersion.incrementAndGet()
    }
}
