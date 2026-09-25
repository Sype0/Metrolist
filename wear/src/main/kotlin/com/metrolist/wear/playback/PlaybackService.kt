/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.playback

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.wear.MainActivity
import com.metrolist.wear.WearApp
import com.metrolist.wear.WearPrefs
import com.metrolist.wear.offline.OfflineStore
import com.metrolist.wear.youtube.StreamResolver
import com.metrolist.wear.youtube.withStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

@UnstableApi
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var prefs: WearPrefs
    private lateinit var player: ExoPlayer
    private lateinit var offline: OfflineStore
    private var session: MediaSession? = null

    private var queue: WatchQueue? = null
    private var loadJob: Job? = null
    private var pageJob: Job? = null
    private var sleepJob: Job? = null
    private val retries = HashMap<String, Int>()

    override fun onCreate() {
        super.onCreate()
        prefs = WearApp.from(this).prefs

        offline = WearApp.from(this).offline
        // Downloads first (read-only), then the player cache, then the network.
        val upstream =
            CacheDataSource
                .Factory()
                .setCache(offline.downloadCache)
                .setCacheWriteDataSinkFactory(null)
                .setUpstreamDataSourceFactory(
                    CacheDataSource
                        .Factory()
                        .setCache(offline.playerCache)
                        .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(OkHttpClient())),
                )
        val resolving =
            ResolvingDataSource.Factory(upstream) { dataSpec ->
                val videoId = dataSpec.key ?: dataSpec.uri.toString()
                // Whatever the watch already holds plays without resolving a stream, so it works offline.
                if (offline.isCached(videoId, dataSpec.position, dataSpec.length)) return@Factory dataSpec
                val stream = runBlocking(Dispatchers.IO) { StreamResolver.resolve(videoId, prefs.highQualityAudio) }
                dataSpec.withStream(stream)
            }

        player =
            ExoPlayer
                .Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true,
                ).setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                // Wear OS convention: don't blast the watch speaker unless the user picked it.
                .setSuppressPlaybackOnUnsuitableOutput(!prefs.allowSpeaker)
                .build()
        player.repeatMode = prefs.repeatMode
        player.shuffleModeEnabled = prefs.shuffle
        player.skipSilenceEnabled = prefs.skipSilence
        player.addListener(playerListener)
        prefs.prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        val openApp =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_PLAYER),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        session =
            MediaSession
                .Builder(this, player)
                .setSessionActivity(openApp)
                .setCallback(sessionCallback)
                .build()
        // Wear OS derives the ongoing activity (watch face, Now Bar) from Media3's media notification.
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        sleepTimerState.value = null
        prefs.prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        scope.cancel()
        session?.release()
        session = null
        player.removeListener(playerListener)
        player.release()
        super.onDestroy()
    }

    private fun play(newQueue: WatchQueue) {
        queue = newQueue
        pageJob?.cancel()
        loadJob?.cancel()
        loadJob =
            scope.launch {
                val (songs, index) =
                    runCatching { newQueue.initial() }.getOrElse {
                        Timber.w(it, "Queue failed to load")
                        return@launch
                    }
                if (songs.isEmpty()) return@launch
                retries.clear()
                player.setMediaItems(songs.map { it.toMediaItem() }, index, 0)
                player.prepare()
                player.play()
            }
    }

    private fun loadMoreIfNeeded() {
        val queue = queue ?: return
        if (pageJob?.isActive == true || player.mediaItemCount == 0) return
        if (!queue.hasNextPage() && !canAutoplay()) return
        if (player.mediaItemCount - player.currentMediaItemIndex > PRELOAD_THRESHOLD) return
        pageJob =
            scope.launch {
                val known = (0 until player.mediaItemCount).mapTo(HashSet()) { player.getMediaItemAt(it).mediaId }
                val more = if (queue.hasNextPage()) queue.nextPage() else continueAsRadio() ?: return@launch
                player.addMediaItems(more.filterNot { it.id in known }.map { it.toMediaItem() })
            }
    }

    private fun canAutoplay() = prefs.autoplay && player.repeatMode == Player.REPEAT_MODE_OFF

    /** Autoplay: once the queue runs out, carry on with a radio seeded by its last song. */
    private suspend fun continueAsRadio(): List<Song>? {
        val seed = player.getMediaItemAt(player.mediaItemCount - 1).mediaId
        val radio = WatchQueue.Radio(WatchEndpoint(videoId = seed))
        val songs =
            try {
                radio.initial().first
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.w(e, "Autoplay radio failed")
                return null
            }
        queue = radio
        return songs
    }

    private fun setSleepTimer(minutes: Int) {
        sleepJob?.cancel()
        sleepJob = null
        player.pauseAtEndOfMediaItems = minutes == SLEEP_END_OF_SONG
        sleepTimerState.value =
            when {
                minutes == SLEEP_END_OF_SONG -> SleepTimer.EndOfSong
                minutes > 0 -> {
                    val durationMs = minutes * 60_000L
                    sleepJob =
                        scope.launch {
                            delay(durationMs)
                            player.pause()
                            sleepTimerState.value = null
                        }
                    SleepTimer.At(System.currentTimeMillis() + durationMs)
                }
                else -> null
            }
    }

    /** Settings write these prefs; the player follows, and writes back what the player UI changes. */
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                WearPrefs.KEY_REPEAT_MODE -> player.repeatMode = prefs.repeatMode
                WearPrefs.KEY_SHUFFLE -> player.shuffleModeEnabled = prefs.shuffle
                WearPrefs.KEY_SKIP_SILENCE -> player.skipSilenceEnabled = prefs.skipSilence
            }
        }

    private val playerListener =
        object : Player.Listener {
            override fun onMediaItemTransition(
                mediaItem: MediaItem?,
                reason: Int,
            ) {
                mediaItem?.let(offline::record)
                loadMoreIfNeeded()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                prefs.repeatMode = repeatMode
                loadMoreIfNeeded()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                prefs.shuffle = shuffleModeEnabled
            }

            override fun onPlayWhenReadyChanged(
                playWhenReady: Boolean,
                reason: Int,
            ) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepTimerState.value == SleepTimer.EndOfSong) {
                    setSleepTimer(0)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                val mediaId = player.currentMediaItem?.mediaId ?: return
                StreamResolver.invalidate(mediaId)
                val attempts = retries.merge(mediaId, 1, Int::plus) ?: 1
                val expired = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode == 403
                if (attempts <= MAX_RETRIES && (expired || error.errorCode in RETRYABLE_ERRORS)) {
                    player.prepare()
                    player.play()
                } else if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        }

    private val sessionCallback =
        object : MediaSession.Callback {
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
            ): MediaSession.ConnectionResult {
                val commands =
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS
                        .buildUpon()
                        .add(COMMAND_PLAY_PENDING)
                        .add(COMMAND_SLEEP_TIMER)
                        .build()
                return MediaSession.ConnectionResult
                    .AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(commands)
                    .build()
            }

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle,
            ): ListenableFuture<SessionResult> {
                when (customCommand.customAction) {
                    COMMAND_PLAY_PENDING.customAction -> {
                        pendingQueue?.let(::play)
                        pendingQueue = null
                    }
                    COMMAND_SLEEP_TIMER.customAction -> setSleepTimer(args.getInt(EXTRA_MINUTES))
                }
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }

            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>,
            ): ListenableFuture<MutableList<MediaItem>> =
                Futures.immediateFuture(mediaItems.mapTo(mutableListOf()) { it.restorePlayable() })
        }

    companion object {
        private const val PRELOAD_THRESHOLD = 3
        private const val MAX_RETRIES = 2
        private val RETRYABLE_ERRORS =
            setOf(
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            )

        val COMMAND_PLAY_PENDING = SessionCommand("com.metrolist.wear.PLAY_PENDING", Bundle.EMPTY)

        /** Args: [EXTRA_MINUTES]; 0 cancels, [SLEEP_END_OF_SONG] pauses when the current song ends. */
        val COMMAND_SLEEP_TIMER = SessionCommand("com.metrolist.wear.SLEEP_TIMER", Bundle.EMPTY)
        const val EXTRA_MINUTES = "minutes"
        const val SLEEP_END_OF_SONG = -1

        /** Handed over in-process by [LocalPlayer] right before it sends [COMMAND_PLAY_PENDING]. */
        @Volatile
        var pendingQueue: WatchQueue? = null

        private val sleepTimerState = MutableStateFlow<SleepTimer?>(null)

        /** The running sleep timer, if any; the service lives in the app's process, so the UI reads it directly. */
        val sleepTimer: StateFlow<SleepTimer?> = sleepTimerState.asStateFlow()
    }
}

sealed interface SleepTimer {
    data class At(val endsAtMs: Long) : SleepTimer

    data object EndOfSong : SleepTimer
}
