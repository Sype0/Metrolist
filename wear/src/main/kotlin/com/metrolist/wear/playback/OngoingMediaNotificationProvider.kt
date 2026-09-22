/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.common.collect.ImmutableList
import com.metrolist.wear.R

/**
 * Media notification that also carries an [OngoingActivity], which is what surfaces the watch
 * player on the watch face, in the recents/app launcher chip and in One UI Watch's Now Bar.
 */
@UnstableApi
class OngoingMediaNotificationProvider(
    private val context: Context,
) : MediaNotification.Provider {
    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        ensureChannel(context)
        val player = mediaSession.player
        val metadata = player.mediaMetadata
        val title = metadata.title ?: context.getString(R.string.app_name)
        val artist = metadata.artist

        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.music_note)
                .setContentTitle(title)
                .setContentText(artist)
                .setContentIntent(mediaSession.sessionActivity)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOnlyAlertOnce(true)
                .setOngoing(player.playWhenReady)
                .setStyle(MediaStyleNotificationHelper.MediaStyle(mediaSession).setShowActionsInCompactView(0, 1, 2))
                .addAction(
                    actionFactory.createMediaAction(
                        mediaSession,
                        IconCompat.createWithResource(context, R.drawable.skip_previous),
                        context.getString(R.string.previous),
                        Player.COMMAND_SEEK_TO_PREVIOUS,
                    ),
                ).addAction(
                    if (player.playWhenReady) {
                        actionFactory.createMediaAction(
                            mediaSession,
                            IconCompat.createWithResource(context, R.drawable.pause),
                            context.getString(R.string.pause),
                            Player.COMMAND_PLAY_PAUSE,
                        )
                    } else {
                        actionFactory.createMediaAction(
                            mediaSession,
                            IconCompat.createWithResource(context, R.drawable.play),
                            context.getString(R.string.play),
                            Player.COMMAND_PLAY_PAUSE,
                        )
                    },
                ).addAction(
                    actionFactory.createMediaAction(
                        mediaSession,
                        IconCompat.createWithResource(context, R.drawable.skip_next),
                        context.getString(R.string.next),
                        Player.COMMAND_SEEK_TO_NEXT,
                    ),
                )

        mediaSession.sessionActivity?.let { touchIntent ->
            OngoingActivity
                .Builder(context, NOTIFICATION_ID, builder)
                .setStaticIcon(R.drawable.music_note)
                .setAnimatedIcon(R.drawable.music_note)
                .setTouchIntent(touchIntent)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setTitle(title.toString())
                .setStatus(ongoingStatus(title, artist))
                .build()
                .apply(context)
        }

        return MediaNotification(NOTIFICATION_ID, builder.build())
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ) = false

    override fun getNotificationChannelInfo() =
        MediaNotification.Provider.NotificationChannelInfo(CHANNEL_ID, context.getString(R.string.channel_playback))

    companion object {
        const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "playback"

        fun ongoingStatus(
            title: CharSequence,
            artist: CharSequence?,
        ): Status =
            Status
                .Builder()
                .addTemplate(if (artist.isNullOrBlank()) "#title#" else "#title# • #artist#")
                .addPart("title", Status.TextPart(title.toString()))
                .addPart("artist", Status.TextPart(artist?.toString().orEmpty()))
                .build()

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
