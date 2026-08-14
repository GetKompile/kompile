#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
DIST_MODULE_DIR="$(cd "${SCRIPT_DIR}/../../.." && pwd)"
STAGER="${DIST_MODULE_DIR}/src/main/build/stage-native-libs.sh"
TEST_TMP="$(mktemp -d)"
trap 'rm -rf "${TEST_TMP}"' EXIT

write_manifest() {
    local directory="$1"
    local count="$2"
    shift 2
    mkdir -p "${directory}"
    {
        printf '# nd4j-shared-runtime-manifest-v1\n'
        printf '# runtime-count=%s\n' "${count}"
        for runtime_name in "$@"; do
            printf '%s\n' "${runtime_name}"
        done
    } > "${directory}/shared-runtime-manifest.txt"
}

write_cpu_backend() {
    local root="$1"
    local classifier="$2"
    local marker="$3"
    local directory="${root}/org/nd4j/linalg/cpu/nativecpu/bindings/${classifier}"
    write_manifest "${directory}" 0
    printf '%s-nd4j\n' "${marker}" > "${directory}/libnd4jcpu.so"
    printf '%s-jni\n' "${marker}" > "${directory}/libjnind4jcpu.so"
}

write_cuda_redist() {
    local root="$1"
    local directory="${root}/org/bytedeco/cuda-redist/linux-x86_64"
    mkdir -p "${directory}"
    for library in libcudart.so.12 libcublas.so.12 libcublasLt.so.12 \
            libcusolver.so.11 libcusparse.so.12 libnvrtc.so.12 libnvJitLink.so.12; do
        printf 'cuda-redist\n' > "${directory}/${library}"
    done
}

SOURCE_TREE="${TEST_TMP}/source"
DEST_TREE="${TEST_TMP}/dest"
BASE_BACKEND="${SOURCE_TREE}/org/nd4j/linalg/cpu/nativecpu/bindings/linux-x86_64"
FLAVOR_BACKEND="${SOURCE_TREE}/org/nd4j/linalg/cpu/nativecpu/bindings/linux-x86_64-avx2"

mkdir -p     "${SOURCE_TREE}/org/bytedeco/openblas/linux-x86_64"     "${SOURCE_TREE}/org/xerial/snappy/native/Linux/x86_64"     "${SOURCE_TREE}/resources/com/pty4j/native/linux/x86-64"     "${SOURCE_TREE}/org/xerial/snappy/native/FreeBSD/x86_64"     "${SOURCE_TREE}/org/bytedeco/onnx/linux-arm64"     "${SOURCE_TREE}/org/bytedeco/onnx/windows-x86_64"

write_manifest "${BASE_BACKEND}" 0
printf 'baseline-nd4j\n' > "${BASE_BACKEND}/libnd4jcpu.so"
printf 'baseline-jni\n' > "${BASE_BACKEND}/libjnind4jcpu.so"

write_manifest "${FLAVOR_BACKEND}" 1 libLLVM.so.22
printf 'avx2-nd4j\n' > "${FLAVOR_BACKEND}/libnd4jcpu.so"
printf 'avx2-jni\n' > "${FLAVOR_BACKEND}/libjnind4jcpu.so"
printf 'llvm-runtime\n' > "${FLAVOR_BACKEND}/libLLVM.so.22"

printf 'openblas\n' > "${SOURCE_TREE}/org/bytedeco/openblas/linux-x86_64/libopenblas.so.0"
printf 'snappy\n' > "${SOURCE_TREE}/org/xerial/snappy/native/Linux/x86_64/libsnappyjava.so"
printf 'pty\n' > "${SOURCE_TREE}/resources/com/pty4j/native/linux/x86-64/libpty.so"
printf 'freebsd\n' > "${SOURCE_TREE}/org/xerial/snappy/native/FreeBSD/x86_64/libforeign.so"
printf 'arm\n' > "${SOURCE_TREE}/org/bytedeco/onnx/linux-arm64/libarm.so"
printf 'windows\n' > "${SOURCE_TREE}/org/bytedeco/onnx/windows-x86_64/foreign.dll"

bash "${STAGER}" "${SOURCE_TREE}" "${DEST_TREE}" linux-x86_64 -avx2

cmp -s "${FLAVOR_BACKEND}/libnd4jcpu.so" "${DEST_TREE}/libnd4jcpu.so"
cmp -s "${FLAVOR_BACKEND}/libjnind4jcpu.so" "${DEST_TREE}/libjnind4jcpu.so"
cmp -s "${FLAVOR_BACKEND}/libLLVM.so.22" "${DEST_TREE}/libLLVM.so.22"
cmp -s "${FLAVOR_BACKEND}/shared-runtime-manifest.txt" "${DEST_TREE}/shared-runtime-manifest.txt"
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
write_cpu_backend "${CONFLICT_SOURCE}" linux-x86_64 baseline
mkdir -p     "${CONFLICT_SOURCE}/one/linux-x86_64"     "${CONFLICT_SOURCE}/two/linux/amd64"
printf 'first\n' > "${CONFLICT_SOURCE}/one/linux-x86_64/libcollision.so"
printf 'second\n' > "${CONFLICT_SOURCE}/two/linux/amd64/libcollision.so"
if bash "${STAGER}" "${CONFLICT_SOURCE}" "${TEST_TMP}/conflict-dest" linux-x86_64 >/dev/null 2>&1; then
    echo "ERROR: conflicting platform basenames were accepted" >&2
    exit 1
fi

DUPLICATE_MANIFEST_SOURCE="${TEST_TMP}/duplicate-manifest-source"
for prefix in one two; do
    directory="${DUPLICATE_MANIFEST_SOURCE}/${prefix}/org/nd4j/linalg/cpu/nativecpu/bindings/linux-x86_64"
    write_manifest "${directory}" 0
    printf '%s-nd4j\n' "${prefix}" > "${directory}/libnd4jcpu.so"
    printf '%s-jni\n' "${prefix}" > "${directory}/libjnind4jcpu.so"
done
if bash "${STAGER}" "${DUPLICATE_MANIFEST_SOURCE}" "${TEST_TMP}/duplicate-manifest-dest" linux-x86_64 >/dev/null 2>&1; then
    echo "ERROR: multiple producer manifests were accepted" >&2
    exit 1
fi

INCOMPLETE_BACKEND_SOURCE="${TEST_TMP}/incomplete-backend-source"
INCOMPLETE_BACKEND="${INCOMPLETE_BACKEND_SOURCE}/org/nd4j/linalg/cpu/nativecpu/bindings/linux-x86_64-avx2"
write_manifest "${INCOMPLETE_BACKEND}" 0
printf 'nd4j-only\n' > "${INCOMPLETE_BACKEND}/libnd4jcpu.so"
if bash "${STAGER}" "${INCOMPLETE_BACKEND_SOURCE}" "${TEST_TMP}/incomplete-backend-dest" linux-x86_64 -avx2 >/dev/null 2>&1; then
    echo "ERROR: incomplete optimized ND4J backend was accepted" >&2
    exit 1
fi

MISSING_RUNTIME_SOURCE="${TEST_TMP}/missing-runtime-source"
MISSING_RUNTIME_BACKEND="${MISSING_RUNTIME_SOURCE}/org/nd4j/linalg/jcublas/bindings/linux-x86_64-cudnn"
write_manifest "${MISSING_RUNTIME_BACKEND}" 1 libMLIR.so.22
printf 'cuda-runtime\n' > "${MISSING_RUNTIME_BACKEND}/libnd4jcuda.so"
printf 'cuda-jni\n' > "${MISSING_RUNTIME_BACKEND}/libjnind4jcuda.so"
if bash "${STAGER}" "${MISSING_RUNTIME_SOURCE}" "${TEST_TMP}/missing-runtime-dest" linux-x86_64 -cudnn nd4j-cuda-12.9 >/dev/null 2>&1; then
    echo "ERROR: missing manifest-owned compiler runtime was accepted" >&2
    exit 1
fi

MISSING_CUDA_REDIST_SOURCE="${TEST_TMP}/missing-cuda-redist-source"
MISSING_CUDA_REDIST_BACKEND="${MISSING_CUDA_REDIST_SOURCE}/org/nd4j/linalg/jcublas/bindings/linux-x86_64"
write_manifest "${MISSING_CUDA_REDIST_BACKEND}" 0
printf 'cuda-runtime\n' > "${MISSING_CUDA_REDIST_BACKEND}/libnd4jcuda.so"
printf 'cuda-jni\n' > "${MISSING_CUDA_REDIST_BACKEND}/libjnind4jcuda.so"
if bash "${STAGER}" "${MISSING_CUDA_REDIST_SOURCE}" "${TEST_TMP}/missing-cuda-redist-dest" linux-x86_64 '' nd4j-cuda-12.9 >/dev/null 2>&1; then
    echo "ERROR: incomplete CUDA redistributable closure was accepted" >&2
    exit 1
fi

COUNT_MISMATCH_SOURCE="${TEST_TMP}/count-mismatch-source"
COUNT_MISMATCH_BACKEND="${COUNT_MISMATCH_SOURCE}/org/nd4j/linalg/cpu/nativecpu/bindings/linux-x86_64"
write_manifest "${COUNT_MISMATCH_BACKEND}" 2 libLLVM.so.22
printf 'nd4j\n' > "${COUNT_MISMATCH_BACKEND}/libnd4jcpu.so"
printf 'jni\n' > "${COUNT_MISMATCH_BACKEND}/libjnind4jcpu.so"
printf 'llvm\n' > "${COUNT_MISMATCH_BACKEND}/libLLVM.so.22"
if bash "${STAGER}" "${COUNT_MISMATCH_SOURCE}" "${TEST_TMP}/count-mismatch-dest" linux-x86_64 >/dev/null 2>&1; then
    echo "ERROR: malformed manifest count was accepted" >&2
    exit 1
fi

ACCELERATOR_SOURCE="${TEST_TMP}/accelerator-source"
ACCELERATOR_BACKEND="${ACCELERATOR_SOURCE}/org/nd4j/linalg/jcublas/bindings/linux-x86_64-cudnn"
write_manifest "${ACCELERATOR_BACKEND}" 2 libLLVM.so.22 libMLIR.so.22
printf 'cuda-runtime\n' > "${ACCELERATOR_BACKEND}/libnd4jcuda.so"
printf 'cuda-jni\n' > "${ACCELERATOR_BACKEND}/libjnind4jcuda.so"
printf 'llvm\n' > "${ACCELERATOR_BACKEND}/libLLVM.so.22"
printf 'mlir\n' > "${ACCELERATOR_BACKEND}/libMLIR.so.22"
write_cuda_redist "${ACCELERATOR_SOURCE}"
bash "${STAGER}" "${ACCELERATOR_SOURCE}" "${TEST_TMP}/accelerator-dest" linux-x86_64 -cudnn nd4j-cuda-12.9
cmp -s "${ACCELERATOR_BACKEND}/libnd4jcuda.so" "${TEST_TMP}/accelerator-dest/libnd4jcuda.so"
cmp -s "${ACCELERATOR_BACKEND}/libjnind4jcuda.so" "${TEST_TMP}/accelerator-dest/libjnind4jcuda.so"
cmp -s "${ACCELERATOR_BACKEND}/shared-runtime-manifest.txt" "${TEST_TMP}/accelerator-dest/shared-runtime-manifest.txt"

echo "stage-native-libs: all checks passed"
