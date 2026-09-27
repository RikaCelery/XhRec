#!/bin/bash

SKIP_RENDER=false
COLOR_FILTER=""

while getopts ":scC:" opt; do
    case $opt in
        s|c) SKIP_RENDER=true ;;
        C) COLOR_FILTER="$OPTARG" ;;
        \?) echo "Unknown option: -$OPTARG"; exit 1 ;;
        :) echo "Option -$OPTARG requires an argument"; exit 1 ;;
    esac
done
shift $((OPTIND - 1))

if [ "$#" -ne 2 ]; then
    echo "Usage: $0 <input_project.mlt> <output_video.mkv>"
    exit 1
fi

# Resolve absolute paths to handle NFS mounts correctly
MLT_FILE="$(realpath "$1")"
FINAL_MKV="$(realpath -m "$2")"
CHAPTERS_TXT="chapters.txt"

# Get the directory names for mounting
MLT_DIR="$(dirname "$MLT_FILE")"
MKV_DIR="$(dirname "$FINAL_MKV")"

cleanup() {
    echo ""
    echo "Interrupted — stopping container..."
    docker stop mlt_render 2>/dev/null
    rm -f "$CHAPTERS_TXT"
    exit 1
}
trap cleanup INT TERM

echo "=== Step 1: Extracting Markers for mkvmerge ==="
rm -f "$CHAPTERS_TXT"

# Use Python to accurately parse, sort, and format the XML structure
python3 -c "
import xml.etree.ElementTree as ET
import sys

try:
    tree = ET.parse('$MLT_FILE')
    root = tree.getroot()
    color_filter_raw = '$COLOR_FILTER'
    markers_list = []

    # Parse comma-separated colors, normalize to lowercase
    if color_filter_raw:
        color_filter = {c.strip().lstrip('#').lower() for c in color_filter_raw.split(',')}
    else:
        color_filter = set()

    # Search for any properties element that contains shotcut markers
    for props in root.findall('.//properties'):
        if props.get('name') == 'shotcut:markers':
            # Iterate through each individual marker block
            for marker in props.findall('properties'):
                title = ''
                start = ''
                for prop in marker.findall('property'):
                    prop_name = prop.get('name')
                    if prop_name == 'text':
                        title = prop.text
                    elif prop_name == 'start':
                        start = prop.text
                    elif prop_name == 'color':
                        color = prop.text.strip().lstrip('#').lower()

                if start:
                    if color_filter and color not in color_filter:
                        continue
                    if not title:
                        title = 'Marker'
                    markers_list.append({'start': start, 'title': title})

    # Sort the markers chronologically by their start timestamp strings
    # (Works perfectly for HH:MM:SS.mmm format)
    markers_list.sort(key=lambda x: x['start'])

    # Print them sequentially after sorting
    for count, m in enumerate(markers_list, start=1):
        # Fallback if title was generic
        title = f'Marker {count}' if m['title'] == 'Marker' else m['title']
        print(f'CHAPTER{count:02d}={m[\"start\"]}')
        print(f'CHAPTER{count:02d}NAME={title}')

except Exception as e:
    print(f'Error parsing XML: {e}', file=sys.stderr)
    sys.exit(1)
" > "$CHAPTERS_TXT"

if [ -s "$CHAPTERS_TXT" ]; then
    echo "Markers successfully extracted (and sorted) to $CHAPTERS_TXT:"
    cat "$CHAPTERS_TXT"
else
    echo "Warning: No markers found or parsed from MLT file."
fi
echo ""

if [ "$SKIP_RENDER" = true ]; then
    echo "Skipping render (-s flag set)."
    if [ ! -f "$FINAL_MKV" ]; then
        echo "Error: Output file does not exist yet, cannot apply chapters."
        rm -f "$CHAPTERS_TXT"
        exit 1
    fi
else

    echo "=== Step 2: Directly Rendering to MKV via Melt ==="

    # Render directly to your target MKV file
    # docker run --rm \
        # -v "$MLT_DIR":"$MLT_DIR" \
        # -v "$MKV_DIR":"$MKV_DIR" \
    docker run -it --rm --name mlt_render \
        --user "$(id -u):65537" \
        -v "/mnt/Multimedia":"/mnt/Multimedia" \
        -v "$MKV_DIR":"$MKV_DIR" \
        -w "$MLT_DIR" \
        mltframework/melt:latest \
        "$MLT_FILE" -consumer avformat:"$FINAL_MKV" vcodec=libx265 acodec=aac crf=27 pix_fmt=yuv420p10le progress=1 2>&1 \
    | tee /dev/tty \
    | grep --line-buffered "current_frame:" \
    | pv -l > /dev/null

    RENDER_EXIT=${PIPESTATUS[0]}

    if [ "$RENDER_EXIT" -ne 0 ]; then
        echo "Error: Melt rendering failed (exit code $RENDER_EXIT)."
        rm -f "$CHAPTERS_TXT"
        exit 1
    fi
fi
sync
chmod 664 "$FINAL_MKV"
echo ""
echo "=== Step 3: Injecting Chapters via mkvmerge ==="

if [ -f "$CHAPTERS_TXT" ] && [ -s "$CHAPTERS_TXT" ]; then
    mkvpropedit "$FINAL_MKV" --chapters "$CHAPTERS_TXT"
    if [ $? -eq 0 ]; then
        echo "Chapters successfully merged!"
    else
        echo "Error: mkvpropedit failed."
    fi
else
    echo "No chapters found to merge."
fi

echo ""
echo "=== Step 4: Cleaning up temporary files ==="
rm -f "$CHAPTERS_TXT"

echo "Done! Final file: $FINAL_MKV"
