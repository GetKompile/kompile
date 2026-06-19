#!/usr/bin/env bash
#
# 05-extract-pdfs.sh
#
# For each PDF under sample-pdfs/, run SmolDocling VLM extraction via the
# /api/vlm/test/run endpoint, poll for completion, then save the page-by-page
# text output as a single markdown file under var/extracted/.
#
# This is the VLM-specific piece that differs from kompile-demo. SmolDocling
# runs in an isolated subprocess (VlmTestSubprocessMain) spawned by
# VlmTestSubprocessLauncher — that process is the one actually loading the
# vision encoder + decoder + embed_tokens ONNX models and running the pipeline
# end-to-end.
#
# Environment knobs:
#   KOMPILE_VLM_MODEL_ID       default: smoldocling-256m
#   KOMPILE_VLM_OUTPUT_FORMAT  default: MARKDOWN (also valid: DOCTAGS, PLAIN_TEXT)
#   KOMPILE_VLM_MAX_PAGES      default: 3    (limit for demo speed)
#   KOMPILE_VLM_PDF_DPI        default: 150
#   KOMPILE_VLM_MAX_NEW_TOKENS default: 2048
#   KOMPILE_VLM_POLL_SECONDS   default: 3

set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PDF_DIR="${DEMO_DIR}/sample-pdfs"
OUT_DIR="${DEMO_DIR}/var/extracted"
APP_BASE="http://localhost:8080"

VLM_MODEL_ID="${KOMPILE_VLM_MODEL_ID:-smoldocling-256m}"
VLM_OUTPUT_FORMAT="${KOMPILE_VLM_OUTPUT_FORMAT:-MARKDOWN}"
VLM_MAX_PAGES="${KOMPILE_VLM_MAX_PAGES:-3}"
VLM_PDF_DPI="${KOMPILE_VLM_PDF_DPI:-150}"
VLM_MAX_NEW_TOKENS="${KOMPILE_VLM_MAX_NEW_TOKENS:-2048}"
VLM_POLL_SECONDS="${KOMPILE_VLM_POLL_SECONDS:-3}"

mkdir -p "${OUT_DIR}"

log() { printf '[05-extract-pdfs] %s\n' "$*"; }

if ! command -v jq >/dev/null 2>&1; then
    log "ERROR: jq is required to parse VLM results; install it (e.g. dnf install jq)"
    exit 1
fi

if ! curl -fsS "${APP_BASE}/actuator/health" >/dev/null 2>&1; then
    log "ERROR: kompile-app-main not reachable at ${APP_BASE}"
    log "       run scripts/03-start-app.sh first"
    exit 1
fi

shopt -s nullglob
pdfs=("${PDF_DIR}"/*.pdf "${PDF_DIR}"/*.PDF)
if (( ${#pdfs[@]} == 0 )); then
    log "ERROR: no PDFs found under ${PDF_DIR}"
    log "       drop one or more *.pdf files there and re-run"
    exit 1
fi

log "found ${#pdfs[@]} PDF(s) to extract"
log "  model:       ${VLM_MODEL_ID}"
log "  format:      ${VLM_OUTPUT_FORMAT}"
log "  maxPages:    ${VLM_MAX_PAGES}"
log "  dpi:         ${VLM_PDF_DPI}"
log "  maxNewToks:  ${VLM_MAX_NEW_TOKENS}"
log "  output dir:  ${OUT_DIR}"

extract_one() {
    local pdf="$1"
    local base; base="$(basename "${pdf}")"
    local stem="${base%.*}"
    local out_md="${OUT_DIR}/${stem}.md"
    local raw_json="${OUT_DIR}/${stem}.result.json"

    log "------------------------------------------------------------"
    log "extracting ${base}"

    local submit_resp
    submit_resp=$(curl -sS -X POST "${APP_BASE}/api/vlm/test/run" \
        -F "file=@${pdf};type=application/pdf" \
        -F "modelId=${VLM_MODEL_ID}" \
        -F "outputFormat=${VLM_OUTPUT_FORMAT}" \
        -F "maxPages=${VLM_MAX_PAGES}" \
        -F "pdfRenderDpi=${VLM_PDF_DPI}" \
        -F "maxNewTokens=${VLM_MAX_NEW_TOKENS}")

    local task_id
    task_id=$(printf '%s' "${submit_resp}" | jq -r '.taskId // empty')
    if [[ -z "${task_id}" ]]; then
        log "ERROR: /api/vlm/test/run did not return a taskId"
        log "response: ${submit_resp}"
        return 1
    fi
    log "taskId=${task_id}"

    # Poll status until DONE or terminal. The status endpoint switches from
    # "RUNNING" to a terminal state; completed results land in /results/{taskId}.
    local status phase pct last=""
    local deadline=$(( SECONDS + 1800 ))
    while true; do
        if (( SECONDS > deadline )); then
            log "ERROR: VLM extraction timed out after 30 minutes for ${base}"
            return 1
        fi
        local status_json
        status_json=$(curl -sS "${APP_BASE}/api/vlm/test/status/${task_id}" || true)
        if [[ -z "${status_json}" ]]; then
            sleep "${VLM_POLL_SECONDS}"
            continue
        fi
        status=$(printf '%s' "${status_json}" | jq -r '.status // empty')
        phase=$(printf '%s' "${status_json}" | jq -r '.currentPhase // empty')
        pct=$(printf '%s' "${status_json}" | jq -r '.progressPercent // empty')
        local tag="${status}|${phase}|${pct}"
        if [[ "${tag}" != "${last}" ]]; then
            log "  status=${status} phase=${phase} progress=${pct}%"
            last="${tag}"
        fi
        case "${status}" in
            COMPLETED|DONE|SUCCESS|FINISHED)
                break
                ;;
            FAILED|ERROR|CANCELLED)
                log "ERROR: extraction ended with status=${status}"
                printf '%s\n' "${status_json}"
                return 1
                ;;
            *)
                sleep "${VLM_POLL_SECONDS}"
                ;;
        esac
    done

    # Fetch the full result and persist the raw JSON for inspection + the
    # assembled markdown we actually feed into the ingest step.
    curl -sS "${APP_BASE}/api/vlm/test/results/${task_id}" -o "${raw_json}"
    if [[ ! -s "${raw_json}" ]]; then
        log "ERROR: empty result from /api/vlm/test/results/${task_id}"
        return 1
    fi

    # Each entry in .pages is { pageNumber, text, success, ... }. Join all
    # successful pages with blank-line separators and prepend an H1 title.
    {
        printf '# %s\n\n' "${stem}"
        printf '_Extracted from %s via SmolDocling (%s)_\n\n' "${base}" "${VLM_MODEL_ID}"
        jq -r '.pages[]? | select(.success == true) | "## Page \(.pageNumber)\n\n\(.text)\n"' \
            "${raw_json}"
    } > "${out_md}"

    local page_count
    page_count=$(jq '.pages | length' "${raw_json}" 2>/dev/null || echo "?")
    log "wrote ${out_md} (${page_count} page result(s))"
}

for pdf in "${pdfs[@]}"; do
    extract_one "${pdf}"
done

log "------------------------------------------------------------"
log "extraction complete; markdown files in ${OUT_DIR}"
