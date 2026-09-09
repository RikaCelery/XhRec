# XhRec Mock Integration Tests Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `subagent-driven-development` or `executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build deterministic in-process tests for room operations, status-driven recording, WebSocket delivery, polling fallback, and exact downloaded file contents using one local Ktor mock server.

**Architecture:** Production XhRec components run in the test process with instance-scoped network and timing dependencies. One loopback Ktor server provides platform HTTP, WebSocket, playlists, and deterministic segments; XhRec control routes run through Ktor `testApplication`.

**Tech Stack:** Kotlin 2.3, Ktor 3.5, kotlinx.coroutines-test, JUnit Jupiter, Gradle.

---

## File map

- `build.gradle.kts`: Ktor WebSocket/test-host dependencies.
- `src/main/kotlin/github/rikacelery/v3/api/ApiClient.kt`: instance-scoped platform client.
- `src/main/kotlin/github/rikacelery/v3/utils/HttpClientProvider.kt`: HTTP-client boundary.
- `src/main/kotlin/github/rikacelery/v3/data/RuntimeTuning.kt`: production timing defaults.
- `src/main/kotlin/github/rikacelery/v3/components/HttpServerComponent.kt`: routes shared with tests.
- Room, LiveEventSource, Scheduler, Session, Downloader, and Writer components: injected endpoints, clients, timings, and output threshold.
- `MockPayloads.kt`: deterministic playlist tokens and segment bytes.
- `MockPlatformServer.kt`: the single HTTP/WS mock server.
- `XhrecIntegrationFixture.kt`: real component graph, probes, waits, and cleanup.
- RoomRoutes, StatusFlow, WebSocketFallback, and DownloadIntegrity integration test classes.

## TDD tasks

### Task 1: Configurable Writer threshold

**Files:** modify `WriterComponent.kt`; create `WriterSmallFileTest.kt`.

- [x] Write failing tests that send StreamStart, short StreamData, and StreamEnd: threshold 0 retains exact bytes; threshold 1024 deletes 1023 bytes.
- [x] Run `./gradlew test --tests '*WriterSmallFileTest'`; expect compilation failure because the parameter is absent.
- [x] Add `private val minOutputBytes: Long = 1024` and use it instead of the literal in `closeActiveFile`.
- [x] Run focused and full tests; expect all to pass.
- [x] Commit as `test: make writer small-file policy configurable`.

```kotlin
val file = record("mock-segment".encodeToByteArray(), minOutputBytes = 0)
assertContentEquals("mock-segment".encodeToByteArray(), file.readBytes())
```

### Task 2: Instance-scoped network and timing dependencies

**Files:** create `HttpClientProvider.kt` and `RuntimeTuning.kt`; modify ApiClient, ConfigComponent, Main, and callers/tests.

- [x] Write failing tests proving two ApiClient instances keep independent hosts and RuntimeTuning preserves current production values.
- [x] Run focused tests; expect missing constructor/types.
- [x] Add this boundary; the default implementation delegates to ClientManager:

```kotlin
interface HttpClientProvider {
    fun direct(key: String, http1: Boolean = false, expectSuccess: Boolean = true): HttpClient
    fun proxied(key: String, http1: Boolean = false, expectSuccess: Boolean = true): HttpClient
}
```

- [x] Convert ApiClient to an instance accepting initial hosts, provider, and base-URL builder. Default remains HTTPS. Construct and share one production instance in Main/Config/Room/Scheduler/LiveEvent/Bootstrap.
- [x] Add timings: room poll 5m, debounce 1500ms, WS reconnect 1s..30s, preconfig 15s, playlist poll 3s/fetch 10s, restart 500ms, downloader race 8s/attempt 25s/deadline 120s/stall 5s/backoff 500ms.
- [x] Run focused tests, compilation, and full tests; expect all to pass.
- [x] Commit as `refactor: make network clients and timing injectable`.

### Task 3: Inject live-recording boundaries

**Files:** modify Room, LiveEventSource, Scheduler, Session, and Downloader; create `RuntimeInjectionTest.kt`.

- [x] Write failing construction tests using a recording provider, short timings, loopback WS URL builder, and loopback master candidates.
- [x] Run focused tests; expect constructor/API failures.
- [x] Replace only existing literals: Room poll/debounce; WS client/URL/backoff; Scheduler client/master/preconfig; Session client/playlist; Downloader client/failure timings. Keep production defaults.
- [x] Assert exact loopback URLs are used and ClientManager is not reached.
- [x] Run focused/full tests; commit as `refactor: inject recording network and timing boundaries`.

```kotlin
val wsUrl: (String) -> String = { "wss://$it/connection/websocket" }
val masterUrls: (Long, String, String?) -> List<String> = ::productionMasterUrls
```

### Task 4: Share production HTTP routes

**Files:** modify `build.gradle.kts` and HttpServerComponent; create `HttpRoutesTest.kt`.

- [x] Add `ktor-server-websockets-jvm:3.5.2` and test dependency `ktor-server-test-host-jvm:3.5.2`.
- [x] Write failing testApplication test: install production routes, call active `/add`, prove AddRoom precedes ActivateRecordingCmd.
- [x] Run it; expect failure because routes are private inside `start()`.
- [x] Extract `installApplication(Application, stopEngine)`; production supplies real shutdown, tests no-op. Keep Netty/TLS/keystore in start. Inject restart delay.
- [x] Run focused/full tests; commit as `refactor: expose shared XhRec control routes`.

```kotlin
application { server.installApplication(this, stopEngine = {}) }
assertEquals(HttpStatusCode.OK, client.get("/add?name=model&active=true").status)
assertEquals(listOf(AddRoom::class, ActivateRecordingCmd::class), commandTypes)
```

### Task 5: Deterministic single mock server

**Files:** create MockPayloads, MockPlatformServer, and MockPlatformServerTest beside this plan.

- [ ] Write failing payload tests for inverse XOR/Base64 decoding, 512-byte bodies, increasing IDs, and overlapping windows.
- [ ] Implement fixed init/segment builders and an encoder accepted by production Decrypter/M3u8Parser.
- [ ] Write failing server tests for platform JSON, master/media playlists, exact bytes, WS auth/subscriptions/pushes, and ephemeral loopback binding.
- [ ] Implement per-room state, manual/automatic status and segment advancement, WS disconnect/reject/restore, request history, and next-request 404/500/delay/interruption faults.
- [ ] Cancel/join generators before stopping Ktor. Run server tests twice.
- [ ] Commit as `test: add deterministic platform mock server`.

```kotlin
assertEquals(token, Decrypter.decode(encryptToken(token, key).reversed(), key))
assertEquals(512, segmentBytes(roomId = 1, generation = 2, index = 3).size)
```

Required controls:

```kotlin
fun addRoom(id: Long, name: String, status: String = "off"): MockRoom
suspend fun setRoomStatus(id: Long, status: String, push: Boolean = true)
suspend fun setStreamStatus(id: Long, status: String, push: Boolean = true)
fun startSegments(id: Long, period: Duration): Job
fun rotateStatuses(id: Long, statuses: List<String>, period: Duration): Job
suspend fun disconnectWebSockets()
fun rejectWebSockets(value: Boolean)
fun failNext(path: String, fault: MockFault)
fun requests(): List<MockRequest>
```

### Task 6: Real component fixture and room operations

**Files:** create XhrecIntegrationFixture and RoomRoutesIntegrationTest.

- [ ] Write RED vertical slice: add inactive, verify unarmed, activate, push public, await Recording, remove, await file close, verify dashboard absence.
- [ ] Build real Config/Room/LiveEvent/Downloader/Writer/Session/Scheduler/Metric/HTTP components with short timings, threshold 0, temp paths, no-proxy clients, and mock URLs.
- [ ] Add bounded awaitEvent/awaitDashboard/awaitFile. Timeout errors include recent events/requests. Teardown closes every owned resource and only its temp directory.
- [ ] Verify the first test twice.
- [ ] Add RED/GREEN cases individually: active add; invalid/duplicate input; remove while preconfiguring/recording; activate/deactivate; break/restart; quality; limits; both autopays; list/dashboard; list.conf persistence/reload.
- [ ] Run route/full tests; commit as `test: cover room control routes with mock platform`.

```kotlin
fixture.get("/add?name=model&active=false").expectOk()
fixture.awaitRoom("model") { !it.listening }
fixture.get("/activate?id=1001").expectOk()
fixture.mock.setRoomStatus(1001, "public")
fixture.awaitSession(1001, "Recording")
fixture.get("/remove?id=1001").expectOk()
fixture.awaitRoomAbsent(1001)
```

### Task 7: Status actions and exact file bytes

**Files:** create StatusFlowIntegrationTest and DownloadIntegrityIntegrationTest.

- [ ] Write RED: start segments, activate offline room, set public, await four segments, set off, await FileReady, compare bytes with mock expected bytes.
- [ ] Force segment 2 to complete before 1; assert inverted completion history and ordered output.
- [ ] Add table-driven RED/GREEN cases for offline/public/group/private/unknown room states and distributing/finished stream states, including paid-show guards.
- [ ] Add automatic `off -> public -> groupShow -> p2p -> public -> off` rotation and two-room state/byte isolation.
- [ ] Add exact-boundary tests for sliding-window dedupe, init change, break, restart, time/size limits, 404 no-retry, transient 500, and stall/retry.
- [ ] Run both classes twice/full suite; commit as `test: verify status actions and recording bytes`.

```kotlin
assertContentEquals(mock.expectedBytes(roomId, generation, throughIndex), ready.file.readBytes())
assertTrue(mock.completionOrder(roomId).indexOf(2) < mock.completionOrder(roomId).indexOf(1))
```

### Task 8: WebSocket, fallback, and recovery

**Files:** create WebSocketFallbackIntegrationTest.

- [ ] Write RED: with polling long, push broadcastChanged(public) and require Recording before any poll.
- [ ] Add RED/GREEN cases for nested modelStatusChanged, streamChanged, duplicate suppression, malformed then valid frames, subscription expansion/reduction, and removal unsubscribe.
- [ ] Write RED fallback: reject/disconnect WS, change only HTTP status, require polling to record, assert no WS push delivered it.
- [ ] Write RED recovery: restore WS, await reconnect/resubscribe, require one debounced catch-up, then prove pushes resume without duplicates.
- [ ] Run the class three times/full suite; verify loopback-only traffic and bounded teardown.
- [ ] Commit as `test: verify websocket delivery and polling fallback`.

### Task 9: Fresh complete verification

- [ ] Map each original requirement to an executed test name; fill gaps through RED/GREEN.
- [ ] Run `./gradlew clean test`.
- [ ] Run `./gradlew test --tests 'github.rikacelery.v3.integration.*'` twice.
- [ ] Run `git diff --check`.
- [ ] Confirm request history is loopback-only and Gradle exits without server/client/coroutine leaks.
- [ ] Commit final corrections as `test: complete mock integration coverage`.

Expected evidence: every command exits 0; exact bytes match; forced out-of-order downloads are written in order; two rooms remain isolated; both WS and polling drive recording; production defaults remain unchanged.
