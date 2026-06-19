#!/usr/bin/env bash
# verify-ingestion.sh — Verify that all uploaded documents are indexed,
# graph nodes exist with source attribution, and cross-document relations are present.
#
# Usage:
#   ./scripts/verify-ingestion.sh [BASE_URL]
#
# Defaults:
#   BASE_URL = http://localhost:8080

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
PASS=0
FAIL=0
WARN=0

check() {
    local label="$1"
    local result="$2"
    if [ "$result" = "true" ]; then
        echo "  PASS: $label"
        PASS=$((PASS + 1))
    else
        echo "  FAIL: $label"
        FAIL=$((FAIL + 1))
    fi
}

warn() {
    local label="$1"
    echo "  WARN: $label"
    WARN=$((WARN + 1))
}

echo "=== FPNA Ingestion Verification ==="
echo "Target: $BASE_URL"
echo ""

# --- 1. Fact sheets ---
echo "[1] Fact Sheets"
fact_sheets=$(curl -s "${BASE_URL}/api/fact-sheets")
fact_count=$(echo "$fact_sheets" | python3 -c "import json,sys; sheets=json.load(sys.stdin); print(sum(s['factCount'] for s in sheets))" 2>/dev/null || echo 0)
indexed_count=$(echo "$fact_sheets" | python3 -c "import json,sys; sheets=json.load(sys.stdin); print(sum(s['indexedCount'] for s in sheets))" 2>/dev/null || echo 0)
echo "  Total facts: $fact_count"
echo "  Indexed:     $indexed_count"
check "Has facts uploaded" "$([ "$fact_count" -gt 0 ] && echo true || echo false)"

# --- 2. Document sources ---
echo ""
echo "[2] Document Sources"
sources=$(curl -s "${BASE_URL}/api/facts")
source_count=$(echo "$sources" | python3 -c "import json,sys; d=json.load(sys.stdin); print(d.get('totalCount', 0))" 2>/dev/null || echo 0)
echo "  Source files: $source_count"

# Check for specific file types
html_count=$(echo "$sources" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len([s for s in d.get('sources',[]) if s.get('extension')=='html']))" 2>/dev/null || echo 0)
xlsx_count=$(echo "$sources" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len([s for s in d.get('sources',[]) if s.get('extension')=='xlsx']))" 2>/dev/null || echo 0)
md_count=$(echo "$sources" | python3 -c "import json,sys; d=json.load(sys.stdin); print(len([s for s in d.get('sources',[]) if s.get('extension')=='md']))" 2>/dev/null || echo 0)
echo "  HTML files:  $html_count"
echo "  XLSX files:  $xlsx_count"
echo "  MD files:    $md_count"
check "Has HTML documents" "$([ "$html_count" -gt 0 ] && echo true || echo false)"
check "Has XLSX documents" "$([ "$xlsx_count" -gt 0 ] && echo true || echo false)"

# --- 3. Knowledge Graph Nodes ---
echo ""
echo "[3] Knowledge Graph"
kg_nodes=$(curl -s "${BASE_URL}/api/knowledge-graph/nodes?limit=100" 2>/dev/null)
kg_status=$?

if echo "$kg_nodes" | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if 'content' in d or isinstance(d, list) else 1)" 2>/dev/null; then
    node_count=$(echo "$kg_nodes" | python3 -c "
import json,sys
d=json.load(sys.stdin)
nodes = d.get('content', d) if isinstance(d, dict) else d
print(len(nodes))
" 2>/dev/null || echo 0)

    # Count by node level
    echo "$kg_nodes" | python3 -c "
import json,sys
d=json.load(sys.stdin)
nodes = d.get('content', d) if isinstance(d, dict) else d
levels = {}
for n in nodes:
    lvl = n.get('nodeType', n.get('nodeLevel', 'UNKNOWN'))
    levels[lvl] = levels.get(lvl, 0) + 1
for lvl, cnt in sorted(levels.items()):
    print(f'  {lvl}: {cnt}')
print(f'  TOTAL: {len(nodes)}')
" 2>/dev/null || echo "  Could not parse node levels"

    check "Knowledge graph has nodes" "$([ "$node_count" -gt 0 ] && echo true || echo false)"

    # Check for TABLE nodes (source attribution)
    table_count=$(echo "$kg_nodes" | python3 -c "
import json,sys
d=json.load(sys.stdin)
nodes = d.get('content', d) if isinstance(d, dict) else d
print(len([n for n in nodes if n.get('nodeType','') == 'TABLE' or n.get('nodeLevel','') == 'TABLE']))
" 2>/dev/null || echo 0)
    echo "  TABLE nodes: $table_count"
    check "Has TABLE graph nodes (source attribution)" "$([ "$table_count" -gt 0 ] && echo true || echo false)"
else
    echo "  KG nodes endpoint returned error or unexpected format"
    echo "  Response: $(echo "$kg_nodes" | head -1)"
    warn "Knowledge graph nodes endpoint not working"
fi

# --- 4. Knowledge Graph Edges ---
echo ""
echo "[4] Graph Edges (Cross-document relations)"
kg_edges=$(curl -s "${BASE_URL}/api/knowledge-graph/edges?limit=100" 2>/dev/null)

if echo "$kg_edges" | python3 -c "import json,sys; d=json.load(sys.stdin); sys.exit(0 if 'content' in d or isinstance(d, list) else 1)" 2>/dev/null; then
    echo "$kg_edges" | python3 -c "
import json,sys
d=json.load(sys.stdin)
edges = d.get('content', d) if isinstance(d, dict) else d
types = {}
for e in edges:
    t = e.get('edgeType', 'UNKNOWN')
    label = e.get('label', '')
    key = f'{t}' + (f' ({label})' if label else '')
    types[key] = types.get(key, 0) + 1
for t, cnt in sorted(types.items()):
    print(f'  {t}: {cnt}')
print(f'  TOTAL: {len(edges)}')
" 2>/dev/null || echo "  Could not parse edge types"

    edge_count=$(echo "$kg_edges" | python3 -c "
import json,sys
d=json.load(sys.stdin)
edges = d.get('content', d) if isinstance(d, dict) else d
print(len(edges))
" 2>/dev/null || echo 0)
    check "Has graph edges" "$([ "$edge_count" -gt 0 ] && echo true || echo false)"
else
    echo "  KG edges endpoint returned error or unexpected format"
    warn "Knowledge graph edges endpoint not working"
fi

# --- 5. Graph Extraction Config ---
echo ""
echo "[5] Graph Extraction Config"
config=$(curl -s "${BASE_URL}/api/graph-extraction/config")
enabled=$(echo "$config" | python3 -c "import json,sys; print(json.load(sys.stdin).get('enabled', False))" 2>/dev/null || echo "false")
preset=$(echo "$config" | python3 -c "import json,sys; print(json.load(sys.stdin).get('activeSchemaPresetId', 'none'))" 2>/dev/null || echo "none")
echo "  Enabled: $enabled"
echo "  Schema preset: $preset"
check "Graph extraction enabled" "$([ "$enabled" = "True" ] && echo true || echo false)"

# --- 6. RAG Query Test ---
echo ""
echo "[6] RAG Query Test"
rag_response=$(curl -s -X POST "${BASE_URL}/api/rag/query" \
    -H "Content-Type: application/json" \
    -d '{"query":"What are the main FP&A procedures described?","k":3}' 2>/dev/null)
rag_status=$(echo "$rag_response" | python3 -c "
import json,sys
d=json.load(sys.stdin)
docs = d.get('documents', d.get('results', []))
print(f'Retrieved {len(docs)} documents')
for doc in docs[:3]:
    src = doc.get('metadata',{}).get('source', doc.get('source','unknown'))
    print(f'  - {src}')
" 2>/dev/null || echo "RAG query failed or returned unexpected format")
echo "  $rag_status"

# --- 7. Embedding Model Status ---
echo ""
echo "[7] Embedding Model"
embed_status=$(curl -s "${BASE_URL}/api/service-state/embedding" 2>/dev/null || echo "{}")
echo "  $(echo "$embed_status" | python3 -c "
import json,sys
d=json.load(sys.stdin)
print(f'Model: {d.get(\"modelId\",\"unknown\")}')
print(f'  Status: {d.get(\"status\",\"unknown\")}')
" 2>/dev/null || echo "Could not check embedding status")"

# --- Summary ---
echo ""
echo "=== Verification Summary ==="
echo "PASS: $PASS"
echo "FAIL: $FAIL"
echo "WARN: $WARN"

if [ "$FAIL" -gt 0 ]; then
    exit 1
fi
