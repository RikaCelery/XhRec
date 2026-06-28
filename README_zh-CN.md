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

每行一个房间。以 `#` 或 `;` 开头的行视为未激活（不会自动录制）。

```ini
# https://poplive.xyz/modelA q:720p limit:120
; https://poplive.xyz/modelB q:240p
https://poplive.xyz/modelC q:highest
```

| 字段            | 描述                                                                                                          |
|-----------------|-------------------------------------------------------------------------------------------------------------|
| `q:<quality>`   | 画质偏好: `240p`, `480p`, `720p`, `720p60`, `1080p`, `1080p60`, 或 `highest` (默认)。`raw` 已弃用，请用 `highest` |
| `limit:<sec>`   | 录制时长限制（秒）                                                                                               |
| `size:<bytes>`  | 录制大小限制（支持后缀: `K`, `M`, `G`, 如 `500M`）                                                                |
| `autopay`       | 对私密秀开启自动支付                                                                                             |
| `pkey:<key>`    | 自定义 psch 密钥                                                                                               |

如果请求的画质不可用，系统会自动选择最接近的匹配项。

### xhrec.json

流解密密钥存储。首次运行时如不存在则会创建默认文件。

```json
{
  "streamAuthKey": "默认 psch 密钥，如果无法从 master playlist 中提取则使用此值",
  "maskSensitiveLogs": true,
  "decryptKeys": {
    "psch 密钥 1": "解密密钥",
    "psch 密钥 2": "解密密钥"
  }
}
```

| 字段                 | 描述                                                                                          |
|---------------------|----------------------------------------------------------------------------------------------|
| `streamAuthKey`     | 用于流认证的默认 psch 密钥                                                                       |
| `maskSensitiveLogs` | 启用日志脱敏（主播名、cookie、token、代理地址）。可在 WebUI 中或通过 `/mask/toggle` 切换。默认 `true` |
| `decryptKeys`       | 解密密钥映射表（psch 密钥 → 解密密钥）                                                             |

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

### 用户脚本

[安装](https://greasyfork.org/zh-CN/scripts/582444-xhrec-control-panel)
![用户脚本](image-1.png)

## API 参考

所有接口均返回 JSON（除非另有说明）。参数通过查询字符串传递。

### 房间管理

| 接口       | 参数                                                              | 描述                 |
|------------|------------------------------------------------------------------|---------------------|
| `/add`     | `name`, `quality`, `active`, `limit`, `autopay`, `pkey`, `size`   | 添加房间              |
| `/remove`  | `id`                                                             | 删除房间              |
| `/restart` | `id`                                                             | 停止后重新开始录制      |
| `/break`   | `id`                                                             | 暂时中断（下次轮询时恢复）|

### 房间设置

| 接口          | 参数                      | 描述                     |
|---------------|--------------------------|-------------------------|
| `/activate`   | `id`                     | 启用自动录制               |
| `/deactivate` | `id`                     | 禁用自动录制               |
| `/quality`    | `id`, `q`                | 设置画质                  |
| `/autopay`    | `id`, `v` (true/false)   | 切换自动支付               |
| `/limit`      | `id`, `v` （秒）          | 设置时长限制（0 = 不限）     |
| `/sizelimit`  | `id`, `v`                | 设置大小限制（0 = 不限）     |

### 状态与监控

| 接口         | 描述                                              |
|-------------|--------------------------------------------------|
| `/status`   | 活跃房间状态（分段数、字节数、正在运行的下载任务）           |
| `/list`     | 所有房间的状态、会话状态、画质                          |
| `/dashboard`| 聚合数据: rooms, statuses, listv2, metrics          |
| `/metrics`  | Prometheus 指标接口                                 |

| `/mask/toggle` | 切换日志脱敏开关      |
| `/mask/status` | 获取当前脱敏状态（true/false） |

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

Prometheus 指标暴露在 `/metrics`。Grafana 仪表盘示例：

![grafana](img_1.png)
