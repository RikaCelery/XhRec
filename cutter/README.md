# XhCut

远程无损粗剪工具，专用于剪辑 XhRec 的录制视频。UI 参考 LosslessCut，**文件选取与全部
ffmpeg 计算都在录制主机内完成**，客户端只负责交互，因此可以在低带宽链路上剪辑 6 小时
级的原始录像。

```
浏览器 (Mac, ~180ms RTT)  ──HTTP──▶  录制主机 10.0.2.202:8092
  Vue 3 单页应用                       Ktor / Netty (容器 xhcut, uid 1000)
  hls.js 低清预览                      ├─ MediaIndex     扫描 / 配对 / 缓存
  主时间轴 + 辅助车道                    ├─ EventParser    解析 .event
                                       ├─ EventTimeMapper 事件 → 媒体时间
                                       ├─ ToyTimeline     玩具 / 礼物 / 等级车道
                                       ├─ AudioLanes      电平图 / 频谱
                                       ├─ PreviewServer   按需 HLS 转码
                                       ├─ CutEngine       关键帧对齐 copy 剪切
                                       ├─ ExportQueue     导出队列
                                       ├─ CutSuggester    切分建议
                                       └─ Llc             JSON5 项目读写
```

## 快速开始

不用自己编译也可以：每次 main 有推送，CI 都会把 **`XhCut-all.jar`** 连同 `XhRec-all.jar`
一起更新到 [dev-build release](https://github.com/RikaCelery/XhRec/releases/tag/dev-build)。
下载后按「部署」第 2 步上传即可。

```bash
./gradlew :cutter:shadowJar      # 编译出 cutter/build/libs/cutter-all.jar
./cutter/check-ui.sh             # 校验 cutter.html 内联模块的 JS 语法与暴露符号
./gradlew :cutter:test           # 99 个测试
```

部署见下一节；起好之后浏览器打开 `http://<主机>:<端口>/`，或深链到某个录制
`http://<主机>:<端口>/?id=<mediaId>`。

## 部署

XhCut 跑在录制主机上，产物是一个自带 jar 的容器镜像。下面四步就是全部流程，
`user@host`、三个宿主目录按你的环境替换即可——**仓库里不保存任何主机的路径**。

### 1. 拿到 jar

自己编译：

```bash
./gradlew :cutter:shadowJar
# -> cutter/build/libs/cutter-all.jar
```

或者直接从 [dev-build release](https://github.com/RikaCelery/XhRec/releases/tag/dev-build)
下载 `XhCut-all.jar`（CI 在 main 每次推送时更新它）。

### 2. 上传三个文件

```bash
ssh user@host 'mkdir -p ~/xhcut'
# 本地编译的话用 cutter/build/libs/cutter-all.jar；下载的话用 XhCut-all.jar
scp XhCut-all.jar cutter/Dockerfile cutter/entrypoint.sh user@host:~/xhcut/
```

镜像里固定把它读作 `/cutter-all.jar`（见 `Dockerfile` 的 `COPY`），所以上传时文件名保持
`cutter-all.jar` 最省事：

```bash
cp XhCut-all.jar cutter-all.jar
```

### 3. 在主机上构建镜像

```bash
ssh user@host 'cd ~/xhcut && docker build -t xhcut .'
```

### 4. 起容器

三个宿主目录**必须已存在**，容器不会在宿主机上创建任何东西：

```bash
ssh user@host 'mkdir -p /path/to/cache'      # 只需一次
```

```bash
ssh user@host 'docker rm -f xhcut 2>/dev/null; docker run -d \
  --name xhcut --restart unless-stopped \
  --user 1000:1000 \
  --gpus all \
  -p 8092:8092 \
  -e TZ=Asia/Shanghai \
  -e NVIDIA_DRIVER_CAPABILITIES=all \
  -e XHCUT_MEDIA=/media/out \
  -e XHCUT_OUT=/media/cuts \
  -e XHCUT_CACHE=/cache \
  -v /path/to/recordings:/media/out:rw \
  -v /path/to/cuts:/media/cuts:rw \
  -v /path/to/cache:/cache:rw \
  --log-opt max-size=10m --log-opt max-file=3 \
  xhcut'
```

要改的只有 `-v` 左边那三个宿主路径；右边的 `/media/out`、`/media/cuts`、`/cache`
是容器内部的挂载点，随便叫什么，只要和 `-e XHCUT_*` 对得上。

| 项 | 说明 |
|---|---|
| `--user 1000:1000` | 以拥有录像语料的 uid 运行，成品属主才对；缓存目录也要对它可写 |
| `--gpus all` + `NVIDIA_DRIVER_CAPABILITIES=all` | 代理转码走 NVENC。**两个都要**：默认的 capability 集合只挂 `libnvidia-ml`（够 `nvidia-smi` 用），不挂 `libnvidia-encode`，会报 `Cannot load libnvidia-encode.so.1`。没有 GPU 的主机可以整段去掉，会自动退回 libx264 |
| 媒体根用 `rw` | 编辑器要能删录像、以及"导出后删除源文件"；挂成 `ro` 这些操作会返回 409 |
| 三个宿主目录必须已存在 | 路径写错应当在这里失败，而不是起一个容器往新建的空目录里静默地写 |

`entrypoint.sh` 在启动时要求 `XHCUT_MEDIA`/`XHCUT_OUT`/`XHCUT_CACHE` 都已设置，缺任一
直接以退出码 2 退出——镜像里**不预置**这三个路径，所以"忘了传"不会假装启动成功。

### 更新

重复 1–4 即可。第 4 步的 `docker rm -f` 会中断正在录的那个文件，所以想只换镜像、
不动运行中的容器，就只跑到第 3 步。

### 本地常用脚本

上面这套流程在本机是用一个不纳入版本控制的本地脚本封装的（路径参数化、构建 + 上传 +
重建 + 重启一条命令）。仓库里只保留文档，`cutter/deploy.sh` 已被 `.gitignore` 忽略。

## 命令行参数

**`-m/--media`（可逗号分隔多个根）、`-o/--out`、`-c/--cache` 三者必填**，缺任一个直接以
退出码 2 报错退出。这三个位置以前会各自回退到容器镜像里的路径（`/media/out`、`/media/cuts`、
`/cache`）——那让"忘了传"看起来像"启动成功"：进程起来了，扫的是一个不存在或不属于它的
目录，然后对外提供一个空库，且没有任何报错。容器由 `entrypoint.sh` 显式传这三项
（未设置 `XHCUT_MEDIA`/`XHCUT_OUT`/`XHCUT_CACHE` 也会拒绝启动），其他调用方必须自己说清。

其余可选：`-p/--port`、`-tz/--timezone`、`--ffmpeg`、`--ffprobe`、`--preview-segment`、
`--preview-concurrency`、`--cache-limit-gb`、`--min-free-gb`、`--skip-dirs`。

## 关键设计决定（均基于实测）

### 1. 扫描必须避免 per-file stat

`/media/nas/out` 是 CIFS 挂载，单次 `stat` 约 0.3 ms，全树约 45k 条目。第一版实现对每个
录制调用 `isDirectory` + `length` + `lastModified` + 最多四次 `isFile`（约 6–7 次 stat，
>20 万次系统调用），扫描耗时 **>150 s**。现在固定为每个录制**正好一次**
`Files.readAttributes`，`.event` 配对完全靠目录 listing 的名字匹配（零 stat）：

| 做法 | 耗时 |
|---|---|
| 纯目录遍历（无 stat） | 11.7 s / 45144 条目 |
| 每个 `.mp4` 一次 stat | 10.6 s / 34085 次 |
| **当前实现（遍历 + 每录制一次 stat）** | **7.5–10 s / 31819 个录制** |

隐藏目录与隐藏文件一律跳过（`.unwanted` 里有 2268 个已弃用录像）。索引落盘缓存，重启
即时可用。

### 2. `.event` 的时间戳不能靠"扫所有 ISO 时间"

只有带 `message` 的事件才有 `createdAt`，而 `goalChanged`、`groupShow`、
`interactiveToyStatusChanged` 恰好是最有价值的三类且**都没有时间戳**。更糟的是朴素的
"取最大 ISO 时间"会命中**截止时间**字段——实测最坏把事件挪走 **85920 s**。

因此只用**路径白名单**取时间戳：`message.createdAt`、`show.createdAt`、`statusChangedAt`、
`startedAt`、`createdAt`、`deletedAt`。嵌套的 `ban.*`、`user.*`、`model.*` 是历史记录，
一律不看：语料里有一条 `userBanned` 携带 **124 天前**的 `ban.createdAt`。

其余事件按**行号在相邻锚点间线性插值**（事件文件严格追加有序）。另外有一道单调性护栏：
落后当前最大值超过 60 s 的时间戳视为陈旧值并丢弃，改走插值。实测 33 个真实文件：无事件
早于录制起点，末事件落在录制结束 **1.0 s** 以内。

### 3. 玩具 FIFO 不需要自己模拟

玩具命令队列是 FIFO 的，但**事件流已经把出队时刻写下来了**：`message.type="lovense"` 的
`createdAt` 是命令**开始执行**的时间，不是礼物到达时间。实测 6 个玩具密集录制、1387 组相邻
命令中 **58%** 满足 `next.createdAt ≈ prev.createdAt + prev.time`（±1 s），即首尾精确相接。

所以活跃区间就是 `[createdAt, createdAt + detail.time]`，**最后一笔礼物之后玩具仍在跑的
积压尾巴自动包含在内**——那通常正是最值得剪的部分。`clear`/`pause` 截断区间。

`detail.time` 的中位数只有 **2 秒**（且 `amount=1` 的微额礼物占多数），原始区间极度抖动，
因此必须经过建议流水线的膨胀+黏合+去抖。

### 4. 剪辑永远 copy，且必须先把 `-ss` 对齐到关键帧

关键帧间隔 2 s。`-ss` 落在非关键帧上时 ffmpeg 会回退到前一个关键帧：实测请求 30 s 实得
**31.936 s**；把 `-ss` 精确对齐到关键帧后实得 **30.016 s**（600 帧 = 20 s × 30fps，完全吻合）。

`ffprobe -read_intervals` 把关键帧探测限制在目标附近几秒，这是可负担的关键：85 min /
1.2 GB 的有界探测只要 **0.15 s**，列全部关键帧要 8.2 s。

导出命令中的编码器参数由 `assertNoEncoding` 断言拦截：codec 选项只允许取值 `copy`，
`-crf`/`-preset`/`-vf`/`-b:v`/`libx264` 等一律拒绝。**不存在重编码路径。**

### 5. 音频分析已经触到解码下限

| 操作 | 60 s 窗口 | 85 min 全片 | 6 h 全片 | 产物 |
|---|---|---|---|---|
| 纯解码（下限） | 0.104 s | ~3.2 s | ~13.5 s | — |
| 电平图 2000×300 | 0.10 s | 3.4 s | 13.5 s | 21 KB |
| 电平图 32000×600 | — | 3.7 s | ~14 s | 280 KB |
| 频谱 1024×256 | 0.18 s | ~2.4 s | ~9.5 s | 531 KB |
| 频谱 1920×512 | 0.37 s | 4.7 s | ~19 s | 2.0 MB |

电平图是**解码受限**的（不加 resample 0.118 s vs 纯解码 0.104 s），加宽输出几乎免费
（2000→32000 px 只多 0.3 s），所以整个录制缓存一张宽总览 PNG，客户端裁剪缩放，服务端零
开销。频谱是**滤波受限**且与输出像素数成正比，因此只在查看的窗口按需生成，默认 1024×256，
绝不自动全片生成。

> ⚠️ **`showwavespic` / `showspectrumpic` 会消费整条输入流后才吐一帧**，所以放在 `-i`
> **之后**的 `-t` 会被静默忽略：`-t 1` 与 `-t 600` 产出的 PNG md5 完全相同
> （`d47974890d21b465`）。本项目的 `-ss`/`-t` 一律放在 `-i` **之前**。

### 6. 预览必须按需转码

客户端带宽实测约 750 KB/s。分片按需转码并落盘缓存，因此拖动到 6 小时录像的任意位置只需
一次小转码，之后都是纯文件读取：

| 档位 | 4 s 分片 | 码率 |
|---|---|---|
| tiny (240p/CRF36) | 144 KB | ~287 kbps |
| low (360p/CRF32，默认) | 321 KB | ~640 kbps |
| high (720p/CRF26) | 1.39 MB | ~2.8 Mbps |

正确性用 SSIM 验证：第 100 个分片（应为 400 s）与源文件 400 s 处 **SSIM 0.958**，与 420 s
处对照仅 **0.554**——分片时间轴精确可信，这是能剪准的前提。

VAAPI 在这台机器上不可用（`Failed to initialise VAAPI connection`），全部软编。

### 7. 建议流水线：膨胀 → 黏合 → 去抖 → 评分 → 限长

原始信号直接切会产生数千个 2 秒碎片。默认参数：

| 参数 | 默认 | 依据 |
|---|---|---|
| `padBefore` / `padAfter` | 8 s / 6 s | 反应先于礼物 |
| `mergeGap` | 15 s | 链式续跑 0–1 s 必然合并；真实空闲 p75≈24 s、23% >30 s 必然保留 |
| `minBlock` | 45 s | 低于此长度是抖动不是内容 |
| `absorbGap` | 45 s | 邻近短块并入邻块而非丢弃 |
| `maxBlock` | 600 s | 超长在**密度最低处**切开，不切在动作中途 |

`mergeGap`/`minBlock`/`pad`/`minScore` 都暴露在 UI 滑杆上**实时重算**——这是"避免抖动、
黏合空洞"的直接操作方式。每个建议块带 `reasons[]`，说明"为什么建议剪这里"。

## 时间轴与播放器

时间轴按专业剪辑软件的方式实现：所有元素（刻度尺、片段轨、各辅助车道、播放头）都定位在
同一个 `zoom × 100%` 宽的容器里，用百分比定位，因此在任何缩放级别都严格对齐。

```
刻度尺（可拖动定位，含时间码 + 关键帧刻度）
片段轨（可拖动、可裁切的片段块）
玩具运行 / 礼物 / 玩具等级 / 聊天密度 / 音频电平 / 频谱
播放头：贯穿所有车道的红线 + 顶部可抓取三角
```

**片段块**：绿色=参与导出，灰色=未勾选，棕色高亮=当前片段，紫色=标记（无终点）。
块内显示序号徽章、名称与时长（宽度足够时）；左右各 7px 为裁切把手（`ew-resize`），
中间拖动整体平移。

**吸附**（默认开）：拖动时吸附到关键帧、其他片段边缘、播放头以及录制的 0/末尾，阈值 8px。
没有吸附，手工把切点对到关键帧基本靠猜——而起点没落在关键帧上正是 ffmpeg 会多切的原因。

**播放头**：拖动刻度尺或播放头三角即可 scrub；播放时随视频移动。定位后按 `Shift+←/→`
跳到当前片段首尾。

### 快捷键

| 键 | 作用 |
|---|---|
| `空格` | 播放 / 暂停 |
| `←` `→` | ±1 秒（`Ctrl/⌘` 为 ±10 秒） |
| `,` `.` | ±1 帧 |
| `Alt+←/→` | 上/下一关键帧 |
| `Shift+←/→` | 跳到当前片段首 / 尾 |
| `↑` `↓` | 上 / 下一片段 |
| `I` / `O` | 设置片段起点 / 终点 |
| `Backspace` | 清除终点（变为标记） |
| `B` | 在光标处分割 |
| `Shift+=` | 新增片段 |
| `Delete` | 删除当前片段 |
| `E` | 导出 |
| `J` `K` `L` | 减速 / 1× / 加速（每次 ∛2） |

### 鼠标

| 操作 | 作用 |
|---|---|
| 拖动刻度尺 / 播放头 | 定位 |
| 拖动片段中间 | 整体平移 |
| 拖动片段两侧把手 | 调整首 / 尾 |
| 双击片段 | 选中并跳到起点 |
| `Ctrl/⌘ + 滚轮` | 以光标为锚点缩放 |
| `Shift + 滚轮` | 上 / 下一关键帧 |
| `Alt + 滚轮` | 逐帧 |
| 滚轮 | 平移 |

### 预览模式

| 档位 | 说明 | 4 s 分片 |
|---|---|---|
| tiny / low / mid / high | 按需转码的 HLS，带宽友好（默认 low） | 144 KB / 321 KB / — / 1.39 MB |
| **无损** | **直接播放源文件，不转码**，浏览器用 Range 请求定位 | 源码率 |
| **本地代理** | 手动生成一次的 faststart 小文件，浏览器自行解码与缓存 | 见下 |

**本地代理不是默认**，默认始终是 HLS 按需模式（秒开）。代理需要手动点「生成 Np 代理」，
可选 **240p / 480p**，两种分辨率各自独立生成/删除；生成是后台任务并显示进度，
完成后时间轴会出现蓝色**缓冲条**，显示浏览器已经缓存、无需再请求的区间。

之所以需要代理而不是直接指向源文件：**录像不是 faststart**，`moov` 索引在文件尾部
（实测 6.4 GB 文件的索引在 6.39 GB 处、24.2 MB），浏览器必须先拉完整索引才能解码第一帧，
按 750 KB/s 算就是每次打开先卡 ~32 秒。代理用 `-movflags +faststart` 把索引前置。

六小时录像的实测代价（`-preset veryfast -crf 30`，生成一次、之后常驻）：

生成使用 **GPU（NVENC + CUDA 解码）**，并默认跳帧到 5fps。六小时录像 360p 实测：

| 帧率 | GPU 耗时 | GPU 体积 | CPU 耗时 | CPU 体积 |
|---|---|---|---|---|
| 全帧 30fps | — | — | 15.5 min | 974 MB |
| 10fps | 8.6 min | 447 MB | 12.2 min | 670 MB |
| **5fps（默认）** | **8.2 min** | **293 MB** | 10.5 min | 579 MB |
| **2fps（最快最小）** | **8.1 min** | **137 MB** | 9.9 min | 417 MB |

三个结论：

1. **降帧率不会劣化体积或速度，两者都单调变好**（CPU 与 GPU 皆然）。5fps→2fps 体积再减半而
   耗时几乎不变，所以 2fps 是"最省"档，5fps 是"能看清动作"档。
2. **GPU 只在跳帧时才有优势**：全帧 30fps 下 NVENC 反而比 CPU ultrafast 慢（8.5s vs 5.2s）；
   跳帧后解码量下降，GPU 才拉开差距（5fps 时 2.6s vs 3.9s，体积还减半）。
3. **1fps 不可用**：NVENC 的 VBR 码率控制在 1fps 下失控，每帧约 90 KB——实测 1903 MB，而
   2fps 只有 137 MB。固定 GOP 也无效，属于编码器行为，因此服务端把下限设为 2fps。

> `NVIDIA_DRIVER_CAPABILITIES=all` 是必需的。Docker 默认只挂 `libnvidia-ml`（够 nvidia-smi
> 用），不挂 `libnvidia-encode`/`libnvcuvid`，于是 NVENC 报 "Cannot load
> libnvidia-encode.so.1"，而 GPU 看上去一切正常。容器内没有 NVENC 时会自动回落到 libx264。

无损模式走 `GET /api/media/{id}/raw`，由 Ktor 的 `PartialContent` 提供字节范围支持
（实测 `bytes=0-1023` 与 `bytes=3000000000-300001023` 均返回 `206` + 正确的
`Content-Range`，包括超过 `Int.MAX_VALUE` 的偏移）。代价是完整源码率，所以是显式选择而非默认。

### 拖动时画面为什么会立刻跟随

按需转码的分片需要 0.3–1.3 s 才到达；如果在 `pointermove` 上反复写 `currentTime`，
hls.js 会以每秒几十次的频率中止并重启分片请求，结果是播放器卡住、后面全部无法预览。

拖动时播放头立即移动，并按约 **160 ms** 的节奏尝试加载最新位置的视频分片：让正在进行的
解码先完成，超过 1 s 仍未完成才替换为最新目标，避免连续取消请求。同时按 70 ms 节流取一张
播放头所在位置的静帧（`/api/media/{id}/frame`，实测任意位置 0.10–0.19 s，含 21900 s 处），加载完成才替换，
过期的响应直接丢弃；松手时提交精确位置，并恢复拖动前的播放/暂停状态。任何跨度超过 10 s 的跳转（键盘、建议列表、
事件表）也走同一机制。缩略图最多一个请求在途，连续移动合并为最新位置，避免抢占播放带宽；
定位完成后取消静帧请求，迟到的图片不会再盖住视频。加载期间始终保留目标位置，只有视频真正
到达目标后才恢复播放头同步，不会因为等待超过 3 s 跳回旧缓存。

HLS 分片使用源时间偏移（`-output_ts_offset`）保持连续时间戳，禁止每片重置到零；否则浏览器会
把新分片不断覆盖到开头的四秒，导致卡顿和错误跳转。修复版本使用 `pts2` 缓存目录与分片 URL
版本，自动避开旧缓存。切换清晰度保留当前位置及播放/暂停状态。

### 关键帧按需加载（性能护栏）

关键帧间隔约 2 秒。**全片列关键帧要遍历整个文件**——6.5 GB 的六小时录像是一次完整扫描，
既无意义又慢到会卡住页面。因此只有当"每关键帧像素间距"足够、且可见窗口不超过 900 秒时
才去取；服务端对超过 1800 秒的窗口直接返回 400，防止任何调用方触发全片扫描。

实测：60 秒窗口 **0.73 s** 返回 30 个关键帧；宽窗口请求被拒绝（`400 keyframe window too wide`）。

### 工作区、缩略图与分片编辑

- 文件列表、预览区、事件表和右侧标签页面板都有拖动分隔条。建议、片段和导出标签页分别记住宽度；
  每条时间轴轨道底部都能拖动调整高度，尺寸限制会为时间轴保留可用空间。
- 时间轴缩略图按当前可见窗口与缩放级别分桶，仅同时加载两张小图，并限制缓存大小；拖动定位时暂停
  缩略图后台请求，让目标视频分片优先加载。蓝色浏览器缓冲条响应分片完成事件，并每 250 ms 核对一次。
- 编辑状态保存在当前浏览器 localStorage，按录像分别存储片段、未完成的开始标记、当前片段、播放头、
  倍速、清晰度、缩放、滚动位置、标签页和最近的撤销记录。布局和轨道设置跨录像保留，刷新或重新打开
  自动恢复。`.llc` 仍可手动保存到服务端；浏览器存储写入失败时会提示备份。
- 「＋片段」或未选片段时的 I 建立一个开始标记；尚未完成时重复新建会移动同一个标记。O 必须严格晚于
  起点。选中已有片段后，I/O 调整该片段两端。拖动、数值输入、接受建议均禁止越界和覆盖其他片段/标记。
- 「合并勾选」合并有终点且已勾选的片段，保留中间间隔；如果合并范围内存在未勾选片段或其他标记，操作
  会被拒绝。分片增删、调整、分割和合并支持撤销/重做（Ctrl/⌘+Z、Ctrl/⌘+Shift+Z）。
- J 减速、K 恢复 1×、L 加速，另有直接选择倍速的菜单。快捷键在捕获阶段拦截，空格在按钮或链接获得焦点
  时也只控制播放；文本、数值输入与下拉框保留原生编辑行为。

### 深链

`?id=<mediaId>` 打开某个录制，`&at=<秒>` 定位光标，`&zoom=<倍数>` 指定缩放，
例如 `/?id=3e24fe9d0c29602f&zoom=100&at=9000`。

## 输出约定

文件名沿用现有 420 个带时码切片的主流格式：

```
<source>-<HH.MM.SS.mmm>-<HH.MM.SS.mmm>-cut<N>.mp4
Ella_lee15-...-06h06m37s.fixed-01.15.34.000-01.20.45.500-cut3.mp4
```

编号**按源文件从 1 开始**并跳过已占用编号（语料中 42 个源为 `(1)`、27 个为 `(1,2)`）。
同时写出 `<source>-proj.llc`，可直接回到 LosslessCut 继续编辑。

`.llc` 是 **JSON5**（无引号键、单引号、尾逗号），本项目自带容错解析器。注意 **`end` 缺失
表示 marker（零长度、不导出）**，而不是"切到文件末尾"——搞反会让每个标记都变成数小时的
导出。

## 测试

```bash
./gradlew :cutter:test      # 70 个测试
./gradlew :test             # 根项目 350 个测试
```

播放回归另见 `check-playback.cjs`：需安装 Playwright 和 Google Chrome，指定 `XHCUT_TEST_URL`
指向测试服务（包含至少 90 秒、无事件文件的录像）。可用 `PLAYWRIGHT_MODULE` 指定 Playwright
模块路径；`XHCUT_TEST_RAW=1` 额外验证浏览器原生 Range 播放。检查包含连续跨分片播放、慢加载、
连续跳转、拖动后的播放状态和清晰度切换。`PreviewServerTest` 使用 ffmpeg/ffprobe 检查真实分片
的音视频时间戳，工具未安装时跳过该测试。

`check-editor.cjs` 使用相同环境变量，对 120 秒的合成测试录像验证拖动期间分片/缓存刷新、缩略图、
捕获阶段快捷键、标记与片段边界、合并/撤销、各面板和轨道缩放，以及刷新后的未保存编辑恢复。

覆盖：文件名解析（语料真实命名，含 `_Galax-`、`xiao-Lin`、下划线/短横两种时间戳形态）、
三种信封解包、**陈旧时间戳护栏**、插值/外推边界、玩具区间构造（链式合并、`clear` 截断、
钳制）、建议流水线（微命令塌缩、远处爆发不黏合、`mergeGap` 生效、孤立抖动剔除、邻近吸收、
聊天聚集）、**导出命令无编码器参数断言**、`.llc` JSON5 往返（marker、v1→v2、转义）。

## 已知边界

- 无时间戳的 `.event`（采样 7/40）→ 置信度标为 `无`，不生成带时间的建议。
- copy 剪切无法起点精确，切点最多回退一个 GOP（2 s）；差值在 UI 与导出结果中**显式回报**。
- 全片电平总览要读完整条音轨（6.5 GB 源实测 49.5 s），一次性并落盘缓存，不是每次打开都算。
- 缓存上限 8 GB（`--cache-limit-gb`），按最久未访问淘汰；`/` 仅剩约 18 GB。
- Root 文件系统紧张时导出前会检查剩余空间（`--min-free-gb`，默认 2 GB）。
