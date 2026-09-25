/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Signs the watch in with a YouTube Music cookie sent over adb. Wear OS has no WebView to log in
 * with, and the manifest guards this receiver with the DUMP permission, which only the shell and
 * the system hold:
 *
 * ```
 * adb shell am broadcast -n com.metrolist.music/com.metrolist.wear.AccountImportReceiver \
 *     --es cookie "'SAPISID=...; __Secure-3PSID=...'" [--es data_sync_id "'...'"]
 * adb shell am broadcast -n com.metrolist.music/com.metrolist.wear.AccountImportReceiver --ez sign_out true
 * ```
 */
class AccountImportReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val app = WearApp.from(context)
        if (intent.getBooleanExtra(EXTRA_SIGN_OUT, false)) {
            app.prefs.clearAccount()
            app.applyAccount(null)
            reply(RESULT_SIGNED_OUT)
            return
        }
        val cookie = intent.getStringExtra(EXTRA_COOKIE)?.trim()
        if (cookie.isNullOrEmpty() || "SAPISID" !in cookie) {
            resultCode = Activity.RESULT_CANCELED
            resultData = "cookie missing or has no SAPISID"
            return
        }
        val account =
            Account(
                cookie = cookie,
                visitorData = intent.getStringExtra(EXTRA_VISITOR_DATA) ?: app.prefs.visitorData,
                dataSyncId = intent.getStringExtra(EXTRA_DATA_SYNC_ID),
            )
        app.prefs.saveAccount(account)
        app.applyAccount(account)
        reply(RESULT_SIGNED_IN)
    }

    private fun reply(message: String) {
        resultCode = Activity.RESULT_OK
        resultData = message
    }

    private companion object {
        const val EXTRA_COOKIE = "cookie"
        const val EXTRA_DATA_SYNC_ID = "data_sync_id"
        const val EXTRA_VISITOR_DATA = "visitor_data"
        const val EXTRA_SIGN_OUT = "sign_out"
        const val RESULT_SIGNED_IN = "signed in"
        const val RESULT_SIGNED_OUT = "signed out"
    }
}
