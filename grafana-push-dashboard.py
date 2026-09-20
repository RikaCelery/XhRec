#!/usr/bin/env python3
"""Push grafana-xhrec-dashboard.json to Grafana.

The dashboard file is in Grafana's v2 schema (`dashboard.grafana.app/v2alpha1`), which is what the
UI exports. It must NOT be written back through that same API: the v2 read does not return
`vizConfig.group`, and a v2 write drops it, after which every panel fails with
"Plugin VizConfig not found" — the group is what names the panel plugin.

So this converts the v2 elements + layout into the classic panel model and posts it through
`/api/dashboards/db` with `overwrite`, merging onto the dashboard that is already there so the
fields this script does not model (annotations, time range, templating) survive.

Usage:
    python3 grafana-push-dashboard.py                 # push
    python3 grafana-push-dashboard.py --dry-run       # print what would change
    GRAFANA_URL=http://router-docker:3000 python3 grafana-push-dashboard.py
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request

DEFAULT_URL = os.environ.get("GRAFANA_URL", "http://router-docker:3000")
DASHBOARD_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "grafana-xhrec-dashboard.json")


def api(url, path, method="GET", body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        url.rstrip("/") + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json", "Accept": "application/json"},
    )
    with urllib.request.urlopen(req, timeout=30) as response:
        return json.load(response)


def to_classic_target(query, ref_id):
    """One v2 `DataQuery` as a classic target.

    The datasource sits *beside* `spec` in the v2 query and is addressed by name, while the classic
    model wants `{type, uid}`. Getting this wrong is not a hard error: Grafana falls back to the
    default datasource and every panel answers with a PromQL parse error instead of data.
    """
    spec = query.get("spec", {})
    reference = query.get("datasource", {})
    target = {
        "datasource": {
            "type": reference.get("type") or query.get("group", "prometheus"),
            "uid": reference.get("uid") or reference.get("name"),
        },
        "expr": spec.get("expr", ""),
        "refId": ref_id,
    }
    if "legendFormat" in spec:
        target["legendFormat"] = spec["legendFormat"]
    if "instant" in spec:
        target["instant"] = spec["instant"]
    if "range" in spec:
        target["range"] = spec["range"]
    target.setdefault("editorMode", spec.get("editorMode", "code"))
    target.setdefault("exemplar", spec.get("exemplar", False))
    target.setdefault("format", spec.get("format", "time_series"))
    return target


def to_classic_panel(name, element, layout_item):
    spec = element.get("spec", {})
    viz = spec.get("vizConfig", {})
    viz_spec = viz.get("spec", {})
    group = viz.get("group")
    if not group:
        raise SystemExit(
            f"panel {name!r} has no vizConfig.group — that field names the panel plugin and the "
            f"dashboard would render it as 'Plugin VizConfig not found'"
        )

    queries = spec.get("data", {}).get("spec", {}).get("queries", [])
    targets = []
    for index, entry in enumerate(queries):
        entry_spec = entry.get("spec", {})
        if entry_spec.get("hidden"):
            continue
        targets.append(to_classic_target(entry_spec.get("query", {}), entry_spec.get("refId", chr(65 + index))))

    reference = layout_item.get("spec", {})
    panel = {
        "id": spec.get("id", 0),
        "type": group,
        "title": spec.get("title", name),
        "description": spec.get("description", ""),
        "datasource": targets[0]["datasource"] if targets else {},
        "targets": targets,
        "gridPos": {
            "h": reference.get("height", 8),
            "w": reference.get("width", 12),
            "x": reference.get("x", 0),
            "y": reference.get("y", 0),
        },
        "fieldConfig": viz_spec.get("fieldConfig", {"defaults": {}, "overrides": []}),
        "options": viz_spec.get("options", {}),
    }
    if viz_spec.get("pluginVersion"):
        panel["pluginVersion"] = viz_spec["pluginVersion"]
    return panel


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--file", default=DASHBOARD_FILE)
    parser.add_argument("--url", default=DEFAULT_URL)
    parser.add_argument("--dry-run", action="store_true")
    args = parser.parse_args()

    source = json.load(open(args.file))
    uid = source["metadata"]["name"]
    spec = source["spec"]

    layout = {item["spec"]["element"]["name"]: item for item in spec["layout"]["spec"]["items"]}
    panels = [to_classic_panel(name, element, layout[name]) for name, element in spec["elements"].items()]
    panels.sort(key=lambda p: (p["gridPos"]["y"], p["gridPos"]["x"]))
    missing = set(spec["elements"]) - set(layout)
    if missing:
        raise SystemExit(f"elements without a layout position (they would not render): {sorted(missing)}")

    live = api(args.url, f"/api/dashboards/uid/{uid}")["dashboard"]
    live["panels"] = panels
    live["title"] = spec.get("title", live.get("title"))
    live["tags"] = spec.get("tags", live.get("tags", []))
    live["editable"] = spec.get("editable", live.get("editable", True))
    live["liveNow"] = spec.get("liveNow", live.get("liveNow", False))
    if "timeSettings" in spec:
        settings = spec["timeSettings"]
        if "from" in settings or "to" in settings:
            live["time"] = {"from": settings.get("from", "now-6h"), "to": settings.get("to", "now")}

    if args.dry_run:
        print(f"{len(panels)} panels -> {uid} ({args.url}); types: "
              f"{sorted({p['type'] for p in panels})}")
        return

    result = api(
        args.url,
        "/api/dashboards/db",
        method="POST",
        body={"dashboard": live, "overwrite": True, "message": "push grafana-xhrec-dashboard.json"},
    )
    print(f"pushed {len(panels)} panels: version {result.get('version')} {result.get('url')}")


if __name__ == "__main__":
    main()
