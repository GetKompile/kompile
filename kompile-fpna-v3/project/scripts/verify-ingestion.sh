#!/usr/bin/env bash
# verify-ingestion.sh — End-to-end verification of FP&A document ingestion and graph extraction.
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
PASS=0
FAIL=0
WARN=0

check() {
    local label="$1"
    local status="$2"
    local detail="$3"
    case "$status" in
        PASS) echo "  PASS: $label — $detail"; ((PASS++)) ;;
        FAIL) echo "  FAIL: $label — $detail"; ((FAIL++)) ;;
        WARN) echo "  WARN: $label — $detail"; ((WARN++)) ;;
    esac
}

echo "=== FP&A Ingestion Verification ==="
echo "Target: $BASE_URL"
echo ""

# --- Check 1: Fact sheet count ---
echo "--- Check 1: Document facts ---"
FACTS_RESPONSE=$(curl -s "$BASE_URL/api/fact-sheets" --max-time 15 2>/dev/null || echo "")
if [ -n "$FACTS_RESPONSE" ]; then
    TOTAL_FACTS=$(echo "$FACTS_RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    facts = data if isinstance(data, list) else data.get('facts', data.get('content', []))
    print(len(facts))
except:
    print(0)
" 2>/dev/null || echo "0")
    if [ "$TOTAL_FACTS" -gt 0 ]; then
        check "Fact sheets" "PASS" "$TOTAL_FACTS facts indexed"
    else
        check "Fact sheets" "FAIL" "No facts found"
    fi
else
    check "Fact sheets" "FAIL" "Could not reach API"
fi

# --- Check 2: Document sources by type ---
echo ""
echo "--- Check 2: Source document types ---"
for ext in html xlsx md; do
    COUNT=$(echo "$FACTS_RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    facts = data if isinstance(data, list) else data.get('facts', data.get('content', []))
    count = sum(1 for f in facts if '.$ext' in str(f.get('source', f.get('metadata', {}).get('source', ''))).lower())
    print(count)
except:
    print(0)
" 2>/dev/null || echo "0")
    if [ "$COUNT" -gt 0 ]; then
        check "$ext documents" "PASS" "$COUNT found"
    else
        check "$ext documents" "WARN" "None found"
    fi
done

# --- Check 3: Knowledge graph nodes ---
echo ""
echo "--- Check 3: Knowledge graph nodes ---"
NODES_RESPONSE=$(curl -s "$BASE_URL/api/knowledge-graph/nodes?limit=200" --max-time 15 2>/dev/null || echo "")
if [ -n "$NODES_RESPONSE" ]; then
    NODE_SUMMARY=$(echo "$NODES_RESPONSE" | python3 -c "
import sys, json
from collections import Counter
try:
    data = json.load(sys.stdin)
    nodes = data if isinstance(data, list) else data.get('nodes', data.get('content', []))
    types = Counter(n.get('type', n.get('label', 'UNKNOWN')) for n in nodes)
    total = len(nodes)
    print(f'{total}')
    for t, c in types.most_common(15):
        print(f'    {t}: {c}')
except:
    print('0')
" 2>/dev/null || echo "0")

    TOTAL_NODES=$(echo "$NODE_SUMMARY" | head -1)
    if [ "$TOTAL_NODES" -gt 0 ]; then
        check "Knowledge graph nodes" "PASS" "$TOTAL_NODES nodes"
        echo "$NODE_SUMMARY" | tail -n +2
    else
        check "Knowledge graph nodes" "WARN" "No nodes yet (extraction may be in progress)"
    fi
else
    check "Knowledge graph nodes" "FAIL" "Could not reach API"
fi

# --- Check 4: Knowledge graph edges ---
echo ""
echo "--- Check 4: Knowledge graph edges ---"
EDGES_RESPONSE=$(curl -s "$BASE_URL/api/knowledge-graph/edges?limit=200" --max-time 15 2>/dev/null || echo "")
if [ -n "$EDGES_RESPONSE" ]; then
    EDGE_SUMMARY=$(echo "$EDGES_RESPONSE" | python3 -c "
import sys, json
from collections import Counter
try:
    data = json.load(sys.stdin)
    edges = data if isinstance(data, list) else data.get('edges', data.get('content', []))
    types = Counter(e.get('type', e.get('label', 'UNKNOWN')) for e in edges)
    total = len(edges)
    print(f'{total}')
    for t, c in types.most_common(15):
        print(f'    {t}: {c}')
except:
    print('0')
" 2>/dev/null || echo "0")

    TOTAL_EDGES=$(echo "$EDGE_SUMMARY" | head -1)
    if [ "$TOTAL_EDGES" -gt 0 ]; then
        check "Knowledge graph edges" "PASS" "$TOTAL_EDGES edges"
        echo "$EDGE_SUMMARY" | tail -n +2
    else
        check "Knowledge graph edges" "WARN" "No edges yet"
    fi
else
    check "Knowledge graph edges" "FAIL" "Could not reach API"
fi

# --- Check 5: Graph extraction config ---
echo ""
echo "--- Check 5: Graph extraction config ---"
GE_CONFIG=$(curl -s "$BASE_URL/api/graph-extraction/config" --max-time 10 2>/dev/null || echo "{}")
GE_ENABLED=$(echo "$GE_CONFIG" | python3 -c "import sys,json; print(json.load(sys.stdin).get('enabled', False))" 2>/dev/null || echo "false")
GE_PRESET=$(echo "$GE_CONFIG" | python3 -c "import sys,json; print(json.load(sys.stdin).get('activeSchemaPresetId', ''))" 2>/dev/null || echo "")

if [ "$GE_ENABLED" = "True" ] || [ "$GE_ENABLED" = "true" ]; then
    check "Graph extraction enabled" "PASS" "enabled=true"
else
    check "Graph extraction enabled" "FAIL" "enabled=false — run scripts/configure-graph-extraction.sh"
fi

if [ "$GE_PRESET" = "fpna-cpg-channel-v1" ]; then
    check "Schema preset" "PASS" "$GE_PRESET"
else
    check "Schema preset" "WARN" "Active preset: '${GE_PRESET:-none}' (expected: fpna-cpg-channel-v1)"
fi

# --- Check 6: RAG query test ---
echo ""
echo "--- Check 6: RAG query test ---"
RAG_RESPONSE=$(curl -s -X POST "$BASE_URL/api/rag/query" \
    -H "Content-Type: application/json" \
    -d '{"query": "What are the main FP&A close-cycle steps described in the process maps?", "maxResults": 3}' \
    --max-time 30 2>/dev/null || echo "")
if [ -n "$RAG_RESPONSE" ] && echo "$RAG_RESPONSE" | python3 -c "import sys,json; d=json.load(sys.stdin); assert d" 2>/dev/null; then
    DOC_COUNT=$(echo "$RAG_RESPONSE" | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    docs = d.get('documents', d.get('results', []))
    print(len(docs))
except:
    print(0)
" 2>/dev/null || echo "0")
    if [ "$DOC_COUNT" -gt 0 ]; then
        check "RAG query" "PASS" "$DOC_COUNT documents retrieved"
    else
        check "RAG query" "WARN" "Query returned empty results"
    fi
else
    check "RAG query" "WARN" "Could not complete RAG query"
fi

# --- Check 7: Embedding model status ---
echo ""
echo "--- Check 7: Embedding model status ---"
EMB_STATUS=$(curl -s "$BASE_URL/api/service-state/embedding" --max-time 10 2>/dev/null || echo "")
if echo "$EMB_STATUS" | grep -qi "ready\|active\|loaded"; then
    check "Embedding model" "PASS" "Model is loaded"
else
    check "Embedding model" "WARN" "Status unclear: ${EMB_STATUS:0:100}"
fi

# --- Summary ---
echo ""
echo "========================================="
echo "PASS: $PASS  |  FAIL: $FAIL  |  WARN: $WARN"
echo "========================================="

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
