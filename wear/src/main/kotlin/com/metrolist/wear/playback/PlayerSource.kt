/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.playback

import kotlinx.coroutines.flow.StateFlow

/** UI-facing snapshot shared by the watch's own player and the phone remote. */
data class PlayerState(
    val active: Boolean = false,
    val mediaId: String? = null,
    val title: String? = null,
    val artist: String? = null,
    /** A URL for the watch player, a [android.graphics.Bitmap] for the phone. */
    val artwork: Any? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val positionSampledAt: Long = 0,
    val durationMs: Long = 0,
    val liked: Boolean = false,
    val canLike: Boolean = true,
    val shuffle: Boolean = false,
    val repeatMode: Int = 0,
    val hasPrevious: Boolean = false,
    val hasNext: Boolean = false,
    val volume: Int = 0,
    val maxVolume: Int = 15,
    /** Why playback isn't happening, shown on the player screen. */
    val error: String? = null,
) {
    fun positionAt(now: Long): Long {
        if (!isPlaying || positionSampledAt == 0L) return positionMs
        val position = positionMs + (now - positionSampledAt).coerceAtLeast(0)
        return if (durationMs > 0) position.coerceAtMost(durationMs) else position
    }

    val progress: Float
        get() = if (durationMs > 0) (positionAt(System.currentTimeMillis()).toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

data class QueueEntry(
    val index: Int,
    val title: String,
    val artist: String,
    val artworkUrl: String?,
    val isCurrent: Boolean,
)

/** Everything the player, queue and volume screens can do, regardless of where audio plays. */
interface PlayerSource {
    val state: StateFlow<PlayerState>

    fun playPause()

    fun next()

    fun previous()

    fun toggleLike()

    fun toggleShuffle()

    fun toggleRepeat()

    fun adjustVolume(steps: Int)

    suspend fun queue(): List<QueueEntry>

    fun skipTo(index: Int)
}
