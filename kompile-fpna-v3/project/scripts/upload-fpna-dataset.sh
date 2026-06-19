#!/usr/bin/env bash
# upload-fpna-dataset.sh — Upload all FP&A 2026-05 artifacts to the kompile-fpna-v3 instance.
# Handles HTML, XLSX, and MD files. Skips BACKUP files and the large .m4v video.
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
DATA_DIR="${2:-/home/agibsonccc/Documents/GitHub/kompile/FP&A workflow artifacts 2026-05}"

if [ ! -d "$DATA_DIR" ]; then
    echo "ERROR: Data directory not found: $DATA_DIR"
    echo "Usage: $0 [BASE_URL] [DATA_DIR]"
    exit 1
fi

UPLOAD_ENDPOINT="$BASE_URL/api/documents/upload-async"
SUCCESS=0
FAIL=0
SKIP=0
TOTAL=0

echo "=== FP&A Dataset Upload ==="
echo "Source:   $DATA_DIR"
echo "Target:   $UPLOAD_ENDPOINT"
echo ""

for file in "$DATA_DIR"/*; do
    filename=$(basename "$file")

    # Skip BACKUP files
    if [[ "$filename" == *"BACKUP"* ]]; then
        echo "SKIP (backup): $filename"
        ((SKIP++))
        continue
    fi

    # Skip video files (too large for direct upload, would need transcription)
    if [[ "$filename" == *.m4v || "$filename" == *.mp4 || "$filename" == *.mov ]]; then
        echo "SKIP (video):  $filename"
        ((SKIP++))
        continue
    fi

    # Only upload known document types
    case "$filename" in
        *.html|*.xlsx|*.md)
            ((TOTAL++))
            echo -n "Uploading ($TOTAL): $filename ... "

            HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
                -X POST "$UPLOAD_ENDPOINT" \
                -F "file=@$file" \
                -F "processingMode=inprocess" \
                --max-time 120)

            if [ "$HTTP_CODE" -ge 200 ] && [ "$HTTP_CODE" -lt 300 ]; then
                echo "OK ($HTTP_CODE)"
                ((SUCCESS++))
            else
                echo "FAIL ($HTTP_CODE)"
                ((FAIL++))
            fi

            # Brief pause between uploads to avoid overwhelming the embedding pipeline
            sleep 2
            ;;
        *)
            echo "SKIP (unknown): $filename"
            ((SKIP++))
            ;;
    esac
done

echo ""
echo "=== Upload Summary ==="
echo "Uploaded: $SUCCESS"
echo "Failed:   $FAIL"
echo "Skipped:  $SKIP"
echo "Total:    $TOTAL"

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
