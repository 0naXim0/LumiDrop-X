package com.lumidrop.x.ui

import android.app.Activity
import android.app.Dialog
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.util.LruCache
import android.util.Size
import java.io.File
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.lumidrop.x.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** ── Pass-34 Part 2 (#4/#7): the album-artwork cache, shared by the Music ────
 *  picker's rows AND the floating playback overlay. Keyed by MediaStore
 *  ALBUM_ID (or, for queue audio with no album id, the playable uri), so
 *  every track of one album shares ONE decoded bitmap for the app's
 *  lifetime. Sources with no decodable art are remembered as MISSING — a
 *  no-art track falls back to the glyph forever after one attempt instead
 *  of re-decoding on every scroll, and the in-flight set keeps two views of
 *  the same source from racing two decodes. */
object MusicArtCache {
    private val MISSING = Any()
    private val lru = object : LruCache<String, Any>(192) {}
    private val inFlight = java.util.Collections.synchronizedSet(HashSet<String>())

    fun cachedBitmap(key: String): Bitmap? = (lru.get(key) as? Bitmap)
    fun isKnownMissing(key: String): Boolean = lru.get(key) === MISSING
    fun rememberBitmap(key: String, bmp: Bitmap) { lru.put(key, bmp) }
    /** ── Pass-42 (§7): a "no art here" mark may NEVER overwrite a real ──
     *  bitmap under the same key. Two independent decode paths race for one
     *  queue-started track: bindAlbumArt's decodeAlbumArt (which on API 29+
     *  tries loadThumbnail FIRST and used to return null for a file:// URI
     *  without ever reaching its retriever route) and enrichTrackMetadata's
     *  own retriever pass (which DOES find the embedded art). Whichever
     *  finished second used to clobber the other's answer — a null decode
     *  overwrote a just-landed valid bitmap with the MISSING sentinel, and
     *  the next ticker refresh (1 s cadence) rendered the fallback glyph:
     *  artwork that "appears at first, then disappears". A real bitmap is
     *  always the strongest answer for a key; missing-marks only ever fill
     *  an empty slot. The check-then-put runs under the LruCache's own
     *  monitor (its get/put synchronize on the same object), so the guard
     *  itself is race-free. */
    fun rememberMissing(key: String) {
        synchronized(lru) {
            if (lru.get(key) == null) lru.put(key, MISSING)
        }
    }
    fun beginDecode(key: String): Boolean = inFlight.add(key)
    fun endDecode(key: String) { inFlight.remove(key) }

    // ── GOAT-PASS 9 (§12 — "artwork appears only after a click"): rows that ──
    //  bind while their key's decode is ALREADY in flight used to get the
    //  fallback glyph and NO retry — beginDecode answered false, nothing
    //  ever re-notified them, and the artwork only materialized on the next
    //  rebind (a scroll, a selection tap, a click). For an album's song
    //  rows this was near-deterministic: the first visible row started the
    //  decode, every sibling bound inside the decode window kept the glyph.
    //  These rows now register as PENDING targets; the decode's completion
    //  dispatches the result to every registered target that still carries
    //  the same key — no rebind, no interaction, first render shows art.
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val pendingByKeys = HashMap<String, MutableList<java.lang.ref.WeakReference<android.widget.ImageView>>>()

    /** Registers [target] to receive [key]'s art the moment the already-
     *  in-flight decode for it lands (weak — a recycled row is collected,
     *  never leaked, and its dead reference is dropped at dispatch time). */
    fun awaitDecode(key: String, target: android.widget.ImageView) {
        synchronized(pendingByKeys) {
            pendingByKeys.getOrPut(key) { mutableListOf() }.add(java.lang.ref.WeakReference(target))
        }
    }

    /** Called by the completing decode coroutine: hands the result to every
     *  still-live pending target for [key] on the main thread. [onReady] must
     *  re-check the target's own tag (a recycled row may have been rebound
     *  to a different key in the meantime). */
    fun dispatchDecodeResult(key: String, onReady: (android.widget.ImageView) -> Unit) {
        val targets: List<java.lang.ref.WeakReference<android.widget.ImageView>>
        synchronized(pendingByKeys) {
            targets = pendingByKeys.remove(key) ?: return
        }
        if (targets.isEmpty()) return
        mainHandler.post {
            for (ref in targets) {
                val iv = ref.get() ?: continue
                try { onReady(iv) } catch (_: Exception) { /* a dying row never breaks the dispatch */ }
            }
        }
    }
}

/** One decodable piece of album art. Routes, strongest first, each falling
 *  through on ANY failure (null OR throw): loadThumbnail on the audio uri
 *  itself (the provider serves the embedded cover) on API 29+, the classic
 *  albumart provider uri by album id, and MediaMetadataRetriever's embedded
 *  picture as the final route for ANY uri — file:// paths included.
 *  Bounds-sampled to roughly the target size. Null only when every route
 *  genuinely fails.
 *  ── Pass-42 (§7): loadThumbnail is NOT a dead end anymore. It works only
 *  for content-provider uris; a QUEUE-started track's uri is a plain
 *  file:// path, where loadThumbnail throws (or answers null) even though
 *  the file carries embedded art — the old code returned null right there
 *  (see MusicArtCache.rememberMissing for the clobbering that caused), and
 *  queue playback could lose its artwork where the same file played from
 *  the Music sheet (a content:// MediaStore uri) showed it fine. The
 *  retriever route now catches every uri shape loadThumbnail cannot. */
suspend fun decodeAlbumArt(ctx: Context, uri: Uri, albumId: Long, targetPx: Int): Bitmap? =
    withContext(Dispatchers.IO) {
        // Route 1: the provider's own thumbnail (API 29+). Content uris only.
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            try {
                ctx.contentResolver.loadThumbnail(uri, Size(targetPx, targetPx), null)?.let { return@withContext it }
            } catch (e: Exception) { /* not a thumbnail-capable uri — fall through */ }
            catch (e: OutOfMemoryError) { /* fall through to a sampled route */ }
        }
        // Route 2: the classic albumart provider by MediaStore album id.
        if (albumId > 0) {
            try {
                val artUri = Uri.parse("content://media/external/audio/albumart/$albumId")
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(artUri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth > 0) {
                    val sample = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / targetPx)
                    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                    ctx.contentResolver.openInputStream(artUri)?.use {
                        BitmapFactory.decodeStream(it, null, opts)?.let { return@withContext it }
                    }
                }
            } catch (e: Exception) { /* fall through to the retriever */ }
            catch (e: OutOfMemoryError) { /* fall through to the retriever */ }
        }
        // Route 3 — the final one, valid for ANY uri shape: the embedded
        // picture through MediaMetadataRetriever (file:// paths, SAF uris,
        // MediaStore content uris that somehow lacked a thumbnail).
        try {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(ctx, uri)
                val data = retriever.embeddedPicture
                if (data == null || data.isEmpty()) null
                else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                    if (bounds.outWidth <= 0) null
                    else {
                        val sample = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / targetPx)
                        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                        BitmapFactory.decodeByteArray(data, 0, data.size, opts)
                    }
                }
            } finally {
                try { retriever.release() } catch (e: Exception) { /* already gone */ }
            }
        } catch (e: Exception) { null } catch (e: OutOfMemoryError) { null }
    }

/** Load [cacheKey]'s art into [target]: cache hit synchronously, else decode
 *  once (deduped) on [scope] and land it only if the view still shows the
 *  same key (the tag guard). The glyph fallback is set first so a never-
 *  landing decode never shows a blank slot. */
fun bindAlbumArt(
    ctx: Context,
    scope: CoroutineScope,
    target: ImageView,
    cacheKey: String,
    uri: Uri,
    albumId: Long,
    targetPx: Int,
    fallbackTint: Int,
) {
    val cached = MusicArtCache.cachedBitmap(cacheKey)
    when {
        cached != null -> target.setImageDrawable(
            androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(ctx.resources, cached).apply { cornerRadius = ctx.dpf(8f) })
        MusicArtCache.isKnownMissing(cacheKey) ->
            target.setImageDrawable(Widgets.icon(ctx, R.drawable.ic_audio, fallbackTint))
        else -> {
            target.setImageDrawable(Widgets.icon(ctx, R.drawable.ic_audio, fallbackTint))
            if (MusicArtCache.beginDecode(cacheKey)) {
                scope.launch {
                    // finally frees the in-flight slot even if the caller's
                    // scope died mid-decode — otherwise that key never retries.
                    try {
                        val bmp = decodeAlbumArt(ctx, uri, albumId, targetPx)
                        if (bmp != null) MusicArtCache.rememberBitmap(cacheKey, bmp)
                        else MusicArtCache.rememberMissing(cacheKey)
                        // ── GOAT-PASS 15 (§7 — thumbnails on the FIRST visible ──
                        // entry): the old `target.isAttachedToWindow` guard here
                        // (and in the dispatch below) is the root cause of the
                        // reported "some songs show no thumbnail when they
                        // first scroll into view, and it appears after scrolling
                        // away and back": RecyclerView's GapWorker PREFETCH
                        // binds rows one-to-four items ahead of the viewport
                        // while they are still DETACHED, and a decode that
                        // completes inside exactly that window dropped its
                        // result on the floor — the bound view drew the glyph
                        // when it entered the screen, and only the next rebind
                        // (the scroll-away-and-back) hit the now-warm cache.
                        // Setting the drawable on a bound-but-detached view is
                        // perfectly safe (it simply draws with the art the
                        // moment it attaches), so the TAG check alone — "is
                        // this still the view for THIS key?" — is the correct
                        // and sufficient guard.
                        if (bmp != null && target.tag == cacheKey) {
                            target.setImageDrawable(
                                androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(ctx.resources, bmp).apply { cornerRadius = ctx.dpf(8f) })
                        }
                        // GOAT-PASS 9 (§12): rows that bound while THIS decode
                        // was in flight receive the result directly — artwork
                        // on the first render, no click/scroll rebind needed.
                        if (bmp != null) {
                            MusicArtCache.dispatchDecodeResult(cacheKey) { iv ->
                                if (iv.tag == cacheKey) {
                                    iv.setImageDrawable(
                                        androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(ctx.resources, bmp).apply { cornerRadius = ctx.dpf(8f) })
                                }
                            }
                        }
                    } finally {
                        MusicArtCache.endDecode(cacheKey)
                    }
                }
            } else {
                // GOAT-PASS 9 (§12): another row's decode for this exact key is
                // already running — wait for it instead of showing the glyph
                // until the next rebind.
                MusicArtCache.awaitDecode(cacheKey, target)
            }
        }
    }
}

/**
 * ══ Pass-34 Part 2 (#4): the ONE in-app audio playback system ══
 *
 * A single MediaPlayer behind a single object, so audio can never play from
 * two competing players at once. Both playback surfaces route through it:
 *
 *   • the Music picker — a row's play control plays the picker's CURRENT
 *     visible track list (its live sort/filter/search order) from that row;
 *   • the Queued Files section — an audio row (or an audio child inside an
 *     expanded album/music folder) plays the queue's audio files in queue
 *     order.
 *
 * ══ Pass-36 Part 2 (#5–#8): the ADAPTIVE two-state player ═══════════════════
 *
 * While audio is active, the player lives in TWO states, both owned by this
 * one object (one MediaPlayer, one overlay instance, one state source — the
 * pill and the expanded card are two representations of the same truth, so
 * they can never disagree):
 *
 *   • EXPANDED — a floating card in its own non-focusable window (touches
 *     outside pass through), FIXED width (min(320dp, screen − 24dp), Pass-36
 *     Part 2 #5 — never resized by any track name), anchored just below the
 *     Send tab's Server Card when that slot is laid out (top-center
 *     otherwise). Full controls: artwork, title/artist, a touch-seekable
 *     progress strip with times, prev / play-pause / next (hold-to-seek),
 *     an explicit Stop, and a ✕ that fully stops and dismisses everything
 *     (Pass-40 #9).
 *
 *   • COMPACT — after [PlaybackOverlay.COLLAPSE_AFTER_MS] without a
 *     meaningful interaction (every touch on the card or its controls
 *     resets the clock), the card animates out and a fixed-size 152×26dp
 *     music pill fades into the Server Card's title row, in the empty space
 *     right of "LumiDrop X" (#7) — never a second floating widget while
 *     that dock exists. The pill carries an animated equalizer (still while
 *     paused) and the track title/artist as space permits; tapping it
 *     smoothly restores the expanded card with the same track, position and
 *     playback state (nothing about the player is touched by the
 *     transition).
 *
 * Listeners (the picker sheets) get a callback on every state change so row
 * indicators stay truthful; the controller owns BOTH representations'
 * refresh, so surfaces that never subscribed still see accurate global
 * state.
 */
object MusicPlayback {

    /** One playable track, from either surface. title/artist/durationMs are
     *  VAR deliberately: queue-sourced tracks are built synchronously from
     *  the staged item (name + uri + size only — see QueueAdapter.trackOf),
     *  and the controller enriches them with the real metadata (retriever
     *  title/artist/duration, the player's own duration) as soon as it is
     *  known — Pass-39 (#3): the queue and the Music sheet must show the
     *  same quality of information, and the persisted session stores the
     *  enriched values too. */
    class Track(
        val key: String,          // stable identity for row state comparison
        val uri: Uri,
        var title: String,
        var artist: String?,
        val albumId: Long,        // 0 = unknown (queue audio)
        var durationMs: Long,
        val size: Long,
    ) {
        val artKey: String get() = if (albumId > 0) "art:$albumId" else "arturi:$uri"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var player: MediaPlayer? = null
    private var tracks: List<Track> = emptyList()
    private var index = -1
    private var preparing = false

    /** True while a track is loaded AND actively playing (not paused). */
    var isPlaying: Boolean = false
        private set
    /** The loaded track's key — null when nothing is loaded. The picker
     *  sheets compare their rows against this for the playing indicator. */
    var currentKey: String? = null
        private set

    private val listeners = ArrayList<(String?) -> Unit>()

    fun addListener(l: (String?) -> Unit) { listeners.add(l) }
    fun removeListener(l: (String?) -> Unit) { listeners.remove(l) }

    private fun notifyState() {
        val key = currentKey
        listeners.toList().forEach { try { it(key) } catch (e: Exception) { /* a broken surface must not break playback */ } }
        overlay?.refresh()
        // ── Pass-38 (#6): every state change re-persists the session — the ──
        // pill survives app closure, activity recreation and process death,
        // and restores paused at exactly the last remembered position.
        persistState()
        // ── Pass-38 (#6): while audio is live, a light 5s ticker keeps the ──
        // persisted POSITION fresh for a mid-song kill; it stands down the
        // moment nothing is playing (a paused session's position is frozen
        // and was persisted by the state change itself).
        ensurePersistTicker()
        // ── Pass-38 (#8): the playback notification mirrors the engine — ──
        // same track, same state, gone when the session is. A RESTORED
        // session (no live player yet) is deliberately not notified: the
        // notification is for playback, and nothing has played.
        val nCtx = appContext ?: hostActivity?.applicationContext
        MusicNotifier.sync(nCtx, if (player != null) currentTrack() else null, isPlaying, positionMs())
    }

    private var overlay: PlaybackOverlay? = null
    private var hostActivity: Activity? = null

    // ── Pass-38 (#6): the persistent pill's store ─────────────────────────────
    // The application context outlives any single activity (rotation) and is
    // captured at the first play()/maybeRestore(); restoreSeekMs is the
    // remembered position of a RESTORED session (no live player yet) — the
    // first play seeks there before starting, and every seek while still
    // unprepared lands on it instead of vanishing.
    private var appContext: Context? = null
    private var restoreSeekMs = -1L
    /** How many tracks around the current one are persisted — prev/next stay
     *  useful after a restore without persisting a thousand-row queue. */
    private val persistWindow = 100
    private var persistTicker: Job? = null

    // ── Pass-40 (#3) + Pass-41 (§6): MUSIC-SHEET NAVIGATION hooks ─────────────
    // The floating player is a launchpad INTO the Music sheet: a tap on the
    // card's main container (not its controls, not the seek surfaces) asks
    // the sheet to open — or, when it is already open, to scroll in place —
    // to the EXACT currently-playing track.
    //
    //   • [musicSheetFocus] — the LIVE Music sheet's locate-and-scroll
    //     handler, installed while the sheet is up and removed on its
    //     dismissal. It receives the TRACK OBJECT (not just its key) and
    //     resolves it against the loaded library through the full CANONICAL
    //     IDENTITY set — the session key, the key without its "q:" queue
    //     prefix, the source uri, and the file path behind a file:// uri —
    //     because the SAME SONG is addressed differently depending on where
    //     it was started from: a track played from the Music sheet carries a
    //     MediaStore path/uri, while the same file played from the Queue
    //     carries a "q:<path-or-uri>" session key (Pass-41 §6: the old
    //     key-only match could never bridge the two, which is why a
    //     queue-started song reported "This track isn't in your music
    //     library" even though the exact song sat in the sheet). Matching by
    //     full path/uri — never by display name — also makes duplicate
    //     filenames in different folders resolve to the right row. Returns
    //     true when the track was found and focused.
    //   • [musicSheetOpener] — the Send tab's tile handler: opens the Music
    //     sheet with a pending focus track (applied once the library's first
    //     frame lands). Used when no sheet is live.
    //
    // A track the library can no longer resolve (deleted, or a SAF-sourced
    // queue file that was never in MediaStore) is handled gracefully by the
    // sheet itself — it opens without scrolling and says so in one quiet
    // toast; nothing crashes and nothing scrolls to a wrong row.
    var musicSheetFocus: ((Track?) -> Boolean)? = null
    var musicSheetOpener: ((Track?) -> Unit)? = null

    /** The card's main-container tap: focus the current track in the Music
     *  sheet — the live sheet scrolls in place; otherwise the sheet opens
     *  focused on it. A no-op when nothing is loaded. */
    fun requestMusicSheetFocus() {
        val t = currentTrack() ?: return
        if (musicSheetFocus?.invoke(t) == true) return
        musicSheetOpener?.invoke(t)
    }

    /** ── GOAT-PASS 7 Part 2 (§7A): the modal-sheet tap hook. While a picker ──
     *  sheet is up, its full-screen backdrop window covers the app —
     *  including the compact pill's dock in the strip the sheet leaves
     *  exposed. The pill (a view inside the app's window) can never receive
     *  that tap itself, so the sheet's backdrop forwards taps that land
     *  inside the pill's live screen bounds: a hit expands the player (its
     *  window re-stacks ABOVE the sheet) and the sheet stays open — the
     *  reported "sometimes the pill only opens on the second click" was
     *  exactly this: the first tap silently dismissed the sheet instead of
     *  opening the player. Returns true when the tap was consumed. */
    fun interceptModalBackdropTap(rawX: Int, rawY: Int): Boolean {
        val ov = overlay ?: return false
        val b = ov.pillScreenBounds() ?: return false
        // A small slop (~6dp) forgives a finger that lands on the pill's
        // very edge — the 26dp pill is a slim target by design.
        val slop = try {
            (ov.activity.resources.displayMetrics.density * 6f).toInt()
        } catch (e: Exception) { 12 }
        val inside = rawX >= b[0] - slop && rawX <= b[2] + slop &&
            rawY >= b[1] - slop && rawY <= b[3] + slop
        if (!inside) return false
        ov.expand()
        return true
    }

    // ── Pass-40 (#3): MODAL-SHEET COEXISTENCE ──────────────────────────────────
    // True while the Music sheet is up. The floating player must remain
    // visible and accessible over it: an expanded card folds to its pill
    // when the sheet opens (the pill's dock sits in the exposed app strip
    // above the sheet — never hidden, one tap from the full controls), and
    // a pill re-expanded while the sheet is up re-stacks its window ABOVE
    // the sheet's so the card is genuinely usable, not merely drawn.
    @Volatile private var modalSheetOpen = false

    /** The Music sheet's visibility edge. On open: remember it, and keep the
     *  player REACHABLE over the sheet — an expanded card folds to its pill
     *  when the pill's dock sits in the exposed top strip (the sheet gets the
     *  full screen; the pill keeps the player one tap away), and otherwise
     *  stays expanded, re-stacked ABOVE the sheet and anchored in the top
     *  strip (the dock being scrolled under the sheet must not bury the
     *  player). On close: forget it and restart the collapse clock so an
     *  expanded card resumes its normal inactivity rhythm. */
    fun onModalSheetVisibilityChanged(open: Boolean) {
        if (modalSheetOpen == open) return
        modalSheetOpen = open
        val ov = overlay ?: return
        if (open) {
            if (ov.isExpanded) {
                if (compactHost != null && dockTopExposed()) ov.collapse()
                else ov.bringAboveSheets()
            }
        } else if (ov.isExpanded) {
            ov.noteInteractionPublic()
        }
    }

    /** Whether the compact dock's TOP edge sits in the strip the sheet's
     *  default 75% coverage leaves exposed (generously ~30% — the sheet can
     *  also be dragged taller, and the pill only needs its top visible to
     *  read and tap). False when the dock is scrolled down the Send screen
     *  or otherwise off-screen. */
    private fun dockTopExposed(): Boolean {
        return try {
            val host = compactHost ?: return false
            val loc = IntArray(2)
            host.getLocationOnScreen(loc)
            val screenH = android.content.res.Resources.getSystem().displayMetrics.heightPixels
            loc[1] >= 0 && loc[1] < screenH * 0.30f
        } catch (e: Exception) { false }
    }

    /** ── Pass-36 Part 2 (#7) + Pass-38 (#7): the compact pill's docks — ─────
     *  ONE live host at a time, one per main tab (the Send tab's Server Card
     *  title row, the Transfers tab's header row and the Settings tab's
     *  header row). Each fragment registers its slot as it becomes the
     *  visible tab (onHiddenChanged(false)) and on view creation; the most
     *  recently registered slot IS the dock — switching tabs moves the pill
     *  (the one shared player re-parents its one compact representation,
     *  never a second widget, never a second controller). Null never means
     *  "draw a floating pill somewhere else" — while no dock exists the
     *  EXPANDED card simply stays up (the collapse is deferred until a dock
     *  can receive the pill). */
    private var compactHost: ViewGroup? = null

    /** Registers [slot] as the compact pill's dock. Safe to call repeatedly;
     *  a pill that should be showing mounts immediately (e.g. the view was
     *  recreated while music was already playing collapsed). */
    fun attachCompactHost(slot: ViewGroup?) {
        if (compactHost === slot && slot != null) return   // the dock did not change
        compactHost = slot
        overlay?.onHostChanged()
    }

    /** Clears the dock when the hosting view is destroyed. [slot] is compared
     *  by identity so a stale destroy for an already-replaced slot is a
     *  no-op. The pill view dies with its host view; the reference is
     *  dropped here so nothing can touch a dead view. */
    fun detachCompactHost(slot: ViewGroup) {
        if (compactHost === slot) {
            compactHost = null
            overlay?.onHostChanged()
        }
    }

    /** The loaded track, for the overlay's own paint. */
    private fun currentTrack(): Track? = tracks.getOrNull(index)

    /** Starts [list] from [startIndex]: replaces any current playback (one
     *  player, one stream — switching contexts just works), binds the
     *  overlay to [activity]'s window layer, and shows it. */
    fun play(activity: Activity, list: List<Track>, startIndex: Int) {
        if (list.isEmpty()) return
        hostActivity = activity
        appContext = activity.applicationContext   // Pass-38 (#6): the persistent session's writer
        tracks = list
        index = startIndex.coerceIn(0, list.lastIndex)
        requestAudioFocus()   // Pass-35 (#9A): own the audio stream while playing
        startCurrent()
        showOverlay()
    }

    fun toggle() {
        val p = player
        if (p == null) {
            // ── Pass-38 (#6): a RESTORED session (paused pill from a previous ──
            // app run) has no live player — the first play builds one and
            // resumes from the remembered position.
            if (currentTrack() != null && hostActivity != null) startCurrent()
            return
        }
        try {
            if (p.isPlaying) {
                p.pause(); isPlaying = false
                // ── Pass-38 (#9): an explicit pause during a video's hold ──
                // cancels the auto-resume — the user said "stay quiet".
                videoHold = null
            } else if (!preparing) {
                // ── Pass-38 (#9): an explicit play while a video holds the ──
                // audio is a deliberate override — the hold is abandoned and
                // the video-end must NOT resume anything on top of it.
                videoHold = null
                p.start(); isPlaying = true
            }
            // preparing: the tap lands between prepareAsync and onPrepared —
            // the prepared path starts it; nothing to do now.
        } catch (e: Exception) { /* released mid-tap */ }
        notifyState()
    }

    fun next() {
        if (tracks.isEmpty()) return
        if (index < tracks.lastIndex) {
            index++
            // ── Pass-38 (#9): a skip during an active video hold MOVES the ──
            // hold to the new session — the new track stays silent while the
            // video plays and resumes from its own beginning afterwards. Only
            // the LATEST session the user expressed is ever eligible.
            if (videoHold != null) videoHold = VideoHold(tracks[index].key, 0L)
            startCurrent()
        }
        else stop()   // ran off the end: a clean stop, exactly like finishing
    }

    fun prev() {
        if (tracks.isEmpty()) return
        if (index > 0) {
            index--
            if (videoHold != null) videoHold = VideoHold(tracks[index].key, 0L)   // Pass-38 (#9): see next()
            startCurrent()
        } else {
            try { player?.seekTo(0) } catch (e: Exception) { /* not seekable */ }
            if (videoHold != null) videoHold = VideoHold(tracks[index].key, 0L)   // Pass-38 (#9)
            restoreSeekMs = 0L   // a restored session rewinds with the button too
            notifyState()
        }
    }

    /** Stop + hide + clear: the single full-teardown path — the explicit
     *  STOP control, the notification's Stop, and (since Pass-40 #9) the ✕
     *  tap's full dismissal. Everything visual goes — the floating card AND
     *  the compact pill — the playback session, the persisted state and the
     *  notification are all torn down together, and there is never a stale
     *  control with a dead player behind it. Idempotent by construction:
     *  a rapid double-✕ (or a ✕ racing a completion) is a clean no-op the
     *  second time — every step null-checks its target.
     *  ── Pass-38 (#6): the explicit dismiss is ALSO the one and only thing ──
     *  that clears the persistent session — app closure, rotation and
     *  process death never pass through here, so they can never masquerade
     *  as a dismiss, and the pill comes back on the next launch.
     *  ── Pass-40 (#9): the ✕ IS this now. "Dismiss" no longer means "hide
     *  the visuals and keep playing" — one action, one meaning: the song
     *  stops, the notification goes, nothing returns after a restart. */
    fun stop() {
        releasePlayer()
        abandonAudioFocus()
        tracks = emptyList(); index = -1
        currentKey = null; isPlaying = false; preparing = false
        videoHold = null
        restoreSeekMs = -1L
        clearPersistedState()
        overlay?.hide()
        MusicNotifier.cancel(appContext ?: hostActivity?.applicationContext)
        notifyState()
    }

    fun positionMs(): Long = try {
        player?.currentPosition?.toLong() ?: restoreSeekMs.coerceAtLeast(0L)
    } catch (e: Exception) { restoreSeekMs.coerceAtLeast(0L) }

    /** True when [key] is the loaded track — the rows' one-line check. */
    fun isCurrent(key: String): Boolean = currentKey == key

    /** ── Pass-35 (#9D): seek the loaded track by [deltaMs] (negative = ──
     *  backward), clamped to [0, duration). The clamp keeps the ends
     *  graceful: 0 rewinds to the start, and the track never seeks ONTO its
     *  own completion (which would fire an unwanted auto-advance). Works
     *  paused or playing; reports state so the overlay's position display
     *  follows the seek immediately instead of on the ticker's cadence. */
    fun seekBy(deltaMs: Long) {
        val p = player ?: run {
            // ── Pass-38 (#6): a restored (not yet playing) session seeks its ──
            // remembered position directly — the hold-to-seek buttons work on
            // the paused pill too.
            val t = currentTrack() ?: return
            val total = if (t.durationMs > 0) t.durationMs else return
            restoreSeekMs = (restoreSeekMs.coerceAtLeast(0L) + deltaMs)
                .coerceAtLeast(0L)
                .coerceAtMost((total - 1).coerceAtLeast(0L))
            notifyState()
            return
        }
        try {
            val duration = p.duration.toLong()
            val target = (p.currentPosition.toLong() + deltaMs)
                .coerceAtLeast(0L)
                .coerceAtMost(if (duration > 0) (duration - 1).coerceAtLeast(0L) else Long.MAX_VALUE)
            p.seekTo(target.toInt())
            notifyState()
        } catch (e: Exception) { /* stale player */ }
    }

    /** ── Pass-36 Part 2 (#6): absolute-position seek for the expanded ──────
     *  player's progress strip — [fraction] of the loaded track's duration,
     *  clamped to [0, duration), the same end-guard seekBy uses (never onto
     *  the track's own completion). Reports state so the strip and the time
     *  labels follow the seek immediately. */
    fun seekToFraction(fraction: Float) {
        val p = player ?: run {
            // ── Pass-38 (#6): a restored (not yet playing) session lands the ──
            // slide-seek on its remembered position itself.
            val t = currentTrack() ?: return
            val total = if (t.durationMs > 0) t.durationMs else return
            restoreSeekMs = (total * fraction.coerceIn(0f, 1f)).toLong()
                .coerceIn(0L, (total - 1).coerceAtLeast(0L))
            notifyState()
            return
        }
        try {
            val duration = p.duration.toLong()
            if (duration <= 0) return
            val target = (duration * fraction.coerceIn(0f, 1f)).toLong()
                .coerceIn(0L, (duration - 1).coerceAtLeast(0L))
            p.seekTo(target.toInt())
            notifyState()
        } catch (e: Exception) { /* stale player */ }
    }

    // ── Pass-35 (#9A): audio-focus interruption handling. The player now ──────
    // requests focus when it starts and answers the system's changes the way
    // a well-behaved audio app does: a transient loss (a call, a navigation
    // prompt) pauses and RESUMES on regain; a duckable loss (a notification
    // chime) drops to 20% volume and restores on gain; a permanent loss
    // pauses and stays paused (the person resumes deliberately). Every
    // transition notifies state, so the overlay, the picker rows and every
    // other control repaint from the player's real condition — the
    // displayed icon can never disagree with the audio again.
    private var audioManager: AudioManager? = null
    private var focusListener: AudioManager.OnAudioFocusChangeListener? = null
    private var focusRequest: android.media.AudioFocusRequest? = null
    private var pausedForTransientFocus = false
    private var duckedForFocus = false

    private fun requestAudioFocus() {
        val activity = hostActivity ?: return
        val am = try {
            activity.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        } catch (e: Exception) { null } ?: return
        abandonAudioFocus()
        audioManager = am
        pausedForTransientFocus = false
        duckedForFocus = false
        val listener = AudioManager.OnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Permanent: pause and stay paused — no auto-resume.
                    pausedForTransientFocus = false
                    duckedForFocus = false
                    try { player?.setVolume(1f, 1f) } catch (e: Exception) { /* gone */ }
                    internalPause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    pausedForTransientFocus = true
                    internalPause()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    duckedForFocus = true
                    try { player?.setVolume(0.2f, 0.2f) } catch (e: Exception) { /* gone */ }
                    notifyState()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    if (duckedForFocus) {
                        duckedForFocus = false
                        try { player?.setVolume(1f, 1f) } catch (e: Exception) { /* gone */ }
                        notifyState()
                    }
                    if (pausedForTransientFocus) {
                        pausedForTransientFocus = false
                        try {
                            player?.start(); isPlaying = player?.isPlaying == true
                        } catch (e: Exception) { /* refused */ }
                        notifyState()
                    }
                }
            }
        }
        focusListener = listener
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build())
                    .setOnAudioFocusChangeListener(listener)
                    .build()
                focusRequest = request
                am.requestAudioFocus(request)
            } else {
                @Suppress("DEPRECATION")   // the API-21..25 route; the AudioFocusRequest form is 26+
                am.requestAudioFocus(listener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
            }
        } catch (e: Exception) { focusListener = null; focusRequest = null }
    }

    private fun abandonAudioFocus() {
        val listener = focusListener
        try {
            val am = audioManager
            if (am != null) {
                focusRequest?.let { am.abandonAudioFocusRequest(it) }
                if (listener != null) am.abandonAudioFocus(listener)   // pre-26 route; a no-op after the request form
            }
        } catch (e: Exception) { /* gone */ }
        focusListener = null
        focusRequest = null
        pausedForTransientFocus = false
        duckedForFocus = false
    }

    /** Pause that only mirrors the player's real state (the focus listener's
     *  path — no toggle semantics, no resume bookkeeping beyond the flags). */
    private fun internalPause() {
        try {
            player?.pause()
            isPlaying = false
        } catch (e: Exception) { /* gone */ }
        notifyState()
    }

    private fun releasePlayer() {
        player?.let { p ->
            try { if (p.isPlaying) p.stop() } catch (e: Exception) { /* mid-release */ }
            try { p.release() } catch (e: Exception) { /* already gone */ }
        }
        player = null
    }

    private fun startCurrent() {
        val activity = hostActivity
        val track = currentTrack()
        if (activity == null || track == null) { stop(); return }
        releasePlayer()
        val p = try { MediaPlayer() } catch (e: Exception) { null } ?: run { notifyState(); return }
        player = p
        currentKey = track.key
        isPlaying = false
        preparing = true
        try {
            @Suppress("DEPRECATION")   // AudioAttributes needs API 26; the stream-type route covers the app's full range
            p.setAudioStreamType(AudioManager.STREAM_MUSIC)
            p.setDataSource(activity, track.uri)
            p.setOnPreparedListener { mp ->
                preparing = false
                // ── Pass-38 (#6): a restored session's first play lands on ──
                // its remembered position before the audio starts.
                if (restoreSeekMs > 0) {
                    try { mp.seekTo(restoreSeekMs.toInt()) } catch (e: Exception) { /* not seekable */ }
                    restoreSeekMs = -1L
                }
                // ── Pass-39 (#3): the ENGINE's duration is authoritative the ──
                // moment it exists. A queue-sourced track is built with
                // durationMs = 0 (nothing knows it yet), and the display used
                // to fall back to the live POSITION as the total — pos/pos =
                // a permanently FULL progress bar ("track shows completed the
                // instant it starts"). The track now carries the real duration
                // from the first prepared frame, so the strip, the times and
                // every seek compute against the truth.
                val t = currentTrack()
                if (t != null && t.durationMs <= 0) {
                    try {
                        val d = mp.duration.toLong()
                        if (d > 0) { t.durationMs = d; persistState() }
                    } catch (e: Exception) { /* not yet durationable */ }
                }
                // ── Pass-38 (#9): while a video holds the audio, a newly ──
                // prepared track does NOT start — it resumes together with
                // the hold's release (the user skipped during a video).
                if (videoHold == null) {
                    try { mp.start(); isPlaying = true } catch (e: Exception) { /* refused to start */ }
                } else {
                    isPlaying = false
                }
                notifyState()
            }
            // Auto-advance: finishing one track is exactly "next" — the
            // playlist context the user started keeps rolling.
            p.setOnCompletionListener { next() }
            p.setOnErrorListener { _, _, _ ->
                stop()
                try { android.widget.Toast.makeText(activity, "Couldn't play this track", android.widget.Toast.LENGTH_SHORT).show() } catch (e: Exception) { /* activity gone */ }
                true
            }
            p.prepareAsync()
        } catch (e: Exception) {
            releasePlayer()
            currentKey = null; preparing = false
            try { android.widget.Toast.makeText(activity, "Couldn't play this track", android.widget.Toast.LENGTH_SHORT).show() } catch (ex: Exception) { /* activity gone */ }
        }
        // ── Pass-39 (#3): the queue's tracks start metadata-poor (filename ──
        // title, no artist, no duration); the real tags + embedded artwork are
        // read once, off-main, and land on THIS track while it plays — the
        // same quality of information the Music sheet's MediaStore rows carry.
        track?.let { enrichTrackMetadata(it) }
        notifyState()
    }

    /** ── Pass-39 (#3): one async metadata pass per track ─────────────────────
     *  For a track that still lacks artist/duration (the queue-audio shape),
     *  read MediaMetadataRetriever's title/artist/duration and the embedded
     *  artwork once on IO, then apply them to the track — IF it is still the
     *  loaded one (a skip during the read must never repaint the NEXT track
     *  with the previous one's tags; that is the "stale metadata" guard).
     *  The embedded picture goes straight into the shared art cache under the
     *  track's own artKey, so the overlay's art slot (which reads that exact
     *  key) shows it deterministically — the same one-decode-per-source rule
     *  every other surface follows. */
    /** The retriever's one-pass answer for a metadata-poor track. */
    private class TrackMetadata(val title: String?, val artist: String?, val duration: Long?, val art: Bitmap?)

    private fun enrichTrackMetadata(track: Track) {
        if (track.artist != null && track.durationMs > 0) return   // already complete
        val cacheKey = track.artKey
        // The queue-audio shape: filename title, no artist, no duration —
        // captured BEFORE the read, when the decision is made.
        val wasPoor = track.artist == null && track.durationMs <= 0
        scope.launch {
            val ctx = appContext ?: hostActivity?.applicationContext ?: return@launch
            val meta = withContext(Dispatchers.IO) {
                try {
                    val r = MediaMetadataRetriever()
                    try {
                        r.setDataSource(ctx, track.uri)
                        val title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)?.trim()?.ifBlank { null }
                        val artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.trim()?.ifBlank { null }
                        val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                        var art: Bitmap? = null
                        if (MusicArtCache.cachedBitmap(cacheKey) == null) {
                            val data = r.embeddedPicture
                            if (data != null && data.isNotEmpty()) {
                                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                                BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                                if (bounds.outWidth > 0) {
                                    val sample = maxOf(1, minOf(bounds.outWidth, bounds.outHeight) / 320)
                                    art = BitmapFactory.decodeByteArray(data, 0, data.size,
                                        BitmapFactory.Options().apply { inSampleSize = sample })
                                }
                            }
                        }
                        TrackMetadata(title, artist, duration, art)
                    } finally { try { r.release() } catch (e: Exception) { /* already gone */ } }
                } catch (e: Exception) { null }
            } ?: return@launch
            if (meta.art != null) MusicArtCache.rememberBitmap(cacheKey, meta.art)
            // Stale-metadata guard: apply ONLY while this exact track is the
            // loaded one — a skip mid-read leaves the next track untouched.
            if (currentKey != track.key) return@launch
            var changed = false
            // The retriever's real TITLE replaces the filename-derived one
            // only for the metadata-poor queue shape; gaps fill either way.
            if (wasPoor && !meta.title.isNullOrBlank()) { track.title = meta.title!!; changed = true }
            if (track.artist == null && meta.artist != null) { track.artist = meta.artist; changed = true }
            if (track.durationMs <= 0 && meta.duration != null && meta.duration > 0) { track.durationMs = meta.duration; changed = true }
            if (changed) notifyState()   // repaints overlay + pill + notification, and re-persists the enriched values
        }
    }

    private fun showOverlay() {
        val activity = hostActivity ?: return
        try {
            if (overlay == null) overlay = PlaybackOverlay(activity)
            overlay?.show()
        } catch (e: Exception) { /* window attach raced activity death; playback itself still runs */ }
        overlay?.refresh()
    }

    // ══ Pass-38 (#9): music/video playback coordination ══════════════════════
    //
    // ONE hold slot, edge-driven, no timers. The media preview reports only
    // REAL transitions of "any video playing" (every video view reports its
    // own starts/stops — page changes, recycles, surface detaches, completion,
    // errors, sheet dismissal all funnel into that one boolean's edges):
    //
    //   • edge to TRUE  — the session that was PLAYING at that moment is
    //     remembered (key + position) and paused. A session that was already
    //     paused is NOT eligible: nothing is remembered for it, ever.
    //   • edge to FALSE — the remembered session resumes IF AND ONLY IF it is
    //     still the loaded one, from exactly its remembered position.
    //
    // Every user action on the player during the video resolves the hold the
    // way the user said to: an explicit PAUSE or PLAY cancels it (no
    // auto-resume, no double-resume), a SKIP moves it to the new session
    // (which stays silent until the video ends), and STOP/DISMISS clears it
    // outright. There is no path by which two holds stack or a resume fires
    // twice — the slot is single, and every transition through it is guarded.
    private class VideoHold(val trackKey: String, val positionMs: Long)
    private var videoHold: VideoHold? = null

    /** The preview's playing-set edge: [anyVideoPlaying] = "at least one
     *  video is audible right now". Idempotent — repeated identical reports
     *  are no-ops, so any number of video events can never produce repeated
     *  or conflicting pause/resume operations. */
    fun onVideoPlaybackChanged(anyVideoPlaying: Boolean) {
        if (anyVideoPlaying) {
            if (videoHold != null) return            // already held — a second video changes nothing
            val key = currentKey ?: return
            if (!isPlaying) return                   // was already paused: never eligible for auto-resume
            videoHold = VideoHold(key, positionMs())
            internalPause()
            notifyState()
        } else {
            val h = videoHold ?: return              // nothing held — a plain stop edge, nothing to do
            videoHold = null
            if (currentKey == h.trackKey && player != null) {
                try { player?.seekTo(h.positionMs.toInt()) } catch (e: Exception) { /* stale player */ }
                try { player?.start(); isPlaying = player?.isPlaying == true } catch (e: Exception) { /* refused */ }
            }
            notifyState()
        }
    }

    // ══ Pass-38 (#6): the persistent music pill ══════════════════════════════
    //
    // The session's identity and state live in SharedPreferences
    // ("lumidrop_music"): the playlist window around the current track, the
    // index, the position, the play/pause condition and the presentation
    // (expanded/compact). Written at every state change (and every ~5s while
    // playing, so a mid-song kill loses at most five seconds of position),
    // cleared ONLY by the explicit dismiss (stop() — the ✕-hold): app
    // closure, activity recreation, rotation and process death never touch
    // it, which is exactly why they can never behave like pressing X.
    //
    // The next launch restores the PILL (never auto-playing — the restored
    // session is paused until the user presses play, which resumes from the
    // remembered position). A restored source that no longer exists answers
    // with the honest play error and clears the session; a plain file://
    // source is checked at restore time already.

    private fun persistPrefs(ctx: Context) =
        ctx.getSharedPreferences("lumidrop_music", Context.MODE_PRIVATE)

    private fun persistState() {
        val ctx = appContext ?: hostActivity?.applicationContext ?: return
        if (currentKey == null) { clearPersistedState(); return }
        try {
            val from = (index - persistWindow).coerceAtLeast(0)
            // ── Pass-39 (extra fix 1): the window is ±persistWindow AROUND ──
            // the current track. The old upper bound was the index itself —
            // a restored session ALWAYS sat on the last persisted track, so
            // finishing it ran off the end into stop() ("after the reopen the
            // song ends and the player just closes"). The tracks AFTER the
            // current one persist now, and auto-advance works after a
            // restore exactly like it always did mid-session.
            val to = (index + persistWindow).coerceAtMost(tracks.lastIndex)
            val arr = org.json.JSONArray()
            for (i in from..to) {
                val t = tracks[i]
                arr.put(org.json.JSONObject()
                    .put("k", t.key).put("u", t.uri.toString()).put("t", t.title)
                    .put("a", t.artist ?: "").put("al", t.albumId)
                    .put("d", t.durationMs).put("s", t.size))
            }
            persistPrefs(ctx).edit()
                .putString("tracks", arr.toString())
                .putInt("index", index - from)
                .putLong("pos", positionMs())
                .putBoolean("playing", isPlaying)
                .apply()
        } catch (e: Exception) { /* persistence must never break playback */ }
    }

    private fun clearPersistedState() {
        val ctx = appContext ?: hostActivity?.applicationContext ?: return
        try { persistPrefs(ctx).edit().clear().apply() } catch (e: Exception) { /* gone */ }
    }

    /** The 5s position refresher while audio is live (see persistState's
     *  contract). One coroutine, launched on demand, standing down by itself
     * once nothing is playing. */
    private fun ensurePersistTicker() {
        if (persistTicker?.isActive == true) return
        persistTicker = scope.launch {
            while (true) {
                delay(5000)
                if (isPlaying || preparing) persistState() else break
            }
        }
    }

    /** ── Pass-38 (#6): the launch hook (MainActivity.onCreate — app start ──
     *  AND activity recreation both land here). A live session keeps its
     *  player untouched and only rebinds the visual to the new window; a
     *  dead one with a persisted session becomes the restored paused pill. */
    fun maybeRestore(activity: Activity) {
        appContext = activity.applicationContext
        if (player != null || currentKey != null) {
            // Live session on a recreated activity (rotation): the overlay's
            // window died with the old activity — rebind the SAME state.
            if (overlay?.activity !== activity) rebindOverlay(activity)
            return
        }
        val ctx = activity.applicationContext
        val prefs = persistPrefs(ctx)
        val blob = try { prefs.getString("tracks", null) } catch (e: Exception) { null } ?: return
        try {
            val arr = org.json.JSONArray(blob)
            if (arr.length() == 0) return
            val parsed = ArrayList<Track>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                parsed.add(Track(
                    key = o.getString("k"),
                    uri = Uri.parse(o.getString("u")),
                    title = o.getString("t"),
                    artist = o.optString("a").ifBlank { null },
                    albumId = o.optLong("al", 0L),
                    durationMs = o.optLong("d", 0L),
                    size = o.optLong("s", 0L),
                ))
            }
            val idx = prefs.getInt("index", 0).coerceIn(0, parsed.lastIndex)
            val pos = prefs.getLong("pos", 0L).coerceAtLeast(0L)
            val track = parsed[idx]
            // Deleted-source guard for plain files (content URIs are judged
            // by the player's own honest error path at play time).
            if (track.uri.scheme == "file" && !File(track.uri.path ?: "").exists()) {
                clearPersistedState()
                return
            }
            tracks = parsed
            index = idx
            currentKey = track.key
            isPlaying = false          // NEVER auto-play a restored session
            preparing = false
            restoreSeekMs = pos
            hostActivity = activity
            try {
                if (overlay == null) overlay = PlaybackOverlay(activity)
                // ── Pass-39 (#8): the restoration presentation is ALWAYS the ──
                // compact pill — never the expanded card. A full app reopen is
                // a fresh context, not a continuation of an in-flight
                // interaction: no persisted expanded-state value is read (the
                // flag is not even stored any more), so no stale "expanded"
                // can ever override the compact landing. In-session
                // presentation (expand/collapse during use) is untouched —
                // this is exclusively the after-reopen landing shape.
                overlay?.restoreState(false)
                overlay?.refresh()
            } catch (e: Exception) { /* window attach raced activity death */ }
            notifyState()
        } catch (e: Exception) {
            // Corrupted store — drop it rather than restore nonsense.
            clearPersistedState()
        }
    }

    /** Re-create the overlay against a NEW activity (rotation), in the same
     *  expanded/compact presentation, with the player untouched. */
    private fun rebindOverlay(activity: Activity) {
        try {
            val wasExpanded = overlay?.isExpanded == true
            overlay?.hide()
            overlay = PlaybackOverlay(activity)
            if (wasExpanded) overlay?.show() else overlay?.restoreState(false)
            overlay?.refresh()
        } catch (e: Exception) { /* window attach raced activity death */ }
    }

    /**
     * ── Pass-36 Part 2 (#5–#8) + Pass-37 (#2): the adaptive player — both ──────
     * states, one owner. The EXPANDED card is a floating window of ONE
     * horizontal width (min(320dp, screen − 24dp) — consistent left/right
     * bounds regardless of track name or metadata), anchored below the Server
     * Card when the compact dock exists (top-center otherwise); the COMPACT
     * pill is a 152×26dp view mounted inside the Send tab's Server Card title
     * row. Both representations repaint from the controller's single state on
     * every [refresh], so they can never disagree about the track, the
     * artwork or the play/pause condition.
     *
     * ── Pass-37 (#2): the expanded card is the COMPACT one-row design again ──
     * (the Pass-36-Part-1 geometry): artwork, title/artist/position and prev /
     * play-pause / next / ✕ on a single line, with NO separate progress row —
     * playback progress is a subtle accent fill inside the card's own
     * background (ProgressCardDrawable) and seeking is a horizontal SLIDE
     * across the card (the buttons consume their own touches, so a slide can
     * never start on or fight a control).
     *
     * Transitions:
     *   • collapse — after [COLLAPSE_AFTER_MS] without a meaningful
     *     interaction (any press on the card, any control, any seek-touch
     *     resets the clock — never while the user is actively interacting).
     *     The card animates out, the window is hidden (its views and state
     *     survive), and the pill fades into the Server Card. If no dock
     *     exists, the collapse is SKIPPED — the card stays up rather than
     *     spawning a floating pill elsewhere.
     *   • expand — a tap on the pill (or a new play()) reverses it: the
     *     window shows again, the pill unmounts, and the card animates in
     *     from the pill's direction. The player itself is untouched by
     *     either transition — track, position and state are preserved by
     *     construction.
     *   • hide — the full teardown (MusicPlayback.stop): window dismissed,
     *     pill unmounted, timers cancelled. The ✕ collapses; HOLDING the ✕
     *     stops (the explicit Stop stays reachable without a fifth button).
     */
    private class PlaybackOverlay(val activity: Activity) {

        companion object {
            /** The inactivity window before the expanded card folds into the
             *  compact pill (#6). Reset by every meaningful interaction. */
            const val COLLAPSE_AFTER_MS = 4000L
        }

        val dialog = Dialog(activity, android.R.style.Theme_Translucent_NoTitleBar)

        // ── The expanded card ──────────────────────────────────────────────────
        private val card: LinearLayout
        private val art: ImageView
        private val title: TextView
        private val artist: TextView
        private val position: TextView
        private val playBtn: ImageView
        /** ── Pass-37 (#2): the card's own background IS the progress bar — ──
         *  no separate row, no extra height. */
        private val progressBg: ProgressCardDrawable

        // ── Lifecycle state ────────────────────────────────────────────────────
        /** The dialog object has been shown at least once (its window may be
         *  hidden while collapsed — hide()/show() preserve the views). */
        private var shown = false
        private var expandedNow = false
        private var ticker: Job? = null
        private var collapseJob: Job? = null

        // ── Pass-37 (#2): the slide-to-seek state. dragFraction owns the ──────
        // display while the finger is down; pendingSeekMs is the committed
        // target pinned until the player's async seekTo converges on it (the
        // ticker would otherwise read the still-stale pre-seek position and
        // snap the fill back).
        private var slideStartX = 0f
        private var slideDragging = false
        private var dragFraction: Float? = null
        private var pendingSeekMs = -1L
        private var pendingSeekTicks = 0

        // ── The compact pill (inside the Server Card's title-row slot) ────────
        private var pill: LinearLayout? = null
        private var pillTitle: TextView? = null
        private var pillEq: EqBarsView? = null

        init {
            fun col(id: Int) = ContextCompat.getColor(activity, id)

            dialog.window?.apply {
                setBackgroundDrawableResource(android.R.color.transparent)
                // ── Pass-41 (§5) + Pass-42 (§2/§3): a CARD-SIZED window that ──
                // PASSES INTERACTIONS THROUGH, with a DETERMINISTIC width and
                // NO outside-click dismissal. Pass-39's (#12) full app-area
                // window with a touch-consuming backdrop swallowed the first
                // outside tap/scroll entirely; the window has wrapped only the
                // card since Pass-41, so anything outside the card's bounds
                // reaches the app beneath (the click opens the item, the
                // scroll scrolls the folder) and the card keeps every one of
                // its own events — controls, slide-to-seek, the seek strip.
                // ── Pass-42 (§2): the window width is the card's EXACT ──
                // design width (set below, once cardW is computed), never a
                // WRAP_CONTENT measurement — a WRAP window's measured size
                // could differ between the first show and later re-adds
                // (rotation rebind, bringAboveSheets' dismiss+show, the
                // pill's re-expand) on some themes and Android versions,
                // which is how a queue-started session could render with a
                // near-full-width overlay while a Music-sheet session looked
                // right. An explicit width makes the geometry identical for
                // EVERY playback source, device and window-lifetime path,
                // and cardW ≤ screen − 24dp keeps it off both edges by
                // construction.
                // ── Pass-42 (§3): outside-click MINIMIZATION is gone. The ──
                // expanded card folds into its pill ONLY through the 4-second
                // inactivity clock (and the ✕) — a tap or scroll elsewhere
                // in the app is that app's own interaction, not a player
                // command. NOT_TOUCH_MODAL stays (it is what lets those
                // interactions through); WATCH_OUTSIDE_TOUCH went with the
                // listener that consumed ACTION_OUTSIDE.
                setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                attributes.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                // LAYOUT_IN_SCREEN pins the window's coordinate space to the
                // SCREEN: y is unambiguous — 0 = screen top — and applyAnchor
                // can position the card directly at the pill's own screen-top.
                addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN)
                addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            dialog.setCancelable(false)

            // ── Pass-37 (#2): the COMPACT one-row card — the Pass-36-Part-1 ───
            // geometry restored (artwork, title/artist/position and prev /
            // play-pause / next / ✕ on ONE line, same paddings, same ~56dp
            // height), with the earlier fixed-BOUND request honored the way
            // it was meant: ONE horizontal width — min(320dp, screen − 24dp)
            // — with fixed left/right edges regardless of track name or
            // metadata length. The oversized two-row design (its separate
            // progress strip + times row) is gone entirely: playback progress
            // is a subtle accent fill inside the card's own background
            // (ProgressCardDrawable), and seeking is a SLIDE across the card
            // itself — the progress indicator is part of the player, not a
            // row of it.
            val cardW = minOf(activity.dp(320), activity.resources.displayMetrics.widthPixels - activity.dp(24))
            progressBg = ProgressCardDrawable(
                baseColor = 0xF2171A20.toInt(),
                strokeColor = (col(R.color.accent) and 0x00FFFFFF) or 0x4D000000,
                fillColor = (col(R.color.accent) and 0x00FFFFFF) or 0x33000000,
                strokePx = activity.dp(1).toFloat(),
                radiusPx = activity.dpf(20f))
            card = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(8), activity.dp(7), activity.dp(4), activity.dp(7))
                isClickable = true; isFocusable = true
                background = progressBg
                elevation = activity.dpf(14f)
                layoutParams = FrameLayout.LayoutParams(cardW, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            }
            // ── Pass-42 (§2): pin the WINDOW to the card's exact design width ──
            // (see the window-setup comment above): every show path — first
            // play, pill re-expand, rotation rebind, bringAboveSheets' dismiss+
            // show — attaches a window of the SAME width for a Music-sheet
            // session and a Queue session alike. Zero horizontal margins; the
            // TOP|CENTER gravity centers it, so the edge clearance is
            // (screen − cardW)/2 ≥ 12dp on every device.
            try {
                dialog.window?.let { w ->
                    w.attributes.width = cardW
                    w.attributes.horizontalMargin = 0f
                    w.setAttributes(w.attributes)
                }
            } catch (e: Exception) { /* window not yet attachable — the card's own fixed width still bounds the layout */ }

            art = ImageView(activity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = activity.dpf(10f)
                    setColor(0x14FFFFFF)
                }
                // ── GOAT-PASS 7 Part 2 (§7B): the artwork is clipped by the ──
                // VIEW ITSELF at a fixed 10dp radius (clipToOutline + a round-
                // rect outline provider). The old paint-level rounding
                // (RoundedBitmapDrawable alone) let the effective radius
                // SHRINK with the CENTER_CROP scale matrix whenever the
                // decoded bitmap came back larger than the view — a 42dp
                // slot could render corners as tight as ~4dp, which read as
                // sharp edges. The outline clip is applied AFTER scaling, in
                // view coordinates, so the corner radius is exactly 10dp on
                // every device, every bitmap size, every drawable path —
                // while the dimensions, proportions and positioning stay
                // byte-for-byte what they were.
                clipToOutline = true
                outlineProvider = object : android.view.ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: android.graphics.Outline) {
                        outline.setRoundRect(0, 0, view.width, view.height, view.context.dpf(10f))
                    }
                }
                layoutParams = LinearLayout.LayoutParams(activity.dp(42), activity.dp(42)).also {
                    it.marginStart = activity.dp(2); it.marginEnd = activity.dp(10)
                }
            }
            val texts = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            title = TextView(activity).apply {
                textSize = 13f; setTextColor(col(R.color.text_primary)); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            artist = TextView(activity).apply {
                textSize = 10.5f; setTextColor(col(R.color.text_secondary)); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            position = TextView(activity).apply {
                textSize = 10.5f; setTextColor(col(R.color.text_muted)); maxLines = 1
            }
            texts.addView(title); texts.addView(artist); texts.addView(position)

            fun ctl(res: Int, desc: String, size: Int = 34, iconSize: Int = 15): ImageView =
                Widgets.iconButton(activity, res, col(R.color.text_secondary), sizeDp = size, iconSizeDp = iconSize, contentDescription = desc)

            // ── Pass-35 (#9D): PREV/NEXT with hold-to-seek, unchanged — a ──────
            // press under 280ms is the classic track change; holding past it
            // switches to seeking through the CURRENT track. Every phase of the
            // hold also resets the collapse clock (never collapse mid-gesture).
            fun seekControl(res: Int, desc: String, direction: Int): ImageView {
                val btn = ctl(res, desc)
                var held = false
                var holdJob: Job? = null
                btn.setOnTouchListener { _, e ->
                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            noteInteraction()
                            held = false
                            holdJob?.cancel()
                            holdJob = scope.launch {
                                delay(280)   // the tap window — anything shorter is a track change
                                held = true
                                var stepMs = 900L
                                while (true) {
                                    noteInteraction()
                                    seekBy(direction * stepMs)
                                    delay(160)
                                    stepMs = (stepMs * 115L / 100L).coerceAtMost(4000L)
                                }
                            }
                            true
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            holdJob?.cancel()
                            if (e.actionMasked == MotionEvent.ACTION_UP && !held) {
                                noteInteraction()
                                if (direction < 0) prev() else next()
                            }
                            true
                        }
                        else -> false
                    }
                }
                return btn
            }

            val prevBtn = seekControl(R.drawable.ic_skip_previous, "Previous track (hold to rewind)", -1)
            playBtn = ImageView(activity).apply {
                setImageDrawable(Widgets.icon(activity, R.drawable.ic_play, col(R.color.accent_light)))
                layoutParams = FrameLayout.LayoutParams(activity.dp(34), activity.dp(34), Gravity.CENTER)
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor((col(R.color.accent) and 0x00FFFFFF) or 0x33000000)
                }
                setPadding(activity.dp(9), activity.dp(9), activity.dp(9), activity.dp(9))
            }
            val playFrame = FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(activity.dp(34), activity.dp(34))
                addView(playBtn)
            }
            val nextBtn = seekControl(R.drawable.ic_skip_next, "Next track (hold to fast-forward)", 1)
            // ── Pass-37 (#2): the ONE control set the compact design asks for — ──
            // prev / play-pause / next / ✕, nothing else on the row.
            // ── Pass-40 (#9): the ✕ tap is the FULL dismissal — playback
            // stops, the session resets, the notification goes, the visuals
            // (card AND pill, compact or expanded, every tab) disappear, and
            // nothing returns after navigation or an app restart. The old
            // Pass-39 tap ("hide the visuals, keep the song running in the
            // notification") is retired: one ✕, one meaning — stop and
            // close. The hold does the same thing (kept for muscle memory;
            // it can never diverge from the tap again).
            val closeBtn = ctl(R.drawable.ic_close, "Stop and close player")

            playBtn.setOnClickListener { noteInteraction(); toggle() }
            closeBtn.setOnClickListener { noteInteraction(); stop() }
            closeBtn.setOnLongClickListener { noteInteraction(); stop(); true }

            card.addView(art); card.addView(texts)
            card.addView(prevBtn); card.addView(playFrame); card.addView(nextBtn)
            card.addView(closeBtn)

            // ── Pass-37 (#2): SLIDE-TO-SEEK across the card itself. A ──────────
            // horizontal drag on any empty card area (the buttons consume their
            // own touches, so the gesture can never start on or fight a
            // control) scrubs playback across the card's full horizontal range.
            // The finger owns the display while it is down — the position
            // ticker never overrides an active drag or a just-committed seek
            // (dragFraction + the pending-seek convergence guard below).
            //
            // ── Pass-40 (#3): the card's two TAP zones. The card's main body ──
            // is the overlay's MAIN CONTAINER: a plain tap on it navigates to
            // the Music sheet and scrolls to the exact playing track (never a
            // seek, never an accidental control). The card's bottom edge is
            // the PROGRESS BAR's tap zone: a tap there seeks straight to the
            // tapped fraction — the clickable counterpart of the slide. The
            // two zones and the slide can never fight: the slide arms only
            // after the touch slop is crossed (a tap never crosses it), the
            // buttons consume their own touches, and the zones only diverge
            // on the finger's RELEASE position.
            val touchSlop = android.view.ViewConfiguration.get(activity).scaledTouchSlop
            // The progress bar's tap zone: the bottom 12dp of the card — the
            // strip the progress fill visually runs along, below the controls'
            // reach (the 34dp buttons sit centered in the ~62dp card).
            val stripZonePx = activity.dp(12).toFloat()
            card.setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        noteInteraction()
                        slideStartX = e.x
                        slideDragging = false
                        true   // the card owns the stream
                    }
                    MotionEvent.ACTION_MOVE -> {
                        noteInteraction()
                        if (!slideDragging && kotlin.math.abs(e.x - slideStartX) > touchSlop) slideDragging = true
                        if (slideDragging) {
                            val f = (e.x / card.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            dragFraction = f
                            progressBg.setFraction(f, animated = false)
                            val t = currentTrack()
                            val total = if (t != null && t.durationMs > 0) t.durationMs else positionMs()
                            position.text = formatMs((total * f).toLong())
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        val f = dragFraction
                        dragFraction = null
                        slideDragging = false
                        if (f != null) {
                            val t = currentTrack()
                            val total = if (t != null && t.durationMs > 0) t.durationMs else positionMs()
                            pendingSeekMs = (total * f).toLong()
                                .coerceIn(0L, (total - 1).coerceAtLeast(0L))
                            pendingSeekTicks = 0
                            seekToFraction(f)
                        } else {
                            // A TAP (no slide ever armed). The bottom strip is
                            // the progress bar: seek to the tapped fraction.
                            // Everywhere else is the main container: navigate
                            // to the Music sheet focused on the playing track.
                            if (e.y >= card.height - stripZonePx) {
                                val frac = (e.x / card.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                val t = currentTrack()
                                val total = if (t != null && t.durationMs > 0) t.durationMs else positionMs()
                                if (total > 0) {
                                    pendingSeekMs = (total * frac).toLong()
                                        .coerceIn(0L, (total - 1).coerceAtLeast(0L))
                                    pendingSeekTicks = 0
                                    progressBg.setFraction(frac, animated = false)
                                    position.text = formatMs(pendingSeekMs)
                                    seekToFraction(frac)
                                }
                            } else {
                                requestMusicSheetFocus()
                            }
                        }
                        true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        dragFraction = null
                        slideDragging = false
                        currentTrack()?.let { tickPosition(it) }
                        true
                    }
                    else -> false
                }
            }

            // ── Pass-41 (§5) → Pass-42 (§3): the card IS the window content ──
            // (no Pass-39 backdrop), and NOT_TOUCH_MODAL delivers everything
            // outside the card straight to the app beneath. Pass-41 also
            // folded the card into its pill on the decor's ACTION_OUTSIDE;
            // that outside-click MINIMIZATION is retired now — a tap or a
            // scroll elsewhere in the app is that app's own interaction and
            // leaves the expanded player exactly where it is. The ONLY
            // things that fold the card are the 4-second inactivity clock
            // (COLLAPSE_AFTER_MS, untouched) and the ✕. No listener rides
            // the decor for this anymore, and no replacement interaction
            // was added.
            dialog.setContentView(card)
        }

        private fun statusBarHeight(): Int = try {
            val id = activity.resources.getIdentifier("status_bar_height", "dimen", "android")
            if (id > 0) activity.resources.getDimensionPixelSize(id) else activity.dp(26)
        } catch (e: Exception) { activity.dp(26) }

        private fun navBarHeight(): Int = try {
            val id = activity.resources.getIdentifier("navigation_bar_height", "dimen", "android")
            if (id > 0) activity.resources.getDimensionPixelSize(id) else 0
        } catch (e: Exception) { 0 }

        /** The expanded card's on-screen anchor. ── Pass-39 (#10): the card's ──
         *  TOP EDGE sits at the compact pill's TOP EDGE — the slot the pill
         *  docks in, wherever the visible tab placed it (Server Card title
         *  row on Send, header row on Transfers/Settings) — so expanding
         *  reads as the card growing out of the pill in place, with no
         *  vertical jump. ── Pass-41 (§5): the window wraps the card now, so
         *  the anchor is the WINDOW's own y — the pill's screen-top directly
         *  (LAYOUT_IN_SCREEN makes 0 = screen top), clamped on-screen. The
         *  classic top-center position stands in when no dock is laid out. */
        private fun applyAnchor() {
            try {
                val screenH = activity.resources.displayMetrics.heightPixels
                val cardH = card.height.takeIf { it > 0 } ?: activity.dp(72)   // the compact one-row card's height until laid out
                val minTop = statusBarHeight() + activity.dp(6)
                val maxTop = (screenH - cardH - activity.dp(12)).coerceAtLeast(minTop)
                // ── Pass-40 (#3): while a modal picker sheet is up, the pill's ──
                // dock may sit anywhere — including scrolled UNDER the sheet's
                // 75% coverage, which would clamp the card to the screen's
                // bottom behind it. The one reliably visible zone is the
                // exposed top strip, so the expanded card anchors TOP-CENTER
                // over the sheet whenever one is open; the pill-anchored
                // geometry applies the rest of the time, exactly as before.
                val pillTop = if (modalSheetOpen) null else compactHost?.let { h ->
                    try {
                        val loc = IntArray(2)
                        h.getLocationOnScreen(loc)
                        loc[1]
                    } catch (e: Exception) { null }
                }
                val y = (pillTop ?: minTop).coerceIn(minTop, maxTop)
                val w = dialog.window ?: return
                if (w.attributes.y != y) {
                    w.attributes.y = y
                    w.setAttributes(w.attributes)
                }
            } catch (e: Exception) { /* keep the last anchor */ }
        }

        // ── The inactivity clock (#6) ───────────────────────────────────────────

        /** ── GOAT-PASS 11 (§5): verifies the expanded card is GENUINELY ──
         *  visible and repairs whatever wedge is found — window re-shown,
         *  card snapped out of a cancelled-animation half-state (alpha,
         *  translation, scale all at their settled values), and the clamped
         *  on-screen anchor re-applied. Every step is individually guarded;
         *  the next tap simply tries again. */
        private fun repairExpandedVisibility() {
            try {
                if (!dialog.isShowing) { dialog.show(); shown = true }
            } catch (e: Exception) { /* the next tap tries again */ }
            try {
                card.animate().cancel()
                if (card.alpha < 0.99f || card.translationY != 0f || card.scaleX != 1f || card.scaleY != 1f) {
                    card.alpha = 1f; card.translationY = 0f; card.scaleX = 1f; card.scaleY = 1f
                }
            } catch (e: Exception) { /* view state repair is best-effort */ }
            try { applyAnchor() } catch (e: Exception) { /* keep the last anchor */ }
        }

        /** Any meaningful interaction with the expanded player restarts the
         *  collapse countdown. Never starts one while collapsed. */
        private fun noteInteraction() {
            if (!expandedNow) return
            collapseJob?.cancel()
            collapseJob = scope.launch {
                delay(COLLAPSE_AFTER_MS)
                // No dock → no collapse: the card stays up rather than
                // spawning a floating pill somewhere else (#7).
                if (expandedNow && compactHost != null) collapse()
            }
        }

        // ── State transitions ───────────────────────────────────────────────────

        /** ── Pass-40 (#3): re-stack this overlay's window ABOVE the modal ──
         *  picker sheet. Dialog windows order by add-time, and the Music
         *  sheet's window was added later — a card re-expanded while that
         *  sheet is up would render BEHIND it (visible to nobody, its taps
         *  landing on the sheet). A dismiss+show pair removes and re-adds
         *  this dialog's window at the TOP of the stack while the Dialog's
         *  PhoneWindow — and with it the whole card view tree and every
         *  listener bound here — is preserved verbatim: same views, same
         *  state, one frame of nothing in between. Guarded to run only for
         *  a card that is genuinely the visible representation (expanded):
         *  the restored-pill state has `shown` true with the window never
         *  up, and re-showing THAT would pop a floating card out of nowhere.
         *  Safe against a dead activity (both halves individually guarded). */
        fun bringAboveSheets() {
            if (!shown || !expandedNow) return
            try {
                dialog.dismiss()
                dialog.show()
            } catch (e: Exception) { /* window attach raced activity death */ }
        }

        /** The controller's modal-sheet-close hook: restart the inactivity
         *  clock from the outside (the sheet closing is an interaction
         *  boundary, not a card touch). */
        fun noteInteractionPublic() = noteInteraction()

        /** First show (a play()): the window comes up EXPANDED. */
        fun show() {
            try { dialog.show(); shown = true } catch (e: Exception) { /* activity gone */ }
            expand()
        }

        /** The pill's tap (or any re-open): back to the expanded card, with
         *  the track, position and playback state untouched — nothing about
         *  the player is involved in the transition. ── Pass-40 (#3): while
         *  a modal picker sheet is up, the re-expanded card also re-stacks
         *  its window above it (see bringAboveSheets) — the pill is the one
         *  player surface that stays reachable over the sheet, and the card
         *  it opens must be genuinely usable, not hidden behind it.
         *  ── GOAT-PASS 7 Part 2 (§7A): a window attach that FAILS here ──
         *  (the activity this overlay was built against is gone — the
         *  rotation/recreation edge) is no longer a silent nothing: the
         *  overlay rebuilds itself against the LIVE host activity in the
         *  same presentation, and if there is no live activity to rebind
         *  to, the pill is put back so the player keeps a reachable surface
         *  instead of vanishing outright. The pill must open the player on
         *  the FIRST click, every click. */
        fun expand() {
            if (!shown) { try { dialog.show(); shown = true } catch (e: Exception) { return } }
            if (expandedNow) {
                // ── GOAT-PASS 11 (§5 — the wedge repair): an expanded ───────────────────
                // state whose card is NOT genuinely visible (a window that
                // lost its show to a raced hide, an entrance animation
                // cancelled mid-fade at alpha≈0, an anchor computed against
                // geometry that has since changed) used to turn every
                // further pill tap into a silent no-op — the reported
                // "tapping the pill multiple times does not visibly open
                // the overlay". Every tap on an already-expanded player now
                // VERIFIES and REPAIRS: the window is re-shown, the card is
                // snapped to its settled fully-visible state, and the
                // clamped on-screen anchor is re-applied. Repeated taps
                // always end with a usable, visible card.
                repairExpandedVisibility()
                noteInteraction()
                return
            }
            expandedNow = true
            collapseJob?.cancel(); collapseJob = null
            unmountPill(animated = true)
            try {
                dialog.show()
            } catch (e: Exception) {
                var recovered = false
                try {
                    val live = hostActivity
                    if (live != null && !live.isFinishing) {
                        rebindOverlay(live)
                        recovered = overlay != null && overlay !== this
                    }
                } catch (e2: Exception) { recovered = false }
                if (!recovered) {
                    expandedNow = false
                    try { mountPill() } catch (e3: Exception) { /* dock gone with the activity */ }
                    try { refreshPill() } catch (e4: Exception) { /* no track loaded */ }
                }
                return
            }
            if (modalSheetOpen) bringAboveSheets()
            applyAnchor()
            // Entrance: from the pill's direction (above), settling into place.
            card.animate().cancel()
            card.alpha = 0f
            card.translationY = -activity.dpf(26f)
            card.scaleX = 0.96f; card.scaleY = 0.96f
            card.animate().alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(220).setInterpolator(Motion.DECELERATE).start()
            noteInteraction()
            refresh()
        }

        /** Inactivity (or the ✕): fold into the compact pill. The window is
         *  only HIDDEN — its views survive, so expanding again is instant
         *  and lossless. Playback is NOT touched. */
        fun collapse() {
            if (!expandedNow) return
            expandedNow = false
            collapseJob?.cancel(); collapseJob = null
            ticker?.cancel(); ticker = null
            card.animate().cancel()
            card.animate()
                .alpha(0f)
                .translationY(-activity.dpf(30f))
                .scaleX(0.94f).scaleY(0.94f)
                .setDuration(200).setInterpolator(Motion.STANDARD)
                .withEndAction {
                    // The guard keeps a collapse that was interrupted by an
                    // expand() from hiding the freshly re-shown window.
                    if (!expandedNow) try { dialog.hide() } catch (e: Exception) { /* already gone */ }
                }
                .start()
            mountPill()
            refreshPill()
        }

        /** Full teardown (MusicPlayback.stop): window dismissed, pill
         *  unmounted, every timer cancelled. */
        fun hide() {
            collapseJob?.cancel(); collapseJob = null
            ticker?.cancel(); ticker = null
            expandedNow = false
            unmountPill(animated = false)
            // Pass-39 (#9): the dock's fit listener dies with the pill.
            pillHostListener?.let { pillHost?.removeOnLayoutChangeListener(it) }
            pillHostListener = null; pillHost = null
            try { if (shown) dialog.dismiss() } catch (e: Exception) { /* already gone */ }
            shown = false
        }

        /** The EXPANDED state's live flag, for the controller's rotation
         * rebind (it re-creates this overlay bound to the new activity and
         * must put the replacement back into the same presentation). */
        val isExpanded: Boolean get() = expandedNow

        /** ── Pass-38 (#6): the restored session's landing presentation ──
         * (maybeRestore). Expanded: the card comes up exactly like a play()
         * would show it. Compact: the pill docks straight away — no
         * expand-then-collapse flash on app open. Playback is NEVER touched:
         * the restored session is paused until the user plays it. */
        fun restoreState(expanded: Boolean) {
            try {
                if (expanded) {
                    dialog.show(); shown = true
                    expand()
                } else {
                    shown = true
                    expandedNow = false
                    mountPill()
                    refreshPill()
                }
            } catch (e: Exception) { /* window attach raced activity death */ }
        }

        /** The compact dock changed (SendFragment attached/detached its
         *  view): a collapsed player with a fresh dock mounts its pill; a
         *  dock that went away takes the pill with it. */
        fun onHostChanged() {
            if (!expandedNow && currentTrack() != null && compactHost != null) {
                mountPill()
                refreshPill()
            } else if (compactHost == null) {
                unmountPill(animated = false)
            }
        }

        // ── The compact pill ────────────────────────────────────────────────────

        private fun buildPill(): LinearLayout {
            fun col(id: Int) = ContextCompat.getColor(activity, id)
            val p = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(activity.dp(9), 0, activity.dp(11), 0)
                isClickable = true; isFocusable = true
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = activity.dpf(13f)
                    setColor(0xF2171A20.toInt())
                    setStroke(activity.dp(1), (col(R.color.accent) and 0x00FFFFFF) or 0x4D000000)
                }
                // #6: a consistent, carefully designed size — fixed 152×26dp,
                // at or under the Server Card title's own line height, so the
                // card's geometry never changes to host it.
                layoutParams = FrameLayout.LayoutParams(activity.dp(152), activity.dp(26), Gravity.CENTER)
            }
            val eq = EqBarsView(activity, barColor = col(R.color.accent_light)).apply {
                layoutParams = LinearLayout.LayoutParams(activity.dp(14), activity.dp(15)).also { it.marginEnd = activity.dp(7) }
            }
            val t = TextView(activity).apply {
                textSize = 10.5f; setTextColor(col(R.color.text_secondary)); maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            p.addView(eq); p.addView(t)
            p.setOnClickListener { expand() }
            pillEq = eq
            pillTitle = t
            return p
        }

        private fun mountPill() {
            val host = compactHost ?: return
            if (pill?.parent === host) return
            unmountPill(animated = false)
            val p = try { buildPill() } catch (e: Exception) { return }
            pill = p
            try {
                host.addView(p)
                host.visibility = View.VISIBLE
                // ── Pass-39 (#9): the pill fits the space its dock actually ──
                // has, never the other way around. The designed width is a
                // 152dp CAP; a narrower tab header (the pill dock is the
                // title row's flexible remainder) gets a proportionally
                // narrower pill with a truncated label — never a wrapped
                // second-line title, never a taller header/card. The dock's
                // layout listener keeps it fitted as the slot resizes (tab
                // switch, rotation, split-screen), one listener per host.
                fitPillToHost(host)
                if (pillHost !== host) {
                    pillHostListener?.let { pillHost?.removeOnLayoutChangeListener(it) }
                    pillHost = host
                    val l = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        if (pill?.parent === host) fitPillToHost(host)
                    }
                    pillHostListener = l
                    host.addOnLayoutChangeListener(l)
                }
                p.alpha = 0f; p.scaleX = 0.88f; p.scaleY = 0.88f
                p.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(160).setInterpolator(Motion.DECELERATE).start()
            } catch (e: Exception) { pill = null }
        }

        private var pillHost: ViewGroup? = null
        private var pillHostListener: View.OnLayoutChangeListener? = null

        /** The pill's width: min(designed 152dp, the dock's current width).
         *  Inside a dock wider than that, the pill hugs the dock's RIGHT edge
         *  (the tab headers' pill position) instead of floating centered. */
        private fun fitPillToHost(host: ViewGroup) {
            val p = pill ?: return
            val lp = p.layoutParams as? FrameLayout.LayoutParams ?: return
            val cap = activity.dp(152)
            val w = if (host.width > 0) minOf(cap, host.width) else cap
            val wantGravity = if (host.width > 0 && w < host.width) Gravity.END or Gravity.CENTER_VERTICAL else Gravity.CENTER
            if (lp.width != w || lp.gravity != wantGravity) {
                lp.width = w
                lp.gravity = wantGravity
                p.layoutParams = lp
            }
        }

        private fun unmountPill(animated: Boolean) {
            val p = pill ?: return
            pill = null; pillTitle = null; pillEq = null
            // ── Pass-38 (#7): the slot that empties is the one the pill is ──
            // actually IN (its parent), NOT `compactHost` — with a pill dock
            // on every tab, the host reference already points at the NEW
            // tab's slot while the pill being unmounted still sits in the
            // OLD one; hiding the new slot (and leaving the old one an
            // empty visible hole) was the multi-tab trap. One detach routine
            // serves both the immediate and the animated path, and a slot
            // with children left (it never has more than the one pill, but
            // the check is cheap) keeps its visibility.
            fun detach() {
                val parent = p.parent as? ViewGroup
                parent?.removeView(p)
                if (parent != null && parent.childCount == 0) parent.visibility = View.GONE
            }
            if (animated && p.isAttachedToWindow) {
                p.animate().alpha(0f).scaleX(0.88f).scaleY(0.88f)
                    .setDuration(120).setInterpolator(Motion.STANDARD)
                    .withEndAction { detach() }
                    .start()
            } else detach()
        }

        // ── Repaint — one state, two representations ────────────────────────────

        /** Repaints whichever representations are up, from the controller's
         *  real condition — every state change lands here, so neither the
         *  card nor the pill can ever show a stale track or icon. */
        fun refresh() {
            if (!shown) return
            val t = currentTrack() ?: run { hide(); return }
            if (expandedNow) {
                fun col(id: Int) = ContextCompat.getColor(activity, id)
                title.text = t.title
                artist.text = t.artist ?: "Unknown artist"
                playBtn.setImageDrawable(Widgets.icon(activity,
                    if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                    col(R.color.accent_light)))
                // Artwork: the shared cache + the same one-decode-per-source rule.
                art.tag = t.artKey
                bindAlbumArt(activity, scope, art, t.artKey, t.uri, t.albumId, activity.dp(42), col(R.color.text_muted))
                tickPosition(t)
                if (ticker?.isActive != true) {
                    ticker = scope.launch {
                        while (shown && expandedNow) {
                            val track = currentTrack() ?: break
                            tickPosition(track)
                            // ── GOAT-PASS 11 (§5): the anchor is re-asserted ─────────────────
                            // on every tick while the card is up — the
                            // position was previously computed ONCE at
                            // expand() time, so any later geometry change
                            // (the page or a container scrolling, a
                            // rotation, a sheet transition) could strand
                            // the card outside the visible viewport with
                            // nothing ever correcting it. applyAnchor is
                            // clamped to the screen by construction and a
                            // no-op when the anchor has not moved, so an
                            // unchanged layout pays one integer compare.
                            try { applyAnchor() } catch (e: Exception) { /* keep the last anchor */ }
                            delay(500)
                        }
                    }
                }
            }
            refreshPill()
        }

        private fun refreshPill() {
            val t = currentTrack() ?: run { unmountPill(animated = false); return }
            val p = pill ?: return
            pillTitle?.text = if (t.artist.isNullOrBlank()) t.title else "${t.title} · ${t.artist}"
            pillEq?.setPlaying(isPlaying)
        }

        /** ── GOAT-PASS 7 Part 2 (§7A): the mounted pill's live on-screen ──
         *  bounds (left, top, right, bottom), or null when no pill is up.
         *  The sheet backdrops use this to hand a tap that lands on the
         *  pill's strip to the player instead of dismissing the sheet. */
        fun pillScreenBounds(): IntArray? {
            val p = pill ?: return null
            if (!p.isAttachedToWindow || p.visibility != View.VISIBLE || p.width <= 0 || p.height <= 0) return null
            return try {
                val loc = IntArray(2)
                p.getLocationOnScreen(loc)
                intArrayOf(loc[0], loc[1], loc[0] + p.width, loc[1] + p.height)
            } catch (e: Exception) { null }
        }

        private fun tickPosition(t: Track) {
            val total = if (t.durationMs > 0) t.durationMs else positionMs()
            var posMs = positionMs()
            // ── Pass-37 (#2): the pending-seek convergence pass — while a ─────
            // committed slide-seek is still landing asynchronously, the display
            // shows the REQUESTED target; the pin clears the moment the
            // player's own position reaches it (or after a bounded number of
            // ticks if a broken stream never converges — honest, never stuck).
            // ── Pass-38: a pin that is persistently FAR from the player's real ──
            // position (≥2s of ticks and still >3s away) is not a slow seek —
            // it is a seek that failed or was superseded (e.g. a hold-to-seek
            // started right after a slide). Drop the pin then, so the display
            // follows what the player is actually doing instead of arguing
            // with it for the full 12-tick window.
            if (pendingSeekMs >= 0) {
                val pending = pendingSeekMs
                val converged = kotlin.math.abs(posMs - pending) <= 250L
                pendingSeekTicks++
                val hopeless = pendingSeekTicks >= 4 && kotlin.math.abs(posMs - pending) > 3000L
                if (converged || hopeless || pendingSeekTicks > 12) {
                    pendingSeekMs = -1L
                    if (converged) posMs = pending.coerceAtLeast(0L)
                } else {
                    posMs = pending.coerceAtLeast(0L)
                }
            }
            // The Pass-36-Part-1 position line, verbatim: elapsed/total while
            // playing, the total alone while paused.
            position.text = if (isPlaying) "${formatMs(posMs)} / ${formatMs(total)}" else formatMs(total)
            // The background fill follows the real (or pinned) position — and
            // NEVER fights an active drag: the finger's fraction is already on
            // the drawable and owns it until ACTION_UP.
            if (dragFraction == null) {
                progressBg.setFraction(if (total > 0) posMs.toFloat() / total else 0f, animated = true)
            }
        }
    }
}

// ═══ Pass-36 Part 2 (#6): the pill's animated equalizer ══════════════════════
// Three rounded bars breathing on a loop while music plays — the compact
// "something is playing" signal. Paused: the bars settle into a quiet static
// shape. Self-contained: one ValueAnimator invalidated per frame on a 14×15dp
// view (a few hundred pixels — cheaper than any bitmap), cancelled the moment
// the view detaches, so a recycled Server Card can never leak an animator.
private class EqBarsView(context: Context, barColor: Int) : View(context) {

    private var playing = false
    private var animator: ValueAnimator? = null
    private var phase = 0f
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = barColor }
    private val pausedLevels = floatArrayOf(0.34f, 0.58f, 0.34f)

    fun setPlaying(p: Boolean) {
        if (playing == p) return
        playing = p
        if (p) startAnim() else stopAnim()
    }

    private fun startAnim() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1100
            repeatCount = ValueAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { a ->
                phase = a.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnim() {
        animator?.cancel()
        animator = null
        phase = 0f
        invalidate()
    }

    override fun onDetachedFromWindow() {
        stopAnim()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val slot = w / 3f
        val barW = slot * 0.5f
        val r = barW / 2f
        for (i in 0..2) {
            val level = if (playing)
                0.26f + 0.64f * kotlin.math.abs(kotlin.math.sin((phase * 2.0 * Math.PI + i * 1.15).toFloat()))
            else pausedLevels[i]
            val bh = (h * level).coerceAtLeast(barW)
            val left = i * slot + (slot - barW) / 2f
            val top = (h - bh) / 2f
            canvas.drawRoundRect(left, top, left + barW, top + bh, r, r, paint)
        }
    }
}

/** ── Pass-37 (#2): the position line's formatter, shared by the ticker ──────
 *  and the live slide preview ("m:ss", hours only when a track actually
 *  needs them). */
internal fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val s = ms / 1000
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

// ═══ Pass-37 (#2): the card's background IS the progress bar ════════════════
// A subtle accent fill grows across the player's own rounded container as the
// track plays — no separate row, no extra height, text/artwork/controls draw
// above it as ordinary content over an ordinary background. The fill is
// clipped to the container's rounded shape (clipPath + clipRect at the
// playhead), so the progress follows the card's geometry at any width; the
// hairline stroke paints on top so the card's edge stays crisp. setFraction
// animates short steps for a smooth ride during playback; a drag paints
// immediately (animated = false) so the fill never lags the finger.
private class ProgressCardDrawable(
    private val baseColor: Int,
    private val strokeColor: Int,
    private val fillColor: Int,
    private val strokePx: Float,
    private val radiusPx: Float,
) : android.graphics.drawable.Drawable() {

    private var fraction = 0f
    private var anim: ValueAnimator? = null
    private val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val shapePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val clipPath = android.graphics.Path()
    private val boundsRect = android.graphics.RectF()

    /** Sets the fill fraction. Animated steps ride a short 180ms tween (the
     *  500ms position ticker then reads as continuous motion); immediate
     *  paints are for finger-driven updates. */
    fun setFraction(f: Float, animated: Boolean) {
        val target = f.coerceIn(0f, 1f)
        if (!animated) {
            anim?.cancel()
            fraction = target
            invalidateSelf()
            return
        }
        if (kotlin.math.abs(target - fraction) < 0.0005f) return
        anim?.cancel()
        if (java.lang.Math.abs(target - fraction) > 0.12f) {
            // A track change or a big jump — land in one step, no sweep
            // across the whole card from a foreign position.
            fraction = target
            invalidateSelf()
            return
        }
        anim = ValueAnimator.ofFloat(fraction, target).apply {
            duration = 180
            interpolator = Motion.DECELERATE
            addUpdateListener { a ->
                fraction = a.animatedValue as Float
                invalidateSelf()
            }
            start()
        }
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        if (w <= 0f || h <= 0f) return
        val half = strokePx / 2f
        boundsRect.set(half, half, w - half, h - half)
        // 1 — the base surface (the card's normal background).
        shapePaint.style = android.graphics.Paint.Style.FILL
        shapePaint.color = baseColor
        canvas.drawRoundRect(boundsRect, radiusPx, radiusPx, shapePaint)
        // 2 — the progress fill, clipped to the card's rounded shape with a
        //     straight playhead edge.
        val f = fraction.coerceIn(0f, 1f)
        if (f > 0.002f) {
            clipPath.reset()
            clipPath.addRoundRect(boundsRect, radiusPx, radiusPx, android.graphics.Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clipPath)
            canvas.clipRect(0f, 0f, w * f, h)
            fillPaint.color = fillColor
            canvas.drawRect(0f, 0f, w * f, h, fillPaint)
            canvas.restore()
        }
        // 3 — the hairline stroke, on top of everything.
        shapePaint.style = android.graphics.Paint.Style.STROKE
        shapePaint.strokeWidth = strokePx
        shapePaint.color = strokeColor
        canvas.drawRoundRect(boundsRect, radiusPx, radiusPx, shapePaint)
    }

    override fun setAlpha(alpha: Int) {
        fillPaint.alpha = alpha
        shapePaint.alpha = alpha
        invalidateSelf()
    }

    @Suppress("DEPRECATION")
    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        fillPaint.colorFilter = colorFilter
        shapePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = -1
    override fun getIntrinsicHeight(): Int = -1
}
