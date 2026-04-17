#!/usr/bin/env bash
#
# stop-all.sh
#
# Kill the demo's staging and app processes (recorded in .pids/) and clean up.

set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PID_DIR="${DEMO_DIR}/.pids"

log() { printf '[stop-all] %s\n' "$*"; }

stop_pid() {
    local label="$1"
    local pid_file="$2"
    if [[ ! -f "${pid_file}" ]]; then
        log "${label}: no pid file (${pid_file})"
        return 0
    fi
    local pid
    pid=$(cat "${pid_file}" || true)
    if [[ -z "${pid}" ]]; then
        log "${label}: empty pid file"
        rm -f "${pid_file}"
        return 0
    fi
    if kill -0 "${pid}" 2>/dev/null; then
        log "${label}: stopping pid ${pid}"
        kill "${pid}" 2>/dev/null || true
        for _ in 1 2 3 4 5 6 7 8 9 10; do
            if ! kill -0 "${pid}" 2>/dev/null; then
                break
            fi
            sleep 1
        done
        if kill -0 "${pid}" 2>/dev/null; then
            log "${label}: pid ${pid} still alive, sending SIGKILL"
            kill -9 "${pid}" 2>/dev/null || true
        fi
    else
        log "${label}: pid ${pid} not running"
    fi
    rm -f "${pid_file}"
}

stop_pid "kompile-app-main" "${PID_DIR}/app.pid"
stop_pid "kompile-model-staging" "${PID_DIR}/staging.pid"

for port in 8080 8090; do
    pids=$(ss -lntp 2>/dev/null | awk -v p=":${port}" '$0 ~ p {print $0}' \
        | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u || true)
    for pid in ${pids}; do
        if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
            log "leftover process on :${port}, stopping pid ${pid}"
            kill "${pid}" 2>/dev/null || true
        fi
    done
done

log "done"
