/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.offline

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.metrolist.wear.MainActivity
import com.metrolist.wear.R
import com.metrolist.wear.WearApp

/** Keeps downloads going with a progress notification while the app is in the background. */
@UnstableApi
class WearDownloadService :
    DownloadService(NOTIFICATION_ID, 1_000L, CHANNEL_ID, R.string.downloads, 0) {
    private val offline get() = WearApp.from(this).offline

    override fun getDownloadManager() = offline.downloadManager

    // No scheduler: downloads waiting for a network pick up again the next time the app runs.
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int,
    ): Notification {
        val message =
            if (downloads.size == 1) {
                with(offline) { downloads[0].song()?.title }
            } else {
                getString(R.string.downloading_n_songs, downloads.size)
            }
        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_DOWNLOADS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return offline.notificationHelper.buildProgressNotification(this, R.drawable.download, openApp, message, downloads, notMetRequirements)
    }

    companion object {
        const val CHANNEL_ID = "download"
        private const val NOTIFICATION_ID = 2
    }
}
