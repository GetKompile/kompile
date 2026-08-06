#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: build-offline-accelerators.sh [options]

Options:
  --variant <all|vulkan|hexagon|tensor-g3|tensor-g5>  Required build selection; 'all' is explicit
  --vulkan-aar <file>                Android Vulkan SDX AAR
  --hexagon-aar <file>               Qualcomm SDX AAR
  --tensor-g3-aar <file>             Pixel 8a Tensor G3 NNAPI SDX AAR
  --tensor-g5-aar <file>             Google Tensor G5 direct-NPU SDX AAR
  --tensor-aar <file>                Deprecated alias for --tensor-g5-aar
  --graph-library <file>             Android Graal graph AOT shared library
  --sdx-llm-sdk <dir>                DL4J sdx-aot Android SDK root (jni/arm64-v8a)
  --graph-header <file>              Packaged graph C ABI header
  --graph-verifier <file>            Packaged graph Android verifier
  --javacpp-jar <file>               JavaCPP 1.5.13 build JAR
  --jni-output <arm64-v8a-dir>       Generated graph JNI output directory
  --android-sdk <dir>                Android SDK root (or ANDROID_HOME)
  --android-ndk <dir>                Android NDK used by runtime verifiers
  --java-home <dir>                  JDK 17 (or JAVA_HOME)
  --maven <file>                     Maven executable (default: mvn)
  --skip-maven                       Reuse already-installed Kompile jars
  --maven-artifacts                  Use passed Maven artifacts without source staging
  --sdx-release-version <version>    Independently pinned canonical SDK release version
  --sdx-release-manifest <file>      Canonical sdx-sdk-manifest.json
  --sdx-release-artifact-root <dir>  Directory containing its exact fileName artifacts
  --output <dir>                     Output directory (default: build/offline-dist)
  --build-id <id>                    Visible APK/build filename identity (default: UTC timestamp)
  --version-code <number>            Android update identity (default: Unix timestamp)
  --ram-gradle-build                 Put all disposable Gradle output under the RAM staging root
  --retain-staging                   Keep generated JNI/Gradle state for diagnosis
  --cleanup-only                     Remove canonical disposable staging and exit

The build is always dependency-offline and emits signed debug/research APKs.
INTERNET is used only for public Hugging Face discovery and app-owned model transfer; inference is local.
Models are separate licensed inputs.
USAGE
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DL4J_ROOT="${DL4J_ROOT:-$REPO_ROOT/../deeplearning4j}"
VARIANT=""
VULKAN_AAR="${SDX_VULKAN_AAR:-$DL4J_ROOT/libnd4j/build/mobile/vulkan/dist/sdx-runtime-android-arm64-vulkan.aar}"
HEXAGON_AAR="${SDX_HEXAGON_AAR:-$DL4J_ROOT/libnd4j/build/mobile/hexagon/dist/sdx-runtime-android-arm64-hexagon.aar}"
TENSOR_G3_AAR="${SDX_TENSOR_G3_AAR:-$DL4J_ROOT/libnd4j/build/mobile/tensor-g3/dist/sdx-runtime-android-arm64-tensor-g3.aar}"
TENSOR_G5_AAR="${SDX_TENSOR_G5_AAR:-$DL4J_ROOT/libnd4j/build/mobile/google-tensor-g5/dist/sdx-chat-runtime-android-arm64-google-tensor-g5.aar}"
GRAPH_MODULE="$REPO_ROOT/kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local"
GRAPH_LIBRARY="${KOMPILE_GRAPH_ANDROID_SO:-$GRAPH_MODULE/target/android-aot/jni/arm64-v8a/libkompile_reasoning_android.so}"
SDX_LLM_SDK="${SDX_LLM_ANDROID_SDK:-$DL4J_ROOT/nd4j/sdx-aot/target/android-aot}"
GRAPH_HEADER=""
GRAPH_VERIFIER=""
JAVACPP_JAR="${JAVACPP_JAR:-}"
JNI_OUTPUT_DIR="$SCRIPT_DIR/app/src/main/jniLibs/arm64-v8a"
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ANDROID_NDK_ARG="${ANDROID_NDK:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}}"
JAVA_HOME_ARG="${JAVA_HOME:-}"
MAVEN="${MAVEN:-$REPO_ROOT/mvnw}"
SKIP_MAVEN=0
MAVEN_ARTIFACTS=0
OUTPUT_DIR="$SCRIPT_DIR/build/offline-dist"
SDX_RELEASE_VERSION="${SDX_RELEASE_VERSION:-}"
SDX_RELEASE_MANIFEST="${SDX_RELEASE_MANIFEST:-}"
SDX_RELEASE_ARTIFACT_ROOT="${SDX_RELEASE_ARTIFACT_ROOT:-}"
RELEASE_CONSUMER=0
AAR_OVERRIDE=0
RETAIN_STAGING="${KOMPILE_ANDROID_RETAIN_STAGING:-0}"
APK_BUILD_ID="${KOMPILE_ANDROID_BUILD_ID:-$(date -u +%Y%m%dT%H%M%SZ)}"
APK_VERSION_CODE="${KOMPILE_ANDROID_VERSION_CODE:-$(date -u +%s)}"
APK_STAGING_ROOT="${KOMPILE_APK_STAGING_ROOT:-/dev/shm}"
APP_BUILD_ROOT="$SCRIPT_DIR/app/build"
RAM_GRADLE_BUILD="${KOMPILE_ANDROID_RAM_GRADLE_BUILD:-0}"
CLEANUP_ONLY=0
TEMPORARY_FILES=()
TEMPORARY_DIRECTORIES=()

remove_owned_build_directory() {
  local candidate="$1"
  local owner_root="$2"
  local label="$3"
  local candidate_real owner_real output_real

  [[ -e "$candidate" || -L "$candidate" ]] || return 0
  if [[ -L "$candidate" || -L "$owner_root" ]]; then
    echo "Refusing to remove symlinked Android build state: $candidate" >&2
    return 1
  fi
  candidate_real="$(realpath -m -- "$candidate")" || return 1
  owner_real="$(realpath -m -- "$owner_root")" || return 1
  output_real="$(realpath -m -- "$OUTPUT_DIR")" || return 1
  if [[ "$candidate_real" == "$owner_real" || "$candidate_real" != "$owner_real/"* ]]; then
    echo "Refusing to remove Android build state outside $owner_real: $candidate_real" >&2
    return 1
  fi
  if [[ "$output_real" == "$candidate_real" || "$output_real" == "$candidate_real/"* ]]; then
    echo "Retaining $label because it contains the final APK output: $candidate_real"
    return 0
  fi
  rm -rf -- "$candidate_real"
  echo "Removed disposable $label: $candidate_real"
}

remove_owned_build_file() {
  local candidate="$1"
  local owner_root="$2"
  local label="$3"
  local candidate_real owner_real

  [[ -e "$candidate" || -L "$candidate" ]] || return 0
  if [[ -L "$candidate" || -L "$owner_root" ]]; then
    echo "Refusing to remove symlinked $label: $candidate" >&2
    return 1
  fi
  candidate_real="$(realpath -m -- "$candidate")" || return 1
  owner_real="$(realpath -m -- "$owner_root")" || return 1
  if [[ "$candidate_real" == "$owner_real" || "$candidate_real" != "$owner_real/"* ]]; then
    echo "Refusing to remove $label outside $owner_real: $candidate_real" >&2
    return 1
  fi
  rm -f -- "$candidate_real"
  echo "Removed disposable $label: $candidate_real"
}

validate_output_location() {
  local output_real candidate candidate_real
  output_real="$(realpath -m -- "$OUTPUT_DIR")" || return 1
  for candidate in \
      "$APP_BUILD_ROOT/intermediates" \
      "$APP_BUILD_ROOT/tmp" \
      "$APP_BUILD_ROOT/kotlin" \
      "$APP_BUILD_ROOT/generated" \
      "$APP_BUILD_ROOT/outputs" \
      "$APP_BUILD_ROOT/sdx-normalized-aar" \
      "$SCRIPT_DIR/build/graph-javacpp" \
      "$SCRIPT_DIR/app/src/main/jniLibs/arm64-v8a" \
      "$REPO_ROOT/kompile-chat-local/mobile/target/android-jni" \
      "$REPO_ROOT/kompile-chat-local/mobile/target/maven-artifacts" \
      "$REPO_ROOT/kompile-chat-local/mobile/target/graph-android-arm64" \
      "$REPO_ROOT/kompile-chat-local/mobile/target/dependency-maven-plugin-markers"; do
    candidate_real="$(realpath -m -- "$candidate")" || return 1
    if [[ "$output_real" == "$candidate_real" || "$output_real" == "$candidate_real/"* ]]; then
      echo "APK output must not be placed inside disposable staging: $OUTPUT_DIR" >&2
      return 1
    fi
  done
}

cleanup_android_build_state() {
  local build_status=$?
  local cleanup_status=0
  local app_build_root="$APP_BUILD_ROOT"
  local android_build_root="$SCRIPT_DIR/build"
  local mobile_root="$REPO_ROOT/kompile-chat-local/mobile"
  local maven_target_root="$REPO_ROOT/kompile-chat-local/mobile/target"
  local jni_output_real direct_jni_real maven_jni_real temporary_file temporary_directory

  trap - EXIT INT TERM
  if [[ "$RETAIN_STAGING" == "1" ]]; then
    echo "Retaining Android build staging by request: app=$app_build_root jni=$JNI_OUTPUT_DIR"
    exit "$build_status"
  fi

  for temporary_file in "${TEMPORARY_FILES[@]}"; do
    remove_owned_build_file "$temporary_file" "$(dirname -- "$temporary_file")" "temporary resolver file" || cleanup_status=1
  done
  remove_owned_build_directory "$app_build_root/intermediates" "$app_build_root" "Gradle intermediates" || cleanup_status=1
  remove_owned_build_directory "$app_build_root/tmp" "$app_build_root" "Gradle temporary state" || cleanup_status=1
  remove_owned_build_directory "$app_build_root/kotlin" "$app_build_root" "Kotlin incremental state" || cleanup_status=1
  remove_owned_build_directory "$app_build_root/generated" "$app_build_root" "Gradle generated state" || cleanup_status=1
  remove_owned_build_directory "$app_build_root/outputs" "$app_build_root" "Gradle APK outputs" || cleanup_status=1
  remove_owned_build_directory "$app_build_root/sdx-normalized-aar" "$app_build_root" "normalized provider AARs" || cleanup_status=1
  remove_owned_build_directory "$android_build_root/graph-javacpp" "$android_build_root" "graph JavaCPP work state" || cleanup_status=1
  for temporary_directory in "${TEMPORARY_DIRECTORIES[@]}"; do
    remove_owned_build_directory "$temporary_directory" "$APK_STAGING_ROOT" "RAM Gradle build root" || cleanup_status=1
  done

  jni_output_real="$(realpath -m -- "$JNI_OUTPUT_DIR")" || cleanup_status=1
  direct_jni_real="$(realpath -m -- "$SCRIPT_DIR/app/src/main/jniLibs/arm64-v8a")" || cleanup_status=1
  maven_jni_real="$(realpath -m -- "$maven_target_root/android-jni/arm64-v8a")" || cleanup_status=1
  if [[ "$jni_output_real" == "$direct_jni_real" ]]; then
    remove_owned_build_directory "$JNI_OUTPUT_DIR" "$SCRIPT_DIR/app/src/main/jniLibs" "direct-build JNI staging" || cleanup_status=1
  elif [[ "$jni_output_real" == "$maven_jni_real" ]]; then
    remove_owned_build_directory "$maven_target_root/android-jni" "$maven_target_root" "Maven JNI staging" || cleanup_status=1
  else
    echo "Retaining caller-owned JNI output outside canonical staging roots: $JNI_OUTPUT_DIR"
  fi

  if [[ "$MAVEN_ARTIFACTS" == "1" || "$CLEANUP_ONLY" == "1" ]]; then
    remove_owned_build_directory "$maven_target_root/maven-artifacts" "$maven_target_root" "Maven dependency staging" || cleanup_status=1
    remove_owned_build_directory "$maven_target_root/graph-android-arm64" "$maven_target_root" "Maven graph SDK staging" || cleanup_status=1
    remove_owned_build_directory "$maven_target_root/dependency-maven-plugin-markers" "$maven_target_root" "Maven unpack markers" || cleanup_status=1
  fi

  if [[ "$build_status" != "0" ]]; then
    exit "$build_status"
  fi
  exit "$cleanup_status"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --variant) VARIANT="${2:?missing value for --variant}"; shift 2 ;;
    --vulkan-aar) VULKAN_AAR="${2:?missing value for --vulkan-aar}"; AAR_OVERRIDE=1; shift 2 ;;
    --hexagon-aar) HEXAGON_AAR="${2:?missing value for --hexagon-aar}"; AAR_OVERRIDE=1; shift 2 ;;
    --tensor-g3-aar) TENSOR_G3_AAR="${2:?missing value for --tensor-g3-aar}"; AAR_OVERRIDE=1; shift 2 ;;
    --tensor-g5-aar|--tensor-aar) TENSOR_G5_AAR="${2:?missing value for $1}"; AAR_OVERRIDE=1; shift 2 ;;
    --graph-library) GRAPH_LIBRARY="${2:?missing value for --graph-library}"; shift 2 ;;
    --sdx-llm-sdk) SDX_LLM_SDK="${2:?missing value for --sdx-llm-sdk}"; shift 2 ;;
    --graph-header) GRAPH_HEADER="${2:?missing value for --graph-header}"; shift 2 ;;
    --graph-verifier) GRAPH_VERIFIER="${2:?missing value for --graph-verifier}"; shift 2 ;;
    --javacpp-jar) JAVACPP_JAR="${2:?missing value for --javacpp-jar}"; shift 2 ;;
    --jni-output) JNI_OUTPUT_DIR="${2:?missing value for --jni-output}"; shift 2 ;;
    --android-sdk) ANDROID_SDK="${2:?missing value for --android-sdk}"; shift 2 ;;
    --android-ndk) ANDROID_NDK_ARG="${2:?missing value for --android-ndk}"; shift 2 ;;
    --java-home) JAVA_HOME_ARG="${2:?missing value for --java-home}"; shift 2 ;;
    --maven) MAVEN="${2:?missing value for --maven}"; shift 2 ;;
    --skip-maven) SKIP_MAVEN=1; shift ;;
    --maven-artifacts) MAVEN_ARTIFACTS=1; SKIP_MAVEN=1; shift ;;
    --sdx-release-version) SDX_RELEASE_VERSION="${2:?missing value for --sdx-release-version}"; shift 2 ;;
    --sdx-release-manifest) SDX_RELEASE_MANIFEST="${2:?missing value for --sdx-release-manifest}"; shift 2 ;;
    --sdx-release-artifact-root) SDX_RELEASE_ARTIFACT_ROOT="${2:?missing value for --sdx-release-artifact-root}"; shift 2 ;;
    --output) OUTPUT_DIR="${2:?missing value for --output}"; shift 2 ;;
    --build-id) APK_BUILD_ID="${2:?missing value for --build-id}"; shift 2 ;;
    --version-code) APK_VERSION_CODE="${2:?missing value for --version-code}"; shift 2 ;;
    --ram-gradle-build) RAM_GRADLE_BUILD=1; shift ;;
    --retain-staging) RETAIN_STAGING=1; shift ;;
    --cleanup-only) CLEANUP_ONLY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$RETAIN_STAGING" in
  0|1) ;;
  *) echo "KOMPILE_ANDROID_RETAIN_STAGING must be 0 or 1" >&2; exit 2 ;;
esac
case "$RAM_GRADLE_BUILD" in
  0|1) ;;
  *) echo "KOMPILE_ANDROID_RAM_GRADLE_BUILD must be 0 or 1" >&2; exit 2 ;;
esac
[[ "$APK_BUILD_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || {
  echo "APK build ID must contain only letters, digits, dot, underscore, and hyphen" >&2
  exit 2
}
[[ "$APK_VERSION_CODE" =~ ^[0-9]+$ ]] && (( APK_VERSION_CODE >= 1 && APK_VERSION_CODE <= 2100000000 )) || {
  echo "APK version code must be an integer from 1 through 2100000000" >&2
  exit 2
}
if [[ "$CLEANUP_ONLY" != "1" ]]; then
  if [[ -z "$VARIANT" ]]; then
    echo "--variant is required; use --variant all only when every APK is intentional" >&2
    usage >&2
    exit 2
  fi
  case "$VARIANT" in
    all|vulkan|hexagon|tensor-g3|tensor-g5) ;;
    *) echo "Invalid --variant: $VARIANT" >&2; exit 2 ;;
  esac
  if [[ "$RAM_GRADLE_BUILD" == "1" ]]; then
    [[ -d "$APK_STAGING_ROOT" && -w "$APK_STAGING_ROOT" ]] || {
      echo "RAM Gradle build root must be an existing writable directory: $APK_STAGING_ROOT" >&2
      exit 2
    }
    APP_BUILD_ROOT="$(mktemp -d "$APK_STAGING_ROOT/.kompile-android-app-build.${APK_BUILD_ID}.XXXXXX")"
    TEMPORARY_DIRECTORIES+=("$APP_BUILD_ROOT")
  fi
fi
validate_output_location || exit 2
trap cleanup_android_build_state EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
if [[ "$CLEANUP_ONLY" == "1" ]]; then
  exit 0
fi
if [[ -n "$SDX_RELEASE_VERSION" || -n "$SDX_RELEASE_MANIFEST" || -n "$SDX_RELEASE_ARTIFACT_ROOT" ]]; then
  [[ -n "$SDX_RELEASE_VERSION" && -n "$SDX_RELEASE_MANIFEST" && -n "$SDX_RELEASE_ARTIFACT_ROOT" ]] || {
    echo "--sdx-release-version, --sdx-release-manifest, and --sdx-release-artifact-root must be supplied together" >&2
    exit 2
  }
  [[ "$AAR_OVERRIDE" == "0" ]] || {
    echo "release-consumer mode cannot be combined with explicit source-build --*-aar inputs" >&2
    exit 2
  }
  RELEASE_CONSUMER=1
fi
[[ "$(uname -s)" == "Linux" ]] || {
  echo "Android accelerator packaging requires a Linux/GNU build host" >&2
  exit 1
}

[[ -d "$ANDROID_SDK" ]] || { echo "Android SDK not found: $ANDROID_SDK" >&2; exit 1; }
[[ -d "$ANDROID_NDK_ARG" ]] || { echo "Android NDK not found: $ANDROID_NDK_ARG" >&2; exit 1; }
[[ -x "$JAVA_HOME_ARG/bin/java" ]] || { echo "JDK not found: $JAVA_HOME_ARG" >&2; exit 1; }
if [[ "$SKIP_MAVEN" != "1" ]]; then
  command -v "$MAVEN" >/dev/null 2>&1 || [[ -x "$MAVEN" ]] || {
    echo "Maven executable not found: $MAVEN" >&2
    exit 1
  }
fi
for required_publication_tool in dd stat; do
  command -v "$required_publication_tool" >/dev/null 2>&1 || {
    echo "$required_publication_tool is required for byte-exact APK publication" >&2
    exit 1
  }
done
[[ -s "$GRAPH_LIBRARY" ]] || {
  echo "Android graph AOT library not found: $GRAPH_LIBRARY" >&2
  exit 1
}
SDX_LLM_JNI_DIR="$SDX_LLM_SDK/jni/arm64-v8a"
[[ -s "$SDX_LLM_JNI_DIR/libsdx_llm.so" ]] || {
  echo "DL4J Android SDX LLM runtime not found: $SDX_LLM_JNI_DIR/libsdx_llm.so" >&2
  echo "Build it explicitly with nd4j/sdx-aot -Pandroid-aot -Dbackend.artifactId=<importer-backend>, or pass --sdx-llm-sdk." >&2
  exit 1
}
[[ -s "$SDX_LLM_JNI_DIR/libjnisdx_llm.so" ]] || {
  echo "DL4J Android SDX JavaCPP transport not found: $SDX_LLM_JNI_DIR/libjnisdx_llm.so" >&2
  echo "Rebuild nd4j/sdx-aot with the explicit android-aot profile." >&2
  exit 1
}
[[ -s "$SDX_LLM_SDK/metadata/build.properties" ]] || {
  echo "DL4J Android SDX LLM metadata not found: $SDX_LLM_SDK/metadata/build.properties" >&2
  exit 1
}
grep -Fxq 'abi.version=2' "$SDX_LLM_SDK/metadata/build.properties" || {
  echo "DL4J Android SDX LLM SDK does not expose ABI v2" >&2
  exit 1
}
grep -Fxq 'direct.gguf=true' "$SDX_LLM_SDK/metadata/build.properties" || {
  echo "DL4J Android SDX LLM SDK does not declare direct GGUF execution" >&2
  exit 1
}

if [[ "$RELEASE_CONSUMER" == "1" ]]; then
  [[ -s "$SDX_RELEASE_MANIFEST" ]] || { echo "Canonical SDX manifest not found: $SDX_RELEASE_MANIFEST" >&2; exit 1; }
  [[ -d "$SDX_RELEASE_ARTIFACT_ROOT" ]] || { echo "SDX artifact root not found: $SDX_RELEASE_ARTIFACT_ROOT" >&2; exit 1; }
  resolve_release_aar() {
    local config_variant=$1
    local output_variable=$2
    local selector_file resolved_file
    selector_file="$(mktemp)"
    TEMPORARY_FILES+=("$selector_file")
    resolved_file="$(mktemp)"
    TEMPORARY_FILES+=("$resolved_file")
    cmake -DMODE=config -DINPUT="$SCRIPT_DIR/accelerators.json" \
      -DVARIANT="$config_variant" -DOUTPUT="$selector_file" \
      -P "$SCRIPT_DIR/tools/verify-offline-apk-json.cmake"
    # Values are validated token fields written by the CMake config contract.
    . "$selector_file"
    rm -f "$selector_file"
    cmake -DSDX_MANIFEST="$SDX_RELEASE_MANIFEST" \
      -DSDX_ARTIFACT_ROOT="$SDX_RELEASE_ARTIFACT_ROOT" \
      -DSDX_RELEASE_VERSION="$SDX_RELEASE_VERSION" \
      -DSDX_COMPONENT="$RELEASE_COMPONENT" \
      -DSDX_PACKAGE_ROLE="$RELEASE_PACKAGE_ROLE" \
      -DSDX_PLATFORM="$RELEASE_PLATFORM" \
      -DSDX_VARIANT="$RELEASE_VARIANT" \
      -DSDX_OUTPUT_FILE="$resolved_file" \
      -P "$SCRIPT_DIR/../cmake/SdxReleaseArtifactResolver.cmake"
    printf -v "$output_variable" '%s' "$(<"$resolved_file")"
    rm -f "$resolved_file"
  }
  [[ "$VARIANT" != "all" && "$VARIANT" != "vulkan" ]] || resolve_release_aar vulkan VULKAN_AAR
  [[ "$VARIANT" != "all" && "$VARIANT" != "hexagon" ]] || resolve_release_aar hexagon HEXAGON_AAR
  [[ "$VARIANT" != "all" && "$VARIANT" != "tensor-g3" ]] || resolve_release_aar tensorG3 TENSOR_G3_AAR
  [[ "$VARIANT" != "all" && "$VARIANT" != "tensor-g5" ]] || resolve_release_aar tensorG5 TENSOR_G5_AAR
fi

export JAVA_HOME="$JAVA_HOME_ARG"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$ANDROID_SDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK"

graph_binding_args=(
  --android-ndk "$ANDROID_NDK_ARG"
  --graph-library "$GRAPH_LIBRARY"
  --output-dir "$JNI_OUTPUT_DIR"
)
if [[ -n "$GRAPH_HEADER" ]]; then
  graph_binding_args+=(--graph-header "$GRAPH_HEADER")
fi
if [[ -n "$GRAPH_VERIFIER" ]]; then
  graph_binding_args+=(--graph-verifier "$GRAPH_VERIFIER")
fi
if [[ -n "$JAVACPP_JAR" ]]; then
  graph_binding_args+=(--javacpp-jar "$JAVACPP_JAR")
fi
"$SCRIPT_DIR/tools/build-graph-javacpp.sh" "${graph_binding_args[@]}"

# The DL4J sdx-aot SDK is a provider-independent GGUF importer shared by all four
# APK flavors. Point the cleanup-owned JNI staging tree at the immutable published
# SDK generation instead of copying its multi-gigabyte CPU import closure. This
# both avoids build-space leakage and guarantees Gradle sees the exact audited SDK
# bytes. Resolve through the public generation symlink now so a later atomic SDK
# publication cannot change an APK build already in progress. Tokenizer JNI is
# deliberately excluded: the normalized provider AAR owns the tokenizer Java/JNI
# pair. Staging the import SDK's copy as an app library makes AGP prefer it over the
# provider and can pair a newer Java facade with an older JNI implementation.
provider_tokenizer_libraries=(
  libjnitokenizers.so
  libtokenizers_ffi.so
  libtokenizers_wrapper.so
)
for provider_tokenizer_library in "${provider_tokenizer_libraries[@]}"; do
  rm -f -- "$JNI_OUTPUT_DIR/$provider_tokenizer_library"
done
for sdx_library in "$SDX_LLM_JNI_DIR"/*.so; do
  [[ -s "$sdx_library" ]] || continue
  sdx_library_name="$(basename "$sdx_library")"
  case "$sdx_library_name" in
    libjnitokenizers.so|libtokenizers_ffi.so|libtokenizers_wrapper.so)
      continue
      ;;
  esac
  canonical_sdx_library="$(realpath -e -- "$sdx_library")"
  staged_library="$JNI_OUTPUT_DIR/$sdx_library_name"
  rm -f -- "$staged_library"
  ln -s -- "$canonical_sdx_library" "$staged_library"
  [[ "$(realpath -e -- "$staged_library")" == "$canonical_sdx_library" ]] || {
    echo "Could not stage immutable SDX SDK library: $sdx_library" >&2
    exit 1
  }
done
[[ -s "$JNI_OUTPUT_DIR/libsdx_llm.so" ]] || {
  echo "libsdx_llm.so was not staged into the APK JNI directory" >&2
  exit 1
}
[[ -s "$JNI_OUTPUT_DIR/libjnisdx_llm.so" ]] || {
  echo "libjnisdx_llm.so was not staged into the APK JNI directory" >&2
  exit 1
}

# Source provider AARs may carry stale provider-independent Java/tokenizer
# layers. Validate provider-owned bindings and native payload here; Gradle's
# canonical normalization and the final APK audit validate the refreshed layers.
if [[ "$VARIANT" == "all" || "$VARIANT" == "vulkan" ]]; then
  [[ -s "$VULKAN_AAR" ]] || { echo "Vulkan AAR not found: $VULKAN_AAR" >&2; exit 1; }
  "$DL4J_ROOT/libnd4j/tools/mobile/verify-android-accelerator-aar.sh" \
    --provider-payload-only \
    --aar "$VULKAN_AAR" \
    --variant vulkan \
    --native-library nd4jvulkan \
    --accelerator NONE \
    --gpu-target VULKAN \
    --device-ready \
    --android-ndk "$ANDROID_NDK_ARG"
fi

if [[ "$VARIANT" == "all" || "$VARIANT" == "hexagon" ]]; then
  [[ -s "$HEXAGON_AAR" ]] || { echo "Hexagon AAR not found: $HEXAGON_AAR" >&2; exit 1; }
  "$DL4J_ROOT/libnd4j/tools/mobile/verify-android-accelerator-aar.sh" \
    --provider-payload-only \
    --aar "$HEXAGON_AAR" \
    --variant hexagon \
    --native-library nd4jhexagon \
    --accelerator QUALCOMM_HEXAGON_HTP \
    --android-ndk "$ANDROID_NDK_ARG"
fi

if [[ "$VARIANT" == "all" || "$VARIANT" == "tensor-g3" ]]; then
  [[ -s "$TENSOR_G3_AAR" ]] || { echo "Tensor G3 AAR not found: $TENSOR_G3_AAR" >&2; exit 1; }
  "$DL4J_ROOT/libnd4j/tools/mobile/verify-android-accelerator-aar.sh" \
    --provider-payload-only \
    --aar "$TENSOR_G3_AAR" \
    --variant tensor-g3 \
    --native-library nd4jnnapi \
    --accelerator NNAPI_ACCELERATOR_ONLY \
    --gpu-target AUTO \
    --device-ready \
    --android-ndk "$ANDROID_NDK_ARG"
fi

if [[ "$VARIANT" == "all" || "$VARIANT" == "tensor-g5" ]]; then
  [[ -s "$TENSOR_G5_AAR" ]] || { echo "Tensor G5 AAR not found: $TENSOR_G5_AAR" >&2; exit 1; }
  "$DL4J_ROOT/libnd4j/tools/mobile/verify-google-tensor-g5-aar.sh" \
    --aar "$TENSOR_G5_AAR" \
    --android-ndk "$ANDROID_NDK_ARG"
fi

if [[ "$SKIP_MAVEN" != "1" ]]; then
  "$MAVEN" -o -f "$REPO_ROOT/pom.xml" -pl :kompile-graph-reasoning-local -am \
    install -DskipTests
  "$MAVEN" -o -f "$REPO_ROOT/kompile-chat-local/pom.xml" \
    -pl :kompile-chat-local-core -am install -DskipTests
fi

verify_apk() {
  local apk="$1"
  local variant="$2"
  local normalized_variant=""
  local runtime_aar=""
  local verifier_args=(
    --apk "$apk"
    --variant "$variant"
    --config "$SCRIPT_DIR/accelerators.json"
    --android-sdk "$ANDROID_SDK"
    --android-ndk "$ANDROID_NDK_ARG"
    --sdx-sdk "$SDX_LLM_SDK"
    --expected-build-id "$APK_BUILD_ID"
    --expected-version-code "$APK_VERSION_CODE"
  )
  case "$variant" in
    vulkan) normalized_variant=vulkan ;;
    hexagon) normalized_variant=hexagon ;;
    tensorG3) normalized_variant=tensor-g3 ;;
    tensorG5) normalized_variant=tensor-g5 ;;
    *) echo "Unsupported APK verification variant: $variant" >&2; exit 1 ;;
  esac
  # This is the exact AAR Gradle consumed after refreshing provider-independent
  # Java/tokenizer layers. Passing an input AAR or app/build fallback can silently
  # audit stale bytes when --ram-gradle-build relocates the build directory.
  runtime_aar="$APP_BUILD_ROOT/sdx-normalized-aar/$normalized_variant/sdx-runtime-$normalized_variant.aar"
  [[ -s "$runtime_aar" ]] || {
    echo "Normalized runtime AAR used by this Gradle build is missing: $runtime_aar" >&2
    exit 1
  }
  verifier_args+=(--runtime-aar "$runtime_aar")
  "$SCRIPT_DIR/tools/verify-offline-apk.sh" "${verifier_args[@]}"
}

gradle_tasks=()
gradle_args=(
  "-PkompileJniLibsDir=$(dirname "$JNI_OUTPUT_DIR")"
  "-PkompileAppBuildDir=$APP_BUILD_ROOT"
  "-PsdxArtifactMode=$([[ "$RELEASE_CONSUMER" == "1" ]] && printf release-consumer || printf source-build)"
  "-PapkBuildId=$APK_BUILD_ID"
  "-PapkVersionCode=$APK_VERSION_CODE"
)
if [[ "$VARIANT" == "all" || "$VARIANT" == "vulkan" ]]; then
  gradle_args+=("-PsdxVulkanAar=$VULKAN_AAR")
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "hexagon" ]]; then
  gradle_args+=("-PsdxHexagonAar=$HEXAGON_AAR")
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "tensor-g3" ]]; then
  gradle_args+=("-PsdxTensorG3Aar=$TENSOR_G3_AAR")
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "tensor-g5" ]]; then
  gradle_args+=("-PsdxTensorG5Aar=$TENSOR_G5_AAR")
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "vulkan" ]]; then
  gradle_tasks+=(":app:assembleVulkanDebug")
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "hexagon" ]]; then
  gradle_tasks+=(":app:assembleHexagonDebug")
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "tensor-g3" ]]; then
  gradle_tasks+=(":app:assembleTensorG3Debug")
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "tensor-g5" ]]; then
  gradle_tasks+=(":app:assembleTensorG5Debug")
fi

gradle_execution_args=(--offline --no-daemon)
if [[ "$RAM_GRADLE_BUILD" == "1" ]]; then
  # The project-local execution history is disposable build state too. Keeping it in the
  # RAM root prevents a failed/aborted large APK build from corrupting the shared .gradle
  # history and avoids leaving another growing cache on the space-constrained workspace.
  gradle_execution_args+=(--project-cache-dir "$APP_BUILD_ROOT/project-cache")
fi
if [[ "${KOMPILE_ANDROID_GRADLE_RERUN_TASKS:-0}" == "1" ]]; then
  gradle_execution_args+=(--rerun-tasks)
fi
"$SCRIPT_DIR/gradlew" -p "$SCRIPT_DIR" \
  "${gradle_execution_args[@]}" "${gradle_args[@]}" "${gradle_tasks[@]}"

mkdir -p "$OUTPUT_DIR"
stage_apk_exactly() {
  local source_apk="$1"
  local staging_apk="$2"
  local source_digest staged_digest source_digest_after attempt

  # GNU `sync FILE` issues a file-level fsync. Do not use `sync -f FILE` here:
  # that invokes syncfs for the entire backing filesystem and can report an
  # unrelated filesystem-wide EIO after this APK inode was already durable.
  sync "$source_apk"
  source_digest="$(sha256sum "$source_apk" | cut -d ' ' -f 1)"
  for attempt in 1 2 3; do
    rm -f -- "$staging_apk"
    # Never publish the Gradle output inode itself. A userspace stream into RAM
    # severs publication from delayed Zip/Gradle writes and Btrfs copy behavior.
    if ! dd if="$source_apk" of="$staging_apk" bs=16M conv=fsync status=none; then
      printf 'APK RAM staging copy failed (attempt %d/3): %s\n' "$attempt" "$source_apk" >&2
      continue
    fi
    sync "$staging_apk"
    staged_digest="$(sha256sum "$staging_apk" | cut -d ' ' -f 1)"
    source_digest_after="$(sha256sum "$source_apk" | cut -d ' ' -f 1)"
    if [[ "$source_digest_after" != "$source_digest" ]]; then
      printf 'Gradle APK changed during publication staging: before=%s after=%s file=%s\n' \
        "$source_digest" "$source_digest_after" "$source_apk" >&2
      return 1
    fi
    if [[ "$staged_digest" == "$source_digest" ]]; then
      printf '%s\n' "$staged_digest"
      return 0
    fi
    printf 'APK RAM staging mismatch (attempt %d/3): source=%s staged=%s\n' \
      "$attempt" "$source_digest" "$staged_digest" >&2
  done
  return 1
}

promote_apk() {
  local source_apk="$1"
  local stable_filename="$2"
  local variant="$3"
  local base="${stable_filename%.apk}"
  local stable_apk="$OUTPUT_DIR/$stable_filename"
  local ram_staging output_staging digest verified_digest published_digest final_digest
  local unique_apk stable_link unique_hash stable_hash stable_digest previous_apk
  local source_bytes available_bytes source_real disposable_outputs_real
  local -a capacity_lines=()

  if [[ ! -d "$APK_STAGING_ROOT" || ! -w "$APK_STAGING_ROOT" ]]; then
    echo "APK RAM staging root must be an existing writable directory: $APK_STAGING_ROOT" >&2
    return 1
  fi
  mapfile -t capacity_lines < <(df -B1 --output=avail "$APK_STAGING_ROOT")
  available_bytes="${capacity_lines[1]//[[:space:]]/}"
  source_bytes="$(stat -c %s "$source_apk")"
  if [[ ! "$available_bytes" =~ ^[0-9]+$ ]] ||
      (( available_bytes < source_bytes + 67108864 )); then
    printf 'APK RAM staging lacks capacity: required=%s available=%s root=%s\n' \
      "$((source_bytes + 67108864))" "${available_bytes:-unknown}" "$APK_STAGING_ROOT" >&2
    return 1
  fi

  ram_staging="$(mktemp "$APK_STAGING_ROOT/.${base}.ram.XXXXXX.apk")"
  TEMPORARY_FILES+=("$ram_staging")
  if ! digest="$(stage_apk_exactly "$source_apk" "$ram_staging")"; then
    echo "Could not stage an exact APK in RAM for verification: $source_apk" >&2
    return 1
  fi
  if ! verify_apk "$ram_staging" "$variant"; then
    return 1
  fi
  sync "$ram_staging"
  verified_digest="$(sha256sum "$ram_staging" | cut -d ' ' -f 1)"
  if [[ "$verified_digest" != "$digest" ]]; then
    printf 'Verified APK bytes changed in RAM: before=%s after=%s\n' \
      "$digest" "$verified_digest" >&2
    return 1
  fi
  unzip -tq "$ram_staging" >/dev/null || {
    echo "RAM-staged APK failed its post-verification Zip integrity check: $ram_staging" >&2
    return 1
  }

  # The fully verified RAM copy is now the publication source. Remove only the
  # canonical disposable Gradle APK so multi-gigabyte build output does not leak
  # or compete with the final fresh output inode. Existing promoted APKs remain
  # untouched until the replacement has passed every check below.
  source_real="$(realpath -e -- "$source_apk")" || return 1
  disposable_outputs_real="$(realpath -m -- "$APP_BUILD_ROOT/outputs")" || return 1
  if [[ -L "$source_apk" || "$source_real" != "$disposable_outputs_real/"* ]]; then
    echo "Refusing to remove non-canonical Gradle APK after RAM verification: $source_apk" >&2
    return 1
  fi
  rm -f -- "$source_real"
  echo "Removed verified disposable Gradle APK: $source_real"

  output_staging="$(mktemp "$OUTPUT_DIR/.${base}.publish.XXXXXX.apk")"
  TEMPORARY_FILES+=("$output_staging")
  if ! dd if="$ram_staging" of="$output_staging" bs=16M conv=fsync status=none; then
    echo "Could not stream the verified APK into a fresh publication inode: $output_staging" >&2
    return 1
  fi
  sync "$output_staging"
  published_digest="$(sha256sum "$output_staging" | cut -d ' ' -f 1)"
  if [[ "$published_digest" != "$digest" ]]; then
    printf 'Published APK copy differs from verified RAM bytes: verified=%s published=%s\n' \
      "$digest" "$published_digest" >&2
    return 1
  fi
  unzip -tq "$output_staging" >/dev/null || {
    echo "Published APK staging inode failed its Zip integrity check: $output_staging" >&2
    return 1
  }

  unique_apk="$OUTPUT_DIR/${base}-${APK_BUILD_ID}-${digest:0:12}.apk"
  mv -f -- "$output_staging" "$unique_apk"
  sync "$unique_apk"
  final_digest="$(sha256sum "$unique_apk" | cut -d ' ' -f 1)"
  if [[ "$final_digest" != "$digest" ]]; then
    printf 'APK changed after final rename: verified=%s final=%s file=%s\n' \
      "$digest" "$final_digest" "$unique_apk" >&2
    return 1
  fi
  unzip -tq "$unique_apk" >/dev/null || {
    echo "Final APK failed its Zip integrity check: $unique_apk" >&2
    return 1
  }

  stable_link="$(mktemp "$OUTPUT_DIR/.${base}.latest.XXXXXX.apk")"
  TEMPORARY_FILES+=("$stable_link")
  rm -f -- "$stable_link"
  ln -- "$unique_apk" "$stable_link"
  stable_digest="$(sha256sum "$stable_link" | cut -d ' ' -f 1)"
  if [[ "$stable_digest" != "$digest" ]]; then
    printf 'Stable APK link differs before promotion: verified=%s stable=%s\n' \
      "$digest" "$stable_digest" >&2
    return 1
  fi
  mv -Tf -- "$stable_link" "$stable_apk"
  sync "$stable_apk"
  stable_digest="$(sha256sum "$stable_apk" | cut -d ' ' -f 1)"
  final_digest="$(sha256sum "$unique_apk" | cut -d ' ' -f 1)"
  if [[ "$stable_digest" != "$digest" || "$final_digest" != "$digest" ]]; then
    printf 'Final APK/side-link bytes are unstable: verified=%s unique=%s stable=%s\n' \
      "$digest" "$final_digest" "$stable_digest" >&2
    return 1
  fi
  unzip -tq "$stable_apk" >/dev/null || {
    echo "Stable APK failed its final Zip integrity check: $stable_apk" >&2
    return 1
  }

  unique_hash="$(mktemp "$OUTPUT_DIR/.${base}.unique-sha256.XXXXXX")"
  TEMPORARY_FILES+=("$unique_hash")
  printf '%s  %s\n' "$digest" "$unique_apk" > "$unique_hash"
  sync "$unique_hash"
  mv -f -- "$unique_hash" "$unique_apk.sha256"
  stable_hash="$(mktemp "$OUTPUT_DIR/.${base}.sha256.XXXXXX")"
  TEMPORARY_FILES+=("$stable_hash")
  printf '%s  %s\n' "$digest" "$stable_apk" > "$stable_hash"
  sync "$stable_hash"
  mv -f -- "$stable_hash" "$stable_apk.sha256"

  final_digest="$(sha256sum "$unique_apk" | cut -d ' ' -f 1)"
  stable_digest="$(sha256sum "$stable_apk" | cut -d ' ' -f 1)"
  if [[ "$final_digest" != "$digest" || "$stable_digest" != "$digest" ]]; then
    printf 'APK bytes changed while publishing checksums: verified=%s unique=%s stable=%s\n' \
      "$digest" "$final_digest" "$stable_digest" >&2
    return 1
  fi

  rm -f -- "$ram_staging"
  shopt -s nullglob
  for previous_apk in "$OUTPUT_DIR/${base}-"*.apk; do
    if [[ "$previous_apk" != "$unique_apk" ]]; then
      rm -f -- "$previous_apk" "$previous_apk.sha256"
    fi
  done
  shopt -u nullglob
  echo "Promoted verified APK: variant=$variant buildId=$APK_BUILD_ID versionCode=$APK_VERSION_CODE file=$unique_apk sha256=$digest"
}

if [[ "$VARIANT" == "all" || "$VARIANT" == "vulkan" ]]; then
  promote_apk \
    "$APP_BUILD_ROOT/outputs/apk/vulkan/debug/app-vulkan-debug.apk" \
    "kompile-offline-graph-chat-vulkan.apk" \
    vulkan
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "hexagon" ]]; then
  promote_apk \
    "$APP_BUILD_ROOT/outputs/apk/hexagon/debug/app-hexagon-debug.apk" \
    "kompile-offline-graph-chat-hexagon.apk" \
    hexagon
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "tensor-g3" ]]; then
  promote_apk \
    "$APP_BUILD_ROOT/outputs/apk/tensorG3/debug/app-tensorG3-debug.apk" \
    "kompile-offline-graph-chat-tensor-g3-pixel-8a.apk" \
    tensorG3
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "tensor-g5" ]]; then
  promote_apk \
    "$APP_BUILD_ROOT/outputs/apk/tensorG5/debug/app-tensorG5-debug.apk" \
    "kompile-offline-graph-chat-tensor-g5.apk" \
    tensorG5
fi

echo "Offline Android accelerator APKs: $OUTPUT_DIR"
