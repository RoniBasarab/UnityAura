# ConcurrentEventTracker SDK

A thread-safe, high-concurrency telemetry SDK for Android.
Events are buffered in memory, persisted to a Room database, and uploaded as compressed (GZIP) payloads via Retrofit.

---

## Architecture

### Concurrency Model: Channel + Mutex

The SDK uses a Channel + Mutex strategy. Here is why other approaches were ruled out:

| Approach          | Why Not                                                                                      |
|-------------------|----------------------------------------------------------------------------------------------|
| `synchronized`    | Blocks the calling thread. Causes UI jank on the main thread and risks coroutine starvation. |
| `Mutex` alone     | `trackEvent()` is non-suspend. Using a Mutex would require `runBlocking`, which blocks the thread. |
| Channel + Mutex   | `trySend()` is non-blocking and returns immediately. A single consumer coroutine serializes buffer access naturally. The Mutex only guards `flushBuffer()` against concurrent calls from the volume trigger and the timer trigger. |

Event flow:

1. `trackEvent()` calls `channel.trySend(event)` — non-blocking, safe from any thread
2. A single background coroutine consumes the channel and appends to `eventBuffer`
3. When the buffer reaches 5 events OR 10 seconds elapse, `flushBuffer()` is called
4. `flushBuffer()` acquires a Mutex, converts events to Room entities with SDK-generated UUIDs, enforces the 100-event DB cap, and inserts into Room
5. `uploadFlushedEvents()` reads from Room, serializes to JSON, compresses with GZIP, and POSTs via Retrofit multipart

---

### Dependency Injection: Manual Service Locator

Uses a manual service locator pattern (`IApplicationInjector` / `ApplicationInjector`) instead of Hilt, Koin, or Dagger.

- All dependencies are `by lazy` properties — singleton behavior with deferred initialization
- Initialized in `UnityAuraApplication.onCreate()`
- Accessed statically via `UnityAuraApplication.injector`

Reasons for this approach:

- Zero external DI framework dependency
- Explicit, readable dependency graph
- Easy to test by swapping the injector interface with fakes

---

### UI: MVI Pattern

- `MainState` — `@Stable` data class holding all UI state
- `MainScreenActions` — Sealed interface for user intents
- `MainViewModel` — Collects tracker StateFlows into a single `MutableStateFlow<MainState>`
- `MainScreen` — Stateless Compose function, observes state via `collectAsStateWithLifecycle()`

---

## Constraints

### Metadata Key Limit (100 keys)

Each `Event` accepts a `metadata: Map<String, String>`. Events with more than 100 metadata keys are silently dropped.
This prevents callers from passing large maps that would bloat memory (in the buffer) and disk (in Room).

### Database Event Limit (100 events)

Room stores a maximum of 100 events. When a flush would exceed the cap, the oldest events are evicted first (FIFO).
This prevents unbounded disk growth when `uploadFlushedEvents()` is never called.
The limit is enforced atomically inside the mutex-protected `flushBuffer()`.

### Retry Policy

Upload attempts are retried up to 3 times. If all attempts fail, events remain in Room and are retried on the next `uploadFlushedEvents()` call.

---

## Deduplication

A `UUID.randomUUID()` is generated for each event at flush time, serving as the Room `@PrimaryKey`.

- **Crash safety** — If the app crashes after a successful upload but before `deleteEventsByUuids()` completes, the events remain in Room. On the next upload they are sent again, but the server can deduplicate using the UUIDs.
- **Re-flush safety** — `OnConflictStrategy.IGNORE` on `insertEvents()` silently ignores duplicate UUIDs, preventing crashes or duplicate rows.
- **Targeted deletion** — After a successful upload, only the specific uploaded UUIDs are deleted. New events that arrived during the upload are preserved.

---

## Build & Run

```bash
# Build
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run instrumented tests (requires emulator or device)
./gradlew connectedAndroidTest
```

---

## Testing

### Unit Tests (`ConcurrentEventTrackerTest`)

Uses fake implementations of `EventDao` and `UploadApi` to cover:

- Volume-based flush (5 events)
- Timer-based flush (10 seconds)
- Concurrent `trackEvent` safety (100 simultaneous calls)
- Metadata key limit enforcement
- FIFO database eviction at 100 events
- Upload success with event cleanup
- Upload retry (3 attempts) with events preserved on failure
- Shutdown buffer drain
- Post-shutdown `trackEvent` safety
- UUID uniqueness
- Duplicate insert handling

### Integration Tests (`MainScreenTest`)

Compose UI tests covering:

- Initial state display
- Button enable/disable logic
- Track event input clearing
- Shutdown state propagation

---

## Edge Cases

| Edge Case                          | Status        | Notes                                                                                  |
|------------------------------------|---------------|----------------------------------------------------------------------------------------|
| Crash after upload, before DB delete | Handled     | UUIDs enable server-side deduplication; `IGNORE` prevents local duplicates             |
| `trackEvent` after `shutdown()`    | Handled       | `trySend` on a closed channel returns failure silently, no crash                       |
| Upload with no events in DB        | Handled       | Returns "No events to upload" gracefully                                               |
| Metadata > 100 keys                | Handled       | Event is silently dropped                                                              |
| DB exceeds 100 events              | Handled       | FIFO eviction of oldest events                                                         |
| Disk full during Room write        | Out of scope  | Room throws `IOException`; buffered events are lost                                    |
| Extreme network throttling         | Out of scope  | 3 retries then fails; events remain in DB for next attempt                             |
| Main thread `shutdown()` call      | Documented    | `runBlocking` in shutdown may cause a brief ANR; recommend calling from background     |
| Multiple simultaneous uploads      | Not prevented | Second call uploads same events; UUID-based dedup prevents true duplication            |
| `onTerminate()` reliability        | Documented    | `Application.onTerminate()` is not reliably called on real devices; shutdown is manual |

---

## If I Had More Time

Given more time, I would revisit the overall complexity of the implementation:

- Look for ways to simplify the concurrency model where possible — the Channel + Mutex combination works well, but some of the surrounding scaffolding (timer management, processor completion tracking) could likely be expressed more concisely using higher-level coroutine primitives.
- Reduce boilerplate in the DI layer — the manual service locator gets the job done, but it requires maintaining a parallel interface (`IApplicationInjector`) that mirrors every dependency. This could be trimmed.
- Audit the StateFlow plumbing in `MainViewModel` — each tracker state is collected and re-emitted individually, which adds noise. A single combined flow or `combine` operator could replace several nearly-identical `launch` blocks.
- General cleanup pass to remove any abstractions that are not pulling their weight and to make the code easier to read at a glance.

---

## TODO

### MQTT Broker Integration

A natural extension of this SDK would be the ability to ingest events from an external source via an MQTT broker, rather than only accepting events from within the app itself.

The rough plan:

1. Stand up (or connect to) an MQTT broker that external systems can publish events to
2. Add an MQTT client library to the app (e.g., Eclipse Paho for Android)
3. Implement a listener/subscriber that connects to the broker on app start and subscribes to the relevant topic(s)
4. On each incoming MQTT message, deserialize the payload into an `Event` object and call `trackEvent()` — this drops it into the existing Channel pipeline with no additional concurrency concerns
5. The existing flush and upload logic handles the rest: events accumulate in Room and are uploaded in compressed batches as normal

This would make the SDK a unified collection point for both in-app telemetry and externally published events, with a single upload path.

## Brief summary of LLM usage
As discussed in our interview via Zoom, i used a task.md file together with planning.md file that my Claude Code generated.
My only prompt was "execute task.md" in which I described the entire plan, together with architecture, coding style syntax, making sure the agent asks me atleast 4-5 questions to get a better understanding of the tasks.
After generating a planning.md i manually reviewed it, changed what I wanted and needed and edited the task.md file again, inserting the prompt "execute task.md" once again untill I was satisfied with his results and applied all code changes.