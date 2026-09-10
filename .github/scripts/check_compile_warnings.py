#!/usr/bin/env python3
"""Gate the Kotlin build on new compiler warnings.

Compiles the main and test sources, reports every `w:` warning as a GitHub annotation and in
the job summary, then fails when a warning appears that the baseline does not know about.

The baseline exists because the tree already carries warnings; it is a ratchet, not an excuse:
`.github/compile-warnings.baseline` is the set that is currently tolerated, and it can only be
regenerated deliberately with `--update-baseline`.

    .github/scripts/check_compile_warnings.py                     # gate (CI and local)
    .github/scripts/check_compile_warnings.py --update-baseline    # accept the current warnings
    .github/scripts/check_compile_warnings.py --log build/g.log    # parse a captured log

Exit codes: 0 clean or unchanged, 1 new warnings, 2 the build itself failed.
"""

from __future__ import annotations

import argparse
import collections
import os
import re
import subprocess
import sys
from pathlib import Path
from urllib.parse import unquote

REPO_ROOT = Path(__file__).resolve().parents[2]
BASELINE = REPO_ROOT / ".github" / "compile-warnings.baseline"
REPORT = REPO_ROOT / "build" / "compile-warnings.txt"

# `w: file:///path/to/Repo/src/main/kotlin/Foo.kt:12:34 message`
WARNING = re.compile(r"^w: file://(?P<file>.+):(?P<line>\d+):(?P<col>\d+) (?P<message>.*)$")
ERROR = re.compile(r"^e: file://.+:\d+:\d+ .*$")

GRADLE_ARGS = ["compileKotlin", "compileTestKotlin", "--no-daemon", "--no-build-cache", "--console=plain"]

Warning = collections.namedtuple("Warning", "path line col message")


def run_gradle() -> tuple[int, str]:
    """Compiles both source sets from scratch, returning the exit code and the combined output."""
    command = ["./gradlew", "clean", *GRADLE_ARGS]
    print(f"$ {' '.join(command)}", flush=True)
    completed = subprocess.run(
        command,
        cwd=REPO_ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
    )
    sys.stdout.write(completed.stdout)
    return completed.returncode, completed.stdout


def parse(log: str) -> tuple[list[Warning], list[str]]:
    warnings: list[Warning] = []
    errors: list[str] = []
    for raw in log.splitlines():
        line = raw.rstrip()
        match = WARNING.match(line)
        if match:
            warnings.append(Warning(relative_path(match["file"]), int(match["line"]), int(match["col"]), match["message"]))
        elif ERROR.match(line):
            errors.append(line)
    return warnings, errors


def relative_path(url_path: str) -> str:
    """Turns the compiler's percent-encoded absolute file URL into a repo-relative path.

    The baseline is shared between machines, so it must never record where the checkout lives.
    When the reported path does not sit under this working copy — a captured CI log, a symlinked
    workspace — the path is cut at its source set instead. Files outside the project at all
    (Gradle plugins, generated code) keep their name, which still tells them apart.
    """
    absolute = Path(unquote(url_path))
    try:
        return absolute.relative_to(REPO_ROOT).as_posix()
    except ValueError:
        pass
    as_posix = absolute.as_posix()
    match = re.search(r"src/(?:main|test)/", as_posix)
    if match:
        return as_posix[match.start():]
    return f"<external>/{absolute.name}"


def key(warning: Warning) -> str:
    """Identifies a warning across edits: line and column move, the text and file do not."""
    return f"{warning.path}\t{warning.message}"


def load_baseline() -> collections.Counter[str]:
    if not BASELINE.exists():
        return collections.Counter()
    entries = [
        line
        for line in BASELINE.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.startswith("#")
    ]
    return collections.Counter(entries)


def write_baseline(warnings: list[Warning]) -> None:
    lines = sorted(key(warning) for warning in warnings)
    header = [
        "# Kotlin compiler warnings this repository currently tolerates.",
        "# Regenerate with .github/scripts/check_compile_warnings.py --update-baseline",
        f"# Entries: {len(lines)}",
    ]
    BASELINE.write_text("\n".join(header + lines) + "\n", encoding="utf-8")


def escape_annotation(text: str) -> str:
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def escape_property(text: str) -> str:
    return escape_annotation(text).replace(":", "%3A").replace(",", "%2C")


def annotate(warnings: list[Warning], new_keys: set[str]) -> None:
    """Emits workflow commands so the warnings land on the PR diff."""
    for warning in warnings:
        level = "warning" if key(warning) in new_keys else "notice"
        print(
            f"::{level} file={escape_property(warning.path)},line={warning.line},"
            f"col={warning.col},title=Kotlin compiler warning::"
            f"{escape_annotation(warning.message)}"
        )


def summary(warnings: list[Warning], new: list[Warning], gone: list[Warning]) -> None:
    by_file = collections.Counter(warning.path for warning in warnings)
    lines = [
        "## Kotlin compiler warnings",
        "",
        f"* warnings now: **{len(warnings)}**",
        f"* new since baseline: **{len(new)}**",
        f"* in baseline but gone: {len(gone)}",
        "",
    ]
    if new:
        lines += ["### New warnings — update the code, or the baseline if that is the intent", ""]
        for warning in sorted(new, key=lambda w: (w.path, w.line)):
            lines += [f"* `{warning.path}:{warning.line}:{warning.col}` — {warning.message}"]
        lines += ["", "```", "./.github/scripts/check_compile_warnings.py --update-baseline", "```", ""]
    if gone:
        lines += ["### No longer reported (the baseline can shrink)", ""]
        for warning in sorted(gone, key=lambda w: w.path):
            lines += [f"* `{warning.path}` — {warning.message}"]
        lines += [""]
    if warnings:
        lines += ["### All warnings by file", "", "| file | warnings |", "| --- | --- |"]
        lines += [f"| `{path}` | {count} |" for path, count in sorted(by_file.items(), key=lambda kv: (-kv[1], kv[0]))]
        lines += [""]

    text = "\n".join(lines)
    print(text)
    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as handle:
            handle.write(text + "\n")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--update-baseline", action="store_true", help="rewrite the baseline from this run")
    parser.add_argument("--log", type=Path, help="parse an existing Gradle log instead of compiling")
    args = parser.parse_args()

    if args.log:
        status, log = 0, args.log.read_text(encoding="utf-8")
    else:
        status, log = run_gradle()

    warnings, errors = parse(log)

    REPORT.parent.mkdir(parents=True, exist_ok=True)
    REPORT.write_text(
        "\n".join(f"{w.path}:{w.line}:{w.col} {w.message}" for w in warnings) + ("\n" if warnings else ""),
        encoding="utf-8",
    )

    if errors:
        print(f"\n{len(errors)} compile error(s):", file=sys.stderr)
        for line in errors[:20]:
            print(f"  {line}", file=sys.stderr)
        return 2
    if status != 0:
        print(f"\nGradle failed with exit code {status}", file=sys.stderr)
        return 2

    if args.update_baseline:
        write_baseline(warnings)
        print(f"\nBaseline written: {len(warnings)} warning(s) -> {BASELINE.relative_to(REPO_ROOT)}")
        return 0

    baseline = load_baseline()
    current = collections.Counter(key(warning) for warning in warnings)
    new_keys = set((current - baseline).elements())
    gone_keys = set((baseline - current).elements())

    annotate(warnings, new_keys)
    summary(
        warnings,
        [warning for warning in warnings if key(warning) in new_keys],
        [Warning(k.split("\t", 1)[0], 0, 0, k.split("\t", 1)[1]) for k in sorted(gone_keys)],
    )

    if new_keys:
        counts = collections.Counter(key(warning) for warning in warnings)
        for entry in sorted(new_keys):
            path, message = entry.split("\t", 1)
            print(f"::error title=New compiler warning::{path}: {message} (x{counts[entry]})")
        print(f"\n{len(new_keys)} new compiler warning(s) not in the baseline", file=sys.stderr)
        return 1

    print(f"\nNo new compiler warnings ({len(warnings)} tolerated by the baseline)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
