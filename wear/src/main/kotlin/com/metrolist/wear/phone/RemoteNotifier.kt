/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.phone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.wear.ongoing.OngoingActivity
import com.metrolist.wear.MainActivity
import com.metrolist.wear.R
import com.metrolist.wear.WearApp
import com.metrolist.wear.playback.OngoingMediaNotificationProvider
import com.metrolist.wear.playback.PlaybackService
import com.metrolist.wear.playback.PlayerState

/**
 * While the phone plays, keeps an ongoing activity on the watch (watch face chip, recents and
 * One UI Watch Now Bar) that opens the remote, the same way Spotify's Wear OS app does.
 */
object RemoteNotifier {
    private const val NOTIFICATION_ID = 2001
    private const val CHANNEL_ID = "phone_remote"

    fun update(
        context: Context,
        state: PlayerState,
    ) {
        val show =
            state.active &&
                (state.isPlaying || state.isBuffering) &&
                !PlaybackService.isActive.value &&
                WearApp.from(context).prefs.phoneOngoingActivity
        if (!show) {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
            return
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        ensureChannel(context)
        val openRemote =
            PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PHONE_REMOTE),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val title = state.title ?: context.getString(R.string.phone_playing)
        val builder =
            NotificationCompat
                .Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.smartphone)
                .setContentTitle(title)
                .setContentText(state.artist)
                .setContentIntent(openRemote)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .addAction(R.drawable.skip_previous, context.getString(R.string.previous), action(context, RemoteActionReceiver.ACTION_PREVIOUS))
                .addAction(R.drawable.pause, context.getString(R.string.pause), action(context, RemoteActionReceiver.ACTION_PLAY_PAUSE))
                .addAction(R.drawable.skip_next, context.getString(R.string.next), action(context, RemoteActionReceiver.ACTION_NEXT))

        OngoingActivity
            .Builder(context, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.smartphone)
            .setTouchIntent(openRemote)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setTitle(title)
            .setStatus(OngoingMediaNotificationProvider.ongoingStatus(title, state.artist))
            .build()
            .apply(context)

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, builder.build())
    }

    private fun action(
        context: Context,
        action: String,
    ) = PendingIntent.getBroadcast(
        context,
        action.hashCode(),
        Intent(context, RemoteActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_phone_remote), NotificationManager.IMPORTANCE_LOW),
        )
    }
}
