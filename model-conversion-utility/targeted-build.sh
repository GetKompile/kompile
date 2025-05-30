#!/bin/bash

# Complete rebuild script for Kompile - ensures proper dependency resolution
set -e

echo "=============================================="
echo "    Kompile Complete Dependency Rebuild      "
echo "=============================================="

# Get to root directory
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$ROOT_DIR"

echo "Working from root directory: $ROOT_DIR"

# Step 1: Clean everything completely
echo "Step 1: Complete clean..."
mvn clean -q

# Step 2: Build ONNX importer first and install it
echo "Step 2: Building and installing ONNX importer dependency..."
cd kompile-model-importer-onnx
mvn clean install -DskipTests -q

# Verify it was installed
if [ ! -f "target/kompile-model-importer-onnx-0.1.0-SNAPSHOT.jar" ]; then
    echo "ERROR: ONNX importer JAR not created!"
    exit 1
fi

echo "✓ ONNX importer built and installed"

# Step 3: Build model conversion utility specifically
echo "Step 3: Building model conversion utility..."
cd ../model-conversion-utility
mvn clean package -DskipTests

# Verify the uber JAR was created
if [ -f "target/model-conversion-utility-0.1.0-SNAPSHOT-uber.jar" ]; then
    echo "✓ Model conversion utility uber JAR created successfully"
    ls -lh target/model-conversion-utility-0.1.0-SNAPSHOT-uber.jar
else
    echo "✗ Model conversion utility uber JAR not found"
    exit 1
fi

if [ -f "target/model-conversion-utility-0.1.0-SNAPSHOT-cli.jar" ]; then
    echo "✓ Model conversion utility CLI JAR created successfully"
    ls -lh target/model-conversion-utility-0.1.0-SNAPSHOT-cli.jar
else
    echo "⚠ CLI JAR not found (this might be OK)"
fi

echo ""
echo "=============================================="
echo "      Build completed successfully!          "
echo "=============================================="
echo ""
echo "You can now run the utility using:"
echo "  java -jar target/model-conversion-utility-0.1.0-SNAPSHOT-uber.jar --help"
