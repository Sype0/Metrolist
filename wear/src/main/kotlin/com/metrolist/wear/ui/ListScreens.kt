/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.ui

import android.app.RemoteInput
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.LevelIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import androidx.wear.input.RemoteInputIntentHelper
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AlbumItem
import com.metrolist.innertube.models.ArtistItem
import com.metrolist.innertube.models.PlaylistItem
import com.metrolist.innertube.models.SongItem
import com.metrolist.innertube.models.WatchEndpoint
import com.metrolist.innertube.models.YTItem
import com.metrolist.innertube.utils.completed
import com.metrolist.wear.R
import com.metrolist.wear.playback.PlayerSource
import com.metrolist.wear.playback.Song
import com.metrolist.wear.playback.WatchQueue
import com.metrolist.wear.playback.toSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Callbacks every browse screen needs. */
class BrowseActions(
    val playLocal: (WatchQueue) -> Unit,
    val openPlaylist: (String) -> Unit,
    val openAlbum: (String) -> Unit,
)

@Composable
internal fun ListScreen(
    title: String,
    edgeButton: (@Composable BoxScope.() -> Unit)? = null,
    content: androidx.wear.compose.foundation.lazy.ScalingLazyListScope.() -> Unit,
) {
    val listState = rememberScalingLazyListState()
    if (edgeButton != null) {
        ScreenScaffold(scrollState = listState, edgeButton = edgeButton) { padding ->
            ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
                item { ListHeader { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                content()
            }
        }
    } else {
        ScreenScaffold(scrollState = listState) { padding ->
            ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
                item { ListHeader { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
                content()
            }
        }
    }
}

/** In a list or album the tapped song plays within that list; elsewhere it seeds a radio. */
internal fun androidx.wear.compose.foundation.lazy.ScalingLazyListScope.songItems(
    songs: List<Song>,
    actions: BrowseActions,
    asList: Boolean,
) {
    items(songs.size) { index ->
        val song = songs[index]
        DownloadableSongButton(
            song = song,
            onClick = {
                actions.playLocal(
                    if (asList) WatchQueue.Fixed(songs, index) else WatchQueue.Radio(WatchEndpoint(videoId = song.id)),
                )
            },
        )
    }
}

private fun androidx.wear.compose.foundation.lazy.ScalingLazyListScope.ytItems(
    items: List<YTItem>,
    actions: BrowseActions,
) {
    items(items) { item ->
        when (item) {
            is SongItem -> {
                val song = item.toSong()
                DownloadableSongButton(
                    song = song,
                    onClick = { actions.playLocal(WatchQueue.Radio(WatchEndpoint(videoId = song.id))) },
                )
            }
            is PlaylistItem ->
                FilledTonalButton(
                    onClick = { actions.openPlaylist(item.id) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Artwork(item.thumbnail) },
                    secondaryLabel = item.author?.name?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    label = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            is AlbumItem ->
                FilledTonalButton(
                    onClick = { actions.openAlbum(item.browseId) },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Artwork(item.thumbnail) },
                    secondaryLabel = item.artists?.let { { Text(it.joinToString { a -> a.name }, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    label = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            is ArtistItem ->
                FilledTonalButton(
                    onClick = { (item.radioEndpoint ?: item.shuffleEndpoint)?.let { actions.playLocal(WatchQueue.Radio(it)) } },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Artwork(item.thumbnail, circle = true) },
                    secondaryLabel = { Text(stringResource(R.string.radio)) },
                    label = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                )
            else -> Unit
        }
    }
}

@Composable
fun QuickPicksScreen(actions: BrowseActions) {
    val (load, retry) =
        rememberLoad(Unit) {
            withContext(Dispatchers.IO) {
                YouTube.home().getOrThrow().sections.filter { section -> section.items.isNotEmpty() }
            }
        }
    ListScreen(stringResource(R.string.quick_picks)) {
        loadStateItems(load, retry) { sections ->
            sections.take(MAX_HOME_SECTIONS).forEach { section ->
                item { ListHeader { Text(section.title, style = MaterialTheme.typography.titleSmall) } }
                ytItems(section.items.take(MAX_SECTION_ITEMS), actions)
            }
        }
    }
}

@Composable
fun SongListScreen(
    title: String,
    key: Any,
    actions: BrowseActions,
    loader: suspend () -> List<Song>,
) {
    val (load, retry) = rememberLoad(key) { withContext(Dispatchers.IO) { loader() } }
    val songs = (load as? Load.Done)?.value.orEmpty()
    ListScreen(
        title = title,
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
        loadStateItems(load, retry) {
            item { DownloadAllButton(it) }
            songItems(it, actions, asList = true)
        }
    }
}

fun likedSongsLoader(): suspend () -> List<Song> = { YouTube.playlist("LM").completed().getOrThrow().songs.map { it.toSong() } }

fun playlistLoader(id: String): suspend () -> List<Song> = {
    YouTube.playlist(id.removePrefix("VL")).completed().getOrThrow().songs.map { it.toSong() }
}

fun albumLoader(browseId: String): suspend () -> List<Song> = { YouTube.album(browseId).getOrThrow().songs.map { it.toSong() } }

@Composable
fun PlaylistsScreen(actions: BrowseActions) {
    val (load, retry) =
        rememberLoad(Unit) {
            withContext(Dispatchers.IO) {
                YouTube.library("FEmusic_liked_playlists").completed().getOrThrow().items.filterIsInstance<PlaylistItem>()
            }
        }
    ListScreen(stringResource(R.string.playlists)) {
        loadStateItems(load, retry) { ytItems(it, actions) }
    }
}

@Composable
fun SearchScreen(
    actions: BrowseActions,
    query: String?,
    onQuery: (String) -> Unit,
) {
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val text =
                result.data
                    ?.let { RemoteInput.getResultsFromIntent(it) }
                    ?.getCharSequence(SEARCH_INPUT_KEY)
                    ?.toString()
                    ?.trim()
            if (!text.isNullOrEmpty()) onQuery(text)
        }
    val hint = stringResource(R.string.search_hint)
    val openInput = {
        val intent: Intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
        RemoteInputIntentHelper.putRemoteInputsExtra(
            intent,
            listOf(RemoteInput.Builder(SEARCH_INPUT_KEY).setLabel(hint).build()),
        )
        launcher.launch(intent)
    }

    val (load, retry) =
        rememberLoad(query) {
            if (query == null) {
                emptyList()
            } else {
                withContext(Dispatchers.IO) {
                    YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow().items.filterIsInstance<SongItem>().map { it.toSong() }
                }
            }
        }

    ListScreen(
        title = query ?: stringResource(R.string.search),
        edgeButton = {
            EdgeButton(onClick = openInput, buttonSize = EdgeButtonSize.Medium) {
                Icon(painterResource(R.drawable.search), contentDescription = stringResource(R.string.search))
            }
        },
    ) {
        if (query == null) {
            item { CenteredText(hint) }
        } else {
            loadStateItems(load, retry) { songItems(it, actions, asList = false) }
        }
    }
}

@Composable
fun QueueScreen(source: PlayerSource) {
    val state by source.state.collectAsState()
    val (load, retry) = rememberLoad(state.mediaId) { source.queue() }
    ListScreen(stringResource(R.string.queue)) {
        loadStateItems(load, retry) { entries ->
            items(entries) { entry ->
                SongButton(
                    song = Song(entry.index.toString(), entry.title, entry.artist, thumbnail = entry.artworkUrl),
                    current = entry.isCurrent,
                    onClick = { source.skipTo(entry.index) },
                )
            }
        }
    }
}

@Composable
fun VolumeScreen(source: PlayerSource) {
    val state by source.state.collectAsState()
    val fraction = if (state.maxVolume > 0) state.volume.toFloat() / state.maxVolume else 0f
    ScreenScaffold {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            // Wear M3 draws the level as an arc on the left edge; it has to be aligned there explicitly.
            LevelIndicator(value = { fraction }, modifier = Modifier.align(Alignment.CenterStart))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { source.adjustVolume(-1) }) {
                    Icon(painterResource(R.drawable.volume_down), stringResource(R.string.volume_down))
                }
                Text("${state.volume}", style = MaterialTheme.typography.displaySmall)
                IconButton(onClick = { source.adjustVolume(1) }) {
                    Icon(painterResource(R.drawable.volume_up), stringResource(R.string.volume_up))
                }
            }
        }
    }
}

private const val SEARCH_INPUT_KEY = "query"
private const val MAX_HOME_SECTIONS = 6
private const val MAX_SECTION_ITEMS = 10
