#!/bin/bash

#
# Debug Runner Script for Kompile Sample
#
# This script runs the Spring Boot application with various debugging tools
# using the custom Java wrapper from platform-tests.
#
# Usage:
#   ./run-debug.sh                           # Normal run
#   ./run-debug.sh valgrind                  # Run with valgrind
#   ./run-debug.sh valgrind-minimal          # Run with minimal valgrind (faster)
#   ./run-debug.sh asan                      # Run with AddressSanitizer
#   ./run-debug.sh compute-sanitizer         # Run with CUDA compute-sanitizer
#   ./run-debug.sh compute-sanitizer-race    # Run with CUDA racecheck
#   ./run-debug.sh jemalloc                  # Run with jemalloc profiling
#

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAVA_WRAPPER="${PROJECT_DIR}/bin/java"

# Find the JAR file
if [[ -f "${PROJECT_DIR}/target/kompile-sample-0.1.0-SNAPSHOT.jar" ]]; then
    JAR_FILE="${PROJECT_DIR}/target/kompile-sample-0.1.0-SNAPSHOT.jar"
else
    echo "ERROR: JAR file not found. Run 'mvn package' first."
    exit 1
fi

if [[ ! -x "$JAVA_WRAPPER" ]]; then
    echo "ERROR: Java wrapper not found or not executable at: $JAVA_WRAPPER"
    echo "Make sure bin/java exists and is executable"
    exit 1
fi

# Default: no special runner
export TEST_RUNNER_PREFIX=""
export OPENBLAS_CORETYPE=HASWELL
export OMP_NUM_THREADS=1
export MKL_NUM_THREADS=1

MODE="${1:-normal}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

case "$MODE" in
    valgrind)
        echo "========================================"
        echo "Running with Valgrind (full leak check)"
        echo "========================================"
        LOG_FILE="${PROJECT_DIR}/valgrind_${TIMESTAMP}.log"
        export TEST_RUNNER_PREFIX="valgrind --leak-check=full --show-leak-kinds=all --log-file=${LOG_FILE}"
        echo "Log file: $LOG_FILE"
        ;;
    valgrind-minimal)
        echo "========================================"
        echo "Running with Valgrind (minimal/fast)"
        echo "========================================"
        LOG_FILE="${PROJECT_DIR}/valgrind_minimal_${TIMESTAMP}.log"
        export TEST_RUNNER_PREFIX="valgrind --leak-check=summary --show-leak-kinds=definite --track-origins=no --log-file=${LOG_FILE}"
        echo "Log file: $LOG_FILE"
        ;;
    asan)
        echo "========================================"
        echo "Running with AddressSanitizer"
        echo "========================================"
        export TEST_RUNNER_PREFIX="asan"
        echo "ASAN logs will be at: /tmp/asan.log.*"
        ;;
    compute-sanitizer)
        echo "========================================"
        echo "Running with CUDA compute-sanitizer"
        echo "========================================"
        LOG_FILE="${PROJECT_DIR}/compute_sanitizer_${TIMESTAMP}.log"
        export TEST_RUNNER_PREFIX="compute-sanitizer --tool memcheck --log-file ${LOG_FILE}"
        echo "Log file: $LOG_FILE"
        ;;
    compute-sanitizer-race)
        echo "========================================"
        echo "Running with CUDA racecheck"
        echo "========================================"
        LOG_FILE="${PROJECT_DIR}/racecheck_${TIMESTAMP}.log"
        export TEST_RUNNER_PREFIX="compute-sanitizer --tool racecheck --log-file ${LOG_FILE}"
        echo "Log file: $LOG_FILE"
        ;;
    jemalloc)
        echo "========================================"
        echo "Running with jemalloc profiling"
        echo "========================================"
        if [[ -z "$JEMALLOC_PATH" ]]; then
            # Try to find jemalloc
            JEMALLOC_PATH=$(find /usr/lib* /usr/local/lib* -name "libjemalloc.so*" 2>/dev/null | head -n 1)
        fi
        if [[ -z "$JEMALLOC_PATH" ]]; then
            echo "ERROR: jemalloc not found. Set JEMALLOC_PATH or install jemalloc."
            exit 1
        fi
        export LD_PRELOAD="$JEMALLOC_PATH"
        export MALLOC_CONF="prof_leak:true,lg_prof_sample:0,prof_final:true"
        echo "Using jemalloc: $JEMALLOC_PATH"
        ;;
    normal|"")
        echo "========================================"
        echo "Running normally (no debug tools)"
        echo "========================================"
        ;;
    *)
        echo "Unknown mode: $MODE"
        echo ""
        echo "Usage: $0 [mode]"
        echo ""
        echo "Modes:"
        echo "  normal               - Normal run (default)"
        echo "  valgrind             - Run with valgrind full leak check"
        echo "  valgrind-minimal     - Run with valgrind minimal (faster)"
        echo "  asan                 - Run with AddressSanitizer"
        echo "  compute-sanitizer    - Run with CUDA compute-sanitizer memcheck"
        echo "  compute-sanitizer-race - Run with CUDA racecheck"
        echo "  jemalloc             - Run with jemalloc memory profiling"
        exit 1
        ;;
esac

echo ""
echo "JAR: $JAR_FILE"
echo "Wrapper: $JAVA_WRAPPER"
echo ""

# Run with the wrapper
"$JAVA_WRAPPER" -jar "$JAR_FILE"
