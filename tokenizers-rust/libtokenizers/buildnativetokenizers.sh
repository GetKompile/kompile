#!/bin/bash

################################################################################
# buildnativetokenizers.sh
# Native build script for tokenizers Rust library with C++ wrapper
# Based on the nd4j buildnativeoperations.sh pattern
# Updated to work with externalized HuggingFace tokenizers project
################################################################################

set -e  # Exit on any error

# Build configuration
BUILD_TYPE="${BUILD_TYPE:-Release}"
PLATFORM="${PLATFORM:-$(uname -s | tr '[:upper:]' '[:lower:]')}"
ARCH="${ARCH:-$(uname -m)}"
VERBOSE="${VERBOSE:-false}"

# Directories
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
BUILD_DIR="${SCRIPT_DIR}/build"
OUTPUT_DIR="${SCRIPT_DIR}/target/native"

echo "==============================================="
echo "Building tokenizers native library"
echo "==============================================="
echo "Platform: ${PLATFORM}"
echo "Architecture: ${ARCH}"
echo "Build type: ${BUILD_TYPE}"
echo "Build directory: ${BUILD_DIR}"
echo "Output directory: ${OUTPUT_DIR}"
echo "==============================================="

# Create directories
mkdir -p "${BUILD_DIR}"
mkdir -p "${OUTPUT_DIR}"

# Function to check if command exists
command_exists() {
    command -v "$1" >/dev/null 2>&1
}

# Check dependencies
echo "Checking dependencies..."

if ! command_exists cargo; then
    echo "Error: Rust cargo not found. Please install Rust: https://rustup.rs/"
    exit 1
fi

if ! command_exists cmake; then
    echo "Error: CMake not found. Please install CMake."
    exit 1
fi

if ! command_exists git; then
    echo "Error: Git not found. Please install Git."
    exit 1
fi

echo "All dependencies found."

# Platform-specific configuration
case "${PLATFORM}" in
    linux)
        TARGET="x86_64-unknown-linux-gnu"
        LIB_EXT="so"
        if [[ "${ARCH}" == "aarch64" || "${ARCH}" == "arm64" ]]; then
            TARGET="aarch64-unknown-linux-gnu"
        fi
        ;;
    darwin)
        TARGET="x86_64-apple-darwin"
        LIB_EXT="dylib"
        if [[ "${ARCH}" == "arm64" ]]; then
            TARGET="aarch64-apple-darwin"
        fi
        ;;
    mingw*|cygwin*|msys*|windows*)
        TARGET="x86_64-pc-windows-gnu"
        LIB_EXT="dll"
        ;;
    *)
        echo "Unsupported platform: ${PLATFORM}"
        exit 1
        ;;
esac

echo "Building for target: ${TARGET}"

# Build using CMake (which will handle the external project)
echo "Building with CMake (including external tokenizers project)..."
cd "${BUILD_DIR}"

# Configure CMake
cmake "${SCRIPT_DIR}" \
    -DCMAKE_BUILD_TYPE="${BUILD_TYPE}" \
    -DCMAKE_INSTALL_PREFIX="${OUTPUT_DIR}" \
    -DPLATFORM="${PLATFORM}" \
    -DARCH="${ARCH}"

# Build everything (CMake will handle cloning, building Rust, generating bindings, and building C++ wrapper)
cmake --build . --config "${BUILD_TYPE}" -j$(nproc 2>/dev/null || echo 4)

# Install to output directory
cmake --install . --config "${BUILD_TYPE}"

# Platform-specific library organization
echo "Organizing platform-specific libraries..."

PLATFORM_DIR="${OUTPUT_DIR}/${PLATFORM}-${ARCH}"
mkdir -p "${PLATFORM_DIR}"

# Copy libraries to platform-specific directory
case "${PLATFORM}" in
    linux)
        find "${BUILD_DIR}" -name "*.so" -exec cp {} "${PLATFORM_DIR}/" \; 2>/dev/null || true
        find "${BUILD_DIR}" -name "*.so.*" -exec cp {} "${PLATFORM_DIR}/" \; 2>/dev/null || true
        ;;
    darwin)
        find "${BUILD_DIR}" -name "*.dylib" -exec cp {} "${PLATFORM_DIR}/" \; 2>/dev/null || true
        find "${BUILD_DIR}" -name "*.jnilib" -exec cp {} "${PLATFORM_DIR}/" \; 2>/dev/null || true
        ;;
    mingw*|cygwin*|msys*|windows*)
        find "${BUILD_DIR}" -name "*.dll" -exec cp {} "${PLATFORM_DIR}/" \; 2>/dev/null || true
        ;;
esac

# Copy generated headers to include directory
INCLUDE_DIR="${OUTPUT_DIR}/include"
mkdir -p "${INCLUDE_DIR}"

# Copy generated C headers
if [[ -f "${BUILD_DIR}/tokenizers-build/bindings/include/tokenizers.h" ]]; then
    cp "${BUILD_DIR}/tokenizers-build/bindings/include/tokenizers.h" "${INCLUDE_DIR}/tokenizers_c.h"
fi

# Copy wrapper headers
if [[ -d "${SCRIPT_DIR}/include" ]]; then
    cp "${SCRIPT_DIR}/include"/*.h "${INCLUDE_DIR}/" 2>/dev/null || true
fi

# Create manifest file
MANIFEST_FILE="${OUTPUT_DIR}/manifest.properties"
cat > "${MANIFEST_FILE}" << EOF
# Tokenizers Native Library Manifest
build.timestamp=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
build.platform=${PLATFORM}
build.arch=${ARCH}
build.type=${BUILD_TYPE}
build.target=${TARGET}
rust.version=$(cargo --version)
cmake.version=$(cmake --version | head -n1)
tokenizers.repo=https://github.com/huggingface/tokenizers.git
EOF

echo "==============================================="
echo "Build completed successfully!"
echo "Output directory: ${OUTPUT_DIR}"
echo "Platform directory: ${PLATFORM_DIR}"
echo "Include directory: ${INCLUDE_DIR}"
echo "==============================================="

# List built artifacts
echo "Built artifacts:"
echo "Libraries:"
find "${OUTPUT_DIR}" -type f \( -name "*.so" -o -name "*.dll" -o -name "*.dylib" -o -name "*.jnilib" \) | sort

echo "Headers:"
find "${OUTPUT_DIR}" -type f -name "*.h" | sort

echo "Build script completed."
