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
  --work-root <dir>                  Canonical isolated producer and staging root
  --android-sdk <dir>                Android SDK root (or ANDROID_HOME)
  --android-ndk <dir>                Android NDK used by runtime verifiers
  --java-home <dir>                  JDK 17 (or JAVA_HOME)
  --maven <file>                     Maven executable (default: mvn)
  --skip-maven                       Reuse already-installed Kompile jars
  --reuse-receipted-producers        Reuse immutable producer artifacts from their verified
                                      historical receipts without coupling them to current sources
  --maven-artifacts                  Use passed Maven artifacts without source staging
  --sdx-release-version <version>    Independently pinned canonical SDK release version
  --sdx-release-manifest <file>      Canonical sdx-sdk-manifest.json
  --sdx-release-artifact-root <dir>  Directory containing its exact fileName artifacts
  --output <dir>                     Output directory (default: build/offline-dist)
  --build-id <id>                    Visible APK/build filename identity (default: UTC timestamp)
  --version-code <number>            Reproduce a historical build only when explicitly enabled
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
TENSOR_G3_CANONICAL_AAR="$DL4J_ROOT/libnd4j/build/mobile/tensor-g3/dist/sdx-runtime-android-arm64-tensor-g3.aar"
TENSOR_G3_NATIVE_AAR="$DL4J_ROOT/libnd4j/build/mobile/tensor-g3/native/sdx-runtime-sdk/dist/sdx-runtime-android-arm64-tensor-g3.aar"
TENSOR_G3_NATIVE_RECEIPT="$TENSOR_G3_NATIVE_AAR.build-receipt"
TENSOR_G3_AAR="${SDX_TENSOR_G3_AAR:-}"
TENSOR_G3_FULL_RECEIPT=""
TENSOR_G3_SOURCE_SHA256=""
TENSOR_G3_PROVENANCE_SHA256=""
SDX_AOT_PROVENANCE_SHA256=""
SDX_AOT_RECEIPT=""
if [[ -n "$TENSOR_G3_AAR" ]]; then
  TENSOR_G3_AAR_SOURCE="environment"
else
  TENSOR_G3_AAR_SOURCE="unresolved"
fi
TENSOR_G5_AAR="${SDX_TENSOR_G5_AAR:-$DL4J_ROOT/libnd4j/build/mobile/google-tensor-g5/dist/sdx-chat-runtime-android-arm64-google-tensor-g5.aar}"
GRAPH_MODULE="$REPO_ROOT/kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local"
GRAPH_LIBRARY="${KOMPILE_GRAPH_ANDROID_SO:-$GRAPH_MODULE/target/android-aot/jni/arm64-v8a/libkompile_reasoning_android.so}"
if [[ -n "${SDX_LLM_ANDROID_SDK:-}" ]]; then
  SDX_LLM_SDK="$SDX_LLM_ANDROID_SDK"
  SDX_LLM_SDK_SOURCE="environment"
else
  SDX_LLM_SDK="$DL4J_ROOT/nd4j/sdx-aot/target/android-aot"
  SDX_LLM_SDK_SOURCE="canonical"
fi
GRAPH_HEADER=""
GRAPH_VERIFIER=""
JAVACPP_JAR="${JAVACPP_JAR:-}"
if [[ -n "$JAVACPP_JAR" ]]; then
  JAVACPP_JAR_SOURCE="environment"
else
  JAVACPP_JAR_SOURCE="unresolved"
fi
JNI_OUTPUT_DIR="$SCRIPT_DIR/app/src/main/jniLibs/arm64-v8a"
JNI_OUTPUT_EXPLICIT=0
WORK_ROOT="${KOMPILE_ANDROID_WORK_ROOT:-}"
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [[ -n "$ANDROID_SDK" ]]; then
  ANDROID_SDK_SOURCE="environment"
else
  ANDROID_SDK_SOURCE="unresolved"
fi
ANDROID_NDK_ARG="${ANDROID_NDK:-${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-}}}"
if [[ -n "$ANDROID_NDK_ARG" ]]; then
  ANDROID_NDK_SOURCE="environment"
else
  ANDROID_NDK_SOURCE="unresolved"
fi
JAVA_HOME_ARG="${JAVA_HOME:-}"
if [[ -n "$JAVA_HOME_ARG" ]]; then
  JAVA_HOME_SOURCE="JAVA_HOME"
else
  JAVA_HOME_SOURCE="unresolved"
fi
MAVEN="${MAVEN:-}"
if [[ -n "$MAVEN" ]]; then
  MAVEN_SOURCE="environment"
else
  MAVEN_SOURCE="unresolved"
fi
SKIP_MAVEN=0
REUSE_RECEIPTED_PRODUCERS=0
MAVEN_ARTIFACTS=0
OUTPUT_DIR="$SCRIPT_DIR/build/offline-dist"
OUTPUT_EXPLICIT=0
SDX_RELEASE_VERSION="${SDX_RELEASE_VERSION:-}"
SDX_RELEASE_MANIFEST="${SDX_RELEASE_MANIFEST:-}"
SDX_RELEASE_ARTIFACT_ROOT="${SDX_RELEASE_ARTIFACT_ROOT:-}"
RELEASE_CONSUMER=0
AAR_OVERRIDE=0
RETAIN_STAGING="${KOMPILE_ANDROID_RETAIN_STAGING:-0}"
APK_BUILD_ID=""
APK_VERSION_CODE=""
APK_VERSION_CODE_OVERRIDE=0
if [[ -n "${KOMPILE_APK_STAGING_ROOT:-}" ]]; then
  APK_STAGING_ROOT="$KOMPILE_APK_STAGING_ROOT"
  APK_STAGING_EXPLICIT=1
else
  APK_STAGING_ROOT=/dev/shm
  APK_STAGING_EXPLICIT=0
fi
APP_BUILD_ROOT="$SCRIPT_DIR/app/build"
RAM_GRADLE_BUILD="${KOMPILE_ANDROID_RAM_GRADLE_BUILD:-0}"
CLEANUP_ONLY=0
TEMPORARY_FILES=()
TEMPORARY_DIRECTORIES=()

resolve_java_home() {
  local java_bin java_real

  if [[ -n "$JAVA_HOME_ARG" ]]; then
    return 0
  fi
  java_bin="$(command -v java 2>/dev/null || true)"
  if [[ -z "$java_bin" ]]; then
    return 0
  fi
  java_real="$(readlink -f -- "$java_bin" 2>/dev/null || true)"
  if [[ -z "$java_real" ]]; then
    java_real="$java_bin"
  fi
  JAVA_HOME_ARG="$(cd "$(dirname "$java_real")/.." && pwd -P)"
  JAVA_HOME_SOURCE="PATH"
}

validate_java_17() {
  local java_settings java_specification_version=""
  local line

  [[ -x "$JAVA_HOME_ARG/bin/java" && -x "$JAVA_HOME_ARG/bin/javac" ]] || {
    echo "JDK 17 not found; pass --java-home, set JAVA_HOME, or put a JDK 17 java on PATH (resolved source=$JAVA_HOME_SOURCE path=$JAVA_HOME_ARG)" >&2
    return 1
  }
  java_settings="$("$JAVA_HOME_ARG/bin/java" -XshowSettings:properties -version 2>&1)" || {
    echo "Could not execute resolved JDK: $JAVA_HOME_ARG/bin/java" >&2
    return 1
  }
  while IFS= read -r line; do
    case "$line" in
      *"java.specification.version ="*)
        java_specification_version="${line##*= }"
        break
        ;;
    esac
  done <<<"$java_settings"
  [[ "$java_specification_version" == "17" ]] || {
    echo "JDK 17 is required; resolved source=$JAVA_HOME_SOURCE path=$JAVA_HOME_ARG java.specification.version=${java_specification_version:-unknown}" >&2
    return 1
  }
}

sha256_file() {
  sha256sum "$1" | cut -d ' ' -f 1
}

require_receipt_value() {
  local receipt_label="$1"
  local field="$2"
  local recorded="$3"
  local expected="$4"

  [[ "$recorded" == "$expected" ]] && return 0
  printf '%s receipt mismatch: field=%s recorded=%q expected=%q\n' \
    "$receipt_label" "$field" "$recorded" "$expected" >&2
  return 1
}

tree_manifest_sha256() {
  local root="$1"
  local file relative mode digest
  [[ -d "$root" ]] || {
    echo "Tree manifest root is missing: $root" >&2
    return 1
  }
  if find "$root" -type l -print -quit | grep -q .; then
    echo "Tree manifest root contains a symlink: $root" >&2
    return 1
  fi
  while IFS= read -r -d '' file; do
    relative="${file#"$root"/}"
    mode="$(stat -c '%a' "$file")"
    digest="$(sha256_file "$file")"
    printf '%s\0%s\0%s\0' "$relative" "$mode" "$digest"
  done < <(find "$root" -type f -print0 | LC_ALL=C sort -z) |
    sha256sum | cut -d ' ' -f 1
}

archive_member_sha256() {
  local archive="$1"
  local member="$2"
  local archive_names
  archive_names="$(unzip -Z1 "$archive")" || {
    echo "Could not list AAR members: $archive" >&2
    return 1
  }
  grep -Fxq "$member" <<<"$archive_names" || {
    echo "Required AAR member is missing: archive=$archive member=$member" >&2
    return 1
  }
  unzip -p "$archive" "$member" | sha256sum | cut -d ' ' -f 1
}

dl4j_source_manifest_sha256() {
  local relative file mode digest
  local -a roots=(
    libnd4j
    nd4j/sdx-aot
    nd4j/nd4j-tokenizers
    nd4j/nd4j-backends/nd4j-api-parent/nd4j-api
    nd4j/nd4j-backends/nd4j-backend-impls/nd4j-cpu-backend-common
    nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx
    nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx-model
    nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx-preset
  )
  local -a excludes=(
    ':(top,exclude)libnd4j/cmake/tests/**'
  )
  {
    git -C "$DL4J_ROOT" ls-files -z --cached --others --exclude-standard -- "${roots[@]}" "${excludes[@]}" |
      LC_ALL=C sort -z |
      while IFS= read -r -d '' relative; do
        file="$DL4J_ROOT/$relative"
        [[ -f "$file" ]] || continue
        mode="$(stat -c '%a' "$file")"
        digest="$(sha256_file "$file")"
        printf '%s\0%s\0%s\0' "$relative" "$mode" "$digest"
      done
  } | sha256sum | cut -d ' ' -f 1
}

dl4j_aot_source_manifest_sha256() {
  local source_manifest_helper="$DL4J_ROOT/nd4j/sdx-aot/src/main/android/source-manifest.sh"
  local -a roots=(
    pom.xml
    build-scripts/release/native-platform.sh
    nd4j/pom.xml
    nd4j/nd4j-backends/pom.xml
    nd4j/nd4j-backends/nd4j-api-parent/pom.xml
    nd4j/nd4j-backends/nd4j-backend-impls/pom.xml
    nd4j/sdx-aot/pom.xml
    nd4j/sdx-aot/include
    nd4j/sdx-aot/src/main/java
    nd4j/sdx-aot/src/main/resources
    nd4j/sdx-aot/src/main/assembly
    nd4j/sdx-aot/src/main/linker
    nd4j/sdx-aot/src/main/android/EmbeddedClasspathAudit.java
    nd4j/sdx-aot/src/main/android/JavaCppNativeImageReachability.java
    nd4j/sdx-aot/src/main/android/source-manifest.sh
    nd4j/sdx-aot/src/main/android/stage-module-resources.sh
    nd4j/nd4j-backends/nd4j-api-parent/nd4j-api
    nd4j/nd4j-backends/nd4j-api-parent/nd4j-native-api
    nd4j/nd4j-backends/nd4j-backend-impls/nd4j-presets-common
    nd4j/nd4j-backends/nd4j-backend-impls/nd4j-native-preset
    nd4j/nd4j-backends/nd4j-backend-impls/nd4j-cpu-backend-common
    nd4j/nd4j-backends/nd4j-backend-impls/nd4j-native
    nd4j/nd4j-tokenizers/tokenizers-native-preset
    nd4j/nd4j-tokenizers/tokenizers-native
    nd4j/samediff-llm
    nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx
    nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx-model
    nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx-preset
  )
  [[ -r "$source_manifest_helper" ]] || {
    echo "SDX AOT source manifest helper is missing: $source_manifest_helper" >&2
    return 1
  }
  # shellcheck source=/dev/null
  source "$source_manifest_helper"
  sdx_git_source_manifest_sha256 "$DL4J_ROOT" "${roots[@]}"
}

module_source_manifest_sha256() {
  local root="$1"
  local relative file mode digest
  {
    git -C "$DL4J_ROOT" ls-files -z --cached --others --exclude-standard -- "$root" |
      LC_ALL=C sort -z |
      while IFS= read -r -d '' relative; do
        file="$DL4J_ROOT/$relative"
        [[ -f "$file" ]] || continue
        mode="$(stat -c '%a' "$file")"
        digest="$(sha256_file "$file")"
        printf '%s\0%s\0%s\0' "$relative" "$mode" "$digest"
      done
  } | sha256sum | cut -d ' ' -f 1
}

dl4j_aot_module_source_manifest_sha256() {
  local root="${1:?module source root is required}"
  local source_manifest_helper="$DL4J_ROOT/nd4j/sdx-aot/src/main/android/source-manifest.sh"
  [[ -r "$source_manifest_helper" ]] || {
    echo "SDX AOT source manifest helper is missing: $source_manifest_helper" >&2
    return 1
  }
  # shellcheck source=/dev/null
  source "$source_manifest_helper"
  sdx_git_source_manifest_sha256 "$DL4J_ROOT" "$root"
}

declare -A SDX_AOT_RECEIPT_VALUES=()
load_sdx_aot_receipt() {
  local receipt="$1"
  local line key value
  SDX_AOT_RECEIPT_VALUES=()
  [[ -s "$receipt" ]] || {
    echo "SDX AOT SDK build receipt is missing: $receipt" >&2
    return 1
  }
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" == *=* ]] || {
      echo "Malformed SDX AOT SDK receipt line: $line" >&2
      return 1
    }
    key="${line%%=*}"
    value="${line#*=}"
    [[ "$key" =~ ^[a-z][a-z0-9_]*$ && -n "$value" ]] || {
      echo "Invalid SDX AOT SDK receipt field: $line" >&2
      return 1
    }
    case "$key" in
      format|stage|cache_schema|native_image_build_mode|native_image_optimization|javacpp_reachability_generator|base_sdk|process_blas_symbols_abi|process_blas_symbols_capability|build_script|object_builder|svm_support|maven|java_home|javacpp_jar|android_api|android_abi|native_library_count|native_stage_key|managed_stage_key|producer|native_packaging|standalone_sdx_cpu_included|*_sha256) ;;
      *) echo "Unknown non-digest SDX AOT SDK receipt field: $key" >&2; return 1 ;;
    esac
    case "$key" in
      native_image_optimization_config_sha256)
        [[ "$value" == "none" || "$value" =~ ^[0-9a-f]{64}$ ]] || {
          echo "Invalid Native Image optimization config SHA-256 in SDX AOT SDK receipt: $line" >&2
          return 1
        }
        ;;
      base_sdk_receipt_sha256)
        [[ "$value" == "not-applicable" || "$value" =~ ^[0-9a-f]{64}$ ]] || {
          echo "Invalid base SDK receipt SHA-256 in SDX AOT SDK receipt: $line" >&2
          return 1
        }
        ;;
      *_sha256)
        [[ "$value" =~ ^[0-9a-f]{64}$ ]] || {
          echo "Invalid SHA-256 in SDX AOT SDK receipt: $line" >&2
          return 1
        }
        ;;
      format|android_api|native_library_count|process_blas_symbols_abi)
        [[ "$value" =~ ^[0-9]+$ ]] || {
          echo "Invalid numeric field in SDX AOT SDK receipt: $line" >&2
          return 1
        }
        ;;
    esac
    [[ ! -v 'SDX_AOT_RECEIPT_VALUES[$key]' ]] || {
      echo "Duplicate SDX AOT SDK receipt field: $key" >&2
      return 1
    }
    SDX_AOT_RECEIPT_VALUES["$key"]="$value"
  done <"$receipt"
}

configure_work_root() {
  local tensor_g3_candidate

  [[ -n "$WORK_ROOT" ]] || return 0
  [[ "$JNI_OUTPUT_EXPLICIT" == "0" ]] || {
    echo "--work-root owns JNI staging; do not combine it with --jni-output" >&2
    return 1
  }
  [[ "$WORK_ROOT" != "/" ]] || {
    echo "Android work root must not be /" >&2
    return 1
  }
  [[ ! -L "$WORK_ROOT" ]] || {
    echo "Android work root must not be a symlink: $WORK_ROOT" >&2
    return 1
  }
  mkdir -p "$WORK_ROOT"
  WORK_ROOT="$(realpath -e -- "$WORK_ROOT")" || return 1
  [[ -d "$WORK_ROOT" && -w "$WORK_ROOT" ]] || {
    echo "Android work root must be a writable directory: $WORK_ROOT" >&2
    return 1
  }

  if (( APK_STAGING_EXPLICIT == 0 )); then
    APK_STAGING_ROOT="$WORK_ROOT/apk-stage"
  fi
  JNI_OUTPUT_DIR="$WORK_ROOT/apk-jni/arm64-v8a"
  if (( OUTPUT_EXPLICIT == 0 )); then
    OUTPUT_DIR="$WORK_ROOT/apk-output"
  fi
  RAM_GRADLE_BUILD=1

  if [[ "$SDX_LLM_SDK_SOURCE" == "canonical" ]]; then
    SDX_LLM_SDK="$WORK_ROOT/aot-sdk/current"
    SDX_LLM_SDK_SOURCE="work-root"
  fi
  if [[ "$TENSOR_G3_AAR_SOURCE" == "unresolved" ]]; then
    tensor_g3_candidate="$WORK_ROOT/accelerator/tensor-g3/dist/sdx-runtime-android-arm64-tensor-g3.aar"
    TENSOR_G3_AAR="$tensor_g3_candidate"
    TENSOR_G3_AAR_SOURCE="work-root"
  fi
}

resolve_android_sdk() {
  local candidate
  local -a candidates=(
    "$HOME/Android/Sdk"
    "$HOME/dev-apps/android-sdk"
  )

  [[ -z "$ANDROID_SDK" ]] || return 0
  for candidate in "${candidates[@]}"; do
    if [[ -d "$candidate" ]]; then
      ANDROID_SDK="$(realpath -e -- "$candidate")"
      ANDROID_SDK_SOURCE="host-discovery"
      return 0
    fi
  done
}

autoconfigure_from_sdx_aot_receipt() {
  local receipt="$SDX_LLM_SDK/metadata/build-receipt"
  local candidate candidate_hash
  local -a matching_ndks=()
  local -a installed_ndks=()

  load_sdx_aot_receipt "$receipt" || return 1
  if [[ -z "$JAVA_HOME_ARG" ]]; then
    JAVA_HOME_ARG="${SDX_AOT_RECEIPT_VALUES[java_home]:-}"
    JAVA_HOME_SOURCE="sdx-aot-receipt"
  fi
  if [[ -z "$MAVEN" ]]; then
    MAVEN="${SDX_AOT_RECEIPT_VALUES[maven]:-}"
    MAVEN_SOURCE="sdx-aot-receipt"
  fi
  if [[ -z "$JAVACPP_JAR" ]]; then
    JAVACPP_JAR="${SDX_AOT_RECEIPT_VALUES[javacpp_jar]:-}"
    JAVACPP_JAR_SOURCE="sdx-aot-receipt"
  fi

  if [[ -z "$ANDROID_NDK_ARG" && -d "$ANDROID_SDK/ndk" ]]; then
    shopt -s nullglob
    installed_ndks=("$ANDROID_SDK"/ndk/*)
    shopt -u nullglob
    for candidate in "${installed_ndks[@]}"; do
      [[ -s "$candidate/source.properties" ]] || continue
      candidate_hash="$(sha256_file "$candidate/source.properties")"
      if [[ "$candidate_hash" == "${SDX_AOT_RECEIPT_VALUES[ndk_revision_sha256]:-}" ]]; then
        matching_ndks+=("$candidate")
      fi
    done
    if (( ${#matching_ndks[@]} == 1 )); then
      ANDROID_NDK_ARG="$(realpath -e -- "${matching_ndks[0]}")"
      ANDROID_NDK_SOURCE="sdx-aot-receipt"
    elif (( ${#matching_ndks[@]} > 1 )); then
      echo "Multiple installed Android NDKs match the SDX AOT receipt; pass --android-ndk explicitly" >&2
      return 1
    fi
  fi
}

verify_sdx_aot_sdk_receipt() {
  local receipt="$SDX_LLM_SDK/metadata/build-receipt"
  local native_manifest="$SDX_LLM_SDK/metadata/cmake-owned-native-libraries.txt"
  local native_bytes="$SDX_LLM_SDK/metadata/sdk-native-bytes.txt"
  local base_native_bytes="$SDX_LLM_SDK/metadata/base-sdk-native-bytes.txt"
  local classpath_manifest="$SDX_LLM_SDK/metadata/classpath-bytes.txt"
  local javacpp_reachability_manifest="$SDX_LLM_SDK/metadata/javacpp-native-image-reachability.txt"
  local fresh_class_builds="$SDX_LLM_SDK/metadata/fresh-class-builds.txt"
  local jdk_support_receipt="$SDX_LLM_SDK/metadata/jdk-support-receipt"
  local optimization_metadata="$SDX_LLM_SDK/metadata/native-image-optimization.txt"
  local generated_javacpp="$SDX_LLM_SDK/metadata/jnijavacpp.cpp"
  local generated_sdx="$SDX_LLM_SDK/metadata/jnisdx_llm.cpp"
  local staged_javacpp_lifecycle_bridge="$SDX_LLM_SDK/metadata/javacpp_jni_lifecycle.cpp"
  local expected_build_script="$DL4J_ROOT/nd4j/sdx-aot/src/main/android/build-android-aot-sdk.sh"
  local expected_object_builder="$GRAPH_MODULE/build-android-ndk.sh"
  local expected_linker_script="$DL4J_ROOT/nd4j/sdx-aot/src/main/linker/sdx_exports.lds"
  local expected_javacpp_reachability_generator="$DL4J_ROOT/nd4j/sdx-aot/src/main/android/JavaCppNativeImageReachability.java"
  local expected_javacpp_lifecycle_bridge="$DL4J_ROOT/nd4j/sdx-aot/src/main/android/javacpp_jni_lifecycle.cpp"
  local expected_source expected_inputs actual_native_bytes actual_native_set declared_native_set library_name key canonical_sdk
  local artifact source_sha tree_sha extra expected_maven maven_version java_version
  local base_sdk base_sdk_actual_sha base_sdk_receipt svm_support importer_library importer_hash importer_entry
  local expected_build_mode expected_optimization expected_optimization_config_sha256 optimization_contract process_symbol_contract
  declare -A expected_module_roots=(
    [nd4j-api]="nd4j/nd4j-backends/nd4j-api-parent/nd4j-api"
    [nd4j-native-api]="nd4j/nd4j-backends/nd4j-api-parent/nd4j-native-api"
    [nd4j-presets-common]="nd4j/nd4j-backends/nd4j-backend-impls/nd4j-presets-common"
    [nd4j-native-preset]="nd4j/nd4j-backends/nd4j-backend-impls/nd4j-native-preset"
    [nd4j-cpu-backend-common]="nd4j/nd4j-backends/nd4j-backend-impls/nd4j-cpu-backend-common"
    [nd4j-native]="nd4j/nd4j-backends/nd4j-backend-impls/nd4j-native"
    [tokenizers-native-preset]="nd4j/nd4j-tokenizers/tokenizers-native-preset"
    [tokenizers-native]="nd4j/nd4j-tokenizers/tokenizers-native"
    [samediff-llm]="nd4j/samediff-llm"
    [nd4j-sdx]="nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx"
    [nd4j-sdx-model]="nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx-model"
    [nd4j-sdx-preset]="nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx-preset"
    [sdx-aot]="nd4j/sdx-aot"
  )
  declare -A seen_fresh_class_builds=()
  declare -A fresh_class_hashes=()
  local -a required=(
    format stage inputs_sha256 native_image_build_mode native_image_optimization
    native_image_optimization_config_sha256 source_manifest_sha256 classes_sha256
    model_classes_sha256 fresh_class_builds_sha256
    classpath_manifest_sha256 runtime_dependency_manifest_sha256
    maven_dependency_arguments_sha256 object_stage_inputs_sha256
    javacpp_reachability_generator
    javacpp_reachability_generator_sha256 javacpp_reachability_config_sha256
    javacpp_jni_config_sha256
    javacpp_initialization_config_sha256 javacpp_reachability_manifest_sha256
    object_sha256
    base_sdk base_sdk_sha256 base_sdk_receipt_sha256 base_sdk_native_sha256
    process_blas_symbols_abi process_blas_symbols_capability
    build_script build_script_sha256 native_image_cache_helper_sha256
    object_builder object_builder_sha256 jdk_support_receipt_sha256
    svm_support libjvm_sha256 liblibchelper_sha256
    maven maven_sha256 maven_version_sha256
    java_home java_version_sha256 linker_script_sha256
    javacpp_jar javacpp_jar_sha256 ndk_revision_sha256 graalvm_version_sha256
    android_api android_abi libsdx_sha256 libjnisdx_sha256
    libjnijavacpp_sha256 jnijavacpp_source_sha256
    javacpp_lifecycle_source_sha256 jnisdx_source_sha256
    native_manifest_sha256 sdk_native_bytes_sha256 native_library_count
  )

  load_sdx_aot_receipt "$receipt" || return 1
  canonical_sdk="$(realpath -e -- "$SDX_LLM_SDK")" || return 1
  if find "$canonical_sdk" -type l -print -quit | grep -q .; then
    echo "SDX AOT SDK generation contains a symlink: $canonical_sdk" >&2
    return 1
  fi
  if find "$canonical_sdk" -perm /0222 -print -quit | grep -q .; then
    echo "SDX AOT SDK generation contains a writable member: $canonical_sdk" >&2
    return 1
  fi
  for key in "${required[@]}"; do
    [[ -v 'SDX_AOT_RECEIPT_VALUES[$key]' ]] || {
      echo "SDX AOT SDK receipt omitted required field: $key" >&2
      return 1
    }
  done
  expected_build_mode="${SDX_AOT_RECEIPT_VALUES[native_image_build_mode]}"
  case "$expected_build_mode" in
    dev)
      expected_optimization="b"
      expected_optimization_config_sha256="$(printf '%s\n' 'Args = -Ob' | sha256sum | cut -d ' ' -f 1)"
      ;;
    production)
      expected_optimization="2"
      expected_optimization_config_sha256="none"
      ;;
    *)
      echo "Unsupported SDX AOT Native Image build mode: $expected_build_mode" >&2
      return 1
      ;;
  esac
  require_receipt_value "SDX AOT SDK" native_image_optimization "${SDX_AOT_RECEIPT_VALUES[native_image_optimization]}" "$expected_optimization" || return 1
  require_receipt_value "SDX AOT SDK" native_image_optimization_config_sha256 "${SDX_AOT_RECEIPT_VALUES[native_image_optimization_config_sha256]}" "$expected_optimization_config_sha256" || return 1
  require_receipt_value "SDX AOT SDK" process_blas_symbols_abi "${SDX_AOT_RECEIPT_VALUES[process_blas_symbols_abi]}" "1" || return 1
  require_receipt_value "SDX AOT SDK" process_blas_symbols_capability "${SDX_AOT_RECEIPT_VALUES[process_blas_symbols_capability]}" "nd4j_process_blas_symbols_abi_v1" || return 1
  [[ -f "$optimization_metadata" && ! -L "$optimization_metadata" && -s "$optimization_metadata" ]] || {
    echo "SDX AOT Native Image optimization metadata is unavailable or unsafe: $optimization_metadata" >&2
    return 1
  }
  [[ "$(wc -l < "$optimization_metadata")" -eq 3 ]] || {
    echo "SDX AOT Native Image optimization metadata has unexpected fields" >&2
    return 1
  }
  for optimization_contract in \
    "build_mode=$expected_build_mode" \
    "optimization=-O$expected_optimization" \
    "config_sha256=$expected_optimization_config_sha256"; do
    [[ "$(grep -Fxc "$optimization_contract" "$optimization_metadata")" -eq 1 ]] || {
      echo "SDX AOT Native Image optimization metadata omitted or duplicated: $optimization_contract" >&2
      return 1
    }
  done

  base_sdk="${SDX_AOT_RECEIPT_VALUES[base_sdk]}"
  if [[ -f "$base_sdk" && ! -L "$base_sdk" ]]; then
    base_sdk="$(realpath -e -- "$base_sdk")" || return 1
    base_sdk_actual_sha="$(sha256_file "$base_sdk")"
    require_receipt_value "SDX AOT SDK" base_sdk_receipt_sha256 "${SDX_AOT_RECEIPT_VALUES[base_sdk_receipt_sha256]}" "not-applicable" || return 1
  elif [[ -d "$base_sdk" && ! -L "$base_sdk" ]]; then
    base_sdk="$(realpath -e -- "$base_sdk")" || return 1
    if find "$base_sdk" -type l -print -quit | grep -q .; then
      echo "SDX AOT base SDK contains a symlink: $base_sdk" >&2
      return 1
    fi
    base_sdk_receipt="$base_sdk/metadata/build-receipt"
    [[ -f "$base_sdk_receipt" && ! -L "$base_sdk_receipt" && -s "$base_sdk_receipt" ]] || {
      echo "SDX AOT base SDK receipt is unavailable or unsafe: $base_sdk_receipt" >&2
      return 1
    }
    require_receipt_value "SDX AOT SDK" base_sdk_receipt_sha256 "${SDX_AOT_RECEIPT_VALUES[base_sdk_receipt_sha256]}" "$(sha256_file "$base_sdk_receipt")" || return 1
    for process_symbol_contract in \
      "process_blas_symbols_abi=1" \
      "process_blas_symbols_capability=nd4j_process_blas_symbols_abi_v1"; do
      [[ "$(grep -Fxc "$process_symbol_contract" "$base_sdk_receipt")" -eq 1 ]] || {
        echo "SDX AOT base SDK receipt omitted or duplicated: $process_symbol_contract" >&2
        return 1
      }
    done
    base_sdk_actual_sha="$(tree_manifest_sha256 "$base_sdk")"
  else
    echo "SDX AOT base artifact is unavailable or unsafe: $base_sdk" >&2
    return 1
  fi
  [[ "$base_sdk_actual_sha" == "${SDX_AOT_RECEIPT_VALUES[base_sdk_sha256]}" ]] || {
    echo "SDX AOT base artifact changed after receipt creation" >&2
    return 1
  }
  # Raw GGUF conversion is owned by a provider-independent nd4j-native closure.
  # The Tensor G3 AAR is verified separately below and must never substitute for
  # the CPU importer merely because both artifacts contain Android JNI members.
  for importer_library in libjnind4jcpu.so libnd4jcpu.so libopenblas.so libomp.so; do
    [[ -s "$SDX_LLM_JNI_DIR/$importer_library" ]] || {
      echo "SDX AOT SDK omitted required CPU importer library: $importer_library" >&2
      return 1
    }
    importer_hash="$(sha256_file "$SDX_LLM_JNI_DIR/$importer_library")"
    importer_entry="$importer_hash $importer_library"
    [[ "$(grep -Fxc "$importer_entry" "$base_native_bytes")" -eq 1 ]] || {
      echo "SDX AOT base closure does not bind CPU importer library: $importer_library" >&2
      return 1
    }
  done
  for provider_library in libnd4jnnapi.so libnd4jvulkan.so liblitert-lm.so; do
    if grep -Eq "^[0-9a-f]{64} $provider_library$" "$base_native_bytes"; then
      echo "SDX AOT CPU importer closure contains accelerator provider: $provider_library" >&2
      return 1
    fi
  done
  svm_support="$(realpath -e -- "${SDX_AOT_RECEIPT_VALUES[svm_support]}")" || return 1
  [[ -d "$svm_support" && ! -L "$svm_support" &&
     -f "$svm_support/libjvm.a" && ! -L "$svm_support/libjvm.a" &&
     -f "$svm_support/liblibchelper.a" && ! -L "$svm_support/liblibchelper.a" &&
     "$(sha256_file "$svm_support/libjvm.a")" == "${SDX_AOT_RECEIPT_VALUES[libjvm_sha256]}" &&
     "$(sha256_file "$svm_support/liblibchelper.a")" == "${SDX_AOT_RECEIPT_VALUES[liblibchelper_sha256]}" ]] || {
    echo "SDX AOT SVM support closure changed after receipt creation" >&2
    return 1
  }
  for required_file in \
    "$native_manifest" \
    "$native_bytes" \
    "$base_native_bytes" \
    "$classpath_manifest" \
    "$javacpp_reachability_manifest" \
    "$fresh_class_builds" \
    "$jdk_support_receipt" \
    "$optimization_metadata" \
    "$generated_javacpp" \
    "$generated_sdx" \
    "$staged_javacpp_lifecycle_bridge"; do
    [[ -s "$required_file" ]] || {
      echo "SDX AOT SDK receipt dependency is missing: $required_file" >&2
      return 1
    }
  done
  if (( REUSE_RECEIPTED_PRODUCERS == 0 )); then
    for required_file in \
      "$expected_build_script" \
      "$expected_javacpp_reachability_generator" \
      "$expected_javacpp_lifecycle_bridge" \
      "$expected_object_builder" \
      "$expected_linker_script"; do
      [[ -s "$required_file" ]] || {
        echo "SDX AOT SDK source dependency is missing: $required_file" >&2
        return 1
      }
    done
  fi

  if (( REUSE_RECEIPTED_PRODUCERS == 0 )); then
    expected_source="$(dl4j_aot_source_manifest_sha256)"
  fi
  expected_maven="$(command -v "$MAVEN")" || return 1
  expected_maven="$(realpath -e -- "$expected_maven")" || return 1
  maven_version="$({ env -u JAVA_TOOL_OPTIONS JAVA_HOME="$JAVA_HOME_ARG" PATH="$JAVA_HOME_ARG/bin:$PATH" "$expected_maven" --version; } 2>&1)"
  java_version="$({ env -u JAVA_TOOL_OPTIONS "$JAVA_HOME_ARG/bin/java" -version; } 2>&1)"
  require_receipt_value "SDX AOT SDK" format "${SDX_AOT_RECEIPT_VALUES[format]}" "8" || return 1
  require_receipt_value "SDX AOT SDK" stage "${SDX_AOT_RECEIPT_VALUES[stage]}" "android-aot-sdk" || return 1
  if (( REUSE_RECEIPTED_PRODUCERS == 0 )); then
    require_receipt_value "SDX AOT SDK" source_manifest_sha256 "${SDX_AOT_RECEIPT_VALUES[source_manifest_sha256]}" "$expected_source" || return 1
  fi
  require_receipt_value "SDX AOT SDK" fresh_class_builds_sha256 "${SDX_AOT_RECEIPT_VALUES[fresh_class_builds_sha256]}" "$(sha256_file "$fresh_class_builds")" || return 1
  require_receipt_value "SDX AOT SDK" javacpp_reachability_manifest_sha256 "${SDX_AOT_RECEIPT_VALUES[javacpp_reachability_manifest_sha256]}" "$(sha256_file "$javacpp_reachability_manifest")" || return 1
  require_receipt_value "SDX AOT SDK staged" javacpp_lifecycle_source_sha256 "${SDX_AOT_RECEIPT_VALUES[javacpp_lifecycle_source_sha256]}" "$(sha256_file "$staged_javacpp_lifecycle_bridge")" || return 1
  require_receipt_value "SDX AOT SDK" jdk_support_receipt_sha256 "${SDX_AOT_RECEIPT_VALUES[jdk_support_receipt_sha256]}" "$(sha256_file "$jdk_support_receipt")" || return 1
  if (( REUSE_RECEIPTED_PRODUCERS == 0 )); then
    require_receipt_value "SDX AOT SDK" javacpp_reachability_generator "${SDX_AOT_RECEIPT_VALUES[javacpp_reachability_generator]}" "$(realpath -e -- "$expected_javacpp_reachability_generator")" || return 1
    require_receipt_value "SDX AOT SDK" javacpp_reachability_generator_sha256 "${SDX_AOT_RECEIPT_VALUES[javacpp_reachability_generator_sha256]}" "$(sha256_file "$expected_javacpp_reachability_generator")" || return 1
    require_receipt_value "SDX AOT SDK" javacpp_lifecycle_source_sha256 "${SDX_AOT_RECEIPT_VALUES[javacpp_lifecycle_source_sha256]}" "$(sha256_file "$expected_javacpp_lifecycle_bridge")" || return 1
    require_receipt_value "SDX AOT SDK" build_script "${SDX_AOT_RECEIPT_VALUES[build_script]}" "$(realpath -e -- "$expected_build_script")" || return 1
    require_receipt_value "SDX AOT SDK" build_script_sha256 "${SDX_AOT_RECEIPT_VALUES[build_script_sha256]}" "$(sha256_file "$expected_build_script")" || return 1
    require_receipt_value "SDX AOT SDK" object_builder "${SDX_AOT_RECEIPT_VALUES[object_builder]}" "$(realpath -e -- "$expected_object_builder")" || return 1
    require_receipt_value "SDX AOT SDK" object_builder_sha256 "${SDX_AOT_RECEIPT_VALUES[object_builder_sha256]}" "$(sha256_file "$expected_object_builder")" || return 1
    require_receipt_value "SDX AOT SDK" linker_script_sha256 "${SDX_AOT_RECEIPT_VALUES[linker_script_sha256]}" "$(sha256_file "$expected_linker_script")" || return 1
  fi
  require_receipt_value "SDX AOT SDK" maven "${SDX_AOT_RECEIPT_VALUES[maven]}" "$expected_maven" || return 1
  require_receipt_value "SDX AOT SDK" maven_sha256 "${SDX_AOT_RECEIPT_VALUES[maven_sha256]}" "$(sha256_file "$expected_maven")" || return 1
  require_receipt_value "SDX AOT SDK" maven_version_sha256 "${SDX_AOT_RECEIPT_VALUES[maven_version_sha256]}" "$(printf '%s\n' "$maven_version" | sha256sum | cut -d ' ' -f 1)" || return 1
  require_receipt_value "SDX AOT SDK" java_home "${SDX_AOT_RECEIPT_VALUES[java_home]}" "$(realpath -e -- "$JAVA_HOME_ARG")" || return 1
  require_receipt_value "SDX AOT SDK" java_version_sha256 "${SDX_AOT_RECEIPT_VALUES[java_version_sha256]}" "$(printf '%s\n' "$java_version" | sha256sum | cut -d ' ' -f 1)" || return 1
  require_receipt_value "SDX AOT SDK" javacpp_jar "${SDX_AOT_RECEIPT_VALUES[javacpp_jar]}" "$(realpath -e -- "$JAVACPP_JAR")" || return 1
  require_receipt_value "SDX AOT SDK" javacpp_jar_sha256 "${SDX_AOT_RECEIPT_VALUES[javacpp_jar_sha256]}" "$(sha256_file "$JAVACPP_JAR")" || return 1
  require_receipt_value "SDX AOT SDK" ndk_revision_sha256 "${SDX_AOT_RECEIPT_VALUES[ndk_revision_sha256]}" "$(sha256_file "$ANDROID_NDK_ARG/source.properties")" || return 1
  require_receipt_value "SDX AOT SDK" android_api "${SDX_AOT_RECEIPT_VALUES[android_api]}" "28" || return 1
  require_receipt_value "SDX AOT SDK" android_abi "${SDX_AOT_RECEIPT_VALUES[android_abi]}" "arm64-v8a" || return 1

  while IFS=' ' read -r artifact source_sha tree_sha extra; do
    [[ "$artifact" =~ ^[A-Za-z0-9._-]+$ &&
       "$source_sha" =~ ^[0-9a-f]{64}$ &&
       "$tree_sha" =~ ^[0-9a-f]{64}$ &&
       -z "$extra" ]] || {
      echo "Malformed fresh class build entry in SDX AOT SDK: $artifact $source_sha $tree_sha $extra" >&2
      return 1
    }
    [[ -v 'expected_module_roots[$artifact]' &&
       ! -v 'seen_fresh_class_builds[$artifact]' ]] || {
      echo "Unexpected or duplicate fresh class build artifact: $artifact" >&2
      return 1
    }
    if (( REUSE_RECEIPTED_PRODUCERS == 0 )); then
      [[ "$source_sha" == "$(dl4j_aot_module_source_manifest_sha256 "${expected_module_roots[$artifact]}")" ]] || {
        echo "SDX AOT SDK fresh classes were compiled from different $artifact source" >&2
        return 1
      }
    fi
    seen_fresh_class_builds["$artifact"]=1
    fresh_class_hashes["$artifact"]="$tree_sha"
  done <"$fresh_class_builds"
  for artifact in "${!expected_module_roots[@]}"; do
    [[ -v 'seen_fresh_class_builds[$artifact]' ]] || {
      echo "SDX AOT SDK omitted a fresh class build for $artifact" >&2
      return 1
    }
  done
  [[ "${SDX_AOT_RECEIPT_VALUES[classes_sha256]}" == "${fresh_class_hashes[sdx-aot]}" &&
     "${SDX_AOT_RECEIPT_VALUES[model_classes_sha256]}" == "${fresh_class_hashes[nd4j-sdx-model]}" ]] || {
    echo "SDX AOT SDK receipt class hashes do not match its fresh compilation manifest" >&2
    return 1
  }

  for reachability_contract in \
    "format=2" \
    "platform=android-arm64" \
    "root=org.eclipse.deeplearning4j.tokenizers.bindings.TokenizersNative" \
    "root=org.nd4j.dsp.model.SdxLlmNative" \
    "root=org.nd4j.linalg.cpu.nativecpu.bindings.Nd4jCpu" \
    'reflection-class=org.nd4j.linalg.cpu.nativecpu.bindings.Nd4jCpu$Environment' \
    'jni-class=org.nd4j.linalg.cpu.nativecpu.bindings.Nd4jCpu$Environment' \
    "reflection-class=org.bytedeco.openblas.global.openblas_nolapack" \
    "jni-class=org.bytedeco.openblas.global.openblas_nolapack"; do
    [[ "$(grep -Fxc "$reachability_contract" "$javacpp_reachability_manifest")" -eq 1 ]] || {
      echo "SDX AOT JavaCPP reachability manifest omitted required contract: $reachability_contract" >&2
      return 1
    }
  done

  # Graal retains JavaCPP native-method names as inert .svm_heap metadata even
  # when their Java entry points have been substituted. The image-wide,
  # fail-closed substitution is the actual safety contract: it protects every
  # present and future backend path before host-JVM JNI marshalling can occur.
  # Exact fresh-source hashes above bind this marker to the checked-in
  # substitutions for Loader and Loader.Helper.
  local java_cpp_symbol_guard=
  java_cpp_symbol_guard="Embedded native images must resolve process symbols through NativeOps, not Loader.addressof: "
  if ! LC_ALL=C grep -aFq "$java_cpp_symbol_guard" "$SDX_LLM_JNI_DIR/libsdx_llm.so"; then
    echo "SDX AOT library omitted the image-wide JavaCPP Loader.addressof guard" >&2
    return 1
  fi
  [[ "${SDX_AOT_RECEIPT_VALUES[libsdx_sha256]}" == "$(sha256_file "$SDX_LLM_JNI_DIR/libsdx_llm.so")" &&
     "${SDX_AOT_RECEIPT_VALUES[libjnisdx_sha256]}" == "$(sha256_file "$SDX_LLM_JNI_DIR/libjnisdx_llm.so")" &&
     "${SDX_AOT_RECEIPT_VALUES[libjnijavacpp_sha256]}" == "$(sha256_file "$SDX_LLM_JNI_DIR/libjnijavacpp.so")" &&
     "${SDX_AOT_RECEIPT_VALUES[classpath_manifest_sha256]}" == "$(sha256_file "$classpath_manifest")" &&
     "${SDX_AOT_RECEIPT_VALUES[base_sdk_native_sha256]}" == "$(sha256_file "$base_native_bytes")" &&
     "${SDX_AOT_RECEIPT_VALUES[jnijavacpp_source_sha256]}" == "$(sha256_file "$generated_javacpp")" &&
     "${SDX_AOT_RECEIPT_VALUES[javacpp_lifecycle_source_sha256]}" == "$(sha256_file "$staged_javacpp_lifecycle_bridge")" &&
     "${SDX_AOT_RECEIPT_VALUES[jnisdx_source_sha256]}" == "$(sha256_file "$generated_sdx")" &&
     "${SDX_AOT_RECEIPT_VALUES[native_manifest_sha256]}" == "$(sha256_file "$native_manifest")" &&
     "${SDX_AOT_RECEIPT_VALUES[sdk_native_bytes_sha256]}" == "$(sha256_file "$native_bytes")" ]] || {
    echo "SDX AOT SDK bytes changed after receipt creation" >&2
    return 1
  }
  actual_native_bytes="$(
    while IFS= read -r library_name; do
      [[ "$library_name" =~ ^lib[A-Za-z0-9._+-]+[.]so$ ]] || return 1
      [[ -s "$SDX_LLM_JNI_DIR/$library_name" ]] || return 1
      printf '%s %s\n' "$(sha256_file "$SDX_LLM_JNI_DIR/$library_name")" "$library_name"
    done <"$native_manifest"
  )" || {
    echo "SDX AOT SDK native manifest is invalid or incomplete" >&2
    return 1
  }
  [[ "$actual_native_bytes" == "$(<"$native_bytes")" &&
     "${SDX_AOT_RECEIPT_VALUES[native_library_count]}" == "$(wc -l <"$native_manifest")" ]] || {
    echo "SDX AOT SDK native closure does not match its byte manifest" >&2
    return 1
  }
  declared_native_set="$(LC_ALL=C sort -u "$native_manifest")"
  actual_native_set="$(find "$SDX_LLM_JNI_DIR" -maxdepth 1 -type f -name '*.so' -printf '%f\n' | LC_ALL=C sort -u)"
  [[ "$declared_native_set" == "$actual_native_set" ]] || {
    echo "SDX AOT SDK native directory contains undeclared or missing libraries" >&2
    return 1
  }

  expected_inputs="$(
    printf '%s\n' \
      "source_manifest_sha256=${SDX_AOT_RECEIPT_VALUES[source_manifest_sha256]}" \
      "classes_sha256=${SDX_AOT_RECEIPT_VALUES[classes_sha256]}" \
      "model_classes_sha256=${SDX_AOT_RECEIPT_VALUES[model_classes_sha256]}" \
      "fresh_class_builds_sha256=${SDX_AOT_RECEIPT_VALUES[fresh_class_builds_sha256]}" \
      "classpath_manifest_sha256=${SDX_AOT_RECEIPT_VALUES[classpath_manifest_sha256]}" \
      "runtime_dependency_manifest_sha256=${SDX_AOT_RECEIPT_VALUES[runtime_dependency_manifest_sha256]}" \
      "maven_dependency_arguments_sha256=${SDX_AOT_RECEIPT_VALUES[maven_dependency_arguments_sha256]}" \
      "object_stage_inputs_sha256=${SDX_AOT_RECEIPT_VALUES[object_stage_inputs_sha256]}" \
      "javacpp_reachability_generator_sha256=${SDX_AOT_RECEIPT_VALUES[javacpp_reachability_generator_sha256]}" \
      "javacpp_reachability_config_sha256=${SDX_AOT_RECEIPT_VALUES[javacpp_reachability_config_sha256]}" \
      "javacpp_jni_config_sha256=${SDX_AOT_RECEIPT_VALUES[javacpp_jni_config_sha256]}" \
      "javacpp_initialization_config_sha256=${SDX_AOT_RECEIPT_VALUES[javacpp_initialization_config_sha256]}" \
      "javacpp_reachability_manifest_sha256=${SDX_AOT_RECEIPT_VALUES[javacpp_reachability_manifest_sha256]}" \
      "native_image_build_mode=${SDX_AOT_RECEIPT_VALUES[native_image_build_mode]}" \
      "native_image_optimization=${SDX_AOT_RECEIPT_VALUES[native_image_optimization]}" \
      "native_image_optimization_config_sha256=${SDX_AOT_RECEIPT_VALUES[native_image_optimization_config_sha256]}" \
      "object_sha256=${SDX_AOT_RECEIPT_VALUES[object_sha256]}" \
      "base_sdk_sha256=${SDX_AOT_RECEIPT_VALUES[base_sdk_sha256]}" \
      "base_sdk_receipt_sha256=${SDX_AOT_RECEIPT_VALUES[base_sdk_receipt_sha256]}" \
      "base_sdk_native_sha256=${SDX_AOT_RECEIPT_VALUES[base_sdk_native_sha256]}" \
      "process_blas_symbols_capability=${SDX_AOT_RECEIPT_VALUES[process_blas_symbols_capability]}" \
      "build_script_sha256=${SDX_AOT_RECEIPT_VALUES[build_script_sha256]}" \
      "native_image_cache_helper_sha256=${SDX_AOT_RECEIPT_VALUES[native_image_cache_helper_sha256]}" \
      "object_builder_sha256=${SDX_AOT_RECEIPT_VALUES[object_builder_sha256]}" \
      "jdk_support_receipt_sha256=${SDX_AOT_RECEIPT_VALUES[jdk_support_receipt_sha256]}" \
      "libjvm_sha256=${SDX_AOT_RECEIPT_VALUES[libjvm_sha256]}" \
      "liblibchelper_sha256=${SDX_AOT_RECEIPT_VALUES[liblibchelper_sha256]}" \
      "maven_sha256=${SDX_AOT_RECEIPT_VALUES[maven_sha256]}" \
      "maven_version_sha256=${SDX_AOT_RECEIPT_VALUES[maven_version_sha256]}" \
      "java_version_sha256=${SDX_AOT_RECEIPT_VALUES[java_version_sha256]}" \
      "linker_script_sha256=${SDX_AOT_RECEIPT_VALUES[linker_script_sha256]}" \
      "javacpp_jar_sha256=${SDX_AOT_RECEIPT_VALUES[javacpp_jar_sha256]}" \
      "ndk_revision_sha256=${SDX_AOT_RECEIPT_VALUES[ndk_revision_sha256]}" \
      "graalvm_version_sha256=${SDX_AOT_RECEIPT_VALUES[graalvm_version_sha256]}" \
      "libsdx_sha256=${SDX_AOT_RECEIPT_VALUES[libsdx_sha256]}" \
      "libjnisdx_sha256=${SDX_AOT_RECEIPT_VALUES[libjnisdx_sha256]}" \
      "libjnijavacpp_sha256=${SDX_AOT_RECEIPT_VALUES[libjnijavacpp_sha256]}" \
      "jnijavacpp_source_sha256=${SDX_AOT_RECEIPT_VALUES[jnijavacpp_source_sha256]}" \
      "javacpp_lifecycle_source_sha256=${SDX_AOT_RECEIPT_VALUES[javacpp_lifecycle_source_sha256]}" \
      "jnisdx_source_sha256=${SDX_AOT_RECEIPT_VALUES[jnisdx_source_sha256]}" \
      "native_manifest_sha256=${SDX_AOT_RECEIPT_VALUES[native_manifest_sha256]}" \
      "sdk_native_bytes_sha256=${SDX_AOT_RECEIPT_VALUES[sdk_native_bytes_sha256]}" |
      sha256sum | cut -d ' ' -f 1
  )"
  [[ "$expected_inputs" == "${SDX_AOT_RECEIPT_VALUES[inputs_sha256]}" ]] || {
    echo "SDX AOT SDK receipt input digest is inconsistent" >&2
    return 1
  }
  if (( REUSE_RECEIPTED_PRODUCERS == 1 )); then
    echo "Verified immutable SDX AOT SDK from historical producer receipt: $receipt"
  fi
  SDX_AOT_RECEIPT="$(realpath -e -- "$receipt")"
  SDX_AOT_PROVENANCE_SHA256="$(sha256_file "$receipt")"
}

declare -A TENSOR_G3_RECEIPT_VALUES=()
load_tensor_g3_receipt() {
  local receipt="$1"
  local line key value
  TENSOR_G3_RECEIPT_VALUES=()
  [[ -s "$receipt" ]] || {
    echo "Tensor G3 full build receipt is missing: $receipt" >&2
    return 1
  }
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ "$line" == *=* ]] || {
      echo "Malformed Tensor G3 build receipt line: $line" >&2
      return 1
    }
    key="${line%%=*}"
    value="${line#*=}"
    [[ "$key" =~ ^[a-z][a-z0-9_]*$ && -n "$value" ]] || {
      echo "Invalid Tensor G3 build receipt field: $line" >&2
      return 1
    }
    case "$key" in
      format|stage|variant|artifact|sha256|inputs_sha256|source_manifest_sha256|profile_sha256|build_script_sha256|ndk_revision_sha256|android_api|android_abi|chip|helpers|required_accelerator_device|native_artifact|native_sha256|native_receipt_sha256|full_source_artifact|full_source_sha256|classes_sha256|fresh_java_builds|fresh_java_builds_sha256|maven|maven_sha256|maven_version_sha256|java_home|java_version_sha256|provider_member|provider_sha256|arm_compute_member|arm_compute_sha256|jni_bridge_member|jni_bridge_sha256) ;;
      *) echo "Unknown Tensor G3 build receipt field: $key" >&2; return 1 ;;
    esac
    [[ ! -v 'TENSOR_G3_RECEIPT_VALUES[$key]' ]] || {
      echo "Duplicate Tensor G3 build receipt field: $key" >&2
      return 1
    }
    TENSOR_G3_RECEIPT_VALUES["$key"]="$value"
  done <"$receipt"
}

verify_tensor_g3_full_receipt() {
  local receipt="$1"
  local aar_real actual_sha expected_inputs expected_source_manifest
  local profile script shared_build_env expected_build_script_sha256
  local ndk_revision native native_receipt full_source fresh_java_builds
  local expected_maven expected_java_home maven_version java_version
  local key id source_sha classes_dir classes_sha extra classes_real expected_classes
  declare -A expected_fresh_roots=(
    [tokenizers-native-preset]="nd4j/nd4j-tokenizers/tokenizers-native-preset"
    [tokenizers-native]="nd4j/nd4j-tokenizers/tokenizers-native"
    [nd4j-sdx-preset]="nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx-preset"
    [nd4j-sdx-model]="nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx-model"
    [nd4j-sdx]="nd4j/nd4j-backends/nd4j-backend-impls/nd4j-sdx"
  )
  declare -A seen_fresh_builds=()
  local -a required=(
    format stage variant artifact sha256 inputs_sha256 source_manifest_sha256
    profile_sha256 build_script_sha256 ndk_revision_sha256 android_api android_abi
    chip helpers required_accelerator_device native_artifact native_sha256
    native_receipt_sha256 full_source_artifact full_source_sha256 classes_sha256
    fresh_java_builds fresh_java_builds_sha256
    maven maven_sha256 maven_version_sha256 java_home java_version_sha256
    provider_member provider_sha256 arm_compute_member arm_compute_sha256
    jni_bridge_member jni_bridge_sha256
  )
  load_tensor_g3_receipt "$receipt" || return 1
  for key in "${required[@]}"; do
    [[ -v 'TENSOR_G3_RECEIPT_VALUES[$key]' ]] || {
      echo "Tensor G3 build receipt omitted required field: $key" >&2
      return 1
    }
  done

  aar_real="$(realpath -e -- "$TENSOR_G3_AAR")" || return 1
  actual_sha="$(sha256_file "$aar_real")"
  native="${TENSOR_G3_RECEIPT_VALUES[native_artifact]}"
  native_receipt="$native.build-receipt"
  full_source="${TENSOR_G3_RECEIPT_VALUES[full_source_artifact]}"
  fresh_java_builds="${TENSOR_G3_RECEIPT_VALUES[fresh_java_builds]}"
  if (( REUSE_RECEIPTED_PRODUCERS == 0 )); then
    profile="$DL4J_ROOT/libnd4j/tools/mobile/profiles/tensor-g3-nnapi.env"
    script="$DL4J_ROOT/libnd4j/tools/mobile/build-android-accelerator.sh"
    shared_build_env="$DL4J_ROOT/libnd4j/tools/mobile/android-build-env.sh"
    [[ -s "$script" && -s "$shared_build_env" ]] || {
      echo "Tensor G3 producer scripts are missing" >&2
      return 1
    }
    expected_build_script_sha256="$(
      {
        printf 'entrypoint=%s\n' "$(sha256_file "$script")"
        printf 'shared_env=%s\n' "$(sha256_file "$shared_build_env")"
      } | sha256sum | cut -d ' ' -f 1
    )"
    ndk_revision="$ANDROID_NDK_ARG/source.properties"
    expected_source_manifest="$(dl4j_source_manifest_sha256)"
    expected_maven="$(realpath -e -- "$(command -v "$MAVEN")")" || return 1
    expected_java_home="$(realpath -e -- "$JAVA_HOME_ARG")" || return 1
    maven_version="$({ env -u JAVA_TOOL_OPTIONS JAVA_HOME="$expected_java_home" PATH="$expected_java_home/bin:$PATH" "$expected_maven" --version; } 2>&1)"
    java_version="$({ env -u JAVA_TOOL_OPTIONS "$expected_java_home/bin/java" -version; } 2>&1)"
  fi

  require_receipt_value "Tensor G3 full build" format "${TENSOR_G3_RECEIPT_VALUES[format]}" "3" || return 1
  require_receipt_value "Tensor G3 full build" stage "${TENSOR_G3_RECEIPT_VALUES[stage]}" "full" || return 1
  require_receipt_value "Tensor G3 full build" variant "${TENSOR_G3_RECEIPT_VALUES[variant]}" "tensor-g3" || return 1
  require_receipt_value "Tensor G3 full build" artifact "${TENSOR_G3_RECEIPT_VALUES[artifact]}" "$aar_real" || return 1
  require_receipt_value "Tensor G3 full build" sha256 "${TENSOR_G3_RECEIPT_VALUES[sha256]}" "$actual_sha" || return 1
  if (( REUSE_RECEIPTED_PRODUCERS == 0 )); then
    require_receipt_value "Tensor G3 full build" source_manifest_sha256 "${TENSOR_G3_RECEIPT_VALUES[source_manifest_sha256]}" "$expected_source_manifest" || return 1
    require_receipt_value "Tensor G3 full build" profile_sha256 "${TENSOR_G3_RECEIPT_VALUES[profile_sha256]}" "$(sha256_file "$profile")" || return 1
    require_receipt_value "Tensor G3 full build" build_script_sha256 "${TENSOR_G3_RECEIPT_VALUES[build_script_sha256]}" "$expected_build_script_sha256" || return 1
    require_receipt_value "Tensor G3 full build" maven "${TENSOR_G3_RECEIPT_VALUES[maven]}" "$expected_maven" || return 1
    require_receipt_value "Tensor G3 full build" maven_sha256 "${TENSOR_G3_RECEIPT_VALUES[maven_sha256]}" "$(sha256_file "$expected_maven")" || return 1
    require_receipt_value "Tensor G3 full build" maven_version_sha256 "${TENSOR_G3_RECEIPT_VALUES[maven_version_sha256]}" "$(printf '%s\n' "$maven_version" | sha256sum | cut -d ' ' -f 1)" || return 1
    require_receipt_value "Tensor G3 full build" java_home "${TENSOR_G3_RECEIPT_VALUES[java_home]}" "$expected_java_home" || return 1
    require_receipt_value "Tensor G3 full build" java_version_sha256 "${TENSOR_G3_RECEIPT_VALUES[java_version_sha256]}" "$(printf '%s\n' "$java_version" | sha256sum | cut -d ' ' -f 1)" || return 1
    require_receipt_value "Tensor G3 full build" ndk_revision_sha256 "${TENSOR_G3_RECEIPT_VALUES[ndk_revision_sha256]}" "$(sha256_file "$ndk_revision")" || return 1
  fi
  require_receipt_value "Tensor G3 full build" android_api "${TENSOR_G3_RECEIPT_VALUES[android_api]}" "31" || return 1
  require_receipt_value "Tensor G3 full build" android_abi "${TENSOR_G3_RECEIPT_VALUES[android_abi]}" "arm64-v8a" || return 1
  require_receipt_value "Tensor G3 full build" chip "${TENSOR_G3_RECEIPT_VALUES[chip]}" "nnapi" || return 1
  require_receipt_value "Tensor G3 full build" helpers "${TENSOR_G3_RECEIPT_VALUES[helpers]}" "nnapi,armcompute" || return 1
  require_receipt_value "Tensor G3 full build" required_accelerator_device "${TENSOR_G3_RECEIPT_VALUES[required_accelerator_device]}" "google-edgetpu" || return 1
  [[ -s "$native" && -s "$native_receipt" && -s "$full_source" && -s "$fresh_java_builds" ]] || {
    echo "Tensor G3 full receipt references missing producer artifacts" >&2
    return 1
  }
  require_receipt_value "Tensor G3 producer chain" native_sha256 "${TENSOR_G3_RECEIPT_VALUES[native_sha256]}" "$(sha256_file "$native")" || return 1
  require_receipt_value "Tensor G3 producer chain" native_receipt_sha256 "${TENSOR_G3_RECEIPT_VALUES[native_receipt_sha256]}" "$(sha256_file "$native_receipt")" || return 1
  require_receipt_value "Tensor G3 producer chain" full_source_sha256 "${TENSOR_G3_RECEIPT_VALUES[full_source_sha256]}" "$(sha256_file "$full_source")" || return 1
  require_receipt_value "Tensor G3 producer chain" fresh_java_builds_sha256 "${TENSOR_G3_RECEIPT_VALUES[fresh_java_builds_sha256]}" "$(sha256_file "$fresh_java_builds")" || return 1
  require_receipt_value "Tensor G3 producer chain" classes_sha256 "${TENSOR_G3_RECEIPT_VALUES[classes_sha256]}" "$(archive_member_sha256 "$aar_real" "classes.jar")" || return 1
  require_receipt_value "Tensor G3 producer chain" provider_sha256 "${TENSOR_G3_RECEIPT_VALUES[provider_sha256]}" "$(archive_member_sha256 "$aar_real" "${TENSOR_G3_RECEIPT_VALUES[provider_member]}")" || return 1
  require_receipt_value "Tensor G3 producer chain" arm_compute_sha256 "${TENSOR_G3_RECEIPT_VALUES[arm_compute_sha256]}" "$(archive_member_sha256 "$aar_real" "${TENSOR_G3_RECEIPT_VALUES[arm_compute_member]}")" || return 1
  require_receipt_value "Tensor G3 producer chain" jni_bridge_sha256 "${TENSOR_G3_RECEIPT_VALUES[jni_bridge_sha256]}" "$(archive_member_sha256 "$aar_real" "${TENSOR_G3_RECEIPT_VALUES[jni_bridge_member]}")" || return 1
  while read -r id source_sha classes_dir classes_sha extra; do
    [[ -n "$id" && -n "$source_sha" && -n "$classes_dir" && -n "$classes_sha" && -z "${extra:-}" &&
       -v 'expected_fresh_roots[$id]' && ! -v 'seen_fresh_builds[$id]' ]] || {
      echo "Unexpected or duplicate Tensor G3 fresh Java build entry: $id" >&2
      return 1
    }
    [[ "$source_sha" =~ ^[0-9a-f]{64}$ && "$classes_sha" =~ ^[0-9a-f]{64}$ ]] || {
      echo "Tensor G3 fresh Java build entry has an invalid digest: $id" >&2
      return 1
    }
    if (( REUSE_RECEIPTED_PRODUCERS == 0 )); then
      [[ "$source_sha" == "$(module_source_manifest_sha256 "${expected_fresh_roots[$id]}")" ]] || {
        echo "Tensor G3 $id classes were compiled from different source" >&2
        return 1
      }
      classes_real="$(realpath -e -- "$classes_dir")" || return 1
      expected_classes="$(realpath -e -- "$DL4J_ROOT/${expected_fresh_roots[$id]}/target/classes")" || return 1
      [[ "$classes_dir" == "$classes_real" && "$classes_real" == "$expected_classes" &&
         "$classes_sha" == "$(tree_manifest_sha256 "$classes_real")" ]] || {
        echo "Tensor G3 fresh Java output changed or came from an unexpected target: $id" >&2
        return 1
      }
    fi
    seen_fresh_builds["$id"]=1
  done <"$fresh_java_builds"
  for id in "${!expected_fresh_roots[@]}"; do
    [[ -v 'seen_fresh_builds[$id]' ]] || {
      echo "Tensor G3 fresh Java build manifest omitted $id" >&2
      return 1
    }
  done
  expected_inputs="$(
    printf '%s\n' \
      "native_receipt_sha256=${TENSOR_G3_RECEIPT_VALUES[native_receipt_sha256]}" \
      "native_sha256=${TENSOR_G3_RECEIPT_VALUES[native_sha256]}" \
      "source_manifest_sha256=${TENSOR_G3_RECEIPT_VALUES[source_manifest_sha256]}" \
      "full_source_sha256=${TENSOR_G3_RECEIPT_VALUES[full_source_sha256]}" \
      "classes_sha256=${TENSOR_G3_RECEIPT_VALUES[classes_sha256]}" \
      "fresh_java_builds_sha256=${TENSOR_G3_RECEIPT_VALUES[fresh_java_builds_sha256]}" \
      "maven_sha256=${TENSOR_G3_RECEIPT_VALUES[maven_sha256]}" \
      "maven_version_sha256=${TENSOR_G3_RECEIPT_VALUES[maven_version_sha256]}" \
      "java_version_sha256=${TENSOR_G3_RECEIPT_VALUES[java_version_sha256]}" \
      "provider_sha256=${TENSOR_G3_RECEIPT_VALUES[provider_sha256]}" \
      "arm_compute_sha256=${TENSOR_G3_RECEIPT_VALUES[arm_compute_sha256]}" \
      "jni_bridge_sha256=${TENSOR_G3_RECEIPT_VALUES[jni_bridge_sha256]}" |
      sha256sum | cut -d ' ' -f 1
  )"
  [[ "$expected_inputs" == "${TENSOR_G3_RECEIPT_VALUES[inputs_sha256]}" ]] || {
    echo "Tensor G3 full receipt input digest is inconsistent" >&2
    return 1
  }
  if (( REUSE_RECEIPTED_PRODUCERS == 1 )); then
    echo "Verified immutable Tensor G3 artifact chain from historical producer receipt: $receipt"
  fi
  TENSOR_G3_SOURCE_SHA256="$actual_sha"
  TENSOR_G3_PROVENANCE_SHA256="$(sha256_file "$receipt")"
  TENSOR_G3_FULL_RECEIPT="$(realpath -e -- "$receipt")"
}

resolve_tensor_g3_aar() {
  if [[ "$VARIANT" != "all" && "$VARIANT" != "tensor-g3" ]]; then
    return 0
  fi
  if [[ "$RELEASE_CONSUMER" == "1" ]]; then
    TENSOR_G3_AAR_SOURCE="release-manifest"
  elif [[ -n "$TENSOR_G3_AAR" ]]; then
    :
  elif [[ -s "$TENSOR_G3_CANONICAL_AAR" ]]; then
    TENSOR_G3_AAR="$TENSOR_G3_CANONICAL_AAR"
    TENSOR_G3_AAR_SOURCE="canonical-published"
  else
    TENSOR_G3_AAR="$TENSOR_G3_CANONICAL_AAR"
    TENSOR_G3_AAR_SOURCE="missing"
  fi
  if [[ "$RELEASE_CONSUMER" == "1" ]]; then
    TENSOR_G3_SOURCE_SHA256="$(sha256_file "$TENSOR_G3_AAR")"
    TENSOR_G3_PROVENANCE_SHA256="$(sha256_file "$SDX_RELEASE_MANIFEST")"
    TENSOR_G3_FULL_RECEIPT="$(realpath -e -- "$SDX_RELEASE_MANIFEST")"
  else
    verify_tensor_g3_full_receipt "$TENSOR_G3_AAR.build-receipt" || {
      echo "Rebuild the full Tensor G3 AAR; native-only or unreceipted AARs are rejected." >&2
      return 1
    }
  fi
  echo "Tensor G3 AAR input: source=$TENSOR_G3_AAR_SOURCE path=$TENSOR_G3_AAR"
  echo "Tensor G3 source SHA-256: $TENSOR_G3_SOURCE_SHA256"
  echo "Tensor G3 provenance SHA-256: $TENSOR_G3_PROVENANCE_SHA256"
}

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

paths_overlap() {
  local first_real second_real
  first_real="$(realpath -m -- "$1")" || return 1
  second_real="$(realpath -m -- "$2")" || return 1
  [[ "$first_real" == "$second_real" ||
     "$first_real" == "$second_real/"* ||
     "$second_real" == "$first_real/"* ]]
}

prepare_work_root() {
  [[ -n "$WORK_ROOT" ]] || return 0
  remove_owned_build_directory "$WORK_ROOT/apk-stage" "$WORK_ROOT" "work-root APK staging" || return 1
  remove_owned_build_directory "$WORK_ROOT/apk-jni" "$WORK_ROOT" "work-root JNI staging" || return 1
  mkdir -p "$APK_STAGING_ROOT"
}

validate_build_layout() {
  local output_real jni_source_root candidate candidate_real
  output_real="$(realpath -m -- "$OUTPUT_DIR")" || return 1
  jni_source_root="$(dirname -- "$JNI_OUTPUT_DIR")"
  [[ "$(basename -- "$JNI_OUTPUT_DIR")" == "arm64-v8a" ]] || {
    echo "JNI output must be the arm64-v8a ABI leaf, not its parent: $JNI_OUTPUT_DIR" >&2
    return 1
  }
  if paths_overlap "$APP_BUILD_ROOT" "$jni_source_root"; then
    echo "Gradle build output and JNI source root must be disjoint: app=$APP_BUILD_ROOT jni=$jni_source_root" >&2
    return 1
  fi
  if paths_overlap "$OUTPUT_DIR" "$APP_BUILD_ROOT" || paths_overlap "$OUTPUT_DIR" "$jni_source_root"; then
    echo "Final APK output must be disjoint from disposable Gradle/JNI staging: $OUTPUT_DIR" >&2
    return 1
  fi
  if [[ "$RAM_GRADLE_BUILD" == "1" ]] && paths_overlap "$OUTPUT_DIR" "$APK_STAGING_ROOT"; then
    echo "Final APK output must be disjoint from the RAM staging root: $OUTPUT_DIR" >&2
    return 1
  fi
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
  local jni_output_real direct_jni_real maven_jni_real work_jni_real temporary_file temporary_directory

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
  if [[ -n "$WORK_ROOT" ]]; then
    work_jni_real="$(realpath -m -- "$WORK_ROOT/apk-jni/arm64-v8a")" || cleanup_status=1
  else
    work_jni_real=""
  fi
  if [[ "$jni_output_real" == "$direct_jni_real" ]]; then
    remove_owned_build_directory "$JNI_OUTPUT_DIR" "$SCRIPT_DIR/app/src/main/jniLibs" "direct-build JNI staging" || cleanup_status=1
  elif [[ "$jni_output_real" == "$maven_jni_real" ]]; then
    remove_owned_build_directory "$maven_target_root/android-jni" "$maven_target_root" "Maven JNI staging" || cleanup_status=1
  elif [[ -n "$work_jni_real" && "$jni_output_real" == "$work_jni_real" ]]; then
    remove_owned_build_directory "$WORK_ROOT/apk-jni" "$WORK_ROOT" "work-root JNI staging" || cleanup_status=1
  else
    echo "Retaining caller-owned JNI output outside canonical staging roots: $JNI_OUTPUT_DIR"
  fi
  if [[ -n "$WORK_ROOT" ]]; then
    remove_owned_build_directory "$WORK_ROOT/apk-stage" "$WORK_ROOT" "work-root APK staging" || cleanup_status=1
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
    --tensor-g3-aar) TENSOR_G3_AAR="${2:?missing value for --tensor-g3-aar}"; TENSOR_G3_AAR_SOURCE="command-line"; AAR_OVERRIDE=1; shift 2 ;;
    --tensor-g5-aar|--tensor-aar) TENSOR_G5_AAR="${2:?missing value for $1}"; AAR_OVERRIDE=1; shift 2 ;;
    --graph-library) GRAPH_LIBRARY="${2:?missing value for --graph-library}"; shift 2 ;;
    --sdx-llm-sdk) SDX_LLM_SDK="${2:?missing value for --sdx-llm-sdk}"; SDX_LLM_SDK_SOURCE="command-line"; shift 2 ;;
    --graph-header) GRAPH_HEADER="${2:?missing value for --graph-header}"; shift 2 ;;
    --graph-verifier) GRAPH_VERIFIER="${2:?missing value for --graph-verifier}"; shift 2 ;;
    --javacpp-jar) JAVACPP_JAR="${2:?missing value for --javacpp-jar}"; JAVACPP_JAR_SOURCE="command-line"; shift 2 ;;
    --jni-output) JNI_OUTPUT_DIR="${2:?missing value for --jni-output}"; JNI_OUTPUT_EXPLICIT=1; shift 2 ;;
    --work-root) WORK_ROOT="${2:?missing value for --work-root}"; shift 2 ;;
    --android-sdk) ANDROID_SDK="${2:?missing value for --android-sdk}"; ANDROID_SDK_SOURCE="command-line"; shift 2 ;;
    --android-ndk) ANDROID_NDK_ARG="${2:?missing value for --android-ndk}"; ANDROID_NDK_SOURCE="command-line"; shift 2 ;;
    --java-home) JAVA_HOME_ARG="${2:?missing value for --java-home}"; JAVA_HOME_SOURCE="command-line"; shift 2 ;;
    --maven) MAVEN="${2:?missing value for --maven}"; MAVEN_SOURCE="command-line"; shift 2 ;;
    --skip-maven) SKIP_MAVEN=1; shift ;;
    --reuse-receipted-producers) REUSE_RECEIPTED_PRODUCERS=1; shift ;;
    --maven-artifacts) MAVEN_ARTIFACTS=1; SKIP_MAVEN=1; shift ;;
    --sdx-release-version) SDX_RELEASE_VERSION="${2:?missing value for --sdx-release-version}"; shift 2 ;;
    --sdx-release-manifest) SDX_RELEASE_MANIFEST="${2:?missing value for --sdx-release-manifest}"; shift 2 ;;
    --sdx-release-artifact-root) SDX_RELEASE_ARTIFACT_ROOT="${2:?missing value for --sdx-release-artifact-root}"; shift 2 ;;
    --output) OUTPUT_DIR="${2:?missing value for --output}"; OUTPUT_EXPLICIT=1; shift 2 ;;
    --build-id) APK_BUILD_ID="${2:?missing value for --build-id}"; shift 2 ;;
    --version-code) APK_VERSION_CODE="${2:?missing value for --version-code}"; APK_VERSION_CODE_OVERRIDE=1; shift 2 ;;
    --ram-gradle-build) RAM_GRADLE_BUILD=1; shift ;;
    --retain-staging) RETAIN_STAGING=1; shift ;;
    --cleanup-only) CLEANUP_ONLY=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 2 ;;
  esac
done

configure_work_root || exit 2

case "$RETAIN_STAGING" in
  0|1) ;;
  *) echo "KOMPILE_ANDROID_RETAIN_STAGING must be 0 or 1" >&2; exit 2 ;;
esac
case "$RAM_GRADLE_BUILD" in
  0|1) ;;
  *) echo "KOMPILE_ANDROID_RAM_GRADLE_BUILD must be 0 or 1" >&2; exit 2 ;;
esac
prepare_work_root || exit 2
if [[ "$CLEANUP_ONLY" != "1" ]]; then
  if [[ "$APK_VERSION_CODE_OVERRIDE" == "1" ]]; then
    [[ "${KOMPILE_ANDROID_ALLOW_MANUAL_VERSION_CODE:-0}" == "1" ]] || {
      echo "--version-code is reserved for historical reproduction; normal builds allocate it automatically" >&2
      echo "Set KOMPILE_ANDROID_ALLOW_MANUAL_VERSION_CODE=1 only for an intentional reproduction" >&2
      exit 2
    }
  else
    APK_VERSION_CODE="$("$SCRIPT_DIR/tools/allocate-apk-version-code.sh" --scan-root "$OUTPUT_DIR")"
  fi
  if [[ -z "$APK_BUILD_ID" ]]; then
    APK_BUILD_ID="v$APK_VERSION_CODE"
  fi
  [[ "$APK_BUILD_ID" =~ ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ ]] || {
    echo "APK build ID must contain only letters, digits, dot, underscore, and hyphen" >&2
    exit 2
  }
  [[ "$APK_VERSION_CODE" =~ ^[0-9]+$ ]] && (( APK_VERSION_CODE >= 1 && APK_VERSION_CODE <= 2100000000 )) || {
    echo "APK version code must be an integer from 1 through 2100000000" >&2
    exit 2
  }
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
validate_build_layout || exit 2
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

resolve_android_sdk
[[ -d "$ANDROID_SDK" ]] || {
  echo "Android SDK not found. Set ANDROID_HOME/ANDROID_SDK_ROOT or pass --android-sdk." >&2
  exit 1
}
autoconfigure_from_sdx_aot_receipt || {
  echo "Unable to derive the packaging toolchain from $SDX_LLM_SDK/metadata/build-receipt" >&2
  exit 1
}
[[ -d "$ANDROID_NDK_ARG" ]] || {
  echo "No installed Android NDK matches the SDX AOT receipt. Install that revision under $ANDROID_SDK/ndk or pass --android-ndk." >&2
  exit 1
}
resolve_java_home
validate_java_17
command -v "$MAVEN" >/dev/null 2>&1 || [[ -x "$MAVEN" ]] || {
  echo "Receipt-bound Maven executable not found: $MAVEN" >&2
  exit 1
}
[[ -s "$JAVACPP_JAR" ]] || {
  echo "Receipt-bound JavaCPP JAR not found: $JAVACPP_JAR" >&2
  exit 1
}
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
SDX_NATIVE_MANIFEST="$SDX_LLM_SDK/metadata/cmake-owned-native-libraries.txt"
[[ -s "$SDX_LLM_JNI_DIR/libsdx_llm.so" ]] || {
  echo "DL4J Android SDX LLM runtime not found: $SDX_LLM_JNI_DIR/libsdx_llm.so" >&2
  echo "Build it explicitly with nd4j/sdx-aot -Pandroid-aot -Dbackend.artifactId=<importer-backend>, or pass --sdx-llm-sdk." >&2
  exit 1
}
[[ -s "$SDX_LLM_JNI_DIR/libjnisdx_llm.so" ]] || {
  echo "DL4J Android SDX direct JNI transport not found: $SDX_LLM_JNI_DIR/libjnisdx_llm.so" >&2
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
resolve_tensor_g3_aar
verify_sdx_aot_sdk_receipt || {
  echo "Rebuild the SDX Android AOT SDK from the current classes before packaging any APK." >&2
  exit 1
}

echo "Android packaging layout: work_root=${WORK_ROOT:-none} app_build=$APP_BUILD_ROOT jni=$JNI_OUTPUT_DIR output=$OUTPUT_DIR"
echo "Android packaging SDK: source=$ANDROID_SDK_SOURCE path=$ANDROID_SDK"
echo "Android packaging NDK: source=$ANDROID_NDK_SOURCE path=$ANDROID_NDK_ARG"
echo "Android packaging JDK: source=$JAVA_HOME_SOURCE path=$JAVA_HOME_ARG"
echo "Android packaging Maven: source=$MAVEN_SOURCE path=$MAVEN"
echo "Android packaging JavaCPP: source=$JAVACPP_JAR_SOURCE path=$JAVACPP_JAR"
echo "Android packaging SDX AOT SDK: source=$SDX_LLM_SDK_SOURCE path=$SDX_LLM_SDK"
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
while IFS= read -r sdx_library_name; do
  [[ "$sdx_library_name" =~ ^lib[A-Za-z0-9._+-]+[.]so$ ]] || {
    echo "Unsafe SDX SDK native manifest member: $sdx_library_name" >&2
    exit 1
  }
  sdx_library="$SDX_LLM_JNI_DIR/$sdx_library_name"
  [[ -f "$sdx_library" && -s "$sdx_library" ]] || {
    echo "Declared SDX SDK library is missing or not a regular file: $sdx_library" >&2
    exit 1
  }
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
done <"$SDX_NATIVE_MANIFEST"
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
    --aar "$HEXAGON_AAR" \
    --variant hexagon \
    --native-library nd4jhexagon \
    --accelerator QUALCOMM_HEXAGON_HTP \
    --android-ndk "$ANDROID_NDK_ARG"
fi

if [[ "$VARIANT" == "all" || "$VARIANT" == "tensor-g3" ]]; then
  [[ -s "$TENSOR_G3_AAR" ]] || { echo "Tensor G3 AAR not found: $TENSOR_G3_AAR" >&2; exit 1; }
  "$DL4J_ROOT/libnd4j/tools/mobile/verify-android-accelerator-aar.sh" \
    --aar "$TENSOR_G3_AAR" \
    --variant tensor-g3 \
    --native-library nd4jnnapi \
    --accelerator NNAPI_ACCELERATOR_ONLY \
    --gpu-target AUTO \
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
  # The core module is small and supplies the APK's public chat wire classes. Recreate it from
  # source so Maven cannot republish stale IDE/ECJ error bytecode solely because target/classes is
  # newer than its sources. This does not clean the graph AOT or native accelerator artifacts.
  "$MAVEN" -o -f "$REPO_ROOT/kompile-chat-local/pom.xml" \
    -pl :kompile-chat-local-core -am clean install -DskipTests
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
  if [[ "$variant" == "tensorG3" &&
        "$(sha256_file "$runtime_aar")" != "$TENSOR_G3_SOURCE_SHA256" ]]; then
    echo "Tensor G3 runtime AAR was rewritten after its producer receipt was verified" >&2
    exit 1
  fi
  verifier_args+=(--runtime-aar "$runtime_aar")
  if [[ "$variant" == "tensorG3" ]]; then
    verifier_args+=(
      --expected-source-runtime-aar-sha256 "$TENSOR_G3_SOURCE_SHA256"
      --expected-runtime-provenance-sha256 "$TENSOR_G3_PROVENANCE_SHA256"
      --expected-sdx-aot-provenance-sha256 "$SDX_AOT_PROVENANCE_SHA256"
    )
  fi
  "$SCRIPT_DIR/tools/verify-offline-apk.sh" "${verifier_args[@]}"
}

verify_packaging_provenance_anchors() {
  [[ -n "$SDX_AOT_PROVENANCE_SHA256" &&
     -s "$SDX_AOT_RECEIPT" &&
     "$(sha256_file "$SDX_AOT_RECEIPT")" == "$SDX_AOT_PROVENANCE_SHA256" ]] || {
    echo "SDX AOT provenance receipt changed during APK packaging" >&2
    return 1
  }
  if [[ "$VARIANT" == tensor-g3 || "$VARIANT" == all ]]; then
    [[ -n "$TENSOR_G3_SOURCE_SHA256" &&
       -n "$TENSOR_G3_PROVENANCE_SHA256" &&
       -s "$TENSOR_G3_AAR" &&
       -s "$TENSOR_G3_FULL_RECEIPT" &&
       "$(sha256_file "$TENSOR_G3_AAR")" == "$TENSOR_G3_SOURCE_SHA256" &&
       "$(sha256_file "$TENSOR_G3_FULL_RECEIPT")" == "$TENSOR_G3_PROVENANCE_SHA256" ]] || {
      echo "Tensor G3 AAR or provenance receipt changed during APK packaging" >&2
      return 1
    }
  fi
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
  gradle_args+=(
    "-PsdxTensorG3Aar=$TENSOR_G3_AAR"
    "-PsdxTensorG3SourceSha256=$TENSOR_G3_SOURCE_SHA256"
    "-PsdxTensorG3ProvenanceSha256=$TENSOR_G3_PROVENANCE_SHA256"
    "-PsdxAotSdkProvenanceSha256=$SDX_AOT_PROVENANCE_SHA256"
  )
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
  gradle_tasks+=(":app:assembleTensorG3Debug" ":app:assembleTensorG3DebugAndroidTest")
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

# Source receipts are validated before Gradle reads any producer artifact.
# From this point forward the APK depends only on those immutable artifact bytes,
# not on unrelated files in the shared producer checkout.
verify_packaging_provenance_anchors || exit 1

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

publish_candidate() {
  local source_apk="$1"
  local stable_filename="$2"
  local variant="$3"
  local base="${stable_filename%.apk}"
  local stable_apk="$OUTPUT_DIR/$stable_filename"
  local ram_staging output_staging digest verified_digest published_digest final_digest
  local unique_apk unique_hash
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
  verify_packaging_provenance_anchors || return 1

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

  unique_hash="$(mktemp "$OUTPUT_DIR/.${base}.candidate-sha256.XXXXXX")"
  TEMPORARY_FILES+=("$unique_hash")
  printf '%s  %s\n' "$digest" "$unique_apk" >"$unique_hash"
  sync "$unique_hash"
  mv -f -- "$unique_hash" "$unique_apk.sha256"

  local package_name test_source test_candidate test_digest normalized_aar normalized_sha normalized_candidate normalized_tmp
  local source_aar source_aar_sha provenance provenance_sha aot_sdk aot_provenance aot_provenance_sha candidate_manifest manifest_tmp
  package_name="none"
  test_source="none"
  test_candidate="none"
  test_digest="none"
  source_aar="none"
  source_aar_sha="none"
  provenance="none"
  provenance_sha="none"
  aot_sdk="none"
  aot_provenance="none"
  aot_provenance_sha="none"
  case "$variant" in
    vulkan) package_name="ai.kompile.chat.local.android.vulkan.debug" ;;
    hexagon) package_name="ai.kompile.chat.local.android.hexagon.debug" ;;
    tensorG3)
      package_name="ai.kompile.chat.local.android.tensorg3.debug"
      test_source="$APP_BUILD_ROOT/outputs/apk/androidTest/tensorG3/debug/app-tensorG3-debug-androidTest.apk"
      [[ -s "$test_source" ]] || {
        echo "Tensor G3 qualification test APK is missing: $test_source" >&2
        return 1
      }
      test_digest="$(sha256_file "$test_source")"
      test_candidate="$OUTPUT_DIR/${base}-$APK_BUILD_ID-${test_digest:0:12}-androidTest.apk"
      dd if="$test_source" of="$test_candidate" bs=16M conv=fsync status=none
      [[ "$(sha256_file "$test_candidate")" == "$test_digest" ]] || {
        echo "Tensor G3 qualification test APK changed during candidate publication" >&2
        return 1
      }
      source_aar="$(realpath -e -- "$TENSOR_G3_AAR")"
      source_aar_sha="$TENSOR_G3_SOURCE_SHA256"
      provenance="$TENSOR_G3_FULL_RECEIPT"
      provenance_sha="$TENSOR_G3_PROVENANCE_SHA256"
      aot_sdk="$(realpath -e -- "$SDX_LLM_SDK")"
      aot_provenance="$SDX_AOT_RECEIPT"
      aot_provenance_sha="$SDX_AOT_PROVENANCE_SHA256"
      ;;
    tensorG5) package_name="ai.kompile.chat.local.android.tensorg5.debug" ;;
  esac
  case "$variant" in
    vulkan) normalized_aar="$APP_BUILD_ROOT/sdx-normalized-aar/vulkan/sdx-runtime-vulkan.aar" ;;
    hexagon) normalized_aar="$APP_BUILD_ROOT/sdx-normalized-aar/hexagon/sdx-runtime-hexagon.aar" ;;
    tensorG3) normalized_aar="$APP_BUILD_ROOT/sdx-normalized-aar/tensor-g3/sdx-runtime-tensor-g3.aar" ;;
    tensorG5) normalized_aar="$APP_BUILD_ROOT/sdx-normalized-aar/tensor-g5/sdx-runtime-tensor-g5.aar" ;;
  esac
  normalized_sha="$(sha256_file "$normalized_aar")"
  normalized_candidate="$OUTPUT_DIR/${base}-${APK_BUILD_ID}-${normalized_sha:0:12}-runtime.aar"
  normalized_tmp="$(mktemp "$OUTPUT_DIR/.${base}.normalized-runtime.XXXXXX")"
  TEMPORARY_FILES+=("$normalized_tmp")
  dd if="$normalized_aar" of="$normalized_tmp" bs=16M conv=fsync status=none
  [[ "$(sha256_file "$normalized_tmp")" == "$normalized_sha" ]] || {
    echo "Normalized runtime AAR changed during candidate publication" >&2
    return 1
  }
  mv -f -- "$normalized_tmp" "$normalized_candidate"
  sync "$normalized_candidate"
  printf '%s  %s\n' "$normalized_sha" "$normalized_candidate" >"$normalized_candidate.sha256"
  sync "$normalized_candidate.sha256"
  candidate_manifest="$unique_apk.candidate"
  manifest_tmp="$(mktemp "$OUTPUT_DIR/.${base}.candidate-manifest.XXXXXX")"
  TEMPORARY_FILES+=("$manifest_tmp")
  {
    printf 'format=3\n'
    printf 'variant=%s\n' "$variant"
    printf 'package=%s\n' "$package_name"
    printf 'build_id=%s\n' "$APK_BUILD_ID"
    printf 'version_code=%s\n' "$APK_VERSION_CODE"
    printf 'candidate_apk=%s\n' "$(realpath -e -- "$unique_apk")"
    printf 'candidate_sha256=%s\n' "$digest"
    printf 'stable_apk=%s\n' "$stable_apk"
    printf 'test_apk=%s\n' "$test_candidate"
    printf 'test_apk_sha256=%s\n' "$test_digest"
    printf 'source_runtime_aar=%s\n' "$source_aar"
    printf 'source_runtime_aar_sha256=%s\n' "$source_aar_sha"
    printf 'normalized_runtime_aar=%s\n' "$(realpath -e -- "$normalized_candidate")"
    printf 'normalized_runtime_aar_sha256=%s\n' "$normalized_sha"
    printf 'runtime_provenance=%s\n' "$provenance"
    printf 'runtime_provenance_sha256=%s\n' "$provenance_sha"
    printf 'sdx_aot_sdk=%s\n' "$aot_sdk"
    printf 'sdx_aot_provenance=%s\n' "$aot_provenance"
    printf 'sdx_aot_provenance_sha256=%s\n' "$aot_provenance_sha"
    printf 'host_verification=PASS\n'
  } >"$manifest_tmp"
  sync "$manifest_tmp"
  mv -f -- "$manifest_tmp" "$candidate_manifest"
  printf '%s  %s\n' "$(sha256_file "$candidate_manifest")" "$candidate_manifest" >"$candidate_manifest.sha256"
  sync "$candidate_manifest" "$candidate_manifest.sha256"
  "$SCRIPT_DIR/tools/prune-offline-apk-candidates.sh" \
    --output "$OUTPUT_DIR" \
    --base "$base" \
    --keep-manifest "$candidate_manifest"
  rm -f -- "$ram_staging"
  echo "Published host-verified candidate only; stable alias is unchanged: variant=$variant file=$unique_apk manifest=$candidate_manifest sha256=$digest"
}

if [[ "$VARIANT" == "all" || "$VARIANT" == "vulkan" ]]; then
  publish_candidate \
    "$APP_BUILD_ROOT/outputs/apk/vulkan/debug/app-vulkan-debug.apk" \
    "kompile-offline-graph-chat-vulkan.apk" \
    vulkan
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "hexagon" ]]; then
  publish_candidate \
    "$APP_BUILD_ROOT/outputs/apk/hexagon/debug/app-hexagon-debug.apk" \
    "kompile-offline-graph-chat-hexagon.apk" \
    hexagon
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "tensor-g3" ]]; then
  publish_candidate \
    "$APP_BUILD_ROOT/outputs/apk/tensorG3/debug/app-tensorG3-debug.apk" \
    "kompile-offline-graph-chat-tensor-g3-pixel-8a.apk" \
    tensorG3
fi
if [[ "$VARIANT" == "all" || "$VARIANT" == "tensor-g5" ]]; then
  publish_candidate \
    "$APP_BUILD_ROOT/outputs/apk/tensorG5/debug/app-tensorG5-debug.apk" \
    "kompile-offline-graph-chat-tensor-g5.apk" \
    tensorG5
fi

echo "Offline Android accelerator APKs: $OUTPUT_DIR"
