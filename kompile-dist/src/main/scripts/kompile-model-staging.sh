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
# Launcher for the native kompile-model-staging service.
#
# Behavior:
#   - Resolves the bundle root via the script location.
#   - Requires the GraalVM native binary at bin/kompile-model-staging.
#   - Side-loads JavaCPP/ND4J/CUDA libraries from the matching dist lib/ tree.
#   - Defaults to port 8090, but accepts overrides via the KOMPILE_STAGING_PORT
#     environment variable or a `--port <N>` command-line flag (the flag takes
#     precedence). Any other arguments are forwarded verbatim.
#
# Usage:
#   bin/kompile-model-staging.sh
#   bin/kompile-model-staging.sh --port 9091
#   KOMPILE_STAGING_PORT=9091 bin/kompile-model-staging.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || echo "${BASH_SOURCE[0]}")")" && pwd)"
DIST_HOME="$(cd "${SCRIPT_DIR}/.." && pwd)"

NATIVE_BIN="${DIST_HOME}/bin/kompile-model-staging"

# Determine port: CLI flag > env var > default 8090.
PORT="${KOMPILE_STAGING_PORT:-8090}"
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

# All GraalVM shims and backend libraries live in the canonical side-loaded lib/ tree.
export KOMPILE_DIST_HOME="${DIST_HOME}"
export KOMPILE_NATIVE_LIB_DIR="${DIST_HOME}/lib"
export LD_LIBRARY_PATH="${DIST_HOME}/lib${LD_LIBRARY_PATH:+:${LD_LIBRARY_PATH}}"
export DYLD_LIBRARY_PATH="${DIST_HOME}/lib${DYLD_LIBRARY_PATH:+:${DYLD_LIBRARY_PATH}}"

if [ ! -x "${NATIVE_BIN}" ]; then
    echo "error: native model-staging worker not found: ${NATIVE_BIN}" >&2
    echo "       install a complete backend-matched Kompile distribution" >&2
    exit 1
fi

exec "${NATIVE_BIN}" \
    -Dkompile.dist.home="${DIST_HOME}" \
    -Dspring.config.additional-location="optional:file:${DIST_HOME}/conf/" \
    "--server.port=${PORT}" \
    "${PASSTHROUGH_ARGS[@]}"
