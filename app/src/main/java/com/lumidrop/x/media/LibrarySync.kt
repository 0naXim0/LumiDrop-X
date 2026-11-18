package com.lumidrop.x.media

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import com.lumidrop.x.settings.SettingsManager
import com.lumidrop.x.ui.invalidateFilesSheetCacheFor
import com.lumidrop.x.ui.invalidateNativeSearchIndex
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ── GOAT-PASS 9 (§5 — live file & media synchronization) ─────────────────────
 *
 * THE GAP THIS CLOSES: nothing in the app ever watched the filesystem or
 * MediaStore. Files created by OTHER apps (camera, downloads, file managers)
 * or received through the app's own server appeared in the Files/Music/Media
 * sheets only after a restart — the Files sheet's process-level listing cache
 * had no TTL and no external invalidation, the Music sheet's 30 s audio cache
 * was only cleared by its own file operations, and an OPEN sheet never
 * refreshed at all. Web clients were already live (the SSE filesVersion push);
 * the NATIVE side was the stale half.
 *
 * THE DESIGN (bounded, event-driven — never a background rescan loop):
 *  • MediaStore ContentObservers on Images/Video/Audio. System-pushed, zero
 *    polling, catch every media change on the volume regardless of which app
 *    made it (camera shots, screenshots, downloads, MediaScanner results for
 *    files THIS app just received).
 *  • ONE FileObserver on the configured receive root — the single directory
 *    the app itself cares about most (received files land there, including
 *    non-media ones the MediaStore observers cannot see). Non-recursive by
 *    FileObserver design; deeper receive traffic goes through the app's own
 *    server path, which already invalidates via notifyAppStorageChanged.
 *  • A trailing debounce per stream (media ~1.2 s, files ~0.8 s) so bursts
 *    (camera volleys, multi-file receives) collapse into one dispatch.
 *  • Dispatch = native fan-out (Files-sheet listing cache, the native search
 *    index, the Music sheet's audio cache) + a web-side filesVersion bump
 *    (open browser views refetch themselves over SSE) + a notification to
 *    any OPEN sheet's reload hook, posted on the main thread.
 *
 * The FilesSheetCache ALSO validates a directory's mtime on every cached
 * serve (see FilesPickerSheet) — a cheap stat() that makes even unobserved
 * external changes (any directory, any app) correct on the next open. The
 * observers are what make ALREADY-OPEN sheets and the web pages update
 * without any user action at all.
 *
 * Battery/discipline: no wake locks, no polling timers, no full-storage
 * rescans. Both observer kinds are kernel/provider-pushed; total steady-state
 * cost is zero until something actually changes.
 */
object LibrarySync {

    /** Trailing debounce for MediaStore change bursts (camera bursts, batch
     *  scans). The first observed change schedules the dispatch; everything
     *  arriving inside the window rides along. */
    private const val MEDIA_DEBOUNCE_MS = 1_200L
    /** Shorter window for receive-root filesystem events — a landed receive
     *  should surface quickly, and one push never produces a long tail. */
    private const val FILES_DEBOUNCE_MS = 800L

    /** An OPEN sheet registers one hook; it is unregistered on dismissal.
     *  [media] = a MediaStore-visible collection changed; [files] = the
     *  receive root (or an app-driven path) changed. Hooks run on MAIN. */
    fun interface Listener {
        fun onLibraryChanged(media: Boolean, files: Boolean)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }

    private var appContext: Context? = null
    @Volatile private var started = false

    // Observer threads: ContentObserver callbacks arrive on the handler's
    // thread; FileObserver callbacks arrive on its own special thread. All
    // debouncing happens on the single bg handler; listener dispatch is
    // posted to main.
    private val handlerThread = HandlerThread("wsx-libsync").apply { isDaemon = true; start() }
    private val bgHandler = Handler(handlerThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var imagesObserver: ContentObserver? = null
    private var videoObserver: ContentObserver? = null
    private var audioObserver: ContentObserver? = null
    private var rootObserver: FileObserver? = null
    private var watchedRootPath: String? = null

    @Volatile private var mediaDispatchScheduled = false
    @Volatile private var filesDispatchScheduled = false

    /** Idempotent. Registers the MediaStore observers and arms the
     *  receive-root FileObserver. Called from the foreground service's
     *  start path (the app's own always-on component). */
    @Synchronized
    fun start(context: Context) {
        if (started) { rearmReceiveRootObserverIfNeeded(); return }
        val ctx = context.applicationContext
        appContext = ctx
        started = true
        val cr = ctx.contentResolver
        imagesObserver = registerMediaObserver(cr, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        videoObserver = registerMediaObserver(cr, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        audioObserver = registerMediaObserver(cr, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        armReceiveRootObserver()
    }

    private fun registerMediaObserver(cr: android.content.ContentResolver, uri: Uri): ContentObserver {
        val obs = object : ContentObserver(bgHandler) {
            override fun onChange(selfChange: Boolean) { onMediaObserved() }
            override fun onChange(selfChange: Boolean, uri: Uri?) { onMediaObserved() }
        }
        try { cr.registerContentObserver(uri, true, obs) } catch (e: Exception) {
        }
        return obs
    }

    /** Arms (or re-arms) the receive-root FileObserver against the CURRENT
     *  configured receive destination. The root can change at runtime (a
     *  settings change, an SD-card destination) — every dispatch re-checks
     *  and re-arms lazily, so no settings coupling is needed. */
    @Synchronized
    private fun armReceiveRootObserver() {
        val ctx = appContext ?: return
        val rootPath = try { SettingsManager.getUploadPath(ctx) } catch (e: Exception) { return }
        if (rootPath.isBlank()) return
        if (rootPath == watchedRootPath && rootObserver != null) return
        try { rootObserver?.stopWatching() } catch (_: Exception) {}
        watchedRootPath = rootPath
        val dir = File(rootPath)
        try { if (!dir.exists()) dir.mkdirs() } catch (_: Exception) {}
        // FileObserver watches a single directory non-recursively — exactly
        // the receive root's top level, where new arrivals (and new received
        // subfolders) land. CREATE covers new children, MOVED_TO/ FROM renames
        // and moves in/out, DELETE removals, CLOSE_WRITE in-place rewrites.
        rootObserver = object : FileObserver(rootPath,
            FileObserver.CREATE or FileObserver.DELETE or
            FileObserver.MOVED_TO or FileObserver.MOVED_FROM or
            FileObserver.CLOSE_WRITE) {
            override fun onEvent(event: Int, path: String?) {
                // The observer's own inotify events also fire for THIS app's
                // receive path — the app-driven notifyAppStorageChanged fan-out
                // already handled those; the debounced dispatch here is at
                // worst a cheap duplicate, at best the only notification for
                // an EXTERNAL writer dropping files into the receive folder.
                onReceiveRootObserved()
            }
        }.also { it.startWatching() }
    }

    @Synchronized
    private fun rearmReceiveRootObserverIfNeeded() { armReceiveRootObserver() }

    /** Called from the foreground service's teardown. */
    @Synchronized
    fun stop() {
        if (!started) return
        started = false
        val ctx = appContext
        if (ctx != null) {
            imagesObserver?.let { try { ctx.contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
            videoObserver?.let { try { ctx.contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
            audioObserver?.let { try { ctx.contentResolver.unregisterContentObserver(it) } catch (_: Exception) {} }
        }
        try { rootObserver?.stopWatching() } catch (_: Exception) {}
        rootObserver = null
        watchedRootPath = null
        imagesObserver = null; videoObserver = null; audioObserver = null
    }

    // ── Observer entry points (debounced) ─────────────────────────────────────

    internal fun onMediaObserved() {
        if (mediaDispatchScheduled) return
        mediaDispatchScheduled = true
        bgHandler.postDelayed({
            mediaDispatchScheduled = false
            dispatchChange(media = true, files = false)
        }, MEDIA_DEBOUNCE_MS)
    }

    internal fun onReceiveRootObserved() {
        if (filesDispatchScheduled) return
        filesDispatchScheduled = true
        bgHandler.postDelayed({
            filesDispatchScheduled = false
            dispatchChange(media = false, files = true)
        }, FILES_DEBOUNCE_MS)
    }

    /** The single fan-out both observer streams (and the app-driven path
     *  below) converge on. Runs on the libsync handler thread. */
    private fun dispatchChange(media: Boolean, files: Boolean) {
        if (!started) return
        // The receive root may have been reconfigured since the last arm.
        try { rearmReceiveRootObserverIfNeeded() } catch (_: Exception) {}
        val ctx = appContext ?: return
        if (media) {
            try { com.lumidrop.x.ui.clearAudioLibraryCache() } catch (_: Exception) {}
            // A new media file is also a new SEARCHABLE file — the native
            // index and the web-side index both re-warm in the background.
            try { invalidateNativeSearchIndex() } catch (_: Exception) {}
        }
        if (files) {
            val root = watchedRootPath
            if (root != null) {
                try { invalidateFilesSheetCacheFor(File(root)) } catch (_: Exception) {}
            }
            try { invalidateNativeSearchIndex() } catch (_: Exception) {}
        }
        // Web half: open browser views refetch their data over SSE.
        try {
            com.lumidrop.x.service.ServerForegroundService.instance?.server
                ?.notifyExternalLibraryChanged()
        } catch (_: Exception) {}
        notifyListeners(media, files)
    }

    /** GOAT-PASS 9 (§5): the APP-DRIVEN change entry point. The server calls
     *  this (through bumpFilesVersion) whenever its own operations land a
     *  file (web upload, native push receive, web delete/rename) — closing
     *  the old gap where a received file refreshed the WEB views but left
     *  every NATIVE cache (Files listing, audio window, native search) stale
     *  until a restart. [changedPath] scopes the Files-sheet cache
     *  invalidation; null falls back to the whole receive root. The web-side
     *  filesVersion bump is the CALLER's job (it just did it). */
    fun notifyAppStorageChanged(context: Context, changedPath: String?) {
        val path = changedPath?.takeIf { it.isNotBlank() }
        try {
            invalidateFilesSheetCacheFor(path?.let { File(it) })
        } catch (_: Exception) {}
        try { invalidateNativeSearchIndex() } catch (_: Exception) {}
        // Received audio must show up in the Music sheet without waiting out
        // the 30 s window; the MediaScanner will eventually fire the audio
        // observer too, but scanner latency is exactly what made received
        // music invisible for minutes.
        try { com.lumidrop.x.ui.clearAudioLibraryCache() } catch (_: Exception) {}
        notifyListeners(media = true, files = true)
    }

    private fun notifyListeners(media: Boolean, files: Boolean) {
        if (listeners.isEmpty()) return
        mainHandler.post {
            for (l in listeners) {
                try { l.onLibraryChanged(media, files) } catch (_: Exception) {}
            }
        }
    }
}
