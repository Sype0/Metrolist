/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear

import android.content.Context
import androidx.core.content.edit
import com.metrolist.wear.protocol.AccountSync

/** Watch-local settings; small enough that SharedPreferences is the right tool. */
class WearPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)

    var visitorData: String?
        get() = prefs.getString(KEY_VISITOR_DATA, null)
        set(value) = prefs.edit { putString(KEY_VISITOR_DATA, value) }

    val account: AccountSync?
        get() =
            prefs.getString(KEY_COOKIE, null)?.let { cookie ->
                AccountSync(
                    cookie = cookie,
                    visitorData = visitorData,
                    dataSyncId = prefs.getString(KEY_DATA_SYNC_ID, null),
                    accountName = prefs.getString(KEY_ACCOUNT_NAME, null),
                    accountEmail = prefs.getString(KEY_ACCOUNT_EMAIL, null),
                )
            }

    fun saveAccount(account: AccountSync) =
        prefs.edit {
            putString(KEY_COOKIE, account.cookie)
            putString(KEY_DATA_SYNC_ID, account.dataSyncId)
            putString(KEY_ACCOUNT_NAME, account.accountName)
            putString(KEY_ACCOUNT_EMAIL, account.accountEmail)
            account.visitorData?.let { putString(KEY_VISITOR_DATA, it) }
        }

    fun clearAccount() =
        prefs.edit {
            remove(KEY_COOKIE)
            remove(KEY_DATA_SYNC_ID)
            remove(KEY_ACCOUNT_NAME)
            remove(KEY_ACCOUNT_EMAIL)
        }

    var highQualityAudio: Boolean
        get() = prefs.getBoolean(KEY_HIGH_QUALITY, false)
        set(value) = prefs.edit { putBoolean(KEY_HIGH_QUALITY, value) }

    /**
     * When off, Media3 holds playback until a Bluetooth output is connected (Wear OS convention).
     * On by default because watches with a speaker otherwise appear to silently not play.
     */
    var allowSpeaker: Boolean
        get() = prefs.getBoolean(KEY_ALLOW_SPEAKER, true)
        set(value) = prefs.edit { putBoolean(KEY_ALLOW_SPEAKER, value) }

    /** Show the phone's playback as an ongoing activity (watch face / Now Bar chip). */
    var phoneOngoingActivity: Boolean
        get() = prefs.getBoolean(KEY_PHONE_ONGOING, true)
        set(value) = prefs.edit { putBoolean(KEY_PHONE_ONGOING, value) }

    private companion object {
        const val KEY_VISITOR_DATA = "visitor_data"
        const val KEY_COOKIE = "cookie"
        const val KEY_DATA_SYNC_ID = "data_sync_id"
        const val KEY_ACCOUNT_NAME = "account_name"
        const val KEY_ACCOUNT_EMAIL = "account_email"
        const val KEY_HIGH_QUALITY = "high_quality"
        const val KEY_PHONE_ONGOING = "phone_ongoing"
        const val KEY_ALLOW_SPEAKER = "allow_speaker"
    }
}
