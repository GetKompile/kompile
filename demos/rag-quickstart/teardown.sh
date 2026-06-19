#!/bin/bash
# ==============================================================================
# Kompile RAG Quickstart - Teardown
# ==============================================================================
# Stops all services started by setup.sh
# ==============================================================================

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
KOMPILE_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
KOMPILE_CLI="$KOMPILE_ROOT/kompile-cli/target/kompile-cli"
APP_PORT="${APP_PORT:-8080}"
STAGING_PORT="${STAGING_PORT:-8081}"

RED='\033[0;31m'
GREEN='\033[0;32m'
NC='\033[0m'

echo "Stopping Kompile RAG services..."

# Stop staging server via CLI
if [ -f "$KOMPILE_CLI" ]; then
    "$KOMPILE_CLI" setup staging-server stop 2>/dev/null && \
        echo -e "  ${GREEN}✔${NC} Staging server stopped" || true
fi

# Kill anything on app port
for pid in $(lsof -ti :$APP_PORT 2>/dev/null); do
    kill "$pid" 2>/dev/null && \
        echo -e "  ${GREEN}✔${NC} Killed process $pid on port $APP_PORT" || true
done

# Kill anything on staging port
for pid in $(lsof -ti :$STAGING_PORT 2>/dev/null); do
    kill "$pid" 2>/dev/null && \
        echo -e "  ${GREEN}✔${NC} Killed process $pid on port $STAGING_PORT" || true
done

# Kill any remaining maven spring-boot:run
for pid in $(pgrep -f "spring-boot:run" 2>/dev/null); do
    kill "$pid" 2>/dev/null && \
        echo -e "  ${GREEN}✔${NC} Killed maven process $pid" || true
done

echo -e "\n${GREEN}All services stopped.${NC}"
