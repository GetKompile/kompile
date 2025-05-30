#!/bin/bash

# Build script for Kompile Model Conversion Utility
# This script rebuilds the entire project to ensure proper dependency resolution

set -e  # Exit on any error

echo "=============================================="
echo "      Kompile Complete Rebuild Script        "
echo "=============================================="

# Get the root project directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"

cd "$ROOT_DIR"

echo "Building entire project from root directory: $ROOT_DIR"

# Step 1: Clean everything first
echo "Step 1: Cleaning entire project..."
mvn clean -q

# Step 2: Build the entire project from root
echo "Step 2: Building entire project (this will take a few minutes)..."
mvn install -DskipTests

echo "Step 3: Verifying model conversion utility build artifacts..."
cd "$ROOT_DIR/model-conversion-utility"

if [ -f "target/model-conversion-utility-0.1.0-SNAPSHOT-uber.jar" ]; then
    echo "✓ Uber JAR created successfully"
    ls -lh target/model-conversion-utility-0.1.0-SNAPSHOT-uber.jar
else
    echo "✗ Uber JAR not found"
    exit 1
fi

if [ -f "target/model-conversion-utility-0.1.0-SNAPSHOT-cli.jar" ]; then
    echo "✓ CLI JAR created successfully"
    ls -lh target/model-conversion-utility-0.1.0-SNAPSHOT-cli.jar
else
    echo "✗ CLI JAR not found"
    exit 1
fi

echo ""
echo "=============================================="
echo "       Complete rebuild successful!          "
echo "=============================================="
echo ""
echo "You can now run the utility using:"
echo "  java -jar target/model-conversion-utility-0.1.0-SNAPSHOT-uber.jar --help"
echo ""
echo "Or use the provided model-utility.sh script:"
echo "  bash ./model-utility.sh --help"
