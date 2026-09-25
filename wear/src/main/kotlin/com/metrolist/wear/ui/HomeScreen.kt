/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.EdgeButtonSize
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text
import com.metrolist.innertube.models.AccountInfo
import com.metrolist.wear.R
import com.metrolist.wear.offline.OfflineStore
import com.metrolist.wear.playback.LocalPlayer
import com.metrolist.wear.playback.PlaybackService
import com.metrolist.wear.playback.PlayerState
import com.metrolist.wear.playback.SleepTimer
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    playerState: PlayerState,
    signedIn: Boolean,
    accountInfo: AccountInfo?,
    onPlayer: () -> Unit,
    onSearch: () -> Unit,
    onQuickPicks: () -> Unit,
    onLiked: () -> Unit,
    onPlaylists: () -> Unit,
    onDownloads: () -> Unit,
    onCached: () -> Unit,
    onSettings: () -> Unit,
    onSignIn: () -> Unit,
) {
    val listState = rememberScalingLazyListState()
    ScreenScaffold(
        scrollState = listState,
        edgeButton = {
            EdgeButton(onClick = onSearch, buttonSize = EdgeButtonSize.Medium) {
                Icon(painterResource(R.drawable.search), contentDescription = stringResource(R.string.search))
            }
        },
    ) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(stringResource(R.string.app_name)) } }
            accountInfo?.let { info ->
                item { AccountCard(info, onClick = onSettings) }
            }
            if (playerState.active) {
                item { NowPlayingButton(playerState, onPlayer) }
            }
            item { NavButton(stringResource(R.string.quick_picks), R.drawable.trending_up, onQuickPicks) }
            if (signedIn) {
                item { NavButton(stringResource(R.string.liked_songs), R.drawable.favorite, onLiked) }
                item { NavButton(stringResource(R.string.playlists), R.drawable.library_music, onPlaylists) }
            } else {
                item { NavButton(stringResource(R.string.sign_in), R.drawable.account, onSignIn, secondary = stringResource(R.string.sign_in_secondary)) }
            }
            item { NavButton(stringResource(R.string.downloads), R.drawable.download, onDownloads) }
            item { NavButton(stringResource(R.string.cached_songs), R.drawable.history, onCached) }
            item { NavButton(stringResource(R.string.settings), R.drawable.settings, onSettings) }
        }
    }
}

@Composable
private fun AccountCard(
    info: AccountInfo,
    onClick: () -> Unit,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        icon = { Artwork(info.thumbnailUrl, circle = true) },
        secondaryLabel = (info.channelHandle ?: info.email)?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
        label = { Text(info.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
private fun NowPlayingButton(
    state: PlayerState,
    onClick: () -> Unit,
) {
    androidx.wear.compose.material3.Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        icon = { Artwork(state.artwork) },
        secondaryLabel = { Text(state.artist ?: stringResource(R.string.now_playing), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        label = { Text(state.title ?: stringResource(R.string.now_playing), maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@UnstableApi
@Composable
fun SettingsScreen(
    player: LocalPlayer,
    onSignIn: () -> Unit,
) {
    val app = rememberApp()
    val prefs = app.prefs
    val account by app.account.collectAsState()
    val accountInfo by app.accountInfo.collectAsState()
    val playerState by player.state.collectAsState()
    val sleepTimer by PlaybackService.sleepTimer.collectAsState()
    var repeatMode by remember { mutableStateOf(prefs.repeatMode) }
    var shuffle by remember { mutableStateOf(prefs.shuffle) }
    var autoplay by remember { mutableStateOf(prefs.autoplay) }
    var skipSilence by remember { mutableStateOf(prefs.skipSilence) }
    var highQuality by remember { mutableStateOf(prefs.highQualityAudio) }
    var allowSpeaker by remember { mutableStateOf(prefs.allowSpeaker) }
    var crownSeeks by remember { mutableStateOf(prefs.crownSeeks) }
    var maxCacheMb by remember { mutableStateOf(prefs.maxCacheMb) }
    var autoDownloadLiked by remember { mutableStateOf(prefs.autoDownloadLiked) }
    val cacheUsed by produceState(app.offline.cacheUsedBytes, maxCacheMb) {
        // Shrinking the limit evicts in the background; pick up the new usage once it has.
        delay(500)
        value = app.offline.cacheUsedBytes
    }
    val listState = rememberScalingLazyListState()

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(stringResource(R.string.settings)) } }

            item { ListHeader { Text(stringResource(R.string.playback)) } }
            item {
                FilledTonalButton(
                    onClick = {
                        repeatMode =
                            when (repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                        prefs.repeatMode = repeatMode
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = {
                        Icon(
                            painterResource(
                                when (repeatMode) {
                                    Player.REPEAT_MODE_ONE -> R.drawable.repeat_one_on
                                    Player.REPEAT_MODE_ALL -> R.drawable.repeat_on
                                    else -> R.drawable.repeat
                                },
                            ),
                            contentDescription = null,
                        )
                    },
                    secondaryLabel = { Text(stringResource(repeatModeLabel(repeatMode))) },
                    label = { Text(stringResource(R.string.repeat)) },
                )
            }
            item {
                SwitchButton(
                    checked = shuffle,
                    onCheckedChange = {
                        shuffle = it
                        prefs.shuffle = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.shuffle)) },
                )
            }
            item {
                SwitchButton(
                    checked = autoplay,
                    onCheckedChange = {
                        autoplay = it
                        prefs.autoplay = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.autoplay)) },
                    secondaryLabel = { Text(stringResource(R.string.autoplay_hint), maxLines = 3) },
                )
            }
            item {
                FilledTonalButton(
                    onClick = { player.setSleepTimer(nextSleepTimer(sleepTimer)) },
                    enabled = playerState.active || sleepTimer != null,
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(painterResource(R.drawable.bedtime), contentDescription = null) },
                    secondaryLabel = { Text(sleepTimerLabel(sleepTimer)) },
                    label = { Text(stringResource(R.string.sleep_timer)) },
                )
            }
            item {
                SwitchButton(
                    checked = skipSilence,
                    onCheckedChange = {
                        skipSilence = it
                        prefs.skipSilence = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.skip_silence)) },
                )
            }

            item { ListHeader { Text(stringResource(R.string.sound_and_controls)) } }
            item {
                SwitchButton(
                    checked = highQuality,
                    onCheckedChange = {
                        highQuality = it
                        prefs.highQualityAudio = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.high_quality_audio)) },
                )
            }
            item {
                SwitchButton(
                    checked = allowSpeaker,
                    onCheckedChange = {
                        allowSpeaker = it
                        prefs.allowSpeaker = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.allow_speaker)) },
                    secondaryLabel = { Text(stringResource(R.string.allow_speaker_hint)) },
                )
            }
            item {
                SwitchButton(
                    checked = crownSeeks,
                    onCheckedChange = {
                        crownSeeks = it
                        prefs.crownSeeks = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.crown_seeks)) },
                    secondaryLabel = { Text(stringResource(R.string.crown_seeks_hint), maxLines = 3) },
                )
            }

            item { ListHeader { Text(stringResource(R.string.storage)) } }
            item {
                FilledTonalButton(
                    onClick = {
                        val sizes = OfflineStore.CACHE_SIZES_MB
                        maxCacheMb = sizes.firstOrNull { it > maxCacheMb } ?: sizes.first()
                        app.offline.setMaxCacheMb(maxCacheMb)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(painterResource(R.drawable.storage), contentDescription = null) },
                    secondaryLabel = {
                        Text(stringResource(R.string.cache_usage, formatSize(cacheUsed), formatSize(maxCacheMb * OfflineStore.MB)), maxLines = 2)
                    },
                    label = { Text(stringResource(R.string.max_cache_size), maxLines = 2) },
                )
            }
            item {
                SwitchButton(
                    checked = autoDownloadLiked,
                    onCheckedChange = {
                        autoDownloadLiked = it
                        prefs.autoDownloadLiked = it
                        if (it) app.offline.syncLikedSongs()
                    },
                    enabled = account != null,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.auto_download_liked), maxLines = 2) },
                    secondaryLabel = {
                        Text(stringResource(if (account != null) R.string.auto_download_liked_hint else R.string.not_signed_in), maxLines = 3)
                    },
                )
            }

            item { ListHeader { Text(stringResource(R.string.account)) } }
            item {
                val info = accountInfo
                when {
                    account != null && info != null -> AccountCard(info, onClick = {})
                    account != null -> CenteredText(stringResource(R.string.signed_in))
                    else -> CenteredText(stringResource(R.string.not_signed_in))
                }
            }
            if (account == null) {
                item {
                    FilledTonalButton(
                        onClick = onSignIn,
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(painterResource(R.drawable.account), contentDescription = null) },
                        secondaryLabel = { Text(stringResource(R.string.sign_in_secondary), maxLines = 2) },
                        label = { Text(stringResource(R.string.sign_in)) },
                    )
                }
            } else {
                item {
                    FilledTonalButton(
                        onClick = {
                            prefs.clearAccount()
                            app.applyAccount(null)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(painterResource(R.drawable.account), contentDescription = null) },
                        label = { Text(stringResource(R.string.sign_out)) },
                    )
                }
            }
        }
    }
}

/** Cycles off → 15 → 30 → 45 → 60 min → end of song → off, continuing from whatever is running. */
private fun nextSleepTimer(current: SleepTimer?): Int =
    when (current) {
        null -> SLEEP_PRESETS_MIN.first()
        SleepTimer.EndOfSong -> 0
        is SleepTimer.At -> {
            val left = minutesLeft(current, System.currentTimeMillis())
            SLEEP_PRESETS_MIN.firstOrNull { it > left } ?: PlaybackService.SLEEP_END_OF_SONG
        }
    }

private fun minutesLeft(
    timer: SleepTimer.At,
    now: Long,
) = ((timer.endsAtMs - now + 59_999) / 60_000).toInt().coerceAtLeast(0)

@Composable
private fun sleepTimerLabel(timer: SleepTimer?): String {
    val now by produceState(System.currentTimeMillis(), timer) {
        while (true) {
            value = System.currentTimeMillis()
            delay(15_000)
        }
    }
    return when (timer) {
        null -> stringResource(R.string.off)
        SleepTimer.EndOfSong -> stringResource(R.string.sleep_end_of_song)
        is SleepTimer.At -> stringResource(R.string.sleep_minutes_left, minutesLeft(timer, now))
    }
}

private val SLEEP_PRESETS_MIN = listOf(15, 30, 45, 60)
