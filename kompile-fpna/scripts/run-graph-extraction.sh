#!/usr/bin/env bash
# run-graph-extraction.sh — Trigger graph relation extraction on indexed documents
# Uses CLI-based model provider (not local models) to avoid GPU resource issues.
#
# Usage:
#   ./scripts/run-graph-extraction.sh [BASE_URL]
#
# Defaults:
#   BASE_URL = http://localhost:8080

set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"

echo "=== Graph Relation Extraction ==="
echo "Target: $BASE_URL"
echo ""

# 1. Verify graph extraction is enabled
config=$(curl -s "${BASE_URL}/api/graph-extraction/config")
enabled=$(echo "$config" | python3 -c "import json,sys; print(json.load(sys.stdin).get('enabled', False))" 2>/dev/null)
provider=$(echo "$config" | python3 -c "import json,sys; print(json.load(sys.stdin).get('extractionModelProvider', 'unknown'))" 2>/dev/null)

echo "Graph extraction enabled: $enabled"
echo "Model provider: $provider"

if [ "$enabled" != "True" ]; then
    echo ""
    echo "Enabling graph extraction..."
    curl -s -X POST "${BASE_URL}/api/graph-extraction/config/toggle" \
        -H "Content-Type: application/json" | python3 -m json.tool 2>/dev/null
fi

# 2. Check schema preset
preset=$(echo "$config" | python3 -c "import json,sys; print(json.load(sys.stdin).get('activeSchemaPresetId', 'None'))" 2>/dev/null)
echo "Active schema preset: $preset"

if [ "$preset" = "None" ] || [ "$preset" = "null" ]; then
    echo ""
    echo "Applying FPNA schema preset..."
    apply_result=$(curl -s -X POST "${BASE_URL}/api/graph-extraction/schema-presets/fpna-cpg-channel-v1/apply" 2>/dev/null)
    echo "$apply_result" | python3 -m json.tool 2>/dev/null || echo "$apply_result"
fi

# 3. Show current entity and relationship types
echo ""
echo "Entity types configured:"
echo "$config" | python3 -c "
import json,sys
c=json.load(sys.stdin)
for et in c.get('entityTypes',[]):
    print(f'  - {et}')
" 2>/dev/null

echo ""
echo "Relationship types configured:"
echo "$config" | python3 -c "
import json,sys
c=json.load(sys.stdin)
for rt in c.get('relationshipTypes',[]):
    print(f'  - {rt}')
" 2>/dev/null

# 4. Trigger re-ingestion (which includes graph extraction)
echo ""
echo "To trigger graph extraction on already-uploaded documents,"
echo "use the web UI at ${BASE_URL} or re-upload files with:"
echo "  ./scripts/upload-fpna-dataset.sh"
echo ""
echo "Graph extraction runs automatically during document ingestion"
echo "when the extraction config is enabled."
echo ""

# 5. Show current graph stats
echo "=== Current Graph State ==="
nodes=$(curl -s "${BASE_URL}/api/knowledge-graph/nodes?limit=1000" 2>/dev/null)
edges=$(curl -s "${BASE_URL}/api/knowledge-graph/edges?limit=1000" 2>/dev/null)

echo "$nodes" | python3 -c "
import json,sys
d=json.load(sys.stdin)
nodes = d.get('content', d) if isinstance(d, dict) else d
levels = {}
for n in nodes:
    lvl = n.get('nodeType', n.get('nodeLevel', 'UNKNOWN'))
    levels[lvl] = levels.get(lvl, 0) + 1
print('Nodes by type:')
for lvl, cnt in sorted(levels.items()):
    print(f'  {lvl}: {cnt}')
print(f'  TOTAL: {len(nodes)}')
" 2>/dev/null || echo "  No graph nodes found (ingestion needed)"

echo ""
echo "$edges" | python3 -c "
import json,sys
d=json.load(sys.stdin)
edges = d.get('content', d) if isinstance(d, dict) else d
types = {}
for e in edges:
    t = e.get('edgeType', 'UNKNOWN')
    label = e.get('label', '')
    key = t + (f' ({label})' if label else '')
    types[key] = types.get(key, 0) + 1
print('Edges by type:')
for t, cnt in sorted(types.items()):
    print(f'  {t}: {cnt}')
print(f'  TOTAL: {len(edges)}')
" 2>/dev/null || echo "  No graph edges found (ingestion needed)"
