package com.lumidrop.x.utils

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkUtils {

    /** The three interface categories Phase 17's Settings screen exposes as
     *  independent toggles. LAN covers both plain home Wi-Fi and USB/other
     *  tethering — anything that isn't specifically this device acting as
     *  the hotspot or using its own cellular radio. */
    enum class InterfaceKind { LAN_WIFI, HOTSPOT, MOBILE_DATA }

    data class DetectedInterface(val kind: InterfaceKind, val label: String, val ip: String)

    // ── Pass-13 (URL tag correctness: [Hotspot] vs [Wi-Fi]) ──────────────────
    // The legacy classification (IP-range + interface-NAME heuristics below)
    // mislabels BOTH directions on modern devices:
    //   • A phone hosting its OWN hotspot on Android 11+ often gets a
    //     randomized subnet (not 192.168.43/49.x) and the soft-AP frequently
    //     rides the ordinary wlan0 (or a wlan1/swlan1) interface — so every
    //     heuristic missed and the URL tag read "Wi-Fi" while the phone was
    //     serving its own hotspot.
    //   • Conversely, a phone CONNECTED as a client to another phone's
    //     classic 192.168.43.x hotspot matches the legacy IP range and was
    //     wrongly tagged "Hotspot".
    // The authoritative signal for "this interface is THIS device's own
    // tethering/hotspot uplink" is ConnectivityManager.getTetheredIfaces():
    // it lists exactly the interfaces currently tied out (Wi-Fi AP, USB,
    // Bluetooth PAN, P2P). Among those, only wlan/ap/swlan-family names are
    // the Wi-Fi hotspot; USB (rndis/usb/ncm) and Bluetooth (bt/bnep) stay
    // LAN-classified, which matches the Settings copy ("LAN covers both
    // plain home Wi-Fi and USB/other tethering").
    // When the reflection call above yields no answer (an OEM refusal, or a
    // build where the method is missing/blocked), the legacy heuristics
    // below decide exactly as before — and on Android 5/6 those heuristics
    // really did match the platform's fixed ap0/192.168.43.x soft-AP config.
    private const val TETHERED_IFACES_MIN_API = 21

    /** The set of interface names currently tethered by THIS device (its own
     *  hotspot / USB / Bluetooth tethering). NULL means "no answer" —
     *  distinct from an EMPTY set, which is a real answer meaning "nothing
     *  is tethered right now". Never throws: any failure simply falls back
     *  to the name/IP heuristics in getAllInterfaces.
     *
     *  getTetheredIfaces() is a hidden framework API — stripped from the
     *  PUBLIC android.jar (so it cannot be called directly at compile time)
     *  but present in the platform at runtime since the Froyo era and on
     *  the greylist (unsupported-but-callable), which is exactly the
     *  profile reflection is for. One Method lookup per call — cheap next
     *  to the NetworkInterface enumeration around it. */
    private fun tetheredIfaceNames(context: Context): Set<String>? {
        if (android.os.Build.VERSION.SDK_INT < TETHERED_IFACES_MIN_API) return null
        return try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
                ?: return null
            val method = android.net.ConnectivityManager::class.java.getMethod("getTetheredIfaces")
            @Suppress("UNCHECKED_CAST")
            val names = method.invoke(cm) as? Array<String> ?: return null
            val out = java.util.HashSet<String>(names.size)
            for (n in names) if (n != null) out.add(n.lowercase())
            out
        } catch (e: Exception) {
            // Some OEM builds refuse tethering queries for normal apps —
            // treat as "no answer" and fall back to the heuristics.
            null
        }
    }

    /**
     * Enumerates every currently-active, non-loopback IPv4 address and
     * classifies each as LAN/Wi-Fi, Hotspot, or Mobile Data — used by the
     * Settings network toggles and the dashboard's multi-URL card. This is
     * a superset of getLocalIpAddress() below (kept unchanged, still used
     * by the single-URL paths) rather than a replacement for it, so nothing
     * that already calls getLocalIpAddress() needs to change.
     *
     * Classification (Pass-13): hotspot detection is anchored on the LIVE
     * tethering answer (tetheredIfaceNames above — a wlan/ap/swlan-family
     * interface currently tethered by THIS device is its own hotspot, full
     * stop), with the interface-NAME heuristics (ap0/swlan0-style names)
     * kept as a name-only signal and the legacy IP ranges demoted to a
     * fallback that only applies when no tethering answer was available at
     * all. Mobile Data vs LAN/Wi-Fi is resolved via
     * ConnectivityManager.getNetworkCapabilities()'s actual transport type
     * first — interface-NAME pattern matching (rmnet/ccmni/etc., kept only
     * as a fallback below) varies enough across chipsets/OEMs that a
     * device using an unrecognized naming scheme would silently
     * misclassify its cellular interface as LAN_WIFI (enabled by default),
     * which is exactly how "mobile data server starts even when disabled"
     * was able to happen — the toggle was being checked correctly, the
     * interface just was never being labeled MOBILE_DATA in the first
     * place on that device.
     */
    fun getAllInterfaces(context: Context): List<DetectedInterface> {
        val out = mutableListOf<DetectedInterface>()

        // (Pass-8 follow-up) Cellular interfaces are re-classified from the
        // live ConnectivityManager transports on EVERY call — see the new
        // cellularIfaceNames() helper below for why this map is rebuilt
        // rather than trusted from a single snapshot.
        val transportByIfaceName = cellularIfaceNames(context)

        // Pass-13: the authoritative "this device is currently tethering
        // through these interfaces" set — see tetheredIfaceNames()'s own
        // comment. Null when unavailable (API < 24 / OEM refusal), in which
        // case the legacy heuristics below decide exactly as before.
        val tethered = tetheredIfaceNames(context)
        // Pass-13: true when the tethering answer is a REAL answer (even an
        // empty one). When it is, an interface that is NOT in the tethered
        // set can never be this device's own hotspot no matter what its IP
        // range looks like (the classic "client of someone else's
        // 192.168.43.x hotspot" case), so the IP-range heuristic is skipped
        // entirely rather than OR-ed in.
        val tetherInfoUsable = tethered != null
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return out
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    if (addr !is Inet4Address) continue
                    val ip = addr.hostAddress ?: continue

                    // An interface explicitly named for the soft AP is always
                    // the hotspot, tethering info or not — ap0/ap_br0/swlan0
                    // exist for exactly one purpose on every OEM that ships
                    // them.
                    val softApName = name.startsWith("ap") || name.startsWith("swlan")
                    // Pass-13: tethering is THE signal for "this wlan-family
                    // interface is currently this device's own hotspot" — it
                    // stays correct through randomized subnets (Android 11+)
                    // and through OEMs that host the AP on wlan0/wlan1.
                    val tetheredWifiAp = tethered?.contains(name) == true &&
                        (name.startsWith("wlan") || softApName)
                    // The legacy IP ranges are demoted to a FALLBACK that only
                    // applies when no tethering answer was available at all
                    // (API 21-23, or an OEM that refused the query) — where
                    // they still describe the platform's fixed soft-AP config.
                    val legacyHotspotRange =
                        ip.startsWith("192.168.43.") || ip.startsWith("192.168.49.")
                    val legacyHotspotIp = !tetherInfoUsable && legacyHotspotRange

                    val kind = when {
                        softApName || tetheredWifiAp || legacyHotspotIp -> InterfaceKind.HOTSPOT
                        name in transportByIfaceName                                     -> InterfaceKind.MOBILE_DATA
                        name.startsWith("rmnet") || name.startsWith("ccmni") ||
                        name.startsWith("wwan") || name.startsWith("pdp")   ||
                        name.startsWith("radio")                                      -> InterfaceKind.MOBILE_DATA
                        else -> InterfaceKind.LAN_WIFI
                    }
                    val label = when (kind) {
                        InterfaceKind.HOTSPOT     -> "Hotspot"
                        InterfaceKind.MOBILE_DATA -> "Mobile Data"
                        InterfaceKind.LAN_WIFI    -> if (name.startsWith("wlan")) "Wi-Fi" else "LAN"
                    }
                    // A given kind can legitimately appear twice (e.g. two
                    // wlan-family addresses) — dedupe by IP, not by kind.
                    if (out.none { it.ip == ip }) out.add(DetectedInterface(kind, label, ip))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return out
    }

    /** The set of interface names currently carrying a CELLULAR transport,
     *  per ConnectivityManager. Extracted from getAllInterfaces because a
     *  SECOND consumer needs the same live classification: getLocalIpAddress()
     *  (below) must never resolve to a carrier interface now that the app is
     *  strictly LAN-based — a hotspot-less, Wi-Fi-less device used to fall
     *  through to its CGNAT 10.x address there, and every surface that reads
     *  that fallback (the server card, the subnet-directed discovery beacon)
     *  then advertised a mobile-data IP that no peer could ever reach.
     *  (Pass-8 follow-up: previously the transports map also recorded
     *  WI-FI/ETHERNET, but no consumer ever read those keys — the LAN_WIFI
     *  fallback classification is name-based and correct without them.) */
    private fun cellularIfaceNames(context: Context): Set<String> {
        val out = java.util.HashSet<String>()
        try {
            val cm = context.applicationContext
                .getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
            cm?.allNetworks?.forEach { network ->
                val caps = cm.getNetworkCapabilities(network) ?: return@forEach
                if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)) return@forEach
                val ifaceName = cm.getLinkProperties(network)?.interfaceName?.lowercase() ?: return@forEach
                out.add(ifaceName)
            }
        } catch (e: Exception) {
            // Falls through to the name-heuristic classification in
            // getAllInterfaces for every interface — never lets a
            // ConnectivityManager failure block interface detection entirely.
        }
        return out
    }

    /** getAllInterfaces() filtered down to whichever kinds are actually
     *  enabled in Settings, then PRIORITY-ORDERED for display. Centralized
     * here (rather than as inline filter logic duplicated in both the
     * dashboard and the notification builder) specifically because that
     * duplication is how a real bug shipped: the dashboard was updated to
     * respect these toggles, but the persistent notification's URL was
     * built from the older, un-gated getLocalIpAddress() and nobody had
     * one shared place to fix.
     *
     *  Pass-7 (combined toggle): Settings exposes ONE "Hotspot / Wi-Fi /
     *  LAN" switch that writes both legacy keys together, so the effective
     *  gate for both local kinds is "either key on" — a device left over
     *  from an older pass with hotspot on but LAN off still reads as
     *  enabled, exactly like its single combined switch shows. Mobile Data
     *  is never enabled: Internet sharing is removed entirely; the kind
     *  stays classified in getAllInterfaces() purely so the request-layer
     *  gate can recognize and refuse direct hits on the carrier address.
     *
     *  Pass-7 (ordering): the FIRST entry is what the server card's single
     *  fixed-height URL row, the QR modal's default chip and the
     *  notification's first line all show — so the order IS the
     *  reachability policy: Hotspot (peers are literally on this device's
     *  own network) first, then Wi-Fi/LAN (same-router peers). Nothing is
     *  hidden — everything else stays one chevron-tap away. */
    fun getEnabledInterfaces(context: Context): List<DetectedInterface> {
        // Pass-11 (Required Implementation 3): the Hotspot / Wi-Fi / LAN toggle
        // is removed — local interfaces are PERMANENTLY enabled. The only
        // filter that remains is the carrier-data one (the app is strictly
        // LAN-based). The setting keys are inert leftovers nothing reads.
        return getAllInterfaces(context)
            .filter {
                when (it.kind) {
                    InterfaceKind.LAN_WIFI, InterfaceKind.HOTSPOT -> true
                    InterfaceKind.MOBILE_DATA -> false
                }
            }
            .sortedBy {
                when (it.kind) {
                    InterfaceKind.HOTSPOT     -> 0
                    InterfaceKind.LAN_WIFI    -> 1
                    InterfaceKind.MOBILE_DATA -> 2
                }
            }
    }

    /**
     * Returns the best local IPv4 address — never a cellular one.
     * Priority order:
     *  1. Hotspot interface (192.168.43.x or 192.168.0.x on ap0/wlan0)
     *  2. Any active WiFi address
     *  3. Any non-loopback IPv4 on a non-cellular interface
     *
     * Pass-8 follow-up (strictly LAN-based): a carrier interface's CGNAT
     * 10.x address used to win the generic-private-IP priority here
     * whenever no Wi-Fi/hotspot was up, which made the server card's
     * no-LAN fallback advertise a mobile-data IP no peer could ever
     * reach — the exact "must never display a mobile data IP when no
     * LAN network is available" defect. Cellular interfaces (classified
     * by live transport when a Context is available, plus the
     * rmnet/ccmni/wwan/pdp/radio name patterns) are skipped outright;
     * with no LAN-family interface up this returns null and the card
     * shows its honest "Waiting for network…" state instead.
     */
    fun getLocalIpAddress(context: Context? = null): String? {
        // Live cellular transport names when a Context is available
        // (both current callers pass one) — name patterns alone otherwise.
        val cellular = context?.let { cellularIfaceNames(it) } ?: emptySet()
        val candidates = mutableListOf<Pair<Int, String>>() // priority, ip

        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                val name = iface.name.lowercase()
                // Strictly LAN-based: skip every cellular interface,
                // whether classified by live transport or name pattern.
                if (name in cellular ||
                    name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("wwan") || name.startsWith("pdp") ||
                    name.startsWith("radio")) continue

                for (addr in iface.inetAddresses) {
                    if (addr.isLoopbackAddress) continue
                    if (addr !is Inet4Address) continue
                    val ip = addr.hostAddress ?: continue

                    val priority = when {
                        // Hotspot address — highest priority
                        ip.startsWith("192.168.43.") -> 10
                        ip.startsWith("192.168.49.") -> 9
                        // Hotspot on common interfaces
                        (name.startsWith("ap") || name.startsWith("swlan")) -> 8
                        // Standard WiFi
                        name.startsWith("wlan") && ip.startsWith("192.168.") -> 7
                        name.startsWith("wlan") -> 6
                        // USB tethering
                        name.startsWith("rndis") || name.startsWith("usb") -> 5
                        // Generic private IP
                        ip.startsWith("192.168.") || ip.startsWith("10.") -> 4
                        else -> 1
                    }
                    candidates.add(priority to ip)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return candidates.maxByOrNull { it.first }?.second
    }
}
