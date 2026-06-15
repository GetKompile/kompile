#!/bin/bash
#
# Launcher script for Kompile RAG Application (native image)
#
# Usage:
#   ./kompile-app.sh [options]
#   ./kompile-app.sh --subprocess=ingest   # Run ingest subprocess
#   ./kompile-app.sh --subprocess=vector   # Run vector population subprocess
#
# Environment variables:
#   KOMPILE_HOME      - Base directory (defaults to script parent dir)
#   KOMPILE_DATA_DIR  - Data/index storage directory
#   KOMPILE_MODEL_DIR - Model cache directory
#   JAVA_OPTS         - Additional JVM options (only for JVM mode)
#

set -e

# Resolve KOMPILE_HOME
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KOMPILE_HOME="${KOMPILE_HOME:-$(dirname "$SCRIPT_DIR")}"

# Set up paths
KOMPILE_BIN="${KOMPILE_HOME}/bin"
KOMPILE_CONF="${KOMPILE_HOME}/conf"
KOMPILE_DATA="${KOMPILE_DATA_DIR:-${KOMPILE_HOME}/data}"
KOMPILE_MODELS="${KOMPILE_MODEL_DIR:-${HOME}/.kompile/models}"
KOMPILE_LOGS="${KOMPILE_HOME}/logs"

# Ensure directories exist
mkdir -p "${KOMPILE_DATA}" "${KOMPILE_MODELS}" "${KOMPILE_LOGS}"

# Set native library path for ND4J/JavaCPP
if [ -d "${KOMPILE_HOME}/natives" ]; then
    export LD_LIBRARY_PATH="${KOMPILE_HOME}/natives:${LD_LIBRARY_PATH:-}"
fi

# Set system properties via environment
export KOMPILE_INDEX_PATH="${KOMPILE_DATA}/index"
export KOMPILE_KEYWORD_INDEX_PATH="${KOMPILE_DATA}/keyword-index"

# Check for subprocess mode
SUBPROCESS_TYPE=""
for arg in "$@"; do
    case "$arg" in
        --subprocess=*)
            SUBPROCESS_TYPE="${arg#--subprocess=}"
            ;;
    esac
done

# Select the appropriate executable
if [ -n "$SUBPROCESS_TYPE" ]; then
    case "$SUBPROCESS_TYPE" in
        ingest)
            EXECUTABLE="${KOMPILE_BIN}/kompile-ingest"
            ;;
        vector|vector-population)
            EXECUTABLE="${KOMPILE_BIN}/kompile-vector"
            ;;
        embedding)
            EXECUTABLE="${KOMPILE_BIN}/kompile-embedding"
            ;;
        model-init)
            EXECUTABLE="${KOMPILE_BIN}/kompile-model-init"
            ;;
        *)
            echo "Unknown subprocess type: $SUBPROCESS_TYPE" >&2
            echo "Valid types: ingest, vector, embedding, model-init" >&2
            exit 1
            ;;
    esac

    if [ ! -x "$EXECUTABLE" ]; then
        # Fall back to unified executable with --subprocess flag
        EXECUTABLE="${KOMPILE_BIN}/kompile-app"
    fi
else
    EXECUTABLE="${KOMPILE_BIN}/kompile-app"
fi

if [ ! -x "$EXECUTABLE" ]; then
    echo "Error: Executable not found or not executable: $EXECUTABLE" >&2
    exit 1
fi

# Launch
exec "$EXECUTABLE" \
    -Dspring.config.additional-location="optional:file:${KOMPILE_CONF}/" \
    -Dkompile.data.dir="${KOMPILE_DATA}" \
    -Dkompile.model.cache.path="${KOMPILE_MODELS}" \
    "$@"
