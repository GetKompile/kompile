#!/usr/bin/env bash
#
# 06-ingest-extracted.sh
#
# Upload every extracted markdown file produced by 05-extract-pdfs.sh to the
# running kompile-app-main via the /api/documents/upload multipart endpoint.
#
# This is the same REST call that kompile-demo/scripts/05-ingest-docs.sh makes;
# the only difference is the input directory: we read from var/extracted/
# (VLM-produced markdown) instead of sample-docs/ (hand-written markdown).

set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_BASE="http://localhost:8080"
DOCS_DIR="${DEMO_DIR}/var/extracted"
ENDPOINT="${APP_BASE}/api/documents/upload"

log() { printf '[06-ingest-extracted] %s\n' "$*"; }

if ! curl -fsS "${APP_BASE}/actuator/health" >/dev/null 2>&1; then
    log "ERROR: kompile-app-main not reachable at ${APP_BASE}"
    exit 1
fi

if [[ ! -d "${DOCS_DIR}" ]]; then
    log "ERROR: ${DOCS_DIR} does not exist"
    log "       run scripts/05-extract-pdfs.sh first"
    exit 1
fi

shopt -s nullglob
md_files=("${DOCS_DIR}"/*.md)
if (( ${#md_files[@]} == 0 )); then
    log "ERROR: no .md files in ${DOCS_DIR}"
    log "       run scripts/05-extract-pdfs.sh first"
    exit 1
fi

log "uploading ${#md_files[@]} extracted markdown file(s) to ${ENDPOINT}"
exit_code=0
for f in "${md_files[@]}"; do
    name=$(basename "${f}")
    log "  -> ${name}"
    http_code=$(curl -sS -o "/tmp/kompile-vlm-demo-upload-${name}.json" -w '%{http_code}' \
        -X POST "${ENDPOINT}" \
        -F "file=@${f};type=text/markdown" \
        -F "processImmediately=true" \
        -F "trackProgress=false" || echo 000)
    if [[ "${http_code}" =~ ^2 ]]; then
        log "     HTTP ${http_code} OK"
    else
        log "     HTTP ${http_code} FAILED"
        if [[ -s "/tmp/kompile-vlm-demo-upload-${name}.json" ]]; then
            cat "/tmp/kompile-vlm-demo-upload-${name}.json"
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
