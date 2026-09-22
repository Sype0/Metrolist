/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.metrolist.wear.R
import com.metrolist.wear.lyrics.LyricLine
import com.metrolist.wear.playback.PlayerSource
import kotlinx.coroutines.delay

/**
 * Lyrics for the current song. Line-synced lyrics highlight and centre the active line;
 * word-synced lyrics additionally fill in each word as it is sung.
 */
@Composable
fun LyricsScreen(source: PlayerSource) {
    val state by source.state.collectAsState()
    val (load, retry) = rememberLoad(state.mediaId) { source.lyrics() }
    val lines = (load as? Load.Done)?.value.orEmpty()
    val synced = lines.firstOrNull()?.timeMs != null
    val wordSynced = lines.any { it.words != null }
    val listState = rememberScalingLazyListState()

    // Reading along shouldn't be interrupted by the screen timing out.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    val position = remember { mutableLongStateOf(0L) }
    LaunchedEffect(lines, state) {
        if (!synced) return@LaunchedEffect
        while (true) {
            position.longValue = state.positionAt(System.currentTimeMillis())
            if (!state.isPlaying) break
            delay(if (wordSynced) WORD_TICK_MS else LINE_TICK_MS)
        }
    }
    val currentLine by remember(lines) {
        derivedStateOf {
            if (!synced) -1 else lines.indexOfLast { !it.background && (it.timeMs ?: 0) <= position.longValue }
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
                        FilledTonalButton(
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
                            LyricLineText(
                                line = line,
                                synced = synced,
                                active = index == currentLine,
                                // Background vocals have no line of their own to wait for.
                                past = synced && (index < currentLine || (line.background && (line.timeMs ?: 0) <= position.longValue)),
                                positionMs = { position.longValue },
                            )
                        }
                    }
            }
        }
    }
}

@Composable
private fun LyricLineText(
    line: LyricLine,
    synced: Boolean,
    active: Boolean,
    past: Boolean,
    positionMs: () -> Long,
) {
    val colors = MaterialTheme.colorScheme
    val dim = colors.onSurfaceVariant.copy(alpha = 0.55f)
    val style =
        when {
            line.background -> MaterialTheme.typography.bodyMedium
            synced -> MaterialTheme.typography.titleMedium
            else -> MaterialTheme.typography.bodyLarge
        }
    val words = line.words
    val text: AnnotatedString =
        if (words != null && (active || line.background)) {
            // Only the lines being sung recompose every tick.
            karaoke(words, positionMs(), sung = colors.primary, upcoming = dim)
        } else {
            AnnotatedString(line.text.ifEmpty { "♪" })
        }
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = style,
        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
        fontStyle = if (line.background) FontStyle.Italic else FontStyle.Normal,
        color =
            when {
                !synced -> colors.onSurface
                words != null && (active || line.background) -> Color.Unspecified
                active -> colors.primary
                past -> colors.onSurfaceVariant
                else -> dim
            },
        modifier = Modifier.fillMaxWidth().padding(vertical = if (line.background) 0.dp else 2.dp),
    )
}

private fun karaoke(
    words: List<com.metrolist.wear.lyrics.LyricWord>,
    positionMs: Long,
    sung: Color,
    upcoming: Color,
): AnnotatedString =
    buildAnnotatedString {
        words.forEach { word ->
            withStyle(SpanStyle(color = if (positionMs >= word.startMs) sung else upcoming)) {
                append(word.text)
            }
            if (word.trailingSpace) append(' ')
        }
    }

private const val LINE_TICK_MS = 250L
private const val WORD_TICK_MS = 80L
