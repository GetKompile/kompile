#!/bin/bash
# test-llm-inference.sh — LFM instruct model inference integration test
#
# Usage:
#   ./test-llm-inference.sh              # servers already running
#   ./test-llm-inference.sh --start      # start servers first, shut down after
#   ./test-llm-inference.sh --no-kill    # start servers, leave running after
#
# Requires: curl, python3, lsof

set -euo pipefail

MODEL_ID="lfm2.5-1.2b-instruct"
STAGING_URL="http://localhost:8090"
SUBPROCESS_URL="http://localhost:8091"
APP_URL="http://localhost:8080"
LOAD_TIMEOUT=300   # seconds to wait for model load
GEN_TIMEOUT=120    # seconds per generate request
STAGING_LOG=/home/agibsonccc/Documents/GitHub/kompile/kompile-app/kompile-app-main/data/logs/staging-server.out.log

STARTED_SERVERS=false
KILL_AFTER=true
PASSED=0
FAILED=0
ERRORS=""

# ── Helpers ──────────────────────────────────────────────────────────────────

red()   { printf "\033[31m%s\033[0m\n" "$*"; }
green() { printf "\033[32m%s\033[0m\n" "$*"; }
yellow(){ printf "\033[33m%s\033[0m\n" "$*"; }
bold()  { printf "\033[1m%s\033[0m\n" "$*"; }

pass() {
    green "  PASS: $1"
    PASSED=$((PASSED + 1))
}

fail() {
    red "  FAIL: $1"
    FAILED=$((FAILED + 1))
    ERRORS="${ERRORS}\n  - $1"
}

cleanup() {
    if $STARTED_SERVERS && $KILL_AFTER; then
        yellow "\nShutting down servers..."
        for port in 8080 8090 8091; do
            pids=$(lsof -i :$port -t 2>/dev/null || true)
            [ -n "$pids" ] && kill $pids 2>/dev/null || true
        done
        sleep 2
        yellow "Servers stopped."
    fi

    echo ""
    bold "════════════════════════════════════════"
    bold "  Results: $PASSED passed, $FAILED failed"
    bold "════════════════════════════════════════"
    if [ $FAILED -gt 0 ]; then
        red "Failures:$ERRORS"
        exit 1
    else
        green "All tests passed."
        exit 0
    fi
}
trap cleanup EXIT

# ── Parse args ───────────────────────────────────────────────────────────────

for arg in "$@"; do
    case $arg in
        --start)   STARTED_SERVERS=true ;;
        --no-kill) STARTED_SERVERS=true; KILL_AFTER=false ;;
    esac
done

# ── Phase 0: Start servers if requested ──────────────────────────────────────

if $STARTED_SERVERS; then
    bold "Phase 0: Starting servers"
    bash "$(dirname "$0")/dev-restart.sh"
    RESTART_EXIT=$?
    if [ $RESTART_EXIT -ne 0 ]; then
        fail "dev-restart.sh failed (exit $RESTART_EXIT)"
        exit 1
    fi
fi

# ── Phase 1: Health checks ──────────────────────────────────────────────────

bold "\nPhase 1: Health checks"

# Wait up to 30s for app-main if it's still coming up
APP_UP=false
for i in $(seq 1 15); do
    if curl -sf "$APP_URL/actuator/health" 2>/dev/null | grep -q UP; then
        APP_UP=true
        break
    fi
    if [ $i -eq 1 ]; then
        echo "  Waiting for app-main on 8080..."
    fi
    sleep 2
done
if $APP_UP; then
    pass "app-main UP on 8080"
else
    fail "app-main not responding on 8080 (waited 30s)"
    exit 1
fi

# Wait up to 20s for staging
STAGING_UP=false
for i in $(seq 1 10); do
    if curl -sf "$STAGING_URL/api/llm/status" >/dev/null 2>&1; then
        STAGING_UP=true
        break
    fi
    if [ $i -eq 1 ]; then
        echo "  Waiting for model-staging on 8090..."
    fi
    sleep 2
done
if $STAGING_UP; then
    pass "model-staging UP on 8090"
else
    fail "model-staging not responding on 8090 (waited 20s)"
    exit 1
fi

# ── Phase 2: Load model ─────────────────────────────────────────────────────

bold "\nPhase 2: Load model ($MODEL_ID)"

# Trigger subprocess + model load via staging
LOAD_RESP=$(curl -sf -X POST "$STAGING_URL/api/llm/load" \
    -H "Content-Type: application/json" \
    -d "{\"modelId\":\"$MODEL_ID\",\"modelPath\":\"/home/agibsonccc/.kompile/models/llm-ggmls/$MODEL_ID\",\"kvCacheType\":\"STATIC\"}" 2>/dev/null || echo '{"error":"request failed"}')

echo "  Load response: $(echo "$LOAD_RESP" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("message",d.get("error","unknown")))' 2>/dev/null || echo "$LOAD_RESP")"

# Wait for subprocess to come up on 8091
echo "  Waiting for subprocess on 8091..."
for i in $(seq 1 60); do
    if lsof -i :8091 -t >/dev/null 2>&1; then
        break
    fi
    sleep 2
done

if ! lsof -i :8091 -t >/dev/null 2>&1; then
    fail "Subprocess never started on 8091"
    exit 1
fi
pass "Subprocess running on 8091"

# Load model on subprocess directly (staging may not forward correctly)
DIRECT_LOAD=$(curl -sf -X POST "$SUBPROCESS_URL/api/llm/load" \
    -H "Content-Type: application/json" \
    -d "{\"modelId\":\"$MODEL_ID\",\"stagingUrl\":\"$STAGING_URL\"}" 2>/dev/null || echo '{"error":"load request failed"}')

echo "  Direct load: $(echo "$DIRECT_LOAD" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(d.get("message",d.get("error","unknown")))' 2>/dev/null || echo "$DIRECT_LOAD")"

# Poll until loaded
echo "  Waiting for model load (timeout ${LOAD_TIMEOUT}s)..."
LOAD_START=$(date +%s)
while true; do
    ELAPSED=$(( $(date +%s) - LOAD_START ))
    if [ $ELAPSED -gt $LOAD_TIMEOUT ]; then
        fail "Model load timed out after ${LOAD_TIMEOUT}s"
        exit 1
    fi

    STATUS=$(curl -sf "$SUBPROCESS_URL/api/llm/status" 2>/dev/null || echo '{}')
    LOADED=$(echo "$STATUS" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("loaded",False))' 2>/dev/null || echo "False")
    LOADING=$(echo "$STATUS" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("loading",False))' 2>/dev/null || echo "False")
    PHASE=$(echo "$STATUS" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("loadingPhase",""))' 2>/dev/null || echo "")

    if [ "$LOADED" = "True" ]; then
        break
    fi

    # Print progress every 10s
    if [ $((ELAPSED % 10)) -eq 0 ] && [ $ELAPSED -gt 0 ]; then
        echo "  ... ${ELAPSED}s: loading=$LOADING phase=$PHASE"
    fi
    sleep 5
done

LOAD_DURATION=$(echo "$STATUS" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("loadDurationMs",-1))' 2>/dev/null || echo "-1")
pass "Model loaded in ${LOAD_DURATION}ms"

# ── Phase 3: Backend verification ───────────────────────────────────────────

bold "\nPhase 3: Backend verification"

SUBPROCESS_PID=$(lsof -i :8091 -t 2>/dev/null | tail -1)
CUDA_LIBS=$(cat /proc/$SUBPROCESS_PID/maps 2>/dev/null | grep -c "cuda" || echo "0")

if [ "$CUDA_LIBS" -gt 0 ]; then
    pass "CUDA backend confirmed ($CUDA_LIBS cuda libs loaded, PID $SUBPROCESS_PID)"
else
    fail "No CUDA libs found in subprocess (PID $SUBPROCESS_PID) — running on CPU"
fi

# Check GPU memory
GPU_INFO=$(nvidia-smi --query-gpu=index,name,memory.used,memory.total --format=csv,noheader 2>/dev/null || echo "nvidia-smi not available")
echo "  GPU status: $GPU_INFO"

# ── Phase 4: Inference tests ────────────────────────────────────────────────

bold "\nPhase 4: Inference tests"

run_generate_test() {
    local TEST_NAME="$1"
    local PROMPT="$2"
    local EXPECT_PATTERN="$3"
    local MAX_DURATION="${4:-$GEN_TIMEOUT}"

    echo ""
    yellow "  Test: $TEST_NAME"
    echo "  Prompt: $PROMPT"

    local RESP
    RESP=$(curl -sf --max-time "$MAX_DURATION" -X POST "$SUBPROCESS_URL/api/llm/generate" \
        -H "Content-Type: application/json" \
        -d "{\"prompt\":\"$PROMPT\"}" 2>/dev/null || echo '{"response":"ERROR: request failed or timed out","durationMs":-1}')

    local RESPONSE DURATION
    RESPONSE=$(echo "$RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("response",""))' 2>/dev/null || echo "ERROR: parse failed")
    DURATION=$(echo "$RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("durationMs",-1))' 2>/dev/null || echo "-1")

    echo "  Duration: ${DURATION}ms"
    echo "  Response (first 200 chars): ${RESPONSE:0:200}"

    # Check for errors
    if echo "$RESPONSE" | grep -qi "error\|exception\|failed"; then
        fail "$TEST_NAME — got error: ${RESPONSE:0:100}"

        # Dump relevant server logs from all known log locations
        echo "  --- Server log tail ---"
        for logfile in "$STAGING_LOG" /tmp/app-main.log; do
            if [ -f "$logfile" ]; then
                echo "  [$logfile]:"
                grep -i "autoregressive\|sizeAt\|rank: 0\|execution failed\|NullPointer\|OutOfMemory\|FATAL" "$logfile" 2>/dev/null | tail -5 || true
            fi
        done
        # Also check subprocess status
        SUB_STATUS=$(curl -sf "$SUBPROCESS_URL/api/llm/status" 2>/dev/null || echo "subprocess unreachable")
        echo "  Subprocess status: $SUB_STATUS"
        echo "  --- end ---"
        return
    fi

    # Check for expected content
    if [ -n "$EXPECT_PATTERN" ]; then
        if echo "$RESPONSE" | grep -qi "$EXPECT_PATTERN"; then
            pass "$TEST_NAME — contains expected pattern '$EXPECT_PATTERN' (${DURATION}ms)"
        else
            fail "$TEST_NAME — missing expected pattern '$EXPECT_PATTERN'"
        fi
    else
        # No pattern check, just verify non-empty non-error response
        if [ ${#RESPONSE} -gt 5 ]; then
            pass "$TEST_NAME — got ${#RESPONSE} chars (${DURATION}ms)"
        else
            fail "$TEST_NAME — response too short (${#RESPONSE} chars)"
        fi
    fi

    # Check for repetition (sign of broken sampling)
    local REPEAT_COUNT
    REPEAT_COUNT=$(echo "$RESPONSE" | grep -oi "is \*\*\|is London\|is Paris\|is the city" | wc -l)
    if [ "$REPEAT_COUNT" -gt 5 ]; then
        fail "$TEST_NAME — excessive repetition detected ($REPEAT_COUNT repeated phrases)"
    fi

    # Check for speed (1.2B on 4090 should be < 30s for 256 tokens)
    if [ "$DURATION" -gt 0 ] && [ "$DURATION" -gt 60000 ]; then
        fail "$TEST_NAME — too slow (${DURATION}ms > 60s) — possible CPU fallback or decode bug"
    fi
}

# Test 1: Simple greeting (fast, should hit EOS quickly)
run_generate_test "Simple greeting" "Hello" "" 30

# Test 2: Factual question (the one that was producing garbage)
run_generate_test "Capital of France" "What is the capital of France?" "paris" 60

# Test 3: Instruction following
run_generate_test "Instruction following" "List three colors." "red\|blue\|green\|yellow\|orange\|purple\|white\|black" 60

# Test 4: Chat-template formatted prompt (bypass auto-template)
run_generate_test "Explicit ChatML" \
    "<|im_start|>user\nWhat is 2+2?<|im_end|>\n<|im_start|>assistant\n" \
    "4" 60

# Test 5: Longer generation (stress test for decode loop stability)
run_generate_test "Longer generation" \
    "Write a short paragraph about the ocean." \
    "ocean\|water\|sea\|wave" 90
