package com.lumidrop.x.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Device photo/video library access for the Media Selector (Part 4.3).
 *
 * By the time the Send screen's "Media" tile is even reachable, the server
 * is already running -- which, per SendFragment.checkPermissionsAndStart(),
 * means either MANAGE_EXTERNAL_STORAGE (API 30+) or READ_EXTERNAL_STORAGE
 * (below it) has already been granted, since starting the server requires
 * one of them. Both are a superset of what querying MediaStore needs, so
 * this deliberately does not do its own separate permission request --
 * doing so would just be asking a second time for access already granted,
 * or worse, implying the person might reasonably say no to browsing media
 * when they already said yes to the app reading it. Queries are still
 * defensive (try/catch, never throw) in case a provider misbehaves on some
 * OEM skin, or the person somehow gets here with access mid-revocation.
 */
data class MediaLibraryItem(
    val id: Long,
    val uri: Uri,
    val name: String,
    val path: String?,
    val size: Long,
    val mime: String,
    val dateAddedSec: Long,
    val isVideo: Boolean,
    val durationMs: Long,
    val bucketId: Long,
    val bucketName: String,
)

data class MediaAlbum(
    val bucketId: Long,
    val name: String,
    val cover: MediaLibraryItem,
    val itemCount: Int,
    // GOAT v2 (item 1 — album cards): aggregate size of every item in this
    // bucket, for the card's own "N items · total size" line. Summed once
    // here, at grouping time, rather than re-summed by every card on every
    // bind.
    val totalBytes: Long,
    // ── Pass-32 (#1): the album's EXACT member media — the same items the ────
    // picker's album view displays, in the same newest-first order. An album
    // selection must queue THESE files (each with its own real name, size,
    // MIME, URI and path), never the bucket's physical parent directory: a
    // bucket is a MediaStore folder view, and the folder itself can hold
    // APKs, documents and subfolders the album never showed (the reported
    // "selecting the Download album queued the whole Download folder").
    // Carried on the model (grouped from the same flat list the album grid
    // was built from) so the queue receives the exact selection the album
    // represented, with zero re-querying and zero folder-send plumbing.
    val items: List<MediaLibraryItem>,
    // GOAT v2 (item 2 — album send): the real absolute directory every item
    // in this bucket lives in, so a long-pressed, selected album can be
    // handed to Phase 2's folder-send engine as a plain path -- no SAF
    // grant exists for an arbitrary MediaStore bucket the person never
    // explicitly picked via a tree chooser, so path is the only address
    // this app actually has for it. Derived from the COVER item's own
    // parent directory rather than re-derived per member: MediaStore's own
    // bucket_id contract is a hash of the containing folder's path, so
    // every item sharing one bucketId is guaranteed to share one immediate
    // parent directory -- there is no "which member's parent" ambiguity to
    // resolve here. Null only if the cover item itself somehow has no
    // usable path (see MediaLibraryItem.path), in which case this album can
    // still be browsed/opened as before, just not sent as a unit.
    val folderPath: String?,
)

enum class MediaTypeFilter { ALL, IMAGES, VIDEOS }

/** ── Pass-33 (#7): one audio track from MediaStore's Audio table. Kept a ────
 *  deliberate SEPARATE type from MediaLibraryItem: that model feeds the
 *  media grid/previewer, whose every consumer branches on isVideo and
 *  image/video assumptions — audio would have to fight all of them. This
 *  carries what a music picker actually shows: title/artist, duration, and
 *  the parent directory (audio "folders" are grouped by real parent path,
 *  the way a person's music is actually laid out on disk). */
data class AudioItem(
    val id: Long,
    val uri: android.net.Uri,
    val name: String,          // display/file name
    val title: String?,        // audio metadata title, when the provider has one
    val artist: String?,       // audio metadata artist, when the provider has one
    val path: String?,
    val parentPath: String?,   // the containing directory's absolute path
    val size: Long,
    val mime: String,
    val durationMs: Long,
    val dateAddedSec: Long,
    // ── Pass-34 (#7): the MediaStore album the track belongs to — the key the
    // Music picker's artwork cache decodes embedded cover art by, so every
    // track of one album shares ONE decoded bitmap (and a track whose album
    // has no art anywhere decodes once and falls back forever, never retried
    // per scroll).
    val albumId: Long = 0L,
)

object MediaLibrary {

    /** Queries images and/or videos, newest first. Two separate table
     *  queries (Images.Media / Video.Media) rather than one combined query
     *  against MediaStore.Files -- column availability (DURATION in
     *  particular) is unambiguous per-table, and either half can be
     *  skipped entirely when the type filter doesn't need it instead of
     *  fetching and then discarding rows. */
    suspend fun query(context: Context, filter: MediaTypeFilter): List<MediaLibraryItem> = withContext(Dispatchers.IO) {
        val out = ArrayList<MediaLibraryItem>()
        if (filter != MediaTypeFilter.VIDEOS) out.addAll(queryImages(context))
        if (filter != MediaTypeFilter.IMAGES) out.addAll(queryVideos(context))
        out.sortByDescending { it.dateAddedSec }
        out
    }

    /** ── Pass-33 (#7) + GOAT-PASS 7 Part 2 (§4A): the device's audio library, ──
     *  newest first, for the Music picker. One table query against
     *  Audio.Media — TITLE/ARTIST/DURATION/ALBUM_ID are audio-table columns
     *  that don't exist on the Files table, same per-table reasoning as the
     *  image/video queries above. Folder grouping is derived from the track's
     *  real parent directory (a person's music is laid out in folders, not
     *  MediaStore buckets).
     *  ── GOAT-PASS 7 (§4A — every album appears WITHOUT playing anything): ──
     *  the selection previously demanded `DURATION > 0`. That is a metadata
     *  LUCK filter, not a content filter: the media scanner extracts DURATION
     *  asynchronously (and some codecs/builds leave it 0 or NULL for real,
     *  playable files), so any album whose tracks had not been fully indexed
     *  yet was INVISIBLE in the Music sheet — its folder row never
     *  materialized until the scanner happened to finish and the sheet was
     *  reopened after the 30s library cache expired, which read exactly like
     *  "albums appear only after I play one of their songs". Album discovery
     *  is now completely independent of playback state: every indexed audio
     *  row is loaded; the only honesty guard left is SIZE > 0 (a zero-byte
     *  file is never playable), and a track whose duration is still
     *  unextracted simply renders its honest "0:00" until the scanner fills
     *  it in. */
    suspend fun queryAudio(context: Context): List<AudioItem> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            android.provider.MediaStore.Audio.Media._ID,
            android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
            android.provider.MediaStore.Audio.Media.TITLE,
            android.provider.MediaStore.Audio.Media.ARTIST,
            android.provider.MediaStore.Audio.Media.SIZE,
            android.provider.MediaStore.Audio.Media.MIME_TYPE,
            android.provider.MediaStore.Audio.Media.DURATION,
            android.provider.MediaStore.Audio.Media.DATE_ADDED,
            android.provider.MediaStore.Audio.Media.DATA,
            // Pass-34 (#7): the album key the Music picker's artwork cache
            // decodes embedded cover art by.
            android.provider.MediaStore.Audio.Media.ALBUM_ID,
        )
        val out = ArrayList<AudioItem>()
        try {
            context.contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                "${android.provider.MediaStore.Audio.Media.SIZE} > 0", null,
                "${android.provider.MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ARTIST)
                val sizeCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.MIME_TYPE)
                val durCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DURATION)
                val dateCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATE_ADDED)
                val dataCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA)
                val albumCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ALBUM_ID)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val path = c.getString(dataCol)
                    out.add(AudioItem(
                        id = id,
                        uri = ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                        name = c.getString(nameCol) ?: c.getString(titleCol) ?: "audio_$id",
                        title = c.getString(titleCol),
                        artist = c.getString(artistCol)?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) },
                        path = path,
                        parentPath = path?.let { java.io.File(it).parent?.takeIf { p -> p.isNotBlank() } },
                        size = c.getLong(sizeCol),
                        mime = c.getString(mimeCol) ?: "audio/*",
                        durationMs = c.getLong(durCol),
                        dateAddedSec = c.getLong(dateCol),
                        albumId = c.getLong(albumCol),
                    ))
                }
            }
        } catch (e: Exception) {
        }
        out.sortByDescending { it.dateAddedSec }
        out
    }

    /** ── Pass-42 (§4): the ONE audio row MediaStore knows for an absolute ────
     *  file path, for opening a file straight from the Files sheet through
     *  the shared music player. Path is the canonical identity (the DATA
     *  column) — the same identity the Music picker's focus lookup uses — so
     *  a library-known file resolves to its REAL title, artist, album id and
     *  duration (and the album-keyed artwork cache), while a file MediaStore
     *  has never indexed (nested folders, received files) simply answers
     *  null and the caller falls back to a file-shaped Track that
     *  enrichTrackMetadata fills from the file's own tags. */
    suspend fun queryAudioForPath(context: Context, path: String): AudioItem? = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            android.provider.MediaStore.Audio.Media._ID,
            android.provider.MediaStore.Audio.Media.DISPLAY_NAME,
            android.provider.MediaStore.Audio.Media.TITLE,
            android.provider.MediaStore.Audio.Media.ARTIST,
            android.provider.MediaStore.Audio.Media.SIZE,
            android.provider.MediaStore.Audio.Media.MIME_TYPE,
            android.provider.MediaStore.Audio.Media.DURATION,
            android.provider.MediaStore.Audio.Media.DATE_ADDED,
            android.provider.MediaStore.Audio.Media.DATA,
            android.provider.MediaStore.Audio.Media.ALBUM_ID,
        )
        try {
            context.contentResolver.query(
                android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                "${android.provider.MediaStore.Audio.Media.DATA}=?", arrayOf(path), null
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val id = c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media._ID))
                val dataPath = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATA))
                AudioItem(
                    id = id,
                    uri = ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id),
                    name = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DISPLAY_NAME))
                        ?: c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE))
                        ?: "audio_$id",
                    title = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.TITLE)),
                    artist = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ARTIST))
                        ?.takeIf { it.isNotBlank() && !it.equals("<unknown>", ignoreCase = true) },
                    path = dataPath,
                    parentPath = dataPath?.let { java.io.File(it).parent?.takeIf { p -> p.isNotBlank() } },
                    size = c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.SIZE)),
                    mime = c.getString(c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.MIME_TYPE)) ?: "audio/*",
                    durationMs = c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DURATION)),
                    dateAddedSec = c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.DATE_ADDED)),
                    albumId = c.getLong(c.getColumnIndexOrThrow(android.provider.MediaStore.Audio.Media.ALBUM_ID)),
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun queryImages(context: Context): List<MediaLibraryItem> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val out = ArrayList<MediaLibraryItem>()
        try {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATA)
                val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
                val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    out.add(MediaLibraryItem(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id),
                        name = c.getString(nameCol) ?: "image_$id",
                        path = c.getString(dataCol),
                        size = c.getLong(sizeCol),
                        mime = c.getString(mimeCol) ?: "image/*",
                        dateAddedSec = c.getLong(dateCol),
                        isVideo = false,
                        durationMs = 0L,
                        bucketId = c.getLong(bucketIdCol),
                        bucketName = c.getString(bucketNameCol) ?: "Photos",
                    ))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    private fun queryVideos(context: Context): List<MediaLibraryItem> {
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
        )
        val out = ArrayList<MediaLibraryItem>()
        try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null,
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val bucketIdCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
                val bucketNameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    out.add(MediaLibraryItem(
                        id = id,
                        uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        name = c.getString(nameCol) ?: "video_$id",
                        path = c.getString(dataCol),
                        size = c.getLong(sizeCol),
                        mime = c.getString(mimeCol) ?: "video/*",
                        dateAddedSec = c.getLong(dateCol),
                        isVideo = true,
                        durationMs = c.getLong(durCol),
                        bucketId = c.getLong(bucketIdCol),
                        bucketName = c.getString(bucketNameCol) ?: "Videos",
                    ))
                }
            }
        } catch (e: Exception) {
        }
        return out
    }

    /** Groups an already-sorted (newest-first) flat list into one album per
     *  bucket, each fronted by its most recent item as the cover. Client-
     *  side grouping rather than a second query with GROUP BY -- the flat
     *  list is already being fetched for the "All Media" grid, and this app
     *  never has so much on-device media that a single linear pass over it
     *  is a meaningful cost. */
    fun groupIntoAlbums(items: List<MediaLibraryItem>): List<MediaAlbum> {
        val byBucket = LinkedHashMap<Long, MutableList<MediaLibraryItem>>()
        for (item in items) byBucket.getOrPut(item.bucketId) { mutableListOf() }.add(item)
        return byBucket.map { (bucketId, bucketItems) ->
            val cover = bucketItems.first() // list is already newest-first
            MediaAlbum(
                bucketId = bucketId,
                name = cover.bucketName,
                cover = cover,
                itemCount = bucketItems.size,
                // Pass-32 (#1): the exact members — see the field's own doc.
                items = java.util.Collections.unmodifiableList(bucketItems.toList()),
                totalBytes = bucketItems.sumOf { it.size },
                // See MediaAlbum.folderPath's own doc comment for why the
                // cover's parent alone is safe to use for the whole bucket.
                folderPath = cover.path?.let { File(it).parentFile?.absolutePath },
            )
        }.sortedByDescending { it.itemCount }
    }
}
