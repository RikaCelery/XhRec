# XhREC

A kotlin application for automatic recording lives from StripChat.

# Usage

```plain
usage: CommandLineParameters
 -f,--file <arg>     Room List File
 -o,--output <arg>   Output Dir
 -p,--port <arg>     Server Port [default:8090]
 -t,--tmp <arg>      Temp Dir
```

```shell
java -jar XhRec-all.jar -f list.conf -t /path/to/temp/folder -o /path/to/destnation/folder
```

# Control

There is no builtin GUI/CLI for now. But we provided a html for preview and a userscript for quick control.

## API

### /add

| Parameter | Description           |
|-----------|-----------------------|
| slug      | Room/Model name       |
| quality   | Quality, default 720p |
| active    | Start auto recording  |

### /break

Temporary stop recording

| Parameter | Description     |
|-----------|-----------------|
| slug      | Room/Model name |

### /remove

| Parameter | Description     |
|-----------|-----------------|
| slug      | Room/Model name |

### /activate

| Parameter | Description     |
|-----------|-----------------|
| slug      | Room/Model name |

### /deactivate

| Parameter | Description     |
|-----------|-----------------|
| slug      | Room/Model name |

### /quality

| Parameter | Description     |
|-----------|-----------------|
| slug      | Room/Model name |
| quality   | Quality         |

### /list (Deprecated)

Simple json status list

```json lines
[
  [
    //is streaming?
    "[ ]",
    //listening
    "         ",
    //recording
    "         ",
    "Model Name",
    "Model Id",
    "720p60"
  ],
  [
    "[ ]",
    "listening",
    "recording",
    "milli_sun_",
    "173611239",
    "720p"
  ]
]
```

### /status

Json status

```json lines
{
  "Model Name": {
    //total segments
    "total": 10046,
    //succeed segments
    "success": 9933,
    //failed segments
    "failed": 98,
    //total bytes
    "bytesWrite": 1409108341,
    //running segments
    "running": {
      "https://xxxx_part3.mp4": {
        // using PROXY
        "type": "PROXY",
        // start download time
        "startAt": 1756357723403
      },
      "https://xxxx_part1.mp4": {
        "type": "DIRECT",
        "startAt": 1756357716154
      },
    }
  }
}
```

### /recorders

```json lines
[
  {
    "name": "Model Name",
    "id": 12345,
    "quality": "720p60",
    // useless for now
    "lastSeen": null
  }
]
```

### /stop-server

Finish all recording tasks.
The server won't shut down for some reason, but it's safe to kill the process when this api responds.

### /metrics

Prometheus metrics

You can build monitor like this:
![img_2.png](img_2.png)



# Configuration

```plain
# https://zh.xhamsterlive.com/modelA q:720p
; https://zh.xhamsterlive.com/modelB q:240p
https://zh.xhamsterlive.com/modelC q:raw
```

+ Start with `#` or `;` will be marked as `INACTIVE`, means will not automatically start recording
+ q:XXXX means preferred quality, raw means original quality.
  ***If not quality matches, program will select closest one.***
+ `zh.` is optional, dont care about it.

# UI

## HTML

![img.png](img.png)

```html
<!DOCTYPE html>
<html lang="zh">
<head>
    <meta charset="UTF-8"/>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:ital,wght@0,100..800;1,100..800&display=swap"
          rel="stylesheet">
    <title>任务状态监控</title>
    <style>
        :root {
            --bg-color-light: #ffffff;
            --text-color-light: #000000;
            --bg-color-dark: #121212;
            --text-color-dark: #eeeeee;
            --table-border: #444;
            --highlight-blue: #007bff;
            --highlight-green: #28a745;
            --highlight-orange: orange;
            --highlight-red: red;
            --highlight-yellow: gold;
        }

        span {

            font-family: "JetBrains Mono", monospace;
            font-weight: 500;
            font-style: normal;
        }

        @media (prefers-color-scheme: dark) {
            body {
                background-color: var(--bg-color-dark);
                color: var(--text-color-dark);
            }

            table {
                border-color: var(--table-border);
            }
        }

        @media (prefers-color-scheme: light) {
            body {
                background-color: var(--bg-color-light);
                color: var(--text-color-light);
            }

            table {
                border-color: var(--table-border);
            }
        }

        body {
            font-family: sans-serif;
            padding: 20px;
        }

        h1,
        h2 {
            color: var(--highlight-blue);
        }

        table {
            width: 100%;
            border-collapse: collapse;
            margin-bottom: 30px;
        }

        th,
        td {
            border: 1px solid var(--table-border);
            padding: 8px 12px;
            text-align: left;
        }

        th {
            background-color: #33333333;
        }

        tr:nth-child(even) {
            background-color: #00000022;
        }

        .proxy {
            color: var(--highlight-yellow);
        }

        .time-short {
            color: var(--highlight-green);
        }

        .time-medium {
            color: var(--highlight-orange);
        }

        .time-long {
            color: var(--highlight-red);
            font-weight: bold;
        }

        .url-list {
            margin-top: 20px;
        }

        .url-item {
            font-size: 0.9rem;
            margin-bottom: 4px;
        }

        iframe {
            width: 100%;
            min-height: 70vh;
        }

        .abnormal {
            background: #ffff004f;
        }
    </style>
</head>
<body>
<h1>下载任务状态</h1>
<table id="summary-table">
    <thead>
    <tr>
        <th>任务名</th>
        <th>Total</th>
        <th>Compelete</th>
        <th>Failed</th>
        <th>Bytes</th>
    </tr>
    </thead>
    <tbody></tbody>
</table>
<details>
    <iframe src="/preview"></iframe>
</details>
<div class="url-list">
    <h2>当前下载中的 URL 列表</h2>
    <div id="url-items">加载中...</div>
</div>

<script>
    function formatBytes(bytes) {
        const units = ["B", "KB", "MB", "GB"];
        let i = 0;
        while (bytes >= 1024 && i < units.length - 1) {
            bytes /= 1024;
            i++;
        }
        return `${bytes.toFixed(3)} ${units[i]}`;
    }

    async function fetchStatus() {
        try {
            // REPLACE TO YOUR OWN API SERVER
            // REPLACE TO YOUR OWN API SERVER
            // REPLACE TO YOUR OWN API SERVER
            // REPLACE TO YOUR OWN API SERVER
            // REPLACE TO YOUR OWN API SERVER
            // REPLACE TO YOUR OWN API SERVER
            const res = await fetch("http://localhost:8090/status");
            const data = await res.json();

            const tableBody = document.querySelector(
                    "#summary-table tbody"
            );
            const urlContainer = document.getElementById("url-items");
            const now = Date.now();

            tableBody.innerHTML = "";
            urlContainer.innerHTML = "";

            const allUrls = [];
            const MAXLEN = 20;
            let allBytes = 0;
            Object.entries(data).forEach(([taskName, taskInfo]) => {
                if (taskInfo.bytesWrite < 0)
                    taskInfo.bytesWrite += 2147483647 * 2;
                allBytes += taskInfo.bytesWrite;
                const row = document.createElement("tr");
                const compelete = taskInfo.success + taskInfo.failed;
                const normal_rate =
                        (taskInfo.success * 100.0) / taskInfo.total;
                row.innerHTML = `
            <td>${taskName.slice(0, MAXLEN)}</td>
			<td>[${taskInfo.total - compelete}] ${compelete}/${taskInfo.total}</td>
			<td class="${normal_rate < 98 ? "abnormal" : ""}">${
                        taskInfo.success
                } (${normal_rate.toFixed(2)}%)</td>
            <td>${taskInfo.failed}</td>
            <td>${formatBytes(taskInfo.bytesWrite || 0)}</td>
          `;
                tableBody.appendChild(row);
                const running = taskInfo.running || {};

                const entries = Object.entries(running)
                        .map(([url, meta]) => {
                            meta.startAtAbs = /_(\d+)_part.\.mp4/.exec(url)[1] * 1000
                            return [url, meta]
                        })
                entries.forEach(([url, meta]) => {
                    const elapsed = (
                            (now - meta.startAt) /
                            1000
                    ).toFixed(1);

                    const elapsedAbs = (
                            (now - meta.startAtAbs) /
                            1000
                    ).toFixed(1);
                    let timeClass = "time-short";
                    if (elapsedAbs > 10) {
                        timeClass = "time-long";
                    } else if (elapsedAbs > 5) {
                        timeClass = "time-medium";
                    }

                    const urlDiv = document.createElement("div");
                    urlDiv.className = "url-item";
                    if (meta.type === "PROXY")
                        urlDiv.classList.add("proxy");
                    urlDiv.innerHTML = `${(taskName + "                                ").slice(0, MAXLEN).replaceAll(" ", "&ensp;")} [${
                            meta.type
                    }] <span class="${timeClass}">[SERVER]:${elapsedAbs}s [DOWNLOADER]${elapsed}s</span> ${
                            url.split("/").slice(-1)[0]
                    }`;
                    allUrls.push({meta: meta, el: urlDiv});
                });
            });

            const row = document.createElement("tr");
            row.innerHTML = `
	    <td>${Object.keys(data).length}个任务</td>
			<td></td>
			<td></td>
            <td></td>
            <td>${formatBytes(allBytes || 0)}</td>
          `;
            tableBody.appendChild(row);
            if (allUrls.length > 0) {
                allUrls.sort((a, b) => {
                    let offseta = 0;
                    let offsetb = 0;
                    if (
                            a.meta.type == "PROXY"
                    )
                        offseta = -15000;
                    if (
                            b.meta.type == "PROXY"
                    )
                        offsetb = -15000;
                    //console.log(a.meta.type)
                    return a.meta.startAt + offseta - b.meta.startAt - offsetb;
                });
                allUrls.forEach((div) =>
                        urlContainer.appendChild(div.el)
                );
            } else {
                urlContainer.textContent = "无下载任务";
            }
        } catch (e) {
            document.getElementById("url-items").textContent =
                    "获取失败: " + e;
        }
    }

    fetchStatus();
    setInterval(fetchStatus, 500);
</script>
</body>
</html>

```

## User script

![img_1.png](img_1.png)

```js
// ==UserScript==
// @name         XhLive Auto Send
// @namespace    https://bbs.tampermonkey.net.cn/
// @version      0.1.0
// @description  try to take over the world!
// @author       You
// @match        https://*.xhamsterlive.com/*
// @match        https://xhamsterlive.com/*
// ==/UserScript==

// Simple toast notification function

if (document.location.pathname.split("/").length != 2) return


function showToast(message, type = 'success') {
    // Create toast element
    const toast = document.createElement('div');
    toast.textContent = message;
    toast.style.cssText = `
        position: fixed;
        top: 50%;
        left: 50%;
        padding: 12px 20px;
        border-radius: 4px;
        background-color: ${type === 'error' ? '#d32f2f' : '#4caf50'};
        color: white;
        font-family: Arial, sans-serif;
        font-size: 14px;
        z-index: 9999;
        box-shadow: 0 2px 10px rgba(0,0,0,0.2);
        animation: toast-slide-in 0.3s ease-out;
    `;

    // Add toast to document
    document.body.appendChild(toast);

    // Remove toast after 3 seconds
    setTimeout(() => {
        toast.style.animation = 'toast-slide-out 0.3s ease-out';
        setTimeout(() => {
            if (toast.parentNode) {
                toast.parentNode.removeChild(toast);
            }
        }, 300);
    }, 3000);
}

(function () {
    'use strict';
    let status = document.createElement("div")
    status.innerText = "..."
    status.style.cssText = `
        padding: 8px 10px;
        border-radius: 9px;
        background-color: ${false ? '#d32f2f' : '#4caf50'};
        color: white;
        font-family: Arial, sans-serif;
        font-size: 14px;
        z-index: 9999;
        box-shadow: 0 2px 10px rgba(0,0,0,0.2);
        animation: toast-slide-in 0.3s ease-out;
    `;
    setInterval(() => {
        (async () => {
            let slug = document.location.pathname.slice(1)
            let resp = await fetch(`http://docker.lan:8090/list`)
            let list = await resp.json()
            let info = list.filter(l => l[3] == slug)[0]
            if (!info) {
                status.innerHTML = "..."
                return
            }
            console.log(info)
            status.style.backgroundColor = `${info[2] == 'recording' ? '#d32f2f' : '#4caf50'}`
            if (info.join(", ") != status.innerHTML)
                status.innerHTML = info.join(", ")
            return
        })().catch(info => {
            console.log(info)
        })
    }, 1000);
    let item = document.createElement("button")
    item.innerHTML = `
    Send (Not Activate)
    `
    let item2 = document.createElement("button")
    item2.innerHTML = `
    Send (Activate)
    `
    item.classList = "btn btn-signup"
    item2.classList = "btn btn-signup"
    item.style.margin = 0
    item2.style.margin = 0
    item.addEventListener("click", async () => {
        let slug = document.location.pathname.slice(1)
        let resp = await fetch(`http://docker.lan:8090/add?slug=${encodeURIComponent(slug)}&active=false`)
        let msg = await resp.text()
        // 显示一个Tost
        showToast(msg);
    })
    item2.addEventListener("click", async () => {
        let slug = document.location.pathname.slice(1)
        let resp = await fetch(`http://docker.lan:8090/add?slug=${encodeURIComponent(slug)}&active=true`)
        let msg = await resp.text()
        // 显示一个Tost
        showToast(msg);
    })


    let remove = document.createElement("button")
    remove.classList = "btn btn-signup"
    remove.style.margin = 0
    remove.innerHTML = `Remove`
    remove.addEventListener("click", async () => {
        let slug = document.location.pathname.slice(1)
        let resp = await fetch(`http://docker.lan:8090/remove?slug=${encodeURIComponent(slug)}`)
        let msg = await resp.text()
        // 显示一个Tost
        showToast(msg);
    })

    let activate = document.createElement("button")
    activate.classList = "btn btn-signup"
    activate.style.margin = 0
    activate.innerHTML = `Activate`
    activate.addEventListener("click", async () => {
        let slug = document.location.pathname.slice(1)
        let resp = await fetch(`http://docker.lan:8090/activate?slug=${encodeURIComponent(slug)}`)
        let msg = await resp.text()
        // 显示一个Tost
        showToast(msg);
    })
    let deactivate = document.createElement("button")
    deactivate.classList = "btn btn-signup"
    deactivate.style.margin = 0
    deactivate.innerHTML = `Deactivate`
    deactivate.addEventListener("click", async () => {
        let slug = document.location.pathname.slice(1)
        let resp = await fetch(`http://docker.lan:8090/deactivate?slug=${encodeURIComponent(slug)}`)
        let msg = await resp.text()
        // 显示一个Tost
        showToast(msg);
    })
    let setRaw = document.createElement("button")
    setRaw.classList = "btn btn-signup"
    setRaw.style.margin = 0
    setRaw.innerHTML = `Q:raw`
    setRaw.addEventListener("click", async () => {
        let slug = document.location.pathname.slice(1)
        let resp = await fetch(`http://docker.lan:8090/quality?slug=${encodeURIComponent(slug)}&quality=raw`)
        let msg = await resp.text()
        // 显示一个Tost
        showToast(msg);
    })
    let set720 = document.createElement("button")
    set720.classList = "btn btn-signup"
    set720.style.margin = 0
    set720.innerHTML = `Q:720p`
    set720.addEventListener("click", async () => {
        let slug = document.location.pathname.slice(1)
        let resp = await fetch(`http://docker.lan:8090/quality?slug=${encodeURIComponent(slug)}&quality=720p`)
        let msg = await resp.text()
        // 显示一个Tost
        showToast(msg);
    })
    let set720p60 = document.createElement("button")
    set720p60.classList = "btn btn-signup"
    set720p60.style.margin = 0
    set720p60.innerHTML = `Q:720p60`
    set720p60.addEventListener("click", async () => {
        let slug = document.location.pathname.slice(1)
        let resp = await fetch(`http://docker.lan:8090/quality?slug=${encodeURIComponent(slug)}&quality=720p60`)
        let msg = await resp.text()
        // 显示一个Tost
        showToast(msg);
    })
    let set1080p60 = document.createElement("button")
    set1080p60.classList = "btn btn-signup"
    set1080p60.style.margin = 0
    set1080p60.innerHTML = `Q:1080p60`
    set1080p60.addEventListener("click", async () => {
        let slug = document.location.pathname.slice(1)
        let resp = await fetch(`http://docker.lan:8090/quality?slug=${encodeURIComponent(slug)}&quality=1080p60`)
        let msg = await resp.text()
        // 显示一个Tost
        showToast(msg);
    })
    let set480 = document.createElement("button")
    set480.classList = "btn btn-signup"
    set480.style.margin = 0
    set480.innerHTML = `Q:480p`
    set480.addEventListener("click", async () => {
        let slug = document.location.pathname.slice(1)
        let resp = await fetch(`http://docker.lan:8090/quality?slug=${encodeURIComponent(slug)}&quality=480p`)
        let msg = await resp.text()
        // 显示一个Tost
        showToast(msg);
    })
    let set240 = document.createElement("button")
    set240.classList = "btn btn-signup"
    set240.style.margin = 0
    set240.innerHTML = `Q:240p`
    set240.addEventListener("click", async () => {
        let slug = document.location.pathname.slice(1)
        let resp = await fetch(`http://docker.lan:8090/quality?slug=${encodeURIComponent(slug)}&quality=240p`)
        let msg = await resp.text()
        // 显示一个Tost
        showToast(msg);
    })
    let set160 = document.createElement("button")
    set160.classList = "btn btn-signup"
    set160.style.margin = 0
    set160.innerHTML = `Q:160p`
    set160.addEventListener("click", async () => {
        let slug = document.location.pathname.slice(1)
        let resp = await fetch(`http://docker.lan:8090/quality?slug=${encodeURIComponent(slug)}&quality=160p`)
        let msg = await resp.text()
        // 显示一个Tost
        showToast(msg);
    })

    let container = document.createElement("div")
    container.style.position = "absolute"
    container.style.left = "10px"
    container.style.top = "50%"
    // container.style.background = "white"
    // container.style.padding = "8px"
    container.style.display = "flex"
    container.style.flexDirection = "column"
    container.style.gap = "4px"
    container.style.justifyContent = "center"
    container.style.alignItems = "center"
    container.style.zIndex = "3"
    container.style.transform = "translateX(0%) translateY(-50%)"
    // container.style.pointerEvents = "none"

    let containerRowSend = document.createElement("div")
    containerRowSend.style.display = "flex"
    containerRowSend.style.gap = "4px"
    containerRowSend.style.justifyContent = "center"
    containerRowSend.style.alignItems = "center"
    let containerRow = document.createElement("div")
    containerRow.style.display = "flex"
    containerRow.style.gap = "4px"
    containerRow.style.justifyContent = "center"
    containerRow.style.alignItems = "center"
    let containerRow2 = document.createElement("div")
    containerRow2.style.display = "flex"
    containerRow2.style.gap = "4px"
    containerRow2.style.justifyContent = "center"
    containerRow2.style.alignItems = "center"
    let containerRow3 = document.createElement("div")
    containerRow3.style.display = "flex"
    containerRow3.style.gap = "4px"
    containerRow3.style.justifyContent = "center"
    containerRow3.style.alignItems = "center"

    container.append(status)
    container.append(containerRowSend)
    container.append(containerRow)
    container.append(containerRow2)
    container.append(containerRow3)
    containerRowSend.append(item)
    containerRowSend.append(item2)
    containerRow.append(remove)
    containerRow.append(activate)
    containerRow.append(deactivate)
    containerRow2.append(setRaw)
    containerRow2.append(set1080p60)
    containerRow2.append(set720p60)
    containerRow2.append(set720)
    containerRow3.append(set480)
    containerRow3.append(set240)
    containerRow3.append(set160)

    document.body.append(container)
    // Your code here...
})();


```