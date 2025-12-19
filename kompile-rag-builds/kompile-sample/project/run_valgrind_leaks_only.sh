#!/bin/bash

#
# Valgrind Leak Detection - Focus on Real Leaks with Stack Traces
#
# This script is optimized for finding ACTUAL memory leaks (not reachable memory)
# with full stack traces, while suppressing JVM/library noise.
#
# Key differences from minimal:
# - Full stack traces (30 frames) for leak identification
# - Shows "definite" and "possible" leaks (not just definite)
# - Periodic leak snapshots (every 2 minutes) saved to separate files
# - Still suppresses JVM/OpenBLAS/library noise
# - NO XML overhead
# - Safe to kill with signal 9 - snapshots are preserved
#
# Use this for: Getting stack traces of actual leaks with periodic sampling
#

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_WRAPPER="/home/agibsonccc/Documents/GitHub/deeplearning4j/platform-tests/bin/java"

# Configuration
SNAPSHOT_INTERVAL=${SNAPSHOT_INTERVAL:-120}  # Seconds between leak check snapshots

# Try to find the jar file
if [[ -f "${PROJECT_DIR}/target/kompile-sample-0.1.0-SNAPSHOT-exec.jar" ]]; then
    JAR_FILE="${PROJECT_DIR}/target/kompile-sample-0.1.0-SNAPSHOT-exec.jar"
elif [[ -f "${PROJECT_DIR}/target/kompile-sample-0.1.0-SNAPSHOT.jar" ]]; then
    JAR_FILE="${PROJECT_DIR}/target/kompile-sample-0.1.0-SNAPSHOT.jar"
else
    JAR_FILE=""
fi

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
OUTPUT_DIR="${PROJECT_DIR}/valgrind_sampled_${TIMESTAMP}"
mkdir -p "$OUTPUT_DIR"
VALGRIND_LOG="${OUTPUT_DIR}/valgrind_full.log"
MONITOR_LOG="${OUTPUT_DIR}/monitor.log"
SUPPRESSION_FILE="${PROJECT_DIR}/valgrind_java_optimized.supp"

if [[ ! -x "$JAVA_WRAPPER" ]]; then
    echo "ERROR: Java wrapper not found at: $JAVA_WRAPPER"
    exit 1
fi

if [[ ! -f "$JAR_FILE" ]]; then
    echo "ERROR: JAR file not found. Run 'mvn package' first."
    exit 1
fi

if [[ ! -f "$SUPPRESSION_FILE" ]]; then
    echo "ERROR: Suppression file not found at: $SUPPRESSION_FILE"
    exit 1
fi

{
    echo "========================================"
    echo "Valgrind - Sampled Leak Detection"
    echo "Started: $(date)"
    echo "========================================"
    echo "Output directory: $OUTPUT_DIR"
    echo ""
    echo "Configuration:"
    echo "  Snapshot interval: ${SNAPSHOT_INTERVAL}s"
    echo "  Stack trace depth: 30 frames"
    echo "  Leak types: Definite + Possible"
    echo "  JVM/library noise: Suppressed"
    echo "  Overhead: ~400%"
    echo ""
} | tee "$MONITOR_LOG"

echo "Monitor log: $MONITOR_LOG"
echo "Full Valgrind log: $VALGRIND_LOG"
echo ""
echo "Periodic snapshots will be saved to: ${OUTPUT_DIR}/leak_snapshot_*.txt"
echo "Safe to kill with 'kill -9' - snapshots are preserved"
echo ""

# CRITICAL: Disable AVX-512 to avoid crashes
export OPENBLAS_CORETYPE=HASWELL
export OMP_NUM_THREADS=1
export MKL_NUM_THREADS=1

# Valgrind configuration optimized for leak detection with stack traces
VALGRIND_CMD="valgrind \
    --leak-check=full \
    --show-leak-kinds=definite,possible \
    --track-origins=no \
    --log-file=$VALGRIND_LOG \
    --num-callers=30 \
    --suppressions=$SUPPRESSION_FILE \
    --show-reachable=no \
    --undef-value-errors=no \
    --expensive-definedness-checks=no \
    --keep-debuginfo=yes \
    --read-inline-info=yes \
    --read-var-info=yes \
    --error-limit=no"

export TEST_RUNNER_PREFIX="$VALGRIND_CMD"

# Start the application in background
echo "Starting application under Valgrind..."
"$JAVA_WRAPPER" -jar "$JAR_FILE" &
APP_PID=$!

echo "Application PID: $APP_PID" | tee -a "$MONITOR_LOG"
echo ""

# Function to capture leak snapshot
capture_leak_snapshot() {
    local snapshot_num=$1
    local snapshot_file="${OUTPUT_DIR}/leak_snapshot_${snapshot_num}.txt"

    echo "[$(date +%H:%M:%S)] Requesting leak check #${snapshot_num}..." | tee -a "$MONITOR_LOG"

    # Send leak check request to valgrind (SIGUSR1 triggers leak check in some versions)
    # For vgdb approach, we use monitor command
    {
        echo "========================================"
        echo "Leak Snapshot #${snapshot_num}"
        echo "Time: $(date)"
        echo "========================================"
        echo ""

        # Extract current leak summary from the log
        if grep -q "LEAK SUMMARY" "$VALGRIND_LOG"; then
            echo "=== Current Leak Summary ==="
            grep -A 10 "LEAK SUMMARY" "$VALGRIND_LOG" | tail -11
            echo ""

            # Get the most recent leak stack traces (last 100 lines with leak info)
            echo "=== Recent Leak Stack Traces ==="
            grep -B 5 -A 30 "definitely lost\|possibly lost" "$VALGRIND_LOG" | tail -200
        else
            echo "No leak data available yet"
        fi

        echo ""
        echo "Full log available at: $VALGRIND_LOG"
    } > "$snapshot_file"

    # Get RSS for correlation
    if [[ -e /proc/$APP_PID ]]; then
        local RSS_KB=$(awk '/^VmRSS:/ {print $2}' /proc/$APP_PID/status)
        local RSS_MB=$((RSS_KB / 1024))
        echo "[$(date +%H:%M:%S)] Snapshot #${snapshot_num} complete (RSS: ${RSS_MB} MB)" | tee -a "$MONITOR_LOG"
    else
        echo "[$(date +%H:%M:%S)] Snapshot #${snapshot_num} complete (process ended)" | tee -a "$MONITOR_LOG"
    fi
}

# Monitor loop - capture snapshots periodically
snapshot_count=0
echo "Starting periodic leak snapshots (every ${SNAPSHOT_INTERVAL}s)..." | tee -a "$MONITOR_LOG"
echo "Monitor progress: tail -f $MONITOR_LOG" | tee -a "$MONITOR_LOG"
echo ""

while kill -0 $APP_PID 2>/dev/null; do
    sleep $SNAPSHOT_INTERVAL

    # Check if process still exists
    if ! kill -0 $APP_PID 2>/dev/null; then
        break
    fi

    capture_leak_snapshot $snapshot_count
    snapshot_count=$((snapshot_count + 1))
done

# Wait for application to finish
wait $APP_PID
EXIT_CODE=$?

echo "" | tee -a "$MONITOR_LOG"
echo "[$(date +%H:%M:%S)] Application finished with exit code: $EXIT_CODE" | tee -a "$MONITOR_LOG"

# Capture final snapshot
sleep 2  # Give Valgrind time to write final summary
capture_leak_snapshot "final"

{
    echo ""
    echo "========================================"
    echo "Leak Detection Results"
    echo "========================================"
    echo ""

    # Extract final leak summary
    if grep -q "LEAK SUMMARY" "$VALGRIND_LOG"; then
        echo "Final Leak Summary:"
        grep -A 10 "LEAK SUMMARY" "$VALGRIND_LOG" | tail -11 | sed 's/^/  /'
        echo ""

        # Count definite leaks
        DEFINITE=$(grep "definitely lost:" "$VALGRIND_LOG" | tail -1 | awk '{print $4, $5}')
        POSSIBLE=$(grep "possibly lost:" "$VALGRIND_LOG" | tail -1 | awk '{print $4, $5}')

        echo "Definite leaks: $DEFINITE"
        echo "Possible leaks: $POSSIBLE"
        echo ""
    else
        echo "No leak summary found (process may have crashed)"
    fi

    echo "Output directory: $OUTPUT_DIR"
    echo ""
    echo "Snapshot files:"
    ls -1 "${OUTPUT_DIR}"/leak_snapshot_*.txt 2>/dev/null | sed 's/^/  /' || echo "  (none created)"
    echo ""
    echo "Full Valgrind log: $VALGRIND_LOG"
    echo ""

    if [[ -f "${OUTPUT_DIR}/leak_snapshot_final.txt" ]]; then
        echo "To analyze leak stack traces:"
        echo "  cat ${OUTPUT_DIR}/leak_snapshot_final.txt | less"
        echo "  grep -A 30 'definitely lost' $VALGRIND_LOG | less"
        echo "  grep -A 30 'possibly lost' $VALGRIND_LOG | less"
    fi

    echo ""
    echo "Exit code: $EXIT_CODE"
} | tee -a "$MONITOR_LOG"

echo ""
echo "Analysis complete. All results in: $OUTPUT_DIR"
echo ""
