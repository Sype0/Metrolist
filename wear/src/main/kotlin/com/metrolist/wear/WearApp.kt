/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear

import android.app.Application
import androidx.media3.common.util.UnstableApi
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AccountInfo
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.wear.offline.OfflineStore
import com.metrolist.wear.youtube.StreamResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

@UnstableApi
class WearApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var prefs: WearPrefs
        private set

    /** Downloads and the player cache; one instance per process, since a cache locks its directory. */
    lateinit var offline: OfflineStore
        private set

    private val _account = MutableStateFlow<Account?>(null)

    /** The signed-in account, or null when browsing anonymously. */
    val account: StateFlow<Account?> = _account.asStateFlow()

    private val _accountInfo = MutableStateFlow<AccountInfo?>(null)

    /** Name and avatar of the signed-in YouTube Music account, fetched after sign-in. */
    val accountInfo: StateFlow<AccountInfo?> = _accountInfo.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        prefs = WearPrefs(this)
        StreamResolver.initialize(this)
        offline = OfflineStore(this, prefs, scope)
        offline.prune()

        val locale = Locale.getDefault()
        YouTube.locale =
            YouTubeLocale(
                gl = locale.country.takeIf { it.length == 2 } ?: "US",
                hl = locale.language.takeIf { it.isNotBlank() } ?: "en",
            )
        applyAccount(prefs.account)

        scope.launch(Dispatchers.IO) {
            if (YouTube.visitorData == null) {
                YouTube.visitorData().onSuccess {
                    YouTube.visitorData = it
                    prefs.visitorData = it
                }
            }
        }
    }

    fun applyAccount(account: Account?) {
        _account.value = account
        YouTube.visitorData = account?.visitorData ?: prefs.visitorData
        YouTube.dataSyncId =
            account?.dataSyncId?.let {
                // Same normalisation Metrolist applies to its stored dataSyncId.
                it.takeIf { !it.contains("||") }
                    ?: it.takeIf { it.endsWith("||") }?.substringBefore("||")
                    ?: it.substringAfter("||")
            }
        YouTube.cookie = account?.cookie
        offline.syncLikedSongs()
        _accountInfo.value = null
        if (account?.cookie != null) {
            scope.launch(Dispatchers.IO) {
                YouTube.accountInfo().onSuccess { _accountInfo.value = it }
            }
        }
    }

    companion object {
        fun from(context: android.content.Context) = context.applicationContext as WearApp
    }
}
