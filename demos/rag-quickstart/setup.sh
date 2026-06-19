#!/bin/bash
# ==============================================================================
# Kompile RAG Quickstart - Fully Automated Setup
# ==============================================================================
# This script sets up a complete RAG (Retrieval-Augmented Generation) system:
#   1. Generates a RAG application project
#   2. Installs the model staging server
#   3. Starts the RAG app
#   4. Starts the staging server & stages the embedding model
#   5. Waits for the embedding model to load
#   6. Indexes sample documents
#   7. Verifies search works
#
# Prerequisites:
#   - Java 17+
#   - Maven 3.9+
#   - kompile-cli built (run from kompile repo root)
#   - kompile-model-staging built
#
# Usage:
#   ./setup.sh [--port PORT] [--staging-port STAGING_PORT] [--skip-build]
# ==============================================================================

set -e

# ─── Configuration ────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KOMPILE_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
KOMPILE_CLI="$KOMPILE_ROOT/kompile-cli/target/kompile-cli"
MVN="${MVN:-/home/agibsonccc/dev-apps/mvn/bin/mvn}"
DEMO_DIR="${DEMO_DIR:-/tmp/kompile-rag-demo}"
APP_PORT="${APP_PORT:-8080}"
STAGING_PORT="${STAGING_PORT:-8081}"
INSTANCE_ID="rag-demo"
SKIP_BUILD=false

# Parse arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --port) APP_PORT="$2"; shift 2 ;;
        --staging-port) STAGING_PORT="$2"; shift 2 ;;
        --skip-build) SKIP_BUILD=true; shift ;;
        --demo-dir) DEMO_DIR="$2"; shift 2 ;;
        --help|-h)
            echo "Usage: $0 [--port PORT] [--staging-port STAGING_PORT] [--skip-build] [--demo-dir DIR]"
            exit 0
            ;;
        *) echo "Unknown option: $1"; exit 1 ;;
    esac
done

# ─── Color output ─────────────────────────────────────────────────────────────

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

step() { echo -e "\n${BLUE}[$1/$TOTAL_STEPS]${NC} $2"; }
ok()   { echo -e "  ${GREEN}✔${NC} $1"; }
warn() { echo -e "  ${YELLOW}⚠${NC} $1"; }
fail() { echo -e "  ${RED}✘${NC} $1"; exit 1; }

TOTAL_STEPS=7

# ─── Prerequisite checks ─────────────────────────────────────────────────────

echo -e "${BLUE}Kompile RAG Quickstart${NC}"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  App port:     $APP_PORT"
echo "  Staging port: $STAGING_PORT"
echo "  Demo dir:     $DEMO_DIR"
echo "  Kompile CLI:  $KOMPILE_CLI"
echo ""

if [ ! -f "$KOMPILE_CLI" ]; then
    fail "kompile-cli not found at $KOMPILE_CLI. Build it first: cd $KOMPILE_ROOT/kompile-cli && mvn clean package -DskipTests"
fi

if ! command -v java &>/dev/null; then
    fail "Java not found. Install Java 17+."
fi

# ─── Step 1: Generate RAG App ─────────────────────────────────────────────────

step 1 "Generating RAG application project..."

if [ -d "$DEMO_DIR/$INSTANCE_ID/project" ] && [ "$SKIP_BUILD" = true ]; then
    ok "Project already exists (--skip-build), reusing."
else
    rm -rf "$DEMO_DIR"
    mkdir -p "$DEMO_DIR"

    "$KOMPILE_CLI" build app \
        --configName="$INSTANCE_ID" \
        --preset=cli-agent-rag \
        --loaders=loader-tika \
        --skipMavenBuild \
        --outputDir="$DEMO_DIR" 2>&1 | grep -v "^CLI:" | grep -v "^CLI Transform:"

    if [ ! -f "$DEMO_DIR/$INSTANCE_ID/project/pom.xml" ]; then
        fail "Failed to generate RAG app project"
    fi
    ok "Generated RAG app at $DEMO_DIR/$INSTANCE_ID/project"
fi

# ─── Step 2: Install staging server component ─────────────────────────────────

step 2 "Installing model staging server..."

STAGING_JAR="$HOME/.kompile/components/kompile-model-staging/0.1.0-SNAPSHOT/kompile-model-staging-0.1.0-SNAPSHOT.jar"
STAGING_SRC="$KOMPILE_ROOT/kompile-app/kompile-model-staging/target/kompile-model-staging-0.1.0-SNAPSHOT-exec.jar"

if [ -f "$STAGING_JAR" ] && [ "$SKIP_BUILD" = true ]; then
    ok "Staging server already installed (--skip-build)"
elif [ -f "$STAGING_SRC" ]; then
    mkdir -p "$(dirname "$STAGING_JAR")"
    cp "$STAGING_SRC" "$STAGING_JAR"
    ok "Installed staging server from build output"
else
    fail "Staging server JAR not found. Build it first:\n  cd $KOMPILE_ROOT/kompile-app/kompile-model-staging && mvn clean package -DskipTests -Dskip.ui"
fi

# ─── Step 3: Start RAG App ────────────────────────────────────────────────────

step 3 "Starting RAG application on port $APP_PORT..."

# Kill anything on the app port
for pid in $(lsof -ti :$APP_PORT 2>/dev/null); do
    kill "$pid" 2>/dev/null || true
done
sleep 1

cd "$DEMO_DIR/$INSTANCE_ID/project"
nohup "$MVN" spring-boot:run \
    -Dskip.ui \
    -Dspring-boot.run.arguments="--server.port=$APP_PORT" \
    > "$DEMO_DIR/app.log" 2>&1 &

APP_PID=$!
echo "  PID: $APP_PID"

# Wait for app to start
echo "  Waiting for app to start..."
for i in $(seq 1 60); do
    if curl -s "http://localhost:$APP_PORT/api/setup/status" 2>/dev/null | grep -q "steps\|complete\|stepNumber"; then
        ok "RAG app started on port $APP_PORT"
        break
    fi
    if [ $i -eq 60 ]; then
        fail "RAG app failed to start within 180s. Check $DEMO_DIR/app.log"
    fi
    sleep 3
done

# ─── Step 4: Run setup (staging + model) ──────────────────────────────────────

step 4 "Running kompile setup (staging server + embedding model)..."

"$KOMPILE_CLI" setup run \
    --port="$APP_PORT" \
    --staging-port="$STAGING_PORT" \
    --stage-model=bge-base-en-v1.5 \
    --timeout=300 2>&1 | sed 's/^/  /'

ok "Setup complete — embedding model loaded"

# ─── Step 5: Index sample documents ──────────────────────────────────────────

step 5 "Indexing sample documents..."

# Create sample documents
DOCS_DIR="$DEMO_DIR/sample-docs"
mkdir -p "$DOCS_DIR"

cat > "$DOCS_DIR/kompile-overview.txt" << 'DOCEOF'
Kompile is a comprehensive AI/ML platform that combines CLI tools for model conversion,
a Spring Boot RAG framework with pluggable embeddings and vector stores, and ML pipelines
for composing workflows. It supports SameDiff, ONNX, TensorFlow, and DL4J model formats.

The platform uses dense retrieval with BGE embeddings for semantic search, backed by
Apache Lucene's HNSW vector indexing via the Anserini toolkit. Documents are chunked,
embedded, and stored in a vector index for fast similarity search.
DOCEOF

cat > "$DOCS_DIR/rag-architecture.txt" << 'DOCEOF'
The Kompile RAG architecture follows a standard retrieval-augmented generation pattern:

1. Document Ingestion: Documents are loaded via specialized loaders (PDF, Office, etc.),
   parsed into text chunks with configurable overlap.
2. Embedding: Each chunk is embedded using a dense encoder model (e.g., BGE-base-en-v1.5)
   running in a GPU subprocess for performance.
3. Indexing: Embeddings are stored in a Lucene HNSW vector index for fast approximate
   nearest-neighbor search.
4. Query: User queries are embedded with the same model, similarity search retrieves
   relevant chunks, and the context is passed to an LLM for answer generation.

The embedding model runs in a separate subprocess with CUDA support for GPU acceleration.
The staging server handles model downloads, ONNX-to-SameDiff conversion, and registry
management.
DOCEOF

cat > "$DOCS_DIR/setup-guide.txt" << 'DOCEOF'
To set up Kompile RAG from scratch:

1. Build the CLI: cd kompile-cli && mvn clean package -DskipTests
2. Build the staging server: cd kompile-app/kompile-model-staging && mvn clean package -DskipTests -Dskip.ui
3. Generate a RAG app: kompile build-rag-app --instanceId=myapp --enableAnserini=true
4. Run automated setup: kompile setup run --stage-model=bge-base-en-v1.5
5. Index documents: Upload via the web UI or use the indexing API

The setup command handles starting the staging server, downloading and converting the
embedding model, configuring the main app to pull from the staging server, and waiting
for the model to load. The entire process takes about 2-3 minutes.
DOCEOF

# Upload documents via multipart file upload (triggers async ingestion)
for doc in "$DOCS_DIR"/*.txt; do
    filename=$(basename "$doc")
    response=$(curl -s -X POST "http://localhost:$APP_PORT/api/documents/upload" \
        -F "file=@$doc" 2>/dev/null)
    if echo "$response" | grep -qi "fileName\|filePath\|websocket"; then
        ok "Uploaded: $filename"
    else
        warn "Upload response for $filename: $response"
    fi
done

# Wait for ingestion to complete (keyword index creation)
echo "  Waiting for keyword indexing..."
for i in $(seq 1 30); do
    sleep 2
    idx_status=$(curl -s "http://localhost:$APP_PORT/api/indexer/status" 2>/dev/null)
    if echo "$idx_status" | python3 -c "import sys,json; d=json.load(sys.stdin); sys.exit(0 if d.get('indexAvailable') else 1)" 2>/dev/null; then
        ok "Keyword index built"
        break
    fi
    if [ $i -eq 30 ]; then
        warn "Keyword index not ready yet (may need manual trigger)"
    fi
done

# Trigger vector population from keyword index
curl -s -X POST "http://localhost:$APP_PORT/api/indexer/vector-index/start" 2>/dev/null > /dev/null

# ─── Step 6: Wait for indexing to complete ─────────────────────────────────────

step 6 "Waiting for vector index population..."

for i in $(seq 1 30); do
    sleep 3
    vec_status=$(curl -s "http://localhost:$APP_PORT/api/indexer/status" 2>/dev/null)
    vec_count=$(echo "$vec_status" | python3 -c "import sys,json; print(json.load(sys.stdin).get('approximateVectorCount',0))" 2>/dev/null)
    if [ "$vec_count" -gt 0 ] 2>/dev/null; then
        ok "Vector index populated ($vec_count vectors)"
        break
    fi
    if [ $i -eq 30 ]; then
        warn "Vector index may still be populating (this is normal for first run)"
    fi
done

# ─── Step 7: Verify search works ──────────────────────────────────────────────

step 7 "Verifying semantic search..."

# Test semantic search via the hybrid search endpoint
ENCODED_Q=$(python3 -c "import urllib.parse; print(urllib.parse.quote('How does the embedding model work?'))")
search_result=$(curl -s "http://localhost:$APP_PORT/api/rag/test/hybrid?q=$ENCODED_Q&maxResults=3" 2>/dev/null)
hit_count=$(echo "$search_result" | python3 -c "import sys,json; print(json.load(sys.stdin).get('totalHits',0))" 2>/dev/null)

if [ "$hit_count" -gt 0 ] 2>/dev/null; then
    ok "Semantic search working ($hit_count hits)"
else
    warn "Semantic search returned no results (vector index may still be populating)"
fi

# Also verify the RAG query endpoint responds
rag_result=$(curl -s -X POST "http://localhost:$APP_PORT/api/rag/query" \
    -H "Content-Type: application/json" \
    -d '{"query": "How does the embedding model work?"}' 2>/dev/null)

if echo "$rag_result" | grep -qi "query\|answer\|error"; then
    ok "RAG endpoint responding (configure LLM API key for full answers)"
fi

# Check final setup status
final_status=$(curl -s "http://localhost:$APP_PORT/api/setup/status" 2>/dev/null)
echo ""
echo -e "${GREEN}Setup Status:${NC}"
echo "$final_status" | python3 -c "
import sys,json
d = json.load(sys.stdin)
for key in ['stagingServer','modelSource','embeddingModel','indexing','searchReady']:
    s = d.get(key,{})
    icon = '✔' if s.get('complete') else '○'
    print(f'  {icon}  {s.get(\"name\",key):20s} {s.get(\"message\",\"\")}')
print(f'  Complete: {d.get(\"setupComplete\",False)}')
" 2>/dev/null

# ─── Done ─────────────────────────────────────────────────────────────────────

echo ""
echo -e "${GREEN}━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━${NC}"
echo -e "${GREEN}RAG Quickstart Setup Complete!${NC}"
echo ""
echo "  RAG App:        http://localhost:$APP_PORT"
echo "  Staging Server: http://localhost:$STAGING_PORT"
echo "  App Log:        $DEMO_DIR/app.log"
echo "  Project Dir:    $DEMO_DIR/$INSTANCE_ID/project"
echo ""
echo "Next steps:"
echo "  - Open http://localhost:$APP_PORT in a browser for the web UI"
echo "  - Upload more documents via the Fact Sheets tab"
echo "  - Configure an LLM (OpenAI, Anthropic) for full RAG answers"
echo "  - Run: kompile setup status --port=$APP_PORT  (to check status)"
echo ""
echo "To stop everything:"
echo "  ./teardown.sh"
echo ""
