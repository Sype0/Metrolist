/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.metrolist.wear.WearApp

/** Handles the buttons of the phone-remote ongoing notification. */
class RemoteActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val phone = WearApp.from(context).phone
        when (intent.action) {
            ACTION_PREVIOUS -> phone.previous()
            ACTION_PLAY_PAUSE -> phone.playPause()
            ACTION_NEXT -> phone.next()
        }
    }

    companion object {
        const val ACTION_PREVIOUS = "com.metrolist.wear.remote.PREVIOUS"
        const val ACTION_PLAY_PAUSE = "com.metrolist.wear.remote.PLAY_PAUSE"
        const val ACTION_NEXT = "com.metrolist.wear.remote.NEXT"
    }
}
