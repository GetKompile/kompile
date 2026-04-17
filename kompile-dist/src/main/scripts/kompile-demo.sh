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
# Combined-demo launcher.
#
# Starts kompile-model-staging in the background (logs to ./logs/staging.log),
# waits up to 60 seconds for its actuator health endpoint to become healthy,
# then starts the main kompile-server in the foreground. SIGINT/SIGTERM are
# trapped so the staging process is killed cleanly when the foreground server
# exits.

set -e

SCRIPT_DIR="$(cd "$(dirname "$(readlink -f "${BASH_SOURCE[0]}" 2>/dev/null || echo "${BASH_SOURCE[0]}")")" && pwd)"
DIST_HOME="$(cd "${SCRIPT_DIR}/.." && pwd)"

STAGING_PORT="${KOMPILE_STAGING_PORT:-8090}"
HEALTH_URL="http://localhost:${STAGING_PORT}/actuator/health"
HEALTH_TIMEOUT_SECS="${KOMPILE_STAGING_HEALTH_TIMEOUT:-60}"

LOG_DIR="${DIST_HOME}/logs"
mkdir -p "${LOG_DIR}"
STAGING_LOG="${LOG_DIR}/staging.log"

STAGING_LAUNCHER="${SCRIPT_DIR}/kompile-model-staging.sh"
SERVER_BIN="${DIST_HOME}/bin/kompile-server"

if [ ! -x "${STAGING_LAUNCHER}" ]; then
    echo "error: staging launcher not found at ${STAGING_LAUNCHER}" >&2
    exit 1
fi
if [ ! -x "${SERVER_BIN}" ]; then
    echo "error: kompile-server binary not found at ${SERVER_BIN}" >&2
    exit 1
fi
if ! command -v curl >/dev/null 2>&1; then
    echo "error: curl is required for the staging healthcheck" >&2
    exit 1
fi

STAGING_PID=""

cleanup() {
    if [ -n "${STAGING_PID}" ] && kill -0 "${STAGING_PID}" 2>/dev/null; then
        echo "[kompile-demo] stopping staging service (pid ${STAGING_PID})"
        kill "${STAGING_PID}" 2>/dev/null || true
        # Give it a moment, then force-kill if still alive.
        for _ in 1 2 3 4 5; do
            kill -0 "${STAGING_PID}" 2>/dev/null || break
            sleep 1
        done
        if kill -0 "${STAGING_PID}" 2>/dev/null; then
            kill -9 "${STAGING_PID}" 2>/dev/null || true
        fi
    fi
}

trap cleanup INT TERM EXIT

echo "[kompile-demo] starting kompile-model-staging on port ${STAGING_PORT} (logs: ${STAGING_LOG})"
KOMPILE_STAGING_PORT="${STAGING_PORT}" "${STAGING_LAUNCHER}" >"${STAGING_LOG}" 2>&1 &
STAGING_PID=$!

echo "[kompile-demo] waiting for ${HEALTH_URL} (timeout ${HEALTH_TIMEOUT_SECS}s)"
deadline=$(( $(date +%s) + HEALTH_TIMEOUT_SECS ))
while :; do
    if ! kill -0 "${STAGING_PID}" 2>/dev/null; then
        echo "error: staging process exited before becoming healthy; see ${STAGING_LOG}" >&2
        exit 1
    fi
    status=$(curl -s -o /dev/null -w "%{http_code}" "${HEALTH_URL}" 2>/dev/null || echo "000")
    if [ "${status}" = "200" ]; then
        echo "[kompile-demo] staging healthy"
        break
    fi
    if [ "$(date +%s)" -ge "${deadline}" ]; then
        echo "error: staging health check timed out after ${HEALTH_TIMEOUT_SECS}s (last status: ${status})" >&2
        echo "       see ${STAGING_LOG}" >&2
        exit 1
    fi
    sleep 1
done

echo "[kompile-demo] starting kompile-server in foreground"
"${SERVER_BIN}" "$@"
