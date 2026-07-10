#!/usr/bin/env bash
set -euo pipefail
# Run while baking the reserved-fleet AMI after vendor SDKs are installed.
required=(git mvn cmake protoc ccache)
for executable in "${required[@]}"; do command -v "$executable" >/dev/null || { echo "Missing $executable" >&2; exit 2; }; done
test -x "${JAVA11_HOME:?}/bin/java"
test -x "${GRAALVM_HOME:?}/bin/native-image"
case "${ACCELERATOR_KIND:?}" in
 nvidia) command -v nvcc >/dev/null ;;
 amd-rocm) command -v hipcc >/dev/null; test -d "${ROCM_HOME:?}" ;;
 zluda) test -d "${ZLUDA_HOME:?}" ;;
 tpu-pjrt) test -n "${PJRT_LIBRARY_PATH:?}" ;;
 hexagon) test -d "${HEXAGON_SDK_ROOT:?}" ;;
 *) echo "Unknown ACCELERATOR_KIND" >&2; exit 2 ;;
esac
