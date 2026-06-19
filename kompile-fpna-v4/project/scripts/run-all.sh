#!/usr/bin/env bash
# run-all.sh — Full pipeline: build, start staging + app, crawl all FP&A data into knowledge graph.
# This is the master orchestrator script. Run it once and the FP&A project is live.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
REPO_ROOT="$(cd "$PROJECT_DIR/../.." && pwd)"
BASE_URL="${1:-http://localhost:8080}"
STAGING_URL="${KOMPILE_STAGING_URL:-http://localhost:8090}"
MVN="${MVN_HOME:-/home/agibsonccc/dev-apps/mvn/bin/mvn}"
KOMPILE_CLI_JAR="$REPO_ROOT/kompile-cli/target/kompile-cli-0.1.0-SNAPSHOT-shaded.jar"
DATA_DIR="$REPO_ROOT/FP&A workflow artifacts 2026-05"
APP_JAVA_OPTS="${KOMPILE_APP_JAVA_OPTS:--Xmx8g -Dorg.bytedeco.javacpp.maxphysicalbytes=40g -Dorg.bytedeco.javacpp.maxbytes=40g}"

echo "=============================================="
echo "  FP&A Workflow Console v3 — Full Setup"
echo "=============================================="
echo "Project:  $PROJECT_DIR"
echo "App URL:  $BASE_URL"
echo "Staging:  $STAGING_URL"
echo "Data:     $DATA_DIR"
echo "CLI JAR:  $KOMPILE_CLI_JAR"
echo "App JVM:  $APP_JAVA_OPTS"
echo ""

# --- Validate prerequisites ---
if [ ! -d "$DATA_DIR" ]; then
    echo "FATAL: FP&A data directory not found: $DATA_DIR"
    exit 1
fi

if [ ! -f "$KOMPILE_CLI_JAR" ]; then
    echo "FATAL: CLI JAR not found: $KOMPILE_CLI_JAR"
    echo "       Build it first: cd $REPO_ROOT/kompile-cli && $MVN clean package -DskipTests"
    exit 1
fi

# --- Step 1: Build the project ---
echo "=== Step 1/4: Build ==="
cd "$PROJECT_DIR"
if [ ! -f "target/kompile-fpna-v3-0.1.0-SNAPSHOT.jar" ]; then
    echo "Building project..."
    "$MVN" clean package -DskipTests 2>&1 | tail -5
    echo "Build complete."
else
    echo "JAR already exists, skipping build. (Delete target/ to force rebuild.)"
fi

# --- Step 2: Start staging server (model server for LFM2) ---
echo ""
echo "=== Step 2/4: Staging server ==="
STAGING_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$STAGING_URL/actuator/health" --max-time 5 2>/dev/null || echo "000")
if [ "$STAGING_HEALTH" = "200" ]; then
    echo "Staging server already running at $STAGING_URL"
else
    echo "Starting staging server..."
    java -jar "$KOMPILE_CLI_JAR" manage start staging 2>&1 &
    echo "Waiting for staging server..."
    for i in $(seq 1 30); do
        STAGING_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$STAGING_URL/actuator/health" --max-time 3 2>/dev/null || echo "000")
        if [ "$STAGING_HEALTH" = "200" ]; then
            echo "Staging server ready."
            break
        fi
        sleep 2
    done
    if [ "$STAGING_HEALTH" != "200" ]; then
        echo "WARN: Staging server did not start. LFM2 extraction will not work."
        echo "      Start manually: java -jar $KOMPILE_CLI_JAR manage start staging"
    fi
fi

# --- Step 3: Start the application ---
echo ""
echo "=== Step 3/4: Application server ==="
mkdir -p "$PROJECT_DIR/data/logs"
APP_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/actuator/health" --max-time 5 2>/dev/null || echo "000")
if [ "$APP_HEALTH" = "200" ]; then
    echo "Application already running at $BASE_URL"
else
    echo "Starting application..."
    cd "$PROJECT_DIR"
    java $APP_JAVA_OPTS \
        --add-opens=java.base/java.lang=ALL-UNNAMED \
        --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
        --add-opens=java.base/java.lang.reflect=ALL-UNNAMED \
        --add-opens=java.base/java.lang.ref=ALL-UNNAMED \
        --add-opens=java.base/java.io=ALL-UNNAMED \
        --add-opens=java.base/java.net=ALL-UNNAMED \
        --add-opens=java.base/java.nio=ALL-UNNAMED \
        --add-opens=java.base/java.util=ALL-UNNAMED \
        --add-opens=java.base/java.util.concurrent=ALL-UNNAMED \
        --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
        --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
        --add-opens=java.base/sun.nio.cs=ALL-UNNAMED \
        --add-opens=java.base/sun.security.action=ALL-UNNAMED \
        --add-opens=java.base/sun.util.calendar=ALL-UNNAMED \
        --add-opens=java.base/sun.misc=ALL-UNNAMED \
        --add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED \
        --add-exports=java.base/jdk.internal.misc=ALL-UNNAMED \
        -jar target/kompile-fpna-v3-0.1.0-SNAPSHOT.jar \
        > data/logs/app.out.log 2> data/logs/app.err.log &
    APP_PID=$!
    echo "$APP_PID" > data/logs/app.pid
    echo "App PID: $APP_PID"

    echo "Waiting for application startup..."
    for i in $(seq 1 60); do
        APP_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$BASE_URL/actuator/health" --max-time 3 2>/dev/null || echo "000")
        if [ "$APP_HEALTH" = "200" ]; then
            echo "Application ready."
            break
        fi
        sleep 3
    done
    if [ "$APP_HEALTH" != "200" ]; then
        echo "FATAL: Application did not start within 3 minutes. Check data/logs/app.out.log"
        exit 1
    fi
fi

# --- Step 4: Crawl all FP&A data with graph extraction ---
echo ""
echo "=== Step 4/4: Unified crawl with graph extraction ==="
echo "Crawling all documents from: $DATA_DIR"
echo "Schema preset: fpna-cpg-channel-v1"
echo ""

# Extract port from BASE_URL for --port flag
PORT=$(echo "$BASE_URL" | sed -n 's/.*:\([0-9]*\)$/\1/p')
PORT="${PORT:-8080}"

java -jar "$KOMPILE_CLI_JAR" crawl start \
    "$DATA_DIR" \
    --schema-preset fpna-cpg-channel-v1 \
    --graph-schema-mode LENIENT \
    --name "FP&A Workflow v3" \
    --port "$PORT" \
    --watch

echo ""
echo "=============================================="
echo "  FP&A Workflow Console v3 is ready!"
echo "  UI: $BASE_URL"
echo "  API: $BASE_URL/api"
echo "  Graph: $BASE_URL/api/knowledge-graph/nodes"
echo "=============================================="
