/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.metrolist.wear.R
import com.metrolist.wear.lyrics.Lyrics
import com.metrolist.wear.playback.PlayerSource
import kotlinx.coroutines.delay

/** Lyrics for the current song; synced lyrics follow playback and keep the active line centred. */
@Composable
fun LyricsScreen(source: PlayerSource) {
    val state by source.state.collectAsState()
    val (load, retry) = rememberLoad(state.mediaId) { source.lyrics()?.let(Lyrics::parse).orEmpty() }
    val lines = (load as? Load.Done)?.value.orEmpty()
    val synced = lines.firstOrNull()?.timeMs != null
    val listState = rememberScalingLazyListState()

    var currentLine by remember(lines) { mutableIntStateOf(-1) }
    LaunchedEffect(lines, state) {
        if (!synced) return@LaunchedEffect
        while (true) {
            val position = state.positionAt(System.currentTimeMillis())
            currentLine = lines.indexOfLast { (it.timeMs ?: 0) <= position }
            if (!state.isPlaying) break
            delay(LYRICS_TICK_MS)
        }
    }
    LaunchedEffect(currentLine) {
        // +1 skips the header item.
        if (currentLine >= 0) listState.animateScrollToItem(currentLine + 1)
    }

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(state.title ?: stringResource(R.string.lyrics), maxLines = 1) } }
            when (load) {
                Load.Loading -> item { CircularProgressIndicator(modifier = Modifier.size(32.dp)) }
                Load.Failed ->
                    item {
                        androidx.wear.compose.material3.FilledTonalButton(
                            onClick = retry,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.retry)) },
                        )
                    }
                is Load.Done ->
                    if (lines.isEmpty()) {
                        item { CenteredText(stringResource(R.string.lyrics_not_found)) }
                    } else {
                        itemsIndexed(lines) { index, line ->
                            val active = !synced || index == currentLine
                            Text(
                                text = line.text.ifEmpty { "♪" },
                                textAlign = TextAlign.Center,
                                style = if (synced) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                                fontWeight = if (synced && active) FontWeight.Bold else FontWeight.Normal,
                                color =
                                    when {
                                        !synced -> MaterialTheme.colorScheme.onSurface
                                        active -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            )
                        }
                    }
            }
        }
    }
}

private const val LYRICS_TICK_MS = 250L
