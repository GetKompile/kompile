#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_MODULE_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
STAGER="${DIST_MODULE_DIR}/src/main/build/stage-native-libs.sh"
TEST_TMP="$(mktemp -d)"
trap 'rm -rf "${TEST_TMP}"' EXIT

SOURCE_TREE="${TEST_TMP}/source"
DEST_TREE="${TEST_TMP}/dest"

mkdir -p \
    "${SOURCE_TREE}/org/nd4j/linux-x86_64" \
    "${SOURCE_TREE}/org/nd4j/linux-x86_64-avx2" \
    "${SOURCE_TREE}/org/bytedeco/openblas/linux-x86_64" \
    "${SOURCE_TREE}/org/xerial/snappy/native/Linux/x86_64" \
    "${SOURCE_TREE}/resources/com/pty4j/native/linux/x86-64" \
    "${SOURCE_TREE}/org/xerial/snappy/native/FreeBSD/x86_64" \
    "${SOURCE_TREE}/org/bytedeco/onnx/linux-arm64" \
    "${SOURCE_TREE}/org/bytedeco/onnx/windows-x86_64"

printf 'baseline-nd4j\n' > "${SOURCE_TREE}/org/nd4j/linux-x86_64/libnd4jcpu.so"
printf 'baseline-jni\n' > "${SOURCE_TREE}/org/nd4j/linux-x86_64/libjnind4jcpu.so"
printf 'avx2-nd4j\n' > "${SOURCE_TREE}/org/nd4j/linux-x86_64-avx2/libnd4jcpu.so"
printf 'avx2-jni\n' > "${SOURCE_TREE}/org/nd4j/linux-x86_64-avx2/libjnind4jcpu.so"
printf 'openblas\n' > "${SOURCE_TREE}/org/bytedeco/openblas/linux-x86_64/libopenblas.so.0"
printf 'snappy\n' > "${SOURCE_TREE}/org/xerial/snappy/native/Linux/x86_64/libsnappyjava.so"
printf 'pty\n' > "${SOURCE_TREE}/resources/com/pty4j/native/linux/x86-64/libpty.so"
printf 'freebsd\n' > "${SOURCE_TREE}/org/xerial/snappy/native/FreeBSD/x86_64/libforeign.so"
printf 'arm\n' > "${SOURCE_TREE}/org/bytedeco/onnx/linux-arm64/libarm.so"
printf 'windows\n' > "${SOURCE_TREE}/org/bytedeco/onnx/windows-x86_64/foreign.dll"

bash "${STAGER}" "${SOURCE_TREE}" "${DEST_TREE}" linux-x86_64 -avx2

cmp -s "${SOURCE_TREE}/org/nd4j/linux-x86_64-avx2/libnd4jcpu.so" "${DEST_TREE}/libnd4jcpu.so"
cmp -s "${SOURCE_TREE}/org/nd4j/linux-x86_64-avx2/libjnind4jcpu.so" "${DEST_TREE}/libjnind4jcpu.so"
cmp -s "${DEST_TREE}/libopenblas.so.0" "${DEST_TREE}/libopenblas_nolapack.so.0"
test -f "${DEST_TREE}/libsnappyjava.so"
test -f "${DEST_TREE}/libpty.so"
test ! -e "${DEST_TREE}/libforeign.so"
test ! -e "${DEST_TREE}/libarm.so"
test ! -e "${DEST_TREE}/foreign.dll"

if [ -n "$(find "${DEST_TREE}" -mindepth 1 -type d -print -quit)" ]; then
    echo "ERROR: staged layout is not flat" >&2
    exit 1
fi

CONFLICT_SOURCE="${TEST_TMP}/conflict-source"
mkdir -p \
    "${CONFLICT_SOURCE}/one/linux-x86_64" \
    "${CONFLICT_SOURCE}/two/linux/amd64"
printf 'first\n' > "${CONFLICT_SOURCE}/one/linux-x86_64/libcollision.so"
printf 'second\n' > "${CONFLICT_SOURCE}/two/linux/amd64/libcollision.so"
if bash "${STAGER}" "${CONFLICT_SOURCE}" "${TEST_TMP}/conflict-dest" linux-x86_64 >/dev/null 2>&1; then
    echo "ERROR: conflicting platform basenames were accepted" >&2
    exit 1
fi

FLAVOR_CONFLICT_SOURCE="${TEST_TMP}/flavor-conflict-source"
mkdir -p \
    "${FLAVOR_CONFLICT_SOURCE}/base/linux-x86_64" \
    "${FLAVOR_CONFLICT_SOURCE}/one/linux-x86_64-avx2" \
    "${FLAVOR_CONFLICT_SOURCE}/two/linux-x86_64-avx2"
printf 'base\n' > "${FLAVOR_CONFLICT_SOURCE}/base/linux-x86_64/libbase.so"
printf 'first-nd4j\n' > "${FLAVOR_CONFLICT_SOURCE}/one/linux-x86_64-avx2/libnd4jcpu.so"
printf 'jni\n' > "${FLAVOR_CONFLICT_SOURCE}/one/linux-x86_64-avx2/libjnind4jcpu.so"
printf 'second-nd4j\n' > "${FLAVOR_CONFLICT_SOURCE}/two/linux-x86_64-avx2/libnd4jcpu.so"
printf 'jni\n' > "${FLAVOR_CONFLICT_SOURCE}/two/linux-x86_64-avx2/libjnind4jcpu.so"
if bash "${STAGER}" "${FLAVOR_CONFLICT_SOURCE}" "${TEST_TMP}/flavor-conflict-dest" linux-x86_64 -avx2 >/dev/null 2>&1; then
    echo "ERROR: conflicting optimized-flavor basenames were accepted" >&2
    exit 1
fi

INCOMPLETE_FLAVOR_SOURCE="${TEST_TMP}/incomplete-flavor-source"
mkdir -p \
    "${INCOMPLETE_FLAVOR_SOURCE}/base/linux-x86_64" \
    "${INCOMPLETE_FLAVOR_SOURCE}/flavor/linux-x86_64-avx2"
printf 'base\n' > "${INCOMPLETE_FLAVOR_SOURCE}/base/linux-x86_64/libbase.so"
printf 'nd4j-only\n' > "${INCOMPLETE_FLAVOR_SOURCE}/flavor/linux-x86_64-avx2/libnd4jcpu.so"
if bash "${STAGER}" "${INCOMPLETE_FLAVOR_SOURCE}" "${TEST_TMP}/incomplete-flavor-dest" linux-x86_64 -avx2 >/dev/null 2>&1; then
    echo "ERROR: incomplete optimized ND4J flavor was accepted" >&2
    exit 1
fi

ACCELERATOR_SOURCE="${TEST_TMP}/accelerator-source"
mkdir -p \
    "${ACCELERATOR_SOURCE}/base/linux-x86_64" \
    "${ACCELERATOR_SOURCE}/cudnn/linux-x86_64-cudnn"
printf 'base\n' > "${ACCELERATOR_SOURCE}/base/linux-x86_64/libbase.so"
printf 'cudnn-runtime\n' > "${ACCELERATOR_SOURCE}/cudnn/linux-x86_64-cudnn/libnd4jcuda.so"
bash "${STAGER}" "${ACCELERATOR_SOURCE}" "${TEST_TMP}/accelerator-dest" linux-x86_64 -cudnn nd4j-cuda-12.9
cmp -s "${ACCELERATOR_SOURCE}/cudnn/linux-x86_64-cudnn/libnd4jcuda.so" "${TEST_TMP}/accelerator-dest/libnd4jcuda.so"

echo "stage-native-libs: all checks passed"
