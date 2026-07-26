#!/usr/bin/env bash
set -euo pipefail
IFS=$'\n\t'

die() {
  printf 'package-offline-graph-chat: %s\n' "$*" >&2
  exit 1
}

usage() {
  printf '%s\n' \
    "Usage: $0 [--plan|--validate|--stage-only|--verify-stage|--package-existing-stage|--package] [options]" \
    "" \
    "The default action is --package (validated STAGE followed by PACKAGE)." \
    "PLAN is source-only and never copies artifacts or creates an archive." \
    "" \
    "Paths:" \
    "  --output ZIP  --stage-dir DIR  --kompile-root DIR  --dl4j-root DIR" \
    "  --examples-root DL4J_SDX_EXAMPLES_DIR  --android-sdk DIR  --android-ndk DIR" \
    "  --sdx-release-version VERSION  --sdx-release-manifest FILE" \
    "  --sdx-release-artifact-root DIR" \
    "Release-consumer inputs are resolved strictly from the pinned canonical release." \
    "Without all three release options, explicit/source-build artifact paths remain in effect." \
    "  --vulkan-apk FILE  --vulkan-apk-sha256 FILE  --vulkan-aar FILE" \
    "  --tensor-g3-apk FILE  --tensor-g3-apk-sha256 FILE  --tensor-g3-aar FILE" \
    "  --staging-exec-jar FILE  --vulkan-provider-manifest FILE" \
    "  --tensor-g3-provider-manifest FILE  --graph-sdk FILE" \
    "  --graph-library FILE  --graph-javacpp-library FILE  --graph-header FILE" \
    "  --canonical-sdz FILE  --canonical-sdz-readme FILE" \
    "" \
    "Required independent 64-hex release hashes for validation/staging:" \
    "  --expected-vulkan-sha256 HASH" \
    "  --expected-tensor-g3-sha256 HASH" \
    "  --expected-vulkan-aar-sha256 HASH" \
    "  --expected-vulkan-libjnisdx-sha256 HASH" \
    "  --expected-tensor-g3-aar-sha256 HASH" \
    "  --expected-staging-jar-sha256 HASH" \
    "" \
    "Source identity overrides:" \
    "  --kompile-source-id ID  --dl4j-source-id ID  --examples-source-id ID" \
    "If omitted for STAGE, each identity is git:<commit>[+dirty]."
}

script_dir="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)"
driver="$script_dir/cmake/FinalOfflineDistribution.cmake"
mode=package
reset_stage=ON

kompile_root="${KOMPILE_ROOT:-}"
dl4j_root="${DL4J_ROOT:-}"
examples_root="${EXAMPLES_ROOT:-}"
android_sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
android_ndk="${ANDROID_NDK_ROOT:-${ANDROID_NDK_HOME:-${ANDROID_NDK:-}}}"
output=""
stage=""

vulkan_apk="${VULKAN_APK:-}"
vulkan_apk_sha256="${VULKAN_APK_SHA256:-}"
expected_vulkan_sha256="${EXPECTED_VULKAN_SHA256:-}"
tensor_g3_apk="${TENSOR_G3_APK:-}"
tensor_g3_apk_sha256="${TENSOR_G3_APK_SHA256:-}"
expected_tensor_g3_sha256="${EXPECTED_TENSOR_G3_SHA256:-}"
vulkan_aar="${VULKAN_AAR:-}"
expected_vulkan_aar_sha256="${EXPECTED_VULKAN_AAR_SHA256:-}"
expected_vulkan_libjnisdx_sha256="${EXPECTED_VULKAN_LIBJNISDX_SHA256:-}"
tensor_g3_aar="${TENSOR_G3_AAR:-}"
expected_tensor_g3_aar_sha256="${EXPECTED_TENSOR_G3_AAR_SHA256:-}"
staging_exec_jar="${STAGING_EXEC_JAR:-}"
expected_staging_jar_sha256="${EXPECTED_STAGING_JAR_SHA256:-}"
vulkan_provider_manifest="${VULKAN_PROVIDER_MANIFEST:-}"
tensor_g3_provider_manifest="${TENSOR_G3_PROVIDER_MANIFEST:-}"
graph_sdk="${GRAPH_SDK:-}"
graph_library="${GRAPH_LIBRARY:-}"
graph_javacpp_library="${GRAPH_JAVACPP_LIBRARY:-}"
graph_header="${GRAPH_HEADER:-}"
canonical_sdz="${CANONICAL_SDZ:-}"
canonical_sdz_readme="${CANONICAL_SDZ_README:-}"
kompile_source_id="${KOMPILE_SOURCE_ID:-}"
dl4j_source_id="${DL4J_SOURCE_ID:-}"
examples_source_id="${EXAMPLES_SOURCE_ID:-}"
sdx_release_version="${SDX_RELEASE_VERSION:-}"
sdx_release_manifest="${SDX_RELEASE_MANIFEST:-}"
sdx_release_artifact_root="${SDX_RELEASE_ARTIFACT_ROOT:-}"

while (($#)); do
  case "$1" in
    --plan) mode=plan; shift ;;
    --validate) mode=validate; shift ;;
    --stage|--stage-only) mode=stage; shift ;;
    --verify-stage) mode=verify-stage; shift ;;
    --package-existing-stage) mode=package-existing-stage; shift ;;
    --package) mode=package; shift ;;
    --no-reset-stage) reset_stage=OFF; shift ;;
    -h|--help) usage; exit 0 ;;
    --*)
      (($# >= 2)) || die "missing value for $1"
      option=$1
      value=$2
      case "$option" in
        --output) destination=output ;;
        --stage-dir) destination=stage ;;
        --kompile-root) destination=kompile_root ;;
        --dl4j-root) destination=dl4j_root ;;
        --examples-root) destination=examples_root ;;
        --sdx-release-version) destination=sdx_release_version ;;
        --sdx-release-manifest) destination=sdx_release_manifest ;;
        --sdx-release-artifact-root) destination=sdx_release_artifact_root ;;
        --android-sdk) destination=android_sdk ;;
        --android-ndk) destination=android_ndk ;;
        --vulkan-apk) destination=vulkan_apk ;;
        --vulkan-apk-sha256) destination=vulkan_apk_sha256 ;;
        --expected-vulkan-sha256) destination=expected_vulkan_sha256 ;;
        --tensor-g3-apk) destination=tensor_g3_apk ;;
        --tensor-g3-apk-sha256) destination=tensor_g3_apk_sha256 ;;
        --expected-tensor-g3-sha256) destination=expected_tensor_g3_sha256 ;;
        --vulkan-aar) destination=vulkan_aar ;;
        --expected-vulkan-aar-sha256) destination=expected_vulkan_aar_sha256 ;;
        --expected-vulkan-libjnisdx-sha256) destination=expected_vulkan_libjnisdx_sha256 ;;
        --tensor-g3-aar) destination=tensor_g3_aar ;;
        --expected-tensor-g3-aar-sha256) destination=expected_tensor_g3_aar_sha256 ;;
        --staging-exec-jar) destination=staging_exec_jar ;;
        --expected-staging-jar-sha256) destination=expected_staging_jar_sha256 ;;
        --vulkan-provider-manifest) destination=vulkan_provider_manifest ;;
        --tensor-g3-provider-manifest) destination=tensor_g3_provider_manifest ;;
        --graph-sdk) destination=graph_sdk ;;
        --graph-library) destination=graph_library ;;
        --graph-javacpp-library) destination=graph_javacpp_library ;;
        --graph-header) destination=graph_header ;;
        --canonical-sdz) destination=canonical_sdz ;;
        --canonical-sdz-readme) destination=canonical_sdz_readme ;;
        --kompile-source-id) destination=kompile_source_id ;;
        --dl4j-source-id) destination=dl4j_source_id ;;
        --examples-source-id) destination=examples_source_id ;;
        *) die "unknown option: $option" ;;
      esac
      printf -v "$destination" '%s' "$value"
      shift 2
      ;;
    *) die "unknown argument: $1" ;;
  esac
done

command -v cmake >/dev/null 2>&1 || die "required command not found: cmake"
[[ -n "$kompile_root" ]] ||
  kompile_root="$(CDPATH= cd -- "$script_dir/../.." && pwd -P)"
sibling_root="$(dirname -- "$kompile_root")"
[[ -n "$dl4j_root" ]] || dl4j_root="$sibling_root/deeplearning4j"
[[ -n "$examples_root" ]] || examples_root="$dl4j_root/nd4j/sdx-runtime-examples"
android_root="$script_dir/android"
if [[ -n "$sdx_release_version" || -n "$sdx_release_manifest" || -n "$sdx_release_artifact_root" ]]; then
  [[ -n "$sdx_release_version" && -n "$sdx_release_manifest" && -n "$sdx_release_artifact_root" ]] ||
    die "--sdx-release-version, --sdx-release-manifest, and --sdx-release-artifact-root must be supplied together"
  sdx_artifact_mode=release-consumer
else
  sdx_artifact_mode=source-build
fi
graph_module="$kompile_root/kompile-app/kompile-data/kompile-graphs/kompile-graph-reasoning-local"
staging_module="$kompile_root/kompile-app/kompile-models/kompile-model-staging"

[[ -n "$output" ]] ||
  output="$script_dir/build/kompile-offline-graph-chat-full.zip"
[[ -n "$stage" ]] ||
  stage="$script_dir/build/final-offline-distribution-stage"
[[ -n "$vulkan_apk" ]] ||
  vulkan_apk="$android_root/build/offline-dist/kompile-offline-graph-chat-vulkan.apk"
[[ -n "$tensor_g3_apk" ]] ||
  tensor_g3_apk="$android_root/build/offline-dist/kompile-offline-graph-chat-tensor-g3-pixel-8a.apk"
[[ -n "$vulkan_apk_sha256" ]] || vulkan_apk_sha256="$vulkan_apk.sha256"
[[ -n "$tensor_g3_apk_sha256" ]] || tensor_g3_apk_sha256="$tensor_g3_apk.sha256"
[[ -n "$vulkan_aar" ]] ||
  vulkan_aar="$android_root/app/libs/vulkan/sdx-runtime-android-arm64-vulkan.aar"
[[ -n "$tensor_g3_aar" ]] ||
  tensor_g3_aar="$android_root/app/libs/tensor-g3/sdx-runtime-android-arm64-tensor-g3.aar"
[[ -n "$staging_exec_jar" ]] ||
  staging_exec_jar="$staging_module/target/kompile-model-staging-0.1.0-SNAPSHOT-exec.jar"
[[ -n "$vulkan_provider_manifest" ]] ||
  vulkan_provider_manifest="$dl4j_root/libnd4j/build/mobile/vulkan/native/sdx-runtime-sdk/providers/sdx.vulkan.v1/android-arm64/vulkan/provider.json"
[[ -n "$tensor_g3_provider_manifest" ]] ||
  tensor_g3_provider_manifest="$dl4j_root/libnd4j/build/mobile/tensor-g3/native/sdx-runtime-sdk/providers/sdx.nnapi-tensor-g3.v1/android-arm64/tensor-g3/provider.json"
[[ -n "$graph_sdk" ]] ||
  graph_sdk="$graph_module/target/kompile-graph-reasoning-local-0.1.0-SNAPSHOT-native-sdk.zip"
[[ -n "$graph_library" ]] ||
  graph_library="$graph_module/target/android-aot/jni/arm64-v8a/libkompile_reasoning_android.so"
[[ -n "$graph_javacpp_library" ]] ||
  graph_javacpp_library="$android_root/app/src/main/jniLibs/arm64-v8a/libjnikompile_graph.so"
[[ -n "$graph_header" ]] || graph_header="$graph_module/include/kompile_reasoning.h"
[[ -n "$canonical_sdz" ]] ||
  canonical_sdz="$examples_root/fixture/src/main/resources/models/mlp.sdz"
[[ -n "$canonical_sdz_readme" ]] ||
  canonical_sdz_readme="$examples_root/fixture/src/main/resources/contracts/mlp-fixture.md"

git_source_id() {
  local root=$1 revision state
  revision="$(git -C "$root" rev-parse --verify HEAD 2>/dev/null)" ||
    die "cannot resolve git source identity: $root"
  state="$(git -C "$root" status --porcelain --untracked-files=normal 2>/dev/null)" ||
    die "cannot inspect git source state: $root"
  [[ -z "$state" ]] || revision="$revision+dirty"
  printf 'git:%s' "$revision"
}

if [[ "$mode" == stage || "$mode" == package ]]; then
  [[ -n "$kompile_source_id" ]] || kompile_source_id="$(git_source_id "$kompile_root")"
  [[ -n "$dl4j_source_id" ]] || dl4j_source_id="$(git_source_id "$dl4j_root")"
  [[ -n "$examples_source_id" ]] || examples_source_id="$(git_source_id "$examples_root")"
fi

definitions=(
  "-DKOMPILE_ROOT:PATH=$kompile_root"
  "-DDL4J_ROOT:PATH=$dl4j_root"
  "-DEXAMPLES_ROOT:PATH=$examples_root"
  "-DSDX_ARTIFACT_MODE:STRING=$sdx_artifact_mode"
  "-DSDX_RELEASE_VERSION:STRING=$sdx_release_version"
  "-DSDX_RELEASE_MANIFEST:FILEPATH=$sdx_release_manifest"
  "-DSDX_RELEASE_ARTIFACT_ROOT:PATH=$sdx_release_artifact_root"
  "-DANDROID_SDK:PATH=$android_sdk"
  "-DANDROID_NDK:PATH=$android_ndk"
  "-DVULKAN_APK:FILEPATH=$vulkan_apk"
  "-DVULKAN_APK_SHA256:FILEPATH=$vulkan_apk_sha256"
  "-DEXPECTED_VULKAN_SHA256:STRING=$expected_vulkan_sha256"
  "-DTENSOR_G3_APK:FILEPATH=$tensor_g3_apk"
  "-DTENSOR_G3_APK_SHA256:FILEPATH=$tensor_g3_apk_sha256"
  "-DEXPECTED_TENSOR_G3_SHA256:STRING=$expected_tensor_g3_sha256"
  "-DVULKAN_AAR:FILEPATH=$vulkan_aar"
  "-DEXPECTED_VULKAN_AAR_SHA256:STRING=$expected_vulkan_aar_sha256"
  "-DEXPECTED_VULKAN_LIBJNISDX_SHA256:STRING=$expected_vulkan_libjnisdx_sha256"
  "-DTENSOR_G3_AAR:FILEPATH=$tensor_g3_aar"
  "-DEXPECTED_TENSOR_G3_AAR_SHA256:STRING=$expected_tensor_g3_aar_sha256"
  "-DSTAGING_EXEC_JAR:FILEPATH=$staging_exec_jar"
  "-DEXPECTED_STAGING_JAR_SHA256:STRING=$expected_staging_jar_sha256"
  "-DVULKAN_PROVIDER_MANIFEST:FILEPATH=$vulkan_provider_manifest"
  "-DTENSOR_G3_PROVIDER_MANIFEST:FILEPATH=$tensor_g3_provider_manifest"
  "-DGRAPH_SDK:FILEPATH=$graph_sdk"
  "-DGRAPH_LIBRARY:FILEPATH=$graph_library"
  "-DGRAPH_JAVACPP_LIBRARY:FILEPATH=$graph_javacpp_library"
  "-DGRAPH_HEADER:FILEPATH=$graph_header"
  "-DCANONICAL_SDZ:FILEPATH=$canonical_sdz"
  "-DCANONICAL_SDZ_README:FILEPATH=$canonical_sdz_readme"
  "-DKOMPILE_SOURCE_ID:STRING=$kompile_source_id"
  "-DDL4J_SOURCE_ID:STRING=$dl4j_source_id"
  "-DEXAMPLES_SOURCE_ID:STRING=$examples_source_id"
)

run_mode() {
  local cmake_mode=$1
  shift
  cmake "-DMODE:STRING=$cmake_mode" "${definitions[@]}" "$@" -P "$driver"
}

case "$mode" in
  plan)
    run_mode PLAN "-DOUTPUT_ARCHIVE:FILEPATH=$output"
    ;;
  validate)
    run_mode VALIDATE
    ;;
  stage)
    run_mode STAGE "-DSTAGE:PATH=$stage" "-DRESET_STAGE:BOOL=$reset_stage"
    ;;
  verify-stage)
    run_mode VERIFY_STAGE "-DSTAGE:PATH=$stage"
    ;;
  package-existing-stage)
    run_mode PACKAGE "-DSTAGE:PATH=$stage" "-DOUTPUT_ARCHIVE:FILEPATH=$output"
    ;;
  package)
    run_mode STAGE "-DSTAGE:PATH=$stage" "-DRESET_STAGE:BOOL=$reset_stage"
    run_mode PACKAGE "-DSTAGE:PATH=$stage" "-DOUTPUT_ARCHIVE:FILEPATH=$output"
    ;;
  *)
    die "internal mode error: $mode"
    ;;
esac
