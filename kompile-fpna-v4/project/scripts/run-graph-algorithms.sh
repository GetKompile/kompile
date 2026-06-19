#!/usr/bin/env bash
# run-graph-algorithms.sh — Run graph algorithms on the extracted knowledge graph.
# Runs PageRank, community detection, and centrality analysis.
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
KG_API="$BASE_URL/api/knowledge-graph"
ALGO_API="$BASE_URL/api/graph-algorithms"

echo "=== Knowledge Graph Algorithms ==="
echo "Target: $BASE_URL"
echo ""

# --- Check node count first ---
NODE_COUNT=$(curl -s "$KG_API/nodes?limit=1" --max-time 10 2>/dev/null | python3 -c "
import sys, json
try:
    d = json.load(sys.stdin)
    nodes = d if isinstance(d, list) else d.get('nodes', d.get('content', []))
    # Try to get totalElements if paginated
    total = d.get('totalElements', len(nodes))
    print(total)
except:
    print(0)
" 2>/dev/null || echo "0")

if [ "$NODE_COUNT" -eq 0 ]; then
    echo "ERROR: No nodes in knowledge graph. Run upload + extraction first."
    exit 1
fi
echo "Graph has approximately $NODE_COUNT nodes."
echo ""

# --- PageRank ---
echo "--- Running PageRank ---"
PR_RESULT=$(curl -s -X POST "$ALGO_API/pagerank" \
    -H "Content-Type: application/json" \
    -d '{"dampingFactor": 0.85, "maxIterations": 20, "tolerance": 1e-6, "topK": 20}' \
    --max-time 60 2>/dev/null || echo "")
if [ -n "$PR_RESULT" ]; then
    echo "$PR_RESULT" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    results = data.get('results', data.get('rankings', data))
    if isinstance(results, list):
        print(f'Top {min(10, len(results))} by PageRank:')
        for r in results[:10]:
            name = r.get('name', r.get('id', '?'))
            score = r.get('score', r.get('pagerank', 0))
            ntype = r.get('type', r.get('label', ''))
            print(f'  {score:.4f}  {ntype:20s}  {name}')
    else:
        print(json.dumps(data, indent=2)[:500])
except Exception as e:
    print(f'Could not parse: {e}')
" 2>/dev/null
else
    echo "WARN: PageRank API did not respond"
fi

# --- Community Detection (Louvain) ---
echo ""
echo "--- Running Louvain Community Detection ---"
CD_RESULT=$(curl -s -X POST "$ALGO_API/communities" \
    -H "Content-Type: application/json" \
    -d '{"algorithm": "louvain", "resolution": 1.0}' \
    --max-time 60 2>/dev/null || echo "")
if [ -n "$CD_RESULT" ]; then
    echo "$CD_RESULT" | python3 -c "
import sys, json
from collections import Counter
try:
    data = json.load(sys.stdin)
    communities = data.get('communities', data.get('results', data))
    if isinstance(communities, list):
        comm_sizes = Counter()
        for item in communities:
            cid = item.get('community', item.get('communityId', 0))
            comm_sizes[cid] += 1
        print(f'Found {len(comm_sizes)} communities:')
        for cid, size in comm_sizes.most_common(10):
            print(f'  Community {cid}: {size} members')
    elif isinstance(communities, dict):
        print(f'Found {len(communities)} communities')
    else:
        print(str(data)[:500])
except Exception as e:
    print(f'Could not parse: {e}')
" 2>/dev/null
else
    echo "WARN: Community detection API did not respond"
fi

# --- Degree Centrality ---
echo ""
echo "--- Running Degree Centrality ---"
DC_RESULT=$(curl -s -X POST "$ALGO_API/centrality/degree" \
    -H "Content-Type: application/json" \
    -d '{"topK": 15}' \
    --max-time 30 2>/dev/null || echo "")
if [ -n "$DC_RESULT" ]; then
    echo "$DC_RESULT" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    results = data.get('results', data.get('rankings', data))
    if isinstance(results, list):
        print(f'Top {min(10, len(results))} by degree centrality (most connected):')
        for r in results[:10]:
            name = r.get('name', r.get('id', '?'))
            degree = r.get('score', r.get('degree', 0))
            ntype = r.get('type', r.get('label', ''))
            print(f'  {degree:6.0f}  {ntype:20s}  {name}')
except Exception as e:
    print(f'Could not parse: {e}')
" 2>/dev/null
else
    echo "WARN: Degree centrality API did not respond"
fi

echo ""
echo "=== Algorithms Complete ==="
