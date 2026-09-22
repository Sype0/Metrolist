/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.android.gms.wearable.Asset
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import com.metrolist.wear.playback.PlayerSource
import com.metrolist.wear.playback.PlayerState
import com.metrolist.wear.playback.QueueEntry
import com.metrolist.wear.protocol.AccountSync
import com.metrolist.wear.protocol.Command
import com.metrolist.wear.protocol.NowPlaying
import com.metrolist.wear.protocol.Queue
import com.metrolist.wear.protocol.WearProtocol
import com.metrolist.wear.protocol.encode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber

/** Remote control for Metrolist on the paired phone, over the Wearable Data Layer. */
class PhoneRepository(
    private val context: Context,
    private val scope: CoroutineScope,
) : PlayerSource {
    private val dataClient = Wearable.getDataClient(context)
    private val messageClient = Wearable.getMessageClient(context)
    private val capabilityClient = Wearable.getCapabilityClient(context)

    private val _state = MutableStateFlow(PlayerState(canLike = true))
    override val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _connected = MutableStateFlow(false)

    /** Whether a phone with the Metrolist (GMS build) app is currently reachable. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private var artworkKey: String? = null
    private var artwork: Bitmap? = null

    init {
        scope.launch {
            refreshConnection()
            runCatching {
                val items = dataClient.getDataItems(Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(WearProtocol.PATH_NOW_PLAYING).build()).await()
                items.forEach { onDataItem(it) }
                items.release()
            }
        }
    }

    suspend fun refreshConnection(): Boolean {
        val reachable = phoneNodes().isNotEmpty()
        _connected.value = reachable
        if (reachable) send(Command.RequestState)
        return reachable
    }

    private suspend fun phoneNodes(): Set<Node> =
        runCatching {
            capabilityClient
                .getCapability(WearProtocol.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE)
                .await()
                .nodes
        }.getOrElse {
            Timber.d(it, "Phone capability lookup failed")
            emptySet()
        }

    /** Called for every data item change, both from the listener service and the initial fetch. */
    suspend fun onDataItem(item: DataItem) {
        if (item.uri.path != WearProtocol.PATH_NOW_PLAYING) return
        val dataMap = DataMapItem.fromDataItem(item).dataMap
        val nowPlaying =
            dataMap.getString(WearProtocol.KEY_STATE)?.let {
                runCatching { WearProtocol.json.decodeFromString(NowPlaying.serializer(), it) }.getOrNull()
            } ?: return
        if (nowPlaying.artworkKey != artworkKey) {
            artworkKey = nowPlaying.artworkKey
            artwork = dataMap.getAsset(WearProtocol.KEY_ARTWORK)?.let { loadAsset(it) }
        }
        _connected.value = true
        _state.value = nowPlaying.toPlayerState(artwork)
        RemoteNotifier.update(context, _state.value)
    }

    private suspend fun loadAsset(asset: Asset): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                dataClient.getFdForAsset(asset).await().inputStream.use(BitmapFactory::decodeStream)
            }.getOrNull()
        }

    fun send(command: Command) {
        scope.launch {
            val nodes = phoneNodes()
            _connected.value = nodes.isNotEmpty()
            nodes.forEach { node ->
                runCatching { messageClient.sendMessage(node.id, WearProtocol.PATH_COMMAND, command.encode()).await() }
                    .onFailure { Timber.w(it, "Command to phone failed") }
            }
        }
    }

    private suspend fun request(path: String): ByteArray? {
        val node = phoneNodes().firstOrNull() ?: return null
        return runCatching { messageClient.sendRequest(node.id, path, ByteArray(0)).await() }
            .onFailure { Timber.w(it, "Request $path failed") }
            .getOrNull()
    }

    suspend fun fetchAccount(): AccountSync? =
        request(WearProtocol.PATH_ACCOUNT)?.let {
            runCatching { WearProtocol.json.decodeFromString(AccountSync.serializer(), it.decodeToString()) }.getOrNull()
        }

    override suspend fun queue(): List<QueueEntry> {
        val queue =
            request(WearProtocol.PATH_QUEUE)?.let {
                runCatching { WearProtocol.json.decodeFromString(Queue.serializer(), it.decodeToString()) }.getOrNull()
            } ?: return emptyList()
        return queue.items.mapIndexed { i, item ->
            val index = queue.offset + i
            QueueEntry(index, item.title, item.artist, item.artworkUrl, index == queue.currentIndex)
        }
    }

    private fun optimistic(update: (PlayerState) -> PlayerState) {
        _state.value = update(_state.value)
    }

    override fun playPause() {
        val playing = _state.value.isPlaying
        optimistic {
            it.copy(
                isPlaying = !playing,
                positionMs = it.positionAt(System.currentTimeMillis()),
                positionSampledAt = System.currentTimeMillis(),
            )
        }
        send(if (playing) Command.Pause else Command.Play)
    }

    override fun next() = send(Command.Next)

    override fun previous() = send(Command.Previous)

    override fun toggleLike() {
        optimistic { it.copy(liked = !it.liked) }
        send(Command.ToggleLike)
    }

    override fun toggleShuffle() {
        optimistic { it.copy(shuffle = !it.shuffle) }
        send(Command.ToggleShuffle)
    }

    override fun toggleRepeat() = send(Command.ToggleRepeat)

    override fun adjustVolume(steps: Int) {
        optimistic { it.copy(volume = (it.volume + steps).coerceIn(0, it.maxVolume)) }
        send(Command.AdjustVolume(steps))
    }

    override fun skipTo(index: Int) = send(Command.SkipToQueueItem(index))

    fun playOnPhone(videoId: String) = send(Command.PlaySong(videoId))

    private fun NowPlaying.toPlayerState(artwork: Bitmap?) =
        PlayerState(
            active = active,
            mediaId = mediaId,
            title = title,
            artist = artist,
            artwork = artwork,
            isPlaying = isPlaying,
            isBuffering = isBuffering,
            positionMs = positionMs,
            positionSampledAt = positionSampledAt,
            durationMs = durationMs,
            liked = liked,
            canLike = true,
            shuffle = shuffle,
            repeatMode = repeatMode,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            volume = volume,
            maxVolume = maxVolume,
        )
}
