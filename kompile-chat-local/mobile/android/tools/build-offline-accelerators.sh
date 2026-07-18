#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<'USAGE'
Usage: build-offline-accelerators.sh [options]

Options:
  --variant <all|vulkan|hexagon|tensor-g3|tensor-g5>  Build selection (default: all)
  --vulkan-aar <file>                Android Vulkan SDX AAR
  --hexagon-aar <file>               Qualcomm SDX AAR
  --tensor-g3-aar <file>             Pixel 8a Tensor G3 NNAPI SDX AAR
  --tensor-g5-aar <file>             Google Tensor G5 direct-NPU SDX AAR
  --tensor-aar <file>                Deprecated alias for --tensor-g5-aar
  --graph-library <file>             Android Graal graph AOT shared library
  --javacpp-jar <file>               JavaCPP 1.5.13 build JAR
  --android-sdk <dir>                Android SDK root (or ANDROID_HOME)
  --android-ndk <dir>                Android NDK used by runtime verifiers
  --java-home <dir>                  JDK 17 (or JAVA_HOME)
  --maven <file>                     Maven executable (default: mvn)
  --skip-maven                       Reuse already-installed Kompile jars
  --output <dir>                     Output directory (default: build/offline-dist)

The build is always dependency-offline and emits signed debug/research APKs.
None of the APKs request INTERNET permission. Models are separate licensed inputs.
USAGE
}

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
DL4J_ROOT="${DL4J_ROOT:-$REPO_ROOT/../deeplearning4j}"
VARIANT="all"
VULKAN_AAR="${SDX_VULKAN_AAR:-$DL4J_ROOT/libnd4j/build/mobile/vulkan/dist/sdx-runtime-android-arm64-vulkan.aar}"
HEXAGON_AAR="${SDX_HEXAGON_AAR:-$DL4J_ROOT/libnd4j/build/mobile/hexagon/dist/sdx-runtime-android-arm64-hexagon.aar}"
TENSOR_G3_AAR="${SDX_TENSOR_G3_AAR:-$DL4J_ROOT/libnd4j/build/mobile/tensor-g3/dist/sdx-runtime-android-arm64-tensor-g3.aar}"
TENSOR_G5_AAR="${SDX_TENSOR_G5_AAR:-$DL4J_ROOT/libnd4j/build/mobile/google-tensor-g5/dist/sdx-chat-runtime-android-arm64-google-tensor-g5.aar}"
GRAPH_MODULE="$REPO_ROOT/kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local"
GRAPH_LIBRARY="${KOMPILE_GRAPH_ANDROID_SO:-$GRAPH_MODULE/target/android-aot/jni/arm64-v8a/libkompile_reasoning_android.so}"
JAVACPP_JAR="${JAVACPP_JAR:-}"
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ANDROID_NDK_ARG="${ANDROID_NDK:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}}"
JAVA_HOME_ARG="${JAVA_HOME:-}"
MAVEN="${MAVEN:-$REPO_ROOT/mvnw}"
SKIP_MAVEN=0
OUTPUT_DIR="$SCRIPT_DIR/build/offline-dist"

stage_artifact() {
  local source_file="$1"
  local target_file="$2"
  mkdir -p "$(dirname "$target_file")"
  if [[ -e "$target_file" && "$source_file" -ef "$target_file" ]]; then
    return
  fi
  cp "$source_file" "$target_file"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --variant) VARIANT="${2:?missing value for --variant}"; shift 2 ;;
    --vulkan-aar) VULKAN_AAR="${2:?missing value for --vulkan-aar}"; shift 2 ;;
    --hexagon-aar) HEXAGON_AAR="${2:?missing value for --hexagon-aar}"; shift 2 ;;
    --tensor-g3-aar) TENSOR_G3_AAR="${2:?missing value for --tensor-g3-aar}"; shift 2 ;;
    --tensor-g5-aar|--tensor-aar) TENSOR_G5_AAR="${2:?missing value for $1}"; shift 2 ;;
    --graph-library) GRAPH_LIBRARY="${2:?missing value for --graph-library}"; shift 2 ;;
    --javacpp-jar) JAVACPP_JAR="${2:?missing value for --javacpp-jar}"; shift 2 ;;
    --android-sdk) ANDROID_SDK="${2:?missing value for --android-sdk}"; shift 2 ;;
    --android-ndk) ANDROID_NDK_ARG="${2:?missing value for --android-ndk}"; shift 2 ;;
    --java-home) JAVA_HOME_ARG="${2:?missing value for --java-home}"; shift 2 ;;
    --maven) MAVEN="${2:?missing value for --maven}"; shift 2 ;;
    --skip-maven) SKIP_MAVEN=1; shift ;;
    --output) OUTPUT_DIR="${2:?missing value for --output}"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

case "$VARIANT" in
  all|vulkan|hexagon|tensor-g3|tensor-g5) ;;
  *) echo "Invalid --variant: $VARIANT" >&2; exit 2 ;;
esac
[[ "$(uname -s)" == "Linux" ]] || {
  echo "Android accelerator packaging requires a Linux/GNU build host" >&2
  exit 1
}

[[ -d "$ANDROID_SDK" ]] || { echo "Android SDK not found: $ANDROID_SDK" >&2; exit 1; }
[[ -d "$ANDROID_NDK_ARG" ]] || { echo "Android NDK not found: $ANDROID_NDK_ARG" >&2; exit 1; }
[[ -x "$JAVA_HOME_ARG/bin/java" ]] || { echo "JDK not found: $JAVA_HOME_ARG" >&2; exit 1; }
command -v "$MAVEN" >/dev/null 2>&1 || [[ -x "$MAVEN" ]] || {
  echo "Maven executable not found: $MAVEN" >&2
  exit 1
}
[[ -s "$GRAPH_LIBRARY" ]] || {
  echo "Android graph AOT library not found: $GRAPH_LIBRARY" >&2
  exit 1
}

export JAVA_HOME="$JAVA_HOME_ARG"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$ANDROID_SDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK"

graph_binding_args=(
  --android-ndk "$ANDROID_NDK_ARG"
  --graph-library "$GRAPH_LIBRARY"
)
if [[ -n "$JAVACPP_JAR" ]]; then
  graph_binding_args+=(--javacpp-jar "$JAVACPP_JAR")
fi
"$SCRIPT_DIR/tools/build-graph-javacpp.sh" "${graph_binding_args[@]}"

if [[ "$VARIANT" == "all" || "$VARIANT" == "vulkan" ]]; then
  [[ -s "$VULKAN_AAR" ]] || { echo "Vulkan AAR not found: $VULKAN_AAR" >&2; exit 1; }
  "$DL4J_ROOT/libnd4j/tools/mobile/verify-android-accelerator-aar.sh" \
    --aar "$VULKAN_AAR" \
    --variant vulkan \
    --native-library nd4jvulkan \
    --accelerator NONE \
    --gpu-target VULKAN \
    --device-ready \
    --android-ndk "$ANDROID_NDK_ARG"
  stage_artifact "$VULKAN_AAR" \
    "$SCRIPT_DIR/app/libs/vulkan/sdx-runtime-android-arm64-vulkan.aar"
fi

if [[ "$VARIANT" == "all" || "$VARIANT" == "hexagon" ]]; then
  [[ -s "$HEXAGON_AAR" ]] || { echo "Hexagon AAR not found: $HEXAGON_AAR" >&2; exit 1; }
  "$DL4J_ROOT/libnd4j/tools/mobile/verify-android-accelerator-aar.sh" \
    --aar "$HEXAGON_AAR" \
    --variant hexagon \
    --native-library nd4jhexagon \
    --accelerator QUALCOMM_HEXAGON_HTP \
    --android-ndk "$ANDROID_NDK_ARG"
  stage_artifact "$HEXAGON_AAR" \
    "$SCRIPT_DIR/app/libs/hexagon/sdx-runtime-android-arm64-hexagon.aar"
fi

if [[ "$VARIANT" == "all" || "$VARIANT" == "tensor-g3" ]]; then
  [[ -s "$TENSOR_G3_AAR" ]] || { echo "Tensor G3 AAR not found: $TENSOR_G3_AAR" >&2; exit 1; }
  "$DL4J_ROOT/libnd4j/tools/mobile/verify-android-accelerator-aar.sh" \
    --aar "$TENSOR_G3_AAR" \
    --variant tensor-g3 \
    --native-library nd4jnnapi \
    --accelerator NNAPI_ACCELERATOR_ONLY \
    --gpu-target AUTO \
    --device-ready \
    --android-ndk "$ANDROID_NDK_ARG"
  stage_artifact "$TENSOR_G3_AAR" \
    "$SCRIPT_DIR/app/libs/tensor-g3/sdx-runtime-android-arm64-tensor-g3.aar"
fi

if [[ "$VARIANT" == "all" || "$VARIANT" == "tensor-g5" ]]; then
  [[ -s "$TENSOR_G5_AAR" ]] || { echo "Tensor G5 AAR not found: $TENSOR_G5_AAR" >&2; exit 1; }
  "$DL4J_ROOT/libnd4j/tools/mobile/verify-google-tensor-g5-aar.sh" \
    --aar "$TENSOR_G5_AAR" \
    --android-ndk "$ANDROID_NDK_ARG"
  stage_artifact "$TENSOR_G5_AAR" \
    "$SCRIPT_DIR/app/libs/tensor-g5/sdx-chat-runtime-android-arm64-google-tensor-g5.aar"
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
  "$SCRIPT_DIR/tools/verify-offline-apk.sh" \
    --apk "$apk" \
    --variant "$variant" \
    --config "$SCRIPT_DIR/accelerators.json" \
    --android-sdk "$ANDROID_SDK" \
    --android-ndk "$ANDROID_NDK_ARG"
}

gradle_tasks=()
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

"$SCRIPT_DIR/gradlew" -p "$SCRIPT_DIR" --offline --no-daemon "${gradle_tasks[@]}"

mkdir -p "$OUTPUT_DIR"
if [[ "$VARIANT" == "all" || "$VARIANT" == "vulkan" ]]; then
  source_apk="$SCRIPT_DIR/app/build/outputs/apk/vulkan/debug/app-vulkan-debug.apk"
  target_apk="$OUTPUT_DIR/kompile-offline-graph-chat-vulkan.apk"
  cp "$source_apk" "$target_apk"
  verify_apk "$target_apk" vulkan
  sha256sum "$target_apk" > "$target_apk.sha256"
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "hexagon" ]]; then
  source_apk="$SCRIPT_DIR/app/build/outputs/apk/hexagon/debug/app-hexagon-debug.apk"
  target_apk="$OUTPUT_DIR/kompile-offline-graph-chat-hexagon.apk"
  cp "$source_apk" "$target_apk"
  verify_apk "$target_apk" hexagon
  sha256sum "$target_apk" > "$target_apk.sha256"
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "tensor-g3" ]]; then
  source_apk="$SCRIPT_DIR/app/build/outputs/apk/tensorG3/debug/app-tensorG3-debug.apk"
  target_apk="$OUTPUT_DIR/kompile-offline-graph-chat-tensor-g3-pixel-8a.apk"
  cp "$source_apk" "$target_apk"
  verify_apk "$target_apk" tensorG3
  sha256sum "$target_apk" > "$target_apk.sha256"
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "tensor-g5" ]]; then
  source_apk="$SCRIPT_DIR/app/build/outputs/apk/tensorG5/debug/app-tensorG5-debug.apk"
  target_apk="$OUTPUT_DIR/kompile-offline-graph-chat-tensor-g5.apk"
  cp "$source_apk" "$target_apk"
  verify_apk "$target_apk" tensorG5
  sha256sum "$target_apk" > "$target_apk.sha256"
fi

echo "Offline Android accelerator APKs: $OUTPUT_DIR"
