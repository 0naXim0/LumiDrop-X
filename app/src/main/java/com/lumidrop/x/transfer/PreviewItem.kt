package com.lumidrop.x.transfer

/**
 * A single staged/queued item awaiting share — either a browser-bound
 * "Quick Share" entry or a native-P2P push target. Deliberately a
 * top-level, standalone data class rather than nested inside SendFragment
 * (or, originally, MainActivity): it crosses a real file boundary —
 * NativePushClient.kt consumes it too — so nesting it inside whichever UI
 * class happens to build it is a coupling that breaks the moment that UI
 * class is refactored (exactly what happened when MainActivity's Send
 * screen became SendFragment: NativePushClient.kt was still typed against
 * the now-gone `MainActivity.PreviewItem`).
 */
data class PreviewItem(
    val name: String,
    val path: String?,
    val uri: String?,
    val size: Long,
    val mime: String = "application/octet-stream",
    val isFolder: Boolean = false,
    val textContent: String? = null,
    // GOAT v2 (item 5): natural (pre-dedup-rename) paths, relative to this
    // folder's own root, that the user removed from the queue after
    // expanding it — e.g. "Subfolder/IMG_02.jpg". Every consumer that
    // walks a folder item's tree (NativePushClient's flattenResolved,
    // LumiDropServer's addZipNode) must skip anything in this set so
    // "remove individual internal files" actually changes what gets sent,
    // not just what the Queue happens to display. Meaningless for a
    // non-folder item; always empty for one.
    val excludedChildPaths: Set<String> = emptySet(),
    // ── Pass-33 (#1): a folder whose contents are FIXED at staging time to ────
    // this exact list — a LOGICAL folder, not a live view of a physical
    // directory. Non-null only for a media-album folder: selecting the
    // "Download" album must queue ONE folder entry whose contents are
    // precisely the images/videos the Media Picker displayed in that album
    // (MediaAlbum.items — the same bucket members the album grid was
    // grouped from), never the whole physical Download directory with its
    // APKs, documents and subfolders. Every folder consumer honors it the
    // same way: enumerate THESE children instead of walking the directory
    // (QueueAdapter.walkChildren for the expand view, SendFragment's
    // folder-stats engine for the counter pill, NativePushClient's
    // manifest builder for native pushes, LumiDropServer's zip streamer
    // for browser downloads — see ManifestNode in FileTreeNode.kt). A null
    // manifest means an ordinary physical folder, walked live as always.
    // [relPath] is relative to the folder's own root (for an album, the
    // member's plain filename — every bucket member shares one parent
    // directory); [path]/[uri] are the member's own real addresses.
    val childManifest: List<FolderChild>? = null,
    // ── Pass-36 Part 2 (#4): hidden-descendant eligibility for a FOLDER ───────
    // item. False (the default, everywhere) means every walker that
    // enumerates this folder's live physical tree — the queue's expand
    // walk, the counter pill's stats walk, the native push's manifest
    // build, the browser ZIP stream — SKIPS hidden files and hidden
    // subfolders (dot-prefixed names, or File.isHidden) at every depth,
    // so a hidden item can never silently ride a selected parent into a
    // transfer. True only when the folder was picked while the Files
    // picker's "Show hidden files" mode was deliberately ON — the one
    // state in which hidden descendants were VISIBLE and therefore fair
    // selection candidates. A manifest folder ignores this flag: its
    // members were fixed at staging time (already filtered at build).
    val includeHiddenChildren: Boolean = false,
) {
    /** One fixed member of a manifest folder — see
     *  [PreviewItem.childManifest]'s own doc comment. */
    data class FolderChild(
        val relPath: String,
        val path: String?,
        val uri: String?,
        val size: Long,
        val mime: String,
    ) {
        /** Resolves this member to a [FileTreeNode] for the walkers that
         *  stream bytes (native push, zip) — the real file by path, or the
         *  provider document by single-document uri. Null only when the
         *  member carries neither address. */
        fun toNode(context: android.content.Context): FileTreeNode? = try {
            when {
                path != null -> FileTreeNode.FsNode(java.io.File(path))
                uri != null -> androidx.documentfile.provider.DocumentFile
                    .fromSingleUri(context, android.net.Uri.parse(uri))
                    ?.let { FileTreeNode.DocNode(it, context.contentResolver) }
                else -> null
            }
        } catch (e: Exception) { null }
    }
}

/**
 * ── Pass-36 Part 2 (#1): the queue title for pasted/typed text ───────────────
 *
 * A staged text item used to be titled "Text message" everywhere — a generic
 * label that said nothing about the content. This derives a clean, readable
 * preview title FROM the content itself, used by every staging site (the
 * Paste tile, the Text tile's Share action, and the live web-queue name the
 * server derives for the same text), so the queue row, the text viewer's
 * header and the native-push filename all describe what the item actually is.
 *
 * Rules:
 *  • filename-hostile characters (slash, backslash, colon, wildcard, quotes,
 *    angle brackets, pipe) become spaces — the title also becomes a FILE name
 *    on the native-push side, so it must survive every filesystem;
 *  • every whitespace run (newlines, tabs, repeated spaces) collapses to one
 *    space, and leading/trailing whitespace is dropped — a multi-line paste
 *    still yields one tidy line;
 *  • capped at [MAX_LEN] characters, cut at a word boundary when one exists
 *    reasonably early, with a single "…" — the queue layout never grows for
 *    a long paste;
 *  • never empty: content that is nothing but whitespace/hostile characters
 *    falls back to the classic "Text message" label (the staging sites
 *    already refuse blank input, so this is a last-resort guard, not a path
 *    real content takes).
 *
 * The ORIGINAL text is never modified — only the title is derived from it;
 * the item's [PreviewItem.textContent] carries the complete, verbatim
 * payload for previewing and sending exactly as before.
 */
fun textPreviewTitle(text: String): String {
    val cleaned = text.replace(REGEX_TITLE_HOSTILE, " ")
    val normalized = cleaned.trim().replace(REGEX_TITLE_WHITESPACE, " ")
    if (normalized.isEmpty()) return "Text message"
    if (normalized.length <= MAX_LEN) return normalized
    val cut = normalized.substring(0, MAX_LEN - 1)
    val lastSpace = cut.lastIndexOf(' ')
    return (if (lastSpace >= MIN_KEEP_LEN) cut.substring(0, lastSpace) else cut).trimEnd() + "…"
}

private val REGEX_TITLE_HOSTILE = Regex("[\\\\/:*?\"<>|\\r\\n\\t]")
private val REGEX_TITLE_WHITESPACE = Regex("\\s+")
private const val MAX_LEN = 60
private const val MIN_KEEP_LEN = 40
