# XhREC

[English](README.md)

自动直播录制的 Kotlin 应用，配合浏览器扩展实现一键控制。

## 快速开始

```shell
./gradlew build
java -jar build/libs/XhRec-all.jar
```

打开 `https://localhost:8090` 进入控制台。

## CLI 选项

| 选项             | 描述              | 默认值                |
|------------------|------------------|----------------------|
| `-f`, `--file`   | 房间列表配置文件      | `list.conf`          |
| `-o`, `--output` | 输出目录            | `out`                |
| `-t`, `--tmp`    | 临时目录            | `tmp`                |
| `-p`, `--port`   | HTTP 服务端口       | `8090`               |
| `-u`, `--users`  | 用户文件            | `users.txt`          |
| `-post`          | 后处理器配置文件     | `postprocessor.json` |

```shell
java -jar build/libs/XhRec-all.jar -p 12340 -f list.conf -post postprocessor.json -t /tmp/xhrec -o /out
```

## 配置

### list.conf

每行一个房间。以 `#` 开头的行是**未激活**的房间（不会自动录制），`#` 后面可以带空格
（`# https://...`）也可以不带（`#https://...`），其余字段照常解析。以 `;` 开头的行则被
整行忽略，房间不会被加载。

```ini
#https://stripchat.com/modelA q:720p limit:120
; https://stripchat.com/modelB q:240p
https://stripchat.com/modelC q:highest
https://stripchat.com/modelD q:highest nopublic nofreespy autopay:private
```

| 字段            | 描述                                                                                                          |
|-----------------|-------------------------------------------------------------------------------------------------------------|
| `q:<quality>`   | 画质偏好: `240p`, `480p`, `720p`, `720p60`, `1080p`, `1080p60`, 或 `highest` (默认)。`raw` 已弃用，请用 `highest` |
| `limit:<sec>`   | 录制时长限制（秒）                                                                                               |
| `size:<bytes>`  | 录制大小限制（支持后缀: `K`, `M`, `G`, 如 `500M`）                                                                |
| `pkey:<key>`    | 自定义 psch 密钥                                                                                               |
| `nopublic`      | 不录制公开（免费）秀                                                                                             |
| `nofreespy`     | 不录制可用免费偷窥额度观看的私密秀                                                                                 |
| `autopay`       | 自动购买门票与偷窥（等价于 `autopay:ticket autopay:private`）                                                      |
| `autopay:ticket`  | 自动购买门票以录制群秀                                                                                        |
| `autopay:private` | 自动花费代币录制私密秀                                                                                        |

录制开关的默认值：公开秀开启、免费偷窥开启、门票自动购买关闭、偷窥自动付费关闭。
因此 `nopublic` 与 `nofreespy` 是"取消默认"的开关（不写即保持默认），而 `autopay`
两项则需要显式开启。四个开关都可以在面板里按房间修改（房间行右侧的滑杆按钮）。

如果请求的画质不可用，系统会自动选择最接近的匹配项。

### xhrec.json

平台域名与流解密密钥存储。首次运行时如不存在则会创建默认文件。

```json
{
  "platformHosts": ["stripchat.com"],
  "webSocketHosts": ["websocket-v6.xhamsterlive.com"],
  "hlsHosts": ["media-hls.doppiocdn.org"],
  "hlsMasterHost": "edge-hls.doppiocdn.org",
  "webHost": "xhamsterlive.com",
  "previewHost": "zh.xhamsterlive.com",
  "thumbHost": "img.doppiocdn.org",
  "streamAuthKey": "默认 psch 密钥，如果无法从 master playlist 中提取则使用此值",
  "maskSensitiveLogs": true,
  "logLevel": "",
  "decryptKeys": {
    "psch 密钥 1": "解密密钥",
    "psch 密钥 2": "解密密钥"
  }
}
```

| 字段                 | 描述                                                                                                                                      |
|---------------------|------------------------------------------------------------------------------------------------------------------------------------------|
| `platformHosts`     | 平台 API 域名列表（仅主机名，不带 `https://`，有序，第一个为主域名）。请求失败时该域名进入冷却，自动切换到下一个可用域名。默认 `["stripchat.com"]` |
| `webSocketHosts`    | WebSocket 域名列表（有序，同样支持失败自动切换）。默认 `["websocket-v6.xhamsterlive.com"]`                                                |
| `hlsHosts`          | CDN 数据域名列表（媒体 playlist 与分片）。系统按 EWMA 记录每个域名的下载速度，优先选择最快域名，同时约 10% 的连接随机分配给其它域名以保持测速新鲜。默认 `["media-hls.doppiocdn.org"]` |
| `hlsMasterHost`     | master playlist 域名，失败时依次尝试 `hlsHosts` 作为后备。默认 `edge-hls.doppiocdn.org`                                                    |
| `webHost`           | WebUI 房间链接域名。默认 `xhamsterlive.com`                                                                                                |
| `previewHost`       | WebUI 快照预览 API 域名。默认 `zh.xhamsterlive.com`                                                                                        |
| `thumbHost`         | WebUI 缩略图域名。默认 `img.doppiocdn.org`                                                                                                 |
| `streamAuthKey`     | 用于流认证的默认 psch 密钥                                                                                                                   |
| `maskSensitiveLogs` | 启用日志脱敏（主播名、cookie、token、代理地址）。可在 WebUI 中或通过 `/mask/toggle` 切换。默认 `true`                                         |
| `logLevel`          | 根日志等级（`TRACE`\|`DEBUG`\|`INFO`\|`WARN`\|`ERROR`\|`OFF`）。可在 WebUI 中或通过 `/log/level` 运行时修改。留空则沿用 `logback.xml` 的配置        |
| `apiToken`          | 设置后，除 `/` 外的所有接口都需要携带 `?token=` 或 `Authorization: Bearer …`                                                                  |
| `decryptKeys`       | 解密密钥映射表（psch 密钥 → 解密密钥）                                                                                                         |

所有域名也可以在运行时通过 WebUI（工具栏网络图标）或 `GET /config/hosts` / `POST /config/hosts` 管理，
修改会持久化到 `xhrec.json` 并即时生效（WebSocket 自动重连，CDN 优选立即切换）。

### users.txt


自动支付使用的用户 cookie。每行一个 cookie。以 `#` 或 `;` 开头的行被忽略。

每个 cookie 在启动时会向平台 API 验证，获取用户的 ID、名称和金币余额。当私密秀需要付费时，系统会选择一个余额充足的用户。

```
# 可选注释
cookie_string_here
```

## WebUI 与浏览器扩展

### 控制台

`https://localhost:8090` — 管理房间、查看实时状态、控制录制。

![控制台](image.png)

#### 导入收藏

工具栏的心形按钮可以导入账号在站点上收藏的主播：

1. 勾选要读取收藏的账号（来自 `users.txt`）；
2. 点击「获取收藏」，把每个收藏的主播解析成房间名 —— 已是房间的会标记为「已存在」，不可重复勾选；
3. 取消勾选不想录制的主播，点击「导入选中」。

导入的房间默认为**未启用**（在 `list.conf` 中写为注释行），需要手动激活后才会开始录制。

### 用户脚本

[安装](https://greasyfork.org/zh-CN/scripts/582444-xhrec-control-panel)
![用户脚本](image-1.png)

## API 参考

所有接口均返回 JSON（除非另有说明）。参数通过查询字符串传递。

### 房间管理

| 接口       | 参数                                                              | 描述                 |
|------------|------------------------------------------------------------------|---------------------|
| `/add`     | `name`, `quality`, `active`, `limit`, `autopayTicket`, `autoPaySpy`, `pkey`, `size` | 添加房间 |
| `/remove`  | `id`                                                             | 删除房间              |
| `/restart` | `id`                                                             | 停止后重新开始录制      |
| `/break`   | `id`                                                             | 暂时中断（下次轮询时恢复）|

### 房间设置

| 接口          | 参数                      | 描述                     |
|---------------|--------------------------|-------------------------|
| `/activate`   | `id`                     | 启用自动录制               |
| `/deactivate` | `id`                     | 禁用自动录制               |
| `/quality`    | `id`, `q`                | 设置画质                  |
| `/filter`     | `id`, `kind` (`public`\|`freespy`\|`ticket`\|`paidspy`), `v` | 切换某个录制开关 |
| `/limit`      | `id`, `v` （秒）          | 设置时长限制（0 = 不限）     |
| `/sizelimit`  | `id`, `v`                | 设置大小限制（0 = 不限）     |

### 状态与监控

| 接口         | 描述                                              |
|-------------|--------------------------------------------------|
| `/status`   | 活跃房间状态（分段数、字节数、正在运行的下载任务）           |
| `/list`     | 所有房间的状态、会话状态、画质                          |
| `/dashboard`| 聚合数据: rooms, statuses, listv2, metrics，以及每个房间的 `hint`（解释已启用却未录制的原因: `public_filter_off`、`ticket_purchase_off`、`private_filter_off`、`no_free_spy`、`preconfig_failed`，可附带原始 `detail`） |
| `/diagnose` | 每个组件的内部状态：状态机、最近的迁移历史、邮箱积压                        |
| `/debug/stream` | 请求总线 / 数据通道 / 事件总线的实时 NDJSON 推送                      |
| `/log/level`| 运行时读取（GET）或修改（POST）根日志等级                                |
| `/metrics`  | Prometheus 指标接口                                 |
| `/mask/toggle` | 切换日志脱敏开关      |
| `/mask/status` | 获取当前脱敏状态（true/false） |

### 收藏导入

| 接口                     | 参数          | 描述                                                              |
|-------------------------|--------------|------------------------------------------------------------------|
| `/users`                |              | 已加载的账号（`userId`、`username`、`coins`；cookie 不出进程）           |
| `/favorites/candidates` | `users`（ID） | 这些账号的收藏，解析为房间名，`existing` 标记已存在的房间                   |
| `/favorites/import`     | `ids`（POST） | 把勾选的主播导入为未启用的房间                                          |

### 实时预览

| 接口        | 参数  | 描述                 |
|------------|------|---------------------|
| `/mse/live` | `id` | 正在录制中的 MP4 流    |

### 服务控制

| 接口              | 描述                       |
|------------------|---------------------------|
| `/graceful-stop` | 完成录制后关闭服务             |
| `/stop-server`   | 完成录制和后处理后退出          |

### Status 响应格式

```json
{
  "主播名": {
    "total": 10046,
    "success": 9933,
    "failed": 98,
    "bytesWrite": 1409108341,
    "running": {
      "https://...part3.mp4": {
        "type": "PROXY",
        "startAt": 1756357723403
      }
    }
  }
}
```

## 诊断与调试流

当某个房间看起来不对、日志却很安静时用这些接口——比如显示"录制中"却完全没有下载。它们都是只读的：
播放列表 URL 只保留 path，密钥类信息（streamAuthKey、apiToken、解密密钥、推流 token）只报告"是否存在"，
不会输出内容。

> 如果 `xhrec.json` 中设置了 `apiToken`，除 `/` 外的所有接口都需要携带：追加 `?token=<apiToken>`，
> 或发送 `Authorization: Bearer <apiToken>`。

### `/diagnose` —— 查看每个组件的内部状态

不带参数时列出当前存活的组件、各自支持的 section，以及是否有调试 tap 处于开启状态：

```shell
curl -sk https://localhost:8090/diagnose
```

```json
{
  "components": [
    { "name": "SchedulerComponent", "sections": ["summary", "entries", "history"] },
    { "name": "SessionComponent", "sections": ["summary", "entries", "history"] },
    { "name": "DownloaderComponent", "sections": ["summary", "entries"] }
  ],
  "monitor": { "watched": [], "dropped": 0 }
}
```

加上 `actor=` 返回该组件的视图。所有组件都支持 `summary`（身份、存活状态、`mailboxDepth`）；
`section=entries` 增加逐房间 / 逐文件的细节；`section=history` 返回状态机最近的迁移记录；
`room=<id>` 只保留指定房间。

```shell
curl -sk "https://localhost:8090/diagnose?actor=SessionComponent&section=entries&room=206236901"
```

```json
{
  "actor": "SessionComponent",
  "started": true,
  "mailboxDepth": 0,
  "sessionCount": 1,
  "states": { "206236901": "Recording" },
  "entries": [
    {
      "roomId": 206236901,
      "roomName": "fox-yiyi",
      "quality": "240p",
      "playlistPath": "https://media-hls.doppiocdn.org/b-hls-22/206236901/206236901_240p_h264.m3u8",
      "noProgressMs": 1932000,
      "segmentIndex": 0,
      "lastSegmentId": 1750,
      "lastPollSegmentCount": 3,
      "lastPollSkipped": 2,
      "lastPollEnqueuedMedia": false,
      "playlistLoopRunning": true,
      "skipStreak": { "skipped": 987, "minId": 1002, "maxId": 1004, "reported": true },
      "fsm": { "state": "Recording", "history": [ "…" ] }
    }
  ]
}
```

**怎么判断卡住的房间。** 下面几个字段能区分两种本来无法分辨的故障：

| 字段 | 含义 |
| --- | --- |
| `lastPollSegmentCount` | 最近一次播放列表实际提供了多少个媒体分片 |
| `lastPollEnqueuedMedia` | 其中是否有分片被真正排入下载队列 |
| `lastPollSkipped` | 其中有多少因已被续传标记覆盖而跳过（正常现象） |
| `lastPollGap` | 本次轮询播放列表跨过了多少个 id、从未展示给我们（已丢失） |
| `previousNewSegmentId` | 本会话已排队的最大 id，也是缺失检测的基线 |
| `lastSegmentId` | 续传标记；id 小于等于它的分片会被跳过 |
| `noProgressMs` | 距离上一次排入或收到数据已经过了多久 |

`lastPollSegmentCount > 0` 而 `lastPollEnqueuedMedia` 为 `false`，说明播放列表是正常的，但所有分片都落在
续传标记之后——直播流还没追上标记。`lastPollSegmentCount = 0` 则说明播放列表本身就是空的。某个组件的
`mailboxDepth` 持续增长，说明它的 actor 正在积压，而不是空闲。

`section=history` 展示房间是如何走到当前状态的，答案通常就在这里：

```shell
curl -sk "https://localhost:8090/diagnose?actor=SchedulerComponent&section=history&room=206236901"
```

```json
{
  "history": {
    "206236901": [
      { "at": "21:35:08.782", "from": "Preconfiguring", "event": "PreconfigFailed", "target": "KEEP", "data": "SchedulerDriveData(failReason=no free spy access, …)" },
      { "at": "21:35:08.782", "from": "Preconfiguring", "event": "BackToArmed", "target": "-> Armed" },
      { "at": "22:21:15.464", "from": "Armed", "event": "RoomStatusChanged", "target": "KEEP", "data": "SchedulerDriveData(roomStatus=public, …)" },
      { "at": "22:21:15.464", "from": "Armed", "event": "BeginPreconfig", "target": "-> Preconfiguring" },
      { "at": "22:21:49.002", "from": "Preconfiguring", "event": "PreconfigDone", "target": "-> Recording", "data": "SchedulerDriveData(quality=240p, …)" }
    ]
  }
}
```

### `/debug/stream` —— 内部总线的实时推送

把请求总线、数据通道、事件总线以 **一行一个 JSON**（NDJSON）推送给客户端。`types` 是
`request`、`data`、`event` 的逗号分隔子集（默认 `request,data`）：

```shell
curl -skN "https://localhost:8090/debug/stream?types=request,data"
```

```
{"kind":"hello","types":["request","data"],"dropped":0}
{"seq":1,"ts":1789140029897,"kind":"request","id":7,"cmd":"GetRooms","ms":1,"result":"[]"}
{"seq":2,"ts":1789140029898,"kind":"request","id":8,"cmd":"GetRecordingHints","ms":0,"result":"RecordingHintsResponse(count=0)"}
{"seq":3,"ts":1789140029899,"kind":"data","msg":"StreamData","room":206236901,"bytes":131072}
{"kind":"heartbeat","dropped":0}
```

| `kind` | 字段 |
| --- | --- |
| `hello` | 首行：已接受的 `types` 与 `dropped` 计数 |
| `request` | `id`、`cmd`、`ms`（耗时）、`result`（或错误 / `TIMEOUT after Nms`） |
| `data` | `msg`（`StreamStart`/`StreamData`/`StreamEnd`/`StreamEvent`）、`room`、`bytes` |
| `event` | `event`（类型名）、`detail`（截断后的 `toString`） |
| `heartbeat` | 空闲时写出；同时也是服务端感知客户端断开的唯一途径 |

这些 tap **默认关闭，且无人订阅时零开销**：每个生产者会先检查是否有客户端需要该类目，再决定是否构造这一行。
客户端消费慢只会丢掉最旧的行（计入 `dropped`），绝不会阻塞下载。断开连接会自动释放 tap ——
`/diagnose` 的 `monitor.watched` 可以看到当前状态。

配合 `jq` 使用是最常见的方式：

```shell
# 只看耗时较长的请求
curl -skN "https://localhost:8090/debug/stream?types=request" | jq -c 'select(.kind=="request" and .ms > 100)'

# 跟踪某个房间的数据流
curl -skN "https://localhost:8090/debug/stream?types=data" | jq -c 'select(.room==206236901)'
```

### `/log/level` —— 运行时修改日志等级

```shell
curl -sk https://localhost:8090/log/level
# {"level":"DEBUG","levels":["TRACE","DEBUG","INFO","WARN","ERROR","OFF"]}

curl -sk -X POST -d "level=TRACE" https://localhost:8090/log/level
# TRACE

curl -sk -X POST -d "level=LOUD" https://localhost:8090/log/level
# HTTP 400: Unknown log level: LOUD
```

等级会立即作用于 root logger，并持久化到 `xhrec.json` 的 `logLevel` 字段，重启后依然生效。
`logback.xml` 中单独固定等级的第三方库日志（netty、ktor、jetty）不受影响。WebUI 工具栏（虫子图标）
提供同样的开关。

## 后处理

在 `postprocessor.json` 中定义。录制结束后处理器按顺序依次执行。

### 内置处理器

| 类型         | 描述               |
|-------------|-------------------|
| `fix_stamp` | 修复 MP4 时间戳     |
| `move`      | 移动/重命名输出文件   |
| `slice`     | 分割视频为多段       |
| `shell`     | 执行任意 shell 命令  |

### 模板变量

可在 `move` 的目标路径和 `shell` 的参数中使用：

| 变量                        | 描述                                  |
|----------------------------|--------------------------------------|
| `{{ROOM_NAME}}`            | 主播/房间名                             |
| `{{ROOM_ID}}`              | 房间 ID                               |
| `{{RECORD_START}}`         | 格式化的开始时间                         |
| `{{RECORD_END}}`           | 格式化的结束时间                         |
| `{{RECORD_DURATION}}`      | 时长（秒）                              |
| `{{RECORD_DURATION_STR}}`  | 格式化时长，如 `00h01m30s`               |
| `{{RECORD_QUALITY}}`       | 画质字符串                              |
| `{{INPUT_ABS}}`            | 输入文件完整路径                         |
| `{{INPUT_DIR}}`            | 输入文件所在目录                         |
| `{{INPUT_NAME}}`           | 输入文件名                              |
| `{{INPUT_NAME_NOEXT}}`     | 不带扩展名的文件名                        |
| `{{TOTAL_FRAMES}}`         | 精确总帧数                              |
| `{{TOTAL_FRAMES_GUESS}}`   | 估算总帧数（FPS × 时长）                 |

### 示例配置

```json
{
  "default": [
    {
      "type": "fix_stamp",
      "output": "out"
    },
    {
      "type": "move",
      "output": "out/[{{ROOM_ID}}]{{ROOM_NAME}}@{{RECORD_START}}-{{RECORD_END}} {{RECORD_DURATION_STR}}",
      "date_pattern": "yyyy-MM-dd HH:mm:ss"
    },
    {
      "type": "slice",
      "output": "out",
      "duration": "1m10s"
    },
    {
      "type": "shell",
      "noreturn": true,
      "remove_input": false,
      "date_pattern": "yyyy-MM-dd_HH-mm-ss",
      "cmd": [
        "ffmpeg",
        "-hide_banner",
        "-loglevel",
        "error",
        "-stats",
        "-i",
        "{{INPUT_ABS}}",
        "-vf",
        "thumbnail={{TOTAL_FRAMES_GUESS}}/400,scale=200:-1,tile=20x20",
        "-vframes",
        "1",
        "{{INPUT_DIR}}/{{INPUT_NAME_NOEXT}}.thumb.png",
        "-y"
      ]
    }
  ]
}
```

## 日志与监控

日志写入 `./logs` 目录，按天轮转 (`xhrec.yyyy-MM-dd.log`)。

### 日志脱敏

默认开启，敏感信息在日志输出中被替换。静态模式（JWT token、cookie、认证 URL 参数、代理地址）替换为 `***`。动态字符串（主播名、用户名）在启动时注册，替换为基于 CRC32 的稳定哈希值——同一会话内保持不变，重启后更新，便于日志关联分析同时保护隐私。

脱敏功能可通过 WebUI 工具栏中的眼睛图标实时切换，或通过 API 控制：

```shell
curl -k https://localhost:8090/mask/toggle     # 开启/关闭
curl -k https://localhost:8090/mask/status     # 查看当前状态
```

该设置持久化到 `xhrec.json` 的 `maskSensitiveLogs` 字段。

### 日志等级

根日志等级无需重启即可调整——WebUI 工具栏（虫子图标）或 `POST /log/level`，见
[诊断与调试流](#诊断与调试流)。它会持久化到 `xhrec.json` 的 `logLevel` 字段；该字段留空则沿用
`logback.xml` 的配置。排查某个具体房间时值得开到 `TRACE`/`DEBUG`，平时用 `INFO` 可以让日志文件小很多。
`logback.xml` 中单独固定等级的第三方库（netty、ktor、jetty）不受影响。

### 排查卡住的录制

**跳过（skipped）是正常稳态，不是故障。** 播放列表是滑动窗口，每次轮询都会重复列出刚写过的分片，所以任何
健康录制都会跳过重叠部分、只下载新增的。这些跳过次数计入 `xhrec_segments_skipped_total{roomId=…}`，因此对
**任何正在录制的房间**它都会持续增长。真正要看的信号是：这个计数器在涨、而 `xhrec_downloaded_total` 不动。

相反方向的故障是 `xhrec_segment_missing_total{roomId=…}`：**流确实发布过、但播放列表从未告诉我们的 id**。
比如上一次刷新停在 id 3，下一次直接是 7，那么 4、5、6 就丢了——播放列表跨过了它们。管线里其他环节都发现不了
（一个我们从没听说过的分片，既不会失败、也永远不会到达下载器），所以由会话自己统计，并把每次跳跃记到 `DEBUG`：

```
DEBUG v3.SessionEntry - roomId=206236901 playlist went from segment id 1023 to 1028; 4 id(s) in between were never advertised
```

**会话接缝处不计入缺失**：时限切分、`Break`、重新激活之后，会话没有更早的观测可以对比；若把重启当作基线重置之外
还去比较，就会把每一次正常的切分都误报成丢数据。

每次轮询的明细在 `TRACE`（可在 WebUI 工具栏或 `POST /log/level` 打开），所以不会污染默认的 `DEBUG` 日志。
读法是"播放列表提供的 M 个 id 中有 N 个已被覆盖，随后标记从 A 推进到 B"：

```
TRACE v3.SessionEntry - roomId=152807806 skipped 1 of 3 advertised segment(s): id 1063 already at or below the resume mark (mark 1063 -> 1065)
```

`id` 是**被跳过集合自身**的范围——只跳过一个时就是一个单值，不是播放列表窗口；而标记打印成 `A -> B`，是因为
到这里它已经被本轮最新的 id 推进过了。所以上面这行表示：窗口是 `1063..1065`，进入本轮时标记是 `1063`，因此
1063 已经写过被跳过，而 1064、1065 是新入队的并把标记推进到了 1065。

当超过 `thresholdSkipLogDelay` 一直没有新分片时，会话会**报告一次**，并且说的是真正发生的事，而不是累计的
跳过事件数（同一批 id 每次轮询都会被重复计数，累计值会严重夸大）：

```
WARN  v3.SessionEntry - roomId=170139817 no new segments for 32s: the playlist still advertises only id 1025..1027, all already covered by the resume mark (1027)
INFO  v3.SessionEntry - roomId=206236901 caught up with the resume mark after 2154s; 1102 skip event(s) (id 1002..1789), recording resumed
```

普通切分后的短暂重叠是静默的，只有**持续**无新分片才会报告。另外两类值得留意的日志：

```
WARN  v3.SessionEntry - Recording stalled roomId=…: no segment for 90s (playlist carries 0 media segment(s));
      ending the session so the room re-resolves its stream
WARN  v3.SchedulerEntry - Preconfig failed room=…: playlist unusable (HTTP 404)
```

前者是看门狗在会话超过 `sessionStallTimeout` 没有任何进展时主动结束它，之后房间会重新执行 preconfig；
后者是 preconfig 探测无法使用该清晰度播放列表——可能是 HTTP 状态码、探测超时，或请求失败。
其中 403/404 还会让调度器请求 RoomComponent 重新读取房间状态：平台已经发放了 token，此时 CDN 拒绝播放列表
说明直播已经发生变化，刷新后的状态会让已经不可录制的房间重新回到 `Armed`，而不是对着一个已经消失的流反复重试。
失败往往紧接着前一个失败（会话刚失败、preconfig 又失败），所以一次失败请求不会变成两次平台请求：第一次失败
立刻读取房间，随后同一房间在 `roomStatusRefreshWindow` 内的失败提示会合并成窗口末尾的一次补读——被 debounce，
但不会被丢弃，因此第一次读取之后才发生的状态变化仍能被及时看到。手动激活属于命令而非提示，永远会真正读取，
并为它之后的失败提示开启同一个窗口。

当日志不够用时，`/diagnose` 可以交互式地看到同样的状态，见 [诊断与调试流](#诊断与调试流)。

Prometheus 指标暴露在 `/metrics`。Grafana 仪表盘示例：

![grafana](img_1.png)
