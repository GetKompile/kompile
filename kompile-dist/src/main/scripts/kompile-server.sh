#!/usr/bin/env bash
#
#   Copyright 2025 Kompile Inc.
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#   distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
# limitations under the License.
#
# Launcher for kompile-server (RAG application — native image).
#
# Sets up library paths and passes the distribution's conf/ location. The admin
# server defaults to port 8080 and follows the same CLI convention as the other
# distributable components: `--port <N>` overrides KOMPILE_SERVER_PORT. The
# legacy `--server.port=<N>` spelling remains accepted for compatibility.
#
# Usage:
#   bin/kompile-server.sh
#   bin/kompile-server.sh --port 9090
#   KOMPILE_SERVER_PORT=9090 bin/kompile-server.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || echo "${BASH_SOURCE[0]}")")" && pwd)"
DIST_HOME="$(cd "${SCRIPT_DIR}/.." && pwd)"

# ── Java resolution (jar-tier fallback only; native binary is preferred below) ──
# Order: $KOMPILE_JAVA → <dist>/runtime/bin/java → $JAVA_HOME/bin/java → java on PATH
resolve_java() {
    if [ -x "${KOMPILE_JAVA:-}" ]; then
        echo "${KOMPILE_JAVA}"
        return
    fi
    if [ -x "${DIST_HOME}/runtime/bin/java" ]; then
        echo "${DIST_HOME}/runtime/bin/java"
        return
    fi
    if [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
        echo "${JAVA_HOME}/bin/java"
        return
    fi
    if command -v java >/dev/null 2>&1; then
        echo "java"
        return
    fi
    echo "error: no Java runtime found. Install Java 21+, set JAVA_HOME, or rebuild the dist" \
         "with a bundled runtime (see build-dist.sh --variant <variant>)." >&2
    exit 1
}

BINARY="${DIST_HOME}/bin/kompile-server"
JAR="${DIST_HOME}/lib/kompile-server.jar"

# Determine port: CLI flag > env var > default 8080. Normalize both the Kompile
# `--port` spelling and the legacy Spring spelling into one server.port argument.
PORT="${KOMPILE_SERVER_PORT:-8080}"
PASSTHROUGH_ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --port)
            if [ -z "${2:-}" ]; then
                echo "error: --port requires a value" >&2
                exit 1
            fi
            PORT="$2"
            shift 2
            ;;
        --port=*)
            PORT="${1#--port=}"
            shift
            ;;
        --server.port=*)
            PORT="${1#--server.port=}"
            shift
            ;;
        *)
            PASSTHROUGH_ARGS+=("$1")
            shift
            ;;
    esac
done

export LD_LIBRARY_PATH="${DIST_HOME}/bin:${DIST_HOME}/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
export DYLD_LIBRARY_PATH="${DIST_HOME}/bin:${DIST_HOME}/lib${DYLD_LIBRARY_PATH:+:${DYLD_LIBRARY_PATH}}"

# Prefer native binary.
if [ -x "${BINARY}" ]; then
    exec "${BINARY}" \
        -Dkompile.dist.home="${DIST_HOME}" \
        -Dspring.config.additional-location="optional:file:${DIST_HOME}/conf/" \
        "--server.port=${PORT}" \
        "${PASSTHROUGH_ARGS[@]}"
fi

# Fall back to exec JAR under the resolved Java runtime.
if [ ! -f "${JAR}" ]; then
    echo "error: neither native binary ${BINARY} nor exec JAR ${JAR} found" >&2
    exit 1
fi

JAVA_BIN="$(resolve_java)"
KOMPILE_SERVER_HEAP="${KOMPILE_SERVER_HEAP:--Xmx4g}"

exec "${JAVA_BIN}" \
    ${KOMPILE_SERVER_HEAP} \
    -Djava.library.path="${DIST_HOME}/bin:${DIST_HOME}/lib" \
    -Dkompile.dist.home="${DIST_HOME}" \
    -Dspring.config.additional-location="optional:file:${DIST_HOME}/conf/" \
    -jar "${JAR}" \
    "--server.port=${PORT}" \
    "${PASSTHROUGH_ARGS[@]}"
