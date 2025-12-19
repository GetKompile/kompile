#!/bin/bash

# Script to find the FIRST and LAST heap files containing libnd4j in MAPPED_LIBRARIES
# Usage: ./find_libnd4j_heaps.sh [directory]
#   directory: Optional. Directory containing heap files (defaults to current directory)

# Use provided directory or current directory
HEAP_DIR="${1:-.}"

# Validate directory exists
if [[ ! -d "$HEAP_DIR" ]]; then
    echo "ERROR: Directory '$HEAP_DIR' does not exist"
    exit 1
fi

# Get absolute path
HEAP_DIR=$(cd "$HEAP_DIR" && pwd)

echo "Searching in directory: $HEAP_DIR"
echo ""

# Find all .heap files in the directory
HEAP_FILES=$(ls -1 "$HEAP_DIR"/*.heap 2>/dev/null)

if [[ -z "$HEAP_FILES" ]]; then
    echo "ERROR: No .heap files found in $HEAP_DIR"
    exit 1
fi

# Extract PID from first heap file (assuming format jeprof.PID.*.heap)
FIRST_FILE=$(echo "$HEAP_FILES" | head -n 1)
PID=$(basename "$FIRST_FILE" | sed 's/jeprof\.\([0-9]*\)\..*/\1/')

if [[ -z "$PID" ]]; then
    echo "ERROR: Could not extract PID from heap files"
    exit 1
fi

echo "Detected PID: $PID"
echo ""

# Variables to store first and last matching files
FIRST_MATCH=""
LAST_MATCH=""

echo "Searching for heap files with libnd4j in MAPPED_LIBRARIES..."
echo ""

# Find files with libnd4j, sorted ascending for first match
for file in $(ls -1 "${HEAP_DIR}"/jeprof.${PID}.*.i*.heap 2>/dev/null | \
             sed 's/.*\.\([0-9]*\)\.i[0-9]*\.heap/\1 &/' | \
             sort -n | \
             awk '{print $2}'); do
    
    if [[ ! -f "$file" ]]; then
        continue
    fi
    
    # Check if libnd4j is in the MAPPED_LIBRARIES section
    if grep -A 1000 "MAPPED_LIBRARIES" "$file" 2>/dev/null | grep -q "libnd4j"; then
        FIRST_MATCH="$file"
        break
    fi
done

# Find files with libnd4j, sorted descending for last match
for file in $(ls -1 "${HEAP_DIR}"/jeprof.${PID}.*.i*.heap 2>/dev/null | \
             sed 's/.*\.\([0-9]*\)\.i[0-9]*\.heap/\1 &/' | \
             sort -rn | \
             awk '{print $2}'); do
    
    if [[ ! -f "$file" ]]; then
        continue
    fi
    
    # Check if libnd4j is in the MAPPED_LIBRARIES section
    if grep -A 1000 "MAPPED_LIBRARIES" "$file" 2>/dev/null | grep -q "libnd4j"; then
        LAST_MATCH="$file"
        break
    fi
done

# Display results
if [[ -z "$FIRST_MATCH" ]] && [[ -z "$LAST_MATCH" ]]; then
    echo "ERROR: No heap files found containing libnd4j in MAPPED_LIBRARIES"
    exit 1
fi

echo "=========================================="
echo "RESULTS"
echo "=========================================="
echo ""

if [[ -n "$FIRST_MATCH" ]]; then
    echo "FIRST heap file with libnd4j:"
    echo "  File: $(basename "$FIRST_MATCH")"
    echo "  Path: $FIRST_MATCH"
    echo ""
    echo "  libnd4j mappings:"
    grep -A 1000 "MAPPED_LIBRARIES" "$FIRST_MATCH" | grep "libnd4j" | sed 's/^/    /'
    echo ""
fi

if [[ -n "$LAST_MATCH" ]]; then
    echo "LAST heap file with libnd4j:"
    echo "  File: $(basename "$LAST_MATCH")"
    echo "  Path: $LAST_MATCH"
    echo ""
    echo "  libnd4j mappings:"
    grep -A 1000 "MAPPED_LIBRARIES" "$LAST_MATCH" | grep "libnd4j" | sed 's/^/    /'
    echo ""
fi

# Provide jeprof commands
echo "=========================================="
echo "SUGGESTED JEPROF COMMANDS"
echo "=========================================="
echo ""

if [[ -n "$FIRST_MATCH" ]] && [[ -n "$LAST_MATCH" ]] && [[ "$FIRST_MATCH" != "$LAST_MATCH" ]]; then
    echo "To analyze leaks from first to last libnd4j load:"
    echo "  cd \"$HEAP_DIR\" && jeprof --base=$(basename "$FIRST_MATCH") $(basename "$LAST_MATCH")"
    echo ""
fi

if [[ -n "$LAST_MATCH" ]]; then
    echo "To analyze the last heap file:"
    echo "  cd \"$HEAP_DIR\" && jeprof $(basename "$LAST_MATCH")"
    echo ""
fi

exit 0
