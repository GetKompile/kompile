#!/usr/bin/env bash
#
# 07-query.sh
#
# POST a natural language query to /api/rag/query and print the RAG-augmented
# response. The retrieved context is drawn from the VLM-extracted markdown that
# steps 05 and 06 produced.
#
# Usage:
#   ./07-query.sh                                       # default question
#   ./07-query.sh "What creatures are in the bestiary?" # custom question

set -euo pipefail

APP_BASE="http://localhost:8080"
ENDPOINT="${APP_BASE}/api/rag/query"

QUERY="${1:-Summarise the first page of the extracted document.}"

log() { printf '[07-query] %s\n' "$*"; }

if ! curl -fsS "${APP_BASE}/actuator/health" >/dev/null 2>&1; then
    log "ERROR: kompile-app-main not reachable at ${APP_BASE}"
    exit 1
fi

escape_json() {
    python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1" 2>/dev/null \
        || printf '"%s"' "$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')"
}

QUERY_JSON=$(escape_json "${QUERY}")

PAYLOAD=$(cat <<JSON
{
  "query": ${QUERY_JSON},
  "useToolCalling": false,
  "searchType": "LOCAL",
  "k": 5
}
JSON
)

log "POST ${ENDPOINT}"
log "query: ${QUERY}"

response=$(curl -sS -X POST "${ENDPOINT}" \
    -H 'Content-Type: application/json' \
    -d "${PAYLOAD}")

log "response:"
printf '%s\n' "${response}"
