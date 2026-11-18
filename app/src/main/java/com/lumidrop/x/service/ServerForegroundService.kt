package com.lumidrop.x.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.lumidrop.x.MainActivity
import com.lumidrop.x.R
import com.lumidrop.x.p2p.NativeDiscovery
import com.lumidrop.x.server.LumiDropServer
import com.lumidrop.x.settings.SettingsManager
import com.lumidrop.x.utils.NetworkUtils

class ServerForegroundService : Service() {

    var server: LumiDropServer? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        @Volatile var isRunning = false
        @Volatile var instance: ServerForegroundService? = null

        const val CH_SERVER      = "wsx_server_ch"
        const val CH_PROGRESS    = "wsx_progress_ch"
        const val CH_PENDING     = "wsx_pending_ch"
        const val CH_CONN        = "wsx_conn_ch"
        const val CH_NATIVE_PUSH = "wsx_native_push_ch"
        const val NOTIF_SERVER_ID         = 1001
        const val NOTIF_UPLOAD_ID         = 1002
        const val NOTIF_PENDING_ID        = 1003
        const val NOTIF_CONN_ID           = 1004
        const val NOTIF_NATIVE_PENDING_ID = 1005
        const val NOTIF_NATIVE_DONE_ID    = 1006
        const val NOTIF_NATIVE_SENT_ID    = 1007
        // Phase 8 (§8.3): max cadence for the notification's network-interface
        // rescan behind refreshNotification's dirty gate.
        const val NOTIF_URL_RESCAN_MS = 5000L

        const val ACTION_START                = "ACTION_START"
        const val ACTION_STOP                 = "ACTION_STOP"
        const val ACTION_UPDATE               = "ACTION_UPDATE"
        const val ACTION_UPLOAD_PROGRESS      = "ACTION_UPLOAD_PROGRESS"
        const val ACTION_PENDING_UPLOAD       = "ACTION_PENDING_UPLOAD"
        const val ACTION_PENDING_UPLOAD_CLEAR = "ACTION_PENDING_UPLOAD_CLEAR"
        const val ACTION_PENDING_CONN         = "ACTION_PENDING_CONN"
        const val ACTION_PENDING_CONN_CLEAR   = "ACTION_PENDING_CONN_CLEAR"
        const val ACTION_PENDING_NATIVE_PUSH       = "ACTION_PENDING_NATIVE_PUSH"
        const val ACTION_PENDING_NATIVE_PUSH_CLEAR = "ACTION_PENDING_NATIVE_PUSH_CLEAR"
        const val ACTION_NATIVE_PUSH_DONE          = "ACTION_NATIVE_PUSH_DONE"
        const val ACTION_NATIVE_PUSH_SENT          = "ACTION_NATIVE_PUSH_SENT"

        /** Call from anywhere to send an upload progress update to the notification. */
        fun notifyUpload(ctx: Context, name: String, progressPct: Int, done: Boolean = false) {
            ctx.startService(Intent(ctx, ServerForegroundService::class.java).apply {
                action = ACTION_UPLOAD_PROGRESS
                putExtra("name", name); putExtra("progress", progressPct); putExtra("done", done)
            })
        }

        /** Alerts the host that a device is waiting for Accept/Decline on an incoming file. */
        fun notifyPendingUpload(ctx: Context, name: String, fromName: String, pendingCount: Int) {
            ctx.startService(Intent(ctx, ServerForegroundService::class.java).apply {
                action = ACTION_PENDING_UPLOAD
                putExtra("name", name); putExtra("from", fromName); putExtra("count", pendingCount)
            })
        }

        /** Clears the pending-upload alert once nothing is left awaiting a decision. */
        fun clearPendingUploadNotif(ctx: Context) {
            ctx.startService(Intent(ctx, ServerForegroundService::class.java).apply {
                action = ACTION_PENDING_UPLOAD_CLEAR
            })
        }

        /** Alerts the host that a device wants to connect and needs Accept/Reject. */
        fun notifyPendingConnRequest(ctx: Context, pendingCount: Int) {
            ctx.startService(Intent(ctx, ServerForegroundService::class.java).apply {
                action = ACTION_PENDING_CONN
                putExtra("count", pendingCount)
            })
        }

        /** Clears the connection-request alert once nothing is left awaiting a decision. */
        fun clearPendingConnNotif(ctx: Context) {
            ctx.startService(Intent(ctx, ServerForegroundService::class.java).apply {
                action = ACTION_PENDING_CONN_CLEAR
            })
        }

        /** Alerts the host that a native peer wants to push a batch of files
         *  and needs Accept/Decline — the batch-level counterpart of
         *  notifyPendingUpload above (Phase 14). */
        fun notifyPendingNativePush(ctx: Context, fromName: String, fileCount: Int, pendingBatches: Int) {
            ctx.startService(Intent(ctx, ServerForegroundService::class.java).apply {
                action = ACTION_PENDING_NATIVE_PUSH
                putExtra("from", fromName); putExtra("files", fileCount); putExtra("count", pendingBatches)
            })
        }

        /** Clears the pending-native-push alert once nothing is left awaiting a decision. */
        fun clearPendingNativePushNotif(ctx: Context) {
            ctx.startService(Intent(ctx, ServerForegroundService::class.java).apply {
                action = ACTION_PENDING_NATIVE_PUSH_CLEAR
            })
        }

        /** A native peer's whole folder/batch push finished landing on this
         *  device — one summary notification rather than one per file. */
        fun notifyNativePushComplete(ctx: Context, fromName: String, fileCount: Int) {
            ctx.startService(Intent(ctx, ServerForegroundService::class.java).apply {
                action = ACTION_NATIVE_PUSH_DONE
                putExtra("from", fromName); putExtra("files", fileCount)
            })
        }

        /** Mirror of [notifyNativePushComplete] for the SENDING side (Phase
         *  1 continuation, item 2's "clean, modern confirmation" once Auto
         *  Send actually finishes delivering to a peer) — a system
         *  notification rather than a Toast so it still reaches the person
         *  even if they've long since left the Send screen, which matters
         *  more than ever now that a batch can sit waiting for an eligible
         *  device before it ever starts (see NativePushClient.sendOrQueueForPeer). */
        fun notifyNativePushSent(ctx: Context, toName: String, fileCount: Int) {
            ctx.startService(Intent(ctx, ServerForegroundService::class.java).apply {
                action = ACTION_NATIVE_PUSH_SENT
                putExtra("to", toName); putExtra("files", fileCount)
            })
        }

        /**
         * Quick Text Transfer confirmation — a plain Toast rather than a
         * full notification-channel entry, since this is a transient,
         * fire-and-forget confirmation ("text received and copied") with no
         * further action for the host to take, unlike the pending-upload
         * alert above which needs persistent Accept/Decline actions. Must
         * already be called from the main thread (apiTextSend hops there
         * via Handler.post before calling this).
         */
        fun notifyTextReceived(ctx: Context, fromName: String) {
            try {
                android.widget.Toast.makeText(ctx,
                    "\uD83D\uDCCB Text received from $fromName — copied to clipboard",
                    android.widget.Toast.LENGTH_LONG).show()
            } catch (e: Exception) { /* best-effort UI feedback only */ }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // GOAT-PASS 10 (background continuity): hand the engine the app
        // context for WakeGuard's in-flight-only partial wake lock (see
        // WakeGuard) — transfers keep flowing while the app sits minimized
        // in the recent-apps drawer on Doze/OEM CPU-throttling builds.
        com.lumidrop.x.transfer.TransferManager.appContextRef = applicationContext
        createChannels()
        when (intent?.action ?: ACTION_START) {
            ACTION_START  -> startServerInternal()
            ACTION_STOP   -> stopSelf()
            ACTION_UPDATE -> refreshNotification()
            ACTION_UPLOAD_PROGRESS -> {
                val name  = intent?.getStringExtra("name")     ?: "file"
                val prog  = intent?.getIntExtra("progress", 0) ?: 0
                val done  = intent?.getBooleanExtra("done", false) ?: false
                // GOAT-PASS 1 threaded the sibling check in when the gate
                // briefly allowed parallel uploads. GOAT-PASS 2 returns the
                // gate to exactly 1, so "another upload still streaming"
                // can no longer happen from THIS host's own admission — but
                // the check stays: it is correct either way, costs one map
                // scan per finished upload, and keeps the notification
                // semantics decoupled from the gate constant. The finishing
                // upload's own tid is excluded because the terminal state
                // may not have landed yet when this intent is handled
                // (notifyService fires just before complete()/fail()).
                if (done) {
                    val tid = intent?.getStringExtra("tid")
                    if (com.lumidrop.x.transfer.TransferManager.activeUploadsOtherThan(tid) == 0)
                        cancelNotif(NOTIF_UPLOAD_ID)
                } else showUploadNotif(name, prog)
            }
            ACTION_PENDING_UPLOAD -> {
                val name  = intent?.getStringExtra("name") ?: "file"
                val from  = intent?.getStringExtra("from") ?: "a device"
                val count = intent?.getIntExtra("count", 1) ?: 1
                showPendingUploadNotif(name, from, count)
            }
            ACTION_PENDING_UPLOAD_CLEAR -> cancelNotif(NOTIF_PENDING_ID)
            ACTION_PENDING_CONN -> {
                val count = intent?.getIntExtra("count", 1) ?: 1
                showPendingConnNotif(count)
            }
            ACTION_PENDING_CONN_CLEAR -> cancelNotif(NOTIF_CONN_ID)
            ACTION_PENDING_NATIVE_PUSH -> {
                val from  = intent?.getStringExtra("from") ?: "a device"
                val files = intent?.getIntExtra("files", 1) ?: 1
                val count = intent?.getIntExtra("count", 1) ?: 1
                showPendingNativePushNotif(from, files, count)
            }
            ACTION_PENDING_NATIVE_PUSH_CLEAR -> cancelNotif(NOTIF_NATIVE_PENDING_ID)
            ACTION_NATIVE_PUSH_DONE -> {
                val from  = intent?.getStringExtra("from") ?: "a device"
                val files = intent?.getIntExtra("files", 1) ?: 1
                showNativePushDoneNotif(from, files)
            }
            ACTION_NATIVE_PUSH_SENT -> {
                val to    = intent?.getStringExtra("to") ?: "device"
                val files = intent?.getIntExtra("files", 1) ?: 1
                showNativePushSentNotif(to, files)
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        // GOAT-PASS 10: no leaked wake lock can outlive the service.
        try { com.lumidrop.x.transfer.TransferManager.appContextRef = null
              com.lumidrop.x.transfer.WakeGuard.releaseAll() } catch (e: Exception) { /* never fatal */ }
        tearDown()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        super.onDestroy()
    }

    /**
     * Pass-5 (R3 — full close from recents): swiping the app away from the
     * recents/app-switcher screen now means EXACTLY that — a complete,
     * unconditional shutdown of everything this app is doing. The old
     * conditional ("keep running while any transfer is in flight") was the
     * product decision being reversed here: the server, every in-flight
     * transfer, native discovery, the Wi-Fi lock, and every notification
     * all stop NOW. stopSelf() funnels into onDestroy() → tearDown(),
     * which fails/cancels all active transfers, stops the HTTP server and
     * native discovery, releases the Wi-Fi lock, and clears the persistent
     * + progress + pending notifications; the process itself is then ended
     * a beat later so no cached, socket-holding, half-dead process survives
     * the swipe either (a plain stopSelf can leave an empty cached process
     * around for the OS's own good-pleasure timing — not what "must fully
     * close and stop everything" asked for).
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // ── Pass-16 (instant removal on Recent-Apps close): say GOODBYE ─────
        // BEFORE anything tears down. Every other LumiDrop device on the LAN
        // drops this device's Connected Devices entry the moment the packet
        // lands (NativeDiscovery.handleBeacon's bye branch) — instead of
        // keeping it greyed-out for the 12s/40s staleness windows. The 400ms
        // kill delay below comfortably covers the two quick UDP sends this
        // fires; a lost packet degrades gracefully to the old expiry sweep.
        try { com.lumidrop.x.p2p.NativeDiscovery.sendGoodbye() } catch (e: Exception) {}
        stopSelf()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            // ── Pass-17 (instant removal, second chance): one last goodbye ──
            // right before the process dies — a packet lost on the first send
            // (busy hotspot, a moment of congestion) was the one remaining gap
            // in "swiping the app away removes it everywhere immediately".
            // Costs nothing: best-effort, and the peers' probe-based fast
            // expiry (NativeDiscovery) is the backstop behind it either way.
            try { com.lumidrop.x.p2p.NativeDiscovery.sendGoodbye() } catch (e: Exception) {}
            try {
                android.os.Process.killProcess(android.os.Process.myPid())
                Runtime.getRuntime().exit(0)
            } catch (e: Exception) { /* already gone — nothing left to do */ }
        }, 400L)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Server start / stop ───────────────────────────────────────────────────

    private fun startServerInternal() {
        if (server != null) return
        // Pass-11 (Required Implementation 3): the Hotspot / Wi-Fi / LAN toggle
        // is REMOVED — local network sharing is permanently enabled, so the
        // "both local kinds off → don't start / stopSelf" boundary is gone
        // with it. The server always starts; native discovery always
        // advertises on LAN.
        val port = SettingsManager.getPort(this)
        // Reliability pass §3: concurrency is no longer a setting — the
        // gates are permanently fixed (GOAT-PASS 2: exactly ONE permit per
        // direction, by explicit specification — see
        // TransferManager.TRANSFER_CONCURRENCY's doc). A restart still
        // drains and rebuilds both gates to a clean slate via resetGates()
        // so a pre-restart worker's late release no-ops against the cleared
        // holder maps instead of inflating the fresh gates.
        com.lumidrop.x.transfer.TransferManager.resetGates()
        try {
            server = LumiDropServer(this, port).also { it.start(0, false) }
            instance = this; isRunning = true
        } catch (e: Exception) {
            stopSelf(); return
        }
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "LumiDropX::wifi")
            .also { it.setReferenceCounted(false); it.acquire() }
        startForeground(NOTIF_SERVER_ID, buildServerNotif(computeNotifUrl(port), 0))
        // Pass-11: LAN advertisement is unconditional — local sharing is
        // permanently on (the toggle is gone).
        NativeDiscovery.start(applicationContext)
        // ── GOAT-PASS 9 (§5 — live synchronization): the MediaStore + ──────────
        // receive-root observers ride the same always-on lifecycle as the
        // server and discovery — new files/folders/media from ANY app surface
        // in the native sheets and the web views without a restart.
        try { com.lumidrop.x.media.LibrarySync.start(applicationContext) } catch (e: Exception) {
        }
        // (Pass-8 follow-up: the Internet-sharing relay tunnel that used to
        // be armed here is removed with the whole Internet Sharing system —
        // the app is strictly LAN-based now.)
        // Pass-7: the pass-6 Auto-Send engine armed here; it is deleted with
        // the whole watched-folder system — Auto-Send is now a plain setting
        // read at selection time (SendFragment.stageOrSendItems), exactly
        // like Auto-Accept is read when an offer arrives. No service-side
        // lifecycle remains.
    }

    private fun tearDown() {
        // Pass-5 (R3): EVERY notification this service can currently be
        // showing is cancelled here, not just the upload progress bar — a
        // full close must leave zero orphaned notifications behind (the
        // persistent server notification itself is removed by
        // stopForeground(STOP_FOREGROUND_REMOVE) in onDestroy).
        cancelNotif(NOTIF_UPLOAD_ID)
        cancelNotif(NOTIF_PENDING_ID)
        cancelNotif(NOTIF_CONN_ID)
        cancelNotif(NOTIF_NATIVE_PENDING_ID)
        cancelNotif(NOTIF_NATIVE_DONE_ID)
        cancelNotif(NOTIF_NATIVE_SENT_ID)
        com.lumidrop.x.transfer.TransferManager.failAllActive()
        NativeDiscovery.stop()
        try { com.lumidrop.x.media.LibrarySync.stop() } catch (e: Exception) { /* observers gone with the process anyway */ }
        // Pass-7: nothing auto-send-related to tear down anymore — the
        // pass-6 engine (observers, polls, ledger) is deleted; full-close
        // discipline for transfers/discovery/notifications above is all
        // this service owns now.
        if (wifiLock?.isHeld   == true) wifiLock?.release();      wifiLock      = null
        server?.stop(); server = null
        isRunning = false; instance = null
    }

    /**
     * Called by [RestartServerReceiver] when SettingsActivity saves a change
     * to a server-dependent setting (port, password, connection confirmation).
     * Performs a graceful in-place restart:
     *  1. Mark any in-flight transfers as failed so the Transfers tab doesn't
     *     show stuck-forever progress bars for transfers the old server was
     *     mid-stream on.
     *  2. Stop the old NanoHTTPD instance.
     *  3. Read freshly-committed settings.
     *  4. Start a new NanoHTTPD instance.
     *  5. Refresh the foreground notification so the URL/port line is correct.
     */
    fun restartServerForSettingsChange() {
        // Pass-11 (Required Implementation 3): no sharing-toggle boundary
        // exists anymore — a restart always restarts.
        com.lumidrop.x.transfer.TransferManager.failAllActive()
        server?.stop(); server = null
        val port = SettingsManager.getPort(this)
        // Reliability pass §3 → GOAT-PASS 2: fixed gates (see
        // startServerInternal) — exactly one transfer per direction.
        com.lumidrop.x.transfer.TransferManager.resetGates()
        try {
            server = LumiDropServer(this, port).also { it.start(0, false) }
            isRunning = true; instance = this
            refreshNotification(force = true)
        } catch (e: Exception) {
            isRunning = false; instance = null; stopSelf()
        }
    }

    // ── Notification: premium persistent server status ────────────────────────

    private fun buildServerNotif(url: String, clients: Int): Notification {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT

        val openPi = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }, flags)
        val stopPi = PendingIntent.getService(this, 1,
            Intent(this, ServerForegroundService::class.java).apply { action = ACTION_STOP },
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)

        val clientLine = when (clients) {
            0    -> "Ready for connections"
            1    -> "1 device connected"
            else -> "$clients devices connected"
        }

        return NotificationCompat.Builder(this, CH_SERVER)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("LumiDrop X  -  Running")
            .setContentText(url)
            .setSubText(clientLine)
            .setStyle(NotificationCompat.BigTextStyle()
                .bigText("$url\n$clientLine")
                .setBigContentTitle("LumiDrop X  -  Running"))
            .setContentIntent(openPi)
            .addAction(NotificationCompat.Action(0, "Stop Server", stopPi))
            .setOngoing(true)
            .setShowWhen(false)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setColor(0xFF7C5CFC.toInt())
            .setColorized(true)
            .build()
    }

    // ── Phase 8 (§8.3/§8.4): notification refresh used to be driven by the
    // Send tab's 500ms UI poll — and every single call enumerated the
    // device's network interfaces (NetworkInterface.getNetworkInterfaces +
    // ConnectivityManager.allNetworks), re-read 3+ SharedPreferences keys,
    // and rebuilt + re-posted the whole Notification on the MAIN thread,
    // twice per second, for a notification whose content almost never
    // changes. Now: the interface scan runs at most once every 5s on a
    // dedicated background executor, its result is cached, and the notify()
    // itself only fires when the (url, clients) content key actually
    // changed. The 500ms caller degenerates to two volatile reads.
    private val notifExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "wsx-notif").apply { isDaemon = true }
    }
    @Volatile private var cachedNotifUrl: String? = null
    @Volatile private var lastNotifClients: Int = -1
    @Volatile private var lastIfScanAtMs: Long = 0L

    private fun computeNotifUrl(port: Int): String {
        val enabled = NetworkUtils.getEnabledInterfaces(this)
        return when {
            enabled.isEmpty() -> "No network currently enabled in Settings"
            enabled.size == 1 -> "http://${enabled[0].ip}:$port"
            else -> enabled.joinToString("   ") { "${it.label}: http://${it.ip}:$port" }
        }
    }

    fun refreshNotification(force: Boolean = false) {
        if (!isRunning) return
        val clients = server?.connectedClients?.get() ?: 0
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && clients == lastNotifClients && cachedNotifUrl != null &&
            now - lastIfScanAtMs < NOTIF_URL_RESCAN_MS) return
        notifExec.execute {
            try {
                if (cachedNotifUrl == null || force || android.os.SystemClock.elapsedRealtime() - lastIfScanAtMs >= NOTIF_URL_RESCAN_MS) {
                    lastIfScanAtMs = android.os.SystemClock.elapsedRealtime()
                    cachedNotifUrl = computeNotifUrl(SettingsManager.getPort(this@ServerForegroundService))
                }
                val url = cachedNotifUrl ?: return@execute
                if (force || url != lastPostedUrl || clients != lastNotifClients) {
                    lastPostedUrl = url
                    lastNotifClients = clients
                    (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                        .notify(NOTIF_SERVER_ID, buildServerNotif(url, clients))
                }
            } catch (e: Exception) {
                // A failed refresh must never take anything else down; the
                // next gated attempt retries on its own schedule.
            }
        }
    }
    @Volatile private var lastPostedUrl: String? = null

    // ── Upload progress notification ──────────────────────────────────────────

    private fun showUploadNotif(name: String, progressPct: Int) {
        val n = NotificationCompat.Builder(this, CH_PROGRESS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Uploading to device")
            .setContentText(name)
            .setProgress(100, progressPct.coerceIn(0, 100), progressPct <= 0)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(0xFF7C5CFC.toInt())
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_UPLOAD_ID, n)
    }

    private fun cancelNotif(id: Int) =
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).cancel(id)

    // ── Pending-upload alert: a device is waiting for Accept/Decline ──────────
    // Unlike the silent, low-priority progress notification above, this one is
    // meant to actually get the host's attention — a decision is required, and
    // (unlike normal progress) nothing proceeds until the host makes it.

    private fun showPendingUploadNotif(name: String, from: String, count: Int) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val openPi = PendingIntent.getActivity(this, 2,
            Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }, flags)

        val title = if (count > 1) "$count files waiting for approval" else "File waiting for approval"
        val text  = if (count > 1) "From $from and others — tap to review" else "\"$name\" from $from — tap to review"

        val n = NotificationCompat.Builder(this, CH_PENDING)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(0xFF7C5CFC.toInt())
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_PENDING_ID, n)
    }

    // ── Connection-request alert: a device wants to join and needs a decision ──

    private fun showPendingConnNotif(count: Int) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val openPi = PendingIntent.getActivity(this, 3,
            Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }, flags)

        val title = if (count > 1) "$count devices want to connect" else "Device wants to connect"
        val text  = "Tap to review and Accept or Reject"

        val n = NotificationCompat.Builder(this, CH_CONN)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setColor(0xFF7C5CFC.toInt())
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_CONN_ID, n)
    }

    // ── Native push alerts (Phase 14): a peer wants to send a batch of files ──

    private fun showPendingNativePushNotif(from: String, files: Int, count: Int) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val openPi = PendingIntent.getActivity(this, 4,
            Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }, flags)

        val title = if (count > 1) "$count devices want to send files" else "Incoming files from $from"
        val fileWord = if (files == 1) "1 file" else "$files files"
        val text  = if (count > 1) "$fileWord from $from and others — tap to review"
                    else "$fileWord — tap to review"

        val n = NotificationCompat.Builder(this, CH_NATIVE_PUSH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(0xFF7C5CFC.toInt())
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_NATIVE_PENDING_ID, n)
    }

    private fun showNativePushDoneNotif(from: String, files: Int) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val openPi = PendingIntent.getActivity(this, 5,
            Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }, flags)
        val fileWord = if (files == 1) "1 file" else "$files files"

        val n = NotificationCompat.Builder(this, CH_NATIVE_PUSH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Received from $from")
            .setContentText("$fileWord saved")
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(0xFF7C5CFC.toInt())
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_NATIVE_DONE_ID, n)
    }

    /** Mirror of [showNativePushDoneNotif] for the sending side — see
     *  [notifyNativePushSent]'s doc comment for why this is a real
     *  notification rather than a Toast. Own notif id (NOTIF_NATIVE_SENT_ID)
     *  so a rapid send-then-receive (or vice versa) can't have one
     *  overwrite the other before either is seen. */
    private fun showNativePushSentNotif(to: String, files: Int) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        else PendingIntent.FLAG_UPDATE_CURRENT
        val openPi = PendingIntent.getActivity(this, 6,
            Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP) }, flags)
        val fileWord = if (files == 1) "1 file" else "$files files"

        val n = NotificationCompat.Builder(this, CH_NATIVE_PUSH)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle("Sent to $to")
            .setContentText("$fileWord delivered")
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setColor(0xFF7C5CFC.toInt())
            .build()
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_NATIVE_SENT_ID, n)
    }

    // ── Notification channels (API 26+) ──────────────────────────────────────

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            CH_SERVER, "Server Status", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Persistent notification while LumiDrop X server is active"
            setShowBadge(false); enableLights(false); enableVibration(false)
        })
        nm.createNotificationChannel(NotificationChannel(
            CH_PROGRESS, "Transfer Progress", NotificationManager.IMPORTANCE_LOW).apply {
            description = "Upload and folder-zip progress"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(
            CH_PENDING, "Incoming File Requests", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Alerts when a device wants to send you a file and your approval is needed"
            setShowBadge(true)
        })
        nm.createNotificationChannel(NotificationChannel(
            CH_CONN, "Connection Requests", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Alerts when a new device wants to connect and your approval is needed"
            setShowBadge(true)
        })
        nm.createNotificationChannel(NotificationChannel(
            CH_NATIVE_PUSH, "Nearby Device Transfers", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Alerts for files sent directly from another LumiDrop X device"
            setShowBadge(true)
        })
    }
}
