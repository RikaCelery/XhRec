#!/usr/bin/env bash
#
# Build XhCut and deploy it to a recording host.
#
#   ./cutter/deploy.sh            build + upload + rebuild image + (re)create container
#   ./cutter/deploy.sh --build    build the image only, leave the running container alone
#
# Every location is supplied by the caller. There are deliberately no defaults: a
# deployment describes *this* host's media root, cut directory and cache, and a value
# compiled in here would either be wrong for someone else's host or — worse — right for
# one machine and silently wrong everywhere else.
#
#   XHCUT_REMOTE=celery@10.0.2.202 \
#   XHCUT_MEDIA_DIR=/media/nas/out \
#   XHCUT_CUTS_DIR=/media/nas/_Crawler/Video/R18/rec/cuts \
#   XHCUT_CACHE_DIR=/mnt/xhcut_cache \
#   ./cutter/deploy.sh
#
# Required:
#   XHCUT_REMOTE      ssh target, e.g. celery@10.0.2.202
#   XHCUT_MEDIA_DIR   host directory holding the recordings (mounted read-write)
#   XHCUT_CUTS_DIR    host directory for finished cuts
#   XHCUT_CACHE_DIR   host scratch directory (must be writable by the container uid)
#
# Optional (defaults in brackets):
#   XHCUT_REMOTE_DIR  where the jar/Dockerfile are uploaded [/home/$USER/xhcut]
#   XHCUT_IMAGE       image name                                   [xhcut]
#   XHCUT_CONTAINER   container name                               [xhcut]
#   XHCUT_HOST_PORT   published port                               [8092]
#   XHCUT_UID         uid:gid the container runs as                [1000]
#   XHCUT_GPU         1 to pass --gpus all                         [1]
#
# The remote login shell is fish, so every remote script is piped to `bash -s`
# explicitly instead of being passed as a command string.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# Prints the header comment block verbatim, so the help text cannot drift from the file
# it documents. `head -n -1` is deliberately avoided: BSD head has no negative counts.
usage() {
    awk 'NR == 1 { next } /^#/ { sub(/^# ?/, ""); print; next } { exit }' "${BASH_SOURCE[0]}"
    exit "${1:-0}"
}

# `require` reports *all* the missing variables at once rather than one per run: setting
# up a new host means filling in several, and discovering them one exit code at a time is
# needless.
# Checked before the requirements: asking for help is not a request to deploy, and on a
# machine with nothing configured it should explain the options rather than list what is
# missing.
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
    usage 0
fi

missing=()
require() {
    local name="$1" value="${!1:-}"
    [[ -n "$value" ]] || missing+=("$name")
}

require XHCUT_REMOTE
require XHCUT_MEDIA_DIR
require XHCUT_CUTS_DIR
require XHCUT_CACHE_DIR

if [[ ${#missing[@]} -gt 0 ]]; then
    echo "ERROR: missing required deployment settings: ${missing[*]}" >&2
    echo >&2
    usage 1 >&2
fi

REMOTE="$XHCUT_REMOTE"
MEDIA_DIR="$XHCUT_MEDIA_DIR"
CUTS_DIR="$XHCUT_CUTS_DIR"
CACHE_DIR="$XHCUT_CACHE_DIR"

# Deriving the upload directory from the ssh target keeps the "no host path baked into
# the script" rule while still working without anyone setting it.
REMOTE_USER="${REMOTE%@*}"
[[ "$REMOTE_USER" == "$REMOTE" ]] && REMOTE_USER="$(whoami)"
REMOTE_DIR="${XHCUT_REMOTE_DIR:-/home/$REMOTE_USER/xhcut}"
IMAGE="${XHCUT_IMAGE:-xhcut}"
CONTAINER="${XHCUT_CONTAINER:-xhcut}"
HOST_PORT="${XHCUT_HOST_PORT:-8092}"
RUN_UID="${XHCUT_UID:-1000}"

# Container-side mount points. These are private to the container, so defaults are fine;
# they are overridable for the rare host that needs a different layout inside.
CONTAINER_MEDIA="${XHCUT_CONTAINER_MEDIA:-/media/out}"
CONTAINER_CUTS="${XHCUT_CONTAINER_CUTS:-/media/cuts}"
CONTAINER_CACHE="${XHCUT_CONTAINER_CACHE:-/cache}"

# The host has an RTX 4060. Passing it in lets the proxy builder decode on the GPU
# and keep frames there; without it the tool falls back to libx264 automatically.
# Set XHCUT_GPU=0 to run without the device. NVIDIA_DRIVER_CAPABILITIES=all is
# required: the default capability set mounts only libnvidia-ml (enough for
# nvidia-smi) and omits libnvidia-encode/libnvcuvid, so NVENC and NVDEC fail with
# "Cannot load libnvidia-encode.so.1" even though the GPU looks present.
GPU="${XHCUT_GPU:-1}"
GPU_ARGS=""
[[ "$GPU" == "1" ]] && GPU_ARGS="--gpus all"

BUILD_ONLY=0
[[ "${1:-}" == "--build" ]] && BUILD_ONLY=1

echo "=== [1/4] Building cutter-all.jar ==="
cd "$REPO_ROOT"
./gradlew :cutter:shadowJar --console=plain -q
JAR="$SCRIPT_DIR/build/libs/cutter-all.jar"
[[ -f "$JAR" ]] || { echo "build output missing: $JAR" >&2; exit 1; }
echo "jar: $JAR ($(du -h "$JAR" | cut -f1))"

echo "=== [2/4] Uploading to $REMOTE:$REMOTE_DIR ==="
ssh "$REMOTE" "mkdir -p '$REMOTE_DIR'"

# The media root is mounted read-write because the editor can delete recordings and
# can remove a source after exporting all of it. It runs as the uid that already owns
# the corpus, so unlinks are permitted; mount it `ro` instead and those actions fail
# with a 409 rather than doing anything unexpected.
#
# All three host directories must already exist: the container is not allowed to create
# anything on the host, and a typo in a path should fail here rather than produce a
# container that quietly writes into a new empty directory.
for spec in "media:$MEDIA_DIR" "cuts:$CUTS_DIR" "cache:$CACHE_DIR"; do
    label="${spec%%:*}"
    dir="${spec#*:}"
    if ! ssh "$REMOTE" "test -d '$dir'"; then
        echo "ERROR: $label directory does not exist on $REMOTE: $dir" >&2
        echo "       Create it once: ssh $REMOTE 'mkdir -p $dir'" >&2
        exit 1
    fi
done

# The cache is regenerable scratch (HLS segments, waveform PNGs) living on local disk,
# and the container writes to it as the runtime uid, so check writability as that user
# rather than as the deploy user — root's `test -w` would pass on a directory the
# container then cannot use.
if ! ssh "$REMOTE" "test -w '$CACHE_DIR'"; then
    echo "ERROR: cache directory is not writable by the deploy user on $REMOTE: $CACHE_DIR" >&2
    echo "       Expected owner: $RUN_UID:$RUN_UID. Fix with:" >&2
    echo "         ssh $REMOTE 'chown $RUN_UID:$RUN_UID $CACHE_DIR'" >&2
    exit 1
fi

scp -q "$JAR" "$REMOTE:$REMOTE_DIR/cutter-all.jar"
scp -q "$SCRIPT_DIR/Dockerfile" "$REMOTE:$REMOTE_DIR/Dockerfile"
scp -q "$SCRIPT_DIR/entrypoint.sh" "$REMOTE:$REMOTE_DIR/entrypoint.sh"

echo "=== [3/4] Building image '$IMAGE' on the host ==="
ssh "$REMOTE" bash -s <<REMOTE_BUILD
set -euo pipefail
cd "$REMOTE_DIR"
docker build -t "$IMAGE" .
REMOTE_BUILD

if [[ "$BUILD_ONLY" == "1" ]]; then
    echo "=== Done (image rebuilt; container left as-is) ==="
    exit 0
fi

echo "=== [4/4] Recreating container '$CONTAINER' ==="
ssh "$REMOTE" bash -s <<REMOTE_RUN
set -euo pipefail

# Run as the NAS-owning uid so cuts land next to the existing corpus with matching
# ownership, and so the cache directory created for that uid stays writable.
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true

docker run -d \
    --name "$CONTAINER" \
    --restart unless-stopped \
    --user $RUN_UID:$RUN_UID \
    $GPU_ARGS \
    -p $HOST_PORT:8092 \
    -e TZ=Asia/Shanghai \
    -e NVIDIA_DRIVER_CAPABILITIES=all \
    -e XHCUT_PORT=8092 \
    -e XHCUT_MEDIA="$CONTAINER_MEDIA" \
    -e XHCUT_OUT="$CONTAINER_CUTS" \
    -e XHCUT_CACHE="$CONTAINER_CACHE" \
    -v "$MEDIA_DIR":"$CONTAINER_MEDIA":rw \
    -v "$CUTS_DIR":"$CONTAINER_CUTS":rw \
    -v "$CACHE_DIR":"$CONTAINER_CACHE":rw \
    --log-opt max-size=10m --log-opt max-file=3 \
    "$IMAGE" >/dev/null

sleep 3
docker ps --filter "name=^$CONTAINER\$" --format 'container: {{.Names}} {{.Status}} {{.Ports}}'
REMOTE_RUN

echo "=== Done. UI: http://${REMOTE#*@}:$HOST_PORT/ ==="
