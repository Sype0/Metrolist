/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.wear.phone

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService
import com.metrolist.wear.WearApp
import kotlinx.coroutines.runBlocking

/** Receives the phone's now-playing state even when the watch app isn't open. */
class PhoneListenerService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        val phone = WearApp.from(this).phone
        // Runs on a binder thread; items must be consumed before the buffer is released.
        runBlocking {
            events
                .filter { it.type == DataEvent.TYPE_CHANGED }
                .forEach { phone.onDataItem(it.dataItem.freeze()) }
        }
    }
}
