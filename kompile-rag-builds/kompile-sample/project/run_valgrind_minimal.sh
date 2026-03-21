#!/bin/bash

#
# Minimal Valgrind Test - Absolute Bare Minimum Overhead
#
# This script runs Valgrind with the ABSOLUTE MINIMUM overhead possible.
# Tradeoffs:
# - No detailed error tracking (fast)
# - No origin tracking (fast)
# - No verbose mode (fast)
# - Minimal stack traces (fast)
# - Only reports definite leaks (focused)
# - Aggressive suppressions (eliminates 99% of noise)
#
# Use this for: Quick sanity checks during development
#

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Use local wrapper if available, fallback to deeplearning4j platform-tests
if [[ -x "${PROJECT_DIR}/bin/java" ]]; then
    JAVA_WRAPPER="${PROJECT_DIR}/bin/java"
else
    JAVA_WRAPPER="/home/agibsonccc/Documents/GitHub/deeplearning4j/platform-tests/bin/java"
fi

# Try to find the jar file
if [[ -f "${PROJECT_DIR}/target/kompile-sample-0.1.0-SNAPSHOT-exec.jar" ]]; then
    JAR_FILE="${PROJECT_DIR}/target/kompile-sample-0.1.0-SNAPSHOT-exec.jar"
elif [[ -f "${PROJECT_DIR}/target/kompile-sample-0.1.0-SNAPSHOT.jar" ]]; then
    JAR_FILE="${PROJECT_DIR}/target/kompile-sample-0.1.0-SNAPSHOT.jar"
else
    JAR_FILE=""
fi

TIMESTAMP=$(date +%Y%m%d_%H%M%S)
VALGRIND_LOG="${PROJECT_DIR}/valgrind_minimal_${TIMESTAMP}.log"
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

echo "========================================"
echo "MINIMAL Valgrind - Ultra-Fast Mode"
echo "========================================"
echo "Log: $VALGRIND_LOG"
echo ""
echo "Extreme optimizations:"
echo "  ✓ No error details (summary only)"
echo "  ✓ No origin tracking"
echo "  ✓ No verbose output"
echo "  ✓ Minimal stack depth (8 frames)"
echo "  ✓ Only definite leaks reported"
echo "  ✓ No XML, no detailed tracking"
echo ""

# CRITICAL: Disable AVX-512 to avoid crashes
export OPENBLAS_CORETYPE=HASWELL
export OMP_NUM_THREADS=1
export MKL_NUM_THREADS=1

# MINIMAL valgrind configuration - FASTEST POSSIBLE
VALGRIND_CMD="valgrind \
    --leak-check=summary \
    --show-leak-kinds=definite \
    --track-origins=no \
    --log-file=$VALGRIND_LOG \
    --num-callers=8 \
    --suppressions=$SUPPRESSION_FILE \
    --show-reachable=no \
    --undef-value-errors=no \
    --read-inline-info=no \
    --read-var-info=no"

export TEST_RUNNER_PREFIX="$VALGRIND_CMD"

echo "Running with MINIMAL tracking... Ctrl+C to stop"
echo ""

"$JAVA_WRAPPER" -jar "$JAR_FILE"

echo ""
echo "========================================"
echo "Results (Definite Leaks Only)"
echo "========================================"
echo ""

# Extract just the leak summary
if grep -q "LEAK SUMMARY" "$VALGRIND_LOG"; then
    grep -A 2 "LEAK SUMMARY" "$VALGRIND_LOG" | grep "definitely lost" | sed 's/^/  /'
else
    echo "No leak summary found"
fi

echo ""
echo "Full log: $VALGRIND_LOG"
echo ""
