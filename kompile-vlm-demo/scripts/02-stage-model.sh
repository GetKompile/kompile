#!/usr/bin/env bash
#
# 02-stage-model.sh
#
# Two-part model staging for the VLM demo:
#   1. Confirm that the SmolDocling-256M VLM pipeline model set is available under
#      ~/.kompile/models/vlm/smoldocling-256m/. This model set is staged via the
#      staging UI / model-catalog flow (not the raw /api/staging/stage HTTP call),
#      so this script only VERIFIES its presence and points at the UI if missing.
#   2. Stage a small Qwen GGUF model for the LLM that answers RAG queries using
#      the VLM-extracted markdown. Same flow as kompile-demo/scripts/02-stage-model.sh.
#
# If you do not have SmolDocling staged yet, run the staging UI:
#   http://localhost:8090/  (model catalog -> VLM -> SmolDocling 256M -> Stage)

set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STAGING_BASE="http://localhost:8090"
LLM_MODEL_ID="${KOMPILE_DEMO_MODEL_ID:-qwen}"
LLM_MODEL_URL="${KOMPILE_DEMO_MODEL_URL:-https://huggingface.co/unsloth/Qwen3.5-0.8B-GGUF/resolve/main/Qwen3.5-0.8B-Q4_K_M.gguf}"
LLM_TOKENIZER_URL="${KOMPILE_DEMO_TOKENIZER_URL:-https://huggingface.co/Qwen/Qwen3.5-0.8B/resolve/main/tokenizer.json}"
VLM_MODEL_DIR="${KOMPILE_VLM_MODEL_DIR:-${HOME}/.kompile/models/vlm/smoldocling-256m}"

log() { printf '[02-stage-model] %s\n' "$*"; }

# ---------- Part 1: SmolDocling VLM pipeline presence check ----------
log "checking SmolDocling model set at ${VLM_MODEL_DIR}"
required_files=(
    "vision_encoder.onnx"
    "decoder_model_merged.onnx"
    "embed_tokens.onnx"
    "tokenizer.json"
    "tokenizer_config.json"
)
missing=0
for f in "${required_files[@]}"; do
    if [[ ! -f "${VLM_MODEL_DIR}/${f}" ]]; then
        log "MISSING: ${VLM_MODEL_DIR}/${f}"
        missing=$(( missing + 1 ))
    fi
done
if (( missing > 0 )); then
    log "ERROR: SmolDocling-256M is not staged (${missing} required files missing)"
    log "       Stage it via the model catalog UI:"
    log "         ${STAGING_BASE}/ -> Model Catalog -> VLM -> SmolDocling 256M"
    log "       Or set KOMPILE_VLM_MODEL_DIR to a directory that contains all of:"
    for f in "${required_files[@]}"; do
        log "         - ${f}"
    done
    exit 1
fi
log "SmolDocling model set OK"

# ---------- Part 2: LLM staging (GGUF -> SameDiff) ----------
# This is a verbatim re-use of the markdown demo's LLM stage step. type=llm_ggml +
# format=gguf routes through the GgmlImporter -> SameDiff conversion path.
PAYLOAD=$(cat <<JSON
{
  "source": "http",
  "repository": "${LLM_MODEL_URL}",
  "modelId": "${LLM_MODEL_ID}",
  "type": "llm_ggml",
  "format": "gguf",
  "tokenizerUrl": "${LLM_TOKENIZER_URL}"
}
JSON
)

if ! curl -fsS "${STAGING_BASE}/actuator/health" >/dev/null 2>&1; then
    log "ERROR: staging server not reachable at ${STAGING_BASE}"
    log "       run scripts/01-start-staging.sh first"
    exit 1
fi

log "requesting stage of LLM model '${LLM_MODEL_ID}' from ${LLM_MODEL_URL}"
log "POST ${STAGING_BASE}/api/staging/stage"

response=$(curl -sS -X POST "${STAGING_BASE}/api/staging/stage" \
    -H 'Content-Type: application/json' \
    -d "${PAYLOAD}")
log "initial response: ${response}"

log "polling status for LLM model '${LLM_MODEL_ID}' (this can take several minutes)"
deadline=$(( SECONDS + 1800 ))
last_status=""
while true; do
    if (( SECONDS > deadline )); then
        log "ERROR: staging timed out after 30 minutes"
        exit 1
    fi

    status_json=$(curl -sS "${STAGING_BASE}/api/staging/status/${LLM_MODEL_ID}" || true)
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
            log "LLM stage complete for '${LLM_MODEL_ID}' (status=${status_upper})"
            log "promoting model '${LLM_MODEL_ID}' to registry"
            promote_resp=$(curl -sS -X POST "${STAGING_BASE}/api/staging/promote/${LLM_MODEL_ID}" \
                -H 'Content-Type: application/json' \
                -d '{"type": "llm_ggml"}' || true)
            log "promote response: ${promote_resp}"
            exit 0
            ;;
        FAILED|CANCELLED)
            log "ERROR: LLM staging ended with status=${status_upper}"
            printf '%s\n' "${status_json}"
            exit 1
            ;;
        *)
            sleep 5
            ;;
    esac
done
