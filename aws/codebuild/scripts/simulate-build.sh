#!/usr/bin/env bash
# Simulate a cloud build LOCALLY: run the same builder image and the same
# in-build entry point (run-build.sh) that CodeBuild / Cloud Build would run,
# against the committed tree, with zero cloud resources. This is the cheap
# rehearsal before paying for cloud minutes — both clouds funnel through
# run-build.sh, so a passing simulation is meaningful for both.
#
#   simulate-build.sh CONFIG TARGET [DL4J_REF] [--rebuild-image] [--shell]
#
# - Linux-container targets only (macOS/Windows/arm64 lanes have no local
#   equivalent). GPU validation targets get --gpus all when the host has
#   nvidia container support; otherwise they run but device tests will fail.
# - The workspace is a `git archive HEAD` extraction under
#   ~/.cache/kompile-simulate/<target>/ — exactly what the cloud clones;
#   uncommitted changes are NOT included (a warning lists them).
# - Maven/ccache state lives in docker volumes kompile-sim-m2 /
#   kompile-sim-ccache, mirroring cloud cache semantics and keeping the
#   container's root-owned files out of your ~/.m2.
set -euo pipefail
root="$(cd "$(dirname "$0")/../../.." && pwd)"
here="$root/aws/codebuild/scripts"
config="${1:?Usage: simulate-build.sh CONFIG TARGET [DL4J_REF] [--rebuild-image] [--shell]}"
target="${2:?Target required (a targets.yml key)}"
shift 2
dl4j_ref="" rebuild=0 want_shell=0
for arg in "$@"; do
  case "$arg" in
    --rebuild-image) rebuild=1 ;;
    --shell) want_shell=1 ;;
    -*) echo "Unknown flag: $arg" >&2; exit 2 ;;
    *) dl4j_ref="$arg" ;;
  esac
done

generated="$(dirname "$config")/generated-codebuild.env"
[ -f "$generated" ] && config="$generated"
# shellcheck disable=SC1090
source "$config"
# shellcheck disable=SC1091
source "$root/aws/codebuild/scripts/aws/targets-lib.sh"
targets_file="$root/aws/codebuild/targets.yml"

host="$(target_field "$targets_file" "$target" host)"
[ -n "$host" ] || { echo "Unknown target: $target" >&2; exit 2; }
variant="$(target_field "$targets_file" "$target" variant)"
kompile="$(target_field "$targets_file" "$target" kompile)"
image_key="$(target_field "$targets_file" "$target" image)"

gpu=0
case "$host" in
  linux-x86_64) family=linux ;;
  linux-android-x86_64) family=android ;;
  linux-cuda-x86_64) [ "$image_key" = CUDA_12_6_IMAGE ] && family=cuda-12.6 || family=cuda-12.9 ;;
  linux-gpu-x86_64) family=cuda-12.9; gpu=1 ;;
  linux-amd-x86_64) family=amd ;;
  linux-tpu-x86_64) family=tpu ;;
  linux-hexagon-x86_64) family=hexagon ;;
  *) echo "Target '$target' (host $host) has no local simulation: only Linux-container lanes can run here." >&2
     exit 2 ;;
esac

command -v docker >/dev/null 2>&1 || { echo "docker is required" >&2; exit 2; }
image="kompile-sim:$family"
if [ "$rebuild" = 1 ] || ! docker image inspect "$image" >/dev/null 2>&1; then
  echo "simulate: building local image $image"
  BUILD_IMAGES_PUSH=false BUILD_IMAGES_TAG_BASE=kompile-sim \
    "$here/aws/build-images.sh" "$config" "$family"
fi

sim="${KOMPILE_SIM_DIR:-$HOME/.cache/kompile-simulate}/$target"
ws="$sim/workspace"
rm -rf "$ws"
mkdir -p "$ws"
echo "simulate: extracting committed tree (git archive HEAD) into $ws"
git -C "$root" archive HEAD | tar -x -C "$ws"
dirty="$(git -C "$root" status --porcelain | wc -l)"
[ "$dirty" -gt 0 ] && echo "simulate: NOTE — $dirty uncommitted change(s) are NOT part of the simulation (the cloud builds the committed tree)"

run_args=(--rm -v "$ws:/workspace" -w /workspace
  -v kompile-sim-m2:/root/.m2 -v kompile-sim-ccache:/root/.ccache)
if [ "$gpu" = 1 ]; then
  if command -v nvidia-smi >/dev/null 2>&1; then
    run_args+=(--gpus all)
    echo "simulate: NVIDIA GPU passthrough enabled"
  else
    echo "simulate: WARNING — GPU target without local NVIDIA support; device tests will fail"
  fi
fi

envs=(
  "CODEBUILD_SRC_DIR=/workspace"
  "BUILD_TARGET=$target"
  "DL4J_REPOSITORY=${DL4J_REPOSITORY:?}"
  "DL4J_REF=${dl4j_ref:-${DL4J_REF:?}}"
  "DL4J_BUILD_THREADS=${DL4J_BUILD_THREADS:-12}"
  "DL4J_EXTRA_MAVEN_ARGS=${DL4J_EXTRA_MAVEN_ARGS:-}"
  "DL4J_MAVEN_OPTS=${DL4J_MAVEN_OPTS:--Xmx8g}"
  "MAVEN_TEST_ARGS=${MAVEN_TEST_ARGS:-}"
  "RUN_TESTS_PROFILES=${RUN_TESTS_PROFILES:-}"
  "RUN_TESTS_MODULES=${RUN_TESTS_MODULES:-}"
  "JAVA11_HOME=/opt/jdk11"
  "GRAALVM_HOME=/opt/graalvm"
  "ANDROID_NDK_HOME=/opt/android-ndk"
  "ROCM_HOME=/opt/rocm"
  "ZLUDA_HOME=/opt/zluda"
  "PJRT_LIBRARY_PATH=${PJRT_LIBRARY_PATH:-/opt/pjrt/libtpu.so}"
  "HEXAGON_SDK_ROOT=/opt/hexagon"
  "CUDA_COMPUTE_CAPABILITIES=${CUDA_COMPUTE_CAPABILITIES:-8.6 9.0}"
  "CUDA_VERSION=${CUDA_VERSION:-12.9}"
  "ZLUDA_TARGET=${ZLUDA_TARGET:-rdna3}"
  "BUILD_KOMPILE=${kompile:-true}"
  "KOMPILE_VARIANT=${variant:-cli-only}"
  "NATIVE_PARALLELISM=${NATIVE_PARALLELISM:-4}"
  "KOMPILE_MAVEN_OPTS=${KOMPILE_MAVEN_OPTS:--Xmx16g}"
)
for e in "${envs[@]}"; do run_args+=(-e "$e"); done

if [ "$want_shell" = 1 ]; then
  echo "simulate: dropping into a shell (run: bash aws/codebuild/scripts/run-build.sh)"
  exec docker run -it "${run_args[@]}" "$image" bash
fi

echo "simulate: running $target in $image"
rc=0
docker run "${run_args[@]}" "$image" bash aws/codebuild/scripts/run-build.sh \
  2>&1 | tee "$sim/simulate.log" || rc=$?
echo
if [ "$rc" = 0 ]; then
  echo "simulate: PASSED — dist output:"
  ls -lh "$ws/dist" 2>/dev/null || echo "  (empty)"
else
  echo "simulate: FAILED (exit $rc) — log: $sim/simulate.log"
fi
exit "$rc"
