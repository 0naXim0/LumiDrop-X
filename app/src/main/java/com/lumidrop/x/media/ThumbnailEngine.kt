package com.lumidrop.x.media

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import com.lumidrop.x.server.MimeTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * Asynchronous, memory+disk-cached thumbnail loader for the Recent Files
 * grid (Phase 16 §2). Decoding happens off the main thread and is bounded
 * to a small number of concurrent jobs (heavy video/APK decodes are not
 * cheap — unbounded parallel decoding of a long Recent Files list would
 * contend hard for CPU/IO and could itself cause the jank this phase is
 * meant to eliminate).
 *
 * Two-tier cache:
 *  - Memory (LruCache, sized off ActivityManager's per-app memory class —
 *    never a fixed guess that's wrong on very low- or high-RAM devices).
 *  - Disk (app cache dir), keyed by a hash of path+size+lastModified so a
 *    changed/replaced file automatically invalidates instead of serving a
 *    stale thumbnail forever.
 *
 * Callers must pass a request tag (the file path) and re-check it after
 * the suspend call returns before applying the bitmap to an ImageView —
 * Recent Files isn't backed by a recycling RecyclerView today, but rows
 * CAN be torn down and rebuilt mid-flight (pull-to-refresh, a completed
 * upload triggering an immediate reload), so the same defensive check a
 * recycled ViewHolder would need still applies here.
 */
object ThumbnailEngine {

    private val memoryCache: LruCache<String, Bitmap> by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L / 8L).toInt().coerceIn(4_096, 32_768)
        object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }
    }

    // Bounds concurrent decode work regardless of how many rows request a
    // thumbnail at once — 3 keeps a handful of frames/album-art/APK
    // extractions moving without saturating the device.
    private val decodeGate = Semaphore(3)

    private fun diskCacheDir(context: Context): File =
        File(context.cacheDir, "thumbs").also { it.mkdirs() }

    // ── PASS 20 (§4 — the ID-keyed optimistic peek layer) ──────────────────
    // A stable MediaStore _ID → last-rendered bitmap map, consulted on the
    // MAIN thread by bind sites for an instant re-display with ZERO IO (no
    // File stat, no MD5). It is an OPTIMISTIC layer only: the authoritative
    // path+length+mtime key stays the source of truth, the background load()
    // result always overwrites whatever the peek showed, and entries ride
    // the same LRU byte budget (an android.util.LruCache of bitmaps, sized
    // alongside memoryCache) so memory stays bounded.
    private val idCache by lazy {
        val maxKb = (Runtime.getRuntime().maxMemory() / 1024L / 32L).toInt().coerceIn(2_048, 16_384)
        object : LruCache<String, Bitmap>(maxKb) {
            override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
        }
    }

    private fun idKey(mediaId: Long, sizePx: Int) = "id:$mediaId:$sizePx"

    @android.annotation.SuppressLint("UseSparseArrays")
    private fun idCachePut(mediaId: Long, sizePx: Int, bmp: Bitmap) {
        try { idCache.put(idKey(mediaId, sizePx), bmp) } catch (e: Exception) { /* best-effort */ }
    }

    /** Main-thread-safe instant lookup for a media row's last-rendered
     *  thumbnail. Returns null on any miss — the caller then launches the
     *  normal async load() (which will both refresh this entry and produce
     *  the authoritative bitmap). Never performs IO. */
    fun peekByMediaId(mediaId: Long, sizeDp: Int, context: Context): Bitmap? {
        if (mediaId < 0) return null
        return try { idCache.get(idKey(mediaId, context.dpToPx(sizeDp))) } catch (e: Exception) { null }
    }

    private fun cacheKey(path: String, sizePx: Int): String {
        val f = File(path)
        val raw = "$path:${f.length()}:${f.lastModified()}:$sizePx"
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    enum class FileCategory { IMAGE, VIDEO, AUDIO, APK, DOC, ARCHIVE, GENERIC }

    fun categoryOf(extension: String): FileCategory {
        val ext = extension.lowercase()
        return when {
            MimeTypes.isImage(ext)   -> FileCategory.IMAGE
            MimeTypes.isVideo(ext)   -> FileCategory.VIDEO
            MimeTypes.isAudio(ext)   -> FileCategory.AUDIO
            MimeTypes.isApk(ext)     -> FileCategory.APK
            MimeTypes.isArchive(ext) -> FileCategory.ARCHIVE
            MimeTypes.isDoc(ext)     -> FileCategory.DOC
            else                     -> FileCategory.GENERIC
        }
    }

    /** Returns a decoded thumbnail Bitmap, or null if this file type isn't
     *  previewable / decoding failed — callers fall back to the vector
     *  file-type icon + extension badge in that case (see Phase 16 §2). */
    suspend fun load(context: Context, path: String, category: FileCategory, sizeDp: Int,
                     mediaId: Long = -1L): Bitmap? {
        if (category != FileCategory.IMAGE && category != FileCategory.VIDEO &&
            category != FileCategory.AUDIO && category != FileCategory.APK) return null

        val sizePx = context.dpToPx(sizeDp)
        val key = cacheKey(path, sizePx)
        memoryCache.get(key)?.let {
            // PASS 20 (§4): a memory hit must also (re)publish the ID-keyed
            // optimistic entry so the NEXT bind of this row can peek-hit.
            if (mediaId >= 0) idCachePut(mediaId, sizePx, it)
            return it
        }

        return withContext(Dispatchers.IO) {
            decodeGate.withPermit {
                // Second check inside the gate — another job may have
                // finished populating this exact key while we were waiting.
                memoryCache.get(key)?.let { return@withPermit it }

                // ── FINAL PASS 9 (PART 2 — black corners AFTER RESTART): ──────
                // the disk cache used to write EVERY entry as JPEG — a format
                // with NO alpha channel. The in-memory LruCache holds
                // ARGB_8888 bitmaps, so the first render (before restart)
                // showed the icon's transparency correctly; after a restart
                // the disk copy was decoded, its transparent pixels baked to
                // BLACK by the JPEG round-trip, and the rounded-corner clip
                // showed those black corners again — exactly the reported
                // "correct while the app stayed open, black after restart".
                // Alpha-carrying thumbnails (APK launcher icons, ICO/PNG
                // glyphs) now persist as PNG; photos/video frames keep JPEG
                // (smaller, and they have no alpha to lose). The stale .jpg
                // twin of an alpha key is deleted so a pre-FP9 cache can
                // never answer with the baked-black copy.
                val alpha = category == FileCategory.APK
                val diskFile = File(diskCacheDir(context), "$key.${if (alpha) "png" else "jpg"}")
                if (alpha) {
                    val staleJpeg = File(diskCacheDir(context), "$key.jpg")
                    if (staleJpeg.exists()) try { staleJpeg.delete() } catch (e: Exception) { /* best-effort */ }
                }
                if (diskFile.exists()) {
                    val cached = android.graphics.BitmapFactory.decodeFile(diskFile.absolutePath)
                    if (cached != null) {
                        memoryCache.put(key, cached)
                        if (mediaId >= 0) idCachePut(mediaId, sizePx, cached)
                        return@withPermit cached
                    }
                }

                val fresh = try {
                    when (category) {
                        // PASS 20 (§4): context handed through — the Pass-19
                        // MediaStore thumbnail-first path now serves the
                        // NATIVE sheet too (previously this call site passed
                        // no context, so every native image cell decoded the
                        // original photo even though the fast path existed).
                        FileCategory.IMAGE -> ThumbnailShared.decodeScaled(path, sizePx, context)
                        FileCategory.VIDEO -> ThumbnailShared.videoThumb(path, sizePx)
                        FileCategory.AUDIO -> ThumbnailShared.audioThumb(path, sizePx)
                        // GOAT-PASS 11 (PART 3): ZIP-based package archives get
                        // their REAL embedded app icon (base APK extracted to a
                        // bounded cache file, then the same platform parser);
                        // ordinary .apk keeps the direct parse. Null either way
                        // → the caller's vector fallback.
                        FileCategory.APK   -> {
                            val ext = File(path).extension.lowercase()
                            if (ext == "xapk" || ext == "apkm" || ext == "apks")
                                ThumbnailShared.packageArchiveIcon(context, path, sizePx)
                            else ThumbnailShared.apkIcon(context, path, sizePx)
                        }
                        else -> null
                    }
                } catch (e: Exception) { null }

                if (fresh != null) {
                    memoryCache.put(key, fresh)
                    if (mediaId >= 0) idCachePut(mediaId, sizePx, fresh)
                    try {
                        FileOutputStream(diskFile).use {
                            fresh.compress(
                                if (alpha) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG,
                                if (alpha) 100 else 88, it)
                        }
                    } catch (e: Exception) { /* disk cache is best-effort only */ }
                }
                fresh
            }
        }
    }

    /** Best-effort cleanup for very old disk-cache entries — called
     *  opportunistically (app start), never blocking, never required for
     *  correctness since entries are content-addressed and self-invalidate. */
    suspend fun pruneDiskCache(context: Context, maxAgeMs: Long = 14L * 24 * 3600 * 1000) {
        withContext(Dispatchers.IO) {
            try {
                val cutoff = System.currentTimeMillis() - maxAgeMs
                diskCacheDir(context).listFiles()?.forEach { f ->
                    if (f.lastModified() < cutoff) f.delete()
                }
            } catch (e: Exception) { /* best-effort */ }
        }
    }

    /** Uri-based fallback for the Media Selector (Part 4.3): most
     *  MediaStore rows still carry a usable MediaColumns.DATA filesystem
     *  path (see [load] above), but scoped storage means that isn't
     *  guaranteed on every provider/OEM combination on API 29+. Shares
     *  this object's own memoryCache and decodeGate — same bounded
     *  concurrency, same LRU eviction — rather than standing up a second,
     *  parallel cache just for the fallback path. Deliberately skips the
     *  disk tier: a content:// row has no reliable (path, length,
     *  lastModified) signature to key a disk entry by the way a real file
     *  does, and this fallback is uncommon enough that memory-only caching
     *  for the picker's one on-screen session is enough — nothing here
     *  needs to survive an app restart.
     *
     *  ── Pass-24 (#6): [isApk] routes APK content URIs through a bounded ──
     *  cache-dir spool (see its own comment inside) so items picked via the
     *  Files tile render their real application icon exactly like the Apps
     *  tile's file-backed items do — one shared engine, no parallel
     *  implementation at the call sites. */
    suspend fun loadFromUri(context: Context, uri: android.net.Uri, isVideo: Boolean, sizeDp: Int,
                            isApk: Boolean = false): Bitmap? {
        val sizePx = context.dpToPx(sizeDp)
        val key = "uri:$uri:$sizePx"
        memoryCache.get(key)?.let { return it }

        return withContext(Dispatchers.IO) {
            decodeGate.withPermit {
                memoryCache.get(key)?.let { return@withPermit it }
                val fresh = try {
                    if (isApk) apkIconFromUri(context, uri, sizePx)
                    else if (isVideo) {
                        // MediaMetadataRetriever accepts a content:// uri
                        // directly via setDataSource(context, uri) — no
                        // filesystem path needed for the video case.
                        val r = android.media.MediaMetadataRetriever()
                        try {
                            r.setDataSource(context, uri)
                            val frame = r.getFrameAtTime(0L, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                            if (frame != null) android.media.ThumbnailUtils.extractThumbnail(frame, sizePx, sizePx)
                            else ThumbnailShared.makeVideoPlaceholder(sizePx)
                        } finally {
                            try { r.release() } catch (e: Exception) { }
                        }
                    } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        // Phase 5 (§5.7): loadThumbnail returns null (or
                        // throws) for image/x-icon rows — the system
                        // thumbnailer has no ICO support any more than
                        // BitmapFactory does — which is exactly the case
                        // that used to leave a valid .ico rendering as a
                        // blank/gray cell in the Media Picker on API 29+.
                        // The stream-based ICO reader is the rescue, run on
                        // the SAME already-open stream path rather than a
                        // second uri resolution.
                        context.contentResolver.loadThumbnail(uri, android.util.Size(sizePx, sizePx), null)
                            ?: context.contentResolver.openInputStream(uri)?.use { ThumbnailShared.icoThumb(it, sizePx) }
                    } else {
                        // Phase 8 (§8.3 memory hardening): the pre-Q branch
                        // used to decodeStream the image at FULL resolution
                        // — a 12 MP photo became a ~48 MB transient ARGB
                        // bitmap per picker-cell bind on API 21–28 (the
                        // scoped-storage rows with no filesystem path), and
                        // that unscaled bitmap then went STRAIGHT into the
                        // ≤32 MB LruCache, instantly evicting every properly
                        // scaled entry and thrashing the decode gate. The
                        // same two-pass bounds-then-scale discipline the
                        // path-based decodeScaled has always used, adapted
                        // to a re-openable stream: bounds pass consumes one
                        // stream, power-of-two inSampleSize decode consumes
                        // a second, exact-target createScaledBitmap finishes
                        // (recycling the rough pass). The ICO rescue keeps
                        // its own fresh stream exactly as before.
                        val bounds = android.graphics.BitmapFactory.Options().apply {
                            inJustDecodeBounds = true
                        }
                        try {
                            context.contentResolver.openInputStream(uri)?.use {
                                android.graphics.BitmapFactory.decodeStream(it, null, bounds)
                            }
                        } catch (e: Exception) { }
                        var sample = 1
                        while ((bounds.outWidth / (sample * 2) >= sizePx && bounds.outWidth > 0) ||
                               (bounds.outHeight / (sample * 2) >= sizePx && bounds.outHeight > 0)) sample *= 2
                        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
                        val rough = try {
                            context.contentResolver.openInputStream(uri)?.use { s ->
                                android.graphics.BitmapFactory.decodeStream(s, null, opts)
                            }
                        } catch (e: Exception) { null }
                        val scaled = when {
                            rough == null -> null
                            rough.width == sizePx && rough.height == sizePx -> rough
                            else -> try {
                                android.graphics.Bitmap.createScaledBitmap(rough, sizePx, sizePx, true)
                                    .also { if (it !== rough) rough.recycle() }
                            } catch (e: Exception) { rough }
                        }
                        scaled
                            // Phase 5 (§5.7): same ICO rescue for the pre-Q
                            // decodeStream path — decodeStream leaves the
                            // stream consumed either way, so this needs its
                            // own fresh stream.
                            ?: context.contentResolver.openInputStream(uri)?.use { ThumbnailShared.icoThumb(it, sizePx) }
                    }
                } catch (e: Exception) {
                    // Even the exception path deserves one ICO attempt —
                    // loadThumbnail has been observed to THROW for
                    // image/x-icon rather than cleanly returning null on
                    // some OEM thumbnailers.
                    if (!isVideo) try {
                        context.contentResolver.openInputStream(uri)?.use { ThumbnailShared.icoThumb(it, sizePx) }
                    } catch (e2: Exception) { null } else null
                }

                if (fresh != null) memoryCache.put(key, fresh)
                fresh
            }
        }
    }

    /** Pass-24 (#6): the real application icon for an APK that only exists
     *  as a content:// URI (the Files tile's SAF picks). Android's archive
     *  parser is strictly file-based (PackageManager.getPackageArchiveInfo
     *  takes a path), so the APK is spooled into the app's own cache dir,
     *  the icon is extracted through the very same ThumbnailShared.apkIcon
     *  the Apps tile's file-backed items use, and the spool is deleted
     *  immediately — the extracted bitmap rides the shared memoryCache, so
     *  the spool happens at most once per URI per session. Bounded: an
     *  oversized "apk" keeps the honest generic glyph instead of spooling
     *  an arbitrarily large blob for a 30dp icon. */
    private suspend fun apkIconFromUri(context: Context, uri: android.net.Uri, sizePx: Int): Bitmap? {
        val capBytes = 300L * 1024 * 1024
        val tmp = File(context.cacheDir, "thumb-apk-" + System.currentTimeMillis() + ".apk")
        try {
            context.contentResolver.openInputStream(uri)?.use { ins ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        total += n
                        if (total > capBytes) throw java.io.IOException("APK exceeds the icon-spool size cap")
                        out.write(buf, 0, n)
                    }
                }
            } ?: return null
            return ThumbnailShared.apkIcon(context, tmp.absolutePath, sizePx)
        } catch (e: Exception) {
            return null
        } finally {
            try { tmp.delete() } catch (e: Exception) { /* best-effort spool cleanup */ }
        }
    }

    private fun Context.dpToPx(dp: Int) = (dp * resources.displayMetrics.density).toInt()
}
