/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.playback

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint

/** What the watch player should play. Mirrors Metrolist's queue types in a reduced form. */
sealed interface WatchQueue {
    suspend fun initial(): Pair<List<Song>, Int>

    fun hasNextPage(): Boolean = false

    suspend fun nextPage(): List<Song> = emptyList()

    class Fixed(
        private val songs: List<Song>,
        private val startIndex: Int,
    ) : WatchQueue {
        override suspend fun initial() = songs to startIndex.coerceIn(0, (songs.size - 1).coerceAtLeast(0))
    }

    /** Endless "radio" built from YouTube Music's watch-next feed, like Metrolist's YouTubeQueue. */
    class Radio(
        private var endpoint: WatchEndpoint,
    ) : WatchQueue {
        private var continuation: String? = null

        override suspend fun initial(): Pair<List<Song>, Int> {
            if (endpoint.videoId != null && endpoint.playlistId == null) {
                endpoint = WatchEndpoint(videoId = endpoint.videoId, playlistId = "RDAMVM${endpoint.videoId}")
            }
            val result =
                YouTube.next(endpoint).getOrElse {
                    // Some tracks have no RDAMVM mix; fall back to the plain watch-next feed.
                    endpoint = WatchEndpoint(videoId = endpoint.videoId)
                    YouTube.next(endpoint).getOrThrow()
                }
            endpoint = result.endpoint
            continuation = result.continuation
            return result.items.map { it.toSong() } to (result.currentIndex ?: 0)
        }

        override fun hasNextPage() = continuation != null

        override suspend fun nextPage(): List<Song> {
            val result = YouTube.next(endpoint, continuation).getOrElse {
                continuation = null
                return emptyList()
            }
            endpoint = result.endpoint
            continuation = result.continuation
            return result.items.map { it.toSong() }
        }
    }
}
