/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.common.Player

/** A YouTube Music login; the cookie is what authenticates InnerTube requests. */
data class Account(
    val cookie: String,
    val visitorData: String? = null,
    val dataSyncId: String? = null,
    val accountName: String? = null,
    val accountEmail: String? = null,
)

/** Watch-local settings; small enough that SharedPreferences is the right tool. */
class WearPrefs(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("wear_prefs", Context.MODE_PRIVATE)

    var visitorData: String?
        get() = prefs.getString(KEY_VISITOR_DATA, null)
        set(value) = prefs.edit { putString(KEY_VISITOR_DATA, value) }

    val account: Account?
        get() =
            prefs.getString(KEY_COOKIE, null)?.let { cookie ->
                Account(
                    cookie = cookie,
                    visitorData = visitorData,
                    dataSyncId = prefs.getString(KEY_DATA_SYNC_ID, null),
                    accountName = prefs.getString(KEY_ACCOUNT_NAME, null),
                    accountEmail = prefs.getString(KEY_ACCOUNT_EMAIL, null),
                )
            }

    fun saveAccount(account: Account) =
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

    /** A [Player] repeat mode; kept in sync with the player so it survives restarts. */
    var repeatMode: Int
        get() = prefs.getInt(KEY_REPEAT_MODE, Player.REPEAT_MODE_OFF)
        set(value) = prefs.edit { putInt(KEY_REPEAT_MODE, value) }

    var shuffle: Boolean
        get() = prefs.getBoolean(KEY_SHUFFLE, false)
        set(value) = prefs.edit { putBoolean(KEY_SHUFFLE, value) }

    /** When a list or album runs out (and repeat is off), keep going with a radio of similar songs. */
    var autoplay: Boolean
        get() = prefs.getBoolean(KEY_AUTOPLAY, true)
        set(value) = prefs.edit { putBoolean(KEY_AUTOPLAY, value) }

    var skipSilence: Boolean
        get() = prefs.getBoolean(KEY_SKIP_SILENCE, false)
        set(value) = prefs.edit { putBoolean(KEY_SKIP_SILENCE, value) }

    /** Crown/bezel on the player screen: seek within the song instead of changing the volume. */
    var crownSeeks: Boolean
        get() = prefs.getBoolean(KEY_CROWN_SEEKS, false)
        set(value) = prefs.edit { putBoolean(KEY_CROWN_SEEKS, value) }

    /** Upper bound for the player cache of recently played songs; downloads don't count toward it. */
    var maxCacheMb: Int
        get() = prefs.getInt(KEY_MAX_CACHE_MB, 128)
        set(value) = prefs.edit { putInt(KEY_MAX_CACHE_MB, value) }

    /** Download every liked song: the whole list when the app starts, and songs liked on the watch. */
    var autoDownloadLiked: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD_LIKED, false)
        set(value) = prefs.edit { putBoolean(KEY_AUTO_DOWNLOAD_LIKED, value) }

    companion object {
        const val KEY_REPEAT_MODE = "repeat_mode"
        const val KEY_SHUFFLE = "shuffle"
        const val KEY_SKIP_SILENCE = "skip_silence"

        private const val KEY_VISITOR_DATA = "visitor_data"
        private const val KEY_COOKIE = "cookie"
        private const val KEY_DATA_SYNC_ID = "data_sync_id"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val KEY_ACCOUNT_EMAIL = "account_email"
        private const val KEY_HIGH_QUALITY = "high_quality"
        private const val KEY_ALLOW_SPEAKER = "allow_speaker"
        private const val KEY_AUTOPLAY = "autoplay"
        private const val KEY_CROWN_SEEKS = "crown_seeks"
        private const val KEY_MAX_CACHE_MB = "max_cache_mb"
        private const val KEY_AUTO_DOWNLOAD_LIKED = "auto_download_liked"
    }
}
