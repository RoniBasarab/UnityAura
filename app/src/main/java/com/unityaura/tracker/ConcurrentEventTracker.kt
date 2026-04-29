package com.unityaura.tracker

import android.content.Context
import com.google.gson.Gson
import com.unityaura.db.EventDao
import com.unityaura.db.EventEntity
import com.unityaura.model.Event
import com.unityaura.network.UploadApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.GZIPOutputStream

class ConcurrentEventTracker(
    private val eventDao: EventDao,
    private val uploadApi: UploadApi,
    private val gson: Gson,
    private val context: Context
) : IEventTracker {

    companion object {
        private const val FLUSH_THRESHOLD = 5
        private const val FLUSH_INTERVAL_MS = 10_000L
        private const val MAX_DB_EVENTS = 100
        private const val MAX_METADATA_KEYS = 100
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val SHUTDOWN_TIMEOUT_MS = 5_000L
    }

    // Channel(UNLIMITED) ensures trySend() never fails due to backpressure.
    // This is superior to synchronized because:
    // 1. trySend() is non-blocking — safe to call from the main thread without causing UI jank
    // 2. The channel naturally serializes access to eventBuffer via a single consumer coroutine
    // 3. No risk of coroutine starvation from thread-blocking locks (unlike synchronized)
    private val eventChannel = Channel<Event>(Channel.UNLIMITED)

    private val eventBuffer = mutableListOf<Event>()

    // Mutex protects flushBuffer() from concurrent execution by both the
    // volume-based trigger (processor coroutine) and the time-based trigger (timer coroutine).
    // Unlike synchronized, Mutex suspends instead of blocking, preserving cooperative scheduling.
    private val bufferMutex = Mutex()

    private val trackerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var flushTimerJob: Job? = null
    private val processorComplete = CompletableDeferred<Unit>()

    private val mutableEventCount = MutableStateFlow(0)
    override val eventCount: StateFlow<Int> = mutableEventCount.asStateFlow()

    private val mutableIsUploading = MutableStateFlow(false)
    override val isUploading: StateFlow<Boolean> = mutableIsUploading.asStateFlow()

    private val mutableLastFlushTime = MutableStateFlow(0L)
    override val lastFlushTime: StateFlow<Long> = mutableLastFlushTime.asStateFlow()

    private val mutableLastUploadResult = MutableStateFlow("Not attempted")
    override val lastUploadResult: StateFlow<String> = mutableLastUploadResult.asStateFlow()

    init {
        startEventProcessor()
        startFlushTimer()
        trackerScope.launch {
            mutableEventCount.value = eventDao.getEventCount()
        }
    }

    override fun trackEvent(event: Event) {
        if (event.metadata.size > MAX_METADATA_KEYS) {
            return
        }
        eventChannel.trySend(event)
    }

    override fun shutdown() {
        eventChannel.close()
        flushTimerJob?.cancel()
        runBlocking {
            withTimeout(SHUTDOWN_TIMEOUT_MS) {
                processorComplete.await()
            }
        }
        trackerScope.cancel()
    }

    override suspend fun uploadFlushedEvents() {
        mutableIsUploading.value = true
        try {
            val events = eventDao.getAllEvents()
            if (events.isEmpty()) {
                mutableLastUploadResult.value = "No events to upload"
                return
            }

            val uploadedUuids = events.map { it.uuid }
            val jsonArray = gson.toJson(events)

            val tempJsonFile = File(context.cacheDir, "events_upload.json")
            tempJsonFile.writeText(jsonArray)

            val gzipFile = File(context.cacheDir, "events_upload.json.gz")
            FileOutputStream(gzipFile).use { fos ->
                GZIPOutputStream(fos).use { gzipOs ->
                    tempJsonFile.inputStream().use { input ->
                        input.copyTo(gzipOs)
                    }
                }
            }

            var lastError: Exception? = null
            for (attempt in 1..MAX_RETRY_ATTEMPTS) {
                try {
                    val requestBody = gzipFile.asRequestBody("application/gzip".toMediaType())
                    val part = MultipartBody.Part.createFormData(
                        "file",
                        gzipFile.name,
                        requestBody
                    )
                    val response = uploadApi.uploadFile(part)
                    if (response.isSuccessful) {
                        eventDao.deleteEventsByUuids(uploadedUuids)
                        mutableEventCount.value = eventDao.getEventCount()
                        mutableLastUploadResult.value = "Success (${uploadedUuids.size} events)"
                        return
                    } else {
                        lastError = Exception("HTTP ${response.code()}")
                    }
                } catch (e: Exception) {
                    lastError = e
                }
            }

            mutableLastUploadResult.value =
                "Failed after $MAX_RETRY_ATTEMPTS attempts: ${lastError?.message}"
        } finally {
            mutableIsUploading.value = false
            File(context.cacheDir, "events_upload.json").delete()
            File(context.cacheDir, "events_upload.json.gz").delete()
        }
    }

    private fun startEventProcessor() {
        trackerScope.launch {
            try {
                for (event in eventChannel) {
                    eventBuffer.add(event)
                    if (eventBuffer.size >= FLUSH_THRESHOLD) {
                        flushBuffer()
                        resetFlushTimer()
                    }
                }
                flushBuffer()
            } finally {
                processorComplete.complete(Unit)
            }
        }
    }

    private fun startFlushTimer() {
        flushTimerJob = trackerScope.launch {
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushBuffer()
            }
        }
    }

    private fun resetFlushTimer() {
        flushTimerJob?.cancel()
        startFlushTimer()
    }

    private suspend fun flushBuffer() {
        bufferMutex.withLock {
            if (eventBuffer.isEmpty()) return

            val eventsToFlush = eventBuffer.toList()
            eventBuffer.clear()

            val entities = eventsToFlush.map { event ->
                EventEntity(
                    uuid = UUID.randomUUID().toString(),
                    eventName = event.name,
                    eventTimestamp = event.timestamp,
                    eventMetadata = gson.toJson(event.metadata)
                )
            }

            val currentCount = eventDao.getEventCount()
            val overflow = (currentCount + entities.size) - MAX_DB_EVENTS
            if (overflow > 0) {
                eventDao.deleteOldestEvents(overflow)
            }

            eventDao.insertEvents(entities)
            mutableLastFlushTime.value = System.currentTimeMillis()
            mutableEventCount.value = eventDao.getEventCount()
        }
    }
}
