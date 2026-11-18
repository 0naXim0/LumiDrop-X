package com.lumidrop.x.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.lumidrop.x.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ══ Pass-38 (#8): the music playback notification ══════════════════════════
 *
 * A real media notification for the one playback engine ([MusicPlayback]):
 * MediaStyle with the session token (lock-screen metadata and media-button
 * routing come from the session), the track's title/artist/duration and its
 * album artwork (with an app-icon fallback), and prev / play-pause / next /
 * stop actions in that order — the first three in the compact view.
 *
 * One notification id, so every state change REPLACES the previous post —
 * duplicates are impossible. The actions land on dynamically-registered
 * broadcast receivers that call straight into the EXISTING controller
 * (MusicPlayback.prev/toggle/next/stop): one player, one state source, the
 * notification is just another view of it. Playback never starts from the
 * notification that wasn't already asked for elsewhere: every action is a
 * user press.
 *
 * Platform APIs only (android.media.session.MediaSession +
 * android.app.Notification.MediaStyle — both API 21+): no new dependency,
 * the full supported Android range covered, channels handled on 26+
 * (IMPORTANCE_LOW: present and persistent, never a heads-up shout), plain
 * low priority before that. POST_NOTIFICATIONS (33+) is respected — a
 * revoked permission simply means the post is skipped, never a crash, and
 * the session keeps the state truthful for whenever permission returns.
 *
 * Lifecycle: [sync] posts/updates while a track is loaded; [cancel] is the
 * one teardown (the player's explicit dismiss) — notification gone, session
 * released, receiver unregistered. A paused-but-alive session keeps the
 * notification with a play action and makes it swipeable (ongoing only
 * while actually playing) — standard media-player behavior.
 */
object MusicNotifier {

    private const val CHANNEL_ID = "music_playback"
    private const val NOTIF_ID = 0x4D55   // "MU"

    private const val ACTION_PREV = "com.lumidrop.x.music.PREV"
    private const val ACTION_TOGGLE = "com.lumidrop.x.music.TOGGLE"
    private const val ACTION_NEXT = "com.lumidrop.x.music.NEXT"
    private const val ACTION_STOP = "com.lumidrop.x.music.STOP"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var session: MediaSession? = null
    private var receiver: BroadcastReceiver? = null

    // The dedup guards: an unchanged (track, state, title, art) post is
    // skipped, so the manager only ever sees real transitions.
    private var lastKey: String? = null
    private var lastPlaying: Boolean? = null
    private var lastArtKey: String? = null
    private var art: Bitmap? = null
    private var artDecoding = false
    // ── Pass-42 (§7): the title rides the dedup key. enrichTrackMetadata
    // fills a queue-started track's real title/artist IN PLACE (same Track
    // object, same key, same play state) — the old (key, isPlaying)-only
    // guard skipped those posts entirely, so the notification kept showing
    // the filename-derived title forever while the overlay showed the
    // enriched one. A title change is a real metadata transition.
    private var lastTitle: String? = null

    /** Mirror the engine: post/update while [track] is loaded (and playback
     *  is live — a restored-but-never-played session is deliberately NOT
     *  notified), tear everything down when it is null. */
    fun sync(context: Context?, track: MusicPlayback.Track?, isPlaying: Boolean, positionMs: Long) {
        val ctx = context?.applicationContext ?: return
        if (track == null) { cancel(ctx); return }
        val key = track.key
        if (key == lastKey && isPlaying == lastPlaying && track.title == lastTitle) return   // a real transition only
        lastKey = key
        lastPlaying = isPlaying
        lastTitle = track.title
        ensureChannel(ctx)
        ensureReceiver(ctx)
        val ms = session(ctx)
        // The artwork: cache hit synchronously, else decoded once per track
        // (the shared MusicArtCache's own dedup) and re-posted when it lands.
        // ── Pass-42 (§7): a hit also UPGRADES a previously art-less post ──
        // for the SAME artKey — another surface's decode (the overlay's
        // bindAlbumArt, enrichTrackMetadata's retriever pass) may have
        // filled the cache after this notifier already posted without art;
        // the next state tick picks the bitmap up instead of showing the
        // fallback icon until the track changes.
        val cachedArt = MusicArtCache.cachedBitmap(track.artKey)
        if (cachedArt != null) {
            if (art == null || lastArtKey != track.artKey) { lastArtKey = track.artKey; art = cachedArt }
        } else if (lastArtKey != track.artKey) {
            lastArtKey = track.artKey
            art = null
            if (!artDecoding) {
                artDecoding = true
                scope.launch {
                    val bmp = try { decodeAlbumArt(ctx, track.uri, track.albumId, 320) } catch (e: Exception) { null }
                    artDecoding = false
                    if (bmp != null && lastArtKey == track.artKey) {
                        art = bmp
                        MusicArtCache.rememberBitmap(track.artKey, bmp)
                        post(ctx, track, isPlaying, positionMs, force = true)
                    }
                }
            }
        }
        post(ctx, track, isPlaying, positionMs, force = false)
    }

    /** The one teardown: notification cancelled, session released, receiver
     *  unregistered. Safe to call any number of times. */
    fun cancel(context: Context?) {
        val ctx = context?.applicationContext ?: run {
            // No context to cancel through — drop the state so the next
            // sync() starts clean instead of deduping against a ghost.
            lastKey = null; lastPlaying = null; lastArtKey = null; art = null
        lastTitle = null
            try { session?.release() } catch (e: Exception) { /* gone */ }
            session = null
            return
        }
        try {
            NotificationManagerCompat.from(ctx).cancel(NOTIF_ID)
        } catch (e: Exception) { /* gone */ }
        try { session?.release() } catch (e: Exception) { /* gone */ }
        session = null
        val r = receiver
        if (r != null) {
            try { ctx.unregisterReceiver(r) } catch (e: Exception) { /* already gone */ }
        }
        receiver = null
        lastKey = null; lastPlaying = null; lastArtKey = null; art = null
        lastTitle = null
    }

    // ---- internals ------------------------------------------------------------

    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try {
            nm.createNotificationChannel(NotificationChannel(
                CHANNEL_ID, "Music Playback", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shows the track playing in LumiDrop X with playback controls"
                setShowBadge(false); enableLights(false); enableVibration(false)
            })
        } catch (e: Exception) { /* never let channel setup break playback */ }
    }

    /** The action receivers, registered once on the application context and
     *  shared by every post. RECEIVER_NOT_EXPORTED on 33+ (via androidx) so
     *  nothing outside the app can press the buttons; the plain form before
     *  that — protected broadcast actions are not exported by default
     *  pre-33, and the actions carry no data. */
    private fun ensureReceiver(ctx: Context) {
        if (receiver != null) return
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    ACTION_PREV -> MusicPlayback.prev()
                    ACTION_TOGGLE -> MusicPlayback.toggle()
                    ACTION_NEXT -> MusicPlayback.next()
                    ACTION_STOP -> MusicPlayback.stop()
                }
            }
        }
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_PREV); addAction(ACTION_TOGGLE)
                addAction(ACTION_NEXT); addAction(ACTION_STOP)
            }
            ContextCompat.registerReceiver(ctx, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiver = r
        } catch (e: Exception) { receiver = null }
    }

    private fun session(ctx: Context): MediaSession {
        session?.let { return it }
        val s = MediaSession(ctx, "LumiDropXMusic")
        s.setCallback(object : MediaSession.Callback() {
            override fun onPlay() { MusicPlayback.toggle() }
            override fun onPause() { MusicPlayback.toggle() }
            override fun onSkipToNext() { MusicPlayback.next() }
            override fun onSkipToPrevious() { MusicPlayback.prev() }
            override fun onStop() { MusicPlayback.stop() }
        })
        s.isActive = true
        session = s
        return s
    }

    private fun post(ctx: Context, track: MusicPlayback.Track, isPlaying: Boolean, positionMs: Long, force: Boolean) {
        if (!force && track.key != lastKey) return   // the track moved on while an art decode flew
        try {
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
            val s = session(ctx)

            // The session's truth: metadata + playback state. This is what
            // lock-screen/Bluetooth surfaces read; the notification below is
            // the visual twin of the same state.
            val meta = android.media.MediaMetadata.Builder()
                .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, track.title)
                .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, track.artist ?: "Unknown artist")
                .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, track.durationMs)
            art?.let { meta.putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, it) }
            s.setMetadata(meta.build())
            s.setPlaybackState(PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE
                    or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_SKIP_TO_NEXT
                    or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_STOP)
                .setState(if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    positionMs, if (isPlaying) 1f else 0f)
                .build())

            val launch = try { ctx.packageManager.getLaunchIntentForPackage(ctx.packageName) } catch (e: Exception) { null }
            val contentPi = if (launch != null) try {
                PendingIntent.getActivity(ctx, 10, launch,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            } catch (e: Exception) { null } else null

            fun pi(action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
                ctx, code, Intent(action).setPackage(ctx.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            @Suppress("DEPRECATION")   // the platform Action.Builder(icon-Int) form spans the app's whole API range
            fun act(icon: Int, title: String, action: String, code: Int) =
                Notification.Action.Builder(icon, title, pi(action, code)).build()

            val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL_ID)
            else @Suppress("DEPRECATION") Notification.Builder(ctx)

            builder.setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(track.title)
                .setContentText(track.artist ?: "Unknown artist")
                .setContentIntent(contentPi)
                .setOngoing(isPlaying)          // playing: persistent; paused: swipeable
                .setOnlyAlertOnce(true)         // state flips never chime
                .setShowWhen(false)
                .setCategory(Notification.CATEGORY_TRANSPORT)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
            if (Build.VERSION.SDK_INT < 26) {
                @Suppress("DEPRECATION")   // the pre-channel priority route (API 21–25)
                builder.setPriority(Notification.PRIORITY_LOW)
            }
            art?.let { builder.setLargeIcon(it) } ?: run {
                // A suitable fallback so the row never looks broken: the app's
                // own icon, rendered to a bitmap once and reused.
                if (fallbackArt == null) fallbackArt = appIconBitmap(ctx)
                fallbackArt?.let { builder.setLargeIcon(it) }
            }
            builder.addAction(act(R.drawable.ic_skip_previous, "Previous", ACTION_PREV, 21))
            builder.addAction(act(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play,
                if (isPlaying) "Pause" else "Play", ACTION_TOGGLE, 22))
            builder.addAction(act(R.drawable.ic_skip_next, "Next", ACTION_NEXT, 23))
            builder.addAction(act(R.drawable.ic_close, "Stop", ACTION_STOP, 24))
            builder.setStyle(android.app.Notification.MediaStyle()
                .setMediaSession(s.sessionToken)
                .setShowActionsInCompactView(0, 1, 2))

            NotificationManagerCompat.from(ctx).notify(NOTIF_ID, builder.build())
        } catch (e: Exception) { /* a failed post must never break playback */ }
    }

    private var fallbackArt: Bitmap? = null

    private fun appIconBitmap(ctx: Context): Bitmap? {
        val d: Drawable = try {
            ctx.packageManager.getApplicationIcon(ctx.packageName)
        } catch (e: Exception) { return null }
        return try {
            if (d is BitmapDrawable) d.bitmap
            else {
                val size = (64 * ctx.resources.displayMetrics.density).toInt().coerceAtLeast(96)
                val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                d.setBounds(0, 0, size, size)
                d.draw(c)
                bmp
            }
        } catch (e: Exception) { null }
    }
}
