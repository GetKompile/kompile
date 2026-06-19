#!/usr/bin/env bash
# Launch and optionally monitor a full FP&A workflow crawl into the scoped knowledge graph.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$SCRIPT_DIR"
REPO_ROOT="$(cd "$PROJECT_DIR/../.." && pwd)"
LOG_DIR="$PROJECT_DIR/data/logs"
KOMPILE_CONFIG_DIR="${KOMPILE_CONFIG_DIR:-$HOME/.kompile/config}"

APP_PORT="${APP_PORT:-8080}"
APP_URL="${APP_URL:-http://localhost:${APP_PORT}}"
STAGING_PORT="${STAGING_PORT:-8090}"
STAGING_URL="${KOMPILE_STAGING_URL:-http://localhost:${STAGING_PORT}}"
DATA_DIR="${DATA_DIR:-$REPO_ROOT/FP&A workflow artifacts 2026-05}"
FACT_SHEET_ID="${FACT_SHEET_ID:-1}"
SCHEMA_PRESET_ID="${SCHEMA_PRESET_ID:-fpna-cpg-channel-v1}"
COLLECTION_NAME="${COLLECTION_NAME:-fpna-workflow-v3}"
GRAPH_LLM_PROVIDER="${GRAPH_LLM_PROVIDER:-llm-chat}"
CRAWL_NAME="${CRAWL_NAME:-FP&A workflow artifacts 2026-05 full crawl $(date +%Y%m%d-%H%M%S)}"
POLL_SECONDS="${POLL_SECONDS:-10}"
WATCH_CRAWL="${WATCH_CRAWL:-true}"
CLEAR_GRAPH_BEFORE_CRAWL="${CLEAR_GRAPH_BEFORE_CRAWL:-true}"
CRAWL_EMBEDDING_BATCH_SIZE="${CRAWL_EMBEDDING_BATCH_SIZE:-2}"
CRAWL_MAX_EMBEDDING_BATCH_SIZE="${CRAWL_MAX_EMBEDDING_BATCH_SIZE:-2}"
CRAWL_ADAPTIVE_BATCHING="${CRAWL_ADAPTIVE_BATCHING:-true}"
EMBEDDING_MODEL_ID="${EMBEDDING_MODEL_ID:-bge-m3}"
EMBEDDING_EXPECTED_DIMENSIONS="${EMBEDDING_EXPECTED_DIMENSIONS:-1024}"
EMBEDDING_REQUIRE_OPTIMIZED="${EMBEDDING_REQUIRE_OPTIMIZED:-true}"
EMBEDDING_OPTIMIZATION_PROFILE="${EMBEDDING_OPTIMIZATION_PROFILE:-FULL}"
EMBEDDING_QUANTIZATION_TYPE="${EMBEDDING_QUANTIZATION_TYPE:-FLOAT16}"
EMBEDDING_OPTIMIZATION_FORCE="${EMBEDDING_OPTIMIZATION_FORCE:-false}"
EMBEDDING_OPTIMAL_BATCH_SIZE="${EMBEDDING_OPTIMAL_BATCH_SIZE:-2}"
EMBEDDING_MAX_BATCH_SIZE="${EMBEDDING_MAX_BATCH_SIZE:-2}"
EMBEDDING_SINGLE_DSP_PLAN="${EMBEDDING_SINGLE_DSP_PLAN:-true}"
EMBEDDING_DSP_PLAN_BATCH_SIZE="${EMBEDDING_DSP_PLAN_BATCH_SIZE:-2}"
MODEL_API_TIMEOUT_SECONDS="${MODEL_API_TIMEOUT_SECONDS:-1800}"
EXECUTION_ERROR_PATTERN="${EXECUTION_ERROR_PATTERN:-DSP execution failed|Native plan|Op .* contains|contains [0-9]+ NaN|contains [0-9]+ Inf|NaN|Inf|non[- ]finite|zero[- ]?magnitude|OutOfMemory|OOM|CUDA|CUDNN|cuBLAS|cuDNN|execution failed|inference failed|Model loading failed|subprocess.*failed|failed status=|status=50|device-side assert|illegal memory}"
RESOURCE_CHECK_ENABLED="${RESOURCE_CHECK_ENABLED:-true}"
MIN_SYSTEM_AVAILABLE_MIB="${MIN_SYSTEM_AVAILABLE_MIB:-16384}"
MIN_GPU_FREE_MIB="${MIN_GPU_FREE_MIB:-4096}"
RESOURCE_POLL_INITIAL_SECONDS="${RESOURCE_POLL_INITIAL_SECONDS:-5}"
RESOURCE_POLL_MAX_SECONDS="${RESOURCE_POLL_MAX_SECONDS:-120}"
RESOURCE_STATUS_INTERVAL_SECONDS="${RESOURCE_STATUS_INTERVAL_SECONDS:-30}"
LLM_CUDA_DEVICE_ID="${LLM_CUDA_DEVICE_ID:-}"
EMBEDDING_CUDA_DEVICE_ID="${EMBEDDING_CUDA_DEVICE_ID:-}"
VLM_CUDA_DEVICE_ID="${VLM_CUDA_DEVICE_ID:-}"
GRAPH_EXTRACTION_MAX_TOKENS="${GRAPH_EXTRACTION_MAX_TOKENS:-128}"
GRAPH_LLM_CHARS_PER_CALL="${GRAPH_LLM_CHARS_PER_CALL:-420}"
GRAPH_LLM_MAX_DOCS_PER_CALL="${GRAPH_LLM_MAX_DOCS_PER_CALL:-1}"
GRAPH_LLM_MAX_CHARS_PER_DOC_SEGMENT="${GRAPH_LLM_MAX_CHARS_PER_DOC_SEGMENT:-420}"
GRAPH_LLM_PARALLELISM="${GRAPH_LLM_PARALLELISM:-1}"
GRAPH_LLM_CALL_TIMEOUT_SECONDS="${GRAPH_LLM_CALL_TIMEOUT_SECONDS:-600}"
GRAPH_LOCAL_COMPACT_PROMPT="${GRAPH_LOCAL_COMPACT_PROMPT:-true}"
GRAPH_LOCAL_CHARS_PER_LLM_CALL="${GRAPH_LOCAL_CHARS_PER_LLM_CALL:-360}"
GRAPH_LOCAL_MAX_CHARS_PER_DOC_SEGMENT="${GRAPH_LOCAL_MAX_CHARS_PER_DOC_SEGMENT:-360}"
GRAPH_LOCAL_MAX_OUTPUT_ENTITIES="${GRAPH_LOCAL_MAX_OUTPUT_ENTITIES:-4}"
GRAPH_LOCAL_MAX_OUTPUT_RELATIONSHIPS="${GRAPH_LOCAL_MAX_OUTPUT_RELATIONSHIPS:-4}"
GRAPH_LOCAL_FALLBACK_ENABLED="${GRAPH_LOCAL_FALLBACK_ENABLED:-true}"
GRAPH_LOCAL_FALLBACK_MAX_ENTITIES="${GRAPH_LOCAL_FALLBACK_MAX_ENTITIES:-8}"
GRAPH_NODE_PERSIST_BATCH_SIZE="${GRAPH_NODE_PERSIST_BATCH_SIZE:-128}"
GRAPH_GENERATE_MISSING_NODE_EMBEDDINGS="${GRAPH_GENERATE_MISSING_NODE_EMBEDDINGS:-false}"
CRAWL_MAX_CONCURRENT_JOBS="${CRAWL_MAX_CONCURRENT_JOBS:-1}"
CRAWL_QUEUE_CAPACITY="${CRAWL_QUEUE_CAPACITY:-25}"
CRAWL_MEMORY_WAIT_THRESHOLD_PERCENT="${CRAWL_MEMORY_WAIT_THRESHOLD_PERCENT:-70}"
CRAWL_MEMORY_CRITICAL_THRESHOLD_PERCENT="${CRAWL_MEMORY_CRITICAL_THRESHOLD_PERCENT:-82}"
CRAWL_MEMORY_WAIT_TIMEOUT_SECONDS="${CRAWL_MEMORY_WAIT_TIMEOUT_SECONDS:-300}"
CRAWL_NATIVE_MEMORY_CLEANUP_ENABLED="${CRAWL_NATIVE_MEMORY_CLEANUP_ENABLED:-true}"
CRAWL_NATIVE_MEMORY_CLEANUP_PASSES="${CRAWL_NATIVE_MEMORY_CLEANUP_PASSES:-3}"
CRAWL_NATIVE_MEMORY_WAIT_THRESHOLD_PERCENT="${CRAWL_NATIVE_MEMORY_WAIT_THRESHOLD_PERCENT:-70}"
CRAWL_NATIVE_MEMORY_CRITICAL_THRESHOLD_PERCENT="${CRAWL_NATIVE_MEMORY_CRITICAL_THRESHOLD_PERCENT:-82}"
CRAWL_GRAPH_EXTRACTION_BATCH_SIZE="${CRAWL_GRAPH_EXTRACTION_BATCH_SIZE:-2}"
CRAWL_BACKGROUND_GRAPH_THREADS="${CRAWL_BACKGROUND_GRAPH_THREADS:-2}"
CRAWL_SOURCE_LOAD_PARALLELISM="${CRAWL_SOURCE_LOAD_PARALLELISM:-2}"
CRAWL_CHUNKING_PARALLELISM="${CRAWL_CHUNKING_PARALLELISM:-2}"
CRAWL_GRAPH_EXTRACTION_PARALLELISM="${CRAWL_GRAPH_EXTRACTION_PARALLELISM:-1}"
CRAWL_GRAPH_EXTRACTION_TARGET_CHARS_PER_BATCH="${CRAWL_GRAPH_EXTRACTION_TARGET_CHARS_PER_BATCH:-2400}"
CRAWL_CHUNKING_TARGET_CHARS_PER_TASK="${CRAWL_CHUNKING_TARGET_CHARS_PER_TASK:-120000}"
CRAWL_VECTOR_BATCH_SIZE="${CRAWL_VECTOR_BATCH_SIZE:-1}"
CRAWL_POST_PROCESS_PARALLEL="${CRAWL_POST_PROCESS_PARALLEL:-false}"
CRAWL_GRAPH_CONSTRUCTOR_SKIP_EMBEDDING="${CRAWL_GRAPH_CONSTRUCTOR_SKIP_EMBEDDING:-true}"
CRAWL_GRAPH_CONSTRUCTOR_PERSIST_MATRIX_GRAPH="${CRAWL_GRAPH_CONSTRUCTOR_PERSIST_MATRIX_GRAPH:-false}"
CRAWL_RETAIN_RESULT_GRAPH="${CRAWL_RETAIN_RESULT_GRAPH:-false}"
CRAWL_COST_SORT_CHUNKS="${CRAWL_COST_SORT_CHUNKS:-true}"
COMPACTION_EMBEDDING_CACHE_SIZE="${COMPACTION_EMBEDDING_CACHE_SIZE:-32}"
COMPACTION_EMBEDDING_NATIVE_MEMORY_THRESHOLD_PERCENT="${COMPACTION_EMBEDDING_NATIVE_MEMORY_THRESHOLD_PERCENT:-76}"
COMPACTION_MAX_EMBEDDING_BLOCK_SIZE="${COMPACTION_MAX_EMBEDDING_BLOCK_SIZE:-16}"
COMPACTION_NATIVE_MEMORY_CLEANUP_PASSES="${COMPACTION_NATIVE_MEMORY_CLEANUP_PASSES:-3}"

mkdir -p "$LOG_DIR"

require_command() {
    local cmd="$1"
    if ! command -v "$cmd" >/dev/null 2>&1; then
        echo "FATAL: required command not found: $cmd" >&2
        exit 1
    fi
}

http_get() {
    local path="$1"
    curl -fsS --max-time 20 "$APP_URL$path"
}

http_post_json() {
    local path="$1"
    local body="$2"
    curl -fsS --max-time 60 \
        -X POST "$APP_URL$path" \
        -H 'Content-Type: application/json' \
        --data-binary "$body"
}

http_json_to_file() {
    local method="$1"
    local url="$2"
    local body="$3"
    local out_file="$4"
    local timeout_seconds="${5:-60}"
    local http_code
    local rc=0

    if [ "$method" = "GET" ]; then
        http_code="$(curl -sS --max-time "$timeout_seconds" -o "$out_file" -w "%{http_code}" "$url")" || rc=$?
    else
        http_code="$(curl -sS --max-time "$timeout_seconds" -o "$out_file" -w "%{http_code}" \
            -X "$method" "$url" \
            -H 'Content-Type: application/json' \
            --data-binary "$body")" || rc=$?
    fi

    echo "$http_code" > "$out_file.http-code"
    if [ "$rc" -ne 0 ] || ! [[ "$http_code" =~ ^2[0-9][0-9]$ ]]; then
        echo "FATAL: $method $url failed rc=$rc http=$http_code response=$out_file" >&2
        if [ -s "$out_file" ]; then
            jq '.' "$out_file" 2>/dev/null >&2 || sed -n '1,160p' "$out_file" >&2
        fi
        exit 1
    fi
}

json_file_must() {
    local file="$1"
    shift
    local args=("$@")
    local message="${args[$((${#args[@]} - 1))]}"
    unset 'args[$((${#args[@]} - 1))]'
    local filter="${args[$((${#args[@]} - 1))]}"
    unset 'args[$((${#args[@]} - 1))]'
    if ! jq -e "${args[@]}" "$filter" "$file" >/dev/null; then
        echo "FATAL: $message" >&2
        echo "JSON file: $file" >&2
        jq '.' "$file" 2>/dev/null >&2 || sed -n '1,160p' "$file" >&2
        exit 1
    fi
}

normalize_quantization_type() {
    local value
    value="$(printf '%s' "${1:-}" | tr '[:lower:]' '[:upper:]')"
    case "$value" in
        FP16|FLOAT16)
            echo "FLOAT16"
            ;;
        *)
            echo "$value"
            ;;
    esac
}

bytes_to_mib() {
    local bytes="${1:-0}"
    if [ -z "$bytes" ] || [ "$bytes" = "null" ]; then
        bytes=0
    fi
    echo $((bytes / 1048576))
}

system_available_mib() {
    awk '/MemAvailable:/ { printf "%d", $2 / 1024 }' /proc/meminfo
}

gpu_free_mib() {
    if ! command -v nvidia-smi >/dev/null 2>&1; then
        echo ""
        return 0
    fi
    nvidia-smi --query-gpu=memory.free --format=csv,noheader,nounits 2>/dev/null \
        | awk 'NR == 1 || $1 < min { min = $1 } END { if (NR > 0) printf "%d", min }'
}

print_resource_snapshot() {
    local label="$1"
    local sys_avail
    local gpu_free
    sys_avail="$(system_available_mib 2>/dev/null || echo 0)"
    gpu_free="$(gpu_free_mib)"
    if [ -n "$gpu_free" ]; then
        echo "  resources[$label] memAvailable=${sys_avail}MiB minMem=${MIN_SYSTEM_AVAILABLE_MIB}MiB minGpuFree=${gpu_free}MiB minGpu=${MIN_GPU_FREE_MIB}MiB"
    else
        echo "  resources[$label] memAvailable=${sys_avail}MiB minMem=${MIN_SYSTEM_AVAILABLE_MIB}MiB gpu=unavailable"
    fi
}

resources_ready() {
    local sys_avail
    local gpu_free
    sys_avail="$(system_available_mib 2>/dev/null || echo 0)"
    if [ "$sys_avail" -lt "$MIN_SYSTEM_AVAILABLE_MIB" ]; then
        return 1
    fi
    gpu_free="$(gpu_free_mib)"
    if [ -n "$gpu_free" ] && [ "$gpu_free" -lt "$MIN_GPU_FREE_MIB" ]; then
        return 1
    fi
    return 0
}

wait_for_resources() {
    local reason="$1"
    if [ "$RESOURCE_CHECK_ENABLED" != "true" ]; then
        return 0
    fi

    local delay="$RESOURCE_POLL_INITIAL_SECONDS"
    while ! resources_ready; do
        print_resource_snapshot "$reason-wait"
        echo "  resource gate not satisfied for $reason; sleeping ${delay}s"
        sleep "$delay"
        if [ "$delay" -lt "$RESOURCE_POLL_MAX_SECONDS" ]; then
            delay=$((delay * 2))
            if [ "$delay" -gt "$RESOURCE_POLL_MAX_SECONDS" ]; then
                delay="$RESOURCE_POLL_MAX_SECONDS"
            fi
        fi
    done
    print_resource_snapshot "$reason-ready"
}

configure_device_routing_json() {
    mkdir -p "$KOMPILE_CONFIG_DIR"
    local config_file="$KOMPILE_CONFIG_DIR/device-routing-config.json"
    local tmp_file="$config_file.tmp.$$"
    if [ ! -s "$config_file" ]; then
        printf '{"serviceRoutes":{},"enabled":true}\n' > "$config_file"
    fi
    cp "$config_file" "$LOG_DIR/device-routing-config.before-crawl.json" || true

    # Build jq filter — only set cudaDeviceId if explicitly provided,
    # otherwise let ND4J auto-select based on free GPU memory.
    local jq_filter='.enabled = true | .serviceRoutes = (.serviceRoutes // {})'

    jq_filter+=' | .serviceRoutes.llm = ((.serviceRoutes.llm // {}) + {deviceType: "cuda"})'
    if [ -n "$LLM_CUDA_DEVICE_ID" ]; then
        jq_filter+=" | .serviceRoutes.llm.cudaDeviceId = $LLM_CUDA_DEVICE_ID"
    else
        jq_filter+=' | del(.serviceRoutes.llm.cudaDeviceId)'
    fi

    jq_filter+=' | .serviceRoutes.embedding = ((.serviceRoutes.embedding // {}) + {deviceType: "cuda", maxThreads: 4})'
    if [ -n "$EMBEDDING_CUDA_DEVICE_ID" ]; then
        jq_filter+=" | .serviceRoutes.embedding.cudaDeviceId = $EMBEDDING_CUDA_DEVICE_ID"
        jq_filter+=' | .serviceRoutes.vectorPopulation = ((.serviceRoutes.vectorPopulation // {}) + {deviceType: "cuda", maxThreads: 4})'
        jq_filter+=" | .serviceRoutes.vectorPopulation.cudaDeviceId = $EMBEDDING_CUDA_DEVICE_ID"
    else
        jq_filter+=' | del(.serviceRoutes.embedding.cudaDeviceId)'
        jq_filter+=' | .serviceRoutes.vectorPopulation = ((.serviceRoutes.vectorPopulation // {}) + {deviceType: "cuda", maxThreads: 4})'
        jq_filter+=' | del(.serviceRoutes.vectorPopulation.cudaDeviceId)'
    fi

    jq_filter+=' | .serviceRoutes.vlm = ((.serviceRoutes.vlm // {}) + {deviceType: "cuda"})'
    if [ -n "$VLM_CUDA_DEVICE_ID" ]; then
        jq_filter+=" | .serviceRoutes.vlm.cudaDeviceId = $VLM_CUDA_DEVICE_ID"
    else
        jq_filter+=' | del(.serviceRoutes.vlm.cudaDeviceId)'
    fi

    jq "$jq_filter" "$config_file" > "$tmp_file"
    mv "$tmp_file" "$config_file"
    cp "$config_file" "$LOG_DIR/device-routing-config.for-crawl.json" || true
    echo "Configured device routing: llm=${LLM_CUDA_DEVICE_ID:-auto} embedding=${EMBEDDING_CUDA_DEVICE_ID:-auto} vlm=${VLM_CUDA_DEVICE_ID:-auto} (empty=ND4J auto-selects by free memory)"
}

configure_graph_extraction_api() {
    local payload
    payload="$(
        jq -n \
            --argjson maxTokens "$GRAPH_EXTRACTION_MAX_TOKENS" \
            --argjson charsPerCall "$GRAPH_LLM_CHARS_PER_CALL" \
            --argjson maxDocs "$GRAPH_LLM_MAX_DOCS_PER_CALL" \
            --argjson maxSegmentChars "$GRAPH_LLM_MAX_CHARS_PER_DOC_SEGMENT" \
            --argjson parallelism "$GRAPH_LLM_PARALLELISM" \
            --argjson timeoutSeconds "$GRAPH_LLM_CALL_TIMEOUT_SECONDS" \
            --argjson compactPrompt "$GRAPH_LOCAL_COMPACT_PROMPT" \
            --argjson localChars "$GRAPH_LOCAL_CHARS_PER_LLM_CALL" \
            --argjson localSegmentChars "$GRAPH_LOCAL_MAX_CHARS_PER_DOC_SEGMENT" \
            --argjson localEntities "$GRAPH_LOCAL_MAX_OUTPUT_ENTITIES" \
            --argjson localRelationships "$GRAPH_LOCAL_MAX_OUTPUT_RELATIONSHIPS" \
            --argjson fallbackEnabled "$GRAPH_LOCAL_FALLBACK_ENABLED" \
            --argjson fallbackMaxEntities "$GRAPH_LOCAL_FALLBACK_MAX_ENTITIES" \
            --argjson graphNodeBatch "$GRAPH_NODE_PERSIST_BATCH_SIZE" \
            --argjson generateMissingNodeEmbeddings "$GRAPH_GENERATE_MISSING_NODE_EMBEDDINGS" \
            --argjson crawlMaxConcurrentJobs "$CRAWL_MAX_CONCURRENT_JOBS" \
            --argjson crawlQueueCapacity "$CRAWL_QUEUE_CAPACITY" \
            --argjson crawlMemoryWait "$CRAWL_MEMORY_WAIT_THRESHOLD_PERCENT" \
            --argjson crawlMemoryCritical "$CRAWL_MEMORY_CRITICAL_THRESHOLD_PERCENT" \
            --argjson crawlMemoryTimeout "$CRAWL_MEMORY_WAIT_TIMEOUT_SECONDS" \
            --argjson crawlNativeCleanup "$CRAWL_NATIVE_MEMORY_CLEANUP_ENABLED" \
            --argjson crawlNativeCleanupPasses "$CRAWL_NATIVE_MEMORY_CLEANUP_PASSES" \
            --argjson crawlNativeWait "$CRAWL_NATIVE_MEMORY_WAIT_THRESHOLD_PERCENT" \
            --argjson crawlNativeCritical "$CRAWL_NATIVE_MEMORY_CRITICAL_THRESHOLD_PERCENT" \
            --argjson crawlBatch "$CRAWL_GRAPH_EXTRACTION_BATCH_SIZE" \
            --argjson crawlBackgroundThreads "$CRAWL_BACKGROUND_GRAPH_THREADS" \
            --argjson crawlSourceThreads "$CRAWL_SOURCE_LOAD_PARALLELISM" \
            --argjson crawlChunkThreads "$CRAWL_CHUNKING_PARALLELISM" \
            --argjson crawlGraphThreads "$CRAWL_GRAPH_EXTRACTION_PARALLELISM" \
            --argjson crawlGraphTargetChars "$CRAWL_GRAPH_EXTRACTION_TARGET_CHARS_PER_BATCH" \
            --argjson crawlChunkTargetChars "$CRAWL_CHUNKING_TARGET_CHARS_PER_TASK" \
            --argjson crawlVectorBatch "$CRAWL_VECTOR_BATCH_SIZE" \
            --argjson crawlPostParallel "$CRAWL_POST_PROCESS_PARALLEL" \
            --argjson crawlSkipConstructorEmbedding "$CRAWL_GRAPH_CONSTRUCTOR_SKIP_EMBEDDING" \
            --argjson crawlPersistMatrix "$CRAWL_GRAPH_CONSTRUCTOR_PERSIST_MATRIX_GRAPH" \
            --argjson crawlRetainGraph "$CRAWL_RETAIN_RESULT_GRAPH" \
            --argjson crawlCostSort "$CRAWL_COST_SORT_CHUNKS" \
            --argjson compactionCache "$COMPACTION_EMBEDDING_CACHE_SIZE" \
            --argjson compactionNativeThreshold "$COMPACTION_EMBEDDING_NATIVE_MEMORY_THRESHOLD_PERCENT" \
            --argjson compactionBlockSize "$COMPACTION_MAX_EMBEDDING_BLOCK_SIZE" \
            --argjson compactionCleanupPasses "$COMPACTION_NATIVE_MEMORY_CLEANUP_PASSES" \
            '{
                extractionMaxTokens: $maxTokens,
                llmCharsPerCall: $charsPerCall,
                llmMaxDocsPerCall: $maxDocs,
                llmMaxCharsPerDocSegment: $maxSegmentChars,
                llmParallelism: $parallelism,
                llmCallTimeoutSeconds: $timeoutSeconds,
                localStagingCompactPromptEnabled: $compactPrompt,
                localStagingCharsPerLlmCall: $localChars,
                localStagingMaxCharsPerDocSegment: $localSegmentChars,
                localStagingMaxOutputEntities: $localEntities,
                localStagingMaxOutputRelationships: $localRelationships,
                localStagingFallbackEnabled: $fallbackEnabled,
                localStagingFallbackMaxEntities: $fallbackMaxEntities,
                graphNodePersistBatchSize: $graphNodeBatch,
                graphGenerateMissingNodeEmbeddings: $generateMissingNodeEmbeddings,
                crawlMaxConcurrentJobs: $crawlMaxConcurrentJobs,
                crawlQueueCapacity: $crawlQueueCapacity,
                crawlMemoryWaitThresholdPercent: $crawlMemoryWait,
                crawlMemoryCriticalThresholdPercent: $crawlMemoryCritical,
                crawlMemoryWaitTimeoutSeconds: $crawlMemoryTimeout,
                crawlNativeMemoryCleanupEnabled: $crawlNativeCleanup,
                crawlNativeMemoryCleanupPasses: $crawlNativeCleanupPasses,
                crawlNativeMemoryWaitThresholdPercent: $crawlNativeWait,
                crawlNativeMemoryCriticalThresholdPercent: $crawlNativeCritical,
                crawlGraphExtractionBatchSize: $crawlBatch,
                crawlBackgroundGraphThreads: $crawlBackgroundThreads,
                crawlSourceLoadParallelism: $crawlSourceThreads,
                crawlChunkingParallelism: $crawlChunkThreads,
                crawlGraphExtractionParallelism: $crawlGraphThreads,
                crawlGraphExtractionTargetCharsPerBatch: $crawlGraphTargetChars,
                crawlChunkingTargetCharsPerTask: $crawlChunkTargetChars,
                crawlVectorBatchSize: $crawlVectorBatch,
                crawlPostProcessParallel: $crawlPostParallel,
                crawlGraphConstructorSkipEmbedding: $crawlSkipConstructorEmbedding,
                crawlGraphConstructorPersistMatrixGraph: $crawlPersistMatrix,
                crawlRetainResultGraph: $crawlRetainGraph,
                crawlCostSortChunks: $crawlCostSort,
                compactionEmbeddingCacheSize: $compactionCache,
                compactionEmbeddingNativeMemoryThresholdPercent: $compactionNativeThreshold,
                compactionMaxEmbeddingBlockSize: $compactionBlockSize,
                compactionNativeMemoryCleanupPasses: $compactionCleanupPasses
            }'
    )"
    echo "$payload" > "$LOG_DIR/graph-extraction-config-request.json"
    curl -fsS --max-time 60 \
        -X PATCH "$APP_URL/api/graph-extraction/config" \
        -H 'Content-Type: application/json' \
        --data-binary "$payload" \
        > "$LOG_DIR/graph-extraction-config-response.json"
    echo "Configured UI graph extraction runtime: maxTokens=$GRAPH_EXTRACTION_MAX_TOKENS localCompact=$GRAPH_LOCAL_COMPACT_PROMPT localChars=$GRAPH_LOCAL_CHARS_PER_LLM_CALL fallback=$GRAPH_LOCAL_FALLBACK_ENABLED graphNodeBatch=$GRAPH_NODE_PERSIST_BATCH_SIZE llmParallelism=$GRAPH_LLM_PARALLELISM crawlGraphThreads=$CRAWL_GRAPH_EXTRACTION_PARALLELISM crawlVectorBatch=$CRAWL_VECTOR_BATCH_SIZE"
}

configure_batch_config_api() {
    local payload
    payload="$(
        jq -n \
            --argjson optimalBatchSize "$EMBEDDING_OPTIMAL_BATCH_SIZE" \
            --argjson maxBatchSize "$EMBEDDING_MAX_BATCH_SIZE" \
            --argjson singleDspPlan "$EMBEDDING_SINGLE_DSP_PLAN" \
            --argjson dspPlanBatchSize "$EMBEDDING_DSP_PLAN_BATCH_SIZE" \
            '{
                optimalBatchSize: $optimalBatchSize,
                maxBatchSize: $maxBatchSize,
                singleDspPlan: $singleDspPlan,
                dspPlanBatchSize: $dspPlanBatchSize,
                memoryScaleFactor: -1.0
            }'
    )"
    echo "$payload" > "$LOG_DIR/batch-config-request.json"
    curl -fsS --max-time 60 \
        -X PUT "$APP_URL/api/embeddings/batch-config/global" \
        -H 'Content-Type: application/json' \
        --data-binary "$payload" \
        > "$LOG_DIR/batch-config-response.json"
    echo "Configured embedding batch config via API: optimal=$EMBEDDING_OPTIMAL_BATCH_SIZE max=$EMBEDDING_MAX_BATCH_SIZE singleDspPlan=$EMBEDDING_SINGLE_DSP_PLAN dspPlanBatchSize=$EMBEDDING_DSP_PLAN_BATCH_SIZE"
}

configure_embedding_model_api() {
    local registry_before="$LOG_DIR/embedding-model-registry-before-crawl.json"
    local registry_after="$LOG_DIR/embedding-model-registry-after-crawl-config.json"
    local active_file="$LOG_DIR/embedding-model-active-after-activate.json"
    local expected_dim="$EMBEDDING_EXPECTED_DIMENSIONS"
    local required_quant
    required_quant="$(normalize_quantization_type "$EMBEDDING_QUANTIZATION_TYPE")"

    echo "Configuring embedding model through staging/app APIs: model=$EMBEDDING_MODEL_ID expectedDim=$expected_dim requireOptimized=$EMBEDDING_REQUIRE_OPTIMIZED quantization=$required_quant"

    http_json_to_file GET "$STAGING_URL/api/staging/registry/model/$EMBEDDING_MODEL_ID" "" "$registry_before" "$MODEL_API_TIMEOUT_SECONDS"
    json_file_must "$registry_before" \
        --arg model "$EMBEDDING_MODEL_ID" '.model_id == $model and (.type == "dense_encoder" or .type == "encoder")' \
        "staging registry entry is not the requested dense encoder: $EMBEDDING_MODEL_ID"

    if [ "$expected_dim" -gt 0 ]; then
        json_file_must "$registry_before" \
            --argjson expected "$expected_dim" '(.metadata.embedding_dim // 0) == $expected' \
            "staging registry entry has the wrong embedding dimension for $EMBEDDING_MODEL_ID"
    fi

    http_json_to_file POST "$STAGING_URL/api/staging/models/$EMBEDDING_MODEL_ID/activate" "{}" "$LOG_DIR/embedding-model-activate-response.json" "$MODEL_API_TIMEOUT_SECONDS"
    json_file_must "$LOG_DIR/embedding-model-activate-response.json" '.success == true' \
        "staging failed to activate embedding model $EMBEDDING_MODEL_ID"

    http_json_to_file GET "$STAGING_URL/api/staging/active" "" "$active_file" "$MODEL_API_TIMEOUT_SECONDS"
    json_file_must "$active_file" \
        --arg model "$EMBEDDING_MODEL_ID" '(.active.dense_encoder // .active.encoder // "") == $model' \
        "staging active dense encoder is not $EMBEDDING_MODEL_ID"

    local optimized
    local current_quant
    local needs_optimization="false"
    optimized="$(jq -r '.metadata.optimized // false' "$registry_before")"
    current_quant="$(normalize_quantization_type "$(jq -r '.metadata.optimization_config.quantization_type // ""' "$registry_before")")"

    if [ "$EMBEDDING_REQUIRE_OPTIMIZED" = "true" ]; then
        if [ "$optimized" != "true" ] || { [ -n "$required_quant" ] && [ "$current_quant" != "$required_quant" ]; }; then
            needs_optimization="true"
        fi
    fi

    if [ "$needs_optimization" = "true" ]; then
        local optimize_payload
        optimize_payload="$(
            jq -n \
                --arg modelId "$EMBEDDING_MODEL_ID" \
                --arg profile "$EMBEDDING_OPTIMIZATION_PROFILE" \
                --arg quantizationType "$required_quant" \
                --argjson force "$EMBEDDING_OPTIMIZATION_FORCE" \
                '{
                    modelId: $modelId,
                    profile: $profile,
                    quantizationType: $quantizationType,
                    force: $force,
                    createBackup: true,
                    dryRun: false,
                    maxIterations: 3
                }'
        )"
        echo "$optimize_payload" > "$LOG_DIR/embedding-model-optimize-request.json"
        http_json_to_file POST "$STAGING_URL/api/compiler/optimize" "$optimize_payload" "$LOG_DIR/embedding-model-optimize-response.json" "$MODEL_API_TIMEOUT_SECONDS"
        json_file_must "$LOG_DIR/embedding-model-optimize-response.json" '.success == true and (.status == "COMPLETED" or .status == null)' \
            "staging compiler optimization failed for $EMBEDDING_MODEL_ID"
        http_json_to_file GET "$STAGING_URL/api/staging/registry/model/$EMBEDDING_MODEL_ID" "" "$registry_after" "$MODEL_API_TIMEOUT_SECONDS"
    else
        cp "$registry_before" "$registry_after"
    fi

    if [ "$EMBEDDING_REQUIRE_OPTIMIZED" = "true" ]; then
        local optimized_after
        local quant_after
        optimized_after="$(jq -r '.metadata.optimized // false' "$registry_after")"
        quant_after="$(normalize_quantization_type "$(jq -r '.metadata.optimization_config.quantization_type // ""' "$registry_after")")"
        if [ "$optimized_after" != "true" ] || { [ -n "$required_quant" ] && [ "$quant_after" != "$required_quant" ]; }; then
            echo "FATAL: $EMBEDDING_MODEL_ID is not recorded as optimized with $required_quant after staging configuration." >&2
            echo "Registry file: $registry_after" >&2
            jq '{model_id,type,path,model_file,metadata}' "$registry_after" >&2
            exit 1
        fi
    fi

    http_json_to_file POST "$APP_URL/api/models/registry/refresh" "{}" "$LOG_DIR/embedding-model-app-registry-refresh-response.json" "$MODEL_API_TIMEOUT_SECONDS"
    json_file_must "$LOG_DIR/embedding-model-app-registry-refresh-response.json" '.success == true' \
        "app failed to refresh model registry before switching embeddings"

    http_json_to_file POST "$APP_URL/api/models/embedding/switch/$EMBEDDING_MODEL_ID" "{}" "$LOG_DIR/embedding-model-app-switch-response.json" "$MODEL_API_TIMEOUT_SECONDS"
    json_file_must "$LOG_DIR/embedding-model-app-switch-response.json" \
        --arg model "$EMBEDDING_MODEL_ID" '.success == true and .currentModel == $model' \
        "app failed to switch active embedding model to $EMBEDDING_MODEL_ID"

    http_json_to_file GET "$APP_URL/api/models/embedding/status" "" "$LOG_DIR/embedding-model-status-before-crawl.json" "$MODEL_API_TIMEOUT_SECONDS"
    json_file_must "$LOG_DIR/embedding-model-status-before-crawl.json" \
        --arg model "$EMBEDDING_MODEL_ID" \
        --argjson expected "$expected_dim" \
        '.modelId == $model
         and .initialized == true
         and (.source // "") != "FAILED"
         and ((.dimensions // 0) == $expected)
         and ((.subprocessRunning // true) == true)
         and ((.subprocessModelLoaded // true) == true)' \
        "app embedding status is not ready for $EMBEDDING_MODEL_ID"

    echo "Configured embedding model via API: model=$EMBEDDING_MODEL_ID dim=$expected_dim optimized=$EMBEDDING_REQUIRE_OPTIMIZED quantization=$required_quant"
}

print_recent_documents() {
    local job_json="$1"
    jq -r '
        (.documentProgress // [])
        | sort_by(.updatedAt // .completedAt // .startedAt // "")
        | reverse
        | .[:5][]
        | "  doc " + ((.status // "UNKNOWN") | tostring)
          + " phase=" + ((.phase // "") | tostring)
          + " chunks=" + ((.chunksCreated // 0) | tostring)
          + " ent=" + ((.entitiesExtracted // 0) | tostring)
          + " rels=" + ((.relationshipsExtracted // 0) | tostring)
          + " nodes=" + ((.graphNodesCreated // 0) | tostring)
          + " edges=" + ((.graphEdgesCreated // 0) | tostring)
          + " file=" + ((.fileName // .sourcePath // .documentKey // "") | tostring)
    ' <<< "$job_json"
}

print_recent_events() {
    local job_json="$1"
    jq -r '
        (.recentEvents // [])
        | reverse
        | .[:5][]
        | "  event " + ((.level // "INFO") | tostring)
          + " phase=" + ((.phase // "") | tostring)
          + " " + ((.message // "") | tostring)
          + (if (.details // "") == "" then "" else " - " + (.details | tostring) end)
    ' <<< "$job_json"
}

execution_error_context() {
    local job_json="$1"
    jq -r --arg pattern "$EXECUTION_ERROR_PATTERN" '
        (
          [(.recentEvents // [])[]
            | select((((.level // "") | tostring | ascii_upcase) == "ERROR"
                      or ((.level // "") | tostring | ascii_upcase) == "FATAL")
                     and (((.message // "") | tostring) + " " + ((.details // "") | tostring) | test($pattern; "i")))]
          + [(.documentProgress // [])[]
            | select((((.status // "") | tostring) | test("FAILED|ERROR"; "i"))
                     and (((.message // "") | tostring) + " " + ((.errorMessage // "") | tostring) | test($pattern; "i")))]
          + [(.errors // [])[]
            | select((tostring) | test($pattern; "i"))]
        )
        | .[-20:]
        | .[]
        | @json
    ' <<< "$job_json"
}

collect_execution_log_context() {
    local job_id="$1"
    local out_file="$LOG_DIR/latest-crawl-execution-error-log-context.txt"
    local hs_err_files
    hs_err_files="$(find "$PROJECT_DIR" "$LOG_DIR" -maxdepth 2 -type f -name 'hs_err*.log' -print 2>/dev/null || true)"

    shopt -s nullglob
    local log_files=(
        "$LOG_DIR/app.out.log"
        "$LOG_DIR/app.err.log"
        "$LOG_DIR/staging.out.log"
        "$LOG_DIR/staging.err.log"
        "$LOG_DIR/staging.log"
        "$LOG_DIR"/*embedding*.log
        "$LOG_DIR"/*subprocess*.log
    )
    shopt -u nullglob

    {
        echo "crawl_job_id=$job_id"
        echo "project_dir=$PROJECT_DIR"
        echo "log_dir=$LOG_DIR"
        echo "status_snapshot=$LOG_DIR/latest-crawl-status-at-execution-error.json"
        echo "execution_event_context=$LOG_DIR/latest-crawl-execution-error-context.jsonl"
        echo "crawl_request=$LOG_DIR/latest-crawl-request.json"
        echo "crawl_status=$LOG_DIR/latest-crawl-status.json"
        echo "embedding_status=$LOG_DIR/embedding-model-status-before-crawl.json"
        echo "embedding_registry_before=$LOG_DIR/embedding-model-registry-before-crawl.json"
        echo "embedding_registry_after=$LOG_DIR/embedding-model-registry-after-crawl-config.json"
        echo "embedding_switch_response=$LOG_DIR/embedding-model-app-switch-response.json"
        echo "execution_error_pattern=$EXECUTION_ERROR_PATTERN"
        echo
        echo "log_files:"
        local file
        for file in "${log_files[@]}"; do
            [ -f "$file" ] && echo "$file"
        done
        if [ -n "$hs_err_files" ]; then
            printf '%s\n' "$hs_err_files"
        fi
        echo
        for file in "${log_files[@]}"; do
            [ -f "$file" ] || continue
            echo "===== $file ====="
            rg -n -i -C 5 "$EXECUTION_ERROR_PATTERN" "$file" 2>/dev/null | sed -n '1,260p' || true
            echo
        done
        if [ -n "$hs_err_files" ]; then
            local hs_file
            while IFS= read -r hs_file; do
                [ -f "$hs_file" ] || continue
                echo "===== $hs_file ====="
                sed -n '1,220p' "$hs_file" || true
                echo
            done <<< "$hs_err_files"
        fi
    } > "$out_file"
    echo "$out_file"
}

stop_crawl_for_execution_error() {
    local job_id="$1"
    local job_json="$2"
    local context_file="$LOG_DIR/latest-crawl-execution-error-context.jsonl"
    local status_file="$LOG_DIR/latest-crawl-status-at-execution-error.json"
    local cancel_file="$LOG_DIR/latest-crawl-cancel-after-execution-error.json"

    echo "$job_json" > "$status_file"
    execution_error_context "$job_json" > "$context_file"
    curl -fsS --max-time 60 -X POST "$APP_URL/api/unified-crawl/jobs/$job_id/cancel" \
        > "$cancel_file" || true
    local log_context
    log_context="$(collect_execution_log_context "$job_id")"

    echo "Execution error detected; crawl cancelled." >&2
    echo "Status snapshot: $status_file" >&2
    echo "Event context:   $context_file" >&2
    echo "Cancel response:  $cancel_file" >&2
    echo "Log context:      $log_context" >&2
    exit 1
}

require_command curl
require_command jq

if [ ! -d "$DATA_DIR" ]; then
    echo "FATAL: FP&A data directory not found: $DATA_DIR" >&2
    exit 1
fi

configure_device_routing_json
wait_for_resources "before-runtime-start"
"$PROJECT_DIR/start"
configure_graph_extraction_api
configure_batch_config_api
configure_embedding_model_api

echo "Cleaning finished in-memory crawl jobs."
curl -fsS --max-time 20 -X POST "$APP_URL/api/unified-crawl/jobs/cleanup" \
    > "$LOG_DIR/latest-crawl-cleanup.json" || true

active_jobs="$(http_get "/api/unified-crawl/jobs/active")"
active_count="$(jq 'length' <<< "$active_jobs")"
if [ "$active_count" -gt 0 ]; then
    echo "An active crawl is already running. Not launching a duplicate."
    jq -r '.[] | "  jobId=\(.jobId) status=\(.status) progress=\(.progressPercent)% phase=\(.currentPhase)"' <<< "$active_jobs"
    echo "$active_jobs" > "$LOG_DIR/latest-active-crawls.json"
    exit 2
fi

if [ "$CLEAR_GRAPH_BEFORE_CRAWL" = "true" ]; then
    wait_for_resources "before-graph-clear"
    echo "Clearing fact-sheet scoped graph before fresh crawl: factSheetId=$FACT_SHEET_ID"
    curl -fsS --max-time 60 -X DELETE "$APP_URL/api/fact-sheets/$FACT_SHEET_ID/graph" \
        > "$LOG_DIR/latest-crawl-graph-clear.json"
fi

payload="$(
    jq -n \
        --arg name "$CRAWL_NAME" \
        --arg dataDir "$DATA_DIR" \
        --arg schemaPresetId "$SCHEMA_PRESET_ID" \
        --arg collectionName "$COLLECTION_NAME" \
        --arg graphLlmProvider "$GRAPH_LLM_PROVIDER" \
        --argjson factSheetId "$FACT_SHEET_ID" \
        --argjson graphMaxTokens "$GRAPH_EXTRACTION_MAX_TOKENS" \
        --argjson embeddingBatchSize "$CRAWL_EMBEDDING_BATCH_SIZE" \
        --argjson maxEmbeddingBatchSize "$CRAWL_MAX_EMBEDDING_BATCH_SIZE" \
        --argjson adaptiveBatching "$CRAWL_ADAPTIVE_BATCHING" \
        '{
            name: $name,
            factSheetId: $factSheetId,
            sources: [
                {
                    label: "FP&A workflow artifacts 2026-05",
                    sourceType: "DIRECTORY",
                    pathOrUrl: $dataDir,
                    maxDepth: 3,
                    maxDocuments: 1000,
                    excludePatterns: ["*.m4v", "*.mp4", "*.mov", "*.BACKUP_*"]
                }
            ],
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
        }'
)"

echo "$payload" > "$LOG_DIR/latest-crawl-request.json"

wait_for_resources "before-crawl-start"
echo "Starting unified FP&A crawl."
response="$(http_post_json "/api/unified-crawl/start" "$payload")"
echo "$response" | tee "$LOG_DIR/latest-crawl-start-response.json"

job_id="$(jq -r '.jobId // empty' <<< "$response")"
if [ -z "$job_id" ]; then
    echo "FATAL: crawl start response did not include a jobId" >&2
    exit 1
fi

echo "$job_id" > "$LOG_DIR/latest-crawl-job.id"
echo "Crawl job ID: $job_id"
echo "Request: $LOG_DIR/latest-crawl-request.json"
echo "Status:  $LOG_DIR/latest-crawl-status.json"

if [ "$WATCH_CRAWL" != "true" ]; then
    echo "WATCH_CRAWL=false, leaving crawl running."
    exit 0
fi

last_doc_signature=""
last_event_signature=""
last_progress_signature=""
poll_delay="$POLL_SECONDS"
last_resource_status_epoch=0

while true; do
    if ! job_json="$(http_get "/api/unified-crawl/jobs/$job_id")"; then
        echo "WARN: failed to poll crawl job; backing off ${poll_delay}s" >&2
        sleep "$poll_delay"
        if [ "$poll_delay" -lt "$RESOURCE_POLL_MAX_SECONDS" ]; then
            poll_delay=$((poll_delay * 2))
            if [ "$poll_delay" -gt "$RESOURCE_POLL_MAX_SECONDS" ]; then
                poll_delay="$RESOURCE_POLL_MAX_SECONDS"
            fi
        fi
        continue
    fi
    echo "$job_json" > "$LOG_DIR/latest-crawl-status.json"

    status="$(jq -r '.status // "UNKNOWN"' <<< "$job_json")"
    progress="$(jq -r '.progressPercent // 0' <<< "$job_json")"
    phase="$(jq -r '.currentPhase // ""' <<< "$job_json")"
    discovered="$(jq -r '.documentsDiscovered // 0' <<< "$job_json")"
    loaded="$(jq -r '.documentsLoaded // 0' <<< "$job_json")"
    chunks="$(jq -r '.chunksCreated // 0' <<< "$job_json")"
    graph_chunks="$(jq -r '(.graphChunksProcessed // 0 | tostring) + "/" + (.graphChunksTotal // 0 | tostring)' <<< "$job_json")"
    embedded="$(jq -r '.chunksEmbedded // 0' <<< "$job_json")"
    entities="$(jq -r '.entitiesExtracted // 0' <<< "$job_json")"
    rels="$(jq -r '.relationshipsExtracted // 0' <<< "$job_json")"
    errors="$(jq -r '.errorCount // 0' <<< "$job_json")"
    heap_mib="$(bytes_to_mib "$(jq -r '.heapUsedBytes // 0' <<< "$job_json")")"
    rss_mib="$(bytes_to_mib "$(jq -r '.processTreeRssBytes // 0' <<< "$job_json")")"
    app_rss_mib="$(bytes_to_mib "$(jq -r '.processRssBytes // 0' <<< "$job_json")")"
    child_rss_mib="$(bytes_to_mib "$(jq -r '.childProcessRssBytes // 0' <<< "$job_json")")"
    embed_rss_mib="$(bytes_to_mib "$(jq -r '.embeddingSubprocessRssBytes // 0' <<< "$job_json")")"
    agent_rss_mib="$(bytes_to_mib "$(jq -r '((.otherChildProcessRssBytes // ((.childProcessRssBytes // 0) - (.embeddingSubprocessRssBytes // 0))) | if . < 0 then 0 else . end)' <<< "$job_json")")"
    native_mib="$(bytes_to_mib "$(jq -r '.nativePhysicalBytes // 0' <<< "$job_json")")"
    vector_batches="$(jq -r '(.vectorBatchesCompleted // 0 | tostring) + "/" + (.vectorBatchesTotal // 0 | tostring)' <<< "$job_json")"
    embedding_batch="$(jq -r '.embeddingBatchSize // 0' <<< "$job_json")"
    model_opt_batch="$(jq -r '.embeddingModelOptimalBatchSize // 0' <<< "$job_json")"
    model_max_batch="$(jq -r '.embeddingModelMaxBatchSize // 0' <<< "$job_json")"
    current_batch="$(jq -r '.currentBatchSize // 0' <<< "$job_json")"
    batch_step="$(jq -r '.currentBatchStep // ""' <<< "$job_json")"
    graph_nodes="$(jq -r '.graph.totalNodeCount // 0' <<< "$job_json")"
    graph_edges="$(jq -r '.graph.relationshipCount // 0' <<< "$job_json")"
    current_file="$(jq -r '.currentFile // ""' <<< "$job_json")"

    printf '[%s] status=%s progress=%s%% phase=%s disc=%s loaded=%s chunks=%s graphChunks=%s vectorBatches=%s embedded=%s ent=%s rels=%s errors=%s heap=%sMiB rss=%sMiB appRss=%sMiB childRss=%sMiB agentRss=%sMiB embedRss=%sMiB native=%sMiB batch=%s modelBatch=%s/%s graph(n=%s e=%s)\n' \
        "$(date +%H:%M:%S)" "$status" "$progress" "$phase" "$discovered" "$loaded" "$chunks" "$graph_chunks" "$vector_batches" "$embedded" "$entities" "$rels" "$errors" "$heap_mib" "$rss_mib" "$app_rss_mib" "$child_rss_mib" "$agent_rss_mib" "$embed_rss_mib" "$native_mib" "$embedding_batch" "$model_opt_batch" "$model_max_batch" "$graph_nodes" "$graph_edges"
    if [ -n "$current_file" ]; then
        echo "  current $current_file"
    fi
    if [ -n "$batch_step" ] && [ "$batch_step" != "null" ]; then
        echo "  batchStep $batch_step currentBatchSize=$current_batch"
    fi

    now_epoch="$(date +%s)"
    if [ "$RESOURCE_CHECK_ENABLED" = "true" ] && [ $((now_epoch - last_resource_status_epoch)) -ge "$RESOURCE_STATUS_INTERVAL_SECONDS" ]; then
        print_resource_snapshot "crawl"
        last_resource_status_epoch="$now_epoch"
    fi

    doc_count="$(jq '(.documentProgress // []) | length' <<< "$job_json")"
    doc_signature="$(jq -c '
        (.documentProgress // [])
        | sort_by(.updatedAt // .completedAt // .startedAt // "")
        | reverse
        | .[:8]
        | map([.documentKey, .status, .phase, .chunksCreated, .entitiesExtracted, .relationshipsExtracted, .graphNodesCreated, .graphEdgesCreated, .message, .errorMessage])
    ' <<< "$job_json")"
    if [ "$doc_signature" != "$last_doc_signature" ] && [ "$doc_count" -gt 0 ]; then
        echo "  documentProgress count=$doc_count"
        print_recent_documents "$job_json"
        last_doc_signature="$doc_signature"
    fi

    event_count="$(jq '(.recentEvents // []) | length' <<< "$job_json")"
    event_signature="$(jq -c '
        (.recentEvents // [])
        | reverse
        | .[:8]
        | map([.timestamp, .phase, .level, .message, .details, .progressPercent])
    ' <<< "$job_json")"
    if [ "$event_signature" != "$last_event_signature" ] && [ "$event_count" -gt 0 ]; then
        print_recent_events "$job_json"
        last_event_signature="$event_signature"
    fi

    if [ -n "$(execution_error_context "$job_json")" ]; then
        stop_crawl_for_execution_error "$job_id" "$job_json"
    fi

    case "$status" in
        COMPLETED)
            echo "Crawl completed."
            curl -fsS --max-time 20 "$APP_URL/api/unified-crawl/graph-stats?factSheetId=$FACT_SHEET_ID" \
                | tee "$LOG_DIR/latest-crawl-graph-stats.json" \
                | jq '{entityCount, relationshipCount, totalNodeCount, documentCount, snippetCount, tableCount, edgeTypeCounts}'
            exit 0
            ;;
        FAILED|CANCELLED)
            echo "Crawl ended with status=$status" >&2
            jq -r '.errorMessage // empty, (.errors // [])[]?' <<< "$job_json" >&2
            exit 1
            ;;
    esac

    progress_signature="${status}|${phase}|${progress}|${graph_chunks}|${embedded}|${entities}|${rels}|${errors}|${batch_step}"
    if [ "$progress_signature" != "$last_progress_signature" ]; then
        poll_delay="$POLL_SECONDS"
        last_progress_signature="$progress_signature"
    elif [ "$poll_delay" -lt "$RESOURCE_POLL_MAX_SECONDS" ]; then
        poll_delay=$((poll_delay * 2))
        if [ "$poll_delay" -gt "$RESOURCE_POLL_MAX_SECONDS" ]; then
            poll_delay="$RESOURCE_POLL_MAX_SECONDS"
        fi
        echo "  no status delta; backing off next poll to ${poll_delay}s"
    fi

    sleep "$poll_delay"
done
