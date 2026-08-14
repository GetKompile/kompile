#!/usr/bin/env bash
# Regression coverage for backend-bearing local SDK validation.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
VALIDATOR="${SCRIPT_DIR}/../../main/build/validate-sdx-assets.sh"
TMP_DIR="$(mktemp -d)"
trap 'rm -rf "${TMP_DIR}"' EXIT

SDK_ROOT="${TMP_DIR}/sdk"
mkdir -p "${SDK_ROOT}/jars"
touch "${SDK_ROOT}/sdx-runtime-linux-x86_64-cuda.zip"
touch "${SDK_ROOT}/jars/nd4j-cuda-12.9-1.0.0-SNAPSHOT.jar"
touch "${SDK_ROOT}/jars/nd4j-cuda-12.9-1.0.0-SNAPSHOT-linux-x86_64.jar"
touch "${SDK_ROOT}/jars/nd4j-cuda-12.9-preset-1.0.0-SNAPSHOT.jar"
touch "${SDK_ROOT}/jars/nd4j-cuda-12.9-platform-1.0.0-SNAPSHOT.jar"

validation_output="$(
    bash "${VALIDATOR}" "${SDK_ROOT}" local linux-x86_64 \
        1.0.0-SNAPSHOT 12.9 nd4j-cuda-12.9 linux-x86_64
)"
case "${validation_output}" in
    *"variant=local backend=nd4j-cuda-12.9"*"classifier=linux-x86_64"*) ;;
    *)
        echo "local CUDA validation did not report the expected lane" >&2
        echo "${validation_output}" >&2
        exit 1
        ;;
esac

if bash "${VALIDATOR}" "${SDK_ROOT}" local linux-x86_64 \
        1.0.0-SNAPSHOT 12.9 >"${TMP_DIR}/missing-backend.out" 2>&1; then
    echo "local validation unexpectedly accepted an implicit backend" >&2
    exit 1
fi
missing_backend_output="$(<"${TMP_DIR}/missing-backend.out")"
case "${missing_backend_output}" in
    *"local requires an explicit backend artifact"*) ;;
    *)
        echo "local validation did not fail with the explicit-backend contract" >&2
        echo "${missing_backend_output}" >&2
        exit 1
        ;;
esac

if bash "${VALIDATOR}" "${SDK_ROOT}" unknown linux-x86_64 \
        1.0.0-SNAPSHOT 12.9 nd4j-cuda-12.9 linux-x86_64 \
        >"${TMP_DIR}/unknown.out" 2>&1; then
    echo "unknown distribution variant unexpectedly validated" >&2
    exit 1
fi

echo "validate-sdx-assets: local distribution checks passed"
