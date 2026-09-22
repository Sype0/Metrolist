/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.IconButton
import androidx.wear.compose.material3.IconButtonDefaults
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.metrolist.wear.R
import com.metrolist.wear.playback.PlayerSource
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Now-playing screen used for both the watch player and the phone remote. */
@Composable
fun PlayerScreen(
    source: PlayerSource,
    isPhone: Boolean,
    onQueue: () -> Unit,
    onVolume: () -> Unit,
    onLyrics: () -> Unit,
) {
    val state by source.state.collectAsState()
    // The indicator reads this state from its draw lambda, so ticking it only redraws the ring.
    val progress = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(state) {
        while (true) {
            progress.floatValue = state.progress
            if (!state.isPlaying) break
            delay(PROGRESS_TICK_MS)
        }
    }

    // Crown/bezel adjusts volume, as on the stock Wear OS media controls.
    val focusRequester = remember { FocusRequester() }
    var rotaryAccumulator by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ScreenScaffold {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .onRotaryScrollEvent { event ->
                        rotaryAccumulator += event.verticalScrollPixels
                        if (abs(rotaryAccumulator) >= ROTARY_STEP_PX) {
                            source.adjustVolume(if (rotaryAccumulator > 0) 1 else -1)
                            rotaryAccumulator = 0f
                        }
                        true
                    }.focusRequester(focusRequester)
                    .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model = state.artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().alpha(0.45f),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.radialGradient(
                            0f to Color.Black.copy(alpha = 0.35f),
                            1f to Color.Black.copy(alpha = 0.85f),
                        ),
                    ),
            )

            CircularProgressIndicator(
                progress = { progress.floatValue },
                modifier = Modifier.fillMaxSize().padding(3.dp),
                strokeWidth = 5.dp,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.fillMaxSize().padding(horizontal = 22.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        painterResource(if (isPhone) R.drawable.smartphone else R.drawable.watch),
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(if (isPhone) R.string.on_phone else R.string.on_watch),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Spacer(Modifier.height(2.dp))
                Text(
                    text = state.title ?: stringResource(R.string.nothing_playing),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().basicMarquee(),
                )
                Text(
                    text = state.artist.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                state.error?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    IconButton(onClick = source::previous, enabled = state.hasPrevious || state.active) {
                        Icon(painterResource(R.drawable.skip_previous), stringResource(R.string.previous))
                    }
                    Box(contentAlignment = Alignment.Center) {
                        FilledIconButton(
                            onClick = source::playPause,
                            enabled = state.active,
                            modifier = Modifier.size(IconButtonDefaults.LargeButtonSize),
                        ) {
                            Icon(
                                painterResource(if (state.isPlaying) R.drawable.pause else R.drawable.play),
                                contentDescription = stringResource(if (state.isPlaying) R.string.pause else R.string.play),
                                modifier = Modifier.size(IconButtonDefaults.LargeIconSize),
                            )
                        }
                        if (state.isBuffering) {
                            CircularProgressIndicator(modifier = Modifier.size(IconButtonDefaults.LargeButtonSize + 6.dp))
                        }
                    }
                    IconButton(onClick = source::next, enabled = state.hasNext) {
                        Icon(painterResource(R.drawable.skip_next), stringResource(R.string.next))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (state.canLike) {
                        IconButton(onClick = source::toggleLike, modifier = Modifier.size(SMALL_BUTTON)) {
                            Icon(
                                painterResource(if (state.liked) R.drawable.favorite else R.drawable.favorite_border),
                                contentDescription = stringResource(R.string.like),
                                tint = if (state.liked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    IconButton(onClick = onVolume, modifier = Modifier.size(SMALL_BUTTON)) {
                        Icon(painterResource(R.drawable.volume_up), stringResource(R.string.volume))
                    }
                    IconButton(onClick = onLyrics, enabled = state.active, modifier = Modifier.size(SMALL_BUTTON)) {
                        Icon(painterResource(R.drawable.lyrics), stringResource(R.string.lyrics))
                    }
                    IconButton(onClick = onQueue, modifier = Modifier.size(SMALL_BUTTON)) {
                        Icon(painterResource(R.drawable.queue_music), stringResource(R.string.queue))
                    }
                }
            }
        }
    }
}

private const val ROTARY_STEP_PX = 48f
private const val PROGRESS_TICK_MS = 500L
private val SMALL_BUTTON = 40.dp
