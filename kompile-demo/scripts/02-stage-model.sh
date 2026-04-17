#!/usr/bin/env bash
#
# 02-stage-model.sh
#
# Ask kompile-model-staging to download a Qwen GGUF model directly from HuggingFace,
# convert it to SameDiff via the GgmlImporter conversion path, validate it, and place
# it in the staging "verified" directory ready for promotion.
#
# Endpoint: POST http://localhost:8090/api/staging/stage
# Polling : GET  http://localhost:8090/api/staging/status/{modelId}
#
# This exercises the GGUF -> SameDiff conversion that was just wired up.

set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAGING_BASE="http://localhost:8090"
MODEL_ID="${KOMPILE_DEMO_MODEL_ID:-qwen}"
MODEL_URL="${KOMPILE_DEMO_MODEL_URL:-https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf}"
TOKENIZER_URL="${KOMPILE_DEMO_TOKENIZER_URL:-https://huggingface.co/Qwen/Qwen3.5-0.8B/resolve/main/tokenizer.json}"

log() { printf '[02-stage-model] %s\n' "$*"; }

# Build the JSON payload. We use source=http so the staging server uses HttpDownloader
# to pull the raw .gguf file, and format=gguf so ConversionService routes it through
# the GgmlImporter -> SameDiff path. type=llm_ggml is the LLM model type from
# ai.kompile.modelmanager.registry.ModelType.
PAYLOAD=$(cat <<JSON
{
  "source": "http",
  "repository": "${MODEL_URL}",
  "modelId": "${MODEL_ID}",
  "type": "llm_ggml",
  "format": "gguf",
  "tokenizerUrl": "${TOKENIZER_URL}"
}
JSON
)

# Health gate.
if ! curl -fsS "${STAGING_BASE}/actuator/health" >/dev/null 2>&1; then
    log "ERROR: staging server not reachable at ${STAGING_BASE}"
    log "       run scripts/01-start-staging.sh first"
    exit 1
fi

log "requesting stage of model '${MODEL_ID}' from ${MODEL_URL}"
log "POST ${STAGING_BASE}/api/staging/stage"

response=$(curl -sS -X POST "${STAGING_BASE}/api/staging/stage" \
    -H 'Content-Type: application/json' \
    -d "${PAYLOAD}")
log "initial response: ${response}"

# Poll until terminal status. Statuses come from StagingStatus enum:
# PENDING, DOWNLOADING, CONVERTING, VALIDATING, READY, COMPLETED, FAILED, CANCELLED.
log "polling status for model '${MODEL_ID}' (this can take several minutes)"
deadline=$(( SECONDS + 1800 ))   # 30 minutes
last_status=""
while true; do
    if (( SECONDS > deadline )); then
        log "ERROR: staging timed out after 30 minutes"
        exit 1
    fi

    status_json=$(curl -sS "${STAGING_BASE}/api/staging/status/${MODEL_ID}" || true)
    if [[ -z "${status_json}" ]]; then
        sleep 3
        continue
    fi

    status=$(printf '%s' "${status_json}" | grep -oE '"status"[[:space:]]*:[[:space:]]*"[A-Za-z_]+"' | head -n1 | sed -E 's/.*"([A-Za-z_]+)"$/\1/' || true)
    progress=$(printf '%s' "${status_json}" | grep -oE '"progress"[[:space:]]*:[[:space:]]*[0-9]+' | head -n1 | grep -oE '[0-9]+' || true)
    status_upper=$(printf '%s' "${status}" | tr '[:lower:]' '[:upper:]')

    if [[ "${status_upper}" != "${last_status}" ]]; then
        log "status=${status_upper:-UNKNOWN} progress=${progress:-?}%"
        last_status="${status_upper}"
    fi

    case "${status_upper}" in
        READY|COMPLETED)
            log "stage complete for '${MODEL_ID}' (status=${status})"
            log "final status:"
            printf '%s\n' "${status_json}"
            # Auto-promote to the registry so /api/llm/load can find it
            log "promoting model '${MODEL_ID}' to registry"
            promote_resp=$(curl -sS -X POST "${STAGING_BASE}/api/staging/promote/${MODEL_ID}" \
                -H 'Content-Type: application/json' \
                -d '{"type": "llm_ggml"}' || true)
            log "promote response: ${promote_resp}"
            exit 0
            ;;
        FAILED|CANCELLED)
            log "ERROR: staging ended with status=${status}"
            printf '%s\n' "${status_json}"
            exit 1
            ;;
        *)
            sleep 5
            ;;
    esac
done
