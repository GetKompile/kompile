#!/usr/bin/env bash
# Build every canonical Tensor G3 producer, verify their receipts, and publish
# the offline APK inside one process-scoped /tmp namespace. No arguments are
# required; environment overrides remain available for nonstandard checkouts.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: build-tensor-g3-offline-apk.sh [--resume-publish] [APK publisher options]

With no arguments, clean stale build generations, build or restore every
canonical Tensor G3 producer, and publish the offline APK.

  --resume-publish  Reuse already-published, receipt-verified producer outputs
                    and run only APK verification, packaging, and publication.

Other arguments are forwarded to the APK publisher.
USAGE
}

fail() {
  printf 'build-tensor-g3-offline-apk: %s\n' "$*" >&2
  exit 3
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_ROOT="${KOMPILE_ANDROID_WORK_ROOT:-/tmp/sdx-android-build}"
DL4J_ROOT="${KOMPILE_DL4J_ROOT:-$(realpath -m -- "$SCRIPT_DIR/../../../../deeplearning4j")}"
SDK_BUILDER="$DL4J_ROOT/nd4j/sdx-aot/src/main/android/build-android-sdx-sdk.sh"
CLEANUP_BUILDER="$DL4J_ROOT/nd4j/sdx-aot/src/main/android/prune-android-sdx-build-cache.sh"
ACCELERATOR_BUILDER="$DL4J_ROOT/libnd4j/tools/mobile/build-android-accelerator.sh"
APK_BUILDER="$SCRIPT_DIR/tools/build-offline-accelerators.sh"
RESUME_PUBLISH=0
PUBLISH_MODE_ARGS=(--ram-gradle-build)
APK_PUBLISHER_ARGS=()

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
    *)
      APK_PUBLISHER_ARGS+=("$1")
      shift
      ;;
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
export SDX_ANDROID_BUILD_ROOT="$WORK_ROOT"
export KOMPILE_NATIVE_QUICK_BUILD="${KOMPILE_NATIVE_QUICK_BUILD:-1}"
export KOMPILE_APK_STAGING_ROOT="${KOMPILE_APK_STAGING_ROOT:-/dev/shm}"
[[ -d "$KOMPILE_APK_STAGING_ROOT" && ! -L "$KOMPILE_APK_STAGING_ROOT" && -w "$KOMPILE_APK_STAGING_ROOT" ]] ||
  fail "APK staging root must be a real writable directory: $KOMPILE_APK_STAGING_ROOT"
export TMPDIR="${KOMPILE_ANDROID_TMPDIR:-$KOMPILE_APK_STAGING_ROOT}"
[[ -d "$TMPDIR" && ! -L "$TMPDIR" && -w "$TMPDIR" ]] ||
  fail "temporary root must be a real writable directory: $TMPDIR"

printf 'Tensor G3 offline APK pipeline:\n'
printf '  DL4J root: %s\n' "$DL4J_ROOT"
printf '  work root: %s\n' "$WORK_ROOT"
printf '  Graal quick build: %s\n' "$KOMPILE_NATIVE_QUICK_BUILD"

printf 'Running mandatory pre-build cleanup.\n'
"$CLEANUP_BUILDER" --build-root "$WORK_ROOT"
"$APK_BUILDER" --cleanup-only --work-root "$WORK_ROOT"

if (( RESUME_PUBLISH == 0 )); then
  "$SDK_BUILDER"
  "$ACCELERATOR_BUILDER"
else
  printf 'Resume publish: reusing immutable producer artifacts from their verified historical receipts.\n'
  PUBLISH_MODE_ARGS+=(--reuse-receipted-producers)
fi

exec "$APK_BUILDER" \
  --variant tensor-g3 \
  --skip-maven \
  --work-root "$WORK_ROOT" \
  "${PUBLISH_MODE_ARGS[@]}" \
  "${APK_PUBLISHER_ARGS[@]}"
