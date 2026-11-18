package com.lumidrop.x.transfer

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipEntry

/**
 * Generic node abstraction so ONE tree-walking core can enumerate both:
 *  (a) regular java.io.File trees   — WebUI folder / multi-select downloads
 *  (b) SAF DocumentFile trees       — host-app "Quick Share a folder" (apiQSDl),
 *                                      and a folder picked for a native peer push
 *
 * Originally a private `ZipNode` living inside LumiDropServer (ZIP streaming
 * only). Promoted to a shared top-level type for Phase 14: native
 * device-to-device folder pushes (NativePushClient) need to walk the exact
 * same two source kinds — a folder picked via Storage Access Framework, or a
 * plain filesystem path — and must preserve directory hierarchy, filenames,
 * and empty folders identically to how the ZIP path already does. Reusing
 * this one walker for both guarantees that guarantee automatically instead
 * of maintaining two subtly-different tree-walkers that could drift apart.
 *
 * LumiDropServer's ZIP code now imports and uses this type directly — see
 * addZipNode() there — rather than defining its own copy.
 */
sealed class FileTreeNode {
    abstract val name: String
    abstract val isDirectory: Boolean
    abstract fun children(): List<FileTreeNode>
    abstract fun openStream(): InputStream
    /** Best-known size in bytes; 0 for directories (never used for them). */
    abstract fun length(): Long
    /** Last-modified time in epoch millis, or 0 if unavailable — used to best-effort
     *  preserve modification time on the receiving side of a native push. */
    abstract fun lastModified(): Long
    /** A real filesystem path to this node, if it has one — lets a caller
     *  offer an "open locally" action for an FsNode-backed item. Always null
     *  for a DocNode (SAF trees are addressed by content:// URI, not path). */
    abstract val localPath: String?

    // ── Pass-36 Part 2 (#4): hidden-descendant EXCLUSION, enforced here at ─────
    // the ONE enumeration point every transfer walker funnels through (the
    // queue's expand walk, the counter pill's stats walk, the native push's
    // manifest build and the browser ZIP stream all walk FileTreeNode trees).
    // A hidden file or hidden folder (dot-prefixed name, or File.isHidden)
    // is dropped from children() unless the node was built with
    // includeHiddenChildren = true — the one state that exists only when the
    // user deliberately turned the Files picker's "Show hidden files" mode ON
    // and picked this tree while the hidden rows were visible. The default
    // is the SAFE default: a hidden item can never silently ride a selected
    // parent folder into a transfer, at any depth, on any send path. A LEAF
    // node (a single staged file) is unaffected — children() is never called
    // on a file by any walker, so an individually picked file goes out whole
    // exactly as picked, hidden or not.
    class FsNode(
        private val file: File,
        private val includeHiddenChildren: Boolean = false,
    ) : FileTreeNode() {
        override val name get() = file.name
        override val isDirectory get() = file.isDirectory
        override val localPath get() = file.absolutePath
        override fun children(): List<FileTreeNode> =
            (file.listFiles() ?: emptyArray())
                .asSequence()
                .filter { includeHiddenChildren || !isHiddenFile(it) }
                .map { FsNode(it, includeHiddenChildren) }
                .toList()
        override fun openStream(): InputStream = FileInputStream(file)
        override fun length(): Long = file.length()
        override fun lastModified(): Long = file.lastModified()
    }

    /** ── Pass-33 (#1): a VIRTUAL directory with a FIXED, pre-known child ──────
     *  set — the node form of a manifest folder (see PreviewItem.childManifest).
     *  children() answers the exact staged members instead of walking any
     *  physical directory, so every existing tree consumer (the queue's
     *  expand walk, the native-push manifest builder, the ZIP streamer)
     *  enumerates precisely the album's media — an APK or document sitting
     *  in the same physical directory can never ride along, and no
     *  unrelated subfolder is ever descended into, because no descent
     *  happens at all. The name participates in transfer naming exactly
     *  like a real folder's would ("Download/…"), and openStream() is
     *  never called on a directory by any walker. */
    class ManifestNode(
        override val name: String,
        private val fixedChildren: List<FileTreeNode>,
    ) : FileTreeNode() {
        override val isDirectory get() = true
        override val localPath: String? = null
        override fun children(): List<FileTreeNode> = fixedChildren
        override fun openStream(): InputStream = throw IOException("ManifestNode is a virtual directory")
        override fun length(): Long = 0L
        override fun lastModified(): Long = 0L
    }

    /**
     * GOAT (critical fix — folder expand/manifest speed): [children] fetches
     * every child's name/size/lastModified/type in ONE ContentResolver
     * query via DocumentsContract directly, instead of
     * DocumentFile.listFiles()'s "one query to list, then a SEPARATE query
     * per child for EACH property later accessed on it" pattern. For a
     * folder of a few hundred files, that's the difference between roughly
     * one Binder round-trip and several hundred — parallelizing those
     * hundreds of round-trips (still in place at every call site — see
     * NativePushClient.resolveTree / QueueAdapter.walkChildren) helps, but
     * doesn't change that there ARE hundreds of them; this changes that.
     * Every folder walk in the app (QueueAdapter.walkChildren for the
     * queue preview, NativePushClient.resolveTree for the real manifest,
     * LumiDropServer.addZipNode for a ZIP download) ultimately calls this,
     * so all three get the improvement for free from one change here.
     *
     * Every property is captured once — at construction for the root (via
     * the public constructor), or from the batched query's cursor for a
     * child — rather than DocumentFile's live re-query-on-every-access
     * approach. A deliberate "snapshot in time" trade-off: the walk that
     * builds a tree of these normally reads each value back only once
     * anyway (building a manifest, or a queue preview list), so there's no
     * real behavioral loss in practice, and it means nothing here ever
     * needs a second round-trip per child for a property already in hand
     * from the listing query.
     */
    class DocNode private constructor(
        private val uri: Uri,
        private val resolver: ContentResolver,
        private val knownName: String,
        private val knownIsDirectory: Boolean,
        private val knownLength: Long,
        private val knownLastModified: Long,
        // ── Pass-36 Part 2 (#4): same hidden-exclusion contract as FsNode — ──
        // a SAF provider has no isHidden flag, so a dot-prefixed display name
        // is the rule. Inherited by every descendant DocNode this query emits.
        private val includeHiddenChildren: Boolean = false,
    ) : FileTreeNode() {
        constructor(doc: DocumentFile, resolver: ContentResolver, includeHiddenChildren: Boolean = false) : this(
            doc.uri, resolver, doc.name ?: "unnamed", doc.isDirectory, doc.length(), doc.lastModified(),
            includeHiddenChildren
        )

        override val name get() = knownName
        override val isDirectory get() = knownIsDirectory
        override val localPath: String? = null
        override fun length(): Long = knownLength
        override fun lastModified(): Long = knownLastModified
        override fun openStream(): InputStream =
            resolver.openInputStream(uri) ?: throw IOException("Cannot open $uri")

        override fun children(): List<FileTreeNode> {
            // Every DocNode's own uri is itself a valid "tree uri" argument
            // for building its children's listing uri — a uri built via any
            // of the *UsingTree DocumentsContract methods (which is all
            // this class ever builds, root included, via
            // buildDocumentUriUsingTree below) carries the originating
            // tree's authority/permission-grant with it at every depth, not
            // just at the root. No separate "remember the original tree
            // uri" field needed.
            val parentDocId = try { DocumentsContract.getDocumentId(uri) } catch (e: Exception) { return emptyList() }
            val childrenUri = try { DocumentsContract.buildChildDocumentsUriUsingTree(uri, parentDocId) }
                catch (e: Exception) { return emptyList() }
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
            val out = ArrayList<FileTreeNode>()
            try {
                resolver.query(childrenUri, projection, null, null, null)?.use { c ->
                    val iId = c.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val iName = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val iSize = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val iMtime = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    val iMime = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (c.moveToNext()) {
                        val docId = if (iId >= 0) c.getString(iId) else null
                        if (docId == null) continue
                        val childUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
                        val childName = (if (iName >= 0) c.getString(iName) else null) ?: "unnamed"
                        // ── Pass-36 Part 2 (#4): the hidden rule applies to ──
                        // SAF trees too — a dot-prefixed document never enters
                        // a transfer enumeration unless the picker's hidden
                        // mode deliberately included this tree.
                        if (!includeHiddenChildren && childName.startsWith(".")) continue
                        val childMime = if (iMime >= 0) c.getString(iMime) else null
                        val childIsDir = childMime == DocumentsContract.Document.MIME_TYPE_DIR
                        val childSize = if (!childIsDir && iSize >= 0) c.getLong(iSize) else 0L
                        val childMtime = if (iMtime >= 0) c.getLong(iMtime) else 0L
                        out.add(DocNode(childUri, resolver, childName, childIsDir, childSize, childMtime, includeHiddenChildren))
                    }
                }
            } catch (e: Exception) {
                return emptyList()   // degrade to "folder appears empty" rather than crash the walk
            }
            return out
        }
    }

    // ── GOAT-PASS 14 (Phase 1, §2.1 — the stable send-time snapshot) ─────────
    // A fully materialized, IMMUTABLE virtual subtree: names, directory-ness,
    // sizes and the exact child LIST are frozen at construction, and only the
    // BYTES are opened live (through [opener]). This is the node form of
    // NativePushClient's ResolvedNode snapshot: the native ZIP-batch send
    // resolves every folder's tree once, at batch-build time (the same moment
    // the ordinary manifest builder snapshots its own trees), prunes the
    // user's queue-time child exclusions out of it, and hands the frozen
    // result to FileTreeNode.ZipBatchNode — so an already-started transfer
    // can never silently change its contents when the queue or the source
    // directory changes afterwards.
    class SnapshotNode(
        override val name: String,
        private val isDir: Boolean,
        private val knownLength: Long,
        private val knownMtime: Long,
        private val fixedChildren: List<SnapshotNode>,
        private val opener: () -> InputStream,
    ) : FileTreeNode() {
        override val isDirectory get() = isDir
        override val localPath: String? = null
        override fun children(): List<FileTreeNode> = fixedChildren.toList()
        override fun openStream(): InputStream = opener()
        override fun length(): Long = knownLength
        override fun lastModified(): Long = knownMtime
    }

    // ── GOAT-PASS 14 (Phase 1, §1.5): a VIRTUAL FILE whose bytes ARE a ZIP ──
    // archive synthesized live from [roots] (one node per top-level batch
    // entry — files, folders, snapshot trees) plus [texts] (queued text
    // messages, written as "<name>.txt" entries). This is the node the
    // native-push leg streams for the new app-side "send queue as one ZIP"
    // mode: the ENTIRE existing native pipeline (manifest entry, pre-
    // registered row, chunked POST, receiver's writeRawStream, Received/
    // History registration) sees one ordinary file whose openStream() happens
    // to produce archive bytes — no second transfer system anywhere.
    //
    // Memory profile is the exact pattern the browser-side ZIP streaming
    // (LumiDropServer.apiZipStream / apiQSDl) has always used: openStream()
    // wires a 256 KB pipe, a daemon writer thread walks the roots through the
    // SHARED ZipTree walker (same entry naming, same empty-dir preservation,
    // same skip-on-error) with Deflater.NO_COMPRESSION, and the archive never
    // exists anywhere in full — no temp file, no whole-archive buffer. Each
    // openStream() call is an independent generation (a retry/resend builds a
    // fresh writer); closing the returned stream tears the writer down
    // through the broken pipe.
    class ZipBatchNode(
        override val name: String,
        private val roots: List<FileTreeNode>,
        private val texts: List<Pair<String, String>> = emptyList(),
    ) : FileTreeNode() {
        override val isDirectory get() = false
        override val localPath: String? = null
        override fun children(): List<FileTreeNode> = emptyList()
        override fun length(): Long = -1L          // genuinely unknown until the archive is fully streamed
        override fun lastModified(): Long = 0L

        // ── GOAT-PASS 14 (§1.8): entries this archive had to SKIP (a source ──
        // file went unreadable mid-stream). Every generation's writer appends
        // here; the sender's send loop drains it after a successful stream and
        // resolves the row FAILED when anything was skipped — an incomplete
        // archive is never reported as a completed transfer.
        private val skippedEntries = java.util.Collections.synchronizedList(ArrayList<String>())

        /** Drains and returns the entries skipped by the archive generations
         *  streamed so far (empty when every entry made it in). */
        fun takeSkippedEntries(): List<String> {
            if (skippedEntries.isEmpty()) return emptyList()
            synchronized(skippedEntries) {
                val out = ArrayList(skippedEntries)
                skippedEntries.clear()
                return out
            }
        }

        override fun openStream(): InputStream {
            val pipeOut = java.io.PipedOutputStream()
            val pipeIn = java.io.PipedInputStream(pipeOut, 256 * 1024)
            val writer = Thread {
                try {
                    java.io.BufferedOutputStream(pipeOut, 64 * 1024).use { buffered ->
                        java.util.zip.ZipOutputStream(buffered).use { zos ->
                            // Media/APK payloads are already compressed — NO_COMPRESSION
                            // keeps the writer fast and lag-free on low-end senders,
                            // byte-for-byte the server-side streamer's choice.
                            zos.setLevel(java.util.zip.Deflater.NO_COMPRESSION)
                            val seen = HashSet<String>()
                            for ((textName, text) in texts) {
                                val entryName = uniqueTreePath(seen,
                                    if (textName.endsWith(".txt")) textName else "$textName.txt")
                                zos.putNextEntry(ZipEntry(entryName))
                                try { zos.write(text.toByteArray(Charsets.UTF_8)) }
                                catch (e: Exception) { /* keep the archive alive */ }
                                zos.closeEntry()
                            }
                            for (root in roots) ZipTree.addNode(zos, root, "", seen,
                                onSkip = { path -> skippedEntries.add(path) })
                        }
                    }
                } catch (e: Exception) {
                    // Reader closed / cancelled mid-stream — the writer ends with the pipe.
                } finally {
                    try { pipeOut.close() } catch (ignored: java.io.IOException) {}
                }
            }
            writer.isDaemon = true
            writer.start()
            return pipeIn
        }
    }
}

/**
 * De-duplicates a proposed relative path against sibling paths already
 * claimed at the same level (e.g. two top-level sources both literally named
 * "Photos", or two native-push batches from the same peer landing in the
 * same destination folder). Directory paths (trailing '/') and file paths
 * share one namespace but can never collide with each other by construction.
 *
 * Shared by LumiDropServer's ZIP entry naming (uniqueZipName, a thin
 * one-line wrapper kept there for call-site clarity) and NativePushClient's
 * manifest builder, so both produce identical collision-safe naming from the
 * same selection.
 */
/** ── Pass-36 Part 2 (#4): true for a filesystem entry this app treats as ─────
 *  hidden — a dot-prefixed name (the universal convention) or the platform's
 *  own hidden flag. Shared by the node walkers' exclusion rule. */
private fun isHiddenFile(f: File): Boolean =
    f.name.startsWith(".") || (try { f.isHidden } catch (e: Exception) { false })

fun uniqueTreePath(seen: MutableSet<String>, proposed: String): String {
    if (seen.add(proposed)) return proposed
    val isDir  = proposed.endsWith("/")
    val body   = if (isDir) proposed.removeSuffix("/") else proposed
    val slash  = body.lastIndexOf('/')
    val base   = if (slash >= 0) body.substring(slash + 1) else body
    val prefix = if (slash >= 0) body.substring(0, slash + 1) else ""
    val dot    = base.lastIndexOf('.')
    var n = 1
    while (true) {
        val candidateBase = if (!isDir && dot > 0) "${base.substring(0, dot)}_$n${base.substring(dot)}"
                             else "${base}_$n"
        val candidate = prefix + candidateBase + (if (isDir) "/" else "")
        if (seen.add(candidate)) return candidate
        n++
    }
}
