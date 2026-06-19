#!/usr/bin/env bash
# Isolated single-document crawl test with DSP diagnostics.
# Tests the specific document that causes zero-magnitude embedding errors.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
LOG_DIR="$PROJECT_DIR/data/logs"
APP_PORT="${APP_PORT:-8080}"
APP_URL="http://localhost:${APP_PORT}"
STAGING_PORT="${STAGING_PORT:-8090}"

# The document that contains the failing FX rates table chunk
TEST_DOC="${TEST_DOC:-$HOME/Documents/GitHub/kompile/FP&A workflow artifacts 2026-05/10_semantic_layer_DRAFT.html}"
FACT_SHEET_ID="${FACT_SHEET_ID:-1}"
COLLECTION_NAME="${COLLECTION_NAME:-fpna-workflow-v3}"
SCHEMA_PRESET_ID="${SCHEMA_PRESET_ID:-fpna-cpg-channel-v1}"
POLL_SECONDS="${POLL_SECONDS:-5}"

mkdir -p "$LOG_DIR"

echo "=== Single-document embedding diagnostic test ==="
echo "Document: $(basename "$TEST_DOC")"
echo "Size: $(wc -c < "$TEST_DOC") bytes"
echo ""

# --- Step 1: Verify runtime is up ---
echo "[Step 1] Checking runtime..."
if ! curl -sf --max-time 5 "$APP_URL/actuator/health" >/dev/null 2>&1; then
    echo "  App not running. Start with: bash start"
    exit 1
fi
echo "  App is UP at $APP_URL"

if ! curl -sf --max-time 5 "http://localhost:$STAGING_PORT/api/staging/status" >/dev/null 2>&1; then
    echo "  Staging not running at port $STAGING_PORT"
    exit 1
fi
echo "  Staging is UP at http://localhost:$STAGING_PORT"

# --- Step 2: Check DSP diagnostics are enabled ---
echo ""
echo "[Step 2] Verifying DSP diagnostics..."
DSP_DIAG=$(python3 -c "
import json
with open('$HOME/.kompile/config/nd4j-environment-config.json') as f:
    c = json.load(f)
print(c.get('dspDiagnostics', 'NOT SET'))
print('debug=' + str(c.get('debug', False)))
print('verbose=' + str(c.get('verbose', False)))
# Also check DSP flags are not explicitly set
for k in ['tritonSkipKernels', 'dspNoFreeze', 'dspNoNativeDecode', 'dspNoAttnOverride', 'dspNoDirect']:
    v = c.get(k)
    if v is not None:
        print(f'WARNING: {k}={v} (should be null)')
")
echo "  $DSP_DIAG"

# --- Step 3: Clean finished crawl jobs ---
echo ""
echo "[Step 3] Cleaning finished crawl jobs..."
curl -sf -X POST "$APP_URL/api/unified-crawl/jobs/cleanup" >/dev/null 2>&1 || true

# --- Step 4: Start a crawl targeting just the single test document ---
echo ""
echo "[Step 4] Starting single-document crawl with DSP diagnostics..."

# Create a temp directory with just the one test doc
TEST_DIR=$(mktemp -d /tmp/kompile-single-doc-test.XXXXXX)
cp "$TEST_DOC" "$TEST_DIR/"
echo "  Copied $(basename "$TEST_DOC") to $TEST_DIR"

CRAWL_REQ=$(jq -n \
    --arg name "DSP diagnostic test - $(basename "$TEST_DOC") $(date +%Y%m%d-%H%M%S)" \
    --arg dataDir "$TEST_DIR" \
    --arg collectionName "$COLLECTION_NAME" \
    --arg schemaPresetId "$SCHEMA_PRESET_ID" \
    --argjson factSheetId "$FACT_SHEET_ID" \
    '{
        name: $name,
        factSheetId: $factSheetId,
        sources: [
            {
                label: "single-doc-test",
                sourceType: "DIRECTORY",
                pathOrUrl: $dataDir,
                maxDepth: 1,
                maxDocuments: 1
            }
        ],
        graphExtraction: {
            enabled: false
        },
        vectorIndex: {
            enabled: true,
            collectionName: $collectionName,
            chunkerName: "recursive",
            embeddingBatchSize: 2,
            maxEmbeddingBatchSize: 2,
            adaptiveBatching: false
        }
    }')

echo "  Request: graphExtraction=disabled vectorIndex=enabled (embedding-only test)"
CRAWL_RESP=$(curl -sf -X POST "$APP_URL/api/unified-crawl/start" \
    -H "Content-Type: application/json" \
    -d "$CRAWL_REQ" 2>/dev/null)
echo "  Response: $CRAWL_RESP"
JOB_ID=$(echo "$CRAWL_RESP" | python3 -c "import json,sys; print(json.load(sys.stdin).get('jobId',''))" 2>/dev/null)

if [ -z "$JOB_ID" ]; then
    echo "  ERROR: Failed to start crawl. Response: $CRAWL_RESP"
    exit 1
fi
echo "  Crawl job started: $JOB_ID"

# --- Step 6: Monitor the crawl ---
echo ""
echo "[Step 6] Monitoring crawl (poll every ${POLL_SECONDS}s)..."
echo "  Watching for: VECTOR_INDEXING phase, zero-magnitude errors, DSP diagnostics"
echo ""

FINAL_STATUS=""
while true; do
    STATUS_JSON=$(curl -sf "$APP_URL/api/unified-crawl/jobs/$JOB_ID" 2>/dev/null || echo '{}')

    STATUS=$(echo "$STATUS_JSON" | python3 -c "
import json, sys
d = json.load(sys.stdin)
s = d.get('status','UNKNOWN')
p = d.get('progressPercent', d.get('progress', 0))
ph = d.get('currentPhase','?')
err = d.get('errorCount',0)
emb = d.get('chunksEmbedded', d.get('embeddedChunks', 0))
vbd = d.get('vectorBatchesDone',0)
vbt = d.get('vectorBatchesTotal',0)
chunks = d.get('chunksProcessed', d.get('chunksCreated', 0))
le = d.get('lastError','')
print(f'{s}|{p}|{ph}|{err}|{emb}|{vbd}/{vbt}|{chunks}')
if le:
    print(f'ERROR: {le[:200]}')
" 2>/dev/null)

    IFS='|' read -r ST PROG PHASE ERRS EMBD VB CHUNKS <<< "$(echo "$STATUS" | head -1)"
    ERR_LINE=$(echo "$STATUS" | tail -n +2)

    TS=$(date +%H:%M:%S)
    echo "[$TS] status=$ST progress=${PROG}% phase=$PHASE errors=$ERRS embedded=$EMBD vectorBatches=$VB chunks=$CHUNKS"
    if [ -n "$ERR_LINE" ]; then
        echo "  $ERR_LINE"
    fi

    if [ "$ST" = "COMPLETED" ] || [ "$ST" = "FAILED" ] || [ "$ST" = "CANCELLED" ]; then
        FINAL_STATUS="$ST"
        break
    fi

    sleep "$POLL_SECONDS"
done

# --- Step 7: Collect diagnostic output ---
echo ""
echo "=== Crawl $FINAL_STATUS ==="
echo ""

if [ "$FINAL_STATUS" = "FAILED" ]; then
    echo "[Diagnostic output]"
    echo ""
    echo "--- Embedding subprocess DSP logs ---"
    # The subprocess logs DSP diagnostics to stderr which goes to the app log
    grep -E "DSP|dsp|FREEZE|freeze|SLOT|slot|plan|Plan|DOUBLE|double|dtype|magnitude|zero" \
        "$LOG_DIR/app.out.log" 2>/dev/null | tail -50
    echo ""
    echo "--- Embedding subprocess stderr ---"
    # Check for any subprocess-specific log files
    for f in "$LOG_DIR"/embedding-subprocess*.log "$LOG_DIR"/subprocess*.log; do
        if [ -f "$f" ]; then
            echo "  File: $f"
            grep -E "DSP|dsp|FREEZE|freeze|SLOT|slot|plan|Plan|DOUBLE|double|dtype|magnitude|zero|ERROR|error" \
                "$f" 2>/dev/null | tail -30
        fi
    done
    echo ""
    echo "--- Last 30 lines of app log ---"
    tail -30 "$LOG_DIR/app.out.log" 2>/dev/null
fi

echo ""
echo "Full app log: $LOG_DIR/app.out.log"
echo "Crawl job ID: $JOB_ID"
