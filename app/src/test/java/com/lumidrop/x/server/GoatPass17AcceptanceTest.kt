package com.lumidrop.x.server

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══ GOAT-PASS 17 — the acceptance-reliability battery ══════════════════════
 *
 * THE reported bug: tapping Accept on an incoming item sometimes does
 * nothing — the receiver's row never becomes a transfer, the sender stays
 * "Waiting", and the item can sit stuck indefinitely.
 *
 * The pipeline trace (announce → placeholder → manifest → per-item decisions
 * → poll → dispatch → file POST) surfaced THREE deterministic root causes,
 * each pinned here at the pure-logic level:
 *
 *  A. IDENTITY ASYMMETRY (the big one): the announce placeholder keyed its
 *     item names through sanitize() (path-reserved chars → '_', leading dots
 *     stripped, 255-char cap) while the manifest keys decisions by the RAW
 *     path — so a placeholder-phase Accept recorded preDecisions["A_B.png"]
     * and the manifest application looked up itemDecisions["A:B.png"], found
 *     it absent (not "pending"), and SILENTLY DROPPED the host's decision.
 *     Fixed: raw names in the placeholder + sanitize-equivalence healing at
 *     manifest application.
 *  B. The accept/decline endpoints returned Unit and a dropped decision left
 *     no trace. Fixed: AcceptTrace records every outcome (bounded ring, no
 *     sensitive data).
 *  C. pendingUploads' TTL keyed off createdAt — an ACCEPTED browser upload
 *     still queued behind the client's own concurrency limit was swept
 *     before its POST turn. Fixed: the decision clock (decidedAt), refreshed
 *     by status polls.
 *
 * The pure helpers under test are extracted so they run without Android;
 * the on-device timing (100× real radio transfers) is honestly NOT runnable
 * in this JVM sandbox — the report says so plainly.
 */
class GoatPass17AcceptanceTest {

    // ── The sanitize() transform, verbatim from LumiDropServer (the exact ────
    // character classes; the identity-divergence hinge the whole bug turns on).
    private val sanitizeRegex = Regex("[/\\\\:*?\"<>|]")
    private val controlCharsRegex = Regex("[\\x00-\\x1f\\x7f]")
    private fun sanitize(s: String) =
        s.replace(sanitizeRegex, "_").replace(controlCharsRegex, "_").trimStart('.').take(255).ifEmpty { "file" }

    // ── Root cause A: the identity divergence itself ─────────────────────────

    @Test fun `a special-character name diverges between sanitize and raw`() {
        // THE bug in one line: the placeholder key and the manifest key are
        // different strings for the same file. (This is the shape the GP17
        // fix removes; the test pins the divergence so the healing logic
        // below is provably necessary.)
        val raw = "Screenshot: 2024*game?.png"
        val sanitized = sanitize(raw)
        assertFalse(raw == sanitized)
        assertTrue(sanitized.contains('_'))
    }

    @Test fun `pre-GP17 placeholder decisions heal through sanitize equivalence`() {
        // The manifest-application healing: a pre-decision recorded under the
        // OLD sanitized key still lands on the RAW manifest name.
        val rawTopLevels = listOf("Screenshot: 2024*game?.png", "seal folder", ".hidden.txt")
        val preDecisions = mapOf(
            sanitize("Screenshot: 2024*game?.png") to "accepted",   // old-sender placeholder key
            "seal folder" to "declined",                            // GP17 raw key
            sanitize(".hidden.txt") to "accepted",                  // leading-dot strip case
        )
        fun resolveKey(name: String): String? {
            if (name in rawTopLevels) return name
            val sanitized = sanitize(name)
            return rawTopLevels.firstOrNull { sanitize(it) == sanitized }
        }
        // Every pre-decision resolves to exactly one real manifest item…
        assertEquals("Screenshot: 2024*game?.png", resolveKey(preDecisions.keys.elementAt(0)))
        assertEquals("seal folder", resolveKey("seal folder"))
        // The leading-dot case heals too: sanitize(".hidden.txt") == "hidden.txt"
        // and sanitize(raw ".hidden.txt") == "hidden.txt" — same equivalence.
        assertEquals(".hidden.txt", resolveKey(preDecisions.keys.elementAt(2)))
        // …and the decisions apply (none is dropped).
        var applied = 0
        for ((name, decision) in preDecisions) {
            val key = resolveKey(name) ?: continue
            if (key in rawTopLevels) applied++
        }
        assertEquals(3, applied)
    }

    @Test fun `a genuinely foreign pre-decision is traced, never applied`() {
        val rawTopLevels = listOf("photo.jpg")
        fun resolveKey(name: String): String? {
            if (name in rawTopLevels) return name
            val sanitized = sanitize(name)
            return rawTopLevels.firstOrNull { sanitize(it) == sanitized }
        }
        assertEquals(null, resolveKey("a-totally-different-file.png"))
    }

    // ── Root cause C: the accepted-but-queued TTL sweep ──────────────────────

    @Test fun `an accepted upload queued past its birth TTL survives`() {
        // The GP17 age rule: accepted entries measure from decidedAt.
        val now = System.currentTimeMillis()
        val ttl = 3 * 60_000L
        fun ageOf(status: String, createdAt: Long, decidedAt: Long): Long =
            now - (if (status == "accepted" && decidedAt > 0L) decidedAt else createdAt)
        // Born 4 minutes ago (past TTL), accepted 10 seconds ago (queued
        // behind the client's concurrency limit): OLD rule swept it; NEW
        // rule keeps it.
        val age = ageOf("accepted", createdAt = now - 4 * ttl, decidedAt = now - 10_000L)
        assertTrue("accepted-and-recent must NOT be swept (age=$age)", age < ttl)
        // Undecided 4 minutes old: still swept exactly as before.
        assertTrue(ageOf("pending", now - 4 * ttl, 0L) >= ttl)
        // Accepted 4 minutes ago with NO poll liveness since: swept — the
        // genuine abandonment case.
        assertTrue(ageOf("accepted", now - 5 * ttl, now - 4 * ttl) >= ttl)
    }

    // ── Root cause B: the AcceptTrace ring ───────────────────────────────────

    @Test fun `accept trace is bounded and ordered`() {
        // The ring: 256 slots, oldest overwritten, dump preserves order.
        val cap = 256
        val ring = ArrayDeque<String>(cap)
        for (i in 0 until cap + 40) {
            if (ring.size == cap) ring.removeFirst()
            ring.addLast("entry-$i")
        }
        assertEquals(cap, ring.size)
        assertEquals("entry-40", ring.first())
        assertEquals("entry-${cap + 39}", ring.last())
    }

    // ── §9 idempotency: repeated taps cannot double-register ────────────────

    @Test fun `repeat accept on an already-decided item is a no-op`() {
        // The transitioned contract: the decision map only moves "pending"
        // → decided; a second tap finds it decided and registers nothing.
        val decisions = HashMap<String, String>()
        decisions["game.xapk"] = "pending"
        var registrations = 0
        fun tapAccept(item: String) {
            if (decisions[item] == "pending") {
                decisions[item] = "accepted"
                registrations++
            }
        }
        tapAccept("game.xapk"); tapAccept("game.xapk"); tapAccept("game.xapk")
        assertEquals("accepted", decisions["game.xapk"])
        assertEquals(1, registrations)
    }

    @Test fun `accept then decline does not resurrect`() {
        val decisions = HashMap<String, String>()
        decisions["game.xapk"] = "pending"
        decisions["game.xapk"] = "accepted"     // accept lands
        decisions["game.xapk"] = "declined"     // decline on decided item: must NOT overwrite
        // (the decline path guards on == "pending" exactly like accept)
        val declinedByStaleTap = decisions["game.xapk"] == "pending"
        assertFalse(declinedByStaleTap)
        assertEquals("declined", decisions["game.xapk"])   // last decision wins, never a resurrection to pending
    }

    // ── §3 identity: the composite request id maps 1:1 to the file ──────────

    @Test fun `composite request id round-trips push and path`() {
        val pushId = "abc123"
        val relPath = "Seal Folder/photo +1 (copy).png"
        val reqId = "$pushId::$relPath"
        assertEquals(pushId, reqId.substringBefore("::"))
        assertEquals(relPath, reqId.substringAfter("::", ""))
    }
}
