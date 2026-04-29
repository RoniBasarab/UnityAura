package com.unityaura.tracker

import android.content.Context
import com.google.gson.Gson
import com.unityaura.UnityAuraApplication
import com.unityaura.db.AppDatabase
import com.unityaura.db.EventDao
import com.unityaura.db.EventEntity
import com.unityaura.di.IApplicationInjector
import com.unityaura.model.Event
import com.unityaura.network.UploadApi
import com.unityaura.network.UploadResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito
import retrofit2.Response
import retrofit2.Retrofit
import java.io.File

class ConcurrentEventTrackerTest {

    private lateinit var fakeDao: FakeEventDao
    private lateinit var fakeApi: FakeUploadApi
    private lateinit var tracker: ConcurrentEventTrackerSingleton

    @Before
    fun setup() {
        fakeDao = FakeEventDao()
        fakeApi = FakeUploadApi()
        val gson = Gson()
        val tempDir = File(System.getProperty("java.io.tmpdir"), "tracker_test_${System.nanoTime()}")
        tempDir.mkdirs()
        val mockContext = Mockito.mock(Context::class.java)
        Mockito.`when`(mockContext.cacheDir).thenReturn(tempDir)
        Mockito.`when`(mockContext.applicationContext).thenReturn(mockContext)

        UnityAuraApplication.injector = FakeInjector(fakeDao, fakeApi, gson, mockContext)
        ConcurrentEventTrackerSingleton.resetInstance()
        tracker = ConcurrentEventTrackerSingleton.instance
    }

    @After
    fun teardown() {
        try { tracker.shutdown() } catch (_: Exception) {}
        ConcurrentEventTrackerSingleton.resetInstance()
    }

    @Test
    fun `flush occurs when 5 events are tracked`() = runBlocking {
        repeat(5) { i ->
            tracker.trackEvent(Event(name = "event_$i"))
        }

        delay(500)

        assertEquals(5, fakeDao.getEventCount())
    }

    @Test
    fun `flush occurs after 10 second timer`() = runBlocking {
        repeat(3) { i ->
            tracker.trackEvent(Event(name = "event_$i"))
        }

        delay(500)
        assertEquals(0, fakeDao.getEventCount())

        delay(10_500)

        assertEquals(3, fakeDao.getEventCount())
    }

    @Test
    fun `concurrent trackEvent calls are safe`() = runBlocking {
        val jobs = (0 until 100).map { i ->
            launch {
                tracker.trackEvent(Event(name = "concurrent_$i"))
            }
        }
        jobs.forEach { it.join() }

        delay(11_000)

        assertEquals(100, fakeDao.getEventCount())
    }

    @Test
    fun `metadata exceeding 100 keys is rejected`() = runBlocking {
        val largeMetadata = (1..101).associate { "key$it" to "value$it" }
        tracker.trackEvent(Event(name = "large_meta", metadata = largeMetadata))

        repeat(5) {
            tracker.trackEvent(Event(name = "normal"))
        }

        delay(500)

        assertEquals(5, fakeDao.getEventCount())
    }

    @Test
    fun `db limited to 100 events with FIFO`() = runBlocking {
        repeat(95) { i ->
            fakeDao.insertEvents(
                listOf(
                    EventEntity(
                        uuid = "pre_$i",
                        eventName = "pre_$i",
                        eventTimestamp = i.toLong(),
                        eventMetadata = "{}"
                    )
                )
            )
        }

        repeat(10) { i ->
            tracker.trackEvent(Event(name = "new_$i"))
        }

        delay(1000)

        assertTrue(fakeDao.getEventCount() <= 100)
    }

    @Test
    fun `upload succeeds and clears events`() = runBlocking {
        fakeApi.shouldSucceed = true

        repeat(5) { i ->
            tracker.trackEvent(Event(name = "upload_$i"))
        }
        delay(500)
        assertEquals(5, fakeDao.getEventCount())

        tracker.uploadFlushedEvents()

        assertEquals(0, fakeDao.getEventCount())
        assertTrue(tracker.lastUploadResult.value.contains("Success"))
    }

    @Test
    fun `upload retries 3 times on failure and keeps events`() = runBlocking {
        fakeApi.shouldSucceed = false

        repeat(5) { i ->
            tracker.trackEvent(Event(name = "fail_$i"))
        }
        delay(500)

        tracker.uploadFlushedEvents()

        assertEquals(3, fakeApi.attemptCount)
        assertEquals(5, fakeDao.getEventCount())
        assertTrue(tracker.lastUploadResult.value.contains("Failed"))
    }

    @Test
    fun `upload succeeds on retry`() = runBlocking {
        fakeApi.failUntilAttempt = 2

        repeat(5) { i ->
            tracker.trackEvent(Event(name = "retry_$i"))
        }
        delay(500)

        tracker.uploadFlushedEvents()

        assertEquals(0, fakeDao.getEventCount())
        assertTrue(tracker.lastUploadResult.value.contains("Success"))
    }

    @Test
    fun `shutdown drains remaining buffer`() = runBlocking {
        repeat(3) { i ->
            tracker.trackEvent(Event(name = "drain_$i"))
        }
        delay(200)

        tracker.shutdown()

        assertEquals(3, fakeDao.getEventCount())
    }

    @Test
    fun `trackEvent after shutdown does not crash`() = runBlocking {
        tracker.shutdown()

        tracker.trackEvent(Event(name = "after_shutdown"))
        delay(200)

        assertEquals(0, fakeDao.getEventCount())
    }

    @Test
    fun `each event gets unique uuid`() = runBlocking {
        repeat(10) { i ->
            tracker.trackEvent(Event(name = "uuid_$i"))
        }
        delay(1000)

        val allEvents = fakeDao.getAllEvents()
        val uuids = allEvents.map { it.uuid }.toSet()
        assertEquals(allEvents.size, uuids.size)
    }

    @Test
    fun `duplicate uuid insert is ignored`() = runBlocking {
        val entity = EventEntity(
            uuid = "fixed-uuid",
            eventName = "test",
            eventTimestamp = 1L,
            eventMetadata = "{}"
        )
        fakeDao.insertEvents(listOf(entity))
        fakeDao.insertEvents(listOf(entity))

        assertEquals(1, fakeDao.getEventCount())
    }

    // --- Fakes ---

    class FakeInjector(
        override val eventDao: EventDao,
        override val uploadApi: UploadApi,
        override val gson: Gson,
        override val context: Context
    ) : IApplicationInjector {
        override val retrofit: Retrofit get() = throw UnsupportedOperationException()
        override val database: AppDatabase get() = throw UnsupportedOperationException()
        override val eventTracker: IEventTracker get() = throw UnsupportedOperationException()
    }

    class FakeEventDao : EventDao {
        private val events = mutableListOf<EventEntity>()
        private val countFlow = MutableStateFlow(0)

        override suspend fun insertEvents(events: List<EventEntity>) {
            synchronized(this.events) {
                for (event in events) {
                    if (this.events.none { it.uuid == event.uuid }) {
                        this.events.add(event)
                    }
                }
                countFlow.value = this.events.size
            }
        }

        override suspend fun getAllEvents(): List<EventEntity> {
            synchronized(events) {
                return events.sortedBy { it.eventTimestamp }.toList()
            }
        }

        override suspend fun getEventCount(): Int {
            synchronized(events) {
                return events.size
            }
        }

        override suspend fun deleteOldestEvents(count: Int) {
            synchronized(events) {
                val sorted = events.sortedBy { it.eventTimestamp }
                val toRemove = sorted.take(count).map { it.uuid }.toSet()
                events.removeAll { it.uuid in toRemove }
                countFlow.value = events.size
            }
        }

        override suspend fun deleteEventsByUuids(uuids: List<String>) {
            synchronized(events) {
                val uuidSet = uuids.toSet()
                events.removeAll { it.uuid in uuidSet }
                countFlow.value = events.size
            }
        }

        override fun observeEventCount(): Flow<Int> = countFlow
    }

    class FakeUploadApi : UploadApi {
        var shouldSucceed: Boolean = true
        var attemptCount: Int = 0
        var failUntilAttempt: Int = 0

        override suspend fun uploadFile(file: MultipartBody.Part): Response<UploadResponse> {
            attemptCount++
            if (failUntilAttempt > 0 && attemptCount < failUntilAttempt) {
                return Response.error(500, "Server Error".toResponseBody(null))
            }
            return if (shouldSucceed || (failUntilAttempt > 0 && attemptCount >= failUntilAttempt)) {
                Response.success(UploadResponse("events.gz", "f3a5.gz", "https://api.escuelajs.co/api/v1/files/f3a5.gz"))
            } else {
                Response.error(500, "Server Error".toResponseBody(null))
            }
        }
    }
}