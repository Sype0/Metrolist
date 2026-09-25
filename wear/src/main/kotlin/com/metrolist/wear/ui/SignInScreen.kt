/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.metrolist.wear.Account
import com.metrolist.wear.R
import com.metrolist.wear.signin.SignInServer
import com.metrolist.wear.signin.SignInState
import kotlinx.coroutines.delay

/** Shows a QR code for the sign-in page the watch serves on Wi-Fi, and signs in once a cookie arrives. */
@Composable
fun SignInScreen(onDone: () -> Unit) {
    val app = rememberApp()
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    val server =
        remember(attempt) {
            SignInServer(context) { cookie ->
                val account = Account(cookie = cookie, visitorData = app.prefs.visitorData)
                app.prefs.saveAccount(account)
                app.applyAccount(account)
            }
        }
    DisposableEffect(server) {
        server.start()
        onDispose { server.stop() }
    }
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val state by server.state.collectAsState()
    LaunchedEffect(state) {
        if (state is SignInState.Done) {
            delay(DONE_DELAY_MS)
            onDone()
        }
    }

    ScreenScaffold {
        Box(modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp), contentAlignment = Alignment.Center) {
            when (val s = state) {
                SignInState.Connecting ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(32.dp))
                        Spacer(Modifier.height(8.dp))
                        CenteredText(stringResource(R.string.signin_connecting))
                    }
                SignInState.NoWifi ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CenteredText(stringResource(R.string.signin_no_wifi))
                        FilledTonalButton(onClick = { attempt++ }, label = { Text(stringResource(R.string.retry)) })
                    }
                is SignInState.Ready ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            stringResource(R.string.signin_scan),
                            style = MaterialTheme.typography.labelSmall,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(4.dp))
                        Image(
                            bitmap = s.qr,
                            contentDescription = s.url,
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.size(QR_SIZE).clip(RoundedCornerShape(6.dp)),
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            s.url.removePrefix("http://"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                    }
                SignInState.Done -> CenteredText(stringResource(R.string.signin_done))
            }
        }
    }
}

private val QR_SIZE = 112.dp
private const val DONE_DELAY_MS = 1_500L
