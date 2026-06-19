#!/usr/bin/env bash
#
# 01-start-staging.sh
#
# Boot kompile-model-staging on port 8090 in the background, then wait until
# /actuator/health returns UP. PID is written to .pids/staging.pid and stdout/stderr
# are tee'd to logs/staging.log.
#
# Idempotent: if a staging process is already healthy on port 8090 it just records
# its PID and exits 0. This is a verbatim copy of kompile-demo/scripts/01-start-staging.sh
# — staging is model-pipeline agnostic, the VLM variant needs the same server.

set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "${DEMO_DIR}/.." && pwd)"
STAGING_MODULE="${REPO_ROOT}/kompile-app/kompile-model-staging"
STAGING_TARGET="${STAGING_MODULE}/target"
LOG_DIR="${DEMO_DIR}/logs"
PID_DIR="${DEMO_DIR}/.pids"
PID_FILE="${PID_DIR}/staging.pid"
LOG_FILE="${LOG_DIR}/staging.log"
HEALTH_URL="http://localhost:8090/actuator/health"
STAGING_CONFIG="${DEMO_DIR}/conf/staging.yml"

mkdir -p "${LOG_DIR}" "${PID_DIR}"

log() { printf '[01-start-staging] %s\n' "$*"; }

if curl -fsS "${HEALTH_URL}" >/dev/null 2>&1; then
    log "staging server already healthy at ${HEALTH_URL}"
    if [[ ! -f "${PID_FILE}" ]]; then
        existing_pid="$(ss -lntp 2>/dev/null | awk '/:8090/ {print $0}' | grep -oE 'pid=[0-9]+' | head -n1 | cut -d= -f2 || true)"
        if [[ -n "${existing_pid:-}" ]]; then
            echo "${existing_pid}" > "${PID_FILE}"
        fi
    fi
    exit 0
fi

STAGING_JAR=""
if [[ -d "${STAGING_TARGET}" ]]; then
    STAGING_JAR="$(find "${STAGING_TARGET}" -maxdepth 1 -type f -name 'kompile-model-staging-*-exec.jar' \
        ! -name '*-sources.jar' ! -name '*-javadoc.jar' | sort | tail -n1)"
    if [[ -z "${STAGING_JAR}" ]]; then
        STAGING_JAR="$(find "${STAGING_TARGET}" -maxdepth 1 -type f -name 'kompile-model-staging-*.jar' \
            ! -name '*-sources.jar' ! -name '*-javadoc.jar' ! -name '*.jar.original' \
            | sort | tail -n1)"
    fi
fi

if [[ -z "${STAGING_JAR}" || ! -f "${STAGING_JAR}" ]]; then
    log "ERROR: no kompile-model-staging jar found under ${STAGING_TARGET}"
    log "       Build it first: cd ${STAGING_MODULE} && mvn -DskipTests package"
    exit 1
fi

log "starting staging server from: ${STAGING_JAR}"
log "logs: ${LOG_FILE}"

JAVA_BIN="${JAVA_HOME:+${JAVA_HOME}/bin/}java"

nohup "${JAVA_BIN}" \
    -Xmx4g \
    -Dspring.config.additional-location="file:${STAGING_CONFIG}" \
    -jar "${STAGING_JAR}" \
    >> "${LOG_FILE}" 2>&1 &
echo $! > "${PID_FILE}"
log "started PID $(cat "${PID_FILE}")"

log "waiting for ${HEALTH_URL} ..."
deadline=$(( SECONDS + 180 ))
until curl -fsS "${HEALTH_URL}" 2>/dev/null | grep -q '"status":"UP"'; do
    if (( SECONDS > deadline )); then
        log "ERROR: staging server did not become healthy in 180s"
        log "       tail -n 50 ${LOG_FILE}"
        tail -n 50 "${LOG_FILE}" || true
        exit 1
    fi
    if ! kill -0 "$(cat "${PID_FILE}")" 2>/dev/null; then
        log "ERROR: staging server PID $(cat "${PID_FILE}") exited"
        tail -n 50 "${LOG_FILE}" || true
        exit 1
    fi
    sleep 2
done

log "staging server is healthy at ${HEALTH_URL}"
