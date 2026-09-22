/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.playback

import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.metrolist.innertube.models.SongItem

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val thumbnail: String? = null,
)

fun SongItem.toSong() =
    Song(
        id = id,
        title = title,
        artist = artists.joinToString { it.name },
        album = album?.name,
        thumbnail = thumbnail,
    )

private val sizeSuffix = Regex("""=w\d+-h\d+.*$""")
private val ytimgSize = Regex("""/(?:hq|mq|sd|maxres)?default\.jpg""")

/** Asks YouTube's image CDNs for a watch-sized image instead of the full-resolution one. */
fun thumbnailUrl(
    url: String?,
    size: Int = 300,
): String? =
    when {
        url == null -> null
        url.contains("googleusercontent.com") || url.contains("ggpht.com") ->
            if (sizeSuffix.containsMatchIn(url)) url.replace(sizeSuffix, "=w$size-h$size-l90-rj") else "$url=w$size-h$size-l90-rj"
        url.contains("i.ytimg.com") -> url.replace(ytimgSize, "/hqdefault.jpg")
        else -> url
    }

fun Song.toMediaItem(): MediaItem =
    MediaItem
        .Builder()
        .setMediaId(id)
        .setUri(id)
        .setCustomCacheKey(id)
        .setMediaMetadata(
            MediaMetadata
                .Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setArtworkUri(thumbnailUrl(thumbnail)?.toUri())
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
                .build(),
        ).build()

/** Rebuilds a playable item from what survives a MediaController round trip (id + metadata). */
fun MediaItem.restorePlayable(): MediaItem =
    buildUpon()
        .setUri(mediaId)
        .setCustomCacheKey(mediaId)
        .build()
