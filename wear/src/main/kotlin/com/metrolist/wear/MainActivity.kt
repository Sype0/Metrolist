/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.metrolist.wear.playback.LocalPlayer
import com.metrolist.wear.ui.BrowseActions
import com.metrolist.wear.ui.CachedSongsScreen
import com.metrolist.wear.ui.DownloadsScreen
import com.metrolist.wear.ui.HomeScreen
import com.metrolist.wear.ui.LyricsScreen
import com.metrolist.wear.ui.MetrolistWearTheme
import com.metrolist.wear.ui.PlayerScreen
import com.metrolist.wear.ui.PlaylistsScreen
import com.metrolist.wear.ui.QueueScreen
import com.metrolist.wear.ui.QuickPicksScreen
import com.metrolist.wear.ui.SearchScreen
import com.metrolist.wear.ui.SettingsScreen
import com.metrolist.wear.ui.SignInScreen
import com.metrolist.wear.ui.SongListScreen
import com.metrolist.wear.ui.VolumeScreen
import com.metrolist.wear.ui.albumLoader
import com.metrolist.wear.ui.likedSongsLoader
import com.metrolist.wear.ui.playlistLoader

@UnstableApi
class MainActivity : ComponentActivity() {
    private lateinit var localPlayer: LocalPlayer
    private var pendingRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = WearApp.from(this)
        localPlayer = LocalPlayer(this, app.scope)
        pendingRoute = routeFor(intent)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {}
                .launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            MetrolistWearTheme {
                WearNavigation(app)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        localPlayer.connect()
    }

    override fun onStop() {
        localPlayer.disconnect()
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingRoute = routeFor(intent)
    }

    private fun routeFor(intent: Intent?) =
        when (intent?.action) {
            ACTION_OPEN_PLAYER -> ROUTE_PLAYER
            ACTION_OPEN_DOWNLOADS -> ROUTE_DOWNLOADS
            else -> null
        }

    @Composable
    private fun WearNavigation(app: WearApp) {
        val navController = rememberSwipeDismissableNavController()
        val localState by localPlayer.state.collectAsState()
        val account by app.account.collectAsState()
        val accountInfo by app.accountInfo.collectAsState()

        LaunchedEffect(pendingRoute) {
            pendingRoute?.let {
                navController.navigate(it) { launchSingleTop = true }
                pendingRoute = null
            }
        }

        val actions =
            BrowseActions(
                playLocal = { queue ->
                    localPlayer.play(queue)
                    navController.navigate(ROUTE_PLAYER) { launchSingleTop = true }
                },
                openPlaylist = { navController.navigate("playlist/$it") },
                openAlbum = { navController.navigate("album/$it") },
            )

        AppScaffold {
            SwipeDismissableNavHost(navController = navController, startDestination = ROUTE_HOME) {
                composable(ROUTE_HOME) {
                    HomeScreen(
                        playerState = localState,
                        signedIn = account != null,
                        accountInfo = accountInfo,
                        onPlayer = { navController.navigate(ROUTE_PLAYER) },
                        onSearch = { navController.navigate(ROUTE_SEARCH) },
                        onQuickPicks = { navController.navigate(ROUTE_QUICK_PICKS) },
                        onLiked = { navController.navigate(ROUTE_LIKED) },
                        onPlaylists = { navController.navigate(ROUTE_PLAYLISTS) },
                        onDownloads = { navController.navigate(ROUTE_DOWNLOADS) },
                        onCached = { navController.navigate(ROUTE_CACHED) },
                        onSettings = { navController.navigate(ROUTE_SETTINGS) },
                        onSignIn = { navController.navigate(ROUTE_SIGN_IN) },
                    )
                }
                composable(ROUTE_PLAYER) {
                    PlayerScreen(
                        localPlayer,
                        onQueue = { navController.navigate(ROUTE_QUEUE) },
                        onVolume = { navController.navigate(ROUTE_VOLUME) },
                        onLyrics = { navController.navigate(ROUTE_LYRICS) },
                    )
                }
                composable(ROUTE_QUEUE) { QueueScreen(localPlayer) }
                composable(ROUTE_VOLUME) { VolumeScreen(localPlayer) }
                composable(ROUTE_LYRICS) { LyricsScreen(localPlayer) }
                composable(ROUTE_SEARCH) {
                    var query by remember { mutableStateOf<String?>(null) }
                    SearchScreen(actions, query) { query = it }
                }
                composable(ROUTE_QUICK_PICKS) { QuickPicksScreen(actions) }
                composable(ROUTE_LIKED) {
                    SongListScreen(stringResource(R.string.liked_songs), "LM", actions, likedSongsLoader())
                }
                composable(ROUTE_PLAYLISTS) { PlaylistsScreen(actions) }
                composable(ROUTE_DOWNLOADS) { DownloadsScreen(actions) }
                composable(ROUTE_CACHED) { CachedSongsScreen(actions) }
                composable("playlist/{id}") { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    SongListScreen(stringResource(R.string.playlists), id, actions, playlistLoader(id))
                }
                composable("album/{id}") { entry ->
                    val id = entry.arguments?.getString("id").orEmpty()
                    SongListScreen("", id, actions, albumLoader(id))
                }
                composable(ROUTE_SETTINGS) { SettingsScreen(localPlayer, onSignIn = { navController.navigate(ROUTE_SIGN_IN) }) }
                composable(ROUTE_SIGN_IN) { SignInScreen(onDone = { navController.popBackStack() }) }
            }
        }
    }

    companion object {
        const val ACTION_OPEN_PLAYER = "com.metrolist.wear.OPEN_PLAYER"
        const val ACTION_OPEN_DOWNLOADS = "com.metrolist.wear.OPEN_DOWNLOADS"

        private const val ROUTE_HOME = "home"
        private const val ROUTE_PLAYER = "player"
        private const val ROUTE_QUEUE = "queue"
        private const val ROUTE_VOLUME = "volume"
        private const val ROUTE_LYRICS = "lyrics"
        private const val ROUTE_SEARCH = "search"
        private const val ROUTE_QUICK_PICKS = "quick_picks"
        private const val ROUTE_LIKED = "liked"
        private const val ROUTE_PLAYLISTS = "playlists"
        private const val ROUTE_DOWNLOADS = "downloads"
        private const val ROUTE_CACHED = "cached"
        private const val ROUTE_SETTINGS = "settings"
        private const val ROUTE_SIGN_IN = "sign_in"
    }
}
