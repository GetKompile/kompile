#!/usr/bin/env bash
# Build every canonical Tensor G3 producer, verify their receipts, and publish
# the offline APK. This is the only supported source-build entrypoint: it owns
# tool discovery, build locations, resource limits, production optimization,
# cache reuse, version allocation, verification, and publication.
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: build-tensor-g3-offline-apk.sh [--jobs N] [--resume-publish]

With no arguments, clean stale build generations, build or restore every
canonical Tensor G3 producer, and publish the offline APK.

  --jobs N         Positive native/Graal build parallelism (default: 12).
                    Overrides BUILD_JOBS from the environment.
  --resume-publish  Reuse already-published, receipt-verified producer outputs.
                    A missing graph AOT producer is recreated from its cached
                    support closure; accelerator and SDX producers stay reused.

Job precedence: --jobs > BUILD_JOBS > 12 (unset or empty environment).
Other stage-specific build-policy options are not part of this interface.
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
BUILD_JOBS="${BUILD_JOBS:-12}"
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
GRAPH_SUPPORT_DIR="$WORK_ROOT/graph-aot-work/clibraries/bionic"
RESOLVED_BUILD_CONFIG="$WORK_ROOT/resolved-build-config.properties"
RESUME_PUBLISH=0
PUBLISH_MODE_ARGS=(--tensor-g3-aar "$ACCELERATOR_AAR")
# Keep one unreferenced completed AOT object as well as active generation inputs:
# a successful Graal build is not referenced by a generation until SDK packaging
# succeeds. Pruning it on retry would discard reusable analysis after a link/copy failure.
# Export the same policy to nested SDK cleanup, not just the first preflight.
export SDX_ANDROID_GENERATION_RETENTION=1
export SDX_ANDROID_MANAGED_STAGE_RETENTION=0
export SDX_ANDROID_OBJECT_STAGE_RETENTION=1
CACHE_RETENTION_ARGS=(
  --retain-generations 1
  --retain-managed-stages 0
  --retain-object-stages 1
)

while (( $# > 0 )); do
  case "$1" in
    --jobs)
      (( $# >= 2 )) || fail "--jobs requires a positive integer"
      BUILD_JOBS="$2"
      shift 2
      ;;
    --jobs=*)
      BUILD_JOBS="${1#*=}"
      shift
      ;;
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

[[ "$BUILD_JOBS" =~ ^[1-9][0-9]*$ ]] ||
  fail "jobs must be a positive integer: '$BUILD_JOBS'"

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
  # A library's existence does not prove it contains today's graph sources. Only
  # explicit historical publication may skip the Kompile source refresh below.
  if (( RESUME_PUBLISH == 1 )) && [[ -s "$GRAPH_LIBRARY" && -s "$GRAPH_SUPPORT_DIR/jdk-support-receipt" ]]; then
    printf 'Resume publish: reusing graph AOT producer and generic support closure: %s\n' "$GRAPH_LIBRARY"
    return
  fi

  if [[ ! -s "$RESOLVED_BUILD_CONFIG" ]]; then
    "$SDK_BUILDER" aot \
      --production \
      --jobs "$BUILD_JOBS" \
      --output-root "$WORK_ROOT" \
      --object-builder "$GRAPH_MODULE/build-android-ndk.sh" \
      --print-config
  fi

  local android_ndk graalvm_home
  local -a graph_maven_args
  android_ndk="$(resolved_config_value android_ndk)"
  graalvm_home="$(resolved_config_value graalvm_home)"
  [[ -n "$android_ndk" && -n "$graalvm_home" ]] ||
    fail "resolved Android tool configuration is incomplete for graph AOT"

  printf 'Refreshing Kompile graph AOT and chat-core from source before APK packaging.\n'
  graph_maven_args=(
    -o -f "$KOMPILE_ROOT/pom.xml"
    -pl :kompile-graph-reasoning-local,:kompile-chat-local-core \
    -am install \
    -DskipTests \
    -Dnd4j.backend=nd4j-native \
    -Dkompile.mobile=tensor-g3 \
    -Dmobile.android.ndk="$android_ndk" \
    -Dmobile.graalvm.home="$graalvm_home" \
    -Dkompile.android.jobs="$BUILD_JOBS" \
    -Dkompile.android.work.dir="$WORK_ROOT/graph-aot-work"
  )
  if [[ -s "$GRAPH_SUPPORT_DIR/jdk-support-receipt" ]]; then
    graph_maven_args+=("-Dkompile.android.reuse.support.dir=$GRAPH_SUPPORT_DIR")
  fi
  "$KOMPILE_MAVEN" "${graph_maven_args[@]}"
  [[ -s "$GRAPH_LIBRARY" ]] || fail "graph AOT producer did not publish: $GRAPH_LIBRARY"
  for support in libjvm.a liblibchelper.a libjava.a libnet.a libnio.a libzip.a libprefs.a libextnet.a jdk-support-receipt; do
    [[ -s "$GRAPH_SUPPORT_DIR/$support" ]] || fail "graph AOT producer omitted generic support input: $support"
  done
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
[[ ! -L "$WORK_ROOT/.locks/tensor-g3-offline-apk.lock" ]] || fail "pipeline lock must not be a symlink"
exec {PIPELINE_LOCK_FD}>"$WORK_ROOT/.locks/tensor-g3-offline-apk.lock"
printf 'Waiting for the canonical Android build lock: %s\n' "$WORK_ROOT/.locks/tensor-g3-offline-apk.lock"
flock "$PIPELINE_LOCK_FD"
export SDX_ANDROID_PIPELINE_LOCK_HELD=1
export SDX_ANDROID_PIPELINE_LOCK_FD="$PIPELINE_LOCK_FD"

printf 'Tensor G3 offline APK pipeline:\n'
printf '  DL4J root: %s\n' "$DL4J_ROOT"
printf '  work root: %s\n' "$WORK_ROOT"
printf '  output root: %s\n' "$FINAL_OUTPUT_DIR"
printf '  native/Graal jobs: %s\n' "$BUILD_JOBS"
printf '  Graal build: production (-O2)\n'

printf 'Running mandatory pre-build cleanup.\n'
if (( RESUME_PUBLISH == 0 )); then
  "$CLEANUP_BUILDER" --build-root "$WORK_ROOT" "${CACHE_RETENTION_ARGS[@]}"
else
  # Historical receipts may reference an immutable CPU generation that is no longer
  # the current symlink target. Selecting that chain happens inside the packager, so
  # pruning producer generations before selection can delete the exact base SDK that
  # --resume-publish promised to reuse. Resume mode creates no producer generations;
  # preserve them and clean only disposable APK/JNI staging below.
  printf 'Resume publish: preserving immutable producer generations until receipt selection completes.\n'
fi
"$APK_BUILDER" --cleanup-only --work-root "$WORK_ROOT"

# Kompile owns the graph producer and the shared Android Native Image support
# closure. SDX consumes those generic, explicit inputs and never discovers this
# repository or graph module on its own.
ensure_graph_aot

refresh_sdx_producers() {
  "$SDK_BUILDER" all \
    --production \
    --jobs "$BUILD_JOBS" \
    --output-root "$WORK_ROOT" \
    --object-builder "$GRAPH_MODULE/build-android-ndk.sh" \
    --reuse-jdk-libs "$GRAPH_SUPPORT_DIR" \
    --reuse-svm-libs "$GRAPH_SUPPORT_DIR"

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
}

if (( RESUME_PUBLISH == 0 )); then
  refresh_sdx_producers
else
  printf 'Resume publish: reusing immutable producer artifacts from their verified historical receipts.\n'
  PUBLISH_MODE_ARGS+=(--reuse-receipted-producers)
fi

publish_args=(
  --variant tensor-g3
  --skip-maven
  --work-root "$WORK_ROOT"
  --output "$FINAL_OUTPUT_DIR"
  "${PUBLISH_MODE_ARGS[@]}"
)
if "$APK_BUILDER" "${publish_args[@]}"; then
  exit 0
else
  publish_status=$?
fi
# Only source-staleness is recoverable here. Never retry a failed audit, compile,
# or device gate, and never mutate producers selected for historical publication.
if (( publish_status != 42 || RESUME_PUBLISH == 1 )); then
  exit "$publish_status"
fi
printf 'SDX AOT source inputs changed; refreshing dependent producers once, preserving caches.\n'
# Do not rerun graph AOT or cleanup. The SDK and provider builders select their
# own stale stages; unchanged CPU/native/AOT inputs remain reusable.
refresh_sdx_producers
# A fresh packager process resolves the new SDK generation and repeats every
# receipt check. A second mismatch exits rather than looping indefinitely.
exec "$APK_BUILDER" "${publish_args[@]}"
