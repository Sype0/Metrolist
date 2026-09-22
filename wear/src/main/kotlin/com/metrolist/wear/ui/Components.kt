/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import coil3.compose.AsyncImage
import com.metrolist.wear.R
import com.metrolist.wear.playback.Song
import com.metrolist.wear.playback.thumbnailUrl

@Composable
fun Artwork(
    model: Any?,
    modifier: Modifier = Modifier,
    circle: Boolean = false,
) {
    AsyncImage(
        model = (model as? String)?.let { thumbnailUrl(it, 120) } ?: model,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        placeholder = painterResource(R.drawable.music_note),
        error = painterResource(R.drawable.music_note),
        modifier = modifier.size(ButtonDefaults.LargeIconSize).clip(if (circle) CircleShape else RoundedCornerShape(8.dp)),
    )
}

@Composable
fun SongButton(
    song: Song,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    current: Boolean = false,
) {
    FilledTonalButton(
        onClick = onClick,
        onLongClick = onLongClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (current) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        icon = { Artwork(song.thumbnail) },
        secondaryLabel = { Text(song.artist, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        label = { Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
fun NavButton(
    label: String,
    @DrawableRes icon: Int,
    onClick: () -> Unit,
    secondary: String? = null,
    primary: Boolean = false,
) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            icon = { Icon(painterResource(icon), contentDescription = null) },
            secondaryLabel = secondary?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
    } else {
        FilledTonalButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            icon = { Icon(painterResource(icon), contentDescription = null) },
            secondaryLabel = secondary?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
            label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        )
    }
}

@Composable
fun CenteredText(text: String) {
    Text(
        text = text,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
}

sealed interface Load<out T> {
    data object Loading : Load<Nothing>

    data class Done<T>(val value: T) : Load<T>

    data object Failed : Load<Nothing>
}

/** Runs [block] once per [key]; the returned lambda retries it. */
@Composable
fun <T> rememberLoad(
    key: Any?,
    block: suspend () -> T,
): Pair<Load<T>, () -> Unit> {
    var attempt by remember(key) { mutableIntStateOf(0) }
    val state by produceState<Load<T>>(Load.Loading, key, attempt) {
        value = Load.Loading
        value = runCatching { block() }.fold({ Load.Done(it) }, { Load.Failed })
    }
    return state to { attempt++ }
}

/** Standard loading / error / empty rows for a list screen. */
fun <T> ScalingLazyListScope.loadStateItems(
    load: Load<List<T>>,
    retry: () -> Unit,
    content: ScalingLazyListScope.(List<T>) -> Unit,
) {
    when (load) {
        Load.Loading -> item { CircularProgressIndicator(modifier = Modifier.size(32.dp)) }
        Load.Failed ->
            item {
                FilledTonalButton(
                    onClick = retry,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.retry)) },
                    secondaryLabel = { Text(stringResource(R.string.load_failed), maxLines = 3) },
                )
            }
        is Load.Done ->
            if (load.value.isEmpty()) {
                item { CenteredText(stringResource(R.string.empty)) }
            } else {
                content(load.value)
            }
    }
}

@Composable
fun rememberApp() = com.metrolist.wear.WearApp.from(LocalContext.current)
