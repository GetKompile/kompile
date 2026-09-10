#!/usr/bin/env bash
# upload-fpna-dataset.sh — Upload all FPNA dataset files to the kompile app
# Uses the async upload endpoint which routes through DocumentIngestService
# (includes LLM graph extraction, email entity extraction, cross-doc relations).
#
# Usage:
#   ./scripts/upload-fpna-dataset.sh [DATA_DIR] [BASE_URL]
#
# Defaults:
#   DATA_DIR  = /home/agibsonccc/Documents/GitHub/fpna-workflow
#   BASE_URL  = http://localhost:8080

set -euo pipefail

DATA_DIR="${1:-/home/agibsonccc/Documents/GitHub/fpna-workflow}"
BASE_URL="${2:-http://localhost:8080}"
UPLOAD_URL="${BASE_URL}/api/documents/upload-async"

if [ ! -d "$DATA_DIR" ]; then
    echo "ERROR: Data directory not found: $DATA_DIR"
    exit 1
fi

# Verify the app is running
if ! curl -sf "${BASE_URL}/api/fact-sheets" >/dev/null 2>&1; then
    echo "ERROR: App not responding at ${BASE_URL}"
    echo "Start it first: java -Xmx4g -jar target/kompile-fpna-0.1.0-SNAPSHOT.jar"
    exit 1
fi

echo "=== FPNA Dataset Upload ==="
echo "Data dir: $DATA_DIR"
echo "Target:   $UPLOAD_URL"
echo ""

TOTAL=0
SUCCESS=0
FAILED=0

# Upload each file (skip .m4v video and backup files)
for f in "$DATA_DIR"/*.{html,xlsx,md} ; do
    [ -f "$f" ] || continue

    # Skip backup files
    basename=$(basename "$f")
    if [[ "$basename" == *BACKUP* ]]; then
        echo "SKIP (backup): $basename"
        continue
    fi

    TOTAL=$((TOTAL + 1))
    echo -n "Uploading: $basename ... "

    response=$(curl -s -w "\n%{http_code}" \
        -F "file=@${f}" \
        -F "processingMode=inprocess" \
        "${UPLOAD_URL}" 2>&1)

    http_code=$(echo "$response" | tail -1)
    body=$(echo "$response" | head -n -1)

    if [ "$http_code" = "202" ] || [ "$http_code" = "200" ]; then
        task_id=$(echo "$body" | python3 -c "import json,sys; print(json.load(sys.stdin).get('taskId','unknown'))" 2>/dev/null || echo "unknown")
        echo "OK (${http_code}) → task: ${task_id}"
        SUCCESS=$((SUCCESS + 1))
    else
        echo "FAILED (${http_code})"
        echo "  Response: $(echo "$body" | head -1)"
        FAILED=$((FAILED + 1))
    fi

    # Delay between files to avoid overwhelming the async pipeline
    sleep 2
done

echo ""
echo "=== Upload Summary ==="
echo "Total:   $TOTAL"
echo "Success: $SUCCESS"
echo "Failed:  $FAILED"
echo ""
echo "Note: Files are being processed asynchronously via DocumentIngestService."
echo "This includes: chunking → embedding → indexing → KG nodes → email extraction → LLM graph extraction → cross-doc relations"
echo "Check graph: curl -s ${BASE_URL}/api/knowledge-graph/nodes?limit=100"
