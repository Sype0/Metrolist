/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.wear

import com.metrolist.music.playback.MusicService

/** Wear OS sync needs Play services, so this flavor ships a no-op. */
@Suppress("UNUSED_PARAMETER")
class WearSync(
    service: MusicService,
) {
    fun start() = Unit

    fun release() = Unit
}
