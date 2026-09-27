#!/usr/bin/env python3
"""
xhmelt.py
----------------
Generates a Shotcut-compatible .mlt project file from a set of
stream recordings and their companion .event files.

File naming convention:
    {roomName}-{yyyy-mm-dd-hhmmss}-{duration}.mp4
    {roomName}-{yyyy-mm-dd-hhmmss}-{duration}.mp4.event

Event file format: newline-delimited JSON objects, each with a
"recordedAt" (ISO 8601 UTC) timestamp and a "type" field.

Requires ffprobe (part of ffmpeg) to be installed and on PATH.

Usage:
    python xhmelt.py [directory] [--output output.mlt] [--fps N]
"""

import argparse
import json
import math as _math
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from fractions import Fraction
from pathlib import Path

# ---------------------------------------------------------------------------
# Marker colours  (Shotcut ARGB hex)
# ---------------------------------------------------------------------------
COLOUR_TIP     = "#00cc44"   # green  – tips
COLOUR_GOAL    = "#ff9900"   # orange – goal changes
COLOUR_PRIVATE = "#cc0000"   # red    – private show events


# ---------------------------------------------------------------------------
# State Tracking for Goals
# ---------------------------------------------------------------------------

class GoalTracker:
    """Tracks state across GoalChanged events to filter out intermediate progress."""
    def __init__(self):
        self.expecting_new: bool = True
        self.current_desc: str | None = None

    def process(self, data: dict) -> tuple[bool, str]:
        g = data.get("goal") or {}
        desc = g.get("description") or "goal"
        left_raw = g.get("left", "?")
        left_str = str(left_raw).strip()
        total = g.get("goal", "?")
        spent = g.get("spent", "?")

        is_completed = (left_str == "0")
        is_new = self.expecting_new or (self.current_desc is not None and desc != self.current_desc)

        if is_new or is_completed:
            if is_completed:
                self.expecting_new = True
                self.current_desc = None
            else:
                self.expecting_new = False
                self.current_desc = desc

            label = f"Goal '{desc}' \u2013 {spent}/{total} (need {left_str})"
            return True, label

        return False, ""


# ---------------------------------------------------------------------------
# ffprobe
# ---------------------------------------------------------------------------

def check_ffprobe() -> None:
    try:
        subprocess.run(["ffprobe", "-version"], capture_output=True, check=True)
    except FileNotFoundError:
        print(
            "Error: ffprobe not found.\n"
            "Install ffmpeg from https://ffmpeg.org/download.html and add to PATH.",
            file=sys.stderr,
        )
        sys.exit(1)


def probe_video(path: Path) -> dict:
    cmd = [
        "ffprobe", "-v", "quiet",
        "-print_format", "json",
        "-show_streams", "-show_format",
        str(path),
    ]
    try:
        result = subprocess.run(cmd, capture_output=True, text=True, check=True)
    except FileNotFoundError:
        raise RuntimeError("ffprobe not found on PATH.")
    except subprocess.CalledProcessError as exc:
        raise RuntimeError(f"ffprobe failed on '{path.name}':\n{exc.stderr.strip()}")

    data = json.loads(result.stdout)
    vs = next((s for s in data.get("streams", []) if s.get("codec_type") == "video"), None)
    if vs is None:
        raise RuntimeError(f"No video stream in '{path.name}'.")

    fmt = data.get("format", {})
    duration = float(vs.get("duration") or fmt.get("duration", 0))
    fps      = float(Fraction(vs.get("r_frame_rate", "30/1")))
    return {
        "duration": duration,
        "fps":      fps,
        "width":    int(vs.get("width",  1280)),
        "height":   int(vs.get("height",  720)),
        "codec":    vs.get("codec_name", "h264"),
    }


# ---------------------------------------------------------------------------
# Time helpers & Filename Parsing
# ---------------------------------------------------------------------------

def t(seconds: float) -> str:
    seconds = max(0.0, seconds)
    h = int(seconds // 3600)
    m = int((seconds % 3600) // 60)
    s = seconds % 60
    return f"{h:02d}:{m:02d}:{s:06.3f}"


FILENAME_RE = re.compile(
    r"^(?P<room>.+?)-(?P<ts>\d{4}-\d{2}-\d{2}-\d{6})-(?P<dur>.+)\.mp4$",
    re.IGNORECASE,
)


def parse_filename_ts(name: str) -> datetime | None:
    m = FILENAME_RE.match(Path(name).name)
    if not m:
        return None
    try:
        dt = datetime.strptime(m.group("ts"), "%Y-%m-%d-%H%M%S")
        return dt.replace(tzinfo=timezone.utc)
    except ValueError:
        return None


def first_event_utc(events: list[dict]) -> datetime | None:
    for ev in events:
        ts = ev.get("recordedAt", "")
        if ts:
            try:
                return parse_iso(ts)
            except Exception:
                pass
    return None


TIMESTAMP_PATHS = [
    ["message", "createdAt"],
    ["show",    "createdAt"],
    ["statusChangedAt"],
    ["startedAt"],
    ["createdAt"],
    ["deletedAt"],
]


def parse_iso(ts: str) -> datetime:
    try:
        return datetime.fromisoformat(ts.rstrip("Z")).replace(tzinfo=timezone.utc)
    except ValueError:
        ts = ts.rstrip("Z").split(".")[0]
        return datetime.strptime(ts, "%Y-%m-%dT%H:%M:%S").replace(tzinfo=timezone.utc)


def _resolve(obj: dict, path: list[str]):
    cur = obj
    for key in path:
        if not isinstance(cur, dict):
            return None
        cur = cur.get(key)
    return cur


def _find_occurrence(data: dict) -> datetime | None:
    for path in TIMESTAMP_PATHS:
        val = _resolve(data, path)
        if isinstance(val, str) and val:
            try:
                return parse_iso(val)
            except Exception:
                pass
    return None


def _unwrap(raw: dict) -> tuple[str, dict]:
    push = raw.get("push")
    if isinstance(push, dict):
        channel = push.get("channel", "unknown").split("@")[0]
        data = push.get("pub", {}).get("data", {})
        return channel, data if isinstance(data, dict) else {}

    etype = raw.get("type")
    data  = raw.get("data")
    if etype and isinstance(data, dict):
        return etype, data

    return (etype or "unknown"), raw


def load_events(path: Path) -> list[dict]:
    events = []
    with open(path, encoding="utf-8") as fh:
        for line in fh:
            line = line.strip()
            if line:
                try:
                    events.append(json.loads(line))
                except json.JSONDecodeError:
                    pass
    return events


# ---------------------------------------------------------------------------
# Event → marker
# ---------------------------------------------------------------------------

def tip_display_duration(amount: int | float, max_dur: float = 4.0) -> float:
    if amount <= 0:
        return 1.0
    dur = 1.0 + (max_dur - 1.0) * _math.log(max(amount, 1)) / _math.log(100)
    return max(1.0, min(max_dur, dur))


def describe_event(raw: dict, goal_tracker: GoalTracker | None = None) -> tuple[str, str, datetime | None, int | None] | None:
    """Return (label, colour, occurrence_dt, tip_amount) or None."""
    channel, data = _unwrap(raw)
    channel_lc = channel.lower()

    if channel_lc == "newchatmessage":
        msg     = data.get("message", {}) if isinstance(data.get("message"), dict) else {}
        mtype   = (msg.get("type") or "").lower()
        details = msg.get("details") or {}
        user    = (msg.get("userData") or {}).get("username", "?")
        occ     = _find_occurrence(data)

        if mtype in ("tip", "privatetip", "usertipped"):
            raw_amount = details.get("amount")
            try:
                amount = int(raw_amount)
            except (TypeError, ValueError):
                amount = None
            body  = (details.get("body") or "").strip()
            label = f"Tip {raw_amount}tk \u2013 {user}"
            if body:
                label += f': "{body}"'
            return label, COLOUR_TIP, occ, amount

        return None

    if channel_lc == "goalchanged":
        if goal_tracker is None:
            goal_tracker = GoalTracker()
        should_emit, label = goal_tracker.process(data)
        if should_emit:
            occ = _find_occurrence(data)
            return label, COLOUR_GOAL, occ, None
        return None

    if channel_lc in ("privatestartedv3", "privateendedv3"):
        label = "Private show started" if "started" in channel_lc else "Private show ended"
        occ   = _find_occurrence(data)
        return label, COLOUR_PRIVATE, occ, None

    return None


# ---------------------------------------------------------------------------
# Overlay text builders
# ---------------------------------------------------------------------------

def _make_text_producer(
    mlt: ET.Element,
    producer_id: str,
    filter_id: str,
    label: str,
    total_dur: float,
    frame: float,
    halign: str,
    valign: str,
    vsize: str,
    vpos: str,
    hsize: str = "1280",
    hpos: str = "0",
) -> None:
    tp = ET.SubElement(mlt, "producer", {
        "id":  producer_id,
        "out": t(total_dur - frame),
    })
    ET.SubElement(tp, "property", {"name": "length"}).text      = t(total_dur)
    ET.SubElement(tp, "property", {"name": "eof"}).text         = "pause"
    ET.SubElement(tp, "property", {"name": "mlt_service"}).text = "color"
    ET.SubElement(tp, "property", {"name": "resource"}).text    = "0x00000000"
    ET.SubElement(tp, "property", {"name": "aspect_ratio"}).text = "1"
    ET.SubElement(tp, "property", {"name": "mlt_image_format"}).text = "rgba"
    ET.SubElement(tp, "property", {"name": "shotcut:caption"}).text = "transparent"
    ET.SubElement(tp, "property", {"name": "shotcut:detail"}).text = "transparent"
    ET.SubElement(tp, "property", {"name": "ignore_points"}).text = "0"
    ET.SubElement(tp, "property", {"name": "xml"}).text = "was here"
    ET.SubElement(tp, "property", {"name": "seekable"}).text = "1"
    ET.SubElement(tp, "property", {"name": "meta.shotcut.vui"}).text = "1"

    filt = ET.SubElement(tp, "filter", {"id": filter_id})
    ET.SubElement(filt, "property", {"name": "mlt_service"}).text = "dynamictext"
    ET.SubElement(filt, "property", {"name": "shotcut:filter"}).text = "dynamicText"
    ET.SubElement(filt, "property", {"name": "shotcut:usePointSize"}).text = "1"
    ET.SubElement(filt, "property", {"name": "shotcut:pointSize"}).text = "34"
    ET.SubElement(filt, "property", {"name": "geometry"}).text    = f"{hpos} {vpos} {hsize} {vsize} 1"
    ET.SubElement(filt, "property", {"name": "argument"}).text    = label
    ET.SubElement(filt, "property", {"name": "size"}).text        = "34"
    ET.SubElement(filt, "property", {"name": "weight"}).text      = "700"
    ET.SubElement(filt, "property", {"name": "family"}).text      = "Arial"
    ET.SubElement(filt, "property", {"name": "fgcolour"}).text    = "#ffffffff"
    ET.SubElement(filt, "property", {"name": "bgcolour"}).text    = "#00000000"
    ET.SubElement(filt, "property", {"name": "olcolour"}).text    = "#ff000000"
    ET.SubElement(filt, "property", {"name": "outline"}).text     = "1"
    ET.SubElement(filt, "property", {"name": "halign"}).text      = halign
    ET.SubElement(filt, "property", {"name": "valign"}).text      = valign
    ET.SubElement(filt, "property", {"name": "pad"}).text         = "10"


def _build_overlay_playlist(
    mlt: ET.Element,
    playlist_id: str,
    track_name: str,
    markers: list[dict],
    frame: float,
    total_dur: float,
    chain_prefix: str,
    halign: str,
    valign: str,
    vsize: str,
    vpos: str,
    hsize: str = "1280",
    hpos: str = "0",
) -> None:
    if not markers:
        pl = ET.SubElement(mlt, "playlist", {"id": playlist_id})
        ET.SubElement(pl, "property", {"name": "shotcut:video"}).text = "1"
        ET.SubElement(pl, "property", {"name": "shotcut:name"}).text  = track_name
        if total_dur > 0:
            ET.SubElement(pl, "blank", {"length": t(total_dur - frame)})
        return

    markers = sorted(markers, key=lambda m: m["time"])

    actual_starts: list[float] = []
    overlay_end = 0.0
    for marker in markers:
        start = max(marker["time"], overlay_end)
        actual_starts.append(start)
        overlay_end = start + marker["dur"]

    for mi, marker in enumerate(markers):
        _make_text_producer(
            mlt,
            producer_id  = f"{chain_prefix}{mi}",
            filter_id    = f"filter_{chain_prefix}{mi}",
            label        = marker["label"],
            total_dur    = total_dur,
            frame        = frame,
            halign       = halign,
            valign       = valign,
            vsize        = vsize,
            vpos         = vpos,
            hpos         = hpos,
            hsize        = hsize,
        )

    pl = ET.SubElement(mlt, "playlist", {"id": playlist_id})
    ET.SubElement(pl, "property", {"name": "shotcut:video"}).text = "1"
    ET.SubElement(pl, "property", {"name": "shotcut:name"}).text  = track_name

    cursor = 0.0
    for mi, start in enumerate(actual_starts):
        dur = markers[mi]["dur"]
        gap = start - cursor

        if gap > frame / 2:
            ET.SubElement(pl, "blank", {"length": t(gap)})

        ET.SubElement(pl, "entry", {
            "producer": f"{chain_prefix}{mi}",
            "in":       t(start),
            "out":      t(start + dur - frame),
        })

        cursor = start + dur

    if total_dur > cursor:
        ET.SubElement(pl, "blank", {"length": t(total_dur - cursor)})


def _add_track_pair(
    tractor: ET.Element,
    playlist_id: str,
    a_track: int,
    b_track: int,
    tr_id_mix: str,
    tr_id_blend: str,
    hide_audio: bool = True,
) -> None:
    if hide_audio:
        ET.SubElement(tractor, "track", {"producer": playlist_id, "hide": "audio"})
    else:
        ET.SubElement(tractor, "track", {"producer": playlist_id})

    mix = ET.SubElement(tractor, "transition", {"id": tr_id_mix})
    ET.SubElement(mix, "property", {"name": "a_track"}).text       = str(a_track)
    ET.SubElement(mix, "property", {"name": "b_track"}).text       = str(b_track)
    ET.SubElement(mix, "property", {"name": "mlt_service"}).text   = "mix"
    ET.SubElement(mix, "property", {"name": "always_active"}).text = "1"
    ET.SubElement(mix, "property", {"name": "sum"}).text           = "1"

    blend = ET.SubElement(tractor, "transition", {"id": tr_id_blend})
    ET.SubElement(blend, "property", {"name": "a_track"}).text     = str(a_track)
    ET.SubElement(blend, "property", {"name": "b_track"}).text     = str(b_track)
    ET.SubElement(blend, "property", {"name": "mlt_service"}).text = "qtblend"
    ET.SubElement(blend, "property", {"name": "threads"}).text     = "0"
    ET.SubElement(blend, "property", {"name": "disable"}).text     = "0"


# ---------------------------------------------------------------------------
# MLT builder
# ---------------------------------------------------------------------------

def build_mlt(
    videos: list[dict],
    fps_override: float | None = None,
    text_dur: float = 4.0,
    tip_max_dur: float = 4.0,
    event_delay: float = 7.0,
) -> ET.Element:
    from math import gcd

    max_probe = max(
        (v["probe"] for v in videos if v.get("probe")), 
        key=lambda p: int(p.get("width", 0)) * int(p.get("height", 0)), 
        default=None
    )
    fps    = fps_override if fps_override is not None else (max_probe["fps"] if max_probe else 30.0)
    width  = max_probe["width"]  if max_probe else 1280
    height = max_probe["height"] if max_probe else 720

    frac   = Fraction(fps).limit_denominator(1001)
    fr_num = frac.numerator
    fr_den = frac.denominator
    g      = gcd(width, height)
    frame  = 1.0 / fps

    mlt = ET.Element("mlt", {
        "LC_NUMERIC": "C",
        "version":    "7.24.0",
        "title":      "Shotcut version 26.8.1",
        "producer":   "main_bin",
    })

    ET.SubElement(mlt, "profile", {
        "description":        f"{width}x{height} {fps:.5g}fps",
        "width":              str(width),
        "height":             str(height),
        "progressive":        "1",
        "sample_aspect_num":  "1",
        "sample_aspect_den":  "1",
        "display_aspect_num": str(width  // g),
        "display_aspect_den": str(height // g),
        "frame_rate_num":     str(fr_num),
        "frame_rate_den":     str(fr_den),
        "colorspace":         "709",
    })

    clip_outs = []
    for idx, vid in enumerate(videos):
        probe = vid.get("probe")
        dur   = probe["duration"] if probe else None
        out   = t(dur - frame) if dur else "999:00:00.000"
        clip_outs.append(out)

        chain = ET.SubElement(mlt, "chain", {"id": f"chain{idx}", "out": out})
        ET.SubElement(chain, "property", {"name": "length"}).text              = t(dur) if dur else "999:00:00.000"
        ET.SubElement(chain, "property", {"name": "eof"}).text                 = "pause"
        ET.SubElement(chain, "property", {"name": "resource"}).text            = str(vid["path"])
        ET.SubElement(chain, "property", {"name": "mlt_service"}).text         = "avformat-novalidate"
        ET.SubElement(chain, "property", {"name": "seekable"}).text            = "1"
        ET.SubElement(chain, "property", {"name": "audio_index"}).text         = "1"
        ET.SubElement(chain, "property", {"name": "video_index"}).text         = "0"
        ET.SubElement(chain, "property", {"name": "shotcut:skipConvert"}).text = "1"

    main_bin = ET.SubElement(mlt, "playlist", {"id": "main_bin"})
    ET.SubElement(main_bin, "property", {"name": "xml_retain"}).text = "1"
    for idx in range(len(videos)):
        ET.SubElement(main_bin, "entry", {
            "producer": f"chain{idx}",
            "in":       "00:00:00.000",
            "out":      clip_outs[idx],
        })

    black = ET.SubElement(mlt, "producer", {
        "id": "black", "in": "00:00:00.000", "out": "00:00:00.000",
    })
    black_length_prop = ET.SubElement(black, "property", {"name": "length"})
    black_length_prop.text = "00:00:00.000"
    ET.SubElement(black, "property", {"name": "eof"}).text              = "pause"
    ET.SubElement(black, "property", {"name": "resource"}).text         = "0"
    ET.SubElement(black, "property", {"name": "aspect_ratio"}).text     = "1"
    ET.SubElement(black, "property", {"name": "mlt_service"}).text      = "color"
    ET.SubElement(black, "property", {"name": "mlt_image_format"}).text = "rgba"
    ET.SubElement(black, "property", {"name": "set.test_audio"}).text   = "0"

    bg_pl = ET.SubElement(mlt, "playlist", {"id": "background"})
    bg_entry = ET.SubElement(bg_pl, "entry", {
        "producer": "black", "in": "00:00:00.000", "out": "00:00:00.000",
    })

    epoch = videos[0]["tz_start"]
    clip_layout: list[tuple[float, float | None]] = []

    for idx, vid in enumerate(videos):
        probe    = vid.get("probe")
        dur      = probe["duration"] if probe else None
        tz_start = vid["tz_start"]

        if dur is None:
            times = []
            for ev in vid["events"]:
                try:
                    times.append((parse_iso(ev["recordedAt"]) - tz_start).total_seconds())
                except Exception:
                    pass
            if times:
                dur = max(s for s in times if s >= 0) + 60.0
                print(f"  WARNING: no ffprobe for '{vid['path'].name}'; estimating {dur:.0f}s from events.", file=sys.stderr)

        tl_start = (tz_start - epoch).total_seconds()
        clip_layout.append((tl_start, dur))

    raw_video_dur = max((s + (d or 0.0) for s, d in clip_layout), default=0.0)
    timeline_padding = (event_delay + max(text_dur, tip_max_dur)) if raw_video_dur > 0 else 0.0
    total_dur = raw_video_dur + timeline_padding

    total_out = t(total_dur - frame) if total_dur else "00:00:00.000"
    black.set("out", total_out)
    black_length_prop.text = t(total_dur) if total_dur else "00:00:00.000"
    bg_entry.set("out", total_out)

    tip_markers:    list[dict] = []
    status_markers: list[dict] = []
    goal_tracker = GoalTracker()

    for idx, vid in enumerate(videos):
        tl_start, dur = clip_layout[idx]
        tz_start = vid["tz_start"]

        first_ev = first_event_utc(vid["events"])
        if first_ev is not None:
            hours_diff = round((first_ev - tz_start).total_seconds() / 3600.0)
            clip_anchor = tz_start + timedelta(hours=hours_diff)
        else:
            clip_anchor = tz_start

        for ev in vid["events"]:
            result = describe_event(ev, goal_tracker=goal_tracker)
            if result is None:
                continue
            label, colour, occ_dt, tip_amount = result

            try:
                ev_dt = occ_dt or parse_iso(ev.get("recordedAt", ""))
            except Exception:
                continue

            offset = (ev_dt - clip_anchor).total_seconds() + event_delay

            if offset < 0 or (dur and offset >= dur):
                continue

            marker = {
                "time":   tl_start + offset,
                "label":  label,
                "colour": colour,
            }

            if tip_amount is not None:
                marker["dur"] = tip_display_duration(tip_amount, max_dur=tip_max_dur)
                tip_markers.append(marker)
            else:
                marker["dur"] = text_dur
                status_markers.append(marker)

    tip_markers.sort(   key=lambda m: m["time"])
    status_markers.sort(key=lambda m: m["time"])
    all_markers = sorted(tip_markers + status_markers, key=lambda m: m["time"])

    pl_video = ET.SubElement(mlt, "playlist", {"id": "playlist0"})
    ET.SubElement(pl_video, "property", {"name": "shotcut:video"}).text = "1"
    ET.SubElement(pl_video, "property", {"name": "shotcut:name"}).text  = "V1"

    v1_cursor = 0.0
    for idx, (tl_start, dur) in enumerate(clip_layout):
        gap = tl_start - v1_cursor
        if gap > frame / 2:
            ET.SubElement(pl_video, "blank", {"length": t(gap)})
        ET.SubElement(pl_video, "entry", {
            "producer": f"chain{idx}",
            "in":       "00:00:00.000",
            "out":      clip_outs[idx],
        })
        v1_cursor = tl_start + (dur if dur else 0.0)

    _build_overlay_playlist(
        mlt, "playlist1", "Tips (V2)",
        tip_markers, frame, total_dur,
        chain_prefix="tip_chain",
        halign="left", valign="top", vsize="70", vpos="0",
        hpos="0", hsize=str(width),
    )

    _build_overlay_playlist(
        mlt, "playlist2", "Status (V3)",
        status_markers, frame, total_dur,
        chain_prefix="status_chain",
        halign="right", valign="top", vsize="70", vpos="70",
        hpos="0", hsize=str(width),
    )

    tractor = ET.SubElement(mlt, "tractor", {
        "id":    "tractor0",
        "title": "Shotcut version 26.8.1",
        "in":    "00:00:00.000",
        "out":   t(total_dur - frame) if total_dur else "00:00:00.000",
    })
    ET.SubElement(tractor, "property", {"name": "shotcut"}).text                      = "1"
    ET.SubElement(tractor, "property", {"name": "shotcut:projectAudioChannels"}).text = "2"
    ET.SubElement(tractor, "property", {"name": "shotcut:projectFolder"}).text        = "0"

    markers_el = ET.SubElement(tractor, "properties", {"name": "shotcut:markers"})
    for mi, marker in enumerate(all_markers):
        m_el = ET.SubElement(markers_el, "properties", {"name": str(mi)})
        ET.SubElement(m_el, "property", {"name": "text"}).text  = marker["label"]
        ET.SubElement(m_el, "property", {"name": "start"}).text = t(marker["time"])
        ET.SubElement(m_el, "property", {"name": "end"}).text   = t(marker["time"])
        ET.SubElement(m_el, "property", {"name": "color"}).text = marker["colour"]

    ET.SubElement(tractor, "track", {"producer": "background"})
    ET.SubElement(tractor, "track", {"producer": "playlist0"})

    mix0 = ET.SubElement(tractor, "transition", {"id": "transition0"})
    ET.SubElement(mix0, "property", {"name": "a_track"}).text       = "0"
    ET.SubElement(mix0, "property", {"name": "b_track"}).text       = "1"
    ET.SubElement(mix0, "property", {"name": "mlt_service"}).text   = "mix"
    ET.SubElement(mix0, "property", {"name": "always_active"}).text = "1"
    ET.SubElement(mix0, "property", {"name": "sum"}).text           = "1"

    comp0 = ET.SubElement(tractor, "transition", {"id": "transition1"})
    ET.SubElement(comp0, "property", {"name": "a_track"}).text     = "0"
    ET.SubElement(comp0, "property", {"name": "b_track"}).text     = "1"
    ET.SubElement(comp0, "property", {"name": "mlt_service"}).text = "qtblend"
    ET.SubElement(comp0, "property", {"name": "threads"}).text     = "0"
    ET.SubElement(comp0, "property", {"name": "disable"}).text     = "1"

    _add_track_pair(tractor, "playlist1", 1, 2, "transition2", "transition3")
    _add_track_pair(tractor, "playlist2", 2, 3, "transition4", "transition5")

    return mlt


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main() -> None:
    parser = argparse.ArgumentParser(
        description="Build a Shotcut .mlt project from stream recordings + .event files.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("directory", nargs="?", default=".", help="Directory containing .mp4 + .mp4.event files")
    parser.add_argument("--output", "-o", default=None, help="Output .mlt path")
    parser.add_argument("--fps", type=float, default=None, help="Override timeline fps")
    parser.add_argument("--text-duration", type=float, default=4.0, dest="text_dur", help="Seconds text is visible")
    parser.add_argument("--tip-max-duration", type=float, default=4.0, dest="tip_max_dur", help="Max seconds tip text is visible")
    parser.add_argument("--tz-offset", type=float, default=-5.0, dest="tz_offset", help="Hours to add to filename to derive UTC")
    parser.add_argument(
        "--event-delay", type=float, default=7.0, dest="event_delay",
        help="Seconds to delay all events to account for live stream lag (default: 7.0)",
    )
    args = parser.parse_args()

    check_ffprobe()

    directory = Path(args.directory).resolve()
    if not directory.is_dir():
        print(f"Error: '{directory}' is not a directory.", file=sys.stderr)
        sys.exit(1)

    output_path = Path(args.output) if args.output else directory / "output.mlt"
    tz_delta = timedelta(hours=args.tz_offset)

    mp4_files = sorted(directory.glob("*.mp4")) or sorted(directory.glob("*.MP4"))
    if not mp4_files:
        print("No .mp4 files found.", file=sys.stderr)
        sys.exit(1)

    videos = []
    for mp4 in mp4_files:
        fn_ts = parse_filename_ts(mp4.name)
        if fn_ts is None:
            continue

        print(f"  Probing  {mp4.name} \u2026", end=" ", flush=True)
        try:
            probe = probe_video(mp4)
            print(f"{probe['duration']:.1f}s  {probe['fps']:.5g}fps  {probe['width']}x{probe['height']}")
        except RuntimeError as exc:
            print(f"FAILED: {exc}")
            probe = None

        event_path = mp4.with_suffix(mp4.suffix + ".event")
        events = []
        if event_path.exists():
            events = load_events(event_path)
            gt = GoalTracker()
            n = sum(1 for e in events if describe_event(e, goal_tracker=gt))
            print(f"           {event_path.name}: {len(events)} events, {n} markers")

        utc_start = first_event_utc(events) or (fn_ts + tz_delta)
        tz_start = fn_ts + tz_delta

        videos.append({
            "path":      mp4,
            "fn_ts":     fn_ts,
            "start_dt":  utc_start,
            "tz_start":  tz_start,
            "events":    events,
            "probe":     probe,
        })

    if not videos:
        print("No valid video files found.", file=sys.stderr)
        sys.exit(1)

    videos.sort(key=lambda v: v["fn_ts"])

    mlt_tree = build_mlt(
        videos,
        fps_override=args.fps,
        text_dur=args.text_dur,
        tip_max_dur=args.tip_max_dur,
        event_delay=args.event_delay
    )
    ET.indent(mlt_tree, space="  ")
    ET.ElementTree(mlt_tree).write(str(output_path), encoding="utf-8", xml_declaration=True)
    print(f"Done \u2192 {output_path}")


if __name__ == "__main__":
    main()