package com.lumidrop.x.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ══ GOAT-PASS 14 (Phase 1, §2.1 / §3 / §4) — the ONE exclusion predicate ══
 *
 * Pins [ChildExclusions] — the single root-relative-removal rule every
 * surface applies to narrow a folder by the user's per-child removals:
 * the Queue's expand view and counter pill, the browser Quick-Share ZIP
 * walk, the native-push manifest builder (pruneResolved / snapshotOf) and
 * the receiver's incoming-sheet declines (PendingNativePush.isExcluded-
 * Nested). Every other fix in this pass funnels through this predicate, so
 * its exact semantics get pinned here on the JVM:
 *
 *  • a DIRECT removal ("IMG_02.jpg") excludes exactly that child;
 *  • an ANCESTOR-folder removal ("Subfolder") excludes that whole subtree
 *    ("Subfolder/IMG_02.jpg", "Subfolder/Deep/x.png" …) — one ✕ on a
 *    subfolder row means the folder, not just its first level;
 *  • a name that merely STARTS LIKE a removed path ("Subfolder2/…" vs the
 *    removed "Subfolder") is NEVER excluded — the separator matters;
 *  • an empty set (no removals — the default everywhere) excludes nothing;
 *  • [isKept] is the exact negation, for the keep-filter call sites.
 *
 * Plus the acceptance arithmetic of the brief's §2 scenario: a 30-item
 * folder with 6 removed children keeps EXACTLY 24 — no stragglers from a
 * removed subfolder, no collateral damage on siblings.
 */
class GoatPass14ChildExclusionsTest {

    // ── direct removal ──────────────────────────────────────────────────

    @Test
    fun `direct child removal excludes exactly that child`() {
        val excluded = setOf("IMG_02.jpg")
        assertTrue(ChildExclusions.isExcluded("IMG_02.jpg", excluded))
        assertFalse(ChildExclusions.isExcluded("IMG_01.jpg", excluded))
        assertFalse(ChildExclusions.isExcluded("IMG_022.jpg", excluded))   // prefix, not the same file
    }

    @Test
    fun `empty removal set never excludes anything`() {
        assertFalse(ChildExclusions.isExcluded("IMG_02.jpg", emptySet()))
        assertFalse(ChildExclusions.isExcluded("Sub/IMG_02.jpg", emptySet()))
        assertTrue(ChildExclusions.isKept("Sub/IMG_02.jpg", emptySet()))
    }

    // ── ancestor-folder removal ─────────────────────────────────────────

    @Test
    fun `removing a subfolder excludes its entire subtree at any depth`() {
        val excluded = setOf("Subfolder")
        assertTrue(ChildExclusions.isExcluded("Subfolder/IMG_02.jpg", excluded))
        assertTrue(ChildExclusions.isExcluded("Subfolder/Deep/x.png", excluded))
        assertTrue(ChildExclusions.isExcluded("Subfolder", excluded))      // the folder itself too
        assertFalse(ChildExclusions.isExcluded("Subfolder2/IMG_02.jpg", excluded))   // sibling prefix
        assertFalse(ChildExclusions.isExcluded("IMG_02.jpg", excluded))
    }

    @Test
    fun `removing a nested folder excludes only beneath its own path`() {
        val excluded = setOf("A/B")
        assertTrue(ChildExclusions.isExcluded("A/B/c.jpg", excluded))
        assertTrue(ChildExclusions.isExcluded("A/B/D/e.jpg", excluded))
        assertFalse(ChildExclusions.isExcluded("A/c.jpg", excluded))       // A itself survives
        assertFalse(ChildExclusions.isExcluded("A/BX/c.jpg", excluded))   // separator matters
    }

    // ── keep-filter form ────────────────────────────────────────────────

    @Test
    fun `isKept is the exact negation of isExcluded`() {
        val excluded = setOf("a.jpg", "Sub")
        for (p in listOf("a.jpg", "b.jpg", "Sub", "Sub/x", "Sub/x/y", "SubX", "")) {
            assertEqualsPair(p, excluded)
        }
    }

    private fun assertEqualsPair(relPath: String, excluded: Set<String>) {
        val excludedNow = ChildExclusions.isExcluded(relPath, excluded)
        assertTrue("isKept must negate isExcluded for '$relPath'",
            ChildExclusions.isKept(relPath, excluded) == !excludedNow)
    }

    // ── the brief's §2 acceptance arithmetic: 30 − 6 = 24 ───────────────

    @Test
    fun `folder of 30 with 6 removed keeps exactly 24`() {
        // 30 children: 27 loose files (IMG_01..IMG_27) + "Sub" with 3 files.
        val all = (1..27).map { "IMG_%02d.jpg".format(it) } +
            listOf("Sub/a.jpg", "Sub/b.jpg", "Sub/c.jpg")
        org.junit.Assert.assertEquals("fixture must hold exactly 30 children", 30, all.size)

        // 6 removed: 3 direct files + the whole "Sub" subfolder (its 3 files
        // count as removed THROUGH their ancestor).
        val excluded = setOf("IMG_03.jpg", "IMG_11.jpg", "IMG_27.jpg", "Sub")
        org.junit.Assert.assertEquals("6 removals (3 files + 1 folder row + its 2 remaining children)",
            6, (1..27).count { ChildExclusions.isExcluded("IMG_%02d.jpg".format(it), excluded) } +
                listOf("Sub/a.jpg", "Sub/b.jpg", "Sub/c.jpg").count { ChildExclusions.isExcluded(it, excluded) })

        val kept = all.filter { ChildExclusions.isKept(it, excluded) }
        org.junit.Assert.assertEquals("exactly 24 children survive the queue's removals",
            24, kept.size)
        assertFalse("no removed subfolder child may ride along", kept.any { it.startsWith("Sub/") })
        assertTrue("untouched siblings survive", "IMG_01.jpg" in kept)
        assertTrue("untouched siblings survive", "IMG_26.jpg" in kept)
    }
}
