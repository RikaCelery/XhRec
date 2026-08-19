# XhRec 组件/模块分析与用例图

> 生成于当前仓库源码分析。目录内包含：
> - `README.md`：组件/模块功能说明
> - `architecture-review.md`：架构合理性/健壮性评审
> - `xhrec-use-case.puml`：用例图 PlantUML 源文件
> - `xhrec-use-case.png` / `xhrec-use-case.svg`：渲染后的用例图
> - `recording-engine-architecture.puml/.png/.svg`：自动录制引擎组件架构图
> - `recording-engine-sequence.puml/.png/.svg`：自动录制引擎核心时序图
> - `recording-engine-state.puml/.png/.svg`：会话状态机图

## 1. 总体架构

XhRec 是一个 Kotlin/JVM 自动直播录制服务，核心为“事件驱动 + Actor 并发”模型：

- `EventBus` 负责组件间异步事件分发；
- `RequestBus` 在事件总线之上实现请求/响应 RPC；
- `DataChannel` 承载流媒体数据（MP4 分片、流事件）；
- 各 `*Component` 是独立 Actor，串行处理自己的邮箱消息，彼此通过事件/请求协作；
- 外部通过 Ktor HTTPS WebUI / REST API / 浏览器扩展控制。

```
浏览器扩展/UserScript ──HTTPS──▶ HttpServerComponent ──RequestBus──▶ Room/Scheduler/Session...
直播平台 API/WS/HLS ──▶ ApiClient / LiveEventSource / DownloaderComponent ──DataChannel──▶ WriterComponent ──▶ PostProcessorComponent
```

## 2. 核心基础设施

| 模块/文件 | 功能 |
|---|---|
| `core/EventBus.kt` | 异步发布/订阅事件总线；支持 Hook 拦截、缓冲积压监控、不丢事件。 |
| `core/RequestBus.kt` | 基于 `CommandEnvelope`/`CommandAck` 的请求-响应总线；带超时、错误响应、并发请求管理。 |
| `core/Actor.kt` | 通用 Actor 基类：串行邮箱、生命周期、事件订阅包装、异常隔离、慢 handler 监控。 |
| `core/DataChannel.kt` | 流媒体数据通道：统一承载 `StreamStart/StreamData/StreamEnd/StreamEvent`，支持 DataHook 拦截。 |
| `core/OrderedEmitter.kt` | 将并发下载完成的分片按序号重排，保证输出到 Writer 的顺序正确。 |

## 3. 主要组件/模块

| 组件 | 功能 |
|---|---|
| `Bootstrap` | 启动时解析 CLI/配置，加载 `users.txt`、`postprocessor.json`、`list.conf`，初始化房间与调度器。 |
| `ApiClient` | 平台 HTTP API 客户端：多域名故障转移、Cookie 校验、房间信息、画质、私密/群组秀支付相关请求。 |
| `ConfigComponent` | 管理 `xhrec.json`：解密密钥、日志脱敏开关、平台/WebSocket/CDN 域名；修改后持久化并实时应用。 |
| `AuthComponent` | 管理自动支付用户账号：加载/校验 Cookie、余额查询、扣费、选择有足够余额的账号。 |
| `RoomComponent` | 房间元数据管理：增删改查、状态缓存、定期刷新主播信息、保存 `list.conf`。 |
| `SchedulerComponent` | 录制调度：启停/中断录制、监听直播状态、录制结束后自动重连、优雅停机。 |
| `SessionComponent` | 单个房间的录制会话状态机：拉取 master/媒体 playlist、选画质、轮询分片、触发下载、自动支付群组秀、限时/限大小。 |
| `LiveEventSource` | 维护到平台的 WebSocket：订阅全局/房间频道，解析 `RoomStatusChanged` 和 `LiveMessage`。 |
| `DownloaderComponent` | 并发下载 HLS 分片：直连/代理竞争、CDN 优选、失败重试、通过 `OrderedEmitter` 输出有序流。 |
| `WriterComponent` | 将流数据写入临时 MP4/事件文件，流结束时关闭、重命名并发布 `FileReady`。 |
| `PostProcessorComponent` | 录制文件后处理：按房间队列隔离，执行 `fix_stamp`、`move`、`slice`、`shell` 处理器。 |
| `MetricComponent` | 统计下载/写入/延迟/代理比例等指标，提供 `/metrics`、`/status`、`/dashboard` 数据。 |
| `MseStore` | 通过 DataChannel Hook 缓存实时流分片，供 WebUI `/mse/live` 实时预览。 |
| `HttpServerComponent` | Ktor HTTPS 服务：WebUI、REST API、CORS、自签名证书、优雅停机。 |

## 4. 辅助模块/工具

| 模块 | 功能 |
|---|---|
| `M3u8Parser` | 解析 master/媒体 playlist，提取分片 URL、PSCH 密钥、清晰度变体。 |
| `Decrypter` | 对 M3U8 中混淆的 URL/PSCH 做 XOR 解密。 |
| `CdnSelector` | 基于 EWMA 下载速度选择最快 CDN；带 ε-greedy 探测和故障冷却。 |
| `HostFailover` | 有序域名列表 + 指数退避故障切换。 |
| `ClientManager` | 管理 OkHttp 客户端：直连/代理、HTTP/1.1 指纹、连接池、WebSocket。 |
| `SensitiveStringRegistry` / `MaskingMessageConverter` | 日志脱敏：隐藏主播名、Cookie、Token、代理地址。 |
| `postprocessors/*` | 内置后处理器：`FixStampProcessor`、`MoveProcessor`、`SliceProcessor`、`ShellProcessor`。 |
| `hooks/*` | 扩展点：EventHook、DataHook、DownloaderHook、WriterHook、RoomHook、PostProcessorHook。 |

## 5. 用例图说明

文件：`xhrec-use-case.puml`

参与者：
- **用户/操作者**：通过 WebUI/REST API 管理房间与录制；
- **管理员/运维**：通过 CLI 启动系统、加载/管理系统配置、执行优雅停机；
- **浏览器扩展/UserScript**：在主播页面一键添加/控制/查看状态；
- **直播平台**：API、WebSocket、HLS CDN 等外部系统；
- **后处理工具链**：ffmpeg/ffprobe/shell 等外部命令。

主要用例：
1. 管理房间：添加、删除、激活、停用、重启、中断。
2. 设置录制参数：画质、时长、大小、自动支付开关。
3. 控制录制：开始/停止/中断/重启。
4. 查看状态与指标：房间列表、实时状态、Prometheus 指标、仪表盘。
5. 实时预览：通过 `/mse/live` 查看正在录制的流。
6. 配置系统：域名管理、日志脱敏开关、解密密钥。
7. 自动录制直播：核心用例，由 Scheduler/Session/Downloader/Writer 协作完成。
8. 自动支付私密/群组秀：AuthComponent + ApiClient 完成付费进入。
9. 后处理录制文件：对完成的 MP4 执行修复/移动/切片/Shell 命令。
10. 优雅停止/关闭：完成当前录制与后处理后退出。
11. 接收平台实时事件：WebSocket 推送状态变化/聊天/礼物等。
12. 下载 HLS 分片：并发下载、CDN 优选、有序写出。
13. 日志脱敏：保护敏感信息。

## 6. 关键数据流

1. `Bootstrap` 加载房间 → `RoomComponent` 持有房间元数据 → `SchedulerComponent` 设置为 armed。
2. `LiveEventSource` 收到 `broadcastChanged/streamChanged` → 发布 `RoomStatusChanged`。
3. `SchedulerComponent` 发现公开直播/群组秀 → 通知 `SessionComponent` 启动会话。
4. `SessionComponent` 拉取 master/media playlist → 通知 `DownloaderComponent` 下载分片。
5. `DownloaderComponent` 通过 `DataChannel` 将有序分片交给 `WriterComponent` 写文件。
6. 流结束/触发限制 → `WriterComponent` 关闭文件 → 发布 `FileReady` → `PostProcessorComponent` 后处理。
7. `MetricComponent` 监听各事件，生成状态与 Prometheus 指标。
8. `HttpServerComponent` 接收用户/扩展请求，通过 `RequestBus` 驱动各组件。


## 7. 自动录制引擎架构设计（重点）

自动录制引擎是 XhRec 的核心，由“事件/调度层 → 会话控制层 → 流媒体管道 → 后处理层”组成。

### 7.1 分层架构

```
┌────────────────────────────────────────────────────────────┐
│ 事件/调度层                                                 │
│   LiveEventSource · SchedulerComponent · EventBus/RequestBus│
├────────────────────────────────────────────────────────────┤
│ 会话控制层                                                  │
│   SessionComponent · ApiClient · M3u8Parser/Decrypter       │
├────────────────────────────────────────────────────────────┤
│ 流媒体管道                                                  │
│   DownloaderComponent · CdnSelector · OrderedEmitter        │
│   DataChannel · WriterComponent · MseStore                  │
├────────────────────────────────────────────────────────────┤
│ 后处理层                                                    │
│   PostProcessorComponent · fix_stamp/move/slice/shell       │
└────────────────────────────────────────────────────────────┘
```

### 7.2 核心组件职责

| 组件 | 在自动录制引擎中的职责 |
|---|---|
| `LiveEventSource` | 维护 WebSocket，接收主播状态变化、聊天/礼物/私密秀等实时事件。 |
| `SchedulerComponent` | 维护 armed 房间集合，根据 `RoomStatusChanged` 决定是否调用 `SessionComponent` 开始录制。 |
| `SessionComponent` | 每个房间一个会话状态机；负责拉流地址解析、画质选择、playlist 轮询、分片发现、限时/限大小、自动支付群组秀。 |
| `ApiClient` | 提供平台 API：广播信息、画质、模型 token、群组秀支付请求等。 |
| `M3u8Parser/Decrypter` | 解析 master/media playlist，解密 URL/PSCH 信息，提取分片列表。 |
| `DownloaderComponent` | 按房间并发下载 HLS 分片，支持直连/代理竞争、CDN 优选、故障冷却。 |
| `CdnSelector` | 用 EWMA 速度选择最快 CDN；约 10% 请求随机探测其它 CDN 保持测速新鲜。 |
| `OrderedEmitter` | 将并发下载完成的分片按序号重排，保证写入顺序。 |
| `DataChannel` | 流媒体数据管道，连接 Downloader、Writer、MseStore。 |
| `WriterComponent` | 将分片写入临时 MP4/事件文件；流结束时关闭、重命名并发布 `FileReady`。 |
| `MseStore` | 通过 DataChannel 拦截数据，为 `/mse/live` 提供实时预览。 |
| `PostProcessorComponent` | 按房间队列隔离处理录制文件；避免一个房间的大文件复制阻塞其它房间。 |

### 7.3 会话状态机

```
Idle → Armed → Fetching → Recording → Closing → Idle
```

- `Idle`：无会话。
- `Armed`：房间已被 Scheduler 激活，等待直播开始。
- `Fetching`：已开始尝试拉取 master/media playlist、配置 token/画质。
- `Recording`：成功获取 playlist 后进入正式录制轮询。
- `Closing`：流结束/用户停止/错误/限流触发后，等待 CutPoint 排空并发布 `RecordingStopped`。

### 7.4 核心录制流程

1. `LiveEventSource` 收到 `broadcastChanged/streamChanged`，发布 `RoomStatusChanged`。
2. `SchedulerComponent` 判断房间已 armed 且状态为 `public/groupShow`，通知 `SessionComponent` 启动。
3. `SessionComponent` 创建 `RoomSession`，拉取 master playlist，匹配 PSCH 解密 key，选择画质。
4. 进入 `pollingLoop`：每 3 秒（首次加 ±500ms jitter）拉取 media playlist。
5. 发现新分片后调用 `DownloaderComponent.tell(DoDownload(...))` 异步下载。
6. `DownloaderComponent` 并发下载分片，通过 `CdnSelector` 选择 CDN，必要时直连/代理竞争。
7. 下载结果交给 `OrderedEmitter` 按序号重排，再发送到 `DataChannel`。
8. `WriterComponent` 从 `DataChannel` 接收 `StreamStart/StreamData/StreamEnd`，写入 MP4 文件。
9. 达到时长/大小限制或流结束时，Session 发送 `DoCutPoint`，Writer 关闭当前文件并发布 `FileReady`。
10. `PostProcessorComponent` 收到 `FileReady` 后按房间串行执行后处理链。

### 7.5 关键设计点

- **按房间隔离**：Session、Downloader 下载状态、Writer 文件、PostProcessor 队列均以 `roomId` 隔离，避免单房间阻塞全局。
- **顺序保证**：下载是并发的，但通过 `OrderedEmitter` 按 `segmentIndex` 排序输出，Writer 始终收到有序流。
- **CDN 优选 + 故障冷却**：`CdnSelector` 记录 EWMA 速度，`HostFailover` 管理域名冷却。
- **直连/代理竞争**：直连 15s 未成功时启动代理参与竞争，用 `select` 取先成功者。
- **CutPoint 机制**：限时/限大小/流切换时通过 CutPoint 结束当前分片并开启新分片，不需要重启 Actor。
- **解密 key 缓存**：Session 缓存 `pkey → decryptKey`，配置变更时清空缓存。
- **首次轮询 jitter**：错开多个房间同时开始的请求尖峰。
- **后处理异步化**：PostProcessor 使用 per-room Channel + 全局 Semaphore，阻塞 IO 在 `Dispatchers.IO` 执行。
- **优雅停机**：`ShutdownCmd` 先停止调度，再等待 Recording/Fetching 会话和 PostProcessor 完成。

### 7.6 对应图表

- 组件架构图：`recording-engine-architecture.png`
- 核心时序图：`recording-engine-sequence.png`
- 会话状态机：`recording-engine-state.png`
- PlantUML 源文件：`.puml` 同名文件
