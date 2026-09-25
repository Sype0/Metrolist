/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.offline

import android.content.Context
import android.util.AtomicFile
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadRequest
import androidx.media3.exoplayer.offline.DownloadService
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.utils.completed
import com.metrolist.wear.WearPrefs
import com.metrolist.wear.playback.Song
import com.metrolist.wear.playback.thumbnailUrl
import com.metrolist.wear.playback.toSong
import com.metrolist.wear.youtube.StreamResolver
import com.metrolist.wear.youtube.withStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.util.concurrent.Executor

/**
 * Songs kept on the watch: downloads (never evicted) and the player cache (least recently played
 * songs go first once it is full). Both are Media3 caches keyed by video id, so the player reads a
 * song from either without touching the network.
 */
@UnstableApi
class OfflineStore(
    private val context: Context,
    private val prefs: WearPrefs,
    private val scope: CoroutineScope,
) {
    private val databaseProvider = StandaloneDatabaseProvider(context)
    private val evictor = ResizableLruEvictor(prefs.maxCacheMb * MB)
    private val httpClient = OkHttpClient()
    private val songs = SongStore(File(context.filesDir, "songs.json"))
    private val artworkDir = File(context.filesDir, "artwork")

    /** Songs streamed while playing; kept in the directory the player cache has always used. */
    val playerCache: Cache = SimpleCache(File(context.cacheDir, "media"), evictor, databaseProvider)

    val downloadCache: Cache = SimpleCache(File(context.filesDir, "download"), NoOpCacheEvictor(), databaseProvider)

    private val _downloads = MutableStateFlow<Map<String, Download>>(emptyMap())

    /** Every download the manager knows about, by video id, whatever its state. */
    val downloads: StateFlow<Map<String, Download>> = _downloads.asStateFlow()

    private val downloadDataSource =
        ResolvingDataSource.Factory(OkHttpDataSource.Factory(httpClient)) { dataSpec ->
            val videoId = dataSpec.key ?: dataSpec.uri.toString()
            val stream = runBlocking(Dispatchers.IO) { StreamResolver.resolve(videoId, prefs.highQualityAudio, forDownload = true) }
            // The downloader reads to the end of whatever it is given, so only chunk when the stream demands it.
            if (stream.requireBoundedRange) {
                dataSpec.withStream(stream)
            } else {
                dataSpec.withUri(stream.url.toUri()).withRequestHeaders(dataSpec.httpRequestHeaders + stream.headers)
            }
        }

    val notificationHelper = DownloadNotificationHelper(context, WearDownloadService.CHANNEL_ID)

    val downloadManager =
        DownloadManager(context, databaseProvider, downloadCache, downloadDataSource, Executor(Runnable::run)).apply {
            maxParallelDownloads = 2
            addListener(
                object : DownloadManager.Listener {
                    override fun onDownloadChanged(
                        downloadManager: DownloadManager,
                        download: Download,
                        finalException: Exception?,
                    ) {
                        val id = download.request.id
                        if (download.state == Download.STATE_FAILED) {
                            Timber.w(finalException, "Download of $id failed")
                            StreamResolver.invalidate(id)
                        }
                        _downloads.update { it + (id to download) }
                        if (download.state == Download.STATE_COMPLETED) {
                            // The download holds the whole song now; free its copy in the player cache.
                            scope.launch(Dispatchers.IO) { runCatching { playerCache.removeResource(id) } }
                        }
                    }

                    override fun onDownloadRemoved(
                        downloadManager: DownloadManager,
                        download: Download,
                    ) {
                        _downloads.update { it - download.request.id }
                    }
                },
            )
        }

    init {
        val existing = HashMap<String, Download>()
        downloadManager.downloadIndex.getDownloads().use { cursor ->
            while (cursor.moveToNext()) existing[cursor.download.request.id] = cursor.download
        }
        _downloads.value = existing
        // Normally DownloadService resumes the manager; resuming here lets queued downloads run
        // even when the service couldn't be started from the background.
        downloadManager.resumeDownloads()
    }

    fun isDownloaded(id: String) = _downloads.value[id]?.state == Download.STATE_COMPLETED

    /** Queues [songs] for download, skipping any already downloaded or on their way. Call on the main thread. */
    fun download(songs: List<Song>) {
        val current = _downloads.value
        val toAdd =
            songs.distinctBy { it.id }.filter {
                current[it.id]?.state !in ACTIVE_OR_DONE
            }
        if (toAdd.isEmpty()) return
        toAdd.forEach { song ->
            val stored = record(song)
            downloadManager.addDownload(
                DownloadRequest
                    .Builder(song.id, song.id.toUri())
                    .setCustomCacheKey(song.id)
                    .setData(stored.toJson().toString().toByteArray())
                    .build(),
            )
        }
        // Runs the downloads as a foreground service with a progress notification when allowed.
        runCatching { DownloadService.start(context, WearDownloadService::class.java) }
            .onFailure { Timber.w(it, "Download service not started; downloading in-process") }
        scope.launch(Dispatchers.IO) { toAdd.forEach { saveArtwork(it) } }
    }

    fun download(song: Song) = download(listOf(song))

    fun removeDownload(id: String) = downloadManager.removeDownload(id)

    /** Tap on a download button: download (or retry), or cancel / delete what is there. Returns whether it now downloads. */
    fun toggleDownload(song: Song): Boolean {
        if (_downloads.value[song.id]?.state in ACTIVE_OR_DONE) {
            removeDownload(song.id)
            return false
        }
        download(song)
        return true
    }

    /** Remembers what a played or downloaded song is, so offline lists can show it. */
    fun record(song: Song): Song {
        val stored = songs.put(song)
        if (!artworkFile(song.id).exists()) scope.launch(Dispatchers.IO) { saveArtwork(stored) }
        return stored
    }

    fun record(item: MediaItem) {
        val metadata = item.mediaMetadata
        record(
            Song(
                id = item.mediaId,
                title = metadata.title?.toString().orEmpty(),
                artist = metadata.artist?.toString().orEmpty(),
                album = metadata.albumTitle?.toString(),
                thumbnail = metadata.artworkUri?.toString(),
            ),
        )
    }

    /** Completed downloads, newest first. */
    fun downloadedSongs(): List<Song> =
        _downloads.value.values
            .filter { it.state == Download.STATE_COMPLETED }
            .sortedByDescending { it.startTimeMs }
            .mapNotNull { it.song() }

    /** Downloads still queued, running or failed, newest first. */
    fun pendingDownloads(): List<Download> =
        downloadManager.currentDownloads
            .filter { it.state != Download.STATE_COMPLETED }
            .sortedByDescending { it.startTimeMs }

    fun Download.song(): Song? =
        runCatching { songFromJson(JSONObject(String(request.data))) }.getOrNull()?.withLocalArtwork()

    /** Fully cached songs that aren't downloads, most recently played first. */
    suspend fun cachedSongs(): List<Song> =
        withContext(Dispatchers.IO) {
            val downloaded = _downloads.value.filterValues { it.state == Download.STATE_COMPLETED }.keys
            playerCache.keys
                .filter { it !in downloaded && isFullyCached(playerCache, it) }
                .map { it to playerCache.getCachedSpans(it).maxOfOrNull { span -> span.lastTouchTimestamp } }
                .sortedByDescending { it.second ?: 0L }
                .mapNotNull { (id, _) -> songs[id]?.withLocalArtwork() }
        }

    /** Whether the player can serve this range from the watch; [length] may be unset (-1). */
    fun isCached(
        id: String,
        position: Long,
        length: Long,
    ) = isCached(downloadCache, id, position, length) || isCached(playerCache, id, position, length)

    private fun isCached(
        cache: Cache,
        id: String,
        position: Long,
        length: Long,
    ): Boolean {
        val required =
            if (length >= 0) {
                length
            } else {
                val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(id))
                if (contentLength <= 0) return false
                (contentLength - position).coerceAtLeast(1)
            }
        return cache.isCached(id, position, required)
    }

    private fun isFullyCached(
        cache: Cache,
        id: String,
    ): Boolean {
        val contentLength = ContentMetadata.getContentLength(cache.getContentMetadata(id))
        return contentLength > 0 && cache.isCached(id, 0, contentLength)
    }

    val cacheUsedBytes: Long get() = playerCache.cacheSpace

    val downloadsUsedBytes: Long get() = downloadCache.cacheSpace

    fun setMaxCacheMb(mb: Int) {
        prefs.maxCacheMb = mb
        scope.launch(Dispatchers.IO) { evictor.resize(mb * MB) }
    }

    /** With auto-download on, fetches the liked songs and downloads the ones not on the watch yet. */
    fun syncLikedSongs() {
        if (!prefs.autoDownloadLiked || YouTube.cookie == null) return
        scope.launch {
            val liked =
                withContext(Dispatchers.IO) {
                    YouTube.playlist("LM").completed().map { page -> page.songs.map { it.toSong() } }
                }.getOrElse {
                    Timber.w(it, "Couldn't load liked songs for auto-download")
                    return@launch
                }
            download(liked)
        }
    }

    /** A song was just liked on the watch. */
    fun onLiked(song: Song) {
        if (prefs.autoDownloadLiked) download(song)
    }

    /** Forgets songs (and their artwork) that are no longer in either cache. */
    fun prune() {
        scope.launch(Dispatchers.IO) {
            val keep = playerCache.keys + downloadCache.keys + _downloads.value.keys
            songs.retain(keep)
            artworkDir.listFiles()?.forEach { if (it.nameWithoutExtension !in keep) it.delete() }
        }
    }

    private fun artworkFile(id: String) = File(artworkDir, "$id.jpg")

    private fun Song.withLocalArtwork(): Song =
        artworkFile(id).takeIf { it.exists() }?.let { copy(thumbnail = it.toUri().toString()) } ?: this

    /** Keeps a watch-sized cover next to the audio so lists and the player show it offline too. */
    private fun saveArtwork(song: Song) {
        val url = thumbnailUrl(song.thumbnail)?.takeIf { it.startsWith("http") } ?: return
        val file = artworkFile(song.id)
        if (file.exists()) return
        runCatching {
            httpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
                val body = response.body?.takeIf { response.isSuccessful } ?: return
                artworkDir.mkdirs()
                val partial = File(artworkDir, "${song.id}.part")
                partial.outputStream().use { out -> body.byteStream().copyTo(out) }
                partial.renameTo(file)
            }
        }.onFailure { Timber.w(it, "Artwork for ${song.id} not saved") }
    }

    companion object {
        const val MB = 1024L * 1024
        val CACHE_SIZES_MB = listOf(64, 128, 256, 512, 1024, 2048)
        private val ACTIVE_OR_DONE =
            setOf(Download.STATE_COMPLETED, Download.STATE_QUEUED, Download.STATE_DOWNLOADING, Download.STATE_RESTARTING)
    }
}

/** Title, artist and cover of songs on the watch, persisted as a small JSON file. */
private class SongStore(
    file: File,
) {
    private val file = AtomicFile(file)
    private val songs: LinkedHashMap<String, Song> by lazy { load() }

    @Synchronized
    operator fun get(id: String): Song? = songs[id]

    /** Stores [song]; a local file cover never replaces the remote one, which is what the store keeps. */
    @Synchronized
    fun put(song: Song): Song {
        val existing = songs[song.id]
        val merged =
            if (existing != null && song.thumbnail?.startsWith("http") != true) song.copy(thumbnail = existing.thumbnail) else song
        if (merged != existing) {
            songs[song.id] = merged
            save()
        }
        return merged
    }

    @Synchronized
    fun retain(ids: Set<String>) {
        if (songs.keys.retainAll(ids)) save()
    }

    private fun load(): LinkedHashMap<String, Song> {
        val result = LinkedHashMap<String, Song>()
        runCatching {
            val array = JSONArray(String(file.readFully()))
            for (i in 0 until array.length()) songFromJson(array.getJSONObject(i)).let { result[it.id] = it }
        }
        return result
    }

    private fun save() {
        val array = JSONArray()
        songs.values.forEach { array.put(it.toJson()) }
        val stream = file.startWrite()
        try {
            stream.write(array.toString().toByteArray())
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            Timber.w(e, "Song store not saved")
        }
    }
}

private fun Song.toJson() =
    JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .putOpt("album", album)
        .putOpt("thumbnail", thumbnail)

private fun songFromJson(json: JSONObject) =
    Song(
        id = json.getString("id"),
        title = json.optString("title"),
        artist = json.optString("artist"),
        album = json.optString("album").takeIf { json.has("album") },
        thumbnail = json.optString("thumbnail").takeIf { json.has("thumbnail") },
    )
