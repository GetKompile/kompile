#!/usr/bin/env bash
#
# 04-load-llm.sh
#
# Tell kompile-app-main to load the staged LLM as the active LanguageModel so
# step 07 (RAG query) can generate answers over the VLM-extracted content.
#
# Endpoint: POST http://localhost:8080/api/llm/load
# Payload : {"modelId": "<id>"}

set -euo pipefail

APP_BASE="http://localhost:8080"
MODEL_ID="${KOMPILE_DEMO_MODEL_ID:-qwen}"

log() { printf '[04-load-llm] %s\n' "$*"; }

if ! curl -fsS "${APP_BASE}/actuator/health" >/dev/null 2>&1; then
    log "ERROR: kompile-app-main not reachable at ${APP_BASE}"
    log "       run scripts/03-start-app.sh first"
    exit 1
fi

PAYLOAD=$(cat <<JSON
{
  "modelId": "${MODEL_ID}"
}
JSON
)

log "POST ${APP_BASE}/api/llm/load"
log "payload: ${PAYLOAD}"

http_code=$(curl -sS -o /tmp/kompile-vlm-demo-llm-load.json -w '%{http_code}' \
    -X POST "${APP_BASE}/api/llm/load" \
    -H 'Content-Type: application/json' \
    -d "${PAYLOAD}" || echo 000)

log "HTTP ${http_code}"
if [[ -s /tmp/kompile-vlm-demo-llm-load.json ]]; then
    log "response body:"
    cat /tmp/kompile-vlm-demo-llm-load.json
    printf '\n'
fi

case "${http_code}" in
    2??)
        log "LLM loaded successfully"
        ;;
    *)
        log "ERROR: /api/llm/load returned HTTP ${http_code}"
        exit 1
        ;;
esac
