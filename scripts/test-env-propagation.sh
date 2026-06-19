#!/usr/bin/env bash
set -euo pipefail

###############################################################################
# test-env-propagation.sh
#
# Rebuilds kompile modules with the centralized SubprocessEnvironmentPropagator
# changes, rebuilds the fpna-v4 subproject JAR, restarts the app, and starts
# a crawl to verify ND4J/Triton env vars propagate to subprocesses.
###############################################################################

MVN=/home/agibsonccc/dev-apps/mvn/bin/mvn
PROJECT_DIR=/home/agibsonccc/Documents/GitHub/kompile
FPNA_PROJECT=$PROJECT_DIR/kompile-rag-builds/kompile-fpna-v4/kompile-fpna-v4/project
FPNA_JAR=$FPNA_PROJECT/target/kompile-fpna-v4-0.1.0-SNAPSHOT.jar
PORT=8182
LOG_FILE=$FPNA_PROJECT/data/logs/app-$(date +%Y%m%d-%H%M%S).log

echo "============================================="
echo " ENV PROPAGATION TEST"
echo " $(date)"
echo "============================================="

# -------------------------------------------------------------------
# Step 1: Kill only the old fpna-v4 app process (by port match)
# -------------------------------------------------------------------
echo ""
echo "[1/6] Stopping old app on port $PORT..."
OLD_PID=$(lsof -ti :$PORT 2>/dev/null || true)
if [ -n "$OLD_PID" ]; then
    echo "  Killing PID $OLD_PID"
    kill $OLD_PID 2>/dev/null || true
    # Wait for it to actually die
    for i in $(seq 1 30); do
        if ! kill -0 $OLD_PID 2>/dev/null; then
            echo "  Process stopped."
            break
        fi
        sleep 1
    done
else
    echo "  No process on port $PORT"
fi

# Also kill old embedding subprocesses spawned by the old app
EMBED_PIDS=$(ps aux | grep 'embedding-subprocess' | grep -v grep | awk '{print $2}' || true)
if [ -n "$EMBED_PIDS" ]; then
    echo "  Killing old embedding subprocesses: $EMBED_PIDS"
    echo "$EMBED_PIDS" | xargs kill 2>/dev/null || true
fi

# Kill old model-staging subprocess
STAGING_PIDS=$(ps aux | grep 'kompile-model-staging' | grep -v grep | awk '{print $2}' || true)
if [ -n "$STAGING_PIDS" ]; then
    echo "  Killing old model-staging subprocesses: $STAGING_PIDS"
    echo "$STAGING_PIDS" | xargs kill 2>/dev/null || true
fi

sleep 2

# -------------------------------------------------------------------
# Step 2: Build affected modules
# -------------------------------------------------------------------
echo ""
echo "[2/6] Building affected modules..."
cd $PROJECT_DIR
$MVN install -DskipTests \
    -pl kompile-app/kompile-app-parent/kompile-app-core,kompile-app/kompile-app-parent/kompile-app-main,kompile-app/kompile-models/kompile-llm-parent/kompile-embeddings/kompile-embedding-anserini,kompile-app/kompile-models/kompile-model-staging,kompile-app/kompile-data/kompile-pipelines/kompile-pipeline-serving \
    2>&1 | tee /tmp/env-propagation-build.log | tail -20

if ! grep -q 'BUILD SUCCESS' /tmp/env-propagation-build.log; then
    echo "BUILD FAILED — see /tmp/env-propagation-build.log"
    exit 1
fi

# -------------------------------------------------------------------
# Step 3: Rebuild fpna-v4 subproject JAR
# -------------------------------------------------------------------
echo ""
echo "[3/6] Rebuilding fpna-v4 JAR..."
cd $FPNA_PROJECT
$MVN clean package -DskipTests 2>&1 | tee /tmp/fpna-v4-build.log | tail -10

if ! grep -q 'BUILD SUCCESS' /tmp/fpna-v4-build.log; then
    echo "FPNA-V4 BUILD FAILED — see /tmp/fpna-v4-build.log"
    exit 1
fi

echo "  JAR: $(ls -lh $FPNA_JAR | awk '{print $5, $9}')"

# -------------------------------------------------------------------
# Step 4: Start the app
# -------------------------------------------------------------------
echo ""
echo "[4/6] Starting app on port $PORT..."
echo "  Log: $LOG_FILE"

cd $FPNA_PROJECT
nohup java -jar $FPNA_JAR --server.port=$PORT > "$LOG_FILE" 2>&1 &
APP_PID=$!
echo "  PID: $APP_PID"

# Wait for app to become healthy
echo "  Waiting for app to start..."
for i in $(seq 1 120); do
    if curl -sf http://localhost:$PORT/api/unified-crawl/jobs 2>/dev/null | python3 -c "import sys,json; json.load(sys.stdin)" 2>/dev/null; then
        echo "  App is UP after ${i}s"
        break
    fi
    if ! kill -0 $APP_PID 2>/dev/null; then
        echo "  App crashed! Check log: $LOG_FILE"
        tail -30 "$LOG_FILE"
        exit 1
    fi
    if [ $i -eq 120 ]; then
        echo "  Timed out after 120s. Log tail:"
        tail -30 "$LOG_FILE"
        exit 1
    fi
    sleep 1
done

# -------------------------------------------------------------------
# Step 5: Start a crawl
# -------------------------------------------------------------------
echo ""
echo "[5/6] Starting crawl..."

CRAWL_RESPONSE=$(curl -sf -X POST http://localhost:$PORT/api/unified-crawl/start \
    -H 'Content-Type: application/json' \
    -d '{
        "name": "env-propagation-test",
        "sources": [
            {
                "label": "FP&A documents",
                "sourceType": "DIRECTORY",
                "pathOrUrl": "/home/agibsonccc/.kompile/documents"
            }
        ],
        "vectorIndex": {"enabled": true},
        "graphExtraction": {"enabled": true}
    }')

echo "  Crawl response: $CRAWL_RESPONSE"
JOB_ID=$(echo "$CRAWL_RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('jobId','unknown'))" 2>/dev/null || echo "unknown")
echo "  Job ID: $JOB_ID"

# -------------------------------------------------------------------
# Step 6: Monitor crawl + verify env propagation
# -------------------------------------------------------------------
echo ""
echo "[6/6] Monitoring crawl and verifying env propagation..."
echo ""

# Check if embedding subprocess gets the triton cache dir
sleep 10  # Give subprocesses time to spawn

echo "--- Subprocess Environment Check ---"
EMBED_PID=$(ps aux | grep 'embedding-subprocess' | grep -v grep | awk '{print $2}' | head -1)
if [ -n "$EMBED_PID" ]; then
    echo "Embedding subprocess PID: $EMBED_PID"
    echo "ND4J_TRITON_CACHE_DIR in env:"
    cat /proc/$EMBED_PID/environ 2>/dev/null | tr '\0' '\n' | grep 'ND4J_TRITON_CACHE_DIR' || echo "  NOT FOUND (this is the bug we're testing for)"
    echo ""
    echo "All ND4J_ env vars:"
    cat /proc/$EMBED_PID/environ 2>/dev/null | tr '\0' '\n' | grep '^ND4J_' | head -20 || echo "  none"
    echo ""
    echo "CUDA_ env vars:"
    cat /proc/$EMBED_PID/environ 2>/dev/null | tr '\0' '\n' | grep '^CUDA_' | head -10 || echo "  none"
else
    echo "  No embedding subprocess found yet — will check during monitoring"
fi

echo ""
echo "--- Crawl Progress ---"

# Poll crawl status — fail fast if no job appears within 30s
NO_JOB_COUNT=0
for i in $(seq 1 120); do
    STATUS_JSON=$(curl -sf http://localhost:$PORT/api/unified-crawl/jobs/active 2>/dev/null || echo '{}')
    STATUS=$(echo "$STATUS_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); print(f'Phase: {d.get(\"currentPhase\",\"?\")}, Status: {d.get(\"status\",\"?\")}, Docs: {d.get(\"documentsLoaded\",0)}, Chunks: {d.get(\"chunksCreated\",0)}, Embedded: {d.get(\"chunksEmbedded\",0)}, Errors: {d.get(\"errorCount\",0)}')" 2>/dev/null || echo "No active job")

    echo "  [$((i*10))s] $STATUS"

    # Fail fast if no job after 30s
    if echo "$STATUS" | grep -q "No active job"; then
        NO_JOB_COUNT=$((NO_JOB_COUNT + 1))
        if [ $NO_JOB_COUNT -ge 3 ]; then
            echo ""
            echo "  ERROR: No active crawl job after ${NO_JOB_COUNT} checks."
            echo "  Checking scheduler queue..."
            curl -sf http://localhost:$PORT/api/scheduler/jobs 2>/dev/null | python3 -m json.tool 2>/dev/null | head -30 || echo "  No scheduler info"
            echo ""
            echo "  Last 20 crawl-related log lines:"
            grep -i 'crawl\|unified.*crawl\|source' "$LOG_FILE" 2>/dev/null | tail -20
            echo ""
            echo "  CRAWL DID NOT START — check request body and app logs."
            break
        fi
    else
        NO_JOB_COUNT=0
    fi

    # Check if done
    if echo "$STATUS_JSON" | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if d.get('status') in ('COMPLETED','FAILED','CANCELLED','COMPLETED_PENDING_EMBEDDING') else 1)" 2>/dev/null; then
        echo ""
        echo "Crawl finished!"
        echo "$STATUS_JSON" | python3 -m json.tool 2>/dev/null | head -30
        break
    fi

    # Re-check embedding subprocess env if we haven't found it yet
    if [ -z "${EMBED_CHECKED:-}" ]; then
        EMBED_PID=$(ps aux | grep 'embedding-subprocess' | grep -v grep | awk '{print $2}' | head -1)
        if [ -n "$EMBED_PID" ]; then
            EMBED_CHECKED=1
            echo ""
            echo "  >>> Embedding subprocess found (PID $EMBED_PID) — checking env:"
            TRITON_DIR=$(cat /proc/$EMBED_PID/environ 2>/dev/null | tr '\0' '\n' | grep 'ND4J_TRITON_CACHE_DIR' || true)
            if [ -n "$TRITON_DIR" ]; then
                echo "  >>> PASS: $TRITON_DIR"
            else
                echo "  >>> FAIL: ND4J_TRITON_CACHE_DIR not propagated!"
            fi
            echo ""
        fi
    fi

    sleep 10
done

echo ""
echo "============================================="
echo " Test complete. App PID: $APP_PID"
echo " Log: $LOG_FILE"
echo "============================================="
