#!/bin/bash
# ==============================================================================
# Kompile RAG Quickstart - Query Tool
# ==============================================================================
# Send a RAG query to the running app.
#
# Usage:
#   ./query.sh "What is Kompile?"
#   ./query.sh --search "embedding model"        # Search only (no LLM)
#   ./query.sh --status                           # Show setup status
# ==============================================================================

APP_PORT="${APP_PORT:-8080}"
BASE_URL="http://localhost:$APP_PORT"

GREEN='\033[0;32m'
BLUE='\033[0;34m'
NC='\033[0m'

case "${1:-}" in
    --status)
        echo -e "${BLUE}Setup Status:${NC}"
        curl -s "$BASE_URL/api/setup/status" | python3 -m json.tool
        ;;
    --search)
        shift
        QUERY="${*:-test query}"
        echo -e "${BLUE}Semantic Search:${NC} $QUERY"
        ENCODED=$(python3 -c "import urllib.parse; print(urllib.parse.quote('$QUERY'))")
        curl -s "$BASE_URL/api/rag/test/hybrid?q=$ENCODED&maxResults=5" | python3 -m json.tool
        ;;
    --help|-h)
        echo "Usage:"
        echo "  $0 \"your question\"        Full RAG query (needs LLM configured)"
        echo "  $0 --search \"keywords\"     Search only (embedding + vector search)"
        echo "  $0 --status                Show setup status"
        ;;
    "")
        echo "Usage: $0 \"your question\" | --search \"keywords\" | --status"
        exit 1
        ;;
    *)
        QUERY="$*"
        echo -e "${BLUE}RAG Query:${NC} $QUERY"
        curl -s -X POST "$BASE_URL/api/rag/query" \
            -H "Content-Type: application/json" \
            -d "{\"query\": \"$QUERY\"}" | python3 -m json.tool
        ;;
esac
