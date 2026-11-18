package com.lumidrop.x.transfer

import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * ── GOAT-PASS 14 (Phase 1, §1.5): the ONE ZIP tree-walking core ──────────────
 * This is the exact walker that used to live privately inside LumiDropServer
 * (as `addZipNode`) — promoted to the shared transfer package so the new
 * app-side ZIP batch sends (browser Quick-Share leg AND native-push leg,
 * via FileTreeNode.ZipBatchNode) stream archives through the very same
 * entry-naming, directory-preservation and skip-on-error behaviour the
 * established browser "Download as zip" always had. One implementation, four
 * call sites (apiZipStream, apiQSDl's folder branch, apiQSDl's zip-batch
 * branch, ZipBatchNode's writer thread) — no second ZIP system.
 *
 * GOAT-PASS 14 (§2.1): the walker now accepts an [excluded] set of
 * root-relative natural paths (the sender's queue-time child removals, or the
 * receiver-side incoming declines remapped to the archive root's own
 * prefix). The check runs on the NATURAL path (parentPrefix + name) BEFORE
 * the cross-source de-dup rename, matching what the Queue's expand view
 * showed when the items were removed — the same rule NativePushClient's
 * flattenResolved has always documented.
 */
object ZipTree {

    /**
     * Recursively writes one node into the zip. Directories ALWAYS get their
     * own explicit entry (trailing '/') even when empty — this is what makes
     * empty folders survive extraction. Unreadable file entries are skipped
     * (never fatal to the archive) and, when [onSkip] is supplied, REPORTED
     * by their natural path — a streamed archive's sender uses that to refuse
     * a silent partial success (GOAT-PASS 14 §1.8). [excluded] holds natural
     * paths relative to the ROOT of this walk (the first call's
     * [parentPrefix] is "" — a folder root's own children therefore match by
     * their root-relative relPath when the caller passes the root's children
     * set, or by "<rootName>/<relPath>" when the caller remaps; each call
     * site documents which form it passes).
     */
    fun addNode(zos: ZipOutputStream, node: FileTreeNode, parentPrefix: String,
                seen: MutableSet<String>, excluded: Set<String> = emptySet(),
                onSkip: ((String) -> Unit)? = null) {
        val naturalPath = parentPrefix + node.name
        if (excluded.isNotEmpty()) {
            if (naturalPath in excluded) return
            for (e in excluded) if (naturalPath.startsWith("$e/")) return
        }
        if (node.isDirectory) {
            val dirPath = uniqueTreePath(seen, naturalPath + "/")
            zos.putNextEntry(ZipEntry(dirPath))
            zos.closeEntry()
            for (child in node.children()) addNode(zos, child, dirPath, seen, excluded, onSkip)
        } else {
            val filePath = uniqueTreePath(seen, naturalPath)
            zos.putNextEntry(ZipEntry(filePath))
            try {
                node.openStream().buffered(65536).use { it.copyTo(zos, 65536) }
            } catch (e: Exception) {
                // unreadable entry — skip it, keep the archive alive, and let
                // an interested caller (a streamed batch send) learn the truth.
                onSkip?.invoke(naturalPath)
            }
            zos.closeEntry()
        }
    }
}
