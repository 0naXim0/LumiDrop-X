package com.lumidrop.x.p2p

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.lumidrop.x.settings.SettingsManager
import com.lumidrop.x.utils.NetworkUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Phase 14 — native LumiDrop X device discovery.
 *
 * DISCOVERY TECHNOLOGY — mDNS/NSD vs UDP broadcast vs hybrid:
 *
 * mDNS (NsdManager) was evaluated and rejected as the primary mechanism:
 *  - This project already removed a prior mDNS-based system for browser
 *    clients (see SettingsManager's "Permanent URL / mDNS" comment) after
 *    finding it unreliable — a direct, documented data point against
 *    trusting mDNS again on this exact device/network matrix.
 *  - More importantly for THIS phase: LumiDrop X's primary topology is one
 *    phone's OWN WiFi hotspot with other phones joined to it. Many Android
 *    hotspot (AP) implementations do not forward multicast traffic between
 *    connected stations at all — this is a widely-documented limitation of
 *    Android's tethering stack, not a per-app bug — which would make mDNS
 *    silently fail on exactly the network shape this app is built around.
 *  - NsdManager also needs CHANGE_WIFI_MULTICAST_STATE, a multicast lock
 *    held for the app's whole discovery lifetime, and has a long history of
 *    OEM-specific quirks (MIUI/Xiaomi in particular restricts background
 *    multicast aggressively).
 * UDP broadcast was chosen instead:
 *  - Works on hotspot AP subnets where multicast doesn't (broadcast is
 *    switched, not routed by IGMP snooping/multicast rules).
 *  - Needs zero additional manifest permissions — INTERNET / ACCESS_WIFI_STATE
 *    / CHANGE_WIFI_STATE are already declared for the HTTP server.
 *  - Trivial to reason about and keep alive: one DatagramSocket per
 *    direction, no service-registration lifecycle to manage.
 *
 * SPEED: three layers work together to get real-world discovery well under
 * 1 second without raising the steady-state battery cost much:
 *  1. Startup burst — a handful of beacons a couple hundred ms apart the
 *     moment discovery starts, instead of gambling everything on one packet
 *     (WiFi/hotspot links occasionally drop the very first packet or two
 *     while association settles).
 *  2. Reply-on-first-sight — the moment ANY beacon arrives from a peer this
 *     device has never seen before, it fires one beacon straight back
 *     immediately rather than waiting for its own next scheduled tick. This
 *     is what actually makes the common case (opening the app while other
 *     devices are already running) fast: the new device's burst reaches
 *     everyone already listening almost instantly, and each of them replies
 *     immediately in turn — the whole handshake finishes in about one LAN
 *     round-trip, not up to a whole beacon interval.
 *  3. A shorter steady-state interval (1.5s, down from an initial 2.5s) as a
 *     safety net for the case a first packet is lost and no reply-trigger
 *     fires — this is the only one of the three with an ongoing battery
 *     cost, which is why it only moved modestly rather than dropping further.
 *
 * WIRE FORMAT: one-line JSON, sent to both the limited broadcast address and
 * the subnet-directed broadcast address (belt-and-suspenders — some hotspot
 * / router firmwares are inconsistent about which one they actually deliver):
 *   {"t":"whx1","id":<stable device uuid>,"name":<display name>,
 *    "port":<this device's LumiDropServer port>,"ver":<app versionCode>}
 *
 * Every running instance is simultaneously a broadcaster and a listener —
 * there is no separate "server" role for discovery itself.
 */
object NativeDiscovery {

    const val DISCOVERY_PORT = 47624
    private const val BEACON_INTERVAL_MS = 1500L
    private const val STARTUP_BURST_COUNT = 4
    private const val STARTUP_BURST_GAP_MS = 200L
    private const val LISTEN_SOCKET_TIMEOUT_MS = 3000
    // Pass-3 (R3): a failed listen bind (the port can be briefly held during
    // a fast service restart race) used to kill discovery SILENTLY —
    // running=false took broadcasting down with it and the device vanished
    // from every peer's list until the next full start(). Retry instead.
    private const val LISTEN_BIND_ATTEMPTS = 5
    private const val LISTEN_BIND_RETRY_MS = 1000L
    // Pass-3 (R3): the foreground-return re-announcement burst — see
    // onAppForeground().
    private const val FOREGROUND_BURST_COUNT = 3
    private const val FOREGROUND_BURST_GAP_MS = 250L
    /** No beacon in this long: still shown, but the UI may grey it out.
     *  Pass-3 (R3): 8s → 12s — background Doze throttling and hotspot
     *  power-save routinely stretch beacon gaps past 8s while the peer is
     *  still genuinely connected; greying out that early read as the
     *  device having problems when it hadn't. */
    const val PEER_STALE_MS = 12_000L
    /** No beacon in this long: dropped from the peer map entirely.
     *  Pass-3 (R3): 15s → 40s — at the normal 1.5s cadence this is still
     *  ~26 missed beacons, so a genuinely-gone peer clears quickly, but a
     *  peer that backgrounded briefly (OEM service throttling, Wi-Fi
     *  reassociation, hotspot power-save) KEEPS its card instead of
     *  vanishing and reappearing — the reported "disappearing connected
     *  device cards when a device enters the background and returns".
     *  40s comfortably covers the longest transient gap a live foreground
     *  service realistically produces; anything longer really is gone. */
    const val PEER_EXPIRE_MS = 40_000L
    private const val MAGIC = "whx1"

    class NativePeer(
        val deviceId: String,
        ip: String,
        port: Int,
        displayName: String,
        lastSeenAt: Long,
        appVersion: Int
    ) {
        @Volatile var ip: String = ip
        @Volatile var port: Int = port
        @Volatile var displayName: String = displayName
        @Volatile var lastSeenAt: Long = lastSeenAt
        @Volatile var appVersion: Int = appVersion

        val isStale: Boolean get() = System.currentTimeMillis() - lastSeenAt > PEER_STALE_MS
        val baseUrl: String get() = "http://$ip:$port"
    }

    fun interface Listener {
        fun onPeersChanged(peers: List<NativePeer>)
    }

    private val peers = ConcurrentHashMap<String, NativePeer>() // keyed by deviceId
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var scope: CoroutineScope? = null
    private var sendSocket: DatagramSocket? = null
    private var listenSocket: DatagramSocket? = null
    @Volatile private var running = false
    @Volatile private var appContext: Context? = null

    // ── Pass-10 (real-time connected-device cleanup): network watch ────────
    // The LAN this device is currently on (its own local IP), re-derived on
    // every expiry pass (3s). The moment it DISAPPEARS (Wi-Fi turned off,
    // hotspot disabled — no LAN-family interface left) or CHANGES (moved to
    // a different network), every peer in the map is by definition
    // unreachable: none of their beacons can arrive on an interface that no
    // longer exists, and any entry we keep would be a stale "connected"
    // card for up to the full 40s expiry window — the exact "turned the
    // hotspot/Wi-Fi off and the device stayed in Connected Devices"
    // report. Clearing immediately + notifying listeners updates the UI on
    // the very next render tick (~500ms worst case), which is what
    // "disappears immediately" means in practice for a polled list.
    @Volatile private var lastKnownLanIp: String? = null

    // Resolved exactly ONCE per start() and shared by every coroutine below —
    // see start()'s comment for why. Never read via myDeviceId(context) from
    // inside this object's own loops after that; myDeviceId() stays public
    // only for OTHER code (e.g. NativePushClient's outgoing manifest) that
    // legitimately needs "my id" well after discovery is already running,
    // where SettingsManager's own synchronized getOrCreateDeviceId() already
    // guarantees the same stable value.
    @Volatile private var myId: String = ""

    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    fun isRunning(): Boolean = running

    fun myDeviceId(ctx: Context): String = SettingsManager.getOrCreateDeviceId(ctx)

    /** "Use the real device name by default if no custom name has been configured." */
    fun myDisplayName(ctx: Context): String {
        val custom = SettingsManager.getDiscoveryName(ctx)
        return custom.ifBlank { Build.MODEL ?: "Android Device" }
    }

    /** Read via PackageManager rather than BuildConfig — avoids depending on
     *  buildFeatures.buildConfig being enabled in the module's build.gradle. */
    private fun appVersionCode(context: Context): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    } catch (e: PackageManager.NameNotFoundException) { 1 }

    @Synchronized
    fun start(context: Context) {
        if (running) return
        val appCtx = context.applicationContext
        appContext = appCtx

        // Resolved ONCE, here, before either loop below launches, so both
        // share the exact same value for the lifetime of this session.
        //
        // BUG FIX: previously broadcastLoop and listenLoop each independently
        // called SettingsManager.getOrCreateDeviceId(context) at their own
        // startup. On a device's very first-ever run — nothing persisted
        // yet — both coroutines could race inside that function's
        // read-then-write: both read "nothing saved", both generate their
        // OWN fresh random UUID, both write, last write wins. The two
        // coroutines then silently disagreed about "my own id" for the rest
        // of that session: broadcastLoop kept sending one value while
        // listenLoop filtered against the other. The moment this device's
        // own beacon reached its own listenLoop (a real, common occurrence —
        // plenty of routers/hotspots reflect broadcast traffic back to the
        // sender), the id comparison failed and the device added itself as
        // a "nearby" peer. Resolving myId exactly once here — before any
        // loop starts — makes that mismatch structurally impossible.
        // SettingsManager.getOrCreateDeviceId() itself is now also
        // @Synchronized as defense in depth, in case any future caller ever
        // invokes it concurrently from somewhere else.
        myId = SettingsManager.getOrCreateDeviceId(appCtx)
        lastKnownLanIp = NetworkUtils.getLocalIpAddress(appCtx)

        running = true
        val job = SupervisorJob()
        val sc = CoroutineScope(Dispatchers.IO + job)
        scope = sc
        sc.launch { broadcastLoop(appCtx) }
        sc.launch { listenLoop() }
        sc.launch { expiryLoop() }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        // ── Pass-16 (instant removal): announce the departure BEFORE the ──
        // listener/sockets tear down, so peers drop this device from their
        // Connected Devices cards immediately instead of waiting out the
        // stale/expire windows. (appContext is read before it's nulled below.)
        try { sendGoodbye() } catch (_: Exception) {}
        running = false
        scope?.cancel()
        scope = null
        try { sendSocket?.close() } catch (_: Exception) {}
        try { listenSocket?.close() } catch (_: Exception) {}
        sendSocket = null
        listenSocket = null
        appContext = null
        myId = ""
        // GOAT v2 fix (item 9 — connected devices disappearing): this used
        // to wipe every known peer the instant discovery stopped, no
        // matter WHY it stopped. NativeDiscovery only ever stops via
        // ServerForegroundService.tearDown() -> onDestroy(), which fires
        // both when the user genuinely turns sharing off AND whenever the
        // OS itself kills/recreates the foreground service — several
        // Android OEMs (Transsion/Tecno among them) are well documented
        // for doing this to foreground services more aggressively than
        // stock Android, "foreground" notwithstanding. A quick kill+
        // restart used to look, from the UI's perspective, like every
        // connected device vanished and then rebuilt itself a moment
        // later — exactly "disappear and reappear after sometime" even
        // though both ends were genuinely still connected the whole time.
        //
        // Simply not clearing here means that story is now told by the
        // SAME stale (8s, greyed out) / expire (15s, actually removed)
        // machinery snapshot()/isStale already use for the ordinary case
        // of a peer's beacons genuinely stopping arriving — a fast
        // restart re-populates peers via the startup burst + reply-on-
        // first-sight before anything has even gone stale, so the UI sees
        // no visible change at all, and a real, lasting stop still fades
        // every peer out gracefully over the same familiar window instead
        // of vanishing them on an unrelated internal implementation
        // detail. peers is intentionally left for the next start() (or
        // the natural expiry timers) to deal with, not this function.
        notifyListeners()
    }

    /** Only non-expired peers, sorted for stable display order. */
    fun snapshot(): List<NativePeer> {
        val now = System.currentTimeMillis()
        return peers.values.filter { now - it.lastSeenAt <= PEER_EXPIRE_MS }
            .sortedBy { it.displayName.lowercase() }
    }

    fun findPeer(deviceId: String): NativePeer? = peers[deviceId]
    fun findPeerByIp(ip: String): NativePeer? = peers.values.firstOrNull { it.ip == ip }

    /** Fires one beacon immediately outside the normal schedule — wired to
     *  the Nearby Devices card's manual refresh button. Uses its own
     *  short-lived socket (see sendOneOffBeacon) so it can never race a
     *  concurrent send() from broadcastLoop's own socket. */
    fun requestImmediateBeacon() {
        val ctx = appContext ?: return
        if (!running) return
        sendOneOffBeacon(ctx)
    }

    /** Pass-7 (genuine refresh): the manual refresh button's native half.
     *  Ordinarily a peer that stopped beaconing (app swiped away, phone
     *  left) lingers greyed-out through the whole 40s expiry window —
     *  correct for an AUTOMATIC sweep (backgrounded-but-alive apps can
     *  legitimately stretch beacon gaps past 12s under Doze throttling),
     *  but not what a person pressing "refresh" is asking for. Here,
     *  anything already past the STALE threshold (12s silent = 8 missed
     *  beacons at the normal 1.5s cadence) is dropped NOW; a live
     *  foreground peer (≤ ~2s silence) sails through untouched, and even a
     *  wrongly-dropped throttled peer re-announces itself on its next
     *  beacon (reply-on-first-sight rebuilds it instantly). */
    fun forceExpireStalePeers() {
        val now = System.currentTimeMillis()
        var removed = false
        val it = peers.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (now - e.value.lastSeenAt > PEER_STALE_MS) {
                it.remove()
                removed = true
            }
        }
        if (removed) notifyListeners()
    }

    /** Pass-3 (R3): called when the app returns to the foreground (see
     *  MainActivity.onResume). A short re-announcement burst so peers
     *  that stopped hearing from us while we were backgrounded — or that
     *  expired us during a hotspot / Wi-Fi hiccup — re-learn this device
     *  within a fraction of a second instead of up to a full beacon
     *  interval; their reply-on-first-sight beacons refresh OUR map of
     *  them just as fast. Recovery from lifecycle changes and temporary
     *  network interruptions becomes symmetric and near-instant on both
     *  ends, instead of the old one-sided wait for the next scheduled
     *  beacon (or the full expiry window on the side that gave up). */
    fun onAppForeground() {
        val ctx = appContext ?: return
        if (!running) return
        val sc = scope ?: return
        sc.launch {
            for (i in 0 until FOREGROUND_BURST_COUNT) {
                sendOneOffBeacon(ctx)
                if (i < FOREGROUND_BURST_COUNT - 1) delay(FOREGROUND_BURST_GAP_MS)
            }
        }
    }

    private suspend fun broadcastLoop(context: Context) {
        val socket = try {
            DatagramSocket().apply { broadcast = true }
        } catch (e: Exception) { running = false; return }
        sendSocket = socket
        try {
            // Startup burst — see the class doc comment's "SPEED" section.
            for (i in 0 until STARTUP_BURST_COUNT) {
                sendOn(socket, context)
                if (i < STARTUP_BURST_COUNT - 1) delay(STARTUP_BURST_GAP_MS)
            }
            while (running) {
                delay(BEACON_INTERVAL_MS)
                sendOn(socket, context)
            }
        } catch (e: Exception) {
            // Includes CancellationException from stop()'s scope.cancel() —
            // falls straight through to the finally block below either way,
            // which is all cleanup here actually needs.
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun sendOn(socket: DatagramSocket, context: Context) {
        try {
            val payload = beaconPayload(context)
            for (addr in broadcastTargets()) {
                try { socket.send(DatagramPacket(payload, payload.size, addr, DISCOVERY_PORT)) }
                catch (_: Exception) { /* one unreachable/filtered target must not stop the others */ }
            }
        } catch (_: Exception) {
            // Transient WiFi hiccup — the caller's own loop (if any) tries again next tick.
        }
    }

    /** A single beacon sent on its own short-lived socket — used for both
     *  requestImmediateBeacon() and the reply-on-first-sight handshake in
     *  handleBeacon(), neither of which should ever touch broadcastLoop's
     *  shared sendSocket from a different coroutine. */
    private fun sendOneOffBeacon(context: Context) {
        try {
            DatagramSocket().apply { broadcast = true }.use { socket -> sendOn(socket, context) }
        } catch (_: Exception) {}
    }

    // ── Pass-19 (symmetric discovery — the rare one-sided Connected Devices) ──
    // Some hotspot/AP firmwares deliver BROADCAST frames asymmetrically —
    // one device hears the other's beacons fine while the reverse path is
    // filtered or rate-limited entirely. Under that asymmetry, A showed B
    // while B had no idea A existed, however long both sat on the same Wi-Fi
    // (the reported rare-but-real mismatch). UNICAST frames are ACKed at the
    // 802.11 layer and delivered reliably even where broadcast is not, so the
    // fix is a rate-limited UNICAST ECHO: whenever a beacon ARRIVES, this
    // device aims its own beacon straight back at the sender's address, at
    // most once per peer per echo interval. A device that can hear beacons
    // but never send broadcast back therefore still refreshes its peers'
    // maps continuously — each of its received beacons triggers a unicast
    // the other side reliably receives. Cheap (two ~120-byte datagrams per
    // peer per 5 s), fully symmetric, and a no-op on networks where both
    // broadcast directions already work.
    private val UNICAST_ECHO_INTERVAL_MS = 5_000L
    private val lastUnicastEchoAt = ConcurrentHashMap<String, Long>()

    /** Fires this device's beacon straight at [address]:DISCOVERY_PORT on a
     *  short-lived socket — same payload, same handling on the peer's
     *  listener (handleBeacon cannot tell unicast from broadcast apart, and
     *  needs to). */
    private fun sendUnicastBeacon(context: Context, address: InetAddress) {
        try {
            DatagramSocket().use { socket ->
                try {
                    val payload = beaconPayload(context)
                    socket.send(DatagramPacket(payload, payload.size, address, DISCOVERY_PORT))
                } catch (_: Exception) { /* best-effort by design */ }
            }
        } catch (_: Exception) {}
    }

    /** Pass-19: the rate-limited unicast echo — at most one per peer per
     *  [UNICAST_ECHO_INTERVAL_MS] while that peer keeps beaconing, which is
     *  far below the 12 s stale threshold on the receiving side, so the
     *  echoing side's entry never goes stale on a network that eats its
     *  broadcasts. */
    private fun maybeSendUnicastEcho(context: Context, peerId: String, address: InetAddress) {
        val now = System.currentTimeMillis()
        val last = lastUnicastEchoAt[peerId] ?: 0L
        if (now - last < UNICAST_ECHO_INTERVAL_MS) return
        lastUnicastEchoAt[peerId] = now
        sendUnicastBeacon(context, address)
    }

    private fun beaconPayload(context: Context): ByteArray = JSONObject().apply {
        put("t", MAGIC)
        put("id", myId)
        put("name", myDisplayName(context))     // read live — a mid-session name change (Settings) takes effect on the very next beacon
        put("port", SettingsManager.getPort(context))
        put("ver", appVersionCode(context))
    }.toString().toByteArray(Charsets.UTF_8)

    // ── Pass-16 ("closing the app from Recent Apps removes it from every ───
    //  other device's Connected Devices card IMMEDIATELY"): the GOODBYE
    //  packet. Swiping the app away kills the process with no chance to say
    //  anything graceful — peers then kept the entry until the 12s stale /
    //  40s expire windows passed. The service's onTaskRemoved (and this
    //  object's own stop()) now fire one last beacon carrying bye=1 BEFORE
    //  the process dies; every listener that receives it drops the peer in
    //  the same instant (peers.remove + notify) instead of waiting out the
    //  expiry. Old peers that don't know the field simply ignore it and keep
    //  the previous behavior.
    private fun goodbyePayload(context: Context): ByteArray = JSONObject().apply {
        put("t", MAGIC)
        put("id", myId)
        put("name", myDisplayName(context))
        put("port", SettingsManager.getPort(context))
        put("ver", appVersionCode(context))
        put("bye", 1)
    }.toString().toByteArray(Charsets.UTF_8)

    /** Pass-16: fires the GOODBYE beacon on its own short-lived socket — sent
     *  before the process is killed on a Recent-Apps swipe and before the
     *  listener stops on a deliberate discovery stop, so peers disappear
     *  from Connected Devices immediately. Best-effort by design: a packet
     *  the network eats falls back to the existing stale/expire sweep. */
    fun sendGoodbye() {
        val ctx = appContext ?: return
        try {
            DatagramSocket().apply { broadcast = true }.use { socket ->
                val payload = goodbyePayload(ctx)
                for (addr in broadcastTargets()) {
                    try { socket.send(DatagramPacket(payload, payload.size, addr, DISCOVERY_PORT)) }
                    catch (_: Exception) { /* best-effort, both targets */ }
                }
                // ── Pass-19 (symmetric departure): unicast goodbyes to every ──
                // peer we know by address, in addition to the broadcasts — on
                // an asymmetric-broadcast network the broadcast goodbye
                // would be the ONE packet the other side never hears, leaving
                // this device lingering on ITS Connected Devices card for the
                // full stale/expire window while it vanished instantly from
                // every device that did hear the broadcast.
                val knownPeers = peers.values.toList()
                for (peer in knownPeers) {
                    try {
                        val addr = InetAddress.getByName(peer.ip)
                        socket.send(DatagramPacket(payload, payload.size, addr, DISCOVERY_PORT))
                    } catch (_: Exception) { /* best-effort per peer */ }
                }
                // One immediate duplicate — a single UDP datagram on a busy
                // hotspot is cheap insurance for "immediately".
                try { Thread.sleep(40) } catch (_: Exception) {}
                for (addr in broadcastTargets()) {
                    try { socket.send(DatagramPacket(payload, payload.size, addr, DISCOVERY_PORT)) }
                    catch (_: Exception) {}
                }
                for (peer in knownPeers) {
                    try {
                        val addr = InetAddress.getByName(peer.ip)
                        socket.send(DatagramPacket(payload, payload.size, addr, DISCOVERY_PORT))
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    /** Both the limited broadcast address and the subnet-directed one — some
     *  hotspot/router firmwares only reliably deliver one or the other. */
    private fun broadcastTargets(): List<InetAddress> {
        val targets = mutableListOf<InetAddress>()
        try { targets.add(InetAddress.getByName("255.255.255.255")) } catch (_: Exception) {}
        // Pass-8 follow-up: strictly LAN-based — the subnet-directed
        // broadcast is derived from the best LOCAL (non-cellular) address,
        // so a cellular-only device no longer aims discovery beacons at
        // its carrier's CGNAT subnet (nothing there could ever reply).
        NetworkUtils.getLocalIpAddress(appContext)?.let { ip ->
            val parts = ip.split(".")
            if (parts.size == 4) {
                try {
                    targets.add(InetAddress.getByName("${parts[0]}.${parts[1]}.${parts[2]}.255"))
                } catch (_: Exception) {}
            }
        }
        return targets
    }

    private suspend fun listenLoop() {
        // Pass-3 (R3): bind with retries — see LISTEN_BIND_ATTEMPTS' own
        // comment for why a single failure must not silently kill
        // discovery (both directions) for the rest of the process life.
        var socket: DatagramSocket? = null
        for (attempt in 0 until LISTEN_BIND_ATTEMPTS) {
            socket = try {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(DISCOVERY_PORT))
                    soTimeout = LISTEN_SOCKET_TIMEOUT_MS
                }
            } catch (e: Exception) { null }
            if (socket != null) break
            if (!running) return
            try { delay(LISTEN_BIND_RETRY_MS) } catch (_: Exception) { return }
        }
        if (socket == null) { running = false; return }
        listenSocket = socket
        val buf = ByteArray(2048)
        while (running) {
            try {
                val packet = DatagramPacket(buf, buf.size)
                socket.receive(packet)
                handleBeacon(packet)
            } catch (e: SocketTimeoutException) {
                // Expected — just a chance to re-check `running`.
            } catch (e: Exception) {
                if (!running) break // socket closed by stop()
            }
        }
        try { socket.close() } catch (_: Exception) {}
    }

    private fun handleBeacon(packet: DatagramPacket) {
        val text = try {
            String(packet.data, 0, packet.length, Charsets.UTF_8)
        } catch (_: Exception) { return }
        val json = try { JSONObject(text) } catch (_: Exception) { return }
        if (json.optString("t") != MAGIC) return
        val id = json.optString("id")
        // myId is set once in start() before either loop launches (see the
        // comment there) — this comparison is now guaranteed to use the
        // exact same id broadcastLoop is actually sending.
        if (id.isBlank() || id == myId) return
        val ip = packet.address?.hostAddress ?: return
        // ── Pass-16 (goodbye): a peer deliberately going away removes its ──
        //  entry THIS instant — no stale gray period, no expiry wait. The
        //  Connected Devices cards on every other device drop it on the
        //  next notify, which happens right here.
        if (json.optInt("bye", 0) == 1) {
            val gone = peers.remove(id)
            if (gone != null) {
                notifyListeners()
            }
            return
        }
        val port = json.optInt("port", 8888)
        val name = json.optString("name").ifBlank { "LumiDrop X Device" }
        val ver = json.optInt("ver", 1)
        val now = System.currentTimeMillis()

        val existing = peers[id]
        if (existing != null) {
            existing.ip = ip; existing.port = port; existing.displayName = name
            existing.lastSeenAt = now; existing.appVersion = ver
        } else {
            peers[id] = NativePeer(id, ip, port, name, now, ver)
            // First time hearing this peer — beacon straight back instead of
            // waiting for our own next scheduled tick. This is the main
            // reason discovery feels near-instant in the common case: the
            // new device's startup burst reaches every already-running
            // listener almost immediately, and each one replies immediately
            // in turn, so both sides finish discovering each other within
            // roughly one LAN round-trip rather than up to a full
            // BEACON_INTERVAL_MS.
            appContext?.let { sendOneOffBeacon(it) }
        }
        // ── Pass-19 (symmetric discovery): rate-limited UNICAST echo back ──
        // at the sender — covers networks that deliver broadcast frames
        // asymmetrically, where the broadcast reply above would never land
        // and this side would silently vanish from the other's Connected
        // Devices (see the unicast block comment above for the full story).
        appContext?.let { ctx ->
            try { maybeSendUnicastEcho(ctx, id, packet.address ?: return@let) } catch (_: Exception) {}
        }
        notifyListeners()
    }

    // ── GOAT-PASS 9 (§14 — discovery flicker + transfer race): the blind ──────
    // peers.clear() that used to run on every local-IP change has been
    // REPLACED by a probe-based reconcile. The old wipe was correct for a
    // DEPARTURE (no LAN interface left — nothing is reachable, clear now),
    // but it also fired on every JOIN: a device connecting to a hotspot
    // while the app was open saw its own local IP flip, wiped the peer map
    // milliseconds after the hotspot device's beacon had been learned, and
    // the next beacon re-added it — the reported "appears, immediately
    // disappears, then appears again" flicker. Worse, an in-flight transfer
    // Session held the REMOVED NativePeer instance, whose IP was now frozen
    // forever (the re-added entry is a different object), so every status
    // poll and file POST went to a dead URL: the sender sat on "Waiting"
    // while the receiver had already accepted and shown nothing — the
    // reported "accepted, nothing happens" transfer loss.
    //
    // The reconcile keeps BOTH properties the wipe was protecting:
    //  - a genuinely lost network still clears fast (every peer gets an
    //    immediate bounded liveness probe; unreachable ones drop within
    //    ~PROBE_TIMEOUT_MS instead of instantly — indistinguishable in
    //    practice for a polled 500ms UI), and
    //  - a valid peer that followed the device to the new network (or was
    //    learned on it moments ago) is NEVER removed: it either responded to
    //    the probe or was heard from within RECONCILE_FRESH_MS, so the card
    //    cannot flicker and sessions keep a live target.
    /** A peer heard from this recently is treated as alive on the CURRENT
     *  network without probing — comfortably more than the 1.5s beacon
     *  cadence plus the 3s sweep gap, so a join-transition beacon always
     *  qualifies, while a genuinely departed peer (silent since before the
     *  change) never does. */
    private val RECONCILE_FRESH_MS = 5_000L
    /** Reconcile probes run concurrently (a network change is rare; the pass
     *  must finish inside one probe timeout even with several peers, not
     *  serialize them on the single-thread probe executor). */
    private val reconcileExec = java.util.concurrent.Executors.newFixedThreadPool(4) { r ->
        Thread(r, "wsx-peer-reconcile").apply { isDaemon = true }
    }
    private val reconciling = java.util.concurrent.atomic.AtomicBoolean(false)

    /** GOAT-PASS 9 (§14): reconcile the peer map with the CURRENT network.
     *  Called from the 3s expiry sweep whenever this device's own local IP
     *  changed or vanished. Peers heard from within [RECONCILE_FRESH_MS] are
     *  kept unconditionally; every other peer gets one immediate bounded
     *  liveness probe — kept if its server answers, dropped (remove-if-
     *  unchanged, so a peer that re-announced mid-probe survives) if not. */
    private fun reconcilePeersIfNetworkChanged(): Boolean {
        val ctx = appContext ?: return false
        val currentLan = try { NetworkUtils.getLocalIpAddress(ctx) } catch (e: Exception) { null }
        val previous = lastKnownLanIp
        if (currentLan == previous) return false
        lastKnownLanIp = currentLan
        if (peers.isEmpty()) return false
        val now = System.currentTimeMillis()
        val toProbe = mutableListOf<NativePeer>()
        val it = peers.entries.iterator()
        while (it.hasNext()) {
            val p = it.next().value
            if (now - p.lastSeenAt <= RECONCILE_FRESH_MS) continue   // freshly heard — alive on the current network
            toProbe.add(p)
        }
        if (toProbe.isEmpty()) {
            return false
        }
        if (!reconciling.compareAndSet(false, true)) return false   // a pass is already running
        reconcileExec.execute {
            val removed = java.util.concurrent.atomic.AtomicInteger(0)
            try {
                val done = java.util.concurrent.CountDownLatch(toProbe.size)
                for (p in toProbe) {
                    reconcileExec.execute {
                        try {
                            if (!peerServerAlive(p) && running) {
                                // Remove-if-unchanged: a peer that re-announced
                                // while our probe was in flight stays.
                                val cur = peers[p.deviceId]
                                if (cur != null && System.currentTimeMillis() - cur.lastSeenAt > RECONCILE_FRESH_MS) {
                                    peers.remove(p.deviceId)
                                    removed.incrementAndGet()
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            try { done.countDown() } catch (_: Exception) {}
                        }
                    }
                }
                try { done.await(PROBE_TIMEOUT_MS + 1_500L, java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) {}
            } catch (_: Exception) {
            } finally {
                reconciling.set(false)
                if (removed.get() > 0) notifyListeners()
            }
        }
        return false   // listener notification is owned by the probe pass above
    }

    /** GOAT-PASS 9 (§14): the LIVE map entry for [deviceId]. Transfer
     *  sessions re-resolve their peer through this on every HTTP call so a
     *  peer entry that was ever removed and re-added (probe-kill + its next
     *  beacon) can never leave a session holding an orphaned instance with
     *  a frozen IP. Null when the id is not currently in the map. */
    fun currentPeer(deviceId: String): NativePeer? = peers[deviceId]

    // ── Pass-17 (probe-based fast expiry — "closing the app removes it from ──
    // every other device IMMEDIATELY"): the pass-16 goodbye beacon covers a
    // CLEAN Recent-Apps swipe, but any kill path that eats the last UDP
    // packet (an OEM's aggressive kill, a crash, a frozen process) left the
    // peer sitting in everyone's Connected Devices for the full 12s-stale /
    // 40s-expire windows. The robust backstop: a peer that has gone QUIET
    // (≥ PROBE_SILENCE_MS, four missed beacons at the normal 1.5s cadence)
    // gets an ACTIVE HTTP liveness probe to its own embedded server
    // (/api/ping — any HTTP response code, including a 401 from a
    // password-protected host, proves the process is alive). A live peer
    // answers in milliseconds and stays (a genuinely throttled/backgrounded
    // app may gap its beacons, but its foreground-service server still
    // responds); a dead one is removed the moment its probe fails — a few
    // seconds after death instead of 40. A wrongly-dropped live peer
    // re-announces itself on its next beacon and reply-on-first-sight
    // re-adds it instantly, so a transient network blip can never cause a
    // lasting disappearance.
    private val PROBE_SILENCE_MS = 6_000L
    private val PROBE_TIMEOUT_MS = 2_500L
    private val probeExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "wsx-peer-probe").apply { isDaemon = true }
    }
    /** In-flight probe guards — a peer id currently being probed must not be
     *  probed again (the probe itself takes up to PROBE_TIMEOUT_MS). */
    private val probingIds = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /** Pass-17: one HTTP liveness probe against [peer]'s own server. True =
     *  the process is alive (ANY HTTP status counts — connection refused /
     *  timeout / reset is the only "dead"). */
    private fun peerServerAlive(peer: NativePeer): Boolean {
        var conn: java.net.HttpURLConnection? = null
        return try {
            conn = (com.lumidrop.x.utils.PeerHttp.open(appContext, "${peer.baseUrl}/api/ping") as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = PROBE_TIMEOUT_MS.toInt()
                readTimeout = PROBE_TIMEOUT_MS.toInt()
            }
            conn.responseCode >= 200   // any answer at all (401/403/404 included) proves life
        } catch (e: Exception) { false } finally { conn?.disconnect() }
    }

    private suspend fun expiryLoop() {
        while (running) {
            try { delay(3000) } catch (_: Exception) { break }
            val now = System.currentTimeMillis()
            var changed = reconcilePeersIfNetworkChanged()
            val it = peers.entries.iterator()
            val probeCandidates = mutableListOf<NativePeer>()
            val expiryProbeCandidates = mutableListOf<NativePeer>()
            while (it.hasNext()) {
                val e = it.next()
                val silentFor = now - e.value.lastSeenAt
                if (silentFor > PEER_EXPIRE_MS) {
                    // ── GOAT-PASS 15 (§6 — the backgrounded-but-alive peer) ──────
                    // The hard 40 s drop used to be UNCONDITIONAL: an app whose
                    // UDP beacons an OEM froze the moment its Activity left the
                    // foreground — while its foreground-service HTTP server and
                    // "LumiDrop X - Running" notification stayed genuinely
                    // alive — survived the 6 s→40 s window only through the
                    // Pass-17 probes below, then vanished at 40 s no matter
                    // what those probes said. The reported symptom ("minimized
                    // for about a minute, disappeared from Nearby Devices") is
                    // exactly this boundary. Expiry is now PROVEN, not assumed:
                    // a peer whose own server answers /api/ping is refresh-lived
                    // (its entry stays fully visible — the service IS the
                    // app-running signal), and only a peer that is BOTH silent
                    // AND unreachable is dropped. A force-stopped app answers
                    // nothing and still leaves within the window + one probe.
                    if (e.key in probingIds) {
                        // An in-flight probe owns this peer's verdict this
                        // tick — never race a second one onto the same id.
                    } else {
                        expiryProbeCandidates.add(e.value)
                    }
                    lastUnicastEchoAt.remove(e.key)   // Pass-19: echo bookkeeping dies with the peer (re-added by any future beacon)
                }
                // Pass-17: quiet-but-not-yet-expired peers are probed —
                // dead ones leave NOW instead of riding out the window.
                else if (silentFor > PROBE_SILENCE_MS && e.key !in probingIds) {
                    probeCandidates.add(e.value)
                }
            }
            // ── GOAT-PASS 15 (§6): the expiry-boundary probe — alive means
            // refreshed (stays visible), dead means removed. Shares the same
            // single-thread executor and in-flight guards as the ordinary
            // Pass-17 probes, so a peer is never probed twice concurrently.
            for (p in expiryProbeCandidates) {
                probingIds.add(p.deviceId)
                probeExec.execute {
                    try {
                        if (peerServerAlive(p)) {
                            // The app is provably running (its foreground
                            // service answered) — the device REMAINS in
                            // Nearby Devices with a fresh liveness clock. No
                            // notifyListeners: nothing about the entry
                            // changed that any listener renders differently
                            // (name/ip/port are untouched; only the internal
                            // expiry clock moved).
                            val cur = peers[p.deviceId]
                            if (cur != null) cur.lastSeenAt = System.currentTimeMillis()
                        } else if (running) {
                            val cur = peers[p.deviceId]
                            if (cur != null && System.currentTimeMillis() - cur.lastSeenAt > PEER_EXPIRE_MS) {
                                peers.remove(p.deviceId)
                                notifyListeners()
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        probingIds.remove(p.deviceId)
                    }
                }
            }
            if (probeCandidates.isNotEmpty()) {
                for (p in probeCandidates) {
                    probingIds.add(p.deviceId)
                    probeExec.execute {
                        try {
                            if (!peerServerAlive(p) && running) {
                                // Still silent AND unreachable — dead. The
                                // remove-if-unchanged guard keeps a peer that
                                // re-announced while our probe was in flight.
                                val cur = peers[p.deviceId]
                                if (cur != null && System.currentTimeMillis() - cur.lastSeenAt > PROBE_SILENCE_MS) {
                                    peers.remove(p.deviceId)
                                    notifyListeners()
                                }
                            }
                        } catch (_: Exception) {
                        } finally {
                            probingIds.remove(p.deviceId)
                        }
                    }
                }
            }
            if (changed) notifyListeners()
        }
    }

    private fun notifyListeners() {
        val snap = snapshot()
        for (l in listeners) { try { l.onPeersChanged(snap) } catch (_: Exception) {} }
    }
}
