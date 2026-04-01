#!/bin/bash

#
# Lightweight Valgrind Test - Optimized for Java/JNI Applications
#
# This script provides minimal overhead Valgrind configuration specifically
# tuned for Java processes with JNI native code, addressing:
# - Unsupported AVX-512/advanced CPU instructions
# - Massive XML output overhead
# - JVM internal false positives
# - Focus on actual native memory leaks in YOUR code (libnd4jcpu.so)
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
VALGRIND_LOG="${PROJECT_DIR}/valgrind_lightweight_${TIMESTAMP}.log"
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
    echo "Create it first or use run_valgrind_test.sh"
    exit 1
fi

echo "========================================"
echo "Lightweight Valgrind Memory Check"
echo "========================================"
echo "Log: $VALGRIND_LOG"
echo "Suppressions: $SUPPRESSION_FILE"
echo ""
echo "Optimizations enabled:"
echo "  ✓ Reduced tracking (no origin tracking)"
echo "  ✓ No XML output (massive overhead savings)"
echo "  ✓ Aggressive JVM/system library suppressions"
echo "  ✓ Focus on native code leaks only"
echo "  ✓ Summary mode (faster execution)"
echo ""

# CRITICAL: Disable AVX-512 to avoid unsupported instruction crashes
# Valgrind doesn't support many AVX-512 instructions yet
export OPENBLAS_CORETYPE=HASWELL  # Force Haswell (AVX2 max) instead of Skylake/Cascade Lake (AVX-512)
export OMP_NUM_THREADS=1          # Single-threaded to reduce complexity
export MKL_NUM_THREADS=1          # Same for MKL if present

echo "CPU instruction set limited to AVX2 (avoiding AVX-512 crashes)"
echo ""

# Lightweight valgrind options focused on actual leaks in YOUR code
VALGRIND_CMD="valgrind \
    --leak-check=summary \
    --show-leak-kinds=definite,possible \
    --track-origins=no \
    --log-file=$VALGRIND_LOG \
    --num-callers=12 \
    --suppressions=$SUPPRESSION_FILE \
    --error-limit=no \
    --show-reachable=no"

export TEST_RUNNER_PREFIX="$VALGRIND_CMD"

echo "Running... Press Ctrl+C to stop and analyze."
echo ""

"$JAVA_WRAPPER" -jar "$JAR_FILE"

echo ""
echo "========================================"
echo "Analysis Results"
echo "========================================"
echo ""

# Extract leak summary
if grep -q "LEAK SUMMARY" "$VALGRIND_LOG"; then
    echo "Leak Summary:"
    grep -A 6 "LEAK SUMMARY" "$VALGRIND_LOG" | sed 's/^/  /'
else
    echo "No leak summary found (may have crashed before completion)"
fi

echo ""
echo "========================================"
echo "Key Findings"
echo "========================================"
echo ""

# Count definite leaks
DEFINITE_LOST=$(grep "definitely lost:" "$VALGRIND_LOG" | tail -1 || echo "")
if [[ -n "$DEFINITE_LOST" ]]; then
    echo "Definite leaks (YOUR code):  $DEFINITE_LOST"
else
    echo "Definite leaks: Not found in summary"
fi

# Check for crashes
if grep -q "unhandled instruction\|Unrecognised instruction" "$VALGRIND_LOG"; then
    echo ""
    echo "⚠️  WARNING: Valgrind crashed on unsupported CPU instructions!"
    echo "    This is likely AVX-512 or newer instructions."
    echo "    Try running with: export OPENBLAS_CORETYPE=HASWELL"
    echo "    (This was already set, but may need kernel-level enforcement)"
    echo ""
    echo "    Alternative: Rebuild OpenBLAS/libnd4j with -march=haswell instead of -march=native"
fi

echo ""
echo "Full log: $VALGRIND_LOG"
echo ""

# Provide quick analysis commands
echo "========================================"
echo "Quick Analysis Commands"
echo "========================================"
echo ""
echo "# View all definite leaks:"
echo "grep -A 20 'definitely lost' $VALGRIND_LOG"
echo ""
echo "# View all possible leaks:"
echo "grep -A 20 'possibly lost' $VALGRIND_LOG"
echo ""
echo "# Count leaks by library:"
echo "grep -B 15 'definitely lost' $VALGRIND_LOG | grep 'obj:' | sort | uniq -c | sort -rn"
echo ""
echo "# Focus on YOUR code (libnd4jcpu.so):"
echo "grep -B 15 'definitely lost' $VALGRIND_LOG | grep -A 10 'libnd4jcpu.so'"
echo ""
