#!/usr/bin/env bash
#
# run-demo.sh
#
# End-to-end VLM RAG-over-PDF demo for kompile. Boots staging, stages a Qwen GGUF
# LLM (downloaded + GGUF->SameDiff converted on the staging server), verifies the
# SmolDocling VLM model set is present, boots kompile-app-main, loads the LLM,
# runs each PDF under sample-pdfs/ through SmolDocling via the /api/vlm/test/run
# endpoint, ingests the extracted markdown, and asks one question about it.
#
# Safe to re-run: each step is idempotent and uses fixed pid files under .pids/.

set -euo pipefail

DEMO_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPTS_DIR="${DEMO_DIR}/scripts"
LOG_DIR="${DEMO_DIR}/logs"

mkdir -p "${LOG_DIR}"

banner() {
    printf '\n========================================================================\n'
    printf ' %s\n' "$*"
    printf '========================================================================\n'
}

step() {
    local script="$1"
    shift
    banner "$(basename "${script}") $*"
    bash "${script}" "$@"
}

QUERY="${1:-Summarise the first page of the extracted document.}"

step "${SCRIPTS_DIR}/01-start-staging.sh"
step "${SCRIPTS_DIR}/02-stage-model.sh"
step "${SCRIPTS_DIR}/03-start-app.sh"
step "${SCRIPTS_DIR}/04-load-llm.sh"
step "${SCRIPTS_DIR}/05-extract-pdfs.sh"
step "${SCRIPTS_DIR}/06-ingest-extracted.sh"

# Brief pause so the ingest task has a chance to write into the vector store
# before we query it.
sleep 5

step "${SCRIPTS_DIR}/07-query.sh" "${QUERY}"

banner "vlm demo complete"
printf 'Stop everything with: %s/scripts/stop-all.sh\n' "${DEMO_DIR}"
