/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.offline

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * Media3's `LeastRecentlyUsedCacheEvictor`, but the limit can change while the cache is open, so
 * the size setting applies without restarting the app. The callbacks run under the cache's lock.
 */
@UnstableApi
class ResizableLruEvictor(
    maxBytes: Long,
) : CacheEvictor {
    private val spans = TreeSet<CacheSpan>(compareBy<CacheSpan> { it.lastTouchTimestamp }.thenComparing { a, b -> a.compareTo(b) })
    private var cache: Cache? = null

    @Volatile
    var maxBytes: Long = maxBytes
        private set

    /** Bytes currently held by the cache. */
    @Volatile
    var currentBytes: Long = 0
        private set

    fun resize(newMaxBytes: Long) {
        maxBytes = newMaxBytes
        val cache = cache ?: return
        // SimpleCache's methods synchronize on the cache itself.
        synchronized(cache) { evict(cache, 0) }
    }

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(
        cache: Cache,
        key: String,
        position: Long,
        length: Long,
    ) {
        this.cache = cache
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(
        cache: Cache,
        span: CacheSpan,
    ) {
        this.cache = cache
        spans += span
        currentBytes += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(
        cache: Cache,
        span: CacheSpan,
    ) {
        spans -= span
        currentBytes -= span.length
    }

    override fun onSpanTouched(
        cache: Cache,
        oldSpan: CacheSpan,
        newSpan: CacheSpan,
    ) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evict(
        cache: Cache,
        requiredBytes: Long,
    ) {
        while (currentBytes + requiredBytes > maxBytes && spans.isNotEmpty()) {
            cache.removeSpan(spans.first())
        }
    }
}
