#!/usr/bin/env bash
#
# Syntax-check the module script inside cutter.html.
#
# The UI is one self-contained HTML file with an inline ES module; a stray syntax
# error leaves Vue unmounted and the page silently blank, which is far easier to
# catch here than by deploying and staring at a screenshot.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HTML="$SCRIPT_DIR/src/main/resources/cutter.html"
TMP="$(mktemp -t cuttermjs.XXXXXX).mjs"

python3 - "$HTML" "$TMP" <<'PY'
import re, sys
src, out = sys.argv[1], sys.argv[2]
html = open(src, encoding="utf-8").read()
m = re.search(r'<script type="module">(.*?)</script>', html, re.S)
if not m:
    sys.exit("no <script type=\"module\"> block found in " + src)
open(out, "w", encoding="utf-8").write(m.group(1))
PY

if command -v node >/dev/null 2>&1; then
    node --check "$TMP"
    echo "cutter.html: module script syntax OK"
else
    echo "cutter.html: node not available, skipping syntax check" >&2
fi

# A duplicated attribute is a *template* compile error, so `node --check` cannot see it:
# Vue drops the offending element and logs to the console, which leaves the page looking
# merely "empty" where those nodes should be. Five lane-mark groups shipped that way —
# every toy/gift/level/chat mark silently missing — so it is checked here.
python3 - "$HTML" <<'PY'
import re, sys
html = open(sys.argv[1], encoding="utf-8").read()
bad = []
for m in re.finditer(r'<([a-zA-Z][\w-]*)((?:"[^"]*"|\'[^\']*\'|[^<>"\'])*?)/?>', html, re.S):
    # Strip quoted values first: a duplicate name inside a JS expression is not an attribute.
    scrubbed = re.sub(r'"[^"]*"|\'[^\']*\'', '""', m.group(2))
    names = re.findall(r'(?<![-:@\w.])([a-zA-Z][\w-]*)\s*=', scrubbed)
    dupes = sorted({a for a in names if names.count(a) > 1})
    if dupes:
        bad.append((html[:m.start()].count("\n") + 1, dupes))
if bad:
    for line, dupes in bad:
        print("cutter.html:%d: duplicate attribute(s) %s" % (line, ", ".join(dupes)), file=sys.stderr)
    sys.exit("cutter.html has duplicate attributes; Vue will drop those elements")
print("cutter.html: no duplicate attributes")
PY
# Runtime reference check: see cutter/check-exposed.py for why a syntax check is
# not sufficient here.
python3 "$SCRIPT_DIR/check-exposed.py" "$TMP"

rm -f "$TMP"
