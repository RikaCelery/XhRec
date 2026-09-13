#!/usr/bin/env bash
#
# Batch-compare recorded toy-command durations against the Lovense overlay that is
# burned into the Marry_Cordy-style recordings.
#
# The overlay is ground truth: it shows `Responding to <user>` with a live ring
# countdown of the command's remaining seconds. Sampling a burst of frames around a
# command's predicted start therefore reveals its real duration.
#
#   ./panel-durations.sh                 # extract strips for the built-in samples
#   ./panel-durations.sh events.txt      # or your own "<event_t> <recorded_seconds>" list
#
# It writes /tmp/ocrjobs/ring_<n>.png — one strip per event, 8 frames one second
# apart, cropped to the ring.
#
# ⚠️ READ THE STRIPS, DO NOT TRUST THE OCR.
#
# The script also runs tesseract for convenience, but on this overlay it is not
# reliable: on a perfectly clean frame showing "26s" it returned 16, and on a frame
# showing "1s" it returned 7. The digits are a stylised font, thin white on a
# saturated ring over moving video, which is about the worst case for a general OCR
# model. Verified by eye against the same images: durations matched the events
# exactly (6s, 20s, 1s, 4s, 1s, 6s all correct).
#
# Reliable options, in order of effort:
#   1. Look at the strips — 8 rings per event is readable at a glance.
#   2. Template-match the digits instead of OCR'ing them (the glyph set is tiny).
#
set -euo pipefail

REMOTE="${PANEL_REMOTE:-celery@10.0.2.202}"
MEDIA="${PANEL_MEDIA:-/media/nas/out/tmp/Marry_Cordy/Marry_Cordy-2026_05_11_12_03_42-09h11m44s.fixed.mp4}"

# Event times come from the lanes API with `nudge=0`; the video lags the event
# stream by this much, which is the offset the editor's slider compensates for.
OFFSET="${PANEL_OFFSET:-8.7}"

# Ring geometry measured on this recording (1280x720).
CROP="${PANEL_CROP:-crop=104:74:64:424,scale=520:370,eq=contrast=1.6:brightness=0.06}"

EVENTS_FILE="${1:-}"
LIST="$(mktemp -t panelevents.XXXXXX)"
if [[ -n "$EVENTS_FILE" ]]; then
    cp "$EVENTS_FILE" "$LIST"
else
    cat > "$LIST" <<'SAMPLES'
1162.0 6.0
1376.0 20.0
1756.0 1.0
4082.0 4.0
10705.0 1.0
13065.0 6.0
SAMPLES
fi

REMOTE_SCRIPT="$(mktemp -t paneldur.XXXXXX.sh)"
cat > "$REMOTE_SCRIPT" <<'REMOTE'
set -u
W=/tmp/ocrjobs; rm -rf "$W"; mkdir -p "$W"
n=0
while read -r T DUR; do
    [ -n "${T:-}" ] || continue
    n=$((n + 1)); d=$(printf %02d "$n")
    j=0
    # Start two seconds early so a short command is still caught despite the
    # offset's residual error, and run two seconds past the predicted end.
    for k in -2 -1 0 1 2 3 4 5; do
        j=$((j + 1))
        S=$(awk -v t="$T" -v o="$OFFSET" -v k="$k" 'BEGIN{printf "%.2f", t+o+k}')
        ffmpeg -v error -ss "$S" -i "$MEDIA" -frames:v 1 -vf "$CROP" -q:v 2 \
            -y "$W/r${d}_$(printf %02d "$j").png" 2>/dev/null
    done
    ffmpeg -v error -start_number 1 -i "$W/r${d}_%02d.png" \
        -vf "tile=8x1:padding=4:color=black" -frames:v 1 -y "$W/ring_$d.png" 2>/dev/null
    rm -f "$W"/r${d}_*.png
    echo "  $d  event_t=$T  recorded=${DUR}s"
done < /tmp/panelevents.txt
echo "strips: $(ls "$W"/ring_*.png 2>/dev/null | wc -l)  ->  $W"
REMOTE

scp -q "$LIST" "$REMOTE:/tmp/panelevents.txt"
scp -q "$REMOTE_SCRIPT" "$REMOTE:/tmp/paneldur.sh"
ssh "$REMOTE" "OFFSET='$OFFSET' MEDIA='$MEDIA' CROP='$CROP' bash /tmp/paneldur.sh"

echo
echo "Fetching strips and running tesseract (advisory only — see the header):"
rm -rf /tmp/ocrjobs && mkdir -p /tmp/ocrjobs
scp -q "$REMOTE:/tmp/ocrjobs/ring_*.png" /tmp/ocrjobs/

i=0
while read -r T DUR; do
    [ -n "${T:-}" ] || continue
    i=$((i + 1)); f=$(printf "/tmp/ocrjobs/ring_%02d.png" "$i")
    [[ -f "$f" ]] || continue
    read -r -a nums <<<"$(tesseract "$f" - --psm 11 -c tessedit_char_whitelist=0123456789 2>/dev/null \
        | grep -oE '[0-9]+' | sort -n -u | tr '\n' ' ')"
    printf "  event_t=%-9s recorded=%-6s ocr_saw=[%s]\n" "$T" "$DUR" "${nums[*]:-}"
done < "$LIST"

rm -f "$LIST" "$REMOTE_SCRIPT"
