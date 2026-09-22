/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.lyrics

import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.WatchEndpoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs

data class LyricLine(
    /** Start time, or null for unsynced lyrics. */
    val timeMs: Long?,
    val text: String,
)

object Lyrics {
    private val lineTag = Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    /** Word-level timing (`<00:01.23>`) and speaker tags used by some of the phone app's providers. */
    private val inlineTags = Regex("""<\d{1,2}:\d{2}(?:[.:]\d{1,3})?>|\{[^}]*\}""")
    private val metaTag = Regex("""^\[[a-zA-Z]+:.*]$""")

    /** Parses LRC (including multiple timestamps per line); anything without timestamps becomes plain lines. */
    fun parse(raw: String): List<LyricLine> {
        val synced = mutableListOf<LyricLine>()
        val plain = mutableListOf<LyricLine>()
        raw.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || metaTag.matches(line)) return@forEach
            val stamps = lineTag.findAll(line).toList()
            val text = line.replace(lineTag, "").replace(inlineTags, "").replace(Regex("\\s+"), " ").trim()
            if (stamps.isEmpty()) {
                plain += LyricLine(null, text)
            } else {
                stamps.forEach { match ->
                    val (min, sec, frac) = match.destructured
                    val fracMs =
                        when (frac.length) {
                            0 -> 0
                            1 -> frac.toInt() * 100
                            2 -> frac.toInt() * 10
                            else -> frac.take(3).toInt()
                        }
                    synced += LyricLine(min.toLong() * 60_000 + sec.toLong() * 1_000 + fracMs, text)
                }
            }
        }
        return if (synced.isNotEmpty()) synced.sortedBy { it.timeMs } else plain
    }

    private val client = OkHttpClient()
    private val json = Json { ignoreUnknownKeys = true }
    private val titleNoise = Regex("""\s*[(\[](official|lyric|audio|video|visualizer|music video|mv|hd|4k)[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)

    /** Watch-side lookup: LrcLib (synced) first, then YouTube Music's plain lyrics. */
    suspend fun fetch(
        videoId: String,
        title: String,
        artist: String,
        durationSec: Int,
    ): String? =
        withContext(Dispatchers.IO) {
            val cleanTitle = title.replace(titleNoise, "").trim()
            val firstArtist = artist.split(",", "&", " feat", " ft.").first().trim()
            runCatching { lrcLib(cleanTitle, firstArtist, durationSec) }.getOrNull()
                ?: runCatching { youTube(videoId) }.getOrNull()
        }

    private fun lrcLib(
        title: String,
        artist: String,
        durationSec: Int,
    ): String? {
        val url =
            "https://lrclib.net/api/search"
                .toHttpUrl()
                .newBuilder()
                .addQueryParameter("track_name", title)
                .addQueryParameter("artist_name", artist)
                .build()
        val request = Request.Builder().url(url).header("User-Agent", "Metrolist Wear (https://github.com/MetrolistGroup/Metrolist)").build()
        val body = client.newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null } ?: return null
        val results = json.parseToJsonElement(body).jsonArray.map { it.jsonObject }
        val candidates =
            results.filter { track ->
                val duration = track["duration"]?.jsonPrimitive?.doubleOrNull ?: return@filter durationSec <= 0
                durationSec <= 0 || abs(duration - durationSec) <= 4
            }
        return candidates.firstNotNullOfOrNull { it.lyricsField("syncedLyrics") }
            ?: candidates.firstNotNullOfOrNull { it.lyricsField("plainLyrics") }
    }

    private fun JsonObject.lyricsField(name: String) = this[name]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

    private suspend fun youTube(videoId: String): String? {
        val endpoint = YouTube.next(WatchEndpoint(videoId = videoId)).getOrNull()?.lyricsEndpoint ?: return null
        return YouTube.lyrics(endpoint).getOrNull()?.takeIf { it.isNotBlank() }
    }
}
