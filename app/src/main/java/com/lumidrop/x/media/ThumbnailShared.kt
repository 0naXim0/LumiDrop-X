package com.lumidrop.x.media

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Shared bitmap-thumbnail strategies used by BOTH the embedded web server
 * (LumiDropServer.apiThumb — browsers viewing the file list) and the native
 * app's own Recent Files grid (ThumbnailEngine). This used to be duplicated
 * private logic inside LumiDropServer only; moved here so the native UI
 * doesn't reinvent a weaker version, and any future fix to seek-position
 * fallback / sampling quality benefits both call sites at once.
 *
 * decodeScaled / videoThumb / makeVideoPlaceholder are moved verbatim from
 * LumiDropServer's previous private implementations — same algorithm, same
 * fallback order, unchanged behavior. audioThumb and apkIcon are new.
 */
object ThumbnailShared {

    // Two-pass strategy:
    //  1. Use the largest power-of-2 inSampleSize that keeps the decoded
    //     bitmap AT LEAST maxSz on its long edge — a high-quality decode.
    //  2. Apply a final bilinear createScaledBitmap to the exact target size.
    // A naive single-pass divide-by-maxSz produces over-downsampled, blocky
    // thumbnails (especially at 80-120px).
    // ── PASS 19 (§4.3 — the system's own thumbnail store, first) ──────────
    // MediaStore maintains ready-made thumbnails for every image it has
    // scanned — the Gallery's own micro (96px) and mini (512px) kinds. For
    // a web tile (40–256px) that entry is a few-millisecond read versus the
    // bounds+inSampleSize decode of the ORIGINAL (hundreds of ms and tens
    // of MB of transient bitmap for a 48MP photo). Resolution by path
    // needs no extra permissions (it is the documented legacy API). Used
    // ONLY when the requested size fits what the store offers; everything
    // else takes the existing path unchanged.
    fun mediaStoreImageThumb(context: Context, path: String, maxSz: Int): Bitmap? {
        if (maxSz > 512) return null
        return try {
            val id = try {
                val cr = context.contentResolver
                val c = cr.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    arrayOf(MediaStore.Images.Media._ID),
                    MediaStore.Images.Media.DATA + "=?",
                    arrayOf(path), null)
                c?.use { if (it.moveToFirst()) it.getLong(0) else null } ?: return null
            } catch (e: Exception) { return null } ?: return null
            @Suppress("DEPRECATION")
            val kind = if (maxSz <= 96) MediaStore.Images.Thumbnails.MICRO_KIND
                       else MediaStore.Images.Thumbnails.MINI_KIND
            @Suppress("DEPRECATION")
            MediaStore.Images.Thumbnails.getThumbnail(context.contentResolver, id, kind, null)
        } catch (e: Exception) { null }
    }

    fun decodeScaled(path: String, maxSz: Int, context: Context? = null): Bitmap? {
        // ── PASS 19 (§4.3): the system thumbnail store first — see
        // mediaStoreImageThumb's note. Only a miss (unscanned file, no
        // context available, or a request larger than the store's kinds)
        // falls through to the original-file decode path, unchanged.
        if (context != null) mediaStoreImageThumb(context, path, maxSz)?.let { sys ->
            // The store's entry may be smaller than the tile wants — only
            // use it when it is close enough to the request that upscaling
            // would not look soft (>= 60% of the target on the long edge).
            val long = maxOf(sys.width, sys.height)
            if (long * 100 >= maxSz * 60) return sys
            sys.recycle()
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        // Phase 5 (§5.7): a failed bounds decode (outWidth/outHeight stay
        // <= 0) is exactly the "the file exists but nothing can draw it"
        // case .ico files live in — BitmapFactory has no ICO decoder, so
        // every .ico that reaches here previously returned null and both
        // the native transfer rows and the web client's /api/thumb showed
        // a blank/gray area for a perfectly valid file. icoThumb() is the
        // deliberate rescue ON THIS FAILURE PATH (not a blind pre-check on
        // the extension): anything BitmapFactory can already decode
        // behaves exactly as before, and anything it can't costs icoThumb
        // one 6-byte header check to reject — cheap for genuinely corrupt
        // files, correct for .ico renamed to anything else too.
        if (opts.outWidth <= 0 || opts.outHeight <= 0) {
            return icoThumb(path, maxSz)
        }
        val rawLarger = maxOf(opts.outWidth, opts.outHeight).takeIf { it > 0 } ?: return null
        var sample = 1
        while (sample * 2 * maxSz <= rawLarger) sample *= 2
        opts.inJustDecodeBounds = false
        opts.inSampleSize = sample
        val rough = try { BitmapFactory.decodeFile(path, opts) } catch (e: Exception) { return null } ?: return null
        val scale = maxSz.toFloat() / maxOf(rough.width, rough.height)
        if (scale >= 1f) return rough  // already fits — no upscale needed
        val w = (rough.width  * scale).toInt().coerceAtLeast(1)
        val h = (rough.height * scale).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(rough, w, h, true)  // bilinear filter
        rough.recycle()
        return scaled
    }

    @Suppress("DEPRECATION")
    fun videoThumb(path: String, maxSz: Int = 256): Bitmap? {
        // Strategy 1 — MediaMetadataRetriever with multiple seek positions.
        // Some MKV / H.265 / AV1 files have no sync frame at t=0, so
        // getFrameAtTime(0, CLOSEST_SYNC) returns null while frames at later
        // timestamps succeed. 5 positions covers virtually all real files.
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(path)
            val timePositions = longArrayOf(0L, 1_000_000L, 3_000_000L, 10_000_000L, 30_000_000L)
            for (t in timePositions) {
                val frame = try {
                    r.getFrameAtTime(t, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (e: Exception) { null }
                if (frame != null) {
                    val scaled = ThumbnailUtils.extractThumbnail(frame, maxSz, maxSz)
                    frame.recycle()
                    return scaled
                }
            }
        } catch (e: Exception) {
            // setDataSource failed — file may be unreadable or unsupported codec
        } finally {
            try { r.release() } catch (e: Exception) { }
        }

        // Strategy 2 — legacy ThumbnailUtils (pre-Q devices only)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val bmp = try {
                ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND)
            } catch (e: Exception) { null }
            if (bmp != null) return bmp
        }

        // Strategy 3 — dark placeholder with a play icon, so a broken-image
        // glyph never appears in either UI for an unsupported codec.
        return makeVideoPlaceholder(maxSz)
    }

    /** Embedded album art, if the audio file has any. Returns null (never a
     *  placeholder) so callers fall back to the generic vector music icon —
     *  most audio files have no embedded art, and that's a normal, expected
     *  case, not a failure worth a bespoke placeholder bitmap for. */
    fun audioThumb(path: String, maxSz: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(path)
            val art = r.embeddedPicture ?: return null
            val bmp = BitmapFactory.decodeByteArray(art, 0, art.size) ?: return null
            ThumbnailUtils.extractThumbnail(bmp, maxSz, maxSz)
        } catch (e: Exception) {
            null
        } finally {
            try { r.release() } catch (e: Exception) { }
        }
    }

    /** An installed-or-not-yet-installed .apk's own launcher icon, extracted
     *  straight from the archive. getPackageArchiveInfo() returns an
     *  ApplicationInfo whose icon-loading methods need sourceDir /
     *  publicSourceDir pointed at the archive manually — without that this
     *  silently returns a blank/default icon instead of throwing, which is
     *  why it's easy to get subtly wrong. */
    /** ── GOAT-PASS 11 (PART 3): the REAL application icon from a ZIP-based ──
     *  package archive (.xapk/.apkm/.apks). The Pass-10 pipeline classified
     *  these as APK-category but ran the platform parser on the CONTAINER
     *  (a ZIP) — always null, so every XAPK fell back to the green vector.
     *
     *  The fix goes to the actual source: open the archive, find the base
     *  APK ("base.apk", else the single APK entry, else manifest.json's
     *  base_apk_name — same base-identification rules as PackageInspector),
     *  stream JUST that entry to a bounded cache file, and run it through
     *  the SAME [apkIcon] platform parser ordinary APKs use (adaptive and
     *  legacy launcher icons handled by the OS itself; alpha preserved —
     *  the engine saves this category as lossless PNG). Never guesses: an
     *  icon that cannot be extracted returns null and the caller shows the
     *  clean vector fallback.
     *
     *  The temp file lives in the app's own cache dir (never next to user
     *  data) and is best-effort deleted after use; a crash mid-way leaves
     *  at most one orphaned cache file, not user-visible damage. */
    fun packageArchiveIcon(context: Context, path: String, sz: Int): Bitmap? {
        return try {
            val archive = java.io.File(path)
            if (!archive.isFile) return null
            java.util.zip.ZipFile(archive).use { zf ->
                val names = zf.entries().asSequence().map { it.name }.toMutableList()
                val apkEntries = names.filter { it.endsWith(".apk", true) }
                if (apkEntries.isEmpty()) return null

                // ── GOAT-PASS 13 (PART 3): base identification is the SAME ──
                // metadata-driven evidence hierarchy as PackageInspector —
                // the icon comes from the APK that will actually be
                // installed, whatever its filename (the Carnage Wars case:
                // "com.ziczac.shooter.apk" IS the base). Filenames are
                // supporting evidence only, never the gate they were in
                // GP11/GP12 (where a multi-APK archive without a literal
                // "base.apk" returned null BEFORE the icon.png fallback could
                // ever run — the reported "no real icon" symptom).
                val xm = com.lumidrop.x.archive.XapkManifest.read(zf)
                val ids = apkEntries.map { it to com.lumidrop.x.archive.ApkManifestReader.readFromZip(zf, it) }
                val baseManifests = ids.filter { it.second?.isBase == true }
                val baseName: String? = when {
                    xm?.baseEntryName != null && apkEntries.any { it.equals(xm.baseEntryName, true) } ->
                        apkEntries.first { it.equals(xm.baseEntryName, true) }
                    baseManifests.size == 1 -> baseManifests[0].first
                    baseManifests.size > 1 -> baseManifests.maxByOrNull { e ->
                        zf.getEntry(e.first)?.size ?: 0L }?.first
                    apkEntries.size == 1 -> apkEntries[0]
                    else -> apkEntries.firstOrNull { it.equals("base.apk", true) }
                        ?: apkEntries.firstOrNull { com.lumidrop.x.transfer.PackageInspector.splitTypeOf(it) == null }
                }
                // ── GOAT-PASS 16 (§2.5/§3 — thumbnail extraction order and ──
                // cost): the CHEAPEST honest source is tried FIRST. The old
                // order extracted the base APK to a temp file before ever
                // looking at icon.png — a 51 MB disk copy per uncached
                // XAPK row, ×3 concurrent rows under the decode gate, which
                // is exactly the storage-bandwidth saturation behind the
                // reported intermittent ~1.5 s Files-sheet freezes (the main
                // thread's own stat/prefs reads stall behind the flood).
                // icon.png (or the manifest's declared icon — typically
                // tens of KB) is read FIRST as a bounded stream; the base
                // APK temp-copy runs only when no declared icon exists AND
                // the base is itself sane (≤ 64 MB — an APK larger than
                // that is a game's asset container, not something to copy
                // just for a 40dp thumbnail; it falls to the vector icon).
                // 1) The manifest's DECLARED icon (validated paths only) or
                //    the XAPK-spec root icon.png convention — the package's
                //    real declared icon, streamed, bounded.
                val iconEntry = xm?.iconEntryName
                    ?: names.firstOrNull { it.equals("icon.png", true) }
                if (iconEntry != null) {
                    val e = zf.getEntry(iconEntry)
                    if (e != null && e.size in 1..(16L * 1024 * 1024)) {
                        zf.getInputStream(e).use { ins ->
                            decodeBoundedCentered(ins, sz)
                        }?.let { return it }
                    }
                }
                // 2) The base APK's own launcher icon — the real thing, but
                //    only worth its temp-copy when the base is small.
                if (baseName != null) {
                    val entry = zf.getEntry(baseName)
                    if (entry != null && entry.size <= 64L * 1024 * 1024) {
                        val tmp = java.io.File.createTempFile("ldx-pkg-icon-", ".apk", context.cacheDir)
                        val fromApk = try {
                            zf.getInputStream(entry).use { ins -> tmp.outputStream().use { ins.copyTo(it) } }
                            apkIcon(context, tmp.absolutePath, sz)
                        } catch (e: Exception) { null } finally {
                            try { tmp.delete() } catch (e: Exception) { /* cache dir */ }
                        }
                        if (fromApk != null) return fromApk
                    }
                }
                null
            }
        } catch (e: Exception) {
            null   // malformed/encrypted archive → clean vector fallback
        }
    }

    /** Bounded image decode: bounds-first, inSampleSize downscale, then a
     *  centered scale-to-fit render that preserves alpha and aspect — the
     *  same treatment apkIcon's drawableToBitmap applies. */
    private fun decodeBoundedCentered(ins: java.io.InputStream, sz: Int): Bitmap? {
        return try {
            val bytes = ins.readBytes()
            if (bytes.isEmpty()) return null
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null   // not an image
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= sz && bounds.outHeight / (sample * 2) >= sz) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
            val scale = sz.toFloat() / maxOf(raw.width, raw.height)
            val w = (raw.width * scale).toInt().coerceAtLeast(1)
            val h = (raw.height * scale).toInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(raw, null,
                android.graphics.Rect((sz - w) / 2, (sz - h) / 2, (sz - w) / 2 + w, (sz - h) / 2 + h),
                Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
            out
        } catch (e: Exception) { null }
    }

    fun apkIcon(context: Context, path: String, sz: Int): Bitmap? {
        return try {
            val pm = context.packageManager
            @Suppress("DEPRECATION")
            val info = pm.getPackageArchiveInfo(path, 0) ?: return null
            val appInfo = info.applicationInfo ?: return null
            appInfo.sourceDir = path
            appInfo.publicSourceDir = path
            val drawable: Drawable = pm.getApplicationIcon(appInfo)
            drawableToBitmap(drawable, sz)
        } catch (e: Exception) {
            null
        }
    }

    private fun drawableToBitmap(drawable: Drawable, sz: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        // ── FINAL PASS 8 (PART 3 — the stretch half of the APK-icon ──────
        // artifact): setBounds(0, 0, sz, sz) forced EVERY drawable into the
        // full square, stretching any icon whose intrinsic aspect is not
        // 1:1 (adaptive-icon drawables, legacy hdpi bitmaps baked with
        // non-square padding). The recipe is now the standard scale-to-
        // fit: read the intrinsic dimensions, scale by the SMALLER axis
        // ratio, and center the result — aspect preserved, a smaller icon
        // centered on the transparent tile (whose rounded backing and
        // FIT_CENTER bind handle the rest — see TransfersFragment/
        // FilesPickerSheet). A drawable with no intrinsic size (a pure
        // ColorDrawable etc.) falls back to the full square as before.
        val iw = drawable.intrinsicWidth
        val ih = drawable.intrinsicHeight
        if (iw > 0 && ih > 0) {
            val scale = sz.toFloat() / maxOf(iw, ih)
            val w = (iw * scale).toInt().coerceAtLeast(1)
            val h = (ih * scale).toInt().coerceAtLeast(1)
            val left = (sz - w) / 2
            val top = (sz - h) / 2
            drawable.setBounds(left, top, left + w, top + h)
        } else {
            drawable.setBounds(0, 0, sz, sz)
        }
        drawable.draw(canvas)
        return bmp
    }

    /** Dark tile with a centred purple play-button — used when every
     *  frame-extraction strategy fails (unusual codec, write-protected
     *  file, etc). Returning null instead would surface as a broken-image
     *  icon in the browser and an empty tile in the native grid; this keeps
     *  both visually consistent. */
    fun makeVideoPlaceholder(sz: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888)
        val cv  = Canvas(bmp)
        val p   = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.parseColor("#0d0d1e")
        cv.drawRect(0f, 0f, sz.toFloat(), sz.toFloat(), p)
        p.color = Color.parseColor("#191930"); p.alpha = 180
        cv.drawCircle(sz / 2f, sz / 2f, sz * 0.45f, p)
        p.color = Color.parseColor("#7c5cfc"); p.alpha = 220
        val cx = sz / 2f; val cy = sz / 2f; val r = sz * 0.22f
        val path = android.graphics.Path()
        path.moveTo(cx - r * 0.55f, cy - r)
        path.lineTo(cx + r,          cy)
        path.lineTo(cx - r * 0.55f, cy + r)
        path.close()
        cv.drawPath(path, p)
        return bmp
    }

    // ── ICO decoding (Phase 5, §5.7) ──────────────────────────────────────
    //
    // Android's BitmapFactory decodes JPEG/PNG/GIF/BMP/WebP/HEIF — it has
    // NO ICO decoder, yet MediaStore happily indexes .ico files as images
    // (image/x-icon), so they show up in the Media Picker and the web
    // client's Images tab as real rows whose thumbnail can never decode:
    // a valid file rendered as a blank/gray area, exactly the reported
    // bug. This is a deliberately small, dependency-free ICO reader that
    // runs on API 21 and only touches what real-world .ico files contain:
    //
    //   header   (6 bytes)  : reserved=0, type=1(icon), count=N
    //   entries  (16 bytes) : per image — width, height (0 means 256),
    //                          bytes-in-resource, absolute file offset
    //   payload  per entry  : either an embedded PNG (modern icons — just
    //                          decodeByteArray it) or a raw DIB whose
    //                          BITMAPINFOHEADER height field is DOUBLE the
    //                          visible height (color data + AND mask),
    //                          stored bottom-up.
    //
    // 32bpp BGRA and 24bpp BGR cover virtually every icon in the wild;
    // 8bpp palette icons are supported too because low-color legacy icons
    // still exist in Windows-era collections. Anything else returns null
    // and the caller's existing vector-icon fallback shows — a clean
    // file-type representation rather than a broken-looking one, which is
    // exactly what §5.7 asks for.

    /** File-path entry point used by decodeScaled's failure path. */
    fun icoThumb(path: String, maxSz: Int): Bitmap? = try {
        java.io.File(path).inputStream().use { icoThumb(it, maxSz) }
    } catch (e: Exception) { null }

    /** Stream entry point (content:// rows via loadFromUri share the same
     *  decoder without a filesystem path needing to exist). Reads the WHOLE
     *  stream into memory first — .ico files are a few KB to a few hundred
     *  KB by nature, so this is not a memory concern the way a video file
     *  would be. */
    fun icoThumb(input: InputStream, maxSz: Int): Bitmap? {
        val raw = try { input.readBytes() } catch (e: Exception) { null } ?: return null
        if (raw.size < 6) return null
        val buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        if (buf.short.toInt() != 0) return null            // reserved
        if (buf.short.toInt() != 1) return null            // type: 1 = icon (2 = cursor)
        val count = buf.short.toInt() and 0xFFFF
        if (count <= 0 || count > 64) return null          // sane cap; real icons are 1..~20 entries

        // Pick the entry closest to (not exceeding where possible) the
        // requested size — an .ico usually carries 16/32/48/256 variants,
        // and decoding the 256px one for an 85dp cell wastes memory on
        // exactly the old devices this app must stay light on. Prefer the
        // largest entry that fits maxSz; fall back to the largest overall.
        var best: IcoEntry? = null
        var bestLargest: IcoEntry? = null
        for (i in 0 until count) {
            if (raw.size < 6 + (i + 1) * 16) return null   // truncated directory
            val width  = buf.get().toInt() and 0xFF       // 0 = 256
            val height = buf.get().toInt() and 0xFF       // 0 = 256
            val colorCount = buf.get().toInt()            // unused by this reader
            val reserved   = buf.get().toInt()            // unused by this reader
            val planes = buf.short.toInt()                // unused by this reader
            val perEntryBitCount = buf.short.toInt()      // unused — the DIB header's own bit count wins
            val size = buf.int
            val offset = buf.int
            val e = IcoEntry(if (width == 0) 256 else width, if (height == 0) 256 else height, size, offset)
            if (e.size <= 0 || e.offset < 0 || e.offset + e.size > raw.size) continue  // corrupt entry — skip, don't fail the whole file
            if (bestLargest == null || e.width * e.height > bestLargest.width * bestLargest.height) bestLargest = e
            if (e.width <= maxSz && e.height <= maxSz &&
                (best == null || e.width * e.height > best.width * best.height)) best = e
        }
        val chosen = best ?: bestLargest ?: return null
        val payload = raw.copyOfRange(chosen.offset, chosen.offset + chosen.size)

        val decoded = decodeIcoPayload(payload) ?: return null
        // Same final scaling contract as decodeScaled: exact target size,
        // bilinear, never upscaled beyond what the source offers (a 16px
        // icon shown at 34dp is blurry no matter what; scaling it up with
        // filtering is still the best available representation of a real
        // file, and infinitely better than a gray box).
        val scale = maxSz.toFloat() / maxOf(decoded.width, decoded.height)
        return if (scale >= 1f) decoded else {
            val w = (decoded.width * scale).toInt().coerceAtLeast(1)
            val h = (decoded.height * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, w, h, true)
        }
    }

    private class IcoEntry(val width: Int, val height: Int, val size: Int, val offset: Int)

    /** One entry's payload: embedded PNG, or a DIB (BITMAPINFOHEADER +
     *  pixel rows + optional palette/AND-mask). */
    private fun decodeIcoPayload(p: ByteArray): Bitmap? {
        // Embedded PNG — the modern default for 256px icons.
        if (p.size > 8 && (p[0].toInt() and 0xFF) == 0x89 && p[1].toInt() == 0x50) {
            return try { BitmapFactory.decodeByteArray(p, 0, p.size) } catch (e: Exception) { null }
        }
        if (p.size < 40) return null
        val b = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
        val dibSize = b.int
        if (dibSize < 40) return null
        val w = b.int
        val h2 = b.int                     // doubled: color rows + AND-mask rows
        if (w <= 0 || h2 <= 0 || h2 and 1 != 0) return null
        val h = h2 / 2
        val planes = b.short.toInt()               // unused by this reader
        val bpp = b.short.toInt() and 0xFFFF
        val compression = b.int                    // BI_RGB expected; a compressed icon is unsupported
        val sizeImage = b.int                      // unused
        val ppmX = b.int                           // unused
        val ppmY = b.int                           // unused
        val colorsUsed = b.int                     // unused
        val colorsImportant = b.int                // unused
        if (compression != 0) return null
        when (bpp) {
            32 -> return decodeDibRows(p, 40, w, h, 4, useMask = false)
            24 -> return decodeDibRows(p, 40, w, h, 3, useMask = true)
            8  -> return decodeDib8(p, 40, w, h)
            else -> return null            // 1/4/16bpp icons are rare enough to fall back to the vector icon
        }
    }

    /** 24bpp/32bpp rows, bottom-up. 32bpp carries real alpha; 24bpp relies
     *  on the AND mask that follows the color data, applied here. */
    private fun decodeDibRows(p: ByteArray, colorOffset: Int, w: Int, h: Int, bytesPerPixel: Int, useMask: Boolean): Bitmap? {
        val rowLen = w * bytesPerPixel
        val padded = ((rowLen + 3) / 4) * 4          // rows are 4-byte aligned
        val colorBytes = padded * h
        if (colorOffset + colorBytes > p.size) return null
        val maskRowBytes = ((w + 31) / 32) * 4
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val srcRow = colorOffset + (h - 1 - y) * padded     // bottom-up
            val maskRow = colorOffset + colorBytes + (h - 1 - y) * maskRowBytes
            for (x in 0 until w) {
                val i = srcRow + x * bytesPerPixel
                val alpha: Int
                if (useMask) {
                    // AND mask: bit SET means transparent. Guarded row by
                    // row — some icons omit the mask entirely.
                    alpha = if (maskRow + (x shr 3) < p.size && (p[maskRow + (x shr 3)].toInt() and (0x80 shr (x and 7))) != 0) 0 else 0xFF
                } else alpha = p[i + 3].toInt() and 0xFF
                out[y * w + x] = (alpha shl 24) or
                    ((p[i + 2].toInt() and 0xFF) shl 16) or      // BGR(A) -> ARGB
                    ((p[i + 1].toInt() and 0xFF) shl 8) or
                    (p[i].toInt() and 0xFF)
            }
        }
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }

    /** 8bpp palette icon: 256-entry BGRA palette (1024 bytes) after the
     *  header, then one byte per pixel, bottom-up, 4-byte-aligned rows. */
    private fun decodeDib8(p: ByteArray, colorOffset: Int, w: Int, h: Int): Bitmap? {
        val paletteOffset = colorOffset
        val paletteBytes = 4 * 256
        if (paletteOffset + paletteBytes > p.size) return null
        val palette = IntArray(256) { i ->
            val j = paletteOffset + i * 4
            ((p[j + 3].toInt() and 0xFF) shl 24) or
            ((p[j + 2].toInt() and 0xFF) shl 16) or
            ((p[j + 1].toInt() and 0xFF) shl 8) or
            (p[j].toInt() and 0xFF)
        }
        val dataOffset = paletteOffset + paletteBytes
        val rowLen = ((w + 3) / 4) * 4
        if (dataOffset + rowLen * h > p.size) return null
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val out = IntArray(w * h)
        for (y in 0 until h) {
            val srcRow = dataOffset + (h - 1 - y) * rowLen
            for (x in 0 until w) out[y * w + x] = palette[p[srcRow + x].toInt() and 0xFF]
        }
        bmp.setPixels(out, 0, w, 0, 0, w, h)
        return bmp
    }
}
