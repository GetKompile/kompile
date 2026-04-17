#!/usr/bin/env bash
#
# run-demo.sh
#
# End-to-end RAG-over-markdown demo for kompile. Boots staging, stages a Qwen GGUF
# model (downloaded + GGUF->SameDiff converted on the staging server), boots
# kompile-app-main, loads the LLM, ingests the sample markdown corpus, and asks one
# question.
#
# Designed to be safe to re-run: each step is idempotent and uses fixed pid files
# under .pids/.

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

QUERY="${1:-What is Kompile and how does its RAG pipeline work?}"

step "${SCRIPTS_DIR}/01-start-staging.sh"
step "${SCRIPTS_DIR}/02-stage-model.sh"
step "${SCRIPTS_DIR}/03-start-app.sh"
step "${SCRIPTS_DIR}/04-load-llm.sh"
step "${SCRIPTS_DIR}/05-ingest-docs.sh"

# Brief pause so the ingest task has a chance to write into the vector store before
# we query it. The upload endpoint returns when the file is written; the actual
# embedding/indexing runs asynchronously.
sleep 5

step "${SCRIPTS_DIR}/06-query.sh" "${QUERY}"

banner "demo complete"
printf 'Stop everything with: %s/scripts/stop-all.sh\n' "${DEMO_DIR}"
