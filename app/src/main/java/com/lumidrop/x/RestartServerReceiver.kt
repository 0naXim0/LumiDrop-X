package com.lumidrop.x

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.lumidrop.x.service.ServerForegroundService
import com.lumidrop.x.settings.SettingsManager

/**
 * Receives [SettingsActivity.ACTION_RESTART_SERVER] and performs an
 * in-process server restart without any UI involvement.
 *
 * Why a BroadcastReceiver instead of a direct method call?  SettingsActivity
 * doesn't hold a reference to the running LumiDropServer (it's owned by
 * ServerForegroundService / MainActivity) — sending a broadcast that the
 * existing service infrastructure already knows how to handle is cleaner than
 * adding a cross-Activity coupling just for this.
 *
 * The restart sequence:
 *  1. Mark any in-flight transfers as failed (server is going away).
 *  2. Stop the current NanoHTTPD server instance.
 *  3. Re-read the freshly-committed port/password settings from SharedPrefs.
 *  4. Start a new NanoHTTPD server instance on the new settings.
 *  5. Update the foreground notification URL line.
 *
 * This is the same stop/start that the manual Stop+Start button does, just
 * driven by a broadcast so it can be triggered from any context without a
 * direct reference to MainActivity.
 */
class RestartServerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != SettingsManager.ACTION_RESTART_SERVER_ACTION) return
        val svc = ServerForegroundService.instance ?: return
        svc.restartServerForSettingsChange()
    }
}
