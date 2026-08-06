#!/usr/bin/env bash
# Stage a complete DL4J SDK shard for the Maven distribution assembly.
set -euo pipefail

SOURCE_DIR="${1:-}"
TARGET_DIR="${2:-}"
VARIANT="${3:-}"
PLATFORM="${4:-}"
ND4J_VERSION="${5:-}"
CUDA_VERSION="${6:-}"
BACKEND_ARTIFACT="${7:-}"
SDK_CLASSIFIER="${8:-}"

if [ -z "${SOURCE_DIR}" ] || [ -z "${TARGET_DIR}" ] || [ -z "${VARIANT}" ] \
        || [ -z "${PLATFORM}" ] || [ -z "${ND4J_VERSION}" ] || [ -z "${CUDA_VERSION}" ]; then
    echo "Usage: $0 <sdk-assets-dir> <target-dir> <variant> <javacpp-platform> <nd4j-version> <cuda-version> [backend-artifact] [sdk-classifier]" >&2
    exit 2
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
bash "${SCRIPT_DIR}/validate-sdx-assets.sh" \
    "${SOURCE_DIR}" "${VARIANT}" "${PLATFORM}" "${ND4J_VERSION}" "${CUDA_VERSION}" "${BACKEND_ARTIFACT}" "${SDK_CLASSIFIER}"

RUNTIME_COUNT=$(find "${SOURCE_DIR}" -type f \( -name '*.zip' -o -name '*.aar' \) | wc -l)
JAR_COUNT=$(find "${SOURCE_DIR}/jars" -type f -name '*.jar' 2>/dev/null | wc -l || true)

rm -rf "${TARGET_DIR}"
mkdir -p "${TARGET_DIR}"
cp -a "${SOURCE_DIR}/." "${TARGET_DIR}/"
echo "Staged DL4J SDK assets: runtime=${RUNTIME_COUNT}, jars=${JAR_COUNT}"
