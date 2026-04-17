#!/usr/bin/env bash
#
# 05-ingest-docs.sh
#
# Upload every markdown file in sample-docs/ to the running kompile-app-main via
# the /api/documents/upload multipart endpoint (verified in
# DocumentManagementController.java).
#
# This is a direct REST call rather than going through the kompile CLI, so the demo
# does not require the CLI binary to be on PATH or the federated subcommands to be
# installed. The endpoint name and payload shape match
# DocumentManagementController.handleFileUpload.

set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_BASE="http://localhost:8080"
DOCS_DIR="${DEMO_DIR}/sample-docs"
ENDPOINT="${APP_BASE}/api/documents/upload"

log() { printf '[05-ingest-docs] %s\n' "$*"; }

if ! curl -fsS "${APP_BASE}/actuator/health" >/dev/null 2>&1; then
    log "ERROR: kompile-app-main not reachable at ${APP_BASE}"
    exit 1
fi

if [[ ! -d "${DOCS_DIR}" ]]; then
    log "ERROR: ${DOCS_DIR} does not exist"
    exit 1
fi

shopt -s nullglob
md_files=("${DOCS_DIR}"/*.md)
if (( ${#md_files[@]} == 0 )); then
    log "ERROR: no .md files in ${DOCS_DIR}"
    exit 1
fi

log "uploading ${#md_files[@]} markdown file(s) to ${ENDPOINT}"
exit_code=0
for f in "${md_files[@]}"; do
    name=$(basename "${f}")
    log "  -> ${name}"
    http_code=$(curl -sS -o "/tmp/kompile-demo-upload-${name}.json" -w '%{http_code}' \
        -X POST "${ENDPOINT}" \
        -F "file=@${f};type=text/markdown" \
        -F "processImmediately=true" \
        -F "trackProgress=false" || echo 000)
    if [[ "${http_code}" =~ ^2 ]]; then
        log "     HTTP ${http_code} OK"
    else
        log "     HTTP ${http_code} FAILED"
        if [[ -s "/tmp/kompile-demo-upload-${name}.json" ]]; then
            cat "/tmp/kompile-demo-upload-${name}.json"
            printf '\n'
        fi
        exit_code=1
    fi
done

if (( exit_code != 0 )); then
    log "one or more uploads failed"
    exit ${exit_code}
fi

log "all uploads accepted; documents are being processed by the ingest pipeline"
