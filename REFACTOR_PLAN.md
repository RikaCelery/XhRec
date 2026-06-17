# Refactor Plan: 事件总线非阻塞化

## Problem Statement

事件总线被同步 I/O 阻塞导致级联雪崩。具体表现：

1. **PostProcessorComponent 共享邮箱阻塞**：MoveProcessor 的大文件复制（`input.copyTo(dest)`）在 Actor 协程中同步执行，一个房间的文件处理阻塞了所有房间的后续消息。
2. **ConfigComponent 文件 I/O 阻塞事件泵**：`saveConfig()` 在 EventBus subscriber 的 collector 协程中执行 `configFile.writeText()`，磁盘慢时整个 ConfigComponent 停止响应，导致所有 `GetDecryptKey` / `MatchDecryptKeys` 请求超时（日志中 25 个 session 在同一毫秒内全部超时）。
3. **pollingLoop 无错开**：多个 session 的 3 秒轮询周期同步，同一时刻发起大量相同请求，放大拥堵效应。
4. **最终崩溃**：事件堆积 + 文件句柄泄漏 → `OutOfMemoryError: Java heap space`。
5. **无测试覆盖**：核心并发框架（EventBus、Actor、RequestBus）没有任何自动化测试。

## Solution

分五个模块改造，核心原则：**按房间隔离、阻塞 I/O 异步化、事件泵永不阻塞、积压可观测**。

### 模块 1: 核心框架测试（EventBus / Actor / RequestBus / DataChannel / OrderedEmitter）

用 `kotlinx-coroutines-test` + JUnit5 `runTest` 编写测试。OrderedEmitter 用 mock DataChannel。

### 模块 2: 事件总线积压监控（EventBus / Actor）

**EventBus**：`publish()` 先用 `tryEmit()`。返回 false 说明 SharedFlow buffer 满、事件正在积压。记录第一个积压事件的类型和时间戳，持续积压时按间隔（每 30 秒）汇总打印积压事件类型和数量。随后 fallback 到 `emit()`（挂起等待，不丢事件）。

**Actor**：在 `handle()` 调用前后记录时间，耗时超过 500ms 时打印 WARN（包含 Actor 名、消息类型、耗时毫秒数）。

### 模块 2: PostProcessorComponent 按房间分发

保持全局 `Semaphore(4)` 限制并发进程数，新增 per-room `Channel<FileReady>` + 专属协程。 `handle()` 只做路由到房间 Channel，通过 `scope.launch` 中 `channel.send()` 避免阻塞 Actor 主循环。房间停止时通过 `RecordingStopped` 事件驱动清理。

### 模块 3: ConfigComponent 非阻塞化 + SessionComponent 缓存

`loadConfig()`/`saveConfig()` 文件读写切到 `Dispatchers.IO`。`PersistConfig` 处理改为 `scope.launch(Dispatchers.IO) { saveConfig() }` 完全异步化。SessionComponent 新增 `ConcurrentHashMap` 缓存 decrypt key 查询结果，`PersistConfig` 时清空。

### 模块 4: pollingLoop 错开

每次 pollingLoop 启动时在 3 秒基础上加随机 ±500ms jitter。

## Commits

### Commit 1: 添加 kotlinx-coroutines-test 测试依赖

`build.gradle.kts` 新增 `testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")`。

### Commit 2: EventBus 测试

`src/test/kotlin/github/rikacelery/v3/core/EventBusTest.kt`

- `publish to single subscriber delivers event correctly`
- `publish to multiple subscribers each gets independent copy`
- `buffer full with DROP_OLDEST drops oldest events`
- `hook returns null swallows event`
- `concurrent publish from multiple coroutines delivers all events`

### Commit 3: Actor 测试

`src/test/kotlin/github/rikacelery/v3/core/ActorTest.kt`

创建一个测试用 `TestActor` 子类。覆盖：
- `tell order equals handle order` — 时序保证
- `handle throws non-CancellationException triggers onError` — 异常隔离
- `start then stop closes mailbox and cancels coroutine` — 生命周期
- `wrapEvent routes events into mailbox` — 集成链路

### Commit 4: RequestBus 测试

`src/test/kotlin/github/rikacelery/v3/core/RequestBusTest.kt`

- `request-response round-trip with CommandAck`
- `timeout throws RequestTimeoutException and cleans pending`
- `ErrorResponse throws RequestErrorException`
- `concurrent 100 requests each gets correct independent response`
- `parent coroutine cancelled cleans up CompletableDeferred`
- `CommandAck arrives before await (extreme out-of-order)`

### Commit 5: DataChannel 测试

`src/test/kotlin/github/rikacelery/v3/core/DataChannelTest.kt`

- `send-receive preserves order`
- `hook returns null drops message`

### Commit 6: OrderedEmitter 测试

`src/test/kotlin/github/rikacelery/v3/core/OrderedEmitterTest.kt`

Mock DataChannel，只验证 OrderedEmitter 自身的排序逻辑：
- `in-order completion emits in-order`
- `out-of-order completion emits sorted by seq`
- `CutPoint result triggers StreamEnd and resets emitter`

### Commit 7: ConfigComponent 非阻塞 IO

`ConfigComponent.kt`:

- `loadConfig()` 方法体内加 `withContext(Dispatchers.IO)` 包裹 `configFile.readText()`
- `saveConfig()` 方法体内加 `withContext(Dispatchers.IO)` 包裹 `configFile.writeText()`
- `PersistConfig` 处理从 `saveConfig()` 改为 `scope.launch(Dispatchers.IO) { saveConfig() }`

### Commit 8: ConfigComponent 测试

`src/test/kotlin/github/rikacelery/v3/components/ConfigComponentTest.kt`

- `GetDecryptKey found returns key value`
- `GetDecryptKey not found returns ConfigResponse(null)`
- `MatchDecryptKeys finds first matching key`
- `MatchDecryptKeys no match returns empty DecryptKeyMatch`
- `ToggleMask flips value and publishes PersistConfig`
- `saveConfig then loadConfig round-trips correctly`
- `saveConfig does not block GetDecryptKey request handling` — **核心隔离测试**

### Commit 9: SessionComponent decryptKeyCache

`SessionComponent.kt`:

- 新增 `private val decryptKeyCache = ConcurrentHashMap<String, String>()`
- `pollingLoop` 第 524 行：先查 cache，miss 时调 `requestBus.request<ConfigResponse>(GetDecryptKey(rs.pkey))`，结果写入 cache
- 新增 `onPersistConfig()` 处理方法，清空 cache
- `onStart()` 中订阅 `PersistConfig` 事件

### Commit 10: PostProcessorComponent 按房间分发

`PostProcessorComponent.kt` 重构：

- 新增 `RoomProcessor` 内部结构：`Channel<FileReady>(capacity=8)` + 专属 `Job`
- 新增 `private val rooms = ConcurrentHashMap<Long, RoomProcessor>()`
- `handle()` 中 `OnProcessorEvent(FileReady)` → `rooms.getOrPut(roomId)` → `scope.launch { room.channel.send(event) }`，主循环不阻塞
- `RoomProcessor` 协程内：`for (event in channel) { semaphore.withPermit { processFile(event) } }`，全局 Semaphore 保留

### Commit 11: PostProcessorComponent 事件驱动清理

- `onStart()` 中新增订阅 `RecordingStopped` 事件
- 收到 `RecordingStopped` → 等待对应 RoomProcessor 的 channel 清空 → 关闭 channel → 从 `rooms` 中移除

### Commit 12: PostProcessorComponent 测试

`src/test/kotlin/github/rikacelery/v3/components/PostProcessorComponentTest.kt`

- `single room single FileReady routes to processFile`
- `single room multiple FileReady processed in arrival order` — 同房间时序
- `room A slow processing does not block room B` — **核心隔离测试**
- `room A FileReady queued while room A still processing` — 同房间串行
- `RecordingStopped cleans up idle RoomProcessor`
- `new FileReady after cleanup recreates RoomProcessor`

### Commit 13: pollingLoop 启动 jitter

`SessionComponent.kt` `pollingLoop` 方法：

- 首次 `delay(3.seconds)` 改为 `delay(3.seconds + Random.nextLong(-500, 500).milliseconds)`
- 后续轮次保持标准 3 秒间隔（无需 jitter）

### Commit 14: EventBus 积压监控

`EventBus.kt`:

- `publish()` 中 `_events.emit(e!!)` 改为先调 `tryEmit()`。返回 true 则直接返回，返回 false 则记录积压。
- 新增积压状态字段：`backlogStartTime: Long`（首次积压时间戳）、`backlogCount: Long`（积压事件累计数）、`firstBackloggedEvent: String`（触发积压的第一个事件类型）。
- `tryEmit()` 返回 false 且 `backlogStartTime == 0`（首次积压）→ 打印 WARN：event 类型 + "event bus buffer full, starting to back up"。
- 后续 `tryEmit()` 返回 false → 累加计数器。
- 距首次积压超过 30 秒、或积压数超过 1000 → 汇总打印 WARN：积压总时长、积压事件数、Top N 积压事件类型分布。然后重置计数器。
- `tryEmit()` 返回 true 且之前处于积压状态 → 打印 INFO：积压已解除，总时长和总积压数。
- 无论 `tryEmit()` 成功与否，最后 fallback 到 `_events.emit(e!!)` — 不丢事件。

### Commit 15: Actor 慢 handler 监控

`Actor.kt`:

- `handle(msg)` 前后记录 `System.nanoTime()`。
- 耗时超过 `slowHandlerThresholdMs`（默认 500ms）→ 打印 WARN："slow handler: actor=$name, msg=${msg::class.simpleName}, took=${duration}ms"。
- `slowHandlerThresholdMs` 作为 Actor 构造参数，默认 500ms，可在子类中按需覆盖。

## Decision Document

### 模块划分

- **核心框架修改 + 测试**：EventBus（tryEmit 积压监控）、Actor（慢 handler 监控 + 测试）、RequestBus（测试）、DataChannel（测试）、OrderedEmitter（测试）
- **PostProcessorComponent**：从全局串行 Actor 改为按 roomId 分发
- **ConfigComponent**：文件 I/O 异步化，PersistConfig 完全异步
- **SessionComponent**：新增 decryptKeyCache，pollingLoop 添加 jitter
- **不改**：WriterComponent（已用 DataChannel + IO dispatch）、DownloaderComponent（handle 只启动协程）、SchedulerComponent（纯消息路由）

### PostProcessorComponent 设计决策

- **全局 Semaphore 保留**：因为 ffmpeg 进程数量需要全局控制，Semaphore 从 `processFile` 所在协程中 `withPermit` 获取
- **Per-room Channel 容量 8**：每个房间最多积压 8 个 FileReady，超过则 `send()` 挂起（在 `scope.launch` 的独立协程中，不阻塞 Actor 主循环）
- **清理策略**：事件驱动（`RecordingStopped`），先等待 channel 清空再关闭
- **handle() 路由**：使用 `scope.launch { channel.send() }` 而非 `trySend()`，保证不丢事件

### ConfigComponent 设计决策

- **loadConfig/saveConfig**：文件操作包 `withContext(Dispatchers.IO)`，suspend 但不阻塞线程
- **PersistConfig**：`scope.launch(Dispatchers.IO)` 完全异步，collector 协程立即返回
- **decryptKeyCache**：`ConcurrentHashMap<String, String>`，key 为 pkey，收到 PersistConfig 时清空

### EventBus 积压监控设计决策

- **两层机制**：`tryEmit()` 快速检测 buffer 满，`emit()` fallback 确保不丢事件。SharedFlow 自带 DROP_OLDEST 不再生效（因为我们主动 tryEmit 后 fallback）。
- **汇总日志而非每条都打**：首次积压立即 WARN，后续按 30 秒间隔或 1000 条阈值汇总，避免日志爆炸。
- **解除通知**：积压解除时打印 INFO，便于定位拥堵时长。
- **保留 DROP_OLDEST 不变**：`extraBufferCapacity` 和 `BufferOverflow` 不做修改。tryEmit 返回 false 意味着 SharedFlow 内部 buffer 满，我们接住了这个信号。

### Actor 慢 handler 监控设计决策

- **默认阈值 500ms**：覆盖大多数合理耗时，超出的都是异常。子类可覆盖。
- **用 `nanoTime` 而非 `currentTimeMillis`**：单调时钟，不受系统时间调整影响。
- **不累积统计**：每次慢 handler 单独打 WARN，不在 Actor 内维护统计状态。如果需要聚合分析，由外部日志系统处理。

### Jitter 设计决策

- **仅首次 delay 加 jitter**：后续轮次保持标准 3 秒间隔，避免累积偏差
- **jitter 范围 ±500ms**：足够分散同时启动的 session，不影响录制的实时性

### 接口变更

- **EventBus.publish()**：行为不变（suspend 函数，不丢事件），内部改为 tryEmit + fallback emit + 积压日志
- **Actor 构造参数**：新增 `slowHandlerThresholdMs: Long = 500`
- PostProcessorComponent 新增订阅：`RecordingStopped`
- SessionComponent 新增订阅：`PersistConfig`
- 无外部 API 变更

## Testing Decisions

### 测试框架

- **kotlinx-coroutines-test** + `runTest`：虚拟时间，快速验证超时和并发场景
- **JUnit5**（已配置）：`assertThrows`、`@Test`
- **OrderedEmitter 的 DataChannel**：用 mock（手动实现一个记录发送消息的 fake）

### 什么算好测试

- 只测试外部可观察行为（发布的事件、抛出的异常、处理顺序），不测内部实现细节
- 并发测试用 `runTest` + `launch` 启动多个协程，验证最终状态而非执行路径
- 隔离测试：验证"A 阻塞时 B 不受影响"是每个模块的核心测试

### 哪些模块被测试

| 模块 | 测试文件 |
|------|---------|
| EventBus | `EventBusTest.kt` |
| Actor | `ActorTest.kt`（需 TestActor 子类） |
| RequestBus | `RequestBusTest.kt` |
| DataChannel | `DataChannelTest.kt` |
| OrderedEmitter | `OrderedEmitterTest.kt`（mock DataChannel） |
| ConfigComponent | `ConfigComponentTest.kt` |
| PostProcessorComponent | `PostProcessorComponentTest.kt` |

### 现有测试设施

- 项目当前**没有任何测试文件**
- `build.gradle.kts` 已配置 `kotlin-test` + JUnit5 + `useJUnitPlatform()`
- 需新增 `kotlinx-coroutines-test` 依赖

## Out of Scope

- **RequestBus 框架级自动去重**：不做，缓存逻辑放在 SessionComponent 业务层
- **EventBus BUFFER SIZE 或 OVERFLOW 策略修改**：保持 DROP_OLDEST + capacity=1024，积压监控在 tryEmit 层上报，不改变 SharedFlow 底层行为
- **Actor 框架通用化 per-key 分发**：不抽象到 Actor 基类，仅在 PostProcessorComponent 内部实现
- **DownloaderComponent / WriterComponent 改造**：已经使用 IO dispatch 或 per-room 协程，不需改动
- **SessionComponent 的 handle() 改造**：经分析 handle() 无阻塞操作，不需改动
- **积压事件的自动 dump / 落盘**：只做日志输出，不做事件内容序列化或持久化
