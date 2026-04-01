#!/bin/bash
#
# Start script for Kompile Sample with custom port, database paths, and debug tools
#
# Usage:
#   ./start.sh                                    # Use defaults
#   ./start.sh --port 9000                        # Custom port
#   ./start.sh --db-path /path/to/h2db            # Custom H2 database path
#   ./start.sh --index-path /path/to/index        # Custom vector index path
#   ./start.sh --debug compute-sanitizer          # Run with CUDA compute-sanitizer
#   ./start.sh --debug cuda-gdb                   # Run with cuda-gdb
#   ./start.sh --debug valgrind                   # Run with valgrind
#   ./start.sh --port 9000 --debug compute-sanitizer  # Multiple options
#
# Debug modes:
#   compute-sanitizer      - CUDA memory checker
#   compute-sanitizer-race - CUDA race condition checker
#   cuda-gdb               - CUDA debugger (interactive)
#   valgrind               - Full valgrind leak check
#   valgrind-minimal       - Fast valgrind check
#
# Environment variables (alternative to flags):
#   KOMPILE_PORT       - Server port (default: 9000)
#   KOMPILE_DB_PATH    - H2 database file path (default: ./data/custom-db)
#   KOMPILE_INDEX_PATH - Anserini vector store index path
#

set -e

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Defaults
PORT="${KOMPILE_PORT:-9000}"
DB_PATH="${KOMPILE_DB_PATH:-${PROJECT_DIR}/data/custom-db}"
INDEX_PATH="${KOMPILE_INDEX_PATH:-${PROJECT_DIR}/data/custom-index}"
DEBUG_MODE=""
RUNNER_PREFIX=""

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --port|-p)
            PORT="$2"
            shift 2
            ;;
        --db-path|-d)
            DB_PATH="$2"
            shift 2
            ;;
        --index-path|-i)
            INDEX_PATH="$2"
            shift 2
            ;;
        --debug)
            DEBUG_MODE="$2"
            shift 2
            ;;
        --help|-h)
            echo "Usage: $0 [OPTIONS]"
            echo ""
            echo "Options:"
            echo "  --port, -p PORT          Server port (default: 9000)"
            echo "  --db-path, -d PATH       H2 database file path (default: ./data/custom-db)"
            echo "  --index-path, -i PATH    Vector store index path (default: ./data/custom-index)"
            echo "  --debug MODE             Debug mode (see below)"
            echo "  --help, -h               Show this help message"
            echo ""
            echo "Debug modes:"
            echo "  compute-sanitizer        CUDA memory checker (memcheck)"
            echo "  compute-sanitizer-race   CUDA race condition checker (racecheck)"
            echo "  compute-sanitizer-init   CUDA initcheck (uninitialized memory)"
            echo "  compute-sanitizer-sync   CUDA synccheck (synchronization errors)"
            echo "  cuda-gdb                 CUDA debugger (interactive)"
            echo "  valgrind                 Full valgrind leak check"
            echo "  valgrind-minimal         Minimal valgrind (faster)"
            echo ""
            echo "Examples:"
            echo "  $0 --port 9000 --debug compute-sanitizer"
            echo "  $0 --debug valgrind"
            exit 0
            ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage information"
            exit 1
            ;;
    esac
done

# Find the JAR file
JAR_FILE="${PROJECT_DIR}/target/kompile-sample-0.1.0-SNAPSHOT.jar"
if [[ ! -f "$JAR_FILE" ]]; then
    echo "ERROR: JAR file not found at: $JAR_FILE"
    echo "Run 'mvn package' first to build the application."
    exit 1
fi

# Create directories if they don't exist
mkdir -p "$(dirname "$DB_PATH")"
mkdir -p "$INDEX_PATH"

# Build the H2 JDBC URL
H2_URL="jdbc:h2:file:${DB_PATH};DB_CLOSE_ON_EXIT=FALSE;AUTO_RECONNECT=TRUE"

# Setup debug mode
TIMESTAMP=$(date +%Y%m%d_%H%M%S)

case "$DEBUG_MODE" in
    compute-sanitizer)
        LOG_FILE="${PROJECT_DIR}/compute_sanitizer_${TIMESTAMP}.log"
        RUNNER_PREFIX="compute-sanitizer --tool memcheck --log-file ${LOG_FILE}"
        echo "========================================"
        echo "CUDA compute-sanitizer (memcheck)"
        echo "Log: $LOG_FILE"
        echo "========================================"
        ;;
    compute-sanitizer-race)
        LOG_FILE="${PROJECT_DIR}/racecheck_${TIMESTAMP}.log"
        RUNNER_PREFIX="compute-sanitizer --tool racecheck --log-file ${LOG_FILE}"
        echo "========================================"
        echo "CUDA compute-sanitizer (racecheck)"
        echo "Log: $LOG_FILE"
        echo "========================================"
        ;;
    compute-sanitizer-init)
        LOG_FILE="${PROJECT_DIR}/initcheck_${TIMESTAMP}.log"
        RUNNER_PREFIX="compute-sanitizer --tool initcheck --log-file ${LOG_FILE}"
        echo "========================================"
        echo "CUDA compute-sanitizer (initcheck)"
        echo "Log: $LOG_FILE"
        echo "========================================"
        ;;
    compute-sanitizer-sync)
        LOG_FILE="${PROJECT_DIR}/synccheck_${TIMESTAMP}.log"
        RUNNER_PREFIX="compute-sanitizer --tool synccheck --log-file ${LOG_FILE}"
        echo "========================================"
        echo "CUDA compute-sanitizer (synccheck)"
        echo "Log: $LOG_FILE"
        echo "========================================"
        ;;
    cuda-gdb)
        RUNNER_PREFIX="cuda-gdb --args"
        echo "========================================"
        echo "CUDA Debugger (cuda-gdb)"
        echo "Interactive mode enabled"
        echo "========================================"
        ;;
    malloc-check)
        export MALLOC_CHECK_=3
        export LIBC_FATAL_STDERR_=1
        echo "========================================"
        echo "MALLOC_CHECK_=3 (glibc heap checking)"
        echo "Will abort on heap corruption"
        echo "========================================"
        ;;
    asan)
        # Need libnd4j built with ASAN for this to work fully
        export ASAN_OPTIONS="detect_leaks=1:halt_on_error=0:print_stats=1:log_path=${PROJECT_DIR}/asan_${TIMESTAMP}.log"
        export LD_PRELOAD="/usr/lib64/libasan.so.8"
        echo "========================================"
        echo "AddressSanitizer"
        echo "Log: ${PROJECT_DIR}/asan_${TIMESTAMP}.log"
        echo "========================================"
        ;;
    efence)
        export LD_PRELOAD="/usr/lib64/libefence.so"
        export EF_PROTECT_BELOW=0
        export EF_PROTECT_FREE=1
        export EF_ALLOW_MALLOC_0=1
        echo "========================================"
        echo "Electric Fence (use-after-free detection)"
        echo "========================================"
        ;;
    valgrind)
        LOG_FILE="${PROJECT_DIR}/valgrind_${TIMESTAMP}.log"
        RUNNER_PREFIX="valgrind --leak-check=full --show-leak-kinds=all --track-origins=yes --log-file=${LOG_FILE}"
        echo "========================================"
        echo "Valgrind (full leak check)"
        echo "Log: $LOG_FILE"
        echo "========================================"
        ;;
    valgrind-minimal)
        LOG_FILE="${PROJECT_DIR}/valgrind_minimal_${TIMESTAMP}.log"
        RUNNER_PREFIX="valgrind --leak-check=summary --show-leak-kinds=definite --track-origins=no --log-file=${LOG_FILE}"
        echo "========================================"
        echo "Valgrind (minimal)"
        echo "Log: $LOG_FILE"
        echo "========================================"
        ;;
    "")
        # No debug mode
        ;;
    *)
        echo "Unknown debug mode: $DEBUG_MODE"
        echo "Use --help for available modes"
        exit 1
        ;;
esac

echo "========================================"
echo "Starting Kompile Sample"
echo "========================================"
echo "Port:        $PORT"
echo "H2 Database: $DB_PATH"
echo "Index Path:  $INDEX_PATH"
echo "JAR:         $JAR_FILE"
if [[ -n "$DEBUG_MODE" ]]; then
    echo "Debug Mode:  $DEBUG_MODE"
fi
echo "========================================"
echo ""

# Common JVM args
JVM_ARGS=(
  -XX:+ExtensiveErrorReports
  -XX:+UnlockDiagnosticVMOptions
  -XX:NativeMemoryTracking=detail
 -Djava.compiler=NONE
  -verbose:jni
   -Xmx4g
    --add-opens java.base/java.lang=ALL-UNNAMED
    --add-opens java.base/java.util=ALL-UNNAMED
    --add-opens java.base/java.nio=ALL-UNNAMED
)

# App args
APP_ARGS=(
    --server.port="$PORT"

    --kompile.vectorstore.anserini.index-path="$INDEX_PATH"
)

# Run the application
if [[ -n "$RUNNER_PREFIX" ]]; then
    exec $RUNNER_PREFIX java "${JVM_ARGS[@]}" -jar "$JAR_FILE" "${APP_ARGS[@]}"
else
    exec java "${JVM_ARGS[@]}" -jar "$JAR_FILE" "${APP_ARGS[@]}"
fi
