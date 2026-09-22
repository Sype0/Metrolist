/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wearable Data Layer contract shared by the phone app (gms flavor) and the Wear OS app.
 * Both apps must use the same application id and signing key, otherwise the Data Layer
 * silently drops every item and message.
 */
object WearProtocol {
    /** Declared in the watch app's `wear.xml`, used by the phone to find watches with Metrolist installed. */
    const val CAPABILITY_WATCH = "metrolist_wear_watch"

    /** Declared in the phone app's `wear.xml`, used by the watch to find the phone. */
    const val CAPABILITY_PHONE = "metrolist_wear_phone"

    /** Phone → watch data item holding [NowPlaying] as JSON and the artwork as an asset. */
    const val PATH_NOW_PLAYING = "/metrolist/now_playing"
    const val KEY_STATE = "state"
    const val KEY_ARTWORK = "artwork"

    /** Watch → phone fire-and-forget message carrying a [Command]. */
    const val PATH_COMMAND = "/metrolist/command"

    /** Watch → phone RPC returning [Queue]. */
    const val PATH_QUEUE = "/metrolist/queue"

    /** Watch → phone RPC returning [AccountSync]. */
    const val PATH_ACCOUNT = "/metrolist/account"

    val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    const val REPEAT_OFF = 0
    const val REPEAT_ONE = 1
    const val REPEAT_ALL = 2
}

@Serializable
data class NowPlaying(
    val active: Boolean = false,
    val mediaId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
    val positionMs: Long = 0,
    /** Wall-clock time [positionMs] was sampled at, so the watch can extrapolate progress. */
    val positionSampledAt: Long = 0,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val liked: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = WearProtocol.REPEAT_OFF,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val volume: Int = 0,
    val maxVolume: Int = 15,
    val artworkKey: String? = null,
) {
    fun positionAt(now: Long): Long {
        if (!isPlaying || positionSampledAt == 0L) return positionMs
        val position = positionMs + (now - positionSampledAt).coerceAtLeast(0)
        return if (durationMs > 0) position.coerceAtMost(durationMs) else position
    }
}

@Serializable
sealed interface Command {
    @Serializable
    data object Play : Command

    @Serializable
    data object Pause : Command

    @Serializable
    data object Next : Command

    @Serializable
    data object Previous : Command

    @Serializable
    data class SeekTo(val positionMs: Long) : Command

    @Serializable
    data object ToggleLike : Command

    @Serializable
    data object ToggleShuffle : Command

    @Serializable
    data object ToggleRepeat : Command

    /** Positive raises, negative lowers the phone's music stream volume by that many steps. */
    @Serializable
    data class AdjustVolume(val steps: Int) : Command

    @Serializable
    data class SkipToQueueItem(val index: Int) : Command

    /** Starts a song on the phone; the phone builds a radio queue from it. */
    @Serializable
    data class PlaySong(val videoId: String, val playlistId: String? = null) : Command

    @Serializable
    data object RequestState : Command
}

@Serializable
data class QueueItem(
    val mediaId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
)

@Serializable
data class Queue(
    val items: List<QueueItem> = emptyList(),
    /** Index of [items]' first element in the full queue; [Command.SkipToQueueItem] uses absolute indices. */
    val offset: Int = 0,
    /** Absolute index of the playing item. */
    val currentIndex: Int = -1,
)

@Serializable
data class AccountSync(
    val cookie: String? = null,
    val visitorData: String? = null,
    val dataSyncId: String? = null,
    val accountName: String? = null,
    val accountEmail: String? = null,
)

fun Command.encode(): ByteArray = WearProtocol.json.encodeToString(Command.serializer(), this).encodeToByteArray()

fun decodeCommand(bytes: ByteArray): Command = WearProtocol.json.decodeFromString(Command.serializer(), bytes.decodeToString())
