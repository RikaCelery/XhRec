#!/usr/bin/env python3
"""Verify that every symbol the Vue template can reach is actually defined.

A syntax check is not enough. If the object returned from `setup()` mentions a name
that was never declared, the failure is a *runtime* `ReferenceError`: Vue aborts
mount, the page renders blank, and the server looks completely healthy. That is
exactly how the XhCut UI went down once — an editing script's search string silently
did not match, so a block of functions was never inserted while the template and the
return statement kept referring to them.

    ./cutter/check-ui.sh          # runs this as part of the UI check
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

IDENT = re.compile(r"[A-Za-z_$][\w$]*")
DECL = re.compile(r"(?:function|const|let|var)\s+([A-Za-z_$][\w$]*)")


def setup_return_block(src: str) -> str:
    """Text of the object literal returned from setup().

    Anchored to the template literal and then searched backwards, because a plain
    forward search for `return {` matches comments and string contents first and the
    lazy match then swallows half the file.
    """
    marker = src.find("template:")
    if marker < 0:
        raise SystemExit("could not locate the template literal")
    head = src[:marker]
    start = head.rfind("return {")
    if start < 0:
        raise SystemExit("could not locate the setup() return object")
    depth = 0
    for i in range(start + len("return "), len(head)):
        if head[i] == "{":
            depth += 1
        elif head[i] == "}":
            depth -= 1
            if depth == 0:
                return head[start:i + 1]
    raise SystemExit("unbalanced braces in the setup() return object")


def exposed_names(block: str) -> set[str]:
    names: set[str] = set()
    for token in re.split(r"[,\s]+", block):
        token = token.strip()
        if token and token != "return" and IDENT.fullmatch(token):
            names.add(token)
    return names


def defined_names(src: str) -> set[str]:
    names = set(DECL.findall(src))
    # Destructured declarations: `const { a, b } = obj`
    for group in re.findall(r"(?:const|let|var)\s*\{([^}]*)\}", src):
        for part in group.split(","):
            part = part.strip().split(":")[-1].strip()
            if IDENT.fullmatch(part or ""):
                names.add(part)
    return names


def main() -> int:
    src = Path(sys.argv[1]).read_text(encoding="utf-8")
    exposed = exposed_names(setup_return_block(src))
    defined = defined_names(src)
    missing = sorted(n for n in exposed if n not in defined)
    if missing:
        print(
            "cutter.html: EXPOSED BUT UNDEFINED -> " + ", ".join(missing),
            file=sys.stderr,
        )
        return 1
    print(f"cutter.html: all {len(exposed)} exposed symbols are defined")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
