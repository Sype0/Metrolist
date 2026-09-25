/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.Text
import com.metrolist.wear.R
import com.metrolist.wear.playback.Song
import com.metrolist.wear.playback.WatchQueue
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Tap plays; long-press downloads the song, or removes its download. */
@UnstableApi
@Composable
fun DownloadableSongButton(
    song: Song,
    onClick: () -> Unit,
) {
    val downloads by rememberApp().offline.downloads.collectAsState()
    val toggle = rememberDownloadToggle()
    SongButton(
        song = song,
        onClick = onClick,
        onLongClick = { toggle(song) },
        downloaded = downloads[song.id]?.state == Download.STATE_COMPLETED,
    )
}

/** Toggles a song's download and says what happened, since the button itself barely changes. */
@UnstableApi
@Composable
fun rememberDownloadToggle(): (Song) -> Unit {
    val context = LocalContext.current
    val offline = rememberApp().offline
    return remember(offline) {
        { song ->
            val downloading = offline.toggleDownload(song)
            Toast.makeText(context, if (downloading) R.string.download_started else R.string.download_removed, Toast.LENGTH_SHORT).show()
        }
    }
}

@UnstableApi
@Composable
fun DownloadAllButton(songs: List<Song>) {
    val offline = rememberApp().offline
    val downloads by offline.downloads.collectAsState()
    val done = songs.count { downloads[it.id]?.state == Download.STATE_COMPLETED }
    val context = LocalContext.current
    FilledTonalButton(
        onClick = {
            offline.download(songs)
            Toast.makeText(context, R.string.download_started, Toast.LENGTH_SHORT).show()
        },
        enabled = done < songs.size,
        modifier = Modifier.fillMaxWidth(),
        icon = { Icon(painterResource(if (done == songs.size) R.drawable.download_done else R.drawable.download), contentDescription = null) },
        secondaryLabel = { Text(stringResource(R.string.downloaded_count, done, songs.size), maxLines = 1) },
        label = { Text(stringResource(R.string.download_all), maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

/** Downloaded songs, plus the ones still downloading; all of it plays without a connection. */
@UnstableApi
@Composable
fun DownloadsScreen(actions: BrowseActions) {
    val offline = rememberApp().offline
    val downloads by offline.downloads.collectAsState()
    val songs = remember(downloads) { offline.downloadedSongs() }
    // Progress isn't pushed as it changes, so poll it while something is downloading.
    val pending by produceState(offline.pendingDownloads(), downloads) {
        while (true) {
            value = offline.pendingDownloads()
            if (value.isEmpty()) break
            delay(1_000)
        }
    }
    val toggle = rememberDownloadToggle()

    ListScreen(
        title = stringResource(R.string.downloads),
        edgeButton =
            if (songs.isNotEmpty()) {
                {
                    EdgeButton(onClick = { actions.playLocal(WatchQueue.Fixed(songs.shuffled(), 0)) }, buttonSize = EdgeButtonSize.Medium) {
                        Icon(painterResource(R.drawable.shuffle), contentDescription = stringResource(R.string.shuffle))
                    }
                }
            } else {
                null
            },
    ) {
        if (songs.isNotEmpty()) item { CenteredText(stringResource(R.string.used_size, formatSize(offline.downloadsUsedBytes))) }
        items(pending, key = { "pending:" + it.request.id }) { download ->
            val song = with(offline) { download.song() } ?: return@items
            SongButton(
                song = song,
                onClick = { toggle(song) },
                secondary = downloadStatus(download),
            )
        }
        items(songs.size, key = { songs[it].id }) { index ->
            val song = songs[index]
            SongButton(
                song = song,
                onClick = { actions.playLocal(WatchQueue.Fixed(songs, index)) },
                onLongClick = { toggle(song) },
                downloaded = true,
            )
        }
        if (songs.isEmpty() && pending.isEmpty()) item { CenteredText(stringResource(R.string.downloads_empty)) }
    }
}

@Composable
private fun downloadStatus(download: Download): String =
    when (download.state) {
        Download.STATE_QUEUED, Download.STATE_RESTARTING -> stringResource(R.string.download_queued)
        Download.STATE_FAILED, Download.STATE_STOPPED -> stringResource(R.string.download_failed)
        Download.STATE_REMOVING -> stringResource(R.string.download_removed)
        else ->
            download.percentDownloaded
                .takeIf { it >= 0 }
                ?.let { stringResource(R.string.downloading_percent, it.roundToInt()) }
                ?: stringResource(R.string.downloading)
    }

/** Songs fully kept in the player cache; they play offline until the cache makes room for newer ones. */
@UnstableApi
@Composable
fun CachedSongsScreen(actions: BrowseActions) {
    val offline = rememberApp().offline
    val downloads by offline.downloads.collectAsState()
    val (load, retry) = rememberLoad(downloads.size) { offline.cachedSongs() }
    val songs = (load as? Load.Done)?.value.orEmpty()
    ListScreen(
        title = stringResource(R.string.cached_songs),
        edgeButton =
            if (songs.isNotEmpty()) {
                {
                    EdgeButton(onClick = { actions.playLocal(WatchQueue.Fixed(songs.shuffled(), 0)) }, buttonSize = EdgeButtonSize.Medium) {
                        Icon(painterResource(R.drawable.shuffle), contentDescription = stringResource(R.string.shuffle))
                    }
                }
            } else {
                null
            },
    ) {
        item {
            CenteredText(
                stringResource(R.string.cache_usage, formatSize(offline.cacheUsedBytes), formatSize(rememberApp().prefs.maxCacheMb * 1024L * 1024)),
            )
        }
        loadStateItems(load, retry) { songItems(it, actions, asList = true) }
    }
}
