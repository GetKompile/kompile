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
# Launcher for kompile-app-crawl-manager, the end-user crawl app (crawls, ingest and
# indexing, fact sheets, documents and index browser, note sync, graph viewing).
#
# This is one of three processes. kompile-app-main is the admin console on :8080 and
# does not mount the crawl API at all, so this process is the only way to run a crawl
# from a dist. See docs/architecture/app-persona-boundary.md.
#
# Behavior:
#   - Resolves the bundle root via the script location.
#   - Prefers the GraalVM native binary at bin/kompile-crawl-manager if present, otherwise
#     falls back to running the exec JAR at lib/kompile-crawl-manager.jar under the JVM.
#   - Defaults to port 8082, but accepts overrides via the KOMPILE_CRAWL_PORT environment
#     variable or a `--port <N>` command-line flag (the flag takes precedence). Any other
#     arguments are forwarded verbatim.
#
# The env vars are KOMPILE_CRAWL_* rather than KOMPILE_CRAWL_MANAGER_*: they match the
# service id `crawl` used everywhere else — the server.port placeholder in the app's
# application.yml, KompileService.CRAWL in the CLI router, and the crawlHeap key in
# project-runtime.json. Only the artifact and script are named "crawl-manager".
#
# Usage:
#   bin/kompile-crawl-manager.sh
#   bin/kompile-crawl-manager.sh --port 9082
#   KOMPILE_CRAWL_PORT=9082 bin/kompile-crawl-manager.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || echo "${BASH_SOURCE[0]}")")" && pwd)"
DIST_HOME="$(cd "${SCRIPT_DIR}/.." && pwd)"

NATIVE_BIN="${DIST_HOME}/bin/kompile-crawl-manager"
JAR="${DIST_HOME}/lib/kompile-crawl-manager.jar"

# Determine port: CLI flag > env var > default 8082.
PORT="${KOMPILE_CRAWL_PORT:-8082}"
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
        *)
            PASSTHROUGH_ARGS+=("$1")
            shift
            ;;
    esac
done

# Prepend dist bin/ and lib/ so the JVM/native image can locate libkompile_pipelines and
# friends at runtime. bin/ matters for the native path specifically: the GraalVM-emitted JDK
# shims (libawt.so, libjava.so, ...) ship beside the binary, and without bin/ on the path the
# image starts and then dies on the first shim it cannot dlopen. Same pairing as
# kompile-server.sh — the two launchers have to agree or the personas fail where admin works.
export LD_LIBRARY_PATH="${DIST_HOME}/bin:${DIST_HOME}/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
export DYLD_LIBRARY_PATH="${DIST_HOME}/bin:${DIST_HOME}/lib${DYLD_LIBRARY_PATH:+:${DYLD_LIBRARY_PATH}}"

# Heap default (only used in the JVM fallback path). Larger than chat's because this is
# the process that runs extraction and bulk indexing. Accepts either a bare size ("8g",
# which is the form project-runtime.json's crawlHeap key uses) or a full JVM flag
# ("-Xmx8g"), because both spellings are in circulation and `java 8g` is a confusing
# failure.
KOMPILE_CRAWL_HEAP="${KOMPILE_CRAWL_HEAP:--Xmx8g}"
case "${KOMPILE_CRAWL_HEAP}" in
    -*) ;;
    *) KOMPILE_CRAWL_HEAP="-Xmx${KOMPILE_CRAWL_HEAP}" ;;
esac

if [ -x "${NATIVE_BIN}" ]; then
    exec "${NATIVE_BIN}" \
        -Dkompile.dist.home="${DIST_HOME}" \
        -Dspring.config.additional-location="optional:file:${DIST_HOME}/conf/" \
        "--server.port=${PORT}" \
        "${PASSTHROUGH_ARGS[@]}"
fi

if [ ! -f "${JAR}" ]; then
    echo "error: neither native binary ${NATIVE_BIN} nor exec JAR ${JAR} found" >&2
    exit 1
fi

# Java resolution order:
#   $KOMPILE_JAVA → <dist>/runtime/bin/java → $JAVA_HOME/bin/java → java on PATH
if [ -x "${KOMPILE_JAVA:-}" ]; then
    JAVA_BIN="${KOMPILE_JAVA}"
elif [ -x "${DIST_HOME}/runtime/bin/java" ]; then
    JAVA_BIN="${DIST_HOME}/runtime/bin/java"
elif [ -n "${JAVA_HOME:-}" ] && [ -x "${JAVA_HOME}/bin/java" ]; then
    JAVA_BIN="${JAVA_HOME}/bin/java"
elif command -v java >/dev/null 2>&1; then
    JAVA_BIN="java"
else
    echo "error: no Java runtime found. Install Java 21+, set JAVA_HOME, or rebuild the dist" >&2
    echo "       with a bundled runtime (see build-dist.sh --variant <variant>)." >&2
    exit 1
fi

exec "${JAVA_BIN}" \
    ${KOMPILE_CRAWL_HEAP} \
    -Djava.library.path="${DIST_HOME}/bin:${DIST_HOME}/lib" \
    -Dkompile.dist.home="${DIST_HOME}" \
    -Dspring.config.additional-location="optional:file:${DIST_HOME}/conf/" \
    -jar "${JAR}" \
    "--server.port=${PORT}" \
    "${PASSTHROUGH_ARGS[@]}"
