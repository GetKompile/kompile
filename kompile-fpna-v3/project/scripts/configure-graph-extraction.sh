#!/usr/bin/env bash
# configure-graph-extraction.sh — Enable graph extraction and apply the fpna-cpg-channel-v1 schema preset.
# Uses LFM2 (lfm2.5-1.2b-instruct) as the extraction model via the staging server.
set -euo pipefail

BASE_URL="${1:-http://localhost:8080}"
STAGING_URL="${KOMPILE_STAGING_URL:-http://localhost:8090}"
SCHEMA_PRESET="fpna-cpg-channel-v1"

echo "=== Graph Extraction Configuration ==="
echo "App URL:     $BASE_URL"
echo "Staging URL: $STAGING_URL"
echo ""

# --- Step 1: Verify staging server is up and LFM2 model is available ---
echo "--- Step 1: Check staging server and LFM2 model ---"
STAGING_HEALTH=$(curl -s -o /dev/null -w "%{http_code}" "$STAGING_URL/actuator/health" --max-time 10 2>/dev/null || echo "000")
if [ "$STAGING_HEALTH" = "200" ]; then
    echo "PASS: Staging server is healthy"
else
    echo "WARN: Staging server not reachable ($STAGING_HEALTH). LFM2 extraction requires staging at $STAGING_URL"
    echo "      Start staging with: kompile manage start staging"
fi

# Check if LFM2 model is in the registry
REGISTRY_FILE="$HOME/.kompile/models/registry.json"
if [ -f "$REGISTRY_FILE" ]; then
    if grep -q "lfm2" "$REGISTRY_FILE"; then
        LFM2_ID=$(python3 -c "
import json
with open('$REGISTRY_FILE') as f:
    reg = json.load(f)
for entry in reg.get('entries', []):
    if 'lfm2' in entry.get('model_id', '').lower():
        print(entry['model_id'])
        break
" 2>/dev/null || echo "")
        if [ -n "$LFM2_ID" ]; then
            echo "PASS: LFM2 model found in registry: $LFM2_ID"
        else
            echo "WARN: LFM2 not found in registry. Will need to be staged before extraction."
        fi
    else
        echo "WARN: No LFM2 model in registry. Stage it via: kompile sdk stage lfm2.5-1.2b-instruct"
    fi
else
    echo "WARN: No model registry found at $REGISTRY_FILE"
fi

# --- Step 2: Check graph extraction config ---
echo ""
echo "--- Step 2: Check graph extraction status ---"
CONFIG_RESPONSE=$(curl -s "$BASE_URL/api/graph-extraction/config" --max-time 10 2>/dev/null || echo "{}")
ENABLED=$(echo "$CONFIG_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('enabled', False))" 2>/dev/null || echo "false")

if [ "$ENABLED" = "True" ] || [ "$ENABLED" = "true" ]; then
    echo "PASS: Graph extraction is already enabled"
else
    echo "INFO: Enabling graph extraction..."
    curl -s -X PUT "$BASE_URL/api/graph-extraction/config" \
        -H "Content-Type: application/json" \
        -d '{"enabled": true}' \
        --max-time 10 > /dev/null 2>&1
    echo "DONE: Graph extraction enabled"
fi

# --- Step 3: Apply schema preset ---
echo ""
echo "--- Step 3: Apply schema preset: $SCHEMA_PRESET ---"
ACTIVE_PRESET=$(echo "$CONFIG_RESPONSE" | python3 -c "import sys,json; print(json.load(sys.stdin).get('activeSchemaPresetId', ''))" 2>/dev/null || echo "")

if [ "$ACTIVE_PRESET" = "$SCHEMA_PRESET" ]; then
    echo "PASS: Schema preset '$SCHEMA_PRESET' is already active"
else
    # Check if preset exists in ~/.kompile/config/schema-presets/
    PRESET_FILE="$HOME/.kompile/config/schema-presets/${SCHEMA_PRESET}.json"
    if [ -f "$PRESET_FILE" ]; then
        echo "INFO: Applying preset from: $PRESET_FILE"
        curl -s -X PUT "$BASE_URL/api/graph-extraction/config" \
            -H "Content-Type: application/json" \
            -d "{\"enabled\": true, \"activeSchemaPresetId\": \"$SCHEMA_PRESET\"}" \
            --max-time 10 > /dev/null 2>&1
        echo "DONE: Schema preset '$SCHEMA_PRESET' applied"
    else
        echo "WARN: Preset file not found: $PRESET_FILE"
        echo "      The preset should already exist from prior configuration."
        echo "      Attempting to apply by ID anyway..."
        curl -s -X PUT "$BASE_URL/api/graph-extraction/config" \
            -H "Content-Type: application/json" \
            -d "{\"enabled\": true, \"activeSchemaPresetId\": \"$SCHEMA_PRESET\"}" \
            --max-time 10 > /dev/null 2>&1
    fi
fi

# --- Step 4: Display configured entity and relationship types ---
echo ""
echo "--- Step 4: Schema Summary ---"
FINAL_CONFIG=$(curl -s "$BASE_URL/api/graph-extraction/config" --max-time 10 2>/dev/null || echo "{}")
echo "$FINAL_CONFIG" | python3 -c "
import sys, json
try:
    cfg = json.load(sys.stdin)
    print(f'  Enabled:  {cfg.get(\"enabled\", False)}')
    print(f'  Preset:   {cfg.get(\"activeSchemaPresetId\", \"(none)\")}')
    et = cfg.get('entityTypes', [])
    rt = cfg.get('relationshipTypes', [])
    if et:
        print(f'  Entity types ({len(et)}): {", ".join(et[:10])}' + ('...' if len(et) > 10 else ''))
    if rt:
        print(f'  Relationship types ({len(rt)}): {", ".join(rt[:10])}' + ('...' if len(rt) > 10 else ''))
except:
    print('  (could not parse config)')
" 2>/dev/null

echo ""
echo "=== Configuration Complete ==="
echo "Graph extraction will run automatically during document ingestion."
echo "LLM-based relation extraction uses LFM2 via staging server at $STAGING_URL."
