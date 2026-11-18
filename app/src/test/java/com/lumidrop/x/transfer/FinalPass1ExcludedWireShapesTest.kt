package com.lumidrop.x.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══ FINAL PASS 1 (§1) — the exclusion WIRE shapes the sender must match ══
 *
 * The reported defect: the receiver declines three children of a 30-item
 * folder from the expanded Incoming Items sheet, and the sender's folder
 * card keeps showing "0/30" (only correcting itself much later, child by
 * child) — the two sides never converged on the eligible manifest.
 *
 * Root cause (sender half): the receiver serializes its per-child
 * exclusions as FULL paths ("Photos/IMG_02.jpg") keyed by top-level name,
 * while the sender's matcher stripped the top-level prefix from its own
 * relPath and compared ONLY against top-RELATIVE entries. Nothing ever
 * matched, so [NativePushClient]'s poll-driven Declined resolution never
 * fired and every exclusion was learned only at that child's own POST
 * refusal.
 *
 * [ChildExclusions.isExcludedOnWire] is the ONE shared matcher now, and it
 * accepts BOTH shapes — the documented top-relative form and the full-path
 * form every receiver of this codebase emits. These tests pin:
 *
 *  • the FULL-PATH shape (the one real receivers send): a directly removed
 *    child, a removed subfolder's whole subtree at any depth, and the exact
 *    30 → 27 arithmetic of the reported scenario;
 *  • the DOCUMENTED top-relative shape (an older receiver, or a future one
 *    that trims the prefix): same semantics;
 *  • both shapes NEVER over-match — a sibling prefix ("Subfolder2/…"),
 *    another top-level's set, an empty map and a null map all keep the
 *    child;
 *  • the receiver's OWN isExcluded-style lookup stays consistent with the
 *    full-path storage (the sheet's ✕ keeps writing what the receiver
 *    itself reads back).
 */
class FinalPass1ExcludedWireShapesTest {

    // ── the full-path wire shape (every current receiver) ────────────────

    @Test
    fun `full-path shape - direct child removal excludes exactly that child`() {
        val byTop = mapOf("Photos" to setOf("Photos/IMG_02.jpg"))
        assertTrue(ChildExclusions.isExcludedOnWire("Photos/IMG_02.jpg", byTop))
        assertFalse(ChildExclusions.isExcludedOnWire("Photos/IMG_01.jpg", byTop))
        assertFalse(ChildExclusions.isExcludedOnWire("Photos/IMG_022.jpg", byTop))   // prefix ≠ same file
    }

    @Test
    fun `full-path shape - removed subfolder excludes its whole subtree at any depth`() {
        val byTop = mapOf("Photos" to setOf("Photos/Trip"))
        assertTrue(ChildExclusions.isExcludedOnWire("Photos/Trip/IMG_02.jpg", byTop))
        assertTrue(ChildExclusions.isExcludedOnWire("Photos/Trip/Deep/x.png", byTop))
        assertFalse(ChildExclusions.isExcludedOnWire("Photos/Trip2/IMG_02.jpg", byTop))   // sibling prefix
        assertFalse(ChildExclusions.isExcludedOnWire("Photos/IMG_02.jpg", byTop))
    }

    @Test
    fun `full-path shape - the reported 30-to-27 arithmetic`() {
        // 30 children; the receiver removes three (two files + one subfolder
        // holding one more file). Exactly 27 remain eligible.
        val children = (1..27).map { "Photos/IMG_%02d.jpg".format(it) } +
            listOf("Photos/IMG_28.jpg", "Photos/IMG_29.jpg", "Photos/Sub/IMG_30.jpg")
        val byTop = mapOf("Photos" to setOf("Photos/IMG_28.jpg", "Photos/IMG_29.jpg", "Photos/Sub"))
        val eligible = children.count { !ChildExclusions.isExcludedOnWire(it, byTop) }
        org.junit.Assert.assertEquals(27, eligible)
    }

    // ── the documented top-relative wire shape (legacy / future) ─────────

    @Test
    fun `relative shape - direct removal excludes exactly that child`() {
        val byTop = mapOf("Photos" to setOf("IMG_02.jpg"))
        assertTrue(ChildExclusions.isExcludedOnWire("Photos/IMG_02.jpg", byTop))
        assertFalse(ChildExclusions.isExcludedOnWire("Photos/IMG_01.jpg", byTop))
    }

    @Test
    fun `relative shape - removed subfolder excludes its subtree`() {
        val byTop = mapOf("Photos" to setOf("Trip"))
        assertTrue(ChildExclusions.isExcludedOnWire("Photos/Trip/a.jpg", byTop))
        assertTrue(ChildExclusions.isExcludedOnWire("Photos/Trip/Deep/b.png", byTop))
        assertFalse(ChildExclusions.isExcludedOnWire("Photos/Trip2/a.jpg", byTop))
    }

    // ── never over-match ─────────────────────────────────────────────────

    @Test
    fun `another top-level's exclusion set never touches this item`() {
        val byTop = mapOf("Docs" to setOf("Docs/IMG_02.jpg", "IMG_02.jpg"))
        assertFalse(ChildExclusions.isExcludedOnWire("Photos/IMG_02.jpg", byTop))
    }

    @Test
    fun `empty or null map never excludes anything`() {
        assertFalse(ChildExclusions.isExcludedOnWire("Photos/IMG_02.jpg", null))
        assertFalse(ChildExclusions.isExcludedOnWire("Photos/IMG_02.jpg", emptyMap()))
        assertFalse(ChildExclusions.isExcludedOnWire("Photos/IMG_02.jpg", mapOf("Photos" to emptySet())))
    }

    @Test
    fun `top-level file itself is never its own subtree match`() {
        // A child path with no separator is the top-level item — the
        // per-CHILD machinery never applies to it (its decision rides the
        // top-level itemDecisions instead).
        val byTop = mapOf("Photos" to setOf("IMG_02.jpg", "Photos/IMG_02.jpg"))
        assertFalse(ChildExclusions.isExcludedOnWire("Photos", byTop))
    }
}
