/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.youtube

import android.content.Context
import android.net.ConnectivityManager
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.core.content.edit
import com.metrolist.innertube.YouTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.PoTokenResult
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import kotlin.time.Clock

/**
 * Watch-side counterpart of Metrolist's `InnerTubeXPlayer`. Wear OS has no WebView, so no
 * BotGuard PO token can be minted here; InnerTubeX then only picks clients that work without one.
 */
object StreamResolver {
    private const val DEFAULT_STREAM_TTL_SECONDS = 5 * 60
    private const val PLAYER_CONFIG_URL =
        "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"

    data class Stream(
        val url: String,
        val headers: Map<String, String>,
        val expiresAtMs: Long,
        val requireBoundedRange: Boolean,
        val rangeChunkSizeBytes: Long,
        val useRangeChunks: Boolean,
    )

    private class Bundle(
        val generation: Long,
        val cipherService: YouTubeCipherService,
        val extractor: InnerTubeExtractor,
    )

    private lateinit var appContext: Context
    private val mutex = Mutex()
    private var bundle: Bundle? = null
    private val cache = HashMap<String, Stream>()

    fun initialize(context: Context) {
        appContext = context.applicationContext
    }

    /** [forDownload] asks for one unbounded stream, since the downloader reads a song start to end. */
    suspend fun resolve(
        videoId: String,
        highQuality: Boolean,
        forDownload: Boolean = false,
    ): Stream {
        val cacheKey = if (forDownload) "$videoId#download" else videoId
        synchronized(cache) {
            cache[cacheKey]?.takeIf { it.expiresAtMs > System.currentTimeMillis() + 30_000 }?.let { return it }
        }
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val quality =
            when {
                highQuality && !connectivity.isActiveNetworkMetered -> AudioQuality.HIGH
                highQuality -> AudioQuality.AUTO
                else -> AudioQuality.LOW
            }
        val stream =
            requireNotNull(
                bundle().extractor.extract(
                    videoId = videoId,
                    hints = ContentHints().withStreamCapabilities(allowHls = false, allowSabr = false, allowBoundedRange = !forDownload),
                    excludedClients = emptySet(),
                    audioQuality = quality,
                    clientPlaybackNonce = generateClientPlaybackNonce(),
                ),
            ) { "No playable stream for $videoId" }
        check(stream.sabrBootstrap == null) { "SABR streams are not supported" }

        val ttlSeconds =
            stream.expiresAt
                ?.let { ((it.toEpochMilliseconds() - Clock.System.now().toEpochMilliseconds()) / 1000L).toInt() }
                ?.coerceAtLeast(1)
                ?: DEFAULT_STREAM_TTL_SECONDS
        return Stream(
            url = stream.audioUrl,
            headers = stream.headers,
            expiresAtMs = System.currentTimeMillis() + ttlSeconds * 1000L,
            requireBoundedRange = stream.requireBoundedRange,
            rangeChunkSizeBytes = stream.rangeChunkSizeBytes,
            useRangeChunks = stream.useRangeChunks,
        ).also { synchronized(cache) { cache[cacheKey] = it } }
    }

    fun invalidate(videoId: String) {
        synchronized(cache) {
            cache.remove(videoId)
            cache.remove("$videoId#download")
        }
    }

    private suspend fun bundle(): Bundle {
        val transport = YouTube.extractionTransport()
        bundle?.takeIf { it.generation == transport.generation }?.let { return it }
        return mutex.withLock {
            val latest = YouTube.extractionTransport()
            bundle?.takeIf { it.generation == latest.generation }?.let { return@withLock it }
            try {
                bundle?.cipherService?.dispose()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Timber.w(error, "Old cipher disposal failed")
            }
            val remoteStore = RemotePlayerConfigStore(latest.httpClient, configRepository, logger)
            val cipherService = YouTubeCipherService(latest.httpClient, remoteStore, logger)
            val parser = YtConfigParserImpl(latest.httpClient, latest.innerTube, remoteStore, logger)
            val extractor =
                InnerTubeExtractor(
                    configParser = parser.withEmbeddedConfigFallback(),
                    cipherService = cipherService,
                    innerTube = latest.innerTube,
                    tokenProvider = tokenProvider,
                    logger = logger,
                )
            Bundle(latest.generation, cipherService, extractor).also { bundle = it }
        }
    }

    private fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(
                videoId: String,
                useLoginCookies: Boolean,
            ) = try {
                this@withEmbeddedConfigFallback.fetchConfig(videoId, useLoginCookies)
            } catch (_: IllegalStateException) {
                this@withEmbeddedConfigFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
            }
        }

    private val tokenProvider =
        object : TokenProvider {
            override val capabilities = TokenProviderCapabilities(providers = emptySet(), usesWebView = false)

            override suspend fun getPoToken(
                videoId: String,
                visitorData: String,
                cookie: String?,
            ): PoTokenResult? = null
        }

    private val configRepository: PlayerConfigRepository by lazy {
        val preferences = appContext.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)
        object : PlayerConfigRepository {
            override val enabled = true
            override val sourceUrl = PLAYER_CONFIG_URL
            override val defaultSourceUrl = PLAYER_CONFIG_URL
            override var cachedJson: String
                get() = preferences.getString("json", "").orEmpty()
                set(value) = preferences.edit { putString("json", value) }
            override var cachedAtMs: Long
                get() = preferences.getLong("cached_at_ms", 0L)
                set(value) = preferences.edit { putLong("cached_at_ms", value) }
            override var cachedSourceUrl: String
                get() = preferences.getString("source_url", "").orEmpty()
                set(value) = preferences.edit { putString("source_url", value) }
            override var cachedEtag: String
                get() = preferences.getString("etag", "").orEmpty()
                set(value) = preferences.edit { putString("etag", value) }
        }
    }

    private val logger =
        InnerTubeLogger { event ->
            val message = event.message + event.details.takeIf { it.isNotEmpty() }?.let { " $it" }.orEmpty()
            when (event.level) {
                InnerTubeLogLevel.DEBUG -> Timber.tag(event.tag).d(message)
                InnerTubeLogLevel.INFO -> Timber.tag(event.tag).i(message)
                InnerTubeLogLevel.WARN -> Timber.tag(event.tag).w(message)
                InnerTubeLogLevel.ERROR -> Timber.tag(event.tag).e(message)
            }
        }
}

/** Same bounded-range handling as Metrolist's `withResolvedStream`. */
fun DataSpec.withStream(stream: StreamResolver.Stream): DataSpec {
    val resolved =
        withUri(stream.url.toUri())
            .withRequestHeaders(httpRequestHeaders + stream.headers)
    if ((!stream.requireBoundedRange && !stream.useRangeChunks) || stream.rangeChunkSizeBytes <= 0L) return resolved
    val boundedLength =
        if (length == C.LENGTH_UNSET.toLong()) stream.rangeChunkSizeBytes else minOf(length, stream.rangeChunkSizeBytes)
    return resolved.subrange(0, boundedLength)
}
