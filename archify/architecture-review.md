# XhRec 架构评审：自动录制引擎

> 结论：整体架构分层清晰、方向合理，核心框架有测试覆盖；但当前实现仍有若干**中等/高风险健壮性问题**，主要集中在背压、并发竞态、资源清理和下载竞争逻辑。建议优先修复 P0/P1 项后再用于大规模房间数场景。

---

## 1. 总体评价

| 维度 | 评价 |
|---|---|
| 模块划分 | 优秀：core / components / data / events / utils 分层清楚 |
| 事件驱动 | 良好：EventBus + RequestBus + DataChannel 三条总线职责明确 |
| Actor 模型 | 良好：串行邮箱、异常隔离、慢 handler 监控 |
| 按房间隔离 | 良好：Session / Downloader / Writer / PostProcessor 均按 roomId 隔离 |
| I/O 异步化 | 良好：文件与进程 I/O 基本切到 Dispatchers.IO |
| 可观测性 | 中等：Prometheus 指标、日志脱敏、Actor 慢日志存在；但 EventBus 积压监控有缺陷 |
| 背压设计 | 偏弱：DataChannel 与 MseStore 无界，存在内存风险 |
| 资源生命周期 | 偏弱：Downloader/Metric/ClientManager 存在按房间的长期累积 |
| 并发竞态 | 中等：Session 启停竞态、PostProcessor 阻塞 Actor 邮箱 |
| 安全 | 中等：本地 API 无鉴权，依赖网络隔离；Cookie 明文存储 |

---

## 2. 做得好的地方

1. **分层清晰**：控制面（EventBus/RequestBus）与数据面（DataChannel）分离，组件不直接相互依赖，易测试、易替换。
2. **Actor 串行处理**：每个组件邮箱串行，避免组件内部状态加锁；并发通过协程实现。
3. **按房间隔离**：`RoomSession`、`ActiveDownload`、Writer 的 `ActiveFile`、PostProcessor 的 `RoomProcessor` 都以 roomId 为键，单房间异常不会直接影响其他房间。
4. **下载顺序保证**：`OrderedEmitter` 将并发下载结果按序号重排，保证写出 MP4 的分片顺序。
5. **故障转移与 CDN 优选**：`HostFailover` + `CdnSelector` 的 EWMA/ε-greedy/冷却机制设计合理。
6. **核心测试**：EventBus / Actor / RequestBus / DataChannel / OrderedEmitter / Config / PostProcessor 均有测试覆盖。
7. **优雅停机**：`ShutdownCmd` 先停调度，再等待会话与后处理完成，方向正确。

---

## 3. 主要风险与问题（按优先级）

### P0 — 高优先级

#### 3.1 EventBus 积压监控失效，事件可能被静默丢弃

`EventBus` 使用：

```kotlin
MutableSharedFlow<Any>(
  replay = 0,
  extraBufferCapacity = 1024,
  onBufferOverflow = BufferOverflow.DROP_OLDEST
)
```

随后用 `tryEmit()` 判断 buffer 是否满。但在 `DROP_OLDEST` 模式下，`tryEmit` **几乎总是返回 true**（它通过丢弃最旧事件来保证发射成功）。因此：

- `recordBacklog()` 永远不会执行；
- 积压监控和“解除积压”日志是死代码；
- 当 buffer 满时，**最旧的控制面事件/命令会被静默丢弃**，且无法观测。

**建议**：
- 将 `onBufferOverflow` 改为 `BufferOverflow.SUSPEND`（或去掉 `DROP_OLDEST`），让 `tryEmit` 返回 false 表示真正积压；
- 或保留 `DROP_OLDEST`，但改用 `SharedFlow` 的订阅者慢消费监测，而不是 `tryEmit` 判断；
- 评估是否需要为控制命令提供 `replay=0` 之外的持久化/确认机制。

#### 3.2 下载器“直连失败后代理竞争”存在逻辑缺陷

`DownloaderComponent.downloadSegment`：

```kotlin
val directDeferred = scope.async { ... 直连 ... }
val directResult = withTimeoutOrNull(15_000) { directDeferred.await() }
if (directResult is Success) return ...

// 直连失败后启动代理
val proxyDeferred = scope.async { ... 代理 ... }

val result = select<DownloadResult> {
    directDeferred.onAwait { ... }
    proxyDeferred.onAwait { ... }
}
```

问题：如果直连**快速失败**（例如 2 秒内返回 `DownloadResult.Failed`），进入 select 时 `directDeferred` 已经完成，`select` 会立即选中直连的 `Failed` 返回，**代理永远没有机会尝试**。这恰恰是代理最该介入的场景。

**建议**：
- 若 `directDeferred` 已完成且为失败，直接 `withTimeoutOrNull(15s) { proxyDeferred.await() }`；
- 或对 select 中的 `directDeferred` 只接受 Success，避免已失败的 future 参与竞争；
- 为整个 `downloadSegment` 增加总超时，防止两个 deferred 都长时间挂起。

#### 3.3 DataChannel 无界，写盘慢时内存会持续膨胀

`DataChannel(capacity = Channel.UNLIMITED)`。

下载端可以高速并发产出 `StreamData`，Writer 端写磁盘较慢时，消息会无限堆积在内存中。直播录制数据量很大（高码率），存在 OOM 风险。

**建议**：
- 将 DataChannel 改为有界（如 256~1024）；
- 或按房间实现有界队列 + 背压；
- 在 `OrderedEmitter` 输出时如果发送挂起，应能向 Session 传递压力信号（跳过/断流/触发 CutPoint），避免无限缓冲。

### P1 — 中高优先级

#### 3.4 OrderedEmitter 缺少“缺口”处理，可能永久卡住一个房间

`OrderedEmitter` 只按 `nextIndex` 顺序发射。如果某个分片的下载任务既没有 `Success` 也没有 `Failed` 回调（例如 future 永久挂起、协程被遗漏），该房间后续所有分片都会卡在 buffer 里，无法写出。

**建议**：
- 给每个分片下载任务设置硬超时；
- 在 `OrderedEmitter` 中增加缺口阈值：超过 N 个后续分片已到而最旧分片仍未完成时，视为缺口，发射 `StreamEnd` 或跳过该分片；
- 定期扫描 `runningJobs`，对超时任务执行取消并补发 `Failed`。

#### 3.5 Session 启停存在竞态

`startSession()` 先注册 `RoomSession` 并置为 `Fetching`，随后在 `scope.launch` 中执行 `configureSession` / `fetchAndCacheMasterPlaylist`。这些挂起点之间若收到 `DoStop`/`DoBreak`，`stopSession()` 会将状态置为 `Closing`，但启动协程**不会重新检查状态**，仍可能继续 `dataChannel.send(StreamStart)`、发布 `RecordingStarted`，导致：

- 停止后仍打开新文件；
- 产生空文件或多余的 CutPoint。

**建议**：
- 在启动协程每个挂起恢复点之后检查 `rs.state`，若已 `Closing`/`Idle` 则直接返回；
- 用 `CoroutineStart.ATOMIC` 或状态机原子 CAS 防止启停交错；
- 为 `RoomSession` 引入“代际 generation”并在 Stop 时递增，启动协程发现代际变化则放弃。

#### 3.6 PostProcessorComponent 在 Actor 邮箱内直接 send，可能阻塞整个 Actor

`handle()` 中：

```kotlin
is FileReady -> {
    val rp = rooms.getOrPut(e.roomId) { RoomProcessor() }
    ...
    rp.channel.send(e)   // channel capacity = 8
}
```

当某个房间的后处理队列已满（8 个 FileReady），`send` 会挂起。此时 PostProcessor Actor 的邮箱无法继续处理其他房间的 `FileReady` 或 `RecordingStopped`，虽然不会直接阻塞 EventBus 的所有订阅者，但会阻塞该组件的 EventBus 订阅协程（因为 `Actor.subscribe` 会把消息发送进同一个有限邮箱）。

**建议**：
- 按 `REFACTOR_PLAN` 的设计，在 `handle` 中 `scope.launch { rp.channel.send(e) }`，并确保按房间顺序；
- 或使用 `trySend` 并缓存未发送事件；
- 更优做法：`wrapEvent` 中直接按 roomId 路由到 per-room 队列，让 Actor 邮箱只承载命令。

#### 3.7 资源按房间累积，长跑场景会泄漏

以下结构都以 roomId 为键但缺少清理：

| 结构 | 泄漏内容 |
|---|---|
| `DownloaderComponent.rooms` | 每个房间的 `ActiveDownload`、`OrderedEmitter`、`Semaphore`、runningJobs |
| `MetricComponent.metrics` | 每个房间的 `RoomMetrics` |
| `ClientManager` 客户端缓存 | `m3u8_${roomId}`、`master_${roomId}` 每个房间创建独立 HttpClient |
| `LiveEventSource.roomStatuses` | 每个房间最新状态 |
| `MseStore.rooms` | 每个房间的 init 分片、最新分片、SSE 通道状态 |

房间被删除或长时间停止后，这些对象不会被释放。房间数量大或反复增删时，连接池、内存和文件句柄会持续增长。

**建议**：
- 在 `RoomRemoved` / `RecordingStopped` 后增加清理链路；
- `ClientManager` 使用固定数量客户端或 LRU 缓存，按 key 关闭；
- `MetricComponent` 在 `RoomRemoved` 或录制停止后 TTL 清理。

### P2 — 中等

#### 3.8 `downloadWithClient` 缺少显式超时

`ClientManager` 未配置 `HttpTimeout`，依赖 OkHttp 默认值。`downloadSegment` 的最终 `select` 也没有总超时。在代理挂起/直连挂起时，单分片可能长时间占用协程。

**建议**：在 `ClientManager` 统一配置 `HttpTimeout`；在 `downloadSegment` 外层加 `withTimeout`。

#### 3.9 Writer 重命名失败未检查

`closeActiveFile`：

```kotlin
active.file.renameTo(finalFile)
```

`renameTo` 返回值被忽略；失败时后续会按 `finalFile.length()` 判断并可能删除原文件，造成录制数据丢失。

**建议**：使用 `Files.move` 并在失败时记录错误、保留原文件。

#### 3.10 Session 轮询通用异常处理不彻底

`pollingLoop` 的 `catch (e: Exception)` 中，当 `configureSession(...) == null` 时只记录了日志，没有设置 `Closing` 也没有 break，而是继续尝试恢复。对于主播确实已下播的房间，可能进入反复空转。

**建议**：确认无法继续录制时，置 `Closing`、发 CutPoint 并退出轮询。

#### 3.11 HTTP API 无鉴权

WebUI/REST API 没有任何认证。虽然默认 HTTPS 自签名，但任何能访问端口的主机都可以添加/删除房间、修改配置、下载预览、控制录制。浏览器扩展和用户脚本也直接使用固定主机地址。

**建议**：
- 绑定 `127.0.0.1` 或增加 API Token / Basic Auth；
- 若必须远程访问，加鉴权和最小权限。

#### 3.12 敏感文件存储

`users.txt` 保存明文 Cookie；`xhrec.json` 保存解密密钥。若目录被备份或泄露，账号密码会暴露。

**建议**：对 `users.txt` 设置 0600 权限，或至少提示风险；解密密钥避免明文落盘。

---

## 4. 改进路线建议

1. **短期（稳定性优先）**
   - 修复 EventBus DROP_OLDEST / 积压监控；
   - 修复 Downloader 代理竞争逻辑；
   - 给 DataChannel 设置有界容量或按房间背压；
   - 为分片下载加硬超时 + OrderedEmitter 缺口处理。

2. **中期（长跑健壮性）**
   - 完善资源清理：RoomRemoved → Session → Downloader → Metric → MseStore 联动；
   - ClientManager 改 LRU/固定客户端；
   - 修复 Session 启停竞态；
   - 修复 PostProcessor Actor 阻塞。

3. **长期（工程化）**
   - 增加组件级集成测试：Session/Downloader/Writer/PostProcessor 的端到端录制测试；
   - 增加故障注入测试：CDN 慢/挂、Write 慢、EventBus 满、文件系统满；
   - 引入结构化日志与 Trace ID（roomId, generation）串联每个房间的完整录制链路。

---

## 5. 评审结论

- **合理性**：高。架构分层、Actor 模型、事件驱动、按房间隔离的思路是正确且成熟的，适合直播录制这类 I/O 密集型长跑服务。
- **健壮性**：中等。当前实现仍存在若干可能造成录制中断、内存膨胀、静默丢事件的缺陷，其中 EventBus、Downloader 竞争逻辑、DataChannel 背压和 OrderedEmitter 缺口处理属于需要优先修复的问题。
- **建议**：在房间数少、手动运维的场景下可运行；若要支撑大规模/无人值守场景，应先完成 P0/P1 项整改。
