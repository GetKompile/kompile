#!/bin/bash
# dev-restart.sh — Clean restart of kompile app-main (+ subprocess)
# Usage: ./dev-restart.sh [--rebuild]

set -e

PORTS="8080 8090 8091"
MVN=/home/agibsonccc/dev-apps/mvn/bin/mvn
APP_DIR=/home/agibsonccc/Documents/GitHub/kompile/kompile-app/kompile-app-main
LOG=/tmp/app-main.log
REBUILD_LOG=/tmp/kompile-rebuild.log

# Kill everything on our ports
for port in $PORTS; do
    pids=$(lsof -i :$port -t 2>/dev/null || true)
    if [ -n "$pids" ]; then
        echo "Killing PIDs on port $port: $pids"
        kill -9 $pids 2>/dev/null || true
    fi
done
sleep 2

# Verify ports are free
for port in $PORTS; do
    if lsof -i :$port -t >/dev/null 2>&1; then
        echo "ERROR: Port $port still in use after kill"
        lsof -i :$port 2>/dev/null
        exit 1
    fi
done
echo "All ports clear."

# Optional rebuild
if [ "$1" = "--rebuild" ]; then
    echo "Rebuilding kompile modules..."
    cd /home/agibsonccc/Documents/GitHub/kompile
    $MVN install -DskipTests -Dskip.ui \
        -pl kompile-pipelines-framework/kompile-pipeline-steps-parent/kompile-pipelines-steps-samediff,kompile-app/kompile-pipelines-app-llm,kompile-app/kompile-app-main \
        2>&1 | tee "$REBUILD_LOG"
    BUILD_EXIT=$?
    if [ $BUILD_EXIT -ne 0 ]; then
        echo "ERROR: Maven build failed (exit $BUILD_EXIT)"
        echo "Full log: $REBUILD_LOG"
        tail -30 "$REBUILD_LOG"
        exit 1
    fi
    if grep -q "BUILD FAILURE" "$REBUILD_LOG"; then
        echo "ERROR: Maven BUILD FAILURE detected"
        grep -A 10 "BUILD FAILURE" "$REBUILD_LOG"
        exit 1
    fi
    echo "Rebuild done (BUILD SUCCESS)."
fi

# Start app-main
echo "Starting app-main..."
cd "$APP_DIR"
$MVN spring-boot:run -Dskip.ui -Dspring-boot.run.jvmArguments="-Xmx4g" > "$LOG" 2>&1 &
APP_PID=$!
echo "app-main PID: $APP_PID, log: $LOG"

# Wait for startup
echo "Waiting for app-main health check..."
for i in $(seq 1 60); do
    # Check if process died
    if ! kill -0 $APP_PID 2>/dev/null; then
        echo "ERROR: app-main process died (PID $APP_PID)"
        echo "--- Last 30 lines of log ---"
        tail -30 "$LOG"
        exit 1
    fi
    if curl -sf http://localhost:8080/actuator/health 2>/dev/null | grep -q UP; then
        echo "app-main UP on :8080 after $((i*2))s"
        # Also check staging
        if curl -sf http://localhost:8090/api/llm/status >/dev/null 2>&1; then
            echo "model-staging UP on :8090"
        else
            echo "WARNING: model-staging not yet responding on :8090"
        fi
        echo "READY"
        exit 0
    fi
    sleep 2
done
echo "ERROR: app-main did not start within 120s"
echo "--- Last 30 lines of log ---"
tail -30 "$LOG"
exit 1
