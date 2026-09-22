/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.media.AudioManager
import android.os.IBinder
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.music.constants.AccountEmailKey
import com.metrolist.music.constants.AccountNameKey
import com.metrolist.music.constants.DataSyncIdKey
import com.metrolist.music.constants.InnerTubeCookieKey
import com.metrolist.music.constants.MediaSessionConstants
import com.metrolist.music.constants.VisitorDataKey
import com.metrolist.music.playback.MusicService
import com.metrolist.music.playback.queues.YouTubeQueue
import com.metrolist.music.utils.dataStore
import com.metrolist.wear.protocol.AccountSync
import com.metrolist.wear.protocol.Command
import com.metrolist.wear.protocol.LyricsResponse
import com.metrolist.wear.protocol.Queue
import com.metrolist.wear.protocol.QueueItem
import com.metrolist.wear.protocol.WearProtocol
import com.metrolist.wear.protocol.decodeCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.asTask
import timber.log.Timber
import kotlin.coroutines.resume

/** Receives remote-control commands and RPCs from Metrolist for Wear OS. */
@UnstableApi
class WearCommandService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearProtocol.PATH_COMMAND) return
        val command = runCatching { decodeCommand(event.data) }.getOrNull() ?: return
        val context = applicationContext
        scope.launch { handle(context, command) }
    }

    override fun onRequest(
        nodeId: String,
        path: String,
        request: ByteArray,
    ): Task<ByteArray>? {
        val context = applicationContext
        return when (path) {
            WearProtocol.PATH_QUEUE -> scope.async { queue(context) }.asTask()
            WearProtocol.PATH_ACCOUNT -> scope.async { account(context) }.asTask()
            WearProtocol.PATH_LYRICS -> scope.async { lyrics(request.decodeToString()) }.asTask()
            else -> null
        }
    }

    companion object {
        private const val CONTROLLER_IDLE_MS = 30_000L
        private const val MAX_QUEUE_ITEMS = 100

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        private val controllerMutex = Mutex()
        private var controller: MediaController? = null
        private var releaseJob: Job? = null

        private suspend fun handle(
            context: Context,
            command: Command,
        ) {
            when (command) {
                Command.RequestState -> {
                    WearSync.active?.publishSoon(0) ?: WearSync.publishInactive(context)
                    return
                }

                is Command.AdjustVolume -> {
                    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                    val direction = if (command.steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                    repeat(kotlin.math.abs(command.steps)) {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
                    }
                    WearSync.active?.publishSoon()
                    return
                }

                else -> Unit
            }

            val controller = runCatching { controller(context) }.getOrElse {
                Timber.w(it, "Wear OS command could not reach MusicService")
                return
            }
            when (command) {
                Command.Play -> {
                    if (controller.playbackState == androidx.media3.common.Player.STATE_IDLE) controller.prepare()
                    controller.play()
                }
                Command.Pause -> controller.pause()
                Command.Next -> controller.seekToNext()
                Command.Previous -> controller.seekToPrevious()
                is Command.SeekTo -> controller.seekTo(command.positionMs)
                is Command.SkipToQueueItem ->
                    if (command.index in 0 until controller.mediaItemCount) {
                        controller.seekTo(command.index, 0)
                        controller.play()
                    }
                Command.ToggleLike -> controller.sendCustomCommand(MediaSessionConstants.CommandToggleLike, android.os.Bundle.EMPTY)
                Command.ToggleShuffle -> controller.sendCustomCommand(MediaSessionConstants.CommandToggleShuffle, android.os.Bundle.EMPTY)
                Command.ToggleRepeat -> controller.sendCustomCommand(MediaSessionConstants.CommandToggleRepeatMode, android.os.Bundle.EMPTY)
                is Command.PlaySong -> {
                    val service = bindMusicService(context) ?: return
                    service.playQueue(YouTubeQueue(WatchEndpoint(videoId = command.videoId, playlistId = command.playlistId)))
                }
                else -> Unit
            }
        }

        private suspend fun queue(context: Context): ByteArray {
            val controller = controller(context)
            val current = controller.currentMediaItemIndex
            val start = (current - 10).coerceAtLeast(0)
            val end = (start + MAX_QUEUE_ITEMS).coerceAtMost(controller.mediaItemCount)
            val items =
                (start until end).map { index ->
                    val item = controller.getMediaItemAt(index)
                    QueueItem(
                        mediaId = item.mediaId,
                        title = item.mediaMetadata.title?.toString().orEmpty(),
                        artist = item.mediaMetadata.artist?.toString().orEmpty(),
                        artworkUrl = item.mediaMetadata.artworkUri?.toString(),
                    )
                }
            val queue = Queue(items = items, offset = start, currentIndex = current)
            return WearProtocol.json.encodeToString(Queue.serializer(), queue).encodeToByteArray()
        }

        private suspend fun account(context: Context): ByteArray {
            val prefs = context.dataStore.data.first()
            val account =
                AccountSync(
                    cookie = prefs[InnerTubeCookieKey],
                    visitorData = prefs[VisitorDataKey],
                    dataSyncId = prefs[DataSyncIdKey],
                    accountName = prefs[AccountNameKey],
                    accountEmail = prefs[AccountEmailKey],
                )
            return WearProtocol.json.encodeToString(AccountSync.serializer(), account).encodeToByteArray()
        }

        private suspend fun lyrics(mediaId: String): ByteArray {
            val lyrics = runCatching { WearSync.active?.lyrics(mediaId) }.getOrNull()
            return WearProtocol.json
                .encodeToString(LyricsResponse.serializer(), LyricsResponse(mediaId, lyrics))
                .encodeToByteArray()
        }

        private suspend fun controller(context: Context): MediaController =
            controllerMutex.withLock {
                releaseJob?.cancel()
                val existing = controller?.takeIf { it.isConnected }
                val connected =
                    existing ?: MediaController
                        .Builder(context, SessionToken(context, ComponentName(context, MusicService::class.java)))
                        .buildAsync()
                        .await()
                        .also { controller = it }
                // Keeping the controller bound would keep MusicService alive, so drop it when idle.
                releaseJob =
                    scope.launch {
                        delay(CONTROLLER_IDLE_MS)
                        controllerMutex.withLock {
                            controller?.release()
                            controller = null
                        }
                    }
                connected
            }

        private suspend fun bindMusicService(context: Context): MusicService? =
            suspendCancellableCoroutine { continuation ->
                val connection =
                    object : ServiceConnection {
                        override fun onServiceConnected(
                            name: ComponentName?,
                            binder: IBinder?,
                        ) {
                            val service = (binder as? MusicService.MusicBinder)?.service
                            context.unbindService(this)
                            if (continuation.isActive) continuation.resume(service)
                        }

                        override fun onServiceDisconnected(name: ComponentName?) = Unit
                    }
                val bound = context.bindService(Intent(context, MusicService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!bound && continuation.isActive) continuation.resume(null)
            }
    }
}
