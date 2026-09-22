/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.metrolist.music.db.entities.LyricsEntity
import com.metrolist.music.playback.MusicService
import com.metrolist.music.ui.utils.resize
import com.metrolist.wear.protocol.NowPlaying
import com.metrolist.wear.protocol.WearProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.ByteArrayOutputStream

/**
 * Mirrors [MusicService]'s playback state to paired watches running Metrolist for Wear OS.
 * Nothing is sent unless a watch advertising [WearProtocol.CAPABILITY_WATCH] is reachable.
 */
@UnstableApi
@OptIn(ExperimentalCoroutinesApi::class)
class WearSync(
    private val service: MusicService,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val dataClient = Wearable.getDataClient(service)
    private val capabilityClient = Wearable.getCapabilityClient(service)
    private val audioManager = service.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private var player: Player? = null
    private var liked = false
    private var hasWatch = false
    private var publishJob: Job? = null
    private var artworkKey: String? = null
    private var artworkBytes: ByteArray? = null

    private val playerListener =
        object : Player.Listener {
            override fun onEvents(
                player: Player,
                events: Player.Events,
            ) = publishSoon()
        }

    private val capabilityListener =
        CapabilityClient.OnCapabilityChangedListener { info: CapabilityInfo ->
            val reachable = info.nodes.isNotEmpty()
            if (reachable && !hasWatch) {
                hasWatch = true
                publishSoon()
            } else {
                hasWatch = reachable
            }
        }

    fun start() {
        active = this
        scope.launch {
            hasWatch =
                runCatching {
                    capabilityClient
                        .getCapability(WearProtocol.CAPABILITY_WATCH, CapabilityClient.FILTER_REACHABLE)
                        .await()
                        .nodes
                        .isNotEmpty()
                }.getOrElse {
                    // Wearable API is missing on devices without Play services or a paired watch.
                    Timber.d(it, "Wear OS capability lookup failed")
                    false
                }
            runCatching { capabilityClient.addListener(capabilityListener, WearProtocol.CAPABILITY_WATCH) }
            publishSoon()
        }
        scope.launch {
            service.playerFlow.collect { newPlayer ->
                player?.removeListener(playerListener)
                player = newPlayer
                newPlayer?.addListener(playerListener)
                publishSoon()
            }
        }
        scope.launch {
            service.currentMediaMetadata
                .flatMapLatest { metadata -> service.database.song(metadata?.id) }
                .map { it?.song?.liked == true }
                .distinctUntilChanged()
                .collect {
                    liked = it
                    publishSoon()
                }
        }
    }

    fun release() {
        if (active === this) active = null
        player?.removeListener(playerListener)
        runCatching { capabilityClient.removeListener(capabilityListener) }
        if (hasWatch) publishInactive(service)
        scope.cancel()
    }

    fun publishSoon(delayMs: Long = PUBLISH_DEBOUNCE_MS) {
        if (!hasWatch) return
        publishJob?.cancel()
        publishJob =
            scope.launch {
                delay(delayMs)
                publish()
            }
    }

    /** Lyrics the phone app already has (cached in its database) or fetches with the user's provider order. */
    suspend fun lyrics(mediaId: String): String? {
        service.database
            .lyrics(mediaId)
            .first()
            ?.lyrics
            ?.takeIf { it != LyricsEntity.LYRICS_NOT_FOUND }
            ?.let { return it }
        val metadata = service.currentMediaMetadata.value?.takeIf { it.id == mediaId } ?: return null
        return service.lyricsHelper
            .getLyrics(metadata)
            .lyrics
            .takeIf { it != LyricsEntity.LYRICS_NOT_FOUND }
    }

    private suspend fun publish() {
        val player = player ?: return
        val item = player.currentMediaItem
        val metadata = service.currentMediaMetadata.value?.takeIf { it.id == item?.mediaId }
        val state =
            NowPlaying(
                active = item != null,
                mediaId = item?.mediaId,
                title = metadata?.title ?: item?.mediaMetadata?.title?.toString(),
                artist =
                    metadata?.artists?.joinToString { it.name }
                        ?: item?.mediaMetadata?.artist?.toString(),
                album = metadata?.album?.title ?: item?.mediaMetadata?.albumTitle?.toString(),
                durationMs = player.duration.takeIf { it > 0 } ?: ((metadata?.duration ?: 0) * 1000L),
                positionMs = player.currentPosition.coerceAtLeast(0),
                positionSampledAt = System.currentTimeMillis(),
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                liked = liked,
                shuffle = player.shuffleModeEnabled,
                repeatMode =
                    when (player.repeatMode) {
                        Player.REPEAT_MODE_ONE -> WearProtocol.REPEAT_ONE
                        Player.REPEAT_MODE_ALL -> WearProtocol.REPEAT_ALL
                        else -> WearProtocol.REPEAT_OFF
                    },
                hasPrevious = player.hasPreviousMediaItem() || player.currentPosition > 3_000,
                hasNext = player.hasNextMediaItem(),
                volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                artworkKey = item?.mediaId,
            )

        val artworkUrl = metadata?.thumbnailUrl ?: item?.mediaMetadata?.artworkUri?.toString()
        if (state.artworkKey != artworkKey) {
            artworkKey = state.artworkKey
            artworkBytes = artworkUrl?.let { loadArtwork(it) }
        }

        val request =
            PutDataMapRequest.create(WearProtocol.PATH_NOW_PLAYING).apply {
                dataMap.putString(WearProtocol.KEY_STATE, WearProtocol.json.encodeToString(NowPlaying.serializer(), state))
                artworkBytes?.let { dataMap.putAsset(WearProtocol.KEY_ARTWORK, Asset.createFromBytes(it)) }
            }
        runCatching {
            dataClient.putDataItem(request.asPutDataRequest().setUrgent()).await()
        }.onFailure { Timber.d(it, "Failed to publish Wear OS state") }
    }

    private suspend fun loadArtwork(url: String): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val result =
                    SingletonImageLoader.get(service).execute(
                        ImageRequest
                            .Builder(service)
                            .data(url.resize(ARTWORK_SIZE, ARTWORK_SIZE))
                            .size(ARTWORK_SIZE)
                            .allowHardware(false)
                            .build(),
                    )
                val bitmap = (result as? SuccessResult)?.image?.toBitmap() ?: return@runCatching null
                ByteArrayOutputStream().use { stream ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                    stream.toByteArray()
                }
            }.getOrNull()
        }

    companion object {
        private const val PUBLISH_DEBOUNCE_MS = 250L
        private const val ARTWORK_SIZE = 320

        /** The sync of the running [MusicService], if any. */
        @Volatile
        var active: WearSync? = null
            private set

        fun publishInactive(context: Context) {
            val request =
                PutDataMapRequest.create(WearProtocol.PATH_NOW_PLAYING).apply {
                    dataMap.putString(
                        WearProtocol.KEY_STATE,
                        WearProtocol.json.encodeToString(NowPlaying.serializer(), NowPlaying(active = false)),
                    )
                    dataMap.putLong("t", System.currentTimeMillis())
                }
            runCatching { Wearable.getDataClient(context).putDataItem(request.asPutDataRequest().setUrgent()) }
        }
    }
}
