#!/bin/bash

# Simple model downloader - no complex build process
set -e

echo "Building minimal model downloader..."

# Go to project root
cd "$(dirname "$0")/.."

# Just build the ONNX importer and model utility - skip everything else
echo "Building ONNX importer..."
cd kompile-model-importer-onnx
mvn clean install -DskipTests -q

echo "Building model conversion utility..."
cd ../model-conversion-utility
mvn clean package -DskipTests -q

# Check if it worked
if [ -f "target/model-conversion-utility-0.1.0-SNAPSHOT-uber.jar" ]; then
    echo "✅ Success! Model downloader ready."
    echo ""
    echo "Run with: java -jar target/model-conversion-utility-0.1.0-SNAPSHOT-uber.jar --help"
else
    echo "❌ Build failed - JAR not found"
    exit 1
fi
