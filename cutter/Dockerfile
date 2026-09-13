# XhCut — remote cutter for XhRec recordings.
#
# Alpine ffmpeg is used for every transcode; it ships libx264, aac, hls, mpegts and
# mp4, which is everything the preview pipeline and the keyframe-aligned copy-cut
# need. VAAPI is deliberately not wired up: the recording host exposes
# /dev/dri but libva fails to initialise there, so encoding stays on the CPU.
# Ubuntu rather than Alpine purely for ffmpeg: Alpine's build has no NVENC/CUDA, and
# the host ffmpeg (which does) is glibc-linked so it cannot be bind-mounted into musl.
# Ubuntu's package ships h264_nvenc and the cuda hwaccel; the NVIDIA driver libraries
# come from `--gpus all` at run time. ProxyService probes for NVENC and silently falls
# back to libx264 when it is absent, so this still runs on a GPU-less host.
FROM eclipse-temurin:17-jre-jammy

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        ffmpeg tzdata curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && cp /usr/share/zoneinfo/Asia/Shanghai /etc/localtime \
    && echo "Asia/Shanghai" > /etc/timezone \
    && mkdir -p /config /logs

WORKDIR /

COPY cutter-all.jar /cutter-all.jar
COPY entrypoint.sh /entrypoint.sh
# Explicit modes: the container runs as a non-root uid, which needs read access to
# both files (`chmod +x` alone would leave the script unreadable and unexecutable).
RUN chmod 644 /cutter-all.jar && chmod 755 /entrypoint.sh

# /config and /logs are created here because they are fixed parts of the image; the
# three recording locations are NOT, so that the image cannot present an empty directory
# that looks like a configured media root. `entrypoint.sh` creates the two it is given.

# 8092 on the host is free; 8090 already serves the XhRec dashboard.
EXPOSE 8092

# Only defaults live here. The three locations (XHCUT_MEDIA / XHCUT_OUT / XHCUT_CACHE)
# are deliberately absent: they are deployment-specific, so they are passed at run time
# (`docker run -e ...` or `-v ...`) and the entrypoint refuses to start without them.
ENV TZ=Asia/Shanghai \
    XHCUT_PORT=8092 \
    XHCUT_JAVA_OPTS="-Xms256m -Xmx1g"

HEALTHCHECK --interval=60s --timeout=10s --start-period=40s --retries=3 \
    CMD curl -fsS "http://127.0.0.1:${XHCUT_PORT}/api/health" >/dev/null || exit 1

ENTRYPOINT ["/entrypoint.sh"]
