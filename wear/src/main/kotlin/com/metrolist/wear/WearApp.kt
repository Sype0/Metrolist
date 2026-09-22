/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear

import android.app.Application
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.AccountInfo
import com.metrolist.innertube.models.YouTubeLocale
import com.metrolist.wear.phone.PhoneRepository
import com.metrolist.wear.youtube.StreamResolver
import com.metrolist.wear.protocol.AccountSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.Locale

class WearApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    lateinit var prefs: WearPrefs
        private set

    lateinit var phone: PhoneRepository
        private set

    private val _accountInfo = MutableStateFlow<AccountInfo?>(null)

    /** Name and avatar of the signed-in YouTube Music account, fetched on the watch after sign-in. */
    val accountInfo: StateFlow<AccountInfo?> = _accountInfo.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())

        prefs = WearPrefs(this)
        phone = PhoneRepository(this, scope)
        StreamResolver.initialize(this)

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

    fun applyAccount(account: AccountSync?) {
        YouTube.visitorData = account?.visitorData ?: prefs.visitorData
        YouTube.dataSyncId =
            account?.dataSyncId?.let {
                // Same normalisation the phone app applies to its stored dataSyncId.
                it.takeIf { !it.contains("||") }
                    ?: it.takeIf { it.endsWith("||") }?.substringBefore("||")
                    ?: it.substringAfter("||")
            }
        YouTube.cookie = account?.cookie
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
