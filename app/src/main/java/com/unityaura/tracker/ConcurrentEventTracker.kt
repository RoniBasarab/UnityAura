package com.unityaura.tracker

import com.unityaura.model.Event


/**  Public API   **/
class ConcurrentEventTracker {

    fun trackEvent(event: Event) {
        ConcurrentEventTrackerSingleton.instance.trackEvent(event)
    }

    fun shutdown() {
        ConcurrentEventTrackerSingleton.instance.shutdown()
    }

    suspend fun uploadFlushedEvents() {
        ConcurrentEventTrackerSingleton.instance.uploadFlushedEvents()
    }
}