package com.lumidrop.x.transfer

/**
 * ── GOAT-PASS 14 (Phase 1, §2.1 / §3): the ONE exclusion predicate shared ──
 * by every surface that narrows a folder's contents by the user's per-child
 * removals — the Queue's expand view and counter pill (SendFragment/
 * QueueAdapter), the browser Quick-Share ZIP walk (LumiDropServer), the
 * native-push manifest builder (NativePushClient) and the receiver's incoming
 * sheet declines (PendingNativePush.isExcludedNested).
 *
 * A child relPath is excluded when it was removed DIRECTLY ("IMG_02.jpg") or
 * when any ANCESTOR folder of it was removed ("Subfolder" removes
 * "Subfolder/IMG_02.jpg" whole). Paths are the folder's own natural
 * root-relative hierarchy — exactly what the expand view showed when the user
 * tapped the row's ✕.
 *
 * Pure Kotlin with zero Android dependency, on purpose: the engine test suite
 * (ChildExclusionsTest) compiles and runs this object on the JVM.
 */
object ChildExclusions {

    /** True when [relPath] (relative to the folder's own root) is removed by
     *  [excluded] — either an exact match or a descendant of a removed
     *  subfolder. An empty set never excludes anything. */
    fun isExcluded(relPath: String, excluded: Set<String>): Boolean {
        if (excluded.isEmpty() || relPath.isEmpty()) return false
        if (relPath in excluded) return true
        for (e in excluded) if (relPath.startsWith("$e/")) return true
        return false
    }

    /** True when [relPath] would still be delivered after [excluded] — the
     *  exact negation of [isExcluded], spelled out for call sites that read
     *  better as a keep-filter. */
    fun isKept(relPath: String, excluded: Set<String>): Boolean = !isExcluded(relPath, excluded)

    /** ── FINAL PASS 1 (§1.3/§1.4): the WIRE form of a receiver's per-child ──
     * exclusions, matched against one sender-side file's full relPath.
     *
     *  [byTop] is the status poll's `excludedNested` map — keyed by the
     *  top-level item's name. Its VALUES have shipped in two shapes:
     *   • the documented form, entries RELATIVE to the top-level item
     *     ("IMG_02.jpg", "Subfolder");
     *   • the form every receiver of this codebase actually emits today —
     *     FULL paths still carrying the top-level prefix
     *     ("Photos/IMG_02.jpg"), because the incoming sheet's ✕ passes
     *     "$top/$childPath" into excludeNativePushNestedPath.
     *  The old sender-side matcher stripped the top-level prefix from the
     *  child's relPath and compared ONLY against the documented shape — so
     *  against every real receiver, nothing ever matched: excluded children
     *  never resolved Declined from the polls, the folder's eligible total
     *  kept the original count for the whole batch ("0/30" instead of
     *  "0/27"), and each exclusion was only learned at that child's own
     *  POST refusal, one at a time. Matching BOTH shapes fixes the
     *  convergence on receivers old and new alike, with zero wire changes.
     *
     *  [relPath] is the sender-side file's own manifest path, ALWAYS
     *  full-form ("Photos/IMG_02.jpg"). Pure function on purpose: the JVM
     *  suite pins both shapes in FinalPass1ExcludedWireShapesTest. */
    fun isExcludedOnWire(relPath: String, byTop: Map<String, Set<String>>?): Boolean {
        if (byTop.isNullOrEmpty() || relPath.isEmpty()) return false
        val top = relPath.substringBefore('/')
        val set = byTop[top] ?: return false
        val rel = relPath.removePrefix("$top/")
        return isExcluded(rel, set) || isExcluded(relPath, set)
    }
}
