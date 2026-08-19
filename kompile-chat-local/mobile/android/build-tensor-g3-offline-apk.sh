#!/usr/bin/env bash
# Build every canonical Tensor G3 producer, verify their receipts, and publish
# the offline APK. This is the only supported source-build entrypoint: it owns
# tool discovery, build locations, resource limits, production optimization,
# cache reuse, version allocation, verification, and publication.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: build-tensor-g3-offline-apk.sh [--resume-publish]

With no arguments, clean stale build generations, build or restore every
canonical Tensor G3 producer, and publish the offline APK.

  --resume-publish  Reuse already-published, receipt-verified producer outputs.
                    A missing graph AOT producer is recreated from its cached
                    support closure; accelerator and SDX producers stay reused.

All build policy is resolved by this script. Stage-specific options and
environment-variable overrides are intentionally not part of this interface.
USAGE
}

fail() {
  printf 'build-tensor-g3-offline-apk: %s\n' "$*" >&2
  exit 3
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# Keep large Android/Graal intermediates on the project volume. The host's
# temporary filesystem is intentionally not used for multi-gigabyte builds.
WORK_ROOT="$SCRIPT_DIR/build/sdx-android-build"
KOMPILE_ROOT="$(realpath -e -- "$SCRIPT_DIR/../../..")"
DL4J_ROOT="$(realpath -e -- "$KOMPILE_ROOT/../deeplearning4j")"
FINAL_OUTPUT_DIR="$SCRIPT_DIR/build/offline-dist"
BUILD_JOBS=2
SDK_BUILDER="$DL4J_ROOT/nd4j/sdx-aot/src/main/android/build-android-sdx-sdk.sh"
CLEANUP_BUILDER="$DL4J_ROOT/nd4j/sdx-aot/src/main/android/prune-android-sdx-build-cache.sh"
ACCELERATOR_BUILDER="$DL4J_ROOT/libnd4j/tools/mobile/build-android-accelerator.sh"
ACCELERATOR_OUTPUT_ROOT="$DL4J_ROOT/libnd4j/build/mobile/tensor-g3"
ACCELERATOR_AAR="$ACCELERATOR_OUTPUT_ROOT/dist/sdx-runtime-android-arm64-tensor-g3.aar"
ACCELERATOR_NATIVE_AAR="$ACCELERATOR_OUTPUT_ROOT/native/sdx-runtime-sdk/dist/sdx-runtime-android-arm64-tensor-g3.aar"
ACCELERATOR_NATIVE_RECEIPT="$ACCELERATOR_NATIVE_AAR.build-receipt"
APK_BUILDER="$SCRIPT_DIR/tools/build-offline-accelerators.sh"
KOMPILE_MAVEN="${SDX_MAVEN:-/home/agibsonccc/dev-apps/mvn/bin/mvn}"
GRAPH_MODULE="$KOMPILE_ROOT/kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local"
GRAPH_LIBRARY="$GRAPH_MODULE/target/android-aot/jni/arm64-v8a/libkompile_reasoning_android.so"
RESOLVED_BUILD_CONFIG="$WORK_ROOT/resolved-build-config.properties"
RESUME_PUBLISH=0
PUBLISH_MODE_ARGS=(--tensor-g3-aar "$ACCELERATOR_AAR")
# The active immutable generations reference every managed/object stage needed for
# an identical-build cache hit. Keep those references, but do not retain obsolete
# rollback generations or unreferenced multi-gigabyte fallback stages.
CACHE_RETENTION_ARGS=(
  --retain-generations 1
  --retain-managed-stages 0
  --retain-object-stages 0
)

while (( $# > 0 )); do
  case "$1" in
    --resume-publish)
      RESUME_PUBLISH=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *) fail "unknown option: $1" ;;
  esac
done

for required in \
  "$SDK_BUILDER" "$CLEANUP_BUILDER" "$ACCELERATOR_BUILDER" \
  "$APK_BUILDER" "$KOMPILE_MAVEN" "$GRAPH_MODULE/build-android-ndk.sh"; do
  [[ -x "$required" ]] || fail "required builder is missing or not executable: $required"
done

resolved_config_value() {
  local key="$1"
  local count value
  [[ -s "$RESOLVED_BUILD_CONFIG" ]] ||
    fail "resolved Android tool configuration is missing: $RESOLVED_BUILD_CONFIG"
  count="$(grep -Ec "^${key}=.*$" "$RESOLVED_BUILD_CONFIG")"
  [[ "$count" == 1 ]] || fail "resolved Android tool configuration has $count entries for $key"
  value="$(grep -E "^${key}=.*$" "$RESOLVED_BUILD_CONFIG")"
  printf '%s\n' "${value#*=}"
}

ensure_graph_aot() {
  if [[ -s "$GRAPH_LIBRARY" ]]; then
    printf 'Reusing verified graph AOT producer: %s\n' "$GRAPH_LIBRARY"
    return
  fi

  if [[ ! -s "$RESOLVED_BUILD_CONFIG" ]]; then
    "$SDK_BUILDER" aot \
      --production \
      --jobs "$BUILD_JOBS" \
      --output-root "$WORK_ROOT" \
      --print-config
  fi

  local android_ndk graalvm_home support_dir
  android_ndk="$(resolved_config_value android_ndk)"
  graalvm_home="$(resolved_config_value graalvm_home)"
  support_dir="$(resolved_config_value jdk_support_dir)"
  [[ -n "$android_ndk" && -n "$graalvm_home" && -n "$support_dir" ]] ||
    fail "resolved Android tool configuration is incomplete for graph AOT"

  printf 'Graph AOT producer is missing; rebuilding only that producer with cached support.\n'
  "$KOMPILE_MAVEN" -o -f "$KOMPILE_ROOT/pom.xml" \
    -pl kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local \
    -am install \
    -DskipTests \
    -Dkompile.mobile=tensor-g3 \
    -Dmobile.android.ndk="$android_ndk" \
    -Dmobile.graalvm.home="$graalvm_home" \
    -Dkompile.android.jobs="$BUILD_JOBS" \
    -Dkompile.android.work.dir="$WORK_ROOT/graph-aot-work" \
    -Dkompile.android.reuse.support.dir="$support_dir"
  [[ -s "$GRAPH_LIBRARY" ]] || fail "graph AOT producer did not publish: $GRAPH_LIBRARY"
}

WORK_ROOT="$(realpath -m -- "$WORK_ROOT")"
[[ "$WORK_ROOT" != / ]] || fail "refusing to use the filesystem root"
if [[ -e "$WORK_ROOT" ]]; then
  [[ -d "$WORK_ROOT" && ! -L "$WORK_ROOT" ]] ||
    fail "work root must be a real directory: $WORK_ROOT"
else
  mkdir -p -- "$WORK_ROOT"
fi
# Keep launcher scratch files (including Native Image response files) beside
# the build as well; do not fall back to the host temporary filesystem.
TMP_ROOT="$WORK_ROOT/tmp"
mkdir -p -- "$TMP_ROOT"
export TMPDIR="$TMP_ROOT"
export TMP="$TMP_ROOT"
export TEMP="$TMP_ROOT"
if [[ "${JAVA_TOOL_OPTIONS:-}" == *"-Djava.io.tmpdir="* ]]; then
  :
else
  export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=$TMP_ROOT"
fi
command -v flock >/dev/null 2>&1 || fail "flock is required"
mkdir -p -- "$WORK_ROOT/.locks"
[[ -d "$WORK_ROOT/.locks" && ! -L "$WORK_ROOT/.locks" ]] ||
  fail "pipeline lock root must be a real directory: $WORK_ROOT/.locks"
exec {PIPELINE_LOCK_FD}>"$WORK_ROOT/.locks/tensor-g3-offline-apk.lock"
printf 'Waiting for the canonical Android build lock: %s\n' "$WORK_ROOT/.locks/tensor-g3-offline-apk.lock"
flock "$PIPELINE_LOCK_FD"
export SDX_ANDROID_PIPELINE_LOCK_HELD=1

printf 'Tensor G3 offline APK pipeline:\n'
printf '  DL4J root: %s\n' "$DL4J_ROOT"
printf '  work root: %s\n' "$WORK_ROOT"
printf '  output root: %s\n' "$FINAL_OUTPUT_DIR"
printf '  native jobs: %s\n' "$BUILD_JOBS"
printf '  Graal build: production (-O2)\n'

printf 'Running mandatory pre-build cleanup.\n'
"$CLEANUP_BUILDER" --build-root "$WORK_ROOT" "${CACHE_RETENTION_ARGS[@]}"
"$APK_BUILDER" --cleanup-only --work-root "$WORK_ROOT"

if (( RESUME_PUBLISH == 0 )); then
  "$SDK_BUILDER" all \
    --production \
    --jobs "$BUILD_JOBS" \
    --output-root "$WORK_ROOT"

  ACCELERATOR_BUILD_ARGS=(
    --jobs "$BUILD_JOBS"
    --output-root "$ACCELERATOR_OUTPUT_ROOT"
  )
  if [[ -s "$ACCELERATOR_NATIVE_AAR" && -s "$ACCELERATOR_NATIVE_RECEIPT" ]]; then
    printf 'Checking the Tensor G3 native receipt against the current source closure.\n'
    if "$ACCELERATOR_BUILDER" tensor-g3-nnapi \
        "${ACCELERATOR_BUILD_ARGS[@]}" --skip-native; then
      printf 'Reused the current receipt-verified Tensor G3 native producer.\n'
    else
      printf 'The Tensor G3 native producer is stale; rebuilding it once with the persistent ccache.\n'
      "$ACCELERATOR_BUILDER" tensor-g3-nnapi "${ACCELERATOR_BUILD_ARGS[@]}"
    fi
  else
    printf 'No verified Tensor G3 native producer exists; building it once at %s.\n' \
      "$ACCELERATOR_OUTPUT_ROOT"
    "$ACCELERATOR_BUILDER" tensor-g3-nnapi "${ACCELERATOR_BUILD_ARGS[@]}"
  fi
else
  printf 'Resume publish: reusing immutable producer artifacts from their verified historical receipts.\n'
  PUBLISH_MODE_ARGS+=(--reuse-receipted-producers)
fi

ensure_graph_aot

exec "$APK_BUILDER" \
  --variant tensor-g3 \
  --skip-maven \
  --work-root "$WORK_ROOT" \
  --output "$FINAL_OUTPUT_DIR" \
  "${PUBLISH_MODE_ARGS[@]}"
