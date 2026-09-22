/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.playback

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.metrolist.innertube.YouTube
import com.metrolist.wear.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The watch's own player, reached through a MediaController on [PlaybackService]. */
@UnstableApi
class LocalPlayer(
    private val context: Context,
    private val scope: CoroutineScope,
) : PlayerSource {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mutex = Mutex()
    private var controller: MediaController? = null
    private val likedIds = HashSet<String>()

    private val _state = MutableStateFlow(PlayerState())
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val listener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) = refresh()
        }

    /** Connects without starting playback, so the UI reflects an already running session. */
    fun connect() {
        scope.launch { runCatching { controller() } }
    }

    fun disconnect() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }

    fun play(queue: WatchQueue) {
        scope.launch {
            val controller = controller()
            PlaybackService.pendingQueue = queue
            controller.sendCustomCommand(PlaybackService.COMMAND_PLAY_PENDING, Bundle.EMPTY)
        }
    }

    private suspend fun controller(): MediaController =
        mutex.withLock {
            controller?.takeIf { it.isConnected }?.let { return it }
            MediaController
                .Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java)))
                .buildAsync()
                .await()
                .also {
                    it.addListener(listener)
                    controller = it
                    refresh()
                }
        }

    private fun withController(block: (MediaController) -> Unit) {
        scope.launch { block(controller()) }
    }

    fun refresh() {
        val player = controller ?: return
        val item = player.currentMediaItem
        _state.value =
            PlayerState(
                active = item != null,
                mediaId = item?.mediaId,
                title = player.mediaMetadata.title?.toString(),
                artist = player.mediaMetadata.artist?.toString(),
                artwork = player.mediaMetadata.artworkUri?.toString(),
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                positionMs = player.currentPosition,
                positionSampledAt = System.currentTimeMillis(),
                durationMs = player.duration.coerceAtLeast(0),
                liked = item?.mediaId in likedIds,
                canLike = YouTube.cookie != null,
                shuffle = player.shuffleModeEnabled,
                repeatMode = player.repeatMode,
                hasPrevious = player.hasPreviousMediaItem() || player.currentPosition > 3_000,
                hasNext = player.hasNextMediaItem(),
                volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                error =
                    when {
                        player.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_UNSUITABLE_AUDIO_OUTPUT ->
                            context.getString(R.string.error_no_output)
                        player.playerError != null ->
                            player.playerError?.let { e -> listOfNotNull(e.errorCodeName, e.cause?.message ?: e.message).joinToString(": ") }
                        else -> null
                    },
            )
    }

    override fun playPause() =
        withController {
            if (it.isPlaying) {
                it.pause()
            } else {
                if (it.playbackState == Player.STATE_IDLE) it.prepare()
                it.play()
            }
        }

    override fun next() = withController { it.seekToNext() }

    override fun previous() = withController { it.seekToPrevious() }

    override fun toggleLike() {
        val id = _state.value.mediaId ?: return
        val like = id !in likedIds
        if (like) likedIds += id else likedIds -= id
        refresh()
        scope.launch {
            withContext(Dispatchers.IO) { YouTube.likeVideo(id, like) }.onFailure {
                if (like) likedIds -= id else likedIds += id
                refresh()
            }
        }
    }

    override fun toggleShuffle() = withController { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    override fun toggleRepeat() =
        withController {
            it.repeatMode =
                when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
        }

    override fun adjustVolume(steps: Int) {
        val direction = if (steps > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        repeat(kotlin.math.abs(steps)) {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        }
        refresh()
    }

    override suspend fun queue(): List<QueueEntry> {
        val player = controller()
        val current = player.currentMediaItemIndex
        return (0 until player.mediaItemCount).map { index ->
            val metadata = player.getMediaItemAt(index).mediaMetadata
            QueueEntry(
                index = index,
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                artworkUrl = metadata.artworkUri?.toString(),
                isCurrent = index == current,
            )
        }
    }

    override fun skipTo(index: Int) =
        withController {
            it.seekTo(index, 0)
            it.play()
        }
}
