/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.ui

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.metrolist.innertube.YouTube
import com.metrolist.wear.R
import com.metrolist.wear.playback.PlayerState
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    localState: PlayerState,
    phoneState: PlayerState,
    phoneConnected: Boolean,
    signedIn: Boolean,
    onLocalPlayer: () -> Unit,
    onPhoneRemote: () -> Unit,
    onSearch: () -> Unit,
    onQuickPicks: () -> Unit,
    onLiked: () -> Unit,
    onPlaylists: () -> Unit,
    onSettings: () -> Unit,
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

            if (localState.active) {
                item {
                    NowPlayingButton(stringResource(R.string.on_watch), R.drawable.watch, localState, onLocalPlayer)
                }
            }
            if (phoneConnected && phoneState.active) {
                item {
                    NowPlayingButton(stringResource(R.string.on_phone), R.drawable.smartphone, phoneState, onPhoneRemote)
                }
            } else if (phoneConnected) {
                item { NavButton(stringResource(R.string.phone_remote), R.drawable.smartphone, onPhoneRemote) }
            }

            item { NavButton(stringResource(R.string.quick_picks), R.drawable.trending_up, onQuickPicks) }
            if (signedIn) {
                item { NavButton(stringResource(R.string.liked_songs), R.drawable.favorite, onLiked) }
                item { NavButton(stringResource(R.string.playlists), R.drawable.library_music, onPlaylists) }
            }
            item { NavButton(stringResource(R.string.settings), R.drawable.settings, onSettings) }
        }
    }
}

@Composable
private fun NowPlayingButton(
    where: String,
    icon: Int,
    state: PlayerState,
    onClick: () -> Unit,
) {
    androidx.wear.compose.material3.Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        icon = { Artwork(state.artwork) },
        secondaryLabel = {
            Icon(painterResource(icon), contentDescription = where, modifier = Modifier.size(14.dp))
            Text(state.artist ?: where, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
        },
        label = { Text(state.title ?: stringResource(R.string.now_playing), maxLines = 1, overflow = TextOverflow.Ellipsis) },
    )
}

@Composable
fun SettingsScreen(onAccountChanged: () -> Unit) {
    val app = rememberApp()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phoneConnected by app.phone.connected.collectAsState()
    var account by remember { mutableStateOf(app.prefs.account) }
    var highQuality by remember { mutableStateOf(app.prefs.highQualityAudio) }
    var allowSpeaker by remember { mutableStateOf(app.prefs.allowSpeaker) }
    var phoneOngoing by remember { mutableStateOf(app.prefs.phoneOngoingActivity) }
    val listState = rememberScalingLazyListState()

    val syncedText = stringResource(R.string.account_synced)
    val failedText = stringResource(R.string.account_sync_failed)

    ScreenScaffold(scrollState = listState) { padding ->
        ScalingLazyColumn(state = listState, contentPadding = padding, modifier = Modifier.fillMaxWidth()) {
            item { ListHeader { Text(stringResource(R.string.settings)) } }
            item { ListHeader { Text(stringResource(R.string.account)) } }
            item {
                CenteredText(
                    account?.let { stringResource(R.string.signed_in_as, it.accountName ?: it.accountEmail ?: "YouTube Music") }
                        ?: stringResource(R.string.not_signed_in),
                )
            }
            item {
                FilledTonalButton(
                    onClick = {
                        scope.launch {
                            val synced = app.phone.fetchAccount()?.takeIf { !it.cookie.isNullOrBlank() }
                            if (synced != null) {
                                app.prefs.saveAccount(synced)
                                app.applyAccount(synced)
                                account = app.prefs.account
                                onAccountChanged()
                            }
                            Toast.makeText(context, if (synced != null) syncedText else failedText, Toast.LENGTH_SHORT).show()
                        }
                    },
                    enabled = phoneConnected,
                    modifier = Modifier.fillMaxWidth(),
                    icon = { Icon(painterResource(R.drawable.sync), contentDescription = null) },
                    secondaryLabel = { Text(stringResource(R.string.use_phone_account_hint), maxLines = 2) },
                    label = { Text(stringResource(R.string.use_phone_account)) },
                )
            }
            if (account != null) {
                item {
                    FilledTonalButton(
                        onClick = {
                            app.prefs.clearAccount()
                            app.applyAccount(null)
                            YouTube.cookie = null
                            account = null
                            onAccountChanged()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        icon = { Icon(painterResource(R.drawable.account), contentDescription = null) },
                        label = { Text(stringResource(R.string.sign_out)) },
                    )
                }
            }
            item {
                SwitchButton(
                    checked = highQuality,
                    onCheckedChange = {
                        highQuality = it
                        app.prefs.highQualityAudio = it
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
                        app.prefs.allowSpeaker = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.allow_speaker)) },
                    secondaryLabel = { Text(stringResource(R.string.allow_speaker_hint)) },
                )
            }
            item {
                SwitchButton(
                    checked = phoneOngoing,
                    onCheckedChange = {
                        phoneOngoing = it
                        app.prefs.phoneOngoingActivity = it
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.phone_ongoing), maxLines = 3) },
                )
            }
        }
    }
}
