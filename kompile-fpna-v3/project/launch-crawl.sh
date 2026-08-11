#!/usr/bin/env bash
# Run the FP&A corpus through the production `kompile crawl` agent.
#
# This wrapper owns only test setup and artifacts. The production CLI owns
# preflight, unified-crawl start/status/cancel/retry, graph reasoning, agent
# budgets, transcript persistence, and operator controls.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
REPO_ROOT="$(cd "$PROJECT_DIR/../.." && pwd)"
LOG_DIR="${LOG_DIR:-$PROJECT_DIR/data/logs}"
RUN_STORE_ROOT="${KOMPILE_CRAWL_RUN_ROOT:-$HOME/.kompile/crawl-runs}"

APP_PORT="${APP_PORT:-8080}"
APP_URL="${APP_URL:-http://localhost:${APP_PORT}}"
# MCP SSE transport is mounted below /mcp while REST/health endpoints use APP_URL.
CRAWL_CLI_URL="${CRAWL_CLI_URL:-$APP_URL/mcp}"
DATA_DIR="${DATA_DIR:-$REPO_ROOT/FP&A workflow artifacts 2026-05}"
FACT_SHEET_ID="${FACT_SHEET_ID:-1}"
SCHEMA_PRESET_ID="${SCHEMA_PRESET_ID:-fpna-cpg-channel-v1}"
COLLECTION_NAME="${COLLECTION_NAME:-fpna-workflow-v3}"
GRAPH_LLM_PROVIDER="${GRAPH_LLM_PROVIDER:-llm-chat}"
CRAWL_NAME="${CRAWL_NAME:-FP&A workflow artifacts 2026-05 full crawl $(date +%Y%m%d-%H%M%S)}"
CRAWL_MODE="${CRAWL_MODE:-auto}"
CRAWL_MAX_STEPS="${CRAWL_MAX_STEPS:-80}"
CRAWL_MAX_TOOL_CALLS="${CRAWL_MAX_TOOL_CALLS:-500}"
CRAWL_SERVER_AGENT="${CRAWL_SERVER_AGENT:-codex-cli}"
CRAWL_RAG="${CRAWL_RAG:-false}"
CRAWL_SESSION_ID="${CRAWL_SESSION_ID:-fpna-$(date +%Y%m%d-%H%M%S)}"
CRAWL_REQUEST_FILE="${CRAWL_REQUEST_FILE:-$LOG_DIR/latest-crawl-request.json}"
CRAWL_GRAPH_MAX_TOKENS="${CRAWL_GRAPH_MAX_TOKENS:-128}"
CRAWL_EMBEDDING_BATCH_SIZE="${CRAWL_EMBEDDING_BATCH_SIZE:-2}"
CRAWL_MAX_EMBEDDING_BATCH_SIZE="${CRAWL_MAX_EMBEDDING_BATCH_SIZE:-2}"
CRAWL_ADAPTIVE_BATCHING="${CRAWL_ADAPTIVE_BATCHING:-true}"
CLEAR_GRAPH_BEFORE_CRAWL="${CLEAR_GRAPH_BEFORE_CRAWL:-true}"
SKIP_APP_START="${SKIP_APP_START:-false}"
APP_HEALTH_PATH="${APP_HEALTH_PATH:-/actuator/health}"
APP_START_TIMEOUT_SECONDS="${APP_START_TIMEOUT_SECONDS:-180}"
KOMPILE_CLI_JAR="${KOMPILE_CLI_JAR:-$REPO_ROOT/kompile-cli/target/kompile-cli-0.1.0-SNAPSHOT-shaded.jar}"

mkdir -p "$LOG_DIR" "$(dirname "$CRAWL_REQUEST_FILE")"

require_command() {
    local command_name="$1"
    if ! command -v "$command_name" >/dev/null 2>&1; then
        echo "FATAL: required command not found: $command_name" >&2
        exit 1
    fi
}

http_ready() {
    curl -fsS --max-time 5 "$APP_URL$APP_HEALTH_PATH" >/dev/null 2>&1
}

wait_for_app() {
    local waited=0
    while [ "$waited" -lt "$APP_START_TIMEOUT_SECONDS" ]; do
        if http_ready; then
            echo "Application is ready at $APP_URL"
            return 0
        fi
        sleep 3
        waited=$((waited + 3))
    done
    echo "FATAL: application did not become ready at $APP_URL within ${APP_START_TIMEOUT_SECONDS}s" >&2
    echo "       Inspect $LOG_DIR/app.out.log and $LOG_DIR/app.err.log" >&2
    return 1
}

resolve_cli() {
    if [ -n "${KOMPILE_CLI_BIN:-}" ]; then
        CLI=( "$KOMPILE_CLI_BIN" )
    elif command -v kompile >/dev/null 2>&1; then
        CLI=( kompile )
    elif [ -f "$KOMPILE_CLI_JAR" ]; then
        CLI=( java -jar "$KOMPILE_CLI_JAR" )
    else
        echo "FATAL: production CLI not found. Set KOMPILE_CLI_BIN or KOMPILE_CLI_JAR." >&2
        exit 1
    fi
}

write_request() {
    if [ -n "${CRAWL_REQUEST_SOURCE:-}" ]; then
        if [ ! -f "$CRAWL_REQUEST_SOURCE" ]; then
            echo "FATAL: CRAWL_REQUEST_SOURCE does not exist: $CRAWL_REQUEST_SOURCE" >&2
            exit 1
        fi
        cp "$CRAWL_REQUEST_SOURCE" "$CRAWL_REQUEST_FILE"
        jq -e 'type == "object" and (.sources | type == "array")' "$CRAWL_REQUEST_FILE" >/dev/null
        return
    fi

    jq -n \
        --arg name "$CRAWL_NAME" \
        --arg dataDir "$DATA_DIR" \
        --arg schemaPresetId "$SCHEMA_PRESET_ID" \
        --arg collectionName "$COLLECTION_NAME" \
        --arg graphLlmProvider "$GRAPH_LLM_PROVIDER" \
        --argjson factSheetId "$FACT_SHEET_ID" \
        --argjson graphMaxTokens "$CRAWL_GRAPH_MAX_TOKENS" \
        --argjson embeddingBatchSize "$CRAWL_EMBEDDING_BATCH_SIZE" \
        --argjson maxEmbeddingBatchSize "$CRAWL_MAX_EMBEDDING_BATCH_SIZE" \
        --argjson adaptiveBatching "$CRAWL_ADAPTIVE_BATCHING" \
        '{
            name: $name,
            factSheetId: $factSheetId,
            sources: [{
                label: "FP&A workflow artifacts 2026-05",
                sourceType: "DIRECTORY",
                pathOrUrl: $dataDir,
                maxDepth: 3,
                maxDocuments: 1000,
                excludePatterns: ["*.m4v", "*.mp4", "*.mov", "*.BACKUP_*"]
            }],
            graphExtraction: {
                enabled: true,
                schemaPresetId: $schemaPresetId,
                schemaMode: "LENIENT",
                llmProvider: $graphLlmProvider,
                temperature: 0.0,
                maxTokens: $graphMaxTokens,
                entityResolution: true,
                entityResolutionUseEmbeddings: true,
                entityResolutionEmbeddingThreshold: 0.88,
                minConfidence: 0.5
            },
            vectorIndex: {
                enabled: true,
                collectionName: $collectionName,
                chunkerName: "recursive",
                embeddingBatchSize: $embeddingBatchSize,
                maxEmbeddingBatchSize: $maxEmbeddingBatchSize,
                adaptiveBatching: $adaptiveBatching
            }
        }' > "$CRAWL_REQUEST_FILE"

    jq -e 'type == "object" and (.sources | type == "array" and length > 0)' \
        "$CRAWL_REQUEST_FILE" >/dev/null
}

require_command curl
require_command jq
if [ ! -d "$DATA_DIR" ] && [ -z "${CRAWL_REQUEST_SOURCE:-}" ]; then
    echo "FATAL: FP&A data directory not found: $DATA_DIR" >&2
    exit 1
fi

resolve_cli
if [ "$SKIP_APP_START" != "true" ]; then
    echo "Starting the production FP&A app/model services."
    "$PROJECT_DIR/start"
fi
wait_for_app
write_request

jq -n \
    --arg sessionId "$CRAWL_SESSION_ID" \
    --arg mode "$CRAWL_MODE" \
    --arg appUrl "$APP_URL" \
    --arg requestFile "$CRAWL_REQUEST_FILE" \
    --arg runStore "$RUN_STORE_ROOT/$CRAWL_SESSION_ID" \
    --arg cli "${CLI[*]}" \
    '{sessionId: $sessionId, mode: $mode, appUrl: $appUrl,
      requestFile: $requestFile, runStore: $runStore, cli: $cli}' \
    > "$LOG_DIR/latest-crawl-run.json"

echo "Starting production crawl agent."
echo "  session: $CRAWL_SESSION_ID"
echo "  mode:    $CRAWL_MODE"
echo "  request: $CRAWL_REQUEST_FILE"
echo "  ledger:  $RUN_STORE_ROOT/$CRAWL_SESSION_ID/events.jsonl"

crawl_args=(
    crawl
    --url "$CRAWL_CLI_URL"
    --session-id "$CRAWL_SESSION_ID"
    --server-agent "$CRAWL_SERVER_AGENT"
    --mode "$CRAWL_MODE"
    --max-steps "$CRAWL_MAX_STEPS"
    --max-tool-calls "$CRAWL_MAX_TOOL_CALLS"
    --headless
    --request-file "$CRAWL_REQUEST_FILE"
)
if [ "$CRAWL_RAG" = "true" ]; then
    crawl_args+=(--rag)
else
    crawl_args+=(--rag=false)
fi
if [ "$CLEAR_GRAPH_BEFORE_CRAWL" = "true" ]; then
    crawl_args+=(--clear-graph)
fi

set +e
"${CLI[@]}" "${crawl_args[@]}" \
    > "$LOG_DIR/crawl-cli.out.log" \
    2> "$LOG_DIR/crawl-cli.err.log"
cli_rc=$?
set -e

RUN_STORE_DIR="$RUN_STORE_ROOT/$CRAWL_SESSION_ID"
if [ -f "$RUN_STORE_DIR/events.jsonl" ]; then
    cp "$RUN_STORE_DIR/events.jsonl" "$LOG_DIR/latest-crawl-run-events.jsonl"
fi
if [ -f "$HOME/.kompile/conversations/$CRAWL_SESSION_ID.txt" ]; then
    cp "$HOME/.kompile/conversations/$CRAWL_SESSION_ID.txt" \
        "$LOG_DIR/latest-crawl-transcript.txt"
fi
curl -fsS --max-time 30 \
    "$APP_URL/api/unified-crawl/graph-stats?factSheetId=$FACT_SHEET_ID" \
    > "$LOG_DIR/latest-crawl-graph-stats.json" || true

if [ "$cli_rc" -ne 0 ]; then
    echo "FATAL: production crawl CLI exited with status $cli_rc" >&2
    echo "       stdout: $LOG_DIR/crawl-cli.out.log" >&2
    echo "       stderr: $LOG_DIR/crawl-cli.err.log" >&2
    exit "$cli_rc"
fi

echo "Production crawl agent completed."
echo "  transcript: $LOG_DIR/latest-crawl-transcript.txt (when enabled)"
echo "  run ledger: $LOG_DIR/latest-crawl-run-events.jsonl"
echo "  graph:      $LOG_DIR/latest-crawl-graph-stats.json"
