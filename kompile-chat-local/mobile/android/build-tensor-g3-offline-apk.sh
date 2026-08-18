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

  --resume-publish  Reuse already-published, receipt-verified producer outputs
                    and run only APK verification, packaging, and publication.

All build policy is resolved by this script. Stage-specific options and
environment-variable overrides are intentionally not part of this interface.
USAGE
}

fail() {
  printf 'build-tensor-g3-offline-apk: %s\n' "$*" >&2
  exit 3
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_ROOT="/tmp/sdx-android-build"
DL4J_ROOT="$(realpath -e -- "$SCRIPT_DIR/../../../../deeplearning4j")"
FINAL_OUTPUT_DIR="$SCRIPT_DIR/build/offline-dist"
BUILD_JOBS=2
SDK_BUILDER="$DL4J_ROOT/nd4j/sdx-aot/src/main/android/build-android-sdx-sdk.sh"
CLEANUP_BUILDER="$DL4J_ROOT/nd4j/sdx-aot/src/main/android/prune-android-sdx-build-cache.sh"
ACCELERATOR_BUILDER="$DL4J_ROOT/libnd4j/tools/mobile/build-android-accelerator.sh"
APK_BUILDER="$SCRIPT_DIR/tools/build-offline-accelerators.sh"
RESUME_PUBLISH=0
PUBLISH_MODE_ARGS=()
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

for required in "$SDK_BUILDER" "$CLEANUP_BUILDER" "$ACCELERATOR_BUILDER" "$APK_BUILDER"; do
  [[ -x "$required" ]] || fail "required builder is missing or not executable: $required"
done

WORK_ROOT="$(realpath -m -- "$WORK_ROOT")"
[[ "$WORK_ROOT" != / ]] || fail "refusing to use the filesystem root"
if [[ -e "$WORK_ROOT" ]]; then
  [[ -d "$WORK_ROOT" && ! -L "$WORK_ROOT" ]] ||
    fail "work root must be a real directory: $WORK_ROOT"
else
  mkdir -p -- "$WORK_ROOT"
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
  "$ACCELERATOR_BUILDER" tensor-g3-nnapi \
    --jobs "$BUILD_JOBS" \
    --output-root "$WORK_ROOT/accelerator/tensor-g3"
else
  printf 'Resume publish: reusing immutable producer artifacts from their verified historical receipts.\n'
  PUBLISH_MODE_ARGS+=(--reuse-receipted-producers)
fi

exec "$APK_BUILDER" \
  --variant tensor-g3 \
  --skip-maven \
  --work-root "$WORK_ROOT" \
  --output "$FINAL_OUTPUT_DIR" \
  "${PUBLISH_MODE_ARGS[@]}"
