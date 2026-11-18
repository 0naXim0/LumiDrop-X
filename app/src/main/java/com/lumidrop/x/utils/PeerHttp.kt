package com.lumidrop.x.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.URL
import java.net.URLConnection

/**
 * ══ FINAL PASS 3 (§hotspot — routed peer HTTP for the mobile-data + ══
 * ══ internet-less-hotspot combination) ══
 *
 * THE PROBLEM THIS SOLVES
 * -----------------------
 * Device A (sender) has an active SIM/mobile-data connection AND is joined
 * over Wi-Fi to device B's hotspot, which has no internet. Android picks
 * the CELLULAR network as the system default (the Wi-Fi network is
 * unvalidated, so it is never promoted), and — since Android 5's
 * multi-network stack — the default network's routes are the ONLY routes a
 * plain, unbound socket can use. Every app-to-peer HTTP request LumiDrop X
 * makes (`URL(...).openConnection()`) is such a socket, so the POST to
 * B's hotspot address 192.168.x.y is routed at the cellular network, which
 * has no route to that subnet: the connection can never be established,
 * the sender's row sits in "Waiting" retrying until the user disables
 * mobile data (which finally makes the Wi-Fi network the default).
 *
 * Device DISCOVERY never had this failure, which is exactly why both
 * devices still appear connected while transfers stall: the discovery
 * beacons are UDP broadcasts — the limited broadcast 255.255.255.255 is
 * sent out on every broadcast-capable interface by the kernel, so the
 * Wi-Fi interface carries it even while cellular is the default — and the
 * listener socket is wildcard-bound, so inbound beacons arrive on the
 * Wi-Fi interface regardless of which network is default. Discovery and
 * transfer use different networking paths; only the transfer path was
 * broken. (Traced, not guessed: NativeDiscovery uses DatagramSocket
 * broadcast + a wildcard listener; every transfer-path connection is an
 * unbound HttpURLConnection.)
 *
 * THE FIX, AT THE ROOT
 * --------------------
 * Open each peer HTTP connection ON THE NETWORK THAT CAN ACTUALLY REACH
 * the peer, instead of implicitly on the default one:
 *
 *  1. If the DEFAULT network already covers the peer's subnet (the
 *     ordinary cases — Wi-Fi with internet, or mobile data off and the
 *     hotspot Wi-Fi being the default), the connection is opened exactly
 *     as before. Zero behavior change; nothing else in the app is touched.
 *  2. Otherwise the active networks are scanned for one whose
 *     LinkProperties carry an IPv4 address on the SAME subnet as the peer
 *     (a Wi-Fi transport network preferred). If found, the connection is
 *     opened through `Network.openConnection(url)` — the platform's own
 *     per-network routing, available since API 21 — so the request rides
 *     the Wi-Fi interface that genuinely reaches the peer, WITHOUT
 *     requiring that network to be validated, and without touching the
 *     process's default network (internet over mobile data keeps working
 *     for everything else).
 *  3. If no network provably covers the peer (peer behind a routed subnet,
 *     hotspot-host edge cases, or ConnectivityManager unavailable), the
 *     connection is opened exactly as before — the fix must never make a
 *     working case worse, and only ever adds a MORE specific route when one
 *     is proven to exist.
 *
 * Peer targets are always raw IPv4 literals (`http://<ip>:<port>` from the
 * discovery beacon / pending-push records), so coverage is decided by pure
 * byte math — no DNS, no guessing. Every step is exception-guarded: any
 * failure in the routing decision falls back to the plain connection, so
 * the helper can never break a request that would otherwise have worked.
 *
 * NOT CHANGED, DELIBERATELY
 * -------------------------
 *  • Discovery (UDP broadcast) — already works in the reported scenario.
 *  • The NanoHTTPD servers — wildcard-bound, reachable on every interface
 *    including the hotspot's own.
 *  • No `bindProcessToNetwork`, no global default-network swap, no mobile
 *    data toggling, no permission additions: the routing decision is made
 *    per connection from live ConnectivityManager state, so there are no
 *    stale handles to a vanished Wi-Fi network (a dropped Wi-Fi simply
 *    leaves the scan with no covering network → plain connection → the
 *    existing retry/auto-continue machinery handles the outage exactly as
 *    it already does).
 */
object PeerHttp {

    /** Opens [url] on the network that can actually reach its host — see the
     *  class doc. Never throws: every failure path degrades to the plain
     *  `url.openConnection()` the app used before this pass. */
    fun open(context: Context?, url: URL): URLConnection {
        val ctx = context?.applicationContext ?: return url.openConnection()
        try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return url.openConnection()
            val target = parseIpv4(url.host ?: return url.openConnection())
                ?: return url.openConnection()   // not a raw IPv4 peer literal → ordinary path
            @Suppress("DEPRECATION")   // activeNetwork: the only default-network getter on API 21
            val active = try { cm.activeNetwork } catch (e: Exception) { null }
            var coveringFallback: Network? = null
            for (n in cm.allNetworks ?: emptyArray()) {
                try {
                    val lp = cm.getLinkProperties(n) ?: continue
                    var covers = false
                    for (la in lp.linkAddresses) {
                        val a = la.address as? java.net.Inet4Address ?: continue
                        val plen = la.prefixLength
                        if (plen < 0 || plen > 32) continue
                        if (sameSubnet(a.address, plen, target)) { covers = true; break }
                    }
                    if (!covers) continue
                    // The default network already reaches the peer — open the
                    // connection exactly the way the app always did.
                    if (n == active) return url.openConnection()
                    // A non-default network covers the peer (the internet-less
                    // hotspot Wi-Fi beside a cellular default). Prefer a Wi-Fi
                    // transport among covering networks; remember the first
                    // other one as the fallback.
                    val caps = try { cm.getNetworkCapabilities(n) } catch (e: Exception) { null }
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                        return n.openConnection(url)
                    }
                    if (coveringFallback == null) coveringFallback = n
                } catch (e: Exception) {
                    // One unreadable network never stops the scan.
                }
            }
            coveringFallback?.let { return it.openConnection(url) }
        } catch (e: Exception) {
            // The routing decision must never break the request itself.
        }
        return url.openConnection()
    }

    /** String-URL convenience overload of [open]. */
    fun open(context: Context?, urlStr: String): URLConnection = open(context, URL(urlStr))

    /** Parses a dotted-quad IPv4 literal into 4 bytes, or null for anything
     *  else (hostname, IPv6, malformed). Pure character math — no DNS. */
    private fun parseIpv4(host: String): ByteArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val out = ByteArray(4)
        for (i in 0 until 4) {
            val p = parts[i]
            if (p.isEmpty() || p.length > 3) return null
            var v = 0
            for (ch in p) {
                if (ch !in '0'..'9') return null
                v = v * 10 + (ch - '0')
                if (v > 255) return null
            }
            out[i] = v.toByte()
        }
        return out
    }

    /** True when [b] falls inside the /plen subnet rooted at [a] (both raw
     *  4-byte IPv4 addresses). Signed-byte compares are safe: both sides are
     *  masked identically. */
    private fun sameSubnet(a: ByteArray, plen: Int, b: ByteArray): Boolean {
        if (a.size != 4 || b.size != 4) return false
        val fullBytes = plen / 8
        val remBits = plen % 8
        for (i in 0 until fullBytes) if (a[i] != b[i]) return false
        if (remBits > 0) {
            val mask = (0xFF shl (8 - remBits)) and 0xFF
            if ((a[fullBytes].toInt() and mask) != (b[fullBytes].toInt() and mask)) return false
        }
        return true
    }
}
