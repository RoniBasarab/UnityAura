package com.unityaura.tracker

import com.unityaura.model.Event
import kotlinx.coroutines.flow.StateFlow

interface IEventTracker {

    fun trackEvent(event: Event)

    fun shutdown()

    suspend fun uploadFlushedEvents()

    val eventCount: StateFlow<Int>

    val isUploading: StateFlow<Boolean>

    val lastFlushTime: StateFlow<Long>

    val lastUploadResult: StateFlow<String>
}
