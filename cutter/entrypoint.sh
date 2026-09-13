#!/bin/sh
# XhCut container entrypoint. Every path is overridable from the environment so the
# same image works for a different media root or port without a rebuild.
set -eu

# Port, timezone and JVM sizing have sensible defaults; the three locations do not.
# They are the only source of these values for the container, so an unset one is a
# deployment mistake and is reported here rather than silently becoming a path that
# happens to exist inside the image.
: "${XHCUT_PORT:=8092}"
: "${XHCUT_ZONE:=Asia/Shanghai}"
: "${XHCUT_JAVA_OPTS:=-Xms256m -Xmx1g}"

require() {
    eval "value=\${$1:-}"
    if [ -z "$value" ]; then
        echo "xhcut: $1 is required (the directory to use for $2)" >&2
        exit 2
    fi
}
require XHCUT_MEDIA "the recordings to read"
require XHCUT_OUT   "finished cuts"
require XHCUT_CACHE "regenerable scratch"

mkdir -p "$XHCUT_OUT" "$XHCUT_CACHE" /logs

echo "xhcut: port=$XHCUT_PORT media=$XHCUT_MEDIA out=$XHCUT_OUT cache=$XHCUT_CACHE zone=$XHCUT_ZONE"

exec java $XHCUT_JAVA_OPTS \
    -jar /cutter-all.jar \
    -p "$XHCUT_PORT" \
    -m "$XHCUT_MEDIA" \
    -o "$XHCUT_OUT" \
    -c "$XHCUT_CACHE" \
    -tz "$XHCUT_ZONE" \
    "$@"
